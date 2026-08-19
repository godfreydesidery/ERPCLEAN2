import 'package:flutter/material.dart';
import 'package:uuid/uuid.dart';

import '../app/app_scope.dart';
import '../app/format.dart';
import '../app/theme.dart';
import '../core/api/api_exception.dart';
import '../services/catalog_service.dart';
import '../services/operations_service.dart';
import '../widgets/async_view.dart';
import '../widgets/common.dart';
import '../widgets/kit.dart';
import 'product_picker.dart';

/// Receive goods with no purchase order — `/goods-receipts/direct`.
class ReceiveGoodsScreen extends StatefulWidget {
  const ReceiveGoodsScreen({super.key});

  @override
  State<ReceiveGoodsScreen> createState() => _ReceiveGoodsScreenState();
}

class _ReceiveGoodsScreenState extends State<ReceiveGoodsScreen> {
  SupplierItem? _supplier;
  final _reference = TextEditingController();
  final _lines = <ReceiptLine>[];
  bool _busy = false;

  /// Minted once per receipt so a retry after a timeout cannot receive the
  /// same delivery twice.
  final String _idempotencyKey = const Uuid().v4();

  @override
  void dispose() {
    _reference.dispose();
    super.dispose();
  }

  double get _total => _lines.fold<double>(0, (a, l) => a + l.total);

  bool get _ready => _supplier != null && _lines.isNotEmpty && !_busy;

