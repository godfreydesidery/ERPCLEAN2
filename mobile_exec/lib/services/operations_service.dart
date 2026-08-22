import '../core/json.dart';
import '../core/session.dart';

/// A line on a direct goods receipt.
///
/// The delivered unit is part of the line, not an assumption. A supplier who
/// delivers 10 cartons of a product stocked in pieces is recorded as 10 CTN at
/// the carton cost; the server converts to base units with the pack factor. The
/// server REQUIRES [unitUid] — a line without it is rejected outright.
class ReceiptLine {
  ReceiptLine({
    required this.productUid,
    required this.productName,
    required this.baseUnit,
    required this.unitUid,
    required this.unit,
    required this.factorToBase,
    required this.qty,
    required this.unitCost,
  });

  final String productUid;
  final String productName;

  /// The product's base unit code — what stock is actually counted in.
  final String baseUnit;

  /// The uid of the unit this delivery came in.
  String unitUid;

  /// That unit's code, for display.
  String unit;

  /// How many base units one [unit] holds. 1 when receiving in the base unit.
  double factorToBase;

  double qty;

  /// Cost of ONE [unit] — per carton when receiving cartons, exactly as the
  /// server reads it.
  double unitCost;

  double get total => qty * unitCost;

  /// What the receipt actually adds to stock.
  double get qtyInBase => qty * factorToBase;

  bool get isBaseUnit => factorToBase == 1;
}

/// An open till session (`PosSessionDto`).
class SessionRow {
  const SessionRow({
    required this.uid,
    required this.tillName,
    required this.cashierName,
    required this.openedAt,
    required this.status,
    required this.openingFloat,
    this.expectedCash,
  });

  factory SessionRow.fromJson(Map<String, dynamic> j) => SessionRow(
        uid: asStrOr(j['uid']),
        tillName: asStrOr(j['tillName']),
        cashierName: asStrOr(j['cashierName']),
        openedAt: asStrOr(j['openedAt']),
        status: asStrOr(j['status']),
        openingFloat: asNumOr(j['openingFloatAmount']),
        expectedCash: asNum(j['expectedCashAmount']),
      );

  final String uid;
  final String tillName;
  final String cashierName;
  final String openedAt;
  final String status;
  final double openingFloat;
  final double? expectedCash;

  bool get isOpen => status.toUpperCase() == 'OPEN';
}

/// A tender subtotal on an X read.
class TenderSubtotal {
  const TenderSubtotal({
    required this.tenderType,
    required this.amount,
    required this.count,
  });

  factory TenderSubtotal.fromJson(Map<String, dynamic> j) => TenderSubtotal(
        tenderType: asStrOr(j['tenderType'], asStrOr(j['type'])),
        amount: asNumOr(j['amount']),
        count: asIntOr(j['count']),
      );

  final String tenderType;
  final double amount;
  final int count;

  /// "MOBILE_MONEY" reads badly on a report; the client says "mobile money".
  String get label => tenderType
      .toLowerCase()
      .replaceAll('_', ' ')
      .replaceFirstMapped(RegExp('^.'), (m) => m[0]!.toUpperCase());
}

/// The X read (`XReadDto`) — also the body of a Z read, taken just before close.
class TillRead {
  const TillRead({
    required this.sessionUid,
    required this.tillName,
    required this.cashierName,
    required this.openedAt,
    required this.openingFloat,
    required this.totalSales,
    required this.cashTender,
    required this.payoutsNet,
    required this.expectedCash,
    required this.invoiceCount,
    required this.tenders,
  });

  factory TillRead.fromJson(Map<String, dynamic> j) => TillRead(
        sessionUid: asStrOr(j['sessionUid']),
        tillName: asStrOr(j['tillName']),
        cashierName: asStrOr(j['cashierName']),
        openedAt: asStrOr(j['openedAt']),
        openingFloat: asNumOr(j['openingFloatAmount']),
        totalSales: asNumOr(j['totalSalesAmount']),
        cashTender: asNumOr(j['cashTenderAmount']),
        payoutsNet: asNumOr(j['totalPayoutsNetAmount']),
        expectedCash: asNumOr(j['expectedCashAmount']),
        invoiceCount: asIntOr(j['invoiceCount']),
        tenders: asList(j['tenderSubtotals'], TenderSubtotal.fromJson),
      );

  final String sessionUid;
  final String tillName;
  final String cashierName;
  final String openedAt;
  final double openingFloat;
  final double totalSales;
  final double cashTender;
  final double payoutsNet;
  final double expectedCash;
  final int invoiceCount;
  final List<TenderSubtotal> tenders;
}

class OperationsService {
  OperationsService(this.session);

  final Session session;

  // -- receiving -----------------------------------------------------------

  /// Receive stock with no purchase order. The backend raises the PO itself
  /// and receives against it in one call (`/goods-receipts/direct`).
  ///
  /// An idempotency key is passed because a retry after a timeout must not
  /// receive the same delivery twice.
  Future<String> receiveDirect({
    required String supplierUid,
    required List<ReceiptLine> lines,
    String? notes,
    required String idempotencyKey,
  }) async {
    final res = await session.api.post(
      '/goods-receipts/direct',
      idempotencyKey: idempotencyKey,
      body: {
        'companyUid': session.companyUid,
        'supplierUid': supplierUid,
        if (notes != null && notes.isNotEmpty) 'notes': notes,
        'lines': [
          for (final l in lines)
            {
              'productUid': l.productUid,
              'unitUid': l.unitUid,
              'receivedQty': l.qty,
              'unitCostAmount': l.unitCost,
              if (l.unitCost == 0) 'note': 'Received at zero cost',
            },
        ],
      },
    );
    return asStrOr(asMap(res)['uid']);
  }

  // -- till sessions -------------------------------------------------------

  Future<List<SessionRow>> openSessions() async {
    final companyId = session.companyId;
    if (companyId == null) return const [];
    final res = await session.api.get('/pos/sessions', query: {
      'companyId': companyId,
      'status': 'OPEN',
    });
    return asList(res, SessionRow.fromJson)
        .where((s) => s.isOpen)
        .toList(growable: false);
  }

  /// X read — looks at the till, changes nothing.
  Future<TillRead> xRead(String sessionUid) async {
    final res = await session.api.get('/pos/sessions/uid/$sessionUid/x-read');
    return TillRead.fromJson(asMap(res));
  }

  /// Z read / close — finalises the session against a counted cash amount.
  /// There is no separate Z endpoint: the close IS the Z.
  Future<SessionRow> closeSession({
    required String sessionUid,
    required double countedCash,
    String? notes,
  }) async {
    final res = await session.api.post(
      '/pos/sessions/uid/$sessionUid/close',
      body: {
        'countedCashAmount': countedCash,
        if (notes != null && notes.isNotEmpty) 'notes': notes,
      },
    );
    return SessionRow.fromJson(asMap(res));
  }
}
