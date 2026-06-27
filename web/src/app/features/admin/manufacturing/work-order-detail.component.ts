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
  AddOperationRequest,
  ApplyCostRequest,
  CloseWorkOrderRequest,
  CompleteWorkOrderRequest,
  IssueComponentsRequest,
  ReleaseWorkOrderRequest,
  UpdateWorkOrderRequest,
  WorkOrderComponentDto,
  WorkOrderDto,
  WorkOrderOperationDto,
  WorkOrderStatus,
} from './models/manufacturing.model';
import { ManufacturingService } from './manufacturing.service';
import { UidOption, UidPickerComponent } from '../../../shared/uid-picker/uid-picker.component';

type LoadState = 'loading' | 'idle' | 'error';

/**
 * Work Order detail + edit + lifecycle actions.
 * Route: /admin/work-orders/uid/:uid
 *
 * Lifecycle actions:
 *  - release   (PLANNED → RELEASED)        WORKORDER.RELEASE
 *  - issue     (RELEASED | IN_PROGRESS)    WORKORDER.MANAGE
 *  - apply-cost (any WIP state)            WORKORDER.MANAGE
 *  - complete  (IN_PROGRESS → COMPLETED)   WORKORDER.CLOSE
 *  - close     (COMPLETED → CLOSED)        WORKORDER.CLOSE
 *  - cancel    (PLANNED|RELEASED|IN_PROGRESS|COMPLETED) WORKORDER.MANAGE
 */
@Component({
  selector: 'app-work-order-detail',
  imports: [FormsModule, RouterLink, DecimalPipe, UidPickerComponent],
  templateUrl: './work-order-detail.component.html',
  styleUrl: './work-order-detail.component.scss',
})
export class WorkOrderDetailComponent {
  private readonly mfgService = inject(ManufacturingService);
  private readonly branchService = inject(BranchService);
  private readonly companyService = inject(CompanyService);
  private readonly organisationService = inject(OrganisationService);
  private readonly alerts = inject(AlertService);
  protected readonly session = inject(SessionStore);

  // ── Picker options ────────────────────────────────────────────────────────
  readonly branchOptions = signal<UidOption[]>([]);
  /** Operation options for the "Apply Cost" action (derived from the loaded WO). */
  readonly operationOptions = computed<UidOption[]>(() => {
    const ops = this.wo()?.operations ?? [];
    return ops.map((op) => ({ uid: op.uid, label: op.description, hint: `Seq ${op.seqNo}` }));
  });

  /** Route input bound via withComponentInputBinding. */
  readonly uid = input.required<string>();

  // ── Entity state ─────────────────────────────────────────────────────────
  readonly wo = signal<WorkOrderDto | null>(null);
  readonly woState = signal<LoadState>('loading');

  // ── Edit form ─────────────────────────────────────────────────────────────
  readonly fPlannedQty = signal('');
  readonly fBomUid = signal('');
  readonly fBranchUid = signal('');
  readonly fPlannedDate = signal('');
  readonly fNotes = signal('');
  readonly saving = signal(false);
  readonly saveError = signal<string | null>(null);

  // ── Release action ────────────────────────────────────────────────────────
  readonly releaseBomUid = signal('');
  readonly releasing = signal(false);
  readonly releaseError = signal<string | null>(null);

  // ── Issue components action ───────────────────────────────────────────────
  readonly issuePostingDate = signal('');
  readonly issuing = signal(false);
  readonly issueError = signal<string | null>(null);

  // ── Apply cost action ─────────────────────────────────────────────────────
  readonly costLabourAmount = signal('');
  readonly costOverheadAmount = signal('');
  readonly costOperationUid = signal('');
  readonly costPostingDate = signal('');
  readonly applyingCost = signal(false);
  readonly applyCostError = signal<string | null>(null);

  // ── Complete action ───────────────────────────────────────────────────────
  readonly completeGoodQty = signal('');
  readonly completeScrapQty = signal('');
  readonly completeAllowOverRun = signal(false);
  readonly completePostingDate = signal('');
  readonly completing = signal(false);
  readonly completeError = signal<string | null>(null);

