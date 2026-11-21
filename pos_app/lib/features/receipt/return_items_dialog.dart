import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../app/theme.dart';
import '../../core/api/api_client.dart';
import '../../core/api/api_exception.dart';
import '../../core/config/app_config.dart';
import '../../core/config/step_up_policy.dart';
import '../../core/money.dart';
import '../../models/sale.dart';
import '../../models/sale_return.dart';
import '../../services/receipt_printer.dart';
import '../../state/app_controller.dart';
import '../../state/providers.dart';
import '../../widgets/ui.dart';
import '../auth/approval_dialog.dart';
import 'receipt_view.dart' show companyReceiptLines;
import 'refund_slip_text.dart';

/// The cashier-facing text for a failed return call.
///
/// The server's `errors[]` are user-safe sentences (ERP ADR-0072 §1b.5) and are
/// what the cashier needs to read — "Only 2 of Widget can still be returned.",
/// "This refund needs a supervisor's approval…". [ApiException.message] already
/// carries them for 400/404/409; for a 403 it deliberately shows a generic line,
/// which here would hide the one thing to do next (fetch a supervisor), so the
/// server's sentence is preferred when it sent one.
String returnErrorText(ApiException e) {
  if (e.isForbidden) {
    for (final err in e.errors) {
      final m = err.message.trim();
      if (m.isNotEmpty) return m;
    }
  }
  return e.message;
}

/// A quantity as the cashier types it: whole numbers bare, fractions to at
/// most three places with trailing zeros dropped.
String formatReturnQty(double q) {
  if (q % 1 == 0) return q.toInt().toString();
  return q
      .toStringAsFixed(kReturnQtyDecimals)
      .replaceFirst(RegExp(r'0+$'), '')
      .replaceFirst(RegExp(r'\.$'), '');
}

/// Opens **Return items** for [invoice]. Resolves to the completed return, or
/// null when nothing was returned (cancelled, refused, or left unresolved).
Future<PosSaleReturn?> showReturnItemsDialog(
    BuildContext context, WidgetRef ref, {required SalesInvoice invoice}) {
  return showDialog<PosSaleReturn>(
    context: context,
    barrierDismissible: false,
    builder: (_) => ReturnItemsDialog(invoice: invoice),
  );
}

/// Prints the refund slip for [ret] on the till's receipt printer — the same
/// single print path ([ReceiptPrinter.printRaw]) the receipt uses, on Windows
/// and on Android Bluetooth alike. Never opens the drawer. Returns true when the
/// slip reached the printer.
Future<bool> printRefundSlip(BuildContext context, WidgetRef ref, PosSaleReturn ret,
    {bool copy = false}) async {
  final cfg = await AppConfig.load();
  final printer = cfg.receiptPrinterName;
  if (printer == null || printer.isEmpty) {
    if (context.mounted) {
      showToast(context, 'No receipt printer set — configure one in Setup.');
    }
    return false;
  }
  final app = ref.read(appControllerProvider);
  final company = app.context?.company;
  final bytes = buildRefundSlipBytes(
    ret: ret,
    companyName: company?.name ?? 'OrbixPOS',
    branchName: app.context?.branch.name ?? '',
    width: cfg.receiptWidthCols,
    mode: cfg.printMode,
    copy: copy,
    companyDetailLines: company == null ? const [] : companyReceiptLines(company),
  );
  try {
    await const ReceiptPrinter().printRaw(printer, bytes);
    if (context.mounted) showToast(context, 'Refund slip printed.', ok: true);
    return true;
  } on ReceiptPrinterException catch (e) {
    if (context.mounted) showToast(context, e.message);
  } catch (_) {
    if (context.mounted) showToast(context, 'Could not print the refund slip.');
  }
  return false;
}

/// The "Return items" dialog (ERP ADR-0072): pick lines and quantities, give a
/// reason, get a manager's approval, and refund the cash.
class ReturnItemsDialog extends ConsumerStatefulWidget {
  const ReturnItemsDialog({super.key, required this.invoice});
  final SalesInvoice invoice;

