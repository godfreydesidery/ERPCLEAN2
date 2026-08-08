import '../core/api/api_client.dart';
import '../core/json.dart';
import '../models/step_up.dart';

/// Manager step-up ("supervisor override") verification.
///
/// The call rides on the CASHIER's own bearer token — the manager types only a
/// username and password, and the till stays signed in as the cashier
/// throughout. Nothing here mints, rotates or stores a token.
class StepUpService {
  StepUpService(this._api);
  final ApiClient _api;

  /// Asks the server whether [username]/[password] identifies a user who holds
  /// [permissionCode] in the caller's active company.
  ///
  /// A refusal comes back as a normal 200 with `authorised: false`, so the only
  /// thrown failures are transport/auth ones. [correlationId] is sent as
  /// `X-Request-Id` so the approval can be tied to the exact sale it was for in
  /// the server log.
  Future<AuthorityVerification> verify({
    required String username,
    required String password,
    required String permissionCode,
    String? correlationId,
  }) async {
    final data = await _api.post(
      '/auth/verify-authority',
      body: {
        'username': username,
        'password': password,
        'permissionCode': permissionCode,
      },
      xRequestId: correlationId,
    );
    return AuthorityVerification.fromJson(asMap(data));
  }
}
