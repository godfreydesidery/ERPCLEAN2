import '../core/api/api_client.dart';
import '../core/json.dart';
import '../models/sale.dart';

/// The POS sale path (§09): ring (with idempotency), whole-sale reverse, and the
/// invoice reads used to assemble/reprint a receipt without re-posting (G-8).
class SaleService {
  SaleService(this._api);
  final ApiClient _api;

  /// Rings a sale. [body] is the assembled `PosSaleRequest`. [idempotencyKey] is
  /// resent verbatim on an ambiguous retry so the server returns the original
  /// invoice instead of double-posting (PRIN-4); [xRequestId] correlates the
  /// attempts in the server log.
  Future<SalesInvoice> ring(
    Map<String, dynamic> body, {
    required String idempotencyKey,
    required String xRequestId,
  }) async {
    final data = await _api.post('/pos/sales',
        body: body, idempotencyKey: idempotencyKey, xRequestId: xRequestId);
    return SalesInvoice.fromJson(asMap(data));
  }

  /// Asks what became of the sale sent under [idempotencyKey] (K11).
  ///
  /// This is a READ. It never posts, never re-runs a business guard and stays
  /// valid after the session closes — so it can give the unfinished-sale prompt
  /// a terminal answer instead of another question.
  Future<PosSaleLookup> lookupByIdempotencyKey(String idempotencyKey) async {
    final data = await _api.get('/pos/sales/idempotency/$idempotencyKey');
    return PosSaleLookup.fromJson(asMap(data));
  }

  /// Reverses (voids) a whole POS sale while its session is still OPEN.
  /// Reverses revenue/VAT/cash + stock server-side.
  ///
  /// [authorisedByUid] is the uid a successful manager step-up returned. It is
  /// not decoration and it is not optional in practice: the server re-resolves
  /// it and refuses the refund unless it names a real, active user — someone
  /// OTHER than the cashier — who genuinely holds `SALES.INVOICE.VOID` in this
  /// invoice's company. Send null only when the signed-in user holds that
  /// authority themselves, in which case there is nobody to escalate to.
  Future<void> reverse(String invoiceUid, String reason,
      {String? authorisedByUid}) async {
    await _api.post('/pos/sales/uid/$invoiceUid/reverse', body: {
      'reason': reason,
      'authorisedByUid': ?authorisedByUid,
    });
  }

  // ----------------------------------------------------------------- receipt reads

  Future<SalesInvoice> getInvoice(String uid) async =>
      SalesInvoice.fromJson(asMap(await _api.get('/sales-invoices/uid/$uid')));

  Future<List<InvoiceLine>> listLines(String uid) async => asList(
      await _api.get('/sales-invoices/uid/$uid/lines'), InvoiceLine.fromJson);

  Future<List<InvoicePayment>> listPayments(String uid) async => asList(
      await _api.get('/sales-invoices/uid/$uid/payments'),
      InvoicePayment.fromJson);

  /// Loads a full receipt (header + lines + payments) from a finalised invoice.
  Future<Receipt> loadReceipt(
    String invoiceUid, {
    required String clientTxnId,
    double? tenderedAmount,
  }) async {
    final invoice = await getInvoice(invoiceUid);
    final lines = await listLines(invoiceUid);
    final payments = await listPayments(invoiceUid);
    return Receipt(
      invoice: invoice,
      lines: lines,
      payments: payments,
      clientTxnId: clientTxnId,
      tenderedAmount: tenderedAmount,
    );
  }

  /// Today's / recent finalised invoices for the company — for the reprint list.
  Future<List<SalesInvoice>> listInvoices(
    String companyId, {
    String? q,
    int page = 0,
    int size = 50,
  }) async {
    final data = await _api.get('/sales-invoices', query: {
      'companyId': companyId,
      'q': q,
      'page': page,
      'size': size,
    });
    return asList(data, SalesInvoice.fromJson);
  }
}
