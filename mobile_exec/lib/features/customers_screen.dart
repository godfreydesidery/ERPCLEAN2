import 'package:flutter/material.dart';

import '../app/format.dart';
import '../app/theme.dart';
import '../widgets/charts.dart';
import '../widgets/common.dart';

/// "Who actually pays me" - customers ranked by PROFIT, not revenue.
///
/// The insight this screen exists to deliver: the account that bills the most
/// is not the account that earns the most. Revenue flatters; margin and
/// collection days decide. Two rankings in deliberately different order, then
/// the concentration risk that follows from them.
class CustomersScreen extends StatelessWidget {
  const CustomersScreen({super.key});

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: HqColors.bg,
      appBar: AppBar(
        title: const Text(
          'Which customers are worth having?',
          style: TextStyle(fontSize: 17, fontWeight: FontWeight.w700),
        ),
      ),
      body: ListView(
        padding: const EdgeInsets.fromLTRB(20, 8, 20, 32),
        children: [
          const _CustomerHeroStrip(),
          const SizedBox(height: 18),
          _revenueCard(),
          const SizedBox(height: 14),
          _profitCard(),
          const SizedBox(height: 14),
          _concentrationCard(),
          const SizedBox(height: 22),
          const SectionLabel(text: '1 THING NEEDS YOU', trailing: 'MONTH TO DATE'),
          const SizedBox(height: 10),
          ExceptionTile(
            title: 'Sokoine Retail Chain is buying 40% less',
            detail:
                'Two months running, and the fall is in their high-margin lines. Nothing '
                'ordered since the first week of August, and no visit has been logged.',
            amount: tzs(13100000),
            owner: 'D. Kessy, Sales',
            actionLabel: 'Call',
            severe: true,
            onAction: () => _toast(context, 'Opening a call to D. Kessy, Sales'),
          ),
          const SizedBox(height: 18),
          const ResidualLine(
            text:
                'Below the top five sit 305 customers carrying TZS 91.8M of profit between '
                'them; none earns more than TZS 4M. Accounts under TZS 2M of monthly gross '
                'profit are not ranked here - they move the total by less than 1.5%.',
          ),
          const SizedBox(height: 20),
          DrillButton(
            label: 'See customer profitability',
            onTap: () => _toast(context, 'Customer profitability, 310 accounts, month to date'),
          ),
        ],
      ),
    );
  }

  // ---------------------------------------------------------------- cards

  Widget _revenueCard() {
    return HqCard(
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          const _CardHead(
            title: 'Top 5 by revenue',
            note: 'What they bill you, month to date',
          ),
          const SizedBox(height: 14),
          RankedBars(
            rows: [
              for (final c in _byRevenue)
                RankRow(
                  c.name,
                  c.revenue,
                  tzs(c.revenue),
                  status: c.name == _kariakoo ? RankStatus.bad : RankStatus.normal,
                ),
            ],
          ),
          const SizedBox(height: 12),
          const _Reading(
            'These five bill TZS 751M between them - 57% of company revenue. Their margins '
            'are not alike: they run from 4.6% at the top of this list to 12.7% at the '
            'fourth name down.',
          ),
        ],
      ),
    );
  }

  Widget _profitCard() {
    return HqCard(
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          const _CardHead(
            title: 'Top 5 by profit',
            note: 'What they leave you, after cost of sales',
          ),
          const SizedBox(height: 14),
          RankedBars(
            rows: [
              for (final c in _byProfit)
                RankRow(
                  c.name,
                  c.profit,
                  tzs(c.profit),
                  status: c.name == _bahari
                      ? RankStatus.best
                      : (c.name == _kariakoo ? RankStatus.bad : RankStatus.normal),
                ),
            ],
          ),
          const SizedBox(height: 12),
          const _Reading(
            'The order changes. Kariakoo Wholesalers bills the most and earns you the least '
            'of the four above it: 4.6% margin, and it settles in 71 days against the 30 '
            'agreed. Bahari Foods buys barely half as much, keeps 12.7% and pays in 26 days, '
            'so it hands you TZS 15.4M against the TZS 9.8M from Kariakoo.',
          ),
          const SizedBox(height: 12),
          _marginStrip(),
        ],
      ),
    );
  }

  /// Margin per account, in the profit order above - the reason the two
  /// rankings disagree, in one line of numerals.
  Widget _marginStrip() {
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 10),
      decoration: BoxDecoration(
        color: HqColors.panel2,
        borderRadius: BorderRadius.circular(HqRadii.sm),
        border: Border.all(color: HqColors.line),
      ),
      child: Row(
        children: [
          for (var i = 0; i < _byProfit.length; i++) ...[
            if (i > 0) Container(width: 1, height: 26, color: HqColors.line),
            Expanded(
              child: Column(
                children: [
                  // Five cells across a 360px phone leave ~50px each - scale
                  // the figure down rather than let it run past its divider.
                  FittedBox(
                    fit: BoxFit.scaleDown,
                    child: Text(
                      pct(_byProfit[i].margin),
                      maxLines: 1,
                      style: HqText.label.copyWith(
                        fontWeight: FontWeight.w700,
                        color:
                            _byProfit[i].margin < 6 ? HqColors.bad : HqColors.ink,
                      ),
                    ),
                  ),
                  const SizedBox(height: 2),
                  Text(
                    _byProfit[i].shortName,
                    style: HqText.tiny.copyWith(fontSize: 10.5),
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                    textAlign: TextAlign.center,
                  ),
                ],
              ),
            ),
          ],
        ],
      ),
    );
  }

  Widget _concentrationCard() {
    return HqCard(
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          const _CardHead(
            title: 'Concentration',
            note: 'How much of the profit rests on how few names',
          ),
          const SizedBox(height: 14),
          LayoutBuilder(
            builder: (context, box) {
              // The ring is sized off the card, not hard-coded: on a 360px
              // phone the card is only ~288 wide, and a 108px ring would leave
              // the risk line beside it too narrow to read.
              final ring = box.maxWidth < 300 ? 92.0 : 108.0;
              final gap = box.maxWidth < 300 ? 12.0 : 16.0;
              return Row(
                crossAxisAlignment: CrossAxisAlignment.center,
                children: [
                  DonutMeter(
                    fraction: 0.38,
                    centre: '38%',
                    caption: 'of profit from your top 5',
                    color: HqColors.brand,
                    size: ring,
                  ),
                  SizedBox(width: gap),
                  Expanded(
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      mainAxisSize: MainAxisSize.min,
                      children: [
                        Text(
                          'Rising - it was 31% a year ago',
                          style: HqText.label.copyWith(
                            color: HqColors.warn,
                            fontWeight: FontWeight.w600,
                          ),
                        ),
                        const SizedBox(height: 4),
                        FittedBox(
                          fit: BoxFit.scaleDown,
                          alignment: Alignment.centerLeft,
                          child: Text(
                            pp(7.0),
                            maxLines: 1,
                            style: HqText.tileValue
                                .copyWith(color: HqColors.warn),
                          ),
                        ),
                        const SizedBox(height: 10),
                        const _Reading(
                          'If Bahari Foods walked tomorrow you would lose TZS 15.4M of profit a '
                          'month - 10.4% of the company total, and more than the whole Moshi '
                          'branch earns. Five phone calls could take out a third of your margin.',
                        ),
                      ],
                    ),
                  ),
                ],
              );
            },
          ),
          const SizedBox(height: 14),
          const Divider(height: 1),
          const SizedBox(height: 14),
          ShareBar(
            parts: const [
              SharePart('Top 5', 56.2),
              SharePart('Next 20', 38.4),
              SharePart('Other 285', 53.4),
            ],
          ),
          const SizedBox(height: 10),
          const _Reading(
            'Profit of TZS 148M split three ways. The long tail of 285 accounts still carries '
            'more than the top five - that is the buffer, and it is thinning.',
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
          backgroundColor: HqColors.ink,
          behavior: SnackBarBehavior.floating,
          duration: const Duration(seconds: 2),
        ),
      );
  }
}

