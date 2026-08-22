import 'package:flutter/material.dart';

import '../app/theme.dart';
import '../services/catalog_service.dart';

/// Choose the unit a quantity is being entered in — the base unit, or one of
/// the product's pack sizes.
///
/// Every option states its conversion ("1 carton = 24 pcs"), because the number
/// the owner types means nothing without it: 10 is ten pieces or two hundred
/// and forty, and only the unit says which.
Future<TxUnit?> pickUnit(
  BuildContext context, {
  required List<TxUnit> units,
  required String baseCode,
  TxUnit? current,
  String title = 'Which unit?',
}) {
  return showModalBottomSheet<TxUnit>(
    context: context,
    backgroundColor: Colors.transparent,
    builder: (context) => Container(
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
            Text(title, style: HqText.title),
            const SizedBox(height: 12),
            for (final u in units)
              Padding(
                padding: const EdgeInsets.only(bottom: 8),
                child: _UnitOption(
                  unit: u,
                  baseCode: baseCode,
                  selected: u.uid == current?.uid,
                  onTap: () => Navigator.of(context).pop(u),
                ),
              ),
            const SizedBox(height: 6),
          ],
        ),
      ),
    ),
  );
}

class _UnitOption extends StatelessWidget {
  const _UnitOption({
    required this.unit,
    required this.baseCode,
    required this.selected,
    required this.onTap,
  });

  final TxUnit unit;
  final String baseCode;
  final bool selected;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    return Material(
      color: Colors.transparent,
      child: InkWell(
        onTap: onTap,
        borderRadius: BorderRadius.circular(HqRadii.sm),
        child: Container(
          padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 13),
          decoration: BoxDecoration(
            borderRadius: BorderRadius.circular(HqRadii.sm),
            color: selected ? HqColors.brandSoft : null,
            border: Border.all(
              color: selected ? HqColors.brand : HqColors.line2,
              width: selected ? 1.5 : 1,
            ),
          ),
          child: Row(
            children: [
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(
                      unit.name.isEmpty ? unit.code : unit.name,
                      maxLines: 1,
                      overflow: TextOverflow.ellipsis,
                      style: TextStyle(
                        fontSize: 15,
                        fontWeight: FontWeight.w600,
                        color: selected ? HqColors.brandD : HqColors.ink,
                      ),
                    ),
                    const SizedBox(height: 2),
                    Text(
                      unit.isBase
                          ? 'The unit stock is counted in'
                          : '1 ${unit.code} = ${unit.factorLabel} $baseCode',
                      style: HqText.tiny,
                    ),
                  ],
                ),
              ),
              if (selected)
                const Icon(Icons.check_circle_rounded,
                    size: 20, color: HqColors.brand),
            ],
          ),
        ),
      ),
    );
  }
}
