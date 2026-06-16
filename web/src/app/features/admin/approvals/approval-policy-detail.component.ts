import { HttpErrorResponse } from '@angular/common/http';
import { DecimalPipe } from '@angular/common';
import { Component, computed, inject, input, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { AlertService } from '../../../core/feedback/alert.service';
import { SessionStore } from '../../../core/auth/session.store';
import { BranchService } from '../branch/branch.service';
import { CompanyService } from '../company/company.service';
import { OrganisationService } from '../organisation/organisation.service';
import {
  ApprovalPolicyDto,
  PolicyBranchScope,
  PolicyStepInputDto,
  UpdateApprovalPolicyRequest,
} from './models/approvals.model';
import { ApprovalsService } from './approvals.service';
import { UidOption, UidPickerComponent } from '../../../shared/uid-picker/uid-picker.component';

type LoadState = 'loading' | 'idle' | 'error';

/**
 * Approval policy detail + edit + lifecycle actions.
 * Route: /admin/approvals/policies/uid/:uid
 * Actions: deactivate (APPROVALS.POLICY.MANAGE + status ACTIVE)
 */
@Component({
  selector: 'app-approval-policy-detail',
  imports: [FormsModule, RouterLink, DecimalPipe, UidPickerComponent],
  templateUrl: './approval-policy-detail.component.html',
  styleUrl: './approval-policy-detail.component.scss',
})
export class ApprovalPolicyDetailComponent {
  private readonly approvalsService = inject(ApprovalsService);
  private readonly branchService = inject(BranchService);
  private readonly companyService = inject(CompanyService);
  private readonly organisationService = inject(OrganisationService);
  private readonly alerts = inject(AlertService);
  protected readonly session = inject(SessionStore);

  // ── Picker options ────────────────────────────────────────────────────────
  readonly branchOptions = signal<UidOption[]>([]);

  /** Route input bound via withComponentInputBinding. */
  readonly uid = input.required<string>();

  // ── Entity header ─────────────────────────────────────────────────────────
  readonly policy = signal<ApprovalPolicyDto | null>(null);
  readonly policyState = signal<LoadState>('loading');

  // ── Edit form fields ──────────────────────────────────────────────────────
  readonly fDocumentType = signal('');
  readonly fName = signal('');
  readonly fBranchScope = signal<PolicyBranchScope>('COMPANY_WIDE');
  readonly fBranchUid = signal('');
  readonly fMinAmount = signal('0');
  readonly fMaxAmount = signal('');
  readonly fNotes = signal('');
  readonly fActive = signal(true);
  readonly fSteps = signal<Array<{ sequence: string; approverRoleCode: string }>>([]);

  readonly saving = signal(false);
  readonly saveError = signal<string | null>(null);

  // ── Deactivate action ─────────────────────────────────────────────────────
  readonly deactivating = signal(false);
  readonly deactivateError = signal<string | null>(null);

  // ── Permissions ───────────────────────────────────────────────────────────
  readonly canManage = computed(() => this.session.hasPermission('APPROVALS.POLICY.MANAGE'));

  // ── Derived status ────────────────────────────────────────────────────────
  readonly isActive = computed(() => this.policy()?.status === 'ACTIVE');
  readonly isDeactivatable = computed(() => this.isActive() && this.canManage());

  constructor() {
    queueMicrotask(() => this.init());
  }

  private init(): void {
    this.loadPolicy();
  }

  private loadPolicy(): void {
    this.policyState.set('loading');
    this.approvalsService.getPolicyByUid(this.uid()).subscribe({
      next: (p) => {
        this.policy.set(p);
        this.policyState.set('idle');
        this.patchForm(p);
        this.loadBranchOptions(p.companyId);
      },
      error: () => this.policyState.set('error'),
    });
  }

  private loadBranchOptions(companyId: string): void {
    this.organisationService.current().subscribe({
      next: (org) => {
        this.companyService.list(org.uid).subscribe({
          next: (companies) => {
            const company = companies.find((c) => c.id === companyId);
            if (!company) return;
            this.branchService.list(company.uid).subscribe({
              next: (branches) => {
                this.branchOptions.set(
                  branches.filter((b) => b.status === 'ACTIVE').map((b) => ({ uid: b.uid, label: b.name, hint: b.code })),
                );
              },
              error: () => {},
            });
          },
          error: () => {},
        });
      },
      error: () => {},
    });
  }

  private patchForm(p: ApprovalPolicyDto): void {
    this.fDocumentType.set(p.documentType ?? '');
    this.fName.set(p.name ?? '');
    this.fBranchScope.set(p.branchScope);
    this.fBranchUid.set(''); // branchUid for update — user must re-enter if changing branch
    this.fMinAmount.set(p.minAmount ?? '0');
    this.fMaxAmount.set(p.maxAmount ?? '');
    this.fNotes.set(p.notes ?? '');
    this.fActive.set(p.active);
    this.fSteps.set(
      (p.steps ?? []).map((s) => ({ sequence: s.sequence, approverRoleCode: s.approverRoleCode })),
    );
  }

  // ── Steps management ──────────────────────────────────────────────────────

  addStep(): void {
    const steps = this.fSteps();
    const nextSeq = String(steps.length + 1);
    this.fSteps.set([...steps, { sequence: nextSeq, approverRoleCode: '' }]);
  }

  removeStep(index: number): void {
    const steps = this.fSteps().filter((_, i) => i !== index)
      .map((s, i) => ({ ...s, sequence: String(i + 1) }));
    this.fSteps.set(steps);
  }

  updateStepRole(index: number, role: string): void {
    this.fSteps.update((steps) =>
      steps.map((s, i) => i === index ? { ...s, approverRoleCode: role } : s),
    );
  }

  // ── Save ──────────────────────────────────────────────────────────────────

  save(): void {
    const docType = this.fDocumentType().trim();
    const name = this.fName().trim();
    const branchScope = this.fBranchScope();
    const minAmount = this.fMinAmount().trim();
    const steps = this.fSteps();

    if (!docType) { this.saveError.set('Document type is required.'); return; }
    if (!name) { this.saveError.set('Policy name is required.'); return; }
    if (!minAmount) { this.saveError.set('Min amount is required.'); return; }
    if (steps.length === 0) { this.saveError.set('At least one approval step is required.'); return; }
    for (const step of steps) {
      if (!step.approverRoleCode.trim()) {
        this.saveError.set(`Step ${step.sequence}: approver role code is required.`);
        return;
      }
    }
    if (branchScope === 'BRANCH' && !this.fBranchUid().trim()) {
      this.saveError.set('Branch UID is required when branch scope is BRANCH.');
      return;
    }

    const stepInputs: PolicyStepInputDto[] = steps.map((s) => ({
      sequence: s.sequence,
      approverRoleCode: s.approverRoleCode.trim(),
    }));

    this.saving.set(true);
    this.saveError.set(null);

    const request: UpdateApprovalPolicyRequest = {
      documentType: docType,
      name,
      branchScope,
      branchUid: branchScope === 'BRANCH' ? this.fBranchUid().trim() : null,
      minAmount,
      maxAmount: this.fMaxAmount().trim() || null,
      notes: this.fNotes().trim() || null,
      active: this.fActive(),
      steps: stepInputs,
    };

    this.approvalsService.updatePolicy(this.uid(), request).subscribe({
      next: (updated) => {
        this.policy.set(updated);
        this.patchForm(updated);
        this.saving.set(false);
        this.alerts.success('Policy saved', updated.name);
      },
      error: (err) => {
        this.saveError.set(this.messageFrom(err, 'Could not save the policy.'));
        this.saving.set(false);
      },
    });
  }

  // ── Deactivate ────────────────────────────────────────────────────────────

  deactivate(): void {
    if (this.deactivating()) return;
    this.deactivating.set(true);
    this.deactivateError.set(null);
    this.approvalsService.deactivatePolicy(this.uid()).subscribe({
      next: (updated) => {
        this.policy.set(updated);
        this.patchForm(updated);
        this.deactivating.set(false);
        this.alerts.success('Policy deactivated', updated.name);
      },
      error: (err) => {
        this.deactivateError.set(this.messageFrom(err, 'Could not deactivate the policy.'));
        this.deactivating.set(false);
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
