import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:pos_app/models/restaurant.dart';
import 'package:pos_app/widgets/pin_pad.dart';

/// Waiter PIN sign-in (V111): the pad, and the tile flag that turns it on.
void main() {
  Future<({PinPadOutcome? outcome, List<String> tried})> run(
      WidgetTester t, Future<String?> Function(String) onPin, Future<void> Function() act) async {
    PinPadOutcome? outcome;
    final tried = <String>[];
    await t.pumpWidget(MaterialApp(
      home: Builder(
        builder: (context) => TextButton(
          onPressed: () async => outcome = await showPinPad(context, name: 'Wanda Waiter',
              onPin: (pin) {
            tried.add(pin);
            return onPin(pin);
          }),
          child: const Text('go'),
        ),
      ),
    ));
    await t.tap(find.text('go'));
    await t.pumpAndSettle();
    await act();
    await t.pumpAndSettle();
    return (outcome: outcome, tried: tried);
  }

  Future<void> type(WidgetTester t, String digits) async {
    for (final d in digits.split('')) {
      await t.tap(find.text(d).last);
      await t.pump();
    }
  }

  testWidgets('the fourth digit submits, and an accepted PIN closes the pad', (t) async {
    final r = await run(t, (_) async => null, () => type(t, '2580'));
    expect(r.tried, ['2580']);
    expect(r.outcome, PinPadOutcome.done);
  });

  testWidgets('a wrong PIN says so and clears for another try', (t) async {
    var first = true;
    final r = await run(t, (_) async {
      if (first) {
        first = false;
        return 'That PIN is not correct.';
      }
      return null;
    }, () async {
      await type(t, '1357');
      await t.pumpAndSettle();
      expect(find.text('That PIN is not correct.'), findsOneWidget);
      await type(t, '25');
      await t.tap(find.byIcon(Icons.backspace_outlined));
      await t.pump();
      await type(t, '58');
      await t.pump();
      await type(t, '0');
    });
    expect(r.tried, ['1357', '2580']);
    expect(r.outcome, PinPadOutcome.done);
  });

  testWidgets('the waiter can fall back to the password', (t) async {
    final r = await run(t, (_) async => null, () => t.tap(find.text('Use password instead')));
    expect(r.outcome, PinPadOutcome.usePassword);
    expect(r.tried, isEmpty);
  });

  test('a waiter tile carries whether a PIN is set, through the till cache', () {
    final w = RestaurantWaiter.fromJson(
        {'uid': 'W1', 'name': 'Wanda', 'username': 'wanda', 'hasPin': true});
    expect(w.hasPin, isTrue);
    expect(RestaurantWaiter.fromJson(w.toJson()).hasPin, isTrue);
    expect(RestaurantWaiter.fromJson({'uid': 'W2', 'name': 'Old', 'username': 'o'}).hasPin, isFalse,
        reason: 'tiles cached before V111 sign in with the password');
  });
}
