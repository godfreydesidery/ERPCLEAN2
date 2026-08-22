import 'dart:io';
import 'dart:typed_data';
import 'dart:ui' show Rect;

import 'package:path_provider/path_provider.dart';
import 'package:pdf/pdf.dart';
import 'package:pdf/widgets.dart' as pw;
import 'package:share_plus/share_plus.dart';

import 'report_doc.dart';

/// How a report leaves the phone.
enum ExportFormat {
  /// A document to read, file or forward. What an owner sends a bank.
  pdf,

  /// Numbers to work on. Excel and Google Sheets open it directly.
  csv,

  /// The report as a message — no attachment, straight into a chat.
  text;

  String get label => switch (this) {
        ExportFormat.pdf => 'PDF',
        ExportFormat.csv => 'CSV',
        ExportFormat.text => 'Text',
      };

  String get hint => switch (this) {
        ExportFormat.pdf => 'A document to read or file',
        ExportFormat.csv => 'Opens in Excel',
        ExportFormat.text => 'Pastes into a chat',
      };
}

/// The PDF bytes for [doc]. Exposed separately from [shareDoc] so a caller can
/// save or preview without going through the share sheet.
Future<Uint8List> buildPdfBytes(ExportDoc doc, {DateTime? now}) =>
    _document(doc, now ?? DateTime.now()).save();

/// Hands [doc] to the phone's own share sheet — WhatsApp, email, Drive, Files,
/// whatever the owner has installed.
///
/// PDF and CSV go as a real file written to the app's cache; text goes as the
/// message body, because a chat message is what "send it on WhatsApp" means
/// when there is nothing worth attaching.
Future<ShareResult> shareDoc(
  ExportDoc doc,
  ExportFormat format, {
  Rect? origin,
  DateTime? now,
}) async {
  final stamp = now ?? DateTime.now();
  final subject =
      doc.subtitle == null ? doc.title : '${doc.title} - ${doc.subtitle}';

  if (format == ExportFormat.text) {
    return SharePlus.instance.share(ShareParams(
      text: doc.toPlainText(),
      subject: subject,
      title: doc.title,
      sharePositionOrigin: origin,
    ));
  }

  final dir = await getTemporaryDirectory();
  final stem = doc.fileStem(stamp);
  final sep = Platform.pathSeparator;

  final XFile file;
  if (format == ExportFormat.csv) {
    final path = '${dir.path}$sep$stem.csv';
    await File(path).writeAsString(doc.toCsv());
    file = XFile(path, mimeType: 'text/csv', name: '$stem.csv');
  } else {
    final path = '${dir.path}$sep$stem.pdf';
    await File(path).writeAsBytes(await buildPdfBytes(doc, now: stamp));
    file = XFile(path, mimeType: 'application/pdf', name: '$stem.pdf');
  }

  return SharePlus.instance.share(ShareParams(
    files: [file],
    subject: subject,
    title: doc.title,
    sharePositionOrigin: origin,
  ));
}

