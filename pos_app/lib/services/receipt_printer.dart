import 'receipt_printer_stub.dart'
    if (dart.library.io) 'receipt_printer_win32.dart' as impl;

/// Thrown when a raw print job cannot be delivered — printer not found, offline,
/// or the platform has no raw-spooler support. [message] is user-safe and can be
/// shown directly in a toast.
class ReceiptPrinterException implements Exception {
  ReceiptPrinterException(this.message);
  final String message;
  @override
  String toString() => message;
}

/// Sends raw bytes (ESC/POS or plain text) to a Windows spooler printer.
///
/// A thin, stateless facade over the platform implementation. The real work runs
/// on Windows desktop via the win32 print-spooler API; everywhere else (web,
/// Android, other desktops) it degrades gracefully — [listPrinters] returns an
/// empty list and [printRaw] throws a friendly [ReceiptPrinterException]. All
/// FFI is guarded so the web/Android build is never broken.
class ReceiptPrinter {
  const ReceiptPrinter();

  /// Names of installed printers, for the Setup dropdown. Empty off Windows.
  List<String> listPrinters() => impl.listPrinters();

  /// Sends [bytes] to the printer named [printerName] using the **RAW** spooler
  /// datatype (so ESC/POS control codes reach the device untouched). Throws
  /// [ReceiptPrinterException] on any failure.
  Future<void> printRaw(String printerName, List<int> bytes) =>
      impl.printRaw(printerName, bytes);
}
