import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../core/api/api_exception.dart';
import '../models/catalog.dart';
import '../services/catalog_service.dart';
import 'providers.dart';

/// Branch stock cache for the register (`STOCK.VIEW`).
///
/// Surfaces what the branch holds **before** the cashier rings — the till used
/// to reveal a shortage only at Pay. Best-effort and non-blocking: any failure
/// (offline, or a cashier without `STOCK.VIEW`) leaves the quantity unknown and
/// the row shows no hint. The ERP still enforces stock at sale time; this is a
/// live-enough preview, not a reservation.
///
/// ## "No stock row" is an answer, not silence
///
/// `/stock/on-hand` only returns products that HAVE an on-hand row, so a
/// product the branch has never stocked is simply absent from the response. The
/// old cache could not tell that apart from "not fetched yet" and rendered
/// nothing in both cases — so a genuinely out-of-stock item looked exactly like
/// a still-loading one, and the cashier read the blank as "fine". This cache
/// therefore remembers which queries have **successfully completed**: absent
/// from a completed query means zero, and is rendered as "Out of stock".
class StockCache {
  StockCache(this._svc);
  final CatalogService _svc;

  final Map<String, StockLevel> _byProduct = {};

  /// Queries whose refresh completed. Only these turn "absent" into "zero".
  final Set<String> _completedQueries = {};

  /// Set once the server has said no. Without it a cashier lacking `STOCK.VIEW`
  /// would fire a doomed request on every keystroke.
  bool _denied = false;

  /// True when this till cannot read stock at all (no `STOCK.VIEW`). Callers use
  /// it to stay silent rather than claim anything about availability.
  bool get denied => _denied;

  /// What the branch holds of [productId], within the result set of [query].
  ///
  /// Returns null while the answer is genuinely unknown (not fetched yet, or
  /// stock reads are denied). Returns a zero [StockLevel] — i.e. out of stock —
  /// when [query] has completed and the product had no on-hand row.
  StockLevel? levelFor(String productId, {required String query}) {
    final known = _byProduct[productId];
    if (known != null) return known;
    if (_denied) return null;
    return _completedQueries.contains(query.trim())
        ? const StockLevel(quantity: 0)
        : null;
  }

  /// What the branch holds of [productId] regardless of any query — used for
  /// cart lines, which were added from a search that has since been cleared.
  /// Null when nothing is known about the product.
  StockLevel? levelForProduct(String productId) => _byProduct[productId];

  /// Refreshes stock for products matching [q] and merges the result. Called as
  /// the cashier searches so visible rows stay fresh. Errors are swallowed —
  /// the row just shows no hint.
  Future<void> refreshFor(String q) async {
    final query = q.trim();
    if (query.isEmpty || _denied) return;
    try {
      _byProduct.addAll(await _svc.stockByProduct(q: query, size: 200));
      _completedQueries.add(query);
    } on ApiException catch (e) {
      // A 403 is permanent for this session; anything else may be transient, so
      // only the permission failure latches.
      if (e.isForbidden) _denied = true;
    } catch (_) {
      /* unknown → no hint */
    }
  }
}

final stockCacheProvider =
    Provider<StockCache>((ref) => StockCache(ref.read(catalogServiceProvider)));
