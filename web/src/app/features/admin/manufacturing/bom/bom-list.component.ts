import { HttpErrorResponse } from '@angular/common/http';
import { DecimalPipe } from '@angular/common';
import { Component, computed, inject, signal } from '@angular/core';
import { takeUntilDestroyed, toObservable } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { debounceTime, distinctUntilChanged, map, merge, skip, Subject, switchMap } from 'rxjs';
import { PageMeta } from '../../../../core/api/api-response.model';
import { AlertService } from '../../../../core/feedback/alert.service';
import { SessionStore } from '../../../../core/auth/session.store';
import { Company } from '../../models/company.model';
import { CompanyService } from '../../company/company.service';
import { OrganisationService } from '../../organisation/organisation.service';
import { ProductService } from '../../products/product.service';
import { BomService } from './bom.service';
import type { BomPage } from './bom.service';
import { BomDto, BomStatus, CreateBomRequest } from './models/bom.model';
import { PaginatorComponent } from '../../../../shared/paginator/paginator.component';
import { UidOption, UidPickerComponent } from '../../../../shared/uid-picker/uid-picker.component';

const DEFAULT_SIZE = 20;

interface LoadTrigger {
  page: number;
}

/**
 * BOM list screen. Company-scoped, status filter, inline create.
 * Route: /admin/boms
 */
@Component({
  selector: 'app-bom-list',
  imports: [FormsModule, RouterLink, DecimalPipe, PaginatorComponent, UidPickerComponent],
  templateUrl: './bom-list.component.html',
  styleUrl: './bom-list.component.scss',
})
export class BomListComponent {
  private readonly bomService = inject(BomService);
  private readonly companyService = inject(CompanyService);
  private readonly organisationService = inject(OrganisationService);
  private readonly productService = inject(ProductService);
  private readonly alerts = inject(AlertService);
  protected readonly session = inject(SessionStore);

  // ── Picker options ────────────────────────────────────────────────────────
  readonly productOptions = signal<UidOption[]>([]);

  // ── Company context ───────────────────────────────────────────────────────
  readonly companies = signal<Company[]>([]);
  readonly selectedCompanyId = signal('');
  readonly selectedCompanyUid = signal('');
  readonly companyState = signal<'loading' | 'idle' | 'error'>('loading');

  // ── List state ────────────────────────────────────────────────────────────
  readonly rows = signal<BomDto[]>([]);
  readonly meta = signal<PageMeta>({
    page: 0,
    size: DEFAULT_SIZE,
    totalElements: 0,
    totalPages: 0,
    hasNext: false,
  });
  readonly state = signal<'loading' | 'idle' | 'error' | 'forbidden'>('idle');
  readonly currentPage = signal(0);
  readonly statusFilter = signal<BomStatus | ''>('');

  // ── Create form ───────────────────────────────────────────────────────────
  readonly showCreateForm = signal(false);
  readonly newParentProductUid = signal('');
  readonly newOutputQty = signal('1');
  readonly newYieldPercent = signal('100');
  readonly newNotes = signal('');
  readonly saving = signal(false);
  readonly formError = signal<string | null>(null);

  readonly canManage = computed(() => this.session.hasPermission('BOM.MANAGE'));
  readonly isEmpty = computed(() => this.state() === 'idle' && this.rows().length === 0);

  // Expose global String constructor for use in templates.
  protected readonly String = String;

  readonly statusOptions: Array<{ value: BomStatus | ''; label: string }> = [
    { value: '', label: 'All statuses' },
    { value: 'DRAFT', label: 'Draft' },
    { value: 'ACTIVE', label: 'Active' },
    { value: 'ARCHIVED', label: 'Archived' },
  ];

  private readonly immediateTrigger$ = new Subject<LoadTrigger>();

