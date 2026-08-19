import 'package:flutter/material.dart';

import '../app/format.dart';
import '../app/theme.dart';
import '../data/mock.dart';
import '../widgets/kit.dart';

/// A searchable product chooser, shared by the adjustment and receiving forms.
Future<Product?> pickProduct(BuildContext context) {
  return showModalBottomSheet<Product>(
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
    final items = kProducts
        .where((p) =>
            _query.isEmpty ||
            p.name.toLowerCase().contains(_query.toLowerCase()) ||
            p.code.toLowerCase().contains(_query.toLowerCase()))
        .toList();

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
              child: items.isEmpty
                  ? Center(
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
                    )
                  : ListView.separated(
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
                            '${p.code} · ${p.onHand} ${p.unit} on hand',
                            style: HqText.tiny,
                          ),
                          trailing: Text(
                            tzs(p.price),
                            style: const TextStyle(
                              fontSize: 13.5,
                              fontWeight: FontWeight.w700,
                              color: HqColors.ink2,
                            ),
                          ),
                          onTap: () => Navigator.of(context).pop(p),
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

  final Product? product;
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
              padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 14),
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
                            style: TextStyle(
                              fontSize: 15,
                              color: HqColors.ink3,
                            ),
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
                                '${product!.code} · '
                                '${product!.onHand} ${product!.unit} on hand',
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
