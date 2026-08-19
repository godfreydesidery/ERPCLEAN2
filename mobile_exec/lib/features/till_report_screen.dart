import 'package:flutter/material.dart';

import '../app/format.dart';
import '../app/theme.dart';
import '../data/mock.dart';
import '../widgets/common.dart';
import '../widgets/kit.dart';

/// X and Z till reports.
///
///  * **X read** — a look at the till mid-shift. The session stays open and
///    nothing resets, so it can be taken as often as you like.
///  * **Z read** — the end-of-day close-out. It finalises the session, takes a
///    Z number, and the totals start again from zero.
///
/// Mockup: the arithmetic is real, nothing is read from or written to a till.
enum TillReadKind { x, z }

class TillReportScreen extends StatefulWidget {
  const TillReportScreen({super.key});

  @override
  State<TillReportScreen> createState() => _TillReportScreenState();
}

class _TillReportScreenState extends State<TillReportScreen> {
  TillReadKind _kind = TillReadKind.x;
  TillSession _session = kOpenSessions.first;

  num get _tenderTotal => kTenderSplit.fold<num>(0, (a, t) => a + t.amount);

  num get _expectedCash =>
      kOpeningFloat + kTenderSplit.first.amount + kCashIn - kCashOut;

  bool get _isZ => _kind == TillReadKind.z;

  Future<void> _run() async {
    if (_isZ) {
      final ok = await showDialog<bool>(
        context: context,
        builder: (context) => AlertDialog(
          title: const Text('Take the Z read?'),
          content: Text(
            'This closes ${_session.till} for the day and resets its totals. '
            'It cannot be undone — take an X read instead if you only want to '
            'look.',
            style: HqText.body,
          ),
          actions: [
            TextButton(
              onPressed: () => Navigator.of(context).pop(false),
              child: const Text('Cancel'),
            ),
            FilledButton(
              onPressed: () => Navigator.of(context).pop(true),
              child: const Text('Close the day'),
            ),
          ],
        ),
      );
      if (ok != true || !mounted) return;
      await showDoneSheet(
        context,
        title: 'Z read taken',
        detail: '${_session.till} closed for the day\n'
            'Z number ${kLastZNumber + 1} · ${tzs(_session.sales)}',
      );
    } else {
      await showDoneSheet(
        context,
        title: 'X read taken',
        detail: '${_session.till} is still open\n'
            '${tzs(_session.sales)} so far today',
      );
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: HqColors.bg,
      appBar: AppBar(
        title: const Text('Till report', style: HqText.title),
        actions: [
          IconButton(
            tooltip: 'Share',
            icon: const Icon(Icons.ios_share_rounded),
            onPressed: () => showShareSheet(
              context,
              '${_isZ ? 'Z' : 'X'} read — ${_session.till}',
            ),
          ),
          const SizedBox(width: 6),
        ],
      ),
      body: ListView(
        padding: const EdgeInsets.fromLTRB(20, 8, 20, 28),
        children: [
          _KindToggle(
            kind: _kind,
            onChanged: (k) => setState(() => _kind = k),
          ),
          const SizedBox(height: 12),
          _KindNote(isZ: _isZ),
          const SizedBox(height: 20),
          const SectionLabel(text: 'WHICH TILL'),
          const SizedBox(height: 10),
          HqDropdown(
            label: 'Till',
            items: [for (final s in kOpenSessions) '${s.till} · ${s.cashier}'],
            value: '${_session.till} · ${_session.cashier}',
            onChanged: (v) {
              final match = kOpenSessions.firstWhere(
                (s) => '${s.till} · ${s.cashier}' == v,
                orElse: () => _session,
              );
              setState(() => _session = match);
            },
          ),
          const SizedBox(height: 22),
          _Header(session: _session, isZ: _isZ),
          const SizedBox(height: 14),
          const SectionLabel(text: 'HOW THEY PAID'),
          const SizedBox(height: 10),
          HqCard(
            child: Column(
              children: [
                for (var i = 0; i < kTenderSplit.length; i++) ...[
                  if (i > 0) const Divider(height: 14),
                  _TenderRow(line: kTenderSplit[i]),
                ],
                const Divider(height: 16, thickness: 1.4),
                FigureRow(
                  label: 'Total takings',
                  value: tzs(_tenderTotal),
                  emphasise: true,
                  valueColor: HqColors.brand,
                ),
              ],
            ),
          ),
          const SizedBox(height: 20),
          const SectionLabel(text: 'CASH IN THE DRAWER'),
          const SizedBox(height: 10),
          HqCard(
            child: Column(
              children: [
                FigureRow(
                  label: 'Opening float',
                  value: tzs(kOpeningFloat),
                ),
                const Divider(height: 14),
                FigureRow(label: 'Cash sales', value: tzs(kTenderSplit.first.amount)),
                const Divider(height: 14),
                FigureRow(label: 'Paid in', value: tzs(kCashIn)),
                const Divider(height: 14),
                FigureRow(
                  label: 'Paid out',
                  value: '−${tzs(kCashOut)}',
                  valueColor: HqColors.bad,
                ),
                const Divider(height: 16, thickness: 1.4),
                FigureRow(
                  label: 'Cash the drawer should hold',
                  value: tzs(_expectedCash),
                  emphasise: true,
                ),
              ],
            ),
          ),
          const SizedBox(height: 20),
          const SectionLabel(text: 'EXCEPTIONS'),
          const SizedBox(height: 10),
          HqCard(
            child: Column(
              children: [
                FigureRow(
                  label: 'Voids ($kVoidCount)',
                  value: tzs(kVoidValue),
                  valueColor: kVoidCount > 0 ? HqColors.warn : null,
                ),
                const Divider(height: 14),
                FigureRow(
                  label: 'Refunds ($kRefundCount)',
                  value: tzs(kRefundValue),
                  valueColor: kRefundCount > 0 ? HqColors.warn : null,
                ),
                const Divider(height: 14),
                FigureRow(label: 'Discounts given', value: tzs(kDiscountValue)),
                const Divider(height: 14),
                FigureRow(label: 'VAT collected', value: tzs(kVatCollected)),
              ],
            ),
          ),
          const SizedBox(height: 20),
          HqCard(
            padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 12),
            child: Column(
              children: [
                FigureRow(
                  label: 'Last Z read',
                  value: 'No. $kLastZNumber',
                ),
                const Divider(height: 14),
                FigureRow(label: 'Taken', value: kLastZAt),
                const Divider(height: 14),
                FigureRow(
                  label: 'X reads since',
                  value: '$kXReadsToday today',
                ),
              ],
            ),
          ),
          const SizedBox(height: 24),
          FilledButton.icon(
            onPressed: _run,
            style: _isZ
                ? FilledButton.styleFrom(backgroundColor: HqColors.bad)
                : null,
            icon: Icon(
              _isZ ? Icons.lock_outline_rounded : Icons.visibility_outlined,
              size: 19,
            ),
            label: Text(_isZ ? 'Take Z read and close' : 'Take X read'),
          ),
          const SizedBox(height: 10),
          OutlinedButton.icon(
            onPressed: () => showShareSheet(
              context,
              '${_isZ ? 'Z' : 'X'} read — ${_session.till}',
            ),
            icon: const Icon(Icons.ios_share_rounded, size: 19),
            label: const Text('Export and share'),
          ),
          const SizedBox(height: 14),
          Center(
            child: Text(
              'Demo build — no till is actually read or closed.',
              style: HqText.tiny,
            ),
          ),
        ],
      ),
    );
  }
}

