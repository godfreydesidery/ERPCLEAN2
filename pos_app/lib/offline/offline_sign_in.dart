import 'dart:convert';
import 'dart:math';

import 'package:flutter/foundation.dart';

import '../core/json.dart';
import '../models/auth.dart';
import '../models/context.dart';
import 'blob_store.dart';
import 'pbkdf2.dart';
import 'secret_cipher.dart';

/// A verifier expires this long after that user's last ONLINE sign-in on this
/// till (owner ruling, ADR-0068 D-7).
const Duration kVerifierLifetime = Duration(days: 14);

/// Wrong offline tries before that user is locked out offline …
const int kOfflineMaxTries = 5;

/// … for this long — the same spirit as the server's own lockout.
const Duration kOfflineLockout = Duration(minutes: 15);

/// PBKDF2-HMAC-SHA256 rounds for a new verifier (~0.7 s on a till PC, run off
/// the UI thread). Each verifier keeps its own count, so raising this later
/// leaves existing verifiers working.
const int kVerifierIterations = 210000;

/// The permissions kept with an offline profile — only what selling offline
/// can use. Everything else needs the server anyway.
const Set<String> kOfflinePermissions = {
  Perms.saleCreate,
  Perms.sessionOpen,
  Perms.sessionClose,
  Perms.sessionView,
  Perms.offlineSwitch,
  Perms.productView,
  Perms.customerView,
  Perms.tillView,
};

/// A slow, salted hash of a password or PIN, derived ON the device. Never the
/// secret itself, and never the server's hash (the server's are never sent).
class Verifier {
  const Verifier(this.salt, this.iterations, this.hash);
  final Uint8List salt;
  final int iterations;
  final Uint8List hash;

  Map<String, dynamic> toJson() =>
      {'s': base64Encode(salt), 'i': iterations, 'h': base64Encode(hash)};

  factory Verifier.fromJson(Map<String, dynamic> j) => Verifier(
      base64Decode(asStrOr(j['s'])), asIntOr(j['i'], kVerifierIterations), base64Decode(asStrOr(j['h'])));
}

/// Someone who has signed in on this till while it was online, with what the
/// till needs to let them sell offline.
class KnownUser {
  const KnownUser({
    required this.username,
    required this.userUid,
    required this.cashierId,
    required this.displayName,
    required this.permissions,
    required this.context,
    required this.lastOnlineAt,
    this.password,
    this.pin,
    this.failedTries = 0,
    this.lockedUntil,
  });

  final String username;
  final String userUid;

  /// The user's id as the server's shifts carry it (the JWT subject).
  final String cashierId;
  final String displayName;
  final Set<String> permissions;

  /// The company and branch of their last online sign-in on this till.
  final PosContext context;
  final DateTime lastOnlineAt;
  final Verifier? password;
  final Verifier? pin;
  final int failedTries;
  final DateTime? lockedUntil;

  static String keyOf(String username) => username.trim().toLowerCase();

  bool expired(DateTime now) => !now.toUtc().isBefore(lastOnlineAt.toUtc().add(kVerifierLifetime));
  bool lockedAt(DateTime now) => lockedUntil != null && now.toUtc().isBefore(lockedUntil!.toUtc());

  /// Offline sign-in is for people who sell: ring a sale and run a shift.
  bool get canSellOffline =>
      permissions.contains(Perms.saleCreate) && permissions.contains(Perms.sessionOpen);

  Me toMe() => Me(
        uid: userUid,
        username: username,
        displayName: displayName,
        isRoot: false,
        activeCompanyUid: context.companyUid,
        activeBranchUid: context.branchUid,
        permissions: permissions,
      );

  KnownUser copyWith({
    Verifier? password,
    Verifier? pin,
    int? failedTries,
    DateTime? lockedUntil,
    bool clearLock = false,
  }) =>
      KnownUser(
        username: username,
        userUid: userUid,
        cashierId: cashierId,
        displayName: displayName,
        permissions: permissions,
        context: context,
        lastOnlineAt: lastOnlineAt,
        password: password ?? this.password,
        pin: pin ?? this.pin,
        failedTries: failedTries ?? this.failedTries,
        lockedUntil: clearLock ? null : (lockedUntil ?? this.lockedUntil),
      );

  Map<String, dynamic> toJson() => {
        'username': username,
        'userUid': userUid,
        'cashierId': cashierId,
        'displayName': displayName,
        'permissions': permissions.toList(),
        'context': context.toJson(),
        'lastOnlineAt': lastOnlineAt.toUtc().toIso8601String(),
        'password': password?.toJson(),
        'pin': pin?.toJson(),
        'failedTries': failedTries,
        'lockedUntil': lockedUntil?.toUtc().toIso8601String(),
      };

