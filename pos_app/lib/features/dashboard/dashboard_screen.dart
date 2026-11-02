import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:intl/intl.dart';

import '../../app/theme.dart';
import '../../core/api/api_exception.dart';
import '../../core/money.dart';
import '../../models/dashboard.dart';
import '../../models/enums.dart';
import '../../state/providers.dart';
import '../../widgets/ui.dart';

/// The till's sales dashboard (Tulia 2026-11-02): this branch's sales for a
/// day or a range — totals, per day, per payment type, and what sold in each
/// department.
Future<void> showDashboard(BuildContext context) => Navigator.of(context)
    .push(MaterialPageRoute<void>(builder: (_) => const DashboardScreen()));

/// A payment type as the dashboard names it.
String dashboardTenderLabel(String wire) {
  for (final t in TenderType.values) {
    if (t.wire == wire) return t == TenderType.mobileMoney ? 'Mobile money' : t.label;
  }
  return wire.replaceAll('_', ' ');
}

/// The ranges offered as one-tap buttons.
enum DashboardRange { today, yesterday, last7, thisMonth, custom }

class DashboardScreen extends ConsumerStatefulWidget {
  const DashboardScreen({super.key});
  @override
  ConsumerState<DashboardScreen> createState() => _DashboardScreenState();
}

class _DashboardScreenState extends ConsumerState<DashboardScreen> {
  DashboardRange _range = DashboardRange.today;
  late DateTime _from;
  late DateTime _to;
  PosDashboard? _data;
  String? _error;
  bool _loading = false;

  @override
  void initState() {
    super.initState();
    _setRange(DashboardRange.today);
  }

  static DateTime _day(DateTime d) => DateTime(d.year, d.month, d.day);

  void _setRange(DashboardRange r, {DateTimeRange? custom}) {
    final today = _day(DateTime.now());
    final (from, to) = switch (r) {
      DashboardRange.today => (today, today),
      DashboardRange.yesterday => (
          today.subtract(const Duration(days: 1)),
          today.subtract(const Duration(days: 1))
        ),
      DashboardRange.last7 => (today.subtract(const Duration(days: 6)), today),
      DashboardRange.thisMonth => (DateTime(today.year, today.month), today),
      DashboardRange.custom => (_day(custom!.start), _day(custom.end)),
    };
    setState(() {
      _range = r;
      _from = from;
      _to = to;
    });
    _load();
  }

  Future<void> _pickDates() async {
    final now = DateTime.now();
    final picked = await showDateRangePicker(
      context: context,
      firstDate: DateTime(now.year - 2),
      lastDate: _day(now),
      initialDateRange: DateTimeRange(start: _from, end: _to),
    );
    if (picked != null && mounted) _setRange(DashboardRange.custom, custom: picked);
  }

  Future<void> _load() async {
    final from = _from, to = _to;
    setState(() {
      _loading = true;
      _error = null;
    });
    try {
      final d = await ref.read(saleServiceProvider).dashboard(from, to);
      if (!mounted || from != _from || to != _to) return;
      setState(() {
        _data = d;
        _loading = false;
      });
    } on ApiException catch (e) {
      if (!mounted) return;
      setState(() {
        _loading = false;
        _error = e.isUnreachable ? ApiException.unreachableMessage : e.message;
      });
    }
  }

  String get _rangeLabel {
    final f = DateFormat('d MMM yyyy');
    return _from == _to ? f.format(_from) : '${f.format(_from)} – ${f.format(_to)}';
  }

