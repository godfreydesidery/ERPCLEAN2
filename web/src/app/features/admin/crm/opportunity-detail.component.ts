import { HttpErrorResponse } from '@angular/common/http';
import { DecimalPipe, SlicePipe } from '@angular/common';
import { Component, computed, inject, input, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { debounceTime, distinctUntilChanged, Subject, switchMap } from 'rxjs';
import { AlertService } from '../../../core/feedback/alert.service';
import { SessionStore } from '../../../core/auth/session.store';
import { ProductModel, UnitOfMeasureDto } from '../models/product.model';
import { ProductService } from '../products/product.service';
import { CrmService } from './crm.service';
import {
  AddOpportunityLineRequest,
  AdvanceStageRequest,
  ConvertOpportunityRequest,
  ConvertResultDto,
  LoseOpportunityRequest,
  OpportunityDto,
  OpportunityLineDto,
  PipelineStageDto,
  UpdateOpportunityRequest,
  WinOpportunityRequest,
} from './models/crm.model';
import { ActivityPanelComponent } from './activity/activity-panel.component';

type LoadState = 'loading' | 'idle' | 'error';

/**
 * Opportunity detail + lifecycle screen. Route: /admin/crm/opportunities/uid/:uid.
 * Actions (OPEN): advance-stage, win, lose, add/remove lines, update fields.
 * Actions (OPEN|WON): convert to Quotation or SalesOrder.
 */
@Component({
  selector: 'app-opportunity-detail',
  imports: [FormsModule, RouterLink, DecimalPipe, SlicePipe, ActivityPanelComponent],
  templateUrl: './opportunity-detail.component.html',
  styleUrl: './opportunity-detail.component.scss',
})
export class OpportunityDetailComponent {
  private readonly crmService = inject(CrmService);
  private readonly productService = inject(ProductService);
  private readonly alerts = inject(AlertService);
  private readonly router = inject(Router);
  protected readonly session = inject(SessionStore);

  readonly uid = input.required<string>();

  // ── Opportunity header ─────────────────────────────────────────────────────
  readonly opp = signal<OpportunityDto | null>(null);
  readonly oppState = signal<LoadState>('loading');

  // ── Pipeline stages (for advance-stage picker) ─────────────────────────────
  readonly stages = signal<PipelineStageDto[]>([]);

  // ── Edit form fields (OPEN only) ───────────────────────────────────────────
  readonly fTitle = signal('');
  readonly fEstimatedValue = signal('');
  readonly fWinProbability = signal('');
  readonly fExpectedCloseDate = signal('');
  readonly fNotes = signal('');
  readonly saving = signal(false);
  readonly saveError = signal<string | null>(null);

  // ── Add-line form ──────────────────────────────────────────────────────────
  readonly productSearchQ = signal('');
  readonly productResults = signal<ProductModel[]>([]);
  readonly selectedProduct = signal<{ uid: string; label: string } | null>(null);
  readonly lineUnits = signal<UnitOfMeasureDto[]>([]);
  readonly lineUnitsState = signal<'idle' | 'loading' | 'error'>('idle');
  readonly newLineUnitUid = signal('');
  readonly newLineQty = signal('');
  readonly newLineUnitPrice = signal('');
  readonly newLineDiscountPct = signal('');
  readonly addingLine = signal(false);
  readonly lineFormError = signal<string | null>(null);
  readonly rowBusyLineUid = signal<string | null>(null);

  // ── Advance-stage action ───────────────────────────────────────────────────
  readonly showAdvanceForm = signal(false);
  readonly advanceStageUid = signal('');
  readonly advanceProbOverride = signal('');
  readonly advancing = signal(false);
  readonly advanceError = signal<string | null>(null);

  // ── Win action ─────────────────────────────────────────────────────────────
  readonly showWinForm = signal(false);
  readonly winWonAt = signal('');
  readonly winning = signal(false);
  readonly winError = signal<string | null>(null);

  // ── Lose action ────────────────────────────────────────────────────────────
  readonly showLoseForm = signal(false);
  readonly loseLossReason = signal('');
  readonly losing = signal(false);
  readonly loseError = signal<string | null>(null);

  // ── Convert action ─────────────────────────────────────────────────────────
  readonly showConvertForm = signal(false);
  readonly convertTarget = signal<'QUOTATION' | 'SALES_ORDER'>('QUOTATION');
  readonly convertValidUntil = signal('');
  readonly converting = signal(false);
  readonly convertError = signal<string | null>(null);
  readonly convertResult = signal<ConvertResultDto | null>(null);

  // ── Permissions ────────────────────────────────────────────────────────────
  readonly canManage = computed(() => this.session.hasPermission('CRM.OPPORTUNITY.MANAGE'));
  readonly canConvert = computed(() => this.session.hasPermission('CRM.OPPORTUNITY.CONVERT'));

  // ── Status helpers ─────────────────────────────────────────────────────────
  readonly isOpen = computed(() => this.opp()?.opportunityStatus === 'OPEN');
  readonly isWon = computed(() => this.opp()?.opportunityStatus === 'WON');
  readonly canEdit = computed(() => this.isOpen() && this.canManage());
  readonly canAddLine = computed(() => this.isOpen() && this.canManage());
  readonly canAdvance = computed(() => this.isOpen() && this.canManage());
  readonly canWin = computed(() => this.isOpen() && this.canManage());
  readonly canLose = computed(() => this.isOpen() && this.canManage());
  readonly canConvertNow = computed(() => {
    const s = this.opp()?.opportunityStatus;
    return (s === 'OPEN' || s === 'WON') && this.canConvert();
  });

  private readonly productSearch$ = new Subject<string>();

  constructor() {
    this.productSearch$
      .pipe(
        debounceTime(300),
        distinctUntilChanged(),
        switchMap((q) => {
          const companyId = this.opp()?.companyId;
          if (!companyId || !q.trim()) { this.productResults.set([]); return []; }
          return this.productService.list(companyId, q.trim(), 0, 10);
        }),
        takeUntilDestroyed(),
      )
      .subscribe({
        next: ({ rows }: { rows: ProductModel[] }) => this.productResults.set(rows),
        error: () => this.productResults.set([]),
      });

    queueMicrotask(() => this.init());
  }

  private init(): void {
    this.loadOpp();
  }

  private loadOpp(): void {
    this.oppState.set('loading');
    this.crmService.getOpportunityByUid(this.uid()).subscribe({
      next: (opp) => {
        this.opp.set(opp);
        this.oppState.set('idle');
        this.patchForm(opp);
        this.loadStages(opp.companyId);
      },
      error: () => this.oppState.set('error'),
    });
  }

  private loadStages(companyId: string): void {
    this.crmService.listPipelineStages(companyId).subscribe({
      next: (rows) => {
        const active = rows.filter((s) => s.active);
        this.stages.set(active);
        if (active.length > 0 && !this.advanceStageUid()) {
          this.advanceStageUid.set(active[0].uid);
        }
      },
      error: () => undefined,
    });
  }

  private loadUnitsForProduct(productUid: string): void {
    this.lineUnits.set([]);
    this.newLineUnitUid.set('');
    this.lineUnitsState.set('loading');
    this.productService.listProductUnits(productUid).subscribe({
      next: (units) => {
        this.lineUnits.set(units);
        this.lineUnitsState.set('idle');
        if (units.length > 0) this.newLineUnitUid.set(units[0].uid);
      },
      error: () => this.lineUnitsState.set('error'),
    });
  }

  private patchForm(opp: OpportunityDto): void {
    this.fTitle.set(opp.title ?? '');
    this.fEstimatedValue.set(opp.estimatedValueAmount ?? '');
    this.fWinProbability.set(opp.winProbability ?? '');
    this.fExpectedCloseDate.set(opp.expectedCloseDate ?? '');
    this.fNotes.set('');
  }

  private reloadOpp(): void {
    this.crmService.getOpportunityByUid(this.uid()).subscribe({
      next: (opp) => this.opp.set(opp),
      error: () => undefined,
    });
  }

  // ── Edit ───────────────────────────────────────────────────────────────────

  save(): void {
    const title = this.fTitle().trim();
    if (!title) { this.saveError.set('Title is required.'); return; }
    this.saving.set(true);
    this.saveError.set(null);
    const request: UpdateOpportunityRequest = {
      title,
      estimatedValueAmount: this.fEstimatedValue().trim() || undefined,
      winProbability: this.fWinProbability().trim() || undefined,
      expectedCloseDate: this.fExpectedCloseDate() || undefined,
      notes: this.fNotes().trim() || undefined,
    };
    this.crmService.updateOpportunity(this.uid(), request).subscribe({
      next: (updated) => {
        this.opp.set(updated);
        this.saving.set(false);
        this.alerts.success('Opportunity saved', updated.title);
      },
      error: (err: unknown) => {
        this.saveError.set(this.messageFrom(err, 'Could not save opportunity.'));
        this.saving.set(false);
      },
    });
  }

  // ── Product search for add-line ────────────────────────────────────────────

  onProductSearchChange(q: string): void {
    this.productSearchQ.set(q);
    this.selectedProduct.set(null);
    this.lineUnits.set([]);
    this.newLineUnitUid.set('');
    this.productSearch$.next(q);
  }

  selectProduct(p: ProductModel): void {
    this.selectedProduct.set({ uid: p.uid, label: `${p.code} — ${p.name}` });
    this.productResults.set([]);
    this.productSearchQ.set(`${p.code} — ${p.name}`);
    this.loadUnitsForProduct(p.uid);
  }

  addLine(): void {
    const product = this.selectedProduct();
    const unitUid = this.newLineUnitUid();
    const qty = this.newLineQty().trim();
    if (!product) { this.lineFormError.set('Product is required.'); return; }
    if (!unitUid) { this.lineFormError.set('Unit is required.'); return; }
    if (!qty || isNaN(+qty) || +qty <= 0) { this.lineFormError.set('Quantity must be > 0.'); return; }

    this.addingLine.set(true);
    this.lineFormError.set(null);
    const request: AddOpportunityLineRequest = {
      productUid: product.uid,
      unitUid,
      estimatedQty: qty,
      estimatedUnitPriceAmount: this.newLineUnitPrice().trim() || undefined,
      lineDiscountPercent: this.newLineDiscountPct().trim() || undefined,
    };
    this.crmService.addOpportunityLine(this.uid(), request).subscribe({
      next: () => {
        this.addingLine.set(false);
        this.resetLineForm();
        this.alerts.success('Line added');
        this.reloadOpp();
      },
      error: (err: unknown) => {
        this.lineFormError.set(this.messageFrom(err, 'Could not add line.'));
        this.addingLine.set(false);
      },
    });
  }

  removeLine(line: OpportunityLineDto): void {
    if (this.rowBusyLineUid() !== null) return;
    this.rowBusyLineUid.set(line.uid);
    this.crmService.removeOpportunityLine(this.uid(), line.uid).subscribe({
      next: () => {
        this.rowBusyLineUid.set(null);
        this.alerts.success('Line removed');
        this.reloadOpp();
      },
      error: () => this.rowBusyLineUid.set(null),
    });
  }

  private resetLineForm(): void {
    this.productSearchQ.set('');
    this.selectedProduct.set(null);
    this.productResults.set([]);
    this.newLineUnitUid.set('');
    this.newLineQty.set('');
    this.newLineUnitPrice.set('');
    this.newLineDiscountPct.set('');
  }

  // ── Advance stage ──────────────────────────────────────────────────────────

  toggleAdvanceForm(): void {
    this.showAdvanceForm.update((v) => !v);
    this.advanceError.set(null);
  }

  advanceStage(): void {
    const stageUid = this.advanceStageUid();
    if (!stageUid) { this.advanceError.set('Select a target stage.'); return; }
    this.advancing.set(true);
    this.advanceError.set(null);
    const request: AdvanceStageRequest = {
      pipelineStageUid: stageUid,
      winProbabilityOverride: this.advanceProbOverride().trim() || undefined,
    };
    this.crmService.advanceStage(this.uid(), request).subscribe({
      next: (updated) => {
        this.opp.set(updated);
        this.advancing.set(false);
        this.showAdvanceForm.set(false);
        this.alerts.success('Stage advanced');
      },
      error: (err: unknown) => {
        this.advanceError.set(this.messageFrom(err, 'Could not advance stage.'));
        this.advancing.set(false);
      },
    });
  }

  // ── Win ────────────────────────────────────────────────────────────────────

  toggleWinForm(): void {
    this.showWinForm.update((v) => !v);
    this.winError.set(null);
    if (!this.showWinForm()) this.winWonAt.set('');
  }

  win(): void {
    if (this.winning()) return;
    this.winning.set(true);
    this.winError.set(null);
    const wonAt = this.winWonAt() || new Date().toISOString();
    const request: WinOpportunityRequest = { wonAt };
    this.crmService.winOpportunity(this.uid(), request).subscribe({
      next: (updated) => {
        this.opp.set(updated);
        this.winning.set(false);
        this.showWinForm.set(false);
        this.winWonAt.set('');
        this.alerts.success('Opportunity marked WON', updated.opportunityNumber);
      },
      error: (err: unknown) => {
        this.winError.set(this.messageFrom(err, 'Could not mark as won.'));
        this.winning.set(false);
      },
    });
  }

  // ── Lose ───────────────────────────────────────────────────────────────────

  toggleLoseForm(): void {
    this.showLoseForm.update((v) => !v);
    this.loseError.set(null);
    if (!this.showLoseForm()) this.loseLossReason.set('');
  }

  lose(): void {
    if (this.losing()) return;
    const reason = this.loseLossReason().trim();
    if (!reason) { this.loseError.set('Loss reason is required.'); return; }
    this.losing.set(true);
    this.loseError.set(null);
    const request: LoseOpportunityRequest = { lossReason: reason };
    this.crmService.loseOpportunity(this.uid(), request).subscribe({
      next: (updated) => {
        this.opp.set(updated);
        this.losing.set(false);
        this.showLoseForm.set(false);
        this.loseLossReason.set('');
        this.alerts.success('Opportunity marked LOST');
      },
      error: (err: unknown) => {
        this.loseError.set(this.messageFrom(err, 'Could not mark as lost.'));
        this.losing.set(false);
      },
    });
  }

  // ── Convert ────────────────────────────────────────────────────────────────

  toggleConvertForm(): void {
    this.showConvertForm.update((v) => !v);
    this.convertError.set(null);
    this.convertResult.set(null);
  }

  convert(): void {
    if (this.converting()) return;
    const target = this.convertTarget();
    const opp = this.opp();
    if (!opp) return;
    if ((opp.lines ?? []).length === 0) {
      this.convertError.set('Add at least one line before converting.');
      return;
    }
    if (target === 'SALES_ORDER' && opp.opportunityStatus !== 'WON') {
      this.convertError.set('Sales Order conversion requires status WON.');
      return;
    }
    this.converting.set(true);
    this.convertError.set(null);
    const request: ConvertOpportunityRequest = {
      target,
      validUntil: target === 'QUOTATION' && this.convertValidUntil() ? this.convertValidUntil() : undefined,
    };
    this.crmService.convertOpportunity(this.uid(), request).subscribe({
      next: (result) => {
        this.convertResult.set(result);
        this.converting.set(false);
        this.reloadOpp();
        this.alerts.success(
          `Converted to ${result.convertedDocumentKind}`,
          result.convertedDocumentNumber ?? result.convertedDocumentUid,
        );
      },
      error: (err: unknown) => {
        this.convertError.set(this.messageFrom(err, 'Could not convert opportunity.'));
        this.converting.set(false);
      },
    });
  }

  navigateToDocument(result: ConvertResultDto): void {
    if (result.convertedDocumentKind === 'QUOTATION') {
      this.router.navigate(['/admin/quotations/uid', result.convertedDocumentUid]);
    } else {
      this.router.navigate(['/admin/sales-orders/uid', result.convertedDocumentUid]);
    }
  }

  // ── Display helpers ────────────────────────────────────────────────────────

  stageName(stageId: string): string {
    return this.stages().find((s) => s.id === stageId)?.name ?? stageId;
  }

  private messageFrom(err: unknown, fallback: string): string {
    if (err instanceof HttpErrorResponse) {
      const errors = (err.error as { errors?: string[] })?.errors;
      if (errors?.length) return errors[0];
    }
    return fallback;
  }
}
