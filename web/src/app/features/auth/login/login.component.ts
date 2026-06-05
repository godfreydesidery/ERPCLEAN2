import { Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router } from '@angular/router';
import { AuthService } from '../../../core/auth/auth.service';

/**
 * Login screen. On success, routes to the admin area. Shows a single generic error for any auth
 * failure (the backend never discloses which factor failed), and a clear "locked" message when the
 * account is locked.
 */
@Component({
  selector: 'app-login',
  imports: [FormsModule],
  templateUrl: './login.component.html',
  styleUrl: './login.component.scss',
})
export class LoginComponent {
  private readonly auth = inject(AuthService);
  private readonly router = inject(Router);

  readonly username = signal('');
  readonly password = signal('');
  readonly submitting = signal(false);
  readonly error = signal<string | null>(null);

  submit(): void {
    if (!this.username().trim() || !this.password()) {
      this.error.set('Enter your username and password.');
      return;
    }
    this.submitting.set(true);
    this.error.set(null);
    this.auth.login({ username: this.username().trim(), password: this.password() }).subscribe({
      next: () => this.router.navigateByUrl('/admin'),
      error: (err) => {
        this.error.set(this.messageFrom(err));
        this.submitting.set(false);
      },
    });
  }

  private messageFrom(err: unknown): string {
    const errors = (err as { error?: { errors?: string[] } })?.error?.errors;
    return errors?.length ? errors[0] : 'Sign-in failed. Please try again.';
  }
}
