import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:intl/intl.dart';

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
Future<void> showReceiptSheet(
    BuildContext context, WidgetRef ref, Receipt receipt) {
  return showDialog(
    context: context,
    builder: (_) => _ReceiptDialog(receipt: receipt),
  );
}

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
  const _ReceiptDialog({required this.receipt});
  final Receipt receipt;
  @override
  ConsumerState<_ReceiptDialog> createState() => _ReceiptDialogState();
}

class _ReceiptDialogState extends ConsumerState<_ReceiptDialog> {
  bool _gift = false;
  bool _reversed = false;
  bool _printing = false;

  /// Who approved the reversal, for the on-screen stamp. Display only — the
  /// authoritative record is the server's audit row.
  String? _reversedBy;

  Receipt get r => widget.receipt;

  @override
  Widget build(BuildContext context) {
    final app = ref.watch(appControllerProvider);
    // Mirror of the endpoint's gate: POS.SALE.VOID (a cashier, who then needs a
    // manager) OR SALES.INVOICE.VOID (a supervisor, who does not). Showing only
    // the first would hide the button from exactly the person the refund policy
    // sends the cashier to find.
    final canReverse = (app.can(Perms.saleVoid) ||
            app.can(stepUpRuleFor(GatedAction.saleReverse).permissionCode)) &&
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

  Widget _receiptBody(AppData app) {
    final inv = r.invoice;
    final ctx = app.context;
    final df = DateFormat('yyyy-MM-dd HH:mm');
    const mono = TextStyle(
        fontFamily: 'Consolas', fontSize: 12.5, color: Color(0xFF1E293B));
    Widget line(String l, String right, {bool bold = false}) => Padding(
          padding: const EdgeInsets.symmetric(vertical: 1),
          child: Row(
            mainAxisAlignment: MainAxisAlignment.spaceBetween,
            children: [
              Expanded(
                  child: Text(l,
                      style: mono.copyWith(
                          fontWeight:
                              bold ? FontWeight.w800 : FontWeight.w400))),
              Text(right,
                  style: mono.copyWith(
                      fontWeight: bold ? FontWeight.w800 : FontWeight.w400,
                      fontFeatures: kTabular)),
            ],
          ),
        );
    Widget dashes() => const Padding(
          padding: EdgeInsets.symmetric(vertical: 6),
          child: Text('--------------------------------',
              style:
                  TextStyle(color: Color(0xFF94A3B8), fontFamily: 'Consolas')),
        );

    return Container(
      color: Colors.white,
      padding: const EdgeInsets.symmetric(horizontal: 18, vertical: 14),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          Center(
            child: Text(ctx?.company.name ?? 'OrbixPOS',
                style: mono.copyWith(fontSize: 15, fontWeight: FontWeight.w800)),
          ),
          if (ctx != null)
            for (final detail in companyReceiptLines(ctx.company))
              Center(
                  child: Text(detail,
                      style: mono.copyWith(color: const Color(0xFF64748B)))),
          Center(child: Text(ctx?.branch.name ?? '', style: mono)),
          const SizedBox(height: 8),
          line('Invoice', inv.invoiceNumber, bold: true),
          line('Date',
              df.format((inv.finalisedAt ?? DateTime.now()).toLocal())),
          line('Cashier', app.me?.displayName ?? ''),
          line('Customer', inv.customerName ?? r.invoice.customerId),
          dashes(),
          if (r.lines.isEmpty)
            Center(
                child: Text('(line detail not loaded)',
                    style: mono.copyWith(color: const Color(0xFF94A3B8))))
          else
            ...r.lines.map((l) => _receiptLine(l, mono)),
          dashes(),
          if (!_gift) ...[
            line('Net', formatAmount(inv.netTotalAmount)),
            line('VAT', formatAmount(inv.vatTotalAmount)),
            line('TOTAL ${inv.currency}', formatAmount(inv.grossTotalAmount),
                bold: true),
            dashes(),
            ...r.payments.map((p) =>
                line(p.tenderType.label, formatAmount(p.amount))),
            if (r.changeDue > 0) line('Change', formatAmount(r.changeDue)),
          ] else
            Center(
                child: Text('* gift receipt — prices hidden *',
                    style: mono.copyWith(color: const Color(0xFF64748B)))),
          const SizedBox(height: 10),
          Center(child: Text('Thank you!', style: mono)),
          if (_reversed || r.invoice.status.isVoid)
            Center(
                child: Text('\n*** REVERSED ***',
                    style: mono.copyWith(
                        color: AppColors.danger, fontWeight: FontWeight.w800))),
          if (_reversedBy != null)
            Center(
                child: Text('Approved by $_reversedBy',
                    style: mono.copyWith(color: const Color(0xFF64748B)))),
        ],
      ),
    );
  }

  Widget _receiptLine(InvoiceLine l, TextStyle mono) {
    final qty = formatAmount(l.quantity, decimals: l.quantity % 1 == 0 ? 0 : 3);
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 2),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          Text(l.productName, style: mono),
          Row(
            mainAxisAlignment: MainAxisAlignment.spaceBetween,
            children: [
              Text('  $qty x ${formatAmount(l.unitPriceAmount)}',
                  style: mono.copyWith(color: const Color(0xFF64748B))),
              Text(formatAmount(l.grossAmount),
                  style: mono.copyWith(fontFeatures: kTabular)),
            ],
          ),
          if (l.lineDiscountAmount > 0)
            Text('  less disc ${formatAmount(l.lineDiscountAmount)}',
                style: mono.copyWith(color: AppColors.brand)),
        ],
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
    final bytes = buildReceiptBytes(
      receipt: r,
      companyName: company?.name ?? 'OrbixPOS',
      branchName: app.context?.branch.name ?? '',
      cashierName: app.me?.displayName ?? '',
      width: cfg.receiptWidthCols,
      mode: cfg.printMode,
      gift: _gift,
      reversed: _reversed || r.invoice.status.isVoid,
      kickDrawer: cfg.kickDrawer,
      companyDetailLines: company == null ? const [] : companyReceiptLines(company),
    );
    setState(() => _printing = true);
    try {
      await const ReceiptPrinter().printRaw(printer, bytes);
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
