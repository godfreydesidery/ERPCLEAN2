import 'package:flutter/material.dart';

import '../app/app_scope.dart';
import '../app/format.dart';
import '../app/theme.dart';
import '../core/export/report_doc.dart';
import '../services/catalog_service.dart';
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
  final _viewKey = GlobalKey<AsyncViewState<ValuationView>>();

  /// The valuation plus the products behind it, so each quantity can be shown
  /// in the unit it is counted in. `/stock/reports/stock-value` returns a bare
  /// number per line and no unit.
  Future<ValuationView> _load() async {
    final scope = AppScope.of(context);
    final results = await Future.wait([
      scope.stock.valuation(),
      scope.catalog.productsByCode(),
    ]);
    return ValuationView(
      result: results[0] as ValuationResult,
      products: results[1] as Map<String, ProductItem>,
    );
  }

  ExportDoc _doc(ValuationView view) {
    final data = view.result;
    final rows = [...data.rows]..sort((a, b) => b.value.compareTo(a.value));
    return ExportDoc(
      title: 'Stock valuation',
      subtitle: data.branchName ?? 'All branches',
      meta: [
        'Valued at moving average cost.',
        if (data.generatedAt != null) 'Generated ${data.generatedAt}',
      ],
      columns: const ['Item', 'Code', 'Quantity', 'Unit', 'Value at cost'],
      rows: [
        for (final r in rows)
          [
            Cell.text(r.label),
            Cell.text(r.code),
            Cell.number(r.quantity,
                decimals: r.quantity == r.quantity.roundToDouble() ? 0 : 2),
            Cell.text(view.unitOf(r)),
            if (r.unvalued)
              const Cell.text('not valued')
            else
              Cell.money(r.value),
          ],
      ],
      totals: [
        DocTotal('Lines', Cell.number(rows.length.toDouble())),
        DocTotal('Total at cost', Cell.money(data.total)),
      ],
      footnote: data.unvaluedCount == 0
          ? null
          : '${data.unvaluedCount} of ${rows.length} lines have never been '
              'valued and count as zero in the total.',
    );
  }

  @override
  Widget build(BuildContext context) {
    final session = AppScope.of(context).session;

    return Scaffold(
      backgroundColor: HqColors.bg,
      appBar: AppBar(
        title: const Text('Stock valuation', style: HqText.title),
        actions: [
          shareAction(context, () {
            final view = _viewKey.currentState?.data;
            return view == null ? null : _doc(view);
          }),
          const SizedBox(width: 6),
        ],
      ),
      body: !session.can('INVENTORY.VALUATION.VIEW')
          ? const NoPermission(code: 'INVENTORY.VALUATION.VIEW')
          : Column(
              children: [
                Expanded(
                  child: AsyncView<ValuationView>(
                    key: _viewKey,
                    load: _load,
                    isEmpty: (d) => d.result.rows.isEmpty,
                    emptyIcon: Icons.account_balance_wallet_outlined,
                    emptyTitle: 'Nothing to value',
                    emptyDetail: 'No stock is held in this branch.',
                    builder: (context, view) {
                      final data = view.result;
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
                                  _ValRow(
                                    row: top[i],
                                    unit: view.unitOf(top[i]),
                                  ),
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
                                showShareSheet(context, _doc(view)),
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
  const _ValRow({required this.row, required this.unit});

  final ValuationRowData row;
  final String unit;

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
            unit.isEmpty
                ? row.quantity.toStringAsFixed(0)
                : '${row.quantity.toStringAsFixed(0)} $unit',
            textAlign: TextAlign.right,
            maxLines: 1,
            overflow: TextOverflow.ellipsis,
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

/// A valuation plus the products behind it, so a quantity can be labelled with
/// the unit it is counted in.
class ValuationView {
  const ValuationView({required this.result, required this.products});

  final ValuationResult result;
  final Map<String, ProductItem> products;

  /// Empty when the product is not in the loaded page — better a bare number
  /// than a wrong unit.
  String unitOf(ValuationRowData row) => products[row.code]?.unit ?? '';
}
