import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:pos_app/core/api/api_client.dart';
import 'package:pos_app/core/api/api_exception.dart';
import 'package:pos_app/core/config/step_up_policy.dart';
import 'package:pos_app/features/receipt/receipt_text.dart';
import 'package:pos_app/features/receipt/receipt_view.dart';
import 'package:pos_app/features/receipt/refund_slip_text.dart';
import 'package:pos_app/features/receipt/return_items_dialog.dart';
import 'package:pos_app/models/auth.dart';
import 'package:pos_app/models/context.dart';
import 'package:pos_app/models/enums.dart';
import 'package:pos_app/models/pos.dart';
import 'package:pos_app/models/sale.dart';
import 'package:pos_app/models/sale_return.dart';
import 'package:pos_app/services/sale_service.dart';
import 'package:pos_app/state/app_controller.dart';
import 'package:pos_app/state/providers.dart';
import 'package:shared_preferences/shared_preferences.dart';

/// POS "refund by item" (ERP ADR-0072, owner rulings 2026-11-21): the cashier
/// returns chosen items / quantities, the refund is cash from the drawer, and
/// once anything has gone back the whole-sale Refund / reverse disappears.
void main() {
  ReturnableLine line({
    String uid = 'L1',
    String name = 'Widget',
    double sold = 3,
    double returned = 0,
    double gross = 3540,
    double grossReturned = 0,
  }) =>
      ReturnableLine(
        invoiceLineUid: uid,
        lineNo: 1,
        productCode: 'SKU-$uid',
        productName: name,
        unitName: 'Pieces',
        qtySold: sold,
        qtyReturned: returned,
        qtyRemaining: sold - returned,
        grossAmount: gross,
        grossReturned: grossReturned,
      );

  group('quantity clamping', () {
    test('whole units: never a fraction, never more than is left', () {
      final l = line(sold: 3, returned: 1);
      expect(l.allowsDecimals, isFalse);
      expect(clampReturnQty(l, 1.7), 1);
      expect(clampReturnQty(l, 5), 2);
      expect(clampReturnQty(l, 0), 0);
      expect(clampReturnQty(l, -2), 0);
    });

    test('a weighed line takes a fraction, to three places', () {
      final l = line(sold: 1.5);
      expect(l.allowsDecimals, isTrue);
      expect(clampReturnQty(l, 0.25), 0.25);
      expect(clampReturnQty(l, 0.12345), 0.123);
      expect(clampReturnQty(l, 2), 1.5);
    });

    test('a fully returned line takes nothing', () {
      final l = line(sold: 2, returned: 2);
      expect(l.fullyReturned, isTrue);
      expect(clampReturnQty(l, 1), 0);
    });
  });

  group('estimated refund', () {
    test('pro-rates the paid line by quantity', () {
      expect(estimateReturnGross(line(sold: 3, gross: 3540), 1), 1180);
      expect(estimateReturnGross(line(sold: 3, gross: 1000), 1), 333.33);
    });

    test('the last units take the exact remainder, as the server does', () {
      // 1000 over 3 units: 333.33 + 333.33 already back, the last is 333.34.
      final l = line(sold: 3, returned: 2, gross: 1000, grossReturned: 666.66);
      expect(estimateReturnGross(l, 1), 333.34);
    });

    test('adds up only the ticked lines with a quantity', () {
      final lines = [
        line(uid: 'A', sold: 3, gross: 3540),
        line(uid: 'B', sold: 2, gross: 500),
        line(uid: 'C', sold: 1, gross: 999),
      ];
      expect(estimateSelectionGross(lines, {'A': 2, 'B': 1, 'C': 0}), 2610);
    });
  });

  group('request body', () {
    test('only positive lines, in sale order, reason trimmed', () {
      final lines = [line(uid: 'A'), line(uid: 'B'), line(uid: 'C')];
      final body = buildReturnRequest(
        reason: '  damaged bottle ',
        authorisedByUid: 'MGR-UID',
        lines: lines,
        selected: {'C': 1, 'A': 2, 'B': 0},
      );
      expect(body, {
        'reason': 'damaged bottle',
        'authorisedByUid': 'MGR-UID',
        'lines': [
          {'invoiceLineUid': 'A', 'qty': 2.0},
          {'invoiceLineUid': 'C', 'qty': 1.0},
        ],
      });
    });

    test('a supervisor acting alone sends no authorisedByUid', () {
      final body = buildReturnRequest(
          reason: 'wrong size',
          authorisedByUid: null,
          lines: [line()],
          selected: {'L1': 1});
      expect(body.containsKey('authorisedByUid'), isFalse);
    });
  });

  group('SaleService', () {
    test('POSTs the return with the Idempotency-Key', () async {
      final api = _RecordingApi(postAnswer: _returnJson());
      final ret = await SaleService(api).returnItems(
          'INV-UID', {'reason': 'x', 'lines': []},
          idempotencyKey: 'KEY-1');
      expect(api.postPath, '/pos/sales/uid/INV-UID/returns');
      expect(api.postIdempotencyKey, 'KEY-1');
      expect(api.postBody, {'reason': 'x', 'lines': []});
      expect(ret.returnNo, 'PRT-0001');
      expect(ret.grossAmount, 1180);
      expect(ret.lines.single.qtyReturned, 1);
    });

    test('reads the returns summary and a single return', () async {
      final api = _RecordingApi(getAnswers: {
        '/pos/sales/uid/INV-UID/returns': _summaryJson(),
        '/pos/returns/uid/RET-UID': _returnJson(),
      });
      final s = await SaleService(api).getReturns('INV-UID');
      expect(s.returnable, isTrue);
      expect(s.lines.first.qtyRemaining, 2);
      expect(s.returns.single.returnNo, 'PRT-0001');
      final one = await SaleService(api).getReturn('RET-UID');
      expect(one.authorisedBy, 'Jane Manager');
    });

    test('an invoice carries hasReturns / returnedGross, a line qtyReturned', () {
      final inv = SalesInvoice.fromJson({
        'id': '1', 'uid': 'U', 'invoiceNumber': 'INV-1', 'status': 'FINALISED',
        'hasReturns': true, 'returnedGross': 1180,
      });
      expect(inv.hasReturns, isTrue);
      expect(inv.returnedGross, 1180);
      expect(SalesInvoice.fromJson(inv.toJson()).hasReturns, isTrue);
      expect(InvoiceLine.fromJson({'qtyReturned': 1}).qtyReturned, 1);
    });
  });

  test('the step-up asks for the same authority the server re-checks', () {
    final rule = stepUpRuleFor(GatedAction.saleReturn);
    expect(rule.requiresApproval, isTrue);
    expect(rule.permissionCode, 'SALES.INVOICE.VOID');
  });

  group('error text', () {
    ApiException fromStatus(int status, List<dynamic> errors) =>
        ApiException.fromResponse(Response(
          requestOptions: RequestOptions(path: '/x'),
          statusCode: status,
          data: {'data': null, 'errors': errors},
        ));

    test('a 403 shows the server sentence (fetch a supervisor)', () {
      final e = fromStatus(403, [
        "This refund needs a supervisor's approval. Ask a supervisor to approve it at the till."
      ]);
      expect(returnErrorText(e), startsWith("This refund needs a supervisor's approval"));
    });

    test('a 409 shows the server sentence', () {
      final e = fromStatus(409, ['Only 2 of Widget can still be returned.']);
      expect(returnErrorText(e), 'Only 2 of Widget can still be returned.');
    });

    test('a bare 403 still reads as a permission refusal', () {
      expect(returnErrorText(fromStatus(403, [])),
          'You do not have permission for this action.');
    });
  });

  group('refund slip', () {
    final ret = PosSaleReturn.fromJson(_returnJson());

    for (final width in [kCols58mm, kCols80mm]) {
      test('fits $width columns and says what was refunded', () {
        final t = buildRefundSlipText(
            ret: ret, companyName: 'Test Co', branchName: 'HQ', width: width);
        for (final l in t.split('\n')) {
          expect(l.length, lessThanOrEqualTo(width), reason: l);
        }
        expect(t, contains('*** REFUND ***'));
        expect(t, contains('PRT-0001'));
        expect(t, contains('INV-0042'));
        expect(t, contains('Widget'));
        expect(t, contains('TZS 1,180.00'));
        expect(t, contains('damaged bottle'));
        expect(t, contains('Asha Cashier'));
        expect(t, contains('Jane Manager'));
        expect(t, contains('Not a fiscal receipt.'));
        expect(t, isNot(contains('VERIFICATION')));
      });
    }

    test('a reprint is marked as a copy', () {
      final t = buildRefundSlipText(
          ret: ret, companyName: 'Test Co', branchName: '', width: 48, copy: true);
      expect(t, contains('REFUND - COPY'));
    });

    test('a supervisor acting alone is named as the approver', () {
      final self = PosSaleReturn.fromJson({..._returnJson(), 'authorisedBy': null});
      final t = buildRefundSlipText(
          ret: self, companyName: 'Test Co', branchName: '', width: 48);
      expect(t, contains(leftRight('APPROVED BY:', 'Asha Cashier', 48)));
    });

    test('never opens the drawer', () {
      final bytes = buildRefundSlipBytes(
          ret: ret, companyName: 'Test Co', branchName: '', width: 48, mode: 'escpos');
      final kick = escDrawerKick.join(',');
      expect(bytes.join(',').contains(kick), isFalse);
    });
  });

  group('receipt dialog', () {
    const cashier = {Perms.saleVoid};

    testWidgets('a cashier on their own shift gets Return items beside Refund / reverse',
        (t) async {
      await _openReceipt(t, _receipt(), _app(cashier), _FakeSales());
      expect(find.text('Return items'), findsOneWidget);
      expect(find.text('Refund / reverse'), findsOneWidget);
      expect(t.takeException(), isNull);
    });

    testWidgets('after a return, Refund / reverse is gone and the amount shows',
        (t) async {
      await _openReceipt(
          t, _receipt(hasReturns: true, returnedGross: 1180), _app(cashier), _FakeSales());
      expect(find.text('Return items'), findsOneWidget);
      expect(find.text('Refund / reverse'), findsNothing);
      expect(find.text('Returned: TZS 1,180.00'), findsOneWidget);
      // The returns list loaded for reprinting.
      expect(find.textContaining('PRT-0001'), findsOneWidget);
      expect(find.byTooltip('Reprint slip'), findsOneWidget);
      expect(t.takeException(), isNull);
    });

    testWidgets('a colleague\'s sale offers neither to a cashier', (t) async {
      await _openReceipt(t, _receipt(session: '9'), _app(cashier), _FakeSales());
      expect(find.text('Return items'), findsNothing);
      expect(find.text('Refund / reverse'), findsNothing);
    });
  });

  group('Return items dialog', () {
    setUp(() => SharedPreferences.setMockInitialValues({}));
    const supervisor = {'SALES.INVOICE.VOID'};

    for (final size in [const Size(1366, 768), const Size(360, 740)]) {
      testWidgets('returns a chosen quantity and says how much cash to give at $size',
          (t) async {
        final sales = _FakeSales();
        await _openReceipt(t, _receipt(), _app(supervisor), sales, size: size);
        await t.tap(find.text('Return items'));
        await t.pumpAndSettle();

        // The fully returned line is shown but cannot be ticked.
        expect(find.text('Fully returned'), findsOneWidget);
        final soapBox = t.widget<Checkbox>(find.byKey(const Key('return-line-L2')));
        expect(soapBox.onChanged, isNull);

        await t.tap(find.byKey(const Key('return-line-L1')));
        await t.pumpAndSettle();
        // Ticking defaults to everything still returnable (2 of 3).
        expect(_qtyText(t, 'L1'), '2');
        await t.tap(find.byTooltip('One less'));
        await t.pumpAndSettle();
        expect(_qtyText(t, 'L1'), '1');
        expect(find.text('TZS 1,180.00'), findsWidgets);

        // Typing more than is left snaps back to the most that can go back.
        await t.enterText(find.byKey(const Key('return-qty-L1')), '7');
        await t.pumpAndSettle();
        expect(_qtyText(t, 'L1'), '2');
        await t.enterText(find.byKey(const Key('return-qty-L1')), '1');

        await t.enterText(find.widgetWithText(TextField, 'e.g. damaged bottle'),
            'damaged bottle');
        await t.pumpAndSettle();
        await t.ensureVisible(find.text('Return & refund'));
        await t.tap(find.text('Return & refund'));
        await t.pumpAndSettle();
        // A supervisor confirms instead of a second approval.
        await t.tap(find.descendant(
            of: find.byType(AlertDialog), matching: find.text('Return & refund')));
        await t.pumpAndSettle();

        expect(sales.posted.single.body, {
          'reason': 'damaged bottle',
          'lines': [
            {'invoiceLineUid': 'L1', 'qty': 1.0}
          ],
        });
        expect(sales.posted.single.key, isNotEmpty);
        expect(find.text('Give the customer'), findsOneWidget);
        expect(find.text('TZS 1,180.00'), findsWidgets);
        expect(t.takeException(), isNull);

        await t.tap(find.descendant(
            of: find.byType(ReturnItemsDialog), matching: find.text('Done')));
        await t.pumpAndSettle();
        // Back on the receipt: the whole-sale reverse is gone.
        expect(find.text('Refund / reverse'), findsNothing);
        expect(find.text('Returned: TZS 1,180.00'), findsOneWidget);
      });
    }

    testWidgets('no answer: Retry resends the same request under the same key',
        (t) async {
      final sales = _FakeSales(failFirstPost: true);
      await _openReceipt(t, _receipt(), _app(supervisor), sales);
      await t.tap(find.text('Return items'));
      await t.pumpAndSettle();
      await t.tap(find.byKey(const Key('return-line-L1')));
      await t.enterText(
          find.widgetWithText(TextField, 'e.g. damaged bottle'), 'wrong size');
      await t.pumpAndSettle();
      await t.tap(find.text('Return & refund'));
      await t.pumpAndSettle();
      await t.tap(find.descendant(
          of: find.byType(AlertDialog), matching: find.text('Return & refund')));
      await t.pumpAndSettle();

      expect(find.textContaining('Do not hand over any cash yet'), findsOneWidget);
      await t.tap(find.text('Retry'));
      await t.pumpAndSettle();

      expect(sales.posted, hasLength(2));
      expect(sales.posted[1].key, sales.posted[0].key);
      expect(sales.posted[1].body, sales.posted[0].body);
      expect(find.text('Give the customer'), findsOneWidget);
    });

    testWidgets('a sale that cannot take a return shows the server reason',
        (t) async {
      final sales = _FakeSales(blocked: 'This sale was not paid at the till.');
      await _openReceipt(t, _receipt(), _app(supervisor), sales);
      await t.tap(find.text('Return items'));
      await t.pumpAndSettle();
      expect(find.text('This sale was not paid at the till.'), findsOneWidget);
      expect(find.text('Return & refund'), findsNothing);
    });
  });
}

