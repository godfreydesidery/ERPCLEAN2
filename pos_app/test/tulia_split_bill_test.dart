import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:pos_app/features/payment/payment_sheet.dart';
import 'package:pos_app/models/enums.dart';
import 'package:pos_app/models/sale.dart';

/// Tulia 2026-11-03: "Split evenly" was a label that did nothing. Split bill
/// breaks one bill across cash / mobile / card / complimentary.
void main() {
  group('split bill rules', () {
    test('covered exactly is fine', () {
      expect(
          splitBillProblem(35000, {
            TenderType.cash: 10000,
            TenderType.card: 15000,
            TenderType.mobileMoney: 10000,
          }),
          isNull);
    });

    test('short says what is still to pay', () {
      expect(splitBillProblem(35000, {TenderType.cash: 10000, TenderType.card: 15000}),
          contains('10,000'));
    });

    test('cash over the bill is change', () {
      expect(splitBillProblem(35000, {TenderType.cash: 25000, TenderType.card: 15000}), isNull);
    });

    test('only cash gives change', () {
      expect(splitBillProblem(35000, {TenderType.cash: 5000, TenderType.card: 40000}),
          contains('Only cash gives change'));
    });

    test('nothing entered', () {
      expect(splitBillProblem(35000, const {}), isNotNull);
    });
  });

  testWidgets('10,000 cash + 15,000 card + the rest on mobile completes', (t) async {
    t.view.physicalSize = const Size(1280, 900);
    t.view.devicePixelRatio = 1;
    addTearDown(t.view.reset);
    List<PosTender>? result;
    await t.pumpWidget(MaterialApp(
      home: Builder(
        builder: (context) => TextButton(
          onPressed: () async =>
              result = await showSplitBill(context, due: 35000, currency: 'TZS'),
          child: const Text('go'),
        ),
      ),
    ));
    await t.tap(find.text('go'));
    await t.pumpAndSettle();

    await t.enterText(find.byKey(const ValueKey('split-CASH')), '10000');
    await t.enterText(find.byKey(const ValueKey('split-CARD')), '15000');
    await t.pump();
    expect(find.text('Remaining'), findsOneWidget);
    await t.tap(find.widgetWithText(InkWell, 'Complete sale'), warnIfMissed: false);
    await t.pumpAndSettle();
    expect(result, isNull, reason: 'nothing completes until the bill is covered');

    await t.tap(find.byKey(const ValueKey('split-rest-MOBILE_MONEY')));
    await t.pump();
    expect(find.text('Remaining'), findsNothing);
    await t.tap(find.text('Complete sale'));
    await t.pumpAndSettle();

    expect(result, isNotNull);
    expect({for (final p in result!) p.tenderType: p.amount}, {
      TenderType.cash: 10000,
      TenderType.mobileMoney: 10000,
      TenderType.card: 15000,
    });
  });

  testWidgets('fits a phone', (t) async {
    t.view.physicalSize = const Size(360, 700);
    t.view.devicePixelRatio = 1;
    addTearDown(t.view.reset);
    await t.pumpWidget(MaterialApp(
      home: Builder(
        builder: (context) => TextButton(
          onPressed: () => showSplitBill(context, due: 1250000, currency: 'TZS'),
          child: const Text('go'),
        ),
      ),
    ));
    await t.tap(find.text('go'));
    await t.pumpAndSettle();
    await t.enterText(find.byKey(const ValueKey('split-CASH')), '250000');
    await t.pump();
    expect(t.takeException(), isNull);
  });

  testWidgets('opening it again shows what was already split', (t) async {
    t.view.physicalSize = const Size(1280, 900);
    t.view.devicePixelRatio = 1;
    addTearDown(t.view.reset);
    await t.pumpWidget(MaterialApp(
      home: Builder(
        builder: (context) => TextButton(
          onPressed: () => showSplitBill(context, due: 35000, currency: 'TZS', initial: [
            PosTender(tenderType: TenderType.complimentary, amount: 5000),
          ]),
          child: const Text('go'),
        ),
      ),
    ));
    await t.tap(find.text('go'));
    await t.pumpAndSettle();
    final comp = t.widget<TextField>(find.byKey(const ValueKey('split-COMPLIMENTARY')));
    expect(comp.controller!.text, '5000');
  });
}
