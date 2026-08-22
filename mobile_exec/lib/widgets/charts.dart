import 'dart:math' as math;
import 'dart:ui' show FontFeature, PathMetric;

import 'package:flutter/material.dart';

import '../app/format.dart';
import '../app/theme.dart';

/// OrbixHQ chart library - hand-painted, no chart packages.
///
/// PALETTE DISCIPLINE (validated for colourblind safety - do not improvise):
///
///  * Magnitude comparison (this branch vs that branch, this month vs that
///    month) uses the EMPHASIS PAIR: one hue plus gray. The eye is being asked
///    "which is bigger", not "which is which", so extra hues only add noise.
///  * Ordered buckets (ageing, tenure, recency) use the SEQUENTIAL teal ramp -
///    older money is darker money.
///  * CATEGORICAL hues are used only when the series themselves are the
///    subject (channel mix, product mix). Capped at four, and ALWAYS
///    direct-labelled - a chart that needs a legend lookup has already lost.
///  * On the dark hero surface the only series colour is [VizColors.mint];
///    brand teal on a teal field fails contrast.
///  * Never a dual axis. Never a pie of two slices.
///  * Red carries meaning: bad for the business. A fall in debtors is green.
///
/// Every widget here fills its parent's width and states its own height.
class VizColors {
  VizColors._();

  /// Categorical - max four, always direct-labelled.
  static const cat1 = Color(0xFF2A78D6);
  static const cat2 = Color(0xFFEB6834);
  static const cat3 = Color(0xFF1BAF7A);
  static const cat4 = Color(0xFFEDA100);

  /// The emphasis pair: the thing you mean, and everything else.
  static const emphasis = HqColors.brand;
  static const muted = Color(0xFFCBD5E1);

  /// Sequential teal ramp, light to dark.
  static const seq = <Color>[
    Color(0xFFCCE7E4),
    Color(0xFF99CFC9),
    Color(0xFF66B7AE),
    Color(0xFF339F93),
    Color(0xFF0F766E),
    Color(0xFF0B5A54),
  ];

  /// The one series colour that survives the dark hero field.
  static const mint = Color(0xFF5EEAD4);

  static const _cats = <Color>[cat1, cat2, cat3, cat4];

  /// Categorical hue by position, wrapping after four.
  static Color cat(int i) => _cats[i % _cats.length];

  /// Sequential shade by position, clamped to the ramp.
  static Color shade(int i) => seq[i.clamp(0, seq.length - 1)];
}

// ---------------------------------------------------------------------------
// painting helpers
// ---------------------------------------------------------------------------

/// Paints [s] at [at]. [align] treats [at] as the left, centre or right anchor.
void _text(
  Canvas canvas,
  String s,
  Offset at,
  TextStyle style, {
  TextAlign align = TextAlign.left,
  double maxW = 400,
}) {
  if (s.isEmpty) return;
  final tp = TextPainter(
    text: TextSpan(text: s, style: style),
    textDirection: TextDirection.ltr,
    textAlign: align,
    maxLines: 1,
    ellipsis: '…',
  )..layout(maxWidth: math.max(8, maxW));
  var dx = at.dx;
  if (align == TextAlign.center) dx -= tp.width / 2;
  if (align == TextAlign.right) dx -= tp.width;
  tp.paint(canvas, Offset(dx, at.dy));
}

/// A dashed straight line.
void _dashLine(
  Canvas canvas,
  Offset a,
  Offset b,
  Paint paint, {
  double dash = 5,
  double gap = 4,
}) {
  final total = (b - a).distance;
  if (total <= 0) return;
  final dir = (b - a) / total;
  var t = 0.0;
  while (t < total) {
    final end = math.min(t + dash, total);
    canvas.drawLine(a + dir * t, a + dir * end, paint);
    t = end + gap;
  }
}

/// A dashed rendering of an arbitrary path (used for the forecast curve).
void _dashPath(
  Canvas canvas,
  Path path,
  Paint paint, {
  double dash = 6,
  double gap = 5,
}) {
  for (final PathMetric m in path.computeMetrics()) {
    var t = 0.0;
    while (t < m.length) {
      final end = math.min(t + dash, m.length);
      canvas.drawPath(m.extractPath(t, end), paint);
      t = end + gap;
    }
  }
}

