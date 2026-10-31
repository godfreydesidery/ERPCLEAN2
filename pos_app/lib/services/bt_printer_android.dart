import 'package:flutter/services.dart';

/// Printing on Android: ESC/POS to a paired Bluetooth printer, through the
/// small native bridge in `MainActivity.kt` (channel `orbix/bt_printer`).
///
/// It covers the built-in printer of most Android POS devices — Sunmi's
/// "InnerPrinter" and most iMin and Telpo devices show up as a paired
/// Bluetooth printer — and any standalone Bluetooth receipt printer. Network
/// printers (IP, port 9100) need none of this; they work as on Windows.
///
/// A printer is named "Name · AA:BB:CC:DD:EE:FF" in the pickers, so the saved
/// setting carries the address to print to and the name a person recognises.
class AndroidBtPrinters {
  AndroidBtPrinters._();

  static const _channel = MethodChannel('orbix/bt_printer');
  static const _sep = ' · ';

  static List<String> _cache = const [];

  /// The paired printers as last read. The pickers are synchronous, so this is
  /// what they show; [refresh] before opening one.
  static List<String> get cached => _cache;

  /// Reads the paired devices. The first call asks for the "Nearby devices"
  /// permission on Android 12+; until it is granted the list is empty.
  static Future<List<String>> refresh() async {
    try {
      final raw = await _channel.invokeListMethod<Map<dynamic, dynamic>>('bonded') ?? const [];
      _cache = [
        for (final d in raw)
          '${d['name'] ?? 'Printer'}$_sep${d['address']}',
      ];
    } on PlatformException {
      _cache = const [];
    } on MissingPluginException {
      _cache = const [];
    }
    return _cache;
  }

  /// The Bluetooth address inside a picker name, or null if it carries none.
  static String? addressOf(String printerName) {
    final i = printerName.lastIndexOf(_sep);
    if (i < 0) return null;
    final address = printerName.substring(i + _sep.length).trim();
    return RegExp(r'^([0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}$').hasMatch(address) ? address : null;
  }

  /// Sends [bytes] to the printer; throws [PlatformException] with a user-safe
  /// message when it cannot print.
  static Future<void> print(String address, List<int> bytes) =>
      _channel.invokeMethod<void>('print', {
        'address': address,
        'bytes': Uint8List.fromList(bytes),
      });
}
