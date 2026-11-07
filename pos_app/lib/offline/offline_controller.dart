import 'dart:async';

import 'package:flutter/foundation.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:uuid/uuid.dart';

import '../core/api/api_exception.dart';
import '../core/json.dart';
import '../models/enums.dart';
import '../models/pos.dart';
import '../services/catalog_service.dart';
import '../state/app_controller.dart';
import '../state/cart_controller.dart';
import '../state/providers.dart';
import '../state/receipt_journal.dart';
import 'offline_catalog.dart';
import 'offline_models.dart';
import 'offline_providers.dart';
import 'offline_queue.dart';
import 'offline_sale.dart';
import 'offline_sign_in.dart';
import 'secret_cipher.dart';

const _uuid = Uuid();

/// How long after the server answers again the till may still finish its
/// basket offline (ADR-0068 D-1). After that it returns online regardless.
const Duration kReturnGrace = Duration(minutes: 5);

/// How often the offline catalogue is refreshed while online (D-2).
const Duration kCatalogRefreshEvery = Duration(minutes: 15);

/// How often the till checks for the server while offline, or while it is
/// showing "no connection".
const Duration kProbeEvery = Duration(seconds: 15);

/// A till switched offline BY HAND (server reachable but slow) counts the server
/// as back only once it answers this fast. Otherwise the first probe after the
/// switch would find it "answering" and send the till straight back.
const Duration kFastAnswer = Duration(milliseconds: 1500);

/// Waits between failed sync attempts.
const List<Duration> kSyncBackoff = [
  Duration(seconds: 15),
  Duration(seconds: 30),
  Duration(minutes: 1),
  Duration(minutes: 2),
  Duration(minutes: 5),
];

/// The uid prefix of a shift opened offline. The register posts against a
/// shift uid; until the server creates the shift, the till knows it only by the
/// client key it chose.
const String kOfflineShiftPrefix = 'OFFLINE-';

/// The client key of [shift] when it was opened offline (and not yet adopted).
String? offlineShiftKeyOf(PosSession? shift) {
  final uid = shift?.uid;
  if (uid == null || !uid.startsWith(kOfflineShiftPrefix)) return null;
  return uid.substring(kOfflineShiftPrefix.length);
}

/// The answer to "may this till go offline now?" (ADR-0068 D-1).
enum OfflineSwitchDecision {
  /// Go ahead.
  allowed,

  /// The server answers, so this is a manual switch: a manager holding
  /// `POS.OFFLINE.SWITCH` must approve it with their password.
  needsManager,

  /// The admin has not allowed this till to sell offline.
  notAllowed,
}

/// The switch rules (D-1), on their own so they are tested on their own:
///
/// * a till that is not allowed offline never goes offline;
/// * the server truly unreachable — any signed-in cashier may switch;
/// * the server answering — only with a manager's approval.
OfflineSwitchDecision decideOfflineSwitch({
  required bool tillAllowed,
  required bool serverReachable,
  required bool managerApproved,
}) {
  if (!tillAllowed) return OfflineSwitchDecision.notAllowed;
  if (!serverReachable) return OfflineSwitchDecision.allowed;
  return managerApproved ? OfflineSwitchDecision.allowed : OfflineSwitchDecision.needsManager;
}

/// An offline sale the till refuses to record, with the reason to show.
class OfflineSaleRefused implements Exception {
  OfflineSaleRefused(this.message);
  final String message;
  @override
  String toString() => message;
}

/// The till's online/offline state (ADR-0068).
class OfflineState {
  const OfflineState({
    this.offline = false,
    this.reason,
    this.periodKey,
    this.startedAt,
    this.switchedByUid,
    this.firstOfflineSaleAt,
    this.serverBackAt,
    this.serverDown = false,
    this.tillAllowed = false,
    this.syncing = false,
    this.refreshing = false,
    this.waiting = 0,
    this.lastSyncAt,
    this.lastSyncError,
    this.held = const [],
    this.pricesAsOf,
    this.queueReadable = true,
    this.firstUnsyncedSoldAt,
    this.offlineSession = false,
  });

  final bool offline;
  final OfflineReason? reason;

  /// The offline period in progress (its client key).
  final String? periodKey;
  final DateTime? startedAt;

  /// The manager who approved a MANUAL switch.
  final String? switchedByUid;

  /// The first sale of this offline period.
  final DateTime? firstOfflineSaleAt;

  /// Set when, while offline, the server answered again: the till finishes the
  /// current basket and returns online, by [onlineBy] at the latest.
  final DateTime? serverBackAt;

