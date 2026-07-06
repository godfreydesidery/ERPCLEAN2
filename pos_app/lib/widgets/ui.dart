import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import '../app/theme.dart';

/// The OrbixPOS wordmark: ◆ Orbix**POS** (prototype `.brand`).
class Brand extends StatelessWidget {
  const Brand({super.key, this.large = false, this.onDark = false});
  final bool large;
  final bool onDark;

  @override
  Widget build(BuildContext context) {
    final markSize = large ? 34.0 : 22.0;
    final nameSize = large ? 28.0 : 18.0;
    final mark = onDark ? Colors.white : AppColors.brand;
    final ink = onDark ? Colors.white : AppColors.ink;
    return Row(
      mainAxisSize: MainAxisSize.min,
      children: [
        Text('◆',
            style: TextStyle(color: mark, fontSize: markSize, height: 1)),
        const SizedBox(width: 9),
        Text.rich(
          TextSpan(
            style: TextStyle(
                fontSize: nameSize,
                fontWeight: FontWeight.w600,
                letterSpacing: .2,
                color: ink),
            children: [
              const TextSpan(text: 'Orbix'),
              TextSpan(
                  text: 'POS',
                  style: TextStyle(
                      color: onDark ? Colors.white : AppColors.brand,
                      fontWeight: FontWeight.w800)),
            ],
          ),
        ),
      ],
    );
  }
}

enum BtnKind { primary, ghost, danger }

/// A flat button matching the prototype `.btn` variants.
class OrbixButton extends StatelessWidget {
  const OrbixButton({
    super.key,
    required this.label,
    this.onPressed,
    this.icon,
    this.kind = BtnKind.primary,
    this.block = false,
    this.large = false,
    this.busy = false,
  });

  final String label;
  final VoidCallback? onPressed;
  final IconData? icon;
  final BtnKind kind;
  final bool block;
  final bool large;
  final bool busy;

  @override
  Widget build(BuildContext context) {
    final enabled = onPressed != null && !busy;
    late Color bg, fg, border;
    switch (kind) {
      case BtnKind.primary:
        bg = AppColors.brand;
        fg = Colors.white;
        border = Colors.transparent;
        break;
      case BtnKind.ghost:
        bg = AppColors.panel;
        fg = AppColors.ink;
        border = AppColors.line2;
        break;
      case BtnKind.danger:
        bg = AppColors.dangerSoft;
        fg = AppColors.danger;
        border = const Color(0xFFFECACA);
        break;
    }
    final child = Row(
      mainAxisSize: block ? MainAxisSize.max : MainAxisSize.min,
      mainAxisAlignment: MainAxisAlignment.center,
      children: [
        if (busy)
          SizedBox(
              width: 16,
              height: 16,
              child: CircularProgressIndicator(strokeWidth: 2, color: fg))
        else if (icon != null)
          Padding(padding: const EdgeInsets.only(right: 8), child: Icon(icon, size: 18)),
        Flexible(
          child: Text(label,
              overflow: TextOverflow.ellipsis,
              style: TextStyle(
                  fontWeight: FontWeight.w600,
                  fontSize: large ? 16 : 14,
                  color: fg)),
        ),
      ],
    );
    return Opacity(
      opacity: enabled ? 1 : .45,
      child: Material(
        color: bg,
        borderRadius: AppRadii.brSm,
        child: InkWell(
          borderRadius: AppRadii.brSm,
          onTap: enabled ? onPressed : null,
          child: Container(
            padding: EdgeInsets.symmetric(
                horizontal: large ? 22 : 18, vertical: large ? 14 : 11),
            decoration: BoxDecoration(
              borderRadius: AppRadii.brSm,
              border: Border.all(color: border),
            ),
            child: child,
          ),
        ),
      ),
    );
  }
}

/// The green "Pay" call-to-action (`.btn--pay`) — small label above a big amount.
class PayButton extends StatelessWidget {
  const PayButton({
    super.key,
    required this.label,
    required this.amount,
    this.onPressed,
    this.icon = Icons.payments_outlined,
  });
  final String label;
  final String amount;
  final VoidCallback? onPressed;
  final IconData icon;

