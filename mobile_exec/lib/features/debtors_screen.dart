import 'package:flutter/material.dart';

import '../app/format.dart';
import '../app/theme.dart';
import '../data/mock.dart' as m;
import '../widgets/charts.dart';
import '../widgets/common.dart' hide Sparkline;

/// "Who owes me money that's late?"
///
/// Exception-led receivables. The hero answers the age question before the
/// size question, because old money is the money that never arrives. The three
/// names that carry two thirds of the book sit ABOVE the supporting detail,
/// each with the person who owns the call.
///
/// Doctrine 10 is doing real work here: the total is FALLING, which is good
/// news and reads green, while DSO is RISING, which is bad news and reads red.
/// A negative number is not automatically a red number.
class DebtorsScreen extends StatelessWidget {
  const DebtorsScreen({super.key});

  /// Six periods of overdue balance, so a slow slide cannot hide (doctrine 5).
  static const List<double> _overdueTrend = <double>[
    274000000,
    291000000,
    305000000,
    330000000,
    341000000,
    318000000,
  ];

  /// Sequential ramp position - older money takes a darker teal.
  static const List<int> _shades = <int>[1, 2, 4, 5];

  @override
  Widget build(BuildContext context) {
    // Everything below is derived from the ledger mock, so the hero sentence,
    // the bars, the three names and the residual all tie to one another.
    final num total = m.kDebtorsOverdue;
    final num over60 = m.kDebtorAgeing[2].amount + m.kDebtorAgeing[3].amount;
    final double over60Share = over60 / total * 100;
    final double namedShare = m.kDebtorNamedTotal / total * 100;
    final double dsoMove = (m.kDso - m.kDsoLastMonth) / m.kDsoLastMonth * 100;
    final int accounts =
        m.kDebtorAgeing.fold<int>(0, (int a, m.AgeBucket b) => a + b.accounts);
    final m.AgeBucket oldest = m.kDebtorAgeing.last;

    return Scaffold(
      backgroundColor: HqColors.bg,
      appBar: AppBar(
        title: const Text(
          "Who owes me money that's late?",
          style: TextStyle(fontSize: 17, fontWeight: FontWeight.w700),
        ),
      ),
      body: SafeArea(
        top: false,
        child: ListView(
          padding: const EdgeInsets.fromLTRB(18, 12, 18, 32),
          children: <Widget>[
            // 1 --------------------------------------------------- the verdict
            HeroCard(
              question: 'Receivables, company-wide',
              verdict:
                  '${tzs(over60)} is over 60 days — up TZS 38M this month.',
              heroValue: tzs(total),
              heroSub: 'overdue across $accounts customers',
              accented: true,
              band: TrustBand.posted,
              asOf: 'Ledger as at ${m.kAsOf} · ${m.kCoverage}',
              comparisons: <Widget>[
                ComparisonRow(
                  label: 'Days sales outstanding — was '
                      '${m.kDsoLastMonth}, target ${m.kDsoTarget}',
                  value: '${m.kDso} days',
                  delta: dsoMove,
                  higherIsBetter: false,
                  onDark: true,
                ),
                ComparisonRow(
                  label:
                      'Sitting past 60 days — your policy caps this at 30%',
                  value: pct(over60Share),
                  onDark: true,
                ),
              ],
              chart: const _OverdueTrend(values: _overdueTrend),
            ),
            const SizedBox(height: 14),

            // 2 ----------------------------------------- how old the money is
            HqCard(
              padding: const EdgeInsets.fromLTRB(16, 15, 16, 14),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                mainAxisSize: MainAxisSize.min,
                children: <Widget>[
                  _CardHead(
                    title: 'How old is the money',
                    trailing: '${tzs(total)} total',
                  ),
                  const SizedBox(height: 3),
                  Text(
                    'Darker is older. The right-hand columns are the ones that '
                    'stop being money.',
                    style: HqText.tiny.copyWith(color: HqColors.ink2),
                  ),
                  const SizedBox(height: 14),
                  AgeingBars(
                    height: 146,
                    buckets: <AgeBucket>[
                      for (int i = 0; i < m.kDebtorAgeing.length; i++)
                        AgeBucket(
                          m.kDebtorAgeing[i].label,
                          m.kDebtorAgeing[i].amount.toDouble(),
                          _shades[i % _shades.length],
                        ),
                    ],
                  ),
                  const SizedBox(height: 12),
                  const Divider(height: 1, thickness: 1, color: HqColors.line),
                  const SizedBox(height: 11),
                  _FootNote(
                    tint: HqColors.bad,
                    text: 'Just ${oldest.accounts} accounts hold the '
                        '${oldest.label} column — ${tzs(oldest.amount)} '
                        'of it.',
                  ),
                ],
              ),
            ),
            const SizedBox(height: 20),

            // 3 ----------------------------------------- the names that matter
            SectionLabel(
              text: '3 names are ${namedShare.toStringAsFixed(0)}% of it',
              trailing: '${tzs(m.kDebtorNamedTotal)} of ${tzs(total)}',
            ),
            for (int i = 0; i < m.kWorstDebtors.length; i++) ...<Widget>[
              if (i > 0) const SizedBox(height: 10),
              ExceptionTile(
                title: m.kWorstDebtors[i].customer,
                detail: '${m.kWorstDebtors[i].age} overdue · '
                    '${m.kWorstDebtors[i].branch} — '
                    '${m.kWorstDebtors[i].status}',
                amount: tzs(m.kWorstDebtors[i].amount),
                owner: '${m.kWorstDebtors[i].owner} · '
                    '${m.kWorstDebtors[i].ownerRole}',
                actionLabel: 'Call',
                severe: !m.kWorstDebtors[i].status.contains('Promised'),
                onAction: () {},
              ),
            ],
            ResidualLine(text: '${m.kDebtorResidual}.'),
            const SizedBox(height: 10),

            // 4 ---------------------------------------- collection performance
            HqCard(
              padding: const EdgeInsets.fromLTRB(16, 15, 16, 16),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                mainAxisSize: MainAxisSize.min,
                children: <Widget>[
                  const _CardHead(
                    title: 'Collection performance',
                    trailing: 'rolling 90 days',
                  ),
                  const SizedBox(height: 16),
                  Row(
                    crossAxisAlignment: CrossAxisAlignment.center,
                    children: <Widget>[
                      const DonutMeter(
                        fraction: 0.71,
                        centre: '71%',
                        caption: 'collected within terms',
                        size: 122,
                      ),
                      const SizedBox(width: 18),
                      Expanded(
                        child: Column(
                          crossAxisAlignment: CrossAxisAlignment.start,
                          mainAxisSize: MainAxisSize.min,
                          children: <Widget>[
                            const _MiniStat(
                              label: '90-day average',
                              value: '71%',
                              note: 'flat since May',
                            ),
                            const SizedBox(height: 11),
                            const Divider(
                              height: 1,
                              thickness: 1,
                              color: HqColors.line,
                            ),
                            const SizedBox(height: 11),
                            _MiniStat(
                              label: 'Target',
                              value: '85%',
                              note: 'Short of target by 14pp — that gap is '
                                  '${tzs(45000000)} a month',
                              noteTint: HqColors.bad,
                            ),
                          ],
                        ),
                      ),
                    ],
                  ),
                ],
              ),
            ),
            const SizedBox(height: 18),

            // 5 ------------------------------------------------------ one drill
            DrillButton(
              label: "See Mo Hardware's payment history",
              onTap: () {},
            ),
          ],
        ),
      ),
    );
  }
}