  /// Online, but a request found no server and the probe confirmed it.
  final bool serverDown;

  /// The till's `offlineAllowed` (an admin setting, D-1).
  final bool tillAllowed;
  final bool syncing;
  final bool refreshing;

  /// Offline sales on this device that the server has not got yet.
  final int waiting;
  final DateTime? lastSyncAt;
  final String? lastSyncError;
  final List<HeldSale> held;

  /// The age of the cached prices — shown while offline.
  final DateTime? pricesAsOf;

  /// False when stored offline sales exist but cannot be opened on this device.
  final bool queueReadable;

  /// The oldest unsynced offline sale: the 24-hour limit counts from it.
  final DateTime? firstUnsyncedSoldAt;

  /// The signed-in user signed in OFFLINE (D-7): there is no server session,
  /// so nothing syncs until they sign in online.
  final bool offlineSession;

  /// The server is back but the user signed in offline: they must sign in
  /// online before the offline sales can be sent.
  bool get needsOnlineSignIn => offlineSession && serverBack;

  bool get serverBack => serverBackAt != null;
  DateTime? get onlineBy => serverBackAt?.add(kReturnGrace);

  OfflineState copyWith({
    bool? offline,
    OfflineReason? reason,
    String? periodKey,
    DateTime? startedAt,
    String? switchedByUid,
    DateTime? firstOfflineSaleAt,
    DateTime? serverBackAt,
    bool clearServerBack = false,
    bool clearPeriod = false,
    bool? serverDown,
    bool? tillAllowed,
    bool? syncing,
    bool? refreshing,
    int? waiting,
    DateTime? lastSyncAt,
    String? lastSyncError,
    bool clearSyncError = false,
    List<HeldSale>? held,
    DateTime? pricesAsOf,
    bool? queueReadable,
    DateTime? firstUnsyncedSoldAt,
    bool clearFirstUnsynced = false,
    bool? offlineSession,
  }) =>
      OfflineState(
        offline: offline ?? this.offline,
        reason: clearPeriod ? null : (reason ?? this.reason),
        periodKey: clearPeriod ? null : (periodKey ?? this.periodKey),
        startedAt: clearPeriod ? null : (startedAt ?? this.startedAt),
        switchedByUid: clearPeriod ? null : (switchedByUid ?? this.switchedByUid),
        firstOfflineSaleAt:
            clearPeriod ? null : (firstOfflineSaleAt ?? this.firstOfflineSaleAt),
        serverBackAt: clearServerBack || clearPeriod ? null : (serverBackAt ?? this.serverBackAt),
        serverDown: serverDown ?? this.serverDown,
        tillAllowed: tillAllowed ?? this.tillAllowed,
        syncing: syncing ?? this.syncing,
        refreshing: refreshing ?? this.refreshing,
        waiting: waiting ?? this.waiting,
        lastSyncAt: lastSyncAt ?? this.lastSyncAt,
        lastSyncError: clearSyncError ? null : (lastSyncError ?? this.lastSyncError),
        held: held ?? this.held,
        pricesAsOf: pricesAsOf ?? this.pricesAsOf,
        queueReadable: queueReadable ?? this.queueReadable,
        firstUnsyncedSoldAt: clearFirstUnsynced
            ? null
            : (firstUnsyncedSoldAt ?? this.firstUnsyncedSoldAt),
        offlineSession: offlineSession ?? this.offlineSession,
      );
}

/// Runs offline POS on the till (ADR-0068 phase 1): the offline catalogue
/// refresh, reachability, the online/offline switch and the return, offline
/// sales into the encrypted queue, offline shifts, and the sync.
class OfflineController extends Notifier<OfflineState> {
  Timer? _probeTimer;
  Timer? _graceTimer;
  Timer? _refreshTimer;
  Timer? _syncTimer;
  bool _probing = false;
  bool _paymentOpen = false;

  /// An ONLINE sign-in is in place (a real server session). Sync needs it.
  bool _signedIn = false;

  /// The user signed in offline (D-7). Never syncs on those credentials.
  bool _offlineSession = false;
  int _syncFailures = 0;

  /// The clock — replaced in tests.
  @visibleForTesting
  DateTime Function() clock = DateTime.now;

  /// Marks the session signed in without the sign-in side effects (catalogue
  /// download, timers) — for tests that drive the sync directly.
  @visibleForTesting
  void markSignedInForTest() => _signedIn = true;

  @override
  OfflineState build() {
    ref.onDispose(_cancelAll);
    return const OfflineState();
  }

  OfflineQueue get _queue => ref.read(offlineQueueProvider);
  OfflineCatalog get _catalog => ref.read(offlineCatalogProvider);

