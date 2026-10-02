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
import '../../models/enums.dart';
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

/// A full-width section divider of equals signs.
String doubleRule(int width) => '=' * width;

/// A `LABEL: value` field line. Kept on one line, value flush-right, while
/// both fit; otherwise the label gets its own line and the value is wrapped
/// flush-right beneath it, so a long customer name is never cut short.
List<String> labelValue(String label, String value, int width) {
  if (label.length + 1 + value.length <= width) {
    return [leftRight(label, value, width)];
  }
  return [
    label,
    for (final part in wrapText(value, width)) part.padLeft(width),
  ];
}

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

/// The tax-category letter printed beside a line, using the TRA letter codes:
/// A = standard rate, C = zero-rated, E = exempt. A line journalled before
/// [InvoiceLine.vatStatus] was carried falls back to its rate: taxed means A,
/// untaxed is left blank because zero-rated and exempt cannot be told apart.
String taxCodeFor(InvoiceLine l) => switch (l.vatStatus) {
      VatStatus.standard => 'A',
      VatStatus.zeroRated => 'C',
      VatStatus.exempt => 'E',
      VatStatus.unknown => l.vatRate > 0 ? 'A' : '',
    };

/// A VAT rate as a percentage label. The API has sent both 18 and 0.18.
String _ratePercent(double rate) {
  final pct = rate <= 1 ? rate * 100 : rate;
  final rounded = (pct * 100).round() / 100;
  return rounded % 1 == 0
      ? '${rounded.toInt()}%'
      : '${rounded.toStringAsFixed(2).replaceFirst(RegExp(r'0$'), '')}%';
}

