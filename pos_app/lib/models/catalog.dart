import '../core/json.dart';
import '../core/money.dart';
import 'enums.dart';

/// A sellable product (subset of `ProductDto` the till needs).
class Product {
  Product({
    required this.id,
    required this.uid,
    required this.code,
    required this.name,
    required this.sellable,
    required this.baseUnitUid,
    required this.baseUnitCode,
    required this.baseUnitName,
    required this.cost,
    required this.vatStatus,
    required this.restrictedKind,
    required this.status,
  });

  final String id;
  final String uid;
  final String code;
  final String name;
  final bool sellable;
  final String? baseUnitUid;
  final String? baseUnitCode;
  final String? baseUnitName;
  final Money cost;
  final String vatStatus;
  final RestrictedKind restrictedKind;
  final String status;

  bool get isActive => status == 'ACTIVE';

  factory Product.fromJson(Map<String, dynamic> j) => Product(
        id: asStrOr(j['id']),
        uid: asStrOr(j['uid']),
        code: asStrOr(j['code']),
        name: asStrOr(j['name']),
        sellable: asBool(j['sellable'], true),
        baseUnitUid: asStr(j['baseUnitUid']),
        baseUnitCode: asStr(j['baseUnitCode']),
        baseUnitName: asStr(j['baseUnitName']),
        cost: Money.fromJson(asMap(j['cost'])),
        vatStatus: asStrOr(j['vatStatus'], 'STANDARD'),
        restrictedKind: RestrictedKind.fromWire(asStr(j['restrictedKind'])),
        status: asStrOr(j['status'], 'ACTIVE'),
      );
}

/// A barcode lookup result (`ProductBarcodeDto`). The `derived*` fields are
/// populated only for an embedded weight/price scan (ADR-0044 D-1a); they are
/// null on a plain exact-match.
class ProductBarcode {
  ProductBarcode({
    required this.uid,
    required this.productId,
    required this.productUid,
    required this.barcode,
    required this.barcodeType,
    required this.uomId,
    required this.primary,
    required this.derivedQuantity,
    required this.derivedAmount,
    required this.valueKind,
  });

  final String uid;
  final String productId;

  /// The scanned product's external uid — used to fetch the full product fresh.
  final String productUid;
  final String barcode;
  final String barcodeType;
  final String? uomId;
  final bool primary;

  /// Decoded weight (e.g. kg) — non-null only for an embedded-WEIGHT scan.
  final double? derivedQuantity;

  /// Decoded amount — non-null only for an embedded-PRICE scan.
  final double? derivedAmount;

  /// `WEIGHT` | `PRICE` | null.
  final String? valueKind;

  bool get isEmbedded => valueKind != null;

  factory ProductBarcode.fromJson(Map<String, dynamic> j) => ProductBarcode(
        uid: asStrOr(j['uid']),
        productId: asStrOr(j['productId']),
        productUid: asStrOr(j['productUid']),
        barcode: asStrOr(j['barcode']),
        barcodeType: asStrOr(j['barcodeType']),
        uomId: asStr(j['uomId']),
        primary: asBool(j['primary']),
        derivedQuantity: asNum(j['derivedQuantity']),
        derivedAmount: asNum(j['derivedAmount']),
        valueKind: asStr(j['valueKind']),
      );
}

/// A configured bulk pack (`ProductBulkPackDto`) — a sellable unit larger than
/// the product's base unit, carrying the conversion factor the server applies
/// (`qty_in_base = quantity × factorToBase`). The DTO identifies its unit by
/// **uid only**, so the numeric `unitId` the sale payload needs is resolved from
/// [AppData.unitsByUid].
class ProductPack {
  ProductPack({
    required this.uid,
    required this.unitUid,
    required this.unitCode,
    required this.unitName,
    required this.factorToBase,
    required this.barcode,
    required this.saleDefault,
  });

  final String uid;
  final String? unitUid;
  final String? unitCode;
  final String? unitName;

  /// How many base units one of this pack contains. Always > 0 (DB CHECK).
  final double factorToBase;
  final String? barcode;
  final bool saleDefault;

  factory ProductPack.fromJson(Map<String, dynamic> j) => ProductPack(
        uid: asStrOr(j['uid']),
        unitUid: asStr(j['unitUid']),
        unitCode: asStr(j['unitCode']),
        unitName: asStr(j['unitName']),
        factorToBase: asNum(j['factorToBase']) ?? 1,
        barcode: asStr(j['barcode']),
        saleDefault: asBool(j['saleDefault']),
      );
}

/// A unit of measure (`UnitOfMeasureDto`).
class Unit {
  Unit({
    required this.id,
    required this.uid,
    required this.code,
    required this.name,
    required this.symbol,
    required this.decimalPlaces,
    required this.fractional,
  });

  final String id;
  final String uid;
  final String code;
  final String name;
  final String? symbol;
  final int decimalPlaces;
  final bool fractional;

  factory Unit.fromJson(Map<String, dynamic> j) => Unit(
        id: asStrOr(j['id']),
        uid: asStrOr(j['uid']),
        code: asStrOr(j['code']),
        name: asStrOr(j['name']),
        symbol: asStr(j['symbol']),
        decimalPlaces: asIntOr(j['decimalPlaces'], 0),
        fractional: asBool(j['fractional']),
      );
}

/// A product price row (`ProductPriceDto`) — used for the **preview** unit price
/// only (the ERP re-derives the authoritative price at sale time, PRIN-2).
class ProductPrice {
  ProductPrice({
    required this.priceListUid,
    required this.priceListCode,
    required this.price,
    this.unitUid,
    this.priceIncludesVat = false,
  });

  final String priceListUid;
  final String priceListCode;
  final Money price;

  /// The unit this price is for. **Null means the base-unit price** (ADR-0048).
  /// A non-null value is an explicit per-pack override — a carton price set by
  /// hand that is deliberately NOT piece price × factor.
  final String? unitUid;

  /// Whether [price] already includes VAT (the source price list's stance,
  /// ADR-0056). When true the POS preview must NOT add VAT on top.
  final bool priceIncludesVat;

  factory ProductPrice.fromJson(Map<String, dynamic> j) => ProductPrice(
        priceListUid: asStrOr(j['priceListUid']),
        priceListCode: asStrOr(j['priceListCode']),
        price: Money.fromJson(asMap(j['price'])),
        unitUid: asStr(j['unitUid']),
        priceIncludesVat: asBool(j['priceIncludesVat']),
      );
}
