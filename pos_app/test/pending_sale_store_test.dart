import 'package:flutter_test/flutter_test.dart';
import 'package:pos_app/models/pos.dart';
import 'package:pos_app/state/pending_sale_store.dart';
import 'package:shared_preferences/shared_preferences.dart';

/// The durable Idempotency-Key is the till's only protection against charging a
/// customer twice when the app dies mid-sale, so its persistence is tested, not
/// assumed.
void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  PendingSale sample() => PendingSale(
        txnId: 'txn-1',
        sessionUid: 'SESS1',
        body: const {
          'sessionUid': 'SESS1',
          'customerId': '7',
          'currency': 'TZS',
          'lines': [
            {'productId': '1', 'unitId': '2', 'quantity': 3},
          ],
        },
        startedAt: DateTime.utc(2026, 8, 1, 9, 15),
        amount: 12500.5,
        currency: 'TZS',
        tenderedAmount: 15000,
      );

  group('PendingSaleStore', () {
    setUp(() => SharedPreferences.setMockInitialValues({}));

    test('a saved attempt survives a fresh store (a new app launch)', () async {
      await PendingSaleStore().save(sample());

      final restored = await PendingSaleStore().read();

      expect(restored, isNotNull);
      expect(restored!.txnId, 'txn-1');
      expect(restored.sessionUid, 'SESS1');
      expect(restored.amount, 12500.5);
      expect(restored.currency, 'TZS');
      expect(restored.tenderedAmount, 15000);
      expect(restored.startedAt.toUtc(), DateTime.utc(2026, 8, 1, 9, 15));
      // The body must round-trip verbatim — it is replayed under the same key.
      expect(restored.body['customerId'], '7');
      expect((restored.body['lines'] as List).length, 1);
    });

    test('reads null when nothing is in flight', () async {
      expect(await PendingSaleStore().read(), isNull);
    });

    test('clear removes the slot so the next sale is not blocked', () async {
      final store = PendingSaleStore();
      await store.save(sample());
      await store.clear();

      expect(await store.read(), isNull);
    });

    test('a corrupt slot is dropped rather than blocking the till forever',
        () async {
      SharedPreferences.setMockInitialValues({'pos.pending_sale': 'not json'});

      expect(await PendingSaleStore().read(), isNull);
    });
  });

  group('PosTill.isHeldBy', () {
    PosTill till({String? cashierId}) => PosTill.fromJson({
          'id': '1',
          'uid': 'tilluid',
          'companyId': '10',
          'branchId': '20',
          'code': 'T1',
          'name': 'Till 1',
          'status': 'ACTIVE',
          'hasOpenSession': cashierId != null,
          'openSessionUid': cashierId == null ? null : 'SESS1',
          'openSessionCashierId': cashierId,
          'openSessionOpenedAt': '2026-08-01T09:15:00Z',
        });

    test('true when the open shift belongs to the signed-in cashier', () {
      expect(till(cashierId: '42').isHeldBy('42'), isTrue);
    });

    test('false when another cashier holds it', () {
      expect(till(cashierId: '7').isHeldBy('42'), isFalse);
    });

    test('false for a free till, and when the caller is unknown', () {
      expect(till().isHeldBy('42'), isFalse);
      expect(till(cashierId: '42').isHeldBy(null), isFalse);
    });

    test('exposes the occupying session so the picker can act on it', () {
      final t = till(cashierId: '42');
      expect(t.openSessionUid, 'SESS1');
      expect(t.openSessionOpenedAt, DateTime.utc(2026, 8, 1, 9, 15));
    });
  });
}
