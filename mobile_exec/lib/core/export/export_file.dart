import 'package:share_plus/share_plus.dart';

import 'export_file_io.dart'
    if (dart.library.js_interop) 'export_file_web.dart'
    as impl;

/// The exported report as a shareable file.
///
/// Phones (dart:io): written to the app's cache directory, as before.
/// Browser (the PWA): kept in memory - there is no file system - and handed to
/// the Web Share API, or downloaded where the browser cannot share files.
Future<XFile> writeExportFile({
  required String stem,
  required String extension,
  required String mimeType,
  required List<int> bytes,
}) => impl.writeExportFile(
  stem: stem,
  extension: extension,
  mimeType: mimeType,
  bytes: bytes,
);