  factory KnownUser.fromJson(Map<String, dynamic> j) => KnownUser(
        username: asStrOr(j['username']),
        userUid: asStrOr(j['userUid']),
        cashierId: asStrOr(j['cashierId']),
        displayName: asStrOr(j['displayName']),
        permissions: {for (final p in (j['permissions'] as List? ?? const [])) p.toString()},
        context: PosContext.fromJson(asMap(j['context'])),
        lastOnlineAt: (asDate(j['lastOnlineAt']) ?? DateTime.fromMillisecondsSinceEpoch(0)).toUtc(),
        password: j['password'] is Map ? Verifier.fromJson(asMap(j['password'])) : null,
        pin: j['pin'] is Map ? Verifier.fromJson(asMap(j['pin'])) : null,
        failedTries: asIntOr(j['failedTries']),
        lockedUntil: asDate(j['lockedUntil'])?.toUtc(),
      );
}

enum OfflineSignInOutcome {
  /// Signed in offline.
  ok,

  /// Not known on this till (never signed in online here, expired, or no
  /// verifier for this kind of secret).
  unknown,

  /// Wrong password or PIN; [OfflineSignInResult.triesLeft] more before lockout.
  wrong,

  /// Too many wrong tries: locked offline until [OfflineSignInResult.lockedUntil].
  locked,

  /// The device's key store cannot open the stored verifiers.
  unavailable,
}

class OfflineSignInResult {
  const OfflineSignInResult(this.outcome, {this.user, this.triesLeft = 0, this.lockedUntil});
  final OfflineSignInOutcome outcome;
  final KnownUser? user;
  final int triesLeft;
  final DateTime? lockedUntil;

  /// What the cashier reads. Says nothing about whether a username exists
  /// beyond "not known on this till".
  String get message => switch (outcome) {
        OfflineSignInOutcome.ok => 'Signed in offline.',
        OfflineSignInOutcome.unknown =>
          'Offline sign-in is only for people who signed in on this till while it was '
              'online in the last 14 days.',
        OfflineSignInOutcome.wrong => triesLeft > 0
            ? 'That is not correct. $triesLeft more ${triesLeft == 1 ? 'try' : 'tries'} '
                'before offline sign-in is locked for 15 minutes.'
            : 'That is not correct.',
        OfflineSignInOutcome.locked =>
          'Too many wrong tries. Offline sign-in is locked for this user for 15 minutes.',
        OfflineSignInOutcome.unavailable =>
          'This device cannot open its offline sign-in data. Wait for the connection.',
      };
}

/// The offline sign-in verifiers and profiles of this till (ADR-0068 D-7),
/// encrypted at rest with the device key store (owner ruling) — the same
/// [SecretCipher] as the offline-sales queue.
///
/// Verifiers are written only by a successful ONLINE sign-in and deleted when
/// the server refuses that user's sign-in. Signing out never deletes one.
class OfflineSignInStore {
  OfflineSignInStore(this._store, this._cipher,
      {this.iterations = kVerifierIterations, Random? random})
      : _random = random ?? Random.secure();

  final BlobStore _store;
  final SecretCipher _cipher;
  final Random _random;

  /// PBKDF2 rounds for NEW verifiers (tests use a small number).
  final int iterations;

  static const _key = 'users';

  Uint8List _deviceSalt = Uint8List(0);
  Map<String, KnownUser> _users = {};
  bool _loaded = false;
  bool _readable = true;
  Future<void> _tail = Future.value();

  bool get readable => _readable;

  Future<T> _serial<T>(Future<T> Function() op) {
    final result = _tail.then((_) => op());
    _tail = result.then((_) {}, onError: (_) {});
    return result;
  }

  Uint8List _randomBytes(int n) =>
      Uint8List.fromList(List.generate(n, (_) => _random.nextInt(256)));

  Future<void> load() => _serial(_ensureLoaded);

  Future<void> _ensureLoaded() async {
    if (_loaded) return;
    final raw = await _store.read(_key);
    if (raw == null || raw.isEmpty) {
      _deviceSalt = _randomBytes(16);
    } else {
      try {
        final j = (jsonDecode(utf8.decode(await _cipher.open(raw))) as Map).cast<String, dynamic>();
        _deviceSalt = base64Decode(asStrOr(j['deviceSalt']));
        _users = {
          for (final u in asList(j['users'], KnownUser.fromJson)) KnownUser.keyOf(u.username): u,
        };
      } catch (e) {
        debugPrint('POS offline sign-in data could not be opened: $e');
        _readable = false;
      }
    }
    _loaded = true;
  }

