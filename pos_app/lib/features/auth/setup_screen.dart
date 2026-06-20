import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../app/theme.dart';
import '../../core/config/app_config.dart';
import '../../state/providers.dart';
import '../../widgets/ui.dart';

/// Setup / diagnostics: configure the ERP host and test reachability before
/// go-live (integrator persona). Persists the host and rebuilds the API client.
Future<void> showSetupDialog(BuildContext context, WidgetRef ref) {
  return showDialog(
    context: context,
    builder: (_) => const _SetupDialog(),
  );
}

class _SetupDialog extends ConsumerStatefulWidget {
  const _SetupDialog();
  @override
  ConsumerState<_SetupDialog> createState() => _SetupDialogState();
}

class _SetupDialogState extends ConsumerState<_SetupDialog> {
  late final TextEditingController _host;
  String? _status;
  bool _ok = false;
  bool _testing = false;

  @override
  void initState() {
    super.initState();
    _host = TextEditingController(text: ref.read(baseHostProvider));
  }

  @override
  void dispose() {
    _host.dispose();
    super.dispose();
  }

  String _trim(String h) => h.trim().endsWith('/')
      ? h.trim().substring(0, h.trim().length - 1)
      : h.trim();

  Future<void> _test() async {
    setState(() {
      _testing = true;
      _status = null;
    });
    final base = '${_trim(_host.text)}/api/v1';
    try {
      final dio = Dio(BaseOptions(
          connectTimeout: const Duration(seconds: 8),
          receiveTimeout: const Duration(seconds: 8)));
      final res = await dio.get('$base/health');
      final up = (res.data is Map) &&
          ((res.data['data']?['status'] ?? res.data['status']) == 'UP');
      setState(() {
        _ok = up;
        _status = up ? 'Reachable — ERP is UP.' : 'Reached host, status unclear.';
      });
    } catch (_) {
      setState(() {
        _ok = false;
        _status = 'Could not reach the ERP at this host.';
      });
    } finally {
      setState(() => _testing = false);
    }
  }

  Future<void> _save() async {
    final host = _trim(_host.text);
    ref.read(baseHostProvider.notifier).set(host);
    await AppConfig(baseHost: host).save();
    if (mounted) Navigator.of(context).pop();
  }

  @override
  Widget build(BuildContext context) {
    return Dialog(
      shape: RoundedRectangleBorder(borderRadius: AppRadii.brLg),
      child: ConstrainedBox(
        constraints: const BoxConstraints(maxWidth: 460),
        child: Padding(
          padding: const EdgeInsets.all(22),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Row(children: const [
                Icon(Icons.dns_outlined, color: AppColors.brand),
                SizedBox(width: 10),
                Text('Setup & diagnostics',
                    style:
                        TextStyle(fontSize: 18, fontWeight: FontWeight.w700)),
              ]),
              const SizedBox(height: 4),
              const Text('Point the till at your ERP server.',
                  style: TextStyle(color: AppColors.ink2, fontSize: 13)),
              const SizedBox(height: 18),
              OrbixField(
                label: 'ERP host',
                controller: _host,
                hint: 'http://localhost:8081',
                prefixIcon: Icons.link,
              ),
              const SizedBox(height: 6),
              const Text('The /api/v1 path is added automatically.',
                  style: TextStyle(color: AppColors.ink3, fontSize: 11)),
              if (_status != null) ...[
                const SizedBox(height: 14),
                Container(
                  width: double.infinity,
                  padding:
                      const EdgeInsets.symmetric(horizontal: 14, vertical: 11),
                  decoration: BoxDecoration(
                    color: _ok ? AppColors.okSoft : AppColors.dangerSoft,
                    borderRadius: AppRadii.brSm,
                    border: Border.all(
                        color: _ok
                            ? const Color(0xFFBBF7D0)
                            : const Color(0xFFFECACA)),
                  ),
                  child: Text(_status!,
                      style: TextStyle(
                          color: _ok ? AppColors.payD : AppColors.danger,
                          fontWeight: FontWeight.w600,
                          fontSize: 13.5)),
                ),
              ],
              const SizedBox(height: 20),
              Row(
                children: [
                  OrbixButton(
                      label: 'Test connection',
                      icon: Icons.wifi_tethering,
                      kind: BtnKind.ghost,
                      busy: _testing,
                      onPressed: _test),
                  const Spacer(),
                  OrbixButton(
                      label: 'Cancel',
                      kind: BtnKind.ghost,
                      onPressed: () => Navigator.of(context).pop()),
                  const SizedBox(width: 10),
                  OrbixButton(label: 'Save', onPressed: _save),
                ],
              ),
            ],
          ),
        ),
      ),
    );
  }
}
