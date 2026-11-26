// Cafe Tulia 2026-11-26: the printed Z-read gains the sales sections of their
// old POS slip (summary, categories, departments, team members). They print
// AFTER every line the Z-read already had, fit 58 and 80 mm paper, and an
// older server (no breakdown) prints exactly what it printed before.
import 'package:flutter_test/flutter_test.dart';
import 'package:pos_app/core/money.dart';
import 'package:pos_app/features/receipt/receipt_text.dart';
import 'package:pos_app/features/receipt/report_text.dart';
import 'package:pos_app/models/enums.dart';
import 'package:pos_app/models/pos.dart';

const _ctx = ReportContext(
  companyName: 'Cafe Tulia',
  branchName: 'Dar es Salaam',
  cashierName: 'Imani',
  sessionNumber: 'POS-0062',
  currency: 'TZS',
);

ZRead _z({ZReadBreakdown? breakdown}) => ZRead(
      sessionUid: 'S1',
      openedAt: DateTime(2026, 11, 24, 7, 30),
      closedAt: DateTime(2026, 11, 24, 22, 5),
      reconciledAt: DateTime(2026, 11, 24, 22, 20),
      openingFloatAmount: 0,
      totalSalesAmount: 888000,
      cashTenderAmount: 288000,
      totalPayoutsNetAmount: 0,
      expectedCashAmount: 288000,
      countedCashAmount: 288000,
      varianceAmount: 0,
      invoiceCount: 40,
      tenderSubtotals: [
        TenderSubtotal(tenderType: TenderType.cash, amount: 288000),
        TenderSubtotal(tenderType: TenderType.card, amount: 600000),
      ],
      breakdown: breakdown,
    );

ZReadBreakdown _b() => ZReadBreakdown.fromJson({
      'grossSalesAmount': 900000,
      'discountAmount': 12000,
      'netSalesAmount': 888000,
      'vatAmount': 135457.63,
      'transactionCount': '40', // the server sends long counts as text
      'categories': [
        {'name': 'BURGER & WINGS', 'amount': 238000, 'count': '12'},
        {
          'name': 'A VERY LONG CATEGORY NAME THAT WILL NOT FIT ON 58MM PAPER',
          'amount': 1234567,
          'count': '3'
        },
      ],
      'departments': [
        {'name': 'KITCHEN', 'amount': 500000, 'count': '30'},
        {'name': 'BARISTA', 'amount': 388000, 'count': '25'},
      ],
      'teamMembers': [
        {'name': 'Imani Samantar', 'amount': 888000, 'count': '40'},
      ],
    });

void main() {
  test('every line fits both paper widths', () {
    for (final width in [kCols58mm, kCols80mm]) {
      final text = buildZReadText(z: _z(breakdown: _b()), ctx: _ctx, width: width);
      for (final line in text.split('\n')) {
        expect(line.length, lessThanOrEqualTo(width),
            reason: 'overflowing line: "$line"');
      }
    }
  });

  test('prints summary, categories, departments and team members', () {
    final t = buildZReadText(z: _z(breakdown: _b()), ctx: _ctx, width: kCols80mm);
    for (final s in ['SUMMARY', 'CATEGORIES', 'DEPARTMENTS', 'TEAM MEMBERS']) {
      expect(t, contains(s));
    }
    expect(t, contains('Discounts'));
    expect(t, contains(formatAmount(12000)));
    expect(t, contains('BURGER & WINGS'));
    expect(t, contains('KITCHEN'));
    expect(t, contains('Imani Samantar (40)'));
  });

  test('a long name is cut, never its amount', () {
    final t = buildZReadText(z: _z(breakdown: _b()), ctx: _ctx, width: kCols58mm);
    final line = t.split('\n').firstWhere((l) => l.startsWith('A VERY LONG'));
    expect(line, endsWith(formatAmount(1234567)));
  });

  test('the sections come after every line the Z-read already printed', () {
    final before = buildZReadText(z: _z(), ctx: _ctx, width: kCols80mm).split('\n');
    final after =
        buildZReadText(z: _z(breakdown: _b()), ctx: _ctx, width: kCols80mm).split('\n');
    final invoices = before.indexWhere((l) => l.startsWith('Invoices'));
    expect(invoices, isNonNegative);
    expect(after.sublist(0, invoices + 1), before.sublist(0, invoices + 1),
        reason: 'the drawer statement is printed exactly as before');
    expect(after.indexWhere((l) => l.contains('SUMMARY')), greaterThan(invoices));
    // ...and the signature lines still close the slip.
    expect(after.indexWhere((l) => l.contains('Cashier ____')),
        greaterThan(after.indexWhere((l) => l.contains('TEAM MEMBERS'))));
  });

  test('an older server sends no breakdown: the slip is exactly as before', () {
    final z = ZRead.fromJson({
      'sessionUid': 'S1',
      'totalSalesAmount': 888000,
      'invoiceCount': '40',
      'tenderSubtotals': [],
    });
    expect(z.breakdown, isNull);
    final t = buildZReadText(z: z, ctx: _ctx, width: kCols80mm);
    expect(t, isNot(contains('SUMMARY')));
    expect(t, isNot(contains('CATEGORIES')));
  });

  test('read from the server JSON', () {
    final z = ZRead.fromJson({
      'sessionUid': 'S1',
      'totalSalesAmount': 888000,
      'invoiceCount': '40',
      'tenderSubtotals': [],
      'breakdown': {
        'grossSalesAmount': 888000,
        'discountAmount': 0,
        'netSalesAmount': 888000,
        'vatAmount': 0,
        'transactionCount': '40',
        'categories': [],
        'departments': [
          {'name': 'Unassigned', 'amount': 888000, 'count': '40'}
        ],
        'teamMembers': [],
      },
    });
    expect(z.breakdown!.transactions, 40);
    expect(z.breakdown!.departments.single.name, 'Unassigned');
    final t = buildZReadText(z: z, ctx: _ctx, width: kCols58mm);
    expect(t, contains('DEPARTMENTS'));
    expect(t, isNot(contains('CATEGORIES')), reason: 'an empty section is left out');
  });
}
