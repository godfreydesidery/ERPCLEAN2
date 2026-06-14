import { DecimalPipe } from '@angular/common';
import { HttpErrorResponse } from '@angular/common/http';
import { Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { AlertService } from '../../../core/feedback/alert.service';
import { SessionStore } from '../../../core/auth/session.store';
import { Company } from '../models/company.model';
import { CompanyService } from '../company/company.service';
import { OrganisationService } from '../organisation/organisation.service';
import { FiscalPeriodDto } from '../gl/models/gl.model';
import { GlService } from '../gl/gl.service';
import { FixedAssetsService } from './fixed-assets.service';
import { DepreciationRunDto, DepreciationRunPreviewDto, RunDepreciationRequest } from './models/fixed-assets.model';

/**
 * Depreciation preview + post screen. Route: /admin/depreciation-runs/post
 * Step 1: preview (POST /preview with query params — read-only, nothing persisted).
 * Step 2: post (POST / — idempotent per company+period; returns existing run if already posted).
 * Perm: FA.DEPRECIATE for both.
 */
@Component({
  selector: 'app-depreciation-post',
  imports: [FormsModule, RouterLink, DecimalPipe],
  templateUrl: './depreciation-post.component.html',
  styleUrl: './depreciation-post.component.scss',
})
export class DepreciationPostComponent {
  private readonly faService = inject(FixedAssetsService);
  private readonly glService = inject(GlService);
  private readonly companyService = inject(CompanyService);
  private readonly organisationService = inject(OrganisationService);
  private readonly alerts = inject(AlertService);
  private readonly router = inject(Router);
  protected readonly session = inject(SessionStore);

  private readonly periodById = signal<Map<string, FiscalPeriodDto>>(new Map());

  // ── Company context ──────────────────────────────────────────────────────────
  readonly companies = signal<Company[]>([]);
  readonly selectedCompanyId = signal('');
  readonly companyState = signal<'loading' | 'idle' | 'error'>('loading');

  // ── Form ──────────────────────────────────────────────────────────────────────
  readonly fFiscalPeriodUid = signal('');
  readonly fPostingDate = signal('');

  // ── Preview ───────────────────────────────────────────────────────────────────
  readonly preview = signal<DepreciationRunPreviewDto | null>(null);
  readonly previewing = signal(false);
  readonly previewError = signal<string | null>(null);

  // ── Post ──────────────────────────────────────────────────────────────────────
  readonly posting = signal(false);
  readonly postError = signal<string | null>(null);
  readonly postedRun = signal<DepreciationRunDto | null>(null);

  readonly canDepreciate = computed(() => this.session.hasPermission('FA.DEPRECIATE'));

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
              this.loadPeriods(list[0].id);
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
    this.preview.set(null);
    this.postedRun.set(null);
    if (id) this.loadPeriods(id);
  }

  private loadPeriods(companyId: string): void {
    this.glService.listPeriods(companyId).subscribe({
      next: (periods) => {
        this.periodById.set(new Map(periods.map((p) => [p.id, p])));
      },
      error: () => undefined,
    });
  }

  periodLabel(fiscalPeriodId: string): string {
    const p = this.periodById().get(fiscalPeriodId);
    return p ? `Period ${p.periodNo} (${p.startDate})` : fiscalPeriodId;
  }

  runPreview(): void {
    const companyId = this.selectedCompanyId();
    const fiscalPeriodUid = this.fFiscalPeriodUid().trim();
    if (!companyId) { this.previewError.set('Select a company.'); return; }
    if (!fiscalPeriodUid) { this.previewError.set('Fiscal period UID is required.'); return; }

    this.previewing.set(true);
    this.previewError.set(null);
    this.preview.set(null);

    this.faService.previewRun(companyId, fiscalPeriodUid).subscribe({
      next: (p) => {
        this.preview.set(p);
        this.previewing.set(false);
      },
      error: (err: unknown) => {
        this.previewError.set(this.messageFrom(err, 'Could not generate preview.'));
        this.previewing.set(false);
      },
    });
  }

  postRun(): void {
    const companyId = this.selectedCompanyId();
    const fiscalPeriodUid = this.fFiscalPeriodUid().trim();
    const postingDate = this.fPostingDate().trim();

    if (!companyId) { this.postError.set('Select a company.'); return; }
    if (!fiscalPeriodUid) { this.postError.set('Fiscal period UID is required.'); return; }
    if (!postingDate) { this.postError.set('Posting date is required.'); return; }

    this.posting.set(true);
    this.postError.set(null);

    const request: RunDepreciationRequest = { companyId, fiscalPeriodUid, postingDate };

    this.faService.postRun(request).subscribe({
      next: (run) => {
        this.postedRun.set(run);
        this.posting.set(false);
        this.alerts.success('Depreciation run posted', run.runNumber);
      },
      error: (err: unknown) => {
        this.postError.set(this.messageFrom(err, 'Could not post depreciation run.'));
        this.posting.set(false);
      },
    });
  }

  goToRun(): void {
    const run = this.postedRun();
    if (run) this.router.navigate(['/admin/depreciation-runs/uid', run.uid]);
  }

  private messageFrom(err: unknown, fallback: string): string {
    if (err instanceof HttpErrorResponse) {
      const errors = (err.error as { errors?: string[] })?.errors;
      if (errors?.length) return errors[0];
    }
    return fallback;
  }
}
