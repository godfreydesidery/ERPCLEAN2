/// Pure X/Z-read rendering: turns a drawer report into a monospaced,
/// column-fixed text block for a thermal printer.
///
/// Deliberately free of Flutter / Riverpod / dart:ffi so it can be unit-tested
/// in isolation, and it reuses the receipt's layout primitives ([centered],
/// [leftRight], [rule], [wrapText]) and byte encoders so a report and a receipt
/// come off the same roll looking like the same document.
///
/// Every emitted line is `<= width` characters, so the printer never soft-wraps
/// a figure out of alignment.
library;

import 'package:intl/intl.dart';

import '../../core/money.dart';
import '../../models/pos.dart';
import 'receipt_text.dart';

/// Everything about the till that a drawer report prints but the X/Z DTO does
/// not carry — gathered once by the caller so the renderer stays pure.
class ReportContext {
  const ReportContext({
    required this.companyName,
    required this.branchName,
    required this.cashierName,
    required this.sessionNumber,
    required this.currency,
    this.companyDetailLines = const [],
  });

  final String companyName;
  final String branchName;
  final String cashierName;
  final String sessionNumber;
  final String currency;
  final List<String> companyDetailLines;
}

final DateFormat _stamp = DateFormat('yyyy-MM-dd HH:mm');

/// Mid-shift X-read: what is in the drawer right now.
///
/// Explicitly labelled non-resetting. A cashier who believes a report "closed"
/// the shift will stop counting, so the report has to say what it is.
String buildXReadText({
  required XRead x,
  required ReportContext ctx,
  required int width,
  DateTime? now,
}) {
  final lines = <String>[];
  _header(lines, ctx, width, 'X-READ', 'MID-SHIFT - NOT A CLOSE');
  _identity(lines, ctx, width,
      openedAt: x.openedAt, printedAt: now ?? DateTime.now());

  lines.add(rule(width));
  lines.add(leftRight('Sales (all tenders)',
      formatAmount(x.totalSalesAmount), width));
  _tenderBreakdown(lines, x.tenderSubtotals, width);

  lines.add(rule(width));
  lines.add(leftRight('Opening float', formatAmount(x.openingFloatAmount), width));
  lines.add(leftRight('Cash sales', formatAmount(x.cashTenderAmount), width));
  _payouts(lines, x.totalPayoutsNetAmount, x.payoutSubtotals, width);
  lines.add(rule(width));
  lines.add(leftRight(
      'EXPECTED CASH ${ctx.currency}', formatAmount(x.expectedCashAmount), width));
  lines.add(rule(width));
  lines.add(leftRight('Invoices', '${x.invoiceCount}', width));

  lines.add('');
  lines.add(centered('X-read does not close the shift', width));
  lines.add(centered('and resets nothing.', width));
  lines.add('');
  lines.add(centered('*** NOT A FISCAL RECEIPT ***', width));
  return lines.join('\n');
}

/// End-of-shift Z-read: the shift's closing statement.
///
/// [reprint] stamps the copy as such. A Z-read is now re-readable after the
/// fact, so an unstamped second copy could be presented as the original — the
/// figures are identical by design, which is exactly why the paper has to say
/// which one it is.
String buildZReadText({
  required ZRead z,
  required ReportContext ctx,
  required int width,
  bool reprint = false,
  DateTime? now,
}) {
  final lines = <String>[];
  _header(lines, ctx, width, 'Z-READ', 'END OF SHIFT');
  _identity(lines, ctx, width,
      openedAt: z.openedAt,
      closedAt: z.closedAt,
      reconciledAt: z.reconciledAt,
      printedAt: now ?? DateTime.now());

  lines.add(rule(width));
  lines.add(leftRight(
      'Sales (all tenders)', formatAmount(z.totalSalesAmount), width));
  _tenderBreakdown(lines, z.tenderSubtotals, width);

  lines.add(rule(width));
  lines.add(leftRight('Opening float', formatAmount(z.openingFloatAmount), width));
  lines.add(leftRight('Cash sales', formatAmount(z.cashTenderAmount), width));
  _payouts(lines, z.totalPayoutsNetAmount, z.payoutSubtotals, width);
  lines.add(rule(width));
  lines.add(leftRight('Expected cash', formatAmount(z.expectedCashAmount), width));
  lines.add(leftRight('Counted cash', formatAmount(z.countedCashAmount), width));
  lines.add(leftRight(
      'VARIANCE ${ctx.currency}', formatAmount(z.varianceAmount), width));
  // Spelled out: a bare signed number gets read the wrong way round at 2am.
  lines.add(centered(_varianceWord(z.varianceAmount), width));
  lines.add(rule(width));
  lines.add(leftRight('Invoices', '${z.invoiceCount}', width));

  lines.add('');
  if (reprint) {
    lines.add(centered('*** REPRINT ***', width));
    lines.add(centered('Same figures as the original.', width));
    lines.add('');
  }
  lines.add(centered('Cashier ______________________', width));
  lines.add('');
  lines.add(centered('Manager ______________________', width));
  lines.add('');
  lines.add(centered('*** NOT A FISCAL RECEIPT ***', width));
  return lines.join('\n');
}

