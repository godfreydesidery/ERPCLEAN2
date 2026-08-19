// Smoke test for the OrbixHQ demo build.
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';

import 'package:orbix_hq/main.dart';

void main() {
  testWidgets('OrbixHQ boots to the sign-in screen', (WidgetTester tester) async {
    await tester.pumpWidget(const OrbixHqApp());
    await tester.pumpAndSettle();
    expect(find.text('OrbixHQ'), findsWidgets);
  });
}
