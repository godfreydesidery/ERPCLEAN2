/// The rules of an offline sale (ERP ADR-0068 D-3), as pure functions so they
/// can be tested without a screen: what a basket may hold, which payments it
/// may take, what is recorded, and the receipt it prints.
library;

import 'dart:math' as math;

import '../core/money.dart';
import '../models/enums.dart';
import '../models/sale.dart';
import '../state/cart_controller.dart';
import 'offline_models.dart';

const double _eps = 0.0001;

/// Why [cart] cannot be sold offline, or null when it can.
///
/// Offline there is nobody to approve anything, so everything that needs a
/// manager or the server is out: a named or credit customer, a discount, and a
/// line the cache holds no server price for (the till never invents one).
String? offlineBasketProblem(CartState cart) {
  if (cart.isEmpty) return 'The basket is empty.';
  final customer = cart.customer;
  if (customer == null || !customer.isWalkIn) {
    return 'Offline sales are for the walk-in customer only. '
        'Switch the customer back to walk-in.';
  }
  for (final l in cart.activeLines) {
    if (l.lineDiscountAmount > 0) {
      return 'Discounts cannot be given offline. Remove the discount on ${l.product.name}.';
    }
    if (l.unitPricePreview == null) {
      return '${l.product.name} has no saved price on this till, so it cannot be sold '
          'offline. Remove it.';
    }
  }
  return null;
}

/// What the cashier is told when a payment type needs its reference.
String offlineReferenceLabel(TenderType t) => switch (t) {
      TenderType.mobileMoney => 'mobile money transaction reference',
      TenderType.card => 'card approval number',
      _ => 'reference',
    };

/// Why [tenders] cannot settle [due] offline, or null when they can.
///
/// Cash, mobile money and card only; mobile money and card each need their
/// reference (the server holds a sale without one); only cash gives change, so
/// the other types together may not exceed what is due.
String? offlineTenderProblem(double due, List<OfflineTender> tenders) {
  if (tenders.isEmpty) return 'Add a payment.';
  for (final t in tenders) {
    if (!kOfflineTenders.contains(t.type)) {
      return 'Offline sales take cash, mobile money or card only.';
    }
    if (t.amount <= 0) return 'Each payment needs an amount.';
    if (offlineTenderNeedsReference(t.type) && (t.reference?.trim().isEmpty ?? true)) {
      return 'Enter the ${offlineReferenceLabel(t.type)}.';
    }
    if ((t.reference?.trim().length ?? 0) > 80) {
      return 'That reference is too long (80 characters at most).';
    }
  }
  final total = tenders.fold(0.0, (s, t) => s + t.amount);
  final nonCash = tenders
      .where((t) => t.type != TenderType.cash)
      .fold(0.0, (s, t) => s + t.amount);
  if (nonCash > due + _eps) {
    return 'Only cash gives change. Mobile money and card together cannot be more '
        'than ${formatAmount(due)}.';
  }
  if (total + _eps < due) return 'Still to pay: ${formatAmount(due - total)}.';
  return null;
}

/// Change owed to the customer: whatever was handed over beyond [due].
double offlineChange(double due, List<OfflineTender> tenders) =>
    math.max(0, tenders.fold(0.0, (s, t) => s + t.amount) - due);

/// The payments the sale RECORDS: cash is reduced by the change given, so the
/// payments add up to the sale and the server books no over-payment.
List<OfflineTender> recordedTenders(double due, List<OfflineTender> tendered) {
  var change = offlineChange(due, tendered);
  final out = <OfflineTender>[];
  for (final t in tendered.reversed) {
    if (t.type == TenderType.cash && change > _eps) {
      final take = math.min(change, t.amount);
      change -= take;
      final left = t.amount - take;
      if (left > _eps) out.add(OfflineTender(type: t.type, amount: left, reference: t.reference));
      continue;
    }
    out.add(t);
  }
  return out.reversed.toList();
}

/// The basket's lines at the prices it shows — each one the server's cached
/// answer, with that price list's VAT stance.
List<OfflineLine> offlineLinesFrom(CartState cart) => [
      for (final l in cart.activeLines)
        OfflineLine(
          productId: l.product.id,
          productUid: l.product.uid,
          productCode: l.product.code,
          productName: l.product.name,
          unitId: l.unit.id,
          unitName: l.unit.name,
          quantity: l.quantity,
          unitPrice: l.unitNetPrice ?? l.unitPricePreview!,
          priceInclusive: l.unitNetPrice == null,
          vatFraction: l.vatFraction,
          vatStatus: l.product.vatStatus,
        ),
    ];

/// The total the customer is shown for [lines], rounded per line.
double offlineGross(List<OfflineLine> lines, int minorUnits) =>
    lines.fold(0.0, (s, l) => s + l.amounts(minorUnits).gross);

/// The printed receipt of an offline sale. It is marked OFFLINE and numbered
/// with the offline number until the sale has synced (then the journal copy
/// gets the real invoice number, and still prints the OFFLINE mark).
Receipt offlineReceipt(
  OfflineSale sale, {
  required List<OfflineTender> tendered,
  required int minorUnits,
}) {
  var net = 0.0, vat = 0.0, gross = 0.0;
  final lines = <InvoiceLine>[];
  for (var i = 0; i < sale.lines.length; i++) {
    final l = sale.lines[i];
    final a = l.amounts(minorUnits);
    net += a.net;
    vat += a.vat;
    gross += a.gross;
    lines.add(InvoiceLine(
      lineNo: i + 1,
      productCode: l.productCode,
      productName: l.productName,
      unitName: l.unitName,
      quantity: l.quantity,
      unitPriceAmount: l.priceInclusive
          ? l.unitPrice
          : roundMinor(l.unitPrice * (1 + l.vatFraction), minorUnits),
      lineDiscountAmount: 0,
      netAmount: a.net,
      vatAmount: a.vat,
      grossAmount: a.gross,
      vatRate: l.vatFraction,
      vatStatus: VatStatus.fromWire(l.vatStatus),
    ));
  }
  final change = offlineChange(sale.grossTotal, tendered);
  var changePlaced = false;
  final payments = <InvoicePayment>[];
  for (final t in tendered) {
    final carriesChange = !changePlaced && t.type == TenderType.cash && change > _eps;
    if (carriesChange) changePlaced = true;
    payments.add(InvoicePayment(
      tenderType: t.type,
      amount: t.amount,
      changeAmount: carriesChange ? change : 0,
      reference: t.reference,
    ));
  }
  return Receipt(
    invoice: SalesInvoice(
      id: '',
      uid: 'offline:${sale.idempotencyKey}',
      invoiceNumber: sale.offlineNumber,
      status: InvoiceStatus.finalised,
      customerId: sale.customerId,
      customerName: sale.customerName,
      agentName: null,
      currency: sale.currency,
      netTotalAmount: roundMinor(net, minorUnits),
      vatTotalAmount: roundMinor(vat, minorUnits),
      grossTotalAmount: roundMinor(gross, minorUnits),
      taxSummary: null,
      finalisedAt: sale.soldAt,
      notes: sale.notes,
      createdByName: sale.cashierName,
      soldOffline: true,
      offlineNumber: sale.offlineNumber,
    ),
    lines: lines,
    payments: payments,
    clientTxnId: sale.idempotencyKey,
    tenderedAmount: sale.cashTendered,
  );
}
