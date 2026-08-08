import 'package:collection/collection.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:uuid/uuid.dart';

import '../models/catalog.dart';
import '../models/parties.dart';
import '../models/sale.dart';

const _uuid = Uuid();

/// One basket line. Money on a line is a **preview** (PRIN-2); the server
/// re-derives the authoritative price/VAT. A `voided` line stays visible
/// (struck through) but is excluded from the posted request and totals.
class CartLine {
  CartLine({
    required this.localId,
    required this.product,
    required this.unit,
    this.unitFactor = 1,
    this.quantity = 1,
    this.unitPricePreview,
    this.lineDiscountAmount = 0,
    this.voided = false,
    this.discountAuthorisedByUid,
    this.discountAuthorisedByName,
  });

  final String localId;
  final Product product;
  Unit unit;

  /// Base units per one [unit] — 1 for the product's base unit, the pack's
  /// `factorToBase` for a pack line. Client-side only: the request posts
  /// [quantity] in [unit] and the server does its own conversion.
  double unitFactor;
  double quantity;
  double? unitPricePreview;
  double lineDiscountAmount;
  bool voided;

  /// The uid returned by a successful manager step-up for THIS line's discount
  /// (K7), posted as `discountAuthorisedByUid`.
  ///
  /// Per line, not per sale: a basket can hold one heavily discounted item and
  /// nine ordinary ones, and a sale-level flag would let the approval for the
  /// first wave the rest through. The server re-verifies the named user really
  /// holds `SALES.DISCOUNT.OVERRIDE` in the invoice's company, so this is a
  /// pointer to an approval, never the approval itself.
  String? discountAuthorisedByUid;

  /// Who approved, for the on-screen "Approved by …" stamp. Display only.
  String? discountAuthorisedByName;

  /// True when this line carries a discount that no manager has approved. The
  /// SERVER decides whether that matters — the ceiling is company policy and
  /// the till is never told what it is — so this only ever drives what the UI
  /// *offers*, never what it allows.
  bool get hasUnapprovedDiscount =>
      !voided && lineDiscountAmount > 0 && (discountAuthorisedByUid == null);

  /// Quantity expressed in the product's base unit — what this line will consume
  /// from stock (2 cartons of 24 = 48). Used for the short-stock warning, which
  /// compares against on-hand quantities that are always in base units.
  double get baseQuantity => quantity * unitFactor;

  /// Preview line total (VAT-inclusive convention from the prototype).
  double get previewGross {
    final g = quantity * (unitPricePreview ?? 0) - lineDiscountAmount;
    return g < 0 ? 0 : g;
  }

  CartLine clone() => CartLine(
        localId: localId,
        product: product,
        unit: unit,
        unitFactor: unitFactor,
        quantity: quantity,
        unitPricePreview: unitPricePreview,
        lineDiscountAmount: lineDiscountAmount,
        voided: voided,
        discountAuthorisedByUid: discountAuthorisedByUid,
        discountAuthorisedByName: discountAuthorisedByName,
      );
}

class CartState {
  const CartState({
    this.lines = const [],
    this.customer,
    this.agent,
    this.currency = 'TZS',
    this.selectedId,
    this.notes,
  });

  final List<CartLine> lines;
  final Customer? customer;
  final Agent? agent;
  final String currency;
  final String? selectedId;

  /// Free-text carried onto the sale (e.g. pharmacy Rx / restaurant table).
  final String? notes;

  List<CartLine> get activeLines => lines.where((l) => !l.voided).toList();
  bool get isEmpty => activeLines.isEmpty;
  int get lineCount => activeLines.length;

  double get itemCount =>
      activeLines.fold(0, (s, l) => s + l.quantity);

  double get previewSubtotal =>
      activeLines.fold(0, (s, l) => s + l.previewGross);

  double get previewDiscount =>
      activeLines.fold(0, (s, l) => s + l.lineDiscountAmount);

  /// True if any active line carries an age restriction (drives the prompt).
  bool get hasRestricted =>
      activeLines.any((l) => l.product.restrictedKind.isRestricted);

  /// Active lines carrying a discount that no manager has approved (K7).
  ///
  /// Used only to decide whether offering an approval could plausibly help
  /// after the server refuses a sale — the threshold itself is the server's,
  /// and the till never guesses at it.
  List<CartLine> get linesNeedingDiscountApproval =>
      activeLines.where((l) => l.hasUnapprovedDiscount).toList();