  // ── Close action ─────────────────────────────────────────────────────────
  readonly closePostingDate = signal('');
  readonly closing = signal(false);
  readonly closeError = signal<string | null>(null);

  // ── Cancel action ─────────────────────────────────────────────────────────
  readonly cancelReason = signal('');
  readonly cancelling = signal(false);
  readonly cancelError = signal<string | null>(null);

  // ── Add operation child ───────────────────────────────────────────────────
  readonly opSeqNo = signal('');
  readonly opDescription = signal('');
  readonly opWorkCentre = signal('');
  readonly opLabourAmount = signal('');
  readonly opOverheadAmount = signal('');
  readonly addingOp = signal(false);
  readonly addOpError = signal<string | null>(null);

  // ── Remove operation busy state ───────────────────────────────────────────
  readonly removingOpUid = signal<string | null>(null);

  // ── Permissions ───────────────────────────────────────────────────────────
  readonly canManage = computed(() => this.session.hasPermission('WORKORDER.MANAGE'));
  readonly canRelease = computed(() => this.session.hasPermission('WORKORDER.RELEASE'));
  readonly canClose = computed(() => this.session.hasPermission('WORKORDER.CLOSE'));

  // ── Status helpers ────────────────────────────────────────────────────────
  readonly canEdit = computed(() => {
    const s = this.wo()?.status;
    return s === 'PLANNED' && this.canManage();
  });
  readonly canReleaseAction = computed(() => {
    return this.wo()?.status === 'PLANNED' && this.canRelease();
  });
  readonly canIssue = computed(() => {
    const s = this.wo()?.status;
    return (s === 'RELEASED' || s === 'IN_PROGRESS') && this.canManage();
  });
  readonly canApplyCost = computed(() => {
    const s = this.wo()?.status;
    return (s === 'RELEASED' || s === 'IN_PROGRESS') && this.canManage();
  });
  readonly canComplete = computed(() => {
    return this.wo()?.status === 'IN_PROGRESS' && this.canClose();
  });
  readonly canCloseAction = computed(() => {
    return this.wo()?.status === 'COMPLETED' && this.canClose();
  });
  readonly canCancel = computed(() => {
    const s = this.wo()?.status;
    return (
      (s === 'PLANNED' || s === 'RELEASED' || s === 'IN_PROGRESS' || s === 'COMPLETED') &&
      this.canManage()
    );
  });
  readonly canAddOp = computed(() => {
    return this.canManage();
  });

  // Expose global String constructor for use in templates.
  protected readonly String = String;

  constructor() {
    queueMicrotask(() => this.init());
  }

  private init(): void {
    this.load();
  }

  load(): void {
    this.woState.set('loading');
    this.mfgService.getByUid(this.uid()).subscribe({
      next: (w) => {
        this.wo.set(w);
        this.woState.set('idle');
        this.patchForm(w);
        this.loadBranchOptions(w);
      },
      error: () => this.woState.set('error'),
    });
  }

