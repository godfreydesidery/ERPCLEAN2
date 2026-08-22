import 'package:flutter/material.dart';

import '../app/app_scope.dart';
import '../app/format.dart';
import '../app/theme.dart';
import '../core/export/report_doc.dart';
import '../services/sales_service.dart';
import '../widgets/async_view.dart';
import '../widgets/common.dart';
import '../widgets/kit.dart';

/// Sales report over a date range — the client's "range of date".
class SalesReportScreen extends StatefulWidget {
  const SalesReportScreen({super.key});

  @override
  State<SalesReportScreen> createState() => _SalesReportScreenState();
}

class _SalesReportScreenState extends State<SalesReportScreen> {
  final _viewKey = GlobalKey<AsyncViewState<SalesReport>>();

  int _period = 2;
  late DateTimeRange _range = _presetRange(2);

  static const _periods = [
    'Today',
    'This week',
    'This month',
    'This year',
    'Custom',
  ];

  static DateTimeRange _presetRange(int i) {
    final now = DateTime.now();
    final today = DateTime(now.year, now.month, now.day);
    return switch (i) {
      0 => DateTimeRange(start: today, end: today),
      1 => DateTimeRange(
          start: today.subtract(Duration(days: today.weekday - 1)),
          end: today),
      2 => DateTimeRange(start: DateTime(now.year, now.month, 1), end: today),
      3 => DateTimeRange(start: DateTime(now.year, 1, 1), end: today),
      _ => DateTimeRange(start: today, end: today),
    };
  }

  String get _rangeLabel => formatRange(_range);

  ExportDoc _doc(SalesReport data, String branch) => ExportDoc(
        title: 'Sales report',
        subtitle: _rangeLabel,
        meta: [
          if (branch.isNotEmpty) branch,
          if (data.generatedAt != null) 'Generated ${data.generatedAt}',
        ],
        columns: const [
          'Item',
          'Code',
          'Qty sold',
          'Sales',
          'Discount',
          'VAT',
          'Margin',
        ],
        rows: [
          for (final r in data.rows)
            [
              Cell.text(r.productName.isEmpty ? r.productCode : r.productName),
              Cell.text(r.productCode),
              Cell.number(r.qtySold,
                  decimals: r.qtySold == r.qtySold.roundToDouble() ? 0 : 2),
              Cell.money(r.amount, currency: data.currency),
              Cell.money(r.discount, currency: data.currency),
              Cell.money(r.vat, currency: data.currency),
              Cell.money(r.margin, currency: data.currency),
            ],
        ],
        totals: [
          DocTotal('Sales', Cell.money(data.total, currency: data.currency)),
          DocTotal('Discount',
              Cell.money(data.discount, currency: data.currency)),
          DocTotal('VAT', Cell.money(data.vat, currency: data.currency)),
          DocTotal('Margin', Cell.money(data.margin, currency: data.currency)),
          DocTotal('Items sold', Cell.number(data.qtySold)),
        ],
      );

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

