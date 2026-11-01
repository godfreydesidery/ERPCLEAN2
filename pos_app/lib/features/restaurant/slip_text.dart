/// Pure text layouts for the restaurant's printouts (ERP ADR-0065): the kitchen
/// and bar slips, and the bill. Free of Flutter so they unit-test in isolation,
/// and built on the receipt's column helpers so every line fits the paper.
library;

import 'package:intl/intl.dart';

import '../../core/money.dart';
import '../../models/restaurant.dart';
import '../receipt/receipt_text.dart';

String _qty(double q) => formatAmount(q, decimals: q % 1 == 0 ? 0 : 3);

String _time(DateTime? at) =>
    DateFormat('dd/MM/yyyy HH:mm').format((at ?? DateTime.now()).toLocal());

/// [text] wrapped to fit, every line indented by [by] spaces. ([wrapText] drops
/// leading spaces, so the indent is added after wrapping.)
List<String> _indented(String text, int by, int width) =>
    [for (final l in wrapText(text, width - by)) '${' ' * by}$l'];

/// One printed line of a slip. A [big] line prints at double width and double
/// height, so it is laid out at half the paper's columns.
class SlipLine {
  const SlipLine(this.text, {this.big = false});
  final String text;
  final bool big;
}

/// A slip for one prep station. Says WHAT to make (or stop making) and for
/// WHOM — never a price. A VOID slip leads with the cancellation so a cook
/// glancing at the rail cannot mistake it for a new order.
///
/// The station, the table and the items print LARGE, readable from across a
/// kitchen; the order number, time and who sent it stay normal size (Cafe
/// Tulia 2026-11-01: "order zitoke na font kubwa"). Bills and receipts are not
/// slips and keep the normal size.
List<SlipLine> buildSlipLines(PrepSlip slip, {required int width}) {
  final big = width ~/ 2;
  List<SlipLine> large(List<String> lines) => [for (final l in lines) SlipLine(l, big: true)];
  List<SlipLine> normal(List<String> lines) => [for (final l in lines) SlipLine(l)];
  List<SlipLine> largeCentered(String text) =>
      large([for (final l in wrapText(text, big)) centered(l, big)]);

  final where = slip.takeaway ? 'TAKEAWAY' : 'TABLE ${slip.tableName ?? ''}'.trim();
  final lines = <SlipLine>[
    ...largeCentered(slip.stationName.toUpperCase()),
    if (slip.isVoid) ...largeCentered('VOID - DO NOT MAKE'),
    if (slip.reprint) SlipLine(centered('REPRINT', width)),
    SlipLine(doubleRule(width)),
    ...largeCentered(where),
    SlipLine(leftRight(slip.orderNumber, _time(slip.at), width)),
    SlipLine(leftRight(slip.isVoid ? 'Voided' : 'Round ${slip.round}',
        slip.guestCount == null ? '' : 'Guests ${slip.guestCount}', width)),
    ...normal(labelValue('By', slip.byName, width)),
    SlipLine(rule(width)),
  ];
  // Course headings only when the slip spans more than one course.
  final courses = slip.lines.where((l) => !l.addOn).map((l) => l.course).toSet();
  int? course;
  for (final l in slip.lines) {
    if (l.addOn) {
      // An add-on belongs to the item above it: indented, never a line of its own.
      lines.addAll(large(_indented('+ ${_qty(l.quantity)} ${l.productName}', 2, big)));
      continue;
    }
    if (courses.length > 1 && l.course != course) {
      course = l.course;
      lines.add(SlipLine(centered('-- COURSE $course --', width)));
    }
    lines.addAll(large(wrapText('${_qty(l.quantity)} x ${l.productName}', big)));
    if (l.note != null && l.note!.trim().isNotEmpty) {
      lines.addAll(large(_indented('>> ${l.note!.trim()}', 2, big)));
    }
    if (slip.isVoid && l.voidReason != null && l.voidReason!.trim().isNotEmpty) {
      lines.addAll(normal(_indented('Reason: ${l.voidReason!.trim()}', 2, width)));
    }
  }
  lines.add(SlipLine(rule(width)));
  return lines;
}

/// The slip as plain text (one string, large lines included at their half
/// width) — for previews and tests; the printer gets [encodeSlip].
String buildSlipText(PrepSlip slip, {required int width}) =>
    buildSlipLines(slip, width: width).map((l) => l.text).join('\n');

/// `GS ! 0x11` / `GS ! 0x00` — character size double width and height / normal.
const List<int> escLargeOn = [0x1D, 0x21, 0x11];
const List<int> escLargeOff = [0x1D, 0x21, 0x00];

/// Encodes a slip as an ESC/POS job, switching large print on and off around
/// the [SlipLine.big] lines; ends with feeds and a partial cut like a receipt.
List<int> encodeSlip(List<SlipLine> lines) {
  const lf = 0x0A;
  final out = <int>[...escInit];
  var large = false;
  for (final l in lines) {
    if (l.big != large) {
      out.addAll(l.big ? escLargeOn : escLargeOff);
      large = l.big;
    }
    out
      ..addAll(asciiBytes(l.text))
      ..add(lf);
  }
  if (large) out.addAll(escLargeOff);
  out
    ..addAll(const [lf, lf, lf])
    ..addAll(escPartialCut);
  return out;
}

/// The bill handed to the customer: itemised and priced by the server, and
/// plainly NOT a receipt. It is not fiscal — the fiscal receipt is the one
/// printed after payment.
String buildBillText(
  RestaurantBill bill, {
  required String companyName,
  required String branchName,
  required int width,
  List<String> companyDetailLines = const [],
}) {
  final where = bill.takeaway ? 'Takeaway' : 'Table ${bill.tableName ?? ''}'.trim();
  final lines = <String>[
    centered(companyName, width),
    for (final d in companyDetailLines) centered(d, width),
    if (branchName.isNotEmpty) centered(branchName, width),
    '',
    centered('BILL - NOT A RECEIPT', width),
    if (bill.printCount > 1) centered('REPRINT ${bill.printCount - 1}', width),
    doubleRule(width),
    leftRight(where, bill.orderNumber, width),
    if (bill.guestCount != null) leftRight('Guests', '${bill.guestCount}', width),
    ...labelValue('Served by', bill.serverName, width),
    leftRight('Date', _time(bill.billedAt), width),
    rule(width),
  ];
  for (final l in bill.lines) {
    lines.addAll(wrapText(l.productName, width));
    lines.add(leftRight('  ${_qty(l.quantity)} x ${formatAmount(l.unitPrice)}',
        formatAmount(l.amount), width));
  }
  lines.addAll([
    rule(width),
    leftRight('Net', formatAmount(bill.netTotal), width),
    leftRight('VAT', formatAmount(bill.vatTotal), width),
    doubleRule(width),
    leftRight('TOTAL ${bill.currency}', formatAmount(bill.grossTotal), width),
    doubleRule(width),
    '',
    centered('Please pay at the cashier.', width),
    centered('Your receipt is printed', width),
    centered('after payment.', width),
  ]);
  return lines.join('\n');
}
