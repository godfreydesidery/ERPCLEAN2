import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import '../app/theme.dart';

/// Operational UI kit — the pieces the data-entry and report screens need.
/// Mockup only: nothing here performs a real action.

// ---------------------------------------------------------------------------
// Text input
// ---------------------------------------------------------------------------

class HqField extends StatelessWidget {
  const HqField({
    super.key,
    required this.label,
    this.hint,
    this.controller,
    this.keyboardType,
    this.prefix,
    this.suffixText,
    this.helper,
    this.maxLines = 1,
    this.readOnly = false,
    this.onTap,
    this.required = false,
  });

  final String label;
  final String? hint;
  final TextEditingController? controller;
  final TextInputType? keyboardType;
  final IconData? prefix;
  final String? suffixText;
  final String? helper;
  final int maxLines;
  final bool readOnly;
  final VoidCallback? onTap;
  final bool required;

  @override
  Widget build(BuildContext context) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        _FieldLabel(label: label, required: required),
        const SizedBox(height: 7),
        TextField(
          controller: controller,
          keyboardType: keyboardType,
          maxLines: maxLines,
          readOnly: readOnly,
          onTap: onTap,
          style: const TextStyle(
            fontSize: 15,
            color: HqColors.ink,
            fontWeight: FontWeight.w500,
          ),
          inputFormatters: keyboardType == TextInputType.number
              ? <TextInputFormatter>[FilteringTextInputFormatter.digitsOnly]
              : null,
          decoration: InputDecoration(
            hintText: hint,
            hintStyle: const TextStyle(color: HqColors.ink3, fontSize: 15),
            prefixIcon: prefix == null
                ? null
                : Icon(prefix, size: 19, color: HqColors.ink3),
            suffixText: suffixText,
            suffixStyle: const TextStyle(
              color: HqColors.ink2,
              fontSize: 13,
              fontWeight: FontWeight.w600,
            ),
          ),
        ),
        if (helper != null) ...[
          const SizedBox(height: 6),
          Text(helper!, style: HqText.tiny),
        ],
      ],
    );
  }
}

class _FieldLabel extends StatelessWidget {
  const _FieldLabel({required this.label, this.required = false});

  final String label;
  final bool required;

  @override
  Widget build(BuildContext context) {
    return Row(
      children: [
        Flexible(
          child: Text(
            label,
            maxLines: 1,
            overflow: TextOverflow.ellipsis,
            style: const TextStyle(
              fontSize: 12.5,
              fontWeight: FontWeight.w700,
              color: HqColors.ink2,
            ),
          ),
        ),
        if (required)
          const Text(
            ' *',
            style: TextStyle(
              fontSize: 12.5,
              fontWeight: FontWeight.w700,
              color: HqColors.bad,
            ),
          ),
      ],
    );
  }
}

// ---------------------------------------------------------------------------
// Dropdown
// ---------------------------------------------------------------------------

class HqDropdown extends StatelessWidget {
  const HqDropdown({
    super.key,
    required this.label,
    required this.items,
    required this.value,
    required this.onChanged,
    this.hint,
    this.required = false,
  });

  final String label;
  final List<String> items;
  final String? value;
  final ValueChanged<String?> onChanged;
  final String? hint;
  final bool required;

  @override
  Widget build(BuildContext context) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        _FieldLabel(label: label, required: required),
        const SizedBox(height: 7),
        DropdownButtonFormField<String>(
          initialValue: value,
          isExpanded: true,
          hint: Text(
            hint ?? 'Select',
            style: const TextStyle(color: HqColors.ink3, fontSize: 15),
          ),
          icon: const Icon(Icons.expand_more_rounded, color: HqColors.ink3),
          style: const TextStyle(
            fontSize: 15,
            color: HqColors.ink,
            fontWeight: FontWeight.w500,
          ),
          items: [
            for (final i in items)
              DropdownMenuItem<String>(
                value: i,
                child: Text(i, overflow: TextOverflow.ellipsis),
              ),
          ],
          onChanged: onChanged,
        ),
      ],
    );
  }
}

// ---------------------------------------------------------------------------
// Quantity stepper
// ---------------------------------------------------------------------------

class QtyStepper extends StatelessWidget {
  const QtyStepper({
    super.key,
    required this.value,
    required this.onChanged,
    this.allowNegative = false,
    this.unit,
  });

  final int value;
  final ValueChanged<int> onChanged;
  final bool allowNegative;
  final String? unit;

