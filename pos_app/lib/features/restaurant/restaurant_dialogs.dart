import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../app/theme.dart';
import '../../core/api/api_exception.dart';
import '../../core/config/app_config.dart';
import '../../core/money.dart';
import '../../models/auth.dart';
import '../../models/restaurant.dart';
import '../../services/receipt_printer.dart';
import '../../state/providers.dart';
import '../../widgets/on_screen_keyboard.dart';
import '../../widgets/ui.dart';
import 'restaurant_printing.dart';
import 'waiter_tiles.dart';

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

// ---------------------------------------------------------------- waiter sign-in (ADR-0066 D-8)

/// A waiter signing in on a shared till: their own username and password,
/// checked by the ERP for RESTAURANT.ORDER.MANAGE. The till stays signed in as
/// the cashier — nothing is issued; the waiter is only named on what they open
/// and send, and the server re-verifies that on every request.
/// Returns (uid, name), or null when backed out.
Future<({String uid, String name})?> askWaiterSignIn(BuildContext context, WidgetRef ref) {
  return showDialog<({String uid, String name})>(
    context: context,
    builder: (_) => const _WaiterSignInDialog(),
  );
}

class _WaiterSignInDialog extends ConsumerStatefulWidget {
  const _WaiterSignInDialog();
  @override
  ConsumerState<_WaiterSignInDialog> createState() => _WaiterSignInDialogState();
}

class _WaiterSignInDialogState extends ConsumerState<_WaiterSignInDialog> {
  final _user = TextEditingController();
  final _pass = TextEditingController();
  final _userFocus = FocusNode();
  final _passFocus = FocusNode();
  bool _busy = false;
  String? _error;

  /// The branch's waiters as tiles (J COB 2026-10-30), from this till's list.
  List<RestaurantWaiter> _waiters = const [];
  bool _typingPassword = false;

  @override
  void initState() {
    super.initState();
    _userFocus.addListener(() {
      if (_userFocus.hasFocus) setState(() => _typingPassword = false);
    });
    _passFocus.addListener(() {
      if (_passFocus.hasFocus) setState(() => _typingPassword = true);
    });
    AppConfig.load().then((cfg) {
      if (mounted) setState(() => _waiters = cfg.waiters);
    });
  }

  @override
  void dispose() {
    _user.dispose();
    _pass.dispose();
    _userFocus.dispose();
    _passFocus.dispose();
    super.dispose();
  }

  void _pick(RestaurantWaiter w) {
    setState(() {
      _user.text = w.username;
      _pass.clear();
      _typingPassword = true;
      _error = null;
    });
    _passFocus.requestFocus();
  }

  Future<void> _go() async {
    if (_user.text.trim().isEmpty || _pass.text.isEmpty || _busy) return;
    setState(() {
      _busy = true;
      _error = null;
    });
    try {
      final v = await ref.read(stepUpServiceProvider).verify(
          username: _user.text.trim(),
          password: _pass.text,
          permissionCode: Perms.restaurantOrderManage);
      if (!mounted) return;
      final uid = v.authoriserUid?.trim() ?? '';
      if (!v.authorised || uid.isEmpty) {
        setState(() {
          _busy = false;
          _error = v.message.isEmpty ? 'That sign-in was not accepted.' : v.message;
        });
        return;
      }
      Navigator.pop(context, (uid: uid, name: v.approverLabel));
    } on ApiException catch (e) {
      if (mounted) {
        setState(() {
          _busy = false;
          _error = e.message;
        });
      }
    }
  }

