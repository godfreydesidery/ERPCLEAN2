import 'dart:async';
import 'dart:typed_data';

import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_riverpod/misc.dart' show Override;
import 'package:pos_app/core/money.dart';
import 'package:pos_app/models/auth.dart';
import 'package:pos_app/models/catalog.dart';
import 'package:pos_app/models/context.dart';
import 'package:pos_app/models/enums.dart';
import 'package:pos_app/models/parties.dart';
import 'package:pos_app/models/pos.dart';
import 'package:pos_app/offline/blob_store.dart';
import 'package:pos_app/offline/offline_catalog.dart';
import 'package:pos_app/offline/offline_models.dart';
import 'package:pos_app/offline/offline_providers.dart';
import 'package:pos_app/offline/offline_queue.dart';
import 'package:pos_app/offline/secret_cipher.dart';
import 'package:pos_app/state/app_controller.dart';
import 'package:pos_app/state/cart_controller.dart';
import 'package:uuid/uuid.dart';

const _uuid = Uuid();

/// The test double for the device key store (DPAPI / Android Keystore): a
/// keyed, reversible scramble with an integrity byte, so a blob sealed under one
/// key does not open under another — like a queue copied to another Windows user.
class TestCipher implements SecretCipher {
  TestCipher({this.key = 7});
  final int key;

  static const _magic = [0x4F, 0x51, 0x31]; // "OQ1"

  @override
  String get name => 'test cipher';

  @override
  Future<Uint8List> seal(Uint8List plain) async => Uint8List.fromList([
        ..._magic,
        key & 0xFF,
        for (var i = 0; i < plain.length; i++) plain[i] ^ ((key * 31 + i * 7) & 0xFF),
      ]);

  @override
  Future<Uint8List> open(Uint8List sealed) async {
    if (sealed.length < 4 ||
        sealed[0] != _magic[0] ||
        sealed[1] != _magic[1] ||
        sealed[2] != _magic[2] ||
        sealed[3] != (key & 0xFF)) {
      throw SecretCipherException('This data was sealed with another key.');
    }
    final body = sealed.sublist(4);
    return Uint8List.fromList(
        [for (var i = 0; i < body.length; i++) body[i] ^ ((key * 31 + i * 7) & 0xFF)]);
  }
}

/// A key store that refuses everything (a device with none).
class BrokenCipher implements SecretCipher {
  const BrokenCipher();
  @override
  String get name => 'broken';
  @override
  Future<Uint8List> seal(Uint8List plain) async => throw SecretCipherException('No key store.');
  @override
  Future<Uint8List> open(Uint8List sealed) async => throw SecretCipherException('No key store.');
}

/// A disk whose next write waits until [release] — to prove what happens (and
/// what must NOT happen yet) while a sale is being stored.
class GatedStore extends MemoryBlobStore {
  Completer<void>? _gate;
  void hold() => _gate = Completer<void>();
  void release() {
    _gate?.complete();
    _gate = null;
  }

  @override
  Future<void> write(String key, Uint8List bytes) async {
    final g = _gate;
    if (g != null) await g.future;
    return super.write(key, bytes);
  }
}

/// A disk that cannot be written (full, read-only, yanked).
class FailingWriteStore implements BlobStore {
  @override
  Future<Uint8List?> read(String key) async => null;
  @override
  Future<void> write(String key, Uint8List bytes) async => throw StateError('disk full');
  @override
  Future<void> delete(String key) async {}
}

OfflineTender cash(double amount) => OfflineTender(type: TenderType.cash, amount: amount);

/// One sale of Sugar 1kg at a NET 1,000 + 18% VAT = 1,180, paid exactly in cash.
OfflineSale sampleSale(String offlineNumber, {String? clientKey}) => OfflineSale(
      idempotencyKey: _uuid.v4(),
      offlineNumber: offlineNumber,
      soldAt: DateTime.utc(2026, 11, 7, 9, 30),
      tillUid: 'TILL1',
      branchUid: 'BR1',
      sessionUid: clientKey == null ? 'SESS1' : null,
      sessionClientKey: clientKey,
      customerId: '7',
      customerName: 'Walk-in',
      currency: 'TZS',
      lines: const [
        OfflineLine(
          productId: '1',
          productUid: 'P1',
          productCode: 'SUG1',
          productName: 'Sugar 1kg',
          unitId: '2',
          unitName: 'Piece',
          quantity: 1,
          unitPrice: 1000,
          priceInclusive: false,
          vatFraction: 0.18,
          vatStatus: 'STANDARD',
        ),
      ],
      tenders: [cash(1180)],
      grossTotal: 1180,
      cashierName: 'Asha',
    );

