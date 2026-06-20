import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../app/theme.dart';
import '../../core/api/api_exception.dart';
import '../../models/enums.dart';
import '../../models/pos.dart';
import '../../state/app_controller.dart';
import '../../state/providers.dart';
import '../../widgets/ui.dart';

class OpenShiftScreen extends ConsumerStatefulWidget {
  const OpenShiftScreen({super.key});
  @override
  ConsumerState<OpenShiftScreen> createState() => _OpenShiftScreenState();
}

class _OpenShiftScreenState extends ConsumerState<OpenShiftScreen> {
  final _float = TextEditingController(text: '0');
  String? _tillUid;
  late Future<List<PosTill>> _tills;

  @override
  void initState() {
    super.initState();
    _tills = _loadTills();
  }

  @override
  void dispose() {
    _float.dispose();
    super.dispose();
  }

  Future<List<PosTill>> _loadTills() {
    final ctx = ref.read(appControllerProvider).context!;
    return ref
        .read(tillServiceProvider)
        .listByBranch(ctx.companyId, ctx.branchId);
  }

  void _reload() => setState(() {
        _tillUid = null;
        _tills = _loadTills();
      });

  Future<void> _open() async {
    final app = ref.read(appControllerProvider);
    if (_tillUid == null) {
      showToast(context, 'Pick a till first.');
      return;
    }
    final float = double.tryParse(_float.text.trim()) ?? 0;
    await ref
        .read(appControllerProvider.notifier)
        .openShift(_tillUid!, float, app.mode);
    final err = ref.read(appControllerProvider).error;
    if (err != null && mounted) showToast(context, err);
  }

