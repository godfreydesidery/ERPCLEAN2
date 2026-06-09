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
import { CustomerModel } from '../models/party.model';
import { CustomerService } from '../parties/customer.service';
import { ArInvoiceDto, SetOpeningBalanceRequest } from './models/ar.model';
import { ArService } from './ar.service';

/**
 * Set AR Opening Balance. Gated AR.OPENING.SET.
 * Form: customer, amount, currency, invoiceDate, dueDate (optional), documentNo (optional).
 */
@Component({
  selector: 'app-ar-opening-balance',
  imports: [FormsModule, RouterLink],
  templateUrl: './ar-opening-balance.component.html',
  styleUrl: './ar-opening-balance.component.scss',
})
export class ArOpeningBalanceComponent {
  private readonly arService = inject(ArService);
  private readonly companyService = inject(CompanyService);
  private readonly organisationService = inject(OrganisationService);
  private readonly customerService = inject(CustomerService);
  private readonly alerts = inject(AlertService);
  protected readonly session = inject(SessionStore);

  // ── Company context ────────────────────────────────────────────────────────
  readonly companies = signal<Company[]>([]);
  readonly selectedCompanyId = signal('');
  readonly companyState = signal<'loading' | 'idle' | 'error'>('loading');

  // ── Customer picker ────────────────────────────────────────────────────────
  readonly customerSearchQ = signal('');
  readonly customerResults = signal<CustomerModel[]>([]);
  readonly selectedCustomer = signal<{ uid: string; label: string } | null>(null);

  // ── Form fields ────────────────────────────────────────────────────────────
  readonly amount = signal('');
  readonly currency = signal('TZS');
  readonly invoiceDate = signal('');
  readonly dueDate = signal('');
  readonly documentNo = signal('');

  // ── Submit state ───────────────────────────────────────────────────────────
  readonly saving = signal(false);
  readonly formError = signal<string | null>(null);
  readonly saved = signal<ArInvoiceDto | null>(null);

  // ── Permissions ────────────────────────────────────────────────────────────
  readonly canSet = computed(() => this.session.hasPermission('AR.OPENING.SET'));

  readonly submitDisabled = computed(() =>
    !this.selectedCustomer() ||
    !String(this.amount() ?? '').trim() ||
    +(String(this.amount() ?? '').trim()) <= 0 ||
    !String(this.invoiceDate() ?? '').trim() ||
    !this.selectedCompanyId() ||
    this.saving(),
  );

  private readonly customerSearch$ = new Subject<string>();

  constructor() {
    this.invoiceDate.set(new Date().toISOString().slice(0, 10));

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
    this.customerResults.set([]);
    this.saved.set(null);
  }

  onCustomerSearchChange(q: string): void {
    this.customerSearchQ.set(q);
    if (!q.trim()) {
      this.selectedCustomer.set(null);
      this.customerResults.set([]);
      return;
    }
    this.selectedCustomer.set(null);
    this.customerSearch$.next(q);
  }

  selectCustomer(c: CustomerModel): void {
    this.selectedCustomer.set({ uid: c.uid, label: `${c.code} — ${c.displayName}` });
    this.customerSearchQ.set(`${c.code} — ${c.displayName}`);
    this.customerResults.set([]);
  }

  submit(): void {
    if (this.submitDisabled()) return;

    const company = this.companies().find((c) => c.id === this.selectedCompanyId());
    if (!company) { this.formError.set('Could not resolve company.'); return; }

    const customer = this.selectedCustomer();
    if (!customer) { this.formError.set('Customer is required.'); return; }

    const amt = String(this.amount() ?? '').trim();
    const curr = String(this.currency() ?? '').trim();
    const date = String(this.invoiceDate() ?? '').trim();
    const due = String(this.dueDate() ?? '').trim();
    const docNo = String(this.documentNo() ?? '').trim();

    if (!amt || +amt <= 0) { this.formError.set('Enter a valid amount.'); return; }
    if (!curr) { this.formError.set('Currency is required.'); return; }
    if (!date) { this.formError.set('Invoice date is required.'); return; }

    const request: SetOpeningBalanceRequest = {
      companyUid: company.uid,
      customerUid: customer.uid,
      amount: amt,
      currency: curr,
      invoiceDate: date,
      dueDate: due || undefined,
      documentNo: docNo || undefined,
    };

    this.saving.set(true);
    this.formError.set(null);

    this.arService.setOpeningBalance(request).subscribe({
      next: (inv) => {
        this.saving.set(false);
        this.saved.set(inv);
        this.alerts.success('Opening balance set', inv.documentNo);
        this.resetForm();
      },
      error: (err) => {
        this.formError.set(this.messageFrom(err, 'Could not set opening balance.'));
        this.saving.set(false);
      },
    });
  }

  private resetForm(): void {
    this.amount.set('');
    this.dueDate.set('');
    this.documentNo.set('');
    this.invoiceDate.set(new Date().toISOString().slice(0, 10));
    this.selectedCustomer.set(null);
    this.customerSearchQ.set('');
    this.customerResults.set([]);
  }

  private messageFrom(err: unknown, fallback: string): string {
    if (err instanceof HttpErrorResponse) {
      const errors = (err.error as { errors?: string[] })?.errors;
      if (errors?.length) return errors[0];
    }
    return fallback;
  }
}
