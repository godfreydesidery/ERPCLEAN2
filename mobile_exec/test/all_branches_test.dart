import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:orbix_hq/app/app_scope.dart';
import 'package:orbix_hq/app/theme.dart';
import 'package:orbix_hq/core/session.dart';
import 'package:orbix_hq/features/stock_report_screen.dart';
import 'package:orbix_hq/services/operations_service.dart';
import 'package:orbix_hq/services/stock_service.dart';
import 'package:shared_preferences/shared_preferences.dart';

const _a1 = BranchRef(
  uid: 'a1',
  name: 'Mbezi',
  code: 'A1',
  companyUid: 'A',
  isDefault: true,
);
const _a2 = BranchRef(
  uid: 'a2',
  name: 'Kariakoo',
  code: 'A2',
  companyUid: 'A',
  isDefault: false,
);
const _b1 = BranchRef(
  uid: 'b1',
  name: 'Arusha',
  code: 'B1',
  companyUid: 'B',
  isDefault: false,
);

/// A signed-out session given a scope by hand: enough for every rule that
/// does not call the server.
Future<Session> _session({
  List<BranchRef> branches = const [_a1, _a2, _b1],
  BranchRef? active = _a1,
  bool all = false,
}) async {
  SharedPreferences.setMockInitialValues(<String, Object>{});
  final s = await Session.create();
  s.branches = branches;
  s.activeBranch = active;
  s.companyUid = active?.companyUid;
  s.companies = const {
    'A': CompanyRef(uid: 'A', id: 1, name: 'Shayo Ltd'),
    'B': CompanyRef(uid: 'B', id: 2, name: 'Tulia Ltd'),
  };
  s.allBranches = all;
  return s;
}

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  group('report scope', () {
    test('one branch: reports are asked for that branch', () async {
      final s = await _session();
      expect(s.reportBranchUid, 'a1');
    });

    test('all branches: reports are asked for no branch', () async {
      final s = await _session(all: true);
      expect(s.reportBranchUid, isNull);
      // The anchor stays: it carries the company to the server.
      expect(s.activeBranch?.uid, 'a1');
    });

    test('labels name the company when branches span companies', () async {
      expect((await _session()).scopeLabel, 'Shayo Ltd · Mbezi');
      expect((await _session(all: true)).scopeLabel,
          'Shayo Ltd · All branches');
    });

    test('labels are just the branch within one company', () async {
      final s = await _session(branches: const [_a1, _a2], all: true);
      expect(s.scopeLabel, 'All branches');
    });

    test('scope key differs between one branch and all branches', () async {
      expect((await _session()).scopeKey,
          isNot((await _session(all: true)).scopeKey));
    });

    test('"All branches" is offered only with two or more branches', () async {
      final s = await _session();
      expect(s.canSeeAllBranchesOf('A'), isTrue);
      expect(s.canSeeAllBranchesOf('B'), isFalse);
      expect(s.branchesInCompany.map((b) => b.uid), ['a1', 'a2']);
    });
  });

  test('stock on hand is not available for all branches', () async {
    final s = await _session(all: true);
    expect(
      () => StockService(s).onHand(),
      throwsA(isA<AllBranchesUnavailable>()),
    );
  });

  testWidgets('stock report says not available for all branches',
      (tester) async {
    final s = await _session(all: true)
      ..permissions = const ['STOCK.VIEW'];
    await tester.pumpWidget(
      AppScope(
        session: s,
        child: MaterialApp(
          theme: buildHqTheme(),
          home: const StockReportScreen(),
        ),
      ),
    );
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 50));

    expect(find.text(kAllBranchesUnavailable), findsOneWidget);
    expect(find.text('Try again'), findsNothing);
  });

  test('all-branches tills keep only the user\'s branches', () {
    SessionRow row(String uid, String branch) => SessionRow.fromJson({
          'uid': uid,
          'status': 'OPEN',
          'branchName': branch,
        });
    final out = sessionsAtBranches(
      [row('1', 'Mbezi'), row('2', 'Kariakoo'), row('3', 'Not mine')],
      branchNames: const ['mbezi', 'Kariakoo'],
    );
    expect(out.map((r) => r.uid), ['1', '2']);
  });
}
