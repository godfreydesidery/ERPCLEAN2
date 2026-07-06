import 'package:shared_preferences/shared_preferences.dart';

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
  });

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

  /// Default for development: the local dev backend the cashier seeds against.
  static const String defaultHost = 'http://localhost:8081';

  static const int defaultWidthCols = 32;
  static const String defaultPrintMode = 'escpos';

  static const String _kHost = 'cfg.baseHost';
  static const String _kPrinter = 'cfg.receiptPrinterName';
  static const String _kWidth = 'cfg.receiptWidthCols';
  static const String _kMode = 'cfg.printMode';
  static const String _kKick = 'cfg.kickDrawer';

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
  }
}
