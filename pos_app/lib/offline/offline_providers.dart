import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../state/providers.dart';
import 'blob_store.dart';
import 'offline_catalog.dart';
import 'offline_queue.dart';
import 'offline_sign_in.dart';
import 'reachability.dart';
import 'secret_cipher_stub.dart';

/// The offline catalogue cache (ADR-0068 D-2). `main.dart` overrides this with
/// the hive_ce-backed store; the default keeps it in memory, which is what the
/// widget tests want.
final offlineCatalogProvider =
    Provider<OfflineCatalog>((ref) => OfflineCatalog(MemoryBlobStore()));

/// The encrypted queue of offline sales. `main.dart` overrides it with the
/// hive_ce store and the device key store. The default can store nothing (its
/// cipher always refuses), so a build or test that has not wired a key store
/// refuses offline sales instead of keeping them in memory or in the clear.
final offlineQueueProvider = Provider<OfflineQueue>(
    (ref) => OfflineQueue(MemoryBlobStore(), const UnavailableCipher()));

/// Offline sign-in verifiers (ADR-0068 D-7), sealed with the device key store.
/// Like the queue, the default can store nothing: a build without a key store
/// offers no offline sign-in.
final offlineSignInStoreProvider = Provider<OfflineSignInStore>(
    (ref) => OfflineSignInStore(MemoryBlobStore(), const UnavailableCipher()));

/// The till's reachability check against the configured ERP.
final reachabilityProbeProvider = Provider<ReachabilityProbe>((ref) {
  final base = ref.watch(apiBaseProvider);
  return () => probeErp(base);
});
