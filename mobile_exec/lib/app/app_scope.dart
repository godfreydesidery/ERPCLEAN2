import 'package:flutter/widgets.dart';

import '../core/session.dart';
import '../services/catalog_service.dart';
import '../services/operations_service.dart';
import '../services/sales_service.dart';
import '../services/stock_service.dart';

/// Holds the session and the services built on it, and rebuilds dependents
/// when the session changes (sign-in, branch switch, sign-out).
class AppScope extends InheritedNotifier<Session> {
  AppScope({super.key, required Session session, required super.child})
      : catalog = CatalogService(session),
        stock = StockService(session),
        sales = SalesService(session),
        operations = OperationsService(session),
        super(notifier: session);

  final CatalogService catalog;
  final StockService stock;
  final SalesService sales;
  final OperationsService operations;

  Session get session => notifier!;

  static AppScope of(BuildContext context) {
    final scope = context.dependOnInheritedWidgetOfExactType<AppScope>();
    assert(scope != null, 'No AppScope above this widget');
    return scope!;
  }

  @override
  bool updateShouldNotify(AppScope oldWidget) =>
      super.updateShouldNotify(oldWidget) || oldWidget.notifier != notifier;
}
