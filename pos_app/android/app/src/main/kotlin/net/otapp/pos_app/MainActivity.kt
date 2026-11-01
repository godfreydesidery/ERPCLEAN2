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
    }

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
        val socket = try {
            connect(bt.getRemoteDevice(address))
        } catch (e: Exception) {
            Log.w(TAG, "Bluetooth connect to $address failed", e)
            return "Could not reach the printer. Check it is switched on, paired, and not " +
                "connected to another phone or till."
        }
        return try {
            socket.use {
                val out = it.outputStream
                // Small chunks: cheap printers have small buffers and drop a long burst.
                var at = 0
                while (at < bytes.size) {
                    val n = minOf(512, bytes.size - at)
                    out.write(bytes, at, n)
                    out.flush()
                    at += n
                    if (at < bytes.size) Thread.sleep(15)
                }
                // Let the printer drain its buffer before the link drops, or the tail is lost.
                Thread.sleep((400L + bytes.size / 8).coerceAtMost(2000L))
            }
            null
        } catch (e: Exception) {
            Log.w(TAG, "Bluetooth write to $address failed", e)
            "The printer connected but did not take the receipt. Check its paper and print again."
        }
    }

    private companion object {
        const val TAG = "OrbixPrint"
    }
}
