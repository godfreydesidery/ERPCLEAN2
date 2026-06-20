import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../app/theme.dart';
import '../../core/money.dart';
import '../../models/catalog.dart';
import '../../state/app_controller.dart';
import '../../state/cart_controller.dart';
import '../../state/catalog_cache.dart';
import '../../widgets/ui.dart';
import '../payment/payment_sheet.dart';

/// Restaurant register: table picker + menu grid + order ticket + send-to-kitchen.
/// Send-to-kitchen is client-side (no backend endpoint); the table rides on the
/// sale notes. Payment/receipt/session are the shared spine.
class RestaurantRegister extends ConsumerStatefulWidget {
  const RestaurantRegister({super.key});
  @override
  ConsumerState<RestaurantRegister> createState() => _RestaurantRegisterState();
}

class _RestaurantRegisterState extends ConsumerState<RestaurantRegister> {
  final _q = TextEditingController();
  List<Product> _menu = const [];
  bool _loading = true;
  int? _table;
  bool _sent = false;

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
    if (mounted) {
      setState(() {
        _menu = _cache.search('', limit: 120);
        _loading = false;
      });
    }
  }

  @override
  void dispose() {
    _q.dispose();
    super.dispose();
  }

  void _add(Product p) {
    final app = ref.read(appControllerProvider);
    final unit = app.unitsByUid[p.baseUnitUid];
    if (unit == null) {
      showToast(context, '${p.name}: no usable unit.');
      return;
    }
    final cart = ref.read(cartProvider.notifier);
    cart.addProduct(p, unit);
    final id = ref.read(cartProvider).selectedId;
    if (id != null) {
      _cache.previewPrice(p.uid, _currency).then((pr) {
        if (pr != null && mounted) cart.setLinePrice(id, pr);
      });
    }
    setState(() => _sent = false);
  }

  void _filter(String q) =>
      setState(() => _menu = _cache.search(q, limit: 120));

  Future<void> _pickTable() async {
    final picked = await showDialog<int>(
      context: context,
      builder: (_) => const _FloorDialog(),
    );
    if (picked != null) {
      setState(() => _table = picked);
      ref.read(cartProvider.notifier).setNotes('Table $picked');
    }
  }

  Future<void> _pay() async {
    if (ref.read(cartProvider).isEmpty) {
      showToast(context, 'Add an item first.');
      return;
    }
    await openPaymentSheet(context, ref);
    if (mounted) {
      setState(() {
        _table = null;
        _sent = false;
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    return Row(
      children: [
        Expanded(child: _menuPane()),
        SizedBox(width: 400, child: _ticket()),
      ],
    );
  }

  Widget _menuPane() {
    return Padding(
      padding: const EdgeInsets.all(16),
      child: Column(
        children: [
          Row(
            children: [
              InkWell(
                borderRadius: AppRadii.brLg,
                onTap: _pickTable,
                child: Container(
                  padding:
                      const EdgeInsets.symmetric(horizontal: 16, vertical: 10),
                  decoration: BoxDecoration(
                    color: AppColors.brandSoft,
                    borderRadius: AppRadii.brLg,
                    border: Border.all(color: const Color(0xFFC7D2FE)),
                  ),
                  child: Row(
                    children: [
                      const Icon(Icons.table_restaurant_outlined,
                          size: 18, color: AppColors.brandD),
                      const SizedBox(width: 8),
                      Text(_table == null ? 'Pick a table' : 'Table $_table',
                          style: const TextStyle(
                              fontWeight: FontWeight.w700,
                              color: AppColors.brandD)),
                    ],
                  ),
                ),
              ),
              const SizedBox(width: 12),
              Expanded(
                child: Container(
                  decoration: BoxDecoration(
                    color: AppColors.panel,
                    borderRadius: AppRadii.brLg,
                    border: Border.all(color: AppColors.line2),
                  ),
                  padding: const EdgeInsets.symmetric(horizontal: 14),
                  child: TextField(
                    controller: _q,
                    onChanged: _filter,
                    decoration: const InputDecoration(
                      border: InputBorder.none,
                      enabledBorder: InputBorder.none,
                      focusedBorder: InputBorder.none,
                      filled: false,
                      hintText: 'Search the menu…',
                      prefixIcon: Icon(Icons.search, color: AppColors.ink3),
                    ),
                  ),
                ),
              ),
            ],
          ),
          const SizedBox(height: 14),
          if (_loading)
            const Expanded(child: Center(child: CircularProgressIndicator()))
          else
            Expanded(child: _grid()),
        ],
      ),
    );
  }

  Widget _grid() {
    if (_menu.isEmpty) {
      return const Center(
          child: Text('No menu items', style: TextStyle(color: AppColors.ink3)));
    }
    return GridView.builder(
      gridDelegate: const SliverGridDelegateWithMaxCrossAxisExtent(
        maxCrossAxisExtent: 160,
        mainAxisExtent: 110,
        crossAxisSpacing: 12,
        mainAxisSpacing: 12,
      ),
      itemCount: _menu.length,
      itemBuilder: (context, i) {
        final p = _menu[i];
        return Material(
          color: AppColors.panel,
          borderRadius: AppRadii.brLg,
          child: InkWell(
            borderRadius: AppRadii.brLg,
            onTap: () => _add(p),
            child: Container(
              padding: const EdgeInsets.all(12),
              decoration: BoxDecoration(
                borderRadius: AppRadii.brLg,
                border: Border.all(color: AppColors.line),
              ),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Container(
                    width: 38,
                    height: 38,
                    alignment: Alignment.center,
                    decoration: BoxDecoration(
                        color: AppColors.brandSoft,
                        borderRadius: AppRadii.brSm),
                    child: const Icon(Icons.restaurant_menu,
                        size: 20, color: AppColors.brand),
                  ),
                  const Spacer(),
                  Text(p.name,
                      maxLines: 2,
                      overflow: TextOverflow.ellipsis,
                      style: const TextStyle(
                          fontWeight: FontWeight.w600, fontSize: 13.5)),
                  Text(p.code,
                      style: const TextStyle(
                          fontSize: 11, color: AppColors.ink3)),
                ],
              ),
            ),
          ),
        );
      },
    );
  }

  Widget _ticket() {
    final cart = ref.watch(cartProvider);
    final ctrl = ref.read(cartProvider.notifier);
    return Container(
      decoration: const BoxDecoration(
          color: AppColors.panel,
          border: Border(left: BorderSide(color: AppColors.line))),
      child: Column(
        children: [
          Container(
            padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 12),
            decoration: const BoxDecoration(
                border: Border(bottom: BorderSide(color: AppColors.line))),
            width: double.infinity,
            child: Row(
              children: [
                Text(_table == null ? 'Order ticket' : 'Table $_table',
                    style: const TextStyle(
                        fontSize: 16, fontWeight: FontWeight.w700)),
                const Spacer(),
                Text('${cart.lineCount} items',
                    style: const TextStyle(color: AppColors.ink2, fontSize: 12)),
              ],
            ),
          ),
          Expanded(
            child: cart.lines.isEmpty
                ? const Center(
                    child: Text('Tap menu items to build the order',
                        style: TextStyle(color: AppColors.ink3)))
                : ListView.builder(
                    padding: const EdgeInsets.all(6),
                    itemCount: cart.lines.length,
                    itemBuilder: (context, i) {
                      final l = cart.lines[i];
                      return Padding(
                        padding: const EdgeInsets.all(8),
                        child: Row(
                          children: [
                            Expanded(
                              child: Column(
                                crossAxisAlignment: CrossAxisAlignment.start,
                                children: [
                                  Text(l.product.name,
                                      maxLines: 1,
                                      overflow: TextOverflow.ellipsis,
                                      style: const TextStyle(
                                          fontWeight: FontWeight.w600)),
                                  Row(
                                    children: [
                                      _qbtn(Icons.remove,
                                          () => ctrl.addQuantity(l.localId, -1)),
                                      SizedBox(
                                        width: 30,
                                        child: Text(
                                            formatAmount(l.quantity, decimals: 0),
                                            textAlign: TextAlign.center,
                                            style: numStyle(
                                                weight: FontWeight.w700)),
                                      ),
                                      _qbtn(Icons.add,
                                          () => ctrl.addQuantity(l.localId, 1)),
                                    ],
                                  ),
                                ],
                              ),
                            ),
                            Text(formatAmount(l.previewGross),
                                style: numStyle(weight: FontWeight.w800)),
                            IconButton(
                                onPressed: () => ctrl.removeLine(l.localId),
                                icon: const Icon(Icons.close,
                                    size: 15, color: AppColors.ink3)),
                          ],
                        ),
                      );
                    },
                  ),
          ),
          if (!cart.isEmpty)
            Padding(
              padding: const EdgeInsets.symmetric(horizontal: 12),
              child: OrbixButton(
                label: _sent ? 'Sent to kitchen ✓' : 'Send to kitchen',
                icon: Icons.soup_kitchen_outlined,
                kind: BtnKind.ghost,
                block: true,
                onPressed: () {
                  setState(() => _sent = true);
                  showToast(context, 'Sent to kitchen.', ok: true);
                },
              ),
            ),
          Container(
            padding: const EdgeInsets.all(14),
            color: AppColors.panel2,
            width: double.infinity,
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
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
                const SizedBox(height: 12),
                PayButton(
                  label: 'PAY',
                  amount: formatAmount(cart.previewSubtotal),
                  onPressed: cart.isEmpty ? null : _pay,
                ),
              ],
            ),
          ),
        ],
      ),
    );
  }

  Widget _qbtn(IconData i, VoidCallback onTap) => InkWell(
        borderRadius: AppRadii.brSm,
        onTap: onTap,
        child: Container(
          width: 26,
          height: 26,
          alignment: Alignment.center,
          decoration: BoxDecoration(
              borderRadius: AppRadii.brSm,
              border: Border.all(color: AppColors.line2)),
          child: Icon(i, size: 15, color: AppColors.ink2),
        ),
      );
}

