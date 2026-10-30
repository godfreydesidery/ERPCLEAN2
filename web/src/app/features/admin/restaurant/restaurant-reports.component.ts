import { DatePipe } from '@angular/common';
import { HttpErrorResponse } from '@angular/common/http';
import { Component, OnInit, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
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
  RestaurantPendingBillsReportDto,
  RestaurantReportFilter,
  RestaurantReportKind,
  WaiterSalesReportDto,
} from './models/restaurant-report.model';
import { RestaurantReportService } from './restaurant-report.service';

type LoadState = 'idle' | 'loading' | 'error' | 'forbidden';

/**
 * Restaurant reports per waiter (J COB 2026-10-30): sales by waiter, the bills paid, and the bills
 * still pending (open, or printed and unpaid) — for one waiter or all. Gated SALES.INVOICE.VIEW,
 * like the Sales Report; export needs REPORT.EXPORT as well.
 */
@Component({
  selector: 'app-restaurant-reports',
  imports: [FormsModule, UidPickerComponent, DatePipe],
  templateUrl: './restaurant-reports.component.html',
})
export class RestaurantReportsComponent implements OnInit {
  private readonly reports = inject(RestaurantReportService);
  private readonly filterOptions = inject(ReportFilterOptionsService);
  protected readonly session = inject(SessionStore);

  readonly kinds: { kind: RestaurantReportKind; label: string }[] = [
    { kind: 'waiter-sales', label: 'Sales by waiter' },
    { kind: 'bills', label: 'Bills paid' },
    { kind: 'pending-bills', label: 'Pending bills' },
  ];

  // ── Filters ─────────────────────────────────────────────────────────────────
  readonly kind = signal<RestaurantReportKind>('waiter-sales');
  readonly fromDate = signal(this.firstDayOfCurrentMonth());
  readonly toDate = signal(todayLocal());
  readonly waiterUid = signal('');
  readonly branchUid = signal('');
  readonly waiterOptions = signal<UidOption[]>([]);
  readonly branchOptions = signal<UidOption[]>([]);

  // ── Results (one per report, so switching tabs never shows another's rows) ──
  readonly sales = signal<WaiterSalesReportDto | null>(null);
  readonly bills = signal<RestaurantBillsReportDto | null>(null);
  readonly pending = signal<RestaurantPendingBillsReportDto | null>(null);
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
  readonly needsDates = computed(() => this.kind() !== 'pending-bills');
  readonly current = computed(() => {
    switch (this.kind()) {
      case 'waiter-sales':
        return this.sales();
      case 'bills':
        return this.bills();
      default:
        return this.pending();
    }
  });

  ngOnInit(): void {
    if (!this.canView()) return;
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
    const kind = this.kind();
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
    if (kind === 'waiter-sales') {
      this.sales.set(null);
      this.reports.waiterSales(f).subscribe(done<WaiterSalesReportDto>((v) => this.sales.set(v)));
    } else if (kind === 'bills') {
      this.bills.set(null);
      this.reports.bills(f).subscribe(done<RestaurantBillsReportDto>((v) => this.bills.set(v)));
    } else {
      this.pending.set(null);
      this.reports
        .pendingBills(f)
        .subscribe(done<RestaurantPendingBillsReportDto>((v) => this.pending.set(v)));
    }
  }

  export(format: ExportFormat): void {
    const f = this.filter();
    if (!f || this.exporting()) return;
    this.exporting.set(true);
    const kind = this.kind();
    const name = kind === 'pending-bills' ? `${kind}_${todayLocal()}` : `${kind}_${f.fromDate}_${f.toDate}`;
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

  addressLine(c: ReportCompanyHeaderDto): string {
    return formatReportAddress(c);
  }

  private firstDayOfCurrentMonth(): string {
    const d = new Date();
    return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-01`;
  }
}