  Future<void> _pickSupplier() async {
    final suppliers = await AppScope.of(context).catalog.suppliers();
    if (!mounted) return;
    final chosen = await showModalBottomSheet<SupplierItem>(
      context: context,
      backgroundColor: Colors.transparent,
      builder: (context) => Container(
        decoration: const BoxDecoration(
          color: HqColors.panel,
          borderRadius: BorderRadius.vertical(top: Radius.circular(22)),
        ),
        padding: const EdgeInsets.fromLTRB(20, 18, 20, 8),
        child: SafeArea(
          top: false,
          child: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              const Text('Choose a supplier', style: HqText.title),
              const SizedBox(height: 8),
              if (suppliers.isEmpty)
                Padding(
                  padding: const EdgeInsets.symmetric(vertical: 24),
                  child: Text(
                    'No suppliers yet. Create one from Operations first.',
                    style: HqText.body,
                  ),
                )
              else
                Flexible(
                  child: ListView.separated(
                    shrinkWrap: true,
                    itemCount: suppliers.length,
                    separatorBuilder: (_, __) => const Divider(height: 1),
                    itemBuilder: (context, i) => ListTile(
                      contentPadding: EdgeInsets.zero,
                      title: Text(
                        suppliers[i].name,
                        maxLines: 1,
                        overflow: TextOverflow.ellipsis,
                        style: const TextStyle(
                          fontSize: 14.5,
                          fontWeight: FontWeight.w600,
                          color: HqColors.ink,
                        ),
                      ),
                      subtitle: suppliers[i].phone == null
                          ? null
                          : Text(suppliers[i].phone!, style: HqText.tiny),
                      onTap: () => Navigator.of(context).pop(suppliers[i]),
                    ),
                  ),
                ),
            ],
          ),
        ),
      ),
    );
    if (chosen != null) setState(() => _supplier = chosen);
  }

  Future<void> _addLine() async {
    final p = await pickProduct(context);
    if (p == null || !mounted) return;
    setState(() {
      _lines.add(ReceiptLine(
        productUid: p.uid,
        productName: p.name,
        unit: p.unit,
        qty: 1,
        unitCost: 0,
      ));
    });
  }

  Future<void> _editCost(int i) async {
    final controller =
        TextEditingController(text: _lines[i].unitCost.toStringAsFixed(0));
    final saved = await showDialog<String>(
      context: context,
      builder: (context) => AlertDialog(
        title: const Text('Cost per unit'),
        content: TextField(
          controller: controller,
          keyboardType: TextInputType.number,
          autofocus: true,
          decoration: const InputDecoration(suffixText: 'TZS'),
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.of(context).pop(),
            child: const Text('Cancel'),
          ),
          FilledButton(
            onPressed: () => Navigator.of(context).pop(controller.text),
            child: const Text('Save'),
          ),
        ],
      ),
    );
    final v = double.tryParse(saved ?? '');
    if (v != null) setState(() => _lines[i].unitCost = v);
  }

  Future<void> _submit() async {
    setState(() => _busy = true);
    try {
      await AppScope.of(context).operations.receiveDirect(
            supplierUid: _supplier!.uid,
            lines: _lines,
            notes: _reference.text.trim(),
            idempotencyKey: _idempotencyKey,
          );
      if (!mounted) return;
      await showDoneSheet(
        context,
        title: 'Goods received',
        detail: '${_lines.length} '
            '${_lines.length == 1 ? 'item' : 'items'} from ${_supplier!.name}\n'
            '${tzs(_total)} added to stock',
      );
      if (mounted) Navigator.of(context).pop();
    } on ApiException catch (e) {
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(
            behavior: SnackBarBehavior.floating,
            backgroundColor: HqColors.bad,
            content: Text(e.message),
          ),
        );
      }
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final session = AppScope.of(context).session;

    return Scaffold(
      backgroundColor: HqColors.bg,
      appBar: AppBar(title: const Text('Receive goods', style: HqText.title)),
      body: !session.can('PURCHASE.RECEIVE.DIRECT')
          ? const NoPermission(code: 'PURCHASE.RECEIVE.DIRECT')
          : ListView(
              padding: const EdgeInsets.fromLTRB(20, 8, 20, 28),
              children: [
                Container(
                  padding: const EdgeInsets.all(13),
                  decoration: BoxDecoration(
                    color: HqColors.brandSoft,
                    borderRadius: BorderRadius.circular(HqRadii.sm),
                  ),
                  child: Row(
                    children: [
                      const Icon(Icons.info_outline_rounded,
                          size: 19, color: HqColors.brand),
                      const SizedBox(width: 10),
                      Expanded(
                        child: Text(
                          'No purchase order needed. Stock goes up and the '
                          'supplier is owed as soon as you save.',
                          style: TextStyle(
                            fontSize: 13,
                            height: 1.35,
                            color: HqColors.brandD,
                          ),
                        ),
                      ),
                    ],
                  ),
                ),
                const SizedBox(height: 20),
                _SupplierTile(supplier: _supplier, onTap: _pickSupplier),
                const SizedBox(height: 16),
                HqField(
                  label: 'Delivery note number',
                  controller: _reference,
                  hint: 'Optional — what is on their paper',
                ),
                const SizedBox(height: 24),
                SectionLabel(
                  text: 'ITEMS',
                  trailing: _lines.isEmpty ? null : '${_lines.length} ADDED',
                ),
                const SizedBox(height: 10),
                if (_lines.isEmpty)
                  _EmptyLines(onAdd: _addLine)
                else ...[
                  for (var i = 0; i < _lines.length; i++) ...[
                    _LineCard(
                      line: _lines[i],
                      onQty: (q) => setState(() => _lines[i].qty = q.toDouble()),
                      onCost: () => _editCost(i),
                      onRemove: () => setState(() => _lines.removeAt(i)),
                    ),
                    const SizedBox(height: 10),
                  ],
                  OutlinedButton.icon(
                    onPressed: _addLine,
                    icon: const Icon(Icons.add_rounded, size: 19),
                    label: const Text('Add another item'),
                  ),
                  const SizedBox(height: 18),
                  HqCard(
                    child: FigureRow(
                      label: 'Total value',
                      value: tzs(_total),
                      emphasise: true,
                      valueColor: HqColors.brand,
                    ),
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
                      : const Text('Receive into stock'),
                ),
              ],
            ),
    );
  }
}

class _SupplierTile extends StatelessWidget {
  const _SupplierTile({required this.supplier, required this.onTap});

  final SupplierItem? supplier;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        const Row(
          children: [
            Text(
              'Supplier',
              style: TextStyle(
                fontSize: 12.5,
                fontWeight: FontWeight.w700,
                color: HqColors.ink2,
              ),
            ),
            Text(
              ' *',
              style: TextStyle(
                fontSize: 12.5,
                fontWeight: FontWeight.w700,
                color: HqColors.bad,
              ),
            ),
          ],
        ),
        const SizedBox(height: 7),
        Material(
          color: HqColors.panel,
          borderRadius: BorderRadius.circular(HqRadii.sm),
          child: InkWell(
            onTap: onTap,
            borderRadius: BorderRadius.circular(HqRadii.sm),
            child: Container(
              padding:
                  const EdgeInsets.symmetric(horizontal: 14, vertical: 14),
              decoration: BoxDecoration(
                borderRadius: BorderRadius.circular(HqRadii.sm),
                border: Border.all(
                  color: supplier == null ? HqColors.line2 : HqColors.brand,
                  width: supplier == null ? 1 : 1.5,
                ),
              ),
              child: Row(
                children: [
                  Icon(
                    Icons.storefront_outlined,
                    size: 20,
                    color: supplier == null ? HqColors.ink3 : HqColors.brand,
                  ),
                  const SizedBox(width: 12),
                  Expanded(
                    child: Text(
                      supplier?.name ?? 'Tap to choose a supplier',
                      maxLines: 1,
                      overflow: TextOverflow.ellipsis,
                      style: TextStyle(
                        fontSize: 15,
                        fontWeight: supplier == null
                            ? FontWeight.w400
                            : FontWeight.w600,
                        color:
                            supplier == null ? HqColors.ink3 : HqColors.ink,
                      ),
                    ),
                  ),
                  const Icon(Icons.chevron_right,
                      size: 20, color: HqColors.ink3),
                ],
              ),
            ),
          ),
        ),
      ],
    );
  }
}

