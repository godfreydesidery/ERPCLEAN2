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

  group('PosTill.occupantHolder', () {
    PosTill till(Map<String, dynamic> extra) => PosTill.fromJson({
          'id': '1',
          'uid': 'tilluid',
          'companyId': '10',
          'branchId': '20',
          'code': 'T1',
          'name': 'Till 1',
          'status': 'ACTIVE',
          'hasOpenSession': true,
          'openSessionCashierId': '7',
          ...extra,
        });

    test('names the colleague holding the till', () {
      final t = till({'openSessionCashierName': 'Asha Mwakalinga'});
      expect(t.occupantHolder, 'Asha Mwakalinga');
    });

    test('falls back to the neutral wording when the server sends no name', () {
      expect(till({}).occupantHolder, 'Another cashier');
      expect(till({'openSessionCashierName': null}).occupantHolder,
          'Another cashier');
      expect(till({'openSessionCashierName': '   '}).occupantHolder,
          'Another cashier');
    });

    test('never falls back to the cashier id', () {
      expect(till({}).occupantHolder, isNot(contains('7')));
    });
  });

  group('PosSession.fromOpenTill', () {
    final openedAt = DateTime.utc(2026, 8, 11, 6, 15);
    final till = PosTill.fromJson({
      'id': '9',
      'uid': 'tilluid',
      'companyId': '10',
      'branchId': '20',
      'code': 'T1',
      'name': 'Till 1',
      'status': 'ACTIVE',
      'hasOpenSession': true,
      'openSessionUid': 'SESS1',
      'openSessionCashierId': '7',
      'openSessionOpenedAt': openedAt.toIso8601String(),
    });

    test('carries what the till knows, so the register can be entered', () {
      final s = PosSession.fromOpenTill(till, sessionUid: 'SESS1');
      expect(s.uid, 'SESS1');
      expect(s.status, PosSessionStatus.open);
      expect(s.openedAt, openedAt);
      expect(s.posTillId, '9');
      expect(s.cashierId, '7');
    });

    test('invents no figure it did not read', () {
      final s = PosSession.fromOpenTill(till, sessionUid: 'SESS1');
      // The flag is the contract: whatever the placeholders are, nothing may
      // print them as a drawer figure.
      expect(s.figuresKnown, isFalse);
      expect(s.sessionNumber, '—');
      expect(s.countedCashAmount, isNull);
      expect(s.expectedCashAmount, isNull);
    });

    test('a session parsed from the server is never flagged', () {
      final s = PosSession.fromJson({
        'uid': 'SESS1',
        'status': 'OPEN',
        'openingFloatAmount': '20000.00',
      });
      expect(s.figuresKnown, isTrue);
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
