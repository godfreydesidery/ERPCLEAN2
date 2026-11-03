import 'package:flutter/material.dart';

import '../app/app_scope.dart';
import '../app/format.dart';
import '../app/theme.dart';
import '../core/export/report_doc.dart';
import '../services/restaurant_service.dart';
import '../widgets/async_view.dart';
import '../widgets/common.dart';
import '../widgets/kit.dart';

/// The restaurant reports (Cafe Tulia): sales by waiter, bills paid and
/// pending bills — each as a summary or in detail — sales by hour and day, and
/// payments and order completion. Same figures as the web app.
class RestaurantReportsScreen extends StatefulWidget {
  const RestaurantReportsScreen({super.key, this.initial = RestaurantReport.waiterSales});

  final RestaurantReport initial;

  @override
  State<RestaurantReportsScreen> createState() =>
      _RestaurantReportsScreenState();
}

class _RestaurantReportsScreenState extends State<RestaurantReportsScreen> {
  final _viewKey = GlobalKey<AsyncViewState<RestaurantTable>>();

  late RestaurantReport _report = widget.initial;
  bool _detail = false;
  int _period = 0;
  late DateTimeRange _range = _presetRange(0);

  static const _periods = ['Today', 'Yesterday', 'This week', 'This month', 'Custom'];

  static DateTimeRange _presetRange(int i) {
    final now = DateTime.now();
    final today = DateTime(now.year, now.month, now.day);
    final yesterday = today.subtract(const Duration(days: 1));
    return switch (i) {
      1 => DateTimeRange(start: yesterday, end: yesterday),
      2 => DateTimeRange(
          start: today.subtract(Duration(days: today.weekday - 1)), end: today),
      3 => DateTimeRange(start: DateTime(now.year, now.month, 1), end: today),
      _ => DateTimeRange(start: today, end: today),
    };
  }

  String get _title =>
      _report.detailPath == null ? _report.label : '${_report.label} — ${_detail ? 'detail' : 'summary'}';

  String get _subtitle =>
      _report.hasDates ? formatRange(_range) : 'Unpaid right now';

  ExportDoc _doc(RestaurantTable t, String branch) =>
      restaurantReportDoc(_title, _subtitle, t, branch);

  void _reload() => _viewKey.currentState?.reload();

  @override
  Widget build(BuildContext context) {
    final session = AppScope.of(context).session;
    final branch = session.activeBranch?.name ?? '';

    return Scaffold(
      backgroundColor: HqColors.bg,
      appBar: AppBar(
        title: const Text('Restaurant reports', style: HqText.title),
        actions: [
          shareAction(context, () {
            final data = _viewKey.currentState?.data;
            return data == null ? null : _doc(data, branch);
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
                    crossAxisAlignment: CrossAxisAlignment.stretch,
                    children: [
                      FilterChipsRow(
                        options: [for (final r in RestaurantReport.values) r.label],
                        selected: _report.index,
                        onSelected: (i) {
                          setState(() => _report = RestaurantReport.values[i]);
                          _reload();
                        },
                      ),
                      if (_report.detailPath != null) ...[
                        const SizedBox(height: 10),
                        SegmentedButton<bool>(
                          segments: const [
                            ButtonSegment(value: false, label: Text('Summary')),
                            ButtonSegment(value: true, label: Text('Detail')),
                          ],
                          selected: {_detail},
                          showSelectedIcon: false,
                          onSelectionChanged: (s) {
                            setState(() => _detail = s.first);
                            _reload();
                          },
                        ),
                      ],
                      if (_report.hasDates) ...[
                        const SizedBox(height: 10),
                        FilterChipsRow(
                          options: _periods,
                          selected: _period,
                          onSelected: (i) {
                            if (i == _periods.length - 1) return;
                            setState(() {
                              _period = i;
                              _range = _presetRange(i);
                            });
                            _reload();
                          },
                        ),
                        const SizedBox(height: 10),
                        DateRangeBar(
                          range: _range,
                          onChanged: (r) {
                            setState(() {
                              _range = r;
                              _period = _periods.length - 1;
                            });
                            _reload();
                          },
                        ),
                      ],
                      const SizedBox(height: 12),
                    ],
                  ),
                ),
                Expanded(
                  child: AsyncView<RestaurantTable>(
                    key: _viewKey,
                    load: () => AppScope.of(context).restaurant.report(
                          _report,
                          detail: _detail,
                          from: _range.start,
                          to: _range.end,
                        ),
                    isEmpty: (t) => t.rows.isEmpty,
                    emptyIcon: Icons.restaurant_outlined,
                    emptyTitle: _report.hasDates
                        ? 'Nothing in this period'
                        : 'No pending bills',
                    emptyDetail: _report.hasDates
                        ? 'Try a different date range.'
                        : 'Every table has paid.',
                    builder: (context, t) => ListView(
                      padding: const EdgeInsets.fromLTRB(20, 0, 20, 28),
                      children: [
                        _Totals(table: t),
                        const SizedBox(height: 16),
                        SectionLabel(
                          text: _title.toUpperCase(),
                          trailing: '${t.rows.length} LINES',
                        ),
                        const SizedBox(height: 10),
                        for (final r in t.rows) ...[
                          _RowCard(table: t, row: r),
                          const SizedBox(height: 8),
                        ],
                        for (final n in t.notes) ...[
                          const SizedBox(height: 4),
                          Text(n, style: HqText.tiny),
                        ],
                        const SizedBox(height: 16),
                        FilledButton.icon(
                          onPressed: () => showShareSheet(context, _doc(t, branch)),
                          icon: const Icon(Icons.ios_share_rounded, size: 19),
                          label: const Text('Export and share'),
                        ),
                        const SizedBox(height: 16),
                        AsOfLine(
                          asOf: t.generatedAt == null
                              ? 'Live from the server'
                              : 'Generated ${t.generatedAt}',
                          coverage: branch,
                        ),
                      ],
                    ),
                  ),
                ),
              ],
            ),
    );
  }
}