// ------------------------------------------------------------------- hero

class _CustomerHeroStrip extends StatelessWidget {
  const _CustomerHeroStrip();

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.fromLTRB(20, 18, 20, 16),
      decoration: BoxDecoration(
        gradient: HqSurfaces.heroGradient,
        borderRadius: BorderRadius.circular(18),
        boxShadow: HqSurfaces.hero,
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              Expanded(
                child: Text(
                  'CUSTOMER PROFIT',
                  maxLines: 1,
                  overflow: TextOverflow.ellipsis,
                  style: HqText.section.copyWith(color: HqSurfaces.accent),
                ),
              ),
              const SizedBox(width: 10),
              const TrustChip(band: TrustBand.provisional, onDark: true),
            ],
          ),
          const SizedBox(height: 12),
          Text(
            'Your biggest customer is your fourth most profitable.',
            style: HqText.verdict.copyWith(color: HqOnDark.primary),
          ),
          const SizedBox(height: 14),
          Row(
            crossAxisAlignment: CrossAxisAlignment.end,
            children: [
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    FittedBox(
                      fit: BoxFit.scaleDown,
                      alignment: Alignment.centerLeft,
                      child: Text(
                        tzs(148000000),
                        maxLines: 1,
                        style: HqText.hero.copyWith(color: HqOnDark.primary),
                      ),
                    ),
                    const SizedBox(height: 6),
                    Text(
                      'gross profit from 310 customers, month to date',
                      style: HqText.label.copyWith(color: HqOnDark.secondary),
                    ),
                  ],
                ),
              ),
              const SizedBox(width: 12),
              Column(
                mainAxisSize: MainAxisSize.min,
                crossAxisAlignment: CrossAxisAlignment.end,
                children: [
                  SizedBox(
                    width: 100,
                    child: Sparkline(
                      values: _profitTrend,
                      color: VizColors.mint,
                      height: 38,
                      strokeWidth: 2,
                    ),
                  ),
                  const SizedBox(height: 6),
                  SizedBox(
                    width: 100,
                    child: Text(
                      '8 months',
                      maxLines: 1,
                      overflow: TextOverflow.ellipsis,
                      textAlign: TextAlign.right,
                      style: HqText.tiny.copyWith(color: HqOnDark.tertiary),
                    ),
                  ),
                ],
              ),
            ],
          ),
          const SizedBox(height: 14),
          Container(height: 1, color: HqOnDark.hairline),
          const SizedBox(height: 12),
          const ComparisonRow(
            label: 'Same month last year',
            value: 'TZS 131M',
            delta: 13.0,
            onDark: true,
          ),
          const SizedBox(height: 8),
          const ComparisonRow(
            label: 'Revenue, same basis',
            value: 'TZS 1.31bn',
            delta: 19.0,
            onDark: true,
          ),
          const SizedBox(height: 10),
          Text(
            'Revenue is growing faster than profit - blended margin slipped 0.6pp, and the '
            'slippage sits in the wholesale accounts.',
            style: HqText.tiny.copyWith(color: HqOnDark.secondary),
          ),
          const SizedBox(height: 12),
          const AsOfLine(
            asOf: '18 Aug 2026, 06:00',
            coverage: '8 of 8 branches',
            onDark: true,
          ),
        ],
      ),
    );
  }
}

