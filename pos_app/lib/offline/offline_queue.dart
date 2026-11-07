import 'dart:convert';
import 'dart:math';

import 'package:flutter/foundation.dart';

import '../core/json.dart';
import 'blob_store.dart';
import 'offline_models.dart';
import 'secret_cipher.dart';

/// How long a till may keep selling offline, counted from its first unsynced
/// offline sale (ADR-0068 D-3). After that it refuses new sales until synced.
const Duration kOfflineSellingLimit = Duration(hours: 24);

/// True when the till has sold offline for [kOfflineSellingLimit] without
/// syncing, so a new offline sale must be refused.
bool offlineCapReached(DateTime? firstUnsyncedSoldAt, DateTime now) =>
    firstUnsyncedSoldAt != null &&
    now.toUtc().difference(firstUnsyncedSoldAt.toUtc()) >= kOfflineSellingLimit;

/// Server caps on one sync call (`OfflineSyncRequest`).
const int kSyncMaxSessions = 20;
const int kSyncMaxSales = 200;
const int kSyncMaxPeriods = 50;

/// Formats a provisional offline number: `OFF-<tag>-<6-digit sequence>`.
///
/// [tag] is the till's code plus this device's two-character tag (`T2K7`), so
/// two devices that have both run till T2 — or one device reinstalled — never
/// print the same number: the server keeps offline numbers unique per company,
/// and a repeat would turn a good sale into a held one.
String formatOfflineNumber(String tag, int seq) =>
    'OFF-$tag-${seq.toString().padLeft(6, '0')}';

/// The till part of the offline number: the till code (letters and digits only,
/// at most 12) followed by [deviceTag]. A till without a usable code falls back
/// to a short, stable tag derived from its uid.
String offlineTillTag(String tillCode, String tillUid, String deviceTag) {
  var code = tillCode.toUpperCase().replaceAll(RegExp(r'[^A-Z0-9]'), '');
  if (code.isEmpty) {
    final uid = tillUid.toUpperCase().replaceAll(RegExp(r'[^A-Z0-9]'), '');
    code = uid.length <= 4 ? (uid.isEmpty ? 'TILL' : uid) : uid.substring(uid.length - 4);
  }
  if (code.length > 12) code = code.substring(0, 12);
  return '$code$deviceTag';
}

/// Everything the queue holds. Immutable; the queue swaps it whole.
class QueueSnapshot {
  const QueueSnapshot({
    this.deviceTag = '',
    this.sequences = const {},
    this.sales = const [],
    this.shifts = const [],
    this.periods = const [],
    this.held = const [],
    this.lastSyncAt,
    this.lastSyncError,
  });

  /// Two characters, random per installation (see [formatOfflineNumber]).
  final String deviceTag;

  /// Highest offline sequence used, per till tag. Only ever goes up.
  final Map<String, int> sequences;
  final List<OfflineSale> sales;
  final List<OfflineShift> shifts;
  final List<OfflinePeriod> periods;
  final List<HeldSale> held;
  final DateTime? lastSyncAt;
  final String? lastSyncError;

  int get waiting => sales.length;

  /// The oldest sale still waiting — the 24-hour limit counts from it.
  DateTime? get firstUnsyncedSoldAt => sales.isEmpty
      ? null
      : sales.map((s) => s.soldAt).reduce((a, b) => a.isBefore(b) ? a : b);

  /// True when there is anything at all to send.
  bool get hasWork =>
      sales.isNotEmpty || shifts.any((s) => !s.adopted) || periods.isNotEmpty;

  QueueSnapshot copyWith({
    String? deviceTag,
    Map<String, int>? sequences,
    List<OfflineSale>? sales,
    List<OfflineShift>? shifts,
    List<OfflinePeriod>? periods,
    List<HeldSale>? held,
    DateTime? lastSyncAt,
    String? lastSyncError,
    bool clearSyncError = false,
  }) =>
      QueueSnapshot(
        deviceTag: deviceTag ?? this.deviceTag,
        sequences: sequences ?? this.sequences,
        sales: sales ?? this.sales,
        shifts: shifts ?? this.shifts,
        periods: periods ?? this.periods,
        held: held ?? this.held,
        lastSyncAt: lastSyncAt ?? this.lastSyncAt,
        lastSyncError: clearSyncError ? null : (lastSyncError ?? this.lastSyncError),
      );

