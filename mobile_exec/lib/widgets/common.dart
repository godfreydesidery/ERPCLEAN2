import 'dart:math' as math;

import 'package:flutter/material.dart';

import '../app/format.dart';
import 'charts.dart';
import '../app/theme.dart';

/// Shared OrbixHQ components.
///
/// Every executive screen is composed from these: one dark hero, white panels
/// on the grey field, exception rows above supporting detail, and a single
/// drill-through in the thumb zone.

// ---------------------------------------------------------------------------
// Trust band
// ---------------------------------------------------------------------------

/// How much the number can be trusted. Doctrine 7 - it is always stamped.
enum TrustBand { posted, provisional, estimated }

extension TrustBandMeta on TrustBand {
  String get label => switch (this) {
        TrustBand.posted => 'POSTED',
        TrustBand.provisional => 'PROVISIONAL',
        TrustBand.estimated => 'ESTIMATED',
      };

  Color get color => switch (this) {
        TrustBand.posted => HqColors.posted,
        TrustBand.provisional => HqColors.provisional,
        TrustBand.estimated => HqColors.ink3,
      };

  Color get softColor => switch (this) {
        TrustBand.posted => HqColors.goodSoft,
        TrustBand.provisional => HqColors.warnSoft,
        TrustBand.estimated => HqColors.panel2,
      };
}

/// A small pill with a dot + band name. Reads on the dark hero and on white.
class TrustChip extends StatelessWidget {
  const TrustChip({
    super.key,
    required this.band,
    this.onDark = false,
    this.overrideLabel,
  });

  final TrustBand band;
  final bool onDark;

  /// Optional replacement copy, e.g. "PROVISIONAL - 2 branches pending".
  final String? overrideLabel;

  @override
  Widget build(BuildContext context) {
    final base = band.color;
    // On the dark field the status inks are too heavy - lift them toward white.
    final dot = onDark ? Color.lerp(base, Colors.white, 0.45)! : base;
    final ink = onDark ? Color.lerp(base, Colors.white, 0.62)! : base;
    final fill = onDark ? Colors.white.withValues(alpha: 0.09) : band.softColor;
    final edge = onDark ? HqOnDark.hairline : base.withValues(alpha: 0.22);

    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 9, vertical: 5),
      decoration: BoxDecoration(
        color: fill,
        borderRadius: BorderRadius.circular(999),
        border: Border.all(color: edge),
      ),
      child: Row(
        mainAxisSize: MainAxisSize.min,
        children: [
          Container(
            width: 6,
            height: 6,
            decoration: BoxDecoration(color: dot, shape: BoxShape.circle),
          ),
          const SizedBox(width: 6),
          Flexible(
            child: Text(
              overrideLabel ?? band.label,
              maxLines: 1,
              overflow: TextOverflow.ellipsis,
              style: TextStyle(
                fontSize: 10.5,
                fontWeight: FontWeight.w700,
                letterSpacing: 0.7,
                color: ink,
              ),
            ),
          ),
        ],
      ),
    );
  }
}

// ---------------------------------------------------------------------------
// Comparison row - doctrine 4, no number travels alone
// ---------------------------------------------------------------------------

class ComparisonRow extends StatelessWidget {
  const ComparisonRow({
    super.key,
    required this.label,
    required this.value,
    this.delta,
    this.higherIsBetter = true,
    this.onDark = false,
  });

  final String label;
  final String value;

  /// Movement in percent. Null hides the triangle.
  final double? delta;

  /// A FALL in debtors is good news - pass false and the arrow turns green.
  final bool higherIsBetter;
  final bool onDark;

