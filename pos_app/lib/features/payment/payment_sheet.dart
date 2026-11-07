import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../app/theme.dart';
import '../../core/api/api_client.dart';
import '../../core/api/api_exception.dart';
import '../../core/config/step_up_policy.dart';
import '../../core/money.dart';
import '../../models/auth.dart';
import '../../models/enums.dart';
import '../../models/restaurant.dart';
import '../../models/sale.dart';
import '../../offline/offline_controller.dart';
import '../../state/app_controller.dart';
import '../../state/cart_controller.dart';
import '../../state/price_cache.dart';
import '../../state/pending_sale_store.dart';
import '../../state/providers.dart';
import '../../state/receipt_journal.dart';
import '../../widgets/ui.dart';
import '../auth/approval_dialog.dart';
import '../offline/offline_payment_sheet.dart';
import '../offline/offline_widgets.dart';
import '../receipt/receipt_view.dart';
import '../register/pickers.dart';
import 'pending_sale_recovery.dart';

/// Set by the online sheet when the cashier chose to finish an unanswered sale
/// offline: the Idempotency-Key of that attempt (ERP ADR-0068).
String? _offlineHandoffKey;

/// Opens the payment modal. On a completed sale it clears the basket lines and
/// shows the receipt.
///
/// While the till is offline (ERP ADR-0068) the offline payment sheet takes the
/// payment instead: cash, mobile money or card, kept on the till until sync.
Future<void> openPaymentSheet(BuildContext context, WidgetRef ref) async {
  if (ref.read(offlineControllerProvider).offline) {
    return openOfflinePaymentSheet(context, ref);
  }
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
  _offlineHandoffKey = null;
  final receipt = await showDialog<Receipt>(
    context: context,
    barrierDismissible: false,
    builder: (_) => const _PaymentSheet(),
  );
  final handoff = _offlineHandoffKey;
  _offlineHandoffKey = null;
  if (receipt == null && handoff != null && context.mounted) {
    // The server stopped answering mid-sale and the cashier chose to finish it
    // offline. Same key: if the attempt did land, the sync answers
    // ALREADY_POSTED instead of booking it twice.
    if (await switchToOffline(context, ref) && context.mounted) {
      await openOfflinePaymentSheet(context, ref, reuseKey: handoff);
    }
    return;
  }
  if (receipt != null) {
    // Next sale: back to the walk-in customer (POS-15) and fresh prices (POS-18).
    ref.read(cartProvider.notifier).clearLines();
    ref.read(priceCacheProvider).clear();
    // Persist locally so it can be reprinted without re-posting (G-8), offline.
    await ref.read(receiptJournalProvider).add(receipt);
    if (context.mounted) await showReceiptSheet(context, ref, receipt);
  }
}

/// What a restaurant payment settles (ERP ADR-0065 D-8): an open order on the
/// server, not the till's basket.
class RestaurantCheckout {
  const RestaurantCheckout({
    required this.order,
    required this.total,
    this.hasRestricted = false,
  });

  /// The order being paid. It lives on the server, so a payment that dies
  /// half-way cannot be lost or double-posted: the server locks the order and
  /// refuses to settle it twice, and Retry reuses the same key.
  final RestaurantOrder order;

  /// The bill total the customer was shown. Sent as `expectedTotal`, so a price
  /// that changed since is refused instead of charged.
  final double total;

  /// Whether the order holds age-restricted items (asks the age question).
  final bool hasRestricted;
}

/// Opens the payment modal for a restaurant order. Returns the receipt once the
/// order has been paid (and shown), or null when the cashier backed out.
Future<Receipt?> openRestaurantPaymentSheet(
    BuildContext context, WidgetRef ref, RestaurantCheckout checkout) async {
  // Restaurant orders live on the server (offline phase 2 is not built).
  if (ref.read(offlineControllerProvider).offline) {
    showToast(context, 'Restaurant orders need the server. The order is safe and can be '
        'paid when the till is back online.');
    return null;
  }
  // An unresolved supermarket sale from earlier still blocks the drawer.
  if (!await resolvePendingSale(context, ref)) return null;
  if (!context.mounted) return null;
  if (ref.read(cartProvider).customer == null) {
    showToast(context, 'Select a customer before completing the sale.');
    await showCustomerPicker(context, ref);
    if (!context.mounted || ref.read(cartProvider).customer == null) return null;
  }
  final receipt = await showDialog<Receipt>(
    context: context,
    barrierDismissible: false,
    builder: (_) => _PaymentSheet(restaurant: checkout),
  );
  if (receipt != null) {
    ref.read(priceCacheProvider).clear();
    await ref.read(receiptJournalProvider).add(receipt);
    if (context.mounted) {
      await showReceiptSheet(context, ref, receipt,
          served: (
            waiter: checkout.order.waiterName,
            where: checkout.order.where,
            order: checkout.order.orderNumber,
          ));
    }
  }
  return receipt;
}

