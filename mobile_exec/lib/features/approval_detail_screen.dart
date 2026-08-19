import 'package:flutter/material.dart';

import '../app/format.dart';
import '../app/theme.dart';
import '../data/mock.dart';
import '../widgets/common.dart';

/// The decision screen — the only screen in OrbixHQ where the owner does
/// something rather than reads something.
///
/// Doctrine: the money first, the evidence second, the decision in the thumb
/// zone. Everything a decider would otherwise phone somebody to ask — who it is
/// with, what is left in the budget, what we paid last time, who has already
/// signed — sits on this one page, so "approve" never means "approve blind".
class ApprovalDetailScreen extends StatefulWidget {
  const ApprovalDetailScreen({super.key, required this.index});

  final int index;

  @override
  State<ApprovalDetailScreen> createState() => _ApprovalDetailScreenState();
}

class _ApprovalDetailScreenState extends State<ApprovalDetailScreen> {
  bool _busy = false;

  int get _i => widget.index.clamp(0, kApprovals.length - 1);

  ApprovalRequest get _req => kApprovals[_i];

  _Evidence get _ev => _kEvidence[_i];

  Future<void> _approve() async {
    if (_busy) return;
    setState(() => _busy = true);

    final confirmed = await showModalBottomSheet<bool>(
      context: context,
      backgroundColor: Colors.transparent,
      useSafeArea: true,
      builder: (_) => _BiometricSheet(request: _req),
    );

    if (!mounted) return;
    setState(() => _busy = false);
    if (confirmed != true) return;

    final messenger = ScaffoldMessenger.of(context);
    final navigator = Navigator.of(context);
    messenger.hideCurrentSnackBar();
    messenger.showSnackBar(
      _snack(
        icon: Icons.check_circle_rounded,
        tint: HqColors.good,
        text: 'Approved — ${tzs(_req.amount)} released to ${_req.requester}',
      ),
    );
    if (navigator.canPop()) navigator.pop(true);
  }

  void _decline() {
    final messenger = ScaffoldMessenger.of(context);
    final navigator = Navigator.of(context);
    messenger.hideCurrentSnackBar();
    messenger.showSnackBar(
      _snack(
        icon: Icons.block_rounded,
        tint: HqColors.bad,
        text: 'Declined — ${_req.requester} will be asked to rework it',
      ),
    );
    if (navigator.canPop()) navigator.pop(false);
  }

