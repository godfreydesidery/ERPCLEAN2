/// The company + branch every screen reads and writes in.
///
/// The server decides the request's company from the `X-Branch-Uid` header: a
/// branch belongs to exactly one company, so switching to a branch of another
/// company moves the whole request into that company (ADR-0003). The app must
/// therefore keep its own idea of "the company" in step with the branch, or a
/// call that also sends `companyId` asks for one company while the header says
/// another — refused for a normal user, silently mixed for root.
///
/// Everything here is pure so the rules can be tested without a server.
library;

import 'json.dart';

/// A branch the signed-in user is assigned to (`GET /auth/my-branches`).
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

/// A company the user may act in (`GET /companies/accessible`).
class CompanyRef {
  const CompanyRef({required this.uid, required this.id, required this.name});

  factory CompanyRef.fromJson(Map<String, dynamic> j) => CompanyRef(
        uid: asStrOr(j['uid']),
        id: asInt(j['id']),
        name: asStrOr(j['name'], asStrOr(j['code'])),
      );

  final String uid;

  /// Numeric id; several list endpoints still take `companyId`.
  final int? id;
  final String name;
}

/// The branch to land in: the one the user last chose on this device if they
/// are still assigned to it, else their default, else the first.
BranchRef? pickInitialBranch(List<BranchRef> branches, String? savedUid) {
  if (branches.isEmpty) return null;
  if (savedUid != null) {
    for (final b in branches) {
      if (b.uid == savedUid) return b;
    }
  }
  for (final b in branches) {
    if (b.isDefault) return b;
  }
  return branches.first;
}

/// Branches grouped by company, in the order the companies first appear.
/// Used by the picker so two "Main" branches of two companies are told apart.
List<({String companyUid, List<BranchRef> branches})> branchesByCompany(
  List<BranchRef> branches,
) {
  final order = <String>[];
  final groups = <String, List<BranchRef>>{};
  for (final b in branches) {
    final key = b.companyUid;
    if (!groups.containsKey(key)) {
      order.add(key);
      groups[key] = [];
    }
    groups[key]!.add(b);
  }
  return [
    for (final k in order) (companyUid: k, branches: groups[k]!),
  ];
}

/// A stable key for "where am I". Screens are keyed on it so a branch or
/// company switch reloads them instead of leaving the old figures on screen
/// under the new branch's name.
String scopeKeyOf(String? companyUid, String? branchUid) =>
    '${companyUid ?? '-'}|${branchUid ?? '-'}';

/// Thrown instead of calling the server when a figure has no all-branches
/// version on the server yet (stock on hand is per branch only). Screens show
/// [kAllBranchesUnavailable] rather than stitching branches together in the
/// app, so a missing server report stays visible and gets added there.
class AllBranchesUnavailable implements Exception {
  const AllBranchesUnavailable();

  @override
  String toString() => kAllBranchesUnavailable;
}

const kAllBranchesUnavailable = 'Not available for all branches';
const kAllBranchesUnavailableDetail = 'Pick one branch to see this.';
