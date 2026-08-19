import 'package:flutter/material.dart';

import '../app/format.dart';
import '../app/theme.dart';
import '../data/mock.dart';
import '../widgets/common.dart';
import '../widgets/kit.dart';

/// Stock report — what is on hand, what moved, and what needs attention.
class StockReportScreen extends StatefulWidget {
  const StockReportScreen({super.key});

  @override
  State<StockReportScreen> createState() => _StockReportScreenState();
}

class _StockReportScreenState extends State<StockReportScreen> {
  String _query = '';

  @override
  Widget build(BuildContext context) {
    final items = kProducts
        .where((p) =>
            _query.isEmpty ||
            p.name.toLowerCase().contains(_query.toLowerCase()) ||
            p.code.toLowerCase().contains(_query.toLowerCase()))
        .toList();

    return Scaffold(
      backgroundColor: HqColors.bg,
      appBar: AppBar(
        title: const Text('Stock report', style: HqText.title),
        actions: [
          IconButton(
            tooltip: 'Share',
            icon: const Icon(Icons.ios_share_rounded),
            onPressed: () =>
                showShareSheet(context, 'Stock report — $kBranchName'),
          ),
          const SizedBox(width: 6),
        ],
      ),
      body: ListView(
        padding: const EdgeInsets.fromLTRB(20, 4, 20, 28),
        children: [
          Row(
            children: [
              Expanded(
                child: StatTile(
                  label: 'Items',
                  value: '$kSkuCount',
                ),
              ),
              const SizedBox(width: 12),
              Expanded(
                child: StatTile(
                  label: 'Low stock',
                  value: '$kLowStockCount',
                ),
              ),
              const SizedBox(width: 12),
              Expanded(
                child: StatTile(
                  label: 'Out of stock',
                  value: '$kOutOfStockCount',
                ),
              ),
            ],
          ),
          const SizedBox(height: 20),
          const SectionLabel(text: 'MOVEMENT THIS MONTH'),
          const SizedBox(height: 10),
          HqCard(
            child: Column(
              children: [
                FigureRow(
                  label: 'Goods received',
                  value: '$kReceiptsThisMonth receipts',
                ),
                const Divider(height: 12),
                FigureRow(
                  label: 'Issued to sales',
                  value: '$kIssuesThisMonth issues',
                ),
                const Divider(height: 12),
                FigureRow(
                  label: 'Adjustments',
                  value: '$kAdjustmentsThisMonth',
                ),
                const Divider(height: 12),
                FigureRow(
                  label: 'Written off',
                  value: tzs(kShrinkageValue),
                  valueColor: HqColors.bad,
                ),
              ],
            ),
          ),
          const SizedBox(height: 22),
          const SectionLabel(text: 'ITEMS ON HAND'),
          const SizedBox(height: 10),
          HqSearchField(
            hint: 'Search item or code',
            onChanged: (v) => setState(() => _query = v),
          ),
          const SizedBox(height: 12),
          if (items.isEmpty)
            Padding(
              padding: const EdgeInsets.symmetric(vertical: 40),
              child: Column(
                children: [
                  const Icon(Icons.search_off_rounded,
                      size: 40, color: HqColors.ink3),
                  const SizedBox(height: 12),
                  Text('Nothing matches "$_query"', style: HqText.body),
                ],
              ),
            )
          else
            HqCard(
              padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 6),
              child: Column(
                children: [
                  for (var i = 0; i < items.length; i++) ...[
                    if (i > 0) const Divider(height: 1),
                    _ItemRow(product: items[i]),
                  ],
                ],
              ),
            ),
          const SizedBox(height: 16),
          FilledButton.icon(
            onPressed: () =>
                showShareSheet(context, 'Stock report — $kBranchName'),
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

class _ItemRow extends StatelessWidget {
  const _ItemRow({required this.product});

  final Product product;

  @override
  Widget build(BuildContext context) {
    final low = product.onHand < 70;

    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 12),
      child: Row(
        children: [
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  product.name,
                  maxLines: 1,
                  overflow: TextOverflow.ellipsis,
                  style: const TextStyle(
                    fontSize: 14,
                    fontWeight: FontWeight.w600,
                    color: HqColors.ink,
                  ),
                ),
                const SizedBox(height: 2),
                Text(
                  '${product.code} · ${product.category}',
                  maxLines: 1,
                  overflow: TextOverflow.ellipsis,
                  style: HqText.tiny,
                ),
              ],
            ),
          ),
          const SizedBox(width: 10),
          Column(
            crossAxisAlignment: CrossAxisAlignment.end,
            children: [
              Text(
                '${product.onHand} ${product.unit}',
                style: TextStyle(
                  fontSize: 14,
                  fontWeight: FontWeight.w700,
                  color: low ? HqColors.warn : HqColors.ink,
                ),
              ),
              const SizedBox(height: 2),
              Text(tzs(product.onHand * product.cost), style: HqText.tiny),
            ],
          ),
        ],
      ),
    );
  }
}
