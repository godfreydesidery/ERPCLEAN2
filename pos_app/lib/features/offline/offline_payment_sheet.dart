import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:intl/intl.dart';

import '../../app/theme.dart';
import '../../core/money.dart';
import '../../models/enums.dart';
import '../../models/sale.dart';
import '../../offline/offline_controller.dart';
import '../../offline/offline_models.dart';
import '../../offline/offline_sale.dart';
import '../../offline/secret_cipher.dart';
import '../../state/basket_pricer.dart';
import '../../state/cart_controller.dart';
import '../../state/pending_sale_store.dart';
import '../../state/price_cache.dart';
import '../../state/receipt_journal.dart';
import '../../widgets/ui.dart';
import '../payment/payment_sheet.dart' show tenderIcon;
import '../receipt/receipt_view.dart';

/// Takes payment for the basket while the till is offline (ERP ADR-0068 D-3).
///
/// Cash, mobile money and card only — mobile money and card each with their
/// reference; no discounts, complimentary, credit or named customers. The sale
/// is written to the encrypted on-device queue FIRST; only then does the
/// receipt (marked OFFLINE, numbered `OFF-…`) come up for printing.
///
/// [reuseKey] is the Idempotency-Key of an online attempt that got no answer:
/// the offline sale carries the same key, so if that attempt did reach the
/// server the sync answers ALREADY_POSTED rather than booking it twice.
Future<void> openOfflinePaymentSheet(BuildContext context, WidgetRef ref,
    {String? reuseKey}) async {
  final ctrl = ref.read(offlineControllerProvider.notifier);
  final cart = ref.read(cartProvider);
  final problem = ctrl.sellingProblem() ?? offlineBasketProblem(cart);
  if (problem != null) {
    final walkInFix = cart.customer != null && !cart.customer!.isWalkIn;
    await showDialog<void>(
      context: context,
      builder: (dialogContext) => AlertDialog(
        shape: RoundedRectangleBorder(borderRadius: AppRadii.brLg),
        title: const Row(children: [
          Icon(Icons.cloud_off, color: AppColors.danger),
          SizedBox(width: 10),
          Text('Cannot sell this offline'),
        ]),
        content: Text(problem),
        actions: [
          if (walkInFix)
            TextButton(
              onPressed: () {
                ref.read(cartProvider.notifier).resetCustomer();
                ref.read(basketPricerProvider).price();
                Navigator.pop(dialogContext);
              },
              child: const Text('Switch to walk-in'),
            ),
          OrbixButton(label: 'OK', onPressed: () => Navigator.pop(dialogContext)),
        ],
      ),
    );
    return;
  }

  // An online sale that never got its answer is still on the device. It is
  // checked when the server is back; meanwhile it must not be rung again.
  final pendingStore = ref.read(pendingSaleStoreProvider);
  final pending = await pendingStore.read();
  if (pending != null && pending.txnId != reuseKey) {
    if (!context.mounted) return;
    final go = await showDialog<bool>(
      context: context,
      builder: (dialogContext) => AlertDialog(
        shape: RoundedRectangleBorder(borderRadius: AppRadii.brLg),
        title: const Text('An earlier sale is still unconfirmed'),
        content: Text(
            'A sale of ${formatMoneyParts(pending.amount, pending.currency)} at '
            '${DateFormat('HH:mm').format(pending.startedAt.toLocal())} got no answer from the '
            'server before the connection dropped. It is checked when the server is back.\n\n'
            'If the customer already paid for it, do not ring it again.'),
        actions: [
          TextButton(
              onPressed: () => Navigator.pop(dialogContext, false),
              child: const Text('Back')),
          OrbixButton(
              label: 'This is a new sale',
              onPressed: () => Navigator.pop(dialogContext, true)),
        ],
      ),
    );
    if (go != true) return;
  }
  if (!context.mounted) return;

  ctrl.paymentOpened();
  Receipt? receipt;
  try {
    receipt = await showDialog<Receipt>(
      context: context,
      barrierDismissible: false,
      builder: (_) => _OfflinePaymentSheet(reuseKey: reuseKey),
    );
    if (receipt != null) {
      ref.read(cartProvider.notifier).clearLines();
      ref.read(priceCacheProvider).clear();
      await ref.read(receiptJournalProvider).add(receipt);
      // The online attempt this sale finished now lives in the offline queue
      // under the same key; its unfinished-sale slot is settled.
      if (reuseKey != null && (await pendingStore.read())?.txnId == reuseKey) {
        await pendingStore.clear();
      }
    }
  } finally {
    ctrl.paymentClosed();
  }
  if (receipt != null && context.mounted) await showReceiptSheet(context, ref, receipt);
}

