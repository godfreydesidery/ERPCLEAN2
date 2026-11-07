import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:intl/intl.dart';

import '../../app/theme.dart';
import '../../core/money.dart';
import '../../offline/offline_controller.dart';
import '../../offline/offline_models.dart';
import '../../offline/offline_providers.dart';
import '../../offline/offline_queue.dart';
import '../../state/app_controller.dart';
import '../../widgets/ui.dart';
import 'offline_widgets.dart';

/// The offline-sales screen (ADR-0068 D-5): how many sales wait, when the till
/// last synced, the sales the server held for a manager, and the switch.
Future<void> showOfflineSyncSheet(BuildContext context) =>
    showDialog(context: context, builder: (_) => const _SyncSheet());

class _SyncSheet extends ConsumerStatefulWidget {
  const _SyncSheet();
  @override
  ConsumerState<_SyncSheet> createState() => _SyncSheetState();
}

class _SyncSheetState extends ConsumerState<_SyncSheet> {
  bool _checking = false;

  static String _when(DateTime? t) =>
      t == null ? '—' : DateFormat('dd MMM, HH:mm').format(t.toLocal());

  Future<void> _sync() async {
    final err = await ref.read(offlineControllerProvider.notifier).syncNow();
    if (!mounted) return;
    final s = ref.read(offlineControllerProvider);
    showToast(
        context,
        err ??
            (s.waiting == 0
                ? 'All offline sales are on the server.'
                : '${s.waiting} still waiting — the till keeps trying.'),
        ok: err == null && s.waiting == 0);
  }

  Future<void> _check() async {
    setState(() => _checking = true);
    final ok = await ref.read(offlineControllerProvider.notifier).probe();
    if (!mounted) return;
    setState(() => _checking = false);
    showToast(context, ok ? 'The server answers.' : 'Still no answer from the server.', ok: ok);
  }

  @override
  Widget build(BuildContext context) {
    final s = ref.watch(offlineControllerProvider);
    final app = ref.watch(appControllerProvider);
    final queue = ref.read(offlineQueueProvider);
    final ctrl = ref.read(offlineControllerProvider.notifier);
    final cap = s.firstUnsyncedSoldAt?.add(kOfflineSellingLimit);

    Widget row(String label, String value, {Color? color}) => Padding(
          padding: const EdgeInsets.symmetric(vertical: 3),
          child: Row(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              SizedBox(
                  width: 150,
                  child: Text(label, style: const TextStyle(color: AppColors.ink2, fontSize: 13))),
              Expanded(
                child: Text(value,
                    style: TextStyle(
                        fontWeight: FontWeight.w600, fontSize: 13, color: color ?? AppColors.ink)),
              ),
            ],
          ),
        );

    return AlertDialog(
      shape: RoundedRectangleBorder(borderRadius: AppRadii.brLg),
      title: Row(children: [
        Icon(s.offline ? Icons.cloud_off : Icons.cloud_done_outlined,
            color: s.offline ? AppColors.danger : AppColors.ok),
        const SizedBox(width: 10),
        const Text('Offline sales'),
      ]),
      content: SizedBox(
        width: 460,
        child: SingleChildScrollView(
          child: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              row(
                  'Till is',
                  s.offline
                      ? 'OFFLINE since ${_when(s.startedAt)}'
                          '${s.reason == OfflineReason.manual ? ' (switched by a manager)' : ' (no connection)'}'
                      : 'Online',
                  color: s.offline ? AppColors.danger : AppColors.ok),
              row('Waiting to sync', '${s.waiting}'),
              if (cap != null)
                row('Offline selling stops', _when(cap),
                    color: cap.difference(DateTime.now().toUtc()) < const Duration(hours: 3)
                        ? AppColors.danger
                        : null),
              row('Last sync', _when(s.lastSyncAt)),
              if (s.lastSyncError != null)
                row('Last problem', s.lastSyncError!, color: AppColors.danger),
              row('Prices as of', _when(s.pricesAsOf)),
              row('Offline selling',
                  s.tillAllowed ? 'Allowed on this till' : 'Not allowed on this till'),
              row('Sales stored with', queue.cipherName),
              if (!s.queueReadable)
                row('Stored sales',
                    'Cannot be opened on this device (another Windows user, or the device '
                    'key changed). Nothing is written over them.',
                    color: AppColors.danger),
              if (s.held.isNotEmpty) ...[
                const SizedBox(height: 12),
                const SectionLabel('Held for a manager'),
                const Text(
                  'The server did not post these. They are on the manager’s offline review '
                  'list; nothing more to do on the till.',
                  style: TextStyle(color: AppColors.ink2, fontSize: 12),
                ),
                const SizedBox(height: 6),
                for (final h in s.held)
                  Container(
                    margin: const EdgeInsets.only(bottom: 6),
                    padding: const EdgeInsets.fromLTRB(10, 8, 4, 8),
                    decoration: BoxDecoration(
                      color: AppColors.warnSoft,
                      borderRadius: AppRadii.brSm,
                      border: Border.all(color: const Color(0xFFFDE68A)),
                    ),
                    child: Row(
                      children: [
                        Expanded(
                          child: Column(
                            crossAxisAlignment: CrossAxisAlignment.start,
                            children: [
                              Text(
                                  '${h.offlineNumber} · ${formatMoneyParts(h.grossTotal, h.currency)}'
                                  ' · ${_when(h.soldAt)}',
                                  style: const TextStyle(fontWeight: FontWeight.w700, fontSize: 12.5)),
                              Text(h.message,
                                  style: const TextStyle(color: AppColors.warn, fontSize: 12)),
                            ],
                          ),
                        ),
                        IconButton(
                          tooltip: 'Dismiss',
                          onPressed: () => ctrl.dismissHeld(h.offlineNumber),
                          icon: const Icon(Icons.close, size: 16),
                        ),
                      ],
                    ),
                  ),
              ],
            ],
          ),
        ),
      ),
      actions: [
        if (s.offline)
          TextButton.icon(
            onPressed: _checking ? null : _check,
            icon: const Icon(Icons.wifi_find, size: 18),
            label: const Text('Check the connection'),
          ),
        if (s.offline && s.serverBack)
          TextButton.icon(
            onPressed: () => ctrl.returnOnline(),
            icon: const Icon(Icons.cloud_done_outlined, size: 18),
            label: const Text('Back online now'),
          ),
        if (!s.offline && s.tillAllowed && app.phase == AppPhase.register && !app.isWaiter)
          TextButton.icon(
            onPressed: () async {
              final ok = await switchToOffline(context, ref);
              if (ok && context.mounted) Navigator.pop(context);
            },
            icon: const Icon(Icons.cloud_off, size: 18),
            label: const Text('Go offline'),
          ),
        if (!s.offline)
          TextButton.icon(
            onPressed: s.refreshing ? null : () => ctrl.refreshCatalog(),
            icon: const Icon(Icons.refresh, size: 18),
            label: Text(s.refreshing ? 'Refreshing prices…' : 'Refresh prices'),
          ),
        if (!s.offline)
          OrbixButton(
            label: s.syncing ? 'Syncing…' : 'Sync now',
            icon: Icons.cloud_upload_outlined,
            busy: s.syncing,
            onPressed: s.syncing ? null : _sync,
          ),
        OrbixButton(label: 'Close', kind: BtnKind.ghost, onPressed: () => Navigator.pop(context)),
      ],
    );
  }
}
