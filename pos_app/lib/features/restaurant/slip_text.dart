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

/// A slip for one prep station. Says WHAT to make (or stop making) and for
/// WHOM — never a price. A VOID slip leads with the cancellation so a cook
/// glancing at the rail cannot mistake it for a new order.
String buildSlipText(PrepSlip slip, {required int width}) {
  final where = slip.takeaway ? 'TAKEAWAY' : 'TABLE ${slip.tableName ?? ''}'.trim();
  final lines = <String>[
    centered(slip.stationName.toUpperCase(), width),
    if (slip.isVoid) ...[
      centered('*** VOID - DO NOT MAKE ***', width),
    ],
    if (slip.reprint) centered('REPRINT', width),
    doubleRule(width),
    leftRight(where, slip.orderNumber, width),
    if (slip.guestCount != null) leftRight('Guests', '${slip.guestCount}', width),
    leftRight(slip.isVoid ? 'Voided' : 'Round ${slip.round}', _time(slip.at), width),
    ...labelValue('By', slip.byName, width),
    rule(width),
  ];
  for (final l in slip.lines) {
    final head = '${_qty(l.quantity)} x ${l.productName}';
    lines.addAll(wrapText(head, width));
    if (l.note != null && l.note!.trim().isNotEmpty) {
      lines.addAll(wrapText('  >> ${l.note!.trim()}', width));
    }
    if (slip.isVoid && l.voidReason != null && l.voidReason!.trim().isNotEmpty) {
      lines.addAll(wrapText('  Reason: ${l.voidReason!.trim()}', width));
    }
  }
  lines.add(rule(width));
  return lines.join('\n');
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
