import 'package:flutter/material.dart';

import '../app/format.dart';
import '../app/theme.dart';
import '../widgets/charts.dart';
import '../widgets/common.dart';

/// Alerts - the inbox of everything OrbixHQ pushed at the owner.
///
/// Doctrine notes for this screen:
///  * The push IS the product. An owner who reads only the 06:30 brief and
///    never opens the app is a satisfied owner, and the screen says so.
///  * Colour is severity, never sentiment: a paid overdue balance is green,
///    a cash floor breach is red, gold is spent once (the morning brief).
///  * Every alert that needs a human names that human and offers one tap.
class NotificationsScreen extends StatefulWidget {
  const NotificationsScreen({super.key});

  @override
  State<NotificationsScreen> createState() => _NotificationsScreenState();
}

// ---------------------------------------------------------------------------
// Demo data - private to this file so it can never collide with lib/data.
// ---------------------------------------------------------------------------

enum _Sev { brief, bad, warn, good }

class _Alert {
  const _Alert({
    required this.day,
    required this.time,
    required this.sev,
    required this.icon,
    required this.title,
    required this.body,
    this.owner,
    this.action,
    this.unread = false,
    this.spark,
    this.sparkCaption,
    this.footnote,
  });

  /// 0 = today, 1 = yesterday, 2 = earlier this week.
  final int day;
  final String time;
  final _Sev sev;
  final IconData icon;
  final String title;
  final String body;
  final String? owner;
  final String? action;
  final bool unread;
  final List<double>? spark;
  final String? sparkCaption;
  final String? footnote;
}

const _dayNames = ['Today', 'Yesterday', 'Earlier this week'];

/// Sales, last seven trading days, TZS millions - the shape behind the brief.
const _briefSpark = <double>[33.8, 36.1, 35.2, 38.4, 37.0, 39.6, 41.2];

/// Alerts actually sent per day, same seven days. Today is still filling up.
const _sentPerDay = <double>[6, 4, 7, 3, 5, 9, 4];
const _sentLabels = <String>['Th', 'Fr', 'Sa', 'Su', 'Mo', 'Tu', 'We'];

final List<_Alert> _alerts = [
  // ---- Today ------------------------------------------------------------
  _Alert(
    day: 0,
    time: '06:30',
    sev: _Sev.brief,
    icon: Icons.wb_twilight,
    title: 'Your morning brief',
    body:
        'Yesterday: Sales ${tzs(41200000)} (+8% vs last Tue) - Cash ${tzs(12400000)} - 2 approvals waiting',
    footnote: 'If you read only this and never open the app, that is fine.',
    spark: _briefSpark,
    sparkCaption: '7 trading days - ${tzs(37200000)} average',
    action: 'Open the day',
    unread: true,
  ),
  const _Alert(
    day: 0,
    time: '2h',
    sev: _Sev.warn,
    icon: Icons.how_to_reg_outlined,
    title: 'An approval sits above your limit',
    body: 'Mwanza purchase order TZS 18.6M, 4.6M over Neema’s ceiling, waiting 2 days.',
    owner: 'Neema Kimaro · Purchasing',
    action: 'Approve',
    unread: true,
  ),
  const _Alert(
    day: 0,
    time: '4h',
    sev: _Sev.bad,
    icon: Icons.account_balance_wallet_outlined,
    title: 'Cash cover drops under your floor',
    body: 'Kariakoo reaches 11 days of cover on Friday. Your floor is 14 days.',
    owner: 'Grace Shirima · Finance',
    action: 'See cash',
    unread: true,
  ),
  const _Alert(
    day: 0,
    time: '5h',
    sev: _Sev.bad,
    icon: Icons.trending_down,
    title: 'A sale left the yard below cost',
    body: '18 bags Simba Cement out of Arusha at 3.4% under landed cost.',
    owner: 'Joseph Mollel · Arusha',
    action: 'Review',
  ),
  // ---- Yesterday --------------------------------------------------------
  const _Alert(
    day: 1,
    time: '1d',
    sev: _Sev.warn,
    icon: Icons.hourglass_bottom_outlined,
    title: 'A top-ten customer has gone quiet',
    body: 'Meru Traders: 34 days since the last order, they normally buy every 9.',
    owner: 'Asha Mwakalinga · Sales',
    action: 'Call sheet',
  ),
  const _Alert(
    day: 1,
    time: '1d',
    sev: _Sev.warn,
    icon: Icons.inventory_2_outlined,
    title: 'Mwanza is out of a fast mover',
    body: 'Azam Cooking Oil 20L: 2 days at zero, about TZS 3.10M of demand walked.',
    owner: 'Salum Rajabu · Mwanza',
    action: 'Reorder',
  ),
  const _Alert(
    day: 1,
    time: '1d',
    sev: _Sev.good,
    icon: Icons.payments_outlined,
    title: 'A long overdue balance was paid',
    body: 'Kilimanjaro Hardware settled TZS 9.80M, 21 days late but in full.',
    owner: 'Grace Shirima · Finance',
  ),
  // ---- Earlier this week ------------------------------------------------
  const _Alert(
    day: 2,
    time: '3d',
    sev: _Sev.good,
    icon: Icons.done_all,
    title: 'The bank matched to the shilling',
    body: 'CRDB Dar es Salaam reconciled for July, no unexplained items left.',
    owner: 'Fatuma Nyerere · Accounts',
  ),
  const _Alert(
    day: 2,
    time: '4d',
    sev: _Sev.good,
    icon: Icons.lock_outline,
    title: 'July is closed',
    body: 'Fatuma Nyerere closed the period. July figures are POSTED and final.',
  ),
];