/// Catmull-Rom smoothed polyline through [pts].
Path _smoothPath(List<Offset> pts) {
  final path = Path();
  if (pts.isEmpty) return path;
  path.moveTo(pts.first.dx, pts.first.dy);
  if (pts.length == 1) return path;
  Offset at(int i) => pts[i.clamp(0, pts.length - 1)];
  for (var i = 0; i < pts.length - 1; i++) {
    final p0 = at(i - 1);
    final p1 = at(i);
    final p2 = at(i + 1);
    final p3 = at(i + 2);
    final c1 = Offset(p1.dx + (p2.dx - p0.dx) / 6, p1.dy + (p2.dy - p0.dy) / 6);
    final c2 = Offset(p2.dx - (p3.dx - p1.dx) / 6, p2.dy - (p3.dy - p1.dy) / 6);
    path.cubicTo(c1.dx, c1.dy, c2.dx, c2.dy, p2.dx, p2.dy);
  }
  return path;
}

double _maxOf(List<double> v, [double seed = 0]) =>
    v.fold<double>(seed, (a, b) => math.max(a, b));

double _minOf(List<double> v, [double seed = 0]) =>
    v.fold<double>(seed, (a, b) => math.min(a, b));

// ---------------------------------------------------------------------------
// 1. Sparkline
// ---------------------------------------------------------------------------

/// The shape of a trend. No axis, no gridlines - it answers "which way",
/// never "how much". Mint by default, because it lives on the dark hero.
class Sparkline extends StatelessWidget {
  const Sparkline({
    super.key,
    required this.values,
    this.color = VizColors.mint,
    this.height = 44,
    this.showLastDot = true,
    this.strokeWidth = 2,
  });

  final List<double> values;
  final Color color;
  final double height;
  final bool showLastDot;
  final double strokeWidth;

  @override
  Widget build(BuildContext context) {
    if (values.length < 2) return SizedBox(height: height);
    return SizedBox(
      height: height,
      width: double.infinity,
      child: CustomPaint(
        painter: _SparkPainter(values, color, showLastDot, strokeWidth),
      ),
    );
  }
}

class _SparkPainter extends CustomPainter {
  _SparkPainter(this.values, this.color, this.showLastDot, this.strokeWidth);

  final List<double> values;
  final Color color;
  final bool showLastDot;
  final double strokeWidth;

  @override
  void paint(Canvas canvas, Size size) {
    final n = values.length;
    if (n < 2 || size.width <= 0 || size.height <= 0) return;

    final lo = _minOf(values, values.first);
    final hi = _maxOf(values, values.first);
    final span = (hi - lo).abs() < 1e-9 ? 1.0 : hi - lo;

    final padY = strokeWidth + 5;
    final padR = showLastDot ? 7.0 : strokeWidth;
    final w = math.max(1.0, size.width - padR);

    double x(int i) => i / (n - 1) * w;
    double y(double v) =>
        size.height - padY - ((v - lo) / span) * (size.height - padY * 2);

    final pts = <Offset>[for (var i = 0; i < n; i++) Offset(x(i), y(values[i]))];
    final line = _smoothPath(pts);

    // area fill, fading out downwards
    final area = Path.from(line)
      ..lineTo(pts.last.dx, size.height)
      ..lineTo(pts.first.dx, size.height)
      ..close();
    canvas.drawPath(
      area,
      Paint()
        ..shader = LinearGradient(
          begin: Alignment.topCenter,
          end: Alignment.bottomCenter,
          colors: [
            color.withValues(alpha: 0.26),
            color.withValues(alpha: 0.02),
          ],
        ).createShader(Offset.zero & size),
    );

    canvas.drawPath(
      line,
      Paint()
        ..style = PaintingStyle.stroke
        ..strokeWidth = strokeWidth
        ..strokeCap = StrokeCap.round
        ..strokeJoin = StrokeJoin.round
        ..color = color,
    );

    if (showLastDot) {
      final p = pts.last;
      canvas.drawCircle(p, 6.5, Paint()..color = color.withValues(alpha: 0.20));
      canvas.drawCircle(p, 3.4, Paint()..color = color);
      canvas.drawCircle(
        p,
        1.3,
        Paint()..color = Colors.white.withValues(alpha: 0.85),
      );
    }
  }

  @override
  bool shouldRepaint(covariant _SparkPainter o) =>
      o.values != values ||
      o.color != color ||
      o.showLastDot != showLastDot ||
      o.strokeWidth != strokeWidth;
}

