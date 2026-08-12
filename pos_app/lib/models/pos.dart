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
    this.openSessionCashierName,
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

  /// The occupant's name. The id alone only ever answered "is this shift mine?";
  /// this answers "then whose is it?". The server substitutes a neutral phrase
  /// (never an id) for a cashier it can no longer name, so it prints as-is —
  /// null only when the till is free, or against a server that predates it.
  final String? openSessionCashierName;

  bool get isActive => status == 'ACTIVE';

  /// Who is holding this till, for a sentence like "$holder has a shift open".
  /// Falls back to the wording the till has always shown when unnamed.
  String get occupantHolder {
    final name = openSessionCashierName?.trim();
    return (name == null || name.isEmpty) ? 'Another cashier' : name;
  }

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
        openSessionCashierName: asStr(j['openSessionCashierName']),
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
    this.figuresKnown = true,
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

  /// False only for [PosSession.fromOpenTill] — a shift resumed without ever
  /// reading the session. The uid and the OPEN status are the till's own facts
  /// and safe to act on; the money and the session number are not known, so the
  /// UI must not present them as figures that came from the server. Anything
  /// parsed from the wire is true, so one successful read clears the condition.
  final bool figuresKnown;

  /// The resume fallback: a session assembled from the till row when the
  /// session read itself is refused or the line is down. Without it an occupied
  /// till is a dead end for the cashier who holds it — the shift is open
  /// server-side and only a counted cash-up ever ends one.
  ///
  /// Nothing is invented. The float sits at zero and the session number prints
  /// as the same em-dash the UI shows for anything unknown, both hidden behind
  /// [figuresKnown]; only what the till actually carries is filled in.
  factory PosSession.fromOpenTill(PosTill till,
          {required String sessionUid}) =>
      PosSession(
        id: '',
        uid: sessionUid,
        posTillId: till.id,
        cashierId: till.openSessionCashierId ?? '',
        sessionNumber: '—',
        status: PosSessionStatus.open,
        openedAt: till.openSessionOpenedAt,
        closedAt: null,
        reconciledAt: null,
        openingFloatAmount: 0,
        countedCashAmount: null,
        expectedCashAmount: null,
        varianceAmount: null,
        notes: null,
        figuresKnown: false,
      );

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

/// One payout-type subtotal within an X/Z-read (`PayoutSubtotalDto`, P3).
///
/// [amount] is always POSITIVE — a payout is cash leaving the drawer, and the
/// whole breakdown is subtracted from expected cash. The subtotals always sum
/// to `totalPayoutsNetAmount` on the same read.
///
/// The server zero-fills one row per payout type on every read, in enum order,
/// so a printed report keeps the same shape shift to shift and two reads of one
/// session are byte-identical.
class PayoutSubtotal {
  PayoutSubtotal(
      {required this.payoutType, required this.amount, required this.count});

  final PosPayoutType payoutType;
  final double amount;

  /// How many payouts of this type. Arrives as a JSON **string** (a `Long`),
  /// like `invoiceCount` — parsed with the same tolerant helper.
  final int count;

  factory PayoutSubtotal.fromJson(Map<String, dynamic> j) => PayoutSubtotal(
        payoutType: PosPayoutType.fromWire(asStr(j['payoutType'])),
        amount: asNumOr(j['amount']),
        count: asIntOr(j['count']),
      );
}

/// One recorded cash payout (`PosPayoutDto`, K8) — the row a drawer-expense
/// list is built from.
///
/// [journalEntryUid] is populated ONLY on the response that CREATES the payout;
/// there is no column to read it back from yet, so it is always null when
/// listing. That is a known seam, not a parsing bug.
class PosPayout {
  PosPayout({
    required this.uid,
    required this.payoutType,
    required this.amount,
    required this.reason,
    required this.recordedAt,
    this.journalEntryUid,
  });

  final String uid;
  final PosPayoutType payoutType;
  final double amount;
  final String? reason;
  final DateTime? recordedAt;
  final String? journalEntryUid;

  factory PosPayout.fromJson(Map<String, dynamic> j) => PosPayout(
        uid: asStrOr(j['uid']),
        payoutType: PosPayoutType.fromWire(asStr(j['payoutType'])),
        amount: asNumOr(j['amount']),
        reason: asStr(j['reason']),
        recordedAt: asDate(j['recordedAt']),
        journalEntryUid: asStr(j['journalEntryUid']),
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
    this.payoutSubtotals = const [],
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

  /// Payouts broken down by type (P3). Empty against a server that predates it,
  /// which prints as a single "Payouts" line — the old behaviour, not an error.
  final List<PayoutSubtotal> payoutSubtotals;

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
        payoutSubtotals: asList(j['payoutSubtotals'], PayoutSubtotal.fromJson),
      );
}

/// The end-of-shift Z-read, returned by BOTH
/// `POST /pos/sessions/uid/{uid}/reconcile` (once, the reconciling call) and
/// `GET /pos/sessions/uid/{uid}/z-read` (repeatable, read-only — P3).
///
/// The server builds both through one private builder over persisted columns,
/// so a reprint can never drift from the document the cashier signed off.
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
    this.payoutSubtotals = const [],
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

  /// Payouts broken down by type (P3). Empty against an older server.
  final List<PayoutSubtotal> payoutSubtotals;

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
        payoutSubtotals: asList(j['payoutSubtotals'], PayoutSubtotal.fromJson),
      );
}
