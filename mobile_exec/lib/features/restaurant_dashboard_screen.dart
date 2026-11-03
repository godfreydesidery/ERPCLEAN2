import 'package:flutter/material.dart';

import '../app/app_scope.dart';
import '../app/format.dart';
import '../app/theme.dart';
import '../core/export/report_doc.dart';
import '../services/restaurant_service.dart';
import '../widgets/async_view.dart';
import '../widgets/charts.dart';
import '../widgets/common.dart';
import '../widgets/kit.dart';

/// The restaurant at a glance (Cafe Tulia): the day's figures against the
/// period before, the tables still waiting to pay, busy hours, waiters and
/// payments. Shared as the Flash Report.
class RestaurantDashboardScreen extends StatefulWidget {
  const RestaurantDashboardScreen({super.key, this.onOpenReports});

  /// Opens the restaurant reports; null hides the link.
  final VoidCallback? onOpenReports;

  @override
  State<RestaurantDashboardScreen> createState() =>
      _RestaurantDashboardScreenState();
}

/// One key figure: how to read it, and which way is good.
class _Kpi {
  const _Kpi(this.label, this.pick, {this.money = true, this.upIsGood = true});

  final String label;
  final double Function(RestaurantKpis k) pick;
  final bool money;
  final bool upIsGood;
}

final _kpis = <_Kpi>[
  _Kpi('Receipts', (k) => k.receipts.toDouble(), money: false),
  _Kpi('Average receipt', (k) => k.averageReceipt),
  _Kpi('Table orders paid', (k) => k.orders.toDouble(), money: false),
  _Kpi('Guests', (k) => k.guests.toDouble(), money: false),
  _Kpi('Spend per guest', (k) => k.spendPerGuest),
  _Kpi('Discounts', (k) => k.discounts, upIsGood: false),
  _Kpi('Complimentary', (k) => k.complimentary, upIsGood: false),
  _Kpi('Refunds', (k) => k.refunds, upIsGood: false),
  _Kpi('Receipts voided', (k) => k.voidedReceipts.toDouble(),
      money: false, upIsGood: false),
  _Kpi('Orders cancelled', (k) => k.cancelledOrders.toDouble(),
      money: false, upIsGood: false),
  _Kpi('Items voided', (k) => k.voidedItems, money: false, upIsGood: false),
];

class _RestaurantDashboardScreenState extends State<RestaurantDashboardScreen> {
  final _viewKey = GlobalKey<AsyncViewState<RestaurantDashboard>>();

  int _period = 0;
  late DateTimeRange _range = _presetRange(0);

  static const _periods = [
    'Today',
    'Yesterday',
    'This week',
    'This month',
    'Custom',
  ];

  static DateTimeRange _presetRange(int i) {
    final now = DateTime.now();
    final today = DateTime(now.year, now.month, now.day);
    return switch (i) {
      1 => DateTimeRange(
          start: today.subtract(const Duration(days: 1)),
          end: today.subtract(const Duration(days: 1))),
      2 => DateTimeRange(
          start: today.subtract(Duration(days: today.weekday - 1)),
          end: today),
      3 => DateTimeRange(start: DateTime(now.year, now.month, 1), end: today),
      _ => DateTimeRange(start: today, end: today),
    };
  }

  void _applyPreset(int i) {
    if (i == _periods.length - 1) return;
    setState(() {
      _period = i;
      _range = _presetRange(i);
    });
    _viewKey.currentState?.reload();
  }

