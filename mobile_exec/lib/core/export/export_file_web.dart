import 'dart:typed_data';

import 'package:share_plus/share_plus.dart';

/// Browser: no file system - the bytes travel in memory.
Future<XFile> writeExportFile({
  required String stem,
  required String extension,
  required String mimeType,
  required List<int> bytes,
}) async {
  final name = '$stem.$extension';
  return XFile.fromData(
    Uint8List.fromList(bytes),
    mimeType: mimeType,
    name: name,
  );
}
