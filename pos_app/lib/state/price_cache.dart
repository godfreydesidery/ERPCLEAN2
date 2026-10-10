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
  PriceCache(this._svc, {DateTime Function()? clock})
      : _now = clock ?? DateTime.now;
  final CatalogService _svc;
  final DateTime Function() _now;

  /// How long an answer is trusted (POS-18). The cache used to live for the
  /// whole app session, so a price changed in the office never reached the
  /// dropdown — while the sale itself charged the new one.
  static const Duration ttl = Duration(minutes: 5);

  final Map<String, DateTime> _fetchedAt = {};

  /// Whose prices are cached: `customerUid|currency` (PRD-01). A different
  /// customer pays different prices, so a change of audience starts afresh.
  String _audience = '';

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

  /// Forget every answer — called after each completed sale so the next
  /// basket starts from the office's current prices (POS-18).
  void clear() {
    _byProductUid.clear();
    _fetchedAt.clear();
  }

  bool _fresh(String uid) {
    final at = _fetchedAt[uid];
    return at != null && _now().difference(at) < ttl;
  }

  /// Resolves prices for [productUids] in the products' own base units, in as
  /// few requests as the batch cap allows. Already-known uids are skipped, so
  /// paging through a long result list re-prices only what is new.
  ///
  /// [customerUid] / [currency] price for the basket's customer (PRD-01) —
  /// what the dropdown shows is what that customer will be charged.
  Future<void> refreshFor(Iterable<String> productUids,
      {String? customerUid, String? currency}) async {
    if (_denied) return;
    final audience = '${customerUid ?? ''}|${currency ?? ''}';
    if (audience != _audience) {
      clear();
      _audience = audience;
    }
    final wanted = <String>[];
    final seen = <String>{};
    for (final uid in productUids) {
      final u = uid.trim();
      if (u.isEmpty || _fresh(u) || !seen.add(u)) continue;
      wanted.add(u);
    }
    if (wanted.isEmpty) return;

    for (var i = 0; i < wanted.length; i += _maxBatch) {
      final chunk =
          wanted.sublist(i, (i + _maxBatch).clamp(0, wanted.length));
      try {
        final rows = await _svc.resolvePrices(chunk,
            customerUid: customerUid, currency: currency);
        // The customer changed while this was in flight — these answers are
        // for someone else, and the new customer's search will ask again.
        if (audience != _audience) return;
        final at = _now();
        for (final row in rows) {
          _byProductUid[row.productUid] = row;
          _fetchedAt[row.productUid] = at;
        }
        // A uid the server did not answer for does not exist in this company.
        // The contract is explicit that this is not an error and means "no
        // price" — record that verdict so the row stops looking like it is
        // still loading forever.
        for (final uid in chunk) {
          if (rows.any((r) => r.productUid == uid)) continue;
          _byProductUid[uid] = ResolvedUnitPrice(
              productUid: uid, unitUid: '', status: UnitPriceStatus.noPrice);
          _fetchedAt[uid] = at;
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