  @override
  Widget build(BuildContext context) {
    final session = AppScope.of(context).session;
    final branch = session.activeBranch?.name ?? '';

    return Scaffold(
      backgroundColor: HqColors.bg,
      appBar: AppBar(
        title: const Text('Restaurant', style: HqText.title),
        actions: [
          shareAction(context, () {
            final data = _viewKey.currentState?.data;
            return data == null ? null : flashReport(data, branch);
          }),
          const SizedBox(width: 6),
        ],
      ),
      body: !session.can('SALES.INVOICE.VIEW')
          ? const NoPermission(code: 'SALES.INVOICE.VIEW')
          : Column(
              children: [
                Padding(
                  padding: const EdgeInsets.fromLTRB(20, 4, 20, 0),
                  child: Column(
                    children: [
                      FilterChipsRow(
                        options: _periods,
                        selected: _period,
                        onSelected: _applyPreset,
                      ),
                      const SizedBox(height: 12),
                      DateRangeBar(
                        range: _range,
                        onChanged: (r) {
                          setState(() {
                            _range = r;
                            _period = _periods.length - 1;
                          });
                          _viewKey.currentState?.reload();
                        },
                      ),
                      const SizedBox(height: 12),
                    ],
                  ),
                ),
                Expanded(
                  child: AsyncView<RestaurantDashboard>(
                    key: _viewKey,
                    load: () => AppScope.of(context)
                        .restaurant
                        .dashboard(_range.start, _range.end),
                    builder: (context, d) => RefreshIndicator(
                      onRefresh: () async => _viewKey.currentState?.reload(),
                      child: RestaurantDashboardBody(
                        data: d,
                        period: formatRange(_range),
                        branch: branch,
                        onOpenReports: widget.onOpenReports,
                      ),
                    ),
                  ),
                ),
              ],
            ),
    );
  }
}

/// The loaded dashboard. Public so its layout can be tested with data.
class RestaurantDashboardBody extends StatelessWidget {
  const RestaurantDashboardBody({
    super.key,
    required this.data,
    required this.period,
    required this.branch,
    this.onOpenReports,
  });

  final RestaurantDashboard data;
  final String period;
  final String branch;
  final VoidCallback? onOpenReports;

  @override
  Widget build(BuildContext context) {
    final d = data;
    final change = changePct(d.current.sales, d.previous.sales);
    final busyHours = d.hours.where((h) => h.amount != 0 || h.count > 0).toList();

    return ListView(
      padding: const EdgeInsets.fromLTRB(20, 0, 20, 28),
      children: [
        _Hero(
          period: period,
          sales: d.current.sales,
          change: change,
          receipts: d.current.receipts,
          guests: d.current.guests,
        ),
        const SizedBox(height: 16),
        _RightNow(data: d),
        const SizedBox(height: 20),
        const SectionLabel(text: 'KEY FIGURES', trailing: 'VS BEFORE'),
        const SizedBox(height: 10),
        _KpiGrid(current: d.current, previous: d.previous),
        if (d.days.length > 1) ...[
          const SizedBox(height: 20),
          const SectionLabel(text: 'SALES BY DAY'),
          const SizedBox(height: 10),
          HqCard(
            child: ColumnTrend(
              values: [for (final x in d.days) x.amount],
              labels: [for (final x in d.days) x.label.substring(8)],
              hatchLast: false,
            ),
          ),
        ],
        _Bars(
          title: 'BUSY HOURS',
          bars: busyHours,
          countWord: 'receipts',
          empty: 'No sales in this period.',
        ),
        _Bars(
          title: 'DAYS OF THE WEEK',
          trailing: 'AVERAGE PER DAY',
          bars: d.weekdays.where((w) => w.note != '0 days').toList(),
          countWord: 'receipts',
        ),
        _Bars(title: 'DEPARTMENTS', bars: d.departments, countWord: 'sold'),
        _Bars(title: 'BEST SELLERS', bars: d.topItems, countWord: ''),
        _Bars(title: 'WAITERS', bars: d.waiters, countWord: 'bills'),
        _Bars(
          title: 'HOW BILLS WERE PAID',
          bars: d.tenders,
          countWord: 'payments',
          empty: 'No payments in this period.',
        ),
        if (d.complimentary.isNotEmpty) ...[
          const SizedBox(height: 20),
          SectionLabel(
              text: 'COMPLIMENTARY',
              trailing: '${d.complimentary.length} BILLS'),
          const SizedBox(height: 10),
          HqCard(
            child: Column(
              children: [
                for (var i = 0; i < d.complimentary.length; i++) ...[
                  if (i > 0) const Divider(height: 18),
                  _CompRow(comp: d.complimentary[i]),
                ],
              ],
            ),
          ),
        ],
        const SizedBox(height: 16),
        FilledButton.icon(
          onPressed: () => showShareSheet(context, flashReport(d, branch)),
          icon: const Icon(Icons.ios_share_rounded, size: 19),
          label: const Text('Share the flash report'),
        ),
        if (onOpenReports != null) ...[
          const SizedBox(height: 10),
          OutlinedButton.icon(
            onPressed: onOpenReports,
            icon: const Icon(Icons.table_rows_outlined, size: 19),
            label: const Text('Restaurant reports'),
          ),
        ],
        const SizedBox(height: 16),
        AsOfLine(
          asOf: d.generatedAt == null
              ? 'Live from the server'
              : 'Generated ${d.generatedAt}',
          coverage: branch,
        ),
      ],
    );
  }
}