// ---------------------------------------------------------------------------
// The hero trend block - six months of overdue balance on the dark field.
// ---------------------------------------------------------------------------

class _OverdueTrend extends StatelessWidget {
  const _OverdueTrend({required this.values});

  final List<double> values;

  static const List<String> _months = <String>[
    'Mar',
    'Apr',
    'May',
    'Jun',
    'Jul',
    'Aug',
  ];

  @override
  Widget build(BuildContext context) {
    final double move = values.last - values[values.length - 2];

    // A FALL in debtors is good news, so it reads green even though it is a
    // negative number (doctrine 10).
    final bool goodNews = move <= 0;
    final Color moveInk = Color.lerp(
      goodNews ? HqColors.good : HqColors.bad,
      Colors.white,
      0.55,
    )!;

    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      mainAxisSize: MainAxisSize.min,
      children: <Widget>[
        Row(
          children: <Widget>[
            const Expanded(
              child: Text(
                'OVERDUE, LAST 6 MONTHS',
                maxLines: 1,
                overflow: TextOverflow.ellipsis,
                style: TextStyle(
                  fontSize: 10.5,
                  fontWeight: FontWeight.w700,
                  letterSpacing: 0.8,
                  color: HqOnDark.tertiary,
                ),
              ),
            ),
            const SizedBox(width: 10),
            Icon(
              goodNews
                  ? Icons.arrow_downward_rounded
                  : Icons.arrow_upward_rounded,
              size: 14,
              color: moveInk,
            ),
            const SizedBox(width: 3),
            // Flexible so the money phrase ellipsizes on a 360 phone rather
            // than pushing the caption row off the hero card.
            Flexible(
              child: Text(
              '${tzs(move.abs())} on last month',
              maxLines: 1,
              overflow: TextOverflow.ellipsis,
              style: TextStyle(
                fontSize: 12,
                fontWeight: FontWeight.w700,
                color: moveInk,
              ),
            ),
            ),
          ],
        ),
        const SizedBox(height: 8),
        Sparkline(values: values, height: 48),
        const SizedBox(height: 6),
        Row(
          children: <Widget>[
            for (final String month in _months)
              Expanded(
                child: Text(
                  month,
                  textAlign: TextAlign.center,
                  maxLines: 1,
                  style: TextStyle(
                    fontSize: 10.5,
                    fontWeight:
                        month == _months.last ? FontWeight.w700 : FontWeight.w400,
                    color: month == _months.last
                        ? HqOnDark.secondary
                        : HqOnDark.tertiary,
                  ),
                ),
              ),
          ],
        ),
        const SizedBox(height: 6),
        const Text(
          'The book peaked at TZS 341M in July. The total is coming down; the '
          'old money is not.',
          style: TextStyle(
            fontSize: 11.5,
            height: 1.35,
            color: HqOnDark.tertiary,
          ),
        ),
      ],
    );
  }
}

