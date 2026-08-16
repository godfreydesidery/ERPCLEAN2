import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../app/theme.dart';
import '../../core/api/api_client.dart';
import '../../core/api/api_exception.dart';
import '../../core/config/step_up_policy.dart';
import '../../core/money.dart';
import '../../models/auth.dart';
import '../../models/enums.dart';
import '../../models/sale.dart';
import '../../state/app_controller.dart';
import '../../state/cart_controller.dart';
import '../../state/pending_sale_store.dart';
import '../../state/providers.dart';
import '../../state/receipt_journal.dart';
import '../../widgets/ui.dart';
import '../auth/approval_dialog.dart';
import '../receipt/receipt_view.dart';
import '../register/pickers.dart';
import 'pending_sale_recovery.dart';

/// Opens the payment modal. On a completed sale it clears the basket lines and
/// shows the receipt.
Future<void> openPaymentSheet(BuildContext context, WidgetRef ref) async {
  // An earlier attempt whose outcome is still unknown must be settled FIRST.
  // Ringing a new basket on top of one is how the same sale gets charged twice.
  if (!await resolvePendingSale(context, ref)) return;
  if (!context.mounted) return;
  // A sale needs a customer (AS-3). The cart normally defaults to the company's
  // walk-in customer; if none is set — no walk-in / no customer registered for
  // this company, or CUSTOMER.VIEW not granted — posting would fail server-side
  // with a raw "customerId: must not be null". Prompt for one here with a
  // friendly, actionable message instead.
  if (ref.read(cartProvider).customer == null) {
    showToast(context, 'Select a customer before completing the sale.');
    await showCustomerPicker(context, ref);
    if (!context.mounted || ref.read(cartProvider).customer == null) return;
  }
  final receipt = await showDialog<Receipt>(
    context: context,
    barrierDismissible: false,
    builder: (_) => const _PaymentSheet(),
  );
  if (receipt != null) {
    ref.read(cartProvider.notifier).clearLines();
    // Persist locally so it can be reprinted without re-posting (G-8), offline.
    await ref.read(receiptJournalProvider).add(receipt);
    if (context.mounted) await showReceiptSheet(context, ref, receipt);
  }
}

class _PaymentSheet extends ConsumerStatefulWidget {
  const _PaymentSheet();
  @override
  ConsumerState<_PaymentSheet> createState() => _PaymentSheetState();
}

class _PaymentSheetState extends ConsumerState<_PaymentSheet> {
  /// Client transaction id for THIS logical sale — used as the Idempotency-Key
  /// and X-Request-Id, and reused verbatim on every retry (PRIN-4/PRIN-7) so an
  /// ambiguous attempt cannot double-post.
  ///
  /// It is written to device storage (see [PendingSaleStore]) BEFORE the POST,
  /// so it also survives the till being force-closed or losing power mid-sale —
  /// in memory alone it would be lost exactly when it matters most.
  final String _txnId = ApiClient.newTxnId();

  /// When this basket was taken to payment (K11). Sent as `capturedAt` so a
  /// basket that only reaches the server days later is refused as a stale
  /// replay instead of being quietly re-priced into the current period.
  final DateTime _capturedAt = DateTime.now();

  final List<PosTender> _tenders = [];
  TenderType _type = TenderType.cash;
  // The amount box is a real editable field: the hardware keyboard types into it
  // AND the on-screen keypad / quick-cash presets drive the same controller.
  final TextEditingController _amountCtrl = TextEditingController();
  final FocusNode _amountFocus = FocusNode();
  bool _busy = false;

  /// The outcome is genuinely unknown — retrying under the SAME key is the safe
  /// move and is what resolves it.
  bool _ambiguous = false;

  /// The server refused the sale outright. Nothing was written, so the till is
  /// free — but the sheet stays open with the basket intact so the cashier can
  /// fix what was wrong and try again.
  String? _rejection;

