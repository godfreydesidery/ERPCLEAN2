import 'package:flutter/material.dart';

import '../app/format.dart';
import '../app/theme.dart';
import '../widgets/charts.dart';
import '../widgets/common.dart';

// ---------------------------------------------------------------------------
// Demo dataset for the daily flash. Tembo Group Ltd, trade of Tue 17 Aug 2026,
// posted at 06:40 the following morning once all eight branches had closed.
// Values in millions of TZS unless the name says otherwise.
// ---------------------------------------------------------------------------

const _kDay = 'Tue 17 Aug';
const _kPostedAt = 'posted 06:40';

/// Yesterday's takings.
const double _kSales = 41.2e6;
const double _kCredit = 14.1e6;

/// The right comparison for a retail day is the SAME WEEKDAY, not the day
/// before. Median of the eight Tuesdays that preceded yesterday.
const double _kTuesdayMedian = 38.1e6;
const double _kPlanPerTuesday = 39.5e6;
const double _kMtdActual = 486e6;

/// Eight prior Tuesdays, then yesterday last (nine points, ninth is the hero).
const List<double> _kTuesdaySpark = <double>[
  35.4, 36.8, 37.9, 38.0, 38.2, 39.1, 40.3, 38.4, 41.2,
];

/// Fourteen trading days, 4 Aug through 17 Aug. Sunday is always the trough.
const List<double> _kFourteenDays = <double>[
  37.8, 39.4, 44.6, 51.2, 18.9, 33.5, 38.4,
  36.9, 40.1, 45.8, 52.7, 19.6, 34.8, 41.2,
];

const List<String> _kFourteenLabels = <String>[
  '4', '5', '6', '7', '8', '9', '10',
  '11', '12', '13', '14', '15', '16', '17',
];

/// Daily plan drawn as the target line on the fourteen-day trend.
const double _kDailyPlanM = 39.5;

const List<double> _kTxnSpark = <double>[1610, 1655, 1702, 1688, 1741, 1770, 1731, 1842];
const List<double> _kBasketSpark = <double>[21.7, 22.2, 22.3, 22.5, 22.0, 22.4, 22.2, 22.4];
const List<double> _kMarginSpark = <double>[8.4, 8.8, 9.1, 9.0, 9.3, 9.6, 9.2, 9.7];

