import '../core/api/api_client.dart';
import '../core/json.dart';
import '../models/auth.dart';
import '../models/context.dart';

/// Resolves the numeric operating context (company id + branch id) from the
/// logged-in user's active uids, using only endpoints a cashier can call
/// (`/organisations/current`, `/companies/accessible`) plus `/branches`.
///
/// NOTE (API gap): there is no auth-only resolver for a branch's numeric id, so
/// branch resolution needs `BRANCH.VIEW`. A clear error is surfaced if missing.
class ContextService {
  ContextService(this._api);
  final ApiClient _api;

  Future<Organisation> currentOrganisation() async =>
      Organisation.fromJson(asMap(await _api.get('/organisations/current')));

  Future<List<Company>> accessibleCompanies(String organisationUid) async =>
      asList(
        await _api.get('/companies/accessible',
            query: {'organisationUid': organisationUid}),
        Company.fromJson,
      );

  Future<List<Branch>> branchesByCompany(String companyUid) async => asList(
        await _api.get('/branches', query: {'companyUid': companyUid}),
        Branch.fromJson,
      );

  /// Builds the full [PosContext]. Throws [StateError] with an actionable message
  /// when the user has no accessible company/branch.
  Future<PosContext> resolve(Me me) async {
    final org = await currentOrganisation();

    final companies = await accessibleCompanies(org.uid);
    if (companies.isEmpty) {
      throw StateError('This user is not assigned to any company.');
    }
    final company = companies.firstWhere(
      (c) => c.uid == me.activeCompanyUid,
      orElse: () => companies.first,
    );

    final branches = await branchesByCompany(company.uid);
    if (branches.isEmpty) {
      throw StateError('No branches found for ${company.name}.');
    }
    final branch = branches.firstWhere(
      (b) => b.uid == me.activeBranchUid,
      orElse: () =>
          branches.firstWhere((b) => b.isDefault, orElse: () => branches.first),
    );

    return PosContext(
        organisationUid: org.uid, company: company, branch: branch);
  }
}
