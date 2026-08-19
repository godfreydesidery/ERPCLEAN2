import 'package:flutter/material.dart';

import '../app/format.dart';
import '../app/theme.dart';
import '../data/mock.dart' hide BridgeStep;
import '../widgets/charts.dart';
import '../widgets/common.dart' hide Sparkline;

/// "Who is winning, who is not?" - the peer-comparison screen a GM runs
/// managers from.
///
/// The screen makes one argument and makes it twice. Sorted against plan,
/// seven branches are fine and Arusha is the entire company gap. Sorted by
/// margin the order changes: the branch selling the second-most is earning
/// the second-least. Volume and earnings are not the same league table.
class BranchLeagueScreen extends StatelessWidget {
  const BranchLeagueScreen({super.key, this.onOpenBranch});

  /// Tapping a branch hands the name up; the demo shell decides where to go.
  final void Function(String branch)? onOpenBranch;

  @override
  Widget build(BuildContext context) {
    final byPlan = <BranchLine>[...kBranches]
      ..sort((a, b) => b.vsPlanPct.compareTo(a.vsPlanPct));
    final byMargin = <BranchLine>[...kBranches]
      ..sort((a, b) => b.marginPct.compareTo(a.marginPct));
    final bySize = <BranchLine>[...kBranches]
      ..sort((a, b) => b.salesMtd.compareTo(a.salesMtd));

    final bestOnPlan = byPlan.first;
    final bestOnMargin = byMargin.first;
    final worst = byPlan.last;

    // Everything below is derived from the branch table - nothing is retyped,
    // so the consolidation always ties out on screen.
    final salesTotal = kBranches.fold<num>(0, (a, b) => a + b.salesMtd);
    final planTotal = kBranches.fold<num>(0, (a, b) => a + b.planMtd);
    final companyGap = salesTotal - planTotal;
    final companyVsPlanPct = companyGap / planTotal * 100;
    final others = kBranches.where((b) => b.name != worst.name);
    final othersGap = others.fold<num>(0, (a, b) => a + b.vsPlanValue);
    final othersPlan = others.fold<num>(0, (a, b) => a + b.planMtd);

    // The second-biggest seller by value - the point of the margin card.
    final runnerUp = bySize[1];
    final runnerUpMarginRank =
        byMargin.indexWhere((b) => b.name == runnerUp.name) + 1;

    return Scaffold(
      backgroundColor: HqColors.bg,
      appBar: AppBar(
        title: const Text(
          'Who is winning, who is not?',
          style: TextStyle(fontSize: 17, fontWeight: FontWeight.w700),
        ),
      ),
      body: ListView(
        padding: const EdgeInsets.fromLTRB(18, 12, 18, 32),
        children: [
          // 1. The verdict ---------------------------------------------------
          _HeroStrip(
            verdict: 'Arusha is the whole gap. Eight branches, one problem.',
            heroValue: tzs(salesTotal),
            heroSub: 'this month, against a ${tzs(planTotal)} plan for the '
                'same $kDaysElapsed days',
            comparisons: [
              ComparisonRow(
                label: 'Company sales vs month-to-date plan',
                value: tzs(companyGap, sign: true),
                delta: companyVsPlanPct.toDouble(),
                onDark: true,
              ),
              ComparisonRow(
                label: 'The other seven, against their own plans',
                value: tzs(othersGap, sign: true),
                delta: (othersGap / othersPlan * 100).toDouble(),
                onDark: true,
              ),
              ComparisonRow(
                label: 'Same $kDaysElapsed days last month',
                value: tzs(kSalesSameDaysLastMonth),
                delta: ((salesTotal - kSalesSameDaysLastMonth) /
                        kSalesSameDaysLastMonth *
                        100)
                    .toDouble(),
                onDark: true,
              ),
            ],
          ),
          const SizedBox(height: 14),

          // 2. Sales against plan --------------------------------------------
          HqCard(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                const _CardHead(
                  title: 'Sales vs plan',
                  caption: 'Every branch against its own month-to-date plan',
                  trailing: '% OF PLAN',
                ),
                const SizedBox(height: 16),
                RankedBars(
                  rows: [
                    for (final b in byPlan)
                      RankRow(
                        b.name,
                        (100 + b.vsPlanPct).toDouble(),
                        pct(100 + b.vsPlanPct),
                        status: b.name == worst.name
                            ? RankStatus.bad
                            : (b.name == bestOnPlan.name
                                ? RankStatus.best
                                : RankStatus.normal),
                      ),
                  ],
                ),
                const SizedBox(height: 16),
                const Divider(height: 1, thickness: 1, color: HqColors.line),
                const SizedBox(height: 12),
                _OwnerNote(
                  manager: worst.manager,
                  role: 'Branch Manager, ${worst.name}',
                  amount: tzs(worst.vsPlanValue.abs()),
                  onTap: () => onOpenBranch?.call(worst.name),
                ),
                const SizedBox(height: 12),
                _Reading(
                  tint: HqColors.brand,
                  text: '${bestOnPlan.name} leads the league at '
                      '${pct(100 + bestOnPlan.vsPlanPct)} of plan on '
                      '${tzs(bestOnPlan.salesMtd)} of sales. Small branch, '
                      'clean month.',
                ),
              ],
            ),
          ),
          const SizedBox(height: 14),

          // 3. Margin - the same eight, a different order ---------------------
          HqCard(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                const _CardHead(
                  title: 'Margin by branch',
                  caption: 'Gross margin earned, and the move on last month',
                  trailing: 'MARGIN',
                ),
                const SizedBox(height: 16),
                RankedBars(
                  rows: [
                    for (final b in byMargin)
                      RankRow(
                        b.name,
                        b.marginPct.toDouble(),
                        '${pct(b.marginPct)}   '
                        '${pp(b.marginPct - b.marginLastMonthPct)}',
                        status:
                            (b.name == worst.name || b.name == runnerUp.name)
                                ? RankStatus.bad
                                : (b.name == bestOnMargin.name
                                    ? RankStatus.best
                                    : RankStatus.normal),
                      ),
                  ],
                ),
                const SizedBox(height: 16),
                _Reading(
                  tint: HqColors.bad,
                  text: 'The top seller is not the top earner. '
                      '${runnerUp.name} sells the second most in the company '
                      '(${tzs(runnerUp.salesMtd)}) and ranks '
                      '${runnerUpMarginRank}th of ${kBranches.length} on '
                      'margin - ${pct(runnerUp.marginPct)}, '
                      '${pp(runnerUp.marginPct - runnerUp.marginLastMonthPct)} '
                      'on July. That volume is being bought with discount.',
                ),
                const SizedBox(height: 9),
                _Reading(
                  text: '${bestOnMargin.name} holds '
                      '${pct(bestOnMargin.marginPct)} on the largest book in '
                      'the company. It is the branch to copy, not the branch '
                      'to chase.',
                ),
              ],
            ),
          ),
          const SizedBox(height: 14),

