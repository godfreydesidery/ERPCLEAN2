import { Component, HostListener, computed, inject, signal } from '@angular/core';
import { NgClass } from '@angular/common';
import { Router, RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { HealthService } from '../../core/health/health.service';
import { AuthService } from '../../core/auth/auth.service';
import { SessionStore } from '../../core/auth/session.store';

/**
 * A single sidebar navigation entry. {@code available} marks features that are built; not-yet-built
 * areas render as disabled "soon" items so the shell shows where the product is going without
 * pretending those routes exist.
 */
interface NavItem {
  readonly label: string;
  readonly route: string;
  readonly icon: string;
  readonly available: boolean;
}

interface NavGroup {
  readonly label: string;
  readonly items: readonly NavItem[];
}

/**
 * The application shell (theme adopted from the Orbix Engine reference): a fixed white topbar
 * (brand, current-user menu, live API status) and a dark off-canvas sidebar with grouped nav, plus
 * a router outlet for feature pages. The branch selector and permission-gated nav arrive in later
 * slices; for now the nav lists the IAM admin areas (Companies is live; the rest are "soon").
 */
@Component({
  selector: 'app-shell',
  imports: [NgClass, RouterLink, RouterLinkActive, RouterOutlet],
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
  readonly sidebarOpen = signal(false);
  readonly userMenuOpen = signal(false);

  readonly initials = computed(() => {
    const name = this.session.user()?.displayName ?? '';
    const parts = name.trim().split(/\s+/).filter((p) => p.length > 0);
    if (parts.length === 0) {
      return '?';
    }
    const first = parts[0].charAt(0);
    if (parts.length === 1) {
      return first.toUpperCase();
    }
    const last = parts.at(-1) ?? '';
    return (first + last.charAt(0)).toUpperCase();
  });

  readonly nav: readonly NavGroup[] = [
    {
      label: 'Administration',
      items: [
        { label: 'Companies', route: '/admin/companies', icon: 'bi-building', available: true },
        { label: 'Users', route: '/admin/users', icon: 'bi-people', available: false },
        { label: 'Roles', route: '/admin/roles', icon: 'bi-shield-lock', available: false },
        { label: 'Audit', route: '/admin/audit', icon: 'bi-clipboard-data', available: false },
      ],
    },
  ];

  constructor() {
    this.healthService.getHealth().subscribe({
      next: (h) => {
        this.health.set(h);
        this.state.set('ok');
      },
      error: () => this.state.set('error'),
    });
  }

  closeSidebar(): void {
    if (this.sidebarOpen()) {
      this.sidebarOpen.set(false);
    }
  }

  @HostListener('document:keydown.escape')
  onEscape(): void {
    this.closeSidebar();
    this.userMenuOpen.set(false);
  }

  // Close the user menu on any outside click; the menu stops propagation on its own click.
  @HostListener('document:click')
  onDocumentClick(): void {
    if (this.userMenuOpen()) {
      this.userMenuOpen.set(false);
    }
  }

  logout(): void {
    this.auth.logout().subscribe({
      next: () => this.router.navigateByUrl('/login'),
      error: () => this.router.navigateByUrl('/login'),
    });
  }
}