  /// Whatever the server said, on an outcome we could NOT classify as a definite
  /// refusal — kept as supporting detail beneath the ambiguous banner, never in
  /// place of it.
  ///
  /// This used to be discarded. A sale that fails a business rule the server has
  /// no definite verdict for (an unpriced line, most commonly) arrives as a plain
  /// 400, which [PosSaleFlowStatus.resolve] maps to `unknown` — so the till showed
  /// "no answer from the ERP, press Retry" while the response body carried an
  /// exact explanation. Retrying can never succeed, and nobody at the counter can
  /// see why. That cost a customer a morning of a dead till.
  ///
  /// The classification is deliberately left alone: only the server may claim
  /// nothing was written, and treating an unrecognised 4xx as definite is what
  /// once produced a second invoice. So the cashier still reads "press Retry — it
  /// is safe". This only stops the reason being invisible to whoever is called
  /// over to look at it.
  String? _serverDetail;

  /// True when a refusal COULD be about an unapproved discount, so offering the
  /// manager-approval path is worth doing. The threshold itself is the server's
  /// business (it is company policy and the till is never told what it is) —
  /// this is only ever about which button to show.
  bool _offerDiscountApproval = false;

  @override
  void dispose() {
    _amountCtrl.dispose();
    _amountFocus.dispose();
    super.dispose();
  }

  double get _gross => ref.read(cartProvider).previewSubtotal;
  double get _paid => _tenders.fold(0, (s, t) => s + t.amount);
  double get _remaining => (_gross - _paid).clamp(0, double.infinity);

  double get _typed => double.tryParse(_amountCtrl.text) ?? 0;

  /// Sets the amount box (quick-cash presets) and keeps focus so the hardware
  /// keyboard can keep editing.
  void _setAmount(String s) {
    _amountCtrl.value = TextEditingValue(
      text: s,
      selection: TextSelection.collapsed(offset: s.length),
    );
    _amountFocus.requestFocus();
    setState(() {});
  }

  // ---------------------------------------------------------------- tender ops

  void _addTender() {
    final amt = _typed > 0 ? _typed : _remaining;
    if (amt <= 0) return;
    setState(() {
      _tenders.add(PosTender(tenderType: _type, amount: amt));
      _amountCtrl.clear();
    });
    _amountFocus.requestFocus();
  }

  void _removeTender(int i) => setState(() => _tenders.removeAt(i));

  /// On-screen keypad — edits the same controller the hardware keyboard types
  /// into, so both input methods stay in sync.
  void _key(String k) {
    final t = _amountCtrl.text;
    final String next;
    if (k == 'C') {
      next = '';
    } else if (k == '<') {
      next = t.isNotEmpty ? t.substring(0, t.length - 1) : t;
    } else if (k == '.') {
      next = t.contains('.') ? t : (t.isEmpty ? '0.' : '$t.');
    } else {
      next = '$t$k';
    }
    _amountCtrl.value = TextEditingValue(
      text: next,
      selection: TextSelection.collapsed(offset: next.length),
    );
    _amountFocus.requestFocus();
    setState(() {});
  }

  // ---------------------------------------------------------------- complete

  Future<bool> _confirmAge() async {
    final cart = ref.read(cartProvider);
    final kinds = cart.activeLines
        .map((l) => l.product.restrictedKind)
        .where((k) => k.isRestricted)
        .map((k) => k.ageLabel)
        .toSet()
        .join(', ');
    final ok = await showDialog<bool>(
      context: context,
      builder: (_) => AlertDialog(
        shape: RoundedRectangleBorder(borderRadius: AppRadii.brLg),
        title: Row(children: const [
          Icon(Icons.shield_outlined, color: AppColors.warn),
          SizedBox(width: 10),
          Text('Age-restricted items'),
        ]),
        content: Text(
            'This basket contains $kinds items. Confirm you have verified the '
            'customer meets the minimum age.'),
        actions: [
          TextButton(
              onPressed: () => Navigator.pop(context, false),
              child: const Text('Cancel')),
          OrbixButton(
              label: 'Age verified',
              onPressed: () => Navigator.pop(context, true)),
        ],
      ),
    );
    return ok ?? false;
  }