  @override
  ConsumerState<ReturnItemsDialog> createState() => _ReturnItemsDialogState();
}

class _ReturnItemsDialogState extends ConsumerState<ReturnItemsDialog> {
  PosSaleReturns? _data;
  bool _loading = true;
  String? _loadError;

  /// Ticked lines and the quantity to return, by invoice-line uid. A ticked
  /// line may briefly hold 0 while the cashier retypes its quantity; only
  /// positive quantities are sent.
  final Map<String, double> _sel = {};
  final Map<String, TextEditingController> _qtyCtrls = {};
  final _reason = TextEditingController();

  String? _formError;
  bool _busy = false;

  /// An attempt whose outcome is unknown (no answer, or a 5xx). It is resent
  /// EXACTLY as it was — same body, same Idempotency-Key — so if it did land the
  /// server answers with that return instead of refunding a second time.
  String? _pendingKey;
  Map<String, dynamic>? _pendingBody;

  PosSaleReturn? _done;
  bool _printing = false;

  SalesInvoice get inv => widget.invoice;

  @override
  void initState() {
    super.initState();
    _load();
  }

  @override
  void dispose() {
    for (final c in _qtyCtrls.values) {
      c.dispose();
    }
    _reason.dispose();
    super.dispose();
  }

  TextEditingController _ctrl(ReturnableLine l) =>
      _qtyCtrls.putIfAbsent(l.invoiceLineUid, TextEditingController.new);

  Future<void> _load() async {
    setState(() {
      _loading = _data == null;
      _loadError = null;
    });
    try {
      final d = await ref.read(saleServiceProvider).getReturns(inv.uid);
      if (!mounted) return;
      setState(() {
        _data = d;
        _loading = false;
        // Keep what the cashier picked, trimmed to what can still go back.
        final byUid = {for (final l in d.lines) l.invoiceLineUid: l};
        _sel.removeWhere((k, _) => byUid[k] == null || byUid[k]!.fullyReturned);
        for (final e in _sel.entries.toList()) {
          final l = byUid[e.key]!;
          final q = clampReturnQty(l, e.value);
          _sel[e.key] = q;
          _ctrl(l).text = formatReturnQty(q);
        }
      });
    } on ApiException catch (e) {
      if (!mounted) return;
      setState(() {
        _loading = false;
        _loadError = returnErrorText(e);
      });
    }
  }

  void _toggle(ReturnableLine l, bool on) {
    setState(() {
      _formError = null;
      if (on) {
        final q = clampReturnQty(l, l.qtyRemaining);
        _sel[l.invoiceLineUid] = q;
        _ctrl(l).text = formatReturnQty(q);
      } else {
        _sel.remove(l.invoiceLineUid);
      }
    });
  }

  void _step(ReturnableLine l, double delta) {
    final cur = _sel[l.invoiceLineUid] ?? 0;
    final next = clampReturnQty(l, cur + delta);
    // The minus button stops at the smallest quantity; untick to return none.
    if (next <= 0) return;
    setState(() {
      _formError = null;
      _sel[l.invoiceLineUid] = next;
      _ctrl(l).text = formatReturnQty(next);
    });
  }

  void _typed(ReturnableLine l, String text) {
    final v = double.tryParse(text.trim()) ?? 0;
    final q = clampReturnQty(l, v);
    setState(() {
      _formError = null;
      _sel[l.invoiceLineUid] = q;
      if (v > l.qtyRemaining) {
        // More than can go back: snap to the most that can.
        final c = _ctrl(l);
        c.text = formatReturnQty(q);
        c.selection = TextSelection.collapsed(offset: c.text.length);
      }
    });
  }

