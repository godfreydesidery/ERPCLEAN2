import { HttpErrorResponse } from '@angular/common/http';
import { DecimalPipe } from '@angular/common';
import { Component, computed, inject, signal } from '@angular/core';
import { takeUntilDestroyed, toObservable } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { debounceTime, distinctUntilChanged, map, merge, skip, Subject, switchMap } from 'rxjs';
import { PageMeta } from '../../../core/api/api-response.model';
import { AlertService } from '../../../core/feedback/alert.service';
import { SessionStore } from '../../../core/auth/session.store';
import { Company } from '../models/company.model';
import { CompanyService } from '../company/company.service';
import { OrganisationService } from '../organisation/organisation.service';
import { ApprovalsService } from './approvals.service';
import type { ApprovalPolicyPage } from './approvals.service';
import {
  ApprovalPolicyDto,
  CreateApprovalPolicyRequest,
  MasterStatus,
  PolicyBranchScope,
  PolicyStepInputDto,
} from './models/approvals.model';

const DEFAULT_SIZE = 20;

interface LoadTrigger { page: number }

/**
 * Approval policies list + inline create.
 * Route: /admin/approvals/policies
 */
@Component({
  selector: 'app-approval-policy-list',
  imports: [FormsModule, RouterLink, DecimalPipe],
  templateUrl: './approval-policy-list.component.html',
  styleUrl: './approval-policy-list.component.scss',
})
export class ApprovalPolicyListComponent {
  private readonly approvalsService = inject(ApprovalsService);
  private readonly companyService = inject(CompanyService);
  private readonly organisationService = inject(OrganisationService);
  private readonly alerts = inject(AlertService);
  protected readonly session = inject(SessionStore);

  // ── Company context ──────────────────────────────────────────────────────────
  readonly companies = signal<Company[]>([]);
  readonly selectedCompanyId = signal('');
  readonly companyState = signal<'loading' | 'idle' | 'error'>('loading');

  // ── Filters ──────────────────────────────────────────────────────────────────
  readonly filterDocumentType = signal('');

  // ── List state ───────────────────────────────────────────────────────────────
  readonly rows = signal<ApprovalPolicyDto[]>([]);
  readonly meta = signal<PageMeta>({ page: 0, size: DEFAULT_SIZE, totalElements: 0, totalPages: 0, hasNext: false });
  readonly state = signal<'loading' | 'idle' | 'error' | 'forbidden'>('idle');
  readonly currentPage = signal(0);
  readonly isEmpty = computed(() => this.state() === 'idle' && this.rows().length === 0);

  // ── Create form ───────────────────────────────────────────────────────────────
  readonly showCreateForm = signal(false);
  readonly newDocumentType = signal('');
  readonly newName = signal('');
  readonly newBranchScope = signal<PolicyBranchScope>('COMPANY_WIDE');
  readonly newBranchUid = signal('');
  readonly newMinAmount = signal('0');
  readonly newMaxAmount = signal('');
  readonly newNotes = signal('');
  // Dynamic step list for the create form
  readonly newSteps = signal<Array<{ sequence: string; approverRoleCode: string }>>([
    { sequence: '1', approverRoleCode: '' },
  ]);
  readonly saving = signal(false);
  readonly formError = signal<string | null>(null);

  // ── Permissions ───────────────────────────────────────────────────────────────
  readonly canManage = computed(() => this.session.hasPermission('APPROVALS.POLICY.MANAGE'));

  private readonly immediateTrigger$ = new Subject<LoadTrigger>();

  constructor() {
    const filterTrigger$ = toObservable(this.selectedCompanyId).pipe(
      skip(1),
      debounceTime(50),
      distinctUntilChanged(),
      map((): LoadTrigger => ({ page: 0 })),
    );

    merge(filterTrigger$, this.immediateTrigger$)
      .pipe(
        switchMap(({ page }) => {
          const companyId = this.selectedCompanyId();
          if (!companyId) return [];
          this.state.set('loading');
          this.currentPage.set(page);
          const docType = this.filterDocumentType().trim() || undefined;
          return this.approvalsService.listPolicies(companyId, docType, page, DEFAULT_SIZE);
        }),
        takeUntilDestroyed(),
      )
      .subscribe({
        next: ({ rows, meta }: ApprovalPolicyPage) => {
          this.rows.set(rows);
          this.meta.set(meta);
          this.state.set('idle');
        },
        error: (err: unknown) =>
          this.state.set(err instanceof HttpErrorResponse && err.status === 403 ? 'forbidden' : 'error'),
      });

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
              this.load(0);
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
    if (id) this.load(0);
  }

