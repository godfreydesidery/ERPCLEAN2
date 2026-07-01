import { HttpErrorResponse } from '@angular/common/http';
import { Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { AlertService } from '../../../core/feedback/alert.service';
import { SessionStore } from '../../../core/auth/session.store';
import { Company } from '../models/company.model';
import { CompanyService } from '../company/company.service';
import { OrganisationService } from '../organisation/organisation.service';
import { BranchService } from '../branch/branch.service';
import { FixedAssetsService } from './fixed-assets.service';
import {
  AssetCategoryDto,
  DepreciationMethod,
  RegisterAssetRequest,
} from './models/fixed-assets.model';
import { UidOption, UidPickerComponent } from '../../../shared/uid-picker/uid-picker.component';

/**
 * Register a new fixed asset (DRAFT). Route: /admin/fixed-assets/create
 * On success navigates to the new asset's detail page.
 */
@Component({
  selector: 'app-fixed-asset-create',
  imports: [FormsModule, RouterLink, UidPickerComponent],
  templateUrl: './fixed-asset-create.component.html',
  styleUrl: './fixed-asset-create.component.scss',
})
export class FixedAssetCreateComponent {
  private readonly faService = inject(FixedAssetsService);
  private readonly companyService = inject(CompanyService);
  private readonly organisationService = inject(OrganisationService);
  private readonly branchService = inject(BranchService);
  private readonly alerts = inject(AlertService);
  private readonly router = inject(Router);
  protected readonly session = inject(SessionStore);

  // ── Company + category context ────────────────────────────────────────────────
  readonly companies = signal<Company[]>([]);
  readonly selectedCompanyId = signal('');
  readonly companyState = signal<'loading' | 'idle' | 'error'>('loading');

  readonly categories = signal<AssetCategoryDto[]>([]);
  readonly categoriesState = signal<'idle' | 'loading' | 'error'>('idle');

  // ── Branch picker ─────────────────────────────────────────────────────────────
  readonly branchOptions = signal<UidOption[]>([]);
  /** Map from branch uid → branch id (numeric string), for RegisterAssetRequest.branchId. */
  private readonly branchIdByUid = new Map<string, string>();
  /** The picker model is bound to branch UID; the request uses branchId derived from it. */
  readonly fBranchUid = signal('');

  // ── Form fields ───────────────────────────────────────────────────────────────
  readonly fCategoryId = signal('');
  // fBranchId removed — derived from fBranchUid via branchIdByUid map
  readonly fName = signal('');
  readonly fAcquisitionCost = signal('');
  readonly fSalvageValue = signal('');
  readonly fMethod = signal<DepreciationMethod>('STRAIGHT_LINE');
  readonly fLifePeriods = signal('');
  readonly fReducingRate = signal('');
  readonly fAcquisitionDate = signal('');
  readonly fDepStartDate = signal('');
  readonly fLocation = signal('');
  readonly fCostCentreId = signal('');
  readonly fAssetTag = signal('');

  readonly saving = signal(false);
  readonly formError = signal<string | null>(null);

  readonly canCreate = computed(() => this.session.hasPermission('FA.REGISTER.MANAGE'));
  readonly isReducingBalance = computed(() => this.fMethod() === 'REDUCING_BALANCE');
  readonly methodOptions: DepreciationMethod[] = ['STRAIGHT_LINE', 'REDUCING_BALANCE'];

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
              // Default to the active company from session; fall back to list[0].
              const activeCompanyUid = this.session.user()?.activeCompanyUid ?? null;
              const active = activeCompanyUid
                ? (list.find((c) => c.uid === activeCompanyUid) ?? list[0])
                : list[0];
              this.selectedCompanyId.set(active.id);
              this.loadCategories(active.id);
              this.loadBranchOptions(active.uid);
            }
          },
          error: () => this.companyState.set('error'),
        });
      },
      error: () => this.companyState.set('error'),
    });
  }

  private loadBranchOptions(companyUid: string): void {
    this.branchService.list(companyUid).subscribe({
      next: (branches) => {
        this.branchIdByUid.clear();
        branches.forEach((b) => this.branchIdByUid.set(b.uid, b.id));
        const activeOptions = branches
          .filter((b) => b.status === 'ACTIVE')
          .map((b) => ({ uid: b.uid, label: b.name, hint: b.code }));
        this.branchOptions.set(activeOptions);
        // Default to the user's active branch; fall back to first active branch.
        if (!this.fBranchUid()) {
          const activeBranchUid = this.session.activeBranchUid() ?? null;
          const defaultBranch = activeBranchUid
            ? (branches.find((b) => b.uid === activeBranchUid && b.status === 'ACTIVE') ?? branches.find((b) => b.status === 'ACTIVE'))
            : branches.find((b) => b.status === 'ACTIVE');
          if (defaultBranch) this.fBranchUid.set(defaultBranch.uid);
        }
      },
      error: () => {},
    });
  }

  onCompanyChange(id: string): void {
    this.selectedCompanyId.set(id);
    this.fCategoryId.set('');
    this.fBranchUid.set('');
    if (id) {
      this.loadCategories(id);
      const company = this.companies().find((c) => c.id === id);
      if (company) this.loadBranchOptions(company.uid);
    }
  }

  private loadCategories(companyId: string): void {
    this.categoriesState.set('loading');
    this.faService.listCategories(companyId).subscribe({
      next: (cats) => {
        this.categories.set(cats.filter((c) => c.status === 'ACTIVE'));
        this.categoriesState.set('idle');
      },
      error: () => this.categoriesState.set('error'),
    });
  }

  /** Coerce a value from ngModel on type="number" to string; prevents .trim() crashes. */
  coerceNumStr(v: string | number | null | undefined): string {
    return v === null || v === undefined ? '' : String(v);
  }

  create(): void {
    const categoryId = this.fCategoryId().trim();
    const branchUid = this.fBranchUid().trim();
    const branchId = this.branchIdByUid.get(branchUid) ?? '';
    const name = this.fName().trim();
    const acquisitionCost = this.fAcquisitionCost().trim();
    const lifePeriods = parseInt(this.fLifePeriods().trim(), 10);
    const acquisitionDate = this.fAcquisitionDate().trim();
    const depStartDate = this.fDepStartDate().trim();

    if (!categoryId) { this.formError.set('Category is required.'); return; }
    if (!branchUid) { this.formError.set('Branch is required.'); return; }
    if (!branchId) { this.formError.set('Could not resolve branch ID — please re-select the branch.'); return; }
    if (!name) { this.formError.set('Asset name is required.'); return; }
    if (!acquisitionCost || isNaN(Number(acquisitionCost))) { this.formError.set('Acquisition cost is required.'); return; }
    if (!lifePeriods || lifePeriods < 1) { this.formError.set('Life periods must be at least 1.'); return; }
    if (!acquisitionDate) { this.formError.set('Acquisition date is required.'); return; }
    if (!depStartDate) { this.formError.set('Depreciation start date is required.'); return; }
    if (this.fMethod() === 'REDUCING_BALANCE' && !this.fReducingRate().trim()) {
      this.formError.set('Reducing rate is required for Reducing Balance method.');
      return;
    }

    this.saving.set(true);
    this.formError.set(null);

    const request: RegisterAssetRequest = {
      companyId: this.selectedCompanyId(),
      branchId,
      categoryId,
      name,
      acquisitionCost,
      salvageValue: this.fSalvageValue().trim() || undefined,
      depreciationMethod: this.fMethod(),
      lifePeriods,
      reducingRate: this.fMethod() === 'REDUCING_BALANCE' ? this.fReducingRate().trim() : undefined,
      acquisitionDate,
      depreciationStartDate: depStartDate,
      location: this.fLocation().trim() || undefined,
      costCentreId: this.fCostCentreId().trim() || undefined,
      assetTag: this.fAssetTag().trim() || undefined,
    };

    this.faService.register(request).subscribe({
      next: (created) => {
        this.saving.set(false);
        this.alerts.success('Asset registered', created.assetNumber);
        this.router.navigate(['/admin/fixed-assets/uid', created.uid]);
      },
      error: (err: unknown) => {
        this.formError.set(this.messageFrom(err, 'Could not register asset.'));
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
