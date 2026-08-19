/// "What People Cost" - payroll, headcount and productivity for the owner.
///
/// Demo screen: every figure below is mock data for Tembo Group Ltd. No backend,
/// no HTTP. Doctrine: one hero number, no number travels alone, exception-led.
library;

import 'package:flutter/material.dart';

import '../app/format.dart';
import '../app/theme.dart';
import '../widgets/charts.dart';
import '../widgets/common.dart';

// ---------------------------------------------------------------------------
// Private demo data. Prefixed with `_` so it can never collide with mock.dart.
// ---------------------------------------------------------------------------

/// Payroll cost, month to date (TZS).
const double _payrollMtd = 176000000;

/// Same point last month (TZS).
const double _payrollLastMonth = 168000000;

/// Company-wide sales, month to date (TZS) - the denominator of the ratio.
const double _salesMtd = 1239000000;

/// The owner's ceiling: staff cost may not exceed 12% of sales.
const double _targetRatio = 12.0;
const double _actualRatio = 14.2;

/// What payroll would have been at the 12% ceiling (TZS).
const double _payrollAtCeiling = 148700000;

const int _headcount = 214;

/// Twelve months of payroll, in TZS millions - the headline history.
const List<double> _payroll12 = <double>[
  151, 154, 149, 158, 162, 157, 163, 166, 161, 170, 168, 176,
];

/// Twelve months of overtime, in TZS millions.
const List<double> _overtime12 = <double>[
  6.2, 6.8, 5.9, 7.4, 8.1, 7.6, 9.0, 9.8, 10.4, 12.1, 13.6, 15.2,
];

const List<String> _monthLabels = <String>[
  'S', 'O', 'N', 'D', 'J', 'F', 'M', 'A', 'M', 'J', 'J', 'A',
];

/// Headcount, last 12 months.
const List<double> _headcount12 = <double>[
  198, 199, 201, 203, 202, 205, 206, 204, 207, 209, 208, 214,
];

/// Revenue per employee, last 12 months (TZS millions).
const List<double> _revPerHead12 = <double>[
  5.41, 5.52, 5.38, 5.71, 5.88, 5.74, 5.96, 6.04, 5.92, 6.11, 5.97, 5.79,
];

/// Profit per employee, last 12 months (TZS thousands).
const List<double> _profitPerHead12 = <double>[
  432, 448, 419, 466, 481, 470, 494, 502, 487, 505, 478, 449,
];

const double _revenuePerHead = 5790000; // TZS 1.239bn / 214 staff
const double _profitPerHead = 449000; // TZS 96.0M net / 214 staff

