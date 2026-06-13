import { DecimalPipe } from '@angular/common';
import { Component, inject, input, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
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

  readonly uid = input.required<string>();

  readonly run = signal<DepreciationRunDto | null>(null);
  readonly state = signal<LoadState>('loading');

  constructor() {
    queueMicrotask(() => this.init());
  }

  private init(): void {
    this.state.set('loading');
    this.faService.getRunByUid(this.uid()).subscribe({
      next: (run) => { this.run.set(run); this.state.set('idle'); },
      error: () => this.state.set('error'),
    });
  }
}
