import '../core/json.dart';
import '../core/session.dart';

/// A row of `StockOnHandDto`.
class StockRow {
  const StockRow({
    required this.uid,
    required this.productCode,
    required this.productName,
    required this.quantity,
    required this.low,
    required this.negative,
    this.reorderLevel,
    this.locationName,
  });

  factory StockRow.fromJson(Map<String, dynamic> j) => StockRow(
        uid: asStrOr(j['uid']),
        productCode: asStrOr(j['productCode']),
        productName: asStrOr(j['productName']),
        quantity: asNumOr(j['quantity']),
        low: asBool(j['low']),
        negative: asBool(j['negative']),
        reorderLevel: asNum(j['reorderLevel']),
        locationName: asStr(j['locationName']),
      );

  final String uid;
  final String productCode;
  final String productName;
  final double quantity;
  final bool low;
  final bool negative;
  final double? reorderLevel;
  final String? locationName;
}

/// A stock-valuation row from `/stock/reports/stock-value`.
/// A row of `ProductStockRowDto`.
class ValuationRowData {
  const ValuationRowData({
    required this.code,
    required this.label,
    required this.quantity,
    required this.value,
    this.unvalued = false,
  });

  factory ValuationRowData.fromJson(Map<String, dynamic> j) {
    final cost = asNum(j['costValue']);
    return ValuationRowData(
      code: asStrOr(j['productCode']),
      label: asStrOr(j['productName'], asStrOr(j['productCode'])),
      quantity: asNumOr(j['quantityOnHand']),
      value: cost ?? 0,
      // costValue is null when a product has never been valued. It is shown
      // as unvalued rather than silently counted as zero.
      unvalued: cost == null,
    );
  }

  final String code;
  final String label;
  final double quantity;
  final double value;
  final bool unvalued;
}

class ValuationResult {
  const ValuationResult({
    required this.rows,
    required this.total,
    required this.unvaluedCount,
    this.branchName,
    this.generatedAt,
  });

  final List<ValuationRowData> rows;
  final double total;

  /// How many lines carried no cost. Surfaced on screen so the total is never
  /// read as complete when it is not.
  final int unvaluedCount;
  final String? branchName;
  final String? generatedAt;
}

/// The reason codes the backend accepts (`AdjustmentReason`), with the wording
/// a storekeeper would use.
const Map<String, String> kAdjustReasons = <String, String>{
  'COUNT_CORRECTION': 'Stock count correction',
  'DAMAGE': 'Damaged',
  'EXPIRY': 'Expired',
  'SHRINKAGE': 'Theft or loss',
  'RECEIPT_CORRECTION': 'Receipt correction',
  'OPENING_STOCK': 'Opening stock',
  'OTHER': 'Other',
};

class StockService {
  StockService(this.session);

  final Session session;

  Future<List<StockRow>> onHand({String? search}) async {
    final res = await session.api.get('/stock/on-hand', query: {
      if (session.companyId != null) 'companyId': session.companyId,
      if (search != null && search.isNotEmpty) 'q': search,
      'size': 200,
    });
    return asList(res, StockRow.fromJson);
  }

  /// Signed delta — positive adds, negative removes.
  Future<void> adjust({
    required String productUid,
    required double quantity,
    required String reasonCode,
    String? note,
  }) async {
    await session.api.post('/stock/adjustments', body: {
      'productUid': productUid,
      'quantity': quantity,
      'reasonCode': reasonCode,
      if (note != null && note.isNotEmpty) 'note': note,
    });
  }

  /// Stock valuation — `/stock/reports/stock-value`.
  ///
  /// The endpoint takes no companyId (it reads the caller's context) and no
  /// as-at date: it always values stock as it stands now.
  Future<ValuationResult> valuation({String? branchUid}) async {
    final res = await session.api.get('/stock/reports/stock-value', query: {
      if (branchUid != null) 'branchUid': branchUid,
    });

    final map = asMap(res);
    final rows = asList(map['rows'], ValuationRowData.fromJson);
    final totals = asMap(map['totals']);

    final declared = asNum(totals['costValue'] ?? totals['totalCostValue']);
    return ValuationResult(
      rows: rows,
      total: declared ?? rows.fold<double>(0, (a, r) => a + r.value),
      unvaluedCount: rows.where((r) => r.unvalued).length,
      branchName: asStr(map['branchName']),
      generatedAt: asStr(map['generatedAt']),
    );
  }
}
