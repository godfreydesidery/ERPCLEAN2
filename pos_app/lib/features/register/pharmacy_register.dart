import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../app/theme.dart';
import '../../core/api/api_exception.dart';
import '../../core/barcode.dart';
import '../../core/money.dart';
import '../../models/catalog.dart';
import '../../state/app_controller.dart';
import '../../state/cart_controller.dart';
import '../../state/catalog_cache.dart';
import '../../state/providers.dart';
import '../../state/stock_cache.dart';
import '../../widgets/ui.dart';
import '../payment/payment_sheet.dart';
import 'pickers.dart';

/// Pharmacy register: a dispensing line table with an Rx/patient header. Shares
/// the payment/receipt/session spine; the Rx context rides on the sale notes.
class PharmacyRegister extends ConsumerStatefulWidget {
  const PharmacyRegister({super.key});
  @override
  ConsumerState<PharmacyRegister> createState() => _PharmacyRegisterState();
}

class _PharmacyRegisterState extends ConsumerState<PharmacyRegister> {
  final _search = TextEditingController();
  final _searchFocus = FocusNode();
  final _prescriber = TextEditingController();
  final _rxNo = TextEditingController();

  Catalogue get _cache => ref.read(catalogProvider);
  StockCache get _stock => ref.read(stockCacheProvider);
  String get _companyId => ref.read(appControllerProvider).context!.companyId;
  String get _currency => ref.read(cartProvider).currency;

  /// Best-effort branch on-hand refresh so a dispensed line can warn when the
  /// drug is short before checkout (repaints when it lands).
  void _refreshStock(String q) {
    _stock.refreshFor(q).then((_) {
      if (mounted) setState(() {});
    });
  }

  @override
  void initState() {
    super.initState();
    _load();
  }

  void _load() {
    // No catalogue preload — products/prices are fetched fresh per action so the
    // till never shows stale data.
    _searchFocus.requestFocus();
  }

  @override
  void dispose() {
    _search.dispose();
    _searchFocus.dispose();
    _prescriber.dispose();
    _rxNo.dispose();
    super.dispose();
  }

  void _syncNotes() {
    final cart = ref.read(cartProvider);
    final parts = <String>[];
    if ((cart.customer?.displayName ?? '').isNotEmpty && !(cart.customer?.isWalkIn ?? true)) {
      parts.add('Patient: ${cart.customer!.displayName}');
    }
    if (_prescriber.text.trim().isNotEmpty) parts.add('Prescriber: ${_prescriber.text.trim()}');
    if (_rxNo.text.trim().isNotEmpty) parts.add('Rx: ${_rxNo.text.trim()}');
    ref.read(cartProvider.notifier).setNotes(parts.isEmpty ? null : parts.join(' | '));
  }

  /// Add a line. [saleUnit] overrides the product's base unit — used when a
  /// scanned pack barcode names its own unit (a box rather than a strip).
  void _add(Product p,
      {double? fixedQty, double? overridePrice, SaleUnit? saleUnit}) {
    final app = ref.read(appControllerProvider);
    final base = app.unitsByUid[p.baseUnitUid];
    final chosen = saleUnit ?? (base == null ? null : SaleUnit(base, 1));
    if (chosen == null) {
      showToast(context, '${p.name}: no usable unit.');
      return;
    }
    final cart = ref.read(cartProvider.notifier);
    cart.addProduct(p, chosen.unit,
        unitFactor: chosen.factor,
        fixedQuantity: fixedQty,
        overridePrice: overridePrice);
    final id = ref.read(cartProvider).selectedId;
    if (overridePrice == null && id != null) {
      _priceLine(id, p, chosen);
    }
  }

  /// Patch a line's preview price for the unit it is being sold in — an explicit
  /// pack price where one is set, otherwise `base × factor`. Same resolution
  /// order as the server, so the preview matches the authoritative price.
  void _priceLine(String lineId, Product p, SaleUnit unit) {
    final app = ref.read(appControllerProvider);
    final cart = ref.read(cartProvider.notifier);
    _cache.previewPrice(p.uid, _currency, unit: unit).then((pp) {
      if (pp != null && mounted) {
        cart.setLinePrice(
            lineId,
            app.grossUnitPrice(pp.amount, p.vatStatus,
                vatInclusive: pp.vatInclusive));
      }
    });
  }

