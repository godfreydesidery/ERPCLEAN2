import 'package:flutter/material.dart';

import '../app/app_scope.dart';
import '../app/format.dart';
import '../app/theme.dart';
import '../services/sales_service.dart';
import '../services/stock_service.dart';
import '../widgets/async_view.dart';
import '../widgets/common.dart';
import '../widgets/kit.dart';

/// What the dashboard needs, loaded in one pass.
class _Overview {
  const _Overview({
    required this.today,
    required this.month,
    required this.stock,
    required this.stockValue,
  });

  final SalesReport? today;
  final SalesReport? month;
  final List<StockRow> stock;
  final double? stockValue;

  List<StockRow> get needsAttention {
    final rows = stock.where((r) => r.low || r.negative).toList()
      ..sort((a, b) => a.quantity.compareTo(b.quantity));
    return rows.take(5).toList();
  }
}

class DashboardScreen extends StatelessWidget {
  const DashboardScreen({super.key, required this.onNavigate});

  final void Function(String route) onNavigate;

  Future<_Overview> _load(BuildContext context) async {
    final scope = AppScope.of(context);
    final session = scope.session;
    final now = DateTime.now();

    SalesReport? today;
    SalesReport? month;
    if (session.can('SALES.INVOICE.VIEW')) {
      today = await scope.sales.today();
      month = await scope.sales.report(
        from: DateTime(now.year, now.month, 1),
        to: DateTime(now.year, now.month, now.day),
      );
    }

    var stock = <StockRow>[];
    if (session.can('STOCK.VIEW')) {
      stock = await scope.stock.onHand();
    }

    double? stockValue;
    if (session.can('INVENTORY.VALUATION.VIEW')) {
      try {
        stockValue = (await scope.stock.valuation()).total;
      } catch (_) {
        stockValue = null; // a valuation failure must not blank the dashboard
      }
    }

    return _Overview(
      today: today,
      month: month,
      stock: stock,
      stockValue: stockValue,
    );
  }

  @override
  Widget build(BuildContext context) {
    final session = AppScope.of(context).session;

    return Scaffold(
      backgroundColor: HqColors.bg,
      body: SafeArea(
        bottom: false,
        child: AsyncView<_Overview>(
          load: () => _load(context),
          builder: (context, data) => ListView(
            padding: const EdgeInsets.fromLTRB(20, 8, 20, 28),
            children: [
              _Greeting(
                name: session.displayName ?? session.username ?? 'there',
                branch: session.activeBranch?.name ?? 'No branch',
              ),
              const SizedBox(height: 18),
              if (data.today == null)
                const _Locked(
                  text: 'You do not have permission to see sales figures.',
                )
              else
                _SalesHero(today: data.today!, month: data.month),
              const SizedBox(height: 22),
              const SectionLabel(text: 'STOCK'),
              const SizedBox(height: 10),
              _StockCard(
                value: data.stockValue,
                itemCount: data.stock.length,
                onTap: () => onNavigate('valuation'),
              ),
              if (data.needsAttention.isNotEmpty) ...[
                const SizedBox(height: 22),
                SectionLabel(
                  text: 'NEEDS ATTENTION',
                  trailing: '${data.needsAttention.length} ITEMS',
                ),
                const SizedBox(height: 10),
                _LowStockCard(rows: data.needsAttention),
              ],
              const SizedBox(height: 22),
              const SectionLabel(text: 'QUICK ACTIONS'),
              const SizedBox(height: 10),
              _QuickActions(onNavigate: onNavigate),
              const SizedBox(height: 20),
              AsOfLine(
                asOf: 'Loaded ${_clock()}',
                coverage: session.activeBranch?.name ?? '',
              ),
            ],
          ),
        ),
      ),
    );
  }

  static String _clock() {
    final n = DateTime.now();
    return '${n.hour.toString().padLeft(2, '0')}:'
        '${n.minute.toString().padLeft(2, '0')}';
  }
}

class _Greeting extends StatelessWidget {
  const _Greeting({required this.name, required this.branch});

  final String name;
  final String branch;

  String get _initials {
    final parts = name.trim().split(RegExp(r'\s+'));
    if (parts.isEmpty || parts.first.isEmpty) return '?';
    if (parts.length == 1) return parts.first.substring(0, 1).toUpperCase();
    return (parts.first.substring(0, 1) + parts.last.substring(0, 1))
        .toUpperCase();
  }

  @override
  Widget build(BuildContext context) {
    return Row(
      children: [
        Container(
          width: 44,
          height: 44,
          decoration: const BoxDecoration(
            gradient: HqSurfaces.brandGradient,
            shape: BoxShape.circle,
          ),
          alignment: Alignment.center,
          child: Text(
            _initials,
            style: const TextStyle(
              color: Colors.white,
              fontWeight: FontWeight.w700,
              fontSize: 15,
            ),
          ),
        ),
        const SizedBox(width: 12),
        Expanded(
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text(
                'Habari, $name',
                maxLines: 1,
                overflow: TextOverflow.ellipsis,
                style: const TextStyle(
                  fontSize: 16.5,
                  fontWeight: FontWeight.w700,
                  color: HqColors.ink,
                ),
              ),
              const SizedBox(height: 2),
              Text(
                branch,
                maxLines: 1,
                overflow: TextOverflow.ellipsis,
                style: HqText.tiny,
              ),
            ],
          ),
        ),
      ],
    );
  }
}