// ---------------------------------------------------------------------------

class _NotificationsScreenState extends State<NotificationsScreen> {
  late final List<bool> _unread =
      _alerts.map((a) => a.unread).toList(growable: false);

  int get _unreadCount => _unread.where((u) => u).length;

  void _markAllRead() {
    if (_unreadCount == 0) return;
    setState(() {
      for (var i = 0; i < _unread.length; i++) {
        _unread[i] = false;
      }
    });
  }

  void _open(int i) {
    if (_unread[i]) setState(() => _unread[i] = false);
  }

  void _toast(String label, [String? owner]) {
    final who = owner == null ? '' : ' · ${owner.split(' · ').first}';
    ScaffoldMessenger.of(context)
      ..hideCurrentSnackBar()
      ..showSnackBar(
        SnackBar(
          behavior: SnackBarBehavior.floating,
          backgroundColor: HqColors.ink,
          duration: const Duration(seconds: 2),
          shape: RoundedRectangleBorder(
            borderRadius: BorderRadius.circular(HqRadii.sm),
          ),
          content: Text('$label$who — demo build, nothing is sent.'),
        ),
      );
  }

  @override
  Widget build(BuildContext context) {
    final slivers = <Widget>[
      SliverPadding(
        padding: const EdgeInsets.fromLTRB(20, 12, 20, 4),
        sliver: SliverToBoxAdapter(child: _summary()),
      ),
    ];

    for (var d = 0; d < _dayNames.length; d++) {
      final idx = <int>[
        for (var i = 0; i < _alerts.length; i++)
          if (_alerts[i].day == d) i,
      ];
      if (idx.isEmpty) continue;
      final unreadHere = idx.where((i) => _unread[i]).length;
      slivers
        ..add(
          SliverPersistentHeader(
            pinned: true,
            delegate: _DayHeaderDelegate(
              label: _dayNames[d],
              trailing: unreadHere > 0
                  ? '$unreadHere unread'
                  : '${idx.length} alert${idx.length == 1 ? '' : 's'}',
              accent: unreadHere > 0,
            ),
          ),
        )
        ..add(
          SliverPadding(
            padding: const EdgeInsets.fromLTRB(20, 0, 20, 2),
            sliver: SliverList.builder(
              itemCount: idx.length,
              itemBuilder: (_, k) => _row(idx[k]),
            ),
          ),
        );
    }

    slivers.add(
      SliverPadding(
        padding: const EdgeInsets.fromLTRB(20, 10, 20, 28),
        sliver: SliverToBoxAdapter(
          child: ResidualLine(
            text:
                'Materiality: an alert only fires above TZS 2.0M or 5% of a branch day. '
                '14 smaller signals were logged this week and never sent.',
          ),
        ),
      ),
    );

    return Scaffold(
      backgroundColor: HqColors.bg,
      appBar: AppBar(
        title: const Text('Alerts', style: HqText.title),
        actions: [
          IconButton(
            tooltip: 'Alert rules',
            onPressed: () => _toast('Alert rules'),
            icon: const Icon(Icons.tune, color: HqColors.ink2),
          ),
          const SizedBox(width: 4),
        ],
      ),
      body: CustomScrollView(slivers: slivers),
    );
  }

  // ---------------------------------------------------------------- summary