    return Scaffold(
      backgroundColor: HqColors.bg,
      appBar: AppBar(
        title: const Text('Sales report', style: HqText.title),
        actions: [
          shareAction(context, () {
            final data = _viewKey.currentState?.data;
            return data == null
                ? null
                : _doc(data, session.activeBranch?.name ?? '');
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
                  child: AsyncView<SalesReport>(
                    key: _viewKey,
                    load: () => AppScope.of(context).sales.report(
                          from: _range.start,
                          to: _range.end,
                        ),
                    isEmpty: (d) => d.rows.isEmpty,
                    emptyIcon: Icons.receipt_long_outlined,
                    emptyTitle: 'No sales in this period',
                    emptyDetail: 'Try a different date range.',
                    builder: (context, data) => ListView(
                      padding: const EdgeInsets.fromLTRB(20, 0, 20, 28),
                      children: [
                        _Totals(period: _rangeLabel, report: data),
                        const SizedBox(height: 20),
                        SectionLabel(
                          text: 'BY PRODUCT',
                          trailing: '${data.rows.length} ITEMS',
                        ),
                        const SizedBox(height: 10),
                        HqCard(
                          padding: const EdgeInsets.fromLTRB(16, 14, 16, 14),
                          child: Column(
                            children: [
                              for (var i = 0; i < data.rows.length; i++) ...[
                                if (i > 0) const Divider(height: 18),
                                _Row(row: data.rows[i], total: data.total),
                              ],
                            ],
                          ),
                        ),
                        const SizedBox(height: 16),
                        FilledButton.icon(
                          onPressed: () => showShareSheet(
                            context,
                            _doc(data, session.activeBranch?.name ?? ''),
                          ),
                          icon: const Icon(Icons.ios_share_rounded, size: 19),
                          label: const Text('Export and share'),
                        ),
                        const SizedBox(height: 16),
                        AsOfLine(
                          asOf: data.generatedAt == null
                              ? 'Live from the server'
                              : 'Generated ${data.generatedAt}',
                          coverage: session.activeBranch?.name ?? '',
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

class _Totals extends StatelessWidget {
  const _Totals({required this.period, required this.report});

  final String period;
  final SalesReport report;

  @override
  Widget build(BuildContext context) {
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
            period.toUpperCase(),
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
          Text(
            tzs(report.total),
            style: const TextStyle(
              fontSize: 34,
              fontWeight: FontWeight.w700,
              color: Colors.white,
              height: 1.05,
              letterSpacing: -1,
            ),
          ),
          const SizedBox(height: 10),
          const Divider(height: 1, color: HqOnDark.hairline),
          const SizedBox(height: 12),
          Row(
            children: [
              Expanded(
                child: _Mini(
                  label: 'Items sold',
                  value: report.qtySold.toStringAsFixed(0),
                ),
              ),
              Container(width: 1, height: 28, color: HqOnDark.hairline),
              Expanded(child: _Mini(label: 'VAT', value: tzs(report.vat))),
              Container(width: 1, height: 28, color: HqOnDark.hairline),
              Expanded(
                child: _Mini(label: 'Margin', value: tzs(report.margin)),
              ),
            ],
          ),
        ],
      ),
    );
  }
}

class _Mini extends StatelessWidget {
  const _Mini({required this.label, required this.value});

  final String label;
  final String value;

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.symmetric(horizontal: 4),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(
            label,
            maxLines: 1,
            overflow: TextOverflow.ellipsis,
            style: const TextStyle(fontSize: 10.5, color: HqOnDark.tertiary),
          ),
          const SizedBox(height: 3),
          Text(
            value,
            maxLines: 1,
            overflow: TextOverflow.ellipsis,
            style: const TextStyle(
              fontSize: 14,
              fontWeight: FontWeight.w700,
              color: Colors.white,
            ),
          ),
        ],
      ),
    );
  }
}

class _Row extends StatelessWidget {
  const _Row({required this.row, required this.total});

  final SalesReportRow row;
  final double total;

  @override
  Widget build(BuildContext context) {
    final share = total == 0 ? 0.0 : (row.amount / total).clamp(0.0, 1.0);

    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Row(
          children: [
            Expanded(
              child: Text(
                row.productName.isEmpty ? row.productCode : row.productName,
                maxLines: 1,
                overflow: TextOverflow.ellipsis,
                style: const TextStyle(
                  fontSize: 14,
                  fontWeight: FontWeight.w600,
                  color: HqColors.ink,
                ),
              ),
            ),
            const SizedBox(width: 10),
            Text(
              tzs(row.amount),
              style: const TextStyle(
                fontSize: 14,
                fontWeight: FontWeight.w700,
                color: HqColors.ink,
              ),
            ),
          ],
        ),
        const SizedBox(height: 6),
        Row(
          children: [
            Expanded(
              child: ClipRRect(
                borderRadius: BorderRadius.circular(3),
                child: LinearProgressIndicator(
                  value: share.toDouble(),
                  minHeight: 6,
                  backgroundColor: HqColors.line,
                  valueColor:
                      const AlwaysStoppedAnimation<Color>(HqColors.brand),
                ),
              ),
            ),
            const SizedBox(width: 10),
            Text(
              '${row.qtySold.toStringAsFixed(0)} · ${pct(share * 100)}',
              style: HqText.tiny,
            ),
          ],
        ),
      ],
    );
  }
}