class _Hero extends StatelessWidget {
  const _Hero({
    required this.period,
    required this.sales,
    required this.change,
    required this.receipts,
    required this.guests,
  });

  final String period;
  final double sales;
  final double? change;
  final int receipts;
  final int guests;

  @override
  Widget build(BuildContext context) {
    final c = change;
    return Container(
      padding: const EdgeInsets.all(20),
      decoration: BoxDecoration(
        gradient: HqSurfaces.heroGradient,
        borderRadius: BorderRadius.circular(20),
        boxShadow: HqSurfaces.hero,
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(
            'SALES · ${period.toUpperCase()}',
            maxLines: 1,
            overflow: TextOverflow.ellipsis,
            style: const TextStyle(
              fontSize: 10.5,
              fontWeight: FontWeight.w700,
              color: HqOnDark.tertiary,
              letterSpacing: 0.8,
            ),
          ),
          const SizedBox(height: 9),
          Amount(
            tzs(sales),
            style: const TextStyle(
              fontSize: 34,
              fontWeight: FontWeight.w700,
              color: Colors.white,
              height: 1.05,
              letterSpacing: -1,
            ),
          ),
          const SizedBox(height: 8),
          Text(
            [
              if (c != null) '${pct(c, sign: true)} vs before',
              '$receipts receipts',
              if (guests > 0) '$guests guests',
            ].join(' · '),
            style: const TextStyle(fontSize: 12.5, color: HqOnDark.secondary),
          ),
        ],
      ),
    );
  }
}

class _RightNow extends StatelessWidget {
  const _RightNow({required this.data});

  final RestaurantDashboard data;

  @override
  Widget build(BuildContext context) {
    return HqCard(
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          const Text('RIGHT NOW',
              style: TextStyle(
                  fontSize: 10.5,
                  fontWeight: FontWeight.w700,
                  color: HqColors.ink3,
                  letterSpacing: 0.8)),
          const SizedBox(height: 10),
          FigureRow(label: 'Tables open', value: '${data.openOrders}'),
          const SizedBox(height: 6),
          FigureRow(
              label: 'Bills printed, not paid', value: '${data.billedOrders}'),
          const SizedBox(height: 6),
          FigureRow(
            label: 'Waiting to be paid',
            value: tzs(data.billedAmount),
            emphasise: true,
          ),
        ],
      ),
    );
  }
}

class _KpiGrid extends StatelessWidget {
  const _KpiGrid({required this.current, required this.previous});

  final RestaurantKpis current;
  final RestaurantKpis previous;

  @override
  Widget build(BuildContext context) {
    final tiles = <Widget>[
      for (final k in _kpis)
        Builder(builder: (_) {
          final now = k.pick(current);
          final c = changePct(now, k.pick(previous));
          return StatTile(
            label: k.label,
            value: k.money ? tzs(now) : qty(now),
            delta: c == null ? null : pct(c, sign: true),
            deltaGood: c == null || c == 0 ? null : (c > 0) == k.upIsGood,
          );
        }),
    ];
    return Column(
      children: [
        for (var i = 0; i < tiles.length; i += 2) ...[
          if (i > 0) const SizedBox(height: 10),
          Row(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Expanded(child: tiles[i]),
              const SizedBox(width: 10),
              Expanded(
                  child: i + 1 < tiles.length
                      ? tiles[i + 1]
                      : const SizedBox.shrink()),
            ],
          ),
        ],
      ],
    );
  }
}