  Map<String, dynamic> toJson() => {
        'v': 1,
        'deviceTag': deviceTag,
        'sequences': sequences,
        'sales': [for (final s in sales) s.toJson()],
        'shifts': [for (final s in shifts) s.toJson()],
        'periods': [for (final p in periods) p.toJson()],
        'held': [for (final h in held) h.toJson()],
        'lastSyncAt': lastSyncAt?.toUtc().toIso8601String(),
        'lastSyncError': lastSyncError,
      };

  factory QueueSnapshot.fromJson(Map<String, dynamic> j) => QueueSnapshot(
        deviceTag: asStrOr(j['deviceTag']),
        sequences: {
          for (final e in asMap(j['sequences']).entries) e.key: asIntOr(e.value),
        },
        sales: asList(j['sales'], OfflineSale.fromJson),
        shifts: asList(j['shifts'], OfflineShift.fromJson),
        periods: asList(j['periods'], OfflinePeriod.fromJson),
        held: asList(j['held'], HeldSale.fromJson),
        lastSyncAt: asDate(j['lastSyncAt'])?.toUtc(),
        lastSyncError: asStr(j['lastSyncError']),
      );
}

/// One sync call's worth of work, for one till.
class SyncBatch {
  const SyncBatch({
    required this.tillUid,
    required this.branchUid,
    required this.shifts,
    required this.sales,
    required this.periods,
  });

  final String tillUid;
  final String branchUid;
  final List<OfflineShift> shifts;
  final List<OfflineSale> sales;
  final List<OfflinePeriod> periods;

  bool get isEmpty => shifts.isEmpty && sales.isEmpty && periods.isEmpty;

  Map<String, dynamic> toWire() => {
        'tillUid': tillUid,
        'sessions': [for (final s in shifts) s.toWire()],
        'sales': [for (final s in sales) s.toWire()],
        'periods': [for (final p in periods) p.toWire()],
      };
}

/// What applying a sync answer did — for the sync sheet and the receipt journal.
class SyncApplied {
  const SyncApplied({
    this.posted = const [],
    this.held = const [],
    this.kept = 0,
    this.adopted = const [],
  });

  /// POSTED and ALREADY_POSTED results (settled, on the books).
  final List<SyncSaleResult> posted;

  /// HELD results (settled on the till, waiting for a manager on the server).
  final List<SyncSaleResult> held;

  /// Sales sent but not answered: still queued, sent again next time.
  final int kept;

  /// Shifts opened offline that the server now knows by a real session.
  final List<OfflineShift> adopted;
}

/// The durable, encrypted queue of offline work (ADR-0068 D-3, D-4): unsynced
/// sales, shifts opened offline, offline periods, and held-sale notices.
///
/// ## Durability before paper
///
/// [addSale] returns only after the sale is sealed by the device key store and
/// written to disk. The register prints the receipt after that — never before —
/// so a receipt in a customer's hand always has its sale on the device.
///
/// ## Encrypted at rest (owner ruling)
///
/// The whole snapshot is sealed with [SecretCipher] (DPAPI / Android Keystore).
/// If the stored blob cannot be opened — another Windows user, a tampered file —
/// the queue is [readable] = false and refuses all writes: overwriting it would
/// destroy sales that are still unsynced.
///
/// Writes are serialised, so two quick sales can never interleave and lose one.
class OfflineQueue {
  OfflineQueue(this._store, this._cipher, {Random? random})
      : _random = random ?? Random.secure();

  final BlobStore _store;
  final SecretCipher _cipher;
  final Random _random;

  static const _key = 'queue';

  QueueSnapshot _snap = const QueueSnapshot();
  bool _loaded = false;
  bool _readable = true;
  Future<void> _tail = Future.value();

  QueueSnapshot get snapshot => _snap;

  /// False when the stored queue exists but cannot be opened on this device.
  bool get readable => _readable;
  String get cipherName => _cipher.name;

  /// Runs [op] after every earlier write has finished.
  Future<T> _serial<T>(Future<T> Function() op) {
    final result = _tail.then((_) => op());
    _tail = result.then((_) {}, onError: (_) {});
    return result;
  }

  /// Reads the stored queue. Safe to call more than once.
  Future<void> load() => _serial(_ensureLoaded);

