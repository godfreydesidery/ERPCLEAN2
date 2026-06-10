import { HttpErrorResponse } from '@angular/common/http';
import { Component, OnInit, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { SessionStore } from '../../../core/auth/session.store';
import { Company } from '../models/company.model';
import { CompanyService } from '../company/company.service';
import { OrganisationService } from '../organisation/organisation.service';
import { CashFlowStatementDto, ExportFormat } from './models/reporting.model';
import { ReportingService } from './reporting.service';
import { downloadBlob } from './reporting.utils';

type LoadState = 'idle' | 'loading' | 'error' | 'forbidden';

/**
 * Cash-Flow Statement (indirect method) screen (FR-REP-03, US-REP-03).
 * Gated REPORT.CASHFLOW.VIEW. Export gated REPORT.EXPORT.
 * Period from/to + optional comparative. Sections: OPERATING, INVESTING, FINANCING.
 * Cash tie-out indicator (net change == Cash + Bank GL movement).
 */
@Component({
  selector: 'app-cash-flow-statement',
  imports: [FormsModule, RouterLink],
  templateUrl: './cash-flow-statement.component.html',
  styleUrl: './cash-flow-statement.component.scss',
})
export class CashFlowStatementComponent implements OnInit {
  private readonly reportingService = inject(ReportingService);
  private readonly companyService = inject(CompanyService);
  private readonly organisationService = inject(OrganisationService);
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
  readonly statement = signal<CashFlowStatementDto | null>(null);
  readonly state = signal<LoadState>('idle');

  // ── Export ────────────────────────────────────────────────────────────────
  readonly exporting = signal(false);

  // ── Permissions ───────────────────────────────────────────────────────────
  readonly canView = computed(() =>
    this.session.hasPermission('REPORT.CASHFLOW.VIEW') || this.session.hasPermission('REPORT.VIEW'),
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
      .cashFlow(companyId, from, to, this.cmpFrom() || null, this.cmpTo() || null)
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
      .exportCashFlow(companyId, from, to, format, this.cmpFrom() || null, this.cmpTo() || null)
      .subscribe({
        next: (blob) => {
          downloadBlob(blob, `cash-flow_${from}_${to}.${format.toLowerCase()}`);
          this.exporting.set(false);
        },
        error: () => this.exporting.set(false),
      });
  }

  // ── Display helpers ────────────────────────────────────────────────────────

  /** Numeric-money guard (BigDecimal arrives as JSON number). */
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
