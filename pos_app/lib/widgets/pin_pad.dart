import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import '../app/theme.dart';

/// What a waiter did with the PIN pad.
enum PinPadOutcome { done, usePassword }

/// A 4-digit PIN for [name] (V111): four dots and a number pad, submitted on
/// the fourth digit. [onPin] returns null when the PIN was accepted (the pad
/// closes with [PinPadOutcome.done]) or a message to show while the pad clears
/// for another try. Null when backed out.
Future<PinPadOutcome?> showPinPad(
  BuildContext context, {
  required String name,
  required Future<String?> Function(String pin) onPin,
}) {
  return showDialog<PinPadOutcome>(
    context: context,
    builder: (_) => _PinPad(name: name, onPin: onPin),
  );
}

class _PinPad extends StatefulWidget {
  const _PinPad({required this.name, required this.onPin});
  final String name;
  final Future<String?> Function(String pin) onPin;
  @override
  State<_PinPad> createState() => _PinPadState();
}

class _PinPadState extends State<_PinPad> {
  String _pin = '';
  String? _error;
  bool _busy = false;
  final _focus = FocusNode();

  @override
  void initState() {
    super.initState();
    _focus.requestFocus();
  }

  @override
  void dispose() {
    _focus.dispose();
    super.dispose();
  }

  Future<void> _key(String k) async {
    if (_busy) return;
    setState(() {
      _error = null;
      if (k == '<') {
        if (_pin.isNotEmpty) _pin = _pin.substring(0, _pin.length - 1);
      } else if (_pin.length < 4) {
        _pin += k;
      }
    });
    if (_pin.length == 4) {
      setState(() => _busy = true);
      final error = await widget.onPin(_pin);
      if (!mounted) return;
      if (error == null) {
        Navigator.pop(context, PinPadOutcome.done);
        return;
      }
      setState(() {
        _busy = false;
        _error = error;
        _pin = '';
      });
    }
  }

  /// A hardware keypad types too.
  KeyEventResult _onKey(FocusNode _, KeyEvent e) {
    if (e is! KeyDownEvent) return KeyEventResult.ignored;
    final ch = e.character;
    if (ch != null && RegExp(r'^[0-9]$').hasMatch(ch)) {
      _key(ch);
      return KeyEventResult.handled;
    }
    if (e.logicalKey == LogicalKeyboardKey.backspace) {
      _key('<');
      return KeyEventResult.handled;
    }
    return KeyEventResult.ignored;
  }

  @override
  Widget build(BuildContext context) {
    Widget key(String k) => Padding(
          padding: const EdgeInsets.all(5),
          child: Material(
            color: AppColors.key,
            borderRadius: AppRadii.brSm,
            child: InkWell(
              borderRadius: AppRadii.brSm,
              onTap: _busy ? null : () => _key(k),
              child: SizedBox(
                width: 72,
                height: 56,
                child: Center(
                  child: k == '<'
                      ? const Icon(Icons.backspace_outlined, color: AppColors.ink2)
                      : Text(k,
                          style: const TextStyle(fontSize: 22, fontWeight: FontWeight.w700)),
                ),
              ),
            ),
          ),
        );
    return Focus(
      focusNode: _focus,
      onKeyEvent: _onKey,
      child: AlertDialog(
        shape: RoundedRectangleBorder(borderRadius: AppRadii.brLg),
        title: Text(widget.name, textAlign: TextAlign.center),
        content: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            const Text('Enter your PIN', style: TextStyle(color: AppColors.ink2)),
            const SizedBox(height: 14),
            Semantics(
              label: '${_pin.length} of 4 digits entered',
              child: Row(
                mainAxisSize: MainAxisSize.min,
                children: [
                  for (var i = 0; i < 4; i++)
                    Container(
                      margin: const EdgeInsets.symmetric(horizontal: 8),
                      width: 16,
                      height: 16,
                      decoration: BoxDecoration(
                        shape: BoxShape.circle,
                        color: i < _pin.length ? AppColors.brand : Colors.transparent,
                        border: Border.all(color: AppColors.brand, width: 2),
                      ),
                    ),
                ],
              ),
            ),
            SizedBox(
              height: 34,
              child: Center(
                child: _busy
                    ? const SizedBox(
                        width: 18, height: 18, child: CircularProgressIndicator(strokeWidth: 2))
                    : _error == null
                        ? null
                        : Text(_error!,
                            textAlign: TextAlign.center,
                            style: const TextStyle(color: AppColors.danger, fontSize: 12.5)),
              ),
            ),
            for (final row in const [
              ['1', '2', '3'],
              ['4', '5', '6'],
              ['7', '8', '9'],
              ['', '0', '<'],
            ])
              Row(
                mainAxisSize: MainAxisSize.min,
                children: [
                  for (final k in row)
                    k.isEmpty ? const SizedBox(width: 82) : key(k),
                ],
              ),
          ],
        ),
        actions: [
          TextButton(
            onPressed: _busy ? null : () => Navigator.pop(context, PinPadOutcome.usePassword),
            child: const Text('Use password instead'),
          ),
          TextButton(
            onPressed: _busy ? null : () => Navigator.pop(context),
            child: const Text('Cancel'),
          ),
        ],
      ),
    );
  }
}
