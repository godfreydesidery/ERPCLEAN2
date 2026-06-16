import { Component, computed, inject } from '@angular/core';
import { RouterLink } from '@angular/router';
import { SessionStore } from '../../../core/auth/session.store';

/**
 * Admin landing — the page every authenticated user is redirected to ({@code /admin} default and the
 * fallback when a permission guard blocks a route).
 *
 * It deliberately does NOT mirror the sidebar menu. Instead:
 *   • Root admin sees the SYSTEM SETUP entry points needed to bootstrap the platform
 *     (company structure → roles → users → audit).
 *   • Everyone else sees a calm welcome (no menu duplication) — personalised shortcuts will live
 *     here later; for now navigation is via the left menu.
 */
interface SetupCard {
  readonly step: number;
  readonly label: string;
  readonly description: string;
  readonly route: string;
  readonly icon: string;
}

@Component({
  selector: 'app-admin-home',
  imports: [RouterLink],
  templateUrl: './admin-home.component.html',
  styleUrl: './admin-home.component.scss',
})
export class AdminHomeComponent {
  protected readonly session = inject(SessionStore);

  readonly displayName = computed(() => this.session.user()?.displayName ?? '');
  readonly isRoot = computed(() => this.session.user()?.isRoot === true);

  /** Root-only system bootstrap / configuration entry points (ordered as a setup flow). */
  readonly setupCards: readonly SetupCard[] = [
    {
      step: 1,
      label: 'Companies & branches',
      description: 'Set up your company structure and the branches that operate under it.',
      route: '/admin/companies',
      icon: 'bi-building',
    },
    {
      step: 2,
      label: 'Roles & permissions',
      description: 'Define roles and choose exactly what each one is allowed to do.',
      route: '/admin/roles',
      icon: 'bi-shield-lock',
    },
    {
      step: 3,
      label: 'Users',
      description: 'Create users, then grant them roles and assign their branches.',
      route: '/admin/users',
      icon: 'bi-people',
    },
    {
      step: 4,
      label: 'Audit log',
      description: 'Review the append-only trail of configuration and access changes.',
      route: '/admin/audit',
      icon: 'bi-clipboard-data',
    },
  ];
}
