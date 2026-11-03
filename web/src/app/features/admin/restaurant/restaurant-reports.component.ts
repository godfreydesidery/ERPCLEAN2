import { DatePipe } from '@angular/common';
import { HttpErrorResponse } from '@angular/common/http';
import { Component, OnInit, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute } from '@angular/router';
import { Observable } from 'rxjs';
import { SessionStore } from '../../../core/auth/session.store';
import { todayLocal } from '../../../shared/date.util';
import { UidOption, UidPickerComponent } from '../../../shared/uid-picker/uid-picker.component';
import { formatReportAddress, ReportCompanyHeaderDto } from '../models/report-company-header.model';
import { ExportFormat } from '../reporting/models/reporting.model';
import { ReportFilterOptionsService } from '../reporting/report-filter-options.service';
import { downloadBlob } from '../reporting/reporting.utils';
import {
  RestaurantBillsReportDto,
  RestaurantBillsSummaryDto,
  RestaurantDaypartDto,
  RestaurantPaymentsReportDto,
  RestaurantPendingBillsReportDto,
  RestaurantPendingSummaryDto,
  RestaurantReportFilter,
  RestaurantReportKind,
  WaiterItemsReportDto,
  WaiterSalesReportDto,
  tenderLabel,
} from './models/restaurant-report.model';
import { RestaurantReportService } from './restaurant-report.service';

type LoadState = 'idle' | 'loading' | 'error' | 'forbidden';

/** A tab on the page; the waiter tabs have a summary and a detail view. */
export type RestaurantReportTab = 'waiters' | 'bills' | 'pending' | 'daypart' | 'payments';
export type RestaurantReportView = 'summary' | 'detail';

const TAB_KINDS: Record<RestaurantReportTab, { summary: RestaurantReportKind; detail: RestaurantReportKind }> = {
  waiters: { summary: 'waiter-sales', detail: 'waiter-items' },
  bills: { summary: 'bills-summary', detail: 'bills' },
  pending: { summary: 'pending-summary', detail: 'pending-bills' },
  daypart: { summary: 'daypart', detail: 'daypart' },
  payments: { summary: 'payments', detail: 'payments' },
};

function tabOf(kind: RestaurantReportKind): RestaurantReportTab {
  for (const [tab, k] of Object.entries(TAB_KINDS) as [RestaurantReportTab, (typeof TAB_KINDS)[RestaurantReportTab]][]) {
    if (k.summary === kind || k.detail === kind) return tab;
  }
  return 'waiters';
}

const ALL_KINDS: readonly RestaurantReportKind[] = [
  'waiter-sales', 'waiter-items', 'bills', 'bills-summary', 'pending-bills', 'pending-summary',
  'daypart', 'payments',
];

/**
 * Restaurant reports (J COB 2026-10-30; summaries, hours and payments 2026-11-03): sales by
 * waiter, the bills paid, the bills still pending — each as a summary or in detail, for one
 * waiter or all — plus sales by hour and day of the week, and payments & completion. Gated
 * SALES.INVOICE.VIEW, like the Sales Report; export needs REPORT.EXPORT as well.
 */
@Component({
  selector: 'app-restaurant-reports',
  imports: [FormsModule, UidPickerComponent, DatePipe],
  templateUrl: './restaurant-reports.component.html',
})
export class RestaurantReportsComponent implements OnInit {
  private readonly reports = inject(RestaurantReportService);
  private readonly filterOptions = inject(ReportFilterOptionsService);
  private readonly route = inject(ActivatedRoute, { optional: true });
  protected readonly session = inject(SessionStore);

  readonly tabs: { tab: RestaurantReportTab; label: string }[] = [
    { tab: 'waiters', label: 'Sales by waiter' },
    { tab: 'bills', label: 'Bills paid' },
    { tab: 'pending', label: 'Pending bills' },
    { tab: 'daypart', label: 'Sales by hour & day' },
    { tab: 'payments', label: 'Payments & completion' },
  ];

