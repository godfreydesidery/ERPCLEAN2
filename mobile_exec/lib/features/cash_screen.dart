import 'package:flutter/material.dart';

import '../app/format.dart';
import '../app/theme.dart';
import '../data/mock.dart' hide AgeBucket, BridgeStep;
import '../widgets/charts.dart';
import '../widgets/common.dart' hide Sparkline;

/// "How long the cash lasts" - the position and the 13-week forecast.
///
/// The owner's real question is never "what is the balance"; it is "do I go
/// under, and when". So the screen leads with runway in weeks, shows where
/// every shilling is sitting, runs the balance forward against the board
/// floor, and then proves the number is real by reconciling it to the bank.
class CashScreen extends StatelessWidget {
  const CashScreen({super.key});

  /// Eight weeks of banked closing balances, TZS millions. The last five are
  /// W29-W33 from the dataset; the three before them extend the same series so
  /// that a slow slide cannot hide behind a short chart.
  static const List<double> _cashHistoryM = <double>[
    142.8,
    138.1,
    134.5,
    131.2,
    124.6,
    118.3,
    108.9,
    96.4,
  ];

  /// Money the ledger says is collectable inside the next seven days.
  static const num _dueIn7Days = 184000000;

  /// The VAT return already accrued for the period.
  static const num _vatDue = 61200000;

  @override
  Widget build(BuildContext context) {
    final banked = <double>[
      for (final w in kCash13Weeks)
        if (w.actual) w.balance / 1000000,
    ];
    final forecast = <double>[
      for (final w in kCash13Weeks)
        if (!w.actual) w.balance / 1000000,
    ];

    return Scaffold(
      backgroundColor: HqColors.bg,
      appBar: AppBar(
        title: const Text(
          'Will I run out of cash?',
          style: TextStyle(fontSize: 17, fontWeight: FontWeight.w700),
        ),
      ),
      body: ListView(
        padding: const EdgeInsets.fromLTRB(20, 8, 20, 32),
        children: [
          _hero(),
          const SizedBox(height: 18),
          _whereCard(),
          const SizedBox(height: 14),
          _forecastCard(banked, forecast),
          const SizedBox(height: 14),
          _reconCard(),
          const SizedBox(height: 14),
          _tiles(),
          const SizedBox(height: 6),
          const ResidualLine(
            text:
                'Counted across 4 bank accounts, 8 tills, 2 safes and 3 mobile wallets. '
                'Balances under TZS 1M are folded into their pot - together they move the '
                'total by less than 0.4%.',
          ),
          const SizedBox(height: 14),
          DrillButton(
            label: 'See what is coming in',
            onTap: () => _toast(
              context,
              'Expected receipts, next 7 days - TZS 184M across 96 accounts',
            ),
          ),
        ],
      ),
    );
  }

  // ------------------------------------------------------------------ hero

  Widget _hero() {
    return const HeroCard(
      question: 'HOW LONG THE CASH LASTS',
      verdict: '5.4 weeks of runway at the current burn.',
      heroValue: 'TZS 96.4M',
      heroSub: 'across bank, till, safe and mobile money',
      accented: true,
      comparisons: <Widget>[
        ComparisonRow(
          label: 'Cash a week ago',
          value: 'TZS 109M',
          delta: -11.5,
          onDark: true,
        ),
        ComparisonRow(
          label: 'Same week last year',
          value: 'TZS 84.3M',
          delta: 14.4,
          onDark: true,
        ),
        ComparisonRow(
          label: 'Runway a month ago',
          value: '7.1 weeks',
          delta: -23.9,
          onDark: true,
        ),
      ],
      chart: Sparkline(values: _cashHistoryM, height: 54),
      // The chip sits un-flexed beside the as-of stamp in HeroCard, so its copy
      // has to stay short - the "bank posted" half of the stamp lives in the
      // as-of line instead, where it is free to wrap and ellipsize.
      asOf: '$kAsOf . $kCoverage . bank posted, 8 weeks banked',
      band: TrustBand.provisional,
      trustLabel: 'TILL PROVISIONAL',
    );
  }

