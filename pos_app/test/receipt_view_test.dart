import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:pos_app/features/receipt/receipt_view.dart';
import 'package:pos_app/models/auth.dart';
import 'package:pos_app/models/context.dart';
import 'package:pos_app/models/enums.dart';
import 'package:pos_app/models/pos.dart';
import 'package:pos_app/models/sale.dart';
import 'package:pos_app/state/app_controller.dart';

/// The "Sale complete" dialog renders the same text the printer gets, so the
/// screen and the paper cannot drift apart. This drives the real dialog and
/// fails on any layout overflow (Flutter reports those as test exceptions).
void main() {
  Receipt receiptOf({String? posSessionId, String? createdByName}) => Receipt(
        invoice: SalesInvoice(
          id: '1',
          uid: 'INV-UID',
          invoiceNumber: 'INV-0042',
          status: InvoiceStatus.finalised,
          customerId: '424242',
          customerName: null,
          agentName: null,
          currency: 'TZS',
          netTotalAmount: 2161.02,
          vatTotalAmount: 388.98,
          grossTotalAmount: 2550,
          taxSummary: null,
          finalisedAt: DateTime(2026, 10, 2, 8, 36, 15),
          notes: null,
          posSessionId: posSessionId,
          createdByName: createdByName,
        ),
        lines: [
          for (final (i, name, gross) in [
            (1, 'SCHWEPPES SODA', 1000.0),
            (2, 'DABAGA PREMIUM', 1200.0),
            (3, 'SHOPPERS WHITE BREAD LARGE LOAF 600G', 350.0),
          ])
            InvoiceLine(
              lineNo: i,
              productCode: 'P$i',
              productName: name,
              unitName: 'pcs',
              quantity: 1,
              unitPriceAmount: gross,
              lineDiscountAmount: 0,
              netAmount: gross / 1.18,
              vatAmount: gross - gross / 1.18,
              grossAmount: gross,
              vatRate: 18,
              vatStatus: VatStatus.standard,
            ),
        ],
        payments: [
          InvoicePayment(
              tenderType: TenderType.cash,
              amount: 3000,
              changeAmount: 450,
              reference: null),
        ],
        clientTxnId: 'txn',
        tenderedAmount: 3000,
      );

  final receipt = receiptOf();

  String receiptText(WidgetTester tester) => tester
      .widgetList<Text>(find.byType(Text))
      .map((t) => t.data ?? '')
      .firstWhere((s) => s.contains('RECEIPT NO:'));

  Future<void> open(WidgetTester tester, Size size,
      {Receipt? r, AppData? app, bool reprint = false}) async {
    tester.view.physicalSize = size;
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.reset);
    await tester.pumpWidget(ProviderScope(
      overrides: [
        appControllerProvider.overrideWith(() => _TestApp(app ?? _app()))
      ],
      child: MaterialApp(
        home: Consumer(
          builder: (context, ref, _) => Scaffold(
            body: Center(
              child: ElevatedButton(
                onPressed: () => showReceiptSheet(context, ref, r ?? receipt,
                    reprint: reprint),
                child: const Text('open'),
              ),
            ),
          ),
        ),
      ),
    ));
    await tester.tap(find.text('open'));
    await tester.pumpAndSettle();
  }

  for (final size in [const Size(1366, 768), const Size(800, 600)]) {
    testWidgets('shows the new layout without overflow at $size',
        (tester) async {
      await open(tester, size);
      expect(find.text('Sale complete'), findsOneWidget);
      final t = receiptText(tester);
      expect(t, contains('TEST CO'));
      expect(t, contains('CUSTOMER NAME:'));
      expect(t, contains('INV-0042'));
      expect(t, contains('Description'));
      expect(t, contains('TAX A-18%'));
      expect(t, contains('TOTAL INCL OF TAX:'));
      expect(t, contains('TZS 2,550.00'));
      expect(t, contains('CHANGE'));
      expect(t, isNot(contains('424242')));
      expect(tester.takeException(), isNull);
    });
  }

  testWidgets('gift toggle hides prices on screen and brings them back',
      (tester) async {
    await open(tester, const Size(1366, 768));

    await tester.tap(find.text('Gift receipt'));
    await tester.pumpAndSettle();
    var t = receiptText(tester);
    expect(t, contains('gift receipt - prices hidden'));
    expect(t, isNot(contains('TOTAL INCL OF TAX')));

    await tester.tap(find.text('Show prices'));
    await tester.pumpAndSettle();
    t = receiptText(tester);
    expect(t, contains('TOTAL INCL OF TAX:'));
    expect(tester.takeException(), isNull);
  });

  group('CASHIER line', () {
    testWidgets('names the person who rang the sale, not the one reprinting it',
        (tester) async {
      await open(tester, const Size(1366, 768),
          r: receiptOf(createdByName: 'Amina Mwanga'),
          app: _app(meName: 'Bakari Mbaga'),
          reprint: true);
      final t = receiptText(tester);
      expect(t, contains('Amina Mwanga'));
      expect(t, isNot(contains('Bakari Mbaga')));
    });

    testWidgets('an old reprint with no recorded name leaves the line off',
        (tester) async {
      await open(tester, const Size(1366, 768),
          app: _app(meName: 'Bakari Mbaga'), reprint: true);
      final t = receiptText(tester);
      expect(t, isNot(contains('CASHIER:')));
      expect(t, isNot(contains('Bakari Mbaga')));
    });

    testWidgets('straight after the sale it is the signed-in cashier',
        (tester) async {
      await open(tester, const Size(1366, 768),
          app: _app(meName: 'Bakari Mbaga'));
      expect(receiptText(tester), contains('Bakari Mbaga'));
    });
  });

  group('Refund / reverse button', () {
    const cashier = {Perms.saleVoid};
    const supervisor = {'SALES.INVOICE.VOID'};

    testWidgets('shows for a sale on the cashier\'s own open shift',
        (tester) async {
      await open(tester, const Size(1366, 768),
          r: receiptOf(posSessionId: '7'),
          app: _app(perms: cashier, shiftId: '7'));
      expect(find.text('Refund / reverse'), findsOneWidget);
    });

    testWidgets('is hidden from a cashier on a colleague\'s sale',
        (tester) async {
      await open(tester, const Size(1366, 768),
          r: receiptOf(posSessionId: '9'),
          app: _app(perms: cashier, shiftId: '7'));
      expect(find.text('Refund / reverse'), findsNothing);
    });

    testWidgets('shows to a supervisor on any sale', (tester) async {
      await open(tester, const Size(1366, 768),
          r: receiptOf(posSessionId: '9'),
          app: _app(perms: supervisor, shiftId: '7'));
      expect(find.text('Refund / reverse'), findsOneWidget);
    });

    testWidgets('an older server that sends no session leaves it to the server',
        (tester) async {
      await open(tester, const Size(1366, 768),
          app: _app(perms: cashier, shiftId: '7'));
      expect(find.text('Refund / reverse'), findsOneWidget);
    });
  });

  group('shouldKickDrawer', () {
    bool kick({
      bool configured = true,
      bool reprint = false,
      bool gift = false,
      bool reversed = false,
      bool alreadyOpened = false,
    }) =>
        shouldKickDrawer(
            configured: configured,
            reprint: reprint,
            gift: gift,
            reversed: reversed,
            alreadyOpened: alreadyOpened);

    test('opens on the first print of the sale itself', () {
      expect(kick(), isTrue);
    });
    test('never when the till is not set to open it', () {
      expect(kick(configured: false), isFalse);
    });
    test('never on a reprint, a gift receipt, a reversed sale or a second copy',
        () {
      expect(kick(reprint: true), isFalse);
      expect(kick(gift: true), isFalse);
      expect(kick(reversed: true), isFalse);
      expect(kick(alreadyOpened: true), isFalse);
    });
  });

  test('an EXPENSE payout is named as such, not folded into Paid out', () {
    expect(PosPayoutType.fromWire('EXPENSE'), PosPayoutType.expense);
    expect(PosPayoutType.expense.label, 'Expense');
  });
}

AppData _app({
  String meName = 'Test Cashier',
  Set<String> perms = const {},
  String? shiftId,
}) =>
    AppData(
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
        displayName: meName,
        isRoot: false,
        activeCompanyUid: 'C-1',
        activeBranchUid: 'B-1',
        permissions: perms,
      ),
      shift: shiftId == null
          ? null
          : PosSession(
              id: shiftId,
              uid: 'S-$shiftId',
              posTillId: '1',
              cashierId: '1',
              sessionNumber: 'S-0001',
              status: PosSessionStatus.open,
              openedAt: DateTime(2026, 10, 2, 7),
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
