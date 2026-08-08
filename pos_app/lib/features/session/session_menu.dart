import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:intl/intl.dart';

import '../../app/theme.dart';
import '../../core/api/api_exception.dart';
import '../../core/config/app_config.dart';
import '../../core/config/step_up_policy.dart';
import '../../core/money.dart';
import '../../models/auth.dart';
import '../../models/context.dart';
import '../../models/enums.dart';
import '../../models/pos.dart';
import '../../models/sale.dart';
import '../../services/receipt_printer.dart';
import '../../state/app_controller.dart';
import '../../state/providers.dart';
import '../../state/receipt_journal.dart';
import '../../widgets/ui.dart';
import '../auth/approval_dialog.dart';
import '../receipt/receipt_view.dart';
import '../receipt/report_text.dart';

/// The session menu drawer (prototype ☰): X-read, payout, today's sales, close,
/// reconcile, Z-read.
///
/// ## Permission-denied actions are HIDDEN, not dimmed
///
/// They used to render at 40% opacity — dimmed but perfectly legible and
/// clickable-looking. The client's objection is exactly right: a greyed row is
/// an advertisement for something the operator cannot have, it invites the tap
/// that produces a 403, and on a till it reads as a fault rather than a policy.
/// A capability the operator does not hold is simply not part of their menu.
///
/// Actions that are permitted but temporarily unavailable (close a session that
/// is not open) DO stay visible and disabled, with the reason in the subtitle —
/// those are workflow states, and hiding them would make the menu jump around
/// as the shift progresses.
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
                        tooltip: 'Close the session menu',
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
                      _info(
                          'Opened',
                          s.openedAt == null
                              ? '—'
                              : df.format(s.openedAt!.toLocal())),
                      _info('Float',
                          formatMoneyParts(s.openingFloatAmount, app.currency)),
                    ],
                  ),
                ),
              const Divider(),
              Expanded(
                child: ListView(
                  padding: const EdgeInsets.symmetric(horizontal: 8),
                  children: _actions(context, ref, app, s),
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

  /// Builds the visible action list. A row only exists when the operator holds
  /// the permission behind it.
  List<Widget> _actions(
      BuildContext context, WidgetRef ref, AppData app, PosSession? s) {
    final out = <Widget>[];

    if (app.can(Perms.sessionView)) {
      out.add(_action(Icons.summarize_outlined, 'X-read',
          'Mid-shift drawer report — resets nothing',
          enabled: s != null, onTap: () => _xRead(context, ref)));
    }
    if (app.can(Perms.sessionOpen)) {
      out.add(_action(Icons.payments_outlined, 'Cash payout',
          'Refund or drawer drop — reason required',
          enabled: s != null && s.status.isOpen,
          disabledNote: 'Only while the session is open',
          onTap: () => _payout(context, ref)));
    }
    if (app.can(Perms.salesInvoiceView)) {
      out.add(_action(Icons.receipt_long_outlined, "Today's sales",
          'Look up & reprint a receipt',
          enabled: true, onTap: () => _todaysSales(context, ref)));
    }
    out.add(_action(Icons.history, 'Recent receipts',
        'Reprint from this device (offline)',
        enabled: true, onTap: () => _recent(context, ref)));

    out.add(const Divider());

    if (app.can(Perms.sessionClose)) {
      out.add(_action(Icons.lock_outline, 'Close session',
          'Count the drawer → variance',
          enabled: s != null && s.status.isOpen,
          disabledNote: 'The session is already closed',
          onTap: () => _close(context, ref)));
    }
    if (app.can(Perms.sessionReconcile)) {
      out.add(_action(Icons.fact_check_outlined, 'Reconcile (Z-read)',
          'Post the variance — this is final',
          enabled: s != null && s.status.isClosed,
          disabledNote: 'Close the session first',
          onTap: () => _reconcile(context, ref)));
    }
    // Reprinting the Z-read is a READ (P3 made it repeatable), so it needs only
    // POS.SESSION.VIEW — a cashier may hold that without POS.SESSION.RECONCILE.
    // The manager step-up on the print itself is what keeps the closing
    // statement a supervised document.
    if (app.can(Perms.sessionView)) {
      out.add(_action(Icons.print_outlined, 'Z-read (reprint)',
          'The final figures for a reconciled session',
          enabled: s != null && s.status.isReconciled,
          disabledNote: 'Available once the session is reconciled',
          onTap: () => _zReadReprint(context, ref)));
    }
    return out;
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
                style:
                    const TextStyle(fontSize: 14, fontWeight: FontWeight.w600)),
          ],
        ),
      );

  /// One menu row. [enabled] false means "not right now" (a workflow state) —
  /// never "not allowed", which is handled by not building the row at all.
  Widget _action(IconData icon, String title, String subtitle,
      {required bool enabled,
      String? disabledNote,
      required VoidCallback onTap}) {
    final note = enabled ? subtitle : (disabledNote ?? subtitle);
    return Semantics(
      button: true,
      enabled: enabled,
      label: '$title. $note',
      child: Opacity(
        opacity: enabled ? 1 : .5,
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
                      Text(note,
                          style: TextStyle(
                              fontSize: 12,
                              color:
                                  enabled ? AppColors.ink3 : AppColors.warn)),
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

  // ---------------------------------------------------------------- actions

  Future<void> _xRead(BuildContext context, WidgetRef ref) async {
    final app = ref.read(appControllerProvider);
    final uid = app.shift?.uid;
    if (uid == null) return;
    // Policy decision, not a hard-coded one: see kStepUpPolicy. X-read defaults
    // to no approval — it is a cashier's own drawer self-check.
    final outcome =
        await approveIfRequired(context, ref, action: GatedAction.xRead);
    if (!outcome.allowed || !context.mounted) return;
    try {
      final x = await ref.read(sessionServiceProvider).xRead(uid);
      if (!context.mounted) return;
      showDialog(
        context: context,
        builder: (_) => _XReadDialog(xRead: x),
      );
    } on ApiException catch (e) {
      if (context.mounted) showToast(context, e.message);
    }
  }

  Future<void> _zReadReprint(BuildContext context, WidgetRef ref) async {
    final app = ref.read(appControllerProvider);
    final uid = app.shift?.uid;
    if (uid == null) return;
    try {
      final z = await ref.read(sessionServiceProvider).zRead(uid);
      if (!context.mounted) return;
      showDialog(
        context: context,
        builder: (_) => _ZReadSheet(zRead: z, reprint: true),
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

  Future<void> _recent(BuildContext context, WidgetRef ref) async {
    final receipts = await ref.read(receiptJournalProvider).recent();
    if (!context.mounted) return;
    showDialog(
      context: context,
      builder: (_) => _RecentReceiptsDialog(receipts: receipts),
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

// ============================================================ printing

/// Gathers the till identity a drawer report prints. Kept in one place so the
/// X-read, the Z-read and a Z-read reprint cannot disagree about the header.
ReportContext _reportContext(AppData app) {
  final Company? company = app.context?.company;
  return ReportContext(
    companyName: company?.name ?? 'OrbixPOS',
    branchName: app.context?.branch.name ?? '',
    cashierName: app.me?.displayName ?? '',
    sessionNumber: app.shift?.sessionNumber ?? '',
    currency: app.currency,
    companyDetailLines:
        company == null ? const [] : companyReceiptLines(company),
  );
}

/// Sends an already-rendered report to the configured printer.
///
/// Returns true on success. Never kicks the cash drawer: a report is read, not
/// paid out, and popping the till on a report turns a cash-control document
/// into a cash-control problem.
Future<bool> _printReport(BuildContext context, String text) async {
  final cfg = await AppConfig.load();
  final printer = cfg.receiptPrinterName;
  if (printer == null || printer.isEmpty) {
    if (context.mounted) {
      showToast(context, 'No receipt printer set — configure one in Setup.');
    }
    return false;
  }
  try {
    await const ReceiptPrinter()
        .printRaw(printer, encodeReportBytes(text, mode: cfg.printMode));
    if (context.mounted) showToast(context, 'Printed.', ok: true);
    return true;
  } on ReceiptPrinterException catch (e) {
    if (context.mounted) showToast(context, e.message);
  } catch (_) {
    if (context.mounted) showToast(context, 'Could not print the report.');
  }
  return false;
}

// ============================================================ X-read

class _XReadDialog extends ConsumerStatefulWidget {
  const _XReadDialog({required this.xRead});
  final XRead xRead;
  @override
  ConsumerState<_XReadDialog> createState() => _XReadDialogState();
}

class _XReadDialogState extends ConsumerState<_XReadDialog> {
  bool _printing = false;

  Future<void> _print() async {
    final app = ref.read(appControllerProvider);
    final cfg = await AppConfig.load();
    if (!mounted) return;
    setState(() => _printing = true);
    final text = buildXReadText(
      x: widget.xRead,
      ctx: _reportContext(app),
      width: cfg.receiptWidthCols,
    );
    await _printReport(context, text);
    if (mounted) setState(() => _printing = false);
  }

  @override
  Widget build(BuildContext context) {
    final app = ref.watch(appControllerProvider);
    final x = widget.xRead;
    return AlertDialog(
      shape: RoundedRectangleBorder(borderRadius: AppRadii.brLg),
      title: const Text('X-read'),
      content: ConstrainedBox(
        constraints: const BoxConstraints(maxWidth: 420),
        child: SingleChildScrollView(
          child: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              _ReportRow('Sales (all tenders)', x.totalSalesAmount,
                  currency: app.currency),
              if (x.tenderSubtotals.isNotEmpty)
                _TenderBreakdown(subtotals: x.tenderSubtotals),
              const Divider(),
              _ReportRow('Opening float', x.openingFloatAmount,
                  currency: app.currency),
              _ReportRow('Cash sales', x.cashTenderAmount,
                  currency: app.currency),
              _ReportRow('Payouts', -x.totalPayoutsNetAmount,
                  currency: app.currency),
              _PayoutBreakdown(subtotals: x.payoutSubtotals),
              const Divider(),
              _ReportRow('Expected cash', x.expectedCashAmount,
                  currency: app.currency, bold: true),
              const Divider(),
              Text('${x.invoiceCount} invoices',
                  style: const TextStyle(color: AppColors.ink3)),
              const SizedBox(height: 4),
              const Text('An X-read does not close the shift and resets nothing.',
                  style: TextStyle(fontSize: 11.5, color: AppColors.ink3)),
            ],
          ),
        ),
      ),
      actions: [
        // Absent on a build that has no raw spooler at all (web / Android) —
        // a control that can only ever fail is not worth the tap.
        if (const ReceiptPrinter().supported)
          OrbixButton(
              label: 'Print',
              icon: Icons.print_outlined,
              kind: BtnKind.ghost,
              busy: _printing,
              onPressed: _printing ? null : _print),
        OrbixButton(label: 'Close', onPressed: () => Navigator.pop(context)),
      ],
    );
  }
}

// ============================================================ Z-read

/// The Z-read view, shared by the reconcile result and a later reprint.
///
/// Printing it takes a manager (see [kStepUpPolicy]): it is the shift's closing
/// statement and the document a variance gets argued from. A reprint is stamped
/// as one — the figures are byte-identical to the original by design, which is
/// precisely why the paper has to say which copy it is.
class _ZReadSheet extends ConsumerStatefulWidget {
  const _ZReadSheet({required this.zRead, this.reprint = false, this.onDone});
  final ZRead zRead;
  final bool reprint;
  final VoidCallback? onDone;
  @override
  ConsumerState<_ZReadSheet> createState() => _ZReadSheetState();
}

class _ZReadSheetState extends ConsumerState<_ZReadSheet> {
  bool _printing = false;

  Future<void> _print() async {
    final outcome = await approveIfRequired(
      context,
      ref,
      action: GatedAction.zReadPrint,
      detail: 'Session ${ref.read(appControllerProvider).shift?.sessionNumber ?? ''}',
      correlationId: widget.zRead.sessionUid,
    );
    if (!outcome.allowed || !mounted) return;

    final app = ref.read(appControllerProvider);
    final cfg = await AppConfig.load();
    if (!mounted) return;
    setState(() => _printing = true);
    final text = buildZReadText(
      z: widget.zRead,
      ctx: _reportContext(app),
      width: cfg.receiptWidthCols,
      reprint: widget.reprint,
    );
    final ok = await _printReport(context, text);
    if (!mounted) return;
    setState(() => _printing = false);
    final by = outcome.approverLabel;
    if (ok && by != null && context.mounted) {
      showToast(context, 'Approved by $by.', ok: true);
    }
  }

  @override
  Widget build(BuildContext context) {
    final app = ref.watch(appControllerProvider);
    final z = widget.zRead;
    return AlertDialog(
      shape: RoundedRectangleBorder(borderRadius: AppRadii.brLg),
      title: Text(widget.reprint ? 'Z-read (reprint)' : 'Z-read'),
      content: ConstrainedBox(
        constraints: const BoxConstraints(maxWidth: 440),
        child: SingleChildScrollView(
          child: _zBody(z, app.currency),
        ),
      ),
      actions: [
        if (const ReceiptPrinter().supported)
          OrbixButton(
              label: 'Print',
              icon: Icons.print_outlined,
              kind: BtnKind.ghost,
              busy: _printing,
              onPressed: _printing ? null : _print),
        OrbixButton(
            label: widget.onDone == null ? 'Close' : 'Finish shift',
            onPressed: () {
              Navigator.pop(context);
              widget.onDone?.call();
            }),
      ],
    );
  }

  Widget _zBody(ZRead z, String currency) => Column(
        mainAxisSize: MainAxisSize.min,
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          _ReportRow('Sales (all tenders)', z.totalSalesAmount,
              currency: currency),
          if (z.tenderSubtotals.isNotEmpty)
            _TenderBreakdown(subtotals: z.tenderSubtotals),
          const Divider(),
          _ReportRow('Opening float', z.openingFloatAmount, currency: currency),
          _ReportRow('Cash sales', z.cashTenderAmount, currency: currency),
          _ReportRow('Payouts', -z.totalPayoutsNetAmount, currency: currency),
          _PayoutBreakdown(subtotals: z.payoutSubtotals),
          const Divider(),
          _ReportRow('Expected', z.expectedCashAmount, currency: currency),
          _ReportRow('Counted', z.countedCashAmount, currency: currency),
          _ReportRow('Variance', z.varianceAmount,
              currency: currency, bold: true, danger: z.varianceAmount < 0),
          const SizedBox(height: 6),
          Text('${z.invoiceCount} invoices',
              style: const TextStyle(color: AppColors.ink3)),
          if (widget.reprint) ...[
            const SizedBox(height: 6),
            const Text(
                'This is a reprint. The figures are identical to the original — '
                'the Z-read is read-only and posts nothing.',
                style: TextStyle(fontSize: 11.5, color: AppColors.ink3)),
          ],
        ],
      );
}

// ============================================================ report parts

class _ReportRow extends StatelessWidget {
  const _ReportRow(this.label, this.value,
      {required this.currency, this.bold = false, this.danger = false});
  final String label;
  final double value;
  final String currency;
  final bool bold;
  final bool danger;

  @override
  Widget build(BuildContext context) => Padding(
        padding: const EdgeInsets.symmetric(vertical: 5),
        child: Row(
          mainAxisAlignment: MainAxisAlignment.spaceBetween,
          children: [
            Text(label,
                style: TextStyle(
                    color: AppColors.ink2,
                    fontWeight: bold ? FontWeight.w800 : FontWeight.w400)),
            const SizedBox(width: 8),
            Flexible(
              child: NumText(formatMoneyParts(value, currency),
                  style: numStyle(
                      weight: bold ? FontWeight.w800 : FontWeight.w700,
                      color: danger ? AppColors.danger : AppColors.ink)),
            ),
          ],
        ),
      );
}

/// Per-tender turnover breakdown (e.g. `Cash 32,020.00 · Mobile 14,000.00`),
/// shown so the cashier sees where non-cash takings went and why gross sales
/// legitimately exceed the cash retained in the drawer.
class _TenderBreakdown extends StatelessWidget {
  const _TenderBreakdown({required this.subtotals});
  final List<TenderSubtotal> subtotals;

  @override
  Widget build(BuildContext context) {
    final text = subtotals
        .map((s) => '${s.tenderType.label} ${formatAmount(s.amount)}')
        .join('   ·   ');
    return Padding(
      padding: const EdgeInsets.only(top: 4, left: 12),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          const Text('By tender',
              style: TextStyle(fontSize: 11, color: AppColors.ink3)),
          const SizedBox(height: 2),
          Text(text, style: numStyle(size: 12.5, weight: FontWeight.w600)),
        ],
      ),
    );
  }
}

/// Payouts split by type (P3). The subtotals always sum to the payouts line
/// above them, so these rows decompose it — they are never an extra deduction.
class _PayoutBreakdown extends StatelessWidget {
  const _PayoutBreakdown({required this.subtotals});
  final List<PayoutSubtotal> subtotals;

  @override
  Widget build(BuildContext context) {
    if (subtotals.isEmpty) return const SizedBox.shrink();
    return Padding(
      padding: const EdgeInsets.only(top: 2, left: 12),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          for (final s in subtotals)
            Padding(
              padding: const EdgeInsets.symmetric(vertical: 2),
              child: Row(
                mainAxisAlignment: MainAxisAlignment.spaceBetween,
                children: [
                  Text(
                      s.count == 0
                          ? s.payoutType.label
                          : '${s.payoutType.label} (${s.count})',
                      style: const TextStyle(
                          fontSize: 12, color: AppColors.ink3)),
                  const SizedBox(width: 8),
                  Flexible(
                    child: NumText('-${formatAmount(s.amount)}',
                        style: numStyle(size: 12.5)),
                  ),
                ],
              ),
            ),
        ],
      ),
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
    // Mirrors the server rule (K8: @NotBlank @Size(min=3)) so the cashier is
    // told what to fix here, rather than after a round trip.
    final reason = _reason.text.trim();
    if (reason.length < 3) {
      showToast(context, 'Say what the cash is for (at least a few words).');
      return;
    }
    setState(() => _busy = true);
    try {
      final payout = await ref
          .read(sessionServiceProvider)
          .payout(widget.sessionUid, _type, amt, reason);
      if (mounted) {
        Navigator.pop(context);
        showToast(
            context,
            payout.journalEntryUid == null
                ? 'Payout recorded.'
                : 'Payout recorded and posted to the ledger.',
            ok: true);
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
          OrbixField(label: 'Reason (required)', controller: _reason),
          const SizedBox(height: 6),
          const Text(
              'A paid-out is booked to the ledger as an expense against the '
              'drawer, so the reason is what the entry is filed under.',
              style: TextStyle(fontSize: 11.5, color: AppColors.ink3)),
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
      final receipt = await ref
          .read(saleServiceProvider)
          .loadReceipt(inv.uid, clientTxnId: inv.uid);
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
                  if (snap.hasError) {
                    final e = snap.error;
                    return Padding(
                        padding: const EdgeInsets.all(40),
                        child: Center(
                            child: Text(e is ApiException
                                ? e.message
                                : 'Could not load sales.')));
                  }
                  // Only finalised sales — never reprint a void/draft as a clean
                  // receipt from this list.
                  final list = (snap.data ?? const <SalesInvoice>[])
                      .where((i) => i.status.isFinalised)
                      .toList();
                  if (list.isEmpty) {
                    return const Padding(
                        padding: EdgeInsets.all(40),
                        child: Center(child: Text('No finalised sales yet.')));
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

// ============================================================ recent receipts

class _RecentReceiptsDialog extends ConsumerWidget {
  const _RecentReceiptsDialog({required this.receipts});
  final List<Receipt> receipts;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final df = DateFormat('MMM d, HH:mm');
    return Dialog(
      shape: RoundedRectangleBorder(borderRadius: AppRadii.brLg),
      child: ConstrainedBox(
        constraints: const BoxConstraints(maxWidth: 480, maxHeight: 560),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            const Padding(
              padding: EdgeInsets.fromLTRB(20, 18, 20, 10),
              child: Align(
                alignment: Alignment.centerLeft,
                child: Text('Recent receipts (this device)',
                    style:
                        TextStyle(fontSize: 18, fontWeight: FontWeight.w700)),
              ),
            ),
            const Divider(height: 1),
            Flexible(
              child: receipts.isEmpty
                  ? const Padding(
                      padding: EdgeInsets.all(40),
                      child:
                          Center(child: Text('No receipts on this device yet.')))
                  : ListView.separated(
                      shrinkWrap: true,
                      itemCount: receipts.length,
                      separatorBuilder: (_, _) => const Divider(height: 1),
                      itemBuilder: (context, i) {
                        final r = receipts[i];
                        return ListTile(
                          leading: const Icon(Icons.receipt_outlined,
                              color: AppColors.brand),
                          title: Text(r.invoice.invoiceNumber),
                          subtitle: Text(r.invoice.finalisedAt == null
                              ? (r.invoice.customerName ?? '')
                              : df.format(r.invoice.finalisedAt!.toLocal())),
                          trailing: Text(
                              formatMoneyParts(r.invoice.grossTotalAmount,
                                  r.invoice.currency),
                              style: numStyle(weight: FontWeight.w700)),
                          onTap: () {
                            Navigator.pop(context);
                            showReceiptSheet(context, ref, r);
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
              OrbixButton(
                  label: 'Close session', busy: _busy, onPressed: _close),
            ]
          : [
              OrbixButton(
                  label: 'Done', onPressed: () => Navigator.pop(context)),
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
        const Padding(
          padding: EdgeInsets.only(top: 2, bottom: 2),
          child: Text(
              'Expected = float + cash sales − payouts (cash tenders only; '
              'card & mobile money settle separately).',
              style: TextStyle(fontSize: 11, color: AppColors.ink3)),
        ),
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
      final z =
          await ref.read(sessionServiceProvider).reconcile(widget.sessionUid);
      // Keep the shift in state so the Z-read stays reachable for a reprint
      // until the cashier explicitly finishes.
      await ref.read(appControllerProvider.notifier).refreshShift();
      if (!mounted) return;
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
    if (z != null) {
      // Same view, same renderer, same print path as a later reprint — so the
      // reprint cannot drift from what was shown here.
      return _ZReadSheet(
        zRead: z,
        onDone: () => ref.read(appControllerProvider.notifier).endShift(),
      );
    }
    return AlertDialog(
      shape: RoundedRectangleBorder(borderRadius: AppRadii.brLg),
      title: const Text('Reconcile session'),
      content: const Text(
          'This finalises the session and posts the cash variance to the '
          'general ledger. This cannot be undone — but the Z-read can be '
          'reprinted afterwards, so nothing is lost if the paper jams.'),
      actions: [
        TextButton(
            onPressed: () => Navigator.pop(context),
            child: const Text('Cancel')),
        OrbixButton(label: 'Reconcile', busy: _busy, onPressed: _reconcile),
      ],
    );
  }
}
