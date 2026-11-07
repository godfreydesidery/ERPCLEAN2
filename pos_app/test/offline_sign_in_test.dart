import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:pos_app/features/auth/login_screen.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:pos_app/core/api/api_client.dart';
import 'package:pos_app/core/api/api_exception.dart';
import 'package:pos_app/core/api/token_manager.dart';
import 'package:pos_app/core/storage/secure_store.dart';
import 'package:pos_app/models/auth.dart';
import 'package:pos_app/models/catalog.dart';
import 'package:pos_app/models/context.dart';
import 'package:pos_app/models/parties.dart';
import 'package:pos_app/models/pos.dart';
import 'package:pos_app/offline/blob_store.dart';
import 'package:pos_app/offline/offline_catalog.dart';
import 'package:pos_app/offline/offline_controller.dart';
import 'package:pos_app/offline/offline_models.dart';
import 'package:pos_app/offline/offline_providers.dart';
import 'package:pos_app/offline/offline_queue.dart';
import 'package:pos_app/offline/offline_sign_in.dart';
import 'package:pos_app/offline/pbkdf2.dart';
import 'package:pos_app/services/auth_service.dart';
import 'package:pos_app/services/catalog_service.dart';
import 'package:pos_app/services/context_service.dart';
import 'package:pos_app/services/party_service.dart';
import 'package:pos_app/services/session_service.dart';
import 'package:pos_app/services/till_service.dart';
import 'package:pos_app/state/app_controller.dart';
import 'package:pos_app/state/cart_controller.dart';
import 'package:pos_app/state/providers.dart';
import 'package:shared_preferences/shared_preferences.dart';

import 'support/offline_fixtures.dart';

/// Offline sign-in (ERP ADR-0068 D-7, moved into phase 1 by the owner on
/// 2026-11-07): a verifier derived on the till at each online sign-in, sealed
/// with the device key store; password or PIN offline; 5 wrong tries lock the
/// user out for 15 minutes; 14 days of life; gone when the server refuses the
/// user; never when the server answers; never on a till not allowed offline.

const _asha = 'USER-ASHA-0000000000000001';

String _jwt(String sub) {
  String seg(Map<String, dynamic> m) => base64Url.encode(utf8.encode(jsonEncode(m))).replaceAll('=', '');
  return '${seg({'alg': 'none'})}.${seg({'sub': sub})}.x';
}

PosContext _ctx() => PosContext(
      organisationUid: 'ORG1',
      company: Company(id: '10', uid: 'CO1', name: 'Tulia', baseCurrency: 'TZS'),
      branch: Branch(
          id: '20',
          uid: 'BR1',
          companyId: '10',
          companyUid: 'CO1',
          code: 'MAIN',
          name: 'Main',
          isDefault: true,
          status: 'ACTIVE'),
    );

class FakeAuth implements AuthService {
  FakeAuth(this.tokens, this._serverDown);
  final TokenManager tokens;
  final bool Function() _serverDown;
  bool refuse = false;
  bool down = false;

  Future<TokenBundle> _session() async {
    if (down || _serverDown()) {
      throw ApiException(statusCode: null, isNetwork: true, message: 'down');
    }
    if (refuse) throw ApiException(statusCode: 401, message: 'Invalid username or password.');
    final b = TokenBundle(
      accessToken: _jwt('U1'),
      accessTokenExpiresAt: DateTime.now().toUtc().add(const Duration(minutes: 15)),
      refreshToken: 'r',
      user: AuthUser(
          uid: _asha,
          username: 'asha',
          displayName: 'Asha',
          isRoot: false,
          activeCompanyUid: 'CO1',
          activeBranchUid: 'BR1',
          hasBranch: true),
    );
    await tokens.setSession(b);
    return b;
  }

  @override
  Future<TokenBundle> login(String username, String password) => _session();
  @override
  Future<TokenBundle> pinLogin(String username, String pin) => _session();
  @override
  Future<Me> me() async {
    if (_serverDown()) throw ApiException(statusCode: null, isNetwork: true, message: 'down');
    return _me;
  }

