import { HttpErrorResponse } from '@angular/common/http';
import { Component, computed, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { Subject, switchMap } from 'rxjs';
import { PageMeta } from '../../../core/api/api-response.model';
import { SessionStore } from '../../../core/auth/session.store';
import { Company } from '../models/company.model';
import { CompanyService } from '../company/company.service';
import { OrganisationService } from '../organisation/organisation.service';
import { AccountDto, JournalEntryDto, JournalFilter } from './models/gl.model';
import { GlService } from './gl.service';
import { PaginatorComponent } from '../../../shared/paginator/paginator.component';

const DEFAULT_SIZE = 20;

/** Source-type filter choices (the backend JournalSourceType names). */
export const JOURNAL_SOURCE_TYPES: ReadonlyArray<{ value: string; label: string }> = [
  { value: 'MANUAL', label: 'Manual' },
  { value: 'SALES', label: 'Sales' },
  { value: 'SALES_REVERSAL', label: 'Sales void' },
  { value: 'COGS', label: 'Cost of sales' },
  { value: 'STOCK_RECEIPT', label: 'Goods receipt' },
  { value: 'STOCK_ADJUSTMENT', label: 'Stock adjustment' },
  { value: 'OPENING_INVENTORY', label: 'Opening stock' },
  { value: 'OPENING_BALANCE', label: 'Opening balance' },
  { value: 'AR_RECEIPT', label: 'Customer receipt' },
  { value: 'AR_CREDIT_NOTE', label: 'Customer credit note' },
  { value: 'AP_BILL', label: 'Supplier bill' },
  { value: 'AP_PAYMENT', label: 'Supplier payment' },
  { value: 'AP_DEBIT_NOTE', label: 'Supplier debit note' },
  { value: 'CASH_DIRECT', label: 'Cash entry' },
  { value: 'CASH_TRANSFER', label: 'Cash transfer' },
  { value: 'VAT_RETURN', label: 'VAT return' },
  { value: 'LANDED_COST', label: 'Landed cost' },
  { value: 'PURCHASE_RETURN', label: 'Purchase return' },
  { value: 'POS_VARIANCE', label: 'POS variance' },
  { value: 'PAYROLL', label: 'Payroll' },
  { value: 'DEPRECIATION', label: 'Depreciation' },
  { value: 'FX_REVALUATION', label: 'FX revaluation' },
  { value: 'YEAR_END_CLOSE', label: 'Year-end close' },
];

/**
 * Paged list of journal entries scoped to the active company.
 * Gated GL.VIEW. SALES auto-posted entries visible read-only (sourceType SALES badge).
 * Navigate to detail via /admin/gl/journals/uid/:uid.
 */
@Component({
  selector: 'app-journal-entry-list',
  imports: [FormsModule, RouterLink, PaginatorComponent],
  templateUrl: './journal-entry-list.component.html',
  styleUrl: './journal-entry-list.component.scss',
})
export class JournalEntryListComponent {
  private readonly glService = inject(GlService);
  private readonly companyService = inject(CompanyService);
  private readonly organisationService = inject(OrganisationService);
  protected readonly session = inject(SessionStore);

  // ── Company context ────────────────────────────────────────────────────────
  readonly companies = signal<Company[]>([]);
  readonly selectedCompanyId = signal('');
  readonly companyState = signal<'loading' | 'idle' | 'error'>('loading');

  // ── List state ─────────────────────────────────────────────────────────────
  readonly rows = signal<JournalEntryDto[]>([]);
  readonly meta = signal<PageMeta>({ page: 0, size: DEFAULT_SIZE, totalElements: 0, totalPages: 0, hasNext: false });
  readonly state = signal<'loading' | 'idle' | 'error' | 'forbidden'>('idle');
  readonly currentPage = signal(0);

  // ── Filters (ACC-19) ───────────────────────────────────────────────────────
  readonly fromDate = signal('');
  readonly toDate = signal('');
  readonly sourceType = signal('');
  readonly accountUid = signal('');
  readonly searchText = signal('');
  readonly accounts = signal<AccountDto[]>([]);
  protected readonly sourceTypes = JOURNAL_SOURCE_TYPES;
  readonly hasFilter = computed(() =>
    !!(this.fromDate() || this.toDate() || this.sourceType() || this.accountUid() || this.searchText().trim()));

  readonly canPost = computed(() => this.session.hasPermission('GL.POST'));
  readonly isEmpty = computed(() => this.state() === 'idle' && this.rows().length === 0);

  private readonly loadTrigger$ = new Subject<number>();

  constructor() {
    this.loadTrigger$
      .pipe(
        switchMap((page) => {
          const companyId = this.selectedCompanyId();
          if (!companyId) return [];
          this.state.set('loading');
          this.currentPage.set(page);
          return this.glService.listJournals(companyId, page, DEFAULT_SIZE, this.currentFilter());
        }),
        takeUntilDestroyed(),
      )
      .subscribe({
        next: ({ rows, meta }) => {
          this.rows.set(rows);
          this.meta.set(meta);
          this.state.set('idle');
        },
        error: (err) =>
          this.state.set(err instanceof HttpErrorResponse && err.status === 403 ? 'forbidden' : 'error'),
      });

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
              this.loadAccounts();
              this.load(0);
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
    this.accountUid.set('');
    if (id) {
      this.loadAccounts();
      this.load(0);
    }
  }

  /** The account filter's choices (all active accounts of the company). */
  private loadAccounts(): void {
    const companyId = this.selectedCompanyId();
    if (!companyId) return;
    this.glService.listAllActiveAccounts(companyId).subscribe({
      next: (list) => this.accounts.set(list),
      error: () => this.accounts.set([]),
    });
  }

  private currentFilter(): JournalFilter {
    return {
      from: this.fromDate() || undefined,
      to: this.toDate() || undefined,
      sourceType: this.sourceType() || undefined,
      accountUid: this.accountUid() || undefined,
      q: this.searchText().trim() || undefined,
    };
  }

  applyFilters(): void {
    this.load(0);
  }

  clearFilters(): void {
    this.fromDate.set('');
    this.toDate.set('');
    this.sourceType.set('');
    this.accountUid.set('');
    this.searchText.set('');
    this.load(0);
  }

  /** The document a person recognises: its number when known, else a shortened machine ref. */
  documentLabel(e: JournalEntryDto): string {
    if (e.documentRef) return e.documentRef;
    if (!e.sourceRef) return '—';
    return e.sourceRef.length > 12 ? e.sourceRef.slice(0, 12) + '…' : e.sourceRef;
  }

  load(page: number): void {
    if (!this.selectedCompanyId()) return;
    this.loadTrigger$.next(page);
  }

  goToPage(page: number): void { this.load(page); }

  prevPage(): void {
    const p = this.currentPage();
    if (p > 0) this.load(p - 1);
  }

  nextPage(): void {
    if (this.meta().hasNext) this.load(this.currentPage() + 1);
  }

  /** Compute the total debits for a journal entry (display-only, server is authoritative). */
  entryTotal(entry: JournalEntryDto): string {
    const total = (entry.lines ?? []).reduce((sum, l) => sum + parseFloat(l.debitAmount || '0'), 0);
    return total.toFixed(2);
  }

}
