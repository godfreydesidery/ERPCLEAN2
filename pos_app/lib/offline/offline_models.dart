/// The offline POS records the till keeps until it can sync (ERP ADR-0068), and
/// the wire shapes of `POST /pos/offline-sync`.
///
/// Every record round-trips through its own `toJson`/`fromJson` (the encrypted
/// queue on the device); `toWire` is the exact body the server's
/// `OfflineSyncRequest` takes, and nothing more.
library;

import '../core/json.dart';
import '../models/enums.dart';
import '../state/cart_controller.dart' show roundMinor;

/// Why the till went offline (`OfflinePeriodReason`).
enum OfflineReason {
  /// The till's own reachability check failed; any signed-in cashier accepted.
  unreachable('UNREACHABLE'),

  /// Switched by hand while the server answered; a manager approved it.
  manual('MANUAL');

  const OfflineReason(this.wire);
  final String wire;

  static OfflineReason fromWire(String? w) =>
      OfflineReason.values.firstWhere((r) => r.wire == w, orElse: () => unreachable);
}

/// The tenders an offline sale may take (D-3). Credit, cheque and complimentary
/// need the server or a manager, so they are not offered offline.
const List<TenderType> kOfflineTenders = [
  TenderType.cash,
  TenderType.mobileMoney,
  TenderType.card,
];

/// True when [t] needs a reference (transaction number / terminal approval).
bool offlineTenderNeedsReference(TenderType t) =>
    t == TenderType.mobileMoney || t == TenderType.card;

/// One line of an offline sale, at the price the till charged — the server's
/// own cached answer, never one the till worked out (ADR-0068 D-2, D-3).
class OfflineLine {
  const OfflineLine({
    required this.productId,
    required this.productUid,
    required this.productCode,
    required this.productName,
    required this.unitId,
    required this.unitName,
    required this.quantity,
    required this.unitPrice,
    required this.priceInclusive,
    required this.vatFraction,
    required this.vatStatus,
  });

  final String productId;
  final String productUid;
  final String productCode;
  final String productName;
  final String unitId;
  final String unitName;
  final double quantity;

  /// The price for ONE [unitId], as the server's resolver gave it.
  final double unitPrice;

  /// True when [unitPrice] is GROSS (a VAT-inclusive price list); false = NET.
  final bool priceInclusive;

  /// VAT rate as a fraction (0.18), for the receipt's VAT split only.
  final double vatFraction;
  final String vatStatus;

  /// Net, VAT and gross of the line, rounded per line as the server does
  /// (`InvoiceTotalsCalculator`): a NET price adds VAT on top, a GROSS price
  /// takes it out. For the printed receipt; the server derives its own.
  ({double net, double vat, double gross}) amounts(int minorUnits) {
    if (priceInclusive) {
      final gross = roundMinor(quantity * unitPrice, minorUnits);
      final net = roundMinor(gross / (1 + vatFraction), minorUnits);
      return (net: net, vat: roundMinor(gross - net, minorUnits), gross: gross);
    }
    final net = roundMinor(quantity * unitPrice, minorUnits);
    final vat = roundMinor(net * vatFraction, minorUnits);
    return (net: net, vat: vat, gross: net + vat);
  }

  Map<String, dynamic> toWire() => {
        'productId': productId,
        'unitId': unitId,
        'quantity': quantity,
        'unitPrice': unitPrice,
        'priceInclusive': priceInclusive,
      };

  Map<String, dynamic> toJson() => {
        'productId': productId,
        'productUid': productUid,
        'productCode': productCode,
        'productName': productName,
        'unitId': unitId,
        'unitName': unitName,
        'quantity': quantity,
        'unitPrice': unitPrice,
        'priceInclusive': priceInclusive,
        'vatFraction': vatFraction,
        'vatStatus': vatStatus,
      };

  factory OfflineLine.fromJson(Map<String, dynamic> j) => OfflineLine(
        productId: asStrOr(j['productId']),
        productUid: asStrOr(j['productUid']),
        productCode: asStrOr(j['productCode']),
        productName: asStrOr(j['productName']),
        unitId: asStrOr(j['unitId']),
        unitName: asStrOr(j['unitName']),
        quantity: asNumOr(j['quantity']),
        unitPrice: asNumOr(j['unitPrice']),
        priceInclusive: asBool(j['priceInclusive']),
        vatFraction: asNumOr(j['vatFraction']),
        vatStatus: asStrOr(j['vatStatus'], 'STANDARD'),
      );
}

/// One payment on an offline sale. The amount is what the sale RECORDS: for
/// cash that is the cash kept (tendered less change), so the payments add up
/// to the sale and the server books no over-payment.
class OfflineTender {
  const OfflineTender({required this.type, required this.amount, this.reference});

