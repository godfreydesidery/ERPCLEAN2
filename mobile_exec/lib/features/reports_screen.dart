import 'package:flutter/material.dart';

import '../app/format.dart';
import '../app/theme.dart';
import '../data/mock.dart';
import '../widgets/common.dart';
import '../widgets/kit.dart';

/// The report list. Three reports the client asked for, each exportable and
/// shareable by WhatsApp or email.
class ReportsScreen extends StatelessWidget {
  const ReportsScreen({super.key, required this.onNavigate});

  final void Function(String route) onNavigate;

  @override
  Widget build(BuildContext context) {
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
            subtitle: 'By product, by branch, by period',
            figure: tzs(kSalesReportTotal),
            figureLabel: 'this month',
            onTap: () => onNavigate('sales'),
          ),
          const SizedBox(height: 12),
          _ReportTile(
            icon: Icons.inventory_outlined,
            tint: const Color(0xFF2A78D6),
            title: 'Stock report',
            subtitle: 'What moved in, out and where it sits',
            figure: '$kSkuCount',
            figureLabel: 'items',
            onTap: () => onNavigate('stock'),
          ),
          const SizedBox(height: 12),
          _ReportTile(
            icon: Icons.account_balance_wallet_outlined,
            tint: const Color(0xFFEB6834),
            title: 'Stock valuation',
            subtitle: 'What the stock on hand is worth',
            figure: tzs(kStockValue),
            figureLabel: 'at cost',
            onTap: () => onNavigate('valuation'),
          ),
          const SizedBox(height: 12),
          _ReportTile(
            icon: Icons.point_of_sale_outlined,
            tint: const Color(0xFF7C7CD6),
            title: 'Till report (X / Z)',
            subtitle: 'Read a till mid-shift, or close the day',
            figure: '${kOpenSessions.length}',
            figureLabel: 'tills open',
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
                    'Every report can go out as PDF, Excel or CSV — by '
                    'WhatsApp, by email, or saved to the phone.',
                    style: HqText.body,
                  ),
                ),
              ],
            ),
          ),
          const SizedBox(height: 12),
          OutlinedButton.icon(
            onPressed: () => showShareSheet(context, 'Sales report — August 2026'),
            icon: const Icon(Icons.ios_share_rounded, size: 18),
            label: const Text('Try sharing a report'),
          ),
          const SizedBox(height: 20),
          const AsOfLine(
            asOf: 'Figures as at $kAsOf',
            coverage: 'Demo data — not connected to the server',
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
    required this.figure,
    required this.figureLabel,
    required this.onTap,
  });

  final IconData icon;
  final Color tint;
  final String title;
  final String subtitle;
  final String figure;
  final String figureLabel;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    return HqCard(
      onTap: onTap,
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              Container(
                width: 44,
                height: 44,
                decoration: BoxDecoration(
                  color: tint.withValues(alpha: 0.10),
                  borderRadius: BorderRadius.circular(12),
                ),
                child: Icon(icon, color: tint, size: 22),
              ),
              const SizedBox(width: 14),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(
                      title,
                      style: const TextStyle(
                        fontSize: 15.5,
                        fontWeight: FontWeight.w700,
                        color: HqColors.ink,
                      ),
                    ),
                    const SizedBox(height: 2),
                    Text(
                      subtitle,
                      style: HqText.tiny,
                      maxLines: 2,
                      overflow: TextOverflow.ellipsis,
                    ),
                  ],
                ),
              ),
              const SizedBox(width: 8),
              const Icon(Icons.chevron_right, color: HqColors.ink3, size: 20),
            ],
          ),
          const SizedBox(height: 14),
          const Divider(height: 1),
          const SizedBox(height: 12),
          Row(
            children: [
              Text(
                figure,
                style: const TextStyle(
                  fontSize: 19,
                  fontWeight: FontWeight.w700,
                  color: HqColors.ink,
                ),
              ),
              const SizedBox(width: 7),
              Flexible(
                child: Text(
                  figureLabel,
                  maxLines: 1,
                  overflow: TextOverflow.ellipsis,
                  style: HqText.tiny,
                ),
              ),
            ],
          ),
        ],
      ),
    );
  }
}
