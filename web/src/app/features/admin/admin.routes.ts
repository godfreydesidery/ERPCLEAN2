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
    // Standalone branch management: pick a company you can act in (scoped picker), then manage its
    // branches. Reachable with only BRANCH.VIEW — no COMPANY.VIEW needed (unlike the nested route
    // below, whose only entry point is the COMPANY.VIEW-gated Companies screen).
    path: 'branches',
    canActivate: [requirePermission('BRANCH.VIEW')],
    loadComponent: () =>
      import('./branch/branch-admin.component').then((m) => m.BranchAdminComponent),
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
    path: 'roles/uid/:uid',
    canActivate: [requirePermission('ROLE.ADMIN')],
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
    path: 'users/uid/:uid',
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
  // Product Master — full-wizard create + edit form.
  {
    path: 'products/master',
    canActivate: [requirePermission('PRODUCT.MANAGE')],
    loadComponent: () =>
      import('./products/product-master.component').then((m) => m.ProductMasterComponent),
  },
  {
    path: 'products/master/uid/:uid',
    canActivate: [requirePermission('PRODUCT.MANAGE')],
    loadComponent: () =>
      import('./products/product-master.component').then((m) => m.ProductMasterComponent),
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
  // ── Sales Orders (O2C) ────────────────────────────────────────────────────
  {
    path: 'quotations',
    canActivate: [requirePermission('SALES.QUOTE.VIEW')],
    loadComponent: () =>
      import('./sales/quotation-list.component').then((m) => m.QuotationListComponent),
  },
  {
    path: 'quotations/uid/:uid',
    canActivate: [requirePermission('SALES.QUOTE.VIEW')],
    loadComponent: () =>
      import('./sales/quotation-detail.component').then((m) => m.QuotationDetailComponent),
  },
  {
    path: 'sales-orders',
    canActivate: [requirePermission('SALES.ORDER.VIEW')],
    loadComponent: () =>
      import('./sales/sales-order-list.component').then((m) => m.SalesOrderListComponent),
  },
  {
    path: 'sales-orders/uid/:uid',
    canActivate: [requirePermission('SALES.ORDER.VIEW')],
    loadComponent: () =>
      import('./sales/sales-order-detail.component').then((m) => m.SalesOrderDetailComponent),
  },
  {
    path: 'deliveries',
    canActivate: [requirePermission('SALES.DELIVERY.VIEW')],
    loadComponent: () =>
      import('./sales/delivery-list.component').then((m) => m.DeliveryListComponent),
  },
  {
    path: 'deliveries/create',
    canActivate: [requirePermission('SALES.DELIVERY.CREATE')],
    loadComponent: () =>
      import('./sales/delivery-create.component').then((m) => m.DeliveryCreateComponent),
  },
  {
    path: 'deliveries/uid/:uid',
    canActivate: [requirePermission('SALES.DELIVERY.VIEW')],
    loadComponent: () =>
      import('./sales/delivery-detail.component').then((m) => m.DeliveryDetailComponent),
  },
  // ── Sales Returns (RMA) ───────────────────────────────────────────────────
  {
    path: 'sales-returns',
    canActivate: [requirePermission('SALES.RETURN.VIEW')],
    loadComponent: () =>
      import('./sales/sales-return-list.component').then((m) => m.SalesReturnListComponent),
  },
  {
    path: 'sales-returns/create',
    canActivate: [requirePermission('SALES.RETURN.CREATE')],
    loadComponent: () =>
      import('./sales/sales-return-create.component').then((m) => m.SalesReturnCreateComponent),
  },
  {
    path: 'sales-returns/uid/:uid',
    canActivate: [requirePermission('SALES.RETURN.VIEW')],
    loadComponent: () =>
      import('./sales/sales-return-detail.component').then((m) => m.SalesReturnDetailComponent),
  },
  // ── Sales Invoices ─────────────────────────────────────────────────────────
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
  // ── Stock Locations / Batches / Serials ──────────────────────────────────
  {
    path: 'stock/locations',
    canActivate: [requirePermission('STOCK.LOCATION.VIEW')],
    loadComponent: () =>
      import('./stock/locations/stock-location-list.component').then(
        (m) => m.StockLocationListComponent,
      ),
  },
  {
    path: 'stock/batches',
    canActivate: [requirePermission('STOCK.VIEW')],
    loadComponent: () =>
      import('./stock/batches/stock-batch-list.component').then(
        (m) => m.StockBatchListComponent,
      ),
  },
  {
    path: 'stock/serials',
    canActivate: [requirePermission('STOCK.VIEW')],
    loadComponent: () =>
      import('./stock/serials/stock-serial-list.component').then(
        (m) => m.StockSerialListComponent,
      ),
  },
  // ── Inventory Valuation ───────────────────────────────────────────────────
  {
    path: 'stock/valuation',
    canActivate: [requirePermission('INVENTORY.VALUATION.VIEW')],
    loadComponent: () =>
      import('./inventory-valuation/stock-valuation-report.component').then(
        (m) => m.StockValuationReportComponent,
      ),
  },
  {
    path: 'stock/valuation/opening',
    canActivate: [requirePermission('INVENTORY.OPENING.SET')],
    loadComponent: () =>
      import('./inventory-valuation/opening-valuation.component').then(
        (m) => m.OpeningValuationComponent,
      ),
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
    canActivate: [requirePermission('PURCHASE.GOODS_RECEIPT.VIEW')],
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
    canActivate: [requirePermission('PURCHASE.GOODS_RECEIPT.VIEW')],
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
  // ── Accounts Receivable ───────────────────────────────────────────────────
  {
    path: 'ar/invoices',
    canActivate: [requirePermission('AR.VIEW')],
    loadComponent: () =>
      import('./ar/ar-invoices-list.component').then((m) => m.ArInvoicesListComponent),
  },
  {
    path: 'ar/receipts/record',
    canActivate: [requirePermission('AR.RECEIPT.RECORD')],
    loadComponent: () =>
      import('./ar/record-receipt.component').then((m) => m.RecordReceiptComponent),
  },
  {
    path: 'ar/statement',
    canActivate: [requirePermission('AR.STATEMENT.VIEW')],
    loadComponent: () =>
      import('./ar/customer-statement.component').then((m) => m.CustomerStatementComponent),
  },
  {
    path: 'ar/opening-balance',
    canActivate: [requirePermission('AR.OPENING.SET')],
    loadComponent: () =>
      import('./ar/ar-opening-balance.component').then((m) => m.ArOpeningBalanceComponent),
  },
  // ── Accounts Payable ──────────────────────────────────────────────────────
  {
    path: 'ap/supplier-bills',
    canActivate: [requirePermission('AP.VIEW')],
    loadComponent: () =>
      import('./ap/supplier-bills-list.component').then((m) => m.SupplierBillsListComponent),
  },
  {
    path: 'ap/supplier-bills/enter',
    canActivate: [requirePermission('AP.BILL.ENTER')],
    loadComponent: () =>
      import('./ap/enter-bill.component').then((m) => m.EnterBillComponent),
  },
  {
    path: 'ap/supplier-bills/uid/:uid',
    canActivate: [requirePermission('AP.VIEW')],
    loadComponent: () =>
      import('./ap/bill-detail.component').then((m) => m.BillDetailComponent),
  },
  {
    path: 'ap/payments/record',
    canActivate: [requirePermission('AP.PAYMENT.RUN')],
    loadComponent: () =>
      import('./ap/record-payment.component').then((m) => m.RecordPaymentComponent),
  },
  {
    path: 'ap/statement',
    canActivate: [requirePermission('AP.VIEW')],
    loadComponent: () =>
      import('./ap/supplier-statement.component').then((m) => m.SupplierStatementComponent),
  },
  {
    path: 'ap/opening-balance',
    canActivate: [requirePermission('AP.OPENING.SET')],
    loadComponent: () =>
      import('./ap/ap-opening-balance.component').then((m) => m.ApOpeningBalanceComponent),
  },
  // ── Cash & Bank ───────────────────────────────────────────────────────────
  {
    path: 'cash/accounts',
    canActivate: [requirePermission('CASH.VIEW')],
    loadComponent: () =>
      import('./cashbank/cash-accounts-list.component').then((m) => m.CashAccountsListComponent),
  },
  {
    path: 'cash/transfers/record',
    canActivate: [requirePermission('CASH.TRANSFER')],
    loadComponent: () =>
      import('./cashbank/record-transfer.component').then((m) => m.RecordTransferComponent),
  },
  {
    path: 'cash/entries/record',
    canActivate: [requirePermission('CASH.ENTRY.RECORD')],
    loadComponent: () =>
      import('./cashbank/record-entry.component').then((m) => m.RecordEntryComponent),
  },
  {
    path: 'cash/cheques',
    canActivate: [requirePermission('CHEQUE.MANAGE')],
    loadComponent: () =>
      import('./cashbank/cheque-register.component').then((m) => m.ChequeRegisterComponent),
  },
  {
    path: 'cash/reconciliations',
    canActivate: [requirePermission('CASH.RECONCILE')],
    loadComponent: () =>
      import('./cashbank/bank-reconciliation.component').then((m) => m.BankReconciliationComponent),
  },
  {
    path: 'cash/statement',
    canActivate: [requirePermission('CASH.VIEW')],
    loadComponent: () =>
      import('./cashbank/cash-account-statement.component').then((m) => m.CashAccountStatementComponent),
  },
  // ── Tax (VAT Returns + WHT) ───────────────────────────────────────────────
  {
    path: 'tax/vat-returns',
    canActivate: [requirePermission('VAT.VIEW')],
    loadComponent: () =>
      import('./tax/vat-returns-list.component').then((m) => m.VatReturnsListComponent),
  },
  {
    path: 'tax/vat-returns/uid/:uid',
    canActivate: [requirePermission('VAT.VIEW')],
    loadComponent: () =>
      import('./tax/vat-return-detail.component').then((m) => m.VatReturnDetailComponent),
  },
  {
    path: 'tax/wht-types',
    canActivate: [requirePermission('WHT.VIEW')],
    loadComponent: () =>
      import('./tax/wht-types-list.component').then((m) => m.WhtTypesListComponent),
  },
  {
    path: 'tax/wht-register',
    canActivate: [requirePermission('WHT.VIEW')],
    loadComponent: () =>
      import('./tax/wht-register.component').then((m) => m.WhtRegisterComponent),
  },
  // ── Financial Reporting ───────────────────────────────────────────────────
  {
    path: 'reporting/income-statement',
    canActivate: [requirePermission('REPORT.PL.VIEW')],
    loadComponent: () =>
      import('./reporting/income-statement.component').then((m) => m.IncomeStatementComponent),
  },
  {
    path: 'reporting/balance-sheet',
    canActivate: [requirePermission('REPORT.BS.VIEW')],
    loadComponent: () =>
      import('./reporting/balance-sheet.component').then((m) => m.BalanceSheetComponent),
  },
  {
    path: 'reporting/cash-flow',
    canActivate: [requirePermission('REPORT.CASHFLOW.VIEW')],
    loadComponent: () =>
      import('./reporting/cash-flow-statement.component').then((m) => m.CashFlowStatementComponent),
  },
  {
    path: 'reporting/account-ledger',
    canActivate: [requirePermission('REPORT.LEDGER.VIEW')],
    loadComponent: () =>
      import('./reporting/account-ledger.component').then((m) => m.AccountLedgerComponent),
  },
  // ── Approvals ─────────────────────────────────────────────────────────────
  {
    path: 'approvals/inbox',
    canActivate: [requirePermission('APPROVALS.DECIDE')],
    loadComponent: () =>
      import('./approvals/approval-inbox.component').then((m) => m.ApprovalInboxComponent),
  },
  {
    path: 'approvals/policies',
    canActivate: [requirePermission('APPROVALS.POLICY.VIEW')],
    loadComponent: () =>
      import('./approvals/approval-policy-list.component').then((m) => m.ApprovalPolicyListComponent),
  },
  {
    path: 'approvals/policies/uid/:uid',
    canActivate: [requirePermission('APPROVALS.POLICY.VIEW')],
    loadComponent: () =>
      import('./approvals/approval-policy-detail.component').then((m) => m.ApprovalPolicyDetailComponent),
  },
  {
    path: 'approvals/requests',
    canActivate: [requirePermission('APPROVALS.REQUEST.VIEW')],
    loadComponent: () =>
      import('./approvals/approval-request-list.component').then((m) => m.ApprovalRequestListComponent),
  },
  {
    path: 'approvals/requests/uid/:uid',
    canActivate: [requirePermission('APPROVALS.REQUEST.VIEW')],
    loadComponent: () =>
      import('./approvals/approval-request-detail.component').then((m) => m.ApprovalRequestDetailComponent),
  },
  // ── Documents & PDF ───────────────────────────────────────────────────────
  {
    path: 'documents',
    canActivate: [requirePermission('DOCUMENT.VIEW')],
    loadComponent: () =>
      import('./documents/document-list.component').then((m) => m.DocumentListComponent),
  },
  {
    path: 'documents/uid/:uid',
    canActivate: [requirePermission('DOCUMENT.VIEW')],
    loadComponent: () =>
      import('./documents/document-detail.component').then((m) => m.DocumentDetailComponent),
  },
  {
    path: 'document-templates',
    canActivate: [requirePermission('DOCUMENT.TEMPLATE.MANAGE')],
    loadComponent: () =>
      import('./documents/document-template-list.component').then((m) => m.DocumentTemplateListComponent),
  },
  {
    path: 'document-branding',
    canActivate: [requirePermission('DOCUMENT.BRANDING.MANAGE')],
    loadComponent: () =>
      import('./documents/document-branding.component').then((m) => m.DocumentBrandingComponent),
  },
  // ── Notifications ─────────────────────────────────────────────────────────
  {
    path: 'notifications',
    canActivate: [requirePermission('NOTIFICATION.VIEW')],
    loadComponent: () =>
      import('./notifications/notification-inbox.component').then((m) => m.NotificationInboxComponent),
  },
  {
    path: 'notification-preferences',
    canActivate: [requirePermission('NOTIFICATION.PREFERENCE.MANAGE')],
    loadComponent: () =>
      import('./notifications/notification-preferences.component').then((m) => m.NotificationPreferencesComponent),
  },
  {
    path: 'notification-types',
    canActivate: [requirePermission('NOTIFICATION.ADMIN')],
    loadComponent: () =>
      import('./notifications/notification-types.component').then((m) => m.NotificationTypesComponent),
  },
  {
    path: 'notification-deliveries',
    canActivate: [requirePermission('NOTIFICATION.ADMIN')],
    loadComponent: () =>
      import('./notifications/notification-delivery-log.component').then((m) => m.NotificationDeliveryLogComponent),
  },
  // ── Costing (Cost Centres / Dimensions) ──────────────────────────────────
  {
    path: 'cost-centre/dimensions',
    canActivate: [requirePermission('COSTING.VIEW')],
    loadComponent: () =>
      import('./cost-centre/dimension-list.component').then((m) => m.DimensionListComponent),
  },
  {
    path: 'cost-centre/values',
    canActivate: [requirePermission('COSTING.VIEW')],
    loadComponent: () =>
      import('./cost-centre/dimension-value-list.component').then((m) => m.DimensionValueListComponent),
  },
  {
    path: 'cost-centre/values/uid/:uid',
    canActivate: [requirePermission('COSTING.VIEW')],
    loadComponent: () =>
      import('./cost-centre/dimension-value-detail.component').then((m) => m.DimensionValueDetailComponent),
  },
  {
    path: 'cost-centre/report',
    canActivate: [requirePermission('COSTING.VIEW')],
    loadComponent: () =>
      import('./cost-centre/costing-report.component').then((m) => m.CostingReportComponent),
  },
  // ── Fixed Assets ─────────────────────────────────────────────────────────────
  {
    path: 'asset-categories',
    canActivate: [requirePermission('FA.CATEGORY.VIEW')],
    loadComponent: () =>
      import('./fixed-assets/asset-category-list.component').then((m) => m.AssetCategoryListComponent),
  },
  {
    path: 'asset-categories/uid/:uid',
    canActivate: [requirePermission('FA.CATEGORY.VIEW')],
    loadComponent: () =>
      import('./fixed-assets/asset-category-detail.component').then((m) => m.AssetCategoryDetailComponent),
  },
  {
    path: 'fixed-assets',
    canActivate: [requirePermission('FA.VIEW')],
    loadComponent: () =>
      import('./fixed-assets/fixed-asset-list.component').then((m) => m.FixedAssetListComponent),
  },
  {
    path: 'fixed-assets/create',
    canActivate: [requirePermission('FA.REGISTER.MANAGE')],
    loadComponent: () =>
      import('./fixed-assets/fixed-asset-create.component').then((m) => m.FixedAssetCreateComponent),
  },
  {
    path: 'fixed-assets/uid/:uid',
    canActivate: [requirePermission('FA.VIEW')],
    loadComponent: () =>
      import('./fixed-assets/fixed-asset-detail.component').then((m) => m.FixedAssetDetailComponent),
  },
  {
    path: 'fixed-assets/reconciliation',
    canActivate: [requirePermission('FA.VIEW')],
    loadComponent: () =>
      import('./fixed-assets/fa-reconciliation.component').then((m) => m.FaReconciliationComponent),
  },
  {
    path: 'depreciation-runs',
    canActivate: [requirePermission('FA.VIEW')],
    loadComponent: () =>
      import('./fixed-assets/depreciation-run-list.component').then((m) => m.DepreciationRunListComponent),
  },
  {
    path: 'depreciation-runs/post',
    canActivate: [requirePermission('FA.DEPRECIATE')],
    loadComponent: () =>
      import('./fixed-assets/depreciation-post.component').then((m) => m.DepreciationPostComponent),
  },
  {
    path: 'depreciation-runs/uid/:uid',
    canActivate: [requirePermission('FA.VIEW')],
    loadComponent: () =>
      import('./fixed-assets/depreciation-run-detail.component').then((m) => m.DepreciationRunDetailComponent),
  },
  // ── CRM ───────────────────────────────────────────────────────────────────
  {
    path: 'crm/leads',
    canActivate: [requirePermission('CRM.LEAD.VIEW')],
    loadComponent: () =>
      import('./crm/lead-list.component').then((m) => m.LeadListComponent),
  },
  {
    path: 'crm/leads/uid/:uid',
    canActivate: [requirePermission('CRM.LEAD.VIEW')],
    loadComponent: () =>
      import('./crm/lead-detail.component').then((m) => m.LeadDetailComponent),
  },
  {
    path: 'crm/opportunities',
    canActivate: [requirePermission('CRM.OPPORTUNITY.VIEW')],
    loadComponent: () =>
      import('./crm/opportunity-list.component').then((m) => m.OpportunityListComponent),
  },
  {
    path: 'crm/opportunities/create',
    canActivate: [requirePermission('CRM.OPPORTUNITY.MANAGE')],
    loadComponent: () =>
      import('./crm/opportunity-create.component').then((m) => m.OpportunityCreateComponent),
  },
  {
    path: 'crm/opportunities/uid/:uid',
    canActivate: [requirePermission('CRM.OPPORTUNITY.VIEW')],
    loadComponent: () =>
      import('./crm/opportunity-detail.component').then((m) => m.OpportunityDetailComponent),
  },
  {
    path: 'crm/pipeline',
    canActivate: [requirePermission('CRM.PIPELINE.VIEW')],
    loadComponent: () =>
      import('./crm/pipeline-dashboard.component').then((m) => m.PipelineDashboardComponent),
  },
  {
    path: 'crm/settings/pipeline-stages',
    canActivate: [requirePermission('CRM.STAGE.MANAGE')],
    loadComponent: () =>
      import('./crm/pipeline-stage-list.component').then((m) => m.PipelineStageListComponent),
  },
  // ── HR & Payroll ──────────────────────────────────────────────────────────────
  {
    path: 'hr/employees',
    canActivate: [requirePermission('HR.EMPLOYEE.VIEW')],
    loadComponent: () =>
      import('./hr-payroll/employee-list.component').then((m) => m.EmployeeListComponent),
  },
  {
    path: 'hr/employees/uid/:uid',
    canActivate: [requirePermission('HR.EMPLOYEE.VIEW')],
    loadComponent: () =>
      import('./hr-payroll/employee-detail.component').then((m) => m.EmployeeDetailComponent),
  },
  {
    path: 'hr/pay-components',
    canActivate: [requirePermission('HR.PAYCOMPONENT.MANAGE')],
    loadComponent: () =>
      import('./hr-payroll/pay-component-list.component').then((m) => m.PayComponentListComponent),
  },
  {
    path: 'hr/pay-components/uid/:uid',
    canActivate: [requirePermission('HR.PAYCOMPONENT.MANAGE')],
    loadComponent: () =>
      import('./hr-payroll/pay-component-detail.component').then((m) => m.PayComponentDetailComponent),
  },
  {
    path: 'hr/payroll-runs',
    canActivate: [requirePermission('HR.PAYROLL.VIEW')],
    loadComponent: () =>
      import('./hr-payroll/payroll-run-list.component').then((m) => m.PayrollRunListComponent),
  },
  {
    path: 'hr/payroll-runs/uid/:uid',
    canActivate: [requirePermission('HR.PAYROLL.VIEW')],
    loadComponent: () =>
      import('./hr-payroll/payroll-run-detail.component').then((m) => m.PayrollRunDetailComponent),
  },
  {
    path: 'hr/leave-requests',
    canActivate: [requirePermission('HR.LEAVE.VIEW')],
    loadComponent: () =>
      import('./hr-payroll/leave-request-list.component').then((m) => m.LeaveRequestListComponent),
  },
  {
    path: 'hr/leave-requests/uid/:uid',
    canActivate: [requirePermission('HR.LEAVE.VIEW')],
    loadComponent: () =>
      import('./hr-payroll/leave-request-detail.component').then((m) => m.LeaveRequestDetailComponent),
  },
  {
    path: 'hr/loans',
    canActivate: [requirePermission('HR.LOAN.MANAGE')],
    loadComponent: () =>
      import('./hr-payroll/loan-list.component').then((m) => m.LoanListComponent),
  },
  {
    path: 'hr/loans/uid/:uid',
    canActivate: [requirePermission('HR.LOAN.MANAGE')],
    loadComponent: () =>
      import('./hr-payroll/loan-detail.component').then((m) => m.LoanDetailComponent),
  },
  // ── Projects ──────────────────────────────────────────────────────────────
  {
    path: 'projects',
    canActivate: [requirePermission('PROJECTS.PROJECT.VIEW')],
    loadComponent: () =>
      import('./projects/project-list.component').then((m) => m.ProjectListComponent),
  },
  {
    path: 'projects/uid/:uid',
    canActivate: [requirePermission('PROJECTS.PROJECT.VIEW')],
    loadComponent: () =>
      import('./projects/project-detail.component').then((m) => m.ProjectDetailComponent),
  },
  {
    path: 'projects/wip-report',
    canActivate: [requirePermission('PROJECTS.COSTING.VIEW')],
    loadComponent: () =>
      import('./projects/project-wip-report.component').then((m) => m.ProjectWipReportComponent),
  },
  // ── Budgeting & Management Accounting ────────────────────────────────────
  {
    path: 'budgets',
    canActivate: [requirePermission('BUDGETING.BUDGET.VIEW')],
    loadComponent: () =>
      import('./budgeting/budget-list.component').then((m) => m.BudgetListComponent),
  },
  {
    path: 'budgets/uid/:uid',
    canActivate: [requirePermission('BUDGETING.BUDGET.VIEW')],
    loadComponent: () =>
      import('./budgeting/budget-detail.component').then((m) => m.BudgetDetailComponent),
  },
  {
    path: 'budget-versions/uid/:uid',
    canActivate: [requirePermission('BUDGETING.BUDGET.VIEW')],
    loadComponent: () =>
      import('./budgeting/budget-version-detail.component').then((m) => m.BudgetVersionDetailComponent),
  },
  {
    path: 'budgeting/variance',
    canActivate: [requirePermission('BUDGETING.REPORT.VIEW')],
    loadComponent: () =>
      import('./budgeting/budget-variance-report.component').then((m) => m.BudgetVarianceReportComponent),
  },
  {
    path: 'budgeting/departmental-actuals',
    canActivate: [requirePermission('BUDGETING.REPORT.VIEW')],
    loadComponent: () =>
      import('./budgeting/departmental-actuals-report.component').then((m) => m.DepartmentalActualsReportComponent),
  },
  // ── Manufacturing ─────────────────────────────────────────────────────────
  {
    path: 'work-orders',
    canActivate: [requirePermission('MANUFACTURING.VIEW')],
    loadComponent: () =>
      import('./manufacturing/work-order-list.component').then((m) => m.WorkOrderListComponent),
  },
  {
    path: 'work-orders/uid/:uid',
    canActivate: [requirePermission('MANUFACTURING.VIEW')],
    loadComponent: () =>
      import('./manufacturing/work-order-detail.component').then(
        (m) => m.WorkOrderDetailComponent,
      ),
  },
  {
    path: 'work-orders/uid/:uid/cost-report',
    canActivate: [requirePermission('MANUFACTURING.VIEW')],
    loadComponent: () =>
      import('./manufacturing/work-order-cost-report.component').then(
        (m) => m.WorkOrderCostReportComponent,
      ),
  },
  {
    path: 'manufacturing/wip-reconciliation',
    canActivate: [requirePermission('MANUFACTURING.VIEW')],
    loadComponent: () =>
      import('./manufacturing/wip-reconciliation.component').then(
        (m) => m.WipReconciliationComponent,
      ),
  },
  // ── FX / Multi-currency ───────────────────────────────────────────────────
  {
    path: 'fx/currencies',
    canActivate: [requirePermission('CURRENCY.MANAGE')],
    loadComponent: () =>
      import('./fx/currency-enablement-list.component').then((m) => m.CurrencyEnablementListComponent),
  },
  {
    path: 'fx/rates',
    canActivate: [requirePermission('CURRENCY.VIEW')],
    loadComponent: () =>
      import('./fx/fx-rate-list.component').then((m) => m.FxRateListComponent),
  },
  {
    path: 'fx/revaluation-runs',
    canActivate: [requirePermission('FX.EXPOSURE.VIEW')],
    loadComponent: () =>
      import('./fx/fx-revaluation-list.component').then((m) => m.FxRevaluationListComponent),
  },
  // ── Analytics ──────────────────────────────────────────────────────────────
  {
    path: 'dashboard',
    canActivate: [requirePermission('BI.VIEW')],
    loadComponent: () =>
      import('./dashboard/dashboard.component').then((m) => m.DashboardComponent),
  },
  // ── POS ──────────────────────────────────────────────────────────────────────
  {
    path: 'pos/tills',
    canActivate: [requirePermission('POS.TILL.VIEW')],
    loadComponent: () =>
      import('./pos/pos-till-list.component').then((m) => m.PosTillListComponent),
  },
  {
    path: 'pos/sessions',
    canActivate: [requirePermission('POS.SESSION.VIEW')],
    loadComponent: () =>
      import('./pos/pos-session-list.component').then((m) => m.PosSessionListComponent),
  },
  {
    path: 'pos/sessions/uid/:uid',
    canActivate: [requirePermission('POS.SESSION.VIEW')],
    loadComponent: () =>
      import('./pos/pos-session-detail.component').then((m) => m.PosSessionDetailComponent),
  },
  {
    path: 'pos/sell',
    canActivate: [requirePermission('POS.SALE.CREATE')],
    loadComponent: () =>
      import('./pos/pos-sale.component').then((m) => m.PosSaleComponent),
  },
  // ── Stock Transfers ─────────────────���──────────────────────────��──────────
  {
    path: 'stock-transfers',
    canActivate: [requirePermission('STOCK.TRANSFER.VIEW')],
    loadComponent: () =>
      import('./stock/transfer/stock-transfer-list.component').then(
        (m) => m.StockTransferListComponent,
      ),
  },
  {
    path: 'stock-transfers/create',
    canActivate: [requirePermission('STOCK.TRANSFER.CREATE')],
    loadComponent: () =>
      import('./stock/transfer/stock-transfer-create.component').then(
        (m) => m.StockTransferCreateComponent,
      ),
  },
  {
    path: 'stock-transfers/uid/:uid',
    canActivate: [requirePermission('STOCK.TRANSFER.VIEW')],
    loadComponent: () =>
      import('./stock/transfer/stock-transfer-detail.component').then(
        (m) => m.StockTransferDetailComponent,
      ),
  },
  // ── Stock Counts (Physical / Cycle Count) ────────────────────────────────
  {
    path: 'stock-counts',
    canActivate: [requirePermission('STOCK.COUNT.VIEW')],
    loadComponent: () =>
      import('./stock/count/stock-count-list.component').then((m) => m.StockCountListComponent),
  },
  {
    path: 'stock-counts/create',
    canActivate: [requirePermission('STOCK.COUNT.CREATE')],
    loadComponent: () =>
      import('./stock/count/stock-count-create.component').then((m) => m.StockCountCreateComponent),
  },
  {
    path: 'stock-counts/uid/:uid',
    canActivate: [requirePermission('STOCK.COUNT.VIEW')],
    loadComponent: () =>
      import('./stock/count/stock-count-detail.component').then((m) => m.StockCountDetailComponent),
  },
  // ── Purchase Requisitions ─────────────────────────────────────────────────
  {
    path: 'purchase-requisitions',
    canActivate: [requirePermission('PURCHASE.REQUISITION.VIEW')],
    loadComponent: () =>
      import('./purchases/requisition/requisition-list.component').then(
        (m) => m.RequisitionListComponent,
      ),
  },
  {
    path: 'purchase-requisitions/create',
    canActivate: [requirePermission('PURCHASE.REQUISITION.CREATE')],
    loadComponent: () =>
      import('./purchases/requisition/requisition-create.component').then(
        (m) => m.RequisitionCreateComponent,
      ),
  },
  {
    path: 'purchase-requisitions/uid/:uid',
    canActivate: [requirePermission('PURCHASE.REQUISITION.VIEW')],
    loadComponent: () =>
      import('./purchases/requisition/requisition-detail.component').then(
        (m) => m.RequisitionDetailComponent,
      ),
  },
  // ── RFQ / Sourcing ────────────────────────────────────────────────────────
  {
    path: 'rfqs',
    canActivate: [requirePermission('PURCHASE.RFQ.VIEW')],
    loadComponent: () =>
      import('./purchases/rfq/rfq-list.component').then((m) => m.RfqListComponent),
  },
  {
    path: 'rfqs/create',
    canActivate: [requirePermission('PURCHASE.RFQ.MANAGE')],
    loadComponent: () =>
      import('./purchases/rfq/rfq-create.component').then((m) => m.RfqCreateComponent),
  },
  {
    path: 'rfqs/uid/:uid',
    canActivate: [requirePermission('PURCHASE.RFQ.VIEW')],
    loadComponent: () =>
      import('./purchases/rfq/rfq-detail.component').then((m) => m.RfqDetailComponent),
  },
  // ── Purchase Returns ──────────────────────────────────────────────────────
  {
    path: 'purchase-returns',
    canActivate: [requirePermission('PURCHASE.RETURN.VIEW')],
    loadComponent: () =>
      import('./purchases/returns/purchase-return-list.component').then((m) => m.PurchaseReturnListComponent),
  },
  {
    path: 'purchase-returns/create',
    canActivate: [requirePermission('PURCHASE.RETURN.CREATE')],
    loadComponent: () =>
      import('./purchases/returns/purchase-return-create.component').then((m) => m.PurchaseReturnCreateComponent),
  },
  {
    path: 'purchase-returns/uid/:uid',
    canActivate: [requirePermission('PURCHASE.RETURN.VIEW')],
    loadComponent: () =>
      import('./purchases/returns/purchase-return-detail.component').then((m) => m.PurchaseReturnDetailComponent),
  },
  // ── Landed Costs ──────────────────────────────────────────────────────────
  {
    path: 'landed-costs',
    canActivate: [requirePermission('PURCHASE.LANDEDCOST.VIEW')],
    loadComponent: () =>
      import('./purchases/landed-costs/landed-cost-list.component').then((m) => m.LandedCostListComponent),
  },
  {
    path: 'landed-costs/create',
    canActivate: [requirePermission('PURCHASE.LANDEDCOST.MANAGE')],
    loadComponent: () =>
      import('./purchases/landed-costs/landed-cost-create.component').then((m) => m.LandedCostCreateComponent),
  },
  {
    path: 'landed-costs/uid/:uid',
    canActivate: [requirePermission('PURCHASE.LANDEDCOST.VIEW')],
    loadComponent: () =>
      import('./purchases/landed-costs/landed-cost-detail.component').then((m) => m.LandedCostDetailComponent),
  },
  // ── Purchase Settings ─────────────────────────────────────────────────────
  {
    path: 'purchase-settings',
    canActivate: [requirePermission('PURCHASE.SETTINGS.MANAGE')],
    loadComponent: () =>
      import('./purchases/settings/purchase-settings.component').then((m) => m.PurchaseSettingsComponent),
  },
  // ── Blanket Orders ────────────────────────────────────────────────────────
  {
    path: 'blanket-orders',
    canActivate: [requirePermission('SALES.BLANKET.VIEW')],
    loadComponent: () =>
      import('./sales/blanket/blanket-order-list.component').then((m) => m.BlanketOrderListComponent),
  },
  {
    path: 'blanket-orders/uid/:uid',
    canActivate: [requirePermission('SALES.BLANKET.VIEW')],
    loadComponent: () =>
      import('./sales/blanket/blanket-order-detail.component').then((m) => m.BlanketOrderDetailComponent),
  },
  {
    path: 'blanket-orders/create',
    canActivate: [requirePermission('SALES.BLANKET.CREATE')],
    loadComponent: () =>
      import('./sales/blanket/blanket-order-create.component').then((m) => m.BlanketOrderCreateComponent),
  },
  // ── Bills of Materials ────────────────────────────────────────────────────
  {
    path: 'boms',
    canActivate: [requirePermission('BOM.VIEW')],
    loadComponent: () =>
      import('./manufacturing/bom/bom-list.component').then((m) => m.BomListComponent),
  },
  {
    path: 'boms/uid/:uid',
    canActivate: [requirePermission('BOM.VIEW')],
    loadComponent: () =>
      import('./manufacturing/bom/bom-detail.component').then((m) => m.BomDetailComponent),
  },
  // ── Standing Orders (Recurring Sales) ────────────────────────────────────────
  {
    path: 'standing-orders',
    canActivate: [requirePermission('SALES.STANDING.VIEW')],
    loadComponent: () =>
      import('./sales/standing/standing-order-list.component').then((m) => m.StandingOrderListComponent),
  },
  {
    path: 'standing-orders/create',
    canActivate: [requirePermission('SALES.STANDING.CREATE')],
    loadComponent: () =>
      import('./sales/standing/standing-order-create.component').then((m) => m.StandingOrderCreateComponent),
  },
  {
    path: 'standing-orders/uid/:uid',
    canActivate: [requirePermission('SALES.STANDING.VIEW')],
    loadComponent: () =>
      import('./sales/standing/standing-order-detail.component').then((m) => m.StandingOrderDetailComponent),
  },
  // ── Pricing Rules ──────────────────────────────────────────────────────────
  {
    path: 'pricing-rules',
    canActivate: [requirePermission('SALES.PRICING.RULE.VIEW')],
    loadComponent: () =>
      import('./sales/pricing/pricing-rules.component').then((m) => m.PricingRulesComponent),
  },
  // ── Other Parties ─────────────────────────────────────────────────────────
  {
    path: 'other-parties',
    canActivate: [requirePermission('OTHERPARTY.VIEW')],
    loadComponent: () =>
      import('./parties/other/other-party-list.component').then((m) => m.OtherPartyListComponent),
  },
  {
    path: 'other-parties/uid/:uid',
    canActivate: [requirePermission('OTHERPARTY.VIEW')],
    loadComponent: () =>
      import('./parties/other/other-party-detail.component').then((m) => m.OtherPartyDetailComponent),
  },
  // ── CRM Activities ────────────────────────────────────────────────────────
  {
    path: 'crm/activities',
    canActivate: [requirePermission('CRM.ACTIVITY.VIEW')],
    loadComponent: () =>
      import('./crm/activity/activity-tasks.component').then((m) => m.ActivityTasksComponent),
  },
  // ── HR Departments ────────────────────────────────────────────────────────
  {
    path: 'hr/departments',
    canActivate: [requirePermission('HR.EMPLOYEE.VIEW')],
    loadComponent: () =>
      import('./hr-payroll/departments/department-list.component').then((m) => m.DepartmentListComponent),
  },
  // ── HR Contracts ─────────────────────────────────────────────────────────
  {
    path: 'hr/contracts',
    canActivate: [requirePermission('HR.EMPLOYEE.VIEW')],
    loadComponent: () =>
      import('./hr-payroll/contracts/contract-list.component').then((m) => m.ContractListComponent),
  },
  // ── HR Statutory Setup ────────────────────────────────────────────────────
  {
    path: 'hr/statutory',
    canActivate: [requirePermission('HR.STATUTORY.MANAGE')],
    loadComponent: () =>
      import('./hr-payroll/statutory/statutory.component').then((m) => m.StatutoryComponent),
  },
  // ── GL Year-End Close ──────────────────────────────────────────────────────
  {
    path: 'gl/year-end',
    canActivate: [requirePermission('GL.YEAR.CLOSE')],
    loadComponent: () =>
      import('./gl/year-end/year-end-close.component').then((m) => m.YearEndCloseComponent),
  },
  // ── AR Receipts list + detail ─────────────────────────────────────────────
  {
    path: 'ar/receipts',
    canActivate: [requirePermission('AR.VIEW')],
    loadComponent: () =>
      import('./ar/ar-receipts-list.component').then((m) => m.ArReceiptsListComponent),
  },
  {
    path: 'ar/receipts/uid/:uid',
    canActivate: [requirePermission('AR.VIEW')],
    loadComponent: () =>
      import('./ar/ar-receipt-detail.component').then((m) => m.ArReceiptDetailComponent),
  },
  // ── AR Ageing + Balance ───────────────────────────────────────────────────
  {
    path: 'ar/ageing',
    canActivate: [requirePermission('AR.STATEMENT.VIEW')],
    loadComponent: () =>
      import('./ar/ar-ageing.component').then((m) => m.ArAgeingComponent),
  },
  // ── AP Payments list + detail ─────────────────────────────────────────────
  {
    path: 'ap/payments',
    canActivate: [requirePermission('AP.VIEW')],
    loadComponent: () =>
      import('./ap/ap-payments-list.component').then((m) => m.ApPaymentsListComponent),
  },
  {
    path: 'ap/payments/uid/:uid',
    canActivate: [requirePermission('AP.VIEW')],
    loadComponent: () =>
      import('./ap/ap-payment-detail.component').then((m) => m.ApPaymentDetailComponent),
  },
  // ── Cash Transfers list + detail ──────────────────────────────────────────
  {
    path: 'cash/transfers',
    canActivate: [requirePermission('CASH.VIEW')],
    loadComponent: () =>
      import('./cashbank/cash-transfers-list.component').then((m) => m.CashTransfersListComponent),
  },
  {
    path: 'cash/transfers/uid/:uid',
    canActivate: [requirePermission('CASH.VIEW')],
    loadComponent: () =>
      import('./cashbank/cash-transfer-detail.component').then((m) => m.CashTransferDetailComponent),
  },
  { path: '', redirectTo: 'home', pathMatch: 'full' },
];
