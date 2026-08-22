import 'package:flutter/foundation.dart';

import 'api/api_client.dart';
import 'api/api_exception.dart';
import '../models/auth.dart';
import 'api/token_manager.dart';
import 'config/hq_config.dart';
import 'json.dart';
import 'jwt.dart';
import 'storage/secure_store.dart';

/// A branch the signed-in user is assigned to.
class BranchRef {
  const BranchRef({
    required this.uid,
    required this.name,
    required this.code,
    required this.companyUid,
    required this.isDefault,
  });

  factory BranchRef.fromJson(Map<String, dynamic> j) => BranchRef(
        uid: asStrOr(j['branchUid']),
        name: asStrOr(j['branchName']),
        code: asStrOr(j['branchCode']),
        companyUid: asStrOr(j['companyUid']),
        isDefault: asBool(j['isDefault']),
      );

  final String uid;
  final String name;
  final String code;
  final String companyUid;
  final bool isDefault;
}

/// The signed-in user, their permissions and the active company/branch.
///
/// Every screen reads this. Nothing here is cached across sign-outs: the token
/// manager clears storage and this object resets with it.
class Session extends ChangeNotifier {
  Session._(this._config, this._tokens, this._store);

  static Future<Session> create() async {
    final config = await HqConfig.load();
    final store = SecureStore();
    final tokens = TokenManager(apiBase: config.apiBase, store: store);
    await tokens.load();
    return Session._(config, tokens, store);
  }

  HqConfig _config;
  final TokenManager _tokens;
  final SecureStore _store;

  ApiClient? _api;

  String? userUid;
  String? username;
  String? displayName;
  bool isRoot = false;
  String? companyUid;

  /// Numeric company id. Several list endpoints still take `companyId` as a
  /// query parameter rather than a uid, so the app resolves it once at sign-in.
  int? companyId;

  List<String> permissions = const [];
  List<BranchRef> branches = const [];
  BranchRef? activeBranch;

  HqConfig get config => _config;

  bool get signedIn => _api != null && userUid != null;

  /// Permission check mirroring the backend: root short-circuits to allowed.
  /// Screens gate on this so a user without a code sees a clear message rather
  /// than a 403 that reads as "the app is broken".
  bool can(String code) => isRoot || permissions.contains(code);

  ApiClient get api {
    final a = _api;
    if (a == null) {
      throw StateError('Not signed in');
    }
    return a;
  }

  void _buildClient() {
    _api = ApiClient(apiBase: _config.apiBase, tokens: _tokens)
      ..branchUidOverride = activeBranch?.uid;
  }

  Future<void> setHost(String host) async {
    _config = HqConfig(baseHost: HqConfig.normaliseHost(host));
    await _config.save();
    notifyListeners();
  }

  /// Sign in, then load identity, permissions and branch assignments.
  Future<void> signIn(String username, String password) async {
    final bare = ApiClient(apiBase: _config.apiBase, tokens: _tokens);
    final res = await bare.post(
      '/auth/login',
      body: {'username': username, 'password': password},
      noAuth: true,
    );
    final map = asMap(res);
    await _tokens.setSession(TokenBundle.fromJson(map));

    _buildClient();
    await _loadIdentity();
  }

  Future<void> _loadIdentity() async {
    final me = asMap(await api.get('/auth/me'));
    userUid = asStr(me['uid']);
    username = asStr(me['username']);
    displayName = asStr(me['displayName']);
    isRoot = asBool(me['isRoot']);
    companyUid = asStr(me['activeCompanyUid']);
    permissions = (me['permissions'] as List?)
            ?.map((e) => e.toString())
            .toList(growable: false) ??
        const [];

    final list = await api.get('/auth/my-branches');
    branches = asList(list, BranchRef.fromJson)
        .where((b) => b.uid.isNotEmpty)
        .toList(growable: false);

    activeBranch = branches.where((b) => b.isDefault).firstOrNull ??
        (branches.isEmpty ? null : branches.first);
    _api?.branchUidOverride = activeBranch?.uid;

    await _resolveCompanyId();
    notifyListeners();
  }

  /// `/auth/me` gives the company **uid**, but several list endpoints want the
  /// numeric id. `/companies` cannot supply it — that endpoint requires an
  /// `organisationUid` the app does not have — so the id is read from the
  /// access token, which carries `companyId` as a claim.
  Future<void> _resolveCompanyId() async {
    final claims = jwtClaims(await _tokens.accessTokenForRequest());
    companyId = asInt(claims?['companyId']);
  }

  Future<void> switchBranch(BranchRef branch) async {
    activeBranch = branch;
    _api?.branchUidOverride = branch.uid;
    notifyListeners();
  }

  /// Restore a session saved on this device, if the refresh token still works.
  Future<bool> restore() async {
    final saved = await _store.readSession();
    if (saved == null || saved.refreshToken.isEmpty) return false;
    _buildClient();
    try {
      await _loadIdentity();
      return signedIn;
    } on ApiException {
      await signOut();
      return false;
    }
  }

  Future<void> signOut() async {
    await _tokens.clear();
    _api = null;
    userUid = null;
    username = null;
    displayName = null;
    isRoot = false;
    companyUid = null;
    companyId = null;
    permissions = const [];
    branches = const [];
    activeBranch = null;
    notifyListeners();
  }
}

extension _FirstOrNull<T> on Iterable<T> {
  T? get firstOrNull => isEmpty ? null : first;
}
