import { Component, computed, inject, input, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { User } from '../models/user.model';
import { UserBranch } from '../models/user-branch.model';
import { UserRole } from '../models/user-role.model';
import { Branch } from '../models/branch.model';
import { Company } from '../models/company.model';
import { Role } from '../models/role.model';
import { UserService } from './user.service';
import { UserBranchService } from './user-branch.service';
import { UserRoleService } from '../user-role/user-role.service';
import { BranchService } from '../branch/branch.service';
import { CompanyService } from '../company/company.service';
import { OrganisationService } from '../organisation/organisation.service';
import { RoleService } from '../role/role.service';
import { AlertService } from '../../../core/feedback/alert.service';

type LoadState = 'loading' | 'idle' | 'error';
type IdleLoadState = 'idle' | 'loading' | 'error';

/**
 * Shows a user header and manages their branch assignments and role assignments.
 * Branch picker: company select -> branch select -> make-default checkbox -> Assign.
 * Role picker: role select -> company select -> optional branch select -> Grant.
 * Per-row actions: Set default / Remove (branches), Revoke (roles), each with a busy spinner.
 * Route: /admin/users/:uid — `uid` is bound via withComponentInputBinding.
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
  private readonly userRoleService = inject(UserRoleService);
  private readonly branchService = inject(BranchService);
  private readonly companyService = inject(CompanyService);
  private readonly organisationService = inject(OrganisationService);
  private readonly roleService = inject(RoleService);
  private readonly alerts = inject(AlertService);

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

  constructor() {
    queueMicrotask(() => this.init());
  }

  private init(): void {
    this.loadUser();
    this.loadAssignments();
    this.loadCompanies();
    this.loadRoleGrants();
    this.loadRoles();
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

  private messageFrom(err: unknown, fallback: string): string {
    const errors = (err as { error?: { errors?: string[] } })?.error?.errors;
    return errors?.length ? errors[0] : fallback;
  }
}