  SnackBar _snack({
    required IconData icon,
    required Color tint,
    required String text,
  }) {
    return SnackBar(
      behavior: SnackBarBehavior.floating,
      backgroundColor: HqColors.ink,
      duration: const Duration(seconds: 4),
      margin: const EdgeInsets.all(16),
      shape: RoundedRectangleBorder(
        borderRadius: BorderRadius.circular(HqRadii.sm),
      ),
      content: Row(
        children: [
          Icon(icon, size: 20, color: Color.lerp(tint, Colors.white, 0.45)),
          const SizedBox(width: 10),
          Expanded(
            child: Text(
              text,
              style: const TextStyle(
                fontSize: 13.5,
                height: 1.3,
                fontWeight: FontWeight.w600,
                color: Colors.white,
              ),
            ),
          ),
        ],
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    final r = _req;
    final e = _ev;
    final money = r.amount > 0;

    return Scaffold(
      backgroundColor: HqColors.bg,
      appBar: AppBar(
        leading: IconButton(
          icon: const Icon(Icons.arrow_back_rounded),
          tooltip: 'Back',
          onPressed: () => Navigator.of(context).maybePop(),
        ),
        title: Text(_docTypeTitle(r.docType), style: HqText.title),
        bottom: const PreferredSize(
          preferredSize: Size.fromHeight(1),
          child: Divider(height: 1, thickness: 1, color: HqColors.line),
        ),
      ),
      body: ListView(
        padding: const EdgeInsets.fromLTRB(18, 18, 18, 26),
        children: [
          _AmountCard(request: r, money: money),
          const SizedBox(height: 22),
          const SectionLabel(
            text: 'Evidence',
            trailing: 'no need to ask around',
          ),
          HqCard(
            padding: const EdgeInsets.fromLTRB(16, 4, 16, 4),
            child: Column(
              children: [
                _DefRow(
                  label: _counterpartyLabel(r.docType),
                  child: _PlainValue(r.counterparty),
                ),
                const _HairRule(),
                _DefRow(label: 'Branch', child: _PlainValue(r.branch)),
                const _HairRule(),
                _DefRow(
                  label: 'Requested by',
                  child: _PersonLine(
                    name: r.requester,
                    caption: '${r.age} ago, from ${r.branch}',
                    tint: HqColors.brand,
                  ),
                ),
                const _HairRule(),
                _DefRow(
                  label: 'Budget line',
                  child: _BudgetBlock(request: r, ev: e),
                ),
                const _HairRule(),
                _DefRow(
                  label: 'Last price paid',
                  child: _PriceBlock(request: r, ev: e),
                ),
                const _HairRule(),
                _DefRow(
                  label: 'Already approved',
                  child: _ApproverBlock(approvedBy: r.alreadyApprovedBy),
                ),
                const _HairRule(),
                _DefRow(
                  label: 'Age',
                  child: _AgeLine(age: r.age, note: e.ageNote),
                ),
              ],
            ),
          ),
          const SizedBox(height: 22),
          const SectionLabel(text: 'What happens next'),
          Text(e.next, style: HqText.body),
          const SizedBox(height: 14),
          Text(
            'As of $kAsOf · $kCompanyName · figures from the ledger, '
            'not a forecast',
            style: HqText.tiny,
          ),
        ],
      ),
      bottomNavigationBar: _ActionBar(
        busy: _busy,
        onApprove: _approve,
        onDecline: _decline,
      ),
    );
  }
}

// ---------------------------------------------------------------------------
// The money, said once
// ---------------------------------------------------------------------------

class _AmountCard extends StatelessWidget {
  const _AmountCard({required this.request, required this.money});

  final ApprovalRequest request;
  final bool money;

  @override
  Widget build(BuildContext context) {
    return HqCard(
      padding: const EdgeInsets.fromLTRB(20, 18, 20, 18),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              Expanded(
                child: Text(
                  _docTypeTitle(request.docType).toUpperCase(),
                  maxLines: 1,
                  overflow: TextOverflow.ellipsis,
                  style: HqText.section,
                ),
              ),
              const SizedBox(width: 10),
              const TrustChip(band: TrustBand.posted),
            ],
          ),
          const SizedBox(height: 12),
          FittedBox(
            fit: BoxFit.scaleDown,
            alignment: Alignment.centerLeft,
            child: Text(
              money ? tzs(request.amount) : 'No money moves',
              maxLines: 1,
              style: const TextStyle(
                fontSize: 34,
                fontWeight: FontWeight.w700,
                letterSpacing: -1.1,
                height: 1.05,
                color: HqColors.ink,
              ),
            ),
          ),
          if (money) ...[
            const SizedBox(height: 5),
            Text(tzsExact(request.amount), style: HqText.tiny),
          ],
          const SizedBox(height: 11),
          Text(request.title, style: HqText.verdict.copyWith(fontSize: 16.5)),
          const SizedBox(height: 6),
          Text(request.note, style: HqText.body),
          if (request.aboveThreshold) ...[
            const SizedBox(height: 14),
            _LimitPill(threshold: kUser.personalApprovalThreshold),
          ],
        ],
      ),
    );
  }
}

/// The single gold object on the screen — it is the one fact that changes the
/// weight of the decision.
class _LimitPill extends StatelessWidget {
  const _LimitPill({required this.threshold});

  static const _ink = Color(0xFF8A6417);

  final num threshold;

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 8),
      decoration: BoxDecoration(
        color: HqSurfaces.accent.withValues(alpha: 0.12),
        borderRadius: BorderRadius.circular(999),
        border: Border.all(color: HqSurfaces.accent.withValues(alpha: 0.45)),
      ),
      child: Row(
        mainAxisSize: MainAxisSize.min,
        children: [
          const Icon(Icons.shield_outlined, size: 15, color: _ink),
          const SizedBox(width: 7),
          Flexible(
            child: Text(
              'ABOVE YOUR LIMIT — ${tzs(threshold)}',
              maxLines: 1,
              overflow: TextOverflow.ellipsis,
              style: const TextStyle(
                fontSize: 11.5,
                fontWeight: FontWeight.w700,
                letterSpacing: 0.6,
                color: _ink,
              ),
            ),
          ),
        ],
      ),
    );
  }
}

