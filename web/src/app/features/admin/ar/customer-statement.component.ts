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
import {
  ArAgeingBucketDto,
  ArInvoiceDto,
  ArReceiptDto,
  ArStatementDto,
  AgeingBucket,
} from './models/ar.model';
import { ArService } from './ar.service';

type LoadState = 'idle' | 'loading' | 'error' | 'forbidden';

const BUCKET_ORDER: AgeingBucket[] = ['CURRENT', 'DAYS_1_30', 'DAYS_31_60', 'DAYS_61_90', 'DAYS_91_PLUS'];

const BUCKET_LABEL: Record<AgeingBucket, string> = {
  CURRENT: 'Current',
  DAYS_1_30: '1 – 30 days',
  DAYS_31_60: '31 – 60 days',
  DAYS_61_90: '61 – 90 days',
  DAYS_91_PLUS: '90+ days',
};

/**
 * Customer Statement screen. Gated AR.STATEMENT.VIEW.
 * Pick company + customer → GET /ar/statement → display:
 *  - totalOutstanding headline
 *  - ageing buckets table
 *  - open items table
 *  - recent receipts table
 *
 * All money arrives as number|string on the wire; coerce with +v throughout.
 */
@Component({
  selector: 'app-customer-statement',
  imports: [FormsModule],
  templateUrl: './customer-statement.component.html',
  styleUrl: './customer-statement.component.scss',
})
export class CustomerStatementComponent {
  private readonly arService = inject(ArService);
  private readonly companyService = inject(CompanyService);
  private readonly organisationService = inject(OrganisationService);
  private readonly customerService = inject(CustomerService);
  protected readonly session = inject(SessionStore);

  // ── Company context ────────────────────────────────────────────────────────
  readonly companies = signal<Company[]>([]);
  readonly selectedCompanyId = signal('');
  readonly companyState = signal<'loading' | 'idle' | 'error'>('loading');

  // ── Customer picker ────────────────────────────────────────────────────────
  readonly customerSearchQ = signal('');
  readonly customerResults = signal<CustomerModel[]>([]);
  readonly selectedCustomer = signal<{ uid: string; label: string } | null>(null);

  // ── Statement data ─────────────────────────────────────────────────────────
  readonly statement = signal<ArStatementDto | null>(null);
  readonly state = signal<LoadState>('idle');

  // ── Permissions ────────────────────────────────────────────────────────────
  readonly canView = computed(() => this.session.hasPermission('AR.STATEMENT.VIEW'));

  // ── Derived display ────────────────────────────────────────────────────────

  /** Ageing buckets sorted by canonical bucket order. */
  readonly sortedAgeing = computed<ArAgeingBucketDto[]>(() => {
    const buckets = this.statement()?.ageing ?? [];
    return [...buckets].sort((a, b) =>
      BUCKET_ORDER.indexOf(a.bucket) - BUCKET_ORDER.indexOf(b.bucket),
    );
  });

  readonly openItems = computed<ArInvoiceDto[]>(() => this.statement()?.openItems ?? []);
  readonly recentReceipts = computed<ArReceiptDto[]>(() => this.statement()?.recentReceipts ?? []);

  readonly totalOutstanding = computed(() => +(this.statement()?.totalOutstanding ?? 0));

  readonly isEmpty = computed(() => this.state() === 'idle' && this.statement() === null);

  readonly bucketLabel = (bucket: AgeingBucket): string => BUCKET_LABEL[bucket] ?? bucket;

  private readonly customerSearch$ = new Subject<string>();

  constructor() {
    this.customerSearch$
      .pipe(
        debounceTime(300),
        distinctUntilChanged(),
        switchMap((q) => {
          const companyId = this.selectedCompanyId();
          if (!companyId || !q.trim()) {
            this.customerResults.set([]);
            return [];
          }
          return this.customerService.list(companyId, q.trim(), 0, 10);
        }),
        takeUntilDestroyed(),
      )
      .subscribe({
        next: ({ rows }) => this.customerResults.set(rows.filter((c) => c.status === 'ACTIVE')),
        error: () => this.customerResults.set([]),
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
    this.selectedCustomer.set(null);
    this.customerSearchQ.set('');
    this.statement.set(null);
    this.state.set('idle');
  }

  onCustomerSearchChange(q: string): void {
    this.customerSearchQ.set(q);
    if (!q.trim()) {
      this.selectedCustomer.set(null);
      this.customerResults.set([]);
      this.statement.set(null);
      this.state.set('idle');
      return;
    }
    this.selectedCustomer.set(null);
    this.customerSearch$.next(q);
  }

  selectCustomer(c: CustomerModel): void {
    this.selectedCustomer.set({ uid: c.uid, label: `${c.code} — ${c.displayName}` });
    this.customerSearchQ.set(`${c.code} — ${c.displayName}`);
    this.customerResults.set([]);
    this.loadStatement(c.uid);
  }

  private loadStatement(customerUid: string): void {
    const companyId = this.selectedCompanyId();
    if (!companyId) return;
    this.state.set('loading');

    this.arService.getStatement(companyId, customerUid).subscribe({
      next: (data) => {
        this.statement.set(data);
        this.state.set('idle');
      },
      error: (err) =>
        this.state.set(err instanceof HttpErrorResponse && err.status === 403 ? 'forbidden' : 'error'),
    });
  }

  refresh(): void {
    const c = this.selectedCustomer();
    if (c) this.loadStatement(c.uid);
  }

  // ── Display helpers ────────────────────────────────────────────────────────

  /** Coerce money — number or string on wire — to display string. */
  fmtMoney(v: number | string | null | undefined): string {
    const n = +(v ?? 0);
    return Number.isFinite(n) ? n.toFixed(2) : '0.00';
  }

  /** Ageing bar segment colour — returns a CSS variable string for [style.background]. */
  bucketBarColor(bucket: AgeingBucket): string {
    switch (bucket) {
      case 'CURRENT':    return 'var(--erp-ok)';
      case 'DAYS_1_30':  return 'var(--erp-warn)';
      case 'DAYS_31_60': return 'var(--erp-warn)';
      case 'DAYS_61_90': return 'var(--erp-danger)';
      case 'DAYS_91_PLUS': return 'var(--erp-danger)';
      default:           return 'var(--erp-neutral)';
    }
  }

}
