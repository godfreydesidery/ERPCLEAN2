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

  Future<List<Unit>> listUnits(String companyId, {int size = 200}) async {
    final data = await _api
        .get('/units', query: {'companyId': companyId, 'size': size});
    return asList(data, Unit.fromJson);
  }

  /// Price-list rows for a product — used for the **preview** unit price only.
  Future<List<ProductPrice>> listPrices(String productUid) async =>
      asList(await _api.get('/products/uid/$productUid/prices'),
          ProductPrice.fromJson);
}
