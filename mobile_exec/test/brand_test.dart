// The brand compiled into the app is exactly the brand's generated profile.
//
// Run plainly, this pins the Dart defaults in lib/app/brand.dart to
// brands/orbix — the generic app is described in two places and must not
// drift. dist/build-hq.ps1 also runs it with the brand being built
// (--dart-define-from-file=brands/<id>/generated/dart_defines.json), which
// proves every value — hex colours included — survives the trip through
// fromEnvironment into the build.
import 'dart:convert';
import 'dart:io';

import 'package:flutter_test/flutter_test.dart';

import 'package:orbix_hq/app/brand.dart';

void main() {
  final file = File('brands/${Brand.id}/generated/dart_defines.json');

  test('the brand has a generated profile', () {
    expect(file.existsSync(), isTrue,
        reason: 'run: python tool/brand.py gen ${Brand.id}');
  });

  test('every compiled value equals the profile', () {
    final defines = (jsonDecode(file.readAsStringSync()) as Map)
        .cast<String, String>();
    final compiled = <String, Object>{
      'BRAND_ID': Brand.id,
      'BRAND_APP_NAME': Brand.appName,
      'BRAND_TAGLINE': Brand.tagline,
      'BRAND_MARK': Brand.mark,
      'BRAND_PRIMARY': Brand.primary,
      'BRAND_PRIMARY_DARK': Brand.primaryDark,
      'BRAND_PRIMARY_SOFT': Brand.primarySoft,
      'BRAND_HERO_MID': Brand.heroMid,
      'BRAND_HERO_END': Brand.heroEnd,
      'BRAND_GRADIENT_START': Brand.gradientStart,
      'BRAND_GRADIENT_END': Brand.gradientEnd,
      'BRAND_ON_DARK_2': Brand.onDarkSecondary,
      'BRAND_ON_DARK_3': Brand.onDarkTertiary,
    };
    expect(compiled.keys.toSet(), defines.keys.toSet(),
        reason: 'brand.dart and tool/brand.py must define the same keys');
    for (final e in compiled.entries) {
      final want = e.value is int ? int.parse(defines[e.key]!) : defines[e.key];
      expect(e.value, want, reason: e.key);
    }
  });
}
