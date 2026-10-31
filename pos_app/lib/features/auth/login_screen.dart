import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../app/theme.dart';
import '../../core/config/app_config.dart';
import '../../core/version.dart';
import '../../models/restaurant.dart';
import '../../state/app_controller.dart';
import '../../widgets/on_screen_keyboard.dart';
import '../../widgets/ui.dart';
import '../restaurant/waiter_tiles.dart';
import 'setup_screen.dart';

class LoginScreen extends ConsumerStatefulWidget {
  const LoginScreen({super.key});
  @override
  ConsumerState<LoginScreen> createState() => _LoginScreenState();
}

/// Which field the on-screen keyboard types into.
enum _Field { user, pass, search }

class _LoginScreenState extends ConsumerState<LoginScreen> {
  final _user = TextEditingController();
  final _pass = TextEditingController();
  final _search = TextEditingController();
  final _userFocus = FocusNode();
  final _passFocus = FocusNode();
  final _searchFocus = FocusNode();

  /// The branch's waiters this till keeps for its sign-in tiles (J COB
  /// 2026-10-30). Empty on a till nobody with the restaurant has signed in to.
  List<RestaurantWaiter> _waiters = const [];
  _Field _active = _Field.user;
  bool _keyboard = false;

  /// Server setup hides behind seven taps on the version line, so nobody
  /// changes the server or printer by accident (owner 2026-10-30). Taps more
  /// than [_tapGap] apart start the count again.
  static const _setupTaps = 7;
  static const _tapGap = Duration(seconds: 2);
  int _taps = 0;
  DateTime? _lastTap;

  void _versionTapped() {
    final now = DateTime.now();
    final last = _lastTap;
    _taps = last != null && now.difference(last) <= _tapGap ? _taps + 1 : 1;
    _lastTap = now;
    if (_taps >= _setupTaps) {
      _taps = 0;
      _lastTap = null;
      showSetupDialog(context, ref);
    } else if (_taps >= 4) {
      final left = _setupTaps - _taps;
      showToast(context, '$left more ${left == 1 ? 'tap' : 'taps'} for Server setup.');
    }
  }

  @override
  void initState() {
    super.initState();
    _userFocus.addListener(() => _track(_userFocus, _Field.user));
    _passFocus.addListener(() => _track(_passFocus, _Field.pass));
    _searchFocus.addListener(() => _track(_searchFocus, _Field.search));
    _search.addListener(() => setState(() {}));
    AppConfig.load().then((cfg) {
      if (!mounted) return;
      setState(() => _waiters = cfg.waiters);
      // No tiles: the username is typed, so it takes the focus as it always did.
      if (cfg.waiters.isEmpty) _userFocus.requestFocus();
    });
  }

  /// Which field the keys type into. Focus alone never brings the keys up — a
  /// field takes focus on its own when the window is activated (J COB
  /// 2026-10-30: "keyboard is visible before tapping").
  void _track(FocusNode node, _Field field) {
    if (node.hasFocus && _active != field) setState(() => _active = field);
  }

  /// A field the waiter TAPPED brings the keys up (on a till with tiles; the
  /// keys come up once a name is picked or a field is tapped, never before).
  Widget _tapToType(Widget field) => Listener(
        onPointerDown: (_) {
          if (_waiters.isNotEmpty && !_keyboard) setState(() => _keyboard = true);
        },
        child: field,
      );

  @override
  void dispose() {
    _user.dispose();
    _pass.dispose();
    _search.dispose();
    _userFocus.dispose();
    _passFocus.dispose();
    _searchFocus.dispose();
    super.dispose();
  }

  void _submit() {
    if (_user.text.trim().isEmpty || _pass.text.isEmpty) return;
    ref.read(appControllerProvider.notifier).login(_user.text, _pass.text);
  }

  /// A waiter tapped their tile: their username is in, type the password.
  void _pick(RestaurantWaiter w) {
    setState(() {
      _user.text = w.username;
      _pass.clear();
      _active = _Field.pass;
      _keyboard = true;
    });
    _passFocus.requestFocus();
  }

  TextEditingController get _activeController => switch (_active) {
        _Field.user => _user,
        _Field.pass => _pass,
        _Field.search => _search,
      };