// --------------------------------------------------------------------------
// Shared sections
// --------------------------------------------------------------------------

void _header(List<String> lines, ReportContext ctx, int width, String title,
    String subtitle) {
  lines.add(centered(
      ctx.companyName.isEmpty ? 'OrbixPOS' : ctx.companyName, width));
  for (final detail in ctx.companyDetailLines) {
    if (detail.isEmpty) continue;
    for (final wrapped in wrapText(detail, width)) {
      lines.add(centered(wrapped, width));
    }
  }
  if (ctx.branchName.isNotEmpty) lines.add(centered(ctx.branchName, width));
  lines.add('');
  lines.add(centered(title, width));
  lines.add(centered(subtitle, width));
  lines.add('');
}

void _identity(
  List<String> lines,
  ReportContext ctx,
  int width, {
  DateTime? openedAt,
  DateTime? closedAt,
  DateTime? reconciledAt,
  required DateTime printedAt,
}) {
  lines.add(leftRight('Session', ctx.sessionNumber, width));
  if (ctx.cashierName.isNotEmpty) {
    lines.add(leftRight('Cashier', ctx.cashierName, width));
  }
  if (openedAt != null) {
    lines.add(leftRight('Opened', _stamp.format(openedAt.toLocal()), width));
  }
  if (closedAt != null) {
    lines.add(leftRight('Closed', _stamp.format(closedAt.toLocal()), width));
  }
  if (reconciledAt != null) {
    lines.add(
        leftRight('Reconciled', _stamp.format(reconciledAt.toLocal()), width));
  }
  lines.add(leftRight('Printed', _stamp.format(printedAt.toLocal()), width));
}

/// Per-tender turnover, indented under the sales line. Explains why gross sales
/// legitimately exceed the cash retained in the drawer.
void _tenderBreakdown(
    List<String> lines, List<TenderSubtotal> subtotals, int width) {
  for (final s in subtotals) {
    lines.add(leftRight('  ${s.tenderType.label}', formatAmount(s.amount), width));
  }
}

/// Payouts, with the per-type breakdown when the server sends one.
///
/// The subtotals always sum to the net total, so the indented rows are a
/// decomposition of the line above them, never an extra deduction. Zero-filled
/// types are printed rather than skipped: a shift with no refunds should print
/// "Refund 0.00", so the reader can tell "none happened" from "the report
/// forgot to mention it".
void _payouts(List<String> lines, double totalNet,
    List<PayoutSubtotal> subtotals, int width) {
  lines.add(leftRight('Payouts', '-${formatAmount(totalNet)}', width));
  for (final s in subtotals) {
    final label = s.count == 0
        ? '  ${s.payoutType.label}'
        : '  ${s.payoutType.label} (${s.count})';
    lines.add(leftRight(label, '-${formatAmount(s.amount)}', width));
  }
}

String _varianceWord(double variance) {
  if (variance == 0) return 'Drawer balances exactly.';
  return variance > 0
      ? 'Drawer is OVER by ${formatAmount(variance)}.'
      : 'Drawer is SHORT by ${formatAmount(variance.abs())}.';
}

// --------------------------------------------------------------------------
// Bytes
// --------------------------------------------------------------------------

/// Encodes an already-rendered report for the printer. Never kicks the drawer —
/// a report is read, not paid out, and popping the till on a report is how a
/// cash-control document turns into a cash-control problem.
List<int> encodeReportBytes(String text, {required String mode}) =>
    mode == 'plain' ? encodePlainText(text) : encodeEscPos(text);
