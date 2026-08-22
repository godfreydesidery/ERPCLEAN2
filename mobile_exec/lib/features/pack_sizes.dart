import 'package:flutter/material.dart';

import '../app/app_scope.dart';
import '../app/theme.dart';
import '../core/api/api_exception.dart';
import '../services/catalog_service.dart';
import '../widgets/async_view.dart';
import '../widgets/common.dart';
import '../widgets/kit.dart';
import 'product_picker.dart';

/// Choose a unit of measure from the company's list.
Future<UnitRef?> pickUnitRef(
  BuildContext context, {
  required List<UnitRef> units,
  UnitRef? current,
  String title = 'Choose a unit',
}) {
  return showModalBottomSheet<UnitRef>(
    context: context,
    isScrollControlled: true,
    backgroundColor: Colors.transparent,
    builder: (context) => DraggableScrollableSheet(
      initialChildSize: 0.6,
      minChildSize: 0.35,
      maxChildSize: 0.92,
      expand: false,
      builder: (context, controller) => Container(
        decoration: const BoxDecoration(
          color: HqColors.panel,
          borderRadius: BorderRadius.vertical(top: Radius.circular(22)),
        ),
        child: Column(
          children: [
            const SizedBox(height: 12),
            Container(
              width: 38,
              height: 4,
              decoration: BoxDecoration(
                color: HqColors.line2,
                borderRadius: BorderRadius.circular(2),
              ),
            ),
            Padding(
              padding: const EdgeInsets.fromLTRB(20, 16, 20, 12),
              child: Align(
                alignment: Alignment.centerLeft,
                child: Text(title, style: HqText.title),
              ),
            ),
            const Divider(height: 1),
            Expanded(
              child: ListView.separated(
                controller: controller,
                padding: const EdgeInsets.symmetric(horizontal: 20),
                itemCount: units.length,
                separatorBuilder: (_, _) => const Divider(height: 1),
                itemBuilder: (context, i) {
                  final u = units[i];
                  final selected = u.uid == current?.uid;
                  return ListTile(
                    contentPadding: EdgeInsets.zero,
                    title: Text(
                      u.name.isEmpty ? u.code : u.name,
                      style: TextStyle(
                        fontSize: 14.5,
                        fontWeight: FontWeight.w600,
                        color: selected ? HqColors.brand : HqColors.ink,
                      ),
                    ),
                    subtitle: Text(u.code, style: HqText.tiny),
                    trailing: selected
                        ? const Icon(Icons.check_circle_rounded,
                            size: 20, color: HqColors.brand)
                        : null,
                    onTap: () => Navigator.of(context).pop(u),
                  );
                },
              ),
            ),
          ],
        ),
      ),
    ),
  );
}

/// A pack size being entered but not yet saved: which unit, how many base units
/// it holds, and optionally what it sells for.
class PackDraft {
  PackDraft(this.unit, {String factor = '', String price = ''})
      : factorController = TextEditingController(text: factor),
        priceController = TextEditingController(text: price);

  final UnitRef unit;
  final TextEditingController factorController;
  final TextEditingController priceController;

  double? get factorValue => double.tryParse(factorController.text.trim());
  double? get priceValue => double.tryParse(priceController.text.trim());

  void dispose() {
    factorController.dispose();
    priceController.dispose();
  }
}

/// One editable pack row: "1 CTN = [24] PCS", and what a carton sells for.
class PackDraftCard extends StatelessWidget {
  const PackDraftCard({
    super.key,
    required this.draft,
    required this.baseCode,
    required this.currency,
    required this.showPrice,
    required this.onChanged,
    required this.onRemove,
    this.busy = false,
    this.onSave,
  });

  final PackDraft draft;
  final String baseCode;
  final String currency;
  final bool showPrice;
  final VoidCallback onChanged;
  final VoidCallback onRemove;

  /// Shown only on the screen that edits a saved product, where each row is
  /// written on its own.
  final VoidCallback? onSave;
  final bool busy;

