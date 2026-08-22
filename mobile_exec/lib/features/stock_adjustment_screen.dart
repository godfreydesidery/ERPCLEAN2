import 'package:flutter/material.dart';

import '../app/app_scope.dart';
import '../app/format.dart';
import '../app/theme.dart';
import '../core/api/api_exception.dart';
import '../services/catalog_service.dart';
import '../services/stock_service.dart';
import '../widgets/async_view.dart';
import '../widgets/common.dart';
import '../widgets/kit.dart';
import 'product_picker.dart';
import 'unit_picker.dart';

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

  /// The units this product can be counted in, and the one being typed in.
  /// The server takes base units only, so the figure entered here is converted
  /// on the way out — the owner counts cartons, the ledger records pieces.
  List<TxUnit> _units = const [];
  TxUnit? _unit;

  @override
  void dispose() {
    _note.dispose();
    super.dispose();
  }

  bool get _ready =>
      _product != null &&
      _delta != 0 &&
      _reasonLabel != null &&
      _unit != null &&
      !_busy;

  /// The signed change in BASE units — what actually gets posted.
  double get _deltaInBase => (_unit?.factor ?? 1) * _delta;

  String? get _reasonCode {
    for (final e in kAdjustReasons.entries) {
      if (e.value == _reasonLabel) return e.key;
    }
    return null;
  }

  Future<void> _pick() async {
    final p = await pickProduct(context);
    if (p == null || !mounted) return;

    // Units are loaded before the product is accepted, so the screen can never
    // show a stepper whose unit is a guess.
    try {
      final units = await AppScope.of(context).catalog.transactionUnits(p);
      if (!mounted) return;
      setState(() {
        _product = p;
        _units = units;
        _unit = units.first;
        _delta = 0;
      });
    } on ApiException catch (e) {
      if (mounted) _showError(e.message);
    } catch (_) {
      if (mounted) _showError('Could not load the units for ${p.name}.');
    }
  }

  Future<void> _changeUnit() async {
    if (_units.length < 2) return;
    final picked = await pickUnit(
      context,
      units: _units,
      baseCode: _product!.unit,
      current: _unit,
      title: 'Counting in which unit?',
    );
    if (picked == null || !mounted) return;
    // The number on the stepper means something different in the new unit, so
    // it is reset rather than silently re-scaled.
    setState(() {
      _unit = picked;
      _delta = 0;
    });
  }

  Future<void> _submit() async {
    setState(() => _busy = true);
    final unit = _unit!;
    try {
      await AppScope.of(context).stock.adjust(
            productUid: _product!.uid,
            quantity: _deltaInBase,
            reasonCode: _reasonCode!,
            note: _note.text.trim(),
          );
      if (!mounted) return;
      final entered = '${_delta > 0 ? '+' : ''}$_delta ${unit.code}';
      final posted = '${_deltaInBase > 0 ? '+' : ''}'
          '${qty(_deltaInBase)} ${_product!.unit}';
      await showDoneSheet(
        context,
        title: 'Adjustment posted',
        detail: '${_product!.name}\n'
            '${unit.isBase ? posted : '$entered = $posted'} · $_reasonLabel',
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
                if (_product != null && _unit != null) ...[
                  const SizedBox(height: 20),
                  Row(
                    children: [
                      Text('Adjust by', style: HqText.label),
                      const Spacer(),
                      if (_units.length > 1)
                        InkWell(
                          onTap: _changeUnit,
                          borderRadius: BorderRadius.circular(6),
                          child: Padding(
                            padding: const EdgeInsets.symmetric(
                                horizontal: 4, vertical: 2),
                            child: Row(
                              mainAxisSize: MainAxisSize.min,
                              children: [
                                Text(
                                  'in ${_unit!.code}',
                                  style: const TextStyle(
                                    fontSize: 13,
                                    fontWeight: FontWeight.w700,
                                    color: HqColors.brand,
                                  ),
                                ),
                                const SizedBox(width: 3),
                                const Icon(Icons.swap_horiz_rounded,
                                    size: 16, color: HqColors.brand),
                              ],
                            ),
                          ),
                        ),
                    ],
                  ),
                  const SizedBox(height: 8),
                  QtyStepper(
                    value: _delta,
                    allowNegative: true,
                    unit: _unit!.code,
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
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        FigureRow(
                          // Always stated in the base unit: that is the number
                          // the ledger moves, whatever was typed above.
                          label: 'Change to post',
                          value: '${_deltaInBase > 0 ? '+' : ''}'
                              '${qty(_deltaInBase)} ${_product!.unit}',
                          emphasise: true,
                          valueColor:
                              _delta < 0 ? HqColors.bad : HqColors.brand,
                        ),
                        if (!_unit!.isBase) ...[
                          const SizedBox(height: 4),
                          Text(
                            '$_delta ${_unit!.code} x ${_unit!.factorLabel} '
                            '${_product!.unit}',
                            style: HqText.tiny,
                          ),
                        ],
                      ],
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
