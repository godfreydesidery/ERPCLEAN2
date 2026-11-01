import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:pos_app/features/register/restaurant_register.dart';
import 'package:pos_app/models/restaurant.dart';

/// The restaurant Orders board (Cafe Tulia 2026-11-01): every open order as a
/// card showing what is on it, so a guest's addition is easy to see.
void main() {
  RestaurantOrder order({String status = 'OPEN', double? billedTotal, List<Map<String, dynamic>>? lines}) =>
      RestaurantOrder.fromJson({
        'uid': 'O1', 'orderNumber': 'ORD-0053', 'orderType': 'DINE_IN', 'status': status,
        'tableName': 'T1', 'openedByName': 'Mr Issa', 'waiterName': 'samwel',
        'openedAt': DateTime.now().subtract(const Duration(minutes: 51)).toIso8601String(),
        'billPrintCount': billedTotal == null ? 0 : 1, 'pendingLineCount': 0,
        'billedTotal': billedTotal,
        'lines': lines ??
            [
              {'uid': 'L1', 'productId': '1', 'productName': 'Virgin Mojito', 'quantity': 2, 'status': 'SENT'},
              {'uid': 'L2', 'productId': '2', 'productName': 'Extra mint', 'quantity': 2,
                  'status': 'SENT', 'parentLineUid': 'L1'},
              {'uid': 'L3', 'productId': '3', 'productName': 'Chocolate Brownie', 'quantity': 1},
              {'uid': 'L4', 'productId': '4', 'productName': 'Mango Juice', 'quantity': 1,
                  'status': 'VOIDED', 'voidReason': 'Wrong item'},
            ],
      });

  Future<void> show(WidgetTester t, RestaurantOrder o, {double? total, VoidCallback? onTap}) =>
      t.pumpWidget(MaterialApp(
        home: Scaffold(
          body: Center(
            child: SizedBox(
              width: 340,
              height: 300,
              child: OrderCard(
                order: o,
                amountOf: (l) => l.productId == '1' ? 20000 : null,
                total: total,
                onTap: onTap,
              ),
            ),
          ),
        ),
      ));

  testWidgets('a card shows where, waiter, time open, the live items and the total', (t) async {
    var opened = 0;
    await show(t, order(), total: 34000, onTap: () => opened++);
    expect(find.text('Table T1'), findsOneWidget);
    expect(find.textContaining('ORD-0053'), findsOneWidget);
    expect(find.textContaining('51m'), findsOneWidget);
    expect(find.text('samwel'), findsOneWidget);
    expect(find.textContaining('Virgin Mojito'), findsOneWidget);
    expect(find.textContaining('Extra mint'), findsOneWidget, reason: 'add-ons show under their item');
    expect(find.textContaining('Mango Juice'), findsNothing, reason: 'voided items do not show');
    expect(find.text('20,000.00'), findsOneWidget);
    expect(find.text('34,000.00'), findsOneWidget);
    expect(find.text('TOTAL'), findsOneWidget);
    await t.tap(find.text('Table T1'));
    expect(opened, 1);
  });

  testWidgets('an item not yet sent to the kitchen is marked NEW', (t) async {
    await show(t, order());
    expect(find.text('NEW'), findsOneWidget, reason: 'only the Brownie is still pending');
  });

  testWidgets('a billed order says so and shows the bill total', (t) async {
    await show(t, order(status: 'BILLED', billedTotal: 54000), total: 54000);
    expect(find.text('BILL PRINTED'), findsOneWidget);
    expect(find.text('BILL'), findsOneWidget);
    expect(find.text('54,000.00'), findsOneWidget);
  });

  testWidgets('an unknown price shows a dash, never a wrong total', (t) async {
    await show(t, order());
    expect(find.text('—'), findsOneWidget);
  });

  test('time open reads like the old board', () {
    final now = DateTime(2026, 11, 1, 15, 0);
    expect(openFor(null), '');
    expect(openFor(now.subtract(const Duration(seconds: 20)), now: now), '<1m');
    expect(openFor(now.subtract(const Duration(minutes: 51)), now: now), '51m');
    expect(openFor(now.subtract(const Duration(minutes: 92)), now: now), '1h 32m');
  });
}
