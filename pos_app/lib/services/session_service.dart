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

  Future<XRead> xRead(String uid) async =>
      XRead.fromJson(asMap(await _api.get('/pos/sessions/uid/$uid/x-read')));

  Future<void> payout(
    String uid,
    PosPayoutType type,
    double amount,
    String? reason,
  ) async {
    await _api.post('/pos/sessions/uid/$uid/payouts', body: {
      'payoutType': type.wire,
      'amount': amount,
      'reason': reason,
    });
  }

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
