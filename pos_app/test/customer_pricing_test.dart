import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:pos_app/core/money.dart';
import 'package:pos_app/models/catalog.dart';
import 'package:pos_app/models/enums.dart';
import 'package:pos_app/models/parties.dart';
import 'package:pos_app/services/catalog_service.dart';
import 'package:pos_app/state/app_controller.dart';
import 'package:pos_app/state/basket_pricer.dart';
import 'package:pos_app/state/cart_controller.dart';
import 'package:pos_app/state/price_cache.dart';
import 'package:pos_app/state/providers.dart';

/// Wave-2 POS fixes around pricing:
///  * PRD-01 follow-up — the till prices for the basket's CUSTOMER (the server
///    already charges account customers their own price);
///  * POS-07 — the line-discount preview mirrors `InvoiceTotalsCalculator`;
///  * POS-15 — the next sale starts on the walk-in customer again;
///  * POS-18 — dropdown prices expire and are dropped after each sale.
void main() {
  final pcs = Unit(
      id: '1', uid: 'U-PCS', code: 'PCS', name: 'Pieces', symbol: 'pc',
      decimalPlaces: 0, fractional: false);
  final crate = Unit(
      id: '2', uid: 'U-CRT', code: 'CRT', name: 'Crate', symbol: 'crt',
      decimalPlaces: 0, fractional: false);

  Product product(String n) => Product(
      id: n, uid: 'P-$n', code: 'CODE-$n', name: 'Product $n', sellable: true,
      baseUnitUid: pcs.uid, baseUnitCode: pcs.code, baseUnitName: pcs.name,
      cost: const Money(0, 'TZS'), vatStatus: 'STANDARD',
      restrictedKind: RestrictedKind.none, status: 'ACTIVE');

  Customer customer(String uid, {bool walkIn = false}) => Customer(
      id: uid, uid: uid, code: uid, displayName: 'Customer $uid',
      customerKind: walkIn ? 'CASH_WALK_IN' : 'CREDIT_ACCOUNT',
      defaultCurrency: 'TZS', status: 'ACTIVE');

  late _PricingCatalog catalog;
  late ProviderContainer c;

  setUp(() {
    catalog = _PricingCatalog();
    c = ProviderContainer(overrides: [
      catalogServiceProvider.overrideWithValue(catalog),
      appControllerProvider.overrideWith(() => _VatApp(pcs)),
    ]);
  });
  tearDown(() => c.dispose());

  group('BasketPricer — prices for the basket customer (PRD-01)', () {
    test('asks the server with customerUid + currency and shows that price',
        () async {
      final bar = customer('BAR');
      c.read(cartProvider.notifier)
          .start(customer: bar, currency: 'TZS');
      c.read(cartProvider.notifier).addProduct(product('A'), pcs);
      catalog.prices = {'BAR': 1200, '': 1000};

      expect(await c.read(basketPricerProvider).price(), isTrue);

      final call = catalog.calls.single;
      expect(call.customerUid, 'BAR');
      expect(call.currency, 'TZS');
      expect(call.unitUid, isNull, reason: 'base-unit line = own base unit');
      // Exclusive list: 1200 net + 18% VAT.
      expect(c.read(cartProvider).previewSubtotal, 1416);
    });

    test('changing the customer re-prices lines already in the basket',
        () async {
      final walkIn = customer('WALK', walkIn: true);
      c.read(cartProvider.notifier).start(customer: walkIn, currency: 'TZS');
      c.read(cartProvider.notifier).addProduct(product('A'), pcs);
      catalog.prices = {'WALK': 1000, 'BAR': 1500};
      await c.read(basketPricerProvider).price();
      expect(c.read(cartProvider).previewSubtotal, 1180);

      c.read(cartProvider.notifier).setCustomer(customer('BAR'));
      await c.read(basketPricerProvider).price();
      expect(c.read(cartProvider).previewSubtotal, 1770,
          reason: 'the account customer pays their own (higher) price');
    });

    test('pack lines are asked for in their own unit', () async {
      c.read(cartProvider.notifier)
          .start(customer: customer('WALK', walkIn: true), currency: 'TZS');
      c.read(cartProvider.notifier).addProduct(product('A'), pcs);
      c.read(cartProvider.notifier)
          .addProduct(product('B'), crate, unitFactor: 24);
      catalog.prices = {'WALK': 100};
      await c.read(basketPricerProvider).price();
      expect(catalog.calls.map((x) => x.unitUid).toSet(), {null, 'U-CRT'});
    });

    test('a failed re-price blanks the prices instead of keeping stale ones',
        () async {
      c.read(cartProvider.notifier)
          .start(customer: customer('WALK', walkIn: true), currency: 'TZS');
      c.read(cartProvider.notifier).addProduct(product('A'), pcs);
      catalog.prices = {'WALK': 1000};
      await c.read(basketPricerProvider).price();
      expect(c.read(cartProvider).hasUnpricedLine, isFalse);

      catalog.fail = true;
      c.read(cartProvider.notifier).setCustomer(customer('BAR'));
      expect(await c.read(basketPricerProvider).price(), isFalse);
      expect(c.read(cartProvider).hasUnpricedLine, isTrue,
          reason: 'payment must not trust a walk-in total for BAR');
    });

    test('a VAT-inclusive list price is used as the gross', () async {
      c.read(cartProvider.notifier)
          .start(customer: customer('WALK', walkIn: true), currency: 'TZS');
      c.read(cartProvider.notifier).addProduct(product('A'), pcs);
      catalog.prices = {'WALK': 1180};
      catalog.inclusive = true;
      await c.read(basketPricerProvider).price();
      expect(c.read(cartProvider).previewSubtotal, 1180);
    });
  });

  group('POS-07 — discount preview mirrors InvoiceTotalsCalculator', () {
    CartLine line({
      double? net,
      double? gross,
      double qty = 1,
      double disc = 0,
      int dp = 0,
    }) =>
        CartLine(
          localId: 'L',
          product: product('A'),
          unit: pcs,
          quantity: qty,
          unitNetPrice: net,
          unitPricePreview: net != null ? net * 1.18 : gross,
          vatFraction: 0.18,
          lineDiscountAmount: disc,
          minorUnits: dp,
        );

    test('exclusive list: discount comes off the NET, VAT after', () {
      // (10,000 − 1,000) × 1.18 = 10,620 — the old preview said 10,800.
      expect(line(net: 10000, disc: 1000).previewGross, 10620);
    });

    test('exclusive list rounds net and VAT per line (TZS = 0 dp)', () {
      // net = round(3 × 333.33 − 0) = 1000; VAT = round(180) = 180.
      expect(line(net: 333.33, qty: 3).previewGross, 1180);
      // net 999.5 → 1000 (HALF_UP).
      expect(line(net: 999.5).previewGross, 1180);
    });

    test('USD rounds to cents', () {
      // net 12.00, VAT 2.16 — not 2.00 (the old 0-dp bug).
      expect(line(net: 12, dp: 2).previewGross, closeTo(14.16, 1e-9));
    });

    test('inclusive list: discount comes off the gross', () {
      expect(line(gross: 11800, disc: 1000).previewGross, 10800);
    });

    test('discount larger than the line floors at zero', () {
      expect(line(net: 100, disc: 500).previewGross, 0);
    });
  });

  group('POS-15 — next sale starts on the default customer', () {
    test('clearLines resets the customer to the walk-in', () {
      final walkIn = customer('WALK', walkIn: true);
      final cart = c.read(cartProvider.notifier);
      cart.start(customer: walkIn, currency: 'TZS');
      cart.setCustomer(customer('BAR'));
      cart.addProduct(product('A'), pcs);

      cart.clearLines();

      expect(c.read(cartProvider).customer?.uid, 'WALK');
      expect(c.read(cartProvider).isEmpty, isTrue);
    });

    test('with no walk-in the basket has no customer (Select customer)', () {
      final cart = c.read(cartProvider.notifier);
      cart.start(customer: null, currency: 'TZS');
      cart.setCustomer(customer('BAR'));
      cart.clearLines();
      expect(c.read(cartProvider).customer, isNull);
    });
  });

  group('POS-18 — dropdown price cache', () {
    test('expires after its TTL and is dropped by clear()', () async {
      var now = DateTime(2026, 10, 10, 9);
      final cache = PriceCache(catalog, clock: () => now);
      catalog.prices = {'': 1000};

      await cache.refreshFor(['P-A']);
      await cache.refreshFor(['P-A']);
      expect(catalog.calls, hasLength(1), reason: 'fresh answer reused');

      now = now.add(PriceCache.ttl + const Duration(seconds: 1));
      await cache.refreshFor(['P-A']);
      expect(catalog.calls, hasLength(2), reason: 'stale answer re-asked');

      cache.clear();
      expect(cache.priceFor('P-A'), isNull);
    });

    test('a different customer starts a fresh cache', () async {
      final cache = PriceCache(catalog);
      catalog.prices = {'WALK': 1000, 'BAR': 1500};
      await cache.refreshFor(['P-A'], customerUid: 'WALK', currency: 'TZS');
      expect(cache.priceFor('P-A')?.amount, 1000);
      await cache.refreshFor(['P-A'], customerUid: 'BAR', currency: 'TZS');
      expect(cache.priceFor('P-A')?.amount, 1500);
      expect(catalog.calls.last.customerUid, 'BAR');
    });
  });
}