  @override
  Widget build(BuildContext context) {
    final d = delta;
    final up = (d ?? 0) >= 0;
    final good = up == higherIsBetter;

    final labelInk = onDark ? HqOnDark.tertiary : HqColors.ink2;
    final valueInk = onDark ? HqOnDark.primary : HqColors.ink;
    final base = good ? HqColors.good : HqColors.bad;
    final moveInk = onDark ? Color.lerp(base, Colors.white, 0.52)! : base;

    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 5),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.center,
        children: [
          Expanded(
            child: Text(
              label,
              maxLines: 2,
              overflow: TextOverflow.ellipsis,
              style: TextStyle(fontSize: 13, height: 1.25, color: labelInk),
            ),
          ),
          const SizedBox(width: 12),
          Text(
            value,
            maxLines: 1,
            overflow: TextOverflow.ellipsis,
            style: TextStyle(
              fontSize: 14,
              fontWeight: FontWeight.w700,
              color: valueInk,
            ),
          ),
          if (d != null) ...[
            const SizedBox(width: 8),
            Icon(
              up ? Icons.arrow_drop_up : Icons.arrow_drop_down,
              size: 20,
              color: moveInk,
            ),
            Text(
              pct(d.abs()),
              maxLines: 1,
              style: TextStyle(
                fontSize: 13,
                fontWeight: FontWeight.w700,
                color: moveInk,
              ),
            ),
          ],
        ],
      ),
    );
  }
}

// ---------------------------------------------------------------------------
// Hero card
// ---------------------------------------------------------------------------

class HeroCard extends StatelessWidget {
  const HeroCard({
    super.key,
    required this.question,
    required this.verdict,
    required this.heroValue,
    this.heroSub,
    this.comparisons = const <Widget>[],
    this.chart,
    this.asOf,
    this.trustLabel,
    this.band = TrustBand.posted,
    this.accented = false,
  });

  /// Doctrine 1 - the title is the question the owner asks.
  final String question;

  /// Doctrine 2 - plain words before any number.
  final String verdict;

  /// Doctrine 3 - exactly one hero number.
  final String heroValue;
  final String? heroSub;
  final List<Widget> comparisons;
  final Widget? chart;
  final String? asOf;
  final String? trustLabel;
  final TrustBand band;

  /// Gold rule beside the hero value - reserved for the single most important
  /// figure in a session. Doctrine: use the accent sparingly.
  final bool accented;

  @override
  Widget build(BuildContext context) {
    return Container(
      width: double.infinity,
      padding: const EdgeInsets.all(20),
      decoration: BoxDecoration(
        gradient: HqSurfaces.heroGradient,
        borderRadius: BorderRadius.circular(22),
        boxShadow: HqSurfaces.hero,
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        mainAxisSize: MainAxisSize.min,
        children: [
          Text(
            question,
            style: const TextStyle(
              fontSize: 13,
              fontWeight: FontWeight.w600,
              letterSpacing: 0.3,
              height: 1.3,
              color: HqOnDark.secondary,
            ),
          ),
          const SizedBox(height: 10),
          Text(
            verdict,
            style: const TextStyle(
              fontSize: 17,
              fontWeight: FontWeight.w600,
              height: 1.32,
              color: HqOnDark.primary,
            ),
          ),
          const SizedBox(height: 16),
          Row(
            crossAxisAlignment: CrossAxisAlignment.center,
            children: [
              if (accented) ...[
                Container(
                  width: 3,
                  height: 38,
                  decoration: BoxDecoration(
                    color: HqSurfaces.accent,
                    borderRadius: BorderRadius.circular(2),
                  ),
                ),
                const SizedBox(width: 12),
              ],
              Expanded(
                child: FittedBox(
                  fit: BoxFit.scaleDown,
                  alignment: Alignment.centerLeft,
                  child: Text(
                    heroValue,
                    maxLines: 1,
                    style: const TextStyle(
                      fontSize: 40,
                      fontWeight: FontWeight.w700,
                      letterSpacing: -1,
                      height: 1.05,
                      color: HqOnDark.primary,
                    ),
                  ),
                ),
              ),
            ],
          ),
          if (heroSub != null) ...[
            const SizedBox(height: 6),
            Text(
              heroSub!,
              style: const TextStyle(
                fontSize: 13,
                height: 1.35,
                color: HqOnDark.tertiary,
              ),
            ),
          ],
          if (comparisons.isNotEmpty) ...[
            const SizedBox(height: 14),
            ...comparisons,
          ],
          if (chart != null) ...[
            const SizedBox(height: 16),
            chart!,
          ],
          const SizedBox(height: 16),
          const Divider(height: 1, thickness: 1, color: HqOnDark.hairline),
          const SizedBox(height: 12),
          Row(
            crossAxisAlignment: CrossAxisAlignment.center,
            children: [
              Expanded(
                child: Text(
                  asOf ?? '',
                  maxLines: 2,
                  overflow: TextOverflow.ellipsis,
                  style: const TextStyle(
                    fontSize: 11.5,
                    height: 1.35,
                    color: HqOnDark.tertiary,
                  ),
                ),
              ),
              const SizedBox(width: 10),
              // Bounded so the chip's own Flexible can ellipsize. Unbounded it
              // takes its intrinsic width and pushes the row off a 360 phone.
              Flexible(
                child: TrustChip(
                    band: band, onDark: true, overrideLabel: trustLabel),
              ),
            ],
          ),
        ],
      ),
    );
  }
}

