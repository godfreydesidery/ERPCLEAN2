import { DatePipe } from '@angular/common';
import { HttpErrorResponse } from '@angular/common/http';
import { Component, OnInit, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { SessionStore } from '../../../core/auth/session.store';
import { formatMoney } from '../../../shared/money.util';
import { UidOption, UidPickerComponent } from '../../../shared/uid-picker/uid-picker.component';
import { BranchService } from '../branch/branch.service';
import { CompanyService } from '../company/company.service';
import { CostCentreService } from '../cost-centre/cost-centre.service';
import { Branch } from '../models/branch.model';
import { OrganisationService } from '../organisation/organisation.service';
import { downloadBlob } from '../reporting/reporting.utils';
import { FixedAssetsService } from './fixed-assets.service';
import {
  AssetCategoryDto,
  FaExportFormat,
  FixedAssetRegisterDto,
  FixedAssetRegisterFilter,
  FixedAssetRegisterRowDto,
  FixedAssetRegisterTotalDto,
  FixedAssetStatus,
} from './models/fixed-assets.model';
import { todayLocal } from '../../../shared/date.util';

type LoadState = 'idle' | 'loading' | 'error' | 'forbidden';

interface CategoryGroup {
  code: string;
  name: string;
  rows: FixedAssetRegisterRowDto[];
  total: FixedAssetRegisterTotalDto | null;
}

/**
 * Fixed Asset Register (FR-FA-17). Route: /admin/fixed-assets/register — gated FA.VIEW, the same
 * code as GET /api/v1/fixed-assets/register and the nav entry.
 *
 * As-at date (default today) + optional category / status / branch / location / cost-centre. The
 * report is company-scoped server-side from the session; the picker OPTIONS are loaded against the
 * caller's first company purely to fill the dropdowns (small master lists, not products).
 */
@Component({
  selector: 'app-fa-register-report',
  imports: [FormsModule, DatePipe, RouterLink, UidPickerComponent],
  templateUrl: './fa-register-report.component.html',
  styleUrl: './fa-register-report.component.scss',
})
export class FaRegisterReportComponent implements OnInit {
  private readonly faService = inject(FixedAssetsService);
  private readonly organisationService = inject(OrganisationService);
  private readonly companyService = inject(CompanyService);
  private readonly branchService = inject(BranchService);
  private readonly costCentreService = inject(CostCentreService);
  protected readonly session = inject(SessionStore);

  // ── Picker options ───────────────────────────────────────────────────────────
  readonly categories = signal<AssetCategoryDto[]>([]);
  readonly branches = signal<Branch[]>([]);
  readonly costCentres = signal<UidOption[]>([]);

  readonly categoryOptions = computed<UidOption[]>(() =>
    this.categories().map((c) => ({ uid: c.uid, label: c.name, hint: c.code })),
  );
  readonly branchOptions = computed<UidOption[]>(() =>
    this.branches().map((b) => ({ uid: b.uid, label: b.name, hint: b.code })),
  );

  readonly statusOptions: readonly { value: FixedAssetStatus; label: string }[] = [
    { value: 'IN_SERVICE', label: 'In service' },
    { value: 'DRAFT', label: 'Draft' },
    { value: 'DISPOSED', label: 'Disposed' },
    { value: 'WRITTEN_OFF', label: 'Written off' },
  ];

  // ── Filters ──────────────────────────────────────────────────────────────────
  readonly asOf = signal(this.today());
  readonly categoryUid = signal('');
  readonly status = signal<FixedAssetStatus | ''>('');
  readonly branchUid = signal('');
  readonly location = signal('');
  readonly costCentreUid = signal('');

  // ── Report ───────────────────────────────────────────────────────────────────
  readonly report = signal<FixedAssetRegisterDto | null>(null);
  readonly state = signal<LoadState>('idle');
  readonly errorMessage = signal<string | null>(null);
  readonly exporting = signal(false);
  readonly exportError = signal<string | null>(null);

  readonly canView = computed(() => this.session.hasPermission('FA.VIEW'));
  /** The export endpoint is FA.VIEW AND REPORT.EXPORT server-side. */
  readonly canExport = computed(
    () => this.session.hasPermission('FA.VIEW') && this.session.hasPermission('REPORT.EXPORT'),
  );
  readonly isEmpty = computed(() => this.state() === 'idle' && this.report() === null);

  /** Rows grouped by category (the server orders by category), each with its in-service subtotal. */
  readonly groups = computed<CategoryGroup[]>(() => {
    const r = this.report();
    if (!r) return [];
    const out: CategoryGroup[] = [];
    for (const row of r.rows) {
      let g = out.length ? out[out.length - 1] : undefined;
      if (!g || g.code !== row.categoryCode) {
        g = {
          code: row.categoryCode,
          name: row.categoryName,
          rows: [],
          total: r.categoryTotals.find((t) => t.categoryCode === row.categoryCode) ?? null,
        };
        out.push(g);
      }
      g.rows.push(row);
    }
    return out;
  });

  readonly fmtMoney = formatMoney;

  ngOnInit(): void {
    if (this.canView()) this.loadFilterOptions();
  }

  private loadFilterOptions(): void {
    this.organisationService.current().subscribe({
      next: (org) => {
        this.companyService.list(org.uid).subscribe({
          next: (list) => {
            if (list.length === 0) return;
            const company = list[0];
            this.faService.listCategories(company.id).subscribe({
              next: (cats) => this.categories.set(cats.filter((c) => c.status === 'ACTIVE')),
              error: () => undefined,
            });
            this.branchService.list(company.uid).subscribe({
              next: (bs) => this.branches.set(bs),
              error: () => undefined,
            });
            this.costCentreService.listDimensions(company.id).subscribe({
              next: (dims) => {
                const cc = dims.find((d) => d.slot === 'COST_CENTRE');
                if (!cc) return;
                this.costCentreService.listValues(cc.uid, 0, 200).subscribe({
                  next: ({ rows }) =>
                    this.costCentres.set(rows.map((v) => ({ uid: v.uid, label: v.name, hint: v.code }))),
                  error: () => undefined,
                });
              },
              error: () => undefined,
            });
          },
          error: () => undefined,
        });
      },
      error: () => undefined,
    });
  }

  private filter(): FixedAssetRegisterFilter | null {
    const asOf = String(this.asOf() ?? '').trim();
    if (!asOf) return null;
    return {
      asOf,
      categoryUid: this.categoryUid() || null,
      status: this.status() || null,
      branchUid: this.branchUid() || null,
      location: this.location().trim() || null,
      costCentreUid: this.costCentreUid() || null,
    };
  }

  run(): void {
    const f = this.filter();
    if (!f) return;
    this.state.set('loading');
    this.report.set(null);
    this.errorMessage.set(null);
    this.faService.getRegister(f).subscribe({
      next: (dto) => {
        this.report.set(dto);
        this.state.set('idle');
      },
      error: (err: unknown) => {
        if (err instanceof HttpErrorResponse && err.status === 403) {
          // Either no FA.VIEW, or a branch the caller is not assigned to — the server's message
          // (user-safe) says which.
          this.errorMessage.set(this.firstError(err));
          this.state.set('forbidden');
          return;
        }
        if (err instanceof HttpErrorResponse && (err.status === 400 || err.status === 404)) {
          this.errorMessage.set(this.firstError(err) ?? 'Check the filters and try again.');
        }
        this.state.set('error');
      },
    });
  }

  export(format: FaExportFormat): void {
    const f = this.filter();
    if (!f || this.exporting()) return;
    this.exporting.set(true);
    this.exportError.set(null);
    this.faService.exportRegister(f, format).subscribe({
      next: (blob) => {
        downloadBlob(blob, `fixed-asset-register_${f.asOf}.${format.toLowerCase()}`);
        this.exporting.set(false);
      },
      error: (err: unknown) => {
        this.exporting.set(false);
        const status = err instanceof HttpErrorResponse ? err.status : 0;
        this.exportError.set(
          status === 401 || status === 403
            ? "You don't have permission to export this report."
            : 'Could not export the register. Please try again.',
        );
      },
    });
  }

  statusLabel(s: FixedAssetStatus): string {
    return this.statusOptions.find((o) => o.value === s)?.label ?? s;
  }

  private firstError(err: HttpErrorResponse): string | null {
    const errors = (err.error as { errors?: unknown[] } | null)?.errors;
    const first = errors?.length ? errors[0] : null;
    return typeof first === 'string' ? first : null;
  }

  private today(): string {
    return todayLocal();
  }
}
