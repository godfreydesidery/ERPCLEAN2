import '../core/api/api_client.dart';
import '../core/json.dart';
import '../models/restaurant.dart';

/// Restaurant orders (ERP ADR-0065). Order → send (slips per station) → bill →
/// pay (an ordinary POS sale). Every call works in the till's active branch.
class RestaurantService {
  RestaurantService(this._api);
  final ApiClient _api;

  static const _base = '/restaurant';
  static const _orders = '$_base/orders';

  // ---------------------------------------------------------------- floor

  Future<List<RestaurantTable>> listTables() async =>
      asList(await _api.get('$_base/tables'), RestaurantTable.fromJson);

  Future<List<PrepStation>> listStations() async =>
      asList(await _api.get('$_base/prep-stations'), PrepStation.fromJson);

  // ---------------------------------------------------------------- orders

  /// The branch's live orders (open and billed), optionally for one table.
  Future<List<RestaurantOrder>> listLiveOrders({String? tableUid}) async =>
      asList(await _api.get(_orders, query: {'tableUid': tableUid}),
          RestaurantOrder.fromJson);

  Future<RestaurantOrder> get(String uid) async =>
      RestaurantOrder.fromJson(asMap(await _api.get('$_orders/uid/$uid')));

  /// Opens an order at [tableUid], or a takeaway when it is null.
  /// [waiterUid]: the waiter signed in on this shared till (ADR-0066 D-8); the
  /// server re-verifies it and records them as the order's server.
  Future<RestaurantOrder> open({String? tableUid, int? guestCount, String? waiterUid}) async =>
      RestaurantOrder.fromJson(asMap(await _api.post(_orders, body: {
        'orderType': tableUid == null ? 'TAKEAWAY' : 'DINE_IN',
        'tableUid': ?tableUid,
        'guestCount': ?guestCount,
        'waiterUid': ?waiterUid,
      })));

  Future<RestaurantOrder> moveTo(String uid, String tableUid) async =>
      RestaurantOrder.fromJson(asMap(
          await _api.patch('$_orders/uid/$uid', body: {'tableUid': tableUid})));

  Future<RestaurantOrder> addItem(String uid,
          {required String productId,
          required String unitId,
          double? quantity,
          String? note,
          List<String> optionUids = const [],
          int? course,
          bool held = false}) async =>
      RestaurantOrder.fromJson(asMap(await _api.post('$_orders/uid/$uid/lines', body: {
        'productId': productId,
        'unitId': unitId,
        'quantity': ?quantity,
        'note': ?note,
        if (optionUids.isNotEmpty) 'modifierOptionUids': optionUids,
        'course': ?course,
        if (held) 'held': true,
      })));

  /// Changes an item that has not been sent yet.
  Future<RestaurantOrder> changeItem(String uid, String lineUid,
          {required double quantity, String? note, int? course, bool? held}) async =>
      RestaurantOrder.fromJson(asMap(await _api.patch(
          '$_orders/uid/$uid/lines/uid/$lineUid',
          body: {'quantity': quantity, 'note': note, 'course': ?course, 'held': ?held})));

  /// Releases held items — of [course], or all — and sends them ("mains now").
  Future<OrderWithSlips> fire(String uid, {int? course, String? waiterUid}) async =>
      OrderWithSlips.fromJson(asMap(await _api.post('$_orders/uid/$uid/fire',
          body: {'course': ?course, 'waiterUid': ?waiterUid})));

  // ---------------------------------------------------------------- choices (ADR-0066 D-4)

  /// The active choices of the company's menu.
  Future<List<ModifierGroup>> listModifierGroups() async =>
      ModifierGroup.activeFrom(await _api.get('$_base/modifier-groups'));

  /// Product uid → the uids of the groups it offers, in order.
  Future<Map<String, List<String>>> listModifierLinks() async {
    final data = await _api.get('$_base/modifier-links');
    final out = <String, List<String>>{};
    if (data is List) {
      for (final m in data.whereType<Map>()) {
        final uid = m['productUid']?.toString();
        final groups = (m['groupUids'] as List? ?? const []).map((e) => e.toString()).toList();
        if (uid != null && groups.isNotEmpty) out[uid] = groups;
      }
    }
    return out;
  }