  @override
  Widget build(BuildContext context) {
    final factor = draft.factorValue;

    return HqCard(
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              Expanded(
                child: Text(
                  draft.unit.name.isEmpty ? draft.unit.code : draft.unit.name,
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
                onPressed: busy ? null : onRemove,
                icon: const Icon(Icons.delete_outline_rounded, size: 19),
                color: HqColors.ink3,
                visualDensity: VisualDensity.compact,
              ),
            ],
          ),
          const SizedBox(height: 4),
          Row(
            crossAxisAlignment: CrossAxisAlignment.end,
            children: [
              Text('1 ${draft.unit.code}  =',
                  style: const TextStyle(
                    fontSize: 14,
                    fontWeight: FontWeight.w600,
                    color: HqColors.ink2,
                  )),
              const SizedBox(width: 10),
              SizedBox(
                width: 96,
                child: TextField(
                  controller: draft.factorController,
                  keyboardType:
                      const TextInputType.numberWithOptions(decimal: true),
                  onChanged: (_) => onChanged(),
                  textAlign: TextAlign.center,
                  style: const TextStyle(
                    fontSize: 17,
                    fontWeight: FontWeight.w700,
                    color: HqColors.ink,
                  ),
                  decoration: const InputDecoration(hintText: '0'),
                ),
              ),
              const SizedBox(width: 10),
              Padding(
                padding: const EdgeInsets.only(bottom: 12),
                child: Text(baseCode,
                    style: const TextStyle(
                      fontSize: 14,
                      fontWeight: FontWeight.w600,
                      color: HqColors.ink2,
                    )),
              ),
            ],
          ),
          if (factor != null && factor > 0)
            Text(
              'A ${draft.unit.code} takes ${_qty(factor)} $baseCode '
              'off the shelf.',
              style: HqText.tiny,
            )
          else
            Text(
              'Enter how many $baseCode are inside one ${draft.unit.code}.',
              style: HqText.tiny.copyWith(color: HqColors.warn),
            ),
          if (showPrice) ...[
            const SizedBox(height: 12),
            HqField(
              label: 'Price per ${draft.unit.code}',
              controller: draft.priceController,
              hint: 'Optional',
              keyboardType: const TextInputType.numberWithOptions(decimal: true),
              suffixText: currency,
              helper: 'Leave blank to sell at the $baseCode price times '
                  '${factor == null || factor <= 0 ? 'the pack size' : _qty(factor)}.',
            ),
          ],
          if (onSave != null) ...[
            const SizedBox(height: 12),
            FilledButton(
              onPressed: busy ? null : onSave,
              child: busy
                  ? const SizedBox(
                      width: 18,
                      height: 18,
                      child: CircularProgressIndicator(
                          strokeWidth: 2.2, color: Colors.white),
                    )
                  : const Text('Save this pack'),
            ),
          ],
        ],
      ),
    );
  }
}

/// Pack sizes for an item that already exists.
///
/// This is the screen that fixes a catalogue registered before pack sizes were
/// set up — the daily-adjustment problem. Nothing here moves stock: it declares
/// how many base units a carton holds, so that receiving and selling in cartons
/// can convert instead of being corrected by hand afterwards.
class PackSizesScreen extends StatefulWidget {
  const PackSizesScreen({super.key});

  @override
  State<PackSizesScreen> createState() => _PackSizesScreenState();
}

class _PackSizesScreenState extends State<PackSizesScreen> {
  ProductItem? _product;

  List<UnitRef> _units = const [];
  List<PackSize> _saved = const [];
  PriceListRef? _priceList;
  Map<String?, double> _prices = const {};

  final _basePrice = TextEditingController();
  final _drafts = <String, PackDraft>{};

  bool _loading = false;
  String? _error;
  String? _busyUnitUid;
  bool _busyBase = false;

  @override
  void dispose() {
    _basePrice.dispose();
    for (final d in _drafts.values) {
      d.dispose();
    }
    super.dispose();
  }

  Future<void> _pick() async {
    final p = await pickProduct(context);
    if (p == null || !mounted) return;
    setState(() {
      _product = p;
      _error = null;
    });
    await _load();
  }

  Future<void> _load() async {
    final p = _product;
    if (p == null) return;
    setState(() => _loading = true);

    final catalog = AppScope.of(context).catalog;
    try {
      final units = await catalog.units();
      final packs = await catalog.packs(p.uid);

      // Pricing is optional: a user without PRICELIST.VIEW still manages packs.
      PriceListRef? priceList;
      var prices = <String?, double>{};
      try {
        priceList = await catalog.defaultPriceList();
        if (priceList != null) {
          final rows = await catalog.prices(p.uid);
          prices = {
            for (final r in rows)
              if (r.priceListUid == priceList.uid) r.unitUid: r.amount,
          };
        }
      } catch (_) {
        priceList = null;
      }

      if (!mounted) return;
      for (final d in _drafts.values) {
        d.dispose();
      }
      _drafts.clear();
      for (final pack in packs) {
        _drafts[pack.unitUid] = PackDraft(
          UnitRef(
              uid: pack.unitUid, code: pack.unitCode, name: pack.unitName),
          factor: _qty(pack.factorToBase),
          price: prices[pack.unitUid] == null
              ? ''
              : _qty(prices[pack.unitUid]!),
        );
      }
      _basePrice.text = prices[null] == null ? '' : _qty(prices[null]!);

      setState(() {
        _units = units;
        _saved = packs;
        _priceList = priceList;
        _prices = prices;
        _loading = false;
        _error = null;
      });
    } on ApiException catch (e) {
      if (!mounted) return;
      setState(() {
        _loading = false;
        _error = e.message;
      });
    } catch (_) {
      if (!mounted) return;
      setState(() {
        _loading = false;
        _error = 'Could not load the pack sizes for this item.';
      });
    }
  }

