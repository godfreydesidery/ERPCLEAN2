import 'package:flutter/material.dart';

import '../../app/theme.dart';
import '../../core/config/app_config.dart';
import '../../core/money.dart';
import '../../models/restaurant.dart';
import '../../services/receipt_printer.dart';
import '../../widgets/ui.dart';
import 'restaurant_printing.dart';

/// Dialogs for the restaurant register (ERP ADR-0065). Each returns the
/// cashier's answer; none of them talks to the server.

// ---------------------------------------------------------------- guests

/// How many guests at the table. Returns null when backed out; 0 = skip.
Future<int?> askGuestCount(BuildContext context, String tableName) {
  return showDialog<int>(
    context: context,
    builder: (_) => AlertDialog(
      shape: RoundedRectangleBorder(borderRadius: AppRadii.brLg),
      title: Text('Table $tableName — how many guests?'),
      content: SizedBox(
        width: 360,
        child: Wrap(
          spacing: 8,
          runSpacing: 8,
          children: [
            for (var n = 1; n <= 12; n++)
              SizedBox(
                width: 72,
                height: 52,
                child: OutlinedButton(
                  onPressed: () => Navigator.pop(context, n),
                  child: Text('$n',
                      style: const TextStyle(
                          fontSize: 18, fontWeight: FontWeight.w700)),
                ),
              ),
          ],
        ),
      ),
      actions: [
        TextButton(
            onPressed: () => Navigator.pop(context),
            child: const Text('Cancel')),
        TextButton(
            onPressed: () => Navigator.pop(context, 0),
            child: const Text('Skip')),
      ],
    ),
  );
}

// ---------------------------------------------------------------- busy table

/// A table with more than one party: pick one, or start a new order there.
/// Returns the chosen order, a null-order result for "new", or null on cancel.
Future<({RestaurantOrder? order})?> pickOrderAtTable(
    BuildContext context, String tableName, List<RestaurantOrder> orders) {
  return showDialog<({RestaurantOrder? order})>(
    context: context,
    builder: (_) => AlertDialog(
      shape: RoundedRectangleBorder(borderRadius: AppRadii.brLg),
      title: Text('Table $tableName'),
      content: SizedBox(
        width: 380,
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            for (final o in orders)
              ListTile(
                leading: const Icon(Icons.receipt_long_outlined),
                title: Text(o.orderNumber),
                subtitle: Text('${o.openedByName} · '
                    '${o.activeLines.length} items'
                    '${o.status == OrderStatus.billed ? ' · bill printed' : ''}'),
                onTap: () => Navigator.pop(context, (order: o)),
              ),
          ],
        ),
      ),
      actions: [
        TextButton(
            onPressed: () => Navigator.pop(context),
            child: const Text('Cancel')),
        OrbixButton(
            label: 'New order at this table',
            icon: Icons.add,
            onPressed: () => Navigator.pop(context, (order: null))),
      ],
    ),
  );
}

// ---------------------------------------------------------------- void / cancel

/// Reason (and how many, for a line of several) for voiding a SENT item.
/// Returns null when backed out.
Future<({String reason, double quantity})?> askVoid(
    BuildContext context, RestaurantOrderLine line) {
  return showDialog<({String reason, double quantity})>(
    context: context,
    builder: (_) => _VoidDialog(line: line),
  );
}

class _VoidDialog extends StatefulWidget {
  const _VoidDialog({required this.line});
  final RestaurantOrderLine line;
  @override
  State<_VoidDialog> createState() => _VoidDialogState();
}

class _VoidDialogState extends State<_VoidDialog> {
  static const _quick = ['Customer changed mind', 'Wrong item', 'Out of stock'];
  final _reason = TextEditingController();
  late double _qty = widget.line.quantity;