  @override
  Widget build(BuildContext context) {
    final d = _data;
    return Scaffold(
      backgroundColor: AppColors.bg,
      appBar: AppBar(
        title: Text(d == null || d.branchName.isEmpty
            ? 'Sales dashboard'
            : 'Sales dashboard · ${d.branchName}'),
        actions: [
          IconButton(
              tooltip: 'Refresh',
              onPressed: _loading ? null : _load,
              icon: const Icon(Icons.refresh)),
        ],
      ),
      body: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          _rangeBar(),
          if (_loading) const LinearProgressIndicator(minHeight: 2),
          Expanded(child: _body(d)),
        ],
      ),
    );
  }

  Widget _rangeBar() {
    Widget chip(String label, DashboardRange r) => Padding(
          padding: const EdgeInsets.only(right: 8),
          child: ChoiceChip(
            label: Text(label),
            selected: _range == r,
            showCheckmark: false,
            onSelected: (_) => r == DashboardRange.custom ? _pickDates() : _setRange(r),
          ),
        );
    return Container(
      color: AppColors.panel,
      padding: const EdgeInsets.fromLTRB(16, 10, 16, 10),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          SingleChildScrollView(
            scrollDirection: Axis.horizontal,
            child: Row(children: [
              chip('Today', DashboardRange.today),
              chip('Yesterday', DashboardRange.yesterday),
              chip('Last 7 days', DashboardRange.last7),
              chip('This month', DashboardRange.thisMonth),
              chip('Choose dates…', DashboardRange.custom),
            ]),
          ),
          const SizedBox(height: 6),
          Text(_rangeLabel, style: const TextStyle(color: AppColors.ink2, fontSize: 13)),
        ],
      ),
    );
  }

  Widget _body(PosDashboard? d) {
    if (_error != null) {
      return Center(
        child: Padding(
          padding: const EdgeInsets.all(24),
          child: Column(mainAxisSize: MainAxisSize.min, children: [
            Text(_error!, textAlign: TextAlign.center),
            const SizedBox(height: 12),
            OrbixButton(label: 'Try again', onPressed: _load),
          ]),
        ),
      );
    }
    if (d == null) return const SizedBox.shrink();
    if (d.invoiceCount == 0) {
      return const Center(
          child: Text('No sales in these dates.', style: TextStyle(color: AppColors.ink3)));
    }
    return LayoutBuilder(builder: (context, box) {
      final wide = box.maxWidth >= 900;
      final left = [_totals(d), _days(d), _tenders(d)];
      final right = [_departments(d)];
      return SingleChildScrollView(
        padding: const EdgeInsets.all(16),
        child: wide
            ? Row(crossAxisAlignment: CrossAxisAlignment.start, children: [
                Expanded(child: Column(children: left)),
                const SizedBox(width: 16),
                Expanded(child: Column(children: right)),
              ])
            : Column(children: [...left, ...right]),
      );
    });
  }

  Widget _card(String title, Widget child) => Container(
        margin: const EdgeInsets.only(bottom: 16),
        padding: const EdgeInsets.all(16),
        decoration: BoxDecoration(
          color: AppColors.panel,
          borderRadius: AppRadii.brLg,
          border: Border.all(color: AppColors.line),
        ),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            Text(title, style: const TextStyle(fontWeight: FontWeight.w700, fontSize: 15)),
            const SizedBox(height: 10),
            child,
          ],
        ),
      );

  Widget _totals(PosDashboard d) {
    Widget tile(String label, String value) => Expanded(
          child: Container(
            padding: const EdgeInsets.all(14),
            decoration: BoxDecoration(color: AppColors.brandSoft, borderRadius: AppRadii.brSm),
            child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
              Text(label, style: const TextStyle(color: AppColors.brandD, fontSize: 12)),
              const SizedBox(height: 4),
              FittedBox(
                fit: BoxFit.scaleDown,
                alignment: Alignment.centerLeft,
                child: Text(value,
                    style: const TextStyle(
                        fontSize: 20, fontWeight: FontWeight.w800, color: AppColors.brandD)),
              ),
            ]),
          ),
        );
    final avg = d.invoiceCount == 0 ? 0.0 : d.grossTotal / d.invoiceCount;
    return Padding(
      padding: const EdgeInsets.only(bottom: 16),
      child: Row(children: [
        tile('Sales (${d.currency})', formatAmount(d.grossTotal)),
        const SizedBox(width: 10),
        tile('Number of sales', '${d.invoiceCount}'),
        const SizedBox(width: 10),
        tile('Average sale', formatAmount(avg)),
      ]),
    );
  }

  Widget _row(String label, String value, {String? note, double? share}) => Padding(
        padding: const EdgeInsets.symmetric(vertical: 5),
        child: Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
          Row(children: [
            Expanded(
              child: Text.rich(TextSpan(children: [
                TextSpan(text: label),
                if (note != null)
                  TextSpan(
                      text: '  $note',
                      style: const TextStyle(color: AppColors.ink3, fontSize: 12)),
              ])),
            ),
            Text(value, style: numStyle(weight: FontWeight.w700)),
          ]),
          if (share != null) ...[
            const SizedBox(height: 4),
            ClipRRect(
              borderRadius: BorderRadius.circular(3),
              child: LinearProgressIndicator(
                value: share.clamp(0, 1),
                minHeight: 5,
                backgroundColor: AppColors.line,
                color: AppColors.brand,
              ),
            ),
          ],
        ]),
      );

  Widget _days(PosDashboard d) {
    if (d.days.length < 2) return const SizedBox.shrink();
    final top = d.days.map((x) => x.gross).fold(0.0, (a, b) => a > b ? a : b);
    final f = DateFormat('EEE d MMM');
    return _card('By day', Column(children: [
      for (final x in d.days)
        _row(f.format(DateTime.parse(x.date)), formatAmount(x.gross),
            note: '${x.invoices} sales', share: top > 0 ? x.gross / top : 0),
    ]));
  }

  Widget _tenders(PosDashboard d) {
    final total = d.tenders.fold(0.0, (s, t) => s + t.amount);
    return _card('By payment type', Column(children: [
      for (final t in d.tenders)
        _row(dashboardTenderLabel(t.tenderType), formatAmount(t.amount),
            note: '${t.payments}', share: total > 0 ? t.amount / total : 0),
    ]));
  }

  Widget _departments(PosDashboard d) {
    return _card('By department', Column(children: [
      for (final dept in d.departments)
        Theme(
          data: Theme.of(context).copyWith(dividerColor: Colors.transparent),
          child: ExpansionTile(
            tilePadding: EdgeInsets.zero,
            childrenPadding: const EdgeInsets.only(left: 8, bottom: 8),
            title: _row(dept.department, formatAmount(dept.gross),
                note: '${dept.items.length} items',
                share: d.grossTotal > 0 ? dept.gross / d.grossTotal : 0),
            children: [
              for (final i in dept.items)
                _row(i.name, formatAmount(i.gross),
                    note: '${formatAmount(i.quantity, decimals: _decimals(i.quantity))} '
                        '${i.unit}'),
            ],
          ),
        ),
    ]));
  }

  static int _decimals(double q) => q == q.roundToDouble() ? 0 : 2;
}
