import { HttpErrorResponse } from '@angular/common/http';
import { Component, computed, inject, signal } from '@angular/core';
import { takeUntilDestroyed, toObservable } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { debounceTime, distinctUntilChanged, map, merge, skip, Subject, switchMap } from 'rxjs';
import { PageMeta } from '../../../../core/api/api-response.model';
import { AlertService } from '../../../../core/feedback/alert.service';
import { SessionStore } from '../../../../core/auth/session.store';
import { Company } from '../../models/company.model';
import { PartyType } from '../../models/party.model';
import { CompanyService } from '../../company/company.service';
import { OrganisationService } from '../../organisation/organisation.service';
import { PaginatorComponent } from '../../../../shared/paginator/paginator.component';
import { OtherPartyModel, CreateOtherPartyRequest } from './other-party.model';
import { OtherPartyService } from './other-party.service';

const DEFAULT_SIZE = 20;

interface LoadTrigger {
  q: string;
  page: number;
}

/**
 * Paged list of other parties scoped to the active company.
 * Provides a search box and an inline create form.
 * All four states (loading / empty / error / forbidden) handled.
 */
@Component({
  selector: 'app-other-party-list',
  imports: [FormsModule, RouterLink, PaginatorComponent],
  templateUrl: './other-party-list.component.html',
  styleUrl: './other-party-list.component.scss',
})
export class OtherPartyListComponent {
  private readonly otherPartyService = inject(OtherPartyService);
  private readonly companyService = inject(CompanyService);
  private readonly organisationService = inject(OrganisationService);
  private readonly alerts = inject(AlertService);
  protected readonly session = inject(SessionStore);

  // ── Company context ────────────────────────────────────────────────────────
  readonly companies = signal<Company[]>([]);
  readonly selectedCompanyId = signal('');
  readonly companyState = signal<'loading' | 'idle' | 'error'>('loading');

  // ── List state ─────────────────────────────────────────────────────────────
  readonly rows = signal<OtherPartyModel[]>([]);
  readonly meta = signal<PageMeta>({ page: 0, size: DEFAULT_SIZE, totalElements: 0, totalPages: 0, hasNext: false });
  readonly state = signal<'loading' | 'idle' | 'error' | 'forbidden'>('idle');
  readonly currentPage = signal(0);

  // ── Search ─────────────────────────────────────────────────────────────────
  readonly searchQ = signal('');

  // ── Create form ────────────────────────────────────────────────────────────
  readonly newDisplayName = signal('');
  readonly newPartyType = signal<PartyType>('INDIVIDUAL');
  readonly newOtherKind = signal('');
  readonly newLegalName = signal('');
  readonly newTin = signal('');
  readonly newVatRegistered = signal(false);
  readonly newVrn = signal('');
  readonly newBusinessRegNo = signal('');
  readonly saving = signal(false);
  readonly formError = signal<string | null>(null);
  readonly showCreateForm = signal(false);

  readonly canManage = computed(() => this.session.hasPermission('OTHERPARTY.MANAGE'));
  readonly isEmpty = computed(() => this.state() === 'idle' && this.rows().length === 0);

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
          return this.otherPartyService.list(companyId, q || undefined, page, DEFAULT_SIZE);
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

  goToPage(page: number): void {
    this.load(page);
  }

  load(page: number): void {
    const companyId = this.selectedCompanyId();
    if (!companyId) return;
    this.immediateTrigger$.next({ q: this.searchQ(), page });
  }

  toggleCreateForm(): void {
    this.showCreateForm.update((v) => !v);
    this.formError.set(null);
  }

  private resetCreateForm(): void {
    this.newDisplayName.set('');
    this.newPartyType.set('INDIVIDUAL');
    this.newOtherKind.set('');
    this.newLegalName.set('');
    this.newTin.set('');
    this.newVatRegistered.set(false);
    this.newVrn.set('');
    this.newBusinessRegNo.set('');
  }

  onNewVatRegisteredChange(checked: boolean): void {
    this.newVatRegistered.set(checked);
    if (!checked) this.newVrn.set('');
  }

  create(): void {
    const companyId = this.selectedCompanyId();
    const displayName = this.newDisplayName().trim();
    if (!companyId || !displayName) {
      this.formError.set('Company and display name are required.');
      return;
    }
    if (this.newPartyType() === 'BUSINESS' && !this.newTin().trim()) {
      this.formError.set('A business party must have a TIN (BR-PARTY-04).');
      return;
    }
    this.saving.set(true);
    this.formError.set(null);
    const vatRegistered = this.newVatRegistered();
    const request: CreateOtherPartyRequest = {
      companyId,
      partyType: this.newPartyType(),
      displayName,
      legalName: this.newLegalName().trim() || undefined,
      tin: this.newTin().trim() || undefined,
      vatRegistered: vatRegistered || undefined,
      vrn: vatRegistered ? (this.newVrn().trim() || undefined) : undefined,
      businessRegNo: this.newBusinessRegNo().trim() || undefined,
      otherKind: this.newOtherKind().trim() || undefined,
    };
    this.otherPartyService.create(request).subscribe({
      next: (created) => {
        this.saving.set(false);
        this.resetCreateForm();
        this.showCreateForm.set(false);
        this.alerts.success('Other party created', created.displayName);
        this.load(this.currentPage());
      },
      error: (err) => {
        this.formError.set(this.messageFrom(err));
        this.saving.set(false);
      },
    });
  }

  statusBadgeClass(status: string): string {
    return status === 'ACTIVE' ? 'text-bg-success' : 'text-bg-secondary';
  }

  private messageFrom(err: unknown): string {
    const errors = (err as { error?: { errors?: string[] } })?.error?.errors;
    return errors?.length ? errors[0] : 'Could not save the other party.';
  }
}
