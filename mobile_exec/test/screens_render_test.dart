// Every screen must render on a phone-sized surface without throwing and
// without a layout overflow, on a normal handset and on a small one.
//
// The screens now load from the network. These tests mount them inside an
// AppScope holding a signed-out session, so each one paints its permission or
// error state — which is exactly the state a user sees when the server is
// unreachable, and the state most likely to be laid out carelessly.
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shared_preferences/shared_preferences.dart';

import 'package:orbix_hq/app/app_scope.dart';
import 'package:orbix_hq/app/theme.dart';
import 'package:orbix_hq/core/session.dart';
import 'package:orbix_hq/features/close_till_screen.dart';
import 'package:orbix_hq/features/create_item_screen.dart';
import 'package:orbix_hq/features/create_supplier_screen.dart';
import 'package:orbix_hq/features/dashboard_screen.dart';
import 'package:orbix_hq/features/edit_item_screen.dart';
import 'package:orbix_hq/features/operations_screen.dart';
import 'package:orbix_hq/features/pack_sizes.dart';
import 'package:orbix_hq/features/products_screen.dart';
import 'package:orbix_hq/features/receive_goods_screen.dart';
import 'package:orbix_hq/features/restaurant_dashboard_screen.dart';
import 'package:orbix_hq/features/restaurant_reports_screen.dart';
import 'package:orbix_hq/features/reports_screen.dart';
import 'package:orbix_hq/features/sales_report_screen.dart';
import 'package:orbix_hq/features/settings_screen.dart';
import 'package:orbix_hq/features/sign_in_screen.dart';
import 'package:orbix_hq/features/stock_adjustment_screen.dart';
import 'package:orbix_hq/features/stock_report_screen.dart';
import 'package:orbix_hq/features/stock_valuation_screen.dart';
import 'package:orbix_hq/features/till_history_screen.dart';
import 'package:orbix_hq/features/till_report_screen.dart';
import 'package:orbix_hq/services/operations_service.dart';

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
      'pack sizes': const PackSizesScreen(),
      'products': const ProductsScreen(),
      'tills': const TillReportScreen(),
      'closed tills': const TillHistoryScreen(),
      'close till': CloseTillScreen(session: _session, read: _read),
      'edit item': const EditItemScreen(productUid: 'P1'),
      'restaurant dashboard': const RestaurantDashboardScreen(),
      'restaurant reports': const RestaurantReportsScreen(),
      'settings': SettingsScreen(onSignOut: () {}),
    };

// A long till and cashier name on purpose: the close form must not overflow.
final _session = SessionRow.fromJson(const {
  'uid': 'S1',
  'tillName': 'Main counter till number two',
  'cashierName': 'Neema Kileo-Mwakalukwa',
  'openedAt': '2026-11-12T05:30:00Z',
  'status': 'OPEN',
  'openingFloatAmount': 50000,
});

final _read = TillRead.fromJson(const {
  'sessionUid': 'S1',
  'tillName': 'Main counter till number two',
  'cashierName': 'Neema Kileo-Mwakalukwa',
  'openedAt': '2026-11-12T05:30:00Z',
  'openingFloatAmount': 50000,
  'totalSalesAmount': 12345678,
  'cashTenderAmount': 9876543,
  'totalPayoutsNetAmount': 20000,
  'expectedCashAmount': 9906543,
  'invoiceCount': 312,
  'tenderSubtotals': [],
});

void main() {
  const sizes = <String, Size>{
    '412x915': Size(412, 915),
    '360x640': Size(360, 640),
  };

  late Session session;

  setUpAll(() async {
    TestWidgetsFlutterBinding.ensureInitialized();
    SharedPreferences.setMockInitialValues(<String, Object>{});
    session = await Session.create();
  });

  sizes.forEach((sizeName, size) {
    group('at $sizeName', () {
      buildScreens().forEach((name, screen) {
        testWidgets('$name renders clean', (tester) async {
          tester.view.physicalSize = size;
          tester.view.devicePixelRatio = 1.0;
          addTearDown(tester.view.reset);

          await tester.pumpWidget(
            AppScope(
              session: session,
              child: MaterialApp(theme: buildHqTheme(), home: screen),
            ),
          );
          await tester.pump(const Duration(milliseconds: 50));

          expect(tester.takeException(), isNull,
              reason: '$name threw at $sizeName');
        });
      });
    });
  });
}
