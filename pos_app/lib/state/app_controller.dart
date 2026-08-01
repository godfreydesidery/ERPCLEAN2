import 'package:collection/collection.dart';
import 'package:flutter/foundation.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../core/api/api_exception.dart';
import '../core/config/app_config.dart';
import '../core/jwt.dart';
import '../models/auth.dart';
import '../models/catalog.dart';
import '../models/context.dart';
import '../models/enums.dart';
import '../models/parties.dart';
import '../models/pos.dart';
import 'providers.dart';

/// Where the app is in the shift flow.
enum AppPhase { booting, login, openShift, register }

/// Immutable top-level app state: who is signed in, the resolved numeric context,
/// the open shift (if any), the active register mode, and the reference data the
/// cart needs (walk-in customer, default agent, units by uid).
class AppData {
  const AppData({
    this.phase = AppPhase.booting,
    this.me,
    this.context,
    this.shift,
    this.mode = BusinessMode.supermarket,
    this.busy = false,
    this.error,
    this.notice,
    this.cashierId,
    this.defaultCustomer,
    this.defaultAgent,
    this.agents = const [],
    this.unitsByUid = const {},
    this.currency = 'TZS',
    this.vatRates = const {},
  });

  final AppPhase phase;
  final Me? me;
  final PosContext? context;
  final PosSession? shift;
  final BusinessMode mode;
  final bool busy;
  final String? error;

  /// A non-blocking message about something that did not work but does not stop
  /// the cashier — e.g. the open-shift lookup failed, so an occupied till may be
  /// their own shift rather than a colleague's. Shown as a strip, never a modal.
  final String? notice;

  /// The signed-in cashier's own user id (the JWT `sub`). Used to tell "your
  /// abandoned shift" apart from "someone else's live shift" on a busy till.
  final String? cashierId;

  final Customer? defaultCustomer;
  final Agent? defaultAgent;
  final List<Agent> agents;
  final Map<String, Unit> unitsByUid;
  final String currency;

  /// VAT rates by `vatStatus`, as a **fraction** (e.g. 0.18 = 18%) — this is the
  /// backend's canonical form (TaxRate.rate is stored 0 ≤ rate < 1). Empty when
  /// TAXRATE.VIEW is not granted, in which case the preview shows net prices.
  final Map<String, double> vatRates;

  bool can(String code) => me?.can(code) ?? false;

  /// VAT fraction for a `vatStatus` (e.g. 0.18); 0 when unknown. Values are
  /// already fractions (see [vatRates]) — no percentage/fraction conversion.
  double vatFractionFor(String vatStatus) => vatRates[vatStatus] ?? 0;

  /// Gross (VAT-inclusive) unit price for the preview total. When the source
  /// price list is VAT-inclusive the stored [amount] is ALREADY gross, so VAT is
  /// not re-applied; otherwise VAT is added exactly once. Falls back to [amount]
  /// when the rate is unknown (net preview).
  double grossUnitPrice(double amount, String vatStatus,
          {bool vatInclusive = false}) =>
      vatInclusive ? amount : amount * (1 + vatFractionFor(vatStatus));

  AppData copyWith({
    AppPhase? phase,
    Me? me,
    PosContext? context,
    PosSession? shift,
    bool clearShift = false,
    BusinessMode? mode,
    bool? busy,
    String? error,
    bool clearError = false,
    String? notice,
    bool clearNotice = false,
    String? cashierId,
    Customer? defaultCustomer,
    Agent? defaultAgent,
    List<Agent>? agents,
    Map<String, Unit>? unitsByUid,
    String? currency,
    Map<String, double>? vatRates,
  }) {
    return AppData(
      phase: phase ?? this.phase,
      me: me ?? this.me,
      context: context ?? this.context,
      shift: clearShift ? null : (shift ?? this.shift),
      mode: mode ?? this.mode,
      busy: busy ?? this.busy,
      error: clearError ? null : (error ?? this.error),
      notice: clearNotice ? null : (notice ?? this.notice),
      cashierId: cashierId ?? this.cashierId,
      defaultCustomer: defaultCustomer ?? this.defaultCustomer,
      defaultAgent: defaultAgent ?? this.defaultAgent,
      agents: agents ?? this.agents,
      unitsByUid: unitsByUid ?? this.unitsByUid,
      currency: currency ?? this.currency,
      vatRates: vatRates ?? this.vatRates,
    );
  }
}

