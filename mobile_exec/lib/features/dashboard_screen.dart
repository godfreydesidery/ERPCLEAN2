import 'package:flutter/material.dart';

import '../app/format.dart';
import '../app/theme.dart';
import '../data/mock.dart';
import '../widgets/charts.dart';
import '../widgets/common.dart';

/// The dashboard: today's sales and the stock position, in that order.
/// Mockup only — every figure comes from `data/mock.dart`.
class DashboardScreen extends StatelessWidget {
  const DashboardScreen({super.key, required this.onNavigate});

  final void Function(String route) onNavigate;

  @override
  Widget build(BuildContext context) {
    final change = (kSalesToday - kSalesYesterday) / kSalesYesterday * 100;

    return Scaffold(
      backgroundColor: HqColors.bg,
      body: SafeArea(
        bottom: false,
        child: ListView(
          padding: const EdgeInsets.fromLTRB(20, 8, 20, 28),
          children: [
            const _Greeting(),
            const SizedBox(height: 18),
            _SalesHero(change: change),
            const SizedBox(height: 14),
            const _TodayTiles(),
            const SizedBox(height: 22),
            SectionLabel(
              text: 'STOCK',
              trailing: '$kSkuCount ITEMS',
            ),
            const SizedBox(height: 10),
            _StockCard(onNavigate: onNavigate),
            const SizedBox(height: 22),
            const SectionLabel(text: 'NEEDS ATTENTION'),
            const SizedBox(height: 10),
            const _LowStockCard(),
            const SizedBox(height: 22),
            const SectionLabel(text: 'RECENT ACTIVITY'),
            const SizedBox(height: 10),
            const _ActivityCard(),
            const SizedBox(height: 18),
            const AsOfLine(
              asOf: 'Figures as at $kAsOf',
              coverage: 'Demo data — not connected to the server',
            ),
          ],
        ),
      ),
    );
  }
}

class _Greeting extends StatelessWidget {
  const _Greeting();

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
          child: const Text(
            kUserInitials,
            style: TextStyle(
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
              const Text(
                'Habari, $kUserName',
                maxLines: 1,
                overflow: TextOverflow.ellipsis,
                style: TextStyle(
                  fontSize: 16.5,
                  fontWeight: FontWeight.w700,
                  color: HqColors.ink,
                ),
              ),
              const SizedBox(height: 2),
              Text(
                '$kBranchName · $kToday',
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
  const _SalesHero({required this.change});

  final double change;

  @override
  Widget build(BuildContext context) {
    final up = change >= 0;

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
            tzs(kSalesToday),
            style: const TextStyle(
              fontSize: 38,
              fontWeight: FontWeight.w700,
              color: Colors.white,
              height: 1.05,
              letterSpacing: -1,
            ),
          ),
          const SizedBox(height: 8),
          Row(
            children: [
              Icon(
                up ? Icons.arrow_upward_rounded : Icons.arrow_downward_rounded,
                size: 15,
                color: up ? const Color(0xFF5EEAD4) : const Color(0xFFFCA5A5),
              ),
              const SizedBox(width: 4),
              Flexible(
                child: Text(
                  '${pct(change.abs())} vs yesterday',
                  maxLines: 1,
                  overflow: TextOverflow.ellipsis,
                  style: TextStyle(
                    fontSize: 12.5,
                    fontWeight: FontWeight.w600,
                    color: up
                        ? const Color(0xFF5EEAD4)
                        : const Color(0xFFFCA5A5),
                  ),
                ),
              ),
            ],
          ),
          const SizedBox(height: 16),
          Sparkline(
            values: kSales7Days,
            color: VizColors.mint,
            height: 52,
          ),
          const SizedBox(height: 6),
          Row(
            mainAxisAlignment: MainAxisAlignment.spaceBetween,
            children: [
              for (final d in kDay7Labels)
                Flexible(
                  child: Text(
                    d,
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                    style: const TextStyle(
                      fontSize: 10,
                      color: HqOnDark.tertiary,
                    ),
                  ),
                ),
            ],
          ),
          const SizedBox(height: 16),
          const Divider(height: 1, color: HqOnDark.hairline),
          const SizedBox(height: 12),
          Row(
            children: [
              Expanded(
                child: _HeroFigure(
                  label: 'This month',
                  value: tzs(kSalesMonthToDate),
                ),
              ),
              Container(width: 1, height: 30, color: HqOnDark.hairline),
              const Expanded(
                child: _HeroFigure(
                  label: 'Invoices today',
                  value: '$kInvoicesToday',
                ),
              ),
              Container(width: 1, height: 30, color: HqOnDark.hairline),
              Expanded(
                child: _HeroFigure(
                  label: 'Average sale',
                  value: tzs(kAverageSale),
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

class _TodayTiles extends StatelessWidget {
  const _TodayTiles();

  @override
  Widget build(BuildContext context) {
    return IntrinsicHeight(
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          Expanded(
            child: StatTile(
              label: 'Cash collected',
              value: tzs(kCashCollectedToday),
            ),
          ),
          const SizedBox(width: 12),
          Expanded(
            child: StatTile(
              label: 'On credit',
              value: tzs(kCreditSalesToday),
            ),
          ),
        ],
      ),
    );
  }
}

class _StockCard extends StatelessWidget {
  const _StockCard({required this.onNavigate});

  final void Function(String route) onNavigate;

  @override
  Widget build(BuildContext context) {
    return HqCard(
      onTap: () => onNavigate('valuation'),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text('Stock value', style: HqText.label),
                    const SizedBox(height: 4),
                    Text(
                      tzs(kStockValue),
                      style: const TextStyle(
                        fontSize: 26,
                        fontWeight: FontWeight.w700,
                        color: HqColors.ink,
                        letterSpacing: -0.5,
                      ),
                    ),
                  ],
                ),
              ),
              const Icon(Icons.chevron_right, color: HqColors.ink3),
            ],
          ),
          const SizedBox(height: 14),
          ShareBar(
            parts: [
              for (final v in kValuation)
                SharePart(v.category, v.value.toDouble()),
            ],
          ),
        ],
      ),
    );
  }
}

