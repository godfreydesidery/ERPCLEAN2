import 'package:flutter/material.dart';

import '../app/format.dart';
import '../app/theme.dart';
import '../data/mock.dart';
import '../widgets/charts.dart' hide AgeBucket, BridgeStep;
import '../widgets/common.dart' hide Sparkline;

/// THE MORNING BRIEF — the home screen, and the most important screen in the
/// app. It answers one question before the owner has to ask it, names the
/// three things that need him, and only then shows supporting detail.
///
/// Doctrine order: greeting → verdict hero → exceptions → glance tiles →
/// league table → one drill-through.
class HomeScreen extends StatelessWidget {
  const HomeScreen({super.key, required this.onNavigate});

  final void Function(String route) onNavigate;

  /// Overdue debtors, month-end closing balances for the last six months.
  /// Ends on the two figures the rest of the app quotes: 341M last month and
  /// 318M now. The book is falling, but 45% of it has aged past 60 days —
  /// which is what the tile calls out, and why that line reads red.
  static const List<double> _overdueTrend = <double>[
    288000000,
    301000000,
    296000000,
    324000000,
    341000000,
    318000000,
  ];

  double _salesDeltaPct() =>
      (kSalesMtd - kSalesSameDaysLastMonth) / kSalesSameDaysLastMonth * 100;

  double _planDeltaPct() => kSalesShortfallVsPlan / kSalesPlan * 100;

