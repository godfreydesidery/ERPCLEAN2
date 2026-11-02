import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:pos_app/features/dashboard/dashboard_screen.dart';
import 'package:pos_app/features/payment/payment_sheet.dart';
import 'package:pos_app/features/receipt/receipt_text.dart';
import 'package:pos_app/models/dashboard.dart';
import 'package:pos_app/models/enums.dart';
import 'package:pos_app/models/sale.dart';
import 'package:pos_app/widgets/category_bar.dart';

/// Tulia round 4 (2026-11-02): complimentary "given to", category buttons and
/// the sales dashboard.
void main() {
  group('complimentary given to', () {
    String receiptWith(List<InvoicePayment> payments) => buildReceiptText(
          receipt: Receipt(
            invoice: SalesInvoice(
              id: '1',
              uid: 'inv',
              invoiceNumber: 'INV-0001',
              status: InvoiceStatus.finalised,
              customerId: 'C1',
              customerName: 'Walk-in Customer',
              agentName: null,
              currency: 'TZS',
              netTotalAmount: 10000,
              vatTotalAmount: 0,
              grossTotalAmount: 10000,
              taxSummary: null,
              finalisedAt: DateTime(2026, 11, 2, 13),
              notes: null,
            ),
            lines: const [],
            payments: payments,
            clientTxnId: 'txn',
            tenderedAmount: null,
          ),
          companyName: 'Tulia',
          branchName: 'Main',
          cashierName: 'Cashier',
          width: kCols58mm,
          gift: false,
        );

    test('the receipt names who a complimentary was given to', () {
      final text = receiptWith([
        InvoicePayment(
            tenderType: TenderType.cash, amount: 4000, changeAmount: 0, reference: 'ignored'),
        InvoicePayment(
            tenderType: TenderType.complimentary,
            amount: 6000,
            changeAmount: 0,
            reference: ' Mr Juma '),
      ]);
      expect(text, contains('Given to: Mr Juma'));
      expect(text, isNot(contains('ignored')), reason: 'only a complimentary names someone');
    });

    test('a complimentary without a name prints as before', () {
      final text = receiptWith([
        InvoicePayment(
            tenderType: TenderType.complimentary,
            amount: 10000,
            changeAmount: 0,
            reference: null),
      ]);
      expect(text, contains('COMPLIMENTARY'));
      expect(text, isNot(contains('Given to')));
    });

    test('the name travels as the tender reference', () {
      final t = PosTender(
          tenderType: TenderType.complimentary,
          amount: 6000,
          reference: 'Mr Juma',
          authorisedByUid: 'MGR1');
      expect(t.toJson()['reference'], 'Mr Juma');
    });

    testWidgets('the name is required, and comes back trimmed', (t) async {
      String? result = 'unset';
      await t.pumpWidget(MaterialApp(
        home: Builder(
          builder: (context) => TextButton(
            onPressed: () async => result = await askComplimentaryGivenTo(context),
            child: const Text('go'),
          ),
        ),
      ));
      await t.tap(find.text('go'));
      await t.pumpAndSettle();
      final cont = find.widgetWithText(InkWell, 'Continue');
      await t.tap(cont, warnIfMissed: false);
      await t.pumpAndSettle();
      expect(find.text('Complimentary — given to'), findsOneWidget,
          reason: 'Continue does nothing until a name is typed');

      await t.enterText(find.byType(TextField), '  Mr Juma ');
      await t.pump();
      await t.tap(find.text('Continue'));
      await t.pumpAndSettle();
      expect(result, 'Mr Juma');
    });
  });

  group('category buttons', () {
    testWidgets('nothing at all when there are no categories', (t) async {
      await t.pumpWidget(MaterialApp(
          home: Scaffold(
              body: CategoryBar(categories: const [], selected: null, onSelected: (_) {}))));
      expect(find.byType(ChoiceChip), findsNothing);
    });

    testWidgets('a tap picks a category; the same again (or All) clears it', (t) async {
      final picked = <String?>[];
      Future<void> pump(String? selected) => t.pumpWidget(MaterialApp(
          home: Scaffold(
              body: CategoryBar(
                  categories: const ['Drinks', 'Food'],
                  selected: selected,
                  onSelected: picked.add))));

      await pump(null);
      expect(find.byType(ChoiceChip), findsNWidgets(3));
      await t.tap(find.text('Drinks'));
      await pump('Drinks');
      await t.tap(find.text('Drinks'));
      await t.tap(find.text('All'));
      expect(picked, ['Drinks', null, null]);
    });
  });

  group('sales dashboard', () {
    test('parses the server answer', () {
      final d = PosDashboard.fromJson({
        'fromDate': '2026-11-01',
        'toDate': '2026-11-02',
        'branchName': 'Main',
        'currency': 'TZS',
        'grossTotal': 2770,
        'invoiceCount': 2,
        'days': [
          {'date': '2026-11-01', 'invoices': 1, 'gross': 1180},
          {'date': '2026-11-02', 'invoices': 1, 'gross': 1590},
        ],
        'tenders': [
          {'tenderType': 'MOBILE_MONEY', 'payments': 1, 'amount': 1000},
        ],
        'departments': [
          {
            'department': 'Drinks',
            'quantity': 3,
            'gross': 1770,
            'items': [
              {'code': 'COKE', 'name': 'Coke', 'unit': 'PCS', 'quantity': 2, 'gross': 1180},
            ],
          },
        ],
      });
      expect(d.grossTotal, 2770);
      expect(d.days.map((x) => x.gross), [1180, 1590]);
      expect(d.departments.single.items.single.name, 'Coke');
      expect(d.tenders.single.amount, 1000);
    });

    test('payment types read as the till names them', () {
      expect(dashboardTenderLabel('MOBILE_MONEY'), 'Mobile money');
      expect(dashboardTenderLabel('COMPLIMENTARY'), 'Complimentary');
      expect(dashboardTenderLabel('BANK_TRANSFER'), 'BANK TRANSFER');
    });
  });
}
