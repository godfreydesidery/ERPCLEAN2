import 'dart:convert';

import 'package:flutter/foundation.dart';

import '../core/json.dart';
import '../models/catalog.dart';
import '../models/enums.dart';
import '../models/parties.dart';
import '../models/pos.dart';
import '../services/catalog_service.dart';
import 'blob_store.dart';

/// A barcode the till can resolve offline: which product, and in which unit
/// when the label is a pack's (`uomId` of the lookup).
class CachedBarcode {
  const CachedBarcode(this.productUid, this.unitId);
  final String productUid;
  final String? unitId;

  Map<String, dynamic> toJson() => {'p': productUid, 'u': unitId};
  factory CachedBarcode.fromJson(Map<String, dynamic> j) =>
      CachedBarcode(asStrOr(j['p']), asStr(j['u']));
}

/// A configured pack (carton, box …) of a product, for offline unit choice.
class CachedPack {
  const CachedPack(this.unitUid, this.factor);
  final String unitUid;
  final double factor;

  Map<String, dynamic> toJson() => {'u': unitUid, 'f': factor};
  factory CachedPack.fromJson(Map<String, dynamic> j) =>
      CachedPack(asStrOr(j['u']), asNumOr(j['f'], 1));
}

/// A selling price exactly as the SERVER's resolver answered it, with its VAT
/// stance (ADR-0068 D-2). The till never computes a price of its own.
class CachedPrice {
  const CachedPrice(this.amount, this.vatInclusive);
  final double amount;
  final bool vatInclusive;

  Map<String, dynamic> toJson() => {'a': amount, 'i': vatInclusive};
  factory CachedPrice.fromJson(Map<String, dynamic> j) =>
      CachedPrice(asNumOr(j['a']), asBool(j['i']));
}

/// Everything the till needs to sell offline, as of [pricesAsOf].
///
/// Not secret (owner ruling): products and prices stay unencrypted and are
/// refreshed often. Offline sales go to the walk-in customer only, so only the
/// walk-in's prices are kept.
class CatalogSnapshot {
  const CatalogSnapshot({
    this.companyId = '',
    this.branchUid = '',
    this.currency = 'TZS',
    this.products = const {},
    this.barcodes = const {},
    this.packs = const {},
    this.prices = const {},
    this.categories = const [],
    this.vatRates = const {},
    this.units = const {},
    this.walkIn,
    this.till,
    this.pricesAsOf,
    this.sweptAt,
  });

  final String companyId;
  final String branchUid;
  final String currency;

  /// Sellable, active products by uid.
  final Map<String, Product> products;
  final Map<String, CachedBarcode> barcodes;
  final Map<String, List<CachedPack>> packs;

  /// `productUid|unitUid` → price; the unit part is empty for the base unit.
  final Map<String, CachedPrice> prices;
  final List<String> categories;
  final Map<String, double> vatRates;
  final Map<String, Unit> units;
  final Customer? walkIn;

  /// The till this device last ran a shift on (with its `offlineAllowed`).
  final PosTill? till;

  /// When the prices were last read from the server — "prices as of 07:40".
  final DateTime? pricesAsOf;

  /// When barcodes and packs were last swept (one request per product, daily).
  final DateTime? sweptAt;

  bool get isEmpty => products.isEmpty;

  static String priceKey(String productUid, String? unitUid) => '$productUid|${unitUid ?? ''}';

  CatalogSnapshot copyWith({
    String? companyId,
    String? branchUid,
    String? currency,
    Map<String, Product>? products,
    Map<String, CachedBarcode>? barcodes,
    Map<String, List<CachedPack>>? packs,
    Map<String, CachedPrice>? prices,
    List<String>? categories,
    Map<String, double>? vatRates,
    Map<String, Unit>? units,
    Customer? walkIn,
    PosTill? till,
    DateTime? pricesAsOf,
    DateTime? sweptAt,
  }) =>
      CatalogSnapshot(
        companyId: companyId ?? this.companyId,
        branchUid: branchUid ?? this.branchUid,
        currency: currency ?? this.currency,
        products: products ?? this.products,
        barcodes: barcodes ?? this.barcodes,
        packs: packs ?? this.packs,
        prices: prices ?? this.prices,
        categories: categories ?? this.categories,
        vatRates: vatRates ?? this.vatRates,
        units: units ?? this.units,
        walkIn: walkIn ?? this.walkIn,
        till: till ?? this.till,
        pricesAsOf: pricesAsOf ?? this.pricesAsOf,
        sweptAt: sweptAt ?? this.sweptAt,
      );