String _qtyText(WidgetTester t, String uid) =>
    t.widget<TextField>(find.byKey(Key('return-qty-$uid'))).controller!.text;

Map<String, dynamic> _returnJson() => {
      'uid': 'RET-UID',
      'returnNo': 'PRT-0001',
      'invoiceUid': 'INV-UID',
      'invoiceNumber': 'INV-0042',
      'reason': 'damaged bottle',
      'netAmount': 1000,
      'discountAmount': 0,
      'vatAmount': 180,
      'grossAmount': 1180,
      'currency': 'TZS',
      'refundTender': 'CASH',
      'authorisedBy': 'Jane Manager',
      'createdByName': 'Asha Cashier',
      'createdAt': '2026-11-21T09:14:03.512Z',
      'payoutUid': 'PAYOUT',
      'sessionUid': 'S-7',
      'lines': [
        {
          'lineNo': 1,
          'invoiceLineUid': 'L1',
          'productCode': 'SKU-001',
          'productName': 'Widget',
          'unitName': 'Pieces',
          'qtyReturned': 1,
          'netAmount': 1000,
          'discountAmount': 0,
          'vatAmount': 180,
          'grossAmount': 1180,
        }
      ],
    };

Map<String, dynamic> _summaryJson({String? blocked, bool withReturn = true}) => {
      'invoiceUid': 'INV-UID',
      'invoiceNumber': 'INV-0042',
      'hasReturns': withReturn,
      'returnedGross': withReturn ? 1180 : 0,
      'returnable': blocked == null,
      'returnBlockedReason': blocked,
      'lines': [
        {
          'invoiceLineUid': 'L1',
          'lineNo': 1,
          'productCode': 'SKU-001',
          'productName': 'Widget',
          'unitName': 'Pieces',
          'qtySold': 3,
          'qtyReturned': 1,
          'qtyRemaining': 2,
          'grossAmount': 3540,
          'grossReturned': 1180,
        },
        {
          'invoiceLineUid': 'L2',
          'lineNo': 2,
          'productCode': 'SKU-002',
          'productName': 'Bar soap',
          'unitName': 'Pieces',
          'qtySold': 1,
          'qtyReturned': 1,
          'qtyRemaining': 0,
          'grossAmount': 500,
          'grossReturned': 500,
        },
      ],
      'returns': withReturn ? [_returnJson()] : [],
    };

