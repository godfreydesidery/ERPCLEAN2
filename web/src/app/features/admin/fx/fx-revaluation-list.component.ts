import { HttpErrorResponse } from '@angular/common/http';
import { Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { AlertService } from '../../../core/feedback/alert.service';
import { SessionStore } from '../../../core/auth/session.store';
import { Company } from '../models/company.model';
import { CompanyService } from '../company/company.service';
import { OrganisationService } from '../organisation/organisation.service';
import { GlService } from '../gl/gl.service';
import { FiscalPeriodDto } from '../gl/models/gl.model';
import { FxService } from './fx.service';
import {
  FxRevaluationPreviewDto,
  FxRevaluationRunDto,
  FxRevaluationRunStatus,
  PostFxRevaluationRequest,
} from './models/fx.model';
import { PageMeta } from '../../../core/api/api-response.model';
import { PaginatorComponent } from '../../../shared/paginator/paginator.component';

/**
 * FX revaluation run list + preview/post/reverse screen (ADR-0036 T5).
 *
 * Screens combined on a single route because the exposure-view and the
 * run-management view are the same data. Permission gates split the actions:
 *  - FX.EXPOSURE.VIEW  : see the list, read run details.
 *  - FX.REVALUE        : run preview, post, reverse.
 *
 * Route: /admin/fx/revaluation-runs
 */
@Component({
  selector: 'app-fx-revaluation-list',
  imports: [FormsModule, PaginatorComponent],
  templateUrl: './fx-revaluation-list.component.html',
  styleUrl: './fx-revaluation-list.component.scss',
})
export class FxRevaluationListComponent {
  private readonly fxService = inject(FxService);
  private readonly glService = inject(GlService);
  private readonly companyService = inject(CompanyService);
  private readonly organisationService = inject(OrganisationService);
  private readonly alerts = inject(AlertService);
  protected readonly session = inject(SessionStore);

  // ── Company context ────────────────────────────────────────────────────────
  readonly companies = signal<Company[]>([]);
  readonly selectedCompanyId = signal('');
  readonly companyState = signal<'loading' | 'idle' | 'error'>('loading');

  // ── Fiscal periods (for period picker) ────────────────────────────────────
  readonly fiscalPeriods = signal<FiscalPeriodDto[]>([]);

  // ── Runs list ──────────────────────────────────────────────────────────────
  readonly rows = signal<FxRevaluationRunDto[]>([]);
  readonly meta = signal<PageMeta>({ page: 0, size: 50, totalElements: 0, totalPages: 0, hasNext: false });
  readonly state = signal<'loading' | 'idle' | 'error' | 'forbidden'>('idle');
  readonly currentPage = signal(0);
  readonly isEmpty = computed(() => this.state() === 'idle' && this.rows().length === 0);

  // ── Run detail (inline expand) ─────────────────────────────────────────────
  readonly expandedUid = signal<string | null>(null);

  // ── Preview panel ──────────────────────────────────────────────────────────
  readonly showPreviewPanel = signal(false);
  readonly previewFiscalPeriodUid = signal('');
  readonly previewSpotRateDate = signal('');
  readonly previewState = signal<'idle' | 'loading' | 'done' | 'error'>('idle');
  readonly previewResult = signal<FxRevaluationPreviewDto | null>(null);
  readonly previewError = signal<string | null>(null);

  // ── Post action (from preview) ─────────────────────────────────────────────
  readonly postingDate = signal('');
  readonly posting = signal(false);
  readonly postError = signal<string | null>(null);

  // ── Reverse action ─────────────────────────────────────────────────────────
  readonly reversingUid = signal<string | null>(null);
  readonly reversalDate = signal('');
  readonly reverseError = signal<string | null>(null);
  readonly showReverseForm = signal<string | null>(null); // uid of run being reversed

  // ── Permissions ───────────────────────────────────────────────────────────
  readonly canRevalue = computed(() => this.session.hasPermission('FX.REVALUE'));
  readonly canExposureView = computed(() => this.session.hasPermission('FX.EXPOSURE.VIEW'));

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
              this.loadFiscalPeriods(list[0].id);
              this.load();
            }
          },
          error: () => this.companyState.set('error'),
        });
      },
      error: () => this.companyState.set('error'),
    });
  }

  private loadFiscalPeriods(companyId: string): void {
    this.glService.listPeriods(companyId).subscribe({
      next: (list) => this.fiscalPeriods.set(list),
      error: () => { /* non-fatal */ },
    });
  }

  onCompanyChange(id: string): void {
    this.selectedCompanyId.set(id);
    this.currentPage.set(0);
    this.previewResult.set(null);
    this.showPreviewPanel.set(false);
    if (id) {
      this.loadFiscalPeriods(id);
      this.load();
    }
  }

  load(page = this.currentPage()): void {
    const companyId = this.selectedCompanyId();
    if (!companyId) return;
    this.state.set('loading');
    this.fxService.listRuns(companyId, page).subscribe({
      next: ({ rows, meta }) => {
        this.rows.set(rows);
        this.meta.set(meta);
        this.currentPage.set(page);
        this.state.set('idle');
      },
      error: (err) =>
        this.state.set(err instanceof HttpErrorResponse && err.status === 403 ? 'forbidden' : 'error'),
    });
  }

  goToPage(page: number): void { this.load(page); }

  prevPage(): void {
    const p = this.currentPage();
    if (p > 0) this.load(p - 1);
  }

  nextPage(): void {
    if (this.meta().hasNext) this.load(this.currentPage() + 1);
  }

  // ── Run detail expand ──────────────────────────────────────────────────────

  toggleDetail(uid: string): void {
    this.expandedUid.set(this.expandedUid() === uid ? null : uid);
  }

  expandedRun = computed(() => {
    const uid = this.expandedUid();
    return uid ? (this.rows().find((r) => r.uid === uid) ?? null) : null;
  });

  // ── Preview ────────────────────────────────────────────────────────────────

  openPreviewPanel(): void {
    this.showPreviewPanel.set(true);
    this.previewResult.set(null);
    this.previewError.set(null);
    this.previewState.set('idle');
    this.previewFiscalPeriodUid.set('');
    this.previewSpotRateDate.set('');
    this.postingDate.set('');
    this.postError.set(null);
  }

  closePreviewPanel(): void {
    this.showPreviewPanel.set(false);
    this.previewResult.set(null);
  }

  runPreview(): void {
    const companyId = this.selectedCompanyId();
    const periodUid = String(this.previewFiscalPeriodUid() ?? '').trim();
    if (!companyId) { this.previewError.set('Select a company first.'); return; }
    if (!periodUid) { this.previewError.set('Select a fiscal period.'); return; }

    this.previewState.set('loading');
    this.previewError.set(null);
    this.previewResult.set(null);

    const spotDate = this.previewSpotRateDate() || null;
    this.fxService.previewRevaluation(companyId, periodUid, spotDate).subscribe({
      next: (dto) => {
        this.previewResult.set(dto);
        this.previewState.set('done');
      },
      error: (err) => {
        this.previewError.set(this.messageFrom(err, 'Preview failed.'));
        this.previewState.set('error');
      },
    });
  }

  // ── Post ───────────────────────────────────────────────────────────────────

  postRun(): void {
    const companyId = this.selectedCompanyId();
    const periodUid = String(this.previewFiscalPeriodUid() ?? '').trim();
    const pDate = String(this.postingDate() ?? '').trim();

    if (!pDate) { this.postError.set('Posting date is required.'); return; }

    const request: PostFxRevaluationRequest = {
      companyId,
      fiscalPeriodUid: periodUid,
      postingDate: pDate,
      spotRateDate: this.previewSpotRateDate() || null,
    };

    this.posting.set(true);
    this.postError.set(null);

    this.fxService.postRevaluation(request).subscribe({
      next: (run) => {
        this.posting.set(false);
        this.showPreviewPanel.set(false);
        this.previewResult.set(null);
        this.alerts.success('Revaluation run posted', run.runNumber);
        this.load(0);
      },
      error: (err) => {
        this.postError.set(this.messageFrom(err, 'Could not post revaluation run.'));
        this.posting.set(false);
      },
    });
  }

  // ── Reverse ────────────────────────────────────────────────────────────────

  openReverseForm(uid: string): void {
    this.showReverseForm.set(uid);
    this.reversalDate.set('');
    this.reverseError.set(null);
  }

  cancelReverse(): void {
    this.showReverseForm.set(null);
    this.reverseError.set(null);
  }

  confirmReverse(run: FxRevaluationRunDto): void {
    const rDate = String(this.reversalDate() ?? '').trim();
    if (!rDate) { this.reverseError.set('Reversal date is required.'); return; }

    this.reversingUid.set(run.uid);
    this.reverseError.set(null);

    this.fxService.reverseRevaluation(run.uid, rDate).subscribe({
      next: (updated) => {
        this.reversingUid.set(null);
        this.showReverseForm.set(null);
        this.rows.update((list) => list.map((r) => (r.uid === updated.uid ? updated : r)));
        this.alerts.success('Run reversed', updated.runNumber);
      },
      error: (err) => {
        this.reverseError.set(this.messageFrom(err, 'Could not reverse run.'));
        this.reversingUid.set(null);
      },
    });
  }

  // ── Display helpers ────────────────────────────────────────────────────────

  statusBadgeClass(status: FxRevaluationRunStatus): string {
    switch (status) {
      case 'PREVIEWED': return 'text-bg-warning';
      case 'POSTED':    return 'text-bg-success';
      case 'REVERSED':  return 'text-bg-secondary';
      default:          return 'text-bg-secondary';
    }
  }

  fmtAmt(v: string | null | undefined): string {
    const n = +(v ?? 0);
    return Number.isFinite(n) ? n.toFixed(2) : '—';
  }

  deltaClass(v: string | null | undefined): string {
    const n = +(v ?? 0);
    if (n > 0) return 'text-success fw-semibold';
    if (n < 0) return 'text-danger fw-semibold';
    return 'text-muted';
  }

  netAdjClass(v: string | null | undefined): string {
    const n = +(v ?? 0);
    if (n > 0) return 'text-success fw-bold';
    if (n < 0) return 'text-danger fw-bold';
    return '';
  }

  periodLabel(uid: string): string {
    const p = this.fiscalPeriods().find((fp) => fp.uid === uid);
    return p ? `Period ${p.periodNo} (${p.startDate} – ${p.endDate})` : uid;
  }

  private messageFrom(err: unknown, fallback: string): string {
    if (err instanceof HttpErrorResponse) {
      const errors = (err.error as { errors?: string[] })?.errors;
      if (errors?.length) return errors[0];
    }
    return fallback;
  }
}
