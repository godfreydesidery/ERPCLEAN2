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
        {
          label: 'Branches',
          route: '/admin/branches',
          icon: 'bi-diagram-2',
          available: true,
          permission: 'BRANCH.VIEW',
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
        { label: 'Other Parties', route: '/admin/other-parties', icon: 'bi-person-rolodex', available: true, permission: 'OTHERPARTY.VIEW' },
        { label: 'Routes', route: '/admin/routes', icon: 'bi-signpost', available: true, permission: 'ROUTE.VIEW' },
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
        { label: 'Quotations', route: '/admin/quotations', icon: 'bi-file-earmark-text', available: true, permission: 'SALES.QUOTE.VIEW' },
        { label: 'Sales Orders', route: '/admin/sales-orders', icon: 'bi-bag-check', available: true, permission: 'SALES.ORDER.VIEW' },
        { label: 'Deliveries', route: '/admin/deliveries', icon: 'bi-truck', available: true, permission: 'SALES.DELIVERY.VIEW' },
        { label: 'Invoices', route: '/admin/sales-invoices', icon: 'bi-receipt', available: true, permission: 'SALES.INVOICE.VIEW' },
        { label: 'Sales Returns', route: '/admin/sales-returns', icon: 'bi-arrow-return-left', available: true, permission: 'SALES.RETURN.VIEW' },
        { label: 'Tax Rates', route: '/admin/tax-rates', icon: 'bi-percent', available: true, permission: 'TAXRATE.VIEW' },
        { label: 'Blanket Orders', route: '/admin/blanket-orders', icon: 'bi-file-earmark-ruled', available: true, permission: 'SALES.BLANKET.VIEW' },
        { label: 'Standing Orders', route: '/admin/standing-orders', icon: 'bi-arrow-repeat', available: true, permission: 'SALES.STANDING.VIEW' },
        { label: 'Pricing Rules', route: '/admin/pricing-rules', icon: 'bi-tags-fill', available: true, permission: 'SALES.PRICING.RULE.VIEW' },
      ],
    },
    {
      label: 'Inventory',
      items: [
        { label: 'Stock On-Hand', route: '/admin/stock', icon: 'bi-boxes', available: true, permission: 'STOCK.VIEW' },
        { label: 'Stock Transfers', route: '/admin/stock-transfers', icon: 'bi-arrow-left-right', available: true, permission: 'STOCK.TRANSFER.VIEW' },
        { label: 'Stock Locations', route: '/admin/stock/locations', icon: 'bi-geo-alt', available: true, permission: 'STOCK.LOCATION.VIEW' },
        { label: 'Stock Batches', route: '/admin/stock/batches', icon: 'bi-layers', available: true, permission: 'STOCK.BATCH.VIEW' },
        { label: 'Serial Numbers', route: '/admin/stock/serials', icon: 'bi-upc-scan', available: true, permission: 'STOCK.SERIAL.VIEW' },
        { label: 'Stock Valuation', route: '/admin/stock/valuation', icon: 'bi-clipboard-data', available: true, permission: 'INVENTORY.VALUATION.VIEW' },
        { label: 'Opening Valuation', route: '/admin/stock/valuation/opening', icon: 'bi-pencil-square', available: true, permission: 'INVENTORY.OPENING.SET' },
        { label: 'Stock Counts', route: '/admin/stock-counts', icon: 'bi-clipboard2-check', available: true, permission: 'STOCK.COUNT.VIEW' },
      ],
    },
    {
      label: 'Purchasing',
      items: [
        { label: 'Purchase Orders', route: '/admin/purchase-orders', icon: 'bi-cart', available: true, permission: 'PURCHASE.ORDER.VIEW' },
        { label: 'Goods Receipts', route: '/admin/goods-receipts', icon: 'bi-box-arrow-in-down', available: true, permission: 'PURCHASE.ORDER.VIEW' },
        { label: 'Purchase Requisitions', route: '/admin/purchase-requisitions', icon: 'bi-clipboard-plus', available: true, permission: 'PURCHASE.REQUISITION.VIEW' },
        { label: 'RFQs / Sourcing', route: '/admin/rfqs', icon: 'bi-search', available: true, permission: 'PURCHASE.RFQ.VIEW' },
        { label: 'Purchase Returns', route: '/admin/purchase-returns', icon: 'bi-arrow-return-left', available: true, permission: 'PURCHASE.RETURN.VIEW' },
        { label: 'Landed Costs', route: '/admin/landed-costs', icon: 'bi-box-arrow-in-right', available: true, permission: 'PURCHASE.LANDED_COST.VIEW' },
        { label: 'Purchase Settings', route: '/admin/purchase-settings', icon: 'bi-gear', available: true, permission: 'PURCHASE.SETTINGS.VIEW' },
      ],
    },
    {
      label: 'Accounting',
      items: [
        { label: 'Chart of Accounts', route: '/admin/gl/accounts', icon: 'bi-diagram-3', available: true, permission: 'GL.VIEW' },
        { label: 'Journal Entries', route: '/admin/gl/journals', icon: 'bi-journal-text', available: true, permission: 'GL.VIEW' },
        { label: 'Trial Balance', route: '/admin/gl/trial-balance', icon: 'bi-calculator', available: true, permission: 'GL.VIEW' },
        { label: 'Fiscal Periods', route: '/admin/gl/periods', icon: 'bi-calendar3', available: true, permission: 'GL.VIEW' },
        { label: 'Posting Accounts', route: '/admin/gl/config', icon: 'bi-gear', available: true, permission: 'GL.MANAGE' },
        { label: 'Year-End Close', route: '/admin/gl/year-end', icon: 'bi-calendar-check', available: true, permission: 'GL.YEAR.CLOSE' },
        { label: 'Receivables', route: '/admin/ar/invoices', icon: 'bi-cash-coin', available: true, permission: 'AR.VIEW' },
        { label: 'Record Receipt', route: '/admin/ar/receipts/record', icon: 'bi-receipt', available: true, permission: 'AR.RECEIPT.RECORD' },
        { label: 'Receipts', route: '/admin/ar/receipts', icon: 'bi-cash-coin', available: true, permission: 'AR.VIEW' },
        { label: 'AR Ageing', route: '/admin/ar/ageing', icon: 'bi-bar-chart-steps', available: true, permission: 'AR.STATEMENT.VIEW' },
        { label: 'Customer Statement', route: '/admin/ar/statement', icon: 'bi-file-earmark-text', available: true, permission: 'AR.STATEMENT.VIEW' },
        { label: 'AR Opening Balance', route: '/admin/ar/opening-balance', icon: 'bi-pencil-square', available: true, permission: 'AR.OPENING.SET' },
        { label: 'Payables', route: '/admin/ap/supplier-bills', icon: 'bi-receipt-cutoff', available: true, permission: 'AP.VIEW' },
        { label: 'Enter Bill', route: '/admin/ap/supplier-bills/enter', icon: 'bi-file-earmark-plus', available: true, permission: 'AP.BILL.ENTER' },
        { label: 'Record Payment', route: '/admin/ap/payments/record', icon: 'bi-cash-stack', available: true, permission: 'AP.PAYMENT.RUN' },
        { label: 'Payments', route: '/admin/ap/payments', icon: 'bi-cash-stack', available: true, permission: 'AP.VIEW' },
        { label: 'Supplier Statement', route: '/admin/ap/statement', icon: 'bi-file-earmark-text', available: true, permission: 'AP.VIEW' },
        { label: 'AP Opening Balance', route: '/admin/ap/opening-balance', icon: 'bi-pencil-square', available: true, permission: 'AP.OPENING.SET' },
        { label: 'Cash & Bank Accounts', route: '/admin/cash/accounts', icon: 'bi-wallet2', available: true, permission: 'CASH.VIEW' },
        { label: 'Cash Transfer', route: '/admin/cash/transfers/record', icon: 'bi-arrow-left-right', available: true, permission: 'CASH.TRANSFER' },
        { label: 'Transfers', route: '/admin/cash/transfers', icon: 'bi-arrow-left-right', available: true, permission: 'CASH.VIEW' },
        { label: 'Cash / Bank Entry', route: '/admin/cash/entries/record', icon: 'bi-cash', available: true, permission: 'CASH.ENTRY.RECORD' },
        { label: 'Cheques', route: '/admin/cash/cheques', icon: 'bi-card-checklist', available: true, permission: 'CHEQUE.MANAGE' },
        { label: 'Bank Reconciliation', route: '/admin/cash/reconciliations', icon: 'bi-bank', available: true, permission: 'CASH.RECONCILE' },
        { label: 'Cash Statement', route: '/admin/cash/statement', icon: 'bi-file-earmark-bar-graph', available: true, permission: 'CASH.VIEW' },
        { label: 'VAT Returns', route: '/admin/tax/vat-returns', icon: 'bi-file-earmark-ruled', available: true, permission: 'VAT.VIEW' },
        { label: 'WHT Types', route: '/admin/tax/wht-types', icon: 'bi-percent', available: true, permission: 'WHT.VIEW' },
        { label: 'WHT Register', route: '/admin/tax/wht-register', icon: 'bi-table', available: true, permission: 'WHT.VIEW' },
        { label: 'Income Statement', route: '/admin/reporting/income-statement', icon: 'bi-bar-chart-line', available: true, permission: 'REPORT.PL.VIEW' },
        { label: 'Balance Sheet', route: '/admin/reporting/balance-sheet', icon: 'bi-building-check', available: true, permission: 'REPORT.BS.VIEW' },
        { label: 'Cash-Flow Statement', route: '/admin/reporting/cash-flow', icon: 'bi-cash-stack', available: true, permission: 'REPORT.CASHFLOW.VIEW' },
        { label: 'Account Ledger', route: '/admin/reporting/account-ledger', icon: 'bi-journal-text', available: true, permission: 'REPORT.LEDGER.VIEW' },
      ],
    },
    // ── Approvals ─────────────────────────────────────────────────────────────
    {
      label: 'Approvals',
      items: [
        { label: 'My Inbox', route: '/admin/approvals/inbox', icon: 'bi-inbox', available: true, permission: 'APPROVALS.DECIDE' },
        { label: 'All Requests', route: '/admin/approvals/requests', icon: 'bi-clipboard-check', available: true, permission: 'APPROVALS.REQUEST.VIEW' },
        { label: 'Approval Policies', route: '/admin/approvals/policies', icon: 'bi-shield-check', available: true, permission: 'APPROVALS.POLICY.VIEW' },
      ],
    },
    // ── Documents & PDF ────────────────────────────────────────────────────────
    {
      label: 'Documents & PDF',
      items: [
        { label: 'Generated Documents', route: '/admin/documents', icon: 'bi-file-earmark-pdf', available: true, permission: 'DOCUMENT.VIEW' },
        { label: 'Document Templates', route: '/admin/document-templates', icon: 'bi-layout-text-sidebar', available: true, permission: 'DOCUMENT.TEMPLATE.MANAGE' },
        { label: 'Document Branding', route: '/admin/document-branding', icon: 'bi-palette', available: true, permission: 'DOCUMENT.BRANDING.MANAGE' },
      ],
    },
    // ── Notifications ──────────────────────────────────────────────────────────
    {
      label: 'Notifications',
      items: [
        { label: 'Inbox', route: '/admin/notifications', icon: 'bi-bell', available: true, permission: 'NOTIFICATION.VIEW' },
        { label: 'Preferences', route: '/admin/notification-preferences', icon: 'bi-sliders', available: true, permission: 'NOTIFICATION.PREFERENCE.MANAGE' },
        { label: 'Type Catalogue', route: '/admin/notification-types', icon: 'bi-toggles', available: true, permission: 'NOTIFICATION.ADMIN' },
        { label: 'Delivery Log', route: '/admin/notification-deliveries', icon: 'bi-journal-text', available: true, permission: 'NOTIFICATION.ADMIN' },
      ],
    },
    // ── Costing ──────────────────────────────────────────────────────────────
    {
      label: 'Costing',
      items: [
        {
          label: 'Dimension Types',
          route: '/admin/cost-centre/dimensions',
          icon: 'bi-diagram-3',
          available: true,
          permission: 'COSTING.VIEW',
        },
        {
          label: 'Dimension Values',
          route: '/admin/cost-centre/values',
          icon: 'bi-tags',
          available: true,
          permission: 'COSTING.VIEW',
        },
        {
          label: 'Sliced Trial Balance',
          route: '/admin/cost-centre/report',
          icon: 'bi-bar-chart-steps',
          available: true,
          permission: 'COSTING.VIEW',
        },
      ],
    },
    // ── Finance / Fixed Assets ────────────────────────────────────────────────
    {
      label: 'Finance / Fixed Assets',
      items: [
        { label: 'Asset Categories', route: '/admin/asset-categories', icon: 'bi-folder2-open', available: true, permission: 'FA.CATEGORY.VIEW' },
        { label: 'Fixed Assets', route: '/admin/fixed-assets', icon: 'bi-building-gear', available: true, permission: 'FA.VIEW' },
        { label: 'Register Asset', route: '/admin/fixed-assets/create', icon: 'bi-plus-circle', available: true, permission: 'FA.REGISTER.MANAGE' },
        { label: 'FA Reconciliation', route: '/admin/fixed-assets/reconciliation', icon: 'bi-bar-chart-steps', available: true, permission: 'FA.VIEW' },
        { label: 'Depreciation Runs', route: '/admin/depreciation-runs', icon: 'bi-calendar3', available: true, permission: 'FA.VIEW' },
        { label: 'Run Depreciation', route: '/admin/depreciation-runs/post', icon: 'bi-play-circle', available: true, permission: 'FA.DEPRECIATE' },
      ],
    },
    // ── CRM ──────────────────────────────────────────────────────────────────
    {
      label: 'CRM',
      items: [
        { label: 'Leads', route: '/admin/crm/leads', icon: 'bi-person-lines-fill', available: true, permission: 'CRM.LEAD.VIEW' },
        { label: 'Opportunities', route: '/admin/crm/opportunities', icon: 'bi-graph-up-arrow', available: true, permission: 'CRM.OPPORTUNITY.VIEW' },
        { label: 'Pipeline Dashboard', route: '/admin/crm/pipeline', icon: 'bi-bar-chart-line', available: true, permission: 'CRM.PIPELINE.VIEW' },
        { label: 'Pipeline Stages', route: '/admin/crm/settings/pipeline-stages', icon: 'bi-kanban', available: true, permission: 'CRM.STAGE.MANAGE' },
        { label: 'CRM Activities', route: '/admin/crm/activities', icon: 'bi-calendar2-check', available: true, permission: 'CRM.ACTIVITY.VIEW' },
      ],
    },
    // ── HR & Payroll ─────────────────────────────────────────────────────────────
    {
      label: 'HR & Payroll',
      items: [
        { label: 'Employees', route: '/admin/hr/employees', icon: 'bi-people', available: true, permission: 'HR.EMPLOYEE.VIEW' },
        { label: 'Departments', route: '/admin/hr/departments', icon: 'bi-diagram-3', available: true, permission: 'HR.EMPLOYEE.VIEW' },
        { label: 'Employee Contracts', route: '/admin/hr/contracts', icon: 'bi-file-earmark-person', available: true, permission: 'HR.EMPLOYEE.VIEW' },
        { label: 'Pay Components', route: '/admin/hr/pay-components', icon: 'bi-sliders', available: true, permission: 'HR.PAYCOMPONENT.MANAGE' },
        { label: 'Payroll Runs', route: '/admin/hr/payroll-runs', icon: 'bi-cash-stack', available: true, permission: 'HR.PAYROLL.VIEW' },
        { label: 'Leave Requests', route: '/admin/hr/leave-requests', icon: 'bi-calendar-check', available: true, permission: 'HR.LEAVE.VIEW' },
        { label: 'Employee Loans', route: '/admin/hr/loans', icon: 'bi-bank', available: true, permission: 'HR.LOAN.MANAGE' },
        { label: 'Statutory Setup', route: '/admin/hr/statutory', icon: 'bi-shield-check', available: true, permission: 'HR.STATUTORY.MANAGE' },
      ],
    },
    // ── Projects ──────────────────────────────────────────────────────────────
    {
      label: 'Projects',
      items: [
        { label: 'Projects', route: '/admin/projects', icon: 'bi-kanban', available: true, permission: 'PROJECTS.PROJECT.VIEW' },
        { label: 'WIP Report', route: '/admin/projects/wip-report', icon: 'bi-graph-up-arrow', available: true, permission: 'PROJECTS.COSTING.VIEW' },
      ],
    },
    // ── Budgeting & Management Accounting ────────────────────────────────────
    {
      label: 'Budgeting',
      items: [
        { label: 'Budgets', route: '/admin/budgets', icon: 'bi-calculator', available: true, permission: 'BUDGETING.BUDGET.VIEW' },
        { label: 'Budget Variance Report', route: '/admin/budgeting/variance', icon: 'bi-bar-chart', available: true, permission: 'BUDGETING.REPORT.VIEW' },
        { label: 'Departmental Actuals', route: '/admin/budgeting/departmental-actuals', icon: 'bi-table', available: true, permission: 'BUDGETING.REPORT.VIEW' },
      ],
    },
    // ── Manufacturing ─────────────────────────────────────────────────────────
    {
      label: 'Manufacturing',
      items: [
        { label: 'Work Orders', route: '/admin/work-orders', icon: 'bi-gear-wide-connected', available: true, permission: 'MANUFACTURING.VIEW' },
        { label: 'WIP Reconciliation', route: '/admin/manufacturing/wip-reconciliation', icon: 'bi-bank', available: true, permission: 'MANUFACTURING.VIEW' },
        { label: 'Bills of Materials', route: '/admin/boms', icon: 'bi-diagram-3', available: true, permission: 'BOM.VIEW' },
      ],
    },
    // ── FX / Currency ─────────────────────────────────────────────────────────
    {
      label: 'FX / Currency',
      items: [
        { label: 'Exchange Rates', route: '/admin/fx/rates', icon: 'bi-currency-exchange', available: true, permission: 'CURRENCY.VIEW' },
        { label: 'Revaluation Runs', route: '/admin/fx/revaluation-runs', icon: 'bi-arrow-repeat', available: true, permission: 'FX.EXPOSURE.VIEW' },
      ],
    },
    // ── Analytics ─────────────────────────────────────────────────────────────
    {
      label: 'Analytics',
      items: [
        { label: 'Dashboard', route: '/admin/dashboard', icon: 'bi-speedometer2', available: true, permission: 'BI.VIEW' },
      ],
    },
    // ── Point of Sale ─────────────────────────────────────────────────────────
    {
      label: 'Point of Sale',
      items: [
        { label: 'Point of Sale', route: '/admin/pos/sell', icon: 'bi-cart-check', available: true, permission: 'POS.SALE.CREATE' },
        { label: 'POS Sessions', route: '/admin/pos/sessions', icon: 'bi-shop', available: true, permission: 'POS.SESSION.VIEW' },
        { label: 'POS Tills', route: '/admin/pos/tills', icon: 'bi-safe2', available: true, permission: 'POS.TILL.VIEW' },
      ],
    },
  ];

  /**
   * Nav groups with permission-filtered items. Reactive: recomputes when permissions change.
   * Groups whose items are all filtered out are dropped entirely, so a user never sees a group
   * header with no items under it (UI/UX: no dangling empty groups).
   */
  readonly nav = computed<readonly NavGroup[]>(() =>
    this.allNav
      .map((group) => ({
        ...group,
        items: group.items.filter(
          (item) => !item.permission || this.session.hasPermission(item.permission),
        ),
      }))
      .filter((group) => group.items.length > 0),
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
