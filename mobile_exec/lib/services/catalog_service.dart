import '../core/json.dart';
import '../core/session.dart';

/// A product as the app needs it, flattened from `ProductDto`.
class ProductItem {
  const ProductItem({
    required this.uid,
    required this.code,
    required this.name,
    required this.unit,
    required this.baseUnitUid,
    this.categoryName,
  });

  factory ProductItem.fromJson(Map<String, dynamic> j) => ProductItem(
        uid: asStrOr(j['uid']),
        code: asStrOr(j['code']),
        name: asStrOr(j['name']),
        unit: asStrOr(j['baseUnitCode'], asStrOr(j['unitCode'], 'unit')),
        baseUnitUid: asStrOr(j['baseUnitUid']),
        categoryName: asStr(j['categoryName']),
      );

  final String uid;
  final String code;
  final String name;

  /// The base unit's CODE — what stock, adjustments and every report count in.
  final String unit;

  /// The base unit's uid. Every write addresses a unit by uid; the screens
  /// display the code.
  final String baseUnitUid;
  final String? categoryName;
}

class SupplierItem {
  const SupplierItem({required this.uid, required this.name, this.phone});

  factory SupplierItem.fromJson(Map<String, dynamic> j) => SupplierItem(
        uid: asStrOr(j['uid']),
        name: asStrOr(j['displayName'], asStrOr(j['name'])),
        phone: asStr(j['phone']),
      );

  final String uid;
  final String name;
  final String? phone;
}

/// A unit of measure the company has configured (`UnitOfMeasureDto`).
class UnitRef {
  const UnitRef({required this.uid, required this.code, required this.name});

  factory UnitRef.fromJson(Map<String, dynamic> j) => UnitRef(
        uid: asStrOr(j['uid']),
        code: asStrOr(j['code']),
        name: asStrOr(j['name'], asStrOr(j['code'])),
      );

  final String uid;
  final String code;
  final String name;

  /// "Carton (CTN)" — the name an owner recognises, with the code they see on
  /// every report.
  String get label => name.isEmpty || name == code ? code : '$name ($code)';
}

/// A configured pack (`ProductBulkPackDto`) — a unit LARGER than the product's
/// base unit, with the conversion the server applies
/// (`qty_in_base = quantity × factorToBase`).
class PackSize {
  const PackSize({
    required this.uid,
    required this.unitUid,
    required this.unitCode,
    required this.unitName,
    required this.factorToBase,
  });

  factory PackSize.fromJson(Map<String, dynamic> j) => PackSize(
        uid: asStrOr(j['uid']),
        unitUid: asStrOr(j['unitUid']),
        unitCode: asStrOr(j['unitCode']),
        unitName: asStrOr(j['unitName'], asStrOr(j['unitCode'])),
        factorToBase: asNumOr(j['factorToBase'], 1),
      );

  final String uid;
  final String unitUid;
  final String unitCode;
  final String unitName;
  final double factorToBase;
}

/// A unit a quantity may be entered in on this product: the base unit
/// (factor 1) or one of its packs.
///
/// Everything the server stores — stock on hand, adjustments, every report — is
/// in BASE units. This type is what lets the owner think in cartons while the
/// app still sends what the server expects.
class TxUnit {
  const TxUnit({
    required this.uid,
    required this.code,
    required this.name,
    required this.factor,
  });

  factory TxUnit.base(ProductItem p) => TxUnit(
        uid: p.baseUnitUid,
        code: p.unit,
        name: p.unit,
        factor: 1,
      );

  factory TxUnit.pack(PackSize pack) => TxUnit(
        uid: pack.unitUid,
        code: pack.unitCode,
        name: pack.unitName,
        factor: pack.factorToBase,
      );

  final String uid;
  final String code;
  final String name;

  /// How many base units one of these holds. 1 for the base unit itself.
  final double factor;

  bool get isBase => factor == 1;

  /// "24" rather than "24.0" for a whole-number factor.
  String get factorLabel =>
      factor == factor.roundToDouble() ? factor.toStringAsFixed(0) : '$factor';

  /// "CTN (24 PCS)" for a pack; the plain code for the base unit. The base code
  /// has to be passed in — a pack does not know what it converts to.
  String labelIn(String baseCode) =>
      isBase ? code : '$code ($factorLabel $baseCode)';

  double toBase(double quantity) => quantity * factor;
}

/// Reads [qtyInBase] back in the units the owner buys and sells in:
/// "10 CTN + 4 PCS" for 244 pieces of a product packed 24 to the carton.
///
/// Largest pack first, remainder in the base unit — the way stock is actually
/// counted on a shelf. Returns null when there is nothing more to say than the
/// base figure the caller already shows (no packs, or a quantity smaller than
/// the smallest pack).
String? describeInPacks(
  double qtyInBase,
  List<TxUnit> units,
  String baseCode,
) {
  final packs = units.where((u) => !u.isBase && u.factor > 1).toList()
    ..sort((a, b) => b.factor.compareTo(a.factor));
  if (packs.isEmpty) return null;

  // Negative stock is a real state (the ERP flags it rather than forbidding
  // it). Decompose the magnitude and put the sign back on the front.
  final negative = qtyInBase < 0;
  var left = qtyInBase.abs();
  if (left < packs.last.factor) return null;

  final parts = <String>[];
  for (final pack in packs) {
    final whole = (left / pack.factor).floor();
    if (whole <= 0) continue;
    parts.add('$whole ${pack.code}');
    left -= whole * pack.factor;
  }
  if (parts.isEmpty) return null;
  if (left > 0) {
    parts.add('${left == left.roundToDouble() ? left.toStringAsFixed(0) : left}'
        ' $baseCode');
  }
  return '${negative ? '-' : ''}${parts.join(' + ')}';
}

