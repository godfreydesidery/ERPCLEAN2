/// Pure receipt rendering: turns a finalised [Receipt] into a monospaced,
/// column-fixed text block and encodes it to printer bytes. Deliberately free of
/// Flutter / Riverpod / dart:ffi so it can be unit-tested in isolation and reused
/// by both the thermal (ESC/POS) and plain-text paths.
///
/// Two widths are supported: **32 columns** (58 mm paper) and **48 columns**
/// (80 mm paper). Every emitted content line is `<= width` characters, so the
/// printer never soft-wraps a total out of alignment.
library;

import 'package:intl/intl.dart';

import '../../core/money.dart';
import '../../models/sale.dart';

/// Standard column counts for the two supported paper widths.
const int kCols58mm = 32;
const int kCols80mm = 48;

// --------------------------------------------------------------------------
// ESC/POS control sequences
// --------------------------------------------------------------------------

/// `ESC @` — initialise printer (clear buffer, reset modes). `0x1B 0x40`.
const List<int> escInit = [0x1B, 0x40];

/// `GS V 66 0` — feed and **partial cut**. `0x1D 0x56 0x42 0x00`.
const List<int> escPartialCut = [0x1D, 0x56, 0x42, 0x00];

/// `ESC p 0 25 250` — cash-drawer kick on pin 2. `0x1B 0x70 0x00 0x19 0xFA`.
const List<int> escDrawerKick = [0x1B, 0x70, 0x00, 0x19, 0xFA];

/// Line feed.
const int _lf = 0x0A;

/// Form feed (plain-text mode page eject).
const int _ff = 0x0C;

// --------------------------------------------------------------------------
// Layout helpers (exposed for unit testing)
// --------------------------------------------------------------------------

/// Centres [text] within [width] by left-padding with spaces. If [text] is at
/// least [width] long it is returned unchanged (the printer soft-wraps it) so a
/// long company name is never silently truncated.
String centered(String text, int width) {
  if (text.length >= width) return text;
  final pad = (width - text.length) ~/ 2;
  return (' ' * pad) + text;
}

/// Renders [left] flush-left and [right] flush-right on a single [width]-wide
/// line. [right] is preserved (truncated only if it alone exceeds [width]);
/// [left] is truncated to whatever space remains. The result is exactly [width]
/// characters wide.
String leftRight(String left, String right, int width) {
  var r = right.length > width ? right.substring(0, width) : right;
  final maxLeft = width - r.length;
  final l = left.length > maxLeft ? left.substring(0, maxLeft) : left;
  final gap = width - l.length - r.length;
  return l + (' ' * gap) + r;
}

/// A full-width horizontal rule of dashes.
String rule(int width) => '-' * width;

/// Word-wraps [text] to lines no wider than [width]. Words longer than [width]
/// are hard-split. Always returns at least one (possibly empty) line.
List<String> wrapText(String text, int width) {
  final words = text.split(RegExp(r'\s+')).where((w) => w.isNotEmpty).toList();
  if (words.isEmpty) return [''];
  final lines = <String>[];
  var cur = '';
  for (var w in words) {
    while (w.length > width) {
      if (cur.isNotEmpty) {
        lines.add(cur);
        cur = '';
      }
      lines.add(w.substring(0, width));
      w = w.substring(width);
    }
    if (cur.isEmpty) {
      cur = w;
    } else if (cur.length + 1 + w.length <= width) {
      cur = '$cur $w';
    } else {
      lines.add(cur);
      cur = w;
    }
  }
  if (cur.isNotEmpty) lines.add(cur);
  return lines;
}

// --------------------------------------------------------------------------
// Receipt text
// --------------------------------------------------------------------------

String _qty(double q) =>
    formatAmount(q, decimals: q % 1 == 0 ? 0 : 3);