// ---------------------------------------------------------------------------
// 2. ColumnTrend
// ---------------------------------------------------------------------------

/// Six or more periods of history, so a slow slide cannot hide. The final
/// column is hatched: a part-period must never read as a full one.
class ColumnTrend extends StatelessWidget {
  const ColumnTrend({
    super.key,
    required this.values,
    required this.labels,
    this.target,
    this.height = 150,
    this.hatchLast = true,
    this.barColor = HqColors.brand,
  });

  final List<double> values;
  final List<String> labels;
  final double? target;
  final double height;
  final bool hatchLast;
  final Color barColor;

  @override
  Widget build(BuildContext context) {
    if (values.isEmpty) return SizedBox(height: height);
    return SizedBox(
      height: height,
      width: double.infinity,
      child: CustomPaint(
        painter:
            _ColumnTrendPainter(values, labels, target, hatchLast, barColor),
      ),
    );
  }
}

class _ColumnTrendPainter extends CustomPainter {
  _ColumnTrendPainter(
    this.values,
    this.labels,
    this.target,
    this.hatchLast,
    this.barColor,
  );

  final List<double> values;
  final List<String> labels;
  final double? target;
  final bool hatchLast;
  final Color barColor;

  @override
  void paint(Canvas canvas, Size size) {
    final n = values.length;
    if (n == 0 || size.width <= 0 || size.height <= 0) return;

    const labelH = 18.0;
    final rightPad = target != null ? 34.0 : 0.0;
    final chartW = math.max(1.0, size.width - rightPad);
    final chartH = math.max(1.0, size.height - labelH);

    var hi = _maxOf(values, 0);
    if (target != null) hi = math.max(hi, target!);
    if (hi <= 0) hi = 1;
    hi *= 1.12;

    final gap = n > 10 ? 5.0 : 8.0;
    final barW = math.max(3.0, (chartW - gap * (n - 1)) / n);

    double y(double v) => chartH - (v / hi) * chartH;

    canvas.drawLine(
      Offset(0, chartH),
      Offset(chartW, chartH),
      Paint()
        ..color = HqColors.line
        ..strokeWidth = 1,
    );

    for (var i = 0; i < n; i++) {
      final left = i * (barW + gap);
      final isLast = i == n - 1;
      final partial = hatchLast && isLast;
      final top = y(math.max(0, values[i]));
      final rect =
          Rect.fromLTRB(left, math.min(top, chartH - 1), left + barW, chartH);
      final r = math.min(4.0, barW / 2);
      final rrect = RRect.fromRectAndCorners(
        rect,
        topLeft: Radius.circular(r),
        topRight: Radius.circular(r),
      );

      canvas.drawRRect(
        rrect,
        Paint()..color = partial ? barColor.withValues(alpha: 0.45) : barColor,
      );

      if (partial) {
        canvas.save();
        canvas.clipRRect(rrect);
        final hatch = Paint()
          ..color = Colors.white.withValues(alpha: 0.55)
          ..strokeWidth = 1.4;
        for (var d = -rect.height; d < barW + rect.height; d += 6) {
          canvas.drawLine(
            Offset(rect.left + d, rect.bottom),
            Offset(rect.left + d + rect.height, rect.top),
            hatch,
          );
        }
        canvas.restore();
      }

      if (i < labels.length) {
        _text(
          canvas,
          labels[i],
          Offset(left + barW / 2, chartH + 4),
          HqText.tiny.copyWith(
            fontSize: 10.5,
            fontWeight: isLast ? FontWeight.w700 : FontWeight.w400,
            color: isLast ? HqColors.ink2 : HqColors.ink3,
          ),
          align: TextAlign.center,
          maxW: barW + gap * 2,
        );
      }
    }

    // the plan line, dashed, labelled where it leaves the chart
    if (target != null) {
      final ty = y(target!);
      _dashLine(
        canvas,
        Offset(0, ty),
        Offset(chartW, ty),
        Paint()
          ..color = HqColors.ink2.withValues(alpha: 0.65)
          ..strokeWidth = 1.2,
      );
      _text(
        canvas,
        'plan',
        Offset(chartW + 5, ty - 7),
        HqText.tiny.copyWith(
          fontSize: 10.5,
          fontWeight: FontWeight.w700,
          color: HqColors.ink2,
        ),
        maxW: rightPad,
      );
    }
  }

