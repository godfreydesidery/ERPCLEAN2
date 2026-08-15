import { Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { User } from '../models/user.model';
import { UserService } from './user.service';
import { AlertService } from '../../../core/feedback/alert.service';
import { OrganisationService } from '../organisation/organisation.service';

/**
 * Lists all users and provides an inline create form (username, displayName, temp password).
 * Per-row actions: Disable/Enable (toggled by status), Unlock (only when locked), Set password
 * (inline password field per row), Manage branches (link to user-detail).
 * Root users never show a Disable action — the backend refuses it, so we hide it.
 * All four states (loading / empty / error / populated) are handled.
 */
@Component({
  selector: 'app-user-list',
  imports: [FormsModule, RouterLink],
  templateUrl: './user-list.component.html',
  styleUrl: './user-list.component.scss',
})
export class UserListComponent {
  private readonly userService = inject(UserService);
  private readonly alerts = inject(AlertService);
  private readonly organisations = inject(OrganisationService);

  /**
   * The signed-in administrator's own organisation alias, rendered as the fixed `@alias` adornment
   * (P6-2b). Sourced from organisations/current, which since P3-7 returns the CALLER's organisation
   * rather than the lowest-id one — so on a shared instance each admin sees their own suffix.
   *
   * Null on an organisation with no alias: those installations keep bare usernames, and the form
   * then shows neither an adornment nor a preview, exactly as before.
   */
  readonly organisationAlias = signal<string | null>(null);
  readonly usernameSuffix = computed(() =>
    this.organisationAlias() ? '@' + this.organisationAlias() : null,
  );
  /** The name the server will actually store. Empty until something is typed, to avoid a bare '@x'. */
  readonly composedUsername = computed(() => {
    const local = this.newUsername().trim();
    const suffix = this.usernameSuffix();
    return local && suffix ? local + suffix : null;
  });

  readonly users = signal<User[]>([]);
  readonly state = signal<'loading' | 'idle' | 'error'>('loading');
  readonly formError = signal<string | null>(null);

  // Create form
  readonly newUsername = signal('');
  readonly newDisplayName = signal('');
  readonly newPassword = signal('');
  readonly saving = signal(false);

  readonly hasUsers = computed(() => this.users().length > 0);

  /** uid of the user whose Disable/Enable/Unlock button is in-flight. */
  readonly busyUid = signal<string | null>(null);

  /** uid of the user whose set-password form is expanded. */
  readonly pwdExpandedUid = signal<string | null>(null);

  /** Map of uid -> new password value for inline set-password forms. */
  readonly pwdValues = signal<Record<string, string>>({});

  /** uid of the user whose set-password save is in-flight. */
  readonly pwdSavingUid = signal<string | null>(null);

  constructor() {
    this.load();
    // The suffix comes from the signed-in admin's OWN organisation, never from a picker: the tenant
    // half of a username is not something an administrator may choose (P6-2b). A failure here is
    // silent on purpose - the form still works, it simply shows no suffix.
    this.organisations.current().subscribe({
      next: (org) => this.organisationAlias.set(org.alias ?? null),
      error: () => this.organisationAlias.set(null),
    });
  }

  load(): void {
    this.state.set('loading');
    this.userService.list().subscribe({
      next: (rows) => {
        this.users.set(rows);
        this.state.set('idle');
      },
      error: () => this.state.set('error'),
    });
  }

  create(): void {
    if (!this.newUsername().trim() || !this.newDisplayName().trim() || !this.newPassword().trim()) {
      this.formError.set('Username, display name, and password are required.');
      return;
    }
    this.saving.set(true);
    this.formError.set(null);
    this.userService
      .create({
        username: this.newUsername().trim(),
        displayName: this.newDisplayName().trim(),
        password: this.newPassword().trim(),
      })
      .subscribe({
        next: (created) => {
          this.users.update((list) => [...list, created]);
          this.newUsername.set('');
          this.newDisplayName.set('');
          this.newPassword.set('');
          this.saving.set(false);
          this.alerts.success('User created', created.username);
        },
        error: (err) => {
          this.formError.set(this.messageFrom(err));
          this.saving.set(false);
        },
      });
  }

  disable(user: User): void {
    if (this.busyUid() !== null) {
      return;
    }
    this.busyUid.set(user.uid);
    this.userService.disable(user.uid).subscribe({
      next: () => {
        this.users.update((list) =>
          list.map((u) => (u.uid === user.uid ? { ...u, status: 'DISABLED' } : u)),
        );
        this.busyUid.set(null);
        this.alerts.success('User disabled', user.username);
      },
      error: () => this.busyUid.set(null),
    });
  }

  enable(user: User): void {
    if (this.busyUid() !== null) {
      return;
    }
    this.busyUid.set(user.uid);
    this.userService.enable(user.uid).subscribe({
      next: () => {
        this.users.update((list) =>
          list.map((u) => (u.uid === user.uid ? { ...u, status: 'ACTIVE' } : u)),
        );
        this.busyUid.set(null);
        this.alerts.success('User enabled', user.username);
      },
      error: () => this.busyUid.set(null),
    });
  }

  unlock(user: User): void {
    if (this.busyUid() !== null) {
      return;
    }
    this.busyUid.set(user.uid);
    this.userService.unlock(user.uid).subscribe({
      next: () => {
        this.users.update((list) =>
          list.map((u) => (u.uid === user.uid ? { ...u, locked: false } : u)),
        );
        this.busyUid.set(null);
        this.alerts.success('User unlocked', user.username);
      },
      error: () => this.busyUid.set(null),
    });
  }

  togglePwdForm(uid: string): void {
    if (this.pwdExpandedUid() === uid) {
      this.pwdExpandedUid.set(null);
    } else {
      this.pwdExpandedUid.set(uid);
      // Ensure an entry exists so ngModel can bind
      if (!this.pwdValues()[uid]) {
        this.pwdValues.update((m) => ({ ...m, [uid]: '' }));
      }
    }
  }

  setPwdValue(uid: string, value: string): void {
    this.pwdValues.update((m) => ({ ...m, [uid]: value }));
  }

  savePassword(user: User): void {
    const pwd = (this.pwdValues()[user.uid] ?? '').trim();
    if (!pwd) {
      return;
    }
    if (this.pwdSavingUid() !== null) {
      return;
    }
    this.pwdSavingUid.set(user.uid);
    this.userService.setPassword(user.uid, { password: pwd }).subscribe({
      next: () => {
        this.pwdSavingUid.set(null);
        this.pwdExpandedUid.set(null);
        this.pwdValues.update((m) => {
          const next = { ...m };
          delete next[user.uid];
          return next;
        });
        this.alerts.success('Password updated', user.username);
      },
      error: () => this.pwdSavingUid.set(null),
    });
  }

  private messageFrom(err: unknown): string {
    const errors = (err as { error?: { errors?: string[] } })?.error?.errors;
    return errors?.length ? errors[0] : 'Could not save the user.';
  }
}
