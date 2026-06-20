import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../models/catalog.dart';
import '../services/catalog_service.dart';
import 'providers.dart';

/// Local catalogue cache (SC-CACHE, G-2). Two jobs:
///  1. Resolve a scanned `productId` (barcode-lookup returns the numeric id, and
///     there is no product-by-id endpoint) to a full [Product].
///  2. Serve instant local search so ringing stays responsive.
/// Selling **prices** are fetched lazily per product (preview only — the ERP is
/// authoritative at sale time) and memoised.
class CatalogCache {
  CatalogCache(this._svc);
  final CatalogService _svc;

  final List<Product> _products = [];
  final Map<String, Product> _byId = {};
  final Map<String, double?> _priceByUid = {};
  bool _loaded = false;

  bool get loaded => _loaded;
  int get count => _products.length;

  /// Pages the whole sellable catalogue into memory once (capped). Safe to call
  /// repeatedly — only the first call hits the network.
  Future<void> ensureLoaded(String companyId) async {
    if (_loaded) return;
    const size = 200;
    const cap = 6000;
    var page = 0;
    while (_products.length < cap) {
      final batch =
          await _svc.searchProducts(companyId, page: page, size: size);
      for (final p in batch) {
        _byId[p.id] = p;
        if (p.sellable && p.isActive) _products.add(p);
      }
      if (batch.length < size) break;
      page++;
    }
    _loaded = true;
  }

  Product? byId(String id) => _byId[id];

  /// Instant local prefix/substring search over code + name.
  List<Product> search(String q, {int limit = 40}) {
    final s = q.trim().toLowerCase();
    if (s.isEmpty) return _products.take(limit).toList();
    return _products
        .where((p) =>
            p.code.toLowerCase().contains(s) ||
            p.name.toLowerCase().contains(s))
        .take(limit)
        .toList();
  }

  /// Exact code match (a scanner that types a plain product code).
  Product? byExactCode(String code) {
    final c = code.trim().toLowerCase();
    for (final p in _products) {
      if (p.code.toLowerCase() == c) return p;
    }
    return null;
  }

  /// Memoised preview unit price for a product in [currency]; null if no price
  /// list row (the line still posts — the server prices it).
  Future<double?> previewPrice(String productUid, String currency) async {
    if (_priceByUid.containsKey(productUid)) return _priceByUid[productUid];
    try {
      final prices = await _svc.listPrices(productUid);
      double? value;
      for (final p in prices) {
        if (p.price.currency == currency) {
          value = p.price.amount;
          break;
        }
      }
      value ??= prices.isNotEmpty ? prices.first.price.amount : null;
      _priceByUid[productUid] = value;
      return value;
    } catch (_) {
      _priceByUid[productUid] = null;
      return null;
    }
  }
}

final catalogCacheProvider =
    Provider<CatalogCache>((ref) => CatalogCache(ref.read(catalogServiceProvider)));
