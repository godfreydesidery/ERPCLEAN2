import { HttpErrorResponse } from '@angular/common/http';
import { DatePipe } from '@angular/common';
import { Component, computed, inject, signal } from '@angular/core';
import { takeUntilDestroyed, toObservable } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { debounceTime, distinctUntilChanged, map, merge, skip, Subject, switchMap } from 'rxjs';
import { PageMeta } from '../../../core/api/api-response.model';
import { SessionStore } from '../../../core/auth/session.store';
import { Company } from '../models/company.model';
import { GoodsReceiptDto, GoodsReceiptStatus } from '../models/purchases.model';
import { CompanyService } from '../company/company.service';
import { OrganisationService } from '../organisation/organisation.service';
import { PurchasesService } from './purchases.service';

const DEFAULT_SIZE = 20;

interface LoadTrigger { q: string; page: number }

/**
 * Goods Receipt list. Company scope, debounced search, paged.
 * Links to GR create (query-param poUid) and individual receipt view (future).
 * Void action surfaced per-row for RECEIVED receipts (PURCHASE.VOID).
 */
@Component({
  selector: 'app-goods-receipt-list',
  imports: [FormsModule, RouterLink, DatePipe],
  templateUrl: './goods-receipt-list.component.html',
  styleUrl: './goods-receipt-list.component.scss',
})
export class GoodsReceiptListComponent {
  private readonly purchasesService = inject(PurchasesService);
  private readonly companyService = inject(CompanyService);
  private readonly organisationService = inject(OrganisationService);
  protected readonly session = inject(SessionStore);

  // ── Company context ────────────────────────────────────────────────────────
  readonly companies = signal<Company[]>([]);
  readonly selectedCompanyId = signal('');
  readonly companyState = signal<'loading' | 'idle' | 'error'>('loading');

  // ── List state ─────────────────────────────────────────────────────────────
  readonly rows = signal<GoodsReceiptDto[]>([]);
  readonly meta = signal<PageMeta>({ page: 0, size: DEFAULT_SIZE, totalElements: 0, totalPages: 0, hasNext: false });
  readonly state = signal<'loading' | 'idle' | 'error' | 'forbidden'>('idle');
  readonly currentPage = signal(0);

  // ── Filters ────────────────────────────────────────────────────────────────
  readonly searchQ = signal('');

  private readonly immediateTrigger$ = new Subject<LoadTrigger>();

  readonly canReceive = computed(() => this.session.hasPermission('PURCHASE.RECEIVE'));
  readonly isEmpty = computed(() => this.state() === 'idle' && this.rows().length === 0);

  constructor() {
    const typingTrigger$ = toObservable(this.searchQ).pipe(
      skip(1),
      debounceTime(300),
      distinctUntilChanged(),
      map((q): LoadTrigger => ({ q, page: 0 })),
    );

    merge(typingTrigger$, this.immediateTrigger$)
      .pipe(
        switchMap(({ q, page }) => {
          const companyId = this.selectedCompanyId();
          if (!companyId) return [];
          this.state.set('loading');
          this.currentPage.set(page);
          return this.purchasesService.listReceipts(companyId, q || undefined, page, DEFAULT_SIZE);
        }),
        takeUntilDestroyed(),
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
    this.immediateTrigger$.next({ q: this.searchQ(), page });
  }

  prevPage(): void { if (this.currentPage() > 0) this.load(this.currentPage() - 1); }
  nextPage(): void { if (this.meta().hasNext) this.load(this.currentPage() + 1); }

  // ── Display helpers ────────────────────────────────────────────────────────

  statusBadgeClass(status: GoodsReceiptStatus): string {
    switch (status) {
      case 'RECEIVED': return 'text-bg-success';
      case 'VOID': return 'text-bg-secondary';
      default: return 'text-bg-warning'; // DRAFT
    }
  }
}
