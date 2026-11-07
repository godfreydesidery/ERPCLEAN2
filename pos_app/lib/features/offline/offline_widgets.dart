import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:intl/intl.dart';

import '../../app/theme.dart';
import '../../core/config/step_up_policy.dart';
import '../../offline/offline_controller.dart';
import '../../offline/offline_queue.dart';
import '../../offline/secret_cipher.dart';
import '../../state/app_controller.dart';
import '../../state/cart_controller.dart';
import '../../widgets/ui.dart';
import '../auth/approval_dialog.dart';
import 'sync_sheet.dart';

String _hm(DateTime? t) => t == null ? '—' : DateFormat('HH:mm').format(t.toLocal());

/// Switches the till offline, applying the rules of ERP ADR-0068 D-1, and
/// tells the cashier what happened. True when the till is now offline.
///
/// * Server truly unreachable — any signed-in cashier may switch.
/// * Server answering — a manager holding `POS.OFFLINE.SWITCH` approves with
///   their PASSWORD (the existing step-up dialog; never the 4-digit PIN).
/// * A till the admin has not allowed — never.
Future<bool> switchToOffline(BuildContext context, WidgetRef ref) async {
  final ctrl = ref.read(offlineControllerProvider.notifier);
  try {
    var decision = await ctrl.switchOffline();
    if (decision == OfflineSwitchDecision.needsManager) {
      if (!context.mounted) return false;
      final till = ctrl.currentTill;
      final approval = await showManagerApproval(context, ref,
          action: GatedAction.offlineSwitch,
          detail: 'The server is answering. Till ${till?.name ?? ''} will sell offline '
              'until it answers quickly again.');
      final uid = approval?.authoriserUid?.trim() ?? '';
      if (approval == null || uid.isEmpty) {
        if (context.mounted) showToast(context, 'Not switched — the till stays online.');
        return false;
      }
      decision = await ctrl.switchOffline(managerUid: uid);
    }
    if (!context.mounted) return decision == OfflineSwitchDecision.allowed;
    switch (decision) {
      case OfflineSwitchDecision.allowed:
        showToast(context,
            'Offline. Sales are kept on this till and sent when the server is back.',
            ok: true, duration: const Duration(seconds: 5));
        return true;
      case OfflineSwitchDecision.notAllowed:
        showToast(
            context,
            'This till is not allowed to sell offline. An administrator can allow it '
            'under POS › Tills.',
            duration: const Duration(seconds: 5));
        return false;
      case OfflineSwitchDecision.needsManager:
        showToast(context, 'Not switched — the till stays online.');
        return false;
    }
  } on SecretCipherException catch (e) {
    if (context.mounted) showToast(context, e.message, duration: const Duration(seconds: 6));
    return false;
  } on OfflineSaleRefused catch (e) {
    if (context.mounted) showToast(context, e.message);
    return false;
  }
}

/// The always-visible connection strip (ADR-0068 D-1): a clear OFFLINE banner
/// while offline, the "no connection — switch to offline?" offer when the
/// server cannot be reached, and the count of offline sales still to sync.
class OfflineStatusStrip extends ConsumerWidget {
  const OfflineStatusStrip({super.key, this.padding = const EdgeInsets.fromLTRB(12, 8, 12, 0)});
  final EdgeInsets padding;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final s = ref.watch(offlineControllerProvider);
    final app = ref.watch(appControllerProvider);
    if (app.isKitchenOnly) return const SizedBox.shrink();

