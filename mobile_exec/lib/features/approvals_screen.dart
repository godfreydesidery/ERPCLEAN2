import 'package:flutter/material.dart';

import '../app/format.dart';
import '../app/theme.dart';
import '../data/mock.dart';
import '../widgets/charts.dart' show VizColors;
import '../widgets/common.dart';

/// "Waiting on you" - the approval inbox.
///
/// The queue is the one screen where the owner is not reading a number, he is
/// clearing a blockage. So it is ordered by what cannot move: the money he
/// personally has to release sits first, every row says who asked and how long
/// it has waited, and anything over his own limit is flagged in gold.
///
/// A request only becomes "yours" once the level below has signed AND it is
/// over the personal limit - until then it is still with the branch or with
/// finance, and putting it on his desk would be noise.
class ApprovalsScreen extends StatefulWidget {
  const ApprovalsScreen({super.key, required this.onOpen});

  /// Index into [kApprovals] - the row the owner tapped.
  final void Function(int index) onOpen;

  @override
  State<ApprovalsScreen> createState() => _ApprovalsScreenState();
}

enum _Queue { yours, all, decided }

/// Over the personal limit, and everybody below has already signed.
bool _isYours(ApprovalRequest a) =>
    a.aboveThreshold && a.alreadyApprovedBy != 'Nobody yet';

/// '2h' -> 2, '3d' -> 72. The queue ages in hours; the pill keeps the original.
int _ageHours(String age) {
  final n = int.tryParse(age.replaceAll(RegExp(r'[^0-9]'), '')) ?? 0;
  return age.trim().toLowerCase().endsWith('d') ? n * 24 : n;
}

class _ApprovalsScreenState extends State<ApprovalsScreen> {
  _Queue _queue = _Queue.yours;

  List<int> get _visible {
    switch (_queue) {
      case _Queue.yours:
        return <int>[
          for (var i = 0; i < kApprovals.length; i++)
            if (_isYours(kApprovals[i])) i,
        ];
      case _Queue.all:
        return <int>[for (var i = 0; i < kApprovals.length; i++) i];
      case _Queue.decided:
        return const <int>[];
    }
  }

  @override
  Widget build(BuildContext context) {
    final yoursCount = kApprovals.where(_isYours).length;

    return Scaffold(
      backgroundColor: HqColors.bg,
      appBar: AppBar(
        title: const Text(
          'Waiting on you',
          style: TextStyle(fontSize: 17, fontWeight: FontWeight.w700),
        ),
        bottom: PreferredSize(
          preferredSize: const Size.fromHeight(60),
          child: Container(
            padding: const EdgeInsets.fromLTRB(20, 0, 20, 12),
            decoration: const BoxDecoration(
              color: HqColors.panel,
              border: Border(bottom: BorderSide(color: HqColors.line)),
            ),
            child: _Segmented(
              labels: <String>[
                'Yours ($yoursCount)',
                'All (${kApprovals.length})',
                'Decided',
              ],
              index: _Queue.values.indexOf(_queue),
              onChanged: (int i) => setState(() => _queue = _Queue.values[i]),
            ),
          ),
        ),
      ),
      body: _queue == _Queue.decided ? _decidedBody() : _queueBody(),
    );
  }

  // -------------------------------------------------------------------------

