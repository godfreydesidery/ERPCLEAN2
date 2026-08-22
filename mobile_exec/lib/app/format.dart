/// Money and number formatting.
///
/// Amounts are written **in full**: `TZS 3,500,000`, never `TZS 3.5M`. The app
/// abbreviated everywhere on the theory that a glanceable figure beats an exact
/// one. The owner reading those figures asked for the opposite (2026-08-22),
/// and he is right: these are amounts he checks against a bank statement, a
/// supplier invoice or a drawer of cash, and a rounded one cannot be checked
/// at all.
///
/// [tzsShort] survives for chart geometry only, where a full figure will not
/// fit above a 40-pixel bar. Everywhere else, render an amount through the
/// `Amount` widget so a long figure shrinks rather than wrapping or clipping.
library;

/// "TZS 1,234,567" — whole shillings, since cents do not circulate here.
String tzs(num v, {bool sign = false}) {
  return '${_prefix(v, sign)}TZS ${groupDigits(v.abs())}';
}

/// The figure without its currency, still in full: "1,234,567".
String tzsBare(num v, {bool sign = false}) {
  return '${_prefix(v, sign)}${groupDigits(v.abs())}';
}

/// "3.5M" — compact and abbreviated, no currency.
///
/// For chart geometry ONLY: a bar label has the width of its bar, and a full
/// figure there either overlaps its neighbour or is clipped. Never use this for
/// a figure someone might act on.
String tzsShort(num v, {bool sign = false}) {
  return '${_prefix(v, sign)}${_abbr(v.abs())}';
}

String _prefix(num v, bool sign) => v < 0 ? '-' : (sign && v > 0 ? '+' : '');

/// "1,234,567.89" — thousands separators, fixed decimals, sign kept in front.
String groupDigits(num value, {int decimals = 0}) {
  final negative = value < 0;
  final fixed = value.abs().toStringAsFixed(decimals);
  final dot = fixed.indexOf('.');
  final whole = dot == -1 ? fixed : fixed.substring(0, dot);
  final fraction = dot == -1 ? '' : fixed.substring(dot);

  final buf = StringBuffer();
  for (var i = 0; i < whole.length; i++) {
    if (i > 0 && (whole.length - i) % 3 == 0) buf.write(',');
    buf.write(whole[i]);
  }
  return '${negative ? '-' : ''}$buf$fraction';
}

String _abbr(num v) {
  if (v >= 1000000000) return '${_sig(v / 1000000000)}bn';
  if (v >= 1000000) return '${_sig(v / 1000000)}M';
  if (v >= 1000) return '${_sig(v / 1000)}k';
  return v.toStringAsFixed(0);
}

String _sig(double v) {
  if (v >= 100) return v.toStringAsFixed(0);
  if (v >= 10) return v.toStringAsFixed(1);
  return v.toStringAsFixed(2);
}

String pct(num v, {bool sign = false}) {
  final p = sign && v > 0 ? '+' : '';
  return '$p${v.toStringAsFixed(1)}%';
}

/// Percentage-point change - never write "%" for a change in a percentage.
String pp(num v) => '${v > 0 ? '+' : ''}${v.toStringAsFixed(1)}pp';

/// A quantity for READING: "2,400", "240", "1.50". Grouped like an amount,
/// because a five-figure stock count is as hard to read as a five-figure price.
String qty(num v) => v == v.roundToDouble()
    ? groupDigits(v)
    : groupDigits(v, decimals: 2);

/// A number for an INPUT FIELD: "2400", "1.5" — never grouped.
///
/// Seeding a numeric field with "2,400" looks right and then fails to parse,
/// so a pack size silently refuses to save. Separate from [qty] for that reason.
String plainNumber(num v) =>
    v == v.roundToDouble() ? v.toStringAsFixed(0) : '$v';
