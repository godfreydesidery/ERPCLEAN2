# Gap review 2026-10-10 — client-reported "missing basics and hardships"

Adversarial review of `develop @ f4de0c94`, requested after clients (Kilimanjaro Star Liquor Store,
SAM Electronix) reported missing basic features and difficulty performing daily operations.

- **How it was done:** 12 static reviewers (one per business area) read the code end to end;
  4 live testers drove a throwaway copy (fresh DB, develop build) as a shop-front clerk, a back-office
  storekeeper, 11 non-root staff roles, and through the real web UI in a headless browser.
- **Constraints:** no schema changes. Items that need a migration or a permission-seed edit are tagged
  `[SCHEMA]` / `[SEED]` and are out of scope until the owner approves them.
- **Evidence:** every finding cites `file:line`; see the area files in this folder.

| Area file | Prefix | Area file | Prefix |
|---|---|---|---|
| [sales.md](sales.md) | SAL | [ap.md](ap.md) | AP |
| [purchases.md](purchases.md) | PUR | [accounting.md](accounting.md) | ACC |
| [stock.md](stock.md) | STK | [products.md](products.md) | PRD |
| [pos.md](pos.md) | POS | [reports.md](reports.md) | RPT |
| [ar-cash.md](ar-cash.md) | ARC | [admin-ux.md](admin-ux.md) | ADM |
| [parity.md](parity.md) | PAR | [open-reported.md](open-reported.md) | OPN |
| [live-shopfront.md](live-shopfront.md) | LSF | [live-backoffice.md](live-backoffice.md) | LBO |
| [live-rbac.md](live-rbac.md) | LRB | [live-ui.md](live-ui.md) | LUI |

## Fix tracking

Wave 1 (2026-10-10, no schema) is integrated on `integration/gap-review-wave1`: 1,735 unit + 1,341
integration tests, 221 web spec files and the production build all green. It reaches `develop`
only with the owner's OK.

| Package (branch) | Fixed | Not done (carried to wave 2) |
|---|---|---|
| `fix/gl-fiscal-year` | ACC-01 (next FY auto-opened at boot + daily), ACC-09, ACC-15, ACC-16, ACC-20, ACC-21, ACC-29 | — |
| `fix/stock-count-cash-count` | LBO-03, STK-02, ARC-01 (count on the sales cash account refused) | Historic LBO-03 repair: `docs/ops/lbo-03-double-posted-stock-counts.sql` |
| `fix/sales-pack-units` | SAL-01/LSF-01, SAL-02 (void & refund), SAL-07, SAL-17, RPT-29 | RPT-01/LSF-11, RPT-02, RPT-16/LSF-12; invoices already raised from old pack deliveries need manual correction |
| `fix/purchasing-pack-units` | PUR-01/LBO-05/LUI-02, PUR-02/LBO-01/LBO-02, PUR-03, PUR-09/PUR-28, PUR-11, LBO-08, OPN-13 (void blocked after sale) | PUR-04/LBO-04 (void after billing), PUR-08 (picker search), PUR-25 reason |
| `fix/ap-billing` | AP-01, AP-05, AP-06/LBO-07, AP-08, AP-09, AP-14, AP-17, AP-18, AP-20, AP-28 | AP-07, AP-10, AP-11/RPT-03/LBO-14, AP-12, AP-16 |
| `fix/stock-operations` | LUI-01, STK-01/LBO-06, STK-05 (ruling), STK-07/STK-23/LBO-29, STK-15, STK-17 (partial), STK-20, STK-26, STK-06/OPN-02 (sellability) | STK-06 in-transit column, STK-10/LBO-16, STK-13, STK-19, ADM-29, adjust location picker on toolbar |
| `fix/ar-cash` | ARC-02, ARC-03, ARC-09, ARC-18/LSF-18, ARC-33 | ARC-05, ARC-06, ARC-12/LBO-17, ARC-15, ARC-17, ARC-19 (needs cheque API), ARC-20, ARC-21, ARC-29 |
| `fix/price-list-resolution` | PRD-01/SAL-04/POS-12/LSF-02 (customer price → customer list → company default → old fallback), PRD-02 contract prices | PRD-02 tiers/promotions (owner decision), PRD-25 scheduled prices (schema), SAL-28; **POS app must re-price for account customers** |
| `fix/role-gates-ui-blockers` | LRB-01, LRB-03/ADM-01, LRB-04, LRB-05, LRB-07, LRB-14, ADM-18, LUI-16, LSF-04/LUI-06 (root → Counter agent); backend gates for LRB-02/LRB-10/LRB-11 | Picker "no access" notice on van recon, batch/serial, petty cash, Enter Bill (ADM-28); LUI-03 downloads; money-parser sweep (LUI-04) on invoice/POS/GRN/payment inputs |
| `fix/report-scope-margin` | RPT-06/LRB-06 (staff see their branches), ADM-14 (cost/margin need INVENTORY.VALUATION.VIEW), RPT-08, RPT-05 on sales exports | RPT-05 headers on other exports, OrbixHQ scope label, branch picker list for branch-limited users |
| Seed grants (owner-approved) | BRANCH_MANAGER +7, ACCOUNTANT +5, SALES_MANAGER REPORT.EXPORT + INVENTORY.VALUATION.VIEW, PRODUCTION_MANAGER STOCK.LOCATION.VIEW | — |

