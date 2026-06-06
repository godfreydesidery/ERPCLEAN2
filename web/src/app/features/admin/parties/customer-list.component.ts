import { HttpErrorResponse } from '@angular/common/http';
import { Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { PageMeta } from '../../../core/api/api-response.model';
import { AlertService } from '../../../core/feedback/alert.service';
import { SessionStore } from '../../../core/auth/session.store';
import { Company } from '../models/company.model';
import { CustomerModel, CreateCustomerRequest, CustomerKind, PartyType } from '../models/party.model';
import { CompanyService } from '../company/company.service';
import { OrganisationService } from '../organisation/organisation.service';
import { CustomerService } from './customer.service';

const DEFAULT_SIZE = 20;

/**
 * Paged list of customers scoped to the active company. Provides a search box and an inline
 * create form. All four states (loading / empty / error / forbidden) handled.
 */
@Component({
  selector: 'app-customer-list',
  imports: [FormsModule, RouterLink],
  templateUrl: './customer-list.component.html',
  styleUrl: './customer-list.component.scss',
})
export class CustomerListComponent {
  private readonly customerService = inject(CustomerService);
  private readonly companyService = inject(CompanyService);
  private readonly organisationService = inject(OrganisationService);
  private readonly alerts = inject(AlertService);
  protected readonly session = inject(SessionStore);

  // ── Company context ────────────────────────────────────────────────────────
  readonly companies = signal<Company[]>([]);
  readonly selectedCompanyId = signal('');
  readonly companyState = signal<'loading' | 'idle' | 'error'>('loading');

  // ── List state ─────────────────────────────────────────────────────────────
  readonly rows = signal<CustomerModel[]>([]);
  readonly meta = signal<PageMeta>({ page: 0, size: DEFAULT_SIZE, totalElements: 0, totalPages: 0, hasNext: false });
  readonly state = signal<'loading' | 'idle' | 'error' | 'forbidden'>('idle');
  readonly currentPage = signal(0);

  // ── Search ─────────────────────────────────────────────────────────────────
  readonly searchQ = signal('');

  // ── Create form ────────────────────────────────────────────────────────────
  readonly newDisplayName = signal('');
  readonly newPartyType = signal<PartyType>('INDIVIDUAL');
  readonly newCustomerKind = signal<CustomerKind>('CASH_WALK_IN');
  readonly saving = signal(false);
  readonly formError = signal<string | null>(null);
  readonly showCreateForm = signal(false);

  readonly canManage = computed(() => this.session.hasPermission('CUSTOMER.MANAGE'));
  readonly isEmpty = computed(() => this.state() === 'idle' && this.rows().length === 0);

  constructor() {
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
    if (id) this.load(0);
  }

  applySearch(): void {
    this.load(0);
  }

  clearSearch(): void {
    this.searchQ.set('');
    this.load(0);
  }

  prevPage(): void {
    const p = this.currentPage();
    if (p > 0) this.load(p - 1);
  }

  nextPage(): void {
    if (this.meta().hasNext) this.load(this.currentPage() + 1);
  }

  private load(page: number): void {
    const companyId = this.selectedCompanyId();
    if (!companyId) return;
    this.state.set('loading');
    this.currentPage.set(page);
    this.customerService.list(companyId, this.searchQ() || undefined, page, DEFAULT_SIZE).subscribe({
      next: ({ rows, meta }) => {
        this.rows.set(rows);
        this.meta.set(meta);
        this.state.set('idle');
      },
      error: (err) =>
        this.state.set(err instanceof HttpErrorResponse && err.status === 403 ? 'forbidden' : 'error'),
    });
  }

  toggleCreateForm(): void {
    this.showCreateForm.update((v) => !v);
    this.formError.set(null);
  }

  create(): void {
    const companyId = this.selectedCompanyId();
    const displayName = this.newDisplayName().trim();
    if (!companyId || !displayName) {
      this.formError.set('Company and display name are required.');
      return;
    }
    this.saving.set(true);
    this.formError.set(null);
    const request: CreateCustomerRequest = {
      companyId,
      partyType: this.newPartyType(),
      displayName,
      customerKind: this.newCustomerKind(),
    };
    this.customerService.create(request).subscribe({
      next: (created) => {
        this.saving.set(false);
        this.newDisplayName.set('');
        this.newPartyType.set('INDIVIDUAL');
        this.newCustomerKind.set('CASH_WALK_IN');
        this.showCreateForm.set(false);
        this.alerts.success('Customer created', created.displayName);
        this.load(this.currentPage());
      },
      error: (err) => {
        this.formError.set(this.messageFrom(err));
        this.saving.set(false);
      },
    });
  }

  statusBadgeClass(status: string): string {
    return status === 'ACTIVE' ? 'text-bg-success' : 'text-bg-secondary';
  }

  private messageFrom(err: unknown): string {
    const errors = (err as { error?: { errors?: string[] } })?.error?.errors;
    return errors?.length ? errors[0] : 'Could not save the customer.';
  }
}
