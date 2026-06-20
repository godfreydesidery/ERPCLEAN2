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
import '../../models/sale.dart';
import '../../state/app_controller.dart';
import '../../state/providers.dart';
import '../../widgets/ui.dart';
import '../receipt/receipt_view.dart';

/// The session menu drawer (prototype ☰): X-read, payout, today's sales, close,
/// reconcile. Actions are permission-gated to honour segregation of duties.
Future<void> openSessionMenu(BuildContext context, WidgetRef ref) {
  return showGeneralDialog(
    context: context,
    barrierDismissible: true,
    barrierLabel: 'Session',
    barrierColor: Colors.black54,
    transitionDuration: const Duration(milliseconds: 160),
    pageBuilder: (_, _, _) => const SizedBox.shrink(),
    transitionBuilder: (context, anim, _, _) {
      return Align(
        alignment: Alignment.centerRight,
        child: FractionalTranslation(
          translation: Offset(1 - anim.value, 0),
          child: const _SessionDrawer(),
        ),
      );
    },
  );
}

class _SessionDrawer extends ConsumerWidget {
  const _SessionDrawer();

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final app = ref.watch(appControllerProvider);
    final s = app.shift;
    final df = DateFormat('HH:mm');
    return Material(
      child: Container(
        width: 380,
        height: double.infinity,
        color: AppColors.panel,
        child: SafeArea(
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              Padding(
                padding: const EdgeInsets.fromLTRB(18, 14, 8, 6),
                child: Row(
                  children: [
                    const Text('Session',
                        style: TextStyle(
                            fontSize: 18, fontWeight: FontWeight.w700)),
                    const Spacer(),
                    IconButton(
                        onPressed: () => Navigator.pop(context),
                        icon: const Icon(Icons.close)),
                  ],
                ),
              ),
              if (s != null)
                Padding(
                  padding: const EdgeInsets.symmetric(horizontal: 12),
                  child: GridView.count(
                    crossAxisCount: 2,
                    shrinkWrap: true,
                    physics: const NeverScrollableScrollPhysics(),
                    childAspectRatio: 3.4,
                    children: [
                      _info('Session', s.sessionNumber),
                      _info('Status', s.status.wire),
                      _info('Opened',
                          s.openedAt == null ? '—' : df.format(s.openedAt!.toLocal())),
                      _info('Float',
                          formatMoneyParts(s.openingFloatAmount, app.currency)),
                    ],
                  ),
                ),
              const Divider(),
              Expanded(
                child: ListView(
                  padding: const EdgeInsets.symmetric(horizontal: 8),
                  children: [
                    _action(context, Icons.summarize_outlined, 'X-read',
                        'Mid-shift drawer report',
                        enabled: app.can(Perms.sessionView),
                        onTap: () => _xRead(context, ref)),
                    _action(context, Icons.payments_outlined, 'Cash payout',
                        'Refund or drawer drop',
                        enabled: app.can(Perms.sessionOpen),
                        onTap: () => _payout(context, ref)),
                    _action(context, Icons.receipt_long_outlined,
                        "Today's sales", 'Look up & reprint a receipt',
                        enabled: true, onTap: () => _todaysSales(context, ref)),
                    const Divider(),
                    _action(context, Icons.lock_outline, 'Close session',
                        'Count the drawer → variance',
                        enabled: app.can(Perms.sessionClose) && s != null &&
                            s.status.isOpen,
                        onTap: () => _close(context, ref)),
                    _action(context, Icons.fact_check_outlined,
                        'Reconcile (Z-read)', 'Post variance — supervisor',
                        enabled: app.can(Perms.sessionReconcile) && s != null,
                        locked: !app.can(Perms.sessionReconcile),
                        onTap: () => _reconcile(context, ref)),
                  ],
                ),
              ),
              Padding(
                padding: const EdgeInsets.all(12),
                child: OrbixButton(
                    label: 'Sign out',
                    icon: Icons.logout,
                    kind: BtnKind.ghost,
                    block: true,
                    onPressed: () {
                      Navigator.pop(context);
                      ref.read(appControllerProvider.notifier).logout();
                    }),
              ),
            ],
          ),
        ),
      ),
    );
  }

  Widget _info(String label, String value) => Padding(
        padding: const EdgeInsets.all(6),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          mainAxisAlignment: MainAxisAlignment.center,
          children: [
            Text(label,
                style: const TextStyle(fontSize: 11, color: AppColors.ink3)),
            Text(value,
                maxLines: 1,
                overflow: TextOverflow.ellipsis,
                style: const TextStyle(
                    fontSize: 14, fontWeight: FontWeight.w600)),
          ],
        ),
      );

  Widget _action(BuildContext context, IconData icon, String title,
      String subtitle,
      {required bool enabled,
      bool locked = false,
      required VoidCallback onTap}) {
    return Opacity(
      opacity: enabled ? 1 : .4,
      child: InkWell(
        borderRadius: AppRadii.brSm,
        onTap: enabled ? onTap : null,
        child: Padding(
          padding: const EdgeInsets.all(14),
          child: Row(
            children: [
              Icon(icon, size: 20, color: AppColors.ink2),
              const SizedBox(width: 14),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(title,
                        style: const TextStyle(fontWeight: FontWeight.w600)),
                    Text(subtitle,
                        style: TextStyle(
                            fontSize: 12,
                            color:
                                locked ? AppColors.warn : AppColors.ink3)),
                  ],
                ),
              ),
              if (locked)
                const Icon(Icons.lock, size: 15, color: AppColors.warn),
            ],
          ),
        ),
      ),
    );
  }

  // ---------------------------------------------------------------- actions

  Future<void> _xRead(BuildContext context, WidgetRef ref) async {
    final app = ref.read(appControllerProvider);
    final uid = app.shift?.uid;
    if (uid == null) return;
    try {
      final x = await ref.read(sessionServiceProvider).xRead(uid);
      if (!context.mounted) return;
      showDialog(
        context: context,
        builder: (_) => _ReportDialog(
          title: 'X-read',
          rows: [
            ('Opening float', x.openingFloatAmount),
            ('Sales', x.totalSalesAmount),
            ('Payouts', -x.totalPayoutsNetAmount),
            ('Expected cash', x.expectedCashAmount),
          ],
          currency: app.currency,
          footer: '${x.invoiceCount} invoices',
        ),
      );
    } on ApiException catch (e) {
      if (context.mounted) showToast(context, e.message);
    }
  }

  Future<void> _payout(BuildContext context, WidgetRef ref) async {
    final app = ref.read(appControllerProvider);
    final uid = app.shift?.uid;
    if (uid == null) return;
    await showDialog(
      context: context,
      builder: (_) => _PayoutDialog(sessionUid: uid, currency: app.currency),
    );
    ref.read(appControllerProvider.notifier).refreshShift();
  }

  Future<void> _todaysSales(BuildContext context, WidgetRef ref) async {
    final app = ref.read(appControllerProvider);
    final companyId = app.context?.companyId;
    if (companyId == null) return;
    showDialog(
      context: context,
      builder: (_) => _TodaysSalesDialog(companyId: companyId),
    );
  }

  Future<void> _close(BuildContext context, WidgetRef ref) async {
    final app = ref.read(appControllerProvider);
    final uid = app.shift?.uid;
    if (uid == null) return;
    Navigator.pop(context); // close the drawer
    await showDialog(
      context: context,
      builder: (_) => _CloseDialog(sessionUid: uid, currency: app.currency),
    );
  }

  Future<void> _reconcile(BuildContext context, WidgetRef ref) async {
    final app = ref.read(appControllerProvider);
    final uid = app.shift?.uid;
    if (uid == null) return;
    Navigator.pop(context);
    await showDialog(
      context: context,
      builder: (_) => _ReconcileDialog(sessionUid: uid, currency: app.currency),
    );
  }
}

