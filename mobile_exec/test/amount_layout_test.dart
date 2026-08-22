// Full amounts must survive a narrow phone.
//
// Writing amounts in full made the widest figure a screen can produce far wider
// than the one each layout was sized for: a hero built for "TZS 480,000" now
// has to hold "TZS 1,284,300,500". These render the worst case in the tightest
// box and fail on an overflow, which is otherwise only visible as a stripe on
// somebody's phone.
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';

import 'package:orbix_hq/app/format.dart';
import 'package:orbix_hq/app/theme.dart';
import 'package:orbix_hq/widgets/common.dart';
import 'package:orbix_hq/widgets/kit.dart';

/// A billion-shilling figure — bigger than this client will post in a day, and
/// the widest string the formatter can produce in practice.
const _huge = 1284300500.0;

Future<void> _pump(WidgetTester tester, Widget child, {double width = 360}) async {
  tester.view.physicalSize = Size(width, 720);
  tester.view.devicePixelRatio = 1.0;
  addTearDown(tester.view.reset);
  await tester.pumpWidget(MaterialApp(
    theme: buildHqTheme(),
    home: Scaffold(body: Center(child: SizedBox(width: width, child: child))),
  ));
  await tester.pump();
}

void main() {
  test('the worst case really is wider than the layouts were built for', () {
    expect(tzs(_huge), 'TZS 1,284,300,500');
    expect(tzs(_huge).length, greaterThan(tzs(480000).length));
  });

  testWidgets('a hero amount scales down instead of overflowing',
      (tester) async {
    await _pump(
      tester,
      Amount(
        tzs(_huge),
        style: const TextStyle(fontSize: 38, fontWeight: FontWeight.w700),
      ),
    );
    expect(find.text('TZS 1,284,300,500'), findsOneWidget);
    expect(tester.takeException(), isNull);
  });

  testWidgets('a hero amount fits even in a third of the width',
      (tester) async {
    // Three figures across the dashboard hero on a 360px handset.
    await _pump(
      tester,
      Amount(
        tzs(_huge),
        style: const TextStyle(fontSize: 14, fontWeight: FontWeight.w700),
      ),
      width: 110,
    );
    expect(tester.takeException(), isNull);
  });

  testWidgets('a figure row holds a long label and a long amount',
      (tester) async {
    await _pump(
      tester,
      FigureRow(
        label: 'Cash the drawer should hold',
        value: tzs(_huge),
        emphasise: true,
      ),
    );
    expect(find.text('TZS 1,284,300,500'), findsOneWidget);
    expect(tester.takeException(), isNull);
  });

  testWidgets('a stat tile holds one too', (tester) async {
    await _pump(
      tester,
      Row(
        children: [
          for (var i = 0; i < 3; i++)
            Expanded(child: StatTile(label: 'Sales', value: tzs(_huge))),
        ],
      ),
    );
    expect(tester.takeException(), isNull);
  });
}
