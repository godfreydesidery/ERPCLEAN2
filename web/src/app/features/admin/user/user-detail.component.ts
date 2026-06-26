import { Component, computed, inject, input, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { User } from '../models/user.model';
import { UserBranch } from '../models/user-branch.model';
import { UserRole } from '../models/user-role.model';
import { UserCompany } from '../models/user-company.model';
import { Branch } from '../models/branch.model';
import { Company } from '../models/company.model';
import { Role } from '../models/role.model';
import { UserService } from './user.service';
import { UserBranchService } from './user-branch.service';
import { UserCompanyService } from './user-company.service';
import { UserRoleService } from '../user-role/user-role.service';
import { BranchService } from '../branch/branch.service';
import { CompanyService } from '../company/company.service';
import { OrganisationService } from '../organisation/organisation.service';
import { RoleService } from '../role/role.service';
import { AlertService } from '../../../core/feedback/alert.service';
import { SessionStore } from '../../../core/auth/session.store';

type LoadState = 'loading' | 'idle' | 'error';
type IdleLoadState = 'idle' | 'loading' | 'error';

/**
 * The user "Assignments" screen — a header plus three cards in order: Companies, Branches, Roles.
 * Companies: assign/remove company memberships (gated by USER.COMPANY.MANAGE).
 * Branch picker: company select -> branch select -> make-default checkbox -> Assign.
 * Role picker: role select -> company select -> optional branch select -> Grant.
 * Branch and role company pickers are scoped to {@link assignableCompanies} (the user's
 * memberships) — assign a company first, then its branches/roles become available.
 * Per-row actions: Set default / Remove (branches), Revoke (roles), Remove (companies).
 * Route: /admin/users/uid/:uid — `uid` is bound via withComponentInputBinding.
 */
@Component({
  selector: 'app-user-detail',
  imports: [FormsModule, RouterLink],
  templateUrl: './user-detail.component.html',
  styleUrl: './user-detail.component.scss',
})
export class UserDetailComponent {
  private readonly userService = inject(UserService);
  private readonly userBranchService = inject(UserBranchService);
  private readonly userCompanyService = inject(UserCompanyService);
  private readonly userRoleService = inject(UserRoleService);
  private readonly branchService = inject(BranchService);
  private readonly companyService = inject(CompanyService);
  private readonly organisationService = inject(OrganisationService);
  private readonly roleService = inject(RoleService);
  private readonly alerts = inject(AlertService);
  private readonly session = inject(SessionStore);

  /** True when the session has USER.COMPANY.MANAGE — gates Assign/Remove controls in the Companies panel. */
  readonly canManageCompanies = computed(() => this.session.hasPermission('USER.COMPANY.MANAGE'));

  /** Route input — Angular binds the `:uid` path param to this signal via withComponentInputBinding. */
  readonly uid = input.required<string>();

  // ── User header ──────────────────────────────────────────────────────────
  readonly user = signal<User | null>(null);
  readonly userState = signal<LoadState>('loading');

  // ── Branch assignments list ───────────────────────────────────────────────
  readonly assignments = signal<UserBranch[]>([]);
  readonly assignmentsState = signal<LoadState>('loading');

  /** uid of the branch-assignment row whose action (set-default or remove) is in-flight. */
  readonly rowBusyUid = signal<string | null>(null);

  // ── Branch assign form ────────────────────────────────────────────────────
  /** Shared companies list — loaded once, used by both the branch panel and the role panel. */
  readonly companies = signal<Company[]>([]);
  readonly companiesState = signal<LoadState>('loading');
  readonly selectedCompanyUid = signal('');
  readonly branches = signal<Branch[]>([]);
  readonly branchesState = signal<IdleLoadState>('idle');
  readonly selectedBranchUid = signal('');
  readonly makeDefault = signal(false);
  readonly assigning = signal(false);
  readonly assignError = signal<string | null>(null);

  readonly hasAssignments = computed(() => this.assignments().length > 0);

  // ── Role assignments list ─────────────────────────────────────────────────
  readonly roleGrants = signal<UserRole[]>([]);
  readonly roleGrantsState = signal<LoadState>('loading');

  /** uid of the role-grant row whose Revoke is in-flight. */
  readonly roleRowBusyUid = signal<string | null>(null);

  // ── Role grant form ───────────────────────────────────────────────────────
  readonly roles = signal<Role[]>([]);
  readonly rolesState = signal<LoadState>('loading');
  readonly grantRoleUid = signal('');
  readonly grantCompanyUid = signal('');
  readonly grantBranches = signal<Branch[]>([]);
  readonly grantBranchesState = signal<IdleLoadState>('idle');
  readonly grantBranchUid = signal('');
  readonly granting = signal(false);
  readonly grantError = signal<string | null>(null);

  readonly hasRoleGrants = computed(() => this.roleGrants().length > 0);

  // ── Company memberships list ──────────────────────────────────────────────
  readonly companyMemberships = signal<UserCompany[]>([]);
  readonly companyMembershipsState = signal<LoadState>('loading');

  /** uid of the company-membership row whose Remove is in-flight. */
  readonly companyRowBusyUid = signal<string | null>(null);

  // ── Company assign form ───────────────────────────────────────────────────
  readonly assignCompanyUid = signal('');
  readonly assigningCompany = signal(false);
  /** null = no error; string = inline calm message (e.g. 409 duplicate). */
  readonly assignCompanyError = signal<string | null>(null);

  readonly hasCompanyMemberships = computed(() => this.companyMemberships().length > 0);

  /** All branches across all loaded companies — used for the branchName() resolver. */
  readonly allBranches = signal<Branch[]>([]);

  /** Look up a company name from the shared companies list; fall back to the uid. */
  readonly companyName = computed(() => {
    const map = new Map(this.companies().map((c) => [c.uid, c.name]));
    return (uid: string) => map.get(uid) ?? uid;
  });

  /** Look up a branch name from all loaded branches; fall back to the uid. */
  readonly branchName = computed(() => {
    const map = new Map(this.allBranches().map((b) => [b.uid, b.name]));
    return (uid: string) => map.get(uid) ?? uid;
  });

  /**
   * Companies the TARGET user is a member of, intersected with companies the current admin can
   * access. Branches and roles may only be assigned within these — you assign a company first
   * (the Companies card above), then its branches/roles become available here.
   */
  readonly assignableCompanies = computed(() => {
    const memberUids = new Set(this.companyMemberships().map((m) => m.companyUid));
    return this.companies().filter((c) => memberUids.has(c.uid));
  });
  readonly hasAssignableCompanies = computed(() => this.assignableCompanies().length > 0);

  constructor() {
    queueMicrotask(() => this.init());
  }

  private init(): void {
    this.loadUser();
    this.loadAssignments();
    this.loadCompanies();
    this.loadRoleGrants();
    this.loadRoles();
    this.loadCompanyMemberships();
  }

  private loadUser(): void {
    this.userState.set('loading');
    this.userService.get(this.uid()).subscribe({
      next: (u) => {
        this.user.set(u);
        this.userState.set('idle');
      },
      error: () => this.userState.set('error'),
    });
  }

  loadAssignments(): void {
    this.assignmentsState.set('loading');
    this.userBranchService.listForUser(this.uid()).subscribe({
      next: (rows) => {
        this.assignments.set(rows);
        this.assignmentsState.set('idle');
      },
      error: () => this.assignmentsState.set('error'),
    });
  }

  private loadCompanies(): void {
    this.companiesState.set('loading');
    this.organisationService.current().subscribe({
      next: (org) => {
        this.companyService.list(org.uid).subscribe({
          next: (rows) => {
            this.companies.set(rows);
            this.companiesState.set('idle');
            // Pre-load branches for all companies so branchName() can resolve role-grant branch uids.
            rows.forEach((c) => {
              this.branchService.list(c.uid).subscribe({
                next: (bs) => this.allBranches.update((prev) => [...prev, ...bs]),
                error: () => undefined,
              });
            });
          },
          error: () => this.companiesState.set('error'),
        });
      },
      error: () => this.companiesState.set('error'),
    });
  }

  onCompanyChange(companyUid: string): void {
    this.selectedCompanyUid.set(companyUid);
    this.selectedBranchUid.set('');
    this.branches.set([]);
    if (!companyUid) {
      this.branchesState.set('idle');
      return;
    }
    this.branchesState.set('loading');
    this.branchService.list(companyUid).subscribe({
      next: (rows) => {
        this.branches.set(rows);
        this.branchesState.set('idle');
      },
      error: () => this.branchesState.set('error'),
    });
  }

  assign(): void {
    const branchUid = this.selectedBranchUid();
    const userUid = this.uid();
    if (!branchUid) {
      this.assignError.set('Select a branch to assign.');
      return;
    }
    this.assigning.set(true);
    this.assignError.set(null);
    this.userBranchService
      .assign({ userUid, branchUid, makeDefault: this.makeDefault() })
      .subscribe({
        next: () => {
          this.selectedBranchUid.set('');
          this.makeDefault.set(false);
          this.assigning.set(false);
          this.alerts.success('Branch assigned');
          this.loadAssignments();
        },
        error: (err) => {
          this.assignError.set(this.messageFrom(err, 'Could not assign branch.'));
          this.assigning.set(false);
        },
      });
  }

  setDefault(assignment: UserBranch): void {
    if (assignment.isDefault || this.rowBusyUid() !== null) {
      return;
    }
    this.rowBusyUid.set(assignment.uid);
    this.userBranchService.setDefault(assignment.uid).subscribe({
      next: () => {
        this.rowBusyUid.set(null);
        this.alerts.success('Default branch updated', assignment.branchName);
        this.loadAssignments();
      },
      error: () => this.rowBusyUid.set(null),
    });
  }

  remove(assignment: UserBranch): void {
    if (this.rowBusyUid() !== null) {
      return;
    }
    this.rowBusyUid.set(assignment.uid);
    this.userBranchService.remove(assignment.uid).subscribe({
      next: () => {
        this.rowBusyUid.set(null);
        this.alerts.success('Branch removed', assignment.branchName);
        this.loadAssignments();
      },
      error: () => this.rowBusyUid.set(null),
    });
  }

  // ── Role grants ───────────────────────────────────────────────────────────

  loadRoleGrants(): void {
    this.roleGrantsState.set('loading');
    this.userRoleService.listForUser(this.uid()).subscribe({
      next: (rows) => {
        this.roleGrants.set(rows);
        this.roleGrantsState.set('idle');
      },
      error: () => this.roleGrantsState.set('error'),
    });
  }

  private loadRoles(): void {
    this.rolesState.set('loading');
    this.roleService.list().subscribe({
      next: (rows) => {
        this.roles.set(rows);
        this.rolesState.set('idle');
      },
      error: () => this.rolesState.set('error'),
    });
  }

  onGrantCompanyChange(companyUid: string): void {
    this.grantCompanyUid.set(companyUid);
    this.grantBranchUid.set('');
    this.grantBranches.set([]);
    if (!companyUid) {
      this.grantBranchesState.set('idle');
      return;
    }
    this.grantBranchesState.set('loading');
    this.branchService.list(companyUid).subscribe({
      next: (rows) => {
        this.grantBranches.set(rows);
        this.grantBranchesState.set('idle');
      },
      error: () => this.grantBranchesState.set('error'),
    });
  }

  grantRole(): void {
    const roleUid = this.grantRoleUid();
    const companyUid = this.grantCompanyUid();
    if (!roleUid || !companyUid) {
      this.grantError.set('Select a role and a company.');
      return;
    }
    const branchUid = this.grantBranchUid() || undefined;
    this.granting.set(true);
    this.grantError.set(null);
    this.userRoleService
      .grant({ userUid: this.uid(), roleUid, companyUid, branchUid })
      .subscribe({
        next: () => {
          this.grantRoleUid.set('');
          this.grantCompanyUid.set('');
          this.grantBranchUid.set('');
          this.grantBranches.set([]);
          this.grantBranchesState.set('idle');
          this.granting.set(false);
          this.alerts.success('Role granted');
          this.loadRoleGrants();
        },
        error: (err) => {
          this.grantError.set(this.messageFrom(err, 'Could not grant the role.'));
          this.granting.set(false);
        },
      });
  }

  revokeGrant(grant: UserRole): void {
    if (this.roleRowBusyUid() !== null) {
      return;
    }
    this.roleRowBusyUid.set(grant.uid);
    this.userRoleService.revoke(grant.uid).subscribe({
      next: () => {
        this.roleRowBusyUid.set(null);
        this.alerts.success('Role revoked', grant.roleCode);
        this.loadRoleGrants();
      },
      error: () => this.roleRowBusyUid.set(null),
    });
  }

  // ── Company memberships ───────────────────────────────────────────────────

  loadCompanyMemberships(): void {
    this.companyMembershipsState.set('loading');
    this.userCompanyService.listForUser(this.uid()).subscribe({
      next: (rows) => {
        this.companyMemberships.set(rows);
        this.companyMembershipsState.set('idle');
      },
      error: () => this.companyMembershipsState.set('error'),
    });
  }

  assignCompany(): void {
    const companyUid = this.assignCompanyUid();
    if (!companyUid) {
      this.assignCompanyError.set('Select a company to assign.');
      return;
    }
    this.assigningCompany.set(true);
    this.assignCompanyError.set(null);
    this.userCompanyService.assign({ userUid: this.uid(), companyUid }).subscribe({
      next: () => {
        this.assignCompanyUid.set('');
        this.assigningCompany.set(false);
        this.alerts.success('Company assigned');
        this.loadCompanyMemberships();
      },
      error: (err) => {
        const status = (err as { status?: number })?.status;
        if (status === 409) {
          this.assignCompanyError.set('Already a member of that company.');
        } else {
          this.assignCompanyError.set(this.messageFrom(err, 'Could not assign company.'));
        }
        this.assigningCompany.set(false);
      },
    });
  }

  removeCompany(membership: UserCompany): void {
    if (this.companyRowBusyUid() !== null) {
      return;
    }
    this.companyRowBusyUid.set(membership.uid);
    this.userCompanyService.remove(membership.uid).subscribe({
      next: () => {
        this.companyRowBusyUid.set(null);
        this.alerts.success('Company membership removed', membership.companyName);
        this.loadCompanyMemberships();
      },
      error: () => this.companyRowBusyUid.set(null),
    });
  }

  private messageFrom(err: unknown, fallback: string): string {
    const errors = (err as { error?: { errors?: string[] } })?.error?.errors;
    return errors?.length ? errors[0] : fallback;
  }
}
