import { HttpErrorResponse } from '@angular/common/http';
import { Component, computed, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { Subject, debounceTime, distinctUntilChanged, switchMap } from 'rxjs';
import { PageMeta } from '../../../core/api/api-response.model';
import { SessionStore } from '../../../core/auth/session.store';
import { Company } from '../models/company.model';
import { CompanyService } from '../company/company.service';
import { OrganisationService } from '../organisation/organisation.service';
import { CustomerModel } from '../models/party.model';
import { CustomerService } from '../parties/customer.service';
import { ArReceiptDto } from './models/ar.model';
import { ArService } from './ar.service';
import { PaginatorComponent } from '../../../shared/paginator/paginator.component';

const DEFAULT_SIZE = 20;

interface LoadTrigger {
  companyId: string;
  customerUid: string;
  page: number;
}

/**
 * Paged list of recorded AR receipts. Company-scoped, optional customer filter.
 * Each row links to the receipt view (getReceipt). Gated AR.VIEW.
 */
@Component({
  selector: 'app-ar-receipts-list',
  imports: [FormsModule, RouterLink, PaginatorComponent],
  templateUrl: './ar-receipts-list.component.html',
  styleUrl: './ar-receipts-list.component.scss',
})
export class ArReceiptsListComponent {
  private readonly arService = inject(ArService);
  private readonly companyService = inject(CompanyService);
  private readonly organisationService = inject(OrganisationService);
  private readonly customerService = inject(CustomerService);
  protected readonly session = inject(SessionStore);

  // ── Company context ────────────────────────────────────────────────────────
  readonly companies = signal<Company[]>([]);
  readonly selectedCompanyId = signal('');
  readonly companyState = signal<'loading' | 'idle' | 'error'>('loading');

  // ── List state ─────────────────────────────────────────────────────────────
  readonly rows = signal<ArReceiptDto[]>([]);
  readonly meta = signal<PageMeta>({ page: 0, size: DEFAULT_SIZE, totalElements: 0, totalPages: 0, hasNext: false });
  readonly state = signal<'loading' | 'idle' | 'error' | 'forbidden'>('idle');
  readonly currentPage = signal(0);

  // ── Customer filter picker ─────────────────────────────────────────────────
  readonly customerFilterQ = signal('');
  readonly customerFilterResults = signal<CustomerModel[]>([]);
  readonly selectedFilterCustomer = signal<{ uid: string; label: string } | null>(null);

  // ── Permissions ────────────────────────────────────────────────────────────
  readonly canView = computed(() => this.session.hasPermission('AR.VIEW'));
  readonly isEmpty = computed(() => this.state() === 'idle' && this.rows().length === 0);

  private readonly loadTrigger$ = new Subject<LoadTrigger>();
  private readonly customerSearch$ = new Subject<string>();

  constructor() {
    this.loadTrigger$
      .pipe(
        switchMap(({ companyId, customerUid, page }) => {
          if (!companyId) return [];
          this.state.set('loading');
          this.currentPage.set(page);
          return this.arService.listReceipts(companyId, customerUid || undefined, page, DEFAULT_SIZE);
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

    this.customerSearch$
      .pipe(
        debounceTime(300),
        distinctUntilChanged(),
        switchMap((q) => {
          const companyId = this.selectedCompanyId();
          if (!companyId || !q.trim()) { this.customerFilterResults.set([]); return []; }
          return this.customerService.list(companyId, q.trim(), 0, 10);
        }),
        takeUntilDestroyed(),
      )
      .subscribe({
        next: ({ rows }) => this.customerFilterResults.set(rows.filter((c) => c.status === 'ACTIVE')),
        error: () => this.customerFilterResults.set([]),
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
    this.clearCustomerFilter();
    if (id) this.load(0);
  }

  load(page: number): void {
    const companyId = this.selectedCompanyId();
    if (!companyId) return;
    this.loadTrigger$.next({
      companyId,
      customerUid: this.selectedFilterCustomer()?.uid ?? '',
      page,
    });
  }

  goToPage(page: number): void { this.load(page); }

  // ── Customer filter ────────────────────────────────────────────────────────

  onCustomerFilterChange(q: string): void {
    this.customerFilterQ.set(q);
    if (!q.trim()) {
      this.clearCustomerFilter();
    } else {
      this.selectedFilterCustomer.set(null);
      this.customerSearch$.next(q);
    }
  }

  selectFilterCustomer(c: CustomerModel): void {
    this.selectedFilterCustomer.set({ uid: c.uid, label: `${c.code} — ${c.displayName}` });
    this.customerFilterQ.set(`${c.code} — ${c.displayName}`);
    this.customerFilterResults.set([]);
    this.load(0);
  }

  clearCustomerFilter(): void {
    this.selectedFilterCustomer.set(null);
    this.customerFilterQ.set('');
    this.customerFilterResults.set([]);
    this.load(0);
  }

  // ── Display helpers ────────────────────────────────────────────────────────

  fmtMoney(v: number | string | null | undefined): string {
    const n = +(v ?? 0);
    return Number.isFinite(n) ? n.toFixed(2) : '0.00';
  }

  private messageFrom(err: unknown, fallback: string): string {
    if (err instanceof HttpErrorResponse) {
      const errors = (err.error as { errors?: string[] })?.errors;
      if (errors?.length) return errors[0];
    }
    return fallback;
  }
}
