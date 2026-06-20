import 'dart:io';

import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:pos_app/core/api/api_client.dart';
import 'package:pos_app/core/api/api_exception.dart';
import 'package:pos_app/core/api/token_manager.dart';
import 'package:pos_app/core/storage/secure_store.dart';
import 'package:pos_app/models/catalog.dart';
import 'package:pos_app/models/enums.dart';
import 'package:pos_app/models/parties.dart';
import 'package:pos_app/models/pos.dart';
import 'package:pos_app/models/sale.dart';
import 'package:pos_app/services/auth_service.dart';
import 'package:pos_app/services/catalog_service.dart';
import 'package:pos_app/services/context_service.dart';
import 'package:pos_app/services/party_service.dart';
import 'package:pos_app/services/sale_service.dart';
import 'package:pos_app/services/session_service.dart';
import 'package:pos_app/services/till_service.dart';
import 'package:pos_app/state/cart_controller.dart';
import 'package:shared_preferences/shared_preferences.dart';

/// Comprehensive end-to-end test of the OrbixPOS client (services + the real
/// CartController request-builder) against a LIVE, seeded ERP backend. Skipped
/// unless POS_LIVE_HOST is set:
///
///   POS_LIVE_HOST=http://localhost:8081 POS_LIVE_USER=pos_cashier POS_LIVE_PASS=Cashier12345 \
///     flutter test test/live_pos_e2e_test.dart
///
/// It MUTATES the backend (creates products/tills/sessions/sales). Run against a
/// throwaway/dev database. Each session-using test creates its own till so the
/// one-open-session-per-till rule never causes cross-test interference.
void main() {
  final host = Platform.environment['POS_LIVE_HOST'];
  final user = Platform.environment['POS_LIVE_USER'] ?? 'pos_cashier';
  final pass = Platform.environment['POS_LIVE_PASS'] ?? 'Cashier12345';
  if (host == null || host.isEmpty) {
    test('live POS e2e (skipped — set POS_LIVE_HOST to run)', () {}, skip: true);
    return;
  }
  final base =
      '${host.endsWith('/') ? host.substring(0, host.length - 1) : host}/api/v1';

  late ApiClient api;
  late AuthService auth;
  late CatalogService catalog;
  late PartyService party;
  late TillService tills;
  late SessionService sessions;
  late SaleService sales;

  late String companyId, companyUid, branchId, currency;
  late Customer walkIn;
  late Agent agent;
  late Map<String, Unit> unitsByUid;
  late List<({Product product, Unit unit, double price})> priced;
  late String unpricedProductId;

  var tillSeq = 0;
  late String runTag; // unique per run so till names never collide on reruns
  Future<PosTill> freshTill() async =>
      tills.create(companyUid, branchId, 'E2E $runTag-${++tillSeq}');

  String newKey() => ApiClient.newTxnId();

  /// Build a sale body through the REAL CartController (exercises buildRequest).
  Map<String, dynamic> cartBody(
    String sessionUid,
    List<({Product p, Unit u, double qty, double disc})> lines, {
    List<PosTender>? tenders,
    double? tenderedAmount,
    bool ageVerified = false,
  }) {
    final c = ProviderContainer();
    addTearDown(c.dispose);
    final cart = c.read(cartProvider.notifier);
    cart.start(customer: walkIn, agent: agent, currency: currency);
    for (final l in lines) {
      cart.addProduct(l.p, l.u, quantity: l.qty);
      if (l.disc > 0) {
        cart.setDiscount(c.read(cartProvider).selectedId!, l.disc);
      }
    }
    return c.read(cartProvider).buildRequest(sessionUid,
        tenders: tenders, tenderedAmount: tenderedAmount, ageVerified: ageVerified);
  }

  setUpAll(() async {
    TestWidgetsFlutterBinding.ensureInitialized();
    HttpOverrides.global = null;
    SharedPreferences.setMockInitialValues({});
    runTag = DateTime.now().millisecondsSinceEpoch.toString();
    final tm = TokenManager(apiBase: base, store: SecureStore());
    api = ApiClient(apiBase: base, tokens: tm);
    auth = AuthService(api, tm);
    catalog = CatalogService(api);
    party = PartyService(api);
    tills = TillService(api);
    sessions = SessionService(api);
    sales = SaleService(api);

    await auth.login(user, pass);
    final me = await auth.me();
    final ctx = await ContextService(api).resolve(me);
    api.branchUidOverride = ctx.branchUid;
    companyId = ctx.companyId;
    companyUid = ctx.companyUid;
    branchId = ctx.branchId;
    currency = ctx.company.baseCurrency ?? 'TZS';

    unitsByUid = {for (final u in await catalog.listUnits(companyId)) u.uid: u};
    walkIn = (await party.findWalkIn(companyId))!;
    agent = (await party.listAgents(companyId)).first;

    // find two priced products with a known unit
    priced = [];
    for (final p in await catalog.searchProducts(companyId, size: 120)) {
      final u = unitsByUid[p.baseUnitUid];
      if (u == null) continue;
      final prices = await catalog.listPrices(p.uid);
      final match = prices.where((x) => x.price.currency == currency).toList();
      if (match.isNotEmpty) {
        priced.add((product: p, unit: u, price: match.first.price.amount));
      }
      if (priced.length >= 2) break;
    }

    // a deliberately UN-priced product (to prove a clear pricing error)
    final created = await api.post('/products', body: {
      'companyUid': companyUid,
      'name': 'E2E No-Price Product',
      'type': 'GOODS',
      'sellable': true,
      'stockable': true,
      'baseUnitUid': priced.first.unit.uid,
      'vatStatus': 'STANDARD',
    });
    unpricedProductId = (created['id']).toString();
  });

  test('context resolves to numeric company + branch ids', () {
    expect(companyId, isNotEmpty);
    expect(branchId, isNotEmpty);
    expect(int.tryParse(companyId), isNotNull);
    expect(int.tryParse(branchId), isNotNull);
  });

  test('reference data present: walk-in customer + agent + two priced products', () {
    expect(walkIn.isWalkIn, isTrue);
    expect(agent.id, isNotEmpty);
    expect(priced.length, greaterThanOrEqualTo(2),
        reason: 'seed priced products first (full-coverage-drive.js)');
  });

  test('multi-line cash sale finalises with the right line count', () async {
    final till = await freshTill();
    final s = await sessions.open(till.uid, 50000);
    final body = cartBody(s.uid, [
      (p: priced[0].product, u: priced[0].unit, qty: 2, disc: 0),
      (p: priced[1].product, u: priced[1].unit, qty: 1, disc: 0),
    ], tenderedAmount: 1000000, ageVerified: true);
    final key = newKey();
    final inv = await sales.ring(body, idempotencyKey: key, xRequestId: key);
    expect(inv.status, InvoiceStatus.finalised);
    final receipt = await sales.loadReceipt(inv.uid, clientTxnId: key);
    expect(receipt.lines.length, 2);
    expect(inv.grossTotalAmount, greaterThan(0));
  });

  test('line discount is recorded on the posted invoice line', () async {
    final till = await freshTill();
    final s = await sessions.open(till.uid, 0);
    const disc = 100.0;
    final body = cartBody(s.uid, [
      (p: priced[0].product, u: priced[0].unit, qty: 2, disc: disc),
    ], tenderedAmount: 1000000, ageVerified: true);
    final inv = await sales.ring(body, idempotencyKey: newKey(), xRequestId: newKey());
    final receipt = await sales.loadReceipt(inv.uid, clientTxnId: 'd');
    expect(receipt.lines.single.lineDiscountAmount, closeTo(disc, 0.001));
  });

  test('split multi-tender (cash + card) finalises with two payments', () async {
    final till = await freshTill();
    final s = await sessions.open(till.uid, 0);
    // price the line first to size the tenders
    // Card takes a small exact partial; cash covers the rest and may over-tender
    // (only cash accepts change). Sized generously because the authoritative
    // VAT-inclusive gross exceeds the net price-list preview (see the VAT test).
    final tenders = [
      PosTender(tenderType: TenderType.card, amount: 100),
      PosTender(tenderType: TenderType.cash, amount: priced[0].price * 2),
    ];
    final body = cartBody(s.uid, [
      (p: priced[0].product, u: priced[0].unit, qty: 1, disc: 0),
    ], tenders: tenders, ageVerified: true);
    final inv = await sales.ring(body, idempotencyKey: newKey(), xRequestId: newKey());
    expect(inv.status, InvoiceStatus.finalised);
    final receipt = await sales.loadReceipt(inv.uid, clientTxnId: 't');
    expect(receipt.payments.length, greaterThanOrEqualTo(2),
        reason: 'both tenders should post as invoice payments');
    expect(receipt.payments.any((p) => p.tenderType == TenderType.card), isTrue,
        reason: 'the card tender must be booked under CARD, not folded into cash');
  });

  test('cash over-tender produces change', () async {
    final till = await freshTill();
    final s = await sessions.open(till.uid, 0);
    final body = cartBody(s.uid, [
      (p: priced[0].product, u: priced[0].unit, qty: 1, disc: 0),
    ], tenderedAmount: priced[0].price * 2, ageVerified: true);
    final inv = await sales.ring(body, idempotencyKey: newKey(), xRequestId: newKey());
    final receipt = await sales.loadReceipt(inv.uid,
        clientTxnId: 'c', tenderedAmount: priced[0].price * 2);
    expect(receipt.changeDue, greaterThan(0));
  });

  test('idempotency: same key returns the original; a fresh key creates a new sale',
      () async {
    final till = await freshTill();
    final s = await sessions.open(till.uid, 0);
    final body = cartBody(s.uid, [
      (p: priced[0].product, u: priced[0].unit, qty: 1, disc: 0),
    ], tenderedAmount: 1000000, ageVerified: true);
    final key = newKey();
    final a = await sales.ring(body, idempotencyKey: key, xRequestId: key);
    final replay = await sales.ring(body, idempotencyKey: key, xRequestId: key);
    expect(replay.uid, a.uid);
    final fresh = await sales.ring(body, idempotencyKey: newKey(), xRequestId: newKey());
    expect(fresh.uid, isNot(a.uid));
  });

  test('whole-sale reverse voids it; a second reverse is rejected', () async {
    final till = await freshTill();
    final s = await sessions.open(till.uid, 0);
    final body = cartBody(s.uid, [
      (p: priced[0].product, u: priced[0].unit, qty: 1, disc: 0),
    ], tenderedAmount: 1000000, ageVerified: true);
    final inv = await sales.ring(body, idempotencyKey: newKey(), xRequestId: newKey());
    await sales.reverse(inv.uid, 'e2e reversal');
    final after = await sales.getInvoice(inv.uid);
    expect(after.status, InvoiceStatus.voided);
    expect(() => sales.reverse(inv.uid, 'again'),
        throwsA(isA<ApiException>()));
  });

  test('selling an un-priced product is rejected with a clear error', () async {
    final till = await freshTill();
    final s = await sessions.open(till.uid, 0);
    final body = {
      'sessionUid': s.uid,
      'customerId': walkIn.id,
      'agentId': agent.id,
      'currency': currency,
      'lines': [
        {'productId': unpricedProductId, 'unitId': priced.first.unit.id, 'quantity': 1}
      ],
      'tenderedAmount': 1000000,
      'ageVerified': true,
    };
    ApiException? caught;
    try {
      await sales.ring(body, idempotencyKey: newKey(), xRequestId: newKey());
    } on ApiException catch (e) {
      caught = e;
    }
    expect(caught, isNotNull, reason: 'a product with no price must be refused');
    expect(caught!.isValidation, isTrue,
        reason: 'expected a 400/422, got ${caught.statusCode}: ${caught.message}');
  });

  test('a sale on a CLOSED session is rejected', () async {
    final till = await freshTill();
    final s = await sessions.open(till.uid, 0);
    final x = await sessions.xRead(s.uid);
    await sessions.close(s.uid, x.expectedCashAmount);
    final body = cartBody(s.uid, [
      (p: priced[0].product, u: priced[0].unit, qty: 1, disc: 0),
    ], tenderedAmount: 1000000, ageVerified: true);
    expect(
        () => sales.ring(body, idempotencyKey: newKey(), xRequestId: newKey()),
        throwsA(isA<ApiException>()));
  });

  test('session reads: x-read totals, payout reduces expected, close variance, reconcile',
      () async {
    final till = await freshTill();
    const float = 20000.0;
    final s = await sessions.open(till.uid, float);
    for (var i = 0; i < 2; i++) {
      final body = cartBody(s.uid, [
        (p: priced[0].product, u: priced[0].unit, qty: 1, disc: 0),
      ], tenderedAmount: 1000000, ageVerified: true);
      await sales.ring(body, idempotencyKey: newKey(), xRequestId: newKey());
    }
    final x1 = await sessions.xRead(s.uid);
    expect(x1.invoiceCount, greaterThanOrEqualTo(2));
    expect(x1.totalSalesAmount, greaterThan(0));

    await sessions.payout(s.uid, PosPayoutType.paidOut, 1000, 'e2e drop');
    final x2 = await sessions.xRead(s.uid);
    expect(x2.expectedCashAmount, closeTo(x1.expectedCashAmount - 1000, 0.001),
        reason: 'a payout must reduce expected cash by its amount');

    final counted = x2.expectedCashAmount; // count exactly => zero variance
    final closed = await sessions.close(s.uid, counted);
    expect(closed.status, PosSessionStatus.closed);
    expect(closed.varianceAmount ?? 0, closeTo(0, 0.001));

    final z = await sessions.reconcile(s.uid);
    expect(z.sessionUid, s.uid);
    expect(z.invoiceCount, greaterThanOrEqualTo(2));
  });

  test('a not-found invoice surfaces as a 404 ApiException', () async {
    ApiException? caught;
    try {
      await sales.getInvoice('01ZZZZZZZZZZZZZZZZZZZZZZZZZ');
    } on ApiException catch (e) {
      caught = e;
    }
    expect(caught, isNotNull);
    expect([400, 403, 404].contains(caught!.statusCode), isTrue,
        reason: 'a bogus invoice uid should be a 4xx (the scoped-permission '
            'check returns 403 for unknown uids), got ${caught.statusCode}');
  });

  test('authoritative gross >= net price-list preview (VAT discipline)', () async {
    // The on-screen preview uses the price-list (net) unit price; the server adds
    // VAT, so the finalised gross can exceed the preview. Guards/documents that
    // gap: cash settles fine; exact multi-tender must be sized to the gross.
    final till = await freshTill();
    final s = await sessions.open(till.uid, 0);
    final c = ProviderContainer();
    addTearDown(c.dispose);
    final cart = c.read(cartProvider.notifier);
    cart.start(customer: walkIn, agent: agent, currency: currency);
    cart.addProduct(priced[0].product, priced[0].unit, quantity: 1);
    cart.setLinePrice(c.read(cartProvider).selectedId!, priced[0].price);
    final preview = c.read(cartProvider).previewSubtotal;
    final body = c.read(cartProvider).buildRequest(s.uid,
        tenderedAmount: priced[0].price * 2, ageVerified: true);
    final inv = await sales.ring(body, idempotencyKey: newKey(), xRequestId: newKey());
    expect(inv.grossTotalAmount, greaterThanOrEqualTo(preview - 0.001),
        reason: 'server gross is authoritative and VAT-inclusive; the net '
            'preview must never exceed it');
    // ignore: avoid_print
    print('PREVIEW=$preview  GROSS=${inv.grossTotalAmount}  '
        'ratio=${(inv.grossTotalAmount / (preview == 0 ? 1 : preview)).toStringAsFixed(3)}');
  });
}
