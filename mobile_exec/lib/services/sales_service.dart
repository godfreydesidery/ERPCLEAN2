import '../core/json.dart';
import '../core/session.dart';

/// One row of `SalesReportDto.rows` (a product line).
class SalesReportRow {
  const SalesReportRow({
    required this.productCode,
    required this.productName,
    required this.qtySold,
    required this.amount,
    required this.margin,
    required this.vat,
    required this.discount,
  });

  factory SalesReportRow.fromJson(Map<String, dynamic> j) => SalesReportRow(
        productCode: asStrOr(j['productCode']),
        productName: asStrOr(j['productName']),
        qtySold: asNumOr(j['qtySold']),
        amount: asNumOr(j['amount']),
        // NULL since ERP 1.9.3 when the product's cost of sale was never
        // established. Kept null: reading it as 0 reports the sale as a
        // break-even, and summing it as 0 hides the gap in the total.
        margin: asNum(j['margin']),
        vat: asNumOr(j['vat']),
        discount: asNumOr(j['discount']),
      );

  final String productCode;
  final String productName;
  final double qtySold;
  final double amount;

  /// Null = not known (no cost of sale on record), never "zero".
  final double? margin;
  final double vat;
  final double discount;
}

class SalesReport {
  const SalesReport({
    required this.rows,
    required this.total,
    required this.qtySold,
    required this.margin,
    required this.marginRowsUnknown,
    required this.vat,
    required this.discount,
    required this.currency,
    this.generatedAt,
  });

  /// The `SalesReportDto` envelope body. Totals the server declares win; a
  /// missing one is summed from the rows.
  factory SalesReport.fromJson(Map<String, dynamic> map) {
    final rows = asList(map['rows'], SalesReportRow.fromJson);
    final totals = asMap(map['totals']);

    double sum(String key, double Function(SalesReportRow) pick) {
      final declared = asNum(totals[key]);
      if (declared != null) return declared;
      return rows.fold<double>(0, (a, r) => a + pick(r));
    }

    // The server counts the rows it left out of the margin total (ERP 1.9.3+).
    // An older server sends no count — and never a null margin — so counting
    // the null rows here gives the same answer against either.
    final unknown = asNum(totals['marginRowsUnknown'])?.toInt() ??
        rows.where((r) => r.margin == null).length;

    return SalesReport(
      rows: rows,
      total: sum('amount', (r) => r.amount),
      qtySold: sum('qtySold', (r) => r.qtySold),
      margin: sum('margin', (r) => r.margin ?? 0),
      marginRowsUnknown: unknown,
      vat: sum('vat', (r) => r.vat),
      discount: sum('discount', (r) => r.discount),
      currency: asStrOr(map['currency'], 'TZS'),
      generatedAt: asStr(map['generatedAt']),
    );
  }

  final List<SalesReportRow> rows;
  final double total;
  final double qtySold;

  /// Summed over the rows whose margin is KNOWN only — see [marginIsPartial].
  final double margin;

  /// How many rows [margin] leaves out because their cost is unknown.
  final int marginRowsUnknown;

  final double vat;
  final double discount;
  final String currency;
  final String? generatedAt;

  /// The margin covers only part of the sales and must be shown as partial.
  bool get marginIsPartial => marginRowsUnknown > 0;

  /// No row's margin is known at all — there is no margin figure to show.
  bool get marginUnknown => rows.isNotEmpty && marginRowsUnknown >= rows.length;
}

class SalesService {
  SalesService(this.session);

  final Session session;

  static String _d(DateTime d) =>
      '${d.year.toString().padLeft(4, '0')}-'
      '${d.month.toString().padLeft(2, '0')}-'
      '${d.day.toString().padLeft(2, '0')}';

  /// `/reports/sales` over a date range. This is the endpoint behind the
  /// client's "range of date" request.
  Future<SalesReport> report({
    required DateTime from,
    required DateTime to,
  }) async {
    final res = await session.api.get('/reports/sales', query: {
      if (session.companyId != null) 'companyId': session.companyId,
      'fromDate': _d(from),
      'toDate': _d(to),
    });

    return SalesReport.fromJson(asMap(res));
  }

  /// Today's takings, used by the dashboard.
  Future<SalesReport> today() {
    final now = DateTime.now();
    final d = DateTime(now.year, now.month, now.day);
    return report(from: d, to: d);
  }
}
