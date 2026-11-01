import 'package:flutter/foundation.dart';

import '../../core/config/app_config.dart';
import '../../models/context.dart';
import '../../models/restaurant.dart';
import '../../services/network_printer.dart';
import '../../services/receipt_printer.dart';
import '../receipt/receipt_text.dart';
import '../receipt/receipt_view.dart' show companyReceiptLines;
import 'slip_text.dart';

/// Slips this device has already put on paper, so the station feed (a kitchen PC
/// printing for every till) never prints the same slip twice — including one
/// this very device sent and printed directly. Bounded: the oldest are dropped.
class PrintedSlips {
  PrintedSlips._();
  static final PrintedSlips instance = PrintedSlips._();

  static const int capacity = 2000;
  final _keys = <String>{};

  bool has(PrepSlip slip) => _keys.contains(slip.key);

  /// Releases a claim whose print failed, so it can be printed again.
  void forget(PrepSlip slip) => _keys.remove(slip.key);

  void remember(PrepSlip slip) {
    _keys.remove(slip.key);
    _keys.add(slip.key);
    while (_keys.length > capacity) {
      _keys.remove(_keys.first);
    }
  }

  @visibleForTesting
  void clear() => _keys.clear();
}

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
  ///
  /// Where a slip goes, in order:
  /// 1. a printer on THIS device chosen for the station (a USB printer);
  /// 2. the station's network address from the ERP;
  /// 3. neither: the station prints for itself — a kitchen PC running OrbixPOS
  ///    picks the slip up from the ERP (ADR-0066 D-6), and the Kitchen Display
  ///    shows it. That is not a failure here.
  ///
  /// [onlyUnprinted]: skip slips this device has printed or is printing — the
  /// station feed's mode. A reprint passes false and always prints.
  Future<List<SlipFailure>> printSlips(List<PrepSlip> slips, {bool onlyUnprinted = false}) async {
    if (slips.isEmpty) return const [];
    final cfg = await loadConfig();
    final failures = <SlipFailure>[];
    for (final slip in slips) {
      if (onlyUnprinted && PrintedSlips.instance.has(slip)) continue;
      final bytes = encodeSlip(buildSlipLines(slip, width: cfg.widthFor(slip.stationUid)));
      try {
        final local = cfg.stationPrinters[slip.stationUid];
        if (local != null && local.isNotEmpty) {
          await _claimWhile(slip, () => spooler.printRaw(local, bytes));
        } else if (slip.printerHost != null && slip.printerHost!.trim().isNotEmpty) {
          await _claimWhile(
              slip, () => network.send(slip.printerHost!.trim(), slip.printerPort, bytes));
        }
      } on ReceiptPrinterException catch (e) {
        failures.add(SlipFailure(slip, e.message));
      } catch (_) {
        failures.add(SlipFailure(slip, 'Could not print the ${slip.stationName} slip.'));
      }
    }
    return failures;
  }

  /// Marks [slip] printed BEFORE sending it, and releases the mark if sending
  /// fails. Marking it only after the printer finished let the station feed on
  /// the same till ("Print ALL slips here") pick the slip up mid-print — a
  /// Bluetooth print takes seconds — and print it a second time (Cafe Tulia
  /// 2026-11-01: "uki send inatoa order 2 badala ya moja").
  Future<void> _claimWhile(PrepSlip slip, Future<void> Function() send) async {
    final had = PrintedSlips.instance.has(slip);
    PrintedSlips.instance.remember(slip);
    try {
      await send();
    } catch (_) {
      if (!had) PrintedSlips.instance.forget(slip);
      rethrow;
    }
  }

  /// The stations among [slips] that this device hands off — no printer chosen
  /// here and no network address in the ERP — in order, without repeats.
  ///
  /// Such a slip is printed only if another device prints for the station (a
  /// kitchen till with "Print ALL slips here"). On a till where nobody set the
  /// station printers it is printed nowhere, so the caller has to say so: a
  /// plain "Sent" read as printed at Tulia Cafe while the kitchen got nothing.
  Future<List<String>> handedOff(List<PrepSlip> slips) async {
    final cfg = await loadConfig();
    final names = <String>[];
    for (final slip in slips) {
      final local = cfg.stationPrinters[slip.stationUid];
      final host = slip.printerHost?.trim() ?? '';
      if ((local == null || local.isEmpty) && host.isEmpty && !names.contains(slip.stationName)) {
        names.add(slip.stationName);
      }
    }
    return names;
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
    final cfg = await loadConfig();
    final local = cfg.stationPrinters[station.uid];
    if ((local == null || local.isEmpty) && station.address.isEmpty) {
      return '${station.name} has no printer on this device or in the ERP. Choose a printer '
          'here, or set its network address in the ERP.';
    }
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
