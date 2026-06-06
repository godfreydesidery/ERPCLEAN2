import { HttpErrorResponse } from '@angular/common/http';
import { Component, computed, inject, signal } from '@angular/core';
import { takeUntilDestroyed, toObservable } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { debounceTime, distinctUntilChanged, map, merge, skip, Subject, switchMap } from 'rxjs';
import { PageMeta } from '../../../core/api/api-response.model';
import { AlertService } from '../../../core/feedback/alert.service';
import { SessionStore } from '../../../core/auth/session.store';
import { Company } from '../models/company.model';
import { AgentKind, AgentModel, CreateAgentRequest, PartyType } from '../models/party.model';
import { User } from '../models/user.model';
import { CompanyService } from '../company/company.service';
import { OrganisationService } from '../organisation/organisation.service';
import { UserService } from '../user/user.service';
import { AgentService } from './agent.service';

const DEFAULT_SIZE = 20;

interface LoadTrigger { q: string; page: number }

@Component({
  selector: 'app-agent-list',
  imports: [FormsModule, RouterLink],
  templateUrl: './agent-list.component.html',
  styleUrl: './agent-list.component.scss',
})
export class AgentListComponent {
  private readonly agentService = inject(AgentService);
  private readonly companyService = inject(CompanyService);
  private readonly organisationService = inject(OrganisationService);
  private readonly userService = inject(UserService);
  private readonly alerts = inject(AlertService);
  protected readonly session = inject(SessionStore);

  readonly companies = signal<Company[]>([]);
  readonly selectedCompanyId = signal('');
  readonly companyState = signal<'loading' | 'idle' | 'error'>('loading');

  readonly rows = signal<AgentModel[]>([]);
  readonly meta = signal<PageMeta>({ page: 0, size: DEFAULT_SIZE, totalElements: 0, totalPages: 0, hasNext: false });
  readonly state = signal<'loading' | 'idle' | 'error' | 'forbidden'>('idle');
  readonly currentPage = signal(0);

  readonly searchQ = signal('');

  readonly newDisplayName = signal('');
  readonly newPartyType = signal<PartyType>('INDIVIDUAL');
  readonly newAgentKind = signal<AgentKind>('EXTERNAL');
  readonly newAppUserId = signal('');
  // Identity fields — conditional on partyType (BR-PARTY-04/05/06).
  readonly newLegalName = signal('');
  readonly newTin = signal('');
  readonly newVatRegistered = signal(false);
  readonly newVrn = signal('');
  readonly newBusinessRegNo = signal('');
  readonly saving = signal(false);
  readonly formError = signal<string | null>(null);
  readonly showCreateForm = signal(false);

  // ── Users list — loaded lazily when INTERNAL kind is first needed ─────────
  readonly users = signal<User[]>([]);
  readonly usersState = signal<'loading' | 'idle' | 'error'>('idle');
  private usersLoaded = false;

  readonly canManage = computed(() => this.session.hasPermission('AGENT.MANAGE'));
  readonly isEmpty = computed(() => this.state() === 'idle' && this.rows().length === 0);

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
          return this.agentService.list(companyId, q || undefined, page, DEFAULT_SIZE);
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

  applySearch(): void { this.load(0); }

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
    const opening = !this.showCreateForm();
    this.showCreateForm.update((v) => !v);
    this.formError.set(null);
    // Lazy-load users when the form is first opened so the picker is ready.
    if (opening) this.ensureUsersLoaded();
  }

  /** Called from the kind <select> (ngModelChange). */
  onNewAgentKindChange(kind: AgentKind): void {
    this.newAgentKind.set(kind);
    if (kind === 'EXTERNAL') {
      // BR-PARTY-11: external must have no user; clear the picker.
      this.newAppUserId.set('');
    } else {
      // Lazy-load when kind switches to INTERNAL in case form was already open.
      this.ensureUsersLoaded();
    }
  }

  private ensureUsersLoaded(): void {
    if (this.usersLoaded) return;
    this.usersLoaded = true;
    this.usersState.set('loading');
    this.userService.list().subscribe({
      // Exclude the super-admin: root is a system user, never a sales agent (BR-PARTY-10).
      // The backend rejects it too; hiding it here means it's never offered.
      next: (list) => { this.users.set(list.filter((u) => !u.isRoot)); this.usersState.set('idle'); },
      error: () => this.usersState.set('error'),
    });
  }

  private resetCreateForm(): void {
    this.newDisplayName.set('');
    this.newPartyType.set('INDIVIDUAL');
    this.newAgentKind.set('EXTERNAL');
    this.newAppUserId.set('');
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
    // BR-PARTY-04: BUSINESS must have a TIN — client guard saves a round-trip.
    if (this.newPartyType() === 'BUSINESS' && !this.newTin().trim()) {
      this.formError.set('A business party must have a TIN (BR-PARTY-04).');
      return;
    }
    // BR-PARTY-10: INTERNAL agent must reference an app user.
    if (this.newAgentKind() === 'INTERNAL' && !this.newAppUserId()) {
      this.formError.set('An internal agent must reference an app user (BR-PARTY-10).');
      return;
    }
    this.saving.set(true);
    this.formError.set(null);
    const vatRegistered = this.newVatRegistered();
    const isInternal = this.newAgentKind() === 'INTERNAL';
    const request: CreateAgentRequest = {
      companyId,
      partyType: this.newPartyType(),
      displayName,
      legalName: this.newLegalName().trim() || undefined,
      tin: this.newTin().trim() || undefined,
      vatRegistered: vatRegistered || undefined,
      // BR-PARTY-06: VRN only valid when vatRegistered is true.
      vrn: vatRegistered ? (this.newVrn().trim() || undefined) : undefined,
      businessRegNo: this.newBusinessRegNo().trim() || undefined,
      agentKind: this.newAgentKind(),
      // BR-PARTY-10/11: send appUserId only for INTERNAL; omit entirely for EXTERNAL.
      appUserId: isInternal ? this.newAppUserId() : undefined,
    };
    this.agentService.create(request).subscribe({
      next: (created) => {
        this.saving.set(false);
        this.resetCreateForm();
        this.showCreateForm.set(false);
        this.alerts.success('Agent created', created.displayName);
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
    return errors?.length ? errors[0] : 'Could not save the agent.';
  }
}