class _KindToggle extends StatelessWidget {
  const _KindToggle({required this.kind, required this.onChanged});

  final TillReadKind kind;
  final ValueChanged<TillReadKind> onChanged;

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.all(4),
      decoration: BoxDecoration(
        color: HqColors.panel,
        borderRadius: BorderRadius.circular(12),
        border: Border.all(color: HqColors.line),
      ),
      child: Row(
        children: [
          _Half(
            label: 'X read',
            sub: 'Look now',
            selected: kind == TillReadKind.x,
            onTap: () => onChanged(TillReadKind.x),
            tint: HqColors.brand,
          ),
          _Half(
            label: 'Z read',
            sub: 'Close the day',
            selected: kind == TillReadKind.z,
            onTap: () => onChanged(TillReadKind.z),
            tint: HqColors.bad,
          ),
        ],
      ),
    );
  }
}

class _Half extends StatelessWidget {
  const _Half({
    required this.label,
    required this.sub,
    required this.selected,
    required this.onTap,
    required this.tint,
  });

  final String label;
  final String sub;
  final bool selected;
  final VoidCallback onTap;
  final Color tint;

  @override
  Widget build(BuildContext context) {
    return Expanded(
      child: GestureDetector(
        onTap: onTap,
        behavior: HitTestBehavior.opaque,
        child: AnimatedContainer(
          duration: const Duration(milliseconds: 150),
          padding: const EdgeInsets.symmetric(vertical: 10),
          decoration: BoxDecoration(
            color: selected ? tint : Colors.transparent,
            borderRadius: BorderRadius.circular(9),
          ),
          child: Column(
            children: [
              Text(
                label,
                style: TextStyle(
                  fontSize: 14.5,
                  fontWeight: FontWeight.w700,
                  color: selected ? Colors.white : HqColors.ink2,
                ),
              ),
              const SizedBox(height: 1),
              Text(
                sub,
                maxLines: 1,
                overflow: TextOverflow.ellipsis,
                style: TextStyle(
                  fontSize: 10.5,
                  color: selected
                      ? Colors.white.withValues(alpha: 0.85)
                      : HqColors.ink3,
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }
}

class _KindNote extends StatelessWidget {
  const _KindNote({required this.isZ});

  final bool isZ;

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.all(13),
      decoration: BoxDecoration(
        color: isZ ? HqColors.badSoft : HqColors.brandSoft,
        borderRadius: BorderRadius.circular(HqRadii.sm),
      ),
      child: Row(
        children: [
          Icon(
            isZ ? Icons.warning_amber_rounded : Icons.info_outline_rounded,
            size: 19,
            color: isZ ? HqColors.bad : HqColors.brand,
          ),
          const SizedBox(width: 10),
          Expanded(
            child: Text(
              isZ
                  ? 'A Z read closes the till for the day and resets its '
                      'totals. Take it once, at the end.'
                  : 'An X read only looks at the till. The session stays open '
                      'and nothing resets — take it as often as you like.',
              style: TextStyle(
                fontSize: 13,
                height: 1.35,
                color: isZ ? const Color(0xFF8A1F1F) : HqColors.brandD,
              ),
            ),
          ),
        ],
      ),
    );
  }
}

class _Header extends StatelessWidget {
  const _Header({required this.session, required this.isZ});

  final TillSession session;
  final bool isZ;

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.all(20),
      decoration: BoxDecoration(
        gradient: HqSurfaces.heroGradient,
        borderRadius: BorderRadius.circular(20),
        boxShadow: HqSurfaces.hero,
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              Expanded(
                child: Text(
                  '${isZ ? 'Z' : 'X'} READ · ${session.till}'.toUpperCase(),
                  maxLines: 1,
                  overflow: TextOverflow.ellipsis,
                  style: const TextStyle(
                    fontSize: 10.5,
                    fontWeight: FontWeight.w700,
                    color: HqOnDark.tertiary,
                    letterSpacing: 0.8,
                  ),
                ),
              ),
              Container(
                padding:
                    const EdgeInsets.symmetric(horizontal: 9, vertical: 4),
                decoration: BoxDecoration(
                  color: Colors.white.withValues(alpha: 0.12),
                  borderRadius: BorderRadius.circular(20),
                ),
                child: Text(
                  isZ ? 'CLOSES THE DAY' : 'SESSION STAYS OPEN',
                  style: const TextStyle(
                    fontSize: 9,
                    fontWeight: FontWeight.w800,
                    color: Colors.white,
                    letterSpacing: 0.5,
                  ),
                ),
              ),
            ],
          ),
          const SizedBox(height: 10),
          Text(
            tzs(session.sales),
            style: const TextStyle(
              fontSize: 34,
              fontWeight: FontWeight.w700,
              color: Colors.white,
              height: 1.05,
              letterSpacing: -1,
            ),
          ),
          const SizedBox(height: 6),
          Text(
            '${session.cashier} · opened ${session.openedAt} · '
            '${session.transactions} sales',
            maxLines: 1,
            overflow: TextOverflow.ellipsis,
            style: const TextStyle(
              fontSize: 12.5,
              color: HqOnDark.secondary,
            ),
          ),
        ],
      ),
    );
  }
}

class _TenderRow extends StatelessWidget {
  const _TenderRow({required this.line});

  final TenderLine line;

  @override
  Widget build(BuildContext context) {
    return Row(
      children: [
        Expanded(
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text(
                line.label,
                style: const TextStyle(
                  fontSize: 14,
                  fontWeight: FontWeight.w600,
                  color: HqColors.ink,
                ),
              ),
              Text('${line.count} sales', style: HqText.tiny),
            ],
          ),
        ),
        const SizedBox(width: 10),
        Text(
          tzs(line.amount),
          style: const TextStyle(
            fontSize: 14.5,
            fontWeight: FontWeight.w700,
            color: HqColors.ink,
          ),
        ),
      ],
    );
  }
}