### Wave 3 (2026-10-10) — books and money

Green: 1,840 unit + 1,404 integration tests, 237 web spec files, production build, migration gate.
Owner-approved schema/seed: **V106** (widens `chk_cash_transaction_type`; explicit `LOCK TABLE …
ACCESS EXCLUSIVE` + `lock_timeout 10s`) and codes `AR.RECEIPT.REVERSE`, `AP.PAYMENT.REVERSE`, `AR.REFUND`
(→ ACCOUNTANT, FINANCE_DIRECTOR). Time ruling: store UTC; derive business dates and display in the
company time zone.

| Package (branch) | Fixed |
|---|---|
| `fix/w3-timezone` | ACC-03, SAL-23, LSF-15, PUR-15, RPT-09, RPT-21, ADM-26, ADM-27 (main screens), print dates |
| `fix/w3-sales-postings` | SAL-06/ACC-04 (deposit to cash), ACC-05/LSF-06/POS-09 (tender → its own GL), SAL-03 (void clears AR) |
| `fix/w3-purchase-fx` | PUR-07/ACC-08, bill FX stamp, AP-15/LBO-09, PUR-14/LBO-10, ACC-17/LBO-13/PUR-21, PUR-22, AP-23 |
| `fix/w3-tax` | ACC-06/ACC-24 (VAT nets credits/debits/voids; GL clears), ACC-07 (pay VAT/WHT/PAYE/NSSF/SDL/WCF), RPT-14/PAR-06 schedules, ACC-13 VAT on cash expenses |
| `fix/w3-gl-exceptions` | ACC-02 (posting exceptions + exactly-once re-post), ACC-19, ACC-14, ACC-18, ACC-26, ACC-23 |
| `fix/w3-reversals` | ARC-04 receipt reversal, ARC-10/ACC-12 petty cash to GL |
| `fix/w3b-cashbook-refunds` | ARC-08 (sales, voids, payouts, over/short in the cash book), ARC-01 cash counts re-enabled (go-live gap carried), AP-03, ARC-11, bounced-cheque cash row |

**Wave-3 deploy cautions:** boot V106 against a restored customer DB first; post a one-off opening
journal per existing petty-cash fund (DR Petty Cash / CR source); stop hand-keying CREDIT_NOTE_VAT /
DEBIT_NOTE_VAT adjustments (user manual 08-finance.md:950 is outdated); legacy USD goods receipts need a
GRNI/Inventory repair; night sales (00:00–03:00 EAT) now post to the EAT day; manual reversal of system
journals is refused; control accounts are refused on bill lines.

### Wave 2 (2026-10-10, no schema) — on `develop` 53650e48

Green: 1,804 unit + 1,360 integration tests, 229 web spec files, production build, migration gate.