class _Bars extends StatelessWidget {
  const _Bars({
    required this.title,
    required this.bars,
    required this.countWord,
    this.trailing,
    this.empty,
  });

  final String title;
  final List<RestaurantBar> bars;
  final String countWord;
  final String? trailing;

  /// Shown when there are no bars; null hides the whole section instead.
  final String? empty;

  @override
  Widget build(BuildContext context) {
    if (bars.isEmpty && empty == null) return const SizedBox.shrink();
    final best = bars.isEmpty
        ? 0.0
        : bars.map((b) => b.amount).reduce((a, b) => a > b ? a : b);
    return Padding(
      padding: const EdgeInsets.only(top: 20),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          SectionLabel(text: title, trailing: trailing),
          const SizedBox(height: 10),
          HqCard(
            child: bars.isEmpty
                ? Text(empty!, style: HqText.body)
                : Column(
                    children: [
                      for (var i = 0; i < bars.length; i++) ...[
                        if (i > 0) const SizedBox(height: 13),
                        _BarRow(
                          bar: bars[i],
                          detail: _countText(bars[i]),
                          factor: best <= 0
                              ? 0
                              : (bars[i].amount / best).clamp(0.03, 1.0),
                          best: bars[i].amount == best && best > 0,
                        ),
                      ],
                    ],
                  ),
          ),
        ],
      ),
    );
  }

  String _countText(RestaurantBar b) {
    final n = qty(b.count);
    if (b.note.isNotEmpty && countWord.isEmpty) return '$n ${b.note}';
    if (b.note.isNotEmpty) return '$n $countWord, ${b.note}';
    return countWord.isEmpty ? n : '$n $countWord';
  }
}

/// A name and its amount, the count beneath, then the bar. Two lines so a
/// long waiter or dish name never pushes the amount off a small phone.
class _BarRow extends StatelessWidget {
  const _BarRow({
    required this.bar,
    required this.detail,
    required this.factor,
    required this.best,
  });

  final RestaurantBar bar;
  final String detail;
  final double factor;
  final bool best;

  @override
  Widget build(BuildContext context) {
    final weight = best ? FontWeight.w700 : FontWeight.w500;
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        Row(
          children: [
            Expanded(
              child: Text(
                bar.label,
                maxLines: 1,
                overflow: TextOverflow.ellipsis,
                style: HqText.label.copyWith(
                  color: best ? HqColors.ink : HqColors.ink2,
                  fontWeight: weight,
                ),
              ),
            ),
            const SizedBox(width: 10),
            Text(
              tzs(bar.amount),
              style: HqText.label.copyWith(
                color: best ? HqColors.brand : HqColors.ink,
                fontWeight: weight,
                fontFeatures: const [FontFeature.tabularFigures()],
              ),
            ),
          ],
        ),
        const SizedBox(height: 2),
        Text(detail, style: HqText.tiny),
        const SizedBox(height: 5),
        ClipRRect(
          borderRadius: BorderRadius.circular(4),
          child: Container(
            height: 8,
            color: HqColors.line,
            alignment: Alignment.centerLeft,
            child: FractionallySizedBox(
              widthFactor: factor,
              child: Container(
                decoration: BoxDecoration(
                  color: best ? HqColors.brand : VizColors.muted,
                  borderRadius: BorderRadius.circular(4),
                ),
              ),
            ),
          ),
        ),
      ],
    );
  }
}

class _CompRow extends StatelessWidget {
  const _CompRow({required this.comp});

  final RestaurantComp comp;

