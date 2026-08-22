import 'package:flutter/material.dart';

import '../app/app_scope.dart';
import '../app/theme.dart';
import '../core/api/api_exception.dart';
import '../services/catalog_service.dart';
import '../widgets/async_view.dart';
import '../widgets/common.dart';
import '../widgets/kit.dart';
import 'pack_sizes.dart';

/// Register a product against `/products`, with the pack sizes it is bought
/// and sold in.
///
/// An item registered with only a base unit can only ever be counted and sold
/// one way. That is the whole of the client's bulk-break problem: goods arrive
/// in cartons, are counted in pieces and are sold as both, and until each pack
/// exists on the product every one of those days ends in a manual adjustment.
/// So the pack sizes are asked for here, at registration, not left for someone
/// to remember to add on a desktop later.
class CreateItemScreen extends StatefulWidget {
  const CreateItemScreen({super.key});

  @override
  State<CreateItemScreen> createState() => _CreateItemScreenState();
}

class _CreateItemScreenState extends State<CreateItemScreen> {
  final _name = TextEditingController();
  final _code = TextEditingController();
  final _barcode = TextEditingController();
  final _price = TextEditingController();

  UnitRef? _unit;
  bool _vatable = true;
  bool _busy = false;

  /// Units the company actually has, loaded from `/units`. A hard-coded list
  /// cannot be used: the write needs each unit's uid, and which units exist is
  /// per company.
  List<UnitRef> _units = const [];
  bool _loadingUnits = true;
  String? _unitsError;

  /// Null when the company has no price list, or the signed-in user cannot see
  /// price lists. Pricing is then simply not offered — the item is still
  /// perfectly usable, and the POS falls back to base price × pack factor.
  PriceListRef? _priceList;

  final _packs = <PackDraft>[];

  @override
  void initState() {
    super.initState();
    _name.addListener(() => setState(() {}));
    _code.addListener(() => setState(() {}));
    WidgetsBinding.instance.addPostFrameCallback((_) => _load());
  }

  @override
  void dispose() {
    for (final c in [_name, _code, _barcode, _price]) {
      c.dispose();
    }
    for (final p in _packs) {
      p.dispose();
    }
    super.dispose();
  }

  Future<void> _load() async {
    if (!AppScope.of(context).session.can('PRODUCT.MANAGE')) {
      setState(() => _loadingUnits = false);
      return;
    }
    final catalog = AppScope.of(context).catalog;
    try {
      final units = await catalog.units();
      if (!mounted) return;
      setState(() {
        _units = units;
        _loadingUnits = false;
        _unitsError = units.isEmpty ? 'This company has no units set up.' : null;
      });
    } on ApiException catch (e) {
      if (!mounted) return;
      setState(() {
        _loadingUnits = false;
        _unitsError = e.message;
      });
    }

    // Pricing is a bonus, never a blocker — a failure here leaves the price
    // fields hidden and the rest of the form working.
    try {
      final list = await catalog.defaultPriceList();
      if (mounted) setState(() => _priceList = list);
    } catch (_) {
      // No price list available to this user; the form stays priceless.
    }
  }

  bool get _ready =>
      _name.text.trim().isNotEmpty && _unit != null && !_busy && !_loadingUnits;

  /// Units still free to use as a pack — the base unit and units already taken
  /// are excluded, which is exactly what the server enforces.
  List<UnitRef> get _availablePackUnits {
    final taken = {
      if (_unit != null) _unit!.uid,
      ..._packs.map((p) => p.unit.uid),
    };
    return _units.where((u) => !taken.contains(u.uid)).toList();
  }

  Future<void> _addPack() async {
    final free = _availablePackUnits;
    if (free.isEmpty) {
      _showError('Every unit is already used on this item.');
      return;
    }
    final chosen = await pickUnitRef(
      context,
      units: free,
      title: 'Which pack unit?',
    );
    if (chosen == null || !mounted) return;
    setState(() => _packs.add(PackDraft(chosen)));
  }

