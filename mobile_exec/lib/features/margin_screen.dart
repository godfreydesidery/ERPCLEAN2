import 'package:flutter/material.dart';

import '../app/format.dart';
import '../app/theme.dart';
import '../data/mock.dart' as mock;
import '../widgets/charts.dart';
import '../widgets/common.dart' hide Sparkline;

/// "Where the Margin Went" — the margin variance bridge.
///
/// The owner's question is not "what is the margin" but "who took it".
/// The screen answers in that order: the verdict in words, the bridge that
/// names each step, the four-sentence P&L, the cash reality behind the profit,
/// and finally which branch is carrying the fall.
///
/// The bridge is expressed in money rather than points: each percentage point
/// of the mock margin bridge is valued at this month's sales, so the opening
/// anchor is the gross profit July's rate would have earned on August's sales,
/// and the closing anchor is the gross profit that actually landed.

// The plain-language P&L. It ties out: 1,243 - 977 = 266 gross, which is the
// same 21.4% as the hero; 266 - 118 = 148 kept, which is 11.9% of what we sold.
const num _pnlSales = 1243000000;
const num _pnlCost = 977000000;
const num _pnlOverheads = 118000000;
const num _pnlKept = 148000000;
const num _pnlKeptPct = 11.9;

// The cash reality. 148 earned, 226 absorbed, so the bank is 78 lighter.
const num _cashStock = 96000000;
const num _cashDebtors = 61000000;
const num _cashSuppliers = 38000000;
const num _cashTax = 31000000;
const num _cashAbsorbed =
    _cashStock + _cashDebtors + _cashSuppliers + _cashTax; // 226M
const num _cashMovement = _pnlKept - _cashAbsorbed; // -78M

class MarginScreen extends StatelessWidget {
  const MarginScreen({super.key});

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: HqColors.bg,
      appBar: AppBar(
        title: const Text(
          'Why is the margin down?',
          style: TextStyle(fontSize: 18, fontWeight: FontWeight.w700),
        ),
      ),
      body: SafeArea(
        top: false,
        child: ListView(
          padding: const EdgeInsets.fromLTRB(16, 10, 16, 30),
          children: [
            const _MarginHero(),
            const SizedBox(height: 14),
            const _BridgeCard(),
            const SizedBox(height: 14),
            const _FourSentencesCard(),
            const SizedBox(height: 14),
            const _CashRealityCard(),
            const SizedBox(height: 14),
            const _BranchMarginCard(),
            const SizedBox(height: 16),
            DrillButton(
              label: 'Which products lost margin?',
              onTap: () {},
            ),
          ],
        ),
      ),
    );
  }
}

// ---------------------------------------------------------------------------
// 1. Hero — one number, three comparisons, twelve months of history
// ---------------------------------------------------------------------------

class _MarginHero extends StatelessWidget {
  const _MarginHero();

  @override
  Widget build(BuildContext context) {
    final history = <double>[
      for (final num m in mock.kMarginHistoryPct) m.toDouble(),
    ];

    return HeroCard(
      question: 'Did we make money, and where did it go?',
      verdict:
          'Margin fell 1.5pp — discounting took most of it, freight the rest.',
      heroValue: pct(mock.kGrossMarginPct),
      heroSub:
          'gross margin, month to date — ${tzs(mock.kGrossProfitMtd)} of gross profit on ${tzs(mock.kSalesMtd)} sold',
      accented: true,
      comparisons: [
        _PpRow(
          label: 'Last month',
          value: pct(mock.kGrossMarginPctLastMonth),
          movePp:
              (mock.kGrossMarginPct - mock.kGrossMarginPctLastMonth).toDouble(),
        ),
        _PpRow(
          label: 'Plan',
          value: pct(mock.kGrossMarginPlanPct),
          movePp: (mock.kGrossMarginPct - mock.kGrossMarginPlanPct).toDouble(),
        ),
        _PpRow(
          label: 'Same month last year',
          value: pct(mock.kGrossMarginPctLastYear),
          movePp:
              (mock.kGrossMarginPct - mock.kGrossMarginPctLastYear).toDouble(),
        ),
      ],
      chart: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        mainAxisSize: MainAxisSize.min,
        children: [
          Sparkline(values: history, color: VizColors.mint, height: 46),
          const SizedBox(height: 8),
          // The two endpoints anchor the ends of the line; the caption sits on
          // its own band underneath. It used to ride between them carrying no
          // flex of its own, which is what overflowed the hero on a 360pt phone.
          const Row(
            children: [
              Expanded(
                child: Text(
                  'Sep 23.9%',
                  maxLines: 1,
                  overflow: TextOverflow.ellipsis,
                  style: TextStyle(fontSize: 11, color: HqOnDark.tertiary),
                ),
              ),
              SizedBox(width: 10),
              Expanded(
                child: Text(
                  'Aug 21.4%',
                  maxLines: 1,
                  overflow: TextOverflow.ellipsis,
                  textAlign: TextAlign.right,
                  style: TextStyle(
                    fontSize: 11,
                    fontWeight: FontWeight.w700,
                    color: HqOnDark.secondary,
                  ),
                ),
              ),
            ],
          ),
          const SizedBox(height: 5),
          const FittedBox(
            fit: BoxFit.scaleDown,
            child: Text(
              'twelve months of company margin',
              maxLines: 1,
              textAlign: TextAlign.center,
              style: TextStyle(fontSize: 11, color: HqOnDark.tertiary),
            ),
          ),
        ],
      ),
      asOf: '${mock.kAsOf} · 8 of 8 branches · month to date',
      band: TrustBand.provisional,
      trustLabel: mock.kTrustBadge,
    );
  }
}

