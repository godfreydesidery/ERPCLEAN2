import '../core/api/api_client.dart';
import '../core/api/api_exception.dart';
import '../models/catalog.dart';
import '../services/catalog_service.dart';
import 'offline_catalog.dart';

/// The catalogue reads the registers make, answered from the offline cache
/// while the till is offline and from the server otherwise (ADR-0068 D-2).
///
/// Sitting at the service seam keeps the registers unchanged: the search box,
/// the scanner, the category buttons, the unit picker and the basket pricer all
/// call the same methods they always did. Online nothing changes, except that a
/// barcode resolved by the server is remembered for offline use.
///
/// Offline, prices are the server's cached answers for the walk-in customer —
/// never a figure the till worked out. A product without a cached price reads
/// NO_PRICE, exactly as the server would say it, and cannot be sold offline.
class OfflineAwareCatalogService extends CatalogService {
  OfflineAwareCatalogService(
    super.api, {
    required OfflineCatalog cache,
    required bool Function() isOffline,
  })  : _cache = cache,
        _offline = isOffline;

  final OfflineCatalog _cache;
  final bool Function() _offline;

  static ApiException _notFound() =>
      ApiException(statusCode: 404, message: 'Not found on this till while offline.');

  @override
  Future<List<Product>> searchProducts(String companyId,
      {String? q, String? category, int page = 0, int size = 50}) async {
    if (_offline()) return _cache.search(q: q, category: category, page: page, size: size);
    return super.searchProducts(companyId, q: q, category: category, page: page, size: size);
  }

  @override
  Future<List<String>> listCategories(String companyId) async {
    if (_offline()) return _cache.snapshot.categories;
    return super.listCategories(companyId);
  }

  @override
  Future<Product> getProduct(String uid) async {
    if (_offline()) return _cache.product(uid) ?? (throw _notFound());
    return super.getProduct(uid);
  }

  @override
  Future<ProductBarcode> lookupBarcode(String companyId, String barcode) async {
    if (_offline()) {
      final hit = _cache.byBarcode(barcode) ?? (throw _notFound());
      return ProductBarcode(
        uid: '',
        productId: hit.product.id,
        productUid: hit.product.uid,
        barcode: barcode,
        barcodeType: '',
        uomId: hit.unitId,
        primary: true,
        derivedQuantity: null,
        derivedAmount: null,
        valueKind: null,
      );
    }
    final bc = await super.lookupBarcode(companyId, barcode);
    // Embedded weight/price labels decode per scan; only plain codes are kept.
    if (!bc.isEmbedded) _cache.learnBarcode(barcode, bc.productUid, bc.uomId);
    return bc;
  }

  @override
  Future<List<ResolvedUnitPrice>> resolvePrices(List<String> productUids,
      {String? unitUid, String? customerUid, String? currency}) async {
    if (_offline()) {
      // Only the walk-in's prices are kept: an offline sale has no other customer.
      final walkIn = _cache.snapshot.walkIn?.uid;
      if (customerUid != null && customerUid.isNotEmpty && customerUid != walkIn) {
        return const [];
      }
      return _cache.resolve(productUids, unitUid: unitUid);
    }
    return super.resolvePrices(productUids,
        unitUid: unitUid, customerUid: customerUid, currency: currency);
  }

  @override
  Future<List<ProductPack>> listPacks(String productUid) async {
    if (_offline()) return _cache.packsOf(productUid);
    return super.listPacks(productUid);
  }

  @override
  Future<Map<String, StockLevel>> stockByProduct(
      {String? q, int page = 0, int size = 200}) async {
    // Offline sales never refuse for stock (D-5): no hint rather than a timeout.
    if (_offline()) return const {};
    return super.stockByProduct(q: q, page: page, size: size);
  }

  /// The plain, always-online service — for the refresher, which must read the
  /// server and never its own cache.
  static CatalogService online(ApiClient api) => CatalogService(api);
}
