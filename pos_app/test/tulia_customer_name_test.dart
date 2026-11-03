import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:pos_app/features/register/restaurant_register.dart';
import 'package:pos_app/features/restaurant/restaurant_dialogs.dart';
import 'package:pos_app/features/restaurant/slip_text.dart';
import 'package:pos_app/models/restaurant.dart';

/// Cafe Tulia 2026-11-03: a bill should say whose it is — a person or a
/// company sitting at the table. Shown on the floor card, printed on the bill.
void main() {
  RestaurantOrder order({String? customerName}) => RestaurantOrder.fromJson({
        'uid': 'O1', 'orderNumber': 'ORD-0122', 'orderType': 'DINE_IN', 'status': 'BILLED',
        'tableName': 'T2', 'openedByName': 'Madam Farida', 'waiterName': 'Madam Farida',
        'openedAt': DateTime.now().subtract(const Duration(minutes: 73)).toIso8601String(),
        'billPrintCount': 1, 'pendingLineCount': 0, 'customerName': ?customerName,
        'lines': [
          {'uid': 'L1', 'productId': '1', 'productName': 'Black tea', 'quantity': 1, 'status': 'SENT'},
        ],
      });

  RestaurantBill bill({String? customerName}) => RestaurantBill.fromJson({
        'orderUid': 'O1', 'orderNumber': 'ORD-0122', 'orderType': 'DINE_IN', 'tableName': 'T2',
        'serverName': 'Madam Farida', 'printCount': 1, 'currency': 'TZS',
        'lines': [
          {'productName': 'Black tea', 'quantity': 1, 'unitPrice': 7000, 'amount': 7000},
        ],
        'netTotal': 5932, 'vatTotal': 1068, 'grossTotal': 7000, 'unsentLineCount': 0,
        'customerName': ?customerName,
      });

  group('the server JSON', () {
    test('carries the name', () {
      expect(order(customerName: 'Acme Ltd').customerName, 'Acme Ltd');
      expect(bill(customerName: 'Acme Ltd').customerName, 'Acme Ltd');
    });

    test('a server older than 1.11.3 sends none', () {
      expect(order().customerName, isNull);
      expect(bill().customerName, isNull);
    });
  });

  group('the printed bill', () {
    test('says whose it is under the table', () {
      final text = buildBillText(bill(customerName: 'Mr Juma'),
          companyName: 'CAFE TULIA', branchName: '', width: 32);
      final lines = text.split('\n');
      final table = lines.indexWhere((l) => l.startsWith('Table T2'));
      expect(lines[table + 1], contains('Customer'));
      expect(lines[table + 1], contains('Mr Juma'));
    });

    test('a long company name wraps instead of running off the paper', () {
      const name = 'Kilimanjaro Holdings and Logistics Company Limited';
      final text = buildBillText(bill(customerName: name),
          companyName: 'CAFE TULIA', branchName: '', width: 32);
      expect(text.split('\n').every((l) => l.length <= 32), isTrue);
      expect(text.replaceAll(RegExp(r'\s+'), ' '), contains('Company Limited'));
    });

    test('no line when no name was given', () {
      final text =
          buildBillText(bill(), companyName: 'CAFE TULIA', branchName: '', width: 32);
      expect(text, isNot(contains('Customer')));
    });
  });

  group('the floor card', () {
    Future<void> show(WidgetTester t, RestaurantOrder o, {double width = 340}) =>
        t.pumpWidget(MaterialApp(
          home: Scaffold(
            body: Center(
              child: SizedBox(
                width: width,
                height: 260,
                child: OrderCard(order: o, amountOf: (_) => 7000, total: 7000, onTap: null),
              ),
            ),
          ),
        ));

    testWidgets('shows the name under the table', (t) async {
      await show(t, order(customerName: 'Acme Ltd'));
      expect(find.text('Table T2'), findsOneWidget);
      expect(find.text('Acme Ltd'), findsOneWidget);
    });

    testWidgets('a long name fits a narrow card', (t) async {
      await show(t, order(customerName: 'Kilimanjaro Holdings and Logistics Company Limited'),
          width: 220);
      expect(t.takeException(), isNull);
    });
  });

  group('asking for the name', () {
    Future<void> host(WidgetTester t, Future<Object?> Function(BuildContext) ask,
        void Function(Object?) done) async {
      t.view.physicalSize = const Size(1280, 800);
      t.view.devicePixelRatio = 1;
      addTearDown(t.view.reset);
      await t.pumpWidget(MaterialApp(
        home: Builder(
          builder: (context) => TextButton(
              onPressed: () async => done(await ask(context)), child: const Text('go')),
        ),
      ));
      await t.tap(find.text('go'));
      await t.pumpAndSettle();
    }

    testWidgets('opening a table: the name is optional, typed before the guests', (t) async {
      Object? got;
      await host(t, (c) => askGuestCount(c, 'T1'), (r) => got = r);
      await t.enterText(find.byKey(const ValueKey('customer-name')), '  Mr Juma ');
      await t.tap(find.text('4'));
      await t.pumpAndSettle();
      expect(got, (guests: 4, name: 'Mr Juma'));
    });

    testWidgets('opening a table: Skip still keeps the name', (t) async {
      Object? got;
      await host(t, (c) => askGuestCount(c, 'T1'), (r) => got = r);
      await t.enterText(find.byKey(const ValueKey('customer-name')), 'Acme Ltd');
      await t.tap(find.text('Skip'));
      await t.pumpAndSettle();
      expect(got, (guests: 0, name: 'Acme Ltd'));
    });

    testWidgets('the on-screen keys type into the name', (t) async {
      Object? got;
      await host(t, (c) => askGuestCount(c, 'T1'), (r) => got = r);
      await t.tap(find.byTooltip('On-screen keys'));
      await t.pumpAndSettle();
      await t.tap(find.text('a').first);
      await t.tap(find.text('1').last);
      await t.pump();
      expect(t.takeException(), isNull);
      await t.tap(find.widgetWithText(OutlinedButton, '2'));
      await t.pumpAndSettle();
      expect((got! as ({int guests, String name})).name, isNotEmpty);
    });

    testWidgets('changing it later: prefilled, saved trimmed, or removed', (t) async {
      Object? got = 'unset';
      await host(t, (c) => askCustomerName(c, order(customerName: 'Mr Juma')), (r) => got = r);
      final field = t.widget<TextField>(find.byKey(const ValueKey('customer-name')));
      expect(field.controller!.text, 'Mr Juma');
      await t.enterText(find.byKey(const ValueKey('customer-name')), ' Acme Ltd ');
      await t.tap(find.text('Save'));
      await t.pumpAndSettle();
      expect(got, 'Acme Ltd');

      await host(t, (c) => askCustomerName(c, order(customerName: 'Mr Juma')), (r) => got = r);
      await t.tap(find.text('Remove'));
      await t.pumpAndSettle();
      expect(got, '');
    });

    testWidgets('no Remove when there is no name yet', (t) async {
      await host(t, (c) => askCustomerName(c, order()), (_) {});
      expect(find.text('Remove'), findsNothing);
    });
  });
}