  @override
  Widget build(BuildContext context) {
    return AlertDialog(
      shape: RoundedRectangleBorder(borderRadius: AppRadii.brLg),
      title: const Text('Who is serving?'),
      content: SizedBox(
        width: _waiters.isEmpty ? 360 : 640,
        child: SingleChildScrollView(
          child: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              Text(
                  _waiters.isEmpty
                      ? 'Waiter, sign in with your own username and password.'
                      : 'Waiter, tap your name and type your password.',
                  style: const TextStyle(color: AppColors.ink2)),
              const SizedBox(height: 10),
              if (_waiters.isNotEmpty) ...[
                ConstrainedBox(
                  constraints: const BoxConstraints(maxHeight: 210),
                  child: SingleChildScrollView(
                    child: WaiterTiles(
                        waiters: _waiters, selected: _user.text.trim(), onPick: _pick),
                  ),
                ),
                const SizedBox(height: 8),
              ],
              TextField(
                controller: _user,
                focusNode: _userFocus,
                autofocus: _waiters.isEmpty,
                decoration: const InputDecoration(labelText: 'Username'),
                onSubmitted: (_) => _passFocus.requestFocus(),
              ),
              TextField(
                controller: _pass,
                focusNode: _passFocus,
                obscureText: true,
                decoration: const InputDecoration(labelText: 'Password'),
                onSubmitted: (_) => _go(),
              ),
              if (_error != null) ...[
                const SizedBox(height: 8),
                Text(_error!, style: const TextStyle(color: AppColors.danger)),
              ],
              if (_waiters.isNotEmpty) ...[
                const SizedBox(height: 10),
                OnScreenKeyboard(
                  controller: _typingPassword ? _pass : _user,
                  keyHeight: 40,
                  enterLabel: _typingPassword ? 'Sign in' : 'Next',
                  onEnter: _typingPassword ? _go : () => _passFocus.requestFocus(),
                ),
              ],
            ],
          ),
        ),
      ),
      actions: [
        TextButton(onPressed: () => Navigator.pop(context), child: const Text('Cancel')),
        OrbixButton(label: 'Sign in', busy: _busy, onPressed: _busy ? null : _go),
      ],
    );
  }
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

// ---------------------------------------------------------------- restaurant settings (this device)

/// This device's restaurant settings (ADR-0065 D-4, ADR-0066 D-6/D-8): for each
/// kitchen/bar station, where its slips print FROM THIS DEVICE and on what paper
/// width, and whether this device prints that station's slips for every till (a
/// kitchen PC with a USB printer). Plus: ask which waiter is serving on a shared
/// till. Device-specific, so stored here, not on the server.
///
/// [allowWaiterSetting] is false where waiter sign-in makes no sense (a kitchen
/// printer login).
Future<void> showStationPrinters(BuildContext context, List<PrepStation> stations,
    {bool allowWaiterSetting = true, bool allowRestaurantOnly = false}) {
  return showDialog<void>(
    context: context,
    builder: (_) => _StationPrintersDialog(
        stations: stations,
        allowWaiterSetting: allowWaiterSetting,
        allowRestaurantOnly: allowRestaurantOnly),
  );
}

class _StationPrintersDialog extends StatefulWidget {
  const _StationPrintersDialog(
      {required this.stations, required this.allowWaiterSetting, this.allowRestaurantOnly = false});
  final List<PrepStation> stations;
  final bool allowWaiterSetting;

  /// A manager (RESTAURANT.SETUP.MANAGE) may make this a restaurant-only till.
  final bool allowRestaurantOnly;
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

  Future<void> _change(void Function(AppConfig cfg) edit) async {
    final cfg = _cfg;
    if (cfg == null) return;
    setState(() => edit(cfg));
    await cfg.save();
  }

  void _setPrinter(String stationUid, String? printer) => _change((cfg) {
        if (printer == null) {
          cfg.stationPrinters.remove(stationUid);
          // Printing for every till needs a printer on this device.
          cfg.agentStations.remove(stationUid);
        } else {
          cfg.stationPrinters[stationUid] = printer;
        }
      });

  Future<void> _test(PrepStation s) async {
    setState(() => _testing = s.uid);
    final err = await const RestaurantPrinting().testStation(s);
    if (!mounted) return;
    setState(() => _testing = null);
    showToast(context, err ?? 'Test sent to ${s.name}.', ok: err == null);
  }

  /// How this station prints, in one sentence, for the person setting it up.
  String _summary(AppConfig cfg, PrepStation s) {
    final local = cfg.stationPrinters[s.uid];
    if (local != null && cfg.agentStations.contains(s.uid)) {
      return 'This device prints every ${s.name} slip, from all tills, on "$local".';
    }
    if (local != null) return 'Slips sent from this device print on "$local".';
    if (s.address.isNotEmpty) return 'Prints on the network printer ${s.address}.';
    return 'No printer from here: slips go to a device that prints ${s.name} '
        '(OrbixPOS on the kitchen PC) and to the Kitchen Display.';
  }

