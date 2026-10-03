/**
 * Pure helpers behind the in-house SVG charts (ADR-0064): number formatting, "nice" axis ticks and
 * a monotone curve. No DOM, no Angular — unit-testable on their own.
 */

/**
 * Chart palette, light surface. Validated with the dataviz checks against #ffffff: slot 1 is the
 * brand blue, slot 2 the orange partner (adjacent CVD ΔE 29.9, normal-vision ΔE 38.5, both ≥ 3:1).
 * Colour follows the SERIES, never its rank, and status colours are never used for a series.
 */
export const CHART_COLORS = {
  series1: '#2563eb',
  series2: '#eb6834',
  /** De-emphasis ink for sparklines and context marks. */
  muted: '#94a3b8',
} as const;

const COMPACT = new Intl.NumberFormat('en-US', { notation: 'compact', maximumFractionDigits: 1 });
const FULL = new Intl.NumberFormat('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2 });

/** 1,284 → "1.3K", 4,200,000 → "4.2M" — axis ticks and tight labels. */
export function formatCompact(v: number): string {
  return Number.isFinite(v) ? COMPACT.format(v) : '—';
}

/** Two decimals with thousands separators — tooltips and value labels. */
export function formatAmount(v: number): string {
  return Number.isFinite(v) ? FULL.format(v) : '—';
}

/** Coerces the API's BigDecimal (a JSON number, typed `string` on the web side) to a number. */
export function toNumber(v: number | string | null | undefined): number {
  const n = typeof v === 'number' ? v : Number(v ?? 0);
  return Number.isFinite(n) ? n : 0;
}

/**
 * Round tick values covering [min, max] — 0 / 50K / 100K, never 0 / 43,117 / 86,234. Always
 * includes zero when the range crosses or touches it, so bars and areas share one baseline.
 */
export function niceTicks(min: number, max: number, target = 5): number[] {
  let lo = Math.min(min, 0);
  let hi = Math.max(max, 0);
  if (lo === hi) hi = lo + 1;
  const raw = (hi - lo) / Math.max(target - 1, 1);
  const mag = Math.pow(10, Math.floor(Math.log10(raw)));
  const norm = raw / mag;
  const step = (norm <= 1 ? 1 : norm <= 2 ? 2 : norm <= 2.5 ? 2.5 : norm <= 5 ? 5 : 10) * mag;
  lo = Math.floor(lo / step) * step;
  hi = Math.ceil(hi / step) * step;
  const ticks: number[] = [];
  for (let t = lo; t <= hi + step / 2; t += step) {
    ticks.push(Math.abs(t) < step / 1e6 ? 0 : Number(t.toPrecision(12)));
  }
  return ticks;
}

export interface Pt {
  x: number;
  y: number;
}

/**
 * SVG path through the points as a monotone cubic (Fritsch–Carlson). The curve is smooth but never
 * overshoots the data — a plain Catmull-Rom would draw a dip below zero between two positive
 * months, which is a value that never happened.
 */
export function monotonePath(points: Pt[]): string {
  const n = points.length;
  if (n === 0) return '';
  if (n === 1) return `M${points[0].x},${points[0].y}`;
  if (n === 2) return `M${points[0].x},${points[0].y}L${points[1].x},${points[1].y}`;

  const dx: number[] = [];
  const slope: number[] = [];
  for (let i = 0; i < n - 1; i++) {
    dx[i] = points[i + 1].x - points[i].x;
    slope[i] = dx[i] === 0 ? 0 : (points[i + 1].y - points[i].y) / dx[i];
  }
  const m: number[] = new Array(n);
  m[0] = slope[0];
  m[n - 1] = slope[n - 2];
  for (let i = 1; i < n - 1; i++) {
    m[i] = slope[i - 1] * slope[i] <= 0 ? 0 : (slope[i - 1] + slope[i]) / 2;
  }
  for (let i = 0; i < n - 1; i++) {
    if (slope[i] === 0) {
      m[i] = 0;
      m[i + 1] = 0;
      continue;
    }
    const a = m[i] / slope[i];
    const b = m[i + 1] / slope[i];
    const h = a * a + b * b;
    if (h > 9) {
      const t = 3 / Math.sqrt(h);
      m[i] = t * a * slope[i];
      m[i + 1] = t * b * slope[i];
    }
  }

  let d = `M${r(points[0].x)},${r(points[0].y)}`;
  for (let i = 0; i < n - 1; i++) {
    const h = dx[i] / 3;
    d += `C${r(points[i].x + h)},${r(points[i].y + m[i] * h)} `
      + `${r(points[i + 1].x - h)},${r(points[i + 1].y - m[i + 1] * h)} `
      + `${r(points[i + 1].x)},${r(points[i + 1].y)}`;
  }
  return d;
}

function r(v: number): number {
  return Math.round(v * 100) / 100;
}