  final TenderType type;
  final double amount;
  final String? reference;

  Map<String, dynamic> toWire() => {
        'tenderType': type.wire,
        'amount': amount,
        if (reference != null && reference!.trim().isNotEmpty) 'reference': reference!.trim(),
      };

  Map<String, dynamic> toJson() => toWire();

  factory OfflineTender.fromJson(Map<String, dynamic> j) => OfflineTender(
        type: TenderType.fromWire(asStr(j['tenderType'])),
        amount: asNumOr(j['amount']),
        reference: asStr(j['reference']),
      );
}

/// A sale rung while offline, waiting in the encrypted queue for the server.
class OfflineSale {
  const OfflineSale({
    required this.idempotencyKey,
    required this.offlineNumber,
    required this.soldAt,
    required this.tillUid,
    required this.branchUid,
    required this.customerId,
    required this.customerName,
    required this.currency,
    required this.lines,
    required this.tenders,
    required this.grossTotal,
    this.sessionUid,
    this.sessionClientKey,
    this.ageVerified = false,
    this.notes,
    this.cashierName = '',
    this.periodKey,
    this.cashTendered,
  });

  final String idempotencyKey;

  /// The provisional number printed on the receipt, e.g. `OFF-T2K7-000153`.
  final String offlineNumber;

  /// When it was sold (UTC) — the server posts it on this time, not the sync's.
  final DateTime soldAt;
  final String tillUid;

  /// The till's branch; the sync must be sent from it (`X-Branch-Uid`).
  final String branchUid;

  /// The server shift, or — for a shift opened offline — its [sessionClientKey].
  /// Exactly one is set.
  final String? sessionUid;
  final String? sessionClientKey;

  final String customerId;
  final String customerName;
  final String currency;
  final List<OfflineLine> lines;
  final List<OfflineTender> tenders;

  /// What the till showed the customer. The server compares, never trusts it.
  final double grossTotal;
  final bool ageVerified;
  final String? notes;

  /// Who rang it — printed on a reprint.
  final String cashierName;

  /// The offline period it was sold in.
  final String? periodKey;

  /// Cash the customer handed over (for the change line on a reprint).
  final double? cashTendered;

  OfflineSale copyWith({String? sessionUid, bool clearClientKey = false}) => OfflineSale(
        idempotencyKey: idempotencyKey,
        offlineNumber: offlineNumber,
        soldAt: soldAt,
        tillUid: tillUid,
        branchUid: branchUid,
        sessionUid: sessionUid ?? this.sessionUid,
        sessionClientKey: clearClientKey ? null : sessionClientKey,
        customerId: customerId,
        customerName: customerName,
        currency: currency,
        lines: lines,
        tenders: tenders,
        grossTotal: grossTotal,
        ageVerified: ageVerified,
        notes: notes,
        cashierName: cashierName,
        periodKey: periodKey,
        cashTendered: cashTendered,
      );

  Map<String, dynamic> toWire() => {
        'idempotencyKey': idempotencyKey,
        'offlineNumber': offlineNumber,
        'soldAt': soldAt.toUtc().toIso8601String(),
        'sessionUid': sessionUid,
        'sessionClientKey': sessionUid == null ? sessionClientKey : null,
        'customerId': customerId,
        'currency': currency,
        'lines': [for (final l in lines) l.toWire()],
        'tenders': [for (final t in tenders) t.toWire()],
        'grossTotal': grossTotal,
        'ageVerified': ageVerified,
        'notes': notes,
      };

  Map<String, dynamic> toJson() => {
        'idempotencyKey': idempotencyKey,
        'offlineNumber': offlineNumber,
        'soldAt': soldAt.toUtc().toIso8601String(),
        'tillUid': tillUid,
        'branchUid': branchUid,
        'sessionUid': sessionUid,
        'sessionClientKey': sessionClientKey,
        'customerId': customerId,
        'customerName': customerName,
        'currency': currency,
        'lines': [for (final l in lines) l.toJson()],
        'tenders': [for (final t in tenders) t.toJson()],
        'grossTotal': grossTotal,
        'ageVerified': ageVerified,
        'notes': notes,
        'cashierName': cashierName,
        'periodKey': periodKey,
        'cashTendered': cashTendered,
      };