  Future<void> _complete() async {
    // Single entry point for a POST that takes money, so the guard lives here
    // rather than only on the buttons. The call sites await dialogs (age check,
    // manager approval) before reaching the request, and _busy is false across
    // those gaps — so two taps could otherwise queue two runs of this method.
    if (_busy) return;
    final cart = ref.read(cartProvider);
    final app = ref.read(appControllerProvider);
    final session = app.shift;
    if (session == null) return;

    final hasUnpriced = cart.hasUnpricedLine;
    final knownGross = !hasUnpriced && _gross > 0;

    // Tender-coverage guard — only when the total is KNOWN (a fully-priced
    // basket). When a line has no preview price the total is indeterminate, so
    // we let the server price it rather than gate on a 0/partial preview.
    if (knownGross && _tenders.isNotEmpty && _paid + 0.0001 < _gross) {
      showToast(context, 'Tendered ${formatAmount(_paid)} is less than the '
          'total ${formatAmount(_gross)}.');
      return;
    }
    if (knownGross && _tenders.isEmpty && _typed > 0 && _typed + 0.0001 < _gross) {
      showToast(context, 'Tendered ${formatAmount(_typed)} is less than the '
          'total ${formatAmount(_gross)}. Use Add tender to split, or key the full amount.');
      return;
    }

    // Age gate (ADR-0044 D-3a). Verify unless the cashier holds the override.
    var ageVerified = false;
    if (cart.hasRestricted) {
      ageVerified = await _confirmAge();
      if (!mounted) return;
      if (!ageVerified && !app.can(Perms.saleAgeOverride)) {
        showToast(context, 'Sale stopped: age not verified.');
        return;
      }
    }

    // Resolve the tenders to post. The single-CASH fast path is used ONLY when
    // no explicit tenders were added AND Cash is the selected type; a non-cash
    // type chosen without "Add tender" is booked under THAT type, never silently
    // recorded as cash.
    List<PosTender>? tenders;
    double? tendered;
    if (_tenders.isNotEmpty) {
      tenders = _tenders;
      tendered = _cashPortion();
    } else if (_type == TenderType.cash) {
      tenders = null; // server settles one exact CASH payment (+ change)
      tendered = _typed > 0 ? _typed : (knownGross ? _gross : null);
    } else {
      final amt = _typed > 0 ? _typed : (knownGross ? _gross : 0.0);
      if (amt <= 0) {
        showToast(context, 'Enter the ${_type.label} amount, then Complete sale.');
        return;
      }
      tenders = [PosTender(tenderType: _type, amount: amt)];
      tendered = null;
    }

    final body = cart.buildRequest(
      session.uid,
      tenders: tenders,
      tenderedAmount: tendered,
      ageVerified: ageVerified,
      capturedAt: _capturedAt,
    );

    setState(() {
      _busy = true;
      _ambiguous = false;
      _rejection = null;
      _serverDetail = null;
      _offerDiscountApproval = false;
    });
    final pendingStore = ref.read(pendingSaleStoreProvider);
    try {
      // Persist the key + the exact body BEFORE the POST. If the till dies
      // between the server's commit and the response, the next launch replays
      // this under the same key instead of re-ringing a second invoice.
      await pendingStore.save(PendingSale(
        txnId: _txnId,
        sessionUid: session.uid,
        body: body,
        startedAt: _capturedAt,
        amount: _gross,
        currency: cart.currency,
        tenderedAmount: tendered,
      ));
      final sale = ref.read(saleServiceProvider);
      final invoice = await sale.ring(body,
          idempotencyKey: _txnId, xRequestId: _txnId);
      // The sale is COMMITTED from here on. The basket must not be re-ringable,
      // so the key stays in the durable slot until the receipt has actually been
      // handed to the register. Clearing it here (which is what this code used to
      // do) opened the double-charge window: any exit between this point and the
      // pop below left a committed sale with a live basket AND no key, so the next
      // Pay minted a fresh _txnId and the server had nothing to dedupe against —
      // the whole basket posted twice.
      Receipt receipt;
      try {
        receipt = await sale.loadReceipt(invoice.uid,
            clientTxnId: _txnId, tenderedAmount: tendered);
      } catch (_) {
        receipt = Receipt(
            invoice: invoice,
            lines: const [],
            payments: const [],
            clientTxnId: _txnId,
            tenderedAmount: tendered);
      }
      // Hand the receipt back BEFORE releasing the slot. If the sheet was disposed
      // while the POST was in flight there is no route to pop, so the slot is left
      // armed on purpose: the next launch replays under the SAME key, gets the same
      // invoice back, and the sale is reconciled instead of duplicated.
      if (!mounted) return;
      await pendingStore.clear();
      if (!mounted) return;
      Navigator.of(context).pop(receipt);
    } on ApiException catch (e) {
      await _handleRingFailure(e, pendingStore);
    }
  }

