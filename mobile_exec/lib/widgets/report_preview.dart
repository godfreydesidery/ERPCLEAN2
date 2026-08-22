import 'package:flutter/material.dart';
import 'package:printing/printing.dart';

import '../app/theme.dart';
import '../core/export/report_doc.dart';
import '../core/export/report_share.dart';

/// Opens [doc] as it will be exported, without sending it anywhere.
Future<void> openReportPreview(
  BuildContext context,
  ExportDoc doc,
  ExportFormat format,
) {
  return Navigator.of(context).push(MaterialPageRoute<void>(
    builder: (_) => ReportPreviewScreen(doc: doc, format: format),
  ));
}

/// The exported report, on screen, before it goes anywhere.
///
/// Until now the only way to see what an export contained was to send it and
/// open it at the other end — which is no way to check a figure before it
/// reaches a supplier or a bank. This shows the same bytes that would be
/// attached: the real rendered PDF, or the exact CSV a spreadsheet will read.
class ReportPreviewScreen extends StatelessWidget {
  const ReportPreviewScreen({
    super.key,
    required this.doc,
    required this.format,
  });

  final ExportDoc doc;
  final ExportFormat format;

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: HqColors.bg,
      appBar: AppBar(
        title: Text(doc.title, style: HqText.title),
        actions: [
          IconButton(
            tooltip: 'Share',
            icon: const Icon(Icons.ios_share_rounded),
            onPressed: () => shareDoc(doc, format),
          ),
          const SizedBox(width: 6),
        ],
      ),
      body: format == ExportFormat.pdf
          ? PdfPreview(
              build: (_) => buildPdfBytes(doc),
              pdfFileName: '${doc.fileStem(DateTime.now())}.pdf',
              // The page format is decided by the report, not the reader, and
              // the debug overlay has no place in a client's hands.
              canChangePageFormat: false,
              canChangeOrientation: false,
              canDebug: false,
              allowPrinting: true,
              allowSharing: true,
              loadingWidget: const Center(
                child: CircularProgressIndicator(color: HqColors.brand),
              ),
            )
          : _TextPreview(doc: doc, format: format),
    );
  }
}

/// CSV and the chat summary, shown exactly as they will be sent.
///
/// Monospace and horizontally scrollable: a CSV read in a proportional font
/// with its long lines wrapped is not the file anyone is checking.
class _TextPreview extends StatelessWidget {
  const _TextPreview({required this.doc, required this.format});

  final ExportDoc doc;
  final ExportFormat format;

  @override
  Widget build(BuildContext context) {
    final body =
        format == ExportFormat.csv ? doc.toCsv() : doc.toPlainText();

    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        Container(
          width: double.infinity,
          color: HqColors.brandSoft,
          padding: const EdgeInsets.fromLTRB(20, 12, 20, 12),
          child: Text(
            format == ExportFormat.csv
                ? 'This is the file a spreadsheet will open.'
                : 'This is the message that will be sent.',
            style: const TextStyle(fontSize: 13, color: HqColors.brandD),
          ),
        ),
        Expanded(
          child: SingleChildScrollView(
            padding: const EdgeInsets.all(16),
            child: SingleChildScrollView(
              scrollDirection: Axis.horizontal,
              child: SelectableText(
                body,
                style: const TextStyle(
                  fontFamily: 'monospace',
                  fontSize: 12.5,
                  height: 1.5,
                  color: HqColors.ink,
                ),
              ),
            ),
          ),
        ),
      ],
    );
  }
}