/// The cashier's answer to the age check.
enum _AgeDecision { verified, overridden, cancelled }

class _PaymentSheet extends ConsumerStatefulWidget {
  const _PaymentSheet({this.restaurant});

  /// Set when paying a restaurant order rather than the basket.
  final RestaurantCheckout? restaurant;
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

  /// Restaurant order discount (a percent OR an amount) and the server's exact
  /// quote for it; the manager who approved it when the ceiling needs one.
  double? _discPercent;
  double? _discAmount;
  RestaurantQuote? _quote;
  String? _discAuthoriserUid;

  /// The manager who approved a complimentary settlement, once asked.
  String? _compAuthoriserUid;

  /// Who a complimentary settlement was given to, once asked. Printed on the
  /// receipt; travels as the tender's reference.
  String? _compGivenTo;

  @override
  void dispose() {
    _amountCtrl.dispose();
    _amountFocus.dispose();
    super.dispose();
  }

  RestaurantCheckout? get _resto => widget.restaurant;

  double get _gross =>
      _resto != null ? (_quote?.grossTotal ?? _resto!.total) : ref.read(cartProvider).previewSubtotal;

  /// A line without a preview price makes the basket total indeterminate. A
  /// restaurant total comes from the server's bill, so it is always known.
  bool get _hasUnpriced => _resto == null && ref.read(cartProvider).hasUnpricedLine;
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
    setState(() => _tenders.add(PosTender(tenderType: _type, amount: amt)));
    // What is still to pay goes in the box, ready for the next payment type.
    final left = _hasUnpriced ? 0.0 : _remaining;
    _setAmount(left > 0
        ? left.toStringAsFixed(minorUnitsFor(ref.read(cartProvider).currency))
        : '');
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

