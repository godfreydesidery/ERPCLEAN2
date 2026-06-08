import { HttpErrorResponse } from '@angular/common/http';
import { Component, computed, inject, signal } from '@angular/core';
import { takeUntilDestroyed, toObservable } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { debounceTime, distinctUntilChanged, map, merge, skip, Subject, switchMap } from 'rxjs';
import { PageMeta } from '../../../core/api/api-response.model';
import { AlertService } from '../../../core/feedback/alert.service';
import { SessionStore } from '../../../core/auth/session.store';
import { Company } from '../models/company.model';
import { CompanyService } from '../company/company.service';
import { OrganisationService } from '../organisation/organisation.service';
import { AccountDto, AccountType, CreateAccountRequest } from './models/gl.model';
import { GlService } from './gl.service';

const DEFAULT_SIZE = 50;

interface LoadTrigger { q: string; page: number }

/**
 * Paged list of Chart of Accounts scoped to the active company.
 * Provides debounced search and an inline create form.
 * Gated by GL.MANAGE for create/deactivate; GL.VIEW for list.
 * All four states (loading / empty / error / forbidden) handled.
 */
@Component({
  selector: 'app-chart-of-accounts-list',
  imports: [FormsModule],
  templateUrl: './chart-of-accounts-list.component.html',
  styleUrl: './chart-of-accounts-list.component.scss',
})
export class ChartOfAccountsListComponent {
  private readonly glService = inject(GlService);
  private readonly companyService = inject(CompanyService);
  private readonly organisationService = inject(OrganisationService);
  private readonly alerts = inject(AlertService);
  protected readonly session = inject(SessionStore);

  // ── Company context ────────────────────────────────────────────────────────
  readonly companies = signal<Company[]>([]);
  readonly selectedCompanyId = signal('');
  readonly companyState = signal<'loading' | 'idle' | 'error'>('loading');

  // ── List state ─────────────────────────────────────────────────────────────
  readonly rows = signal<AccountDto[]>([]);
  readonly meta = signal<PageMeta>({ page: 0, size: DEFAULT_SIZE, totalElements: 0, totalPages: 0, hasNext: false });
  readonly state = signal<'loading' | 'idle' | 'error' | 'forbidden'>('idle');
  readonly currentPage = signal(0);

  // ── Search ─────────────────────────────────────────────────────────────────
  readonly searchQ = signal('');

  // ── Create form ────────────────────────────────────────────────────────────
  readonly showCreateForm = signal(false);
  readonly newCode = signal('');
  readonly newName = signal('');
  readonly newAccountType = signal<AccountType>('ASSET');
  readonly saving = signal(false);
  readonly formError = signal<string | null>(null);

  // ── Row actions ────────────────────────────────────────────────────────────
  readonly rowBusyUid = signal<string | null>(null);

  readonly canManage = computed(() => this.session.hasPermission('GL.MANAGE'));
  readonly isEmpty = computed(() => this.state() === 'idle' && this.rows().length === 0);

  readonly accountTypes: AccountType[] = ['ASSET', 'LIABILITY', 'EQUITY', 'INCOME', 'EXPENSE'];

  // ── Search pipeline ────────────────────────────────────────────────────────
  private readonly immediateTrigger$ = new Subject<LoadTrigger>();

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
          return this.glService.listAccounts(companyId, q || undefined, page, DEFAULT_SIZE);
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

  applySearch(): void {
    this.load(0);
  }

  clearSearch(): void {
    this.searchQ.set('');
    this.load(0);
  }

  prevPage(): void {
    const p = this.currentPage();
    if (p > 0) this.load(p - 1);
  }

  nextPage(): void {
    if (this.meta().hasNext) this.load(this.currentPage() + 1);
  }

  load(page: number): void {
    const companyId = this.selectedCompanyId();
    if (!companyId) return;
    this.immediateTrigger$.next({ q: this.searchQ(), page });
  }

  toggleCreateForm(): void {
    this.showCreateForm.update((v) => !v);
    this.formError.set(null);
    if (!this.showCreateForm()) this.resetCreateForm();
  }

  private resetCreateForm(): void {
    this.newCode.set('');
    this.newName.set('');
    this.newAccountType.set('ASSET');
  }

  create(): void {
    const companyId = this.selectedCompanyId();
    const code = String(this.newCode() ?? '').trim();
    const name = String(this.newName() ?? '').trim();

    if (!companyId || !code || !name) {
      this.formError.set('Account code and name are required.');
      return;
    }

    const company = this.companies().find((c) => c.id === companyId);
    if (!company) {
      this.formError.set('Could not resolve selected company.');
      return;
    }

    this.saving.set(true);
    this.formError.set(null);

    const request: CreateAccountRequest = {
      companyUid: company.uid,
      accountCode: code,
      name,
      accountType: this.newAccountType(),
    };

    this.glService.createAccount(request).subscribe({
      next: (created) => {
        this.saving.set(false);
        this.resetCreateForm();
        this.showCreateForm.set(false);
        this.alerts.success('Account created', created.accountCode + ' — ' + created.name);
        this.load(this.currentPage());
      },
      error: (err) => {
        this.formError.set(this.messageFrom(err));
        this.saving.set(false);
      },
    });
  }

  deactivate(account: AccountDto): void {
    if (this.rowBusyUid() !== null) return;
    this.rowBusyUid.set(account.uid);
    this.glService.updateAccount(account.uid, { name: account.name, active: false }).subscribe({
      next: () => {
        this.rowBusyUid.set(null);
        this.alerts.success('Account deactivated', account.name);
        this.load(this.currentPage());
      },
      error: () => this.rowBusyUid.set(null),
    });
  }

  reactivate(account: AccountDto): void {
    if (this.rowBusyUid() !== null) return;
    this.rowBusyUid.set(account.uid);
    this.glService.updateAccount(account.uid, { name: account.name, active: true }).subscribe({
      next: () => {
        this.rowBusyUid.set(null);
        this.alerts.success('Account reactivated', account.name);
        this.load(this.currentPage());
      },
      error: () => this.rowBusyUid.set(null),
    });
  }

  typeBadgeClass(type: AccountType): string {
    switch (type) {
      case 'ASSET': return 'text-bg-primary';
      case 'LIABILITY': return 'text-bg-warning';
      case 'EQUITY': return 'text-bg-info';
      case 'INCOME': return 'text-bg-success';
      case 'EXPENSE': return 'text-bg-danger';
    }
  }

  private messageFrom(err: unknown): string {
    const errors = (err as { error?: { errors?: string[] } })?.error?.errors;
    return errors?.length ? errors[0] : 'Could not save the account.';
  }
}
