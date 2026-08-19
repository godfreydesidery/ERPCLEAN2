import 'package:flutter/material.dart';

import '../app/theme.dart';
import '../data/mock.dart';

/// The first impression: OrbixHQ sign-in.
///
/// Full-bleed executive field (the hero gradient), the app mark and wordmark,
/// then one white card carrying the whole sign-in in four objects: which
/// server, who, the password, and the way in. Nothing else competes.
class SignInScreen extends StatefulWidget {
  const SignInScreen({super.key, required this.onSignedIn});

  /// Fired by both the Sign in button and the fingerprint shortcut.
  final VoidCallback onSignedIn;

  @override
  State<SignInScreen> createState() => _SignInScreenState();
}

class _SignInScreenState extends State<SignInScreen> {
  static const String _serverHost = 'tembo.orbixerp.com';

  final TextEditingController _username =
      TextEditingController(text: 'bmbaga');
  final TextEditingController _password =
      TextEditingController(text: 'TemboHQ2026');

  bool _obscure = true;

  @override
  void dispose() {
    _username.dispose();
    _password.dispose();
    super.dispose();
  }

  void _submit() {
    FocusScope.of(context).unfocus();
    widget.onSignedIn();
  }

  void _serverLocked() {
    final messenger = ScaffoldMessenger.of(context);
    messenger.hideCurrentSnackBar();
    messenger.showSnackBar(
      SnackBar(
        behavior: SnackBarBehavior.floating,
        backgroundColor: HqColors.ink,
        duration: const Duration(seconds: 3),
        margin: const EdgeInsets.fromLTRB(20, 0, 20, 24),
        shape: RoundedRectangleBorder(
          borderRadius: BorderRadius.circular(HqRadii.sm),
        ),
        content: const Text(
          'Your company server is set by the administrator.',
          style: TextStyle(fontSize: 13.5, color: Colors.white),
        ),
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: const Color(0xFF08201F),
      body: Container(
        decoration: const BoxDecoration(gradient: HqSurfaces.heroGradient),
        child: Stack(
          children: [
            // Two soft blooms lift the flat gradient without adding an object.
            const Positioned(
              top: -120,
              left: -90,
              child: _Glow(size: 320, color: Color(0xFF14B8A6), alpha: 0.20),
            ),
            const Positioned(
              bottom: -140,
              right: -110,
              child: _Glow(size: 360, color: Color(0xFF0F766E), alpha: 0.24),
            ),
            SafeArea(
              child: LayoutBuilder(
                builder: (context, constraints) {
                  final minH =
                      (constraints.maxHeight - 56).clamp(0.0, double.infinity);
                  return SingleChildScrollView(
                    padding: const EdgeInsets.fromLTRB(20, 28, 20, 28),
                    child: ConstrainedBox(
                      constraints: BoxConstraints(minHeight: minH),
                      child: Center(
                        child: ConstrainedBox(
                          constraints: const BoxConstraints(maxWidth: 420),
                          child: Column(
                            mainAxisSize: MainAxisSize.min,
                            crossAxisAlignment: CrossAxisAlignment.stretch,
                            children: [
                              const _Brandmark(),
                              const SizedBox(height: 28),
                              _card(),
                              const SizedBox(height: 20),
                              const Text(
                                'Protected by your company server  '
                                '·  v1.0.0 (demo)',
                                textAlign: TextAlign.center,
                                style: TextStyle(
                                  fontSize: 11.5,
                                  height: 1.4,
                                  color: HqOnDark.tertiary,
                                ),
                              ),
                            ],
                          ),
                        ),
                      ),
                    ),
                  );
                },
              ),
            ),
          ],
        ),
      ),
    );
  }

  Widget _card() {
    return Container(
      padding: const EdgeInsets.fromLTRB(18, 18, 18, 14),
      decoration: BoxDecoration(
        color: HqColors.panel,
        borderRadius: BorderRadius.circular(20),
        border: Border.all(color: Colors.white.withValues(alpha: 0.10)),
        boxShadow: [
          BoxShadow(
            color: const Color(0xFF04100F).withValues(alpha: 0.38),
            blurRadius: 34,
            offset: const Offset(0, 16),
          ),
        ],
      ),
      child: Column(
        mainAxisSize: MainAxisSize.min,
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          _serverRow(),
          const SizedBox(height: 18),
          const _FieldLabel('Username'),
          const SizedBox(height: 7),
          TextField(
            controller: _username,
            textInputAction: TextInputAction.next,
            autocorrect: false,
            style: const TextStyle(
              fontSize: 15.5,
              fontWeight: FontWeight.w600,
              color: HqColors.ink,
            ),
            decoration: _fieldStyle(
              hint: 'Your username',
              icon: Icons.person_outline_rounded,
            ),
          ),
          const SizedBox(height: 14),
          const _FieldLabel('Password'),
          const SizedBox(height: 7),
          TextField(
            controller: _password,
            obscureText: _obscure,
            obscuringCharacter: '•',
            autocorrect: false,
            enableSuggestions: false,
            textInputAction: TextInputAction.go,
            onSubmitted: (_) => _submit(),
            style: const TextStyle(
              fontSize: 15.5,
              fontWeight: FontWeight.w600,
              letterSpacing: 1.2,
              color: HqColors.ink,
            ),
            decoration: _fieldStyle(
              hint: 'Your password',
              icon: Icons.lock_outline_rounded,
            ).copyWith(
              suffixIcon: IconButton(
                onPressed: () => setState(() => _obscure = !_obscure),
                icon: Icon(
                  _obscure
                      ? Icons.visibility_outlined
                      : Icons.visibility_off_outlined,
                  size: 20,
                  color: HqColors.ink3,
                ),
                tooltip: _obscure ? 'Show password' : 'Hide password',
                splashRadius: 20,
              ),
            ),
          ),
          const SizedBox(height: 20),
          FilledButton(
            onPressed: _submit,
            child: const Row(
              mainAxisSize: MainAxisSize.min,
              children: [
                Text('Sign in'),
                SizedBox(width: 8),
                Icon(Icons.arrow_forward_rounded, size: 18),
              ],
            ),
          ),
          const SizedBox(height: 12),
          Row(
            children: [
              const Expanded(
                child: Divider(height: 1, thickness: 1, color: HqColors.line),
              ),
              Padding(
                padding: const EdgeInsets.symmetric(horizontal: 10),
                child: Text(
                  'or',
                  style: HqText.tiny.copyWith(fontWeight: FontWeight.w600),
                ),
              ),
              const Expanded(
                child: Divider(height: 1, thickness: 1, color: HqColors.line),
              ),
            ],
          ),
          const SizedBox(height: 4),
          Center(
            child: TextButton.icon(
              onPressed: _submit,
              icon: const Icon(Icons.fingerprint_rounded, size: 22),
              label: const Text('Use fingerprint instead'),
              style: TextButton.styleFrom(
                foregroundColor: HqColors.brand,
                padding: const EdgeInsets.symmetric(
                  horizontal: 14,
                  vertical: 10,
                ),
                textStyle: const TextStyle(
                  fontSize: 14.5,
                  fontWeight: FontWeight.w600,
                ),
              ),
            ),
          ),
        ],
      ),
    );
  }

