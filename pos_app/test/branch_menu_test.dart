import 'package:dio/dio.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:pos_app/core/api/api_client.dart';
import 'package:pos_app/core/api/api_exception.dart';
import 'package:pos_app/core/api/token_manager.dart';
import 'package:pos_app/core/money.dart';
import 'package:pos_app/core/storage/secure_store.dart';
import 'package:pos_app/models/catalog.dart';
import 'package:pos_app/models/enums.dart';
import 'package:pos_app/offline/blob_store.dart';
import 'package:pos_app/offline/offline_catalog.dart';
import 'package:pos_app/offline/offline_catalog_service.dart';
import 'package:pos_app/services/catalog_service.dart';
import 'package:pos_app/state/price_cache.dart';
import 'package:pos_app/state/stock_cache.dart';

/// Branch menu and branch prices (ERP ADR-0073): the till asks for its branch's
/// menu only, and nothing it caches — the offline catalogue, the dropdown
/// prices, the stock hints — is ever shown at another branch.

Product _product(String n, {String? category}) => Product(
    id: n,
    uid: 'P-$n',
    code: 'CODE-$n',
    name: 'Product $n',
    sellable: true,
    baseUnitUid: 'U-PCS',
    baseUnitCode: 'PCS',
    baseUnitName: 'Pieces',
    cost: const Money(0, 'TZS'),
    vatStatus: 'STANDARD',
    restrictedKind: RestrictedKind.none,
    status: 'ACTIVE',
    category: category);

/// Records every GET the catalogue service makes and answers a list, or a
/// barcode for the barcode lookup.
class _RecordingApi extends ApiClient {
  _RecordingApi()
      : super(
            apiBase: 'http://erp.test/api/v1',
            tokens: TokenManager(apiBase: 'http://erp.test/api/v1', store: SecureStore()));

  final gets = <({String path, Map<String, dynamic> query})>[];

  @override
  Future<dynamic> get(String path, {Map<String, dynamic>? query}) async {
    gets.add((path: path, query: {...?query}));
    if (path == '/products/barcode-lookup') {
      return {'uid': 'B1', 'productId': '1', 'productUid': 'P-1', 'barcode': query?['barcode']};
    }
    return const [];
  }

  Map<String, dynamic> queryOf(String path) => gets.lastWhere((g) => g.path == path).query;
}

/// A server whose menu and prices depend on the branch the till is at.
class _BranchServer implements CatalogService {
  String branch = 'BR1';
  final pageCalls = <int>[];
  final barcodeSweeps = <String>[];
  bool categoriesFail = false;

  Map<String, List<Product>> menus = {
    'BR1': [_product('1', category: 'Drinks'), _product('2', category: 'Food')],
    'BR2': [_product('1', category: 'Drinks'), _product('3', category: 'Grill')],
  };
  Map<String, double> priceAt = {'BR1': 1000, 'BR2': 1500};

  @override
  Future<List<Product>> listProductsPage(String companyId, {required int page, int size = 200}) async {
    pageCalls.add(page);
    return page == 0 ? menus[branch]! : const [];
  }

  @override
  Future<List<String>> listCategories(String companyId) async {
    if (categoriesFail) throw ApiException(statusCode: 500, message: 'boom');
    return {for (final p in menus[branch]!) p.category!}.toList();
  }

  @override
  Future<List<ProductBarcode>> listBarcodes(String productUid) async {
    barcodeSweeps.add(productUid);
    return [
      ProductBarcode(
          uid: 'B-$productUid',
          productId: productUid,
          productUid: productUid,
          barcode: 'BC-$productUid',
          barcodeType: 'EAN13',
          uomId: null,
          primary: true,
          derivedQuantity: null,
          derivedAmount: null,
          valueKind: null),
    ];
  }

  @override
  Future<List<ProductPack>> listPacks(String productUid) async => const [];

