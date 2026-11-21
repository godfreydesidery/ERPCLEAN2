import '../core/json.dart';

/// POS "refund by item" (ERP ADR-0072): the cashier takes back chosen items of
/// a finalised sale and hands their value back in CASH from the drawer.
///
/// The server is authoritative for every amount. The till only ever shows an
/// *estimate* before the return is sent ([estimateReturnGross]); after it, the
/// return's own [PosSaleReturn.grossAmount] is what the customer is given.

/// One line of a completed return (`PosSaleReturnDto.lines[]`).
class PosSaleReturnLine {
  const PosSaleReturnLine({
    required this.lineNo,
    required this.invoiceLineUid,
    required this.productCode,
    required this.productName,
    required this.unitName,
    required this.qtyReturned,
    required this.netAmount,
    required this.discountAmount,
    required this.vatAmount,
    required this.grossAmount,
  });

  final int lineNo;
  final String invoiceLineUid;
  final String productCode;
  final String productName;
  final String? unitName;
  final double qtyReturned;
  final double netAmount;
  final double discountAmount;
  final double vatAmount;
  final double grossAmount;

  factory PosSaleReturnLine.fromJson(Map<String, dynamic> j) => PosSaleReturnLine(
        lineNo: asIntOr(j['lineNo']),
        invoiceLineUid: asStrOr(j['invoiceLineUid']),
        productCode: asStrOr(j['productCode']),
        productName: asStrOr(j['productName']),
        unitName: asStr(j['unitName']),
        qtyReturned: asNumOr(j['qtyReturned']),
        netAmount: asNumOr(j['netAmount']),
        discountAmount: asNumOr(j['discountAmount']),
        vatAmount: asNumOr(j['vatAmount']),
        grossAmount: asNumOr(j['grossAmount']),
      );
}

/// A completed item return (`PosSaleReturnDto`) — what the refund slip prints.
class PosSaleReturn {
  const PosSaleReturn({
    required this.uid,
    required this.returnNo,
    required this.invoiceUid,
    required this.invoiceNumber,
    required this.reason,
    required this.netAmount,
    required this.discountAmount,
    required this.vatAmount,
    required this.grossAmount,
    required this.currency,
    required this.refundTender,
    required this.authorisedBy,
    required this.createdByName,
    required this.createdAt,
    required this.lines,
    this.payoutUid,
    this.sessionUid,
  });

  final String uid;

  /// `PRT-0001`, numbered per company.
  final String returnNo;
  final String invoiceUid;
  final String invoiceNumber;
  final String reason;
  final double netAmount;
  final double discountAmount;
  final double vatAmount;

  /// **The cash handed back to the customer.**
  final double grossAmount;
  final String currency;

  /// Always `CASH` (owner ruling 2026-11-21).
  final String refundTender;

  /// The approving supervisor's name; null when a supervisor acted on their own
  /// authority.
  final String? authorisedBy;
  final String? createdByName;
  final DateTime? createdAt;
  final List<PosSaleReturnLine> lines;
  final String? payoutUid;
  final String? sessionUid;

  factory PosSaleReturn.fromJson(Map<String, dynamic> j) => PosSaleReturn(
        uid: asStrOr(j['uid']),
        returnNo: asStrOr(j['returnNo']),
        invoiceUid: asStrOr(j['invoiceUid']),
        invoiceNumber: asStrOr(j['invoiceNumber']),
        reason: asStrOr(j['reason']),
        netAmount: asNumOr(j['netAmount']),
        discountAmount: asNumOr(j['discountAmount']),
        vatAmount: asNumOr(j['vatAmount']),
        grossAmount: asNumOr(j['grossAmount']),
        currency: asStrOr(j['currency']),
        refundTender: asStrOr(j['refundTender'], 'CASH'),
        authorisedBy: asStr(j['authorisedBy']),
        createdByName: asStr(j['createdByName']),
        createdAt: asDate(j['createdAt']),
        lines: asList(j['lines'], PosSaleReturnLine.fromJson),
        payoutUid: asStr(j['payoutUid']),
        sessionUid: asStr(j['sessionUid']),
      );
}

/// A sold line as the "Return items" screen sees it
/// (`PosSaleReturnsDto.lines[]`); every quantity is in the sold line's unit.
class ReturnableLine {
  const ReturnableLine({
    required this.invoiceLineUid,
    required this.lineNo,
    required this.productCode,
    required this.productName,
    required this.unitName,
    required this.qtySold,
    required this.qtyReturned,
    required this.qtyRemaining,
    required this.grossAmount,
    required this.grossReturned,
  });

  final String invoiceLineUid;
  final int lineNo;
  final String productCode;
  final String productName;
  final String? unitName;
  final double qtySold;
  final double qtyReturned;
  final double qtyRemaining;

  /// What the customer paid for the whole line.
  final double grossAmount;

  /// What earlier returns of this line already refunded.
  final double grossReturned;

  bool get fullyReturned => qtyRemaining <= 0;

