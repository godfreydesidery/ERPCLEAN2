import { HttpErrorResponse } from '@angular/common/http';
import { DatePipe } from '@angular/common';
import { Component, computed, inject, signal } from '@angular/core';
import { takeUntilDestroyed, toObservable } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { debounceTime, distinctUntilChanged, map, merge, skip, Subject, switchMap } from 'rxjs';
import { PageMeta } from '../../../core/api/api-response.model';
import { AlertService } from '../../../core/feedback/alert.service';
import { SessionStore } from '../../../core/auth/session.store';
import { Company } from '../models/company.model';
import { CustomerModel } from '../models/party.model';
import {
  CreateSalesInvoiceRequest,
  SalesInvoiceDto,
} from '../models/sales.model';
import { CompanyService } from '../company/company.service';
import { OrganisationService } from '../organisation/organisation.service';
import { CustomerService } from '../parties/customer.service';
import { AgentService } from '../parties/agent.service';
import { AgentModel } from '../models/party.model';
import { RouteDto } from '../routes/models/route.model';
import { RoutesService } from '../routes/routes.service';
import { SalesService } from './sales.service';
import { PaginatorComponent } from '../../../shared/paginator/paginator.component';
import { CurrencySelectComponent } from '../../../shared/currency-select/currency-select.component';

const DEFAULT_SIZE = 20;

interface LoadTrigger { q: string; status: string; page: number }

/**
 * Paged list of sales invoices scoped to the active company.
 * Provides debounced search, status filter, and inline create form.
 * All four states (loading / empty / error / forbidden) handled.
 */
@Component({
  selector: 'app-sales-invoice-list',
  imports: [FormsModule, RouterLink, DatePipe, PaginatorComponent, CurrencySelectComponent],
  templateUrl: './sales-invoice-list.component.html',
  styleUrl: './sales-invoice-list.component.scss',
})
export class SalesInvoiceListComponent {
  private readonly salesService = inject(SalesService);
  private readonly companyService = inject(CompanyService);
  private readonly organisationService = inject(OrganisationService);
  private readonly customerService = inject(CustomerService);
  private readonly agentService = inject(AgentService);
  private readonly routesService = inject(RoutesService);
  private readonly alerts = inject(AlertService);
  protected readonly session = inject(SessionStore);

  // ── Company context ────────────────────────────────────────────────────────
  readonly companies = signal<Company[]>([]);
  readonly selectedCompanyId = signal('');
  readonly selectedCompanyUid = computed(() => this.companies().find((c) => c.id === this.selectedCompanyId())?.uid ?? '');
  readonly companyState = signal<'loading' | 'idle' | 'error'>('loading');

  // ── List state ─────────────────────────────────────────────────────────────
  readonly rows = signal<SalesInvoiceDto[]>([]);
  readonly meta = signal<PageMeta>({ page: 0, size: DEFAULT_SIZE, totalElements: 0, totalPages: 0, hasNext: false });
  readonly state = signal<'loading' | 'idle' | 'error' | 'forbidden'>('idle');
  readonly currentPage = signal(0);

  // ── Filters ────────────────────────────────────────────────────────────────
  readonly searchQ = signal('');
  readonly statusFilter = signal('');

  // ── Create form ────────────────────────────────────────────────────────────
  readonly showCreateForm = signal(false);
  readonly newCurrency = signal('TZS');
  readonly newNotes = signal('');
  readonly saving = signal(false);
  readonly formError = signal<string | null>(null);

  // ── Customer picker for create form ───────────────────────────────────────
  readonly customerSearchQ = signal('');
  readonly customerResults = signal<CustomerModel[]>([]);
  readonly selectedCustomer = signal<{ uid: string; label: string } | null>(null);

  // ── Agent picker for create form ───────────────────────────────────────────
  readonly agentSearchQ = signal('');
  readonly agentResults = signal<AgentModel[]>([]);
  readonly selectedAgent = signal<{ uid: string; label: string } | null>(null);

  // ── Route picker for create form (optional) ────────────────────────────────
  readonly companyRoutes = signal<RouteDto[]>([]);
  readonly companyRoutesState = signal<'idle' | 'loading' | 'error'>('idle');
  readonly selectedRouteUid = signal('');

  // ── Search pipeline ────────────────────────────────────────────────────────
  private readonly immediateTrigger$ = new Subject<LoadTrigger>();
  private readonly customerSearch$ = new Subject<string>();
  private readonly agentSearch$ = new Subject<string>();

  readonly canCreate = computed(() => this.session.hasPermission('SALES.INVOICE.CREATE'));
  readonly isEmpty = computed(() => this.state() === 'idle' && this.rows().length === 0);

