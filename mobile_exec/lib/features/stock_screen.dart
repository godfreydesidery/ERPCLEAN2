import 'package:flutter/material.dart';

import '../app/format.dart';
import '../app/theme.dart';
import '../widgets/charts.dart';
import '../widgets/common.dart';

// ---------------------------------------------------------------------------
// Demo dataset - Tembo Group Ltd, stock health as at 18 Aug 2026.
// Kept private to this screen so it can never collide with lib/data/mock.dart.
// ---------------------------------------------------------------------------

const double _stockValue = 1310000000; // TZS 1.31bn on hand, all branches
const double _stockValuePrior = 1244000000; // last month close
const double _coverMonths = 6.0;
const double _coverCeiling = 4.0;
const double _deadValue = 340000000; // has sat 90 days or more
const int _branchCount = 8;

/// Eight months of closing stock value, in TZS millions. Doctrine: at least
/// six periods of history stand behind the headline number.
const List<double> _valueTrend = <double>[
  1080, 1125, 1161, 1214, 1189, 1244, 1268, 1310,
];

/// How long it has sat. Higher shade index = older money = darker bar.
const List<String> _ageLabels = <String>['0-30 d', '31-60 d', '61-90 d', '90+ d'];
const List<double> _ageValues = <double>[620, 230, 120, 340];
const List<int> _ageShades = <int>[1, 2, 3, 5];

class _Dead {
  const _Dead(this.sku, this.detail, this.amount, {this.severe = false});
  final String sku;
  final String detail;
  final double amount;
  final bool severe;
}

const List<_Dead> _deadSkus = <_Dead>[
  _Dead(
    'Galvanised roofing sheets, 28g',
    'Mwanza and Tanga hold 1,240 bundles between them. Nothing issued since 4 March.',
    128000000,
    severe: true,
  ),
  _Dead(
    'Ceramic wall tiles, 30x60 beige',
    'Kariakoo bought 14 months of cover in one order. Last sale was 112 days ago.',
    96000000,
  ),
  _Dead(
    'PVC conduit pipe, 20mm',
    'Arusha is sitting on 9 months of cover since the Themi site contract lapsed.',
    61000000,
  ),
];

enum _Sev { critical, tight, watch }

class _RunOut {
  const _RunOut(this.item, this.daysCover, this.branch, this.incoming, this.status);
  final String item;
  final int daysCover;
  final String branch;
  final String incoming;
  final _Sev status;
}

const List<_RunOut> _runOuts = <_RunOut>[
  _RunOut('Cement 32.5N, 50kg', 2, 'Dar es Salaam and Kariakoo',
      'Delivery due 21 Aug', _Sev.critical),
  _RunOut('Tembo Gold cooking oil, 20L', 6, 'Mbeya and Mwanza',
      'Delivery due 24 Aug', _Sev.tight),
  _RunOut('Kagera sugar, 50kg', 9, 'Dodoma', 'Delivery due 27 Aug', _Sev.watch),
];

class _Branch {
  const _Branch(this.name, this.millions, this.status);
  final String name;
  final double millions;
  final RankStatus status;
}

const List<_Branch> _byBranch = <_Branch>[
  _Branch('Dar es Salaam (HQ)', 402, RankStatus.normal),
  _Branch('Kariakoo', 268, RankStatus.bad),
  _Branch('Mwanza', 186, RankStatus.normal),
  _Branch('Arusha', 158, RankStatus.normal),
  _Branch('Dodoma', 122, RankStatus.normal),
  _Branch('Mbeya', 92, RankStatus.normal),
  _Branch('Moshi', 48, RankStatus.good),
  _Branch('Tanga', 34, RankStatus.best),
];

const String _asOf = 'as at 18 Aug 2026, 06:00';
const String _coverage = '8 of 8 branches counted';

// ---------------------------------------------------------------------------

/// "What my money is sleeping in" - stock health for the owner.
class StockScreen extends StatelessWidget {
  const StockScreen({super.key});

  double get _valueDeltaPct =>
      (_stockValue - _stockValuePrior) / _stockValuePrior * 100;

  double get _coverDeltaPct => (_coverMonths - _coverCeiling) / _coverCeiling * 100;

