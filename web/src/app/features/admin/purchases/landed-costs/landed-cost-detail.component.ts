import { HttpErrorResponse } from '@angular/common/http';
import { DatePipe, DecimalPipe } from '@angular/common';
import { Component, computed, inject, input, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { AlertService } from '../../../../core/feedback/alert.service';
import { SessionStore } from '../../../../core/auth/session.store';
import { LandedCostDto } from '../../models/purchases.model';
import { LandedCostService } from './landed-cost.service';

type LoadState = 'loading' | 'idle' | 'error';

@Component({
  selector: 'app-landed-cost-detail',
  imports: [RouterLink, DatePipe, DecimalPipe],
  templateUrl: './landed-cost-detail.component.html',
  styleUrl: './landed-cost-detail.component.scss',
})
export class LandedCostDetailComponent {
  private readonly landedCostService = inject(LandedCostService);
  private readonly alerts = inject(AlertService);
  protected readonly session = inject(SessionStore);

  readonly uid = input.required<string>();

  readonly entity = signal<LandedCostDto | null>(null);
  readonly state = signal<LoadState>('loading');

  readonly confirming = signal(false);
  readonly confirmError = signal<string | null>(null);

  readonly canConfirm = computed(() => this.session.hasPermission('PURCHASE.LANDEDCOST.MANAGE'));
  readonly isDraft = computed(() => this.entity()?.status === 'DRAFT');

  constructor() {
    queueMicrotask(() => this.load());
  }

  private load(): void {
    this.state.set('loading');
    this.landedCostService.getByUid(this.uid()).subscribe({
      next: (lc) => { this.entity.set(lc); this.state.set('idle'); },
      error: () => this.state.set('error'),
    });
  }

  confirm(): void {
    if (this.confirming()) return;
    this.confirming.set(true);
    this.confirmError.set(null);
    this.landedCostService.confirm(this.uid()).subscribe({
      next: (updated) => {
        this.confirming.set(false);
        this.entity.set(updated);
        this.alerts.success('Landed cost confirmed', updated.landedCostNumber);
      },
      error: (err) => {
        this.confirmError.set(this.messageFrom(err, 'Could not confirm landed cost.'));
        this.confirming.set(false);
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