  /// Asks the cashier to confirm the customer's age.
  ///
  /// **Cancel always stops the sale.** Someone who holds the age override gets a
  /// third, explicit button for it. It used to be implied: for an override
  /// holder, Cancel fell through to "sell anyway", so the one button that reads
  /// as "stop" completed the sale.
  Future<_AgeDecision> _confirmAge({required bool canOverride}) async {
    final cart = ref.read(cartProvider);
    final kinds = _resto != null
        ? 'age-restricted'
        : cart.activeLines
            .map((l) => l.product.restrictedKind)
            .where((k) => k.isRestricted)
            .map((k) => k.ageLabel)
            .toSet()
            .join(', ');
    final decision = await showDialog<_AgeDecision>(
      context: context,
      builder: (_) => AlertDialog(
        shape: RoundedRectangleBorder(borderRadius: AppRadii.brLg),
        title: Row(children: const [
          Icon(Icons.shield_outlined, color: AppColors.warn),
          SizedBox(width: 10),
          Text('Age-restricted items'),
        ]),
        content: Text(
            'This ${_resto != null ? 'order' : 'basket'} contains $kinds items. Confirm you have verified the '
            'customer meets the minimum age.'),
        actions: [
          TextButton(
              onPressed: () => Navigator.pop(context, _AgeDecision.cancelled),
              child: const Text('Cancel')),
          if (canOverride)
            TextButton(
                onPressed: () => Navigator.pop(context, _AgeDecision.overridden),
                child: const Text('Override without check')),
          OrbixButton(
              label: 'Age verified',
              onPressed: () => Navigator.pop(context, _AgeDecision.verified)),
        ],
      ),
    );
    return decision ?? _AgeDecision.cancelled;
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

    // A split payment: the amount still in the box is the last part, with or
    // without Add tender (Tulia 2026-11-02: split "haifanyi kazi").
    if (_tenders.isNotEmpty && _typed > 0) {
      setState(() {
        _tenders.add(PosTender(tenderType: _type, amount: _typed));
        _amountCtrl.clear();
      });
    }

    final hasUnpriced = _hasUnpriced;
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

    // Age gate (ADR-0044 D-3a). Verified, or an explicit override by someone who
    // holds it (the server re-checks that permission); Cancel always stops.
    var ageVerified = false;
    if (_resto?.hasRestricted ?? cart.hasRestricted) {
      final decision =
          await _confirmAge(canOverride: app.can(Perms.saleAgeOverride));
      if (!mounted) return;
      if (decision == _AgeDecision.cancelled) {
        showToast(context, 'Sale stopped: age not verified.');
        return;
      }
      ageVerified = decision == _AgeDecision.verified;
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

    // Complimentary names who it was given to, and needs a manager unless the
    // cashier may approve discounts.
    final comp = (tenders ?? const <PosTender>[])
        .where((t) => t.tenderType == TenderType.complimentary)
        .fold(0.0, (s, t) => s + t.amount);
    if (comp > 0) {
      if (_compGivenTo == null) {
        final who = await askComplimentaryGivenTo(context);
        if (who == null || !mounted) return;
        _compGivenTo = who;
      }
      if (!app.can(Perms.discountOverride) && _compAuthoriserUid == null) {
        final approval = await showManagerApproval(context, ref,
            action: GatedAction.complimentary,
            detail: 'Complimentary ${formatAmount(comp)} for $_compGivenTo',
            correlationId: _txnId);
        if (approval == null || !mounted) return;
        final uid = approval.authoriserUid?.trim() ?? '';
        if (uid.isEmpty) {
          showToast(context, 'That approval could not be applied. Try again.');
          return;
        }
        _compAuthoriserUid = uid;
      }
      tenders = [
        for (final t in tenders!)
          t.tenderType == TenderType.complimentary
              ? PosTender(
                  tenderType: t.tenderType,
                  amount: t.amount,
                  reference: _compGivenTo,
                  authorisedByUid: _compAuthoriserUid)
              : t,
      ];
    }

    final resto = _resto;
    if (resto != null) {
      await _payRestaurantOrder(resto, session.uid,
          tenders: tenders, tendered: tendered, ageVerified: ageVerified);
      return;
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

  /// Pays a restaurant order: the server turns it into a POS sale (ADR-0065 D-8).
  ///
  /// No pending-sale slot is armed. The order itself is the durable record: it
  /// stays on the server until it is paid, the server locks it and refuses a
  /// second settlement, and Retry reuses [_txnId] so an ambiguous attempt
  /// returns the same sale instead of a new one.
  Future<void> _payRestaurantOrder(
    RestaurantCheckout resto,
    String sessionUid, {
    required List<PosTender>? tenders,
    required double? tendered,
    required bool ageVerified,
  }) async {
    final cart = ref.read(cartProvider);
    final body = <String, dynamic>{
      'sessionUid': sessionUid,
      'customerId': cart.customer?.id,
      'currency': cart.currency,
      if (tenders != null && tenders.isNotEmpty)
        'tenders': tenders.map((t) => t.toJson()).toList(),
      'tenderedAmount': ?tendered,
      'expectedTotal': _quote?.grossTotal ?? resto.total,
      'ageVerified': ageVerified,
      'discountPercent': ?_discPercent,
      'discountAmount': ?_discAmount,
      'discountAuthorisedByUid': ?_discAuthoriserUid,
    };
    setState(() {
      _busy = true;
      _ambiguous = false;
      _rejection = null;
      _serverDetail = null;
      _offerDiscountApproval = false;
    });
    try {
      final paid = await ref
          .read(restaurantServiceProvider)
          .pay(resto.order.uid, body, idempotencyKey: _txnId);
      final sale = ref.read(saleServiceProvider);
      Receipt receipt;
      try {
        receipt = await sale.loadReceipt(paid.invoice.uid,
            clientTxnId: _txnId, tenderedAmount: tendered);
      } catch (_) {
        receipt = Receipt(
            invoice: paid.invoice,
            lines: const [],
            payments: const [],
            clientTxnId: _txnId,
            tenderedAmount: tendered);
      }
      if (!mounted) return;
      Navigator.of(context).pop(receipt);
    } on ApiException catch (e) {
      await _handleRingFailure(e, null);
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
      ApiException e, PendingSaleStore? pendingStore) async {
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
    if (!undecided) await pendingStore?.clear();
    if (!mounted) return;

    // Offer the manager-approval path only for an explicit business REJECTED,
    // and only when there is in fact an unapproved discount it could be about.
    // Offering it after a 403 or a validation error would be a guess dressed up
    // as a remedy — the till does not know the ceiling and must not pretend to.
    final canApprove = status == PosSaleFlowStatus.rejected &&
        (_resto == null
            ? ref.read(cartProvider).linesNeedingDiscountApproval.isNotEmpty
            : (_quote?.discountTotal ?? 0) > 0 && _discAuthoriserUid == null);
    // A refused complimentary approval is asked for again on the next attempt.
    if (!undecided) _compAuthoriserUid = null;

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
    if (_resto != null) {
      final approval = await showManagerApproval(context, ref,
          action: GatedAction.discountOverride,
          detail: 'Discount ${formatAmount(_quote?.discountTotal ?? 0)}',
          correlationId: _txnId);
      if (approval == null || !mounted) return;
      final uid = approval.authoriserUid?.trim() ?? '';
      if (uid.isEmpty) {
        showToast(context, 'That approval could not be applied. Try again.');
        return;
      }
      setState(() {
        _discAuthoriserUid = uid;
        _rejection = null;
        _serverDetail = null;
      });
      showToast(context, 'Approved by ${approval.approverLabel}.', ok: true);
      await _complete();
      return;
    }
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
    final screen = MediaQuery.sizeOf(context);
    // A phone: one column that scrolls. A phone held sideways: both sides, each
    // scrolling. A till or a tablet: the sheet as it always was.
    final narrow = screen.width < 760;
    final short = screen.height < 600;
    final Widget body;
    if (narrow) {
      // Complete sale stays pinned under the scrolling part, never below the fold.
      body = Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          Flexible(
            child: SingleChildScrollView(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.stretch,
                children: [
                  _left(change, compact: true),
                  _right(compact: true, below: true, actions: false),
                ],
              ),
            ),
          ),
          Container(
            padding: const EdgeInsets.fromLTRB(14, 10, 14, 14),
            decoration: const BoxDecoration(
                border: Border(top: BorderSide(color: AppColors.line))),
            child: _actions(),
          ),
        ],
      );
    } else if (short) {
      body = Row(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          Expanded(child: SingleChildScrollView(child: _left(change, compact: true))),
          SizedBox(
            width: 320,
            child: Column(
              children: [
                Expanded(
                    child: SingleChildScrollView(child: _right(compact: true, actions: false))),
                Container(
                  padding: const EdgeInsets.fromLTRB(14, 8, 14, 12),
                  decoration: const BoxDecoration(
                      border: Border(
                          left: BorderSide(color: AppColors.line),
                          top: BorderSide(color: AppColors.line))),
                  child: _actions(),
                ),
              ],
            ),
          ),
        ],
      );
    } else {
      body = Row(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          Expanded(child: _left(change)),
          SizedBox(width: 320, child: _right()),
        ],
      );
    }
    return PopScope(
      canPop: !_busy,
      child: Dialog(
        shape: RoundedRectangleBorder(borderRadius: AppRadii.brLg),
        insetPadding: narrow || short
            ? const EdgeInsets.all(8)
            : const EdgeInsets.symmetric(horizontal: 40, vertical: 24),
        child: ConstrainedBox(
          // A phone gets the whole height; elsewhere the sheet keeps its size.
          constraints: BoxConstraints(maxWidth: 840, maxHeight: narrow ? screen.height : 620),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              _header(),
              Flexible(child: body),
            ],
          ),
        ),
      ),
    );
  }

  double _changePreview() {
    // Total is indeterminate while a line has no preview price — don't show a
    // change figure computed against a 0/partial total.
    if (_hasUnpriced) return 0;
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
          Expanded(
            child: Text(
              _resto == null
                  ? 'Payment'
                  : 'Payment · ${_resto!.order.where} · ${_resto!.order.orderNumber}'
                      '${_resto!.order.waiterName.isEmpty ? '' : ' · Waiter: ${_resto!.order.waiterName}'}',
              maxLines: 2,
              overflow: TextOverflow.ellipsis,
              style: const TextStyle(fontSize: 18, fontWeight: FontWeight.w700)),
          ),
          IconButton(
            onPressed: _busy ? null : () => Navigator.pop(context),
            icon: const Icon(Icons.close),
          ),
        ],
      ),
    );
  }

  /// [compact]: the sheet scrolls, so nothing in it may claim the leftover height.
  Widget _left(double change, {bool compact = false}) {
    final cart = ref.watch(cartProvider);
    return Padding(
      padding: EdgeInsets.all(compact ? 14 : 20),
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
          if (compact)
            _tenderList(shrink: true)
          else
            Expanded(child: _tenderList()),
          const Divider(),
          _summaryRow('Total', _gross, bold: true),
          _summaryRow('Paid', _paid),
          if (_tenders.isNotEmpty && !_hasUnpriced && _remaining > 0)
            _summaryRow('Remaining', _remaining, bold: true),
          if (change > 0) _summaryRow('Change', change, accent: true),
          if (_ambiguous) ...[
            const SizedBox(height: 10),
            _ambiguousBanner(),
            if (_serverDetail != null && _serverDetail!.trim().isNotEmpty) ...[
              const SizedBox(height: 6),
              _serverDetailNote(_serverDetail!),
            ],
            _finishOfflineOffer(),
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
        final icon = tenderIcon(t);
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
                    FittedBox(
                      fit: BoxFit.scaleDown,
                      child: Text(t.label,
                          style: TextStyle(
                              fontSize: 12,
                              fontWeight: FontWeight.w600,
                              color: active ? AppColors.brandD : AppColors.ink2)),
                    ),
                  ],
                ),
              ),
            ),
          ),
        );
      }).toList(),
    );
  }

  Widget _tenderList({bool shrink = false}) {
    if (_tenders.isEmpty) {
      return Center(
        child: Padding(
          padding: EdgeInsets.symmetric(vertical: shrink ? 6 : 0),
          child: Text(shrink ? 'Quick-cash or add a tender' : 'Quick-cash or add a tender →',
              style: const TextStyle(color: AppColors.ink3)),
        ),
      );
    }
    return ListView.builder(
      shrinkWrap: shrink,
      physics: shrink ? const NeverScrollableScrollPhysics() : null,
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

  /// ERP ADR-0068: the connection died under this sale and the probe confirmed
  /// the server is gone. On a till allowed offline the cashier may finish the
  /// sale offline, under the SAME key — so if the attempt did reach the server,
  /// the sync answers ALREADY_POSTED instead of charging twice.
  Widget _finishOfflineOffer() {
    final s = ref.watch(offlineControllerProvider);
    if (_resto != null || !s.serverDown || !s.tillAllowed || _busy) {
      return const SizedBox.shrink();
    }
    return Padding(
      padding: const EdgeInsets.only(top: 8),
      child: OrbixButton(
        label: 'No connection — finish this sale offline',
        icon: Icons.cloud_off,
        kind: BtnKind.ghost,
        block: true,
        onPressed: () {
          _offlineHandoffKey = _txnId;
          Navigator.of(context).pop();
        },
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

  /// [below]: under the amounts on a phone rather than beside them.
  /// [actions]: false when the sheet pins Add tender / Complete sale itself.
  Widget _right({bool compact = false, bool below = false, bool actions = true}) {
    return Container(
      padding: EdgeInsets.all(compact ? 14 : 20),
      decoration: BoxDecoration(
          border: below
              ? const Border(top: BorderSide(color: AppColors.line))
              : const Border(left: BorderSide(color: AppColors.line))),
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
              // On Android the sheet's own keypad does the typing; the system
              // keyboard would cover half the sheet.
              autofocus: true,
              textAlign: TextAlign.right,
              keyboardType: defaultTargetPlatform == TargetPlatform.android
                  ? TextInputType.none
                  : const TextInputType.numberWithOptions(decimal: true),
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
          if (_resto != null) ...[
            _discountRow(),
            const SizedBox(height: 4),
          ],
          _splitRow(),
          const SizedBox(height: 8),
          _quickCash(),
          const SizedBox(height: 12),
          if (compact)
            SizedBox(height: 216, child: _keypad())
          else
            Expanded(child: _keypad()),
          if (actions) ...[
            const SizedBox(height: 12),
            _actions(),
          ],
        ],
      ),
    );
  }

  Widget _actions() {
    return Column(
      mainAxisSize: MainAxisSize.min,
      children: [
        SlowWaitHint(active: _busy),
        OrbixButton(
            label: 'Add tender',
            icon: Icons.add,
            kind: BtnKind.ghost,
            block: true,
            onPressed: _busy ? null : _addTender),
        const SizedBox(height: 8),
        SizedBox(
          width: double.infinity,
          child: PayButton(
            label: _busy
                ? 'Recording the sale…'
                : _ambiguous
                    ? 'Retry this sale'
                    : 'Complete sale',
            amount: formatAmount(_gross),
            busy: _busy,
            onPressed: _busy ? null : _complete,
          ),
        ),
      ],
    );
  }

  /// Restaurant order discount: a percent or an amount, priced exactly by the
  /// server (quote) before anything is paid.
  Widget _discountRow() {
    final off = _quote?.discountTotal ?? 0;
    return Row(
      children: [
        const Text('Discount', style: TextStyle(color: AppColors.ink2, fontSize: 12.5)),
        const SizedBox(width: 8),
        Expanded(
          child: off > 0
              ? Text(
                  '− ${formatAmount(off)}${_discPercent != null ? ' (${formatAmount(_discPercent!, decimals: 0)}%)' : ''}',
                  overflow: TextOverflow.ellipsis,
                  style: numStyle(size: 13, weight: FontWeight.w700, color: AppColors.payD))
              : const SizedBox.shrink(),
        ),
        if (off > 0)
          IconButton(
            tooltip: 'Remove discount',
            visualDensity: VisualDensity.compact,
            onPressed: _busy ? null : _clearDiscount,
            icon: const Icon(Icons.close, size: 18),
          ),
        TextButton.icon(
          onPressed: _busy ? null : _askDiscount,
          icon: const Icon(Icons.percent, size: 16),
          label: Text(off > 0 ? 'Change' : 'Add'),
        ),
      ],
    );
  }

  void _clearDiscount() => setState(() {
        _discPercent = null;
        _discAmount = null;
        _quote = null;
        _discAuthoriserUid = null;
        _tenders.clear();
      });

  Future<void> _askDiscount() async {
    final resto = _resto;
    if (resto == null) return;
    final cart = ref.read(cartProvider);
    final answer = await askOrderDiscount(context, currency: cart.currency);
    if (answer == null || !mounted) return;
    setState(() => _busy = true);
    try {
      final q = await ref.read(restaurantServiceProvider).quote(resto.order.uid,
          customerId: cart.customer?.id,
          currency: cart.currency,
          percent: answer.percent,
          amount: answer.amount);
      if (!mounted) return;
      setState(() {
        _discPercent = answer.percent;
        _discAmount = answer.amount;
        _quote = q;
        _discAuthoriserUid = null;
        // The amount due changed: tenders keyed against the old total are dropped.
        _tenders.clear();
        _amountCtrl.clear();
      });
      showToast(context, 'Discount applied. Amount due ${formatAmount(q.grossTotal)}.', ok: true);
    } on ApiException catch (e) {
      if (mounted) showToast(context, e.message);
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  /// Split bill, and on a restaurant order the even-share buttons beside it.
  /// "Split evenly" used to be a plain label here, and a cashier tapping it got
  /// nothing (Tulia 2026-11-03).
  ///
  /// Splitting a bill evenly (ADR-0066 D-2): one sale, several payments. Each
  /// ÷ button keys in one share of what is still to pay, rounded UP to the
  /// currency's smallest unit so the last guest never comes up short; the
  /// cashier then picks the tender and taps Add tender for each guest.
  Widget _splitRow() {
    final minor = minorUnitsFor(ref.read(cartProvider).currency);
    final factor = 1.0 * _pow10(minor);
    return Row(
      children: [
        Expanded(child: SizedBox(height: 34, child: _splitBillBtn())),
        if (_resto != null)
          for (final n in const [2, 3, 4])
            Padding(
              padding: const EdgeInsets.only(left: 6),
              child: _quickBtnSized('÷$n', () {
                final share = (_remaining / n * factor).ceil() / factor;
                _setAmount(share.toStringAsFixed(minor));
              }),
            ),
      ],
    );
  }

  Widget _splitBillBtn() {
    return Material(
      color: AppColors.brandSoft,
      borderRadius: AppRadii.brSm,
      child: InkWell(
        borderRadius: AppRadii.brSm,
        onTap: _busy ? null : _splitBill,
        child: Container(
          alignment: Alignment.center,
          padding: const EdgeInsets.symmetric(horizontal: 8),
          decoration: BoxDecoration(
            borderRadius: AppRadii.brSm,
            border: Border.all(color: AppColors.brand),
          ),
          child: const FittedBox(
            fit: BoxFit.scaleDown,
            child: Row(
              mainAxisSize: MainAxisSize.min,
              children: [
                Icon(Icons.call_split, size: 16, color: AppColors.brandD),
                SizedBox(width: 6),
                Text('Split bill',
                    style: TextStyle(
                        fontWeight: FontWeight.w700, fontSize: 13, color: AppColors.brandD)),
              ],
            ),
          ),
        ),
      ),
    );
  }

  /// Breaks the bill across payment types — e.g. 35,000 as 10,000 cash +
  /// 15,000 card + 10,000 mobile — and completes the sale once every shilling
  /// is covered, so the receipt comes straight up for printing.
  Future<void> _splitBill() async {
    if (_hasUnpriced) {
      showToast(context, 'An item has no price yet, so the total is not known. Fix the price first.');
      return;
    }
    final parts = await showSplitBill(context,
        due: _gross, currency: ref.read(cartProvider).currency, initial: _tenders);
    if (parts == null || !mounted) return;
    setState(() {
      _tenders
        ..clear()
        ..addAll(parts);
      _amountCtrl.clear();
    });
    await _complete();
  }

  static int _pow10(int n) {
    var v = 1;
    for (var i = 0; i < n; i++) {
      v *= 10;
    }
    return v;
  }

  Widget _quickBtnSized(String label, VoidCallback onTap) =>
      SizedBox(width: 46, height: 34, child: _quickBtn(label, onTap));

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

  /// The number pad. Four rows that always share the height they are given, so
  /// the bottom row (C 0 ⌫) never falls off the screen. A fixed key shape did
  /// exactly that once the restaurant's Split evenly row took its space (J COB
  /// 2026-10-30: "0 is missing").
  Widget _keypad() {
    const rows = [
      ['7', '8', '9'],
      ['4', '5', '6'],
      ['1', '2', '3'],
      ['C', '0', '<'],
    ];
    return Column(
      children: [
        for (var r = 0; r < rows.length; r++) ...[
          if (r > 0) const SizedBox(height: 8),
          Expanded(
            child: Row(
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                for (var c = 0; c < 3; c++) ...[
                  if (c > 0) const SizedBox(width: 8),
                  Expanded(child: _keypadKey(rows[r][c])),
                ],
              ],
            ),
          ),
        ],
      ],
    );
  }

  Widget _keypadKey(String k) {
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
                  style: const TextStyle(fontSize: 20, fontWeight: FontWeight.w700)),
        ),
      ),
    );
  }
}