class _OfflinePaymentSheet extends ConsumerStatefulWidget {
  const _OfflinePaymentSheet({this.reuseKey});
  final String? reuseKey;
  @override
  ConsumerState<_OfflinePaymentSheet> createState() => _OfflinePaymentSheetState();
}

class _OfflinePaymentSheetState extends ConsumerState<_OfflinePaymentSheet> {
  final _amount = TextEditingController();
  final _reference = TextEditingController();
  final List<OfflineTender> _tenders = [];
  TenderType _type = TenderType.cash;
  bool _busy = false;
  String? _problem;

  @override
  void dispose() {
    _amount.dispose();
    _reference.dispose();
    super.dispose();
  }

  int get _minor => minorUnitsFor(ref.read(cartProvider).currency);
  double get _due => offlineGross(offlineLinesFrom(ref.read(cartProvider)), _minor);
  double get _paid => _tenders.fold(0.0, (s, t) => s + t.amount);
  double get _left => (_due - _paid).clamp(0, double.infinity);
  double get _typed => double.tryParse(_amount.text.trim()) ?? 0;

  void _set(String v) {
    _amount.value = TextEditingValue(text: v, selection: TextSelection.collapsed(offset: v.length));
    setState(() {});
  }

  OfflineTender? _boxTender({bool fillRemaining = false}) {
    final amt = _typed > 0 ? _typed : (fillRemaining ? _left : 0.0);
    if (amt <= 0) return null;
    final ref_ = _reference.text.trim();
    return OfflineTender(type: _type, amount: amt, reference: ref_.isEmpty ? null : ref_);
  }

  void _addTender() {
    final t = _boxTender(fillRemaining: true);
    if (t == null) return;
    if (offlineTenderNeedsReference(t.type) && t.reference == null) {
      setState(() => _problem = 'Enter the ${offlineReferenceLabel(t.type)}.');
      return;
    }
    setState(() {
      _tenders.add(t);
      _problem = null;
      _reference.clear();
    });
    final left = _left;
    _set(left > 0 ? left.toStringAsFixed(_minor) : '');
  }

  Future<bool> _confirmAge() async {
    final ok = await showDialog<bool>(
      context: context,
      builder: (dialogContext) => AlertDialog(
        shape: RoundedRectangleBorder(borderRadius: AppRadii.brLg),
        title: const Row(children: [
          Icon(Icons.shield_outlined, color: AppColors.warn),
          SizedBox(width: 10),
          Text('Age-restricted items'),
        ]),
        content: const Text('Confirm you have checked the customer meets the minimum age. '
            'Offline, the age check cannot be overridden.'),
        actions: [
          TextButton(
              onPressed: () => Navigator.pop(dialogContext, false),
              child: const Text('Cancel')),
          OrbixButton(label: 'Age verified', onPressed: () => Navigator.pop(dialogContext, true)),
        ],
      ),
    );
    return ok == true;
  }