  void _enter() {
    switch (_active) {
      case _Field.user:
        _passFocus.requestFocus();
      case _Field.pass:
        _submit();
      case _Field.search:
        final hits = _waiters.where((w) => w.matches(_search.text)).toList();
        if (hits.length == 1) _pick(hits.first);
    }
  }

  @override
  Widget build(BuildContext context) {
    final app = ref.watch(appControllerProvider);
    return Scaffold(
      backgroundColor: AppColors.panel,
      body: LayoutBuilder(
        builder: (context, c) {
          final wide = c.maxWidth >= 900;
          final form = _form(app);
          final keyboard = _keyboard
              ? Padding(
                  padding: const EdgeInsets.fromLTRB(16, 0, 16, 12),
                  child: Center(
                    child: ConstrainedBox(
                      constraints: const BoxConstraints(maxWidth: 860),
                      child: OnScreenKeyboard(
                        controller: _activeController,
                        onEnter: _enter,
                        enterLabel: _active == _Field.pass ? 'Sign in' : 'Enter',
                      ),
                    ),
                  ),
                )
              : null;
          if (!wide) {
            return Column(
              children: [
                Expanded(
                  child: Center(
                    child: SingleChildScrollView(
                      child: Column(
                        mainAxisSize: MainAxisSize.min,
                        children: [
                          if (_waiters.isNotEmpty)
                            Padding(
                              padding: const EdgeInsets.fromLTRB(16, 16, 16, 0),
                              child: ConstrainedBox(
                                constraints: const BoxConstraints(maxHeight: 340),
                                child: _waiterPanel(),
                              ),
                            ),
                          form,
                        ],
                      ),
                    ),
                  ),
                ),
                ?keyboard,
              ],
            );
          }
          return Column(
            children: [
              Expanded(
                child: Row(
                  children: [
                    Expanded(child: Center(child: SingleChildScrollView(child: form))),
                    Expanded(
                      flex: 1,
                      child: _waiters.isEmpty
                          ? const _Aside()
                          : Padding(
                              padding: const EdgeInsets.all(24),
                              child: _waiterPanel(),
                            ),
                    ),
                  ],
                ),
              ),
              ?keyboard,
            ],
          );
        },
      ),
    );
  }

  /// Waiter tiles with a search box: tap your name, then type your password.
  Widget _waiterPanel() {
    return Column(
      mainAxisSize: MainAxisSize.min,
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        const Text('Waiters', style: TextStyle(fontSize: 20, fontWeight: FontWeight.w700)),
        const SizedBox(height: 4),
        const Text('Tap your name, then type your password.',
            style: TextStyle(color: AppColors.ink2)),
        const SizedBox(height: 12),
        if (_waiters.length > 6) ...[
          _tapToType(OrbixField(
            hint: 'Search waiters',
            controller: _search,
            focusNode: _searchFocus,
            prefixIcon: Icons.search,
          )),
          const SizedBox(height: 12),
        ],
        Flexible(
          child: SingleChildScrollView(
            // A gutter for the scrollbar, which draws over the right edge.
            padding: const EdgeInsets.only(right: 16),
            child: WaiterTiles(
              waiters: _waiters,
              query: _search.text,
              selected: _user.text.trim(),
              onPick: _pick,
            ),
          ),
        ),
      ],
    );
  }

  Widget _form(AppData app) {
    return ConstrainedBox(
      constraints: const BoxConstraints(maxWidth: 420),
      child: Padding(
        padding: const EdgeInsets.symmetric(horizontal: 28, vertical: 24),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            const Brand(large: true),
            const SizedBox(height: 26),
            const Text('Sign in',
                style: TextStyle(fontSize: 24, fontWeight: FontWeight.w700)),
            const SizedBox(height: 6),
            const Text('Open your till and start your shift.',
                style: TextStyle(color: AppColors.ink2)),
            const SizedBox(height: 24),
            _tapToType(OrbixField(
              label: 'Username',
              controller: _user,
              focusNode: _userFocus,
              prefixIcon: Icons.person_outline,
              textInputAction: TextInputAction.next,
              onSubmitted: (_) => _passFocus.requestFocus(),
            )),
            const SizedBox(height: 16),
            _tapToType(OrbixField(
              label: 'Password',
              controller: _pass,
              focusNode: _passFocus,
              obscure: true,
              prefixIcon: Icons.lock_outline,
              textInputAction: TextInputAction.go,
              onSubmitted: (_) => _submit(),
            )),
            if (app.error != null) ...[
              const SizedBox(height: 16),
              _ErrorBanner(app.error!),
            ],
            const SizedBox(height: 22),
            OrbixButton(
              label: 'Sign in',
              block: true,
              large: true,
              busy: app.busy,
              onPressed: app.busy ? null : _submit,
            ),
            const SizedBox(height: 16),
            Center(
              child: TextButton.icon(
                onPressed: () => setState(() => _keyboard = !_keyboard),
                icon: Icon(_keyboard ? Icons.keyboard_hide_outlined : Icons.keyboard_outlined,
                    size: 16),
                label: Text(_keyboard ? 'Hide keyboard' : 'Show keyboard'),
                style: TextButton.styleFrom(foregroundColor: AppColors.ink2),
              ),
            ),
            // Shown only when the ERP could not be reached — a new till, or a
            // server that moved — so it can be fixed without knowing the taps.
            if (app.serverUnreachable)
              Center(
                child: TextButton.icon(
                  onPressed: () => showSetupDialog(context, ref),
                  icon: const Icon(Icons.settings_outlined, size: 16),
                  label: const Text('Server setup'),
                  style: TextButton.styleFrom(foregroundColor: AppColors.ink2),
                ),
              ),
            const SizedBox(height: 4),
            Center(
              child: GestureDetector(
                behavior: HitTestBehavior.opaque,
                onTap: _versionTapped,
                child: const Padding(
                  padding: EdgeInsets.symmetric(horizontal: 16, vertical: 8),
                  child: Text('OrbixPOS $kAppVersion',
                      style: TextStyle(fontSize: 11.5, color: AppColors.ink3)),
                ),
              ),
            ),
          ],
        ),
      ),
    );
  }
}