// ---------------------------------------------------------------------------
// White panel
// ---------------------------------------------------------------------------

class HqCard extends StatelessWidget {
  const HqCard({
    super.key,
    required this.child,
    this.padding = const EdgeInsets.all(16),
    this.onTap,
  });

  final Widget child;
  final EdgeInsets padding;
  final VoidCallback? onTap;

  @override
  Widget build(BuildContext context) {
    final radius = BorderRadius.circular(16);

    final panel = Container(
      width: double.infinity,
      padding: padding,
      decoration: BoxDecoration(
        color: HqColors.panel,
        borderRadius: radius,
        border: Border.all(color: HqColors.line),
        boxShadow: HqSurfaces.card,
      ),
      child: child,
    );

    if (onTap == null) return panel;

    return Material(
      color: Colors.transparent,
      borderRadius: radius,
      child: InkWell(
        onTap: onTap,
        borderRadius: radius,
        child: panel,
      ),
    );
  }
}

// ---------------------------------------------------------------------------
// Section label
// ---------------------------------------------------------------------------

class SectionLabel extends StatelessWidget {
  const SectionLabel({super.key, required this.text, this.trailing});

  final String text;
  final String? trailing;

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.only(top: 2, bottom: 10),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.end,
        children: [
          Expanded(
            child: Text(
              text.toUpperCase(),
              maxLines: 2,
              overflow: TextOverflow.ellipsis,
              style: HqText.section,
            ),
          ),
          if (trailing != null) ...[
            const SizedBox(width: 12),
            Flexible(
              child: Text(
                trailing!,
                maxLines: 1,
                overflow: TextOverflow.ellipsis,
                textAlign: TextAlign.right,
                style: HqText.tiny,
              ),
            ),
          ],
        ],
      ),
    );
  }
}

// ---------------------------------------------------------------------------
// Exception tile - doctrine 6: what, how much, who owns it, one tap
// ---------------------------------------------------------------------------

class ExceptionTile extends StatelessWidget {
  const ExceptionTile({
    super.key,
    required this.title,
    required this.detail,
    required this.amount,
    required this.owner,
    required this.actionLabel,
    this.onAction,
    this.severe = true,
  });

  final String title;
  final String detail;
  final String amount;
  final String owner;
  final String actionLabel;
  final VoidCallback? onAction;

  /// Red bar when it is costing money now, amber when it is a warning.
  final bool severe;

  static String initialsOf(String name) {
    final parts =
        name.trim().split(RegExp(r'\s+')).where((p) => p.isNotEmpty).toList();
    if (parts.isEmpty) return '?';
    if (parts.length == 1) {
      final p = parts.first;
      return (p.length == 1 ? p : p.substring(0, 2)).toUpperCase();
    }
    return '${parts.first[0]}${parts[1][0]}'.toUpperCase();
  }

