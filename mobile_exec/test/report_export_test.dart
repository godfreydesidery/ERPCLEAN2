// What actually leaves the phone.
//
// The screens abbreviate for a glance; a shared file must not. These lock in
// that a spreadsheet receives bare numbers, that a value containing a comma
// cannot shift every column after it, and that a truncated chat summary says
// how much it left out.
import 'package:flutter_test/flutter_test.dart';

import 'package:orbix_hq/core/export/report_doc.dart';
import 'package:orbix_hq/core/export/report_share.dart';

ExportDoc _doc({List<List<Cell>>? rows}) => ExportDoc(
      title: 'Stock report',
      subtitle: 'Kariakoo',
      meta: const ['On hand as at the moment this was run.'],
      columns: const ['Item', 'On hand', 'Unit'],
      rows: rows ??
          const [
            [Cell.text('Cooking Oil 1L'), Cell.number(240), Cell.text('PCS')],
            [Cell.text('Sugar 50kg'), Cell.number(12), Cell.text('BAG')],
          ],
      totals: const [DocTotal('Items', Cell.number(2))],
    );

void main() {
  group('Cell', () {
    test('a number reaches a spreadsheet bare and a person grouped', () {
      const c = Cell.number(1234567);
      expect(c.csv, '1234567');
      expect(c.display, '1,234,567');
      expect(c.isNumeric, isTrue);
    });

    test('money carries its currency for a person, never for the sheet', () {
      const c = Cell.money(48000);
      expect(c.csv, '48000.00');
      expect(c.display, 'TZS 48,000.00');
    });

    test('text is text', () {
      const c = Cell.text('Cooking Oil 1L');
      expect(c.csv, 'Cooking Oil 1L');
      expect(c.isNumeric, isFalse);
    });
  });

  group('toCsv', () {
    test('writes the header, the rows and the totals', () {
      final lines = _doc().toCsv().trim().split('\n').map((l) => l.trim());
      expect(lines, contains('Item,On hand,Unit'));
      expect(lines, contains('Cooking Oil 1L,240,PCS'));
      expect(lines, contains('Items,2'));
    });

    test('a comma inside a value cannot shift the columns', () {
      final csv = _doc(rows: const [
        [Cell.text('Rice, Mbeya 25kg'), Cell.number(4), Cell.text('BAG')],
      ]).toCsv();
      expect(csv, contains('"Rice, Mbeya 25kg",4,BAG'));
    });

    test('a quote inside a value is doubled, not dropped', () {
      final csv = _doc(rows: const [
        [Cell.text('Pipe 2" steel'), Cell.number(9), Cell.text('PCS')],
      ]).toCsv();
      expect(csv, contains('"Pipe 2"" steel",9,PCS'));
    });
  });

  group('toPlainText', () {
    test('reads as a message, with the totals at the end', () {
      final text = _doc().toPlainText();
      expect(text, startsWith('Stock report\nKariakoo'));
      expect(text, contains('Cooking Oil 1L — 240 · PCS'));
      expect(text, contains('Items: 2'));
    });

    test('a long report says how many lines it left out', () {
      final many = [
        for (var i = 0; i < 45; i++)
          [
            Cell.text('Item $i'),
            Cell.number(i.toDouble()),
            const Cell.text('PCS'),
          ],
      ];
      final text = _doc(rows: many).toPlainText(maxRows: 40);
      expect(text, contains('Item 39'));
      expect(text, isNot(contains('Item 40 ')));
      expect(text, contains('...and 5 more'));
    });
  });

  group('buildPdfBytes', () {
    test('produces a real PDF', () async {
      final bytes = await buildPdfBytes(_doc(), now: DateTime(2026, 8, 22));
      expect(bytes.length, greaterThan(500));
      expect(String.fromCharCodes(bytes.take(5)), '%PDF-');
    });

    test('lays out an empty report without failing', () async {
      // A report with no rows is a normal outcome — a branch with no stock, a
      // quiet day. It must still produce a document, not an exception.
      final bytes = await buildPdfBytes(_doc(rows: const []));
      expect(String.fromCharCodes(bytes.take(5)), '%PDF-');
    });

    test('lays out a report whose text is outside Latin-1', () async {
      final bytes = await buildPdfBytes(ExportDoc(
        title: 'Packs — sizes',
        columns: const ['Pack'],
        rows: const [
          [Cell.text('CTN ×24')],
        ],
      ));
      expect(String.fromCharCodes(bytes.take(5)), '%PDF-');
    });
  });

  group('asciiForPdf', () {
    test('folds characters the built-in font cannot draw', () {
      // Helvetica is Latin-1: an em dash or a multiplication sign would come
      // out as an empty box, which is how a correct report starts looking
      // broken.
      expect(asciiForPdf('Packs — sizes'), 'Packs - sizes');
      expect(asciiForPdf('CTN ×24'), 'CTN x24');
      expect(asciiForPdf('OIL-1L · PCS'), 'OIL-1L - PCS');
      expect(asciiForPdf('each item’s unit'), "each item's unit");
      expect(asciiForPdf('and 5 more…'), 'and 5 more...');
    });

    test('leaves ordinary text alone', () {
      expect(asciiForPdf('Stock report'), 'Stock report');
      expect(asciiForPdf('TZS 1,234,567.89'), 'TZS 1,234,567.89');
    });
  });

  group('fileStem', () {
    test('is a name an owner can find again', () {
      expect(
        _doc().fileStem(DateTime(2026, 8, 22)),
        'stock-report-2026-08-22',
      );
    });

    test('collapses punctuation rather than emitting it', () {
      final doc = ExportDoc(
        title: 'X read - Till 1 (Kariakoo)',
        columns: const ['A'],
        rows: const [],
      );
      expect(doc.fileStem(DateTime(2026, 1, 5)), 'x-read-till-1-kariakoo-2026-01-05');
    });
  });
}