/// The order discount a cashier keys: a percent or an amount, never both.
Future<({double? percent, double? amount})?> askOrderDiscount(BuildContext context,
    {required String currency}) {
  final ctrl = TextEditingController();
  var byPercent = true;
  return showDialog<({double? percent, double? amount})>(
    context: context,
    builder: (context) => StatefulBuilder(
      builder: (context, setState) {
        final value = double.tryParse(ctrl.text.trim()) ?? 0;
        final valid = value > 0 && (!byPercent || value <= 100);
        return AlertDialog(
          shape: RoundedRectangleBorder(borderRadius: AppRadii.brLg),
          title: const Text('Discount'),
          content: SizedBox(
            width: 360,
            child: Column(
              mainAxisSize: MainAxisSize.min,
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                SegmentedButton<bool>(
                  showSelectedIcon: false,
                  segments: [
                    const ButtonSegment(value: true, label: Text('Percent (%)')),
                    ButtonSegment(value: false, label: Text('Amount ($currency)')),
                  ],
                  selected: {byPercent},
                  onSelectionChanged: (v) => setState(() => byPercent = v.first),
                ),
                const SizedBox(height: 12),
                TextField(
                  controller: ctrl,
                  autofocus: true,
                  keyboardType: const TextInputType.numberWithOptions(decimal: true),
                  inputFormatters: [decimalInputFormatter],
                  decoration: InputDecoration(
                    labelText: byPercent ? 'Percent off' : 'Amount off',
                    suffixText: byPercent ? '%' : currency,
                  ),
                  onChanged: (_) => setState(() {}),
                ),
                if (byPercent && value > 100)
                  const Padding(
                    padding: EdgeInsets.only(top: 6),
                    child: Text('Not more than 100%.',
                        style: TextStyle(color: AppColors.danger, fontSize: 12)),
                  ),
              ],
            ),
          ),
          actions: [
            TextButton(onPressed: () => Navigator.pop(context), child: const Text('Cancel')),
            OrbixButton(
              label: 'Apply',
              onPressed: valid
                  ? () => Navigator.pop(
                      context, (percent: byPercent ? value : null, amount: byPercent ? null : value))
                  : null,
            ),
          ],
        );
      },
    ),
  );
}

