import { HttpErrorResponse } from '@angular/common/http';
import { Component, computed, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { debounceTime, distinctUntilChanged, Subject, switchMap } from 'rxjs';
import { SessionStore } from '../../../core/auth/session.store';
import { Company } from '../models/company.model';
import { CompanyService } from '../company/company.service';
import { OrganisationService } from '../organisation/organisation.service';
import { SupplierModel } from '../models/party.model';
import { SupplierService } from '../parties/supplier.service';
import {
  AgeingBucket,
  ApAgeingRowDto,
  ApBalanceDto,
  SupplierBillDto,
} from './models/ap.model';
import { ApService } from './ap.service';

type LoadState = 'idle' | 'loading' | 'error' | 'forbidden';

const BUCKET_ORDER: AgeingBucket[] = [
  'CURRENT', 'DAYS_1_30', 'DAYS_31_60', 'DAYS_61_90', 'DAYS_91_PLUS',
];

const BUCKET_LABEL: Record<AgeingBucket, string> = {
  CURRENT:      'Current',
  DAYS_1_30:    '1 – 30 days',
  DAYS_31_60:   '31 – 60 days',
  DAYS_61_90:   '61 – 90 days',
  DAYS_91_PLUS: '90+ days',
};

/**
 * Supplier Statement screen. Gated AP.VIEW.
 * Pick company + supplier → load balance + ageing + open bills.
 *
 * Money arrives as number|string on wire; coerce with +v throughout.
 */
@Component({
  selector: 'app-supplier-statement',
  imports: [FormsModule],
  templateUrl: './supplier-statement.component.html',
  styleUrl: './supplier-statement.component.scss',
})
export class SupplierStatementComponent {
  private readonly apService = inject(ApService);
  private readonly companyService = inject(CompanyService);
  private readonly organisationService = inject(OrganisationService);
  private readonly supplierService = inject(SupplierService);
  protected readonly session = inject(SessionStore);

  // ── Company context ────────────────────────────────────────────────────────
  readonly companies = signal<Company[]>([]);
  readonly selectedCompanyId = signal('');
  readonly companyState = signal<'loading' | 'idle' | 'error'>('loading');

  // ── Supplier picker ────────────────────────────────────────────────────────
  readonly supplierSearchQ = signal('');
  readonly supplierResults = signal<SupplierModel[]>([]);
  readonly selectedSupplier = signal<{ uid: string; label: string } | null>(null);

  // ── Statement data ─────────────────────────────────────────────────────────
  readonly balance = signal<ApBalanceDto | null>(null);
  readonly ageing = signal<ApAgeingRowDto[]>([]);
  readonly openBills = signal<SupplierBillDto[]>([]);
  readonly state = signal<LoadState>('idle');

  // ── Permissions ────────────────────────────────────────────────────────────
  readonly canView = computed(() => this.session.hasPermission('AP.VIEW'));

  // ── Derived ────────────────────────────────────────────────────────────────

  readonly sortedAgeing = computed<ApAgeingRowDto[]>(() =>
    [...this.ageing()].sort((a, b) =>
      BUCKET_ORDER.indexOf(a.bucket) - BUCKET_ORDER.indexOf(b.bucket),
    ),
  );

  readonly outstandingBalance = computed(() => +(this.balance()?.outstandingBalance ?? 0));

  readonly isEmpty = computed(() => this.state() === 'idle' && this.balance() === null);

  readonly bucketLabel = (bucket: AgeingBucket): string => BUCKET_LABEL[bucket] ?? bucket;

  private readonly supplierSearch$ = new Subject<string>();

  constructor() {
    this.supplierSearch$
      .pipe(
        debounceTime(300),
        distinctUntilChanged(),
        switchMap((q) => {
          const companyId = this.selectedCompanyId();
          if (!companyId || !q.trim()) {
            this.supplierResults.set([]);
            return [];
          }
          return this.supplierService.list(companyId, q.trim(), 0, 10);
        }),
        takeUntilDestroyed(),
      )
      .subscribe({
        next: ({ rows }) => this.supplierResults.set(rows.filter((s) => s.status === 'ACTIVE')),
        error: () => this.supplierResults.set([]),
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
            if (list.length > 0) this.selectedCompanyId.set(list[0].id);
          },
          error: () => this.companyState.set('error'),
        });
      },
      error: () => this.companyState.set('error'),
    });
  }

  onCompanyChange(id: string): void {
    this.selectedCompanyId.set(id);
    this.selectedSupplier.set(null);
    this.supplierSearchQ.set('');
    this.clearData();
    this.state.set('idle');
  }

  onSupplierSearchChange(q: string): void {
    this.supplierSearchQ.set(q);
    if (!q.trim()) {
      this.selectedSupplier.set(null);
      this.supplierResults.set([]);
      this.clearData();
      this.state.set('idle');
      return;
    }
    this.selectedSupplier.set(null);
    this.supplierSearch$.next(q);
  }

  selectSupplier(s: SupplierModel): void {
    this.selectedSupplier.set({ uid: s.uid, label: `${s.code} — ${s.displayName}` });
    this.supplierSearchQ.set(`${s.code} — ${s.displayName}`);
    this.supplierResults.set([]);
    this.loadStatement(s.uid);
  }

  private clearData(): void {
    this.balance.set(null);
    this.ageing.set([]);
    this.openBills.set([]);
  }

  private loadStatement(supplierUid: string): void {
    const companyId = this.selectedCompanyId();
    if (!companyId) return;
    this.state.set('loading');
    this.clearData();

    // Load balance, ageing, and open bills in parallel.
    let balanceDone = false;
    let ageingDone = false;
    let billsDone = false;
    let errored = false;

    const checkDone = () => {
      if (!errored && balanceDone && ageingDone && billsDone) {
        this.state.set('idle');
      }
    };

    const handleError = (err: unknown) => {
      if (!errored) {
        errored = true;
        this.state.set(
          err instanceof HttpErrorResponse && err.status === 403 ? 'forbidden' : 'error',
        );
      }
    };

    this.apService.getBalance(companyId, supplierUid).subscribe({
      next: (b) => { this.balance.set(b); balanceDone = true; checkDone(); },
      error: handleError,
    });

    this.apService.getAgeing(companyId, supplierUid).subscribe({
      next: (rows) => { this.ageing.set(rows); ageingDone = true; checkDone(); },
      error: handleError,
    });

    this.apService.listBills(companyId, supplierUid, undefined, 0, 100).subscribe({
      next: ({ rows }) => {
        this.openBills.set(
          rows.filter((b) => b.status !== 'PAID'),
        );
        billsDone = true;
        checkDone();
      },
      error: handleError,
    });
  }

  refresh(): void {
    const s = this.selectedSupplier();
    if (s) this.loadStatement(s.uid);
  }

  // ── Display helpers ────────────────────────────────────────────────────────

  /** Coerce money — number or string on wire — to display string. */
  fmtMoney(v: number | string | null | undefined): string {
    const n = +(v ?? 0);
    return Number.isFinite(n) ? n.toFixed(2) : '0.00';
  }

  bucketBadgeClass(bucket: AgeingBucket): string {
    switch (bucket) {
      case 'CURRENT':      return 'text-bg-success';
      case 'DAYS_1_30':    return 'text-bg-warning';
      case 'DAYS_31_60':   return 'text-bg-orange';
      case 'DAYS_61_90':   return 'text-bg-danger';
      case 'DAYS_91_PLUS': return 'text-bg-dark';
      default:             return 'text-bg-secondary';
    }
  }

  billStatusBadgeClass(status: string): string {
    switch (status) {
      case 'DRAFT':          return 'text-bg-secondary';
      case 'MATCHED':        return 'text-bg-info';
      case 'HELD':           return 'text-bg-danger';
      case 'APPROVED':       return 'text-bg-primary';
      case 'PARTIALLY_PAID': return 'text-bg-warning';
      case 'PAID':           return 'text-bg-success';
      default:               return 'text-bg-light border';
    }
  }
}
