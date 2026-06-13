import { HttpErrorResponse } from '@angular/common/http';
import { DecimalPipe } from '@angular/common';
import { Component, computed, inject, signal } from '@angular/core';
import { takeUntilDestroyed, toObservable } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { debounceTime, distinctUntilChanged, map, merge, skip, Subject, switchMap } from 'rxjs';
import { PageMeta } from '../../../core/api/api-response.model';
import { AlertService } from '../../../core/feedback/alert.service';
import { SessionStore } from '../../../core/auth/session.store';
import { Company } from '../models/company.model';
import { CompanyService } from '../company/company.service';
import { OrganisationService } from '../organisation/organisation.service';
import { UidPickerComponent, UidOption } from '../../../shared/uid-picker/uid-picker.component';
import { PaginatorComponent } from '../../../shared/paginator/paginator.component';
import { PosService } from './pos.service';
import type { PosSessionPage } from './pos.service';
import { OpenSessionRequest, PosSessionDto, PosSessionStatus, PosTillDto } from './models/pos.model';

const DEFAULT_SIZE = 20;

interface LoadTrigger { page: number }

/**
 * POS Session list screen. Company scope, paged.
 * Inline "Open session" form: till picker + opening float.
 * Row actions: view/x-read, close, reconcile — gated by status + perms.
 * Gated: POS.SESSION.VIEW / POS.SESSION.OPEN / POS.SESSION.CLOSE / POS.SESSION.RECONCILE.
 */
@Component({
  selector: 'app-pos-session-list',
  imports: [FormsModule, RouterLink, DecimalPipe, PaginatorComponent, UidPickerComponent],
  templateUrl: './pos-session-list.component.html',
  styleUrl: './pos-session-list.component.scss',
})
export class PosSessionListComponent {
  private readonly posService = inject(PosService);
  private readonly companyService = inject(CompanyService);
  private readonly organisationService = inject(OrganisationService);
  private readonly alerts = inject(AlertService);
  protected readonly session = inject(SessionStore);

  // ── Company context ────────────────────────────────────────────────────────
  readonly companies = signal<Company[]>([]);
  readonly selectedCompanyId = signal('');
  readonly companyState = signal<'loading' | 'idle' | 'error'>('loading');

  // ── List state ─────────────────────────────────────────────────────────────
  readonly rows = signal<PosSessionDto[]>([]);
  readonly meta = signal<PageMeta>({ page: 0, size: DEFAULT_SIZE, totalElements: 0, totalPages: 0, hasNext: false });
  readonly state = signal<'loading' | 'idle' | 'error' | 'forbidden'>('idle');
  readonly currentPage = signal(0);

  // ── Tills for picker ───────────────────────────────────────────────────────
  readonly tills = signal<PosTillDto[]>([]);
  readonly tillOptions = computed<UidOption[]>(() =>
    this.tills().filter((t) => t.status === 'ACTIVE').map((t) => ({ uid: t.uid, label: t.name })),
  );

  // ── Open-session form ──────────────────────────────────────────────────────
  readonly showOpenForm = signal(false);
  readonly newTillUid = signal('');
  readonly newOpeningFloat = signal('0.00');
  readonly opening = signal(false);
  readonly openError = signal<string | null>(null);

  // ── Row busy (close / reconcile) ───────────────────────────────────────────
  readonly rowBusyUid = signal<string | null>(null);

  private readonly immediateTrigger$ = new Subject<LoadTrigger>();

  readonly canOpen = computed(() => this.session.hasPermission('POS.SESSION.OPEN'));
  readonly canClose = computed(() => this.session.hasPermission('POS.SESSION.CLOSE'));
  readonly canReconcile = computed(() => this.session.hasPermission('POS.SESSION.RECONCILE'));
  readonly isEmpty = computed(() => this.state() === 'idle' && this.rows().length === 0);

  constructor() {
    const companyTrigger$ = toObservable(this.selectedCompanyId).pipe(
      skip(1),
      debounceTime(50),
      distinctUntilChanged(),
      map((): LoadTrigger => ({ page: 0 })),
    );

    merge(companyTrigger$, this.immediateTrigger$)
      .pipe(
        switchMap(({ page }) => {
          const companyId = this.selectedCompanyId();
          if (!companyId) return [];
          this.state.set('loading');
          this.currentPage.set(page);
          return this.posService.listSessions(companyId, page, DEFAULT_SIZE);
        }),
        takeUntilDestroyed(),
      )
      .subscribe({
        next: ({ rows, meta }: PosSessionPage) => {
          this.rows.set(rows);
          this.meta.set(meta);
          this.state.set('idle');
        },
        error: (err: unknown) =>
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
              this.loadTills(list[0].id);
              this.load(0);
            }
          },
          error: () => this.companyState.set('error'),
        });
      },
      error: () => this.companyState.set('error'),
    });
  }

  private loadTills(companyId: string): void {
    this.posService.listTills(companyId).subscribe({
      next: (list) => this.tills.set(list),
      error: () => this.tills.set([]),
    });
  }

  onCompanyChange(id: string): void {
    this.selectedCompanyId.set(id);
    if (id) {
      this.loadTills(id);
      this.load(0);
    }
  }

  load(page: number): void {
    if (!this.selectedCompanyId()) return;
    this.immediateTrigger$.next({ page });
  }

  goToPage(page: number): void { this.load(page); }

  // ── Open session form ──────────────────────────────────────────────────────

  toggleOpenForm(): void {
    this.showOpenForm.update((v) => !v);
    this.openError.set(null);
    if (!this.showOpenForm()) this.resetOpenForm();
  }

  private resetOpenForm(): void {
    this.newTillUid.set('');
    this.newOpeningFloat.set('0.00');
  }

  openSession(): void {
    const tillUid = this.newTillUid().trim();
    const float = this.newOpeningFloat().trim();

    if (!tillUid) { this.openError.set('Till is required.'); return; }
    if (!float || isNaN(+float) || +float < 0) { this.openError.set('Opening float must be a valid non-negative number.'); return; }

    const request: OpenSessionRequest = { tillUid, openingFloatAmount: float };

    this.opening.set(true);
    this.openError.set(null);

    this.posService.openSession(request).subscribe({
      next: (created) => {
        this.opening.set(false);
        this.resetOpenForm();
        this.showOpenForm.set(false);
        this.alerts.success('Session opened', created.uid);
        this.load(0);
      },
      error: (err: unknown) => {
        this.openError.set(this.messageFrom(err, 'Could not open session.'));
        this.opening.set(false);
      },
    });
  }

  // ── Display helpers ────────────────────────────────────────────────────────

  statusBadgeClass(status: PosSessionStatus): string {
    switch (status) {
      case 'OPEN': return 'text-bg-success';
      case 'CLOSED': return 'text-bg-warning';
      case 'RECONCILED': return 'text-bg-secondary';
      default: return 'text-bg-light';
    }
  }

  private messageFrom(err: unknown, fallback: string): string {
    if (err instanceof HttpErrorResponse) {
      const errors = (err.error as { errors?: string[] })?.errors;
      if (errors?.length) return errors[0];
    }
    return fallback;
  }
}