  // ── Filters ─────────────────────────────────────────────────────────────────
  readonly kind = signal<RestaurantReportKind>('waiter-sales');
  readonly fromDate = signal(this.firstDayOfCurrentMonth());
  readonly toDate = signal(todayLocal());
  readonly waiterUid = signal('');
  readonly branchUid = signal('');
  readonly waiterOptions = signal<UidOption[]>([]);
  readonly branchOptions = signal<UidOption[]>([]);

  // ── Results (one per report, so switching never shows another's rows) ──────
  readonly sales = signal<WaiterSalesReportDto | null>(null);
  readonly waiterItems = signal<WaiterItemsReportDto | null>(null);
  readonly bills = signal<RestaurantBillsReportDto | null>(null);
  readonly billsSummary = signal<RestaurantBillsSummaryDto | null>(null);
  readonly pending = signal<RestaurantPendingBillsReportDto | null>(null);
  readonly pendingSummary = signal<RestaurantPendingSummaryDto | null>(null);
  readonly daypart = signal<RestaurantDaypartDto | null>(null);
  readonly payments = signal<RestaurantPaymentsReportDto | null>(null);
  readonly state = signal<LoadState>('idle');
  readonly exporting = signal(false);

  readonly canView = computed(() => this.session.hasPermission('SALES.INVOICE.VIEW'));
  readonly canExport = computed(() => this.session.hasPermission('REPORT.EXPORT'));
  /** The waiter list needs a restaurant permission; without it the filter is not offered. */
  readonly canFilterWaiter = computed(
    () =>
      this.session.hasPermission('RESTAURANT.ORDER.MANAGE') ||
      this.session.hasPermission('RESTAURANT.SETUP.MANAGE'),
  );
  readonly tab = computed(() => tabOf(this.kind()));
  readonly view = computed<RestaurantReportView>(() =>
    TAB_KINDS[this.tab()].detail === this.kind() && TAB_KINDS[this.tab()].summary !== this.kind()
      ? 'detail'
      : 'summary',
  );
  /** Hours and payments are one table each — no summary/detail choice. */
  readonly hasViews = computed(() => {
    const t = TAB_KINDS[this.tab()];
    return t.summary !== t.detail;
  });
  readonly needsDates = computed(
    () => this.kind() !== 'pending-bills' && this.kind() !== 'pending-summary',
  );
  /** Hours and payments cover the whole floor; the waiter filter does not apply. */
  readonly showWaiter = computed(
    () => this.canFilterWaiter() && this.kind() !== 'daypart' && this.kind() !== 'payments',
  );
  readonly current = computed<{ company: ReportCompanyHeaderDto } | null>(() => {
    switch (this.kind()) {
      case 'waiter-sales':
        return this.sales();
      case 'waiter-items':
        return this.waiterItems();
      case 'bills':
        return this.bills();
      case 'bills-summary':
        return this.billsSummary();
      case 'pending-bills':
        return this.pending();
      case 'pending-summary':
        return this.pendingSummary();
      case 'daypart':
        return this.daypart();
      default:
        return this.payments();
    }
  });

  ngOnInit(): void {
    if (!this.canView()) return;
    // A dashboard link may name the report: /admin/restaurant/reports?report=pending-summary
    const asked = this.route?.snapshot.queryParamMap.get('report') as RestaurantReportKind | null;
    if (asked && ALL_KINDS.includes(asked)) this.kind.set(asked);
    this.filterOptions.branchOptions().subscribe({
      next: (opts) => this.branchOptions.set(opts),
      error: () => undefined,
    });
    if (this.canFilterWaiter()) {
      this.reports.waiters().subscribe({
        next: (list) =>
          this.waiterOptions.set(list.map((w) => ({ uid: w.uid, label: w.name, hint: w.username }))),
        error: () => undefined,
      });
    }
  }

  setKind(kind: RestaurantReportKind): void {
    if (this.kind() === kind) return;
    this.kind.set(kind);
    this.state.set('idle');
  }

  /** Opens a tab in the view (summary or detail) the user was last looking at. */
  setTab(tab: RestaurantReportTab): void {
    this.setKind(TAB_KINDS[tab][this.view()]);
  }