  @override
  Widget build(BuildContext context) {
    final cfg = _cfg;
    final stations = widget.stations.where((s) => s.active).toList();
    return AlertDialog(
      shape: RoundedRectangleBorder(borderRadius: AppRadii.brLg),
      title: const Text('Restaurant settings — this device'),
      content: SizedBox(
        width: 620,
        child: cfg == null
            ? const SizedBox(height: 80, child: Center(child: CircularProgressIndicator()))
            : SingleChildScrollView(
                child: Column(
                  mainAxisSize: MainAxisSize.min,
                  crossAxisAlignment: CrossAxisAlignment.stretch,
                  children: [
                    if (widget.allowWaiterSetting) ...[
                      SwitchListTile(
                        contentPadding: EdgeInsets.zero,
                        title: const Text('Shared till: ask who is serving'),
                        subtitle: const Text(
                            'A waiter signs in before opening an order, and is named on the '
                            'order, the kitchen slips and the bill.'),
                        value: cfg.askWaiter,
                        onChanged: (v) => _change((c) => c.askWaiter = v),
                      ),
                      const Divider(),
                    ],
                    if (widget.allowRestaurantOnly) ...[
                      SwitchListTile(
                        contentPadding: EdgeInsets.zero,
                        title: const Text('Restaurant only — this till'),
                        subtitle: const Text(
                            'This till shows only the Restaurant register: Supermarket and '
                            'Pharmacy are hidden for everyone who signs in here.'),
                        value: cfg.restaurantOnly,
                        onChanged: (v) => _change((c) => c.restaurantOnly = v),
                      ),
                      const Divider(),
                    ],
                    const SectionLabel('Kitchen & bar printers'),
                    const SizedBox(height: 6),
                    if (stations.isEmpty)
                      const Text(
                          'No stations are set up for this branch. A manager adds them in the ERP '
                          '(Point of Sale → Restaurant Setup).')
                    else
                      for (final s in stations) _stationCard(cfg, s),
                  ],
                ),
              ),
      ),
      actions: [
        OrbixButton(label: 'Done', onPressed: () => Navigator.pop(context)),
      ],
    );
  }

  Widget _stationCard(AppConfig cfg, PrepStation s) {
    final local = cfg.stationPrinters[s.uid];
    return Container(
      margin: const EdgeInsets.only(bottom: 10),
      padding: const EdgeInsets.all(12),
      decoration: BoxDecoration(
          borderRadius: AppRadii.brSm, border: Border.all(color: AppColors.line2)),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          Row(
            children: [
              Expanded(
                child: Text(s.name, style: const TextStyle(fontWeight: FontWeight.w700)),
              ),
              OrbixButton(
                label: 'Test',
                kind: BtnKind.ghost,
                busy: _testing == s.uid,
                onPressed: _testing == null ? () => _test(s) : null,
              ),
            ],
          ),
          const SizedBox(height: 8),
          Row(
            children: [
              Expanded(
                flex: 3,
                child: DropdownButtonFormField<String?>(
                  initialValue: local,
                  isExpanded: true,
                  decoration: const InputDecoration(isDense: true, labelText: 'Printer'),
                  items: [
                    DropdownMenuItem<String?>(
                      value: null,
                      child: Text(s.address.isEmpty
                          ? 'None on this device'
                          : 'Network — ${s.address}'),
                    ),
                    for (final p in {..._local, ?local})
                      DropdownMenuItem<String?>(value: p, child: Text('This device — $p')),
                  ],
                  onChanged: (v) => _setPrinter(s.uid, v),
                ),
              ),
              const SizedBox(width: 10),
              Expanded(
                flex: 2,
                child: DropdownButtonFormField<int>(
                  initialValue: cfg.widthFor(s.uid),
                  decoration: const InputDecoration(isDense: true, labelText: 'Paper'),
                  items: const [
                    DropdownMenuItem(value: 32, child: Text('58 mm')),
                    DropdownMenuItem(value: 48, child: Text('80 mm')),
                  ],
                  onChanged: (v) => _change((c) {
                    if (v != null) c.stationWidths[s.uid] = v;
                  }),
                ),
              ),
            ],
          ),
          CheckboxListTile(
            contentPadding: EdgeInsets.zero,
            controlAffinity: ListTileControlAffinity.leading,
            title: Text('Print ALL ${s.name} slips here, from every till'),
            subtitle: const Text(
                'For the PC the station printer is plugged into. Keep OrbixPOS open on it.'),
            value: cfg.agentStations.contains(s.uid),
            onChanged: local == null
                ? null
                : (v) => _change((c) {
                      if (v == true) {
                        c.agentStations.add(s.uid);
                      } else {
                        c.agentStations.remove(s.uid);
                      }
                    }),
          ),
          Text(_summary(cfg, s), style: const TextStyle(fontSize: 12, color: AppColors.ink2)),
        ],
      ),
    );
  }
}