  void _toast(BuildContext context, String message) {
    ScaffoldMessenger.of(context)
      ..hideCurrentSnackBar()
      ..showSnackBar(
        SnackBar(
          content: Text(message),
          backgroundColor: HqColors.ink,
          behavior: SnackBarBehavior.floating,
          margin: const EdgeInsets.all(16),
          shape: RoundedRectangleBorder(
            borderRadius: BorderRadius.circular(HqRadii.sm),
          ),
        ),
      );
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: HqColors.bg,
      appBar: AppBar(
        title: const Text('Where is my money sitting?', style: HqText.title),
      ),
      body: ListView(
        padding: const EdgeInsets.fromLTRB(20, 8, 20, 36),
        children: <Widget>[
          // 1 - the hero
          HeroCard(
            question: 'What is my money sleeping in?',
            verdict:
                'TZS 1.31bn in stock - 6.0 months of cover against a 4-month ceiling.',
            heroValue: tzs(_stockValue),
            heroSub: 'across $_branchCount branches',
            band: TrustBand.provisional,
            trustLabel: 'PROVISIONAL',
            asOf: _asOf,
            chart: const Sparkline(
              values: _valueTrend,
              color: VizColors.mint,
              height: 46,
              strokeWidth: 2.2,
            ),
            comparisons: <Widget>[
              ComparisonRow(
                label: 'Months of cover vs the 4-month policy',
                value: '${_coverMonths.toStringAsFixed(1)} months',
                delta: _coverDeltaPct,
                higherIsBetter: false,
                onDark: true,
              ),
              ComparisonRow(
                label: 'Stock value vs last month',
                value: tzs(_stockValue),
                delta: _valueDeltaPct,
                higherIsBetter: false,
                onDark: true,
              ),
              const ComparisonRow(
                label: 'The two months above policy are worth',
                value: 'TZS 437M',
                onDark: true,
              ),
            ],
          ),
          const SizedBox(height: 18),

          // 2 - how long it has sat
          HqCard(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: <Widget>[
                const SectionLabel(text: 'HOW LONG IT HAS SAT', trailing: 'BY VALUE'),
                const SizedBox(height: 6),
                const Text(
                  'A quarter of the money has not moved since May.',
                  style: HqText.verdict,
                ),
                const SizedBox(height: 16),
                AgeingBars(
                  height: 130,
                  buckets: <AgeBucket>[
                    for (var i = 0; i < _ageValues.length; i++)
                      AgeBucket(_ageLabels[i], _ageValues[i], _ageShades[i]),
                  ],
                ),
                const SizedBox(height: 16),
                const Divider(height: 1),
                const SizedBox(height: 14),
                const _Fact(
                  label: 'Fresh stock, under 60 days',
                  value: 'TZS 850M',
                  note: '65% of the pile - healthy',
                  good: true,
                ),
                const SizedBox(height: 12),
                const _Fact(
                  label: 'Aged past 90 days',
                  value: 'TZS 340M',
                  note: '26% of the pile, up from 21% in June',
                  good: false,
                ),
              ],
            ),
          ),
          const SizedBox(height: 22),

          // 3 - exceptions: the things that need a person
          SectionLabel(
            text: "${tzsBare(_deadValue)} HASN'T MOVED IN 90 DAYS",
            trailing: '3 NEED YOU',
          ),
          const SizedBox(height: 10),
          for (final d in _deadSkus) ...<Widget>[
            ExceptionTile(
              title: d.sku,
              detail: d.detail,
              amount: tzs(d.amount),
              owner: 'Head of Purchasing',
              actionLabel: 'Mark for clearance',
              severe: d.severe,
              onAction: () => _toast(
                context,
                '${d.sku} sent to the Head of Purchasing for clearance pricing.',
              ),
            ),
            const SizedBox(height: 10),
          ],
          const ResidualLine(text: '12 SKUs are 70% of it.'),
          const SizedBox(height: 22),

          // 4 - the other end of the same problem
          HqCard(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: <Widget>[
                const SectionLabel(text: 'ABOUT TO RUN OUT', trailing: '3 LINES'),
                const SizedBox(height: 6),
                const Text(
                  'Cement is the one to watch - two days left and the counters keep selling it.',
                  style: HqText.verdict,
                ),
                const SizedBox(height: 16),
                for (var i = 0; i < _runOuts.length; i++) ...<Widget>[
                  if (i > 0)
                    const Padding(
                      padding: EdgeInsets.symmetric(vertical: 12),
                      child: Divider(height: 1),
                    ),
                  _RunOutRow(item: _runOuts[i]),
                ],
                const SizedBox(height: 16),
                const ResidualLine(
                  text: 'Every other line has three weeks of cover or better.',
                ),
              ],
            ),
          ),
          const SizedBox(height: 22),

          // 5 - where it is held
          HqCard(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: <Widget>[
                const SectionLabel(text: 'STOCK BY BRANCH', trailing: 'COMPANY-WIDE'),
                const SizedBox(height: 6),
                const Text(
                  'Kariakoo holds a fifth of the money on a tenth of the sales.',
                  style: HqText.verdict,
                ),
                const SizedBox(height: 16),
                RankedBars(
                  rows: <RankRow>[
                    for (final b in _byBranch)
                      RankRow(
                        b.name,
                        b.millions,
                        tzs(b.millions * 1000000),
                        status: b.status,
                      ),
                  ],
                ),
                const SizedBox(height: 16),
                const Divider(height: 1),
                const SizedBox(height: 12),
                const Text(
                  'Kariakoo carries 9.1 months of cover against the 4-month ceiling. '
                  'Moshi and Tanga sit at 2.8 months and turn their stock fastest in '
                  'the company.',
                  style: HqText.tiny,
                ),
              ],
            ),
          ),
          const SizedBox(height: 20),

