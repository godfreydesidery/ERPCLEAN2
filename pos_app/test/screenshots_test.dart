// Palette preview generator.
//
// Renders the OrbixPOS Login and Supermarket-register screens with the CURRENT
// theme (see lib/app/theme.dart) straight to PNG files under pos_app/screenshots/
// — no backend, no login. Re-run after any palette tweak to see the result:
//
//   flutter test test/screenshots_test.dart
//
// These are faithful mock-ups built from the real design tokens and shared
// widgets (OrbixButton / PayButton / NumText), so the COLOURS are exactly what
// the app renders. Layout is representative, not pixel-identical to the live app.

import 'dart:io';
import 'dart:typed_data';
import 'dart:ui' as ui;

import 'package:flutter/material.dart';
import 'package:flutter/rendering.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';

import 'package:pos_app/app/theme.dart';
import 'package:pos_app/widgets/ui.dart';

Future<void> _loadFont(String family, String path) async {
  final file = File(path);
  if (!file.existsSync()) return;
  final bytes = file.readAsBytesSync();
  final loader = FontLoader(family)
    ..addFont(Future.value(ByteData.view(Uint8List.fromList(bytes).buffer)));
  await loader.load();
}

Future<void> _capture(
  WidgetTester tester, {
  required Size size,
  required Widget child,
  required String name,
}) async {
  final key = GlobalKey();
  tester.view.physicalSize = size;
  tester.view.devicePixelRatio = 1.0;
  addTearDown(tester.view.reset);

  await tester.pumpWidget(MaterialApp(
    debugShowCheckedModeBanner: false,
    theme: buildAppTheme(),
    home: Scaffold(
      backgroundColor: AppColors.bg,
      body: RepaintBoundary(
        key: key,
        child: SizedBox(width: size.width, height: size.height, child: child),
      ),
    ),
  ));
  await tester.pumpAndSettle();

  await tester.runAsync(() async {
    final boundary =
        key.currentContext!.findRenderObject() as RenderRepaintBoundary;
    final image = await boundary.toImage(pixelRatio: 2.0);
    final data = await image.toByteData(format: ui.ImageByteFormat.png);
    final out = File('screenshots/$name.png')..createSync(recursive: true);
    out.writeAsBytesSync(data!.buffer.asUint8List());
  });
}

void main() {
  setUpAll(() async {
    await _loadFont('Segoe UI', r'C:\Windows\Fonts\segoeui.ttf');
    await _loadFont('MaterialIcons',
        r'C:\flutter\bin\cache\artifacts\material_fonts\materialicons-regular.otf');
  });

  testWidgets('register palette', (tester) async {
    await _capture(tester,
        size: const Size(1280, 760), name: 'register', child: const _Register());
  });

  testWidgets('login palette', (tester) async {
    await _capture(tester,
        size: const Size(1120, 680), name: 'login', child: const _Login());
  });
}

// ============================================================ REGISTER mock ===

class _Register extends StatelessWidget {
  const _Register();

  @override
  Widget build(BuildContext context) {
    return Row(
      children: [
        Expanded(child: _left()),
        const SizedBox(width: 360, child: _Keypad()),
      ],
    );
  }

  Widget _left() {
    return Padding(
      padding: const EdgeInsets.all(16),
      child: Column(
        children: [
          _searchBar(),
          const SizedBox(height: 12),
          const Expanded(child: _Grid()),
        ],
      ),
    );
  }

  Widget _searchBar() {
    return Container(
      decoration: BoxDecoration(
        color: AppColors.panel,
        borderRadius: AppRadii.brLg,
        border: Border.all(color: AppColors.line2),
      ),
      padding: const EdgeInsets.only(left: 14, right: 4),
      child: Row(
        children: [
          const Icon(Icons.qr_code_scanner, color: AppColors.ink3, size: 20),
          const SizedBox(width: 10),
          const Expanded(
            child: Padding(
              padding: EdgeInsets.symmetric(vertical: 15),
              child: Text('Scan a barcode or search by code / name…',
                  style: TextStyle(color: AppColors.ink3, fontSize: 15)),
            ),
          ),
          Padding(
            padding: const EdgeInsets.all(4),
            child: Material(
              color: AppColors.brand,
              borderRadius: AppRadii.brSm,
              child: const SizedBox(
                width: 46,
                height: 42,
                child: Icon(Icons.add, color: Colors.white),
              ),
            ),
          ),
        ],
      ),
    );
  }
}

class _Grid extends StatelessWidget {
  const _Grid();