  @override
  bool shouldRepaint(covariant _ColumnTrendPainter o) =>
      o.values != values ||
      o.labels != labels ||
      o.target != target ||
      o.hatchLast != hatchLast ||
      o.barColor != barColor;
}

// ---------------------------------------------------------------------------
// 3. RankedBars
// ---------------------------------------------------------------------------

enum RankStatus { normal, good, bad, best }

class RankRow {
  const RankRow(
    this.label,
    this.value,
    this.trailing, {
    this.status = RankStatus.normal,
  });

  final String label;
  final double value;
  final String trailing;
  final RankStatus status;
}

/// The league table: who is ahead, who is behind, by how far. Emphasis pair
/// only - ranking is a magnitude question, so colour carries the verdict,
/// never the identity of the row.
class RankedBars extends StatelessWidget {
  const RankedBars({super.key, required this.rows});

  final List<RankRow> rows;

  static Color _colorFor(RankStatus s) => switch (s) {
        RankStatus.bad => HqColors.bad,
        RankStatus.good => HqColors.good,
        RankStatus.best => HqColors.brand,
        RankStatus.normal => VizColors.muted,
      };

  @override
  Widget build(BuildContext context) {
    if (rows.isEmpty) return const SizedBox.shrink();
    final hi = rows.fold<double>(0, (a, r) => math.max(a, r.value.abs()));
    final denom = hi <= 0 ? 1.0 : hi;

    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        for (var i = 0; i < rows.length; i++) ...[
          if (i > 0) const SizedBox(height: 13),
          _RankedRow(
            row: rows[i],
            factor: (rows[i].value.abs() / denom).clamp(0.03, 1.0),
          ),
        ],
      ],
    );
  }
}

class _RankedRow extends StatelessWidget {
  const _RankedRow({required this.row, required this.factor});

  final RankRow row;
  final double factor;

  @override
  Widget build(BuildContext context) {
    final strong = row.status != RankStatus.normal;
    final weight = strong ? FontWeight.w700 : FontWeight.w500;
    final fill = RankedBars._colorFor(row.status);
    final trailingColor = switch (row.status) {
      RankStatus.bad => HqColors.bad,
      RankStatus.good => HqColors.good,
      RankStatus.best => HqColors.brand,
      RankStatus.normal => HqColors.ink,
    };

    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        Row(
          children: [
            Expanded(
              child: Text(
                row.label,
                maxLines: 1,
                overflow: TextOverflow.ellipsis,
                style: HqText.label.copyWith(
                  color: strong ? HqColors.ink : HqColors.ink2,
                  fontWeight: weight,
                ),
              ),
            ),
            const SizedBox(width: 10),
            Text(
              row.trailing,
              style: HqText.label.copyWith(
                color: trailingColor,
                fontWeight: weight,
                fontFeatures: const [FontFeature.tabularFigures()],
              ),
            ),
          ],
        ),
        const SizedBox(height: 6),
        ClipRRect(
          borderRadius: BorderRadius.circular(4),
          child: Container(
            height: 8,
            color: HqColors.line,
            alignment: Alignment.centerLeft,
            child: FractionallySizedBox(
              widthFactor: factor,
              child: Container(
                decoration: BoxDecoration(
                  color: fill,
                  borderRadius: BorderRadius.circular(4),
                ),
              ),
            ),
          ),
        ),
      ],
    );
  }
}

// ---------------------------------------------------------------------------
// 4. WaterfallBridge
// ---------------------------------------------------------------------------

class BridgeStep {
  const BridgeStep(this.label, this.delta, {this.isTotal = false});

  final String label;
  final double delta;
  final bool isTotal;
}

/// "Where the money went between last month and this one." Floating bars on a
/// running total; totals are anchored to zero and painted brand.
class WaterfallBridge extends StatelessWidget {
  const WaterfallBridge({super.key, required this.steps, this.height = 190});

  final List<BridgeStep> steps;
  final double height;

  @override
  Widget build(BuildContext context) {
    if (steps.isEmpty) return SizedBox(height: height);
    return SizedBox(
      height: height,
      width: double.infinity,
      child: CustomPaint(painter: _BridgePainter(steps)),
    );
  }
}

class _BridgePainter extends CustomPainter {
  _BridgePainter(this.steps);