/// Who a complimentary settlement was given to (Tulia 2026-11-02), printed on
/// the receipt. Null when cancelled.
Future<String?> askComplimentaryGivenTo(BuildContext context) {
  final name = TextEditingController();
  return showDialog<String>(
    context: context,
    builder: (context) => StatefulBuilder(
      builder: (context, setState) {
        final value = name.text.trim();
        void done() => Navigator.pop(context, value);
        return AlertDialog(
          shape: RoundedRectangleBorder(borderRadius: AppRadii.brLg),
          title: const Text('Complimentary — given to'),
          content: SizedBox(
            width: 340,
            child: TextField(
              controller: name,
              autofocus: true,
              maxLength: 60,
              textCapitalization: TextCapitalization.words,
              decoration: const InputDecoration(
                labelText: 'Name',
                hintText: 'Who is this for?',
              ),
              onChanged: (_) => setState(() {}),
              onSubmitted: (_) => value.isEmpty ? null : done(),
            ),
          ),
          actions: [
            TextButton(onPressed: () => Navigator.pop(context), child: const Text('Cancel')),
            OrbixButton(label: 'Continue', onPressed: value.isEmpty ? null : done),
          ],
        );
      },
    ),
  );
}

IconData tenderIcon(TenderType t) => switch (t) {
      TenderType.cash => Icons.payments_outlined,
      TenderType.card => Icons.credit_card,
      TenderType.mobileMoney => Icons.smartphone,
      TenderType.cheque => Icons.receipt_long_outlined,
      TenderType.complimentary => Icons.card_giftcard,
    };

