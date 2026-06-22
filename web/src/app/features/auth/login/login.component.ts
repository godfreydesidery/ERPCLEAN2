import { Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, Router } from '@angular/router';
import { AuthService } from '../../../core/auth/auth.service';

/**
 * Login screen (theme adopted from the Orbix Engine reference). On success, routes to the admin
 * area — or back to the `returnUrl` the user was bounced from (session timeout / protected-route
 * guard), if one was supplied. Shows a single generic error for any auth failure (the backend never
 * discloses which factor failed) and a clear "locked" message when the account is locked.
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
  private readonly route = inject(ActivatedRoute);

  readonly username = signal('');
  readonly password = signal('');
  readonly submitting = signal(false);
  readonly showPassword = signal(false);
  readonly error = signal<string | null>(null);
  readonly year = new Date().getFullYear();

  submit(): void {
    if (!this.username().trim() || !this.password()) {
      this.error.set('Enter your username and password.');
      return;
    }
    this.submitting.set(true);
    this.error.set(null);
    this.auth.login({ username: this.username().trim(), password: this.password() }).subscribe({
      next: () => this.router.navigateByUrl(this.returnTarget()),
      error: (err) => {
        this.error.set(this.messageFrom(err));
        this.submitting.set(false);
      },
    });
  }

  /**
   * The post-login destination: the `returnUrl` query param if present and safe, else the admin
   * dashboard. Only same-origin relative paths are honoured — anything not starting with a single
   * '/' (e.g. a protocol-relative '//evil.com') is rejected to close an open-redirect vector.
   */
  private returnTarget(): string {
    const target = this.route.snapshot.queryParamMap.get('returnUrl');
    return target && target.startsWith('/') && !target.startsWith('//') ? target : '/admin';
  }

  private messageFrom(err: unknown): string {
    const errors = (err as { error?: { errors?: string[] } })?.error?.errors;
    return errors?.length ? errors[0] : 'Sign-in failed. Please try again.';
  }
}
