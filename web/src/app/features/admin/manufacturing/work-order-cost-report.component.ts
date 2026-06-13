import { DecimalPipe } from '@angular/common';
import { Component, computed, inject, input, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { SessionStore } from '../../../core/auth/session.store';
import { WorkOrderCostReportDto } from './models/manufacturing.model';
import { ManufacturingService } from './manufacturing.service';

type LoadState = 'loading' | 'idle' | 'error';

/**
 * Per-order cost report. Read-only.
 * Route: /admin/work-orders/uid/:uid/cost-report
 * Perm: MANUFACTURING.VIEW (scoped)
 */
@Component({
  selector: 'app-work-order-cost-report',
  imports: [RouterLink, DecimalPipe],
  templateUrl: './work-order-cost-report.component.html',
  styleUrl: './work-order-cost-report.component.scss',
})
export class WorkOrderCostReportComponent {
  private readonly mfgService = inject(ManufacturingService);
  protected readonly session = inject(SessionStore);

  /** Route input bound via withComponentInputBinding. */
  readonly uid = input.required<string>();

  readonly report = signal<WorkOrderCostReportDto | null>(null);
  readonly state = signal<LoadState>('loading');

  readonly netWip = computed(() => {
    const r = this.report();
    if (!r) return 0;
    return +r.wipDebitTotal - +r.wipCreditTotal;
  });

  constructor() {
    queueMicrotask(() => this.load());
  }

  private load(): void {
    this.state.set('loading');
    this.mfgService.getCostReport(this.uid()).subscribe({
      next: (r) => {
        this.report.set(r);
        this.state.set('idle');
      },
      error: () => this.state.set('error'),
    });
  }
}