  Future<void> _complete() async {
    if (_busy) return;
    final due = _due;
    // The amount still in the box is the last part (or the whole payment).
    final tenders = [..._tenders];
    final last = _boxTender(fillRemaining: _tenders.isEmpty);
    if (last != null) tenders.add(last);
    final problem = offlineTenderProblem(due, tenders);
    if (problem != null) {
      setState(() => _problem = problem);
      return;
    }
    final cart = ref.read(cartProvider);
    var ageVerified = false;
    if (cart.hasRestricted) {
      ageVerified = await _confirmAge();
      if (!mounted) return;
      if (!ageVerified) {
        showToast(context, 'Sale stopped: age not verified.');
        return;
      }
    }
    setState(() {
      _busy = true;
      _problem = null;
    });
    try {
      // Durable FIRST: the sale is sealed and on disk before any receipt exists.
      final sale = await ref.read(offlineControllerProvider.notifier).recordSale(
            cart: cart,
            tendered: tenders,
            ageVerified: ageVerified,
            idempotencyKey: widget.reuseKey,
          );
      final receipt = offlineReceipt(sale, tendered: tenders, minorUnits: _minor);
      if (!mounted) return;
      Navigator.of(context).pop(receipt);
    } on OfflineSaleRefused catch (e) {
      if (mounted) setState(() => _problem = e.message);
    } on SecretCipherException catch (e) {
      if (mounted) setState(() => _problem = e.message);
    } catch (_) {
      if (mounted) {
        setState(() => _problem =
            'The sale could not be stored on this till, so it was not recorded. Try again.');
      }
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final cart = ref.watch(cartProvider);
    final due = _due;
    final change = offlineChange(due, [..._tenders, ?_boxTender()]);
    final needsRef = offlineTenderNeedsReference(_type);
    return PopScope(
      canPop: !_busy,
      child: Dialog(
        shape: RoundedRectangleBorder(borderRadius: AppRadii.brLg),
        insetPadding: const EdgeInsets.all(12),
        child: ConstrainedBox(
          constraints: const BoxConstraints(maxWidth: 520),
          child: SingleChildScrollView(
            padding: const EdgeInsets.all(18),
            child: Column(
              mainAxisSize: MainAxisSize.min,
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                Row(
                  children: [
                    const Icon(Icons.cloud_off, color: AppColors.danger),
                    const SizedBox(width: 8),
                    const Expanded(
                      child: Text('Payment — OFFLINE',
                          style: TextStyle(fontSize: 18, fontWeight: FontWeight.w700)),
                    ),
                    IconButton(
                      onPressed: _busy ? null : () => Navigator.pop(context),
                      icon: const Icon(Icons.close),
                    ),
                  ],
                ),
                const Text(
                    'Kept on this till and sent to the ERP when the server is back. '
                    'The receipt is marked OFFLINE.',
                    style: TextStyle(color: AppColors.ink2, fontSize: 12.5)),
                const SizedBox(height: 12),
                Container(
                  padding: const EdgeInsets.all(14),
                  decoration:
                      BoxDecoration(color: AppColors.brandSoft, borderRadius: AppRadii.brLg),
                  child: Column(children: [
                    Text('AMOUNT DUE (${cart.currency})',
                        style: const TextStyle(
                            fontSize: 12, fontWeight: FontWeight.w600, color: AppColors.brandD)),
                    NumText(formatAmount(due),
                        alignment: Alignment.center,
                        style: const TextStyle(
                            fontSize: 30,
                            fontWeight: FontWeight.w800,
                            color: AppColors.brandD,
                            fontFeatures: kTabular)),
                  ]),
                ),
                const SizedBox(height: 12),
                Row(
                  children: [
                    for (final t in kOfflineTenders)
                      Expanded(
                        child: Padding(
                          padding: const EdgeInsets.only(right: 6),
                          child: ChoiceChip(
                            key: ValueKey('offline-tender-${t.wire}'),
                            avatar: Icon(tenderIcon(t), size: 16),
                            label: Text(t.label),
                            selected: _type == t,
                            onSelected: _busy ? null : (_) => setState(() => _type = t),
                          ),
                        ),
                      ),
                  ],
                ),
                const SizedBox(height: 10),
                OrbixField(
                  label: 'Amount',
                  controller: _amount,
                  autofocus: true,
                  big: true,
                  enabled: !_busy,
                  keyboardType: const TextInputType.numberWithOptions(decimal: true),
                  inputFormatters: [decimalInputFormatter],
                  onChanged: (_) => setState(() {}),
                ),
                if (needsRef) ...[
                  const SizedBox(height: 8),
                  OrbixField(
                    key: const ValueKey('offline-reference'),
                    label: _type == TenderType.card
                        ? 'Card approval number (required)'
                        : 'Mobile money reference (required)',
                    controller: _reference,
                    enabled: !_busy,
                    onChanged: (_) => setState(() {}),
                  ),
                ],
                const SizedBox(height: 8),
                Wrap(
                  spacing: 6,
                  runSpacing: 6,
                  children: [
                    for (final p in _presets(_left > 0 ? _left : due))
                      OutlinedButton(
                        onPressed: _busy ? null : () => _set(p.toStringAsFixed(_minor)),
                        child: Text(p == (_left > 0 ? _left : due)
                            ? 'Exact'
                            : formatAmount(p, decimals: 0)),
                      ),
                  ],
                ),
                if (_tenders.isNotEmpty) ...[
                  const SizedBox(height: 10),
                  for (var i = 0; i < _tenders.length; i++)
                    ListTile(
                      dense: true,
                      contentPadding: EdgeInsets.zero,
                      leading: Icon(tenderIcon(_tenders[i].type), size: 18),
                      title: Text('${_tenders[i].type.label} ${formatAmount(_tenders[i].amount)}'),
                      subtitle: _tenders[i].reference == null
                          ? null
                          : Text('Ref ${_tenders[i].reference}'),
                      trailing: IconButton(
                        icon: const Icon(Icons.close, size: 16),
                        onPressed: _busy ? null : () => setState(() => _tenders.removeAt(i)),
                      ),
                    ),
                ],
                const Divider(),
                _sum('Total', due, bold: true),
                _sum('Paid', _paid + (_boxTender()?.amount ?? 0)),
                if (change > 0) _sum('Change (cash)', change, color: AppColors.payD),
                if (_problem != null) ...[
                  const SizedBox(height: 8),
                  Container(
                    padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 10),
                    decoration: BoxDecoration(
                      color: AppColors.dangerSoft,
                      borderRadius: AppRadii.brSm,
                      border: Border.all(color: const Color(0xFFFECACA)),
                    ),
                    child: Text(_problem!,
                        style: const TextStyle(
                            color: AppColors.danger, fontSize: 12.5, fontWeight: FontWeight.w600)),
                  ),
                ],
                const SizedBox(height: 12),
                OrbixButton(
                  label: 'Add payment (split)',
                  icon: Icons.add,
                  kind: BtnKind.ghost,
                  block: true,
                  onPressed: _busy ? null : _addTender,
                ),
                const SizedBox(height: 8),
                PayButton(
                  label: _busy ? 'Saving the sale…' : 'Complete offline sale',
                  amount: formatAmount(due),
                  busy: _busy,
                  onPressed: _busy ? null : _complete,
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }

  Widget _sum(String label, double v, {bool bold = false, Color? color}) => Padding(
        padding: const EdgeInsets.symmetric(vertical: 2),
        child: Row(
          children: [
            Expanded(
              child: Text(label,
                  style: TextStyle(
                      fontWeight: bold ? FontWeight.w800 : FontWeight.w600,
                      color: color ?? AppColors.ink2)),
            ),
            Text(formatAmount(v),
                style: numStyle(weight: bold ? FontWeight.w800 : FontWeight.w700, color: color ?? AppColors.ink)),
          ],
        ),
      );

  List<double> _presets(double amount) {
    final out = <double>{amount};
    for (final step in [1000, 5000, 10000]) {
      out.add((amount / step).ceil() * step.toDouble());
    }
    return (out.toList()..sort()).take(4).toList();
  }
}