  // ------------------------------------------------------- where the cash is

  Widget _whereCard() {
    final total = kCashPots.fold<num>(0, (a, p) => a + p.amount);

    return HqCard(
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          const _CardHead(
            title: 'Where the cash is',
            note: 'Every shilling the company can reach today',
          ),
          const SizedBox(height: 14),
          ShareBar(
            parts: <SharePart>[
              for (final p in kCashPots) SharePart(p.label, p.amount.toDouble()),
            ],
          ),
          const SizedBox(height: 16),
          const Divider(height: 1, thickness: 1, color: HqColors.line),
          const SizedBox(height: 6),
          for (var i = 0; i < kCashPots.length; i++)
            _PotRow(pot: kCashPots[i], tint: VizColors.cat(i)),
          const SizedBox(height: 4),
          const Divider(height: 1, thickness: 1, color: HqColors.line),
          const SizedBox(height: 12),
          Row(
            children: [
              const Expanded(
                child: Text(
                  'Total reachable today',
                  style: TextStyle(
                    fontSize: 13,
                    fontWeight: FontWeight.w700,
                    color: HqColors.ink,
                  ),
                ),
              ),
              const SizedBox(width: 12),
              Text(
                tzsExact(total),
                style: const TextStyle(
                  fontSize: 15,
                  fontWeight: FontWeight.w700,
                  letterSpacing: -0.3,
                  color: HqColors.ink,
                ),
              ),
            ],
          ),
          const SizedBox(height: 7),
          Text(
            'Till and mobile money are same-day. The safes clear on the next banking run.',
            style: HqText.tiny.copyWith(height: 1.4),
          ),
        ],
      ),
    );
  }

  // --------------------------------------------------------------- forecast

  Widget _forecastCard(List<double> banked, List<double> forecast) {
    return HqCard(
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          const _CardHead(
            title: 'The next 13 weeks',
            note: 'Weekly closing balance against your TZS 40M floor',
          ),
          const SizedBox(height: 14),
          ForecastLine(
            actual: banked,
            forecast: forecast,
            floor: kCashFloor / 1000000,
            height: 178,
          ),
          const SizedBox(height: 2),
          const Row(
            children: [
              Expanded(
                child: Text(
                  '14 Jul',
                  style: HqText.tiny,
                  textAlign: TextAlign.left,
                ),
              ),
              Expanded(
                child: Text(
                  '25 Aug',
                  style: HqText.tiny,
                  textAlign: TextAlign.center,
                ),
              ),
              Expanded(
                child: Text(
                  '6 Oct',
                  style: HqText.tiny,
                  textAlign: TextAlign.right,
                ),
              ),
            ],
          ),
          const SizedBox(height: 14),
          const Wrap(
            spacing: 16,
            runSpacing: 8,
            children: <Widget>[
              _Key(color: HqColors.brand, label: 'banked to 18 Aug', dashed: false),
              _Key(color: HqColors.brand, label: 'forecast, 8 weeks', dashed: true),
              _Key(color: HqColors.bad, label: 'floor TZS 40M', dashed: true),
            ],
          ),
          const SizedBox(height: 13),
          Text(
            'assumes collections continue at the 90-day average of 71%',
            style: HqText.tiny.copyWith(
              fontStyle: FontStyle.italic,
              color: HqColors.ink3,
              height: 1.4,
            ),
          ),
          const SizedBox(height: 12),
          Container(
            padding: const EdgeInsets.fromLTRB(12, 11, 12, 11),
            decoration: BoxDecoration(
              color: HqColors.warnSoft,
              borderRadius: BorderRadius.circular(HqRadii.sm),
              border: Border.all(color: HqColors.warn.withValues(alpha: 0.22)),
            ),
            child: const Row(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Icon(Icons.warning_amber_rounded, size: 18, color: HqColors.warn),
                SizedBox(width: 9),
                Expanded(
                  child: Text(
                    'Crosses your 40M floor on 26 Aug unless Mo Hardware pays.',
                    style: TextStyle(
                      fontSize: 13,
                      fontWeight: FontWeight.w600,
                      height: 1.35,
                      color: HqColors.warn,
                    ),
                  ),
                ),
              ],
            ),
          ),
        ],
      ),
    );
  }

  // --------------------------------------------------------- reconciliation

  Widget _reconCard() {
    final r = kBankRecon;
    final reconciled =
        kBankReconLines.fold<num>(0, (a, l) => a + l.systemBalance);

    return HqCard(
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          const _CardHead(
            title: 'Does the cash match?',
            note: 'CRDB - main collection account',
          ),
          const SizedBox(height: 16),
          _ReconRow(
            label: 'System',
            caption: 'What the ledger says you hold',
            value: tzs(r.systemBalance),
          ),
          const SizedBox(height: 12),
          const Divider(height: 1, thickness: 1, color: HqColors.line),
          const SizedBox(height: 12),
          _ReconRow(
            label: 'Bank statement',
            caption: 'What the bank fed back this morning',
            value: tzs(r.statementBalance),
          ),
          const SizedBox(height: 16),
          Container(
            padding: const EdgeInsets.fromLTRB(13, 12, 13, 12),
            decoration: BoxDecoration(
              color: HqColors.goodSoft,
              borderRadius: BorderRadius.circular(HqRadii.sm),
              border: Border.all(color: HqColors.good.withValues(alpha: 0.28)),
            ),
            child: Row(
              children: [
                Container(
                  width: 26,
                  height: 26,
                  alignment: Alignment.center,
                  decoration: const BoxDecoration(
                    color: HqColors.good,
                    shape: BoxShape.circle,
                  ),
                  child: const Icon(
                    Icons.check_rounded,
                    size: 17,
                    color: Colors.white,
                  ),
                ),
                const SizedBox(width: 11),
                Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    mainAxisSize: MainAxisSize.min,
                    children: [
                      const Text(
                        'Matched - nothing unexplained',
                        style: TextStyle(
                          fontSize: 14,
                          fontWeight: FontWeight.w700,
                          height: 1.25,
                          color: HqColors.good,
                        ),
                      ),
                      const SizedBox(height: 2),
                      Text(
                        '${r.unmatchedItems} unmatched items, last reconciled ${r.lastMatched}',
                        maxLines: 2,
                        overflow: TextOverflow.ellipsis,
                        style: HqText.tiny.copyWith(height: 1.35),
                      ),
                    ],
                  ),
                ),
              ],
            ),
          ),
          const SizedBox(height: 12),
          Text(
            '${kBankReconLines.length} of ${kBankReconLines.length} bank accounts '
            'reconciled, ${tzs(reconciled)} in total. A balance nobody has matched '
            'is a balance you do not have.',
            style: HqText.tiny.copyWith(height: 1.45),
          ),
        ],
      ),
    );
  }

  // ------------------------------------------------------------------ tiles

  Widget _tiles() {
    return IntrinsicHeight(
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: <Widget>[
          Expanded(
            child: StatTile(
              label: 'Due in 7 d',
              value: tzs(_dueIn7Days),
              delta: '71% usually lands',
            ),
          ),
          const SizedBox(width: 10),
          Expanded(
            child: StatTile(
              label: 'Payable 7 d',
              value: tzs(kCreditorsDueThisWeek),
              delta: '${tzs(kCreditorsOverdue)} already late',
              deltaGood: false,
            ),
          ),
          const SizedBox(width: 10),
          Expanded(
            child: StatTile(
              label: 'VAT to TRA',
              value: tzs(_vatDue),
              delta: 'due 20 Sept',
            ),
          ),
        ],
      ),
    );
  }

  static void _toast(BuildContext context, String message) {
    ScaffoldMessenger.of(context)
      ..hideCurrentSnackBar()
      ..showSnackBar(
        SnackBar(
          content: Text(message),
          behavior: SnackBarBehavior.floating,
          backgroundColor: HqColors.ink,
        ),
      );
  }
}