  factory OfflineSale.fromJson(Map<String, dynamic> j) => OfflineSale(
        idempotencyKey: asStrOr(j['idempotencyKey']),
        offlineNumber: asStrOr(j['offlineNumber']),
        soldAt: (asDate(j['soldAt']) ?? DateTime.now()).toUtc(),
        tillUid: asStrOr(j['tillUid']),
        branchUid: asStrOr(j['branchUid']),
        sessionUid: asStr(j['sessionUid']),
        sessionClientKey: asStr(j['sessionClientKey']),
        customerId: asStrOr(j['customerId']),
        customerName: asStrOr(j['customerName']),
        currency: asStrOr(j['currency'], 'TZS'),
        lines: asList(j['lines'], OfflineLine.fromJson),
        tenders: asList(j['tenders'], OfflineTender.fromJson),
        grossTotal: asNumOr(j['grossTotal']),
        ageVerified: asBool(j['ageVerified']),
        notes: asStr(j['notes']),
        cashierName: asStrOr(j['cashierName']),
        periodKey: asStr(j['periodKey']),
        cashTendered: asNum(j['cashTendered']),
      );
}

/// A shift the till opened while offline (D-4). The server creates it as a
/// real shift at sync, idempotently on [clientKey].
class OfflineShift {
  const OfflineShift({
    required this.clientKey,
    required this.tillUid,
    required this.branchUid,
    required this.openedAt,
    required this.openingFloat,
    required this.cashierId,
    this.serverSessionUid,
    this.serverSessionNumber,
  });

  final String clientKey;
  final String tillUid;
  final String branchUid;
  final DateTime openedAt;
  final double openingFloat;
  final String cashierId;

  /// Set once a sync answered with the server's shift.
  final String? serverSessionUid;
  final String? serverSessionNumber;

  bool get adopted => serverSessionUid != null;

  OfflineShift adopt(String uid, String? number) => OfflineShift(
        clientKey: clientKey,
        tillUid: tillUid,
        branchUid: branchUid,
        openedAt: openedAt,
        openingFloat: openingFloat,
        cashierId: cashierId,
        serverSessionUid: uid,
        serverSessionNumber: number,
      );

  Map<String, dynamic> toWire() => {
        'clientKey': clientKey,
        'openedAt': openedAt.toUtc().toIso8601String(),
        'openingFloatAmount': openingFloat,
      };

  Map<String, dynamic> toJson() => {
        'clientKey': clientKey,
        'tillUid': tillUid,
        'branchUid': branchUid,
        'openedAt': openedAt.toUtc().toIso8601String(),
        'openingFloat': openingFloat,
        'cashierId': cashierId,
        'serverSessionUid': serverSessionUid,
        'serverSessionNumber': serverSessionNumber,
      };

  factory OfflineShift.fromJson(Map<String, dynamic> j) => OfflineShift(
        clientKey: asStrOr(j['clientKey']),
        tillUid: asStrOr(j['tillUid']),
        branchUid: asStrOr(j['branchUid']),
        openedAt: (asDate(j['openedAt']) ?? DateTime.now()).toUtc(),
        openingFloat: asNumOr(j['openingFloat']),
        cashierId: asStrOr(j['cashierId']),
        serverSessionUid: asStr(j['serverSessionUid']),
        serverSessionNumber: asStr(j['serverSessionNumber']),
      );
}

/// One stretch of offline selling (D-1, D-8): who switched, why, how long.
class OfflinePeriod {
  const OfflinePeriod({
    required this.clientKey,
    required this.tillUid,
    required this.branchUid,
    required this.reason,
    required this.startedAt,
    this.switchedByUid,
    this.endedAt,
    this.salesCount = 0,
    this.sessionUid,
    this.sessionClientKey,
  });

  final String clientKey;
  final String tillUid;
  final String branchUid;
  final OfflineReason reason;

  /// The manager who approved a MANUAL switch; null = the signed-in user.
  final String? switchedByUid;
  final DateTime startedAt;
  final DateTime? endedAt;
  final int salesCount;
  final String? sessionUid;
  final String? sessionClientKey;

  bool get ended => endedAt != null;

  OfflinePeriod copyWith({
    DateTime? endedAt,
    int? salesCount,
    String? sessionUid,
    String? sessionClientKey,
    bool clearClientKey = false,
  }) =>
      OfflinePeriod(
        clientKey: clientKey,
        tillUid: tillUid,
        branchUid: branchUid,
        reason: reason,
        switchedByUid: switchedByUid,
        startedAt: startedAt,
        endedAt: endedAt ?? this.endedAt,
        salesCount: salesCount ?? this.salesCount,
        sessionUid: sessionUid ?? this.sessionUid,
        sessionClientKey: clearClientKey ? null : (sessionClientKey ?? this.sessionClientKey),
      );

  Map<String, dynamic> toWire() => {
        'clientKey': clientKey,
        'reason': reason.wire,
        'switchedByUid': switchedByUid,
        'startedAt': startedAt.toUtc().toIso8601String(),
        'endedAt': endedAt?.toUtc().toIso8601String(),
        'salesCount': salesCount,
        'sessionUid': sessionUid,
        'sessionClientKey': sessionUid == null ? sessionClientKey : null,
      };

