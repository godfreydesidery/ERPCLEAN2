import 'dart:convert';

import 'package:shared_preferences/shared_preferences.dart';

import '../../models/restaurant.dart';

/// Per-install configuration — the ERP host plus the local receipt-printer
/// settings. The `/api/v1` path is identical in every environment (AS-1), so
/// only the scheme+host+port is configurable. Surfaced on the Setup/diagnostics
/// screen and persisted locally (SharedPreferences).
class AppConfig {
  AppConfig({
    required this.baseHost,
    this.receiptPrinterName,
    this.receiptWidthCols = defaultWidthCols,
    this.printMode = defaultPrintMode,
    this.kickDrawer = false,
    Map<String, String>? stationPrinters,
    Map<String, int>? stationWidths,
    Set<String>? agentStations,
    this.askWaiter = false,
    this.restaurantOnly = false,
    List<RestaurantWaiter>? waiters,
  })  : waiters = waiters ?? [],
        stationPrinters = stationPrinters ?? {},
        stationWidths = stationWidths ?? {},
        agentStations = agentStations ?? {};

  /// e.g. `http://localhost:8081` or `https://erp.example.com`. No trailing slash,
  /// no `/api/v1` suffix.
  String baseHost;

  /// The Windows spooler printer to send receipts to. `null`/empty = no printer
  /// configured (the Print button then nudges the operator to Setup).
  String? receiptPrinterName;

  /// Receipt column width: 32 (58 mm) or 48 (80 mm).
  int receiptWidthCols;

  /// `'escpos'` (thermal, with cut) or `'plain'` (text + form feed).
  String printMode;

  /// Fire the cash-drawer kick pulse after printing (ESC/POS only).
  bool kickDrawer;

  /// Restaurant prep-station overrides (ERP ADR-0065 D-4): station uid → the
  /// name of a printer installed on THIS device. A station listed here prints
  /// to that local printer instead of its network address — for a kitchen
  /// printer plugged into this PC by USB. Device-specific, so it lives here and
  /// not on the server.
  final Map<String, String> stationPrinters;

  /// Paper width per station on THIS device (ADR-0066): station uid → 32 (58 mm)
  /// or 48 (80 mm). A station not listed uses [receiptWidthCols].
  final Map<String, int> stationWidths;

  /// Stations whose slips THIS device prints for every till (ADR-0066 D-6) — a
  /// kitchen PC with the kitchen printer on USB. It fetches the station's new
  /// slips from the ERP and prints them on [stationPrinters].
  final Set<String> agentStations;

  /// Shared till: ask which waiter is serving before a new order is opened.
  bool askWaiter;

  /// This till shows only the Restaurant register (J COB 2026-10-30). Set per
  /// till, by a manager, in the restaurant Settings.
  bool restaurantOnly;

  /// The branch's waiters, kept for the sign-in tiles. Refreshed whenever
  /// someone signs in on this till, so the tiles are there while nobody is.
  List<RestaurantWaiter> waiters;

  /// Initial host used until the operator sets one on the Setup screen. Overridable
  /// at build time with `--dart-define=POS_HOST=http://your-erp-host` so a shipped
  /// build can pre-fill the right server (e.g. QA/prod); dev builds fall back to the
  /// local backend the cashier seeds against.
  static const String defaultHost =
      String.fromEnvironment('POS_HOST', defaultValue: 'http://localhost:8081');

  static const int defaultWidthCols = 32;
  static const String defaultPrintMode = 'escpos';

  static const String _kHost = 'cfg.baseHost';
  static const String _kPrinter = 'cfg.receiptPrinterName';
  static const String _kWidth = 'cfg.receiptWidthCols';
  static const String _kMode = 'cfg.printMode';
  static const String _kKick = 'cfg.kickDrawer';
  static const String _kStations = 'cfg.stationPrinters';
  static const String _kWidths = 'cfg.stationWidths';
  static const String _kAgent = 'cfg.agentStations';
  static const String _kAskWaiter = 'cfg.askWaiter';
  static const String _kRestaurantOnly = 'cfg.restaurantOnly';
  static const String _kWaiters = 'cfg.waiters';