// ============================================================ report dialog

class _ReportDialog extends StatelessWidget {
  const _ReportDialog(
      {required this.title,
      required this.rows,
      required this.currency,
      this.footer});
  final String title;
  final List<(String, double)> rows;
  final String currency;
  final String? footer;

  @override
  Widget build(BuildContext context) {
    return AlertDialog(
      shape: RoundedRectangleBorder(borderRadius: AppRadii.brLg),
      title: Text(title),
      content: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          for (final r in rows)
            Padding(
              padding: const EdgeInsets.symmetric(vertical: 6),
              child: Row(
                mainAxisAlignment: MainAxisAlignment.spaceBetween,
                children: [
                  Text(r.$1, style: const TextStyle(color: AppColors.ink2)),
                  Text(formatMoneyParts(r.$2, currency),
                      style: numStyle(weight: FontWeight.w700)),
                ],
              ),
            ),
          if (footer != null) ...[
            const Divider(),
            Text(footer!, style: const TextStyle(color: AppColors.ink3)),
          ],
        ],
      ),
      actions: [
        OrbixButton(label: 'Close', onPressed: () => Navigator.pop(context)),
      ],
    );
  }
}

// ============================================================ payout

class _PayoutDialog extends ConsumerStatefulWidget {
  const _PayoutDialog({required this.sessionUid, required this.currency});
  final String sessionUid;
  final String currency;
  @override
  ConsumerState<_PayoutDialog> createState() => _PayoutDialogState();
}

