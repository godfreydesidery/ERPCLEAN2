import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:pos_app/models/catalog.dart';
import 'package:pos_app/services/catalog_service.dart';
import 'package:pos_app/state/app_controller.dart';
import 'package:pos_app/state/cart_controller.dart';
import 'package:pos_app/state/catalog_cache.dart';

/// Selling by a bulk pack (carton, box) rather than the product's base unit.
///
/// The server is authoritative: it converts `quantity × factorToBase` into base
/// units and re-derives the price. These tests pin the CLIENT side of that
/// contract — that the right unit is posted, that previews use the same
/// arithmetic the server does, and that the stock check compares like with like.
void main() {
  Unit unit(String id, String code, {String? uid}) => Unit.fromJson({
        'id': id,
        'uid': uid ?? 'u-$code',
        'code': code,
        'name': code == 'PCS' ? 'Pieces' : code,
        'decimalPlaces': 0,
        'fractional': false,
      });

  Product product({String baseUnitUid = 'u-PCS'}) => Product.fromJson({
        'id': '100',
        'uid': 'p-soap',
        'code': 'SOAP',
        'name': 'Tembo Bar Soap',
        'sellable': true,
        'baseUnitUid': baseUnitUid,
        'baseUnitCode': 'PCS',
        'baseUnitName': 'Pieces',
        'vatStatus': 'STANDARD',
        'status': 'ACTIVE',
      });

  final pcs = unit('1', 'PCS');
  final carton = unit('2', 'CARTON');
  final box = unit('3', 'BOX');
  final unitsByUid = {'u-PCS': pcs, 'u-CARTON': carton, 'u-BOX': box};

  Map<String, dynamic> packJson(String unitUid, num factor,
          {String uid = 'bp1'}) =>
      {
        'uid': uid,
        'unitUid': unitUid,
        'unitCode': unitUid.replaceFirst('u-', ''),
        'unitName': unitUid.replaceFirst('u-', ''),
        'factorToBase': factor,
      };

  group('SaleUnit', () {
    test('labels a whole-number factor without a trailing .0', () {
      expect(SaleUnit(carton, 24).factorLabel, '24');
      expect(SaleUnit(carton, 24).label, 'CARTON (×24)');
    });

    test('keeps a fractional factor readable', () {
      expect(SaleUnit(carton, 0.5).factorLabel, '0.5');
    });

    test('the base unit shows no multiplier', () {
      expect(SaleUnit(pcs, 1).isBase, isTrue);
      expect(SaleUnit(pcs, 1).label, 'Pieces');
    });
  });

  group('Catalogue.sellableUnits', () {
    test('lists the base unit first, then each configured pack', () async {
      final cat = Catalogue(_FakeCatalogService([
        ProductPack.fromJson(packJson('u-CARTON', 24)),
        ProductPack.fromJson(packJson('u-BOX', 12, uid: 'bp2')),
      ]));

      final units = await cat.sellableUnits(product(), unitsByUid);

      expect(units.map((u) => u.unit.code), ['PCS', 'CARTON', 'BOX']);
      expect(units.map((u) => u.factor), [1, 24, 12]);
    });

    test('skips a pack whose unit the company no longer has', () async {
      final cat = Catalogue(_FakeCatalogService([
        ProductPack.fromJson(packJson('u-GONE', 6)),
        ProductPack.fromJson(packJson('u-CARTON', 24, uid: 'bp2')),
      ]));

      final units = await cat.sellableUnits(product(), unitsByUid);

      expect(units.map((u) => u.unit.code), ['PCS', 'CARTON']);
    });

    test('never lists the base unit twice when a pack names it', () async {
      final cat = Catalogue(_FakeCatalogService([
        ProductPack.fromJson(packJson('u-PCS', 1)),
      ]));

      final units = await cat.sellableUnits(product(), unitsByUid);

      expect(units.length, 1);
      expect(units.single.unit.code, 'PCS');
    });

    test('ignores a non-positive factor', () async {
      final cat = Catalogue(_FakeCatalogService([
        ProductPack.fromJson(packJson('u-CARTON', 0)),
      ]));

      final units = await cat.sellableUnits(product(), unitsByUid);

      expect(units.map((u) => u.unit.code), ['PCS']);
    });

    test('falls back to the base unit when the pack fetch fails', () async {
      // A cashier without PRODUCT.VIEW on packs, or an offline blip, must still
      // be able to ring the item in its base unit.
      final cat = Catalogue(_FakeCatalogService(const [], fails: true));

      final units = await cat.sellableUnits(product(), unitsByUid);

      expect(units.map((u) => u.unit.code), ['PCS']);
    });

    test('returns nothing when even the base unit is unknown', () async {
      final cat = Catalogue(_FakeCatalogService(const []));

      final units =
          await cat.sellableUnits(product(baseUnitUid: 'u-MISSING'), unitsByUid);

      expect(units, isEmpty);
    });
  });

  group('Catalogue.sellableUnitById — a scanned pack barcode', () {
    test('resolves the pack the barcode addresses', () async {
      final cat = Catalogue(_FakeCatalogService([
        ProductPack.fromJson(packJson('u-CARTON', 24)),
      ]));

      final found = await cat.sellableUnitById(product(), '2', unitsByUid);

      expect(found?.unit.code, 'CARTON');
      expect(found?.factor, 24);
    });

    test('returns null for a unit that is not configured for the product',
        () async {
      final cat = Catalogue(_FakeCatalogService(const []));

      final found = await cat.sellableUnitById(product(), '3', unitsByUid);

      expect(found, isNull, reason: 'caller falls back to the base unit');
    });
  });

  group('CartController — pack lines', () {
    late ProviderContainer container;
    late CartController cart;

    setUp(() {
      container = ProviderContainer();
      cart = container.read(cartProvider.notifier);
    });

    tearDown(() => container.dispose());

    test('base quantity multiplies out the pack factor', () {
      cart.addProduct(product(), carton, unitFactor: 24, quantity: 2);

      final line = container.read(cartProvider).lines.single;
      expect(line.quantity, 2, reason: 'the cashier keyed 2 cartons');
      expect(line.baseQuantity, 48, reason: '2 × 24 pieces leave the shelf');
    });

    test('the request posts the pack unit and the PACK quantity', () {
      // The server multiplies by factorToBase itself — posting 48 here would
      // double-convert and sell two cartons as forty-eight.
      cart.addProduct(product(), carton, unitFactor: 24, quantity: 2);

      final line = (container
          .read(cartProvider)
          .buildRequest('sess-1')['lines'] as List)
          .single as Map;

      expect(line['unitId'], '2');
      expect(line['quantity'], 2);
    });

    test('a base-unit line still defaults to factor 1', () {
      cart.addProduct(product(), pcs);

      final line = container.read(cartProvider).lines.single;
      expect(line.unitFactor, 1);
      expect(line.baseQuantity, 1);
    });

    test('the same product in two units stays on two lines', () {
      cart.addProduct(product(), pcs, quantity: 3);
      cart.addProduct(product(), carton, unitFactor: 24, quantity: 1);

      expect(container.read(cartProvider).lines.length, 2);
    });
  });

  group('CartController.setLineUnit', () {
    late ProviderContainer container;
    late CartController cart;

    setUp(() {
      container = ProviderContainer();
      cart = container.read(cartProvider.notifier);
    });

    tearDown(() => container.dispose());

    test('switches unit and factor, keeping the keyed quantity', () {
      cart.addProduct(product(), pcs, quantity: 2, unitPricePreview: 4000);
      final id = container.read(cartProvider).lines.single.localId;

      cart.setLineUnit(id, carton, 24);

      final line = container.read(cartProvider).lines.single;
      expect(line.unit.code, 'CARTON');
      expect(line.unitFactor, 24);
      expect(line.quantity, 2, reason: '"2" now means two cartons');
      expect(line.baseQuantity, 48);
    });

    test('clears the stale per-piece price so no wrong total is shown', () {
      cart.addProduct(product(), pcs, quantity: 1, unitPricePreview: 4000);
      final id = container.read(cartProvider).lines.single.localId;

      cart.setLineUnit(id, carton, 24);

      final line = container.read(cartProvider).lines.single;
      expect(line.unitPricePreview, isNull);
      expect(line.previewGross, 0, reason: 'shows "—" until re-priced');
    });

    test('merges into an existing line of the same product and unit', () {
      cart.addProduct(product(), carton, unitFactor: 24, quantity: 1);
      final cartonId = container.read(cartProvider).lines.single.localId;
      cart.addProduct(product(), pcs, quantity: 2);
      final pcsId = container
          .read(cartProvider)
          .lines
          .firstWhere((l) => l.localId != cartonId)
          .localId;

      cart.setLineUnit(pcsId, carton, 24);

      final lines = container.read(cartProvider).lines;
      expect(lines.length, 1, reason: 'one product+unit, one line');
      expect(lines.single.localId, cartonId);
      expect(lines.single.quantity, 3, reason: '1 carton + the switched 2');
      expect(container.read(cartProvider).selectedId, cartonId,
          reason: 'selection follows the surviving line');
    });

    test('does nothing when the unit is already the one asked for', () {
      cart.addProduct(product(), pcs, quantity: 2, unitPricePreview: 4000);
      final id = container.read(cartProvider).lines.single.localId;

      cart.setLineUnit(id, pcs, 1);

      expect(container.read(cartProvider).lines.single.unitPricePreview, 4000,
          reason: 'a no-op must not blank a good price');
    });

    test('ignores an unknown line id', () {
      cart.addProduct(product(), pcs);

      cart.setLineUnit('not-a-line', carton, 24);

      expect(container.read(cartProvider).lines.single.unit.code, 'PCS');
    });
  });

  group('pack price preview', () {
    // Mirrors PriceResolutionServiceImpl: the price list holds the BASE unit
    // price, and a pack resolves to base × factorToBase. The preview must use
    // the same arithmetic or the on-screen total disagrees with the receipt.
    const app = AppData(vatRates: {'STANDARD': 0.18});

    test('a pack previews at base price × factor, plus VAT', () {
      expect(app.grossUnitPrice(1000 * 24, 'STANDARD'), closeTo(28320, 0.001));
    });

    test('a VAT-inclusive price list is not grossed up twice', () {
      expect(app.grossUnitPrice(1180 * 24, 'STANDARD', vatInclusive: true),
          closeTo(28320, 0.001));
    });

    test('the base unit is unchanged by the factor-1 multiplication', () {
      expect(app.grossUnitPrice(1000 * 1, 'STANDARD'), closeTo(1180, 0.001));
    });
  });
}

/// Stands in for the HTTP layer: [Catalogue] only calls `listPacks` on these
/// paths, so everything else is left to throw if it is ever reached.
class _FakeCatalogService implements CatalogService {
  _FakeCatalogService(this.packs, {this.fails = false});

  final List<ProductPack> packs;
  final bool fails;

  @override
  Future<List<ProductPack>> listPacks(String productUid) async {
    if (fails) throw Exception('packs unavailable');
    return packs;
  }

  @override
  dynamic noSuchMethod(Invocation invocation) => super.noSuchMethod(invocation);
}
