// Web fallback for [NetworkPrinter]: a browser cannot open a raw TCP socket.
import 'receipt_printer.dart' show ReceiptPrinterException;

bool networkPrintingSupported() => false;

Future<void> sendRaw(
    String host, int port, List<int> bytes, Duration connectTimeout) async {
  throw ReceiptPrinterException(
      'Kitchen and bar printing needs the desktop or Android app.');
}