| Package (branch) | Fixed | Not done (carried to wave 3) |
|---|---|---|
| `fix/w2-units-and-stock` | STK-08/OPN-01 (packs on adjust, count, opening), PRD-07/LSF-09 (valued opening stock), toolbar adjust location, STK-10/LBO-16, STK-13, STK-19, PUR-08, STK-06 in-transit column, ADM-29 menu entry | Packs on PO receipts, adjustment register |
| `fix/w2-ap` | AP-07, AP-10, AP-11/RPT-03/LBO-14/LBO-15, AP-12, AP-16, PUR-04/LBO-04 (+ bill line foreign-account security fix) | BillMatch FX stamp dropped on saved USD bills |
| `fix/w2-ar-cash` | ARC-05, ARC-06, ARC-12/LBO-17, ARC-15, ARC-17, ARC-20, ARC-21, ARC-29 | ARC-04 receipt reversal (owner approved new code `AR.RECEIPT.REVERSE`) |
| `fix/w2-sales-reports` | RPT-01, RPT-02, RPT-16, SAL-10, SAL-12, SAL-13, SAL-28, RPT-05 (all exports) | — |
| `fix/w2-products-ux` | PRD-06/LSF-03, PRD-03/04/05/33, PRD-08, PRD-12/17, ADM-02 (self password change + forced change), LUI-04 + ADM-28 sweeps | PRD-09 branch price not yet charged |
| `fix/w2-pos` | Customer pricing on till + web POS, POS-02/04/05/06/07/14/15/17/18 | POS version bump + release; refuse unapproved payouts once tills upgraded |
| `fix/w2-purchase-return-print` | Kilimanjaro sheet row 9: purchase return print + Excel/CSV | — |
| Seed grants | STOREKEEPER INVENTORY.OPENING.SET | — |

**Wave-2 deploy cautions:** deploy the server together with the new POS build — 1.5.4 tills lose the
Refund payout for non-supervisors and show "Expected cash 0.00" to cashiers until upgraded; admin-
created/reset users must change password at next web sign-in; reports now show VAT-inclusive
discounts, base-unit quantities and current product names; more items will show as Low stock.

**Deploy cautions:** the deployed till shows walk-in prices but the server now charges account
customers their own price; the default till can no longer be cash-counted; stock stranded in
TRANSIT before the 2026-09-29 fix is no longer sellable; check in-flight transfers; purchase-return
drafts on pack lines saved before this change must be re-entered.

Everything not listed above is still open; the consolidated list below sets the order for later waves.

### Owner rulings (2026-10-10)

| Question | Ruling |
|---|---|
| Report/BI scope for branch-restricted staff (RPT-06, LRB-06) | "All branches" means the user's assigned branches; owner/root and company-wide users see and filter everything |
| Cost and margin visible to cashiers/salespeople (ADM-14) | Hidden; requires `INVENTORY.VALUATION.VIEW` |
| Counter/closed-session POS returns (SAL-02) | Full void-with-refund now (no schema); partial returns later |
| Void a GRN after some of its stock was sold (OPN-13) | Blocked; correct with a purchase return or stock adjustment |
| Who may dispatch/receive a transfer (STK-05) | Dispatch from the source branch, receive in the destination branch; root exempt |
| Role-bundle grants in `R__seed_permissions.sql` | Owner reviews the exact grant list before any seed edit |
| OrbixPOS till fixes (POS-02/04/05/06/07/15…) | Next wave, with a new POS build (not delivered until the owner says) |
| Integration | Commit and merge locally into `develop`; the owner pushes |

---

## P0 — corrupts money / stock / books, or blocks a daily operation (fix first)