// ---------------------------------------------------------------------------
// small parts
// ---------------------------------------------------------------------------

class _CardHead extends StatelessWidget {
  const _CardHead({required this.title, required this.note});

  final String title;
  final String note;

  @override
  Widget build(BuildContext context) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      mainAxisSize: MainAxisSize.min,
      children: [
        Text(
          title,
          maxLines: 2,
          overflow: TextOverflow.ellipsis,
          style: const TextStyle(
            fontSize: 16,
            fontWeight: FontWeight.w700,
            height: 1.25,
            color: HqColors.ink,
          ),
        ),
        const SizedBox(height: 3),
        Text(
          note,
          maxLines: 2,
          overflow: TextOverflow.ellipsis,
          style: HqText.tiny.copyWith(height: 1.35),
        ),
      ],
    );
  }
}

/// One cash location: colour key, name, where it sits, exact amount.
class _PotRow extends StatelessWidget {
  const _PotRow({required this.pot, required this.tint});

  final CashPot pot;
  final Color tint;

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 7),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.center,
        children: [
          Container(
            width: 9,
            height: 9,
            decoration: BoxDecoration(
              color: tint,
              borderRadius: BorderRadius.circular(2.5),
            ),
          ),
          const SizedBox(width: 10),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              mainAxisSize: MainAxisSize.min,
              children: [
                Text(
                  pot.label,
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
                  pot.detail,
                  maxLines: 1,
                  overflow: TextOverflow.ellipsis,
                  style: HqText.tiny,
                ),
              ],
            ),
          ),
          const SizedBox(width: 12),
          Text(
            tzsExact(pot.amount),
            maxLines: 1,
            style: const TextStyle(
              fontSize: 13.5,
              fontWeight: FontWeight.w700,
              letterSpacing: -0.2,
              color: HqColors.ink,
            ),
          ),
        ],
      ),
    );
  }
}