  void _cancelAll() {
    _probeTimer?.cancel();
    _graceTimer?.cancel();
    _refreshTimer?.cancel();
    _syncTimer?.cancel();
  }

  /// The till a sale or shift belongs to: the open shift's till, else the one
  /// this device last ran a shift on (kept in the offline cache).
  PosTill? get currentTill {
    final app = ref.read(appControllerProvider);
    final cached = _catalog.snapshot.till;
    final till = app.till ?? cached;
    if (till == null) return null;
    final ctx = app.context;
    if (ctx != null && till.branchId != ctx.branchId) return null;
    // The cached till must be the open shift's own till. Selling on another
    // till's number against this shift would be held at sync ("the shift
    // belongs to a different till"), so no till is better than the wrong one.
    final shift = app.shift;
    if (shift != null && shift.posTillId.isNotEmpty && shift.posTillId != till.id) return null;
    return till;
  }

  void _publishQueue() {
    final q = _queue.snapshot;
    state = state.copyWith(
      waiting: q.waiting,
      held: q.held,
      lastSyncAt: q.lastSyncAt,
      lastSyncError: q.lastSyncError,
      clearSyncError: q.lastSyncError == null,
      queueReadable: _queue.readable,
      firstUnsyncedSoldAt: q.firstUnsyncedSoldAt,
      clearFirstUnsynced: q.firstUnsyncedSoldAt == null,
    );
  }

  // ---------------------------------------------------------------- lifecycle

  /// After a successful online sign-in: load the queue and the cache, start the
  /// catalogue refresh, and send anything waiting.
  Future<void> signedIn() async {
    _signedIn = true;
    _offlineSession = false;
    state = state.copyWith(offlineSession: false);
    ref.read(apiClientProvider).onUnreachable = onUnreachable;
    await _queue.load();
    await _catalog.load();
    _publishQueue();
    state = state.copyWith(
      tillAllowed: currentTill?.offlineAllowed ?? false,
      pricesAsOf: _catalog.snapshot.pricesAsOf,
    );
    _refreshTimer?.cancel();
    _refreshTimer = Timer.periodic(kCatalogRefreshEvery, (_) => refreshCatalog());
    unawaited(refreshCatalog());
    unawaited(syncNow());
  }

  /// Signing out ends any offline period (it is sent with the next sync).
  Future<void> signedOut() async {
    _signedIn = false;
    _offlineSession = false;
    if (state.offline) await _endPeriod();
    _cancelAll();
    state = OfflineState(
      waiting: _queue.snapshot.waiting,
      held: _queue.snapshot.held,
      lastSyncAt: _queue.snapshot.lastSyncAt,
      pricesAsOf: state.pricesAsOf,
      queueReadable: _queue.readable,
      firstUnsyncedSoldAt: _queue.snapshot.firstUnsyncedSoldAt,
    );
  }

  /// The till of the open shift changed (opened, resumed, refreshed).
  Future<void> tillChanged(PosTill? till) async {
    state = state.copyWith(tillAllowed: till?.offlineAllowed ?? false);
    if (till != null) await _catalog.rememberTill(till);
  }

  // ---------------------------------------------------------------- catalogue

  /// Re-reads products, prices and the till's offline permission from the
  /// server. Never while offline; a failure keeps the previous cache.
  Future<void> refreshCatalog({bool forceSweep = false}) async {
    if (state.offline || state.refreshing || !_signedIn) return;
    final app = ref.read(appControllerProvider);
    final ctx = app.context;
    if (ctx == null || app.isWaiter || app.isKitchenOnly) return;
    state = state.copyWith(refreshing: true);
    try {
      final snap = await OfflineCatalogRefresher(
        CatalogService(ref.read(apiClientProvider)),
        clock: clock,
      ).build(
        previous: _catalog.snapshot,
        companyId: ctx.companyId,
        branchUid: ctx.branchUid,
        currency: app.currency,
        walkIn: app.defaultCustomer,
        unitsByUid: app.unitsByUid,
        vatRates: app.vatRates,
        forceSweep: forceSweep,
      );
      // The till's own setting can change in the office (POS › Tills).
      var till = currentTill;
      if (till != null) {
        try {
          till = await ref.read(tillServiceProvider).getByUid(till.uid);
          ref.read(appControllerProvider.notifier).refreshTill(till);
        } catch (_) {/* keep the cached till */}
      }
      await _catalog.save(till == null ? snap : snap.copyWith(till: till));
      state = state.copyWith(
        refreshing: false,
        pricesAsOf: snap.pricesAsOf,
        tillAllowed: till?.offlineAllowed ?? state.tillAllowed,
      );
    } catch (e) {
      debugPrint('POS offline catalogue refresh failed: $e');
      state = state.copyWith(refreshing: false);
    }
  }

