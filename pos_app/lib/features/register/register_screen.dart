import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../app/theme.dart';
import '../../models/enums.dart';
import '../../offline/offline_controller.dart';
import '../../state/app_controller.dart';
import '../../state/cart_controller.dart';
import '../../widgets/ui.dart';
import '../offline/offline_widgets.dart';
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
            currency: app.currency,
          );
      // Surface an interrupted sale as soon as the till is back at the register,
      // rather than waiting until the cashier next reaches the payment sheet.
      // Offline it cannot be checked; the offline payment sheet warns instead.
      if (mounted && !ref.read(offlineControllerProvider).offline) {
        resolvePendingSale(context, ref);
      }
    });
  }

  @override
  Widget build(BuildContext context) {
    final mode = ref.watch(appControllerProvider.select((s) => s.mode));
    final offline = ref.watch(offlineControllerProvider.select((s) => s.offline));
    return Scaffold(
      backgroundColor: AppColors.bg,
      body: SafeArea(
        child: Column(
          children: [
            const _TopBar(),
            const _BranchFallbackStrip(),
            // ERP ADR-0068: the always-visible OFFLINE banner / connection strip.
            const OfflineStatusStrip(),
            Expanded(
              child: switch (mode) {
                BusinessMode.supermarket => const SupermarketRegister(),
                BusinessMode.pharmacy => const PharmacyRegister(),
                // Restaurant orders live on the server; offline phase 2 (one till
                // running the restaurant) is not built yet.
                BusinessMode.restaurant =>
                  offline ? const _RestaurantOffline() : const RestaurantRegister(),
              },
            ),
          ],
        ),
      ),
    );
  }
}

