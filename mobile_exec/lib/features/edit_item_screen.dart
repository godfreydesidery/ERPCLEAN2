import 'package:flutter/material.dart';

import '../app/app_scope.dart';
import '../app/format.dart' show groupDigits;
import '../app/theme.dart';
import '../core/api/api_exception.dart';
import '../core/session.dart';
import '../services/catalog_service.dart';
import '../widgets/async_view.dart';
import '../widgets/common.dart';
import '../widgets/kit.dart';
import 'create_item_screen.dart' show StockTrackingSwitch;
import 'master_pickers.dart';

/// Edit an existing item: name, description, department, supplier, cost
/// price, selling price, VAT and whether stock is tracked; or deactivate it.
///
/// Turning stock tracking off is refused by the server while the item still
/// has stock on hand anywhere in the company; its message is shown as is.
///
/// The "At (branch name)" section edits the item's entry on the ACTIVE
/// branch's menu (ADR-0073): whether it is sold there and its price there.
/// One branch at a time; the full branch table stays on the web.
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
    this.branch,
    this.branchMenu,
    this.branchMenuNote,
  });

  final ProductFull product;
  final PriceListRef? priceList;
  final double? basePrice;

  /// Null when the product has no supplier; "On file" when it has one this
  /// user's supplier list does not show.
  final String? supplierName;

  /// The branch the session is in; null hides the branch section.
  final BranchRef? branch;

  /// The item at [branch]; null when it could not be read ([branchMenuNote]
  /// says why).
  final BranchMenuEntry? branchMenu;
  final String? branchMenuNote;
}

