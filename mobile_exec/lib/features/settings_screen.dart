import 'package:flutter/material.dart';

import '../app/theme.dart';
import '../data/mock.dart';
import '../widgets/common.dart';

/// Profile and settings. Deliberately short — the client asked for the
/// minimum, so this holds identity, branch, and the few switches that matter.
class SettingsScreen extends StatefulWidget {
  const SettingsScreen({super.key, required this.onSignOut});

  final VoidCallback onSignOut;

  @override
  State<SettingsScreen> createState() => _SettingsScreenState();
}

class _SettingsScreenState extends State<SettingsScreen> {
  bool _biometric = true;
  bool _dailySummary = true;
  bool _lowStockAlerts = true;

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: HqColors.bg,
      body: SafeArea(
        bottom: false,
        child: ListView(
          padding: const EdgeInsets.fromLTRB(20, 8, 20, 28),
          children: [
            const _ProfileHeader(),
            const SizedBox(height: 22),
            const SectionLabel(text: 'WHERE YOU ARE WORKING'),
            const SizedBox(height: 10),
            HqCard(
              padding: const EdgeInsets.symmetric(horizontal: 16),
              child: Column(
                children: [
                  _Row(
                    icon: Icons.business_outlined,
                    title: 'Company',
                    value: kCompanyName,
                    locked: true,
                  ),
                  const Divider(height: 1),
                  _Row(
                    icon: Icons.storefront_outlined,
                    title: 'Branch',
                    value: kBranchName,
                    onTap: () => _pickBranch(context),
                  ),
                ],
              ),
            ),
            const SizedBox(height: 22),
            const SectionLabel(text: 'SECURITY'),
            const SizedBox(height: 10),
            HqCard(
              padding: const EdgeInsets.symmetric(horizontal: 16),
              child: Column(
                children: [
                  _Switch(
                    icon: Icons.fingerprint_rounded,
                    title: 'Fingerprint unlock',
                    subtitle: 'Open the app without typing a password',
                    value: _biometric,
                    onChanged: (v) => setState(() => _biometric = v),
                  ),
                ],
              ),
            ),
            const SizedBox(height: 22),
            const SectionLabel(text: 'NOTIFICATIONS'),
            const SizedBox(height: 10),
            HqCard(
              padding: const EdgeInsets.symmetric(horizontal: 16),
              child: Column(
                children: [
                  _Switch(
                    icon: Icons.sunny,
                    title: 'Daily summary',
                    subtitle: "Yesterday's sales, every morning at 06:30",
                    value: _dailySummary,
                    onChanged: (v) => setState(() => _dailySummary = v),
                  ),
                  const Divider(height: 1),
                  _Switch(
                    icon: Icons.inventory_2_outlined,
                    title: 'Low stock alerts',
                    subtitle: 'When an item falls below its reorder level',
                    value: _lowStockAlerts,
                    onChanged: (v) => setState(() => _lowStockAlerts = v),
                  ),
                ],
              ),
            ),
            const SizedBox(height: 22),
            const SectionLabel(text: 'ABOUT'),
            const SizedBox(height: 10),
            HqCard(
              padding: const EdgeInsets.symmetric(horizontal: 16),
              child: const Column(
                children: [
                  _Row(
                    icon: Icons.dns_outlined,
                    title: 'Server',
                    value: kServerHost,
                  ),
                  Divider(height: 1),
                  _Row(
                    icon: Icons.info_outline_rounded,
                    title: 'Version',
                    value: '1.0.0 — demo build',
                  ),
                ],
              ),
            ),
            const SizedBox(height: 24),
            OutlinedButton.icon(
              onPressed: widget.onSignOut,
              icon: const Icon(Icons.logout_rounded, size: 19),
              label: const Text('Sign out'),
              style: OutlinedButton.styleFrom(
                foregroundColor: HqColors.bad,
                side: const BorderSide(color: HqColors.line2),
              ),
            ),
            const SizedBox(height: 16),
            Center(
              child: Text(
                'This is a look-and-feel demo.\n'
                'It is not connected to the ERP.',
                textAlign: TextAlign.center,
                style: HqText.tiny,
              ),
            ),
          ],
        ),
      ),
    );
  }

  void _pickBranch(BuildContext context) {
    showModalBottomSheet<void>(
      context: context,
      backgroundColor: Colors.transparent,
      builder: (context) => Container(
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
              const SizedBox(height: 12),
              for (final b in kBranches)
                ListTile(
                  contentPadding: EdgeInsets.zero,
                  leading: Icon(
                    b == kBranchName
                        ? Icons.radio_button_checked
                        : Icons.radio_button_unchecked,
                    color: b == kBranchName ? HqColors.brand : HqColors.ink3,
                  ),
                  title: Text(
                    b,
                    style: TextStyle(
                      fontSize: 15,
                      fontWeight: b == kBranchName
                          ? FontWeight.w700
                          : FontWeight.w500,
                      color: HqColors.ink,
                    ),
                  ),
                  onTap: () => Navigator.of(context).pop(),
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
  const _ProfileHeader();

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
            child: const Text(
              kUserInitials,
              style: TextStyle(
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
                const Text(
                  kUserName,
                  maxLines: 1,
                  overflow: TextOverflow.ellipsis,
                  style: TextStyle(
                    fontSize: 18,
                    fontWeight: FontWeight.w700,
                    color: Colors.white,
                  ),
                ),
                const SizedBox(height: 3),
                Text(
                  kUserRole,
                  maxLines: 1,
                  overflow: TextOverflow.ellipsis,
                  style: const TextStyle(
                    fontSize: 13,
                    color: HqOnDark.secondary,
                  ),
                ),
                const SizedBox(height: 1),
                Text(
                  kCompanyName,
                  maxLines: 1,
                  overflow: TextOverflow.ellipsis,
                  style: const TextStyle(
                    fontSize: 12,
                    color: HqOnDark.tertiary,
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
    this.locked = false,
  });

  final IconData icon;
  final String title;
  final String value;
  final VoidCallback? onTap;
  final bool locked;

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
      trailing: locked
          ? const Icon(Icons.lock_outline_rounded,
              size: 16, color: HqColors.ink3)
          : (onTap == null
              ? null
              : const Icon(Icons.chevron_right,
                  size: 20, color: HqColors.ink3)),
    );
  }
}

class _Switch extends StatelessWidget {
  const _Switch({
    required this.icon,
    required this.title,
    required this.subtitle,
    required this.value,
    required this.onChanged,
  });

  final IconData icon;
  final String title;
  final String subtitle;
  final bool value;
  final ValueChanged<bool> onChanged;

  @override
  Widget build(BuildContext context) {
    return SwitchListTile.adaptive(
      value: value,
      onChanged: onChanged,
      contentPadding: EdgeInsets.zero,
      activeThumbColor: HqColors.brand,
      secondary: Icon(icon, size: 21, color: HqColors.ink3),
      title: Text(
        title,
        style: const TextStyle(
          fontSize: 14.5,
          fontWeight: FontWeight.w600,
          color: HqColors.ink,
        ),
      ),
      subtitle: Text(subtitle, style: HqText.tiny),
    );
  }
}