  Widget _queueBody() {
    final visible = _visible;
    final yours = _queue == _Queue.yours;
    final total = visible.fold<num>(
      0,
      (num sum, int i) => sum + kApprovals[i].amount,
    );

    return ListView(
      padding: const EdgeInsets.fromLTRB(20, 16, 20, 34),
      children: <Widget>[
        const _QueueHero(),
        const SizedBox(height: 22),
        SectionLabel(
          text: yours
              ? '${visible.length} things need you'
              : '${visible.length} decisions waiting',
          trailing:
              yours ? '${tzs(total)} ON YOUR DESK' : '${tzs(total)} IN TOTAL',
        ),
        const SizedBox(height: 2),
        for (var k = 0; k < visible.length; k++) ...<Widget>[
          if (k > 0) const SizedBox(height: 12),
          _ApprovalCard(
            request: kApprovals[visible[k]],
            onTap: () => widget.onOpen(visible[k]),
          ),
        ],
        const SizedBox(height: 16),
        ResidualLine(
          text: yours
              ? 'Four more requests are in the queue but still sit with the branch '
                  'or with finance - they reach your desk only once the level below '
                  'has signed. Tap All to see them.'
              : 'Three of these are inside your TZS 20M limit and would clear at '
                  'branch level; they are listed so that nothing decides itself. '
                  'Requests under TZS 2M never reach this screen.',
        ),
      ],
    );
  }

  Widget _decidedBody() {
    return ListView(
      padding: const EdgeInsets.fromLTRB(20, 24, 20, 34),
      children: const <Widget>[
        SectionLabel(text: 'Decided today', trailing: 'NOTHING OUTSTANDING'),
        SizedBox(height: 4),
        _EmptyState(),
        SizedBox(height: 16),
        ResidualLine(
          text:
              'Every decision is kept with your name, the amount, and the limit it '
              'was measured against. Nothing you signed today is still open.',
        ),
      ],
    );
  }
}

// ---------------------------------------------------------------------------
// The sticky filter
// ---------------------------------------------------------------------------

class _Segmented extends StatelessWidget {
  const _Segmented({
    required this.labels,
    required this.index,
    required this.onChanged,
  });

  final List<String> labels;
  final int index;
  final ValueChanged<int> onChanged;

  @override
  Widget build(BuildContext context) {
    return Container(
      height: 40,
      padding: const EdgeInsets.all(4),
      decoration: BoxDecoration(
        color: HqColors.panel2,
        borderRadius: BorderRadius.circular(12),
        border: Border.all(color: HqColors.line),
      ),
      child: Row(
        children: <Widget>[
          for (var i = 0; i < labels.length; i++)
            Expanded(
              child: _Segment(
                label: labels[i],
                selected: i == index,
                onTap: () => onChanged(i),
              ),
            ),
        ],
      ),
    );
  }
}

class _Segment extends StatelessWidget {
  const _Segment({
    required this.label,
    required this.selected,
    required this.onTap,
  });

  final String label;
  final bool selected;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    final radius = BorderRadius.circular(9);

    return Material(
      color: Colors.transparent,
      borderRadius: radius,
      child: InkWell(
        onTap: onTap,
        borderRadius: radius,
        child: AnimatedContainer(
          duration: const Duration(milliseconds: 160),
          curve: Curves.easeOut,
          alignment: Alignment.center,
          decoration: BoxDecoration(
            color: selected ? HqColors.panel : Colors.transparent,
            borderRadius: radius,
            border: Border.all(
              color: selected ? HqColors.line : Colors.transparent,
            ),
            boxShadow: selected
                ? <BoxShadow>[
                    BoxShadow(
                      color: const Color(0xFF0F172A).withValues(alpha: 0.07),
                      blurRadius: 6,
                      offset: const Offset(0, 2),
                    ),
                  ]
                : const <BoxShadow>[],
          ),
          child: Text(
            label,
            maxLines: 1,
            overflow: TextOverflow.ellipsis,
            style: TextStyle(
              fontSize: 12.5,
              fontWeight: selected ? FontWeight.w700 : FontWeight.w600,
              color: selected ? HqColors.ink : HqColors.ink3,
            ),
          ),
        ),
      ),
    );
  }
}

// ---------------------------------------------------------------------------
// The summary strip - one hero number, then the two facts that qualify it
// ---------------------------------------------------------------------------

class _QueueHero extends StatelessWidget {
  const _QueueHero();

