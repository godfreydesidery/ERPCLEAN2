// Every screen must render on a phone-sized surface without throwing and
// without a layout overflow. Eighteen screens were written in parallel; this is
// the gate that proves they compose.
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';

import 'package:orbix_hq/app/theme.dart';
import 'package:orbix_hq/features/approval_detail_screen.dart';
import 'package:orbix_hq/features/approvals_screen.dart';
import 'package:orbix_hq/features/branch_detail_screen.dart';
import 'package:orbix_hq/features/branch_league_screen.dart';
import 'package:orbix_hq/features/budget_screen.dart';
import 'package:orbix_hq/features/cash_screen.dart';
import 'package:orbix_hq/features/customers_screen.dart';
import 'package:orbix_hq/features/debtors_screen.dart';
import 'package:orbix_hq/features/governance_screen.dart';
import 'package:orbix_hq/features/home_screen.dart';
import 'package:orbix_hq/features/margin_screen.dart';
import 'package:orbix_hq/features/notifications_screen.dart';
import 'package:orbix_hq/features/people_screen.dart';
import 'package:orbix_hq/features/reports_screen.dart';
import 'package:orbix_hq/features/settings_screen.dart';
import 'package:orbix_hq/features/sign_in_screen.dart';
import 'package:orbix_hq/features/stock_screen.dart';
import 'package:orbix_hq/features/todays_trade_screen.dart';

void main() {
  final screens = <String, Widget>{
    'sign in': SignInScreen(onSignedIn: () {}),
    'morning brief': HomeScreen(onNavigate: (_) {}),
    'approvals': ApprovalsScreen(onOpen: (_) {}),
    'approval detail': const ApprovalDetailScreen(index: 0),
    'reports': ReportsScreen(onOpen: (_) {}),
    'cash': const CashScreen(),
    'margin': const MarginScreen(),
    'debtors': const DebtorsScreen(),
    'branch league': const BranchLeagueScreen(),
    "today's trade": const TodaysTradeScreen(),
    'stock': const StockScreen(),
    'governance': const GovernanceScreen(),
    'people': const PeopleScreen(),
    'budget': const BudgetScreen(),
    'customers': const CustomersScreen(),
    'notifications': const NotificationsScreen(),
    'branch detail': const BranchDetailScreen(branchName: 'Arusha'),
    'settings': SettingsScreen(onSignOut: () {}),
  };

  // A common mid-range Android the audience actually carries.
  const phone = Size(412, 915);

  screens.forEach((name, screen) {
    testWidgets('$name renders clean', (tester) async {
      tester.view.physicalSize = phone;
      tester.view.devicePixelRatio = 1.0;
      addTearDown(tester.view.reset);

      await tester.pumpWidget(
        MaterialApp(theme: buildHqTheme(), home: screen),
      );
      await tester.pumpAndSettle();

      expect(tester.takeException(), isNull, reason: '$name threw while building');
    });
  });

  testWidgets('every screen also survives a small phone', (tester) async {
    tester.view.physicalSize = const Size(360, 640);
    tester.view.devicePixelRatio = 1.0;
    addTearDown(tester.view.reset);

    for (final entry in screens.entries) {
      await tester.pumpWidget(
        MaterialApp(theme: buildHqTheme(), home: entry.value),
      );
      await tester.pumpAndSettle();
      expect(tester.takeException(), isNull,
          reason: '${entry.key} threw at 360x640');
    }
  });
}
