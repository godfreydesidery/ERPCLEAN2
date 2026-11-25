import 'dart:convert';

import 'package:shared_preferences/shared_preferences.dart';

import '../../models/auth.dart';

/// Persists the auth tokens + user thumbnail.
///
/// v1 stores them in the app's local preferences (an OS-user-protected file).
/// This is deliberately toolchain-free so the till builds on every target
/// (Windows desktop included) without extra native components. Refresh tokens
/// are short-lived (7 days), rotated and revocable, which bounds the exposure.
///
/// HARDENING (production): swap this implementation for an OS keystore
/// (DPAPI / Keychain / Android Keystore) behind the same interface once the
/// platform's secure-storage toolchain is provisioned on the till image.
class SecureStore {
  SharedPreferences? _prefs;

  Future<SharedPreferences> get _p async =>
      _prefs ??= await SharedPreferences.getInstance();

  static const _kAccess = 'hq.access_token';
  static const _kExpires = 'hq.access_expires';
  static const _kRefresh = 'hq.refresh_token';
  static const _kUser = 'hq.auth_user';

  Future<void> saveSession(TokenBundle b) async {
    final p = await _p;
    await p.setString(_kAccess, b.accessToken);
    await p.setString(_kExpires, b.accessTokenExpiresAt.toIso8601String());
    await p.setString(_kRefresh, b.refreshToken);
    await p.setString(
      _kUser,
      jsonEncode({
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
    final p = await _p;
    final access = p.getString(_kAccess);
    final refresh = p.getString(_kRefresh);
    final userStr = p.getString(_kUser);
    if (access == null || refresh == null || userStr == null) return null;
    final user = AuthUser.fromJson(
        (jsonDecode(userStr) as Map).cast<String, dynamic>());
    return TokenBundle(
      accessToken: access,
      accessTokenExpiresAt:
          DateTime.tryParse(p.getString(_kExpires) ?? '')?.toUtc() ??
              DateTime.now().toUtc(),
      refreshToken: refresh,
      user: user,
    );
  }

  /// The branch [userUid] last chose on this device. Kept across sign-outs:
  /// it is a preference, not a credential, and the app only honours it while
  /// the user is still assigned to that branch.
  Future<String?> readBranchUid(String? userUid) async {
    if (userUid == null) return null;
    return (await _p).getString('$_kBranchPrefix$userUid');
  }

  Future<void> saveBranchUid(String? userUid, String branchUid) async {
    if (userUid == null) return;
    await (await _p).setString('$_kBranchPrefix$userUid', branchUid);
  }

  static const _kBranchPrefix = 'hq.branch.';

  /// Whether [userUid] last chose "All branches".
  Future<bool> readAllBranches(String? userUid) async {
    if (userUid == null) return false;
    return (await _p).getBool('$_kAllPrefix$userUid') ?? false;
  }

  Future<void> saveAllBranches(String? userUid, bool all) async {
    if (userUid == null) return;
    await (await _p).setBool('$_kAllPrefix$userUid', all);
  }

  static const _kAllPrefix = 'hq.allbranches.';

  Future<void> clear() async {
    final p = await _p;
    await p.remove(_kAccess);
    await p.remove(_kExpires);
    await p.remove(_kRefresh);
    await p.remove(_kUser);
  }
}