  applyFilter(): void {
    if (this.selectedCompanyId()) this.load(0);
  }

  load(page: number): void {
    const companyId = this.selectedCompanyId();
    if (!companyId) return;
    this.immediateTrigger$.next({ page });
  }

  prevPage(): void { if (this.currentPage() > 0) this.load(this.currentPage() - 1); }
  nextPage(): void { if (this.meta().hasNext) this.load(this.currentPage() + 1); }

  // ── Create form ────────────────────────────────────────────────────────────────

  toggleCreateForm(): void {
    this.showCreateForm.update((v) => !v);
    this.formError.set(null);
    if (!this.showCreateForm()) this.resetCreateForm();
  }

  private resetCreateForm(): void {
    this.newDocumentType.set('');
    this.newName.set('');
    this.newBranchScope.set('COMPANY_WIDE');
    this.newBranchUid.set('');
    this.newMinAmount.set('0');
    this.newMaxAmount.set('');
    this.newNotes.set('');
    this.newSteps.set([{ sequence: '1', approverRoleCode: '' }]);
  }

  addStep(): void {
    const steps = this.newSteps();
    const nextSeq = String(steps.length + 1);
    this.newSteps.set([...steps, { sequence: nextSeq, approverRoleCode: '' }]);
  }

  removeStep(index: number): void {
    const steps = this.newSteps().filter((_, i) => i !== index)
      .map((s, i) => ({ ...s, sequence: String(i + 1) }));
    this.newSteps.set(steps);
  }

  updateStepRole(index: number, role: string): void {
    this.newSteps.update((steps) =>
      steps.map((s, i) => i === index ? { ...s, approverRoleCode: role } : s),
    );
  }

  create(): void {
    const companyId = this.selectedCompanyId();
    const docType = this.newDocumentType().trim();
    const name = this.newName().trim();
    const branchScope = this.newBranchScope();
    const minAmount = this.newMinAmount().trim();
    const steps = this.newSteps();

    if (!companyId) { this.formError.set('Select a company first.'); return; }
    if (!docType) { this.formError.set('Document type is required.'); return; }
    if (!name) { this.formError.set('Policy name is required.'); return; }
    if (!minAmount) { this.formError.set('Min amount is required.'); return; }
    if (steps.length === 0) { this.formError.set('At least one approval step is required.'); return; }
    for (const step of steps) {
      if (!step.approverRoleCode.trim()) {
        this.formError.set(`Step ${step.sequence}: approver role code is required.`);
        return;
      }
    }
    if (branchScope === 'BRANCH' && !this.newBranchUid().trim()) {
      this.formError.set('Branch UID is required when branch scope is BRANCH.');
      return;
    }

    const stepInputs: PolicyStepInputDto[] = steps.map((s) => ({
      sequence: s.sequence,
      approverRoleCode: s.approverRoleCode.trim(),
    }));

    this.saving.set(true);
    this.formError.set(null);

    const request: CreateApprovalPolicyRequest = {
      companyId,
      documentType: docType,
      name,
      branchScope,
      branchUid: branchScope === 'BRANCH' ? this.newBranchUid().trim() : null,
      minAmount,
      maxAmount: this.newMaxAmount().trim() || null,
      notes: this.newNotes().trim() || null,
      steps: stepInputs,
    };

    this.approvalsService.createPolicy(request).subscribe({
      next: (created) => {
        this.saving.set(false);
        this.resetCreateForm();
        this.showCreateForm.set(false);
        this.alerts.success('Policy created', created.name);
        this.load(this.currentPage());
      },
      error: (err: unknown) => {
        this.formError.set(this.messageFrom(err, 'Could not create policy.'));
        this.saving.set(false);
      },
    });
  }

  // ── Display helpers ──────────────────────────────────────────────────────────

  statusBadgeClass(status: MasterStatus): string {
    switch (status) {
      case 'ACTIVE': return 'text-bg-success';
      case 'INACTIVE': return 'text-bg-warning';
      case 'ARCHIVED': return 'text-bg-secondary';
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
