package net.otapp.pos_app

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import android.util.Log
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * Receipt and slip printing on Android (an Android POS device or a tablet).
 *
 * Android has no print spooler for raw ESC/POS, but almost every Android POS device exposes its
 * built-in thermal printer as a paired Bluetooth printer (Sunmi's "InnerPrinter", and most iMin,
 * Telpo and similar devices), and standalone Bluetooth receipt printers work the same way. Both
 * speak ESC/POS over the Bluetooth serial profile (SPP), so one small bridge covers them all:
 *
 *  - `bonded` lists the paired Bluetooth devices (name + address) for the printer pickers;
 *  - `print` opens an SPP socket to one of them, writes the bytes and closes.
 *
 * No third-party library: the platform API is enough, and a printing dependency would sit between
 * the till and every receipt it prints.
 */
class MainActivity : FlutterActivity() {
    private val channelName = "orbix/bt_printer"
    private val io = Executors.newSingleThreadExecutor()

    /** The Bluetooth Serial Port Profile — what ESC/POS printers listen on. */
    private val spp: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, channelName)
            .setMethodCallHandler { call, result ->
                when (call.method) {
                    "bonded" -> result.success(bonded())
                    "print" -> {
                        val address = call.argument<String>("address")
                        val bytes = call.argument<ByteArray>("bytes")
                        if (address.isNullOrBlank() || bytes == null) {
                            result.error("ARGS", "Nothing to print.", null)
                        } else {
                            io.execute {
                                val error = send(address, bytes)
                                runOnUiThread {
                                    if (error == null) result.success(null)
                                    else result.error("PRINT", error, null)
                                }
                            }
                        }
                    }
                    else -> result.notImplemented()
                }
            }
        // Offline POS (ERP ADR-0068): the queue of unsynced offline sales is encrypted at rest
        // with a key that lives only in the Android Keystore. See OfflineKeystore below.
        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, "orbix/keystore")
            .setMethodCallHandler { call, result ->
                val bytes = call.argument<ByteArray>("bytes")
                if (bytes == null || (call.method != "seal" && call.method != "open")) {
                    result.notImplemented()
                    return@setMethodCallHandler
                }
                crypto.execute {
                    val out = try {
                        if (call.method == "seal") OfflineKeystore.seal(bytes) else OfflineKeystore.open(bytes)
                    } catch (e: Exception) {
                        Log.w(TAG, "Keystore ${call.method} failed", e)
                        null
                    }
                    runOnUiThread {
                        if (out != null) result.success(out)
                        else result.error(
                            "KEYSTORE",
                            if (call.method == "seal") "This device could not encrypt the offline sales."
                            else "The offline sales on this device could not be opened.",
                            null,
                        )
                    }
                }
            }
    }

    /** Keystore work runs here, never behind a slow Bluetooth print on [io]. */
    private val crypto = Executors.newSingleThreadExecutor()

    private fun adapter(): BluetoothAdapter? =
        (getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager?)?.adapter

    /** Android 12+ asks the user for "Nearby devices" before any Bluetooth use. */
    private fun permitted(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    /** Paired devices; asks for the permission (once) when it is missing. Runs on the UI thread. */
    @SuppressLint("MissingPermission")
    private fun bonded(): List<Map<String, String>> {
        val bt = adapter() ?: return emptyList()
        if (!permitted()) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT), 7001)
            }
            return emptyList()
        }
        if (!bt.isEnabled) return emptyList()
        return bt.bondedDevices.map { mapOf("name" to (it.name ?: it.address), "address" to it.address) }
    }

    /**
     * The ways to open the serial link, most standard first. Many printers (ZJiang's ZJ80 family,
     * some built-in "InnerPrinter" services) refuse the secure SDP socket but take an insecure one,
     * or only answer on RFCOMM channel 1 directly. The hidden `createRfcommSocket` is the long-
     * standing last resort for those; it is reached by reflection and skipped where it is blocked.
     */
    @SuppressLint("MissingPermission")
    private val openers: List<(BluetoothDevice) -> BluetoothSocket> = listOf(
        { d -> d.createRfcommSocketToServiceRecord(spp) },
        { d -> d.createInsecureRfcommSocketToServiceRecord(spp) },
        { d ->
            d.javaClass.getMethod("createRfcommSocket", Int::class.javaPrimitiveType)
                .invoke(d, 1) as BluetoothSocket
        },
    )

    /** Which opener last worked per printer, so a till does not retry the failing ones each time. */
    private val working = ConcurrentHashMap<String, Int>()

    /** Opens a connected socket to [device], trying each way to connect in turn. */
    private fun connect(device: BluetoothDevice): BluetoothSocket {
        val first = working[device.address] ?: 0
        val order = listOf(first) + openers.indices.filter { it != first }
        var last: Exception? = null
        for (i in order) {
            val socket = try {
                openers[i](device)
            } catch (e: Exception) {
                last = e
                continue
            }
            try {
                socket.connect()
                working[device.address] = i
                return socket
            } catch (e: Exception) {
                last = e
                try {
                    socket.close()
                } catch (_: Exception) {
                }
            }
        }
        throw last ?: IOException("No way to connect")
    }

    /** Sends one job; returns a user-safe message on failure, null when it printed. */
    @SuppressLint("MissingPermission")
    private fun send(address: String, bytes: ByteArray): String? {
        val bt = adapter() ?: return "This device has no Bluetooth."
        if (!permitted()) return "Allow OrbixPOS to use nearby devices (Bluetooth), then print again."
        if (!bt.isEnabled) return "Bluetooth is off. Switch it on and print again."
        // Nothing here starts a discovery, and on Android 12+ cancelling one needs the "scan"
        // permission this app does not hold: the SecurityException used to fail every print.
        try {
            if (bt.isDiscovering) bt.cancelDiscovery()
        } catch (_: SecurityException) {
        }
        val started = SystemClock.elapsedRealtime()
        val socket = try {
            connect(bt.getRemoteDevice(address))
        } catch (e: Exception) {
            Log.w(TAG, "Bluetooth connect to $address failed", e)
            return "Could not reach the printer. Check it is switched on, paired, and not " +
                "connected to another phone or till."
        }
        var at = 0
        try {
            val out = socket.outputStream
            // Small chunks: cheap printers have small buffers and drop a long burst.
            while (at < bytes.size) {
                val n = minOf(512, bytes.size - at)
                out.write(bytes, at, n)
                out.flush()
                at += n
                if (at < bytes.size) Thread.sleep(15)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Bluetooth write to $address failed after $at of ${bytes.size} bytes", e)
            closeQuietly(socket)
            return "The printer connected but did not take the receipt. Check its paper and print again."
        }
        // Every byte is with the printer now. Nothing below may report a failure: a "failed"
        // job is printed again, and this one is already on paper (Cafe Tulia 2026-11-04).
        try {
            // Let the printer drain its buffer before the link drops, or the tail is lost.
            Thread.sleep((400L + bytes.size / 8).coerceAtMost(2000L))
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        closeQuietly(socket)
        Log.i(TAG, "Printed ${bytes.size} bytes to $address (way ${working[address]}) in " +
            "${SystemClock.elapsedRealtime() - started} ms")
        return null
    }

    private fun closeQuietly(socket: BluetoothSocket) {
        try {
            socket.close()
        } catch (e: Exception) {
            Log.i(TAG, "Bluetooth close failed (job already sent)", e)
        }
    }

    private companion object {
        const val TAG = "OrbixPrint"
    }
}

/**
 * AES-256-GCM with a key generated inside the Android Keystore and never exported (ERP ADR-0068,
 * owner ruling: the offline-sales queue is encrypted with a key in the device's secure key store).
 * Sealed form: a 12-byte IV followed by the ciphertext and its 16-byte tag, so a tampered or
 * truncated blob fails to open instead of decrypting to garbage.
 */
object OfflineKeystore {
    private const val ALIAS = "orbixpos.offline.v1"
    private const val STORE = "AndroidKeyStore"
    private const val IV_LEN = 12
    private const val TAG_BITS = 128

    private fun key(): javax.crypto.SecretKey {
        val ks = java.security.KeyStore.getInstance(STORE).apply { load(null) }
        (ks.getEntry(ALIAS, null) as? java.security.KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val gen = javax.crypto.KeyGenerator.getInstance(
            android.security.keystore.KeyProperties.KEY_ALGORITHM_AES, STORE)
        gen.init(
            android.security.keystore.KeyGenParameterSpec.Builder(
                ALIAS,
                android.security.keystore.KeyProperties.PURPOSE_ENCRYPT or
                    android.security.keystore.KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(android.security.keystore.KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(android.security.keystore.KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return gen.generateKey()
    }

    fun seal(plain: ByteArray): ByteArray {
        val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(javax.crypto.Cipher.ENCRYPT_MODE, key())
        val iv = cipher.iv
        val body = cipher.doFinal(plain)
        return iv + body
    }

    fun open(sealed: ByteArray): ByteArray {
        require(sealed.size > IV_LEN) { "Sealed data is too short." }
        val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            javax.crypto.Cipher.DECRYPT_MODE,
            key(),
            javax.crypto.spec.GCMParameterSpec(TAG_BITS, sealed, 0, IV_LEN),
        )
        return cipher.doFinal(sealed, IV_LEN, sealed.size - IV_LEN)
    }
}
