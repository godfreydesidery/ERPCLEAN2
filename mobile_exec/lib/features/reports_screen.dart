import 'package:flutter/material.dart';

import '../app/app_scope.dart';
import '../app/theme.dart';
import '../widgets/common.dart';

/// The report list. Each one runs against the live ERP and can be exported
/// and shared. Reports the signed-in user cannot see are shown locked rather
/// than hidden, so it is obvious what exists and who to ask.
class ReportsScreen extends StatelessWidget {
  const ReportsScreen({super.key, required this.onNavigate});

  final void Function(String route) onNavigate;

  @override
  Widget build(BuildContext context) {
    final session = AppScope.of(context).session;

    return Scaffold(
      backgroundColor: HqColors.bg,
      appBar: AppBar(
        title: const Text('Reports', style: HqText.title),
        titleSpacing: 20,
      ),
      body: ListView(
        padding: const EdgeInsets.fromLTRB(20, 8, 20, 28),
        children: [
          _ReportTile(
            icon: Icons.receipt_long_outlined,
            tint: HqColors.brand,
            title: 'Sales report',
            subtitle: 'By product, over any date range',
            allowed: session.can('SALES.INVOICE.VIEW'),
            onTap: () => onNavigate('sales'),
          ),
          const SizedBox(height: 12),
          _ReportTile(
            icon: Icons.inventory_outlined,
            tint: const Color(0xFF2A78D6),
            title: 'Stock report',
            subtitle: 'What is on hand right now',
            allowed: session.can('STOCK.VIEW'),
            onTap: () => onNavigate('stock'),
          ),
          const SizedBox(height: 12),
          _ReportTile(
            icon: Icons.account_balance_wallet_outlined,
            tint: const Color(0xFFEB6834),
            title: 'Stock valuation',
            subtitle: 'What the stock on hand is worth',
            allowed: session.can('INVENTORY.VALUATION.VIEW'),
            onTap: () => onNavigate('valuation'),
          ),
          const SizedBox(height: 12),
          _ReportTile(
            icon: Icons.point_of_sale_outlined,
            tint: const Color(0xFF7C7CD6),
            title: 'X read',
            subtitle: 'Where a till stands right now',
            allowed: session.can('POS.SESSION.VIEW'),
            onTap: () => onNavigate('till'),
          ),
          const SizedBox(height: 24),
          const SectionLabel(text: 'SHARING'),
          const SizedBox(height: 10),
          HqCard(
            child: Row(
              children: [
                Container(
                  width: 40,
                  height: 40,
                  decoration: BoxDecoration(
                    color: const Color(0xFF25D366).withValues(alpha: 0.12),
                    borderRadius: BorderRadius.circular(11),
                  ),
                  child: const Icon(Icons.ios_share_rounded,
                      size: 19, color: Color(0xFF25D366)),
                ),
                const SizedBox(width: 13),
                const Expanded(
                  child: Text(
                    'Every report goes out as a PDF or a spreadsheet — straight '
                    'to WhatsApp, email, or anywhere else on the phone.',
                    style: HqText.body,
                  ),
                ),
              ],
            ),
          ),
          const SizedBox(height: 18),
          AsOfLine(
            asOf: 'Live from the server',
            coverage: session.activeBranch?.name ?? '',
          ),
        ],
      ),
    );
  }
}

class _ReportTile extends StatelessWidget {
  const _ReportTile({
    required this.icon,
    required this.tint,
    required this.title,
    required this.subtitle,
    required this.allowed,
    required this.onTap,
  });

  final IconData icon;
  final Color tint;
  final String title;
  final String subtitle;
  final bool allowed;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    return HqCard(
      onTap: onTap,
      child: Row(
        children: [
          Container(
            width: 44,
            height: 44,
            decoration: BoxDecoration(
              color: tint.withValues(alpha: allowed ? 0.10 : 0.05),
              borderRadius: BorderRadius.circular(12),
            ),
            child: Icon(
              icon,
              color: allowed ? tint : HqColors.ink3,
              size: 22,
            ),
          ),
          const SizedBox(width: 14),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  title,
                  style: TextStyle(
                    fontSize: 15.5,
                    fontWeight: FontWeight.w700,
                    color: allowed ? HqColors.ink : HqColors.ink3,
                  ),
                ),
                const SizedBox(height: 2),
                Text(
                  allowed ? subtitle : 'You do not have access to this',
                  style: HqText.tiny,
                  maxLines: 2,
                  overflow: TextOverflow.ellipsis,
                ),
              ],
            ),
          ),
          const SizedBox(width: 8),
          Icon(
            allowed ? Icons.chevron_right : Icons.lock_outline_rounded,
            color: HqColors.ink3,
            size: allowed ? 20 : 17,
          ),
        ],
      ),
    );
  }
}
