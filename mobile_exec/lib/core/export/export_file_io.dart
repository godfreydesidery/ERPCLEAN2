import 'dart:io';

import 'package:path_provider/path_provider.dart';
import 'package:share_plus/share_plus.dart';

/// Phones and desktop: a real file in the app's cache, as share sheets expect.
Future<XFile> writeExportFile({
  required String stem,
  required String extension,
  required String mimeType,
  required List<int> bytes,
}) async {
  final dir = await getTemporaryDirectory();
  final name = '$stem.$extension';
  final path = '${dir.path}${Platform.pathSeparator}$name';
  await File(path).writeAsBytes(bytes);
  return XFile(path, mimeType: mimeType, name: name);
}