  String _newDeviceTag() {
    const alphabet = 'ABCDEFGHJKLMNPQRSTUVWXYZ23456789';
    return String.fromCharCodes(
        List.generate(2, (_) => alphabet.codeUnitAt(_random.nextInt(alphabet.length))));
  }

  Future<void> _persist(QueueSnapshot next) async {
    if (!_readable) {
      throw SecretCipherException(
          'The offline sales stored on this device cannot be opened, so nothing new is '
          'written over them. Ask your supervisor.');
    }
    final plain = Uint8List.fromList(utf8.encode(jsonEncode(next.toJson())));
    final sealed = await _cipher.seal(plain);
    await _store.write(_key, sealed);
    _snap = next;
  }

  /// Loads the snapshot on first use. Runs inside [_serial] only.
  Future<void> _ensureLoaded() async {
    if (_loaded) return;
    final raw = await _store.read(_key);
    if (raw == null || raw.isEmpty) {
      _snap = QueueSnapshot(deviceTag: _newDeviceTag());
    } else {
      try {
        final plain = await _cipher.open(raw);
        _snap = QueueSnapshot.fromJson(
            (jsonDecode(utf8.decode(plain)) as Map).cast<String, dynamic>());
        if (_snap.deviceTag.isEmpty) _snap = _snap.copyWith(deviceTag: _newDeviceTag());
      } catch (e) {
        debugPrint('POS offline queue could not be opened: $e');
        _readable = false;
      }
    }
    _loaded = true;
  }

  // ------------------------------------------------------------------ sales

  /// Numbers, seals and stores a new offline sale in ONE durable write, and
  /// returns it. [build] receives the offline number to put on the sale.
  ///
  /// The sequence is taken from the stored snapshot and only ever increases, so
  /// a number is never handed out twice on this device — not across restarts,
  /// and not when two sales are rung back to back. Throws (and stores nothing)
  /// when the device cannot store it securely; the caller must then refuse the
  /// sale, before any receipt is printed.
  Future<OfflineSale> addSale({
    required String tillCode,
    required String tillUid,
    required OfflineSale Function(String offlineNumber) build,
  }) =>
      _serial(() async {
        await _ensureLoaded();
        final tag = offlineTillTag(tillCode, tillUid, _snap.deviceTag);
        final seq = (_snap.sequences[tag] ?? 0) + 1;
        final sale = build(formatOfflineNumber(tag, seq));
        final periods = [
          for (final p in _snap.periods)
            p.clientKey == sale.periodKey ? p.copyWith(salesCount: p.salesCount + 1) : p,
        ];
        await _persist(_snap.copyWith(
          sequences: {..._snap.sequences, tag: seq},
          sales: [..._snap.sales, sale],
          periods: periods,
        ));
        return sale;
      });

  /// True when [sessionUid] (a server shift) or [clientKey] (a shift opened
  /// offline) still has sales on this device that the server has not got.
  /// Closing that shift must wait (D-4): its Z-report would be short.
  bool hasUnsyncedFor({String? sessionUid, String? clientKey}) {
    String? adoptedUid;
    String? keyOfAdopted;
    for (final s in _snap.shifts) {
      if (clientKey != null && s.clientKey == clientKey) adoptedUid = s.serverSessionUid;
      if (sessionUid != null && s.serverSessionUid == sessionUid) keyOfAdopted = s.clientKey;
    }
    return _snap.sales.any((s) =>
        (sessionUid != null && s.sessionUid == sessionUid) ||
        (adoptedUid != null && s.sessionUid == adoptedUid) ||
        (clientKey != null && s.sessionClientKey == clientKey) ||
        (keyOfAdopted != null && s.sessionClientKey == keyOfAdopted));
  }

  // ------------------------------------------------------------ shifts/periods

  Future<void> addShift(OfflineShift shift) => _serial(() async {
        await _ensureLoaded();
        await _persist(_snap.copyWith(shifts: [..._snap.shifts, shift]));
      });

  Future<void> startPeriod(OfflinePeriod period) => _serial(() async {
        await _ensureLoaded();
        await _persist(_snap.copyWith(periods: [..._snap.periods, period]));
      });

