import 'package:shared_preferences/shared_preferences.dart';

/// Per-install configuration — chiefly the ERP host. The `/api/v1` path is
/// identical in every environment (AS-1), so only the scheme+host+port is
/// configurable. Surfaced on the Setup/diagnostics screen and persisted locally.
class AppConfig {
  AppConfig({required this.baseHost});

  /// e.g. `http://localhost:8081` or `https://erp.example.com`. No trailing slash,
  /// no `/api/v1` suffix.
  String baseHost;

  /// Initial host used until the operator sets one on the Setup screen. Overridable
  /// at build time with `--dart-define=POS_HOST=http://your-erp-host` so a shipped
  /// build can pre-fill the right server (e.g. QA/prod); dev builds fall back to the
  /// local backend the cashier seeds against.
  static const String defaultHost =
      String.fromEnvironment('POS_HOST', defaultValue: 'http://localhost:8081');

  static const String _kHost = 'cfg.baseHost';

  /// Full API base, e.g. `http://localhost:8081/api/v1`.
  String get apiBase => '${_trim(baseHost)}/api/v1';

  static String _trim(String h) =>
      h.endsWith('/') ? h.substring(0, h.length - 1) : h;

  static Future<AppConfig> load() async {
    final prefs = await SharedPreferences.getInstance();
    return AppConfig(baseHost: prefs.getString(_kHost) ?? defaultHost);
  }

  Future<void> save() async {
    final prefs = await SharedPreferences.getInstance();
    await prefs.setString(_kHost, _trim(baseHost));
  }
}