  // ---------------------------------------------------------------- reachability

  /// Called by the API client whenever a request got no answer at all. A probe
  /// confirms it before the till offers to go offline.
  void onUnreachable() {
    if (state.offline || _probing || !_signedIn) return;
    final phase = ref.read(appControllerProvider).phase;
    if (phase != AppPhase.register && phase != AppPhase.openShift) return;
    unawaited(probe());
  }

  /// Checks the server now and acts on the answer. True when it answered.
  Future<bool> probe() async {
    if (_probing) return !state.serverDown;
    _probing = true;
    Duration? latency;
    try {
      latency = await ref.read(reachabilityProbeProvider)();
    } catch (_) {
      latency = null;
    } finally {
      _probing = false;
    }
    _onProbe(latency);
    return latency != null;
  }

  void _onProbe(Duration? latency) {
    if (state.offline) {
      final back = latency != null &&
          (state.reason != OfflineReason.manual || latency <= kFastAnswer);
      if (back) {
        if (state.serverBackAt == null) {
          state = state.copyWith(serverBackAt: clock().toUtc());
          _graceTimer?.cancel();
          _graceTimer = Timer(kReturnGrace, _graceExpired);
        }
        // Signed in offline: the cashier is asked to sign in online (the strip
        // says so); only once the grace is over and nothing is being rung is
        // the sign-in forced. Otherwise: nothing being rung, or the grace is
        // over — back online now. Never under an open payment.
        if (_offlineSession) {
          if (!_paymentOpen && _graceOver && ref.read(cartProvider).isEmpty) {
            unawaited(requestOnlineSignIn());
          }
        } else if (!_paymentOpen && (ref.read(cartProvider).isEmpty || _graceOver)) {
          unawaited(returnOnline());
        }
      } else if (state.serverBackAt != null) {
        _graceTimer?.cancel();
        state = state.copyWith(clearServerBack: true);
      }
      return;
    }
    if (latency != null) {
      if (state.serverDown) {
        state = state.copyWith(serverDown: false);
        _probeTimer?.cancel();
      }
      return;
    }
    if (!state.serverDown) {
      state = state.copyWith(serverDown: true);
      _startProbeTimer();
    }
  }

  void _startProbeTimer() {
    _probeTimer?.cancel();
    _probeTimer = Timer.periodic(kProbeEvery, (_) => probe());
  }

  /// The server answered more than [kReturnGrace] ago: the till may not stay
  /// offline on a working connection any longer, basket or not (D-1).
  bool get _graceOver {
    final by = state.onlineBy;
    return by != null && !clock().toUtc().isBefore(by);
  }

  void _graceExpired() {
    if (!state.offline) return;
    // A payment on screen finishes first; paymentClosed() completes the return.
    if (_paymentOpen) return;
    if (_offlineSession) {
      if (ref.read(cartProvider).isEmpty) unawaited(requestOnlineSignIn());
      return;
    }
    unawaited(returnOnline());
  }

  // ---------------------------------------------------------------- switching

  /// Switches the till offline, if the rules allow (D-1). Pass [managerUid] —
  /// the `authoriserUid` of a `POS.OFFLINE.SWITCH` step-up — for a manual
  /// switch while the server is reachable. Throws [SecretCipherException] when
  /// this device cannot keep offline sales securely.
  Future<OfflineSwitchDecision> switchOffline({String? managerUid}) async {
    if (state.offline) return OfflineSwitchDecision.allowed;
    final till = currentTill;
    Duration? latency;
    try {
      latency = await ref.read(reachabilityProbeProvider)();
    } catch (_) {
      latency = null;
    }
    final reachable = latency != null;
    final decision = decideOfflineSwitch(
      tillAllowed: till?.offlineAllowed ?? false,
      serverReachable: reachable,
      managerApproved: managerUid != null && managerUid.isNotEmpty,
    );
    if (decision != OfflineSwitchDecision.allowed) {
      if (!reachable && !state.serverDown) state = state.copyWith(serverDown: true);
      return decision;
    }
    await _enter(
      till!,
      reachable ? OfflineReason.manual : OfflineReason.unreachable,
      reachable ? managerUid : null,
    );
    return decision;
  }

