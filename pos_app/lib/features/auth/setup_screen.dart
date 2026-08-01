import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../app/theme.dart';
import '../../core/api/erp_tls.dart';
import '../../core/config/app_config.dart';
import '../../services/receipt_printer.dart';
import '../receipt/receipt_text.dart';
import '../../state/providers.dart';
import '../../widgets/ui.dart';

/// Setup / diagnostics: configure the ERP host and the receipt printer, and test
/// both before go-live (integrator persona). Persists everything to AppConfig.
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

  // Receipt-printer settings.
  List<String> _printers = const [];
  String? _printerName;
  int _width = AppConfig.defaultWidthCols;
  String _mode = AppConfig.defaultPrintMode;
  bool _kick = false;
  bool _printing = false;

  @override
  void initState() {
    super.initState();
    _host = TextEditingController(text: ref.read(baseHostProvider));
    _printers = const ReceiptPrinter().listPrinters();
    _loadConfig();
  }

  Future<void> _loadConfig() async {
    final cfg = await AppConfig.load();
    if (!mounted) return;
    setState(() {
      _printerName = cfg.receiptPrinterName;
      _width = cfg.receiptWidthCols;
      _mode = cfg.printMode;
      _kick = cfg.kickDrawer;
    });
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
      applyErpTls(dio);
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

  Future<void> _testPrint() async {
    final printer = _printerName;
    if (printer == null || printer.isEmpty) {
      showToast(context, 'Choose a printer first.');
      return;
    }
    setState(() => _printing = true);
    try {
      final text = sampleReceiptText(width: _width);
      final bytes = _mode == 'plain'
          ? encodePlainText(text)
          : encodeEscPos(text, kickDrawer: _kick);
      await const ReceiptPrinter().printRaw(printer, bytes);
      if (mounted) showToast(context, 'Test sent to $printer.', ok: true);
    } on ReceiptPrinterException catch (e) {
      if (mounted) showToast(context, e.message);
    } catch (_) {
      if (mounted) showToast(context, 'Could not print the test receipt.');
    } finally {
      if (mounted) setState(() => _printing = false);
    }
  }

  Future<void> _save() async {
    final host = _trim(_host.text);
    ref.read(baseHostProvider.notifier).set(host);
    await AppConfig(
      baseHost: host,
      receiptPrinterName: _printerName,
      receiptWidthCols: _width,
      printMode: _mode,
      kickDrawer: _kick,
    ).save();
    if (mounted) Navigator.of(context).pop();
  }

  @override
  Widget build(BuildContext context) {
    return Dialog(
      shape: RoundedRectangleBorder(borderRadius: AppRadii.brLg),
      child: ConstrainedBox(
        constraints: const BoxConstraints(maxWidth: 460, maxHeight: 660),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Padding(
              padding: const EdgeInsets.fromLTRB(22, 22, 22, 0),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Row(children: const [
                    Icon(Icons.dns_outlined, color: AppColors.brand),
                    SizedBox(width: 10),
                    Text('Setup & diagnostics',
                        style: TextStyle(
                            fontSize: 18, fontWeight: FontWeight.w700)),
                  ]),
                  const SizedBox(height: 4),
                  const Text('Point the till at your ERP server and receipt printer.',
                      style: TextStyle(color: AppColors.ink2, fontSize: 13)),
                ],
              ),
            ),
            Flexible(
              child: SingleChildScrollView(
                padding: const EdgeInsets.fromLTRB(22, 18, 22, 4),
                child: Column(
                  mainAxisSize: MainAxisSize.min,
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
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
                        padding: const EdgeInsets.symmetric(
                            horizontal: 14, vertical: 11),
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
                    const SizedBox(height: 10),
                    OrbixButton(
                        label: 'Test connection',
                        icon: Icons.wifi_tethering,
                        kind: BtnKind.ghost,
                        busy: _testing,
                        onPressed: _test),
                    const SizedBox(height: 22),
                    const Divider(height: 1),
                    const SizedBox(height: 16),
                    const SectionLabel('Receipt printer'),
                    _labeled(
                      'Printer',
                      _dropdown<String?>(
                        value: _printerName,
                        items: _printerItems(),
                        onChanged: (v) => setState(() => _printerName = v),
                      ),
                    ),
                    if (_printers.isEmpty)
                      const Padding(
                        padding: EdgeInsets.only(top: 6),
                        child: Text(
                            'No printers detected (Windows desktop only).',
                            style:
                                TextStyle(color: AppColors.ink3, fontSize: 11)),
                      ),
                    const SizedBox(height: 14),
                    _labeled(
                      'Paper width',
                      _dropdown<int>(
                        value: _width,
                        items: const [
                          DropdownMenuItem(
                              value: kCols58mm, child: Text('58 mm · 32 cols')),
                          DropdownMenuItem(
                              value: kCols80mm, child: Text('80 mm · 48 cols')),
                        ],
                        onChanged: (v) =>
                            setState(() => _width = v ?? AppConfig.defaultWidthCols),
                      ),
                    ),
                    const SizedBox(height: 14),
                    _labeled(
                      'Print mode',
                      _dropdown<String>(
                        value: _mode,
                        items: const [
                          DropdownMenuItem(
                              value: 'escpos',
                              child: Text('Thermal (ESC/POS + cut)')),
                          DropdownMenuItem(
                              value: 'plain', child: Text('Plain text')),
                        ],
                        onChanged: (v) => setState(
                            () => _mode = v ?? AppConfig.defaultPrintMode),
                      ),
                    ),
                    const SizedBox(height: 6),
                    InkWell(
                      borderRadius: AppRadii.brSm,
                      onTap: () => setState(() => _kick = !_kick),
                      child: Padding(
                        padding: const EdgeInsets.symmetric(vertical: 4),
                        child: Row(
                          children: [
                            Checkbox(
                              value: _kick,
                              onChanged: (v) =>
                                  setState(() => _kick = v ?? false),
                            ),
                            const Expanded(
                              child: Text('Open cash drawer after printing',
                                  style: TextStyle(fontSize: 13.5)),
                            ),
                          ],
                        ),
                      ),
                    ),
                    const SizedBox(height: 8),
                    OrbixButton(
                        label: 'Test print',
                        icon: Icons.print_outlined,
                        kind: BtnKind.ghost,
                        busy: _printing,
                        onPressed: _testPrint),
                  ],
                ),
              ),
            ),
            const Divider(height: 1),
            Padding(
              padding: const EdgeInsets.all(16),
              child: Row(
                children: [
                  const Spacer(),
                  OrbixButton(
                      label: 'Cancel',
                      kind: BtnKind.ghost,
                      onPressed: () => Navigator.of(context).pop()),
                  const SizedBox(width: 10),
                  OrbixButton(label: 'Save', onPressed: _save),
                ],
              ),
            ),
          ],
        ),
      ),
    );
  }

  List<DropdownMenuItem<String?>> _printerItems() {
    final names = <String>{..._printers};
    if (_printerName != null && _printerName!.isNotEmpty) {
      names.add(_printerName!);
    }
    return [
      const DropdownMenuItem<String?>(value: null, child: Text('— none —')),
      ...names.map((n) => DropdownMenuItem<String?>(
            value: n,
            child: Text(n, overflow: TextOverflow.ellipsis),
          )),
    ];
  }

  Widget _labeled(String label, Widget child) => Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        mainAxisSize: MainAxisSize.min,
        children: [
          Padding(
            padding: const EdgeInsets.only(bottom: 6),
            child: Text(label,
                style: const TextStyle(
                    fontSize: 12.5,
                    fontWeight: FontWeight.w600,
                    color: AppColors.ink2)),
          ),
          child,
        ],
      );

  Widget _dropdown<T>({
    required T value,
    required List<DropdownMenuItem<T>> items,
    required ValueChanged<T?> onChanged,
  }) =>
      Container(
        width: double.infinity,
        padding: const EdgeInsets.symmetric(horizontal: 12),
        decoration: BoxDecoration(
          color: AppColors.panel,
          borderRadius: AppRadii.brSm,
          border: Border.all(color: AppColors.line2),
        ),
        child: DropdownButtonHideUnderline(
          child: DropdownButton<T>(
            value: value,
            isExpanded: true,
            items: items,
            onChanged: onChanged,
          ),
        ),
      );
}
