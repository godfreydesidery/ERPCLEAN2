// The pack-size UI on a small handset.
//
// These screens are used standing in a store room on whatever phone the owner
// has. A conversion line that overflows, or one that says nothing until a
// number is typed, is the difference between a pack being set up correctly and
// a day of adjustments.
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';

import 'package:orbix_hq/app/theme.dart';
import 'package:orbix_hq/features/pack_sizes.dart';
import 'package:orbix_hq/features/unit_picker.dart';
import 'package:orbix_hq/services/catalog_service.dart';

const _carton = UnitRef(uid: 'U-CTN', code: 'CTN', name: 'Carton');

const _piece = ProductItem(
  uid: 'P1',
  code: 'OIL-1L',
  name: 'Cooking Oil 1L',
  unit: 'PCS',
  baseUnitUid: 'U-PCS',
);

Future<void> _pump(WidgetTester tester, Widget child) async {
  tester.view.physicalSize = const Size(360, 640);
  tester.view.devicePixelRatio = 1.0;
  addTearDown(tester.view.reset);
  await tester.pumpWidget(MaterialApp(
    theme: buildHqTheme(),
    home: Scaffold(body: SingleChildScrollView(child: child)),
  ));
  await tester.pump();
}

void main() {
  group('PackDraftCard', () {
    testWidgets('asks for the pack size before one is entered',
        (tester) async {
      final draft = PackDraft(_carton);
      addTearDown(draft.dispose);

      await _pump(
        tester,
        PackDraftCard(
          draft: draft,
          baseCode: 'PCS',
          currency: 'TZS',
          showPrice: true,
          onChanged: () {},
          onRemove: () {},
        ),
      );

      expect(find.text('Enter how many PCS are inside one CTN.'), findsOneWidget);
      expect(find.text('Price per CTN'), findsOneWidget);
      expect(tester.takeException(), isNull);
    });

    testWidgets('states the conversion once a size is entered',
        (tester) async {
      final draft = PackDraft(_carton, factor: '24');
      addTearDown(draft.dispose);

      await _pump(
        tester,
        PackDraftCard(
          draft: draft,
          baseCode: 'PCS',
          currency: 'TZS',
          showPrice: true,
          onChanged: () {},
          onRemove: () {},
        ),
      );

      expect(find.text('A CTN takes 24 PCS off the shelf.'), findsOneWidget);
      expect(draft.factorValue, 24);
      expect(tester.takeException(), isNull);
    });

    testWidgets('hides pricing when there is no price list', (tester) async {
      final draft = PackDraft(_carton, factor: '24');
      addTearDown(draft.dispose);

      await _pump(
        tester,
        PackDraftCard(
          draft: draft,
          baseCode: 'PCS',
          currency: 'TZS',
          showPrice: false,
          onChanged: () {},
          onRemove: () {},
        ),
      );

      expect(find.text('Price per CTN'), findsNothing);
      expect(tester.takeException(), isNull);
    });
  });

  group('pickUnit', () {
    testWidgets('every option states what it converts to', (tester) async {
      final units = [
        TxUnit.base(_piece),
        TxUnit(uid: 'U-CTN', code: 'CTN', name: 'Carton', factor: 24),
      ];
      TxUnit? chosen;

      await tester.pumpWidget(MaterialApp(
        theme: buildHqTheme(),
        home: Scaffold(
          body: Builder(
            builder: (context) => TextButton(
              onPressed: () async {
                chosen = await pickUnit(
                  context,
                  units: units,
                  baseCode: 'PCS',
                  current: units.first,
                );
              },
              child: const Text('open'),
            ),
          ),
        ),
      ));

      await tester.tap(find.text('open'));
      await tester.pumpAndSettle();

      expect(find.text('1 CTN = 24 PCS'), findsOneWidget);
      expect(find.text('The unit stock is counted in'), findsOneWidget);

      await tester.tap(find.text('Carton'));
      await tester.pumpAndSettle();

      expect(chosen?.code, 'CTN');
      expect(chosen?.toBase(10), 240);
    });
  });
}
