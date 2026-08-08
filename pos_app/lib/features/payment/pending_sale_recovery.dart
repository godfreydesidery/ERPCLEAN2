import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:intl/intl.dart';

import '../../app/theme.dart';
import '../../core/api/api_exception.dart';
import '../../core/config/step_up_policy.dart';
import '../../core/money.dart';
import '../../models/enums.dart';
import '../../models/sale.dart';
import '../../state/pending_sale_store.dart';
import '../../state/providers.dart';
import '../../state/receipt_journal.dart';
import '../../widgets/ui.dart';
import '../auth/approval_dialog.dart';
import '../receipt/receipt_view.dart';

/// What the cashier chose in the unfinished-sale prompt.
enum _Choice { check, later, giveUp }

/// Guards against two prompts stacking over the same unfinished sale: the
/// register opens one fire-and-forget on entry, and a Pay tap opens another.
/// Both begin with an async read, so without this a tap landing in that gap
/// would push a second barrier-dismissible dialog over the first.
bool _resolving = false;

/// Settles an unfinished sale left behind by an interrupted attempt (a crash, a
/// force-close, or a timeout the cashier walked away from).
///
/// Returns `true` when the till is clear to ring a new sale, `false` when the
/// cashier must deal with the unfinished one first. Call it BEFORE opening the
/// payment sheet: ringing a fresh basket while an attempt is unresolved is
/// exactly how a sale gets charged twice.
///
/// ## Why the old loop could not end
///
/// "Check sale" used to **re-POST the whole sale**. That re-ran every business
/// guard against data that had since moved on, so hours later the answer was
/// "the session is closed" — which is not an answer to "did it post?". The
/// prompt came back forever, and its only exit was "Stop asking", which
/// discarded the question rather than answering it, leaving no trace anywhere
/// that a real-money question had been abandoned.
///
/// Now it calls the cheap read (`GET /pos/sales/idempotency/{key}`), which
/// consults the idempotency marker and nothing else and therefore stays valid
/// long after the shift ended. Every verdict is terminal except one:
///
/// * **POSTED** — show the receipt, clear the slot. Done.
/// * **NEVER_POSTED** — nothing was recorded; the cashier chooses to complete it
///   (replayed under the SAME reference, which is race-safe) or discard it.
/// * **UNKNOWN** — an attempt genuinely is in progress. The only verdict that
///   leaves the question open.
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
    case _Choice.giveUp:
      return _abandon(context, ref, pending);
    case _Choice.later:
    case null:
      return false;
  }
}

/// Asks the server what became of the sale, and acts on the answer.
Future<bool> _checkWithServer(
    BuildContext context, WidgetRef ref, PendingSale pending) async {
  final PosSaleLookup lookup;
  try {
    lookup = await ref
        .read(saleServiceProvider)
        .lookupByIdempotencyKey(pending.txnId);
  } on ApiException catch (e) {
    // We could not ASK. That is not the same as not knowing the answer — keep
    // the slot and keep the till blocked rather than risk a second charge.
    if (context.mounted) {
      showToast(
          context,
          e.isAmbiguous
              ? 'Cannot reach the ERP to check. Try again when the connection '
                  'is back.'
              : e.message);
    }
    return false;
  }
  if (!context.mounted) return false;

  switch (lookup.verdict) {
    case PosSaleLookupVerdict.posted:
      return _settlePosted(context, ref, pending, lookup);
    case PosSaleLookupVerdict.neverPosted:
      return _settleNeverPosted(context, ref, pending);
    case PosSaleLookupVerdict.unknown:
      showToast(context,
          'That sale is still going through. Give it a moment, then check again.');
      return false;
  }
}

/// Definitive: the sale exists. Release the slot and put the receipt in front of
/// the cashier so the question is visibly closed.
Future<bool> _settlePosted(BuildContext context, WidgetRef ref,
    PendingSale pending, PosSaleLookup lookup) async {
  await ref.read(pendingSaleStoreProvider).clear();
  final receipt = await _loadReceipt(ref, pending, lookup);
  if (receipt != null) {
    await ref.read(receiptJournalProvider).add(receipt);
  }
  if (!context.mounted) return true;
  showToast(
      context,
      'That sale went through as ${lookup.invoiceNumber ?? 'a receipt'}. '
      'Nothing more to do.',
      ok: true);
  if (receipt != null) await showReceiptSheet(context, ref, receipt);
  return true;
}

