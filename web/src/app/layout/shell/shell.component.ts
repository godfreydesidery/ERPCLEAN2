import { Component, HostListener, computed, inject, signal } from '@angular/core';
import { NgClass } from '@angular/common';
import { Router, RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { HealthService } from '../../core/health/health.service';
import { AuthService } from '../../core/auth/auth.service';
import { SessionStore } from '../../core/auth/session.store';
import { LoadingService } from '../../core/feedback/loading.service';
import { ToastContainerComponent } from '../../core/feedback/toast-container.component';
import { AlertHostComponent } from '../../core/feedback/alert-host.component';
import { UserBranch } from '../../features/admin/models/user-branch.model';

/**
 * A single sidebar navigation entry.
 * - `available`: false renders the item as a "soon" badge (route does not exist yet).
 * - `permission`: when set, the item is hidden from users who lack that permission (root sees all).
 *   Items without a `permission` key are always shown to authenticated users.
 */
interface NavItem {
  readonly label: string;
  readonly route: string;
  readonly icon: string;
  readonly available: boolean;
  readonly permission?: string;
}

interface NavGroup {
  readonly label: string;
  readonly items: readonly NavItem[];
}

/**
 * The application shell (theme adopted from the Orbix Engine reference): a fixed white topbar
 * (brand, current-user menu, live API status) and a dark off-canvas sidebar with grouped nav, plus
 * a router outlet for feature pages. On construction the shell calls /auth/me to re-hydrate the
 * effective permission codes after a page refresh (the login flow already populates them, but they
 * would be lost without this call on reload).
 */
@Component({
  selector: 'app-shell',
  imports: [
    NgClass,
    RouterLink,
    RouterLinkActive,
    RouterOutlet,
    ToastContainerComponent,
    AlertHostComponent,
  ],
  templateUrl: './shell.component.html',
  styleUrl: './shell.component.scss',
})
export class ShellComponent {
  private readonly healthService = inject(HealthService);
  private readonly auth = inject(AuthService);
  private readonly router = inject(Router);
  protected readonly session = inject(SessionStore);
  protected readonly loading = inject(LoadingService);

  readonly health = signal<{ status: string; service: string } | null>(null);
  readonly state = signal<'loading' | 'ok' | 'error'>('loading');
  readonly sidebarOpen = signal(false);
  readonly userMenuOpen = signal(false);
  readonly branchMenuOpen = signal(false);
  readonly branches = signal<UserBranch[]>([]);
  readonly switching = signal(false);

  /**
   * The branch from the loaded list whose branchUid matches the session's activeBranchUid.
   * Falls back to the default branch, then to the first in the list. Null until branches load.
   */
  readonly activeBranch = computed<UserBranch | null>(() => {
    const list = this.branches();
    if (list.length === 0) return null;
    const activeUid = this.session.activeBranchUid();
    if (activeUid) {
      const match = list.find((b) => b.branchUid === activeUid);
      if (match) return match;
    }
    return list.find((b) => b.isDefault) ?? list[0];
  });

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

  private readonly allNav: readonly NavGroup[] = [
    {
      label: 'Administration',
      items: [
        {
          label: 'Companies',
          route: '/admin/companies',
          icon: 'bi-building',
          available: true,
          permission: 'COMPANY.VIEW',
        },
        { label: 'Users', route: '/admin/users', icon: 'bi-people', available: true, permission: 'USER.VIEW' },
        {
          label: 'Roles',
          route: '/admin/roles',
          icon: 'bi-shield-lock',
          available: true,
          permission: 'ROLE.VIEW',
        },
        { label: 'Audit', route: '/admin/audit', icon: 'bi-clipboard-data', available: true, permission: 'AUDIT.VIEW' },
      ],
    },
    {
      label: 'Parties',
      items: [
        { label: 'Customers', route: '/admin/customers', icon: 'bi-people-fill', available: true, permission: 'CUSTOMER.VIEW' },
        { label: 'Suppliers', route: '/admin/suppliers', icon: 'bi-truck', available: true, permission: 'SUPPLIER.VIEW' },
        { label: 'Sales Agents', route: '/admin/agents', icon: 'bi-person-badge', available: true, permission: 'AGENT.VIEW' },
      ],
    },
    {
      label: 'Products',
      items: [
        { label: 'Products', route: '/admin/products', icon: 'bi-box-seam', available: true, permission: 'PRODUCT.VIEW' },
        { label: 'Price Lists', route: '/admin/price-lists', icon: 'bi-tags', available: true, permission: 'PRICELIST.VIEW' },
        { label: 'Units of Measure', route: '/admin/units', icon: 'bi-rulers', available: true, permission: 'UOM.VIEW' },
      ],
    },
    {
      label: 'Sales',
      items: [
        { label: 'Invoices', route: '/admin/sales-invoices', icon: 'bi-receipt', available: true, permission: 'SALES.INVOICE.VIEW' },
        { label: 'Tax Rates', route: '/admin/tax-rates', icon: 'bi-percent', available: true, permission: 'TAXRATE.VIEW' },
      ],
    },
    {
      label: 'Inventory',
      items: [
        { label: 'Stock On-Hand', route: '/admin/stock', icon: 'bi-boxes', available: true, permission: 'STOCK.VIEW' },
      ],
    },
    {
      label: 'Purchasing',
      items: [
        { label: 'Purchase Orders', route: '/admin/purchase-orders', icon: 'bi-cart', available: true, permission: 'PURCHASE.ORDER.VIEW' },
        { label: 'Goods Receipts', route: '/admin/goods-receipts', icon: 'bi-box-arrow-in-down', available: true, permission: 'PURCHASE.ORDER.VIEW' },
      ],
    },
  ];

  /** Nav groups with permission-filtered items. Reactive: recomputes when permissions change. */
  readonly nav = computed<readonly NavGroup[]>(() =>
    this.allNav.map((group) => ({
      ...group,
      items: group.items.filter(
        (item) => !item.permission || this.session.hasPermission(item.permission),
      ),
    })),
  );

  constructor() {
    this.healthService.getHealth().subscribe({
      next: (h) => {
        this.health.set(h);
        this.state.set('ok');
      },
      error: () => this.state.set('error'),
    });

    // Re-hydrate effective permissions after a page refresh. Failure is non-fatal — the user is
    // still authenticated; they just get a conservative (empty) permission set until next login.
    if (this.session.isAuthenticated()) {
      this.auth.me().subscribe({ error: () => undefined });
      this.auth.myBranches().subscribe({
        next: (list) => this.branches.set(list),
        error: () => this.branches.set([]),
      });
    }
  }

  onBranchPick(branchUid: string): void {
    const active = this.activeBranch();
    if (active?.branchUid === branchUid) {
      this.branchMenuOpen.set(false);
      return;
    }
    this.switching.set(true);
    this.branchMenuOpen.set(false);
    this.auth.switchBranch(branchUid).subscribe({
      next: () => this.switching.set(false),
      error: () => this.switching.set(false),
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
    this.branchMenuOpen.set(false);
  }

  // Close any open dropdown on an outside click; each menu stops propagation on its own click.
  @HostListener('document:click')
  onDocumentClick(): void {
    if (this.userMenuOpen()) this.userMenuOpen.set(false);
    if (this.branchMenuOpen()) this.branchMenuOpen.set(false);
  }

  logout(): void {
    this.auth.logout().subscribe({
      next: () => this.router.navigateByUrl('/login'),
      error: () => this.router.navigateByUrl('/login'),
    });
  }
}
