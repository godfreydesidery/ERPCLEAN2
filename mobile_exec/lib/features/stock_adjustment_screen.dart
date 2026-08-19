import 'package:flutter/material.dart';

import '../app/app_scope.dart';
import '../app/theme.dart';
import '../core/api/api_exception.dart';
import '../services/catalog_service.dart';
import '../services/stock_service.dart';
import '../widgets/async_view.dart';
import '../widgets/common.dart';
import '../widgets/kit.dart';
import 'product_picker.dart';

/// Stock adjustment — a signed correction with a reason, posted to
/// `/stock/adjustments`.
class StockAdjustmentScreen extends StatefulWidget {
  const StockAdjustmentScreen({super.key});

  @override
  State<StockAdjustmentScreen> createState() => _StockAdjustmentScreenState();
}

class _StockAdjustmentScreenState extends State<StockAdjustmentScreen> {
  ProductItem? _product;
  int _delta = 0;
  String? _reasonLabel;
  final _note = TextEditingController();
  bool _busy = false;

  @override
  void dispose() {
    _note.dispose();
    super.dispose();
  }

  bool get _ready =>
      _product != null && _delta != 0 && _reasonLabel != null && !_busy;

  String? get _reasonCode {
    for (final e in kAdjustReasons.entries) {
      if (e.value == _reasonLabel) return e.key;
    }
    return null;
  }

  Future<void> _pick() async {
    final p = await pickProduct(context);
    if (p != null) setState(() => _product = p);
  }

  Future<void> _submit() async {
    setState(() => _busy = true);
    try {
      await AppScope.of(context).stock.adjust(
            productUid: _product!.uid,
            quantity: _delta.toDouble(),
            reasonCode: _reasonCode!,
            note: _note.text.trim(),
          );
      if (!mounted) return;
      await showDoneSheet(
        context,
        title: 'Adjustment posted',
        detail: '${_product!.name}\n'
            '${_delta > 0 ? '+' : ''}$_delta ${_product!.unit} · $_reasonLabel',
      );
      if (mounted) Navigator.of(context).pop();
    } on ApiException catch (e) {
      if (mounted) _showError(e.message);
    } catch (_) {
      if (mounted) _showError('Could not post the adjustment.');
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  void _showError(String message) {
    ScaffoldMessenger.of(context).showSnackBar(
      SnackBar(
        behavior: SnackBarBehavior.floating,
        backgroundColor: HqColors.bad,
        content: Text(message),
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    final session = AppScope.of(context).session;

    return Scaffold(
      backgroundColor: HqColors.bg,
      appBar: AppBar(
        title: const Text('Stock adjustment', style: HqText.title),
      ),
      body: !session.can('STOCK.ADJUST')
          ? const NoPermission(code: 'STOCK.ADJUST')
          : ListView(
              padding: const EdgeInsets.fromLTRB(20, 8, 20, 28),
              children: [
                ProductPickerTile(product: _product, onTap: _pick),
                if (_product != null) ...[
                  const SizedBox(height: 20),
                  Text('Adjust by', style: HqText.label),
                  const SizedBox(height: 8),
                  QtyStepper(
                    value: _delta,
                    allowNegative: true,
                    unit: _product!.unit,
                    onChanged: (v) => setState(() => _delta = v),
                  ),
                  const SizedBox(height: 10),
                  Row(
                    children: [
                      for (final step in const [-10, -1, 1, 10]) ...[
                        Expanded(
                          child: OutlinedButton(
                            onPressed: () => setState(() => _delta += step),
                            style: OutlinedButton.styleFrom(
                              minimumSize: const Size.fromHeight(40),
                              padding: EdgeInsets.zero,
                              side: const BorderSide(color: HqColors.line2),
                            ),
                            child: Text(
                              step > 0 ? '+$step' : '$step',
                              style: const TextStyle(
                                fontSize: 14,
                                fontWeight: FontWeight.w700,
                              ),
                            ),
                          ),
                        ),
                        if (step != 10) const SizedBox(width: 8),
                      ],
                    ],
                  ),
                  const SizedBox(height: 18),
                  HqCard(
                    padding: const EdgeInsets.symmetric(
                        horizontal: 16, vertical: 12),
                    child: FigureRow(
                      label: 'Change to post',
                      value: '${_delta > 0 ? '+' : ''}$_delta '
                          '${_product!.unit}',
                      emphasise: true,
                      valueColor:
                          _delta < 0 ? HqColors.bad : HqColors.brand,
                    ),
                  ),
                  const SizedBox(height: 20),
                  HqDropdown(
                    label: 'Reason',
                    required: true,
                    items: kAdjustReasons.values.toList(),
                    value: _reasonLabel,
                    hint: 'Why is the quantity changing?',
                    onChanged: (v) => setState(() => _reasonLabel = v),
                  ),
                  const SizedBox(height: 18),
                  HqField(
                    label: 'Note',
                    controller: _note,
                    hint: 'Optional — anything the auditor should know',
                    maxLines: 3,
                  ),
                ],
                const SizedBox(height: 26),
                FilledButton(
                  onPressed: _ready ? _submit : null,
                  child: _busy
                      ? const SizedBox(
                          width: 20,
                          height: 20,
                          child: CircularProgressIndicator(
                            strokeWidth: 2.2,
                            color: Colors.white,
                          ),
                        )
                      : const Text('Post adjustment'),
                ),
              ],
            ),
    );
  }
}
