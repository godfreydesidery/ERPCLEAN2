import 'package:flutter/material.dart';

import '../app/app_scope.dart';
import '../app/format.dart';
import '../app/theme.dart';
import '../core/api/api_exception.dart';
import '../core/export/report_doc.dart';
import '../services/catalog_service.dart';
import '../services/stock_service.dart';
import '../widgets/async_view.dart';
import '../widgets/common.dart';
import '../widgets/kit.dart';

/// Stock report — what is on hand right now, searchable.
///
/// Every quantity is labelled with the unit it is counted in. `/stock/on-hand`
/// does not carry the unit, so the product list is loaded alongside and joined
/// on the product code: a bare "240" on a phone is unreadable when the same
/// item is also handled in cartons.
class StockReportScreen extends StatefulWidget {
  const StockReportScreen({super.key});

  @override
  State<StockReportScreen> createState() => _StockReportScreenState();
}

class _StockReportScreenState extends State<StockReportScreen> {
  final _viewKey = GlobalKey<AsyncViewState<StockView>>();
  String _query = '';

  Future<StockView> _load() async {
    final scope = AppScope.of(context);
    final results = await Future.wait([
      scope.stock.onHand(),
      scope.catalog.productsByCode(),
    ]);
    return StockView(
      rows: results[0] as List<StockRow>,
      products: results[1] as Map<String, ProductItem>,
    );
  }

