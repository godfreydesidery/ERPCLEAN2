import 'package:flutter/material.dart';
import 'package:intl/intl.dart';

import '../app/format.dart';
import '../app/theme.dart';
import '../core/export/report_doc.dart';
import '../services/operations_service.dart';
import '../widgets/common.dart';
import '../widgets/kit.dart';

/// Pieces shared by the till screens: when a session happened, how its
/// over/short reads, and the Z read itself (on screen and as a document).

final _stamp = DateFormat('d MMM yyyy, HH:mm');

/// "12 Nov 2026, 18:40" in the phone's own time, or the raw text if the
/// server ever sends something that is not an ISO instant.
String when(String? iso) {
  if (iso == null || iso.isEmpty) return '-';
  final t = DateTime.tryParse(iso);
  return t == null ? iso : _stamp.format(t.toLocal());
}

/// "Over TZS 2,000", "Short TZS 5,000" or "Balanced". Variance is counted
/// minus expected, so positive is cash the drawer should not have had.
String overShort(double variance) {
  if (variance.abs() < 0.005) return 'Balanced';
  return variance > 0 ? 'Over ${tzs(variance)}' : 'Short ${tzs(-variance)}';
}

Color overShortColor(double variance) {
  if (variance.abs() < 0.005) return HqColors.good;
  return variance > 0 ? HqColors.warn : HqColors.bad;
}

/// The final figures of a reconciled session.
class ZReadView extends StatelessWidget {
  const ZReadView({super.key, required this.z});

  final ZRead z;

  @override
  Widget build(BuildContext context) {
    final read = z.read;
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Container(
          padding: const EdgeInsets.all(20),
          decoration: BoxDecoration(
            gradient: HqSurfaces.heroGradient,
            borderRadius: BorderRadius.circular(20),
            boxShadow: HqSurfaces.hero,
          ),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text(
                'Z READ - ${read.tillName}'.toUpperCase(),
                maxLines: 1,
                overflow: TextOverflow.ellipsis,
                style: const TextStyle(
                  fontSize: 10.5,
                  fontWeight: FontWeight.w700,
                  color: HqOnDark.tertiary,
                  letterSpacing: 0.8,
                ),
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
                style:
                    const TextStyle(fontSize: 12.5, color: HqOnDark.secondary),
              ),
              const SizedBox(height: 2),
              Text(
                'Closed ${when(z.closedAt)}',
                style:
                    const TextStyle(fontSize: 12.5, color: HqOnDark.secondary),
              ),
            ],
          ),
        ),
        const SizedBox(height: 16),
        const SectionLabel(text: 'HOW THEY PAID'),
        const SizedBox(height: 10),
        HqCard(
          child: Column(
            children: [
              if (read.tenders.isEmpty)
                Text('No sales in this session.', style: HqText.body)
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
        const SectionLabel(text: 'CASH'),
        const SizedBox(height: 10),
        HqCard(
          child: Column(
            children: [
              FigureRow(label: 'Opening float', value: tzs(read.openingFloat)),
              const Divider(height: 14),
              FigureRow(label: 'Cash sales', value: tzs(read.cashTender)),
              const Divider(height: 14),
              FigureRow(
                label: 'Paid out',
                value: tzs(read.payoutsNet),
                valueColor: read.payoutsNet > 0 ? HqColors.bad : null,
              ),
              const Divider(height: 14),
              FigureRow(
                label: 'Expected in the drawer',
                value: tzs(read.expectedCash),
              ),
              const Divider(height: 14),
              FigureRow(label: 'Counted', value: tzs(z.countedCash)),
              const Divider(height: 16, thickness: 1.4),
              FigureRow(
                label: 'Over / short',
                value: overShort(z.variance),
                emphasise: true,
                valueColor: overShortColor(z.variance),
              ),
            ],
          ),
        ),
        if (z.breakdown case final b?) ...[
          const SizedBox(height: 18),
          const SectionLabel(text: 'SUMMARY'),
          const SizedBox(height: 10),
          HqCard(
            child: Column(
              children: [
                FigureRow(label: 'Gross sales', value: tzs(b.gross)),
                const Divider(height: 14),
                FigureRow(
                  label: 'Discounts',
                  value: tzs(b.discount),
                  valueColor: b.discount > 0 ? HqColors.bad : null,
                ),
                const Divider(height: 14),
                FigureRow(label: 'of which VAT', value: tzs(b.vat)),
                const Divider(height: 14),
                FigureRow(label: 'Transactions', value: '${b.transactions}'),
                const Divider(height: 16, thickness: 1.4),
                FigureRow(
                  label: 'Net sales',
                  value: tzs(b.net),
                  emphasise: true,
                  valueColor: HqColors.brand,
                ),
              ],
            ),
          ),
          for (final (title, lines, counts) in [
            ('CATEGORIES', b.categories, false),
            ('DEPARTMENTS', b.departments, false),
            ('TEAM MEMBERS', b.team, true),
          ])
            if (lines.isNotEmpty) ...[
              const SizedBox(height: 18),
              SectionLabel(text: title),
              const SizedBox(height: 10),
              HqCard(
                child: Column(
                  children: [
                    for (var i = 0; i < lines.length; i++) ...[
                      if (i > 0) const Divider(height: 14),
                      FigureRow(
                        label: counts
                            ? '${lines[i].name} (${lines[i].count})'
                            : lines[i].name,
                        value: tzs(lines[i].amount),
                      ),
                    ],
                  ],
                ),
              ),
            ],
        ],
        const SizedBox(height: 18),
        OutlinedButton.icon(
          onPressed: () => showShareSheet(context, zReadDoc(z)),
          icon: const Icon(Icons.ios_share_rounded, size: 19),
          label: const Text('Export and share'),
        ),
      ],
    );
  }
}

/// A Z read as a document, in the order the drawer is accounted for.
ExportDoc zReadDoc(ZRead z) {
  final read = z.read;
  return ExportDoc(
    title: 'Z read - ${read.tillName}',
    subtitle: 'Cashier ${read.cashierName}',
    meta: [
      if (z.branchName != null) 'Branch ${z.branchName}',
      'Opened ${when(read.openedAt)}',
      'Closed ${when(z.closedAt)}',
      'Reconciled ${when(z.reconciledAt)}',
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
      DocTotal('Expected in the drawer', Cell.money(read.expectedCash)),
      DocTotal('Counted', Cell.money(z.countedCash)),
      DocTotal('Over (+) / short (-)', Cell.money(z.variance)),
      // The sales sections, when the server sends them (appended: the lines
      // above are exactly as before).
      if (z.breakdown case final b?) ...[
        DocTotal('Gross sales', Cell.money(b.gross)),
        DocTotal('Discounts', Cell.money(b.discount)),
        DocTotal('Net sales', Cell.money(b.net)),
        DocTotal('of which VAT', Cell.money(b.vat)),
        for (final c in b.categories)
          DocTotal('Category · ${c.name}', Cell.money(c.amount)),
        for (final d in b.departments)
          DocTotal('Department · ${d.name}', Cell.money(d.amount)),
        for (final m in b.team)
          DocTotal('Team · ${m.name} (${m.count})', Cell.money(m.amount)),
      ],
    ],
  );
}
