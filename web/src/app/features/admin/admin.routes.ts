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
  { path: '', redirectTo: 'home', pathMatch: 'full' },
];
