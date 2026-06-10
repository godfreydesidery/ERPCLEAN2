import { HttpErrorResponse } from '@angular/common/http';
import { Component, OnInit, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { SessionStore } from '../../../core/auth/session.store';
import { Company } from '../models/company.model';
import { CompanyService } from '../company/company.service';
import { OrganisationService } from '../organisation/organisation.service';
import {
  ExportFormat,
  IncomeStatementDto,
  StatementSectionDto,
} from './models/reporting.model';
import { ReportingService } from './reporting.service';
import { downloadBlob } from './reporting.utils';

type LoadState = 'idle' | 'loading' | 'error' | 'forbidden';

/**
 * Income Statement / P&L screen (FR-REP-01, US-REP-01).
 * Gated REPORT.PL.VIEW. Export gated REPORT.EXPORT.
 * Period: fromDate + toDate; optional comparative cmpFrom/cmpTo.
 * Shows sections (REVENUE, COST_OF_SALES, OPERATING_EXPENSES) + gross-profit + net-profit subtotals.
 * Reconciliation bar: green "Balanced" / red alarm.
 */
@Component({
  selector: 'app-income-statement',
  imports: [FormsModule, RouterLink],
  templateUrl: './income-statement.component.html',
  styleUrl: './income-statement.component.scss',
})
export class IncomeStatementComponent implements OnInit {
  private readonly reportingService = inject(ReportingService);
  private readonly companyService = inject(CompanyService);
  private readonly organisationService = inject(OrganisationService);
  private readonly route = inject(ActivatedRoute);
  protected readonly session = inject(SessionStore);

  // ── Company context ────────────────────────────────────────────────────────
  readonly companies = signal<Company[]>([]);
  readonly selectedCompanyId = signal('');
  readonly companyState = signal<'loading' | 'idle' | 'error'>('loading');

  // ── Period selector ────────────────────────────────────────────────────────
  readonly fromDate = signal(this.firstDayOfCurrentMonth());
  readonly toDate = signal(this.today());
  readonly cmpFrom = signal('');
  readonly cmpTo = signal('');

  // ── Statement data ─────────────────────────────────────────────────────────
  readonly statement = signal<IncomeStatementDto | null>(null);
  readonly state = signal<LoadState>('idle');

  // ── Export ────────────────────────────────────────────────────────────────
  readonly exporting = signal(false);

  // ── Permissions ───────────────────────────────────────────────────────────
  readonly canView = computed(() =>
    this.session.hasPermission('REPORT.PL.VIEW') || this.session.hasPermission('REPORT.VIEW'),
  );
  readonly canExport = computed(() => this.session.hasPermission('REPORT.EXPORT'));

  readonly isEmpty = computed(() => this.state() === 'idle' && this.statement() === null);

  ngOnInit(): void {
    this.loadCompanies();
  }

  private loadCompanies(): void {
    this.companyState.set('loading');
    this.organisationService.current().subscribe({
      next: (org) => {
        this.companyService.list(org.uid).subscribe({
          next: (list) => {
            this.companies.set(list);
            this.companyState.set('idle');
            if (list.length > 0) {
              this.selectedCompanyId.set(list[0].id);
            }
          },
          error: () => this.companyState.set('error'),
        });
      },
      error: () => this.companyState.set('error'),
    });
  }

  onCompanyChange(id: string): void {
    this.selectedCompanyId.set(id);
    this.statement.set(null);
  }

  run(): void {
    const companyId = this.selectedCompanyId();
    const from = String(this.fromDate() ?? '').trim();
    const to = String(this.toDate() ?? '').trim();
    if (!companyId || !from || !to) return;

    this.state.set('loading');
    this.statement.set(null);

    this.reportingService
      .incomeStatement(companyId, from, to, this.cmpFrom() || null, this.cmpTo() || null)
      .subscribe({
        next: (dto) => {
          this.statement.set(dto);
          this.state.set('idle');
        },
        error: (err) =>
          this.state.set(
            err instanceof HttpErrorResponse && err.status === 403 ? 'forbidden' : 'error',
          ),
      });
  }

  export(format: ExportFormat): void {
    const companyId = this.selectedCompanyId();
    const from = String(this.fromDate() ?? '').trim();
    const to = String(this.toDate() ?? '').trim();
    if (!companyId || !from || !to || this.exporting()) return;

    this.exporting.set(true);
    this.reportingService
      .exportIncomeStatement(companyId, from, to, format, this.cmpFrom() || null, this.cmpTo() || null)
      .subscribe({
        next: (blob) => {
          downloadBlob(blob, `income-statement_${from}_${to}.${format.toLowerCase()}`);
          this.exporting.set(false);
        },
        error: () => this.exporting.set(false),
      });
  }

  sectionByKey(key: string): StatementSectionDto | undefined {
    return this.statement()?.sections.find((s) => s.sectionKey === key);
  }

  // ── Display helpers ────────────────────────────────────────────────────────

  /** Numeric-money guard (BigDecimal arrives as JSON number — never call string methods on it). */
  fmtMoney(v: number | string | null | undefined): string {
    const n = +(v ?? 0);
    return Number.isFinite(n) ? n.toLocaleString('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2 }) : '0.00';
  }

  private today(): string {
    return new Date().toISOString().slice(0, 10);
  }

  private firstDayOfCurrentMonth(): string {
    const d = new Date();
    return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-01`;
  }
}