  @override
  void dispose() {
    _reason.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final l = widget.line;
    final whole = l.quantity;
    return AlertDialog(
      shape: RoundedRectangleBorder(borderRadius: AppRadii.brLg),
      title: Text('Void ${l.productName}'),
      content: SizedBox(
        width: 400,
        child: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(
                '${l.prepStationName ?? 'The kitchen'} has already been asked to make this. '
                'A VOID slip will print there.',
                style: const TextStyle(color: AppColors.ink2)),
            if (whole > 1) ...[
              const SizedBox(height: 14),
              Row(
                children: [
                  const Text('How many?'),
                  const Spacer(),
                  IconButton(
                      onPressed: _qty > 1 ? () => setState(() => _qty -= 1) : null,
                      icon: const Icon(Icons.remove_circle_outline)),
                  Text(formatAmount(_qty, decimals: 0),
                      style: const TextStyle(
                          fontSize: 18, fontWeight: FontWeight.w700)),
                  IconButton(
                      onPressed: _qty < whole ? () => setState(() => _qty += 1) : null,
                      icon: const Icon(Icons.add_circle_outline)),
                  Text(' of ${formatAmount(whole, decimals: 0)}'),
                ],
              ),
            ],
            const SizedBox(height: 14),
            Wrap(
              spacing: 6,
              children: [
                for (final q in _quick)
                  ActionChip(
                      label: Text(q),
                      onPressed: () => setState(() => _reason.text = q)),
              ],
            ),
            const SizedBox(height: 8),
            TextField(
              controller: _reason,
              maxLength: 255,
              decoration: const InputDecoration(labelText: 'Reason'),
              onChanged: (_) => setState(() {}),
            ),
          ],
        ),
      ),
      actions: [
        TextButton(
            onPressed: () => Navigator.pop(context),
            child: const Text('Keep it')),
        OrbixButton(
          label: 'Void',
          kind: BtnKind.danger,
          onPressed: _reason.text.trim().isEmpty
              ? null
              : () => Navigator.pop(
                  context, (reason: _reason.text.trim(), quantity: _qty)),
        ),
      ],
    );
  }
}

/// Confirms cancelling a whole order. A reason is required when anything was
/// already sent. Returns the reason ('' when none was needed), or null.
Future<String?> askCancelOrder(
    BuildContext context, RestaurantOrder order, {required bool anySent}) {
  final reason = TextEditingController();
  return showDialog<String>(
    context: context,
    builder: (_) => StatefulBuilder(
      builder: (context, setState) => AlertDialog(
        shape: RoundedRectangleBorder(borderRadius: AppRadii.brLg),
        title: Text('Cancel ${order.orderNumber}?'),
        content: SizedBox(
          width: 400,
          child: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text(anySent
                  ? 'Some items were already sent. VOID slips will print at the kitchen and bar.'
                  : 'Nothing was sent yet, so nothing was made.'),
              if (anySent) ...[
                const SizedBox(height: 10),
                TextField(
                  controller: reason,
                  maxLength: 255,
                  decoration: const InputDecoration(labelText: 'Reason'),
                  onChanged: (_) => setState(() {}),
                ),
              ],
            ],
          ),
        ),
        actions: [
          TextButton(
              onPressed: () => Navigator.pop(context),
              child: const Text('Keep the order')),
          OrbixButton(
            label: 'Cancel order',
            kind: BtnKind.danger,
            onPressed: anySent && reason.text.trim().isEmpty
                ? null
                : () => Navigator.pop(context, reason.text.trim()),
          ),
        ],
      ),
    ),
  ).whenComplete(reason.dispose);
}

// ---------------------------------------------------------------- send first?

enum SendFirst { send, skip }

/// Items are still unsent when billing or paying: send them first?
/// Returns null when backed out.
Future<SendFirst?> askSendFirst(BuildContext context, int pending, String doing) {
  return showDialog<SendFirst>(
    context: context,
    builder: (_) => AlertDialog(
      shape: RoundedRectangleBorder(borderRadius: AppRadii.brLg),
      title: Text('$pending item${pending == 1 ? '' : 's'} not sent yet'),
      content: Text(
          'Send them to the kitchen and bar before you $doing? Items poured or '
          'served at the counter can be billed without sending.'),
      actions: [
        TextButton(
            onPressed: () => Navigator.pop(context),
            child: const Text('Back')),
        TextButton(
            onPressed: () => Navigator.pop(context, SendFirst.skip),
            child: Text('${doing[0].toUpperCase()}${doing.substring(1)} without sending')),
        OrbixButton(
            label: 'Send first',
            icon: Icons.soup_kitchen_outlined,
            onPressed: () => Navigator.pop(context, SendFirst.send)),
      ],
    ),
  );
}

// ---------------------------------------------------------------- printing

/// Slips that did not print. Each can be reprinted; the order is already sent.
Future<void> showSlipFailures(BuildContext context, List<SlipFailure> failures,
    Future<String?> Function(PrepSlip slip) reprint) {
  return showDialog<void>(
    context: context,
    builder: (_) => _SlipFailuresDialog(failures: failures, reprint: reprint),
  );
}

