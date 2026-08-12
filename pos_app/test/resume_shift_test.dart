import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:pos_app/core/api/api_exception.dart';
import 'package:pos_app/models/enums.dart';
import 'package:pos_app/models/pos.dart';
import 'package:pos_app/services/session_service.dart';
import 'package:pos_app/state/app_controller.dart';
import 'package:pos_app/state/providers.dart';

/// Resuming an open shift.
///
/// A shift stays open server-side until someone counts the drawer, so the till
/// belongs to the cashier holding it whatever the app can or cannot read. The
/// reported defect: reading the session takes POS.SESSION.VIEW, and where that
/// is refused Resume 403s and the cashier is left on the picker with a till
/// they can neither re-enter nor close.
class _StubSessions implements SessionService {
  _StubSessions(this._getByUid);
  final Future<PosSession> Function(String uid) _getByUid;

  @override
  Future<PosSession> getByUid(String uid) => _getByUid(uid);

  // The resume path touches nothing else on the service.
  @override
  dynamic noSuchMethod(Invocation invocation) => super.noSuchMethod(invocation);
}

void main() {
  final openedAt = DateTime.utc(2026, 8, 11, 6, 15);

  PosTill till() => PosTill(
        id: '9',
        uid: 'TILL1',
        companyId: '10',
        branchId: '20',
        code: 'T1',
        name: 'Till 1',
        cashBankAccountId: null,
        status: 'ACTIVE',
        hasOpenSession: true,
        openSessionUid: 'SESS1',
        openSessionCashierId: '7',
        openSessionOpenedAt: openedAt,
      );

  PosSession serverSession() => PosSession.fromJson({
        'id': '55',
        'uid': 'SESS1',
        'posTillId': '9',
        'cashierId': '7',
        'sessionNumber': 'POS-0007',
        'status': 'OPEN',
        'openedAt': openedAt.toIso8601String(),
        'openingFloatAmount': '20000.00',
      });

  ProviderContainer containerWith(Future<PosSession> Function(String) get) =>
      ProviderContainer.test(overrides: [
        sessionServiceProvider.overrideWithValue(_StubSessions(get)),
      ]);

  group('AppController.resumeShift', () {
    test('reads the session and enters the register when allowed', () async {
      final c = containerWith((_) async => serverSession());

      await c
          .read(appControllerProvider.notifier)
          .resumeShift('SESS1', BusinessMode.supermarket, fallbackTill: till());

      final app = c.read(appControllerProvider);
      expect(app.phase, AppPhase.register);
      expect(app.shift!.sessionNumber, 'POS-0007');
      expect(app.shift!.figuresKnown, isTrue);
      expect(app.busy, isFalse);
      expect(app.error, isNull);
    });

    test('a refused read still resumes, on the till row alone', () async {
      final c = containerWith((_) async =>
          throw ApiException(statusCode: 403, message: 'no permission'));

      await c
          .read(appControllerProvider.notifier)
          .resumeShift('SESS1', BusinessMode.supermarket, fallbackTill: till());

      final app = c.read(appControllerProvider);
      expect(app.phase, AppPhase.register);
      expect(app.shift!.uid, 'SESS1');
      expect(app.shift!.status, PosSessionStatus.open);
      expect(app.shift!.openedAt, openedAt);
      // Figures nobody read must be flagged, never presented as server truth.
      expect(app.shift!.figuresKnown, isFalse);
      expect(app.busy, isFalse);
      expect(app.error, isNull);
    });

    test('a dead line resumes the same way — the power cuts out mid-shift',
        () async {
      final c = containerWith((_) async => throw ApiException(
          statusCode: null, isNetwork: true, message: 'cannot reach the ERP'));

      await c
          .read(appControllerProvider.notifier)
          .resumeShift('SESS1', BusinessMode.supermarket, fallbackTill: till());

      expect(c.read(appControllerProvider).phase, AppPhase.register);
      expect(c.read(appControllerProvider).shift!.figuresKnown, isFalse);
    });

    test('a 404 is a real answer about the session and is not papered over',
        () async {
      final c = containerWith((_) async =>
          throw ApiException(statusCode: 404, message: 'Not found.'));

      await c
          .read(appControllerProvider.notifier)
          .resumeShift('SESS1', BusinessMode.supermarket, fallbackTill: till());

      final app = c.read(appControllerProvider);
      expect(app.phase, isNot(AppPhase.register));
      expect(app.error, 'Not found.');
      expect(app.busy, isFalse);
    });

    test('without a till row a refusal reports, and never hangs busy',
        () async {
      final c = containerWith((_) async =>
          throw ApiException(statusCode: 403, message: 'no permission'));

      await c
          .read(appControllerProvider.notifier)
          .resumeShift('SESS1', BusinessMode.supermarket);

      final app = c.read(appControllerProvider);
      expect(app.phase, isNot(AppPhase.register));
      expect(app.error, 'no permission');
      expect(app.busy, isFalse);
    });

    test('a non-ApiException failure clears busy instead of stranding it',
        () async {
      final c = containerWith((_) async => throw StateError('parse blew up'));

      await c
          .read(appControllerProvider.notifier)
          .resumeShift('SESS1', BusinessMode.supermarket, fallbackTill: till());

      final app = c.read(appControllerProvider);
      // The bug this guards: the throw escaped, leaving the till spinning on a
      // busy flag nothing ever cleared.
      expect(app.busy, isFalse);
      expect(app.error, 'parse blew up');
    });
  });
}
