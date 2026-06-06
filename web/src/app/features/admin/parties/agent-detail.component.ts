import { HttpErrorResponse } from '@angular/common/http';
import { Component, computed, inject, input, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { AlertService } from '../../../core/feedback/alert.service';
import { SessionStore } from '../../../core/auth/session.store';
import { Branch } from '../models/branch.model';
import { Company } from '../models/company.model';
import { AgentKind, AgentModel, PartyBranch, PartyType, UpdateAgentRequest } from '../models/party.model';
import { User } from '../models/user.model';
import { BranchService } from '../branch/branch.service';
import { CompanyService } from '../company/company.service';
import { OrganisationService } from '../organisation/organisation.service';
import { UserService } from '../user/user.service';
import { AgentService } from './agent.service';

type LoadState = 'loading' | 'idle' | 'error';

/**
 * Agent detail + edit. Route: /admin/agents/uid/:uid.
 * When agentKind is INTERNAL, a user selector is shown; EXTERNAL shows no user picker.
 * The backend enforces this rule; the UI guides via show/hide + aria-required.
 */
@Component({
  selector: 'app-agent-detail',
  imports: [FormsModule, RouterLink],
  templateUrl: './agent-detail.component.html',
  styleUrl: './agent-detail.component.scss',
})
export class AgentDetailComponent {
  private readonly agentService = inject(AgentService);
  private readonly companyService = inject(CompanyService);
  private readonly organisationService = inject(OrganisationService);
  private readonly branchService = inject(BranchService);
  private readonly userService = inject(UserService);
  private readonly alerts = inject(AlertService);
  protected readonly session = inject(SessionStore);

  readonly uid = input.required<string>();

  readonly agent = signal<AgentModel | null>(null);
  readonly agentState = signal<LoadState>('loading');

  readonly fDisplayName = signal('');
  readonly fLegalName = signal('');
  readonly fPartyType = signal<PartyType>('INDIVIDUAL');
  readonly fTin = signal('');
  readonly fVatRegistered = signal(false);
  readonly fVrn = signal('');
  readonly fBusinessRegNo = signal('');
  readonly fMobileMoneyNo = signal('');
  readonly fPhone = signal('');
  readonly fEmail = signal('');
  readonly fPhysicalAddress = signal('');
  readonly fPostalAddress = signal('');
  readonly fRegion = signal('');
  readonly fDistrict = signal('');
  readonly fAgentKind = signal<AgentKind>('EXTERNAL');
  readonly fAppUserId = signal('');

  readonly saving = signal(false);
  readonly saveError = signal<string | null>(null);
  readonly archiving = signal(false);

  readonly isInternal = computed(() => this.fAgentKind() === 'INTERNAL');
  readonly canManage = computed(() => this.session.hasPermission('AGENT.MANAGE'));
  readonly canAssign = computed(() => this.session.hasPermission('PARTY.BRANCH.ASSIGN'));

  // ── Users list for agent-kind=INTERNAL ────────────────────────────────────
  readonly users = signal<User[]>([]);
  readonly usersState = signal<LoadState>('idle');

  // ── Branch associations ────────────────────────────────────────────────────
  readonly branches = signal<PartyBranch[]>([]);
  readonly branchesState = signal<LoadState>('loading');
  readonly rowBusyBranchId = signal<string | null>(null);

  readonly companies = signal<Company[]>([]);
  readonly companiesState = signal<LoadState>('loading');
  readonly selectedCompanyUid = signal('');
  readonly companyBranches = signal<Branch[]>([]);
  readonly companyBranchesState = signal<'idle' | 'loading' | 'error'>('idle');
  readonly selectedBranchUid = signal('');
  readonly assigning = signal(false);
  readonly assignError = signal<string | null>(null);

  private readonly branchById = signal<Map<string, Branch>>(new Map());

  readonly hasBranches = computed(() => this.branches().length > 0);

  branchDisplay(branchId: string): string {
    const b = this.branchById().get(branchId);
    return b ? `${b.code} — ${b.name}` : branchId;
  }

  userDisplay(userId: string): string {
    if (!userId) return '—';
    const u = this.users().find((u) => u.id === userId);
    return u ? `${u.username} (${u.displayName})` : userId;
  }

  constructor() {
    queueMicrotask(() => this.init());
  }

  private init(): void {
    this.loadAgent();
    this.loadBranches();
    this.loadCompanies();
    this.loadUsers();
  }

  private loadAgent(): void {
    this.agentState.set('loading');
    this.agentService.getByUid(this.uid()).subscribe({
      next: (a) => {
        this.agent.set(a);
        this.agentState.set('idle');
        this.patchForm(a);
      },
      error: () => this.agentState.set('error'),
    });
  }

  private patchForm(a: AgentModel): void {
    this.fDisplayName.set(a.displayName ?? '');
    this.fLegalName.set(a.legalName ?? '');
    this.fPartyType.set(a.partyType);
    this.fTin.set(a.tin ?? '');
    this.fVatRegistered.set(a.vatRegistered);
    this.fVrn.set(a.vrn ?? '');
    this.fBusinessRegNo.set(a.businessRegNo ?? '');
    this.fMobileMoneyNo.set(a.mobileMoneyNo ?? '');
    this.fPhone.set(a.phone ?? '');
    this.fEmail.set(a.email ?? '');
    this.fPhysicalAddress.set(a.physicalAddress ?? '');
    this.fPostalAddress.set(a.postalAddress ?? '');
    this.fRegion.set(a.region ?? '');
    this.fDistrict.set(a.district ?? '');
    this.fAgentKind.set(a.agentKind);
    this.fAppUserId.set(a.appUserId ?? '');
  }

  private loadUsers(): void {
    this.usersState.set('loading');
    this.userService.list().subscribe({
      next: (list) => { this.users.set(list); this.usersState.set('idle'); },
      error: () => this.usersState.set('error'),
    });
  }

  loadBranches(): void {
    this.branchesState.set('loading');
    this.agentService.listBranches(this.uid()).subscribe({
      next: (rows) => { this.branches.set(rows); this.branchesState.set('idle'); },
      error: () => this.branchesState.set('error'),
    });
  }

  private loadCompanies(): void {
    this.companiesState.set('loading');
    this.organisationService.current().subscribe({
      next: (org) => {
        this.companyService.list(org.uid).subscribe({
          next: (list) => {
            this.companies.set(list);
            this.companiesState.set('idle');
            list.forEach((co) => this.loadCompanyBranchesForDisplay(co.uid));
          },
          error: () => this.companiesState.set('error'),
        });
      },
      error: () => this.companiesState.set('error'),
    });
  }

  private loadCompanyBranchesForDisplay(companyUid: string): void {
    this.branchService.list(companyUid).subscribe({
      next: (rows) => {
        this.branchById.update((map) => {
          const next = new Map(map);
          rows.forEach((b) => next.set(b.id, b));
          return next;
        });
      },
      error: () => undefined,
    });
  }

  onCompanyChange(companyUid: string): void {
    this.selectedCompanyUid.set(companyUid);
    this.selectedBranchUid.set('');
    this.companyBranches.set([]);
    if (!companyUid) { this.companyBranchesState.set('idle'); return; }
    this.companyBranchesState.set('loading');
    this.branchService.list(companyUid).subscribe({
      next: (rows) => {
        this.companyBranches.set(rows);
        this.companyBranchesState.set('idle');
        this.branchById.update((map) => {
          const next = new Map(map);
          rows.forEach((b) => next.set(b.id, b));
          return next;
        });
      },
      error: () => this.companyBranchesState.set('error'),
    });
  }

  assign(): void {
    const branchUid = this.selectedBranchUid();
    if (!branchUid) { this.assignError.set('Select a branch to assign.'); return; }
    this.assigning.set(true);
    this.assignError.set(null);
    this.agentService.assignBranch(this.uid(), { branchUid }).subscribe({
      next: () => {
        this.selectedBranchUid.set('');
        this.assigning.set(false);
        this.alerts.success('Branch assigned');
        this.loadBranches();
      },
      error: (err) => {
        this.assignError.set(this.messageFrom(err, 'Could not assign branch.'));
        this.assigning.set(false);
      },
    });
  }

  removeBranch(pb: PartyBranch): void {
    if (this.rowBusyBranchId() !== null) return;
    const branch = this.branchById().get(pb.branchId);
    if (!branch) return;
    this.rowBusyBranchId.set(pb.branchId);
    this.agentService.removeBranch(this.uid(), branch.uid).subscribe({
      next: () => {
        this.rowBusyBranchId.set(null);
        this.alerts.success('Branch removed', branch.name);
        this.loadBranches();
      },
      error: () => this.rowBusyBranchId.set(null),
    });
  }

  save(): void {
    const displayName = this.fDisplayName().trim();
    if (!displayName) { this.saveError.set('Display name is required.'); return; }
    if (this.isInternal() && !this.fAppUserId()) {
      this.saveError.set('An IAM user must be selected for an internal agent.');
      return;
    }
    this.saving.set(true);
    this.saveError.set(null);

    const request: UpdateAgentRequest = {
      partyType: this.fPartyType(),
      displayName,
      legalName: this.fLegalName().trim() || undefined,
      tin: this.fTin().trim() || undefined,
      vatRegistered: this.fVatRegistered(),
      vrn: this.fVatRegistered() && this.fVrn().trim() ? this.fVrn().trim() : undefined,
      businessRegNo: this.fBusinessRegNo().trim() || undefined,
      mobileMoneyNo: this.fMobileMoneyNo().trim() || undefined,
      phone: this.fPhone().trim() || undefined,
      email: this.fEmail().trim() || undefined,
      physicalAddress: this.fPhysicalAddress().trim() || undefined,
      postalAddress: this.fPostalAddress().trim() || undefined,
      region: this.fRegion().trim() || undefined,
      district: this.fDistrict().trim() || undefined,
      agentKind: this.fAgentKind(),
      appUserId: this.isInternal() && this.fAppUserId() ? this.fAppUserId() : undefined,
    };

    this.agentService.update(this.uid(), request).subscribe({
      next: (updated) => {
        this.agent.set(updated);
        this.saving.set(false);
        this.alerts.success('Agent saved', updated.displayName);
      },
      error: (err) => {
        this.saveError.set(this.messageFrom(err, 'Could not save the agent.'));
        this.saving.set(false);
      },
    });
  }

  archive(): void {
    if (this.archiving()) return;
    this.archiving.set(true);
    this.agentService.archive(this.uid()).subscribe({
      next: () => {
        this.archiving.set(false);
        this.agent.update((a) => (a ? { ...a, status: 'ARCHIVED' } : a));
        this.alerts.success('Agent archived');
      },
      error: (err) => { this.archiving.set(false); this.saveError.set(this.messageFrom(err, 'Could not archive.')); },
    });
  }

  restore(): void {
    if (this.archiving()) return;
    this.archiving.set(true);
    this.agentService.restore(this.uid()).subscribe({
      next: () => {
        this.archiving.set(false);
        this.agent.update((a) => (a ? { ...a, status: 'ACTIVE' } : a));
        this.alerts.success('Agent restored');
      },
      error: (err) => { this.archiving.set(false); this.saveError.set(this.messageFrom(err, 'Could not restore.')); },
    });
  }

  private messageFrom(err: unknown, fallback: string): string {
    if (err instanceof HttpErrorResponse) {
      const errors = (err.error as { errors?: string[] })?.errors;
      if (errors?.length) return errors[0];
    }
    return fallback;
  }
}