          // 4. Channel mix ----------------------------------------------------
          HqCard(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                const _CardHead(
                  title: 'Channel mix',
                  caption: 'Where the company-wide sales came from',
                  trailing: 'SHARE',
                ),
                const SizedBox(height: 16),
                ShareBar(
                  parts: [
                    for (final c in kChannels)
                      SharePart(c.name, c.salesMtd.toDouble()),
                  ],
                ),
                const SizedBox(height: 16),
                _Reading(
                  text: 'Counter was '
                      '${pct(kChannels.first.shareLastMonthPct)} of sales in '
                      'July and is ${pct(kChannels.first.sharePct)} now. '
                      'Counter earns ${pct(kChannels.first.marginPct)}, '
                      'project work earns ${pct(kChannels.last.marginPct)} - '
                      'the shift in mix is where the margin went.',
                ),
              ],
            ),
          ),
          const SizedBox(height: 14),

          // 5. Twelve months, so a slow slide cannot hide ----------------------
          HqCard(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                const _CardHead(
                  title: 'Company sales, 12 months',
                  caption: 'All eight branches consolidated, against plan',
                  trailing: 'TZS',
                ),
                const SizedBox(height: 16),
                ColumnTrend(
                  values: [for (final m in kSalesHistory) m.value.toDouble()],
                  labels: [for (final m in kSalesHistory) m.label],
                  target: kSalesPlan.toDouble(),
                  height: 162,
                ),
                const SizedBox(height: 14),
                _Reading(
                  text: 'The last column is hatched: $kDaysElapsed days of '
                      '$kDaysInMonth, not a finished month. On this pace '
                      'August lands at ${tzs(kSalesOnPace)} against a '
                      '${tzs(kSalesPlan)} plan, and last August closed at '
                      '${tzs(kSalesSameMonthLastYear)}.',
                ),
              ],
            ),
          ),

          ResidualLine(
            text: '${kBranches.length - 1} branches are within 5% of plan and '
                'are not named. $kMaterialityRule.',
          ),
          const SizedBox(height: 2),
          DrillButton(
            label: 'Open ${worst.name}',
            onTap: () => onOpenBranch?.call(worst.name),
          ),
        ],
      ),
    );
  }
}

