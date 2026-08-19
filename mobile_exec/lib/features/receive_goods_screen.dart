import 'package:flutter/material.dart';

import '../app/format.dart';
import '../app/theme.dart';
import '../data/mock.dart';
import '../widgets/common.dart';
import '../widgets/kit.dart';
import 'product_picker.dart';

/// Receive goods without a purchase order — the client asked for this
/// explicitly. Mockup: builds a real-looking receipt, posts nothing.
class ReceiveGoodsScreen extends StatefulWidget {
  const ReceiveGoodsScreen({super.key});

  @override
  State<ReceiveGoodsScreen> createState() => _ReceiveGoodsScreenState();
}

class _ReceivedLine {
  _ReceivedLine({required this.product, required this.qty, required this.cost});

  final Product product;
  int qty;
  num cost;

  num get total => qty * cost;
}

class _ReceiveGoodsScreenState extends State<ReceiveGoodsScreen> {
  String? _supplier;
  final _reference = TextEditingController();
  final _lines = <_ReceivedLine>[];

  @override
  void dispose() {
    _reference.dispose();
    super.dispose();
  }

  num get _total => _lines.fold<num>(0, (a, l) => a + l.total);

  bool get _ready => _supplier != null && _lines.isNotEmpty;

  Future<void> _addLine() async {
    final p = await pickProduct(context);
    if (p == null || !mounted) return;
    setState(() {
      _lines.add(_ReceivedLine(product: p, qty: 1, cost: p.cost));
    });
  }

  Future<void> _submit() async {
    await showDoneSheet(
      context,
      title: 'Goods received',
      detail: '${_lines.length} '
          '${_lines.length == 1 ? 'item' : 'items'} from $_supplier\n'
          '${tzs(_total)} added to stock',
    );
    if (mounted) Navigator.of(context).pop();
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: HqColors.bg,
      appBar: AppBar(title: const Text('Receive goods', style: HqText.title)),
      body: ListView(
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
                    'No purchase order needed. Stock goes up and the supplier '
                    'is owed as soon as you save.',
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
          HqDropdown(
            label: 'Supplier',
            required: true,
            items: [for (final s in kSuppliers) s.name],
            value: _supplier,
            hint: 'Who delivered?',
            onChanged: (v) => setState(() => _supplier = v),
          ),
          const SizedBox(height: 16),
          HqField(
            label: 'Delivery note number',
            controller: _reference,
            hint: 'Optional — what is on their paper',
          ),
          const SizedBox(height: 24),
          Row(
            children: [
              Expanded(
                child: SectionLabel(
                  text: 'ITEMS',
                  trailing: _lines.isEmpty ? null : '${_lines.length} ADDED',
                ),
              ),
            ],
          ),
          const SizedBox(height: 10),
          if (_lines.isEmpty)
            _EmptyLines(onAdd: _addLine)
          else ...[
            for (var i = 0; i < _lines.length; i++) ...[
              _LineCard(
                line: _lines[i],
                onQty: (q) => setState(() => _lines[i].qty = q),
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
              child: Column(
                children: [
                  FigureRow(
                    label: 'Items',
                    value: '${_lines.length}',
                  ),
                  const Divider(height: 14),
                  FigureRow(
                    label: 'Total value',
                    value: tzs(_total),
                    emphasise: true,
                    valueColor: HqColors.brand,
                  ),
                ],
              ),
            ),
          ],
          const SizedBox(height: 26),
          FilledButton(
            onPressed: _ready ? _submit : null,
            child: const Text('Receive into stock'),
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
        border: Border.all(color: HqColors.line2, style: BorderStyle.solid),
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
    required this.onRemove,
  });

  final _ReceivedLine line;
  final ValueChanged<int> onQty;
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
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(
                      line.product.name,
                      maxLines: 1,
                      overflow: TextOverflow.ellipsis,
                      style: const TextStyle(
                        fontSize: 14.5,
                        fontWeight: FontWeight.w600,
                        color: HqColors.ink,
                      ),
                    ),
                    const SizedBox(height: 2),
                    Text(
                      '${line.product.code} · ${tzs(line.cost)} per '
                      '${line.product.unit}',
                      maxLines: 1,
                      overflow: TextOverflow.ellipsis,
                      style: HqText.tiny,
                    ),
                  ],
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
          const SizedBox(height: 12),
          Row(
            children: [
              Expanded(
                flex: 3,
                child: QtyStepper(
                  value: line.qty,
                  unit: line.product.unit,
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
