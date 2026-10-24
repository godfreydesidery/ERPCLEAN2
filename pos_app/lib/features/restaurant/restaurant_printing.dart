import '../../core/config/app_config.dart';
import '../../models/context.dart';
import '../../models/restaurant.dart';
import '../../services/network_printer.dart';
import '../../services/receipt_printer.dart';
import '../receipt/receipt_text.dart';
import '../receipt/receipt_view.dart' show companyReceiptLines;
import 'slip_text.dart';

/// A slip that did not print, with what to tell the waiter.
class SlipFailure {
  SlipFailure(this.slip, this.message);
  final PrepSlip slip;
  final String message;
}

/// Puts the restaurant's paper where it belongs (ERP ADR-0065 D-4, D-6, D-7).
///
/// * Station slips go to the station's own printer: a printer installed on
///   this device when the Setup override names one, else the station's network
///   address from the ERP.
/// * The bill goes to this till's receipt printer — "inatoka sehemu moja tu kwa
///   cashier".
///
/// A failed slip never undoes the send: the other station's slip may already be
/// cooking. The caller offers a reprint instead.
class RestaurantPrinting {
  const RestaurantPrinting({
    this.network = const NetworkPrinter(),
    this.spooler = const ReceiptPrinter(),
    this.loadConfig = AppConfig.load,
  });

  final NetworkPrinter network;
  final ReceiptPrinter spooler;
  final Future<AppConfig> Function() loadConfig;

  /// Prints every slip; returns the ones that failed (empty = all printed).
  Future<List<SlipFailure>> printSlips(List<PrepSlip> slips) async {
    if (slips.isEmpty) return const [];
    final cfg = await loadConfig();
    final failures = <SlipFailure>[];
    for (final slip in slips) {
      final bytes = encodeEscPos(buildSlipText(slip, width: cfg.receiptWidthCols));
      try {
        final local = cfg.stationPrinters[slip.stationUid];
        if (local != null && local.isNotEmpty) {
          await spooler.printRaw(local, bytes);
        } else if (slip.printerHost != null && slip.printerHost!.trim().isNotEmpty) {
          await network.send(slip.printerHost!.trim(), slip.printerPort, bytes);
        } else {
          failures.add(SlipFailure(slip,
              '${slip.stationName} has no printer set. Add its address in the ERP or pick a printer under Printers.'));
        }
      } on ReceiptPrinterException catch (e) {
        failures.add(SlipFailure(slip, e.message));
      } catch (_) {
        failures.add(SlipFailure(slip, 'Could not print the ${slip.stationName} slip.'));
      }
    }
    return failures;
  }

  /// Prints the bill on this till's receipt printer. Returns null when it
  /// printed, else a message for the cashier.
  Future<String?> printBill(RestaurantBill bill, {PosContext? context}) async {
    final cfg = await loadConfig();
    final printer = cfg.receiptPrinterName;
    if (printer == null || printer.isEmpty) {
      return 'No receipt printer set — configure one in Setup.';
    }
    final company = context?.company;
    final text = buildBillText(bill,
        companyName: company?.name ?? 'OrbixPOS',
        branchName: context?.branch.name ?? '',
        width: cfg.receiptWidthCols,
        companyDetailLines: company == null ? const [] : companyReceiptLines(company));
    final bytes = cfg.printMode == 'plain' ? encodePlainText(text) : encodeEscPos(text);
    try {
      await spooler.printRaw(printer, bytes);
      return null;
    } on ReceiptPrinterException catch (e) {
      return e.message;
    } catch (_) {
      return 'Could not print the bill.';
    }
  }

  /// A one-line test page for a station, from the Printers dialog.
  Future<String?> testStation(PrepStation station) async {
    final slip = PrepSlip(
      isVoid: false,
      stationUid: station.uid,
      stationName: station.name,
      printerHost: station.printerHost,
      printerPort: station.printerPort,
      orderNumber: 'TEST',
      takeaway: true,
      round: 1,
      reprint: false,
      byName: 'Printer test',
      at: DateTime.now(),
      lines: [PrepSlipLine(productName: 'Test print', quantity: 1)],
    );
    final failures = await printSlips([slip]);
    return failures.isEmpty ? null : failures.first.message;
  }
}
