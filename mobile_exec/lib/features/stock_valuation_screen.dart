import 'package:flutter/material.dart';

import '../app/format.dart';
import '../app/theme.dart';
import '../data/mock.dart';
import '../widgets/charts.dart';
import '../widgets/common.dart';
import '../widgets/kit.dart';

/// Stock valuation — what the stock on hand is worth, by category.
class StockValuationScreen extends StatelessWidget {
  const StockValuationScreen({super.key});

  @override
  Widget build(BuildContext context) {
    final total = kValuation.fold<num>(0, (a, b) => a + b.value);
    final totalQty = kValuation.fold<int>(0, (a, b) => a + b.qty);
    final totalSkus = kValuation.fold<int>(0, (a, b) => a + b.skus);

    return Scaffold(
      backgroundColor: HqColors.bg,
      appBar: AppBar(
        title: const Text('Stock valuation', style: HqText.title),
        actions: [
          IconButton(
            tooltip: 'Share',
            icon: const Icon(Icons.ios_share_rounded),
            onPressed: () =>
                showShareSheet(context, 'Stock valuation — $kBranchName'),
          ),
          const SizedBox(width: 6),
        ],
      ),
      body: ListView(
        padding: const EdgeInsets.fromLTRB(20, 4, 20, 28),
        children: [
          Container(
            padding: const EdgeInsets.all(20),
            decoration: BoxDecoration(
              gradient: HqSurfaces.heroGradient,
              borderRadius: BorderRadius.circular(20),
              boxShadow: HqSurfaces.hero,
            ),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                const Text(
                  'STOCK ON HAND, AT COST',
                  style: TextStyle(
                    fontSize: 10.5,
                    fontWeight: FontWeight.w700,
                    color: HqOnDark.tertiary,
                    letterSpacing: 0.8,
                  ),
                ),
                const SizedBox(height: 9),
                Text(
                  tzs(total),
                  style: const TextStyle(
                    fontSize: 34,
                    fontWeight: FontWeight.w700,
                    color: Colors.white,
                    height: 1.05,
                    letterSpacing: -1,
                  ),
                ),
                const SizedBox(height: 6),
                Text(
                  '$totalSkus items · $totalQty units · $kBranchName',
                  maxLines: 1,
                  overflow: TextOverflow.ellipsis,
                  style: const TextStyle(
                    fontSize: 12.5,
                    color: HqOnDark.secondary,
                  ),
                ),
              ],
            ),
          ),
          const SizedBox(height: 20),
          const SectionLabel(text: 'BY CATEGORY'),
          const SizedBox(height: 10),
          HqCard(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                RankedBars(
                  rows: [
                    for (final v in kValuation)
                      RankRow(
                        v.category,
                        v.value.toDouble(),
                        tzs(v.value),
                        status: v == kValuation.first
                            ? RankStatus.best
                            : RankStatus.normal,
                      ),
                  ],
                ),
              ],
            ),
          ),
          const SizedBox(height: 20),
          const SectionLabel(text: 'DETAIL'),
          const SizedBox(height: 10),
          HqCard(
            padding: const EdgeInsets.fromLTRB(16, 12, 16, 12),
            child: Column(
              children: [
                const _HeadRow(),
                const Divider(height: 14),
                for (var i = 0; i < kValuation.length; i++) ...[
                  if (i > 0) const Divider(height: 14),
                  _ValuationRowTile(row: kValuation[i]),
                ],
                const Divider(height: 16, thickness: 1.4),
                Row(
                  children: [
                    const Expanded(
                      flex: 4,
                      child: Text(
                        'Total',
                        style: TextStyle(
                          fontSize: 14,
                          fontWeight: FontWeight.w700,
                          color: HqColors.ink,
                        ),
                      ),
                    ),
                    Expanded(
                      flex: 3,
                      child: Text(
                        tzs(total),
                        textAlign: TextAlign.right,
                        style: const TextStyle(
                          fontSize: 15,
                          fontWeight: FontWeight.w700,
                          color: HqColors.brand,
                        ),
                      ),
                    ),
                  ],
                ),
              ],
            ),
          ),
          const SizedBox(height: 16),
          FilledButton.icon(
            onPressed: () =>
                showShareSheet(context, 'Stock valuation — $kBranchName'),
            icon: const Icon(Icons.ios_share_rounded, size: 19),
            label: const Text('Export and share'),
          ),
          const SizedBox(height: 18),
          const AsOfLine(
            asOf: 'Valued at moving average cost, $kAsOf',
            coverage: 'Demo data — not connected to the server',
          ),
        ],
      ),
    );
  }
}

class _HeadRow extends StatelessWidget {
  const _HeadRow();

  @override
  Widget build(BuildContext context) {
    const style = TextStyle(
      fontSize: 10.5,
      fontWeight: FontWeight.w700,
      color: HqColors.ink3,
      letterSpacing: 0.6,
    );
    return const Row(
      children: [
        Expanded(flex: 4, child: Text('CATEGORY', style: style)),
        Expanded(
          flex: 2,
          child: Text('UNITS', textAlign: TextAlign.right, style: style),
        ),
        Expanded(
          flex: 3,
          child: Text('VALUE', textAlign: TextAlign.right, style: style),
        ),
      ],
    );
  }
}

class _ValuationRowTile extends StatelessWidget {
  const _ValuationRowTile({required this.row});

  final ValuationRow row;

  @override
  Widget build(BuildContext context) {
    return Row(
      children: [
        Expanded(
          flex: 4,
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text(
                row.category,
                maxLines: 1,
                overflow: TextOverflow.ellipsis,
                style: const TextStyle(
                  fontSize: 14,
                  fontWeight: FontWeight.w600,
                  color: HqColors.ink,
                ),
              ),
              Text('${row.skus} items', style: HqText.tiny),
            ],
          ),
        ),
        Expanded(
          flex: 2,
          child: Text(
            '${row.qty}',
            textAlign: TextAlign.right,
            style: const TextStyle(fontSize: 13.5, color: HqColors.ink2),
          ),
        ),
        Expanded(
          flex: 3,
          child: Text(
            tzs(row.value),
            textAlign: TextAlign.right,
            style: const TextStyle(
              fontSize: 14,
              fontWeight: FontWeight.w700,
              color: HqColors.ink,
            ),
          ),
        ),
      ],
    );
  }
}