### A. Packs vs pieces (cartons/crates) — the systemic root of the "daily adjustments" complaint
| ID(s) | Defect | Where |
|---|---|---|
| SAL-01, LSF-01 | SO → delivery → invoice bills base qty × pack price (1 crate invoiced as 25 crates, live: 1,437,500 vs 57,500) | DeliveryServiceImpl.java:169,222,407-416; delivery-create.component.ts:78 |
| PUR-01, LBO-05, LUI-02 | Receive-against-LPO prefills/shows pieces under a Carton header → "Over-receipt rejected", or silently books 12× stock | goods-receipt-create.component.ts:210, .html:177-180 |
| PUR-02, LBO-01, LBO-02 | Purchase return: 1 crate returned removes 1 bottle at crate value (avg cost corrupted); over-return check lets 150 crates through on an 8-crate GRN | PurchaseReturnServiceImpl.java:131,147,207 |
| AP-06, PUR-06, LBO-07 | 3-way match compares billed crates vs received bottles → over-billing passes as MATCHED | BillMatchServiceImpl.java:358,384-386 |
| PRD-06, LSF-03 | Crate barcode added in Product Master loses its unit (`uomUid` vs `unitUid`) → POS scans a crate as 1 bottle | product.model.ts:244 / product-master.ts:1043 vs AddBarcodeRequest |
| RPT-01, LSF-11 | Sales/Profitability "qty sold" adds crates+bottles | SalesReportQuery.java:174; ProfitabilityReportQuery.java:157 |
| LBO-08 | Printed GRN margin = crate cost vs bottle SP (−1,700%) | GoodsReceiptPrintQuery.java:222,251 |
| STK-08, OPN-01, LBO-21 | Adjust / count / opening balance accept base units only | AdjustStockRequest, EnterCountRequest, OpeningBalanceRequest |
| LSF-22, LBO-20, POS-19 | Bottles/crates seeded fractional → 0.5 bottle sells/adjusts | UnitOfMeasureSeeder.java:40-54 |

### B. Books silently wrong
| ID(s) | Defect | Where |
|---|---|---|
| LBO-03 | Every stock-count variance posts to GL TWICE (per-line + net journal). Live: JB-0051/0052 | StockCountServiceImpl.java:270-286 + InventoryValuationServiceImpl.java:548 |
| ARC-01 | Cash count on the main cash account expects only the cash book (sales never write it) → whole day's takings booked as "cash over" income | CashCountServiceImpl.java:114; GLPostingSafeInvoker.java:110-112 |
| ACC-01 | **Deadline 1 Jan 2027 (~83 days):** only the creation year is ever seeded; no FY2027 → sales/COGS journals dropped, receipts/adjustments fail with accountant jargon | FiscalCalendarServiceImpl.java:159-166 (only caller: CompanyProvisioningServiceImpl:173) |
| ACC-02 | Automatic GL posting swallows failures (closed period, no FY, overlap…) — no exceptions list, no re-post | GLPostingSafeInvoker.java:133-137,176-180; SalesPostingHandler.java:76-95 |
| ACC-09 | Overlapping fiscal years allowed → every posting fails | FiscalCalendarServiceImpl.java:56-61 |
| SAL-06, ACC-04 | Credit sale with counter deposit: GL debits AR for full gross, cash never booked; AR ≠ GL | GLPostingSafeInvoker.java:105-119; ArSalePostedHandler.java:169-172 |
| SAL-03 | Voiding an on-account invoice leaves the AR open item owed | SalesInvoiceServiceImpl.java:571-579 (no AR SALE.VOIDED consumer) |
| PUR-03, PUR-04, LBO-04, OPN-13 | GRN can be voided after it was billed / returned / partly sold → AP owes for stock that's gone, returns double-reverse | GoodsReceiptServiceImpl.java:263-304 |
| AP-05 | Same GRN line can be billed twice | BillMatchServiceImpl.java:381-387 |
| PUR-07, ACC-08 | USD GRN valued at face amount as TZS | GoodsReceiptServiceImpl.java:464/500; GoodsReceiptStockHandler.java:181 |
| AP-15, PUR-14, LBO-09/10 | Purchase-return debit note credits Purchases (not GRNI) and carries no VAT → profit overstated, input VAT over-claimed | PurchaseReturnServiceImpl.java:231-265 |
| ACC-03, SAL-23, LSF-15, PUR-15, RPT-09 | Posting dates / VAT window / BI in UTC → 00:00-03:00 EAT sales land in previous day/month | SalesPostingHandler.java:115; SalesInvoiceServiceImpl.java:1097 … |
| STK-02 | Stocktake variance computed against LIVE qty at post time → sales between count and post become phantom stock | StockCountServiceImpl.java:223-228 |
| STK-06, OPN-02, STK-07, LBO-29 | In-transit (and quarantine/van) stock counts as destination's sellable stock; transit location found by guesswork and can be hijacked | StockReservationServiceImpl.java:88-114; LocationResolver.java:73-87 |
| PRD-07, LSF-09, LUI-13 | Opening stock from Product Master posted at zero value | product-master.ts:1154-1185; StockServiceImpl.java:220 |

