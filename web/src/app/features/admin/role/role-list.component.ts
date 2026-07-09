import { Component, HostListener, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { Role } from '../models/role.model';
import { RoleService } from './role.service';
import { AlertService } from '../../../core/feedback/alert.service';
import { SessionStore } from '../../../core/auth/session.store';

/**
 * Lists all roles. ROLE.VIEW is sufficient to reach this screen.
 * The inline create form and per-row Edit link are shown only when the caller also holds
 * ROLE.ADMIN (catalogue mutations). A view-only role auditor sees the read-only list.
 */
@Component({
  selector: 'app-role-list',
  imports: [FormsModule, RouterLink],
  templateUrl: './role-list.component.html',
  styleUrl: './role-list.component.scss',
})
export class RoleListComponent {
  private readonly roleService = inject(RoleService);
  private readonly alerts = inject(AlertService);
  protected readonly session = inject(SessionStore);

  readonly roles = signal<Role[]>([]);
  readonly state = signal<'loading' | 'idle' | 'error'>('loading');
  readonly formError = signal<string | null>(null);

  // Create form
  readonly code = signal('');
  readonly name = signal('');
  readonly description = signal('');
  readonly saving = signal(false);

  readonly hasRoles = computed(() => this.roles().length > 0);
  /** True when the caller may create, edit, or archive roles (ROLE.ADMIN). */
  readonly canAdmin = computed(() => this.session.hasPermission('ROLE.ADMIN'));

  /**
   * The role whose permissions are shown in the read-only popup, or null when closed.
   * ROLE.VIEW is enough — the permission codes already ride along on each list row, so no extra
   * fetch (and no PERMISSION.VIEW) is needed to inspect what a role grants.
   */
  readonly permissionsRole = signal<Role | null>(null);

  /** The selected role's permission codes grouped by module (the code's first dot-segment). */
  readonly permissionGroups = computed<{ module: string; codes: string[] }[]>(() => {
    const role = this.permissionsRole();
    if (!role) {
      return [];
    }
    const groups = new Map<string, string[]>();
    for (const code of [...role.permissionCodes].sort((a, b) => a.localeCompare(b))) {
      const module = code.split('.')[0] || 'OTHER';
      const list = groups.get(module) ?? [];
      list.push(code);
      groups.set(module, list);
    }
    return [...groups.entries()]
      .sort((a, b) => a[0].localeCompare(b[0]))
      .map(([module, codes]) => ({ module, codes }));
  });

  constructor() {
    this.load();
  }

  openPermissions(role: Role): void {
    this.permissionsRole.set(role);
  }

  closePermissions(): void {
    this.permissionsRole.set(null);
  }

  /** Dismiss the permissions popup on Escape (reliable regardless of where focus sits). */
  @HostListener('document:keydown.escape')
  onEscape(): void {
    if (this.permissionsRole()) {
      this.closePermissions();
    }
  }

  load(): void {
    this.state.set('loading');
    this.roleService.list().subscribe({
      next: (rows) => {
        this.roles.set(rows);
        this.state.set('idle');
      },
      error: () => this.state.set('error'),
    });
  }

  create(): void {
    if (!this.code().trim() || !this.name().trim()) {
      this.formError.set('Code and name are required.');
      return;
    }
    this.saving.set(true);
    this.formError.set(null);
    this.roleService
      .create({
        code: this.code().trim(),
        name: this.name().trim(),
        description: this.description().trim() || undefined,
      })
      .subscribe({
        next: (created) => {
          this.roles.update((list) => [...list, created]);
          this.code.set('');
          this.name.set('');
          this.description.set('');
          this.saving.set(false);
          this.alerts.success('Role created');
        },
        error: (err) => {
          this.formError.set(this.messageFrom(err));
          this.saving.set(false);
        },
      });
  }

  private messageFrom(err: unknown): string {
    const errors = (err as { error?: { errors?: string[] } })?.error?.errors;
    return errors?.length ? errors[0] : 'Could not save the role.';
  }
}
