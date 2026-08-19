import 'package:flutter/material.dart';

/// OrbixHQ design tokens.
///
/// Sibling to OrbixPOS (`pos_app/lib/app/theme.dart`) - same silhouette, same
/// ink/hairline ramp, deliberately different brand field so the two apps are
/// distinguishable at a glance on a home screen. POS ships blue #1B6FD1;
/// HQ takes deep teal.
class HqColors {
  HqColors._();

  // brand - deep teal, the executive field
  static const brand = Color(0xFF0F766E);
  static const brandD = Color(0xFF0B5A54);
  static const brandSoft = Color(0xFFE6F4F1);

  // surfaces
  static const bg = Color(0xFFF4F6F8);
  static const panel = Color(0xFFFFFFFF);
  static const panel2 = Color(0xFFF7F9FB);

  // ink
  static const ink = Color(0xFF0F172A);
  static const ink2 = Color(0xFF475569);
  static const ink3 = Color(0xFF94A3B8);

  // hairlines
  static const line = Color(0xFFE2E8F0);
  static const line2 = Color(0xFFCBD5E1);

  // status - red means BAD FOR THE BUSINESS, never merely "negative number"
  static const bad = Color(0xFFDC2626);
  static const badSoft = Color(0xFFFEF2F2);
  static const warn = Color(0xFFB45309);
  static const warnSoft = Color(0xFFFFFBEB);
  static const good = Color(0xFF15803D);
  static const goodSoft = Color(0xFFF0FDF4);

  // trust bands
  static const posted = Color(0xFF15803D);
  static const provisional = Color(0xFFB45309);
  static const estimated = Color(0xFF64748B);
}

class HqRadii {
  HqRadii._();
  static const lg = 14.0;
  static const sm = 10.0;
}

ThemeData buildHqTheme() {
  final scheme = ColorScheme.fromSeed(
    seedColor: HqColors.brand,
    brightness: Brightness.light,
  ).copyWith(
    primary: HqColors.brand,
    surface: HqColors.panel,
  );

  return ThemeData(
    useMaterial3: true,
    colorScheme: scheme,
    scaffoldBackgroundColor: HqColors.bg,
    fontFamily: 'Roboto',
    appBarTheme: const AppBarTheme(
      backgroundColor: HqColors.panel,
      foregroundColor: HqColors.ink,
      elevation: 0,
      centerTitle: false,
      surfaceTintColor: Colors.transparent,
    ),
    cardTheme: CardThemeData(
      color: HqColors.panel,
      elevation: 0,
      margin: EdgeInsets.zero,
      shape: RoundedRectangleBorder(
        borderRadius: BorderRadius.circular(HqRadii.lg),
        side: const BorderSide(color: HqColors.line),
      ),
    ),
    dividerTheme: const DividerThemeData(color: HqColors.line, thickness: 1, space: 1),
    filledButtonTheme: FilledButtonThemeData(
      style: FilledButton.styleFrom(
        backgroundColor: HqColors.brand,
        foregroundColor: Colors.white,
        minimumSize: const Size.fromHeight(52),
        shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(HqRadii.sm)),
        textStyle: const TextStyle(fontSize: 16, fontWeight: FontWeight.w600),
      ),
    ),
    outlinedButtonTheme: OutlinedButtonThemeData(
      style: OutlinedButton.styleFrom(
        foregroundColor: HqColors.ink,
        minimumSize: const Size.fromHeight(52),
        side: const BorderSide(color: HqColors.line2),
        shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(HqRadii.sm)),
        textStyle: const TextStyle(fontSize: 16, fontWeight: FontWeight.w600),
      ),
    ),
    inputDecorationTheme: InputDecorationTheme(
      filled: true,
      fillColor: HqColors.panel,
      contentPadding: const EdgeInsets.symmetric(horizontal: 14, vertical: 16),
      border: OutlineInputBorder(
        borderRadius: BorderRadius.circular(HqRadii.sm),
        borderSide: const BorderSide(color: HqColors.line2),
      ),
      enabledBorder: OutlineInputBorder(
        borderRadius: BorderRadius.circular(HqRadii.sm),
        borderSide: const BorderSide(color: HqColors.line2),
      ),
      focusedBorder: OutlineInputBorder(
        borderRadius: BorderRadius.circular(HqRadii.sm),
        borderSide: const BorderSide(color: HqColors.brand, width: 2),
      ),
    ),
  );
}

/// Type ramp. Executive screens are read at arm's length, often in sunlight.
class HqText {
  HqText._();
  static const hero = TextStyle(fontSize: 40, fontWeight: FontWeight.w700, color: HqColors.ink, height: 1.05, letterSpacing: -1);
  static const verdict = TextStyle(fontSize: 17, fontWeight: FontWeight.w600, color: HqColors.ink, height: 1.3);
  static const title = TextStyle(fontSize: 20, fontWeight: FontWeight.w700, color: HqColors.ink);
  static const section = TextStyle(fontSize: 12, fontWeight: FontWeight.w700, color: HqColors.ink3, letterSpacing: 0.8);
  static const body = TextStyle(fontSize: 14, color: HqColors.ink2, height: 1.4);
  static const label = TextStyle(fontSize: 13, color: HqColors.ink2);
  static const tiny = TextStyle(fontSize: 11.5, color: HqColors.ink3, height: 1.35);
  static const tileValue = TextStyle(fontSize: 19, fontWeight: FontWeight.w700, color: HqColors.ink);
}

/// Premium surfaces for the executive hero areas.
class HqSurfaces {
  HqSurfaces._();

  /// The hero gradient - deep teal into near-black, the "executive" field.
  static const heroGradient = LinearGradient(
    begin: Alignment.topLeft,
    end: Alignment.bottomRight,
    colors: [Color(0xFF0F766E), Color(0xFF0B3B39), Color(0xFF08201F)],
    stops: [0.0, 0.55, 1.0],
  );

  static const brandGradient = LinearGradient(
    begin: Alignment.topLeft,
    end: Alignment.bottomRight,
    colors: [Color(0xFF12897F), Color(0xFF0C5F58)],
  );

  /// Warm accent - used sparingly, for the one thing that matters most.
  static const accent = Color(0xFFD9A441);
  static const accentSoft = Color(0xFF3A3116);

  static List<BoxShadow> get card => [
        BoxShadow(color: const Color(0xFF0F172A).withValues(alpha: 0.04), blurRadius: 2, offset: const Offset(0, 1)),
        BoxShadow(color: const Color(0xFF0F172A).withValues(alpha: 0.05), blurRadius: 14, offset: const Offset(0, 6)),
      ];

  static List<BoxShadow> get hero => [
        BoxShadow(color: const Color(0xFF0B3B39).withValues(alpha: 0.30), blurRadius: 26, offset: const Offset(0, 12)),
      ];
}

/// Ink ramp for use ON the dark hero surface.
class HqOnDark {
  HqOnDark._();
  static const primary = Color(0xFFFFFFFF);
  static const secondary = Color(0xFFB9D6D2);
  static const tertiary = Color(0xFF7FA9A4);
  static const hairline = Color(0x2AFFFFFF);
}
