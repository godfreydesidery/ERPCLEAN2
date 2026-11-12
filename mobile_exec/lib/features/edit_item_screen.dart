import 'package:flutter/material.dart';

import '../app/app_scope.dart';
import '../app/theme.dart';
import '../core/api/api_exception.dart';
import '../services/catalog_service.dart';
import '../widgets/async_view.dart';
import '../widgets/common.dart';
import '../widgets/kit.dart';
import 'master_pickers.dart';

/// Edit an existing item: name, description, department, supplier, cost
/// price, selling price and VAT; or deactivate it.
///
/// The code and the counting unit are not edited here. The code never
/// changes (BR-PROD-02), and changing the unit under existing prices is
/// refused by the server for good reason; both stay on the web.
class EditItemScreen extends StatefulWidget {
  const EditItemScreen({super.key, required this.productUid});

  final String productUid;

  @override
  State<EditItemScreen> createState() => _EditItemScreenState();
}

class _Loaded {
  const _Loaded({
    required this.product,
    required this.priceList,
    required this.basePrice,
    required this.supplierName,
  });

  final ProductFull product;
  final PriceListRef? priceList;
  final double? basePrice;

  /// Null when the product has no supplier; "On file" when it has one this
  /// user's supplier list does not show.
  final String? supplierName;
}

class _EditItemScreenState extends State<EditItemScreen> {
  Future<_Loaded> _load() async {
    final catalog = AppScope.of(context).catalog;
    final product = await catalog.product(widget.productUid);

    PriceListRef? list;
    double? basePrice;
    try {
      list = await catalog.defaultPriceList();
      if (list != null) {
        final prices = await catalog.prices(product.uid);
        for (final p in prices) {
          if (p.priceListUid == list.uid && p.unitUid == null) {
            basePrice = p.amount;
          }
        }
      }
    } catch (_) {
      // Pricing out of this user's reach: the price field is not offered.
      list = null;
    }

    String? supplierName;
    final sid = product.preferredSupplierId;
    if (sid != null) {
      supplierName = 'On file';
      try {
        final all = await catalog.suppliers(size: 500);
        for (final s in all) {
          if (s.id == sid) supplierName = s.name;
        }
      } catch (_) {
        // Keep "On file".
      }
    }

    return _Loaded(
      product: product,
      priceList: list,
      basePrice: basePrice,
      supplierName: supplierName,
    );
  }

  @override
  Widget build(BuildContext context) {
    final session = AppScope.of(context).session;
    return Scaffold(
      backgroundColor: HqColors.bg,
      appBar: AppBar(title: const Text('Edit item', style: HqText.title)),
      body: !session.can('PRODUCT.MANAGE')
          ? const NoPermission(code: 'PRODUCT.MANAGE')
          : AsyncView<_Loaded>(
              load: _load,
              builder: (context, data) => _EditForm(data: data),
            ),
    );
  }
}

class _EditForm extends StatefulWidget {
  const _EditForm({required this.data});

  final _Loaded data;

  @override
  State<_EditForm> createState() => _EditFormState();
}

class _EditFormState extends State<_EditForm> {
  late final ProductFull _p = widget.data.product;
  late final _name = TextEditingController(text: _p.name);
  late final _description = TextEditingController(text: _p.description);
  late final _cost = TextEditingController(text: _fmt(_p.cost));
  late final _price = TextEditingController(text: _fmt(widget.data.basePrice));
  late String _department = _p.category;
  late bool _vatable = _p.isVatable;

  /// The supplier the screen shows. [_supplierChanged] says whether the owner
  /// touched it; untouched, the stored one is sent back as it was.
  late String? _supplierName = widget.data.supplierName;
  SupplierItem? _newSupplier;
  bool _supplierChanged = false;

  bool _busy = false;

  static String _fmt(double? v) {
    if (v == null) return '';
    return v == v.roundToDouble() ? v.toStringAsFixed(0) : v.toString();
  }

  static double? _num(TextEditingController c) =>
      double.tryParse(c.text.trim().replaceAll(',', ''));

  @override
  void initState() {
    super.initState();
    _name.addListener(() => setState(() {}));
  }

  @override
  void dispose() {
    for (final c in [_name, _description, _cost, _price]) {
      c.dispose();
    }
    super.dispose();
  }

  String get _currency =>
      widget.data.priceList?.currency ?? _p.costCurrency ?? 'TZS';

