import 'package:flutter_test/flutter_test.dart';
import 'package:pos_app/core/money.dart';
import 'package:pos_app/models/catalog.dart';
import 'package:pos_app/models/enums.dart';
import 'package:pos_app/models/parties.dart';
import 'package:pos_app/state/cart_controller.dart';
import 'package:pos_app/state/parked_sale_store.dart';
import 'package:shared_preferences/shared_preferences.dart';

/// POS-06: hold a basket and recall it later, kept on the till only.
void main() {
  final pcs = Unit(
      id: '1', uid: 'U-PCS', code: 'PCS', name: 'Pieces', symbol: 'pc',
      decimalPlaces: 0, fractional: false);
  final soda = Product(
      id: '7', uid: 'P-SODA', code: 'SODA', name: 'Soda 500ml', sellable: true,
      baseUnitUid: pcs.uid, baseUnitCode: pcs.code, baseUnitName: pcs.name,
      cost: const Money(400, 'TZS'), vatStatus: 'STANDARD',
      restrictedKind: RestrictedKind.none, status: 'ACTIVE');
  final bar = Customer(
      id: '9', uid: 'C-BAR', code: 'CUST-9', displayName: 'Mama Bar',
      customerKind: 'CREDIT_ACCOUNT', defaultCurrency: 'TZS', status: 'ACTIVE');

  CartState basket() => CartState(
        customer: bar,
        currency: 'TZS',
        lines: [
          CartLine(
              localId: 'a', product: soda, unit: pcs, quantity: 3,
              unitPricePreview: 1000, lineDiscountAmount: 200,
              discountAuthorisedByUid: 'MGR', discountAuthorisedByName: 'Juma'),
          CartLine(
              localId: 'b', product: soda, unit: pcs, quantity: 9,
              voided: true),
        ],
      );

  setUp(() => SharedPreferences.setMockInitialValues({}));

  test('a parked basket comes back with its lines, customer and discount',
      () async {
    final store = ParkedSaleStore();
    final now = DateTime(2026, 10, 10, 9);
    expect(
        await store.park(ParkedSale.fromCart(basket(),
            id: 'H1', sessionUid: 'S1', now: now)),
        isTrue);

    final back = await store.list('S1', now: now);
    expect(back, hasLength(1));
    final p = back.single;
    expect(p.customer?.uid, 'C-BAR');
    expect(p.lines, hasLength(1), reason: 'a voided line is not parked');
    expect(p.lines.single.product.uid, 'P-SODA');
    expect(p.lines.single.product.cost.amount, 400);
    expect(p.lines.single.quantity, 3);
    expect(p.lines.single.lineDiscountAmount, 200);
    expect(p.lines.single.toJson().toString(), isNot(contains('MGR')),
        reason: 'a manager approval is never stored with a parked basket');
    expect(p.label, 'Soda 500ml');
  });

  test('only this shift sees its baskets, and Recall removes it', () async {
    final store = ParkedSaleStore();
    final now = DateTime(2026, 10, 10, 9);
    await store.park(
        ParkedSale.fromCart(basket(), id: 'H1', sessionUid: 'S1', now: now));
    await store.park(
        ParkedSale.fromCart(basket(), id: 'H2', sessionUid: 'S2', now: now));

    expect((await store.list('S1', now: now)).map((s) => s.id), ['H1']);
    await store.remove('H1');
    expect(await store.list('S1', now: now), isEmpty);
    expect((await store.list('S2', now: now)).map((s) => s.id), ['H2']);
  });

  test('a basket older than a day is dropped', () async {
    final store = ParkedSaleStore();
    final then = DateTime(2026, 10, 9, 8);
    await store.park(
        ParkedSale.fromCart(basket(), id: 'OLD', sessionUid: 'S1', now: then));
    expect(
        await store.list('S1',
            now: then.add(ParkedSaleStore.maxAge + const Duration(minutes: 1))),
        isEmpty);
  });
}