/// Builds the full receipt as a newline-joined string for [width] columns.
///
/// Laid out after the TRA-style supermarket receipt: centred company header
/// (name, address/contacts/TIN/VRN, branch), a customer block, the receipt
/// number/date/time/cashier block, an item table (Description / Qty / Amount
/// plus the tax letter), the tax totals, the tenders and change, then a
/// thank-you footer. This is an ordinary sales receipt: it carries none of the
/// fiscal markings (legal-receipt banners, serial/UIN, Z number, verification
/// code, QR) — those may only be printed from a real TRA fiscalisation.
///
/// When [gift] is true all prices are hidden (line amounts, unit prices and the
/// totals block) — only item names and quantities remain, as a returns slip.
/// When [reversed] is true a `*** REVERSED ***` banner is appended, followed by
/// the approver when [reversedBy] is given.
String buildReceiptText({
  required Receipt receipt,
  required String companyName,
  required String branchName,
  required String cashierName,
  required int width,
  required bool gift,
  bool reversed = false,
  String? reversedBy,
  DateTime? now,
  List<String> companyDetailLines = const [],
}) {
  final inv = receipt.invoice;
  final lines = <String>[];
  final when = (inv.finalisedAt ?? now ?? DateTime.now()).toLocal();

  // Header: company name, then the detail block (address/contacts/TIN/VRN,
  // each centred and wrapped to width), then the branch name.
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

  // Customer block
  lines.add(doubleRule(width));
  final customer = (inv.customerName ?? '').trim();
  lines.addAll(labelValue(
      'CUSTOMER NAME:', customer.isEmpty ? 'n/a' : customer, width));

  // Receipt block
  lines.add(doubleRule(width));
  lines.addAll(labelValue('RECEIPT NO:', inv.invoiceNumber, width));
  lines.addAll(labelValue(
      'RECEIPT DATE:', DateFormat('dd-MM-yyyy').format(when), width));
  lines.addAll(labelValue(
      'RECEIPT TIME:', DateFormat('HH:mm:ss').format(when), width));
  if (cashierName.isNotEmpty) {
    lines.addAll(labelValue('CASHIER:', cashierName, width));
  }
  lines.add(doubleRule(width));

  // Item table. Qty and Amount columns are sized to the widest value on this
  // receipt so a large total never pushes a row past the paper edge.
  if (receipt.lines.isEmpty) {
    lines.add(centered('(line detail not loaded)', width));
  } else {
    final qtyW = receipt.lines
        .map((l) => _qty(l.quantity).length + 1)
        .fold<int>(4, (a, b) => a > b ? a : b);
    final amtW = gift
        ? 0
        : receipt.lines
            .map((l) => formatAmount(l.grossAmount).length + 1)
            .fold<int>(7, (a, b) => a > b ? a : b);
    final codeW = gift ? 0 : 2;
    final numbersW = qtyW + amtW + codeW;
    // Too little room left for a readable name (a huge amount on 58 mm paper):
    // stack instead — the name on its own full-width line(s), the numbers
    // flush-right beneath it.
    // 12 = the 'Description' heading plus a space.
    final stacked = width - numbersW < 12;
    final nameW = stacked ? width : width - numbersW;
    String numbers(String qty, String amt, String code) =>
        qty.padLeft(qtyW) +
        (gift ? '' : amt.padLeft(amtW) + code.padLeft(codeW));

    lines.add(stacked
        ? leftRight('Description', numbers('Qty', 'Amount', ''), width)
        : 'Description'.padRight(nameW) + numbers('Qty', 'Amount', ''));
    for (final l in receipt.lines) {
      final nameLines = wrapText(l.productName, nameW);
      final row = numbers(_qty(l.quantity), formatAmount(l.grossAmount),
          gift ? '' : taxCodeFor(l));
      if (stacked) {
        lines.addAll(nameLines);
        lines.add(row.padLeft(width));
      } else {
        lines.add(nameLines.first.padRight(nameW) + row);
        lines.addAll(nameLines.skip(1));
      }
      if (!gift) {
        if (l.quantity != 1) {
          lines.add('  @ ${formatAmount(l.unitPriceAmount)}');
        }
        if (l.lineDiscountAmount > 0) {
          lines.add('  less disc ${formatAmount(l.lineDiscountAmount)}');
        }
      }
    }
  }
  lines.add(doubleRule(width));

  // Totals + tenders (hidden on a gift receipt)
  if (gift) {
    lines.add(centered('* gift receipt - prices hidden *', width));
  } else {
    // labelValue, not leftRight: at 58 mm a multi-million total leaves no room
    // beside its label, and leftRight would cut the label short.
    lines.addAll(labelValue(
        'TOTAL EXCL OF TAX:', formatAmount(inv.netTotalAmount), width));
    // One line per standard rate on the receipt, in first-seen order.
    final byRate = <String, double>{};
    for (final l in receipt.lines) {
      if (taxCodeFor(l) != 'A') continue;
      final key = _ratePercent(l.vatRate);
      byRate[key] = (byRate[key] ?? 0) + l.vatAmount;
    }
    for (final e in byRate.entries) {
      lines.addAll(labelValue('TAX A-${e.key}', formatAmount(e.value), width));
    }
    lines.addAll(
        labelValue('TOTAL TAX:', formatAmount(inv.vatTotalAmount), width));
    lines.addAll(labelValue('TOTAL INCL OF TAX:',
        '${inv.currency} ${formatAmount(inv.grossTotalAmount)}', width));
    lines.add(doubleRule(width));
    for (final p in receipt.payments) {
      // The wire token spelled out (MOBILE MONEY), not the till's short label.
      lines.addAll(labelValue(p.tenderType.wire.replaceAll('_', ' '),
          formatAmount(p.amount), width));
    }
    if (receipt.changeDue > 0) {
      lines.addAll(
          labelValue('CHANGE', formatAmount(receipt.changeDue), width));
    }
  }

  // Footer
  lines.add('');
  lines.add(centered('Thank you!', width));
  if (reversed) {
    lines.add('');
    lines.add(centered('*** REVERSED ***', width));
    if (reversedBy != null && reversedBy.isNotEmpty) {
      for (final wrapped in wrapText('Approved by $reversedBy', width)) {
        lines.add(centered(wrapped, width));
      }
    }
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
  String? reversedBy,
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
    reversedBy: reversedBy,
    now: now,
    companyDetailLines: companyDetailLines,
  );
  return mode == 'plain'
      ? encodePlainText(text)
      : encodeEscPos(text, kickDrawer: kickDrawer);
}
