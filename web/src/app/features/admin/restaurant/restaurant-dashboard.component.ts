import { HttpErrorResponse } from '@angular/common/http';
import { Component, OnInit, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { SessionStore } from '../../../core/auth/session.store';
import { BarListComponent, BarListRow } from '../../../shared/charts/bar-list.component';
import { CHART_COLORS } from '../../../shared/charts/chart-math';
import { TrendChartComponent, TrendSeries } from '../../../shared/charts/trend-chart.component';
import { localIsoDate, todayLocal } from '../../../shared/date.util';
import { UidOption, UidPickerComponent } from '../../../shared/uid-picker/uid-picker.component';
import { ExportFormat } from '../reporting/models/reporting.model';
import { ReportFilterOptionsService } from '../reporting/report-filter-options.service';
import { downloadBlob } from '../reporting/reporting.utils';
import {
  DashboardKpisDto,
  RestaurantDashboardDto,
  RestaurantDashboardFilter,
  tenderLabel,
} from './models/restaurant-report.model';
import { RestaurantReportService } from './restaurant-report.service';

type LoadState = 'idle' | 'loading' | 'error' | 'forbidden';
type QuickRange = 'today' | 'yesterday' | 'week' | 'month';

/** One KPI tile: its value now, and how it moved against the same number of days before. */
export interface KpiTile {
  key: keyof DashboardKpisDto;
  label: string;
  value: string;
  /** Percentage change vs the previous period; null when the previous period had none. */
  change: number | null;
  /** Up is good for sales, bad for refunds and voids — drives the colour, not the arrow. */
  goodWhenUp: boolean;
}

const TILES: { key: keyof DashboardKpisDto; label: string; money: boolean; goodWhenUp: boolean }[] = [
  { key: 'sales', label: 'Sales', money: true, goodWhenUp: true },
  { key: 'receipts', label: 'Receipts', money: false, goodWhenUp: true },
  { key: 'averageReceipt', label: 'Average receipt', money: true, goodWhenUp: true },
  { key: 'orders', label: 'Table orders paid', money: false, goodWhenUp: true },
  { key: 'guests', label: 'Guests', money: false, goodWhenUp: true },
  { key: 'spendPerGuest', label: 'Spend per guest', money: true, goodWhenUp: true },
  { key: 'discounts', label: 'Discounts', money: true, goodWhenUp: false },
  { key: 'complimentary', label: 'Complimentary', money: true, goodWhenUp: false },
  { key: 'refunds', label: 'Refunds', money: true, goodWhenUp: false },
  { key: 'voidedReceipts', label: 'Receipts voided', money: false, goodWhenUp: false },
  { key: 'cancelledOrders', label: 'Orders cancelled', money: false, goodWhenUp: false },
  { key: 'voidedItems', label: 'Items voided', money: false, goodWhenUp: false },
];

const MONTHS = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];

/**
 * The restaurant dashboard (2026-11-03): how the restaurant is doing over a date range — sales,
 * guests, discounts, complimentary and voids against the period before, what is open right now,
 * and where the sales came from (days, hours, weekdays, departments, items, waiters, payments).
 * The same figures download as the Flash Report. Gated SALES.INVOICE.VIEW like the reports;
 * the download needs REPORT.EXPORT.
 */
@Component({
  selector: 'app-restaurant-dashboard',
  imports: [FormsModule, RouterLink, UidPickerComponent, TrendChartComponent, BarListComponent],
  templateUrl: './restaurant-dashboard.component.html',
})
export class RestaurantDashboardComponent implements OnInit {
  private readonly reports = inject(RestaurantReportService);
  private readonly filterOptions = inject(ReportFilterOptionsService);
  protected readonly session = inject(SessionStore);

  readonly quickRanges: { key: QuickRange; label: string }[] = [
    { key: 'today', label: 'Today' },
    { key: 'yesterday', label: 'Yesterday' },
    { key: 'week', label: 'Last 7 days' },
    { key: 'month', label: 'This month' },
  ];

  readonly fromDate = signal(todayLocal());
  readonly toDate = signal(todayLocal());
  readonly branchUid = signal('');
  readonly branchOptions = signal<UidOption[]>([]);
  readonly data = signal<RestaurantDashboardDto | null>(null);
  readonly state = signal<LoadState>('idle');
  readonly exporting = signal(false);

  readonly canView = computed(() => this.session.hasPermission('SALES.INVOICE.VIEW'));
  readonly canExport = computed(() => this.session.hasPermission('REPORT.EXPORT'));

  readonly tiles = computed<KpiTile[]>(() => {
    const d = this.data();
    if (!d) return [];
    return TILES.map((t) => {
      const now = +(d.current[t.key] ?? 0);
      const before = +(d.previous?.[t.key] ?? 0);
      return {
        key: t.key,
        label: t.label,
        value: t.money ? this.fmtMoney(now) : this.fmtCount(now),
        change: before ? ((now - before) / Math.abs(before)) * 100 : null,
        goodWhenUp: t.goodWhenUp,
      };
    });
  });

  readonly trend = computed<{ labels: string[]; series: TrendSeries[] } | null>(() => {
    const d = this.data();
    if (!d || d.days.length < 2) return null;
    return {
      labels: d.days.map((x) => this.shortDate(x.date)),
      series: [
        { key: 'sales', name: 'Sales', color: CHART_COLORS.series1, values: d.days.map((x) => +x.sales), area: true },
      ],
    };
  });

