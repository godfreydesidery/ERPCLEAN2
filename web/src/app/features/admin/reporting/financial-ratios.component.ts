import { HttpErrorResponse } from '@angular/common/http';
import { Component, OnInit, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { SessionStore } from '../../../core/auth/session.store';
import { UidPickerComponent } from '../../../shared/uid-picker/uid-picker.component';
import { Company } from '../models/company.model';
import { CompanyService } from '../company/company.service';
import { OrganisationService } from '../organisation/organisation.service';
import { ExportFormat, FinancialRatioDto, FinancialRatiosDto } from './models/reporting.model';
import { ReportingService } from './reporting.service';
import { downloadBlob } from './reporting.utils';
import { BRANCH_STATEMENT_NOTE, StatementBranchFilterState } from './statement-branch-filter';
import { todayLocal } from '../../../shared/date.util';

type LoadState = 'idle' | 'loading' | 'error' | 'forbidden';

/**
 * Financial Ratios — liquidity, margins, gearing, return and working-capital days for a period,
 * each shown with its formula and the statement figures it was worked out from. Every number is
 * computed by the server from the Income Statement and the Balance Sheet; the screen never
 * re-derives one. A ratio that cannot be worked out (its divisor is zero) shows a dash and the
 * reason — never 0.00, which would read as a real, terrible result.
 *
 * Route: /admin/reporting/ratios. Needs BOTH REPORT.PL.VIEW and REPORT.BS.VIEW (it discloses
 * figures from both statements); export additionally REPORT.EXPORT. Optional branch filter.
 */
@Component({
  selector: 'app-financial-ratios',
  imports: [FormsModule, UidPickerComponent],
  templateUrl: './financial-ratios.component.html',
  styleUrl: './financial-ratios.component.scss',
})
export class FinancialRatiosComponent implements OnInit {
  private readonly reportingService = inject(ReportingService);
  private readonly companyService = inject(CompanyService);
  private readonly organisationService = inject(OrganisationService);
  protected readonly session = inject(SessionStore);

  readonly companies = signal<Company[]>([]);
  readonly selectedCompanyId = signal('');
  readonly companyState = signal<'loading' | 'idle' | 'error'>('loading');

  readonly fromDate = signal(this.firstDayOfYear());
  readonly toDate = signal(this.today());

  /** Ratios take a branch but not the company-level slice (ratios of that slice mean nothing). */
  readonly branch = new StatementBranchFilterState(false);
  protected readonly branchNote = BRANCH_STATEMENT_NOTE;

  readonly report = signal<FinancialRatiosDto | null>(null);
  readonly state = signal<LoadState>('idle');
  readonly exporting = signal(false);
  readonly loadError = signal<string | null>(null);

  readonly canView = computed(
    () => this.session.hasPermission('REPORT.PL.VIEW') && this.session.hasPermission('REPORT.BS.VIEW'),
  );
  readonly canExport = computed(() => this.session.hasPermission('REPORT.EXPORT'));
  readonly isEmpty = computed(() => this.state() === 'idle' && this.report() === null);

  readonly datesValid = computed(() => {
    const from = String(this.fromDate() ?? '').trim();
    const to = String(this.toDate() ?? '').trim();
    return !!from && !!to && from <= to;
  });

  ngOnInit(): void {
    this.organisationService.current().subscribe({
      next: (org) =>
        this.companyService.list(org.uid).subscribe({
          next: (list) => {
            this.companies.set(list);
            this.companyState.set('idle');
            if (list.length > 0) {
              this.selectedCompanyId.set(list[0].id);
              this.branch.loadFor(list[0].uid);
            }
          },
          error: () => this.companyState.set('error'),
        }),
      error: () => this.companyState.set('error'),
    });
  }

  onCompanyChange(id: string): void {
    this.selectedCompanyId.set(id);
    this.report.set(null);
    this.branch.loadFor(this.companies().find((c) => c.id === id)?.uid);
  }

  onBranchChange(value: string | null): void {
    this.branch.value.set(value ?? '');
    this.report.set(null);
  }

  run(): void {
    const companyId = this.selectedCompanyId();
    if (!this.canView() || !companyId || !this.datesValid()) return;
    this.state.set('loading');
    this.report.set(null);
    this.loadError.set(null);
    this.reportingService
      .financialRatios(companyId, this.from(), this.to(), this.branch.filter().branchUid ?? null)
      .subscribe({
        next: (dto) => {
          this.report.set(dto);
          this.state.set('idle');
        },
        error: (err: unknown) => {
          this.loadError.set(this.serverMessage(err));
          this.state.set(
            err instanceof HttpErrorResponse && err.status === 403 ? 'forbidden' : 'error',
          );
        },
      });
  }

  export(format: ExportFormat): void {
    const companyId = this.selectedCompanyId();
    if (!this.canView() || !this.canExport() || !companyId || !this.datesValid() || this.exporting()) return;
    this.exporting.set(true);
    this.reportingService
      .exportFinancialRatios(
        companyId, this.from(), this.to(), format, this.branch.filter().branchUid ?? null,
      )
      .subscribe({
        next: (blob) => {
          downloadBlob(blob, `financial-ratios_${this.from()}_${this.to()}.${format.toLowerCase()}`);
          this.exporting.set(false);
        },
        error: () => this.exporting.set(false),
      });
  }

  /** "4.44 ×", "60.00 %", "45.00 days" — or null when the ratio has no value. */
  fmtResult(r: FinancialRatioDto): string | null {
    if (r.value === null || r.value === undefined) return null;
    const n = +r.value;
    if (!Number.isFinite(n)) return null;
    const text = n.toLocaleString('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2 });
    return r.unit === 'x' ? `${text} ×` : r.unit === '%' ? `${text} %` : `${text} days`;
  }

  /** Numeric-money guard: BigDecimal arrives as a JSON number. */
  fmtMoney(v: number | string | null | undefined): string {
    const n = +(v ?? 0);
    return Number.isFinite(n)
      ? n.toLocaleString('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2 })
      : '0.00';
  }

  /** Prefer the server's own sentence: for a branch refusal it says what to do. */
  private serverMessage(err: unknown): string | null {
    if (err instanceof HttpErrorResponse) {
      const errors = (err.error as { errors?: string[] })?.errors;
      if (errors?.length) return errors[0];
    }
    return null;
  }

  private from(): string {
    return String(this.fromDate() ?? '').trim();
  }

  private to(): string {
    return String(this.toDate() ?? '').trim();
  }

  private today(): string {
    return todayLocal();
  }

  private firstDayOfYear(): string {
    return `${new Date().getFullYear()}-01-01`;
  }
}
