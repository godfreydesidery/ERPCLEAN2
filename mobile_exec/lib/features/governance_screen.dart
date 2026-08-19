import 'package:flutter/material.dart';

import '../app/format.dart';
import '../app/theme.dart';
import '../widgets/charts.dart';
import '../widgets/common.dart';

/// "What Needs Watching" - the governance register.
///
/// This is the screen that protects the owner. Every other screen answers a
/// question about performance; this one answers a question about conduct:
/// what happened this month that policy said should not happen, who did it,
/// and whether this month's numbers can be trusted at all.
///
/// Exception-led by nature - the register IS the content, so the three items
/// that need a human explanation sit above every supporting chart.

// --- Demo dataset, private to this file so it can never collide with the
// --- shared mock dataset.

const _asOf = '18 Aug 2026, 18:40';
const _coverage = '8 of 8 branches - 1 to 18 Aug';
const _windowDays = 18;

/// Overrides raised per month, Mar to Aug 2026. Six periods on the headline.
const _history = <double>[14, 11, 15, 10, 12, 9];
const _historySpan = 'Mar to Aug';

const _overridesThisMonth = 9;
const _overridesLastMonth = 12;
const _overridesSixMonthAvg = 11.8;

/// Kind of override, what it put at stake, and how many were raised.
class _Kind {
  const _Kind(this.label, this.value, this.count, this.status);
  final String label;
  final double value;
  final int count;
  final RankStatus status;
}

const _kinds = <_Kind>[
  _Kind('Below-cost sales', 12400000, 2, RankStatus.bad),
  _Kind('Discounts beyond policy', 8900000, 3, RankStatus.bad),
  _Kind('Negative stock allowed', 4600000, 1, RankStatus.normal),
  _Kind('Backdated entries', 2800000, 1, RankStatus.normal),
  _Kind('Voided documents', 1600000, 1, RankStatus.bad),
  _Kind('Manual journals, unusual accounts', 900000, 1, RankStatus.normal),
];

double get _atStake => _kinds.fold<double>(0, (a, k) => a + k.value);

/// A month-end close gate. [done] is the fraction complete and [weight] is how
/// much of the readiness score it carries - a trial balance that does not tie
/// is worth more than one unreconciled bank account.
class _Gate {
  const _Gate(this.label, this.detail, this.weight, this.done);
  final String label;
  final String detail;
  final double weight;
  final double done;
  bool get pass => done >= 0.999;
}

const _gates = <_Gate>[
  _Gate('Trial balance ties', 'Debits equal credits across all 8 branches', 30, 1.0),
  _Gate('Depreciation posted', 'The August run has not been started', 25, 0.0),
  _Gate('Banks reconciled', '7 of 8 accounts - Mbeya still open', 25, 0.875),
  _Gate('No unfiscalised sales', '3 counter sales with no EFD receipt', 20, 0.0),
];

/// Close readiness, 0 to 100. Computed from the gates, never typed in.
double _closeScore() {
  final earned = _gates.fold<double>(0, (a, g) => a + g.weight * g.done);
  final total = _gates.fold<double>(0, (a, g) => a + g.weight);
  return total == 0 ? 0 : (earned / total) * 100;
}

class GovernanceScreen extends StatelessWidget {
  const GovernanceScreen({super.key});

