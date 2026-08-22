import 'package:dio/dio.dart';

import '../../models/auth.dart';
import '../json.dart';
import '../storage/secure_store.dart';
import 'api_response.dart';
import 'erp_tls.dart';

/// Owns the live token bundle and the **transparent refresh** discipline (AS-6,
/// G-4). Uses a bare Dio (no interceptors) for `/auth/refresh` so refreshing
/// never recurses through the authenticated client. Refresh is single-flight: a
/// burst of expiring requests triggers at most one network refresh.
class TokenManager {
  TokenManager({required String apiBase, required SecureStore store})
      : _store = store,
        _bare = Dio(BaseOptions(
          baseUrl: apiBase,
          contentType: 'application/json',
          connectTimeout: const Duration(seconds: 15),
          receiveTimeout: const Duration(seconds: 20),
        )) {
    applyErpTls(_bare);
  }

  final SecureStore _store;
  final Dio _bare;

  TokenBundle? _bundle;
  Future<bool>? _inflight;

  /// Refresh this many seconds before the access token actually expires.
  static const _skew = Duration(seconds: 30);

  TokenBundle? get bundle => _bundle;
  AuthUser? get user => _bundle?.user;
  bool get hasSession => _bundle != null;

  Future<void> load() async {
    _bundle = await _store.readSession();
  }

  Future<void> setSession(TokenBundle b) async {
    _bundle = b;
    await _store.saveSession(b);
  }

  Future<void> clear() async {
    _bundle = null;
    await _store.clear();
  }

  /// Returns a usable access token, refreshing proactively if it is within the
  /// skew of expiry. Null when there is no session at all.
  Future<String?> accessTokenForRequest() async {
    final b = _bundle;
    if (b == null) return null;
    final now = DateTime.now().toUtc();
    if (b.accessTokenExpiresAt.subtract(_skew).isBefore(now)) {
      await refreshNow();
    }
    return _bundle?.accessToken;
  }

  /// Single-flight refresh. Returns true on success; on failure the session is
  /// cleared (the caller routes to re-login).
  Future<bool> refreshNow() {
    return _inflight ??= _doRefresh().whenComplete(() => _inflight = null);
  }

  Future<bool> _doRefresh() async {
    final refresh = _bundle?.refreshToken;
    if (refresh == null) return false;
    try {
      final res = await _bare.post('/auth/refresh',
          data: {'refreshToken': refresh});
      final data = unwrapData(res.data);
      await setSession(TokenBundle.fromJson(asMap(data)));
      return true;
    } on DioException catch (e) {
      // ONLY a genuine server rejection (401/403) invalidates the session. A
      // transient transport failure (timeout / connection drop on flaky till
      // Wi-Fi) must NOT wipe a still-valid refresh token — keep it so the next
      // request simply retries, instead of forcing a spurious mid-shift logout.
      final status = e.response?.statusCode;
      if (status == 401 || status == 403) await clear();
      return false;
    }
  }
}
