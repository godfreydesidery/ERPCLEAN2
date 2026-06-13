import { HttpErrorResponse } from '@angular/common/http';
import { Component, computed, inject, input, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { AlertService } from '../../../core/feedback/alert.service';
import { SessionStore } from '../../../core/auth/session.store';
import { HrPayrollService } from './hr-payroll.service';
import { CreatePayComponentRequest, PayComponentBasis, PayComponentDto, PayComponentKind } from './models/hr-payroll.model';

/**
 * Pay-component detail + edit + deactivate screen.
 * Route: /admin/hr/pay-components/uid/:uid
 */
@Component({
  selector: 'app-pay-component-detail',
  imports: [FormsModule, RouterLink],
  templateUrl: './pay-component-detail.component.html',
  styleUrl: './pay-component-detail.component.scss',
})
export class PayComponentDetailComponent {
  private readonly hrService = inject(HrPayrollService);
  private readonly alerts = inject(AlertService);
  protected readonly session = inject(SessionStore);

  readonly uid = input.required<string>();

  readonly comp = signal<PayComponentDto | null>(null);
  readonly state = signal<'loading' | 'idle' | 'error'>('loading');

  // ── Edit form ────────────────────────────────────────────────────────────────
  readonly fCode = signal('');
  readonly fName = signal('');
  readonly fKind = signal<PayComponentKind>('EARNING');
  readonly fBasis = signal<PayComponentBasis>('FIXED');
  readonly fGlAccountId = signal('');
  readonly fTaxable = signal(false);
  readonly fPensionable = signal(false);
  readonly saving = signal(false);
  readonly saveError = signal<string | null>(null);
  readonly deactivating = signal(false);

  readonly canManage = computed(() => this.session.hasPermission('HR.PAYCOMPONENT.MANAGE'));

  constructor() {
    queueMicrotask(() => this.init());
  }

  private init(): void {
    this.state.set('loading');
    this.hrService.getPayComponentByUid(this.uid()).subscribe({
      next: (c) => {
        this.comp.set(c);
        this.state.set('idle');
        this.patchForm(c);
      },
      error: () => this.state.set('error'),
    });
  }

  private patchForm(c: PayComponentDto): void {
    this.fCode.set(c.code);
    this.fName.set(c.name);
    this.fKind.set(c.kind);
    this.fBasis.set(c.basis);
    this.fGlAccountId.set(c.glAccountId);
    this.fTaxable.set(c.taxable);
    this.fPensionable.set(c.pensionable);
  }

  save(): void {
    const code = this.fCode().trim();
    const name = this.fName().trim();
    const glAccountId = this.fGlAccountId().trim();

    if (!code) { this.saveError.set('Code is required.'); return; }
    if (!name) { this.saveError.set('Name is required.'); return; }
    if (!glAccountId) { this.saveError.set('GL Account ID is required.'); return; }

    this.saving.set(true);
    this.saveError.set(null);

    const request: CreatePayComponentRequest = {
      code,
      name,
      kind: this.fKind(),
      basis: this.fBasis(),
      glAccountId,
      taxable: this.fTaxable(),
      pensionable: this.fPensionable(),
    };

    this.hrService.updatePayComponent(this.uid(), request).subscribe({
      next: (updated) => {
        this.comp.set(updated);
        this.saving.set(false);
        this.alerts.success('Pay component saved', updated.name);
      },
      error: (err) => {
        this.saveError.set(this.messageFrom(err, 'Could not save pay component.'));
        this.saving.set(false);
      },
    });
  }

  deactivate(): void {
    if (this.deactivating()) return;
    this.deactivating.set(true);
    this.saveError.set(null);
    this.hrService.deactivatePayComponent(this.uid()).subscribe({
      next: () => {
        this.deactivating.set(false);
        this.comp.update((c) => (c ? { ...c, active: false } : c));
        this.alerts.success('Pay component deactivated');
      },
      error: (err) => {
        this.deactivating.set(false);
        this.saveError.set(this.messageFrom(err, 'Could not deactivate pay component.'));
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
