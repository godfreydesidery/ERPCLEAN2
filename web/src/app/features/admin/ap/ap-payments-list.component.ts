import { HttpErrorResponse } from '@angular/common/http';
import { Component, computed, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { Subject, debounceTime, distinctUntilChanged, switchMap } from 'rxjs';
import { PageMeta } from '../../../core/api/api-response.model';
import { AlertService } from '../../../core/feedback/alert.service';
import { SessionStore } from '../../../core/auth/session.store';
import { Company } from '../models/company.model';
import { CompanyService } from '../company/company.service';
import { OrganisationService } from '../organisation/organisation.service';
import { SupplierModel } from '../models/party.model';
import { SupplierService } from '../parties/supplier.service';
import { ApPaymentDto, PaySingleBillRequest, SupplierBillDto } from './models/ap.model';
import { ApService } from './ap.service';
import { PaginatorComponent } from '../../../shared/paginator/paginator.component';

const DEFAULT_SIZE = 20;

interface LoadTrigger { companyId: string; supplierUid: string; page: number }

/**
 * AP Payments list — paged, company-scoped, optional supplier filter.
 * Each row links to payment detail. Includes inline "Pay Single Bill" form.
 * Gated AP.VIEW; pay action gated AP.PAYMENT.RUN.
 */
@Component({
  selector: 'app-ap-payments-list',
  imports: [FormsModule, RouterLink, PaginatorComponent],
  templateUrl: './ap-payments-list.component.html',
  styleUrl: './ap-payments-list.component.scss',
})
export class ApPaymentsListComponent {
  private readonly apService = inject(ApService);
  private readonly companyService = inject(CompanyService);
  private readonly organisationService = inject(OrganisationService);
  private readonly supplierService = inject(SupplierService);
  private readonly alerts = inject(AlertService);
  protected readonly session = inject(SessionStore);

  // ── Company context ────────────────────────────────────────────────────────
  readonly companies = signal<Company[]>([]);
  readonly selectedCompanyId = signal('');
  readonly companyState = signal<'loading' | 'idle' | 'error'>('loading');

  // ── List state ─────────────────────────────────────────────────────────────
  readonly rows = signal<ApPaymentDto[]>([]);
  readonly meta = signal<PageMeta>({ page: 0, size: DEFAULT_SIZE, totalElements: 0, totalPages: 0, hasNext: false });
  readonly state = signal<'loading' | 'idle' | 'error' | 'forbidden'>('idle');
  readonly currentPage = signal(0);

  // ── Supplier filter ────────────────────────────────────────────────────────
  readonly supplierFilterQ = signal('');
  readonly supplierFilterResults = signal<SupplierModel[]>([]);
  readonly selectedFilterSupplier = signal<{ uid: string; label: string } | null>(null);

  // ── Pay single bill form ───────────────────────────────────────────────────
  readonly showPayForm = signal(false);
  // Bill picker (search open bills)
  readonly payBillQ = signal('');
  readonly payBillResults = signal<SupplierBillDto[]>([]);
  readonly payBillSearching = signal(false);
  readonly selectedPayBill = signal<SupplierBillDto | null>(null);
  readonly payAmount = signal('');
  readonly payDate = signal('');
  readonly payTender = signal('BANK_TRANSFER');
  readonly payRef = signal('');
  readonly paying = signal(false);
  readonly payError = signal<string | null>(null);

  // ── Permissions ────────────────────────────────────────────────────────────
  readonly canView = computed(() => this.session.hasPermission('AP.VIEW'));
  readonly canPay = computed(() => this.session.hasPermission('AP.PAYMENT.RUN'));
  readonly isEmpty = computed(() => this.state() === 'idle' && this.rows().length === 0);

  private readonly loadTrigger$ = new Subject<LoadTrigger>();
  private readonly supplierSearch$ = new Subject<string>();
  private readonly billSearch$ = new Subject<string>();

  constructor() {
    this.loadTrigger$
      .pipe(
        switchMap(({ companyId, supplierUid, page }) => {
          if (!companyId) return [];
          this.state.set('loading');
          this.currentPage.set(page);
          return this.apService.listPayments(companyId, supplierUid || undefined, page, DEFAULT_SIZE);
        }),
        takeUntilDestroyed(),
      )
      .subscribe({
        next: ({ rows, meta }) => { this.rows.set(rows); this.meta.set(meta); this.state.set('idle'); },
        error: (err) =>
          this.state.set(err instanceof HttpErrorResponse && err.status === 403 ? 'forbidden' : 'error'),
      });

    this.supplierSearch$
      .pipe(
        debounceTime(300),
        distinctUntilChanged(),
        switchMap((q) => {
          const companyId = this.selectedCompanyId();
          if (!companyId || !q.trim()) { this.supplierFilterResults.set([]); return []; }
          return this.supplierService.list(companyId, q.trim(), 0, 10);
        }),
        takeUntilDestroyed(),
      )
      .subscribe({
        next: ({ rows }) => this.supplierFilterResults.set(rows.filter((s) => s.status === 'ACTIVE')),
        error: () => this.supplierFilterResults.set([]),
      });

    this.billSearch$
      .pipe(
        debounceTime(300),
        distinctUntilChanged(),
        switchMap((q) => {
          const companyId = this.selectedCompanyId();
          if (!companyId || !q.trim()) { this.payBillResults.set([]); return []; }
          return this.apService.listBills(companyId, undefined, undefined, 0, 20);
        }),
        takeUntilDestroyed(),
      )
      .subscribe({
        next: ({ rows }) => {
          const q = this.payBillQ().toLowerCase();
          this.payBillResults.set(
            rows.filter((b) =>
              b.status !== 'PAID' &&
              (b.billNumber.toLowerCase().includes(q) ||
               b.supplierInvoiceNo.toLowerCase().includes(q)),
            ),
          );
        },
        error: () => this.payBillResults.set([]),
      });

    this.payDate.set(new Date().toISOString().slice(0, 10));
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
            if (list.length > 0) { this.selectedCompanyId.set(list[0].id); this.load(0); }
          },
          error: () => this.companyState.set('error'),
        });
      },
      error: () => this.companyState.set('error'),
    });
  }

  onCompanyChange(id: string): void {
    this.selectedCompanyId.set(id);
    this.clearSupplierFilter();
    if (id) this.load(0);
  }

  load(page: number): void {
    const companyId = this.selectedCompanyId();
    if (!companyId) return;
    this.loadTrigger$.next({ companyId, supplierUid: this.selectedFilterSupplier()?.uid ?? '', page });
  }

  goToPage(page: number): void { this.load(page); }

  // ── Supplier filter ────────────────────────────────────────────────────────

  onSupplierFilterChange(q: string): void {
    this.supplierFilterQ.set(q);
    if (!q.trim()) { this.clearSupplierFilter(); } else {
      this.selectedFilterSupplier.set(null);
      this.supplierSearch$.next(q);
    }
  }

  selectFilterSupplier(s: SupplierModel): void {
    this.selectedFilterSupplier.set({ uid: s.uid, label: `${s.code} — ${s.displayName}` });
    this.supplierFilterQ.set(`${s.code} — ${s.displayName}`);
    this.supplierFilterResults.set([]);
    this.load(0);
  }

  clearSupplierFilter(): void {
    this.selectedFilterSupplier.set(null);
    this.supplierFilterQ.set('');
    this.supplierFilterResults.set([]);
    this.load(0);
  }

  // ── Pay single bill form ───────────────────────────────────────────────────

  openPayForm(): void {
    this.showPayForm.set(true);
    this.payError.set(null);
    this.selectedPayBill.set(null);
    this.payBillQ.set('');
    this.payBillResults.set([]);
    this.payAmount.set('');
    this.payRef.set('');
    this.payTender.set('BANK_TRANSFER');
    this.payDate.set(new Date().toISOString().slice(0, 10));
  }

  closePayForm(): void { this.showPayForm.set(false); this.payError.set(null); }

  onBillSearchChange(q: string): void {
    this.payBillQ.set(q);
    this.selectedPayBill.set(null);
    this.billSearch$.next(q);
  }

  selectPayBill(b: SupplierBillDto): void {
    this.selectedPayBill.set(b);
    this.payBillQ.set(`${b.billNumber} — ${b.supplierInvoiceNo}`);
    this.payBillResults.set([]);
    this.payAmount.set(String(+(b.outstandingAmount ?? 0)));
  }

  submitPaySingle(): void {
    const bill = this.selectedPayBill();
    const company = this.companies().find((c) => c.id === this.selectedCompanyId());
    if (!company) { this.payError.set('Could not resolve company.'); return; }
    if (!bill) { this.payError.set('Select a bill.'); return; }
    const amount = String(this.payAmount() ?? '').trim();
    if (!amount || isNaN(+amount) || +amount <= 0) {
      this.payError.set('Enter a valid positive amount.');
      return;
    }
    const date = String(this.payDate() ?? '').trim();
    if (!date) { this.payError.set('Payment date is required.'); return; }

    this.paying.set(true);
    this.payError.set(null);

    const request: PaySingleBillRequest = {
      companyUid: company.uid,
      supplierBillUid: bill.uid,
      amount,
      paymentDate: date,
      tenderType: this.payTender(),
      bankReference: this.payRef().trim() || null,
    };

    this.apService.paySingle(request).subscribe({
      next: () => {
        this.paying.set(false);
        this.closePayForm();
        this.alerts.success('Payment recorded', bill.billNumber);
        this.load(this.currentPage());
      },
      error: (err) => {
        this.payError.set(this.messageFrom(err, 'Could not record payment.'));
        this.paying.set(false);
      },
    });
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