PosSession shift(String uid) => PosSession(
      id: '',
      uid: uid,
      posTillId: '5',
      cashierId: 'U1',
      sessionNumber: 'PS-1',
      status: PosSessionStatus.open,
      openedAt: DateTime.utc(2026, 11, 7, 7),
      closedAt: null,
      reconciledAt: null,
      openingFloatAmount: 0,
      countedCashAmount: null,
      expectedCashAmount: null,
      varianceAmount: null,
      notes: null,
    );

final walkIn = Customer(
    id: '7',
    uid: 'CUST7',
    code: 'WALKIN',
    displayName: 'Walk-in',
    customerKind: 'CASH_WALK_IN',
    defaultCurrency: 'TZS',
    status: 'ACTIVE');

final piece = Unit(
    id: '2', uid: 'U-PCS', code: 'PCS', name: 'Piece', symbol: null, decimalPlaces: 0, fractional: false);

final sugar = Product(
  id: '1',
  uid: 'P1',
  code: 'SUG1',
  name: 'Sugar 1kg',
  sellable: true,
  baseUnitUid: 'U-PCS',
  baseUnitCode: 'PCS',
  baseUnitName: 'Piece',
  cost: Money.fromJson(const {'amount': '700', 'currency': 'TZS'}),
  vatStatus: 'STANDARD',
  restrictedKind: RestrictedKind.none,
  status: 'ACTIVE',
);

PosTill till({bool allowed = true}) => PosTill(
      id: '5',
      uid: 'TILL1',
      companyId: '10',
      branchId: '20',
      code: 'T2',
      name: 'Till 2',
      cashBankAccountId: null,
      status: 'ACTIVE',
      hasOpenSession: true,
      openSessionUid: 'SESS1',
      openSessionCashierId: 'U1',
      offlineAllowed: allowed,
    );

AppData registerApp({bool tillAllowed = true}) => AppData(
      phase: AppPhase.register,
      me: Me(
        uid: 'U1',
        username: 'asha',
        displayName: 'Asha',
        isRoot: false,
        activeCompanyUid: 'CO1',
        activeBranchUid: 'BR1',
        permissions: const {'POS.SALE.CREATE', 'POS.SESSION.OPEN', 'POS.SESSION.CLOSE'},
      ),
      context: PosContext(
        organisationUid: 'ORG1',
        company: Company(id: '10', uid: 'CO1', name: 'Tulia'),
        branch: Branch(
            id: '20',
            uid: 'BR1',
            companyId: '10',
            companyUid: 'CO1',
            code: 'MAIN',
            name: 'Main',
            isDefault: true,
            status: 'ACTIVE'),
      ),
      shift: shift('SESS1'),
      cashierId: 'U1',
      defaultCustomer: walkIn,
      unitsByUid: {'U-PCS': piece},
      currency: 'TZS',
      vatRates: const {'STANDARD': 0.18},
      till: till(allowed: tillAllowed),
    );

class StubApp extends AppController {
  StubApp(this._data);
  final AppData _data;
  @override
  AppData build() => _data;
}

/// A signed-in cashier at the register of till T2, with the server reachable
/// or not, over an in-memory queue sealed with [TestCipher].
class OfflineFixture {
  OfflineFixture({
    required this.reachable,
    bool tillAllowed = true,
    SecretCipher? cipher,
    BlobStore? store,
    List<Override> extra = const [],
  }) : queue = OfflineQueue(store ?? MemoryBlobStore(), cipher ?? TestCipher()) {
    container = ProviderContainer(overrides: [
      appControllerProvider.overrideWith(() => StubApp(registerApp(tillAllowed: tillAllowed))),
      offlineQueueProvider.overrideWithValue(queue),
      offlineCatalogProvider.overrideWithValue(OfflineCatalog(MemoryBlobStore())),
      reachabilityProbeProvider.overrideWithValue(() async => reachable ? latency : null),
      ...extra,
    ]);
  }

  /// Whether the probe finds the server — flip it to simulate the line coming back.
  bool reachable;

  /// How fast the server answers the probe when [reachable].
  Duration latency = const Duration(milliseconds: 40);
  final OfflineQueue queue;
  late final ProviderContainer container;

  /// A walk-in basket of one Sugar 1kg priced from the cache (NET 1,000 + 18%).
  CartState pricedCart() {
    final cart = container.read(cartProvider.notifier);
    cart.start(customer: walkIn, currency: 'TZS');
    cart.addProduct(sugar, piece);
    cart.setLinePricing(container.read(cartProvider).lines.single.localId,
        amount: 1000, vatInclusive: false, vatFraction: 0.18, minorUnits: 0);
    return container.read(cartProvider);
  }

  void dispose() => container.dispose();
}
