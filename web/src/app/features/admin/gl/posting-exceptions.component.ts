import { HttpErrorResponse } from '@angular/common/http';
import { Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { PageMeta } from '../../../core/api/api-response.model';
import { SessionStore } from '../../../core/auth/session.store';
import { PaginatorComponent } from '../../../shared/paginator/paginator.component';
import { formatMoney } from '../../../shared/money.util';
import { Company } from '../models/company.model';
import { CompanyService } from '../company/company.service';
import { OrganisationService } from '../organisation/organisation.service';
import { GlService } from './gl.service';
import { GlPostingExceptionDto, GlSalesTieOutDto } from './models/gl.model';

const DEFAULT_SIZE = 20;

/** Source types an automatic poster can fail on — the filter's choices. */
export const EXCEPTION_SOURCE_TYPES: ReadonlyArray<{ value: string; label: string }> = [
  { value: 'SALES', label: 'Sales' },
  { value: 'SALES_REVERSAL', label: 'Sales void' },
  { value: 'COGS', label: 'Cost of sales' },
  { value: 'STOCK_RECEIPT', label: 'Goods receipt' },
  { value: 'LANDED_COST', label: 'Landed cost' },
  { value: 'PURCHASE_RETURN', label: 'Purchase return' },
  { value: 'AR_RECEIPT', label: 'Customer receipt' },
  { value: 'AP_PAYMENT', label: 'Supplier payment' },
  { value: 'POS_VARIANCE', label: 'POS variance' },
  { value: 'PAYROLL', label: 'Payroll' },
  { value: 'FX_REVALUATION', label: 'FX revaluation' },
];

function isoDate(d: Date): string {
  const p = (n: number) => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}`;
}

/**
 * GL "Posting exceptions" (ACC-02): automatic postings that failed — a closed or missing period, an
 * unmapped or inactive account, a missing rate — and were swallowed so the sale or stock movement
 * could stand. Lists them (GL.VIEW) and re-posts one through the poster that failed (GL.POST),
 * exactly once. Also shows the sales-vs-GL revenue and VAT tie-out for the chosen dates.
 */
@Component({
  selector: 'app-posting-exceptions',
  imports: [FormsModule, RouterLink, PaginatorComponent],
  templateUrl: './posting-exceptions.component.html',
  styleUrl: './posting-exceptions.component.scss',
})
export class PostingExceptionsComponent {
  private readonly glService = inject(GlService);
  private readonly companyService = inject(CompanyService);
  private readonly organisationService = inject(OrganisationService);
  protected readonly session = inject(SessionStore);

  protected readonly sourceTypes = EXCEPTION_SOURCE_TYPES;
  protected readonly money = formatMoney;

  readonly companies = signal<Company[]>([]);
  readonly selectedCompanyId = signal('');
  readonly companyState = signal<'loading' | 'idle' | 'error'>('loading');

  // Filters — dates default to the current month (the tie-out needs a range).
  readonly sourceType = signal('');
  readonly from = signal(isoDate(new Date(new Date().getFullYear(), new Date().getMonth(), 1)));
  readonly to = signal(isoDate(new Date(new Date().getFullYear(), new Date().getMonth() + 1, 0)));
  readonly includeResolved = signal(false);

  readonly rows = signal<GlPostingExceptionDto[]>([]);
  readonly meta = signal<PageMeta>({ page: 0, size: DEFAULT_SIZE, totalElements: 0, totalPages: 0, hasNext: false });
  readonly state = signal<'loading' | 'idle' | 'error' | 'forbidden'>('idle');
  readonly currentPage = signal(0);

  readonly tieOut = signal<GlSalesTieOutDto | null>(null);
  readonly tieOutState = signal<'loading' | 'idle' | 'error'>('idle');

  // Re-post panel
  readonly repostTarget = signal<GlPostingExceptionDto | null>(null);
  readonly repostDate = signal('');
  readonly reposting = signal(false);
  readonly repostError = signal<string | null>(null);
  readonly notice = signal<string | null>(null);

  readonly canRepost = computed(() => this.session.hasPermission('GL.POST'));
  readonly isEmpty = computed(() => this.state() === 'idle' && this.rows().length === 0);
  readonly tieOutBalanced = computed(() => {
    const t = this.tieOut();
    return !!t && !this.nonZero(t.revenueDifference) && !this.nonZero(t.vatDifference);
  });

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
              this.refresh();
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
    this.repostTarget.set(null);
    if (id) this.refresh();
  }

  /** Apply the filters: reload the list from page 0 and the tie-out for the dates. */
  refresh(): void {
    this.load(0);
    this.loadTieOut();
  }

  load(page: number): void {
    const companyId = this.selectedCompanyId();
    if (!companyId) return;
    this.state.set('loading');
    this.currentPage.set(page);
    this.glService
      .listPostingExceptions(
        companyId,
        {
          sourceType: this.sourceType() || undefined,
          from: this.from() || undefined,
          to: this.to() || undefined,
          includeResolved: this.includeResolved(),
        },
        page,
        DEFAULT_SIZE,
      )
      .subscribe({
        next: ({ rows, meta }) => {
          this.rows.set(rows);
          this.meta.set(meta);
          this.state.set('idle');
        },
        error: (err) =>
          this.state.set(err instanceof HttpErrorResponse && err.status === 403 ? 'forbidden' : 'error'),
      });
  }

  loadTieOut(): void {
    const companyId = this.selectedCompanyId();
    if (!companyId) return;
    this.tieOutState.set('loading');
    this.glService.getSalesTieOut(companyId, this.from() || undefined, this.to() || undefined).subscribe({
      next: (t) => {
        this.tieOut.set(t);
        this.tieOutState.set('idle');
      },
      error: () => {
        this.tieOut.set(null);
        this.tieOutState.set('error');
      },
    });
  }

  goToPage(page: number): void {
    this.load(page);
  }

  /** Money arrives as a JSON number (or a string in older DTOs) — coerce, never string-compare. */
  nonZero(v: number | string | null | undefined): boolean {
    return Number(v ?? 0) !== 0;
  }

  sourceLabel(value: string | null): string {
    if (!value) return '—';
    return this.sourceTypes.find((s) => s.value === value)?.label ?? value;
  }

  /** The document as a person reads it: its number when the poster knew it, else a short ref. */
  documentLabel(row: GlPostingExceptionDto): string {
    if (row.documentNumber) return row.documentNumber;
    if (row.sourceRef) return row.sourceRef.length > 12 ? row.sourceRef.slice(0, 12) + '…' : row.sourceRef;
    return '—';
  }

  openRepost(row: GlPostingExceptionDto): void {
    this.repostTarget.set(row);
    this.repostDate.set('');
    this.repostError.set(null);
    this.notice.set(null);
  }

  cancelRepost(): void {
    this.repostTarget.set(null);
    this.repostError.set(null);
  }

  confirmRepost(): void {
    const target = this.repostTarget();
    const companyId = this.selectedCompanyId();
    if (!target || !companyId || this.reposting()) return;
    this.reposting.set(true);
    this.repostError.set(null);
    this.glService.repostPostingException(companyId, target.uid, this.repostDate() || null).subscribe({
      next: (res) => {
        this.reposting.set(false);
        this.repostTarget.set(null);
        this.notice.set(
          res.outcome === 'ALREADY_POSTED'
            ? `Already in the ledger (${res.batchNumber ?? 'existing journal'}) — the exception is closed, nothing was posted twice.`
            : `Posted as ${res.batchNumber ?? 'a new journal'} on ${res.postingDate ?? ''}.`,
        );
        this.refresh();
      },
      error: (err: unknown) => {
        this.reposting.set(false);
        this.repostError.set(this.errorText(err));
      },
    });
  }

  private errorText(err: unknown): string {
    if (err instanceof HttpErrorResponse) {
      if (err.status === 403) return "You don't have permission to re-post journals.";
      const first = err.error?.errors?.[0];
      if (typeof first === 'string' && first.trim()) return first;
    }
    return 'The posting could not be re-posted. Please try again.';
  }
}
