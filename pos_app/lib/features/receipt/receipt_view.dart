import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:intl/intl.dart';

import '../../app/theme.dart';
import '../../core/api/api_exception.dart';
import '../../core/money.dart';
import '../../models/auth.dart';
import '../../models/sale.dart';
import '../../state/app_controller.dart';
import '../../state/providers.dart';
import '../../state/receipt_journal.dart';
import '../../widgets/ui.dart';

/// Shows a printed-style receipt built from the finalised invoice (the receipt
/// of record, AS-8). Reprint/gift never re-post (G-8); reverse is gated.
Future<void> showReceiptSheet(
    BuildContext context, WidgetRef ref, Receipt receipt) {
  return showDialog(
    context: context,
    builder: (_) => _ReceiptDialog(receipt: receipt),
  );
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

  Receipt get r => widget.receipt;

  @override
  Widget build(BuildContext context) {
    final app = ref.watch(appControllerProvider);
    final canReverse = app.can(Perms.saleVoid) &&
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
                            onPressed: () => showToast(context,
                                'Sent to printer (peripheral stub).', ok: true)),
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
                'stock. Allowed while the session is open.'),
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
              label: 'Reverse',
              kind: BtnKind.danger,
              onPressed: () => Navigator.pop(context, true)),
        ],
      ),
    );
    if (ok != true) return;
    final reason = reasonCtrl.text.trim().isEmpty
        ? 'POS reversal'
        : reasonCtrl.text.trim();
    try {
      await ref.read(saleServiceProvider).reverse(r.invoice.uid, reason);
      // Reconcile the local journal so an offline reprint reflects the reversal.
      await ref.read(receiptJournalProvider).markReversed(r.invoice.uid);
      setState(() => _reversed = true);
      if (mounted) showToast(context, 'Sale reversed.', ok: true);
    } on ApiException catch (e) {
      if (mounted) showToast(context, e.message);
    }
  }
}
