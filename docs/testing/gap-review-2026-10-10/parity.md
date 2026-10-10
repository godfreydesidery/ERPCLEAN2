# OrbixERP: SME feature-parity review (breadth view), develop @ f4de0c94, 2026-10-10

Baseline: what a Tanzanian retail or wholesale SME gets out of the box from QuickBooks Online, Zoho Inventory/Books, Odoo Community (Sales/Inventory/POS/Accounting), Loyverse POS and local retail systems, plus what TRA expects (EFD/VFD, VAT return, TIN/VRN on tax invoices).

Path keys: **B** = `backend/src/main/java/com/erp`, **W** = `web/src/app/features/admin`, **SH** = `web/src/app/layout/shell/shell.component.ts` (the menu).

Status meanings:
- **Present**: the backend endpoint, the web route and the menu entry all exist.
- **Partial**: the feature works but is missing a piece SMEs expect.
- **Backend-only**: there is an API but no web screen.
- **Hidden**: there is a route but no menu entry.
- **Missing**: no backend and no web support.

Schema column: **N** = the fix needs no schema change. **S** = NEEDS-SCHEMA (out of scope).

## 1. Matrix

| # | Area | Capability | Status | Evidence | Schema |
|---|---|---|---|---|---|
| 1 | Setup | Company profile with TIN, VRN, logo and address | Present | `Company.taxId/vrn/logoRef`; `/admin/companies`, `/admin/document-branding` | – |
| 2 | Setup | Branches | Present | BranchController; `/admin/branches` | – |
| 3 | Setup | Guided in-app setup (company → tax → accounts → opening balances checklist) | Partial | Only the server-install wizards exist (`dist/release/*/setup-wizard.ps1`). Inside the app there are only the home cards (`W/home/admin-home.component.ts`). | N |
| 4 | Setup | Default chart of accounts seeded | Present | `ApGlSeeder` and the other seeders; `/admin/gl/accounts` | – |
| 5 | Setup | Fiscal year and periods | Present | `/admin/gl/periods`, `/admin/gl/year-end` | – |
| 6 | Setup | Tax rates (standard, zero-rated, exempt) | Present | TaxRateController; `/admin/tax-rates`; `VatStatus` | – |
| 7 | Setup | Multi-currency and exchange rates | Present | `/admin/fx/currencies`, `/admin/fx/rates` | – |
| 8 | Setup | Opening stock with valuation | Present | `/admin/stock/valuation/opening`; bulk import key `stock` | – |
| 9 | Setup | Opening AR/AP balances (entered by hand) | Present | `/admin/ar/opening-balance`, `/admin/ap/opening-balance` | – |
| 10 | Setup | Hide unused modules / simple mode for a small shop | Missing | The menu has about 150 entries over 18 groups (SH:113–493). There is no module toggle anywhere; root sees everything. | N if done as a deployment property; S if per company |
| 11 | Setup | Swahili user interface | Missing | No i18n in web or POS. Swahili appears only as search keywords (SH:189). | N |
| 12 | Master | Products: create/edit, archive, restore | Present | ProductController; `/admin/products`, `/products/master` | – |
| 13 | Master | Several barcodes per item, barcode lookup, weighed-item barcodes | Present | `ProductController /barcodes`, `/barcode-lookup`, `/weighing` | – |
| 14 | Master | Barcode and shelf-label printing | Missing | No label endpoint or screen (grep for label printing finds nothing) | N |
| 15 | Master | Product variants (size/colour matrix) | Missing | `Product.java` has no parent or variant fields | S |
| 16 | Master | Category and brand masters | Partial | `Product.category` and `Product.brand` are free-text strings (Product.java:183,192); no master list, no hierarchy | N for a pick-list of existing values; S for a hierarchy |
| 17 | Master | Product photo | Partial | Image URL text field only (`W/products/product-master.component.html:216`); no upload | S (file store) |
| 18 | Master | Several units of measure and pack sizes | Present | `/units`, `/bulk-packs`; `/admin/units` | – |
| 19 | Master | Price lists and per-customer pricing | Present | `/admin/price-lists`; `Customer.defaultPriceListId` | – |
| 20 | Master | Promotions and quantity-break pricing | Present | `/admin/pricing-rules` | – |
| 21 | Master | Mass price change | Present | `/admin/mass-price-change` | – |
| 22 | Master | Kits, recipes, bills of materials | Present | `Product /components`; `/admin/boms` | – |
| 23 | Master | Customer with TIN/VRN, credit limit, terms, mobile-money number | Present | `PartyBase.tin/vrn/mobileMoneyNo`; `Customer.creditLimit` | – |
| 24 | Master | Several contacts and delivery addresses per customer or supplier | Backend-only | `CustomerContactAddressController` and `SupplierContactAddressController` (`/contacts`, `/addresses`); nothing in web calls them | N |
| 25 | Master | Supplier bank accounts | Backend-only | `SupplierBankAccountController`; no web reference | N |
| 26 | Master | Payment-terms master | Backend-only | `PaymentTermsController /api/v1/payment-terms`; no web reference. Customers use the free `paymentTermsDays` field instead. | N |
| 27 | Master | Suppliers, sales agents, routes, other parties | Present | `/admin/suppliers`, `/agents`, `/routes`, `/other-parties` | – |
| 28 | Sales | Quotation printed as a pro-forma | Present | `/admin/quotations`; DocumentType `QUOTATION` prints as "PROFORMA INVOICE" | – |
| 29 | Sales | Sales orders, partial delivery, approval | Present | `/admin/sales-orders`, `/admin/deliveries` | – |
| 30 | Sales | Print or PDF a sales order | Missing | The DocumentType enum has no SALES_ORDER; `sales-order-detail.component.html` has no print | N if stream-only; S if kept as a generated document (V19 CHECK) |
| 31 | Sales | Delivery note printout | Present | DocumentType `DELIVERY_NOTE`; print on delivery-detail | – |
| 32 | Sales | Tax invoice showing seller TIN/VRN and buyer TIN/VRN | Partial | Seller TIN and VRN are printed. The buyer block (`DocumentRenderModel.PartyBlock`) carries only `taxId`, so the buyer's VRN never prints even though `PartyBase.vrn` exists. | N |
| 33 | Sales | Credit sales with credit-limit checks | Present | `CreditExposureCalculator`, `SalesInvoiceServiceImpl` | – |
| 34 | Sales | Line and whole-document discounts | Present | `SalesInvoice.docDiscountAmount/Percent` | – |
| 35 | Sales | Sales returns and credit notes | Present | `/admin/sales-returns`; ArCreditNoteController | – |
| 36 | Sales | Print a credit note from the return | Hidden | `CREDIT_NOTE` can be rendered only from the `/admin/documents` generator; `sales-return-detail.component.html` has no print or PDF button | N |
| 37 | Sales | Recurring and standing orders, blanket orders | Present | `/admin/standing-orders`, `/blanket-orders` | – |
| 38 | Sales | Customer deposits and advance payments (unapplied cash) | Present | AR receipt allocations and unapplied amounts | – |
| 39 | Sales | Sales commission calculation and report | Missing | No commission model. Sales Summary grouped by AGENT gives only the base figures. | S for per-agent rates |
| 40 | Sales | Email an invoice, quote or statement to the customer | Missing | `EmailSender` sends only plain-text notifications to internal users (no attachment). DocumentController has no "send" endpoint. | N (SMTP already wired) |
| 41 | Sales | Share an invoice or statement by WhatsApp or SMS | Missing | Web has no `wa.me` or `navigator.share`. Sharing exists only for the exec app's reports (`mobile_exec/lib/core/export/report_share.dart`). | N |
| 42 | POS | Till app (Windows, Android, web) | Present | `pos_app/`; web `/admin/pos/sell` | – |
| 43 | POS | Scanner (keyboard wedge), embedded weight/price barcodes | Present | `pos_app/lib/core/barcode.dart` | – |
| 44 | POS | Receipt printing | Partial | Windows only. `receipt_printer_stub.dart` throws "only available on the Windows desktop app", so Android tills (Sunmi, Bluetooth printers) cannot print. | N |
| 45 | POS | Cash-drawer kick | Present | `app_config.dart kickDrawer` (ESC/POS) | – |
| 46 | POS | Split tenders: cash, mobile money, card, cheque | Present | `TenderType` enum (backend and POS) | – |
| 47 | POS | Shift open/close, X/Z reading, variance, payouts | Present | `session_menu.dart`; `/admin/pos/sessions` | – |
| 48 | POS | Discount or price override with manager approval | Present | `step_up_policy.dart`; StepUpController | – |
| 49 | POS | Hold (park) a sale and recall it | Missing | Nothing in `pos_app/lib` for hold, park, suspend or recall | N |
| 50 | POS | Selling while offline | Missing | `pos_app/README.md`: "Offline queue-and-replay is not yet implemented". The idempotency key it would rely on already exists. | N |
| 51 | POS | Return part of a sale at the till | Partial | Whole-sale reverse only (`receipt_view.dart:21`). Line returns have to be done on web `/admin/sales-returns`. | N |
| 52 | POS | Credit (on-account) sale at the till | Missing | `TenderType` has no ACCOUNT option and the code enforces "paid-in-full" | N (use the existing credit-invoice path) |
| 53 | POS | Customer loyalty points | Missing | The `Customer.loyaltyPoints` column exists but nothing reads or writes it | N for a simple balance; S for a points ledger |
| 54 | POS | Gift cards and vouchers | Missing | Not present anywhere | S |
| 55 | POS | Record serial/IMEI numbers at sale | Missing | `PosSaleRequest.LineItem` has no serial field. `StockSerialService.issue()` has no caller and no endpoint, so serials captured at goods receipt stay IN_STOCK after the item is sold. | N |
| 56 | POS | Warranty tracking (electronics) | Missing | Not present anywhere | S |
| 57 | POS | Mobile-money integration (M-Pesa, Tigo/Mixx, Airtel) | Partial | One manual MOBILE_MONEY tender. No breakdown by network, no push payment or confirmation. | S for a transaction log |
| 58 | Purchasing | Purchase orders with approval and print | Present | `/admin/purchase-orders`; DocumentType `PURCHASE_ORDER` | – |
| 59 | Purchasing | Goods receipt against PO, and receipt without PO | Present | `/admin/goods-receipts`, `/goods-receipts/direct` | – |
| 60 | Purchasing | Supplier bills with 3-way match | Present | `/admin/ap/supplier-bills`; BillMatchController | – |
| 61 | Purchasing | Purchase returns and debit notes | Present | `/admin/purchase-returns`; `ap.service.ts` debit-notes | – |
| 62 | Purchasing | Debit-note printout | Missing | DocumentType `DEBIT_NOTE` is reserved but marked "NOT rendered" | N if stream-only |
| 63 | Purchasing | Turn reorder suggestions into a draft PO | Partial | `/admin/reports/reorder` has only Run plus PDF/XLSX/CSV export; no "create PO" action | N |
| 64 | Purchasing | Email a PO to the supplier | Missing | Same email gap as #40 | N |
| 65 | Purchasing | Landed costs | Present | `/admin/landed-costs` | – |
| 66 | Purchasing | Requisitions, RFQs | Present | `/admin/purchase-requisitions`, `/rfqs` | – |
| 67 | Purchasing | Expense or service bill posted to a chosen expense account | Backend-only | The backend honours a per-line GL account (`BillMatchServiceImpl.java:519-523`). `enter-bill.component.html` has no GL-account column, so every non-stock line posts to "Purchases" (5150). | N |
| 68 | Inventory | Stock on hand by branch and location | Present | `/admin/stock`, `/stock/locations` | – |
| 69 | Inventory | Inter-branch transfers with in-transit, printable | Present | `/admin/stock-transfers` | – |
| 70 | Inventory | Stock adjustments with reasons (damage, expiry, shrinkage) | Present | `POST /stock/adjustments` (one product per post); `stock-list` | – |
| 71 | Inventory | Multi-line adjustment voucher with printout | Missing | `AdjustStockRequest` takes a single `productUid` | N |
| 72 | Inventory | Stock counts / stocktake | Present | `/admin/stock-counts` | – |
| 73 | Inventory | Batches with expiry; "expiring" view | Present | `/stock-batches/expiring`; batch-list "expiring" tab | – |
| 74 | Inventory | Expiry alert notification | Missing | The seeded notification types are GOODS_RECEIVED, DELIVERY_CONFIRMED, PAYMENT_RECEIVED, INVOICE_OVERDUE, LOW_STOCK and APPROVAL_PENDING; there is no expiry type (`NotificationTypeSeeder`) | N (seeder row; check whether a CHECK constraint lists type keys) |
| 75 | Inventory | Reorder levels and in-app low-stock alert | Present | `NotificationScanner` LOW_STOCK runs hourly | – |
| 76 | Inventory | Stock card (movement history per item) | Present | `/admin/stock/item-inquiry`, `/reports/stock-movement` | – |
| 77 | Inventory | Moving-average valuation, stock value report | Present | `/admin/stock/valuation`, `/reports/stock-value` | – |
| 78 | Inventory | Negative-stock control | Present | Sales settings | – |
| 79 | Inventory | Stock ageing / dead stock | Present | `/admin/reports/stock-ageing` | – |
| 80 | Inventory | Serial-number register | Present | `/admin/stock/serials` (receipt side only; see #55) | – |
| 81 | AR | Receipts with allocation to invoices | Present | `/admin/ar/receipts/record` | – |
| 82 | AR | Customer statement PDF | Present | `AR_STATEMENT`; `/admin/ar/statement` | – |
| 83 | AR | Ageing across all customers | Present | `/ar/ageing/by-customer`; `/admin/ar/ageing` | – |
| 84 | AR | Write-offs | Present | `ar.service.ts` write-offs from `ar-invoices-list` | – |
| 85 | AR | Overdue reminders sent to the customer (dunning) | Missing | INVOICE_OVERDUE notifies internal AR.VIEW users only | N |
| 86 | AR | Customer page showing balance and history | Partial | `customer-detail.component.html` has no balance or invoice list; you have to open the statement separately | N |
| 87 | AR | Printed payment receipt for the customer | Missing | No RECEIPT DocumentType; `ar-receipt-detail` has no print | N if stream-only |
| 88 | AP | Supplier payments with allocation | Present | `/admin/ap/payments/record` | – |
| 89 | AP | Supplier statement | Present | `/admin/ap/statement` | – |
| 90 | AP | Creditors ageing across all suppliers | Backend-only | `GET /ap/statement/ageing` treats `supplierId` as optional, but web shows ageing only inside one supplier's statement. The menu has AR Ageing but no AP Ageing. | N |
| 91 | AP | Payment voucher / remittance printout | Missing | No document type, no print button | N if stream-only |
| 92 | Cash | Several cash, bank and mobile-money accounts | Present | `/admin/cash/accounts` | – |
| 93 | Cash | Quick expense entry with input VAT, payee and receipt number | Partial | `record-entry` has only account, direction, amount, date, one counter GL account and memo. No VAT split, so input VAT is lost. | N for the VAT split as two GL legs |
| 94 | Cash | Petty cash | Present | `/admin/petty-cash/funds` | – |
| 95 | Cash | Bank reconciliation | Present | `/admin/cash/reconciliations` (manual mark-cleared) | – |
| 96 | Cash | Bank statement import (CSV) | Missing | BankReconciliationController offers only mark-cleared and complete | N if matched in memory |
| 97 | Cash | Cheque register | Present | `/admin/cash/cheques` | – |
| 98 | Cash | Attach photos or files (expense receipts, supplier invoices) | Missing | `MultipartFile` is used only by bulk import | S |
| 99 | Cash | Daily cash-up and cash counts | Present | `/admin/reports/payment-summary`, `/admin/cash/counts` | – |
| 100 | Tax | Journals, trial balance, P&L, balance sheet, cash flow, equity, ledger | Present | `/admin/gl/*`, `/admin/reporting/*` | – |
| 101 | Tax | Period lock and year-end close | Present | `/admin/gl/periods`, `/admin/gl/year-end` | – |
| 102 | Tax | VAT return summary with PDF | Present | `VatReturnController /export`; `/admin/tax/vat-returns` | – |
| 103 | Tax | VAT return schedules for TRA (sales and purchases listing with TIN, VRN, invoice number, EFD number) | Missing | The export contains only the summary face (`VatReturnController.flatten`) | N |
| 104 | Tax | EFD/VFD fiscalisation with TRA | Partial | FiscalReceiptController and the fiscal seam are built (V82). The only providers are `NotConfiguredFiscalisationProvider` and `SimulatedFiscalisationProvider` (`B/platform/fiscal`). POS receipts are non-fiscal. | N (table exists) |
| 105 | Tax | Withholding tax (WHT) types and register | Present | `/admin/tax/wht-*` | – |
| 106 | Tax | Payroll with PAYE, NSSF, SDL, WCF | Present | `/admin/hr/*`, `/reports/payroll-statutory` | – |
| 107 | Tax | Audit trail | Present | `/admin/audit` | – |
| 108 | Reports | Sales by customer, agent, route, branch | Present | `SalesSummaryGroupBy`; `/admin/reports/sales-summary` | – |
| 109 | Reports | Sales and profit by product and department | Present | `/admin/reports/profitability` | – |
| 110 | Reports | Sales register with filters | Present | `/admin/reports/sales` | – |
| 111 | Reports | Dashboard with KPIs and charts | Present | `/admin/dashboard` (BiDashboardController) | – |
| 112 | Reports | Export to PDF, Excel and CSV | Present | `ExportFormat` enum; 24 controllers have `/export` | – |
| 113 | Reports | Scheduled or emailed reports (e.g. daily sales to owner) | Missing | No scheduler or mail for reports | N |
| 114 | Notify | In-app inbox, preferences, delivery log | Present | `/admin/notifications*` | – |
| 115 | Notify | Email to staff | Partial | Sent only when SMTP is configured (`@ConditionalOnBean(JavaMailSender)`), plain text only | N |
| 116 | Notify | SMS | Missing | `NotificationChannel.SMS` is marked "Reserved — not sent in v1" | N (already in the DB CHECK) |
| 117 | Notify | WhatsApp | Missing | Not present anywhere | N (staff) / S (log of messages to customers) |
| 118 | Notify | Clicking a notification opens the source document | Partial | Seeded links such as `/stock/receipts/{sourceUid}` do not match `/admin/...` routes; the inbox component has no router link | N |
| 119 | Branch | Branch switching, per-branch stock and sales, consolidated reports | Present | `X-Branch-Uid`; branch filters on reports | – |
| 120 | Branch | Restrict users to their branches | Present | UserBranchController | – |
| 121 | Users | Roles, permissions, approval policies | Present | `/admin/roles`, `/admin/approvals/policies` | – |
| 122 | Users | Users change their own password | Missing | Only the admin `PUT /users/uid/{uid}/password` (needs USER.MANAGE). There is no "my password" screen. | N |
| 123 | Users | Forgot-password reset | Missing | No endpoint | N if done by the admin through a temporary password |
| 124 | Users | Two-factor sign-in | Missing | Not present anywhere | S |
| 125 | Users | Session timeout, lockout and unlock | Present | `/users/uid/{uid}/unlock` | – |
| 126 | Data | Bulk import of products, customers, suppliers, prices and stock | Present | `/api/v1/bulk/{key}`; `/admin/bulk-import` | – |
| 127 | Data | Export of master lists | Present | `GET /bulk/{key}/export` | – |
| 128 | Data | Import of open AR/AP invoices, chart of accounts, GL opening balances | Missing | Only five import handlers exist (customers, suppliers, products, prices, stock) | N |
| 129 | Data | Automatic backups | Partial | The Docker bundle schedules a nightly backup (`install.sh --backup-time`). The Windows LAN single-jar package (`dist/lan/run`) has no backup script. No in-app "download backup". | N |
| 130 | Mobile | Owner app (dashboard, reports, receive goods, stock adjustment, create item) | Present | `mobile_exec/lib/features/*` (OrbixHQ) | – |
| 131 | Mobile | POS on Android | Partial | Builds and runs, but cannot print (#44) | N |
| 132 | Mobile | Field-sales or van-sales order capture on a phone | Missing | No order capture in OrbixHQ or the POS app; web only | N |

Totals (132 rows): **Present 78, Partial 15, Backend-only 5, Hidden 1, Missing 33.**

## 2. Findings (everything not Present)

Effort: S is under a day, M is 1–3 days, L is 1–2 weeks, XL is more than 2 weeks.

| ID | Capability | Status | Severity | Evidence | Fix direction | Effort |
|---|---|---|---|---|---|---|
| PAR-01 | EFD/VFD fiscalisation with TRA | Partial | **Blocker** (VAT-registered shops) | `B/platform/fiscal/*`: only NotConfigured and Simulated providers. POS receipts are "non-fiscal TRA look" (memory: owner ruled the fiscal block may come ONLY from real fiscalisation). | Implement a real `FiscalisationProvider` for a TRA VFD API (or an EFD device bridge) behind the existing seam and `fiscal_receipt` table (V82). Print the fiscal verification code and QR on the POS receipt and the invoice. | XL (external accreditation) |
| PAR-02 | Record serial/IMEI at sale | Missing | **High** (electronics client) | `PosSaleRequest.LineItem` has no serial. `StockSerialService.issue()` (`StockSerialService.java:52`) has no caller and no endpoint, so sold serials stay IN_STOCK. | Add an optional `serialUids` to the POS line and invoice line request. Carry it in the SaleFinalised/DeliveryConfirmed event payload (JSONB) and call `issue(uid, invoiceUid)` in `SaleIssueStockHandler`. Add a picker to the POS and invoice screens. | M |
| PAR-03 | Receipt printing on Android POS | Partial | **High** | `pos_app/lib/services/receipt_printer_stub.dart`: "only available on the Windows desktop app" | Add an ESC/POS Bluetooth/USB print path for Android (and the Sunmi built-in printer). The receipt text builder already exists. | M |
| PAR-04 | Email or WhatsApp an invoice, quote or statement to the customer | Missing | **High** | `EmailSender.java` sends plain-text notifications to users only; there is no share action in web | Add "Send" on invoice, quote, statement and PO: (a) email with the rendered PDF attached through the existing JavaMailSender; (b) a WhatsApp button building `wa.me/<customer phone>?text=` with a short-lived PDF download link, or the Web Share API. | M |
| PAR-05 | POS offline selling | Missing | **High** (unstable links; a server PC outage stops every till) | `pos_app/README.md` | Queue sales locally under their existing durable idempotency key, replay them on reconnect (the server already de-duplicates), and use the catalogue/price caches already in `state/`. | L |
| PAR-06 | VAT return schedules (TRA sales and purchases listings) | Missing | **High** (monthly VAT filing) | `VatReturnController.flatten`: summary face only | Add XLSX/CSV export of the invoice-level sales and purchase listings for the period (customer/supplier TIN, VRN, invoice number, date, net, VAT, EFD number) in TRA's upload column order. | M |
| PAR-07 | Expense bills posted to a chosen expense account | Backend-only | **High** | `BillMatchServiceImpl.java:519-523` supports `glAccountId` per line; `W/ap/enter-bill.component.html` has no column for it | Add an expense-account picker per non-PO bill line in Enter Bill. Without it every rent, power or fuel bill lands in "Purchases" and distorts COGS. | S |
| PAR-08 | Quick expense entry with input VAT | Partial | Medium | `W/cashbank/record-entry.component.html`: one counter GL account only | Add an optional VAT amount (posts a second leg to Input VAT), payee name and receipt number to the memo/reference. | S–M |
| PAR-09 | Hold (park) and recall a sale at POS | Missing | **High** (queues at the till) | Nothing in `pos_app/lib` | Hold carts client-side (local store of cart state) with a recall list per till. | S |
| PAR-10 | Credit (on-account) sale at POS | Missing | Medium (bars and wholesale) | `TenderType` {CASH, MOBILE_MONEY, CHEQUE, CARD}; paid-in-full invariant | Add a "Charge to account" option that posts through the existing credit sales-invoice path (credit-limit check already exists) instead of a new tender. | M |
| PAR-11 | Return part of a sale at the till | Partial | Medium | `receipt_view.dart`: whole-sale reverse only | Add a line-select return dialog at POS that calls the existing sales-return API. | M |
| PAR-12 | Barcode and shelf-label printing | Missing | **High** (retail) | No label feature | Add a label sheet (A4 grid PDF, plus raw ESC/POS or ZPL for label printers) from a product selection or a goods receipt, showing barcode, name and price. | M |
| PAR-13 | Customer loyalty points | Missing | Medium | `Customer.loyaltyPoints` is never read or written | Simple earn/redeem against the existing column, with the rule in Sales Settings. A points ledger NEEDS-SCHEMA (out of scope). | M |
| PAR-14 | Self-service password change and forgot password | Missing | **High** (shared passwords, admin bottleneck) | `UserController.java:96` is admin-only | Add `POST /auth/me/password` (current plus new password) and a "Change password" item in the user menu. For "forgot", an admin-issued temporary password with forced change. | S–M |
| PAR-15 | Turn reorder suggestions into a draft PO | Partial | Medium | `W/inventory-valuation/reorder-report.component.html` has export only | Add "Create draft PO(s)" from the selected rows, grouped by preferred supplier (`Product.preferredSupplierId` exists). | S–M |
| PAR-16 | Printed customer payment receipt | Missing | **High** (customers paying cash or mobile money expect a receipt) | No RECEIPT DocumentType; `ar-receipt-detail.component.html` has no print | Stream-only PDF render through the existing renderer/letterhead (no `generated_documents` row, which avoids the V19 CHECK). | S |
| PAR-17 | Payment voucher / remittance printout | Missing | Medium | `ap-payment-detail` has no print | Stream-only PDF like PAR-16. | S |
| PAR-18 | Sales order printout | Missing | Low | `sales-order-detail` has no print | Stream-only PDF. | S |
| PAR-19 | Debit-note printout | Missing | Low | DocumentType `DEBIT_NOTE` "NOT rendered" | Stream-only PDF. | S |
| PAR-20 | Credit-note print from the return screen | Hidden | Medium | Only via `/admin/documents` | Add a Print button on `sales-return-detail` calling `documents/render?type=CREDIT_NOTE`. | S |
| PAR-21 | Buyer VRN on the tax invoice | Partial | Medium (compliance) | `DocumentRenderModel.PartyBlock(name, …, taxId)` has no vrn | Add the buyer VRN to the party block from `PartyBase.vrn` (a render-model record, not schema). | S |
| PAR-22 | SMS notifications | Missing | Medium | `NotificationChannel.SMS` is "Reserved — not sent in v1" | Add an SMS sender (Beem, NextSMS or Africa's Talking) on the existing delivery row. The channel is already allowed by the DB CHECK. | M |
| PAR-23 | WhatsApp notifications | Missing | Medium | None | Phase 1: share links (PAR-04). WhatsApp Business API for messages to customers NEEDS-SCHEMA for the message log (out of scope). | M / S |
| PAR-24 | Overdue reminders to customers (dunning) | Missing | Medium | INVOICE_OVERDUE goes to internal users only | Add "Send reminder" (email/SMS/WhatsApp) from AR ageing using PAR-04/PAR-22. | S after PAR-04 |
| PAR-25 | Creditors (AP) ageing across all suppliers | Backend-only | Medium | `ApStatementController.java:84` (supplierId optional); no screen | Add an "AP Ageing" screen mirroring AR Ageing, and a menu item. | S |
| PAR-26 | Customer contacts and addresses | Backend-only | Low | `CustomerContactAddressController`, `SupplierContactAddressController` | Add tabs on the customer and supplier detail pages. | S–M |
| PAR-27 | Supplier bank accounts | Backend-only | Low | `SupplierBankAccountController` | Add a tab on supplier detail; show the account on the payment voucher (PAR-17). | S |
| PAR-28 | Payment-terms master | Backend-only | Low | `PaymentTermsController` | Add a settings screen and a terms picker on customer and supplier. | S |
| PAR-29 | Customer page with balance and history | Partial | Medium | `customer-detail.component.html` has no balance or transactions | Add a balance card (`/ar/balance`) and recent invoices/receipts, plus a link to the statement. | S |
| PAR-30 | Expiry alerts | Missing | Medium (liquor, pharmacy, FMCG) | No EXPIRY type in `NotificationTypeSeeder` | Add an EXPIRING_BATCH scan alongside LOW_STOCK. First verify that no CHECK constraint lists the notification type keys. | S |
| PAR-31 | Multi-line stock adjustment voucher | Missing | Medium (the memory notes item-wise adjustment as a go-live gate) | `AdjustStockRequest` takes a single product | Add a batch endpoint that posts N adjustments under one reference, plus a printable PDF. | M |
| PAR-32 | Product variants | Missing | Low (electronics/liquor: low; apparel: high) | `Product.java` | NEEDS-SCHEMA (out of scope). Workaround: code-suffix convention plus bulk import. | – |
| PAR-33 | Category and brand masters | Partial | Low | Free-text strings (Product.java:183,192) | Offer a pick-list of distinct existing values to stop "Beer", "beer" and "BEER" drift. A hierarchy NEEDS-SCHEMA (out of scope). | S |
| PAR-34 | Product photo upload | Partial | Low | URL field only | NEEDS-SCHEMA (out of scope; needs a file store). | – |
| PAR-35 | Attachments (expense receipts, supplier invoices) | Missing | Medium | No upload outside bulk import | NEEDS-SCHEMA (out of scope). | – |
| PAR-36 | Bank statement CSV import | Missing | Low–Medium | BankReconciliationController offers only mark-cleared and complete | Upload a CSV, match in memory to uncleared transactions by amount and date, and pre-tick them (nothing persisted). | M |
| PAR-37 | Sales commission | Missing | Low–Medium | None | One flat % in Sales Settings applied to the Sales Summary by AGENT (no schema). Per-agent rates NEEDS-SCHEMA (out of scope). | S |
| PAR-38 | Gift cards and vouchers | Missing | Low | None | NEEDS-SCHEMA (out of scope). | – |
| PAR-39 | Warranty tracking | Missing | Low–Medium (electronics) | None | NEEDS-SCHEMA (out of scope). After PAR-02, a sold-serial lookup gives the sale date. | – |
| PAR-40 | Mobile-money integration | Partial | Low–Medium | One manual tender | Phase 1 (no schema): capture the network and reference in the tender reference or notes and split the cash-up by network. API integration NEEDS-SCHEMA (out of scope). | S / – |
| PAR-41 | Hide unused modules / simple mode | Missing | **High** (perceived as "complex, missing basics" because the basics are buried) | About 150 menu items (SH:113–493) | Ship a deployment property (e.g. `erp.ui.modules`) served through SpaWebConfig that hides groups such as HR, Manufacturing, Projects, Budgeting, CRM, FX and Fixed Assets. Add a "Retail Starter" role bundle. Per-company toggles NEEDS-SCHEMA (out of scope). | S–M |
| PAR-42 | Swahili user interface | Missing | Medium (cashiers and storekeepers) | No i18n | Translate the POS first (one ARB file, small string set), then the core web screens. | L |
| PAR-43 | Guided in-app setup | Partial | Medium | Home cards only | Add a "Getting started" checklist computed from data that already exists (company TIN/VRN set? tax rates? opening stock? a till?). | S–M |
| PAR-44 | Import open AR/AP invoices and GL opening balances | Missing | Medium (onboarding from QuickBooks, Tally or Excel) | Five import handlers only | Add bulk-import handlers `ar-opening`, `ap-opening` and `gl-opening` that call the existing opening-balance services. | M |
| PAR-45 | Backups for the LAN single-jar install | Partial | **High** (data loss risk at Windows-hosted clients) | `dist/lan/run` has install, start, stop and uninstall only | Add `backup.cmd` (pg_dump with rotation) plus a scheduled task on install, mirroring the Docker bundle. | S |
| PAR-46 | Scheduled or emailed reports | Missing | Low–Medium | None | A daily-sales email to the owner via `@Scheduled` and the existing exporters. Recipients come from a property for now. | M |
| PAR-47 | Field-sales / van-sales mobile order capture | Missing | Medium (route-sales clients) | No order screen in OrbixHQ or the POS app | Add an order-taking screen to OrbixHQ against the existing SO API. | L |
| PAR-48 | Notification click-through to the source document | Partial | Low | Seeded links (`/stock/receipts/...`) do not match `/admin/...` routes; the inbox has no link | Map type keys to admin routes on the web side. | S |
| PAR-49 | Email to staff only when SMTP is configured | Partial | Low | `@ConditionalOnBean(JavaMailSender)` | Add an SMTP settings check and a "Test email" action to the notification admin page, and document the setup for LAN installs. | S |
| PAR-50 | Two-factor sign-in | Missing | Low | None | NEEDS-SCHEMA (out of scope). | – |

## 3. Top 10 basics missing (ranked for a Tanzanian retail or wholesale shop)

1. **No real TRA EFD/VFD fiscal receipts** (PAR-01). The seam exists but only the simulated provider ships. A VAT-registered shop still needs a separate EFD machine and has to key every sale twice.
2. **Invoices, quotes and statements cannot be sent by email or WhatsApp** (PAR-04, PAR-24). Every competitor has "Send"; here the user downloads the PDF and attaches it by hand.
3. **Serial/IMEI numbers are never recorded at sale** (PAR-02). The electronics client's serial register shows sold phones as still in stock, and there is no warranty lookup.
4. **Android tills cannot print receipts, and the POS stops when the server is unreachable** (PAR-03, PAR-05). Loyverse and Odoo POS both print on Android and sell offline.
5. **No hold/recall, no partial return and no credit sale at the till** (PAR-09, PAR-11, PAR-10). These are day-one POS basics.
6. **No barcode or shelf-label printing** (PAR-12). Retailers re-label stock at every goods receipt.
7. **No printed payment receipt for customers and no payment voucher for suppliers** (PAR-16, PAR-17). Customers paying cash or mobile money get nothing on paper from the back office.
8. **Expense bookkeeping is broken from the UI.** Non-stock bills cannot choose an expense account, so everything posts to "Purchases" (PAR-07), and cash expenses cannot claim input VAT (PAR-08). Add the missing VAT purchase and sales schedules for the monthly TRA return (PAR-06).
9. **Users cannot change their own password, and there is no forgot-password path** (PAR-14). Combined with about 150 menu entries and no simple mode (PAR-41), basic tasks feel buried, which matches the "missing basics" complaint.
10. **No backup on the Windows LAN install** (PAR-45), and no SMS, WhatsApp or expiry alerts (PAR-22, PAR-30). Owners expect "low stock / expiring / day's sales" on their phone.

Notes:
- Almost all of the top 10 can be fixed without schema changes. The exceptions are the TRA provider work, which is external rather than schema, and warranty, gift cards, photos, attachments, variants and 2FA, which are NEEDS-SCHEMA.
- "Present" was verified as backend endpoint + `admin.routes.ts` route + menu entry in SH. The POS was verified from `pos_app/lib` source. Nothing was run.
