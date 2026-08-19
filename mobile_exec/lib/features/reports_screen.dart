import 'package:flutter/material.dart';

import '../app/format.dart';
import '../app/theme.dart';
import '../data/mock.dart';
import '../widgets/charts.dart';
import '../widgets/common.dart';

/// The report catalogue - how an owner browses everything OrbixHQ can answer.
///
/// This screen carries no figure of its own beyond counts. Its job is to make
/// twenty-nine reports feel like eight decisions: Money, Cash, Debt, Stock,
/// Sales, Cost, People, Governance - the order a trading group actually worries
/// in. Four reports are pinned because they are opened every morning.
class ReportsScreen extends StatelessWidget {
  const ReportsScreen({super.key, required this.onOpen});

  /// Fired with the slug of the report the owner tapped, e.g. `branch-league`.
  final void Function(String reportKey) onOpen;

  int get _reportCount =>
      kCatalogue.fold<int>(0, (int a, CatalogueSection s) => a + s.entries.length);

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: HqColors.bg,
      appBar: AppBar(
        title: const Text('Reports', style: HqText.title),
      ),
      body: ListView(
        padding: const EdgeInsets.fromLTRB(20, 4, 20, 40),
        children: <Widget>[
          // 1 - the question, then the shape of the answer
          QuestionHeader(
            question: 'What do you want to look at?',
            subtitle:
                '$_reportCount reports for $kCompanyName, grouped the way the '
                'business runs. Four are pinned because you open them daily.',
          ),
          const SizedBox(height: 16),

          // 2 - the stamp: doctrine 7 applies to the catalogue too
          Row(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: <Widget>[
              Expanded(
                child: AsOfLine(asOf: 'Figures as at $kAsOf', coverage: kCoverage),
              ),
              const SizedBox(width: 12),
              // Bounded so the chip's own Flexible can ellipsize instead of
              // pushing the row past the screen on a 360dp handset.
              ConstrainedBox(
                constraints: const BoxConstraints(maxWidth: 170),
                child: const TrustChip(
                  band: TrustBand.provisional,
                  overrideLabel: kTrustBadge,
                ),
              ),
            ],
          ),
          const SizedBox(height: 18),

          // 3 - search
          const _SearchField(),
          const SizedBox(height: 24),

          // 4 - pinned
          const SectionLabel(text: 'Pinned', trailing: 'Opened every morning'),
          SizedBox(
            // 126 = 26 padding + 28 icon row + 11 gap + two title lines (2 x
            // 16.2) + 4 + one sub line (13.75), with headroom. At 108 the two
            // wrapping titles ("Where the Margin Went", "How Long the Cash
            // Lasts") overflowed the chip by ~7px on the bottom.
            height: 126,
            child: ListView.separated(
              scrollDirection: Axis.horizontal,
              padding: EdgeInsets.zero,
              physics: const BouncingScrollPhysics(),
              itemCount: _pinned.length,
              separatorBuilder: (_, __) => const SizedBox(width: 12),
              itemBuilder: (BuildContext _, int i) => _PinnedCard(
                pin: _pinned[i],
                onTap: () => onOpen(reportKeyFor(_pinned[i].title)),
              ),
            ),
          ),
          const SizedBox(height: 26),

          // 5 - the catalogue, section by section
          for (final CatalogueSection section in kCatalogue) ...<Widget>[
            SectionLabel(
              text: section.title,
              trailing: '${section.entries.length} reports',
            ),
            Padding(
              padding: const EdgeInsets.only(bottom: 10),
              child: Text(
                section.blurb,
                style: HqText.tiny.copyWith(color: HqColors.ink2),
              ),
            ),
            HqCard(
              padding: EdgeInsets.zero,
              child: ClipRRect(
                borderRadius: BorderRadius.circular(15),
                child: Column(
                  mainAxisSize: MainAxisSize.min,
                  children: <Widget>[
                    for (int i = 0; i < section.entries.length; i++) ...<Widget>[
                      if (i > 0)
                        const Divider(
                          height: 1,
                          thickness: 1,
                          color: HqColors.line,
                        ),
                      _ReportRow(
                        entry: section.entries[i],
                        onTap: () => onOpen(reportKeyFor(section.entries[i].screen)),
                      ),
                    ],
                  ],
                ),
              ),
            ),
            const SizedBox(height: 22),
          ],

          // 6 - the residual: what this screen is not telling you
          const ResidualLine(
            text:
                'Every report opens with its own as-of stamp, coverage and trust '
                'band. Consolidation is company-wide, all branches of '
                '$kCompanyName.',
          ),
        ],
      ),
    );
  }
}

// ---------------------------------------------------------------------------
// Report keys
// ---------------------------------------------------------------------------