  Future<void> _submit() async {
    setState(() => _busy = true);
    final catalog = AppScope.of(context).catalog;
    final baseUnit = _unit!;

    // Anything that fails AFTER the product exists is reported by name. The
    // item is real at that point, and saying "created" while three pack sizes
    // silently did not save is how the catalogue drifts out of shape.
    final failures = <String>[];
    try {
      final created = await catalog.createProduct(
        name: _name.text.trim(),
        code: _code.text.trim().toUpperCase(),
        baseUnitUid: baseUnit.uid,
        vatable: _vatable,
      );

      final basePrice = double.tryParse(_price.text.trim());
      if (basePrice != null && basePrice > 0 && _priceList != null) {
        try {
          await catalog.setPrice(
            productUid: created.uid,
            priceListUid: _priceList!.uid,
            amount: basePrice,
            currency: _priceList!.currency,
          );
        } catch (_) {
          failures.add('the ${baseUnit.code} price');
        }
      }

      for (final draft in _packs) {
        final factor = draft.factorValue;
        if (factor == null || factor <= 0) {
          failures.add('${draft.unit.code} (no pack size entered)');
          continue;
        }
        try {
          await catalog.addPack(
            productUid: created.uid,
            unitUid: draft.unit.uid,
            factorToBase: factor,
          );
        } catch (_) {
          failures.add(draft.unit.code);
          continue;
        }

        final packPrice = draft.priceValue;
        if (packPrice != null && packPrice > 0 && _priceList != null) {
          try {
            await catalog.setPrice(
              productUid: created.uid,
              priceListUid: _priceList!.uid,
              amount: packPrice,
              currency: _priceList!.currency,
              unitUid: draft.unit.uid,
            );
          } catch (_) {
            failures.add('the ${draft.unit.code} price');
          }
        }
      }

      final barcode = _barcode.text.trim();
      if (barcode.isNotEmpty) {
        try {
          await catalog.addBarcode(
            productUid: created.uid,
            barcode: barcode,
            unitUid: baseUnit.uid,
          );
        } catch (_) {
          failures.add('the barcode');
        }
      }

      if (!mounted) return;
      await showDoneSheet(
        context,
        title: failures.isEmpty ? 'Item created' : 'Item created with gaps',
        detail: failures.isEmpty
            ? '${created.name}\n${created.code}\n'
                '${_soldByLine(baseUnit)}'
            : '${created.name}\n${created.code}\n\n'
                'Could not save: ${failures.join(', ')}.\n'
                'Add them from Pack sizes.',
      );
      if (mounted) Navigator.of(context).pop();
    } on ApiException catch (e) {
      if (mounted) _showError(e.message);
    } catch (_) {
      if (mounted) _showError('The item could not be created.');
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  String _soldByLine(UnitRef baseUnit) {
    final saved = _packs.where((p) => (p.factorValue ?? 0) > 0);
    if (saved.isEmpty) return 'Sold by ${baseUnit.code}';
    final units = [baseUnit.code, ...saved.map((p) => p.unit.code)];
    return 'Sold by ${units.join(', ')}';
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
    final currency = _priceList?.currency ?? 'TZS';

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
                  controller: _code,
                  hint: 'Leave blank to number it automatically',
                  helper: 'Must be unique within the company.',
                ),
                const SizedBox(height: 16),
                HqField(
                  label: 'Barcode',
                  controller: _barcode,
                  hint: 'Optional',
                  prefix: Icons.qr_code_scanner_rounded,
                ),
                const SizedBox(height: 24),
                const SectionLabel(text: 'HOW IT IS COUNTED'),
                const SizedBox(height: 12),
                if (_loadingUnits)
                  const Padding(
                    padding: EdgeInsets.symmetric(vertical: 16),
                    child: LinearProgressIndicator(minHeight: 2),
                  )
                else if (_unitsError != null)
                  _Notice(
                    icon: Icons.error_outline_rounded,
                    tint: HqColors.bad,
                    text: _unitsError!,
                  )
                else
                  _BaseUnitTile(
                    unit: _unit,
                    onTap: () async {
                      final chosen = await pickUnitRef(
                        context,
                        units: _units,
                        current: _unit,
                        title: 'Counted in which unit?',
                      );
                      if (chosen != null) setState(() => _unit = chosen);
                    },
                  ),
                const SizedBox(height: 8),
                Text(
                  'Choose the SMALLEST unit you ever sell — usually the piece. '
                  'Stock is counted in it, and every pack below converts to it.',
                  style: HqText.tiny,
                ),
                if (_priceList != null) ...[
                  const SizedBox(height: 16),
                  HqField(
                    label: 'Selling price'
                        '${_unit == null ? '' : ' per ${_unit!.code}'}',
                    controller: _price,
                    hint: 'Optional',
                    keyboardType:
                        const TextInputType.numberWithOptions(decimal: true),
                    suffixText: currency,
                  ),
                ],
                const SizedBox(height: 24),
                SectionLabel(
                  text: 'PACK SIZES',
                  trailing: _packs.isEmpty ? null : '${_packs.length} ADDED',
                ),
                const SizedBox(height: 10),
                if (_unit == null)
                  _Notice(
                    icon: Icons.info_outline_rounded,
                    tint: HqColors.ink3,
                    text: 'Choose the counting unit first, then add the '
                        'cartons and outers this item comes in.',
                  )
                else ...[
                  for (var i = 0; i < _packs.length; i++) ...[
                    PackDraftCard(
                      draft: _packs[i],
                      baseCode: _unit!.code,
                      currency: currency,
                      showPrice: _priceList != null,
                      onChanged: () => setState(() {}),
                      onRemove: () => setState(() {
                        _packs.removeAt(i).dispose();
                      }),
                    ),
                    const SizedBox(height: 10),
                  ],
                  OutlinedButton.icon(
                    onPressed: _availablePackUnits.isEmpty ? null : _addPack,
                    icon: const Icon(Icons.add_rounded, size: 19),
                    label: Text(_packs.isEmpty
                        ? 'Add a carton, box or outer'
                        : 'Add another pack size'),
                  ),
                ],
                const SizedBox(height: 24),
                const SectionLabel(text: 'TAX'),
                const SizedBox(height: 4),
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
                _Notice(
                  icon: Icons.info_outline_rounded,
                  tint: HqColors.ink3,
                  text: _priceList == null
                      ? 'Prices and opening stock are set separately — '
                          'receive the goods to bring stock in.'
                      : 'A pack with no price of its own sells at the '
                          '${_unit?.code ?? 'base'} price times its size.',
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

class _BaseUnitTile extends StatelessWidget {
  const _BaseUnitTile({required this.unit, required this.onTap});

  final UnitRef? unit;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        const Row(
          children: [
            Text(
              'Counted and stocked in',
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
              padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 14),
              decoration: BoxDecoration(
                borderRadius: BorderRadius.circular(HqRadii.sm),
                border: Border.all(
                  color: unit == null ? HqColors.line2 : HqColors.brand,
                  width: unit == null ? 1 : 1.5,
                ),
              ),
              child: Row(
                children: [
                  Icon(
                    Icons.straighten_rounded,
                    size: 20,
                    color: unit == null ? HqColors.ink3 : HqColors.brand,
                  ),
                  const SizedBox(width: 12),
                  Expanded(
                    child: Text(
                      unit?.label ?? 'Tap to choose a unit',
                      maxLines: 1,
                      overflow: TextOverflow.ellipsis,
                      style: TextStyle(
                        fontSize: 15,
                        fontWeight:
                            unit == null ? FontWeight.w400 : FontWeight.w600,
                        color: unit == null ? HqColors.ink3 : HqColors.ink,
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

class _Notice extends StatelessWidget {
  const _Notice({required this.icon, required this.tint, required this.text});

  final IconData icon;
  final Color tint;
  final String text;

  @override
  Widget build(BuildContext context) {
    return HqCard(
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Icon(icon, size: 19, color: tint),
          const SizedBox(width: 10),
          Expanded(child: Text(text, style: HqText.body)),
        ],
      ),
    );
  }
}