  @override
  Widget build(BuildContext context) {
    return Container(
      decoration: BoxDecoration(
        color: HqColors.panel,
        borderRadius: BorderRadius.circular(HqRadii.sm),
        border: Border.all(color: HqColors.line2),
      ),
      child: Row(
        children: [
          _StepButton(
            icon: Icons.remove_rounded,
            onTap: () {
              if (allowNegative || value > 0) onChanged(value - 1);
            },
          ),
          Expanded(
            child: Column(
              mainAxisSize: MainAxisSize.min,
              children: [
                Text(
                  '$value',
                  style: TextStyle(
                    fontSize: 22,
                    fontWeight: FontWeight.w700,
                    color: value < 0 ? HqColors.bad : HqColors.ink,
                  ),
                ),
                if (unit != null)
                  Text(unit!, style: HqText.tiny, maxLines: 1),
              ],
            ),
          ),
          _StepButton(
            icon: Icons.add_rounded,
            onTap: () => onChanged(value + 1),
          ),
        ],
      ),
    );
  }
}

class _StepButton extends StatelessWidget {
  const _StepButton({required this.icon, required this.onTap});

  final IconData icon;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    return Material(
      color: Colors.transparent,
      child: InkWell(
        onTap: onTap,
        borderRadius: BorderRadius.circular(HqRadii.sm),
        child: SizedBox(
          width: 54,
          height: 60,
          child: Icon(icon, size: 22, color: HqColors.brand),
        ),
      ),
    );
  }
}

// ---------------------------------------------------------------------------
// Action tile — the operations hub
// ---------------------------------------------------------------------------

class ActionTile extends StatelessWidget {
  const ActionTile({
    super.key,
    required this.icon,
    required this.title,
    required this.subtitle,
    required this.tint,
    this.onTap,
  });

  final IconData icon;
  final String title;
  final String subtitle;
  final Color tint;
  final VoidCallback? onTap;

  @override
  Widget build(BuildContext context) {
    return Material(
      color: HqColors.panel,
      borderRadius: BorderRadius.circular(16),
      child: InkWell(
        onTap: onTap,
        borderRadius: BorderRadius.circular(16),
        child: Container(
          padding: const EdgeInsets.all(16),
          decoration: BoxDecoration(
            borderRadius: BorderRadius.circular(16),
            border: Border.all(color: HqColors.line),
          ),
          child: Row(
            children: [
              Container(
                width: 44,
                height: 44,
                decoration: BoxDecoration(
                  color: tint.withValues(alpha: 0.10),
                  borderRadius: BorderRadius.circular(12),
                ),
                child: Icon(icon, color: tint, size: 22),
              ),
              const SizedBox(width: 14),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(
                      title,
                      style: const TextStyle(
                        fontSize: 15,
                        fontWeight: FontWeight.w600,
                        color: HqColors.ink,
                      ),
                    ),
                    const SizedBox(height: 2),
                    Text(
                      subtitle,
                      style: HqText.tiny,
                      maxLines: 2,
                      overflow: TextOverflow.ellipsis,
                    ),
                  ],
                ),
              ),
              const SizedBox(width: 8),
              const Icon(Icons.chevron_right, color: HqColors.ink3, size: 20),
            ],
          ),
        ),
      ),
    );
  }
}

// ---------------------------------------------------------------------------
// Search field
// ---------------------------------------------------------------------------

class HqSearchField extends StatelessWidget {
  const HqSearchField({
    super.key,
    required this.hint,
    this.onChanged,
    this.controller,
  });

  final String hint;
  final ValueChanged<String>? onChanged;
  final TextEditingController? controller;

  @override
  Widget build(BuildContext context) {
    return TextField(
      controller: controller,
      onChanged: onChanged,
      style: const TextStyle(fontSize: 15, color: HqColors.ink),
      decoration: InputDecoration(
        hintText: hint,
        hintStyle: const TextStyle(color: HqColors.ink3, fontSize: 15),
        prefixIcon: const Icon(Icons.search_rounded,
            size: 20, color: HqColors.ink3),
        contentPadding: const EdgeInsets.symmetric(vertical: 14),
      ),
    );
  }
}

// ---------------------------------------------------------------------------
// Filter chips row
// ---------------------------------------------------------------------------

class FilterChipsRow extends StatelessWidget {
  const FilterChipsRow({
    super.key,
    required this.options,
    required this.selected,
    required this.onSelected,
  });

  final List<String> options;
  final int selected;
  final ValueChanged<int> onSelected;

