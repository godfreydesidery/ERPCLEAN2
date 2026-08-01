import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:intl/intl.dart';

import '../../app/theme.dart';
import '../../core/api/api_exception.dart';
import '../../core/money.dart';
import '../../models/auth.dart';
import '../../models/enums.dart';
import '../../models/pos.dart';
import '../../state/app_controller.dart';
import '../../state/providers.dart';
import '../../widgets/ui.dart';

class OpenShiftScreen extends ConsumerStatefulWidget {
  const OpenShiftScreen({super.key});
  @override
  ConsumerState<OpenShiftScreen> createState() => _OpenShiftScreenState();
}

class _OpenShiftScreenState extends ConsumerState<OpenShiftScreen> {
  final _float = TextEditingController(text: '0');
  String? _tillUid;
  late Future<List<PosTill>> _tills;

  @override
  void initState() {
    super.initState();
    _tills = _loadTills();
  }

  @override
  void dispose() {
    _float.dispose();
    super.dispose();
  }

  Future<List<PosTill>> _loadTills() {
    final ctx = ref.read(appControllerProvider).context!;
    return ref
        .read(tillServiceProvider)
        .listByBranch(ctx.companyId, ctx.branchId);
  }

  /// Re-fetches the tills. Wired to pull-to-refresh, the Retry button and every
  /// action that frees a till, so a cashier never has to restart the app to see
  /// that a supervisor released one.
  Future<void> _reload() async {
    final tills = _loadTills();
    setState(() {
      _tillUid = null;
      _tills = tills;
    });
    try {
      await tills;
    } catch (_) {/* the grid renders the failure itself */}
  }

  Future<void> _open() async {
    final app = ref.read(appControllerProvider);
    if (_tillUid == null) {
      showToast(context, 'Pick a till first.');
      return;
    }
    final float = double.tryParse(_float.text.trim()) ?? 0;
    await ref
        .read(appControllerProvider.notifier)
        .openShift(_tillUid!, float, app.mode);
    final err = ref.read(appControllerProvider).error;
    if (err != null && mounted) showToast(context, err);
  }