  @override
  Widget build(BuildContext context) {
    return Row(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Expanded(
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text(
                comp.givenTo.isEmpty ? 'Given to: not recorded' : comp.givenTo,
                maxLines: 2,
                overflow: TextOverflow.ellipsis,
                style: const TextStyle(
                    fontSize: 14,
                    fontWeight: FontWeight.w600,
                    color: HqColors.ink),
              ),
              const SizedBox(height: 2),
              Text('${comp.receipt} · ${comp.at}', style: HqText.tiny),
            ],
          ),
        ),
        const SizedBox(width: 10),
        Text(tzs(comp.amount),
            style: const TextStyle(
                fontSize: 14, fontWeight: FontWeight.w700, color: HqColors.ink)),
      ],
    );
  }
}

/// The dashboard as a document: every key figure against the period before,
/// then what is waiting, payments, departments, best sellers, waiters and
/// complimentary bills.
ExportDoc flashReport(RestaurantDashboard d, String branch) {
  final cur = d.currency;
  Cell fig(double v, bool money) =>
      money ? Cell.money(v, currency: cur) : Cell.number(v, decimals: v % 1 == 0 ? 0 : 2);
  Cell changeCell(double now, double before) {
    final c = changePct(now, before);
    return Cell.text(c == null ? '' : pct(c, sign: true));
  }

  List<Cell> section(String title) =>
      [Cell.text(title.toUpperCase()), const Cell.text(''), const Cell.text(''), const Cell.text('')];

  List<Cell> bar(RestaurantBar b, String countWord) => [
        Cell.text(b.label),
        Cell.money(b.amount, currency: cur),
        Cell.text('${qty(b.count)} $countWord'.trim()),
        const Cell.text(''),
      ];

  return ExportDoc(
    title: 'Flash report',
    subtitle: d.fromDate == d.toDate ? d.fromDate : '${d.fromDate} to ${d.toDate}',
    meta: [
      if (branch.isNotEmpty) branch,
      'Compared with ${d.previousFromDate == d.previousToDate ? d.previousFromDate : '${d.previousFromDate} to ${d.previousToDate}'}',
      if (d.generatedAt != null) 'Generated ${d.generatedAt}',
    ],
    columns: const ['', 'This period', 'Before', 'Change'],
    rows: [
      [
        const Cell.text('Sales'),
        fig(d.current.sales, true),
        fig(d.previous.sales, true),
        changeCell(d.current.sales, d.previous.sales),
      ],
      for (final k in _kpis)
        [
          Cell.text(k.label),
          fig(k.pick(d.current), k.money),
          fig(k.pick(d.previous), k.money),
          changeCell(k.pick(d.current), k.pick(d.previous)),
        ],
      section('Right now'),
      [const Cell.text('Tables open'), Cell.number(d.openOrders.toDouble()), const Cell.text(''), const Cell.text('')],
      [const Cell.text('Bills printed, not paid'), Cell.number(d.billedOrders.toDouble()), const Cell.text(''), const Cell.text('')],
      [const Cell.text('Waiting to be paid'), Cell.money(d.billedAmount, currency: cur), const Cell.text(''), const Cell.text('')],
      if (d.tenders.isNotEmpty) section('Payments'),
      for (final b in d.tenders) bar(b, 'payments'),
      if (d.departments.isNotEmpty) section('Departments'),
      for (final b in d.departments) bar(b, 'sold'),
      if (d.topItems.isNotEmpty) section('Best sellers'),
      for (final b in d.topItems) bar(b, b.note),
      if (d.waiters.isNotEmpty) section('Waiters'),
      for (final b in d.waiters) bar(b, 'bills'),
      if (d.complimentary.isNotEmpty) section('Complimentary'),
      for (final c in d.complimentary)
        [
          Cell.text(c.givenTo.isEmpty ? 'Given to: not recorded' : c.givenTo),
          Cell.money(c.amount, currency: cur),
          Cell.text('${c.receipt} ${c.at}'),
          const Cell.text(''),
        ],
    ],
    totals: [
      DocTotal('Sales', Cell.money(d.current.sales, currency: cur)),
      DocTotal('Receipts', Cell.number(d.current.receipts.toDouble())),
    ],
  );
}