/// One side of the reconciliation: what it is, where it came from, how much.
class _ReconRow extends StatelessWidget {
  const _ReconRow({
    required this.label,
    required this.caption,
    required this.value,
  });

  final String label;
  final String caption;
  final String value;

  @override
  Widget build(BuildContext context) {
    return Row(
      crossAxisAlignment: CrossAxisAlignment.center,
      children: [
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
                  fontSize: 14,
                  fontWeight: FontWeight.w600,
                  color: HqColors.ink,
                ),
              ),
              const SizedBox(height: 2),
              Text(
                caption,
                maxLines: 2,
                overflow: TextOverflow.ellipsis,
                style: HqText.tiny.copyWith(height: 1.35),
              ),
            ],
          ),
        ),
        const SizedBox(width: 12),
        Text(
          value,
          maxLines: 1,
          style: const TextStyle(
            fontSize: 20,
            fontWeight: FontWeight.w700,
            letterSpacing: -0.5,
            color: HqColors.ink,
          ),
        ),
      ],
    );
  }
}

/// A legend key: a short rule, solid or dashed, plus what it means.
class _Key extends StatelessWidget {
  const _Key({required this.color, required this.label, required this.dashed});

  final Color color;
  final String label;
  final bool dashed;

  @override
  Widget build(BuildContext context) {
    return Row(
      mainAxisSize: MainAxisSize.min,
      children: [
        SizedBox(
          width: 18,
          height: 3,
          child: dashed
              ? Row(
                  children: <Widget>[
                    for (var i = 0; i < 3; i++) ...[
                      if (i > 0) const SizedBox(width: 3),
                      Expanded(
                        child: DecoratedBox(
                          decoration: BoxDecoration(
                            color: color,
                            borderRadius: BorderRadius.circular(2),
                          ),
                        ),
                      ),
                    ],
                  ],
                )
              : DecoratedBox(
                  decoration: BoxDecoration(
                    color: color,
                    borderRadius: BorderRadius.circular(2),
                  ),
                ),
        ),
        const SizedBox(width: 7),
        Text(label, style: HqText.tiny.copyWith(color: HqColors.ink2)),
      ],
    );
  }
}