  constructor() {
    // Main list debounce pipeline
    const typingTrigger$ = toObservable(this.searchQ).pipe(
      skip(1),
      debounceTime(300),
      distinctUntilChanged(),
      map((q): LoadTrigger => ({ q, status: this.statusFilter(), page: 0 })),
    );

    merge(typingTrigger$, this.immediateTrigger$)
      .pipe(
        switchMap(({ q, status, page }) => {
          const companyId = this.selectedCompanyId();
          if (!companyId) return [];
          this.state.set('loading');
          this.currentPage.set(page);
          return this.salesService.list(companyId, q || undefined, status || undefined, page, DEFAULT_SIZE);
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

    // Customer search debounce
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

    // Agent search debounce
    this.agentSearch$
      .pipe(
        debounceTime(300),
        distinctUntilChanged(),
        switchMap((q) => {
          const companyId = this.selectedCompanyId();
          if (!companyId || !q.trim()) {
            this.agentResults.set([]);
            return [];
          }
          return this.agentService.list(companyId, q.trim(), 0, 10);
        }),
        takeUntilDestroyed(),
      )
      .subscribe({
        next: ({ rows }) => this.agentResults.set(rows.filter((a) => a.status === 'ACTIVE')),
        error: () => this.agentResults.set([]),
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
              this.loadCompanyRoutes(list[0].id);
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
    if (id) {
      this.load(0);
      this.loadCompanyRoutes(id);
    }
  }

  private loadCompanyRoutes(companyId: string): void {
    this.companyRoutesState.set('loading');
    this.routesService.list(companyId, undefined, 0, 200).subscribe({
      next: ({ rows }) => {
        this.companyRoutes.set(rows.filter((r) => r.status === 'ACTIVE'));
        this.companyRoutesState.set('idle');
      },
      error: () => this.companyRoutesState.set('error'),
    });
  }

  applySearch(): void {
    this.load(0);
  }

  clearSearch(): void {
    this.searchQ.set('');
    this.load(0);
  }

  onStatusChange(status: string): void {
    this.statusFilter.set(status);
    this.load(0);
  }

  goToPage(page: number): void { this.load(page); }

  prevPage(): void {
    const p = this.currentPage();
    if (p > 0) this.load(p - 1);
  }

  nextPage(): void {
    if (this.meta().hasNext) this.load(this.currentPage() + 1);
  }

  load(page: number): void {
    const companyId = this.selectedCompanyId();
    if (!companyId) return;
    this.immediateTrigger$.next({ q: this.searchQ(), status: this.statusFilter(), page });
  }

  // ── Customer picker ────────────────────────────────────────────────────────

  onCustomerSearchChange(q: string): void {
    this.customerSearchQ.set(q);
    this.selectedCustomer.set(null);
    this.customerSearch$.next(q);
  }

  selectCustomer(customer: CustomerModel): void {
    this.selectedCustomer.set({ uid: customer.uid, label: `${customer.code} — ${customer.displayName}` });
    this.customerResults.set([]);
    this.customerSearchQ.set(`${customer.code} — ${customer.displayName}`);
  }

  // ── Agent picker ───────────────────────────────────────────────────────────

  onAgentSearchChange(q: string): void {
    this.agentSearchQ.set(q);
    this.selectedAgent.set(null);
    this.agentSearch$.next(q);
  }

  selectAgent(agent: AgentModel): void {
    this.selectedAgent.set({ uid: agent.uid, label: `${agent.code} — ${agent.displayName}` });
    this.agentResults.set([]);
    this.agentSearchQ.set(`${agent.code} — ${agent.displayName}`);
  }

  clearAgent(): void {
    this.selectedAgent.set(null);
    this.agentSearchQ.set('');
    this.agentResults.set([]);
  }

  // ── Create form ────────────────────────────────────────────────────────────

  toggleCreateForm(): void {
    this.showCreateForm.update((v) => !v);
    this.formError.set(null);
    if (!this.showCreateForm()) {
      this.resetCreateForm();
    }
  }

  private resetCreateForm(): void {
    this.newCurrency.set('TZS');
    this.newNotes.set('');
    this.selectedCustomer.set(null);
    this.customerSearchQ.set('');
    this.customerResults.set([]);
    this.selectedAgent.set(null);
    this.agentSearchQ.set('');
    this.agentResults.set([]);
    this.selectedRouteUid.set('');
  }

  create(): void {
    const selectedCustomer = this.selectedCustomer();
    const currency = this.newCurrency().trim();

    if (!selectedCustomer) {
      this.formError.set('Customer is required.');
      return;
    }
    if (!currency) {
      this.formError.set('Currency is required.');
      return;
    }

    const company = this.companies().find((c) => c.id === this.selectedCompanyId());
    if (!company) {
      this.formError.set('Could not resolve selected company.');
      return;
    }

    this.saving.set(true);
    this.formError.set(null);

    const request: CreateSalesInvoiceRequest = {
      companyUid: company.uid,
      customerUid: selectedCustomer.uid,
      currency,
      notes: this.newNotes().trim() || undefined,
      agentUid: this.selectedAgent()?.uid || undefined,
      routeUid: this.selectedRouteUid() || undefined,
    };

    this.salesService.create(request).subscribe({
      next: (created) => {
        this.saving.set(false);
        this.resetCreateForm();
        this.showCreateForm.set(false);
        this.alerts.success('Invoice created', created.invoiceNumber ?? 'DRAFT');
        this.load(this.currentPage());
      },
      error: (err) => {
        this.formError.set(this.messageFrom(err));
        this.saving.set(false);
      },
    });
  }

  // ── Display helpers ────────────────────────────────────────────────────────

  invoiceLabel(inv: SalesInvoiceDto): string {
    return inv.invoiceNumber ?? 'DRAFT';
  }

  private messageFrom(err: unknown): string {
    const errors = (err as { error?: { errors?: string[] } })?.error?.errors;
    return errors?.length ? errors[0] : 'Could not save the invoice.';
  }
}