class _SalesHero extends StatelessWidget {
  const _SalesHero({required this.today, this.month});

  final SalesReport today;
  final SalesReport? month;

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
          const Text(
            "TODAY'S SALES",
            style: TextStyle(
              fontSize: 10.5,
              fontWeight: FontWeight.w700,
              color: HqOnDark.tertiary,
              letterSpacing: 0.8,
            ),
          ),
          const SizedBox(height: 10),
          Text(
            tzs(today.total),
            style: const TextStyle(
              fontSize: 38,
              fontWeight: FontWeight.w700,
              color: Colors.white,
              height: 1.05,
              letterSpacing: -1,
            ),
          ),
          const SizedBox(height: 16),
          const Divider(height: 1, color: HqOnDark.hairline),
          const SizedBox(height: 12),
          Row(
            children: [
              Expanded(
                child: _HeroFigure(
                  label: 'This month',
                  value: month == null ? '—' : tzs(month!.total),
                ),
              ),
              Container(width: 1, height: 30, color: HqOnDark.hairline),
              Expanded(
                child: _HeroFigure(
                  label: 'Items sold today',
                  value: today.qtySold.toStringAsFixed(0),
                ),
              ),
              Container(width: 1, height: 30, color: HqOnDark.hairline),
              Expanded(
                child: _HeroFigure(
                  label: 'VAT today',
                  value: tzs(today.vat),
                ),
              ),
            ],
          ),
        ],
      ),
    );
  }
}

class _HeroFigure extends StatelessWidget {
  const _HeroFigure({required this.label, required this.value});

  final String label;
  final String value;

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.symmetric(horizontal: 6),
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

class _StockCard extends StatelessWidget {
  const _StockCard({
    required this.value,
    required this.itemCount,
    required this.onTap,
  });

  final double? value;
  final int itemCount;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    return HqCard(
      onTap: onTap,
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text('Stock value', style: HqText.label),
                const SizedBox(height: 4),
                Text(
                  value == null ? 'Not available' : tzs(value!),
                  style: TextStyle(
                    fontSize: value == null ? 17 : 26,
                    fontWeight: FontWeight.w700,
                    color: value == null ? HqColors.ink3 : HqColors.ink,
                    letterSpacing: -0.5,
                  ),
                ),
                const SizedBox(height: 4),
                Text('$itemCount items on hand', style: HqText.tiny),
              ],
            ),
          ),
          const Icon(Icons.chevron_right, color: HqColors.ink3),
        ],
      ),
    );
  }
}

class _LowStockCard extends StatelessWidget {
  const _LowStockCard({required this.rows});

  final List<StockRow> rows;

  @override
  Widget build(BuildContext context) {
    return HqCard(
      padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 6),
      child: Column(
        children: [
          for (var i = 0; i < rows.length; i++) ...[
            if (i > 0) const Divider(height: 1),
            Padding(
              padding: const EdgeInsets.symmetric(vertical: 12),
              child: Row(
                children: [
                  Container(
                    width: 8,
                    height: 8,
                    decoration: BoxDecoration(
                      color: rows[i].negative ? HqColors.bad : HqColors.warn,
                      shape: BoxShape.circle,
                    ),
                  ),
                  const SizedBox(width: 12),
                  Expanded(
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Text(
                          rows[i].productName,
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
                          rows[i].negative
                              ? 'Below zero — needs a count'
                              : 'Below reorder level',
                          maxLines: 1,
                          overflow: TextOverflow.ellipsis,
                          style: HqText.tiny,
                        ),
                      ],
                    ),
                  ),
                  const SizedBox(width: 10),
                  Text(
                    rows[i].quantity.toStringAsFixed(0),
                    style: TextStyle(
                      fontSize: 13.5,
                      fontWeight: FontWeight.w700,
                      color:
                          rows[i].negative ? HqColors.bad : HqColors.ink2,
                    ),
                  ),
                ],
              ),
            ),
          ],
        ],
      ),
    );
  }
}

class _QuickActions extends StatelessWidget {
  const _QuickActions({required this.onNavigate});

  final void Function(String route) onNavigate;

  @override
  Widget build(BuildContext context) {
    return Column(
      children: [
        ActionTile(
          icon: Icons.local_shipping_outlined,
          title: 'Receive goods',
          subtitle: 'Take in stock without a purchase order',
          tint: const Color(0xFF2A78D6),
          onTap: () => onNavigate('receive'),
        ),
        const SizedBox(height: 10),
        ActionTile(
          icon: Icons.tune_rounded,
          title: 'Stock adjustment',
          subtitle: 'Correct a quantity',
          tint: const Color(0xFFEB6834),
          onTap: () => onNavigate('adjust'),
        ),
      ],
    );
  }
}

class _Locked extends StatelessWidget {
  const _Locked({required this.text});

  final String text;

  @override
  Widget build(BuildContext context) {
    return HqCard(
      child: Row(
        children: [
          const Icon(Icons.lock_outline_rounded,
              size: 20, color: HqColors.ink3),
          const SizedBox(width: 12),
          Expanded(child: Text(text, style: HqText.body)),
        ],
      ),
    );
  }
}
