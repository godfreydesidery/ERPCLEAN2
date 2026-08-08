import { DatePipe } from '@angular/common';
import { HttpErrorResponse } from '@angular/common/http';
import { Component, OnInit, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { PageMeta } from '../../../core/api/api-response.model';
import { SessionStore } from '../../../core/auth/session.store';
import { PaginatorComponent } from '../../../shared/paginator/paginator.component';
import { UidOption, UidPickerComponent } from '../../../shared/uid-picker/uid-picker.component';
import { BranchService } from '../branch/branch.service';
import { CompanyService } from '../company/company.service';
import { formatReportAddress, ReportCompanyHeaderDto } from '../models/report-company-header.model';
import { OrganisationService } from '../organisation/organisation.service';
import { ProductService } from '../products/product.service';
import { ExportFormat } from '../reporting/models/reporting.model';
import { downloadBlob } from '../reporting/reporting.utils';
import {
  StockMovementReportDto,
  StockMovementReportFilter,
  StockMovementReportMode,
} from './models/stock-movement-report.model';
import { StockMovementReportService } from './stock-movement-report.service';

type LoadState = 'idle' | 'loading' | 'error' | 'forbidden';

/**
 * Period Stock Movement report (K9) — "show me stock movement for a date range", which the existing
 * Stock Report could not answer (it is a present-moment snapshot with no period at all).
 *
 * Filter bar: required From/To (defaults to the current month), a SUMMARY / DETAIL toggle, and
 * optional Branch / Product narrowing. SUMMARY is one row per product with the five columns —
 * OPENING, PURCHASES, SALES, ADJUSTMENTS/OTHER, CLOSING — that close arithmetically
 * (`closing = opening + purchases − sales + adjustments`). DETAIL is one row per ledger movement
 * with a running balance that stays continuous across pages.
 *
 * Scope is server-side from the JWT + X-Branch-Uid; no companyId is sent. The Branch/Product picker
 * OPTIONS are loaded locally against the caller's first accessible company purely to fill the
 * dropdowns (the same 200-cap fetch the Sales Report uses).
 *
 * Route: /admin/reports/stock-movement. Gated INVENTORY.VALUATION.VIEW; export needs REPORT.EXPORT.
 */
@Component({
  selector: 'app-stock-movement-report',
  imports: [FormsModule, DatePipe, UidPickerComponent, PaginatorComponent],
  templateUrl: './stock-movement-report.component.html',
  styleUrl: './stock-movement-report.component.scss',
})
export class StockMovementReportComponent implements OnInit {
  private readonly reportService = inject(StockMovementReportService);
  private readonly companyService = inject(CompanyService);
  private readonly organisationService = inject(OrganisationService);
  private readonly branchService = inject(BranchService);
  private readonly productService = inject(ProductService);
  protected readonly session = inject(SessionStore);

  /** Page size — the server clamps anything larger, so keep it modest and paginate. */
  private static readonly PAGE_SIZE = 50;

  // ── Filter option lists (loaded once against the caller's first company) ────
  readonly branchOptions = signal<UidOption[]>([]);
  readonly productOptions = signal<UidOption[]>([]);

  // ── Filter form ────────────────────────────────────────────────────────────
  readonly fromDate = signal(this.firstDayOfCurrentMonth());
  readonly toDate = signal(this.today());
  readonly mode = signal<StockMovementReportMode>('SUMMARY');
  readonly branchUid = signal('');
  readonly productUid = signal('');

  // ── Report data ────────────────────────────────────────────────────────────
  readonly report = signal<StockMovementReportDto | null>(null);
  readonly state = signal<LoadState>('idle');
  readonly exporting = signal(false);
  readonly loadError = signal<string | null>(null);
  /** Set by validate(); shown inline instead of firing a request that cannot succeed. */
  readonly filterError = signal<string | null>(null);

  readonly canView = computed(() => this.session.hasPermission('INVENTORY.VALUATION.VIEW'));
  /** The export endpoint is gated REPORT.EXPORT server-side — distinct from the view permission. */
  readonly canExport = computed(() => this.session.hasPermission('REPORT.EXPORT'));
  readonly isEmpty = computed(() => this.state() === 'idle' && this.report() === null);
  readonly isSummary = computed(() => this.report()?.mode !== 'DETAIL');

  /**
   * Paging metadata for the shared paginator. The backend sends page/size/totalElements/totalPages
   * as plain numbers on the report DTO (not a PageMeta envelope), so map them here.
   */
  readonly meta = computed<PageMeta | null>(() => {
    const r = this.report();
    if (!r) return null;
    return {
      page: r.page,
      size: r.size,
      totalElements: r.totalElements,
      totalPages: r.totalPages,
      hasNext: r.page < r.totalPages - 1,
    };
  });

  ngOnInit(): void {
    if (this.canView()) {
      this.loadFilterOptions();
    }
  }

  private loadFilterOptions(): void {
    this.organisationService.current().subscribe({
      next: (org) => {
        this.companyService.list(org.uid).subscribe({
          next: (list) => {
            if (list.length === 0) return;
            const company = list[0];
            this.branchService.list(company.uid).subscribe({
              next: (branches) =>
                this.branchOptions.set(
                  branches
                    .filter((b) => b.status === 'ACTIVE')
                    .map((b) => ({ uid: b.uid, label: b.name, hint: b.code })),
                ),
              // Non-fatal: without BRANCH.VIEW the picker stays empty and the report covers
              // every branch the caller's company has — which is the unfiltered default anyway.
              error: () => this.branchOptions.set([]),
            });
            this.productService.list(company.id, '', 0, 200).subscribe({
              next: ({ rows }) =>
                this.productOptions.set(
                  rows
                    .filter((p) => p.status !== 'ARCHIVED')
                    .map((p) => ({ uid: p.uid, label: p.name, hint: p.code })),
                ),
              error: () => this.productOptions.set([]),
            });
          },
          error: () => undefined,
        });
      },
      error: () => undefined,
    });
  }

  // ── Run / page ─────────────────────────────────────────────────────────────

  run(): void {
    this.load(0);
  }

  goToPage(page: number): void {
    this.load(page);
  }

  /** Switching SUMMARY ↔ DETAIL re-runs from page 0 — the two grids do not share a row count. */
  onModeChange(mode: StockMovementReportMode): void {
    this.mode.set(mode);
    if (this.report()) {
      this.load(0);
    }
  }

  private load(page: number): void {
    const filter = this.currentFilter();
    if (!filter) return;

    this.state.set('loading');
    this.loadError.set(null);
    this.reportService.report(filter, page, StockMovementReportComponent.PAGE_SIZE).subscribe({
      next: (dto) => {
        this.report.set(dto);
        this.state.set('idle');
      },
      error: (err: unknown) => {
        this.report.set(null);
        if (err instanceof HttpErrorResponse && err.status === 403) {
          this.state.set('forbidden');
          return;
        }
        this.loadError.set(this.messageFrom(err, 'Could not build the Stock Movement report.'));
        this.state.set('error');
      },
    });
  }

  export(format: ExportFormat): void {
    const filter = this.currentFilter();
    if (!filter || this.exporting()) return;

    this.exporting.set(true);
    this.reportService.export(filter, format).subscribe({
      next: (blob) => {
        downloadBlob(
          blob,
          `stock-movement_${filter.fromDate}_${filter.toDate}.${format.toLowerCase()}`,
        );
        this.exporting.set(false);
      },
      error: () => this.exporting.set(false),
    });
  }

  /**
   * The filter as the API wants it, or null when the form is not usable yet. Validated here rather
   * than in the template so both `run()` and `export()` obey the same rule.
   */
  private currentFilter(): StockMovementReportFilter | null {
    const from = String(this.fromDate() ?? '').trim();
    const to = String(this.toDate() ?? '').trim();
    if (!from || !to) {
      this.filterError.set('Choose both a start and an end date.');
      return null;
    }
    if (from > to) {
      // ISO dates compare correctly as strings — no Date parsing needed.
      this.filterError.set('The start date must be on or before the end date.');
      return null;
    }
    this.filterError.set(null);
    return {
      fromDate: from,
      toDate: to,
      mode: this.mode(),
      branchUid: this.branchUid() || null,
      productUid: this.productUid() || null,
    };
  }

  // ── Display helpers ────────────────────────────────────────────────────────

  /**
   * Quantity in the product's base unit. Whole numbers print clean; up to 3 dp is kept so a
   * fractional holding (weighed goods, or loose pieces under a carton base) never renders as "0".
   */
  fmtQty(v: number | string | null | undefined): string {
    const n = +(v ?? 0);
    return Number.isFinite(n)
      ? n.toLocaleString('en-US', { minimumFractionDigits: 0, maximumFractionDigits: 3 })
      : '0';
  }

  /** GOODS_RECEIPT → "Goods receipt". Free text (an operator's note) is passed through untouched. */
  humanise(text: string | null | undefined): string {
    if (!text || !text.trim()) return '—';
    if (text !== text.toUpperCase()) return text;
    const spaced = text.replace(/_/g, ' ').toLowerCase();
    return spaced.charAt(0).toUpperCase() + spaced.slice(1);
  }

  sourceLabel(type: string | null, uid: string | null): string {
    if (!type) return '—';
    return uid ? `${this.humanise(type)} ${uid}` : this.humanise(type);
  }

  addressLine(c: ReportCompanyHeaderDto): string {
    return formatReportAddress(c);
  }

  private messageFrom(err: unknown, fallback: string): string {
    if (err instanceof HttpErrorResponse) {
      const errors = (err.error as { errors?: string[] })?.errors;
      if (errors?.length) return errors[0];
    }
    return fallback;
  }

  private today(): string {
    return new Date().toISOString().slice(0, 10);
  }

  private firstDayOfCurrentMonth(): string {
    const d = new Date();
    return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-01`;
  }
}