  @override
  Widget build(BuildContext context) {
    final enabled = onPressed != null;
    return Opacity(
      opacity: enabled ? 1 : .5,
      child: Material(
        color: AppColors.pay,
        borderRadius: AppRadii.brSm,
        child: InkWell(
          borderRadius: AppRadii.brSm,
          onTap: onPressed,
          child: Padding(
            padding: const EdgeInsets.symmetric(horizontal: 20, vertical: 12),
            child: Row(
              mainAxisAlignment: MainAxisAlignment.center,
              children: [
                Icon(icon, color: Colors.white, size: 20),
                const SizedBox(width: 10),
                Flexible(
                  child: Column(
                    mainAxisSize: MainAxisSize.min,
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text(label,
                          style: const TextStyle(
                              color: Colors.white70,
                              fontWeight: FontWeight.w600,
                              fontSize: 12,
                              height: 1.1)),
                      NumText(amount,
                          alignment: Alignment.centerLeft,
                          style: const TextStyle(
                              color: Colors.white,
                              fontWeight: FontWeight.w800,
                              fontSize: 18,
                              height: 1.1,
                              fontFeatures: kTabular)),
                    ],
                  ),
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }
}

/// A labelled text field (`.field`).
class OrbixField extends StatelessWidget {
  const OrbixField({
    super.key,
    this.label,
    this.controller,
    this.hint,
    this.obscure = false,
    this.keyboardType,
    this.onSubmitted,
    this.onChanged,
    this.autofocus = false,
    this.big = false,
    this.focusNode,
    this.textInputAction,
    this.prefixIcon,
    this.inputFormatters,
    this.enabled = true,
  });

  final String? label;
  final TextEditingController? controller;
  final String? hint;
  final bool obscure;
  final TextInputType? keyboardType;
  final ValueChanged<String>? onSubmitted;
  final ValueChanged<String>? onChanged;
  final bool autofocus;
  final bool big;
  final FocusNode? focusNode;
  final TextInputAction? textInputAction;
  final IconData? prefixIcon;
  final List<TextInputFormatter>? inputFormatters;
  final bool enabled;

  @override
  Widget build(BuildContext context) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      mainAxisSize: MainAxisSize.min,
      children: [
        if (label != null)
          Padding(
            padding: const EdgeInsets.only(bottom: 6),
            child: Text(label!,
                style: const TextStyle(
                    fontSize: 12.5,
                    fontWeight: FontWeight.w600,
                    color: AppColors.ink2)),
          ),
        TextField(
          controller: controller,
          focusNode: focusNode,
          obscureText: obscure,
          keyboardType: keyboardType,
          textInputAction: textInputAction,
          autofocus: autofocus,
          enabled: enabled,
          onSubmitted: onSubmitted,
          onChanged: onChanged,
          inputFormatters: inputFormatters,
          style: TextStyle(
              fontSize: big ? 22 : 15,
              fontWeight: big ? FontWeight.w700 : FontWeight.w400),
          decoration: InputDecoration(
            hintText: hint,
            prefixIcon: prefixIcon != null
                ? Icon(prefixIcon, color: AppColors.ink3, size: 20)
                : null,
            contentPadding: EdgeInsets.symmetric(
                horizontal: 14, vertical: big ? 14 : 12),
          ),
        ),
      ],
    );
  }
}

/// A topbar info chip (`.chip`): muted label + bold value, optional ok styling.
class InfoChip extends StatelessWidget {
  const InfoChip(
      {super.key, this.icon, required this.label, this.value, this.ok = false});
  final IconData? icon;
  final String label;
  final String? value;
  final bool ok;

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 13, vertical: 6),
      decoration: BoxDecoration(
        color: ok ? AppColors.okSoft : AppColors.panel2,
        borderRadius: AppRadii.brPill,
        border: Border.all(color: ok ? const Color(0xFFBBF7D0) : AppColors.line),
      ),
      child: Row(
        mainAxisSize: MainAxisSize.min,
        children: [
          if (icon != null) ...[
            Icon(icon, size: 13, color: ok ? AppColors.ok : AppColors.ink3),
            const SizedBox(width: 7),
          ],
          Text(label,
              style: TextStyle(
                  fontSize: 13, color: ok ? AppColors.ok : AppColors.ink2)),
          if (value != null) ...[
            const SizedBox(width: 5),
            Text(value!,
                style: TextStyle(
                    fontSize: 13,
                    fontWeight: FontWeight.w700,
                    color: ok ? AppColors.ok : AppColors.ink)),
          ],
        ],
      ),
    );
  }
}

/// An uppercase section label (`.section-label`).
class SectionLabel extends StatelessWidget {
  const SectionLabel(this.text, {super.key});
  final String text;
  @override
  Widget build(BuildContext context) => Padding(
        padding: const EdgeInsets.only(bottom: 12, top: 8),
        child: Text(text.toUpperCase(),
            style: const TextStyle(
                fontSize: 13,
                letterSpacing: .5,
                fontWeight: FontWeight.w700,
                color: AppColors.ink2)),
      );
}

/// A white rounded card (`.panel` + shadow).
class Panel extends StatelessWidget {
  const Panel(
      {super.key,
      required this.child,
      this.padding = const EdgeInsets.all(16),
      this.color = AppColors.panel});
  final Widget child;
  final EdgeInsetsGeometry padding;
  final Color color;
  @override
  Widget build(BuildContext context) => Container(
        padding: padding,
        decoration: BoxDecoration(
          color: color,
          borderRadius: AppRadii.brLg,
          border: Border.all(color: AppColors.line),
          boxShadow: AppShadows.card,
        ),
        child: child,
      );
}

/// A round avatar with initials (`.avatar`).
class Avatar extends StatelessWidget {
  const Avatar(this.name, {super.key, this.size = 42});
  final String name;
  final double size;
  @override
  Widget build(BuildContext context) {
    final initials = name.trim().isEmpty
        ? '?'
        : name
            .trim()
            .split(RegExp(r'\s+'))
            .take(2)
            .map((w) => w[0].toUpperCase())
            .join();
    return Container(
      width: size,
      height: size,
      alignment: Alignment.center,
      decoration: const BoxDecoration(
          color: AppColors.brand, shape: BoxShape.circle),
      child: Text(initials,
          style: TextStyle(
              color: Colors.white,
              fontWeight: FontWeight.w700,
              fontSize: size * 0.33)),
    );
  }
}

/// A toast at the bottom (`.toast`) — dark pill, optional ok (green) tint.
void showToast(BuildContext context, String message, {bool ok = false}) {
  final messenger = ScaffoldMessenger.of(context);
  messenger.clearSnackBars();
  messenger.showSnackBar(SnackBar(
    behavior: SnackBarBehavior.floating,
    backgroundColor: ok ? AppColors.payD : AppColors.ink,
    duration: const Duration(seconds: 3),
    width: 360,
    shape: RoundedRectangleBorder(borderRadius: AppRadii.brLg),
    content: Row(
      mainAxisSize: MainAxisSize.min,
      children: [
        Icon(ok ? Icons.check_circle_outline : Icons.info_outline,
            color: Colors.white, size: 18),
        const SizedBox(width: 10),
        Expanded(
            child: Text(message,
                style: const TextStyle(
                    color: Colors.white, fontWeight: FontWeight.w600))),
      ],
    ),
  ));
}

/// A non-blocking amber warning strip shown when the POS resolved a branch that
/// was NOT the operator's intended/active branch (see `PosContext.branchFallback`).
/// The cashier can still work — this just makes the fallback visible.
class BranchFallbackBanner extends StatelessWidget {
  const BranchFallbackBanner(this.branchName, {super.key});
  final String branchName;

