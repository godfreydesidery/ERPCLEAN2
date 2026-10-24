import '../core/json.dart';
import 'sale.dart';

/// Restaurant orders on the server (ERP ADR-0065): tables, prep stations, the
/// open order ("check"), the slips the till prints at the kitchen and bar, and
/// the bill. The server is the only copy of an open order — the till keeps
/// nothing about it locally.

/// A table's live state on the floor, derived by the server from its orders.
enum TableState {
  free,
  occupied,
  billed;

  static TableState parse(String? s) => switch (s) {
        'OCCUPIED' => TableState.occupied,
        'BILLED' => TableState.billed,
        _ => TableState.free,
      };
}

enum OrderStatus {
  open,
  billed,
  paid,
  cancelled;

  static OrderStatus parse(String? s) => switch (s) {
        'BILLED' => OrderStatus.billed,
        'PAID' => OrderStatus.paid,
        'CANCELLED' => OrderStatus.cancelled,
        _ => OrderStatus.open,
      };

  bool get isLive => this == OrderStatus.open || this == OrderStatus.billed;
}

enum OrderLineStatus {
  pending,
  sent,
  voided;

  static OrderLineStatus parse(String? s) => switch (s) {
        'SENT' => OrderLineStatus.sent,
        'VOIDED' => OrderLineStatus.voided,
        _ => OrderLineStatus.pending,
      };
}

class RestaurantTable {
  RestaurantTable({
    required this.uid,
    required this.name,
    required this.seats,
    this.area,
    required this.active,
    required this.state,
    required this.liveOrderCount,
    this.readyItemCount = 0,
  });

  final String uid;
  final String name;
  final int seats;
  final String? area;
  final bool active;
  final TableState state;
  final int liveOrderCount;

  /// Items the kitchen marked ready (ADR-0066 D-6): the floor says "Food ready".
  final int readyItemCount;

  factory RestaurantTable.fromJson(Map<String, dynamic> j) => RestaurantTable(
        uid: asStrOr(j['uid']),
        name: asStrOr(j['name']),
        seats: asIntOr(j['seats'], 4),
        area: asStr(j['area']),
        active: asStr(j['status']) == 'ACTIVE',
        state: TableState.parse(asStr(j['state'])),
        liveOrderCount: asIntOr(j['liveOrderCount']),
        readyItemCount: asIntOr(j['readyItemCount']),
      );
}

class PrepStation {
  PrepStation({
    required this.uid,
    required this.name,
    this.printerHost,
    required this.printerPort,
    required this.isDefault,
    required this.active,
  });

  final String uid;
  final String name;
  final String? printerHost;
  final int printerPort;
  final bool isDefault;
  final bool active;

  String get address =>
      (printerHost == null || printerHost!.isEmpty) ? '' : '$printerHost:$printerPort';

  factory PrepStation.fromJson(Map<String, dynamic> j) => PrepStation(
        uid: asStrOr(j['uid']),
        name: asStrOr(j['name']),
        printerHost: asStr(j['printerHost']),
        printerPort: asIntOr(j['printerPort'], 9100),
        isDefault: asBool(j['defaultStation']),
        active: asStr(j['status']) == 'ACTIVE',
      );
}

class RestaurantOrderLine {
  RestaurantOrderLine({
    required this.uid,
    required this.productId,
    required this.productUid,
    required this.productName,
    required this.unitId,
    required this.quantity,
    this.note,
    required this.status,
    this.fireRound,
    this.prepStationName,
    this.voidReason,
    this.parentLineUid,
    this.course = 1,
    this.held = false,
    this.readyAt,
  });

  final String uid;
  final String productId;
  final String? productUid;
  final String productName;
  final String unitId;
  final double quantity;
  final String? note;
  final OrderLineStatus status;
  final int? fireRound;
  final String? prepStationName;
  final String? voidReason;

  /// Set on a priced add-on: the item it was chosen on (ADR-0066 D-4). An add-on
  /// follows its item — it is never changed, removed or voided on its own.
  final String? parentLineUid;

  /// 1 = starters/drinks, 2 = mains, 3 = dessert (ADR-0066 D-5).
  final int course;

  /// Held back from Send until fired.
  final bool held;

  /// When the kitchen display marked it ready (ADR-0066 D-6).
  final DateTime? readyAt;

  bool get isAddOn => parentLineUid != null;
  bool get isReady => readyAt != null;

  bool get isPending => status == OrderLineStatus.pending;
  bool get isSent => status == OrderLineStatus.sent;
  bool get isVoided => status == OrderLineStatus.voided;

  factory RestaurantOrderLine.fromJson(Map<String, dynamic> j) =>
      RestaurantOrderLine(
        uid: asStrOr(j['uid']),
        productId: asStrOr(j['productId']),
        productUid: asStr(j['productUid']),
        productName: asStrOr(j['productName'], 'Item'),
        unitId: asStrOr(j['unitId']),
        quantity: asNumOr(j['quantity']),
        note: asStr(j['note']),
        status: OrderLineStatus.parse(asStr(j['status'])),
        fireRound: asInt(j['fireRound']),
        prepStationName: asStr(j['prepStationName']),
        voidReason: asStr(j['voidReason']),
        parentLineUid: asStr(j['parentLineUid']),
        course: asIntOr(j['course'], 1),
        held: asBool(j['held']),
        readyAt: asDate(j['readyAt']),
      );
}

