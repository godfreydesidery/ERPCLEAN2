/// A report, in a shape that can leave the phone.
///
/// The screens abbreviate for an owner's glance ("TZS 3.5M" — see
/// `app/format.dart`). A document someone else will open in Excel, file, or
/// send to a bank must not: every figure here carries its exact value, and the
/// CSV writes bare numbers so a spreadsheet reads them as numbers.
library;

import '../../app/format.dart';

/// One cell. Text and numbers are distinguished so the CSV can stay bare while
/// the PDF and the pasted text stay readable.
class Cell {
  const Cell._(this._text, this._number, this._decimals, this._currency);

  const Cell.text(String value) : this._(value, null, 0, null);

  const Cell.number(double value, {int decimals = 0})
      : this._(null, value, decimals, null);

  const Cell.money(double value, {String currency = 'TZS'})
      : this._(null, value, 2, currency);

  final String? _text;
  final double? _number;
  final int _decimals;
  final String? _currency;

  bool get isNumeric => _number != null;

  /// What a spreadsheet should see: no thousands separators, no currency code,
  /// nothing it would have to parse back out of a string.
  String get csv {
    final n = _number;
    if (n == null) return _text ?? '';
    return n.toStringAsFixed(_decimals);
  }

  /// What a person should see: grouped digits, currency where there is one.
  String get display {
    final n = _number;
    if (n == null) return _text ?? '';
    final grouped = groupDigits(n, decimals: _decimals);
    final currency = _currency;
    return currency == null ? grouped : '$currency $grouped';
  }
}

/// A figure that stands on its own — a report total, an expected-cash line.
class DocTotal {
  const DocTotal(this.label, this.value);

  final String label;
  final Cell value;
}

/// A whole report: what it is, what it covers, its rows and its totals.
class ExportDoc {
  const ExportDoc({
    required this.title,
    required this.columns,
    required this.rows,
    this.subtitle,
    this.meta = const <String>[],
    this.totals = const <DocTotal>[],
    this.footnote,
  });

  /// "Stock report" — names the document, not the moment.
  final String title;

  /// "1 Aug – 22 Aug 2026" — the period or scope, when there is one.
  final String? subtitle;

  /// Context lines: branch, who ran it, when. Printed under the title.
  final List<String> meta;

  final List<String> columns;
  final List<List<Cell>> rows;
  final List<DocTotal> totals;

  /// A caveat the reader must not miss — e.g. lines with no cost.
  final String? footnote;

  /// A filename an owner can find later: `stock-report-2026-08-22`.
  String fileStem(DateTime now) {
    final slug = title
        .toLowerCase()
        .replaceAll(RegExp('[^a-z0-9]+'), '-')
        .replaceAll(RegExp('^-+|-+\$'), '');
    final stamp = '${now.year.toString().padLeft(4, '0')}-'
        '${now.month.toString().padLeft(2, '0')}-'
        '${now.day.toString().padLeft(2, '0')}';
    return '${slug.isEmpty ? 'report' : slug}-$stamp';
  }

  /// RFC 4180 CSV. Excel and Google Sheets both open this directly.
  String toCsv() {
    final buf = StringBuffer();

    void line(List<String> cells) {
      buf.writeln(cells.map(_escapeCsv).join(','));
    }

    line([title]);
    if (subtitle != null) line([subtitle!]);
    for (final m in meta) {
      line([m]);
    }
    buf.writeln();

    line(columns);
    for (final row in rows) {
      line(row.map((c) => c.csv).toList());
    }

    if (totals.isNotEmpty) {
      buf.writeln();
      for (final t in totals) {
        line([t.label, t.value.csv]);
      }
    }
    if (footnote != null) {
      buf.writeln();
      line([footnote!]);
    }
    return buf.toString();
  }

  /// A plain-text summary to paste straight into a chat.
  ///
  /// Long reports are truncated on purpose: 200 stock lines pasted into
  /// WhatsApp is unreadable, and the count that was left out is stated so the
  /// reader knows to open the attachment instead of trusting a short list.
  String toPlainText({int maxRows = 40}) {
    final buf = StringBuffer()..writeln(title);
    if (subtitle != null) buf.writeln(subtitle);
    for (final m in meta) {
      buf.writeln(m);
    }
    buf.writeln();

    final shown = rows.length > maxRows ? rows.take(maxRows).toList() : rows;
    for (final row in shown) {
      final label = row.isEmpty ? '' : row.first.display;
      final rest = row.skip(1).map((c) => c.display).where((s) => s.isNotEmpty);
      buf.writeln(rest.isEmpty ? label : '$label — ${rest.join(' · ')}');
    }
    if (rows.length > shown.length) {
      final hidden = rows.length - shown.length;
      buf.writeln('...and $hidden more — see the attached file.');
    }

    if (totals.isNotEmpty) {
      buf.writeln();
      for (final t in totals) {
        buf.writeln('${t.label}: ${t.value.display}');
      }
    }
    if (footnote != null) {
      buf
        ..writeln()
        ..writeln(footnote);
    }
    return buf.toString().trimRight();
  }
}

String _escapeCsv(String value) {
  if (!value.contains(RegExp('[",\r\n]'))) return value;
  return '"${value.replaceAll('"', '""')}"';
}