  @override
  Widget build(BuildContext context) {
    final app = ref.watch(appControllerProvider);
    return Scaffold(
      body: SafeArea(
        child: Center(
          child: SingleChildScrollView(
            padding: const EdgeInsets.symmetric(horizontal: 24, vertical: 28),
            child: ConstrainedBox(
              constraints: const BoxConstraints(maxWidth: 720),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  _head(app),
                  const SizedBox(height: 24),
                  const SectionLabel('Business mode'),
                  _modeCards(app),
                  const SizedBox(height: 12),
                  Row(
                    children: [
                      const Expanded(child: SectionLabel('Choose a till')),
                      if (app.can('POS.TILL.MANAGE'))
                        TextButton.icon(
                          onPressed: _createTill,
                          icon: const Icon(Icons.add, size: 16),
                          label: const Text('New till'),
                        ),
                    ],
                  ),
                  _tillGrid(),
                  const SizedBox(height: 24),
                  _floatRow(app),
                ],
              ),
            ),
          ),
        ),
      ),
    );
  }

  Widget _head(AppData app) {
    return Row(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            const Brand(),
            const SizedBox(height: 14),
            const Text('Open shift',
                style: TextStyle(fontSize: 24, fontWeight: FontWeight.w700)),
            const SizedBox(height: 4),
            Text(
              '${app.context?.company.name ?? ''} · ${app.context?.branch.name ?? ''}',
              style: const TextStyle(color: AppColors.ink2),
            ),
          ],
        ),
        const Spacer(),
        Row(
          children: [
            Avatar(app.me?.displayName ?? '?', size: 42),
            const SizedBox(width: 11),
            Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(app.me?.displayName ?? '',
                    style: const TextStyle(fontWeight: FontWeight.w700)),
                Text('@${app.me?.username ?? ''}',
                    style: const TextStyle(color: AppColors.ink3, fontSize: 12)),
              ],
            ),
            const SizedBox(width: 8),
            IconButton(
              tooltip: 'Sign out',
              onPressed: () =>
                  ref.read(appControllerProvider.notifier).logout(),
              icon: const Icon(Icons.logout, color: AppColors.ink2),
            ),
          ],
        ),
      ],
    );
  }

  Widget _modeCards(AppData app) {
    return Row(
      children: BusinessMode.values.map((m) {
        final active = app.mode == m;
        return Expanded(
          child: Padding(
            padding: EdgeInsets.only(right: m == BusinessMode.restaurant ? 0 : 12),
            child: InkWell(
              borderRadius: AppRadii.brLg,
              onTap: () => ref.read(appControllerProvider.notifier).setMode(m),
              child: Container(
                padding: const EdgeInsets.all(16),
                decoration: BoxDecoration(
                  color: active ? AppColors.brandSoft : AppColors.panel,
                  borderRadius: AppRadii.brLg,
                  border: Border.all(
                      color: active ? AppColors.brand : AppColors.line2,
                      width: 1.5),
                ),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(m.glyph, style: const TextStyle(fontSize: 26)),
                    const SizedBox(height: 6),
                    Text(m.label,
                        style: const TextStyle(
                            fontSize: 15, fontWeight: FontWeight.w700)),
                    const SizedBox(height: 2),
                    Text(m.blurb,
                        style: const TextStyle(
                            color: AppColors.ink2, fontSize: 12)),
                  ],
                ),
              ),
            ),
          ),
        );
      }).toList(),
    );
  }

  Widget _tillGrid() {
    return FutureBuilder<List<PosTill>>(
      future: _tills,
      builder: (context, snap) {
        if (snap.connectionState == ConnectionState.waiting) {
          return const Padding(
            padding: EdgeInsets.all(24),
            child: Center(child: CircularProgressIndicator()),
          );
        }
        if (snap.hasError) {
          final e = snap.error;
          final msg = e is ApiException ? e.message : 'Could not load tills.';
          return _emptyBox(Icons.error_outline, msg, retry: true);
        }
        final tills = (snap.data ?? []).where((t) => t.isActive).toList();
        if (tills.isEmpty) {
          return _emptyBox(Icons.point_of_sale_outlined,
              'No active tills on this branch yet.');
        }
        return GridView.builder(
          shrinkWrap: true,
          physics: const NeverScrollableScrollPhysics(),
          gridDelegate: const SliverGridDelegateWithMaxCrossAxisExtent(
            maxCrossAxisExtent: 200,
            mainAxisExtent: 78,
            crossAxisSpacing: 12,
            mainAxisSpacing: 12,
          ),
          itemCount: tills.length,
          itemBuilder: (context, i) {
            final t = tills[i];
            final active = _tillUid == t.uid;
            return InkWell(
              borderRadius: AppRadii.brSm,
              onTap: () => setState(() => _tillUid = t.uid),
              child: Container(
                padding: const EdgeInsets.all(16),
                decoration: BoxDecoration(
                  color: active ? AppColors.brandSoft : AppColors.panel,
                  borderRadius: AppRadii.brSm,
                  border: Border.all(
                      color: active ? AppColors.brand : AppColors.line2,
                      width: 1.5),
                ),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  mainAxisAlignment: MainAxisAlignment.center,
                  children: [
                    Row(
                      children: [
                        Expanded(
                          child: Text(t.name,
                              maxLines: 1,
                              overflow: TextOverflow.ellipsis,
                              style: const TextStyle(
                                  fontWeight: FontWeight.w700)),
                        ),
                        const Icon(Icons.circle, size: 9, color: AppColors.ok),
                      ],
                    ),
                    const SizedBox(height: 3),
                    Text(t.code,
                        style: const TextStyle(
                            color: AppColors.ink2, fontSize: 12)),
                  ],
                ),
              ),
            );
          },
        );
      },
    );
  }

  Widget _emptyBox(IconData icon, String msg, {bool retry = false}) {
    return Container(
      width: double.infinity,
      padding: const EdgeInsets.symmetric(vertical: 28, horizontal: 20),
      decoration: BoxDecoration(
        color: AppColors.panel,
        borderRadius: AppRadii.brSm,
        border: Border.all(color: AppColors.line),
      ),
      child: Column(
        children: [
          Icon(icon, size: 34, color: AppColors.ink3),
          const SizedBox(height: 10),
          Text(msg,
              textAlign: TextAlign.center,
              style: const TextStyle(color: AppColors.ink2)),
          if (retry) ...[
            const SizedBox(height: 12),
            OrbixButton(
                label: 'Retry', kind: BtnKind.ghost, onPressed: _reload),
          ],
        ],
      ),
    );
  }

  Widget _floatRow(AppData app) {
    return Row(
      crossAxisAlignment: CrossAxisAlignment.end,
      children: [
        Expanded(
          child: OrbixField(
            label: 'Opening float (${app.currency})',
            controller: _float,
            keyboardType: const TextInputType.numberWithOptions(decimal: true),
            inputFormatters: [
              FilteringTextInputFormatter.allow(RegExp(r'[0-9.]'))
            ],
            big: true,
            prefixIcon: Icons.savings_outlined,
          ),
        ),
        const SizedBox(width: 16),
        OrbixButton(
          label: 'Open session',
          icon: Icons.lock_open,
          large: true,
          busy: app.busy,
          onPressed: app.busy ? null : _open,
        ),
      ],
    );
  }

  Future<void> _createTill() async {
    final nameCtrl = TextEditingController();
    final ok = await showDialog<bool>(
      context: context,
      builder: (_) => AlertDialog(
        shape: RoundedRectangleBorder(borderRadius: AppRadii.brLg),
        title: const Text('New till'),
        content: OrbixField(
            label: 'Till name', controller: nameCtrl, autofocus: true),
        actions: [
          TextButton(
              onPressed: () => Navigator.pop(context, false),
              child: const Text('Cancel')),
          OrbixButton(
              label: 'Create',
              onPressed: () => Navigator.pop(context, true)),
        ],
      ),
    );
    if (ok != true || nameCtrl.text.trim().isEmpty) return;
    final ctx = ref.read(appControllerProvider).context!;
    try {
      await ref.read(tillServiceProvider).create(
          ctx.companyUid, ctx.branchId, nameCtrl.text.trim());
      if (mounted) showToast(context, 'Till created.', ok: true);
      _reload();
    } on ApiException catch (e) {
      if (mounted) showToast(context, e.message);
    }
  }
}
