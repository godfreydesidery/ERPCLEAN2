import 'package:flutter/material.dart';

import '../app/format.dart';
import '../app/theme.dart';
import '../data/mock.dart';
import '../widgets/common.dart';
import '../widgets/kit.dart';

/// Close a till session: pick the till, count the cash, see the difference.
/// Mockup: the arithmetic is real, the closing is not.
class CloseSessionScreen extends StatefulWidget {
  const CloseSessionScreen({super.key});

  @override
  State<CloseSessionScreen> createState() => _CloseSessionScreenState();
}

class _CloseSessionScreenState extends State<CloseSessionScreen> {
  TillSession? _session;
  final _counted = TextEditingController();

  @override
  void initState() {
    super.initState();
    _counted.addListener(() => setState(() {}));
  }

  @override
  void dispose() {
    _counted.dispose();
    super.dispose();
  }

  num? get _countedValue => num.tryParse(_counted.text);

  num? get _difference =>
      _session == null || _countedValue == null
          ? null
          : _countedValue! - _session!.cash;

  Future<void> _submit() async {
    final diff = _difference ?? 0;
    await showDoneSheet(
      context,
      title: 'Session closed',
      detail: '${_session!.till} · ${_session!.cashier}\n'
          '${diff == 0 ? 'Balanced exactly' : '${tzs(diff.abs())} '
              '${diff > 0 ? 'over' : 'short'}'}',
    );
    if (mounted) Navigator.of(context).pop();
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: HqColors.bg,
      appBar: AppBar(title: const Text('Close session', style: HqText.title)),
      body: ListView(
        padding: const EdgeInsets.fromLTRB(20, 8, 20, 28),
        children: [
          SectionLabel(
            text: 'OPEN TILLS',
            trailing: '${kOpenSessions.length} TODAY',
          ),
          const SizedBox(height: 10),
          for (final s in kOpenSessions) ...[
            _SessionCard(
              session: s,
              selected: _session == s,
              onTap: () => setState(() {
                _session = _session == s ? null : s;
                _counted.clear();
              }),
            ),
            const SizedBox(height: 10),
          ],
          if (_session != null) ...[
            const SizedBox(height: 12),
            const SectionLabel(text: 'COUNT THE CASH'),
            const SizedBox(height: 12),
            HqCard(
              child: Column(
                children: [
                  FigureRow(
                    label: 'Sales this session',
                    value: tzs(_session!.sales),
                  ),
                  const Divider(height: 14),
                  FigureRow(
                    label: 'Transactions',
                    value: '${_session!.transactions}',
                  ),
                  const Divider(height: 14),
                  FigureRow(
                    label: 'Cash the system expects',
                    value: tzs(_session!.cash),
                    emphasise: true,
                  ),
                ],
              ),
            ),
            const SizedBox(height: 18),
            HqField(
              label: 'Cash counted in the drawer',
              required: true,
              controller: _counted,
              keyboardType: TextInputType.number,
              hint: '0',
              suffixText: 'TZS',
              helper: 'Count the notes and coins before you close.',
            ),
            if (_difference != null) ...[
              const SizedBox(height: 16),
              _DifferenceCard(difference: _difference!),
            ],
          ],
          const SizedBox(height: 26),
          FilledButton(
            onPressed: _session != null && _countedValue != null
                ? _submit
                : null,
            child: const Text('Close session'),
          ),
          const SizedBox(height: 10),
          Center(
            child: Text(
              'Demo build — no till is actually closed.',
              style: HqText.tiny,
            ),
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

  final TillSession session;
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
                      session.till,
                      style: const TextStyle(
                        fontSize: 15,
                        fontWeight: FontWeight.w700,
                        color: HqColors.ink,
                      ),
                    ),
                    const SizedBox(height: 2),
                    Text(
                      '${session.cashier} · opened ${session.openedAt}',
                      maxLines: 1,
                      overflow: TextOverflow.ellipsis,
                      style: HqText.tiny,
                    ),
                  ],
                ),
              ),
              const SizedBox(width: 8),
              Column(
                crossAxisAlignment: CrossAxisAlignment.end,
                children: [
                  Text(
                    tzs(session.sales),
                    style: const TextStyle(
                      fontSize: 14,
                      fontWeight: FontWeight.w700,
                      color: HqColors.ink,
                    ),
                  ),
                  Text('${session.transactions} sales', style: HqText.tiny),
                ],
              ),
            ],
          ),
        ),
      ),
    );
  }
}

class _DifferenceCard extends StatelessWidget {
  const _DifferenceCard({required this.difference});

  final num difference;

  @override
  Widget build(BuildContext context) {
    final balanced = difference == 0;
    final over = difference > 0;
    final color = balanced
        ? HqColors.good
        : (over ? HqColors.warn : HqColors.bad);
    final soft = balanced
        ? HqColors.goodSoft
        : (over ? HqColors.warnSoft : HqColors.badSoft);

    return Container(
      padding: const EdgeInsets.all(16),
      decoration: BoxDecoration(
        color: soft,
        borderRadius: BorderRadius.circular(14),
        border: Border.all(color: color.withValues(alpha: 0.3)),
      ),
      child: Row(
        children: [
          Icon(
            balanced ? Icons.check_circle_rounded : Icons.error_outline_rounded,
            color: color,
            size: 26,
          ),
          const SizedBox(width: 13),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  balanced
                      ? 'Balanced exactly'
                      : '${tzs(difference.abs())} ${over ? 'over' : 'short'}',
                  style: TextStyle(
                    fontSize: 16,
                    fontWeight: FontWeight.w700,
                    color: color,
                  ),
                ),
                const SizedBox(height: 2),
                Text(
                  balanced
                      ? 'The drawer matches the system.'
                      : over
                          ? 'There is more cash than the system expects.'
                          : 'There is less cash than the system expects.',
                  style: HqText.tiny,
                ),
              ],
            ),
          ),
        ],
      ),
    );
  }
}