class _EditItemScreenState extends State<EditItemScreen> {
  Future<_Loaded> _load() async {
    final scope = AppScope.of(context);
    final catalog = scope.catalog;
    final session = scope.session;
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

    // The branch menu is extra: a failure here never blocks editing the item.
    final branch = showBranchMenu(
      branchCount: session.branches.length,
      canView: session.can('PRODUCT.VIEW'),
    )
        ? session.activeBranch
        : null;
    BranchMenuEntry? menu;
    String? menuNote;
    if (branch != null) {
      try {
        menu = await catalog.branchMenu(product.uid, branch.uid);
        if (menu == null) menuNote = "This branch's menu could not be read.";
      } on ApiException catch (e) {
        menuNote = e.isForbidden
            ? "You cannot see this branch's menu."
            : "This branch's menu could not be read.";
      } catch (_) {
        menuNote = "This branch's menu could not be read.";
      }
    }

    return _Loaded(
      product: product,
      priceList: list,
      basePrice: basePrice,
      supplierName: supplierName,
      branch: branch,
      branchMenu: menu,
      branchMenuNote: menuNote,
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
  late bool _stockable = _p.isStockable;

  /// The item at the active branch, as the screen shows it.
  late bool _soldHere = widget.data.branchMenu?.soldHere ?? true;
  late final _branchPrice =
      TextEditingController(text: _fmt(widget.data.branchMenu?.price));

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
    for (final c in [_name, _description, _cost, _price, _branchPrice]) {
      c.dispose();
    }
    super.dispose();
  }

  String get _currency =>
      widget.data.priceList?.currency ?? _p.costCurrency ?? 'TZS';

  bool get _canEditBranchMenu =>
      AppScope.of(context).session.can('PRODUCT.BRANCH.ASSIGN');

  /// What to send for the branch section, or null when nothing changed or it
  /// is not editable. Throws [FormatException] for a price that is not a
  /// number.
  Map<String, dynamic>? _branchMenuChange() {
    final branch = widget.data.branch;
    final before = widget.data.branchMenu;
    if (branch == null || before == null || !_canEditBranchMenu) return null;
    final text = _branchPrice.text.trim();
    double? price;
    if (text.isNotEmpty) {
      price = _num(_branchPrice);
      if (price == null || price < 0) throw const FormatException();
    }
    return branchMenuBody(
      branchUid: branch.uid,
      before: before,
      soldHere: _soldHere,
      price: price,
    );
  }

  Future<void> _save() async {
    final catalog = AppScope.of(context).catalog;
    final Map<String, dynamic>? menuChange;
    try {
      menuChange = _branchMenuChange();
    } on FormatException {
      _showError('Enter a valid price here, or leave it empty for the '
          'company price.');
      return;
    }
    setState(() => _busy = true);
    try {
      final cost = _num(_cost);
      await catalog.updateProduct(
        _p,
        name: _name.text.trim(),
        description: _description.text.trim(),
        category: _department,
        vatable: _vatable,
        stockable: _stockable,
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

      // The branch's entry is its own record too.
      String? menuProblem;
      if (menuChange != null) {
        try {
          await catalog.setBranchMenu(_p.uid, menuChange);
        } on ApiException catch (e) {
          menuProblem = e.isForbidden
              ? 'You do not have permission to change what this branch sells.'
              : e.message;
        } catch (_) {
          menuProblem = 'The change at this branch was not saved.';
        }
      }

      if (!mounted) return;
      await showDoneSheet(
        context,
        title: savedTitle(
          priceFailed: priceProblem != null,
          branchFailed: menuProblem != null,
          branchName: widget.data.branch?.name ?? 'this branch',
        ),
        detail: [
          '${_name.text.trim()}\n${_p.code}',
          ?priceProblem,
          ?menuProblem,
        ].join('\n\n'),
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
        StockTrackingSwitch(
          value: _stockable,
          // A service is never stock-tracked (the server refuses it).
          onChanged:
              _p.isService ? null : (v) => setState(() => _stockable = v),
          subtitle: _p.isService
              ? 'Services are never stock-tracked.'
              : _p.isStockable && !_stockable
                  ? 'Only possible once its stock is zero — sales will stop '
                      'reducing stock'
                  : null,
        ),
        if (widget.data.branch != null) ...[
          const SizedBox(height: 20),
          BranchMenuSection(
            branchName: widget.data.branch!.name,
            soldHere: _soldHere,
            onSoldHereChanged:
                widget.data.branchMenu != null && _canEditBranchMenu
                    ? (v) => setState(() => _soldHere = v)
                    : null,
            priceController: _branchPrice,
            unit: unit,
            currency: widget.data.priceList?.currency ?? _currency,
            companyPrice: widget.data.basePrice,
            loaded: widget.data.branchMenu != null,
            note: widget.data.branchMenuNote ??
                (_canEditBranchMenu
                    ? null
                    : 'You can see, but not change, what this branch sells.'),
          ),
        ],
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

/// Whether the "At (branch name)" section is shown. Hidden for a user with
/// only one branch (owner ruling, 2026-11-24): there "Sold here" off would
/// just be a second way to deactivate the item.
bool showBranchMenu({required int branchCount, required bool canView}) =>
    canView && branchCount >= 2;

/// The done sheet's title after a save. The item itself has saved by the time
/// this is shown; the price and the branch entry are separate records and may
/// each have failed.
String savedTitle({
  required bool priceFailed,
  required bool branchFailed,
  required String branchName,
}) {
  if (priceFailed && branchFailed) {
    return 'Saved, except the price and at $branchName';
  }
  if (priceFailed) return 'Saved, except the price';
  if (branchFailed) return 'Saved, except at $branchName';
  return 'Item saved';
}

/// "At (branch name)": whether the item is sold at the active branch, and its
/// price there (ADR-0073). An empty price means the company price applies.
///
/// [onSoldHereChanged] null shows the section read-only. When [loaded] is
/// false only the title and [note] are shown: the switch would be a guess.
class BranchMenuSection extends StatelessWidget {
  const BranchMenuSection({
    super.key,
    required this.branchName,
    required this.soldHere,
    required this.onSoldHereChanged,
    required this.priceController,
    required this.unit,
    required this.currency,
    this.companyPrice,
    this.note,
    this.loaded = true,
  });

  final String branchName;
  final bool soldHere;
  final ValueChanged<bool>? onSoldHereChanged;
  final TextEditingController priceController;
  final String unit;
  final String currency;

  /// The company selling price per base unit, shown as the field's hint.
  final double? companyPrice;
  final String? note;
  final bool loaded;

  String get _hint {
    final p = companyPrice;
    if (p == null) return 'Company price';
    final decimals = p == p.roundToDouble() ? 0 : 2;
    return 'Company price ${groupDigits(p, decimals: decimals)}';
  }

  @override
  Widget build(BuildContext context) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        SectionLabel(text: 'At $branchName'),
        if (note != null)
          Padding(
            padding: const EdgeInsets.only(bottom: 4),
            child: Text(note!, style: HqText.tiny),
          ),
        if (loaded) ...[
          SwitchListTile.adaptive(
            value: soldHere,
            onChanged: onSoldHereChanged,
            contentPadding: EdgeInsets.zero,
            activeThumbColor: HqColors.brand,
            title: const Text(
              'Sold here',
              style: TextStyle(
                fontSize: 15,
                fontWeight: FontWeight.w600,
                color: HqColors.ink,
              ),
            ),
            subtitle: Text(
              soldHere
                  ? 'On the menu at this branch'
                  : 'Not offered at this branch',
              style: HqText.tiny,
            ),
          ),
          const SizedBox(height: 8),
          HqField(
            label: 'Price here per $unit',
            controller: priceController,
            readOnly: onSoldHereChanged == null,
            hint: _hint,
            keyboardType: const TextInputType.numberWithOptions(decimal: true),
            suffixText: currency,
            helper: 'Empty = company price',
          ),
        ],
      ],
    );
  }
}
