import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../app/theme.dart';
import '../../core/api/api_exception.dart';
import '../../core/config/app_config.dart';
import '../../core/config/step_up_policy.dart';
import '../../core/money.dart';
import '../../models/auth.dart';
import '../../models/context.dart';
import '../../models/sale.dart';
import '../../services/receipt_printer.dart';
import '../../state/app_controller.dart';
import '../../state/providers.dart';
import '../../state/receipt_journal.dart';
import '../../widgets/ui.dart';
import '../auth/approval_dialog.dart';
import 'receipt_text.dart';

/// Shows a printed-style receipt built from the finalised invoice (the receipt
/// of record, AS-8). Reprint/gift never re-post (G-8); reverse is gated.
///
/// [reprint] is true when the receipt is opened again from Today's sales or
/// Recent receipts rather than straight after the sale. A reprint never opens
/// the cash drawer: the drawer opens to take the money for a sale, and a till
/// that pops it on every reprint is an open drawer on request.
Future<void> showReceiptSheet(
    BuildContext context, WidgetRef ref, Receipt receipt,
    {bool reprint = false}) {
  return showDialog(
    context: context,
    builder: (_) => _ReceiptDialog(receipt: receipt, reprint: reprint),
  );
}

/// Whether printing this receipt should open the cash drawer: only the first
/// print of a sale's own receipt, when the till is set to open it. Never a
/// reprint, a gift receipt, a reversed sale, or a second copy — each of those
/// would be an open drawer with no money going in.
bool shouldKickDrawer({
  required bool configured,
  required bool reprint,
  required bool gift,
  required bool reversed,
  required bool alreadyOpened,
}) =>
    configured && !reprint && !gift && !reversed && !alreadyOpened;

/// Builds the fiscal-header detail lines (address, Tel, Email, TIN, VRN) for
/// [company], in printed-receipt order. Omits any field that is null/empty so
/// the header stays clean for companies that haven't backfilled these yet.
List<String> companyReceiptLines(Company company) {
  final lines = <String>[];
  void addIfPresent(String? value, [String prefix = '']) {
    final v = value?.trim() ?? '';
    if (v.isEmpty) return;
    lines.add('$prefix$v');
  }

  addIfPresent(company.addressLine1);
  addIfPresent(company.addressLine2);
  final cityRegion = [company.city, company.region]
      .where((s) => (s ?? '').trim().isNotEmpty)
      .join(', ');
  addIfPresent(cityRegion.isEmpty ? null : cityRegion);
  addIfPresent(company.country);
  addIfPresent(company.contactPhone, 'Tel: ');
  addIfPresent(company.contactEmail, 'Email: ');
  addIfPresent(company.taxId, 'TIN: ');
  addIfPresent(company.vrn, 'VRN: ');
  return lines;
}

class _ReceiptDialog extends ConsumerStatefulWidget {
  const _ReceiptDialog({required this.receipt, this.reprint = false});
  final Receipt receipt;
  final bool reprint;
  @override
  ConsumerState<_ReceiptDialog> createState() => _ReceiptDialogState();
}

class _ReceiptDialogState extends ConsumerState<_ReceiptDialog> {
  bool _gift = false;
  bool _reversed = false;
  bool _printing = false;

  /// The drawer opens at most once per sale, on the first successful print of
  /// the original receipt. Printing a second copy does not open it again.
  bool _drawerOpened = false;

  /// Who approved the reversal, for the on-screen stamp. Display only — the
  /// authoritative record is the server's audit row.
  String? _reversedBy;

  Receipt get r => widget.receipt;

  /// The CASHIER line: whoever rang the sale. Straight after a sale that is the
  /// signed-in user; on a reprint it is only ever the name the server recorded,
  /// and is left off rather than guessed when an old receipt does not carry it —
  /// printing the reprinting user's name would put the wrong person on the slip.
  String _cashierName(AppData app) {
    final rang = r.invoice.createdByName?.trim() ?? '';
    if (rang.isNotEmpty) return rang;
    return widget.reprint ? '' : (app.me?.displayName ?? '');
  }