  /// True when any active line has no preview price yet — so the on-screen total
  /// is indeterminate (the server will price it). Payment must not gate tender
  /// sufficiency on the preview when this holds.
  bool get hasUnpricedLine =>
      activeLines.any((l) => l.unitPricePreview == null);

  /// Build the `PosSaleRequest` body. [tenders] omitted => server settles a
  /// single exact CASH payment (the legacy path).
  /// [capturedAt] is when this basket was keyed (K11). The server refuses a
  /// basket older than its configured limit rather than re-pricing a days-old
  /// sale into the current period; omitting it is inert, so an older backend is
  /// unaffected either way.
  Map<String, dynamic> buildRequest(
    String sessionUid, {
    List<PosTender>? tenders,
    double? tenderedAmount,
    bool ageVerified = false,
    String? notes,
    DateTime? capturedAt,
  }) {
    final reqLines = activeLines
        .map((l) => {
              'productId': l.product.id,
              'unitId': l.unit.id,
              'quantity': l.quantity,
              if (l.lineDiscountAmount > 0)
                'lineDiscountAmount': l.lineDiscountAmount,
              if (l.discountAuthorisedByUid != null)
                'discountAuthorisedByUid': l.discountAuthorisedByUid,
            })
        .toList();
    return {
      'sessionUid': sessionUid,
      'customerId': customer?.id,
      'agentId': agent?.id,
      'currency': currency,
      'lines': reqLines,
      if (tenders != null && tenders.isNotEmpty)
        'tenders': tenders.map((t) => t.toJson()).toList(),
      'tenderedAmount': ?tenderedAmount,
      'ageVerified': ageVerified,
      'notes': ?(notes ?? this.notes),
      if (capturedAt != null) 'capturedAt': capturedAt.toUtc().toIso8601String(),
    };
  }

  CartState copyWith({
    List<CartLine>? lines,
    Customer? customer,
    Agent? agent,
    String? currency,
    String? selectedId,
    bool clearSelected = false,
    String? notes,
  }) =>
      CartState(
        lines: lines ?? this.lines,
        customer: customer ?? this.customer,
        agent: agent ?? this.agent,
        currency: currency ?? this.currency,
        selectedId: clearSelected ? null : (selectedId ?? this.selectedId),
        notes: notes ?? this.notes,
      );
}

class CartController extends Notifier<CartState> {
  @override
  CartState build() => const CartState();

  /// Initialise the basket for a new shift with the resolved defaults.
  void start({Customer? customer, Agent? agent, required String currency}) {
    state = CartState(customer: customer, agent: agent, currency: currency);
  }

  List<CartLine> get _copy => state.lines.map((l) => l.clone()).toList();

  /// Add a product. For a non-embedded item the same product+unit merges into
  /// one line (qty += [quantity]); embedded weight/price always adds a fresh
  /// line. [fixedQuantity] (embedded weight) and [overridePrice] (embedded
  /// price) come from a scale-label decode.
  void addProduct(
    Product product,
    Unit unit, {
    double unitFactor = 1,
    double quantity = 1,
    double? unitPricePreview,
    double? fixedQuantity,
    double? overridePrice,
  }) {
    final lines = _copy;
    if (fixedQuantity == null && overridePrice == null) {
      final existing = lines.firstWhere(
        (l) => l.product.id == product.id && l.unit.id == unit.id && !l.voided,
        orElse: () => CartLine(localId: '', product: product, unit: unit),
      );
      if (existing.localId.isNotEmpty) {
        existing.quantity += quantity;
        state = state.copyWith(lines: lines, selectedId: existing.localId);
        return;
      }
    }
    final line = CartLine(
      localId: _uuid.v4(),
      product: product,
      unit: unit,
      unitFactor: unitFactor,
      quantity: fixedQuantity ?? quantity,
      unitPricePreview: overridePrice ?? unitPricePreview,
    );
    lines.add(line);
    state = state.copyWith(lines: lines, selectedId: line.localId);
  }