  private loadBranchOptions(w: WorkOrderDto): void {
    // WorkOrderDto.companyId is the numeric id. BranchService.list needs companyUid.
    // Resolve: load all companies for the current org and match by id.
    this.organisationService.current().subscribe({
      next: (org) => {
        this.companyService.list(org.uid).subscribe({
          next: (companies) => {
            const company = companies.find((c) => c.id === w.companyId);
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

  private patchForm(w: WorkOrderDto): void {
    this.fPlannedQty.set(w.plannedQty ?? '');
    this.fBomUid.set(w.bomUid ?? '');
    this.fBranchUid.set(w.branchId ?? '');
    this.fPlannedDate.set(w.plannedDate ?? '');
    this.fNotes.set(w.notes ?? '');
  }

  // ── Save (update) ─────────────────────────────────────────────────────────

  save(): void {
    const plannedQty = this.fPlannedQty().trim();
    if (!plannedQty || isNaN(Number(plannedQty)) || Number(plannedQty) <= 0) {
      this.saveError.set('Planned quantity must be a positive number.');
      return;
    }
    this.saving.set(true);
    this.saveError.set(null);

    const request: UpdateWorkOrderRequest = {
      plannedQty,
      bomUid: this.fBomUid().trim() || undefined,
      branchUid: this.fBranchUid().trim() || undefined,
      plannedDate: this.fPlannedDate().trim() || undefined,
      notes: this.fNotes().trim() || undefined,
    };

    this.mfgService.update(this.uid(), request).subscribe({
      next: (updated) => {
        this.wo.set(updated);
        this.saving.set(false);
        this.alerts.success('Work order saved', updated.woNumber);
        this.patchForm(updated);
      },
      error: (err: unknown) => {
        this.saveError.set(this.messageFrom(err, 'Could not save work order.'));
        this.saving.set(false);
      },
    });
  }

  // ── Release ───────────────────────────────────────────────────────────────

  release(): void {
    this.releasing.set(true);
    this.releaseError.set(null);
    const bomUid = this.releaseBomUid().trim();
    const request: ReleaseWorkOrderRequest | undefined = bomUid ? { bomUid } : undefined;
    this.mfgService.release(this.uid(), request).subscribe({
      next: (updated) => {
        this.wo.set(updated);
        this.releasing.set(false);
        this.alerts.success('Work order released', updated.woNumber);
      },
      error: (err: unknown) => {
        this.releaseError.set(this.messageFrom(err, 'Could not release work order.'));
        this.releasing.set(false);
      },
    });
  }

  // ── Issue components ───────────────────────────────────────────────────────

  issueComponents(): void {
    const postingDate = this.issuePostingDate().trim();
    if (!postingDate) {
      this.issueError.set('Posting date is required.');
      return;
    }
    this.issuing.set(true);
    this.issueError.set(null);
    const request: IssueComponentsRequest = { full: true, postingDate };
    this.mfgService.issueComponents(this.uid(), request).subscribe({
      next: (updated) => {
        this.wo.set(updated);
        this.issuing.set(false);
        this.alerts.success('Components issued', updated.woNumber);
      },
      error: (err: unknown) => {
        this.issueError.set(this.messageFrom(err, 'Could not issue components.'));
        this.issuing.set(false);
      },
    });
  }

  // ── Apply cost ─────────────────────────────────────────────────────────────

  applyCost(): void {
    const postingDate = this.costPostingDate().trim();
    if (!postingDate) {
      this.applyCostError.set('Posting date is required.');
      return;
    }
    const labour = this.costLabourAmount().trim();
    const overhead = this.costOverheadAmount().trim();
    if ((!labour || Number(labour) <= 0) && (!overhead || Number(overhead) <= 0)) {
      this.applyCostError.set('At least one of labour or overhead amount must be positive.');
      return;
    }
    this.applyingCost.set(true);
    this.applyCostError.set(null);
    const request: ApplyCostRequest = {
      labourAmount: labour || undefined,
      overheadAmount: overhead || undefined,
      operationUid: this.costOperationUid().trim() || undefined,
      postingDate,
    };
    this.mfgService.applyCost(this.uid(), request).subscribe({
      next: (updated) => {
        this.wo.set(updated);
        this.applyingCost.set(false);
        this.alerts.success('Cost applied', updated.woNumber);
      },
      error: (err: unknown) => {
        this.applyCostError.set(this.messageFrom(err, 'Could not apply cost.'));
        this.applyingCost.set(false);
      },
    });
  }

  // ── Complete ──────────────────────────────────────────────────────────────

  complete(): void {
    const goodQty = this.completeGoodQty().trim();
    const postingDate = this.completePostingDate().trim();
    if (!goodQty || isNaN(Number(goodQty)) || Number(goodQty) <= 0) {
      this.completeError.set('Good quantity must be a positive number.');
      return;
    }
    if (!postingDate) {
      this.completeError.set('Posting date is required.');
      return;
    }
    this.completing.set(true);
    this.completeError.set(null);
    const request: CompleteWorkOrderRequest = {
      goodQty,
      scrapQty: this.completeScrapQty().trim() || undefined,
      allowOverRun: this.completeAllowOverRun(),
      postingDate,
    };
    this.mfgService.complete(this.uid(), request).subscribe({
      next: (updated) => {
        this.wo.set(updated);
        this.completing.set(false);
        this.alerts.success('Work order completed', updated.woNumber);
      },
      error: (err: unknown) => {
        this.completeError.set(this.messageFrom(err, 'Could not complete work order.'));
        this.completing.set(false);
      },
    });
  }

  // ── Close ─────────────────────────────────────────────────────────────────

  closeOrder(): void {
    const postingDate = this.closePostingDate().trim();
    if (!postingDate) {
      this.closeError.set('Posting date is required.');
      return;
    }
    this.closing.set(true);
    this.closeError.set(null);
    const request: CloseWorkOrderRequest = { postingDate };
    this.mfgService.close(this.uid(), request).subscribe({
      next: (updated) => {
        this.wo.set(updated);
        this.closing.set(false);
        this.alerts.success('Work order closed', updated.woNumber);
      },
      error: (err: unknown) => {
        this.closeError.set(this.messageFrom(err, 'Could not close work order.'));
        this.closing.set(false);
      },
    });
  }

  // ── Cancel ────────────────────────────────────────────────────────────────

  cancelOrder(): void {
    this.cancelling.set(true);
    this.cancelError.set(null);
    const reason = this.cancelReason().trim() || undefined;
    this.mfgService.cancel(this.uid(), reason).subscribe({
      next: (updated) => {
        this.wo.set(updated);
        this.cancelling.set(false);
        this.alerts.success('Work order cancelled', updated.woNumber);
      },
      error: (err: unknown) => {
        this.cancelError.set(this.messageFrom(err, 'Could not cancel work order.'));
        this.cancelling.set(false);
      },
    });
  }

  // ── Add operation ─────────────────────────────────────────────────────────

  addOperation(): void {
    const seqNo = this.opSeqNo().trim();
    const description = this.opDescription().trim();
    if (!seqNo || isNaN(Number(seqNo)) || Number(seqNo) < 1) {
      this.addOpError.set('Sequence number must be a positive integer.');
      return;
    }
    if (!description) {
      this.addOpError.set('Description is required.');
      return;
    }
    this.addingOp.set(true);
    this.addOpError.set(null);
    const request: AddOperationRequest = {
      seqNo,
      description,
      workCentre: this.opWorkCentre().trim() || undefined,
      labourAmount: this.opLabourAmount().trim() || undefined,
      overheadAmount: this.opOverheadAmount().trim() || undefined,
    };
    this.mfgService.addOperation(this.uid(), request).subscribe({
      next: (op) => {
        this.wo.update((w) => (w ? { ...w, operations: [...w.operations, op] } : w));
        this.addingOp.set(false);
        this.opSeqNo.set('');
        this.opDescription.set('');
        this.opWorkCentre.set('');
        this.opLabourAmount.set('');
        this.opOverheadAmount.set('');
        this.addOpError.set(null);
        this.alerts.success('Operation added');
      },
      error: (err: unknown) => {
        this.addOpError.set(this.messageFrom(err, 'Could not add operation.'));
        this.addingOp.set(false);
      },
    });
  }

  // ── Remove operation ──────────────────────────────────────────────────────

  removeOperation(op: WorkOrderOperationDto): void {
    if (this.removingOpUid() !== null) return;
    this.removingOpUid.set(op.uid);
    this.mfgService.removeOperation(this.uid(), op.uid).subscribe({
      next: () => {
        this.wo.update((w) =>
          w ? { ...w, operations: w.operations.filter((o) => o.uid !== op.uid) } : w,
        );
        this.removingOpUid.set(null);
        this.alerts.success('Operation removed');
      },
      error: (err: unknown) => {
        this.removingOpUid.set(null);
        this.saveError.set(this.messageFrom(err, 'Could not remove operation.'));
      },
    });
  }

  // ── Display helpers ───────────────────────────────────────────────────────

  trackByUid(_: number, item: WorkOrderComponentDto | WorkOrderOperationDto): string {
    return item.uid;
  }

  private messageFrom(err: unknown, fallback: string): string {
    if (err instanceof HttpErrorResponse) {
      const errors = (err.error as { errors?: string[] })?.errors;
      if (errors?.length) return errors[0];
    }
    return fallback;
  }
}
