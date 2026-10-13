import { Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router } from '@angular/router';
import { AuthService } from '../../../core/auth/auth.service';
import { SessionStore } from '../../../core/auth/session.store';
import { AlertService } from '../../../core/feedback/alert.service';

/**
 * Self-service password change (ADM-02 / PAR-14). Reached from the user menu, and forced by
 * mustChangePasswordGuard when an administrator set the password (create or reset). The server
 * enforces the password policy, checks the current password (wrong guesses count towards the
 * login lockout) and signs out every other session.
 */
@Component({
  selector: 'app-change-password',
  imports: [FormsModule],
  templateUrl: './change-password.component.html',
})
export class ChangePasswordComponent {
  private readonly auth = inject(AuthService);
  private readonly session = inject(SessionStore);
  private readonly router = inject(Router);
  private readonly alerts = inject(AlertService);

  /** True when the user is here because an administrator set a temporary password. */
  readonly forced = this.session.mustChangePassword;

  readonly currentPassword = signal('');
  readonly newPassword = signal('');
  readonly confirmPassword = signal('');
  readonly saving = signal(false);
  readonly error = signal<string | null>(null);

  submit(): void {
    if (this.saving()) return;
    const current = this.currentPassword();
    const next = this.newPassword();
    if (!current || !next) {
      this.error.set('Enter your current password and a new password.');
      return;
    }
    if (next !== this.confirmPassword()) {
      this.error.set('The new password and its confirmation do not match.');
      return;
    }
    if (next === current) {
      this.error.set('Choose a new password that is different from your current one.');
      return;
    }
    this.saving.set(true);
    this.error.set(null);
    this.auth.changeOwnPassword({ currentPassword: current, newPassword: next }).subscribe({
      next: () => {
        this.saving.set(false);
        this.currentPassword.set('');
        this.newPassword.set('');
        this.confirmPassword.set('');
        this.alerts.success('Password changed', 'Other devices have been signed out.');
        void this.router.navigateByUrl('/admin');
      },
      error: (err: unknown) => {
        const errors = (err as { error?: { errors?: string[] } })?.error?.errors;
        this.error.set(errors?.length ? errors[0] : 'Could not change your password. Please try again.');
        this.saving.set(false);
      },
    });
  }
}
