import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../app/theme.dart';
import '../../core/api/api_exception.dart';
import '../../core/config/step_up_policy.dart';
import '../../core/money.dart';
import '../../models/auth.dart';
import '../../models/catalog.dart';
import '../../models/restaurant.dart';
import '../../services/restaurant_service.dart';
import '../../state/app_controller.dart';
import '../../state/cart_controller.dart';
import '../../state/price_cache.dart';
import '../../state/providers.dart';
import '../../widgets/ui.dart';
import '../auth/approval_dialog.dart';
import '../payment/payment_sheet.dart';
import '../restaurant/restaurant_dialogs.dart';
import '../restaurant/restaurant_printing.dart';
import '../restaurant/slip_text.dart';

/// Restaurant register (ERP ADR-0065): the floor, then one order at a time.
///
/// Order → **Send** (a slip per station: food to the kitchen, drinks to the
/// bar) → **Bill** (printed here, at the cashier; not a receipt) → **Pay** (the
/// order becomes a POS sale, the receipt prints, the table is free again).
///
/// The open order lives on the ERP, never on this till: every action asks the
/// server and redraws from its answer, so a restart loses nothing and another
/// device sees the same table.
class RestaurantRegister extends ConsumerStatefulWidget {
  const RestaurantRegister({super.key});
  @override
  ConsumerState<RestaurantRegister> createState() => _RestaurantRegisterState();
}

enum _Load { loading, ready, error, forbidden, unsupported }

class _RestaurantRegisterState extends ConsumerState<RestaurantRegister> {
  // ---------------------------------------------------------------- floor
  _Load _load = _Load.loading;
  List<RestaurantTable> _tables = const [];
  List<RestaurantOrder> _live = const [];
  List<PrepStation> _stations = const [];

  /// The menu's choices (ADR-0066 D-4), and which items ask them (product uid →
  /// group uids). Empty against a server without them.
  List<ModifierGroup> _groups = const [];
  Map<String, List<String>> _links = const {};

  // ---------------------------------------------------------------- order
  RestaurantOrder? _order;
  bool _busy = false;

  // ---------------------------------------------------------------- menu
  final _q = TextEditingController();
  List<Product> _menu = const [];
  bool _menuLoading = true;
  Timer? _debounce;

  /// Products seen on the menu, by id — for the age question at payment.
  final Map<String, Product> _seen = {};

  final _printing = const RestaurantPrinting();

  RestaurantService get _svc => ref.read(restaurantServiceProvider);

  /// Only a till with an open shift takes money; a waiter's device does not.
  bool get _canTakePayment {
    final app = ref.read(appControllerProvider);
    return app.shift != null && app.can(Perms.saleCreate);
  }
  String get _companyId => ref.read(appControllerProvider).context!.companyId;
  PriceCache get _prices => ref.read(priceCacheProvider);

  @override
  void initState() {
    super.initState();
    _loadFloor();
    _browse();
  }

  @override
  void dispose() {
    _debounce?.cancel();
    _q.dispose();
    super.dispose();
  }

  // ================================================================ data

  Future<void> _loadFloor() async {
    setState(() => _load = _Load.loading);
    try {
      final results = await Future.wait([
        _svc.listTables(),
        _svc.listLiveOrders(),
        _svc.listStations(),
      ]);
      if (!mounted) return;
      setState(() {
        _tables = (results[0] as List<RestaurantTable>).where((t) => t.active).toList();
        _live = results[1] as List<RestaurantOrder>;
        _stations = results[2] as List<PrepStation>;
        _load = _Load.ready;
      });
    } on ApiException catch (e) {
      if (!mounted) return;
      setState(() => _load = e.isForbidden
          ? _Load.forbidden
          : e.isNotFound
              ? _Load.unsupported
              : _Load.error);
      return;
    }
    await _loadChoices();
  }

  /// Best effort: a menu without choices (or an older server) just adds plain.
  Future<void> _loadChoices() async {
    try {
      final groups = await _svc.listModifierGroups();
      final links = await _svc.listModifierLinks();
      if (mounted) {
        setState(() {
          _groups = groups;
          _links = links;
        });
      }
    } catch (_) {/* plain items only */}
  }

  /// The active groups [p] offers, in order.
  List<ModifierGroup> _groupsFor(Product p) {
    final uids = _links[p.uid] ?? const [];
    return [
      for (final uid in uids) ?_groups.where((g) => g.uid == uid).firstOrNull,
    ];
  }

  Future<void> _browse() async {
    try {
      final hits = await ref
          .read(catalogServiceProvider)
          .searchProducts(_companyId, size: 120);
      _setMenu(hits);
    } catch (_) {
      if (mounted) setState(() => _menu = const []);
    }
    if (mounted) setState(() => _menuLoading = false);
  }

  void _filter(String q) {
    _debounce?.cancel();
    final s = q.trim();
    if (s.isEmpty) {
      _browse();
      return;
    }
    _debounce = Timer(const Duration(milliseconds: 250), () async {
      try {
        final hits = await ref
            .read(catalogServiceProvider)
            .searchProducts(_companyId, q: s, size: 120);
        _setMenu(hits);
      } catch (_) {}
    });
  }

  void _setMenu(List<Product> hits) {
    if (!mounted) return;
    setState(() {
      _menu = hits;
      for (final p in hits) {
        _seen[p.id] = p;
      }
    });
    _refreshPrices(hits);
  }

