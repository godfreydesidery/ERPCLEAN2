import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../core/api/api_exception.dart';
import '../models/catalog.dart';
import '../models/enums.dart';
import '../services/catalog_service.dart';
import 'providers.dart';

/// Selling prices for the rows currently on screen (K6), resolved by the SERVER.
///
/// One request per result page — `POST /product-prices/resolve` takes up to 200
/// product uids at a time — not one request per row. A 60-row search that
/// priced itself row-by-row would put 60 round trips behind every keystroke.
///
/// The figures come from the ERP's own price resolver, so what the cashier reads
/// in the dropdown is what the invoice will charge. Where the server says there
/// is no price, this cache says so too: it never falls back to a locally
/// computed guess, because those guesses disagreeing with the posted invoice is
/// the exact defect the batch endpoint was built to end.
class PriceCache {
  PriceCache(this._svc);
  final CatalogService _svc;

  /// Server cap on one batch (`ResolveUnitPricesRequest`).
  static const int _maxBatch = 200;

  final Map<String, ResolvedUnitPrice> _byProductUid = {};

  bool _denied = false;

  /// True when this till may not read prices at all. Callers then show nothing
  /// rather than implying an item is unpriced.
  bool get denied => _denied;

  /// The server's answer for [productUid], or null while it is still unknown.
  ///
  /// A returned row with `status != RESOLVED` is a definite "no price" — render
  /// it as such.
  ResolvedUnitPrice? priceFor(String productUid) => _byProductUid[productUid];

  /// Resolves prices for [productUids] in the products' own base units, in as
  /// few requests as the batch cap allows. Already-known uids are skipped, so
  /// paging through a long result list re-prices only what is new.
  Future<void> refreshFor(Iterable<String> productUids) async {
    if (_denied) return;
    final wanted = <String>[];
    final seen = <String>{};
    for (final uid in productUids) {
      final u = uid.trim();
      if (u.isEmpty || _byProductUid.containsKey(u) || !seen.add(u)) continue;
      wanted.add(u);
    }
    if (wanted.isEmpty) return;

    for (var i = 0; i < wanted.length; i += _maxBatch) {
      final chunk =
          wanted.sublist(i, (i + _maxBatch).clamp(0, wanted.length));
      try {
        final rows = await _svc.resolvePrices(chunk);
        for (final row in rows) {
          _byProductUid[row.productUid] = row;
        }
        // A uid the server did not answer for does not exist in this company.
        // The contract is explicit that this is not an error and means "no
        // price" — record that verdict so the row stops looking like it is
        // still loading forever.
        for (final uid in chunk) {
          _byProductUid.putIfAbsent(
              uid,
              () => ResolvedUnitPrice(
                  productUid: uid,
                  unitUid: '',
                  status: UnitPriceStatus.noPrice));
        }
      } on ApiException catch (e) {
        if (e.isForbidden) _denied = true;
        return;
      } catch (_) {
        return; // transient — leave the rows unknown and try again next search
      }
    }
  }
}

final priceCacheProvider =
    Provider<PriceCache>((ref) => PriceCache(ref.read(catalogServiceProvider)));
