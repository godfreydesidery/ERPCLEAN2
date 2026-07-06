import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../models/catalog.dart';
import '../services/catalog_service.dart';
import 'providers.dart';

/// Cache-free catalogue access. Deliberately holds **no** in-memory cache: product
/// data and selling prices are ALWAYS fetched fresh from the backend, so a price
/// or catalogue change is reflected on the till immediately (no app restart).
/// The ERP stays authoritative for price/VAT at sale time — these reads are the
/// on-screen preview aid.
class Catalogue {
  Catalogue(this._svc);
  final CatalogService _svc;

  /// Fresh full product by uid — resolves a scanned barcode's product (the
  /// barcode lookup returns the product's uid). Null on any error, so the caller
  /// can fall back to a search.
  Future<Product?> productByUid(String uid) async {
    try {
      return await _svc.getProduct(uid);
    } catch (_) {
      return null;
    }
  }

  /// Fresh preview unit price for a product in [currency]; null if it has no price
  /// row (the line still posts — the server prices it). Carries the source price
  /// list's VAT-inclusive flag so the register knows whether to add VAT. Fetched
  /// fresh on every call — never memoised.
  Future<PreviewPrice?> previewPrice(String productUid, String currency) async {
    try {
      final prices = await _svc.listPrices(productUid);
      ProductPrice? match;
      for (final p in prices) {
        if (p.price.currency == currency) {
          match = p;
          break;
        }
      }
      match ??= prices.isNotEmpty ? prices.first : null;
      return match == null
          ? null
          : PreviewPrice(match.price.amount, match.priceIncludesVat);
    } catch (_) {
      return null;
    }
  }
}

/// A product's preview unit price plus whether that amount already includes VAT
/// (its source price list's stance). [amount] is the NET price when
/// [vatInclusive] is false, and the gross price when true.
class PreviewPrice {
  const PreviewPrice(this.amount, this.vatInclusive);
  final double amount;
  final bool vatInclusive;
}

final catalogProvider =
    Provider<Catalogue>((ref) => Catalogue(ref.read(catalogServiceProvider)));