### C. Pricing doesn't work as configured
| ID(s) | Defect |
|---|---|
| PRD-01, SAL-04, POS-12, LSF-02 | Price resolution = first price row created; customer default price list, company default list, archived/effective dates all ignored. Live: bar on WHOLESALE charged retail |
| PRD-02 | "Pricing Rules" (customer prices, tiers, promos) menu saves rules that are never applied |
| SAL-05 | Sales orders/quotes bypass price-override permission, discount ceiling, below-cost check (price 0 allowed) |
| PRD-09, PRD-25 | Branch price / effective-from typed but discarded |

### D. Daily operation blocked
| ID(s) | Blocked operation |
|---|---|
| LUI-01 | Stock adjustment toolbar & "Set to counted qty" can't save (ngModel in form without `name` — NG01352). Verified |
| STK-01, LBO-06 | After any transfer, adjust / import / opening balance refused for that product at the receiving branch ("held at more than one location") |
| SAL-02, LSF-05 | No return/refund for a counter (DIRECT) invoice or a POS sale after its session closed [partial return-against-invoice = SCHEMA; full void-with-refund = no schema] |
| LSF-04, LUI-06 | Fresh install / owner cannot ring the first sale: invoice needs an agent, root can't be one, cashier setup has no agent step |
| ARC-02 | Record Receipt allocation grid shows OTHER customers' invoices; customer/status filters ignored on Receivables & Receipts (customerUid vs customerId) |
| ARC-03 | Credit Note button always fails "Customer not found" |
| AP-01 | Held / failed-match supplier bill is stranded: no re-match/edit/delete, duplicate guard blocks re-entry |
| LUI-03 | "Something went wrong — Fiscal receipt not found" modal on every finalised invoice & unknown barcode |
| LUI-04, ADM-25 | "1,800" rejected with leaked Java text; AR receipt "68,300" silently read as 0 |
| ARC-09 | Bank reconciliation rejected from the 2nd month onwards |
| ACC-10 | Manual journal impossible once any dimension is mandatory (form has no dimension field) |
| AP-12, ARC-17 | USD supplier/customer opening balance can't be saved |
| AP-17 | Supplier payment success screen breaks ("undefined payment(s)") |

### E. Staff (non-root) can't do their job — the "works for root" trap
| ID | Role → blocked task | Fix |
|---|---|---|
| LRB-01 | Procurement can't set selling prices (price-list lookup 403) → items reach till unpriced | widen read gate or [SEED] |
| LRB-02 | Field agent: van reconciliation unusable | widen gate or [SEED] |
| LRB-03, ADM-01 | Cashier: web cash count till dropdown empty | narrow till lookup |
| LRB-04 | Accountant/FD: Enter Bill can't load PO lines / GRNs | add AP.BILL.ENTER to 3 gates (no seed) |
| LRB-05 | Fully cash-paid sale to over-limit customer refused as "no permission" | assess gross − payments |
| LRB-07 | Storekeeper: bulk stock import hidden from menu | shell.component.ts:169 |
| LRB-08/09, ADM-12/13 | Accountant can't see sales/POS/stock; Branch manager can't receive transfers; sales manager can't export | [SEED] |
| LRB-10/11, ADM-10/11 | Batch/serial location pickers, petty-cash expense accounts, POS session cashier names empty | gate/DTO |
| ADM-28 | Root cause: interceptor passes 403 silently and 134 handlers swallow errors → empty dropdowns, no message | picker "no access" state + per-role smoke test |