  List<UnitRef> get _freeUnits {
    final taken = {_product?.baseUnitUid, ..._drafts.keys};
    return _units.where((u) => !taken.contains(u.uid)).toList();
  }

  Future<void> _addPack() async {
    final free = _freeUnits;
    if (free.isEmpty) {
      _snack('Every unit is already used on this item.', bad: true);
      return;
    }
    final chosen =
        await pickUnitRef(context, units: free, title: 'Which pack unit?');
    if (chosen == null || !mounted) return;
    setState(() => _drafts[chosen.uid] = PackDraft(chosen));
  }

  /// Writes one pack row: the factor, then its price if one was typed.
  Future<void> _savePack(String unitUid) async {
    final p = _product!;
    final draft = _drafts[unitUid]!;
    final factor = draft.factorValue;
    if (factor == null || factor <= 0) {
      _snack('Enter how many ${p.unit} are inside one ${draft.unit.code}.',
          bad: true);
      return;
    }

    setState(() => _busyUnitUid = unitUid);
    final catalog = AppScope.of(context).catalog;
    try {
      final existing =
          _saved.where((s) => s.unitUid == unitUid).firstOrNull;
      if (existing == null) {
        await catalog.addPack(
          productUid: p.uid,
          unitUid: unitUid,
          factorToBase: factor,
        );
      } else if (existing.factorToBase != factor) {
        await catalog.updatePack(
          productUid: p.uid,
          packUid: existing.uid,
          factorToBase: factor,
        );
      }

      final price = draft.priceValue;
      if (price != null && price > 0 && _priceList != null) {
        await catalog.setPrice(
          productUid: p.uid,
          priceListUid: _priceList!.uid,
          amount: price,
          currency: _priceList!.currency,
          unitUid: unitUid,
        );
      }

      if (!mounted) return;
      _snack('${draft.unit.code} saved: 1 ${draft.unit.code} = '
          '${_qty(factor)} ${p.unit}.');
      await _load();
    } on ApiException catch (e) {
      if (mounted) _snack(e.message, bad: true);
    } catch (_) {
      if (mounted) _snack('That pack size could not be saved.', bad: true);
    } finally {
      if (mounted) setState(() => _busyUnitUid = null);
    }
  }

  Future<void> _saveBasePrice() async {
    final p = _product!;
    final amount = double.tryParse(_basePrice.text.trim());
    if (amount == null || amount <= 0) {
      _snack('Enter a price per ${p.unit}.', bad: true);
      return;
    }
    setState(() => _busyBase = true);
    try {
      await AppScope.of(context).catalog.setPrice(
            productUid: p.uid,
            priceListUid: _priceList!.uid,
            amount: amount,
            currency: _priceList!.currency,
          );
      if (mounted) _snack('Price per ${p.unit} saved.');
    } on ApiException catch (e) {
      if (mounted) _snack(e.message, bad: true);
    } catch (_) {
      if (mounted) _snack('That price could not be saved.', bad: true);
    } finally {
      if (mounted) setState(() => _busyBase = false);
    }
  }

  Future<void> _removePack(String unitUid) async {
    final p = _product!;
    final existing = _saved.where((s) => s.unitUid == unitUid).firstOrNull;
    if (existing == null) {
      setState(() => _drafts.remove(unitUid)?.dispose());
      return;
    }

    final confirmed = await showDialog<bool>(
      context: context,
      builder: (context) => AlertDialog(
        title: Text('Remove ${existing.unitCode}?'),
        content: Text(
          'This item will no longer be bought or sold by '
          '${existing.unitCode}. Stock already on hand is not changed.',
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.of(context).pop(false),
            child: const Text('Keep it'),
          ),
          FilledButton(
            style: FilledButton.styleFrom(backgroundColor: HqColors.bad),
            onPressed: () => Navigator.of(context).pop(true),
            child: const Text('Remove'),
          ),
        ],
      ),
    );
    if (confirmed != true || !mounted) return;

