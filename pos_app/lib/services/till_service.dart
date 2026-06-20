import '../core/api/api_client.dart';
import '../core/json.dart';
import '../models/pos.dart';

/// POS till administration (§07).
class TillService {
  TillService(this._api);
  final ApiClient _api;

  Future<List<PosTill>> listByBranch(String companyId, String branchId) async {
    final data = await _api.get('/pos/tills',
        query: {'companyId': companyId, 'branchId': branchId});
    return asList(data, PosTill.fromJson);
  }

  Future<PosTill> getByUid(String uid) async =>
      PosTill.fromJson(asMap(await _api.get('/pos/tills/uid/$uid')));

  /// Manager-only (`POS.TILL.MANAGE`). [cashBankAccountUid] is optional — the
  /// server defaults to the company's default cash account.
  Future<PosTill> create(
    String companyUid,
    String branchId,
    String name, {
    String? cashBankAccountUid,
  }) async {
    final data = await _api.post('/pos/tills', body: {
      'companyUid': companyUid,
      'branchId': branchId,
      'name': name,
      'cashBankAccountUid': cashBankAccountUid,
    });
    return PosTill.fromJson(asMap(data));
  }

  Future<void> deactivate(String uid) async =>
      _api.delete('/pos/tills/uid/$uid');
}