  Widget _serverRow() {
    final radius = BorderRadius.circular(14);
    return Material(
      color: Colors.transparent,
      borderRadius: radius,
      child: InkWell(
        onTap: _serverLocked,
        borderRadius: radius,
        child: Container(
          padding: const EdgeInsets.fromLTRB(12, 11, 8, 11),
          decoration: BoxDecoration(
            color: HqColors.panel2,
            borderRadius: radius,
            border: Border.all(color: HqColors.line),
          ),
          child: Row(
            children: [
              Container(
                width: 32,
                height: 32,
                alignment: Alignment.center,
                decoration: BoxDecoration(
                  color: HqColors.brandSoft,
                  borderRadius: BorderRadius.circular(9),
                ),
                child: const Icon(
                  Icons.dns_outlined,
                  size: 17,
                  color: HqColors.brand,
                ),
              ),
              const SizedBox(width: 11),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  mainAxisSize: MainAxisSize.min,
                  children: [
                    Text(
                      kCompanyName,
                      maxLines: 1,
                      overflow: TextOverflow.ellipsis,
                      style: const TextStyle(
                        fontSize: 13.5,
                        fontWeight: FontWeight.w700,
                        height: 1.2,
                        color: HqColors.ink,
                      ),
                    ),
                    const SizedBox(height: 2),
                    const Text(
                      _serverHost,
                      maxLines: 1,
                      overflow: TextOverflow.ellipsis,
                      style: TextStyle(
                        fontSize: 11.5,
                        height: 1.25,
                        color: HqColors.ink3,
                      ),
                    ),
                  ],
                ),
              ),
              const SizedBox(width: 8),
              const Icon(Icons.edit_outlined, size: 17, color: HqColors.ink3),
              const SizedBox(width: 6),
            ],
          ),
        ),
      ),
    );
  }

  InputDecoration _fieldStyle({required String hint, required IconData icon}) {
    return InputDecoration(
      hintText: hint,
      hintStyle: const TextStyle(
        fontSize: 15,
        fontWeight: FontWeight.w400,
        letterSpacing: 0,
        color: HqColors.ink3,
      ),
      filled: true,
      fillColor: HqColors.panel2,
      isDense: true,
      contentPadding: const EdgeInsets.symmetric(horizontal: 12, vertical: 15),
      prefixIcon: Icon(icon, size: 19, color: HqColors.ink3),
      prefixIconConstraints: const BoxConstraints(minWidth: 42, minHeight: 42),
      border: OutlineInputBorder(
        borderRadius: BorderRadius.circular(12),
        borderSide: const BorderSide(color: HqColors.line),
      ),
      enabledBorder: OutlineInputBorder(
        borderRadius: BorderRadius.circular(12),
        borderSide: const BorderSide(color: HqColors.line),
      ),
      focusedBorder: OutlineInputBorder(
        borderRadius: BorderRadius.circular(12),
        borderSide: const BorderSide(color: HqColors.brand, width: 2),
      ),
    );
  }
}

