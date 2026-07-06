import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../services/catalog_service.dart';
import 'providers.dart';

/// Branch on-hand cache for the register (`STOCK.VIEW`).
///
/// Surfaces remaining stock next to a product **before** the cashier rings it —
/// a busy-day-feedback fix: the till used to reveal a shortage only at Pay. It
/// is best-effort and non-blocking: any error (offline, or a cashier without
/// `STOCK.VIEW`) simply leaves the quantity unknown and the register shows no
/// stock hint. Quantities are a live-enough preview — refreshed each time the
/// cashier searches — not an authoritative reservation (the ERP still enforces
/// stock at sale time).
class StockCache {
  StockCache(this._svc);
  final CatalogService _svc;

  final Map<String, double> _byProduct = {};

  /// Remaining on-hand for [productId] at the active branch, or `null` when
  /// unknown (not fetched yet, or `STOCK.VIEW` denied).
  double? onHand(String productId) => _byProduct[productId];

  /// Refreshes on-hand for products matching [q] at the active branch and merges
  /// the result. Called as the cashier searches so the visible rows stay fresh.
  /// Errors are swallowed — the row just shows no hint.
  Future<void> refreshFor(String q) async {
    if (q.trim().isEmpty) return;
    try {
      _byProduct.addAll(await _svc.onHandByProduct(q: q, size: 200));
    } catch (_) {
      /* unknown → no hint */
    }
  }
}

final stockCacheProvider =
    Provider<StockCache>((ref) => StockCache(ref.read(catalogServiceProvider)));
