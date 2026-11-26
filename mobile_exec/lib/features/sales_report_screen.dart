import 'package:flutter/material.dart';
import 'package:shared_preferences/shared_preferences.dart';

import '../app/app_scope.dart';
import '../app/format.dart';
import '../app/theme.dart';
import '../core/export/report_doc.dart';
import '../services/sales_service.dart';
import '../widgets/async_view.dart';
import '../widgets/common.dart';
import '../widgets/kit.dart';
import 'sales_report_view.dart';

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

  /// Search, food/drinks and order of the item list. The report itself is
  /// never changed - this only chooses and orders what is shown.
  SalesFilter _filter = const SalesFilter();
  final _search = TextEditingController();

  static const _periods = [
    'Today',
    'This week',
    'This month',
    'Last month',
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
      // Day 0 of this month is the last day of the previous one.
      3 => DateTimeRange(
          start: DateTime(now.year, now.month - 1, 1),
          end: DateTime(now.year, now.month, 0)),
      4 => DateTimeRange(start: DateTime(now.year, 1, 1), end: today),
      _ => DateTimeRange(start: today, end: today),
    };
  }

  String get _rangeLabel => formatRange(_range);

  bool _drinksLoaded = false;

  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    // Needs the session (an inherited widget), so not in initState; once only.
    if (!_drinksLoaded) {
      _drinksLoaded = true;
      _loadDrinkCategories();
    }
  }

  @override
  void dispose() {
    _search.dispose();
    super.dispose();
  }

  /// Drink categories are kept per company on this device: categories are a
  /// company's own words, and the choice is one owner's view, not a setting.
  String get _drinksKey =>
      'hq.salesReport.drinkCategories.${AppScope.of(context).session.companyId ?? ''}';

  Future<void> _loadDrinkCategories() async {
    try {
      final p = await SharedPreferences.getInstance();
      if (!mounted) return;
      final saved = p.getStringList(_drinksKey) ?? const <String>[];
      setState(() => _filter = _filter.copyWith(drinkCategories: saved.toSet()));
    } catch (_) {
      // No storage (a private browser window): Food / Drinks just starts empty.
    }
  }

  Future<void> _chooseDrinkCategories(List<String> categories) async {
    final picked = await showModalBottomSheet<Set<String>>(
      context: context,
      isScrollControlled: true,
      builder: (_) => _DrinkCategoriesSheet(
        categories: categories,
        selected: _filter.drinkCategories,
      ),
    );
    if (picked == null || !mounted) return;
    setState(() => _filter = _filter.copyWith(drinkCategories: picked));
    try {
      final p = await SharedPreferences.getInstance();
      await p.setStringList(_drinksKey, picked.toList()..sort());
    } catch (_) {
      // Kept for this visit only.
    }
  }

  String _filterLabel() => [
        if (_filter.query.trim().isNotEmpty) '"${_filter.query.trim()}"',
        if (_filter.kind != SalesKind.all) _filter.kind.label,
      ].join(' · ');

  ExportDoc _doc(SalesReport data, String branch) {
    final shown = applySalesFilter(data.rows, _filter);
    final m = SalesMatches.of(shown);
    return ExportDoc(
        title: 'Sales report',
        subtitle: _rangeLabel,
        meta: [
          if (branch.isNotEmpty) branch,
          if (_filter.narrows)
            'Showing ${m.items} of ${data.rows.length} items: ${_filterLabel()}',
          'Ordered by ${_filter.sort.label.toLowerCase()}',
          if (data.generatedAt != null) 'Generated ${data.generatedAt}',
          if (data.marginIsPartial) _marginNote(data),
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
          for (final r in shown)
            [
              Cell.text(r.productName.isEmpty ? r.productCode : r.productName),
              Cell.text(r.productCode),
              Cell.number(r.qtySold,
                  decimals: r.qtySold == r.qtySold.roundToDouble() ? 0 : 2),
              Cell.money(r.amount, currency: data.currency),
              Cell.money(r.discount, currency: data.currency),
              Cell.money(r.vat, currency: data.currency),
              r.margin == null
                  ? const Cell.unknown()
                  : Cell.money(r.margin!, currency: data.currency),
            ],
        ],
        // Narrowed: the totals are those of the items shown, and say so - a
        // whole-report total under a filtered list would be misread.
        totals: _filter.narrows
            ? [
                DocTotal('Sales (shown)',
                    Cell.money(m.amount, currency: data.currency)),
                DocTotal('Items sold (shown)', Cell.number(m.qtySold)),
                DocTotal('Sales (whole report)',
                    Cell.money(data.total, currency: data.currency)),
              ]
            : [
                DocTotal(
                    'Sales', Cell.money(data.total, currency: data.currency)),
                DocTotal('Discount',
                    Cell.money(data.discount, currency: data.currency)),
                DocTotal('VAT', Cell.money(data.vat, currency: data.currency)),
                DocTotal(
                    data.marginIsPartial ? 'Margin (partial)' : 'Margin',
                    data.marginUnknown
                        ? const Cell.unknown()
                        : Cell.money(data.margin, currency: data.currency)),
                DocTotal('Items sold', Cell.number(data.qtySold)),
              ],
      );
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

    return Scaffold(
      backgroundColor: HqColors.bg,
      appBar: AppBar(
        title: const Text('Sales report', style: HqText.title),
        actions: [
          shareAction(context, () {
            final data = _viewKey.currentState?.data;
            return data == null
                ? null
                : _doc(data, session.scopeLabel);
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
                    builder: (context, data) {
                      final shown = applySalesFilter(data.rows, _filter);
                      final matches = SalesMatches.of(shown);
                      final withCategories = reportHasCategories(data.rows);
                      return ListView(
                      padding: const EdgeInsets.fromLTRB(20, 0, 20, 28),
                      children: [
                        _Totals(period: _rangeLabel, report: data),
                        const SizedBox(height: 16),
                        TextField(
                          controller: _search,
                          textInputAction: TextInputAction.search,
                          decoration: InputDecoration(
                            hintText: 'Search items, e.g. matcha',
                            prefixIcon: const Icon(Icons.search_rounded),
                            suffixIcon: _filter.query.isEmpty
                                ? null
                                : IconButton(
                                    tooltip: 'Clear search',
                                    icon: const Icon(Icons.close_rounded),
                                    onPressed: () {
                                      _search.clear();
                                      setState(() => _filter =
                                          _filter.copyWith(query: ''));
                                    },
                                  ),
                          ),
                          onChanged: (v) => setState(
                              () => _filter = _filter.copyWith(query: v)),
                        ),
                        const SizedBox(height: 10),
                        Row(
                          children: [
                            if (withCategories) ...[
                              Expanded(
                                child: FilterChipsRow(
                                  options: [
                                    for (final k in SalesKind.values) k.label
                                  ],
                                  selected: _filter.kind.index,
                                  onSelected: (i) {
                                    final kind = SalesKind.values[i];
                                    if (kind != SalesKind.all &&
                                        _filter.drinkCategories.isEmpty) {
                                      // First use: say which categories are drinks.
                                      _chooseDrinkCategories(
                                              categoriesOf(data.rows))
                                          .then((_) {
                                        if (mounted) {
                                          setState(() => _filter =
                                              _filter.copyWith(kind: kind));
                                        }
                                      });
                                      return;
                                    }
                                    setState(() =>
                                        _filter = _filter.copyWith(kind: kind));
                                  },
                                ),
                              ),
                              IconButton(
                                tooltip: 'Choose drink categories',
                                icon: const Icon(Icons.tune_rounded),
                                onPressed: () => _chooseDrinkCategories(
                                    categoriesOf(data.rows)),
                              ),
                            ] else
                              const Spacer(),
                            PopupMenuButton<SalesSort>(
                              tooltip: 'Order',
                              initialValue: _filter.sort,
                              onSelected: (s) => setState(
                                  () => _filter = _filter.copyWith(sort: s)),
                              itemBuilder: (_) => [
                                for (final s in SalesSort.values)
                                  PopupMenuItem(value: s, child: Text(s.label)),
                              ],
                              child: Padding(
                                padding: const EdgeInsets.symmetric(
                                    horizontal: 6, vertical: 8),
                                child: Row(
                                  mainAxisSize: MainAxisSize.min,
                                  children: [
                                    const Icon(Icons.sort_rounded, size: 18),
                                    const SizedBox(width: 4),
                                    Text(_filter.sort.label,
                                        style: HqText.tiny),
                                  ],
                                ),
                              ),
                            ),
                          ],
                        ),
                        if (_filter.narrows) ...[
                          const SizedBox(height: 10),
                          HqCard(
                            child: Column(
                              children: [
                                FigureRow(
                                  label: 'Matching items',
                                  value: '${matches.items}',
                                ),
                                const Divider(height: 14),
                                FigureRow(
                                  label: 'Quantity sold',
                                  value: qty(matches.qtySold),
                                ),
                                const Divider(height: 14),
                                FigureRow(
                                  label: 'Sales',
                                  value: tzs(matches.amount),
                                  emphasise: true,
                                  valueColor: HqColors.brand,
                                ),
                              ],
                            ),
                          ),
                        ],
                        const SizedBox(height: 20),
                        SectionLabel(
                          text: 'BY PRODUCT',
                          trailing: _filter.narrows
                              ? '${shown.length} OF ${data.rows.length} ITEMS'
                              : '${data.rows.length} ITEMS',
                        ),
                        const SizedBox(height: 10),
                        HqCard(
                          padding: const EdgeInsets.fromLTRB(16, 14, 16, 14),
                          child: Column(
                            children: [
                              if (shown.isEmpty)
                                Text('No item matches.', style: HqText.body),
                              for (var i = 0; i < shown.length; i++) ...[
                                if (i > 0) const Divider(height: 18),
                                _Row(row: shown[i], total: data.total),
                              ],
                            ],
                          ),
                        ),
                        const SizedBox(height: 16),
                        FilledButton.icon(
                          onPressed: () => showShareSheet(
                            context,
                            _doc(data, session.scopeLabel),
                          ),
                          icon: const Icon(Icons.ios_share_rounded, size: 19),
                          label: const Text('Export and share'),
                        ),
                        const SizedBox(height: 16),
                        AsOfLine(
                          asOf: data.generatedAt == null
                              ? 'Live from the server'
                              : 'Generated ${data.generatedAt}',
                          coverage: session.scopeLabel,
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
          Amount(
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
                child: _Mini(
                  label: report.marginIsPartial ? 'Margin (partial)' : 'Margin',
                  value: report.marginUnknown ? '—' : tzs(report.margin),
                ),
              ),
            ],
          ),
          if (report.marginIsPartial) ...[
            const SizedBox(height: 10),
            Text(
              _marginNote(report),
              style: const TextStyle(
                fontSize: 11.5,
                color: HqOnDark.secondary,
                height: 1.35,
              ),
            ),
          ],
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
          Amount(
            value,
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

/// Tick the categories that are drinks; everything else counts as food.
class _DrinkCategoriesSheet extends StatefulWidget {
  const _DrinkCategoriesSheet({
    required this.categories,
    required this.selected,
  });

  final List<String> categories;
  final Set<String> selected;

  @override
  State<_DrinkCategoriesSheet> createState() => _DrinkCategoriesSheetState();
}

class _DrinkCategoriesSheetState extends State<_DrinkCategoriesSheet> {
  late final Set<String> _keys = {
    for (final s in widget.selected) s.trim().toLowerCase()
  };

  @override
  Widget build(BuildContext context) {
    return SafeArea(
      child: ConstrainedBox(
        constraints: BoxConstraints(
            maxHeight: MediaQuery.of(context).size.height * 0.8),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            const Padding(
              padding: EdgeInsets.fromLTRB(20, 18, 20, 4),
              child: Text('Which categories are drinks?', style: HqText.title),
            ),
            const Padding(
              padding: EdgeInsets.fromLTRB(20, 0, 20, 8),
              child: Text(
                'Everything else counts as food. Saved on this phone.',
                style: HqText.tiny,
              ),
            ),
            Flexible(
              child: ListView(
                shrinkWrap: true,
                children: [
                  for (final c in widget.categories)
                    CheckboxListTile(
                      title: Text(c),
                      value: _keys.contains(c.trim().toLowerCase()),
                      onChanged: (on) => setState(() => on == true
                          ? _keys.add(c.trim().toLowerCase())
                          : _keys.remove(c.trim().toLowerCase())),
                    ),
                ],
              ),
            ),
            Padding(
              padding: const EdgeInsets.fromLTRB(20, 8, 20, 16),
              child: FilledButton(
                onPressed: () => Navigator.of(context).pop({
                  for (final c in widget.categories)
                    if (_keys.contains(c.trim().toLowerCase())) c
                }),
                child: const Text('Save'),
              ),
            ),
          ],
        ),
      ),
    );
  }
}

/// Why the margin is partial, in the owner's words. A total that silently
/// leaves rows out while looking complete is worse than one that says so.
String _marginNote(SalesReport r) {
  final n = r.marginRowsUnknown;
  final items = n == 1 ? '1 item has' : '$n items have';
  return r.marginUnknown
      ? 'No margin: none of these items has a recorded cost.'
      : 'Margin leaves out sales of items with no recorded cost — '
          '$items no cost yet.';
}
