import { HttpErrorResponse } from '@angular/common/http';
import { DecimalPipe } from '@angular/common';
import { Component, computed, inject, signal } from '@angular/core';
import { takeUntilDestroyed, toObservable } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { debounceTime, distinctUntilChanged, map, merge, skip, Subject, switchMap } from 'rxjs';
import { PageMeta } from '../../../core/api/api-response.model';
import { AlertService } from '../../../core/feedback/alert.service';
import { SessionStore } from '../../../core/auth/session.store';
import { Company } from '../models/company.model';
import { CustomerModel, AgentModel } from '../models/party.model';
import { CreateQuotationRequest, QuotationDto, QuotationStatus } from '../models/sales-orders.model';
import { CompanyService } from '../company/company.service';
import { OrganisationService } from '../organisation/organisation.service';
import { CustomerService } from '../parties/customer.service';
import { AgentService } from '../parties/agent.service';
import { SalesOrdersService } from './sales-orders.service';
import type { QuotationPage } from './sales-orders.service';
import { PaginatorComponent } from '../../../shared/paginator/paginator.component';

const DEFAULT_SIZE = 20;

interface LoadTrigger { page: number }

/**
 * Quotation list screen. Company scope, status filter, inline create.
 * Route: /admin/quotations
 */
@Component({
  selector: 'app-quotation-list',
  imports: [FormsModule, RouterLink, DecimalPipe, PaginatorComponent],
  templateUrl: './quotation-list.component.html',
  styleUrl: './quotation-list.component.scss',
})
export class QuotationListComponent {
  private readonly soService = inject(SalesOrdersService);
  private readonly companyService = inject(CompanyService);
  private readonly organisationService = inject(OrganisationService);
  private readonly customerService = inject(CustomerService);
  private readonly agentService = inject(AgentService);
  private readonly alerts = inject(AlertService);
  protected readonly session = inject(SessionStore);

  // ── Company context ─────────────────────────────────────────────────────────
  readonly companies = signal<Company[]>([]);
  readonly selectedCompanyId = signal('');
  readonly companyState = signal<'loading' | 'idle' | 'error'>('loading');

  // ── List state ──────────────────────────────────────────────────────────────
  readonly rows = signal<QuotationDto[]>([]);
  readonly meta = signal<PageMeta>({ page: 0, size: DEFAULT_SIZE, totalElements: 0, totalPages: 0, hasNext: false });
  readonly state = signal<'loading' | 'idle' | 'error' | 'forbidden'>('idle');
  readonly currentPage = signal(0);

  // ── Create form ─────────────────────────────────────────────────────────────
  readonly showCreateForm = signal(false);
  readonly newCurrency = signal('TZS');
  readonly newQuoteDate = signal('');
  readonly newValidUntil = signal('');
  readonly newNotes = signal('');
  readonly saving = signal(false);
  readonly formError = signal<string | null>(null);

  // ── Customer picker ──────────────────────────────────────────────────────────
  readonly customerSearchQ = signal('');
  readonly customerResults = signal<CustomerModel[]>([]);
  readonly selectedCustomer = signal<{ uid: string; label: string } | null>(null);

  // ── Agent picker (optional) ─────────────────────────────────────────────────
  readonly agentSearchQ = signal('');
  readonly agentResults = signal<AgentModel[]>([]);
  readonly selectedAgent = signal<{ uid: string; label: string } | null>(null);

  private readonly immediateTrigger$ = new Subject<LoadTrigger>();
  private readonly customerSearch$ = new Subject<string>();
  private readonly agentSearch$ = new Subject<string>();

  readonly canCreate = computed(() => this.session.hasPermission('SALES.QUOTE.CREATE'));
  readonly isEmpty = computed(() => this.state() === 'idle' && this.rows().length === 0);

  readonly statusOptions: Array<{ value: QuotationStatus | ''; label: string }> = [
    { value: '', label: 'All statuses' },
    { value: 'DRAFT', label: 'Draft' },
    { value: 'SENT', label: 'Sent' },
    { value: 'ACCEPTED', label: 'Accepted' },
    { value: 'REJECTED', label: 'Rejected' },
    { value: 'EXPIRED', label: 'Expired' },
  ];

