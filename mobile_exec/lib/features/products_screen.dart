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

/// Products — what an item is, what it cost, what it sells for, and how much is
/// on the shelf.
///
/// Six facts about a product used to live on six screens, and the phone could
/// reach none of them: the client asked to see description, unit, cost, buying
/// and selling price and stock on hand in one place. The Product List report
/// carries five of those on one line; the catalogue supplies the description
/// and the counting unit, and the pack sizes are one tap away.
class ProductsScreen extends StatefulWidget {
  const ProductsScreen({super.key});

  @override
  State<ProductsScreen> createState() => _ProductsScreenState();
}

class _ProductsScreenState extends State<ProductsScreen> {
  final _viewKey = GlobalKey<AsyncViewState<ProductsView>>();
  String _query = '';

  Future<ProductsView> _load() async {
    final scope = AppScope.of(context);
    final results = await Future.wait([
      scope.stock.productList(),
      scope.catalog.productsByCode(),
    ]);
    return ProductsView(
      report: results[0] as ProductListReport,
      products: results[1] as Map<String, ProductItem>,
    );
  }

  ExportDoc _doc(ProductsView view) {
    final report = view.report;
    return ExportDoc(
      title: 'Product list',
      subtitle: report.branchName ?? 'All branches',
      meta: [
        if (report.priceListName != null)
          'Selling prices from ${report.priceListName}'
              '${report.priceIncludesVat ? ' (VAT included)' : ' (before VAT)'}',
        'Buying price is the average cost of the stock on hand.',
        if (report.generatedAt != null) 'Generated ${report.generatedAt}',
      ],
      columns: const [
        'Item',
        'Code',
        'Unit',
        'On hand',
        'Buying price',
        'Selling price',
        'Value at cost',
      ],
      rows: [
        for (final r in report.rows)
          [
            Cell.text(r.name),
            Cell.text(r.code),
            Cell.text(view.unitOf(r)),
            Cell.number(r.quantityOnHand,
                decimals:
                    r.quantityOnHand == r.quantityOnHand.roundToDouble() ? 0 : 2),
            if (r.buyingPrice == null)
              const Cell.text('never costed')
            else
              Cell.money(r.buyingPrice!, currency: report.currency),
            if (r.sellingPrice == null)
              const Cell.text('never priced')
            else
              Cell.money(r.sellingPrice!, currency: report.currency),
            if (r.costValue == null)
              const Cell.text('')
            else
              Cell.money(r.costValue!, currency: report.currency),
          ],
      ],
      totals: [
        DocTotal('Products', Cell.number(report.rows.length.toDouble())),
      ],
      footnote: 'A blank price is a product that has never been costed or '
          'priced — not one that is free.',
    );
  }

