import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:orbix_hq/app/theme.dart';
import 'package:orbix_hq/main_web.dart';
import 'package:orbix_hq/pwa/pwa_bridge.dart';
import 'package:orbix_hq/pwa/pwa_controller.dart';
import 'package:orbix_hq/pwa/pwa_frame.dart';
import 'package:orbix_hq/pwa/pwa_state.dart';
import 'package:shared_preferences/shared_preferences.dart';

class _FakeBridge implements PwaBridge {
  _FakeBridge(Map<String, Object?> initial) : _json = jsonEncode(initial);

  String _json;
  void Function(String)? _listener;
  int prompts = 0;
  int snoozes = 0;
  int updates = 0;
  String promptOutcome = 'accepted';

  void push(Map<String, Object?> state) {
    _json = jsonEncode(state);
    _listener?.call(_json);
  }

  @override
  String? stateJson() => _json;

  @override
  void subscribe(void Function(String json) onChange) => _listener = onChange;

  @override
  Future<String> promptInstall() async {
    prompts++;
    return promptOutcome;
  }

  @override
  void snoozeInstallHint() => snoozes++;

  @override
  void applyUpdate() => updates++;
}

void main() {
  group('PwaState', () {
    test('garbage JSON falls back to the quiet defaults', () {
      final s = PwaState.fromJson('not json');
      expect(s.online, isTrue);
      expect(s.installAvailable, isFalse);
      expect(s.updateReady, isFalse);
    });

    test('install hint: Chrome with a captured prompt, not installed', () {
      final s = PwaState.fromJson('{"canPrompt":true,"online":true}');
      expect(s.showInstallHint(dismissedThisSession: false), isTrue);
      expect(s.showInstallHint(dismissedThisSession: true), isFalse);
    });

    test(
      'install hint: iPhone Safari has no prompt but still gets the hint',
      () {
        final s = PwaState.fromJson('{"ios":true}');
        expect(s.showInstallHint(dismissedThisSession: false), isTrue);
      },
    );

    test('never suggests installing an installed app', () {
      for (final j in [
        '{"canPrompt":true,"standalone":true}',
        '{"ios":true,"standalone":true}',
        '{"canPrompt":true,"installed":true}',
      ]) {
        expect(
          PwaState.fromJson(j).showInstallHint(dismissedThisSession: false),
          isFalse,
          reason: j,
        );
      }
    });

    test('install hint is quiet when snoozed, offline, or an update waits', () {
      for (final j in [
        '{"canPrompt":true,"hintSnoozed":true}',
        '{"canPrompt":true,"online":false}',
        '{"canPrompt":true,"updateReady":true}',
      ]) {
        expect(
          PwaState.fromJson(j).showInstallHint(dismissedThisSession: false),
          isFalse,
          reason: j,
        );
      }
    });

    test(
      'a browser with neither prompt nor iOS (Firefox desktop) gets none',
      () {
        expect(PwaState.fromJson('{}').installAvailable, isFalse);
      },
    );

    test('update is offered until the user says Later', () {
      final s = PwaState.fromJson('{"updateReady":true}');
      expect(s.showUpdate(laterThisSession: false), isTrue);
      expect(s.showUpdate(laterThisSession: true), isFalse);
    });
  });

  group('PwaController', () {
    test('follows the bridge and never applies an update by itself', () {
      final bridge = _FakeBridge({'online': true});
      final c = PwaController(bridge: bridge);
      var notified = 0;
      c.addListener(() => notified++);

      bridge.push({'online': false, 'updateReady': true});
      expect(c.showOffline, isTrue);
      expect(c.showUpdate, isTrue);
      expect(notified, 1);
      expect(bridge.updates, 0);

      c.updateLater();
      expect(c.showUpdate, isFalse);
      expect(bridge.updates, 0);

      c.updateNow();
      expect(bridge.updates, 1);
    });

    test('Not now snoozes in the browser and hides the hint', () {
      final bridge = _FakeBridge({'canPrompt': true});
      final c = PwaController(bridge: bridge);
      expect(c.showInstallHint, isTrue);
      c.notNow();
      expect(c.showInstallHint, isFalse);
      expect(bridge.snoozes, 1);
    });

    test('declining the browser dialog stops asking this visit', () async {
      final bridge = _FakeBridge({'canPrompt': true})
        ..promptOutcome = 'dismissed';
      final c = PwaController(bridge: bridge);
      await c.install();
      expect(bridge.prompts, 1);
      expect(c.showInstallHint, isFalse);
    });

    test(
      'iPhone: Install opens the Add to Home Screen help, no prompt',
      () async {
        final bridge = _FakeBridge({'ios': true});
        final c = PwaController(bridge: bridge);
        await c.install();
        expect(bridge.prompts, 0);
        expect(c.iosHelpOpen, isTrue);
      },
    );

    test('off the web the default bridge is inert', () {
      final c = PwaController();
      expect(c.showOffline, isFalse);
      expect(c.showUpdate, isFalse);
      expect(c.showInstallHint, isFalse);
    });
  });

  group('PwaFrame', () {
    // The REAL app theme: its full-width button style once broke the banner.
    Future<void> pump(WidgetTester t, PwaController c, Size size) async {
      t.view.physicalSize = size;
      t.view.devicePixelRatio = 1;
      addTearDown(t.view.reset);
      await t.pumpWidget(
        PwaFrame(
          controller: c,
          child: MaterialApp(
            theme: buildHqTheme(),
            home: const Scaffold(body: SizedBox.expand()),
          ),
        ),
      );
    }

    for (final width in [360.0, 800.0, 1440.0]) {
      testWidgets('install + update banners lay out and are announced at '
          '${width.toInt()} px', (t) async {
        final sem = t.ensureSemantics();
        for (final state in [
          {'canPrompt': true},
          {'updateReady': true},
          {'ios': true},
          {'online': false},
        ]) {
          await pump(
            t,
            PwaController(bridge: _FakeBridge(state)),
            Size(width, 800),
          );
          expect(t.takeException(), isNull, reason: '$state');
        }
        await pump(
          t,
          PwaController(bridge: _FakeBridge({'canPrompt': true})),
          Size(width, 800),
        );
        expect(
          find.bySemanticsLabel(RegExp('Install Orbix App')),
          findsOneWidget,
        );
        final install = t.getSize(find.widgetWithText(FilledButton, 'Install'));
        expect(install.width, lessThan(200));
        sem.dispose();
      });
    }

    testWidgets('offline banner shows and the app is pushed below it', (
      t,
    ) async {
      final bridge = _FakeBridge({'online': false});
      await pump(t, PwaController(bridge: bridge), const Size(390, 844));
      expect(find.text('You are offline'), findsOneWidget);
      expect(t.getRect(find.byType(Scaffold)).top, greaterThan(0));
      expect(t.takeException(), isNull);
    });

    testWidgets('update banner: Update now calls the bridge', (t) async {
      final bridge = _FakeBridge({'updateReady': true});
      await pump(t, PwaController(bridge: bridge), const Size(390, 844));
      await t.tap(find.text('Update now'));
      expect(bridge.updates, 1);
    });

    testWidgets('iPhone install banner explains Add to Home Screen', (t) async {
      final bridge = _FakeBridge({'ios': true});
      await pump(t, PwaController(bridge: bridge), const Size(390, 844));
      await t.tap(find.text('Show me how'));
      await t.pump();
      expect(find.textContaining('Add to Home Screen'), findsOneWidget);
      await t.tap(find.text('Got it'));
      await t.pump();
      expect(bridge.snoozes, 1);
      expect(find.text('Install Orbix App'), findsNothing);
    });

    testWidgets('wide screen: the app is capped and centred', (t) async {
      await pump(
        t,
        PwaController(bridge: _FakeBridge({})),
        const Size(1440, 900),
      );
      final r = t.getRect(find.byType(Scaffold));
      expect(r.width, kPwaMaxAppWidth);
      expect(r.center.dx, 720);
    });

    testWidgets('phone: full width, no banner when nothing to say', (t) async {
      await pump(
        t,
        PwaController(bridge: _FakeBridge({})),
        const Size(390, 844),
      );
      expect(t.getRect(find.byType(Scaffold)).width, 390);
      expect(find.byType(FilledButton), findsNothing);
    });
  });

  group('seedSameOriginServer', () {
    test('first launch: the server is the page origin', () async {
      SharedPreferences.setMockInitialValues({});
      await seedSameOriginServer(
        base: Uri.parse('https://app.orbixsystems.app/'),
      );
      final p = await SharedPreferences.getInstance();
      expect(p.getString('hq.base_host'), 'https://app.orbixsystems.app');
    });

    test('a server the user already set is kept', () async {
      SharedPreferences.setMockInitialValues({
        'hq.base_host': 'https://other.example',
      });
      await seedSameOriginServer(
        base: Uri.parse('https://app.orbixsystems.app/'),
      );
      final p = await SharedPreferences.getInstance();
      expect(p.getString('hq.base_host'), 'https://other.example');
    });
  });
}