class _SlipFailuresDialog extends StatefulWidget {
  const _SlipFailuresDialog({required this.failures, required this.reprint});
  final List<SlipFailure> failures;
  final Future<String?> Function(PrepSlip slip) reprint;
  @override
  State<_SlipFailuresDialog> createState() => _SlipFailuresDialogState();
}

class _SlipFailuresDialogState extends State<_SlipFailuresDialog> {
  late final Map<SlipFailure, String?> _status = {
    for (final f in widget.failures) f: f.message,
  };
  SlipFailure? _busy;

  Future<void> _retry(SlipFailure f) async {
    setState(() => _busy = f);
    final err = await widget.reprint(f.slip);
    if (!mounted) return;
    setState(() {
      _busy = null;
      _status[f] = err;
    });
  }

  @override
  Widget build(BuildContext context) {
    return AlertDialog(
      shape: RoundedRectangleBorder(borderRadius: AppRadii.brLg),
      title: const Row(children: [
        Icon(Icons.print_disabled_outlined, color: AppColors.warn),
        SizedBox(width: 10),
        Text('Some slips did not print'),
      ]),
      content: SizedBox(
        width: 460,
        child: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            const Text(
                'The order IS sent — only the paper is missing. Reprint, or tell '
                'the station by hand.',
                style: TextStyle(color: AppColors.ink2)),
            const SizedBox(height: 12),
            for (final f in widget.failures)
              ListTile(
                contentPadding: EdgeInsets.zero,
                leading: Icon(
                    _status[f] == null ? Icons.check_circle : Icons.error_outline,
                    color: _status[f] == null ? AppColors.ok : AppColors.danger),
                title: Text('${f.slip.stationName}${f.slip.isVoid ? ' (VOID)' : ''}'),
                subtitle: Text(_status[f] ?? 'Printed.'),
                trailing: _status[f] == null
                    ? null
                    : OrbixButton(
                        label: 'Reprint',
                        kind: BtnKind.ghost,
                        busy: _busy == f,
                        onPressed: _busy == null ? () => _retry(f) : null),
              ),
          ],
        ),
      ),
      actions: [
        OrbixButton(label: 'Done', onPressed: () => Navigator.pop(context)),
      ],
    );
  }
}

/// The bill as printed (or as it would have printed), with a reprint action.
Future<void> showBillPreview(BuildContext context,
    {required String text, String? printError, VoidCallback? onPrintAgain}) {
  return showDialog<void>(
    context: context,
    builder: (_) => AlertDialog(
      shape: RoundedRectangleBorder(borderRadius: AppRadii.brLg),
      title: const Text('Bill'),
      content: SizedBox(
        width: 420,
        child: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            if (printError != null)
              Padding(
                padding: const EdgeInsets.only(bottom: 10),
                child: Text('Not printed: $printError',
                    style: const TextStyle(
                        color: AppColors.danger, fontWeight: FontWeight.w600)),
              ),
            Flexible(
              child: SingleChildScrollView(
                child: Container(
                  color: Colors.white,
                  padding: const EdgeInsets.all(10),
                  child: Text(text,
                      style: const TextStyle(
                          fontFamily: 'Consolas',
                          fontSize: 12.5,
                          height: 1.3,
                          color: Color(0xFF1E293B))),
                ),
              ),
            ),
          ],
        ),
      ),
      actions: [
        if (onPrintAgain != null)
          TextButton(
              onPressed: () {
                Navigator.pop(context);
                onPrintAgain();
              },
              child: const Text('Print again')),
        OrbixButton(label: 'Done', onPressed: () => Navigator.pop(context)),
      ],
    ),
  );
}

// ---------------------------------------------------------------- item choices (ADR-0066)

/// What the waiter chose for an item.
typedef ItemChoice = ({List<String> optionUids, int course, bool held, String? note});

/// The item dialog: its choices ([groups], each with its rule), the course, a
/// hold, and a free note. Returns null when backed out.
Future<ItemChoice?> askItemChoices(
    BuildContext context, String productName, List<ModifierGroup> groups) {
  return showDialog<ItemChoice>(
    context: context,
    builder: (_) => _ItemChoicesDialog(productName: productName, groups: groups),
  );
}

class _ItemChoicesDialog extends StatefulWidget {
  const _ItemChoicesDialog({required this.productName, required this.groups});
  final String productName;
  final List<ModifierGroup> groups;
  @override
  State<_ItemChoicesDialog> createState() => _ItemChoicesDialogState();
}