  @override
  Widget build(BuildContext context) {
    final history =
        kSalesHistory.map((p) => p.value.toDouble()).toList(growable: false);

    return Scaffold(
      backgroundColor: HqColors.bg,
      body: SafeArea(
        bottom: false,
        child: ListView(
          padding: const EdgeInsets.fromLTRB(20, 12, 20, 36),
          children: [
            // 1 — who is looking, at what scope, and what is waiting.
            _GreetingRow(onNavigate: onNavigate),
            const SizedBox(height: 18),

            // 2 — the verdict, before any number.
            HeroCard(
              question: 'Are we going to hit the month?',
              verdict: 'Short TZS 45M on plan — all of it Arusha.',
              heroValue: tzs(kSalesMtd),
              heroSub:
                  'in $kDaysElapsed days · on pace ${tzsBare(kSalesOnPace)}',
              accented: true,
              comparisons: [
                ComparisonRow(
                  label: 'vs same $kDaysElapsed days last month',
                  value: tzs(kSalesSameDaysLastMonth),
                  delta: _salesDeltaPct(),
                  onDark: true,
                ),
                const SizedBox(height: 8),
                ComparisonRow(
                  label: 'vs plan ${tzsBare(kSalesPlan)}',
                  value: tzs(kSalesShortfallVsPlan),
                  delta: _planDeltaPct(),
                  onDark: true,
                ),
              ],
              chart: _HeroTrend(values: history),
              asOf: kAsOf,
              trustLabel: kTrustBadge,
              band: TrustBand.provisional,
            ),
            const SizedBox(height: 22),

            // 3 — exception-led. What needs a person, above the detail.
            const SectionLabel(text: kExceptionsHeadline, trailing: '18 Aug'),
            for (var i = 0; i < kExceptions.length; i++) ...[
              if (i > 0) const SizedBox(height: 10),
              ExceptionTile(
                title: kExceptions[i].title,
                detail: kExceptions[i].detail,
                amount: tzs(kExceptions[i].moneyAtRisk),
                owner: kExceptions[i].owner,
                actionLabel: kExceptions[i].action,
                severe: kExceptions[i].severity == 'bad',
                onAction: () => onNavigate(_exceptionRoutes[i]),
              ),
            ],
            const ResidualLine(
              text: '8 branches within tolerance (≥5% and ≥TZS 2M)',
            ),
            const SizedBox(height: 12),

            // 4 — the four figures an owner checks every morning.
            const SectionLabel(
              text: 'Today at a glance',
              trailing: 'vs last month',
            ),
            _TileRow(
              left: StatTile(
                label: 'Gross margin',
                value: pct(kGrossMarginPct),
                delta: '${pp(-0.3)} vs last month',
                deltaGood: false,
                spark: kMarginHistoryPct
                    .map((v) => v.toDouble())
                    .toList(growable: false),
              ),
              right: StatTile(
                label: 'Cash in hand',
                value: tzsBare(kCashTotal),
                delta: '$kRunwayWeeks wks of runway',
                deltaGood: false,
                spark: kCash13Weeks
                    .map((w) => w.balance.toDouble())
                    .toList(growable: false),
              ),
            ),
            const SizedBox(height: 12),
            _TileRow(
              left: StatTile(
                label: 'Overdue from customers',
                value: tzsBare(kDebtorsOverdue),
                delta: '45% of it is 60 days+',
                deltaGood: false,
                spark: _overdueTrend,
              ),
              right: StatTile(
                label: 'Stock on hand',
                value: tzsBare(kStockValue),
                delta: '$kStockCoverMonths months of cover',
                deltaGood: false,
                spark: kStockHistory
                    .map((v) => v.toDouble())
                    .toList(growable: false),
              ),
            ),
            const SizedBox(height: 22),

            // 5 — where the month is actually being won and lost.
            HqCard(
              onTap: () => onNavigate('league'),
              padding: const EdgeInsets.fromLTRB(16, 16, 16, 18),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                mainAxisSize: MainAxisSize.min,
                children: [
                  Row(
                    children: [
                      const Expanded(
                        child: Text(
                          'Sales by branch, this month',
                          style: TextStyle(
                            fontSize: 15.5,
                            fontWeight: FontWeight.w700,
                            color: HqColors.ink,
                          ),
                        ),
                      ),
                      const SizedBox(width: 8),
                      Icon(
                        Icons.chevron_right_rounded,
                        size: 20,
                        color: HqColors.ink3.withValues(alpha: 0.9),
                      ),
                    ],
                  ),
                  const SizedBox(height: 3),
                  const Text(
                    'Month to date, each against its own plan',
                    style: HqText.tiny,
                  ),
                  const SizedBox(height: 16),
                  RankedBars(
                    rows: [
                      for (final b in kBranches)
                        RankRow(
                          b.name,
                          b.salesMtd.toDouble(),
                          '${tzsBare(b.salesMtd)} · ${pct(b.vsPlanPct, sign: true)}',
                          status: _statusFor(b),
                        ),
                    ],
                  ),
                  const SizedBox(height: 16),
                  const Divider(height: 1, thickness: 1, color: HqColors.line),
                  const SizedBox(height: 12),
                  const AsOfLine(asOf: kAsOf, coverage: kCoverage),
                ],
              ),
            ),
            const SizedBox(height: 18),

            // 6 — the one tap-through, in the thumb zone.
            DrillButton(
              label: 'Why is Arusha down?',
              onTap: () => onNavigate('arusha'),
            ),
          ],
        ),
      ),
    );
  }

  /// Red for the branch outside the materiality rule, brand for the best
  /// performer, grey for everyone inside tolerance. Colour carries the
  /// verdict, never the identity of a row.
  static RankStatus _statusFor(BranchLine b) {
    if (b.name == kProblemBranch) return RankStatus.bad;
    if (b.name == 'Dar es Salaam') return RankStatus.best;
    return RankStatus.normal;
  }

  static const List<String> _exceptionRoutes = <String>[
    'arusha',
    'debtors',
    'stock',
  ];
}

// ---------------------------------------------------------------------------
// Greeting
// ---------------------------------------------------------------------------

class _GreetingRow extends StatelessWidget {
  const _GreetingRow({required this.onNavigate});

  final void Function(String route) onNavigate;

  @override
  Widget build(BuildContext context) {
    final firstName = kUser.name.split(' ').first;

    return Row(
      crossAxisAlignment: CrossAxisAlignment.center,
      children: [
        Container(
          width: 46,
          height: 46,
          decoration: const BoxDecoration(
            shape: BoxShape.circle,
            gradient: HqSurfaces.brandGradient,
          ),
          alignment: Alignment.center,
          child: Text(
            kUser.initials,
            style: const TextStyle(
              fontSize: 15,
              fontWeight: FontWeight.w700,
              letterSpacing: 0.4,
              color: Colors.white,
            ),
          ),
        ),
        const SizedBox(width: 12),
        Expanded(
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            mainAxisSize: MainAxisSize.min,
            children: [
              Text(
                'Good morning, $firstName',
                maxLines: 1,
                overflow: TextOverflow.ellipsis,
                style: const TextStyle(
                  fontSize: 17,
                  fontWeight: FontWeight.w700,
                  height: 1.2,
                  color: HqColors.ink,
                ),
              ),
              const SizedBox(height: 5),
              Row(
                children: [
                  Flexible(
                    child: Text(
                      kCompanyName,
                      maxLines: 1,
                      overflow: TextOverflow.ellipsis,
                      style: HqText.tiny.copyWith(color: HqColors.ink2),
                    ),
                  ),
                  const SizedBox(width: 8),
                  _ScopeChip(onTap: () => onNavigate('league')),
                ],
              ),
            ],
          ),
        ),
        const SizedBox(width: 10),
        _BellButton(count: kExceptions.length, onTap: () => onNavigate('trade')),
      ],
    );
  }
}

