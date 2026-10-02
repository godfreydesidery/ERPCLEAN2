import 'package:flutter_test/flutter_test.dart';
import 'package:pos_app/core/api/api_client.dart';
import 'package:pos_app/services/sale_service.dart';

/// "Today's sales" used to list the latest 50 invoices of the whole company —
/// any branch, any day, and not only till sales. It now asks the server for
/// this branch's POS sales since the till's own midnight.
void main() {
  test('asks for this branch, from the till\'s local midnight, as a UTC instant',
      () async {
    final api = _RecordingApi();
    await SaleService(api).listTodaysSales('5',
        branchId: '12', now: DateTime(2026, 10, 2, 14, 30));

    expect(api.path, '/sales-invoices');
    expect(api.query!['companyId'], '5');
    expect(api.query!['branchId'], '12');
    final from = DateTime.parse(api.query!['finalisedFrom'] as String);
    expect(from.isUtc, isTrue);
    expect(from.toLocal(), DateTime(2026, 10, 2));
  });

  test('just after midnight it is already the new day', () async {
    final api = _RecordingApi();
    await SaleService(api).listTodaysSales('5',
        branchId: '12', now: DateTime(2026, 10, 3, 0, 5));
    final from = DateTime.parse(api.query!['finalisedFrom'] as String);
    expect(from.toLocal(), DateTime(2026, 10, 3));
  });
}

class _RecordingApi implements ApiClient {
  String? path;
  Map<String, dynamic>? query;

  @override
  Future<dynamic> get(String path, {Map<String, dynamic>? query}) async {
    this.path = path;
    this.query = query;
    return const <dynamic>[];
  }

  @override
  dynamic noSuchMethod(Invocation invocation) => super.noSuchMethod(invocation);
}
