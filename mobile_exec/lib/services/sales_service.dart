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
        margin: asNumOr(j['margin']),
        vat: asNumOr(j['vat']),
        discount: asNumOr(j['discount']),
      );

  final String productCode;
  final String productName;
  final double qtySold;
  final double amount;
  final double margin;
  final double vat;
  final double discount;
}

class SalesReport {
  const SalesReport({
    required this.rows,
    required this.total,
    required this.qtySold,
    required this.margin,
    required this.vat,
    required this.discount,
    required this.currency,
    this.generatedAt,
  });

  final List<SalesReportRow> rows;
  final double total;
  final double qtySold;
  final double margin;
  final double vat;
  final double discount;
  final String currency;
  final String? generatedAt;
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

    final map = asMap(res);
    final rows = asList(map['rows'], SalesReportRow.fromJson);
    final totals = asMap(map['totals']);

    double sum(String key, double Function(SalesReportRow) pick) {
      final declared = asNum(totals[key]);
      if (declared != null) return declared;
      return rows.fold<double>(0, (a, r) => a + pick(r));
    }

    return SalesReport(
      rows: rows,
      total: sum('amount', (r) => r.amount),
      qtySold: sum('qtySold', (r) => r.qtySold),
      margin: sum('margin', (r) => r.margin),
      vat: sum('vat', (r) => r.vat),
      discount: sum('discount', (r) => r.discount),
      currency: asStrOr(map['currency'], 'TZS'),
      generatedAt: asStr(map['generatedAt']),
    );
  }

  /// Today's takings, used by the dashboard.
  Future<SalesReport> today() {
    final now = DateTime.now();
    final d = DateTime(now.year, now.month, now.day);
    return report(from: d, to: d);
  }
}