  /// Full API base, e.g. `http://localhost:8081/api/v1`.
  String get apiBase => '${_trim(baseHost)}/api/v1';

  static String _trim(String h) =>
      h.endsWith('/') ? h.substring(0, h.length - 1) : h;

  static Future<AppConfig> load() async {
    final prefs = await SharedPreferences.getInstance();
    final printer = prefs.getString(_kPrinter);
    return AppConfig(
      baseHost: prefs.getString(_kHost) ?? defaultHost,
      receiptPrinterName: (printer == null || printer.isEmpty) ? null : printer,
      receiptWidthCols: prefs.getInt(_kWidth) ?? defaultWidthCols,
      printMode: prefs.getString(_kMode) ?? defaultPrintMode,
      kickDrawer: prefs.getBool(_kKick) ?? false,
      stationPrinters: _decodeStations(prefs.getString(_kStations)),
      stationWidths: _decodeWidths(prefs.getString(_kWidths)),
      agentStations: (prefs.getStringList(_kAgent) ?? const []).toSet(),
      askWaiter: prefs.getBool(_kAskWaiter) ?? false,
      restaurantOnly: prefs.getBool(_kRestaurantOnly) ?? false,
      waiters: _decodeWaiters(prefs.getString(_kWaiters)),
    );
  }

  Future<void> save() async {
    final prefs = await SharedPreferences.getInstance();
    await prefs.setString(_kHost, _trim(baseHost));
    final printer = receiptPrinterName;
    if (printer == null || printer.isEmpty) {
      await prefs.remove(_kPrinter);
    } else {
      await prefs.setString(_kPrinter, printer);
    }
    await prefs.setInt(_kWidth, receiptWidthCols);
    await prefs.setString(_kMode, printMode);
    await prefs.setBool(_kKick, kickDrawer);
    final stations = {
      for (final e in stationPrinters.entries)
        if (e.value.trim().isNotEmpty) e.key: e.value.trim(),
    };
    if (stations.isEmpty) {
      await prefs.remove(_kStations);
    } else {
      await prefs.setString(_kStations, jsonEncode(stations));
    }
    final widths = {
      for (final e in stationWidths.entries)
        if (e.value == 32 || e.value == 48) e.key: e.value,
    };
    if (widths.isEmpty) {
      await prefs.remove(_kWidths);
    } else {
      await prefs.setString(_kWidths, jsonEncode(widths));
    }
    await prefs.setStringList(_kAgent, agentStations.toList());
    await prefs.setBool(_kAskWaiter, askWaiter);
    await prefs.setBool(_kRestaurantOnly, restaurantOnly);
    await prefs.setString(_kWaiters, jsonEncode([for (final w in waiters) w.toJson()]));
  }

  /// A corrupt or missing list means "no tiles" — never a crash at start.
  static List<RestaurantWaiter> _decodeWaiters(String? raw) {
    if (raw == null || raw.isEmpty) return [];
    try {
      final l = jsonDecode(raw);
      if (l is! List) return [];
      return [
        for (final e in l)
          if (e is Map<String, dynamic>) RestaurantWaiter.fromJson(e),
      ].where((w) => w.username.isNotEmpty).toList();
    } catch (_) {
      return [];
    }
  }

  /// Slip width for [stationUid] on this device.
  int widthFor(String stationUid) => stationWidths[stationUid] ?? receiptWidthCols;

  static Map<String, int> _decodeWidths(String? raw) {
    if (raw == null || raw.isEmpty) return {};
    try {
      final m = jsonDecode(raw);
      if (m is! Map) return {};
      return {
        for (final e in m.entries)
          if (e.value == 32 || e.value == 48) e.key.toString(): e.value as int,
      };
    } catch (_) {
      return {};
    }
  }

  /// A corrupt or missing value means "no overrides" — never a crash at start.
  static Map<String, String> _decodeStations(String? raw) {
    if (raw == null || raw.isEmpty) return {};
    try {
      final m = jsonDecode(raw);
      if (m is! Map) return {};
      return {
        for (final e in m.entries)
          if (e.value is String && (e.value as String).isNotEmpty)
            e.key.toString(): e.value as String,
      };
    } catch (_) {
      return {};
    }
  }
}
