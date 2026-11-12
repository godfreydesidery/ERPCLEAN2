import 'package:flutter/material.dart';

import '../app/app_scope.dart';
import '../app/format.dart';
import '../app/theme.dart';
import '../core/api/api_exception.dart';
import '../services/operations_service.dart';
import '../widgets/common.dart';
import '../widgets/kit.dart';
import 'till_z.dart';

/// Close a till from the phone: the owner's end of day when the shop has
/// sent the count.
///
/// The server's close is `POST /pos/sessions/uid/{uid}/close` with the
/// counted cash; it ends the session whoever opened it. Reconcile is a second
/// step (`/reconcile`, its own permission) that posts the over/short to the
/// ledger and produces the Z read. When the user may do both, both are done
/// here in one go by default, because that is what "close the books" means to
/// the owner.
///
/// Closing ends the cashier's shift. The till cannot be asked whether it is
/// mid-sale, so the screen says plainly what happens and asks for a
/// confirmation instead of blocking.
class CloseTillScreen extends StatefulWidget {
  const CloseTillScreen({super.key, required this.session, required this.read});

  final SessionRow session;

  /// The X read the owner was looking at, for the expected cash.
  final TillRead read;

  @override
  State<CloseTillScreen> createState() => _CloseTillScreenState();
}

class _CloseTillScreenState extends State<CloseTillScreen> {
  final _counted = TextEditingController();
  final _notes = TextEditingController();
  bool _reconcile = true;
  bool _busy = false;

  /// Set once the close has gone through, so a failed reconcile is never
  /// followed by a second close of the same session.
  SessionRow? _closed;
  ZRead? _z;

  @override
  void initState() {
    super.initState();
    _counted.addListener(() => setState(() {}));
  }

  @override
  void dispose() {
    _counted.dispose();
    _notes.dispose();
    super.dispose();
  }

  double? get _countedValue =>
      double.tryParse(_counted.text.trim().replaceAll(',', ''));

