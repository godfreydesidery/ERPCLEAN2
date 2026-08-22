/// Money and number formatting for an owner's eye.
///
/// Doctrine: TZS in millions/billions, at most 3 significant figures, no cents
/// above drill level 3. A number an owner cannot read at a glance is noise.
library;

String tzs(num v, {bool sign = false}) {
  final s = _abbr(v.abs());
  final p = v < 0 ? '-' : (sign && v > 0 ? '+' : '');
  return '${p}TZS $s';
}

String tzsBare(num v, {bool sign = false}) {
  final s = _abbr(v.abs());
  final p = v < 0 ? '-' : (sign && v > 0 ? '+' : '');
  return '$p$s';
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

/// Exact figure, with thousands separators - shown on tap, never on a tile.
String tzsExact(num v) {
  final neg = v < 0;
  final digits = v.abs().round().toString();
  final buf = StringBuffer();
  for (var i = 0; i < digits.length; i++) {
    if (i > 0 && (digits.length - i) % 3 == 0) buf.write(',');
    buf.write(digits[i]);
  }
  return '${neg ? '-' : ''}TZS $buf';
}

String pct(num v, {bool sign = false}) {
  final p = sign && v > 0 ? '+' : '';
  return '$p${v.toStringAsFixed(1)}%';
}

/// Percentage-point change - never write "%" for a change in a percentage.
String pp(num v) => '${v > 0 ? '+' : ''}${v.toStringAsFixed(1)}pp';