/// What stops a split from completing, or null when it covers the bill. Only
/// cash gives change, so the other types may not add up to more than is due.
String? splitBillProblem(double due, Map<TenderType, double> parts) {
  final total = parts.values.fold(0.0, (s, v) => s + v);
  final nonCash = parts.entries
      .where((e) => e.key != TenderType.cash)
      .fold(0.0, (s, e) => s + e.value);
  if (total <= 0) return 'Enter the amount for each payment type.';
  if (nonCash > due + 0.0001) {
    return 'Only cash gives change. Card, mobile, cheque and complimentary '
        'together cannot be more than ${formatAmount(due)}.';
  }
  if (total + 0.0001 < due) return 'Still to pay: ${formatAmount(due - total)}.';
  return null;
}

/// Split bill (Tulia 2026-11-03): one amount per payment type. Null when
/// cancelled; otherwise the parts, in the order the types are listed.
Future<List<PosTender>?> showSplitBill(BuildContext context,
    {required double due, required String currency, List<PosTender> initial = const []}) {
  final minor = minorUnitsFor(currency);
  final ctrls = {
    for (final t in TenderType.values)
      t: TextEditingController(text: () {
        final v = initial
            .where((p) => p.tenderType == t)
            .fold(0.0, (s, p) => s + p.amount);
        return v > 0 ? v.toStringAsFixed(minor) : '';
      }()),
  };
  return showDialog<List<PosTender>>(
    context: context,
    builder: (context) => StatefulBuilder(
      builder: (context, setState) {
        final parts = {
          for (final e in ctrls.entries) e.key: double.tryParse(e.value.text.trim()) ?? 0.0,
        };
        final total = parts.values.fold(0.0, (s, v) => s + v);
        final problem = splitBillProblem(due, parts);
        final change = problem == null ? total - due : 0.0;

        void fillRest(TenderType t) {
          final others = total - parts[t]!;
          final rest = due - others;
          if (rest <= 0) return;
          final s = rest.toStringAsFixed(minor);
          ctrls[t]!.value =
              TextEditingValue(text: s, selection: TextSelection.collapsed(offset: s.length));
          setState(() {});
        }

        void done() => Navigator.pop(context, [
              for (final t in TenderType.values)
                if (parts[t]! > 0) PosTender(tenderType: t, amount: parts[t]!),
            ]);

        Widget row(TenderType t) => Padding(
              padding: const EdgeInsets.only(bottom: 8),
              child: Row(
                children: [
                  Icon(tenderIcon(t), size: 20, color: AppColors.ink2),
                  const SizedBox(width: 10),
                  SizedBox(
                      width: 110,
                      child: Text(t.label, style: const TextStyle(fontWeight: FontWeight.w600))),
                  Expanded(
                    child: TextField(
                      key: ValueKey('split-${t.wire}'),
                      controller: ctrls[t],
                      textAlign: TextAlign.right,
                      keyboardType: const TextInputType.numberWithOptions(decimal: true),
                      inputFormatters: [decimalInputFormatter],
                      decoration: const InputDecoration(isDense: true, hintText: '0'),
                      onChanged: (_) => setState(() {}),
                    ),
                  ),
                  const SizedBox(width: 6),
                  TextButton(
                    key: ValueKey('split-rest-${t.wire}'),
                    onPressed: total + 0.0001 < due ? () => fillRest(t) : null,
                    child: const Text('Rest'),
                  ),
                ],
              ),
            );

        Widget sum(String label, double v, {Color? color}) => Padding(
              padding: const EdgeInsets.symmetric(vertical: 2),
              child: Row(
                children: [
                  Expanded(
                    child: Text(label,
                        style: TextStyle(fontWeight: FontWeight.w700, color: color ?? AppColors.ink2)),
                  ),
                  const SizedBox(width: 8),
                  Text(formatAmount(v),
                      style: numStyle(weight: FontWeight.w800, color: color ?? AppColors.ink)),
                ],
              ),
            );

        return AlertDialog(
          shape: RoundedRectangleBorder(borderRadius: AppRadii.brLg),
          title: const Text('Split the bill'),
          content: SizedBox(
            width: 420,
            child: SingleChildScrollView(
              child: Column(
                mainAxisSize: MainAxisSize.min,
                crossAxisAlignment: CrossAxisAlignment.stretch,
                children: [
                  sum('Amount due ($currency)', due),
                  const SizedBox(height: 12),
                  for (final t in TenderType.values) row(t),
                  const Divider(),
                  sum('Entered', total),
                  if (total + 0.0001 < due)
                    sum('Remaining', due - total, color: AppColors.danger)
                  else if (change > 0.0001)
                    sum('Change (cash)', change, color: AppColors.payD),
                  if (problem != null && total > 0 && total + 0.0001 >= due) ...[
                    const SizedBox(height: 6),
                    Text(problem, style: const TextStyle(color: AppColors.danger, fontSize: 12.5)),
                  ],
                ],
              ),
            ),
          ),
          actions: [
            TextButton(onPressed: () => Navigator.pop(context), child: const Text('Cancel')),
            OrbixButton(
                label: 'Complete sale',
                icon: Icons.check,
                onPressed: problem == null ? done : null),
          ],
        );
      },
    ),
  );
}
