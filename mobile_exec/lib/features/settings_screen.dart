import 'package:flutter/material.dart';

import '../app/app_scope.dart';
import '../app/theme.dart';
import '../app/version.dart';
import '../core/session.dart';
import '../widgets/common.dart';

/// Profile, branch and server. Short by design.
class SettingsScreen extends StatelessWidget {
  const SettingsScreen({super.key, required this.onSignOut});

  final VoidCallback onSignOut;

  @override
  Widget build(BuildContext context) {
    final scope = AppScope.of(context);
    final session = scope.session;

    return Scaffold(
      backgroundColor: HqColors.bg,
      body: SafeArea(
        bottom: false,
        child: ListView(
          padding: const EdgeInsets.fromLTRB(20, 8, 20, 28),
          children: [
            _ProfileHeader(session: session),
            const SizedBox(height: 22),
            const SectionLabel(text: 'WHERE YOU ARE WORKING'),
            const SizedBox(height: 10),
            HqCard(
              padding: const EdgeInsets.symmetric(horizontal: 16),
              child: Column(
                children: [
                  _Row(
                    icon: Icons.storefront_outlined,
                    title: 'Branch',
                    value: session.activeBranch?.name ?? 'None assigned',
                    onTap: session.branches.length < 2
                        ? null
                        : () => _pickBranch(context, session),
                  ),
                  const Divider(height: 1),
                  _Row(
                    icon: Icons.badge_outlined,
                    title: 'Signed in as',
                    value: session.username ?? '—',
                  ),
                ],
              ),
            ),
            const SizedBox(height: 22),
            SectionLabel(
              text: 'WHAT YOU CAN DO',
              trailing: session.isRoot
                  ? 'ROOT'
                  : '${session.permissions.length} PERMISSIONS',
            ),
            const SizedBox(height: 10),
            HqCard(
              padding: const EdgeInsets.symmetric(horizontal: 16),
              child: Column(
                children: [
                  _Capability(
                    label: 'See sales reports',
                    granted: session.can('SALES.INVOICE.VIEW'),
                  ),
                  const Divider(height: 1),
                  _Capability(
                    label: 'See stock',
                    granted: session.can('STOCK.VIEW'),
                  ),
                  const Divider(height: 1),
                  _Capability(
                    label: 'Adjust stock',
                    granted: session.can('STOCK.ADJUST'),
                  ),
                  const Divider(height: 1),
                  _Capability(
                    label: 'Receive goods',
                    granted: session.can('PURCHASE.RECEIVE.DIRECT'),
                  ),
                  const Divider(height: 1),
                  _Capability(
                    label: 'Create items',
                    granted: session.can('PRODUCT.MANAGE'),
                  ),
                  const Divider(height: 1),
                  _Capability(
                    label: 'Create suppliers',
                    granted: session.can('SUPPLIER.MANAGE'),
                  ),
                  const Divider(height: 1),
                  _Capability(
                    label: 'Close a till',
                    granted: session.can('POS.SESSION.CLOSE'),
                  ),
                ],
              ),
            ),
            const SizedBox(height: 22),
            const SectionLabel(text: 'ABOUT'),
            const SizedBox(height: 10),
            HqCard(
              padding: const EdgeInsets.symmetric(horizontal: 16),
              child: Column(
                children: [
                  _Row(
                    icon: Icons.dns_outlined,
                    title: 'Server',
                    value: session.config.baseHost,
                  ),
                  const Divider(height: 1),
                  const _Row(
                    icon: Icons.info_outline_rounded,
                    title: 'Version',
                    value: kAppVersion,
                  ),
                ],
              ),
            ),
            const SizedBox(height: 24),
            OutlinedButton.icon(
              onPressed: onSignOut,
              icon: const Icon(Icons.logout_rounded, size: 19),
              label: const Text('Sign out'),
              style: OutlinedButton.styleFrom(
                foregroundColor: HqColors.bad,
                side: const BorderSide(color: HqColors.line2),
              ),
            ),
          ],
        ),
      ),
    );
  }

