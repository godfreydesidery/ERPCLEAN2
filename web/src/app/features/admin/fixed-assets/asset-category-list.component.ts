import { HttpErrorResponse } from '@angular/common/http';
import { Component, computed, inject, signal } from '@angular/core';
import { takeUntilDestroyed, toObservable } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { debounceTime, distinctUntilChanged, map, merge, skip, Subject, switchMap } from 'rxjs';
import { AlertService } from '../../../core/feedback/alert.service';
import { SessionStore } from '../../../core/auth/session.store';
import { Company } from '../models/company.model';
import { CompanyService } from '../company/company.service';
import { OrganisationService } from '../organisation/organisation.service';
import { FixedAssetsService } from './fixed-assets.service';
import {
  AssetCategoryDto,
  CreateAssetCategoryRequest,
  DepreciationMethod,
} from './models/fixed-assets.model';

interface LoadTrigger { page: number }

/**
 * Asset Category list + inline create. Route: /admin/asset-categories
 * Not paginated — list returns all categories for the selected company.
 */
@Component({
  selector: 'app-asset-category-list',
  imports: [FormsModule, RouterLink],
  templateUrl: './asset-category-list.component.html',
  styleUrl: './asset-category-list.component.scss',
})
export class AssetCategoryListComponent {
  private readonly faService = inject(FixedAssetsService);
  private readonly companyService = inject(CompanyService);
  private readonly organisationService = inject(OrganisationService);
  private readonly alerts = inject(AlertService);
  protected readonly session = inject(SessionStore);

  // ── Company context ──────────────────────────────────────────────────────────
  readonly companies = signal<Company[]>([]);
  readonly selectedCompanyId = signal('');
  readonly companyState = signal<'loading' | 'idle' | 'error'>('loading');

  // ── List state ───────────────────────────────────────────────────────────────
  readonly rows = signal<AssetCategoryDto[]>([]);
  readonly state = signal<'loading' | 'idle' | 'error' | 'forbidden'>('idle');

  readonly isEmpty = computed(() => this.state() === 'idle' && this.rows().length === 0);
  readonly canManage = computed(() => this.session.hasPermission('FA.CATEGORY.MANAGE'));

  // ── Create form ──────────────────────────────────────────────────────────────
  readonly showCreateForm = signal(false);
  readonly newCode = signal('');
  readonly newName = signal('');
  readonly newMethod = signal<DepreciationMethod>('STRAIGHT_LINE');
  readonly newLifePeriods = signal('');
  readonly newReducingRate = signal('');
  readonly newAssetAccountId = signal('');
  readonly newAccumDepAccountId = signal('');
  readonly newDepExpenseAccountId = signal('');
  readonly saving = signal(false);
  readonly formError = signal<string | null>(null);

  readonly methodOptions: DepreciationMethod[] = ['STRAIGHT_LINE', 'REDUCING_BALANCE'];

  private readonly immediateTrigger$ = new Subject<LoadTrigger>();

  constructor() {
    const companyChangeTrigger$ = toObservable(this.selectedCompanyId).pipe(
      skip(1),
      debounceTime(50),
      distinctUntilChanged(),
      map((): LoadTrigger => ({ page: 0 })),
    );

    merge(companyChangeTrigger$, this.immediateTrigger$)
      .pipe(
        switchMap(() => {
          const companyId = this.selectedCompanyId();
          if (!companyId) return [];
          this.state.set('loading');
          return this.faService.listCategories(companyId);
        }),
        takeUntilDestroyed(),
      )
      .subscribe({
        next: (rows) => { this.rows.set(rows); this.state.set('idle'); },
        error: (err: unknown) =>
          this.state.set(err instanceof HttpErrorResponse && err.status === 403 ? 'forbidden' : 'error'),
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
              this.load();
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
    if (id) this.load();
  }

  load(): void {
    if (!this.selectedCompanyId()) return;
    this.immediateTrigger$.next({ page: 0 });
  }

  // ── Create form ─────────────────────────────────────────────────────────────

  toggleCreateForm(): void {
    this.showCreateForm.update((v) => !v);
    this.formError.set(null);
    if (!this.showCreateForm()) this.resetForm();
  }

  private resetForm(): void {
    this.newCode.set('');
    this.newName.set('');
    this.newMethod.set('STRAIGHT_LINE');
    this.newLifePeriods.set('');
    this.newReducingRate.set('');
    this.newAssetAccountId.set('');
    this.newAccumDepAccountId.set('');
    this.newDepExpenseAccountId.set('');
  }

  /** Coerce a value from ngModel on type="number" to string; prevents .trim() crashes. */
  coerceNumStr(v: string | number | null | undefined): string {
    return v === null || v === undefined ? '' : String(v);
  }

  create(): void {
    const code = this.newCode().trim();
    const name = this.newName().trim();
    const lifePeriods = parseInt(this.newLifePeriods().trim(), 10);
    const assetAccountId = this.newAssetAccountId().trim();
    const accumDepAccountId = this.newAccumDepAccountId().trim();
    const depExpenseAccountId = this.newDepExpenseAccountId().trim();
    const method = this.newMethod();

    if (!code) { this.formError.set('Code is required.'); return; }
    if (!name) { this.formError.set('Name is required.'); return; }
    if (!lifePeriods || lifePeriods < 1) { this.formError.set('Life periods must be at least 1.'); return; }
    if (!assetAccountId) { this.formError.set('Asset account ID is required.'); return; }
    if (!accumDepAccountId) { this.formError.set('Accumulated depreciation account ID is required.'); return; }
    if (!depExpenseAccountId) { this.formError.set('Depreciation expense account ID is required.'); return; }
    if (method === 'REDUCING_BALANCE' && !this.newReducingRate().trim()) {
      this.formError.set('Reducing rate is required for Reducing Balance method.');
      return;
    }

    const company = this.companies().find((c) => c.id === this.selectedCompanyId());
    if (!company) { this.formError.set('Could not resolve selected company.'); return; }

    this.saving.set(true);
    this.formError.set(null);

    const request: CreateAssetCategoryRequest = {
      companyId: company.id,
      code,
      name,
      defaultMethod: method,
      defaultLifePeriods: lifePeriods,
      defaultReducingRate: method === 'REDUCING_BALANCE' ? this.newReducingRate().trim() : undefined,
      assetAccountId,
      accumDepAccountId,
      depExpenseAccountId,
    };

    this.faService.createCategory(request).subscribe({
      next: (created) => {
        this.saving.set(false);
        this.resetForm();
        this.showCreateForm.set(false);
        this.alerts.success('Category created', created.name);
        this.load();
      },
      error: (err: unknown) => {
        this.formError.set(this.messageFrom(err, 'Could not create category.'));
        this.saving.set(false);
      },
    });
  }

  private messageFrom(err: unknown, fallback: string): string {
    if (err instanceof HttpErrorResponse) {
      const errors = (err.error as { errors?: string[] })?.errors;
      if (errors?.length) return errors[0];
    }
    return fallback;
  }
}