  static const _rows = [
    ['1', '1001', 'Cooking Oil 1L', 'PCS', '2', '6,500.00', '·', '13,000.00', false],
    ['2', '2003', 'Bar Soap 800g', 'PCS', '5', '2,200.00', '500.00', '10,500.00', true],
    ['3', '4110', 'Maize Flour 10kg', 'BAG', '1', '28,000.00', '·', '28,000.00', false],
    ['4', '7001', 'Drinking Water 500ml', 'PCS', '12', '700.00', '·', '8,400.00', false],
  ];

  static const _wIdx = 36.0,
      _wCode = 116.0,
      _wUnit = 54.0,
      _wQty = 88.0,
      _wPrice = 128.0,
      _wDisc = 90.0,
      _wTotal = 136.0;

  @override
  Widget build(BuildContext context) {
    return Container(
      decoration: BoxDecoration(
        color: AppColors.panel,
        border: Border.all(color: AppColors.xlLineHard),
      ),
      child: Column(
        children: [
          _header(),
          for (final r in _rows) _row(r),
        ],
      ),
    );
  }

  Widget _header() {
    const h = TextStyle(
        fontSize: 11,
        fontWeight: FontWeight.w700,
        color: Color(0xFF374151),
        letterSpacing: .4);
    Widget cell(String t, double w, {bool right = false, bool center = false}) =>
        SizedBox(
          width: w,
          child: Container(
            padding: const EdgeInsets.symmetric(horizontal: 9, vertical: 7),
            alignment: right
                ? Alignment.centerRight
                : (center ? Alignment.center : Alignment.centerLeft),
            child: Text(t.toUpperCase(), style: h),
          ),
        );
    return Container(
      decoration: const BoxDecoration(
        color: AppColors.xlHead,
        border: Border(bottom: BorderSide(color: AppColors.xlLineHard)),
      ),
      child: Row(children: [
        cell('#', _wIdx, center: true),
        cell('Code', _wCode),
        const Expanded(
            child: Padding(
                padding: EdgeInsets.symmetric(horizontal: 9, vertical: 7),
                child: Text('ITEM', style: h))),
        cell('Unit', _wUnit, center: true),
        cell('Qty', _wQty, right: true),
        cell('Price', _wPrice, right: true),
        cell('Disc', _wDisc, right: true),
        cell('Total', _wTotal, right: true),
      ]),
    );
  }

  Widget _row(List<Object> r) {
    final selected = r[8] as bool;
    final hasDisc = (r[6] as String) != '·';
    Widget cell(Widget child, double w,
            {Alignment align = Alignment.centerLeft, Color? bg}) =>
        SizedBox(
          width: w,
          child: Container(
            height: 32,
            alignment: align,
            padding: const EdgeInsets.symmetric(horizontal: 9),
            decoration: BoxDecoration(
              color: bg,
              border: const Border(
                  right: BorderSide(color: AppColors.xlLine),
                  bottom: BorderSide(color: AppColors.xlLine)),
            ),
            child: child,
          ),
        );
    return Container(
      color: selected ? AppColors.xlSel : AppColors.panel,
      child: Row(children: [
        cell(Text(r[0] as String,
                style: const TextStyle(fontSize: 12, color: Color(0xFF9AA0A6))),
            _wIdx,
            align: Alignment.center, bg: AppColors.xlHead),
        cell(
            Text(r[1] as String,
                style: const TextStyle(fontSize: 12, color: Color(0xFF5F6368))),
            _wCode),
        Expanded(
          child: Container(
            height: 32,
            alignment: Alignment.centerLeft,
            padding: const EdgeInsets.symmetric(horizontal: 9),
            decoration: const BoxDecoration(
              border: Border(
                  right: BorderSide(color: AppColors.xlLine),
                  bottom: BorderSide(color: AppColors.xlLine)),
            ),
            child: Text(r[2] as String,
                style: const TextStyle(
                    fontSize: 13.5,
                    fontWeight: FontWeight.w600,
                    color: AppColors.xlInk)),
          ),
        ),
        cell(
            Text(r[3] as String,
                style: const TextStyle(fontSize: 12, color: Color(0xFF5F6368))),
            _wUnit,
            align: Alignment.center),
        cell(NumText(r[4] as String, style: numStyle(size: 13.5)), _wQty,
            align: Alignment.centerRight),
        cell(
            NumText(r[5] as String,
                style: numStyle(
                    size: 13.5,
                    weight: FontWeight.w400,
                    color: const Color(0xFF5F6368))),
            _wPrice,
            align: Alignment.centerRight),
        cell(
            NumText(r[6] as String,
                style: numStyle(
                    size: 13.5,
                    color: hasDisc ? AppColors.brand : AppColors.ink3)),
            _wDisc,
            align: Alignment.centerRight),
        cell(
            NumText(r[7] as String,
                style: numStyle(size: 13.5, weight: FontWeight.w700)),
            _wTotal,
            align: Alignment.centerRight),
      ]),
    );
  }
}

