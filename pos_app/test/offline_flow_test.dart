import 'dart:convert';
import 'dart:io' show Directory, Platform;
import 'dart:typed_data';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:hive_ce/hive.dart';
import 'package:pos_app/core/api/api_client.dart';
import 'package:pos_app/core/api/api_exception.dart';
import 'package:pos_app/core/api/token_manager.dart';
import 'package:pos_app/core/storage/secure_store.dart';
import 'package:pos_app/features/offline/offline_payment_sheet.dart';
import 'package:pos_app/models/enums.dart';
import 'package:pos_app/models/pos.dart';
import 'package:pos_app/offline/blob_store.dart';
import 'package:pos_app/offline/offline_controller.dart';
import 'package:pos_app/offline/offline_queue.dart';
import 'package:pos_app/offline/offline_sale.dart';
import 'package:pos_app/offline/secret_cipher.dart';
import 'package:pos_app/services/session_service.dart';
import 'package:pos_app/state/app_controller.dart';
import 'package:pos_app/state/cart_controller.dart';
import 'package:pos_app/state/providers.dart';
import 'package:pos_app/state/receipt_journal.dart';
import 'package:shared_preferences/shared_preferences.dart';

import 'support/offline_fixtures.dart';

/// Answers `POST /pos/offline-sync` like the server: every shift created,
/// every sale POSTED with an invoice number, every period recorded.
class _SyncServer extends ApiClient {
  _SyncServer()
      : super(
            apiBase: 'http://erp.test/api/v1',
            tokens: TokenManager(apiBase: 'http://erp.test/api/v1', store: SecureStore()));

  final bodies = <Map<String, dynamic>>[];

  @override
  Future<dynamic> post(String path,
      {Object? body,
      Map<String, dynamic>? query,
      String? idempotencyKey,
      String? xRequestId,
      bool noAuth = false}) async {
    if (path != '/pos/offline-sync') throw ApiException(statusCode: 404, message: 'Not found.');
    final b = (jsonDecode(jsonEncode(body)) as Map).cast<String, dynamic>();
    bodies.add(b);
    var n = 0;
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
            'invoiceUid': 'INV-${++n}',
            'invoiceNumber': 'SI-000$n',
            'flags': const [],
          },
      ],
      'periodsRecorded': (b['periods'] as List).length,
    };
  }

  /// The catalogue refresh on return is not under test: no server for it.
  @override
  Future<dynamic> get(String path, {Map<String, dynamic>? query}) async =>
      throw ApiException(statusCode: null, isNetwork: true, message: 'down');
}

class _Sessions implements SessionService {
  @override
  Future<PosSession> getByUid(String uid) async => shift(uid);
  @override
  dynamic noSuchMethod(Invocation invocation) => super.noSuchMethod(invocation);
}

