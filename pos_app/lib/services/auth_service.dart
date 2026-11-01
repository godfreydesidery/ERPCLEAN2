import '../core/api/api_client.dart';
import '../core/api/token_manager.dart';
import '../core/json.dart';
import '../models/auth.dart';

/// Authentication + identity (§01). Login/refresh persist the token bundle via
/// the [TokenManager]; the client attaches the bearer automatically thereafter.
class AuthService {
  AuthService(this._api, this._tokens);
  final ApiClient _api;
  final TokenManager _tokens;

  Future<TokenBundle> login(String username, String password) async {
    final data = await _api.post('/auth/login',
        body: {'username': username, 'password': password}, noAuth: true);
    final bundle = TokenBundle.fromJson(asMap(data));
    await _tokens.setSession(bundle);
    return bundle;
  }

  /// A waiter's 4-digit PIN sign-in (V111) — waiters only; the server refuses others.
  Future<TokenBundle> pinLogin(String username, String pin) async {
    final data = await _api.post('/auth/pin-login',
        body: {'username': username, 'pin': pin}, noAuth: true);
    final bundle = TokenBundle.fromJson(asMap(data));
    await _tokens.setSession(bundle);
    return bundle;
  }

  /// The signed-in waiter sets or changes their own PIN, proving it with the
  /// current PIN or their password.
  Future<void> changeMyPin(String currentSecret, String newPin) async {
    await _api.post('/auth/me/pin', body: {'currentSecret': currentSecret, 'newPin': newPin});
  }

  Future<Me> me() async => Me.fromJson(asMap(await _api.get('/auth/me')));

  Future<List<UserBranch>> myBranches() async =>
      asList(await _api.get('/auth/my-branches'), UserBranch.fromJson);

  /// "It is me": the signed-in user's own password, re-typed at a till that asks
  /// who is serving on every table (ADR-0067 D-4). Throws [ApiException] with a
  /// user-safe message when it is wrong; a wrong one counts towards lockout.
  Future<void> confirmOwnPassword(String password) async {
    await _api.post('/auth/me/confirm-password', body: {'password': password});
  }

  /// Best-effort logout: revoke the refresh token server-side, then wipe locally
  /// regardless of the network outcome.
  Future<void> logout() async {
    final refresh = _tokens.bundle?.refreshToken;
    if (refresh != null) {
      try {
        await _api.post('/auth/logout', body: {'refreshToken': refresh});
      } catch (_) {
        // ignore — we clear locally below either way
      }
    }
    await _tokens.clear();
  }
}