  @override
  Widget build(BuildContext context) {
    final status = severe ? HqColors.bad : HqColors.warn;

    return Container(
      decoration: BoxDecoration(
        color: HqColors.panel,
        borderRadius: BorderRadius.circular(16),
        border: Border.all(color: HqColors.line),
        boxShadow: HqSurfaces.card,
      ),
      clipBehavior: Clip.antiAlias,
      child: IntrinsicHeight(
        child: Row(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            Container(width: 3, color: status),
            Expanded(
              child: Padding(
                padding: const EdgeInsets.fromLTRB(14, 13, 14, 11),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  mainAxisSize: MainAxisSize.min,
                  children: [
                    Row(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Expanded(
                          child: Column(
                            crossAxisAlignment: CrossAxisAlignment.start,
                            mainAxisSize: MainAxisSize.min,
                            children: [
                              Text(
                                title,
                                maxLines: 2,
                                overflow: TextOverflow.ellipsis,
                                style: const TextStyle(
                                  fontSize: 14.5,
                                  fontWeight: FontWeight.w700,
                                  height: 1.25,
                                  color: HqColors.ink,
                                ),
                              ),
                              const SizedBox(height: 3),
                              Text(
                                detail,
                                maxLines: 3,
                                overflow: TextOverflow.ellipsis,
                                style:
                                    HqText.tiny.copyWith(color: HqColors.ink2),
                              ),
                            ],
                          ),
                        ),
                        const SizedBox(width: 12),
                        ConstrainedBox(
                          constraints: const BoxConstraints(maxWidth: 120),
                          child: Text(
                            amount,
                            maxLines: 1,
                            overflow: TextOverflow.ellipsis,
                            textAlign: TextAlign.right,
                            style: TextStyle(
                              fontSize: 15.5,
                              fontWeight: FontWeight.w700,
                              letterSpacing: -0.2,
                              color: status,
                            ),
                          ),
                        ),
                      ],
                    ),
                    const SizedBox(height: 12),
                    Row(
                      children: [
                        _Avatar(initials: initialsOf(owner), tint: status),
                        const SizedBox(width: 8),
                        Expanded(
                          child: Text(
                            owner,
                            maxLines: 1,
                            overflow: TextOverflow.ellipsis,
                            style: const TextStyle(
                              fontSize: 12.5,
                              fontWeight: FontWeight.w600,
                              color: HqColors.ink2,
                            ),
                          ),
                        ),
                        const SizedBox(width: 10),
                        _PillButton(label: actionLabel, onTap: onAction),
                      ],
                    ),
                  ],
                ),
              ),
            ),
          ],
        ),
      ),
    );
  }
}

class _Avatar extends StatelessWidget {
  const _Avatar({required this.initials, required this.tint});

  final String initials;
  final Color tint;

  @override
  Widget build(BuildContext context) {
    return Container(
      width: 26,
      height: 26,
      alignment: Alignment.center,
      decoration: BoxDecoration(
        color: tint.withValues(alpha: 0.10),
        shape: BoxShape.circle,
        border: Border.all(color: tint.withValues(alpha: 0.22)),
      ),
      child: Text(
        initials,
        style: TextStyle(
          fontSize: 10.5,
          fontWeight: FontWeight.w700,
          letterSpacing: 0.2,
          color: tint,
        ),
      ),
    );
  }
}

class _PillButton extends StatelessWidget {
  const _PillButton({required this.label, this.onTap});

  final String label;
  final VoidCallback? onTap;

  @override
  Widget build(BuildContext context) {
    final radius = BorderRadius.circular(999);
    return Material(
      color: Colors.transparent,
      borderRadius: radius,
      child: InkWell(
        onTap: onTap,
        borderRadius: radius,
        child: Container(
          padding: const EdgeInsets.symmetric(horizontal: 13, vertical: 7),
          decoration: BoxDecoration(
            borderRadius: radius,
            border: Border.all(color: HqColors.line2),
            color: HqColors.panel2,
          ),
          child: Text(
            label,
            maxLines: 1,
            overflow: TextOverflow.ellipsis,
            style: const TextStyle(
              fontSize: 12,
              fontWeight: FontWeight.w700,
              color: HqColors.brand,
            ),
          ),
        ),
      ),
    );
  }
}

