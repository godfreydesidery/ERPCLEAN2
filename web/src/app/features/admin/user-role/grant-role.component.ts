import { Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Role } from '../models/role.model';
import { UserRole } from '../models/user-role.model';
import { User } from '../models/user.model';
import { Company } from '../models/company.model';
import { Branch } from '../models/branch.model';
import { RoleService } from '../role/role.service';
import { UserRoleService } from './user-role.service';
import { UserService } from '../user/user.service';
import { CompanyService } from '../company/company.service';
import { BranchService } from '../branch/branch.service';
import { OrganisationService } from '../organisation/organisation.service';
import { AlertService } from '../../../core/feedback/alert.service';
import { UidPickerComponent, UidOption } from '../../../shared/uid-picker/uid-picker.component';

/**
 * Minimal grant-role screen for v1: a form to assign a role to a user (by uid) for a given
 * company + optional branch, and a list of existing grants with a Revoke action. All four states
 * are handled on both the grant list and the grant form.
 */
@Component({
  selector: 'app-grant-role',
  imports: [FormsModule, UidPickerComponent],
  templateUrl: './grant-role.component.html',
  styleUrl: './grant-role.component.scss',
})
export class GrantRoleComponent {
  private readonly userRoleService = inject(UserRoleService);
  private readonly roleService = inject(RoleService);
  private readonly userService = inject(UserService);
  private readonly companyService = inject(CompanyService);
  private readonly branchService = inject(BranchService);
  private readonly organisationService = inject(OrganisationService);
  private readonly alerts = inject(AlertService);

  // Available roles for the dropdown
  readonly roles = signal<Role[]>([]);
  readonly rolesState = signal<'loading' | 'idle' | 'error'>('loading');

  // Picker option lists
  readonly users = signal<User[]>([]);
  readonly companies = signal<Company[]>([]);
  readonly branches = signal<Branch[]>([]);

  readonly userOptions = computed<UidOption[]>(() =>
    this.users().map((u) => ({ uid: u.uid, label: u.displayName, hint: u.username })),
  );
  readonly companyOptions = computed<UidOption[]>(() =>
    this.companies().map((c) => ({ uid: c.uid, label: c.name, hint: c.code })),
  );
  readonly branchOptions = computed<UidOption[]>(() =>
    this.branches().map((b) => ({ uid: b.uid, label: b.name, hint: b.code })),
  );

  /** All branches across all loaded companies — used for the branchName() resolver in the grants table. */
  readonly allBranches = signal<Branch[]>([]);

  /** Look up a company name; falls back to uid. */
  readonly companyName = computed(() => {
    const map = new Map(this.companies().map((c) => [c.uid, c.name]));
    return (uid: string) => map.get(uid) ?? uid;
  });

  /** Look up a branch name; falls back to uid. */
  readonly branchName = computed(() => {
    const map = new Map(this.allBranches().map((b) => [b.uid, b.name]));
    return (uid: string) => map.get(uid) ?? uid;
  });

  // Form fields
  readonly userUid = signal('');
  readonly roleUid = signal('');
  readonly companyUid = signal('');
  readonly branchUid = signal('');
  readonly saving = signal(false);
  readonly formError = signal<string | null>(null);

  // Grant lookup state
  readonly lookupUid = signal('');
  readonly grants = signal<UserRole[]>([]);
  readonly grantsState = signal<'idle' | 'loading' | 'error'>('idle');
  readonly grantsSearched = signal(false);
  readonly revokeError = signal<string | null>(null);

  /** uid of the grant whose Revoke button is in-flight. */
  readonly revokeUid = signal<string | null>(null);

  constructor() {
    this.roleService.list().subscribe({
      next: (rows) => {
        this.roles.set(rows);
        this.rolesState.set('idle');
      },
      error: () => this.rolesState.set('error'),
    });
    this.userService.list().subscribe({
      next: (rows) => this.users.set(rows),
      error: () => undefined,
    });
    this.organisationService.current().subscribe({
      next: (org) => {
        this.companyService.list(org.uid).subscribe({
          next: (list) => {
            this.companies.set(list);
            // Load branches for the first company so the picker is pre-populated
            if (list.length > 0) this.loadBranches(list[0].uid);
            // Pre-load all branches for the branchName() resolver in the grants table.
            list.forEach((c) => {
              this.branchService.list(c.uid).subscribe({
                next: (bs) => this.allBranches.update((prev) => [...prev, ...bs]),
                error: () => undefined,
              });
            });
          },
          error: () => undefined,
        });
      },
      error: () => undefined,
    });
  }

  /** Called when companyUid changes to reload the branch picker. */
  onCompanyUidChange(uid: string): void {
    this.companyUid.set(uid);
    this.branchUid.set('');
    if (uid) this.loadBranches(uid);
  }

  private loadBranches(companyUid: string): void {
    this.branchService.list(companyUid).subscribe({
      next: (list) => this.branches.set(list),
      error: () => undefined,
    });
  }

  grant(): void {
    if (!this.userUid().trim() || !this.roleUid() || !this.companyUid().trim()) {
      this.formError.set('User UID, role, and company UID are required.');
      return;
    }
    this.saving.set(true);
    this.formError.set(null);
    this.userRoleService
      .grant({
        userUid: this.userUid().trim(),
        roleUid: this.roleUid(),
        companyUid: this.companyUid().trim(),
        branchUid: this.branchUid().trim() || undefined,
      })
      .subscribe({
        next: () => {
          this.userUid.set('');
          this.roleUid.set('');
          this.companyUid.set('');
          this.branchUid.set('');
          this.saving.set(false);
          this.alerts.success('Role granted');
          // Re-load grants if a lookup is active
          if (this.grantsSearched()) {
            this.loadGrants();
          }
        },
        error: (err) => {
          this.formError.set(this.messageFrom(err, 'Could not grant the role.'));
          this.saving.set(false);
        },
      });
  }

  searchGrants(): void {
    if (!this.lookupUid().trim()) {
      return;
    }
    this.grantsSearched.set(true);
    this.loadGrants();
  }

  private loadGrants(): void {
    this.grantsState.set('loading');
    this.revokeError.set(null);
    this.userRoleService.listForUser(this.lookupUid().trim()).subscribe({
      next: (rows) => {
        this.grants.set(rows);
        this.grantsState.set('idle');
      },
      error: () => this.grantsState.set('error'),
    });
  }

  revoke(uid: string): void {
    if (this.revokeUid() !== null) {
      return;
    }
    this.revokeError.set(null);
    this.revokeUid.set(uid);
    this.userRoleService.revoke(uid).subscribe({
      next: () => {
        this.revokeUid.set(null);
        this.alerts.success('Role revoked');
        this.loadGrants();
      },
      error: (err) => {
        this.revokeUid.set(null);
        this.revokeError.set(this.messageFrom(err, 'Could not revoke the grant.'));
      },
    });
  }

  private messageFrom(err: unknown, fallback: string): string {
    const errors = (err as { error?: { errors?: string[] } })?.error?.errors;
    return errors?.length ? errors[0] : fallback;
  }
}
