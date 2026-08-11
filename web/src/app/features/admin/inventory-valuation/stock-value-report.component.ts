import { DatePipe } from '@angular/common';
import { HttpErrorResponse } from '@angular/common/http';
import { Component, OnInit, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { AuthService } from '../../../core/auth/auth.service';
import { SessionStore } from '../../../core/auth/session.store';
import { UidOption, UidPickerComponent } from '../../../shared/uid-picker/uid-picker.component';
import { BranchService } from '../branch/branch.service';
import { CompanyService } from '../company/company.service';
import { formatReportAddress, ReportCompanyHeaderDto } from '../models/report-company-header.model';
import { OrganisationService } from '../organisation/organisation.service';
import { SupplierService } from '../parties/supplier.service';
import { ExportFormat } from '../reporting/models/reporting.model';
import { downloadBlob } from '../reporting/reporting.utils';
import {
  formatStockAmount,
  formatStockQty,
  ProductStockReportDto,
  ProductStockReportFilter,
} from './models/product-stock-report.model';
import { ProductStockReportService } from './product-stock-report.service';

type LoadState = 'idle' | 'loading' | 'error' | 'forbidden';

/**
 * Stock Value report — the Product List rows plus cost value and sale value per item, with grand
 * totals at the foot: what the stock on the shelf cost, and what it is worth at selling price.
 *
 * The supplier filter is the headline feature (the "supplier-wise" report the client asked for):
 * pick one supplier and the totals value that supplier's stock on its own.
 *
 * Two honesty rules drive the markup:
 *  - A null cost or price is UNKNOWN, not zero, and renders as a dash (see formatStockAmount()).
 *  - Rows with stock but no cost / no price contribute nothing to the totals, so their COUNTS are
 *    stated right above the foot. Without that, an understated total reads as a complete one —
 *    which is exactly how bulk-imported stock went unnoticed.
 *
 * Route: /admin/reports/stock-value. Gated INVENTORY.VALUATION.VIEW; export needs REPORT.EXPORT.
 */
@Component({
  selector: 'app-stock-value-report',
  imports: [FormsModule, DatePipe, RouterLink, UidPickerComponent],
  templateUrl: './stock-value-report.component.html',
  styleUrl: './stock-value-report.component.scss',
})
export class StockValueReportComponent implements OnInit {
  private readonly reportService = inject(ProductStockReportService);
  private readonly organisationService = inject(OrganisationService);
  private readonly companyService = inject(CompanyService);
  private readonly branchService = inject(BranchService);
  private readonly supplierService = inject(SupplierService);
  private readonly auth = inject(AuthService);
  protected readonly session = inject(SessionStore);

  // ── Filter option lists (loaded once against the caller's first company) ────
  readonly branchOptions = signal<UidOption[]>([]);
  readonly supplierOptions = signal<UidOption[]>([]);

  // ── Filter form ────────────────────────────────────────────────────────────
  readonly branchUid = signal('');
  readonly supplierUid = signal('');

  // ── Report data ────────────────────────────────────────────────────────────
  readonly report = signal<ProductStockReportDto | null>(null);
  readonly state = signal<LoadState>('idle');
  readonly exporting = signal(false);
  readonly loadError = signal<string | null>(null);
  /**
   * A refusal caused by the BRANCH FILTER alone — shown beside the picker, never in place of the
   * report. Being unassigned to one branch is not the same as being barred from the report, which
   * the caller can plainly run unfiltered.
   */
  readonly branchFilterError = signal<string | null>(null);

  readonly canView = computed(() => this.session.hasPermission('INVENTORY.VALUATION.VIEW'));
  /** The export endpoint is gated REPORT.EXPORT server-side — distinct from the view permission. */
  readonly canExport = computed(() => this.session.hasPermission('REPORT.EXPORT'));
  readonly isEmpty = computed(() => this.state() === 'idle' && this.report() === null);

  /** Mirrors ProductStockReportController.sellingHeader() so screen and printout agree word for word. */
  readonly sellingHeader = computed(() => {
    const r = this.report();
    if (!r || r.priceListName === null) return 'Selling Price';
    return r.priceIncludesVat ? 'Selling Price (VAT incl.)' : 'Selling Price (excl. VAT)';
  });

  /** True when the company has no default price list — sale values cannot be computed at all. */
  readonly noPriceList = computed(() => {
    const r = this.report();
    return r !== null && r.priceListName === null;
  });

  /**
   * Whether to offer the fix, not just the diagnosis. Setting a default needs PRICELIST.MANAGE: the
   * Price Lists screen hides "Set default" without it and its route guard turns the caller away
   * outright, so for the stock roles who mostly read this report — storekeeper, accountant, branch
   * manager, production manager — the link is a dead end. They get the plain sentence instead. The
   * diagnosis itself reaches everyone either way. Kept word for word in step with the Product List.
   */
  readonly canSetDefaultPriceList = computed(() => this.session.hasPermission('PRICELIST.MANAGE'));

  /**
   * Items holding stock but carrying no cost / no selling price — silently EXCLUDED from the totals.
   * Null when there are none, so the banner only appears when the totals really are understated.
   */
  readonly excludedFromTotals = computed<{ unvalued: number; unpriced: number } | null>(() => {
    const t = this.report()?.totals;
    if (!t) return null;
    if (t.unvaluedItems === 0 && t.unpricedItems === 0) return null;
    return { unvalued: t.unvaluedItems, unpriced: t.unpricedItems };
  });

  /**
   * Items no longer in the catalogue that still hold stock — INCLUDED here (that is what keeps this
   * valuation tied to the books) but absent from the Product List. Null when there are none, so the
   * note only appears when the two reports really do disagree. Mirrors the exported header line
   * "Includes N discontinued item(s) that still hold stock".
   */
  readonly discontinuedCount = computed<number | null>(() => {
    const n = this.report()?.totals?.discontinuedItems ?? 0;
    return n > 0 ? n : null;
  });

  ngOnInit(): void {
    if (this.canView()) {
      this.loadFilterOptions();
      // No filter is required, so the whole-company value loads straight away; narrowing to one
      // supplier is then one picker away.
      this.run();
    }
  }

  private loadFilterOptions(): void {
    this.organisationService.current().subscribe({
      next: (org) => {
        this.companyService.list(org.uid).subscribe({
          next: (list) => {
            if (list.length === 0) return;
            const company = list[0];
            // Non-fatal on failure: without BRANCH.VIEW / SUPPLIER.VIEW the picker simply stays
            // empty and the report covers every branch / supplier — the unfiltered default anyway.
            this.loadBranchOptions(company.uid);
            this.supplierService.list(company.id, undefined, 0, 200).subscribe({
              next: ({ rows }) =>
                this.supplierOptions.set(
                  rows
                    .filter((s) => s.status === 'ACTIVE')
                    .map((s) => ({ uid: s.uid, label: s.displayName, hint: s.code })),
                ),
              error: () => this.supplierOptions.set([]),
            });
          },
          error: () => undefined,
        });
      },
      error: () => undefined,
    });
  }

  /**
   * Offers only the branches the caller can actually read.
   *
   * The report refuses a branch-filtered run for a caller with no assignment to that branch, so
   * listing every branch in the company offered a choice that always failed and read as a broken
   * screen. GET /auth/my-branches is self-scoped (ADR-0003 D-6) and needs no BRANCH.VIEW; it is the
   * same call the shell's branch switcher already makes, and the session payload carries only the
   * ACTIVE branch, never the list — so there is no cheaper source to read from.
   *
   * Root forks back to the full company list on purpose: the server exempts root from the
   * assignment check, but root usually holds one assignment or none (ADR-0003 D-4), so narrowing
   * the picker would HIDE branches root is entitled to read.
   *
   * Narrowed to the active company either way — a user assigned across companies would otherwise be
   * offered a branch this report cannot resolve.
   */
  private loadBranchOptions(companyUid: string): void {
    if (this.session.user()?.isRoot === true) {
      this.branchService.list(companyUid).subscribe({
        next: (branches) =>
          this.branchOptions.set(
            branches
              .filter((b) => b.status === 'ACTIVE')
              .map((b) => ({ uid: b.uid, label: b.name, hint: b.code })),
          ),
        error: () => this.branchOptions.set([]),
      });
      return;
    }
    this.auth.myBranches().subscribe({
      next: (branches) =>
        this.branchOptions.set(
          branches
            .filter((b) => b.companyUid === companyUid)
            .map((b) => ({ uid: b.branchUid, label: b.branchName, hint: b.branchCode })),
        ),
      error: () => this.branchOptions.set([]),
    });
  }

  run(): void {
    this.state.set('loading');
    this.loadError.set(null);
    this.branchFilterError.set(null);
    this.reportService.stockValue(this.currentFilter()).subscribe({
      next: (dto) => {
        this.report.set(dto);
        this.state.set('idle');
      },
      error: (err: unknown) => {
        if (err instanceof HttpErrorResponse && err.status === 403) {
          this.handleForbidden(err);
          return;
        }
        this.report.set(null);
        this.loadError.set(
          this.messageFrom(err, 'Could not build the Stock Value report. Please try again.'),
        );
        this.state.set('error');
      },
    });
  }

  /**
   * A 403 has two very different meanings here, and collapsing the screen for both was wrong.
   *
   * With a branch chosen and figures already on screen it is the BRANCH that was refused, not the
   * report: the message goes beside the picker, the previous figures stay put, and the user simply
   * picks another branch. Only a refusal of the unfiltered report — or one with nothing to fall back
   * on — replaces the page, and even then it prints the server's own sentence when there is one,
   * because "you are not assigned to that branch" sends the user somewhere useful and "no
   * permission" sends them chasing a permission they already hold.
   */
  private handleForbidden(err: HttpErrorResponse): void {
    if (this.branchUid() && this.report() !== null) {
      this.branchFilterError.set(
        this.messageFrom(err, 'You cannot view this branch. Pick another, or clear the filter.'),
      );
      this.state.set('idle');
      return;
    }
    this.report.set(null);
    this.loadError.set(this.messageFrom(err, ''));
    this.state.set('forbidden');
  }

  export(format: ExportFormat): void {
    if (this.exporting()) return;
    this.exporting.set(true);
    this.reportService.exportStockValue(this.currentFilter(), format).subscribe({
      next: (blob) => {
        downloadBlob(blob, `stock-value_${this.today()}.${format.toLowerCase()}`);
        this.exporting.set(false);
      },
      error: () => this.exporting.set(false),
    });
  }

  /** Built in one place so run() and export() can never disagree about what was asked for. */
  private currentFilter(): ProductStockReportFilter {
    return { branchUid: this.branchUid() || null, supplierUid: this.supplierUid() || null };
  }

  // ── Display helpers ────────────────────────────────────────────────────────

  /** Unknown money renders as an em dash, never 0.00 — see formatStockAmount(). */
  fmtAmt(v: number | string | null | undefined): string {
    return formatStockAmount(v);
  }

  fmtQty(v: number | string | null | undefined): string {
    return formatStockQty(v);
  }

  addressLine(c: ReportCompanyHeaderDto): string {
    return formatReportAddress(c);
  }

  /** Prefers the server's user-safe message; falls back to plain copy with no internal detail. */
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
}