  Future<void> _enter(PosTill till, OfflineReason reason, String? by) async {
    final app = ref.read(appControllerProvider);
    final ctx = app.context;
    if (ctx == null) throw OfflineSaleRefused('Sign in first.');
    await _queue.load();
    final key = _uuid.v4();
    final at = clock().toUtc();
    final shift = app.shift;
    final offKey = offlineShiftKeyOf(shift);
    await _queue.startPeriod(OfflinePeriod(
      clientKey: key,
      tillUid: till.uid,
      branchUid: ctx.branchUid,
      reason: reason,
      switchedByUid: by,
      startedAt: at,
      sessionUid: offKey == null ? shift?.uid : null,
      sessionClientKey: offKey,
    ));
    _syncTimer?.cancel();
    _graceTimer?.cancel();
    state = state.copyWith(
      offline: true,
      reason: reason,
      periodKey: key,
      startedAt: at,
      switchedByUid: by,
      clearServerBack: true,
      serverDown: false,
      tillAllowed: true,
    );
    _publishQueue();
    _startProbeTimer();
  }

  Future<void> _endPeriod() async {
    final key = state.periodKey;
    if (key == null) return;
    try {
      await _queue.endPeriod(key, clock());
    } catch (e) {
      debugPrint('POS could not end the offline period: $e');
    }
  }

  /// Back to online selling, then sync (D-1 "coming back"). A user who signed
  /// in offline has no server session: they are sent to sign in online first.
  Future<void> returnOnline() async {
    if (!state.offline) return;
    if (_offlineSession) return requestOnlineSignIn();
    _probeTimer?.cancel();
    _graceTimer?.cancel();
    await _endPeriod();
    state = state.copyWith(offline: false, clearPeriod: true, serverDown: false);
    _publishQueue();
    unawaited(syncNow());
    unawaited(refreshCatalog());
  }

  /// The offline payment sheet opened / closed. While it is open the till never
  /// flips back online under the cashier.
  void paymentOpened() => _paymentOpen = true;

  void paymentClosed() {
    _paymentOpen = false;
    basketDone();
  }

  /// A basket was finished (or emptied). If the server is back, this is the
  /// moment to return online.
  void basketDone() {
    if (!state.offline || !state.serverBack || _paymentOpen) return;
    if (_offlineSession) {
      if (_graceOver && ref.read(cartProvider).isEmpty) unawaited(requestOnlineSignIn());
      return;
    }
    if (ref.read(cartProvider).isEmpty || _graceOver) unawaited(returnOnline());
  }

  // ---------------------------------------------------------------- offline sign-in

  /// Whether the sign-in screen should offer offline sign-in: the server does
  /// not answer, this device's till may sell offline, and someone is known.
  Future<bool> offlineSignInAvailable() async {
    final store = ref.read(offlineSignInStoreProvider);
    await store.load();
    await _catalog.load();
    final till = _catalog.snapshot.till;
    if (till == null || !till.offlineAllowed) return false;
    if (store.knownUsers(clock()).isEmpty) return false;
    Duration? latency;
    try {
      latency = await ref.read(reachabilityProbeProvider)();
    } catch (_) {
      latency = null;
    }
    return latency == null;
  }

  /// Checks an offline sign-in (D-7). Allowed only while the server does not
  /// answer, only on a till allowed offline, and only for a known user of this
  /// till's branch who may sell. Returns the user, or the message to show.
  Future<({KnownUser? user, String? error})> verifyOfflineSignIn(String username, String secret,
      {required bool pin}) async {
    final store = ref.read(offlineSignInStoreProvider);
    await store.load();
    await _catalog.load();
    await _queue.load();
    final till = _catalog.snapshot.till;
    if (till == null || !till.offlineAllowed) {
      return (user: null, error: 'This till is not allowed to sell offline.');
    }
    Duration? latency;
    try {
      latency = await ref.read(reachabilityProbeProvider)();
    } catch (_) {
      latency = null;
    }
    if (latency != null) {
      return (user: null, error: 'The server answers — sign in as usual.');
    }
    final r = await store.verify(username, secret, pin: pin, now: clock());
    final u = r.user;
    if (r.outcome != OfflineSignInOutcome.ok || u == null) return (user: null, error: r.message);
    if (!u.canSellOffline) {
      return (user: null, error: 'Offline sign-in is for people who run a till and sell.');
    }
    if (u.context.branchId != till.branchId) {
      return (user: null, error: 'You last signed in at another branch, so you cannot sign in '
          'offline on this till.');
    }
    if (_catalog.snapshot.walkIn == null || _catalog.snapshot.isEmpty) {
      return (user: null, error: 'This till has no saved products or prices yet, so it cannot '
          'sell offline.');
    }
    return (user: u, error: null);
  }

