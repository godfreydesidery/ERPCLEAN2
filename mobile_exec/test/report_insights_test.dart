// Cafe Tulia 2026-11-26: search items sold ("MATCHA"), food vs drinks, the
// daily sales log (items + customer on each bill) and the Z read's sales
// sections. Each new piece must leave an OLDER server's reports exactly as
// they were: no new column, no empty section, no changed figure.
import 'package:flutter_test/flutter_test.dart';

import 'package:orbix_hq/features/restaurant_reports_screen.dart';
import 'package:orbix_hq/features/sales_report_view.dart';
import 'package:orbix_hq/services/operations_service.dart';
import 'package:orbix_hq/services/restaurant_service.dart';
import 'package:orbix_hq/services/sales_service.dart';

SalesReportRow _row(String name, double qty, double amount, [String? cat]) =>
    SalesReportRow.fromJson({
      'productCode': name.toUpperCase().replaceAll(' ', ''),
      'productName': name,
      'qtySold': qty,
      'amount': amount,
      'margin': null,
      'vat': 0,
      'discount': 0,
      if (cat != null) 'category': cat,
    });

void main() {
  final rows = [
    _row('Matcha latte', 12, 180000, 'SPECIALTY MATCHA'),
    _row('Iced matcha', 30, 360000, 'specialty matcha'),
    _row('Burger', 20, 500000, 'BURGERS'),
    _row('Latte', 30, 240000, 'COFFEE'),
    _row('Chapati', 5, 10000),
  ];

  group('sales report rows', () {
    test('category is read; blank or missing means none (older server)', () {
      expect(_row('A', 1, 1, 'COFFEE').category, 'COFFEE');
      expect(_row('A', 1, 1, '   ').category, isNull);
      expect(_row('A', 1, 1).category, isNull);
      expect(reportHasCategories([_row('A', 1, 1)]), isFalse);
      expect(reportHasCategories(rows), isTrue);
    });

    test('search "MATCHA" finds every matcha, ignoring case, and totals them', () {
      final shown = applySalesFilter(rows, const SalesFilter(query: 'MATCHA'));
      expect(shown.map((r) => r.productName),
          containsAll(['Matcha latte', 'Iced matcha']));
      expect(shown, hasLength(2));
      final m = SalesMatches.of(shown);
      expect(m.items, 2);
      expect(m.qtySold, 42);
      expect(m.amount, 540000);
    });

    test('most sold first, ties by name; highest sales; A-Z', () {
      expect(applySalesFilter(rows, const SalesFilter()).map((r) => r.productName),
          ['Iced matcha', 'Latte', 'Burger', 'Matcha latte', 'Chapati']);
      expect(
          applySalesFilter(
                  rows, const SalesFilter(sort: SalesSort.highestSales))
              .first
              .productName,
          'Burger');
      expect(
          applySalesFilter(rows, const SalesFilter(sort: SalesSort.az))
              .map((r) => r.productName)
              .first,
          'Burger');
    });

    test('drinks = the ticked categories (any case); food = everything else', () {
      const drinks = {'Specialty Matcha', 'COFFEE'};
      final d = applySalesFilter(rows,
          const SalesFilter(kind: SalesKind.drinks, drinkCategories: drinks));
      final f = applySalesFilter(rows,
          const SalesFilter(kind: SalesKind.food, drinkCategories: drinks));
      expect(d.map((r) => r.productName).toSet(),
          {'Matcha latte', 'Iced matcha', 'Latte'});
      // An item with no category is food, so nothing falls out of both lists.
      expect(f.map((r) => r.productName).toSet(), {'Burger', 'Chapati'});
      expect(d.length + f.length, rows.length);
    });

    test('the list never changes the report rows themselves', () {
      final copy = List.of(rows);
      applySalesFilter(rows, const SalesFilter(query: 'x', sort: SalesSort.az));
      expect(rows, copy);
      expect(const SalesFilter().narrows, isFalse);
      expect(const SalesFilter(query: ' ').narrows, isFalse);
    });

    test('categories are listed once each, A-Z', () {
      expect(categoriesOf(rows), ['BURGERS', 'COFFEE', 'SPECIALTY MATCHA']);
    });
  });

  group('bills paid (daily sales log)', () {
    Map<String, dynamic> bill({bool withItems = true}) => {
          'paidAt': '2026-11-24 10:27',
          'servedAt': 'T4',
          'waiterName': 'Rose',
          'invoiceNumber': 'INV-1001',
          'invoiceStatus': 'FINALISED',
          'tenders': 'CASH',
          'amount': 150000,
          if (withItems) 'items': '2× Burger, Coffee, Cake',
          if (withItems) 'customerName': 'Mr Juma',
        };

    test('a newer server adds Items and Customer after Amount', () {
      final t = RestaurantService.tableFor('bills', {
        'rows': [bill()],
        'billCount': 1,
        'totalAmount': 150000,
      });
      expect(t.columns,
          ['Paid', 'Table', 'Waiter', 'Receipt', 'Paid by', 'Amount', 'Items', 'Customer']);
      expect(t.money, {5}, reason: 'Amount keeps its place');
      expect(t.rows.single[6], '2× Burger, Coffee, Cake');
      expect(t.rows.single[7], 'Mr Juma');
    });

    test('an older server: exactly the columns as before, nothing empty added', () {
      final t = RestaurantService.tableFor('bills', {
        'rows': [bill(withItems: false)],
        'billCount': 1,
        'totalAmount': 150000,
      });
      expect(t.columns, ['Paid', 'Table', 'Waiter', 'Receipt', 'Paid by', 'Amount']);
      expect(t.rows.single, hasLength(6));
    });

    test('search narrows the lines (customer, item…), totals stay the period\'s', () {
      final t = RestaurantService.tableFor('bills', {
        'rows': [
          bill(),
          {...bill(), 'items': 'Latte', 'customerName': '', 'invoiceNumber': 'INV-1002'},
        ],
        'billCount': 2,
        'totalAmount': 300000,
      });
      expect(matchingLines(t, 'juma').rows, hasLength(1));
      expect(matchingLines(t, 'BURGER').rows, hasLength(1));
      expect(matchingLines(t, 'inv-100').rows, hasLength(2));
      expect(matchingLines(t, '  ').rows, hasLength(2));
      expect(matchingLines(t, 'juma').totals, t.totals);
    });
  });

  group('Z read sales sections', () {
    Map<String, dynamic> z({bool withBreakdown = true}) => {
          'tillName': 'Counter001',
          'cashierName': 'Imani',
          'totalSalesAmount': 888000,
          'cashTenderAmount': 288000,
          'invoiceCount': '40',
          'tenderSubtotals': [],
          'countedCashAmount': 288000,
          'varianceAmount': 0,
          if (withBreakdown)
            'breakdown': {
              'grossSalesAmount': 900000,
              'discountAmount': 12000,
              'netSalesAmount': 888000,
              'vatAmount': 135457.63,
              'transactionCount': '40',
              'categories': [
                {'name': 'BURGER & WINGS', 'amount': 238000, 'count': '12'},
              ],
              'departments': [
                {'name': 'Kitchen', 'amount': 500000, 'count': '30'},
                {'name': 'Barista', 'amount': 388000, 'count': '25'},
              ],
              'teamMembers': [
                {'name': 'Imani', 'amount': 888000, 'count': '40'},
              ],
            },
        };

    test('read from a newer server (counts arrive as text)', () {
      final b = ZRead.fromJson(z()).breakdown!;
      expect(b.net, 888000);
      expect(b.discount, 12000);
      expect(b.transactions, 40);
      expect(b.departments.map((d) => d.name), ['Kitchen', 'Barista']);
      expect(b.team.single.count, 40);
    });

    test('an older server sends none: no sections, the Z read as before', () {
      final read = ZRead.fromJson(z(withBreakdown: false));
      expect(read.breakdown, isNull);
      expect(read.read.totalSales, 888000);
    });
  });
}
