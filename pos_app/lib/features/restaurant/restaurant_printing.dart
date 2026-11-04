import 'dart:async';

import 'package:flutter/foundation.dart';
import 'package:shared_preferences/shared_preferences.dart';

import '../../core/config/app_config.dart';
import '../../models/context.dart';
import '../../models/restaurant.dart';
import '../../services/network_printer.dart';
import '../../services/receipt_printer.dart';
import '../receipt/receipt_text.dart';
import '../receipt/receipt_view.dart' show companyReceiptLines;
import 'slip_text.dart';

/// Slips this device has put on paper, or is putting on paper right now, so
/// no slip prints twice on this device: not when the station feed (a till
/// printing for every till) fetches a slip this till's own Send is printing,
/// nor the other way round, nor after the app is restarted.
///
/// Recently printed slips are saved on the device: the feed's first asks after a
/// restart reach back a minute ([StationPrintAgent.overlap]), and without the
/// saved list it printed that minute again (Cafe Tulia 2026-11-04).
class PrintedSlips {
  PrintedSlips._();
  static final PrintedSlips instance = PrintedSlips._();

  static const int capacity = 2000;

  /// How long a printed slip is kept on disk: well past the feed's overlap.
  static const Duration keepOnDisk = Duration(minutes: 10);

  static const String _prefsKey = 'restaurant.printedSlips';

  /// Key -> when it printed, oldest first.
  final _printed = <String, DateTime>{};

  /// Key -> the print under way; completes with null when it printed, else the
  /// message for the waiter. Never completes with an error.
  final _inFlight = <String, Future<String?>>{};

  Future<void>? _restoring;

  bool has(PrepSlip slip) => _printed.containsKey(slip.key);

  /// Whether a print of [slip] is under way on this device.
  bool printing(PrepSlip slip) => _inFlight.containsKey(slip.key);

  /// Releases a slip so it can be printed again.
  void forget(PrepSlip slip) => _printed.remove(slip.key);

  void remember(PrepSlip slip) {
    _printed.remove(slip.key);
    _printed[slip.key] = DateTime.now();
    while (_printed.length > capacity) {
      _printed.remove(_printed.keys.first);
    }
    _save();
  }

  /// Loads the slips printed shortly before the app was last closed. Safe to
  /// call often: it reads the device once.
  Future<void> restore() => _restoring ??= _load();

  Future<void> _load() async {
    try {
      final prefs = await SharedPreferences.getInstance();
      final cutoff = DateTime.now().subtract(keepOnDisk);
      for (final entry in prefs.getStringList(_prefsKey) ?? const <String>[]) {
        final bar = entry.indexOf('|');
        if (bar <= 0) continue;
        final ms = int.tryParse(entry.substring(0, bar));
        if (ms == null) continue;
        final at = DateTime.fromMillisecondsSinceEpoch(ms);
        final key = entry.substring(bar + 1);
        if (at.isAfter(cutoff) && !_printed.containsKey(key)) _printed[key] = at;
      }
    } catch (_) {
      // No saved list (first run, or storage unavailable): nothing to skip.
    }
  }

  Future<void> _save() async {
    try {
      final cutoff = DateTime.now().subtract(keepOnDisk);
      final recent = [
        for (final e in _printed.entries)
          if (e.value.isAfter(cutoff)) '${e.value.millisecondsSinceEpoch}|${e.key}',
      ];
      final prefs = await SharedPreferences.getInstance();
      await prefs.setStringList(_prefsKey, recent);
    } catch (_) {
      // Saving is a safeguard for restarts; printing does not depend on it.
    }
  }

  @visibleForTesting
  void clear() {
    _printed.clear();
    _inFlight.clear();
    _restoring = null;
  }
}

/// How [RestaurantPrinting.printSlips] treats a slip already printed here.
enum SlipPrintMode {
  /// Send, Fire, Void, Cancel and a retry after a failure: each slip once. A
  /// slip already printed is not printed again; one being printed (by the
  /// station feed) is waited for, and its outcome is this slip's outcome.
  once,

  /// The station feed: skips slips printed or being printed here, without
  /// waiting: the feed asks again in a few seconds anyway.
  feed,

