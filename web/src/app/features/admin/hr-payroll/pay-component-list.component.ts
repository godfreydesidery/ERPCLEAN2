import { HttpErrorResponse } from '@angular/common/http';
import { Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { AlertService } from '../../../core/feedback/alert.service';
import { SessionStore } from '../../../core/auth/session.store';
import { Company } from '../models/company.model';
import { CompanyService } from '../company/company.service';
import { OrganisationService } from '../organisation/organisation.service';
import { HrPayrollService } from './hr-payroll.service';
import { CreatePayComponentRequest, PayComponentBasis, PayComponentDto, PayComponentKind } from './models/hr-payroll.model';

/**
 * Pay-components list (non-paginated) with inline create.
 * Route: /admin/hr/pay-components
 * Both VIEW and MANAGE use HR.PAYCOMPONENT.MANAGE (no separate view perm).
 */
@Component({
  selector: 'app-pay-component-list',
  imports: [FormsModule, RouterLink],
  templateUrl: './pay-component-list.component.html',
  styleUrl: './pay-component-list.component.scss',
})
export class PayComponentListComponent {
  private readonly hrService = inject(HrPayrollService);
  private readonly companyService = inject(CompanyService);
  private readonly organisationService = inject(OrganisationService);
  private readonly alerts = inject(AlertService);
  protected readonly session = inject(SessionStore);

  // ── Company context ──────────────────────────────────────────────────────────
  readonly companies = signal<Company[]>([]);
  readonly selectedCompanyId = signal('');
  readonly companyState = signal<'loading' | 'idle' | 'error'>('loading');

  // ── List state ───────────────────────────────────────────────────────────────
  readonly rows = signal<PayComponentDto[]>([]);
  readonly state = signal<'loading' | 'idle' | 'error' | 'forbidden'>('idle');

  // ── Create form ──────────────────────────────────────────────────────────────
  readonly showCreateForm = signal(false);
  readonly fCode = signal('');
  readonly fName = signal('');
  readonly fKind = signal<PayComponentKind>('EARNING');
  readonly fBasis = signal<PayComponentBasis>('FIXED');
  readonly fGlAccountId = signal('');
  readonly fTaxable = signal(false);
  readonly fPensionable = signal(false);
  readonly saving = signal(false);
  readonly formError = signal<string | null>(null);

  readonly canManage = computed(() => this.session.hasPermission('HR.PAYCOMPONENT.MANAGE'));
  readonly isEmpty = computed(() => this.state() === 'idle' && this.rows().length === 0);

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
              this.load();
            }
          },
          error: () => this.companyState.set('error'),
        });
      },
      error: () => this.companyState.set('error'),
    });
  }

  onCompanyChange(id: string): void {
    this.selectedCompanyId.set(id);
    if (id) this.load();
  }

  load(): void {
    const companyId = this.selectedCompanyId();
    if (!companyId) return;
    this.state.set('loading');
    this.hrService.listPayComponents(companyId).subscribe({
      next: (rows) => {
        this.rows.set(rows);
        this.state.set('idle');
      },
      error: (err: unknown) =>
        this.state.set(err instanceof HttpErrorResponse && err.status === 403 ? 'forbidden' : 'error'),
    });
  }

  toggleCreateForm(): void {
    this.showCreateForm.update((v) => !v);
    this.formError.set(null);
    if (!this.showCreateForm()) this.resetCreateForm();
  }

  private resetCreateForm(): void {
    this.fCode.set('');
    this.fName.set('');
    this.fKind.set('EARNING');
    this.fBasis.set('FIXED');
    this.fGlAccountId.set('');
    this.fTaxable.set(false);
    this.fPensionable.set(false);
  }

  create(): void {
    const code = this.fCode().trim();
    const name = this.fName().trim();
    const glAccountId = this.fGlAccountId().trim();

    if (!code) { this.formError.set('Code is required.'); return; }
    if (!name) { this.formError.set('Name is required.'); return; }
    if (!glAccountId) { this.formError.set('GL Account ID is required.'); return; }

    this.saving.set(true);
    this.formError.set(null);

    const request: CreatePayComponentRequest = {
      code,
      name,
      kind: this.fKind(),
      basis: this.fBasis(),
      glAccountId,
      taxable: this.fTaxable(),
      pensionable: this.fPensionable(),
    };

    this.hrService.createPayComponent(request).subscribe({
      next: (created) => {
        this.saving.set(false);
        this.resetCreateForm();
        this.showCreateForm.set(false);
        this.alerts.success('Pay component created', created.name);
        this.load();
      },
      error: (err: unknown) => {
        this.formError.set(this.messageFrom(err, 'Could not create pay component.'));
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
