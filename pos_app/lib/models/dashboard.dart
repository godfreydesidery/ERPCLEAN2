import '../core/json.dart';

/// The till's sales dashboard (`GET /pos/dashboard`, Tulia 2026-11-02): the
/// active branch's finalised sales over a date range. No cost or profit.
class PosDashboard {
  PosDashboard({
    required this.fromDate,
    required this.toDate,
    required this.branchName,
    required this.currency,
    required this.grossTotal,
    required this.invoiceCount,
    required this.days,
    required this.tenders,
    required this.departments,
  });

  final String fromDate;
  final String toDate;
  final String branchName;
  final String currency;
  final double grossTotal;
  final int invoiceCount;
  final List<DashboardDay> days;
  final List<DashboardTender> tenders;
  final List<DashboardDepartment> departments;

  factory PosDashboard.fromJson(Map<String, dynamic> j) => PosDashboard(
        fromDate: asStrOr(j['fromDate']),
        toDate: asStrOr(j['toDate']),
        branchName: asStrOr(j['branchName']),
        currency: asStrOr(j['currency'], 'TZS'),
        grossTotal: asNumOr(j['grossTotal']),
        invoiceCount: asIntOr(j['invoiceCount']),
        days: asList(j['days'], DashboardDay.fromJson),
        tenders: asList(j['tenders'], DashboardTender.fromJson),
        departments: asList(j['departments'], DashboardDepartment.fromJson),
      );
}

class DashboardDay {
  DashboardDay({required this.date, required this.invoices, required this.gross});
  final String date;
  final int invoices;
  final double gross;

  factory DashboardDay.fromJson(Map<String, dynamic> j) => DashboardDay(
      date: asStrOr(j['date']), invoices: asIntOr(j['invoices']), gross: asNumOr(j['gross']));
}

class DashboardTender {
  DashboardTender({required this.tenderType, required this.payments, required this.amount});
  final String tenderType;
  final int payments;
  final double amount;

  factory DashboardTender.fromJson(Map<String, dynamic> j) => DashboardTender(
      tenderType: asStrOr(j['tenderType']),
      payments: asIntOr(j['payments']),
      amount: asNumOr(j['amount']));
}

class DashboardDepartment {
  DashboardDepartment(
      {required this.department, required this.quantity, required this.gross, required this.items});
  final String department;
  final double quantity;
  final double gross;
  final List<DashboardItem> items;

  factory DashboardDepartment.fromJson(Map<String, dynamic> j) => DashboardDepartment(
        department: asStrOr(j['department']),
        quantity: asNumOr(j['quantity']),
        gross: asNumOr(j['gross']),
        items: asList(j['items'], DashboardItem.fromJson),
      );
}

class DashboardItem {
  DashboardItem(
      {required this.code,
      required this.name,
      required this.unit,
      required this.quantity,
      required this.gross});
  final String code;
  final String name;
  final String unit;
  final double quantity;
  final double gross;

  factory DashboardItem.fromJson(Map<String, dynamic> j) => DashboardItem(
        code: asStrOr(j['code']),
        name: asStrOr(j['name']),
        unit: asStrOr(j['unit']),
        quantity: asNumOr(j['quantity']),
        gross: asNumOr(j['gross']),
      );
}