class _VatApp extends AppController {
  _VatApp(this.pcs);
  final Unit pcs;

  @override
  AppData build() => AppData(
        unitsByUid: {pcs.uid: pcs},
        vatRates: const {'STANDARD': 0.18},
      );
}

class _Call {
  _Call(this.productUids, this.unitUid, this.customerUid, this.currency);
  final List<String> productUids;
  final String? unitUid;
  final String? customerUid;
  final String? currency;
}

/// Answers `/product-prices/resolve` from a per-customer price table
/// (`''` = asked without a customer).
class _PricingCatalog implements CatalogService {
  Map<String, double> prices = {};
  bool inclusive = false;
  bool fail = false;
  final calls = <_Call>[];

  @override
  Future<List<ResolvedUnitPrice>> resolvePrices(List<String> productUids,
      {String? unitUid, String? customerUid, String? currency}) async {
    calls.add(_Call(productUids, unitUid, customerUid, currency));
    if (fail) throw Exception('offline');
    final amount = prices[customerUid ?? ''];
    return [
      for (final uid in productUids)
        ResolvedUnitPrice(
          productUid: uid,
          unitUid: unitUid ?? 'U-PCS',
          status: amount == null
              ? UnitPriceStatus.noPrice
              : UnitPriceStatus.fromWire('RESOLVED'),
          amount: amount,
          currency: 'TZS',
          vatInclusive: inclusive,
        ),
    ];
  }

  @override
  dynamic noSuchMethod(Invocation invocation) =>
      throw UnimplementedError(invocation.memberName.toString());
}