  Me get _me => Me(
        uid: _asha,
        username: 'asha',
        displayName: 'Asha',
        isRoot: false,
        activeCompanyUid: 'CO1',
        activeBranchUid: 'BR1',
        permissions: const {'POS.SALE.CREATE', 'POS.SESSION.OPEN', 'POS.SESSION.CLOSE', 'SALES.INVOICE.VIEW'},
      );
  @override
  Future<void> logout() async => tokens.clear();
  @override
  dynamic noSuchMethod(Invocation invocation) => super.noSuchMethod(invocation);
}

class _Context implements ContextService {
  @override
  Future<PosContext> resolve(Me me) async => _ctx();
  @override
  dynamic noSuchMethod(Invocation invocation) => super.noSuchMethod(invocation);
}

class _Parties implements PartyService {
  @override
  Future<Customer?> findWalkIn(String companyId) async => walkIn;
  @override
  dynamic noSuchMethod(Invocation invocation) => super.noSuchMethod(invocation);
}

class _Catalog implements CatalogService {
  @override
  Future<List<Unit>> listUnits(String companyId, {int size = 200}) async => [piece];
  @override
  Future<Map<String, double>> taxRatesByStatus(String companyId) async => const {'STANDARD': 0.18};
  @override
  dynamic noSuchMethod(Invocation invocation) => super.noSuchMethod(invocation);
}

class _Sessions implements SessionService {
  @override
  Future<List<PosSession>> list(String companyId, {String? status, int page = 0, int size = 50}) async =>
      const [];
  @override
  Future<PosSession> getByUid(String uid) async => shift(uid);
  @override
  dynamic noSuchMethod(Invocation invocation) => super.noSuchMethod(invocation);
}

class _Tills implements TillService {
  @override
  Future<List<PosTill>> listByBranch(String companyId, String branchId) async => const [];
  @override
  dynamic noSuchMethod(Invocation invocation) => super.noSuchMethod(invocation);
}

/// The ERP's API as far as these tests reach it: the offline sync (answered
/// like the server), and nothing else (the catalogue refresh finds no server).
class FakeApi extends ApiClient {
  FakeApi(TokenManager tokens) : super(apiBase: 'http://erp.test/api/v1', tokens: tokens);
  final syncs = <Map<String, dynamic>>[];

  @override
  Future<dynamic> post(String path,
      {Object? body,
      Map<String, dynamic>? query,
      String? idempotencyKey,
      String? xRequestId,
      bool noAuth = false}) async {
    final b = (jsonDecode(jsonEncode(body)) as Map).cast<String, dynamic>();
    syncs.add(b);
    return {
      'sessions': [
        for (final s in b['sessions'] as List)
          {'clientKey': s['clientKey'], 'sessionUid': 'SRV-9', 'sessionNumber': 'PS-77'},
      ],
      'sales': [
        for (final s in b['sales'] as List)
          {
            'offlineNumber': s['offlineNumber'],
            'idempotencyKey': s['idempotencyKey'],
            'status': 'POSTED',
            'invoiceNumber': 'SI-1',
          },
      ],
      'periodsRecorded': (b['periods'] as List).length,
    };
  }

  @override
  Future<dynamic> get(String path, {Map<String, dynamic>? query}) async =>
      throw ApiException(statusCode: null, isNetwork: true, message: 'down');
}

/// The till's disks — they outlive any one "app launch" ([Till.launch]).
class Disks {
  final queue = MemoryBlobStore();
  final users = MemoryBlobStore();
  final catalog = MemoryBlobStore();
}