  void _pickBranch(BuildContext context, Session session) {
    showModalBottomSheet<void>(
      context: context,
      backgroundColor: Colors.transparent,
      builder: (sheetContext) => Container(
        decoration: const BoxDecoration(
          color: HqColors.panel,
          borderRadius: BorderRadius.vertical(top: Radius.circular(22)),
        ),
        padding: const EdgeInsets.fromLTRB(20, 18, 20, 8),
        child: SafeArea(
          top: false,
          child: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              const Text('Choose a branch', style: HqText.title),
              const SizedBox(height: 4),
              Text(
                'Reports and stock follow the branch you pick.',
                style: HqText.tiny,
              ),
              const SizedBox(height: 12),
              for (final b in session.branches)
                ListTile(
                  contentPadding: EdgeInsets.zero,
                  leading: Icon(
                    b.uid == session.activeBranch?.uid
                        ? Icons.radio_button_checked
                        : Icons.radio_button_unchecked,
                    color: b.uid == session.activeBranch?.uid
                        ? HqColors.brand
                        : HqColors.ink3,
                  ),
                  title: Text(
                    b.name,
                    style: TextStyle(
                      fontSize: 15,
                      fontWeight: b.uid == session.activeBranch?.uid
                          ? FontWeight.w700
                          : FontWeight.w500,
                      color: HqColors.ink,
                    ),
                  ),
                  subtitle: b.isDefault
                      ? Text('Your default', style: HqText.tiny)
                      : null,
                  onTap: () {
                    session.switchBranch(b);
                    Navigator.of(sheetContext).pop();
                  },
                ),
              const SizedBox(height: 8),
            ],
          ),
        ),
      ),
    );
  }
}

class _ProfileHeader extends StatelessWidget {
  const _ProfileHeader({required this.session});

  final Session session;

  String get _name => session.displayName ?? session.username ?? 'Signed in';

  String get _initials {
    final parts = _name.trim().split(RegExp(r'\s+'));
    if (parts.isEmpty || parts.first.isEmpty) return '?';
    if (parts.length == 1) return parts.first.substring(0, 1).toUpperCase();
    return (parts.first.substring(0, 1) + parts.last.substring(0, 1))
        .toUpperCase();
  }

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.all(20),
      decoration: BoxDecoration(
        gradient: HqSurfaces.heroGradient,
        borderRadius: BorderRadius.circular(20),
        boxShadow: HqSurfaces.hero,
      ),
      child: Row(
        children: [
          Container(
            width: 56,
            height: 56,
            decoration: BoxDecoration(
              color: Colors.white.withValues(alpha: 0.15),
              shape: BoxShape.circle,
              border: Border.all(color: HqOnDark.hairline),
            ),
            alignment: Alignment.center,
            child: Text(
              _initials,
              style: const TextStyle(
                color: Colors.white,
                fontWeight: FontWeight.w700,
                fontSize: 19,
              ),
            ),
          ),
          const SizedBox(width: 16),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  _name,
                  maxLines: 1,
                  overflow: TextOverflow.ellipsis,
                  style: const TextStyle(
                    fontSize: 18,
                    fontWeight: FontWeight.w700,
                    color: Colors.white,
                  ),
                ),
                const SizedBox(height: 3),
                Text(
                  session.isRoot ? 'Administrator' : 'ERP user',
                  maxLines: 1,
                  overflow: TextOverflow.ellipsis,
                  style: const TextStyle(
                    fontSize: 13,
                    color: HqOnDark.secondary,
                  ),
                ),
              ],
            ),
          ),
        ],
      ),
    );
  }
}

class _Row extends StatelessWidget {
  const _Row({
    required this.icon,
    required this.title,
    required this.value,
    this.onTap,
  });

  final IconData icon;
  final String title;
  final String value;
  final VoidCallback? onTap;

  @override
  Widget build(BuildContext context) {
    return ListTile(
      onTap: onTap,
      contentPadding: EdgeInsets.zero,
      leading: Icon(icon, size: 21, color: HqColors.ink3),
      title: Text(
        title,
        style: const TextStyle(
          fontSize: 14.5,
          fontWeight: FontWeight.w600,
          color: HqColors.ink,
        ),
      ),
      subtitle: Text(
        value,
        maxLines: 1,
        overflow: TextOverflow.ellipsis,
        style: HqText.tiny,
      ),
      trailing: onTap == null
          ? null
          : const Icon(Icons.chevron_right, size: 20, color: HqColors.ink3),
    );
  }
}

class _Capability extends StatelessWidget {
  const _Capability({required this.label, required this.granted});

  final String label;
  final bool granted;

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 11),
      child: Row(
        children: [
          Icon(
            granted ? Icons.check_circle_rounded : Icons.remove_circle_outline,
            size: 19,
            color: granted ? HqColors.good : HqColors.ink3,
          ),
          const SizedBox(width: 12),
          Expanded(
            child: Text(
              label,
              style: TextStyle(
                fontSize: 14,
                color: granted ? HqColors.ink : HqColors.ink3,
                fontWeight: granted ? FontWeight.w500 : FontWeight.w400,
              ),
            ),
          ),
        ],
      ),
    );
  }
}
