// An unknown margin stays unknown.
//
// Since ERP 1.9.3 the sales report sends a NULL margin for a product whose
// cost of sale was never recorded, and counts those rows in
// totals.marginRowsUnknown. Before this, the app read the null as 0 — showing
// the sale as break-even — and presented the margin total as complete while it
// left those sales out. Owners judge the business by that figure.
import 'package:flutter_test/flutter_test.dart';

import 'package:orbix_hq/core/export/report_doc.dart';
import 'package:orbix_hq/services/sales_service.dart';

Map<String, dynamic> _row(String code, {num? margin, num amount = 1000}) => {
      'productCode': code,
      'productName': 'Item $code',
      'qtySold': 1,
      'amount': amount,
      'margin': margin,
      'vat': 0,
      'discount': 0,
    };

void main() {
  test('an older server: every margin known, nothing partial', () {
    final r = SalesReport.fromJson({
      'rows': [_row('A', margin: 200), _row('B', margin: 300)],
      'totals': {'amount': 2000, 'margin': 500},
    });

    expect(r.margin, 500);
    expect(r.marginRowsUnknown, 0);
    expect(r.marginIsPartial, isFalse);
    expect(r.marginUnknown, isFalse);
  });

  test('a null margin is unknown, not zero, and the total says partial', () {
    final r = SalesReport.fromJson({
      'rows': [_row('A', margin: 200), _row('B')],
      // The server's margin total covers the known rows only.
      'totals': {'amount': 2000, 'margin': 200, 'marginRowsUnknown': 1},
    });

    expect(r.rows[1].margin, isNull);
    expect(r.margin, 200);
    expect(r.marginRowsUnknown, 1);
    expect(r.marginIsPartial, isTrue);
    expect(r.marginUnknown, isFalse);
  });

  test('no margin known at all: there is no margin figure', () {
    final r = SalesReport.fromJson({
      'rows': [_row('A'), _row('B')],
      'totals': {'amount': 2000, 'margin': 0, 'marginRowsUnknown': 2},
    });

    expect(r.marginUnknown, isTrue);
  });

  test('without the server count, the null rows are counted here', () {
    final r = SalesReport.fromJson({
      'rows': [_row('A', margin: 200), _row('B'), _row('C')],
      'totals': {'amount': 3000},
    });

    expect(r.marginRowsUnknown, 2);
    // Summed from the known rows only.
    expect(r.margin, 200);
  });

  test('an unknown cell is a dash to read and EMPTY in the CSV', () {
    const c = Cell.unknown();

    expect(c.display, '—');
    // A 0 here would be summed by the spreadsheet as a real zero.
    expect(c.csv, '');
    // It sits in a numeric column, which must stay right-aligned.
    expect(c.isNumeric, isTrue);
  });
}