  /// Open the unit picker for a line and apply the choice.
  Future<void> _pickUnit(CartLine line) async {
    final app = ref.read(appControllerProvider);
    final units = await _cache.sellableUnits(line.product, app.unitsByUid);
    if (!mounted) return;
    if (units.length < 2) {
      showToast(context, '${line.product.name} is only sold in ${line.unit.name}.');
      return;
    }
    final picked = await showUnitPicker(context,
        productName: line.product.name,
        units: units,
        currentUnitId: line.unit.id);
    if (picked == null || !mounted) return;
    ref
        .read(cartProvider.notifier)
        .setLineUnit(line.localId, picked.unit, picked.factor);
    // setLineUnit may have merged this line into an existing one — re-price the
    // line that now holds the selection.
    final id = ref.read(cartProvider).selectedId;
    if (id != null) _priceLine(id, line.product, picked);
  }

  Future<void> _onSearch(String raw) async {
    final v = raw.trim();
    if (v.isEmpty) return;
    _refreshStock(v);
    // Barcode lookup only for actual scans; a typed drug name always 404s here.
    // The product is fetched fresh by uid (no local catalogue).
    if (looksLikeBarcode(v)) {
      try {
        final bc = await ref.read(catalogServiceProvider).lookupBarcode(_companyId, v);
        if (!mounted) return;
        final p = await _cache.productByUid(bc.productUid);
        if (!mounted) return;
        if (p != null) {
          // Embedded-PRICE labels carry an amount the server cannot honour — adding
          // the line would charge the catalogue price. Refuse it (weight is fine).
          if (bc.valueKind == 'PRICE' || bc.derivedAmount != null) {
            showToast(context,
                "Price-embedded labels aren't supported yet — enter ${p.name} manually.");
            _reset();
            return;
          }
          // A barcode may address a pack (a box) rather than the base unit.
          // Honour it when it is a configured unit for this product.
          SaleUnit? scanned;
          final uomId = bc.uomId;
          if (uomId != null) {
            scanned = await _cache.sellableUnitById(
                p, uomId, ref.read(appControllerProvider).unitsByUid);
            if (!mounted) return;
          }
          _add(p, fixedQty: bc.derivedQuantity, saleUnit: scanned);
          _reset();
          return;
        }
      } on ApiException catch (e) {
        if (!e.isNotFound && mounted) showToast(context, e.message);
      }
      if (!mounted) return;
    }
    // Fresh server search — an exact product-code match wins, else the first hit.
    List<Product> hits;
    try {
      hits = await ref
          .read(catalogServiceProvider)
          .searchProducts(_companyId, q: v, size: 10);
    } catch (_) {
      hits = const [];
    }
    if (!mounted) return;
    Product? exact;
    for (final p in hits) {
      if (p.code.toLowerCase() == v.toLowerCase()) {
        exact = p;
        break;
      }
    }
    final pick = exact ?? (hits.isNotEmpty ? hits.first : null);
    if (pick != null) {
      _add(pick);
      _reset();
    } else {
      showToast(context, 'No match for "$v".');
    }
  }

  void _reset() {
    _search.clear();
    _searchFocus.requestFocus();
  }

  /// Small red tag on a dispensed line when the branch on-hand is known and
  /// below the quantity being dispensed — a heads-up before checkout rather than
  /// a rejection at Pay. Silent when stock is unknown or sufficient.
  Widget _shortStockTag(CartLine line) {
    final oh = _stock.levelForProduct(line.product.id)?.sellable;
    // On-hand is in BASE units, so a pack line is checked against its base
    // equivalent (2 boxes of 20 needs 40), not the pack count.
    if (oh == null || oh >= line.baseQuantity) return const SizedBox.shrink();
    final base = line.unitFactor == 1 ? '' : ' ${line.product.baseUnitCode ?? ''}';
    final label = oh <= 0
        ? 'out of stock'
        : 'only ${formatAmount(oh, decimals: oh % 1 == 0 ? 0 : 2)}$base left';
    return Padding(
      padding: const EdgeInsets.only(left: 6),
      child: Container(
        padding: const EdgeInsets.symmetric(horizontal: 6, vertical: 1),
        decoration: BoxDecoration(
          color: AppColors.dangerSoft,
          borderRadius: AppRadii.brPill,
          border: Border.all(color: const Color(0xFFFCA5A5)),
        ),
        child: Text(label,
            style: const TextStyle(
                fontSize: 10,
                fontWeight: FontWeight.w700,
                color: AppColors.danger)),
      ),
    );
  }

