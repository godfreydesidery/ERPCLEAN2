import 'dart:async';

import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../app/theme.dart';
import '../../core/api/api_client.dart';
import '../../core/api/api_exception.dart';
import '../../core/barcode.dart';
import '../../core/config/step_up_policy.dart';
import '../../core/money.dart';
import '../../models/catalog.dart';
import '../../state/app_controller.dart';
import '../../state/basket_pricer.dart';
import '../../state/cart_controller.dart';
import '../../state/parked_sale_store.dart';
import '../../state/catalog_cache.dart';
import '../../state/price_cache.dart';
import '../../state/providers.dart';
import '../../state/stock_cache.dart';
import '../../widgets/ui.dart';
import '../auth/approval_dialog.dart';
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

  /// On Android the search box takes the scanner without the system keyboard:
  /// it keeps focus after every item, and a keyboard popping up each time would
  /// cover the basket. A tap on the box brings the keyboard up for typing.
  static final bool _android = defaultTargetPlatform == TargetPlatform.android;
  bool _typing = false;

  void _refocusSearch() {
    if (_android && _typing && mounted) {
      setState(() => _typing = false);
      SystemChannels.textInput.invokeMethod<void>('TextInput.hide');
    }
    _searchFocus.requestFocus();
  }

  void _typeInSearch() {
    if (!_android || _typing) return;
    setState(() => _typing = true);
    WidgetsBinding.instance.addPostFrameCallback(
        (_) => SystemChannels.textInput.invokeMethod<void>('TextInput.show'));
  }
  final _resultsScroll = ScrollController();
  List<Product> _results = const [];
  int _resultIndex = -1; // highlighted row in the results dropdown (-1 = none)
  bool _busyScan = false;
  Timer? _debounce;

  /// The query the visible [_results] came from. The stock cache needs it to
  /// tell "this branch holds none of it" (absent from a completed query) apart
  /// from "we have not asked yet" — the two used to render identically, as
  /// nothing, and a cashier reads nothing as "fine".
  String _resultsQuery = '';

  // The numpad box is a real editable field: the hardware keyboard types into it
  // (once a Qty/Disc cell is tapped) AND the on-screen keys drive the same
  // controller. Enter applies it to the selected line.
  final _numCtrl = TextEditingController();
  final _numFocus = FocusNode();
  _NumTarget _target = _NumTarget.qty;

  /// Phone layout: the number pad is folded away until a quantity or discount
  /// is being keyed, so the basket keeps the screen.
  bool _phoneKeys = false;

  Catalogue get _cache => ref.read(catalogProvider);
  StockCache get _stock => ref.read(stockCacheProvider);
  PriceCache get _prices => ref.read(priceCacheProvider);
  String get _companyId => ref.read(appControllerProvider).context!.companyId;
  String get _currency => ref.read(cartProvider).currency;

  /// Refresh on-hand for the current query so search rows show remaining stock
  /// before the cashier rings (best-effort; repaints when it lands).
  void _refreshStock(String q) {
    _stock.refreshFor(q).then((_) {
      if (mounted) setState(() {});
    });
  }

  /// Price the whole visible result page in ONE request (K6, using P2's batch
  /// endpoint). Prices come from the ERP's own resolver, so what the cashier
  /// reads while keying in is what the invoice will charge — the till does not
  /// approximate the pricing rules and then disagree with the posted sale.
  void _refreshPrices(List<Product> results) {
    if (results.isEmpty) return;
    final cart = ref.read(cartProvider);
    _prices
        .refreshFor(results.map((p) => p.uid),
            customerUid: cart.customer?.uid, currency: cart.currency)
        .then((_) {
      if (mounted) setState(() {});
    });
  }

  @override
  void initState() {
    super.initState();
    _load();
  }

  void _load() {
    // No catalogue preload — products and prices are fetched fresh per action so
    // the till never shows stale data (a backend price/catalogue change is live).
    _refocusSearch();
    _refreshParked();
  }

  @override
  void dispose() {
    _debounce?.cancel();
    _search.dispose();
    _searchFocus.dispose();
    _resultsScroll.dispose();
    _numCtrl.dispose();
    _numFocus.dispose();
    super.dispose();
  }

  // --------------------------------------------------------------- add / scan

  /// Add a line. [saleUnit] overrides the product's base unit — used when a
  /// scanned pack barcode names its own unit; null means sell by the base unit.
  void _addProduct(Product p,
      {double quantity = 1,
      double? fixedQuantity,
      double? overridePrice,
      SaleUnit? saleUnit}) {
    final app = ref.read(appControllerProvider);
    final base = app.unitsByUid[p.baseUnitUid];
    final chosen = saleUnit ?? (base == null ? null : SaleUnit(base, 1));
    if (chosen == null) {
      showToast(context, '${p.name}: no usable unit configured.');
      return;
    }
    final cart = ref.read(cartProvider.notifier);
    cart.addProduct(p, chosen.unit,
        unitFactor: chosen.factor,
        quantity: quantity,
        fixedQuantity: fixedQuantity,
        overridePrice: overridePrice);
    final lineId = ref.read(cartProvider).selectedId;
    if (overridePrice == null && lineId != null) {
      _priceLine(lineId, p, chosen);
    }
  }

  /// Patch a line's preview price for the unit it is being sold in. The lookup
  /// honours an explicit pack price where one is set and falls back to
  /// `base × factor` otherwise — the same order the server resolves in, so the
  /// preview matches the authoritative total either way.
  void _priceLine(String lineId, Product p, SaleUnit unit) {
    // Priced by the SERVER for the basket's customer and currency (PRD-01),
    // so an account customer's own price shows before payment, not after.
    ref.read(basketPricerProvider).price(lineIds: [lineId]).then((_) {
      if (mounted) setState(() {});
    });
  }

  /// Open the unit picker for a line and apply the choice. Silently does nothing
  /// when the product has no pack configured — there is nothing to choose.
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
    // The line may have merged into an existing one of the same product+unit —
    // re-price whichever line now holds the selection, not the original id.
    final lineId = ref.read(cartProvider).selectedId;
    if (lineId != null) _priceLine(lineId, line.product, picked);
  }

  Future<void> _onSubmit(String raw) async {
    final value = raw.trim();
    if (value.isEmpty) return;
    // Enter resolves the input itself, so the search-as-you-type still waiting
    // on its debounce is redundant — and harmful: a scanner types the symbol and
    // presses Enter within milliseconds, so that search would fire AFTER the item
    // is added and re-open the dropdown over an empty box.
    _debounce?.cancel();
    setState(() => _busyScan = true);
    try {
      // 1) barcode lookup (exact or embedded weight/price) — only when the input
      // actually looks like a scanned barcode. A typed name ("oil", "cement")
      // always 404s here, so skip straight to the search below. The product is
      // then fetched FRESH by uid (no local catalogue).
      if (looksLikeBarcode(value)) {
        try {
          final bc = await ref
              .read(catalogServiceProvider)
              .lookupBarcode(_companyId, value);
          if (!mounted) return;
          final product = await _cache.productByUid(bc.productUid);
          if (!mounted) return;
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
            // A barcode may address a pack rather than the base unit (a carton
            // label). Honour that unit when it is a configured one for this
            // product; anything else falls back to the base unit.
            SaleUnit? scanned;
            final uomId = bc.uomId;
            if (uomId != null) {
              scanned = await _cache.sellableUnitById(
                  product, uomId, ref.read(appControllerProvider).unitsByUid);
              if (!mounted) return;
            }
            _addProduct(product,
                fixedQuantity: bc.derivedQuantity, saleUnit: scanned);
            _resetSearch();
            return;
          }
        } on ApiException catch (e) {
          if (!e.isNotFound) rethrow;
        }
        if (!mounted) return;
      }
      // 2) fresh server search. An exact product-code match wins (a keyed code);
      // otherwise a single hit auto-adds and multiple hits open the dropdown.
      List<Product> hits;
      try {
        hits = await ref
            .read(catalogServiceProvider)
            .searchProducts(_companyId, q: value, size: 40);
      } on ApiException {
        rethrow; // unreachable / refused — reported below, never as "No match"
      } catch (_) {
        hits = const [];
      }
      if (!mounted) return;
      Product? exact;
      for (final p in hits) {
        if (p.code.toLowerCase() == value.toLowerCase()) {
          exact = p;
          break;
        }
      }
      if (exact != null) {
        _addProduct(exact);
        _resetSearch();
      } else if (hits.length == 1) {
        _addProduct(hits.first);
        _resetSearch();
      } else if (hits.isEmpty) {
        showToast(context, 'No match for "$value".');
      } else {
        _setResults(hits, value);
        _refreshStock(value);
        _refreshPrices(hits);
      }
    } on ApiException catch (e) {
      // POS-17: a dropped connection is not "this product does not exist".
      if (mounted) {
        showToast(context,
            e.isUnreachable ? ApiException.unreachableMessage : e.message);
      }
    } finally {
      if (mounted) setState(() => _busyScan = false);
    }
  }

  void _onChanged(String v) {
    _debounce?.cancel();
    final q = v.trim();
    if (q.isEmpty) {
      _setResults(const [], '');
      return;
    }
    // Debounced fresh server search (no local cache — results are always live).
    _refreshStock(q);
    _debounce = Timer(const Duration(milliseconds: 250), () => _serverSearch(q));
  }

  Future<void> _serverSearch(String q) async {
    try {
      final hits = await ref
          .read(catalogServiceProvider)
          .searchProducts(_companyId, q: q, size: 60);
      // A reply for text that is no longer in the box is stale — the box was
      // cleared by an add, or a scan replaced it while this was in flight.
      // Showing it would open a dropdown nobody asked for.
      if (mounted && _isCurrentQuery(q)) {
        _setResults(hits, q);
        _refreshStock(q);
        _refreshPrices(hits);
      }
    } on ApiException catch (e) {
      if (mounted && _isCurrentQuery(q)) {
        _setResults(const [], q);
        // POS-17: say why the dropdown is empty when it is the link, not the
        // catalogue.
        if (e.isUnreachable) showToast(context, ApiException.unreachableMessage);
      }
    } catch (_) {
      if (mounted && _isCurrentQuery(q)) _setResults(const [], q);
    }
  }

  bool _isCurrentQuery(String q) => _search.text.trim() == q;

  /// Replace the results dropdown and highlight the first row. [query] is the
  /// search these rows answered — the stock hint needs it to distinguish "none
  /// in this branch" from "not asked yet".
  void _setResults(List<Product> r, String query) {
    setState(() {
      _results = r;
      _resultsQuery = query.trim();
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
    // Clearing the controller from code does not fire onChanged, so a pending
    // debounce would survive the reset and repopulate the dropdown.
    _debounce?.cancel();
    _search.clear();
    _setResults(const [], '');
    _refocusSearch();
  }

  // --------------------------------------------------------------- numpad

  void _numKey(String k) {
    final t = _numCtrl.text;
    final String next;
    if (k == 'C') {
      next = '';
    } else if (k == '<') {
      next = t.isNotEmpty ? t.substring(0, t.length - 1) : t;
    } else if (k == '.') {
      next = t.contains('.') ? t : (t.isEmpty ? '0.' : '$t.');
    } else {
      next = '$t$k';
    }
    _numCtrl.value = TextEditingValue(
      text: next,
      selection: TextSelection.collapsed(offset: next.length),
    );
    _numFocus.requestFocus();
  }

  void _applyNumpad() {
    final cart = ref.read(cartProvider);
    final id = cart.selectedId;
    final val = double.tryParse(_numCtrl.text);
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
    _numCtrl.clear();
    // Scanner-first: hand focus back to the search box so the next scan lands
    // there, not in the numpad.
    _refocusSearch();
  }

  // ---------------------------------------------------------- hold / recall

  /// How many baskets are on hold for this shift — shown on the Recall key.
  int _parkedCount = 0;

  Future<void> _refreshParked() async {
    final uid = ref.read(appControllerProvider).shift?.uid;
    if (uid == null) return;
    final n = (await ref.read(parkedSaleStoreProvider).list(uid)).length;
    if (mounted) setState(() => _parkedCount = n);
  }

  /// POS-06: put the basket on hold so the queue keeps moving while the
  /// customer fetches money or another item. Kept on this till only.
  Future<void> _hold() async {
    final cart = ref.read(cartProvider);
    final sessionUid = ref.read(appControllerProvider).shift?.uid;
    if (cart.isEmpty || sessionUid == null) return;
    final ok = await ref.read(parkedSaleStoreProvider).park(ParkedSale.fromCart(
        cart,
        id: ApiClient.newTxnId(),
        sessionUid: sessionUid,
        now: DateTime.now()));
    if (!mounted) return;
    if (!ok) {
      showToast(context,
          "Couldn't put this sale on hold on this till. Finish it or remove it.");
      return;
    }
    ref.read(cartProvider.notifier).clearLines();
    showToast(context, 'Sale on hold. Use Recall when the customer is back.',
        ok: true);
    await _refreshParked();
    _refocusSearch();
  }

  /// POS-06: bring a held basket back. Lines are re-priced by the server for
  /// the basket's customer — prices may have changed while it waited.
  Future<void> _recall() async {
    final app = ref.read(appControllerProvider);
    final sessionUid = app.shift?.uid;
    if (sessionUid == null) return;
    if (!ref.read(cartProvider).isEmpty) {
      showToast(context, 'Hold or finish the current sale first.');
      return;
    }
    final store = ref.read(parkedSaleStoreProvider);
    final parked = await store.list(sessionUid);
    if (!mounted) return;
    if (parked.isEmpty) {
      showToast(context, 'No sales on hold.');
      return;
    }
    final pick = await showDialog<ParkedSale>(
      context: context,
      builder: (_) => _RecallDialog(parked: parked),
    );
    if (pick == null || !mounted) return;

    final cart = ref.read(cartProvider.notifier);
    if (pick.customer != null) cart.setCustomer(pick.customer!);
    if (pick.notes != null) cart.setNotes(pick.notes);
    var skipped = 0;
    for (final l in pick.lines) {
      final unit = app.unitsByUid[l.unitUid];
      if (unit == null) {
        skipped++;
        continue;
      }
      cart.addProduct(l.product, unit,
          unitFactor: l.unitFactor, quantity: l.quantity);
      final id = ref.read(cartProvider).selectedId;
      if (id != null && l.lineDiscountAmount > 0) {
        cart.setDiscount(id, l.lineDiscountAmount);
      }
    }
    await store.remove(pick.id);
    await ref.read(basketPricerProvider).price();
    if (!mounted) return;
    await _refreshParked();
    if (skipped > 0 && mounted) {
      showToast(context,
          '$skipped item(s) could not be restored — their unit is no longer set up. Scan them again.');
    }
    _refocusSearch();
  }

  Future<void> _pay() async {
    final cart = ref.read(cartProvider);
    if (cart.isEmpty) {
      showToast(context, 'Add an item first.');
      return;
    }
    await openPaymentSheet(context, ref);
    if (mounted) _refocusSearch();
  }

  // --------------------------------------------------------------- build

  /// Below this width the basket and the number pad no longer fit side by side:
  /// phones, and tablets in portrait, get the one-column layout.
  static const double _phoneBelow = 860;

  @override
  Widget build(BuildContext context) {
    return LayoutBuilder(builder: (context, box) {
      if (box.maxWidth < _phoneBelow) return _phone(box);
      // A 1024 x 768 till has ~1000 px: a narrower pad leaves the basket its room.
      final side = box.maxWidth >= 1180 ? 340.0 : 290.0;
      return Row(
        children: [
          Expanded(child: _left()),
          SizedBox(width: side, child: _numpadPanel()),
        ],
      );
    });
  }

  // --------------------------------------------------------------- phone

  /// Phones and tablets in portrait: search, the basket as cards, and the
  /// customer, hold/recall and PAY underneath — the number pad opens on demand.
  /// A phone held sideways (little height) puts the controls beside the basket.
  Widget _phone(BoxConstraints box) {
    final cart = ref.watch(cartProvider);
    final basket = Padding(
      padding: const EdgeInsets.fromLTRB(12, 12, 12, 0),
      child: Stack(
        children: [
          Column(
            children: [
              _searchBar(),
              const SizedBox(height: 10),
              Expanded(child: _phoneBasket(cart)),
            ],
          ),
          if (_results.isNotEmpty)
            Positioned(top: 52, left: 0, right: 0, child: _resultsOverlay()),
        ],
      ),
    );
    if (box.maxHeight < 520) {
      return Row(
        children: [
          Expanded(child: basket),
          SizedBox(
            width: 300,
            child: Container(
              decoration: const BoxDecoration(
                  color: AppColors.panel, border: Border(left: BorderSide(color: AppColors.line))),
              child: SingleChildScrollView(child: _phoneControls(cart)),
            ),
          ),
        ],
      );
    }
    return Column(
      children: [
        Expanded(child: basket),
        Container(
          decoration: const BoxDecoration(
              color: AppColors.panel, border: Border(top: BorderSide(color: AppColors.line))),
          // The pad and the footer never take more than two thirds of the height.
          constraints: BoxConstraints(maxHeight: box.maxHeight * 0.75),
          child: SingleChildScrollView(child: _phoneControls(cart)),
        ),
      ],
    );
  }

  Widget _phoneControls(CartState cart) {
    return Padding(
      padding: const EdgeInsets.fromLTRB(12, 10, 12, 12),
      child: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          if (_phoneKeys) ...[
            _targetToggle(),
            _discountApprovalBar(cart),
            const SizedBox(height: 8),
            _numDisplay(),
            const SizedBox(height: 8),
            SizedBox(height: 250, child: _keys()),
            const SizedBox(height: 10),
          ],
          Row(
            children: [
              Expanded(child: _customerChip(cart)),
              const SizedBox(width: 8),
              Tooltip(
                message: _phoneKeys ? 'Hide the number pad' : 'Number pad',
                child: OutlinedButton(
                  style: OutlinedButton.styleFrom(
                      minimumSize: const Size(56, 52), padding: EdgeInsets.zero),
                  onPressed: () => setState(() => _phoneKeys = !_phoneKeys),
                  child: Icon(_phoneKeys ? Icons.keyboard_hide_outlined : Icons.dialpad),
                ),
              ),
            ],
          ),
          // While keying, the pad needs the room: Hold and Recall come back with
          // the pad closed, and PAY stays in view either way.
          if (!_phoneKeys) ...[
          const SizedBox(height: 8),
          Row(
            children: [
              Expanded(
                child: OutlinedButton.icon(
                  onPressed: cart.isEmpty ? null : _hold,
                  icon: const Icon(Icons.pause_circle_outline, size: 18),
                  label: const Text('Hold'),
                ),
              ),
              const SizedBox(width: 8),
              Expanded(
                child: OutlinedButton.icon(
                  onPressed: _recall,
                  icon: const Icon(Icons.restore, size: 18),
                  label: Text(_parkedCount > 0 ? 'Recall ($_parkedCount)' : 'Recall'),
                ),
              ),
            ],
          ),
          ],
          const SizedBox(height: 8),
          SizedBox(
            width: double.infinity,
            child: PayButton(
              label: 'PAY · ${cart.lineCount} lines',
              amount: formatAmount(cart.previewSubtotal),
              onPressed: cart.isEmpty ? null : _pay,
            ),
          ),
        ],
      ),
    );
  }

  Widget _phoneBasket(CartState cart) {
    return Container(
      decoration: BoxDecoration(
        color: AppColors.panel,
        borderRadius: AppRadii.brSm,
        border: Border.all(color: AppColors.xlLineHard),
      ),
      child: cart.lines.isEmpty
          ? _emptyGrid()
          : ListView.separated(
              itemCount: cart.lines.length,
              separatorBuilder: (_, _) => const Divider(height: 1, color: AppColors.xlLine),
              itemBuilder: (context, i) => _phoneRow(cart.lines[i], cart.selectedId),
            ),
    );
  }

  /// One basket line as a card: every action the till grid has — quantity,
  /// unit, discount, remove and void — at a size a finger can hit.
  Widget _phoneRow(CartLine line, String? selectedId) {
    final selected = line.localId == selectedId;
    final voided = line.voided;
    final ctrl = ref.read(cartProvider.notifier);
    final muted = voided ? AppColors.ink3 : AppColors.xlInk;
    final strike = voided ? TextDecoration.lineThrough : null;

    void edit(_NumTarget t) {
      ctrl.select(line.localId);
      setState(() {
        _target = t;
        _phoneKeys = true;
      });
      _numFocus.requestFocus();
    }

    Widget chip(Widget child, {VoidCallback? onTap, bool hi = false}) => InkWell(
          borderRadius: AppRadii.brSm,
          onTap: onTap,
          child: Container(
            constraints: const BoxConstraints(minHeight: 36),
            padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 6),
            decoration: BoxDecoration(
              color: hi ? AppColors.brandSoft : AppColors.panel2,
              borderRadius: AppRadii.brSm,
              border: Border.all(color: AppColors.line2),
            ),
            child: child,
          ),
        );

    final qtyHi = selected && _target == _NumTarget.qty;
    final discHi = selected && _target == _NumTarget.disc;

    return Material(
      color: selected ? AppColors.xlSel : AppColors.panel,
      child: InkWell(
        onTap: () => ctrl.select(line.localId),
        child: Padding(
          padding: const EdgeInsets.fromLTRB(12, 10, 4, 10),
          child: Row(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Row(
                      children: [
                        Flexible(
                          child: Text(line.product.name,
                              maxLines: 2,
                              overflow: TextOverflow.ellipsis,
                              style: TextStyle(
                                  fontSize: 14.5,
                                  fontWeight: FontWeight.w600,
                                  color: muted,
                                  decoration: strike)),
                        ),
                        if (line.product.restrictedKind.isRestricted) ...[
                          const SizedBox(width: 6),
                          _agePill(line.product.restrictedKind.ageLabel),
                        ],
                        if (!voided) _shortStockTag(line),
                      ],
                    ),
                    Text(line.product.code,
                        style: const TextStyle(
                            fontFamily: 'Consolas', fontSize: 11.5, color: Color(0xFF5F6368))),
                    const SizedBox(height: 6),
                    Wrap(
                      spacing: 6,
                      runSpacing: 6,
                      crossAxisAlignment: WrapCrossAlignment.center,
                      children: [
                        chip(
                            NumText(
                                '× ${formatAmount(line.quantity, decimals: line.unit.fractional ? 3 : 0)}',
                                style: numStyle(size: 13.5, color: muted)),
                            hi: qtyHi,
                            onTap: voided ? null : () => edit(_NumTarget.qty)),
                        chip(
                            Row(mainAxisSize: MainAxisSize.min, children: [
                              Text(line.unit.code,
                                  style: TextStyle(
                                      fontSize: 12.5,
                                      fontWeight:
                                          line.unitFactor == 1 ? FontWeight.w400 : FontWeight.w700,
                                      color: line.unitFactor == 1
                                          ? const Color(0xFF5F6368)
                                          : AppColors.brand)),
                              const Icon(Icons.arrow_drop_down, size: 16, color: Color(0xFF9AA0A6)),
                            ]),
                            onTap: voided
                                ? null
                                : () {
                                    ctrl.select(line.localId);
                                    _pickUnit(line);
                                  }),
                        NumText(
                            line.unitPricePreview == null
                                ? '@ —'
                                : '@ ${formatAmount(line.unitPricePreview!)}',
                            style: numStyle(
                                size: 12.5, weight: FontWeight.w400, color: const Color(0xFF5F6368))),
                        chip(
                            Row(mainAxisSize: MainAxisSize.min, children: [
                              const Text('Disc ',
                                  style: TextStyle(fontSize: 11.5, color: AppColors.ink3)),
                              _discountCell(line),
                            ]),
                            hi: discHi,
                            onTap: voided ? null : () => edit(_NumTarget.disc)),
                      ],
                    ),
                  ],
                ),
              ),
              Column(
                crossAxisAlignment: CrossAxisAlignment.end,
                children: [
                  Padding(
                    padding: const EdgeInsets.only(right: 8),
                    child: NumText(formatAmount(line.previewGross),
                        style: numStyle(size: 15, weight: FontWeight.w800, color: muted)),
                  ),
                  Row(
                    mainAxisSize: MainAxisSize.min,
                    children: [
                      IconButton(
                        tooltip: voided ? 'Undo void' : 'Void',
                        onPressed: () => ctrl.toggleVoid(line.localId),
                        icon: Icon(voided ? Icons.check_box : Icons.check_box_outline_blank,
                            color: voided ? AppColors.warn : AppColors.ink3),
                      ),
                      IconButton(
                        tooltip: 'Remove',
                        onPressed: () => ctrl.removeLine(line.localId),
                        icon: const Icon(Icons.close, color: AppColors.ink3),
                      ),
                    ],
                  ),
                ],
              ),
            ],
          ),
        ),
      ),
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
                  keyboardType: _android && !_typing ? TextInputType.none : null,
                  onTap: _typeInSearch,
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
                    // Only pick from the dropdown when it answers what is in the
                    // box now. A scan lands and submits before its own search
                    // has run, so rows still showing belong to the PREVIOUS
                    // input — picking one would add the last item again instead
                    // of the one just scanned.
                    if (_results.isNotEmpty &&
                        _resultIndex >= 0 &&
                        _resultsQuery == v.trim()) {
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
                      _priceTag(p),
                      _stockTag(p.id),
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
    return LayoutBuilder(builder: (context, box) {
      final cols = _Cols.forWidth(box.maxWidth);
      return Container(
        decoration: BoxDecoration(
          color: AppColors.panel,
          border: Border.all(color: AppColors.xlLineHard),
        ),
        child: Column(
          children: [
            _gridHeader(cols),
            Expanded(
              child: cart.lines.isEmpty
                  ? _emptyGrid()
                  : ListView.builder(
                      itemCount: cart.lines.length,
                      itemBuilder: (context, i) =>
                          _gridRow(cart.lines[i], i + 1, cart.selectedId, cols),
                    ),
            ),
          ],
        ),
      );
    });
  }

  Widget _gridHeader(_Cols c) {
    const h = TextStyle(
        fontSize: 11,
        fontWeight: FontWeight.w700,
        color: Color(0xFF374151),
        letterSpacing: .4);
    Widget cell(String t,
        {double? width, int? flex, bool right = false, bool center = false}) {
      final content = Container(
        padding: const EdgeInsets.symmetric(horizontal: 9, vertical: 7),
        alignment: right
            ? Alignment.centerRight
            : (center ? Alignment.center : Alignment.centerLeft),
        child: Text(t.toUpperCase(),
            style: h, maxLines: 1, overflow: TextOverflow.ellipsis),
      );
      return flex != null
          ? Expanded(flex: flex, child: content)
          : SizedBox(width: width, child: content);
    }

    return Container(
      decoration: const BoxDecoration(
        color: AppColors.xlHead,
        border: Border(bottom: BorderSide(color: AppColors.xlLineHard)),
      ),
      child: Row(
        children: [
          if (!c.compact) cell('#', width: c.idx, center: true),
          if (!c.compact) cell('Code', width: c.code),
          cell('Item', flex: 1),
          cell('Unit', width: c.unit, center: true),
          cell('Qty', width: c.qty, right: true),
          cell('Price', width: c.price, right: true),
          cell('Disc', width: c.disc, right: true),
          cell('Total', width: c.total, right: true),
          cell('', width: c.act, center: true),
          cell('V', width: c.act, center: true),
        ],
      ),
    );
  }

  Widget _gridRow(CartLine line, int idx, String? selectedId, _Cols c) {
    final selected = line.localId == selectedId;
    final voided = line.voided;
    final ctrl = ref.read(cartProvider.notifier);
    final muted = voided ? AppColors.ink3 : AppColors.xlInk;
    final strike = voided ? TextDecoration.lineThrough : null;

    Widget cell(Widget child,
        {double? width,
        int? flex,
        Alignment align = Alignment.centerLeft,
        VoidCallback? onTap,
        Color? bg}) {
      final content = InkWell(
        onTap: onTap,
        child: Container(
          // Compact rows carry the code under the name, so they need two lines.
          height: c.compact ? 42 : 30,
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
      return flex != null
          ? Expanded(flex: flex, child: content)
          : SizedBox(width: width, child: content);
    }

    void selectQty() {
      ctrl.select(line.localId);
      setState(() => _target = _NumTarget.qty);
      // Focus the numpad so the hardware keyboard edits this line immediately.
      _numFocus.requestFocus();
    }

    void selectDisc() {
      ctrl.select(line.localId);
      setState(() => _target = _NumTarget.disc);
      _numFocus.requestFocus();
    }

    final qtyHi = selected && _target == _NumTarget.qty;
    final discHi = selected && _target == _NumTarget.disc;

    return Container(
      color: selected ? AppColors.xlSel : AppColors.panel,
      child: Row(
        children: [
          if (!c.compact)
            cell(
                Text('$idx',
                    style: const TextStyle(
                        fontSize: 12, color: Color(0xFF9AA0A6))),
                width: c.idx,
                align: Alignment.center,
                bg: AppColors.xlHead,
                onTap: () => ctrl.select(line.localId)),
          if (!c.compact)
            cell(
                Text(line.product.code,
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                    style: TextStyle(
                        fontFamily: 'Consolas',
                        fontSize: 12,
                        color: const Color(0xFF5F6368),
                        decoration: strike)),
                width: c.code,
                onTap: () => ctrl.select(line.localId)),
          cell(
              Column(
                mainAxisAlignment: MainAxisAlignment.center,
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Row(
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
                      if (!voided) _shortStockTag(line),
                    ],
                  ),
                  if (c.compact)
                    Text(line.product.code,
                        maxLines: 1,
                        overflow: TextOverflow.ellipsis,
                        style: const TextStyle(
                            fontFamily: 'Consolas', fontSize: 11, color: Color(0xFF5F6368))),
                ],
              ),
              flex: 1,
              onTap: () => ctrl.select(line.localId)),
          cell(
              Row(
                mainAxisAlignment: MainAxisAlignment.center,
                children: [
                  Flexible(
                    child: Text(line.unit.code,
                        maxLines: 1,
                        overflow: TextOverflow.ellipsis,
                        style: TextStyle(
                            fontSize: 12,
                            fontWeight: line.unitFactor == 1
                                ? FontWeight.w400
                                : FontWeight.w700,
                            color: line.unitFactor == 1
                                ? const Color(0xFF5F6368)
                                : AppColors.brand)),
                  ),
                  const Icon(Icons.arrow_drop_down,
                      size: 14, color: Color(0xFF9AA0A6)),
                ],
              ),
              width: c.unit,
              align: Alignment.center,
              onTap: voided
                  ? null
                  : () {
                      ctrl.select(line.localId);
                      _pickUnit(line);
                    }),
          cell(
              NumText(
                  formatAmount(line.quantity,
                      decimals: line.unit.fractional ? 3 : 0),
                  style: numStyle(size: 13.5, color: muted)),
              width: c.qty,
              align: Alignment.centerRight,
              bg: qtyHi ? AppColors.brandSoft : null,
              onTap: selectQty),
          cell(
              NumText(
                  line.unitPricePreview == null
                      ? '—'
                      : formatAmount(line.unitPricePreview!),
                  style: numStyle(
                      size: 13.5, weight: FontWeight.w400, color: const Color(0xFF5F6368))),
              width: c.price,
              align: Alignment.centerRight,
              onTap: () => ctrl.select(line.localId)),
          cell(
              _discountCell(line),
              width: c.disc,
              align: Alignment.centerRight,
              bg: discHi ? AppColors.brandSoft : null,
              onTap: selectDisc),
          cell(
              NumText(formatAmount(line.previewGross),
                  style: numStyle(
                      size: 13.5, weight: FontWeight.w700, color: muted)),
              width: c.total,
              align: Alignment.centerRight,
              onTap: () => ctrl.select(line.localId)),
          cell(
              Icon(Icons.close,
                  size: 14,
                  color: voided ? AppColors.ink3 : const Color(0xFFC4C9CF)),
              width: c.act,
              align: Alignment.center,
              onTap: () => ctrl.removeLine(line.localId)),
          cell(
              Icon(voided ? Icons.check_box : Icons.check_box_outline_blank,
                  size: 15, color: voided ? AppColors.warn : AppColors.ink3),
              width: c.act,
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

  /// The selling price for one base unit, as the SERVER resolves it (K6).
  ///
  /// Price is the half of the picture the dropdown was missing: stock told the
  /// cashier whether they *could* sell it, and nothing told them what it costs,
  /// so every price question meant adding the line to find out and then
  /// removing it again.
  ///
  /// Three states, all distinguishable: a figure, an explicit "no price", and
  /// nothing at all while the answer is still in flight (or when this till may
  /// not read prices — implying "unpriced" would be a lie).
  Widget _priceTag(Product p) {
    if (_prices.denied) return const SizedBox.shrink();
    final price = _prices.priceFor(p.uid);
    if (price == null) return const SizedBox.shrink();
    if (!price.isResolved) {
      return const Padding(
        padding: EdgeInsets.only(left: 8),
        child: Text('no price',
            style: TextStyle(
                fontSize: 11,
                fontStyle: FontStyle.italic,
                color: AppColors.warn)),
      );
    }
    final app = ref.read(appControllerProvider);
    // Same VAT stance as the cart preview: an inclusive price list is already
    // gross, so VAT is added exactly once or not at all.
    final gross = app.grossUnitPrice(price.amount!, p.vatStatus,
        vatInclusive: price.vatInclusive);
    return Padding(
      padding: const EdgeInsets.only(left: 8),
      child: Text(formatAmount(gross),
          maxLines: 1,
          softWrap: false,
          style: numStyle(size: 12.5, weight: FontWeight.w700)),
    );
  }

  /// Compact stock hint for a product row: muted "N in stock", or a red
  /// "Out of stock" when the branch holds none.
  ///
  /// "Out of stock" is now stated explicitly. A product with no on-hand row is
  /// simply absent from `/stock/on-hand`, and the old code rendered that as
  /// nothing — identical to still-loading — so the most important case looked
  /// like the most reassuring one.
  ///
  /// The figure is what checkout enforces when the server reports it, and the
  /// physical shelf count when it does not (see [StockLevel]); the label says
  /// which, rather than promising stock the sale might then refuse.
  Widget _stockTag(String productId) {
    final level = _stock.levelFor(productId, query: _resultsQuery);
    if (level == null) return const SizedBox.shrink();
    final qty = level.sellable;
    final out = qty <= 0;
    final text = out
        ? 'Out of stock'
        : '${formatAmount(qty, decimals: qty % 1 == 0 ? 0 : 2)} '
            '${level.isAuthoritative ? 'available' : 'in stock'}';
    return Padding(
      padding: const EdgeInsets.only(left: 8),
      child: Text(
        text,
        style: TextStyle(
          fontSize: 11,
          fontWeight: FontWeight.w600,
          color: out ? AppColors.danger : AppColors.ink3,
        ),
      ),
    );
  }

  /// Cart-line short-stock warning: a small red tag when the branch on-hand is
  /// known and is below the quantity being rung (so the cashier sees it before
  /// hitting Pay, not as a checkout-time rejection). Silent when stock is
  /// unknown or sufficient.
  Widget _shortStockTag(CartLine line) {
    final level = _stock.levelForProduct(line.product.id);
    final oh = level?.sellable;
    // On-hand is always in BASE units, so a pack line is compared against the
    // base equivalent (2 cartons of 24 needs 48) — otherwise selling 2 cartons
    // out of 30 pieces would look fine and fail at checkout.
    if (oh == null || oh >= line.baseQuantity) return const SizedBox.shrink();
    // Name the unit on a pack line — a bare "only 30 left" against 2 cartons
    // reads as a contradiction unless it says 30 PCS.
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

  /// The Disc cell. A discount that a manager has approved carries a small tick
  /// so the approval is visible on the line it belongs to, not buried in a
  /// toast that has already faded.
  ///
  /// **The till never decides whether an approval is needed.** The ceiling is
  /// company policy held by the server, which is the only thing that can
  /// enforce it — the client would just be a second, drifting copy. So there is
  /// no "too big" styling here: the cashier may pre-authorise from the line
  /// (long-press), and if the server refuses at Pay the payment sheet offers
  /// the same approval there.
  Widget _discountCell(CartLine line) {
    if (line.lineDiscountAmount == 0) {
      return NumText('·', style: numStyle(size: 13.5, color: AppColors.ink3));
    }
    final approved = line.discountAuthorisedByUid != null;
    return Row(
      mainAxisAlignment: MainAxisAlignment.end,
      children: [
        if (approved)
          const Padding(
            padding: EdgeInsets.only(right: 3),
            child: Icon(Icons.verified_user,
                size: 12, color: AppColors.ok),
          ),
        Flexible(
          child: NumText(formatAmount(line.lineDiscountAmount),
              style: numStyle(size: 13.5, color: AppColors.brand)),
        ),
      ],
    );
  }

  /// Asks a manager to authorise this line's discount before it is sent.
  ///
  /// Optional: the server is the authority on whether the discount needs one at
  /// all, so this exists purely so a cashier who already knows can get the
  /// approval while the manager is standing there, instead of discovering it at
  /// Pay with a queue behind them.
  Future<void> _approveDiscount(CartLine line) async {
    if (line.lineDiscountAmount <= 0) {
      showToast(context, 'Set a discount on the line first.');
      return;
    }
    final approval = await showManagerApproval(
      context,
      ref,
      action: GatedAction.discountOverride,
      detail: '${line.product.name}: less '
          '${formatAmount(line.lineDiscountAmount)} $_currency',
    );
    if (approval == null || !mounted) return;
    final uid = approval.authoriserUid?.trim() ?? '';
    if (uid.isEmpty) return;
    ref
        .read(cartProvider.notifier)
        .setDiscountAuthorisation(line.localId, uid, approval.approverLabel);
    showToast(context, 'Approved by ${approval.approverLabel}.', ok: true);
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
          _discountApprovalBar(cart),
          const SizedBox(height: 8),
          _numDisplay(),
          const SizedBox(height: 8),
          // Keys fill a short screen and stop growing on a tall one.
          Expanded(
            child: Align(
              alignment: Alignment.topCenter,
              child: ConstrainedBox(
                  constraints: const BoxConstraints(maxHeight: 380), child: _keys()),
            ),
          ),
          const SizedBox(height: 10),
          Row(
            children: [
              Expanded(
                child: OutlinedButton.icon(
                  onPressed: cart.isEmpty ? null : _hold,
                  icon: const Icon(Icons.pause_circle_outline, size: 18),
                  label: const Text('Hold'),
                ),
              ),
              const SizedBox(width: 8),
              Expanded(
                child: OutlinedButton.icon(
                  onPressed: _recall,
                  icon: const Icon(Icons.restore, size: 18),
                  label: Text(_parkedCount > 0
                      ? 'Recall ($_parkedCount)'
                      : 'Recall'),
                ),
              ),
            ],
          ),
          const SizedBox(height: 8),
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
          NumText(formatAmount(cart.previewSubtotal),
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
                  Text(cart.customer?.displayName ?? 'Select customer',
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
          onTap: () {
            setState(() => _target = t);
            _numFocus.requestFocus();
          },
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

  /// Sits directly under the Qty/Disc toggle — where the cashier's eyes already
  /// are the moment a discount is keyed — offering the manager approval, or
  /// confirming the one already attached.
  ///
  /// Optional by design: the server owns the ceiling and will refuse anything
  /// over it at Pay, where the same prompt is offered again. This is only about
  /// getting the manager's approval while they are still standing there.
  Widget _discountApprovalBar(CartState cart) {
    final id = cart.selectedId;
    if (id == null) return const SizedBox.shrink();
    CartLine? line;
    for (final l in cart.lines) {
      if (l.localId == id) {
        line = l;
        break;
      }
    }
    if (line == null || line.voided || line.lineDiscountAmount <= 0) {
      return const SizedBox.shrink();
    }
    final approvedBy = line.discountAuthorisedByName;
    if (approvedBy != null) {
      return Padding(
        padding: const EdgeInsets.only(top: 8),
        child: Container(
          padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 7),
          decoration: BoxDecoration(
            color: AppColors.okSoft,
            borderRadius: AppRadii.brSm,
            border: Border.all(color: const Color(0xFFBBF7D0)),
          ),
          child: Row(
            children: [
              const Icon(Icons.verified_user, size: 14, color: AppColors.ok),
              const SizedBox(width: 8),
              Expanded(
                child: Text('Discount approved by $approvedBy',
                    maxLines: 2,
                    style: const TextStyle(
                        fontSize: 11.5,
                        fontWeight: FontWeight.w600,
                        color: AppColors.ok)),
              ),
            ],
          ),
        ),
      );
    }
    final target = line;
    return Padding(
      padding: const EdgeInsets.only(top: 8),
      child: OrbixButton(
        label: 'Approve discount…',
        icon: Icons.verified_user_outlined,
        kind: BtnKind.ghost,
        block: true,
        onPressed: () => _approveDiscount(target),
      ),
    );
  }

  Widget _numDisplay() {
    return Container(
      width: double.infinity,
      padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 5),
      decoration: BoxDecoration(
        color: AppColors.panel2,
        borderRadius: AppRadii.brSm,
        border: Border.all(color: AppColors.line2),
      ),
      child: TextField(
        controller: _numCtrl,
        focusNode: _numFocus,
        textAlign: TextAlign.right,
        // The keypad beside it does the typing on Android.
        keyboardType: _android
            ? TextInputType.none
            : const TextInputType.numberWithOptions(decimal: true),
        inputFormatters: [decimalInputFormatter],
        onSubmitted: (_) => _applyNumpad(),
        style: numStyle(size: 26, weight: FontWeight.w800),
        decoration: const InputDecoration(
          // Same inherited `filled: true` from the global theme. The text here is dark, so this is
          // cosmetic - a wrong-coloured box, not a value you cannot read - but it is the same defect
          // and it is fixed the same way.
          filled: false,
          fillColor: Colors.transparent,
          isDense: true,
          contentPadding: EdgeInsets.symmetric(vertical: 6),
          border: InputBorder.none,
          enabledBorder: InputBorder.none,
          focusedBorder: InputBorder.none,
          hintText: '0',
        ),
      ),
    );
  }

  Widget _keys() {
    const rows = [
      ['7', '8', '9'],
      ['4', '5', '6'],
      ['1', '2', '3'],
      ['.', '0', '<'],
    ];
    // Rows share the height the panel has, so no key is ever clipped — a fixed
    // aspect ratio cut the bottom rows off on short screens.
    return Column(
      children: [
        for (var r = 0; r < rows.length; r++) ...[
          if (r > 0) const SizedBox(height: 8),
          Expanded(
            child: Row(
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                for (var c = 0; c < 3; c++) ...[
                  if (c > 0) const SizedBox(width: 8),
                  Expanded(child: _key(rows[r][c])),
                ],
              ],
            ),
          ),
        ],
        const SizedBox(height: 8),
        Row(
          children: [
            Expanded(
                child: OrbixButton(
                    label: 'Clear',
                    kind: BtnKind.clear,
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
      color: AppColors.key,
      borderRadius: AppRadii.brSm,
      child: InkWell(
        borderRadius: AppRadii.brSm,
        onTap: () => _numKey(k),
        child: Container(
          alignment: Alignment.center,
          decoration: BoxDecoration(
            borderRadius: AppRadii.brSm,
            border: Border.all(color: AppColors.keyLine),
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

/// The basket grid's column widths for the space it has. Numeric columns stay
/// FIXED so figures line up; only the item name flexes. From ~900 px the full
/// set; below it (a 1024 x 768 till, a tablet) the code moves under the name
/// and the numbers tighten, so nothing overflows.
class _Cols {
  const _Cols({
    required this.idx,
    required this.code,
    required this.unit,
    required this.qty,
    required this.price,
    required this.disc,
    required this.total,
    required this.act,
  });

  final double idx, code, unit, qty, price, disc, total, act;

  bool get compact => code == 0;

  static _Cols forWidth(double width) => width >= 900
      ? const _Cols(idx: 36, code: 116, unit: 54, qty: 88, price: 128, disc: 82, total: 136, act: 30)
      : const _Cols(idx: 0, code: 0, unit: 48, qty: 64, price: 92, disc: 70, total: 104, act: 28);
}

/// The list of baskets on hold for this shift (POS-06), oldest first.
class _RecallDialog extends StatelessWidget {
  const _RecallDialog({required this.parked});
  final List<ParkedSale> parked;

  @override
  Widget build(BuildContext context) {
    String hhmm(DateTime t) =>
        '${t.hour.toString().padLeft(2, '0')}:${t.minute.toString().padLeft(2, '0')}';
    return AlertDialog(
      shape: RoundedRectangleBorder(borderRadius: AppRadii.brLg),
      title: const Text('Sales on hold'),
      content: ConstrainedBox(
        constraints: const BoxConstraints(maxWidth: 440, maxHeight: 420),
        child: ListView.separated(
          shrinkWrap: true,
          itemCount: parked.length,
          separatorBuilder: (_, _) => const Divider(height: 1),
          itemBuilder: (context, i) {
            final p = parked[i];
            final who = p.customer == null || p.customer!.isWalkIn
                ? 'Walk-in'
                : p.customer!.displayName;
            return ListTile(
              leading: const Icon(Icons.shopping_basket_outlined,
                  color: AppColors.brand),
              title: Text(p.label, maxLines: 1, overflow: TextOverflow.ellipsis),
              subtitle: Text('$who · held at ${hhmm(p.parkedAt.toLocal())}',
                  style: const TextStyle(fontSize: 12)),
              trailing: Text(formatMoneyParts(p.previewTotal, p.currency),
                  style: numStyle(weight: FontWeight.w700)),
              onTap: () => Navigator.pop(context, p),
            );
          },
        ),
      ),
      actions: [
        TextButton(
            onPressed: () => Navigator.pop(context),
            child: const Text('Cancel')),
      ],
    );
  }
}
