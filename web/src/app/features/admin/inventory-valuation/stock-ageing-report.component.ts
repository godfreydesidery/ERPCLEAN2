import { DatePipe } from '@angular/common';
import { HttpErrorResponse } from '@angular/common/http';
import { Component, OnInit, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { SessionStore } from '../../../core/auth/session.store';
import { UidOption, UidPickerComponent } from '../../../shared/uid-picker/uid-picker.component';
import { formatReportAddress, ReportCompanyHeaderDto } from '../models/report-company-header.model';
import { ExportFormat } from '../reporting/models/reporting.model';
import {
  ReportFilterOptionsService,
  fmtMoneyOrDash,
  fmtQuantity,
  serverMessage,
  todayIso,
} from '../reporting/report-filter-options.service';
import { downloadBlob } from '../reporting/reporting.utils';
import { StockAgeingFilter, StockAgeingReportDto, StockAgeingRowDto } from './models/stock-ageing.model';
import { StockReorderAgeingService } from './stock-reorder-ageing.service';

type LoadState = 'idle' | 'loading' | 'error' | 'forbidden';

/** "Slow" means no sale in this many days (or never) — the filter the owner reaches for first. */
export const SLOW_STOCK_DAYS = 90;

/**
 * Stock Ageing — on-hand quantity and value per item split into age buckets, plus days since each
 * item last sold (slow and dead stock).
 *
 * The age is INFERRED, and the screen says so: stock is assumed first-in, first-out — what is on the
 * shelf is the most recently received units. Stock is costed at moving average, so each bucket is
 * valued at the item's one average cost; ageing is a view of quantities.
 *
 * Stock older than the recorded movement history has an unknown age; it is shown in the oldest
 * bucket with a marker, and counted, never silently aged.
 *
 * Route: /admin/reports/stock-ageing. Gated INVENTORY.VALUATION.VIEW (it shows value); export
 * additionally REPORT.EXPORT.
 */
@Component({
  selector: 'app-stock-ageing-report',
  imports: [FormsModule, DatePipe, UidPickerComponent],
  templateUrl: './stock-ageing-report.component.html',
  styleUrl: './stock-ageing-report.component.scss',
})
export class StockAgeingReportComponent implements OnInit {
  private readonly api = inject(StockReorderAgeingService);
  private readonly options = inject(ReportFilterOptionsService);
  protected readonly session = inject(SessionStore);

  protected readonly fmtAmount = fmtMoneyOrDash;
  protected readonly fmtQty = fmtQuantity;
  protected readonly slowDays = SLOW_STOCK_DAYS;

  readonly asOf = signal(todayIso());
  readonly branchUid = signal('');
  readonly slowOnly = signal(false);
  readonly branchOptions = signal<UidOption[]>([]);

  readonly report = signal<StockAgeingReportDto | null>(null);
  readonly state = signal<LoadState>('idle');
  readonly exporting = signal(false);
  readonly loadError = signal<string | null>(null);

  readonly canView = computed(() => this.session.hasPermission('INVENTORY.VALUATION.VIEW'));
  readonly canExport = computed(() => this.session.hasPermission('REPORT.EXPORT'));
  readonly oldestBucket = computed(() => (this.report()?.buckets.length ?? 1) - 1);

  /** Rows on screen — all, or only the slow movers. The export always carries every row. */
  readonly visibleRows = computed<StockAgeingRowDto[]>(() => {
    const rows = this.report()?.rows ?? [];
    if (!this.slowOnly()) return rows;
    return rows.filter((r) => r.daysSinceLastSale === null || r.daysSinceLastSale >= SLOW_STOCK_DAYS);
  });

  ngOnInit(): void {
    if (this.canView()) {
      this.options.branchOptions().subscribe((opts) => this.branchOptions.set(opts));
      this.run();
    }
  }

  run(): void {
    this.state.set('loading');
    this.loadError.set(null);
    this.api.stockAgeing(this.currentFilter()).subscribe({
      next: (dto) => {
        this.report.set(dto);
        this.state.set('idle');
      },
      error: (err: unknown) => {
        this.report.set(null);
        const forbidden = err instanceof HttpErrorResponse && err.status === 403;
        this.loadError.set(
          serverMessage(err, forbidden ? '' : 'Could not build the Stock Ageing report. Please try again.'),
        );
        this.state.set(forbidden ? 'forbidden' : 'error');
      },
    });
  }

  export(format: ExportFormat): void {
    if (this.exporting()) return;
    const f = this.currentFilter();
    this.exporting.set(true);
    this.api.exportStockAgeing(f, format).subscribe({
      next: (blob) => {
        downloadBlob(blob, `stock-ageing_${f.asOf ?? todayIso()}.${format.toLowerCase()}`);
        this.exporting.set(false);
      },
      error: () => this.exporting.set(false),
    });
  }

  private currentFilter(): StockAgeingFilter {
    return {
      asOf: String(this.asOf() ?? '').trim() || null,
      branchUid: this.branchUid() || null,
    };
  }

  /** The days-since-sale cell: "Never" for an item with no sale on record. */
  lastSold(r: StockAgeingRowDto): string {
    return r.daysSinceLastSale === null ? 'Never' : String(r.daysSinceLastSale);
  }

  addressLine(c: ReportCompanyHeaderDto): string {
    return formatReportAddress(c);
  }
}