  final List<BridgeStep> steps;

  @override
  void paint(Canvas canvas, Size size) {
    final n = steps.length;
    if (n == 0 || size.width <= 0 || size.height <= 0) return;

    const valueH = 17.0;
    const labelH = 26.0;
    final chartTop = valueH;
    final chartBottom = math.max(chartTop + 10, size.height - labelH);

    // resolve every step into a (from, to) span on the running total
    final from = List<double>.filled(n, 0);
    final to = List<double>.filled(n, 0);
    final shown = List<double>.filled(n, 0);
    var run = 0.0;
    for (var i = 0; i < n; i++) {
      final s = steps[i];
      if (s.isTotal) {
        final v = s.delta != 0 ? s.delta : run;
        from[i] = 0;
        to[i] = v;
        shown[i] = v;
        run = v;
      } else {
        from[i] = run;
        to[i] = run + s.delta;
        shown[i] = s.delta;
        run = to[i];
      }
    }

    var hi = math.max(0.0, math.max(_maxOf(from, 0), _maxOf(to, 0)));
    var lo = math.min(0.0, math.min(_minOf(from, 0), _minOf(to, 0)));
    if ((hi - lo).abs() < 1e-9) hi = 1;
    final pad = (hi - lo) * 0.10;
    hi += pad;
    lo -= pad;

    double y(double v) =>
        chartBottom - ((v - lo) / (hi - lo)) * (chartBottom - chartTop);

    final gap = n > 6 ? 8.0 : 12.0;
    final barW = math.max(6.0, (size.width - gap * (n - 1)) / n);

    final zeroY = y(0);
    canvas.drawLine(
      Offset(0, zeroY),
      Offset(size.width, zeroY),
      Paint()
        ..color = HqColors.line
        ..strokeWidth = 1,
    );

    final connector = Paint()
      ..color = HqColors.line2
      ..strokeWidth = 1.1;

    for (var i = 0; i < n; i++) {
      final s = steps[i];
      final left = i * (barW + gap);
      final yA = y(from[i]);
      final yB = y(to[i]);
      final top = math.min(yA, yB);
      final bottom = math.max(yA, yB);
      final rect =
          Rect.fromLTRB(left, top, left + barW, math.max(top + 2, bottom));

      final color = s.isTotal
          ? HqColors.brand
          : (shown[i] >= 0 ? VizColors.cat3 : HqColors.bad);

      canvas.drawRRect(
        RRect.fromRectAndRadius(rect, const Radius.circular(3)),
        Paint()..color = color,
      );

      // dashed connector, drawn at the level this bar hands over
      if (i < n - 1 && !steps[i + 1].isTotal) {
        final hand = y(to[i]);
        _dashLine(
          canvas,
          Offset(rect.right, hand),
          Offset(rect.right + gap, hand),
          connector,
          dash: 3,
          gap: 3,
        );
      }

      _text(
        canvas,
        s.isTotal ? tzsShort(shown[i]) : tzsShort(shown[i], sign: true),
        Offset(left + barW / 2, top - valueH + 3),
        HqText.tiny.copyWith(
          fontSize: 11,
          fontWeight: FontWeight.w700,
          color: s.isTotal ? HqColors.ink : color,
        ),
        align: TextAlign.center,
        maxW: barW + gap * 2,
      );

      _text(
        canvas,
        s.label,
        Offset(left + barW / 2, chartBottom + 7),
        HqText.tiny.copyWith(
          fontSize: 10.5,
          color: s.isTotal ? HqColors.ink2 : HqColors.ink3,
          fontWeight: s.isTotal ? FontWeight.w700 : FontWeight.w400,
        ),
        align: TextAlign.center,
        maxW: barW + gap,
      );
    }
  }

  @override
  bool shouldRepaint(covariant _BridgePainter o) => o.steps != steps;
}

// ---------------------------------------------------------------------------
// 5. AgeingBars
// ---------------------------------------------------------------------------

class AgeBucket {
  const AgeBucket(this.label, this.value, this.shade);

  final String label;
  final double value;

  /// Index into [VizColors.seq]; older money takes a darker shade.
  final int shade;
}

/// Ordered buckets on the sequential ramp - the darker it gets, the older the
/// money, so the shape of the debt reads before any figure does.
class AgeingBars extends StatelessWidget {
  const AgeingBars({super.key, required this.buckets, this.height = 132});

