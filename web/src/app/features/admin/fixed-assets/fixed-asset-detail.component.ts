import { DecimalPipe } from '@angular/common';
import { HttpErrorResponse } from '@angular/common/http';
import { Component, computed, inject, input, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { AlertService } from '../../../core/feedback/alert.service';
import { SessionStore } from '../../../core/auth/session.store';
import {
  AssetDisposalDto,
  AssetRevaluationDto,
  DepreciationScheduleLineDto,
  DisposeAssetRequest,
  FixedAssetDto,
  FixedAssetStatus,
  MasterStatus,
  PlaceInServiceRequest,
  RevalueAssetRequest,
  RevaluationDirection,
  TransferAssetRequest,
  UpdateAssetRequest,
  WriteOffAssetRequest,
} from './models/fixed-assets.model';
import { FixedAssetsService } from './fixed-assets.service';

type LoadState = 'loading' | 'idle' | 'error';

/**
 * Fixed Asset detail screen — view, edit (DRAFT), and lifecycle actions.
 * Route: /admin/fixed-assets/uid/:uid
 *
 * Lifecycle:
 *  DRAFT -> place-in-service (IN_SERVICE)
 *  IN_SERVICE -> dispose (DISPOSED) | write-off (WRITTEN_OFF) | revalue
 *  DRAFT/IN_SERVICE -> transfer (no status change)
 */
@Component({
  selector: 'app-fixed-asset-detail',
  imports: [FormsModule, RouterLink, DecimalPipe],
  templateUrl: './fixed-asset-detail.component.html',
  styleUrl: './fixed-asset-detail.component.scss',
})
export class FixedAssetDetailComponent {
  private readonly faService = inject(FixedAssetsService);
  private readonly alerts = inject(AlertService);
  protected readonly session = inject(SessionStore);

  readonly uid = input.required<string>();

  // ── Entity ───────────────────────────────────────────────────────────────────
  readonly asset = signal<FixedAssetDto | null>(null);
  readonly state = signal<LoadState>('loading');

  // ── Edit form (DRAFT only) ────────────────────────────────────────────────────
  readonly fName = signal('');
  readonly fLocation = signal('');
  readonly fAssetTag = signal('');
  readonly fCostCentreId = signal('');
  readonly saving = signal(false);
  readonly saveError = signal<string | null>(null);

  // ── Place-in-service form ─────────────────────────────────────────────────────
  readonly showPlaceInServiceForm = signal(false);
  readonly fPostingDate = signal('');
  readonly placingInService = signal(false);
  readonly placeInServiceError = signal<string | null>(null);

  // ── Transfer form ─────────────────────────────────────────────────────────────
  readonly showTransferForm = signal(false);
  readonly fTransferBranchId = signal('');
  readonly fTransferLocation = signal('');
  readonly fTransferCostCentreId = signal('');
  readonly transferring = signal(false);
  readonly transferError = signal<string | null>(null);

  // ── Dispose form ──────────────────────────────────────────────────────────────
  readonly showDisposeForm = signal(false);
  readonly fDisposalDate = signal('');
  readonly fProceedsAmount = signal('');
  readonly fDisposalReason = signal('');
  readonly disposing = signal(false);
  readonly disposeError = signal<string | null>(null);
  readonly lastDisposal = signal<AssetDisposalDto | null>(null);

  // ── Write-off form ────────────────────────────────────────────────────────────
  readonly showWriteOffForm = signal(false);
  readonly fWriteOffDate = signal('');
  readonly fWriteOffReason = signal('');
  readonly writingOff = signal(false);
  readonly writeOffError = signal<string | null>(null);

  // ── Revalue form ──────────────────────────────────────────────────────────────
  readonly showRevalueForm = signal(false);
  readonly fRevalDirection = signal<RevaluationDirection>('UP');
  readonly fRevalDelta = signal('');
  readonly fRevalDate = signal('');
  readonly fRevalReason = signal('');
  readonly revaluing = signal(false);
  readonly revalueError = signal<string | null>(null);

  // ── Child: schedule ───────────────────────────────────────────────────────────
  readonly schedule = signal<DepreciationScheduleLineDto[]>([]);
  readonly scheduleState = signal<LoadState>('idle');

  // ── Child: revaluations ───────────────────────────────────────────────────────
  readonly revaluations = signal<AssetRevaluationDto[]>([]);
  readonly revalState = signal<LoadState>('idle');

  // ── Permissions ───────────────────────────────────────────────────────────────
  readonly canManage = computed(() => this.session.hasPermission('FA.REGISTER.MANAGE'));
  readonly canDispose = computed(() => this.session.hasPermission('FA.DISPOSE'));

  // ── Status helpers ────────────────────────────────────────────────────────────
  readonly isDraft = computed(() => this.asset()?.status === 'DRAFT');
  readonly isInService = computed(() => this.asset()?.status === 'IN_SERVICE');
  readonly isTerminal = computed(() => {
    const s = this.asset()?.status;
    return s === 'DISPOSED' || s === 'WRITTEN_OFF';
  });

  readonly directionOptions: RevaluationDirection[] = ['UP', 'DOWN'];

  constructor() {
    queueMicrotask(() => this.init());
  }

  private init(): void {
    this.loadAsset();
  }

  private loadAsset(): void {
    this.state.set('loading');
    this.faService.getByUid(this.uid()).subscribe({
      next: (asset) => {
        this.asset.set(asset);
        this.state.set('idle');
        this.patchForm(asset);
        if (asset.status === 'IN_SERVICE') {
          this.loadSchedule();
          this.loadRevaluations();
        }
      },
      error: () => this.state.set('error'),
    });
  }

  private patchForm(asset: FixedAssetDto): void {
    this.fName.set(asset.name ?? '');
    this.fLocation.set(asset.location ?? '');
    this.fAssetTag.set(asset.assetTag ?? '');
    this.fCostCentreId.set(asset.costCentreId ?? '');
  }

  private loadSchedule(): void {
    this.scheduleState.set('loading');
    this.faService.listSchedule(this.uid()).subscribe({
      next: (lines) => { this.schedule.set(lines); this.scheduleState.set('idle'); },
      error: () => this.scheduleState.set('error'),
    });
  }

  private loadRevaluations(): void {
    this.revalState.set('loading');
    this.faService.listRevaluations(this.uid()).subscribe({
      next: (rows) => { this.revaluations.set(rows); this.revalState.set('idle'); },
      error: () => this.revalState.set('error'),
    });
  }

  // ── Edit (DRAFT only) ─────────────────────────────────────────────────────────

  save(): void {
    const name = this.fName().trim();
    if (!name) { this.saveError.set('Name is required.'); return; }
    this.saving.set(true);
    this.saveError.set(null);

    const request: UpdateAssetRequest = {
      name,
      location: this.fLocation().trim() || undefined,
      assetTag: this.fAssetTag().trim() || undefined,
      costCentreId: this.fCostCentreId().trim() || undefined,
    };

    this.faService.update(this.uid(), request).subscribe({
      next: (updated) => {
        this.asset.set(updated);
        this.saving.set(false);
        this.alerts.success('Asset saved', updated.name);
      },
      error: (err) => {
        this.saveError.set(this.messageFrom(err, 'Could not save asset.'));
        this.saving.set(false);
      },
    });
  }

  // ── Place in service ──────────────────────────────────────────────────────────

  togglePlaceInServiceForm(): void {
    this.showPlaceInServiceForm.update((v) => !v);
    this.placeInServiceError.set(null);
    if (!this.showPlaceInServiceForm()) this.fPostingDate.set('');
  }

  placeInService(): void {
    const postingDate = this.fPostingDate().trim();
    if (!postingDate) { this.placeInServiceError.set('Posting date is required.'); return; }
    this.placingInService.set(true);
    this.placeInServiceError.set(null);

    const request: PlaceInServiceRequest = { postingDate };

    this.faService.placeInService(this.uid(), request).subscribe({
      next: (updated) => {
        this.asset.set(updated);
        this.placingInService.set(false);
        this.showPlaceInServiceForm.set(false);
        this.fPostingDate.set('');
        this.alerts.success('Asset placed in service', updated.assetNumber);
        this.loadSchedule();
        this.loadRevaluations();
      },
      error: (err) => {
        this.placeInServiceError.set(this.messageFrom(err, 'Could not place asset in service.'));
        this.placingInService.set(false);
      },
    });
  }

  // ── Transfer ──────────────────────────────────────────────────────────────────

  toggleTransferForm(): void {
    this.showTransferForm.update((v) => !v);
    this.transferError.set(null);
    if (!this.showTransferForm()) {
      this.fTransferBranchId.set('');
      this.fTransferLocation.set('');
      this.fTransferCostCentreId.set('');
    }
  }

  transfer(): void {
    this.transferring.set(true);
    this.transferError.set(null);

    const request: TransferAssetRequest = {
      branchId: this.fTransferBranchId().trim() || undefined,
      location: this.fTransferLocation().trim() || undefined,
      costCentreId: this.fTransferCostCentreId().trim() || undefined,
    };

    this.faService.transfer(this.uid(), request).subscribe({
      next: (updated) => {
        this.asset.set(updated);
        this.patchForm(updated);
        this.transferring.set(false);
        this.showTransferForm.set(false);
        this.alerts.success('Asset transferred', updated.assetNumber);
      },
      error: (err) => {
        this.transferError.set(this.messageFrom(err, 'Could not transfer asset.'));
        this.transferring.set(false);
      },
    });
  }

  // ── Dispose ───────────────────────────────────────────────────────────────────

  toggleDisposeForm(): void {
    this.showDisposeForm.update((v) => !v);
    this.disposeError.set(null);
    if (!this.showDisposeForm()) {
      this.fDisposalDate.set('');
      this.fProceedsAmount.set('');
      this.fDisposalReason.set('');
    }
  }

  dispose(): void {
    const disposalDate = this.fDisposalDate().trim();
    const proceedsAmount = this.fProceedsAmount().trim();
    if (!disposalDate) { this.disposeError.set('Disposal date is required.'); return; }
    if (!proceedsAmount || isNaN(Number(proceedsAmount))) { this.disposeError.set('Proceeds amount is required (enter 0 for none).'); return; }

    this.disposing.set(true);
    this.disposeError.set(null);

    const request: DisposeAssetRequest = {
      disposalDate,
      proceedsAmount,
      reason: this.fDisposalReason().trim() || undefined,
    };

    this.faService.dispose(this.uid(), request).subscribe({
      next: (disposal) => {
        this.lastDisposal.set(disposal);
        this.disposing.set(false);
        this.showDisposeForm.set(false);
        this.alerts.success('Asset disposed', disposal.disposalType);
        // Reload asset to get DISPOSED status
        this.loadAsset();
      },
      error: (err) => {
        this.disposeError.set(this.messageFrom(err, 'Could not dispose asset.'));
        this.disposing.set(false);
      },
    });
  }

  // ── Write-off ─────────────────────────────────────────────────────────────────

  toggleWriteOffForm(): void {
    this.showWriteOffForm.update((v) => !v);
    this.writeOffError.set(null);
    if (!this.showWriteOffForm()) {
      this.fWriteOffDate.set('');
      this.fWriteOffReason.set('');
    }
  }

  writeOff(): void {
    const disposalDate = this.fWriteOffDate().trim();
    if (!disposalDate) { this.writeOffError.set('Write-off date is required.'); return; }

    this.writingOff.set(true);
    this.writeOffError.set(null);

    const request: WriteOffAssetRequest = {
      disposalDate,
      reason: this.fWriteOffReason().trim() || undefined,
    };

    this.faService.writeOff(this.uid(), request).subscribe({
      next: (disposal) => {
        this.lastDisposal.set(disposal);
        this.writingOff.set(false);
        this.showWriteOffForm.set(false);
        this.alerts.success('Asset written off', disposal.disposalType);
        this.loadAsset();
      },
      error: (err) => {
        this.writeOffError.set(this.messageFrom(err, 'Could not write off asset.'));
        this.writingOff.set(false);
      },
    });
  }

  // ── Revalue ───────────────────────────────────────────────────────────────────

  toggleRevalueForm(): void {
    this.showRevalueForm.update((v) => !v);
    this.revalueError.set(null);
    if (!this.showRevalueForm()) {
      this.fRevalDirection.set('UP');
      this.fRevalDelta.set('');
      this.fRevalDate.set('');
      this.fRevalReason.set('');
    }
  }

  revalue(): void {
    const delta = this.fRevalDelta().trim();
    const date = this.fRevalDate().trim();
    if (!delta || isNaN(Number(delta)) || Number(delta) <= 0) {
      this.revalueError.set('Delta amount must be a positive number.');
      return;
    }
    if (!date) { this.revalueError.set('Revaluation date is required.'); return; }

    this.revaluing.set(true);
    this.revalueError.set(null);

    const request: RevalueAssetRequest = {
      direction: this.fRevalDirection(),
      deltaAmount: delta,
      revaluationDate: date,
      reason: this.fRevalReason().trim() || undefined,
    };

    this.faService.revalue(this.uid(), request).subscribe({
      next: (reval) => {
        this.revaluing.set(false);
        this.showRevalueForm.set(false);
        this.alerts.success('Asset revalued', `${reval.direction} ${reval.deltaAmount}`);
        this.loadAsset();
        this.loadSchedule();
        this.loadRevaluations();
      },
      error: (err) => {
        this.revalueError.set(this.messageFrom(err, 'Could not revalue asset.'));
        this.revaluing.set(false);
      },
    });
  }

  // ── Display helpers ───────────────────────────────────────────────────────────

  statusBadgeClass(status: FixedAssetStatus): string {
    switch (status) {
      case 'IN_SERVICE': return 'text-bg-success';
      case 'DISPOSED': return 'text-bg-secondary';
      case 'WRITTEN_OFF': return 'text-bg-dark';
      default: return 'text-bg-warning'; // DRAFT
    }
  }

  categoryStatusBadgeClass(status: MasterStatus): string {
    return status === 'ACTIVE' ? 'text-bg-success' : 'text-bg-secondary';
  }

  private messageFrom(err: unknown, fallback: string): string {
    if (err instanceof HttpErrorResponse) {
      const errors = (err.error as { errors?: string[] })?.errors;
      if (errors?.length) return errors[0];
    }
    return fallback;
  }
}