class RestaurantOrder {
  RestaurantOrder({
    required this.uid,
    required this.orderNumber,
    required this.takeaway,
    required this.status,
    this.tableUid,
    this.tableName,
    this.guestCount,
    required this.openedByName,
    this.openedAt,
    this.billedTotal,
    required this.billPrintCount,
    this.salesInvoiceUid,
    required this.pendingLineCount,
    required this.lines,
  });

  final String uid;
  final String orderNumber;
  final bool takeaway;
  final OrderStatus status;
  final String? tableUid;
  final String? tableName;
  final int? guestCount;
  final String openedByName;
  final DateTime? openedAt;
  final double? billedTotal;
  final int billPrintCount;
  final String? salesInvoiceUid;
  final int pendingLineCount;
  final List<RestaurantOrderLine> lines;

  /// Where the order is being served, for headers and slips.
  String get where => takeaway ? 'Takeaway' : 'Table ${tableName ?? ''}'.trim();

  List<RestaurantOrderLine> get activeLines =>
      lines.where((l) => !l.isVoided).toList();

  factory RestaurantOrder.fromJson(Map<String, dynamic> j) => RestaurantOrder(
        uid: asStrOr(j['uid']),
        orderNumber: asStrOr(j['orderNumber']),
        takeaway: asStr(j['orderType']) == 'TAKEAWAY',
        status: OrderStatus.parse(asStr(j['status'])),
        tableUid: asStr(j['tableUid']),
        tableName: asStr(j['tableName']),
        guestCount: asInt(j['guestCount']),
        openedByName: asStrOr(j['openedByName']),
        openedAt: asDate(j['openedAt']),
        billedTotal: asNum(j['billedTotalAmount']),
        billPrintCount: asIntOr(j['billPrintCount']),
        salesInvoiceUid: asStr(j['salesInvoiceUid']),
        pendingLineCount: asIntOr(j['pendingLineCount']),
        lines: asList(j['lines'], RestaurantOrderLine.fromJson),
      );
}

/// One slip for one prep station. The till prints it; the server never does.
class PrepSlip {
  PrepSlip({
    required this.isVoid,
    required this.stationUid,
    required this.stationName,
    this.printerHost,
    required this.printerPort,
    required this.orderNumber,
    required this.takeaway,
    this.tableName,
    this.guestCount,
    required this.round,
    required this.reprint,
    required this.byName,
    this.at,
    required this.lines,
  });

  final bool isVoid;
  final String stationUid;
  final String stationName;
  final String? printerHost;
  final int printerPort;
  final String orderNumber;
  final bool takeaway;
  final String? tableName;
  final int? guestCount;
  final int round;
  final bool reprint;
  final String byName;
  final DateTime? at;
  final List<PrepSlipLine> lines;

  factory PrepSlip.fromJson(Map<String, dynamic> j) => PrepSlip(
        isVoid: asStr(j['kind']) == 'VOID',
        stationUid: asStrOr(j['stationUid']),
        stationName: asStrOr(j['stationName']),
        printerHost: asStr(j['printerHost']),
        printerPort: asIntOr(j['printerPort'], 9100),
        orderNumber: asStrOr(j['orderNumber']),
        takeaway: asStr(j['orderType']) == 'TAKEAWAY',
        tableName: asStr(j['tableName']),
        guestCount: asInt(j['guestCount']),
        round: asIntOr(j['round']),
        reprint: asBool(j['reprint']),
        byName: asStrOr(j['byName']),
        at: asDate(j['at']),
        lines: asList(j['lines'], PrepSlipLine.fromJson),
      );
}

class PrepSlipLine {
  PrepSlipLine({
    required this.productName,
    this.unitName,
    required this.quantity,
    this.note,
    this.voidReason,
    this.addOn = false,
    this.course = 1,
  });

  final String productName;
  final String? unitName;
  final double quantity;
  final String? note;
  final String? voidReason;

  /// A priced add-on, printed indented under the item before it.
  final bool addOn;
  final int course;

  factory PrepSlipLine.fromJson(Map<String, dynamic> j) => PrepSlipLine(
        productName: asStrOr(j['productName'], 'Item'),
        unitName: asStr(j['unitName']),
        quantity: asNumOr(j['quantity']),
        note: asStr(j['note']),
        voidReason: asStr(j['voidReason']),
        addOn: asBool(j['addOn']),
        course: asIntOr(j['course'], 1),
      );
}

/// The bill: NOT a receipt. Priced by the server exactly as payment will charge.
class RestaurantBill {
  RestaurantBill({
    required this.orderUid,
    required this.orderNumber,
    required this.takeaway,
    this.tableName,
    this.guestCount,
    required this.serverName,
    this.billedAt,
    required this.printCount,
    required this.currency,
    required this.lines,
    required this.netTotal,
    required this.vatTotal,
    required this.grossTotal,
    required this.unsentLineCount,
  });