// ---------------------------------------------------------------------------
// A shorter gradient hero than HeroCard. This screen's argument is the two
// league tables, so the hero states the verdict and then gets out of the way.
// ---------------------------------------------------------------------------

class _HeroStrip extends StatelessWidget {
  const _HeroStrip({
    required this.verdict,
    required this.heroValue,
    required this.heroSub,
    required this.comparisons,
  });

  final String verdict;
  final String heroValue;
  final String heroSub;
  final List<Widget> comparisons;

  @override
  Widget build(BuildContext context) {
    return Container(
      width: double.infinity,
      padding: const EdgeInsets.fromLTRB(18, 16, 18, 14),
      decoration: BoxDecoration(
        gradient: HqSurfaces.heroGradient,
        borderRadius: BorderRadius.circular(20),
        boxShadow: HqSurfaces.hero,
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        mainAxisSize: MainAxisSize.min,
        children: [
          const Text(
            'COMPANY-WIDE, MONTH TO DATE',
            style: TextStyle(
              fontSize: 11,
              fontWeight: FontWeight.w700,
              letterSpacing: 0.9,
              color: HqOnDark.tertiary,
            ),
          ),
          const SizedBox(height: 9),
          Text(
            verdict,
            style: const TextStyle(
              fontSize: 17,
              fontWeight: FontWeight.w600,
              height: 1.3,
              color: HqOnDark.primary,
            ),
          ),
          const SizedBox(height: 14),
          Row(
            children: [
              Container(
                width: 3,
                height: 34,
                decoration: BoxDecoration(
                  color: HqSurfaces.accent,
                  borderRadius: BorderRadius.circular(2),
                ),
              ),
              const SizedBox(width: 12),
              Expanded(
                child: FittedBox(
                  fit: BoxFit.scaleDown,
                  alignment: Alignment.centerLeft,
                  child: Text(
                    heroValue,
                    maxLines: 1,
                    style: const TextStyle(
                      fontSize: 36,
                      fontWeight: FontWeight.w700,
                      letterSpacing: -1,
                      height: 1.05,
                      color: HqOnDark.primary,
                    ),
                  ),
                ),
              ),
            ],
          ),
          const SizedBox(height: 6),
          Text(
            heroSub,
            style: const TextStyle(
              fontSize: 12.5,
              height: 1.35,
              color: HqOnDark.tertiary,
            ),
          ),
          const SizedBox(height: 10),
          ...comparisons,
          const SizedBox(height: 12),
          const Divider(height: 1, thickness: 1, color: HqOnDark.hairline),
          const SizedBox(height: 11),
          const Row(
            children: [
              Expanded(
                child: AsOfLine(
                  asOf: kAsOf,
                  coverage: '8 of 8 branches reported',
                  onDark: true,
                ),
              ),
              SizedBox(width: 10),
              TrustChip(
                band: TrustBand.provisional,
                onDark: true,
                overrideLabel: kTrustBadge,
              ),
            ],
          ),
        ],
      ),
    );
  }
}

// ---------------------------------------------------------------------------
// Small parts
// ---------------------------------------------------------------------------

class _CardHead extends StatelessWidget {
  const _CardHead({required this.title, this.caption, this.trailing});

  final String title;
  final String? caption;
  final String? trailing;

