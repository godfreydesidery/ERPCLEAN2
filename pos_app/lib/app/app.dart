import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../features/auth/login_screen.dart';
import '../features/register/register_screen.dart';
import '../features/shift/open_shift_screen.dart';
import '../state/app_controller.dart';
import '../widgets/ui.dart';
import 'theme.dart';

class OrbixPosApp extends StatelessWidget {
  const OrbixPosApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'OrbixPOS',
      debugShowCheckedModeBanner: false,
      theme: buildAppTheme(),
      home: const RootRouter(),
    );
  }
}

/// Switches the visible screen on the app phase and kicks off bootstrap once.
class RootRouter extends ConsumerStatefulWidget {
  const RootRouter({super.key});
  @override
  ConsumerState<RootRouter> createState() => _RootRouterState();
}

class _RootRouterState extends ConsumerState<RootRouter> {
  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addPostFrameCallback(
        (_) => ref.read(appControllerProvider.notifier).bootstrap());
  }

  @override
  Widget build(BuildContext context) {
    final phase = ref.watch(appControllerProvider.select((s) => s.phase));
    final screen = switch (phase) {
      AppPhase.booting => const _BootScreen(),
      AppPhase.login => const LoginScreen(),
      AppPhase.openShift => const OpenShiftScreen(),
      AppPhase.register => const RegisterScreen(),
    };
    return AnimatedSwitcher(
      duration: const Duration(milliseconds: 180),
      child: KeyedSubtree(key: ValueKey(phase), child: screen),
    );
  }
}

class _BootScreen extends StatelessWidget {
  const _BootScreen();
  @override
  Widget build(BuildContext context) {
    return const Scaffold(
      body: Center(
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            Brand(large: true),
            SizedBox(height: 24),
            SizedBox(
                width: 24,
                height: 24,
                child: CircularProgressIndicator(strokeWidth: 2)),
          ],
        ),
      ),
    );
  }
}