/// One launch of the app over [disks].
class Till {
  Till(this.disks, {this.reachable = false, bool tillAllowed = true, PosTill? cachedTill}) {
    tokens = TokenManager(apiBase: 'http://erp.test/api/v1', store: SecureStore());
    auth = FakeAuth(tokens, () => !reachable);
    api = FakeApi(tokens);
    queue = OfflineQueue(disks.queue, TestCipher());
    users = OfflineSignInStore(disks.users, TestCipher(), iterations: 1000);
    catalog = OfflineCatalog(disks.catalog);
    container = ProviderContainer(overrides: [
      tokenManagerProvider.overrideWithValue(tokens),
      apiClientProvider.overrideWithValue(api),
      authServiceProvider.overrideWithValue(auth),
      contextServiceProvider.overrideWithValue(_Context()),
      partyServiceProvider.overrideWithValue(_Parties()),
      catalogServiceProvider.overrideWithValue(_Catalog()),
      sessionServiceProvider.overrideWithValue(_Sessions()),
      tillServiceProvider.overrideWithValue(_Tills()),
      offlineQueueProvider.overrideWithValue(queue),
      offlineSignInStoreProvider.overrideWithValue(users),
      offlineCatalogProvider.overrideWithValue(catalog),
      reachabilityProbeProvider
          .overrideWithValue(() async => reachable ? const Duration(milliseconds: 30) : null),
    ]);
    _seedTill = cachedTill ?? till(allowed: tillAllowed).withOccupancy();
  }

  final Disks disks;
  bool reachable;
  late final TokenManager tokens;
  late final FakeAuth auth;
  late final FakeApi api;
  late final OfflineQueue queue;
  late final OfflineSignInStore users;
  late final OfflineCatalog catalog;
  late final ProviderContainer container;
  late final PosTill _seedTill;

  AppController get app => container.read(appControllerProvider.notifier);
  AppData get appState => container.read(appControllerProvider);
  OfflineController get offline => container.read(offlineControllerProvider.notifier);
  OfflineState get offlineState => container.read(offlineControllerProvider);

  /// What an earlier online session left in the offline cache.
  Future<void> seedCatalog() async {
    await catalog.load();
    if (catalog.snapshot.isEmpty) {
      await catalog.save(CatalogSnapshot(
        companyId: '10',
        branchUid: 'BR1',
        currency: 'TZS',
        products: {'P1': sugar},
        prices: {CatalogSnapshot.priceKey('P1', null): const CachedPrice(1000, false)},
        vatRates: const {'STANDARD': 0.18},
        units: {'U-PCS': piece},
        walkIn: walkIn,
        till: _seedTill,
        pricesAsOf: DateTime.utc(2026, 11, 7, 7, 40),
      ));
    }
  }

  void dispose() => container.dispose();
}

