import 'package:flutter/material.dart';

import '../app/format.dart';
import '../app/theme.dart';
import '../data/mock.dart';
import '../widgets/charts.dart';
import '../widgets/common.dart';
import '../widgets/kit.dart';

/// Sales report — period filter, a trend, then the breakdown by product or by
/// branch. Exportable and shareable. Mockup: the filters change the labels,
/// not the data.
class SalesReportScreen extends StatefulWidget {
  const SalesReportScreen({super.key});

  @override
  State<SalesReportScreen> createState() => _SalesReportScreenState();
}

class _SalesReportScreenState extends State<SalesReportScreen> {
  int _period = 2;
  int _breakdown = 0;

  static const _periods = ['Today', 'This week', 'This month', 'This year'];

  @override
  Widget build(BuildContext context) {
    final rows = _breakdown == 0 ? kSalesByProduct : kSalesByBranch;

    return Scaffold(
      backgroundColor: HqColors.bg,
      appBar: AppBar(
        title: const Text('Sales report', style: HqText.title),
        actions: [
          IconButton(
            tooltip: 'Share',
            icon: const Icon(Icons.ios_share_rounded),
            onPressed: () => showShareSheet(
              context,
              'Sales report — ${_periods[_period]}',
            ),
          ),
          const SizedBox(width: 6),
        ],
      ),
      body: ListView(
        padding: const EdgeInsets.fromLTRB(20, 4, 20, 28),
        children: [
          FilterChipsRow(
            options: _periods,
            selected: _period,
            onSelected: (i) => setState(() => _period = i),
          ),
          const SizedBox(height: 16),
          _Totals(period: _periods[_period]),
          const SizedBox(height: 14),
          HqCard(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text('Last 12 months', style: HqText.label),
                const SizedBox(height: 14),
                ColumnTrend(
                  values: kSales12Months,
                  labels: kMonthLabels,
                  height: 150,
                ),
                const SizedBox(height: 8),
                Text(
                  'August is a part month — shown hatched.',
                  style: HqText.tiny,
                ),
              ],
            ),
          ),
          const SizedBox(height: 22),
          Row(
            children: [
              Expanded(
                child: SectionLabel(
                  text: _breakdown == 0 ? 'BY PRODUCT' : 'BY BRANCH',
                ),
              ),
              _Toggle(
                index: _breakdown,
                onChanged: (i) => setState(() => _breakdown = i),
              ),
            ],
          ),
          const SizedBox(height: 12),
          HqCard(
            padding: const EdgeInsets.fromLTRB(16, 14, 16, 6),
            child: Column(
              children: [
                for (var i = 0; i < rows.length; i++) ...[
                  if (i > 0) const Divider(height: 18),
                  _SalesRowTile(row: rows[i]),
                ],
                const SizedBox(height: 8),
              ],
            ),
          ),
          const SizedBox(height: 16),
          FilledButton.icon(
            onPressed: () => showShareSheet(
              context,
              'Sales report — ${_periods[_period]}',
            ),
            icon: const Icon(Icons.ios_share_rounded, size: 19),
            label: const Text('Export and share'),
          ),
          const SizedBox(height: 18),
          const AsOfLine(
            asOf: 'Figures as at $kAsOf',
            coverage: 'Demo data — not connected to the server',
          ),
        ],
      ),
    );
  }
}

class _Totals extends StatelessWidget {
  const _Totals({required this.period});

  final String period;

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
            style: const TextStyle(
              fontSize: 10.5,
              fontWeight: FontWeight.w700,
              color: HqOnDark.tertiary,
              letterSpacing: 0.8,
            ),
          ),
          const SizedBox(height: 9),
          Text(
            tzs(kSalesReportTotal),
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
              const Expanded(
                child: _MiniFigure(
                  label: 'Invoices',
                  value: '$kSalesReportInvoices',
                ),
              ),
              Container(width: 1, height: 28, color: HqOnDark.hairline),
              Expanded(
                child: _MiniFigure(
                  label: 'Average sale',
                  value: tzs(kSalesReportTotal / kSalesReportInvoices),
                ),
              ),
            ],
          ),
        ],
      ),
    );
  }
}

class _MiniFigure extends StatelessWidget {
  const _MiniFigure({required this.label, required this.value});

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
              fontSize: 15,
              fontWeight: FontWeight.w700,
              color: Colors.white,
            ),
          ),
        ],
      ),
    );
  }
}

class _Toggle extends StatelessWidget {
  const _Toggle({required this.index, required this.onChanged});

  final int index;
  final ValueChanged<int> onChanged;

  @override
  Widget build(BuildContext context) {
    const labels = ['Product', 'Branch'];
    return Container(
      padding: const EdgeInsets.all(3),
      decoration: BoxDecoration(
        color: HqColors.panel,
        borderRadius: BorderRadius.circular(9),
        border: Border.all(color: HqColors.line),
      ),
      child: Row(
        mainAxisSize: MainAxisSize.min,
        children: [
          for (var i = 0; i < labels.length; i++)
            GestureDetector(
              onTap: () => onChanged(i),
              child: Container(
                padding:
                    const EdgeInsets.symmetric(horizontal: 12, vertical: 6),
                decoration: BoxDecoration(
                  color: i == index ? HqColors.brand : Colors.transparent,
                  borderRadius: BorderRadius.circular(7),
                ),
                child: Text(
                  labels[i],
                  style: TextStyle(
                    fontSize: 12,
                    fontWeight: FontWeight.w600,
                    color: i == index ? Colors.white : HqColors.ink2,
                  ),
                ),
              ),
            ),
        ],
      ),
    );
  }
}

class _SalesRowTile extends StatelessWidget {
  const _SalesRowTile({required this.row});

  final SalesRow row;

  @override
  Widget build(BuildContext context) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Row(
          children: [
            Expanded(
              child: Text(
                row.label,
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
                  value: row.share,
                  minHeight: 6,
                  backgroundColor: HqColors.line,
                  valueColor:
                      const AlwaysStoppedAnimation<Color>(HqColors.brand),
                ),
              ),
            ),
            const SizedBox(width: 10),
            Text(
              '${row.qty} · ${pct(row.share * 100)}',
              style: HqText.tiny,
            ),
          ],
        ),
      ],
    );
  }
}
