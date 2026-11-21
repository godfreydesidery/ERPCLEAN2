/// The refund slip for an item return (ERP ADR-0072), as monospaced text and
/// printer bytes. Pure — no Flutter — like `receipt_text.dart`, whose layout
/// helpers and encoders it reuses so the slip goes down exactly the same print
/// path as a receipt.
///
/// **Non-fiscal.** A refund slip carries none of the fiscal markings (no
/// legal-receipt banner, serial, Z number, verification code or QR): it is the
/// till's record that cash left the drawer against an earlier receipt.
library;

import 'package:intl/intl.dart';

import '../../core/money.dart';
import '../../models/sale_return.dart';
import 'receipt_text.dart';

String _qty(double q) => formatAmount(q, decimals: q % 1 == 0 ? 0 : 3);

/// Builds the refund slip for [ret] at [width] columns.
///
/// [copy] marks a reprint (`REFUND - COPY`), so a second slip can never pass as
/// a second refund.
String buildRefundSlipText({
  required PosSaleReturn ret,
  required String companyName,
  required String branchName,
  required int width,
  bool copy = false,
  DateTime? now,
  List<String> companyDetailLines = const [],
}) {
  final lines = <String>[];
  final when = (ret.createdAt ?? now ?? DateTime.now()).toLocal();

  final name = companyName.isEmpty ? 'OrbixPOS' : companyName.toUpperCase();
  for (final wrapped in wrapText(name, width)) {
    lines.add(centered(wrapped, width));
  }
  for (final detail in companyDetailLines) {
    if (detail.isEmpty) continue;
    for (final wrapped in wrapText(detail, width)) {
      lines.add(centered(wrapped, width));
    }
  }
  if (branchName.isNotEmpty) lines.add(centered(branchName, width));

  lines.add(doubleRule(width));
  lines.add(centered(copy ? '*** REFUND - COPY ***' : '*** REFUND ***', width));
  lines.add(doubleRule(width));

  lines.addAll(labelValue('REFUND NO:', ret.returnNo, width));
  lines.addAll(labelValue('ORIGINAL RECEIPT:', ret.invoiceNumber, width));
  lines.addAll(labelValue('DATE:', DateFormat('dd-MM-yyyy').format(when), width));
  lines.addAll(labelValue('TIME:', DateFormat('HH:mm:ss').format(when), width));
  final cashier = ret.createdByName?.trim() ?? '';
  if (cashier.isNotEmpty) lines.addAll(labelValue('CASHIER:', cashier, width));
  // A supervisor acting on their own authority leaves authorisedBy null — they
  // ARE the approver, so they are named rather than leaving the line blank.
  final approver = (ret.authorisedBy?.trim().isNotEmpty ?? false)
      ? ret.authorisedBy!.trim()
      : cashier;
  if (approver.isNotEmpty) lines.addAll(labelValue('APPROVED BY:', approver, width));
  lines.add(doubleRule(width));

  // Items: Description / Qty / Amount, columns sized to the widest value.
  if (ret.lines.isEmpty) {
    lines.add(centered('(no items)', width));
  } else {
    final qtyW = ret.lines
        .map((l) => _qty(l.qtyReturned).length + 1)
        .fold<int>(4, (a, b) => a > b ? a : b);
    final amtW = ret.lines
        .map((l) => formatAmount(l.grossAmount).length + 1)
        .fold<int>(7, (a, b) => a > b ? a : b);
    final numbersW = qtyW + amtW;
    final stacked = width - numbersW < 12;
    final nameW = stacked ? width : width - numbersW;
    String numbers(String q, String a) => q.padLeft(qtyW) + a.padLeft(amtW);

    lines.add(stacked
        ? leftRight('Description', numbers('Qty', 'Amount'), width)
        : 'Description'.padRight(nameW) + numbers('Qty', 'Amount'));
    for (final l in ret.lines) {
      final nameLines = wrapText(l.productName, nameW);
      final row = numbers(_qty(l.qtyReturned), formatAmount(l.grossAmount));
      if (stacked) {
        lines.addAll(nameLines);
        lines.add(row.padLeft(width));
      } else {
        lines.add(nameLines.first.padRight(nameW) + row);
        lines.addAll(nameLines.skip(1));
      }
    }
  }
  lines.add(doubleRule(width));

  lines.addAll(labelValue('TOTAL EXCL OF TAX:', formatAmount(ret.netAmount), width));
  lines.addAll(labelValue('TOTAL TAX:', formatAmount(ret.vatAmount), width));
  lines.addAll(labelValue('TOTAL REFUNDED:',
      '${ret.currency} ${formatAmount(ret.grossAmount)}', width));
  lines.addAll(labelValue(
      'REFUNDED IN:', ret.refundTender.isEmpty ? 'CASH' : ret.refundTender, width));
  lines.add(doubleRule(width));

  final reason = ret.reason.trim();
  if (reason.isNotEmpty) {
    lines.add('REASON:');
    lines.addAll(wrapText(reason, width));
    lines.add('');
  }
  lines.add('Customer signature:');
  lines.add('');
  lines.add(rule(width));
  lines.add('');
  lines.add(centered('Not a fiscal receipt.', width));
  return lines.join('\n');
}

/// Builds the refund slip and encodes it for [mode] (`'escpos'` or `'plain'`).
/// Never kicks the drawer: like a reversal, a refund is money going OUT, and
/// the till has never popped the drawer for that on its own.
List<int> buildRefundSlipBytes({
  required PosSaleReturn ret,
  required String companyName,
  required String branchName,
  required int width,
  required String mode,
  bool copy = false,
  DateTime? now,
  List<String> companyDetailLines = const [],
}) {
  final text = buildRefundSlipText(
    ret: ret,
    companyName: companyName,
    branchName: branchName,
    width: width,
    copy: copy,
    now: now,
    companyDetailLines: companyDetailLines,
  );
  return mode == 'plain' ? encodePlainText(text) : encodeEscPos(text);
}