class _LowStockCard extends StatelessWidget {
  const _LowStockCard();

  @override
  Widget build(BuildContext context) {
    return HqCard(
      padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 6),
      child: Column(
        children: [
          for (var i = 0; i < kLowStock.length; i++) ...[
            if (i > 0) const Divider(height: 1),
            Padding(
              padding: const EdgeInsets.symmetric(vertical: 12),
              child: Row(
                children: [
                  Container(
                    width: 8,
                    height: 8,
                    decoration: BoxDecoration(
                      color: kLowStock[i].critical
                          ? HqColors.bad
                          : HqColors.warn,
                      shape: BoxShape.circle,
                    ),
                  ),
                  const SizedBox(width: 12),
                  Expanded(
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Text(
                          kLowStock[i].name,
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
                          kLowStock[i].detail,
                          maxLines: 1,
                          overflow: TextOverflow.ellipsis,
                          style: HqText.tiny,
                        ),
                      ],
                    ),
                  ),
                  const SizedBox(width: 10),
                  Text(
                    '${kLowStock[i].qty} ${kLowStock[i].unit}',
                    style: TextStyle(
                      fontSize: 13.5,
                      fontWeight: FontWeight.w700,
                      color: kLowStock[i].critical
                          ? HqColors.bad
                          : HqColors.ink2,
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

class _ActivityCard extends StatelessWidget {
  const _ActivityCard();

  static const _icons = <String, IconData>{
    'receive': Icons.local_shipping_outlined,
    'adjust': Icons.tune_rounded,
    'item': Icons.inventory_2_outlined,
    'session': Icons.point_of_sale_outlined,
  };

  @override
  Widget build(BuildContext context) {
    return HqCard(
      padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 6),
      child: Column(
        children: [
          for (var i = 0; i < kRecentActivity.length; i++) ...[
            if (i > 0) const Divider(height: 1),
            Padding(
              padding: const EdgeInsets.symmetric(vertical: 12),
              child: Row(
                children: [
                  Container(
                    width: 34,
                    height: 34,
                    decoration: BoxDecoration(
                      color: HqColors.brandSoft,
                      borderRadius: BorderRadius.circular(9),
                    ),
                    child: Icon(
                      _icons[kRecentActivity[i].kind] ?? Icons.circle_outlined,
                      size: 17,
                      color: HqColors.brand,
                    ),
                  ),
                  const SizedBox(width: 12),
                  Expanded(
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Text(
                          kRecentActivity[i].title,
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
                          kRecentActivity[i].detail,
                          maxLines: 1,
                          overflow: TextOverflow.ellipsis,
                          style: HqText.tiny,
                        ),
                      ],
                    ),
                  ),
                  const SizedBox(width: 8),
                  Text(kRecentActivity[i].time, style: HqText.tiny),
                ],
              ),
            ),
          ],
        ],
      ),
    );
  }
}