  Future<void> _pay() async {
    if (ref.read(cartProvider).isEmpty) {
      showToast(context, 'Add an item first.');
      return;
    }
    _syncNotes();
    await openPaymentSheet(context, ref);
    if (mounted) {
      _prescriber.clear();
      _rxNo.clear();
      _searchFocus.requestFocus();
    }
  }

  @override
  Widget build(BuildContext context) {
    return Row(
      children: [
        Expanded(child: _main()),
        SizedBox(width: 360, child: _side()),
      ],
    );
  }

  Widget _main() {
    return Padding(
      padding: const EdgeInsets.all(16),
      child: Column(
        children: [
          _rxBar(),
          const SizedBox(height: 12),
          Container(
            decoration: BoxDecoration(
              color: AppColors.panel,
              borderRadius: AppRadii.brLg,
              border: Border.all(color: AppColors.line2),
            ),
            padding: const EdgeInsets.only(left: 14, right: 4),
            child: Row(
              children: [
                const Icon(Icons.qr_code_scanner, color: AppColors.ink3, size: 20),
                const SizedBox(width: 10),
                Expanded(
                  child: TextField(
                    controller: _search,
                    focusNode: _searchFocus,
                    decoration: const InputDecoration(
                      border: InputBorder.none,
                      enabledBorder: InputBorder.none,
                      focusedBorder: InputBorder.none,
                      filled: false,
                      hintText: 'Scan or search a drug…',
                      contentPadding: EdgeInsets.symmetric(vertical: 14),
                    ),
                    onSubmitted: _onSearch,
                  ),
                ),
              ],
            ),
          ),
          const SizedBox(height: 12),
          Expanded(child: _lineTable()),
        ],
      ),
    );
  }