  @override
  Widget build(BuildContext context) {
    return Row(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Expanded(
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            mainAxisSize: MainAxisSize.min,
            children: [
              Text(
                title,
                maxLines: 2,
                overflow: TextOverflow.ellipsis,
                style: HqText.title.copyWith(fontSize: 16.5, height: 1.2),
              ),
              if (caption != null) ...[
                const SizedBox(height: 4),
                Text(
                  caption!,
                  maxLines: 2,
                  overflow: TextOverflow.ellipsis,
                  style: HqText.tiny,
                ),
              ],
            ],
          ),
        ),
        if (trailing != null) ...[
          const SizedBox(width: 12),
          Container(
            padding: const EdgeInsets.symmetric(horizontal: 9, vertical: 4),
            decoration: BoxDecoration(
              color: HqColors.panel2,
              borderRadius: BorderRadius.circular(999),
              border: Border.all(color: HqColors.line),
            ),
            child: Text(
              trailing!,
              maxLines: 1,
              style: const TextStyle(
                fontSize: 10,
                fontWeight: FontWeight.w700,
                letterSpacing: 0.7,
                color: HqColors.ink3,
              ),
            ),
          ),
        ],
      ],
    );
  }
}

/// The muted line under a chart - the sentence the chart exists to prove.
class _Reading extends StatelessWidget {
  const _Reading({required this.text, this.tint = HqColors.line2});

  final String text;
  final Color tint;

  @override
  Widget build(BuildContext context) {
    return Row(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Container(
          width: 6,
          height: 6,
          margin: const EdgeInsets.only(top: 6),
          decoration: BoxDecoration(color: tint, shape: BoxShape.circle),
        ),
        const SizedBox(width: 9),
        Expanded(
          child: Text(
            text,
            style: HqText.tiny.copyWith(color: HqColors.ink2, height: 1.45),
          ),
        ),
      ],
    );
  }
}

/// Doctrine 6 - the branch that is short carries a person's name, and one tap
/// that takes you to them.
class _OwnerNote extends StatelessWidget {
  const _OwnerNote({
    required this.manager,
    required this.role,
    required this.amount,
    this.onTap,
  });

  final String manager;
  final String role;
  final String amount;
  final VoidCallback? onTap;

  @override
  Widget build(BuildContext context) {
    final radius = BorderRadius.circular(HqRadii.sm);

    return Material(
      color: Colors.transparent,
      borderRadius: radius,
      child: InkWell(
        onTap: onTap,
        borderRadius: radius,
        child: Padding(
          padding: const EdgeInsets.symmetric(vertical: 3),
          child: Row(
            children: [
              Container(
                width: 32,
                height: 32,
                alignment: Alignment.center,
                decoration: BoxDecoration(
                  color: HqColors.bad.withValues(alpha: 0.10),
                  shape: BoxShape.circle,
                  border:
                      Border.all(color: HqColors.bad.withValues(alpha: 0.22)),
                ),
                child: Text(
                  ExceptionTile.initialsOf(manager),
                  style: const TextStyle(
                    fontSize: 11.5,
                    fontWeight: FontWeight.w700,
                    color: HqColors.bad,
                  ),
                ),
              ),
              const SizedBox(width: 10),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  mainAxisSize: MainAxisSize.min,
                  children: [
                    Text(
                      manager,
                      maxLines: 1,
                      overflow: TextOverflow.ellipsis,
                      style: const TextStyle(
                        fontSize: 13.5,
                        fontWeight: FontWeight.w700,
                        color: HqColors.ink,
                      ),
                    ),
                    const SizedBox(height: 2),
                    Text(
                      role,
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
                mainAxisSize: MainAxisSize.min,
                children: [
                  Text(
                    amount,
                    maxLines: 1,
                    style: const TextStyle(
                      fontSize: 15,
                      fontWeight: FontWeight.w700,
                      letterSpacing: -0.2,
                      color: HqColors.bad,
                    ),
                  ),
                  const SizedBox(height: 2),
                  const Text('behind plan', style: HqText.tiny),
                ],
              ),
              const Icon(
                Icons.chevron_right_rounded,
                size: 20,
                color: HqColors.ink3,
              ),
            ],
          ),
        ),
      ),
    );
  }
}
