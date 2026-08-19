import 'package:flutter/material.dart';

import '../app/format.dart';
import '../app/theme.dart';
import '../widgets/charts.dart';
import '../widgets/common.dart';

/// Who runs a branch, and where it sits. Kept private to this file so the
/// screen compiles standalone even before the shared dataset lands.
class _BranchProfile {
  const _BranchProfile(this.zone, this.manager, this.managerFull);
  final String zone;
  final String manager;
  final String managerFull;

  /// Family name only - "P. Kimaro" becomes "Kimaro". A pill button on a 360px
  /// phone has no room for an initial as well.
  String get surname => manager.split(' ').last;
}

const _defaultProfile = _BranchProfile('Northern zone', 'P. Kimaro', 'Paulina Kimaro');

const Map<String, _BranchProfile> _profiles = <String, _BranchProfile>{
  'Arusha': _defaultProfile,
  'Moshi': _BranchProfile('Northern zone', 'E. Mollel', 'Elibariki Mollel'),
  'Dar es Salaam': _BranchProfile('Coast zone', 'H. Nyagawa', 'Hamisi Nyagawa'),
  'Kariakoo': _BranchProfile('Coast zone', 'S. Juma', 'Salma Juma'),
  'Tanga': _BranchProfile('Coast zone', 'R. Mkumbo', 'Restituta Mkumbo'),
  'Mwanza': _BranchProfile('Lake zone', 'C. Masanja', 'Charles Masanja'),
  'Dodoma': _BranchProfile('Central zone', 'A. Sanga', 'Anna Sanga'),
  'Mbeya': _BranchProfile('Southern zone', 'J. Mwakalinga', 'Joyce Mwakalinga'),
};

// ---------------------------------------------------------------------------
// The month, in figures. One branch, this month, against its own history.
// ---------------------------------------------------------------------------
const double _thisMonth = 38.4e6;
const double _lastMonth = 49.2e6;
const double _plan = 49.0e6;
const double _companyAverageBranch = 52.6e6;

/// Jan to Aug. Eight closed periods standing behind the headline.
const List<double> _trend = <double>[
  44.1e6,
  47.8e6,
  46.2e6,
  51.0e6,
  50.4e6,
  52.3e6,
  49.2e6,
  38.4e6,
];

/// The bridge has to add up: 49.2 - 4.8 - 3.6 - 3.6 + 1.2 = 38.4.
const double _lostCustomer = -4.8e6;
const double _productGroup = -3.6e6;
const double _priceGivenAway = -3.6e6;
const double _newBusiness = 1.2e6;

/// The branch drill-down. This screen has one job: land the owner on a CAUSE
/// and a NAME. It deliberately shows no documents; the web app does documents.
class BranchDetailScreen extends StatelessWidget {
  const BranchDetailScreen({super.key, required this.branchName});

  final String branchName;

  _BranchProfile get _profile => _profiles[branchName] ?? _defaultProfile;

  double get _vsLastMonth => (_thisMonth - _lastMonth) / _lastMonth * 100;
  double get _vsCompany => (_thisMonth - _companyAverageBranch) / _companyAverageBranch * 100;
  double get _belowPlan => _plan - _thisMonth;