// ---------------------------------------------------------------------------
// Pieces
// ---------------------------------------------------------------------------

/// App mark, wordmark, the one gold rule on the screen, and the tagline.
class _Brandmark extends StatelessWidget {
  const _Brandmark();

  @override
  Widget build(BuildContext context) {
    return Column(
      mainAxisSize: MainAxisSize.min,
      children: [
        Container(
          width: 72,
          height: 72,
          alignment: Alignment.center,
          decoration: BoxDecoration(
            gradient: HqSurfaces.brandGradient,
            borderRadius: BorderRadius.circular(20),
            border: Border.all(color: Colors.white.withValues(alpha: 0.22)),
            boxShadow: [
              BoxShadow(
                color: const Color(0xFF0B3B39).withValues(alpha: 0.55),
                blurRadius: 28,
                offset: const Offset(0, 12),
              ),
            ],
          ),
          child: const Text(
            'H',
            style: TextStyle(
              fontSize: 36,
              fontWeight: FontWeight.w700,
              height: 1.0,
              letterSpacing: -1,
              color: HqOnDark.primary,
            ),
          ),
        ),
        const SizedBox(height: 20),
        const Text(
          'OrbixHQ',
          style: TextStyle(
            fontSize: 28,
            fontWeight: FontWeight.w700,
            letterSpacing: -0.6,
            height: 1.1,
            color: HqOnDark.primary,
          ),
        ),
        const SizedBox(height: 10),
        Container(
          width: 30,
          height: 3,
          decoration: BoxDecoration(
            color: HqSurfaces.accent,
            borderRadius: BorderRadius.circular(2),
          ),
        ),
        const SizedBox(height: 12),
        const Text(
          'Your business, in your pocket.',
          textAlign: TextAlign.center,
          style: TextStyle(
            fontSize: 14,
            height: 1.35,
            color: HqOnDark.secondary,
          ),
        ),
      ],
    );
  }
}

class _FieldLabel extends StatelessWidget {
  const _FieldLabel(this.text);

  final String text;

  @override
  Widget build(BuildContext context) {
    return Text(
      text.toUpperCase(),
      style: const TextStyle(
        fontSize: 11,
        fontWeight: FontWeight.w700,
        letterSpacing: 0.8,
        color: HqColors.ink3,
      ),
    );
  }
}

/// A soft radial bloom behind the gradient. Decoration only.
class _Glow extends StatelessWidget {
  const _Glow({required this.size, required this.color, required this.alpha});

  final double size;
  final Color color;
  final double alpha;

  @override
  Widget build(BuildContext context) {
    return IgnorePointer(
      child: Container(
        width: size,
        height: size,
        decoration: BoxDecoration(
          shape: BoxShape.circle,
          gradient: RadialGradient(
            colors: [
              color.withValues(alpha: alpha),
              color.withValues(alpha: 0.0),
            ],
          ),
        ),
      ),
    );
  }
}
