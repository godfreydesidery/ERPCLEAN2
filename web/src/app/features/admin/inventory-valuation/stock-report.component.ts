import { DatePipe } from '@angular/common';
import { HttpErrorResponse } from '@angular/common/http';
import { Component, OnInit, computed, inject, signal } from '@angular/core';
import { SessionStore } from '../../../core/auth/session.store';
import { formatReportAddress, ReportCompanyHeaderDto } from '../models/report-company-header.model';
import { ExportFormat } from '../reporting/models/reporting.model';
import { downloadBlob } from '../reporting/reporting.utils';
import { StockReportDto } from './models/stock-report.model';
import { StockReportService } from './stock-report.service';

type LoadState = 'idle' | 'loading' | 'error' | 'forbidden';

/**
 * Stock Report screen (SAM Electronix go-live). Gated INVENTORY.VALUATION.VIEW.
 *
 * Company header + a per-product table (Code | Description | Qty On Hand | Buying Price |
 * Selling Price | Value) with a bold total-value row, and PDF/Excel/CSV export. Distinct from the
 * Stock Valuation Report (avg-cost + GL reconciliation) at /admin/stock/valuation — this is the
 * plain operational print report SAM asked for at go-live.
 */
@Component({
  selector: 'app-stock-report',
  imports: [DatePipe],
  templateUrl: './stock-report.component.html',
  styleUrl: './stock-report.component.scss',
})
export class StockReportComponent implements OnInit {
  private readonly stockReportService = inject(StockReportService);
  protected readonly session = inject(SessionStore);

  readonly report = signal<StockReportDto | null>(null);
  readonly state = signal<LoadState>('idle');
  readonly exporting = signal(false);

  readonly canView = computed(() => this.session.hasPermission('INVENTORY.VALUATION.VIEW'));
  /** The export endpoint is gated REPORT.EXPORT server-side — distinct from the view permission. */
  readonly canExport = computed(() => this.session.hasPermission('REPORT.EXPORT'));
  readonly isEmpty = computed(() => this.state() === 'idle' && this.report() === null);

  ngOnInit(): void {
    if (this.canView()) {
      this.load();
    }
  }

  load(): void {
    this.state.set('loading');
    this.report.set(null);
    this.stockReportService.report().subscribe({
      next: (dto) => {
        this.report.set(dto);
        this.state.set('idle');
      },
      error: (err) =>
        this.state.set(
          err instanceof HttpErrorResponse && err.status === 403 ? 'forbidden' : 'error',
        ),
    });
  }

  export(format: ExportFormat): void {
    if (this.exporting()) return;
    this.exporting.set(true);
    this.stockReportService.export(format).subscribe({
      next: (blob) => {
        downloadBlob(blob, `stock-report_${this.today()}.${format.toLowerCase()}`);
        this.exporting.set(false);
      },
      error: () => this.exporting.set(false),
    });
  }

  // ── Display helpers ────────────────────────────────────────────────────────

  /** Numeric-money guard — BigDecimal arrives as a JSON number; never call string methods on it. */
  fmtMoney(v: number | string | null | undefined): string {
    const n = +(v ?? 0);
    return Number.isFinite(n)
      ? n.toLocaleString('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2 })
      : '0.00';
  }

  fmtQty(v: number | string | null | undefined): string {
    const n = +(v ?? 0);
    return Number.isFinite(n)
      ? n.toLocaleString('en-US', { minimumFractionDigits: 0, maximumFractionDigits: 0 })
      : '0';
  }

  addressLine(c: ReportCompanyHeaderDto): string {
    return formatReportAddress(c);
  }

  private today(): string {
    return new Date().toISOString().slice(0, 10);
  }
}