  void _say(BuildContext context, String message) {
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
    final p = _profile;

    return Scaffold(
      backgroundColor: HqColors.bg,
      appBar: AppBar(
        titleSpacing: 4,
        title: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          mainAxisSize: MainAxisSize.min,
          children: [
            // Both lines stay single-line: a wrapped branch name would push
            // the sub-line past the 56px toolbar and overflow the AppBar.
            Text(
              branchName,
              maxLines: 1,
              overflow: TextOverflow.ellipsis,
              style: HqText.title,
            ),
            const SizedBox(height: 2),
            Text(
              '${p.zone}  ·  ${p.manager}',
              maxLines: 1,
              overflow: TextOverflow.ellipsis,
              style: HqText.tiny,
            ),
          ],
        ),
      ),
      body: SafeArea(
        top: false,
        child: ListView(
          padding: const EdgeInsets.fromLTRB(20, 12, 20, 32),
          children: [
            // 1 - the verdict, the one number, and what it is measured against.
            HeroCard(
              question: 'Why is $branchName down?',
              verdict:
                  'Down 22% — two thirds of it is one product group and one lost customer.',
              heroValue: tzs(_thisMonth),
              heroSub: 'this month, against a ${tzsBare(_plan)} plan',
              band: TrustBand.provisional,
              trustLabel: 'PROVISIONAL',
              asOf: 'as at 18 Aug 2026, 06:10',
              comparisons: [
                ComparisonRow(
                  label: '$branchName last month',
                  value: tzs(_lastMonth),
                  delta: _vsLastMonth,
                  higherIsBetter: true,
                  onDark: true,
                ),
                ComparisonRow(
                  label: 'Average branch, company-wide',
                  value: tzs(_companyAverageBranch),
                  delta: _vsCompany,
                  higherIsBetter: true,
                  onDark: true,
                ),
              ],
              chart: _HeroTrend(belowPlan: _belowPlan),
            ),

            const SizedBox(height: 18),

            // 2 - the cause, as a bridge. Never a transaction list.
            HqCard(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  const Text('What moved it', style: HqText.title),
                  const SizedBox(height: 6),
                  const Text(
                    'Last month taken apart into this month. Two bars carry 70% of the fall.',
                    style: HqText.body,
                  ),
                  const SizedBox(height: 16),
                  WaterfallBridge(
                    height: 196,
                    steps: [
                      BridgeStep('Last month', _lastMonth, isTotal: true),
                      BridgeStep('Kilimanjaro Hardware', _lostCustomer),
                      BridgeStep('Cement & binders', _productGroup),
                      BridgeStep('Price given away', _priceGivenAway),
                      BridgeStep('New accounts', _newBusiness),
                      BridgeStep('This month', _thisMonth, isTotal: true),
                    ],
                  ),
                  const SizedBox(height: 14),
                  ResidualLine(
                    text:
                        'Residual: movements under TZS 0.5M are folded into price given away. '
                        'Nothing else in the branch moved by more than TZS 0.3M.',
                  ),
                ],
              ),
            ),

            const SizedBox(height: 18),

            // 3 - the four numbers a branch manager is held to.
            HqCard(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  const Text('The manager’s numbers', style: HqText.title),
                  const SizedBox(height: 6),
                  Text(
                    'Each against the same month last year. ${p.managerFull} answers for all four.',
                    style: HqText.body,
                  ),
                  const SizedBox(height: 16),
                  Row(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Expanded(
                        child: StatTile(
                          label: 'Margin',
                          value: '21.4%',
                          delta: '-2.6pp',
                          deltaGood: false,
                          spark: [24.5, 24.1, 23.8, 23.9, 23.2, 22.6, 22.0, 21.4],
                        ),
                      ),
                      const SizedBox(width: 12),
                      Expanded(
                        child: StatTile(
                          label: 'Debtors',
                          value: 'TZS 61.2M',
                          delta: '+14%',
                          deltaGood: false,
                          spark: [48.0, 49.6, 51.2, 52.9, 54.8, 57.1, 59.0, 61.2],
                        ),
                      ),
                    ],
                  ),
                  const SizedBox(height: 12),
                  Row(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Expanded(
                        child: StatTile(
                          label: 'Stock cover',
                          value: '9.8 weeks',
                          delta: '+2.1 wks',
                          deltaGood: false,
                          spark: [6.9, 7.1, 7.4, 7.8, 8.3, 8.9, 9.4, 9.8],
                        ),
                      ),
                      const SizedBox(width: 12),
                      Expanded(
                        child: StatTile(
                          label: 'Staff cost',
                          value: 'TZS 11.6M',
                          delta: '-4%',
                          deltaGood: true,
                          spark: [12.1, 12.3, 12.2, 12.0, 11.9, 11.8, 11.7, 11.6],
                        ),
                      ),
                    ],
                  ),
                ],
              ),
            ),

            const SizedBox(height: 22),

            // 4 - exception-led. Two things, one name on each, one tap each.
            SectionLabel(
              text: '2 THINGS NEED YOU',
              trailing: '${tzsBare(8.4e6)} at stake',
            ),
            const SizedBox(height: 10),
            ExceptionTile(
              severe: true,
              title: 'Kilimanjaro Hardware has stopped buying',
              detail:
                  'No order in 41 days, after three steady years at about TZS 4.8M a month. '
                  'Nobody from $branchName has called them.',
              amount: tzs(_lostCustomer.abs()),
              owner: p.managerFull,
              // Short pill labels on purpose: ExceptionTile lays the avatar,
              // the owner's name and this button on ONE row, and the button is
              // not flexible - a long label pushes the row off a 360px screen.
              actionLabel: 'See customer',
              onAction: () => _say(
                context,
                'Kilimanjaro Hardware — last order 8 Jul 2026. Demo only.',
              ),
            ),
            const SizedBox(height: 10),
            ExceptionTile(
              title: 'Cement & binders sold more and earned less',
              detail:
                  'Volume up 12%, margin down TZS 3.6M. Nine deals were priced below the '
                  'company floor, all approved inside the branch.',
              amount: tzs(_productGroup.abs()),
              owner: p.managerFull,
              actionLabel: 'Call ${p.surname}',
              onAction: () => _say(
                context,
                'Calling ${p.managerFull} — demo only, no call is placed.',
              ),
            ),

            const SizedBox(height: 20),

            AsOfLine(
              asOf: '18 Aug 2026, 06:10',
              coverage:
                  '$branchName only · 100% of posted sales, August receipts still provisional',
            ),
            const SizedBox(height: 10),
            const Text(
              'Deliberately not shown here: invoices. Open the web app for documents.',
              style: HqText.tiny,
            ),

            const SizedBox(height: 22),
            DrillButton(
              label: 'Call ${p.manager}',
              onTap: () => _say(
                context,
                'Calling ${p.managerFull}, ${p.zone.toLowerCase()} — demo only.',
              ),
            ),
          ],
        ),
      ),
    );
  }
}

