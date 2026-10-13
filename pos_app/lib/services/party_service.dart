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

  /// Finds the company's ACTIVE walk-in/cash customer (the POS default), or
  /// null when it has none.
  ///
  /// POS-14: this used to read the first 100 customers and, when the walk-in
  /// was not among them, fall back to `customers.first` — booking every
  /// anonymous sale to a real account. Now it asks the server for
  /// `customerKind=CASH_WALK_IN` and NEVER substitutes another customer; with
  /// no walk-in the register reads "Select customer".
  ///
  /// A server that predates the filter ignores it and returns every customer,
  /// so the rows are still checked and later pages read (bounded).
  Future<Customer?> findWalkIn(String companyId) async {
    const size = 100;
    for (var page = 0; page < 20; page++) {
      final data = await _api.get('/customers', query: {
        'companyId': companyId,
        'customerKind': 'CASH_WALK_IN',
        'page': page,
        'size': size,
      });
      final rows = asList(data, Customer.fromJson);
      for (final c in rows) {
        if (c.isWalkIn && c.status == 'ACTIVE') return c;
      }
      // Filtered answer (only walk-ins, none active) or the last page: done.
      if (rows.length < size || rows.every((c) => c.isWalkIn)) return null;
    }
    return null;
  }

  Future<List<Agent>> listAgents(String companyId, {int size = 100}) async {
    final data = await _api
        .get('/agents', query: {'companyId': companyId, 'size': size});
    return asList(data, Agent.fromJson);
  }
}