  Future<void> _persist() async {
    if (!_readable) return;
    final plain = utf8.encode(jsonEncode({
      'v': 1,
      'deviceSalt': base64Encode(_deviceSalt),
      'users': [for (final u in _users.values) u.toJson()],
    }));
    await _store.write(_key, await _cipher.seal(Uint8List.fromList(plain)));
  }

  Future<Verifier> _derive(String secret) async {
    final userSalt = _randomBytes(16);
    final hash = await pbkdf2Sha256Async(
        utf8.encode(secret), [..._deviceSalt, ...userSalt], iterations);
    return Verifier(userSalt, iterations, hash);
  }

  Future<bool> _matches(Verifier v, String secret) async {
    final hash =
        await pbkdf2Sha256Async(utf8.encode(secret), [..._deviceSalt, ...v.salt], v.iterations);
    return constantTimeEquals(hash, v.hash);
  }

  /// The user as stored (null when unknown). For tests and the sign-in screen.
  KnownUser? user(String username) => _users[KnownUser.keyOf(username)];

  /// Users who may sign in offline on this till right now.
  List<KnownUser> knownUsers(DateTime now) =>
      [for (final u in _users.values) if (!u.expired(now) && u.canSellOffline) u];

  /// After a successful ONLINE sign-in: derive and keep this user's verifier
  /// for [secret] (their password, or [pin] for a PIN sign-in), refresh the
  /// profile, and reset any offline lockout. The secret itself is not kept.
  Future<void> rememberOnline({
    required String username,
    required String secret,
    required bool pin,
    required Me me,
    required String cashierId,
    required PosContext context,
    DateTime? now,
  }) =>
      _serial(() async {
        await _ensureLoaded();
        if (!_readable) return;
        final key = KnownUser.keyOf(username);
        final verifier = await _derive(secret);
        final prior = _users[key];
        // A verifier of another person who once had this username is dropped.
        final same = prior != null && prior.userUid == me.uid;
        _users[key] = KnownUser(
          username: username.trim(),
          userUid: me.uid,
          cashierId: cashierId,
          displayName: me.displayName,
          permissions: {
            for (final p in kOfflinePermissions)
              if (me.can(p)) p
          },
          context: context,
          lastOnlineAt: (now ?? DateTime.now()).toUtc(),
          password: pin ? (same ? prior.password : null) : verifier,
          pin: pin ? verifier : (same ? prior.pin : null),
        );
        await _persist();
      });

  /// The server refused this user's online sign-in (disabled, locked, removed —
  /// or a refusal the server deliberately does not explain): their offline
  /// sign-in goes at once.
  Future<void> forget(String username) => _serial(() async {
        await _ensureLoaded();
        if (_users.remove(KnownUser.keyOf(username)) != null) await _persist();
      });

  /// Checks [secret] offline. Counts wrong tries and locks the user out offline
  /// after [kOfflineMaxTries]. An expired verifier is deleted here.
  Future<OfflineSignInResult> verify(String username, String secret,
          {required bool pin, DateTime? now}) =>
      _serial(() async {
        await _ensureLoaded();
        if (!_readable) return const OfflineSignInResult(OfflineSignInOutcome.unavailable);
        final at = (now ?? DateTime.now()).toUtc();
        final key = KnownUser.keyOf(username);
        final u = _users[key];
        if (u == null) return const OfflineSignInResult(OfflineSignInOutcome.unknown);
        if (u.expired(at)) {
          _users.remove(key);
          await _safePersist();
          return const OfflineSignInResult(OfflineSignInOutcome.unknown);
        }
        if (u.lockedAt(at)) {
          return OfflineSignInResult(OfflineSignInOutcome.locked, lockedUntil: u.lockedUntil);
        }
        final v = pin ? u.pin : u.password;
        if (v == null) return const OfflineSignInResult(OfflineSignInOutcome.unknown);
        if (await _matches(v, secret)) {
          _users[key] = u.copyWith(failedTries: 0, clearLock: true);
          await _safePersist();
          return OfflineSignInResult(OfflineSignInOutcome.ok, user: _users[key]);
        }
        final tries = u.failedTries + 1;
        if (tries >= kOfflineMaxTries) {
          final until = at.add(kOfflineLockout);
          _users[key] = u.copyWith(failedTries: 0, lockedUntil: until);
          await _safePersist();
          return OfflineSignInResult(OfflineSignInOutcome.locked, lockedUntil: until);
        }
        _users[key] = u.copyWith(failedTries: tries, clearLock: true);
        await _safePersist();
        return OfflineSignInResult(OfflineSignInOutcome.wrong, triesLeft: kOfflineMaxTries - tries);
      });

  Future<void> _safePersist() async {
    try {
      await _persist();
    } catch (e) {
      debugPrint('POS offline sign-in data could not be saved: $e');
    }
  }
}
