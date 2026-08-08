import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../app/theme.dart';
import '../../core/api/api_exception.dart';
import '../../core/config/step_up_policy.dart';
import '../../models/step_up.dart';
import '../../state/providers.dart';
import '../../widgets/ui.dart';

/// What a gated action is allowed to do next.
class ApprovalOutcome {
  const ApprovalOutcome._(this.allowed, this.approval);

  /// True when the action may proceed — either because the policy does not gate
  /// it, or because a manager approved it.
  final bool allowed;

  /// The approval, when one was actually obtained. Null when the action was not
  /// gated (nothing to stamp) or when it was refused.
  final AuthorityVerification? approval;

  /// The policy does not gate this action — proceed on the cashier's own rights.
  static const ApprovalOutcome notRequired = ApprovalOutcome._(true, null);

  /// The cashier cancelled, or the credentials were refused.
  static const ApprovalOutcome refused = ApprovalOutcome._(false, null);

  static ApprovalOutcome granted(AuthorityVerification a) =>
      ApprovalOutcome._(true, a);

  /// Name to stamp on the action, or null when there is nobody to name.
  String? get approverLabel => approval?.approverLabel;
}

/// Runs [action] through [kStepUpPolicy]: prompts for a manager only when the
/// policy says so, and reports back whether the caller may proceed.
///
/// Call sites therefore contain no policy of their own — they ask, and honour
/// the answer.
///
/// [detail] is a single sentence naming the specific thing being approved (the
/// item, the amount, the receipt number). [correlationId] is sent as the
/// request's `X-Request-Id`, so the audited step-up attempt can be tied to the
/// exact sale it was for in the server log.
Future<ApprovalOutcome> approveIfRequired(
  BuildContext context,
  WidgetRef ref, {
  required GatedAction action,
  String? detail,
  String? correlationId,
}) async {
  if (!stepUpRuleFor(action).requiresApproval) return ApprovalOutcome.notRequired;
  final approval = await showManagerApproval(context, ref,
      action: action, detail: detail, correlationId: correlationId);
  return approval == null
      ? ApprovalOutcome.refused
      : ApprovalOutcome.granted(approval);
}

/// Shows the manager step-up prompt unconditionally (ignoring the policy's
/// `requiresApproval` flag) and returns the approval, or null if the cashier
/// backed out.
///
/// Use this directly only when the SERVER has already said an approval is
/// needed — e.g. a discount it refused as over the ceiling. Everywhere else go
/// through [approveIfRequired] so the policy stays in one place.
Future<AuthorityVerification?> showManagerApproval(
  BuildContext context,
  WidgetRef ref, {
  required GatedAction action,
  String? detail,
  String? correlationId,
}) {
  return showDialog<AuthorityVerification>(
    context: context,
    barrierDismissible: false,
    builder: (_) => _ApprovalDialog(
      rule: stepUpRuleFor(action),
      detail: detail,
      correlationId: correlationId,
    ),
  );
}

class _ApprovalDialog extends ConsumerStatefulWidget {
  const _ApprovalDialog({
    required this.rule,
    this.detail,
    this.correlationId,
  });

  final StepUpRule rule;
  final String? detail;
  final String? correlationId;

  @override
  ConsumerState<_ApprovalDialog> createState() => _ApprovalDialogState();
}

class _ApprovalDialogState extends ConsumerState<_ApprovalDialog> {
  final _username = TextEditingController();
  final _password = TextEditingController();
  final _passwordFocus = FocusNode();
  bool _busy = false;

  /// The last refusal, shown inline. Server text only — the client never
  /// invents a reason, and never says which half of the credentials was wrong.
  String? _error;

  @override
  void dispose() {
    _username.dispose();
    _password.dispose();
    _passwordFocus.dispose();
    super.dispose();
  }

  Future<void> _submit() async {
    final username = _username.text.trim();
    final password = _password.text;
    if (username.isEmpty || password.isEmpty) {
      setState(() => _error = 'Enter the manager username and password.');
      return;
    }
    setState(() {
      _busy = true;
      _error = null;
    });
    try {
      final result = await ref.read(stepUpServiceProvider).verify(
            username: username,
            password: password,
            permissionCode: widget.rule.permissionCode,
            correlationId: widget.correlationId,
          );
      if (!mounted) return;
      if (result.authorised) {
        Navigator.pop(context, result);
        return;
      }
      // A refusal is a normal 200 — keep the dialog open so the manager can
      // retype without the cashier losing the sale behind it. Clear only the
      // password: retyping the username every time is how a queue forms.
      _password.clear();
      _passwordFocus.requestFocus();
      setState(() {
        _busy = false;
        _error = result.message;
      });
    } on ApiException catch (e) {
      if (!mounted) return;
      setState(() {
        _busy = false;
        _error = e.message;
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    return AlertDialog(
      shape: RoundedRectangleBorder(borderRadius: AppRadii.brLg),
      title: Row(children: [
        const Icon(Icons.verified_user_outlined, color: AppColors.brand),
        const SizedBox(width: 10),
        Flexible(child: Text(widget.rule.title)),
      ]),
      content: ConstrainedBox(
        constraints: const BoxConstraints(maxWidth: 420),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            Text(widget.rule.purpose,
                style: const TextStyle(color: AppColors.ink2)),
            if (widget.detail != null) ...[
              const SizedBox(height: 6),
              Text(widget.detail!,
                  style: const TextStyle(fontWeight: FontWeight.w600)),
            ],
            const SizedBox(height: 14),
            OrbixField(
              label: 'Manager username',
              controller: _username,
              autofocus: true,
              enabled: !_busy,
              textInputAction: TextInputAction.next,
              onSubmitted: (_) => _passwordFocus.requestFocus(),
            ),
            const SizedBox(height: 10),
            OrbixField(
              label: 'Manager password',
              controller: _password,
              focusNode: _passwordFocus,
              obscure: true,
              enabled: !_busy,
              textInputAction: TextInputAction.done,
              onSubmitted: (_) => _busy ? null : _submit(),
            ),
            const SizedBox(height: 10),
            const Text(
              'You stay signed in — this only checks the manager’s '
              'authority for this one action.',
              style: TextStyle(fontSize: 11.5, color: AppColors.ink3),
            ),
            if (_error != null) ...[
              const SizedBox(height: 12),
              Container(
                padding:
                    const EdgeInsets.symmetric(horizontal: 12, vertical: 10),
                decoration: BoxDecoration(
                  color: AppColors.dangerSoft,
                  borderRadius: AppRadii.brSm,
                  border: Border.all(color: const Color(0xFFFECACA)),
                ),
                child: Row(
                  children: [
                    const Icon(Icons.error_outline,
                        size: 16, color: AppColors.danger),
                    const SizedBox(width: 8),
                    Expanded(
                      child: Text(_error!,
                          style: const TextStyle(
                              color: AppColors.danger,
                              fontSize: 12.5,
                              fontWeight: FontWeight.w600)),
                    ),
                  ],
                ),
              ),
            ],
          ],
        ),
      ),
      actions: [
        TextButton(
            onPressed: _busy ? null : () => Navigator.pop(context),
            child: const Text('Cancel')),
        OrbixButton(
            label: 'Approve',
            icon: Icons.check,
            busy: _busy,
            onPressed: _busy ? null : _submit),
      ],
    );
  }
}
