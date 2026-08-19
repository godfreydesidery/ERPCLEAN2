import 'package:flutter/material.dart';

import '../app/theme.dart';
import '../data/mock.dart';
import '../widgets/common.dart';
import '../widgets/kit.dart';

/// Register a supplier. Mockup: validates and confirms, posts nothing.
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
  bool _vatRegistered = true;

  static const _termOptions = <String>[
    'Cash on delivery',
    '7 days',
    '14 days',
    '30 days',
    '60 days',
  ];

  @override
  void initState() {
    super.initState();
    _name.addListener(() => setState(() {}));
    _phone.addListener(() => setState(() {}));
  }

  @override
  void dispose() {
    for (final c in [_name, _phone, _email, _tin, _address]) {
      c.dispose();
    }
    super.dispose();
  }

  bool get _ready =>
      _name.text.trim().isNotEmpty && _phone.text.trim().isNotEmpty;

  Future<void> _submit() async {
    await showDoneSheet(
      context,
      title: 'Supplier created',
      detail: '${_name.text.trim()}\n${_phone.text.trim()}',
    );
    if (mounted) Navigator.of(context).pop();
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: HqColors.bg,
      appBar: AppBar(title: const Text('New supplier', style: HqText.title)),
      body: ListView(
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
            required: true,
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
            items: _termOptions,
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
            subtitle: Text('They can issue a VAT invoice', style: HqText.tiny),
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
          const SizedBox(height: 24),
          const SectionLabel(text: 'ALREADY REGISTERED'),
          const SizedBox(height: 10),
          HqCard(
            padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 6),
            child: Column(
              children: [
                for (var i = 0; i < kSuppliers.length; i++) ...[
                  if (i > 0) const Divider(height: 1),
                  Padding(
                    padding: const EdgeInsets.symmetric(vertical: 11),
                    child: Row(
                      children: [
                        Container(
                          width: 34,
                          height: 34,
                          decoration: BoxDecoration(
                            color: HqColors.brandSoft,
                            borderRadius: BorderRadius.circular(9),
                          ),
                          alignment: Alignment.center,
                          child: Text(
                            kSuppliers[i].name.substring(0, 1),
                            style: const TextStyle(
                              fontSize: 14,
                              fontWeight: FontWeight.w700,
                              color: HqColors.brand,
                            ),
                          ),
                        ),
                        const SizedBox(width: 12),
                        Expanded(
                          child: Column(
                            crossAxisAlignment: CrossAxisAlignment.start,
                            children: [
                              Text(
                                kSuppliers[i].name,
                                maxLines: 1,
                                overflow: TextOverflow.ellipsis,
                                style: const TextStyle(
                                  fontSize: 14,
                                  fontWeight: FontWeight.w600,
                                  color: HqColors.ink,
                                ),
                              ),
                              Text(
                                '${kSuppliers[i].phone} · '
                                '${kSuppliers[i].location}',
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
                ],
              ],
            ),
          ),
          const SizedBox(height: 26),
          FilledButton(
            onPressed: _ready ? _submit : null,
            child: const Text('Create supplier'),
          ),
          const SizedBox(height: 10),
          Center(
            child: Text(
              'Demo build — nothing is saved to the server.',
              style: HqText.tiny,
            ),
          ),
        ],
      ),
    );
  }
}
