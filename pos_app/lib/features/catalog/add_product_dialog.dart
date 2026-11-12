import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../app/theme.dart';
import '../../core/api/api_exception.dart';
import '../../models/catalog.dart';
import '../../models/restaurant.dart';
import '../../state/app_controller.dart';
import '../../state/providers.dart';
import '../../widgets/ui.dart';

/// Adds a product from the till (Tulia 2026-11-02): name, selling price,
/// department, unit, VAT and an optional barcode — the HQ app's new-item flow.
/// Only offered to someone holding PRODUCT.MANAGE; the server checks it again.
///
/// In a restaurant the department decides where the item's order slip prints
/// (Tulia 2026-11-12): the dialog shows that station, and a NEW department must
/// be given one, or every item in it would print at the default station.
/// Moving an existing department is done in the ERP, not here.
/// Returns the new product's name, or null when nothing was added.
Future<String?> showAddProductDialog(BuildContext context) => showDialog<String>(
      context: context,
      barrierDismissible: false,
      builder: (_) => const _AddProductDialog(),
    );

class _AddProductDialog extends ConsumerStatefulWidget {
  const _AddProductDialog();
  @override
  ConsumerState<_AddProductDialog> createState() => _AddProductDialogState();
}

class _AddProductDialogState extends ConsumerState<_AddProductDialog> {
  final _name = TextEditingController();
  final _price = TextEditingController();
  final _department = TextEditingController();
  final _barcode = TextEditingController();
  final _code = TextEditingController();
  List<Unit> _units = const [];
  Unit? _unit;
  bool _vatable = true;
  bool _stockable = true;
  List<String> _departments = const [];
  List<PrepStation> _stations = const [];
  List<CategoryRoute> _routes = const [];
  String? _stationUid;
  ({String uid, String currency, bool includesVat})? _priceList;
  bool _priceListKnown = false;
  bool _busy = false;
  String? _error;

  @override
  void initState() {
    super.initState();
    final app = ref.read(appControllerProvider);
    _units = app.unitsByUid.values.toList()..sort((a, b) => a.name.compareTo(b.name));
    _unit = _defaultUnit(_units);
    _load();
  }

  @override
  void dispose() {
    for (final c in [_name, _price, _department, _barcode, _code]) {
      c.dispose();
    }
    super.dispose();
  }

  /// A piece if the company has one, else the first unit.
  static Unit? _defaultUnit(List<Unit> units) {
    for (final u in units) {
      final k = '${u.code} ${u.name}'.toLowerCase();
      if (k.contains('pcs') || k.contains('piece') || k.contains('each')) return u;
    }
    return units.isEmpty ? null : units.first;
  }

  Future<void> _load() async {
    final companyId = ref.read(appControllerProvider).context!.companyId;
    final catalog = ref.read(catalogServiceProvider);
    try {
      final depts = await catalog.listCategories(companyId);
      if (mounted) setState(() => _departments = depts);
    } catch (_) {}
    // Kitchen/bar stations: none (a shop) hides "Prints at" altogether.
    try {
      final restaurant = ref.read(restaurantServiceProvider);
      final stations = await restaurant.listStations();
      final active = [for (final st in stations) if (st.active) st]
        ..sort((a, b) => a.name.toLowerCase().compareTo(b.name.toLowerCase()));
      final routes = active.isEmpty ? <CategoryRoute>[] : await restaurant.listCategoryRoutes();
      if (mounted) {
        setState(() {
          _stations = active;
          _routes = routes;
        });
      }
    } catch (_) {}
    try {
      final pl = await catalog.defaultPriceList(companyId);
      if (mounted) {
        setState(() {
          _priceList = pl;
          _priceListKnown = true;
        });
      }
    } catch (_) {
      if (mounted) setState(() => _priceListKnown = true);
    }
    if (_units.isEmpty) {
      try {
        final units = await catalog.listUnits(companyId);
        if (mounted) {
          setState(() {
            _units = units..sort((a, b) => a.name.compareTo(b.name));
            _unit = _defaultUnit(_units);
          });
        }
      } catch (_) {}
    }
  }

  double get _amount => double.tryParse(_price.text.trim()) ?? 0;

  /// The route of the department as typed, or null (blank or new).
  CategoryRoute? get _route => CategoryRoute.find(_routes, _department.text);