class _ItemChoicesDialogState extends State<_ItemChoicesDialog> {
  final Map<String, Set<String>> _chosen = {};
  final _note = TextEditingController();
  int _course = 1;
  bool _held = false;

  @override
  void dispose() {
    _note.dispose();
    super.dispose();
  }

  Set<String> _of(ModifierGroup g) => _chosen.putIfAbsent(g.uid, () => <String>{});

  void _toggle(ModifierGroup g, ModifierOption o) {
    setState(() {
      final set = _of(g);
      if (set.contains(o.uid)) {
        set.remove(o.uid);
      } else if (g.single) {
        set
          ..clear()
          ..add(o.uid);
      } else if (g.maxSelect == null || set.length < g.maxSelect!) {
        set.add(o.uid);
      }
    });
  }

  /// The first group whose minimum is not met yet, for the button hint.
  ModifierGroup? get _missing =>
      widget.groups.where((g) => _of(g).length < g.minSelect).firstOrNull;

  @override
  Widget build(BuildContext context) {
    final missing = _missing;
    return AlertDialog(
      shape: RoundedRectangleBorder(borderRadius: AppRadii.brLg),
      title: Text(widget.productName),
      content: SizedBox(
        width: 480,
        child: SingleChildScrollView(
          child: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              for (final g in widget.groups) ...[
                Row(
                  children: [
                    Text(g.name, style: const TextStyle(fontWeight: FontWeight.w700)),
                    const SizedBox(width: 8),
                    Text(g.rule, style: const TextStyle(color: AppColors.ink2, fontSize: 12)),
                  ],
                ),
                const SizedBox(height: 6),
                Wrap(
                  spacing: 6,
                  runSpacing: 6,
                  children: [
                    for (final o in g.options)
                      FilterChip(
                        label: Text(o.priced ? '${o.label} (+)' : o.label),
                        selected: _of(g).contains(o.uid),
                        onSelected: (_) => _toggle(g, o),
                      ),
                  ],
                ),
                const SizedBox(height: 14),
              ],
              const Text('Course', style: TextStyle(fontWeight: FontWeight.w700)),
              const SizedBox(height: 6),
              SegmentedButton<int>(
                segments: const [
                  ButtonSegment(value: 1, label: Text('1 · Starters/Drinks')),
                  ButtonSegment(value: 2, label: Text('2 · Mains')),
                  ButtonSegment(value: 3, label: Text('3 · Dessert')),
                ],
                selected: {_course},
                onSelectionChanged: (s) => setState(() => _course = s.first),
              ),
              SwitchListTile(
                contentPadding: EdgeInsets.zero,
                title: const Text('Hold — send when I fire it'),
                value: _held,
                onChanged: (v) => setState(() => _held = v),
              ),
              TextField(
                controller: _note,
                maxLength: 200,
                decoration: const InputDecoration(labelText: 'Note for the kitchen (optional)'),
              ),
            ],
          ),
        ),
      ),
      actions: [
        TextButton(
            onPressed: () => Navigator.pop(context),
            child: const Text('Cancel')),
        OrbixButton(
          label: missing == null ? 'Add' : 'Choose ${missing.name}',
          onPressed: missing != null
              ? null
              : () {
                  final uids = <String>[
                    for (final g in widget.groups)
                      for (final o in g.options)
                        if (_of(g).contains(o.uid)) o.uid,
                  ];
                  final note = _note.text.trim();
                  Navigator.pop(context, (
                    optionUids: uids,
                    course: _course,
                    held: _held,
                    note: note.isEmpty ? null : note,
                  ));
                },
        ),
      ],
    );
  }
}

/// Which held course to fire. Returns null when backed out.
Future<int?> askFireCourse(BuildContext context, List<int> courses) {
  return showDialog<int>(
    context: context,
    builder: (_) => AlertDialog(
      shape: RoundedRectangleBorder(borderRadius: AppRadii.brLg),
      title: const Text('Fire which course?'),
      content: Wrap(
        spacing: 8,
        children: [
          for (final c in courses)
            OrbixButton(
                label: 'Course $c', onPressed: () => Navigator.pop(context, c)),
        ],
      ),
      actions: [
        TextButton(
            onPressed: () => Navigator.pop(context),
            child: const Text('Cancel')),
      ],
    ),
  );
}

