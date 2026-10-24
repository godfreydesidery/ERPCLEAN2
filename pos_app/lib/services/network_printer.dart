import 'network_printer_stub.dart'
    if (dart.library.io) 'network_printer_io.dart' as impl;
import 'receipt_printer.dart' show ReceiptPrinterException;

/// Sends raw ESC/POS bytes to a network printer — the kitchen or bar printer of
/// a restaurant (ERP ADR-0065 D-6). Most thermal printers with an Ethernet or
/// Wi-Fi port accept a raw job on TCP port 9100: open a socket, write the bytes,
/// close. No driver and no Windows install is needed, and any till on the LAN
/// can reach it.
///
/// Failures throw [ReceiptPrinterException] with a message a cashier can act on.
class NetworkPrinter {
  const NetworkPrinter();

  /// How long to wait for the printer to accept the connection. A kitchen
  /// printer that is off should fail fast — the waiter is standing there.
  static const Duration connectTimeout = Duration(seconds: 4);

  bool get supported => impl.networkPrintingSupported();

  Future<void> send(String host, int port, List<int> bytes) =>
      impl.sendRaw(host, port, bytes, connectTimeout);
}

/// Re-exported so callers catch one exception type for both printer kinds.
typedef NetworkPrinterException = ReceiptPrinterException;