          // 6 - the drill
          DrillButton(
            label: 'See the dead stock',
            onTap: () => _toast(
              context,
              'Dead stock: 12 SKUs, TZS 238M, ranked by days since last issue.',
            ),
          ),
          const SizedBox(height: 18),

          // 7 - provenance and the materiality rule
          const AsOfLine(asOf: _asOf, coverage: _coverage),
          const SizedBox(height: 8),
          const Text(
            'Valued at moving average cost. Provisional until the Mwanza and Tanga '
            'cycle counts post on 20 Aug. Lines under TZS 5M are grouped as other; '
            'that residual is TZS 31M, 2.4% of stock.',
            style: HqText.tiny,
          ),
        ],
      ),
    );
  }
}

/// A labelled figure with its comparison - no number travels alone.
class _Fact extends StatelessWidget {
  const _Fact({
    required this.label,
    required this.value,
    required this.note,
    required this.good,
  });

  final String label;
  final String value;
  final String note;
  final bool good;

  @override
  Widget build(BuildContext context) {
    final Color tone = good ? HqColors.good : HqColors.bad;
    return Row(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: <Widget>[
        Container(
          width: 4,
          height: 34,
          margin: const EdgeInsets.only(top: 2, right: 12),
          decoration: BoxDecoration(
            color: tone.withValues(alpha: 0.75),
            borderRadius: BorderRadius.circular(2),
          ),
        ),
        Expanded(
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: <Widget>[
              Text(label, style: HqText.label),
              const SizedBox(height: 2),
              Text(note, style: HqText.tiny),
            ],
          ),
        ),
        const SizedBox(width: 12),
        Text(value, style: HqText.tileValue.copyWith(color: tone)),
      ],
    );
  }
}

/// One line of the "about to run out" list: days of cover, then the rescue date.
class _RunOutRow extends StatelessWidget {
  const _RunOutRow({required this.item});

  final _RunOut item;

  @override
  Widget build(BuildContext context) {
    final (Color tone, Color field) = switch (item.status) {
      _Sev.critical => (HqColors.bad, HqColors.badSoft),
      _Sev.tight => (HqColors.warn, HqColors.warnSoft),
      _Sev.watch => (HqColors.ink2, HqColors.panel2),
    };

    // Twenty-one days is the comfortable floor for a fast mover.
    final double fill = (item.daysCover / 21).clamp(0.04, 1.0);
    final int lit = (fill * 1000).round();

    return Row(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: <Widget>[
        Container(
          width: 56,
          padding: const EdgeInsets.symmetric(vertical: 8),
          decoration: BoxDecoration(
            color: field,
            borderRadius: BorderRadius.circular(HqRadii.sm),
            border: Border.all(color: tone.withValues(alpha: 0.20)),
          ),
          child: Column(
            children: <Widget>[
              Text(
                '${item.daysCover}',
                style: HqText.tileValue.copyWith(color: tone, fontSize: 22),
              ),
              Text(
                item.daysCover == 1 ? 'day' : 'days',
                style: HqText.tiny.copyWith(color: tone),
              ),
            ],
          ),
        ),
        const SizedBox(width: 14),
        Expanded(
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: <Widget>[
              Text(item.item, style: HqText.verdict.copyWith(fontSize: 15)),
              const SizedBox(height: 3),
              Text('${item.branch} - ${item.incoming}', style: HqText.tiny),
              const SizedBox(height: 9),
              ClipRRect(
                borderRadius: BorderRadius.circular(3),
                child: SizedBox(
                  height: 6,
                  child: Row(
                    children: <Widget>[
                      Expanded(flex: lit, child: ColoredBox(color: tone)),
                      Expanded(
                        flex: 1000 - lit,
                        child: const ColoredBox(color: HqColors.line),
                      ),
                    ],
                  ),
                ),
              ),
            ],
          ),
        ),
      ],
    );
  }
}
