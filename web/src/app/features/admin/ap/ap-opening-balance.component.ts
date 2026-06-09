import { HttpErrorResponse } from '@angular/common/http';
import { Component, computed, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { debounceTime, distinctUntilChanged, Subject, switchMap } from 'rxjs';
import { AlertService } from '../../../core/feedback/alert.service';
import { SessionStore } from '../../../core/auth/session.store';
import { Company } from '../models/company.model';
import { CompanyService } from '../company/company.service';
import { OrganisationService } from '../organisation/organisation.service';
import { SupplierModel } from '../models/party.model';
import { SupplierService } from '../parties/supplier.service';
import { SetApOpeningBalanceRequest, SupplierBillDto } from './models/ap.model';
import { ApService } from './ap.service';

/**
 * Set AP Opening Balance. Gated AP.OPENING.SET.
 * Form: supplier, grossAmount, currency, billDate, dueDate, supplierInvoiceNo (optional).
 * Mirrors AR ar-opening-balance.component exactly.
 */
@Component({
  selector: 'app-ap-opening-balance',
  imports: [FormsModule, RouterLink],
  templateUrl: './ap-opening-balance.component.html',
  styleUrl: './ap-opening-balance.component.scss',
})
export class ApOpeningBalanceComponent {
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

  // ── Supplier picker ────────────────────────────────────────────────────────
  readonly supplierSearchQ = signal('');
  readonly supplierResults = signal<SupplierModel[]>([]);
  readonly selectedSupplier = signal<{ uid: string; label: string } | null>(null);

  // ── Form fields ────────────────────────────────────────────────────────────
  readonly grossAmount = signal('');
  readonly currency = signal('TZS');
  readonly billDate = signal('');
  readonly dueDate = signal('');
  readonly supplierInvoiceNo = signal('');

  // ── Submit state ───────────────────────────────────────────────────────────
  readonly saving = signal(false);
  readonly formError = signal<string | null>(null);
  readonly saved = signal<SupplierBillDto | null>(null);

  // ── Permissions ────────────────────────────────────────────────────────────
  readonly canSet = computed(() => this.session.hasPermission('AP.OPENING.SET'));

  readonly submitDisabled = computed(() =>
    !this.selectedSupplier() ||
    !String(this.grossAmount() ?? '').trim() ||
    +(String(this.grossAmount() ?? '').trim()) <= 0 ||
    !String(this.billDate() ?? '').trim() ||
    !String(this.dueDate() ?? '').trim() ||
    !this.selectedCompanyId() ||
    this.saving(),
  );

  private readonly supplierSearch$ = new Subject<string>();

  constructor() {
    this.billDate.set(new Date().toISOString().slice(0, 10));
    this.dueDate.set(new Date().toISOString().slice(0, 10));

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
    this.supplierResults.set([]);
    this.saved.set(null);
  }

  onSupplierSearchChange(q: string): void {
    this.supplierSearchQ.set(q);
    if (!q.trim()) {
      this.selectedSupplier.set(null);
      this.supplierResults.set([]);
      return;
    }
    this.selectedSupplier.set(null);
    this.supplierSearch$.next(q);
  }

  selectSupplier(s: SupplierModel): void {
    this.selectedSupplier.set({ uid: s.uid, label: `${s.code} — ${s.displayName}` });
    this.supplierSearchQ.set(`${s.code} — ${s.displayName}`);
    this.supplierResults.set([]);
  }

  submit(): void {
    if (this.submitDisabled()) return;

    const company = this.companies().find((c) => c.id === this.selectedCompanyId());
    if (!company) { this.formError.set('Could not resolve company.'); return; }

    const supplier = this.selectedSupplier();
    if (!supplier) { this.formError.set('Supplier is required.'); return; }

    const amt = String(this.grossAmount() ?? '').trim();
    const curr = String(this.currency() ?? '').trim();
    const date = String(this.billDate() ?? '').trim();
    const due = String(this.dueDate() ?? '').trim();
    const invNo = String(this.supplierInvoiceNo() ?? '').trim();

    if (!amt || +amt <= 0) { this.formError.set('Enter a valid amount.'); return; }
    if (!curr) { this.formError.set('Currency is required.'); return; }
    if (!date) { this.formError.set('Bill date is required.'); return; }
    if (!due) { this.formError.set('Due date is required.'); return; }

    const request: SetApOpeningBalanceRequest = {
      companyUid: company.uid,
      supplierUid: supplier.uid,
      grossAmount: amt,
      currency: curr,
      billDate: date,
      dueDate: due,
      supplierInvoiceNo: invNo || null,
    };

    this.saving.set(true);
    this.formError.set(null);

    this.apService.setOpeningBalance(request).subscribe({
      next: (bill) => {
        this.saving.set(false);
        this.saved.set(bill);
        this.alerts.success('AP opening balance set', bill.billNumber);
        this.resetForm();
      },
      error: (err) => {
        this.formError.set(this.messageFrom(err, 'Could not set opening balance.'));
        this.saving.set(false);
      },
    });
  }

  private resetForm(): void {
    this.grossAmount.set('');
    this.supplierInvoiceNo.set('');
    this.billDate.set(new Date().toISOString().slice(0, 10));
    this.dueDate.set(new Date().toISOString().slice(0, 10));
    this.selectedSupplier.set(null);
    this.supplierSearchQ.set('');
    this.supplierResults.set([]);
  }

  private messageFrom(err: unknown, fallback: string): string {
    if (err instanceof HttpErrorResponse) {
      const errors = (err.error as { errors?: string[] })?.errors;
      if (errors?.length) return errors[0];
    }
    return fallback;
  }
}