  final List<AgeBucket> buckets;
  final double height;

  @override
  Widget build(BuildContext context) {
    if (buckets.isEmpty) return SizedBox(height: height);
    return SizedBox(
      height: height,
      width: double.infinity,
      child: CustomPaint(painter: _AgeingPainter(buckets)),
    );
  }
}

class _AgeingPainter extends CustomPainter {
  _AgeingPainter(this.buckets);

  final List<AgeBucket> buckets;

  @override
  void paint(Canvas canvas, Size size) {
    final n = buckets.length;
    if (n == 0 || size.width <= 0 || size.height <= 0) return;

    const valueH = 17.0;
    const labelH = 18.0;
    final top = valueH;
    final baseline = math.max(top + 8, size.height - labelH);

    var hi = buckets.fold<double>(0, (a, b) => math.max(a, b.value));
    if (hi <= 0) hi = 1;
    hi *= 1.06;

    final gap = n > 5 ? 8.0 : 11.0;
    final barW = math.max(6.0, (size.width - gap * (n - 1)) / n);

    canvas.drawLine(
      Offset(0, baseline),
      Offset(size.width, baseline),
      Paint()
        ..color = HqColors.line
        ..strokeWidth = 1,
    );

    for (var i = 0; i < n; i++) {
      final b = buckets[i];
      final left = i * (barW + gap);
      final h = (math.max(0, b.value) / hi) * (baseline - top);
      final rect = Rect.fromLTRB(left, baseline - h, left + barW, baseline);
      final r = math.min(4.0, barW / 2);

      canvas.drawRRect(
        RRect.fromRectAndCorners(
          rect,
          topLeft: Radius.circular(r),
          topRight: Radius.circular(r),
        ),
        Paint()..color = VizColors.shade(b.shade),
      );

      _text(
        canvas,
        tzsShort(b.value),
        Offset(left + barW / 2, rect.top - valueH + 3),
        HqText.tiny.copyWith(
          fontSize: 11,
          fontWeight: FontWeight.w700,
          color: b.shade >= 4 ? HqColors.ink : HqColors.ink2,
        ),
        align: TextAlign.center,
        maxW: barW + gap,
      );

      _text(
        canvas,
        b.label,
        Offset(left + barW / 2, baseline + 4),
        HqText.tiny.copyWith(fontSize: 10.5, color: HqColors.ink3),
        align: TextAlign.center,
        maxW: barW + gap,
      );
    }
  }

  @override
  bool shouldRepaint(covariant _AgeingPainter o) => o.buckets != buckets;
}

// ---------------------------------------------------------------------------
// 6. DonutMeter
// ---------------------------------------------------------------------------

/// One fraction, said once: a ring plus a big centred figure. Never a pie.
class DonutMeter extends StatelessWidget {
  const DonutMeter({
    super.key,
    required this.fraction,
    required this.centre,
    required this.caption,
    this.color = HqColors.brand,
    this.size = 112,
  });

  final double fraction;
  final String centre;
  final String caption;
  final Color color;
  final double size;

  @override
  Widget build(BuildContext context) {
    final f = fraction.isNaN ? 0.0 : fraction.clamp(0.0, 1.0);
    return SizedBox(
      width: size,
      height: size,
      child: Stack(
        alignment: Alignment.center,
        children: [
          CustomPaint(size: Size.square(size), painter: _DonutPainter(f, color)),
          Padding(
            padding: EdgeInsets.symmetric(horizontal: size * 0.20),
            child: Column(
              mainAxisSize: MainAxisSize.min,
              children: [
                Text(
                  centre,
                  maxLines: 1,
                  overflow: TextOverflow.ellipsis,
                  style: HqText.title.copyWith(
                    fontSize: size * 0.24,
                    letterSpacing: -0.6,
                    height: 1.0,
                  ),
                ),
                const SizedBox(height: 3),
                Text(
                  caption,
                  textAlign: TextAlign.center,
                  maxLines: 2,
                  overflow: TextOverflow.ellipsis,
                  style: HqText.tiny.copyWith(fontSize: 10.5),
                ),
              ],
            ),
          ),
        ],
      ),
    );
  }
}

class _DonutPainter extends CustomPainter {
  _DonutPainter(this.fraction, this.color);

  final double fraction;
  final Color color;