class _Keypad extends StatelessWidget {
  const _Keypad();

  @override
  Widget build(BuildContext context) {
    return Container(
      decoration: const BoxDecoration(
        color: AppColors.panel,
        border: Border(left: BorderSide(color: AppColors.line)),
      ),
      padding: const EdgeInsets.all(14),
      child: Column(
        children: [
          _totalCard(),
          const SizedBox(height: 11),
          _customerChip(),
          const SizedBox(height: 11),
          _toggle(),
          const SizedBox(height: 8),
          _display(),
          const SizedBox(height: 8),
          Expanded(child: _keys()),
          const SizedBox(height: 10),
          SizedBox(
            width: double.infinity,
            child: PayButton(
                label: 'PAY', amount: '59,900.00', onPressed: () {}),
          ),
        ],
      ),
    );
  }

  Widget _totalCard() {
    return Container(
      width: double.infinity,
      padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 14),
      decoration:
          BoxDecoration(color: AppColors.ink, borderRadius: AppRadii.brLg),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.end,
        children: const [
          Text('TOTAL (TZS)',
              style: TextStyle(
                  color: Color(0xFFCBD5E1),
                  fontSize: 11,
                  fontWeight: FontWeight.w700,
                  letterSpacing: .6)),
          SizedBox(height: 2),
          NumText('59,900.00',
              style: TextStyle(
                  color: Colors.white,
                  fontSize: 30,
                  fontWeight: FontWeight.w800,
                  height: 1.1,
                  fontFeatures: kTabular)),
          SizedBox(height: 6),
          Text('4 lines · 20 items',
              style: TextStyle(
                  color: Color(0xFFCBD5E1),
                  fontSize: 12,
                  fontFeatures: kTabular)),
        ],
      ),
    );
  }

  Widget _customerChip() {
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 8),
      decoration: BoxDecoration(
        color: AppColors.panel2,
        borderRadius: AppRadii.brSm,
        border: Border.all(color: AppColors.line),
      ),
      child: Row(children: [
        Container(
          width: 30,
          height: 30,
          alignment: Alignment.center,
          decoration: BoxDecoration(
              color: AppColors.brandSoft, borderRadius: AppRadii.brSm),
          child: const Icon(Icons.person_outline,
              size: 16, color: AppColors.brand),
        ),
        const SizedBox(width: 10),
        const Expanded(
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text('Customer',
                  style: TextStyle(fontSize: 11, color: AppColors.ink3)),
              Text('Walk-in',
                  style:
                      TextStyle(fontSize: 14, fontWeight: FontWeight.w600)),
            ],
          ),
        ),
        const Icon(Icons.chevron_right, color: AppColors.ink3),
      ]),
    );
  }

  Widget _toggle() {
    Widget seg(String label, bool active) => Expanded(
          child: Container(
            alignment: Alignment.center,
            padding: const EdgeInsets.symmetric(vertical: 9),
            decoration: BoxDecoration(
              color: active ? AppColors.panel : Colors.transparent,
              borderRadius: AppRadii.brSm,
              boxShadow: active ? AppShadows.card : null,
            ),
            child: Text(label,
                style: TextStyle(
                    fontWeight: FontWeight.w700,
                    color: active ? AppColors.brand : AppColors.ink2)),
          ),
        );
    return Container(
      padding: const EdgeInsets.all(3),
      decoration: BoxDecoration(
        color: AppColors.panel2,
        borderRadius: AppRadii.brSm,
        border: Border.all(color: AppColors.line),
      ),
      child: Row(children: [seg('× Qty', true), seg('− Disc', false)]),
    );
  }

  Widget _display() {
    return Container(
      width: double.infinity,
      padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 11),
      decoration: BoxDecoration(
        color: AppColors.panel2,
        borderRadius: AppRadii.brSm,
        border: Border.all(color: AppColors.line2),
      ),
      child: Text('0',
          textAlign: TextAlign.right,
          style: numStyle(size: 26, weight: FontWeight.w800)),
    );
  }

  Widget _keys() {
    const keys = ['7', '8', '9', '4', '5', '6', '1', '2', '3', '.', '0', '<'];
    return Column(
      children: [
        Expanded(
          child: GridView.count(
            crossAxisCount: 3,
            mainAxisSpacing: 8,
            crossAxisSpacing: 8,
            childAspectRatio: 1.6,
            physics: const NeverScrollableScrollPhysics(),
            children: keys.map(_key).toList(),
          ),
        ),
        const SizedBox(height: 8),
        Row(children: [
          const Expanded(
              child: OrbixButton(
                  label: 'Clear', kind: BtnKind.clear, onPressed: _noop)),
          const SizedBox(width: 8),
          const Expanded(
              child: OrbixButton(label: 'Set qty', onPressed: _noop)),
        ]),
      ],
    );
  }

  Widget _key(String k) {
    return Material(
      color: AppColors.key,
      borderRadius: AppRadii.brSm,
      child: Container(
        alignment: Alignment.center,
        decoration: BoxDecoration(
          borderRadius: AppRadii.brSm,
          border: Border.all(color: AppColors.keyLine),
        ),
        child: k == '<'
            ? const Icon(Icons.backspace_outlined, size: 18)
            : Text(k,
                style:
                    const TextStyle(fontSize: 22, fontWeight: FontWeight.w700)),
      ),
    );
  }
}

