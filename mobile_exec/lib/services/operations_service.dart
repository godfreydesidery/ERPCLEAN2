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

/// A till session (`PosSessionDto`): OPEN, CLOSED (counted, awaiting
/// reconcile) or RECONCILED (final, has a Z read).
class SessionRow {
  const SessionRow({
    required this.uid,
    required this.tillName,
    required this.cashierName,
    required this.openedAt,
    required this.status,
    required this.openingFloat,
    this.expectedCash,
    this.sessionNumber = '',
    this.branchName,
    this.closedAt,
    this.reconciledAt,
    this.countedCash,
    this.variance,
    this.notes,
  });

  factory SessionRow.fromJson(Map<String, dynamic> j) => SessionRow(
        uid: asStrOr(j['uid']),
        tillName: asStrOr(j['tillName']),
        cashierName: asStrOr(j['cashierName']),
        openedAt: asStrOr(j['openedAt']),
        status: asStrOr(j['status']),
        openingFloat: asNumOr(j['openingFloatAmount']),
        expectedCash: asNum(j['expectedCashAmount']),
        sessionNumber: asStrOr(j['sessionNumber']),
        branchName: asStr(j['branchName']),
        closedAt: asStr(j['closedAt']),
        reconciledAt: asStr(j['reconciledAt']),
        countedCash: asNum(j['countedCashAmount']),
        variance: asNum(j['varianceAmount']),
        notes: asStr(j['notes']),
      );

  final String uid;
  final String tillName;
  final String cashierName;
  final String openedAt;
  final String status;
  final double openingFloat;
  final double? expectedCash;
  final String sessionNumber;
  final String? branchName;
  final String? closedAt;
  final String? reconciledAt;
  final double? countedCash;

  /// Counted minus expected: positive = over, negative = short.
  final double? variance;
  final String? notes;

  bool get isOpen => status.toUpperCase() == 'OPEN';
  bool get isClosed => status.toUpperCase() == 'CLOSED';
  bool get isReconciled => status.toUpperCase() == 'RECONCILED';
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
    this.expectedKnown = true,
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
        expectedKnown: j['expectedCashAmount'] != null,
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

  /// False when the server withheld expected cash. A user without the
  /// reconcile permission counts "blind", so the X read leaves it out.
  final bool expectedKnown;
  final int invoiceCount;
  final List<TenderSubtotal> tenders;
}

/// The Z read (`ZReadDto`): the final figures of a RECONCILED session.
class ZRead {
  const ZRead({
    required this.read,
    required this.countedCash,
    required this.variance,
    this.branchName,
    this.closedAt,
    this.reconciledAt,
  });

  factory ZRead.fromJson(Map<String, dynamic> j) => ZRead(
        read: TillRead.fromJson(j),
        countedCash: asNumOr(j['countedCashAmount']),
        variance: asNumOr(j['varianceAmount']),
        branchName: asStr(j['branchName']),
        closedAt: asStr(j['closedAt']),
        reconciledAt: asStr(j['reconciledAt']),
      );

  /// Takings, tenders and expected cash: the same shape as an X read.
  final TillRead read;
  final double countedCash;

  /// Counted minus expected: positive = over, negative = short.
  final double variance;
  final String? branchName;
  final String? closedAt;
  final String? reconciledAt;
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

  /// Sessions in [status] (CLOSED or RECONCILED), newest first. The list is
  /// paged on the server; the app shows the most recent page.
  Future<List<SessionRow>> sessionsIn(String status, {int size = 30}) async {
    final companyId = session.companyId;
    if (companyId == null) return const [];
    final res = await session.api.get('/pos/sessions', query: {
      'companyId': companyId,
      'status': status,
      'size': size,
      'sort': status == 'RECONCILED' ? 'reconciledAt,desc' : 'closedAt,desc',
    });
    return asList(res, SessionRow.fromJson);
  }

  /// Reconcile a CLOSED session: posts the over/short to the ledger and
  /// returns the Z read. Needs POS.SESSION.RECONCILE.
  Future<ZRead> reconcile(String sessionUid, {String? notes}) async {
    final res = await session.api.post(
      '/pos/sessions/uid/$sessionUid/reconcile',
      body: {
        if (notes != null && notes.isNotEmpty) 'notes': notes,
      },
    );
    return ZRead.fromJson(asMap(res));
  }

  /// The Z read of a RECONCILED session, as often as it is asked.
  Future<ZRead> zRead(String sessionUid) async {
    final res = await session.api.get('/pos/sessions/uid/$sessionUid/z-read');
    return ZRead.fromJson(asMap(res));
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
