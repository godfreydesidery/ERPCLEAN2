import 'package:flutter/material.dart';

import '../app/format.dart';
import '../app/theme.dart';
import '../data/mock.dart';
import '../widgets/common.dart';

/// Profile + settings.
///
/// This screen is where the security story is made visible. An owner who can
/// see that the app is locked, that amounts are hidden, and that his own
/// approval threshold is enforced by a fingerprint will trust every other
/// screen more. So the switches are not a list - they are the evidence.
class SettingsScreen extends StatefulWidget {
  const SettingsScreen({super.key, required this.onSignOut});

  final VoidCallback onSignOut;

  @override
  State<SettingsScreen> createState() => _SettingsScreenState();
}

class _SettingsScreenState extends State<SettingsScreen> {
  bool _fingerprint = true;
  bool _fingerprintAboveThreshold = true;
  bool _hideAmounts = true;
  bool _blockScreenshots = true;
  bool _approvalsWaiting = true;
  bool _onlyAboveThreshold = false;

  String _briefTime = '06:30';

  String get _threshold => tzsExact(kUser.personalApprovalThreshold);

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: HqColors.bg,
      body: SafeArea(
        bottom: false,
        child: ListView(
          padding: const EdgeInsets.fromLTRB(18, 18, 18, 34),
          children: [
            const _ProfileHeader(),
            const SizedBox(height: 22),

            // ---------------------------------------------------------------
            // SCOPE
            // ---------------------------------------------------------------
            const SectionLabel(text: 'Scope', trailing: 'Company-wide'),
            _Group(
              children: [
                _SettingRow(
                  icon: Icons.apartment_rounded,
                  title: 'Company',
                  subtitle: 'Consolidation is company-wide - all '
                      '${kUser.branches.length} branches',
                  value: kCompanyName,
                  locked: true,
                ),
                _SettingRow(
                  icon: Icons.account_tree_rounded,
                  title: 'Branches',
                  subtitle: 'Dar es Salaam (HQ), Arusha, Mwanza and 5 more',
                  value: 'All branches',
                  onTap: _openBranches,
                ),
              ],
            ),
            const SizedBox(height: 20),

            // ---------------------------------------------------------------
            // SECURITY
            // ---------------------------------------------------------------
            const SectionLabel(text: 'Security', trailing: 'All protections on'),
            _Group(
              children: [
                _SwitchRow(
                  icon: Icons.fingerprint_rounded,
                  title: 'Fingerprint unlock',
                  subtitle: 'Asked for every time OrbixHQ opens',
                  value: _fingerprint,
                  onChanged: (v) => setState(() => _fingerprint = v),
                ),
                _SwitchRow(
                  icon: Icons.verified_user_rounded,
                  title: 'Require fingerprint above TZS 20M',
                  subtitle: 'Approvals at or above $_threshold ask again',
                  value: _fingerprintAboveThreshold,
                  onChanged: (v) =>
                      setState(() => _fingerprintAboveThreshold = v),
                ),
                _SwitchRow(
                  icon: Icons.visibility_off_rounded,
                  title: 'Hide amounts on the lock screen',
                  subtitle: 'Notifications show the branch, never the figure',
                  value: _hideAmounts,
                  onChanged: (v) => setState(() => _hideAmounts = v),
                ),
                _SwitchRow(
                  icon: Icons.screenshot_monitor_rounded,
                  title: 'Screenshot blocking',
                  subtitle: 'Company numbers cannot leave the phone as an image',
                  value: _blockScreenshots,
                  onChanged: (v) => setState(() => _blockScreenshots = v),
                ),
                _SettingRow(
                  icon: Icons.phonelink_erase_rounded,
                  title: 'Sign out all my devices',
                  subtitle: 'Signed in on 2 devices - this phone and an iPad',
                  danger: true,
                  onTap: _confirmSignOutEverywhere,
                ),
              ],
            ),
            const SizedBox(height: 20),

            // ---------------------------------------------------------------
            // NOTIFICATIONS
            // ---------------------------------------------------------------
            const SectionLabel(text: 'Notifications'),
            _Group(
              children: [
                _SettingRow(
                  icon: Icons.wb_twilight_rounded,
                  title: 'Morning brief - $_briefTime',
                  subtitle: 'Yesterday, the month so far, and what needs you',
                  onTap: _openBriefTime,
                ),
                _SwitchRow(
                  icon: Icons.how_to_reg_rounded,
                  title: 'Approvals waiting',
                  subtitle: '${kApprovals.length} waiting now, '
                      'oldest $kApprovalsOldest',
                  value: _approvalsWaiting,
                  onChanged: (v) => setState(() => _approvalsWaiting = v),
                ),
                _SwitchRow(
                  icon: Icons.filter_alt_rounded,
                  title: 'Only above TZS 20M',
                  subtitle: 'Smaller approvals stay silent until the brief',
                  value: _onlyAboveThreshold,
                  enabled: _approvalsWaiting,
                  onChanged: (v) => setState(() => _onlyAboveThreshold = v),
                ),
              ],
            ),
            const SizedBox(height: 20),

            // ---------------------------------------------------------------
            // ABOUT
            // ---------------------------------------------------------------
            const SectionLabel(text: 'About'),
            const _Group(
              children: [
                _SettingRow(
                  icon: Icons.dns_rounded,
                  title: 'Server',
                  value: 'tembo.orbixerp.com',
                ),
                _SettingRow(
                  icon: Icons.info_outline_rounded,
                  title: 'Version',
                  value: '1.0.0 (demo build)',
                ),
                _SettingRow(
                  icon: Icons.schedule_rounded,
                  title: 'Data as of',
                  value: kAsOf,
                ),
              ],
            ),
            const SizedBox(height: 24),

            OutlinedButton.icon(
              onPressed: widget.onSignOut,
              icon: const Icon(Icons.logout_rounded, size: 20),
              label: const Text('Sign out'),
            ),
            const SizedBox(height: 12),
            Text(
              'OrbixHQ for $kCompanyName - $kCoverage',
              textAlign: TextAlign.center,
              style: HqText.tiny,
            ),
          ],
        ),
      ),
    );
  }

  // -------------------------------------------------------------------------
  // Sheets and dialogs
  // -------------------------------------------------------------------------

  Future<void> _openBranches() async {
    await _sheet(
      title: 'Branches you can see',
      note: 'Consolidation is company-wide - every figure in OrbixHQ already '
          'includes all ${kUser.branches.length}.',
      options: kUser.branches,
      selected: kUser.branches,
    );
  }

  Future<void> _openBriefTime() async {
    final picked = await _sheet(
      title: 'Morning brief',
      note: 'Delivered every working day, East Africa Time.',
      options: const ['06:00', '06:30', '07:00', '07:30'],
      selected: [_briefTime],
    );
    if (picked != null && mounted) setState(() => _briefTime = picked);
  }

  Future<String?> _sheet({
    required String title,
    required String note,
    required List<String> options,
    required List<String> selected,
  }) {
    return showModalBottomSheet<String>(
      context: context,
      backgroundColor: HqColors.panel,
      showDragHandle: true,
      shape: const RoundedRectangleBorder(
        borderRadius: BorderRadius.vertical(top: Radius.circular(22)),
      ),
      builder: (sheetContext) {
        return SafeArea(
          top: false,
          child: Padding(
            padding: const EdgeInsets.fromLTRB(18, 2, 18, 18),
            child: Column(
              mainAxisSize: MainAxisSize.min,
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(title, style: HqText.title),
                const SizedBox(height: 6),
                Text(note, style: HqText.body),
                const SizedBox(height: 14),
                for (final option in options)
                  _SheetOption(
                    label: option,
                    checked: selected.contains(option),
                    onTap: () => Navigator.of(sheetContext).pop(option),
                  ),
              ],
            ),
          ),
        );
      },
    );
  }

  Future<void> _confirmSignOutEverywhere() async {
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (dialogContext) => AlertDialog(
        backgroundColor: HqColors.panel,
        shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(18)),
        title: const Text('Sign out all devices?', style: HqText.title),
        content: const Text(
          'This phone and the iPad will both need a fresh sign-in and a '
          'fingerprint. Nothing on the server changes.',
          style: HqText.body,
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.of(dialogContext).pop(false),
            child: const Text('Keep me signed in'),
          ),
          TextButton(
            onPressed: () => Navigator.of(dialogContext).pop(true),
            style: TextButton.styleFrom(foregroundColor: HqColors.bad),
            child: const Text('Sign out everywhere'),
          ),
        ],
      ),
    );
    if (confirmed == true) widget.onSignOut();
  }
}