  /// A department the branch has not routed yet: it needs a station.
  bool get _needsStation =>
      _stations.isNotEmpty && _department.text.trim().isNotEmpty && _route == null;

  /// Where unrouted items print: the default station, else the first one.
  String? get _defaultStationName {
    if (_stations.isEmpty) return null;
    for (final st in _stations) {
      if (st.isDefault) return st.name;
    }
    return _stations.first.name;
  }

  String? _stationNameOf(CategoryRoute r) {
    if (r.stationName != null && r.stationName!.isNotEmpty) return r.stationName;
    for (final st in _stations) {
      if (st.uid == r.stationUid) return st.name;
    }
    return null;
  }

  bool get _valid =>
      _name.text.trim().isNotEmpty &&
      _amount > 0 &&
      _unit != null &&
      (!_needsStation || _stationUid != null) &&
      !_busy;

  Future<void> _save() async {
    if (!_valid) return;
    final app = ref.read(appControllerProvider);
    final catalog = ref.read(catalogServiceProvider);
    final name = _name.text.trim();
    final department = _department.text.trim();
    final routeTo = _needsStation ? _stationUid : null;
    setState(() {
      _busy = true;
      _error = null;
    });
    String uid;
    try {
      uid = await catalog.createProduct(
        companyUid: app.context!.companyUid,
        name: name,
        baseUnitUid: _unit!.uid,
        code: _code.text,
        category: _department.text,
        vatable: _vatable,
        stockable: _stockable,
      );
    } on ApiException catch (e) {
      if (mounted) {
        setState(() {
          _busy = false;
          _error = e.message;
        });
      }
      return;
    }
    // The product exists from here on: whatever fails next is reported, never
    // retried as a second product.
    final problems = <String>[];
    if (routeTo != null) {
      try {
        await ref.read(restaurantServiceProvider).setCategoryRoute(department, routeTo);
      } on ApiException catch (e) {
        problems.add('"$department" will print at the default station until a manager sets it '
            'in Restaurant Setup (${e.message})');
      }
    }
    final pl = _priceList;
    if (pl == null) {
      problems.add('no price list was found, so it has no price yet');
    } else {
      try {
        await catalog.setPrice(uid, priceListUid: pl.uid, amount: _amount, currency: pl.currency);
      } on ApiException catch (e) {
        problems.add('the price was not saved (${e.message})');
      }
    }
    if (_barcode.text.trim().isNotEmpty) {
      try {
        await catalog.addBarcode(uid, _barcode.text);
      } on ApiException catch (e) {
        problems.add('the barcode was not saved (${e.message})');
      }
    }
    if (!mounted) return;
    if (problems.isNotEmpty) {
      await showDialog<void>(
        context: context,
        builder: (context) => AlertDialog(
          title: Text('$name was added'),
          content: Text('But ${problems.join(', and ')}. Fix it in the ERP under Products.'),
          actions: [
            OrbixButton(label: 'OK', onPressed: () => Navigator.pop(context)),
          ],
        ),
      );
      if (!mounted) return;
    }
    Navigator.pop(context, name);
  }

  /// "Juices · Barista" when the department already prints somewhere.
  String _chipLabel(String department) {
    final r = CategoryRoute.find(_routes, department);
    final at = r == null ? null : _stationNameOf(r);
    return at == null ? department : '$department · $at';
  }

  static const _note = TextStyle(color: AppColors.ink3, fontSize: 12);

  /// Where the item's order slip will print.
  List<Widget> _printsAt(String? routedTo) {
    if (_department.text.trim().isEmpty) {
      return [
        Padding(
          padding: const EdgeInsets.only(top: 6),
          child: Text('No department: orders print at ${_defaultStationName ?? 'the default station'}.',
              style: _note),
        ),
      ];
    }
    if (!_needsStation) {
      return [
        Padding(
          padding: const EdgeInsets.only(top: 6),
          child: Text(
              'Orders print at ${routedTo ?? 'the default station'}, like the rest of this department. '
              'To change it, use Restaurant Setup in the ERP.',
              style: _note),
        ),
      ];
    }
    return [
      const SizedBox(height: 10),
      const Text('Prints at', style: TextStyle(fontWeight: FontWeight.w600)),
      const SizedBox(height: 6),
      Wrap(
        spacing: 6,
        runSpacing: 6,
        children: [
          for (final st in _stations)
            ChoiceChip(
              label: Text(st.name),
              selected: _stationUid == st.uid,
              onSelected: (_) => setState(() => _stationUid = st.uid),
            ),
        ],
      ),
      const Padding(
        padding: EdgeInsets.only(top: 4),
        child: Text('A new department: choose where its orders print.', style: _note),
      ),
    ];
  }

