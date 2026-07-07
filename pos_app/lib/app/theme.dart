import 'package:flutter/material.dart';

/// OrbixPOS design tokens, lifted verbatim from the HTML prototype
/// (`pos-ui-prototype/styles.css` `:root`). Single source of truth for colour
/// so every screen matches the agreed visual design.
class AppColors {
  AppColors._();

  // surfaces
  static const bg = Color(0xFFEEF1F6);
  static const panel = Color(0xFFFFFFFF);
  static const panel2 = Color(0xFFF7F8FB);

  // ink (text)
  static const ink = Color(0xFF0F172A);
  static const ink2 = Color(0xFF475569);
  static const ink3 = Color(0xFF94A3B8);

  // hairlines
  static const line = Color(0xFFE2E8F0);
  static const line2 = Color(0xFFCBD5E1);

  // brand (blue) — primary actions and the "Enter" accent, per the reference till
  static const brand = Color(0xFF1B6FD1);
  static const brandD = Color(0xFF155BAC);
  static const brandSoft = Color(0xFFE8F1FB);

  // pay (navy) — the big PAY call-to-action, per the reference till
  static const pay = Color(0xFF00296B);
  static const payD = Color(0xFF001E52);

  // keypad number keys — light "hardware" grey (reference till)
  static const key = Color(0xFFE7EAEE);
  static const keyLine = Color(0xFFC4CBD4);

  // clear / CE — solid maroon, the destructive keypad action (reference till)
  static const clear = Color(0xFF9E1B1B);
  static const clearD = Color(0xFF7E1414);

  // status
  static const danger = Color(0xFFDC2626);
  static const dangerSoft = Color(0xFFFEF2F2);
  static const warn = Color(0xFFB45309);
  static const warnSoft = Color(0xFFFFFBEB);
  static const ok = Color(0xFF16A34A);
  static const okSoft = Color(0xFFF0FDF4);

  // excel grid (supermarket register)
  static const xlLine = Color(0xFFD4D7DC);
  static const xlLineHard = Color(0xFFC6CBD1);
  static const xlHead = Color(0xFFF3F4F6);
  static const xlSel = Color(0xFFEAF1FB);
  static const xlInk = Color(0xFF1F2328);
}

/// Corner radii from the prototype (`--radius`, `--radius-sm`).
class AppRadii {
  AppRadii._();
  static const lg = 14.0;
  static const sm = 10.0;
  static const pill = 999.0;

  static const brLg = BorderRadius.all(Radius.circular(lg));
  static const brSm = BorderRadius.all(Radius.circular(sm));
  static const brPill = BorderRadius.all(Radius.circular(pill));
}

/// Tabular figures — the prototype uses `font-variant-numeric:tabular-nums`
/// pervasively so money/qty columns line up.
const List<FontFeature> kTabular = [FontFeature.tabularFigures()];

/// The brand font. Segoe UI on the primary (Windows) target; falls back to the
/// platform default elsewhere.
const String kFontFamily = 'Segoe UI';

ThemeData buildAppTheme() {
  final base = ThemeData(
    useMaterial3: true,
    fontFamily: kFontFamily,
    scaffoldBackgroundColor: AppColors.bg,
    colorScheme: ColorScheme.fromSeed(
      seedColor: AppColors.brand,
      primary: AppColors.brand,
      surface: AppColors.panel,
      error: AppColors.danger,
      brightness: Brightness.light,
    ),
    splashFactory: InkRipple.splashFactory,
  );

  return base.copyWith(
    textTheme: base.textTheme.apply(
      bodyColor: AppColors.ink,
      displayColor: AppColors.ink,
    ),
    dividerTheme: const DividerThemeData(
      color: AppColors.line,
      thickness: 1,
      space: 1,
    ),
    inputDecorationTheme: InputDecorationTheme(
      filled: true,
      fillColor: AppColors.panel,
      isDense: true,
      contentPadding: const EdgeInsets.symmetric(horizontal: 14, vertical: 12),
      hintStyle: const TextStyle(color: AppColors.ink3),
      enabledBorder: OutlineInputBorder(
        borderRadius: AppRadii.brSm,
        borderSide: const BorderSide(color: AppColors.line2),
      ),
      border: OutlineInputBorder(
        borderRadius: AppRadii.brSm,
        borderSide: const BorderSide(color: AppColors.line2),
      ),
      focusedBorder: OutlineInputBorder(
        borderRadius: AppRadii.brSm,
        borderSide: const BorderSide(color: AppColors.brand, width: 2),
      ),
      errorBorder: OutlineInputBorder(
        borderRadius: AppRadii.brSm,
        borderSide: const BorderSide(color: AppColors.danger),
      ),
    ),
    scrollbarTheme: ScrollbarThemeData(
      thumbColor: WidgetStateProperty.all(AppColors.line2),
      radius: const Radius.circular(7),
    ),
  );
}

/// Shared shadow tokens (`--shadow`, `--shadow-lg`).
class AppShadows {
  AppShadows._();
  static const card = [
    BoxShadow(color: Color(0x0F0F172A), blurRadius: 2, offset: Offset(0, 1)),
    BoxShadow(color: Color(0x140F172A), blurRadius: 24, offset: Offset(0, 8)),
  ];
  static const lg = [
    BoxShadow(color: Color(0x470F172A), blurRadius: 60, offset: Offset(0, 20)),
  ];
}
