import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';
import { CHART_COLORS, monotonePath } from './chart-math';

/**
 * Small trend line for a stat tile (ADR-0064): the last N periods as a smooth line with a light
 * wash, a hairline at zero when the series crosses it, and a dot on the latest point. Decorative
 * (`aria-hidden`) — the tile states its value in text and the full trend has its own chart.
 */
@Component({
  selector: 'app-sparkline',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @if (geo(); as g) {
      <svg [attr.width]="width()" [attr.height]="height()" [attr.viewBox]="'0 0 ' + width() + ' ' + height()"
           aria-hidden="true" focusable="false">
        @if (g.zeroY !== null) {
          <line x1="0" [attr.x2]="width()" [attr.y1]="g.zeroY" [attr.y2]="g.zeroY" class="sp__zero" />
        }
        <path [attr.d]="g.area" [attr.fill]="color()" fill-opacity="0.1" stroke="none" />
        <path [attr.d]="g.line" fill="none" [attr.stroke]="color()" stroke-width="1.75"
              stroke-linejoin="round" stroke-linecap="round" />
        <circle [attr.cx]="g.last.x" [attr.cy]="g.last.y" r="3" [attr.fill]="color()" stroke="#fff" stroke-width="1.5" />
      </svg>
    }
  `,
  styles: `
    :host { display: inline-block; line-height: 0; }
    svg { display: block; overflow: visible; }
    .sp__zero { stroke: #cdd4df; stroke-width: 1; shape-rendering: crispEdges; }
  `,
})
export class SparklineComponent {
  readonly values = input.required<number[]>();
  readonly color = input<string>(CHART_COLORS.series1);
  readonly width = input<number>(112);
  readonly height = input<number>(32);

  readonly geo = computed(() => {
    const vals = this.values().filter((v) => Number.isFinite(v));
    if (vals.length < 2) return null;
    const w = this.width();
    const h = this.height();
    const pad = 3;
    const lo = Math.min(...vals, 0);
    const hi = Math.max(...vals, 0);
    const span = hi - lo || 1;
    const y = (v: number) => h - pad - ((v - lo) / span) * (h - pad * 2);
    const step = (w - pad * 2) / (vals.length - 1);
    const pts = vals.map((v, i) => ({ x: pad + i * step, y: y(v) }));
    const line = monotonePath(pts);
    const base = y(0);
    const area = `${line}L${pts[pts.length - 1].x},${base}L${pts[0].x},${base}Z`;
    return { line, area, last: pts[pts.length - 1], zeroY: lo < 0 && hi > 0 ? base : null };
  });
}
