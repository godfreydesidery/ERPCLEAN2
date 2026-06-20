import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
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

enum _NumTarget { qty, disc }

/// Supermarket register: 75% Excel-style line grid + 25% number pad
/// (prototype `.reg-pos`). Scanner-first: the search field stays focused and
/// resolves a scan to a line; the numpad edits the selected line.
class SupermarketRegister extends ConsumerStatefulWidget {
  const SupermarketRegister({super.key});
  @override
  ConsumerState<SupermarketRegister> createState() =>
      _SupermarketRegisterState();
}

class _SupermarketRegisterState extends ConsumerState<SupermarketRegister> {
  final _search = TextEditingController();
  late final FocusNode _searchFocus = FocusNode(onKeyEvent: _onSearchKey);
  final _resultsScroll = ScrollController();
  List<Product> _results = const [];
  int _resultIndex = -1; // highlighted row in the results dropdown (-1 = none)
  bool _loadingCatalogue = true;
  bool _busyScan = false;
  Timer? _debounce;

  String _numpad = '';
  _NumTarget _target = _NumTarget.qty;

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
    } catch (_) {/* search will fall back to server */}
    if (mounted) setState(() => _loadingCatalogue = false);
    _searchFocus.requestFocus();
  }

  @override
  void dispose() {
    _debounce?.cancel();
    _search.dispose();
    _searchFocus.dispose();
    _resultsScroll.dispose();
    super.dispose();
  }

  // --------------------------------------------------------------- add / scan

  void _addProduct(Product p,
      {double quantity = 1, double? fixedQuantity, double? overridePrice}) {
    final app = ref.read(appControllerProvider);
    final unit = app.unitsByUid[p.baseUnitUid];
    if (unit == null) {
      showToast(context, '${p.name}: no usable unit configured.');
      return;
    }
    final cart = ref.read(cartProvider.notifier);
    cart.addProduct(p, unit,
        quantity: quantity,
        fixedQuantity: fixedQuantity,
        overridePrice: overridePrice);
    final lineId = ref.read(cartProvider).selectedId;
    if (overridePrice == null && lineId != null) {
      _cache.previewPrice(p.uid, _currency).then((net) {
        if (net != null && mounted) {
          cart.setLinePrice(lineId, app.grossUnitPrice(net, p.vatStatus));
        }
      });
    }
  }

  Future<void> _onSubmit(String raw) async {
    final value = raw.trim();
    if (value.isEmpty) return;
    setState(() => _busyScan = true);
    try {
      // 1) exact product code
      final byCode = _cache.byExactCode(value);
      if (byCode != null) {
        _addProduct(byCode);
        _resetSearch();
        return;
      }
      // 2) barcode lookup (exact or embedded weight/price)
      try {
        final bc =
            await ref.read(catalogServiceProvider).lookupBarcode(_companyId, value);
        if (!context.mounted) return;
        final product = _cache.byId(bc.productId);
        if (product != null) {
          // Embedded-PRICE labels carry an absolute amount the server cannot
          // honour (POS pricing is server-authoritative; unitPrice is ignored),
          // so adding the line would post at the catalogue price — a wrong
          // charge. Refuse it instead. Embedded-WEIGHT is fine (qty is linear).
          if (bc.valueKind == 'PRICE' || bc.derivedAmount != null) {
            showToast(context,
                "Price-embedded labels aren't supported yet — enter ${product.name} manually.");
            _resetSearch();
            return;
          }
          _addProduct(product, fixedQuantity: bc.derivedQuantity);
          _resetSearch();
          return;
        }
      } on ApiException catch (e) {
        if (!e.isNotFound) rethrow;
      }
      if (!context.mounted) return;
      // 3) server search → single hit auto-adds, else show the dropdown
      List<Product> hits;
      try {
        hits = await ref
            .read(catalogServiceProvider)
            .searchProducts(_companyId, q: value, size: 40);
      } catch (_) {
        hits = _cache.search(value);
      }
      if (!context.mounted) return;
      if (hits.length == 1) {
        _addProduct(hits.first);
        _resetSearch();
      } else if (hits.isEmpty) {
        showToast(context, 'No match for "$value".');
      } else {
        _setResults(hits);
      }
    } on ApiException catch (e) {
      if (mounted) showToast(context, e.message);
    } finally {
      if (mounted) setState(() => _busyScan = false);
    }
  }

  void _onChanged(String v) {
    _debounce?.cancel();
    final q = v.trim();
    if (q.isEmpty) {
      _setResults(const []);
      return;
    }
    // Show instant local matches if the cache is warm, then refine from the
    // server (so search works even before the full catalogue finishes loading).
    _setResults(_cache.search(q));
    _debounce = Timer(const Duration(milliseconds: 250), () => _serverSearch(q));
  }

  Future<void> _serverSearch(String q) async {
    try {
      final hits = await ref
          .read(catalogServiceProvider)
          .searchProducts(_companyId, q: q, size: 60);
      if (mounted) _setResults(hits);
    } catch (_) {
      if (mounted) _setResults(_cache.search(q));
    }
  }

  /// Replace the results dropdown and highlight the first row.
  void _setResults(List<Product> r) {
    setState(() {
      _results = r;
      _resultIndex = r.isEmpty ? -1 : 0;
    });
  }

  /// Up/Down arrows move the highlight through the results (Enter adds it,
  /// handled in the field's onSubmitted). Returns handled so the arrows don't
  /// move the text cursor instead.
  KeyEventResult _onSearchKey(FocusNode node, KeyEvent event) {
    if (_results.isEmpty) return KeyEventResult.ignored;
    if (event is! KeyDownEvent && event is! KeyRepeatEvent) {
      return KeyEventResult.ignored;
    }
    final k = event.logicalKey;
    if (k == LogicalKeyboardKey.arrowDown) {
      setState(() =>
          _resultIndex = (_resultIndex + 1).clamp(0, _results.length - 1));
      _scrollToHighlighted();
      return KeyEventResult.handled;
    }
    if (k == LogicalKeyboardKey.arrowUp) {
      setState(() => _resultIndex = _resultIndex <= 0 ? 0 : _resultIndex - 1);
      _scrollToHighlighted();
      return KeyEventResult.handled;
    }
    return KeyEventResult.ignored;
  }

  /// Keep the highlighted row visible as the user arrows past the fold.
  void _scrollToHighlighted() {
    if (!_resultsScroll.hasClients || _resultIndex < 0) return;
    const rowH = 40.0;
    final target = _resultIndex * rowH;
    final offset = _resultsScroll.offset;
    final viewport = _resultsScroll.position.viewportDimension;
    final max = _resultsScroll.position.maxScrollExtent;
    if (target < offset) {
      _resultsScroll.jumpTo(target.clamp(0.0, max));
    } else if (target + rowH > offset + viewport) {
      _resultsScroll.jumpTo((target + rowH - viewport).clamp(0.0, max));
    }
  }

  /// Add the currently-highlighted result (Enter on the search field).
  void _addHighlighted() {
    if (_resultIndex >= 0 && _resultIndex < _results.length) {
      _addProduct(_results[_resultIndex]);
      _resetSearch();
    }
  }

  void _resetSearch() {
    _search.clear();
    _setResults(const []);
    _searchFocus.requestFocus();
  }

  // --------------------------------------------------------------- numpad

  void _numKey(String k) {
    setState(() {
      if (k == 'C') {
        _numpad = '';
      } else if (k == '<') {
        if (_numpad.isNotEmpty) _numpad = _numpad.substring(0, _numpad.length - 1);
      } else if (k == '.') {
        if (!_numpad.contains('.')) _numpad += _numpad.isEmpty ? '0.' : '.';
      } else {
        _numpad += k;
      }
    });
  }

  void _applyNumpad() {
    final cart = ref.read(cartProvider);
    final id = cart.selectedId;
    final val = double.tryParse(_numpad);
    if (id == null) {
      showToast(context, 'Select a line first.');
      return;
    }
    if (val == null) return;
    final ctrl = ref.read(cartProvider.notifier);
    if (_target == _NumTarget.qty) {
      ctrl.setQuantity(id, val);
    } else {
      ctrl.setDiscount(id, val);
    }
    setState(() => _numpad = '');
  }

  Future<void> _pay() async {
    final cart = ref.read(cartProvider);
    if (cart.isEmpty) {
      showToast(context, 'Add an item first.');
      return;
    }
    await openPaymentSheet(context, ref);
    if (mounted) _searchFocus.requestFocus();
  }

  // --------------------------------------------------------------- build

  @override
  Widget build(BuildContext context) {
    return Row(
      children: [
        Expanded(child: _left()),
        SizedBox(width: 340, child: _numpadPanel()),
      ],
    );
  }

  Widget _left() {
    return Padding(
      padding: const EdgeInsets.all(16),
      child: Stack(
        children: [
          Column(
            children: [
              _searchBar(),
              if (_loadingCatalogue)
                const Padding(
                  padding: EdgeInsets.only(top: 10),
                  child: LinearProgressIndicator(minHeight: 2),
                ),
              const SizedBox(height: 12),
              Expanded(child: _grid()),
            ],
          ),
          // The search-results dropdown must paint ON TOP of the grid, so it is
          // the last child of the Stack (not nested inside the search bar, where
          // the grid would occlude it).
          if (_results.isNotEmpty)
            Positioned(
              top: 52,
              left: 0,
              right: 0,
              child: _resultsOverlay(),
            ),
        ],
      ),
    );
  }

  Widget _searchBar() {
    return Container(
      decoration: BoxDecoration(
        color: AppColors.panel,
        borderRadius: AppRadii.brLg,
        border: Border.all(color: AppColors.line2),
      ),
      padding: const EdgeInsets.only(left: 14, right: 4),
      child: Row(
            children: [
              Icon(_busyScan ? Icons.hourglass_top : Icons.qr_code_scanner,
                  color: AppColors.ink3, size: 20),
              const SizedBox(width: 10),
              Expanded(
                child: TextField(
                  controller: _search,
                  focusNode: _searchFocus,
                  autofocus: true,
                  textInputAction: TextInputAction.go,
                  decoration: const InputDecoration(
                    border: InputBorder.none,
                    enabledBorder: InputBorder.none,
                    focusedBorder: InputBorder.none,
                    filled: false,
                    hintText: 'Scan a barcode or search by code / name…',
                    contentPadding: EdgeInsets.symmetric(vertical: 14),
                  ),
                  onChanged: _onChanged,
                  onSubmitted: (v) {
                    if (_results.isNotEmpty && _resultIndex >= 0) {
                      _addHighlighted();
                    } else {
                      _onSubmit(v);
                    }
                  },
                ),
              ),
              Padding(
                padding: const EdgeInsets.all(4),
                child: Material(
                  color: AppColors.brand,
                  borderRadius: AppRadii.brSm,
                  child: InkWell(
                    borderRadius: AppRadii.brSm,
                    onTap: () => _onSubmit(_search.text),
                    child: const SizedBox(
                      width: 46,
                      height: 42,
                      child: Icon(Icons.add, color: Colors.white),
                    ),
                  ),
                ),
              ),
            ],
          ),
        );
  }

  Widget _resultsOverlay() {
    return Material(
      elevation: 8,
      borderRadius: AppRadii.brSm,
        child: Container(
          constraints: const BoxConstraints(maxHeight: 420),
          decoration: BoxDecoration(
            color: AppColors.panel,
            borderRadius: AppRadii.brSm,
            border: Border.all(color: AppColors.xlLineHard),
          ),
          child: Scrollbar(
            controller: _resultsScroll,
            thumbVisibility: true,
            child: ListView.separated(
            controller: _resultsScroll,
            shrinkWrap: true,
            padding: EdgeInsets.zero,
            itemCount: _results.length,
            separatorBuilder: (_, _) =>
                const Divider(height: 1, color: AppColors.line),
            itemBuilder: (context, i) {
              final p = _results[i];
              return InkWell(
                onTap: () {
                  _addProduct(p);
                  _resetSearch();
                },
                child: Container(
                  color: i == _resultIndex ? AppColors.brandSoft : null,
                  padding: const EdgeInsets.symmetric(
                      horizontal: 12, vertical: 9),
                  child: Row(
                    children: [
                      SizedBox(
                          width: 90,
                          child: Text(p.code,
                              style: const TextStyle(
                                  fontFamily: 'Consolas',
                                  fontSize: 12,
                                  color: AppColors.ink3))),
                      Expanded(
                          child: Text(p.name,
                              maxLines: 1, overflow: TextOverflow.ellipsis)),
                      if (p.restrictedKind.isRestricted)
                        Padding(
                          padding: const EdgeInsets.only(left: 8),
                          child: _agePill(p.restrictedKind.ageLabel),
                        ),
                    ],
                  ),
                ),
              );
            },
          ),
          ),
        ),
      );
  }

  Widget _grid() {
    final cart = ref.watch(cartProvider);
    return Container(
      decoration: BoxDecoration(
        color: AppColors.panel,
        border: Border.all(color: AppColors.xlLineHard),
      ),
      child: Column(
        children: [
          _gridHeader(),
          Expanded(
            child: cart.lines.isEmpty
                ? _emptyGrid()
                : ListView.builder(
                    itemCount: cart.lines.length,
                    itemBuilder: (context, i) =>
                        _gridRow(cart.lines[i], i + 1, cart.selectedId),
                  ),
          ),
        ],
      ),
    );
  }

  static const _wIdx = 34.0;
  static const _wCode = 64.0;
  static const _wUnit = 52.0;
  static const _wQty = 72.0;
  static const _wPrice = 84.0;
  static const _wDisc = 70.0;
  static const _wTotal = 92.0;
  static const _wAct = 30.0;

  Widget _gridHeader() {
    TextStyle h = const TextStyle(
        fontSize: 11,
        fontWeight: FontWeight.w700,
        color: Color(0xFF374151),
        letterSpacing: .4);
    Widget cell(String t, double w, {bool right = false, bool center = false}) =>
        Container(
          width: w,
          padding: const EdgeInsets.symmetric(horizontal: 9, vertical: 7),
          alignment: right
              ? Alignment.centerRight
              : (center ? Alignment.center : Alignment.centerLeft),
          child: Text(t.toUpperCase(), style: h),
        );
    return Container(
      decoration: const BoxDecoration(
        color: AppColors.xlHead,
        border: Border(bottom: BorderSide(color: AppColors.xlLineHard)),
      ),
      child: Row(
        children: [
          cell('#', _wIdx, center: true),
          cell('Code', _wCode),
          const Expanded(
              child: Padding(
                  padding: EdgeInsets.symmetric(horizontal: 9, vertical: 7),
                  child: Text('ITEM',
                      style: TextStyle(
                          fontSize: 11,
                          fontWeight: FontWeight.w700,
                          color: Color(0xFF374151),
                          letterSpacing: .4)))),
          cell('Unit', _wUnit, center: true),
          cell('Qty', _wQty, right: true),
          cell('Price', _wPrice, right: true),
          cell('Disc', _wDisc, right: true),
          cell('Total', _wTotal, right: true),
          cell('', _wAct, center: true),
          cell('V', _wAct, center: true),
        ],
      ),
    );
  }

  Widget _gridRow(CartLine line, int idx, String? selectedId) {
    final selected = line.localId == selectedId;
    final voided = line.voided;
    final ctrl = ref.read(cartProvider.notifier);
    final muted = voided ? AppColors.ink3 : AppColors.xlInk;
    final strike = voided ? TextDecoration.lineThrough : null;

    Widget cell(Widget child, double w,
            {Alignment align = Alignment.centerLeft,
            VoidCallback? onTap,
            Color? bg}) =>
        InkWell(
          onTap: onTap,
          child: Container(
            width: w,
            height: 30,
            alignment: align,
            padding: const EdgeInsets.symmetric(horizontal: 9),
            decoration: BoxDecoration(
              color: bg,
              border: const Border(
                  right: BorderSide(color: AppColors.xlLine),
                  bottom: BorderSide(color: AppColors.xlLine)),
            ),
            child: child,
          ),
        );

    void selectQty() {
      ctrl.select(line.localId);
      setState(() => _target = _NumTarget.qty);
    }

    void selectDisc() {
      ctrl.select(line.localId);
      setState(() => _target = _NumTarget.disc);
    }

    final qtyHi = selected && _target == _NumTarget.qty;
    final discHi = selected && _target == _NumTarget.disc;

    return Container(
      color: selected ? AppColors.xlSel : AppColors.panel,
      child: Row(
        children: [
          cell(
              Text('$idx',
                  style: const TextStyle(
                      fontSize: 12, color: Color(0xFF9AA0A6))),
              _wIdx,
              align: Alignment.center,
              bg: AppColors.xlHead,
              onTap: () => ctrl.select(line.localId)),
          cell(
              Text(line.product.code,
                  maxLines: 1,
                  overflow: TextOverflow.ellipsis,
                  style: TextStyle(
                      fontFamily: 'Consolas',
                      fontSize: 12,
                      color: const Color(0xFF5F6368),
                      decoration: strike)),
              _wCode,
              onTap: () => ctrl.select(line.localId)),
          Expanded(
            child: InkWell(
              onTap: () => ctrl.select(line.localId),
              child: Container(
                height: 30,
                alignment: Alignment.centerLeft,
                padding: const EdgeInsets.symmetric(horizontal: 9),
                decoration: const BoxDecoration(
                  border: Border(
                      right: BorderSide(color: AppColors.xlLine),
                      bottom: BorderSide(color: AppColors.xlLine)),
                ),
                child: Row(
                  children: [
                    Flexible(
                      child: Text(line.product.name,
                          maxLines: 1,
                          overflow: TextOverflow.ellipsis,
                          style: TextStyle(
                              fontSize: 13.5,
                              fontWeight: FontWeight.w600,
                              color: muted,
                              decoration: strike)),
                    ),
                    if (line.product.restrictedKind.isRestricted) ...[
                      const SizedBox(width: 6),
                      _agePill(line.product.restrictedKind.ageLabel),
                    ],
                  ],
                ),
              ),
            ),
          ),
          cell(
              Text(line.unit.code,
                  style: TextStyle(
                      fontSize: 12, color: const Color(0xFF5F6368))),
              _wUnit,
              align: Alignment.center),
          cell(
              Text(formatAmount(line.quantity, decimals: line.unit.fractional ? 3 : 0),
                  style: numStyle(size: 13.5, color: muted)),
              _wQty,
              align: Alignment.centerRight,
              bg: qtyHi ? AppColors.brandSoft : null,
              onTap: selectQty),
          cell(
              Text(
                  line.unitPricePreview == null
                      ? '—'
                      : formatAmount(line.unitPricePreview!),
                  style: numStyle(
                      size: 13.5, weight: FontWeight.w400, color: const Color(0xFF5F6368))),
              _wPrice,
              align: Alignment.centerRight,
              onTap: () => ctrl.select(line.localId)),
          cell(
              Text(line.lineDiscountAmount == 0
                  ? '·'
                  : formatAmount(line.lineDiscountAmount),
                  style: numStyle(
                      size: 13.5,
                      color: line.lineDiscountAmount == 0
                          ? AppColors.ink3
                          : AppColors.brand)),
              _wDisc,
              align: Alignment.centerRight,
              bg: discHi ? AppColors.brandSoft : null,
              onTap: selectDisc),
          cell(
              Text(formatAmount(line.previewGross),
                  style: numStyle(
                      size: 13.5, weight: FontWeight.w700, color: muted)),
              _wTotal,
              align: Alignment.centerRight,
              onTap: () => ctrl.select(line.localId)),
          cell(
              Icon(Icons.close,
                  size: 14,
                  color: voided ? AppColors.ink3 : const Color(0xFFC4C9CF)),
              _wAct,
              align: Alignment.center,
              onTap: () => ctrl.removeLine(line.localId)),
          cell(
              Icon(voided ? Icons.check_box : Icons.check_box_outline_blank,
                  size: 15, color: voided ? AppColors.warn : AppColors.ink3),
              _wAct,
              align: Alignment.center,
              onTap: () => ctrl.toggleVoid(line.localId)),
        ],
      ),
    );
  }

  Widget _emptyGrid() {
    return const Center(
      child: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          Icon(Icons.shopping_cart_outlined, size: 42, color: AppColors.ink3),
          SizedBox(height: 10),
          Text('Scan or search to add items',
              style: TextStyle(color: AppColors.ink3)),
        ],
      ),
    );
  }

  Widget _agePill(String label) => Container(
        padding: const EdgeInsets.symmetric(horizontal: 6, vertical: 1),
        decoration: BoxDecoration(
          color: AppColors.warnSoft,
          borderRadius: AppRadii.brPill,
          border: Border.all(color: const Color(0xFFFED7AA)),
        ),
        child: Text(label,
            style: const TextStyle(
                fontSize: 10,
                fontWeight: FontWeight.w700,
                color: AppColors.warn)),
      );

  // --------------------------------------------------------------- numpad panel

  Widget _numpadPanel() {
    final cart = ref.watch(cartProvider);
    return Container(
      decoration: const BoxDecoration(
        color: AppColors.panel,
        border: Border(left: BorderSide(color: AppColors.line)),
      ),
      padding: const EdgeInsets.all(14),
      child: Column(
        children: [
          _totalCard(cart),
          const SizedBox(height: 11),
          _customerChip(cart),
          const SizedBox(height: 11),
          _targetToggle(),
          const SizedBox(height: 8),
          _numDisplay(),
          const SizedBox(height: 8),
          Expanded(child: _keys()),
          const SizedBox(height: 10),
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
    );
  }

  Widget _totalCard(CartState cart) {
    return Container(
      width: double.infinity,
      padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 14),
      decoration: BoxDecoration(
          color: AppColors.ink, borderRadius: AppRadii.brLg),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.end,
        children: [
          Text('TOTAL (${cart.currency})',
              style: const TextStyle(
                  color: Color(0xFFCBD5E1),
                  fontSize: 11,
                  fontWeight: FontWeight.w700,
                  letterSpacing: .6)),
          const SizedBox(height: 2),
          Text(formatAmount(cart.previewSubtotal),
              style: const TextStyle(
                  color: Colors.white,
                  fontSize: 30,
                  fontWeight: FontWeight.w800,
                  height: 1.1,
                  fontFeatures: kTabular)),
          const SizedBox(height: 6),
          Text('${cart.lineCount} lines · ${formatAmount(cart.itemCount, decimals: cart.itemCount % 1 == 0 ? 0 : 3)} items',
              style: const TextStyle(
                  color: Color(0xFFCBD5E1),
                  fontSize: 12,
                  fontFeatures: kTabular)),
          const Padding(
            padding: EdgeInsets.only(top: 4),
            child: Text('preview — ERP is authoritative',
                style: TextStyle(color: Color(0xFF94A3B8), fontSize: 10)),
          ),
        ],
      ),
    );
  }

  Widget _customerChip(CartState cart) {
    return InkWell(
      borderRadius: AppRadii.brSm,
      onTap: () => showCustomerPicker(context, ref),
      child: Container(
        padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 8),
        decoration: BoxDecoration(
          color: AppColors.panel2,
          borderRadius: AppRadii.brSm,
          border: Border.all(color: AppColors.line),
        ),
        child: Row(
          children: [
            Container(
              width: 30,
              height: 30,
              alignment: Alignment.center,
              decoration: BoxDecoration(
                  color: AppColors.brandSoft, borderRadius: AppRadii.brSm),
              child: const Icon(Icons.person_outline,
                  size: 16, color: AppColors.brand),
            ),
            const SizedBox(width: 10),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  const Text('Customer',
                      style:
                          TextStyle(fontSize: 11, color: AppColors.ink3)),
                  Text(cart.customer?.displayName ?? 'Walk-in',
                      maxLines: 1,
                      overflow: TextOverflow.ellipsis,
                      style: const TextStyle(
                          fontSize: 14, fontWeight: FontWeight.w600)),
                ],
              ),
            ),
            const Icon(Icons.chevron_right, color: AppColors.ink3),
          ],
        ),
      ),
    );
  }

  Widget _targetToggle() {
    Widget seg(String label, _NumTarget t) {
      final active = _target == t;
      return Expanded(
        child: InkWell(
          borderRadius: AppRadii.brSm,
          onTap: () => setState(() => _target = t),
          child: Container(
            alignment: Alignment.center,
            padding: const EdgeInsets.symmetric(vertical: 9),
            decoration: BoxDecoration(
              color: active ? AppColors.panel : Colors.transparent,
              borderRadius: AppRadii.brSm,
              boxShadow: active ? AppShadows.card : null,
            ),
            child: Text(label,
                style: TextStyle(
                    fontWeight: FontWeight.w700,
                    color: active ? AppColors.brand : AppColors.ink2)),
          ),
        ),
      );
    }

    return Container(
      padding: const EdgeInsets.all(3),
      decoration: BoxDecoration(
        color: AppColors.panel2,
        borderRadius: AppRadii.brSm,
        border: Border.all(color: AppColors.line),
      ),
      child: Row(children: [seg('× Qty', _NumTarget.qty), seg('− Disc', _NumTarget.disc)]),
    );
  }

  Widget _numDisplay() {
    return Container(
      width: double.infinity,
      padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 11),
      decoration: BoxDecoration(
        color: AppColors.panel2,
        borderRadius: AppRadii.brSm,
        border: Border.all(color: AppColors.line2),
      ),
      child: Text(_numpad.isEmpty ? '0' : _numpad,
          textAlign: TextAlign.right,
          style: numStyle(size: 26, weight: FontWeight.w800)),
    );
  }

  Widget _keys() {
    final keys = ['7', '8', '9', '4', '5', '6', '1', '2', '3', '.', '0', '<'];
    return Column(
      children: [
        Expanded(
          child: GridView.count(
            crossAxisCount: 3,
            mainAxisSpacing: 8,
            crossAxisSpacing: 8,
            childAspectRatio: 1.6,
            physics: const NeverScrollableScrollPhysics(),
            children: keys.map((k) => _key(k)).toList(),
          ),
        ),
        const SizedBox(height: 8),
        Row(
          children: [
            Expanded(
                child: OrbixButton(
                    label: 'Clear',
                    kind: BtnKind.ghost,
                    onPressed: () => _numKey('C'))),
            const SizedBox(width: 8),
            Expanded(
                child: OrbixButton(
                    label: _target == _NumTarget.qty ? 'Set qty' : 'Set disc',
                    onPressed: _applyNumpad)),
          ],
        ),
      ],
    );
  }

  Widget _key(String k) {
    return Material(
      color: AppColors.panel,
      borderRadius: AppRadii.brSm,
      child: InkWell(
        borderRadius: AppRadii.brSm,
        onTap: () => _numKey(k),
        child: Container(
          alignment: Alignment.center,
          decoration: BoxDecoration(
            borderRadius: AppRadii.brSm,
            border: Border.all(color: AppColors.line2),
          ),
          child: k == '<'
              ? const Icon(Icons.backspace_outlined, size: 18)
              : Text(k,
                  style: const TextStyle(
                      fontSize: 22, fontWeight: FontWeight.w700)),
        ),
      ),
    );
  }
}
