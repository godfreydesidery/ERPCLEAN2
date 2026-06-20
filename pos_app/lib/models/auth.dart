import '../core/json.dart';

/// The authenticated user thumbnail returned with a token (`TokenResponse.AuthUser`).
class AuthUser {
  AuthUser({
    required this.uid,
    required this.username,
    required this.displayName,
    required this.isRoot,
    required this.activeCompanyUid,
    required this.activeBranchUid,
    required this.hasBranch,
  });

  final String uid;
  final String username;
  final String displayName;
  final bool isRoot;
  final String? activeCompanyUid;
  final String? activeBranchUid;
  final bool hasBranch;

  factory AuthUser.fromJson(Map<String, dynamic> j) => AuthUser(
        uid: asStrOr(j['uid']),
        username: asStrOr(j['username']),
        displayName: asStrOr(j['displayName']),
        isRoot: asBool(j['isRoot']),
        activeCompanyUid: asStr(j['activeCompanyUid']),
        activeBranchUid: asStr(j['activeBranchUid']),
        hasBranch: asBool(j['hasBranch']),
      );
}

/// `POST /auth/login` and `/auth/refresh` response.
class TokenBundle {
  TokenBundle({
    required this.accessToken,
    required this.accessTokenExpiresAt,
    required this.refreshToken,
    required this.user,
  });

  final String accessToken;

  /// Absolute expiry (the server sends epoch-seconds).
  final DateTime accessTokenExpiresAt;
  final String refreshToken;
  final AuthUser user;

  factory TokenBundle.fromJson(Map<String, dynamic> j) => TokenBundle(
        accessToken: asStrOr(j['accessToken']),
        accessTokenExpiresAt: DateTime.fromMillisecondsSinceEpoch(
          asIntOr(j['accessTokenExpiresAt']) * 1000,
          isUtc: true,
        ),
        refreshToken: asStrOr(j['refreshToken']),
        user: AuthUser.fromJson(asMap(j['user'])),
      );
}

/// `GET /auth/me` — identity + EFFECTIVE permission codes for the active scope.
/// Drives permission-aware UI (the 403 gate is the real enforcement).
class Me {
  Me({
    required this.uid,
    required this.username,
    required this.displayName,
    required this.isRoot,
    required this.activeCompanyUid,
    required this.activeBranchUid,
    required this.permissions,
  });

  final String uid;
  final String username;
  final String displayName;
  final bool isRoot;
  final String? activeCompanyUid;
  final String? activeBranchUid;
  final Set<String> permissions;

  /// Root bypasses every check (empty permission set but `isRoot`).
  bool can(String code) => isRoot || permissions.contains(code);

  factory Me.fromJson(Map<String, dynamic> j) => Me(
        uid: asStrOr(j['uid']),
        username: asStrOr(j['username']),
        displayName: asStrOr(j['displayName']),
        isRoot: asBool(j['isRoot']),
        activeCompanyUid: asStr(j['activeCompanyUid']),
        activeBranchUid: asStr(j['activeBranchUid']),
        permissions: (j['permissions'] as List? ?? const [])
            .map((e) => e.toString())
            .toSet(),
      );
}

/// `GET /auth/my-branches` — a branch the caller may switch to (self-scoped).
/// NOTE: carries `branchUid` but no numeric branch id (see [Branch] resolution).
class UserBranch {
  UserBranch({
    required this.uid,
    required this.branchUid,
    required this.branchCode,
    required this.branchName,
    required this.companyUid,
    required this.isDefault,
  });

  final String uid;
  final String branchUid;
  final String branchCode;
  final String branchName;
  final String companyUid;
  final bool isDefault;

  factory UserBranch.fromJson(Map<String, dynamic> j) => UserBranch(
        uid: asStrOr(j['uid']),
        branchUid: asStrOr(j['branchUid']),
        branchCode: asStrOr(j['branchCode']),
        branchName: asStrOr(j['branchName']),
        companyUid: asStrOr(j['companyUid']),
        isDefault: asBool(j['isDefault']),
      );
}

/// The seven POS permission codes + the cross-module reads the client gates on.
class Perms {
  Perms._();
  static const saleCreate = 'POS.SALE.CREATE';
  static const saleVoid = 'POS.SALE.VOID';
  static const saleAgeOverride = 'POS.SALE.AGE_OVERRIDE';
  static const sessionOpen = 'POS.SESSION.OPEN';
  static const sessionClose = 'POS.SESSION.CLOSE';
  static const sessionView = 'POS.SESSION.VIEW';
  static const sessionReconcile = 'POS.SESSION.RECONCILE';
  static const tillView = 'POS.TILL.VIEW';
  static const tillManage = 'POS.TILL.MANAGE';
  // cross-module reads
  static const productView = 'PRODUCT.VIEW';
  static const customerView = 'CUSTOMER.VIEW';
  static const agentView = 'AGENT.VIEW';
  static const branchView = 'BRANCH.VIEW';
  static const salesInvoiceView = 'SALES.INVOICE.VIEW';
}
