import '../core/api/api_client.dart';
import '../core/json.dart';
import '../models/parties.dart';

/// Customers + sales agents (§05). A numeric customer id and agent id are
/// required on every sale (AS-3); the POS defaults to the walk-in customer.
class PartyService {
  PartyService(this._api);
  final ApiClient _api;

  Future<List<Customer>> searchCustomers(
    String companyId, {
    String? q,
    int page = 0,
    int size = 50,
  }) async {
    final data = await _api.get('/customers', query: {
      'companyId': companyId,
      'q': q,
      'page': page,
      'size': size,
    });
    return asList(data, Customer.fromJson);
  }

  /// Finds the company's walk-in/cash customer (the POS default). Falls back to
  /// the first customer when none is explicitly flagged CASH_WALK_IN.
  Future<Customer?> findWalkIn(String companyId) async {
    final customers = await searchCustomers(companyId, size: 100);
    if (customers.isEmpty) return null;
    return customers.firstWhere(
      (c) => c.isWalkIn,
      orElse: () => customers.first,
    );
  }

  Future<List<Agent>> listAgents(String companyId, {int size = 100}) async {
    final data = await _api
        .get('/agents', query: {'companyId': companyId, 'size': size});
    return asList(data, Agent.fromJson);
  }
}
