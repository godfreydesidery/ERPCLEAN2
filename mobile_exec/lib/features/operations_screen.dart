import 'package:flutter/material.dart';

import '../app/theme.dart';
import '../widgets/common.dart';
import '../widgets/kit.dart';

/// The operations hub — the things a manager does from the shop floor.
class OperationsScreen extends StatelessWidget {
  const OperationsScreen({super.key, required this.onNavigate});

  final void Function(String route) onNavigate;

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: HqColors.bg,
      appBar: AppBar(
        title: const Text('Operations', style: HqText.title),
        titleSpacing: 20,
      ),
      body: ListView(
        padding: const EdgeInsets.fromLTRB(20, 8, 20, 28),
        children: [
          const SectionLabel(text: 'STOCK'),
          const SizedBox(height: 10),
          ActionTile(
            icon: Icons.local_shipping_outlined,
            title: 'Receive goods',
            subtitle: 'Take in stock without a purchase order',
            tint: const Color(0xFF2A78D6),
            onTap: () => onNavigate('receive'),
          ),
          const SizedBox(height: 10),
          ActionTile(
            icon: Icons.tune_rounded,
            title: 'Stock adjustment',
            subtitle: 'Correct a quantity — damage, count, loss',
            tint: const Color(0xFFEB6834),
            onTap: () => onNavigate('adjust'),
          ),
          const SizedBox(height: 22),
          const SectionLabel(text: 'MASTER DATA'),
          const SizedBox(height: 10),
          ActionTile(
            icon: Icons.inventory_2_outlined,
            title: 'New item',
            subtitle: 'Register a product with its unit and price',
            tint: HqColors.brand,
            onTap: () => onNavigate('item'),
          ),
          const SizedBox(height: 10),
          ActionTile(
            icon: Icons.storefront_outlined,
            title: 'New supplier',
            subtitle: 'Add a supplier you buy from',
            tint: const Color(0xFF7C7CD6),
            onTap: () => onNavigate('supplier'),
          ),
          const SizedBox(height: 10),
          ActionTile(
            icon: Icons.view_in_ar_outlined,
            title: 'Pack sizes',
            subtitle: 'Cartons, boxes and outers — and what each sells for',
            tint: const Color(0xFF0E9F6E),
            onTap: () => onNavigate('packs'),
          ),
          const SizedBox(height: 22),
          const SectionLabel(text: 'TILLS'),
          const SizedBox(height: 10),
          ActionTile(
            icon: Icons.receipt_outlined,
            title: 'X read',
            subtitle: 'See where a till stands right now',
            tint: const Color(0xFF7C7CD6),
            onTap: () => onNavigate('till'),
          ),
          const SizedBox(height: 24),
        ],
      ),
    );
  }
}
