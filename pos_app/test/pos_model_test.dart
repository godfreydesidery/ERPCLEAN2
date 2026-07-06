import 'package:flutter_test/flutter_test.dart';
import 'package:pos_app/models/enums.dart';
import 'package:pos_app/models/pos.dart';

void main() {
  group('PosTill.hasOpenSession', () {
    test('parses true when the till is occupied', () {
      final t = PosTill.fromJson({
        'id': '1',
        'uid': 'tilluid',
        'companyId': '10',
        'branchId': '20',
        'code': 'T1',
        'name': 'Till 1',
        'status': 'ACTIVE',
        'hasOpenSession': true,
      });
      expect(t.hasOpenSession, isTrue);
    });

    test('defaults to false when the field is absent', () {
      final t = PosTill.fromJson({
        'id': '1',
        'uid': 'tilluid',
        'companyId': '10',
        'branchId': '20',
        'code': 'T1',
        'name': 'Till 1',
        'status': 'ACTIVE',
      });
      expect(t.hasOpenSession, isFalse);
    });
  });

  group('XRead tender breakdown (#227)', () {
    test('parses cashTenderAmount and per-tender subtotals', () {
      final x = XRead.fromJson({
        'sessionUid': 's1',
        'openingFloatAmount': '100.00',
        'totalSalesAmount': '46020.00',
        'cashTenderAmount': '32020.00',
        'totalPayoutsNetAmount': '0.00',
        'expectedCashAmount': '32120.00',
        'invoiceCount': 7,
        'tenderSubtotals': [
          {'tenderType': 'CASH', 'amount': '32020.00'},
          {'tenderType': 'MOBILE_MONEY', 'amount': '14000.00'},
        ],
      });
      // Gross turnover (all tenders) stays distinct from cash retained.
      expect(x.totalSalesAmount, 46020.00);
      expect(x.cashTenderAmount, 32020.00);
      // Expected cash = float + cash sales - payouts, ties out to cash only.
      expect(x.expectedCashAmount, 32120.00);
      expect(x.tenderSubtotals, hasLength(2));
      expect(x.tenderSubtotals.first.tenderType, TenderType.cash);
      expect(x.tenderSubtotals.first.amount, 32020.00);
      expect(x.tenderSubtotals[1].tenderType, TenderType.mobileMoney);
    });

    test('tolerates a missing tenderSubtotals list', () {
      final x = XRead.fromJson({
        'sessionUid': 's1',
        'openingFloatAmount': 0,
        'totalSalesAmount': 0,
        'cashTenderAmount': 0,
        'totalPayoutsNetAmount': 0,
        'expectedCashAmount': 0,
        'invoiceCount': 0,
      });
      expect(x.tenderSubtotals, isEmpty);
      expect(x.cashTenderAmount, 0);
    });
  });

  group('ZRead tender breakdown (#227)', () {
    test('parses cashTenderAmount and subtotals alongside the variance', () {
      final z = ZRead.fromJson({
        'sessionUid': 's1',
        'openingFloatAmount': '100.00',
        'totalSalesAmount': '46020.00',
        'cashTenderAmount': '32020.00',
        'totalPayoutsNetAmount': '0.00',
        'expectedCashAmount': '32120.00',
        'countedCashAmount': '32100.00',
        'varianceAmount': '-20.00',
        'invoiceCount': 7,
        'tenderSubtotals': [
          {'tenderType': 'CASH', 'amount': '32020.00'},
          {'tenderType': 'CARD', 'amount': '0.00'},
        ],
      });
      expect(z.cashTenderAmount, 32020.00);
      expect(z.varianceAmount, -20.00);
      expect(z.tenderSubtotals, hasLength(2));
      expect(z.tenderSubtotals[1].tenderType, TenderType.card);
    });
  });
}