  /// Marks the period ended (the till is back online). Ended periods are sent
  /// with the next sync and dropped once the server has recorded them.
  Future<void> endPeriod(String clientKey, DateTime at) => _serial(() async {
        await _ensureLoaded();
        await _persist(_snap.copyWith(periods: [
          for (final p in _snap.periods)
            p.clientKey == clientKey && !p.ended ? p.copyWith(endedAt: at.toUtc()) : p,
        ]));
      });

  /// Attaches a period to the shift it is in, once a shift is opened offline.
  Future<void> attachPeriodToShift(String periodKey, {String? sessionUid, String? clientKey}) =>
      _serial(() async {
        await _ensureLoaded();
        await _persist(_snap.copyWith(periods: [
          for (final p in _snap.periods)
            p.clientKey == periodKey
                ? p.copyWith(sessionUid: sessionUid, sessionClientKey: clientKey)
                : p,
        ]));
      });

  Future<void> dismissHeld(String offlineNumber) => _serial(() async {
        await _ensureLoaded();
        await _persist(_snap.copyWith(
            held: [for (final h in _snap.held) if (h.offlineNumber != offlineNumber) h]));
      });

  Future<void> recordSyncError(String message) => _serial(() async {
        await _ensureLoaded();
        if (!_readable) return;
        await _persist(_snap.copyWith(lastSyncError: message));
      });

  // ------------------------------------------------------------------ sync

  /// The next batch to send from [branchUid], or null when nothing waits there.
  ///
  /// One till per call (the endpoint takes one `tillUid`). Shifts opened offline
  /// go in the same call as their sales; a sale whose offline shift could not be
  /// included waits for the next call, so it is never sent without its shift.
  SyncBatch? nextBatch({required String branchUid}) {
    final tills = <String>[
      ..._snap.sales.where((s) => s.branchUid == branchUid).map((s) => s.tillUid),
      ..._snap.shifts.where((s) => !s.adopted && s.branchUid == branchUid).map((s) => s.tillUid),
      ..._snap.periods.where((p) => p.branchUid == branchUid).map((p) => p.tillUid),
    ];
    if (tills.isEmpty) return null;
    final till = tills.first;

    final shifts = _snap.shifts
        .where((s) => s.tillUid == till && !s.adopted)
        .take(kSyncMaxSessions)
        .toList();
    final sentKeys = {for (final s in shifts) s.clientKey};
    // Held back only when its offline shift is still waiting but did not fit in
    // this call; a shift the server already has (adopted) or that is no longer
    // kept (it could not be created — the server then holds the sale) is no
    // reason to wait.
    final waitingKeys = {
      for (final s in _snap.shifts)
        if (!s.adopted && !sentKeys.contains(s.clientKey)) s.clientKey
    };

    final sales = (_snap.sales.where((s) => s.tillUid == till).toList()
          ..sort((a, b) => a.soldAt.compareTo(b.soldAt)))
        .where((s) => s.sessionUid != null || !waitingKeys.contains(s.sessionClientKey))
        .take(kSyncMaxSales)
        .toList();
    final periods =
        _snap.periods.where((p) => p.tillUid == till).take(kSyncMaxPeriods).toList();
    final batch = SyncBatch(
        tillUid: till,
        branchUid: branchUid,
        shifts: shifts,
        sales: [for (final s in sales) _withAdoptedSession(s)],
        periods: [for (final p in periods) _periodWithAdoptedSession(p)]);
    return batch.isEmpty ? null : batch;
  }

  OfflineSale _withAdoptedSession(OfflineSale s) {
    if (s.sessionUid != null || s.sessionClientKey == null) return s;
    for (final sh in _snap.shifts) {
      if (sh.adopted && sh.clientKey == s.sessionClientKey) {
        return s.copyWith(sessionUid: sh.serverSessionUid, clearClientKey: true);
      }
    }
    return s;
  }

  OfflinePeriod _periodWithAdoptedSession(OfflinePeriod p) {
    if (p.sessionUid != null || p.sessionClientKey == null) return p;
    for (final sh in _snap.shifts) {
      if (sh.adopted && sh.clientKey == p.sessionClientKey) {
        return p.copyWith(sessionUid: sh.serverSessionUid, clearClientKey: true);
      }
    }
    return p;
  }

