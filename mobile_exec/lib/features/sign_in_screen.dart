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

  Future<void> _editHost() async {
    final session = AppScope.of(context).session;
    final controller = TextEditingController(text: session.config.baseHost);
    final saved = await showDialog<String>(
      context: context,
      builder: (context) => AlertDialog(
        title: const Text('ERP server'),
        content: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
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
    final host = HqConfig.normaliseHost(AppScope.of(context).session.config.baseHost);

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
                    InkWell(
                      onTap: _busy ? null : _editHost,
                      borderRadius: BorderRadius.circular(8),
                      child: Padding(
                        padding: const EdgeInsets.symmetric(vertical: 4),
                        child: Row(
                          children: [
                            const Icon(Icons.dns_outlined,
                                size: 17, color: HqColors.ink3),
                            const SizedBox(width: 8),
                            Expanded(
                              child: Text(
                                host,
                                maxLines: 1,
                                overflow: TextOverflow.ellipsis,
                                style: const TextStyle(
                                  fontSize: 13,
                                  fontWeight: FontWeight.w600,
                                  color: HqColors.ink2,
                                ),
                              ),
                            ),
                            const Icon(Icons.edit_outlined,
                                size: 16, color: HqColors.ink3),
                          ],
                        ),
                      ),
                    ),
                    const Divider(height: 22),
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
              const Center(
                child: Text(
                  'Protected by your company\'s server',
                  style: TextStyle(fontSize: 11.5, color: HqOnDark.tertiary),
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }
}
