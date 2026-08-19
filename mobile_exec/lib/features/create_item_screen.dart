import 'package:flutter/material.dart';

import '../app/format.dart';
import '../app/theme.dart';
import '../data/mock.dart';
import '../widgets/common.dart';
import '../widgets/kit.dart';

/// Register a new product. Mockup: validates and confirms, posts nothing.
class CreateItemScreen extends StatefulWidget {
  const CreateItemScreen({super.key});

  @override
  State<CreateItemScreen> createState() => _CreateItemScreenState();
}

class _CreateItemScreenState extends State<CreateItemScreen> {
  final _name = TextEditingController();
  final _code = TextEditingController();
  final _barcode = TextEditingController();
  final _cost = TextEditingController();
  final _price = TextEditingController();
  final _opening = TextEditingController();

  String? _category;
  String? _unit;
  bool _vatable = true;

  @override
  void initState() {
    super.initState();
    for (final c in [_cost, _price]) {
      c.addListener(() => setState(() {}));
    }
  }

  @override
  void dispose() {
    for (final c in [_name, _code, _barcode, _cost, _price, _opening]) {
      c.dispose();
    }
    super.dispose();
  }

  bool get _ready =>
      _name.text.trim().isNotEmpty &&
      _category != null &&
      _unit != null &&
      _price.text.trim().isNotEmpty;

  double? get _margin {
    final c = double.tryParse(_cost.text);
    final p = double.tryParse(_price.text);
    if (c == null || p == null || p == 0) return null;
    return (p - c) / p * 100;
  }

  Future<void> _submit() async {
    await showDoneSheet(
      context,
      title: 'Item created',
      detail: '${_name.text.trim()}\n'
          '${_category!} · sold by ${_unit!}',
    );
    if (mounted) Navigator.of(context).pop();
  }

  @override
  Widget build(BuildContext context) {
    final margin = _margin;

    return Scaffold(
      backgroundColor: HqColors.bg,
      appBar: AppBar(title: const Text('New item', style: HqText.title)),
      body: ListView(
        padding: const EdgeInsets.fromLTRB(20, 8, 20, 28),
        children: [
          const SectionLabel(text: 'WHAT IT IS'),
          const SizedBox(height: 12),
          HqField(
            label: 'Item name',
            required: true,
            controller: _name,
            hint: 'e.g. Cooking Oil 20L — Tembo',
            onTap: () => setState(() {}),
          ),
          const SizedBox(height: 16),
          Row(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Expanded(
                child: HqField(
                  label: 'Item code',
                  controller: _code,
                  hint: 'Auto if left blank',
                ),
              ),
              const SizedBox(width: 12),
              Expanded(
                child: HqField(
                  label: 'Barcode',
                  controller: _barcode,
                  hint: 'Optional',
                  prefix: Icons.qr_code_scanner_rounded,
                ),
              ),
            ],
          ),
          const SizedBox(height: 16),
          HqDropdown(
            label: 'Category',
            required: true,
            items: kCategories,
            value: _category,
            onChanged: (v) => setState(() => _category = v),
          ),
          const SizedBox(height: 16),
          HqDropdown(
            label: 'Sold by',
            required: true,
            items: kUnits,
            value: _unit,
            hint: 'Unit of measure',
            onChanged: (v) => setState(() => _unit = v),
          ),
          const SizedBox(height: 24),
          const SectionLabel(text: 'PRICING'),
          const SizedBox(height: 12),
          Row(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Expanded(
                child: HqField(
                  label: 'Buying price',
                  controller: _cost,
                  keyboardType: TextInputType.number,
                  hint: '0',
                  suffixText: 'TZS',
                ),
              ),
              const SizedBox(width: 12),
              Expanded(
                child: HqField(
                  label: 'Selling price',
                  required: true,
                  controller: _price,
                  keyboardType: TextInputType.number,
                  hint: '0',
                  suffixText: 'TZS',
                ),
              ),
            ],
          ),
          if (margin != null) ...[
            const SizedBox(height: 12),
            Container(
              padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 12),
              decoration: BoxDecoration(
                color: margin < 0 ? HqColors.badSoft : HqColors.goodSoft,
                borderRadius: BorderRadius.circular(HqRadii.sm),
              ),
              child: Row(
                children: [
                  Icon(
                    margin < 0
                        ? Icons.trending_down_rounded
                        : Icons.trending_up_rounded,
                    size: 19,
                    color: margin < 0 ? HqColors.bad : HqColors.good,
                  ),
                  const SizedBox(width: 10),
                  Expanded(
                    child: Text(
                      margin < 0
                          ? 'You would sell this below cost.'
                          : 'Margin ${pct(margin)}',
                      style: TextStyle(
                        fontSize: 13.5,
                        fontWeight: FontWeight.w600,
                        color: margin < 0 ? HqColors.bad : HqColors.good,
                      ),
                    ),
                  ),
                ],
              ),
            ),
          ],
          const SizedBox(height: 16),
          SwitchListTile.adaptive(
            value: _vatable,
            onChanged: (v) => setState(() => _vatable = v),
            contentPadding: EdgeInsets.zero,
            activeThumbColor: HqColors.brand,
            title: const Text(
              'VAT applies',
              style: TextStyle(
                fontSize: 15,
                fontWeight: FontWeight.w600,
                color: HqColors.ink,
              ),
            ),
            subtitle: Text('Standard rate 18%', style: HqText.tiny),
          ),
          const SizedBox(height: 16),
          const SectionLabel(text: 'OPENING STOCK'),
          const SizedBox(height: 12),
          HqField(
            label: 'Quantity on hand',
            controller: _opening,
            keyboardType: TextInputType.number,
            hint: '0',
            helper: 'Leave at zero if you will receive it separately.',
          ),
          const SizedBox(height: 26),
          FilledButton(
            onPressed: _ready ? _submit : null,
            child: const Text('Create item'),
          ),
          const SizedBox(height: 10),
          Center(
            child: Text(
              'Demo build — nothing is saved to the server.',
              style: HqText.tiny,
            ),
          ),
        ],
      ),
    );
  }
}
