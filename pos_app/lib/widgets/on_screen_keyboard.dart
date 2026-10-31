import 'package:flutter/material.dart';

import '../app/theme.dart';

/// A touch keyboard for tills with no physical one (J COB 2026-10-30): types
/// into [controller] — whichever field the screen says is active — at its
/// caret. Letters, a symbols page (passwords carry them), shift for one capital,
/// space, backspace and Enter.
///
/// Wrapped in a [TextFieldTapRegion], so tapping a key counts as tapping the
/// field: the field keeps its focus and caret instead of losing them.
class OnScreenKeyboard extends StatefulWidget {
  const OnScreenKeyboard({
    super.key,
    required this.controller,
    this.onEnter,
    this.enterLabel = 'Enter',
    this.keyHeight = 46,
  });

  /// The field the keys type into; null disables the keys.
  final TextEditingController? controller;

  /// Enter: next field, or submit.
  final VoidCallback? onEnter;
  final String enterLabel;
  final double keyHeight;

  @override
  State<OnScreenKeyboard> createState() => _OnScreenKeyboardState();
}

class _OnScreenKeyboardState extends State<OnScreenKeyboard> {
  bool _shift = false;
  bool _symbols = false;

  static const _letters = ['1234567890', 'qwertyuiop', 'asdfghjkl', 'zxcvbnm'];
  static const _symbolRows = ['1234567890', r'!@#$%&*()-', r"_+=/\:;'?", '.,<>[]{}~^'];

  void _insert(String t) {
    final c = widget.controller;
    if (c == null) return;
    final text = c.text;
    final sel = c.selection;
    final start = sel.isValid ? sel.start : text.length;
    final end = sel.isValid ? sel.end : text.length;
    c.value = TextEditingValue(
      text: text.replaceRange(start, end, t),
      selection: TextSelection.collapsed(offset: start + t.length),
    );
    if (_shift) setState(() => _shift = false);
  }

  void _backspace() {
    final c = widget.controller;
    if (c == null || c.text.isEmpty) return;
    final text = c.text;
    final sel = c.selection;
    final start = sel.isValid ? sel.start : text.length;
    final end = sel.isValid ? sel.end : text.length;
    if (start != end) {
      c.value = TextEditingValue(
          text: text.replaceRange(start, end, ''),
          selection: TextSelection.collapsed(offset: start));
    } else if (start > 0) {
      c.value = TextEditingValue(
          text: text.replaceRange(start - 1, start, ''),
          selection: TextSelection.collapsed(offset: start - 1));
    }
  }

  @override
  Widget build(BuildContext context) {
    final rows = _symbols ? _symbolRows : _letters;
    final enabled = widget.controller != null;
    return TextFieldTapRegion(
      child: ExcludeFocus(
        child: Container(
          padding: const EdgeInsets.all(8),
          decoration: BoxDecoration(
            color: AppColors.panel2,
            borderRadius: AppRadii.brLg,
            border: Border.all(color: AppColors.line),
          ),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              for (var r = 0; r < rows.length; r++)
                _row([
                  for (final ch in rows[r].split(''))
                    _Key(
                      label: _shift && !_symbols ? ch.toUpperCase() : ch,
                      height: widget.keyHeight,
                      onTap: enabled ? () => _insert(_shift && !_symbols ? ch.toUpperCase() : ch) : null,
                    ),
                  if (r == 0)
                    _Key(
                      icon: Icons.backspace_outlined,
                      semantics: 'Backspace',
                      flex: 2,
                      height: widget.keyHeight,
                      onTap: enabled ? _backspace : null,
                    ),
                ]),
              _row([
                _Key(
                  label: _symbols ? 'abc' : '⇧',
                  semantics: _symbols ? 'Letters' : 'Shift',
                  flex: 2,
                  height: widget.keyHeight,
                  active: _shift && !_symbols,
                  onTap: () => setState(() {
                    if (_symbols) {
                      _symbols = false;
                    } else {
                      _shift = !_shift;
                    }
                  }),
                ),
                _Key(
                  label: _symbols ? 'abc' : '?123',
                  semantics: _symbols ? 'Letters' : 'Symbols',
                  flex: 2,
                  height: widget.keyHeight,
                  onTap: () => setState(() {
                    _symbols = !_symbols;
                    _shift = false;
                  }),
                ),
                _Key(label: '@', height: widget.keyHeight, onTap: enabled ? () => _insert('@') : null),
                _Key(
                  label: 'space',
                  semantics: 'Space',
                  flex: 6,
                  height: widget.keyHeight,
                  onTap: enabled ? () => _insert(' ') : null,
                ),
                _Key(label: '.', height: widget.keyHeight, onTap: enabled ? () => _insert('.') : null),
                _Key(
                  label: widget.enterLabel,
                  flex: 3,
                  height: widget.keyHeight,
                  primary: true,
                  onTap: widget.onEnter,
                ),
              ]),
            ],
          ),
        ),
      ),
    );
  }

  Widget _row(List<Widget> keys) => Padding(
        padding: const EdgeInsets.symmetric(vertical: 3),
        child: Row(children: keys),
      );
}

class _Key extends StatelessWidget {
  const _Key({
    this.label,
    this.icon,
    this.semantics,
    this.flex = 1,
    required this.height,
    this.onTap,
    this.active = false,
    this.primary = false,
  });

  final String? label;
  final IconData? icon;
  final String? semantics;
  final int flex;
  final double height;
  final VoidCallback? onTap;
  final bool active;
  final bool primary;

  @override
  Widget build(BuildContext context) {
    final bg = primary
        ? AppColors.brand
        : active
            ? AppColors.brandSoft
            : AppColors.panel;
    final fg = primary ? Colors.white : AppColors.ink;
    return Expanded(
      flex: flex,
      child: Padding(
        padding: const EdgeInsets.symmetric(horizontal: 3),
        child: Semantics(
          button: true,
          label: semantics ?? label,
          child: Material(
            color: onTap == null ? AppColors.panel2 : bg,
            borderRadius: AppRadii.brSm,
            child: InkWell(
              borderRadius: AppRadii.brSm,
              onTap: onTap,
              child: Container(
                height: height,
                alignment: Alignment.center,
                decoration: BoxDecoration(
                  borderRadius: AppRadii.brSm,
                  border: Border.all(color: AppColors.line2),
                ),
                child: icon != null
                    ? Icon(icon, size: 20, color: fg)
                    : Text(label ?? '',
                        style: TextStyle(fontSize: 17, fontWeight: FontWeight.w600, color: fg)),
              ),
            ),
          ),
        ),
      ),
    );
  }
}