  @override
  Widget build(BuildContext context) {
    final app = ref.watch(appControllerProvider);
    return Scaffold(
      body: SafeArea(
        child: RefreshIndicator(
          onRefresh: _reload,
          child: Center(
            child: SingleChildScrollView(
              physics: const AlwaysScrollableScrollPhysics(),
              padding: const EdgeInsets.symmetric(horizontal: 24, vertical: 28),
              child: ConstrainedBox(
                constraints: const BoxConstraints(maxWidth: 720),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    _head(app),
                    if (app.context?.branchFallback ?? false) ...[
                      const SizedBox(height: 14),
                      BranchFallbackBanner(app.context!.branch.name),
                    ],
                    if (app.notice != null) ...[
                      const SizedBox(height: 14),
                      NoticeStrip(app.notice!,
                          onDismiss: () => ref
                              .read(appControllerProvider.notifier)
                              .clearNotice()),
                    ],
                    const SizedBox(height: 24),
                    const SectionLabel('Business mode'),
                    _modeCards(app),
                    const SizedBox(height: 12),
                    Row(
                      children: [
                        const Expanded(child: SectionLabel('Choose a till')),
                        TextButton.icon(
                          onPressed: _reload,
                          icon: const Icon(Icons.refresh, size: 16),
                          label: const Text('Refresh'),
                        ),
                        if (app.can('POS.TILL.MANAGE'))
                          TextButton.icon(
                            onPressed: _createTill,
                            icon: const Icon(Icons.add, size: 16),
                            label: const Text('New till'),
                          ),
                      ],
                    ),
                    _tillGrid(app),
                    const SizedBox(height: 24),
                    _floatRow(app),
                  ],
                ),
              ),
            ),
          ),
        ),
      ),
    );
  }

  Widget _head(AppData app) {
    return Row(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            const Brand(),
            const SizedBox(height: 14),
            const Text('Open shift',
                style: TextStyle(fontSize: 24, fontWeight: FontWeight.w700)),
            const SizedBox(height: 4),
            Text(
              '${app.context?.company.name ?? ''} · ${app.context?.branch.name ?? ''}',
              style: const TextStyle(color: AppColors.ink2),
            ),
          ],
        ),
        const Spacer(),
        Row(
          children: [
            Avatar(app.me?.displayName ?? '?', size: 42),
            const SizedBox(width: 11),
            Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(app.me?.displayName ?? '',
                    style: const TextStyle(fontWeight: FontWeight.w700)),
                Text('@${app.me?.username ?? ''}',
                    style: const TextStyle(color: AppColors.ink3, fontSize: 12)),
              ],
            ),
            const SizedBox(width: 8),
            IconButton(
              tooltip: 'Sign out',
              onPressed: () =>
                  ref.read(appControllerProvider.notifier).logout(),
              icon: const Icon(Icons.logout, color: AppColors.ink2),
            ),
          ],
        ),
      ],
    );
  }

  Widget _modeCards(AppData app) {
    return Row(
      children: BusinessMode.values.map((m) {
        final active = app.mode == m;
        return Expanded(
          child: Padding(
            padding: EdgeInsets.only(right: m == BusinessMode.restaurant ? 0 : 12),
            child: InkWell(
              borderRadius: AppRadii.brLg,
              onTap: () => ref.read(appControllerProvider.notifier).setMode(m),
              child: Container(
                padding: const EdgeInsets.all(16),
                decoration: BoxDecoration(
                  color: active ? AppColors.brandSoft : AppColors.panel,
                  borderRadius: AppRadii.brLg,
                  border: Border.all(
                      color: active ? AppColors.brand : AppColors.line2,
                      width: 1.5),
                ),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(m.glyph, style: const TextStyle(fontSize: 26)),
                    const SizedBox(height: 6),
                    Text(m.label,
                        style: const TextStyle(
                            fontSize: 15, fontWeight: FontWeight.w700)),
                    const SizedBox(height: 2),
                    Text(m.blurb,
                        style: const TextStyle(
                            color: AppColors.ink2, fontSize: 12)),
                  ],
                ),
              ),
            ),
          ),
        );
      }).toList(),
    );
  }

  Widget _tillGrid(AppData app) {
    return FutureBuilder<List<PosTill>>(
      future: _tills,
      builder: (context, snap) {
        if (snap.connectionState == ConnectionState.waiting) {
          return const Padding(
            padding: EdgeInsets.all(24),
            child: Center(child: CircularProgressIndicator()),
          );
        }
        if (snap.hasError) {
          final e = snap.error;
          final msg = e is ApiException ? e.message : 'Could not load tills.';
          return _emptyBox(Icons.error_outline, msg, retry: true);
        }
        final tills = (snap.data ?? []).where((t) => t.isActive).toList();
        if (tills.isEmpty) {
          return _emptyBox(Icons.point_of_sale_outlined,
              'No active tills on this branch yet.');
        }
        return GridView.builder(
          shrinkWrap: true,
          physics: const NeverScrollableScrollPhysics(),
          gridDelegate: const SliverGridDelegateWithMaxCrossAxisExtent(
            maxCrossAxisExtent: 200,
            mainAxisExtent: 78,
            crossAxisSpacing: 12,
            mainAxisSpacing: 12,
          ),
          itemCount: tills.length,
          itemBuilder: (context, i) {
            final t = tills[i];
            final occupied = t.hasOpenSession;
            final mine = t.isHeldBy(app.cashierId);
            final active = _tillUid == t.uid;
            // An occupied till can't host a second session (backend 409s), but a
            // dead tile is a trap: the commonest cause is the cashier's OWN app
            // being force-closed, and only a counted cash-up ever closes a
            // shift. So it stays tappable and explains itself — resume/cash up
            // if it is theirs, who holds it and what to do if it is not.
            return Opacity(
              opacity: occupied && !mine ? .55 : 1,
              child: InkWell(
                borderRadius: AppRadii.brSm,
                onTap: occupied
                    ? () => _showOccupied(t, mine: mine, app: app)
                    : () => setState(() => _tillUid = t.uid),
                child: Container(
                  padding: const EdgeInsets.all(16),
                  decoration: BoxDecoration(
                    color: active ? AppColors.brandSoft : AppColors.panel,
                    borderRadius: AppRadii.brSm,
                    border: Border.all(
                        color: active ? AppColors.brand : AppColors.line2,
                        width: 1.5),
                  ),
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    mainAxisAlignment: MainAxisAlignment.center,
                    children: [
                      Row(
                        children: [
                          Expanded(
                            child: Text(t.name,
                                maxLines: 1,
                                overflow: TextOverflow.ellipsis,
                                style: const TextStyle(
                                    fontWeight: FontWeight.w700)),
                          ),
                          Icon(Icons.circle,
                              size: 9,
                              color: !occupied
                                  ? AppColors.ok
                                  : (mine
                                      ? AppColors.warn
                                      : AppColors.danger)),
                        ],
                      ),
                      const SizedBox(height: 3),
                      Text(
                          !occupied
                              ? t.code
                              : (mine
                                  ? '${t.code} · Your shift'
                                  : '${t.code} · In use'),
                          maxLines: 1,
                          overflow: TextOverflow.ellipsis,
                          style: const TextStyle(
                              color: AppColors.ink2, fontSize: 12)),
                    ],
                  ),
                ),
              ),
            );
          },
        );
      },
    );
  }

  Widget _emptyBox(IconData icon, String msg, {bool retry = false}) {
    return Container(
      width: double.infinity,
      padding: const EdgeInsets.symmetric(vertical: 28, horizontal: 20),
      decoration: BoxDecoration(
        color: AppColors.panel,
        borderRadius: AppRadii.brSm,
        border: Border.all(color: AppColors.line),
      ),
      child: Column(
        children: [
          Icon(icon, size: 34, color: AppColors.ink3),
          const SizedBox(height: 10),
          Text(msg,
              textAlign: TextAlign.center,
              style: const TextStyle(color: AppColors.ink2)),
          if (retry) ...[
            const SizedBox(height: 12),
            OrbixButton(
                label: 'Retry', kind: BtnKind.ghost, onPressed: _reload),
          ],
        ],
      ),
    );
  }

  Widget _floatRow(AppData app) {
    return Row(
      crossAxisAlignment: CrossAxisAlignment.end,
      children: [
        Expanded(
          child: OrbixField(
            label: 'Opening float (${app.currency})',
            controller: _float,
            keyboardType: const TextInputType.numberWithOptions(decimal: true),
            inputFormatters: [
              FilteringTextInputFormatter.allow(RegExp(r'[0-9.]'))
            ],
            big: true,
            prefixIcon: Icons.savings_outlined,
          ),
        ),
        const SizedBox(width: 16),
        OrbixButton(
          label: 'Open session',
          icon: Icons.lock_open,
          large: true,
          busy: app.busy,
          onPressed: app.busy ? null : _open,
        ),
      ],
    );
  }

  // ------------------------------------------------------------ occupied till

  /// Explains an occupied till instead of dead-ending on it.
  ///
  /// If the shift is the signed-in cashier's own (the usual cause: their app was
  /// force-closed and nothing closes a shift but a counted cash-up) they can
  /// resume it or cash it up right here. Otherwise they learn who holds it and
  /// what has to happen next.
  Future<void> _showOccupied(PosTill t,
      {required bool mine, required AppData app}) async {
    final sessionUid = t.openSessionUid;
    final since = _since(t.openSessionOpenedAt);

    if (!mine || sessionUid == null) {
      await showDialog<void>(
        context: context,
        builder: (_) => AlertDialog(
          shape: RoundedRectangleBorder(borderRadius: AppRadii.brLg),
          title: const Text('Till in use'),
          content: Text(
            'Another cashier has a shift open on ${t.name}$since. A shift only '
            'ends once the drawer is counted — that cashier can close it from '
            'their own sign-in, or an administrator can close it in the ERP.',
            style: const TextStyle(color: AppColors.ink2),
          ),
          actions: [
            OrbixButton(
                label: 'Got it', onPressed: () => Navigator.pop(context)),
          ],
        ),
      );
      return;
    }

    final canClose = app.can(Perms.sessionClose);
    final action = await showDialog<String>(
      context: context,
      builder: (_) => AlertDialog(
        shape: RoundedRectangleBorder(borderRadius: AppRadii.brLg),
        title: const Text('Your shift is still open'),
        content: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(
              'You already have a shift open on ${t.name}$since. Carry on where '
              'you left off, or count the drawer and close it.',
              style: const TextStyle(color: AppColors.ink2),
            ),
            if (!canClose) ...[
              const SizedBox(height: 10),
              const Text(
                "If you're not carrying on, ask a supervisor to close it for you.",
                style: TextStyle(fontSize: 12, color: AppColors.ink3),
              ),
            ],
          ],
        ),
        actions: [
          TextButton(
              onPressed: () => Navigator.pop(context),
              child: const Text('Cancel')),
          if (canClose)
            OrbixButton(
                label: 'Close shift',
                kind: BtnKind.ghost,
                onPressed: () => Navigator.pop(context, 'close')),
          OrbixButton(
              label: 'Resume shift',
              icon: Icons.play_arrow,
              onPressed: () => Navigator.pop(context, 'resume')),
        ],
      ),
    );
    if (!mounted || action == null) return;

    if (action == 'resume') {
      await ref
          .read(appControllerProvider.notifier)
          .resumeShift(sessionUid, app.mode);
      final err = ref.read(appControllerProvider).error;
      if (err != null && mounted) showToast(context, err);
      return;
    }

    final closed = await showDialog<bool>(
      context: context,
      builder: (_) => _CashUpDialog(
          sessionUid: sessionUid, tillName: t.name, currency: app.currency),
    );
    if (closed == true && mounted) {
      showToast(context, 'Shift closed. The till is free again.', ok: true);
      await _reload();
    }
  }

  /// " since 09:14" / " since Aug 1, 09:14" for a shift opened on an earlier day.
  String _since(DateTime? openedAt) {
    if (openedAt == null) return '';
    final local = openedAt.toLocal();
    final now = DateTime.now();
    final sameDay = local.year == now.year &&
        local.month == now.month &&
        local.day == now.day;
    final fmt = DateFormat(sameDay ? 'HH:mm' : 'MMM d, HH:mm');
    return ' since ${fmt.format(local)}';
  }

  Future<void> _createTill() async {
    final nameCtrl = TextEditingController();
    final ok = await showDialog<bool>(
      context: context,
      builder: (_) => AlertDialog(
        shape: RoundedRectangleBorder(borderRadius: AppRadii.brLg),
        title: const Text('New till'),
        content: OrbixField(
            label: 'Till name', controller: nameCtrl, autofocus: true),
        actions: [
          TextButton(
              onPressed: () => Navigator.pop(context, false),
              child: const Text('Cancel')),
          OrbixButton(
              label: 'Create',
              onPressed: () => Navigator.pop(context, true)),
        ],
      ),
    );
    if (ok != true || nameCtrl.text.trim().isEmpty) return;
    final ctx = ref.read(appControllerProvider).context!;
    try {
      await ref.read(tillServiceProvider).create(
          ctx.companyUid, ctx.branchId, nameCtrl.text.trim());
      if (mounted) showToast(context, 'Till created.', ok: true);
      await _reload();
    } on ApiException catch (e) {
      if (mounted) showToast(context, e.message);
    }
  }
}