/// The daily flash an owner opens at 07:00 with the first cup of tea.
class TodaysTradeScreen extends StatelessWidget {
  const TodaysTradeScreen({super.key});

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: HqColors.bg,
      appBar: AppBar(
        title: Text(
          'How did we trade yesterday?',
          style: HqText.title.copyWith(fontSize: 18),
        ),
      ),
      body: ListView(
        padding: const EdgeInsets.fromLTRB(20, 8, 20, 32),
        children: <Widget>[
          _hero(),
          const SizedBox(height: 10),
          const _WeekdayNote(),
          const SizedBox(height: 22),
          _exceptions(context),
          const SizedBox(height: 22),
          _fourteenDays(),
          const SizedBox(height: 14),
          _whereItCameFrom(),
          const SizedBox(height: 14),
          _byBranch(),
          const SizedBox(height: 14),
          _statTiles(),
          const SizedBox(height: 18),
          DrillButton(
            label: 'See yesterday by branch',
            onTap: () => _drill(context),
          ),
          const SizedBox(height: 18),
          const ResidualLine(
            text: 'Branch and channel splits reconcile to the day total within '
                'TZS 0.1M of rounding. A gap under TZS 0.5M, or under 2% of a '
                'branch day, is not chased.',
          ),
          const SizedBox(height: 12),
          const AsOfLine(
            asOf: '$_kDay, $_kPostedAt',
            coverage: 'All 8 branches closed and posted · POS tills reconciled',
          ),
        ],
      ),
    );
  }

  // -- hero ------------------------------------------------------------------

  Widget _hero() {
    return HeroCard(
      question: 'How did we trade yesterday?',
      verdict: 'TZS 41.2M yesterday — 8% ahead of the last eight Tuesdays.',
      heroValue: tzs(_kSales),
      heroSub: 'of which ${tzs(_kCredit)} on credit',
      asOf: '$_kDay · $_kPostedAt',
      trustLabel: 'POSTED',
      band: TrustBand.posted,
      chart: const _TuesdayTrail(),
      comparisons: <Widget>[
        ComparisonRow(
          label: 'Median of the last 8 Tuesdays',
          value: tzs(_kTuesdayMedian),
          delta: 8.1,
          higherIsBetter: true,
          onDark: true,
        ),
        ComparisonRow(
          label: 'Plan for a Tuesday',
          value: tzs(_kPlanPerTuesday),
          delta: 4.3,
          higherIsBetter: true,
          onDark: true,
        ),
        ComparisonRow(
          label: 'Month to date against plan',
          value: tzs(_kMtdActual),
          delta: 2.5,
          higherIsBetter: true,
          onDark: true,
        ),
      ],
    );
  }

  // -- exceptions ------------------------------------------------------------

  Widget _exceptions(BuildContext context) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: <Widget>[
        const SectionLabel(text: '2 THINGS NEED YOU', trailing: _kDay),
        const SizedBox(height: 10),
        ExceptionTile(
          title: 'Mwanza missed its own Tuesday again',
          detail: 'Fourth Tuesday running below its median. Counter takings '
              'fell while the route vans held their numbers.',
          amount: tzs(0.6e6),
          owner: 'Neema Mkwawa · Branch Manager, Mwanza',
          actionLabel: 'Call Neema',
          severe: true,
          onAction: () => _ack(context, 'Calling Neema Mkwawa, Mwanza.'),
        ),
        const SizedBox(height: 10),
        ExceptionTile(
          title: 'Credit took 34% of the day',
          detail: 'Credit ran at 27% of sales across the last eight Tuesdays. '
              'Three customers were served past their limit.',
          amount: tzs(_kCredit),
          owner: 'Juma Kessy · Credit Controller',
          actionLabel: 'Review limits',
          onAction: () => _ack(context, 'Opening the three limit breaches.'),
        ),
      ],
    );
  }

  // -- fourteen days ---------------------------------------------------------

  Widget _fourteenDays() {
    return HqCard(
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: <Widget>[
          _cardTitle('The last 14 days'),
          const SizedBox(height: 4),
          Text(
            'Saturdays carry the counter, Sunday is always the trough. The line '
            'is the daily plan of TZS 39.5M — held on 9 of the 14 days.',
            style: HqText.tiny,
          ),
          const SizedBox(height: 16),
          const ColumnTrend(
            values: _kFourteenDays,
            labels: _kFourteenLabels,
            target: _kDailyPlanM,
            height: 132,
          ),
          const SizedBox(height: 8),
          Text('Day of August · TZS millions', style: HqText.tiny),
        ],
      ),
    );
  }

  // -- channel mix -----------------------------------------------------------

  Widget _whereItCameFrom() {
    return HqCard(
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: <Widget>[
          _cardTitle('Where it came from'),
          const SizedBox(height: 4),
          Text(
            'Counter TZS 16.4M · credit TZS 14.1M · route vans TZS 7.3M · '
            'projects TZS 3.4M.',
            style: HqText.tiny,
          ),
          const SizedBox(height: 16),
          const ShareBar(
            parts: <SharePart>[
              SharePart('Counter', 16.4),
              SharePart('Credit', 14.1),
              SharePart('Route vans', 7.3),
              SharePart('Projects', 3.4),
            ],
          ),
          const SizedBox(height: 12),
          Text(
            'Credit is 7pp heavier than a normal Tuesday. Cash through the '
            'counter still paid for the day.',
            style: HqText.tiny,
          ),
        ],
      ),
    );
  }

  // -- branch ranking --------------------------------------------------------

  Widget _byBranch() {
    return HqCard(
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: <Widget>[
          _cardTitle('By branch, yesterday'),
          const SizedBox(height: 4),
          Text(
            'Company-wide, all eight branches. The figure on the right is each '
            "branch against its own Tuesday median.",
            style: HqText.tiny,
          ),
          const SizedBox(height: 16),
          const RankedBars(
            rows: <RankRow>[
              RankRow('Kariakoo', 11.8, '+14%', status: RankStatus.best),
              RankRow('Dar es Salaam (HQ)', 9.4, '+9%', status: RankStatus.good),
              RankRow('Arusha', 5.6, '+4%'),
              RankRow('Mwanza', 4.1, '-12%', status: RankStatus.bad),
              RankRow('Mbeya', 3.5, '+2%'),
              RankRow('Dodoma', 3.0, '-3%'),
              RankRow('Moshi', 2.3, '+5%'),
              RankRow('Tanga', 1.5, '-19%', status: RankStatus.bad),
            ],
          ),
          const SizedBox(height: 12),
          Text(
            'Kariakoo and the HQ counter carried 51% of the day between them.',
            style: HqText.tiny,
          ),
        ],
      ),
    );
  }

  // -- stat tiles ------------------------------------------------------------

  Widget _statTiles() {
    return const Row(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: <Widget>[
        Expanded(
          child: StatTile(
            label: 'Transactions',
            value: '1,842',
            delta: '+6% vs Tue',
            deltaGood: true,
            spark: _kTxnSpark,
          ),
        ),
        SizedBox(width: 12),
        Expanded(
          child: StatTile(
            label: 'Average basket',
            value: 'TZS 22.4k',
            delta: '+1.6%',
            deltaGood: true,
            spark: _kBasketSpark,
          ),
        ),
        SizedBox(width: 12),
        Expanded(
          child: StatTile(
            label: 'Margin earned',
            value: 'TZS 9.7M',
            delta: '-0.6pp',
            deltaGood: false,
            spark: _kMarginSpark,
          ),
        ),
      ],
    );
  }

  // -- helpers ---------------------------------------------------------------

  Widget _cardTitle(String text) =>
      Text(text, style: HqText.title.copyWith(fontSize: 16));

  void _drill(BuildContext context) =>
      _ack(context, 'Yesterday by branch, all eight, opening.');

  void _ack(BuildContext context, String message) {
    final messenger = ScaffoldMessenger.of(context);
    messenger.hideCurrentSnackBar();
    messenger.showSnackBar(
      SnackBar(
        content: Text(message),
        backgroundColor: HqColors.ink,
        behavior: SnackBarBehavior.floating,
        duration: const Duration(seconds: 2),
        shape: RoundedRectangleBorder(
          borderRadius: BorderRadius.circular(HqRadii.sm),
        ),
      ),
    );
  }
}

