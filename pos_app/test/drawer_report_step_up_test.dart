import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:pos_app/core/api/api_client.dart';
import 'package:pos_app/core/config/step_up_policy.dart';
import 'package:pos_app/features/session/session_menu.dart';
import 'package:pos_app/models/auth.dart';
import 'package:pos_app/models/pos.dart';
import 'package:pos_app/models/step_up.dart';
import 'package:pos_app/services/session_service.dart';
import 'package:pos_app/services/step_up_service.dart';
import 'package:pos_app/state/app_controller.dart';
import 'package:pos_app/state/providers.dart';

/// Drawer reports for an operator who lacks `POS.SESSION.VIEW`.
///
/// The reported complaint: a shop owner withdrew the X-read and Z-read from the
/// cashier role — meaning "a drawer report takes a supervisor" — and the rows
/// disappeared from the till altogether (permission-denied rows are hidden by
/// design). Nothing to tap, nothing to ask a manager about, and it reads as the
/// till losing the feature rather than as the policy just set.
///
/// So the rows stay, and a manager approves the report at the terminal. The
/// approval is tokenless and re-verified server-side per request: the client
/// only ever carries the approver's uid, for exactly ONE call.
class _StubSessions implements SessionService {
  final List<String> calls = [];

  /// The approver uid the last authorised call carried, if any.
  String? lastAuthorisedBy;

  @override
  Future<XRead> xRead(String uid) async {
    calls.add('GET x-read');
    return XRead.fromJson(_xReadJson(uid));
  }

  @override
  Future<XRead> xReadAuthorised(String uid,
      {required String authorisedByUid}) async {
    calls.add('POST x-read/authorised');
    lastAuthorisedBy = authorisedByUid;
    return XRead.fromJson(_xReadJson(uid));
  }

  @override
  Future<ZRead> zRead(String uid) async {
    calls.add('GET z-read');
    return ZRead.fromJson(_zReadJson(uid));
  }

  @override
  Future<ZRead> zReadAuthorised(String uid,
      {required String authorisedByUid}) async {
    calls.add('POST z-read/authorised');
    lastAuthorisedBy = authorisedByUid;
    return ZRead.fromJson(_zReadJson(uid));
  }

  // The drawer touches nothing else on the service in these flows.
  @override
  dynamic noSuchMethod(Invocation invocation) => super.noSuchMethod(invocation);
}

Map<String, dynamic> _xReadJson(String uid) => {
      'sessionUid': uid,
      'openingFloatAmount': '20000.00',
      'totalSalesAmount': '46020.00',
      'cashTenderAmount': '32020.00',
      'totalPayoutsNetAmount': '0.00',
      'expectedCashAmount': '52020.00',
      'invoiceCount': 7,
      'tenderSubtotals': [],
    };

Map<String, dynamic> _zReadJson(String uid) => {
      ..._xReadJson(uid),
      'countedCashAmount': '52020.00',
      'varianceAmount': '0.00',
    };

class _StubStepUp implements StepUpService {
  _StubStepUp(this.result);

  /// What the server answers. A refusal is a normal 200 with `authorised:false`.
  final AuthorityVerification result;

  final List<String> askedFor = [];

  @override
  Future<AuthorityVerification> verify({
    required String username,
    required String password,
    required String permissionCode,
    String? correlationId,
  }) async {
    askedFor.add(permissionCode);
    return result;
  }

  @override
  dynamic noSuchMethod(Invocation invocation) => super.noSuchMethod(invocation);
}

/// Records what a service actually put on the wire.
class _RecordingApi implements ApiClient {
  String? path;
  Object? body;
  Object? reply;

  @override
  Future<dynamic> post(
    String path, {
    Object? body,
    Map<String, dynamic>? query,
    String? idempotencyKey,
    String? xRequestId,
    bool noAuth = false,
  }) async {
    this.path = path;
    this.body = body;
    return reply;
  }

  @override
  dynamic noSuchMethod(Invocation invocation) => super.noSuchMethod(invocation);
}