  @override
  Widget build(BuildContext context) {
    final score = _closeScore();
    final scoreColour = score >= 80
        ? HqColors.good
        : score >= 60
            ? HqColors.warn
            : HqColors.bad;

    return Scaffold(
      backgroundColor: HqColors.bg,
      appBar: AppBar(
        title: const Text(
          "What happened that shouldn't have?",
          style: TextStyle(fontSize: 17, fontWeight: FontWeight.w700),
        ),
      ),
      body: ListView(
        padding: const EdgeInsets.fromLTRB(20, 8, 20, 40),
        children: [
          _heroStrip(),
          const SizedBox(height: 20),

          // 1. What kind of thing went wrong.
          HqCard(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                SectionLabel(text: 'BY KIND', trailing: 'value at stake'),
                const SizedBox(height: 14),
                RankedBars(
                  rows: [
                    for (final k in _kinds)
                      RankRow(
                        '${k.label}  (${k.count})',
                        k.value,
                        tzs(k.value),
                        status: k.status,
                      ),
                  ],
                ),
                const SizedBox(height: 12),
                const Text(
                  'Two kinds carry TZS 21.3M of the TZS 31.2M. Below-cost selling '
                  'and discounting past the ceiling are 69% of the exposure - the '
                  'other four kinds together are under a third.',
                  style: HqText.tiny,
                ),
              ],
            ),
          ),
          const SizedBox(height: 22),

          // 2. The three that need a person to answer for them.
          SectionLabel(text: '3 NEED AN EXPLANATION', trailing: 'TZS 14.5M'),
          const SizedBox(height: 10),
          ExceptionTile(
            title: 'Cement sold below cost, Kariakoo',
            detail:
                'Twiga 42.5N moved 6.2% under moving-average cost on a 400-bag '
                'order. No price approval sits on the line.',
            amount: tzs(7800000),
            owner: 'Neema Kessy - Kariakoo Branch Manager',
            actionLabel: 'Ask for a reason',
            severe: true,
            onAction: () => _ask(context, 'Neema Kessy'),
          ),
          const SizedBox(height: 10),
          ExceptionTile(
            title: 'Discount past policy, Mwanza',
            detail:
                '18% allowed to a credit customer already 46 days overdue. The '
                'standing ceiling for that tier is 10%.',
            amount: tzs(5100000),
            owner: 'Juma Mwakalinga - Regional Sales Head',
            actionLabel: 'Ask for a reason',
            onAction: () => _ask(context, 'Juma Mwakalinga'),
          ),
          const SizedBox(height: 10),
          ExceptionTile(
            title: 'Invoice voided after fiscalisation',
            detail:
                'A retail counter document was cancelled 41 minutes after the '
                'EFD receipt printed. The receipt is with the customer.',
            amount: tzs(1600000),
            owner: 'Asha Mrutu - POS Supervisor, Dar es Salaam',
            actionLabel: 'Ask for a reason',
            severe: true,
            onAction: () => _ask(context, 'Asha Mrutu'),
          ),
          const SizedBox(height: 22),

          // 3. How long decisions sit before somebody decides.
          HqCard(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                QuestionHeader(
                  question: 'How long do decisions sit?',
                  subtitle: 'Approvals waiting on a person, not on the system',
                ),
                const SizedBox(height: 16),
                // Ring + two metrics. The ring is a fixed-size box, so its
                // diameter and the gutter are derived from the panel width -
                // at 360 the old 104 + 20 left the metrics too little room.
                LayoutBuilder(
                  builder: (context, constraints) {
                    final narrow = constraints.maxWidth < 300;
                    return Row(
                      crossAxisAlignment: CrossAxisAlignment.center,
                      children: [
                        DonutMeter(
                          fraction: 0.62,
                          centre: '62%',
                          caption: 'decided within SLA',
                          color: HqColors.warn,
                          size: narrow ? 92.0 : 104.0,
                        ),
                        SizedBox(width: narrow ? 14.0 : 20.0),
                        const Expanded(
                          child: Column(
                            crossAxisAlignment: CrossAxisAlignment.start,
                            mainAxisSize: MainAxisSize.min,
                            children: [
                              _MetricRow(
                                label: 'Median time to decide',
                                value: '9h',
                                note: 'against an 8h promise - 7h in July',
                              ),
                              SizedBox(height: 14),
                              _MetricRow(
                                label: 'Oldest still waiting',
                                value: '3d',
                                note: 'a Dodoma purchase order, TZS 9.4M',
                              ),
                            ],
                          ),
                        ),
                      ],
                    );
                  },
                ),
                const SizedBox(height: 14),
                const Text(
                  'Four approvals in every ten miss the eight-hour promise. A slow '
                  'approval is where an override begins - people stop waiting and '
                  'act anyway.',
                  style: HqText.tiny,
                ),
              ],
            ),
          ),
          const SizedBox(height: 22),