class _ErrorBanner extends StatelessWidget {
  const _ErrorBanner(this.message);
  final String message;
  @override
  Widget build(BuildContext context) => Container(
        width: double.infinity,
        padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 11),
        decoration: BoxDecoration(
          color: AppColors.dangerSoft,
          borderRadius: AppRadii.brSm,
          border: Border.all(color: const Color(0xFFFECACA)),
        ),
        child: Row(children: [
          const Icon(Icons.error_outline, color: AppColors.danger, size: 18),
          const SizedBox(width: 10),
          Expanded(
              child: Text(message,
                  style: const TextStyle(
                      color: AppColors.danger,
                      fontWeight: FontWeight.w600,
                      fontSize: 13.5))),
        ]),
      );
}

class _Aside extends StatelessWidget {
  const _Aside();
  static const _ticks = [
    'Fast, scanner-first checkout',
    'Multi-tender: cash, card, mobile money, cheque & split',
    'Server-authoritative pricing, VAT & totals',
    'Reliable sales that ride out brief network blips',
    'Built-in X-read, close, and Z-read reconcile',
  ];

  @override
  Widget build(BuildContext context) {
    return Container(
      decoration: const BoxDecoration(
        gradient: LinearGradient(
          begin: Alignment.topLeft,
          end: Alignment.bottomRight,
          // Blue → navy, echoing the register's Enter-blue and PAY-navy accents.
          colors: [Color(0xFF1B6FD1), Color(0xFF14508F), Color(0xFF00296B)],
          stops: [0, .6, 1],
        ),
      ),
      child: Padding(
        padding: const EdgeInsets.all(60),
        child: Column(
          mainAxisAlignment: MainAxisAlignment.center,
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            const Text(
              'A fast, dependable till for every counter.',
              style: TextStyle(
                  color: Colors.white,
                  fontSize: 30,
                  height: 1.2,
                  fontWeight: FontWeight.w700),
            ),
            const SizedBox(height: 26),
            ..._ticks.map((t) => Padding(
                  padding: const EdgeInsets.only(bottom: 14),
                  child: Row(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Container(
                        width: 21,
                        height: 21,
                        alignment: Alignment.center,
                        decoration: BoxDecoration(
                            color: Colors.white.withValues(alpha: .18),
                            shape: BoxShape.circle),
                        child: const Icon(Icons.check,
                            size: 12, color: Colors.white),
                      ),
                      const SizedBox(width: 9),
                      Expanded(
                          child: Text(t,
                              style: const TextStyle(
                                  color: Color(0xFFD6E6FA), fontSize: 15.5))),
                    ],
                  ),
                )),
          ],
        ),
      ),
    );
  }
}
