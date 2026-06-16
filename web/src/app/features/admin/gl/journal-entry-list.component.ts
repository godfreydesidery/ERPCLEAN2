import { HttpErrorResponse } from '@angular/common/http';
import { Component, computed, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { Subject, switchMap } from 'rxjs';
import { PageMeta } from '../../../core/api/api-response.model';
import { SessionStore } from '../../../core/auth/session.store';
import { Company } from '../models/company.model';
import { CompanyService } from '../company/company.service';
import { OrganisationService } from '../organisation/organisation.service';
import { JournalEntryDto } from './models/gl.model';
import { GlService } from './gl.service';
import { PaginatorComponent } from '../../../shared/paginator/paginator.component';

const DEFAULT_SIZE = 20;

/**
 * Paged list of journal entries scoped to the active company.
 * Gated GL.VIEW. SALES auto-posted entries visible read-only (sourceType SALES badge).
 * Navigate to detail via /admin/gl/journals/uid/:uid.
 */
@Component({
  selector: 'app-journal-entry-list',
  imports: [FormsModule, RouterLink, PaginatorComponent],
  templateUrl: './journal-entry-list.component.html',
  styleUrl: './journal-entry-list.component.scss',
})
export class JournalEntryListComponent {
  private readonly glService = inject(GlService);
  private readonly companyService = inject(CompanyService);
  private readonly organisationService = inject(OrganisationService);
  protected readonly session = inject(SessionStore);

  // ── Company context ────────────────────────────────────────────────────────
  readonly companies = signal<Company[]>([]);
  readonly selectedCompanyId = signal('');
  readonly companyState = signal<'loading' | 'idle' | 'error'>('loading');

  // ── List state ─────────────────────────────────────────────────────────────
  readonly rows = signal<JournalEntryDto[]>([]);
  readonly meta = signal<PageMeta>({ page: 0, size: DEFAULT_SIZE, totalElements: 0, totalPages: 0, hasNext: false });
  readonly state = signal<'loading' | 'idle' | 'error' | 'forbidden'>('idle');
  readonly currentPage = signal(0);

  readonly canPost = computed(() => this.session.hasPermission('GL.POST'));
  readonly isEmpty = computed(() => this.state() === 'idle' && this.rows().length === 0);

  private readonly loadTrigger$ = new Subject<number>();

  constructor() {
    this.loadTrigger$
      .pipe(
        switchMap((page) => {
          const companyId = this.selectedCompanyId();
          if (!companyId) return [];
          this.state.set('loading');
          this.currentPage.set(page);
          return this.glService.listJournals(companyId, page, DEFAULT_SIZE);
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
    if (!this.selectedCompanyId()) return;
    this.loadTrigger$.next(page);
  }

  goToPage(page: number): void { this.load(page); }

  prevPage(): void {
    const p = this.currentPage();
    if (p > 0) this.load(p - 1);
  }

  nextPage(): void {
    if (this.meta().hasNext) this.load(this.currentPage() + 1);
  }

  /** Compute the total debits for a journal entry (display-only, server is authoritative). */
  entryTotal(entry: JournalEntryDto): string {
    const total = (entry.lines ?? []).reduce((sum, l) => sum + parseFloat(l.debitAmount || '0'), 0);
    return total.toFixed(2);
  }

}