  Map<String, dynamic> toJson() => {
        'v': 1,
        'companyId': companyId,
        'branchUid': branchUid,
        'currency': currency,
        'products': [for (final p in products.values) p.toJson()],
        'barcodes': {for (final e in barcodes.entries) e.key: e.value.toJson()},
        'packs': {
          for (final e in packs.entries) e.key: [for (final p in e.value) p.toJson()]
        },
        'prices': {for (final e in prices.entries) e.key: e.value.toJson()},
        'categories': categories,
        'vatRates': vatRates,
        'units': [for (final u in units.values) u.toJson()],
        'walkIn': walkIn?.toJson(),
        'till': till?.toJson(),
        'pricesAsOf': pricesAsOf?.toUtc().toIso8601String(),
        'sweptAt': sweptAt?.toUtc().toIso8601String(),
      };

  factory CatalogSnapshot.fromJson(Map<String, dynamic> j) {
    final products = asList(j['products'], Product.fromJson);
    final units = asList(j['units'], Unit.fromJson);
    return CatalogSnapshot(
      companyId: asStrOr(j['companyId']),
      branchUid: asStrOr(j['branchUid']),
      currency: asStrOr(j['currency'], 'TZS'),
      products: {for (final p in products) p.uid: p},
      barcodes: {
        for (final e in asMap(j['barcodes']).entries)
          e.key: CachedBarcode.fromJson(asMap(e.value)),
      },
      packs: {
        for (final e in asMap(j['packs']).entries)
          e.key: asList(e.value, CachedPack.fromJson),
      },
      prices: {
        for (final e in asMap(j['prices']).entries) e.key: CachedPrice.fromJson(asMap(e.value)),
      },
      categories: j['categories'] is List
          ? [for (final c in j['categories'] as List) c.toString()]
          : const [],
      vatRates: {for (final e in asMap(j['vatRates']).entries) e.key: asNumOr(e.value)},
      units: {for (final u in units) u.uid: u},
      walkIn: j['walkIn'] is Map ? Customer.fromJson(asMap(j['walkIn'])) : null,
      till: j['till'] is Map ? PosTill.fromJson(asMap(j['till'])) : null,
      pricesAsOf: asDate(j['pricesAsOf'])?.toUtc(),
      sweptAt: asDate(j['sweptAt'])?.toUtc(),
    );
  }
}

/// The offline catalogue: an in-memory [CatalogSnapshot] backed by the device
/// store, with the lookups the registers need while offline.
class OfflineCatalog {
  OfflineCatalog(this._store);
  final BlobStore _store;

  static const _key = 'catalog';

  CatalogSnapshot _snap = const CatalogSnapshot();
  CatalogSnapshot get snapshot => _snap;

  Future<void> load() async {
    try {
      final raw = await _store.read(_key);
      if (raw == null || raw.isEmpty) return;
      _snap = CatalogSnapshot.fromJson(
          (jsonDecode(utf8.decode(raw)) as Map).cast<String, dynamic>());
    } catch (e) {
      // An unreadable cache is only a cache: start empty and refresh.
      debugPrint('POS offline catalogue could not be read: $e');
    }
  }

  Future<void> save(CatalogSnapshot next) async {
    _snap = next;
    try {
      await _store.write(_key, Uint8List.fromList(utf8.encode(jsonEncode(next.toJson()))));
    } catch (e) {
      debugPrint('POS offline catalogue could not be saved: $e');
    }
  }

  /// Keeps the till this device runs a shift on, for an offline shift later.
  Future<void> rememberTill(PosTill till) => save(_snap.copyWith(till: till));

  /// Remembers a barcode resolved online, so it scans offline before the next
  /// daily sweep. Memory only; written with the next refresh.
  void learnBarcode(String code, String productUid, String? unitId) {
    if (code.trim().isEmpty || productUid.isEmpty) return;
    _snap = _snap.copyWith(
        barcodes: {..._snap.barcodes, code.trim(): CachedBarcode(productUid, unitId)});
  }

  // ------------------------------------------------------------------ lookups

  Product? product(String uid) => _snap.products[uid];

