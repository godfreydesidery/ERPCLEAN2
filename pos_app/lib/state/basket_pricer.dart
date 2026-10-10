import 'package:collection/collection.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../models/catalog.dart';

import '../services/catalog_service.dart';
import 'app_controller.dart';
import 'cart_controller.dart';
import 'providers.dart';

/// Prices basket lines with the SERVER's resolver, for the basket's customer.
///
/// The server prices a POS sale for the sale's customer (their contract price,
/// else their default price list, else the company default list — PRD-01). The
/// till used to preview the first price row it found, i.e. a walk-in price, so
/// an account customer whose price is higher hit "tenders don't cover the
/// total" at payment. Every line is now priced by `POST /product-prices/resolve`
/// with the basket's `customerUid` + `currency`, and the whole basket is
/// re-priced whenever the customer changes.
class BasketPricer {
  BasketPricer(this._ref);
  final Ref _ref;

  CatalogService get _svc => _ref.read(catalogServiceProvider);

  /// Prices [lineIds] (default: every active line). One request per distinct
  /// unit, so a basket of base-unit lines costs one round trip.
  ///
  /// Returns false when the server could not be asked. The affected lines then
  /// show no price ("—"), which tells the payment step not to trust the
  /// on-screen total — a stale price for another customer is worse than none.
  Future<bool> price({Iterable<String>? lineIds}) async {
    final cart = _ref.read(cartProvider);
    final wanted = lineIds?.toSet();
    final lines = cart.activeLines
        .where((l) => wanted == null || wanted.contains(l.localId))
        .toList();
    if (lines.isEmpty) return true;

    final customerUid = cart.customer?.uid;
    final currency = cart.currency;
    final minor = minorUnitsFor(currency);

    // Group by the unit asked for: null = the product's own base unit.
    final groups = <String?, List<CartLine>>{};
    for (final l in lines) {
      final base = l.product.baseUnitUid;
      final unitKey = (base != null && l.unit.uid == base) ? null : l.unit.uid;
      groups.putIfAbsent(unitKey, () => []).add(l);
    }

    var ok = true;
    for (final entry in groups.entries) {
      final productUids = {for (final l in entry.value) l.product.uid}.toList();
      Map<String, ResolvedUnitPrice>? byUid;
      try {
        final rows = await _svc.resolvePrices(productUids,
            unitUid: entry.key,
            customerUid: (customerUid == null || customerUid.isEmpty)
                ? null
                : customerUid,
            currency: currency);
        byUid = {for (final r in rows) r.productUid: r};
      } catch (_) {
        ok = false;
      }
      _apply(entry.value, byUid, customerUid, minor);
    }
    return ok;
  }

  /// Writes the answers back, skipping any line that changed while the request
  /// was in flight (unit switched, removed, or the customer changed again —
  /// that newer change has its own pricing on the way).
  void _apply(List<CartLine> asked, Map<String, ResolvedUnitPrice>? byUid,
      String? customerUid, int minor) {
    final now = _ref.read(cartProvider);
    if (now.customer?.uid != customerUid) return;
    final app = _ref.read(appControllerProvider);
    final notifier = _ref.read(cartProvider.notifier);
    for (final l in asked) {
      final cur = now.lines.where((x) => x.localId == l.localId).firstOrNull;
      if (cur == null || cur.voided || cur.unit.id != l.unit.id) continue;
      final row = byUid?[l.product.uid];
      final resolved = row != null && row.isResolved;
      notifier.setLinePricing(
        l.localId,
        amount: resolved ? row.amount : null,
        vatInclusive: resolved ? row.vatInclusive : true,
        vatFraction: app.vatFractionFor(l.product.vatStatus),
        minorUnits: minor,
      );
    }
  }
}

final basketPricerProvider =
    Provider<BasketPricer>((ref) => BasketPricer(ref));