// ---------------------------------------------------------------------------
// Definition list
// ---------------------------------------------------------------------------

class _DefRow extends StatelessWidget {
  const _DefRow({required this.label, required this.child});

  final String label;
  final Widget child;

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 13),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          SizedBox(
            width: 100,
            child: Text(
              label,
              style: HqText.label.copyWith(color: HqColors.ink3, height: 1.35),
            ),
          ),
          const SizedBox(width: 12),
          Expanded(child: child),
        ],
      ),
    );
  }
}

class _HairRule extends StatelessWidget {
  const _HairRule();

  @override
  Widget build(BuildContext context) =>
      const Divider(height: 1, thickness: 1, color: HqColors.line);
}

class _PlainValue extends StatelessWidget {
  const _PlainValue(this.text);

  final String text;

  @override
  Widget build(BuildContext context) {
    return Text(
      text,
      style: const TextStyle(
        fontSize: 14,
        height: 1.35,
        fontWeight: FontWeight.w600,
        color: HqColors.ink,
      ),
    );
  }
}

class _InitialsAvatar extends StatelessWidget {
  const _InitialsAvatar({
    required this.name,
    required this.tint,
    this.size = 28,
  });

  final String name;
  final Color tint;
  final double size;

  @override
  Widget build(BuildContext context) {
    return Container(
      width: size,
      height: size,
      alignment: Alignment.center,
      decoration: BoxDecoration(
        color: tint.withValues(alpha: 0.10),
        shape: BoxShape.circle,
        border: Border.all(color: tint.withValues(alpha: 0.24)),
      ),
      child: Text(
        ExceptionTile.initialsOf(name),
        style: TextStyle(
          fontSize: size * 0.38,
          fontWeight: FontWeight.w700,
          letterSpacing: 0.2,
          color: tint,
        ),
      ),
    );
  }
}

class _PersonLine extends StatelessWidget {
  const _PersonLine({
    required this.name,
    required this.caption,
    required this.tint,
  });

  final String name;
  final String caption;
  final Color tint;

  @override
  Widget build(BuildContext context) {
    return Row(
      children: [
        _InitialsAvatar(name: name, tint: tint),
        const SizedBox(width: 10),
        Expanded(
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            mainAxisSize: MainAxisSize.min,
            children: [
              _PlainValue(name),
              const SizedBox(height: 2),
              Text(
                caption,
                maxLines: 2,
                overflow: TextOverflow.ellipsis,
                style: HqText.tiny,
              ),
            ],
          ),
        ),
      ],
    );
  }
}

/// Budget line, what is left on it, and the slice this request would take.
/// Teal is already committed, gold is this decision, grey is what survives it.
class _BudgetBlock extends StatelessWidget {
  const _BudgetBlock({required this.request, required this.ev});

  final ApprovalRequest request;
  final _Evidence ev;

  @override
  Widget build(BuildContext context) {
    final used = ev.usedFraction.clamp(0.0, 1.0);
    final ask = ev.requestFraction.clamp(0.0, 1.0 - used);
    final rest = (1.0 - used - ask).clamp(0.0, 1.0);

    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      mainAxisSize: MainAxisSize.min,
      children: [
        _PlainValue(request.budgetLine),
        const SizedBox(height: 3),
        Text(ev.budgetLeft, style: HqText.tiny),
        const SizedBox(height: 9),
        ClipRRect(
          borderRadius: BorderRadius.circular(999),
          child: SizedBox(
            height: 8,
            child: Row(
              children: [
                Expanded(
                  flex: (used * 1000).round().clamp(1, 1000),
                  child: const ColoredBox(color: HqColors.brand),
                ),
                if (ask > 0.004)
                  Expanded(
                    flex: (ask * 1000).round().clamp(1, 1000),
                    child: const ColoredBox(color: HqSurfaces.accent),
                  ),
                if (rest > 0.004)
                  Expanded(
                    flex: (rest * 1000).round().clamp(1, 1000),
                    child: const ColoredBox(color: HqColors.line),
                  ),
              ],
            ),
          ),
        ),
        const SizedBox(height: 8),
        Row(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            const Padding(
              padding: EdgeInsets.only(top: 3),
              child: _Swatch(color: HqSurfaces.accent),
            ),
            const SizedBox(width: 7),
            Expanded(child: Text(ev.afterText, style: HqText.tiny)),
          ],
        ),
      ],
    );
  }
}

