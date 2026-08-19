import 'package:flutter/material.dart';

import '../app/app_scope.dart';
import '../app/format.dart';
import '../app/theme.dart';
import '../services/stock_service.dart';
import '../widgets/async_view.dart';
import '../widgets/charts.dart';
import '../widgets/common.dart';
import '../widgets/kit.dart';

/// Stock valuation — what the stock on hand is worth.
class StockValuationScreen extends StatefulWidget {
  const StockValuationScreen({super.key});

  @override
  State<StockValuationScreen> createState() => _StockValuationScreenState();
}

class _StockValuationScreenState extends State<StockValuationScreen> {
  final _viewKey = GlobalKey<AsyncViewState<ValuationResult>>();

  @override
  Widget build(BuildContext context) {
    final session = AppScope.of(context).session;

    return Scaffold(
      backgroundColor: HqColors.bg,
      appBar: AppBar(
        title: const Text('Stock valuation', style: HqText.title),
        actions: [
          IconButton(
            tooltip: 'Share',
            icon: const Icon(Icons.ios_share_rounded),
            onPressed: () => showShareSheet(context, 'Stock valuation'),
          ),
          const SizedBox(width: 6),
        ],
      ),
      body: !session.can('INVENTORY.VALUATION.VIEW')
          ? const NoPermission(code: 'INVENTORY.VALUATION.VIEW')
          : Column(
              children: [
                Expanded(
                  child: AsyncView<ValuationResult>(
                    key: _viewKey,
                    load: () => AppScope.of(context).stock.valuation(),
                    isEmpty: (d) => d.rows.isEmpty,
                    emptyIcon: Icons.account_balance_wallet_outlined,
                    emptyTitle: 'Nothing to value',
                    emptyDetail: 'No stock is held in this branch.',
                    builder: (context, data) {
                      final top = [...data.rows]
                        ..sort((a, b) => b.value.compareTo(a.value));
                      final shown = top.take(12).toList();

                      return ListView(
                        padding: const EdgeInsets.fromLTRB(20, 0, 20, 28),
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
                                  tzs(data.total),
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
                                  '${data.rows.length} lines · '
                                  '${data.branchName ?? 'all branches'}',
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
                          if (data.unvaluedCount > 0) ...[
                            const SizedBox(height: 12),
                            Container(
                              padding: const EdgeInsets.all(13),
                              decoration: BoxDecoration(
                                color: HqColors.warnSoft,
                                borderRadius:
                                    BorderRadius.circular(HqRadii.sm),
                              ),
                              child: Row(
                                children: [
                                  const Icon(Icons.warning_amber_rounded,
                                      size: 19, color: HqColors.warn),
                                  const SizedBox(width: 10),
                                  Expanded(
                                    child: Text(
                                      '${data.unvaluedCount} of '
                                      '${data.rows.length} lines have never '
                                      'been valued and count as zero here.',
                                      style: const TextStyle(
                                        fontSize: 13,
                                        height: 1.35,
                                        color: Color(0xFF8A5A0B),
                                      ),
                                    ),
                                  ),
                                ],
                              ),
                            ),
                          ],
                          const SizedBox(height: 20),
                          SectionLabel(
                            text: 'BIGGEST HOLDINGS',
                            trailing: shown.length < data.rows.length
                                ? 'TOP ${shown.length}'
                                : null,
                          ),
                          const SizedBox(height: 10),
                          HqCard(
                            child: RankedBars(
                              rows: [
                                for (var i = 0; i < shown.length; i++)
                                  RankRow(
                                    shown[i].label,
                                    shown[i].value,
                                    tzs(shown[i].value),
                                    status: i == 0
                                        ? RankStatus.best
                                        : RankStatus.normal,
                                  ),
                              ],
                            ),
                          ),
                          const SizedBox(height: 20),
                          const SectionLabel(text: 'DETAIL'),
                          const SizedBox(height: 10),
                          HqCard(
                            padding:
                                const EdgeInsets.fromLTRB(16, 12, 16, 12),
                            child: Column(
                              children: [
                                for (var i = 0; i < top.length; i++) ...[
                                  if (i > 0) const Divider(height: 14),
                                  _ValRow(row: top[i]),
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
                                        tzs(data.total),
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
                                showShareSheet(context, 'Stock valuation'),
                            icon: const Icon(Icons.ios_share_rounded, size: 19),
                            label: const Text('Export and share'),
                          ),
                          const SizedBox(height: 16),
                          AsOfLine(
                            asOf: 'Valued at moving average cost',
                            coverage: session.activeBranch?.name ?? '',
                          ),
                        ],
                      );
                    },
                  ),
                ),
              ],
            ),
    );
  }
}

class _ValRow extends StatelessWidget {
  const _ValRow({required this.row});

  final ValuationRowData row;

  @override
  Widget build(BuildContext context) {
    return Row(
      children: [
        Expanded(
          flex: 4,
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
        Expanded(
          flex: 2,
          child: Text(
            row.quantity.toStringAsFixed(0),
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
