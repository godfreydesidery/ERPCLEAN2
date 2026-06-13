import { HttpErrorResponse } from '@angular/common/http';
import { Component, computed, inject, input, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { AlertService } from '../../../core/feedback/alert.service';
import { SessionStore } from '../../../core/auth/session.store';
import { DimensionValueDto, UpdateDimensionValueRequest } from './models/cost-centre.model';
import { CostCentreService } from './cost-centre.service';

type LoadState = 'loading' | 'idle' | 'error';

/**
 * Dimension-value detail + edit screen.
 * Edit: name, parent (by UID) or clear parent.
 * Actions: deactivate / activate (COSTING.MANAGE).
 * Route: /admin/cost-centre/values/uid/:uid
 */
@Component({
  selector: 'app-dimension-value-detail',
  imports: [FormsModule, RouterLink],
  templateUrl: './dimension-value-detail.component.html',
  styleUrl: './dimension-value-detail.component.scss',
})
export class DimensionValueDetailComponent {
  private readonly service = inject(CostCentreService);
  private readonly alerts = inject(AlertService);
  protected readonly session = inject(SessionStore);

  /** Route input bound via withComponentInputBinding. */
  readonly uid = input.required<string>();

  // ── Entity ─────────────────────────────────────────────────────────────────
  readonly entity = signal<DimensionValueDto | null>(null);
  readonly entityState = signal<LoadState>('loading');

  // ── Edit form fields ───────────────────────────────────────────────────────
  readonly fName = signal('');
  readonly fParentUid = signal('');
  readonly fClearParent = signal(false);
  readonly saving = signal(false);
  readonly saveError = signal<string | null>(null);

  // ── Action busy ────────────────────────────────────────────────────────────
  readonly actioning = signal(false);

  readonly canManage = computed(() => this.session.hasPermission('COSTING.MANAGE'));

  constructor() {
    queueMicrotask(() => this.init());
  }

  private init(): void {
    this.loadEntity();
  }

  private loadEntity(): void {
    this.entityState.set('loading');
    this.service.getValueByUid(this.uid()).subscribe({
      next: (v) => {
        this.entity.set(v);
        this.entityState.set('idle');
        this.patchForm(v);
      },
      error: () => this.entityState.set('error'),
    });
  }

  private patchForm(v: DimensionValueDto): void {
    this.fName.set(v.name ?? '');
    this.fParentUid.set(v.parentUid ?? '');
    this.fClearParent.set(false);
  }

  save(): void {
    const name = this.fName().trim();
    if (!name) {
      this.saveError.set('Name is required.');
      return;
    }
    this.saving.set(true);
    this.saveError.set(null);

    const clearParent = this.fClearParent();
    const parentUid = clearParent ? null : (this.fParentUid().trim() || null);

    const request: UpdateDimensionValueRequest = {
      name,
      parentUid,
      clearParent: clearParent || null,
    };

    this.service.updateValue(this.uid(), request).subscribe({
      next: (updated) => {
        this.entity.set(updated);
        this.patchForm(updated);
        this.saving.set(false);
        this.alerts.success('Value saved', updated.name);
      },
      error: (err) => {
        this.saveError.set(this.messageFrom(err, 'Could not save value.'));
        this.saving.set(false);
      },
    });
  }

  deactivate(): void {
    if (this.actioning()) return;
    this.actioning.set(true);
    this.service.deactivateValue(this.uid()).subscribe({
      next: (updated) => {
        this.entity.set(updated);
        this.actioning.set(false);
        this.alerts.success('Value deactivated', updated.name);
      },
      error: (err) => {
        this.actioning.set(false);
        this.saveError.set(this.messageFrom(err, 'Could not deactivate value.'));
      },
    });
  }

  activate(): void {
    if (this.actioning()) return;
    this.actioning.set(true);
    this.service.activateValue(this.uid()).subscribe({
      next: (updated) => {
        this.entity.set(updated);
        this.actioning.set(false);
        this.alerts.success('Value activated', updated.name);
      },
      error: (err) => {
        this.actioning.set(false);
        this.saveError.set(this.messageFrom(err, 'Could not activate value.'));
      },
    });
  }

  statusBadgeClass(status: string): string {
    switch (status) {
      case 'ACTIVE': return 'text-bg-success';
      case 'INACTIVE': return 'text-bg-secondary';
      case 'ARCHIVED': return 'text-bg-dark';
      default: return 'text-bg-secondary';
    }
  }

  private messageFrom(err: unknown, fallback: string): string {
    if (err instanceof HttpErrorResponse) {
      const errors = (err.error as { errors?: string[] })?.errors;
      if (errors?.length) return errors[0];
    }
    return fallback;
  }
}