  /// Prices the visible menu in one batch request from the ERP's own resolver,
  /// so a tile shows what the sale will charge.
  void _refreshPrices(List<Product> items) {
    if (items.isEmpty) return;
    final cart = ref.read(cartProvider);
    _prices
        .refreshFor(items.map((p) => p.uid),
            customerUid: cart.customer?.uid, currency: cart.currency)
        .then((_) {
      if (mounted) setState(() {});
    });
  }

  /// Runs one server action with the busy flag up, showing any refusal.
  Future<T?> _run<T>(Future<T> Function() action) async {
    if (_busy) return null;
    setState(() => _busy = true);
    try {
      return await action();
    } on ApiException catch (e) {
      if (mounted) showToast(context, e.message);
      return null;
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  void _showOrder(RestaurantOrder o) => setState(() => _order = o);

  Future<void> _backToFloor() async {
    setState(() => _order = null);
    // A paid sale cleared the price cache; re-price the menu for the next order.
    _refreshPrices(_menu);
    await _loadFloor();
  }

  // ================================================================ floor actions

  Future<void> _tapTable(RestaurantTable t) async {
    final here = _live.where((o) => o.tableUid == t.uid).toList();
    if (here.length == 1) {
      final fresh = await _run(() => _svc.get(here.first.uid));
      if (fresh != null) _showOrder(fresh);
      return;
    }
    if (here.length > 1) {
      final pick = await pickOrderAtTable(context, t.name, here);
      if (pick == null || !mounted) return;
      final chosen = pick.order;
      if (chosen != null) {
        final fresh = await _run(() => _svc.get(chosen.uid));
        if (fresh != null) _showOrder(fresh);
        return;
      }
    }
    final guests = await askGuestCount(context, t.name);
    if (guests == null || !mounted) return;
    final opened = await _run(
        () => _svc.open(tableUid: t.uid, guestCount: guests == 0 ? null : guests));
    if (opened != null) _showOrder(opened);
  }

  Future<void> _newTakeaway() async {
    final opened = await _run(() => _svc.open());
    if (opened != null) _showOrder(opened);
  }

  Future<void> _openExisting(RestaurantOrder o) async {
    final fresh = await _run(() => _svc.get(o.uid));
    if (fresh != null) _showOrder(fresh);
  }

  // ================================================================ order actions

  /// A tap: straight onto the order, unless the item asks a choice ("Milk?").
  Future<void> _add(Product p) async {
    if (_groupsFor(p).isNotEmpty) return _addWithChoices(p);
    await _addItem(p);
  }

  /// A long-press (or an item with choices): choices, course, hold and a note.
  Future<void> _addWithChoices(Product p) async {
    final choice = await askItemChoices(context, p.name, _groupsFor(p));
    if (choice == null || !mounted) return;
    await _addItem(p,
        note: choice.note,
        optionUids: choice.optionUids,
        course: choice.course,
        held: choice.held);
  }

  Future<void> _addItem(Product p,
      {String? note, List<String> optionUids = const [], int? course, bool held = false}) async {
    final order = _order;
    if (order == null) return;
    final unit = ref.read(appControllerProvider).unitsByUid[p.baseUnitUid];
    if (unit == null) {
      showToast(context, '${p.name}: no usable unit.');
      return;
    }
    final updated = await _run(() => _svc.addItem(order.uid,
        productId: p.id,
        unitId: unit.id,
        note: note,
        optionUids: optionUids,
        course: course,
        held: held));
    if (updated != null) _showOrder(updated);
  }

  /// Hold an unsent item back, or release it (ADR-0066 D-5).
  Future<void> _toggleHold(RestaurantOrderLine l) => _plan(l, held: !l.held);

  /// Moves an unsent item to the next course (1 → 2 → 3 → 1).
  Future<void> _nextCourse(RestaurantOrderLine l) => _plan(l, course: l.course % 3 + 1);

  Future<void> _plan(RestaurantOrderLine l, {int? course, bool? held}) async {
    final order = _order;
    if (order == null) return;
    final updated = await _run(() => _svc.changeItem(order.uid, l.uid,
        quantity: l.quantity, note: l.note, course: course, held: held));
    if (updated != null) _showOrder(updated);
  }

  /// Fires held items: the one course waiting, or the one the waiter picks.
  Future<void> _fire() async {
    final order = _order;
    if (order == null) return;
    final courses = order.lines
        .where((l) => l.isPending && l.held && !l.isAddOn)
        .map((l) => l.course)
        .toSet()
        .toList()
      ..sort();
    if (courses.isEmpty) return;
    final course = courses.length == 1 ? courses.first : await askFireCourse(context, courses);
    if (course == null || !mounted) return;
    final res = await _run(() => _svc.fire(order.uid, course: course));
    if (res == null || !mounted) return;
    _showOrder(res.order);
    await _printSlips(res.slips, okMessage: 'Course $course sent');
  }

  Future<void> _changeQty(RestaurantOrderLine l, double delta) async {
    final order = _order;
    if (order == null) return;
    final next = l.quantity + delta;
    final updated = await _run(() => next < 1
        ? _svc.removeItem(order.uid, l.uid)
        : _svc.changeItem(order.uid, l.uid, quantity: next, note: l.note));
    if (updated != null) _showOrder(updated);
  }

  Future<void> _remove(RestaurantOrderLine l) async {
    final order = _order;
    if (order == null) return;
    final updated = await _run(() => _svc.removeItem(order.uid, l.uid));
    if (updated != null) _showOrder(updated);
  }

  Future<void> _send({bool quiet = false}) async {
    final order = _order;
    if (order == null) return;
    final res = await _run(() => _svc.send(order.uid));
    if (res == null || !mounted) return;
    _showOrder(res.order);
    await _printSlips(res.slips, okMessage: quiet ? null : 'Sent');
  }

  /// Prints slips; reports the ones that failed with a reprint per station.
  Future<void> _printSlips(List<PrepSlip> slips, {String? okMessage}) async {
    if (slips.isEmpty) {
      if (okMessage != null && mounted) {
        showToast(context, '$okMessage. No kitchen or bar printer is set up for this branch.');
      }
      return;
    }
    final failures = await _printing.printSlips(slips);
    if (!mounted) return;
    if (failures.isEmpty) {
      if (okMessage != null) {
        showToast(context, '$okMessage to ${slips.map((s) => s.stationName).join(' and ')}.',
            ok: true);
      }
      return;
    }
    await showSlipFailures(context, failures, (slip) async {
      final again = await _printing.printSlips([slip]);
      return again.isEmpty ? null : again.first.message;
    });
  }

  Future<void> _reprintLast() async {
    final order = _order;
    if (order == null) return;
    final slips = await _run(() => _svc.slips(order.uid));
    if (slips == null || !mounted) return;
    if (slips.isEmpty) {
      showToast(context, 'Nothing has been sent for this order yet.');
      return;
    }
    await _printSlips(slips, okMessage: 'Reprinted');
  }

  /// Unsent items before billing/paying: offer to send them first. Returns
  /// false when the cashier backed out.
  Future<bool> _offerSendFirst(String doing) async {
    final order = _order;
    if (order == null || order.pendingLineCount == 0) return true;
    final answer = await askSendFirst(context, order.pendingLineCount, doing);
    if (answer == null || !mounted) return false;
    if (answer == SendFirst.send) await _send(quiet: true);
    return mounted;
  }

  Future<void> _bill() async {
    final order = _order;
    if (order == null) return;
    if (order.activeLines.isEmpty) {
      showToast(context, 'Add an item first.');
      return;
    }
    if (!await _offerSendFirst('print the bill')) return;
    final cart = ref.read(cartProvider);
    final bill = await _run(() => _svc.bill(order.uid,
        customerId: cart.customer?.id, currency: cart.currency));
    if (bill == null || !mounted) return;
    final ctx = ref.read(appControllerProvider).context;
    final err = await _printing.printBill(bill, context: ctx);
    if (!mounted) return;
    final fresh = await _run(() => _svc.get(order.uid));
    if (fresh != null) _showOrder(fresh);
    if (!mounted) return;
    if (err == null) {
      showToast(context, 'Bill printed: ${formatAmount(bill.grossTotal)}.', ok: true);
      return;
    }
    // Not printed: show it on screen so the customer can still be told the total.
    await showBillPreview(context,
        text: buildBillText(bill,
            companyName: ctx?.company.name ?? 'OrbixPOS',
            branchName: ctx?.branch.name ?? '',
            width: 32),
        printError: err,
        onPrintAgain: _bill);
  }

  Future<void> _pay() async {
    final order = _order;
    if (order == null) return;
    if (order.activeLines.isEmpty) {
      showToast(context, 'Add an item first.');
      return;
    }
    if (!await _offerSendFirst('take payment')) return;
    var current = _order!;
    // The amount due is the server's own price. A printed bill already carries
    // it; otherwise price the order now (nothing is printed).
    double? total = current.status == OrderStatus.billed ? current.billedTotal : null;
    if (total == null) {
      final cart = ref.read(cartProvider);
      final priceFor = current;
      final bill = await _run(() => _svc.bill(priceFor.uid,
          customerId: cart.customer?.id, currency: cart.currency));
      if (bill == null || !mounted) return;
      total = bill.grossTotal;
      current = await _run(() => _svc.get(priceFor.uid)) ?? current;
      if (!mounted) return;
    }
    final restricted = await _hasRestricted(current);
    if (!mounted) return;
    final receipt = await openRestaurantPaymentSheet(context, ref,
        RestaurantCheckout(order: current, total: total, hasRestricted: restricted));
    if (!mounted) return;
    if (receipt != null) {
      await _backToFloor();
      return;
    }
    // Backed out or refused (e.g. prices changed): show the order as it is now.
    final uid = current.uid;
    final fresh = await _run(() => _svc.get(uid));
    if (fresh == null || !mounted) return;
    if (fresh.status == OrderStatus.paid) {
      await _backToFloor();
    } else {
      _showOrder(fresh);
    }
  }

  /// Whether any item on [order] is age-restricted — from the menu already
  /// loaded, else one product read per unknown item.
  Future<bool> _hasRestricted(RestaurantOrder order) async {
    for (final l in order.activeLines) {
      var p = _seen[l.productId];
      if (p == null && l.productUid != null) {
        try {
          final loaded = await ref.read(catalogServiceProvider).getProduct(l.productUid!);
          _seen[loaded.id] = loaded;
          p = loaded;
        } catch (_) {
          continue; // the server still checks; this only decides the question
        }
      }
      if (p != null && p.restrictedKind.isRestricted) return true;
    }
    return false;
  }

  /// A manager's approval when the cashier may not void alone. Returns
  /// `ok: false` when it was refused or backed out of.
  Future<({bool ok, String? uid})> _voidAuthority(String detail) async {
    final app = ref.read(appControllerProvider);
    if (app.can(Perms.restaurantOrderVoid)) return (ok: true, uid: null);
    final approval = await showManagerApproval(context, ref,
        action: GatedAction.restaurantVoid, detail: detail);
    final uid = approval?.authoriserUid?.trim();
    if (uid == null || uid.isEmpty) return (ok: false, uid: null);
    return (ok: true, uid: uid);
  }

  Future<void> _void(RestaurantOrderLine l) async {
    final order = _order;
    if (order == null) return;
    final answer = await askVoid(context, l);
    if (answer == null || !mounted) return;
    final auth = await _voidAuthority(
        '${formatAmount(answer.quantity, decimals: 0)} x ${l.productName} — ${answer.reason}');
    if (!auth.ok || !mounted) return;
    final res = await _run(() => _svc.voidItem(order.uid, l.uid,
        reason: answer.reason,
        quantity: answer.quantity < l.quantity ? answer.quantity : null,
        authorisedByUid: auth.uid));
    if (res == null || !mounted) return;
    _showOrder(res.order);
    await _printSlips(res.slips, okMessage: 'Void sent');
  }

  Future<void> _cancelOrder() async {
    final order = _order;
    if (order == null) return;
    final anySent = order.lines.any((l) => l.isSent);
    final reason = await askCancelOrder(context, order, anySent: anySent);
    if (reason == null || !mounted) return;
    String? authUid;
    if (anySent) {
      final auth = await _voidAuthority('Cancel ${order.orderNumber} — $reason');
      if (!auth.ok || !mounted) return;
      authUid = auth.uid;
    }
    final res = await _run(() => _svc.cancel(order.uid,
        reason: reason.isEmpty ? null : reason, authorisedByUid: authUid));
    if (res == null || !mounted) return;
    await _printSlips(res.slips, okMessage: anySent ? 'Void sent' : null);
    if (!mounted) return;
    showToast(context, '${order.orderNumber} cancelled.', ok: true);
    await _backToFloor();
  }

  /// Split the bill by item (ADR-0066 D-2): the chosen items move to a new
  /// check at the same table, paid on its own. Splitting evenly is done at
  /// payment, with the ÷2 ÷3 ÷4 buttons.
  Future<void> _split() async {
    final order = _order;
    if (order == null) return;
    if (order.activeLines.length < 2 && (order.activeLines.firstOrNull?.quantity ?? 0) < 2) {
      showToast(context, 'There is only one item — nothing to split.');
      return;
    }
    final items = await askSplit(context, order);
    if (items == null || items.isEmpty || !mounted) return;
    final res = await _run(() => _svc.split(order.uid, items));
    if (res == null || !mounted) return;
    _showOrder(res.original);
    showToast(context,
        'Moved to a new check, ${res.created.orderNumber}. Open it from the floor.',
        ok: true);
  }

  /// Merge another open order into this one (ADR-0066 D-3) — tables pushed
  /// together, or a takeaway that sat down.
  Future<void> _merge() async {
    final order = _order;
    if (order == null) return;
    final live = await _run(() => _svc.listLiveOrders());
    if (live == null || !mounted) return;
    final other = await pickOrderToMerge(
        context, live.where((o) => o.uid != order.uid).toList());
    if (other == null || !mounted) return;
    final merged = await _run(() => _svc.merge(order.uid, other.uid));
    if (merged == null || !mounted) return;
    _showOrder(merged);
    showToast(context, '${other.orderNumber} merged into ${merged.orderNumber}.', ok: true);
  }

  Future<void> _moveTable() async {
    final order = _order;
    if (order == null || order.takeaway) return;
    final target = await pickTable(context, _tables, exceptUid: order.tableUid);
    if (target == null || !mounted) return;
    final moved = await _run(() => _svc.moveTo(order.uid, target.uid));
    if (moved != null) {
      _showOrder(moved);
      if (mounted) showToast(context, 'Moved to table ${target.name}.', ok: true);
    }
  }

  // ================================================================ build

  /// Below this width (a tablet in portrait) the order screen shows the menu
  /// and the order as two tabs instead of side by side (ADR-0066 D-8).
  static const double _tabletBreakpoint = 900;

  @override
  Widget build(BuildContext context) {
    final order = _order;
    if (order == null) return _floor();
    return LayoutBuilder(builder: (context, box) {
      if (box.maxWidth >= _tabletBreakpoint) {
        return Row(
          children: [
            Expanded(child: _menuPane()),
            SizedBox(width: 400, child: _ticket(order)),
          ],
        );
      }
      final count = order.activeLines.length;
      return DefaultTabController(
        length: 2,
        child: Column(
          children: [
            Material(
              color: AppColors.panel,
              child: TabBar(
                labelColor: AppColors.brandD,
                indicatorColor: AppColors.brand,
                tabs: [
                  const Tab(icon: Icon(Icons.restaurant_menu), text: 'Menu'),
                  Tab(icon: const Icon(Icons.receipt_long_outlined), text: 'Order ($count)'),
                ],
              ),
            ),
            Expanded(
              child: TabBarView(
                children: [_menuPane(), _ticket(order)],
              ),
            ),
          ],
        ),
      );
    });
  }

  // ---------------------------------------------------------------- floor

  Widget _floor() {
    return Padding(
      padding: const EdgeInsets.all(16),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          Row(
            children: [
              const Text('Floor',
                  style: TextStyle(fontSize: 20, fontWeight: FontWeight.w800)),
              const Spacer(),
              OrbixButton(
                  label: 'Printers',
                  icon: Icons.print_outlined,
                  kind: BtnKind.ghost,
                  onPressed: _load == _Load.ready
                      ? () => showStationPrinters(context, _stations)
                      : null),
              const SizedBox(width: 8),
              OrbixButton(
                  label: 'Refresh',
                  icon: Icons.refresh,
                  kind: BtnKind.ghost,
                  onPressed: _busy ? null : _loadFloor),
              const SizedBox(width: 8),
              OrbixButton(
                  label: 'New takeaway',
                  icon: Icons.takeout_dining_outlined,
                  busy: _busy,
                  onPressed: _load == _Load.ready && !_busy ? _newTakeaway : null),
            ],
          ),
          const SizedBox(height: 14),
          Expanded(child: _floorBody()),
        ],
      ),
    );
  }