/// A price list (`PriceListDto`) — only the parts the app needs.
class PriceListRef {
  const PriceListRef({
    required this.uid,
    required this.name,
    required this.currency,
    required this.isDefault,
    required this.priceIncludesVat,
  });

  factory PriceListRef.fromJson(Map<String, dynamic> j) => PriceListRef(
        uid: asStrOr(j['uid']),
        name: asStrOr(j['name'], asStrOr(j['code'])),
        currency: asStrOr(j['currency'], 'TZS'),
        // The record component is `isDefault`; `default` is accepted too rather
        // than silently treating every list as non-default if that shape moves.
        isDefault: asBool(j['isDefault']) || asBool(j['default']),
        priceIncludesVat: asBool(j['priceIncludesVat']),
      );

  final String uid;
  final String name;
  final String currency;
  final bool isDefault;
  final bool priceIncludesVat;
}

/// A price row (`ProductPriceDto`) — the amount, and which unit it is for.
class UnitPrice {
  const UnitPrice({
    required this.priceListUid,
    required this.unitUid,
    required this.amount,
    required this.currency,
  });

  factory UnitPrice.fromJson(Map<String, dynamic> j) {
    final price = asMap(j['price']);
    return UnitPrice(
      priceListUid: asStrOr(j['priceListUid']),
      // Null unit = the base-unit row.
      unitUid: asStr(j['unitUid']),
      amount: asNumOr(price['amount'], asNumOr(j['amount'])),
      currency: asStrOr(price['currency'], asStrOr(j['currency'], 'TZS')),
    );
  }

  final String priceListUid;
  final String? unitUid;
  final double amount;
  final String currency;
}

/// Products, suppliers, units, packs, prices and the master-data writes.
class CatalogService {
  CatalogService(this.session);

  final Session session;

  /// `/products` takes a numeric `companyId`, not a uid.
  Future<List<ProductItem>> products({String? search, int size = 100}) async {
    final companyId = session.companyId;
    if (companyId == null) return const [];
    final res = await session.api.get('/products', query: {
      'companyId': companyId,
      if (search != null && search.isNotEmpty) 'q': search,
      'size': size,
    });
    return asList(res, ProductItem.fromJson);
  }

  /// Products keyed by code.
  ///
  /// The stock endpoints identify a row by product CODE and carry no unit, so
  /// this is what lets a report say "240 PCS" instead of a bare 240. A wider
  /// page than the pickers use: a report showing an unlabelled number for
  /// everything past the hundredth product is the bug being fixed.
  Future<Map<String, ProductItem>> productsByCode() async {
    final all = await products(size: 500);
    return {for (final p in all) p.code: p};
  }

  Future<List<SupplierItem>> suppliers({String? search}) async {
    final companyId = session.companyId;
    if (companyId == null) return const [];
    final res = await session.api.get('/suppliers', query: {
      'companyId': companyId,
      if (search != null && search.isNotEmpty) 'q': search,
      'size': 100,
    });
    return asList(res, SupplierItem.fromJson);
  }

  // -- units and packs ------------------------------------------------------

  /// Every unit of measure the company has. `/units` is open to any signed-in
  /// user — units are reference data picked on every line.
  Future<List<UnitRef>> units() async {
    final companyId = session.companyId;
    if (companyId == null) return const [];
    final res = await session.api.get('/units', query: {
      'companyId': companyId,
      'size': 200,
    });
    return asList(res, UnitRef.fromJson);
  }

  Future<List<PackSize>> packs(String productUid) async {
    final res = await session.api.get('/products/uid/$productUid/bulk-packs');
    return asList(res, PackSize.fromJson);
  }

  /// The units [p] may be transacted in — base unit first, then each pack.
  ///
  /// A failure is deliberately NOT swallowed. On a write path a silently
  /// missing carton option means the owner types "10" meaning cartons and the
  /// server stores 10 pieces; a visible error is the safe direction.
  Future<List<TxUnit>> transactionUnits(ProductItem p) async {
    final list = <TxUnit>[TxUnit.base(p)];
    for (final pack in await packs(p.uid)) {
      // Skip a non-positive factor (defensive — the DB forbids it) and any pack
      // defined on the base unit, which would duplicate the entry above.
      if (pack.factorToBase <= 0) continue;
      if (pack.unitUid == p.baseUnitUid) continue;
      if (list.any((u) => u.uid == pack.unitUid)) continue;
      list.add(TxUnit.pack(pack));
    }
    return list;
  }