  /// Products matching [q] (code or name, any case) and/or [category], exact
  /// code matches first — what the online search returns, from the cache.
  List<Product> search({String? q, String? category, int page = 0, int size = 50}) {
    final needle = q?.trim().toLowerCase() ?? '';
    final exact = <Product>[];
    final rest = <Product>[];
    for (final p in _snap.products.values) {
      if (category != null && p.category != category) continue;
      if (needle.isEmpty) {
        rest.add(p);
      } else if (p.code.toLowerCase() == needle) {
        exact.add(p);
      } else if (p.code.toLowerCase().contains(needle) ||
          p.name.toLowerCase().contains(needle)) {
        rest.add(p);
      }
    }
    rest.sort((a, b) => a.name.toLowerCase().compareTo(b.name.toLowerCase()));
    final all = [...exact, ...rest];
    final from = page * size;
    if (from >= all.length) return const [];
    return all.sublist(from, (from + size).clamp(0, all.length));
  }

  /// The product behind a scanned [code]: a cached barcode, else an exact
  /// product code (some shops key the code into the barcode field).
  ({Product product, String? unitId})? byBarcode(String code) {
    final c = code.trim();
    final hit = _snap.barcodes[c];
    if (hit != null) {
      final p = _snap.products[hit.productUid];
      if (p != null) return (product: p, unitId: hit.unitId);
    }
    for (final p in _snap.products.values) {
      if (p.code == c) return (product: p, unitId: null);
    }
    return null;
  }

  /// The cached server price for [productUid] in [unitUid] (null = base unit).
  CachedPrice? price(String productUid, {String? unitUid}) {
    final p = _snap.products[productUid];
    final base = unitUid == null || (p != null && p.baseUnitUid == unitUid);
    return _snap.prices[CatalogSnapshot.priceKey(productUid, base ? null : unitUid)];
  }

  /// [productUids] priced as the server's batch resolver would answer, from the
  /// cache: RESOLVED with the cached figure, else a definite NO_PRICE.
  List<ResolvedUnitPrice> resolve(List<String> productUids, {String? unitUid}) => [
        for (final uid in productUids)
          if (_snap.products.containsKey(uid))
            () {
              final c = price(uid, unitUid: unitUid);
              final unit = unitUid ?? _snap.products[uid]!.baseUnitUid ?? '';
              return c == null
                  ? ResolvedUnitPrice(
                      productUid: uid, unitUid: unit, status: UnitPriceStatus.noPrice)
                  : ResolvedUnitPrice(
                      productUid: uid,
                      unitUid: unit,
                      status: UnitPriceStatus.resolved,
                      amount: c.amount,
                      currency: _snap.currency,
                      vatInclusive: c.vatInclusive);
            }(),
      ];

  /// The cached packs of a product as `ProductPack`s (the shape the register's
  /// unit picker already understands).
  List<ProductPack> packsOf(String productUid) => [
        for (final p in _snap.packs[productUid] ?? const <CachedPack>[])
          ProductPack(
            uid: '',
            unitUid: p.unitUid,
            unitCode: _snap.units[p.unitUid]?.code,
            unitName: _snap.units[p.unitUid]?.name,
            factorToBase: p.factor,
            barcode: null,
            saleDefault: false,
          ),
      ];
}

/// Fills the offline catalogue from the server while the till is online
/// (ADR-0068 D-2): after sign-in, every ~15 minutes, and on demand.
///
/// Products are paged through the normal list (200 a page). Prices come from
/// the server's own resolver in batches of 200, for the walk-in customer — the
/// only customer an offline sale may have. Barcodes and packs have no
/// company-wide list, so they are swept one product at a time, at most once a
/// day; barcodes scanned online in between are learnt as they go.
class OfflineCatalogRefresher {
  OfflineCatalogRefresher(this._svc, {DateTime Function()? clock})
      : _now = clock ?? DateTime.now;

  final CatalogService _svc;
  final DateTime Function() _now;

  static const int _page = 200;
  static const int _maxPages = 200;
  static const int _batch = 200;
  static const Duration sweepEvery = Duration(hours: 24);

  /// Requests in flight at once during the barcode/pack sweep.
  static const int _sweepParallel = 6;