  @override
  Widget build(BuildContext context) {
    final aboveSum = kApprovals
        .where((ApprovalRequest a) => a.aboveThreshold)
        .fold<num>(0, (num s, ApprovalRequest a) => s + a.amount);

    num sumWhere(bool Function(int hours) test) => kApprovals
        .where((ApprovalRequest a) => test(_ageHours(a.age)))
        .fold<num>(0, (num s, ApprovalRequest a) => s + a.amount);

    final today = sumWhere((int h) => h < 24);
    final aDay = sumWhere((int h) => h >= 24 && h < 72);
    final older = sumWhere((int h) => h >= 72);

    return HeroCard(
      question: 'What cannot move until I decide?',
      verdict: 'Seven decisions are parked. Three of them are yours, and the '
          'weekly supplier run is the one that has to leave today.',
      heroValue: tzs(kApprovalsTotalValue),
      heroSub: 'held in ${kApprovals.length} requests, '
          '$kApprovalsAboveThreshold of them above your TZS 20M limit',
      comparisons: const <Widget>[
        ComparisonRow(
          label: 'Your median time to decide, against 7h in July',
          value: '9h',
          delta: 28.6,
          higherIsBetter: false,
          onDark: true,
        ),
      ],
      chart: _QueueStats(
        aboveCount: kApprovalsAboveThreshold,
        aboveSum: aboveSum,
        oldest: kApprovalsOldest,
        today: today,
        aDay: aDay,
        older: older,
      ),
      asOf: 'As of $kAsOf · $kCoverage',
      band: TrustBand.posted,
      trustLabel: 'LIVE QUEUE',
    );
  }
}

class _QueueStats extends StatelessWidget {
  const _QueueStats({
    required this.aboveCount,
    required this.aboveSum,
    required this.oldest,
    required this.today,
    required this.aDay,
    required this.older,
  });

  final int aboveCount;
  final num aboveSum;
  final String oldest;
  final num today;
  final num aDay;
  final num older;

  // Older money is heavier ink, so the stale sliver is what the eye lands on.
  static const Color _shadeNew = Color(0x47FFFFFF);
  static const Color _shadeDay = Color(0x94FFFFFF);
  static const Color _shadeOld = Color(0xF2FFFFFF);

  int _flex(num v) {
    final total = today + aDay + older;
    if (total <= 0) return 1;
    return (v / total * 100).round().clamp(1, 100);
  }

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.fromLTRB(14, 13, 14, 14),
      decoration: BoxDecoration(
        color: Colors.white.withValues(alpha: 0.06),
        borderRadius: BorderRadius.circular(14),
        border: Border.all(color: HqOnDark.hairline),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        mainAxisSize: MainAxisSize.min,
        children: <Widget>[
          Row(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: <Widget>[
              Expanded(
                child: _DarkStat(
                  label: 'ABOVE YOUR LIMIT',
                  value: '$aboveCount requests',
                  note: '${tzs(aboveSum)} of the ${tzs(kApprovalsTotalValue)}',
                ),
              ),
              const SizedBox(
                height: 54,
                child: VerticalDivider(
                  width: 25,
                  thickness: 1,
                  color: HqOnDark.hairline,
                ),
              ),
              Expanded(
                child: _DarkStat(
                  label: 'OLDEST',
                  value: oldest == '3d' ? '3 days' : oldest,
                  note: 'against your 8h promise',
                ),
              ),
            ],
          ),
          const SizedBox(height: 14),
          const Divider(height: 1, thickness: 1, color: HqOnDark.hairline),
          const SizedBox(height: 13),
          ClipRRect(
            borderRadius: BorderRadius.circular(6),
            child: SizedBox(
              height: 10,
              child: Row(
                children: <Widget>[
                  Expanded(
                    flex: _flex(today),
                    child: const ColoredBox(color: _shadeNew),
                  ),
                  const SizedBox(width: 2),
                  Expanded(
                    flex: _flex(aDay),
                    child: const ColoredBox(color: _shadeDay),
                  ),
                  const SizedBox(width: 2),
                  Expanded(
                    flex: _flex(older),
                    child: const ColoredBox(color: _shadeOld),
                  ),
                ],
              ),
            ),
          ),
          const SizedBox(height: 10),
          Wrap(
            spacing: 14,
            runSpacing: 6,
            children: <Widget>[
              _AgeKey(color: _shadeNew, label: 'today', value: tzsBare(today)),
              _AgeKey(color: _shadeDay, label: 'a day', value: tzsBare(aDay)),
              _AgeKey(color: _shadeOld, label: '3 days', value: tzsBare(older)),
            ],
          ),
        ],
      ),
    );
  }
}

