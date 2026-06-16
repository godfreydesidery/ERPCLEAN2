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
import { SupplierModel } from '../models/party.model';
import {
  CreatePurchaseOrderRequest,
  PurchaseOrderDto,
} from '../models/purchases.model';
import { CompanyService } from '../company/company.service';
import { OrganisationService } from '../organisation/organisation.service';
import { SupplierService } from '../parties/supplier.service';
import { PurchasesService } from './purchases.service';
import { PaginatorComponent } from '../../../shared/paginator/paginator.component';

const DEFAULT_SIZE = 20;

interface LoadTrigger { q: string; status: string; page: number }

/**
 * Purchase Order list screen. Company scope, debounced search, status filter, inline create.
 * Mirrors sales-invoice-list.component exactly in structure.
 */
@Component({
  selector: 'app-purchase-order-list',
  imports: [FormsModule, RouterLink, DatePipe, PaginatorComponent],
  templateUrl: './purchase-order-list.component.html',
  styleUrl: './purchase-order-list.component.scss',
})
export class PurchaseOrderListComponent {
  private readonly purchasesService = inject(PurchasesService);
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
  readonly rows = signal<PurchaseOrderDto[]>([]);
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
  readonly newExpectedDate = signal('');
  readonly saving = signal(false);
  readonly formError = signal<string | null>(null);

  // ── Supplier picker ────────────────────────────────────────────────────────
  readonly supplierSearchQ = signal('');
  readonly supplierResults = signal<SupplierModel[]>([]);
  readonly selectedSupplier = signal<{ uid: string; label: string } | null>(null);

  private readonly immediateTrigger$ = new Subject<LoadTrigger>();
  private readonly supplierSearch$ = new Subject<string>();

  readonly canCreate = computed(() => this.session.hasPermission('PURCHASE.ORDER.CREATE'));
  readonly isEmpty = computed(() => this.state() === 'idle' && this.rows().length === 0);

  readonly statusOptions: Array<{ value: string; label: string }> = [
    { value: '', label: 'All statuses' },
    { value: 'DRAFT', label: 'Draft' },
    { value: 'ORDERED', label: 'Ordered' },
    { value: 'PARTIALLY_RECEIVED', label: 'Partially Received' },
    { value: 'RECEIVED', label: 'Received' },
    { value: 'CLOSED', label: 'Closed' },
    { value: 'VOID', label: 'Void' },
  ];

  constructor() {
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
          return this.purchasesService.listOrders(companyId, q || undefined, status || undefined, page, DEFAULT_SIZE);
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

    this.supplierSearch$
      .pipe(
        debounceTime(300),
        distinctUntilChanged(),
        switchMap((q) => {
          const companyId = this.selectedCompanyId();
          if (!companyId || !q.trim()) { this.supplierResults.set([]); return []; }
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

  onStatusChange(status: string): void {
    this.statusFilter.set(status);
    this.load(0);
  }

  load(page: number): void {
    const companyId = this.selectedCompanyId();
    if (!companyId) return;
    this.immediateTrigger$.next({ q: this.searchQ(), status: this.statusFilter(), page });
  }

  goToPage(page: number): void { this.load(page); }

  prevPage(): void { if (this.currentPage() > 0) this.load(this.currentPage() - 1); }
  nextPage(): void { if (this.meta().hasNext) this.load(this.currentPage() + 1); }

  // ── Supplier picker ────────────────────────────────────────────────────────

  onSupplierSearchChange(q: string): void {
    this.supplierSearchQ.set(q);
    this.selectedSupplier.set(null);
    this.supplierSearch$.next(q);
  }

  selectSupplier(s: SupplierModel): void {
    this.selectedSupplier.set({ uid: s.uid, label: `${s.code} — ${s.displayName}` });
    this.supplierResults.set([]);
    this.supplierSearchQ.set(`${s.code} — ${s.displayName}`);
  }

  // ── Create ─────────────────────────────────────────────────────────────────

  toggleCreateForm(): void {
    this.showCreateForm.update((v) => !v);
    this.formError.set(null);
    if (!this.showCreateForm()) this.resetCreateForm();
  }

  private resetCreateForm(): void {
    this.newCurrency.set('TZS');
    this.newNotes.set('');
    this.newExpectedDate.set('');
    this.selectedSupplier.set(null);
    this.supplierSearchQ.set('');
    this.supplierResults.set([]);
  }

  create(): void {
    const supplier = this.selectedSupplier();
    const currency = this.newCurrency().trim();
    if (!supplier) { this.formError.set('Supplier is required.'); return; }
    if (!currency) { this.formError.set('Currency is required.'); return; }
    const company = this.companies().find((c) => c.id === this.selectedCompanyId());
    if (!company) { this.formError.set('Could not resolve selected company.'); return; }

    this.saving.set(true);
    this.formError.set(null);

    const request: CreatePurchaseOrderRequest = {
      companyUid: company.uid,
      supplierUid: supplier.uid,
      currency,
      notes: this.newNotes().trim() || undefined,
      expectedDate: this.newExpectedDate().trim() || undefined,
    };

    this.purchasesService.createOrder(request).subscribe({
      next: (created) => {
        this.saving.set(false);
        this.resetCreateForm();
        this.showCreateForm.set(false);
        this.alerts.success('Purchase order created', this.orderLabel(created));
        this.load(this.currentPage());
      },
      error: (err) => {
        this.formError.set(this.messageFrom(err, 'Could not create purchase order.'));
        this.saving.set(false);
      },
    });
  }

  // ── Display helpers ────────────────────────────────────────────────────────

  orderLabel(po: PurchaseOrderDto): string {
    return po.orderNumber ?? 'DRAFT';
  }

  private messageFrom(err: unknown, fallback: string): string {
    if (err instanceof HttpErrorResponse) {
      const errors = (err.error as { errors?: string[] })?.errors;
      if (errors?.length) return errors[0];
    }
    return fallback;
  }
}
