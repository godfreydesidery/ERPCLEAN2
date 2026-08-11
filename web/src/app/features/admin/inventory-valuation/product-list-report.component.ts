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
 * Product List report — item code, description, supplier, on-hand quantity, buying price and
 * selling price, optionally narrowed to one branch or one supplier.
 *
 * It carries NO totals row, deliberately: this is a catalogue, and a sum across unrelated products
 * tells the reader nothing. The Stock Value report at /admin/reports/stock-value is the one with
 * money totals — the two are cross-linked so nobody concludes the totals are missing by mistake.
 *
 * Selling price comes from the company's DEFAULT price list, and the column head names the VAT
 * stance because "selling price" alone is ambiguous to a shopkeeper. When no default list is set the
 * column is blank and an inline notice says why, rather than leaving a column of dashes to read as
 * a bug.
 *
 * Route: /admin/reports/product-list. Gated INVENTORY.VALUATION.VIEW (it discloses buying prices);
 * export additionally needs REPORT.EXPORT.
 */
@Component({
  selector: 'app-product-list-report',
  imports: [FormsModule, DatePipe, RouterLink, UidPickerComponent],
  templateUrl: './product-list-report.component.html',
  styleUrl: './product-list-report.component.scss',
})
export class ProductListReportComponent implements OnInit {
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

  /** True when the company has no default price list — every selling price will be blank. */
  readonly noPriceList = computed(() => {
    const r = this.report();
    return r !== null && r.priceListName === null;
  });

  /**
   * Whether to offer the fix, not just the diagnosis. Setting a default needs PRICELIST.MANAGE: the
   * Price Lists screen hides "Set default" without it and its route guard turns the caller away
   * outright, so for the stock roles who mostly read this report — storekeeper, accountant, branch
   * manager, production manager — the link is a dead end. They get the plain sentence instead. The
   * diagnosis itself reaches everyone either way.
   */
  readonly canSetDefaultPriceList = computed(() => this.session.hasPermission('PRICELIST.MANAGE'));

  ngOnInit(): void {
    if (this.canView()) {
      this.loadFilterOptions();
      // No filter is required, so the catalogue loads straight away rather than making the user
      // click Run to see a list they asked for by opening the screen.
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
    this.reportService.productList(this.currentFilter()).subscribe({
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
          this.messageFrom(err, 'Could not build the Product List. Please try again.'),
        );
        this.state.set('error');
      },
    });
  }

  /**
   * A 403 has two very different meanings here, and collapsing the screen for both was wrong.
   *
   * With a branch chosen and a listing already on screen it is the BRANCH that was refused, not the
   * report: the message goes beside the picker, the previous listing stays put, and the user simply
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
    this.reportService.exportProductList(this.currentFilter(), format).subscribe({
      next: (blob) => {
        downloadBlob(blob, `product-list_${this.today()}.${format.toLowerCase()}`);
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