/// A restaurant report as a document: the same rows the screen lists.
ExportDoc restaurantReportDoc(
        String title, String subtitle, RestaurantTable t, String branch) =>
    ExportDoc(
      title: title,
      subtitle: subtitle,
      meta: [
        if (branch.isNotEmpty) branch,
        'Amounts in ${t.currency}',
        if (t.generatedAt != null) 'Generated ${t.generatedAt}',
      ],
      columns: t.columns,
      rows: [
        for (final r in t.rows)
          [
            for (var c = 0; c < r.length; c++) _cell(t, c, r[c]),
          ],
      ],
      totals: [
        for (final (label, value) in t.totals)
          DocTotal(
              label,
              value is double
                  ? Cell.money(value, currency: t.currency)
                  : Cell.text('$value')),
      ],
      footnote: t.notes.isEmpty ? null : t.notes.join(' '),
    );

Cell _cell(RestaurantTable t, int c, Object v) {
  if (v is double) {
    return t.money.contains(c)
        ? Cell.money(v, currency: t.currency)
        : Cell.number(v, decimals: v % 1 == 0 ? 0 : 2);
  }
  return Cell.text('$v');
}

String _show(RestaurantTable t, int c, Object v) {
  if (v is double) return t.money.contains(c) ? tzs(v) : qty(v);
  return '$v';
}

class _Totals extends StatelessWidget {
  const _Totals({required this.table});

  final RestaurantTable table;

  @override
  Widget build(BuildContext context) {
    return HqCard(
      child: Column(
        children: [
          for (var i = 0; i < table.totals.length; i++) ...[
            if (i > 0) const SizedBox(height: 6),
            FigureRow(
              label: table.totals[i].$1,
              value: table.totals[i].$2 is double
                  ? tzs(table.totals[i].$2 as double)
                  : '${table.totals[i].$2}',
              emphasise: i == table.totals.length - 1,
            ),
          ],
        ],
      ),
    );
  }
}

/// One line of the report: its first column as the heading, the rest as
/// labelled figures. A blank cell is left out.
class _RowCard extends StatelessWidget {
  const _RowCard({required this.table, required this.row});

  final RestaurantTable table;
  final List<Object> row;

  @override
  Widget build(BuildContext context) {
    return HqCard(
      padding: const EdgeInsets.fromLTRB(16, 12, 16, 12),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(
            '${row.first}',
            style: const TextStyle(
                fontSize: 14.5, fontWeight: FontWeight.w700, color: HqColors.ink),
          ),
          const SizedBox(height: 6),
          for (var c = 1; c < row.length && c < table.columns.length; c++)
            if ('${row[c]}'.isNotEmpty)
              Padding(
                padding: const EdgeInsets.only(top: 3),
                child: Row(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    SizedBox(
                      width: 110,
                      child: Text(table.columns[c],
                          maxLines: 2,
                          overflow: TextOverflow.ellipsis,
                          style: HqText.tiny),
                    ),
                    const SizedBox(width: 8),
                    Expanded(
                      child: Text(
                        _show(table, c, row[c]),
                        textAlign: row[c] is double ? TextAlign.right : TextAlign.left,
                        style: const TextStyle(fontSize: 13.5, color: HqColors.ink),
                      ),
                    ),
                  ],
                ),
              ),
        ],
      ),
    );
  }
}