  Map<String, dynamic> toJson() => {
        ...toWire(),
        'tillUid': tillUid,
        'branchUid': branchUid,
        'sessionClientKey': sessionClientKey,
      };

  factory OfflinePeriod.fromJson(Map<String, dynamic> j) => OfflinePeriod(
        clientKey: asStrOr(j['clientKey']),
        tillUid: asStrOr(j['tillUid']),
        branchUid: asStrOr(j['branchUid']),
        reason: OfflineReason.fromWire(asStr(j['reason'])),
        switchedByUid: asStr(j['switchedByUid']),
        startedAt: (asDate(j['startedAt']) ?? DateTime.now()).toUtc(),
        endedAt: asDate(j['endedAt'])?.toUtc(),
        salesCount: asIntOr(j['salesCount']),
        sessionUid: asStr(j['sessionUid']),
        sessionClientKey: asStr(j['sessionClientKey']),
      );
}

/// A sale the server HELD instead of posting (not on the books until a manager
/// deals with it on the review list). Kept on the till so the cashier sees it.
class HeldSale {
  const HeldSale({
    required this.offlineNumber,
    required this.message,
    required this.grossTotal,
    required this.currency,
    required this.soldAt,
    required this.heldAt,
  });

  final String offlineNumber;
  final String message;
  final double grossTotal;
  final String currency;
  final DateTime soldAt;
  final DateTime heldAt;

  Map<String, dynamic> toJson() => {
        'offlineNumber': offlineNumber,
        'message': message,
        'grossTotal': grossTotal,
        'currency': currency,
        'soldAt': soldAt.toUtc().toIso8601String(),
        'heldAt': heldAt.toUtc().toIso8601String(),
      };

  factory HeldSale.fromJson(Map<String, dynamic> j) => HeldSale(
        offlineNumber: asStrOr(j['offlineNumber']),
        message: asStrOr(j['message']),
        grossTotal: asNumOr(j['grossTotal']),
        currency: asStrOr(j['currency']),
        soldAt: (asDate(j['soldAt']) ?? DateTime.now()).toUtc(),
        heldAt: (asDate(j['heldAt']) ?? DateTime.now()).toUtc(),
      );
}

/// One sale's outcome in the sync answer (`OfflineSyncResultDto.SaleResult`).
class SyncSaleResult {
  const SyncSaleResult({
    required this.offlineNumber,
    required this.idempotencyKey,
    required this.status,
    this.invoiceUid,
    this.invoiceNumber,
    this.flags = const [],
    this.message,
  });

  final String offlineNumber;
  final String idempotencyKey;

  /// POSTED, ALREADY_POSTED or HELD — each one settles the sale on the till.
  final String status;
  final String? invoiceUid;
  final String? invoiceNumber;
  final List<String> flags;
  final String? message;

  bool get isHeld => status == 'HELD';

  /// True for every status the contract defines. An unknown status is NOT
  /// treated as settled: the sale stays queued and is sent again, which is safe
  /// because the server is idempotent on the key.
  bool get settles => status == 'POSTED' || status == 'ALREADY_POSTED' || status == 'HELD';

  factory SyncSaleResult.fromJson(Map<String, dynamic> j) => SyncSaleResult(
        offlineNumber: asStrOr(j['offlineNumber']),
        idempotencyKey: asStrOr(j['idempotencyKey']),
        status: asStrOr(j['status']),
        invoiceUid: asStr(j['invoiceUid']),
        invoiceNumber: asStr(j['invoiceNumber']),
        flags: j['flags'] is List ? [for (final f in j['flags'] as List) f.toString()] : const [],
        message: asStr(j['message']),
      );
}

/// The server's whole answer to one sync call (`OfflineSyncResultDto`).
class SyncAnswer {
  const SyncAnswer({
    this.sessions = const [],
    this.sales = const [],
    this.periodsRecorded = 0,
  });

  final List<({String clientKey, String sessionUid, String? sessionNumber})> sessions;
  final List<SyncSaleResult> sales;
  final int periodsRecorded;

  factory SyncAnswer.fromJson(Map<String, dynamic> j) => SyncAnswer(
        sessions: [
          for (final s in asList(j['sessions'], (m) => m))
            (
              clientKey: asStrOr(s['clientKey']),
              sessionUid: asStrOr(s['sessionUid']),
              sessionNumber: asStr(s['sessionNumber']),
            ),
        ],
        sales: asList(j['sales'], SyncSaleResult.fromJson),
        periodsRecorded: asIntOr(j['periodsRecorded']),
      );
}