/// A hero comparison in percentage points — never "%" for a change in a
/// percentage, and the pill turns red only when the movement is bad for us.
class _PpRow extends StatelessWidget {
  const _PpRow({
    required this.label,
    required this.value,
    required this.movePp,
  });

  final String label;
  final String value;
  final double movePp;

  @override
  Widget build(BuildContext context) {
    final good = movePp >= 0;
    final base = good ? HqColors.good : HqColors.bad;
    final ink = Color.lerp(base, Colors.white, 0.52)!;

    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 5),
      child: Row(
        children: [
          Expanded(
            child: Text(
              label,
              maxLines: 1,
              overflow: TextOverflow.ellipsis,
              style: const TextStyle(fontSize: 13, color: HqOnDark.tertiary),
            ),
          ),
          const SizedBox(width: 12),
          // The value keeps its own width, but never more than this - a long
          // figure clips rather than shoving the pill off the card.
          ConstrainedBox(
            constraints: const BoxConstraints(maxWidth: 96),
            child: Text(
              value,
              maxLines: 1,
              overflow: TextOverflow.ellipsis,
              textAlign: TextAlign.right,
              style: const TextStyle(
                fontSize: 14,
                fontWeight: FontWeight.w700,
                color: HqOnDark.primary,
              ),
            ),
          ),
          const SizedBox(width: 8),
          Container(
            padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 3),
            decoration: BoxDecoration(
              color: Colors.white.withValues(alpha: 0.09),
              borderRadius: BorderRadius.circular(999),
              border: Border.all(color: HqOnDark.hairline),
            ),
            child: Text(
              pp(movePp),
              maxLines: 1,
              overflow: TextOverflow.ellipsis,
              style: TextStyle(
                fontSize: 11.5,
                fontWeight: FontWeight.w700,
                color: ink,
              ),
            ),
          ),
        ],
      ),
    );
  }
}

// ---------------------------------------------------------------------------
// 2. The bridge — what moved the margin
// ---------------------------------------------------------------------------

class _BridgeCard extends StatelessWidget {
  const _BridgeCard();

  static String _shortLabel(String label) => switch (label) {
        'Opening margin' => 'July rate',
        'Landed cost' => 'Freight',
        'Closing margin' => 'Actual',
        _ => label,
      };

  /// Each point of margin valued at this month's sales, so the bridge reads in
  /// money: 22.9% of 412M would have earned 94.3M, and 88.2M actually landed.
  static List<BridgeStep> _steps() {
    final sales = mock.kSalesMtd.toDouble();
    final out = <BridgeStep>[];
    for (var i = 0; i < mock.kMarginBridge.length; i++) {
      final s = mock.kMarginBridge[i];
      final label = _shortLabel(s.label);
      if (s.anchor) {
        // The opening anchor is set explicitly; the closing anchor takes the
        // running total, which is what proves the bridge reconciles.
        final opening = i == 0 ? sales * s.runningPct / 100 : 0.0;
        out.add(BridgeStep(label, opening, isTotal: true));
      } else {
        out.add(BridgeStep(label, sales * s.deltaPp / 100));
      }
    }
    return out;
  }

  @override
  Widget build(BuildContext context) {
    return HqCard(
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        mainAxisSize: MainAxisSize.min,
        children: [
          const SectionLabel(
            text: 'What moved the margin',
            trailing: 'July rate to today',
          ),
          Text(
            "Gross profit August would have earned at July's margin, against "
            'what actually landed. Each bar is one cause, in money.',
            style: HqText.tiny.copyWith(color: HqColors.ink2, height: 1.45),
          ),
          const SizedBox(height: 16),
          WaterfallBridge(steps: _steps(), height: 200),
          const SizedBox(height: 14),
          const Divider(height: 1, thickness: 1, color: HqColors.line),
          const SizedBox(height: 12),
          Text(
            'Price is the big step. Counter discounting at Arusha and Kariakoo '
            'gave away ${tzs(mock.kSalesMtd * 0.009)} of gross profit — 0.9 of '
            'the 1.5 points. Clearing and transport on the Dar consignments '
            'took another 0.5, and the shift toward project work 0.4.',
            style: HqText.tiny.copyWith(color: HqColors.ink2, height: 1.5),
          ),
        ],
      ),
    );
  }
}

