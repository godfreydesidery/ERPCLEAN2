import 'package:flutter/material.dart';

import '../app/app_scope.dart';
import '../app/theme.dart';
import '../core/api/api_exception.dart';
import '../widgets/async_view.dart';
import '../widgets/common.dart';
import '../widgets/kit.dart';

/// Register a product against `/products`.
class CreateItemScreen extends StatefulWidget {
  const CreateItemScreen({super.key});

  @override
  State<CreateItemScreen> createState() => _CreateItemScreenState();
}

class _CreateItemScreenState extends State<CreateItemScreen> {
  final _name = TextEditingController();
  final _code = TextEditingController();
  final _barcode = TextEditingController();

  String? _unit;
  bool _vatable = true;
  bool _busy = false;

  /// Base unit codes the ERP ships with.
  static const _units = <String>[
    'PCS',
    'BAG',
    'CTN',
    'KG',
    'LTR',
    'BOX',
    'DZN',
  ];

  @override
  void initState() {
    super.initState();
    _name.addListener(() => setState(() {}));
    _code.addListener(() => setState(() {}));
  }

  @override
  void dispose() {
    for (final c in [_name, _code, _barcode]) {
      c.dispose();
    }
    super.dispose();
  }

  bool get _ready =>
      _name.text.trim().isNotEmpty &&
      _code.text.trim().isNotEmpty &&
      _unit != null &&
      !_busy;

  Future<void> _submit() async {
    setState(() => _busy = true);
    try {
      final created = await AppScope.of(context).catalog.createProduct(
            name: _name.text.trim(),
            code: _code.text.trim().toUpperCase(),
            baseUnitCode: _unit!,
            barcode: _barcode.text.trim(),
            vatable: _vatable,
          );
      if (!mounted) return;
      await showDoneSheet(
        context,
        title: 'Item created',
        detail: '${created.name}\n${created.code}',
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
      appBar: AppBar(title: const Text('New item', style: HqText.title)),
      body: !session.can('PRODUCT.MANAGE')
          ? const NoPermission(code: 'PRODUCT.MANAGE')
          : ListView(
              padding: const EdgeInsets.fromLTRB(20, 8, 20, 28),
              children: [
                const SectionLabel(text: 'WHAT IT IS'),
                const SizedBox(height: 12),
                HqField(
                  label: 'Item name',
                  required: true,
                  controller: _name,
                  hint: 'e.g. Cooking Oil 20L',
                ),
                const SizedBox(height: 16),
                HqField(
                  label: 'Item code',
                  required: true,
                  controller: _code,
                  hint: 'e.g. OIL-20L',
                  helper: 'Must be unique within the company.',
                ),
                const SizedBox(height: 16),
                HqField(
                  label: 'Barcode',
                  controller: _barcode,
                  hint: 'Optional',
                  prefix: Icons.qr_code_scanner_rounded,
                ),
                const SizedBox(height: 16),
                HqDropdown(
                  label: 'Sold by',
                  required: true,
                  items: _units,
                  value: _unit,
                  hint: 'Unit of measure',
                  onChanged: (v) => setState(() => _unit = v),
                ),
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
                  subtitle: Text(
                    _vatable ? 'Standard rate' : 'Exempt',
                    style: HqText.tiny,
                  ),
                ),
                const SizedBox(height: 10),
                HqCard(
                  child: Row(
                    children: [
                      const Icon(Icons.info_outline_rounded,
                          size: 19, color: HqColors.ink3),
                      const SizedBox(width: 10),
                      Expanded(
                        child: Text(
                          'Prices and opening stock are set separately — '
                          'receive the goods to bring stock in.',
                          style: HqText.body,
                        ),
                      ),
                    ],
                  ),
                ),
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
                      : const Text('Create item'),
                ),
              ],
            ),
    );
  }
}