class _DarkStat extends StatelessWidget {
  const _DarkStat({
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
      children: <Widget>[
        Text(
          label,
          maxLines: 1,
          overflow: TextOverflow.ellipsis,
          style: const TextStyle(
            fontSize: 10,
            fontWeight: FontWeight.w700,
            letterSpacing: 0.8,
            color: HqOnDark.tertiary,
          ),
        ),
        const SizedBox(height: 6),
        FittedBox(
          fit: BoxFit.scaleDown,
          alignment: Alignment.centerLeft,
          child: Text(
            value,
            maxLines: 1,
            style: const TextStyle(
              fontSize: 17,
              fontWeight: FontWeight.w700,
              letterSpacing: -0.2,
              color: HqOnDark.primary,
            ),
          ),
        ),
        const SizedBox(height: 3),
        Text(
          note,
          maxLines: 2,
          overflow: TextOverflow.ellipsis,
          style: const TextStyle(
            fontSize: 11,
            height: 1.3,
            color: HqOnDark.secondary,
          ),
        ),
      ],
    );
  }
}

class _AgeKey extends StatelessWidget {
  const _AgeKey({
    required this.color,
    required this.label,
    required this.value,
  });

  final Color color;
  final String label;
  final String value;

  @override
  Widget build(BuildContext context) {
    return Row(
      mainAxisSize: MainAxisSize.min,
      children: <Widget>[
        Container(
          width: 8,
          height: 8,
          decoration: BoxDecoration(
            color: color,
            borderRadius: BorderRadius.circular(2.5),
          ),
        ),
        const SizedBox(width: 6),
        Text(
          label,
          style: const TextStyle(fontSize: 11, color: HqOnDark.tertiary),
        ),
        const SizedBox(width: 5),
        Text(
          value,
          style: const TextStyle(
            fontSize: 11,
            fontWeight: FontWeight.w700,
            color: HqOnDark.primary,
          ),
        ),
      ],
    );
  }
}

// ---------------------------------------------------------------------------
// One request
// ---------------------------------------------------------------------------

class _ApprovalCard extends StatelessWidget {
  const _ApprovalCard({required this.request, required this.onTap});

  final ApprovalRequest request;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    final meta = _DocMeta.of(request.docType);
    final radius = BorderRadius.circular(16);
    final hasMoney = request.amount > 0;