/// The server says nothing was recorded. Two honest ways out, and the cashier
/// picks — the till does not decide by itself whether a customer standing there
/// with the goods gets charged.
///
/// **Completing replays the stored basket under the SAME reference.** That is
/// the only race-safe way to finish it: if an attempt happens to commit at this
/// very instant, the replay finds that marker and returns the very same invoice
/// instead of creating a second one. Ringing the basket fresh would mint a new
/// reference and could genuinely double-charge.
Future<bool> _settleNeverPosted(
    BuildContext context, WidgetRef ref, PendingSale pending) async {
  final store = ref.read(pendingSaleStoreProvider);
  final complete = await showDialog<bool>(
    context: context,
    barrierDismissible: false,
    builder: (_) => _NeverPostedDialog(pending: pending),
  );
  if (!context.mounted) return false;

  if (complete != true) {
    await store.clear();
    if (context.mounted) {
      showToast(context, 'Discarded — nothing was charged.', ok: true);
    }
    return true;
  }

  final sale = ref.read(saleServiceProvider);
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
    if (!context.mounted) return false;
    return _handleReplayFailure(context, ref, e);
  }
}

/// Maps a failed replay onto the till's state, branching on the machine-readable
/// outcome (and the HTTP status behind it) — never on the message text.
Future<bool> _handleReplayFailure(
    BuildContext context, WidgetRef ref, ApiException e) async {
  final store = ref.read(pendingSaleStoreProvider);
  if (e.isAmbiguousWrite) {
    if (context.mounted) {
      showToast(context,
          'Still no answer from the ERP. Check the connection and try again.');
    }
    return false;
  }
  switch (PosSaleFlowStatus.resolve(e.code, e.statusCode)) {
    case PosSaleFlowStatus.inFlight:
      if (context.mounted) {
        showToast(context,
            'That sale is still going through. Give it a moment, then check again.');
      }
      return false;
    case PosSaleFlowStatus.staleReplay:
      await store.clear();
      if (context.mounted) {
        showToast(context,
            'That basket is too old to complete. Nothing was charged — ring it '
            'again.');
      }
      return true;
    case PosSaleFlowStatus.rejected:
    case PosSaleFlowStatus.unknown:
      // A definite refusal means nothing was written. Release the till and show
      // the server's reason.
      await store.clear();
      if (context.mounted) showToast(context, e.message);
      return true;
  }
}

/// Loads the full receipt for a sale the lookup says was posted. Falls back to a
/// header-only receipt so the cashier still sees the invoice number when the
/// detail reads fail.
Future<Receipt?> _loadReceipt(
    WidgetRef ref, PendingSale pending, PosSaleLookup lookup) async {
  final uid = lookup.invoiceUid;
  if (uid == null || uid.isEmpty) return null;
  final sale = ref.read(saleServiceProvider);
  try {
    return await sale.loadReceipt(uid,
        clientTxnId: pending.txnId, tenderedAmount: pending.tenderedAmount);
  } catch (_) {
    try {
      final invoice = await sale.getInvoice(uid);
      return Receipt(
          invoice: invoice,
          lines: const [],
          payments: const [],
          clientTxnId: pending.txnId,
          tenderedAmount: pending.tenderedAmount);
    } catch (_) {
      return null;
    }
  }
}

/// Abandons an unfinished sale without an answer.
///
/// This writes real money off a question, so it does not happen on a cashier's
/// own say-so and it does not happen only on this device. It takes a manager
/// step-up, which the server audits (`AUTH.STEP_UP.*` — actor is the cashier who
/// asked, target is the approver), so "someone decided to stop asking about a
/// 240,000 sale at 19:40" survives somewhere other than a local preference key
/// that was then deleted. The sale's own reference travels as the request's
/// `X-Request-Id`, which the server puts into its MDC, tying the approval to the
/// exact sale in the request log.
///
/// One last read is attempted first: if the server can answer, the question gets
/// closed properly and no approval is needed at all.
Future<bool> _abandon(
    BuildContext context, WidgetRef ref, PendingSale pending) async {
  try {
    final lookup = await ref
        .read(saleServiceProvider)
        .lookupByIdempotencyKey(pending.txnId);
    if (lookup.verdict != PosSaleLookupVerdict.unknown) {
      if (!context.mounted) return false;
      return lookup.verdict == PosSaleLookupVerdict.posted
          ? _settlePosted(context, ref, pending, lookup)
          : _settleNeverPosted(context, ref, pending);
    }
  } on ApiException catch (_) {
    // Offline, or the server cannot say — fall through to the approval.
  }
  if (!context.mounted) return false;

  final outcome = await approveIfRequired(
    context,
    ref,
    action: GatedAction.abandonUnfinishedSale,
    detail: 'Sale of ${formatMoneyParts(pending.amount, pending.currency)} '
        'started ${DateFormat('MMM d, HH:mm').format(pending.startedAt.toLocal())}.',
    correlationId: pending.txnId,
  );
  if (!outcome.allowed) {
    if (context.mounted) {
      showToast(context, 'Not approved — the sale is still unresolved.');
    }
    return false;
  }

  await ref.read(pendingSaleStoreProvider).clear();
  if (context.mounted) {
    final by = outcome.approverLabel;
    showToast(
        context,
        by == null
            ? "Left unresolved. Check Today's sales before ringing it again."
            : "Left unresolved, approved by $by. Check Today's sales before "
                'ringing it again.',
        ok: true);
  }
  return true;
}