/// The restaurant register while the till is offline (ERP ADR-0068 phase 1).
///
/// Restaurant orders — tables, kitchen and bar slips, bills — live on the
/// server, so they cannot be worked offline yet. Nothing is lost: every open
/// order stays on the server exactly as it was and is billed when the till is
/// back. Quick sales can still be rung offline on the other register.
class _RestaurantOffline extends ConsumerWidget {
  const _RestaurantOffline();

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final app = ref.watch(appControllerProvider);
    final quick = app.modes.contains(BusinessMode.supermarket);
    return Center(
      child: ConstrainedBox(
        constraints: const BoxConstraints(maxWidth: 520),
        child: Padding(
          padding: const EdgeInsets.all(24),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              const Icon(Icons.cloud_off, size: 46, color: AppColors.danger),
              const SizedBox(height: 12),
              const Text('Restaurant orders need the server',
                  textAlign: TextAlign.center,
                  style: TextStyle(fontSize: 20, fontWeight: FontWeight.w700)),
              const SizedBox(height: 8),
              const Text(
                'Tables, kitchen and bar slips and bills are kept on the server, so they '
                'cannot be worked while the till is offline. Open orders are safe — nothing '
                'is lost — and are billed as usual once the till is back online.',
                textAlign: TextAlign.center,
                style: TextStyle(color: AppColors.ink2),
              ),
              const SizedBox(height: 18),
              if (quick)
                OrbixButton(
                  label: 'Use the quick-sale register',
                  icon: Icons.shopping_cart_outlined,
                  large: true,
                  onPressed: () =>
                      ref.read(appControllerProvider.notifier).setMode(BusinessMode.supermarket),
                )
              else
                const Text(
                  'This till runs the restaurant only, so it cannot sell until it is back online.',
                  textAlign: TextAlign.center,
                  style: TextStyle(color: AppColors.warn, fontWeight: FontWeight.w600),
                ),
            ],
          ),
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
    // A tablet in portrait has no room for the chips; the branch and session
    // are still in the session menu.
    final width = MediaQuery.sizeOf(context).width;
    final compact = width < 900;
    // A phone: the brand mark alone, and the mode switch as a dropdown.
    final phone = width < 600;
    return Container(
      height: 60,
      padding: EdgeInsets.symmetric(horizontal: phone ? 8 : 18),
      decoration: const BoxDecoration(
        color: AppColors.panel,
        border: Border(bottom: BorderSide(color: AppColors.line)),
      ),
      child: Row(
        children: [
          if (!phone) const Brand(),
          if (!phone) const SizedBox(width: 16),
          if (ctx != null && !compact)
            InfoChip(
                icon: Icons.store_outlined,
                label: ctx.branch.name,
                value: ctx.company.name),
          const SizedBox(width: 8),
          if (shift != null && !compact)
            InfoChip(
                icon: Icons.receipt_long_outlined,
                label: 'Session',
                value: shift.sessionNumber,
                ok: true),
          if (app.isWaiter && !compact)
            const InfoChip(
                icon: Icons.room_service_outlined, label: 'Waiter', value: 'Orders only'),
          if (app.isKitchenOnly && !compact)
            const InfoChip(
                icon: Icons.print_outlined, label: 'Kitchen printer', value: 'Prints slips'),
          const Spacer(),
          // A waiter works the restaurant only; the other modes sell over a till.
          if (!app.isWaiter && !app.isKitchenOnly)
            compact
                ? const _ModeMenu()
                : const Flexible(
                    flex: 4,
                    child: FittedBox(fit: BoxFit.scaleDown, child: _ModeSwitch())),
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

/// Switching mode mid-basket would orphan lines — guard it.
void _switchMode(BuildContext context, WidgetRef ref, BusinessMode m) {
  final mode = ref.read(appControllerProvider).mode;
  if (!ref.read(cartProvider).isEmpty && m != mode) {
    showToast(context, 'Finish or clear the current sale before switching mode.');
    return;
  }
  ref.read(appControllerProvider.notifier).setMode(m);
}

/// The mode switch on a narrow screen: the current mode, tap to pick another.
/// Scaled-down pills were unreadable on a phone.
class _ModeMenu extends ConsumerWidget {
  const _ModeMenu();
  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final mode = ref.watch(appControllerProvider.select((s) => s.mode));
    final modes = ref.watch(appControllerProvider.select((s) => s.modes));
    final label = Container(
      padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 8),
      decoration: BoxDecoration(
        color: AppColors.panel2,
        borderRadius: AppRadii.brPill,
        border: Border.all(color: AppColors.line),
      ),
      child: Row(
        mainAxisSize: MainAxisSize.min,
        children: [
          Text('${mode.glyph}  ${mode.label}',
              style: const TextStyle(
                  fontSize: 13.5, fontWeight: FontWeight.w600, color: AppColors.ink)),
          if (modes.length > 1) const Icon(Icons.arrow_drop_down, color: AppColors.ink2),
        ],
      ),
    );
    if (modes.length < 2) return label;
    return PopupMenuButton<BusinessMode>(
      tooltip: 'Change mode',
      onSelected: (m) => _switchMode(context, ref, m),
      itemBuilder: (_) => [
        for (final m in modes)
          CheckedPopupMenuItem<BusinessMode>(
              value: m, checked: m == mode, child: Text('${m.glyph}  ${m.label}')),
      ],
      child: label,
    );
  }
}

class _ModeSwitch extends ConsumerWidget {
  const _ModeSwitch();
  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final mode = ref.watch(appControllerProvider.select((s) => s.mode));
    // A restaurant-only till (J COB 2026-10-30) offers no other register.
    final modes = ref.watch(appControllerProvider.select((s) => s.modes));
    return Container(
      padding: const EdgeInsets.all(4),
      decoration: BoxDecoration(
        color: AppColors.panel2,
        borderRadius: AppRadii.brPill,
        border: Border.all(color: AppColors.line),
      ),
      child: Row(
        mainAxisSize: MainAxisSize.min,
        children: modes.map((m) {
          final active = mode == m;
          return InkWell(
            borderRadius: AppRadii.brPill,
            onTap: () => _switchMode(context, ref, m),
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