// ---------------------------------------------------------------------------
// The gradient identity header
// ---------------------------------------------------------------------------

class _ProfileHeader extends StatelessWidget {
  const _ProfileHeader();

  @override
  Widget build(BuildContext context) {
    return Container(
      width: double.infinity,
      padding: const EdgeInsets.fromLTRB(20, 22, 20, 18),
      decoration: BoxDecoration(
        gradient: HqSurfaces.heroGradient,
        borderRadius: BorderRadius.circular(22),
        boxShadow: HqSurfaces.hero,
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        mainAxisSize: MainAxisSize.min,
        children: [
          Row(
            crossAxisAlignment: CrossAxisAlignment.center,
            children: [
              Container(
                width: 62,
                height: 62,
                alignment: Alignment.center,
                decoration: BoxDecoration(
                  color: Colors.white.withValues(alpha: 0.10),
                  shape: BoxShape.circle,
                  border: Border.all(
                    color: HqSurfaces.accent.withValues(alpha: 0.85),
                    width: 1.6,
                  ),
                ),
                child: Text(
                  kUser.initials,
                  style: const TextStyle(
                    fontSize: 22,
                    fontWeight: FontWeight.w700,
                    letterSpacing: 0.5,
                    color: HqOnDark.primary,
                  ),
                ),
              ),
              const SizedBox(width: 16),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  mainAxisSize: MainAxisSize.min,
                  children: [
                    Text(
                      kUser.name,
                      maxLines: 1,
                      overflow: TextOverflow.ellipsis,
                      style: const TextStyle(
                        fontSize: 22,
                        fontWeight: FontWeight.w700,
                        letterSpacing: -0.4,
                        height: 1.1,
                        color: HqOnDark.primary,
                      ),
                    ),
                    const SizedBox(height: 4),
                    Text(
                      kUser.role,
                      maxLines: 1,
                      overflow: TextOverflow.ellipsis,
                      style: const TextStyle(
                        fontSize: 13.5,
                        fontWeight: FontWeight.w600,
                        color: HqOnDark.secondary,
                      ),
                    ),
                    const SizedBox(height: 6),
                    Row(
                      children: [
                        const Icon(
                          Icons.apartment_rounded,
                          size: 13,
                          color: HqOnDark.tertiary,
                        ),
                        const SizedBox(width: 5),
                        Expanded(
                          child: Text(
                            kUser.company,
                            maxLines: 1,
                            overflow: TextOverflow.ellipsis,
                            style: const TextStyle(
                              fontSize: 12.5,
                              color: HqOnDark.tertiary,
                            ),
                          ),
                        ),
                      ],
                    ),
                  ],
                ),
              ),
            ],
          ),
          const SizedBox(height: 18),
          const Divider(height: 1, thickness: 1, color: HqOnDark.hairline),
          const SizedBox(height: 14),
          Row(
            children: [
              const Expanded(
                child: _HeaderFact(
                  icon: Icons.lock_rounded,
                  label: 'Locked by fingerprint',
                  value: 'On this device',
                ),
              ),
              Container(width: 1, height: 32, color: HqOnDark.hairline),
              Expanded(
                child: _HeaderFact(
                  icon: Icons.account_tree_rounded,
                  label: 'Sees',
                  value: 'All ${kUser.branches.length} branches',
                  alignEnd: true,
                ),
              ),
            ],
          ),
        ],
      ),
    );
  }
}

