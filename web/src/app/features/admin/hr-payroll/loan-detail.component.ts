import { HttpErrorResponse } from '@angular/common/http';
import { DecimalPipe } from '@angular/common';
import { Component, computed, inject, input, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { AlertService } from '../../../core/feedback/alert.service';
import { SessionStore } from '../../../core/auth/session.store';
import { CompanyService } from '../company/company.service';
import { OrganisationService } from '../organisation/organisation.service';
import { AccountDto } from '../gl/models/gl.model';
import { GlService } from '../gl/gl.service';
import { HrPayrollService } from './hr-payroll.service';
import { EmployeeLoanDto } from './models/hr-payroll.model';

/**
 * Loan detail screen with approve action.
 * Route: /admin/hr/loans/uid/:uid
 */
@Component({
  selector: 'app-loan-detail',
  imports: [RouterLink, DecimalPipe],
  templateUrl: './loan-detail.component.html',
  styleUrl: './loan-detail.component.scss',
})
export class LoanDetailComponent {
  private readonly hrService = inject(HrPayrollService);
  private readonly glService = inject(GlService);
  private readonly companyService = inject(CompanyService);
  private readonly organisationService = inject(OrganisationService);
  private readonly alerts = inject(AlertService);
  protected readonly session = inject(SessionStore);

  private readonly glAccountMap = signal<Map<string, AccountDto>>(new Map());

  readonly uid = input.required<string>();

  readonly loan = signal<EmployeeLoanDto | null>(null);
  readonly state = signal<'loading' | 'idle' | 'error'>('loading');
  readonly approving = signal(false);
  readonly actionError = signal<string | null>(null);

  readonly canManage = computed(() => this.session.hasPermission('HR.LOAN.MANAGE'));
  /** Approve is valid for loans not yet ACTIVE (i.e. still in the initial pending state) */
  readonly canApproveAction = computed(() => {
    const s = this.loan()?.status;
    return s !== 'ACTIVE' && s !== 'SETTLED' && s !== 'CANCELLED';
  });

  constructor() {
    queueMicrotask(() => this.init());
  }

  private init(): void {
    this.state.set('loading');
    this.loadGlAccounts();
    this.hrService.getLoanByUid(this.uid()).subscribe({
      next: (l) => {
        this.loan.set(l);
        this.state.set('idle');
      },
      error: () => this.state.set('error'),
    });
  }

  private loadGlAccounts(): void {
    this.organisationService.current().subscribe({
      next: (org) => {
        this.companyService.list(org.uid).subscribe({
          next: (companies) => {
            if (companies.length === 0) return;
            this.glService.listAllActiveAccounts(companies[0].id).subscribe({
              next: (accounts) => {
                this.glAccountMap.set(new Map(accounts.map((a) => [a.id, a])));
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

  glAccountLabel(glAccountId: string): string {
    const acc = this.glAccountMap().get(String(glAccountId));
    return acc ? `${acc.accountCode} — ${acc.name}` : String(glAccountId ?? '');
  }

  approve(): void {
    if (this.approving()) return;
    this.approving.set(true);
    this.actionError.set(null);
    this.hrService.approveLoan(this.uid()).subscribe({
      next: (updated) => {
        this.loan.set(updated);
        this.approving.set(false);
        this.alerts.success('Loan approved', updated.loanNumber);
      },
      error: (err) => {
        this.approving.set(false);
        this.actionError.set(this.messageFrom(err, 'Could not approve loan.'));
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
