// 1.4.0: closing a till from the phone, and editing an item.
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';

import 'package:orbix_hq/app/theme.dart';
import 'package:orbix_hq/features/till_z.dart';
import 'package:orbix_hq/services/catalog_service.dart';
import 'package:orbix_hq/services/operations_service.dart';

void main() {
  group('over / short', () {
    test('positive variance is over, negative is short', () {
      expect(overShort(2000), 'Over TZS 2,000');
      expect(overShort(-5000), 'Short TZS 5,000');
      expect(overShort(0), 'Balanced');
      expect(overShort(0.001), 'Balanced');
    });

    test('short is red, over is amber, balanced is green', () {
      expect(overShortColor(-1), HqColors.bad);
      expect(overShortColor(1), HqColors.warn);
      expect(overShortColor(0), HqColors.good);
    });
  });

  group('sessions', () {
    test('a closed session carries its count and variance', () {
      final s = SessionRow.fromJson(const {
        'uid': 'S1',
        'tillName': 'Till 1',
        'cashierName': 'Amina',
        'openedAt': '2026-11-12T05:00:00Z',
        'closedAt': '2026-11-12T18:00:00Z',
        'status': 'CLOSED',
        'openingFloatAmount': '50000.00',
        'expectedCashAmount': '150000.00',
        'countedCashAmount': '145000.00',
        'varianceAmount': '-5000.00',
        'sessionNumber': 'POS-0042',
      });
      expect(s.isClosed, isTrue);
      expect(s.isOpen, isFalse);
      expect(s.countedCash, 145000);
      expect(s.variance, -5000);
      expect(s.sessionNumber, 'POS-0042');
    });

    test('an X read without expected cash is a blind count, not zero', () {
      final blind = TillRead.fromJson(const {
        'tillName': 'Till 1',
        'totalSalesAmount': 1000,
        'expectedCashAmount': null,
      });
      expect(blind.expectedKnown, isFalse);

      final seen = TillRead.fromJson(const {
        'tillName': 'Till 1',
        'expectedCashAmount': 0,
      });
      expect(seen.expectedKnown, isTrue);
    });

    test('a Z read keeps the X-read figures plus count and variance', () {
      final z = ZRead.fromJson(const {
        'sessionUid': 'S1',
        'tillName': 'Till 1',
        'cashierName': 'Amina',
        'totalSalesAmount': '200000',
        'expectedCashAmount': '150000',
        'countedCashAmount': '152000',
        'varianceAmount': '2000',
        'invoiceCount': 40,
        'tenderSubtotals': [
          {'tenderType': 'CASH', 'amount': '100000', 'count': 30},
        ],
      });
      expect(z.read.totalSales, 200000);
      expect(z.read.tenders.single.label, 'Cash');
      expect(z.countedCash, 152000);
      expect(z.variance, 2000);
      expect(overShort(z.variance), 'Over TZS 2,000');
    });

    testWidgets('the Z read fits a small phone', (tester) async {
      tester.view.physicalSize = const Size(360, 1400);
      tester.view.devicePixelRatio = 1.0;
      addTearDown(tester.view.reset);
      final z = ZRead.fromJson(const {
        'tillName': 'A very long till name that goes on',
        'cashierName': 'A cashier with a long double-barrelled name',
        'totalSalesAmount': '123456789',
        'expectedCashAmount': '98765432',
        'countedCashAmount': '98760000',
        'varianceAmount': '-5432',
        'invoiceCount': 1234,
        'tenderSubtotals': [
          {'tenderType': 'MOBILE_MONEY', 'amount': '24691357', 'count': 400},
        ],
      });
      await tester.pumpWidget(MaterialApp(
        theme: buildHqTheme(),
        home: Scaffold(
          body: ListView(children: [ZReadView(z: z)]),
        ),
      ));
      expect(tester.takeException(), isNull);
      expect(find.text('Short TZS 5,432'), findsOneWidget);
    });
  });

  group('product edit body', () {
    ProductFull product({Object? cost, String vat = 'STANDARD'}) =>
        ProductFull.fromJson({
          'uid': 'P1',
          'code': 'PROD-0001',
          'name': 'Phone case',
          'description': 'Black',
          'type': 'GOODS',
          'sellable': true,
          'stockable': true,
          'baseUnitUid': 'U1',
          'baseUnitCode': 'PCS',
          'cost': cost,
          'vatStatus': vat,
          'status': 'ACTIVE',
          'category': 'Accessories',
          'preferredSupplierId': '77',
        });

    Map<String, dynamic> body(
      ProductFull p, {
      bool vatable = true,
      double? cost,
      String? supplierId,
      bool clearSupplier = false,
    }) =>
        productUpdateBody(
          p,
          name: 'Phone case',
          description: 'Black',
          category: 'Accessories',
          vatable: vatable,
          cost: cost,
          preferredSupplierId: supplierId,
          clearSupplier: clearSupplier,
        );

    test('every field the PUT replaces is sent', () {
      final b = body(product(cost: {'amount': '3000.00', 'currency': 'TZS'}));
      for (final k in [
        'name',
        'description',
        'type',
        'sellable',
        'stockable',
        'baseUnitUid',
        'cost',
        'vatStatus',
      ]) {
        expect(b.containsKey(k), isTrue, reason: '$k missing');
      }
      expect(b['baseUnitUid'], 'U1');
    });

    test('an untouched cost is sent back, not wiped', () {
      final stored = {'amount': '3000.00', 'currency': 'TZS'};
      expect(body(product(cost: stored))['cost'], stored);
    });

    test('a typed cost replaces it', () {
      final b = body(product(cost: {'amount': '3000.00', 'currency': 'TZS'}),
          cost: 3500);
      expect(b['cost'], {'amount': '3500.00', 'currency': 'TZS'});
    });

    test('no stored cost and none typed sends no cost', () {
      expect(body(product()).containsKey('cost'), isFalse);
    });

    test('VAT on keeps a zero rate; VAT off is exempt', () {
      expect(body(product(vat: 'ZERO'))['vatStatus'], 'ZERO');
      expect(body(product(vat: 'EXEMPT'))['vatStatus'], 'STANDARD');
      expect(body(product(), vatable: false)['vatStatus'], 'EXEMPT');
    });

    test('supplier: keep, change or clear', () {
      expect(body(product(), supplierId: '77')['preferredSupplierId'], '77');
      expect(body(product(), supplierId: '88')['preferredSupplierId'], '88');
      final cleared = body(product(), clearSupplier: true);
      expect(cleared.containsKey('preferredSupplierId'), isFalse);
      expect(cleared['clearFields'], ['preferredSupplierId']);
    });
  });
}
