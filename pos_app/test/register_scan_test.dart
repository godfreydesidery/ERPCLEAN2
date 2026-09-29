import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:pos_app/core/money.dart';
import 'package:pos_app/features/register/supermarket_register.dart';
import 'package:pos_app/models/catalog.dart';
import 'package:pos_app/models/context.dart';
import 'package:pos_app/models/enums.dart';
import 'package:pos_app/services/catalog_service.dart';
import 'package:pos_app/state/app_controller.dart';
import 'package:pos_app/state/cart_controller.dart';
import 'package:pos_app/state/providers.dart';

/// A barcode scanner is a keyboard that types the whole symbol and presses
/// Enter within a few milliseconds. The register runs TWO searches off that:
/// the Enter-driven barcode lookup, and a 250 ms debounced search-as-you-type.
/// Nothing cancelled the debounced one, so its reply landed after the item was
/// added and re-opened the dropdown (the client's report) — and the next scan's
/// Enter then added the stale highlighted row, i.e. the PREVIOUS item.
void main() {
  final pcs = Unit(
      id: '1', uid: 'U-PCS', code: 'PCS', name: 'Pieces', symbol: 'pc',
      decimalPlaces: 0, fractional: false);

  Product product(String n) => Product(
      id: n, uid: 'P-$n', code: 'CODE-$n', name: 'Product $n', sellable: true,
      baseUnitUid: pcs.uid, baseUnitCode: pcs.code, baseUnitName: pcs.name,
      cost: const Money(0, 'TZS'), vatStatus: 'STANDARD',
      restrictedKind: RestrictedKind.none, status: 'ACTIVE');

  final a = product('A');
  final b = product('B');
  final byBarcode = {'1111111111111': a, '2222222222222': b};

  late _FakeCatalog catalog;

  Future<void> pumpRegister(WidgetTester tester) async {
    tester.view.physicalSize = const Size(1600, 1000);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.reset);
    catalog = _FakeCatalog(byBarcode);
    await tester.pumpWidget(ProviderScope(
      overrides: [
        catalogServiceProvider.overrideWithValue(catalog),
        appControllerProvider.overrideWith(() => _TestApp(pcs)),
      ],
      child: const MaterialApp(home: Scaffold(body: SupermarketRegister())),
    ));
    await tester.pump();
  }

  /// Scanner-speed input: the whole symbol in one edit, then Enter at once.
  Future<void> scan(WidgetTester tester, String code) async {
    await tester.enterText(find.byType(TextField).first, code);
    await tester.testTextInput.receiveAction(TextInputAction.go);
    await tester.pump();
  }

  /// Let every timer and slow reply finish.
  Future<void> settle(WidgetTester tester) async {
    for (var i = 0; i < 10; i++) {
      await tester.pump(const Duration(milliseconds: 100));
    }
  }

  Finder dropdownRow(Product p) =>
      find.descendant(of: find.byType(Scrollbar), matching: find.text(p.name));

  List<String> cartNames(WidgetTester tester) {
    final container = ProviderScope.containerOf(
        tester.element(find.byType(SupermarketRegister)));
    return container
        .read(cartProvider)
        .activeLines
        .expand((l) => List.filled(l.quantity.round(), l.product.name))
        .toList();
  }

  testWidgets('a scan adds the item and the dropdown stays closed',
      (tester) async {
    await pumpRegister(tester);

    await scan(tester, '1111111111111');
    await settle(tester);

    expect(cartNames(tester), ['Product A']);
    expect(dropdownRow(a), findsNothing,
        reason: 'the debounced search must not re-open the dropdown');
    expect(tester.widget<TextField>(find.byType(TextField).first).controller!.text,
        isEmpty);
  });

  testWidgets('back-to-back scans each add the item that was scanned',
      (tester) async {
    await pumpRegister(tester);

    await scan(tester, '1111111111111');
    await settle(tester);
    await scan(tester, '2222222222222');
    await settle(tester);

    expect(cartNames(tester), ['Product A', 'Product B'],
        reason: 'the second scan must not add the stale highlighted row');
    expect(dropdownRow(b), findsNothing);
  });

  testWidgets('typed search still opens the dropdown and Enter adds the row',
      (tester) async {
    await pumpRegister(tester);

    await tester.enterText(find.byType(TextField).first, 'Product');
    await settle(tester);
    expect(dropdownRow(a), findsOneWidget);
    expect(dropdownRow(b), findsOneWidget);

    await tester.sendKeyEvent(LogicalKeyboardKey.arrowDown);
    await tester.testTextInput.receiveAction(TextInputAction.go);
    await settle(tester);

    expect(cartNames(tester), ['Product B']);
    expect(dropdownRow(a), findsNothing);
  });
}

class _TestApp extends AppController {
  _TestApp(this.pcs);
  final Unit pcs;

  @override
  AppData build() => AppData(
        context: PosContext(
          organisationUid: 'ORG',
          company: Company(id: '1', uid: 'C-1', name: 'Test Co'),
          branch: Branch(
              id: '1', uid: 'B-1', companyId: '1', companyUid: 'C-1',
              code: 'HQ', name: 'HQ', isDefault: true, status: 'ACTIVE'),
        ),
        unitsByUid: {pcs.uid: pcs},
      );
}

/// A catalogue server that answers slowly, the way a real one over a network
/// does — slow enough that the debounced search replies after the scan's own
/// lookup has already added the item.
class _FakeCatalog implements CatalogService {
  _FakeCatalog(this.byBarcode);
  final Map<String, Product> byBarcode;

  List<Product> get all => byBarcode.values.toList();

  @override
  Future<ProductBarcode> lookupBarcode(String companyId, String barcode) async {
    await Future<void>.delayed(const Duration(milliseconds: 40));
    final p = byBarcode[barcode]!;
    return ProductBarcode(
        uid: 'BC-$barcode', productId: p.id, productUid: p.uid,
        barcode: barcode, barcodeType: 'EAN13', uomId: null, primary: true,
        derivedQuantity: null, derivedAmount: null, valueKind: null);
  }

  @override
  Future<Product> getProduct(String uid) async =>
      all.firstWhere((p) => p.uid == uid);

  /// Mirrors the backend: name/code contains, or an exact barcode match.
  @override
  Future<List<Product>> searchProducts(String companyId,
      {String? q, int page = 0, int size = 50}) async {
    await Future<void>.delayed(const Duration(milliseconds: 150));
    final s = (q ?? '').toLowerCase();
    return [
      for (final e in byBarcode.entries)
        if (e.key == q ||
            e.value.name.toLowerCase().contains(s) ||
            e.value.code.toLowerCase().contains(s))
          e.value
    ];
  }

  @override
  Future<Map<String, StockLevel>> stockByProduct(
          {String? q, int page = 0, int size = 200}) async =>
      const {};

  @override
  Future<List<ResolvedUnitPrice>> resolvePrices(List<String> productUids,
          {String? unitUid}) async =>
      const [];

  @override
  Future<List<ProductPrice>> listPrices(String productUid) async => const [];

  @override
  Future<List<ProductPack>> listPacks(String productUid) async => const [];

  @override
  dynamic noSuchMethod(Invocation invocation) =>
      throw UnimplementedError(invocation.memberName.toString());
}