/// The branch scope the whole brief is drawn at. Doctrine 11 — company-wide,
/// never "group".
class _ScopeChip extends StatelessWidget {
  const _ScopeChip({required this.onTap});

  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    final radius = BorderRadius.circular(999);

    return Material(
      color: HqColors.brandSoft,
      borderRadius: radius,
      child: InkWell(
        onTap: onTap,
        borderRadius: radius,
        child: Padding(
          padding: const EdgeInsets.fromLTRB(9, 3, 5, 3),
          child: Row(
            mainAxisSize: MainAxisSize.min,
            children: const [
              Text(
                'All branches',
                style: TextStyle(
                  fontSize: 11.5,
                  fontWeight: FontWeight.w700,
                  height: 1.25,
                  color: HqColors.brandD,
                ),
              ),
              Icon(
                Icons.expand_more_rounded,
                size: 15,
                color: HqColors.brandD,
              ),
            ],
          ),
        ),
      ),
    );
  }
}

class _BellButton extends StatelessWidget {
  const _BellButton({required this.count, required this.onTap});

  final int count;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    return Material(
      color: HqColors.panel,
      shape: const CircleBorder(side: BorderSide(color: HqColors.line)),
      clipBehavior: Clip.antiAlias,
      child: InkWell(
        onTap: onTap,
        child: SizedBox(
          width: 44,
          height: 44,
          child: Stack(
            alignment: Alignment.center,
            children: [
              const Icon(
                Icons.notifications_none_rounded,
                size: 22,
                color: HqColors.ink2,
              ),
              Positioned(
                top: 9,
                right: 9,
                child: Container(
                  width: 16,
                  height: 16,
                  decoration: const BoxDecoration(
                    color: HqColors.bad,
                    shape: BoxShape.circle,
                  ),
                  alignment: Alignment.center,
                  child: Text(
                    '$count',
                    style: const TextStyle(
                      fontSize: 10,
                      fontWeight: FontWeight.w700,
                      height: 1.0,
                      color: Colors.white,
                    ),
                  ),
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }
}

// ---------------------------------------------------------------------------
// Hero trend — 12 months, so a slow slide cannot hide (doctrine 5)
// ---------------------------------------------------------------------------

class _HeroTrend extends StatelessWidget {
  const _HeroTrend({required this.values});

  final List<double> values;

  @override
  Widget build(BuildContext context) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      mainAxisSize: MainAxisSize.min,
      children: [
        Sparkline(values: values, color: VizColors.mint, height: 46),
        const SizedBox(height: 7),
        Row(
          mainAxisAlignment: MainAxisAlignment.spaceBetween,
          children: [
            Flexible(
              child: Text(
                '${kMonthLabels.first} · 12 months',
                maxLines: 1,
                overflow: TextOverflow.ellipsis,
                style: const TextStyle(fontSize: 10.5, color: HqOnDark.tertiary),
              ),
            ),
            const SizedBox(width: 8),
            const Flexible(
              child: Text(
                'Aug · part month',
                maxLines: 1,
                overflow: TextOverflow.ellipsis,
                style: TextStyle(fontSize: 10.5, color: HqOnDark.tertiary),
              ),
            ),
          ],
        ),
      ],
    );
  }
}

// ---------------------------------------------------------------------------
// Two tiles, equal height
// ---------------------------------------------------------------------------

class _TileRow extends StatelessWidget {
  const _TileRow({required this.left, required this.right});

  final Widget left;
  final Widget right;

  @override
  Widget build(BuildContext context) {
    return IntrinsicHeight(
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          Expanded(child: left),
          const SizedBox(width: 12),
          Expanded(child: right),
        ],
      ),
    );
  }
}