/// A simple floor: pick a table number.
class _FloorDialog extends StatelessWidget {
  const _FloorDialog();
  @override
  Widget build(BuildContext context) {
    return Dialog(
      shape: RoundedRectangleBorder(borderRadius: AppRadii.brLg),
      child: ConstrainedBox(
        constraints: const BoxConstraints(maxWidth: 520),
        child: Padding(
          padding: const EdgeInsets.all(20),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              const Text('Choose a table',
                  style: TextStyle(fontSize: 18, fontWeight: FontWeight.w700)),
              const SizedBox(height: 16),
              GridView.builder(
                shrinkWrap: true,
                gridDelegate: const SliverGridDelegateWithMaxCrossAxisExtent(
                  maxCrossAxisExtent: 110,
                  mainAxisExtent: 90,
                  crossAxisSpacing: 12,
                  mainAxisSpacing: 12,
                ),
                itemCount: 12,
                itemBuilder: (context, i) {
                  final n = i + 1;
                  return Material(
                    color: AppColors.panel,
                    borderRadius: AppRadii.brLg,
                    child: InkWell(
                      borderRadius: AppRadii.brLg,
                      onTap: () => Navigator.pop(context, n),
                      child: Container(
                        alignment: Alignment.center,
                        decoration: BoxDecoration(
                          borderRadius: AppRadii.brLg,
                          border: Border.all(color: AppColors.line2),
                        ),
                        child: Column(
                          mainAxisAlignment: MainAxisAlignment.center,
                          children: [
                            Text('$n',
                                style: const TextStyle(
                                    fontSize: 18, fontWeight: FontWeight.w800)),
                            const Text('seats 4',
                                style: TextStyle(
                                    fontSize: 11, color: AppColors.ink3)),
                          ],
                        ),
                      ),
                    ),
                  );
                },
              ),
            ],
          ),
        ),
      ),
    );
  }
}
