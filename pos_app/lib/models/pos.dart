import '../core/json.dart';
import 'enums.dart';

/// A POS till/register (`PosTillDto`).
class PosTill {
  PosTill({
    required this.id,
    required this.uid,
    required this.companyId,
    required this.branchId,
    required this.code,
    required this.name,
    required this.cashBankAccountId,
    required this.status,
    this.hasOpenSession = false,
    this.openSessionUid,
    this.openSessionCashierId,
    this.openSessionOpenedAt,
  });

  final String id;
  final String uid;
  final String companyId;
  final String branchId;
  final String code;
  final String name;
  final String? cashBankAccountId;
  final String status; // MasterStatus

  /// `true` when a POS session in status OPEN already exists for this till, so
  /// the picker can show it as occupied BEFORE the cashier tries to open one
  /// (the backend still enforces this with a 409 as a backstop).
  final bool hasOpenSession;

  /// The occupying session, when there is one. Without these an occupied till is
  /// a dead end — the cashier whose app was force-closed cannot tell their own
  /// abandoned shift from a colleague's live one, so they can neither resume nor
  /// cash it up.
  final String? openSessionUid;
  final String? openSessionCashierId;
  final DateTime? openSessionOpenedAt;

  bool get isActive => status == 'ACTIVE';

  /// True when this till is held by an open shift belonging to [cashierId].
  bool isHeldBy(String? cashierId) =>
      hasOpenSession &&
      cashierId != null &&
      openSessionCashierId != null &&
      openSessionCashierId == cashierId;

  factory PosTill.fromJson(Map<String, dynamic> j) => PosTill(
        id: asStrOr(j['id']),
        uid: asStrOr(j['uid']),
        companyId: asStrOr(j['companyId']),
        branchId: asStrOr(j['branchId']),
        code: asStrOr(j['code']),
        name: asStrOr(j['name']),
        cashBankAccountId: asStr(j['cashBankAccountId']),
        status: asStrOr(j['status'], 'ACTIVE'),
        hasOpenSession: asBool(j['hasOpenSession']),
        openSessionUid: asStr(j['openSessionUid']),
        openSessionCashierId: asStr(j['openSessionCashierId']),
        openSessionOpenedAt: asDate(j['openSessionOpenedAt']),
      );
}

/// A POS cash session/shift (`PosSessionDto`).
class PosSession {
  PosSession({
    required this.id,
    required this.uid,
    required this.posTillId,
    required this.cashierId,
    required this.sessionNumber,
    required this.status,
    required this.openedAt,
    required this.closedAt,
    required this.reconciledAt,
    required this.openingFloatAmount,
    required this.countedCashAmount,
    required this.expectedCashAmount,
    required this.varianceAmount,
    required this.notes,
  });

  final String id;
  final String uid;
  final String posTillId;
  final String cashierId;
  final String sessionNumber;
  final PosSessionStatus status;
  final DateTime? openedAt;
  final DateTime? closedAt;
  final DateTime? reconciledAt;
  final double openingFloatAmount;
  final double? countedCashAmount;
  final double? expectedCashAmount;
  final double? varianceAmount;
  final String? notes;

  factory PosSession.fromJson(Map<String, dynamic> j) => PosSession(
        id: asStrOr(j['id']),
        uid: asStrOr(j['uid']),
        posTillId: asStrOr(j['posTillId']),
        cashierId: asStrOr(j['cashierId']),
        sessionNumber: asStrOr(j['sessionNumber']),
        status: PosSessionStatus.fromWire(asStr(j['status'])),
        openedAt: asDate(j['openedAt']),
        closedAt: asDate(j['closedAt']),
        reconciledAt: asDate(j['reconciledAt']),
        openingFloatAmount: asNumOr(j['openingFloatAmount']),
        countedCashAmount: asNum(j['countedCashAmount']),
        expectedCashAmount: asNum(j['expectedCashAmount']),
        varianceAmount: asNum(j['varianceAmount']),
        notes: asStr(j['notes']),
      );
}

/// One tender-type subtotal within an X/Z-read breakdown (`TenderSubtotalDto`).
///
/// [amount] nets CASH over-tender change (BR-SALES-07), so the CASH entry equals
/// the drawer-affecting `cashTenderAmount`; non-CASH entries are unaffected.
class TenderSubtotal {
  TenderSubtotal({required this.tenderType, required this.amount});

