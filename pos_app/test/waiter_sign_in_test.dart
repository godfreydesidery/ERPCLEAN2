import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:pos_app/features/auth/login_screen.dart';
import 'package:pos_app/widgets/on_screen_keyboard.dart';
import 'package:shared_preferences/shared_preferences.dart';

/// The sign-in screen of a restaurant with fifty waiters (J COB 2026-10-30):
/// it must lay out at real till sizes, narrow by letter, and bring up the
/// keyboard only once a name is picked.
const _first = ['Amina', 'Baraka', 'Fatuma', 'Hassan', 'Neema', 'Salim', 'Rehema', 'Juma', 'Zawadi', 'Kassim'];
const _last = ['Ali', 'Juma', 'Mushi', 'Kileo', 'Mbwana'];

List<Map<String, String>> _fiftyWaiters() => [
      for (final f in _first)
        for (final l in _last)
          {'uid': 'U-$f-$l', 'name': '$f $l', 'username': '${f.toLowerCase()}.${l.toLowerCase()}'},
    ];

Future<void> _pump(WidgetTester tester, Size size) async {
  SharedPreferences.setMockInitialValues({'cfg.waiters': jsonEncode(_fiftyWaiters())});
  tester.view.physicalSize = size;
  tester.view.devicePixelRatio = 1;
  addTearDown(tester.view.reset);
  await tester.pumpWidget(const ProviderScope(child: MaterialApp(home: LoginScreen())));
  await tester.pumpAndSettle();
}

void main() {
  for (final size in const [Size(1366, 768), Size(1024, 768), Size(800, 1280)]) {
    testWidgets('fifty waiters lay out at ${size.width.toInt()}x${size.height.toInt()}', (tester) async {
      await _pump(tester, size);
      expect(tester.takeException(), isNull);
      expect(find.text('Waiters'), findsOneWidget);
      expect(find.byType(OnScreenKeyboard), findsNothing,
          reason: 'a long list is browsed with the whole height');

      // The A–Z strip narrows fifty names to one letter.
      await tester.tap(find.text('Z'));
      await tester.pumpAndSettle();
      expect(find.text('Zawadi Ali'), findsOneWidget);
      expect(find.text('Amina Ali'), findsNothing);

      // Picking a name fills the username and brings up the keys for the password.
      await tester.tap(find.text('Zawadi Mushi'));
      await tester.pumpAndSettle();
      expect(tester.takeException(), isNull);
      expect(find.byType(OnScreenKeyboard), findsOneWidget);
      expect(
          find.byWidgetPredicate(
              (w) => w is EditableText && w.controller.text == 'zawadi.mushi' && !w.obscureText),
          findsOneWidget,
          reason: 'the username is filled in');
    });
  }

  testWidgets('a search box finds a waiter among fifty', (tester) async {
    await _pump(tester, const Size(1366, 768));
    await tester.enterText(find.widgetWithText(TextField, 'Search waiters'), 'kass');
    await tester.pumpAndSettle();
    expect(find.text('Kassim Ali'), findsOneWidget);
    expect(find.text('Neema Ali'), findsNothing);
  });

  testWidgets('focus alone never brings the keys up; a tap does', (tester) async {
    await _pump(tester, const Size(1366, 768));
    final username = find.byWidgetPredicate((w) => w is EditableText && !w.obscureText).at(1);
    // What Windows does when the window is activated: the field takes focus by itself.
    tester.widget<EditableText>(username).focusNode.requestFocus();
    await tester.pumpAndSettle();
    expect(find.byType(OnScreenKeyboard), findsNothing);

    await tester.tap(username);
    await tester.pumpAndSettle();
    expect(find.byType(OnScreenKeyboard), findsOneWidget);
  });

  testWidgets('a small restaurant also starts without the keys', (tester) async {
    SharedPreferences.setMockInitialValues({'cfg.waiters': jsonEncode(_fiftyWaiters().take(4).toList())});
    tester.view.physicalSize = const Size(1366, 768);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.reset);
    await tester.pumpWidget(const ProviderScope(child: MaterialApp(home: LoginScreen())));
    await tester.pumpAndSettle();
    expect(find.byType(OnScreenKeyboard), findsNothing);
    await tester.tap(find.text('Amina Juma'));
    await tester.pumpAndSettle();
    expect(find.byType(OnScreenKeyboard), findsOneWidget);
  });

  testWidgets('Server setup is hidden; seven taps on the version open it', (tester) async {
    await _pump(tester, const Size(1366, 768));
    expect(find.text('Server setup'), findsNothing);
    final version = find.textContaining('OrbixPOS ');
    for (var i = 0; i < 6; i++) {
      await tester.tap(version);
      await tester.pump(const Duration(milliseconds: 100));
    }
    expect(find.text('Setup & diagnostics'), findsNothing);
    await tester.tap(version);
    await tester.pumpAndSettle();
    expect(find.text('Setup & diagnostics'), findsOneWidget);
  });
}