// =============================================================== LOGIN mock ===

class _Login extends StatelessWidget {
  const _Login();

  static const _ticks = [
    'Sells fast at the counter — scan and go',
    'Reliable sales that ride out brief network blips',
    'Built-in X-read, close, and Z-read reconcile',
  ];

  @override
  Widget build(BuildContext context) {
    return Row(
      children: [
        Expanded(child: _brandPanel()),
        SizedBox(width: 440, child: _signIn()),
      ],
    );
  }

  Widget _brandPanel() {
    return Container(
      decoration: const BoxDecoration(
        gradient: LinearGradient(
          begin: Alignment.topLeft,
          end: Alignment.bottomRight,
          colors: [Color(0xFF1B6FD1), Color(0xFF14508F), Color(0xFF00296B)],
          stops: [0, .6, 1],
        ),
      ),
      child: Padding(
        padding: const EdgeInsets.all(56),
        child: Column(
          mainAxisAlignment: MainAxisAlignment.center,
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            const Brand(large: true, onDark: true),
            const SizedBox(height: 30),
            const Text('A fast, dependable till for every counter.',
                style: TextStyle(
                    color: Colors.white,
                    fontSize: 30,
                    height: 1.2,
                    fontWeight: FontWeight.w700)),
            const SizedBox(height: 26),
            ..._ticks.map((t) => Padding(
                  padding: const EdgeInsets.only(bottom: 14),
                  child: Row(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Container(
                        width: 21,
                        height: 21,
                        alignment: Alignment.center,
                        decoration: BoxDecoration(
                            color: Colors.white.withValues(alpha: .18),
                            shape: BoxShape.circle),
                        child: const Icon(Icons.check,
                            size: 12, color: Colors.white),
                      ),
                      const SizedBox(width: 9),
                      Expanded(
                          child: Text(t,
                              style: const TextStyle(
                                  color: Color(0xFFD6E6FA), fontSize: 15.5))),
                    ],
                  ),
                )),
          ],
        ),
      ),
    );
  }

  Widget _signIn() {
    return Container(
      color: AppColors.panel,
      padding: const EdgeInsets.symmetric(horizontal: 44),
      child: Column(
        mainAxisAlignment: MainAxisAlignment.center,
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          const Text('Sign in',
              style: TextStyle(fontSize: 26, fontWeight: FontWeight.w800)),
          const SizedBox(height: 4),
          const Text('Enter your cashier credentials to open the till.',
              style: TextStyle(color: AppColors.ink2, fontSize: 14)),
          const SizedBox(height: 24),
          _field('Username', 'cashier01', Icons.person_outline),
          const SizedBox(height: 14),
          _field('Password', '••••••••', Icons.lock_outline),
          const SizedBox(height: 22),
          const OrbixButton(
              label: 'Sign in',
              block: true,
              large: true,
              icon: Icons.login,
              onPressed: _noop),
        ],
      ),
    );
  }

  Widget _field(String label, String value, IconData icon) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Padding(
          padding: const EdgeInsets.only(bottom: 6),
          child: Text(label,
              style: const TextStyle(
                  fontSize: 12.5,
                  fontWeight: FontWeight.w600,
                  color: AppColors.ink2)),
        ),
        Container(
          padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 13),
          decoration: BoxDecoration(
            color: AppColors.panel,
            borderRadius: AppRadii.brSm,
            border: Border.all(color: AppColors.line2),
          ),
          child: Row(children: [
            Icon(icon, color: AppColors.ink3, size: 20),
            const SizedBox(width: 10),
            Text(value,
                style: const TextStyle(fontSize: 15, color: AppColors.ink)),
          ]),
        ),
      ],
    );
  }
}

void _noop() {}
