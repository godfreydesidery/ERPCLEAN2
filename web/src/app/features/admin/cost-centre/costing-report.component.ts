import { HttpErrorResponse } from '@angular/common/http';
import { DecimalPipe } from '@angular/common';
import { Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { SessionStore } from '../../../core/auth/session.store';
import { Company } from '../models/company.model';
import { CompanyService } from '../company/company.service';
import { OrganisationService } from '../organisation/organisation.service';
import { GlService } from '../gl/gl.service';
import { FiscalPeriodDto } from '../gl/models/gl.model';
import {
  DimensionSlicedTbDto,
  DimensionSlicedTbRowDto,
  DimensionSlot,
  DimensionValueDto,
} from './models/cost-centre.model';
import { CostCentreService } from './cost-centre.service';

type LoadState = 'idle' | 'loading' | 'error' | 'forbidden';

const SLOT_OPTIONS: Array<{ value: DimensionSlot; label: string }> = [
  { value: 'COST_CENTRE', label: 'Cost Centre' },
  { value: 'DEPARTMENT', label: 'Department' },
  { value: 'DIMENSION_3', label: 'Dimension 3' },
  { value: 'DIMENSION_4', label: 'Dimension 4' },
];

/**
 * Dimension-sliced trial balance report.
 * Requires COSTING.VIEW + GL.VIEW.
 * Rows carry raw net (slice does NOT net to zero — do NOT show balanced indicator).
 * Negative net rendered in red/danger.
 * Route: /admin/cost-centre/report
 */
@Component({
  selector: 'app-costing-report',
  imports: [FormsModule, DecimalPipe],
  templateUrl: './costing-report.component.html',
  styleUrl: './costing-report.component.scss',
})
export class CostingReportComponent {
  private readonly service = inject(CostCentreService);
  private readonly glService = inject(GlService);
  private readonly companyService = inject(CompanyService);
  private readonly organisationService = inject(OrganisationService);
  protected readonly session = inject(SessionStore);

  // ── Company context ────────────────────────────────────────────────────────
  readonly companies = signal<Company[]>([]);
  readonly selectedCompanyId = signal('');
  readonly companyState = signal<'loading' | 'idle' | 'error'>('loading');

  // ── Filters ────────────────────────────────────────────────────────────────
  readonly slotOptions = SLOT_OPTIONS;
  readonly selectedSlot = signal<DimensionSlot>('COST_CENTRE');
  readonly selectedValueUid = signal('');
  readonly rollUp = signal(false);
  readonly selectedPeriodId = signal('');

  // ── Period selector ────────────────────────────────────────────────────────
  readonly periods = signal<FiscalPeriodDto[]>([]);
  readonly periodsState = signal<'idle' | 'loading' | 'error'>('idle');

  // ── Dimension values (for value picker) ───────────────────────────────────
  readonly values = signal<DimensionValueDto[]>([]);

  // ── Report state ──────────────────────────────────────────────────────────
  readonly report = signal<DimensionSlicedTbDto | null>(null);
  readonly state = signal<LoadState>('idle');

  readonly canView = computed(
    () => this.session.hasPermission('COSTING.VIEW') && this.session.hasPermission('GL.VIEW'),
  );

  readonly isEmpty = computed(() => this.state() === 'idle' && this.report() === null);

  /** Rows for display — already grouped by value (no sort needed, shown as returned). */
  readonly rows = computed<DimensionSlicedTbRowDto[]>(() => this.report()?.rows ?? []);

  readonly totalDebit = computed(() =>
    this.rows().reduce((s, r) => s + Number.parseFloat(r.totalDebit ?? '0'), 0),
  );
  readonly totalCredit = computed(() =>
    this.rows().reduce((s, r) => s + Number.parseFloat(r.totalCredit ?? '0'), 0),
  );
  readonly totalNet = computed(() =>
    this.rows().reduce((s, r) => s + Number.parseFloat(r.net ?? '0'), 0),
  );

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
              this.loadPeriods(list[0].id);
            }
          },
          error: () => this.companyState.set('error'),
        });
      },
      error: () => this.companyState.set('error'),
    });
  }

  private loadPeriods(companyId: string): void {
    this.periodsState.set('loading');
    this.glService.listPeriods(companyId).subscribe({
      next: (list) => {
        this.periods.set(list);
        this.periodsState.set('idle');
      },
      error: () => this.periodsState.set('error'),
    });
  }

  onCompanyChange(id: string): void {
    this.selectedCompanyId.set(id);
    this.report.set(null);
    this.selectedPeriodId.set('');
    this.selectedValueUid.set('');
    this.values.set([]);
    this.periods.set([]);
    if (id) this.loadPeriods(id);
  }

  onSlotChange(slot: DimensionSlot): void {
    this.selectedSlot.set(slot);
    this.selectedValueUid.set('');
    this.values.set([]);
    // Load values for picker when slot changes — optional; user can still run all-values report
  }

  loadReport(): void {
    const companyId = this.selectedCompanyId();
    if (!companyId) return;

    if (!this.canView()) {
      this.state.set('forbidden');
      return;
    }

    this.state.set('loading');
    this.report.set(null);

    const periodId = this.selectedPeriodId() || null;

    this.service
      .getSlicedTrialBalance(companyId, this.selectedSlot(), {
        valueUid: this.selectedValueUid() || null,
        rollUp: this.rollUp(),
        periodId,
      })
      .subscribe({
        next: (data) => {
          this.report.set(data);
          this.state.set('idle');
        },
        error: (err) =>
          this.state.set(err instanceof HttpErrorResponse && err.status === 403 ? 'forbidden' : 'error'),
      });
  }

  // ── Display helpers ────────────────────────────────────────────────────────

  netClass(net: string): string {
    const n = Number.parseFloat(net ?? '0');
    if (n < 0) return 'text-danger';
    return '';
  }

}