    return Material(
      color: Colors.transparent,
      borderRadius: radius,
      child: InkWell(
        onTap: onTap,
        borderRadius: radius,
        child: Container(
          padding: const EdgeInsets.fromLTRB(12, 13, 10, 13),
          decoration: BoxDecoration(
            color: HqColors.panel,
            borderRadius: radius,
            border: Border.all(color: HqColors.line),
            boxShadow: HqSurfaces.card,
          ),
          child: Row(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: <Widget>[
              _TypeIcon(meta: meta),
              const SizedBox(width: 12),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  mainAxisSize: MainAxisSize.min,
                  children: <Widget>[
                    Row(
                      children: <Widget>[
                        Flexible(
                          child: Text(
                            meta.label,
                            maxLines: 1,
                            overflow: TextOverflow.ellipsis,
                            style: TextStyle(
                              fontSize: 10.5,
                              fontWeight: FontWeight.w700,
                              letterSpacing: 0.8,
                              color: meta.color,
                            ),
                          ),
                        ),
                        if (request.aboveThreshold) ...<Widget>[
                          const SizedBox(width: 8),
                          const _AboveLimitPill(),
                        ],
                      ],
                    ),
                    const SizedBox(height: 6),
                    Text(
                      request.title,
                      maxLines: 2,
                      overflow: TextOverflow.ellipsis,
                      style: const TextStyle(
                        fontSize: 15,
                        fontWeight: FontWeight.w700,
                        height: 1.25,
                        color: HqColors.ink,
                      ),
                    ),
                    const SizedBox(height: 7),
                    Text(
                      hasMoney ? tzs(request.amount) : 'No money involved',
                      maxLines: 1,
                      overflow: TextOverflow.ellipsis,
                      style: TextStyle(
                        fontSize: hasMoney ? 18 : 15,
                        fontWeight: FontWeight.w700,
                        letterSpacing: -0.3,
                        color: hasMoney ? HqColors.ink : HqColors.ink3,
                      ),
                    ),
                    const SizedBox(height: 11),
                    Row(
                      children: <Widget>[
                        _Initials(
                          text: ExceptionTile.initialsOf(request.requester),
                          tint: meta.color,
                        ),
                        const SizedBox(width: 8),
                        Expanded(
                          child: Text(
                            '${request.requester} · ${request.branch}',
                            maxLines: 1,
                            overflow: TextOverflow.ellipsis,
                            style: const TextStyle(
                              fontSize: 12,
                              fontWeight: FontWeight.w600,
                              color: HqColors.ink2,
                            ),
                          ),
                        ),
                        const SizedBox(width: 8),
                        _AgePill(age: request.age),
                      ],
                    ),
                  ],
                ),
              ),
              const SizedBox(width: 2),
              const Padding(
                padding: EdgeInsets.only(top: 2),
                child: Icon(
                  Icons.chevron_right_rounded,
                  size: 22,
                  color: HqColors.ink3,
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }
}

class _TypeIcon extends StatelessWidget {
  const _TypeIcon({required this.meta});

  final _DocMeta meta;

  @override
  Widget build(BuildContext context) {
    return Container(
      width: 46,
      height: 46,
      alignment: Alignment.center,
      decoration: BoxDecoration(
        color: meta.color.withValues(alpha: 0.10),
        borderRadius: BorderRadius.circular(13),
        border: Border.all(color: meta.color.withValues(alpha: 0.20)),
      ),
      child: Icon(meta.icon, size: 22, color: meta.color),
    );
  }
}

class _AboveLimitPill extends StatelessWidget {
  const _AboveLimitPill();

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 4),
      decoration: BoxDecoration(
        color: HqSurfaces.accent.withValues(alpha: 0.13),
        borderRadius: BorderRadius.circular(999),
        border: Border.all(color: HqSurfaces.accent.withValues(alpha: 0.42)),
      ),
      child: const Text(
        'ABOVE YOUR LIMIT',
        maxLines: 1,
        overflow: TextOverflow.ellipsis,
        style: TextStyle(
          fontSize: 9.5,
          fontWeight: FontWeight.w700,
          letterSpacing: 0.6,
          color: Color(0xFF8A6516),
        ),
      ),
    );
  }
}

class _Initials extends StatelessWidget {
  const _Initials({required this.text, required this.tint});

  final String text;
  final Color tint;

  @override
  Widget build(BuildContext context) {
    return Container(
      width: 22,
      height: 22,
      alignment: Alignment.center,
      decoration: BoxDecoration(
        color: tint.withValues(alpha: 0.10),
        shape: BoxShape.circle,
        border: Border.all(color: tint.withValues(alpha: 0.22)),
      ),
      child: Text(
        text,
        style: TextStyle(
          fontSize: 9.5,
          fontWeight: FontWeight.w700,
          letterSpacing: 0.2,
          color: tint,
        ),
      ),
    );
  }
}

/// How long it has sat. Amber after a day, red after three - a decision that
/// does not get made is a business that does not move.
class _AgePill extends StatelessWidget {
  const _AgePill({required this.age});