  Widget _rxBar() {
    final cart = ref.watch(cartProvider);
    return Container(
      padding: const EdgeInsets.all(12),
      decoration: BoxDecoration(
        color: AppColors.panel,
        borderRadius: AppRadii.brLg,
        border: Border.all(color: AppColors.line),
      ),
      child: Row(
        children: [
          Expanded(
            child: InkWell(
              borderRadius: AppRadii.brSm,
              onTap: () => showCustomerPicker(context, ref),
              child: Container(
                padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 10),
                decoration: BoxDecoration(
                    color: AppColors.panel2,
                    borderRadius: AppRadii.brSm,
                    border: Border.all(color: AppColors.line)),
                child: Row(
                  children: [
                    const Icon(Icons.personal_injury_outlined,
                        size: 18, color: AppColors.brand),
                    const SizedBox(width: 8),
                    Expanded(
                      child: Column(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: [
                          const Text('Patient',
                              style: TextStyle(fontSize: 11, color: AppColors.ink3)),
                          Text(
                              cart.customer?.isWalkIn ?? true
                                  ? 'Walk-in'
                                  : cart.customer!.displayName,
                              maxLines: 1,
                              overflow: TextOverflow.ellipsis,
                              style: const TextStyle(fontWeight: FontWeight.w600)),
                        ],
                      ),
                    ),
                  ],
                ),
              ),
            ),
          ),
          const SizedBox(width: 10),
          Expanded(
            child: OrbixField(
                controller: _prescriber, hint: 'Prescriber', label: null),
          ),
          const SizedBox(width: 10),
          SizedBox(
            width: 130,
            child: OrbixField(controller: _rxNo, hint: 'Rx #'),
          ),
        ],
      ),
    );
  }

  Widget _lineTable() {
    final cart = ref.watch(cartProvider);
    final ctrl = ref.read(cartProvider.notifier);
    if (cart.lines.isEmpty) {
      return _empty('Scan or search to dispense');
    }
    return Container(
      decoration: BoxDecoration(
          color: AppColors.panel, border: Border.all(color: AppColors.line)),
      child: ListView.separated(
        itemCount: cart.lines.length,
        separatorBuilder: (_, _) => const Divider(height: 1, color: AppColors.line),
        itemBuilder: (context, i) {
          final l = cart.lines[i];
          return Padding(
            padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 8),
            child: Row(
              children: [
                Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Row(
                        children: [
                          Flexible(
                            child: Text(l.product.name,
                                maxLines: 1,
                                overflow: TextOverflow.ellipsis,
                                style: const TextStyle(fontWeight: FontWeight.w600)),
                          ),
                          if (l.product.restrictedKind.isRestricted) ...[
                            const SizedBox(width: 6),
                            const Icon(Icons.warning_amber,
                                size: 14, color: AppColors.warn),
                          ],
                          if (!l.voided) _shortStockTag(l),
                        ],
                      ),
                      // The unit shown is the LINE's unit, not the product's
                      // base unit — a box line must not read as a strip. Tap to
                      // switch between the base unit and any configured pack.
                      InkWell(
                        onTap: l.voided ? null : () => _pickUnit(l),
                        child: Row(
                          mainAxisSize: MainAxisSize.min,
                          children: [
                            Text('${l.product.code}  ·  ${l.unit.name}',
                                style: TextStyle(
                                    fontSize: 12,
                                    fontWeight: l.unitFactor == 1
                                        ? FontWeight.w400
                                        : FontWeight.w700,
                                    color: l.unitFactor == 1
                                        ? AppColors.ink3
                                        : AppColors.brand)),
                            const Icon(Icons.arrow_drop_down,
                                size: 14, color: AppColors.ink3),
                          ],
                        ),
                      ),
                    ],
                  ),
                ),
                _qtyStepper(l, ctrl),
                SizedBox(
                  width: 108,
                  child: NumText(formatAmount(l.previewGross),
                      style: numStyle(weight: FontWeight.w700)),
                ),
                IconButton(
                  onPressed: () => ctrl.removeLine(l.localId),
                  icon: const Icon(Icons.close, size: 16, color: AppColors.ink3),
                ),
              ],
            ),
          );
        },
      ),
    );
  }

  Widget _qtyStepper(CartLine l, CartController ctrl) {
    return Row(
      children: [
        _qbtn(Icons.remove, () => ctrl.addQuantity(l.localId, -1)),
        SizedBox(
          width: 34,
          child: Text(formatAmount(l.quantity, decimals: l.unit.fractional ? 2 : 0),
              textAlign: TextAlign.center,
              style: numStyle(weight: FontWeight.w700)),
        ),
        _qbtn(Icons.add, () => ctrl.addQuantity(l.localId, 1)),
      ],
    );
  }

  Widget _qbtn(IconData i, VoidCallback onTap) => InkWell(
        borderRadius: AppRadii.brSm,
        onTap: onTap,
        child: Container(
          width: 28,
          height: 28,
          alignment: Alignment.center,
          decoration: BoxDecoration(
              borderRadius: AppRadii.brSm,
              border: Border.all(color: AppColors.line2)),
          child: Icon(i, size: 16, color: AppColors.ink2),
        ),
      );

  Widget _side() {
    final cart = ref.watch(cartProvider);
    return Container(
      decoration: const BoxDecoration(
          color: AppColors.panel,
          border: Border(left: BorderSide(color: AppColors.line))),
      child: Column(
        children: [
          const Spacer(),
          Container(
            width: double.infinity,
            padding: const EdgeInsets.all(16),
            color: AppColors.panel2,
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                _totalRow('Items', cart.itemCount.toStringAsFixed(0)),
                _totalRow('Lines', '${cart.lineCount}'),
                const Divider(),
                Row(
                  mainAxisAlignment: MainAxisAlignment.spaceBetween,
                  children: [
                    Text('Total (${cart.currency})',
                        style: const TextStyle(
                            fontWeight: FontWeight.w800, fontSize: 18)),
                    const SizedBox(width: 8),
                    Flexible(
                      child: NumText(formatAmount(cart.previewSubtotal),
                          style: numStyle(size: 20, weight: FontWeight.w800)),
                    ),
                  ],
                ),
                const SizedBox(height: 4),
                const Text('preview — ERP is authoritative',
                    style: TextStyle(fontSize: 11, color: AppColors.ink3)),
                const SizedBox(height: 14),
                SizedBox(
                  width: double.infinity,
                  child: PayButton(
                    label: 'PAY',
                    amount: formatAmount(cart.previewSubtotal),
                    onPressed: cart.isEmpty ? null : _pay,
                  ),
                ),
              ],
            ),
          ),
        ],
      ),
    );
  }

  Widget _totalRow(String l, String v) => Padding(
        padding: const EdgeInsets.symmetric(vertical: 3),
        child: Row(
          mainAxisAlignment: MainAxisAlignment.spaceBetween,
          children: [
            Text(l, style: const TextStyle(color: AppColors.ink2)),
            Text(v, style: numStyle(weight: FontWeight.w700)),
          ],
        ),
      );

  Widget _empty(String msg) => Center(
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            const Icon(Icons.medication_outlined, size: 42, color: AppColors.ink3),
            const SizedBox(height: 10),
            Text(msg, style: const TextStyle(color: AppColors.ink3)),
          ],
        ),
      );
}
