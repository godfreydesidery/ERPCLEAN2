import 'package:flutter/material.dart';

import '../app/app_scope.dart';
import '../app/format.dart';
import '../app/theme.dart';
import '../core/export/report_doc.dart';
import '../services/operations_service.dart';
import '../widgets/async_view.dart';
import '../widgets/common.dart';
import '../widgets/kit.dart';

/// X read — `GET /pos/sessions/uid/{uid}/x-read`.
///
/// Read-only by design. An X read looks at a till and changes nothing, so it
/// is safe from a phone. Closing the day (the Z) stays at the till itself: a
/// manager closing a session remotely would end a cashier's shift from under
/// them mid-sale.
class TillReportScreen extends StatefulWidget {
  const TillReportScreen({super.key});

  @override
  State<TillReportScreen> createState() => _TillReportScreenState();
}

class _TillReportScreenState extends State<TillReportScreen> {
  SessionRow? _session;

  @override
  Widget build(BuildContext context) {
    final session = AppScope.of(context).session;

    return Scaffold(
      backgroundColor: HqColors.bg,
      appBar: AppBar(title: const Text('X read', style: HqText.title)),
      body: !session.can('POS.SESSION.VIEW')
          ? const NoPermission(code: 'POS.SESSION.VIEW')
          : AsyncView<List<SessionRow>>(
              load: () => AppScope.of(context).operations.openSessions(),
              isEmpty: (d) => d.isEmpty,
              emptyIcon: Icons.point_of_sale_outlined,
              emptyTitle: 'No till is open',
              emptyDetail: 'A cashier must open a session first.',
              builder: (context, sessions) {
                final chosen = _session != null &&
                        sessions.any((s) => s.uid == _session!.uid)
                    ? _session!
                    : sessions.first;

                return ListView(
                  padding: const EdgeInsets.fromLTRB(20, 8, 20, 28),
                  children: [
                    const _ReadNote(),
                    const SizedBox(height: 18),
                    SectionLabel(
                      text: 'OPEN TILLS',
                      trailing: '${sessions.length}',
                    ),
                    const SizedBox(height: 10),
                    for (final s in sessions) ...[
                      _SessionCard(
                        session: s,
                        selected: s.uid == chosen.uid,
                        onTap: () => setState(() => _session = s),
                      ),
                      const SizedBox(height: 10),
                    ],
                    const SizedBox(height: 10),
                    _ReadPanel(key: ValueKey(chosen.uid), session: chosen),
                  ],
                );
              },
            ),
    );
  }
}

class _ReadNote extends StatelessWidget {
  const _ReadNote();

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.all(13),
      decoration: BoxDecoration(
        color: HqColors.brandSoft,
        borderRadius: BorderRadius.circular(HqRadii.sm),
      ),
      child: Row(
        children: [
          const Icon(Icons.info_outline_rounded,
              size: 19, color: HqColors.brand),
          const SizedBox(width: 10),
          Expanded(
            child: Text(
              'An X read only looks at the till. Nothing is closed and nothing '
              'resets, so you can take it as often as you like.',
              style: TextStyle(
                fontSize: 13,
                height: 1.35,
                color: HqColors.brandD,
              ),
            ),
          ),
        ],
      ),
    );
  }
}

class _ReadPanel extends StatelessWidget {
  const _ReadPanel({super.key, required this.session});

  final SessionRow session;

  @override
  Widget build(BuildContext context) {
    return AsyncView<TillRead>(
      load: () => AppScope.of(context).operations.xRead(session.uid),
      builder: (context, read) {
        return Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            _Header(read: read),
            const SizedBox(height: 16),
            const SectionLabel(text: 'HOW THEY PAID'),
            const SizedBox(height: 10),
            HqCard(
              child: Column(
                children: [
                  if (read.tenders.isEmpty)
                    Text('No sales on this till yet.', style: HqText.body)
                  else
                    for (var i = 0; i < read.tenders.length; i++) ...[
                      if (i > 0) const Divider(height: 14),
                      FigureRow(
                        label: '${read.tenders[i].label} '
                            '(${read.tenders[i].count})',
                        value: tzs(read.tenders[i].amount),
                      ),
                    ],
                  const Divider(height: 16, thickness: 1.4),
                  FigureRow(
                    label: 'Total takings',
                    value: tzs(read.totalSales),
                    emphasise: true,
                    valueColor: HqColors.brand,
                  ),
                ],
              ),
            ),
            const SizedBox(height: 18),
            const SectionLabel(text: 'CASH IN THE DRAWER'),
            const SizedBox(height: 10),
            HqCard(
              child: Column(
                children: [
                  FigureRow(
                    label: 'Opening float',
                    value: tzs(read.openingFloat),
                  ),
                  const Divider(height: 14),
                  FigureRow(label: 'Cash sales', value: tzs(read.cashTender)),
                  const Divider(height: 14),
                  FigureRow(
                    label: 'Paid out',
                    value: tzs(read.payoutsNet),
                    valueColor: read.payoutsNet > 0 ? HqColors.bad : null,
                  ),
                  const Divider(height: 16, thickness: 1.4),
                  FigureRow(
                    label: 'Cash the drawer should hold',
                    value: tzs(read.expectedCash),
                    emphasise: true,
                  ),
                ],
              ),
            ),
            const SizedBox(height: 18),
            OutlinedButton.icon(
              onPressed: () => showShareSheet(context, _readDoc(read)),
              icon: const Icon(Icons.ios_share_rounded, size: 19),
              label: const Text('Export and share'),
            ),
          ],
        );
      },
    );
  }
}