          // 4. Can this month's numbers be believed at all.
          HqCard(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                QuestionHeader(
                  question: "Can I trust this month's numbers?",
                  subtitle: 'Close readiness, scored from four gates',
                ),
                const SizedBox(height: 16),
                Center(
                  child: DonutMeter(
                    fraction: score / 100,
                    centre: score.round().toString(),
                    caption: 'of 100 close-ready',
                    color: scoreColour,
                    size: 132,
                  ),
                ),
                const SizedBox(height: 8),
                Center(
                  child: Text(
                    'Two gates open. Last month closed at 94 by the 3rd.',
                    style: HqText.tiny,
                  ),
                ),
                const SizedBox(height: 18),
                for (final g in _gates) ...[
                  _GateRow(gate: g),
                  if (!identical(g, _gates.last))
                    const Divider(height: 18, thickness: 1, color: HqColors.line),
                ],
                const SizedBox(height: 16),
                Container(
                  padding: const EdgeInsets.all(12),
                  decoration: BoxDecoration(
                    color: HqColors.panel2,
                    borderRadius: BorderRadius.circular(HqRadii.sm),
                    border: Border.all(color: HqColors.line),
                  ),
                  child: Row(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: const [
                      Icon(Icons.info_outline, size: 16, color: HqColors.ink3),
                      SizedBox(width: 10),
                      Expanded(
                        child: Text(
                          'A month-end profit figure quoted while depreciation has '
                          'not run is worse than no figure at all. It reads high, '
                          'and everyone remembers the first number they were told.',
                          style: HqText.tiny,
                        ),
                      ),
                    ],
                  ),
                ),
              ],
            ),
          ),
          const SizedBox(height: 18),

          ResidualLine(
            text:
                'Residual: 2 overrides under TZS 500k are counted but not listed, '
                'TZS 0.7M in total. Materiality: an override is raised above '
                'TZS 500k, or above 5% off list price, whichever bites first.',
          ),
          const SizedBox(height: 16),

          DrillButton(
            label: 'See all $_overridesThisMonth overrides',
            onTap: () => _toast(
              context,
              'Override register - $_overridesThisMonth items over $_windowDays days',
            ),
          ),
        ],
      ),
    );
  }

  /// Compact dark hero. One number, six periods behind it, two comparisons.
  Widget _heroStrip() {
    return Container(
      padding: const EdgeInsets.fromLTRB(18, 16, 18, 14),
      decoration: BoxDecoration(
        gradient: HqSurfaces.heroGradient,
        borderRadius: BorderRadius.circular(18),
        boxShadow: HqSurfaces.hero,
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          // Chip left, money right. Both flexible: on a 360 phone the band
          // name and the figure would otherwise sum past the hero's width.
          Row(
            mainAxisAlignment: MainAxisAlignment.spaceBetween,
            children: [
              const Flexible(
                child: TrustChip(band: TrustBand.provisional, onDark: true),
              ),
              const SizedBox(width: 10),
              Flexible(
                child: Text(
                  '${tzs(_atStake)} at stake',
                  maxLines: 1,
                  overflow: TextOverflow.ellipsis,
                  textAlign: TextAlign.right,
                  style: const TextStyle(
                    fontSize: 12.5,
                    fontWeight: FontWeight.w700,
                    color: HqSurfaces.accent,
                    letterSpacing: 0.2,
                  ),
                ),
              ),
            ],
          ),
          const SizedBox(height: 12),
          const Text(
            '9 overrides this month, TZS 31M at stake.',
            style: TextStyle(
              fontSize: 17,
              fontWeight: FontWeight.w600,
              color: HqOnDark.primary,
              height: 1.3,
            ),
          ),
          const SizedBox(height: 14),
          // The figure and its six-month trend sit shoulder to shoulder on a
          // 412 phone. Below ~300 logical pixels of hero the caption alone is
          // wider than half the strip, so the trend drops underneath instead
          // of squeezing the number - same parts, same rhythm, no overflow.
          LayoutBuilder(
            builder: (context, constraints) {
              if (constraints.maxWidth < 300) {
                return const Column(
                  crossAxisAlignment: CrossAxisAlignment.stretch,
                  mainAxisSize: MainAxisSize.min,
                  children: [
                    Align(
                      alignment: Alignment.centerLeft,
                      child: _HeroFigure(),
                    ),
                    SizedBox(height: 14),
                    _HeroTrend(),
                  ],
                );
              }
              return const Row(
                crossAxisAlignment: CrossAxisAlignment.end,
                children: [
                  Flexible(flex: 6, child: _HeroFigure()),
                  SizedBox(width: 16),
                  Expanded(flex: 5, child: _HeroTrend()),
                ],
              );
            },
          ),
          const SizedBox(height: 14),
          const Divider(height: 1, thickness: 1, color: HqOnDark.hairline),
          const SizedBox(height: 12),
          ComparisonRow(
            label: 'Last month',
            value: '$_overridesLastMonth raised',
            delta: -25.0,
            higherIsBetter: false,
            onDark: true,
          ),
          const SizedBox(height: 8),
          ComparisonRow(
            label: 'Six-month average',
            value: '$_overridesSixMonthAvg a month',
            delta: -23.7,
            higherIsBetter: false,
            onDark: true,
          ),
          const SizedBox(height: 12),
          AsOfLine(asOf: _asOf, coverage: _coverage, onDark: true),
        ],
      ),
    );
  }

  static void _ask(BuildContext context, String person) =>
      _toast(context, 'Reason requested from $person - due back in 24 hours');

  static void _toast(BuildContext context, String message) {
    ScaffoldMessenger.of(context)
      ..hideCurrentSnackBar()
      ..showSnackBar(
        SnackBar(
          content: Text(message),
          backgroundColor: HqColors.ink,
          behavior: SnackBarBehavior.floating,
          duration: const Duration(seconds: 2),
        ),
      );
  }
}

