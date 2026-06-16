import { HttpErrorResponse } from '@angular/common/http';
import { Component, computed, inject, input, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { AlertService } from '../../../core/feedback/alert.service';
import { SessionStore } from '../../../core/auth/session.store';
import {
  AssetCategoryDto,
  DepreciationMethod,
  UpdateAssetCategoryRequest,
} from './models/fixed-assets.model';
import { FixedAssetsService } from './fixed-assets.service';

type LoadState = 'loading' | 'idle' | 'error';

/**
 * Asset Category detail + edit screen. Route: /admin/asset-categories/uid/:uid
 * Supports edit and archive (soft-delete).
 */
@Component({
  selector: 'app-asset-category-detail',
  imports: [FormsModule, RouterLink],
  templateUrl: './asset-category-detail.component.html',
  styleUrl: './asset-category-detail.component.scss',
})
export class AssetCategoryDetailComponent {
  private readonly faService = inject(FixedAssetsService);
  private readonly alerts = inject(AlertService);
  protected readonly session = inject(SessionStore);

  readonly uid = input.required<string>();

  // ── Entity ───────────────────────────────────────────────────────────────────
  readonly category = signal<AssetCategoryDto | null>(null);
  readonly state = signal<LoadState>('loading');

  // ── Edit form fields ─────────────────────────────────────────────────────────
  readonly fName = signal('');
  readonly fMethod = signal<DepreciationMethod>('STRAIGHT_LINE');
  readonly fLifePeriods = signal('');
  readonly fReducingRate = signal('');
  readonly fAssetAccountId = signal('');
  readonly fAccumDepAccountId = signal('');
  readonly fDepExpenseAccountId = signal('');

  readonly saving = signal(false);
  readonly saveError = signal<string | null>(null);
  readonly archiving = signal(false);

  readonly canManage = computed(() => this.session.hasPermission('FA.CATEGORY.MANAGE'));
  readonly isActive = computed(() => this.category()?.status === 'ACTIVE');
  readonly isReducingBalance = computed(() => this.fMethod() === 'REDUCING_BALANCE');

  readonly methodOptions: DepreciationMethod[] = ['STRAIGHT_LINE', 'REDUCING_BALANCE'];

  constructor() {
    queueMicrotask(() => this.init());
  }

  private init(): void {
    this.loadCategory();
  }

  private loadCategory(): void {
    this.state.set('loading');
    this.faService.getCategoryByUid(this.uid()).subscribe({
      next: (cat) => {
        this.category.set(cat);
        this.state.set('idle');
        this.patchForm(cat);
      },
      error: () => this.state.set('error'),
    });
  }

  private patchForm(cat: AssetCategoryDto): void {
    this.fName.set(cat.name ?? '');
    this.fMethod.set(cat.defaultMethod);
    this.fLifePeriods.set(String(cat.defaultLifePeriods));
    this.fReducingRate.set(cat.defaultReducingRate ?? '');
    this.fAssetAccountId.set(cat.assetAccountId ?? '');
    this.fAccumDepAccountId.set(cat.accumDepAccountId ?? '');
    this.fDepExpenseAccountId.set(cat.depExpenseAccountId ?? '');
  }

  save(): void {
    const name = this.fName().trim();
    const lifePeriods = parseInt(this.fLifePeriods().trim(), 10);
    if (!name) { this.saveError.set('Name is required.'); return; }
    if (!lifePeriods || lifePeriods < 1) { this.saveError.set('Life periods must be at least 1.'); return; }
    if (this.fMethod() === 'REDUCING_BALANCE' && !this.fReducingRate().trim()) {
      this.saveError.set('Reducing rate is required for Reducing Balance method.');
      return;
    }

    this.saving.set(true);
    this.saveError.set(null);

    const request: UpdateAssetCategoryRequest = {
      name,
      defaultMethod: this.fMethod(),
      defaultLifePeriods: lifePeriods,
      defaultReducingRate: this.fMethod() === 'REDUCING_BALANCE' ? this.fReducingRate().trim() : undefined,
      assetAccountId: this.fAssetAccountId().trim(),
      accumDepAccountId: this.fAccumDepAccountId().trim(),
      depExpenseAccountId: this.fDepExpenseAccountId().trim(),
    };

    this.faService.updateCategory(this.uid(), request).subscribe({
      next: (updated) => {
        this.category.set(updated);
        this.saving.set(false);
        this.alerts.success('Category saved', updated.name);
      },
      error: (err) => {
        this.saveError.set(this.messageFrom(err, 'Could not save category.'));
        this.saving.set(false);
      },
    });
  }

  archive(): void {
    if (this.archiving()) return;
    this.archiving.set(true);
    this.faService.archiveCategory(this.uid()).subscribe({
      next: (archived) => {
        this.archiving.set(false);
        this.category.set(archived);
        this.alerts.success('Category archived', archived.name);
      },
      error: (err) => {
        this.archiving.set(false);
        this.saveError.set(this.messageFrom(err, 'Could not archive category.'));
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
