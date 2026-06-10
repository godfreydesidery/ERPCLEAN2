import { HttpErrorResponse } from '@angular/common/http';
import { Component, OnInit, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute } from '@angular/router';
import { SessionStore } from '../../../core/auth/session.store';
import { Company } from '../models/company.model';
import { CompanyService } from '../company/company.service';
import { OrganisationService } from '../organisation/organisation.service';
import { AccountLedgerDto, ExportFormat } from './models/reporting.model';
import { ReportingService } from './reporting.service';
import { downloadBlob } from './reporting.utils';

type LoadState = 'idle' | 'loading' | 'error' | 'forbidden';

/**
 * GL Account Ledger drill-down screen (FR-REP-04, US-REP-04).
 * Gated REPORT.LEDGER.VIEW. Export gated REPORT.EXPORT.
 * Accepts ?accountUid=, ?fromDate=, ?toDate=, ?companyId= as query params (drill-in from statements).
 * Shows opening balance, running journal lines, closing balance. Paginated.
 */
@Component({
  selector: 'app-account-ledger',
  imports: [FormsModule],
  templateUrl: './account-ledger.component.html',
  styleUrl: './account-ledger.component.scss',
})
export class AccountLedgerComponent implements OnInit {
  private readonly reportingService = inject(ReportingService);
  private readonly companyService = inject(CompanyService);
  private readonly organisationService = inject(OrganisationService);
  private readonly route = inject(ActivatedRoute);
  protected readonly session = inject(SessionStore);

  // ── Company context ────────────────────────────────────────────────────────
  readonly companies = signal<Company[]>([]);
  readonly selectedCompanyId = signal('');
  readonly companyState = signal<'loading' | 'idle' | 'error'>('loading');

  // ── Account + period selector ──────────────────────────────────────────────
  readonly accountUid = signal('');
  readonly fromDate = signal(this.firstDayOfCurrentMonth());
  readonly toDate = signal(this.today());

  // ── Pagination ────────────────────────────────────────────────────────────
  readonly page = signal(0);
  readonly pageSize = 50;

  // ── Ledger data ───────────────────────────────────────────────────────────
  readonly ledger = signal<AccountLedgerDto | null>(null);
  readonly state = signal<LoadState>('idle');

  // ── Export ────────────────────────────────────────────────────────────────
  readonly exporting = signal(false);

  // ── Permissions ───────────────────────────────────────────────────────────
  readonly canView = computed(() =>
    this.session.hasPermission('REPORT.LEDGER.VIEW') || this.session.hasPermission('REPORT.VIEW'),
  );
  readonly canExport = computed(() => this.session.hasPermission('REPORT.EXPORT'));

  readonly isEmpty = computed(() => this.state() === 'idle' && this.ledger() === null);

  readonly hasNextPage = computed(() => {
    const l = this.ledger();
    if (!l) return false;
    return (this.page() + 1) * this.pageSize < l.totalElements;
  });

  ngOnInit(): void {
    // Pre-fill from query params (drill-in from statement lines)
    const qp = this.route.snapshot.queryParamMap;
    const qAccountUid = qp.get('accountUid');
    const qFromDate = qp.get('fromDate');
    const qToDate = qp.get('toDate');
    const qCompanyId = qp.get('companyId');

    if (qAccountUid) this.accountUid.set(qAccountUid);
    if (qFromDate) this.fromDate.set(qFromDate);
    if (qToDate) this.toDate.set(qToDate);

    this.loadCompanies(qCompanyId ?? null);
  }

  private loadCompanies(preselectedCompanyId: string | null): void {
    this.companyState.set('loading');
    this.organisationService.current().subscribe({
      next: (org) => {
        this.companyService.list(org.uid).subscribe({
          next: (list) => {
            this.companies.set(list);
            this.companyState.set('idle');
            if (preselectedCompanyId) {
              this.selectedCompanyId.set(preselectedCompanyId);
            } else if (list.length > 0) {
              this.selectedCompanyId.set(list[0].id);
            }
            // Auto-run if all params are pre-filled from the drill-in
            if (this.accountUid() && this.fromDate() && this.toDate() && this.selectedCompanyId()) {
              this.run();
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
    this.ledger.set(null);
  }

  run(): void {
    const companyId = this.selectedCompanyId();
    const uid = String(this.accountUid() ?? '').trim();
    const from = String(this.fromDate() ?? '').trim();
    const to = String(this.toDate() ?? '').trim();
    if (!companyId || !uid || !from || !to) return;

    this.page.set(0);
    this.loadPage(0);
  }

  loadPage(pageNo: number): void {
    const companyId = this.selectedCompanyId();
    const uid = String(this.accountUid() ?? '').trim();
    const from = String(this.fromDate() ?? '').trim();
    const to = String(this.toDate() ?? '').trim();
    if (!companyId || !uid || !from || !to) return;

    this.state.set('loading');
    this.reportingService
      .accountLedger(companyId, uid, from, to, pageNo, this.pageSize)
      .subscribe({
        next: (dto) => {
          this.ledger.set(dto);
          this.page.set(pageNo);
          this.state.set('idle');
        },
        error: (err) =>
          this.state.set(
            err instanceof HttpErrorResponse && err.status === 403 ? 'forbidden' : 'error',
          ),
      });
  }

  prevPage(): void {
    if (this.page() > 0) this.loadPage(this.page() - 1);
  }

  nextPage(): void {
    if (this.hasNextPage()) this.loadPage(this.page() + 1);
  }

  export(format: ExportFormat): void {
    const companyId = this.selectedCompanyId();
    const uid = String(this.accountUid() ?? '').trim();
    const from = String(this.fromDate() ?? '').trim();
    const to = String(this.toDate() ?? '').trim();
    if (!companyId || !uid || !from || !to || this.exporting()) return;

    this.exporting.set(true);
    this.reportingService.exportAccountLedger(companyId, uid, from, to, format).subscribe({
      next: (blob) => {
        const name = this.ledger()?.accountCode ?? uid;
        downloadBlob(blob, `account-ledger_${name}_${from}_${to}.${format.toLowerCase()}`);
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

  runningBalanceClass(v: number | string | null | undefined): string {
    const n = +(v ?? 0);
    return n < 0 ? 'text-danger' : '';
  }

  private today(): string {
    return new Date().toISOString().slice(0, 10);
  }

  private firstDayOfCurrentMonth(): string {
    const d = new Date();
    return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-01`;
  }
}