  /** Only the hours that sold something, so a restaurant open 07–23 is not padded with night. */
  readonly hourRows = computed<BarListRow[]>(() =>
    (this.data()?.hours ?? [])
      .filter((h) => +h.receipts > 0)
      .map((h) => ({
        key: String(h.hour),
        label: `${this.pad(h.hour)}:00–${this.pad((h.hour + 1) % 24)}:00`,
        sublabel: `${this.fmtCount(h.receipts)} receipts`,
        value: +h.sales,
      })),
  );

  readonly weekdayRows = computed<BarListRow[]>(() =>
    (this.data()?.weekdays ?? []).map((w) => ({
      key: String(w.day),
      label: w.label,
      sublabel: `average per day · ${this.fmtCount(w.days)} ${+w.days === 1 ? 'day' : 'days'}`,
      value: +w.averagePerDay,
    })),
  );

  readonly departmentRows = computed<BarListRow[]>(() =>
    (this.data()?.departments ?? []).map((x) => ({
      key: x.name,
      label: x.name,
      sublabel: `${this.fmtCount(x.quantity)} sold`,
      value: +x.sales,
    })),
  );

  readonly itemRows = computed<BarListRow[]>(() =>
    (this.data()?.topItems ?? []).map((x, i) => ({
      key: `${x.code ?? ''}-${i}`,
      label: x.name,
      sublabel: `${this.fmtCount(x.quantity)}${x.unit ? ' ' + x.unit : ''}`,
      value: +x.sales,
    })),
  );

  readonly waiterRows = computed<BarListRow[]>(() =>
    (this.data()?.waiters ?? []).map((w) => ({
      key: w.waiterUid ?? 'counter',
      label: w.waiterUid === null ? 'Counter sales (no table order)' : w.waiterName,
      sublabel: `${this.fmtCount(w.bills)} bills`,
      value: +w.amount,
    })),
  );

  readonly tenderRows = computed<BarListRow[]>(() =>
    (this.data()?.tenders ?? []).map((t) => ({
      key: t.tenderType,
      label: tenderLabel(t.tenderType),
      sublabel: `${this.fmtCount(t.payments)} payments`,
      value: +t.amount,
    })),
  );

  ngOnInit(): void {
    if (!this.canView()) return;
    this.filterOptions.branchOptions().subscribe({
      next: (opts) => this.branchOptions.set(opts),
      error: () => undefined,
    });
    this.load();
  }

  setRange(range: QuickRange): void {
    const today = this.parse(todayLocal());
    let from = today;
    let to = today;
    if (range === 'yesterday') {
      from = this.addDays(today, -1);
      to = from;
    } else if (range === 'week') {
      from = this.addDays(today, -6);
    } else if (range === 'month') {
      from = new Date(today.getFullYear(), today.getMonth(), 1);
    }
    this.fromDate.set(localIsoDate(from));
    this.toDate.set(localIsoDate(to));
    this.load();
  }

  private filter(): RestaurantDashboardFilter | null {
    const from = String(this.fromDate() ?? '').trim();
    const to = String(this.toDate() ?? '').trim();
    if (!from || !to) return null;
    return { fromDate: from, toDate: to, branchUid: this.branchUid() || null };
  }

  load(): void {
    const f = this.filter();
    if (!f) return;
    this.state.set('loading');
    this.reports.dashboard(f).subscribe({
      next: (d) => {
        this.data.set(d);
        this.state.set('idle');
      },
      error: (err: unknown) =>
        this.state.set(
          err instanceof HttpErrorResponse && err.status === 403 ? 'forbidden' : 'error',
        ),
    });
  }

  exportFlash(format: ExportFormat): void {
    const f = this.filter();
    if (!f || this.exporting()) return;
    this.exporting.set(true);
    this.reports.exportFlashReport(f, format).subscribe({
      next: (blob) => {
        downloadBlob(blob, `restaurant-flash-report_${f.fromDate}_${f.toDate}.${format.toLowerCase()}`);
        this.exporting.set(false);
      },
      error: () => this.exporting.set(false),
    });
  }

  // ── Display helpers ─────────────────────────────────────────────────────────

  /** "+12%" / "−8%"; the sign is in the text so colour is never the only cue. */
  fmtChange(change: number): string {
    const rounded = Math.round(change);
    if (rounded === 0) return '0%';
    return `${rounded > 0 ? '+' : '−'}${Math.abs(rounded).toLocaleString('en-US')}%`;
  }

  /** Green when the move is good for the business, red when it is not. */
  changeClass(t: KpiTile): string {
    if (t.change === null || Math.round(t.change) === 0) return '';
    const good = t.change > 0 === t.goodWhenUp;
    return good ? 'erp-kpi__delta--up' : 'erp-kpi__delta--down';
  }

  fmtMoney(v: number | string | null | undefined): string {
    const n = +(v ?? 0);
    return Number.isFinite(n)
      ? n.toLocaleString('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2 })
      : '0.00';
  }

  fmtCount(v: number | string | null | undefined): string {
    const n = +(v ?? 0);
    return Number.isFinite(n) ? n.toLocaleString('en-US', { maximumFractionDigits: 2 }) : '0';
  }

  private shortDate(iso: string): string {
    const [, m, d] = iso.split('-');
    return `${+d} ${MONTHS[+m - 1] ?? ''}`;
  }

  private pad(n: number): string {
    return String(n).padStart(2, '0');
  }

  private parse(iso: string): Date {
    const [y, m, d] = iso.split('-').map(Number);
    return new Date(y, m - 1, d);
  }

  private addDays(d: Date, days: number): Date {
    const out = new Date(d);
    out.setDate(out.getDate() + days);
    return out;
  }
}
