// Non-Windows / web fallback for [ReceiptPrinter]. Imported (via the conditional
// import in receipt_printer.dart) on any target without dart:io / dart:ffi, so
// the web build never pulls in win32 or FFI.
import 'receipt_printer.dart' show ReceiptPrinterException;

/// There is no raw spooler on this platform, so nothing here can ever print.
bool rawPrintingSupported() => false;

/// No spooler here — nothing to enumerate.
List<String> listPrinters() => const <String>[];

/// Raw printing is a Windows-desktop-only capability.
Future<void> printRaw(String printerName, List<int> bytes) async {
  throw ReceiptPrinterException(
      'Receipt printing is only available on the Windows desktop app.');
}