Future<void> _settle() async {
  for (var i = 0; i < 40; i++) {
    await Future<void>.delayed(Duration.zero);
  }
}

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  group('coming back online (D-1)', () {
    test('server back mid-basket: the basket is finished offline, then the till returns',
        () async {
      final f = OfflineFixture(reachable: false);
      addTearDown(f.dispose);
      final ctrl = f.container.read(offlineControllerProvider.notifier);
      await ctrl.switchOffline();
      f.pricedCart(); // a basket in progress

      f.reachable = true;
      await ctrl.probe();
      var s = f.container.read(offlineControllerProvider);
      expect(s.offline, isTrue, reason: 'never flips under a basket being rung');
      expect(s.serverBack, isTrue);
      expect(s.onlineBy, s.serverBackAt!.add(const Duration(minutes: 5)));

      f.container.read(cartProvider.notifier).clearLines();
      ctrl.basketDone();
      await _settle();
      s = f.container.read(offlineControllerProvider);
      expect(s.offline, isFalse);
      expect(f.queue.snapshot.periods.single.ended, isTrue);
    });

    test('after the 5-minute grace the till returns online even with a basket', () async {
      final f = OfflineFixture(reachable: false);
      addTearDown(f.dispose);
      final ctrl = f.container.read(offlineControllerProvider.notifier);
      var now = DateTime.utc(2026, 11, 7, 10);
      ctrl.clock = () => now;
      await ctrl.switchOffline();
      f.pricedCart();
      f.reachable = true;
      await ctrl.probe();
      expect(f.container.read(offlineControllerProvider).offline, isTrue);

      now = now.add(const Duration(minutes: 4));
      await ctrl.probe();
      expect(f.container.read(offlineControllerProvider).offline, isTrue);

      now = now.add(const Duration(minutes: 1));
      await ctrl.probe();
      await _settle();
      expect(f.container.read(offlineControllerProvider).offline, isFalse,
          reason: 'a till cannot stay offline on a working connection');
      expect(f.container.read(cartProvider).isEmpty, isFalse, reason: 'the basket is kept');
    });

    test('server back with nothing being rung: straight back online', () async {
      final f = OfflineFixture(reachable: false);
      addTearDown(f.dispose);
      final ctrl = f.container.read(offlineControllerProvider.notifier);
      await ctrl.switchOffline();
      f.reachable = true;
      await ctrl.probe();
      await _settle();
      expect(f.container.read(offlineControllerProvider).offline, isFalse);
    });

    test('an open offline payment is never pulled online under the cashier', () async {
      final f = OfflineFixture(reachable: false);
      addTearDown(f.dispose);
      final ctrl = f.container.read(offlineControllerProvider.notifier);
      await ctrl.switchOffline();
      ctrl.paymentOpened();
      f.reachable = true;
      await ctrl.probe();
      await _settle();
      expect(f.container.read(offlineControllerProvider).offline, isTrue);
      ctrl.paymentClosed(); // the basket was emptied by the completed sale
      await _settle();
      expect(f.container.read(offlineControllerProvider).offline, isFalse);
    });

    test('a manual switch is not undone by a slow answer', () async {
      final f = OfflineFixture(reachable: true);
      addTearDown(f.dispose);
      final ctrl = f.container.read(offlineControllerProvider.notifier);
      await ctrl.switchOffline(managerUid: 'MGR1');
      f.latency = const Duration(seconds: 3); // reachable, but slow: why the manager switched
      await ctrl.probe();
      await _settle();
      expect(f.container.read(offlineControllerProvider).offline, isTrue);
      expect(f.container.read(offlineControllerProvider).serverBack, isFalse);
      f.latency = const Duration(milliseconds: 200); // fast again
      await ctrl.probe();
      await _settle();
      expect(f.container.read(offlineControllerProvider).offline, isFalse);
    });
  });

  group('offline shift and sync (D-4, D-5)', () {
    test('a shift opened offline syncs with its sales and becomes the server shift', () async {
      SharedPreferences.setMockInitialValues({});
      final server = _SyncServer();
      final f = OfflineFixture(reachable: false, extra: [
        apiClientProvider.overrideWithValue(server),
        sessionServiceProvider.overrideWithValue(_Sessions()),
      ]);
      addTearDown(f.dispose);
      final ctrl = f.container.read(offlineControllerProvider.notifier);
      ctrl.markSignedInForTest();
      final app = f.container.read(appControllerProvider.notifier);

      // The cashier's shift ended; the line is down; a new shift opens offline.
      app.endShift();
      await ctrl.switchOffline();
      await app.openOfflineShift(5000, BusinessMode.supermarket);
      final offShift = f.container.read(appControllerProvider).shift!;
      expect(offlineShiftKeyOf(offShift), isNotNull);

      final sale = await ctrl.recordSale(
          cart: f.pricedCart(), tendered: [cash(2000)], ageVerified: false);
      expect(sale.sessionClientKey, offlineShiftKeyOf(offShift));
      expect(sale.sessionUid, isNull);
      await ReceiptJournal()
          .add(offlineReceipt(sale, tendered: [cash(2000)], minorUnits: 0));
      // Closing waits for the sync (D-4).
      expect(ctrl.shiftHasUnsynced(offShift), isTrue);

      f.reachable = true;
      f.container.read(cartProvider.notifier).clearLines();
      await ctrl.returnOnline(); // ends the period, then syncs
      await _settle();

      final sent = server.bodies.single;
      expect(sent['tillUid'], 'TILL1');
      expect((sent['sessions'] as List).single['openingFloatAmount'], 5000);
      expect((sent['sales'] as List).single['sessionClientKey'], offlineShiftKeyOf(offShift));
      final period = (sent['periods'] as List).single as Map;
      expect(period['reason'], 'UNREACHABLE');
      expect(period['endedAt'], isNotNull);
      expect(period['salesCount'], 1);

      expect(f.queue.snapshot.sales, isEmpty);
      expect(f.queue.snapshot.periods, isEmpty);
      final s = f.container.read(offlineControllerProvider);
      expect(s.waiting, 0);
      expect(s.lastSyncAt, isNotNull);
      // The till now works on the real server shift.
      final now = f.container.read(appControllerProvider).shift!;
      expect(now.uid, 'SRV-9');
      expect(ctrl.shiftHasUnsynced(now), isFalse);
      // The reprint carries the real invoice number and keeps the OFFLINE mark.
      final reprint = (await ReceiptJournal().recent()).first.invoice;
      expect(reprint.invoiceNumber, 'SI-0001');
      expect(reprint.offlineNumber, sale.offlineNumber);
      expect(reprint.soldOffline, isTrue);
    });
  });

  testWidgets('the sale is sealed on disk before its receipt appears', (tester) async {
    SharedPreferences.setMockInitialValues({});
    tester.view.physicalSize = const Size(1400, 2200);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.reset);

    final disk = GatedStore();
    final f = OfflineFixture(reachable: false, store: disk);
    addTearDown(f.dispose);
    final ctrl = f.container.read(offlineControllerProvider.notifier);
    await ctrl.switchOffline();
    f.pricedCart();

    await tester.pumpWidget(UncontrolledProviderScope(
      container: f.container,
      child: MaterialApp(
        home: Scaffold(
          body: Consumer(
            builder: (context, ref, _) => ElevatedButton(
                onPressed: () => openOfflinePaymentSheet(context, ref), child: const Text('Pay')),
          ),
        ),
      ),
    ));
    await tester.tap(find.text('Pay'));
    await tester.pumpAndSettle();
    expect(find.text('Payment — OFFLINE'), findsOneWidget);
    // Offline tenders only: no complimentary, no cheque.
    expect(find.byKey(const ValueKey('offline-tender-COMPLIMENTARY')), findsNothing);
    expect(find.byKey(const ValueKey('offline-tender-CHEQUE')), findsNothing);

    disk.hold(); // the disk is slow
    await tester.tap(find.text('Complete offline sale'));
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 50));
    expect(find.text('Sale recorded offline'), findsNothing, reason: 'no receipt before the write');
    expect(f.queue.snapshot.sales, isEmpty);

    disk.release();
    await tester.pumpAndSettle();
    final stored = f.queue.snapshot.sales.single;
    expect(find.text('Sale recorded offline'), findsOneWidget);
    expect(find.textContaining('*** OFFLINE SALE ***'), findsOneWidget);
    expect(find.textContaining(stored.offlineNumber), findsOneWidget);
    expect(f.container.read(cartProvider).isEmpty, isTrue);

    await ctrl.signedOut(); // stop the probe timer
    await tester.pump();
  });

  testWidgets('mobile money without its reference is refused, nothing stored', (tester) async {
    SharedPreferences.setMockInitialValues({});
    tester.view.physicalSize = const Size(1400, 2200);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.reset);
    final f = OfflineFixture(reachable: false);
    addTearDown(f.dispose);
    final ctrl = f.container.read(offlineControllerProvider.notifier);
    await ctrl.switchOffline();
    f.pricedCart();
    await tester.pumpWidget(UncontrolledProviderScope(
      container: f.container,
      child: MaterialApp(
        home: Scaffold(
          body: Consumer(
            builder: (context, ref, _) => ElevatedButton(
                onPressed: () => openOfflinePaymentSheet(context, ref), child: const Text('Pay')),
          ),
        ),
      ),
    ));
    await tester.tap(find.text('Pay'));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const ValueKey('offline-tender-MOBILE_MONEY')));
    await tester.pumpAndSettle();
    expect(find.byKey(const ValueKey('offline-reference')), findsOneWidget);
    await tester.tap(find.text('Complete offline sale'));
    await tester.pumpAndSettle();
    expect(find.text('Enter the mobile money transaction reference.'), findsOneWidget);
    expect(f.queue.snapshot.sales, isEmpty);

    await tester.enterText(
        find.descendant(
            of: find.byKey(const ValueKey('offline-reference')), matching: find.byType(TextField)),
        'MP-88231');
    await tester.pump();
    await tester.tap(find.text('Complete offline sale'));
    await tester.pumpAndSettle();
    final stored = f.queue.snapshot.sales.single;
    expect(stored.tenders.single.type, TenderType.mobileMoney);
    expect(stored.tenders.single.reference, 'MP-88231');

    await ctrl.signedOut();
    await tester.pump();
  });

  test('the hive store keeps the sealed queue across a close and reopen', () async {
    final dir = await Directory.systemTemp.createTemp('orbix_offline_');
    addTearDown(() async {
      await Hive.close();
      await dir.delete(recursive: true);
    });
    Hive.init(dir.path);
    final q = OfflineQueue(await HiveBlobStore.open('offline_queue'), TestCipher());
    final sale = await q.addSale(tillCode: 'T2', tillUid: 'TILL1', build: sampleSale);
    await Hive.close();

    Hive.init(dir.path); // a new app launch
    final back = OfflineQueue(await HiveBlobStore.open('offline_queue'), TestCipher());
    await back.load();
    expect(back.snapshot.sales.single.offlineNumber, sale.offlineNumber);
    final next = await back.addSale(tillCode: 'T2', tillUid: 'TILL1', build: sampleSale);
    expect(next.offlineNumber, endsWith('-000002'));
  });

  group('device key store', () {
    test('Windows DPAPI seals and opens; the blob is not the plaintext; tampering fails',
        () async {
      final c = platformCipher();
      expect(c.name, 'Windows DPAPI');
      final plain = Uint8List.fromList(utf8.encode('OFF-T2K7-000001 Sugar 1kg 1180'));
      final sealed = await c.seal(plain);
      expect(latin1.decode(sealed).contains('Sugar'), isFalse);
      expect(await c.open(sealed), plain);
      final tampered = Uint8List.fromList(sealed)..[sealed.length - 1] ^= 0xFF;
      await expectLater(c.open(tampered), throwsA(isA<SecretCipherException>()));

      // And the whole queue over it, end to end.
      final store = MemoryBlobStore();
      final q = OfflineQueue(store, c);
      final sale = await q.addSale(tillCode: 'T2', tillUid: 'TILL1', build: sampleSale);
      final back = OfflineQueue(store, platformCipher());
      await back.load();
      expect(back.snapshot.sales.single.offlineNumber, sale.offlineNumber);
    }, skip: !Platform.isWindows ? 'DPAPI exists on Windows only' : false);
  });
}
