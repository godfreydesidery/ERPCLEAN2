import 'package:dio/dio.dart';
import 'package:flutter/foundation.dart';

import '../../models/auth.dart';
import '../json.dart';
import '../storage/secure_store.dart';
import 'api_response.dart';
import 'erp_tls.dart';

/// What one `/auth/refresh` attempt established.
enum RefreshOutcome {
  /// A new token bundle is in place.
  refreshed,

  /// The server refused the refresh token (or there was none): the session is
  /// over and the cashier must sign in again.
  rejected,

  /// The server could not be asked — no answer, a timeout, a 5xx. This says
  /// NOTHING about the session, which is kept; it is not a sign-out.
  unavailable,
}

/// The result of [TokenManager.refreshNow]. [failure] is set for
/// [RefreshOutcome.unavailable] so the caller can report the real problem
/// ("can't reach the ERP") instead of a session expiry.
class RefreshResult {
  const RefreshResult._(this.outcome) : failure = null;

  static const refreshed = RefreshResult._(RefreshOutcome.refreshed);
  static const rejected = RefreshResult._(RefreshOutcome.rejected);
  const RefreshResult.unavailable(DioException this.failure)
      : outcome = RefreshOutcome.unavailable;

  final RefreshOutcome outcome;
  final DioException? failure;

  bool get ok => outcome == RefreshOutcome.refreshed;
}

/// Owns the live token bundle and the **transparent refresh** discipline (AS-6,
/// G-4). Uses a bare Dio (no interceptors) for `/auth/refresh` so refreshing
/// never recurses through the authenticated client. Refresh is single-flight: a
/// burst of expiring requests triggers at most one network refresh.
class TokenManager {
  TokenManager({
    required String apiBase,
    required SecureStore store,
    @visibleForTesting HttpClientAdapter? httpAdapter,
  })  : _store = store,
        _bare = Dio(BaseOptions(
          baseUrl: apiBase,
          contentType: 'application/json',
          connectTimeout: const Duration(seconds: 15),
          receiveTimeout: const Duration(seconds: 20),
        )) {
    applyErpTls(_bare);
    if (httpAdapter != null) _bare.httpClientAdapter = httpAdapter;
  }

  final SecureStore _store;
  final Dio _bare;

  TokenBundle? _bundle;
  Future<RefreshResult>? _inflight;

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

  /// Single-flight refresh. Only [RefreshOutcome.rejected] ends the session
  /// (it is cleared here); [RefreshOutcome.unavailable] keeps it.
  Future<RefreshResult> refreshNow() {
    return _inflight ??= _doRefresh().whenComplete(() => _inflight = null);
  }

  Future<RefreshResult> _doRefresh() async {
    final refresh = _bundle?.refreshToken;
    if (refresh == null || refresh.isEmpty) return RefreshResult.rejected;
    try {
      final res = await _bare.post('/auth/refresh',
          data: {'refreshToken': refresh});
      final data = unwrapData(res.data);
      await setSession(TokenBundle.fromJson(asMap(data)));
      return RefreshResult.refreshed;
    } on DioException catch (e) {
      // ONLY a genuine server rejection (401/403) invalidates the session. A
      // transient transport failure (timeout / connection drop on flaky till
      // Wi-Fi, a server mid-restart) must NOT wipe a still-valid refresh token —
      // keep it so the next request simply retries, instead of forcing a
      // spurious mid-shift logout.
      final status = e.response?.statusCode;
      if (status == 401 || status == 403) {
        await clear();
        return RefreshResult.rejected;
      }
      return RefreshResult.unavailable(e);
    } catch (e) {
      // An answer we could not read (a proxy's HTML page, a truncated body) is
      // not a refusal either.
      return RefreshResult.unavailable(DioException(
        requestOptions: RequestOptions(path: '/auth/refresh'),
        error: e,
      ));
    }
  }
}
