import 'package:flutter/foundation.dart';

import 'api/api_client.dart';
import 'api/api_exception.dart';
import '../models/auth.dart';
import 'api/token_manager.dart';
import 'config/hq_config.dart';
import 'json.dart';
import 'jwt.dart';
import 'scope.dart';
import 'storage/secure_store.dart';

export 'scope.dart' show BranchRef, CompanyRef;

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

  /// The companies the user may act in, keyed by uid. Best-effort: filled from
  /// `/companies/accessible`, plus the sign-in company from the token.
  Map<String, CompanyRef> companies = const {};

  HqConfig get config => _config;

  /// Name of the company the app is working in, when known.
  String? get companyName => companies[companyUid]?.name;

  /// The user's branches span more than one company — screens then name the
  /// company beside the branch so a figure is never read against the wrong one.
  bool get spansCompanies =>
      branches.map((b) => b.companyUid).toSet().length > 1;

  /// "Company · Branch" when the user works across companies, else the branch.
  String get scopeLabel {
    final branch = activeBranch?.name ?? 'No branch';
    final company = companyName;
    return spansCompanies && company != null && company.isNotEmpty
        ? '$company · $branch'
        : branch;
  }

  /// Changes whenever the company or branch does. The shell keys its pages on
  /// it so every screen reloads in the new scope.
  String get scopeKey => scopeKeyOf(companyUid, activeBranch?.uid);

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
    _tokens.rebase(_config.apiBase);
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

    // Land where the user last worked on this device, if still assigned.
    final saved = await _store.readBranchUid(userUid);
    final initial = pickInitialBranch(branches, saved);

    // Ask in the branch's scope, so "my organisation" is the branch's one.
    _api?.branchUidOverride = initial?.uid;
    await _loadCompanies();
    await _enter(initial);
    notifyListeners();
  }

  /// The sign-in company from the token (it carries `companyId` as a claim),
  /// then every company the user may act in from `/companies/accessible`.
  ///
  /// The second call is best-effort: without it the app still works in the
  /// sign-in company, and [_enter] falls back to the branch record for another.
  Future<void> _loadCompanies() async {
    final known = <String, CompanyRef>{};
    final claims = jwtClaims(await _tokens.accessTokenForRequest());
    final tokenCompanyId = asInt(claims?['companyId']);
    final signInCompany = companyUid;
    if (signInCompany != null && tokenCompanyId != null) {
      known[signInCompany] =
          CompanyRef(uid: signInCompany, id: tokenCompanyId, name: '');
    }

    try {
      final org = asMap(await api.get('/organisations/current'));
      final orgUid = asStr(org['uid']);
      if (orgUid != null) {
        final list = await api.get(
          '/companies/accessible',
          query: {'organisationUid': orgUid},
        );
        for (final c in asList(list, CompanyRef.fromJson)) {
          if (c.uid.isEmpty) continue;
          final prior = known[c.uid];
          known[c.uid] = CompanyRef(
            uid: c.uid,
            id: c.id ?? prior?.id,
            name: c.name,
          );
        }
      }
    } on ApiException {
      // Keep the sign-in company; names stay unknown.
    }
    companies = known;
  }

  /// Make [branch] the scope of every request, and the company with it.
  ///
  /// The server moves the request into the branch's company when it sees the
  /// header, so the `companyUid` / `companyId` the app sends must follow too.
  Future<void> _enter(BranchRef? branch) async {
    activeBranch = branch;
    _api?.branchUidOverride = branch?.uid;
    if (branch == null) {
      // No branch assignment: stay in the sign-in company, as before.
      companyId = companies[companyUid]?.id;
      return;
    }

    if (branch.companyUid.isNotEmpty) companyUid = branch.companyUid;
    var id = companies[companyUid]?.id;
    if (id == null) {
      // Not in the accessible list (older server, or the call failed): the
      // branch record names its company id. Needs BRANCH.VIEW; if that is
      // refused too, the id stays unknown and company lists show nothing
      // rather than another company's rows.
      try {
        final b = asMap(await api.get('/branches/uid/${branch.uid}'));
        id = asInt(b['companyId']);
        final bid = asStr(b['id']);
        if (bid != null && bid.isNotEmpty) _branchIds[branch.uid] = bid;
        final cu = companyUid;
        if (id != null && cu != null) {
          companies = {
            ...companies,
            cu: CompanyRef(uid: cu, id: id, name: companies[cu]?.name ?? ''),
          };
        }
      } on ApiException {
        id = null;
      }
    }
    companyId = id;
  }

  final Map<String, String> _branchIds = {};

  /// Numeric id of the active branch (`GET /branches/uid/{uid}`), for the few
  /// lists that only carry a branch id. Null when it cannot be read — the
  /// caller then matches on the branch name instead.
  Future<String?> activeBranchId() async {
    final uid = activeBranch?.uid;
    if (uid == null) return null;
    final known = _branchIds[uid];
    if (known != null) return known;
    try {
      final id = asStr(asMap(await api.get('/branches/uid/$uid'))['id']);
      if (id != null && id.isNotEmpty) _branchIds[uid] = id;
      return id;
    } on ApiException {
      return null;
    }
  }

  Future<void> switchBranch(BranchRef branch) async {
    if (branch.uid == activeBranch?.uid) return;
    await _enter(branch);
    await _store.saveBranchUid(userUid, branch.uid);
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
    companies = const {};
    notifyListeners();
  }
}