// ============================================================ orphan cash-up

/// Closes an orphaned shift from the till picker. This is a real cash-up, not a
/// tidy-up: the counted amount is what the cashier physically counts in the
/// drawer, so the field starts EMPTY and is never pre-filled with the expected
/// figure — a defaulted count would post a fake zero variance and hide a
/// genuine shortage. Requires `POS.SESSION.CLOSE`, which the cashier role holds.
class _CashUpDialog extends ConsumerStatefulWidget {
  const _CashUpDialog({
    required this.sessionUid,
    required this.tillName,
    required this.currency,
  });

  final String sessionUid;
  final String tillName;
  final String currency;

  @override
  ConsumerState<_CashUpDialog> createState() => _CashUpDialogState();
}

class _CashUpDialogState extends ConsumerState<_CashUpDialog> {
  final _counted = TextEditingController();
  bool _busy = false;
  PosSession? _closed;

  @override
  void dispose() {
    _counted.dispose();
    super.dispose();
  }

  Future<void> _close() async {
    final counted = double.tryParse(_counted.text.trim());
    if (counted == null) {
      showToast(context, 'Count the drawer and enter the cash total.');
      return;
    }
    setState(() => _busy = true);
    try {
      final session = await ref
          .read(sessionServiceProvider)
          .close(widget.sessionUid, counted);
      setState(() {
        _busy = false;
        _closed = session;
      });
    } on ApiException catch (e) {
      setState(() => _busy = false);
      if (mounted) showToast(context, e.message);
    }
  }