  /// Decides what the refusal means for the till.
  ///
  /// **This is the K11 defect.** The old rule was "409 = undecided, keep the
  /// pending slot" — and until K11 the server answered 409 for four different
  /// things, including a plain first-attempt business rejection. So a cashier
  /// who under-tendered, or hit an out-of-stock line, armed a durable "pending
  /// sale" for an attempt that had never been in flight at all: a ghost that
  /// then blocked the till on every subsequent Pay, forever, with nothing to
  /// find on the server.
  ///
  /// The slot is now armed for genuinely ambiguous outcomes ONLY:
  ///
  /// * no response at all (timeout / connection drop) — the request may or may
  ///   not have reached the server;
  /// * a 5xx, which can arrive after the row committed;
  /// * `IN_FLIGHT`, which means an attempt under this key really is running.
  ///
  /// `REJECTED` (nothing was written) and `STALE_REPLAY` (too old to complete)
  /// are definite: the slot is dropped and the basket stays on screen to be
  /// fixed. Branching is on the machine-readable code and the status — never on
  /// the message.
  Future<void> _handleRingFailure(
      ApiException e, PendingSaleStore pendingStore) async {
    final status = PosSaleFlowStatus.resolve(e.code, e.statusCode);
    // "Nothing was written" is a claim only the SERVER may make, via an explicit
    // REJECTED or STALE_REPLAY verdict. Anything else — a proxy that rewrote a 502
    // into a 4xx, a 401 the refresh could not repair, a response we failed to parse
    // — is an unknown outcome that can sit on top of a commit. Treating those as
    // definite refusals cleared the key and told the cashier nothing was charged,
    // which is precisely the instruction that produced a second invoice.
    final declaredNoWrite = status == PosSaleFlowStatus.rejected ||
        status == PosSaleFlowStatus.staleReplay;
    final undecided = !declaredNoWrite ||
        e.isAmbiguousWrite ||
        status == PosSaleFlowStatus.inFlight;
    if (!undecided) await pendingStore.clear();
    if (!mounted) return;

    // Offer the manager-approval path only for an explicit business REJECTED,
    // and only when there is in fact an unapproved discount it could be about.
    // Offering it after a 403 or a validation error would be a guess dressed up
    // as a remedy — the till does not know the ceiling and must not pretend to.
    final canApprove = status == PosSaleFlowStatus.rejected &&
        ref.read(cartProvider).linesNeedingDiscountApproval.isNotEmpty;

    setState(() {
      _busy = false;
      _ambiguous = undecided;
      _rejection = undecided ? null : e.message;
      // Keep the server's own words either way. When the outcome is undecided the
      // banner above still leads with "press Retry"; this only preserves the
      // explanation that used to be dropped on the floor.
      _serverDetail = undecided ? e.message : null;
      _offerDiscountApproval = canApprove;
    });
    if (status == PosSaleFlowStatus.staleReplay) {
      showToast(context,
          'This basket is too old to complete. Nothing was charged — ring it '
          'again.');
      return;
    }
    if (!e.isAmbiguousWrite) showToast(context, e.message);
  }

