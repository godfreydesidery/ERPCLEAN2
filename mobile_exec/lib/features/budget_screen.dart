import 'package:flutter/material.dart';

import '../app/format.dart';
import '../app/theme.dart';
import '../widgets/charts.dart';
import '../widgets/common.dart';

/// "Plan vs Reality" - budget versus actual for the month to date.
///
/// The owner's question is not "what is the budget" but "are we spending what
/// we said". The screen answers it in one line, then shows exactly where the
/// TZS 12M went: a bridge from plan profit to actual profit, the cost lines
/// that broke their envelope, and how much of each budget is already burnt.
///
/// Demo data only - nothing here talks to a backend.

// --- private demo data -------------------------------------------------------
// Underscore-prefixed so it can never collide with the shared mock dataset.

/// Cumulative shortfall against plan, TZS millions, eight months to date.
/// Six periods minimum on the headline; this carries eight.
const _behindPlanTrend = <double>[3.1, 2.4, 5.6, 4.2, 6.8, 8.4, 9.6, 12.0];

const _planProfit = 96000000.0;
const _actualProfit = 84000000.0;

const _asOf = 'as at 18 Aug 2026, 18:40';
const _coverage = '8 branches - 1 company - 42 cost lines';
const _trust = 'Ledger posted to 17 Aug; August accruals provisional';

