import 'package:flutter/material.dart';

import '../app/app_scope.dart';
import '../app/format.dart';
import '../app/theme.dart';
import '../core/api/api_exception.dart';
import '../services/operations_service.dart';
import '../widgets/async_view.dart';
import '../widgets/common.dart';
import '../widgets/kit.dart';
import 'till_z.dart';

/// Closed tills: sessions counted and closed but not yet reconciled (at the
/// till or from this app), and the recent reconciled ones with their Z read.
class TillHistoryScreen extends StatefulWidget {
  const TillHistoryScreen({super.key});

  @override
  State<TillHistoryScreen> createState() => _TillHistoryScreenState();
}

class _History {
  const _History(this.closed, this.reconciled);

  final List<SessionRow> closed;
  final List<SessionRow> reconciled;
}

class _TillHistoryScreenState extends State<TillHistoryScreen> {
  final _key = GlobalKey<AsyncViewState<_History>>();

  Future<_History> _load() async {
    final ops = AppScope.of(context).operations;
    final results = await Future.wait([
      ops.sessionsIn('CLOSED'),
      ops.sessionsIn('RECONCILED'),
    ]);
    return _History(results[0], results[1]);
  }

  Future<void> _open(SessionRow s) async {
    final changed = await Navigator.of(context).push<bool>(
      MaterialPageRoute(builder: (_) => _SessionScreen(session: s)),
    );
    if (changed == true) _key.currentState?.reload();
  }

  @override
  Widget build(BuildContext context) {
    final session = AppScope.of(context).session;
    return Scaffold(
      backgroundColor: HqColors.bg,
      appBar: AppBar(title: const Text('Closed tills', style: HqText.title)),
      body: !session.can('POS.SESSION.VIEW')
          ? const NoPermission(code: 'POS.SESSION.VIEW')
          : AsyncView<_History>(
              key: _key,
              load: _load,
              isEmpty: (h) => h.closed.isEmpty && h.reconciled.isEmpty,
              emptyIcon: Icons.history_rounded,
              emptyTitle: 'No closed tills yet',
              emptyDetail: 'A till appears here once its session is closed.',
              builder: (context, h) => ListView(
                padding: const EdgeInsets.fromLTRB(20, 8, 20, 28),
                children: [
                  if (h.closed.isNotEmpty) ...[
                    SectionLabel(
                      text: 'WAITING TO BE RECONCILED',
                      trailing: '${h.closed.length}',
                    ),
                    const SizedBox(height: 10),
                    for (final s in h.closed) ...[
                      _Row(session: s, onTap: () => _open(s)),
                      const SizedBox(height: 10),
                    ],
                    const SizedBox(height: 12),
                  ],
                  if (h.reconciled.isNotEmpty) ...[
                    const SectionLabel(text: 'RECONCILED'),
                    const SizedBox(height: 10),
                    for (final s in h.reconciled) ...[
                      _Row(session: s, onTap: () => _open(s)),
                      const SizedBox(height: 10),
                    ],
                  ],
                ],
              ),
            ),
    );
  }
}

class _Row extends StatelessWidget {
  const _Row({required this.session, required this.onTap});

  final SessionRow session;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    final v = session.variance;
    return Material(
      color: HqColors.panel,
      borderRadius: BorderRadius.circular(16),
      child: InkWell(
        onTap: onTap,
        borderRadius: BorderRadius.circular(16),
        child: Container(
          padding: const EdgeInsets.all(14),
          decoration: BoxDecoration(
            borderRadius: BorderRadius.circular(16),
            border: Border.all(color: HqColors.line),
          ),
          child: Row(
            children: [
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(
                      session.tillName,
                      maxLines: 1,
                      overflow: TextOverflow.ellipsis,
                      style: const TextStyle(
                        fontSize: 15,
                        fontWeight: FontWeight.w700,
                        color: HqColors.ink,
                      ),
                    ),
                    const SizedBox(height: 2),
                    Text(
                      [
                        session.cashierName,
                        if (session.branchName != null) session.branchName!,
                        'closed ${when(session.closedAt)}',
                      ].join(' · '),
                      maxLines: 2,
                      overflow: TextOverflow.ellipsis,
                      style: HqText.tiny,
                    ),
                  ],
                ),
              ),
              const SizedBox(width: 10),
              if (v != null)
                Text(
                  overShort(v),
                  style: TextStyle(
                    fontSize: 13,
                    fontWeight: FontWeight.w700,
                    color: overShortColor(v),
                  ),
                ),
              const SizedBox(width: 4),
              const Icon(Icons.chevron_right, size: 20, color: HqColors.ink3),
            ],
          ),
        ),
      ),
    );
  }
}

