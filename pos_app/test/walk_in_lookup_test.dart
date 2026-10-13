import 'package:flutter_test/flutter_test.dart';
import 'package:pos_app/core/api/api_client.dart';
import 'package:pos_app/services/party_service.dart';

/// POS-14: the walk-in default must never fall back to a real customer.
void main() {
  Map<String, dynamic> cust(String uid, String kind, {String status = 'ACTIVE'}) => {
        'id': uid, 'uid': uid, 'code': uid, 'displayName': 'Name $uid',
        'customerKind': kind, 'status': status,
      };

  test('asks the server for CASH_WALK_IN and returns it', () async {
    final api = _FakeApi((q) => [cust('W', 'CASH_WALK_IN')]);
    final c = await PartyService(api).findWalkIn('1');
    expect(c?.uid, 'W');
    expect(api.queries.single['customerKind'], 'CASH_WALK_IN');
  });

  test('no walk-in => null, never another customer', () async {
    final api = _FakeApi((q) => [cust('BAR', 'CREDIT_ACCOUNT')]);
    expect(await PartyService(api).findWalkIn('1'), isNull);
  });

  test('an archived walk-in is not used', () async {
    final api =
        _FakeApi((q) => [cust('W', 'CASH_WALK_IN', status: 'ARCHIVED')]);
    expect(await PartyService(api).findWalkIn('1'), isNull);
  });

  test('old server (filter ignored): walks later pages for the walk-in',
      () async {
    final api = _FakeApi((q) => q['page'] == 0
        ? List.generate(100, (i) => cust('C$i', 'CREDIT_ACCOUNT'))
        : [cust('W', 'CASH_WALK_IN')]);
    final c = await PartyService(api).findWalkIn('1');
    expect(c?.uid, 'W');
    expect(api.queries, hasLength(2));
  });
}

class _FakeApi implements ApiClient {
  _FakeApi(this.answer);
  final List<Map<String, dynamic>> Function(Map<String, dynamic> query) answer;
  final queries = <Map<String, dynamic>>[];

  @override
  Future<dynamic> get(String path, {Map<String, dynamic>? query}) async {
    queries.add(query ?? const {});
    return answer(query ?? const {});
  }

  @override
  dynamic noSuchMethod(Invocation invocation) =>
      throw UnimplementedError(invocation.memberName.toString());
}