  final TenderType tenderType;
  final double amount;

  factory TenderSubtotal.fromJson(Map<String, dynamic> j) => TenderSubtotal(
        tenderType: TenderType.fromWire(asStr(j['tenderType'])),
        amount: asNumOr(j['amount']),
      );
}

/// `GET /pos/sessions/uid/{uid}/x-read` — mid-shift snapshot.
///
/// [totalSalesAmount] is gross turnover across ALL tenders (a reporting figure);
/// [cashTenderAmount] is the net CASH retained in the drawer — this, not gross
/// sales, is what [expectedCashAmount] reconciles to. [tenderSubtotals] explains
/// the difference by breaking turnover down per tender type.
class XRead {
  XRead({
    required this.sessionUid,
    required this.openedAt,
    required this.openingFloatAmount,
    required this.totalSalesAmount,
    required this.cashTenderAmount,
    required this.totalPayoutsNetAmount,
    required this.expectedCashAmount,
    required this.invoiceCount,
    required this.tenderSubtotals,
  });

  final String sessionUid;
  final DateTime? openedAt;
  final double openingFloatAmount;
  final double totalSalesAmount;
  final double cashTenderAmount;
  final double totalPayoutsNetAmount;
  final double expectedCashAmount;
  final int invoiceCount;
  final List<TenderSubtotal> tenderSubtotals;

  factory XRead.fromJson(Map<String, dynamic> j) => XRead(
        sessionUid: asStrOr(j['sessionUid']),
        openedAt: asDate(j['openedAt']),
        openingFloatAmount: asNumOr(j['openingFloatAmount']),
        totalSalesAmount: asNumOr(j['totalSalesAmount']),
        cashTenderAmount: asNumOr(j['cashTenderAmount']),
        totalPayoutsNetAmount: asNumOr(j['totalPayoutsNetAmount']),
        expectedCashAmount: asNumOr(j['expectedCashAmount']),
        invoiceCount: asIntOr(j['invoiceCount']),
        tenderSubtotals: asList(j['tenderSubtotals'], TenderSubtotal.fromJson),
      );
}

/// `POST /pos/sessions/uid/{uid}/reconcile` — end-of-shift Z-read.
class ZRead {
  ZRead({
    required this.sessionUid,
    required this.openedAt,
    required this.closedAt,
    required this.reconciledAt,
    required this.openingFloatAmount,
    required this.totalSalesAmount,
    required this.cashTenderAmount,
    required this.totalPayoutsNetAmount,
    required this.expectedCashAmount,
    required this.countedCashAmount,
    required this.varianceAmount,
    required this.invoiceCount,
    required this.tenderSubtotals,
  });

  final String sessionUid;
  final DateTime? openedAt;
  final DateTime? closedAt;
  final DateTime? reconciledAt;
  final double openingFloatAmount;
  final double totalSalesAmount;
  final double cashTenderAmount;
  final double totalPayoutsNetAmount;
  final double expectedCashAmount;
  final double countedCashAmount;
  final double varianceAmount;
  final int invoiceCount;
  final List<TenderSubtotal> tenderSubtotals;

  factory ZRead.fromJson(Map<String, dynamic> j) => ZRead(
        sessionUid: asStrOr(j['sessionUid']),
        openedAt: asDate(j['openedAt']),
        closedAt: asDate(j['closedAt']),
        reconciledAt: asDate(j['reconciledAt']),
        openingFloatAmount: asNumOr(j['openingFloatAmount']),
        totalSalesAmount: asNumOr(j['totalSalesAmount']),
        cashTenderAmount: asNumOr(j['cashTenderAmount']),
        totalPayoutsNetAmount: asNumOr(j['totalPayoutsNetAmount']),
        expectedCashAmount: asNumOr(j['expectedCashAmount']),
        countedCashAmount: asNumOr(j['countedCashAmount']),
        varianceAmount: asNumOr(j['varianceAmount']),
        invoiceCount: asIntOr(j['invoiceCount']),
        tenderSubtotals: asList(j['tenderSubtotals'], TenderSubtotal.fromJson),
      );
}
