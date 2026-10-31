import 'package:flutter_test/flutter_test.dart';
import 'package:pos_app/core/config/app_config.dart';
import 'package:pos_app/features/restaurant/restaurant_printing.dart';
import 'package:pos_app/features/restaurant/slip_text.dart';
import 'package:pos_app/features/restaurant/station_print_agent.dart';
import 'package:pos_app/models/auth.dart';
import 'package:pos_app/models/restaurant.dart';
import 'package:pos_app/state/app_controller.dart';
import 'package:pos_app/services/bt_printer_android.dart';
import 'package:pos_app/services/network_printer.dart';
import 'package:pos_app/services/receipt_printer.dart';
import 'package:shared_preferences/shared_preferences.dart';

/// Restaurant orders on the till (ERP ADR-0065): the server's JSON, the kitchen
/// and bar slips, the bill, and where each slip is sent.

PrepSlip _slip({
  bool isVoid = false,
  String station = 'Bar',
  String? host = '192.168.1.51',
  String uid = 'S2',
}) =>
    PrepSlip(
      isVoid: isVoid,
      stationUid: uid,
      stationName: station,
      printerHost: host,
      printerPort: 9100,
      orderNumber: 'ORD-0007',
      takeaway: false,
      tableName: 'T4',
      guestCount: 3,
      round: 2,
      reprint: false,
      byName: 'Asha Waiter',
      at: DateTime(2026, 10, 24, 12, 40),
      lines: [
        PrepSlipLine(productName: 'Pumpkin Spice Latte Iced Extra Large', quantity: 2, note: 'no sugar'),
        PrepSlipLine(productName: 'Mango smoothie', quantity: 1, voidReason: isVoid ? 'Wrong item' : null),
      ],
    );

RestaurantBill _bill({int printCount = 1}) => RestaurantBill(
      orderUid: 'O1',
      orderNumber: 'ORD-0007',
      takeaway: false,
      tableName: 'T4',
      guestCount: 3,
      serverName: 'Asha Waiter',
      billedAt: DateTime(2026, 10, 24, 13, 5),
      printCount: printCount,
      currency: 'TZS',
      lines: [
        BillLine(productName: 'Grilled Chicken Tacos', quantity: 1, unitPrice: 12000, amount: 12000),
        BillLine(productName: 'Pumpkin Spice Latte', quantity: 2, unitPrice: 6000, amount: 12000),
      ],
      netTotal: 20339,
      vatTotal: 3661,
      grossTotal: 24000,
      unsentLineCount: 0,
    );

class _FakeNetwork implements NetworkPrinter {
  final sent = <String>[];
  bool fail = false;
  @override
  bool get supported => true;
  @override
  Future<void> send(String host, int port, List<int> bytes) async {
    if (fail) throw ReceiptPrinterException('Could not reach the printer at $host:$port.');
    sent.add('$host:$port');
  }
}

class _FakeSpooler implements ReceiptPrinter {
  final printed = <String>[];
  @override
  bool get supported => true;
  @override
  List<String> listPrinters() => const ['USB Kitchen'];
  @override
  Future<void> refresh() async {}
  @override
  Future<void> printRaw(String printerName, List<int> bytes) async => printed.add(printerName);
}

class _FailingOnceSpooler extends _FakeSpooler {
  bool _failed = false;
  @override
  Future<void> printRaw(String printerName, List<int> bytes) async {
    if (!_failed) {
      _failed = true;
      throw ReceiptPrinterException('Printer out of paper.');
    }
    await super.printRaw(printerName, bytes);
  }
}