  /// Applies the server's answer to [sent], exactly as the contract says:
  ///
  /// * every sale the answer lists — POSTED, ALREADY_POSTED or HELD — leaves the
  ///   queue (HELD ones are remembered as a notice for the cashier);
  /// * every sale the answer omits stays, and is sent again next time (it was
  ///   in flight on the server);
  /// * a shift opened offline that the server answered is adopted: its sales
  ///   and periods now carry the server's session uid;
  /// * ended periods are dropped once the server says it recorded them all.
  Future<SyncApplied> applyAnswer(SyncBatch sent, SyncAnswer answer, {DateTime? now}) =>
      _serial(() async {
        await _ensureLoaded();
        final at = (now ?? DateTime.now()).toUtc();

        // Shifts first, so the sales left behind are rewritten to the real session.
        final adopted = <OfflineShift>[];
        var shifts = [..._snap.shifts];
        for (final s in answer.sessions) {
          if (s.sessionUid.isEmpty) continue;
          final i = shifts.indexWhere((x) => x.clientKey == s.clientKey);
          if (i < 0) continue;
          shifts[i] = shifts[i].adopt(s.sessionUid, s.sessionNumber);
          adopted.add(shifts[i]);
        }
        final uidByKey = {for (final s in shifts) if (s.adopted) s.clientKey: s.serverSessionUid!};

        final settled = <String, SyncSaleResult>{};
        for (final r in answer.sales) {
          if (!r.settles) continue;
          if (r.idempotencyKey.isNotEmpty) settled[r.idempotencyKey] = r;
        }
        final byNumber = {
          for (final r in answer.sales)
            if (r.settles && r.idempotencyKey.isEmpty) r.offlineNumber: r
        };
        final sentKeys = {for (final s in sent.sales) s.idempotencyKey};

        final posted = <SyncSaleResult>[];
        final heldResults = <SyncSaleResult>[];
        final held = [..._snap.held];
        final remaining = <OfflineSale>[];
        var kept = 0;
        for (final s in _snap.sales) {
          final r = settled[s.idempotencyKey] ??
              (sentKeys.contains(s.idempotencyKey) ? byNumber[s.offlineNumber] : null);
          if (r == null) {
            if (sentKeys.contains(s.idempotencyKey)) kept++;
            final key = s.sessionClientKey;
            remaining.add(s.sessionUid == null && key != null && uidByKey.containsKey(key)
                ? s.copyWith(sessionUid: uidByKey[key], clearClientKey: true)
                : s);
            continue;
          }
          if (r.isHeld) {
            heldResults.add(r);
            if (!held.any((h) => h.offlineNumber == s.offlineNumber)) {
              held.add(HeldSale(
                offlineNumber: s.offlineNumber,
                message: r.message ?? 'Held for a manager to review.',
                grossTotal: s.grossTotal,
                currency: s.currency,
                soldAt: s.soldAt,
                heldAt: at,
              ));
            }
          } else {
            posted.add(r);
          }
        }

        final recordedAll = answer.periodsRecorded >= sent.periods.length;
        final sentPeriodKeys = {for (final p in sent.periods) p.clientKey};
        final periods = <OfflinePeriod>[];
        for (final p in _snap.periods) {
          if (recordedAll && p.ended && sentPeriodKeys.contains(p.clientKey)) continue;
          final key = p.sessionClientKey;
          periods.add(p.sessionUid == null && key != null && uidByKey.containsKey(key)
              ? p.copyWith(sessionUid: uidByKey[key], clearClientKey: true)
              : p);
        }

        // An adopted shift is kept only while something still refers to it. So is
        // a shift the server was sent but did not create (its sales came back
        // HELD): once nothing refers to it, re-sending it would only fail again.
        final stillReferenced = {
          for (final s in remaining) ?s.sessionClientKey,
          for (final p in periods) ?p.sessionClientKey,
        };
        final sentShiftKeys = {for (final s in sent.shifts) s.clientKey};
        shifts = [
          for (final s in shifts)
            if (stillReferenced.contains(s.clientKey) ||
                (!s.adopted && !sentShiftKeys.contains(s.clientKey)))
              s
        ];

        await _persist(_snap.copyWith(
          sales: remaining,
          shifts: shifts,
          periods: periods,
          held: held,
          lastSyncAt: at,
          clearSyncError: true,
        ));
        return SyncApplied(posted: posted, held: heldResults, kept: kept, adopted: adopted);
      });
}