class BudgetScreen extends StatelessWidget {
  const BudgetScreen({super.key});

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: HqColors.bg,
      appBar: AppBar(
        title: const Text('Are we spending what we said?', style: HqText.title),
      ),
      body: SafeArea(
        top: false,
        child: ListView(
          padding: const EdgeInsets.fromLTRB(20, 12, 20, 32),
          children: [
            _hero(),
            const SizedBox(height: 18),
            _variance(),
            const SizedBox(height: 16),
            _overspends(),
            const SizedBox(height: 16),
            _consumed(),
            const SizedBox(height: 16),
            const ResidualLine(text: '11 other cost lines within 5% of plan.'),
            const SizedBox(height: 6),
            const Text(
              'A line is raised here only when it misses plan by more than '
              'TZS 1.5M or 5%, whichever bites first.',
              style: HqText.tiny,
            ),
            const SizedBox(height: 18),
            DrillButton(
              label: 'Open the freight line',
              onTap: () => _drill(context, 'Freight'),
            ),
            const SizedBox(height: 18),
            const AsOfLine(asOf: _asOf, coverage: _coverage),
          ],
        ),
      ),
    );
  }

  // --- hero ------------------------------------------------------------------

  Widget _hero() {
    return HeroCard(
      question: 'Are we spending what we said?',
      verdict: 'Ahead on sales, over on cost - net TZS 12M behind plan.',
      heroValue: 'TZS 12M',
      heroSub: 'behind plan, month to date',
      asOf: _asOf,
      trustLabel: _trust,
      band: TrustBand.provisional,
      comparisons: const [
        ComparisonRow(
          label: 'Sales vs plan',
          value: '+3%',
          delta: 3.0,
          onDark: true,
        ),
        ComparisonRow(
          label: 'Cost vs plan',
          value: '+7%',
          delta: 7.0,
          higherIsBetter: false,
          onDark: true,
        ),
      ],
      chart: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          const Sparkline(
            values: _behindPlanTrend,
            color: VizColors.mint,
            height: 46,
          ),
          const SizedBox(height: 6),
          // Both ends are flexible: on a 360-wide phone the two captions
          // together are wider than the hero's inner box, so each takes at
          // most half and ellipses rather than overflowing. On a normal
          // handset neither is truncated and the spaceBetween look survives.
          Row(
            crossAxisAlignment: CrossAxisAlignment.end,
            mainAxisAlignment: MainAxisAlignment.spaceBetween,
            children: const [
              Flexible(
                child: Text(
                  'Jan  TZS 3.1M behind',
                  maxLines: 1,
                  overflow: TextOverflow.ellipsis,
                  style: TextStyle(fontSize: 11.5, color: HqOnDark.tertiary),
                ),
              ),
              SizedBox(width: 10),
              Flexible(
                child: Text(
                  'Aug  TZS 12M',
                  maxLines: 1,
                  overflow: TextOverflow.ellipsis,
                  textAlign: TextAlign.right,
                  style: TextStyle(
                    fontSize: 11.5,
                    color: HqOnDark.secondary,
                    fontWeight: FontWeight.w600,
                  ),
                ),
              ),
            ],
          ),
        ],
      ),
    );
  }

  // --- where the variance is -------------------------------------------------

  Widget _variance() {
    return HqCard(
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          const SectionLabel(
            text: 'WHERE THE VARIANCE IS',
            trailing: 'month to date',
          ),
          const SizedBox(height: 14),
          const WaterfallBridge(
            height: 178,
            steps: [
              BridgeStep('Plan profit', _planProfit, isTotal: true),
              BridgeStep('Sales', 14400000),
              BridgeStep('Cost of sales', -19100000),
              BridgeStep('Overhead', -7300000),
              BridgeStep('Actual profit', _actualProfit, isTotal: true),
            ],
          ),
          const SizedBox(height: 14),
          // Two intrinsically-sized money columns plus an arrow are ~6px short
          // of the card's inner width on a 360 phone - one character of
          // formatting drift and it overflows. Each end takes a half instead,
          // which reads identically to the old spaceBetween.
          Row(
            crossAxisAlignment: CrossAxisAlignment.end,
            children: [
              Expanded(
                child: _bridgeEnd('Planned', tzs(_planProfit), HqColors.ink2),
              ),
              const Padding(
                padding: EdgeInsets.symmetric(horizontal: 8),
                child: Icon(Icons.arrow_forward_rounded,
                    size: 16, color: HqColors.ink3),
              ),
              Expanded(
                child: _bridgeEnd('Actual', tzs(_actualProfit), HqColors.bad,
                    alignEnd: true),
              ),
            ],
          ),
          const SizedBox(height: 12),
          const Divider(height: 1),
          const SizedBox(height: 12),
          const Text(
            'Selling did its job: volume beat plan by TZS 14.4M. Cost of sales '
            'gave it all back and more - TZS 19.1M worse than plan, with '
            'overhead a further TZS 7.3M over.',
            style: HqText.body,
          ),
        ],
      ),
    );
  }

  Widget _bridgeEnd(String label, String value, Color valueColor,
      {bool alignEnd = false}) {
    return Column(
      mainAxisSize: MainAxisSize.min,
      crossAxisAlignment:
          alignEnd ? CrossAxisAlignment.end : CrossAxisAlignment.start,
      children: [
        Text(
          label,
          maxLines: 1,
          overflow: TextOverflow.ellipsis,
          textAlign: alignEnd ? TextAlign.right : TextAlign.left,
          style: HqText.tiny,
        ),
        const SizedBox(height: 2),
        // The figure keeps its full weight and never truncates: it scales
        // down a hair instead if the half-width is tight.
        FittedBox(
          fit: BoxFit.scaleDown,
          alignment: alignEnd ? Alignment.centerRight : Alignment.centerLeft,
          child: Text(
            value,
            maxLines: 1,
            style: HqText.tileValue.copyWith(color: valueColor),
          ),
        ),
      ],
    );
  }

  // --- biggest overspends ----------------------------------------------------

  Widget _overspends() {
    return HqCard(
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          const SectionLabel(
            text: 'BIGGEST OVERSPENDS',
            trailing: 'over plan',
          ),
          const SizedBox(height: 14),
          const RankedBars(
            rows: [
              RankRow('Freight', 6400000, '+41%', status: RankStatus.bad),
              RankRow('Packaging', 4100000, '+27%'),
              RankRow('Fuel', 3200000, '+18%'),
              RankRow('Repairs', 2600000, '+15%'),
              RankRow('Electricity', 1400000, '+9%'),
            ],
          ),
          const SizedBox(height: 14),
          const Divider(height: 1),
          const SizedBox(height: 12),
          Text(
            'Freight alone is ${tzs(6400000)} over a ${tzs(15600000)} plan '
            '(${pct(41.0, sign: true)}) - more than the other four together. '
            'Mwanza and Mbeya deliveries carry it; Neema Kileo has the file.',
            style: HqText.body,
          ),
        ],
      ),
    );
  }

  // --- budget consumed -------------------------------------------------------

  Widget _consumed() {
    return HqCard(
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          const SectionLabel(
            text: 'BUDGET CONSUMED',
            trailing: '58% of the month gone',
          ),
          const SizedBox(height: 16),
          // Three meters share the row, sized from the card's real width
          // rather than the 112px default - at 360 the default trio is far
          // wider than the card. Widths are floored so 3*itemW + 2*gap can
          // never round past maxWidth and spill the third meter onto a second
          // run, and the ring is never wider than its own column.
          LayoutBuilder(
            builder: (context, constraints) {
              const gap = 12.0;
              final itemW =
                  ((constraints.maxWidth - gap * 2) / 3).floorToDouble();
              var donut = (itemW - 8).clamp(52.0, 92.0);
              if (donut > itemW) donut = itemW;
              return Wrap(
                spacing: gap,
                runSpacing: 18,
                alignment: WrapAlignment.spaceBetween,
                children: [
                  SizedBox(
                    width: itemW,
                    child: DonutMeter(
                      fraction: 0.64,
                      centre: '64%',
                      caption: 'Sales & marketing',
                      color: HqColors.good,
                      size: donut,
                    ),
                  ),
                  SizedBox(
                    width: itemW,
                    child: DonutMeter(
                      fraction: 0.91,
                      centre: '91%',
                      caption: 'Factory overhead',
                      color: HqColors.bad,
                      size: donut,
                    ),
                  ),
                  SizedBox(
                    width: itemW,
                    child: DonutMeter(
                      fraction: 0.72,
                      centre: '72%',
                      caption: 'Admin',
                      color: HqColors.warn,
                      size: donut,
                    ),
                  ),
                ],
              );
            },
          ),
          const SizedBox(height: 16),
          const Divider(height: 1),
          const SizedBox(height: 12),
          const Text(
            'Factory overhead has burnt 91% of the month\'s envelope with 42% '
            'of the month still to run - on this pace it closes TZS 9M over. '
            'Sales & marketing and Admin are both inside pace.',
            style: HqText.body,
          ),
        ],
      ),
    );
  }

  // --- drill -----------------------------------------------------------------

  void _drill(BuildContext context, String line) {
    ScaffoldMessenger.of(context)
      ..hideCurrentSnackBar()
      ..showSnackBar(
        SnackBar(
          backgroundColor: HqColors.ink,
          behavior: SnackBarBehavior.floating,
          duration: const Duration(seconds: 3),
          shape: RoundedRectangleBorder(
            borderRadius: BorderRadius.circular(HqRadii.sm),
          ),
          content: Text(
            '$line: 34 postings, ${tzsExact(22000000)} spent against '
            '${tzsExact(15600000)} planned.',
            style: const TextStyle(color: Colors.white),
          ),
        ),
      );
  }
}