class _PayoutDialogState extends ConsumerState<_PayoutDialog> {
  PosPayoutType _type = PosPayoutType.paidOut;
  final _amount = TextEditingController();
  final _reason = TextEditingController();
  bool _busy = false;

  @override
  void dispose() {
    _amount.dispose();
    _reason.dispose();
    super.dispose();
  }

  Future<void> _submit() async {
    final amt = double.tryParse(_amount.text.trim()) ?? 0;
    if (amt <= 0) {
      showToast(context, 'Enter an amount.');
      return;
    }
    setState(() => _busy = true);
    try {
      await ref.read(sessionServiceProvider).payout(
          widget.sessionUid, _type, amt, _reason.text.trim());
      if (mounted) {
        Navigator.pop(context);
        showToast(context, 'Payout recorded.', ok: true);
      }
    } on ApiException catch (e) {
      setState(() => _busy = false);
      if (mounted) showToast(context, e.message);
    }
  }

  @override
  Widget build(BuildContext context) {
    return AlertDialog(
      shape: RoundedRectangleBorder(borderRadius: AppRadii.brLg),
      title: const Text('Cash payout'),
      content: Column(
        mainAxisSize: MainAxisSize.min,
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          Row(
            children: PosPayoutType.values.map((t) {
              final active = _type == t;
              return Expanded(
                child: Padding(
                  padding: const EdgeInsets.only(right: 8),
                  child: InkWell(
                    borderRadius: AppRadii.brSm,
                    onTap: () => setState(() => _type = t),
                    child: Container(
                      padding: const EdgeInsets.symmetric(vertical: 10),
                      alignment: Alignment.center,
                      decoration: BoxDecoration(
                        color: active ? AppColors.brandSoft : AppColors.panel,
                        borderRadius: AppRadii.brSm,
                        border: Border.all(
                            color: active ? AppColors.brand : AppColors.line2),
                      ),
                      child: Text(t.label,
                          style: TextStyle(
                              fontWeight: FontWeight.w600,
                              color:
                                  active ? AppColors.brandD : AppColors.ink2)),
                    ),
                  ),
                ),
              );
            }).toList(),
          ),
          const SizedBox(height: 14),
          OrbixField(
              label: 'Amount (${widget.currency})',
              controller: _amount,
              keyboardType:
                  const TextInputType.numberWithOptions(decimal: true),
              inputFormatters: [
                FilteringTextInputFormatter.allow(RegExp(r'[0-9.]'))
              ],
              big: true),
          const SizedBox(height: 12),
          OrbixField(label: 'Reason', controller: _reason),
        ],
      ),
      actions: [
        TextButton(
            onPressed: () => Navigator.pop(context),
            child: const Text('Cancel')),
        OrbixButton(label: 'Record', busy: _busy, onPressed: _submit),
      ],
    );
  }
}

// ============================================================ today's sales

class _TodaysSalesDialog extends ConsumerStatefulWidget {
  const _TodaysSalesDialog({required this.companyId});
  final String companyId;
  @override
  ConsumerState<_TodaysSalesDialog> createState() => _TodaysSalesDialogState();
}

class _TodaysSalesDialogState extends ConsumerState<_TodaysSalesDialog> {
  late Future<List<SalesInvoice>> _future;

  @override
  void initState() {
    super.initState();
    _future = ref.read(saleServiceProvider).listInvoices(widget.companyId);
  }

  Future<void> _reprint(SalesInvoice inv) async {
    try {
      final receipt = await ref.read(saleServiceProvider).loadReceipt(inv.uid,
          clientTxnId: inv.uid);
      if (mounted) {
        Navigator.pop(context);
        showReceiptSheet(context, ref, receipt);
      }
    } on ApiException catch (e) {
      if (mounted) showToast(context, e.message);
    }
  }