    setState(() => _busyUnitUid = unitUid);
    try {
      await AppScope.of(context).catalog.removePack(p.uid, existing.uid);
      if (mounted) _snack('${existing.unitCode} removed.');
      await _load();
    } on ApiException catch (e) {
      if (mounted) _snack(e.message, bad: true);
    } finally {
      if (mounted) setState(() => _busyUnitUid = null);
    }
  }

  void _snack(String message, {bool bad = false}) {
    ScaffoldMessenger.of(context).showSnackBar(
      SnackBar(
        behavior: SnackBarBehavior.floating,
        backgroundColor: bad ? HqColors.bad : HqColors.ink,
        content: Text(message),
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    final session = AppScope.of(context).session;
    final p = _product;
    final currency = _priceList?.currency ?? 'TZS';

    return Scaffold(
      backgroundColor: HqColors.bg,
      appBar: AppBar(title: const Text('Pack sizes', style: HqText.title)),
      body: !session.can('PRODUCT.MANAGE')
          ? const NoPermission(code: 'PRODUCT.MANAGE')
          : ListView(
              padding: const EdgeInsets.fromLTRB(20, 8, 20, 28),
              children: [
                HqCard(
                  child: Row(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      const Icon(Icons.info_outline_rounded,
                          size: 19, color: HqColors.brand),
                      const SizedBox(width: 10),
                      Expanded(
                        child: Text(
                          'Tell the system how many pieces are in a carton, a '
                          'box, an outer. Then goods can be ordered by carton, '
                          'received by piece, and sold either way — with no '
                          'adjustment afterwards.',
                          style: HqText.body,
                        ),
                      ),
                    ],
                  ),
                ),
                const SizedBox(height: 18),
                ProductPickerTile(
                    product: p, onTap: _pick, label: 'Item'),
                if (p != null) ...[
                  if (_loading)
                    const Padding(
                      padding: EdgeInsets.symmetric(vertical: 26),
                      child: LinearProgressIndicator(minHeight: 2),
                    )
                  else if (_error != null) ...[
                    const SizedBox(height: 18),
                    HqCard(
                      child: Column(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: [
                          Text(_error!, style: HqText.body),
                          const SizedBox(height: 10),
                          OutlinedButton(
                            onPressed: _load,
                            child: const Text('Try again'),
                          ),
                        ],
                      ),
                    ),
                  ] else ...[
                    const SizedBox(height: 22),
                    const SectionLabel(text: 'COUNTED IN'),
                    const SizedBox(height: 10),
                    HqCard(
                      child: Column(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: [
                          FigureRow(
                            label: 'Stock is counted in',
                            value: p.unit,
                            emphasise: true,
                          ),
                          const SizedBox(height: 6),
                          Text(
                            'Every pack below converts to ${p.unit}. The '
                            'counting unit itself cannot be changed here — it '
                            'is what all existing stock is measured in.',
                            style: HqText.tiny,
                          ),
                          if (_priceList != null) ...[
                            const SizedBox(height: 14),
                            HqField(
                              label: 'Price per ${p.unit}',
                              controller: _basePrice,
                              hint: _prices[null] == null
                                  ? 'Not set'
                                  : 'Current price',
                              keyboardType: const TextInputType
                                  .numberWithOptions(decimal: true),
                              suffixText: currency,
                            ),
                            const SizedBox(height: 10),
                            OutlinedButton(
                              onPressed: _busyBase ? null : _saveBasePrice,
                              child: Text(_busyBase
                                  ? 'Saving…'
                                  : 'Save price per ${p.unit}'),
                            ),
                          ],
                        ],
                      ),
                    ),
                    const SizedBox(height: 22),
                    SectionLabel(
                      text: 'PACK SIZES',
                      trailing:
                          _drafts.isEmpty ? null : '${_drafts.length} SET',
                    ),
                    const SizedBox(height: 10),
                    if (_drafts.isEmpty)
                      HqCard(
                        child: Column(
                          children: [
                            const Icon(Icons.inventory_outlined,
                                size: 34, color: HqColors.ink3),
                            const SizedBox(height: 10),
                            Text(
                              'No pack sizes yet — this item can only be '
                              'handled one ${p.unit} at a time.',
                              style: HqText.body,
                              textAlign: TextAlign.center,
                            ),
                          ],
                        ),
                      )
                    else
                      for (final entry in _drafts.entries) ...[
                        PackDraftCard(
                          draft: entry.value,
                          baseCode: p.unit,
                          currency: currency,
                          showPrice: _priceList != null,
                          busy: _busyUnitUid == entry.key,
                          onChanged: () => setState(() {}),
                          onSave: () => _savePack(entry.key),
                          onRemove: () => _removePack(entry.key),
                        ),
                        const SizedBox(height: 10),
                      ],
                    const SizedBox(height: 6),
                    OutlinedButton.icon(
                      onPressed: _freeUnits.isEmpty ? null : _addPack,
                      icon: const Icon(Icons.add_rounded, size: 19),
                      label: Text(_drafts.isEmpty
                          ? 'Add a carton, box or outer'
                          : 'Add another pack size'),
                    ),
                  ],
                ],
                const SizedBox(height: 24),
              ],
            ),
    );
  }
}

/// "24" rather than "24.0"; keeps a decimal only when there is one.
String _qty(double v) =>
    v == v.roundToDouble() ? v.toStringAsFixed(0) : v.toString();