  /// Asks a manager to authorise the discounts the server would not take on the
  /// cashier's authority alone (K7), then retries under the SAME key.
  ///
  /// The approval is stamped on the specific lines named in the prompt, not on
  /// the sale: a basket may hold one heavily discounted item and nine ordinary
  /// ones, and a sale-level flag would let one approval wave the rest through.
  /// The server independently re-resolves the named manager and requires them
  /// to genuinely hold the override in this invoice's company, so what travels
  /// on the wire is a pointer to an approval, never the approval itself.
  Future<void> _approveDiscountsAndRetry() async {
    // The button is disabled on _busy, but _busy is false while this awaits the
    // manager prompt — so without this the cashier can stack two approval dialogs
    // and two retries. Hide the affordance for the rest of this attempt instead.
    if (_busy || !_offerDiscountApproval) return;
    setState(() => _offerDiscountApproval = false);
    final lines = ref.read(cartProvider).linesNeedingDiscountApproval;
    if (lines.isEmpty) return;
    final detail = lines
        .map((l) =>
            '${l.product.name}: less ${formatAmount(l.lineDiscountAmount)}')
        .join('\n');
    final approval = await showManagerApproval(
      context,
      ref,
      action: GatedAction.discountOverride,
      detail: detail,
      correlationId: _txnId,
    );
    if (approval == null || !mounted) return;
    final uid = approval.authoriserUid?.trim() ?? '';
    if (uid.isEmpty) {
      // Cannot happen against a correct server (an approval always names its
      // approver) — but stamping a blank uid would read to the server as "no
      // approval supplied", i.e. the same refusal with an extra round trip.
      showToast(context, 'That approval could not be applied. Try again.');
      return;
    }
    final ctrl = ref.read(cartProvider.notifier);
    for (final l in lines) {
      ctrl.setDiscountAuthorisation(l.localId, uid, approval.approverLabel);
    }
    setState(() {
      _rejection = null;
      _serverDetail = null;
      _offerDiscountApproval = false;
    });
    showToast(context, 'Approved by ${approval.approverLabel}.', ok: true);
    await _complete();
  }

  double? _cashPortion() {
    final cash = _tenders
        .where((t) => t.tenderType == TenderType.cash)
        .fold(0.0, (s, t) => s + t.amount);
    return cash > 0 ? cash : null;
  }

  // ---------------------------------------------------------------- build

