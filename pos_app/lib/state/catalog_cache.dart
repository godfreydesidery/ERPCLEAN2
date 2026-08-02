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

  /// Every unit [p] may be sold in: its base unit first (factor 1), then each
  /// configured pack whose unit is in [unitsByUid]. Fetched fresh like prices.
  ///
  /// A pack fetch failure degrades to base-unit-only rather than throwing — a
  /// missing carton option must never stop the cashier ringing the item.
  Future<List<SaleUnit>> sellableUnits(
      Product p, Map<String, Unit> unitsByUid) async {
    final base = unitsByUid[p.baseUnitUid];
    final out = <SaleUnit>[if (base != null) SaleUnit(base, 1)];
    try {
      for (final pack in await _svc.listPacks(p.uid)) {
        final unit = unitsByUid[pack.unitUid];
        // Skip a pack whose unit this company no longer has, a non-positive
        // factor (defensive — the DB forbids it), and a pack defined ON the base
        // unit, which would duplicate the entry already added above.
        if (unit == null || pack.factorToBase <= 0) continue;
        if (base != null && unit.id == base.id) continue;
        if (out.any((s) => s.unit.id == unit.id)) continue;
        out.add(SaleUnit(unit, pack.factorToBase));
      }
    } catch (_) {
      // Packs unavailable (offline, 403) — the base unit alone is still sellable.
    }
    return out;
  }

  /// The sellable unit of [p] whose numeric id is [unitId] — used to honour the
  /// `uomId` a scanned pack barcode resolves to. Null when that unit is not a
  /// configured unit for the product, so the caller can fall back to the base.
  Future<SaleUnit?> sellableUnitById(
      Product p, String unitId, Map<String, Unit> unitsByUid) async {
    for (final s in await sellableUnits(p, unitsByUid)) {
      if (s.unit.id == unitId) return s;
    }
    return null;
  }
}

/// A unit a product can be sold in, with the multiplier that converts one of it
/// into the product's base unit. The base unit itself has [factor] 1.
///
/// The factor drives two client-side previews only — the server re-derives both:
/// price (`base price × factor`, mirroring `PriceResolutionServiceImpl`) and the
/// on-hand check (`quantity × factor` base units).
class SaleUnit {
  const SaleUnit(this.unit, this.factor);
  final Unit unit;
  final double factor;

  bool get isBase => factor == 1;

  /// "24" rather than "24.0" for a whole-number factor.
  String get factorLabel =>
      factor == factor.roundToDouble() ? factor.toStringAsFixed(0) : '$factor';

  /// "Carton (×24)" for a pack; the plain unit name for the base unit.
  String get label => isBase ? unit.name : '${unit.name} (×$factorLabel)';
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
