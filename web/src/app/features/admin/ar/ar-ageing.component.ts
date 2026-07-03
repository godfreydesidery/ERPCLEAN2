import { HttpErrorResponse } from '@angular/common/http';
import { Component, computed, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { debounceTime, distinctUntilChanged, Subject, switchMap } from 'rxjs';
import { SessionStore } from '../../../core/auth/session.store';
import { Company } from '../models/company.model';
import { CompanyService } from '../company/company.service';
import { OrganisationService } from '../organisation/organisation.service';
import { CustomerModel } from '../models/party.model';
import { CustomerService } from '../parties/customer.service';
import { ArAgeingRowDto, ArBalanceDto } from './models/ar.model';
import { ArService } from './ar.service';
import { formatMoney } from '../../../shared/money.util';

type LoadState = 'idle' | 'loading' | 'error' | 'forbidden';

/**
 * AR Ageing — company-wide ageing report across all customers, plus per-customer balance lookup.
 * Gated AR.STATEMENT.VIEW.
 */
@Component({
  selector: 'app-ar-ageing',
  imports: [FormsModule],
  templateUrl: './ar-ageing.component.html',
  styleUrl: './ar-ageing.component.scss',
})
export class ArAgeingComponent {
  private readonly arService = inject(ArService);
  private readonly companyService = inject(CompanyService);
  private readonly organisationService = inject(OrganisationService);
  private readonly customerService = inject(CustomerService);
  protected readonly session = inject(SessionStore);

  // ── Company context ────────────────────────────────────────────────────────
  readonly companies = signal<Company[]>([]);
  readonly selectedCompanyId = signal('');
  readonly companyState = signal<'loading' | 'idle' | 'error'>('loading');

  // ── Ageing data ────────────────────────────────────────────────────────────
  readonly ageingRows = signal<ArAgeingRowDto[]>([]);
  readonly state = signal<LoadState>('idle');

  // ── Balance lookup ─────────────────────────────────────────────────────────
  readonly balanceCustomerQ = signal('');
  readonly balanceCustomerResults = signal<CustomerModel[]>([]);
  readonly selectedBalanceCustomer = signal<{ uid: string; label: string } | null>(null);
  readonly balance = signal<ArBalanceDto | null>(null);
  readonly balanceState = signal<LoadState>('idle');

  // ── Permissions ────────────────────────────────────────────────────────────
  readonly canView = computed(() => this.session.hasPermission('AR.STATEMENT.VIEW'));
  readonly isEmpty = computed(() => this.state() === 'idle' && this.ageingRows().length === 0);

  private readonly balanceCustomerSearch$ = new Subject<string>();

  constructor() {
    this.balanceCustomerSearch$
      .pipe(
        debounceTime(300),
        distinctUntilChanged(),
        switchMap((q) => {
          const companyId = this.selectedCompanyId();
          if (!companyId || !q.trim()) { this.balanceCustomerResults.set([]); return []; }
          return this.customerService.list(companyId, q.trim(), 0, 10);
        }),
        takeUntilDestroyed(),
      )
      .subscribe({
        next: ({ rows }) => this.balanceCustomerResults.set(rows.filter((c) => c.status === 'ACTIVE')),
        error: () => this.balanceCustomerResults.set([]),
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
              this.loadAgeing();
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
    this.ageingRows.set([]);
    this.balance.set(null);
    this.selectedBalanceCustomer.set(null);
    this.balanceCustomerQ.set('');
    if (id) this.loadAgeing();
  }

  loadAgeing(): void {
    const companyId = this.selectedCompanyId();
    if (!companyId) return;
    this.state.set('loading');
    this.arService.getAgeing(companyId).subscribe({
      next: (rows) => { this.ageingRows.set(rows); this.state.set('idle'); },
      error: (err) =>
        this.state.set(err instanceof HttpErrorResponse && err.status === 403 ? 'forbidden' : 'error'),
    });
  }

  // ── Balance lookup ─────────────────────────────────────────────────────────

  onBalanceCustomerChange(q: string): void {
    this.balanceCustomerQ.set(q);
    if (!q.trim()) {
      this.selectedBalanceCustomer.set(null);
      this.balanceCustomerResults.set([]);
      this.balance.set(null);
    } else {
      this.selectedBalanceCustomer.set(null);
      this.balanceCustomerSearch$.next(q);
    }
  }

  selectBalanceCustomer(c: CustomerModel): void {
    this.selectedBalanceCustomer.set({ uid: c.uid, label: `${c.code} — ${c.displayName}` });
    this.balanceCustomerQ.set(`${c.code} — ${c.displayName}`);
    this.balanceCustomerResults.set([]);
    this.loadBalance(c.uid);
  }

  private loadBalance(customerUid: string): void {
    const companyId = this.selectedCompanyId();
    if (!companyId) return;
    this.balanceState.set('loading');
    this.balance.set(null);
    this.arService.getBalance(companyId, customerUid).subscribe({
      next: (b) => { this.balance.set(b); this.balanceState.set('idle'); },
      error: (err) =>
        this.balanceState.set(err instanceof HttpErrorResponse && err.status === 403 ? 'forbidden' : 'error'),
    });
  }

  // ── Display helpers ────────────────────────────────────────────────────────

  /** Coerce + format money with thousand separators (shared util). */
  readonly fmtMoney = formatMoney;
}
