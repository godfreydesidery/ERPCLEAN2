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
 *   • Everyone else sees a permission-gated LAUNCHPAD — each card is only shown when the
 *     session holds the exact permission that the target route guard requires.
 *     A user who sees a card is guaranteed to be able to open the screen (no silent redirect).
 *     If no cards are visible, a calm fallback placeholder is shown.
 */
interface SetupCard {
  readonly step: number;
  readonly label: string;
  readonly description: string;
  readonly route: string;
  readonly icon: string;
}

interface LaunchCard {
  readonly label: string;
  readonly description: string;
  readonly route: string;
  readonly icon: string;
  /** The requirePermission() code on the target route in admin.routes.ts. */
  readonly permCode: string;
}

/**
 * Curated quick-launch destinations for operational users.
 * Each permCode MUST match the requirePermission() guard on the target route in admin.routes.ts.
 * Dashboard is first — the headline for GMs and senior staff.
 */
const ALL_LAUNCH_CARDS: readonly LaunchCard[] = [
  // ── Executive / BI ────────────────────────────────────────────────────────
  {
    label: 'Dashboard',
    description: 'Finance KPIs — Revenue, OpEx, Net Profit, Working Capital at a glance.',
    route: '/admin/dashboard',
    icon: 'bi-speedometer2',
    permCode: 'BI.VIEW',
  },
  // ── Sales ─────────────────────────────────────────────────────────────────
  {
    label: 'Sales orders',
    description: 'View and manage customer orders across the O2C flow.',
    route: '/admin/sales-orders',
    icon: 'bi-cart-check',
    permCode: 'SALES.ORDER.VIEW',
  },
  {
    label: 'POS sell',
    description: 'Open the point-of-sale terminal to ring up a sale.',
    route: '/admin/pos/sell',
    icon: 'bi-upc-scan',
    permCode: 'POS.SALE.CREATE',
  },
  {
    label: 'Customers',
    description: 'Browse and edit customer records and contact details.',
    route: '/admin/customers',
    icon: 'bi-person-lines-fill',
    permCode: 'CUSTOMER.VIEW',
  },
  // ── Purchasing ────────────────────────────────────────────────────────────
  {
    label: 'Purchase orders',
    description: 'Issue and track purchase orders sent to suppliers.',
    route: '/admin/purchase-orders',
    icon: 'bi-bag-check',
    permCode: 'PURCHASE.ORDER.VIEW',
  },
  {
    label: 'Goods receipts',
    description: 'Record stock received against purchase orders.',
    route: '/admin/goods-receipts',
    icon: 'bi-box-arrow-in-down',
    permCode: 'PURCHASE.GOODS_RECEIPT.VIEW',
  },
  {
    label: 'Suppliers',
    description: 'Manage supplier master records and contact details.',
    route: '/admin/suppliers',
    icon: 'bi-truck',
    permCode: 'SUPPLIER.VIEW',
  },
  // ── Stock ─────────────────────────────────────────────────────────────────
  {
    label: 'Stock on-hand',
    description: 'Check current inventory levels across locations.',
    route: '/admin/stock',
    icon: 'bi-boxes',
    permCode: 'STOCK.VIEW',
  },
  {
    label: 'Stock count',
    description: 'Run physical / cycle counts to reconcile inventory.',
    route: '/admin/stock-counts',
    icon: 'bi-clipboard2-check',
    permCode: 'STOCK.COUNT.VIEW',
  },
  {
    label: 'Stock transfer',
    description: 'Move stock between locations or branches.',
    route: '/admin/stock-transfers',
    icon: 'bi-arrow-left-right',
    permCode: 'STOCK.TRANSFER.VIEW',
  },
  // ── Finance ───────────────────────────────────────────────────────────────
  {
    label: 'Record receipt',
    description: 'Post a customer payment against an AR invoice.',
    route: '/admin/ar/receipts/record',
    icon: 'bi-cash-coin',
    permCode: 'AR.RECEIPT.RECORD',
  },
  {
    label: 'Record payment',
    description: 'Post a supplier payment against an AP bill.',
    route: '/admin/ap/payments/record',
    icon: 'bi-send-check',
    permCode: 'AP.PAYMENT.RUN',
  },
  {
    label: 'Enter supplier bill',
    description: 'Capture a supplier invoice for accounts payable.',
    route: '/admin/ap/supplier-bills/enter',
    icon: 'bi-receipt',
    permCode: 'AP.BILL.ENTER',
  },
  {
    label: 'Post journal',
    description: 'Enter a manual General Ledger journal entry.',
    route: '/admin/gl/journals/post',
    icon: 'bi-journal-bookmark',
    permCode: 'GL.POST',
  },
  // ── Manufacturing ─────────────────────────────────────────────────────────
  {
    label: 'Work orders',
    description: 'Manage manufacturing work orders and production runs.',
    route: '/admin/work-orders',
    icon: 'bi-tools',
    permCode: 'MANUFACTURING.VIEW',
  },
];

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

  /**
   * Cards visible to the current non-root user — filtered to only those whose
   * permCode the session already holds, so every rendered card is navigable.
   */
  readonly launchCards = computed<readonly LaunchCard[]>(() =>
    ALL_LAUNCH_CARDS.filter((c) => this.session.hasPermission(c.permCode)),
  );

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