  Future<void> _submit() async {
    final d = _data;
    if (d == null || _busy) return;
    final reason = _reason.text.trim();
    final chosen =
        d.lines.where((l) => (_sel[l.invoiceLineUid] ?? 0) > 0).toList();
    if (chosen.isEmpty) {
      setState(() => _formError =
          'Tick the items coming back and enter how many of each.');
      return;
    }
    if (reason.length < 3) {
      setState(() => _formError = 'Enter the reason for the return.');
      return;
    }
    if (reason.length > 255) {
      setState(() => _formError = 'Keep the reason under 255 characters.');
      return;
    }
    final estimate = estimateSelectionGross(d.lines, _sel);
    final amount = formatMoneyParts(estimate, inv.currency);

    // A supervisor at the till IS the approval the step-up looks for (as on
    // Refund / reverse); the server reaches the same conclusion on its own.
    final selfAuthorised = ref
        .read(appControllerProvider)
        .can(stepUpRuleFor(GatedAction.saleReturn).permissionCode);
    String? approvedByUid;
    if (selfAuthorised) {
      if (await _confirm(chosen.length, amount) != true) return;
    } else {
      final outcome = await approveIfRequired(
        context,
        ref,
        action: GatedAction.saleReturn,
        detail: 'Receipt ${inv.invoiceNumber} — return ${chosen.length} '
            '${chosen.length == 1 ? 'item' : 'items'}, about $amount in cash',
        correlationId: inv.uid,
      );
      if (!outcome.allowed) {
        if (mounted) showToast(context, 'Not approved — nothing was returned.');
        return;
      }
      approvedByUid = outcome.approval?.authoriserUid;
    }
    if (!mounted) return;
    final body = buildReturnRequest(
        reason: reason, authorisedByUid: approvedByUid, lines: d.lines, selected: _sel);
    await _send(body, ApiClient.newTxnId());
  }

  Future<bool?> _confirm(int count, String amount) => showDialog<bool>(
        context: context,
        builder: (ctx) => AlertDialog(
          shape: RoundedRectangleBorder(borderRadius: AppRadii.brLg),
          title: const Text('Return these items?'),
          content: Text('Return $count ${count == 1 ? 'item' : 'items'} and give '
              'about $amount back in cash from the drawer.'),
          actions: [
            TextButton(
                onPressed: () => Navigator.pop(ctx, false),
                child: const Text('Cancel')),
            OrbixButton(
                label: 'Return & refund',
                onPressed: () => Navigator.pop(ctx, true)),
          ],
        ),
      );

  Future<void> _send(Map<String, dynamic> body, String key) async {
    final retry = _pendingKey == key;
    setState(() {
      _busy = true;
      _formError = null;
    });
    try {
      final ret = await ref
          .read(saleServiceProvider)
          .returnItems(inv.uid, body, idempotencyKey: key);
      if (!mounted) return;
      setState(() {
        _busy = false;
        _pendingKey = null;
        _pendingBody = null;
        _done = ret;
      });
      unawaited(_printSlip(ret));
    } on ApiException catch (e) {
      if (!mounted) return;
      // No answer, or a server error after the write may have committed: the
      // refund may or may not exist. Hold the attempt and offer Retry with the
      // same key — never a fresh key, which could refund twice. A refusal while
      // retrying (e.g. "still in progress") keeps the hold too.
      if (e.isAmbiguousWrite || retry) {
        setState(() {
          _busy = false;
          _pendingKey = key;
          _pendingBody = body;
          _formError = e.isAmbiguousWrite ? null : returnErrorText(e);
        });
        return;
      }
      setState(() {
        _busy = false;
        _formError = returnErrorText(e);
      });
      // Someone else may have returned items meanwhile: show the fresh counts.
      if (e.isConflict) unawaited(_load());
    }
  }

  Future<void> _printSlip(PosSaleReturn ret, {bool copy = false}) async {
    if (_printing) return;
    setState(() => _printing = true);
    try {
      await printRefundSlip(context, ref, ret, copy: copy);
    } finally {
      if (mounted) setState(() => _printing = false);
    }
  }

  void _close() => Navigator.pop(context, _done);

