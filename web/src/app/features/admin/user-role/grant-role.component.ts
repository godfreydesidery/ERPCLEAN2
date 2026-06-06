import { Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Role } from '../models/role.model';
import { UserRole } from '../models/user-role.model';
import { RoleService } from '../role/role.service';
import { UserRoleService } from './user-role.service';
import { AlertService } from '../../../core/feedback/alert.service';

/**
 * Minimal grant-role screen for v1: a form to assign a role to a user (by uid) for a given
 * company + optional branch, and a list of existing grants with a Revoke action. All four states
 * are handled on both the grant list and the grant form.
 */
@Component({
  selector: 'app-grant-role',
  imports: [FormsModule],
  templateUrl: './grant-role.component.html',
  styleUrl: './grant-role.component.scss',
})
export class GrantRoleComponent {
  private readonly userRoleService = inject(UserRoleService);
  private readonly roleService = inject(RoleService);
  private readonly alerts = inject(AlertService);

  // Available roles for the dropdown
  readonly roles = signal<Role[]>([]);
  readonly rolesState = signal<'loading' | 'idle' | 'error'>('loading');

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