class _RecordingApi implements ApiClient {
  _RecordingApi({this.postAnswer, this.getAnswers = const {}});
  final Object? postAnswer;
  final Map<String, Object?> getAnswers;
  String? postPath;
  Object? postBody;
  String? postIdempotencyKey;

  @override
  Future<dynamic> post(String path,
      {Object? body,
      Map<String, dynamic>? query,
      String? idempotencyKey,
      String? xRequestId,
      bool noAuth = false}) async {
    postPath = path;
    postBody = body;
    postIdempotencyKey = idempotencyKey;
    return postAnswer;
  }

  @override
  Future<dynamic> get(String path, {Map<String, dynamic>? query}) async =>
      getAnswers[path];

  @override
  dynamic noSuchMethod(Invocation invocation) => super.noSuchMethod(invocation);
}

class _FakeSales implements SaleService {
  _FakeSales({this.failFirstPost = false, this.blocked});
  final bool failFirstPost;
  final String? blocked;
  final posted = <({Map<String, dynamic> body, String key})>[];

  @override
  Future<PosSaleReturns> getReturns(String invoiceUid) async =>
      PosSaleReturns.fromJson(_summaryJson(blocked: blocked));

  @override
  Future<PosSaleReturn> returnItems(String invoiceUid, Map<String, dynamic> body,
      {required String idempotencyKey}) async {
    posted.add((body: body, key: idempotencyKey));
    if (failFirstPost && posted.length == 1) {
      throw ApiException(statusCode: null, isTimeout: true, message: 'timeout');
    }
    return PosSaleReturn.fromJson(_returnJson());
  }