  constructor() {
    const typingTrigger$ = toObservable(this.selectedCompanyId).pipe(
      skip(1),
      debounceTime(50),
      distinctUntilChanged(),
      map((): LoadTrigger => ({ page: 0 })),
    );

    merge(typingTrigger$, this.immediateTrigger$)
      .pipe(
        switchMap(({ page }) => {
          const companyId = this.selectedCompanyId();
          if (!companyId) return [];
          this.state.set('loading');
          this.currentPage.set(page);
          return this.soService.listQuotations(companyId, page, DEFAULT_SIZE);
        }),
        takeUntilDestroyed(),
      )
      .subscribe({
        next: ({ rows, meta }: QuotationPage) => {
          this.rows.set(rows);
          this.meta.set(meta);
          this.state.set('idle');
        },
        error: (err: unknown) =>
          this.state.set(err instanceof HttpErrorResponse && err.status === 403 ? 'forbidden' : 'error'),
      });

    this.customerSearch$
      .pipe(
        debounceTime(300),
        distinctUntilChanged(),
        switchMap((q) => {
          const companyId = this.selectedCompanyId();
          if (!companyId || !q.trim()) { this.customerResults.set([]); return []; }
          return this.customerService.list(companyId, q.trim(), 0, 10);
        }),
        takeUntilDestroyed(),
      )
      .subscribe({
        next: ({ rows }: { rows: CustomerModel[] }) => this.customerResults.set(rows.filter((c) => c.status === 'ACTIVE')),
        error: () => this.customerResults.set([]),
      });

    this.agentSearch$
      .pipe(
        debounceTime(300),
        distinctUntilChanged(),
        switchMap((q) => {
          const companyId = this.selectedCompanyId();
          if (!companyId || !q.trim()) { this.agentResults.set([]); return []; }
          return this.agentService.list(companyId, q.trim(), 0, 10);
        }),
        takeUntilDestroyed(),
      )
      .subscribe({
        next: ({ rows }: { rows: AgentModel[] }) => this.agentResults.set(rows.filter((a) => a.status === 'ACTIVE')),
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

  load(page: number): void {
    const companyId = this.selectedCompanyId();
    if (!companyId) return;
    this.immediateTrigger$.next({ page });
  }

  goToPage(page: number): void { this.load(page); }

  prevPage(): void { if (this.currentPage() > 0) this.load(this.currentPage() - 1); }
  nextPage(): void { if (this.meta().hasNext) this.load(this.currentPage() + 1); }

  // ── Customer picker ──────────────────────────────────────────────────────────

  onCustomerSearchChange(q: string): void {
    this.customerSearchQ.set(q);
    this.selectedCustomer.set(null);
    this.customerSearch$.next(q);
  }

  selectCustomer(c: CustomerModel): void {
    this.selectedCustomer.set({ uid: c.uid, label: `${c.code} — ${c.displayName}` });
    this.customerResults.set([]);
    this.customerSearchQ.set(`${c.code} — ${c.displayName}`);
  }

  // ── Agent picker ─────────────────────────────────────────────────────────────

  onAgentSearchChange(q: string): void {
    this.agentSearchQ.set(q);
    this.selectedAgent.set(null);
    this.agentSearch$.next(q);
  }

  selectAgent(a: AgentModel): void {
    this.selectedAgent.set({ uid: a.uid, label: `${a.code} — ${a.displayName}` });
    this.agentResults.set([]);
    this.agentSearchQ.set(`${a.code} — ${a.displayName}`);
  }

  clearAgent(): void {
    this.selectedAgent.set(null);
    this.agentSearchQ.set('');
    this.agentResults.set([]);
  }

  // ── Create form ─────────────────────────────────────────────────────────────

  toggleCreateForm(): void {
    this.showCreateForm.update((v) => !v);
    this.formError.set(null);
    if (!this.showCreateForm()) this.resetCreateForm();
  }

  private resetCreateForm(): void {
    this.newCurrency.set('TZS');
    this.newQuoteDate.set('');
    this.newValidUntil.set('');
    this.newNotes.set('');
    this.selectedCustomer.set(null);
    this.customerSearchQ.set('');
    this.customerResults.set([]);
    this.selectedAgent.set(null);
    this.agentSearchQ.set('');
    this.agentResults.set([]);
  }

  create(): void {
    const customer = this.selectedCustomer();
    const currency = this.newCurrency().trim();
    const quoteDate = this.newQuoteDate().trim();
    const validUntil = this.newValidUntil().trim();

    if (!customer) { this.formError.set('Customer is required.'); return; }
    if (!currency) { this.formError.set('Currency is required.'); return; }
    if (!quoteDate) { this.formError.set('Quote date is required.'); return; }
    if (!validUntil) { this.formError.set('Valid until date is required.'); return; }

    const company = this.companies().find((c) => c.id === this.selectedCompanyId());
    if (!company) { this.formError.set('Could not resolve selected company.'); return; }

    this.saving.set(true);
    this.formError.set(null);

    const request: CreateQuotationRequest = {
      companyUid: company.uid,
      customerUid: customer.uid,
      currency,
      quoteDate,
      validUntil,
      notes: this.newNotes().trim() || undefined,
      agentUid: this.selectedAgent()?.uid || undefined,
    };

    this.soService.createQuotation(request).subscribe({
      next: (created) => {
        this.saving.set(false);
        this.resetCreateForm();
        this.showCreateForm.set(false);
        this.alerts.success('Quotation created', created.quoteNumber ?? 'DRAFT');
        this.load(this.currentPage());
      },
      error: (err: unknown) => {
        this.formError.set(this.messageFrom(err, 'Could not create quotation.'));
        this.saving.set(false);
      },
    });
  }

  // ── Display helpers ──────────────────────────────────────────────────────────

  quoteLabel(q: QuotationDto): string {
    return q.quoteNumber ?? 'DRAFT';
  }

  private messageFrom(err: unknown, fallback: string): string {
    if (err instanceof HttpErrorResponse) {
      const errors = (err.error as { errors?: string[] })?.errors;
      if (errors?.length) return errors[0];
    }
    return fallback;
  }
}
