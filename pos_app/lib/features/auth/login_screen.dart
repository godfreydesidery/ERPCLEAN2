import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../app/theme.dart';
import '../../state/app_controller.dart';
import '../../widgets/ui.dart';
import 'setup_screen.dart';

class LoginScreen extends ConsumerStatefulWidget {
  const LoginScreen({super.key});
  @override
  ConsumerState<LoginScreen> createState() => _LoginScreenState();
}

class _LoginScreenState extends ConsumerState<LoginScreen> {
  final _user = TextEditingController();
  final _pass = TextEditingController();
  final _passFocus = FocusNode();

  @override
  void dispose() {
    _user.dispose();
    _pass.dispose();
    _passFocus.dispose();
    super.dispose();
  }

  void _submit() {
    if (_user.text.trim().isEmpty || _pass.text.isEmpty) return;
    ref.read(appControllerProvider.notifier).login(_user.text, _pass.text);
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
          if (!wide) return Center(child: SingleChildScrollView(child: form));
          return Row(
            children: [
              Expanded(child: Center(child: SingleChildScrollView(child: form))),
              Expanded(flex: 1, child: const _Aside()),
            ],
          );
        },
      ),
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
            OrbixField(
              label: 'Username',
              controller: _user,
              autofocus: true,
              prefixIcon: Icons.person_outline,
              textInputAction: TextInputAction.next,
              onSubmitted: (_) => _passFocus.requestFocus(),
            ),
            const SizedBox(height: 16),
            OrbixField(
              label: 'Password',
              controller: _pass,
              focusNode: _passFocus,
              obscure: true,
              prefixIcon: Icons.lock_outline,
              textInputAction: TextInputAction.go,
              onSubmitted: (_) => _submit(),
            ),
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
                onPressed: () => showSetupDialog(context, ref),
                icon: const Icon(Icons.settings_outlined, size: 16),
                label: const Text('Server setup'),
                style: TextButton.styleFrom(foregroundColor: AppColors.ink2),
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