  @override
  Widget build(BuildContext context) {
    final df = DateFormat('HH:mm');
    return Dialog(
      shape: RoundedRectangleBorder(borderRadius: AppRadii.brLg),
      child: ConstrainedBox(
        constraints: const BoxConstraints(maxWidth: 520, maxHeight: 600),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            const Padding(
              padding: EdgeInsets.fromLTRB(20, 18, 20, 12),
              child: Align(
                alignment: Alignment.centerLeft,
                child: Text("Today's sales",
                    style:
                        TextStyle(fontSize: 18, fontWeight: FontWeight.w700)),
              ),
            ),
            const Divider(height: 1),
            Flexible(
              child: FutureBuilder<List<SalesInvoice>>(
                future: _future,
                builder: (context, snap) {
                  if (snap.connectionState == ConnectionState.waiting) {
                    return const Padding(
                        padding: EdgeInsets.all(40),
                        child: Center(child: CircularProgressIndicator()));
                  }
                  final list = snap.data ?? const [];
                  if (list.isEmpty) {
                    return const Padding(
                        padding: EdgeInsets.all(40),
                        child: Center(child: Text('No sales yet.')));
                  }
                  return ListView.separated(
                    shrinkWrap: true,
                    itemCount: list.length,
                    separatorBuilder: (_, _) => const Divider(height: 1),
                    itemBuilder: (context, i) {
                      final inv = list[i];
                      return ListTile(
                        title: Text(inv.invoiceNumber),
                        subtitle: Text(inv.finalisedAt == null
                            ? (inv.customerName ?? '')
                            : df.format(inv.finalisedAt!.toLocal())),
                        trailing: Text(
                            formatMoneyParts(
                                inv.grossTotalAmount, inv.currency),
                            style: numStyle(weight: FontWeight.w700)),
                        onTap: () => _reprint(inv),
                      );
                    },
                  );
                },
              ),
            ),
            Padding(
              padding: const EdgeInsets.all(12),
              child: Align(
                alignment: Alignment.centerRight,
                child: OrbixButton(
                    label: 'Close',
                    kind: BtnKind.ghost,
                    onPressed: () => Navigator.pop(context)),
              ),
            ),
          ],
        ),
      ),
    );
  }
}

// ============================================================ close

class _CloseDialog extends ConsumerStatefulWidget {
  const _CloseDialog({required this.sessionUid, required this.currency});
  final String sessionUid;
  final String currency;
  @override
  ConsumerState<_CloseDialog> createState() => _CloseDialogState();
}

class _CloseDialogState extends ConsumerState<_CloseDialog> {
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
      showToast(context, 'Enter the counted cash.');
      return;
    }
    setState(() => _busy = true);
    try {
      final s = await ref
          .read(sessionServiceProvider)
          .close(widget.sessionUid, counted);
      ref.read(appControllerProvider.notifier).updateShift(s);
      setState(() {
        _busy = false;
        _closed = s;
      });
    } on ApiException catch (e) {
      setState(() => _busy = false);
      if (mounted) showToast(context, e.message);
    }
  }

  @override
  Widget build(BuildContext context) {
    final s = _closed;
    return AlertDialog(
      shape: RoundedRectangleBorder(borderRadius: AppRadii.brLg),
      title: Text(s == null ? 'Close session' : 'Session closed'),
      content: s == null
          ? Column(
              mainAxisSize: MainAxisSize.min,
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                const Text('Count the drawer and enter the cash total.',
                    style: TextStyle(color: AppColors.ink2)),
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
          : _variance(s),
      actions: s == null
          ? [
              TextButton(
                  onPressed: () => Navigator.pop(context),
                  child: const Text('Cancel')),
              OrbixButton(label: 'Close session', busy: _busy, onPressed: _close),
            ]
          : [
              OrbixButton(
                  label: 'Done',
                  onPressed: () => Navigator.pop(context)),
            ],
    );
  }

  Widget _variance(PosSession s) {
    final variance = s.varianceAmount ?? 0;
    final over = variance >= 0;
    return Column(
      mainAxisSize: MainAxisSize.min,
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        _vrow('Expected', s.expectedCashAmount ?? 0),
        _vrow('Counted', s.countedCashAmount ?? 0),
        Container(
          margin: const EdgeInsets.only(top: 10),
          padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 12),
          decoration: BoxDecoration(
            color: variance == 0
                ? AppColors.okSoft
                : (over ? AppColors.okSoft : AppColors.dangerSoft),
            borderRadius: AppRadii.brSm,
            border: Border.all(
                color: variance == 0 || over
                    ? const Color(0xFFBBF7D0)
                    : const Color(0xFFFECACA)),
          ),
          child: Row(
            mainAxisAlignment: MainAxisAlignment.spaceBetween,
            children: [
              const Text('Variance',
                  style: TextStyle(fontWeight: FontWeight.w700)),
              Text(formatMoneyParts(variance, widget.currency),
                  style: numStyle(
                      size: 17,
                      weight: FontWeight.w800,
                      color: variance == 0 || over
                          ? AppColors.payD
                          : AppColors.danger)),
            ],
          ),
        ),
        const SizedBox(height: 6),
        const Text('Reconcile (Z-read) posts this variance — supervisor.',
            style: TextStyle(fontSize: 12, color: AppColors.ink3)),
      ],
    );
  }

  Widget _vrow(String label, double v) => Padding(
        padding: const EdgeInsets.symmetric(vertical: 4),
        child: Row(
          mainAxisAlignment: MainAxisAlignment.spaceBetween,
          children: [
            Text(label, style: const TextStyle(color: AppColors.ink2)),
            Text(formatMoneyParts(v, widget.currency),
                style: numStyle(weight: FontWeight.w700)),
          ],
        ),
      );
}

