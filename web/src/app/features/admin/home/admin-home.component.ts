import { Component, computed, inject } from '@angular/core';
import { RouterLink } from '@angular/router';
import { SessionStore } from '../../../core/auth/session.store';

/**
 * Neutral admin landing — the page EVERY authenticated user is redirected to (the {@code /admin}
 * default, and the fallback when a permission guard blocks a forbidden route). It shows a welcome
 * and a card per admin area the user can actually access (permission-filtered, root sees all), so no
 * one is ever dropped onto a screen they lack permission for. A user with no admin permissions sees
 * a calm "no admin access" message instead of an error.
 */
interface HomeCard {
  readonly label: string;
  readonly description: string;
  readonly route: string;
  readonly icon: string;
  readonly permission: string;
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

  private readonly allCards: readonly HomeCard[] = [
    { label: 'Companies', description: 'View and manage companies and their branches.', route: '/admin/companies', icon: 'bi-building', permission: 'COMPANY.VIEW' },
    { label: 'Users', description: 'Create users, manage status, assign branches and roles.', route: '/admin/users', icon: 'bi-people', permission: 'USER.VIEW' },
    { label: 'Roles', description: 'Define roles and the permissions they grant.', route: '/admin/roles', icon: 'bi-shield-lock', permission: 'ROLE.VIEW' },
    { label: 'Audit', description: 'Review the append-only trail of access changes.', route: '/admin/audit', icon: 'bi-clipboard-data', permission: 'AUDIT.VIEW' },
    { label: 'Customers', description: 'Manage customer accounts, credit terms, and branch associations.', route: '/admin/customers', icon: 'bi-people-fill', permission: 'CUSTOMER.VIEW' },
    { label: 'Suppliers', description: 'Manage supplier accounts and branch associations.', route: '/admin/suppliers', icon: 'bi-truck', permission: 'SUPPLIER.VIEW' },
    { label: 'Sales Agents', description: 'Manage internal and external sales agents.', route: '/admin/agents', icon: 'bi-person-badge', permission: 'AGENT.VIEW' },
  ];

  /** Only the cards the user has permission for (root sees all). Reactive to permission changes. */
  readonly cards = computed(() =>
    this.allCards.filter((c) => this.session.hasPermission(c.permission)),
  );
}