  @override
  Widget build(BuildContext context) {
    final app = ref.watch(appControllerProvider);
    final supervisor =
        app.can(stepUpRuleFor(GatedAction.saleReverse).permissionCode);
    // A cashier may reverse only sales rung on their own open shift; the server
    // refuses anything else, so offering the button on a colleague's sale only
    // led to a manager approving a refund that was then refused. Unknown
    // session (an older server) leaves the decision to the server, as before.
    final sessionId = r.invoice.posSessionId;
    final ownShift = sessionId == null || sessionId == app.shift?.id;
    // Mirror of the endpoint's gate: POS.SALE.VOID (a cashier, who then needs a
    // manager) OR SALES.INVOICE.VOID (a supervisor, who does not). Showing only
    // the first would hide the button from exactly the person the refund policy
    // sends the cashier to find.
    final canReverse = (app.can(Perms.saleVoid) || supervisor) &&
        (supervisor || ownShift) &&
        (app.shift?.status.isOpen ?? false) &&
        !_reversed &&
        !r.invoice.status.isVoid;
    final voided = _reversed || r.invoice.status.isVoid;
    return Dialog(
      shape: RoundedRectangleBorder(borderRadius: AppRadii.brLg),
      child: ConstrainedBox(
        constraints: const BoxConstraints(maxWidth: 400, maxHeight: 700),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            Padding(
              padding: const EdgeInsets.fromLTRB(18, 16, 8, 16),
              child: Row(
                children: [
                  Icon(voided ? Icons.cancel : Icons.check_circle,
                      color: voided ? AppColors.danger : AppColors.pay),
                  const SizedBox(width: 10),
                  Expanded(
                    child: Text(voided ? 'Sale reversed' : 'Sale complete',
                        style: const TextStyle(
                            fontSize: 18, fontWeight: FontWeight.w700)),
                  ),
                  IconButton(
                      onPressed: () => Navigator.pop(context),
                      icon: const Icon(Icons.close)),
                ],
              ),
            ),
            const Divider(height: 1),
            Flexible(
              child: SingleChildScrollView(
                padding: const EdgeInsets.all(8),
                child: _receiptBody(app),
              ),
            ),
            const Divider(height: 1),
            Padding(
              padding: const EdgeInsets.all(12),
              child: Column(
                children: [
                  Row(
                    children: [
                      Expanded(
                        child: OrbixButton(
                            label: 'Print',
                            icon: Icons.print_outlined,
                            kind: BtnKind.ghost,
                            busy: _printing,
                            onPressed: _print),
                      ),
                      const SizedBox(width: 8),
                      Expanded(
                        child: OrbixButton(
                            label: _gift ? 'Show prices' : 'Gift receipt',
                            icon: Icons.card_giftcard_outlined,
                            kind: BtnKind.ghost,
                            onPressed: () => setState(() => _gift = !_gift)),
                      ),
                    ],
                  ),
                  const SizedBox(height: 8),
                  Row(
                    children: [
                      if (canReverse)
                        Expanded(
                          child: OrbixButton(
                              label: 'Refund / reverse',
                              icon: Icons.undo,
                              kind: BtnKind.danger,
                              onPressed: _reverse),
                        ),
                      if (canReverse) const SizedBox(width: 8),
                      Expanded(
                        child: OrbixButton(
                            label: 'Done',
                            onPressed: () => Navigator.pop(context)),
                      ),
                    ],
                  ),
                ],
              ),
            ),
          ],
        ),
      ),
    );
  }

  /// The on-screen receipt is the printed text itself, rendered monospaced at
  /// the 80 mm width, so the screen and the paper can never disagree.
  Widget _receiptBody(AppData app) {
    final ctx = app.context;
    final text = buildReceiptText(
      receipt: r,
      companyName: ctx?.company.name ?? 'OrbixPOS',
      branchName: ctx?.branch.name ?? '',
      cashierName: _cashierName(app),
      width: kCols80mm,
      gift: _gift,
      reversed: _reversed || r.invoice.status.isVoid,
      reversedBy: _reversedBy,
      companyDetailLines:
          ctx == null ? const [] : companyReceiptLines(ctx.company),
    );
    return Container(
      color: Colors.white,
      padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 14),
      child: FittedBox(
        fit: BoxFit.scaleDown,
        alignment: Alignment.topCenter,
        child: Text(text,
            style: const TextStyle(
                fontFamily: 'Consolas',
                fontSize: 12.5,
                height: 1.3,
                color: Color(0xFF1E293B))),
      ),
    );
  }

  /// Builds the receipt bytes (respecting configured width/mode/gift flag) and
  /// sends them to the configured Windows printer. Falls back to a nudge toward
  /// Setup when no printer is configured; toasts any spooler error friendly.
  Future<void> _print() async {
    final cfg = await AppConfig.load();
    final printer = cfg.receiptPrinterName;
    if (printer == null || printer.isEmpty) {
      if (mounted) {
        showToast(context, 'No receipt printer set — configure one in Setup.');
      }
      return;
    }
    final app = ref.read(appControllerProvider);
    final company = app.context?.company;
    final reversed = _reversed || r.invoice.status.isVoid;
    final kick = shouldKickDrawer(
        configured: cfg.kickDrawer,
        reprint: widget.reprint,
        gift: _gift,
        reversed: reversed,
        alreadyOpened: _drawerOpened);
    final bytes = buildReceiptBytes(
      receipt: r,
      companyName: company?.name ?? 'OrbixPOS',
      branchName: app.context?.branch.name ?? '',
      cashierName: _cashierName(app),
      width: cfg.receiptWidthCols,
      mode: cfg.printMode,
      gift: _gift,
      reversed: reversed,
      reversedBy: _reversedBy,
      kickDrawer: kick,
      companyDetailLines: company == null ? const [] : companyReceiptLines(company),
    );
    setState(() => _printing = true);
    try {
      await const ReceiptPrinter().printRaw(printer, bytes);
      if (kick) _drawerOpened = true;
      if (mounted) showToast(context, 'Printed.', ok: true);
    } on ReceiptPrinterException catch (e) {
      if (mounted) showToast(context, e.message);
    } catch (_) {
      if (mounted) showToast(context, 'Could not print the receipt.');
    } finally {
      if (mounted) setState(() => _printing = false);
    }
  }

  /// Reverses the sale: reason, then a **manager step-up**, then the call.
  ///
  /// A refund is money out of the drawer against a receipt that already exists,
  /// so it is the textbook shrinkage route and cannot rest on the cashier's own
  /// authority. The step-up asks for `SALES.INVOICE.VOID` specifically because
  /// the CASHIER bundle does NOT hold it — gating on `POS.SALE.VOID`, which
  /// cashiers do hold, would have let a cashier approve their own refund by
  /// retyping their own password, which is a control in appearance only.
  ///
  /// The verification issues no token: the till stays signed in as the cashier
  /// throughout, so the manager walks away and the queue keeps moving.
  ///
  /// **The approver's uid is sent with the reversal.** Without that, everything
  /// above was theatre: the password prompt lived entirely in this app, so curl,
  /// a script or a second client reached the same endpoint with no manager
  /// anywhere near it. The server re-resolves the uid and refuses the refund
  /// unless it names a real, active, DIFFERENT user who genuinely holds
  /// `SALES.INVOICE.VOID` in this invoice's company — so a fabricated uid buys
  /// nothing, and the name lands in the audit trail either way.
  ///
  /// A supervisor who is signed in at the till themselves is not asked to find a
  /// second supervisor: they already are the authority, and the server accepts
  /// the reversal with no `authorisedByUid` at all.
  Future<void> _reverse() async {
    final reasonCtrl = TextEditingController();
    final ok = await showDialog<bool>(
      context: context,
      builder: (_) => AlertDialog(
        shape: RoundedRectangleBorder(borderRadius: AppRadii.brLg),
        title: const Text('Reverse this sale?'),
        content: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            const Text(
                'This voids the whole sale and reverses revenue, VAT, cash and '
                'stock. Allowed while the session is open, and it needs a '
                'manager.'),
            const SizedBox(height: 12),
            OrbixField(
                label: 'Reason', controller: reasonCtrl, autofocus: true),
          ],
        ),
        actions: [
          TextButton(
              onPressed: () => Navigator.pop(context, false),
              child: const Text('Cancel')),
          OrbixButton(
              label: 'Continue',
              kind: BtnKind.danger,
              onPressed: () => Navigator.pop(context, true)),
        ],
      ),
    );
    if (ok != true) {
      reasonCtrl.dispose();
      return;
    }
    final reason = reasonCtrl.text.trim().isEmpty
        ? 'POS reversal'
        : reasonCtrl.text.trim();
    reasonCtrl.dispose();
    if (!mounted) return;

    // A supervisor signed in at the till IS the authority the step-up looks for.
    // Prompting them for a second manager's password would deadlock a one-manager
    // shop — and, since nobody may approve themselves, retyping their own
    // password now correctly fails. Skip straight to the reversal; the server
    // reaches the same conclusion independently.
    final selfAuthorised = ref
        .read(appControllerProvider)
        .can(stepUpRuleFor(GatedAction.saleReverse).permissionCode);

    String? approvedByUid;
    String? approverLabel;
    if (!selfAuthorised) {
      final outcome = await approveIfRequired(
        context,
        ref,
        action: GatedAction.saleReverse,
        detail: 'Receipt ${r.invoice.invoiceNumber} — '
            '${formatMoneyParts(r.invoice.grossTotalAmount, r.invoice.currency)}',
        correlationId: r.invoice.uid,
      );
      if (!outcome.allowed) {
        if (mounted) showToast(context, 'Not approved — the sale stands.');
        return;
      }
      approvedByUid = outcome.approval?.authoriserUid;
      approverLabel = outcome.approverLabel;
    }
    if (!mounted) return;

    try {
      await ref.read(saleServiceProvider).reverse(r.invoice.uid, reason,
          authorisedByUid: approvedByUid);
      // Reconcile the local journal so an offline reprint reflects the reversal.
      await ref.read(receiptJournalProvider).markReversed(r.invoice.uid);
      final by = approverLabel;
      setState(() {
        _reversed = true;
        _reversedBy = by;
      });
      if (mounted) {
        showToast(context,
            by == null ? 'Sale reversed.' : 'Sale reversed — approved by $by.',
            ok: true);
      }
    } on ApiException catch (e) {
      if (mounted) showToast(context, e.message);
    }
  }
}