  @override
  Widget build(BuildContext context) {
    return SizedBox(
      height: 36,
      child: ListView.separated(
        scrollDirection: Axis.horizontal,
        itemCount: options.length,
        separatorBuilder: (_, __) => const SizedBox(width: 8),
        itemBuilder: (context, i) {
          final on = i == selected;
          return GestureDetector(
            onTap: () => onSelected(i),
            child: AnimatedContainer(
              duration: const Duration(milliseconds: 140),
              padding: const EdgeInsets.symmetric(horizontal: 15),
              alignment: Alignment.center,
              decoration: BoxDecoration(
                color: on ? HqColors.brand : HqColors.panel,
                borderRadius: BorderRadius.circular(20),
                border: Border.all(color: on ? HqColors.brand : HqColors.line2),
              ),
              child: Text(
                options[i],
                style: TextStyle(
                  fontSize: 13,
                  fontWeight: FontWeight.w600,
                  color: on ? Colors.white : HqColors.ink2,
                ),
              ),
            ),
          );
        },
      ),
    );
  }
}

// ---------------------------------------------------------------------------
// Share / export sheet
// ---------------------------------------------------------------------------

/// The share sheet the client asked for: every report goes out by WhatsApp or
/// email, as PDF or Excel. Mockup — it shows the flow, it does not send.
Future<void> showShareSheet(BuildContext context, String reportName) {
  return showModalBottomSheet<void>(
    context: context,
    backgroundColor: Colors.transparent,
    isScrollControlled: true,
    builder: (context) => _ShareSheet(reportName: reportName),
  );
}

class _ShareSheet extends StatefulWidget {
  const _ShareSheet({required this.reportName});

  final String reportName;

  @override
  State<_ShareSheet> createState() => _ShareSheetState();
}

class _ShareSheetState extends State<_ShareSheet> {
  int _format = 0;

  static const _formats = ['PDF', 'Excel', 'CSV'];

  void _send(String channel) {
    Navigator.of(context).pop();
    ScaffoldMessenger.of(context).showSnackBar(
      SnackBar(
        behavior: SnackBarBehavior.floating,
        backgroundColor: HqColors.ink,
        content: Text(
          '${widget.reportName} (${_formats[_format]}) ready to send by $channel',
        ),
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    return Container(
      decoration: const BoxDecoration(
        color: HqColors.panel,
        borderRadius: BorderRadius.vertical(top: Radius.circular(22)),
      ),
      padding: const EdgeInsets.fromLTRB(20, 12, 20, 8),
      child: SafeArea(
        top: false,
        child: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Center(
              child: Container(
                width: 38,
                height: 4,
                decoration: BoxDecoration(
                  color: HqColors.line2,
                  borderRadius: BorderRadius.circular(2),
                ),
              ),
            ),
            const SizedBox(height: 18),
            const Text('Share report', style: HqText.title),
            const SizedBox(height: 3),
            Text(
              widget.reportName,
              style: HqText.body,
              maxLines: 2,
              overflow: TextOverflow.ellipsis,
            ),
            const SizedBox(height: 18),
            const Text(
              'FORMAT',
              style: TextStyle(
                fontSize: 11,
                fontWeight: FontWeight.w700,
                color: HqColors.ink3,
                letterSpacing: 0.7,
              ),
            ),
            const SizedBox(height: 8),
            Row(
              children: [
                for (var i = 0; i < _formats.length; i++) ...[
                  Expanded(
                    child: GestureDetector(
                      onTap: () => setState(() => _format = i),
                      child: Container(
                        padding: const EdgeInsets.symmetric(vertical: 11),
                        alignment: Alignment.center,
                        decoration: BoxDecoration(
                          color: i == _format
                              ? HqColors.brandSoft
                              : HqColors.panel,
                          borderRadius: BorderRadius.circular(HqRadii.sm),
                          border: Border.all(
                            color: i == _format
                                ? HqColors.brand
                                : HqColors.line2,
                            width: i == _format ? 1.6 : 1,
                          ),
                        ),
                        child: Text(
                          _formats[i],
                          style: TextStyle(
                            fontSize: 13.5,
                            fontWeight: FontWeight.w700,
                            color: i == _format
                                ? HqColors.brand
                                : HqColors.ink2,
                          ),
                        ),
                      ),
                    ),
                  ),
                  if (i != _formats.length - 1) const SizedBox(width: 10),
                ],
              ],
            ),
            const SizedBox(height: 20),
            const Text(
              'SEND BY',
              style: TextStyle(
                fontSize: 11,
                fontWeight: FontWeight.w700,
                color: HqColors.ink3,
                letterSpacing: 0.7,
              ),
            ),
            const SizedBox(height: 10),
            _ShareRow(
              icon: Icons.chat_rounded,
              tint: const Color(0xFF25D366),
              title: 'WhatsApp',
              subtitle: 'Send to a contact or a group',
              onTap: () => _send('WhatsApp'),
            ),
            const SizedBox(height: 10),
            _ShareRow(
              icon: Icons.mail_outline_rounded,
              tint: const Color(0xFF2A78D6),
              title: 'Email',
              subtitle: 'Attach and send',
              onTap: () => _send('email'),
            ),
            const SizedBox(height: 10),
            _ShareRow(
              icon: Icons.download_rounded,
              tint: HqColors.ink2,
              title: 'Save to phone',
              subtitle: 'Keep a copy in Downloads',
              onTap: () => _send('download'),
            ),
            const SizedBox(height: 12),
          ],
        ),
      ),
    );
  }
}