  @override
  Future<PosSaleReturn> getReturn(String returnUid) async =>
      PosSaleReturn.fromJson(_returnJson());

  @override
  dynamic noSuchMethod(Invocation invocation) => super.noSuchMethod(invocation);
}

Receipt _receipt({
  bool hasReturns = false,
  double returnedGross = 0,
  String session = '7',
}) =>
    Receipt(
      invoice: SalesInvoice(
        id: '1',
        uid: 'INV-UID',
        invoiceNumber: 'INV-0042',
        status: InvoiceStatus.finalised,
        customerId: '1',
        customerName: null,
        agentName: null,
        currency: 'TZS',
        netTotalAmount: 3423.73,
        vatTotalAmount: 616.27,
        grossTotalAmount: 4040,
        taxSummary: null,
        finalisedAt: DateTime(2026, 11, 21, 8, 30),
        notes: null,
        posSessionId: session,
        createdByName: 'Asha Cashier',
        hasReturns: hasReturns,
        returnedGross: returnedGross,
      ),
      lines: [
        InvoiceLine(
          lineNo: 1,
          productCode: 'SKU-001',
          productName: 'Widget',
          unitName: 'Pieces',
          quantity: 3,
          unitPriceAmount: 1180,
          lineDiscountAmount: 0,
          netAmount: 3000,
          vatAmount: 540,
          grossAmount: 3540,
          vatRate: 18,
          vatStatus: VatStatus.standard,
        ),
        InvoiceLine(
          lineNo: 2,
          productCode: 'SKU-002',
          productName: 'Bar soap',
          unitName: 'Pieces',
          quantity: 1,
          unitPriceAmount: 500,
          lineDiscountAmount: 0,
          netAmount: 423.73,
          vatAmount: 76.27,
          grossAmount: 500,
          vatRate: 18,
          vatStatus: VatStatus.standard,
        ),
      ],
      payments: [
        InvoicePayment(
            tenderType: TenderType.cash, amount: 4040, changeAmount: 0, reference: null),
      ],
      clientTxnId: 'txn',
      tenderedAmount: 4040,
    );