/// Eight months of branch turnover on the dark hero, plus the single gold
/// figure that says how far short of plan the month landed.
class _HeroTrend extends StatelessWidget {
  const _HeroTrend({required this.belowPlan});

  final double belowPlan;

  @override
  Widget build(BuildContext context) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Sparkline(
          values: _trend,
          color: VizColors.mint,
          height: 48,
          showLastDot: true,
          strokeWidth: 2.4,
        ),
        const SizedBox(height: 10),
        // Full-width Wrap, not a Row with a Spacer: on a wide phone the two
        // sit apart exactly as before, and on a narrow one the gold pill drops
        // the period caption onto its own line instead of overflowing.
        SizedBox(
          width: double.infinity,
          child: Wrap(
            alignment: WrapAlignment.spaceBetween,
            crossAxisAlignment: WrapCrossAlignment.center,
            spacing: 12,
            runSpacing: 8,
            children: [
              Container(
                padding:
                    const EdgeInsets.symmetric(horizontal: 10, vertical: 5),
                decoration: BoxDecoration(
                  color: HqSurfaces.accent.withValues(alpha: 0.16),
                  borderRadius: BorderRadius.circular(999),
                  border: Border.all(
                    color: HqSurfaces.accent.withValues(alpha: 0.45),
                  ),
                ),
                child: Text(
                  'Short of plan by ${tzs(belowPlan)}',
                  maxLines: 2,
                  overflow: TextOverflow.ellipsis,
                  style: const TextStyle(
                    fontSize: 11.5,
                    fontWeight: FontWeight.w700,
                    height: 1.25,
                    color: HqSurfaces.accent,
                    letterSpacing: 0.2,
                  ),
                ),
              ),
              const Text(
                'Jan to Aug',
                maxLines: 1,
                overflow: TextOverflow.ellipsis,
                style: TextStyle(fontSize: 11.5, color: HqOnDark.tertiary),
              ),
            ],
          ),
        ),
      ],
    );
  }
}