### F. Controls (cash leakage / fraud)
| ID(s) | Gap |
|---|---|
| POS-02 | Till "Refund" payout: cash out with only a typed reason, no approval, no GL/stock/VAT reversal; manual tells cashiers to use it |
| POS-03 | Safe/drawer drop booked as an expense [clean DROP type = SCHEMA; flag + GL key = no schema] |
| POS-04 | Cashier can read "Expected cash" before counting (blind cash-up defeated) |
| POS-05 | Paid-outs/expenses: no manager, no limit |
| LSF-07 | Credit limit bypassed by back-to-back invoices (exposure reads AR, filled ~1 s later via outbox) |
| ARC-07 | Credit hold/stop can't be set and isn't enforced on counter/POS |
| PUR-05 | PO lines editable after approval; placement only checks the flag |
| STK-05 | Storekeeper can create, dispatch AND receive a transfer into another branch |
| RPT-06 | Branch-restricted users see every branch by clearing the filter (owner ruling) |
| ADM-14 | Cashier/salesperson can see cost & margin (profitability gated on SALES.INVOICE.VIEW) — owner ruling |
| POS-07 | POS screen total ≠ receipt total on discounted lines (gross vs net discount) — breaks exact M-Pesa payment |

---------------------------------------------------------------------------------------------------
## P1 — missing basics clients will expect (no schema)

- **Undo/correct:** reverse customer receipt (ARC-04), reverse supplier payment (AP-03), cancel draft invoice (SAL-13/LSF-17), cancel draft purchase return (LBO-26), recall dispatched transfer (STK-04), short/damaged transfer receipt (STK-03/LBO-11), edit draft lines (SAL-12/PUR-29).
- **Money in/out:** choose cash/bank/M-Pesa account on receipts & payments (ARC-05, AP-08, SAL-15); tender → its own account instead of all to Cash (ACC-05, POS-09, LSF-06 — per-tender GL keys are [SCHEMA], routing via cash_bank_account is not); supplier advance (AP-02); apply on-account money later (ARC-06); customer refund (ARC-11); part-pay on Record Payment (AP-07); mobile-money reference kept (ARC-18, LSF-18); bill due date from terms (AP-09); supplier edit wipes terms (AP-10).
- **Expenses:** findable expense entry defaulting to OUT with a list and undo (ARC-12, LBO-17); expense account per bill line (AP-16/PAR-07); petty cash posts to GL (ARC-10/ACC-12); input VAT on cash expenses (ACC-13); record paying VAT/PAYE/NSSF/WHT (ACC-07); VAT return includes credit notes/returns/voids (ACC-06/ACC-24).
- **Who owes / whom I owe / cash today:** creditors ageing all suppliers (AP-11/RPT-03/LBO-14), daily cash report per branch (RPT-04/ARC-08), returns netted in sales reports (RPT-07/LSF-13), transfer register (RPT-12), voids/discounts + over/short reports (RPT-10/11), customer balance on customer screen (ARC-21/PAR-29).
- **Printing & sharing:** thermal/inline receipt for web POS & invoices (ADM-03), customer payment receipt, payment voucher, sales return/credit note, count sheet, Z-read (ADM-04/PAR-16/17/20), buyer TIN/VRN + local dates + "0.18%" VAT fix on tax invoice (SAL-20/LSF-16/LUI-09/PAR-21), email/WhatsApp send (PAR-04).
- **POS:** hold/park sale (POS-06), on-account sale (POS-08), partial / next-day return (POS-10), manager open-price for unpriced item (POS-11), 3 HTTP calls per scan + stock query per keystroke (POS-13), walk-in fallback to a real customer (POS-14), customer carries to next sale (POS-15), keyboard shortcuts (POS-23), Android printing (PAR-03). Offline selling (POS-01) is L.
- **Products / go-live:** Product Master drops category/brand/tracking/etc. (PRD-03/LSF-08); detail-screen save wipes reorder/supplier/18+ (PRD-05); serial/lot tracking inert, sale never consumes serials (STK-09/PRD-04/PAR-02 — SAM); Excel mangles barcodes (PRD-08); no pack-size import (PRD-10); re-upload duplicates customers (PRD-12); bulk-import all-or-nothing (PRD-18); reorder level on product ignored (STK-10/LBO-16); mass price change can't target groups and hits crate prices (PRD-13); price history (PRD-14/RPT-23); default sale/purchase unit (PRD-24); copy product (PRD-27).
- **Admin/UX:** self-service password change (ADM-02/PAR-14); staff must type `name@default-organisation` (LUI-16); 4-form onboarding (ADM-15/LRB-16); ~145-item menu, no simple mode, home page with no shop actions (ADM-17/21, LUI-14, PAR-41); date filter/sort/export on lists (ADM-05/06); broken status filters (SAL-10/11, ADM-07/08, PUR-11); 100/200-row picker caps on returns, landed cost, bills, AR (PUR-08, AP-21, ADM-09, SAL-21); unsaved-work loss (ADM-23); UTC default dates in 55 web places (ADM-26); phone layout hides numbers (LUI-07); LAN Windows install has no backup (PAR-45).