  Widget _floorBody() {
    switch (_load) {
      case _Load.loading:
        return const Center(child: CircularProgressIndicator());
      case _Load.forbidden:
        return const _FloorNotice(
            icon: Icons.lock_outline,
            text: 'Restaurant orders are not enabled for your login. Ask a manager to '
                'give you the "Restaurant orders" permission.');
      case _Load.unsupported:
        return const _FloorNotice(
            icon: Icons.system_update_outlined,
            text: 'This ERP server does not support restaurant orders yet. '
                'Update the ERP server, then try again.');
      case _Load.error:
        return _FloorNotice(
            icon: Icons.cloud_off_outlined,
            text: 'Could not load the floor. Check the connection to the ERP.',
            action: OrbixButton(label: 'Try again', onPressed: _loadFloor));
      case _Load.ready:
        break;
    }
    final takeaways = _live.where((o) => o.takeaway).toList();
    final narrow = MediaQuery.sizeOf(context).width < _tabletBreakpoint;
    final takeawayPanel = Panel(
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        mainAxisSize: MainAxisSize.min,
        children: [
          const Padding(
            padding: EdgeInsets.fromLTRB(14, 12, 14, 6),
            child: SectionLabel('Takeaway orders'),
          ),
          if (takeaways.isEmpty)
            const Padding(
              padding: EdgeInsets.all(14),
              child: Text('None open.', style: TextStyle(color: AppColors.ink3)),
            )
          else
            for (final o in takeaways)
              ListTile(
                dense: true,
                leading: const Icon(Icons.takeout_dining_outlined),
                title: Text(o.orderNumber),
                subtitle: Text('${o.activeLines.length} items · ${o.openedByName}'
                    '${o.status == OrderStatus.billed ? ' · bill printed' : ''}'),
                onTap: _busy ? null : () => _openExisting(o),
              ),
        ],
      ),
    );
    final tablesView = _tables.isEmpty
        ? const _FloorNotice(
            icon: Icons.table_restaurant_outlined,
            text: 'No tables are set up for this branch. A manager adds them in the '
                'ERP (Point of Sale → Restaurant Setup). Takeaway works meanwhile.')
        : GridView.builder(
            gridDelegate: const SliverGridDelegateWithMaxCrossAxisExtent(
              maxCrossAxisExtent: 150,
              mainAxisExtent: 104,
              crossAxisSpacing: 12,
              mainAxisSpacing: 12,
            ),
            itemCount: _tables.length,
            itemBuilder: (context, i) => _TableTile(
              table: _tables[i],
              onTap: _busy ? null : () => _tapTable(_tables[i]),
            ),
          );
    if (narrow) {
      // Tablet in portrait: takeaways above the tables, both full width.
      return Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          if (takeaways.isNotEmpty) ...[takeawayPanel, const SizedBox(height: 12)],
          Expanded(child: tablesView),
        ],
      );
    }
    return Row(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Expanded(child: tablesView),
        const SizedBox(width: 16),
        SizedBox(width: 280, child: takeawayPanel),
      ],
    );
  }

  // ---------------------------------------------------------------- menu

  Widget _menuPane() {
    return Padding(
      padding: const EdgeInsets.all(16),
      child: Column(
        children: [
          Row(
            children: [
              OrbixButton(
                  label: 'Floor',
                  icon: Icons.arrow_back,
                  kind: BtnKind.ghost,
                  onPressed: _busy ? null : _backToFloor),
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
          if (_menuLoading)
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
            onTap: _busy ? null : () => _add(p),
            onLongPress: _busy ? null : () => _addWithChoices(p),
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
                  _tilePrice(p),
                ],
              ),
            ),
          ),
        );
      },
    );
  }

  /// The tile's price line, VAT-inclusive (J COB 2026-10-24: price, not the
  /// item code). Empty while the answer is in flight or when this till may not
  /// read prices; an explicit "No price" when the server has none.
  Widget _tilePrice(Product p) {
    if (_prices.denied) return const SizedBox(height: 15);
    final price = _prices.priceFor(p.uid);
    if (price == null) return const SizedBox(height: 15);
    if (!price.isResolved) {
      return const Text('No price',
          style: TextStyle(
              fontSize: 11.5,
              fontStyle: FontStyle.italic,
              color: AppColors.warn));
    }
    final gross = ref.read(appControllerProvider).grossUnitPrice(
        price.amount!, p.vatStatus,
        vatInclusive: price.vatInclusive);
    return Text(formatAmount(gross),
        maxLines: 1,
        softWrap: false,
        overflow: TextOverflow.ellipsis,
        style: numStyle(size: 12.5, weight: FontWeight.w700));
  }

  /// A rough running total from the menu prices, until the bill gives the real
  /// one. Null when any item's price is not known yet.
  double? _estimate(RestaurantOrder order) {
    final app = ref.read(appControllerProvider);
    var sum = 0.0;
    for (final l in order.activeLines) {
      final p = _seen[l.productId];
      final price = p == null ? null : _prices.priceFor(p.uid);
      if (p == null || price == null || !price.isResolved) return null;
      sum += l.quantity *
          app.grossUnitPrice(price.amount!, p.vatStatus, vatInclusive: price.vatInclusive);
    }
    return sum;
  }

  // ---------------------------------------------------------------- ticket

  Widget _ticket(RestaurantOrder order) {
    final pending = _withAddOns(order.lines.where((l) => l.isPending).toList(), byCourse: true);
    final sent = _withAddOns(order.lines.where((l) => !l.isPending).toList(), byCourse: false);
    final toSend = pending.where((l) => !l.held && !l.isAddOn).length;
    final heldCount = pending.where((l) => l.held && !l.isAddOn).length;
    final billed = order.status == OrderStatus.billed && order.billedTotal != null;
    final estimate = billed ? null : _estimate(order);
    final hasItems = order.activeLines.isNotEmpty;

    return Container(
      decoration: const BoxDecoration(
          color: AppColors.panel,
          border: Border(left: BorderSide(color: AppColors.line))),
      child: Column(
        children: [
          Container(
            padding: const EdgeInsets.fromLTRB(14, 8, 4, 8),
            decoration: const BoxDecoration(
                border: Border(bottom: BorderSide(color: AppColors.line))),
            child: Row(
              children: [
                Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text(order.where,
                          style: const TextStyle(
                              fontSize: 16, fontWeight: FontWeight.w700)),
                      Text(
                          '${order.orderNumber}'
                          '${order.guestCount != null ? ' · ${order.guestCount} guests' : ''}'
                          '${billed ? ' · bill printed' : ''}',
                          style: const TextStyle(color: AppColors.ink2, fontSize: 12)),
                    ],
                  ),
                ),
                PopupMenuButton<String>(
                  tooltip: 'More',
                  enabled: !_busy,
                  onSelected: (v) {
                    switch (v) {
                      case 'move':
                        _moveTable();
                      case 'split':
                        _split();
                      case 'merge':
                        _merge();
                      case 'reprint':
                        _reprintLast();
                      case 'cancel':
                        _cancelOrder();
                    }
                  },
                  itemBuilder: (_) => [
                    if (!order.takeaway)
                      const PopupMenuItem(value: 'move', child: Text('Move to another table')),
                    const PopupMenuItem(value: 'split', child: Text('Split the bill…')),
                    const PopupMenuItem(
                        value: 'merge', child: Text('Merge another order into this…')),
                    const PopupMenuItem(
                        value: 'reprint', child: Text('Reprint last kitchen/bar slips')),
                    const PopupMenuItem(value: 'cancel', child: Text('Cancel order')),
                  ],
                ),
              ],
            ),
          ),
          Expanded(
            child: order.lines.isEmpty
                ? const Center(
                    child: Text('Tap menu items to build the order.\nLong-press for choices, a course or a note.',
                        textAlign: TextAlign.center,
                        style: TextStyle(color: AppColors.ink3)))
                : ListView(
                    padding: const EdgeInsets.symmetric(vertical: 6),
                    children: [
                      if (pending.isNotEmpty) ...[
                        _groupLabel('NEW — NOT SENT YET', AppColors.warn),
                        for (final l in pending) l.isAddOn ? _addOnRow(l) : _pendingRow(l),
                      ],
                      if (sent.isNotEmpty) ...[
                        _groupLabel('SENT', AppColors.ink3),
                        for (final l in sent) l.isAddOn ? _addOnRow(l) : _sentRow(l),
                      ],
                    ],
                  ),
          ),
          Padding(
            padding: const EdgeInsets.fromLTRB(12, 8, 12, 0),
            child: Row(
              children: [
                Expanded(
                  child: OrbixButton(
                    label: toSend == 0 ? 'Send' : 'Send ($toSend)',
                    icon: Icons.soup_kitchen_outlined,
                    kind: BtnKind.ghost,
                    block: true,
                    onPressed: toSend == 0 || _busy ? null : _send,
                  ),
                ),
                if (heldCount > 0) ...[
                  const SizedBox(width: 8),
                  Expanded(
                    child: OrbixButton(
                      label: 'Fire ($heldCount)',
                      icon: Icons.local_fire_department_outlined,
                      kind: BtnKind.ghost,
                      block: true,
                      onPressed: _busy ? null : _fire,
                    ),
                  ),
                ],
                const SizedBox(width: 8),
                Expanded(
                  child: OrbixButton(
                    label: billed ? 'Reprint bill' : 'Bill',
                    icon: Icons.receipt_long_outlined,
                    kind: BtnKind.ghost,
                    block: true,
                    onPressed: !hasItems || _busy ? null : _bill,
                  ),
                ),
              ],
            ),
          ),
          Container(
            margin: const EdgeInsets.only(top: 8),
            padding: const EdgeInsets.all(14),
            color: AppColors.panel2,
            width: double.infinity,
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                Row(
                  mainAxisAlignment: MainAxisAlignment.spaceBetween,
                  children: [
                    Text(billed ? 'Bill (${ref.read(cartProvider).currency})' : 'Estimated',
                        style: const TextStyle(fontWeight: FontWeight.w800, fontSize: 18)),
                    const SizedBox(width: 8),
                    Flexible(
                      child: NumText(
                          billed
                              ? formatAmount(order.billedTotal!)
                              : estimate == null
                                  ? '—'
                                  : formatAmount(estimate),
                          style: numStyle(size: 20, weight: FontWeight.w800)),
                    ),
                  ],
                ),
                const SizedBox(height: 12),
                if (_canTakePayment)
                  PayButton(
                    label: 'PAY',
                    amount: billed
                        ? formatAmount(order.billedTotal!)
                        : estimate == null
                            ? ''
                            : formatAmount(estimate),
                    onPressed: !hasItems || _busy ? null : _pay,
                  )
                else
                  // Waiter mode (ADR-0066 D-8): money is taken over a till.
                  Container(
                    padding: const EdgeInsets.symmetric(vertical: 14),
                    alignment: Alignment.center,
                    decoration: BoxDecoration(
                        color: AppColors.panel,
                        borderRadius: AppRadii.brSm,
                        border: Border.all(color: AppColors.line2)),
                    child: const Text('Payment is taken at the cashier',
                        style: TextStyle(color: AppColors.ink2, fontWeight: FontWeight.w600)),
                  ),
              ],
            ),
          ),
        ],
      ),
    );
  }

  Widget _groupLabel(String text, Color color) => Padding(
        padding: const EdgeInsets.fromLTRB(14, 8, 14, 2),
        child: Text(text,
            style: TextStyle(
                fontSize: 11, fontWeight: FontWeight.w800, color: color, letterSpacing: .4)),
      );

  /// Items in order, each followed by its own add-ons; unsent items by course.
  static List<RestaurantOrderLine> _withAddOns(List<RestaurantOrderLine> lines,
      {required bool byCourse}) {
    final items = lines.where((l) => !l.isAddOn).toList();
    if (byCourse) {
      items.sort((a, b) => a.course.compareTo(b.course));
    } else {
      items.sort((a, b) => (a.fireRound ?? 0).compareTo(b.fireRound ?? 0));
    }
    final out = <RestaurantOrderLine>[];
    final placed = <String>{};
    for (final item in items) {
      out.add(item);
      for (final a in lines.where((x) => x.parentLineUid == item.uid)) {
        out.add(a);
        placed.add(a.uid);
      }
    }
    out.addAll(lines.where((l) => l.isAddOn && !placed.contains(l.uid)));
    return out;
  }

  /// An add-on, under its item: changed, sent and voided with it, never alone.
  Widget _addOnRow(RestaurantOrderLine l) {
    return Padding(
      padding: const EdgeInsets.fromLTRB(46, 0, 8, 4),
      child: Row(
        children: [
          Expanded(
            child: Text('+ ${l.productName}',
                maxLines: 1,
                overflow: TextOverflow.ellipsis,
                style: TextStyle(
                    fontSize: 13,
                    color: l.isVoided ? AppColors.ink3 : AppColors.ink2,
                    decoration: l.isVoided ? TextDecoration.lineThrough : null)),
          ),
          Text(formatAmount(l.quantity, decimals: 0),
              style: const TextStyle(fontSize: 13, color: AppColors.ink2)),
        ],
      ),
    );
  }

  Widget _chip(String text, Color color, {VoidCallback? onTap, String? tooltip}) => Tooltip(
        message: tooltip ?? '',
        child: InkWell(
          borderRadius: AppRadii.brPill,
          onTap: onTap,
          child: Container(
            margin: const EdgeInsets.only(right: 6),
            padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 2),
            decoration: BoxDecoration(
                borderRadius: AppRadii.brPill, border: Border.all(color: color)),
            child: Text(text,
                style: TextStyle(fontSize: 11, fontWeight: FontWeight.w700, color: color)),
          ),
        ),
      );

  Widget _pendingRow(RestaurantOrderLine l) {
    return Padding(
      padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 4),
      child: Row(
        children: [
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(l.productName,
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                    style: const TextStyle(fontWeight: FontWeight.w600)),
                Padding(
                  padding: const EdgeInsets.symmetric(vertical: 2),
                  child: Row(
                    children: [
                      _chip('Course ${l.course}', AppColors.brand,
                          tooltip: 'Move to the next course',
                          onTap: _busy ? null : () => _nextCourse(l)),
                      _chip(l.held ? 'Held' : 'Hold', l.held ? AppColors.warn : AppColors.ink3,
                          tooltip: l.held ? 'Release: send with the next Send' : 'Hold until fired',
                          onTap: _busy ? null : () => _toggleHold(l)),
                    ],
                  ),
                ),
                if (l.note != null)
                  Text(l.note!,
                      style: const TextStyle(fontSize: 12, color: AppColors.ink2)),
                Row(
                  children: [
                    _qbtn(Icons.remove, _busy ? null : () => _changeQty(l, -1)),
                    SizedBox(
                      width: 34,
                      child: Text(formatAmount(l.quantity, decimals: 0),
                          textAlign: TextAlign.center,
                          style: numStyle(weight: FontWeight.w700)),
                    ),
                    _qbtn(Icons.add, _busy ? null : () => _changeQty(l, 1)),
                  ],
                ),
              ],
            ),
          ),
          IconButton(
              tooltip: 'Remove',
              onPressed: _busy ? null : () => _remove(l),
              icon: const Icon(Icons.close, size: 16, color: AppColors.ink3)),
        ],
      ),
    );
  }

  Widget _sentRow(RestaurantOrderLine l) {
    final voided = l.isVoided;
    final style = TextStyle(
        fontWeight: FontWeight.w600,
        color: voided ? AppColors.ink3 : AppColors.ink,
        decoration: voided ? TextDecoration.lineThrough : null);
    return Padding(
      padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 4),
      child: Row(
        children: [
          SizedBox(
            width: 34,
            child: Text(formatAmount(l.quantity, decimals: 0),
                textAlign: TextAlign.center, style: style),
          ),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(l.productName,
                    maxLines: 1, overflow: TextOverflow.ellipsis, style: style),
                Text(
                    voided
                        ? 'Voided: ${l.voidReason ?? ''}'
                        : '${l.isReady ? 'READY · ' : ''}'
                            '${l.prepStationName ?? 'No station'} · round ${l.fireRound ?? 1}'
                            '${l.course > 1 ? ' · course ${l.course}' : ''}'
                            '${l.note != null ? ' · ${l.note}' : ''}',
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                    style: TextStyle(
                        fontSize: 12,
                        fontWeight: l.isReady ? FontWeight.w700 : null,
                        color: voided
                            ? AppColors.danger
                            : l.isReady
                                ? AppColors.ok
                                : AppColors.ink2)),
              ],
            ),
          ),
          if (!voided)
            TextButton(
                onPressed: _busy ? null : () => _void(l),
                child: const Text('Void', style: TextStyle(color: AppColors.danger))),
        ],
      ),
    );
  }

  Widget _qbtn(IconData i, VoidCallback? onTap) => InkWell(
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

/// One table on the floor, coloured by its live state.
class _TableTile extends StatelessWidget {
  const _TableTile({required this.table, required this.onTap});
  final RestaurantTable table;
  final VoidCallback? onTap;

  @override
  Widget build(BuildContext context) {
    final (bg, border, label) = switch (table.state) {
      TableState.free => (AppColors.panel, AppColors.line2, 'Free'),
      TableState.occupied => (AppColors.brandSoft, AppColors.brand, 'Occupied'),
      TableState.billed => (AppColors.warnSoft, AppColors.warn, 'Bill printed'),
    };
    return Material(
      color: bg,
      borderRadius: AppRadii.brLg,
      child: InkWell(
        borderRadius: AppRadii.brLg,
        onTap: onTap,
        child: Container(
          padding: const EdgeInsets.all(12),
          decoration: BoxDecoration(
            borderRadius: AppRadii.brLg,
            border: Border.all(color: border, width: 1.5),
          ),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Row(
                children: [
                  Expanded(
                    child: Text(table.name,
                        maxLines: 1,
                        overflow: TextOverflow.ellipsis,
                        style: const TextStyle(fontSize: 18, fontWeight: FontWeight.w800)),
                  ),
                  if (table.liveOrderCount > 1)
                    Container(
                      padding: const EdgeInsets.symmetric(horizontal: 6, vertical: 2),
                      decoration: BoxDecoration(
                          color: border, borderRadius: AppRadii.brPill),
                      child: Text('${table.liveOrderCount}',
                          style: const TextStyle(
                              color: Colors.white, fontSize: 11, fontWeight: FontWeight.w800)),
                    ),
                ],
              ),
              const Spacer(),
              Text(table.readyItemCount > 0 ? 'Food ready' : label,
                  style: TextStyle(
                      color: table.readyItemCount > 0 ? AppColors.ok : border,
                      fontWeight: FontWeight.w800)),
              Text('seats ${table.seats}${table.area != null ? ' · ${table.area}' : ''}',
                  maxLines: 1,
                  overflow: TextOverflow.ellipsis,
                  style: const TextStyle(fontSize: 11, color: AppColors.ink3)),
            ],
          ),
        ),
      ),
    );
  }
}

class _FloorNotice extends StatelessWidget {
  const _FloorNotice({required this.icon, required this.text, this.action});
  final IconData icon;
  final String text;
  final Widget? action;

  @override
  Widget build(BuildContext context) {
    return Center(
      child: ConstrainedBox(
        constraints: const BoxConstraints(maxWidth: 440),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            Icon(icon, size: 40, color: AppColors.ink3),
            const SizedBox(height: 10),
            Text(text,
                textAlign: TextAlign.center,
                style: const TextStyle(color: AppColors.ink2)),
            if (action != null) ...[const SizedBox(height: 12), action!],
          ],
        ),
      ),
    );
  }
}