/// The nine-Tuesday trail that sits inside the hero, on the dark field.
class _TuesdayTrail extends StatelessWidget {
  const _TuesdayTrail();

  @override
  Widget build(BuildContext context) {
    return Column(
      mainAxisSize: MainAxisSize.min,
      crossAxisAlignment: CrossAxisAlignment.start,
      children: <Widget>[
        const Sparkline(
          values: _kTuesdaySpark,
          color: VizColors.mint,
          height: 44,
          showLastDot: true,
          strokeWidth: 2.2,
        ),
        const SizedBox(height: 8),
        Text(
          'Nine Tuesdays · yesterday is the dot',
          style: HqText.tiny.copyWith(color: HqOnDark.tertiary),
        ),
      ],
    );
  }
}

/// Why the comparison is a Tuesday and not Monday. Owners ask this once.
class _WeekdayNote extends StatelessWidget {
  const _WeekdayNote();

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.fromLTRB(12, 10, 12, 10),
      decoration: BoxDecoration(
        color: HqColors.panel2,
        borderRadius: BorderRadius.circular(HqRadii.sm),
        border: const Border(
          left: BorderSide(color: HqColors.line2, width: 3),
        ),
      ),
      child: Text(
        'Measured against the last eight Tuesdays, not against Monday. In '
        'retail a Monday is not a Sunday and a Tuesday is not a Saturday — '
        'day-on-day would flatter or damn the branch for nothing.',
        style: HqText.tiny,
      ),
    );
  }
}
