import '../app/format.dart';
import '../core/api/api_exception.dart';
import '../core/json.dart';
import '../core/session.dart';

// The restaurant dashboard and reports (Cafe Tulia, ERP 1.11.4+): the same
// `/reports/restaurant/*` endpoints the web app uses. Money arrives as JSON
// numbers in the company's base currency; a voided receipt is never in a total.

/// One headline figure set: the period, or the period just before it.
class RestaurantKpis {
  const RestaurantKpis({
    required this.sales,
    required this.receipts,
    required this.averageReceipt,
    required this.orders,
    required this.guests,
    required this.spendPerGuest,
    required this.discounts,
    required this.complimentary,
    required this.refunds,
    required this.voidedReceipts,
    required this.cancelledOrders,
    required this.voidedItems,
  });

  factory RestaurantKpis.fromJson(Map<String, dynamic> j) => RestaurantKpis(
        sales: asNumOr(j['sales']),
        receipts: asIntOr(j['receipts']),
        averageReceipt: asNumOr(j['averageReceipt']),
        orders: asIntOr(j['orders']),
        guests: asIntOr(j['guests']),
        spendPerGuest: asNumOr(j['spendPerGuest']),
        discounts: asNumOr(j['discounts']),
        complimentary: asNumOr(j['complimentary']),
        refunds: asNumOr(j['refunds']),
        voidedReceipts: asIntOr(j['voidedReceipts']),
        cancelledOrders: asIntOr(j['cancelledOrders']),
        voidedItems: asNumOr(j['voidedItems']),
      );

  final double sales;
  final int receipts;
  final double averageReceipt;
  final int orders;
  final int guests;
  final double spendPerGuest;
  final double discounts;
  final double complimentary;
  final double refunds;
  final int voidedReceipts;
  final int cancelledOrders;
  final double voidedItems;
}

/// A label and an amount with a count beside it: an hour, a weekday, a
/// department, a best seller, a waiter or a payment type.
class RestaurantBar {
  const RestaurantBar(this.label, this.amount, this.count, {this.note = ''});

  final String label;
  final double amount;
  final double count;

  /// Extra words for the count, e.g. "4 days".
  final String note;
}

class RestaurantComp {
  const RestaurantComp(this.receipt, this.at, this.givenTo, this.amount);

  final String receipt;
  final String at;
  final String givenTo;
  final double amount;
}

class RestaurantDashboard {
  const RestaurantDashboard({
    required this.fromDate,
    required this.toDate,
    required this.previousFromDate,
    required this.previousToDate,
    required this.currency,
    required this.current,
    required this.previous,
    required this.openOrders,
    required this.billedOrders,
    required this.billedAmount,
    required this.days,
    required this.hours,
    required this.weekdays,
    required this.departments,
    required this.topItems,
    required this.waiters,
    required this.tenders,
    required this.complimentary,
    this.generatedAt,
  });

