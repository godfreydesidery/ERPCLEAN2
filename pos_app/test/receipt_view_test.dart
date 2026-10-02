import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:pos_app/features/receipt/receipt_view.dart';
import 'package:pos_app/models/context.dart';
import 'package:pos_app/models/enums.dart';
import 'package:pos_app/models/sale.dart';
import 'package:pos_app/state/app_controller.dart';

/// The "Sale complete" dialog renders the same text the printer gets, so the
/// screen and the paper cannot drift apart. This drives the real dialog and
/// fails on any layout overflow (Flutter reports those as test exceptions).
void main() {
  final receipt = Receipt(
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

  String receiptText(WidgetTester tester) => tester
      .widgetList<Text>(find.byType(Text))
      .map((t) => t.data ?? '')
      .firstWhere((s) => s.contains('RECEIPT NO:'));

  Future<void> open(WidgetTester tester, Size size) async {
    tester.view.physicalSize = size;
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.reset);
    await tester.pumpWidget(ProviderScope(
      overrides: [appControllerProvider.overrideWith(_TestApp.new)],
      child: MaterialApp(
        home: Consumer(
          builder: (context, ref, _) => Scaffold(
            body: Center(
              child: ElevatedButton(
                onPressed: () => showReceiptSheet(context, ref, receipt),
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
}

class _TestApp extends AppController {
  @override
  AppData build() => AppData(
        context: PosContext(
          organisationUid: 'ORG',
          company: Company(id: '1', uid: 'C-1', name: 'Test Co'),
          branch: Branch(
              id: '1', uid: 'B-1', companyId: '1', companyUid: 'C-1',
              code: 'HQ', name: 'HQ', isDefault: true, status: 'ACTIVE'),
        ),
      );
}