// ---------------------------------------------------------------------------
// Stat tile + sparkline
// ---------------------------------------------------------------------------

class StatTile extends StatelessWidget {
  const StatTile({
    super.key,
    required this.label,
    required this.value,
    this.delta,
    this.deltaGood,
    this.spark,
  });

  final String label;
  final String value;

  /// Already-formatted movement, e.g. "+4.2% vs last month".
  final String? delta;

  /// True = good for the business, false = bad, null = neutral ink.
  final bool? deltaGood;
  final List<double>? spark;

  @override
  Widget build(BuildContext context) {
    final s = spark;
    final deltaInk = deltaGood == null
        ? HqColors.ink2
        : (deltaGood! ? HqColors.good : HqColors.bad);

    return Container(
      padding: const EdgeInsets.fromLTRB(14, 12, 14, 12),
      decoration: BoxDecoration(
        color: HqColors.panel,
        borderRadius: BorderRadius.circular(16),
        border: Border.all(color: HqColors.line),
        boxShadow: HqSurfaces.card,
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        mainAxisSize: MainAxisSize.min,
        children: [
          Text(
            label.toUpperCase(),
            maxLines: 2,
            overflow: TextOverflow.ellipsis,
            style: const TextStyle(
              fontSize: 11.5,
              fontWeight: FontWeight.w700,
              letterSpacing: 0.6,
              height: 1.25,
              color: HqColors.ink3,
            ),
          ),
          const SizedBox(height: 7),
          FittedBox(
            fit: BoxFit.scaleDown,
            alignment: Alignment.centerLeft,
            child: Text(value, maxLines: 1, style: HqText.tileValue),
          ),
          if (delta != null) ...[
            const SizedBox(height: 4),
            Text(
              delta!,
              maxLines: 2,
              overflow: TextOverflow.ellipsis,
              style: TextStyle(
                fontSize: 11.5,
                fontWeight: FontWeight.w600,
                height: 1.3,
                color: deltaInk,
              ),
            ),
          ],
          if (s != null && s.length > 1) ...[
            const SizedBox(height: 10),
            SizedBox(height: 28, child: Sparkline(values: s)),
          ],
        ],
      ),
    );
  }
}

/// A tiny trend line - doctrine 5, history is always visible.

class _SparkPainter extends CustomPainter {
  _SparkPainter({
    required this.values,
    required this.color,
    required this.filled,
  });

  final List<double> values;
  final Color color;
  final bool filled;

  @override
  void paint(Canvas canvas, Size size) {
    if (values.length < 2 || size.width <= 0 || size.height <= 0) return;

    final lo = values.reduce(math.min);
    final hi = values.reduce(math.max);
    final span = (hi - lo).abs() < 1e-9 ? 1.0 : (hi - lo);

    const inset = 2.0;
    final h = math.max(1.0, size.height - inset * 2);
    final dx = size.width / (values.length - 1);

    final points = <Offset>[
      for (var i = 0; i < values.length; i++)
        Offset(dx * i, inset + h - ((values[i] - lo) / span) * h),
    ];

    if (filled) {
      final area = Path()..moveTo(points.first.dx, size.height);
      for (final p in points) {
        area.lineTo(p.dx, p.dy);
      }
      area
        ..lineTo(points.last.dx, size.height)
        ..close();
      canvas.drawPath(
        area,
        Paint()
          ..shader = LinearGradient(
            begin: Alignment.topCenter,
            end: Alignment.bottomCenter,
            colors: [
              color.withValues(alpha: 0.20),
              color.withValues(alpha: 0.0),
            ],
          ).createShader(Offset.zero & size),
      );
    }

    final line = Path()..moveTo(points.first.dx, points.first.dy);
    for (final p in points.skip(1)) {
      line.lineTo(p.dx, p.dy);
    }
    canvas.drawPath(
      line,
      Paint()
        ..color = color
        ..style = PaintingStyle.stroke
        ..strokeWidth = 2
        ..strokeCap = StrokeCap.round
        ..strokeJoin = StrokeJoin.round,
    );

    canvas.drawCircle(points.last, 2.6, Paint()..color = color);
  }