  /// Switch a line to another of the product's sellable units (base ⇄ pack).
  ///
  /// Quantity is deliberately NOT rescaled: "2" with the unit changed to Carton
  /// means two cartons, which is what a cashier correcting a mis-picked unit
  /// expects. The stale price is cleared so the row shows "—" until the caller
  /// patches in the pack price — showing the per-piece price against a carton
  /// would misstate the total.
  ///
  /// If another active line already holds this product in the target unit the
  /// two merge, so the basket never shows the same product+unit twice.
  void setLineUnit(String localId, Unit unit, double factor) {
    final lines = _copy;
    final line = lines.where((l) => l.localId == localId).firstOrNull;
    if (line == null || line.unit.id == unit.id) return;

    final twin = lines
        .where((l) =>
            l.localId != localId &&
            !l.voided &&
            l.product.id == line.product.id &&
            l.unit.id == unit.id)
        .firstOrNull;
    if (twin != null) {
      twin.quantity += line.quantity;
      final mergedDiscount = twin.lineDiscountAmount + line.lineDiscountAmount;
      // The merged discount is one the manager never saw as a single figure, so
      // any approval on either half lapses. If it still needs one, the server
      // will say so and the cashier can ask once, for the real number.
      if (mergedDiscount != twin.lineDiscountAmount) {
        twin.discountAuthorisedByUid = null;
        twin.discountAuthorisedByName = null;
      }
      twin.lineDiscountAmount = mergedDiscount;
      lines.removeWhere((l) => l.localId == localId);
      state = state.copyWith(lines: lines, selectedId: twin.localId);
      return;
    }

    line.unit = unit;
    line.unitFactor = factor;
    line.unitPricePreview = null;
    state = state.copyWith(lines: lines, selectedId: localId);
  }

  void _mutate(String localId, void Function(CartLine) fn) {
    final lines = _copy;
    final line = lines.where((l) => l.localId == localId).firstOrNull;
    if (line == null) return;
    fn(line);
    state = state.copyWith(lines: lines);
  }

  void setQuantity(String localId, double qty) =>
      _mutate(localId, (l) => l.quantity = qty <= 0 ? 1 : qty);

  void addQuantity(String localId, double delta) => _mutate(localId, (l) {
        final q = l.quantity + delta;
        l.quantity = q < 1 ? 1 : q;
      });

  /// Sets a line discount, **dropping any manager approval attached to it**.
  ///
  /// An approval is for the discount the manager actually saw. Letting a 5%
  /// approval survive an edit to 60% would turn the control into a rubber
  /// stamp that only has to be obtained once.
  void setDiscount(String localId, double amount) => _mutate(localId, (l) {
        l.lineDiscountAmount = amount < 0 ? 0 : amount;
        l.discountAuthorisedByUid = null;
        l.discountAuthorisedByName = null;
      });

  /// Attaches a manager's approval to a line's discount (K7). [uid] is the
  /// `authoriserUid` from a successful step-up; [name] is for display only.
  void setDiscountAuthorisation(String localId, String uid, String name) =>
      _mutate(localId, (l) {
        l.discountAuthorisedByUid = uid;
        l.discountAuthorisedByName = name;
      });

  /// Patch the preview unit price once the (async) price-list lookup returns.
  void setLinePrice(String localId, double? price) =>
      _mutate(localId, (l) => l.unitPricePreview = price);

  void toggleVoid(String localId) {
    _mutate(localId, (l) => l.voided = !l.voided);
    // If the selected line was just voided, drop the selection so the numpad
    // cannot silently edit a struck-through line that is excluded from the sale.
    final line = state.lines.where((l) => l.localId == localId).firstOrNull;
    if (state.selectedId == localId && (line?.voided ?? false)) {
      state = state.copyWith(clearSelected: true);
    }
  }

  void removeLine(String localId) {
    final lines = _copy..removeWhere((l) => l.localId == localId);
    state = state.copyWith(
        lines: lines,
        clearSelected: state.selectedId == localId);
  }

  void select(String localId) => state = state.copyWith(selectedId: localId);

  void setCustomer(Customer c) => state = state.copyWith(customer: c);
  void setAgent(Agent a) => state = state.copyWith(agent: a);
  void setCurrency(String c) => state = state.copyWith(currency: c);
  void setNotes(String? n) =>
      state = CartState(
        lines: state.lines,
        customer: state.customer,
        agent: state.agent,
        currency: state.currency,
        selectedId: state.selectedId,
        notes: n,
      );

  void clearLines() => state = CartState(
        customer: state.customer,
        agent: state.agent,
        currency: state.currency,
      );
}

final cartProvider =
    NotifierProvider<CartController, CartState>(CartController.new);
