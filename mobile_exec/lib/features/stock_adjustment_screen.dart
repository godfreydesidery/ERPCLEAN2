import 'package:flutter/material.dart';

import '../app/format.dart';
import '../app/theme.dart';
import '../data/mock.dart';
import '../widgets/common.dart';
import '../widgets/kit.dart';
import 'product_picker.dart';

/// Stock adjustment — correct a quantity, with a reason.
/// Mockup: the form works, nothing is posted.
class StockAdjustmentScreen extends StatefulWidget {
  const StockAdjustmentScreen({super.key});

  @override
  State<StockAdjustmentScreen> createState() => _StockAdjustmentScreenState();
}

class _StockAdjustmentScreenState extends State<StockAdjustmentScreen> {
  Product? _product;
  int _delta = 0;
  String? _reason;
  final _note = TextEditingController();

  @override
  void dispose() {
    _note.dispose();
    super.dispose();
  }

  bool get _ready => _product != null && _delta != 0 && _reason != null;

  int get _newOnHand => (_product?.onHand ?? 0) + _delta;

  Future<void> _pick() async {
    final p = await pickProduct(context);
    if (p != null) setState(() => _product = p);
  }

  Future<void> _submit() async {
    await showDoneSheet(
      context,
      title: 'Adjustment recorded',
      detail: '${_product!.name}\n'
          '${_delta > 0 ? '+' : ''}$_delta ${_product!.unit} · $_reason',
    );
    if (mounted) Navigator.of(context).pop();
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: HqColors.bg,
      appBar: AppBar(
        title: const Text('Stock adjustment', style: HqText.title),
      ),
      body: ListView(
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
                Expanded(
                  child: _Quick(
                    label: '−10',
                    onTap: () => setState(() => _delta -= 10),
                  ),
                ),
                const SizedBox(width: 8),
                Expanded(
                  child: _Quick(
                    label: '−1',
                    onTap: () => setState(() => _delta -= 1),
                  ),
                ),
                const SizedBox(width: 8),
                Expanded(
                  child: _Quick(
                    label: '+1',
                    onTap: () => setState(() => _delta += 1),
                  ),
                ),
                const SizedBox(width: 8),
                Expanded(
                  child: _Quick(
                    label: '+10',
                    onTap: () => setState(() => _delta += 10),
                  ),
                ),
              ],
            ),
            const SizedBox(height: 18),
            HqCard(
              padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 12),
              child: Column(
                children: [
                  FigureRow(
                    label: 'On hand now',
                    value: '${_product!.onHand} ${_product!.unit}',
                  ),
                  const Divider(height: 14),
                  FigureRow(
                    label: 'After this adjustment',
                    value: '$_newOnHand ${_product!.unit}',
                    emphasise: true,
                    valueColor:
                        _newOnHand < 0 ? HqColors.bad : HqColors.brand,
                  ),
                  const Divider(height: 14),
                  FigureRow(
                    label: 'Value of the change',
                    value: tzs(_delta * _product!.cost, sign: true),
                    valueColor: _delta < 0 ? HqColors.bad : HqColors.good,
                  ),
                ],
              ),
            ),
            if (_newOnHand < 0) ...[
              const SizedBox(height: 12),
              _Warning(
                text: 'This would take stock below zero. '
                    'Check the count before you post.',
              ),
            ],
            const SizedBox(height: 20),
            HqDropdown(
              label: 'Reason',
              required: true,
              items: kAdjustmentReasons,
              value: _reason,
              hint: 'Why is the quantity changing?',
              onChanged: (v) => setState(() => _reason = v),
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
            child: const Text('Record adjustment'),
          ),
          const SizedBox(height: 10),
          Center(
            child: Text(
              'Demo build — nothing is posted to the server.',
              style: HqText.tiny,
            ),
          ),
        ],
      ),
    );
  }
}

class _Quick extends StatelessWidget {
  const _Quick({required this.label, required this.onTap});

  final String label;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    return OutlinedButton(
      onPressed: onTap,
      style: OutlinedButton.styleFrom(
        minimumSize: const Size.fromHeight(40),
        padding: EdgeInsets.zero,
        side: const BorderSide(color: HqColors.line2),
      ),
      child: Text(
        label,
        style: const TextStyle(fontSize: 14, fontWeight: FontWeight.w700),
      ),
    );
  }
}

class _Warning extends StatelessWidget {
  const _Warning({required this.text});

  final String text;

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.all(13),
      decoration: BoxDecoration(
        color: HqColors.warnSoft,
        borderRadius: BorderRadius.circular(HqRadii.sm),
        border: Border.all(color: HqColors.warn.withValues(alpha: 0.3)),
      ),
      child: Row(
        children: [
          const Icon(Icons.warning_amber_rounded,
              size: 19, color: HqColors.warn),
          const SizedBox(width: 10),
          Expanded(
            child: Text(
              text,
              style: const TextStyle(
                fontSize: 13,
                color: Color(0xFF8A5A0B),
                height: 1.35,
              ),
            ),
          ),
        ],
      ),
    );
  }
}