  @override
  Widget build(BuildContext context) {
    final session = _closed;
    return AlertDialog(
      shape: RoundedRectangleBorder(borderRadius: AppRadii.brLg),
      title: Text(session == null ? 'Close your shift' : 'Shift closed'),
      content: session == null
          ? Column(
              mainAxisSize: MainAxisSize.min,
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                Text(
                  'Count the cash in the ${widget.tillName} drawer and enter the '
                  'total. This is what the shift is settled against.',
                  style: const TextStyle(color: AppColors.ink2),
                ),
                const SizedBox(height: 14),
                OrbixField(
                    label: 'Counted cash (${widget.currency})',
                    controller: _counted,
                    autofocus: true,
                    big: true,
                    keyboardType:
                        const TextInputType.numberWithOptions(decimal: true),
                    inputFormatters: [
                      FilteringTextInputFormatter.allow(RegExp(r'[0-9.]'))
                    ]),
              ],
            )
          : _variance(session),
      actions: session == null
          ? [
              TextButton(
                  onPressed: () => Navigator.pop(context, false),
                  child: const Text('Cancel')),
              OrbixButton(
                  label: 'Close shift', busy: _busy, onPressed: _close),
            ]
          : [
              OrbixButton(
                  label: 'Done', onPressed: () => Navigator.pop(context, true)),
            ],
    );
  }

  Widget _variance(PosSession session) {
    final variance = session.varianceAmount ?? 0;
    final short = variance < 0;
    return Column(
      mainAxisSize: MainAxisSize.min,
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        _row('Expected', session.expectedCashAmount ?? 0),
        _row('Counted', session.countedCashAmount ?? 0),
        _row('Variance', variance, danger: short),
        const SizedBox(height: 8),
        const Text(
            'A supervisor reconciles the shift to post the variance. The till is '
            'free to use now.',
            style: TextStyle(fontSize: 12, color: AppColors.ink3)),
      ],
    );
  }

  Widget _row(String label, double value, {bool danger = false}) => Padding(
        padding: const EdgeInsets.symmetric(vertical: 4),
        child: Row(
          mainAxisAlignment: MainAxisAlignment.spaceBetween,
          children: [
            Text(label, style: const TextStyle(color: AppColors.ink2)),
            Text(formatMoneyParts(value, widget.currency),
                style: numStyle(
                    weight: FontWeight.w700,
                    color: danger ? AppColors.danger : AppColors.ink)),
          ],
        ),
      );
}
