import 'package:flutter/material.dart';

import 'app/theme.dart';
import 'features/approval_detail_screen.dart';
import 'features/approvals_screen.dart';
import 'features/branch_detail_screen.dart';
import 'features/branch_league_screen.dart';
import 'features/budget_screen.dart';
import 'features/cash_screen.dart';
import 'features/customers_screen.dart';
import 'features/debtors_screen.dart';
import 'features/governance_screen.dart';
import 'features/home_screen.dart';
import 'features/margin_screen.dart';
import 'features/notifications_screen.dart';
import 'features/people_screen.dart';
import 'features/reports_screen.dart';
import 'features/settings_screen.dart';
import 'features/sign_in_screen.dart';
import 'features/stock_screen.dart';
import 'features/todays_trade_screen.dart';

void main() => runApp(const OrbixHqApp());

class OrbixHqApp extends StatelessWidget {
  const OrbixHqApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'OrbixHQ',
      debugShowCheckedModeBanner: false,
      theme: buildHqTheme(),
      home: const _Root(),
    );
  }
}

class _Root extends StatefulWidget {
  const _Root();

  @override
  State<_Root> createState() => _RootState();
}

class _RootState extends State<_Root> {
  bool _signedIn = false;

  @override
  Widget build(BuildContext context) {
    if (!_signedIn) {
      return SignInScreen(onSignedIn: () => setState(() => _signedIn = true));
    }
    return _Shell(onSignOut: () => setState(() => _signedIn = false));
  }
}

/// Bottom-nav shell. Five destinations, matching how an executive actually
/// moves: the brief, the decisions, the catalogue, the alerts, and themselves.
class _Shell extends StatefulWidget {
  const _Shell({required this.onSignOut});

  final VoidCallback onSignOut;

  @override
  State<_Shell> createState() => _ShellState();
}

class _ShellState extends State<_Shell> {
  int _tab = 0;

  void _push(BuildContext context, Widget screen) {
    Navigator.of(context).push(MaterialPageRoute<void>(builder: (_) => screen));
  }

  /// Route strings are deliberately loose in the demo — every tap lands
  /// somewhere real so the owner can walk the whole product.
  void _navigate(String route) {
    final screen = switch (route) {
      'cash' => const CashScreen(),
      'margin' => const MarginScreen(),
      'debtors' => const DebtorsScreen(),
      'league' => const BranchLeagueScreen(),
      'stock' => const StockScreen(),
      'trade' => const TodaysTradeScreen(),
      'governance' => const GovernanceScreen(),
      'people' => const PeopleScreen(),
      'budget' => const BudgetScreen(),
      'customers' => const CustomersScreen(),
      'arusha' => const BranchDetailScreen(branchName: 'Arusha'),
      _ => const BranchDetailScreen(branchName: 'Arusha'),
    };
    _push(context, screen);
  }

  @override
  Widget build(BuildContext context) {
    final pages = <Widget>[
      HomeScreen(onNavigate: _navigate),
      ApprovalsScreen(
        onOpen: (i) => _push(context, ApprovalDetailScreen(index: i)),
      ),
      ReportsScreen(onOpen: _navigate),
      const NotificationsScreen(),
      SettingsScreen(onSignOut: widget.onSignOut),
    ];

    return Scaffold(
      body: IndexedStack(index: _tab, children: pages),
      bottomNavigationBar: DecoratedBox(
        decoration: const BoxDecoration(
          color: HqColors.panel,
          border: Border(top: BorderSide(color: HqColors.line)),
        ),
        child: NavigationBar(
          selectedIndex: _tab,
          onDestinationSelected: (i) => setState(() => _tab = i),
          backgroundColor: HqColors.panel,
          surfaceTintColor: Colors.transparent,
          indicatorColor: HqColors.brandSoft,
          height: 66,
          labelBehavior: NavigationDestinationLabelBehavior.alwaysShow,
          destinations: const [
            NavigationDestination(
              icon: Icon(Icons.insights_outlined),
              selectedIcon: Icon(Icons.insights, color: HqColors.brand),
              label: 'Brief',
            ),
            NavigationDestination(
              icon: Badge(label: Text('3'), child: Icon(Icons.how_to_reg_outlined)),
              selectedIcon: Badge(
                label: Text('3'),
                child: Icon(Icons.how_to_reg, color: HqColors.brand),
              ),
              label: 'Approvals',
            ),
            NavigationDestination(
              icon: Icon(Icons.donut_small_outlined),
              selectedIcon: Icon(Icons.donut_small, color: HqColors.brand),
              label: 'Reports',
            ),
            NavigationDestination(
              icon: Icon(Icons.notifications_none),
              selectedIcon: Icon(Icons.notifications, color: HqColors.brand),
              label: 'Alerts',
            ),
            NavigationDestination(
              icon: Icon(Icons.person_outline),
              selectedIcon: Icon(Icons.person, color: HqColors.brand),
              label: 'You',
            ),
          ],
        ),
      ),
    );
  }
}
