import 'package:flutter/material.dart';

import '../../app/theme.dart';
import '../../models/restaurant.dart';

/// The branch's waiters as tiles (J COB 2026-10-30): tap your name, then type
/// your password. Filtered by [query]; [selected] is the username picked.
class WaiterTiles extends StatelessWidget {
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

  @override
  Widget build(BuildContext context) {
    final shown = waiters.where((w) => w.matches(query)).toList();
    if (shown.isEmpty) {
      return const Padding(
        padding: EdgeInsets.all(16),
        child: Text('No waiter matches.', style: TextStyle(color: AppColors.ink3)),
      );
    }
    return GridView.builder(
      shrinkWrap: true,
      gridDelegate: const SliverGridDelegateWithMaxCrossAxisExtent(
        maxCrossAxisExtent: 160,
        mainAxisExtent: 96,
        crossAxisSpacing: 10,
        mainAxisSpacing: 10,
      ),
      itemCount: shown.length,
      itemBuilder: (context, i) {
        final w = shown[i];
        final on = w.username == selected;
        return Semantics(
          button: true,
          selected: on,
          label: 'Sign in as ${w.name}',
          child: Material(
            color: on ? AppColors.brandSoft : AppColors.panel,
            borderRadius: AppRadii.brLg,
            child: InkWell(
              borderRadius: AppRadii.brLg,
              onTap: () => onPick(w),
              child: Container(
                padding: const EdgeInsets.all(10),
                decoration: BoxDecoration(
                  borderRadius: AppRadii.brLg,
                  border: Border.all(color: on ? AppColors.brand : AppColors.line2, width: 1.5),
                ),
                child: Column(
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
      },
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