class _HeaderFact extends StatelessWidget {
  const _HeaderFact({
    required this.icon,
    required this.label,
    required this.value,
    this.alignEnd = false,
  });

  final IconData icon;
  final String label;
  final String value;
  final bool alignEnd;

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding:
          EdgeInsets.only(left: alignEnd ? 16 : 0, right: alignEnd ? 0 : 16),
      child: Column(
        crossAxisAlignment:
            alignEnd ? CrossAxisAlignment.end : CrossAxisAlignment.start,
        mainAxisSize: MainAxisSize.min,
        children: [
          Row(
            mainAxisSize: MainAxisSize.min,
            children: [
              Icon(icon, size: 12, color: HqOnDark.tertiary),
              const SizedBox(width: 5),
              Flexible(
                child: Text(
                  label,
                  maxLines: 1,
                  overflow: TextOverflow.ellipsis,
                  style: const TextStyle(
                    fontSize: 11,
                    letterSpacing: 0.4,
                    color: HqOnDark.tertiary,
                  ),
                ),
              ),
            ],
          ),
          const SizedBox(height: 4),
          Text(
            value,
            maxLines: 1,
            overflow: TextOverflow.ellipsis,
            style: const TextStyle(
              fontSize: 13.5,
              fontWeight: FontWeight.w700,
              color: HqOnDark.primary,
            ),
          ),
        ],
      ),
    );
  }
}

