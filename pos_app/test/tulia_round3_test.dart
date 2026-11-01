import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:pos_app/core/config/step_up_policy.dart';
import 'package:pos_app/features/payment/payment_sheet.dart';
import 'package:pos_app/features/register/restaurant_register.dart';
import 'package:pos_app/models/enums.dart';
import 'package:pos_app/models/restaurant.dart';
import 'package:pos_app/models/sale.dart';

/// Restaurant round 3: complimentary tender, order discount, completed bills.
void main() {
  group('complimentary tender', () {
    test('travels as COMPLIMENTARY with the approving manager', () {
      expect(TenderType.fromWire('COMPLIMENTARY'), TenderType.complimentary);
      final t = PosTender(
          tenderType: TenderType.complimentary, amount: 24000, authorisedByUid: 'MGR1');
      expect(t.toJson(), {
        'tenderType': 'COMPLIMENTARY',
        'amount': 24000,
        'authorisedByUid': 'MGR1',
      });
      expect(PosTender(tenderType: TenderType.cash, amount: 1).toJson(),
          isNot(contains('authorisedByUid')));
    });

    test('is approved by the discount-override permission, as the server checks', () {
      expect(kStepUpPolicy[GatedAction.complimentary]!.permissionCode, 'SALES.DISCOUNT.OVERRIDE');
      expect(kStepUpPolicy[GatedAction.complimentary]!.requiresApproval, isTrue);
    });
  });

  group('server answers', () {
    test('a quote and a completed bill parse', () {
      final q = RestaurantQuote.fromJson(
          {'undiscountedTotal': 18000, 'discountTotal': 1800, 'grossTotal': 16200});
      expect(q.grossTotal, 16200);
      expect(q.discountTotal, 1800);

      final p = PaidOrder.fromJson({
        'order': {
          'uid': 'O1', 'orderNumber': 'ORD-0007', 'orderType': 'DINE_IN', 'status': 'PAID',
          'tableName': 'T1', 'openedByName': 'Mr Issa', 'billPrintCount': 1,
          'pendingLineCount': 0, 'lines': <dynamic>[], 'salesInvoiceUid': 'INV1',
        },
        'invoiceNumber': 'INV-0032',
        'paidTotal': 13000,
      });
      expect(p.order.status, OrderStatus.paid);
      expect(p.invoiceNumber, 'INV-0032');
      expect(p.paidTotal, 13000);
    });
  });

  group('discount dialog', () {
    Future<({double? percent, double? amount})?> open(WidgetTester t,
        Future<void> Function() act) async {
      ({double? percent, double? amount})? result;
      await t.pumpWidget(MaterialApp(
        home: Builder(
          builder: (context) => TextButton(
            onPressed: () async => result = await askOrderDiscount(context, currency: 'TZS'),
            child: const Text('go'),
          ),
        ),
      ));
      await t.tap(find.text('go'));
      await t.pumpAndSettle();
      await act();
      await t.pumpAndSettle();
      return result;
    }

    testWidgets('a percent is returned as a percent', (t) async {
      final r = await open(t, () async {
        await t.enterText(find.byType(TextField), '10');
        await t.pump();
        await t.tap(find.text('Apply'));
      });
      expect(r, (percent: 10.0, amount: null));
    });

    testWidgets('an amount is returned as an amount', (t) async {
      final r = await open(t, () async {
        await t.tap(find.text('Amount (TZS)'));
        await t.pump();
        await t.enterText(find.byType(TextField), '5000');
        await t.pump();
        await t.tap(find.text('Apply'));
      });
      expect(r, (percent: null, amount: 5000.0));
    });

    testWidgets('more than 100% cannot be applied', (t) async {
      final r = await open(t, () async {
        await t.enterText(find.byType(TextField), '150');
        await t.pump();
        expect(find.text('Not more than 100%.'), findsOneWidget);
        await t.tap(find.text('Apply'));
        await t.pump();
        await t.tap(find.text('Cancel'));
      });
      expect(r, isNull);
    });
  });

  testWidgets('a completed bill shows PAID, its invoice number and what was paid', (t) async {
    final order = RestaurantOrder.fromJson({
      'uid': 'O1', 'orderNumber': 'ORD-0007', 'orderType': 'DINE_IN', 'status': 'PAID',
      'tableName': 'T1', 'openedByName': 'Mr Issa', 'waiterName': 'samwel',
      'billPrintCount': 1, 'pendingLineCount': 0,
      'lines': [
        {'uid': 'L1', 'productId': '1', 'productName': 'Virgin Mojito', 'quantity': 1, 'status': 'SENT'},
      ],
    });
    await t.pumpWidget(MaterialApp(
      home: Scaffold(
        body: SizedBox(
          width: 340,
          height: 300,
          child: OrderCard(
              order: order, amountOf: (_) => null, total: 13000, note: 'INV-0032', onTap: null),
        ),
      ),
    ));
    expect(find.text('PAID'), findsNWidgets(2), reason: 'badge and footer');
    expect(find.textContaining('INV-0032'), findsOneWidget);
    expect(find.text('13,000.00'), findsOneWidget);
    expect(find.text('NEW'), findsNothing);
  });
}
