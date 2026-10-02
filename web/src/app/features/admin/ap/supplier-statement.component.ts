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
  ApReconciliationDto,
  SupplierBillDto,
} from './models/ap.model';
import { ApService } from './ap.service';
import { AgeingCurrencyGroup, groupAgeingByCurrency } from '../../../shared/ageing-currency.util';
import { ExportFormat } from '../reporting/models/reporting.model';
import { downloadBlob } from '../reporting/reporting.utils';
import { exportErrorMessage, firstOfMonthIso, todayIso } from '../reporting/ledger-export.util';

type LoadState = 'idle' | 'loading' | 'error' | 'forbidden';

const BUCKET_ORDER: AgeingBucket[] = [
  'CURRENT', 'D1_30', 'D31_60', 'D61_90', 'D90_PLUS',
];

const BUCKET_LABEL: Record<AgeingBucket, string> = {
  CURRENT:      'Current',
  D1_30:    '1 – 30 days',
  D31_60:   '31 – 60 days',
  D61_90:   '61 – 90 days',
  D90_PLUS: '90+ days',
};

/**
 * Supplier Statement screen. Gated AP.VIEW.
 * Pick company + supplier → load balance + ageing + open bills.
 * The AP-to-GL reconciliation is company-wide, so it loads with the company — before any supplier
 * is picked — and says plainly whether the sub-ledger agrees with the GL control account.
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
  readonly reconciliation = signal<ApReconciliationDto | null>(null);
  readonly reconState = signal<LoadState>('idle');
  readonly state = signal<LoadState>('idle');

  // ── Printable statement (export) ───────────────────────────────────────────
  /** Statement period. An empty From runs the statement from the supplier's first transaction. */
  readonly exportFrom = signal(firstOfMonthIso());
  readonly exportTo = signal(todayIso());
  readonly exporting = signal(false);
  readonly exportError = signal<string | null>(null);

  // ── Permissions ────────────────────────────────────────────────────────────
  readonly canView = computed(() => this.session.hasPermission('AP.VIEW'));
  /** The export endpoints also require REPORT.EXPORT server-side — distinct from the view code. */
  readonly canExport = computed(() => this.session.hasPermission('REPORT.EXPORT'));

  /** |difference| below half a cent counts as agreeing (rounding on the two sides). */
  readonly reconDifference = computed(() => +(this.reconciliation()?.difference ?? 0));
  readonly absDifference = computed(() => Math.abs(this.reconDifference()));
  readonly reconciled = computed(
    () => this.reconciliation() !== null && Math.abs(this.reconDifference()) < 0.005,
  );

  // ── Derived ────────────────────────────────────────────────────────────────

  /** Ageing, one five-bucket group per currency (base first) — never summed across currencies. */
  readonly ageingGroups = computed<AgeingCurrencyGroup<ApAgeingRowDto>[]>(() =>
    groupAgeingByCurrency(this.ageing(), BUCKET_ORDER),
  );

  readonly multiCurrency = computed(() => this.ageingGroups().length > 1);

  /** The base-currency buckets (the server lists base first) — drives the headers and the bar. */
  readonly sortedAgeing = computed<ApAgeingRowDto[]>(() => this.ageingGroups()[0]?.buckets ?? []);

  /** Total of the base-currency buckets — the bar's denominator. */
  readonly baseAgeingTotal = computed(() => this.ageingGroups()[0]?.total ?? 0);

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
            if (list.length > 0) {
              this.selectedCompanyId.set(list[0].id);
              this.loadReconciliation(list[0].id);
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
    this.selectedSupplier.set(null);
    this.supplierSearchQ.set('');
    this.clearData();
    this.state.set('idle');
    this.reconciliation.set(null);
    if (id) this.loadReconciliation(id);
  }

  /** Sub-ledger vs GL 2100 for the whole company (same gate as the screen: AP.VIEW). */
  loadReconciliation(companyId: string = this.selectedCompanyId()): void {
    if (!companyId) return;
    this.reconState.set('loading');
    this.apService.getReconciliation(companyId).subscribe({
      next: (r) => { this.reconciliation.set(r); this.reconState.set('idle'); },
      error: (err) =>
        this.reconState.set(err instanceof HttpErrorResponse && err.status === 403 ? 'forbidden' : 'error'),
    });
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

    // Load balance, ageing and open bills in parallel (the reconciliation is company-wide and
    // loads with the company).
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
        this.openBills.set(rows.filter((b) => b.status !== 'PAID'));
        billsDone = true;
        checkDone();
      },
      error: handleError,
    });
  }

  refresh(): void {
    const s = this.selectedSupplier();
    if (s) this.loadStatement(s.uid);
    this.loadReconciliation();
  }

  /** Download the supplier statement (balance b/f, movements with running balance, closing). */
  exportStatement(format: ExportFormat): void {
    const s = this.selectedSupplier();
    const companyId = this.selectedCompanyId();
    if (!s || !companyId || this.exporting()) return;
    const from = String(this.exportFrom() ?? '').trim();
    const to = String(this.exportTo() ?? '').trim();
    this.exporting.set(true);
    this.exportError.set(null);
    this.apService.exportStatement(companyId, s.uid, from, to, format).subscribe({
      next: (blob) => {
        downloadBlob(blob, `supplier-statement_${to || 'today'}.${format.toLowerCase()}`);
        this.exporting.set(false);
      },
      error: (err) => {
        this.exportError.set(exportErrorMessage(err));
        this.exporting.set(false);
      },
    });
  }

  /** Download this supplier's ageing buckets as at today. */
  exportAgeing(format: ExportFormat): void {
    const s = this.selectedSupplier();
    const companyId = this.selectedCompanyId();
    if (!s || !companyId || this.exporting()) return;
    this.exporting.set(true);
    this.exportError.set(null);
    this.apService.exportAgeing(companyId, s.uid, format).subscribe({
      next: (blob) => {
        downloadBlob(blob, `supplier-ageing_${todayIso()}.${format.toLowerCase()}`);
        this.exporting.set(false);
      },
      error: (err) => {
        this.exportError.set(exportErrorMessage(err));
        this.exporting.set(false);
      },
    });
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
      case 'D1_30':    return 'text-bg-warning';
      case 'D31_60':   return 'text-bg-orange';
      case 'D61_90':   return 'text-bg-danger';
      case 'D90_PLUS': return 'text-bg-dark';
      default:             return 'text-bg-secondary';
    }
  }

}