  @override
  Widget build(BuildContext context) {
    final typed = _department.text.trim().toLowerCase();
    final suggestions = _departments
        .where((d) => typed.isEmpty || d.toLowerCase().contains(typed))
        .where((d) => d.toLowerCase() != typed)
        .take(8)
        .toList();
    final route = _route;
    final routedTo = route == null ? null : _stationNameOf(route);
    final pl = _priceList;
    final priceNote = !_priceListKnown
        ? null
        : pl == null
            ? 'No price list in the ERP: the product is added without a price.'
            : pl.includesVat
                ? 'Selling price in ${pl.currency}, VAT included.'
                : 'Selling price in ${pl.currency}, before VAT.';
    return AlertDialog(
      shape: RoundedRectangleBorder(borderRadius: AppRadii.brLg),
      title: const Text('Add a product'),
      content: SizedBox(
        width: 420,
        child: SingleChildScrollView(
          child: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              OrbixField(
                  label: 'Name',
                  controller: _name,
                  autofocus: true,
                  onChanged: (_) => setState(() {})),
              const SizedBox(height: 12),
              OrbixField(
                label: 'Selling price',
                controller: _price,
                keyboardType: const TextInputType.numberWithOptions(decimal: true),
                inputFormatters: [decimalInputFormatter],
                onChanged: (_) => setState(() {}),
              ),
              if (priceNote != null)
                Padding(
                  padding: const EdgeInsets.only(top: 4),
                  child: Text(priceNote,
                      style: const TextStyle(color: AppColors.ink3, fontSize: 12)),
                ),
              const SizedBox(height: 12),
              OrbixField(
                  label: 'Department',
                  hint: 'e.g. Drinks',
                  controller: _department,
                  onChanged: (_) => setState(() {})),
              if (suggestions.isNotEmpty)
                Padding(
                  padding: const EdgeInsets.only(top: 6),
                  child: Wrap(
                    spacing: 6,
                    runSpacing: 6,
                    children: [
                      for (final d in suggestions)
                        ActionChip(
                          label: Text(_chipLabel(d)),
                          onPressed: () => setState(() => _department.text = d),
                        ),
                    ],
                  ),
                ),
              if (_stations.isNotEmpty) ..._printsAt(routedTo),
              const SizedBox(height: 12),
              DropdownButtonFormField<Unit>(
                initialValue: _unit,
                isExpanded: true,
                decoration: const InputDecoration(labelText: 'Sold by'),
                items: [
                  for (final u in _units)
                    DropdownMenuItem(value: u, child: Text(u.name, overflow: TextOverflow.ellipsis)),
                ],
                onChanged: (u) => setState(() => _unit = u),
              ),
              SwitchListTile(
                contentPadding: EdgeInsets.zero,
                title: const Text('Charge VAT'),
                subtitle: Text(_vatable ? 'Standard rate' : 'Exempt'),
                value: _vatable,
                onChanged: (v) => setState(() => _vatable = v),
              ),
              SwitchListTile(
                contentPadding: EdgeInsets.zero,
                title: const Text('Track stock'),
                subtitle: const Text('Turn off for dishes made to order'),
                value: _stockable,
                onChanged: (v) => setState(() => _stockable = v),
              ),
              OrbixField(label: 'Barcode (optional)', controller: _barcode),
              const SizedBox(height: 12),
              OrbixField(
                  label: 'Code (optional)', hint: 'Left blank, the ERP numbers it', controller: _code),
              if (_error != null) ...[
                const SizedBox(height: 10),
                Text(_error!, style: const TextStyle(color: AppColors.danger, fontSize: 12.5)),
              ],
            ],
          ),
        ),
      ),
      actions: [
        TextButton(
            onPressed: _busy ? null : () => Navigator.pop(context), child: const Text('Cancel')),
        OrbixButton(label: 'Add product', busy: _busy, onPressed: _valid ? _save : null),
      ],
    );
  }
}