class _EmptyLines extends StatelessWidget {
  const _EmptyLines({required this.onAdd});

  final VoidCallback onAdd;

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.symmetric(vertical: 30, horizontal: 20),
      decoration: BoxDecoration(
        color: HqColors.panel,
        borderRadius: BorderRadius.circular(16),
        border: Border.all(color: HqColors.line2),
      ),
      child: Column(
        children: [
          const Icon(Icons.add_box_outlined, size: 38, color: HqColors.ink3),
          const SizedBox(height: 12),
          Text(
            'Nothing added yet',
            style: HqText.body.copyWith(fontWeight: FontWeight.w600),
          ),
          const SizedBox(height: 4),
          Text(
            'Add each item the supplier delivered.',
            style: HqText.tiny,
            textAlign: TextAlign.center,
          ),
          const SizedBox(height: 16),
          FilledButton.icon(
            onPressed: onAdd,
            icon: const Icon(Icons.add_rounded, size: 19),
            label: const Text('Add an item'),
          ),
        ],
      ),
    );
  }
}

class _LineCard extends StatelessWidget {
  const _LineCard({
    required this.line,
    required this.onQty,
    required this.onCost,
    required this.onRemove,
  });

  final ReceiptLine line;
  final ValueChanged<int> onQty;
  final VoidCallback onCost;
  final VoidCallback onRemove;

  @override
  Widget build(BuildContext context) {
    return HqCard(
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              Expanded(
                child: Text(
                  line.productName,
                  maxLines: 1,
                  overflow: TextOverflow.ellipsis,
                  style: const TextStyle(
                    fontSize: 14.5,
                    fontWeight: FontWeight.w600,
                    color: HqColors.ink,
                  ),
                ),
              ),
              IconButton(
                onPressed: onRemove,
                icon: const Icon(Icons.close_rounded, size: 19),
                color: HqColors.ink3,
                visualDensity: VisualDensity.compact,
              ),
            ],
          ),
          const SizedBox(height: 8),
          InkWell(
            onTap: onCost,
            borderRadius: BorderRadius.circular(6),
            child: Padding(
              padding: const EdgeInsets.symmetric(vertical: 4),
              child: Row(
                children: [
                  Text(
                    line.unitCost == 0
                        ? 'Set cost per ${line.unit}'
                        : '${tzs(line.unitCost)} per ${line.unit}',
                    style: TextStyle(
                      fontSize: 13,
                      fontWeight: FontWeight.w600,
                      color: line.unitCost == 0
                          ? HqColors.warn
                          : HqColors.ink2,
                    ),
                  ),
                  const SizedBox(width: 5),
                  const Icon(Icons.edit_outlined,
                      size: 14, color: HqColors.ink3),
                ],
              ),
            ),
          ),
          const SizedBox(height: 10),
          Row(
            children: [
              Expanded(
                flex: 3,
                child: QtyStepper(
                  value: line.qty.round(),
                  unit: line.unit,
                  onChanged: onQty,
                ),
              ),
              const SizedBox(width: 14),
              Expanded(
                flex: 2,
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.end,
                  children: [
                    Text('Line total', style: HqText.tiny),
                    const SizedBox(height: 3),
                    Text(
                      tzs(line.total),
                      maxLines: 1,
                      overflow: TextOverflow.ellipsis,
                      style: const TextStyle(
                        fontSize: 16,
                        fontWeight: FontWeight.w700,
                        color: HqColors.ink,
                      ),
                    ),
                  ],
                ),
              ),
            ],
          ),
        ],
      ),
    );
  }
}