// ---------------------------------------------------------------------------
// 3. The four sentences — the P&L an owner can read in ten seconds
// ---------------------------------------------------------------------------

class _FourSentencesCard extends StatelessWidget {
  const _FourSentencesCard();

  @override
  Widget build(BuildContext context) {
    return HqCard(
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        mainAxisSize: MainAxisSize.min,
        children: [
          const SectionLabel(
            text: 'The four sentences',
            trailing: 'Company-wide, last two months',
          ),
          _Sentence(lead: 'We sold ', money: tzs(_pnlSales), tail: '.'),
          _Sentence(
            lead: 'It cost us ',
            money: tzs(_pnlCost),
            tail: ' to buy and make.',
          ),
          _Sentence(
            lead: 'We spent ',
            money: tzs(_pnlOverheads),
            tail: ' running the place.',
          ),
          _Sentence(
            lead: 'We kept ',
            money: tzs(_pnlKept),
            tail: ' — ${pct(_pnlKeptPct)} of everything we sold.',
            emphasised: true,
          ),
        ],
      ),
    );
  }
}

class _Sentence extends StatelessWidget {
  const _Sentence({
    required this.lead,
    required this.money,
    required this.tail,
    this.emphasised = false,
  });

  final String lead;
  final String money;
  final String tail;

  /// The last line is the one that matters — hairline above it, heavier ink.
  final bool emphasised;

  @override
  Widget build(BuildContext context) {
    final base = TextStyle(
      fontSize: emphasised ? 16 : 15,
      height: 1.45,
      fontWeight: emphasised ? FontWeight.w700 : FontWeight.w400,
      color: emphasised ? HqColors.ink : HqColors.ink2,
    );

    return Container(
      padding: EdgeInsets.only(top: emphasised ? 13 : 7, bottom: 7),
      decoration: emphasised
          ? const BoxDecoration(
              border: Border(top: BorderSide(color: HqColors.line2)),
            )
          : null,
      child: RichText(
        text: TextSpan(
          style: base,
          children: [
            TextSpan(text: lead),
            TextSpan(
              text: money,
              style: base.copyWith(
                fontWeight: FontWeight.w700,
                color: HqColors.ink,
                letterSpacing: -0.2,
              ),
            ),
            TextSpan(text: tail),
          ],
        ),
      ),
    );
  }
}

// ---------------------------------------------------------------------------
// 4. Cash reality — profit is not cash, and the owner banks cash
// ---------------------------------------------------------------------------

class _CashRealityCard extends StatelessWidget {
  const _CashRealityCard();

  @override
  Widget build(BuildContext context) {
    return HqCard(
      padding: EdgeInsets.zero,
      child: Container(
        padding: const EdgeInsets.all(16),
        decoration: BoxDecoration(
          color: HqColors.warnSoft,
          borderRadius: BorderRadius.circular(16),
          border: Border.all(color: HqColors.warn.withValues(alpha: 0.22)),
        ),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          mainAxisSize: MainAxisSize.min,
          children: [
            Row(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Container(
                  width: 30,
                  height: 30,
                  alignment: Alignment.center,
                  decoration: BoxDecoration(
                    color: HqColors.warn.withValues(alpha: 0.12),
                    shape: BoxShape.circle,
                    border: Border.all(
                      color: HqColors.warn.withValues(alpha: 0.28),
                    ),
                  ),
                  child: const Icon(
                    Icons.trending_down_rounded,
                    size: 17,
                    color: HqColors.warn,
                  ),
                ),
                const SizedBox(width: 11),
                Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    mainAxisSize: MainAxisSize.min,
                    children: [
                      Text(
                        'Profit ${tzs(_pnlKept)}, but cash ${tzs(_cashMovement)}',
                        maxLines: 3,
                        overflow: TextOverflow.ellipsis,
                        style: const TextStyle(
                          fontSize: 16,
                          fontWeight: FontWeight.w700,
                          height: 1.3,
                          color: HqColors.ink,
                        ),
                      ),
                      const SizedBox(height: 3),
                      Text(
                        'Profit is not cash. ${tzs(_cashAbsorbed)} of it never reached the bank.',
                        maxLines: 3,
                        overflow: TextOverflow.ellipsis,
                        style: HqText.tiny.copyWith(
                          color: HqColors.ink2,
                          height: 1.4,
                        ),
                      ),
                    ],
                  ),
                ),
              ],
            ),
            const SizedBox(height: 14),
            const _CashUse(
              label: 'Stock went up',
              note: 'more cement and rebar standing on the floor',
              amount: _cashStock,
            ),
            const _CashUse(
              label: 'Customers owe us more',
              note: 'credit sales grew faster than collections',
              amount: _cashDebtors,
            ),
            const _CashUse(
              label: 'Suppliers paid down',
              note: 'the weekly payment run cleared older bills',
              amount: _cashSuppliers,
            ),
            const _CashUse(
              label: 'Tax paid',
              note: 'VAT and provisional income tax',
              amount: _cashTax,
            ),
            const SizedBox(height: 12),
            Container(
              padding: const EdgeInsets.only(top: 11),
              decoration: BoxDecoration(
                border: Border(
                  top: BorderSide(color: HqColors.warn.withValues(alpha: 0.26)),
                ),
              ),
              child: Row(
                children: [
                  const Expanded(
                    child: Text(
                      'What the bank actually did',
                      maxLines: 2,
                      overflow: TextOverflow.ellipsis,
                      style: TextStyle(
                        fontSize: 13.5,
                        fontWeight: FontWeight.w700,
                        color: HqColors.ink,
                      ),
                    ),
                  ),
                  ConstrainedBox(
                    constraints: const BoxConstraints(maxWidth: 150),
                    child: Text(
                      tzs(_cashMovement),
                      maxLines: 1,
                      overflow: TextOverflow.ellipsis,
                      textAlign: TextAlign.right,
                      style: const TextStyle(
                        fontSize: 15,
                        fontWeight: FontWeight.w700,
                        letterSpacing: -0.2,
                        color: HqColors.bad,
                      ),
                    ),
                  ),
                ],
              ),
            ),
          ],
        ),
      ),
    );
  }
}

