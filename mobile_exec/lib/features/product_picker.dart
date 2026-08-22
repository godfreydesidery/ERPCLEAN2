import 'package:flutter/material.dart';

import '../app/app_scope.dart';
import '../app/theme.dart';
import '../services/catalog_service.dart';
import '../widgets/async_view.dart';
import '../widgets/kit.dart';

/// A searchable product chooser, backed by `/products`.
Future<ProductItem?> pickProduct(BuildContext context) {
  return showModalBottomSheet<ProductItem>(
    context: context,
    isScrollControlled: true,
    backgroundColor: Colors.transparent,
    builder: (_) => const _ProductPicker(),
  );
}

class _ProductPicker extends StatefulWidget {
  const _ProductPicker();

  @override
  State<_ProductPicker> createState() => _ProductPickerState();
}

class _ProductPickerState extends State<_ProductPicker> {
  String _query = '';

  @override
  Widget build(BuildContext context) {
    return DraggableScrollableSheet(
      initialChildSize: 0.75,
      minChildSize: 0.5,
      maxChildSize: 0.94,
      expand: false,
      builder: (context, controller) => Container(
        decoration: const BoxDecoration(
          color: HqColors.panel,
          borderRadius: BorderRadius.vertical(top: Radius.circular(22)),
        ),
        child: Column(
          children: [
            const SizedBox(height: 12),
            Container(
              width: 38,
              height: 4,
              decoration: BoxDecoration(
                color: HqColors.line2,
                borderRadius: BorderRadius.circular(2),
              ),
            ),
            Padding(
              padding: const EdgeInsets.fromLTRB(20, 16, 20, 12),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  const Text('Choose an item', style: HqText.title),
                  const SizedBox(height: 12),
                  HqSearchField(
                    hint: 'Search name or code',
                    onChanged: (v) => setState(() => _query = v),
                  ),
                ],
              ),
            ),
            const Divider(height: 1),
            Expanded(
              child: AsyncView<List<ProductItem>>(
                load: () => AppScope.of(context).catalog.products(),
                isEmpty: (d) => d.isEmpty,
                emptyIcon: Icons.inventory_2_outlined,
                emptyTitle: 'No items yet',
                emptyDetail: 'Create one from Operations first.',
                builder: (context, all) {
                  final q = _query.trim().toLowerCase();
                  final items = q.isEmpty
                      ? all
                      : all
                          .where((p) =>
                              p.name.toLowerCase().contains(q) ||
                              p.code.toLowerCase().contains(q))
                          .toList();

                  if (items.isEmpty) {
                    return Center(
                      child: Column(
                        mainAxisSize: MainAxisSize.min,
                        children: [
                          const Icon(Icons.search_off_rounded,
                              size: 40, color: HqColors.ink3),
                          const SizedBox(height: 12),
                          Text('No item matches "$_query"',
                              style: HqText.body),
                        ],
                      ),
                    );
                  }

                  return ListView.separated(
                    controller: controller,
                    padding: const EdgeInsets.symmetric(
                        horizontal: 20, vertical: 8),
                    itemCount: items.length,
                    separatorBuilder: (_, __) => const Divider(height: 1),
                    itemBuilder: (context, i) {
                      final p = items[i];
                      return ListTile(
                        contentPadding: EdgeInsets.zero,
                        title: Text(
                          p.name,
                          maxLines: 1,
                          overflow: TextOverflow.ellipsis,
                          style: const TextStyle(
                            fontSize: 14.5,
                            fontWeight: FontWeight.w600,
                            color: HqColors.ink,
                          ),
                        ),
                        subtitle: Text(
                          // The unit stock is COUNTED in. "per PCS" read as a
                          // price basis; it is the counting unit, and every
                          // quantity on the next screen is in it.
                          '${p.code} · counted in ${p.unit}',
                          style: HqText.tiny,
                        ),
                        onTap: () => Navigator.of(context).pop(p),
                      );
                    },
                  );
                },
              ),
            ),
          ],
        ),
      ),
    );
  }
}

/// The tile that opens the picker and then shows what was chosen.
class ProductPickerTile extends StatelessWidget {
  const ProductPickerTile({
    super.key,
    required this.product,
    required this.onTap,
    this.label = 'Item',
  });

  final ProductItem? product;
  final VoidCallback onTap;
  final String label;

  @override
  Widget build(BuildContext context) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Row(
          children: [
            Text(
              label,
              style: const TextStyle(
                fontSize: 12.5,
                fontWeight: FontWeight.w700,
                color: HqColors.ink2,
              ),
            ),
            const Text(
              ' *',
              style: TextStyle(
                fontSize: 12.5,
                fontWeight: FontWeight.w700,
                color: HqColors.bad,
              ),
            ),
          ],
        ),
        const SizedBox(height: 7),
        Material(
          color: HqColors.panel,
          borderRadius: BorderRadius.circular(HqRadii.sm),
          child: InkWell(
            onTap: onTap,
            borderRadius: BorderRadius.circular(HqRadii.sm),
            child: Container(
              padding:
                  const EdgeInsets.symmetric(horizontal: 14, vertical: 14),
              decoration: BoxDecoration(
                borderRadius: BorderRadius.circular(HqRadii.sm),
                border: Border.all(
                  color: product == null ? HqColors.line2 : HqColors.brand,
                  width: product == null ? 1 : 1.5,
                ),
              ),
              child: Row(
                children: [
                  Icon(
                    Icons.inventory_2_outlined,
                    size: 20,
                    color: product == null ? HqColors.ink3 : HqColors.brand,
                  ),
                  const SizedBox(width: 12),
                  Expanded(
                    child: product == null
                        ? const Text(
                            'Tap to choose an item',
                            style:
                                TextStyle(fontSize: 15, color: HqColors.ink3),
                          )
                        : Column(
                            crossAxisAlignment: CrossAxisAlignment.start,
                            children: [
                              Text(
                                product!.name,
                                maxLines: 1,
                                overflow: TextOverflow.ellipsis,
                                style: const TextStyle(
                                  fontSize: 15,
                                  fontWeight: FontWeight.w600,
                                  color: HqColors.ink,
                                ),
                              ),
                              const SizedBox(height: 2),
                              Text(
                                '${product!.code} · counted in '
                                '${product!.unit}',
                                maxLines: 1,
                                overflow: TextOverflow.ellipsis,
                                style: HqText.tiny,
                              ),
                            ],
                          ),
                  ),
                  const SizedBox(width: 8),
                  Icon(
                    product == null
                        ? Icons.chevron_right
                        : Icons.swap_horiz_rounded,
                    size: 20,
                    color: HqColors.ink3,
                  ),
                ],
              ),
            ),
          ),
        ),
      ],
    );
  }
}
