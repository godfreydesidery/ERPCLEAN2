import 'dart:io';

import 'package:flutter_test/flutter_test.dart';
import 'package:pos_app/core/api/api_client.dart';
import 'package:pos_app/core/api/token_manager.dart';
import 'package:pos_app/core/storage/secure_store.dart';
import 'package:pos_app/models/catalog.dart';
import 'package:pos_app/models/enums.dart';
import 'package:pos_app/services/auth_service.dart';
import 'package:pos_app/services/catalog_service.dart';
import 'package:pos_app/services/context_service.dart';
import 'package:pos_app/services/party_service.dart';
import 'package:pos_app/services/sale_service.dart';
import 'package:pos_app/services/session_service.dart';
import 'package:pos_app/services/till_service.dart';
import 'package:shared_preferences/shared_preferences.dart';

/// End-to-end smoke test of the POS Dart layer (services + models + API client)
/// against a LIVE ERP backend. Skipped unless `POS_LIVE_HOST` is set, e.g.:
///
///   POS_LIVE_HOST=http://localhost:8081 flutter test test/live_pos_smoke_test.dart
///
/// Assumes the backend has POS prerequisites seeded (e.g. via
/// `e2e/full-coverage-drive.js`): a till, priced products, a walk-in customer,
/// and a sales agent. Logs in as the bootstrap root (rootadmin/RootPass12345).
void main() {
  final host = Platform.environment['POS_LIVE_HOST'];
  final user = Platform.environment['POS_LIVE_USER'] ?? 'rootadmin';
  final pass = Platform.environment['POS_LIVE_PASS'] ?? 'RootPass12345';

  if (host == null || host.isEmpty) {
    test('live POS smoke (skipped — set POS_LIVE_HOST to run)', () {}, skip: true);
    return;
  }

  final base = '${host.endsWith('/') ? host.substring(0, host.length - 1) : host}/api/v1';

  late ApiClient api;
  late AuthService auth;
  late ContextService ctxSvc;
  late CatalogService catalog;
  late PartyService party;
  late TillService tills;
  late SessionService sessions;
  late SaleService sales;

  setUpAll(() {
    TestWidgetsFlutterBinding.ensureInitialized();
    // flutter_test installs an HttpOverrides that 400s every request; clear it
    // so this live test makes real network calls.
    HttpOverrides.global = null;
    SharedPreferences.setMockInitialValues({});
    final store = SecureStore();
    final tokens = TokenManager(apiBase: base, store: store);
    api = ApiClient(apiBase: base, tokens: tokens);
    auth = AuthService(api, tokens);
    ctxSvc = ContextService(api);
    catalog = CatalogService(api);
    party = PartyService(api);
    tills = TillService(api);
    sessions = SessionService(api);
    sales = SaleService(api);
  });

  test('login → context → open shift → ring (idempotent) → receipt → close → reconcile',
      () async {
    // 1) Auth + numeric context resolution
    final bundle = await auth.login(user, pass);
    expect(bundle.accessToken, isNotEmpty);
    final me = await auth.me();
    final context = await ctxSvc.resolve(me);
    api.branchUidOverride = context.branchUid;
    final currency = context.company.baseCurrency ?? 'TZS';

    // 2) Reference data: a priced product + its unit, walk-in customer, agent
    final units = await catalog.listUnits(context.companyId);
    final unitsByUid = {for (final u in units) u.uid: u};
    final products = await catalog.searchProducts(context.companyId, size: 100);
    expect(products, isNotEmpty, reason: 'seed products first (full-coverage-drive.js)');

    Product? chosen;
    for (final p in products) {
      final prices = await catalog.listPrices(p.uid);
      if (prices.isNotEmpty && unitsByUid[p.baseUnitUid] != null) {
        chosen = p;
        break;
      }
    }
    expect(chosen, isNotNull, reason: 'need a priced product with a known unit');
    final unit = unitsByUid[chosen!.baseUnitUid]!;

    final walkIn = await party.findWalkIn(context.companyId);
    expect(walkIn, isNotNull, reason: 'need a walk-in customer');
    final agents = await party.listAgents(context.companyId);
    expect(agents, isNotEmpty, reason: 'need a sales agent');

    // 3) A till
    final tillList = await tills.listByBranch(context.companyId, context.branchId);
    final active = tillList.where((t) => t.isActive).toList();
    expect(active, isNotEmpty, reason: 'need an active till');
    final till = active.first;

    // 4) Open a session
    final session = await sessions.open(till.uid, 100000);
    expect(session.status, PosSessionStatus.open);

    // 5) Ring a sale with a durable idempotency key
    final body = {
      'sessionUid': session.uid,
      'customerId': walkIn!.id,
      'agentId': agents.first.id,
      'currency': currency,
      'lines': [
        {'productId': chosen.id, 'unitId': unit.id, 'quantity': 1}
      ],
      'tenderedAmount': 100000,
      'ageVerified': true,
    };
    final key = ApiClient.newTxnId();
    final invoice = await sales.ring(body, idempotencyKey: key, xRequestId: key);
    expect(invoice.status, InvoiceStatus.finalised);
    expect(invoice.grossTotalAmount, greaterThan(0));

    // 6) Idempotent replay — same key returns the SAME invoice, no double-post
    final replay = await sales.ring(body, idempotencyKey: key, xRequestId: key);
    expect(replay.uid, invoice.uid, reason: 'idempotency-key must not double-post');

    // 7) Receipt assembles from the finalised invoice
    final receipt = await sales.loadReceipt(invoice.uid, clientTxnId: key);
    expect(receipt.lines, isNotEmpty);
    expect(receipt.invoice.invoiceNumber, isNotEmpty);

    // 8) X-read reflects the sale, then close + reconcile to leave it clean
    final x = await sessions.xRead(session.uid);
    expect(x.invoiceCount, greaterThanOrEqualTo(1));
    final closed = await sessions.close(
        session.uid, (x.expectedCashAmount));
    expect(closed.status, PosSessionStatus.closed);
    final z = await sessions.reconcile(session.uid);
    expect(z.sessionUid, session.uid);

    // ignore: avoid_print
    print('LIVE SMOKE OK: invoice ${invoice.invoiceNumber} '
        'gross ${invoice.grossTotalAmount} ${invoice.currency}; '
        'idempotent replay matched; session reconciled.');
  }, timeout: const Timeout(Duration(minutes: 2)));
}