class _Swatch extends StatelessWidget {
  const _Swatch({required this.color});

  final Color color;

  @override
  Widget build(BuildContext context) {
    return Container(
      width: 9,
      height: 9,
      decoration: BoxDecoration(
        color: color,
        borderRadius: BorderRadius.circular(2.5),
      ),
    );
  }
}

/// What we paid last time, and how far this request has moved from it. Red only
/// when the movement is bad for the business — dearer to buy, or more given
/// away. A better price on a sale is green.
class _PriceBlock extends StatelessWidget {
  const _PriceBlock({required this.request, required this.ev});

  final ApprovalRequest request;
  final _Evidence ev;

  @override
  Widget build(BuildContext context) {
    final d = ev.priceDeltaPct;
    final tint = d == null
        ? HqColors.ink2
        : (ev.deltaIsBad ? HqColors.bad : HqColors.good);

    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      mainAxisSize: MainAxisSize.min,
      children: [
        Row(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Expanded(child: _PlainValue(request.lastPricePaid)),
            if (d != null) ...[
              const SizedBox(width: 10),
              Container(
                padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 4),
                decoration: BoxDecoration(
                  color: tint.withValues(alpha: 0.09),
                  borderRadius: BorderRadius.circular(999),
                  border: Border.all(color: tint.withValues(alpha: 0.24)),
                ),
                child: Text(
                  pct(d, sign: true),
                  style: TextStyle(
                    fontSize: 12,
                    fontWeight: FontWeight.w700,
                    color: tint,
                  ),
                ),
              ),
            ],
          ],
        ),
        const SizedBox(height: 4),
        Text(ev.priceNote, style: HqText.tiny),
      ],
    );
  }
}

class _ApproverBlock extends StatelessWidget {
  const _ApproverBlock({required this.approvedBy});

  final String approvedBy;

  @override
  Widget build(BuildContext context) {
    if (approvedBy.toLowerCase().startsWith('nobody')) {
      return Container(
        padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 8),
        decoration: BoxDecoration(
          color: HqColors.warnSoft,
          borderRadius: BorderRadius.circular(999),
          border: Border.all(color: HqColors.warn.withValues(alpha: 0.28)),
        ),
        child: const Text(
          'Nobody yet — you are the first pair of eyes',
          maxLines: 2,
          overflow: TextOverflow.ellipsis,
          style: TextStyle(
            fontSize: 12,
            height: 1.3,
            fontWeight: FontWeight.w700,
            color: HqColors.warn,
          ),
        ),
      );
    }

    final parts = approvedBy.split(',');
    final name = parts.first.trim();
    final role =
        parts.length > 1 ? parts.sublist(1).join(',').trim() : 'Approver';

    return Row(
      children: [
        _InitialsAvatar(name: name, tint: HqColors.good),
        const SizedBox(width: 10),
        Expanded(
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            mainAxisSize: MainAxisSize.min,
            children: [
              Row(
                children: [
                  Flexible(child: _PlainValue(name)),
                  const SizedBox(width: 6),
                  const Icon(
                    Icons.verified_rounded,
                    size: 15,
                    color: HqColors.good,
                  ),
                ],
              ),
              const SizedBox(height: 2),
              Text(
                role,
                maxLines: 1,
                overflow: TextOverflow.ellipsis,
                style: HqText.tiny,
              ),
            ],
          ),
        ),
      ],
    );
  }
}

class _AgeLine extends StatelessWidget {
  const _AgeLine({required this.age, required this.note});

  final String age;
  final String note;