void main() {
  group('kitchen PC printing for every till', () {
    setUp(() {
      SharedPreferences.setMockInitialValues({});
      PrintedSlips.instance.clear();
    });

    PrepSlip fired(String line) => PrepSlip(
          isVoid: false, stationUid: 'S1', stationName: 'Kitchen', printerPort: 9100,
          orderNumber: 'ORD-0001', takeaway: false, tableName: 'T1', round: 1, reprint: false,
          byName: 'Asha', lines: [PrepSlipLine(productName: 'Tacos', quantity: 1)],
          lineUids: [line]);

    StationPrintAgent agentWith(_FakeSpooler spool, List<List<PrepSlip>> answers,
        List<DateTime?> asked) {
      var call = 0;
      return StationPrintAgent(
        fetch: (station, since) async {
          asked.add(since);
          final slips = call < answers.length ? answers[call] : <PrepSlip>[];
          call++;
          return (until: DateTime.utc(2026, 10, 24, 12, 0, call), slips: slips);
        },
        printing: RestaurantPrinting(
            network: _FakeNetwork(), spooler: spool,
            loadConfig: () async => AppConfig(
                baseHost: 'http://x', stationPrinters: {'S1': 'USB Kitchen'}, agentStations: {'S1'})),
        loadConfig: () async => AppConfig(
            baseHost: 'http://x', stationPrinters: {'S1': 'USB Kitchen'}, agentStations: {'S1'}),
      );
    }

    test('prints each slip once though the overlapping asks return it twice', () async {
      final spool = _FakeSpooler();
      final asked = <DateTime?>[];
      final agent = agentWith(spool, [[], [fired('L1')], [fired('L1'), fired('L2')]], asked);
      await agent.tick();
      await agent.tick();
      await agent.tick();
      expect(spool.printed, ['USB Kitchen', 'USB Kitchen']);
      expect(asked.first, isNull, reason: 'the first ask starts the cursor: no history');
      expect(asked[1], DateTime.utc(2026, 10, 24, 12, 0, 1).subtract(StationPrintAgent.overlap));
      expect(agent.log.value, hasLength(2));
      agent.dispose();
    });

    test('a slip this device already printed directly is not printed again', () async {
      final spool = _FakeSpooler();
      PrintedSlips.instance.remember(fired('L7'));
      final agent = agentWith(spool, [[fired('L7')]], []);
      await agent.tick();
      expect(spool.printed, isEmpty);
      agent.dispose();
    });

    test('a slip that fails to print is retried on the next round', () async {
      final spool = _FailingOnceSpooler();
      final agent = StationPrintAgent(
        fetch: (station, since) async =>
            (until: DateTime.now(), slips: since == null ? [fired('L9')] : <PrepSlip>[]),
        printing: RestaurantPrinting(
            network: _FakeNetwork(), spooler: spool,
            loadConfig: () async => AppConfig(
                baseHost: 'http://x', stationPrinters: {'S1': 'USB Kitchen'}, agentStations: {'S1'})),
        loadConfig: () async => AppConfig(
            baseHost: 'http://x', stationPrinters: {'S1': 'USB Kitchen'}, agentStations: {'S1'}),
      );
      await agent.tick();
      expect(spool.printed, isEmpty);
      expect(agent.log.value.first.failed, isTrue);
      await agent.tick();
      expect(spool.printed, ['USB Kitchen']);
      agent.dispose();
    });
  });

  group('waiter mode', () {
    Me me(List<String> perms, {bool root = false}) =>
        Me.fromJson({'uid': 'U', 'username': 'u', 'isRoot': root, 'permissions': perms});

    test('order-taker without a till is a waiter; a cashier and root are not', () {
      expect(AppData.isWaiterLogin(me(['RESTAURANT.ORDER.MANAGE', 'PRODUCT.VIEW'])), isTrue);
      expect(AppData.isWaiterLogin(me(['RESTAURANT.ORDER.MANAGE', 'POS.SESSION.OPEN'])), isFalse);
      expect(AppData.isWaiterLogin(me(['POS.SESSION.OPEN'])), isFalse);
      expect(AppData.isWaiterLogin(me(const [], root: true)), isFalse);
    });
  });

  group('server JSON', () {
    test('an order parses ids as strings, amounts as numbers and line states', () {
      final o = RestaurantOrder.fromJson({
        'uid': 'O1',
        'orderNumber': 'ORD-0001',
        'orderType': 'DINE_IN',
        'status': 'BILLED',
        'tableUid': 'T1',
        'tableName': 'T1',
        'guestCount': 2,
        'openedByName': 'Asha',
        'billedTotalAmount': 24000,
        'billPrintCount': 1,
        'pendingLineCount': 1,
        'lines': [
          {'uid': 'L1', 'productId': '147', 'productName': 'Tacos', 'unitId': '3',
           'quantity': 1, 'status': 'SENT', 'fireRound': 1, 'prepStationName': 'Kitchen'},
          {'uid': 'L2', 'productId': '159', 'productName': 'Latte', 'unitId': '3',
           'quantity': 2, 'status': 'PENDING', 'note': 'no sugar'},
          {'uid': 'L3', 'productId': '171', 'productName': 'Donut', 'unitId': '3',
           'quantity': 1, 'status': 'VOIDED', 'voidReason': 'Wrong item'},
        ],
      });
      expect(o.status, OrderStatus.billed);
      expect(o.where, 'Table T1');
      expect(o.billedTotal, 24000);
      expect(o.lines.map((l) => l.status),
          [OrderLineStatus.sent, OrderLineStatus.pending, OrderLineStatus.voided]);
      expect(o.activeLines, hasLength(2));
      expect(o.lines.first.productId, '147');
    });

    test('choices: only active groups with active options; rules read as words', () {
      final groups = ModifierGroup.activeFrom([
        {'uid': 'G1', 'name': 'Milk', 'minSelect': 1, 'maxSelect': 1, 'status': 'ACTIVE', 'options': [
          {'uid': 'O1', 'label': 'Oat', 'status': 'ACTIVE'},
          {'uid': 'O2', 'label': 'Soy', 'status': 'INACTIVE'},
        ]},
        {'uid': 'G2', 'name': 'Old', 'minSelect': 0, 'status': 'INACTIVE', 'options': [
          {'uid': 'O3', 'label': 'X', 'status': 'ACTIVE'},
        ]},
        {'uid': 'G3', 'name': 'Extras', 'minSelect': 0, 'maxSelect': null, 'status': 'ACTIVE', 'options': [
          {'uid': 'O4', 'label': 'Syrup', 'productUid': 'P9', 'status': 'ACTIVE'},
        ]},
      ]);
      expect(groups.map((g) => g.name), ['Milk', 'Extras']);
      expect(groups.first.options.map((o) => o.label), ['Oat']);
      expect(groups.first.rule, 'Choose 1');
      expect(groups.first.single, isTrue);
      expect(groups.last.rule, 'Optional');
      expect(groups.last.options.single.priced, isTrue);
    });

    test('an add-on line knows its item; held and ready parse', () {
      final l = RestaurantOrderLine.fromJson({
        'uid': 'L2', 'productId': '9', 'productName': 'Syrup', 'unitId': '1', 'quantity': 2,
        'status': 'SENT', 'parentLineUid': 'L1', 'course': 2, 'held': false,
        'readyAt': '2026-10-24T12:00:00Z',
      });
      expect(l.isAddOn, isTrue);
      expect(l.course, 2);
      expect(l.isReady, isTrue);
    });

    test('a takeaway says so, and a table state parses', () {
      expect(RestaurantOrder.fromJson({'orderType': 'TAKEAWAY'}).where, 'Takeaway');
      expect(RestaurantTable.fromJson({'state': 'BILLED', 'status': 'ACTIVE'}).state,
          TableState.billed);
    });
  });

  group('slip text', () {
    test('a kitchen/bar slip names the station, table and items — never a price', () {
      final text = buildSlipText(_slip(), width: 32);
      expect(text, contains('BAR'));
      expect(text, contains('TABLE T4'));
      expect(text, contains('ORD-0007'));
      expect(text, contains('2 x Pumpkin Spice Latte'));
      expect(text, contains('>> no sugar'));
      expect(text, isNot(contains('6,000')));
      for (final line in text.split('\n')) {
        expect(line.length, lessThanOrEqualTo(32), reason: line);
      }
    });

    test('add-ons print indented under their item; courses get headings', () {
      final slip = PrepSlip(
        isVoid: false, stationUid: 'S1', stationName: 'Kitchen', printerPort: 9100,
        orderNumber: 'ORD-0009', takeaway: true, round: 1, reprint: false, byName: 'Asha',
        lines: [
          PrepSlipLine(productName: 'Soup', quantity: 1, course: 1),
          PrepSlipLine(productName: 'Burger', quantity: 2, course: 2),
          PrepSlipLine(productName: 'Extra cheese', quantity: 2, addOn: true, course: 2),
        ],
      );
      final text = buildSlipText(slip, width: 32).split('\n');
      final burger = text.indexWhere((l) => l.contains('2 x Burger'));
      expect(text[burger + 1], '    + 2 Extra cheese');
      expect(text.indexWhere((l) => l.contains('COURSE 2')), lessThan(burger));
      expect(text.where((l) => l.contains('COURSE')), hasLength(2));
    });

    test('a VOID slip leads with the cancellation and says why', () {
      final text = buildSlipText(_slip(isVoid: true), width: 48);
      expect(text.split('\n')[1], contains('VOID - DO NOT MAKE'));
      expect(text, contains('Reason: Wrong item'));
    });
  });

  group('bill text', () {
    test('is plainly not a receipt and carries the server totals', () {
      final text = buildBillText(_bill(), companyName: 'CAFE TULIA', branchName: 'Main', width: 32);
      expect(text, contains('BILL - NOT A RECEIPT'));
      expect(text, isNot(contains('REPRINT')));
      expect(text, contains('24,000'));
      for (final line in text.split('\n')) {
        expect(line.length, lessThanOrEqualTo(32), reason: line);
      }
    });

    test('a second print says REPRINT', () {
      final text = buildBillText(_bill(printCount: 3), companyName: 'C', branchName: '', width: 48);
      expect(text, contains('REPRINT 2'));
    });
  });

  group('where slips go', () {
    setUp(() => SharedPreferences.setMockInitialValues({}));

    test('network address by default; a local override wins; no address is handed off', () async {
      final net = _FakeNetwork();
      final spool = _FakeSpooler();
      final printing = RestaurantPrinting(
        network: net,
        spooler: spool,
        loadConfig: () async => AppConfig(
            baseHost: 'http://x', stationPrinters: {'S1': 'USB Kitchen'}),
      );
      final failures = await printing.printSlips([
        _slip(station: 'Bar', uid: 'S2', host: '192.168.1.51'),
        _slip(station: 'Kitchen', uid: 'S1', host: '192.168.1.50'),
        _slip(station: 'Grill', uid: 'S3', host: null),
      ]);
      expect(net.sent, ['192.168.1.51:9100']);
      expect(spool.printed, ['USB Kitchen']);
      expect(failures, isEmpty, reason: 'Grill prints from its own device or the Kitchen Display');
    });

    test('a printer that is off is reported per slip, not thrown', () async {
      final net = _FakeNetwork()..fail = true;
      final printing = RestaurantPrinting(
          network: net, spooler: _FakeSpooler(),
          loadConfig: () async => AppConfig(baseHost: 'http://x'));
      final failures = await printing.printSlips([_slip()]);
      expect(failures.single.message, contains('Could not reach the printer'));
    });

    test('a station with no printer anywhere is handed to its own device, not failed', () async {
      final printing = RestaurantPrinting(
          network: _FakeNetwork(), spooler: _FakeSpooler(),
          loadConfig: () async => AppConfig(baseHost: 'http://x'));
      expect(await printing.printSlips([_slip(station: 'Grill', uid: 'S3', host: null)]), isEmpty);
    });

    test('per-device settings survive a restart', () async {
      final cfg = AppConfig(baseHost: 'http://x', stationWidths: {'S1': 48, 'S2': 99},
          agentStations: {'S1'}, waiterPrompt: WaiterPrompt.everyTable, teamService: true);
      await cfg.save();
      final loaded = await AppConfig.load();
      expect(loaded.stationWidths, {'S1': 48});
      expect(loaded.widthFor('S1'), 48);
      expect(loaded.widthFor('S9'), AppConfig.defaultWidthCols);
      expect(loaded.agentStations, {'S1'});
      expect(loaded.waiterPrompt, WaiterPrompt.everyTable);
      expect(loaded.teamService, isTrue);
    });

    test('a till set to "ask who is serving" before 1.7.2 keeps asking before a new order',
        () async {
      SharedPreferences.setMockInitialValues({'cfg.askWaiter': true});
      expect((await AppConfig.load()).waiterPrompt, WaiterPrompt.newOrder);
      SharedPreferences.setMockInitialValues({});
      final fresh = await AppConfig.load();
      expect(fresh.waiterPrompt, WaiterPrompt.off);
      expect(fresh.teamService, isFalse, reason: 'ownership is the default');
    });

    test('station overrides survive a restart', () async {
      final cfg = AppConfig(baseHost: 'http://x', stationPrinters: {'S1': 'USB Kitchen', 'S2': ' '});
      await cfg.save();
      final loaded = await AppConfig.load();
      expect(loaded.stationPrinters, {'S1': 'USB Kitchen'});
    });
  });

  group('waiter tiles (J COB 2026-10-30)', () {
    setUp(() => SharedPreferences.setMockInitialValues({}));

    test('the till keeps the waiter list, so the tiles are there with nobody signed in', () async {
      final cfg = await AppConfig.load();
      expect(cfg.waiters, isEmpty);
      cfg.waiters = [
        const RestaurantWaiter(uid: 'U1', name: 'Baraka Juma', username: 'baraka'),
        const RestaurantWaiter(uid: 'U2', name: 'Neema Ali', username: 'neema'),
      ];
      cfg.restaurantOnly = true;
      await cfg.save();

      final loaded = await AppConfig.load();
      expect(loaded.waiters.map((w) => w.username), ['baraka', 'neema']);
      expect(loaded.restaurantOnly, isTrue);
    });

    test('a corrupt saved list means no tiles, never a crash', () async {
      SharedPreferences.setMockInitialValues({'cfg.waiters': 'not json'});
      expect((await AppConfig.load()).waiters, isEmpty);
    });

    test('search finds a waiter by name or username, in any case', () {
      const w = RestaurantWaiter(uid: 'U1', name: 'Baraka Juma', username: 'bjuma');
      expect(w.matches(''), isTrue);
      expect(w.matches('bara'), isTrue);
      expect(w.matches('BJU'), isTrue);
      expect(w.matches('neema'), isFalse);
    });

    test('server JSON', () {
      final w = RestaurantWaiter.fromJson({'uid': 'U1', 'name': 'Baraka', 'username': 'baraka'});
      expect(w.toJson(), {'uid': 'U1', 'name': 'Baraka', 'username': 'baraka'});
    });
  });

  group('the waiter holding an order (ADR-0067)', () {
    Map<String, dynamic> order(Map<String, dynamic> extra) => {
          'uid': 'O1', 'orderNumber': 'ORD-0001', 'orderType': 'DINE_IN', 'status': 'OPEN',
          'tableName': 'T1', 'openedByName': 'Baraka', 'billPrintCount': 0,
          'pendingLineCount': 0, 'lines': <dynamic>[], ...extra,
        };

    test('a moved table names the waiter now holding it', () {
      final o = RestaurantOrder.fromJson(order({'waiterUid': 'W2', 'waiterName': 'Neema'}));
      expect(o.waiterUid, 'W2');
      expect(o.waiterName, 'Neema');
      expect(o.openedByName, 'Baraka');
    });

    test('an older server (no waiter fields) still names the opener', () {
      final o = RestaurantOrder.fromJson(order({}));
      expect(o.waiterUid, isNull);
      expect(o.waiterName, 'Baraka');
    });
  });

  group('Android Bluetooth printer names', () {
    test('a picker name carries the address it prints to', () {
      expect(AndroidBtPrinters.addressOf('InnerPrinter · 00:11:22:33:44:55'), '00:11:22:33:44:55');
      expect(AndroidBtPrinters.addressOf('BlueTooth Printer · AA:BB:CC:dd:ee:ff'), 'AA:BB:CC:dd:ee:ff');
    });

    test('a Windows printer name, or a malformed one, carries none', () {
      expect(AndroidBtPrinters.addressOf('EPSON TM-T20II Receipt'), isNull);
      expect(AndroidBtPrinters.addressOf('Kitchen · not-an-address'), isNull);
    });
  });
}
