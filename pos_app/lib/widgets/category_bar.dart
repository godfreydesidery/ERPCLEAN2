import 'package:flutter/material.dart';

import '../app/theme.dart';

/// A row of category buttons (Tulia 2026-11-02): "All" plus each product
/// category, scrolling sideways when they do not fit. Nothing at all when there
/// are no categories, so a shop that never set any sees no change.
class CategoryBar extends StatelessWidget {
  const CategoryBar({
    super.key,
    required this.categories,
    required this.selected,
    required this.onSelected,
  });

  static const double height = 40;

  final List<String> categories;

  /// The category shown, or null for all.
  final String? selected;
  final ValueChanged<String?> onSelected;

  @override
  Widget build(BuildContext context) {
    if (categories.isEmpty) return const SizedBox.shrink();
    Widget chip(String label, String? value) {
      final on = selected == value;
      return Padding(
        padding: const EdgeInsets.only(right: 8),
        child: ChoiceChip(
          label: Text(label),
          selected: on,
          showCheckmark: false,
          onSelected: (_) => onSelected(on && value != null ? null : value),
          labelStyle: TextStyle(
              fontWeight: FontWeight.w600, color: on ? AppColors.brandD : AppColors.ink2),
          selectedColor: AppColors.brandSoft,
          backgroundColor: AppColors.panel,
          side: BorderSide(color: on ? AppColors.brand : AppColors.line2),
        ),
      );
    }

    return SizedBox(
      height: height,
      child: ListView(
        scrollDirection: Axis.horizontal,
        children: [
          chip('All', null),
          for (final c in categories) chip(c, c),
        ],
      ),
    );
  }
}