  @override
  Widget build(BuildContext context) {
    final stale = age.endsWith('d');
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      mainAxisSize: MainAxisSize.min,
      children: [
        Row(
          children: [
            Icon(
              stale ? Icons.hourglass_bottom_rounded : Icons.schedule_rounded,
              size: 15,
              color: stale ? HqColors.warn : HqColors.ink3,
            ),
            const SizedBox(width: 6),
            Text(
              'Waiting $age',
              style: TextStyle(
                fontSize: 14,
                fontWeight: FontWeight.w700,
                color: stale ? HqColors.warn : HqColors.ink,
              ),
            ),
          ],
        ),
        const SizedBox(height: 3),
        Text(note, style: HqText.tiny),
      ],
    );
  }
}

// ---------------------------------------------------------------------------
// The decision, in the thumb zone
// ---------------------------------------------------------------------------

class _ActionBar extends StatelessWidget {
  const _ActionBar({
    required this.busy,
    required this.onApprove,
    required this.onDecline,
  });

  final bool busy;
  final VoidCallback onApprove;
  final VoidCallback onDecline;

  @override
  Widget build(BuildContext context) {
    return Container(
      decoration: const BoxDecoration(
        color: HqColors.panel,
        border: Border(top: BorderSide(color: HqColors.line)),
      ),
      child: SafeArea(
        top: false,
        child: Padding(
          padding: const EdgeInsets.fromLTRB(18, 12, 18, 10),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              Row(
                children: [
                  Expanded(
                    child: OutlinedButton(
                      onPressed: busy ? null : onDecline,
                      child: const Text('Decline'),
                    ),
                  ),
                  const SizedBox(width: 12),
                  Expanded(
                    flex: 2,
                    child: FilledButton(
                      onPressed: busy ? null : onApprove,
                      style: FilledButton.styleFrom(
                        backgroundColor: HqColors.good,
                        foregroundColor: Colors.white,
                        disabledBackgroundColor:
                            HqColors.good.withValues(alpha: 0.45),
                        disabledForegroundColor:
                            Colors.white.withValues(alpha: 0.85),
                        minimumSize: const Size.fromHeight(52),
                        shape: RoundedRectangleBorder(
                          borderRadius: BorderRadius.circular(HqRadii.sm),
                        ),
                        textStyle: const TextStyle(
                          fontSize: 16,
                          fontWeight: FontWeight.w700,
                        ),
                      ),
                      child: const Text('Approve'),
                    ),
                  ),
                ],
              ),
              const SizedBox(height: 9),
              Row(
                mainAxisAlignment: MainAxisAlignment.center,
                children: [
                  const Icon(
                    Icons.fingerprint_rounded,
                    size: 14,
                    color: HqColors.ink3,
                  ),
                  const SizedBox(width: 6),
                  Flexible(
                    child: Text(
                      "You'll be asked for your fingerprint to confirm.",
                      maxLines: 1,
                      overflow: TextOverflow.ellipsis,
                      style: HqText.tiny,
                    ),
                  ),
                ],
              ),
            ],
          ),
        ),
      ),
    );
  }
}

// ---------------------------------------------------------------------------
// Biometric step-up
// ---------------------------------------------------------------------------

class _BiometricSheet extends StatefulWidget {
  const _BiometricSheet({required this.request});

  final ApprovalRequest request;

  @override
  State<_BiometricSheet> createState() => _BiometricSheetState();
}