  Future<void> _openDetail(ProductsView view, ProductLine line) async {
    final product = view.products[line.code];
    final scope = AppScope.of(context);

    // Pack sizes and per-unit prices are one request each, so they are fetched
    // for the one product being opened rather than for the whole catalogue.
    List<TxUnit> units = const [];
    List<UnitPrice> prices = const [];
    if (product != null) {
      try {
        units = await scope.catalog.transactionUnits(product);
      } on ApiException {
        // The detail is still worth showing without its packs.
      }
      try {
        prices = await scope.catalog.prices(product.uid);
      } catch (_) {
        // Pricing may be out of this user's reach; the rest still stands.
      }
    }
    if (!mounted) return;

    await showModalBottomSheet<void>(
      context: context,
      isScrollControlled: true,
      backgroundColor: Colors.transparent,
      builder: (context) => _ProductDetail(
        line: line,
        product: product,
        units: units,
        prices: prices,
        currency: view.report.currency,
        priceListName: view.report.priceListName,
        priceIncludesVat: view.report.priceIncludesVat,
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    final session = AppScope.of(context).session;

    return Scaffold(
      backgroundColor: HqColors.bg,
      appBar: AppBar(
        title: const Text('Products', style: HqText.title),
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
                Padding(
                  padding: const EdgeInsets.fromLTRB(20, 4, 20, 12),
                  child: HqSearchField(
                    hint: 'Search item or code',
                    onChanged: (v) => setState(() => _query = v),
                  ),
                ),
                Expanded(
                  child: AsyncView<ProductsView>(
                    key: _viewKey,
                    load: _load,
                    isEmpty: (d) => d.report.rows.isEmpty,
                    emptyIcon: Icons.inventory_2_outlined,
                    emptyTitle: 'No products yet',
                    emptyDetail: 'Register one from Operations first.',
                    builder: (context, view) {
                      final all = view.report.rows;
                      final q = _query.trim().toLowerCase();
                      final rows = q.isEmpty
                          ? all
                          : all
                              .where((r) =>
                                  r.name.toLowerCase().contains(q) ||
                                  r.code.toLowerCase().contains(q))
                              .toList();
                      final unpriced =
                          all.where((r) => r.sellingPrice == null).length;

                      return ListView(
                        padding: const EdgeInsets.fromLTRB(20, 0, 20, 28),
                        children: [
                          Row(
                            children: [
                              Expanded(
                                child: StatTile(
                                  label: 'Products',
                                  value: '${all.length}',
                                ),
                              ),
                              const SizedBox(width: 12),
                              Expanded(
                                child: StatTile(
                                  label: 'Not priced',
                                  value: '$unpriced',
                                ),
                              ),
                            ],
                          ),
                          const SizedBox(height: 20),
                          SectionLabel(
                            text: 'CATALOGUE',
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
                                    _ProductRow(
                                      line: rows[i],
                                      unit: view.unitOf(rows[i]),
                                      currency: view.report.currency,
                                      onTap: () =>
                                          _openDetail(view, rows[i]),
                                    ),
                                  ],
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
                            asOf: view.report.priceListName == null
                                ? 'Live from the server'
                                : 'Prices from ${view.report.priceListName}',
                            coverage: view.report.branchName ??
                                session.activeBranch?.name ??
                                '',
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

/// The report plus the catalogue behind it — the report has no unit or
/// description on its rows.
class ProductsView {
  const ProductsView({required this.report, required this.products});

  final ProductListReport report;
  final Map<String, ProductItem> products;

  String unitOf(ProductLine line) => products[line.code]?.unit ?? '';
}

class _ProductRow extends StatelessWidget {
  const _ProductRow({
    required this.line,
    required this.unit,
    required this.currency,
    required this.onTap,
  });

  final ProductLine line;
  final String unit;
  final String currency;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    final negative = line.quantityOnHand < 0;

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
                    line.name,
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
                      line.code,
                      if (unit.isNotEmpty) unit,
                      if (line.discontinued) 'discontinued',
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
                Amount(
                  line.sellingPrice == null
                      ? 'not priced'
                      : tzs(line.sellingPrice!),
                  alignment: Alignment.centerRight,
                  style: TextStyle(
                    fontSize: 14,
                    fontWeight: FontWeight.w700,
                    color: line.sellingPrice == null
                        ? HqColors.ink3
                        : HqColors.ink,
                  ),
                ),
                const SizedBox(height: 2),
                Text(
                  '${qty(line.quantityOnHand)}'
                  '${unit.isEmpty ? '' : ' $unit'}',
                  style: HqText.tiny.copyWith(
                    color: negative ? HqColors.bad : HqColors.ink3,
                    fontWeight: negative ? FontWeight.w700 : FontWeight.w400,
                  ),
                ),
              ],
            ),
          ],
        ),
      ),
    );
  }
}

/// Everything known about one product, on one sheet.
class _ProductDetail extends StatelessWidget {
  const _ProductDetail({
    required this.line,
    required this.product,
    required this.units,
    required this.prices,
    required this.currency,
    required this.priceListName,
    required this.priceIncludesVat,
  });

  final ProductLine line;
  final ProductItem? product;
  final List<TxUnit> units;
  final List<UnitPrice> prices;
  final String currency;
  final String? priceListName;
  final bool priceIncludesVat;

  /// The price set for [unit], when one is set explicitly. A pack with no price
  /// of its own sells at base price x factor, which the row states instead of
  /// inventing a number here.
  double? _priceFor(TxUnit unit) {
    for (final p in prices) {
      if (p.unitUid == unit.uid) return p.amount;
      if (unit.isBase && p.unitUid == null) return p.amount;
    }
    return null;
  }

  @override
  Widget build(BuildContext context) {
    final base = product?.unit ?? '';
    final packs = base.isEmpty
        ? null
        : describeInPacks(line.quantityOnHand, units, base);
    final description = product?.description?.trim();

    return DraggableScrollableSheet(
      initialChildSize: 0.7,
      minChildSize: 0.4,
      maxChildSize: 0.94,
      expand: false,
      builder: (context, controller) => Container(
        decoration: const BoxDecoration(
          color: HqColors.panel,
          borderRadius: BorderRadius.vertical(top: Radius.circular(22)),
        ),
        child: ListView(
          controller: controller,
          padding: const EdgeInsets.fromLTRB(20, 12, 20, 28),
          children: [
            Center(
              child: Container(
                width: 38,
                height: 4,
                decoration: BoxDecoration(
                  color: HqColors.line2,
                  borderRadius: BorderRadius.circular(2),
                ),
              ),
            ),
            const SizedBox(height: 16),
            Text(line.name, style: HqText.title),
            const SizedBox(height: 3),
            Text(
              [
                line.code,
                if (line.supplierName != null) line.supplierName!,
              ].join(' · '),
              style: HqText.tiny,
            ),
            if (description != null && description.isNotEmpty) ...[
              const SizedBox(height: 12),
              Text(description, style: HqText.body),
            ],
            const SizedBox(height: 20),
            const SectionLabel(text: 'ON THE SHELF'),
            const SizedBox(height: 8),
            HqCard(
              child: Column(
                children: [
                  FigureRow(
                    label: 'Stock on hand',
                    value: base.isEmpty
                        ? qty(line.quantityOnHand)
                        : '${qty(line.quantityOnHand)} $base',
                    emphasise: true,
                    valueColor:
                        line.quantityOnHand < 0 ? HqColors.bad : HqColors.ink,
                  ),
                  if (packs != null) ...[
                    const Divider(height: 18),
                    FigureRow(label: 'That is', value: packs),
                  ],
                  const Divider(height: 18),
                  FigureRow(
                    label: 'Value at cost',
                    value: line.costValue == null
                        ? 'never valued'
                        : tzs(line.costValue!),
                  ),
                ],
              ),
            ),
            const SizedBox(height: 20),
            const SectionLabel(text: 'WHAT IT COSTS AND EARNS'),
            const SizedBox(height: 8),
            HqCard(
              child: Column(
                children: [
                  FigureRow(
                    label: 'Buying price'
                        '${base.isEmpty ? '' : ' per $base'}',
                    value: line.buyingPrice == null
                        ? 'never costed'
                        : tzs(line.buyingPrice!),
                  ),
                  const Divider(height: 18),
                  FigureRow(
                    label: 'Selling price'
                        '${base.isEmpty ? '' : ' per $base'}',
                    value: line.sellingPrice == null
                        ? 'never priced'
                        : tzs(line.sellingPrice!),
                    emphasise: true,
                  ),
                  if (line.margin != null) ...[
                    const Divider(height: 18),
                    FigureRow(
                      label: 'Margin per ${base.isEmpty ? 'unit' : base}',
                      value: tzs(line.margin!),
                      valueColor:
                          line.margin! < 0 ? HqColors.bad : HqColors.good,
                    ),
                  ],
                ],
              ),
            ),
            if (priceListName != null) ...[
              const SizedBox(height: 8),
              Text(
                'Selling price from $priceListName, '
                '${priceIncludesVat ? 'VAT included' : 'before VAT'}.',
                style: HqText.tiny,
              ),
            ],
            const SizedBox(height: 20),
            const SectionLabel(text: 'UNITS IT IS HANDLED IN'),
            const SizedBox(height: 8),
            HqCard(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  if (units.isEmpty)
                    Text(
                      base.isEmpty
                          ? 'Units could not be loaded for this item.'
                          : 'Counted in $base. No pack sizes are set.',
                      style: HqText.body,
                    )
                  else
                    for (var i = 0; i < units.length; i++) ...[
                      if (i > 0) const Divider(height: 18),
                      _UnitRow(
                        unit: units[i],
                        baseCode: base,
                        price: _priceFor(units[i]),
                      ),
                    ],
                  if (units.length == 1) ...[
                    const SizedBox(height: 10),
                    Text(
                      'Add cartons or outers from Operations, Pack sizes.',
                      style: HqText.tiny,
                    ),
                  ],
                ],
              ),
            ),
            const SizedBox(height: 20),
          ],
        ),
      ),
    );
  }
}

class _UnitRow extends StatelessWidget {
  const _UnitRow({
    required this.unit,
    required this.baseCode,
    required this.price,
  });

  final TxUnit unit;
  final String baseCode;

  /// Null when this unit has no price of its own.
  final double? price;

  @override
  Widget build(BuildContext context) {
    return Row(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Expanded(
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text(
                unit.code,
                style: const TextStyle(
                  fontSize: 14.5,
                  fontWeight: FontWeight.w600,
                  color: HqColors.ink,
                ),
              ),
              const SizedBox(height: 2),
              Text(
                unit.isBase
                    ? 'The unit stock is counted in'
                    : '1 ${unit.code} = ${unit.factorLabel} $baseCode',
                style: HqText.tiny,
              ),
            ],
          ),
        ),
        const SizedBox(width: 10),
        Column(
          crossAxisAlignment: CrossAxisAlignment.end,
          children: [
            Amount(
              price == null ? 'no price of its own' : tzs(price!),
              alignment: Alignment.centerRight,
              style: TextStyle(
                fontSize: 14,
                fontWeight: FontWeight.w700,
                color: price == null ? HqColors.ink3 : HqColors.ink,
              ),
            ),
            if (price == null && !unit.isBase)
              Text('sells at ${unit.factorLabel} x the $baseCode price',
                  style: HqText.tiny),
          ],
        ),
      ],
    );
  }
}