  final String age;

  @override
  Widget build(BuildContext context) {
    final hours = _ageHours(age);
    final Color ink;
    final Color fill;
    if (hours >= 72) {
      ink = HqColors.bad;
      fill = HqColors.badSoft;
    } else if (hours >= 24) {
      ink = HqColors.warn;
      fill = HqColors.warnSoft;
    } else {
      ink = HqColors.ink3;
      fill = HqColors.panel2;
    }

    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 9, vertical: 4),
      decoration: BoxDecoration(
        color: fill,
        borderRadius: BorderRadius.circular(999),
        border: Border.all(color: ink.withValues(alpha: 0.25)),
      ),
      child: Text(
        age,
        maxLines: 1,
        style: TextStyle(
          fontSize: 11,
          fontWeight: FontWeight.w700,
          letterSpacing: 0.2,
          color: ink,
        ),
      ),
    );
  }
}

class _DocMeta {
  const _DocMeta(this.label, this.icon, this.color);

  final String label;
  final IconData icon;
  final Color color;

  static _DocMeta of(String docType) => switch (docType) {
        'PAYMENT_BATCH' => const _DocMeta(
            'PAYMENT RUN',
            Icons.payments_rounded,
            HqColors.brand,
          ),
        'PURCHASE_ORDER' => const _DocMeta(
            'PURCHASE ORDER',
            Icons.local_shipping_rounded,
            VizColors.cat1,
          ),
        'CREDIT_LIMIT' => const _DocMeta(
            'CREDIT LIMIT',
            Icons.credit_card_rounded,
            VizColors.cat2,
          ),
        'SALES_ORDER' => const _DocMeta(
            'SALES ORDER',
            Icons.shopping_bag_rounded,
            VizColors.cat3,
          ),
        'DISCOUNT' => const _DocMeta(
            'DISCOUNT',
            Icons.sell_rounded,
            HqColors.warn,
          ),
        'JOURNAL' => const _DocMeta(
            'JOURNAL',
            Icons.swap_horiz_rounded,
            HqColors.estimated,
          ),
        'LEAVE' => const _DocMeta(
            'LEAVE',
            Icons.event_available_rounded,
            VizColors.cat4,
          ),
        _ => const _DocMeta(
            'REQUEST',
            Icons.description_outlined,
            HqColors.ink2,
          ),
      };
}

// ---------------------------------------------------------------------------
// Nothing left to decide
// ---------------------------------------------------------------------------

class _EmptyState extends StatelessWidget {
  const _EmptyState();

  @override
  Widget build(BuildContext context) {
    return const HqCard(
      padding: EdgeInsets.symmetric(horizontal: 24, vertical: 44),
      child: Column(
        mainAxisSize: MainAxisSize.min,
        children: <Widget>[
          _EmptyMark(),
          SizedBox(height: 20),
          Text(
            'Nothing waiting on you.',
            textAlign: TextAlign.center,
            style: HqText.verdict,
          ),
          SizedBox(height: 6),
          Text(
            'Cleared at 14:20.',
            textAlign: TextAlign.center,
            style: HqText.body,
          ),
        ],
      ),
    );
  }
}

class _EmptyMark extends StatelessWidget {
  const _EmptyMark();

  @override
  Widget build(BuildContext context) {
    return Container(
      width: 84,
      height: 84,
      alignment: Alignment.center,
      decoration: BoxDecoration(
        color: HqColors.panel2,
        shape: BoxShape.circle,
        border: Border.all(color: HqColors.line),
      ),
      child: const Icon(Icons.check_rounded, size: 42, color: HqColors.ink3),
    );
  }
}
