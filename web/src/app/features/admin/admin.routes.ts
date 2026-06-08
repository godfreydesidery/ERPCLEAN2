import { Routes } from '@angular/router';
import { requirePermission } from '../../core/auth/permission.guard';

/**
 * Admin (IAM) feature routes, lazy-loaded from app.routes.ts.
 * Slice 1: companies + branches. Slice 3: roles + role-grants.
 * Each feature route is guarded by its view permission — a user lacking it is redirected to the
 * neutral admin home rather than landing on a screen they can't use. The default ('') goes to home,
 * NOT companies, so no user is dropped onto a permissioned screen by the redirect.
 * Path params (`:companyUid`, `:uid`) are bound to required component inputs via withComponentInputBinding.
 */
export const ADMIN_ROUTES: Routes = [
  {
    path: 'home',
    loadComponent: () =>
      import('./home/admin-home.component').then((m) => m.AdminHomeComponent),
  },
  {
    path: 'companies',
    canActivate: [requirePermission('COMPANY.VIEW')],
    loadComponent: () =>
      import('./company/company-list.component').then((m) => m.CompanyListComponent),
  },
  {
    path: 'companies/:companyUid/branches',
    canActivate: [requirePermission('BRANCH.VIEW')],
    loadComponent: () =>
      import('./branch/branch-list.component').then((m) => m.BranchListComponent),
  },
  {
    path: 'roles',
    canActivate: [requirePermission('ROLE.VIEW')],
    loadComponent: () =>
      import('./role/role-list.component').then((m) => m.RoleListComponent),
  },
  {
    path: 'roles/:uid',
    canActivate: [requirePermission('ROLE.MANAGE')],
    loadComponent: () =>
      import('./role/role-edit.component').then((m) => m.RoleEditComponent),
  },
  {
    path: 'role-grants',
    canActivate: [requirePermission('ROLE.MANAGE')],
    loadComponent: () =>
      import('./user-role/grant-role.component').then((m) => m.GrantRoleComponent),
  },
  {
    path: 'users',
    canActivate: [requirePermission('USER.VIEW')],
    loadComponent: () =>
      import('./user/user-list.component').then((m) => m.UserListComponent),
  },
  {
    path: 'users/:uid',
    canActivate: [requirePermission('USER.VIEW')],
    loadComponent: () =>
      import('./user/user-detail.component').then((m) => m.UserDetailComponent),
  },
  {
    path: 'audit',
    canActivate: [requirePermission('AUDIT.VIEW')],
    loadComponent: () =>
      import('./audit/audit-list.component').then((m) => m.AuditListComponent),
  },
  // ── Parties ───────────────────────────────────────────────────────────────
  {
    path: 'customers',
    canActivate: [requirePermission('CUSTOMER.VIEW')],
    loadComponent: () =>
      import('./parties/customer-list.component').then((m) => m.CustomerListComponent),
  },
  {
    path: 'customers/uid/:uid',
    canActivate: [requirePermission('CUSTOMER.VIEW')],
    loadComponent: () =>
      import('./parties/customer-detail.component').then((m) => m.CustomerDetailComponent),
  },
  {
    path: 'suppliers',
    canActivate: [requirePermission('SUPPLIER.VIEW')],
    loadComponent: () =>
      import('./parties/supplier-list.component').then((m) => m.SupplierListComponent),
  },
  {
    path: 'suppliers/uid/:uid',
    canActivate: [requirePermission('SUPPLIER.VIEW')],
    loadComponent: () =>
      import('./parties/supplier-detail.component').then((m) => m.SupplierDetailComponent),
  },
  {
    path: 'agents',
    canActivate: [requirePermission('AGENT.VIEW')],
    loadComponent: () =>
      import('./parties/agent-list.component').then((m) => m.AgentListComponent),
  },
  {
    path: 'agents/uid/:uid',
    canActivate: [requirePermission('AGENT.VIEW')],
    loadComponent: () =>
      import('./parties/agent-detail.component').then((m) => m.AgentDetailComponent),
  },
  // ── Products ──────────────────────────────────────────────────────────────
  {
    path: 'products',
    canActivate: [requirePermission('PRODUCT.VIEW')],
    loadComponent: () =>
      import('./products/product-list.component').then((m) => m.ProductListComponent),
  },
  {
    path: 'products/uid/:uid',
    canActivate: [requirePermission('PRODUCT.VIEW')],
    loadComponent: () =>
      import('./products/product-detail.component').then((m) => m.ProductDetailComponent),
  },
  {
    path: 'price-lists',
    canActivate: [requirePermission('PRICELIST.VIEW')],
    loadComponent: () =>
      import('./products/price-list-list.component').then((m) => m.PriceListListComponent),
  },
  {
    path: 'units',
    canActivate: [requirePermission('UOM.VIEW')],
    loadComponent: () =>
      import('./products/units-of-measure-list.component').then((m) => m.UnitsOfMeasureListComponent),
  },
  // ── Routes ───────────────────────────────────────────────────────────────
  {
    path: 'routes',
    canActivate: [requirePermission('ROUTE.VIEW')],
    loadComponent: () =>
      import('./routes/route-list.component').then((m) => m.RouteListComponent),
  },
  {
    path: 'routes/uid/:uid',
    canActivate: [requirePermission('ROUTE.VIEW')],
    loadComponent: () =>
      import('./routes/route-detail.component').then((m) => m.RouteDetailComponent),
  },
  // ── Sales ─────────────────────────────────────────────────────────────────
  {
    path: 'sales-invoices',
    canActivate: [requirePermission('SALES.INVOICE.VIEW')],
    loadComponent: () =>
      import('./sales/sales-invoice-list.component').then((m) => m.SalesInvoiceListComponent),
  },
  {
    path: 'sales-invoices/uid/:uid',
    canActivate: [requirePermission('SALES.INVOICE.VIEW')],
    loadComponent: () =>
      import('./sales/sales-invoice-detail.component').then((m) => m.SalesInvoiceDetailComponent),
  },
  {
    path: 'tax-rates',
    canActivate: [requirePermission('TAXRATE.VIEW')],
    loadComponent: () =>
      import('./sales/tax-rate-list.component').then((m) => m.TaxRateListComponent),
  },
  // ── Stock ─────────────────────────────────────────────────────────────────
  {
    path: 'stock',
    canActivate: [requirePermission('STOCK.VIEW')],
    loadComponent: () =>
      import('./stock/stock-list.component').then((m) => m.StockListComponent),
  },
  // ── Purchases ─────────────────────────────────────────────────────────────
  {
    path: 'purchase-orders',
    canActivate: [requirePermission('PURCHASE.ORDER.VIEW')],
    loadComponent: () =>
      import('./purchases/purchase-order-list.component').then((m) => m.PurchaseOrderListComponent),
  },
  {
    path: 'purchase-orders/uid/:uid',
    canActivate: [requirePermission('PURCHASE.ORDER.VIEW')],
    loadComponent: () =>
      import('./purchases/purchase-order-detail.component').then((m) => m.PurchaseOrderDetailComponent),
  },
  {
    path: 'goods-receipts',
    canActivate: [requirePermission('PURCHASE.ORDER.VIEW')],
    loadComponent: () =>
      import('./purchases/goods-receipt-list.component').then((m) => m.GoodsReceiptListComponent),
  },
  {
    path: 'goods-receipts/create',
    canActivate: [requirePermission('PURCHASE.RECEIVE')],
    loadComponent: () =>
      import('./purchases/goods-receipt-create.component').then((m) => m.GoodsReceiptCreateComponent),
  },
  {
    path: 'goods-receipts/uid/:uid',
    canActivate: [requirePermission('PURCHASE.ORDER.VIEW')],
    loadComponent: () =>
      import('./purchases/goods-receipt-detail.component').then((m) => m.GoodsReceiptDetailComponent),
  },
  // ── General Ledger (Accounting) ───────────────────────────────────────────
  {
    path: 'gl/accounts',
    canActivate: [requirePermission('GL.VIEW')],
    loadComponent: () =>
      import('./gl/chart-of-accounts-list.component').then((m) => m.ChartOfAccountsListComponent),
  },
  {
    path: 'gl/journals',
    canActivate: [requirePermission('GL.VIEW')],
    loadComponent: () =>
      import('./gl/journal-entry-list.component').then((m) => m.JournalEntryListComponent),
  },
  {
    path: 'gl/journals/post',
    canActivate: [requirePermission('GL.POST')],
    loadComponent: () =>
      import('./gl/post-journal.component').then((m) => m.PostJournalComponent),
  },
  {
    path: 'gl/journals/uid/:uid',
    canActivate: [requirePermission('GL.VIEW')],
    loadComponent: () =>
      import('./gl/journal-entry-detail.component').then((m) => m.JournalEntryDetailComponent),
  },
  {
    path: 'gl/trial-balance',
    canActivate: [requirePermission('GL.VIEW')],
    loadComponent: () =>
      import('./gl/trial-balance.component').then((m) => m.TrialBalanceComponent),
  },
  {
    path: 'gl/periods',
    canActivate: [requirePermission('GL.VIEW')],
    loadComponent: () =>
      import('./gl/fiscal-periods.component').then((m) => m.FiscalPeriodsComponent),
  },
  {
    path: 'gl/config',
    canActivate: [requirePermission('GL.MANAGE')],
    loadComponent: () =>
      import('./gl/gl-config.component').then((m) => m.GlConfigComponent),
  },
  { path: '', redirectTo: 'home', pathMatch: 'full' },
];