  Future<bool> _confirm(String cashier, String till) async {
    final ok = await showDialog<bool>(
      context: context,
      builder: (context) => AlertDialog(
        title: Text('Close $till?'),
        content: Text(
          'This ends $cashier\'s shift. Any sale they try after this is '
          'refused and they will need to open a new session.\n\n'
          'Counted cash: ${tzs(_countedValue ?? 0)}',
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.of(context).pop(false),
            child: const Text('Cancel'),
          ),
          FilledButton(
            onPressed: () => Navigator.of(context).pop(true),
            child: const Text('Close till'),
          ),
        ],
      ),
    );
    return ok ?? false;
  }

  Future<void> _submit({required bool canReconcile}) async {
    final counted = _countedValue;
    if (counted == null || counted < 0) return;
    final ops = AppScope.of(context).operations;
    final notes = _notes.text.trim();

    if (_closed == null &&
        !await _confirm(widget.session.cashierName, widget.session.tillName)) {
      return;
    }
    if (!mounted) return;
    setState(() => _busy = true);
    try {
      _closed ??= await ops.closeSession(
        sessionUid: widget.session.uid,
        countedCash: counted,
        notes: notes,
      );
      if (canReconcile && _reconcile) {
        final z = await ops.reconcile(widget.session.uid, notes: notes);
        if (mounted) setState(() => _z = z);
      } else if (mounted) {
        await showDoneSheet(
          context,
          title: 'Till closed',
          detail: '${widget.session.tillName} · ${overShort(
            _closed!.variance ?? 0,
          )}\n'
              'It still needs reconciling. Find it under Closed tills.',
        );
        if (mounted) Navigator.of(context).pop(true);
      }
    } on ApiException catch (e) {
      if (mounted) _showError(e.message);
    } catch (_) {
      if (mounted) {
        _showError(_closed == null
            ? 'The till could not be closed.'
            : 'The till is closed, but reconciling did not go through. '
                'Try again, or reconcile it later from Closed tills.');
      }
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  void _showError(String message) {
    ScaffoldMessenger.of(context).showSnackBar(
      SnackBar(
        behavior: SnackBarBehavior.floating,
        backgroundColor: HqColors.bad,
        content: Text(message),
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    final session = AppScope.of(context).session;
    final canReconcile = session.can('POS.SESSION.RECONCILE');
    final read = widget.read;
    final counted = _countedValue;
    final z = _z;

    // Every way back reports whether the till was closed, so the till list
    // underneath reloads; and nothing leaves mid-request.
    return PopScope(
      canPop: false,
      onPopInvokedWithResult: (didPop, _) {
        if (!didPop && !_busy) Navigator.of(context).pop(_closed != null);
      },
      child: Scaffold(
        backgroundColor: HqColors.bg,
        appBar: AppBar(
          title: Text(z == null ? 'Close till' : 'Till closed',
              style: HqText.title),
          leading: BackButton(
            onPressed: _busy
                ? null
                : () => Navigator.of(context).pop(_closed != null),
          ),
        ),
        body: z != null
            ? ListView(
                padding: const EdgeInsets.fromLTRB(20, 8, 20, 28),
                children: [
                  ZReadView(z: z),
                  const SizedBox(height: 10),
                  FilledButton(
                    onPressed: () => Navigator.of(context).pop(true),
                    child: const Text('Done'),
                  ),
                ],
              )
            : ListView(
                padding: const EdgeInsets.fromLTRB(20, 8, 20, 28),
                children: [
                  _Warning(
                    text: _closed == null
                        ? 'Closing ends ${widget.session.cashierName}\'s shift '
                            'on ${widget.session.tillName}. Make sure they '
                            'have stopped selling and the till is online, so '
                            'every sale has reached the server first.'
                        : 'The till is already closed. Only the reconcile '
                            'is left to do.',
                  ),
                  const SizedBox(height: 18),
                  SectionLabel(text: widget.session.tillName.toUpperCase()),
                  const SizedBox(height: 10),
                  HqCard(
                    child: Column(
                      children: [
                        FigureRow(
                          label: 'Total takings',
                          value: tzs(read.totalSales),
                        ),
                        const Divider(height: 14),
                        FigureRow(
                          label: 'Opening float',
                          value: tzs(read.openingFloat),
                        ),
                        const Divider(height: 14),
                        FigureRow(
                          label: 'Cash sales',
                          value: tzs(read.cashTender),
                        ),
                        const Divider(height: 14),
                        FigureRow(
                          label: 'Paid out',
                          value: tzs(read.payoutsNet),
                        ),
                        const Divider(height: 16, thickness: 1.4),
                        FigureRow(
                          label: 'Cash the drawer should hold',
                          value: read.expectedKnown
                              ? tzs(read.expectedCash)
                              : 'Hidden',
                          emphasise: true,
                        ),
                      ],
                    ),
                  ),
                  if (!read.expectedKnown) ...[
                    const SizedBox(height: 8),
                    Text(
                      'You count blind: the expected figure is shown only to '
                      'users who can reconcile.',
                      style: HqText.tiny,
                    ),
                  ],
                  const SizedBox(height: 22),
                  HqField(
                    label: 'Cash counted in the drawer',
                    required: true,
                    controller: _counted,
                    readOnly: _closed != null,
                    hint: 'The figure the shop sent you',
                    keyboardType:
                        const TextInputType.numberWithOptions(decimal: true),
                    suffixText: 'TZS',
                  ),
                  if (counted != null && read.expectedKnown) ...[
                    const SizedBox(height: 12),
                    HqCard(
                      child: FigureRow(
                        label: 'Over / short',
                        value: overShort(counted - read.expectedCash),
                        emphasise: true,
                        valueColor:
                            overShortColor(counted - read.expectedCash),
                      ),
                    ),
                  ],
                  const SizedBox(height: 16),
                  HqField(
                    label: 'Notes',
                    controller: _notes,
                    hint: 'Optional, e.g. who counted',
                    maxLines: 2,
                  ),
                  if (canReconcile) ...[
                    const SizedBox(height: 12),
                    SwitchListTile.adaptive(
                      value: _reconcile,
                      onChanged: _closed != null
                          ? null
                          : (v) => setState(() => _reconcile = v),
                      contentPadding: EdgeInsets.zero,
                      activeThumbColor: HqColors.brand,
                      title: const Text(
                        'Reconcile now',
                        style: TextStyle(
                          fontSize: 15,
                          fontWeight: FontWeight.w600,
                          color: HqColors.ink,
                        ),
                      ),
                      subtitle: Text(
                        'Posts the over/short to the books and gives you '
                        'the Z read.',
                        style: HqText.tiny,
                      ),
                    ),
                  ],
                  const SizedBox(height: 22),
                  FilledButton(
                    onPressed: _busy || counted == null || counted < 0
                        ? null
                        : () => _submit(canReconcile: canReconcile),
                    child: _busy
                        ? const SizedBox(
                            width: 20,
                            height: 20,
                            child: CircularProgressIndicator(
                              strokeWidth: 2.2,
                              color: Colors.white,
                            ),
                          )
                        : Text(_closed != null
                            ? 'Reconcile'
                            : (canReconcile && _reconcile
                                ? 'Close and reconcile'
                                : 'Close till')),
                  ),
                ],
              ),
      ),
    );
  }
}

class _Warning extends StatelessWidget {
  const _Warning({required this.text});

  final String text;

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.all(13),
      decoration: BoxDecoration(
        color: HqColors.warnSoft,
        borderRadius: BorderRadius.circular(HqRadii.sm),
      ),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          const Icon(Icons.warning_amber_rounded,
              size: 19, color: HqColors.warn),
          const SizedBox(width: 10),
          Expanded(
            child: Text(
              text,
              style: const TextStyle(
                fontSize: 13,
                height: 1.35,
                color: HqColors.ink,
              ),
            ),
          ),
        ],
      ),
    );
  }
}