  constructor() {
    const filterTrigger$ = toObservable(this.statusFilter).pipe(
      skip(1),
      debounceTime(50),
      distinctUntilChanged(),
      map((): LoadTrigger => ({ page: 0 })),
    );

    merge(filterTrigger$, this.immediateTrigger$)
      .pipe(
        switchMap(({ page }) => {
          const companyId = this.selectedCompanyId();
          if (!companyId) return [];
          this.state.set('loading');
          this.currentPage.set(page);
          return this.bomService.list(companyId, page, DEFAULT_SIZE, undefined, this.statusFilter());
        }),
        takeUntilDestroyed(),
      )
      .subscribe({
        next: ({ rows, meta }: BomPage) => {
          this.rows.set(rows);
          this.meta.set(meta);
          this.state.set('idle');
        },
        error: (err: unknown) =>
          this.state.set(
            err instanceof HttpErrorResponse && err.status === 403 ? 'forbidden' : 'error',
          ),
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
              this.selectedCompanyUid.set(list[0].uid);
              this.load(0);
              this.loadProductOptions(list[0].id);
            }
          },
          error: () => this.companyState.set('error'),
        });
      },
      error: () => this.companyState.set('error'),
    });
  }

  private loadProductOptions(companyId: string): void {
    this.productService.list(companyId, undefined, 0, 500).subscribe({
      next: ({ rows }) => {
        this.productOptions.set(
          rows
            .filter((p) => p.status === 'ACTIVE')
            .map((p) => ({ uid: p.uid, label: p.name, hint: p.code })),
        );
      },
      error: () => {},
    });
  }

  onCompanyChange(id: string): void {
    this.selectedCompanyId.set(id);
    const company = this.companies().find((c) => c.id === id);
    if (company) {
      this.selectedCompanyUid.set(company.uid);
      this.loadProductOptions(company.id);
    }
    if (id) this.load(0);
  }

  onStatusChange(s: string): void {
    this.statusFilter.set(s as BomStatus | '');
  }

  load(page: number): void {
    const companyId = this.selectedCompanyId();
    if (!companyId) return;
    this.immediateTrigger$.next({ page });
  }

  goToPage(page: number): void {
    this.load(page);
  }

  // ── Create form ────────────────────────────────────────────────────────────

  toggleCreateForm(): void {
    this.showCreateForm.update((v) => !v);
    this.formError.set(null);
    if (!this.showCreateForm()) this.resetCreateForm();
  }

  private resetCreateForm(): void {
    this.newParentProductUid.set('');
    this.newOutputQty.set('1');
    this.newYieldPercent.set('100');
    this.newNotes.set('');
  }

  create(): void {
    const parentProductUid = this.newParentProductUid().trim();
    const outputQty = this.newOutputQty().trim();

    if (!parentProductUid) {
      this.formError.set('Parent product is required.');
      return;
    }
    if (!outputQty || isNaN(Number(outputQty)) || Number(outputQty) <= 0) {
      this.formError.set('Output quantity must be a positive number.');
      return;
    }

    const companyId = this.selectedCompanyId();
    if (!companyId) {
      this.formError.set('No company selected.');
      return;
    }

    this.saving.set(true);
    this.formError.set(null);

    const request: CreateBomRequest = {
      parentProductUid,
      outputQty,
      yieldPercent: this.newYieldPercent().trim() || undefined,
      notes: this.newNotes().trim() || undefined,
    };

    this.bomService.create(companyId, request).subscribe({
      next: (created) => {
        this.saving.set(false);
        this.resetCreateForm();
        this.showCreateForm.set(false);
        this.alerts.success('BOM created', `v${created.versionNo}`);
        this.load(this.currentPage());
      },
      error: (err: unknown) => {
        this.formError.set(this.messageFrom(err, 'Could not create BOM.'));
        this.saving.set(false);
      },
    });
  }

  // ── Display helpers ───────────────────────────────────────────────────────

  /**
   * Resolve a product uid to its human-readable label (name + code hint).
   * Falls back to the first 8 chars of the uid + "…" rather than showing the full uid (C1).
   */
  productLabel(uid: string): string {
    const opt = this.productOptions().find((o) => o.uid === uid);
    if (opt) return opt.hint ? `${opt.label} (${opt.hint})` : opt.label;
    // uid not yet in options (e.g. inactive product) — show a short code only
    return uid ? uid.slice(0, 8) + '…' : '—';
  }

  private messageFrom(err: unknown, fallback: string): string {
    if (err instanceof HttpErrorResponse) {
      const errors = (err.error as { errors?: string[] })?.errors;
      if (errors?.length) return errors[0];
    }
    return fallback;
  }
}