/// Lays [doc] out on A4.
///
/// Built on the standard Helvetica face, so the file embeds no font and stays
/// small enough to send over a phone connection.
pw.Document _document(ExportDoc doc, DateTime stamp) {
  final pdf = pw.Document(title: doc.title);

  // Numeric columns are right-aligned so figures line up on their last digit —
  // the only way a column of money can be read down.
  final alignments = <int, pw.AlignmentGeometry>{};
  for (var i = 0; i < doc.columns.length; i++) {
    final numeric = doc.rows.isNotEmpty &&
        doc.rows.every((r) => i < r.length && r[i].isNumeric);
    alignments[i] =
        numeric ? pw.Alignment.centerRight : pw.Alignment.centerLeft;
  }

  pdf.addPage(
    pw.MultiPage(
      pageFormat: PdfPageFormat.a4,
      margin: const pw.EdgeInsets.fromLTRB(28, 32, 28, 36),
      header: (context) => context.pageNumber == 1
          ? pw.SizedBox()
          : pw.Padding(
              padding: const pw.EdgeInsets.only(bottom: 8),
              child: pw.Text(
                asciiForPdf(doc.title),
                style:
                    const pw.TextStyle(fontSize: 9, color: PdfColors.grey600),
              ),
            ),
      footer: (context) => pw.Row(
        mainAxisAlignment: pw.MainAxisAlignment.spaceBetween,
        children: [
          pw.Text('OrbixERP',
              style: const pw.TextStyle(fontSize: 8, color: PdfColors.grey600)),
          pw.Text('Page ${context.pageNumber} of ${context.pagesCount}',
              style: const pw.TextStyle(fontSize: 8, color: PdfColors.grey600)),
        ],
      ),
      build: (context) => [
        pw.Text(asciiForPdf(doc.title),
            style: pw.TextStyle(fontSize: 17, fontWeight: pw.FontWeight.bold)),
        if (doc.subtitle != null) ...[
          pw.SizedBox(height: 3),
          pw.Text(
            asciiForPdf(doc.subtitle!),
            style: const pw.TextStyle(fontSize: 11, color: PdfColors.grey700),
          ),
        ],
        if (doc.meta.isNotEmpty) ...[
          pw.SizedBox(height: 6),
          for (final line in doc.meta)
            pw.Text(
              asciiForPdf(line),
              style: const pw.TextStyle(fontSize: 9, color: PdfColors.grey700),
            ),
        ],
        pw.SizedBox(height: 14),
        if (doc.rows.isEmpty)
          pw.Text(
            'Nothing to report for this selection.',
            style: const pw.TextStyle(fontSize: 10, color: PdfColors.grey700),
          )
        else
          pw.TableHelper.fromTextArray(
            headers: doc.columns.map(asciiForPdf).toList(),
            data: [
              for (final row in doc.rows)
                row.map((c) => asciiForPdf(c.display)).toList(),
            ],
            headerStyle: pw.TextStyle(
              fontSize: 9,
              fontWeight: pw.FontWeight.bold,
              color: PdfColors.white,
            ),
            headerDecoration:
                const pw.BoxDecoration(color: PdfColors.blueGrey800),
            headerAlignments: alignments,
            cellStyle: const pw.TextStyle(fontSize: 9),
            cellAlignments: alignments,
            cellPadding:
                const pw.EdgeInsets.symmetric(horizontal: 6, vertical: 4),
            oddRowDecoration: const pw.BoxDecoration(color: PdfColors.grey100),
            border: pw.TableBorder.all(color: PdfColors.grey400, width: 0.4),
          ),
        if (doc.totals.isNotEmpty) ...[
          pw.SizedBox(height: 14),
          pw.Container(
            padding: const pw.EdgeInsets.all(10),
            decoration: const pw.BoxDecoration(color: PdfColors.grey200),
            child: pw.Column(
              children: [
                for (final t in doc.totals)
                  pw.Padding(
                    padding: const pw.EdgeInsets.symmetric(vertical: 2),
                    child: pw.Row(
                      mainAxisAlignment: pw.MainAxisAlignment.spaceBetween,
                      children: [
                        pw.Text(asciiForPdf(t.label),
                            style: const pw.TextStyle(fontSize: 10)),
                        pw.Text(
                          asciiForPdf(t.value.display),
                          style: pw.TextStyle(
                              fontSize: 10, fontWeight: pw.FontWeight.bold),
                        ),
                      ],
                    ),
                  ),
              ],
            ),
          ),
        ],
        if (doc.footnote != null) ...[
          pw.SizedBox(height: 12),
          pw.Text(
            asciiForPdf(doc.footnote!),
            style: const pw.TextStyle(fontSize: 9, color: PdfColors.grey700),
          ),
        ],
        pw.SizedBox(height: 16),
        pw.Text('Generated ${_stamp(stamp)}',
            style: const pw.TextStyle(fontSize: 8, color: PdfColors.grey600)),
      ],
    ),
  );

  return pdf;
}

String _stamp(DateTime d) => '${d.day.toString().padLeft(2, '0')}/'
    '${d.month.toString().padLeft(2, '0')}/'
    '${d.year} '
    '${d.hour.toString().padLeft(2, '0')}:'
    '${d.minute.toString().padLeft(2, '0')}';

/// Folds the typographic characters the app uses on screen down to ASCII.
///
/// The built-in Helvetica face covers Latin-1 only, and an em dash or a
/// multiplication sign outside it draws as an empty box — which is how a
/// perfectly correct report starts looking broken.
String asciiForPdf(String value) => value
    .replaceAll('—', '-')
    .replaceAll('–', '-')
    .replaceAll('×', 'x')
    .replaceAll('·', '-')
    .replaceAll('‘', "'")
    .replaceAll('’', "'")
    .replaceAll('“', '"')
    .replaceAll('”', '"')
    .replaceAll('…', '...');