// ---------------------------------------------------------------- split / merge

/// Chooses what moves to a new check: line uid → quantity. Returns null when
/// backed out or nothing was chosen.
Future<Map<String, double>?> askSplit(BuildContext context, RestaurantOrder order) {
  return showDialog<Map<String, double>>(
    context: context,
    builder: (_) => _SplitDialog(order: order),
  );
}

class _SplitDialog extends StatefulWidget {
  const _SplitDialog({required this.order});
  final RestaurantOrder order;
  @override
  State<_SplitDialog> createState() => _SplitDialogState();
}

class _SplitDialogState extends State<_SplitDialog> {
  final Map<String, double> _move = {};

  @override
  Widget build(BuildContext context) {
    // Add-ons move with their item, so only items are offered.
    final lines = widget.order.activeLines.where((l) => !l.isAddOn).toList();
    final chosen = _move.values.fold<double>(0, (s, q) => s + q);
    return AlertDialog(
      shape: RoundedRectangleBorder(borderRadius: AppRadii.brLg),
      title: const Text('Split the bill — move items to a new check'),
      content: SizedBox(
        width: 460,
        child: ListView(
          shrinkWrap: true,
          children: [
            for (final l in lines)
              Padding(
                padding: const EdgeInsets.symmetric(vertical: 4),
                child: Row(
                  children: [
                    Expanded(
                      child: Text(
                          '${l.productName}${l.note != null ? ' (${l.note})' : ''}',
                          maxLines: 2,
                          overflow: TextOverflow.ellipsis),
                    ),
                    IconButton(
                        onPressed: (_move[l.uid] ?? 0) > 0
                            ? () => setState(() {
                                  final q = (_move[l.uid] ?? 0) - 1;
                                  if (q <= 0) {
                                    _move.remove(l.uid);
                                  } else {
                                    _move[l.uid] = q;
                                  }
                                })
                            : null,
                        icon: const Icon(Icons.remove_circle_outline)),
                    SizedBox(
                      width: 70,
                      child: Text(
                          '${formatAmount(_move[l.uid] ?? 0, decimals: 0)} / '
                          '${formatAmount(l.quantity, decimals: 0)}',
                          textAlign: TextAlign.center),
                    ),
                    IconButton(
                        onPressed: (_move[l.uid] ?? 0) + 1 <= l.quantity
                            ? () => setState(() => _move[l.uid] = (_move[l.uid] ?? 0) + 1)
                            : null,
                        icon: const Icon(Icons.add_circle_outline)),
                  ],
                ),
              ),
          ],
        ),
      ),
      actions: [
        TextButton(
            onPressed: () => Navigator.pop(context),
            child: const Text('Cancel')),
        OrbixButton(
            label: 'Move ${formatAmount(chosen, decimals: 0)} to a new check',
            onPressed: chosen > 0 ? () => Navigator.pop(context, Map.of(_move)) : null),
      ],
    );
  }
}

/// Picks another live order to merge into this one.
Future<RestaurantOrder?> pickOrderToMerge(
    BuildContext context, List<RestaurantOrder> others) {
  return showDialog<RestaurantOrder>(
    context: context,
    builder: (_) => AlertDialog(
      shape: RoundedRectangleBorder(borderRadius: AppRadii.brLg),
      title: const Text('Merge another order into this one'),
      content: SizedBox(
        width: 420,
        child: others.isEmpty
            ? const Text('There are no other open orders in this branch.')
            : ListView(
                shrinkWrap: true,
                children: [
                  for (final o in others)
                    ListTile(
                      leading: const Icon(Icons.call_merge),
                      title: Text('${o.where} · ${o.orderNumber}'),
                      subtitle: Text('${o.activeLines.length} items · ${o.openedByName}'),
                      onTap: () => Navigator.pop(context, o),
                    ),
                ],
              ),
      ),
      actions: [
        TextButton(
            onPressed: () => Navigator.pop(context),
            child: const Text('Cancel')),
      ],
    ),
  );
}

// ---------------------------------------------------------------- move table