Future<void> _settle() async {
  for (var i = 0; i < 60; i++) {
    await Future<void>.delayed(Duration.zero);
  }
}

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  setUp(() => SharedPreferences.setMockInitialValues({}));

  // ------------------------------------------------------------------ the verifier

  test('PBKDF2-HMAC-SHA256 matches the RFC 7914 test vector', () {
    final v = pbkdf2Sha256(utf8.encode('passwd'), utf8.encode('salt'), 1, length: 64);
    expect(
        v.map((b) => b.toRadixString(16).padLeft(2, '0')).join(),
        '55ac046e56e3089fec1691c22544b605f94185216dde0465e68b9d57c20dacbc'
        '49ca9cccf179b645991664b39d77ef317c71b845b1e30bd509112041d3a19783');
  });

  test('an online sign-in leaves a verifier and profile — never the password', () async {
    final disks = Disks();
    final t = Till(disks, reachable: true);
    addTearDown(t.dispose);
    await t.seedCatalog();

    await t.app.login('Asha', 'Secret#2026');
    expect(t.appState.phase, AppPhase.openShift);

    final u = t.users.user('asha')!;
    expect(u.userUid, _asha);
    expect(u.cashierId, 'U1');
    expect(u.password, isNotNull);
    expect(u.password!.iterations, 1000);
    expect(u.pin, isNull);
    expect(u.canSellOffline, isTrue);
    expect(u.permissions, isNot(contains('SALES.INVOICE.VIEW')), reason: 'only what selling offline needs');
    expect(u.context.branchUid, 'BR1');

    // Sealed at rest, and even opened holds no password.
    final raw = disks.users.data['users']!;
    expect(latin1.decode(raw).contains('asha'), isFalse);
    final opened = utf8.decode(await TestCipher().open(raw));
    expect(opened.contains('Secret#2026'), isFalse);
  });

  test('a PIN sign-in online adds a PIN verifier beside the password one', () async {
    final t = Till(Disks(), reachable: true);
    addTearDown(t.dispose);
    await t.seedCatalog();
    await t.app.login('asha', 'Secret#2026');
    await t.app.loginWithPin('asha', '4821');
    final u = t.users.user('asha')!;
    expect(u.password, isNotNull);
    expect(u.pin, isNotNull);
  });

  test('a refused online sign-in deletes the verifier at once; a dead line does not', () async {
    final t = Till(Disks(), reachable: true);
    addTearDown(t.dispose);
    await t.seedCatalog();
    await t.app.login('asha', 'Secret#2026');
    expect(t.users.user('asha'), isNotNull);

    t.auth.down = true;
    await t.app.login('asha', 'Secret#2026');
    expect(t.users.user('asha'), isNotNull, reason: 'no answer says nothing about the account');

    t.auth.down = false;
    t.auth.refuse = true; // disabled / locked / removed — the server does not say which
    await t.app.login('asha', 'Secret#2026');
    expect(t.users.user('asha'), isNull);
  });

  test('signing out keeps the verifier', () async {
    final t = Till(Disks(), reachable: true);
    addTearDown(t.dispose);
    await t.seedCatalog();
    await t.app.login('asha', 'Secret#2026');
    await t.app.logout();
    expect(t.users.user('asha'), isNotNull);
  });

  // ------------------------------------------------------------------ the store

  group('offline check of password and PIN', () {
    late OfflineSignInStore store;
    final online = DateTime.utc(2026, 11, 1, 8);
    setUp(() async {
      store = OfflineSignInStore(MemoryBlobStore(), TestCipher(), iterations: 1000);
      final me = Me(
          uid: _asha,
          username: 'asha',
          displayName: 'Asha',
          isRoot: false,
          activeCompanyUid: 'CO1',
          activeBranchUid: 'BR1',
          permissions: const {'POS.SALE.CREATE', 'POS.SESSION.OPEN'});
      await store.rememberOnline(
          username: 'asha', secret: 'Secret#2026', pin: false, me: me, cashierId: 'U1', context: _ctx(), now: online);
      await store.rememberOnline(
          username: 'asha', secret: '4821', pin: true, me: me, cashierId: 'U1', context: _ctx(), now: online);
    });

    test('right and wrong password; right and wrong PIN', () async {
      final at = online.add(const Duration(hours: 2));
      expect((await store.verify('ASHA ', 'Secret#2026', pin: false, now: at)).outcome,
          OfflineSignInOutcome.ok);
      expect((await store.verify('asha', 'secret#2026', pin: false, now: at)).outcome,
          OfflineSignInOutcome.wrong);
      expect((await store.verify('asha', '4821', pin: true, now: at)).outcome, OfflineSignInOutcome.ok);
      final wrongPin = await store.verify('asha', '1111', pin: true, now: at);
      expect(wrongPin.outcome, OfflineSignInOutcome.wrong);
      expect(wrongPin.triesLeft, 4, reason: 'the right PIN in between reset the count');
      // The password is not accepted as a PIN, nor the PIN as a password.
      expect((await store.verify('asha', '4821', pin: false, now: at)).outcome,
          OfflineSignInOutcome.wrong);
    });

    test('5 wrong tries lock the user out offline for 15 minutes', () async {
      final at = online.add(const Duration(hours: 1));
      for (var i = 1; i <= 4; i++) {
        final r = await store.verify('asha', 'nope', pin: false, now: at);
        expect(r.outcome, OfflineSignInOutcome.wrong);
        expect(r.triesLeft, 5 - i);
      }
      final fifth = await store.verify('asha', 'nope', pin: false, now: at);
      expect(fifth.outcome, OfflineSignInOutcome.locked);
      expect(fifth.lockedUntil, at.add(const Duration(minutes: 15)));
      // Locked even with the right password (and the PIN).
      expect((await store.verify('asha', 'Secret#2026', pin: false, now: at.add(const Duration(minutes: 14)))).outcome,
          OfflineSignInOutcome.locked);
      expect((await store.verify('asha', '4821', pin: true, now: at.add(const Duration(minutes: 14)))).outcome,
          OfflineSignInOutcome.locked);
      expect((await store.verify('asha', 'Secret#2026', pin: false, now: at.add(const Duration(minutes: 15)))).outcome,
          OfflineSignInOutcome.ok);
    });

    test('the lockout survives an app restart', () async {
      final disk = MemoryBlobStore();
      final s1 = OfflineSignInStore(disk, TestCipher(), iterations: 1000);
      await s1.rememberOnline(
          username: 'asha',
          secret: 'Secret#2026',
          pin: false,
          me: Me(
              uid: _asha,
              username: 'asha',
              displayName: 'Asha',
              isRoot: false,
              activeCompanyUid: 'CO1',
              activeBranchUid: 'BR1',
              permissions: const {'POS.SALE.CREATE', 'POS.SESSION.OPEN'}),
          cashierId: 'U1',
          context: _ctx(),
          now: online);
      final at = online.add(const Duration(hours: 1));
      for (var i = 0; i < 5; i++) {
        await s1.verify('asha', 'nope', pin: false, now: at);
      }
      final s2 = OfflineSignInStore(disk, TestCipher(), iterations: 1000);
      expect((await s2.verify('asha', 'Secret#2026', pin: false, now: at.add(const Duration(minutes: 1)))).outcome,
          OfflineSignInOutcome.locked);
    });

    test('a verifier expires 14 days after the last online sign-in, and is deleted', () async {
      expect(
          (await store.verify('asha', 'Secret#2026',
                  pin: false, now: online.add(const Duration(days: 13, hours: 23))))
              .outcome,
          OfflineSignInOutcome.ok);
      expect(
          (await store.verify('asha', 'Secret#2026', pin: false, now: online.add(const Duration(days: 14))))
              .outcome,
          OfflineSignInOutcome.unknown);
      expect(store.user('asha'), isNull);
    });

    test('no verifier, no offline sign-in', () async {
      expect((await store.verify('baraka', 'anything', pin: false, now: online)).outcome,
          OfflineSignInOutcome.unknown);
      final noPin = OfflineSignInStore(MemoryBlobStore(), TestCipher(), iterations: 1000);
      expect((await noPin.verify('asha', '4821', pin: true, now: online)).outcome,
          OfflineSignInOutcome.unknown);
    });

    test('verifiers another device (or Windows user) cannot open are refused', () async {
      final disk = MemoryBlobStore();
      final s = OfflineSignInStore(disk, TestCipher(), iterations: 1000);
      await s.forget('x'); // creates nothing
      await s.rememberOnline(
          username: 'asha',
          secret: 'Secret#2026',
          pin: false,
          me: Me(
              uid: _asha,
              username: 'asha',
              displayName: 'Asha',
              isRoot: false,
              activeCompanyUid: 'CO1',
              activeBranchUid: 'BR1',
              permissions: const {'POS.SALE.CREATE', 'POS.SESSION.OPEN'}),
          cashierId: 'U1',
          context: _ctx(),
          now: online);
      final other = OfflineSignInStore(disk, TestCipher(key: 42), iterations: 1000);
      expect((await other.verify('asha', 'Secret#2026', pin: false, now: online)).outcome,
          OfflineSignInOutcome.unavailable);
    });
  });

  // ------------------------------------------------------------------ the rules around it

  group('when offline sign-in is allowed', () {
    Future<Till> knownTill({bool tillAllowed = true}) async {
      final disks = Disks();
      final first = Till(disks, reachable: true, tillAllowed: tillAllowed);
      await first.seedCatalog();
      await first.app.login('asha', 'Secret#2026');
      first.dispose();
      final t = Till(disks, reachable: false, tillAllowed: tillAllowed);
      await t.seedCatalog();
      return t;
    }

    test('refused while the server answers', () async {
      final t = await knownTill();
      addTearDown(t.dispose);
      t.reachable = true;
      final err = await t.app.loginOffline('asha', 'Secret#2026');
      expect(err, contains('server answers'));
      expect(t.appState.phase, isNot(AppPhase.register));
      expect(t.offlineState.offline, isFalse);
    });

    test('refused on a till not allowed offline', () async {
      final t = await knownTill(tillAllowed: false);
      addTearDown(t.dispose);
      expect(await t.app.loginOffline('asha', 'Secret#2026'), contains('not allowed to sell offline'));
    });

    test('wrong password offline: refused, nothing opened', () async {
      final t = await knownTill();
      addTearDown(t.dispose);
      expect(await t.app.loginOffline('asha', 'guess'), contains('not correct'));
      expect(t.appState.phase, isNot(AppPhase.register));
      expect(t.appState.offlineSession, isFalse);
    });

    test('right password offline: the till is offline, signed in offline, stale tokens gone', () async {
      final t = await knownTill();
      addTearDown(t.dispose);
      await t.tokens.setSession(TokenBundle(
          accessToken: _jwt('SOMEONE-ELSE'),
          accessTokenExpiresAt: DateTime.now().toUtc(),
          refreshToken: 'old',
          user: AuthUser(
              uid: 'X',
              username: 'x',
              displayName: 'X',
              isRoot: false,
              activeCompanyUid: 'CO1',
              activeBranchUid: 'BR1',
              hasBranch: true)));
      expect(await t.app.loginOffline('asha', 'Secret#2026'), isNull);
      expect(t.appState.offlineSession, isTrue);
      expect(t.appState.me!.uid, _asha);
      expect(t.appState.context!.branchUid, 'BR1');
      expect(t.offlineState.offline, isTrue);
      expect(t.offlineState.offlineSession, isTrue);
      expect(t.tokens.hasSession, isFalse, reason: 'never sync with stale credentials');
    });
  });

  // ------------------------------------------------------------------ restart mid-outage

  test('restart in the middle of an outage: sign in offline, same shift, same queue, same period',
      () async {
    final disks = Disks();

    // Launch 1: online sign-in, then the line drops; an offline shift and a sale.
    final one = Till(disks, reachable: true, cachedTill: till(allowed: true).withOccupancy());
    await one.seedCatalog();
    await one.app.login('asha', 'Secret#2026');
    one.reachable = false;
    expect(await one.offline.switchOffline(), OfflineSwitchDecision.allowed);
    await one.app.openOfflineShift(5000, one.appState.mode);
    final shiftUid = one.appState.shift!.uid;
    expect(offlineShiftKeyOf(one.appState.shift), isNotNull);
    final sold = await one.offline.recordSale(
        cart: _cart(one.container), tendered: [cash(1180)], ageVerified: false);
    expect(sold.rungByUid, _asha);
    final periodKey = one.offlineState.periodKey;
    one.dispose(); // the till crashes

    // Launch 2: the server is still down.
    final two = Till(disks, reachable: false);
    addTearDown(two.dispose);
    await two.app.bootstrap();
    await _settle();
    expect(two.appState.phase, AppPhase.login);
    expect(two.appState.offlineSignInAvailable, isTrue);

    expect(await two.app.loginOffline('asha', 'Secret#2026'), isNull);
    expect(two.appState.phase, AppPhase.register);
    expect(two.appState.shift!.uid, shiftUid);
    expect(two.appState.shift!.openingFloatAmount, 5000);
    expect(two.offlineState.offline, isTrue);
    expect(two.offlineState.periodKey, periodKey, reason: 'the open offline period continues');
    expect(two.offlineState.waiting, 1);

    final next = await two.offline.recordSale(
        cart: _cart(two.container), tendered: [cash(1180)], ageVerified: false);
    expect(next.sessionClientKey, offlineShiftKeyOf(two.appState.shift));
    expect(next.offlineNumber, isNot(sold.offlineNumber));
    expect(next.toWire()['rungByUid'], _asha);
    expect(two.queue.snapshot.sales.length, 2);
  });

  // ------------------------------------------------------------------ server back

  test('server back after an offline sign-in: no sync until an online sign-in, then it syncs',
      () async {
    final disks = Disks();
    final first = Till(disks, reachable: true);
    await first.seedCatalog();
    await first.app.login('asha', 'Secret#2026');
    first.dispose();

    final t = Till(disks, reachable: false);
    addTearDown(t.dispose);
    await t.seedCatalog();
    expect(await t.app.loginOffline('asha', 'Secret#2026'), isNull);
    expect(t.appState.phase, AppPhase.openShift);
    await t.app.openOfflineShift(0, t.appState.mode);
    await t.offline.recordSale(cart: _cart(t.container), tendered: [cash(1180)], ageVerified: false);
    t.container.read(cartProvider.notifier).clearLines();

    t.reachable = true;
    await t.offline.probe();
    expect(t.offlineState.needsOnlineSignIn, isTrue);
    expect(t.offlineState.offline, isTrue, reason: 'no session to go online with');
    expect(await t.offline.syncNow(), contains('Sign in online'));
    expect(t.api.syncs, isEmpty);

    await t.offline.requestOnlineSignIn();
    expect(t.appState.phase, AppPhase.login);
    expect(t.appState.error, contains('Sign in'));
    expect(t.queue.snapshot.sales.length, 1, reason: 'the queue waits safely');
    expect(t.queue.snapshot.periods.single.ended, isTrue);
    expect(t.api.syncs, isEmpty);

    // A normal online sign-in: the offline shift is resumed and everything syncs.
    await t.app.login('asha', 'Secret#2026');
    await _settle();
    expect(t.api.syncs.length, 1);
    final sent = t.api.syncs.single;
    expect((sent['sales'] as List).single['rungByUid'], _asha);
    expect((sent['sessions'] as List).length, 1);
    expect(t.queue.snapshot.sales, isEmpty);
    expect(t.appState.phase, AppPhase.register);
    expect(t.appState.shift!.uid, 'SRV-9');
  });

  testWidgets('the sign-in screen offers offline sign-in only when it is available', (tester) async {
    tester.view.physicalSize = const Size(1400, 1800);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.reset);
    Future<void> pump(bool available) async {
      await tester.pumpWidget(ProviderScope(
        key: UniqueKey(),
        overrides: [
          appControllerProvider.overrideWith(() => StubApp(
              AppData(phase: AppPhase.login, offlineSignInAvailable: available))),
        ],
        child: const MaterialApp(home: LoginScreen()),
      ));
      await tester.pumpAndSettle();
    }

    await pump(false);
    expect(find.text('Sign in offline'), findsNothing);
    await pump(true);
    expect(find.text('Sign in offline'), findsOneWidget);
    expect(find.text('No connection to the ERP'), findsOneWidget);
  });

  test('rungByUid travels on the wire (≤ 26 characters)', () {
    final s = sampleSale('OFF-T2K7-000001');
    expect(s.toWire()['rungByUid'], isNull);
    final rung = OfflineSale.fromJson({...s.toJson(), 'rungByUid': _asha});
    expect(rung.toWire()['rungByUid'], _asha);
    expect(_asha.length, lessThanOrEqualTo(26));
  });
}

CartState _cart(ProviderContainer c) {
  final cart = c.read(cartProvider.notifier);
  cart.start(customer: walkIn, currency: 'TZS');
  cart.addProduct(sugar, piece);
  cart.setLinePricing(c.read(cartProvider).lines.single.localId,
      amount: 1000, vatInclusive: false, vatFraction: 0.18, minorUnits: 0);
  return c.read(cartProvider);
}
