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

  /// Reverses (voids) a whole POS sale while its session is still OPEN
  /// (`POS.SALE.VOID`). Reverses revenue/VAT/cash + stock server-side.
  Future<void> reverse(String invoiceUid, String reason) async {
    await _api.post('/pos/sales/uid/$invoiceUid/reverse',
        body: {'reason': reason});
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
