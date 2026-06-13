import { HttpErrorResponse } from '@angular/common/http';
import { Component, computed, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { FormsModule } from '@angular/forms';
import { SessionStore } from '../../../core/auth/session.store';
import { Company } from '../models/company.model';
import { CompanyService } from '../company/company.service';
import { OrganisationService } from '../organisation/organisation.service';
import { CashTransferDto } from './models/cashbank.model';
import { CashbankService } from './cashbank.service';
import { PaginatorComponent } from '../../../shared/paginator/paginator.component';
import { PageMeta } from '../../../core/api/api-response.model';
import { Subject, switchMap } from 'rxjs';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';

const DEFAULT_SIZE = 20;

interface LoadTrigger { companyId: string; page: number }

/**
 * Paged list of recorded cash transfers. Company-scoped.
 * Each row links to a transfer view by uid. Gated CASH.VIEW.
 *
 * Note: CashTransferController returns a plain List (no pagination envelope).
 * The service's listTransfers wraps with SKIP_UNWRAP; the backend however doesn't
 * paginate — we do client-side slicing for UX consistency.
 */
@Component({
  selector: 'app-cash-transfers-list',
  imports: [FormsModule, RouterLink, PaginatorComponent],
  templateUrl: './cash-transfers-list.component.html',
  styleUrl: './cash-transfers-list.component.scss',
})
export class CashTransfersListComponent {
  private readonly cashbankService = inject(CashbankService);
  private readonly companyService = inject(CompanyService);
  private readonly organisationService = inject(OrganisationService);
  protected readonly session = inject(SessionStore);

  // ── Company context ────────────────────────────────────────────────────────
  readonly companies = signal<Company[]>([]);
  readonly selectedCompanyId = signal('');
  readonly companyState = signal<'loading' | 'idle' | 'error'>('loading');

  // ── List state ─────────────────────────────────────────────────────────────
  readonly allRows = signal<CashTransferDto[]>([]);
  readonly rows = signal<CashTransferDto[]>([]);
  readonly meta = signal<PageMeta>({ page: 0, size: DEFAULT_SIZE, totalElements: 0, totalPages: 0, hasNext: false });
  readonly state = signal<'loading' | 'idle' | 'error' | 'forbidden'>('idle');
  readonly currentPage = signal(0);

  // ── Permissions ────────────────────────────────────────────────────────────
  readonly canView = computed(() => this.session.hasPermission('CASH.VIEW'));
  readonly isEmpty = computed(() => this.state() === 'idle' && this.allRows().length === 0);

  private readonly loadTrigger$ = new Subject<LoadTrigger>();

  constructor() {
    this.loadTrigger$
      .pipe(
        switchMap(({ companyId, page }) => {
          if (!companyId) return [];
          this.state.set('loading');
          this.currentPage.set(page);
          return this.cashbankService.listTransfers(companyId, page, DEFAULT_SIZE);
        }),
        takeUntilDestroyed(),
      )
      .subscribe({
        next: ({ rows, meta }) => {
          this.allRows.set(rows);
          this.rows.set(rows);
          this.meta.set(meta);
          this.state.set('idle');
        },
        error: (err) =>
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

  load(page: number): void {
    const companyId = this.selectedCompanyId();
    if (!companyId) return;
    this.loadTrigger$.next({ companyId, page });
  }

  goToPage(page: number): void { this.load(page); }

  // ── Display helpers ────────────────────────────────────────────────────────

  fmtMoney(v: number | string | null | undefined): string {
    const n = +(v ?? 0);
    return Number.isFinite(n) ? n.toFixed(2) : '0.00';
  }
}