// ---------------------------------------------------------------------------
// Small shared pieces
// ---------------------------------------------------------------------------

class _CardHead extends StatelessWidget {
  const _CardHead({required this.title, required this.trailing});

  final String title;
  final String trailing;

  @override
  Widget build(BuildContext context) {
    return Row(
      crossAxisAlignment: CrossAxisAlignment.end,
      children: <Widget>[
        Expanded(
          child: Text(
            title,
            maxLines: 1,
            overflow: TextOverflow.ellipsis,
            style: HqText.title.copyWith(fontSize: 16.5, height: 1.2),
          ),
        ),
        const SizedBox(width: 12),
        Flexible(
          child: Text(
            trailing,
            maxLines: 1,
            overflow: TextOverflow.ellipsis,
            textAlign: TextAlign.right,
            style: HqText.tiny,
          ),
        ),
      ],
    );
  }
}

class _FootNote extends StatelessWidget {
  const _FootNote({required this.text, required this.tint});

  final String text;
  final Color tint;

  @override
  Widget build(BuildContext context) {
    return Row(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: <Widget>[
        Container(
          margin: const EdgeInsets.only(top: 4),
          width: 6,
          height: 6,
          decoration: BoxDecoration(color: tint, shape: BoxShape.circle),
        ),
        const SizedBox(width: 9),
        Expanded(
          child: Text(
            text,
            style: const TextStyle(
              fontSize: 12.5,
              height: 1.35,
              fontWeight: FontWeight.w600,
              color: HqColors.ink2,
            ),
          ),
        ),
      ],
    );
  }
}

class _MiniStat extends StatelessWidget {
  const _MiniStat({
    required this.label,
    required this.value,
    required this.note,
    this.noteTint,
  });

  final String label;
  final String value;
  final String note;
  final Color? noteTint;

  @override
  Widget build(BuildContext context) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      mainAxisSize: MainAxisSize.min,
      children: <Widget>[
        Row(
          crossAxisAlignment: CrossAxisAlignment.end,
          textBaseline: TextBaseline.alphabetic,
          children: <Widget>[
            Expanded(
              child: Text(
                label.toUpperCase(),
                maxLines: 1,
                overflow: TextOverflow.ellipsis,
                style: const TextStyle(
                  fontSize: 10.5,
                  fontWeight: FontWeight.w700,
                  letterSpacing: 0.6,
                  color: HqColors.ink3,
                ),
              ),
            ),
            const SizedBox(width: 8),
            Text(value, maxLines: 1, style: HqText.tileValue),
          ],
        ),
        const SizedBox(height: 3),
        Text(
          note,
          maxLines: 2,
          overflow: TextOverflow.ellipsis,
          style: TextStyle(
            fontSize: 11.5,
            height: 1.3,
            fontWeight: noteTint == null ? FontWeight.w400 : FontWeight.w600,
            color: noteTint ?? HqColors.ink3,
          ),
        ),
      ],
    );
  }
}
