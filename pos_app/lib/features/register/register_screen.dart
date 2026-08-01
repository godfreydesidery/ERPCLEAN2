import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../app/theme.dart';
import '../../models/enums.dart';
import '../../state/app_controller.dart';
import '../../state/cart_controller.dart';
import '../../widgets/ui.dart';
import '../payment/pending_sale_recovery.dart';
import '../session/session_menu.dart';
import 'pharmacy_register.dart';
import 'restaurant_register.dart';
import 'supermarket_register.dart';

class RegisterScreen extends ConsumerStatefulWidget {
  const RegisterScreen({super.key});
  @override
  ConsumerState<RegisterScreen> createState() => _RegisterScreenState();
}

class _RegisterScreenState extends ConsumerState<RegisterScreen> {
  @override
  void initState() {
    super.initState();
    // Seed the basket with the resolved defaults for this shift.
    WidgetsBinding.instance.addPostFrameCallback((_) {
      final app = ref.read(appControllerProvider);
      ref.read(cartProvider.notifier).start(
            customer: app.defaultCustomer,
            agent: app.defaultAgent,
            currency: app.currency,
          );
      // Surface an interrupted sale as soon as the till is back at the register,
      // rather than waiting until the cashier next reaches the payment sheet.
      if (mounted) resolvePendingSale(context, ref);
    });
  }

  @override
  Widget build(BuildContext context) {
    final mode = ref.watch(appControllerProvider.select((s) => s.mode));
    return Scaffold(
      backgroundColor: AppColors.bg,
      body: SafeArea(
        child: Column(
          children: [
            const _TopBar(),
            const _BranchFallbackStrip(),
            Expanded(
              child: switch (mode) {
                BusinessMode.supermarket => const SupermarketRegister(),
                BusinessMode.pharmacy => const PharmacyRegister(),
                BusinessMode.restaurant => const RestaurantRegister(),
              },
            ),
          ],
        ),
      ),
    );
  }
}

/// A non-blocking warning strip surfaced when the shift's branch was resolved by
/// fallback (the operator's intended branch couldn't be confirmed). Shown here so
/// a resumed session — which skips the open-shift screen — still warns the cashier.
class _BranchFallbackStrip extends ConsumerWidget {
  const _BranchFallbackStrip();
  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final ctx = ref.watch(appControllerProvider.select((s) => s.context));
    if (ctx == null || !ctx.branchFallback) return const SizedBox.shrink();
    return Padding(
      padding: const EdgeInsets.fromLTRB(12, 10, 12, 0),
      child: BranchFallbackBanner(ctx.branch.name),
    );
  }
}

class _TopBar extends ConsumerWidget {
  const _TopBar();

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final app = ref.watch(appControllerProvider);
    final ctx = app.context;
    final shift = app.shift;
    return Container(
      height: 60,
      padding: const EdgeInsets.symmetric(horizontal: 18),
      decoration: const BoxDecoration(
        color: AppColors.panel,
        border: Border(bottom: BorderSide(color: AppColors.line)),
      ),
      child: Row(
        children: [
          const Brand(),
          const SizedBox(width: 16),
          if (ctx != null)
            InfoChip(
                icon: Icons.store_outlined,
                label: ctx.branch.name,
                value: ctx.company.name),
          const SizedBox(width: 8),
          if (shift != null)
            InfoChip(
                icon: Icons.receipt_long_outlined,
                label: 'Session',
                value: shift.sessionNumber,
                ok: true),
          const Spacer(),
          const _ModeSwitch(),
          const Spacer(),
          IconButton(
            tooltip: 'Session menu',
            onPressed: () => openSessionMenu(context, ref),
            icon: const Icon(Icons.menu, color: AppColors.ink2),
          ),
          const SizedBox(width: 4),
          Avatar(app.me?.displayName ?? '?', size: 34),
        ],
      ),
    );
  }
}

class _ModeSwitch extends ConsumerWidget {
  const _ModeSwitch();
  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final mode = ref.watch(appControllerProvider.select((s) => s.mode));
    final cart = ref.watch(cartProvider);
    return Container(
      padding: const EdgeInsets.all(4),
      decoration: BoxDecoration(
        color: AppColors.panel2,
        borderRadius: AppRadii.brPill,
        border: Border.all(color: AppColors.line),
      ),
      child: Row(
        mainAxisSize: MainAxisSize.min,
        children: BusinessMode.values.map((m) {
          final active = mode == m;
          return InkWell(
            borderRadius: AppRadii.brPill,
            onTap: () {
              // Switching mode mid-basket would orphan lines — guard it.
              if (!cart.isEmpty && m != mode) {
                showToast(context,
                    'Finish or clear the current sale before switching mode.');
                return;
              }
              ref.read(appControllerProvider.notifier).setMode(m);
            },
            child: Container(
              padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 7),
              decoration: BoxDecoration(
                color: active ? AppColors.ink : Colors.transparent,
                borderRadius: AppRadii.brPill,
              ),
              child: Text('${m.glyph}  ${m.label}',
                  style: TextStyle(
                      fontSize: 13,
                      fontWeight: FontWeight.w600,
                      color: active ? Colors.white : AppColors.ink2)),
            ),
          );
        }).toList(),
      ),
    );
  }
}

