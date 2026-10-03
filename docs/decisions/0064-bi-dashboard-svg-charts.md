# 0064 — BI dashboard charts: in-house SVG components, no chart library

- **Status:** Accepted (2026-10-03, owner request: "modern charts, line curves, bars")
- **Amends:** ADR-0037 D-8 ("chart-free v1; chart.js deferred to its own v2 ADR"). This is that v2 ADR.
- **Constraints set by the owner:** no migration, no change to any API response.

## Context

ADR-0037 shipped the Analytics Dashboard chart-free: stat cards, CSS bars and tables. The owner
found it inelegant and asked for real charts — smooth line curves and bars. D-8 expected the
upgrade to be `ng2-charts + chart.js`, a template-only swap.

## Decision

Draw the charts as small standalone Angular components in `web/src/app/shared/charts/`, written
directly in SVG/HTML — **no charting dependency**:

| Component | Used for |
|---|---|
| `TrendChartComponent` | Revenue and net profit, last 12 fiscal periods, on one chart: monotone (Fritsch–Carlson) curves that never overshoot the data, one y-axis with round ticks and a zero baseline, a crosshair snapping to the nearest period with one tooltip listing every series, arrow-key reading with a live region, and a **Show table** toggle. |
| `BarListComponent` | Pipeline value by stage, Sales by Branch: label and value as real text, thin bar scaled to the largest row. |
| `SparklineComponent` | 12-period trend inside the Revenue and Net Profit tiles (decorative; the tile states the value). |
| `chart-math.ts` | Formatting, nice ticks, the monotone path, the validated palette. |

Colours: slot 1 is the brand blue `#2563eb`, slot 2 orange `#eb6834`, validated against the
white card surface (adjacent CVD ΔE 29.9, normal-vision ΔE 38.5, both ≥ 3:1). Colour follows the
series, never its rank; status colours are never used for a series. All charts read only what the
`DashboardDto` already carries.

## Why not chart.js / ng2-charts

- **Accessibility is the CI gate.** `<canvas>` charts are opaque to axe and screen readers and need
  a hand-built text alternative anyway. SVG and HTML lists are inspectable, and the axe specs scan
  them as they render.
- **The charts needed are few and simple** — one multi-line trend, bar lists and sparklines. About
  400 lines of owned code against a permanent ~200 KB dependency with its own release cycle and CVE stream (the
  npm-audit gate already goes red on upstream advisories).
- **Look and behaviour stay in the design system** (tokens, type, spacing) instead of being themed
  through a library's options object.

## Consequences

- New chart types are written here, not configured. If the BI surface grows past a handful of
  forms (stacked areas, scatter, maps), revisit this ADR and adopt a library then.
- The two trend panels became one ("Revenue and net profit"): same currency, one axis — never a
  second y-axis.
- ADR-0037's D-8 "Reversibility" note still holds: the backend is untouched, so a library could
  replace these components later without server work.