  @override
  void paint(Canvas canvas, Size size) {
    if (size.width <= 0 || size.height <= 0) return;
    const stroke = 11.0;
    final rect =
        Rect.fromLTWH(0, 0, size.width, size.height).deflate(stroke / 2 + 1);
    if (rect.width <= 0 || rect.height <= 0) return;

    canvas.drawArc(
      rect,
      0,
      math.pi * 2,
      false,
      Paint()
        ..style = PaintingStyle.stroke
        ..strokeWidth = stroke
        ..color = HqColors.line,
    );

    if (fraction <= 0) return;
    canvas.drawArc(
      rect,
      -math.pi / 2,
      math.pi * 2 * fraction,
      false,
      Paint()
        ..style = PaintingStyle.stroke
        ..strokeWidth = stroke
        ..strokeCap = StrokeCap.round
        ..color = color,
    );
  }

  @override
  bool shouldRepaint(covariant _DonutPainter o) =>
      o.fraction != fraction || o.color != color;
}

// ---------------------------------------------------------------------------
// 7. ShareBar
// ---------------------------------------------------------------------------

class SharePart {
  const SharePart(this.label, this.value);

  final String label;
  final double value;
}

/// Part-to-whole in one bar, direct-labelled underneath. Categorical hues,
/// capped at four; beyond that it falls back to the sequential ramp.
class ShareBar extends StatelessWidget {
  const ShareBar({super.key, required this.parts});

  final List<SharePart> parts;

  static Color _colorAt(int i, int n) => n <= 4
      ? VizColors.cat(i)
      : VizColors.shade(VizColors.seq.length - 1 - i);

  @override
  Widget build(BuildContext context) {
    final visible = parts.where((p) => p.value > 0).toList();
    if (visible.isEmpty) return const SizedBox.shrink();
    final total = visible.fold<double>(0, (a, p) => a + p.value);
    if (total <= 0) return const SizedBox.shrink();

    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        ClipRRect(
          borderRadius: BorderRadius.circular(7),
          child: SizedBox(
            height: 14,
            child: Row(
              children: [
                for (var i = 0; i < visible.length; i++) ...[
                  if (i > 0) const SizedBox(width: 2),
                  Expanded(
                    flex: math.max(1, (visible[i].value / total * 1000).round()),
                    child: ColoredBox(color: _colorAt(i, visible.length)),
                  ),
                ],
              ],
            ),
          ),
        ),
        const SizedBox(height: 10),
        Wrap(
          spacing: 16,
          runSpacing: 7,
          children: [
            for (var i = 0; i < visible.length; i++)
              Row(
                mainAxisSize: MainAxisSize.min,
                children: [
                  Container(
                    width: 9,
                    height: 9,
                    decoration: BoxDecoration(
                      color: _colorAt(i, visible.length),
                      borderRadius: BorderRadius.circular(2.5),
                    ),
                  ),
                  const SizedBox(width: 6),
                  Text(
                    visible[i].label,
                    style: HqText.tiny.copyWith(color: HqColors.ink2),
                  ),
                  const SizedBox(width: 5),
                  Text(
                    '${(visible[i].value / total * 100).toStringAsFixed(0)}%',
                    style: HqText.tiny.copyWith(
                      color: HqColors.ink,
                      fontWeight: FontWeight.w700,
                    ),
                  ),
                ],
              ),
          ],
        ),
      ],
    );
  }
}

// ---------------------------------------------------------------------------
// 8. ForecastLine
// ---------------------------------------------------------------------------

/// Actual solid, forecast dashed, the uncertainty widening as it runs out -
/// and a red dashed floor, because the owner's real question is "do we go
/// under, and when".
class ForecastLine extends StatelessWidget {
  const ForecastLine({
    super.key,
    required this.actual,
    required this.forecast,
    this.floor,
    this.height = 168,
  });

  final List<double> actual;
  final List<double> forecast;
  final double? floor;
  final double height;

  @override
  Widget build(BuildContext context) {
    if (actual.isEmpty) return SizedBox(height: height);
    return SizedBox(
      height: height,
      width: double.infinity,
      child: CustomPaint(painter: _ForecastPainter(actual, forecast, floor)),
    );
  }
}

class _ForecastPainter extends CustomPainter {
  _ForecastPainter(this.actual, this.forecast, this.floor);

