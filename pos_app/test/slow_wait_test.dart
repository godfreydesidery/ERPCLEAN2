import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:pos_app/widgets/ui.dart';

/// What a cashier sees while the ERP is slow to answer: a spinner on the button
/// at once, and a "slow connection" line once the wait passes 5 seconds.
void main() {
  Widget host(Widget child) => MaterialApp(home: Scaffold(body: child));

  testWidgets('the slow-connection line appears only after 5 s of waiting, and goes', (t) async {
    await t.pumpWidget(host(const SlowWaitHint(active: true)));
    expect(find.textContaining('Slow connection'), findsNothing);
    await t.pump(const Duration(seconds: 4));
    expect(find.textContaining('Slow connection'), findsNothing);
    await t.pump(const Duration(seconds: 2));
    expect(find.textContaining('Slow connection'), findsOneWidget);

    await t.pumpWidget(host(const SlowWaitHint(active: false)));
    expect(find.textContaining('Slow connection'), findsNothing);

    // A new wait starts its own 5 s; the last one does not carry over.
    await t.pumpWidget(host(const SlowWaitHint(active: true)));
    await t.pump(const Duration(seconds: 1));
    expect(find.textContaining('Slow connection'), findsNothing);
  });

  testWidgets('a busy pay button spins and ignores taps', (t) async {
    var taps = 0;
    await t.pumpWidget(host(PayButton(
        label: 'Recording the sale…', amount: '1,500.00', busy: true, onPressed: () => taps++)));
    expect(find.byType(CircularProgressIndicator), findsOneWidget);
    await t.tap(find.text('Recording the sale…'));
    expect(taps, 0);

    await t.pumpWidget(host(PayButton(label: 'Complete sale', amount: '1,500.00', onPressed: () => taps++)));
    expect(find.byType(CircularProgressIndicator), findsNothing);
    await t.tap(find.text('Complete sale'));
    expect(taps, 1);
  });
}
