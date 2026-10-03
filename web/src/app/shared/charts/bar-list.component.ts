import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';
import { CHART_COLORS, formatAmount } from './chart-math';

export interface BarListRow {
  key: string;
  label: string;
  /** Secondary text after the label, e.g. "34 invoices". */
  sublabel?: string;
  value: number;
}

/**
 * Horizontal bar list (ADR-0064): one row per category, label and value as real text and a thin
 * bar scaled to the largest row. Built as a list, not a picture, so screen readers read the label
 * and the value directly and nothing needs a separate table. One series = one colour for every
 * bar; the order is the caller's (ranked for branches, pipeline order for stages).
 */
@Component({
  selector: 'app-bar-list',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <ul class="bl" [attr.aria-label]="label()">
      @for (row of rows(); track row.key) {
        <li class="bl__row">
          <div class="bl__head">
            <span class="bl__label">{{ row.label }}</span>
            @if (row.sublabel) {
              <span class="bl__sub">{{ row.sublabel }}</span>
            }
            <span class="bl__value">{{ unit() }} {{ amount(row.value) }}</span>
          </div>
          <div class="bl__track" aria-hidden="true">
            <div class="bl__fill" [style.width.%]="pct(row.value)" [style.background]="color()"
                 [class.bl__fill--neg]="row.value < 0"></div>
          </div>
        </li>
      }
    </ul>
  `,
  styles: `
    :host { display: block; }
    .bl { list-style: none; margin: 0; padding: 0; display: flex; flex-direction: column; gap: 0.15rem; }
    .bl__row { padding: 0.4rem 0.5rem; border-radius: var(--erp-radius-sm); transition: background-color 0.15s ease; }
    .bl__row:hover { background: #f6f8fb; }
    .bl__head { display: flex; align-items: baseline; gap: 0.5rem; font-size: 0.85rem; margin-bottom: 0.35rem; }
    .bl__label { color: var(--erp-text); font-weight: 600; min-width: 0; overflow-wrap: anywhere; }
    .bl__sub { color: var(--erp-text-3); font-size: 0.78rem; white-space: nowrap; }
    .bl__value { margin-left: auto; color: var(--erp-text); font-variant-numeric: tabular-nums; white-space: nowrap; }
    .bl__track { height: 8px; background: #eef2f7; border-radius: 0 4px 4px 0; }
    .bl__fill { height: 100%; border-radius: 0 4px 4px 0; min-width: 2px; transition: width 0.4s ease; }
    .bl__fill--neg { opacity: 0.45; }
    @media (prefers-reduced-motion: reduce) { .bl__fill, .bl__row { transition: none; } }
  `,
})
export class BarListComponent {
  readonly rows = input.required<BarListRow[]>();
  /** Accessible name for the list. */
  readonly label = input.required<string>();
  /** Unit before each value, e.g. the currency code. */
  readonly unit = input<string>('');
  readonly color = input<string>(CHART_COLORS.series1);

  private readonly max = computed(() =>
    Math.max(...this.rows().map((r) => Math.abs(r.value)), 0),
  );

  pct(v: number): number {
    const max = this.max();
    return max > 0 ? Math.min(100, (Math.abs(v) / max) * 100) : 0;
  }

  amount(v: number): string {
    return formatAmount(v);
  }
}
