import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_riverpod/misc.dart' show Override;
import 'dart:io' show Directory, Platform;

import 'package:hive_ce_flutter/hive_flutter.dart';
import 'package:path_provider/path_provider.dart';

import 'app/app.dart';
import 'offline/blob_store.dart';
import 'offline/offline_catalog.dart';
import 'offline/offline_providers.dart';
import 'offline/offline_queue.dart';
import 'offline/offline_sign_in.dart';
import 'offline/secret_cipher.dart';

Future<void> main() async {
  WidgetsFlutterBinding.ensureInitialized();
  runApp(ProviderScope(
    overrides: await _offlineStorage(),
    child: const OrbixPosApp(),
  ));
}

/// Opens the on-device stores for offline POS (ERP ADR-0068): the catalogue
/// cache, and the queue of offline sales sealed with the device key store.
///
/// If the disk store cannot be opened the app still starts — online selling
/// needs none of it — and the defaults apply: an in-memory catalogue and a
/// queue that refuses to store anything, so offline sales are refused rather
/// than kept somewhere they could be lost.
Future<List<Override>> _offlineStorage() async {
  try {
    // The app-support folder (%APPDATA%\… on Windows, the app's private files on
    // Android) — never Documents, which a Windows PC may sync to OneDrive.
    final base = await getApplicationSupportDirectory();
    final dir = Directory('${base.path}${Platform.pathSeparator}offline');
    await dir.create(recursive: true);
    Hive.init(dir.path);
    final catalog =
        OfflineCatalog(await HiveBlobStore.open('offline_catalog', compactEachWrite: true));
    final cipher = platformCipher();
    final queue = OfflineQueue(await HiveBlobStore.open('offline_queue'), cipher);
    final users = OfflineSignInStore(await HiveBlobStore.open('offline_users'), cipher);
    return [
      offlineCatalogProvider.overrideWithValue(catalog),
      offlineQueueProvider.overrideWithValue(queue),
      offlineSignInStoreProvider.overrideWithValue(users),
    ];
  } catch (e) {
    debugPrint('POS offline storage unavailable: $e');
    return const [];
  }
}
