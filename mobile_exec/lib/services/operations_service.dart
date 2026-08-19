import '../core/json.dart';
import '../core/session.dart';

/// A line on a direct goods receipt.
class ReceiptLine {
  ReceiptLine({
    required this.productUid,
    required this.productName,
    required this.unit,
    required this.qty,
    required this.unitCost,
  });

  final String productUid;
  final String productName;
  final String unit;
  double qty;
  double unitCost;

  double get total => qty * unitCost;
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
