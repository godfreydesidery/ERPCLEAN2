import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:pos_app/features/dashboard/dashboard_screen.dart';
import 'package:pos_app/features/shift/open_shift_screen.dart';
import 'package:pos_app/models/auth.dart';
import 'package:pos_app/models/context.dart';
import 'package:pos_app/models/dashboard.dart';
import 'package:pos_app/models/pos.dart';
import 'package:pos_app/models/sale.dart';
import 'package:pos_app/services/sale_service.dart';
import 'package:pos_app/services/till_service.dart';
import 'package:pos_app/state/app_controller.dart';
import 'package:pos_app/state/providers.dart';

/// Tulia 2026-11-03: a manager had to pick a till and open a shift — a real
/// cash session — just to look at the sales dashboard. The open-shift screen
/// now offers the dashboard and today's sales directly.
class _StubApp extends AppController {
  _StubApp(this._data);
  final AppData _data;
  @override
  AppData build() => _data;
}

class _NoTills implements TillService {
  @override
  Future<List<PosTill>> listByBranch(String companyId, String branchId) async => [];

  @override
  dynamic noSuchMethod(Invocation invocation) => super.noSuchMethod(invocation);
}

/// Answers nothing: these tests only care that the screens open.
class _PendingSales implements SaleService {
  @override
  Future<PosDashboard> dashboard(DateTime from, DateTime to) => Completer<PosDashboard>().future;

  @override
  Future<List<SalesInvoice>> listTodaysSales(String companyId,
          {required String branchId, DateTime? now, int size = 0}) =>
      Completer<List<SalesInvoice>>().future;

  @override
  dynamic noSuchMethod(Invocation invocation) => super.noSuchMethod(invocation);
}

AppData _app(Set<String> permissions) => AppData(
      phase: AppPhase.openShift,
      me: Me(
        uid: 'USER1',
        username: 'manager',
        displayName: 'Mr Issa',
        isRoot: false,
        activeCompanyUid: 'CO1',
        activeBranchUid: 'BR1',
        permissions: permissions,
      ),
      context: PosContext(
        organisationUid: 'ORG1',
        company: Company(id: '10', uid: 'CO1', name: 'Tulia'),
        branch: Branch(
            id: '20',
            uid: 'BR1',
            companyId: '10',
            companyUid: 'CO1',
            code: 'MAIN',
            name: 'Main',
            isDefault: true,
            status: 'ACTIVE'),
      ),
    );

Future<void> _pump(WidgetTester t, Set<String> permissions, Size size) async {
  t.view.physicalSize = size;
  t.view.devicePixelRatio = 1;
  addTearDown(t.view.reset);
  await t.pumpWidget(ProviderScope(
    overrides: [
      appControllerProvider.overrideWith(() => _StubApp(_app(permissions))),
      tillServiceProvider.overrideWithValue(_NoTills()),
      saleServiceProvider.overrideWithValue(_PendingSales()),
    ],
    child: const MaterialApp(home: OpenShiftScreen()),
  ));
  await t.pump();
}

void main() {
  testWidgets('a manager opens the dashboard without a till', (t) async {
    await _pump(t, {Perms.salesInvoiceView}, const Size(1366, 768));
    expect(find.text('Open shift'), findsOneWidget);
    await t.tap(find.text('Sales dashboard'));
    await t.pump();
    await t.pump(const Duration(milliseconds: 400));
    expect(find.byType(DashboardScreen), findsOneWidget);
  });

  testWidgets("today's sales opens without a till", (t) async {
    await _pump(t, {Perms.salesInvoiceView}, const Size(1366, 768));
    await t.tap(find.text("Today's sales"));
    await t.pump();
    expect(find.byType(Dialog), findsOneWidget);
  });

  testWidgets('a cashier without sales view sees neither', (t) async {
    await _pump(t, {Perms.sessionOpen}, const Size(1366, 768));
    expect(find.text('Sales dashboard'), findsNothing);
    expect(find.text("Today's sales"), findsNothing);
    expect(find.byIcon(Icons.insights_outlined), findsNothing);
  });

  for (final size in const [Size(800, 1280), Size(1024, 768), Size(390, 844)]) {
    testWidgets('fits at ${size.width.toInt()}x${size.height.toInt()}',
        (t) async {
      await _pump(t, {Perms.salesInvoiceView}, size);
      expect(t.takeException(), isNull);
      expect(find.text('Sales dashboard'), findsOneWidget);
      expect(find.text("Today's sales"), findsOneWidget);
    });
  }
}