  @override
  Widget build(BuildContext context) {
    final change = _changePreview();
    // barrierDismissible:false stops a click-away, and the X button is disabled
    // while _busy — but neither blocks the Esc key, which pops a Flutter dialog by
    // default on desktop. Esc pressed during a slow POST disposed this State mid
    // sale, which is one of the ways a committed sale used to lose its sheet. The
    // route stays put until the request settles.
    return PopScope(
      canPop: !_busy,
      child: Dialog(
        shape: RoundedRectangleBorder(borderRadius: AppRadii.brLg),
        child: ConstrainedBox(
          constraints: const BoxConstraints(maxWidth: 840, maxHeight: 620),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              _header(),
              Flexible(
                child: Row(
                  crossAxisAlignment: CrossAxisAlignment.stretch,
                  children: [
                    Expanded(child: _left(change)),
                    SizedBox(width: 320, child: _right()),
                  ],
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }

  double _changePreview() {
    // Total is indeterminate while a line has no preview price — don't show a
    // change figure computed against a 0/partial total.
    if (ref.read(cartProvider).hasUnpricedLine) return 0;
    if (_tenders.isEmpty) {
      return (_typed - _gross).clamp(0, double.infinity);
    }
    return (_paid - _gross).clamp(0, double.infinity);
  }

  Widget _header() {
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 20, vertical: 16),
      decoration: const BoxDecoration(
          border: Border(bottom: BorderSide(color: AppColors.line))),
      child: Row(
        children: [
          const Text('Payment',
              style: TextStyle(fontSize: 18, fontWeight: FontWeight.w700)),
          const Spacer(),
          IconButton(
            onPressed: _busy ? null : () => Navigator.pop(context),
            icon: const Icon(Icons.close),
          ),
        ],
      ),
    );
  }

  Widget _left(double change) {
    final cart = ref.watch(cartProvider);
    return Padding(
      padding: const EdgeInsets.all(20),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          Container(
            padding: const EdgeInsets.all(16),
            decoration: BoxDecoration(
                color: AppColors.brandSoft, borderRadius: AppRadii.brLg),
            child: Column(
              children: [
                Text('AMOUNT DUE (${cart.currency})',
                    style: const TextStyle(
                        fontSize: 12,
                        fontWeight: FontWeight.w600,
                        color: AppColors.brandD)),
                NumText(formatAmount(_gross),
                    alignment: Alignment.center,
                    style: const TextStyle(
                        fontSize: 32,
                        fontWeight: FontWeight.w800,
                        color: AppColors.brandD,
                        fontFeatures: kTabular)),
              ],
            ),
          ),
          const SizedBox(height: 16),
          _tenderTypes(),
          const SizedBox(height: 14),
          Expanded(child: _tenderList()),
          const Divider(),
          _summaryRow('Total', _gross, bold: true),
          _summaryRow('Paid', _paid),
          if (change > 0) _summaryRow('Change', change, accent: true),
          if (_ambiguous) ...[
            const SizedBox(height: 10),
            _ambiguousBanner(),
            if (_serverDetail != null && _serverDetail!.trim().isNotEmpty) ...[
              const SizedBox(height: 6),
              _serverDetailNote(_serverDetail!),
            ],
          ],
          if (_rejection != null) ...[
            const SizedBox(height: 10),
            _rejectedBanner(_rejection!),
          ],
        ],
      ),
    );
  }

  Widget _tenderTypes() {
    return Row(
      children: TenderType.values.map((t) {
        final active = _type == t;
        final icon = switch (t) {
          TenderType.cash => Icons.payments_outlined,
          TenderType.card => Icons.credit_card,
          TenderType.mobileMoney => Icons.smartphone,
          TenderType.cheque => Icons.receipt_long_outlined,
        };
        return Expanded(
          child: Padding(
            padding: const EdgeInsets.only(right: 8),
            child: InkWell(
              borderRadius: AppRadii.brSm,
              onTap: () => setState(() => _type = t),
              child: Container(
                padding: const EdgeInsets.symmetric(vertical: 12),
                decoration: BoxDecoration(
                  color: active ? AppColors.brandSoft : AppColors.panel,
                  borderRadius: AppRadii.brSm,
                  border: Border.all(
                      color: active ? AppColors.brand : AppColors.line2,
                      width: 1.5),
                ),
                child: Column(
                  children: [
                    Icon(icon,
                        size: 18,
                        color: active ? AppColors.brandD : AppColors.ink2),
                    const SizedBox(height: 4),
                    Text(t.label,
                        style: TextStyle(
                            fontSize: 12,
                            fontWeight: FontWeight.w600,
                            color: active ? AppColors.brandD : AppColors.ink2)),
                  ],
                ),
              ),
            ),
          ),
        );
      }).toList(),
    );
  }

  Widget _tenderList() {
    if (_tenders.isEmpty) {
      return const Center(
        child: Text('Quick-cash or add a tender →',
            style: TextStyle(color: AppColors.ink3)),
      );
    }
    return ListView.builder(
      itemCount: _tenders.length,
      itemBuilder: (context, i) {
        final t = _tenders[i];
        return Container(
          margin: const EdgeInsets.only(bottom: 8),
          padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 9),
          decoration: BoxDecoration(
            color: AppColors.panel2,
            borderRadius: AppRadii.brSm,
            border: Border.all(color: AppColors.line),
          ),
          child: Row(
            children: [
              Text(t.tenderType.label,
                  style: const TextStyle(
                      color: AppColors.ink2, fontWeight: FontWeight.w600)),
              const SizedBox(width: 10),
              Expanded(
                child: NumText(formatAmount(t.amount),
                    style: numStyle(weight: FontWeight.w700)),
              ),
              const SizedBox(width: 10),
              InkWell(
                onTap: () => _removeTender(i),
                child: const Icon(Icons.close, size: 16, color: AppColors.ink3),
              ),
            ],
          ),
        );
      },
    );
  }