  final List<double> actual;
  final List<double> forecast;
  final double? floor;

  @override
  void paint(Canvas canvas, Size size) {
    final a = actual.length;
    final f = forecast.length;
    if (a == 0 || size.width <= 0 || size.height <= 0) return;

    final all = <double>[...actual, ...forecast, if (floor != null) floor!];
    var lo = _minOf(all, all.first);
    var hi = _maxOf(all, all.first);
    if ((hi - lo).abs() < 1e-9) {
      hi = hi + 1;
      lo = lo - 1;
    }
    final range = hi - lo;
    lo -= range * 0.14;
    hi += range * 0.14;

    const rightPad = 8.0;
    const topPad = 6.0;
    const botPad = 6.0;
    final w = math.max(1.0, size.width - rightPad);
    final plotH = math.max(1.0, size.height - topPad - botPad);
    final total = a + f;
    final steps = total > 1 ? total - 1 : 1;

    double x(int i) => i / steps * w;
    double y(double v) => size.height - botPad - ((v - lo) / (hi - lo)) * plotH;

    final actualPts = <Offset>[
      for (var i = 0; i < a; i++) Offset(x(i), y(actual[i])),
    ];

    // the forecast starts at the last actual point, so the two read as one story
    final fcPts = <Offset>[
      actualPts.last,
      for (var i = 0; i < f; i++) Offset(x(a + i), y(forecast[i])),
    ];

    // widening confidence band around the forecast
    if (f > 0) {
      final band = range * 0.05;
      final upper = <Offset>[];
      final lower = <Offset>[];
      for (var i = 0; i < fcPts.length; i++) {
        final grow = band * i * 0.9 / (hi - lo) * plotH;
        upper.add(Offset(fcPts[i].dx, fcPts[i].dy - grow));
        lower.add(Offset(fcPts[i].dx, fcPts[i].dy + grow));
      }
      final bandPath = Path()..moveTo(upper.first.dx, upper.first.dy);
      for (final p in upper.skip(1)) {
        bandPath.lineTo(p.dx, p.dy);
      }
      for (final p in lower.reversed) {
        bandPath.lineTo(p.dx, p.dy);
      }
      bandPath.close();
      canvas.drawPath(
        bandPath,
        Paint()..color = HqColors.brand.withValues(alpha: 0.10),
      );
    }

    // actual - solid
    if (actualPts.length >= 2) {
      canvas.drawPath(
        _smoothPath(actualPts),
        Paint()
          ..style = PaintingStyle.stroke
          ..strokeWidth = 2.4
          ..strokeCap = StrokeCap.round
          ..strokeJoin = StrokeJoin.round
          ..color = HqColors.brand,
      );
    }
    canvas.drawCircle(actualPts.last, 3.6, Paint()..color = HqColors.brand);
    canvas.drawCircle(actualPts.last, 1.5, Paint()..color = Colors.white);

    // forecast - dashed, direct-labelled
    if (fcPts.length >= 2) {
      _dashPath(
        canvas,
        _smoothPath(fcPts),
        Paint()
          ..style = PaintingStyle.stroke
          ..strokeWidth = 2.2
          ..strokeCap = StrokeCap.round
          ..color = HqColors.brand.withValues(alpha: 0.75),
      );
      _text(
        canvas,
        'forecast',
        Offset(fcPts.last.dx + rightPad, fcPts.last.dy - 19),
        HqText.tiny.copyWith(
          fontSize: 10.5,
          fontWeight: FontWeight.w700,
          color: HqColors.brand,
        ),
        align: TextAlign.right,
        maxW: 90,
      );
    }

    // the floor - red, because going under it is bad for the business
    if (floor != null) {
      final fy = y(floor!);
      _dashLine(
        canvas,
        Offset(0, fy),
        Offset(size.width, fy),
        Paint()
          ..color = HqColors.bad.withValues(alpha: 0.85)
          ..strokeWidth = 1.3,
      );
      _text(
        canvas,
        'your floor',
        Offset(2, fy + 3),
        HqText.tiny.copyWith(
          fontSize: 10.5,
          fontWeight: FontWeight.w700,
          color: HqColors.bad,
        ),
        maxW: 120,
      );
    }
  }

  @override
  bool shouldRepaint(covariant _ForecastPainter o) =>
      o.actual != actual || o.forecast != forecast || o.floor != floor;
}
