import { DecimalPipe } from '@angular/common';
import { Component, inject, input, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { FiscalPeriodDto } from '../gl/models/gl.model';
import { GlService } from '../gl/gl.service';
import { CompanyService } from '../company/company.service';
import { OrganisationService } from '../organisation/organisation.service';
import { DepreciationRunDto } from './models/fixed-assets.model';
import { FixedAssetsService } from './fixed-assets.service';

type LoadState = 'loading' | 'idle' | 'error';

/**
 * Depreciation Run detail screen. Route: /admin/depreciation-runs/uid/:uid
 * Shows run header + all per-asset lines.
 */
@Component({
  selector: 'app-depreciation-run-detail',
  imports: [RouterLink, DecimalPipe],
  templateUrl: './depreciation-run-detail.component.html',
  styleUrl: './depreciation-run-detail.component.scss',
})
export class DepreciationRunDetailComponent {
  private readonly faService = inject(FixedAssetsService);
  private readonly glService = inject(GlService);
  private readonly companyService = inject(CompanyService);
  private readonly organisationService = inject(OrganisationService);

  readonly uid = input.required<string>();

  readonly run = signal<DepreciationRunDto | null>(null);
  readonly state = signal<LoadState>('loading');

  private readonly periodById = signal<Map<string, FiscalPeriodDto>>(new Map());

  constructor() {
    queueMicrotask(() => this.init());
  }

  private init(): void {
    this.state.set('loading');
    this.faService.getRunByUid(this.uid()).subscribe({
      next: (run) => {
        this.run.set(run);
        this.state.set('idle');
        this.loadPeriods(run.companyId);
      },
      error: () => this.state.set('error'),
    });
  }

  private loadPeriods(companyId: string): void {
    this.glService.listPeriods(companyId).subscribe({
      next: (periods) => {
        this.periodById.set(new Map(periods.map((p) => [p.id, p])));
      },
      error: () => undefined,
    });
  }

  periodLabel(fiscalPeriodId: string): string {
    const p = this.periodById().get(fiscalPeriodId);
    return p ? `Period ${p.periodNo} (${p.startDate} – ${p.endDate})` : fiscalPeriodId;
  }
}