  /// Reprint and the test page: prints, whatever was printed before.
  reprint,
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
  /// 1. a printer on THIS device chosen for the station (a USB or Bluetooth printer);
  /// 2. the station's network address from the ERP;
  /// 3. neither: the station prints for itself: a kitchen PC running OrbixPOS
  ///    picks the slip up from the ERP (ADR-0066 D-6), and the Kitchen Display
  ///    shows it. That is not a failure here.
  ///
  /// Every slip of the batch this call will print is claimed BEFORE the first
  /// one goes to a printer. A Bluetooth print takes seconds; claiming each slip
  /// only at its turn left the bar slip unclaimed while the kitchen slip
  /// printed, and the station feed on the same till printed it as well. And
  /// Send used to print a slip the feed was already printing. Together they put
  /// both slips on paper twice (Cafe Tulia 2026-11-04).
  ///
  /// [onPrinted] is told of each slip this call put on paper itself.
  Future<List<SlipFailure>> printSlips(List<PrepSlip> slips,
      {SlipPrintMode mode = SlipPrintMode.once, void Function(PrepSlip slip)? onPrinted}) async {
    if (slips.isEmpty) return const [];
    final cfg = await loadConfig();
    final registry = PrintedSlips.instance;
    await registry.restore();

    // One pass with no await in it: nothing else on this device can claim a
    // slip between the check and the claim.
    final plan = <(PrepSlip, _Step)>[];
    final mine = <String>{};
    for (final slip in slips) {
      final route = _routeOf(cfg, slip);
      if (route == null) continue; // handed off: another device prints it
      if (slip.lineUids.isEmpty) {
        // No line ids (a server before 2026-10-24, the test page): nothing tells
        // this slip from the station's next one, so it is printed as before —
        // except by the feed, which would print it again on every ask.
        if (mode == SlipPrintMode.feed) continue;
        plan.add((slip, _Step.print(route, cfg.widthFor(slip.stationUid), Completer<String?>())));
        continue;
      }
      final key = slip.key;
      if (mode != SlipPrintMode.reprint) {
        if (registry.has(slip) || mine.contains(key)) {
          _trace(mode, slip, 'skipped: already printed here');
          continue;
        }
        final running = registry._inFlight[key];
        if (running != null) {
          if (mode == SlipPrintMode.feed) {
            _trace(mode, slip, 'skipped: printing now');
            continue;
          }
          plan.add((slip, _Step.join(running)));
          continue;
        }
      }
      final done = Completer<String?>();
      registry._inFlight.putIfAbsent(key, () => done.future);
      mine.add(key);
      plan.add((slip, _Step.print(route, cfg.widthFor(slip.stationUid), done)));
    }

    final failures = <SlipFailure>[];
    for (final (slip, step) in plan) {
      final String? error;
      final join = step.join;
      if (join != null) {
        _trace(mode, slip, 'waiting for the print under way');
        error = await join;
        _trace(mode, slip, error == null ? 'printed by that print' : 'that print failed');
      } else {
        error = await _printOne(mode, slip, step);
        if (error == null) onPrinted?.call(slip);
      }
      if (error != null) failures.add(SlipFailure(slip, error));
    }
    return failures;
  }

  /// Sends one claimed slip; returns null when it printed, else the message.
  /// Settles the claim either way.
  Future<String?> _printOne(SlipPrintMode mode, PrepSlip slip, _Step step) async {
    final registry = PrintedSlips.instance;
    final done = step.done!;
    final route = step.route!;
    final watch = Stopwatch()..start();
    String? error;
    try {
      final bytes = encodeSlip(buildSlipLines(slip, width: step.width!));
      if (route.local != null) {
        await spooler.printRaw(route.local!, bytes);
      } else {
        await network.send(route.host!, slip.printerPort, bytes);
      }
      if (slip.lineUids.isNotEmpty) registry.remember(slip);
    } on ReceiptPrinterException catch (e) {
      error = e.message;
    } catch (_) {
      error = 'Could not print the ${slip.stationName} slip.';
    }
    if (identical(registry._inFlight[slip.key], done.future)) {
      registry._inFlight.remove(slip.key);
    }
    done.complete(error);
    _trace(mode, slip,
        error == null ? 'printed in ${watch.elapsedMilliseconds} ms' : 'failed: $error');
    return error;
  }

  /// Where [slip] prints from this device, or null when it is handed off.
  static _Route? _routeOf(AppConfig cfg, PrepSlip slip) {
    final local = cfg.stationPrinters[slip.stationUid];
    if (local != null && local.isNotEmpty) return _Route(local: local);
    final host = slip.printerHost?.trim() ?? '';
    if (host.isNotEmpty) return _Route(host: host);
    return null;
  }

  /// One line per print decision in the device log (`adb logcat -s flutter`),
  /// so a slip on paper twice can be traced to who printed it and why.
  static void _trace(SlipPrintMode mode, PrepSlip slip, String what) {
    debugPrint('OrbixPrint ${mode.name} ${slip.isVoid ? 'VOID ' : ''}${slip.orderNumber} '
        'round ${slip.round} ${slip.stationName} [${slip.key}]: $what');
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
    final failures = await printSlips([slip], mode: SlipPrintMode.reprint);
    return failures.isEmpty ? null : failures.first.message;
  }
}

class _Route {
  const _Route({this.local, this.host});
  final String? local;
  final String? host;
}

/// What [RestaurantPrinting.printSlips] does with one slip: print it under its
/// own claim, or wait for the print already under way.
class _Step {
  _Step.print(_Route this.route, int this.width, Completer<String?> this.done) : join = null;
  _Step.join(Future<String?> this.join)
      : route = null,
        width = null,
        done = null;

  final _Route? route;
  final int? width;
  final Completer<String?>? done;
  final Future<String?>? join;
}