  /// The pack breakdown of one line, loaded on demand.
  ///
  /// Per-product pack sizes are one request each — cheap on a tap, ruinous
  /// across a two-hundred-line report, so the list stays in base units and the
  /// carton reading is a tap away.
  Future<void> _showBreakdown(StockRow row, ProductItem? product) async {
    if (product == null) return;
    final messenger = ScaffoldMessenger.of(context);
    List<TxUnit> units;
    try {
      units = await AppScope.of(context).catalog.transactionUnits(product);
    } on ApiException catch (e) {
      messenger.showSnackBar(SnackBar(
        behavior: SnackBarBehavior.floating,
        backgroundColor: HqColors.bad,
        content: Text(e.message),
      ));
      return;
    }
    if (!mounted) return;

    final packs = describeInPacks(row.quantity, units, product.unit);
    await showModalBottomSheet<void>(
      context: context,
      backgroundColor: Colors.transparent,
      builder: (context) => Container(
        decoration: const BoxDecoration(
          color: HqColors.panel,
          borderRadius: BorderRadius.vertical(top: Radius.circular(22)),
        ),
        padding: const EdgeInsets.fromLTRB(20, 18, 20, 8),
        child: SafeArea(
          top: false,
          child: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text(
                row.productName.isEmpty ? row.productCode : row.productName,
                style: HqText.title,
                maxLines: 2,
                overflow: TextOverflow.ellipsis,
              ),
              const SizedBox(height: 2),
              Text(row.productCode, style: HqText.tiny),
              const SizedBox(height: 16),
              FigureRow(
                label: 'On hand',
                value: '${qty(row.quantity)} ${product.unit}',
                emphasise: true,
                valueColor: row.negative ? HqColors.bad : HqColors.ink,
              ),
              if (packs != null) ...[
                const Divider(height: 20),
                FigureRow(label: 'That is', value: packs),
              ],
              const Divider(height: 20),
              for (final u in units.where((u) => !u.isBase))
                Padding(
                  padding: const EdgeInsets.symmetric(vertical: 4),
                  child: Text(
                    '1 ${u.code} = ${u.factorLabel} ${product.unit}',
                    style: HqText.tiny,
                  ),
                ),
              if (units.length == 1)
                Text(
                  'No pack sizes are set for this item. Add them from '
                  'Operations > Pack sizes.',
                  style: HqText.tiny,
                ),
              const SizedBox(height: 12),
            ],
          ),
        ),
      ),
    );
  }

  ExportDoc _doc(StockView view, String branch) => ExportDoc(
        title: 'Stock report',
        subtitle: branch.isEmpty ? null : branch,
        meta: ['On hand as at the moment this was run.'],
        columns: const ['Item', 'Code', 'Location', 'On hand', 'Unit', 'Reorder level'],
        rows: [
          for (final r in view.rows)
            [
              Cell.text(r.productName.isEmpty ? r.productCode : r.productName),
              Cell.text(r.productCode),
              Cell.text(r.locationName ?? ''),
              Cell.number(r.quantity, decimals: _decimals(r.quantity)),
              Cell.text(view.unitOf(r)),
              if (r.reorderLevel == null)
                const Cell.text('')
              else
                Cell.number(r.reorderLevel!,
                    decimals: _decimals(r.reorderLevel!)),
            ],
        ],
        totals: [
          DocTotal('Items', Cell.number(view.rows.length.toDouble())),
          DocTotal('Low stock',
              Cell.number(view.rows.where((r) => r.low).length.toDouble())),
          DocTotal('Below zero',
              Cell.number(view.rows.where((r) => r.negative).length.toDouble())),
        ],
        footnote: 'Quantities are in each item’s counting unit.',
      );

  @override
  Widget build(BuildContext context) {
    final session = AppScope.of(context).session;
    final branch = session.activeBranch?.name ?? '';

    return Scaffold(
      backgroundColor: HqColors.bg,
      appBar: AppBar(
        title: const Text('Stock report', style: HqText.title),
        actions: [
          shareAction(context, () {
            final view = _viewKey.currentState?.data;
            return view == null ? null : _doc(view, branch);
          }),
          const SizedBox(width: 6),
        ],
      ),
      body: !session.can('STOCK.VIEW')
          ? const NoPermission(code: 'STOCK.VIEW')
          : Column(
              children: [
                Padding(
                  padding: const EdgeInsets.fromLTRB(20, 4, 20, 12),
                  child: HqSearchField(
                    hint: 'Search item or code',
                    onChanged: (v) => setState(() => _query = v),
                  ),
                ),
                Expanded(
                  child: AsyncView<StockView>(
                    key: _viewKey,
                    load: _load,
                    isEmpty: (d) => d.rows.isEmpty,
                    emptyIcon: Icons.inventory_outlined,
                    emptyTitle: 'No stock records',
                    emptyDetail: 'Nothing is held in this branch yet.',
                    builder: (context, view) {
                      final all = view.rows;
                      final q = _query.trim().toLowerCase();
                      final rows = q.isEmpty
                          ? all
                          : all
                              .where((r) =>
                                  r.productName.toLowerCase().contains(q) ||
                                  r.productCode.toLowerCase().contains(q))
                              .toList();
                      final low = all.where((r) => r.low).length;
                      final neg = all.where((r) => r.negative).length;

                      return ListView(
                        padding: const EdgeInsets.fromLTRB(20, 0, 20, 28),
                        children: [
                          Row(
                            children: [
                              Expanded(
                                child: StatTile(
                                  label: 'Items',
                                  value: '${all.length}',
                                ),
                              ),
                              const SizedBox(width: 12),
                              Expanded(
                                child: StatTile(
                                  label: 'Low stock',
                                  value: '$low',
                                ),
                              ),
                              const SizedBox(width: 12),
                              Expanded(
                                child: StatTile(
                                  label: 'Below zero',
                                  value: '$neg',
                                ),
                              ),
                            ],
                          ),
                          const SizedBox(height: 20),
                          SectionLabel(
                            text: 'ON HAND',
                            trailing: q.isEmpty ? null : '${rows.length} FOUND',
                          ),
                          const SizedBox(height: 10),
                          if (rows.isEmpty)
                            Padding(
                              padding: const EdgeInsets.symmetric(vertical: 40),
                              child: Column(
                                children: [
                                  const Icon(Icons.search_off_rounded,
                                      size: 40, color: HqColors.ink3),
                                  const SizedBox(height: 12),
                                  Text('Nothing matches "$_query"',
                                      style: HqText.body),
                                ],
                              ),
                            )
                          else
                            HqCard(
                              padding: const EdgeInsets.symmetric(
                                  horizontal: 16, vertical: 6),
                              child: Column(
                                children: [
                                  for (var i = 0; i < rows.length; i++) ...[
                                    if (i > 0) const Divider(height: 1),
                                    _ItemRow(
                                      row: rows[i],
                                      unit: view.unitOf(rows[i]),
                                      onTap: () => _showBreakdown(
                                        rows[i],
                                        view.products[rows[i].productCode],
                                      ),
                                    ),
                                  ],
                                ],
                              ),
                            ),
                          const SizedBox(height: 16),
                          FilledButton.icon(
                            onPressed: () =>
                                showShareSheet(context, _doc(view, branch)),
                            icon: const Icon(Icons.ios_share_rounded, size: 19),
                            label: const Text('Export and share'),
                          ),
                          const SizedBox(height: 16),
                          AsOfLine(
                            asOf: 'Live from the server',
                            coverage: branch,
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

/// On-hand rows plus the products they belong to, so each quantity can be
/// labelled with its unit.
class StockView {
  const StockView({required this.rows, required this.products});

  final List<StockRow> rows;
  final Map<String, ProductItem> products;

  /// The unit [row] is counted in, or empty when the product is not in the
  /// loaded page — better a bare number than a wrong unit.
  String unitOf(StockRow row) => products[row.productCode]?.unit ?? '';
}

class _ItemRow extends StatelessWidget {
  const _ItemRow({required this.row, required this.unit, required this.onTap});

  final StockRow row;
  final String unit;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    final color = row.negative
        ? HqColors.bad
        : (row.low ? HqColors.warn : HqColors.ink);

    return InkWell(
      onTap: onTap,
      child: Padding(
        padding: const EdgeInsets.symmetric(vertical: 12),
        child: Row(
          children: [
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    row.productName.isEmpty ? row.productCode : row.productName,
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
                    [
                      row.productCode,
                      if (row.locationName != null) row.locationName!,
                    ].join(' · '),
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
                  unit.isEmpty
                      ? qty(row.quantity)
                      : '${qty(row.quantity)} $unit',
                  style: TextStyle(
                    fontSize: 15,
                    fontWeight: FontWeight.w700,
                    color: color,
                  ),
                ),
                if (row.reorderLevel != null)
                  Text(
                    'reorder ${qty(row.reorderLevel!)}',
                    style: HqText.tiny,
                  ),
              ],
            ),
          ],
        ),
      ),
    );
  }
}

int _decimals(double v) => v == v.roundToDouble() ? 0 : 2;