  /// Add a pack size. [factorToBase] is how many BASE units one pack holds.
  Future<PackSize> addPack({
    required String productUid,
    required String unitUid,
    required double factorToBase,
  }) async {
    final res = await session.api.post(
      '/products/uid/$productUid/bulk-packs',
      body: {'unitUid': unitUid, 'factorToBase': factorToBase},
    );
    return PackSize.fromJson(asMap(res));
  }

  /// Correct a pack's conversion factor. Only the factor is updatable — the
  /// unit is part of the row's key, so changing it is a remove and re-add.
  Future<PackSize> updatePack({
    required String productUid,
    required String packUid,
    required double factorToBase,
  }) async {
    final res = await session.api.put(
      '/products/uid/$productUid/bulk-packs/$packUid',
      body: {'factorToBase': factorToBase},
    );
    return PackSize.fromJson(asMap(res));
  }

  Future<void> removePack(String productUid, String packUid) =>
      session.api.delete('/products/uid/$productUid/bulk-packs/$packUid');

  // -- prices ---------------------------------------------------------------

  /// The company's default price list, or null when there is none — or when the
  /// caller cannot see price lists. Callers treat null as "pricing is not
  /// available here" rather than as an error: a pack still sells without a
  /// price of its own, because the server falls back to base price × factor.
  Future<PriceListRef?> defaultPriceList() async {
    final companyId = session.companyId;
    if (companyId == null) return null;
    final res = await session.api.get('/price-lists', query: {
      'companyId': companyId,
      'size': 100,
    });
    final all = asList(res, PriceListRef.fromJson);
    if (all.isEmpty) return null;
    return all.firstWhere((l) => l.isDefault, orElse: () => all.first);
  }

  Future<List<UnitPrice>> prices(String productUid) async {
    final res = await session.api.get('/products/uid/$productUid/prices');
    return asList(res, UnitPrice.fromJson);
  }

  /// Set the selling price for one unit of [productUid]. A null [unitUid]
  /// targets the base-unit price; a pack unit's uid sets that pack's own price
  /// — which is what "each unit can have its own price" means.
  Future<void> setPrice({
    required String productUid,
    required String priceListUid,
    required double amount,
    required String currency,
    String? unitUid,
  }) async {
    await session.api.post('/products/uid/$productUid/prices', body: {
      'priceListUid': priceListUid,
      'price': {'amount': amount.toStringAsFixed(2), 'currency': currency},
      if (unitUid != null) 'unitUid': unitUid,
    });
  }

  // -- master-data writes ---------------------------------------------------

  /// Create a product. `CreateProductRequest` addresses the company and the
  /// base unit by **uid** (unlike the list endpoint, which wants the numeric
  /// company id), types the entry GOODS/SERVICE, and needs `sellable` and
  /// `stockable` stated — they default to false, which would create an item
  /// that can be neither sold nor stocked.
  Future<ProductItem> createProduct({
    required String name,
    required String code,
    required String baseUnitUid,
    bool vatable = true,
    bool stockable = true,
  }) async {
    final res = await session.api.post('/products', body: {
      'companyUid': session.companyUid,
      // Blank code is legal: the server assigns PROD-#### itself.
      if (code.isNotEmpty) 'code': code,
      'name': name,
      'type': 'GOODS',
      'sellable': true,
      'stockable': stockable,
      'baseUnitUid': baseUnitUid,
      'vatStatus': vatable ? 'STANDARD' : 'EXEMPT',
    });
    return ProductItem.fromJson(asMap(res));
  }

  /// Attach a barcode. Optionally scoped to one unit, so a scan at the till
  /// resolves straight to the carton rather than the piece.
  Future<void> addBarcode({
    required String productUid,
    required String barcode,
    bool primary = true,
    String? unitUid,
  }) async {
    await session.api.post('/products/uid/$productUid/barcodes', body: {
      'barcode': barcode,
      'primary': primary,
      if (unitUid != null) 'unitUid': unitUid,
    });
  }

  /// Create a supplier. `CreateSupplierRequest` takes the numeric `companyId`.
  Future<SupplierItem> createSupplier({
    required String displayName,
    String? phone,
    String? email,
    String? tin,
    bool vatRegistered = false,
    String? physicalAddress,
    int? paymentTermsDays,
  }) async {
    final res = await session.api.post('/suppliers', body: {
      'companyId': session.companyId,
      'partyType': 'ORGANISATION',
      'supplierKind': 'GOODS',
      'displayName': displayName,
      if (phone != null && phone.isNotEmpty) 'phone': phone,
      if (email != null && email.isNotEmpty) 'email': email,
      if (tin != null && tin.isNotEmpty) 'tin': tin,
      'vatRegistered': vatRegistered,
      if (physicalAddress != null && physicalAddress.isNotEmpty)
        'physicalAddress': physicalAddress,
      if (paymentTermsDays != null) 'paymentTermsDays': paymentTermsDays,
    });
    return SupplierItem.fromJson(asMap(res));
  }
}
