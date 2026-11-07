import 'dart:convert';
import 'dart:typed_data';

import 'package:flutter_test/flutter_test.dart';
import 'package:pos_app/core/config/step_up_policy.dart';
import 'package:pos_app/features/receipt/receipt_text.dart';
import 'package:pos_app/models/auth.dart';
import 'package:pos_app/models/enums.dart';
import 'package:pos_app/models/parties.dart';
import 'package:pos_app/models/pos.dart';
import 'package:pos_app/offline/blob_store.dart';
import 'package:pos_app/offline/offline_controller.dart';
import 'package:pos_app/offline/offline_models.dart';
import 'package:pos_app/offline/offline_queue.dart';
import 'package:pos_app/offline/offline_sale.dart';
import 'package:pos_app/offline/secret_cipher.dart';
import 'package:pos_app/state/cart_controller.dart';
import 'package:pos_app/state/receipt_journal.dart';
import 'package:shared_preferences/shared_preferences.dart';

import 'support/offline_fixtures.dart';

/// Offline POS, phase 1 (ERP ADR-0068). The owner's rulings are the spec:
/// cashier switches only when the server is truly unreachable, a manager's
/// password otherwise; cash / mobile money / card with references; the sale is
/// durable (and encrypted) before any receipt exists; numbers never repeat; the
/// sync answer is applied exactly; 24 hours of unsynced selling at most.
void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  // ------------------------------------------------------------------ switch

  group('switch rules (D-1)', () {
    test('a till the admin has not allowed never goes offline', () {
      for (final reachable in [true, false]) {
        for (final manager in [true, false]) {
          expect(
              decideOfflineSwitch(
                  tillAllowed: false, serverReachable: reachable, managerApproved: manager),
              OfflineSwitchDecision.notAllowed);
        }
      }
    });

    test('server truly unreachable: any signed-in cashier may switch', () {
      expect(
          decideOfflineSwitch(tillAllowed: true, serverReachable: false, managerApproved: false),
          OfflineSwitchDecision.allowed);
    });

    test('server reachable: a manual switch needs a manager', () {
      expect(
          decideOfflineSwitch(tillAllowed: true, serverReachable: true, managerApproved: false),
          OfflineSwitchDecision.needsManager);
      expect(
          decideOfflineSwitch(tillAllowed: true, serverReachable: true, managerApproved: true),
          OfflineSwitchDecision.allowed);
    });

    test('the manual switch asks for POS.OFFLINE.SWITCH (a password step-up)', () {
      final rule = stepUpRuleFor(GatedAction.offlineSwitch);
      expect(rule.requiresApproval, isTrue);
      expect(rule.permissionCode, 'POS.OFFLINE.SWITCH');
      expect(Perms.offlineSwitch, 'POS.OFFLINE.SWITCH');
    });

    test('controller: unreachable server, a cashier switches; the period says UNREACHABLE',
        () async {
      final f = OfflineFixture(reachable: false);
      addTearDown(f.dispose);
      final ctrl = f.container.read(offlineControllerProvider.notifier);

      expect(await ctrl.switchOffline(), OfflineSwitchDecision.allowed);

      final s = f.container.read(offlineControllerProvider);
      expect(s.offline, isTrue);
      expect(s.reason, OfflineReason.unreachable);
      expect(s.switchedByUid, isNull);
      final period = f.queue.snapshot.periods.single;
      expect(period.reason, OfflineReason.unreachable);
      expect(period.toWire()['switchedByUid'], isNull);
      expect(period.sessionUid, 'SESS1');
    });

    test('controller: reachable server needs a manager; with one it is a MANUAL switch',
        () async {
      final f = OfflineFixture(reachable: true);
      addTearDown(f.dispose);
      final ctrl = f.container.read(offlineControllerProvider.notifier);

      expect(await ctrl.switchOffline(), OfflineSwitchDecision.needsManager);
      expect(f.container.read(offlineControllerProvider).offline, isFalse);
      expect(f.queue.snapshot.periods, isEmpty);

      expect(await ctrl.switchOffline(managerUid: 'MGR1'), OfflineSwitchDecision.allowed);
      final s = f.container.read(offlineControllerProvider);
      expect(s.offline, isTrue);
      expect(s.reason, OfflineReason.manual);
      expect(f.queue.snapshot.periods.single.toWire()['switchedByUid'], 'MGR1');
      expect(f.queue.snapshot.periods.single.toWire()['reason'], 'MANUAL');
    });

    test('controller: a till not allowed offline stays online even with no server', () async {
      final f = OfflineFixture(reachable: false, tillAllowed: false);
      addTearDown(f.dispose);
      expect(await f.container.read(offlineControllerProvider.notifier).switchOffline(),
          OfflineSwitchDecision.notAllowed);
      expect(f.container.read(offlineControllerProvider).offline, isFalse);
    });

    test('controller: a device that cannot encrypt refuses to go offline', () async {
      final f = OfflineFixture(reachable: false, cipher: const BrokenCipher());
      addTearDown(f.dispose);
      await expectLater(f.container.read(offlineControllerProvider.notifier).switchOffline(),
          throwsA(isA<SecretCipherException>()));
      expect(f.container.read(offlineControllerProvider).offline, isFalse);
    });
  });

  // ------------------------------------------------------------------ queue

  group('durable, encrypted queue (D-3)', () {
    test('a recorded sale survives a new app launch, field for field', () async {
      final store = MemoryBlobStore();
      final q = OfflineQueue(store, TestCipher());
      final sale = await q.addSale(tillCode: 'T2', tillUid: 'TILL1', build: sampleSale);

      final relaunched = OfflineQueue(store, TestCipher());
      await relaunched.load();
      final back = relaunched.snapshot.sales.single;
      expect(back.offlineNumber, sale.offlineNumber);
      expect(back.idempotencyKey, sale.idempotencyKey);
      expect(back.toWire(), sale.toWire());
      expect(relaunched.snapshot.deviceTag, q.snapshot.deviceTag);
    });

    test('the wire shape is exactly the server contract', () async {
      final q = OfflineQueue(MemoryBlobStore(), TestCipher());
      final sale = await q.addSale(tillCode: 'T2', tillUid: 'TILL1', build: sampleSale);
      final w = sale.toWire();
      expect(w.keys.toSet(), {
        'idempotencyKey', 'offlineNumber', 'soldAt', 'sessionUid', 'sessionClientKey',
        'customerId', 'currency', 'lines', 'tenders', 'grossTotal', 'ageVerified', 'notes',
      });
      expect((w['lines'] as List).single,
          {'productId': '1', 'unitId': '2', 'quantity': 1.0, 'unitPrice': 1000.0, 'priceInclusive': false});
      expect((w['tenders'] as List).single, {'tenderType': 'CASH', 'amount': 1180.0});
      expect(DateTime.parse(w['soldAt'] as String).isUtc, isTrue);
    });

    test('the queue is encrypted at rest: nothing readable on disk', () async {
      final store = MemoryBlobStore();
      final q = OfflineQueue(store, TestCipher());
      final sale = await q.addSale(tillCode: 'T2', tillUid: 'TILL1', build: sampleSale);
      final raw = latin1.decode(store.data['queue']!);
      expect(raw.contains(sale.offlineNumber), isFalse);
      expect(raw.contains('Sugar 1kg'), isFalse);
      expect(raw.contains('idempotencyKey'), isFalse);
    });

    test('a queue this device cannot open is never written over', () async {
      final store = MemoryBlobStore();
      await OfflineQueue(store, TestCipher())
          .addSale(tillCode: 'T2', tillUid: 'TILL1', build: sampleSale);
      final before = Uint8List.fromList(store.data['queue']!);

      final other = OfflineQueue(store, TestCipher(key: 99)); // another device / Windows user
      await other.load();
      expect(other.readable, isFalse);
      await expectLater(other.addSale(tillCode: 'T2', tillUid: 'TILL1', build: sampleSale),
          throwsA(isA<SecretCipherException>()));
      expect(store.data['queue'], before);
    });

    test('nothing is stored (so nothing can print) when the write fails', () async {
      final q = OfflineQueue(FailingWriteStore(), TestCipher());
      await expectLater(q.addSale(tillCode: 'T2', tillUid: 'TILL1', build: sampleSale),
          throwsA(anything));
      expect(q.snapshot.sales, isEmpty);
    });
  });

  // ------------------------------------------------------------------ numbering

  group('offline numbering', () {
    test('OFF-<till tag>-<6 digits>, counting up per till', () async {
      final q = OfflineQueue(MemoryBlobStore(), TestCipher());
      final a = await q.addSale(tillCode: 'T2', tillUid: 'TILL1', build: sampleSale);
      final b = await q.addSale(tillCode: 'T2', tillUid: 'TILL1', build: sampleSale);
      final c = await q.addSale(tillCode: 'T-3', tillUid: 'TILL3', build: sampleSale);
      final tag = q.snapshot.deviceTag;
      expect(a.offlineNumber, 'OFF-T2$tag-000001');
      expect(b.offlineNumber, 'OFF-T2$tag-000002');
      expect(c.offlineNumber, 'OFF-T3$tag-000001');
      expect(RegExp(r'^OFF-T2[A-Z2-9]{2}-\d{6}$').hasMatch(a.offlineNumber), isTrue);
      expect(a.offlineNumber.length, lessThanOrEqualTo(40));
    });

    test('the sequence persists across restarts and survives a full sync', () async {
      final store = MemoryBlobStore();
      final q = OfflineQueue(store, TestCipher());
      await q.addSale(tillCode: 'T2', tillUid: 'TILL1', build: sampleSale);
      await q.addSale(tillCode: 'T2', tillUid: 'TILL1', build: sampleSale);
      // Everything synced: the queue is empty, but the counter must not reset.
      final batch = q.nextBatch(branchUid: 'BR1')!;
      await q.applyAnswer(
          batch,
          SyncAnswer(sales: [
            for (final s in batch.sales)
              SyncSaleResult(
                  offlineNumber: s.offlineNumber,
                  idempotencyKey: s.idempotencyKey,
                  status: 'POSTED'),
          ]));
      expect(q.snapshot.sales, isEmpty);

      final relaunched = OfflineQueue(store, TestCipher());
      await relaunched.load();
      final next = await relaunched.addSale(tillCode: 'T2', tillUid: 'TILL1', build: sampleSale);
      expect(next.offlineNumber, endsWith('-000003'));
    });

    test('sales rung back to back never share a number', () async {
      final q = OfflineQueue(MemoryBlobStore(), TestCipher());
      final sales = await Future.wait([
        for (var i = 0; i < 30; i++)
          q.addSale(tillCode: 'T2', tillUid: 'TILL1', build: sampleSale),
      ]);
      final numbers = sales.map((s) => s.offlineNumber).toSet();
      expect(numbers.length, 30);
      expect(q.snapshot.sales.length, 30);
    });

    test('a till with no usable code gets a stable tag from its uid', () {
      expect(offlineTillTag('', '01HZX9ABCD', 'K7'), 'ABCDK7');
      expect(offlineTillTag('  ', '01HZX9ABCD', 'K7'), offlineTillTag('', '01HZX9ABCD', 'K7'));
      expect(offlineTillTag('Front Till 1', 'X', 'K7'), 'FRONTTILL1K7');
    });
  });

  // ------------------------------------------------------------------ sync answer

  group('sync answer handling (D-5)', () {
    test('POSTED / ALREADY_POSTED / HELD leave the queue; an omitted sale stays', () async {
      final q = OfflineQueue(MemoryBlobStore(), TestCipher());
      final s1 = await q.addSale(tillCode: 'T2', tillUid: 'TILL1', build: sampleSale);
      final s2 = await q.addSale(tillCode: 'T2', tillUid: 'TILL1', build: sampleSale);
      final s3 = await q.addSale(tillCode: 'T2', tillUid: 'TILL1', build: sampleSale);
      final s4 = await q.addSale(tillCode: 'T2', tillUid: 'TILL1', build: sampleSale);
      final batch = q.nextBatch(branchUid: 'BR1')!;
      expect(batch.sales.length, 4);

      final applied = await q.applyAnswer(
          batch,
          SyncAnswer(sales: [
            SyncSaleResult(
                offlineNumber: s1.offlineNumber,
                idempotencyKey: s1.idempotencyKey,
                status: 'POSTED',
                invoiceUid: 'INV1',
                invoiceNumber: 'SI-0001',
                flags: const ['PRICE_DIFFERENCE']),
            SyncSaleResult(
                offlineNumber: s2.offlineNumber,
                idempotencyKey: s2.idempotencyKey,
                status: 'ALREADY_POSTED',
                invoiceUid: 'INV2',
                invoiceNumber: 'SI-0002'),
            SyncSaleResult(
                offlineNumber: s3.offlineNumber,
                idempotencyKey: s3.idempotencyKey,
                status: 'HELD',
                message: 'A mobile money or card payment needs its reference.'),
            // s4 omitted: in flight on the server — keep it and send again.
          ]));

      expect(q.snapshot.sales.map((s) => s.idempotencyKey), [s4.idempotencyKey]);
      expect(applied.posted.map((r) => r.status), ['POSTED', 'ALREADY_POSTED']);
      expect(applied.held.single.offlineNumber, s3.offlineNumber);
      expect(applied.kept, 1);
      final held = q.snapshot.held.single;
      expect(held.offlineNumber, s3.offlineNumber);
      expect(held.message, 'A mobile money or card payment needs its reference.');
      expect(q.snapshot.lastSyncAt, isNotNull);
      // The omitted sale goes again next time.
      expect(q.nextBatch(branchUid: 'BR1')!.sales.single.idempotencyKey, s4.idempotencyKey);
    });

    test('an unknown status does not settle a sale (it is sent again)', () async {
      final q = OfflineQueue(MemoryBlobStore(), TestCipher());
      final s1 = await q.addSale(tillCode: 'T2', tillUid: 'TILL1', build: sampleSale);
      final batch = q.nextBatch(branchUid: 'BR1')!;
      await q.applyAnswer(
          batch,
          SyncAnswer(sales: [
            SyncSaleResult(
                offlineNumber: s1.offlineNumber, idempotencyKey: s1.idempotencyKey, status: 'MAYBE'),
          ]));
      expect(q.snapshot.sales.single.idempotencyKey, s1.idempotencyKey);
    });

    test('a shift opened offline is sent with its sales and adopted from the answer', () async {
      final q = OfflineQueue(MemoryBlobStore(), TestCipher());
      await q.addShift(OfflineShift(
          clientKey: 'K1',
          tillUid: 'TILL1',
          branchUid: 'BR1',
          openedAt: DateTime.utc(2026, 11, 7, 7),
          openingFloat: 5000,
          cashierId: 'U1'));
      final s1 = await q.addSale(
          tillCode: 'T2', tillUid: 'TILL1', build: (n) => sampleSale(n, clientKey: 'K1'));
      final batch = q.nextBatch(branchUid: 'BR1')!;
      expect(batch.shifts.single.toWire(),
          {'clientKey': 'K1', 'openedAt': '2026-11-07T07:00:00.000Z', 'openingFloatAmount': 5000.0});
      expect(batch.sales.single.toWire()['sessionClientKey'], 'K1');
      expect(batch.sales.single.toWire()['sessionUid'], isNull);

      // Rung while the sync was in flight: still on the offline key.
      final s2 = await q.addSale(
          tillCode: 'T2', tillUid: 'TILL1', build: (n) => sampleSale(n, clientKey: 'K1'));

      final applied = await q.applyAnswer(
          batch,
          SyncAnswer(
            sessions: const [(clientKey: 'K1', sessionUid: 'SRV-SESS-9', sessionNumber: 'PS-77')],
            sales: [
              SyncSaleResult(
                  offlineNumber: s1.offlineNumber,
                  idempotencyKey: s1.idempotencyKey,
                  status: 'POSTED'),
            ],
          ));

      expect(applied.adopted.single.serverSessionUid, 'SRV-SESS-9');
      final left = q.snapshot.sales.single;
      expect(left.idempotencyKey, s2.idempotencyKey);
      expect(left.sessionUid, 'SRV-SESS-9');
      expect(left.sessionClientKey, isNull);
      expect(q.nextBatch(branchUid: 'BR1')!.sales.single.toWire()['sessionUid'], 'SRV-SESS-9');
      expect(q.nextBatch(branchUid: 'BR1')!.shifts, isEmpty);
      // Closing the shift waits for that sale.
      expect(q.hasUnsyncedFor(sessionUid: 'SRV-SESS-9'), isTrue);
    });

    test('a shift the server could not create is not re-sent once its sales are held',
        () async {
      final q = OfflineQueue(MemoryBlobStore(), TestCipher());
      await q.addShift(OfflineShift(
          clientKey: 'K2',
          tillUid: 'TILL1',
          branchUid: 'BR1',
          openedAt: DateTime.utc(2026, 11, 7, 7),
          openingFloat: 0,
          cashierId: 'U1'));
      final s1 = await q.addSale(
          tillCode: 'T2', tillUid: 'TILL1', build: (n) => sampleSale(n, clientKey: 'K2'));
      final batch = q.nextBatch(branchUid: 'BR1')!;
      // The server refused the shift (no session in the answer) and held its sale.
      await q.applyAnswer(
          batch,
          SyncAnswer(sales: [
            SyncSaleResult(
                offlineNumber: s1.offlineNumber,
                idempotencyKey: s1.idempotencyKey,
                status: 'HELD',
                message: 'The shift this sale was rung in could not be found or created.'),
          ]));
      expect(q.snapshot.shifts, isEmpty);
      expect(q.nextBatch(branchUid: 'BR1'), isNull, reason: 'nothing left to hammer the server with');

      // A later sale still in that shift is sent (and the server decides).
      await q.addSale(
          tillCode: 'T2', tillUid: 'TILL1', build: (n) => sampleSale(n, clientKey: 'K2'));
      expect(q.nextBatch(branchUid: 'BR1')!.sales.single.sessionClientKey, 'K2');
    });

    test('ended periods go once recorded; the live one stays', () async {
      final q = OfflineQueue(MemoryBlobStore(), TestCipher());
      OfflinePeriod p(String key) => OfflinePeriod(
          clientKey: key,
          tillUid: 'TILL1',
          branchUid: 'BR1',
          reason: OfflineReason.unreachable,
          startedAt: DateTime.utc(2026, 11, 7, 8));
      await q.startPeriod(p('P1'));
      await q.endPeriod('P1', DateTime.utc(2026, 11, 7, 9));
      await q.startPeriod(p('P2'));
      final batch = q.nextBatch(branchUid: 'BR1')!;
      expect(batch.periods.length, 2);
      expect(batch.periods.first.toWire()['endedAt'], '2026-11-07T09:00:00.000Z');

      await q.applyAnswer(batch, const SyncAnswer(periodsRecorded: 2));
      expect(q.snapshot.periods.map((p) => p.clientKey), ['P2']);
    });

    test('a sale of another branch waits for a sign-in there', () async {
      final q = OfflineQueue(MemoryBlobStore(), TestCipher());
      await q.addSale(tillCode: 'T2', tillUid: 'TILL1', build: sampleSale);
      expect(q.nextBatch(branchUid: 'OTHER'), isNull);
    });
  });

  // ------------------------------------------------------------------ 24 hours

  group('24-hour offline limit (D-3)', () {
    final first = DateTime.utc(2026, 11, 7, 8);
    test('counted from the first unsynced offline sale', () {
      expect(offlineCapReached(null, first), isFalse);
      expect(offlineCapReached(first, first.add(const Duration(hours: 23, minutes: 59))), isFalse);
      expect(offlineCapReached(first, first.add(const Duration(hours: 24))), isTrue);
    });

    test('the till refuses a new offline sale after 24 hours until it syncs', () async {
      final f = OfflineFixture(reachable: false);
      addTearDown(f.dispose);
      final ctrl = f.container.read(offlineControllerProvider.notifier);
      ctrl.clock = () => first;
      await ctrl.switchOffline();
      await ctrl.recordSale(
          cart: f.pricedCart(), tendered: [cash(1180)], ageVerified: false);

      ctrl.clock = () => first.add(const Duration(hours: 23));
      expect(ctrl.sellingProblem(), isNull);

      ctrl.clock = () => first.add(const Duration(hours: 24, minutes: 1));
      expect(ctrl.sellingProblem(), contains('24 hours'));
      await expectLater(
          ctrl.recordSale(cart: f.pricedCart(), tendered: [cash(1180)], ageVerified: false),
          throwsA(isA<OfflineSaleRefused>()));
      expect(f.queue.snapshot.sales.length, 1);
    });
  });

  // ------------------------------------------------------------------ tenders / basket

  group('tender and reference rules (D-3)', () {
    test('cash, mobile money and card only', () {
      expect(offlineTenderProblem(1180, const []), 'Add a payment.');
      for (final t in [TenderType.cheque, TenderType.complimentary]) {
        expect(offlineTenderProblem(1180, [OfflineTender(type: t, amount: 1180)]),
            contains('cash, mobile money or card only'));
      }
    });

    test('mobile money and card need their reference', () {
      expect(
          offlineTenderProblem(
              1180, const [OfflineTender(type: TenderType.mobileMoney, amount: 1180)]),
          'Enter the mobile money transaction reference.');
      expect(
          offlineTenderProblem(1180,
              const [OfflineTender(type: TenderType.card, amount: 1180, reference: '  ')]),
          'Enter the card approval number.');
      expect(
          offlineTenderProblem(1180, const [
            OfflineTender(type: TenderType.mobileMoney, amount: 500, reference: 'MP123'),
            OfflineTender(type: TenderType.card, amount: 680, reference: 'APP9'),
          ]),
          isNull);
    });

    test('only cash gives change; the payments must cover the total', () {
      expect(
          offlineTenderProblem(1180,
              const [OfflineTender(type: TenderType.card, amount: 2000, reference: 'A')]),
          contains('Only cash gives change'));
      expect(offlineTenderProblem(1180, [cash(1000)]), contains('Still to pay'));
      expect(offlineTenderProblem(1180, [cash(2000)]), isNull);
    });

    test('cash is recorded net of change, so payments equal the sale', () {
      final rec = recordedTenders(1180, [
        cash(1000),
        const OfflineTender(type: TenderType.mobileMoney, amount: 500, reference: 'MP1'),
      ]);
      expect(offlineChange(1180, [cash(1000), const OfflineTender(type: TenderType.mobileMoney, amount: 500)]), 320);
      expect(rec.map((t) => (t.type, t.amount)).toList(),
          [(TenderType.cash, 680.0), (TenderType.mobileMoney, 500.0)]);
      expect(rec.fold(0.0, (s, t) => s + t.amount), 1180);
      expect(recordedTenders(1180, [cash(2000)]).single.amount, 1180);
    });

    test('no discounts, named customers or unpriced lines offline', () {
      final f = OfflineFixture(reachable: false);
      addTearDown(f.dispose);
      expect(offlineBasketProblem(f.pricedCart()), isNull);

      final cart = f.container.read(cartProvider.notifier);
      cart.setDiscount(f.container.read(cartProvider).lines.single.localId, 100);
      expect(offlineBasketProblem(f.container.read(cartProvider)), contains('Discounts'));

      cart.setDiscount(f.container.read(cartProvider).lines.single.localId, 0);
      cart.setCustomer(Customer(
          id: '8',
          uid: 'CUST8',
          code: 'C8',
          displayName: 'Hotel Bahari',
          customerKind: 'REGISTERED',
          defaultCurrency: 'TZS',
          status: 'ACTIVE'));
      expect(offlineBasketProblem(f.container.read(cartProvider)), contains('walk-in'));

      cart.resetCustomer();
      cart.setLinePricing(f.container.read(cartProvider).lines.single.localId,
          amount: null, vatInclusive: false, vatFraction: 0.18, minorUnits: 0);
      expect(offlineBasketProblem(f.container.read(cartProvider)), contains('no saved price'));
    });
  });

  // ------------------------------------------------------------------ receipt

  group('offline receipt', () {
    test('marked OFFLINE with the offline number; VAT split from the cached price', () async {
      final q = OfflineQueue(MemoryBlobStore(), TestCipher());
      final sale = await q.addSale(tillCode: 'T2', tillUid: 'TILL1', build: sampleSale);
      final r = offlineReceipt(sale, tendered: [cash(2000)], minorUnits: 0);
      expect(r.invoice.soldOffline, isTrue);
      expect(r.invoice.awaitingSync, isTrue);
      expect(r.invoice.grossTotalAmount, 1180);
      expect(r.invoice.vatTotalAmount, 180);
      expect(r.changeDue, 820);

      final text = buildReceiptText(
          receipt: r,
          companyName: 'Tulia',
          branchName: 'Main',
          cashierName: 'Asha',
          width: kCols80mm,
          gift: false);
      expect(text, contains('*** OFFLINE SALE ***'));
      expect(text, contains(sale.offlineNumber));
      expect(text, contains('Sold offline.'));
    });

    test('a reprint after sync keeps the OFFLINE mark and shows both numbers', () async {
      SharedPreferences.setMockInitialValues({});
      final q = OfflineQueue(MemoryBlobStore(), TestCipher());
      final sale = await q.addSale(tillCode: 'T2', tillUid: 'TILL1', build: sampleSale);
      final journal = ReceiptJournal();
      await journal.add(offlineReceipt(sale, tendered: [cash(1180)], minorUnits: 0));
      await journal.markSynced(sale.offlineNumber, invoiceUid: 'INV1', invoiceNumber: 'SI-0042');

      final reprint = (await journal.recent()).first;
      expect(reprint.invoice.invoiceNumber, 'SI-0042');
      expect(reprint.invoice.offlineNumber, sale.offlineNumber);
      expect(reprint.invoice.awaitingSync, isFalse);
      final text = buildReceiptText(
          receipt: reprint,
          companyName: 'Tulia',
          branchName: 'Main',
          cashierName: '',
          width: kCols80mm,
          gift: false);
      expect(text, contains('*** OFFLINE SALE ***'));
      expect(text, contains('SI-0042'));
      expect(text, contains(sale.offlineNumber));
      expect(text, isNot(contains('Sold offline.')));
    });
  });

  // ------------------------------------------------------------------ model glue

  test('PosTill carries offlineAllowed and round-trips for the cache', () {
    final t = PosTill.fromJson({'id': '5', 'uid': 'TILL1', 'code': 'T2', 'offlineAllowed': true});
    expect(t.offlineAllowed, isTrue);
    expect(PosTill.fromJson(t.toJson()).offlineAllowed, isTrue);
    expect(PosTill.fromJson({'id': '5'}).offlineAllowed, isFalse);
  });

  test('offline shift key helper', () {
    expect(offlineShiftKeyOf(null), isNull);
    expect(offlineShiftKeyOf(shift('SESS1')), isNull);
    expect(offlineShiftKeyOf(shift('${kOfflineShiftPrefix}abc')), 'abc');
  });
}