  Future<void> _save() async {
    final catalog = AppScope.of(context).catalog;
    setState(() => _busy = true);
    try {
      final cost = _num(_cost);
      await catalog.updateProduct(
        _p,
        name: _name.text.trim(),
        description: _description.text.trim(),
        category: _department,
        vatable: _vatable,
        cost: cost != null && cost >= 0 ? cost : null,
        currency: _p.costCurrency ?? _currency,
        preferredSupplierId: _supplierChanged
            ? _newSupplier?.id
            : _p.preferredSupplierId,
        clearSupplier: _supplierChanged && _newSupplier == null,
      );

      // The price is its own record; only written when it actually changed.
      String? priceProblem;
      final list = widget.data.priceList;
      final price = _num(_price);
      if (list != null &&
          price != null &&
          price > 0 &&
          price != widget.data.basePrice) {
        try {
          await catalog.setPrice(
            productUid: _p.uid,
            priceListUid: list.uid,
            amount: price,
            currency: list.currency,
          );
        } on ApiException catch (e) {
          priceProblem = e.message;
        } catch (_) {
          priceProblem = 'The selling price was not saved.';
        }
      }

      if (!mounted) return;
      await showDoneSheet(
        context,
        title: priceProblem == null ? 'Item saved' : 'Saved, except the price',
        detail: priceProblem == null
            ? '${_name.text.trim()}\n${_p.code}'
            : '${_name.text.trim()}\n${_p.code}\n\n$priceProblem',
      );
      if (mounted) Navigator.of(context).pop(true);
    } on ApiException catch (e) {
      if (mounted) _showError(e.message);
    } catch (_) {
      if (mounted) _showError('The item could not be saved.');
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  Future<void> _toggleActive() async {
    final deactivate = _p.isActive;
    final ok = await showDialog<bool>(
      context: context,
      builder: (context) => AlertDialog(
        title: Text(deactivate ? 'Deactivate this item?' : 'Reactivate?'),
        content: Text(deactivate
            ? '${_p.name} stops being offered for sale. Its history and stock '
                'stay, and you can reactivate it later.'
            : '${_p.name} can be sold again.'),
        actions: [
          TextButton(
            onPressed: () => Navigator.of(context).pop(false),
            child: const Text('Cancel'),
          ),
          FilledButton(
            onPressed: () => Navigator.of(context).pop(true),
            child: Text(deactivate ? 'Deactivate' : 'Reactivate'),
          ),
        ],
      ),
    );
    if (ok != true || !mounted) return;
    final catalog = AppScope.of(context).catalog;
    setState(() => _busy = true);
    try {
      if (deactivate) {
        await catalog.archive(_p.uid);
      } else {
        await catalog.restore(_p.uid);
      }
      if (mounted) Navigator.of(context).pop(true);
    } on ApiException catch (e) {
      if (mounted) _showError(e.message);
    } catch (_) {
      if (mounted) _showError('That did not go through. Try again.');
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
    final unit = _p.baseUnitCode;
    return ListView(
      padding: const EdgeInsets.fromLTRB(20, 8, 20, 28),
      children: [
        SectionLabel(text: _p.code, trailing: _p.isActive ? null : 'INACTIVE'),
        const SizedBox(height: 12),
        HqField(label: 'Item name', required: true, controller: _name),
        const SizedBox(height: 16),
        HqField(
          label: 'Description',
          controller: _description,
          hint: 'Optional',
          maxLines: 2,
        ),
        const SizedBox(height: 16),
        PickTile(
          label: 'Department',
          icon: Icons.category_outlined,
          value: _department,
          placeholder: 'None',
          onTap: () async {
            final d = await pickDepartment(context, current: _department);
            if (d != null) setState(() => _department = d);
          },
          onClear: () => setState(() => _department = ''),
        ),
        const SizedBox(height: 24),
        const SectionLabel(text: 'BUYING AND SELLING'),
        const SizedBox(height: 12),
        PickTile(
          label: 'Supplier',
          icon: Icons.storefront_outlined,
          value: _supplierName,
          placeholder: 'None',
          onTap: () async {
            final s = await pickSupplier(context);
            if (s != null) {
              setState(() {
                _newSupplier = s;
                _supplierName = s.name;
                _supplierChanged = true;
              });
            }
          },
          onClear: () => setState(() {
            _newSupplier = null;
            _supplierName = null;
            _supplierChanged = true;
          }),
        ),
        const SizedBox(height: 16),
        HqField(
          label: 'Cost price per $unit',
          controller: _cost,
          hint: 'What you pay the supplier',
          keyboardType: const TextInputType.numberWithOptions(decimal: true),
          suffixText: _currency,
          helper: 'Stock already on hand keeps the cost it was received at.',
        ),
        if (widget.data.priceList != null) ...[
          const SizedBox(height: 16),
          HqField(
            label: 'Selling price per $unit',
            controller: _price,
            hint: 'Not priced yet',
            keyboardType: const TextInputType.numberWithOptions(decimal: true),
            suffixText: widget.data.priceList!.currency,
            helper: 'On ${widget.data.priceList!.name}. Pack prices are set '
                'in Pack sizes.',
          ),
        ],
        const SizedBox(height: 12),
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
          subtitle: Text(_vatable ? 'Taxed' : 'Exempt', style: HqText.tiny),
        ),
        const SizedBox(height: 22),
        FilledButton(
          onPressed: _busy || _name.text.trim().isEmpty ? null : _save,
          child: _busy
              ? const SizedBox(
                  width: 20,
                  height: 20,
                  child: CircularProgressIndicator(
                    strokeWidth: 2.2,
                    color: Colors.white,
                  ),
                )
              : const Text('Save changes'),
        ),
        const SizedBox(height: 10),
        TextButton(
          onPressed: _busy ? null : _toggleActive,
          style: TextButton.styleFrom(
            foregroundColor: _p.isActive ? HqColors.bad : HqColors.brand,
          ),
          child: Text(_p.isActive ? 'Deactivate item' : 'Reactivate item'),
        ),
      ],
    );
  }
}