  /// Removes an item that has not been sent yet.
  Future<RestaurantOrder> removeItem(String uid, String lineUid) async =>
      RestaurantOrder.fromJson(
          asMap(await _api.delete('$_orders/uid/$uid/lines/uid/$lineUid')));

  /// Fires every unsent item; returns one slip per station to print.
  Future<OrderWithSlips> send(String uid, {String? waiterUid}) async =>
      OrderWithSlips.fromJson(asMap(await _api.post('$_orders/uid/$uid/send',
          body: waiterUid == null ? null : {'waiterUid': waiterUid})));

  /// A station's slips sent or voided after [since] — for a device that prints
  /// the station itself. [since] null starts the cursor now (no history).
  Future<({DateTime until, List<PrepSlip> slips})> stationSlips(String stationUid,
      {DateTime? since}) async {
    final data = asMap(await _api.get('$_base/kitchen/slips', query: {
      'stationUid': stationUid,
      'since': since?.toUtc().toIso8601String(),
    }));
    return (
      until: asDate(data['until']) ?? DateTime.now(),
      slips: asList(data['slips'], PrepSlip.fromJson),
    );
  }

  /// Slips rebuilt for a reprint ([round] null = the latest).
  Future<List<PrepSlip>> slips(String uid, {int? round, String? stationUid}) async =>
      asList(
          await _api.get('$_orders/uid/$uid/slips',
              query: {'round': round, 'stationUid': stationUid}),
          PrepSlip.fromJson);

  /// Voids a SENT item. [authorisedByUid] is a manager step-up, when the cashier
  /// may not void alone. [quantity] null voids the whole line.
  Future<OrderWithSlips> voidItem(String uid, String lineUid,
          {required String reason, double? quantity, String? authorisedByUid}) async =>
      OrderWithSlips.fromJson(asMap(await _api.post(
          '$_orders/uid/$uid/lines/uid/$lineUid/void',
          body: {
            'reason': reason,
            'quantity': ?quantity,
            'authorisedByUid': ?authorisedByUid,
          })));

  Future<OrderWithSlips> cancel(String uid,
          {String? reason, String? authorisedByUid}) async =>
      OrderWithSlips.fromJson(asMap(await _api.post('$_orders/uid/$uid/cancel', body: {
        'reason': ?reason,
        'authorisedByUid': ?authorisedByUid,
      })));

  /// Moves the chosen items (line uid → quantity) onto a new check at the same
  /// table (ADR-0066 D-2). Returns (what is left, the new check).
  Future<({RestaurantOrder original, RestaurantOrder created})> split(
      String uid, Map<String, double> items) async {
    final data = asMap(await _api.post('$_orders/uid/$uid/split', body: {
      'items': [
        for (final e in items.entries) {'lineUid': e.key, 'quantity': e.value},
      ],
    }));
    return (
      original: RestaurantOrder.fromJson(asMap(data['original'])),
      created: RestaurantOrder.fromJson(asMap(data['created'])),
    );
  }

  /// Moves every item of [fromOrderUid] onto order [uid] and closes it (D-3).
  Future<RestaurantOrder> merge(String uid, String fromOrderUid) async =>
      RestaurantOrder.fromJson(asMap(await _api.post('$_orders/uid/$uid/merge',
          body: {'fromOrderUid': fromOrderUid})));

  /// Prices the order for [customerId] and records the bill.
  Future<RestaurantBill> bill(String uid,
          {String? customerId, required String currency}) async =>
      RestaurantBill.fromJson(asMap(await _api.post('$_orders/uid/$uid/bill', body: {
        'customerId': ?customerId,
        'currency': currency,
      })));

  /// Takes payment: the order becomes a POS sale. Reuse [idempotencyKey] on a
  /// retry — the same sale comes back, never a second one.
  Future<RestaurantPayment> pay(String uid, Map<String, dynamic> body,
          {required String idempotencyKey}) async =>
      RestaurantPayment.fromJson(asMap(await _api.post('$_orders/uid/$uid/pay',
          body: body, idempotencyKey: idempotencyKey, xRequestId: idempotencyKey)));
}