class _StubApp extends AppController {
  _StubApp(this._data);
  final AppData _data;
  @override
  AppData build() => _data;
}

const _approverUid = 'MGR7ZZZZZZZZZZZZZZZZZZZZZZ';

const _approved = AuthorityVerification(
  authorised: true,
  permissionCode: 'POS.SESSION.RECONCILE',
  message: 'Approved.',
  authoriserUid: _approverUid,
  authoriserUsername: 'branchmgr',
  authoriserName: 'Grace Mushi',
);

const _refused = AuthorityVerification(
  authorised: false,
  permissionCode: 'POS.SESSION.RECONCILE',
  message: 'That user may not approve this action.',
);

Me _me(Set<String> permissions) => Me(
      uid: 'USER1',
      username: 'cashier1',
      displayName: 'Neema',
      isRoot: false,
      activeCompanyUid: 'CO1',
      activeBranchUid: 'BR1',
      permissions: permissions,
    );

PosSession _session({String status = 'OPEN'}) => PosSession.fromJson({
      'id': '55',
      'uid': 'SESS1',
      'posTillId': '9',
      'cashierId': '7',
      'sessionNumber': 'POS-0007',
      'status': status,
      'openedAt': DateTime.utc(2026, 8, 12, 6, 15).toIso8601String(),
      'openingFloatAmount': '20000.00',
    });

AppData _appData(Set<String> permissions, {String status = 'OPEN'}) => AppData(
      phase: AppPhase.register,
      me: _me(permissions),
      shift: _session(status: status),
    );

/// Opens the session drawer over a bare host screen.
Future<void> _openDrawer(
  WidgetTester tester, {
  required AppData app,
  required _StubSessions sessions,
  required _StubStepUp stepUp,
}) async {
  tester.view.physicalSize = const Size(1200, 900);
  tester.view.devicePixelRatio = 1.0;
  addTearDown(tester.view.reset);

  await tester.pumpWidget(ProviderScope(
    overrides: [
      appControllerProvider.overrideWith(() => _StubApp(app)),
      sessionServiceProvider.overrideWithValue(sessions),
      stepUpServiceProvider.overrideWithValue(stepUp),
    ],
    child: MaterialApp(
      home: Consumer(
        builder: (context, ref, _) => Scaffold(
          body: Builder(
            builder: (inner) => TextButton(
              onPressed: () => openSessionMenu(inner, ref),
              child: const Text('open the drawer'),
            ),
          ),
        ),
      ),
    ),
  ));
  await tester.tap(find.text('open the drawer'));
  await tester.pumpAndSettle();
}

/// Types a manager's credentials into the step-up prompt and approves.
Future<void> _approveAsManager(WidgetTester tester) async {
  await tester.enterText(find.byType(TextField).at(0), 'branchmgr');
  await tester.enterText(find.byType(TextField).at(1), 'correct horse');
  await tester.tap(find.text('Approve'));
  await tester.pumpAndSettle();
}

