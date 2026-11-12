import 'dart:async';

import 'package:flutter/material.dart';

import '../app/app_scope.dart';
import '../app/theme.dart';
import '../core/api/api_exception.dart';
import '../services/catalog_service.dart';
import '../widgets/kit.dart';

/// Pickers for the master data a product points at: its supplier and its
/// department. Shared by New item and Edit item.

/// Choose a supplier, searchable. Null when dismissed.
Future<SupplierItem?> pickSupplier(BuildContext context) {
  return showModalBottomSheet<SupplierItem>(
    context: context,
    isScrollControlled: true,
    backgroundColor: Colors.transparent,
    builder: (_) => const _SupplierSheet(),
  );
}

class _SupplierSheet extends StatefulWidget {
  const _SupplierSheet();

  @override
  State<_SupplierSheet> createState() => _SupplierSheetState();
}

/// Searches on the SERVER as the owner types. Filtering a preloaded first
/// page would hide every supplier past it.
class _SupplierSheetState extends State<_SupplierSheet> {
  List<SupplierItem>? _all;
  String? _error;
  String _query = '';
  Timer? _debounce;
  int _seq = 0;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addPostFrameCallback((_) => _load(''));
  }

  @override
  void dispose() {
    _debounce?.cancel();
    super.dispose();
  }

  void _onQuery(String v) {
    setState(() => _query = v);
    _debounce?.cancel();
    _debounce =
        Timer(const Duration(milliseconds: 300), () => _load(v.trim()));
  }

  Future<void> _load(String q) async {
    final seq = ++_seq;
    try {
      final list = await AppScope.of(context).catalog.suppliers(search: q);
      // A slower, older search must not overwrite a newer one.
      if (mounted && seq == _seq) {
        setState(() {
          _all = list;
          _error = null;
        });
      }
    } on ApiException catch (e) {
      if (mounted && seq == _seq) setState(() => _error = e.message);
    } catch (_) {
      if (mounted && seq == _seq) {
        setState(() => _error = 'Suppliers could not be loaded.');
      }
    }
  }

  @override
  Widget build(BuildContext context) {
    final shown = _all ?? const <SupplierItem>[];

    return _Sheet(
      title: 'Choose a supplier',
      child: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          HqSearchField(
            hint: 'Search suppliers',
            onChanged: _onQuery,
          ),
          const SizedBox(height: 8),
          if (_error != null)
            Padding(
              padding: const EdgeInsets.symmetric(vertical: 24),
              child: Text(_error!, style: HqText.body),
            )
          else if (_all == null)
            const Padding(
              padding: EdgeInsets.symmetric(vertical: 24),
              child: LinearProgressIndicator(minHeight: 2),
            )
          else if (shown.isEmpty)
            Padding(
              padding: const EdgeInsets.symmetric(vertical: 24),
              child: Text(
                _query.trim().isEmpty
                    ? 'No suppliers yet. Create one from Operations first.'
                    : 'No supplier matches "${_query.trim()}".',
                style: HqText.body,
              ),
            )
          else
            Flexible(
              child: ListView.separated(
                shrinkWrap: true,
                itemCount: shown.length,
                separatorBuilder: (_, _) => const Divider(height: 1),
                itemBuilder: (context, i) => ListTile(
                  contentPadding: EdgeInsets.zero,
                  title: Text(
                    shown[i].name,
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                    style: const TextStyle(
                      fontSize: 15,
                      fontWeight: FontWeight.w600,
                      color: HqColors.ink,
                    ),
                  ),
                  subtitle: shown[i].phone == null
                      ? null
                      : Text(shown[i].phone!, style: HqText.tiny),
                  onTap: () => Navigator.of(context).pop(shown[i]),
                ),
              ),
            ),
        ],
      ),
    );
  }
}

/// Choose a department: one already in use, or a new one typed in. Returns
/// the name; '' means "no department"; null means dismissed.
Future<String?> pickDepartment(BuildContext context, {String? current}) {
  return showModalBottomSheet<String>(
    context: context,
    isScrollControlled: true,
    backgroundColor: Colors.transparent,
    builder: (_) => _DepartmentSheet(current: current),
  );
}

class _DepartmentSheet extends StatefulWidget {
  const _DepartmentSheet({this.current});

  final String? current;

  @override
  State<_DepartmentSheet> createState() => _DepartmentSheetState();
}

class _DepartmentSheetState extends State<_DepartmentSheet> {
  final _typed = TextEditingController();
  List<String>? _all;

  @override
  void initState() {
    super.initState();
    _typed.addListener(() => setState(() {}));
    WidgetsBinding.instance.addPostFrameCallback((_) => _load());
  }

  @override
  void dispose() {
    _typed.dispose();
    super.dispose();
  }

  Future<void> _load() async {
    try {
      final list = await AppScope.of(context).catalog.categories();
      if (mounted) setState(() => _all = list);
    } catch (_) {
      // The list is only a convenience: typing a department still works.
      if (mounted) setState(() => _all = const []);
    }
  }

