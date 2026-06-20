import 'dart:convert';

import 'package:flutter_secure_storage/flutter_secure_storage.dart';

import '../../models/auth.dart';

/// Persists the auth tokens + user thumbnail in the OS secure store (DPAPI on
/// Windows, Keystore on Android). On web the implementation degrades to the
/// browser's less-secure store — acceptable for the v1 "Should" web target.
class SecureStore {
  SecureStore([FlutterSecureStorage? storage])
      : _s = storage ?? const FlutterSecureStorage();

  final FlutterSecureStorage _s;

  static const _kAccess = 'pos.access_token';
  static const _kExpires = 'pos.access_expires';
  static const _kRefresh = 'pos.refresh_token';
  static const _kUser = 'pos.auth_user';

  Future<void> saveSession(TokenBundle b) async {
    await _s.write(key: _kAccess, value: b.accessToken);
    await _s.write(
        key: _kExpires, value: b.accessTokenExpiresAt.toIso8601String());
    await _s.write(key: _kRefresh, value: b.refreshToken);
    await _s.write(
      key: _kUser,
      value: jsonEncode({
        'uid': b.user.uid,
        'username': b.user.username,
        'displayName': b.user.displayName,
        'isRoot': b.user.isRoot,
        'activeCompanyUid': b.user.activeCompanyUid,
        'activeBranchUid': b.user.activeBranchUid,
        'hasBranch': b.user.hasBranch,
      }),
    );
  }

  Future<TokenBundle?> readSession() async {
    final access = await _s.read(key: _kAccess);
    final expires = await _s.read(key: _kExpires);
    final refresh = await _s.read(key: _kRefresh);
    final userStr = await _s.read(key: _kUser);
    if (access == null || refresh == null || userStr == null) return null;
    final user = AuthUser.fromJson(
        (jsonDecode(userStr) as Map).cast<String, dynamic>());
    return TokenBundle(
      accessToken: access,
      accessTokenExpiresAt:
          DateTime.tryParse(expires ?? '')?.toUtc() ?? DateTime.now().toUtc(),
      refreshToken: refresh,
      user: user,
    );
  }

  Future<void> clear() async {
    await _s.delete(key: _kAccess);
    await _s.delete(key: _kExpires);
    await _s.delete(key: _kRefresh);
    await _s.delete(key: _kUser);
  }
}