class _CashUse extends StatelessWidget {
  const _CashUse({
    required this.label,
    required this.note,
    required this.amount,
  });

  final String label;
  final String note;
  final num amount;

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 6),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Container(
            width: 7,
            height: 7,
            margin: const EdgeInsets.only(top: 5),
            decoration: const BoxDecoration(
              color: HqColors.warn,
              shape: BoxShape.circle,
            ),
          ),
          const SizedBox(width: 10),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              mainAxisSize: MainAxisSize.min,
              children: [
                Text(
                  label,
                  maxLines: 1,
                  overflow: TextOverflow.ellipsis,
                  style: const TextStyle(
                    fontSize: 13.5,
                    fontWeight: FontWeight.w600,
                    color: HqColors.ink,
                  ),
                ),
                const SizedBox(height: 2),
                Text(
                  note,
                  maxLines: 2,
                  overflow: TextOverflow.ellipsis,
                  style: HqText.tiny.copyWith(color: HqColors.ink2),
                ),
              ],
            ),
          ),
          const SizedBox(width: 12),
          ConstrainedBox(
            constraints: const BoxConstraints(maxWidth: 132),
            child: Text(
              '−${tzs(amount)}',
              maxLines: 1,
              overflow: TextOverflow.ellipsis,
              textAlign: TextAlign.right,
              style: const TextStyle(
                fontSize: 13.5,
                fontWeight: FontWeight.w700,
                color: HqColors.warn,
              ),
            ),
          ),
        ],
      ),
    );
  }
}

// ---------------------------------------------------------------------------
// 5. Margin by branch — who is carrying the fall
// ---------------------------------------------------------------------------

class _BranchMarginCard extends StatelessWidget {
  const _BranchMarginCard();

  static List<RankRow> _rows() {
    final branches = [...mock.kBranches]
      ..sort((a, b) => b.marginPct.compareTo(a.marginPct));
    final top = branches.first.marginPct;

    return [
      for (final b in branches)
        RankRow(
          b.name,
          b.marginPct.toDouble(),
          '${pct(b.marginPct)}   ${pp(b.marginPct - b.marginLastMonthPct)}',
          status: _statusFor(b, top),
        ),
    ];
  }

  static RankStatus _statusFor(mock.BranchLine b, num top) {
    final drop = b.marginLastMonthPct - b.marginPct;
    if (drop >= 1.5) return RankStatus.bad;
    if (b.marginPct == top) return RankStatus.best;
    if (drop < 0) return RankStatus.good;
    return RankStatus.normal;
  }

  @override
  Widget build(BuildContext context) {
    return HqCard(
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        mainAxisSize: MainAxisSize.min,
        children: [
          SectionLabel(
            text: 'Margin by branch',
            trailing:
                'All 8 · plan ${pct(mock.kGrossMarginPlanPct)}',
          ),
          const SizedBox(height: 4),
          RankedBars(rows: _rows()),
          const SizedBox(height: 6),
          const ResidualLine(
            text:
                'Arusha and Kariakoo together explain 0.8pp of the 1.5pp lost. '
                'The other six branches each moved less than a point.',
          ),
        ],
      ),
    );
  }
}