class PeopleScreen extends StatelessWidget {
  const PeopleScreen({super.key});

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: HqColors.bg,
      appBar: AppBar(
        title: const Text('What is my wage bill doing?'),
      ),
      body: ListView(
        padding: const EdgeInsets.fromLTRB(20, 16, 20, 40),
        children: <Widget>[
          _hero(),
          const SizedBox(height: 18),
          _costByFunction(),
          const SizedBox(height: 14),
          _headcountAndProductivity(),
          const SizedBox(height: 14),
          _overtime(),
          const SizedBox(height: 22),
          const SectionLabel(text: '2 THINGS NEED YOU', trailing: 'TZS 47.5M'),
          const SizedBox(height: 10),
          _exceptionOvertime(),
          const SizedBox(height: 10),
          _exceptionLeave(),
          const SizedBox(height: 18),
          const ResidualLine(
            text:
                'TZS 2.1M of casual labour is not yet costed to a function - 1.2% of '
                'payroll, left in Admin until the timesheets are keyed. Anything '
                'above TZS 5M is named here rather than pooled.',
          ),
          const SizedBox(height: 16),
          DrillButton(
            label: 'See the overtime by branch',
            onTap: () => _drill(context, 'Overtime by branch'),
          ),
          const SizedBox(height: 18),
          const AsOfLine(
            asOf: 'as at 18 Aug 2026, 06:00',
            coverage: 'all 8 branches - 214 of 214 staff on the August run',
          ),
        ],
      ),
    );
  }

  // -------------------------------------------------------------------------
  // Hero: the one number, with its history and two comparisons.
  // -------------------------------------------------------------------------

  Widget _hero() {
    final double vsLastMonth =
        (_payrollMtd - _payrollLastMonth) / _payrollLastMonth * 100;
    final double vsCeiling =
        (_payrollMtd - _payrollAtCeiling) / _payrollAtCeiling * 100;

    return HeroCard(
      question: 'Is payroll outgrowing sales?',
      verdict: 'Staff cost is 14.2% of sales - your ceiling is 12%.',
      heroValue: tzs(_payrollMtd),
      heroSub: 'payroll, month to date, $_headcount staff',
      asOf: 'as at 18 Aug 2026',
      trustLabel: 'PROVISIONAL - August run not yet posted',
      band: TrustBand.provisional,
      chart: const Sparkline(
        values: _payroll12,
        color: VizColors.mint,
        height: 48,
        showLastDot: true,
        strokeWidth: 2.4,
      ),
      comparisons: <Widget>[
        ComparisonRow(
          label: 'Last month, same day',
          value: tzs(_payrollLastMonth),
          delta: vsLastMonth,
          higherIsBetter: false,
          onDark: true,
        ),
        ComparisonRow(
          label: 'Your 12% ceiling',
          value: tzs(_payrollAtCeiling),
          delta: vsCeiling,
          higherIsBetter: false,
          onDark: true,
        ),
      ],
    );
  }

  // -------------------------------------------------------------------------
  // Cost by function.
  // -------------------------------------------------------------------------

  Widget _costByFunction() {
    return HqCard(
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: <Widget>[
          const Text('Cost by function', style: HqText.title),
          const SizedBox(height: 4),
          Text(
            'Where the ${tzs(_payrollMtd)} sits this month - '
            '${pct(_actualRatio)} of the ${tzs(_salesMtd)} you sold. Factory is '
            'the only function ahead of its own budget.',
            style: HqText.body,
          ),
          const SizedBox(height: 16),
          RankedBars(
            rows: <RankRow>[
              RankRow('Sales', 52400000, tzs(52400000)),
              RankRow('Factory', 48900000, tzs(48900000),
                  status: RankStatus.bad),
              RankRow('Warehouse', 24100000, tzs(24100000)),
              RankRow('Admin', 21600000, tzs(21600000)),
              RankRow('Drivers', 17800000, tzs(17800000)),
              RankRow('Finance', 11200000, tzs(11200000),
                  status: RankStatus.good),
            ],
          ),
          const SizedBox(height: 14),
          Text(
            'Factory is TZS 5.4M over its budget, all of it overtime. Finance is '
            'TZS 0.9M under after the Dodoma clerk moved to Mwanza.',
            style: HqText.tiny,
          ),
        ],
      ),
    );
  }

  // -------------------------------------------------------------------------
  // Headcount and productivity.
  // -------------------------------------------------------------------------

  Widget _headcountAndProductivity() {
    return HqCard(
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: <Widget>[
          const Text('Headcount and productivity', style: HqText.title),
          const SizedBox(height: 4),
          const Text(
            'Six more people than last month, and each one is carrying less.',
            style: HqText.body,
          ),
          const SizedBox(height: 16),
          const IntrinsicHeight(
            child: Row(
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: <Widget>[
                Expanded(
                  child: StatTile(
                    label: 'Headcount',
                    value: '214',
                    delta: '+6',
                    deltaGood: false,
                    spark: _headcount12,
                  ),
                ),
                SizedBox(width: 10),
                Expanded(
                  child: StatTile(
                    label: 'Revenue per employee',
                    value: 'TZS 5.79M',
                    delta: '-3.0%',
                    deltaGood: false,
                    spark: _revPerHead12,
                  ),
                ),
                SizedBox(width: 10),
                Expanded(
                  child: StatTile(
                    label: 'Profit per employee',
                    value: 'TZS 449k',
                    delta: '-6.1%',
                    deltaGood: false,
                    spark: _profitPerHead12,
                  ),
                ),
              ],
            ),
          ),
          const SizedBox(height: 14),
          Text(
            'The six joiners are all Kariakoo counter and loading staff. Until '
            'they lift takings, every head earns you ${tzs(_revenuePerHead)} of '
            'sales and ${tzs(_profitPerHead)} of profit a month - both down on '
            'July. Hold the wage bill at ${pct(_targetRatio)} of sales and this '
            'month costs ${tzs(_payrollAtCeiling)}, not ${tzs(_payrollMtd)}.',
            style: HqText.tiny,
          ),
        ],
      ),
    );
  }

  // -------------------------------------------------------------------------
  // Overtime.
  // -------------------------------------------------------------------------

  Widget _overtime() {
    return HqCard(
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: <Widget>[
          Row(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: <Widget>[
              const Expanded(child: Text('Overtime', style: HqText.title)),
              Container(
                padding: const EdgeInsets.symmetric(horizontal: 9, vertical: 4),
                decoration: BoxDecoration(
                  color: HqColors.badSoft,
                  borderRadius: BorderRadius.circular(999),
                  border: Border.all(color: HqColors.bad.withValues(alpha: 0.28)),
                ),
                child: const Text(
                  '2.5x in 12 months',
                  style: TextStyle(
                    fontSize: 11.5,
                    fontWeight: FontWeight.w700,
                    color: HqColors.bad,
                  ),
                ),
              ),
            ],
          ),
          const SizedBox(height: 4),
          const Text(
            'TZS 15.2M this month against a TZS 8.0M monthly allowance - the '
            'fourth month running above it.',
            style: HqText.body,
          ),
          const SizedBox(height: 16),
          const ColumnTrend(
            values: _overtime12,
            labels: _monthLabels,
            target: 8.0,
            height: 132,
            hatchLast: true,
            barColor: VizColors.cat2,
          ),
          const SizedBox(height: 12),
          Text(
            'Kariakoo drives it: TZS 6.2M of this month\'s TZS 15.2M, on a branch '
            'that is 19% of headcount. Dar es Salaam (HQ) and Arusha are both '
            'inside allowance. The last bar is part-month and will grow.',
            style: HqText.tiny,
          ),
        ],
      ),
    );
  }

  // -------------------------------------------------------------------------
  // The two things that need the owner.
  // -------------------------------------------------------------------------

  Widget _exceptionOvertime() {
    return const ExceptionTile(
      title: 'Overtime has concentrated in Kariakoo',
      detail:
          '1,840 overtime hours this month, 3.1x the branch\'s own 12-month '
          'average. Two loaders account for a third of it. No approval was '
          'recorded for any hour beyond the first 400.',
      amount: 'TZS 6.2M',
      owner: 'N. Kileo, HR',
      actionLabel: 'Cap Kariakoo overtime',
      severe: true,
    );
  }

  Widget _exceptionLeave() {
    return const ExceptionTile(
      title: 'Leave liability is building up',
      detail:
          '63 staff now carry 30 or more untaken leave days, led by Factory and '
          'Warehouse. The accrued cost grows about TZS 4.8M a month and lands in '
          'one payment if they all take it at once.',
      amount: 'TZS 41.3M',
      owner: 'N. Kileo, HR',
      actionLabel: 'Schedule leave now',
    );
  }

  // -------------------------------------------------------------------------

  void _drill(BuildContext context, String what) {
    ScaffoldMessenger.of(context)
      ..hideCurrentSnackBar()
      ..showSnackBar(
        SnackBar(
          behavior: SnackBarBehavior.floating,
          backgroundColor: HqColors.ink,
          content: Text('$what - available in the full app'),
        ),
      );
  }
}