class AppController extends Notifier<AppData> {
  @override
  AppData build() => const AppData();

  /// One-time startup: load persisted config, wire the auth-lost callback,
  /// restore any session, and resolve context. Falls back to login on failure.
  Future<void> bootstrap() async {
    final config = await AppConfig.load();
    ref.read(baseHostProvider.notifier).set(config.baseHost);
    ref.read(apiClientProvider).onAuthLost = onAuthLost;
    final tokens = ref.read(tokenManagerProvider);
    await tokens.load();
    if (!tokens.hasSession) {
      state = state.copyWith(phase: AppPhase.login);
      return;
    }
    try {
      await _afterAuth();
    } on ApiException catch (e) {
      // Only a genuine auth failure should drop the session. A transient
      // network / 5xx / permission hiccup at startup must NOT wipe a valid
      // token — surface the error and let the cashier retry by signing in.
      if (e.isUnauthorized) await tokens.clear();
      state = state.copyWith(phase: AppPhase.login, error: e.message);
    } catch (e) {
      state = state.copyWith(phase: AppPhase.login, error: _friendly(e));
    }
  }

  Future<void> login(String username, String password) async {
    state = state.copyWith(busy: true, clearError: true);
    try {
      await ref.read(authServiceProvider).login(username.trim(), password);
      await _afterAuth();
    } on ApiException catch (e) {
      state = state.copyWith(busy: false, error: e.message);
    } catch (e) {
      state = state.copyWith(busy: false, error: _friendly(e));
    }
  }

  /// Resolve identity + numeric context + cart reference data after a successful
  /// authentication, then land on the open-shift screen.
  Future<void> _afterAuth() async {
    final auth = ref.read(authServiceProvider);
    final ctxSvc = ref.read(contextServiceProvider);
    final parties = ref.read(partyServiceProvider);
    final catalog = ref.read(catalogServiceProvider);

    final me = await auth.me();
    final context = await ctxSvc.resolve(me);

    // Scope every subsequent call to the chosen branch (§02), and re-attach the
    // auth-lost callback in case the API client was rebuilt (e.g. a host change).
    final client = ref.read(apiClientProvider);
    client.onAuthLost = onAuthLost;
    client.branchUidOverride = context.branchUid;

    final units = await catalog.listUnits(context.companyId);
    final unitsByUid = {for (final u in units) u.uid: u};

    Customer? walkIn;
    List<Agent> agents = const [];
    Agent? defaultAgent;
    try {
      walkIn = await parties.findWalkIn(context.companyId);
    } catch (_) {/* CUSTOMER.VIEW may be absent — handled at cart time */}
    try {
      agents = await parties.listAgents(context.companyId);
      defaultAgent = agents.isNotEmpty ? agents.first : null;
    } catch (_) {/* AGENT.VIEW may be absent */}

    final currency = context.company.baseCurrency ??
        walkIn?.defaultCurrency ??
        'TZS';

    // Best-effort VAT rates so the preview total is VAT-inclusive and matches
    // the server gross (needs TAXRATE.VIEW; absent -> preview shows net prices).
    Map<String, double> vatRates = const {};
    try {
      vatRates = await catalog.taxRatesByStatus(context.companyId);
    } catch (_) {/* no TAXRATE.VIEW — net preview */}

    // Resume an existing OPEN session for this cashier (orphaned-shift recovery
    // after a restart / re-login) instead of forcing a duplicate-open later.
    //
    // The status filter is essential: unfiltered, this is every session the
    // company ever had, so past one page the still-OPEN row is simply not there
    // and the cashier is locked out of a till they themselves hold.
    //
    // "No open shift" and "we couldn't look" are different answers and must not
    // be swallowed into the same silence — a miss is the normal case, a failure
    // is why a cashier is left staring at an "In use" till with no explanation.
    final sub = jwtSub(ref.read(tokenManagerProvider).bundle?.accessToken);
    PosSession? resumable;
    String? notice;
    try {
      if (sub != null) {
        final open = await ref
            .read(sessionServiceProvider)
            .list(context.companyId, status: PosSessionStatus.open.wire, size: 50);
        resumable = open
            .where((s) => s.status.isOpen && s.cashierId == sub)
            .firstOrNull;
      }
    } on ApiException catch (e) {
      debugPrint('POS resume lookup failed: ${e.statusCode} ${e.message}');
      notice = "We couldn't check whether you already have a shift open. "
          'If your till shows as in use, open it again from the list below.';
    } catch (e) {
      debugPrint('POS resume lookup failed: $e');
      notice = "We couldn't check whether you already have a shift open. "
          'If your till shows as in use, open it again from the list below.';
    }

    state = state.copyWith(
      phase: resumable != null ? AppPhase.register : AppPhase.openShift,
      me: me,
      context: context,
      shift: resumable,
      cashierId: sub,
      busy: false,
      clearError: true,
      notice: notice,
      clearNotice: notice == null,
      defaultCustomer: walkIn,
      defaultAgent: defaultAgent,
      agents: agents,
      unitsByUid: unitsByUid,
      currency: currency,
      vatRates: vatRates,
    );
  }

