import '../core/api/api_client.dart';
import '../core/json.dart';
import '../models/catalog.dart';

/// Catalogue + pricing reads (§03/§04). List endpoints return a bare array in
/// `data` (pagination lives in the envelope `meta`), so the caller pages by
/// length: a full page implies there may be more.
class CatalogService {
  CatalogService(this._api);
  final ApiClient _api;

  Future<List<Product>> searchProducts(
    String companyId, {
    String? q,
    int page = 0,
    int size = 50,
  }) async {
    final data = await _api.get('/products', query: {
      'companyId': companyId,
      'q': q,
      'page': page,
      'size': size,
    });
    return asList(data, Product.fromJson);
  }

  Future<Product> getProduct(String uid) async =>
      Product.fromJson(asMap(await _api.get('/products/uid/$uid')));

  /// Resolves a scanned barcode (exact-match or embedded weight/price) within
  /// the company. Throws [ApiException] 404 when unknown.
  Future<ProductBarcode> lookupBarcode(String companyId, String barcode) async {
    final data = await _api.get('/products/barcode-lookup',
        query: {'companyId': companyId, 'barcode': barcode});
    return ProductBarcode.fromJson(asMap(data));
  }

  /// Stock per product at the caller's **active branch**, summed across that
  /// branch's stock locations and keyed by product id. Optional [q] filters by
  /// product code/name (the same filter the catalogue search uses), so a
  /// register can refresh just the products it is showing. Requires
  /// `STOCK.VIEW`; returns an empty map on any error (including a 403 for a
  /// cashier without the permission) so the till simply shows no stock hint.
  ///
  /// Summing every location in the branch mirrors what the sale-time guard
  /// does (`StockReservationServiceImpl.getAvailability`): stock is fungible
  /// within a branch, so goods sitting in a receiving bay are still sellable.
  ///
  /// **Known gap:** that guard enforces `quantity − reserved`, and
  /// `StockOnHandDto` exposes no reservation figure, so `availableQty` comes
  /// back null today and [StockLevel.sellable] degrades to the physical count.
  /// The parse is already in place, so the till becomes exact the moment the
  /// DTO carries the field — no client change needed.
  Future<Map<String, StockLevel>> stockByProduct(
      {String? q, int page = 0, int size = 200}) async {
    final data = await _api.get('/stock/on-hand', query: {
      'q': q,
      'page': page,
      'size': size,
    });
    final out = <String, StockLevel>{};
    if (data is List) {
      for (final row in data) {
        if (row is Map) {
          final pid = asStr(row['productId']);
          if (pid == null) continue;
          final level = StockLevel.fromJson(row.cast<String, dynamic>());
          final prior = out[pid];
          out[pid] = prior == null ? level : prior.plus(level);
        }
      }
    }
    return out;
  }

  /// Batch price read (P2) — "what will the server charge for these products?".
  ///
  /// ONE request per result page, not one per row. The server runs the real
  /// resolver, so the figure shown is the figure the invoice will carry;
  /// rows come back **in requested order** but must be keyed by `productUid`,
  /// because a uid that does not exist (or belongs to another company) is
  /// omitted entirely rather than reported as an error.
  ///
  /// [unitUid] null prices each product in its OWN base unit — the normal POS
  /// search case, where every row has a different base unit.
  Future<List<ResolvedUnitPrice>> resolvePrices(List<String> productUids,
      {String? unitUid}) async {
    if (productUids.isEmpty) return const [];
    final data = await _api.post('/product-prices/resolve', body: {
      'productUids': productUids,
      'unitUid': ?unitUid,
    });
    return asList(data, ResolvedUnitPrice.fromJson);
  }

  Future<List<Unit>> listUnits(String companyId, {int size = 200}) async {
    final data = await _api
        .get('/units', query: {'companyId': companyId, 'size': size});
    return asList(data, Unit.fromJson);
  }

  /// Price-list rows for a product — used for the **preview** unit price only.
  Future<List<ProductPrice>> listPrices(String productUid) async =>
      asList(await _api.get('/products/uid/$productUid/prices'),
          ProductPrice.fromJson);

  /// Configured bulk packs for a product (carton, box, …) with their
  /// conversion factors. Requires `PRODUCT.VIEW` — the same permission the
  /// catalogue search already needs, so a cashier who can ring can read these.
  Future<List<ProductPack>> listPacks(String productUid) async =>
      asList(await _api.get('/products/uid/$productUid/bulk-packs'),
          ProductPack.fromJson);

  /// VAT rates by `vatStatus`, as a **fraction** (e.g. 0.18 = 18%). Lets the
  /// preview show VAT-inclusive totals that match the server's gross. Needs
  /// `TAXRATE.VIEW`; the caller treats an empty map (or a thrown 403) as "show
  /// net prices".
  Future<Map<String, double>> taxRatesByStatus(String companyId) async {
    final data = await _api.get('/tax-rates', query: {'companyId': companyId});
    final out = <String, double>{};
    if (data is List) {
      for (final m in data) {
        if (m is Map) {
          final status = m['vatStatus']?.toString();
          final rate = asNum(m['rate']);
          // The backend stores TaxRate.rate as a FRACTION with a DB CHECK
          // (0 ≤ rate < 1) — e.g. 0.1800 for 18% — so it is used verbatim as a
          // fraction. No percentage-vs-fraction guessing: a value outside
          // [0, 1) is an invalid row and is skipped (net preview for that status).
          if (status != null && rate != null && rate >= 0 && rate < 1) {
            out[status] = rate;
          }
        }
      }
    }
    return out;
  }
}
