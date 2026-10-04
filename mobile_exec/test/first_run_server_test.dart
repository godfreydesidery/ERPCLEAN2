// A build with no server baked in — the generic Orbix app — asks for the
// address openly on first launch, instead of hiding it behind the footer
// gesture that only support knows about. Once it is set, the sign-in form
// takes over and the address goes back behind the gesture.
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shared_preferences/shared_preferences.dart';

import 'package:orbix_hq/app/app_scope.dart';
import 'package:orbix_hq/app/theme.dart';
import 'package:orbix_hq/core/session.dart';
import 'package:orbix_hq/features/sign_in_screen.dart';

void main() {
  Future<Session> pumpSignIn(WidgetTester tester) async {
    tester.view.physicalSize = const Size(412, 915);
    tester.view.devicePixelRatio = 1.0;
    addTearDown(tester.view.reset);
    // What a build made with HQ_HOST="" starts from.
    SharedPreferences.setMockInitialValues(<String, Object>{
      'hq.base_host': '',
    });
    final session = await Session.create();
    await tester.pumpWidget(AppScope(
      session: session,
      child: MaterialApp(
        theme: buildHqTheme(),
        home: SignInScreen(onSignedIn: () {}),
      ),
    ));
    await tester.pump();
    return session;
  }

  testWidgets('with no server, it asks for one before anything else',
      (tester) async {
    await pumpSignIn(tester);

    expect(find.text("Connect to your company's server"), findsOneWidget);
    expect(find.text('Username'), findsNothing);
  });

  testWidgets('entering the address brings up the sign-in form',
      (tester) async {
    final session = await pumpSignIn(tester);

    await tester.tap(find.text('Enter server address'));
    await tester.pumpAndSettle();
    expect(find.text('This phone is not connected to a server yet.'),
        findsOneWidget);

    await tester.enterText(
        find.byType(TextField), 'https://erp.example.com/api/v1/');
    await tester.tap(find.text('Save'));
    await tester.pumpAndSettle();

    // The /api/v1 the user pasted is stripped — the double-path trap.
    expect(session.config.baseHost, 'https://erp.example.com');
    expect(find.text("Connect to your company's server"), findsNothing);
    expect(find.text('Username'), findsOneWidget);
  });
}
