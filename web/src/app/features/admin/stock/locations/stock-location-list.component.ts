import { HttpErrorResponse } from '@angular/common/http';
import { Component, computed, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { Subject, switchMap } from 'rxjs';
import { PageMeta } from '../../../../core/api/api-response.model';
import { AlertService } from '../../../../core/feedback/alert.service';
import { SessionStore } from '../../../../core/auth/session.store';
import { Company } from '../../models/company.model';
import { Branch } from '../../models/branch.model';
import { CompanyService } from '../../company/company.service';
import { BranchService } from '../../branch/branch.service';
import { OrganisationService } from '../../organisation/organisation.service';
import { StockLocationService } from './stock-location.service';
import {
  CreateStockLocationRequest,
  LocationType,
  StockLocationDto,
  UpdateStockLocationRequest,
} from './stock-location.model';
import { PaginatorComponent } from '../../../../shared/paginator/paginator.component';
import { UidPickerComponent } from '../../../../shared/uid-picker/uid-picker.component';
import type { UidOption } from '../../../../shared/uid-picker/uid-picker.component';

const DEFAULT_SIZE = 20;

interface LoadTrigger { page: number }

/**
 * Stock Location list screen.
 * - Inline create form (STOCK.LOCATION.MANAGE).
 * - Inline edit (name + locationType).
 * - Row actions: deactivate / reactivate / set-default.
 * - Branch picker for create uses <app-uid-picker>.
 * Route: /admin/stock/locations
 */
@Component({
  selector: 'app-stock-location-list',
  imports: [FormsModule, PaginatorComponent, UidPickerComponent],
  templateUrl: './stock-location-list.component.html',
  styleUrl: './stock-location-list.component.scss',
})
export class StockLocationListComponent {
  private readonly locationService = inject(StockLocationService);
  private readonly companyService = inject(CompanyService);
  private readonly branchService = inject(BranchService);
  private readonly organisationService = inject(OrganisationService);
  private readonly alerts = inject(AlertService);
  protected readonly session = inject(SessionStore);

  // ── Company / Branch context ──────────────────────────────────────────────
  readonly companies = signal<Company[]>([]);
  readonly selectedCompanyId = signal('');
  readonly companyState = signal<'loading' | 'idle' | 'error'>('loading');
  readonly branches = signal<Branch[]>([]);

  // ── Branch picker options for create form ─────────────────────────────────
  readonly branchOptions = computed<UidOption[]>(() =>
    this.branches().map((b) => ({ uid: b.uid, label: b.name, hint: b.code })),
  );

  // ── List state ────────────────────────────────────────────────────────────
  readonly rows = signal<StockLocationDto[]>([]);
  readonly meta = signal<PageMeta>({ page: 0, size: DEFAULT_SIZE, totalElements: 0, totalPages: 0, hasNext: false });
  readonly state = signal<'loading' | 'idle' | 'error' | 'forbidden'>('idle');
  readonly currentPage = signal(0);
  readonly isEmpty = computed(() => this.state() === 'idle' && this.rows().length === 0);

  // ── Permissions ───────────────────────────────────────────────────────────
  readonly canManage = computed(() => this.session.hasPermission('STOCK.LOCATION.MANAGE'));
  readonly canView = computed(() => this.session.hasPermission('STOCK.LOCATION.VIEW'));

  // ── Create form ───────────────────────────────────────────────────────────
  readonly showCreateForm = signal(false);
  readonly fCode = signal('');
  readonly fName = signal('');
  readonly fLocationType = signal<LocationType>('WAREHOUSE');
  readonly fBranchUid = signal('');
  readonly fMakeDefault = signal(false);
  readonly saving = signal(false);
  readonly formError = signal<string | null>(null);

  // ── Edit form ─────────────────────────────────────────────────────────────
  readonly editingUid = signal<string | null>(null);
  readonly eName = signal('');
  readonly eLocationType = signal<LocationType>('WAREHOUSE');
  readonly editSaving = signal(false);
  readonly editError = signal<string | null>(null);

  // ── Row action busy state ─────────────────────────────────────────────────
  readonly actionBusyUid = signal<string | null>(null);

  readonly locationTypes: LocationType[] = ['WAREHOUSE', 'STORE', 'VAN', 'QUARANTINE', 'OTHER'];

  private readonly immediateTrigger$ = new Subject<LoadTrigger>();

  constructor() {
    this.immediateTrigger$
      .pipe(
        switchMap(({ page }) => {
          this.state.set('loading');
          this.currentPage.set(page);
          return this.locationService.list(page, DEFAULT_SIZE);
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

    this.loadCompanies();
  }

  // ── Company / Branch loading ──────────────────────────────────────────────

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
              this.loadBranches(list[0].uid);
              this.load(0);
            }
          },
          error: () => this.companyState.set('error'),
        });
      },
      error: () => this.companyState.set('error'),
    });
  }

  private loadBranches(companyUid: string): void {
    this.branchService.list(companyUid).subscribe({
      next: (list) => this.branches.set(list),
      error: () => this.branches.set([]),
    });
  }

  onCompanyChange(id: string): void {
    this.selectedCompanyId.set(id);
    const company = this.companies().find((c) => c.id === id);
    if (company) this.loadBranches(company.uid);
    if (id) this.load(0);
  }

  load(page: number): void {
    this.immediateTrigger$.next({ page });
  }

  goToPage(page: number): void { this.load(page); }

  // ── Create form ───────────────────────────────────────────────────────────

  toggleCreateForm(): void {
    this.showCreateForm.update((v) => !v);
    if (!this.showCreateForm()) this.resetCreateForm();
    this.formError.set(null);
  }

  private resetCreateForm(): void {
    this.fCode.set('');
    this.fName.set('');
    this.fLocationType.set('WAREHOUSE');
    this.fBranchUid.set('');
    this.fMakeDefault.set(false);
  }

  submitCreate(): void {
    const code = this.fCode().trim();
    const name = this.fName().trim();
    const branchUid = this.fBranchUid();
    if (!code) { this.formError.set('Code is required.'); return; }
    if (!name) { this.formError.set('Name is required.'); return; }
    if (!branchUid) { this.formError.set('Select a branch.'); return; }

    this.saving.set(true);
    this.formError.set(null);
    const request: CreateStockLocationRequest = {
      code,
      name,
      locationType: this.fLocationType(),
      branchUid,
      makeDefault: this.fMakeDefault(),
    };
    this.locationService.create(request).subscribe({
      next: (loc) => {
        this.saving.set(false);
        this.showCreateForm.set(false);
        this.resetCreateForm();
        this.alerts.success('Location created', loc.code);
        this.load(this.currentPage());
      },
      error: (err) => {
        this.formError.set(this.messageFrom(err, 'Could not create location.'));
        this.saving.set(false);
      },
    });
  }

  // ── Edit form ─────────────────────────────────────────────────────────────

  startEdit(row: StockLocationDto): void {
    this.editingUid.set(row.uid);
    this.eName.set(row.name);
    this.eLocationType.set(row.locationType);
    this.editError.set(null);
  }

  cancelEdit(): void {
    this.editingUid.set(null);
    this.editError.set(null);
  }

  submitEdit(row: StockLocationDto): void {
    const name = this.eName().trim();
    if (!name) { this.editError.set('Name is required.'); return; }

    this.editSaving.set(true);
    this.editError.set(null);
    const request: UpdateStockLocationRequest = {
      name,
      locationType: this.eLocationType(),
    };
    this.locationService.update(row.uid, request).subscribe({
      next: (updated) => {
        this.editSaving.set(false);
        this.editingUid.set(null);
        this.rows.update((rs) => rs.map((r) => (r.uid === updated.uid ? updated : r)));
        this.alerts.success('Location updated', updated.name);
      },
      error: (err) => {
        this.editError.set(this.messageFrom(err, 'Could not update location.'));
        this.editSaving.set(false);
      },
    });
  }

  // ── Row actions ───────────────────────────────────────────────────────────

  deactivate(row: StockLocationDto): void {
    this.actionBusyUid.set(row.uid);
    this.locationService.deactivate(row.uid).subscribe({
      next: () => {
        this.actionBusyUid.set(null);
        this.rows.update((rs) => rs.map((r) =>
          r.uid === row.uid ? { ...r, status: 'INACTIVE' as const } : r,
        ));
        this.alerts.success('Location deactivated', row.name);
      },
      error: (err) => {
        this.actionBusyUid.set(null);
        this.alerts.error('Deactivate failed', this.messageFrom(err, 'Could not deactivate.'));
      },
    });
  }

  reactivate(row: StockLocationDto): void {
    this.actionBusyUid.set(row.uid);
    this.locationService.reactivate(row.uid).subscribe({
      next: (updated) => {
        this.actionBusyUid.set(null);
        this.rows.update((rs) => rs.map((r) => (r.uid === updated.uid ? updated : r)));
        this.alerts.success('Location reactivated', updated.name);
      },
      error: (err) => {
        this.actionBusyUid.set(null);
        this.alerts.error('Reactivate failed', this.messageFrom(err, 'Could not reactivate.'));
      },
    });
  }

  setDefault(row: StockLocationDto): void {
    this.actionBusyUid.set(row.uid);
    this.locationService.setDefault(row.uid).subscribe({
      next: (updated) => {
        this.actionBusyUid.set(null);
        // Re-load so the old default gets cleared visually
        this.load(this.currentPage());
        this.alerts.success('Default location set', updated.name);
      },
      error: (err) => {
        this.actionBusyUid.set(null);
        this.alerts.error('Set default failed', this.messageFrom(err, 'Could not set default.'));
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