/// Slug for a report screen name - `Today's Trade` becomes `todays-trade`.
/// The pinned chips and the catalogue rows resolve to the same key, so a
/// report opened from either place lands in one destination.
String reportKeyFor(String screenName) => screenName
    .toLowerCase()
    .replaceAll(RegExp(r"['’]"), '')
    .replaceAll(RegExp(r'[^a-z0-9]+'), '-')
    .replaceAll(RegExp(r'^-+|-+$'), '');

// ---------------------------------------------------------------------------
// Search
// ---------------------------------------------------------------------------

class _SearchField extends StatelessWidget {
  const _SearchField();

  @override
  Widget build(BuildContext context) {
    final int count =
        kCatalogue.fold<int>(0, (int a, CatalogueSection s) => a + s.entries.length);

    return Container(
      height: 52,
      padding: const EdgeInsets.symmetric(horizontal: 14),
      decoration: BoxDecoration(
        color: HqColors.panel,
        borderRadius: BorderRadius.circular(12),
        border: Border.all(color: HqColors.line2),
        boxShadow: HqSurfaces.card,
      ),
      child: Row(
        children: <Widget>[
          const Icon(Icons.search_rounded, size: 20, color: HqColors.ink3),
          const SizedBox(width: 10),
          Expanded(
            child: Text(
              'Search reports',
              maxLines: 1,
              overflow: TextOverflow.ellipsis,
              style: HqText.label.copyWith(color: HqColors.ink3, fontSize: 14.5),
            ),
          ),
          const SizedBox(width: 10),
          Container(
            padding: const EdgeInsets.symmetric(horizontal: 9, vertical: 4),
            decoration: BoxDecoration(
              color: HqColors.panel2,
              borderRadius: BorderRadius.circular(999),
              border: Border.all(color: HqColors.line),
            ),
            child: Text(
              '$count',
              style: const TextStyle(
                fontSize: 11.5,
                fontWeight: FontWeight.w700,
                color: HqColors.ink2,
              ),
            ),
          ),
        ],
      ),
    );
  }
}

// ---------------------------------------------------------------------------
// Pinned reports
// ---------------------------------------------------------------------------

class _Pin {
  const _Pin(this.title, this.sub, this.icon, {this.accent = false});

  final String title;
  final String sub;
  final IconData icon;

  /// The gold rule. Exactly one pin carries it - the one that decides the day.
  final bool accent;
}

/// Titles match their catalogue entry exactly, so both routes give one key.
final List<_Pin> _pinned = <_Pin>[
  const _Pin(
    "Today's Trade",
    'Ahead of last Tuesday',
    Icons.storefront_rounded,
    accent: true,
  ),
  _Pin(
    'Where the Margin Went',
    '${pp(kGrossMarginDeltaPp)} vs last month',
    Icons.call_split_rounded,
  ),
  const _Pin(
    'How Long the Cash Lasts',
    'Floor reached in week 41',
    Icons.hourglass_bottom_rounded,
  ),
  const _Pin(
    'Branch League',
    '$kProblemBranch is dragging',
    Icons.emoji_events_outlined,
  ),
];

class _PinnedCard extends StatelessWidget {
  const _PinnedCard({required this.pin, required this.onTap});

  final _Pin pin;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    final BorderRadius radius = BorderRadius.circular(18);

