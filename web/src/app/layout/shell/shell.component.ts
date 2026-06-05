import { Component, inject, signal } from '@angular/core';
import { Router, RouterLink, RouterOutlet } from '@angular/router';
import { HealthService } from '../../core/health/health.service';
import { AuthService } from '../../core/auth/auth.service';
import { SessionStore } from '../../core/auth/session.store';

/**
 * The application shell: a top bar (brand, nav, current user + logout, live API status) and a
 * router outlet for feature pages. The branch selector lands in Slice 5.
 */
@Component({
  selector: 'app-shell',
  imports: [RouterLink, RouterOutlet],
  templateUrl: './shell.component.html',
  styleUrl: './shell.component.scss',
})
export class ShellComponent {
  private readonly healthService = inject(HealthService);
  private readonly auth = inject(AuthService);
  private readonly router = inject(Router);
  protected readonly session = inject(SessionStore);

  readonly health = signal<{ status: string; service: string } | null>(null);
  readonly state = signal<'loading' | 'ok' | 'error'>('loading');

  constructor() {
    this.healthService.getHealth().subscribe({
      next: (h) => {
        this.health.set(h);
        this.state.set('ok');
      },
      error: () => this.state.set('error'),
    });
  }

  logout(): void {
    this.auth.logout().subscribe({
      next: () => this.router.navigateByUrl('/login'),
      error: () => this.router.navigateByUrl('/login'),
    });
  }
}
