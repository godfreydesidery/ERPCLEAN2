import '../core/json.dart';
import 'enums.dart';

/// A single tender on a POS sale request (`PosSaleRequest.PosTender`). Populate
/// only the instrument field relevant to [tenderType].
class PosTender {
  PosTender({
    required this.tenderType,
    required this.amount,
    this.reference,
    this.cashBankAccountId,
    this.chequeId,
    this.mobileMoneyRef,
    this.cardRef,
  });

  final TenderType tenderType;
  final double amount;
  final String? reference;
  final String? cashBankAccountId;
  final String? chequeId;
  final String? mobileMoneyRef;
  final String? cardRef;

  Map<String, dynamic> toJson() => {
        'tenderType': tenderType.wire,
        'amount': amount,
        if (reference != null) 'reference': reference,
        if (cashBankAccountId != null) 'cashBankAccountId': cashBankAccountId,
        if (chequeId != null) 'chequeId': chequeId,
        if (mobileMoneyRef != null) 'mobileMoneyRef': mobileMoneyRef,
        if (cardRef != null) 'cardRef': cardRef,
      };
}

/// `GET /pos/sales/idempotency/{key}` — the definitive answer to "did the sale
/// I sent under this reference actually post?" (K11).
///
/// Cheap and read-only: it consults the idempotency marker alone, runs no
/// business guard and touches neither the session nor stock, so it still
/// answers correctly long after the shift was closed. That is precisely what
/// the old "replay the whole sale" recovery could not do — days later it
/// answered "the session is closed", which is not an answer, so the unfinished
/// sale question could never be closed out.
class PosSaleLookup {
  const PosSaleLookup({
    required this.idempotencyKey,
    required this.verdict,
    required this.message,
    this.invoiceUid,
    this.invoiceNumber,
    this.grossTotalAmount,
  });

  final String idempotencyKey;
  final PosSaleLookupVerdict verdict;
  final String message;

  /// Populated only when [verdict] is POSTED.
  final String? invoiceUid;
  final String? invoiceNumber;
  final double? grossTotalAmount;

  factory PosSaleLookup.fromJson(Map<String, dynamic> j) => PosSaleLookup(
        idempotencyKey: asStrOr(j['idempotencyKey']),
        verdict: PosSaleLookupVerdict.fromWire(asStr(j['verdict'])),
        message: asStrOr(j['message']),
        invoiceUid: asStr(j['invoiceUid']),
        invoiceNumber: asStr(j['invoiceNumber']),
        grossTotalAmount: asNum(j['grossTotalAmount']),
      );
}

/// The finalised invoice header returned by `POST /pos/sales` — the **receipt of
/// record** (AS-8). All money is server-authoritative.
class SalesInvoice {
  SalesInvoice({
    required this.id,
    required this.uid,
    required this.invoiceNumber,
    required this.status,
    required this.customerId,
    required this.customerName,
    required this.agentName,
    required this.currency,
    required this.netTotalAmount,
    required this.vatTotalAmount,
    required this.grossTotalAmount,
    required this.taxSummary,
    required this.finalisedAt,
    required this.notes,
  });

  final String id;
  final String uid;
  final String invoiceNumber;
  final InvoiceStatus status;
  final String customerId;
  final String? customerName;
  final String? agentName;
  final String currency;
  final double netTotalAmount;
  final double vatTotalAmount;
  final double grossTotalAmount;
  final String? taxSummary;
  final DateTime? finalisedAt;
  final String? notes;

  factory SalesInvoice.fromJson(Map<String, dynamic> j) => SalesInvoice(
        id: asStrOr(j['id']),
        uid: asStrOr(j['uid']),
        invoiceNumber: asStrOr(j['invoiceNumber']),
        status: InvoiceStatus.fromWire(asStr(j['status'])),
        customerId: asStrOr(j['customerId']),
        customerName: asStr(j['customerName']),
        agentName: asStr(j['agentName']),
        currency: asStrOr(j['currency']),
        netTotalAmount: asNumOr(j['netTotalAmount']),
        vatTotalAmount: asNumOr(j['vatTotalAmount']),
        grossTotalAmount: asNumOr(j['grossTotalAmount']),
        taxSummary: asStr(j['taxSummary']),
        finalisedAt: asDate(j['finalisedAt']),
        notes: asStr(j['notes']),
      );

  Map<String, dynamic> toJson() => {
        'id': id,
        'uid': uid,
        'invoiceNumber': invoiceNumber,
        'status': status.wire,
        'customerId': customerId,
        'customerName': customerName,
        'agentName': agentName,
        'currency': currency,
        'netTotalAmount': netTotalAmount,
        'vatTotalAmount': vatTotalAmount,
        'grossTotalAmount': grossTotalAmount,
        'taxSummary': taxSummary,
        'finalisedAt': finalisedAt?.toIso8601String(),
        'notes': notes,
      };
}

