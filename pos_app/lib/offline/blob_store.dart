import 'dart:typed_data';

import 'package:hive_ce/hive.dart';

/// Where the offline data lives on the device: a small key → bytes store.
///
/// Behind an interface so the queue and the catalogue cache can be tested
/// without a disk, and so the storage choice (hive_ce today) stays in one place.
abstract class BlobStore {
  Future<Uint8List?> read(String key);

  /// Writes [bytes] and returns only once they are durable — the offline queue
  /// relies on that before it lets a receipt print.
  Future<void> write(String key, Uint8List bytes);

  Future<void> delete(String key);
}

/// hive_ce box storage. Hive appends to its log and flushes, so a power cut
/// mid-write leaves the previous value readable rather than a torn file.
///
/// The box must be opened through [HiveBlobStore.open] after `Hive.init` /
/// `Hive.initFlutter` (main.dart does it at start-up).
class HiveBlobStore implements BlobStore {
  HiveBlobStore._(this._box, this._compact);
  final Box<Uint8List> _box;
  final bool _compact;

  /// [compactEachWrite]: rewrite the file after each write. For the catalogue,
  /// whose few-MB value is replaced every refresh — without it Hive keeps up to
  /// ~60 old copies before compacting by itself.
  static Future<HiveBlobStore> open(String boxName, {bool compactEachWrite = false}) async {
    final box = Hive.isBoxOpen(boxName)
        ? Hive.box<Uint8List>(boxName)
        : await Hive.openBox<Uint8List>(boxName);
    return HiveBlobStore._(box, compactEachWrite);
  }

  @override
  Future<Uint8List?> read(String key) async => _box.get(key);

  @override
  Future<void> write(String key, Uint8List bytes) async {
    await _box.put(key, bytes);
    await _box.flush();
    if (_compact) await _box.compact();
  }

  @override
  Future<void> delete(String key) async {
    await _box.delete(key);
    await _box.flush();
  }
}

/// In-memory store for tests (and a safe fallback when the disk store cannot be
/// opened — the queue then refuses offline sales, see `OfflineQueue.durable`).
class MemoryBlobStore implements BlobStore {
  final Map<String, Uint8List> data = {};

  @override
  Future<Uint8List?> read(String key) async => data[key];

  @override
  Future<void> write(String key, Uint8List bytes) async =>
      data[key] = Uint8List.fromList(bytes);

  @override
  Future<void> delete(String key) async => data.remove(key);
}
