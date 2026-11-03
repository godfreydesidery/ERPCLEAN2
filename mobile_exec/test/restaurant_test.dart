// The restaurant dashboard and reports (Cafe Tulia, ERP 1.11.4+).
//
// The JSON below is the shape the ERP sends from /reports/restaurant/*. These
// lock in how each report is read, what the flash report and the shared files
// carry, and that the dashboard lays out on a small phone with long names.
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';

import 'package:orbix_hq/app/theme.dart';
import 'package:orbix_hq/features/restaurant_dashboard_screen.dart';
import 'package:orbix_hq/features/restaurant_reports_screen.dart';
import 'package:orbix_hq/services/restaurant_service.dart';

Map<String, dynamic> _kpis(num sales, {int receipts = 4, int guests = 6}) => {
      'sales': sales,
      'receipts': receipts,
      'averageReceipt': receipts == 0 ? 0 : sales / receipts,
      'orders': 3,
      'guests': guests,
      'spendPerGuest': 9000,
      'discounts': 2000,
      'complimentary': 7000,
      'refunds': 0,
      'voidedReceipts': 1,
      'cancelledOrders': 1,
      'voidedItems': 2,
    };

Map<String, dynamic> _dashboard() => {
      'fromDate': '2026-11-03',
      'toDate': '2026-11-03',
      'previousFromDate': '2026-11-02',
      'previousToDate': '2026-11-02',
      'currency': 'TZS',
      'current': _kpis(50000),
      'previous': _kpis(40000),
      'live': {'openOrders': 2, 'billedOrders': 1, 'billedAmount': 13000},
      'days': [
        {'date': '2026-11-03', 'receipts': 4, 'sales': 50000},
      ],
      'hours': [
        for (var h = 0; h < 24; h++)
          {'hour': h, 'receipts': h == 13 ? 3 : (h == 19 ? 1 : 0),
            'sales': h == 13 ? 38000 : (h == 19 ? 12000 : 0)},
      ],
      'weekdays': [
        for (var d = 1; d <= 7; d++)
          {'day': d, 'label': ['Monday', 'Tuesday', 'Wednesday', 'Thursday',
              'Friday', 'Saturday', 'Sunday'][d - 1],
            'days': d == 2 ? 1 : 0, 'receipts': d == 2 ? 4 : 0,
            'sales': d == 2 ? 50000 : 0, 'averagePerDay': d == 2 ? 50000 : 0},
      ],
      'departments': [
        {'name': 'Food', 'quantity': 3, 'sales': 36000},
        {'name': 'Drinks', 'quantity': 4, 'sales': 14000},
      ],
      'topItems': [
        {'code': '147', 'name': 'Grilled Chicken Tacos with Extra Guacamole and Salsa',
          'unit': 'PCS', 'quantity': 3, 'sales': 36000},
      ],
      'waiters': [
        {'waiterUid': 'W1', 'waiterName': 'Madam Farida Abdallah Mohamedi',
          'bills': 3, 'guests': 6, 'amount': 43000, 'averageBill': 14333.33},
        {'waiterUid': null, 'waiterName': 'Counter sales (no table order)',
          'bills': 1, 'guests': 0, 'amount': 7000, 'averageBill': 7000},
      ],
      'tenders': [
        {'tenderType': 'MOBILE_MONEY', 'payments': 2, 'amount': 30000},
        {'tenderType': 'CASH', 'payments': 1, 'amount': 13000},
        {'tenderType': 'COMPLIMENTARY', 'payments': 1, 'amount': 7000},
      ],
      'complimentary': [
        {'receiptNumber': 'SI-0042', 'at': '2026-11-03 13:20',
          'givenTo': 'Kilimanjaro Holdings and Logistics Company Limited', 'amount': 7000},
      ],
      'generatedAt': '2026-11-03T12:00:00Z',
    };

