import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:intl/intl.dart';

import '../../app/theme.dart';
import '../../core/api/api_exception.dart';
import '../../core/money.dart';
import '../../models/sale.dart';
import '../../state/pending_sale_store.dart';
import '../../state/providers.dart';
import '../../state/receipt_journal.dart';
import '../../widgets/ui.dart';
import '../receipt/receipt_view.dart';

/// What the cashier chose in the unfinished-sale prompt.
enum _Choice { check, later, discard }

/// Settles an unfinished sale left behind by an interrupted attempt (a crash, a
/// force-close, or a timeout the cashier walked away from).
///
/// Returns `true` when the till is clear to ring a new sale, `false` when the
/// cashier must deal with the unfinished one first. Call it BEFORE opening the
/// payment sheet: ringing a fresh basket while an attempt is unresolved is
/// exactly how a sale gets charged twice.
/// Guards against two prompts stacking over the same unfinished sale: the
/// register opens one fire-and-forget on entry, and a Pay tap opens another.
/// Both begin with an async read, so without this a tap landing in that gap
/// would push a second barrier-dismissible dialog over the first.
bool _resolving = false;

Future<bool> resolvePendingSale(BuildContext context, WidgetRef ref) async {
  if (_resolving) return false;
  _resolving = true;
  try {
    return await _resolvePendingSale(context, ref);
  } finally {
    _resolving = false;
  }
}

Future<bool> _resolvePendingSale(BuildContext context, WidgetRef ref) async {
  final store = ref.read(pendingSaleStoreProvider);
  final pending = await store.read();
  if (pending == null) return true;
  if (!context.mounted) return false;

  final choice = await showDialog<_Choice>(
    context: context,
    barrierDismissible: false,
    builder: (_) => _PendingSaleDialog(pending: pending),
  );
  if (!context.mounted) return false;

  switch (choice) {
    case _Choice.check:
      return _checkWithServer(context, ref, pending);
    case _Choice.discard:
      await store.clear();
      if (context.mounted) {
        showToast(context,
            "Stopped checking. If that sale did go through you'll find it "
            "under Today's sales.");
      }
      return true;
    case _Choice.later:
    case null:
      return false;
  }
}

/// Replays the stored request under the SAME key. The server returns the
/// original sale if it committed, and completes it exactly once if it never
/// arrived — either way there is only ever one invoice.
Future<bool> _checkWithServer(
    BuildContext context, WidgetRef ref, PendingSale pending) async {
  final sale = ref.read(saleServiceProvider);
  final store = ref.read(pendingSaleStoreProvider);
  try {
    final invoice = await sale.ring(pending.body,
        idempotencyKey: pending.txnId, xRequestId: pending.txnId);
    await store.clear();

    Receipt receipt;
    try {
      receipt = await sale.loadReceipt(invoice.uid,
          clientTxnId: pending.txnId, tenderedAmount: pending.tenderedAmount);
    } catch (_) {
      receipt = Receipt(
          invoice: invoice,
          lines: const [],
          payments: const [],
          clientTxnId: pending.txnId,
          tenderedAmount: pending.tenderedAmount);
    }
    await ref.read(receiptJournalProvider).add(receipt);
    if (!context.mounted) return true;
    showToast(context, 'That sale is settled — here is the receipt.', ok: true);
    await showReceiptSheet(context, ref, receipt);
    return true;
  } on ApiException catch (e) {
    if (e.isAmbiguousWrite) {
      // Still unknown — keep the slot so it can be checked again, and keep the
      // till blocked rather than risk a second charge.
      if (context.mounted) {
        showToast(context,
            'Still no answer from the ERP. Check the connection and try again.');
      }
      return false;
    }
    if (e.statusCode == 409) {
      // The original attempt is still being processed (or its outcome is not
      // readable yet). That is NOT a terminal answer: clearing the slot here
      // would free the till to ring the same sale a second time — the exact
      // duplicate-invoice this recovery flow exists to prevent. Keep the slot.
      if (context.mounted) {
        showToast(context,
            'That sale is still going through. Give it a moment, then check again.');
      }
      return false;
    }
    // A definite answer, but not one that proves the sale posted (e.g. the shift
    // has since been closed). Release the till and point at where to look — we
    // must never guess that it did not go through.
    await store.clear();
    if (context.mounted) {
      showToast(context,
          "We couldn't settle that sale from here. Check Today's sales for it "
          'before ringing it again.');
    }
    return true;
  }
}

class _PendingSaleDialog extends StatelessWidget {
  const _PendingSaleDialog({required this.pending});
  final PendingSale pending;

  @override
  Widget build(BuildContext context) {
    final at = DateFormat('MMM d, HH:mm').format(pending.startedAt.toLocal());
    return AlertDialog(
      shape: RoundedRectangleBorder(borderRadius: AppRadii.brLg),
      title: Row(children: const [
        Icon(Icons.help_outline, color: AppColors.warn),
        SizedBox(width: 10),
        Flexible(child: Text('Unfinished sale')),
      ]),
      content: Column(
        mainAxisSize: MainAxisSize.min,
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(
            'A sale of ${formatMoneyParts(pending.amount, pending.currency)} '
            'started at $at was interrupted before we knew whether it went '
            'through.',
            style: const TextStyle(color: AppColors.ink2),
          ),
          const SizedBox(height: 10),
          const Text(
            'Check it now — this can never ring the same sale twice.',
            style: TextStyle(fontWeight: FontWeight.w600),
          ),
        ],
      ),
      actions: [
        TextButton(
            onPressed: () => Navigator.pop(context, _Choice.discard),
            child: const Text('Stop asking')),
        TextButton(
            onPressed: () => Navigator.pop(context, _Choice.later),
            child: const Text('Not now')),
        OrbixButton(
            label: 'Check sale',
            icon: Icons.sync,
            onPressed: () => Navigator.pop(context, _Choice.check)),
      ],
    );
  }
}
