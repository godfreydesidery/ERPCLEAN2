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

/// A line of the Product List report (`ProductStockRowDto`) — the catalogue
/// with what each item cost, what it sells for and what is on the shelf.
///
/// `buyingPrice` is the weighted-average cost of the stock in scope, not a
/// purchase-order price: what the goods on the shelf actually cost. Either
/// price may be null and that is a real answer — a product that has never been
/// costed or never been priced. Rendering those as 0.00 would read as "free".
class ProductLine {
  const ProductLine({
    required this.code,
    required this.name,
    required this.quantityOnHand,
    this.supplierName,
    this.buyingPrice,
    this.costValue,
    this.sellingPrice,
    this.saleValue,
    this.discontinued = false,
  });

  factory ProductLine.fromJson(Map<String, dynamic> j) => ProductLine(
        code: asStrOr(j['productCode']),
        name: asStrOr(j['productName'], asStrOr(j['productCode'])),
        quantityOnHand: asNumOr(j['quantityOnHand']),
        supplierName: asStr(j['supplierName']),
        buyingPrice: asNum(j['buyingPrice']),
        costValue: asNum(j['costValue']),
        sellingPrice: asNum(j['sellingPrice']),
        saleValue: asNum(j['saleValue']),
        discontinued: asBool(j['discontinued']),
      );

  final String code;
  final String name;
  final double quantityOnHand;
  final String? supplierName;

  /// Weighted-average unit cost; null when never valued.
  final double? buyingPrice;
  final double? costValue;

  /// Unit selling price from the default price list; null when never priced.
  final double? sellingPrice;
  final double? saleValue;

  /// Out of the catalogue but still holding stock.
  final bool discontinued;

  /// What one unit earns over what it cost. Null unless both are known — a
  /// margin against an unknown cost is a guess, not a figure.
  double? get margin => (buyingPrice == null || sellingPrice == null)
      ? null
      : sellingPrice! - buyingPrice!;
}

/// The Product List report as a whole.
class ProductListReport {
  const ProductListReport({
    required this.rows,
    required this.currency,
    this.branchName,
    this.priceListName,
    this.priceIncludesVat = false,
    this.generatedAt,
  });

  final List<ProductLine> rows;
  final String currency;
  final String? branchName;

  /// Which price list the selling prices came from. Stated rather than assumed:
  /// a selling price means nothing without knowing whose list it is on.
  final String? priceListName;

  /// Whether those selling prices already include VAT.
  final bool priceIncludesVat;
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

  /// Product List — `/stock/reports/product-list`.
  ///
  /// The catalogue with cost, price and stock on one line. Takes no company id
  /// (it reads the caller's context) and no product filter: it returns every
  /// product, which is what lets the screen search without a round trip.
  Future<ProductListReport> productList({String? branchUid}) async {
    final res = await session.api.get('/stock/reports/product-list', query: {
      if (branchUid != null) 'branchUid': branchUid,
    });

    final map = asMap(res);
    return ProductListReport(
      rows: asList(map['rows'], ProductLine.fromJson),
      currency: asStrOr(map['currency'], 'TZS'),
      branchName: asStr(map['branchName']),
      priceListName: asStr(map['priceListName']),
      priceIncludesVat: asBool(map['priceIncludesVat']),
      generatedAt: asStr(map['generatedAt']),
    );
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
