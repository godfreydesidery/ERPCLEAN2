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
import {
  ProfitabilityReportDto,
  formatProfitAmount,
  formatProfitQty,
} from './models/profitability-report.model';
import { ExportFormat } from './models/reporting.model';
import { ReportingService } from './reporting.service';
import { downloadBlob } from './reporting.utils';

type LoadState = 'idle' | 'loading' | 'error' | 'forbidden';

/**
 * Profitability Report (K-2026-08-30 #2): gross sales, VAT, net, cost of sales and profit, per item
 * and in total, over a date range.
 *
 * This is NOT the Income Statement. That one is built from the general ledger, carries every other
 * cost the business incurs, and never shows VAT (output VAT is a liability, not a P&L line). This
 * one is built from the sales invoices and the stock issued against them, and answers "what did we
 * make on what we sold" without anyone having to post a journal. The subtitle says so on the
 * screen, because two screens that both say "profit" and disagree is how trust in both is lost.
 *
 * A null cost or profit is rendered as an em dash and explained: the item was sold before its stock
 * had ever been costed, so the figure is unknown — NOT zero. Printing 0.00 there would report the
 * entire sale as profit.
 *
 * Route: /admin/reports/profitability. Gated SALES.INVOICE.VIEW; export additionally REPORT.EXPORT.
 */
@Component({
  selector: 'app-profitability-report',
  imports: [FormsModule, DatePipe, RouterLink, UidPickerComponent],
  templateUrl: './profitability-report.component.html',
  styleUrl: './profitability-report.component.scss',
})
export class ProfitabilityReportComponent implements OnInit {
  private readonly reportingService = inject(ReportingService);
  private readonly organisationService = inject(OrganisationService);
  private readonly companyService = inject(CompanyService);
  private readonly branchService = inject(BranchService);
  private readonly auth = inject(AuthService);
  protected readonly session = inject(SessionStore);

  protected readonly fmtAmount = formatProfitAmount;
  protected readonly fmtQty = formatProfitQty;

  // ── Filter form ────────────────────────────────────────────────────────────
  readonly fromDate = signal(this.firstDayOfCurrentMonth());
  readonly toDate = signal(this.today());
  readonly branchUid = signal('');
  readonly branchOptions = signal<UidOption[]>([]);

  // ── Report data ────────────────────────────────────────────────────────────
  readonly report = signal<ProfitabilityReportDto | null>(null);
  readonly state = signal<LoadState>('idle');
  readonly exporting = signal(false);
  readonly loadError = signal<string | null>(null);

  readonly canView = computed(() => this.session.hasPermission('SALES.INVOICE.VIEW'));
  /** The export endpoint is gated REPORT.EXPORT server-side — distinct from the view permission. */
  readonly canExport = computed(() => this.session.hasPermission('REPORT.EXPORT'));
  readonly isEmpty = computed(() => this.state() === 'idle' && this.report() === null);

  /** The date range is the only required input, so an invalid one is worth saying before the call. */
  readonly datesValid = computed(() => {
    const from = String(this.fromDate() ?? '').trim();
    const to = String(this.toDate() ?? '').trim();
    return !!from && !!to && from <= to;
  });

  /**
   * True when the foot excludes some cost of sales. The screen must say so: a partial total reads
   * as complete, and a profit total missing its cost side OVERSTATES the result.
   */
  readonly costIncomplete = computed(() => (this.report()?.totals.rowsWithUnknownCost ?? 0) > 0);

  ngOnInit(): void {
    if (this.canView()) {
      this.loadBranchOptions();
      this.run();
    }
  }

  run(): void {
    if (!this.datesValid()) return;

    this.state.set('loading');
    this.loadError.set(null);
    this.reportingService.profitabilityReport(this.currentFilter()).subscribe({
      next: (dto) => {
        this.report.set(dto);
        this.state.set('idle');
      },
      error: (err: unknown) => {
        this.report.set(null);
        if (err instanceof HttpErrorResponse && err.status === 403) {
          this.loadError.set(this.serverMessage(err));
          this.state.set('forbidden');
          return;
        }
        this.loadError.set(this.serverMessage(err));
        this.state.set('error');
      },
    });
  }

  export(format: ExportFormat): void {
    if (!this.datesValid() || this.exporting()) return;

    const filter = this.currentFilter();
    this.exporting.set(true);
    this.reportingService.exportProfitabilityReport(filter, format).subscribe({
      next: (blob) => {
        downloadBlob(
          blob,
          `profitability_${filter.fromDate}_${filter.toDate}.${format.toLowerCase()}`,
        );
        this.exporting.set(false);
      },
      error: () => this.exporting.set(false),
    });
  }

  private currentFilter() {
    return {
      fromDate: String(this.fromDate() ?? '').trim(),
      toDate: String(this.toDate() ?? '').trim(),
      branchUid: this.branchUid() || null,
    };
  }

  /**
   * Offers only the branches the caller may read — root forks to the full company list, since the
   * server exempts root from the assignment check but root usually holds one assignment or none.
   */
  private loadBranchOptions(): void {
    this.organisationService.current().subscribe({
      next: (org) => {
        this.companyService.list(org.uid).subscribe({
          next: (list) => {
            if (list.length === 0) return;
            const companyUid = list[0].uid;
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
          },
          error: () => undefined,
        });
      },
      error: () => undefined,
    });
  }

  addressLine(c: ReportCompanyHeaderDto): string {
    return formatReportAddress(c);
  }

  /** Prefer the server's own sentence: it names what to do about the refusal. */
  private serverMessage(err: unknown): string | null {
    if (err instanceof HttpErrorResponse) {
      const errors = (err.error as { errors?: string[] })?.errors;
      if (errors?.length) return errors[0];
    }
    return null;
  }

  private today(): string {
    return new Date().toISOString().slice(0, 10);
  }

  private firstDayOfCurrentMonth(): string {
    const d = new Date();
    return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-01`;
  }
}