  /// Whether a part-unit may be returned. The returns read carries no unit
  /// flags, so the sale itself is the evidence: a line sold (or left) in a
  /// fraction is a weighed / measured unit, and one sold in whole units is
  /// returned in whole units — the way the register only lets a fractional
  /// unit take a fraction.
  bool get allowsDecimals => qtySold % 1 != 0 || qtyRemaining % 1 != 0;

  factory ReturnableLine.fromJson(Map<String, dynamic> j) => ReturnableLine(
        invoiceLineUid: asStrOr(j['invoiceLineUid']),
        lineNo: asIntOr(j['lineNo']),
        productCode: asStrOr(j['productCode']),
        productName: asStrOr(j['productName']),
        unitName: asStr(j['unitName']),
        qtySold: asNumOr(j['qtySold']),
        qtyReturned: asNumOr(j['qtyReturned']),
        qtyRemaining: asNumOr(j['qtyRemaining']),
        grossAmount: asNumOr(j['grossAmount']),
        grossReturned: asNumOr(j['grossReturned']),
      );
}

/// `GET /pos/sales/uid/{uid}/returns` — what can still be returned, and the
/// returns already made.
class PosSaleReturns {
  const PosSaleReturns({
    required this.invoiceUid,
    required this.invoiceNumber,
    required this.hasReturns,
    required this.returnedGross,
    required this.returnable,
    required this.returnBlockedReason,
    required this.lines,
    required this.returns,
  });

  final String invoiceUid;
  final String invoiceNumber;
  final bool hasReturns;
  final double returnedGross;

  /// Advisory: false with a friendly [returnBlockedReason] when the sale cannot
  /// take a return (credit / complimentary sale, session closed, all returned).
  /// The POST re-checks everything.
  final bool returnable;
  final String? returnBlockedReason;
  final List<ReturnableLine> lines;
  final List<PosSaleReturn> returns;

  factory PosSaleReturns.fromJson(Map<String, dynamic> j) => PosSaleReturns(
        invoiceUid: asStrOr(j['invoiceUid']),
        invoiceNumber: asStrOr(j['invoiceNumber']),
        hasReturns: asBool(j['hasReturns']),
        returnedGross: asNumOr(j['returnedGross']),
        returnable: asBool(j['returnable']),
        returnBlockedReason: asStr(j['returnBlockedReason']),
        lines: asList(j['lines'], ReturnableLine.fromJson),
        returns: asList(j['returns'], PosSaleReturn.fromJson),
      );
}

/// The quantity step the till rounds a typed return quantity to: three places,
/// as the receipt prints them (the server accepts up to six).
const int kReturnQtyDecimals = 3;

/// Clamps a typed / stepped return quantity to what [line] can still take:
/// never below 0, never above [ReturnableLine.qtyRemaining], whole units only
/// unless the line [ReturnableLine.allowsDecimals]. 0 means "not returning".
double clampReturnQty(ReturnableLine line, double qty) {
  if (qty.isNaN || qty <= 0 || line.fullyReturned) return 0;
  var q = line.allowsDecimals
      ? _round(qty, kReturnQtyDecimals)
      : qty.floorToDouble();
  if (q > line.qtyRemaining) q = line.qtyRemaining;
  return q < 0 ? 0 : q;
}

/// What the cashier sees before sending the return — **an estimate only**.
///
/// Pro-rates the line's paid gross by quantity. Returning everything that is
/// left takes the exact remainder (`grossAmount - grossReturned`), as the
/// server does, so the last of several partial returns lands on the cent.
double estimateReturnGross(ReturnableLine line, double qty) {
  if (qty <= 0 || line.qtySold <= 0) return 0;
  if (qty >= line.qtyRemaining) {
    final rest = line.grossAmount - line.grossReturned;
    return rest < 0 ? 0 : _round(rest, 2);
  }
  return _round(line.grossAmount * qty / line.qtySold, 2);
}

/// The estimated refund for a whole selection, keyed by invoice-line uid.
double estimateSelectionGross(
    List<ReturnableLine> lines, Map<String, double> selected) {
  var total = 0.0;
  for (final l in lines) {
    final q = selected[l.invoiceLineUid] ?? 0;
    if (q > 0) total += estimateReturnGross(l, q);
  }
  return _round(total, 2);
}

/// The `PosSaleReturnRequest` body: only lines with a positive quantity, in the
/// sale's line order, each once. `authorisedByUid` is left out when a
/// supervisor acts on their own authority.
Map<String, dynamic> buildReturnRequest({
  required String reason,
  required String? authorisedByUid,
  required List<ReturnableLine> lines,
  required Map<String, double> selected,
}) {
  final uid = authorisedByUid?.trim();
  return {
    'reason': reason.trim(),
    if (uid != null && uid.isNotEmpty) 'authorisedByUid': uid,
    'lines': [
      for (final l in lines)
        if ((selected[l.invoiceLineUid] ?? 0) > 0)
          {'invoiceLineUid': l.invoiceLineUid, 'qty': selected[l.invoiceLineUid]},
    ],
  };
}

double _round(double v, int places) {
  var f = 1.0;
  for (var i = 0; i < places; i++) {
    f *= 10;
  }
  return (v * f).roundToDouble() / f;
}
