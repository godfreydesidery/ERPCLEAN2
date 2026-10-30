import 'package:flutter/material.dart';

import '../../app/theme.dart';
import '../../models/restaurant.dart';

/// The branch's waiters as tiles (J COB 2026-10-30): tap your name, then type
/// your password. Filtered by [query]; [selected] is the username picked.
///
/// A big restaurant has fifty waiters, so past [compactFrom] the tiles go
/// compact (name only, half the height) and an A–Z strip of the initials that
/// exist narrows the list to one letter — the usual staff list on a till.
class WaiterTiles extends StatefulWidget {
  const WaiterTiles({
    super.key,
    required this.waiters,
    required this.onPick,
    this.query = '',
    this.selected,
  });

  final List<RestaurantWaiter> waiters;
  final ValueChanged<RestaurantWaiter> onPick;
  final String query;
  final String? selected;

  static const compactFrom = 12;

  @override
  State<WaiterTiles> createState() => _WaiterTilesState();
}

class _WaiterTilesState extends State<WaiterTiles> {
  /// The initial the list is narrowed to, or null for everyone.
  String? _letter;

  static String _initialOf(RestaurantWaiter w) {
    final n = w.name.trim();
    return n.isEmpty ? '#' : n[0].toUpperCase();
  }

  @override
  Widget build(BuildContext context) {
    final compact = widget.waiters.length > WaiterTiles.compactFrom;
    final letters = compact
        ? (widget.waiters.map(_initialOf).toSet().toList()..sort())
        : const <String>[];
    final letter = letters.contains(_letter) ? _letter : null;
    final shown = widget.waiters
        .where((w) => w.matches(widget.query))
        .where((w) => letter == null || _initialOf(w) == letter)
        .toList();

    return Column(
      mainAxisSize: MainAxisSize.min,
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        if (letters.length > 1) ...[
          Wrap(
            spacing: 6,
            runSpacing: 6,
            children: [
              _LetterChip(label: 'All', on: letter == null, onTap: () => setState(() => _letter = null)),
              for (final l in letters)
                _LetterChip(
                  label: l,
                  on: letter == l,
                  onTap: () => setState(() => _letter = letter == l ? null : l),
                ),
            ],
          ),
          const SizedBox(height: 10),
        ],
        if (shown.isEmpty)
          const Padding(
            padding: EdgeInsets.all(16),
            child: Text('No waiter matches.', style: TextStyle(color: AppColors.ink3)),
          )
        else
          GridView.builder(
            shrinkWrap: true,
            physics: const NeverScrollableScrollPhysics(),
            gridDelegate: SliverGridDelegateWithMaxCrossAxisExtent(
              maxCrossAxisExtent: compact ? 150 : 160,
              mainAxisExtent: compact ? 50 : 96,
              crossAxisSpacing: 8,
              mainAxisSpacing: 8,
            ),
            itemCount: shown.length,
            itemBuilder: (context, i) => _tile(shown[i], compact),
          ),
      ],
    );
  }

  Widget _tile(RestaurantWaiter w, bool compact) {
    final on = w.username == widget.selected;
    return Semantics(
      button: true,
      selected: on,
      label: 'Sign in as ${w.name}',
      child: Material(
        color: on ? AppColors.brandSoft : AppColors.panel,
        borderRadius: AppRadii.brLg,
        child: InkWell(
          borderRadius: AppRadii.brLg,
          onTap: () => widget.onPick(w),
          child: Container(
            padding: EdgeInsets.all(compact ? 6 : 10),
            decoration: BoxDecoration(
              borderRadius: AppRadii.brLg,
              border: Border.all(color: on ? AppColors.brand : AppColors.line2, width: 1.5),
            ),
            child: compact
                ? Center(
                    child: Text(w.name,
                        maxLines: 2,
                        textAlign: TextAlign.center,
                        overflow: TextOverflow.ellipsis,
                        style: const TextStyle(fontWeight: FontWeight.w700, fontSize: 13)),
                  )
                : Column(
                    mainAxisAlignment: MainAxisAlignment.center,
                    children: [
                      CircleAvatar(
                        radius: 18,
                        backgroundColor: on ? AppColors.brand : AppColors.panel2,
                        child: Text(_initials(w.name),
                            style: TextStyle(
                                fontWeight: FontWeight.w800,
                                color: on ? Colors.white : AppColors.ink2)),
                      ),
                      const SizedBox(height: 6),
                      Text(w.name,
                          maxLines: 2,
                          textAlign: TextAlign.center,
                          overflow: TextOverflow.ellipsis,
                          style: const TextStyle(fontWeight: FontWeight.w700, fontSize: 13)),
                    ],
                  ),
          ),
        ),
      ),
    );
  }

  static String _initials(String name) {
    final parts = name.trim().split(RegExp(r'\s+')).where((p) => p.isNotEmpty).toList();
    if (parts.isEmpty) return '?';
    final first = parts.first[0];
    final last = parts.length > 1 ? parts.last[0] : '';
    return (first + last).toUpperCase();
  }
}

class _LetterChip extends StatelessWidget {
  const _LetterChip({required this.label, required this.on, required this.onTap});
  final String label;
  final bool on;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    return Semantics(
      button: true,
      selected: on,
      label: label == 'All' ? 'All waiters' : 'Waiters starting with $label',
      child: Material(
        color: on ? AppColors.ink : AppColors.panel,
        borderRadius: AppRadii.brSm,
        child: InkWell(
          borderRadius: AppRadii.brSm,
          onTap: onTap,
          child: Container(
            constraints: const BoxConstraints(minWidth: 40, minHeight: 40),
            padding: const EdgeInsets.symmetric(horizontal: 8),
            decoration: BoxDecoration(
              borderRadius: AppRadii.brSm,
              border: Border.all(color: AppColors.line2),
            ),
            // Sized to the letter: an aligned Container would stretch to the
            // whole row and stack the strip one letter per line.
            child: Center(
              widthFactor: 1,
              heightFactor: 1,
              child: Text(label,
                  style: TextStyle(
                      fontWeight: FontWeight.w800,
                      fontSize: 15,
                      color: on ? Colors.white : AppColors.ink)),
            ),
          ),
        ),
      ),
    );
  }
}
