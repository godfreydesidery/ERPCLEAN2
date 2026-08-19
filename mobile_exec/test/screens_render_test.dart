// Every screen must render on a phone-sized surface without throwing and
// without a layout overflow, on a normal handset and on a small one.
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';

import 'package:orbix_hq/app/theme.dart';
import 'package:orbix_hq/features/close_session_screen.dart';
import 'package:orbix_hq/features/create_item_screen.dart';
import 'package:orbix_hq/features/create_supplier_screen.dart';
import 'package:orbix_hq/features/dashboard_screen.dart';
import 'package:orbix_hq/features/operations_screen.dart';
import 'package:orbix_hq/features/receive_goods_screen.dart';
import 'package:orbix_hq/features/reports_screen.dart';
import 'package:orbix_hq/features/sales_report_screen.dart';
import 'package:orbix_hq/features/settings_screen.dart';
import 'package:orbix_hq/features/sign_in_screen.dart';
import 'package:orbix_hq/features/stock_adjustment_screen.dart';
import 'package:orbix_hq/features/stock_report_screen.dart';
import 'package:orbix_hq/features/stock_valuation_screen.dart';

Map<String, Widget> buildScreens() => <String, Widget>{
      'sign in': SignInScreen(onSignedIn: () {}),
      'dashboard': DashboardScreen(onNavigate: (_) {}),
      'reports': ReportsScreen(onNavigate: (_) {}),
      'operations': OperationsScreen(onNavigate: (_) {}),
      'sales report': const SalesReportScreen(),
      'stock report': const StockReportScreen(),
      'stock valuation': const StockValuationScreen(),
      'receive goods': const ReceiveGoodsScreen(),
      'stock adjustment': const StockAdjustmentScreen(),
      'create item': const CreateItemScreen(),
      'create supplier': const CreateSupplierScreen(),
      'close session': const CloseSessionScreen(),
      'settings': SettingsScreen(onSignOut: () {}),
    };

void main() {
  // A common mid-range Android, and a small one.
  const sizes = <String, Size>{
    '412x915': Size(412, 915),
    '360x640': Size(360, 640),
  };

  sizes.forEach((sizeName, size) {
    group('at $sizeName', () {
      buildScreens().forEach((name, screen) {
        testWidgets('$name renders clean', (tester) async {
          tester.view.physicalSize = size;
          tester.view.devicePixelRatio = 1.0;
          addTearDown(tester.view.reset);

          await tester.pumpWidget(
            MaterialApp(theme: buildHqTheme(), home: screen),
          );
          await tester.pumpAndSettle();

          expect(tester.takeException(), isNull,
              reason: '$name threw at $sizeName');
        });
      });
    });
  });
}