/// One closed session: the Z read if reconciled; otherwise its count and a
/// Reconcile button.
class _SessionScreen extends StatefulWidget {
  const _SessionScreen({required this.session});

  final SessionRow session;

  @override
  State<_SessionScreen> createState() => _SessionScreenState();
}

class _SessionScreenState extends State<_SessionScreen> {
  ZRead? _z;
  bool _busy = false;

  Future<void> _reconcile() async {
    final ok = await showDialog<bool>(
      context: context,
      builder: (context) => AlertDialog(
        title: const Text('Reconcile this till?'),
        content: Text(
          '${overShort(widget.session.variance ?? 0)} is posted to the books. '
          'This is final.',
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.of(context).pop(false),
            child: const Text('Cancel'),
          ),
          FilledButton(
            onPressed: () => Navigator.of(context).pop(true),
            child: const Text('Reconcile'),
          ),
        ],
      ),
    );
    if (ok != true || !mounted) return;
    setState(() => _busy = true);
    try {
      final z = await AppScope.of(context)
          .operations
          .reconcile(widget.session.uid);
      if (mounted) setState(() => _z = z);
    } on ApiException catch (e) {
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(SnackBar(
          behavior: SnackBarBehavior.floating,
          backgroundColor: HqColors.bad,
          content: Text(e.message),
        ));
      }
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final s = widget.session;
    final canReconcile =
        AppScope.of(context).session.can('POS.SESSION.RECONCILE');

    Widget body;
    if (_z != null) {
      body = ListView(
        padding: const EdgeInsets.fromLTRB(20, 8, 20, 28),
        children: [ZReadView(z: _z!)],
      );
    } else if (s.isReconciled) {
      body = AsyncView<ZRead>(
        load: () => AppScope.of(context).operations.zRead(s.uid),
        builder: (context, z) => ListView(
          padding: const EdgeInsets.fromLTRB(20, 8, 20, 28),
          children: [ZReadView(z: z)],
        ),
      );
    } else {
      body = ListView(
        padding: const EdgeInsets.fromLTRB(20, 8, 20, 28),
        children: [
          SectionLabel(text: s.tillName.toUpperCase()),
          const SizedBox(height: 10),
          HqCard(
            child: Column(
              children: [
                FigureRow(label: 'Cashier', value: s.cashierName),
                const Divider(height: 14),
                FigureRow(label: 'Opened', value: when(s.openedAt)),
                const Divider(height: 14),
                FigureRow(label: 'Closed', value: when(s.closedAt)),
                const Divider(height: 14),
                FigureRow(
                  label: 'Expected in the drawer',
                  value: s.expectedCash == null ? '-' : tzs(s.expectedCash!),
                ),
                const Divider(height: 14),
                FigureRow(
                  label: 'Counted',
                  value: s.countedCash == null ? '-' : tzs(s.countedCash!),
                ),
                const Divider(height: 16, thickness: 1.4),
                FigureRow(
                  label: 'Over / short',
                  value: overShort(s.variance ?? 0),
                  emphasise: true,
                  valueColor: overShortColor(s.variance ?? 0),
                ),
              ],
            ),
          ),
          if (s.notes != null && s.notes!.isNotEmpty) ...[
            const SizedBox(height: 10),
            Text('Notes: ${s.notes}', style: HqText.body),
          ],
          const SizedBox(height: 22),
          if (canReconcile)
            FilledButton(
              onPressed: _busy ? null : _reconcile,
              child: _busy
                  ? const SizedBox(
                      width: 20,
                      height: 20,
                      child: CircularProgressIndicator(
                        strokeWidth: 2.2,
                        color: Colors.white,
                      ),
                    )
                  : const Text('Reconcile'),
            )
          else
            Text(
              'Reconciling needs the POS.SESSION.RECONCILE permission.',
              style: HqText.tiny,
            ),
        ],
      );
    }

    return Scaffold(
      backgroundColor: HqColors.bg,
      appBar: AppBar(
        title: Text(s.isReconciled || _z != null ? 'Z read' : 'Closed till',
            style: HqText.title),
        leading: BackButton(
          onPressed: () => Navigator.of(context).pop(_z != null),
        ),
      ),
      body: body,
    );
  }
}