/// Builds the full receipt as a newline-joined string for [width] columns.
///
/// Mirrors the on-screen receipt dialog: centred company/branch header, the
/// invoice/date/cashier/customer field lines, the line items, the Net/VAT/TOTAL
/// block, the tender lines and change, then a thank-you footer with the invoice
/// number. When [gift] is true all prices are hidden (line amounts, unit prices
/// and the totals block) — only item names and quantities remain, as a returns
/// slip. When [reversed] is true a `*** REVERSED ***` banner is appended.
String buildReceiptText({
  required Receipt receipt,
  required String companyName,
  required String branchName,
  required String cashierName,
  required int width,
  required bool gift,
  bool reversed = false,
  DateTime? now,
  List<String> companyDetailLines = const [],
}) {
  final inv = receipt.invoice;
  final lines = <String>[];
  final df = DateFormat('yyyy-MM-dd HH:mm');
  final when = (inv.finalisedAt ?? now ?? DateTime.now()).toLocal();

  // Header: company name, then the fiscal detail block (address/contacts/
  // TIN/VRN, each centred and wrapped to width), then the branch name.
  lines.add(centered(companyName.isEmpty ? 'OrbixPOS' : companyName, width));
  for (final detail in companyDetailLines) {
    if (detail.isEmpty) continue;
    for (final wrapped in wrapText(detail, width)) {
      lines.add(centered(wrapped, width));
    }
  }
  if (branchName.isNotEmpty) lines.add(centered(branchName, width));
  lines.add('');

  // Field lines
  lines.add(leftRight('Invoice', inv.invoiceNumber, width));
  lines.add(leftRight('Date', df.format(when), width));
  if (cashierName.isNotEmpty) {
    lines.add(leftRight('Cashier', cashierName, width));
  }
  final customer = inv.customerName ?? inv.customerId;
  if (customer.isNotEmpty) {
    lines.add(leftRight('Customer', customer, width));
  }
  lines.add(rule(width));

  // Line items
  if (receipt.lines.isEmpty) {
    lines.add(centered('(line detail not loaded)', width));
  } else {
    for (final l in receipt.lines) {
      lines.addAll(wrapText(l.productName, width));
      if (gift) {
        lines.add('  Qty ${_qty(l.quantity)}');
      } else {
        lines.add(leftRight(
            '  ${_qty(l.quantity)} x ${formatAmount(l.unitPriceAmount)}',
            formatAmount(l.grossAmount),
            width));
        if (l.lineDiscountAmount > 0) {
          lines.add('  less disc ${formatAmount(l.lineDiscountAmount)}');
        }
      }
    }
  }
  lines.add(rule(width));

  // Totals + tenders (hidden on a gift receipt)
  if (gift) {
    lines.add(centered('* gift receipt - prices hidden *', width));
  } else {
    lines.add(leftRight('Net', formatAmount(inv.netTotalAmount), width));
    lines.add(leftRight('VAT', formatAmount(inv.vatTotalAmount), width));
    lines.add(leftRight('TOTAL ${inv.currency}',
        formatAmount(inv.grossTotalAmount), width));
    lines.add(rule(width));
    for (final p in receipt.payments) {
      lines.add(leftRight(p.tenderType.label, formatAmount(p.amount), width));
    }
    if (receipt.changeDue > 0) {
      lines.add(leftRight('Change', formatAmount(receipt.changeDue), width));
    }
  }

  // Footer
  lines.add('');
  lines.add(centered('Thank you!', width));
  lines.add(centered(inv.invoiceNumber, width));
  if (reversed) {
    lines.add('');
    lines.add(centered('*** REVERSED ***', width));
  }

  return lines.join('\n');
}

/// A short self-test receipt for the Setup "Test print" button, so the operator
/// can verify paper width and alignment before go-live.
String sampleReceiptText({required int width}) {
  final lines = <String>[
    centered('OrbixPOS', width),
    centered('Test print', width),
    '',
    leftRight('Width', '$width cols', width),
    leftRight('Date', DateFormat('yyyy-MM-dd HH:mm').format(DateTime.now()),
        width),
    rule(width),
    leftRight('Sample item A', formatAmount(1000), width),
    leftRight('Sample item B', formatAmount(2500), width),
    rule(width),
    leftRight('TOTAL TZS', formatAmount(3500), width),
    '',
    centered('Alignment OK if the', width),
    centered('amounts are flush right.', width),
  ];
  return lines.join('\n');
}

// --------------------------------------------------------------------------
// Byte encoders
// --------------------------------------------------------------------------

/// Maps a string to printable-ASCII bytes: keeps `0x20`–`0x7E` and line feeds,
/// replacing everything else (accents, tabs, control chars) with `?`. Thermal
/// heads reliably render the ASCII range on the default CP437 code page.
List<int> asciiBytes(String s) {
  final out = <int>[];
  for (final u in s.codeUnits) {
    if (u == _lf) {
      out.add(_lf);
    } else if (u >= 0x20 && u <= 0x7E) {
      out.add(u);
    } else {
      out.add(0x3F); // '?'
    }
  }
  return out;
}

/// Encodes [text] as an ESC/POS job: `ESC @` init, optional cash-drawer kick,
/// the receipt body, a few feeds, then a partial cut.
List<int> encodeEscPos(String text, {bool kickDrawer = false}) {
  final out = <int>[];
  out.addAll(escInit);
  if (kickDrawer) out.addAll(escDrawerKick);
  out.addAll(asciiBytes(text));
  out.addAll(const [_lf, _lf, _lf, _lf]);
  out.addAll(escPartialCut);
  return out;
}

/// Encodes [text] as a plain-text job: the body then a form feed (page eject).
/// For printers driven as a generic/text device with no ESC/POS cutter.
List<int> encodePlainText(String text) {
  final out = <int>[];
  out.addAll(asciiBytes(text));
  out.addAll(const [_lf, _lf]);
  out.add(_ff);
  return out;
}

/// Convenience: build the receipt text for [mode] and encode it to bytes.
/// [mode] is `'escpos'` (default) or `'plain'`.
List<int> buildReceiptBytes({
  required Receipt receipt,
  required String companyName,
  required String branchName,
  required String cashierName,
  required int width,
  required String mode,
  required bool gift,
  bool reversed = false,
  bool kickDrawer = false,
  DateTime? now,
  List<String> companyDetailLines = const [],
}) {
  final text = buildReceiptText(
    receipt: receipt,
    companyName: companyName,
    branchName: branchName,
    cashierName: cashierName,
    width: width,
    gift: gift,
    reversed: reversed,
    now: now,
    companyDetailLines: companyDetailLines,
  );
  return mode == 'plain'
      ? encodePlainText(text)
      : encodeEscPos(text, kickDrawer: kickDrawer);
}
