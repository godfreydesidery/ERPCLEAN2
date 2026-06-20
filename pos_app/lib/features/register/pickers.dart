import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../app/theme.dart';
import '../../core/api/api_exception.dart';
import '../../models/parties.dart';
import '../../state/app_controller.dart';
import '../../state/cart_controller.dart';
import '../../state/providers.dart';
import '../../widgets/ui.dart';

/// Customer picker (FR-CUST-1/2): walk-in default + search registered customers.
/// Sets the basket customer.
Future<void> showCustomerPicker(BuildContext context, WidgetRef ref) {
  return showDialog(
    context: context,
    builder: (_) => const _CustomerPicker(),
  );
}

class _CustomerPicker extends ConsumerStatefulWidget {
  const _CustomerPicker();
  @override
  ConsumerState<_CustomerPicker> createState() => _CustomerPickerState();
}

class _CustomerPickerState extends ConsumerState<_CustomerPicker> {
  final _q = TextEditingController();
  List<Customer> _results = const [];
  bool _busy = false;

  @override
  void initState() {
    super.initState();
    _search('');
  }

  @override
  void dispose() {
    _q.dispose();
    super.dispose();
  }

  Future<void> _search(String q) async {
    final companyId = ref.read(appControllerProvider).context?.companyId;
    if (companyId == null) return;
    setState(() => _busy = true);
    try {
      final list = await ref
          .read(partyServiceProvider)
          .searchCustomers(companyId, q: q.isEmpty ? null : q, size: 50);
      if (mounted) setState(() => _results = list);
    } on ApiException catch (e) {
      if (mounted) showToast(context, e.message);
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  void _pick(Customer c) {
    ref.read(cartProvider.notifier).setCustomer(c);
    Navigator.pop(context);
  }

  @override
  Widget build(BuildContext context) {
    final current = ref.watch(cartProvider).customer;
    return Dialog(
      shape: RoundedRectangleBorder(borderRadius: AppRadii.brLg),
      child: ConstrainedBox(
        constraints: const BoxConstraints(maxWidth: 480, maxHeight: 600),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            Padding(
              padding: const EdgeInsets.fromLTRB(20, 18, 20, 8),
              child: Row(
                children: [
                  const Text('Customer',
                      style:
                          TextStyle(fontSize: 18, fontWeight: FontWeight.w700)),
                  const Spacer(),
                  IconButton(
                      onPressed: () => Navigator.pop(context),
                      icon: const Icon(Icons.close)),
                ],
              ),
            ),
            Padding(
              padding: const EdgeInsets.symmetric(horizontal: 20),
              child: OrbixField(
                controller: _q,
                hint: 'Search name / code / phone…',
                prefixIcon: Icons.search,
                autofocus: true,
                onChanged: _search,
              ),
            ),
            const SizedBox(height: 8),
            if (_busy) const LinearProgressIndicator(minHeight: 2),
            Flexible(
              child: ListView.separated(
                shrinkWrap: true,
                itemCount: _results.length,
                separatorBuilder: (_, _) => const Divider(height: 1),
                itemBuilder: (context, i) {
                  final c = _results[i];
                  final selected = current?.id == c.id;
                  return ListTile(
                    leading: Icon(
                        c.isWalkIn ? Icons.directions_walk : Icons.person_outline,
                        color: AppColors.brand),
                    title: Text(c.displayName),
                    subtitle: Text(
                        '${c.code}${c.isWalkIn ? ' · Walk-in' : ''}',
                        style: const TextStyle(fontSize: 12)),
                    trailing: selected
                        ? const Icon(Icons.check_circle, color: AppColors.pay)
                        : null,
                    onTap: () => _pick(c),
                  );
                },
              ),
            ),
            const SizedBox(height: 8),
          ],
        ),
      ),
    );
  }
}