class _BiometricSheetState extends State<_BiometricSheet>
    with SingleTickerProviderStateMixin {
  late final AnimationController _pulse = AnimationController(
    vsync: this,
    duration: const Duration(milliseconds: 1100),
  )..repeat(reverse: true);

  bool _reading = true;

  @override
  void initState() {
    super.initState();
    _run();
  }

  Future<void> _run() async {
    await Future<void>.delayed(const Duration(milliseconds: 1500));
    if (!mounted) return;
    setState(() => _reading = false);
    _pulse.stop();
    await Future<void>.delayed(const Duration(milliseconds: 450));
    if (!mounted) return;
    Navigator.of(context).pop(true);
  }

  @override
  void dispose() {
    _pulse.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final r = widget.request;
    final tint = _reading ? HqColors.brand : HqColors.good;

    return Container(
      width: double.infinity,
      decoration: const BoxDecoration(
        color: HqColors.panel,
        borderRadius: BorderRadius.vertical(top: Radius.circular(24)),
      ),
      padding: const EdgeInsets.fromLTRB(24, 12, 24, 22),
      child: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          Container(
            width: 40,
            height: 4,
            decoration: BoxDecoration(
              color: HqColors.line2,
              borderRadius: BorderRadius.circular(999),
            ),
          ),
          const SizedBox(height: 24),
          AnimatedBuilder(
            animation: _pulse,
            builder: (context, child) {
              final t = _reading ? _pulse.value : 1.0;
              return Container(
                width: 104,
                height: 104,
                alignment: Alignment.center,
                decoration: BoxDecoration(
                  shape: BoxShape.circle,
                  color: tint.withValues(alpha: 0.06 + 0.06 * t),
                  border: Border.all(
                    color: tint.withValues(alpha: 0.22 + 0.28 * t),
                    width: 1.5,
                  ),
                ),
                child: child,
              );
            },
            child: Icon(
              _reading ? Icons.fingerprint_rounded : Icons.check_rounded,
              size: 52,
              color: tint,
            ),
          ),
          const SizedBox(height: 20),
          Text(
            _reading ? 'Confirm with fingerprint' : 'Fingerprint recognised',
            textAlign: TextAlign.center,
            style: HqText.title.copyWith(fontSize: 19),
          ),
          const SizedBox(height: 7),
          Text(
            r.amount > 0 ? '${tzs(r.amount)} · ${r.title}' : r.title,
            textAlign: TextAlign.center,
            style: HqText.body,
          ),
          const SizedBox(height: 4),
          Text(
            'Approving as ${kUser.name}, ${kUser.role}',
            textAlign: TextAlign.center,
            style: HqText.tiny,
          ),
          const SizedBox(height: 22),
          OutlinedButton(
            onPressed: () => Navigator.of(context).pop(false),
            child: const Text('Cancel'),
          ),
        ],
      ),
    );
  }
}

// ---------------------------------------------------------------------------
// Demo evidence — one entry per request in kApprovals, same order
// ---------------------------------------------------------------------------

class _Evidence {
  const _Evidence({
    required this.budgetLeft,
    required this.usedFraction,
    required this.requestFraction,
    required this.afterText,
    required this.priceDeltaPct,
    required this.deltaIsBad,
    required this.priceNote,
    required this.ageNote,
    required this.next,
  });

  /// What is unspent on that budget line before this request.
  final String budgetLeft;

  /// Bar segments: already committed, this request, and what would survive it.
  final double usedFraction;
  final double requestFraction;
  final String afterText;

  /// Movement against the last comparable transaction. Null where a percentage
  /// would be meaningless — a credit limit, a leave request.
  final double? priceDeltaPct;
  final bool deltaIsBad;
  final String priceNote;

  final String ageNote;
  final String next;
}

