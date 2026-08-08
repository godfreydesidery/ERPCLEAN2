import '../core/api/api_client.dart';
import '../core/json.dart';
import '../models/enums.dart';
import '../models/pos.dart';

/// POS session lifecycle (§08): open, view, x-read, payout, close, reconcile.
class SessionService {
  SessionService(this._api);
  final ApiClient _api;

  Future<PosSession> open(String tillUid, double openingFloat) async {
    final data = await _api.post('/pos/sessions',
        body: {'tillUid': tillUid, 'openingFloatAmount': openingFloat});
    return PosSession.fromJson(asMap(data));
  }

  Future<PosSession> getByUid(String uid) async =>
      PosSession.fromJson(asMap(await _api.get('/pos/sessions/uid/$uid')));

  /// Lists sessions for the company, newest first (the server orders them).
  ///
  /// [status] filters server-side — pass `'OPEN'` to look for a live shift. The
  /// filter is not optional in practice: an unfiltered page 0 is every session
  /// the company ever had, so once there are more than [size] of them the one
  /// OPEN row falls off the page and the shift looks lost.
  Future<List<PosSession>> list(
    String companyId, {
    String? status,
    int page = 0,
    int size = 50,
  }) async {
    final data = await _api.get('/pos/sessions', query: {
      'companyId': companyId,
      'status': status,
      'page': page,
      'size': size,
    });
    return asList(data, PosSession.fromJson);
  }

  /// Mid-shift X-read. Valid on an OPEN **and** a CLOSED session (P3 relaxed
  /// it), so an ended shift can still be examined; on a RECONCILED session the
  /// server points at the Z-read instead of producing a second, competing
  /// end-of-shift summary.
  Future<XRead> xRead(String uid) async =>
      XRead.fromJson(asMap(await _api.get('/pos/sessions/uid/$uid/x-read')));

  /// Re-reads the end-of-shift Z-read of an already-reconciled session (P3).
  ///
  /// Read-only and repeatable: it posts nothing and returns byte-for-byte what
  /// the reconciling call returned, so a reprint cannot drift from the document
  /// the cashier signed off. 409 while the session is not yet RECONCILED.
  Future<ZRead> zRead(String uid) async =>
      ZRead.fromJson(asMap(await _api.get('/pos/sessions/uid/$uid/z-read')));

  /// Records a drawer payout. Returns the created row (K8) — it carries the GL
  /// journal uid, which is the only place that reference is ever visible.
  ///
  /// [reason] is now mandatory server-side (min 3 chars, trimmed).
  Future<PosPayout> payout(
    String uid,
    PosPayoutType type,
    double amount,
    String? reason,
  ) async {
    final data = await _api.post('/pos/sessions/uid/$uid/payouts', body: {
      'payoutType': type.wire,
      'amount': amount,
      'reason': reason,
    });
    return PosPayout.fromJson(asMap(data));
  }

  /// Records a categorised business expense paid out of the drawer (K8, V94).
  ///
  /// [entryId] is the Idempotency-Key and MUST be stable for one expense entry:
  /// create it ONCE when the operator opens the expense form (with
  /// [ApiClient.newTxnId], exactly as `_PaymentSheet._txnId` does for a sale)
  /// and reuse the SAME value on every retry. A key regenerated per attempt
  /// defeats the mechanism completely — that is how two byte-identical POSTs
  /// produced two payouts and two journals, 2,468 posted for one 1,234 expense.
  ///
  /// Under a repeated key the server replays the ORIGINAL payout (200 with the
  /// same `uid` and `journalEntryUid`), so a retry after a lost response is
  /// safe and needs no confirmation dialog. A 409 means an earlier attempt
  /// under this key is still in flight: wait and re-read the payout list rather
  /// than entering the expense again.
  Future<PosPayout> recordExpense(
    String uid, {
    required double amount,
    required String category,
    required String reason,
    required String entryId,
  }) async {
    final data = await _api.post(
      '/pos/sessions/uid/$uid/expenses',
      body: {'amount': amount, 'category': category, 'reason': reason},
      idempotencyKey: entryId,
      xRequestId: entryId,
    );
    return PosPayout.fromJson(asMap(data));
  }

  /// The individual payouts of a session, oldest first (K8).
  Future<List<PosPayout>> listPayouts(String uid) async => asList(
      await _api.get('/pos/sessions/uid/$uid/payouts'), PosPayout.fromJson);

  Future<PosSession> close(
    String uid,
    double countedCash, {
    String? notes,
  }) async {
    final data = await _api.post('/pos/sessions/uid/$uid/close',
        body: {'countedCashAmount': countedCash, 'notes': notes});
    return PosSession.fromJson(asMap(data));
  }

  Future<ZRead> reconcile(String uid, {String? notes}) async {
    final data = await _api
        .post('/pos/sessions/uid/$uid/reconcile', body: {'notes': notes});
    return ZRead.fromJson(asMap(data));
  }
}