  @override
  Future<List<ResolvedUnitPrice>> resolvePrices(List<String> productUids,
          {String? unitUid, String? customerUid, String? currency}) async =>
      [
        for (final uid in productUids)
          ResolvedUnitPrice(
              productUid: uid,
              unitUid: unitUid ?? 'U-PCS',
              status: UnitPriceStatus.resolved,
              amount: priceAt[branch],
              currency: 'TZS'),
      ];

  @override
  Future<Map<String, StockLevel>> stockByProduct({String? q, int page = 0, int size = 200}) async =>
      {'1': StockLevel(quantity: branch == 'BR1' ? 5 : 9)};

  @override
  dynamic noSuchMethod(Invocation invocation) => super.noSuchMethod(invocation);
}

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  group('the till asks for its branch menu only (soldHere=true)', () {
    test('search, offline paging, categories and barcode lookup all send soldHere', () async {
      final api = _RecordingApi();
      final svc = CatalogService(api);

      await svc.searchProducts('10', q: 'soda', category: 'Drinks');
      expect(api.queryOf('/products'), containsPair('soldHere', true));
      expect(api.queryOf('/products'), containsPair('q', 'soda'));

      await svc.listProductsPage('10', page: 3);
      expect(api.queryOf('/products'), containsPair('soldHere', true));
      expect(api.queryOf('/products'), containsPair('sort', 'id,asc'));

      await svc.listCategories('10');
      expect(api.queryOf('/products/categories'), {'companyId': '10', 'soldHere': true});

      await svc.lookupBarcode('10', '6001234567890');
      expect(api.queryOf('/products/barcode-lookup'),
          {'companyId': '10', 'barcode': '6001234567890', 'soldHere': true});
    });

    test('the offline-aware service (what the registers use) sends it too when online', () async {
      final api = _RecordingApi();
      final svc = OfflineAwareCatalogService(api,
          cache: OfflineCatalog(MemoryBlobStore()), isOffline: () => false);
      await svc.searchProducts('10', q: 'x');
      await svc.listCategories('10');
      await svc.lookupBarcode('10', '123');
      for (final g in api.gets) {
        expect(g.query['soldHere'], isTrue, reason: g.path);
      }
      expect(api.gets, hasLength(3));
    });
  });

  test('a BRANCH_PRICE row parses as an ordinary resolved price', () {
    final r = ResolvedUnitPrice.fromJson({
      'productUid': 'P-1',
      'unitUid': 'U-PCS',
      'amount': 1500,
      'currency': 'TZS',
      'vatInclusive': true,
      'status': 'RESOLVED',
      'priceSource': 'BRANCH_PRICE',
      'priceListUid': null,
      'priceListName': 'Branch price',
    });
    expect(r.isResolved, isTrue);
    expect(r.amount, 1500);
    expect(r.vatInclusive, isTrue);
  });

  group('a sale refused as not on the branch menu offers no discount approval', () {
    // The server's answer to POST /pos/sales (PosSaleController.handlePosSaleFlow).
    ApiException refusal(String? errorCode, String text) => ApiException.fromResponse(Response(
          requestOptions: RequestOptions(path: '/pos/sales'),
          statusCode: 422,
          headers: Headers.fromMap({
            kPosSaleStatusHeader: ['REJECTED'],
          }),
          data: {
            'data': {'code': 'REJECTED', 'message': text, 'errorCode': errorCode},
            'errors': [text],
            'meta': null,
          },
        ));

    const menuText = 'Soda is not sold at this branch. Remove it from the sale, or ask a manager '
        'to turn it on in Product Master.';

    test("the refusal carries the rule's token and the server's own words", () {
      final e = refusal(kNotOnBranchMenu, menuText);
      expect(e.errorCode, 'NOT_ON_BRANCH_MENU');
      expect(e.code, 'REJECTED');
      expect(e.message, menuText);
      expect(refusal(null, 'Short tender.').errorCode, isNull);
    });

    test('NOT_ON_BRANCH_MENU never offers discount approval, even with a discount in the basket', () {
      final e = refusal(kNotOnBranchMenu, menuText);
      expect(
          refusalOffersDiscountApproval(
              status: PosSaleFlowStatus.resolve(e.code, e.statusCode),
              errorCode: e.errorCode,
              hasUnapprovedDiscount: true),
          isFalse);
    });

    test('a discount refusal still offers it', () {
      for (final code in ['DISCOUNT_APPROVAL_REQUIRED', null]) {
        final e = refusal(code, 'The discount needs a manager.');
        expect(
            refusalOffersDiscountApproval(
                status: PosSaleFlowStatus.resolve(e.code, e.statusCode),
                errorCode: e.errorCode,
                hasUnapprovedDiscount: true),
            isTrue,
            reason: '$code');
        expect(
            refusalOffersDiscountApproval(
                status: PosSaleFlowStatus.resolve(e.code, e.statusCode),
                errorCode: e.errorCode,
                hasUnapprovedDiscount: false),
            isFalse,
            reason: 'no discount to approve');
      }
    });
  });

  group('the offline catalogue is per branch', () {
    Future<CatalogSnapshot> build(_BranchServer server, CatalogSnapshot previous, String branch) {
      server.branch = branch;
      return OfflineCatalogRefresher(server, clock: () => DateTime.utc(2026, 11, 24, 8))
          .build(
        previous: previous,
        companyId: '10',
        branchUid: branch,
        currency: 'TZS',
        walkIn: null,
        unitsByUid: const {},
        vatRates: const {},
      );
    }

    test('a rebuild at another branch keeps nothing of the old branch', () async {
      final server = _BranchServer();
      final atOne = await build(server, const CatalogSnapshot(), 'BR1');
      expect(atOne.branchUid, 'BR1');
      expect(atOne.products.keys, unorderedEquals(['P-1', 'P-2']));
      expect(atOne.barcodes.keys, unorderedEquals(['BC-P-1', 'BC-P-2']));
      expect(atOne.prices[CatalogSnapshot.priceKey('P-1', null)]!.amount, 1000);

      server.barcodeSweeps.clear();
      final atTwo = await build(server, atOne, 'BR2');
      expect(atTwo.branchUid, 'BR2');
      expect(atTwo.products.keys, unorderedEquals(['P-1', 'P-3']));
      // A new branch is swept at once — not up to a day later — so its own
      // items scan offline.
      expect(server.barcodeSweeps, unorderedEquals(['P-1', 'P-3']));
      expect(atTwo.barcodes.keys, unorderedEquals(['BC-P-1', 'BC-P-3']));
      expect(atTwo.prices[CatalogSnapshot.priceKey('P-1', null)]!.amount, 1500,
          reason: "the branch's own price");
      expect(atTwo.categories, unorderedEquals(['Drinks', 'Grill']));
    });

    test('the same branch keeps its daily sweep', () async {
      final server = _BranchServer();
      final first = await build(server, const CatalogSnapshot(), 'BR1');
      server.barcodeSweeps.clear();
      await build(server, first, 'BR1');
      expect(server.barcodeSweeps, isEmpty);
    });

    test("categories that cannot be read never fall back to another branch's", () async {
      final server = _BranchServer();
      final atOne = await build(server, const CatalogSnapshot(), 'BR1');
      server.categoriesFail = true;
      final atTwo = await build(server, atOne, 'BR2');
      expect(atTwo.categories, ['Drinks', 'Grill'], reason: "this branch's products' own");
      final again = await build(server, atTwo, 'BR2');
      expect(again.categories, ['Drinks', 'Grill'], reason: "the last ones, this branch's");
    });

    test("offline, a cache built at another branch answers as an empty menu", () async {
      final server = _BranchServer();
      final cache = OfflineCatalog(MemoryBlobStore());
      await cache.save(await build(server, const CatalogSnapshot(), 'BR1'));

      var branch = 'BR2';
      final api = _RecordingApi();
      final svc = OfflineAwareCatalogService(api,
          cache: cache, isOffline: () => true, activeBranchUid: () => branch);

      expect(await svc.searchProducts('10', q: 'Product'), isEmpty);
      expect(await svc.listCategories('10'), isEmpty);
      expect(await svc.resolvePrices(['P-1']), isEmpty);
      expect(await svc.listPacks('P-1'), isEmpty);
      await expectLater(
          svc.lookupBarcode('10', 'BC-P-1'),
          throwsA(isA<ApiException>()
              .having((e) => e.statusCode, 'status', 404)
              .having((e) => e.errors, 'errors', isNotEmpty)
              .having((e) => e.message, 'message', contains('no saved menu for this branch'))));
      await expectLater(svc.getProduct('P-1'), throwsA(isA<ApiException>()));
      expect(api.gets, isEmpty, reason: 'offline: nothing goes to the server');

      // At the branch it was built for it sells as before.
      branch = 'BR1';
      expect((await svc.searchProducts('10', q: 'Product')).map((p) => p.uid),
          unorderedEquals(['P-1', 'P-2']));
      expect((await svc.resolvePrices(['P-1'])).single.amount, 1000);
      expect((await svc.lookupBarcode('10', 'BC-P-2')).productUid, 'P-2');
    });

    test("online, a barcode is not learnt into another branch's cache", () async {
      final server = _BranchServer();
      final cache = OfflineCatalog(MemoryBlobStore());
      await cache.save(await build(server, const CatalogSnapshot(), 'BR1'));
      final svc = OfflineAwareCatalogService(_RecordingApi(),
          cache: cache, isOffline: () => false, activeBranchUid: () => 'BR2');
      await svc.lookupBarcode('10', 'NEW-CODE');
      expect(cache.snapshot.barcodes.containsKey('NEW-CODE'), isFalse);
    });

    test('servesBranch', () async {
      final cache = OfflineCatalog(MemoryBlobStore());
      await cache.save(const CatalogSnapshot(branchUid: 'BR1'));
      expect(cache.servesBranch('BR1'), isTrue);
      expect(cache.servesBranch('BR2'), isFalse);
      expect(cache.servesBranch(null), isTrue, reason: 'no branch known: no restriction');
    });
  });

  group('the dropdown prices and stock hints are per branch', () {
    test('prices read at one branch are not shown at another', () async {
      final server = _BranchServer();
      var branch = 'BR1';
      final cache = PriceCache(server, branchUid: () => branch);

      server.branch = 'BR1';
      await cache.refreshFor(['P-1'], currency: 'TZS');
      expect(cache.priceFor('P-1')!.amount, 1000);

      // Signed in at BR2: the BR1 answer is gone at once, not after the TTL.
      branch = 'BR2';
      server.branch = 'BR2';
      expect(cache.priceFor('P-1'), isNull);
      await cache.refreshFor(['P-1'], currency: 'TZS');
      expect(cache.priceFor('P-1')!.amount, 1500);
    });

    test('a price answer that lands after a branch change is dropped', () async {
      final server = _BranchServer();
      var branch = 'BR1';
      final cache = PriceCache(server, branchUid: () => branch);
      final pending = cache.refreshFor(['P-1'], currency: 'TZS');
      branch = 'BR2';
      expect(cache.priceFor('P-1'), isNull);
      await pending;
      expect(cache.priceFor('P-1'), isNull, reason: "that answer was BR1's price");
    });

    test('stock read at one branch is forgotten at another', () async {
      final server = _BranchServer();
      var branch = 'BR1';
      final stock = StockCache(server, branchUid: () => branch);

      await stock.refreshFor('soda');
      expect(stock.levelForProduct('1')!.quantity, 5);
      expect(stock.levelFor('2', query: 'soda')!.quantity, 0, reason: 'absent = out of stock');

      branch = 'BR2';
      server.branch = 'BR2';
      expect(stock.levelForProduct('1'), isNull);
      expect(stock.levelFor('2', query: 'soda'), isNull, reason: 'not asked at this branch yet');
      await stock.refreshFor('soda');
      expect(stock.levelForProduct('1')!.quantity, 9);
    });
  });
}