void main() {
  group('the dashboard', () {
    final d = RestaurantDashboard.fromJson(_dashboard());

    test('reads the figures and the period before', () {
      expect(d.current.sales, 50000);
      expect(d.previous.sales, 40000);
      expect(changePct(d.current.sales, d.previous.sales), closeTo(25, 0.001));
      expect(d.openOrders, 2);
      expect(d.billedAmount, 13000);
    });

    test('no change is claimed against a zero', () {
      expect(changePct(5, 0), isNull);
    });

    test('hours are labelled, payment types worded, counter sales named', () {
      expect(d.hours, hasLength(24));
      expect(d.hours[13].label, '13:00–14:00');
      expect(d.hours[23].label, '23:00–00:00');
      expect(d.tenders.map((t) => t.label),
          ['Mobile money', 'Cash', 'Complimentary']);
      expect(d.waiters.last.label, RestaurantDashboard.counterSales);
      expect(d.weekdays[1].note, '1 day');
    });

    test('the flash report carries every figure, the change and the comps', () {
      final doc = flashReport(d, 'Cafe Tulia');
      final csv = doc.toCsv();
      expect(doc.title, 'Flash report');
      expect(csv, contains('Sales,50000.00,40000.00,+25.0%'));
      expect(csv, contains('Guests'));
      expect(csv, contains('Waiting to be paid,13000.00'));
      expect(csv, contains('Counter sales (no table order)'));
      expect(csv, contains('Kilimanjaro Holdings'));
      expect(csv, isNot(contains('TZS 5')), reason: 'bare numbers for a spreadsheet');
    });
  });

  group('the reports', () {
    test('sales by waiter, summary and detail', () {
      final s = RestaurantService.tableFor('waiter-sales', {
        'currency': 'TZS',
        'rows': [
          {'waiterName': 'Baraka', 'bills': 2, 'guests': 4, 'averageBill': 10000, 'amount': 20000},
        ],
        'totals': {'bills': 2, 'guests': 4, 'amount': 20000},
      });
      expect(s.rows.single, ['Baraka', 2.0, 4.0, 10000.0, 20000.0]);
      expect(s.totals.last, ('Amount', 20000.0));
      expect(s.totals.first, ('Bills', '2'), reason: 'a count is not money');

      final d = RestaurantService.tableFor('waiter-items', {
        'currency': 'TZS',
        'rows': [
          {'waiterUid': 'W1', 'waiterName': 'Baraka', 'code': '147', 'name': 'Tacos',
            'unit': 'PCS', 'quantity': 2, 'amount': 24000},
        ],
        'waiters': [
          {'waiterUid': 'W1', 'waiterName': 'Baraka', 'bills': 2, 'amount': 24000},
        ],
        'totalAmount': 24000,
      });
      expect(d.rows.single, ['Baraka', 'Tacos', 2.0, 24000.0]);
      expect(d.totals.first, ('Baraka · 2 bills', 24000.0));
    });

    test('bills paid summary has one column per payment type used', () {
      final t = RestaurantService.tableFor('bills-summary', {
        'currency': 'TZS',
        'tenderTypes': ['CASH', 'MOBILE_MONEY'],
        'rows': [
          {'waiterName': 'Baraka', 'bills': 2, 'guests': 4, 'tenders': [5000, 15000], 'amount': 20000},
        ],
        'totals': {'waiterName': 'TOTAL', 'bills': 2, 'guests': 4, 'tenders': [5000, 15000], 'amount': 20000},
      });
      expect(t.columns, ['Waiter', 'Bills', 'Guests', 'Cash', 'Mobile money', 'Amount']);
      expect(t.money, {3, 4, 5});
      expect(t.totals, contains(('Mobile money', 15000.0)));
    });

    test('a voided bill is marked in the detail', () {
      final t = RestaurantService.tableFor('bills', {
        'currency': 'TZS',
        'rows': [
          {'paidAt': '2026-11-03 13:00', 'servedAt': 'T1', 'waiterName': 'Baraka',
            'invoiceNumber': 'SI-1', 'invoiceStatus': 'VOID', 'tenders': 'CASH, MOBILE_MONEY', 'amount': 9000},
        ],
        'billCount': 0,
        'totalAmount': 0,
      });
      expect(t.rows.single[3], 'SI-1 (void)');
      expect(t.rows.single[4], 'Cash, Mobile money');
    });

    test('pending bills: summary and detail', () {
      final s = RestaurantService.tableFor('pending-summary', {
        'currency': 'TZS',
        'rows': [
          {'waiterName': 'Asha', 'open': 1, 'billed': 1, 'items': 3, 'billedAmount': 13000, 'oldestOpenedAt': '2026-11-03 09:10'},
        ],
        'totals': {'open': 1, 'billed': 1, 'items': 3, 'billedAmount': 13000},
      });
      expect(s.rows.single.last, '2026-11-03 09:10');
      expect(s.totals.last, ('Waiting to be paid', 13000.0));

      final d = RestaurantService.tableFor('pending-bills', {
        'currency': 'TZS',
        'rows': [
          {'openedAt': '09:10', 'servedAt': 'T2', 'waiterName': 'Asha', 'status': 'BILLED', 'items': 3, 'billedAmount': 13000},
          {'openedAt': '09:30', 'servedAt': 'T3', 'waiterName': 'Asha', 'status': 'OPEN', 'items': 1, 'billedAmount': null},
        ],
        'openCount': 1,
        'billedCount': 1,
        'billedAmount': 13000,
      });
      expect(d.rows.map((r) => r[3]), ['Bill printed', 'Open']);
    });

    test('sales by hour and day: only hours that sold, then all seven days', () {
      Map<String, dynamic> slot(String label, num sales) => {
            'label': label, 'receipts': sales == 0 ? 0 : 1, 'sales': sales,
            'days': 1, 'averagePerDay': sales, 'departmentSales': [sales, 0],
          };
      final t = RestaurantService.tableFor('daypart', {
        'currency': 'TZS',
        'departments': ['Food', 'Drinks'],
        'hours': [for (var h = 0; h < 24; h++) slot('$h:00-${h + 1}:00', h == 13 ? 9000 : 0)],
        'weekdays': [for (final d in ['Monday', 'Tuesday', 'Wednesday', 'Thursday', 'Friday', 'Saturday', 'Sunday']) slot(d, 0)],
        'totals': slot('TOTAL', 9000),
      });
      expect(t.columns, ['Time', 'Receipts', 'Sales', 'Per day', 'Food', 'Drinks']);
      expect(t.rows, hasLength(1 + 7));
      expect(t.rows.first.first, '13:00–14:00');
    });

    test('payments and completion', () {
      final t = RestaurantService.tableFor('payments', {
        'currency': 'TZS',
        'tenders': [
          {'tenderType': 'CASH', 'payments': 2, 'amount': 20000, 'share': 100},
        ],
        'totalPaid': 20000,
        'completion': {'opened': 3, 'paid': 1, 'cancelled': 1, 'pending': 1, 'completionRate': 33.3},
        'refunds': [],
        'refundTotal': 0,
        'voidedReceipts': [],
        'voidedReceiptTotal': 0,
        'cancelledOrders': [
          {'orderNumber': 'ORD-9', 'servedAt': 'T1', 'waiterName': 'Asha', 'cancelledAt': '10:00', 'reason': 'Left', 'items': 2},
        ],
        'voidedItems': [
          {'voidedAt': '11:00', 'orderNumber': 'ORD-8', 'servedAt': 'T2', 'waiterName': 'Asha', 'productName': 'Latte', 'quantity': 1, 'reason': 'Spilt'},
        ],
      });
      expect(t.rows.map((r) => r.first), ['Payment', 'Cancelled order', 'Voided item']);
      expect(t.rows[1][1], contains('Left'));
      expect(t.rows[2][1], '1 × Latte (ORD-8, Asha) — Spilt');
      expect(t.totals, contains(('Completion rate', '33.3%')));
      expect(t.totals, contains(('Orders opened', '3')));

      final csv = restaurantReportDoc('Payments & completion', 'Today', t, 'Cafe Tulia').toCsv();
      expect(csv, contains('Payment'));
      expect(csv, contains('20000.00'));
    });

    test('an unknown report is refused, not guessed', () {
      expect(() => RestaurantService.tableFor('menu', {}), throwsArgumentError);
    });
  });

  group('the dashboard on a phone', () {
    for (final size in const [Size(360, 640), Size(412, 915)]) {
      testWidgets('lays out at ${size.width.toInt()}x${size.height.toInt()} with long names',
          (tester) async {
        tester.view.physicalSize = size;
        tester.view.devicePixelRatio = 1.0;
        addTearDown(tester.view.reset);
        await tester.pumpWidget(MaterialApp(
          theme: buildHqTheme(),
          home: Scaffold(
            body: RestaurantDashboardBody(
              data: RestaurantDashboard.fromJson(_dashboard()),
              period: '3 Nov 2026',
              branch: 'Cafe Tulia',
              onOpenReports: () {},
            ),
          ),
        ));
        await tester.pump();
        expect(tester.takeException(), isNull);
        expect(find.text('Tables open'), findsOneWidget);
        await tester.scrollUntilVisible(find.text('Share the flash report'), 400);
        expect(tester.takeException(), isNull);
      });
    }
  });
}
