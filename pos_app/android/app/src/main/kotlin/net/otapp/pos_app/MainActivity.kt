package net.otapp.pos_app

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel
import java.util.UUID
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

    /** Sends one job; returns a user-safe message on failure, null when it printed. */
    @SuppressLint("MissingPermission")
    private fun send(address: String, bytes: ByteArray): String? {
        val bt = adapter() ?: return "This device has no Bluetooth."
        if (!permitted()) return "Allow OrbixPOS to use nearby devices (Bluetooth), then print again."
        if (!bt.isEnabled) return "Bluetooth is off. Switch it on and print again."
        return try {
            bt.cancelDiscovery()
            bt.getRemoteDevice(address).createRfcommSocketToServiceRecord(spp).use { socket ->
                socket.connect()
                socket.outputStream.write(bytes)
                socket.outputStream.flush()
                // Let the printer drain its buffer before the link drops, or the tail is lost.
                Thread.sleep(400)
            }
            null
        } catch (e: Exception) {
            "Could not print to the Bluetooth printer. Check it is switched on and paired."
        }
    }
}
