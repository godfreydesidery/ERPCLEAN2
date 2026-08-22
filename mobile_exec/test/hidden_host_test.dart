// The server address is not on the sign-in screen, and takes seven taps to
// reach.
//
// It used to sit above the username with a pencil next to it. A manager never
// needs it — the address is baked in at build time — and a phone pointed at
// the wrong server looks exactly like a broken app, so the field invited a
// support call it could not prevent. Support must still be able to move a
// client to a new address down a phone line, hence a gesture rather than no
// way in at all.
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shared_preferences/shared_preferences.dart';

import 'package:orbix_hq/app/app_scope.dart';
import 'package:orbix_hq/app/theme.dart';
import 'package:orbix_hq/core/session.dart';
import 'package:orbix_hq/features/sign_in_screen.dart';

const _footer = 'Protected by your company\'s server';

void main() {
  late Session session;

  setUpAll(() async {
    TestWidgetsFlutterBinding.ensureInitialized();
    SharedPreferences.setMockInitialValues(<String, Object>{});
    session = await Session.create();
  });

  Future<void> pumpSignIn(WidgetTester tester) async {
    tester.view.physicalSize = const Size(412, 915);
    tester.view.devicePixelRatio = 1.0;
    addTearDown(tester.view.reset);
    await tester.pumpWidget(AppScope(
      session: session,
      child: MaterialApp(
        theme: buildHqTheme(),
        home: SignInScreen(onSignedIn: () {}),
      ),
    ));
    await tester.pump();
  }

  Future<void> tapFooter(WidgetTester tester, int times) async {
    for (var i = 0; i < times; i++) {
      await tester.tap(find.text(_footer));
      await tester.pump();
    }
  }

  testWidgets('the address is nowhere on the screen', (tester) async {
    await pumpSignIn(tester);

    expect(find.text(session.config.baseHost), findsNothing);
    expect(find.textContaining('localhost'), findsNothing);
    expect(find.byIcon(Icons.dns_outlined), findsNothing);
    expect(find.text('Server address'), findsNothing);
    // What a manager should see is the sign-in form, and only that.
    expect(find.text('Username'), findsOneWidget);
  });

  testWidgets('six taps reveal nothing', (tester) async {
    await pumpSignIn(tester);
    await tapFooter(tester, 6);

    expect(find.text('Server address'), findsNothing);
  });

  testWidgets('the seventh tap opens it, showing the current address',
      (tester) async {
    await pumpSignIn(tester);
    await tapFooter(tester, 7);
    await tester.pumpAndSettle();

    expect(find.text('Server address'), findsOneWidget);
    expect(find.text('This phone is set to:'), findsOneWidget);
    // Support reads this back down the phone, so it must be the real value.
    expect(find.text(session.config.baseHost), findsWidgets);
  });

  testWidgets('nothing hints at the gesture until it is plainly deliberate',
      (tester) async {
    await pumpSignIn(tester);

    // Three stray taps must leave no trace that anything is there.
    await tapFooter(tester, 3);
    expect(find.textContaining('more'), findsNothing);

    // From the fourth, someone doing it on purpose is told where they are.
    await tapFooter(tester, 1);
    expect(find.text('3 more'), findsOneWidget);

    await tapFooter(tester, 2);
    expect(find.text('1 more'), findsOneWidget);
  });
}