Future<void> _openReceipt(
    WidgetTester t, Receipt r, AppData app, SaleService sales,
    {Size size = const Size(1366, 768)}) async {
  t.view.physicalSize = size;
  t.view.devicePixelRatio = 1;
  addTearDown(t.view.reset);
  await t.pumpWidget(ProviderScope(
    overrides: [
      appControllerProvider.overrideWith(() => _TestApp(app)),
      saleServiceProvider.overrideWithValue(sales),
    ],
    child: MaterialApp(
      home: Consumer(
        builder: (context, ref, _) => Scaffold(
          body: Center(
            child: ElevatedButton(
              onPressed: () => showReceiptSheet(context, ref, r, reprint: true),
              child: const Text('open'),
            ),
          ),
        ),
      ),
    ),
  ));
  await t.tap(find.text('open'));
  await t.pumpAndSettle();
}

AppData _app(Set<String> perms) => AppData(
      context: PosContext(
        organisationUid: 'ORG',
        company: Company(id: '1', uid: 'C-1', name: 'Test Co'),
        branch: Branch(
            id: '1', uid: 'B-1', companyId: '1', companyUid: 'C-1',
            code: 'HQ', name: 'HQ', isDefault: true, status: 'ACTIVE'),
      ),
      me: Me(
        uid: 'U-1',
        username: 'tester',
        displayName: 'Asha Cashier',
        isRoot: false,
        activeCompanyUid: 'C-1',
        activeBranchUid: 'B-1',
        permissions: perms,
      ),
      shift: PosSession(
        id: '7',
        uid: 'S-7',
        posTillId: '1',
        cashierId: '1',
        sessionNumber: 'S-0001',
        status: PosSessionStatus.open,
        openedAt: DateTime(2026, 11, 21, 7),
        closedAt: null,
        reconciledAt: null,
        openingFloatAmount: 0,
        countedCashAmount: null,
        expectedCashAmount: null,
        varianceAmount: null,
        notes: null,
      ),
    );

class _TestApp extends AppController {
  _TestApp(this._data);
  final AppData _data;
  @override
  AppData build() => _data;
}
