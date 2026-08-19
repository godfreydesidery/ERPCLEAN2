import 'package:flutter/material.dart';

import '../app/app_scope.dart';
import '../app/theme.dart';
import '../services/stock_service.dart';
import '../widgets/async_view.dart';
import '../widgets/common.dart';
import '../widgets/kit.dart';

/// Stock report — what is on hand right now, searchable.
class StockReportScreen extends StatefulWidget {
  const StockReportScreen({super.key});

  @override
  State<StockReportScreen> createState() => _StockReportScreenState();
}

class _StockReportScreenState extends State<StockReportScreen> {
  final _viewKey = GlobalKey<AsyncViewState<List<StockRow>>>();
  String _query = '';

  @override
  Widget build(BuildContext context) {
    final session = AppScope.of(context).session;

    return Scaffold(
      backgroundColor: HqColors.bg,
      appBar: AppBar(
        title: const Text('Stock report', style: HqText.title),
        actions: [
          IconButton(
            tooltip: 'Share',
            icon: const Icon(Icons.ios_share_rounded),
            onPressed: () => showShareSheet(context, 'Stock report'),
          ),
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
                  child: AsyncView<List<StockRow>>(
                    key: _viewKey,
                    load: () => AppScope.of(context).stock.onHand(),
                    isEmpty: (d) => d.isEmpty,
                    emptyIcon: Icons.inventory_outlined,
                    emptyTitle: 'No stock records',
                    emptyDetail: 'Nothing is held in this branch yet.',
                    builder: (context, all) {
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
                                    _ItemRow(row: rows[i]),
                                  ],
                                ],
                              ),
                            ),
                          const SizedBox(height: 16),
                          FilledButton.icon(
                            onPressed: () =>
                                showShareSheet(context, 'Stock report'),
                            icon: const Icon(Icons.ios_share_rounded, size: 19),
                            label: const Text('Export and share'),
                          ),
                          const SizedBox(height: 16),
                          AsOfLine(
                            asOf: 'Live from the server',
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

class _ItemRow extends StatelessWidget {
  const _ItemRow({required this.row});

  final StockRow row;

  @override
  Widget build(BuildContext context) {
    final color = row.negative
        ? HqColors.bad
        : (row.low ? HqColors.warn : HqColors.ink);

    return Padding(
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
                row.quantity.toStringAsFixed(0),
                style: TextStyle(
                  fontSize: 15,
                  fontWeight: FontWeight.w700,
                  color: color,
                ),
              ),
              if (row.reorderLevel != null)
                Text(
                  'reorder ${row.reorderLevel!.toStringAsFixed(0)}',
                  style: HqText.tiny,
                ),
            ],
          ),
        ],
      ),
    );
  }
}
