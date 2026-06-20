import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../core/api/api_exception.dart';
import '../core/config/app_config.dart';
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
    this.defaultCustomer,
    this.defaultAgent,
    this.agents = const [],
    this.unitsByUid = const {},
    this.currency = 'TZS',
  });

  final AppPhase phase;
  final Me? me;
  final PosContext? context;
  final PosSession? shift;
  final BusinessMode mode;
  final bool busy;
  final String? error;

  final Customer? defaultCustomer;
  final Agent? defaultAgent;
  final List<Agent> agents;
  final Map<String, Unit> unitsByUid;
  final String currency;

  bool can(String code) => me?.can(code) ?? false;

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
    Customer? defaultCustomer,
    Agent? defaultAgent,
    List<Agent>? agents,
    Map<String, Unit>? unitsByUid,
    String? currency,
  }) {
    return AppData(
      phase: phase ?? this.phase,
      me: me ?? this.me,
      context: context ?? this.context,
      shift: clearShift ? null : (shift ?? this.shift),
      mode: mode ?? this.mode,
      busy: busy ?? this.busy,
      error: clearError ? null : (error ?? this.error),
      defaultCustomer: defaultCustomer ?? this.defaultCustomer,
      defaultAgent: defaultAgent ?? this.defaultAgent,
      agents: agents ?? this.agents,
      unitsByUid: unitsByUid ?? this.unitsByUid,
      currency: currency ?? this.currency,
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

    state = state.copyWith(
      phase: AppPhase.openShift,
      me: me,
      context: context,
      busy: false,
      clearError: true,
      defaultCustomer: walkIn,
      defaultAgent: defaultAgent,
      agents: agents,
      unitsByUid: unitsByUid,
      currency: currency,
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

  /// Strips the "Bad state:" prefix that StateError.toString() adds, so context
  /// errors (e.g. "This user is not assigned to any company.") read cleanly.
  String _friendly(Object e) => e is StateError ? e.message : e.toString();
}

final appControllerProvider =
    NotifierProvider<AppController, AppData>(AppController.new);