  Widget _summary() {
    final n = _unreadCount;
    final verdict = n == 0
        ? 'Nothing is waiting on you. Every alert has been read.'
        : n == 1
            ? 'One alert still needs you. Nothing is on fire.'
            : '$n alerts still need you. Nothing is on fire.';

    return HqCard(
      padding: const EdgeInsets.fromLTRB(16, 14, 16, 14),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(
            'What did the app tell you while you were away?',
            style: HqText.tiny.copyWith(color: HqColors.ink3),
          ),
          const SizedBox(height: 6),
          Text(verdict, style: HqText.verdict),
          const SizedBox(height: 10),
          Row(
            children: [
              Expanded(
                child: Text.rich(
                  TextSpan(
                    children: [
                      TextSpan(
                        text: '$n unread',
                        style: const TextStyle(
                          fontSize: 14,
                          fontWeight: FontWeight.w700,
                          color: HqColors.brand,
                        ),
                      ),
                      TextSpan(
                        text: '  ·  quiet hours 21:00–06:00',
                        style: HqText.label.copyWith(fontSize: 13),
                      ),
                    ],
                  ),
                ),
              ),
              const SizedBox(width: 8),
              TextButton(
                onPressed: _markAllRead,
                style: TextButton.styleFrom(
                  foregroundColor: n == 0 ? HqColors.ink3 : HqColors.brand,
                  minimumSize: const Size(0, 34),
                  padding: const EdgeInsets.symmetric(horizontal: 10),
                  visualDensity: VisualDensity.compact,
                  tapTargetSize: MaterialTapTargetSize.shrinkWrap,
                  textStyle: const TextStyle(
                    fontSize: 12.5,
                    fontWeight: FontWeight.w700,
                  ),
                ),
                child: const Text('Mark all read'),
              ),
            ],
          ),
          const SizedBox(height: 12),
          const Divider(height: 1, color: HqColors.line),
          const SizedBox(height: 12),
          Row(
            children: [
              const Expanded(
                child: Text('ALERTS SENT PER DAY', style: HqText.section),
              ),
              Text('38 this month', style: HqText.tiny),
            ],
          ),
          const SizedBox(height: 8),
          ColumnTrend(
            values: _sentPerDay,
            labels: _sentLabels,
            height: 54,
            hatchLast: true,
            barColor: HqColors.brand,
          ),
          const SizedBox(height: 6),
          Text(
            '9 of the 38 asked you to do something, down from 15 last month.',
            style: HqText.tiny,
          ),
          const SizedBox(height: 12),
          Row(
            children: [
              TrustChip(band: TrustBand.posted),
              const SizedBox(width: 10),
              Expanded(
                child: AsOfLine(
                  asOf: 'today 06:30',
                  coverage: '8 branches · one company',
                ),
              ),
            ],
          ),
        ],
      ),
    );
  }

  // -------------------------------------------------------------------- row

  Widget _row(int i) {
    final a = _alerts[i];
    final unread = _unread[i];
    final tone = _tone(a.sev);

    return Padding(
      padding: const EdgeInsets.only(bottom: 10),
      child: Material(
        color: Colors.transparent,
        child: InkWell(
          borderRadius: BorderRadius.circular(HqRadii.lg),
          onTap: () => _open(i),
          child: AnimatedContainer(
            duration: const Duration(milliseconds: 220),
            curve: Curves.easeOut,
            padding: const EdgeInsets.all(13),
            decoration: BoxDecoration(
              color: unread ? HqColors.brandSoft : HqColors.panel,
              borderRadius: BorderRadius.circular(HqRadii.lg),
              border: Border.all(
                color: unread
                    ? HqColors.brand.withValues(alpha: 0.22)
                    : HqColors.line,
              ),
              boxShadow: unread ? null : HqSurfaces.card,
            ),
            child: Row(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Container(
                  width: 38,
                  height: 38,
                  decoration: BoxDecoration(
                    color: tone.tint,
                    shape: BoxShape.circle,
                  ),
                  child: Icon(a.icon, size: 19, color: tone.ink),
                ),
                const SizedBox(width: 12),
                Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Row(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: [
                          Expanded(
                            child: Text(
                              a.title,
                              maxLines: 2,
                              overflow: TextOverflow.ellipsis,
                              style: const TextStyle(
                                fontSize: 14.5,
                                fontWeight: FontWeight.w600,
                                color: HqColors.ink,
                                height: 1.25,
                              ),
                            ),
                          ),
                          const SizedBox(width: 8),
                          if (unread)
                            Container(
                              width: 8,
                              height: 8,
                              margin: const EdgeInsets.only(top: 5, right: 6),
                              decoration: const BoxDecoration(
                                color: HqColors.brand,
                                shape: BoxShape.circle,
                              ),
                            ),
                          Text(
                            a.time,
                            style: HqText.tiny.copyWith(
                              color: unread ? HqColors.brand : HqColors.ink3,
                              fontWeight:
                                  unread ? FontWeight.w700 : FontWeight.w400,
                            ),
                          ),
                        ],
                      ),
                      const SizedBox(height: 3),
                      Text(
                        a.body,
                        maxLines: a.sev == _Sev.brief ? 2 : 1,
                        overflow: TextOverflow.ellipsis,
                        style:
                            HqText.label.copyWith(fontSize: 12.8, height: 1.35),
                      ),
                      if (a.spark != null) ...[
                        const SizedBox(height: 9),
                        Row(
                          children: [
                            SizedBox(
                              width: 92,
                              child: Sparkline(
                                values: a.spark!,
                                color: HqSurfaces.accent,
                                height: 26,
                                strokeWidth: 2,
                              ),
                            ),
                            const SizedBox(width: 10),
                            Expanded(
                              child: Text(
                                a.sparkCaption ?? '',
                                maxLines: 2,
                                style: HqText.tiny,
                              ),
                            ),
                          ],
                        ),
                      ],
                      if (a.footnote != null) ...[
                        const SizedBox(height: 8),
                        Text(
                          a.footnote!,
                          style: HqText.tiny.copyWith(
                            fontStyle: FontStyle.italic,
                            color: HqColors.ink3,
                          ),
                        ),
                      ],
                      if (a.owner != null || a.action != null) ...[
                        const SizedBox(height: 9),
                        Row(
                          children: [
                            if (a.owner != null) ...[
                              const Icon(
                                Icons.person_outline,
                                size: 13,
                                color: HqColors.ink3,
                              ),
                              const SizedBox(width: 4),
                              Expanded(
                                child: Text(
                                  a.owner!,
                                  maxLines: 1,
                                  overflow: TextOverflow.ellipsis,
                                  style: HqText.tiny,
                                ),
                              ),
                            ] else
                              const Spacer(),
                            if (a.action != null) ...[
                              const SizedBox(width: 8),
                              _MiniAction(
                                label: a.action!,
                                onTap: () {
                                  _open(i);
                                  _toast(a.action!, a.owner);
                                },
                              ),
                            ],
                          ],
                        ),
                      ],
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

  _Tone _tone(_Sev s) => switch (s) {
        _Sev.brief =>
          _Tone(HqSurfaces.accent, HqSurfaces.accent.withValues(alpha: 0.14)),
        _Sev.bad => const _Tone(HqColors.bad, HqColors.badSoft),
        _Sev.warn => const _Tone(HqColors.warn, HqColors.warnSoft),
        _Sev.good => const _Tone(HqColors.good, HqColors.goodSoft),
      };
}

class _Tone {
  const _Tone(this.ink, this.tint);
  final Color ink;
  final Color tint;
}

/// The one-tap action on an alert: a small white pill, brand ink, chevron.
class _MiniAction extends StatelessWidget {
  const _MiniAction({required this.label, required this.onTap});

  final String label;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    return Material(
      color: HqColors.panel,
      borderRadius: BorderRadius.circular(999),
      child: InkWell(
        borderRadius: BorderRadius.circular(999),
        onTap: onTap,
        child: Container(
          padding: const EdgeInsets.fromLTRB(11, 6, 7, 6),
          decoration: BoxDecoration(
            borderRadius: BorderRadius.circular(999),
            border: Border.all(color: HqColors.brand.withValues(alpha: 0.35)),
          ),
          child: Row(
            mainAxisSize: MainAxisSize.min,
            children: [
              Text(
                label,
                style: const TextStyle(
                  fontSize: 11.5,
                  fontWeight: FontWeight.w700,
                  color: HqColors.brand,
                ),
              ),
              const Icon(Icons.chevron_right, size: 14, color: HqColors.brand),
            ],
          ),
        ),
      ),
    );
  }
}

/// Day separator that pins to the top of the viewport while its group scrolls.
class _DayHeaderDelegate extends SliverPersistentHeaderDelegate {
  const _DayHeaderDelegate({
    required this.label,
    required this.trailing,
    required this.accent,
  });

  final String label;
  final String trailing;
  final bool accent;

  @override
  double get minExtent => 42;

  @override
  double get maxExtent => 42;

  @override
  Widget build(
      BuildContext context, double shrinkOffset, bool overlapsContent) {
    return Container(
      color: HqColors.bg,
      padding: const EdgeInsets.fromLTRB(20, 13, 20, 8),
      alignment: Alignment.centerLeft,
      child: Row(
        children: [
          Expanded(child: Text(label.toUpperCase(), style: HqText.section)),
          Text(
            trailing,
            style: HqText.tiny.copyWith(
              color: accent ? HqColors.brand : HqColors.ink3,
              fontWeight: accent ? FontWeight.w700 : FontWeight.w400,
            ),
          ),
        ],
      ),
    );
  }

  @override
  bool shouldRebuild(covariant _DayHeaderDelegate old) =>
      old.label != label || old.trailing != trailing || old.accent != accent;
}