## P2 — polish / lower impact
Raw enum/jargon errors (SAL-26, PUR-36, AP-30, ACC-21, LRB-14), raw ISO timestamps (ADM-27, LBO-22/23), N+1 list queries (SAL-30), notification `{documentNo}` placeholder (LBO-25), OpenAPI wrong purchase-return shape (LBO-30), thin default COA (ACC-27), etc. — see area files.

---------------------------------------------------------------------------------------------------
## Owner rulings needed before some fixes
1. Branch visibility: should "All branches" be allowed for branch-restricted staff? (RPT-06 / LRB-06)
2. Cashier/salesperson seeing cost & margin (ADM-14).
3. Role-bundle additions [SEED]: BRANCH_MANAGER (transfer receive, AR statement, returns), ACCOUNTANT (sales/POS/stock view), SALES_MANAGER (REPORT.EXPORT), PROCUREMENT (PRICELIST.VIEW), FIELD_SALES (STOCK.LOCATION.VIEW).
4. GRN void after partial sale/billing: block, or allow with override? (OPN-13)
5. SAM: serial vs batch for sell-by-identifier.
6. Kilimanjaro VAT registration + "VAT included" purchase setting (ADR-0063).
7. Counter-sale return: accept full void-with-refund now (no schema), partial return later [SCHEMA]?
8. Fiscalisation (TRA VFD) provider and date — legal gate (OPN-05/PAR-01).
9. Historical transfer-cost data repair (OPN-15) — confirm whether it was done.

## Delivery / process findings (not code)
- SAM Electronix prod: last recorded deploy main@621a8cda (2026-08-01, V92) — ~268 commits and 13 migrations behind, including fixes SAM asked for. Upgrade crosses V93–V105 + multitenancy → boot-rehearse on a restored copy first.
- Kilimanjaro: confirmed on 1.10.0; 1.10.1–1.10.4 built but no install recorded (incl. the transfer fix for their 10-02 complaint).
- OrbixPOS 1.5.4+12 published, no till install recorded; never tested on a real thermal printer.
- Config not done: Kilimanjaro "VAT included"; SAM company address/TIN/VRN; walk-in customer + cashier↔agent links.
- LICENSE.txt in the shipped bundle is still an unreviewed template.

## [SCHEMA] items (reported, out of scope)
Partial return against an invoice (sales_returns.delivery_id NOT NULL); BANK_TRANSFER sales tender (V5 CHECK); Mobile Money cash account type; per-tender GL config keys (chk_gl_config_key); DROP payout type (V94 CHECK); GRN supplier-invoice-no / FX rate / bonus qty columns; product variants, photos, attachments, gift cards, warranty, 2FA; stock valuation as at a past date; per-customer overdue days.