  /// The user signed in offline: the till is offline from here on, continuing
  /// the offline period still open on the device (an app restart mid-outage),
  /// or starting one.
  Future<void> enterOfflineSession(KnownUser user) async {
    _signedIn = false;
    _offlineSession = true;
    await _queue.load();
    await _catalog.load();
    final till = currentTill ?? _catalog.snapshot.till;
    if (till == null) throw OfflineSaleRefused('This till is not allowed to sell offline.');
    final open = _queue.snapshot.periods
        .where((p) => !p.ended && p.tillUid == till.uid)
        .toList();
    if (open.isNotEmpty) {
      final p = open.last;
      state = state.copyWith(
        offline: true,
        reason: p.reason,
        periodKey: p.clientKey,
        startedAt: p.startedAt,
        switchedByUid: p.switchedByUid,
        clearServerBack: true,
        serverDown: false,
        tillAllowed: true,
        offlineSession: true,
      );
      _publishQueue();
      _startProbeTimer();
    } else {
      await _enter(till, OfflineReason.unreachable, null);
      state = state.copyWith(offlineSession: true);
    }
    if (ref.read(appControllerProvider).shift != null) await attachPeriodToCurrentShift();
  }

  /// The server is back and the user signed in offline: end the offline period
  /// and send them to sign in online. The queue (and the open shift, kept on
  /// the till row) wait safely on the device; nothing syncs until then.
  Future<void> requestOnlineSignIn() async {
    if (!_offlineSession) return;
    _probeTimer?.cancel();
    _graceTimer?.cancel();
    await _endPeriod();
    _offlineSession = false;
    state = state.copyWith(offline: false, clearPeriod: true, serverDown: false, offlineSession: false);
    _publishQueue();
    ref.read(appControllerProvider.notifier).offlineSessionEnded();
  }

  /// The shift held on [till] by this device, rebuilt from disk: a shift
  /// opened offline from the queue (its float and time), else the server shift
  /// the till row names.
  PosSession restoreShift(PosTill till) {
    final uid = till.openSessionUid!;
    final key = uid.startsWith(kOfflineShiftPrefix) ? uid.substring(kOfflineShiftPrefix.length) : null;
    if (key != null) {
      for (final s in _queue.snapshot.shifts) {
        if (s.clientKey != key) continue;
        if (s.adopted) break;
        return PosSession(
          id: '',
          uid: uid,
          posTillId: till.id,
          cashierId: s.cashierId,
          sessionNumber: 'Offline shift',
          status: PosSessionStatus.open,
          openedAt: s.openedAt,
          closedAt: null,
          reconciledAt: null,
          openingFloatAmount: s.openingFloat,
          countedCashAmount: null,
          expectedCashAmount: null,
          varianceAmount: null,
          notes: null,
        );
      }
    }
    return PosSession.fromOpenTill(till, sessionUid: uid);
  }

  // ---------------------------------------------------------------- selling

  /// Why an offline sale cannot be rung right now, or null when it can.
  String? sellingProblem() {
    if (!state.offline) return 'The till is online.';
    if (!_queue.readable) {
      return 'The offline sales stored on this device cannot be opened, so no new ones '
          'are taken. Ask your supervisor.';
    }
    if (offlineCapReached(_queue.snapshot.firstUnsyncedSoldAt, clock())) {
      return 'This till has sold offline for 24 hours without reaching the server. '
          'Reconnect and sync before selling again.';
    }
    final till = currentTill;
    if (till == null || !till.offlineAllowed) return 'This till is not allowed to sell offline.';
    final app = ref.read(appControllerProvider);
    if (app.defaultCustomer == null) {
      return 'No walk-in customer is saved on this till, so it cannot sell offline.';
    }
    if (app.shift == null) return 'Open a shift first.';
    return null;
  }