  @override
  Widget build(BuildContext context) {
    return Container(
      width: double.infinity,
      padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 10),
      decoration: BoxDecoration(
        color: AppColors.warnSoft,
        borderRadius: AppRadii.brSm,
        border: Border.all(color: const Color(0xFFFDE68A)),
      ),
      child: Row(
        children: [
          const Icon(Icons.info_outline, size: 18, color: AppColors.warn),
          const SizedBox(width: 10),
          Expanded(
            child: Text(
              "Using branch $branchName — we couldn't confirm your usual "
              'branch. Check this is correct before selling.',
              style: const TextStyle(
                  color: AppColors.warn,
                  fontSize: 12.5,
                  fontWeight: FontWeight.w600),
            ),
          ),
        ],
      ),
    );
  }
}

/// Tabular number style helper.
TextStyle numStyle(
        {double size = 14,
        FontWeight weight = FontWeight.w600,
        Color color = AppColors.ink}) =>
    TextStyle(
        fontSize: size,
        fontWeight: weight,
        color: color,
        fontFeatures: kTabular);

/// A numeric value that never wraps or clips: renders [text] on a single line
/// and scales it DOWN to fit its (bounded) parent, so a large money amount
/// shrinks gracefully instead of overflowing/clipping a fixed-width grid cell.
/// Common (small) values render at their natural size. Give it a bounded width
/// (a fixed cell, or a [Flexible]/[Expanded] parent).
class NumText extends StatelessWidget {
  const NumText(this.text,
      {super.key, this.style, this.alignment = Alignment.centerRight});
  final String text;
  final TextStyle? style;
  final Alignment alignment;

  @override
  Widget build(BuildContext context) => FittedBox(
        fit: BoxFit.scaleDown,
        alignment: alignment,
        child: Text(text, maxLines: 1, softWrap: false, style: style),
      );
}