void main() {
  group('step-up policy', () {
    test('an X-read a cashier cannot read themselves takes a manager', () {
      final rule = stepUpRuleFor(GatedAction.xRead);
      expect(rule.requiresApproval, isTrue);
      // POS.SESSION.VIEW would be a control in appearance only — a cashier can
      // hold it, so they could approve their own report.
      expect(rule.permissionCode, 'POS.SESSION.RECONCILE');
    });

    test('both drawer reports ask for the same approver', () {
      expect(stepUpRuleFor(GatedAction.zReadPrint).permissionCode,
          stepUpRuleFor(GatedAction.xRead).permissionCode);
    });
  });

  group('SessionService wire contract', () {
    test('an authorised X-read POSTs the approver uid', () async {
      final api = _RecordingApi()..reply = _xReadJson('SESS1');
      await SessionService(api)
          .xReadAuthorised('SESS1', authorisedByUid: _approverUid);

      expect(api.path, '/pos/sessions/uid/SESS1/x-read/authorised');
      expect(api.body, {'authorisedByUid': _approverUid});
    });

    test('an authorised Z-read POSTs the approver uid', () async {
      final api = _RecordingApi()..reply = _zReadJson('SESS1');
      await SessionService(api)
          .zReadAuthorised('SESS1', authorisedByUid: _approverUid);

      expect(api.path, '/pos/sessions/uid/SESS1/z-read/authorised');
      expect(api.body, {'authorisedByUid': _approverUid});
    });
  });

  group('the session drawer', () {
    testWidgets('shows both reports to a cashier who lacks POS.SESSION.VIEW',
        (tester) async {
      await _openDrawer(tester,
          app: _appData({Perms.saleCreate, Perms.sessionOpen}),
          sessions: _StubSessions(),
          stepUp: _StubStepUp(_approved));

      // The client's actual complaint: these rows used to vanish entirely.
      expect(find.text('X-read'), findsOneWidget);
      expect(find.text('Z-read (reprint)'), findsOneWidget);
    });

    testWidgets('leaves the reports hidden for someone not working the till',
        (tester) async {
      // The rows mirror the endpoints' own gate — POS.SESSION.VIEW or
      // POS.SALE.CREATE — and this operator holds neither. Showing a row the
      // server would refuse sends a cashier to fetch a manager who then types a
      // correct password for nothing.
      await _openDrawer(tester,
          app: _appData({Perms.salesInvoiceView, Perms.sessionOpen}),
          sessions: _StubSessions(),
          stepUp: _StubStepUp(_approved));

      expect(find.text('X-read'), findsNothing);
      expect(find.text('Z-read (reprint)'), findsNothing);
    });

    testWidgets('an X-read on a manager approval carries the approver uid',
        (tester) async {
      final sessions = _StubSessions();
      final stepUp = _StubStepUp(_approved);
      await _openDrawer(tester,
          app: _appData({Perms.saleCreate}),
          sessions: sessions,
          stepUp: stepUp);

      await tester.tap(find.text('X-read'));
      await tester.pumpAndSettle();
      expect(find.text('Manager approval — X-read'), findsOneWidget);

      await _approveAsManager(tester);

      expect(stepUp.askedFor, ['POS.SESSION.RECONCILE']);
      expect(sessions.calls, ['POST x-read/authorised']);
      expect(sessions.lastAuthorisedBy, _approverUid);
      // …and the report the cashier asked for is on screen.
      expect(find.text('Sales (all tenders)'), findsOneWidget);
    });

    testWidgets('a holder of POS.SESSION.VIEW is never asked for a manager',
        (tester) async {
      final sessions = _StubSessions();
      final stepUp = _StubStepUp(_approved);
      await _openDrawer(tester,
          app: _appData({Perms.saleCreate, Perms.sessionView}),
          sessions: sessions,
          stepUp: stepUp);

      await tester.tap(find.text('X-read'));
      await tester.pumpAndSettle();

      // Making a manager walk over for someone already allowed is a workflow
      // regression, not a control.
      expect(find.text('Manager approval — X-read'), findsNothing);
      expect(stepUp.askedFor, isEmpty);
      expect(sessions.calls, ['GET x-read']);
      expect(find.text('Sales (all tenders)'), findsOneWidget);
    });

    testWidgets('a cancelled prompt does nothing, quietly', (tester) async {
      final sessions = _StubSessions();
      final stepUp = _StubStepUp(_approved);
      await _openDrawer(tester,
          app: _appData({Perms.saleCreate}),
          sessions: sessions,
          stepUp: stepUp);

      await tester.tap(find.text('X-read'));
      await tester.pumpAndSettle();
      await tester.tap(find.text('Cancel'));
      await tester.pumpAndSettle();

      expect(sessions.calls, isEmpty);
      expect(find.text('Sales (all tenders)'), findsNothing);
      // No scolding, no error strip — the cashier changed their mind.
      expect(find.byType(SnackBar), findsNothing);
    });

    testWidgets('a refused manager keeps the prompt open with the server text',
        (tester) async {
      final sessions = _StubSessions();
      await _openDrawer(tester,
          app: _appData({Perms.saleCreate}),
          sessions: sessions,
          stepUp: _StubStepUp(_refused));

      await tester.tap(find.text('X-read'));
      await tester.pumpAndSettle();
      await _approveAsManager(tester);

      expect(find.text('That user may not approve this action.'),
          findsOneWidget);
      expect(find.text('Manager approval — X-read'), findsOneWidget);
      expect(sessions.calls, isEmpty);
    });

    testWidgets('the approval clears once viewed — a second report asks again',
        (tester) async {
      final sessions = _StubSessions();
      final stepUp = _StubStepUp(_approved);
      await _openDrawer(tester,
          app: _appData({Perms.saleCreate}),
          sessions: sessions,
          stepUp: stepUp);

      await tester.tap(find.text('X-read'));
      await tester.pumpAndSettle();
      await _approveAsManager(tester);
      await tester.tap(find.text('Close'));
      await tester.pumpAndSettle();

      await tester.tap(find.text('X-read'));
      await tester.pumpAndSettle();

      // Nothing was kept: no cached uid, no "approved session", no second
      // report on the first manager's say-so.
      expect(find.text('Manager approval — X-read'), findsOneWidget);
      await _approveAsManager(tester);
      expect(stepUp.askedFor.length, 2);
      expect(sessions.calls,
          ['POST x-read/authorised', 'POST x-read/authorised']);
    });

    testWidgets('the Z-read reprint takes the same route once reconciled',
        (tester) async {
      final sessions = _StubSessions();
      final stepUp = _StubStepUp(_approved);
      await _openDrawer(tester,
          app: _appData({Perms.saleCreate}, status: 'RECONCILED'),
          sessions: sessions,
          stepUp: stepUp);

      await tester.tap(find.text('Z-read (reprint)'));
      await tester.pumpAndSettle();
      expect(find.text('Manager approval — Z-read'), findsOneWidget);

      await _approveAsManager(tester);

      expect(sessions.calls, ['POST z-read/authorised']);
      expect(sessions.lastAuthorisedBy, _approverUid);
      expect(find.text('Counted'), findsOneWidget);
    });

    testWidgets('printing the copy a manager just approved does not ask again',
        (tester) async {
      // Viewing and printing are one act: the figures are already on screen, and the paper shows
      // the same manager the same numbers. A second prompt protects nothing and teaches cashiers
      // to fetch a manager twice per report. The server audited the approval on the read.
      final stepUp = _StubStepUp(_approved);
      await _openDrawer(tester,
          app: _appData({Perms.saleCreate}, status: 'RECONCILED'),
          sessions: _StubSessions(),
          stepUp: stepUp);

      await tester.tap(find.text('Z-read (reprint)'));
      await tester.pumpAndSettle();
      await _approveAsManager(tester);
      expect(stepUp.askedFor, ['POS.SESSION.RECONCILE']);   // asked exactly once, to open it

      await tester.tap(find.text('Print'));
      await tester.pump();

      // No second manager prompt for the same rendering.
      expect(find.textContaining('Manager approval'), findsNothing);
      expect(stepUp.askedFor, ['POS.SESSION.RECONCILE']);   // still once
    });

    testWidgets('the Z-read reprint stays disabled before reconciliation',
        (tester) async {
      final sessions = _StubSessions();
      await _openDrawer(tester,
          app: _appData({Perms.saleCreate}),
          sessions: sessions,
          stepUp: _StubStepUp(_approved));

      // Visible but not yet available — a workflow state, with the reason in
      // the subtitle. The server's rule is untouched by any of this.
      expect(find.text('Available once the session is reconciled'),
          findsOneWidget);
      await tester.tap(find.text('Z-read (reprint)'));
      await tester.pumpAndSettle();
      expect(sessions.calls, isEmpty);
      expect(find.text('Manager approval — Z-read'), findsNothing);
    });
  });
}