    return SizedBox(
      width: 182,
      child: Material(
        color: Colors.transparent,
        borderRadius: radius,
        child: InkWell(
          onTap: onTap,
          borderRadius: radius,
          child: Container(
            padding: const EdgeInsets.fromLTRB(14, 13, 13, 13),
            decoration: BoxDecoration(
              gradient: HqSurfaces.brandGradient,
              borderRadius: radius,
              boxShadow: HqSurfaces.card,
            ),
            child: Row(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: <Widget>[
                if (pin.accent) ...<Widget>[
                  Container(
                    width: 3,
                    height: 34,
                    decoration: BoxDecoration(
                      color: HqSurfaces.accent,
                      borderRadius: BorderRadius.circular(2),
                    ),
                  ),
                  const SizedBox(width: 11),
                ],
                Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    // The horizontal list gives every chip a tight height, so
                    // the column fills it: icon pinned top, copy pinned bottom.
                    // The Spacer absorbs the slack when a title is one line and
                    // collapses to zero when it wraps to two.
                    mainAxisSize: MainAxisSize.max,
                    children: <Widget>[
                      Row(
                        children: <Widget>[
                          Container(
                            width: 28,
                            height: 28,
                            alignment: Alignment.center,
                            decoration: BoxDecoration(
                              color: Colors.white.withValues(alpha: 0.14),
                              shape: BoxShape.circle,
                              border: Border.all(color: HqOnDark.hairline),
                            ),
                            child: Icon(
                              pin.icon,
                              size: 15,
                              color: HqOnDark.primary,
                            ),
                          ),
                          const Spacer(),
                          const Icon(
                            Icons.north_east_rounded,
                            size: 14,
                            color: HqOnDark.tertiary,
                          ),
                        ],
                      ),
                      const SizedBox(height: 11),
                      const Spacer(),
                      Text(
                        pin.title,
                        maxLines: 2,
                        overflow: TextOverflow.ellipsis,
                        style: const TextStyle(
                          fontSize: 13.5,
                          fontWeight: FontWeight.w700,
                          height: 1.2,
                          letterSpacing: -0.1,
                          color: HqOnDark.primary,
                        ),
                      ),
                      const SizedBox(height: 4),
                      Text(
                        pin.sub,
                        maxLines: 1,
                        overflow: TextOverflow.ellipsis,
                        style: const TextStyle(
                          fontSize: 11,
                          height: 1.25,
                          color: HqOnDark.secondary,
                        ),
                      ),
                    ],
                  ),
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }
}

// ---------------------------------------------------------------------------
// A catalogue row
// ---------------------------------------------------------------------------

/// Archetype order fixes the hue, so the same kind of report is always the
/// same colour across the catalogue. The pill is direct-labelled - the dot
/// never has to be looked up.
const List<String> _archetypeOrder = <String>[
  'Verdict',
  'Bridge',
  'Trend',
  'Runway',
  'Position',
  'Match',
  'Ageing',
  'Exception',
  'Queue',
  'Coverage',
  'League',
  'Ranking',
];

Color _archetypeDot(String archetype) {
  final int i = _archetypeOrder.indexOf(archetype);
  return i < 0 ? VizColors.muted : VizColors.cat(i);
}

class _ReportRow extends StatelessWidget {
  const _ReportRow({required this.entry, required this.onTap});

  final CatalogueEntry entry;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    return Material(
      color: Colors.transparent,
      child: InkWell(
        onTap: onTap,
        child: Padding(
          padding: const EdgeInsets.fromLTRB(15, 13, 12, 13),
          child: Row(
            crossAxisAlignment: CrossAxisAlignment.center,
            children: <Widget>[
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  mainAxisSize: MainAxisSize.min,
                  children: <Widget>[
                    Text(
                      entry.screen,
                      maxLines: 2,
                      overflow: TextOverflow.ellipsis,
                      style: const TextStyle(
                        fontSize: 14.5,
                        fontWeight: FontWeight.w600,
                        height: 1.25,
                        color: HqColors.ink,
                      ),
                    ),
                    const SizedBox(height: 3),
                    Text(
                      entry.question,
                      maxLines: 2,
                      overflow: TextOverflow.ellipsis,
                      style: HqText.tiny.copyWith(color: HqColors.ink3),
                    ),
                  ],
                ),
              ),
              const SizedBox(width: 10),
              // The pill is the only non-flex child with text in this row, so
              // it is capped: label + gap + chevron can never exceed 164dp,
              // which leaves the title column real width even at 360dp.
              ConstrainedBox(
                constraints: const BoxConstraints(maxWidth: 132),
                child: _ArchetypePill(archetype: entry.archetype),
              ),
              const SizedBox(width: 2),
              const Icon(
                Icons.chevron_right_rounded,
                size: 22,
                color: HqColors.ink3,
              ),
            ],
          ),
        ),
      ),
    );
  }
}

class _ArchetypePill extends StatelessWidget {
  const _ArchetypePill({required this.archetype});

  final String archetype;

  @override
  Widget build(BuildContext context) {
    final Color dot = _archetypeDot(archetype);

    return Container(
      padding: const EdgeInsets.fromLTRB(8, 4, 9, 4),
      decoration: BoxDecoration(
        color: HqColors.panel2,
        borderRadius: BorderRadius.circular(999),
        border: Border.all(color: HqColors.line),
      ),
      child: Row(
        mainAxisSize: MainAxisSize.min,
        children: <Widget>[
          Container(
            width: 5,
            height: 5,
            decoration: BoxDecoration(color: dot, shape: BoxShape.circle),
          ),
          const SizedBox(width: 5),
          Flexible(
            child: Text(
              archetype,
              maxLines: 1,
              overflow: TextOverflow.ellipsis,
              style: const TextStyle(
                fontSize: 10.5,
                fontWeight: FontWeight.w700,
                letterSpacing: 0.3,
                color: HqColors.ink2,
              ),
            ),
          ),
        ],
      ),
    );
  }
}
