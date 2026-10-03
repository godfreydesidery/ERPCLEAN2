import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  ElementRef,
  afterNextRender,
  computed,
  inject,
  input,
  signal,
} from '@angular/core';
import { formatAmount, formatCompact, monotonePath, niceTicks } from './chart-math';

/** One line on a trend chart. `values[i]` belongs to `labels[i]`. */
export interface TrendSeries {
  key: string;
  name: string;
  color: string;
  values: number[];
  /** Draw a 10% wash under the line (the lead series only — two washes muddy each other). */
  area?: boolean;
}

/**
 * Smooth multi-series trend chart, hand-built in SVG (ADR-0064): monotone curves (no overshoot),
 * one y-axis with round ticks, a zero baseline, hairline grid, a crosshair that snaps to the nearest
 * period with one tooltip listing every series, and arrow-key reading for keyboard users. A "Table"
 * toggle shows the same numbers as a table, so no value is reachable only by hovering.
 *
 * All series share one unit (the inputs are one currency); two measures of different scale belong
 * in two charts, never on a second axis.
 */
@Component({
  selector: 'app-trend-chart',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="tc">
      <div class="tc__bar">
        @if (series().length > 1) {
          <ul class="tc__legend" aria-label="Legend">
            @for (s of series(); track s.key) {
              <li><span class="tc__key" [style.background]="s.color" aria-hidden="true"></span>{{ s.name }}</li>
            }
          </ul>
        }
        <button type="button" class="tc__toggle" (click)="showTable.set(!showTable())"
                [attr.aria-pressed]="showTable()">
          <i class="bi" [class.bi-table]="!showTable()" [class.bi-graph-up]="showTable()" aria-hidden="true"></i>
          {{ showTable() ? 'Show chart' : 'Show table' }}
        </button>
      </div>

      @if (showTable()) {
        <div class="erp-table-wrap">
          <table class="erp-table">
            <caption class="visually-hidden">{{ label() }}</caption>
            <thead>
              <tr>
                <th scope="col">Period</th>
                @for (s of series(); track s.key) {
                  <th scope="col" class="num">{{ s.name }}{{ unit() ? ' (' + unit() + ')' : '' }}</th>
                }
              </tr>
            </thead>
            <tbody>
              @for (l of labels(); track $index; let i = $index) {
                <tr>
                  <th scope="row" class="small fw-normal">{{ l }}</th>
                  @for (s of series(); track s.key) {
                    <td class="num mono small">{{ amount(s.values[i]) }}</td>
                  }
                </tr>
              }
            </tbody>
          </table>
        </div>
      } @else {
        <div class="tc__plot" tabindex="0" role="group"
             [attr.aria-label]="label() + '. Use the left and right arrow keys to read each period.'"
             (keydown)="onKey($event)" (pointerleave)="active.set(null)" (blur)="active.set(null)">
          <svg [attr.width]="width()" [attr.height]="height()" aria-hidden="true"
               (pointermove)="onPointer($event)">
            @for (t of geo().ticks; track t) {
              <line [attr.x1]="geo().left" [attr.x2]="geo().right" [attr.y1]="geo().y(t)" [attr.y2]="geo().y(t)"
                    [class.tc__grid]="t !== 0" [class.tc__zero]="t === 0" />
              <text [attr.x]="geo().left - 8" [attr.y]="geo().y(t) + 4" text-anchor="end" class="tc__tick">{{ compact(t) }}</text>
            }
            @for (i of geo().xTicks; track i) {
              <text [attr.x]="geo().x(i)" [attr.y]="height() - 6" text-anchor="middle" class="tc__tick">{{ labels()[i] }}</text>
            }
            @for (s of geo().lines; track s.key) {
              @if (s.area) {
                <path [attr.d]="s.areaD" [attr.fill]="s.color" fill-opacity="0.1" stroke="none" />
              }
            }
            @for (s of geo().lines; track s.key) {
              <path [attr.d]="s.d" fill="none" [attr.stroke]="s.color" stroke-width="2"
                    stroke-linejoin="round" stroke-linecap="round" />
            }
            @if (active(); as a) {
              <line [attr.x1]="geo().x(a.i)" [attr.x2]="geo().x(a.i)" [attr.y1]="geo().top" [attr.y2]="geo().bottom" class="tc__cross" />
            }
            @for (s of geo().lines; track s.key) {
              @if (activeIndex() !== null) {
                <circle [attr.cx]="s.pts[activeIndex()!].x" [attr.cy]="s.pts[activeIndex()!].y" r="4.5"
                        [attr.fill]="s.color" stroke="#fff" stroke-width="2" />
              } @else if (s.pts.length) {
                <circle [attr.cx]="s.pts[s.pts.length - 1].x" [attr.cy]="s.pts[s.pts.length - 1].y" r="4"
                        [attr.fill]="s.color" stroke="#fff" stroke-width="2" />
              }
            }
          </svg>
          @if (active(); as a) {
            <div class="tc__tip" [style.left.px]="geo().x(a.i)" [class.tc__tip--left]="geo().x(a.i) > width() / 2">
              <div class="tc__tip-title">{{ labels()[a.i] }}</div>
              @for (s of series(); track s.key) {
                <div class="tc__tip-row">
                  <span class="tc__tip-key" [style.background]="s.color"></span>
                  <strong>{{ unit() }} {{ amount(s.values[a.i]) }}</strong>
                  <span class="tc__tip-name">{{ s.name }}</span>
                </div>
              }
            </div>
          }
        </div>
        <output class="visually-hidden" aria-live="polite">{{ liveText() }}</output>
      }
    </div>
  `,
  styles: `
    :host { display: block; }
    .tc__bar { display: flex; align-items: center; gap: 1rem; margin-bottom: 0.5rem; min-height: 1.75rem; }
    .tc__legend { display: flex; flex-wrap: wrap; gap: 0.25rem 1rem; list-style: none; margin: 0; padding: 0;
      font-size: 0.8rem; color: var(--erp-text-2); }
    .tc__legend li { display: inline-flex; align-items: center; gap: 0.4rem; }
    .tc__key { width: 14px; height: 2px; border-radius: 1px; display: inline-block; }
    .tc__toggle { margin-left: auto; border: 0; background: none; padding: 0.15rem 0.35rem; font-size: 0.78rem;
      color: var(--erp-text-2); border-radius: var(--erp-radius-sm); display: inline-flex; align-items: center; gap: 0.35rem; }
    .tc__toggle:hover { background: var(--erp-neutral-bg); color: var(--erp-text); }
    .tc__toggle:focus-visible, .tc__plot:focus-visible { outline: 2px solid var(--erp-primary); outline-offset: 2px; }
    .tc__plot { position: relative; border-radius: var(--erp-radius-sm); touch-action: pan-y; }
    svg { display: block; overflow: visible; }
    .tc__grid { stroke: #edf0f4; stroke-width: 1; shape-rendering: crispEdges; }
    .tc__zero { stroke: #cdd4df; stroke-width: 1; shape-rendering: crispEdges; }
    .tc__tick { fill: var(--erp-text-3); font-size: 11px; font-variant-numeric: tabular-nums; }
    .tc__cross { stroke: #94a3b8; stroke-width: 1; shape-rendering: crispEdges; }
    .tc__tip { position: absolute; top: 4px; transform: translateX(12px); pointer-events: none; z-index: 2;
      background: var(--erp-card); border: 1px solid var(--erp-border); border-radius: var(--erp-radius-sm);
      box-shadow: var(--erp-shadow-md); padding: 0.45rem 0.6rem; font-size: 0.78rem; white-space: nowrap; }
    .tc__tip--left { transform: translateX(calc(-100% - 12px)); }
    .tc__tip-title { color: var(--erp-text-3); margin-bottom: 0.2rem; }
    .tc__tip-row { display: flex; align-items: center; gap: 0.45rem; }
    .tc__tip-row strong { color: var(--erp-text); font-variant-numeric: tabular-nums; }
    .tc__tip-name { color: var(--erp-text-2); }
    .tc__tip-key { width: 12px; height: 2px; border-radius: 1px; display: inline-block; }
  `,
})
export class TrendChartComponent {
  /** Period labels along the x-axis, oldest first. */
  readonly labels = input.required<string[]>();
  readonly series = input.required<TrendSeries[]>();
  /** Accessible name for the plot and caption for the table view. */
  readonly label = input.required<string>();
  /** Unit shown in the tooltip and table headings, e.g. the currency code. */
  readonly unit = input<string>('');
  readonly height = input<number>(260);

  readonly width = signal(640);
  readonly showTable = signal(false);
  readonly active = signal<{ i: number } | null>(null);
  readonly activeIndex = computed(() => this.active()?.i ?? null);

  private readonly host = inject(ElementRef<HTMLElement>);

  constructor() {
    const destroyRef = inject(DestroyRef);
    afterNextRender(() => {
      const el = this.host.nativeElement as HTMLElement;
      const measure = (w: number) => { if (w > 0) this.width.set(Math.max(280, Math.round(w))); };
      measure(el.clientWidth);
      if (typeof ResizeObserver === 'undefined') return;
      const ro = new ResizeObserver((entries) => measure(entries[0]?.contentRect.width ?? 0));
      ro.observe(el);
      destroyRef.onDestroy(() => ro.disconnect());
    });
  }

  readonly geo = computed(() => {
    const w = this.width();
    const h = this.height();
    const n = this.labels().length;
    const all = this.series().flatMap((s) => s.values).filter((v) => Number.isFinite(v));
    const ticks = niceTicks(all.length ? Math.min(...all) : 0, all.length ? Math.max(...all) : 0, 5);
    const left = Math.max(...ticks.map((t) => formatCompact(t).length)) * 7 + 14;
    const right = w - 12;
    const top = 10;
    const bottom = h - 24;
    const yMin = ticks[0];
    const yMax = ticks[ticks.length - 1];
    const y = (v: number) => bottom - ((v - yMin) / (yMax - yMin || 1)) * (bottom - top);
    const step = n > 1 ? (right - left) / (n - 1) : 0;
    const x = (i: number) => (n > 1 ? left + i * step : (left + right) / 2);
    const base = y(Math.min(Math.max(0, yMin), yMax));
    const lines = this.series().map((s) => {
      const pts = s.values.slice(0, n).map((v, i) => ({ x: x(i), y: y(Number.isFinite(v) ? v : 0) }));
      const d = monotonePath(pts);
      const areaD = pts.length > 1
        ? `${d}L${pts[pts.length - 1].x},${base}L${pts[0].x},${base}Z`
        : '';
      return { ...s, pts, d, areaD };
    });
    // Thin the x labels so they never collide: keep every k-th, always keep the last.
    const every = step > 0 ? Math.max(1, Math.ceil(52 / step)) : 1;
    const xTicks: number[] = [];
    for (let i = 0; i < n; i++) {
      if (i % every === 0 || i === n - 1) xTicks.push(i);
    }
    if (xTicks.length > 1 && xTicks[xTicks.length - 1] - xTicks[xTicks.length - 2] < every) {
      xTicks.splice(xTicks.length - 2, 1);
    }
    return { left, right, top, bottom, y, x, step, ticks, lines, xTicks };
  });

  readonly liveText = computed(() => {
    const a = this.active();
    if (!a) return '';
    const parts = this.series().map((s) => `${s.name} ${this.unit()} ${formatAmount(s.values[a.i])}`);
    return `${this.labels()[a.i]}: ${parts.join(', ')}`;
  });

  compact(v: number): string {
    return formatCompact(v);
  }

  amount(v: number | undefined): string {
    return formatAmount(v ?? NaN);
  }

  onPointer(e: PointerEvent): void {
    const n = this.labels().length;
    if (n === 0) return;
    const rect = (e.currentTarget as SVGElement).getBoundingClientRect();
    const g = this.geo();
    const px = e.clientX - rect.left;
    const i = g.step > 0 ? Math.round((px - g.left) / g.step) : 0;
    this.active.set({ i: Math.min(n - 1, Math.max(0, i)) });
  }

  onKey(e: KeyboardEvent): void {
    const n = this.labels().length;
    if (n === 0) return;
    const cur = this.active()?.i;
    let next: number | null = null;
    if (e.key === 'ArrowRight') next = cur === undefined ? 0 : Math.min(n - 1, cur + 1);
    else if (e.key === 'ArrowLeft') next = cur === undefined ? n - 1 : Math.max(0, cur - 1);
    else if (e.key === 'Home') next = 0;
    else if (e.key === 'End') next = n - 1;
    else if (e.key === 'Escape') { this.active.set(null); return; }
    if (next === null) return;
    e.preventDefault();
    this.active.set({ i: next });
  }
}