// ---------------------------------------------------------------------------
// Grouped card
// ---------------------------------------------------------------------------

class _Group extends StatelessWidget {
  const _Group({required this.children});

  final List<Widget> children;

  @override
  Widget build(BuildContext context) {
    return HqCard(
      padding: EdgeInsets.zero,
      child: ClipRRect(
        borderRadius: BorderRadius.circular(15),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            for (var i = 0; i < children.length; i++) ...[
              if (i > 0)
                const Padding(
                  padding: EdgeInsets.only(left: 62),
                  child: Divider(height: 1, thickness: 1, color: HqColors.line),
                ),
              children[i],
            ],
          ],
        ),
      ),
    );
  }
}

class _IconTile extends StatelessWidget {
  const _IconTile({required this.icon, required this.tint});

  final IconData icon;
  final Color tint;

  @override
  Widget build(BuildContext context) {
    return Container(
      width: 34,
      height: 34,
      alignment: Alignment.center,
      decoration: BoxDecoration(
        color: tint.withValues(alpha: 0.09),
        borderRadius: BorderRadius.circular(HqRadii.sm),
        border: Border.all(color: tint.withValues(alpha: 0.18)),
      ),
      child: Icon(icon, size: 18, color: tint),
    );
  }
}

// ---------------------------------------------------------------------------
// A navigable / locked / dangerous row
// ---------------------------------------------------------------------------

class _SettingRow extends StatelessWidget {
  const _SettingRow({
    required this.icon,
    required this.title,
    this.subtitle,
    this.value,
    this.onTap,
    this.locked = false,
    this.danger = false,
  });

  final IconData icon;
  final String title;
  final String? subtitle;
  final String? value;
  final VoidCallback? onTap;
  final bool locked;
  final bool danger;

  @override
  Widget build(BuildContext context) {
    final tint = danger ? HqColors.bad : HqColors.brand;

    final row = Padding(
      padding: const EdgeInsets.fromLTRB(14, 13, 14, 13),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.center,
        children: [
          _IconTile(icon: icon, tint: tint),
          const SizedBox(width: 14),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              mainAxisSize: MainAxisSize.min,
              children: [
                Row(
                  children: [
                    Flexible(
                      child: Text(
                        title,
                        maxLines: 1,
                        overflow: TextOverflow.ellipsis,
                        style: TextStyle(
                          fontSize: 14.5,
                          fontWeight: FontWeight.w600,
                          height: 1.25,
                          color: danger ? HqColors.bad : HqColors.ink,
                        ),
                      ),
                    ),
                    if (locked) ...[
                      const SizedBox(width: 6),
                      const Icon(
                        Icons.lock_rounded,
                        size: 13,
                        color: HqColors.ink3,
                      ),
                    ],
                  ],
                ),
                if (value != null) ...[
                  const SizedBox(height: 2),
                  Text(
                    value!,
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                    style: const TextStyle(
                      fontSize: 13.5,
                      fontWeight: FontWeight.w700,
                      color: HqColors.brand,
                    ),
                  ),
                ],
                if (subtitle != null) ...[
                  const SizedBox(height: 3),
                  Text(
                    subtitle!,
                    maxLines: 2,
                    overflow: TextOverflow.ellipsis,
                    style: HqText.tiny,
                  ),
                ],
              ],
            ),
          ),
          if (onTap != null) ...[
            const SizedBox(width: 10),
            Icon(
              Icons.chevron_right_rounded,
              size: 22,
              color: danger ? HqColors.bad : HqColors.ink3,
            ),
          ],
        ],
      ),
    );

    if (onTap == null) return row;

    return Material(
      color: Colors.transparent,
      child: InkWell(onTap: onTap, child: row),
    );
  }
}

