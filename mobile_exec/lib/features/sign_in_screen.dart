import 'package:flutter/material.dart';

import '../app/app_scope.dart';
import '../app/theme.dart';
import '../core/api/api_exception.dart';
import '../core/config/hq_config.dart';
import '../widgets/kit.dart';

/// Sign in against the real ERP.
class SignInScreen extends StatefulWidget {
  const SignInScreen({super.key, required this.onSignedIn});

  final VoidCallback onSignedIn;

  @override
  State<SignInScreen> createState() => _SignInScreenState();
}

class _SignInScreenState extends State<SignInScreen> {
  final _user = TextEditingController();
  final _pass = TextEditingController();
  bool _obscure = true;
  bool _busy = false;
  String? _error;

  /// Taps on the footer, counting towards revealing the server address.
  ///
  /// The address is baked in at build time and a manager never needs to touch
  /// it, so it is not on the screen: an editable server field above the
  /// username invites someone to change it, and a phone pointed at the wrong
  /// server looks exactly like a broken app. Support still has to be able to
  /// move a client to a new address down a phone line, hence a gesture rather
  /// than no way in at all. Seven taps is the Android developer-options idiom
  /// — nobody arrives here by accident, and it is easy to describe out loud.
  static const _tapsToReveal = 7;
  int _taps = 0;
  DateTime? _firstTap;

  @override
  void dispose() {
    _user.dispose();
    _pass.dispose();
    super.dispose();
  }