  factory RestaurantDashboard.fromJson(Map<String, dynamic> j) {
    final live = asMap(j['live']);
    return RestaurantDashboard(
      fromDate: asStrOr(j['fromDate']),
      toDate: asStrOr(j['toDate']),
      previousFromDate: asStrOr(j['previousFromDate']),
      previousToDate: asStrOr(j['previousToDate']),
      currency: asStrOr(j['currency'], 'TZS'),
      current: RestaurantKpis.fromJson(asMap(j['current'])),
      previous: RestaurantKpis.fromJson(asMap(j['previous'])),
      openOrders: asIntOr(live['openOrders']),
      billedOrders: asIntOr(live['billedOrders']),
      billedAmount: asNumOr(live['billedAmount']),
      days: asList(j['days'], (d) => RestaurantBar(
          asStrOr(d['date']), asNumOr(d['sales']), asNumOr(d['receipts']))),
      hours: asList(j['hours'], (h) {
        final hour = asIntOr(h['hour']);
        return RestaurantBar(hourLabel(hour), asNumOr(h['sales']),
            asNumOr(h['receipts']));
      }),
      weekdays: asList(j['weekdays'], (w) {
        final n = asIntOr(w['days']);
        return RestaurantBar(asStrOr(w['label']), asNumOr(w['averagePerDay']),
            asNumOr(w['receipts']),
            note: n == 1 ? '1 day' : '$n days');
      }),
      departments: asList(j['departments'], (d) => RestaurantBar(
          asStrOr(d['name']), asNumOr(d['sales']), asNumOr(d['quantity']))),
      topItems: asList(j['topItems'], (i) => RestaurantBar(
          asStrOr(i['name'], asStrOr(i['code'])), asNumOr(i['sales']),
          asNumOr(i['quantity']),
          note: asStrOr(i['unit']))),
      waiters: asList(j['waiters'], (w) => RestaurantBar(
          w['waiterUid'] == null
              ? counterSales
              : asStrOr(w['waiterName']),
          asNumOr(w['amount']),
          asNumOr(w['bills']))),
      tenders: asList(j['tenders'], (t) => RestaurantBar(
          tenderLabel(asStrOr(t['tenderType'])), asNumOr(t['amount']),
          asNumOr(t['payments']))),
      complimentary: asList(j['complimentary'], (c) => RestaurantComp(
          asStrOr(c['receiptNumber']), asStrOr(c['at']),
          asStrOr(c['givenTo']), asNumOr(c['amount']))),
      generatedAt: asStr(j['generatedAt']),
    );
  }

  /// The waiter row for sales rung up without a table order.
  static const counterSales = 'Counter sales (no table order)';

  final String fromDate;
  final String toDate;
  final String previousFromDate;
  final String previousToDate;
  final String currency;
  final RestaurantKpis current;
  final RestaurantKpis previous;
  final int openOrders;
  final int billedOrders;
  final double billedAmount;
  final List<RestaurantBar> days;
  final List<RestaurantBar> hours;
  final List<RestaurantBar> weekdays;
  final List<RestaurantBar> departments;
  final List<RestaurantBar> topItems;
  final List<RestaurantBar> waiters;
  final List<RestaurantBar> tenders;
  final List<RestaurantComp> complimentary;
  final String? generatedAt;
}

/// "07:00–08:00".
String hourLabel(int h) =>
    '${h.toString().padLeft(2, '0')}:00–${((h + 1) % 24).toString().padLeft(2, '0')}:00';

/// CASH → Cash, MOBILE_MONEY → Mobile money.
String tenderLabel(String code) {
  if (code.isEmpty) return '';
  final s = code.replaceAll('_', ' ').toLowerCase();
  return s[0].toUpperCase() + s.substring(1);
}

/// The percentage change from [before] to [now]; null when there is no
/// earlier figure to compare with.
double? changePct(double now, double before) =>
    before == 0 ? null : (now - before) / before.abs() * 100;

/// One of the restaurant reports, already flattened into a table: what the
/// screen lists and what goes out as a PDF or spreadsheet are the same rows.
class RestaurantTable {
  const RestaurantTable({
    required this.columns,
    required this.rows,
    required this.totals,
    required this.currency,
    this.money = const <int>{},
    this.counts = const <int>{},
    this.notes = const <String>[],
    this.generatedAt,
  });

  final List<String> columns;

  /// Each cell is a String or a double; [money] and [counts] say which
  /// columns hold amounts and plain numbers.
  final List<List<Object>> rows;

  /// Label → value lines under the table: a double is an amount of money,
  /// a String is already worded (a count, a rate).
  final List<(String, Object)> totals;
  final Set<int> money;
  final Set<int> counts;
  final String currency;
  final List<String> notes;
  final String? generatedAt;
}