// ---------------------------------------------------------------------------
// A switch row - the security evidence
// ---------------------------------------------------------------------------

class _SwitchRow extends StatelessWidget {
  const _SwitchRow({
    required this.icon,
    required this.title,
    required this.subtitle,
    required this.value,
    required this.onChanged,
    this.enabled = true,
  });

  final IconData icon;
  final String title;
  final String subtitle;
  final bool value;
  final ValueChanged<bool> onChanged;
  final bool enabled;

  @override
  Widget build(BuildContext context) {
    final tint = enabled ? HqColors.brand : HqColors.ink3;

    return SwitchTheme(
      data: SwitchThemeData(
        thumbColor: WidgetStateProperty.resolveWith((states) {
          if (states.contains(WidgetState.disabled)) return HqColors.line2;
          return states.contains(WidgetState.selected)
              ? Colors.white
              : HqColors.ink3;
        }),
        trackColor: WidgetStateProperty.resolveWith((states) {
          if (states.contains(WidgetState.disabled)) return HqColors.panel2;
          return states.contains(WidgetState.selected)
              ? HqColors.brand
              : HqColors.line;
        }),
        trackOutlineColor: WidgetStateProperty.resolveWith((states) {
          return states.contains(WidgetState.selected)
              ? Colors.transparent
              : HqColors.line2;
        }),
      ),
      child: SwitchListTile.adaptive(
        value: value,
        onChanged: enabled ? onChanged : null,
        contentPadding: const EdgeInsets.fromLTRB(14, 6, 10, 6),
        visualDensity: VisualDensity.compact,
        secondary: _IconTile(icon: icon, tint: tint),
        title: Text(
          title,
          maxLines: 2,
          overflow: TextOverflow.ellipsis,
          style: TextStyle(
            fontSize: 14.5,
            fontWeight: FontWeight.w600,
            height: 1.25,
            color: enabled ? HqColors.ink : HqColors.ink3,
          ),
        ),
        subtitle: Padding(
          padding: const EdgeInsets.only(top: 3),
          child: Text(
            subtitle,
            maxLines: 2,
            overflow: TextOverflow.ellipsis,
            style: HqText.tiny,
          ),
        ),
      ),
    );
  }
}

// ---------------------------------------------------------------------------
// Bottom-sheet option
// ---------------------------------------------------------------------------

class _SheetOption extends StatelessWidget {
  const _SheetOption({
    required this.label,
    required this.checked,
    required this.onTap,
  });

  final String label;
  final bool checked;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    final radius = BorderRadius.circular(HqRadii.sm);

    return Padding(
      padding: const EdgeInsets.only(bottom: 8),
      child: Material(
        color: checked ? HqColors.brandSoft : HqColors.panel2,
        borderRadius: radius,
        child: InkWell(
          onTap: onTap,
          borderRadius: radius,
          child: Container(
            padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 13),
            decoration: BoxDecoration(
              borderRadius: radius,
              border:
                  Border.all(color: checked ? HqColors.brand : HqColors.line),
            ),
            child: Row(
              children: [
                Expanded(
                  child: Text(
                    label,
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                    style: TextStyle(
                      fontSize: 14.5,
                      fontWeight: checked ? FontWeight.w700 : FontWeight.w500,
                      color: checked ? HqColors.ink : HqColors.ink2,
                    ),
                  ),
                ),
                Icon(
                  checked
                      ? Icons.check_circle_rounded
                      : Icons.radio_button_unchecked_rounded,
                  size: 19,
                  color: checked ? HqColors.brand : HqColors.line2,
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }
}
