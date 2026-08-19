import 'package:flutter/material.dart';

import 'app/theme.dart';
import 'features/close_session_screen.dart';
import 'features/create_item_screen.dart';
import 'features/create_supplier_screen.dart';
import 'features/dashboard_screen.dart';
import 'features/operations_screen.dart';
import 'features/receive_goods_screen.dart';
import 'features/reports_screen.dart';
import 'features/sales_report_screen.dart';
import 'features/settings_screen.dart';
import 'features/sign_in_screen.dart';
import 'features/stock_adjustment_screen.dart';
import 'features/stock_report_screen.dart';
import 'features/stock_valuation_screen.dart';
import 'features/till_report_screen.dart';

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

/// Four tabs: what happened, what it is worth, what to do, and who I am.
class _Shell extends StatefulWidget {
  const _Shell({required this.onSignOut});

  final VoidCallback onSignOut;

  @override
  State<_Shell> createState() => _ShellState();
}

class _ShellState extends State<_Shell> {
  int _tab = 0;

  void _navigate(String route) {
    final screen = switch (route) {
      'sales' => const SalesReportScreen(),
      'stock' => const StockReportScreen(),
      'valuation' => const StockValuationScreen(),
      'receive' => const ReceiveGoodsScreen(),
      'adjust' => const StockAdjustmentScreen(),
      'item' => const CreateItemScreen(),
      'supplier' => const CreateSupplierScreen(),
      'session' => const CloseSessionScreen(),
      'till' => const TillReportScreen(),
      _ => const StockValuationScreen(),
    };
    Navigator.of(context).push(
      MaterialPageRoute<void>(builder: (_) => screen),
    );
  }

  @override
  Widget build(BuildContext context) {
    final pages = <Widget>[
      DashboardScreen(onNavigate: _navigate),
      ReportsScreen(onNavigate: _navigate),
      OperationsScreen(onNavigate: _navigate),
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
              icon: Icon(Icons.space_dashboard_outlined),
              selectedIcon: Icon(Icons.space_dashboard, color: HqColors.brand),
              label: 'Dashboard',
            ),
            NavigationDestination(
              icon: Icon(Icons.assessment_outlined),
              selectedIcon: Icon(Icons.assessment, color: HqColors.brand),
              label: 'Reports',
            ),
            NavigationDestination(
              icon: Icon(Icons.bolt_outlined),
              selectedIcon: Icon(Icons.bolt, color: HqColors.brand),
              label: 'Operations',
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
