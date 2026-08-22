import 'package:flutter/material.dart';

import '../app/app_scope.dart';
import '../app/theme.dart';
import '../core/api/api_exception.dart';
import '../widgets/async_view.dart';
import '../widgets/common.dart';
import '../widgets/kit.dart';

/// Register a supplier against `/suppliers`.
class CreateSupplierScreen extends StatefulWidget {
  const CreateSupplierScreen({super.key});

  @override
  State<CreateSupplierScreen> createState() => _CreateSupplierScreenState();
}

class _CreateSupplierScreenState extends State<CreateSupplierScreen> {
  final _name = TextEditingController();
  final _phone = TextEditingController();
  final _email = TextEditingController();
  final _tin = TextEditingController();
  final _address = TextEditingController();

  String? _terms;
  bool _vatRegistered = false;
  bool _busy = false;

  static const _termOptions = <String, int>{
    'Cash on delivery': 0,
    '7 days': 7,
    '14 days': 14,
    '30 days': 30,
    '60 days': 60,
  };

  @override
  void initState() {
    super.initState();
    _name.addListener(() => setState(() {}));
  }

  @override
  void dispose() {
    for (final c in [_name, _phone, _email, _tin, _address]) {
      c.dispose();
    }
    super.dispose();
  }

  bool get _ready => _name.text.trim().isNotEmpty && !_busy;

  Future<void> _submit() async {
    setState(() => _busy = true);
    try {
      final created = await AppScope.of(context).catalog.createSupplier(
            displayName: _name.text.trim(),
            phone: _phone.text.trim(),
            email: _email.text.trim(),
            tin: _tin.text.trim(),
            vatRegistered: _vatRegistered,
            physicalAddress: _address.text.trim(),
            paymentTermsDays: _terms == null ? null : _termOptions[_terms],
          );
      if (!mounted) return;
      await showDoneSheet(
        context,
        title: 'Supplier created',
        detail: created.name,
      );
      if (mounted) Navigator.of(context).pop();
    } on ApiException catch (e) {
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(
            behavior: SnackBarBehavior.floating,
            backgroundColor: HqColors.bad,
            content: Text(e.message),
          ),
        );
      }
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final session = AppScope.of(context).session;

    return Scaffold(
      backgroundColor: HqColors.bg,
      appBar: AppBar(title: const Text('New supplier', style: HqText.title)),
      body: !session.can('SUPPLIER.MANAGE')
          ? const NoPermission(code: 'SUPPLIER.MANAGE')
          : ListView(
              padding: const EdgeInsets.fromLTRB(20, 8, 20, 28),
              children: [
                const SectionLabel(text: 'WHO THEY ARE'),
                const SizedBox(height: 12),
                HqField(
                  label: 'Supplier name',
                  required: true,
                  controller: _name,
                  hint: 'e.g. Mbasha Holdings Ltd',
                ),
                const SizedBox(height: 16),
                HqField(
                  label: 'Phone',
                  controller: _phone,
                  hint: '+255 7XX XXX XXX',
                  prefix: Icons.phone_outlined,
                  keyboardType: TextInputType.phone,
                ),
                const SizedBox(height: 16),
                HqField(
                  label: 'Email',
                  controller: _email,
                  hint: 'Optional',
                  prefix: Icons.mail_outline_rounded,
                  keyboardType: TextInputType.emailAddress,
                ),
                const SizedBox(height: 16),
                HqField(
                  label: 'Address',
                  controller: _address,
                  hint: 'Town or street',
                  maxLines: 2,
                ),
                const SizedBox(height: 24),
                const SectionLabel(text: 'TRADING'),
                const SizedBox(height: 12),
                HqDropdown(
                  label: 'Payment terms',
                  items: _termOptions.keys.toList(),
                  value: _terms,
                  hint: 'When we pay them',
                  onChanged: (v) => setState(() => _terms = v),
                ),
                const SizedBox(height: 16),
                SwitchListTile.adaptive(
                  value: _vatRegistered,
                  onChanged: (v) => setState(() => _vatRegistered = v),
                  contentPadding: EdgeInsets.zero,
                  activeThumbColor: HqColors.brand,
                  title: const Text(
                    'VAT registered',
                    style: TextStyle(
                      fontSize: 15,
                      fontWeight: FontWeight.w600,
                      color: HqColors.ink,
                    ),
                  ),
                  subtitle:
                      Text('They can issue a VAT invoice', style: HqText.tiny),
                ),
                if (_vatRegistered) ...[
                  const SizedBox(height: 12),
                  HqField(
                    label: 'TIN',
                    controller: _tin,
                    hint: 'Tax identification number',
                    keyboardType: TextInputType.number,
                  ),
                ],
                const SizedBox(height: 26),
                FilledButton(
                  onPressed: _ready ? _submit : null,
                  child: _busy
                      ? const SizedBox(
                          width: 20,
                          height: 20,
                          child: CircularProgressIndicator(
                            strokeWidth: 2.2,
                            color: Colors.white,
                          ),
                        )
                      : const Text('Create supplier'),
                ),
              ],
            ),
    );
  }
}
