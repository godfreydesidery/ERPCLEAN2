// dart:io implementation of [NetworkPrinter] — raw TCP, the ESC/POS "port 9100"
// protocol. Runs on Windows, Android and every other target with sockets.
import 'dart:async';
import 'dart:io';

import 'receipt_printer.dart' show ReceiptPrinterException;

bool networkPrintingSupported() => true;

Future<void> sendRaw(
    String host, int port, List<int> bytes, Duration connectTimeout) async {
  Socket socket;
  try {
    socket = await Socket.connect(host, port, timeout: connectTimeout);
  } on SocketException {
    throw ReceiptPrinterException(
        'Could not reach the printer at $host:$port. Check it is on and connected.');
  } on TimeoutException {
    throw ReceiptPrinterException(
        'The printer at $host:$port did not answer. Check it is on and connected.');
  }
  try {
    socket.add(bytes);
    await socket.flush();
  } on SocketException {
    throw ReceiptPrinterException(
        'The printer at $host:$port stopped responding while printing.');
  } finally {
    await socket.close();
    socket.destroy();
  }
}