/// The reports, each with a summary and (where it has one) a detail view.
enum RestaurantReport {
  waiterSales('Sales by waiter', 'waiter-sales', 'waiter-items', true),
  bills('Bills paid', 'bills-summary', 'bills', true),
  pending('Pending bills', 'pending-summary', 'pending-bills', false),
  hours('Sales by hour & day', 'daypart', null, true),
  payments('Payments & completion', 'payments', null, true);

  const RestaurantReport(this.label, this.summaryPath, this.detailPath,
      this.hasDates);

  final String label;
  final String summaryPath;
  final String? detailPath;

  /// Pending bills are "now"; every other report covers a date range.
  final bool hasDates;
}

class RestaurantService {
  RestaurantService(this.session);

  final Session session;

  static String _d(DateTime d) =>
      '${d.year.toString().padLeft(4, '0')}-'
      '${d.month.toString().padLeft(2, '0')}-'
      '${d.day.toString().padLeft(2, '0')}';

  Future<Map<String, dynamic>> _get(String path,
      {DateTime? from, DateTime? to}) async {
    try {
      final res = await session.api.get('/reports/restaurant/$path', query: {
        if (from != null) 'fromDate': _d(from),
        if (to != null) 'toDate': _d(to),
        if (session.activeBranch != null) 'branchUid': session.activeBranch!.uid,
      });
      return asMap(res);
    } on ApiException catch (e) {
      // An ERP older than the restaurant reports has no such address.
      if (e.isNotFound) {
        throw ApiException(
          statusCode: 404,
          message: 'This server does not have the restaurant reports yet. '
              'Ask for the ERP to be updated.',
        );
      }
      rethrow;
    }
  }

  Future<RestaurantDashboard> dashboard(DateTime from, DateTime to) async =>
      RestaurantDashboard.fromJson(await _get('dashboard', from: from, to: to));

  Future<RestaurantTable> report(RestaurantReport report,
      {required bool detail,
      required DateTime from,
      required DateTime to}) async {
    final path = detail && report.detailPath != null
        ? report.detailPath!
        : report.summaryPath;
    final j = await _get(path,
        from: report.hasDates ? from : null, to: report.hasDates ? to : null);
    return tableFor(path, j);
  }