// ============================================================ reconcile

class _ReconcileDialog extends ConsumerStatefulWidget {
  const _ReconcileDialog({required this.sessionUid, required this.currency});
  final String sessionUid;
  final String currency;
  @override
  ConsumerState<_ReconcileDialog> createState() => _ReconcileDialogState();
}

class _ReconcileDialogState extends ConsumerState<_ReconcileDialog> {
  bool _busy = false;
  ZRead? _z;

  Future<void> _reconcile() async {
    setState(() => _busy = true);
    try {
      final z = await ref
          .read(sessionServiceProvider)
          .reconcile(widget.sessionUid);
      setState(() {
        _busy = false;
        _z = z;
      });
    } on ApiException catch (e) {
      setState(() => _busy = false);
      if (mounted) showToast(context, e.message);
    }
  }

  @override
  Widget build(BuildContext context) {
    final z = _z;
    return AlertDialog(
      shape: RoundedRectangleBorder(borderRadius: AppRadii.brLg),
      title: Text(z == null ? 'Reconcile session' : 'Z-read'),
      content: z == null
          ? const Text(
              'This finalises the session and posts the cash variance to the '
              'general ledger. This cannot be undone.')
          : Column(
              mainAxisSize: MainAxisSize.min,
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                _zrow('Opening float', z.openingFloatAmount),
                _zrow('Sales', z.totalSalesAmount),
                _zrow('Payouts', -z.totalPayoutsNetAmount),
                _zrow('Expected', z.expectedCashAmount),
                _zrow('Counted', z.countedCashAmount),
                const Divider(),
                _zrow('Variance', z.varianceAmount, danger: z.varianceAmount < 0),
                const SizedBox(height: 6),
                Text('${z.invoiceCount} invoices',
                    style: const TextStyle(color: AppColors.ink3)),
              ],
            ),
      actions: z == null
          ? [
              TextButton(
                  onPressed: () => Navigator.pop(context),
                  child: const Text('Cancel')),
              OrbixButton(
                  label: 'Reconcile',
                  busy: _busy,
                  onPressed: _reconcile),
            ]
          : [
              OrbixButton(
                  label: 'Finish shift',
                  onPressed: () {
                    Navigator.pop(context);
                    ref.read(appControllerProvider.notifier).endShift();
                  }),
            ],
    );
  }

  Widget _zrow(String label, double v, {bool danger = false}) => Padding(
        padding: const EdgeInsets.symmetric(vertical: 5),
        child: Row(
          mainAxisAlignment: MainAxisAlignment.spaceBetween,
          children: [
            Text(label, style: const TextStyle(color: AppColors.ink2)),
            Text(formatMoneyParts(v, widget.currency),
                style: numStyle(
                    weight: FontWeight.w700,
                    color: danger ? AppColors.danger : AppColors.ink)),
          ],
        ),
      );
}