  /// Builds a fresh snapshot from the server. Throws on a failure that leaves
  /// nothing useful (the caller keeps the previous cache).
  Future<CatalogSnapshot> build({
    required CatalogSnapshot previous,
    required String companyId,
    required String branchUid,
    required String currency,
    required Customer? walkIn,
    required Map<String, Unit> unitsByUid,
    required Map<String, double> vatRates,
    bool forceSweep = false,
  }) async {
    final products = <String, Product>{};
    for (var page = 0; page < _maxPages; page++) {
      final rows = await _svc.listProductsPage(companyId, page: page, size: _page);
      for (final p in rows) {
        if (p.isActive && p.sellable) products[p.uid] = p;
      }
      if (rows.length < _page) break;
    }

    List<String> categories = previous.categories;
    try {
      categories = await _svc.listCategories(companyId);
    } catch (_) {/* keep the last ones */}

    final sameCompany = previous.companyId == companyId;
    var barcodes = sameCompany ? {...previous.barcodes} : <String, CachedBarcode>{};
    var packs = sameCompany ? {...previous.packs} : <String, List<CachedPack>>{};
    var sweptAt = sameCompany ? previous.sweptAt : null;
    final now = _now().toUtc();
    if (forceSweep || sweptAt == null || now.difference(sweptAt) >= sweepEvery) {
      final swept = await _sweep(products.keys.toList());
      barcodes = {...barcodes, ...swept.barcodes};
      packs = swept.packs;
      sweptAt = now;
    }
    // Drop what no longer exists.
    barcodes.removeWhere((_, b) => !products.containsKey(b.productUid));
    packs.removeWhere((uid, _) => !products.containsKey(uid));

    final prices = <String, CachedPrice>{};
    final customerUid = (walkIn?.uid.isEmpty ?? true) ? null : walkIn!.uid;
    final uids = products.keys.toList();
    for (var i = 0; i < uids.length; i += _batch) {
      final chunk = uids.sublist(i, (i + _batch).clamp(0, uids.length));
      final rows =
          await _svc.resolvePrices(chunk, customerUid: customerUid, currency: currency);
      for (final r in rows) {
        if (r.isResolved) {
          prices[CatalogSnapshot.priceKey(r.productUid, null)] =
              CachedPrice(r.amount!, r.vatInclusive);
        }
      }
    }
    // Pack prices: one batch per pack unit (the resolver prices one unit at a time).
    final byPackUnit = <String, List<String>>{};
    packs.forEach((productUid, list) {
      for (final p in list) {
        byPackUnit.putIfAbsent(p.unitUid, () => []).add(productUid);
      }
    });
    for (final e in byPackUnit.entries) {
      for (var i = 0; i < e.value.length; i += _batch) {
        final chunk = e.value.sublist(i, (i + _batch).clamp(0, e.value.length));
        try {
          final rows = await _svc.resolvePrices(chunk,
              unitUid: e.key, customerUid: customerUid, currency: currency);
          for (final r in rows) {
            if (r.isResolved) {
              prices[CatalogSnapshot.priceKey(r.productUid, e.key)] =
                  CachedPrice(r.amount!, r.vatInclusive);
            }
          }
        } catch (_) {/* that pack is simply not sellable offline */}
      }
    }

    return previous.copyWith(
      companyId: companyId,
      branchUid: branchUid,
      currency: currency,
      products: products,
      barcodes: barcodes,
      packs: packs,
      prices: prices,
      categories: categories,
      vatRates: vatRates,
      units: unitsByUid,
      walkIn: walkIn,
      pricesAsOf: now,
      sweptAt: sweptAt,
    );
  }

  Future<({Map<String, CachedBarcode> barcodes, Map<String, List<CachedPack>> packs})> _sweep(
      List<String> productUids) async {
    final barcodes = <String, CachedBarcode>{};
    final packs = <String, List<CachedPack>>{};
    var next = 0;
    Future<void> worker() async {
      while (next < productUids.length) {
        final uid = productUids[next++];
        try {
          for (final b in await _svc.listBarcodes(uid)) {
            if (b.barcode.trim().isNotEmpty) {
              barcodes[b.barcode.trim()] = CachedBarcode(uid, b.uomId);
            }
          }
        } catch (_) {/* this product scans online only until the next sweep */}
        try {
          final list = [
            for (final p in await _svc.listPacks(uid))
              if (p.unitUid != null && p.factorToBase > 0) CachedPack(p.unitUid!, p.factorToBase),
          ];
          if (list.isNotEmpty) packs[uid] = list;
        } catch (_) {/* base unit only offline */}
      }
    }

    await Future.wait([for (var i = 0; i < _sweepParallel; i++) worker()]);
    return (barcodes: barcodes, packs: packs);
  }
}