  /// Flattens the JSON of [path] into a table. Public for the tests.
  static RestaurantTable tableFor(String path, Map<String, dynamic> j) {
    final cur = asStrOr(j['currency'], 'TZS');
    final at = asStr(j['generatedAt']);
    switch (path) {
      case 'waiter-sales':
        final t = asMap(j['totals']);
        return RestaurantTable(
          columns: const ['Waiter', 'Bills', 'Guests', 'Average bill', 'Amount'],
          rows: asList(j['rows'], (r) => <Object>[
                asStrOr(r['waiterName']), asNumOr(r['bills']),
                asNumOr(r['guests']), asNumOr(r['averageBill']),
                asNumOr(r['amount'])]),
          totals: [
            ('Bills', qty(asNumOr(t['bills']))),
            ('Guests', qty(asNumOr(t['guests']))),
            ('Amount', asNumOr(t['amount'])),
          ],
          counts: const {1, 2},
          money: const {3, 4},
          currency: cur,
          generatedAt: at,
        );
      case 'waiter-items':
        return RestaurantTable(
          columns: const ['Waiter', 'Item', 'Qty', 'Amount'],
          rows: asList(j['rows'], (r) => <Object>[
                asStrOr(r['waiterName']),
                asStrOr(r['name'], asStrOr(r['code'])),
                asNumOr(r['quantity']), asNumOr(r['amount'])]),
          totals: [
            for (final w in asList(j['waiters'], (w) => w))
              ('${asStrOr(w['waiterName'])} · ${asIntOr(w['bills'])} bills',
                  asNumOr(w['amount'])),
            ('Total', asNumOr(j['totalAmount'])),
          ],
          counts: const {2},
          money: const {3},
          currency: cur,
          generatedAt: at,
        );
      case 'bills-summary':
        final types = _strs(j['tenderTypes']).map(tenderLabel).toList();
        List<Object> row(Map<String, dynamic> r) => <Object>[
              asStrOr(r['waiterName']), asNumOr(r['bills']),
              asNumOr(r['guests']),
              ..._nums(r['tenders']),
              asNumOr(r['amount'])];
        final t = asMap(j['totals']);
        final tenders = _nums(t['tenders']);
        return RestaurantTable(
          columns: ['Waiter', 'Bills', 'Guests', ...types, 'Amount'],
          rows: asList(j['rows'], row),
          totals: [
            ('Bills', qty(asNumOr(t['bills']))),
            for (var i = 0; i < types.length && i < tenders.length; i++)
              (types[i], tenders[i]),
            ('Amount', asNumOr(t['amount'])),
          ],
          counts: const {1, 2},
          money: {for (var i = 3; i < 4 + types.length; i++) i},
          currency: cur,
          generatedAt: at,
        );
      case 'bills':
        return RestaurantTable(
          columns: const ['Paid', 'Table', 'Waiter', 'Receipt', 'Paid by', 'Amount'],
          rows: asList(j['rows'], (r) {
            final voided = asStrOr(r['invoiceStatus']) != 'FINALISED';
            return <Object>[
              asStrOr(r['paidAt']), asStrOr(r['servedAt']),
              asStrOr(r['waiterName']),
              '${asStrOr(r['invoiceNumber'])}${voided ? ' (void)' : ''}',
              asStrOr(r['tenders']).split(', ').map(tenderLabel).join(', '),
              asNumOr(r['amount'])];
          }),
          totals: [
            ('Bills', qty(asNumOr(j['billCount']))),
            ('Amount', asNumOr(j['totalAmount'])),
          ],
          money: const {5},
          currency: cur,
          notes: const ['A voided receipt is listed but left out of the totals.'],
          generatedAt: at,
        );
      case 'pending-summary':
        final t = asMap(j['totals']);
        return RestaurantTable(
          columns: const ['Waiter', 'Open', 'Bill printed', 'Items', 'Bill amount', 'Oldest order'],
          rows: asList(j['rows'], (r) => <Object>[
                asStrOr(r['waiterName']), asNumOr(r['open']),
                asNumOr(r['billed']), asNumOr(r['items']),
                asNumOr(r['billedAmount']), asStrOr(r['oldestOpenedAt'])]),
          totals: [
            ('Open', qty(asNumOr(t['open']))),
            ('Bill printed', qty(asNumOr(t['billed']))),
            ('Waiting to be paid', asNumOr(t['billedAmount'])),
          ],
          counts: const {1, 2, 3},
          money: const {4},
          currency: cur,
          notes: const ['An open order has no amount until its bill is printed.'],
          generatedAt: at,
        );
      case 'pending-bills':
        return RestaurantTable(
          columns: const ['Opened', 'Table', 'Waiter', 'Status', 'Items', 'Bill'],
          rows: asList(j['rows'], (r) => <Object>[
                asStrOr(r['openedAt']), asStrOr(r['servedAt']),
                asStrOr(r['waiterName']),
                asStrOr(r['status']) == 'BILLED' ? 'Bill printed' : 'Open',
                asNumOr(r['items']), asNumOr(r['billedAmount'])]),
          totals: [
            ('Open', qty(asNumOr(j['openCount']))),
            ('Bill printed', qty(asNumOr(j['billedCount']))),
            ('Waiting to be paid', asNumOr(j['billedAmount'])),
          ],
          counts: const {4},
          money: const {5},
          currency: cur,
          notes: const ['An open order has no amount until its bill is printed.'],
          generatedAt: at,
        );
      case 'daypart':
        final depts = _strs(j['departments']);
        List<Object> slot(Map<String, dynamic> s) => <Object>[
              asStrOr(s['label']).replaceAll('-', '–'), asNumOr(s['receipts']),
              asNumOr(s['sales']), asNumOr(s['averagePerDay']),
              ..._nums(s['departmentSales'])];
        final hours = asList(j['hours'], (s) => s)
            .where((s) => asNumOr(s['receipts']) > 0 || asNumOr(s['sales']) != 0)
            .map(slot);
        final t = asMap(j['totals']);
        return RestaurantTable(
          columns: ['Time', 'Receipts', 'Sales', 'Per day', ...depts],
          rows: [...hours, ...asList(j['weekdays'], slot)],
          totals: [
            ('Receipts', qty(asNumOr(t['receipts']))),
            ('Sales', asNumOr(t['sales'])),
          ],
          counts: const {1},
          money: {for (var i = 2; i < 4 + depts.length; i++) i},
          currency: cur,
          notes: const [
            'Hours with sales first, then each day of the week. A receipt '
                'counts at the time it was paid.',
          ],
          generatedAt: at,
        );
      case 'payments':
        final c = asMap(j['completion']);
        final rate = asNum(c['completionRate']);
        String reason(Object? r) => asStrOr(r).isEmpty ? '' : ' — ${asStrOr(r)}';
        return RestaurantTable(
          columns: const ['Section', 'What', 'When', 'Amount'],
          rows: [
            for (final t in asList(j['tenders'], (t) => t))
              <Object>['Payment', '${tenderLabel(asStrOr(t['tenderType']))} · '
                  '${asIntOr(t['payments'])} payments · '
                  '${asNumOr(t['share']).toStringAsFixed(1)}%', '',
                  asNumOr(t['amount'])],
            for (final r in asList(j['refunds'], (r) => r))
              <Object>['Refund', '${asStrOr(r['receiptNumber'])}${reason(r['reason'])}',
                  asStrOr(r['at']), asNumOr(r['amount'])],
            for (final v in asList(j['voidedReceipts'], (v) => v))
              <Object>['Voided receipt', '${asStrOr(v['receiptNumber'])} · ${asStrOr(v['by'])}',
                  asStrOr(v['soldAt']), asNumOr(v['amount'])],
            for (final o in asList(j['cancelledOrders'], (o) => o))
              <Object>['Cancelled order',
                  '${asStrOr(o['orderNumber'])} ${asStrOr(o['servedAt'])} · '
                      '${asStrOr(o['waiterName'])}${reason(o['reason'])}',
                  asStrOr(o['cancelledAt']), ''],
            for (final v in asList(j['voidedItems'], (v) => v))
              <Object>['Voided item',
                  '${qty(asNumOr(v['quantity']))} × '
                      '${asStrOr(v['productName'])} (${asStrOr(v['orderNumber'])}, '
                      '${asStrOr(v['waiterName'])})${reason(v['reason'])}',
                  asStrOr(v['voidedAt']), ''],
          ],
          totals: [
            ('Total paid', asNumOr(j['totalPaid'])),
            ('Refunded', asNumOr(j['refundTotal'])),
            ('Voided receipts', asNumOr(j['voidedReceiptTotal'])),
            ('Orders opened', qty(asNumOr(c['opened']))),
            ('Paid', qty(asNumOr(c['paid']))),
            ('Cancelled', qty(asNumOr(c['cancelled']))),
            ('Not yet paid', qty(asNumOr(c['pending']))),
            ('Completion rate', rate == null ? '—' : '${rate.toStringAsFixed(1)}%'),
          ],
          money: const {3},
          currency: cur,
          generatedAt: at,
        );
    }
    throw ArgumentError('Unknown restaurant report $path');
  }
}

List<double> _nums(dynamic v) =>
    v is List ? v.map((x) => asNumOr(x)).toList() : <double>[];

List<String> _strs(dynamic v) =>
    v is List ? v.map((x) => '$x').toList() : <String>[];