// ============================================================ dialogs

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
      content: ConstrainedBox(
        constraints: const BoxConstraints(maxWidth: 460),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(
              'A sale of ${formatMoneyParts(pending.amount, pending.currency)} '
              'started at $at was interrupted before we knew whether it went '
              'through.',
              style: const TextStyle(color: AppColors.ink2),
            ),
            const SizedBox(height: 12),
            // Say what each button actually does. The old copy promised "this
            // can never ring the same sale twice" and then offered "Stop
            // asking" without mentioning that it answers nothing.
            const _WhatEachButtonDoes(),
          ],
        ),
      ),
      actions: [
        TextButton(
            onPressed: () => Navigator.pop(context, _Choice.giveUp),
            child: const Text('Leave unresolved…')),
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

class _WhatEachButtonDoes extends StatelessWidget {
  const _WhatEachButtonDoes();

  @override
  Widget build(BuildContext context) {
    Widget row(IconData icon, String label, String what) => Padding(
          padding: const EdgeInsets.symmetric(vertical: 4),
          child: Row(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Icon(icon, size: 15, color: AppColors.ink3),
              const SizedBox(width: 8),
              Expanded(
                child: Text.rich(
                  TextSpan(children: [
                    TextSpan(
                        text: '$label — ',
                        style: const TextStyle(fontWeight: FontWeight.w700)),
                    TextSpan(
                        text: what,
                        style: const TextStyle(color: AppColors.ink2)),
                  ]),
                  style: const TextStyle(fontSize: 12.5),
                ),
              ),
            ],
          ),
        );

    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        row(Icons.sync, 'Check sale',
            'asks the ERP what happened. This only reads — it charges nothing '
            'and it decides nothing.'),
        row(Icons.schedule, 'Not now',
            'leaves the question open. The till stays blocked until it is '
            'settled.'),
        row(Icons.lock_outline, 'Leave unresolved',
            'gives up without an answer. Needs a manager, and is recorded on '
            'the ERP.'),
      ],
    );
  }
}

/// Shown once the server has confirmed nothing was recorded.
class _NeverPostedDialog extends StatelessWidget {
  const _NeverPostedDialog({required this.pending});
  final PendingSale pending;

  @override
  Widget build(BuildContext context) {
    return AlertDialog(
      shape: RoundedRectangleBorder(borderRadius: AppRadii.brLg),
      title: Row(children: const [
        Icon(Icons.check_circle_outline, color: AppColors.ok),
        SizedBox(width: 10),
        Flexible(child: Text('Nothing was recorded')),
      ]),
      content: ConstrainedBox(
        constraints: const BoxConstraints(maxWidth: 460),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(
              'The ERP has no sale under this reference, so the customer was '
              'not charged. The basket '
              '(${formatMoneyParts(pending.amount, pending.currency)}) is still '
              'here.',
              style: const TextStyle(color: AppColors.ink2),
            ),
            const SizedBox(height: 12),
            const Text(
              'Complete it now — this is the safe option. It is sent under the '
              'same reference, so even if the first attempt lands this second, '
              'you get that same receipt back, never a second charge.',
              style: TextStyle(fontSize: 12.5, fontWeight: FontWeight.w600),
            ),
            const SizedBox(height: 8),
            const Text(
              'Discard if the customer has left or no longer wants it.',
              style: TextStyle(fontSize: 12.5, color: AppColors.ink3),
            ),
          ],
        ),
      ),
      actions: [
        TextButton(
            onPressed: () => Navigator.pop(context, false),
            child: const Text('Discard')),
        OrbixButton(
            label: 'Complete the sale',
            icon: Icons.point_of_sale,
            onPressed: () => Navigator.pop(context, true)),
      ],
    );
  }
}
