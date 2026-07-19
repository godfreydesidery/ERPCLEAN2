import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../app/theme.dart';
import '../../core/api/api_client.dart';
import '../../core/api/api_exception.dart';
import '../../core/money.dart';
import '../../models/auth.dart';
import '../../models/enums.dart';
import '../../models/sale.dart';
import '../../state/app_controller.dart';
import '../../state/cart_controller.dart';
import '../../state/providers.dart';
import '../../state/receipt_journal.dart';
import '../../widgets/ui.dart';
import '../receipt/receipt_view.dart';

/// Opens the payment modal. On a completed sale it clears the basket lines and
/// shows the receipt.
Future<void> openPaymentSheet(BuildContext context, WidgetRef ref) async {
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
  /// Durable client transaction id for THIS logical sale — used as the
  /// Idempotency-Key and X-Request-Id, and reused verbatim on every retry
  /// (PRIN-4/PRIN-7) so an ambiguous attempt cannot double-post.
  final String _txnId = ApiClient.newTxnId();

  final List<PosTender> _tenders = [];
  TenderType _type = TenderType.cash;
  // The amount box is a real editable field: the hardware keyboard types into it
  // AND the on-screen keypad / quick-cash presets drive the same controller.
  final TextEditingController _amountCtrl = TextEditingController();
  final FocusNode _amountFocus = FocusNode();
  bool _busy = false;
  bool _ambiguous = false;

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
    );

    setState(() {
      _busy = true;
      _ambiguous = false;
    });
    try {
      final sale = ref.read(saleServiceProvider);
      final invoice = await sale.ring(body,
          idempotencyKey: _txnId, xRequestId: _txnId);
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
      if (!mounted) return;
      Navigator.of(context).pop(receipt);
    } on ApiException catch (e) {
      setState(() {
        _busy = false;
        _ambiguous = e.isAmbiguousWrite;
      });
      if (!e.isAmbiguousWrite && mounted) showToast(context, e.message);
    }
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
    return Dialog(
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
      child: const Text(
        'The outcome was unknown (network/timeout). Press Complete again — the '
        'same idempotency key returns the original sale if it went through.',
        style: TextStyle(
            color: AppColors.warn, fontSize: 12.5, fontWeight: FontWeight.w600),
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
              label: _ambiguous ? 'RETRY (same key)' : 'Complete sale',
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