  @override
  bool shouldRepaint(covariant _SparkPainter old) =>
      old.color != color ||
      old.filled != filled ||
      !_sameValues(old.values, values);

  static bool _sameValues(List<double> a, List<double> b) {
    if (identical(a, b)) return true;
    if (a.length != b.length) return false;
    for (var i = 0; i < a.length; i++) {
      if (a[i] != b[i]) return false;
    }
    return true;
  }
}

// ---------------------------------------------------------------------------
// Residual, stamp, header, drill
// ---------------------------------------------------------------------------

/// Doctrine 8 - state the residual and the materiality rule.
class ResidualLine extends StatelessWidget {
  const ResidualLine({super.key, required this.text});

  final String text;

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 10),
      child: Text(text, textAlign: TextAlign.center, style: HqText.tiny),
    );
  }
}

/// Doctrine 7 - the as-of stamp plus coverage, on light or dark.
class AsOfLine extends StatelessWidget {
  const AsOfLine({
    super.key,
    required this.asOf,
    required this.coverage,
    this.onDark = false,
  });

  final String asOf;
  final String coverage;
  final bool onDark;

  @override
  Widget build(BuildContext context) {
    final ink = onDark ? HqOnDark.tertiary : HqColors.ink3;
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      mainAxisSize: MainAxisSize.min,
      children: [
        Text(
          asOf,
          maxLines: 2,
          overflow: TextOverflow.ellipsis,
          style: TextStyle(fontSize: 11.5, height: 1.35, color: ink),
        ),
        const SizedBox(height: 2),
        Text(
          coverage,
          maxLines: 2,
          overflow: TextOverflow.ellipsis,
          style: TextStyle(fontSize: 11.5, height: 1.35, color: ink),
        ),
      ],
    );
  }
}

/// Doctrine 1 on a screen that carries no hero card.
class QuestionHeader extends StatelessWidget {
  const QuestionHeader({super.key, required this.question, this.subtitle});

  final String question;
  final String? subtitle;

  @override
  Widget build(BuildContext context) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      mainAxisSize: MainAxisSize.min,
      children: [
        Text(question, style: HqText.title.copyWith(height: 1.25)),
        if (subtitle != null) ...[
          const SizedBox(height: 6),
          Text(subtitle!, style: HqText.body),
        ],
      ],
    );
  }
}

/// Doctrine 9 - the single tap-through per screen, in the thumb zone.
class DrillButton extends StatelessWidget {
  const DrillButton({super.key, required this.label, this.onTap});

  final String label;
  final VoidCallback? onTap;

  @override
  Widget build(BuildContext context) {
    final radius = BorderRadius.circular(HqRadii.sm);

    return Material(
      color: HqColors.panel,
      borderRadius: radius,
      child: InkWell(
        onTap: onTap,
        borderRadius: radius,
        child: Container(
          width: double.infinity,
          padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 15),
          decoration: BoxDecoration(
            borderRadius: radius,
            border: Border.all(color: HqColors.line2),
          ),
          child: Row(
            children: [
              Expanded(
                child: Text(
                  label,
                  maxLines: 2,
                  overflow: TextOverflow.ellipsis,
                  style: const TextStyle(
                    fontSize: 15,
                    fontWeight: FontWeight.w600,
                    height: 1.25,
                    color: HqColors.ink,
                  ),
                ),
              ),
              const SizedBox(width: 10),
              const Icon(
                Icons.chevron_right_rounded,
                size: 22,
                color: HqColors.ink3,
              ),
            ],
          ),
        ),
      ),
    );
  }
}