  void setMode(BusinessMode mode) => state = state.copyWith(mode: mode);

  /// Open a shift on [tillUid] with [openingFloat] in the chosen [mode].
  Future<void> openShift(
      String tillUid, double openingFloat, BusinessMode mode) async {
    state = state.copyWith(busy: true, clearError: true);
    try {
      final session =
          await ref.read(sessionServiceProvider).open(tillUid, openingFloat);
      state = state.copyWith(
          phase: AppPhase.register, shift: session, mode: mode, busy: false);
    } on ApiException catch (e) {
      state = state.copyWith(busy: false, error: e.message);
    }
  }

  /// Pick up a shift this cashier already has open on a till — the recovery path
  /// after a force-close, where the session is still OPEN server-side because
  /// nothing but a counted cash-up may ever close it.
  Future<void> resumeShift(String sessionUid, BusinessMode mode) async {
    state = state.copyWith(busy: true, clearError: true);
    try {
      final session =
          await ref.read(sessionServiceProvider).getByUid(sessionUid);
      state = state.copyWith(
          phase: AppPhase.register,
          shift: session,
          mode: mode,
          busy: false,
          clearNotice: true);
    } on ApiException catch (e) {
      state = state.copyWith(busy: false, error: e.message);
    }
  }

  /// Replace the current shift snapshot (after payout/close/x-read refresh).
  void updateShift(PosSession session) => state = state.copyWith(shift: session);

  /// Refresh the shift from the server.
  Future<void> refreshShift() async {
    final uid = state.shift?.uid;
    if (uid == null) return;
    try {
      final s = await ref.read(sessionServiceProvider).getByUid(uid);
      state = state.copyWith(shift: s);
    } catch (_) {/* keep last-known on a transient read error */}
  }

  /// After a session is reconciled (terminal), drop back to the open-shift screen.
  void endShift() =>
      state = state.copyWith(phase: AppPhase.openShift, clearShift: true);

  Future<void> logout() async {
    try {
      await ref.read(authServiceProvider).logout();
    } catch (_) {}
    ref.read(apiClientProvider).branchUidOverride = null;
    state = const AppData(phase: AppPhase.login);
  }

  /// A 401 that could not be refreshed away — force re-login.
  void onAuthLost() {
    ref.read(apiClientProvider).branchUidOverride = null;
    state = const AppData(
      phase: AppPhase.login,
      error: 'Your session ended. Please sign in again.',
    );
  }

  void clearError() => state = state.copyWith(clearError: true);

  void clearNotice() => state = state.copyWith(clearNotice: true);

  /// Strips the "Bad state:" prefix that StateError.toString() adds, so context
  /// errors (e.g. "This user is not assigned to any company.") read cleanly.
  String _friendly(Object e) => e is StateError ? e.message : e.toString();
}

final appControllerProvider =
    NotifierProvider<AppController, AppData>(AppController.new);
