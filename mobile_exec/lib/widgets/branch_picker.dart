import 'package:flutter/material.dart';

import '../app/theme.dart';
import '../core/scope.dart';
import '../core/session.dart';

/// The branch picker sheet: the user's branches grouped by company, with an
/// "All branches" choice per company where they hold more than one branch.
///
/// [forAction] is set when an action needs one branch (receiving goods,
/// adjusting stock): the sheet then offers only this company's branches and
/// no "All branches". Completes with true when the user picked something.
Future<bool> showBranchPicker(
  BuildContext context,
  Session session, {
  String? forAction,
}) async {
  final picked = await showModalBottomSheet<bool>(
    context: context,
    isScrollControlled: true,
    backgroundColor: Colors.transparent,
    builder: (sheetContext) => _BranchSheet(
      session: session,
      forAction: forAction,
    ),
  );
  return picked ?? false;
}

class _BranchSheet extends StatelessWidget {
  const _BranchSheet({required this.session, this.forAction});

  final Session session;
  final String? forAction;

  @override
  Widget build(BuildContext context) {
    final action = forAction;
    final source = action == null ? session.branches : session.branchesInCompany;
    final groups = branchesByCompany(source);
    final named = session.spansCompanies && action == null;

    return Container(
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
            Text(
              action == null ? 'Choose a branch' : '$action at which branch?',
              style: HqText.title,
            ),
            const SizedBox(height: 4),
            Text(
              action != null
                  ? 'This is done at one branch. The app switches to the branch '
                      'you pick.'
                  : session.spansCompanies
                      ? 'Every figure follows what you pick, and the company it '
                          'belongs to.'
                      : 'Every figure follows what you pick.',
              style: HqText.tiny,
            ),
            const SizedBox(height: 12),
            Flexible(
              child: SingleChildScrollView(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    for (final g in groups) ...[
                      // Two companies can each have a "Main" branch: name the
                      // company whenever the list spans more than one.
                      if (named)
                        Padding(
                          padding: const EdgeInsets.only(top: 10, bottom: 2),
                          child: Text(
                            _companyTitle(g.companyUid),
                            style: HqText.label,
                          ),
                        ),
                      if (action == null &&
                          session.canSeeAllBranchesOf(g.companyUid))
                        _tile(
                          context,
                          title: 'All branches',
                          subtitle: '${g.branches.length} branches together',
                          active: session.allBranches &&
                              session.companyUid == g.companyUid,
                          onTap: () => session.showAllBranches(g.companyUid),
                        ),
                      for (final b in g.branches)
                        _tile(
                          context,
                          title: b.name,
                          subtitle: b.isDefault ? 'Your default' : null,
                          active: !session.allBranches &&
                              b.uid == session.activeBranch?.uid,
                          onTap: () => session.switchBranch(b),
                        ),
                    ],
                  ],
                ),
              ),
            ),
            const SizedBox(height: 8),
          ],
        ),
      ),
    );
  }

  String _companyTitle(String companyUid) {
    final name = session.companies[companyUid]?.name ?? '';
    return name.isNotEmpty ? name.toUpperCase() : 'COMPANY';
  }

  Widget _tile(
    BuildContext context, {
    required String title,
    String? subtitle,
    required bool active,
    required Future<void> Function() onTap,
  }) {
    return ListTile(
      contentPadding: EdgeInsets.zero,
      leading: Icon(
        active ? Icons.radio_button_checked : Icons.radio_button_unchecked,
        color: active ? HqColors.brand : HqColors.ink3,
      ),
      title: Text(
        title,
        style: TextStyle(
          fontSize: 15,
          fontWeight: active ? FontWeight.w700 : FontWeight.w500,
          color: HqColors.ink,
        ),
      ),
      subtitle: subtitle == null ? null : Text(subtitle, style: HqText.tiny),
      onTap: () async {
        final nav = Navigator.of(context);
        await onTap();
        nav.pop(true);
      },
    );
  }
}