  // ------------------------------------------------------------------ build

  @override
  Widget build(BuildContext context) {
    return Dialog(
      shape: RoundedRectangleBorder(borderRadius: AppRadii.brLg),
      child: ConstrainedBox(
        constraints: const BoxConstraints(maxWidth: 520, maxHeight: 720),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            Padding(
              padding: const EdgeInsets.fromLTRB(18, 14, 8, 12),
              child: Row(
                children: [
                  const Icon(Icons.assignment_return_outlined,
                      color: AppColors.brand),
                  const SizedBox(width: 10),
                  Expanded(
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Text(_done == null ? 'Return items' : 'Items returned',
                            style: const TextStyle(
                                fontSize: 18, fontWeight: FontWeight.w700)),
                        Text('Receipt ${inv.invoiceNumber}',
                            style: const TextStyle(
                                fontSize: 13, color: AppColors.ink2)),
                      ],
                    ),
                  ),
                  IconButton(
                      tooltip: 'Close',
                      onPressed: _busy ? null : _close,
                      icon: const Icon(Icons.close)),
                ],
              ),
            ),
            const Divider(height: 1),
            Flexible(
              child: SingleChildScrollView(
                padding: const EdgeInsets.fromLTRB(16, 12, 16, 12),
                child: _body(),
              ),
            ),
            const Divider(height: 1),
            Padding(
              padding: const EdgeInsets.all(12),
              child: _footer(),
            ),
          ],
        ),
      ),
    );
  }

  Widget _body() {
    if (_done != null) return _donePanel(_done!);
    if (_pendingKey != null) return _pendingPanel();
    if (_loading) {
      return const Padding(
          padding: EdgeInsets.all(40),
          child: Center(child: CircularProgressIndicator()));
    }
    if (_loadError != null) return _message(Icons.error_outline, _loadError!);
    final d = _data!;
    if (!d.returnable) {
      return _message(
          Icons.info_outline,
          (d.returnBlockedReason?.trim().isNotEmpty ?? false)
              ? d.returnBlockedReason!.trim()
              : 'Items on this sale cannot be returned at the till.');
    }
    return _form(d);
  }

  Widget _message(IconData icon, String text) => Padding(
        padding: const EdgeInsets.symmetric(vertical: 24, horizontal: 8),
        child: Row(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Icon(icon, color: AppColors.warn),
            const SizedBox(width: 10),
            Expanded(child: Text(text, style: const TextStyle(fontSize: 15))),
          ],
        ),
      );

  Widget _form(PosSaleReturns d) {
    final estimate = estimateSelectionGross(d.lines, _sel);
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        if (d.hasReturns && d.returnedGross > 0)
          Padding(
            padding: const EdgeInsets.only(bottom: 8),
            child: Text(
                'Already returned from this sale: '
                '${formatMoneyParts(d.returnedGross, inv.currency)}',
                style: const TextStyle(fontSize: 13, color: AppColors.ink2)),
          ),
        const Text('Tick the items coming back and how many of each.',
            style: TextStyle(fontSize: 13, color: AppColors.ink2)),
        const SizedBox(height: 4),
        for (final l in d.lines) ...[
          _lineTile(l),
          const Divider(height: 1),
        ],
        const SizedBox(height: 12),
        OrbixField(
            label: 'Reason',
            hint: 'e.g. damaged bottle',
            controller: _reason,
            enabled: !_busy,
            onChanged: (_) {
              if (_formError != null) setState(() => _formError = null);
            }),
        const SizedBox(height: 14),
        Container(
          padding: const EdgeInsets.all(12),
          decoration: BoxDecoration(
            color: AppColors.panel2,
            borderRadius: AppRadii.brSm,
            border: Border.all(color: AppColors.line),
          ),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              Row(
                children: [
                  const Expanded(
                    child: Text('Estimated cash refund',
                        style: TextStyle(fontWeight: FontWeight.w600)),
                  ),
                  Flexible(
                    child: NumText(formatMoneyParts(estimate, inv.currency),
                        key: const Key('return-estimate'),
                        style: numStyle(size: 17, weight: FontWeight.w800)),
                  ),
                ],
              ),
              const SizedBox(height: 4),
              const Text(
                  'An estimate. The ERP works out the exact amount, and that is '
                  'what you give back.',
                  style: TextStyle(fontSize: 12, color: AppColors.ink2)),
            ],
          ),
        ),
        if (_formError != null)
          Padding(
            padding: const EdgeInsets.only(top: 10),
            child: Text(_formError!,
                style: const TextStyle(
                    color: AppColors.danger, fontWeight: FontWeight.w600)),
          ),
      ],
    );
  }

  Widget _lineTile(ReturnableLine l) {
    final on = _sel.containsKey(l.invoiceLineUid);
    final q = _sel[l.invoiceLineUid] ?? 0;
    final unit = (l.unitName ?? '').trim();
    final stats = 'Sold ${formatReturnQty(l.qtySold)}'
        '${unit.isEmpty ? '' : ' $unit'}'
        ' · returned ${formatReturnQty(l.qtyReturned)}'
        ' · left ${formatReturnQty(l.qtyRemaining)}';
    final full = l.fullyReturned;
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 4),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          Row(
            children: [
              Checkbox(
                key: Key('return-line-${l.invoiceLineUid}'),
                value: on,
                onChanged: full || _busy ? null : (v) => _toggle(l, v ?? false),
              ),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(l.productName,
                        style: TextStyle(
                            fontWeight: FontWeight.w600,
                            color: full ? AppColors.ink3 : AppColors.ink)),
                    Text(full ? 'Fully returned' : stats,
                        style: const TextStyle(
                            fontSize: 12.5, color: AppColors.ink2)),
                  ],
                ),
              ),
              if (on && q > 0) ...[
                const SizedBox(width: 8),
                Text(formatAmount(estimateReturnGross(l, q)),
                    style: numStyle()),
              ],
            ],
          ),
          if (on)
            Row(
              mainAxisAlignment: MainAxisAlignment.end,
              children: [
                const Text('Return',
                    style: TextStyle(fontSize: 13, color: AppColors.ink2)),
                const SizedBox(width: 4),
                IconButton(
                  tooltip: 'One less',
                  visualDensity: VisualDensity.compact,
                  onPressed: _busy || q <= 1 ? null : () => _step(l, -1),
                  icon: const Icon(Icons.remove_circle_outline),
                ),
                SizedBox(
                  width: 72,
                  child: TextField(
                    key: Key('return-qty-${l.invoiceLineUid}'),
                    controller: _ctrl(l),
                    enabled: !_busy,
                    textAlign: TextAlign.center,
                    keyboardType: l.allowsDecimals
                        ? const TextInputType.numberWithOptions(decimal: true)
                        : TextInputType.number,
                    inputFormatters: [
                      l.allowsDecimals
                          ? decimalInputFormatter
                          : FilteringTextInputFormatter.digitsOnly,
                    ],
                    decoration: const InputDecoration(
                        isDense: true,
                        contentPadding:
                            EdgeInsets.symmetric(horizontal: 6, vertical: 10)),
                    onChanged: (t) => _typed(l, t),
                  ),
                ),
                IconButton(
                  tooltip: 'One more',
                  visualDensity: VisualDensity.compact,
                  onPressed:
                      _busy || q >= l.qtyRemaining ? null : () => _step(l, 1),
                  icon: const Icon(Icons.add_circle_outline),
                ),
              ],
            ),
        ],
      ),
    );
  }

  Widget _pendingPanel() => Padding(
        padding: const EdgeInsets.symmetric(vertical: 16, horizontal: 4),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            const Row(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Icon(Icons.sync_problem, color: AppColors.warn),
                SizedBox(width: 10),
                Expanded(
                  child: Text(
                      "The ERP did not answer, so we can't tell whether the refund "
                      'went through. Do not hand over any cash yet.\n\n'
                      'Press Retry: the same request is sent again, and the ERP '
                      'will not refund it twice.',
                      style: TextStyle(fontSize: 15)),
                ),
              ],
            ),
            if (_formError != null)
              Padding(
                padding: const EdgeInsets.only(top: 12),
                child: Text(_formError!,
                    style: const TextStyle(
                        color: AppColors.danger, fontWeight: FontWeight.w600)),
              ),
          ],
        ),
      );

  Widget _donePanel(PosSaleReturn ret) {
    final approver = ret.authorisedBy?.trim() ?? '';
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        Container(
          padding: const EdgeInsets.symmetric(vertical: 18, horizontal: 12),
          decoration: BoxDecoration(
            color: AppColors.okSoft,
            borderRadius: AppRadii.brLg,
            border: Border.all(color: const Color(0xFFBBF7D0)),
          ),
          child: Column(
            children: [
              const Icon(Icons.payments_outlined, color: AppColors.ok, size: 34),
              const SizedBox(height: 6),
              const Text('Give the customer',
                  style: TextStyle(fontSize: 16, fontWeight: FontWeight.w600)),
              const SizedBox(height: 4),
              NumText(formatMoneyParts(ret.grossAmount, ret.currency),
                  key: const Key('return-cash-due'),
                  alignment: Alignment.center,
                  style: numStyle(
                      size: 32, weight: FontWeight.w800, color: AppColors.ok)),
              const Text('in cash from the drawer',
                  style: TextStyle(fontSize: 15, fontWeight: FontWeight.w600)),
            ],
          ),
        ),
        const SizedBox(height: 12),
        Text(
            'Refund ${ret.returnNo}'
            '${approver.isEmpty ? '' : ' · approved by $approver'}',
            style: const TextStyle(fontWeight: FontWeight.w600)),
        const SizedBox(height: 6),
        for (final l in ret.lines)
          Padding(
            padding: const EdgeInsets.symmetric(vertical: 2),
            child: Row(
              children: [
                Expanded(
                    child: Text(
                        '${formatReturnQty(l.qtyReturned)} × ${l.productName}')),
                Text(formatAmount(l.grossAmount), style: numStyle()),
              ],
            ),
          ),
      ],
    );
  }

  Widget _footer() {
    if (_done != null) {
      return Row(
        children: [
          Expanded(
            child: OrbixButton(
                label: 'Print slip',
                icon: Icons.print_outlined,
                kind: BtnKind.ghost,
                busy: _printing,
                onPressed: () => _printSlip(_done!, copy: true)),
          ),
          const SizedBox(width: 8),
          Expanded(child: OrbixButton(label: 'Done', onPressed: _close)),
        ],
      );
    }
    if (_pendingKey != null) {
      return Row(
        children: [
          Expanded(
            child: OrbixButton(
                label: 'Close',
                kind: BtnKind.ghost,
                onPressed: _busy ? null : _close),
          ),
          const SizedBox(width: 8),
          Expanded(
            child: OrbixButton(
                label: 'Retry',
                icon: Icons.refresh,
                busy: _busy,
                onPressed: () => _send(_pendingBody!, _pendingKey!)),
          ),
        ],
      );
    }
    final d = _data;
    final canSubmit = d != null &&
        d.returnable &&
        _loadError == null &&
        _sel.values.any((q) => q > 0);
    return Row(
      children: [
        Expanded(
          child: OrbixButton(
              label: 'Cancel',
              kind: BtnKind.ghost,
              onPressed: _busy ? null : _close),
        ),
        if (d != null && d.returnable) ...[
          const SizedBox(width: 8),
          Expanded(
            child: OrbixButton(
                label: 'Return & refund',
                icon: Icons.assignment_return_outlined,
                kind: BtnKind.danger,
                busy: _busy,
                onPressed: canSubmit ? _submit : null),
          ),
        ],
      ],
    );
  }
}