/// The hero number and what it counts. Sized to whatever width it is handed,
/// so it can sit beside the trend on a 412 phone and above it on a 360 one.
class _HeroFigure extends StatelessWidget {
  const _HeroFigure();

  @override
  Widget build(BuildContext context) {
    return const Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      mainAxisSize: MainAxisSize.min,
      children: [
        FittedBox(
          fit: BoxFit.scaleDown,
          alignment: Alignment.centerLeft,
          child: Text(
            '$_overridesThisMonth',
            maxLines: 1,
            style: TextStyle(
              fontSize: 44,
              fontWeight: FontWeight.w700,
              color: HqOnDark.primary,
              height: 1.0,
              letterSpacing: -1.5,
            ),
          ),
        ),
        SizedBox(height: 4),
        Text(
          'policy exceptions, $_windowDays days',
          maxLines: 2,
          overflow: TextOverflow.ellipsis,
          style: TextStyle(
            fontSize: 12.5,
            height: 1.25,
            color: HqOnDark.secondary,
          ),
        ),
      ],
    );
  }
}

/// Six months behind the number - doctrine 5, history is always visible.
class _HeroTrend extends StatelessWidget {
  const _HeroTrend();

  @override
  Widget build(BuildContext context) {
    return const Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      mainAxisSize: MainAxisSize.min,
      children: [
        Sparkline(
          values: _history,
          color: VizColors.mint,
          height: 44,
          showLastDot: true,
          strokeWidth: 2.4,
        ),
        SizedBox(height: 5),
        Text(
          '$_historySpan - six months',
          maxLines: 1,
          overflow: TextOverflow.ellipsis,
          textAlign: TextAlign.right,
          style: TextStyle(fontSize: 11, color: HqOnDark.tertiary),
        ),
      ],
    );
  }
}

/// A labelled figure with its comparison underneath - no number travels alone.
class _MetricRow extends StatelessWidget {
  const _MetricRow({
    required this.label,
    required this.value,
    required this.note,
  });

  final String label;
  final String value;
  final String note;

  @override
  Widget build(BuildContext context) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      mainAxisSize: MainAxisSize.min,
      children: [
        Text(label, maxLines: 2, overflow: TextOverflow.ellipsis, style: HqText.label),
        const SizedBox(height: 3),
        FittedBox(
          fit: BoxFit.scaleDown,
          alignment: Alignment.centerLeft,
          child: Text(
            value,
            maxLines: 1,
            style: HqText.tileValue.copyWith(fontSize: 26, color: HqColors.warn),
          ),
        ),
        const SizedBox(height: 2),
        Text(note, maxLines: 3, overflow: TextOverflow.ellipsis, style: HqText.tiny),
      ],
    );
  }
}

/// One close gate: a tick or a cross, the reason, and what it is worth.
class _GateRow extends StatelessWidget {
  const _GateRow({required this.gate});

  final _Gate gate;

  @override
  Widget build(BuildContext context) {
    final passed = gate.pass;
    final partial = !passed && gate.done > 0;
    final colour =
        passed ? HqColors.good : (partial ? HqColors.warn : HqColors.bad);
    final icon = passed
        ? Icons.check_circle
        : (partial ? Icons.error_outline : Icons.cancel);

    return Row(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Icon(icon, size: 19, color: colour),
        const SizedBox(width: 11),
        Expanded(
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            mainAxisSize: MainAxisSize.min,
            children: [
              Text(
                gate.label,
                maxLines: 2,
                overflow: TextOverflow.ellipsis,
                style: HqText.verdict.copyWith(fontSize: 14.5),
              ),
              const SizedBox(height: 2),
              Text(
                gate.detail,
                maxLines: 3,
                overflow: TextOverflow.ellipsis,
                style: HqText.tiny,
              ),
            ],
          ),
        ),
        const SizedBox(width: 10),
        // The weight is the only unbounded child left on this row - cap it so
        // a wide score can never push the gate name off the panel.
        ConstrainedBox(
          constraints: const BoxConstraints(maxWidth: 86),
          child: Text(
            '${(gate.weight * gate.done).round()} / ${gate.weight.round()}',
            maxLines: 1,
            overflow: TextOverflow.ellipsis,
            textAlign: TextAlign.right,
            style:
                HqText.tiny.copyWith(fontWeight: FontWeight.w700, color: colour),
          ),
        ),
      ],
    );
  }
}
