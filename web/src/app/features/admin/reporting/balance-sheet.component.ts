import { HttpErrorResponse } from '@angular/common/http';
import { Component, OnInit, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { SessionStore } from '../../../core/auth/session.store';
import { Company } from '../models/company.model';
import { CompanyService } from '../company/company.service';
import { OrganisationService } from '../organisation/organisation.service';
import { BalanceSheetDto, ExportFormat } from './models/reporting.model';
import { ReportingService } from './reporting.service';
import { downloadBlob } from './reporting.utils';

type LoadState = 'idle' | 'loading' | 'error' | 'forbidden';

/**
 * Balance Sheet screen (FR-REP-02, US-REP-02).
 * Gated REPORT.BS.VIEW. Export gated REPORT.EXPORT.
 * As-at date + optional comparative as-at.
 * Shows current/non-current assets, liabilities, equity + balance indicator (ASSET == LIAB + EQUITY).
 */
@Component({
  selector: 'app-balance-sheet',
  imports: [FormsModule, RouterLink],
  templateUrl: './balance-sheet.component.html',
  styleUrl: './balance-sheet.component.scss',
})
export class BalanceSheetComponent implements OnInit {
  private readonly reportingService = inject(ReportingService);
  private readonly companyService = inject(CompanyService);
  private readonly organisationService = inject(OrganisationService);
  protected readonly session = inject(SessionStore);

  // ── Company context ────────────────────────────────────────────────────────
  readonly companies = signal<Company[]>([]);
  readonly selectedCompanyId = signal('');
  readonly companyState = signal<'loading' | 'idle' | 'error'>('loading');

  // ── Period selector ────────────────────────────────────────────────────────
  readonly asAtDate = signal(this.today());
  readonly compareAsAt = signal('');

  // ── Statement data ─────────────────────────────────────────────────────────
  readonly statement = signal<BalanceSheetDto | null>(null);
  readonly state = signal<LoadState>('idle');

  // ── Export ────────────────────────────────────────────────────────────────
  readonly exporting = signal(false);

  // ── Permissions ───────────────────────────────────────────────────────────
  readonly canView = computed(() =>
    this.session.hasPermission('REPORT.BS.VIEW') || this.session.hasPermission('REPORT.VIEW'),
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
    const asAt = String(this.asAtDate() ?? '').trim();
    if (!companyId || !asAt) return;

    this.state.set('loading');
    this.statement.set(null);

    this.reportingService
      .balanceSheet(companyId, asAt, this.compareAsAt() || null)
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
    const asAt = String(this.asAtDate() ?? '').trim();
    if (!companyId || !asAt || this.exporting()) return;

    this.exporting.set(true);
    this.reportingService
      .exportBalanceSheet(companyId, asAt, format, this.compareAsAt() || null)
      .subscribe({
        next: (blob) => {
          downloadBlob(blob, `balance-sheet_${asAt}.${format.toLowerCase()}`);
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
}