// -------------------------------------------------------------- small bits

class _CardHead extends StatelessWidget {
  const _CardHead({required this.title, required this.note});

  final String title;
  final String note;

  @override
  Widget build(BuildContext context) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Text(title, style: HqText.title.copyWith(fontSize: 16.5)),
        const SizedBox(height: 3),
        Text(note, style: HqText.tiny),
      ],
    );
  }
}

/// The muted reading that turns a chart into a sentence an owner can repeat.
class _Reading extends StatelessWidget {
  const _Reading(this.text);

  final String text;

  @override
  Widget build(BuildContext context) {
    return Text(
      text,
      style: HqText.body.copyWith(fontSize: 12.5, color: HqColors.ink3, height: 1.45),
    );
  }
}

// -------------------------------------------------------------------- data
//
// Demo figures for Tembo Group Ltd, month to date. Private to this file so
// they can never collide with the shared mock dataset.

class _Customer {
  const _Customer(this.name, this.shortName, this.revenue, this.profit, this.margin);

  /// Account name as the owner says it out loud.
  final String name;

  /// Short form, for the margin strip.
  final String shortName;

  final double revenue;
  final double profit;

  /// Gross margin, percent.
  final double margin;
}

const _kariakoo = 'Kariakoo Wholesalers';
const _bahari = 'Bahari Foods Ltd';

const _byRevenue = <_Customer>[
  _Customer(_kariakoo, 'Kariakoo', 214000000, 9800000, 4.6),
  _Customer('Mwananchi Distributors', 'Mwananchi', 178000000, 11600000, 6.5),
  _Customer('Sokoine Retail Chain', 'Sokoine', 142000000, 13100000, 9.2),
  _Customer(_bahari, 'Bahari', 121000000, 15400000, 12.7),
  _Customer('Uhuru Hardware Stores', 'Uhuru', 96000000, 6300000, 6.6),
];

const _byProfit = <_Customer>[
  _Customer(_bahari, 'Bahari', 121000000, 15400000, 12.7),
  _Customer('Sokoine Retail Chain', 'Sokoine', 142000000, 13100000, 9.2),
  _Customer('Mwananchi Distributors', 'Mwananchi', 178000000, 11600000, 6.5),
  _Customer(_kariakoo, 'Kariakoo', 214000000, 9800000, 4.6),
  _Customer('Uhuru Hardware Stores', 'Uhuru', 96000000, 6300000, 6.6),
];

/// Company gross profit, TZS millions, the eight months to date.
const _profitTrend = <double>[118, 124, 121, 133, 129, 138, 141, 148];
