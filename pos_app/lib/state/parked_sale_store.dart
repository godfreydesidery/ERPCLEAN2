import 'dart:convert';

import 'package:flutter/foundation.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:shared_preferences/shared_preferences.dart';

import '../models/catalog.dart';
import '../models/parties.dart';
import 'cart_controller.dart';

/// One line of a parked basket — enough to ring it again without a server
/// round trip. Prices are NOT kept: a recalled basket is re-priced by the
/// server (prices may have changed while it waited).
class ParkedLine {
  const ParkedLine({
    required this.product,
    required this.unitUid,
    required this.unitFactor,
    required this.quantity,
    required this.lineDiscountAmount,
  });

  final Product product;
  final String unitUid;
  final double unitFactor;
  final double quantity;
  final double lineDiscountAmount;

  Map<String, dynamic> toJson() => {
        'product': product.toJson(),
        'unitUid': unitUid,
        'unitFactor': unitFactor,
        'quantity': quantity,
        'lineDiscountAmount': lineDiscountAmount,
      };

  factory ParkedLine.fromJson(Map<String, dynamic> j) => ParkedLine(
        product: Product.fromJson((j['product'] as Map).cast<String, dynamic>()),
        unitUid: (j['unitUid'] ?? '').toString(),
        unitFactor: (j['unitFactor'] as num?)?.toDouble() ?? 1,
        quantity: (j['quantity'] as num?)?.toDouble() ?? 1,
        lineDiscountAmount: (j['lineDiscountAmount'] as num?)?.toDouble() ?? 0,
      );
}

/// A basket put on hold (POS-06): the customer went back for an item or to
/// fetch money, and the queue keeps moving.
///
/// Kept on THIS till only, like [PendingSaleStore] — a parked basket is not a
/// sale and the server never sees it until it is recalled and paid. Manager
/// discount approvals are deliberately NOT kept: an approval is for one
/// moment at the counter, and a recalled discount is asked for again.
class ParkedSale {
  const ParkedSale({
    required this.id,
    required this.sessionUid,
    required this.parkedAt,
    required this.lines,
    required this.previewTotal,
    required this.currency,
    this.customer,
    this.notes,
  });

  final String id;
  final String sessionUid;
  final DateTime parkedAt;
  final List<ParkedLine> lines;
  final Customer? customer;
  final String? notes;

  /// The on-screen total when parked — a label for the Recall list only.
  final double previewTotal;
  final String currency;

  /// "Neema's crate of soda" is not knowable; the first item name is.
  String get label {
    if (lines.isEmpty) return 'Empty basket';
    final first = lines.first.product.name;
    return lines.length == 1 ? first : '$first + ${lines.length - 1} more';
  }

  Map<String, dynamic> toJson() => {
        'id': id,
        'sessionUid': sessionUid,
        'parkedAt': parkedAt.toIso8601String(),
        'lines': lines.map((l) => l.toJson()).toList(),
        'customer': customer?.toJson(),
        'notes': notes,
        'previewTotal': previewTotal,
        'currency': currency,
      };

  factory ParkedSale.fromJson(Map<String, dynamic> j) => ParkedSale(
        id: (j['id'] ?? '').toString(),
        sessionUid: (j['sessionUid'] ?? '').toString(),
        parkedAt:
            DateTime.tryParse((j['parkedAt'] ?? '').toString()) ?? DateTime.now(),
        lines: ((j['lines'] as List?) ?? const [])
            .whereType<Map>()
            .map((m) => ParkedLine.fromJson(m.cast<String, dynamic>()))
            .toList(),
        customer: j['customer'] is Map
            ? Customer.fromJson((j['customer'] as Map).cast<String, dynamic>())
            : null,
        notes: j['notes']?.toString(),
        previewTotal: (j['previewTotal'] as num?)?.toDouble() ?? 0,
        currency: (j['currency'] ?? '').toString(),
      );

  /// Snapshot of the active (non-voided) lines of [cart].
  factory ParkedSale.fromCart(CartState cart,
          {required String id,
          required String sessionUid,
          required DateTime now}) =>
      ParkedSale(
        id: id,
        sessionUid: sessionUid,
        parkedAt: now,
        lines: [
          for (final l in cart.activeLines)
            ParkedLine(
              product: l.product,
              unitUid: l.unit.uid,
              unitFactor: l.unitFactor,
              quantity: l.quantity,
              lineDiscountAmount: l.lineDiscountAmount,
            ),
        ],
        customer: cart.customer,
        notes: cart.notes,
        previewTotal: cart.previewSubtotal,
        currency: cart.currency,
      );
}

/// Parked baskets in SharedPreferences, newest last.
class ParkedSaleStore {
  static const _key = 'pos.parked_sales';

  /// A till that parks more than this is not holding baskets, it is losing
  /// them. The oldest falls off — with the cashier told by the caller.
  static const int maxParked = 20;

  /// Parked baskets older than this are dropped: a basket from a past day is
  /// not a sale anyone is coming back for.
  static const Duration maxAge = Duration(hours: 24);

  Future<List<ParkedSale>> _readAll() async {
    try {
      final p = await SharedPreferences.getInstance();
      final raw = p.getString(_key);
      if (raw == null || raw.isEmpty) return [];
      return (jsonDecode(raw) as List)
          .whereType<Map>()
          .map((m) => ParkedSale.fromJson(m.cast<String, dynamic>()))
          .toList();
    } catch (e) {
      debugPrint('POS could not read parked baskets: $e');
      return [];
    }
  }

  Future<void> _writeAll(List<ParkedSale> all) async {
    try {
      final p = await SharedPreferences.getInstance();
      await p.setString(_key, jsonEncode(all.map((s) => s.toJson()).toList()));
    } catch (e) {
      debugPrint('POS could not save parked baskets: $e');
    }
  }

  /// Parked baskets of [sessionUid], oldest first. Stale ones are pruned.
  Future<List<ParkedSale>> list(String sessionUid, {DateTime? now}) async {
    final t = now ?? DateTime.now();
    final all = await _readAll();
    final fresh = all.where((s) => t.difference(s.parkedAt) < maxAge).toList();
    if (fresh.length != all.length) await _writeAll(fresh);
    return fresh.where((s) => s.sessionUid == sessionUid).toList();
  }

  /// Adds [sale]; returns false when it could not be kept (storage failure).
  Future<bool> park(ParkedSale sale) async {
    final all = await _readAll();
    all.add(sale);
    while (all.length > maxParked) {
      all.removeAt(0);
    }
    await _writeAll(all);
    final check = await _readAll();
    return check.any((s) => s.id == sale.id);
  }

  Future<void> remove(String id) async {
    final all = await _readAll();
    all.removeWhere((s) => s.id == id);
    await _writeAll(all);
  }
}

final parkedSaleStoreProvider =
    Provider<ParkedSaleStore>((ref) => ParkedSaleStore());