  /// Records an offline sale: checks every rule, numbers it, and writes it to
  /// the encrypted queue. Returns only once it is durable — print after this,
  /// never before. Throws [OfflineSaleRefused] (nothing stored) on any rule, or
  /// [SecretCipherException] when it could not be stored securely.
  ///
  /// [idempotencyKey] reuses the key of an online attempt that got no answer,
  /// so if that attempt did reach the server the sync answers ALREADY_POSTED
  /// instead of booking the sale twice.
  Future<OfflineSale> recordSale({
    required CartState cart,
    required List<OfflineTender> tendered,
    required bool ageVerified,
    String? idempotencyKey,
  }) async {
    final problem = sellingProblem() ?? offlineBasketProblem(cart);
    if (problem != null) throw OfflineSaleRefused(problem);
    if (cart.hasRestricted && !ageVerified) {
      throw OfflineSaleRefused('Confirm the customer\'s age first.');
    }
    final minor = minorUnitsFor(cart.currency);
    final lines = offlineLinesFrom(cart);
    final gross = offlineGross(lines, minor);
    final tenderProblem = offlineTenderProblem(gross, tendered);
    if (tenderProblem != null) throw OfflineSaleRefused(tenderProblem);

    final app = ref.read(appControllerProvider);
    final ctx = app.context!;
    final shift = app.shift!;
    final offKey = offlineShiftKeyOf(shift);
    final till = currentTill!;
    final cash = tendered
        .where((t) => t.type == TenderType.cash)
        .fold(0.0, (s, t) => s + t.amount);
    final sale = await _queue.addSale(
      tillCode: till.code,
      tillUid: till.uid,
      build: (number) => OfflineSale(
        idempotencyKey: idempotencyKey ?? _uuid.v4(),
        offlineNumber: number,
        soldAt: clock().toUtc(),
        tillUid: till.uid,
        branchUid: ctx.branchUid,
        sessionUid: offKey == null ? shift.uid : null,
        sessionClientKey: offKey,
        customerId: cart.customer!.id,
        customerName: cart.customer!.displayName,
        currency: cart.currency,
        lines: lines,
        tenders: recordedTenders(gross, tendered),
        grossTotal: gross,
        ageVerified: ageVerified,
        notes: cart.notes,
        cashierName: app.me?.displayName ?? '',
        periodKey: state.periodKey,
        cashTendered: cash > 0 ? cash : null,
        rungByUid: app.me?.uid,
      ),
    );
    state = state.copyWith(firstOfflineSaleAt: state.firstOfflineSaleAt ?? sale.soldAt);
    _publishQueue();
    return sale;
  }

  // ---------------------------------------------------------------- shifts

  /// Opens a provisional shift while offline (D-4). The server creates the real
  /// shift at sync (idempotent on the client key) and its sales land in it.
  Future<PosSession> openOfflineShift(double openingFloat) async {
    if (!state.offline) throw OfflineSaleRefused('The till is online: open the shift as usual.');
    final till = currentTill;
    if (till == null || !till.offlineAllowed) {
      throw OfflineSaleRefused('This till is not allowed to sell offline.');
    }
    if (till.hasOpenSession) {
      throw OfflineSaleRefused('${till.name} already has a shift open on the server '
          '(${till.occupantHolder}). An offline shift cannot be opened on it.');
    }
    final app = ref.read(appControllerProvider);
    final ctx = app.context!;
    final key = _uuid.v4();
    final at = clock().toUtc();
    await _queue.addShift(OfflineShift(
      clientKey: key,
      tillUid: till.uid,
      branchUid: ctx.branchUid,
      openedAt: at,
      openingFloat: openingFloat,
      cashierId: app.cashierId ?? '',
    ));
    final period = state.periodKey;
    if (period != null) await _queue.attachPeriodToShift(period, clientKey: key);
    // The device's till row now says this cashier holds it, so an app restart
    // (or a sign-in offline) resumes this shift instead of opening another.
    await tillChanged(till.withOccupancy(
        sessionUid: '$kOfflineShiftPrefix$key', cashierId: app.cashierId, openedAt: at));
    return PosSession(
      id: '',
      uid: '$kOfflineShiftPrefix$key',
      posTillId: till.id,
      cashierId: app.cashierId ?? '',
      sessionNumber: 'Offline shift',
      status: PosSessionStatus.open,
      openedAt: at,
      closedAt: null,
      reconciledAt: null,
      openingFloatAmount: openingFloat,
      countedCashAmount: null,
      expectedCashAmount: null,
      varianceAmount: null,
      notes: null,
    );
  }

  /// Ties the offline period in progress to the shift now open (a shift
  /// resumed or opened after the till went offline).
  Future<void> attachPeriodToCurrentShift() async {
    final key = state.periodKey;
    final shift = ref.read(appControllerProvider).shift;
    if (key == null || shift == null) return;
    final offKey = offlineShiftKeyOf(shift);
    try {
      await _queue.attachPeriodToShift(key,
          sessionUid: offKey == null ? shift.uid : null, clientKey: offKey);
    } catch (e) {
      debugPrint('POS could not attach the offline period to the shift: $e');
    }
  }

  /// True when the shift still has sales the server has not got (D-4: closing
  /// it waits until they have synced, so its Z-report is complete).
  bool shiftHasUnsynced(PosSession? shift) {
    if (shift == null) return false;
    final key = offlineShiftKeyOf(shift);
    return key != null || _queue.hasUnsyncedFor(sessionUid: shift.uid);
  }

