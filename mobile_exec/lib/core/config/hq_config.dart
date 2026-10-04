import 'package:shared_preferences/shared_preferences.dart';

/// Per-install configuration. The `/api/v1` path is identical in every
/// environment, so only scheme+host+port is configurable.
///
/// Trap carried over from the POS: the host field takes scheme+host ONLY —
/// the app appends `/api/v1` itself. Typing `.../api/v1` yields a double
/// `/api/v1/api/v1` and every call 401s.
class HqConfig {
  HqConfig({required this.baseHost});

  /// e.g. `http://localhost:8081`. No trailing slash, no `/api/v1` suffix.
  String baseHost;

  /// Overridable at build time so a shipped build can pre-fill the right
  /// server: `--dart-define=HQ_HOST=https://erp.example.com` (a customer
  /// brand's `brands/<id>/brand.json` sets it). The generic Orbix build sets it
  /// EMPTY — it does not know whose server it will meet — and the sign-in
  /// screen then asks for the address before anything else.
  static const String defaultHost = String.fromEnvironment(
    'HQ_HOST',
    defaultValue: 'http://localhost:8081',
  );

  static const _kHost = 'hq.base_host';

  /// Empty while there is no host: Dio rejects a relative base URL like a bare
  /// `/api/v1` outright, and the app must still start to ask for the address.
  String get apiBase => hasHost ? '${_trim(baseHost)}/api/v1' : '';

  /// False until a server address is known — a generic build on first launch.
  bool get hasHost => _trim(baseHost).isNotEmpty;

  static String _trim(String h) {
    var v = h.trim();
    while (v.endsWith('/')) {
      v = v.substring(0, v.length - 1);
    }
    return v;
  }

  /// Strips a trailing `/api/v1` a user may have pasted in, so the double-path
  /// trap cannot be typed into the field.
  static String normaliseHost(String input) {
    var v = _trim(input);
    if (v.toLowerCase().endsWith('/api/v1')) {
      v = v.substring(0, v.length - '/api/v1'.length);
    }
    return _trim(v);
  }

  static Future<HqConfig> load() async {
    final p = await SharedPreferences.getInstance();
    return HqConfig(baseHost: p.getString(_kHost) ?? defaultHost);
  }

  Future<void> save() async {
    final p = await SharedPreferences.getInstance();
    await p.setString(_kHost, normaliseHost(baseHost));
  }
}