  @override
  Widget build(BuildContext context) {
    final q = _typed.text.trim();
    final lower = q.toLowerCase();
    final all = _all ?? const <String>[];
    final shown =
        all.where((d) => lower.isEmpty || d.toLowerCase().contains(lower));
    final exact = all.any((d) => d.toLowerCase() == lower);

    return _Sheet(
      title: 'Department',
      child: Column(
        mainAxisSize: MainAxisSize.min,
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          HqField(
            label: 'Find or type a new department',
            controller: _typed,
            hint: 'e.g. Phones, Accessories',
          ),
          const SizedBox(height: 8),
          if (q.isNotEmpty && !exact)
            ListTile(
              contentPadding: EdgeInsets.zero,
              leading: const Icon(Icons.add_rounded, color: HqColors.brand),
              title: Text(
                'Use new department "$q"',
                style: const TextStyle(
                  fontWeight: FontWeight.w600,
                  color: HqColors.brand,
                ),
              ),
              onTap: () => Navigator.of(context).pop(q),
            ),
          if (_all == null)
            const Padding(
              padding: EdgeInsets.symmetric(vertical: 16),
              child: LinearProgressIndicator(minHeight: 2),
            )
          else
            Flexible(
              child: ListView(
                shrinkWrap: true,
                children: [
                  for (final d in shown)
                    ListTile(
                      contentPadding: EdgeInsets.zero,
                      title: Text(d),
                      trailing: d == widget.current
                          ? const Icon(Icons.check_rounded,
                              color: HqColors.brand)
                          : null,
                      onTap: () => Navigator.of(context).pop(d),
                    ),
                  if (widget.current != null && widget.current!.isNotEmpty)
                    ListTile(
                      contentPadding: EdgeInsets.zero,
                      title: Text('No department', style: HqText.body),
                      onTap: () => Navigator.of(context).pop(''),
                    ),
                ],
              ),
            ),
        ],
      ),
    );
  }
}

/// A bottom sheet that grows with its content up to most of the screen and
/// stays above the keyboard.
class _Sheet extends StatelessWidget {
  const _Sheet({required this.title, required this.child});

  final String title;
  final Widget child;

  @override
  Widget build(BuildContext context) {
    final media = MediaQuery.of(context);
    return Padding(
      padding: EdgeInsets.only(bottom: media.viewInsets.bottom),
      child: Container(
        constraints: BoxConstraints(maxHeight: media.size.height * 0.8),
        decoration: const BoxDecoration(
          color: HqColors.panel,
          borderRadius: BorderRadius.vertical(top: Radius.circular(22)),
        ),
        padding: const EdgeInsets.fromLTRB(20, 18, 20, 8),
        child: SafeArea(
          top: false,
          child: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              Text(title, style: HqText.title),
              const SizedBox(height: 10),
              Flexible(child: child),
            ],
          ),
        ),
      ),
    );
  }
}

/// A tappable field showing a picked value, in the form's field style.
class PickTile extends StatelessWidget {
  const PickTile({
    super.key,
    required this.label,
    required this.icon,
    required this.value,
    required this.placeholder,
    required this.onTap,
    this.onClear,
  });

  final String label;
  final IconData icon;
  final String? value;
  final String placeholder;
  final VoidCallback onTap;
  final VoidCallback? onClear;

  @override
  Widget build(BuildContext context) {
    final empty = value == null || value!.isEmpty;
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Text(
          label,
          style: const TextStyle(
            fontSize: 12.5,
            fontWeight: FontWeight.w700,
            color: HqColors.ink2,
          ),
        ),
        const SizedBox(height: 7),
        Material(
          color: HqColors.panel,
          borderRadius: BorderRadius.circular(HqRadii.sm),
          child: InkWell(
            onTap: onTap,
            borderRadius: BorderRadius.circular(HqRadii.sm),
            child: Container(
              padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 14),
              decoration: BoxDecoration(
                borderRadius: BorderRadius.circular(HqRadii.sm),
                border: Border.all(color: HqColors.line2),
              ),
              child: Row(
                children: [
                  Icon(icon,
                      size: 20, color: empty ? HqColors.ink3 : HqColors.brand),
                  const SizedBox(width: 12),
                  Expanded(
                    child: Text(
                      empty ? placeholder : value!,
                      maxLines: 1,
                      overflow: TextOverflow.ellipsis,
                      style: TextStyle(
                        fontSize: 15,
                        fontWeight:
                            empty ? FontWeight.w400 : FontWeight.w600,
                        color: empty ? HqColors.ink3 : HqColors.ink,
                      ),
                    ),
                  ),
                  if (!empty && onClear != null)
                    IconButton(
                      tooltip: 'Clear',
                      visualDensity: VisualDensity.compact,
                      icon: const Icon(Icons.close_rounded,
                          size: 18, color: HqColors.ink3),
                      onPressed: onClear,
                    )
                  else
                    const Icon(Icons.chevron_right,
                        size: 20, color: HqColors.ink3),
                ],
              ),
            ),
          ),
        ),
      ],
    );
  }
}
