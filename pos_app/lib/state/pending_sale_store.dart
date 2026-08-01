import 'dart:convert';

import 'package:flutter/foundation.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:shared_preferences/shared_preferences.dart';

/// A sale that was sent to the ERP but whose outcome this device never saw.
///
/// The Idempotency-Key alone is not enough to be safe: if it lives only in the
/// payment sheet's memory then force-closing the till between the server COMMIT
/// and the response landing loses it, and re-ringing the basket mints a fresh
/// key — a second finalised invoice, with duplicate revenue, VAT, COGS and stock
/// issue. So the key is written to device storage BEFORE the POST, together with
/// the exact request body, and is cleared only on a confirmed terminal outcome.
///
/// Replaying the stored body under the stored key is always safe: the server
/// reserves the key in the same transaction as the sale, so the replay returns
/// the ORIGINAL invoice when it committed and completes the sale exactly once
/// when it never arrived (ADR-0042 D-1).
class PendingSale {
  const PendingSale({
    required this.txnId,
    required this.sessionUid,
    required this.body,
    required this.startedAt,
    required this.amount,
    required this.currency,
    this.tenderedAmount,
  });

  /// The Idempotency-Key / X-Request-Id of the attempt.
  final String txnId;

  /// The session the sale was rung against — a shift change invalidates a replay.
  final String sessionUid;

  /// The exact `PosSaleRequest` that was posted, replayed verbatim.
  final Map<String, dynamic> body;

  final DateTime startedAt;

  /// Basket total and currency at the time — shown so the cashier recognises
  /// which sale is being reconciled.
  final double amount;
  final String currency;

  final double? tenderedAmount;

  Map<String, dynamic> toJson() => {
        'txnId': txnId,
        'sessionUid': sessionUid,
        'body': body,
        'startedAt': startedAt.toIso8601String(),
        'amount': amount,
        'currency': currency,
        'tenderedAmount': tenderedAmount,
      };

  factory PendingSale.fromJson(Map<String, dynamic> j) => PendingSale(
        txnId: (j['txnId'] ?? '').toString(),
        sessionUid: (j['sessionUid'] ?? '').toString(),
        body: (j['body'] as Map?)?.cast<String, dynamic>() ?? <String, dynamic>{},
        startedAt:
            DateTime.tryParse((j['startedAt'] ?? '').toString()) ?? DateTime.now(),
        amount: (j['amount'] as num?)?.toDouble() ?? 0,
        currency: (j['currency'] ?? '').toString(),
        tenderedAmount: (j['tenderedAmount'] as num?)?.toDouble(),
      );
}

/// Durable single-slot storage for the in-flight sale (SharedPreferences, the
/// same local store the receipt journal and app config use).
///
/// One slot is enough: the till rings one sale at a time and an unresolved
/// attempt blocks the next one, so there can never be two.
///
/// Every method is non-throwing. This is a safety net around the sale path, and
/// a net that can knock the sale over is worse than none — a local storage
/// hiccup must never leave the cashier stuck mid-payment.
class PendingSaleStore {
  static const _key = 'pos.pending_sale';

  /// Persists the attempt. Must complete BEFORE the sale is POSTed — that
  /// ordering is the whole point.
  Future<void> save(PendingSale sale) async {
    try {
      final p = await SharedPreferences.getInstance();
      await p.setString(_key, jsonEncode(sale.toJson()));
    } catch (e) {
      debugPrint('POS could not persist the in-flight sale key: $e');
    }
  }

  Future<PendingSale?> read() async {
    try {
      final p = await SharedPreferences.getInstance();
      final raw = p.getString(_key);
      if (raw == null || raw.isEmpty) return null;
      return PendingSale.fromJson((jsonDecode(raw) as Map).cast<String, dynamic>());
    } catch (e) {
      // Unreadable slot — drop it rather than block the till forever.
      debugPrint('POS could not read the in-flight sale key: $e');
      await clear();
      return null;
    }
  }

  /// Clears the slot. Only after a CONFIRMED terminal outcome: the sale came
  /// back (finalised) or the server definitively rejected it.
  Future<void> clear() async {
    try {
      final p = await SharedPreferences.getInstance();
      await p.remove(_key);
    } catch (e) {
      debugPrint('POS could not clear the in-flight sale key: $e');
    }
  }
}

final pendingSaleStoreProvider =
    Provider<PendingSaleStore>((ref) => PendingSaleStore());