  // ---------------------------------------------------------------- sync

  /// Sends what waits, one batch after another, and applies each answer.
  /// Returns an error message, or null when the queue went through (or there
  /// was nothing to send). Retries on its own with backoff after a failure.
  Future<String?> syncNow() async {
    // Never on the credentials of an offline sign-in (D-7): sign in online first.
    if (_offlineSession) return 'Sign in online to send the offline sales.';
    if (state.offline || state.syncing || !_signedIn) return null;
    final ctx = ref.read(appControllerProvider).context;
    if (ctx == null) return null;
    await _queue.load();
    if (!_queue.readable) {
      _publishQueue();
      return 'The offline sales stored on this device cannot be opened.';
    }
    if (_queue.nextBatch(branchUid: ctx.branchUid) == null) {
      _publishQueue();
      return null;
    }
    _syncTimer?.cancel();
    state = state.copyWith(syncing: true);
    String? error;
    var stalled = false;
    try {
      for (var round = 0; round < 50; round++) {
        final batch = _queue.nextBatch(branchUid: ctx.branchUid);
        if (batch == null) break;
        final before = _progressMark();
        final data = await ref.read(apiClientProvider).post('/pos/offline-sync', body: batch.toWire());
        final applied =
            await _queue.applyAnswer(batch, SyncAnswer.fromJson(asMap(data)), now: clock());
        await _afterApplied(applied);
        _publishQueue();
        if (_progressMark() == before) {
          // The server took nothing from this batch (every sale in flight).
          stalled = true;
          break;
        }
      }
      if (!stalled) _syncFailures = 0;
    } on ApiException catch (e) {
      error = e.message;
    } on SecretCipherException catch (e) {
      error = e.message;
    } catch (e) {
      debugPrint('POS offline sync failed: $e');
      error = 'The offline sales could not be sent. The till tries again by itself.';
    }
    if (error != null || stalled) {
      _syncFailures++;
    }
    if (error != null) {
      try {
        await _queue.recordSyncError(error);
      } catch (_) {}
    }
    state = state.copyWith(syncing: false);
    _publishQueue();
    if (error != null || stalled) {
      final wait = kSyncBackoff[(_syncFailures - 1).clamp(0, kSyncBackoff.length - 1)];
      _syncTimer?.cancel();
      _syncTimer = Timer(wait, () => syncNow());
    }
    return error;
  }

  String _progressMark() {
    final q = _queue.snapshot;
    return '${q.sales.length}/${q.shifts.where((s) => !s.adopted).length}/${q.periods.length}';
  }

  Future<void> _afterApplied(SyncApplied applied) async {
    final journal = ref.read(receiptJournalProvider);
    for (final r in applied.posted) {
      try {
        await journal.markSynced(r.offlineNumber,
            invoiceUid: r.invoiceUid, invoiceNumber: r.invoiceNumber);
      } catch (_) {/* the reprint keeps the offline number */}
    }
    final app = ref.read(appControllerProvider);
    final current = offlineShiftKeyOf(app.shift);
    for (final s in applied.adopted) {
      if (s.clientKey != current) continue;
      final uid = s.serverSessionUid!;
      try {
        final session = await ref.read(sessionServiceProvider).getByUid(uid);
        ref.read(appControllerProvider.notifier).updateShift(session);
      } catch (_) {
        final shift = app.shift!;
        ref.read(appControllerProvider.notifier).updateShift(PosSession(
              id: '',
              uid: uid,
              posTillId: shift.posTillId,
              cashierId: shift.cashierId,
              sessionNumber: s.serverSessionNumber ?? '—',
              status: PosSessionStatus.open,
              openedAt: shift.openedAt,
              closedAt: null,
              reconciledAt: null,
              openingFloatAmount: shift.openingFloatAmount,
              countedCashAmount: null,
              expectedCashAmount: null,
              varianceAmount: null,
              notes: null,
            ));
      }
      final till = currentTill;
      if (till != null) {
        await tillChanged(till.withOccupancy(
            sessionUid: uid, cashierId: app.cashierId, openedAt: s.openedAt));
      }
    }
  }

  /// Forget a held-sale notice once the cashier has read it.
  Future<void> dismissHeld(String offlineNumber) async {
    await _queue.dismissHeld(offlineNumber);
    _publishQueue();
  }
}

final offlineControllerProvider =
    NotifierProvider<OfflineController, OfflineState>(OfflineController.new);