/// A finalised invoice line (`SalesInvoiceLineDto`) — for the printed receipt body.
class InvoiceLine {
  InvoiceLine({
    required this.lineNo,
    required this.productCode,
    required this.productName,
    required this.unitName,
    required this.quantity,
    required this.unitPriceAmount,
    required this.lineDiscountAmount,
    required this.netAmount,
    required this.vatAmount,
    required this.grossAmount,
    required this.vatRate,
  });

  final int lineNo;
  final String productCode;
  final String productName;
  final String? unitName;
  final double quantity;
  final double unitPriceAmount;
  final double lineDiscountAmount;
  final double netAmount;
  final double vatAmount;
  final double grossAmount;
  final double vatRate;

  factory InvoiceLine.fromJson(Map<String, dynamic> j) => InvoiceLine(
        lineNo: asIntOr(j['lineNo']),
        productCode: asStrOr(j['productCode']),
        productName: asStrOr(j['productName']),
        unitName: asStr(j['unitName']),
        quantity: asNumOr(j['quantity']),
        unitPriceAmount: asNumOr(j['unitPriceAmount']),
        lineDiscountAmount: asNumOr(j['lineDiscountAmount']),
        netAmount: asNumOr(j['netAmount']),
        vatAmount: asNumOr(j['vatAmount']),
        grossAmount: asNumOr(j['grossAmount']),
        vatRate: asNumOr(j['vatRate']),
      );

  Map<String, dynamic> toJson() => {
        'lineNo': lineNo,
        'productCode': productCode,
        'productName': productName,
        'unitName': unitName,
        'quantity': quantity,
        'unitPriceAmount': unitPriceAmount,
        'lineDiscountAmount': lineDiscountAmount,
        'netAmount': netAmount,
        'vatAmount': vatAmount,
        'grossAmount': grossAmount,
        'vatRate': vatRate,
      };
}

/// A finalised invoice payment (`SalesInvoicePaymentDto`) — receipt tender lines.
class InvoicePayment {
  InvoicePayment({
    required this.tenderType,
    required this.amount,
    required this.changeAmount,
    required this.reference,
  });

  final TenderType tenderType;
  final double amount;
  final double changeAmount;
  final String? reference;

  factory InvoicePayment.fromJson(Map<String, dynamic> j) => InvoicePayment(
        tenderType: TenderType.fromWire(asStr(j['tenderType'])),
        amount: asNumOr(j['amount']),
        changeAmount: asNumOr(j['changeAmount']),
        reference: asStr(j['reference']),
      );

  Map<String, dynamic> toJson() => {
        'tenderType': tenderType.wire,
        'amount': amount,
        'changeAmount': changeAmount,
        'reference': reference,
      };
}

/// A fully-loaded receipt: header + lines + payments, persisted in the local
/// receipt journal so it can be reprinted without re-posting (G-8).
class Receipt {
  Receipt({
    required this.invoice,
    required this.lines,
    required this.payments,
    required this.clientTxnId,
    required this.tenderedAmount,
  });

  final SalesInvoice invoice;
  final List<InvoiceLine> lines;
  final List<InvoicePayment> payments;

  /// The durable client transaction id used as `X-Request-Id` across attempts.
  final String clientTxnId;

  /// Cash presented (receipt aid; not stored on the invoice).
  final double? tenderedAmount;

  double get changeDue {
    final paid = payments.fold<double>(0, (s, p) => s + p.amount);
    final change = payments.fold<double>(0, (s, p) => s + p.changeAmount);
    if (change > 0) return change;
    if (tenderedAmount != null) {
      return (tenderedAmount! - invoice.grossTotalAmount).clamp(0, double.infinity);
    }
    return (paid - invoice.grossTotalAmount).clamp(0, double.infinity);
  }

  Map<String, dynamic> toJson() => {
        'invoice': invoice.toJson(),
        'lines': lines.map((l) => l.toJson()).toList(),
        'payments': payments.map((p) => p.toJson()).toList(),
        'clientTxnId': clientTxnId,
        'tenderedAmount': tenderedAmount,
      };

  factory Receipt.fromJson(Map<String, dynamic> j) => Receipt(
        invoice: SalesInvoice.fromJson(asMap(j['invoice'])),
        lines: asList(j['lines'], InvoiceLine.fromJson),
        payments: asList(j['payments'], InvoicePayment.fromJson),
        clientTxnId: asStrOr(j['clientTxnId']),
        tenderedAmount: asNum(j['tenderedAmount']),
      );
}
