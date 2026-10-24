import 'dart:async';

import 'package:flutter/foundation.dart';

import '../../core/config/app_config.dart';
import '../../models/restaurant.dart';
import 'restaurant_printing.dart';

/// One line of the agent's log, for the kitchen printer screen.
class AgentLogEntry {
  AgentLogEntry(this.at, this.text, {this.failed = false});
  final DateTime at;
  final String text;
  final bool failed;
}

/// Fetches a station's slips from the ERP.
typedef StationSlipFetch = Future<({DateTime until, List<PrepSlip> slips})> Function(
    String stationUid, DateTime? since);

/// Prints, on THIS device, the slips of the stations it is set to print for —
/// whichever till sent them (ADR-0066 D-6). This is how a kitchen whose printer is
/// plugged into a PC by USB gets its paper: OrbixPOS runs on that PC, asks the
/// ERP every few seconds for new slips, and prints them.
///
/// A small overlap on every ask covers an order committed a moment late; slips
/// already printed here are skipped by key ([PrintedSlips]). A slip that fails to
/// print is retried on the next tick rather than lost.
class StationPrintAgent {
  StationPrintAgent({
    required this.fetch,
    this.printing = const RestaurantPrinting(),
    this.loadConfig = AppConfig.load,
    this.interval = const Duration(seconds: 5),
  });

  final StationSlipFetch fetch;
  final RestaurantPrinting printing;
  final Future<AppConfig> Function() loadConfig;
  final Duration interval;

  /// How far back each ask reaches before the last answer.
  static const Duration overlap = Duration(seconds: 60);

  final Map<String, DateTime> _cursor = {};
  final List<PrepSlip> _retry = [];
  Timer? _timer;
  bool _running = false;

  /// The last things the agent did, newest first — shown on the kitchen screen.
  final ValueNotifier<List<AgentLogEntry>> log = ValueNotifier(const []);

  bool get active => _timer != null;

  void start() {
    _timer ??= Timer.periodic(interval, (_) => tick());
    tick();
  }

  void stop() {
    _timer?.cancel();
    _timer = null;
  }

  /// One round: every agent station, then any retries. Never throws.
  @visibleForTesting
  Future<void> tick() async {
    if (_running) return;
    _running = true;
    try {
      final cfg = await loadConfig();
      for (final station in cfg.agentStations) {
        try {
          final since = _cursor[station];
          final res = await fetch(station, since?.subtract(overlap));
          _cursor[station] = res.until;
          for (final slip in res.slips) {
            if (!PrintedSlips.instance.has(slip) && !_retry.any((r) => r.key == slip.key)) {
              _retry.add(slip);
            }
          }
        } catch (_) {
          // The ERP did not answer; the cursor stays, so the next tick asks again.
        }
      }
      if (_retry.isEmpty) return;
      final batch = List.of(_retry);
      _retry.clear();
      final failures = await printing.printSlips(batch);
      final failedKeys = {for (final f in failures) f.slip.key};
      for (final slip in batch) {
        final failed = failedKeys.contains(slip.key);
        if (failed) _retry.add(slip);
        _note(
            failed
                ? '${slip.stationName}: ${slip.orderNumber} did not print — retrying'
                : '${slip.isVoid ? 'VOID ' : ''}${slip.stationName}: '
                    '${slip.takeaway ? 'Takeaway' : 'Table ${slip.tableName ?? ''}'} · ${slip.orderNumber}',
            failed: failed);
      }
    } finally {
      _running = false;
    }
  }

  void _note(String text, {bool failed = false}) {
    final next = [AgentLogEntry(DateTime.now(), text, failed: failed), ...log.value];
    log.value = next.length > 50 ? next.sublist(0, 50) : next;
  }

  void dispose() {
    stop();
    log.dispose();
  }
}