const List<_Evidence> _kEvidence = <_Evidence>[
  _Evidence(
    budgetLeft: 'TZS 486M committed for August, TZS 118M still unspent',
    usedFraction: 0.757,
    requestFraction: 0.198,
    afterText: 'TZS 22M would remain on the line after this run',
    priceDeltaPct: 9.1,
    deltaIsBad: true,
    priceNote: 'TZS 8M more than the 14 Aug run, on the same five suppliers',
    ageNote: 'Twiga Cement expects the transfer before Friday',
    next: 'Approving releases the file to the NMB supplier account. '
        'A. Mwakalinga sends it today, the bank clears it on Friday, and '
        'TZS 62M is left in the collection account.',
  ),
  _Evidence(
    budgetLeft: 'TZS 240M for building materials, TZS 64M still unspent',
    usedFraction: 0.733,
    requestFraction: 0.200,
    afterText: 'TZS 16M would remain for the rest of August',
    priceDeltaPct: 7.0,
    deltaIsBad: true,
    priceNote: 'TZS 16,900 a bag against TZS 15,800 on 2 Aug',
    ageNote: 'Dar has 2 days of cement cover left',
    next: 'Approving sends the order to Twiga Cement. S. Lyimo confirms the '
        'loading list this afternoon, and both loads reach Dar and Dodoma '
        'before the weekend.',
  ),
  _Evidence(
    budgetLeft: 'TZS 180M of credit for Kariakoo, TZS 48M still unused',
    usedFraction: 0.733,
    requestFraction: 0.222,
    afterText: 'TZS 8M of headroom would be left for every other Kariakoo '
        'customer',
    priceDeltaPct: null,
    deltaIsBad: true,
    priceNote: 'The existing TZS 40M limit is already fully drawn',
    ageNote: 'The account has been 91 days overdue throughout',
    next: 'Approving doubles the exposure on an account that has broken two '
        'payment promises. J. Mushi would lift the hold, and the Kariakoo '
        'counter could sell on credit again from tomorrow.',
  ),
  _Evidence(
    budgetLeft: 'TZS 210M of Lake-zone project sales, TZS 62M still open',
    usedFraction: 0.705,
    requestFraction: 0.162,
    afterText: 'TZS 28M of the zone plan would still be open after this order',
    priceDeltaPct: 6.3,
    deltaIsBad: false,
    priceNote: 'TZS 34M against TZS 32M for the same package in June',
    ageNote: 'The site wants delivery on Monday',
    next: 'Approving books the order and reserves the roofing sheets at '
        'Mwanza. E. Sanga schedules the delivery, and the TZS 68M already '
        'owed stays due on 22 Aug.',
  ),
  _Evidence(
    budgetLeft: 'TZS 40M of price concessions for Arusha, TZS 12.6M left',
    usedFraction: 0.685,
    requestFraction: 0.310,
    afterText: 'Almost nothing would be left for Arusha concessions this month',
    priceDeltaPct: 50.0,
    deltaIsBad: true,
    priceNote: '6% against the standard 4% — 2pp given away on every tile',
    ageNote: 'The tiles have not moved in 410 days',
    next: 'Approving lets P. Kimaro cut tile prices at the Arusha counter for '
        'the rest of the month. It clears old stock, and it takes the branch '
        'margin below 17%.',
  ),
  _Evidence(
    budgetLeft: 'TZS 96M of landed cost for August, TZS 25M unallocated',
    usedFraction: 0.740,
    requestFraction: 0.090,
    afterText: 'TZS 16.4M would remain unallocated after the reclass',
    priceDeltaPct: 8.9,
    deltaIsBad: true,
    priceNote: 'TZS 8.6M against TZS 7.9M reclassed in July',
    ageNote: 'It has waited 3 days and it closes the August cost of sales',
    next: 'Approving posts the journal to cost of sales. A. Mwakalinga can '
        'then close August, and 0.2pp of margin moves out of overheads into '
        'landed cost.',
  ),
  _Evidence(
    budgetLeft: 'Leave provision for Tanga, 18 of 28 days still available',
    usedFraction: 0.357,
    requestFraction: 0.357,
    afterText: '8 days of entitlement would remain for the rest of the year',
    priceDeltaPct: null,
    deltaIsBad: false,
    priceNote: 'Last leave taken in March, 6 days',
    ageNote: 'It has waited 3 days and the leave starts next week',
    next: 'Approving books the leave and confirms the cover from Moshi. '
        'L. Kessy updates the roster, and Tanga runs on a stand-in manager '
        'for ten days.',
  ),
];

// ---------------------------------------------------------------------------
// Copy helpers
// ---------------------------------------------------------------------------

String _docTypeTitle(String docType) => switch (docType) {
      'PURCHASE_ORDER' => 'Purchase order',
      'SALES_ORDER' => 'Sales order',
      'CREDIT_LIMIT' => 'Credit limit',
      'DISCOUNT' => 'Discount',
      'PAYMENT_BATCH' => 'Payment batch',
      'JOURNAL' => 'Journal',
      'LEAVE' => 'Leave request',
      _ => 'Approval',
    };

String _counterpartyLabel(String docType) => switch (docType) {
      'PURCHASE_ORDER' || 'PAYMENT_BATCH' || 'JOURNAL' => 'Supplier',
      'SALES_ORDER' || 'CREDIT_LIMIT' || 'DISCOUNT' => 'Customer',
      _ => 'Counterparty',
    };