    Widget bar({
      required Color bg,
      required Color fg,
      required Color border,
      required IconData icon,
      required List<Widget> lines,
      List<Widget> actions = const [],
    }) =>
        Padding(
          padding: padding,
          child: Container(
            width: double.infinity,
            padding: const EdgeInsets.fromLTRB(12, 8, 8, 8),
            decoration: BoxDecoration(
              color: bg,
              borderRadius: AppRadii.brSm,
              border: Border.all(color: border, width: 1.5),
            ),
            child: Row(
              children: [
                Icon(icon, color: fg, size: 20),
                const SizedBox(width: 10),
                Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    mainAxisSize: MainAxisSize.min,
                    children: lines,
                  ),
                ),
                ...actions,
              ],
            ),
          ),
        );

    TextStyle t(Color c, {bool bold = false, double size = 12.5}) =>
        TextStyle(color: c, fontSize: size, fontWeight: bold ? FontWeight.w800 : FontWeight.w600);

    final details = TextButton(
        onPressed: () => showOfflineSyncSheet(context),
        child: const Text('Details'));

    if (s.offline) {
      final cap = s.firstUnsyncedSoldAt?.add(kOfflineSellingLimit);
      final capSoon = cap != null && cap.difference(DateTime.now().toUtc()) < const Duration(hours: 3);
      return bar(
        bg: AppColors.dangerSoft,
        fg: AppColors.danger,
        border: const Color(0xFFFCA5A5),
        icon: Icons.cloud_off,
        lines: [
          Text('OFFLINE — sales are kept on this till until the server is back',
              style: t(AppColors.danger, bold: true, size: 13.5)),
          Text(
            '${s.waiting} waiting to sync · prices as of ${_hm(s.pricesAsOf)}'
            ' · cash, mobile money or card only — no discounts or refunds',
            style: t(AppColors.danger),
          ),
          if (capSoon)
            Text('Offline selling stops at ${_hm(cap)} unless the till reaches the server.',
                style: t(AppColors.warn, bold: true)),
          if (s.offlineSession && !s.serverBack)
            Text('Signed in offline: sales are sent after the next online sign-in.',
                style: t(AppColors.danger)),
          if (s.needsOnlineSignIn)
            Text(
              'The server is back. Finish this sale, then sign in online so the offline '
              'sales can be sent (by ${_hm(s.onlineBy)} at the latest).',
              style: t(AppColors.ok, bold: true),
            )
          else if (s.serverBack)
            Text(
              'The server is back. Finish this sale — the till returns online after it '
              '(by ${_hm(s.onlineBy)} at the latest).',
              style: t(AppColors.ok, bold: true),
            ),
        ],
        actions: [
          if (s.needsOnlineSignIn)
            Padding(
              padding: const EdgeInsets.only(left: 6),
              child: OrbixButton(
                label: 'Sign in online',
                icon: Icons.login,
                onPressed: ref.watch(cartProvider).isEmpty
                    ? () => ref.read(offlineControllerProvider.notifier).requestOnlineSignIn()
                    : null,
              ),
            ),
          details,
        ],
      );
    }

    if (s.serverDown && !app.isWaiter) {
      return bar(
        bg: AppColors.warnSoft,
        fg: AppColors.warn,
        border: const Color(0xFFFDE68A),
        icon: Icons.wifi_off,
        lines: [
          Text('No connection to the ERP.', style: t(AppColors.warn, bold: true, size: 13.5)),
          Text(
            s.tillAllowed
                ? 'Switch to offline to keep selling? Sales are kept on this till and sent '
                    'when the connection is back.'
                : 'This till is not set up to sell offline. Wait for the connection, or ask '
                    'an administrator to allow offline selling for it.',
            style: t(AppColors.warn),
          ),
        ],
        actions: [
          if (s.tillAllowed && app.phase == AppPhase.register)
            Padding(
              padding: const EdgeInsets.only(left: 6),
              child: OrbixButton(
                label: 'Switch to offline',
                icon: Icons.cloud_off,
                onPressed: () => switchToOffline(context, ref),
              ),
            ),
        ],
      );
    }

    if (s.waiting > 0 || s.held.isNotEmpty || !s.queueReadable) {
      final msg = !s.queueReadable
          ? 'Offline sales on this device cannot be opened. Ask your supervisor.'
          : s.syncing
              ? 'Sending ${s.waiting} offline sale${s.waiting == 1 ? '' : 's'}…'
              : s.waiting > 0
                  ? '${s.waiting} offline sale${s.waiting == 1 ? '' : 's'} waiting to sync'
                      '${s.lastSyncError == null ? '' : ' — ${s.lastSyncError}'}'
                  : '${s.held.length} offline sale${s.held.length == 1 ? ' was' : 's were'} '
                      'held for a manager to review';
      return bar(
        bg: AppColors.brandSoft,
        fg: AppColors.brandD,
        border: const Color(0xFFBFDBFE),
        icon: s.syncing ? Icons.sync : Icons.cloud_upload_outlined,
        lines: [Text(msg, style: t(AppColors.brandD))],
        actions: [details],
      );
    }
    return const SizedBox.shrink();
  }
}