/// Picks a free (or any other) table to move an order to.
Future<RestaurantTable?> pickTable(BuildContext context,
    List<RestaurantTable> tables, {String? exceptUid}) {
  final choices = tables.where((t) => t.active && t.uid != exceptUid).toList();
  return showDialog<RestaurantTable>(
    context: context,
    builder: (_) => AlertDialog(
      shape: RoundedRectangleBorder(borderRadius: AppRadii.brLg),
      title: const Text('Move to table'),
      content: SizedBox(
        width: 420,
        child: Wrap(
          spacing: 8,
          runSpacing: 8,
          children: [
            for (final t in choices)
              SizedBox(
                width: 92,
                height: 56,
                child: OutlinedButton(
                  onPressed: () => Navigator.pop(context, t),
                  child: Text(t.name + (t.state == TableState.free ? '' : ' •')),
                ),
              ),
          ],
        ),
      ),
      actions: [
        TextButton(
            onPressed: () => Navigator.pop(context),
            child: const Text('Cancel')),
      ],
    ),
  );
}

// ---------------------------------------------------------------- station printers

/// Where each kitchen/bar station prints FROM THIS TILL: its network address
/// (set in the ERP), or a printer installed on this PC (for a USB printer).
Future<void> showStationPrinters(BuildContext context, List<PrepStation> stations) {
  return showDialog<void>(
    context: context,
    builder: (_) => _StationPrintersDialog(stations: stations),
  );
}

class _StationPrintersDialog extends StatefulWidget {
  const _StationPrintersDialog({required this.stations});
  final List<PrepStation> stations;
  @override
  State<_StationPrintersDialog> createState() => _StationPrintersDialogState();
}

class _StationPrintersDialogState extends State<_StationPrintersDialog> {
  AppConfig? _cfg;
  final List<String> _local = const ReceiptPrinter().listPrinters();
  String? _testing;

  @override
  void initState() {
    super.initState();
    AppConfig.load().then((c) {
      if (mounted) setState(() => _cfg = c);
    });
  }

  Future<void> _set(String stationUid, String? printer) async {
    final cfg = _cfg;
    if (cfg == null) return;
    setState(() {
      if (printer == null) {
        cfg.stationPrinters.remove(stationUid);
      } else {
        cfg.stationPrinters[stationUid] = printer;
      }
    });
    await cfg.save();
  }

  Future<void> _test(PrepStation s) async {
    setState(() => _testing = s.uid);
    final err = await const RestaurantPrinting().testStation(s);
    if (!mounted) return;
    setState(() => _testing = null);
    showToast(context, err ?? 'Test sent to ${s.name}.', ok: err == null);
  }

  @override
  Widget build(BuildContext context) {
    final cfg = _cfg;
    return AlertDialog(
      shape: RoundedRectangleBorder(borderRadius: AppRadii.brLg),
      title: const Text('Kitchen & bar printers'),
      content: SizedBox(
        width: 560,
        child: cfg == null
            ? const SizedBox(
                height: 80, child: Center(child: CircularProgressIndicator()))
            : widget.stations.isEmpty
                ? const Text(
                    'No stations are set up for this branch. A manager adds them in the ERP '
                    '(Point of Sale → Restaurant Setup).')
                : Column(
                    mainAxisSize: MainAxisSize.min,
                    children: [
                      for (final s in widget.stations.where((s) => s.active))
                        Padding(
                          padding: const EdgeInsets.symmetric(vertical: 6),
                          child: Row(
                            children: [
                              SizedBox(
                                width: 120,
                                child: Text(s.name,
                                    style: const TextStyle(fontWeight: FontWeight.w700)),
                              ),
                              Expanded(
                                child: DropdownButtonFormField<String?>(
                                  initialValue: cfg.stationPrinters[s.uid],
                                  isExpanded: true,
                                  decoration: const InputDecoration(isDense: true),
                                  items: [
                                    DropdownMenuItem<String?>(
                                      value: null,
                                      child: Text(s.address.isEmpty
                                          ? 'Network — no address set'
                                          : 'Network — ${s.address}'),
                                    ),
                                    for (final p in {..._local, ?cfg.stationPrinters[s.uid]})
                                      DropdownMenuItem<String?>(
                                          value: p, child: Text('This PC — $p')),
                                  ],
                                  onChanged: (v) => _set(s.uid, v),
                                ),
                              ),
                              const SizedBox(width: 8),
                              OrbixButton(
                                label: 'Test',
                                kind: BtnKind.ghost,
                                busy: _testing == s.uid,
                                onPressed: _testing == null ? () => _test(s) : null,
                              ),
                            ],
                          ),
                        ),
                    ],
                  ),
      ),
      actions: [
        OrbixButton(label: 'Done', onPressed: () => Navigator.pop(context)),
      ],
    );
  }
}