  final String orderUid;
  final String orderNumber;
  final bool takeaway;
  final String? tableName;
  final int? guestCount;
  final String serverName;
  final DateTime? billedAt;
  final int printCount;
  final String currency;
  final List<BillLine> lines;
  final double netTotal;
  final double vatTotal;
  final double grossTotal;
  final int unsentLineCount;

  factory RestaurantBill.fromJson(Map<String, dynamic> j) => RestaurantBill(
        orderUid: asStrOr(j['orderUid']),
        orderNumber: asStrOr(j['orderNumber']),
        takeaway: asStr(j['orderType']) == 'TAKEAWAY',
        tableName: asStr(j['tableName']),
        guestCount: asInt(j['guestCount']),
        serverName: asStrOr(j['serverName']),
        billedAt: asDate(j['billedAt']),
        printCount: asIntOr(j['printCount'], 1),
        currency: asStrOr(j['currency']),
        lines: asList(j['lines'], BillLine.fromJson),
        netTotal: asNumOr(j['netTotal']),
        vatTotal: asNumOr(j['vatTotal']),
        grossTotal: asNumOr(j['grossTotal']),
        unsentLineCount: asIntOr(j['unsentLineCount']),
      );
}

class BillLine {
  BillLine({
    required this.productName,
    required this.quantity,
    required this.unitPrice,
    required this.amount,
  });

  final String productName;
  final double quantity;
  final double unitPrice;
  final double amount;

  factory BillLine.fromJson(Map<String, dynamic> j) => BillLine(
        productName: asStrOr(j['productName'], 'Item'),
        quantity: asNumOr(j['quantity']),
        unitPrice: asNumOr(j['unitPrice']),
        amount: asNumOr(j['amount']),
      );
}

/// Result of Send, void or cancel: the order afterwards and the slips to print.
class OrderWithSlips {
  OrderWithSlips(this.order, this.slips);
  final RestaurantOrder order;
  final List<PrepSlip> slips;

  factory OrderWithSlips.fromJson(Map<String, dynamic> j) => OrderWithSlips(
        RestaurantOrder.fromJson(asMap(j['order'])),
        asList(j['slips'], PrepSlip.fromJson),
      );
}

/// Result of payment: the PAID order and the sale it became.
class RestaurantPayment {
  RestaurantPayment(this.order, this.invoice);
  final RestaurantOrder order;
  final SalesInvoice invoice;

  factory RestaurantPayment.fromJson(Map<String, dynamic> j) => RestaurantPayment(
        RestaurantOrder.fromJson(asMap(j['order'])),
        SalesInvoice.fromJson(asMap(j['invoice'])),
      );
}

// ---------------------------------------------------------------- modifiers (ADR-0066 D-4)

class ModifierOption {
  ModifierOption({required this.uid, required this.label, this.productName, required this.priced});

  final String uid;
  final String label;
  final String? productName;

  /// A priced add-on (charged, its own line) rather than a free instruction.
  final bool priced;

  factory ModifierOption.fromJson(Map<String, dynamic> j) => ModifierOption(
        uid: asStrOr(j['uid']),
        label: asStrOr(j['label']),
        productName: asStr(j['productName']),
        priced: asStr(j['productUid']) != null,
      );
}

/// A choice offered on menu items: "Milk" (choose one), "Extras" (any).
class ModifierGroup {
  ModifierGroup({
    required this.uid,
    required this.name,
    required this.minSelect,
    this.maxSelect,
    required this.options,
  });

  final String uid;
  final String name;
  final int minSelect;

  /// Null = no upper limit.
  final int? maxSelect;
  final List<ModifierOption> options;

  bool get single => maxSelect == 1;

  /// The rule in words, for the dialog: "Choose 1", "Optional".
  String get rule {
    if (minSelect == 0 && maxSelect == null) return 'Optional';
    if (minSelect == 0) return 'Optional — up to $maxSelect';
    if (minSelect == maxSelect) return 'Choose $minSelect';
    return 'Choose $minSelect to ${maxSelect ?? 'any'}';
  }

  factory ModifierGroup.fromJson(Map<String, dynamic> j) => ModifierGroup(
        uid: asStrOr(j['uid']),
        name: asStrOr(j['name']),
        minSelect: asIntOr(j['minSelect']),
        maxSelect: asInt(j['maxSelect']),
        options: asList(j['options'], ModifierOption.fromJson)
            .where((o) => o.uid.isNotEmpty)
            .toList(),
      );

  /// Groups as the server sends them, keeping active ones with active options.
  static List<ModifierGroup> activeFrom(dynamic data) => (data is List ? data : const [])
      .whereType<Map>()
      .map((m) => m.cast<String, dynamic>())
      .where((m) => m['status'] == 'ACTIVE')
      .map((m) {
        final g = ModifierGroup.fromJson({
          ...m,
          'options': (m['options'] as List? ?? const [])
              .whereType<Map>()
              .where((o) => o['status'] == 'ACTIVE')
              .toList(),
        });
        return g;
      })
      .where((g) => g.options.isNotEmpty)
      .toList();
}
