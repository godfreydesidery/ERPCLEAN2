// ADR-0073: the item's entry at the active branch — sold here, price here —
// read from GET /products/uid/{uid}/branches and written with
// PUT /products/uid/{uid}/branch-menu.
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';

import 'package:orbix_hq/app/theme.dart';
import 'package:orbix_hq/features/edit_item_screen.dart';
import 'package:orbix_hq/services/catalog_service.dart';

List<BranchMenuRow> _rows(List<Map<String, dynamic>> json) =>
    json.map(BranchMenuRow.fromJson).toList();

void main() {
  group('load mapping', () {
    test('no rows: sold here at the company price (sold everywhere)', () {
      final e = branchMenuFor(const [], '7');
      expect(e, isNotNull);
      expect(e!.soldHere, isTrue);
      expect(e.price, isNull);
    });

    test('no rows needs no branch id', () {
      expect(branchMenuFor(const [], null)!.soldHere, isTrue);
    });

    test('rows, but none for this branch: not sold here', () {
      final rows = _rows(const [
        {'branchId': '3', 'active': true, 'branchPrice': null},
      ]);
      final e = branchMenuFor(rows, '7')!;
      expect(e.soldHere, isFalse);
      expect(e.price, isNull);
    });

    test('a row for this branch with a price (ids arrive as strings)', () {
      final rows = _rows(const [
        {'branchId': '3', 'active': true, 'branchPrice': null},
        {'branchId': '7', 'active': true, 'branchPrice': '1500.0000'},
      ]);
      final e = branchMenuFor(rows, '7')!;
      expect(e.soldHere, isTrue);
      expect(e.price, 1500);
    });

    test('an inactive row for this branch: not sold here', () {
      final rows = _rows(const [
        {'branchId': 7, 'active': false, 'branchPrice': 900},
      ]);
      final e = branchMenuFor(rows, '7')!;
      expect(e.soldHere, isFalse);
      expect(e.price, 900);
    });

    test('rows but the branch id is unknown: cannot be told', () {
      final rows = _rows(const [
        {'branchId': '3', 'active': true},
      ]);
      expect(branchMenuFor(rows, null), isNull);
      expect(branchMenuFor(rows, ''), isNull);
    });
  });

  group('request body', () {
    const sold = BranchMenuEntry(soldHere: true);
    const priced = BranchMenuEntry(soldHere: true, price: 1500);

    test('nothing changed: no call', () {
      expect(
        branchMenuBody(
          branchUid: 'B1',
          before: sold,
          soldHere: true,
          price: null,
        ),
        isNull,
      );
      expect(
        branchMenuBody(
          branchUid: 'B1',
          before: priced,
          soldHere: true,
          price: 1500,
        ),
        isNull,
      );
    });

    test('setting a price sends only the price', () {
      expect(
        branchMenuBody(
          branchUid: 'B1',
          before: sold,
          soldHere: true,
          price: 1800,
        ),
        {'branchUid': 'B1', 'price': 1800},
      );
    });

    test('a fractional price is sent as is', () {
      expect(
        branchMenuBody(
          branchUid: 'B1',
          before: sold,
          soldHere: true,
          price: 62.5,
        ),
        {'branchUid': 'B1', 'price': 62.5},
      );
    });

    test('changing a price sends the new price', () {
      expect(
        branchMenuBody(
          branchUid: 'B1',
          before: priced,
          soldHere: true,
          price: 2000,
        ),
        {'branchUid': 'B1', 'price': 2000},
      );
    });

    test('emptying a set price sends clearPrice', () {
      expect(
        branchMenuBody(
          branchUid: 'B1',
          before: priced,
          soldHere: true,
          price: null,
        ),
        {'branchUid': 'B1', 'clearPrice': true},
      );
    });

    test('turning sold here off sends soldHere false only', () {
      expect(
        branchMenuBody(
          branchUid: 'B1',
          before: priced,
          soldHere: false,
          price: 1500,
        ),
        {'branchUid': 'B1', 'soldHere': false},
      );
    });

    test('turning sold here on and pricing it in one save', () {
      expect(
        branchMenuBody(
          branchUid: 'B1',
          before: const BranchMenuEntry(soldHere: false),
          soldHere: true,
          price: 1200,
        ),
        {'branchUid': 'B1', 'soldHere': true, 'price': 1200},
      );
    });
  });

  group('when the section shows', () {
    test('hidden for a user with one branch (or none)', () {
      expect(showBranchMenu(branchCount: 1, canView: true), isFalse);
      expect(showBranchMenu(branchCount: 0, canView: true), isFalse);
    });

    test('shown with two or more branches', () {
      expect(showBranchMenu(branchCount: 2, canView: true), isTrue);
      expect(showBranchMenu(branchCount: 5, canView: true), isTrue);
    });

    test('hidden without PRODUCT.VIEW', () {
      expect(showBranchMenu(branchCount: 3, canView: false), isFalse);
    });
  });

  group('done sheet title', () {
    test('names what did not save', () {
      expect(
        savedTitle(priceFailed: false, branchFailed: false, branchName: 'A'),
        'Item saved',
      );
      expect(
        savedTitle(priceFailed: true, branchFailed: false, branchName: 'A'),
        'Saved, except the price',
      );
      expect(
        savedTitle(
          priceFailed: false,
          branchFailed: true,
          branchName: 'Mikocheni',
        ),
        'Saved, except at Mikocheni',
      );
      expect(
        savedTitle(
          priceFailed: true,
          branchFailed: true,
          branchName: 'Mikocheni',
        ),
        'Saved, except the price and at Mikocheni',
      );
    });
  });

  group('section', () {
    Future<void> pump(
      WidgetTester tester,
      Widget child, {
      Size size = const Size(360, 640),
    }) async {
      tester.view.physicalSize = size;
      tester.view.devicePixelRatio = 1.0;
      addTearDown(tester.view.reset);
      await tester.pumpWidget(
        MaterialApp(
          theme: buildHqTheme(),
          home: Scaffold(
            body: ListView(
              padding: const EdgeInsets.all(20),
              children: [child],
            ),
          ),
        ),
      );
    }

    testWidgets('shows the branch, the switch and the price field', (
      tester,
    ) async {
      var sold = true;
      final price = TextEditingController();
      addTearDown(price.dispose);
      await pump(
        tester,
        StatefulBuilder(
          builder: (context, setState) => BranchMenuSection(
            branchName: 'Mikocheni Beach Restaurant and Lounge Bar',
            soldHere: sold,
            onSoldHereChanged: (v) => setState(() => sold = v),
            priceController: price,
            unit: 'PCS',
            currency: 'TZS',
            companyPrice: 1500,
          ),
        ),
      );
      expect(
        find.text('AT MIKOCHENI BEACH RESTAURANT AND LOUNGE BAR'),
        findsOneWidget,
      );
      expect(find.text('Sold here'), findsOneWidget);
      expect(find.text('Price here per PCS'), findsOneWidget);
      expect(find.text('Empty = company price'), findsOneWidget);
      expect(find.text('Company price 1,500'), findsOneWidget);
      expect(tester.takeException(), isNull);

      await tester.tap(find.text('Sold here'));
      await tester.pump();
      expect(sold, isFalse);
      expect(find.text('Not offered at this branch'), findsOneWidget);
    });

    testWidgets('read-only without the permission', (tester) async {
      final price = TextEditingController(text: '1800');
      addTearDown(price.dispose);
      await pump(
        tester,
        BranchMenuSection(
          branchName: 'Main',
          soldHere: true,
          onSoldHereChanged: null,
          priceController: price,
          unit: 'PCS',
          currency: 'TZS',
          note: 'You can see, but not change, what this branch sells.',
        ),
      );
      final sw = tester.widget<Switch>(find.byType(Switch));
      expect(sw.onChanged, isNull);
      final field = tester.widget<TextField>(find.byType(TextField));
      expect(field.readOnly, isTrue);
      expect(
        find.text('You can see, but not change, what this branch sells.'),
        findsOneWidget,
      );
    });

    testWidgets('not loaded: only the note, no guessed switch', (tester) async {
      final price = TextEditingController();
      addTearDown(price.dispose);
      await pump(
        tester,
        BranchMenuSection(
          branchName: 'Main',
          soldHere: true,
          onSoldHereChanged: null,
          priceController: price,
          unit: 'PCS',
          currency: 'TZS',
          loaded: false,
          note: "This branch's menu could not be read.",
        ),
      );
      expect(find.byType(Switch), findsNothing);
      expect(find.byType(TextField), findsNothing);
      expect(
        find.text("This branch's menu could not be read."),
        findsOneWidget,
      );
    });
  });
}