class _ShareRow extends StatelessWidget {
  const _ShareRow({
    required this.icon,
    required this.tint,
    required this.title,
    required this.subtitle,
    required this.onTap,
  });

  final IconData icon;
  final Color tint;
  final String title;
  final String subtitle;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    return Material(
      color: Colors.transparent,
      child: InkWell(
        onTap: onTap,
        borderRadius: BorderRadius.circular(HqRadii.sm),
        child: Container(
          padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 12),
          decoration: BoxDecoration(
            borderRadius: BorderRadius.circular(HqRadii.sm),
            border: Border.all(color: HqColors.line),
          ),
          child: Row(
            children: [
              Container(
                width: 38,
                height: 38,
                decoration: BoxDecoration(
                  color: tint.withValues(alpha: 0.12),
                  borderRadius: BorderRadius.circular(10),
                ),
                child: Icon(icon, color: tint, size: 20),
              ),
              const SizedBox(width: 12),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(
                      title,
                      style: const TextStyle(
                        fontSize: 14.5,
                        fontWeight: FontWeight.w600,
                        color: HqColors.ink,
                      ),
                    ),
                    Text(subtitle, style: HqText.tiny, maxLines: 1),
                  ],
                ),
              ),
              const Icon(Icons.chevron_right, size: 18, color: HqColors.ink3),
            ],
          ),
        ),
      ),
    );
  }
}

// ---------------------------------------------------------------------------
// Success confirmation
// ---------------------------------------------------------------------------

Future<void> showDoneSheet(
  BuildContext context, {
  required String title,
  required String detail,
}) {
  return showModalBottomSheet<void>(
    context: context,
    backgroundColor: Colors.transparent,
    builder: (context) => Container(
      decoration: const BoxDecoration(
        color: HqColors.panel,
        borderRadius: BorderRadius.vertical(top: Radius.circular(22)),
      ),
      padding: const EdgeInsets.fromLTRB(24, 26, 24, 16),
      child: SafeArea(
        top: false,
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            Container(
              width: 68,
              height: 68,
              decoration: const BoxDecoration(
                color: HqColors.goodSoft,
                shape: BoxShape.circle,
              ),
              child: const Icon(Icons.check_rounded,
                  size: 36, color: HqColors.good),
            ),
            const SizedBox(height: 18),
            Text(title, style: HqText.title, textAlign: TextAlign.center),
            const SizedBox(height: 6),
            Text(detail, style: HqText.body, textAlign: TextAlign.center),
            const SizedBox(height: 22),
            FilledButton(
              onPressed: () => Navigator.of(context).pop(),
              child: const Text('Done'),
            ),
            const SizedBox(height: 6),
          ],
        ),
      ),
    ),
  );
}

// ---------------------------------------------------------------------------
// A labelled figure, used across the report screens
// ---------------------------------------------------------------------------

class FigureRow extends StatelessWidget {
  const FigureRow({
    super.key,
    required this.label,
    required this.value,
    this.emphasise = false,
    this.valueColor,
  });

  final String label;
  final String value;
  final bool emphasise;
  final Color? valueColor;

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 7),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Expanded(
            child: Text(
              label,
              style: TextStyle(
                fontSize: 14,
                color: emphasise ? HqColors.ink : HqColors.ink2,
                fontWeight: emphasise ? FontWeight.w700 : FontWeight.w400,
              ),
            ),
          ),
          const SizedBox(width: 12),
          Text(
            value,
            style: TextStyle(
              fontSize: emphasise ? 16 : 14.5,
              fontWeight: FontWeight.w700,
              color: valueColor ?? HqColors.ink,
            ),
          ),
        ],
      ),
    );
  }
}
