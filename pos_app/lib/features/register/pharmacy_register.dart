import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../app/theme.dart';
import '../../core/api/api_exception.dart';
import '../../core/money.dart';
import '../../models/catalog.dart';
import '../../state/app_controller.dart';
import '../../state/cart_controller.dart';
import '../../state/catalog_cache.dart';
import '../../state/providers.dart';
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
  bool _loading = true;

  CatalogCache get _cache => ref.read(catalogCacheProvider);
  String get _companyId => ref.read(appControllerProvider).context!.companyId;
  String get _currency => ref.read(cartProvider).currency;

  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load() async {
    try {
      await _cache.ensureLoaded(_companyId);
    } catch (_) {}
    if (mounted) setState(() => _loading = false);
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

  void _add(Product p, {double? fixedQty, double? overridePrice}) {
    final app = ref.read(appControllerProvider);
    final unit = app.unitsByUid[p.baseUnitUid];
    if (unit == null) {
      showToast(context, '${p.name}: no usable unit.');
      return;
    }
    final cart = ref.read(cartProvider.notifier);
    cart.addProduct(p, unit, fixedQuantity: fixedQty, overridePrice: overridePrice);
    final id = ref.read(cartProvider).selectedId;
    if (overridePrice == null && id != null) {
      _cache.previewPrice(p.uid, _currency).then((net) {
        if (net != null && mounted) {
          cart.setLinePrice(id, app.grossUnitPrice(net, p.vatStatus));
        }
      });
    }
  }

  Future<void> _onSearch(String raw) async {
    final v = raw.trim();
    if (v.isEmpty) return;
    final byCode = _cache.byExactCode(v);
    if (byCode != null) {
      _add(byCode);
      _reset();
      return;
    }
    try {
      final bc = await ref.read(catalogServiceProvider).lookupBarcode(_companyId, v);
      if (!context.mounted) return;
      final p = _cache.byId(bc.productId);
      if (p != null) {
        // Embedded-PRICE labels carry an amount the server cannot honour — adding
        // the line would charge the catalogue price. Refuse it (weight is fine).
        if (bc.valueKind == 'PRICE' || bc.derivedAmount != null) {
          showToast(context,
              "Price-embedded labels aren't supported yet — enter ${p.name} manually.");
          _reset();
          return;
        }
        _add(p, fixedQty: bc.derivedQuantity);
        _reset();
        return;
      }
    } on ApiException catch (e) {
      if (!e.isNotFound && mounted) showToast(context, e.message);
    }
    if (!mounted) return;
    // Server search so it works regardless of the local-cache load state.
    List<Product> hits;
    try {
      hits = await ref
          .read(catalogServiceProvider)
          .searchProducts(_companyId, q: v, size: 10);
    } catch (_) {
      hits = _cache.search(v);
    }
    if (!context.mounted) return;
    if (hits.isNotEmpty) {
      _add(hits.first);
      _reset();
    } else {
      showToast(context, 'No match for "$v".');
    }
  }

  void _reset() {
    _search.clear();
    _searchFocus.requestFocus();
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
          if (_loading) const Padding(
              padding: EdgeInsets.only(top: 10),
              child: LinearProgressIndicator(minHeight: 2)),
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
                        ],
                      ),
                      Text(
                          '${l.product.code}  ·  ${l.product.baseUnitName ?? l.unit.name}'
                          '${l.product.status == 'ACTIVE' ? '' : ''}',
                          style: const TextStyle(
                              fontSize: 12, color: AppColors.ink3)),
                    ],
                  ),
                ),
                _qtyStepper(l, ctrl),
                SizedBox(
                  width: 96,
                  child: Text(formatAmount(l.previewGross),
                      textAlign: TextAlign.right,
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
                    Text(formatAmount(cart.previewSubtotal),
                        style: numStyle(size: 20, weight: FontWeight.w800)),
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