  Future<void> _signIn() async {
    if (_user.text.trim().isEmpty || _pass.text.isEmpty) {
      setState(() => _error = 'Enter your username and password.');
      return;
    }
    setState(() {
      _busy = true;
      _error = null;
    });
    try {
      await AppScope.of(context).session.signIn(_user.text.trim(), _pass.text);
      if (mounted) widget.onSignedIn();
    } on ApiException catch (e) {
      setState(() => _error = e.statusCode == 401
          ? 'That username or password is not right.'
          : e.message);
    } catch (_) {
      setState(() => _error = 'Could not sign in. Try again.');
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  void _footerTapped() {
    final now = DateTime.now();
    // A slow, unrelated series of taps must not accumulate into the gesture.
    if (_firstTap == null ||
        now.difference(_firstTap!) > const Duration(seconds: 4)) {
      _firstTap = now;
      _taps = 0;
    }
    _taps++;
    if (_taps >= _tapsToReveal) {
      _taps = 0;
      _firstTap = null;
      setState(() {});
      _editHost();
      return;
    }
    setState(() {});
  }

  /// Counts down only once someone is plainly doing this on purpose, so a
  /// stray tap never hints that anything is there.
  String? get _tapHint {
    final left = _tapsToReveal - _taps;
    if (_taps < 4 || left <= 0) return null;
    return left == 1 ? '1 more' : '$left more';
  }

  Future<void> _editHost() async {
    final session = AppScope.of(context).session;
    final controller = TextEditingController(text: session.config.baseHost);
    final saved = await showDialog<String>(
      context: context,
      builder: (context) => AlertDialog(
        title: const Text('Server address'),
        content: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(
              'This phone is set to:',
              style: HqText.tiny,
            ),
            const SizedBox(height: 3),
            Text(
              HqConfig.normaliseHost(session.config.baseHost),
              style: const TextStyle(
                fontSize: 13.5,
                fontWeight: FontWeight.w700,
                color: HqColors.ink,
              ),
            ),
            const SizedBox(height: 14),
            TextField(
              controller: controller,
              autocorrect: false,
              keyboardType: TextInputType.url,
              decoration: const InputDecoration(
                hintText: 'https://erp.example.com',
              ),
            ),
            const SizedBox(height: 10),
            Text(
              'Address only — do not add /api/v1, the app adds it.',
              style: HqText.tiny,
            ),
          ],
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.of(context).pop(),
            child: const Text('Cancel'),
          ),
          FilledButton(
            onPressed: () => Navigator.of(context).pop(controller.text),
            child: const Text('Save'),
          ),
        ],
      ),
    );
    if (saved != null && saved.trim().isNotEmpty) {
      await session.setHost(saved);
      if (mounted) setState(() {});
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      body: Container(
        decoration: const BoxDecoration(gradient: HqSurfaces.heroGradient),
        child: SafeArea(
          child: ListView(
            padding: const EdgeInsets.fromLTRB(24, 40, 24, 28),
            children: [
              Center(
                child: Container(
                  width: 72,
                  height: 72,
                  decoration: BoxDecoration(
                    color: Colors.white.withValues(alpha: 0.14),
                    borderRadius: BorderRadius.circular(20),
                    border: Border.all(color: HqOnDark.hairline),
                  ),
                  alignment: Alignment.center,
                  child: const Text(
                    'H',
                    style: TextStyle(
                      fontSize: 34,
                      fontWeight: FontWeight.w800,
                      color: Colors.white,
                    ),
                  ),
                ),
              ),
              const SizedBox(height: 18),
              const Center(
                child: Text(
                  'OrbixHQ',
                  style: TextStyle(
                    fontSize: 28,
                    fontWeight: FontWeight.w700,
                    color: Colors.white,
                    letterSpacing: -0.5,
                  ),
                ),
              ),
              const SizedBox(height: 6),
              const Center(
                child: Text(
                  'Your business, in your pocket.',
                  style: TextStyle(fontSize: 13.5, color: HqOnDark.secondary),
                ),
              ),
              const SizedBox(height: 34),
              Container(
                padding: const EdgeInsets.all(20),
                decoration: BoxDecoration(
                  color: HqColors.panel,
                  borderRadius: BorderRadius.circular(18),
                ),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    HqField(
                      label: 'Username',
                      controller: _user,
                      hint: 'Your ERP username',
                      prefix: Icons.person_outline,
                    ),
                    const SizedBox(height: 16),
                    Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        const Text(
                          'Password',
                          style: TextStyle(
                            fontSize: 12.5,
                            fontWeight: FontWeight.w700,
                            color: HqColors.ink2,
                          ),
                        ),
                        const SizedBox(height: 7),
                        TextField(
                          controller: _pass,
                          obscureText: _obscure,
                          onSubmitted: (_) => _signIn(),
                          style: const TextStyle(
                            fontSize: 15,
                            color: HqColors.ink,
                            fontWeight: FontWeight.w500,
                          ),
                          decoration: InputDecoration(
                            prefixIcon: const Icon(Icons.lock_outline,
                                size: 19, color: HqColors.ink3),
                            suffixIcon: IconButton(
                              icon: Icon(
                                _obscure
                                    ? Icons.visibility_outlined
                                    : Icons.visibility_off_outlined,
                                size: 19,
                                color: HqColors.ink3,
                              ),
                              onPressed: () =>
                                  setState(() => _obscure = !_obscure),
                            ),
                          ),
                        ),
                      ],
                    ),
                    if (_error != null) ...[
                      const SizedBox(height: 14),
                      Container(
                        padding: const EdgeInsets.all(12),
                        decoration: BoxDecoration(
                          color: HqColors.badSoft,
                          borderRadius: BorderRadius.circular(HqRadii.sm),
                        ),
                        child: Row(
                          children: [
                            const Icon(Icons.error_outline_rounded,
                                size: 18, color: HqColors.bad),
                            const SizedBox(width: 9),
                            Expanded(
                              child: Text(
                                _error!,
                                style: const TextStyle(
                                  fontSize: 13,
                                  color: HqColors.bad,
                                  height: 1.3,
                                ),
                              ),
                            ),
                          ],
                        ),
                      ),
                    ],
                    const SizedBox(height: 20),
                    FilledButton(
                      onPressed: _busy ? null : _signIn,
                      child: _busy
                          ? const SizedBox(
                              width: 20,
                              height: 20,
                              child: CircularProgressIndicator(
                                strokeWidth: 2.2,
                                color: Colors.white,
                              ),
                            )
                          : const Text('Sign in'),
                    ),
                  ],
                ),
              ),
              const SizedBox(height: 20),
              Center(
                // The way back to the server address. Looks like a footnote,
                // because to everyone but support that is all it is.
                child: GestureDetector(
                  behavior: HitTestBehavior.opaque,
                  onTap: _busy ? null : _footerTapped,
                  child: Padding(
                    padding: const EdgeInsets.symmetric(
                        horizontal: 24, vertical: 8),
                    child: Column(
                      children: [
                        const Text(
                          'Protected by your company\'s server',
                          style: TextStyle(
                              fontSize: 11.5, color: HqOnDark.tertiary),
                        ),
                        if (_tapHint != null) ...[
                          const SizedBox(height: 4),
                          Text(
                            _tapHint!,
                            style: const TextStyle(
                                fontSize: 11, color: HqOnDark.tertiary),
                          ),
                        ],
                      ],
                    ),
                  ),
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }
}