class _Header extends StatelessWidget {
  const _Header({required this.read});

  final TillRead read;

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.all(20),
      decoration: BoxDecoration(
        gradient: HqSurfaces.heroGradient,
        borderRadius: BorderRadius.circular(20),
        boxShadow: HqSurfaces.hero,
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              Expanded(
                child: Text(
                  'X READ - ${read.tillName}'.toUpperCase(),
                  maxLines: 1,
                  overflow: TextOverflow.ellipsis,
                  style: const TextStyle(
                    fontSize: 10.5,
                    fontWeight: FontWeight.w700,
                    color: HqOnDark.tertiary,
                    letterSpacing: 0.8,
                  ),
                ),
              ),
              Container(
                padding:
                    const EdgeInsets.symmetric(horizontal: 9, vertical: 4),
                decoration: BoxDecoration(
                  color: Colors.white.withValues(alpha: 0.12),
                  borderRadius: BorderRadius.circular(20),
                ),
                child: const Text(
                  'STAYS OPEN',
                  style: TextStyle(
                    fontSize: 9,
                    fontWeight: FontWeight.w800,
                    color: Colors.white,
                    letterSpacing: 0.5,
                  ),
                ),
              ),
            ],
          ),
          const SizedBox(height: 10),
          Amount(
            tzs(read.totalSales),
            style: const TextStyle(
              fontSize: 34,
              fontWeight: FontWeight.w700,
              color: Colors.white,
              height: 1.05,
              letterSpacing: -1,
            ),
          ),
          const SizedBox(height: 6),
          Text(
            '${read.cashierName} · ${read.invoiceCount} sales',
            maxLines: 1,
            overflow: TextOverflow.ellipsis,
            style: const TextStyle(fontSize: 12.5, color: HqOnDark.secondary),
          ),
        ],
      ),
    );
  }
}

class _SessionCard extends StatelessWidget {
  const _SessionCard({
    required this.session,
    required this.selected,
    required this.onTap,
  });

  final SessionRow session;
  final bool selected;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    return Material(
      color: HqColors.panel,
      borderRadius: BorderRadius.circular(16),
      child: InkWell(
        onTap: onTap,
        borderRadius: BorderRadius.circular(16),
        child: Container(
          padding: const EdgeInsets.all(16),
          decoration: BoxDecoration(
            borderRadius: BorderRadius.circular(16),
            border: Border.all(
              color: selected ? HqColors.brand : HqColors.line,
              width: selected ? 1.6 : 1,
            ),
          ),
          child: Row(
            children: [
              Container(
                width: 42,
                height: 42,
                decoration: BoxDecoration(
                  color: selected ? HqColors.brand : HqColors.brandSoft,
                  borderRadius: BorderRadius.circular(11),
                ),
                child: Icon(
                  Icons.point_of_sale_outlined,
                  size: 21,
                  color: selected ? Colors.white : HqColors.brand,
                ),
              ),
              const SizedBox(width: 13),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(
                      session.tillName,
                      maxLines: 1,
                      overflow: TextOverflow.ellipsis,
                      style: const TextStyle(
                        fontSize: 15,
                        fontWeight: FontWeight.w700,
                        color: HqColors.ink,
                      ),
                    ),
                    const SizedBox(height: 2),
                    Text(
                      session.cashierName,
                      maxLines: 1,
                      overflow: TextOverflow.ellipsis,
                      style: HqText.tiny,
                    ),
                  ],
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }
}

/// An X read as a document — the figures a manager is asked to account for,
/// in the order they are counted at the drawer.
ExportDoc _readDoc(TillRead read) => ExportDoc(
      title: 'X read - ${read.tillName}',
      subtitle: 'Cashier ${read.cashierName}',
      meta: [
        'Opened ${read.openedAt}',
        'A read only - the till stays open.',
      ],
      columns: const ['Tender', 'Transactions', 'Amount'],
      rows: [
        for (final t in read.tenders)
          [
            Cell.text(t.label),
            Cell.number(t.count.toDouble()),
            Cell.money(t.amount),
          ],
      ],
      totals: [
        DocTotal('Invoices', Cell.number(read.invoiceCount.toDouble())),
        DocTotal('Total sales', Cell.money(read.totalSales)),
        DocTotal('Opening float', Cell.money(read.openingFloat)),
        DocTotal('Cash sales', Cell.money(read.cashTender)),
        DocTotal('Paid out', Cell.money(read.payoutsNet)),
        DocTotal('Cash the drawer should hold', Cell.money(read.expectedCash)),
      ],
    );