  Widget _summaryRow(String label, double value,
      {bool bold = false, bool accent = false}) {
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 3),
      child: Row(
        mainAxisAlignment: MainAxisAlignment.spaceBetween,
        children: [
          Text(label,
              style: TextStyle(
                  color: accent ? AppColors.payD : AppColors.ink2,
                  fontWeight: bold ? FontWeight.w800 : FontWeight.w600,
                  fontSize: bold ? 18 : 14)),
          const SizedBox(width: 8),
          Flexible(
            child: NumText(formatAmount(value),
                style: numStyle(
                    size: bold ? 18 : 14,
                    weight: bold ? FontWeight.w800 : FontWeight.w700,
                    color: accent ? AppColors.payD : AppColors.ink)),
          ),
        ],
      ),
    );
  }

  Widget _ambiguousBanner() {
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 10),
      decoration: BoxDecoration(
        color: AppColors.warnSoft,
        borderRadius: AppRadii.brSm,
        border: Border.all(color: const Color(0xFFFDE68A)),
      ),
      // Read by a cashier mid-sale, with a customer waiting: say what to do, not what
      // went wrong internally. The reassurance is the important part — retrying looks
      // like the risky choice and is in fact the safe one.
      child: const Text(
        'No answer from the ERP, so we cannot tell whether this sale went through. '
        'Press Retry — it is safe. If the sale was already recorded you will get that '
        'same receipt back, never a second charge.',
        style: TextStyle(
            color: AppColors.warn, fontSize: 12.5, fontWeight: FontWeight.w600),
      ),
    );
  }

  /// The server's own words, under the ambiguous banner — supporting detail, in a
  /// quieter voice, never the headline.
  ///
  /// Deliberately NOT styled as an error and deliberately second. The cashier's
  /// instruction is still "press Retry", because the outcome really is unknown and
  /// retrying under the same key is still the safe move. This line is for the
  /// person called over when retrying keeps failing: without it they see only
  /// "no answer from the ERP" and start looking at the network, when the response
  /// said something as specific as "this product has no price yet".
  Widget _serverDetailNote(String message) {
    return Row(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        const Padding(
          padding: EdgeInsets.only(top: 1, right: 6),
          child: Icon(Icons.info_outline, size: 13, color: AppColors.ink2),
        ),
        Expanded(
          child: Text(
            'The ERP said: $message',
            style: const TextStyle(
                color: AppColors.ink2, fontSize: 11.5, height: 1.35),
          ),
        ),
      ],
    );
  }

  /// Shown when the ERP definitely refused the sale. The wording leads with the
  /// reassurance that matters at a till with a customer waiting — nothing was
  /// charged — because a refusal that looks ambiguous is what makes a cashier
  /// ring it a second time "to be sure".
  Widget _rejectedBanner(String message) {
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 10),
      decoration: BoxDecoration(
        color: AppColors.dangerSoft,
        borderRadius: AppRadii.brSm,
        border: Border.all(color: const Color(0xFFFECACA)),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          Text(message,
              style: const TextStyle(
                  color: AppColors.danger,
                  fontSize: 12.5,
                  fontWeight: FontWeight.w600)),
          const SizedBox(height: 4),
          // Only promise "nothing was charged" for a refusal the server declared.
          // This banner is now reached only on REJECTED/STALE_REPLAY; every other
          // failure routes to the ambiguous banner, which says to press Retry.
          const Text('Nothing was charged. Fix it and try again.',
              style: TextStyle(color: AppColors.ink2, fontSize: 11.5)),
          if (_offerDiscountApproval) ...[
            const SizedBox(height: 10),
            OrbixButton(
              label: 'Get manager approval for the discount',
              icon: Icons.verified_user_outlined,
              kind: BtnKind.ghost,
              block: true,
              onPressed: _busy ? null : _approveDiscountsAndRetry,
            ),
          ],
        ],
      ),
    );
  }

  Widget _right() {
    return Container(
      padding: const EdgeInsets.all(20),
      decoration: const BoxDecoration(
          border: Border(left: BorderSide(color: AppColors.line))),
      child: Column(
        children: [
          Container(
            width: double.infinity,
            padding: const EdgeInsets.symmetric(horizontal: 18, vertical: 14),
            decoration: BoxDecoration(
                color: AppColors.ink, borderRadius: AppRadii.brSm),
            child: TextField(
              controller: _amountCtrl,
              focusNode: _amountFocus,
              autofocus: true,
              textAlign: TextAlign.right,
              keyboardType:
                  const TextInputType.numberWithOptions(decimal: true),
              inputFormatters: [decimalInputFormatter],
              cursorColor: Colors.white,
              onChanged: (_) => setState(() {}),
              onSubmitted: (_) => _busy ? null : _complete(),
              style: const TextStyle(
                  color: Colors.white,
                  fontSize: 28,
                  fontWeight: FontWeight.w800,
                  fontFeatures: kTabular),
              decoration: const InputDecoration(
                // The global inputDecorationTheme is `filled: true` with a WHITE fillColor
                // (AppColors.panel). This field paints white text at 28pt on a dark box, so
                // inheriting that fill covers the box in white and the cashier's typed tender
                // amount becomes invisible. Opting out is the whole fix.
                filled: false,
                fillColor: Colors.transparent,
                isDense: true,
                contentPadding: EdgeInsets.zero,
                border: InputBorder.none,
                enabledBorder: InputBorder.none,
                focusedBorder: InputBorder.none,
                hintText: '0',
                hintStyle: TextStyle(color: Color(0x66FFFFFF)),
              ),
            ),
          ),
          const SizedBox(height: 12),
          _quickCash(),
          const SizedBox(height: 12),
          Expanded(child: _keypad()),
          const SizedBox(height: 12),
          OrbixButton(
              label: 'Add tender',
              icon: Icons.add,
              kind: BtnKind.ghost,
              block: true,
              onPressed: _addTender),
          const SizedBox(height: 8),
          SizedBox(
            width: double.infinity,
            child: PayButton(
              label: _ambiguous ? 'Retry this sale' : 'Complete sale',
              amount: formatAmount(_gross),
              onPressed: _busy ? null : _complete,
            ),
          ),
        ],
      ),
    );
  }

  Widget _quickCash() {
    final presets = _presets(_gross);
    return GridView.count(
      crossAxisCount: 4,
      shrinkWrap: true,
      physics: const NeverScrollableScrollPhysics(),
      mainAxisSpacing: 6,
      crossAxisSpacing: 6,
      childAspectRatio: 2,
      children: [
        for (final p in presets)
          _quickBtn(p == _gross ? 'Exact' : formatAmount(p, decimals: 0),
              () => _setAmount(p.toStringAsFixed(2))),
      ],
    );
  }

  List<double> _presets(double gross) {
    final out = <double>{gross};
    for (final step in [1000, 2000, 5000, 10000, 20000]) {
      out.add((gross / step).ceil() * step.toDouble());
    }
    final list = out.toList()..sort();
    return list.take(8).toList();
  }

  Widget _quickBtn(String label, VoidCallback onTap) {
    return Material(
      color: AppColors.panel,
      borderRadius: AppRadii.brSm,
      child: InkWell(
        borderRadius: AppRadii.brSm,
        onTap: onTap,
        child: Container(
          alignment: Alignment.center,
          decoration: BoxDecoration(
            borderRadius: AppRadii.brSm,
            border: Border.all(color: AppColors.line2),
          ),
          child: Text(label,
              style: const TextStyle(
                  fontWeight: FontWeight.w700,
                  fontSize: 13,
                  color: AppColors.ink2)),
        ),
      ),
    );
  }

  Widget _keypad() {
    final keys = ['7', '8', '9', '4', '5', '6', '1', '2', '3', 'C', '0', '<'];
    return GridView.count(
      crossAxisCount: 3,
      mainAxisSpacing: 8,
      crossAxisSpacing: 8,
      childAspectRatio: 1.5,
      physics: const NeverScrollableScrollPhysics(),
      children: keys.map((k) {
        return Material(
          color: AppColors.panel,
          borderRadius: AppRadii.brSm,
          child: InkWell(
            borderRadius: AppRadii.brSm,
            onTap: () => _key(k),
            child: Container(
              alignment: Alignment.center,
              decoration: BoxDecoration(
                borderRadius: AppRadii.brSm,
                border: Border.all(color: AppColors.line2),
              ),
              child: k == '<'
                  ? const Icon(Icons.backspace_outlined, size: 18)
                  : Text(k,
                      style: const TextStyle(
                          fontSize: 20, fontWeight: FontWeight.w700)),
            ),
          ),
        );
      }).toList(),
    );
  }
}