  setView(view: RestaurantReportView): void {
    this.setKind(TAB_KINDS[this.tab()][view]);
  }

  private filter(): RestaurantReportFilter | null {
    const from = String(this.fromDate() ?? '').trim();
    const to = String(this.toDate() ?? '').trim();
    if (this.needsDates() && (!from || !to)) return null;
    return {
      fromDate: from,
      toDate: to,
      waiterUid: this.waiterUid() || null,
      branchUid: this.branchUid() || null,
    };
  }

  run(): void {
    const f = this.filter();
    if (!f) return;
    this.state.set('loading');
    const done = <T>(set: (v: T) => void) => ({
      next: (dto: T) => {
        set(dto);
        this.state.set('idle');
      },
      error: (err: unknown) =>
        this.state.set(
          err instanceof HttpErrorResponse && err.status === 403 ? 'forbidden' : 'error',
        ),
    });
    switch (this.kind()) {
      case 'waiter-sales':
        this.sales.set(null);
        this.reports.waiterSales(f).subscribe(done<WaiterSalesReportDto>((v) => this.sales.set(v)));
        break;
      case 'waiter-items':
        this.waiterItems.set(null);
        this.reports
          .waiterItems(f)
          .subscribe(done<WaiterItemsReportDto>((v) => this.waiterItems.set(v)));
        break;
      case 'bills':
        this.bills.set(null);
        this.reports.bills(f).subscribe(done<RestaurantBillsReportDto>((v) => this.bills.set(v)));
        break;
      case 'bills-summary':
        this.billsSummary.set(null);
        this.reports
          .billsSummary(f)
          .subscribe(done<RestaurantBillsSummaryDto>((v) => this.billsSummary.set(v)));
        break;
      case 'pending-bills':
        this.pending.set(null);
        this.reports
          .pendingBills(f)
          .subscribe(done<RestaurantPendingBillsReportDto>((v) => this.pending.set(v)));
        break;
      case 'pending-summary':
        this.pendingSummary.set(null);
        this.reports
          .pendingSummary(f)
          .subscribe(done<RestaurantPendingSummaryDto>((v) => this.pendingSummary.set(v)));
        break;
      case 'daypart':
        this.daypart.set(null);
        this.reports.daypart(f).subscribe(done<RestaurantDaypartDto>((v) => this.daypart.set(v)));
        break;
      case 'payments':
        this.payments.set(null);
        this.reports
          .payments(f)
          .subscribe(done<RestaurantPaymentsReportDto>((v) => this.payments.set(v)));
        break;
    }
  }

  export(format: ExportFormat): void {
    const f = this.filter();
    if (!f || this.exporting()) return;
    this.exporting.set(true);
    const kind = this.kind();
    const name = this.needsDates() ? `${kind}_${f.fromDate}_${f.toDate}` : `${kind}_${todayLocal()}`;
    const call: Observable<Blob> = this.reports.export(kind, f, format);
    call.subscribe({
      next: (blob) => {
        downloadBlob(blob, `restaurant-${name}.${format.toLowerCase()}`);
        this.exporting.set(false);
      },
      error: () => this.exporting.set(false),
    });
  }

  // ── Display helpers ─────────────────────────────────────────────────────────

  /** Numeric-money guard — BigDecimal arrives as a JSON number; never call string methods on it. */
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

  fmtPct(v: number | string | null | undefined): string {
    if (v === null || v === undefined) return '—';
    const n = +v;
    return Number.isFinite(n) ? `${n.toLocaleString('en-US', { maximumFractionDigits: 1 })}%` : '—';
  }

  tenderLabel(tender: string): string {
    return tenderLabel(tender);
  }

  /** The waiter-items rows of one waiter, for the grouped detail table. */
  itemsOf(r: WaiterItemsReportDto, waiterUid: string | null) {
    return r.rows.filter((row) => row.waiterUid === waiterUid);
  }

  addressLine(c: ReportCompanyHeaderDto): string {
    return formatReportAddress(c);
  }

  private firstDayOfCurrentMonth(): string {
    const d = new Date();
    return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-01`;
  }
}
