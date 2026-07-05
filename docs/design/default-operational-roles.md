# Design note — default operational role bundles

- **Status:** Proposal for owner approval (precedes any migration). Pairs with **ADR-0057** (draft).
- **Author:** Solutions Architect
- **Date:** 2026-07-05
- **Context source:** owner decision (2026-07-05) to ship working roles out of the box; persona-UAT
  finding [[rbac-no-operational-role-bundles]]; ADR-0002 (RBAC), ADR-0047 (read-closure), ADR-0043
  (schema freeze / durable DB), ADR-0001 (roles org-wide).

> **DESIGN ONLY.** No code, no migration, no seed edit is written by this note. The SQL below is an
> illustrative *shape*; the actual migration (an edit to the repeatable RBAC seed) is authored only
> after the owner approves this proposal and the DDL/version per the standing migration-approval rule.

---

## 1. Problem & the model we build on

The product seeds exactly one role — **`ORG_ADMIN`** (row in `V1__baseline.sql`; its grants filled by
`R__seed_permissions.sql` via a `CROSS JOIN` over every permission, so it self-heals and holds the
whole catalogue). Every *operational* role (Salesperson, Cashier, Accountant, …) must be hand-built by
each tenant. Fresh tenants — and our persona-UAT harness — therefore hit 403s on core jobs because the
roles don't exist; `app_user.is_root` masks it (root bypasses every check).

Two invariants shape the fix:

1. **Roles are org-wide** (ADR-0001 D-A; `roles` has **no `company_id`**). A role is defined once for
   the whole organisation; a user is granted it **per company (and optionally per branch)** through
   `user_role`. So default roles are seeded **once, org-wide**, exactly like `ORG_ADMIN` — not per
   company. Every company's admin sees and assigns the same shared catalogue.
2. **`is_system` roles are undeletable** (BR-7). The defaults are `is_system = true`.

Consequence of (1): seeding N default roles is a fixed set of `roles` rows + `role_permission` grants,
independent of how many companies exist — a perfect fit for the convergent repeatable seed.

---

## 2. Proposed role set (12 roles, one per persona)

`is_system = true`, org-wide, bare business-name codes (matching `ORG_ADMIN`; no prefix — see OQ-4).

| # | Code | Name | Persona it serves | One-line remit |
|---|------|------|-------------------|----------------|
| 1 | `SALESPERSON` | Salesperson | Salesperson | Quote → order → invoice → deliver → return; issue fiscal receipt; onboard customers. **No** override/void/settings. |
| 2 | `CASHIER` | Cashier | Cashier | POS till (open/sell/close/reconcile), customer receipting, EOD cash count, petty cash, fiscal receipt. Front-line cash only. |
| 3 | `FIELD_SALES_AGENT` | Field Sales Agent | Field/Route Sales Agent | Van/route selling: order/invoice/deliver on a route, collect receipts, **van-stock reconciliation**. |
| 4 | `STOREKEEPER` | Storekeeper | Storekeeper / Stock Controller | Stock view/adjust/count/**post**/transfer/locations, receive goods against PO, supplier returns, batch/serial/expiry. |
| 5 | `ACCOUNTANT` | Accountant | Accountant | GL journals (not close), AR/AP sub-ledgers, cash & bank + treasury, VAT **prepare** (not file), WHT, cost tagging, financial reports. |
| 6 | `SALES_MANAGER` | Sales Manager | Group Sales Manager | Full sales incl. **override/void/credit-override/settings**, pricing rules, blanket/standing/drop-ship, CRM, agents/routes, approve sales. |
| 7 | `BRANCH_MANAGER` | Branch Manager | Branch Manager | Broad-but-shallow single-branch oversight: cross-module views + approvals + operational sign-offs (POS reconcile, cash count, stock post, PO/requisition approve). **Assign branch-scoped.** |
| 8 | `PROCUREMENT_OFFICER` | Procurement Officer | Procurement Officer | Requisition → RFQ → PO → receive → return, landed cost, suppliers. **No** approve/void/settings (SoD). |
| 9 | `PROCUREMENT_MANAGER` | Procurement Manager | Procurement Manager | Everything the officer has **plus** PO/requisition **approve**, PO/GR **void**, purchase settings, approvals. |
| 10 | `HR_PAYROLL_MANAGER` | HR & Payroll Manager | HR/Payroll Manager | Employees, leave, loans, pay components, payroll run/approve/post/disburse/reverse, statutory, payee detail. |
| 11 | `FINANCE_DIRECTOR` | Finance Director | Finance Director / CFO | Everything the Accountant has **plus** GL config + period/year close, VAT **file**, FX revalue, fixed assets, budgeting, approvals policy, costing admin. **Not** IAM/company admin. |
| 12 | `PRODUCTION_MANAGER` | Production Manager | Production Manager/Supervisor | Work-order lifecycle, BOMs, material movements, WIP/costing views. |

**Why these, and why not fewer.** Each maps to one persona in the owner's list. The two officer/manager
pairs (`PROCUREMENT_OFFICER`/`PROCUREMENT_MANAGER`; and the `SALESPERSON`/`SALES_MANAGER` split, and
`ACCOUNTANT`/`FINANCE_DIRECTOR`) exist to preserve **segregation of duties** — the whole point of a
create-vs-approve, post-vs-close, prepare-vs-file boundary is that one bundle should not hold both. If
the owner prefers ~10, the officer/manager pairs are the only sane collapse candidates, at the cost of
SoD. `Cashier` (front-line till) and the treasury functions are deliberately **not** one role: bank
reconciliation, inter-account transfers and the cheque register are an accounting function
(→ `ACCOUNTANT`/`FINANCE_DIRECTOR`), not a till cashier's.

**Baseline granted to every role** (kept out of the per-role lists below to reduce noise):
`NOTIFICATION.VIEW`, `NOTIFICATION.PREFERENCE.MANAGE` (own in-app inbox), `DOCUMENT.RENDER` (print/
download what the user can already see), `BRANCH.VIEW` (self-contained; also a member-floor read).

---

## 3. Role → permission-code matrix (authoritative grant lists)

Least-privilege. **Only codes that exist in `R__seed_permissions.sql` are used** (verified 2026-07-05).
Each list is *in addition to* the §2 baseline. Reads whose gate is a **member-floor** (`hasOrMember`
`PRODUCT.VIEW`, `scopedOrMember` `BRANCH.VIEW`) are granted explicitly anyway, for self-containment.

### 3.1 Compact capability grid

Legend: **F**=full/manage, **W**=write/create, **A**=approve, **R**=read, **·**=none.

| Role | Sales | POS | AR | AP | Cash/Bank | Stock | Purch | GL/Tax | HR | Mfg | CRM | Reports/BI | Approvals |
|------|:---:|:---:|:---:|:---:|:---:|:---:|:---:|:---:|:---:|:---:|:---:|:---:|:---:|
| SALESPERSON | W | · | R | · | · | R | · | · | · | · | · | · | · |
| CASHIER | R+settle | F | W | · | count/petty | · | · | · | · | · | · | · | · |
| FIELD_SALES_AGENT | W | · | W | · | · | R+vanrecon | · | · | · | · | · | · | · |
| STOREKEEPER | · | · | · | · | · | **F** | receive/return R | · | · | · | · | · | · |
| ACCOUNTANT | · | · | **F** | **F** | **F** | R | · | post+prep | · | · | · | **R** | · |
| SALES_MANAGER | **F** | · | R+dispute | · | · | R | · | tax R | · | · | **F** | R | A |
| BRANCH_MANAGER | override/void | reconcile | R | R | count | adjust+post R | A | R | · | · | · | **R** | A |
| PROCUREMENT_OFFICER | · | · | · | · | · | R | **W** | · | · | · | · | · | · |
| PROCUREMENT_MANAGER | · | · | · | · | · | R | **F+A** | · | · | · | · | R | A |
| HR_PAYROLL_MANAGER | · | · | · | · | · | · | · | · | **F** | · | · | · | · |
| FINANCE_DIRECTOR | · | · | **F** | **F** | **F** | R | · | **F**+close+file | · | · | · | **F** | **F**+policy |
| PRODUCTION_MANAGER | · | · | · | · | · | move R | · | costing R | · | **F** | · | · | · |

### 3.2 Authoritative explicit code lists

**SALESPERSON** — `SALES.QUOTE.CREATE`, `SALES.QUOTE.SEND`, `SALES.QUOTE.ACCEPT`, `SALES.QUOTE.VIEW`,
`SALES.ORDER.CREATE`, `SALES.ORDER.CONFIRM`, `SALES.ORDER.CANCEL`, `SALES.ORDER.VIEW`,
`SALES.INVOICE.CREATE`, `SALES.INVOICE.SETTLE`, `SALES.INVOICE.VIEW`, `SALES.DELIVERY.CREATE`,
`SALES.DELIVERY.VIEW`, `SALES.RETURN.CREATE`, `SALES.RETURN.VIEW`, `FISCAL.MANAGE`, `FISCAL.VIEW`,
`CUSTOMER.MANAGE`, `CUSTOMER.VIEW`, `AR.INVOICE.VIEW`, `AR.STATEMENT.VIEW`, `PRICELIST.VIEW`,
`SALES.PRICING.RULE.VIEW`, `STOCK.VIEW`, `ROUTE.VIEW`, `AGENT.VIEW`, `PRODUCT.VIEW`, `UOM.VIEW`,
`CURRENCY.VIEW`, `TAXRATE.VIEW`, `PAYMENTTERMS.VIEW`, `DOCUMENT.VIEW`.

**CASHIER** — `POS.SALE.CREATE`, `POS.SALE.VOID`, `POS.SESSION.OPEN`, `POS.SESSION.CLOSE`,
`POS.SESSION.RECONCILE`, `POS.SESSION.VIEW`, `POS.TILL.VIEW`, `SALES.INVOICE.SETTLE`,
`SALES.INVOICE.VIEW`, `AR.RECEIPT.RECORD`, `AR.RECEIPT.ALLOCATE`, `AR.INVOICE.VIEW`, `AR.VIEW`,
`CASH.COUNT.MANAGE`, `CASH.COUNT.VIEW`, `PETTY_CASH.MANAGE`, `PETTY_CASH.VIEW`, `FISCAL.MANAGE`,
`FISCAL.VIEW`, `CUSTOMER.VIEW`, `PRODUCT.VIEW`, `UOM.VIEW`, `CURRENCY.VIEW`, `DOCUMENT.VIEW`.
*(POS.SESSION.RECONCILE posts a variance to GL — see OQ-5; kept on Cashier as the "reconcile your own
drawer" default, absent it a fresh single-cashier tenant cannot close a till.)*

**FIELD_SALES_AGENT** — `SALES.QUOTE.CREATE`, `SALES.QUOTE.SEND`, `SALES.QUOTE.VIEW`,
`SALES.ORDER.CREATE`, `SALES.ORDER.CONFIRM`, `SALES.ORDER.VIEW`, `SALES.INVOICE.CREATE`,
`SALES.INVOICE.SETTLE`, `SALES.INVOICE.VIEW`, `SALES.DELIVERY.CREATE`, `SALES.DELIVERY.VIEW`,
`SALES.RETURN.CREATE`, `SALES.RETURN.VIEW`, `FISCAL.MANAGE`, `FISCAL.VIEW`, `AR.RECEIPT.RECORD`,
`AR.RECEIPT.ALLOCATE`, `AR.INVOICE.VIEW`, `AR.STATEMENT.VIEW`, `AR.VIEW`, `STOCK.VAN_RECON.MANAGE`,
`STOCK.VAN_RECON.VIEW`, `STOCK.VIEW`, `INVENTORY.BATCH.VIEW`, `ROUTE.VIEW`, `AGENT.VIEW`,
`CUSTOMER.MANAGE`, `CUSTOMER.VIEW`, `PRICELIST.VIEW`, `SALES.PRICING.RULE.VIEW`, `PRODUCT.VIEW`,
`UOM.VIEW`, `CURRENCY.VIEW`, `TAXRATE.VIEW`, `PAYMENTTERMS.VIEW`, `DOCUMENT.VIEW`.

**STOREKEEPER** — `STOCK.VIEW`, `STOCK.ADJUST`, `STOCK.OPENING`, `STOCK.COUNT.CREATE`,
`STOCK.COUNT.VIEW`, `STOCK.COUNT.POST`, `STOCK.LOCATION.MANAGE`, `STOCK.LOCATION.VIEW`,
`STOCK.TRANSFER.CREATE`, `STOCK.TRANSFER.RECEIVE`, `STOCK.TRANSFER.VIEW`, `INVENTORY.BATCH.VIEW`,
`INVENTORY.SERIAL.VIEW`, `INVENTORY.EXPIRY.VIEW`, `PURCHASE.RECEIVE`, `PURCHASE.GOODS_RECEIPT.VIEW`,
`PURCHASE.ORDER.VIEW`, `PURCHASE.RETURN.CREATE`, `PURCHASE.RETURN.VIEW`, `PRODUCT.VIEW`, `UOM.VIEW`,
`DOCUMENT.VIEW`. *(STOCK.COUNT.POST is the variance-posting authority — see OQ-5/SoD.)*

**ACCOUNTANT** — `GL.VIEW`, `GL.POST`, `AR.VIEW`, `AR.INVOICE.VIEW`, `AR.RECEIPT.RECORD`,
`AR.RECEIPT.ALLOCATE`, `AR.CREDITNOTE`, `AR.WRITEOFF`, `AR.STATEMENT.VIEW`, `AR.OPENING.SET`,
`AR.DISPUTE.MANAGE`, `AP.VIEW`, `AP.BILL.ENTER`, `AP.BILL.MATCH`, `AP.PAYMENT.RUN`, `AP.DEBITNOTE`,
`AP.OPENING.SET`, `CASH.VIEW`, `CASH.ACCOUNT.MANAGE`, `CASH.ENTRY.RECORD`, `CASH.RECONCILE`,
`CASH.TRANSFER`, `CHEQUE.MANAGE`, `CASH.COUNT.VIEW`, `PETTY_CASH.VIEW`, `PETTY_CASH.MANAGE`,
`VAT.VIEW`, `VAT.RETURN.PREPARE`, `VAT.ADJUST`, `WHT.VIEW`, `WHT.MANAGE`, `WHT.REMIT`, `COSTING.VIEW`,
`COSTING.TAG`, `FX.EXPOSURE.VIEW`, `FA.VIEW`, `FA.CATEGORY.VIEW`, `REPORT.VIEW`, `REPORT.PL.VIEW`,
`REPORT.BS.VIEW`, `REPORT.CASHFLOW.VIEW`, `REPORT.LEDGER.VIEW`, `REPORT.EXPORT`, `BI.VIEW`,
`BI.FINANCE.VIEW`, `CUSTOMER.VIEW`, `SUPPLIER.VIEW`, `PRODUCT.VIEW`, `UOM.VIEW`, `CURRENCY.VIEW`,
`TAXRATE.VIEW`, `PAYMENTTERMS.VIEW`, `DOCUMENT.VIEW`.
*(Deliberately **excluded**: `GL.MANAGE`, `GL.PERIOD.CLOSE`, `GL.YEAR.CLOSE`, `VAT.RETURN.FILE`,
`FX.REVALUE` — reserved for `FINANCE_DIRECTOR`.)*

**SALES_MANAGER** — `SALES.QUOTE.CREATE`, `SALES.QUOTE.SEND`, `SALES.QUOTE.ACCEPT`, `SALES.QUOTE.VIEW`,
`SALES.ORDER.CREATE`, `SALES.ORDER.CONFIRM`, `SALES.ORDER.CANCEL`, `SALES.ORDER.VIEW`,
`SALES.INVOICE.CREATE`, `SALES.INVOICE.SETTLE`, `SALES.INVOICE.VIEW`, `SALES.INVOICE.OVERRIDE`,
`SALES.INVOICE.VOID`, `SALES.CREDIT.OVERRIDE`, `SALES.DELIVERY.CREATE`, `SALES.DELIVERY.VIEW`,
`SALES.RETURN.CREATE`, `SALES.RETURN.VIEW`, `SALES.DROPSHIP.CREATE`, `SALES.DROPSHIP.VIEW`,
`SALES.BLANKET.CREATE`, `SALES.BLANKET.MANAGE`, `SALES.BLANKET.CLOSE`, `SALES.BLANKET.VIEW`,
`SALES.STANDING.CREATE`, `SALES.STANDING.MANAGE`, `SALES.STANDING.GENERATE`, `SALES.STANDING.VIEW`,
`SALES.SETTINGS.MANAGE`, `SALES.PRICING.RULE.MANAGE`, `SALES.PRICING.RULE.VIEW`, `PRICELIST.MANAGE`,
`PRICELIST.VIEW`, `PRICE.MASS_UPDATE`, `FISCAL.MANAGE`, `FISCAL.VIEW`, `CRM.LEAD.MANAGE`,
`CRM.LEAD.QUALIFY`, `CRM.LEAD.VIEW`, `CRM.OPPORTUNITY.MANAGE`, `CRM.OPPORTUNITY.CONVERT`,
`CRM.OPPORTUNITY.VIEW`, `CRM.ACTIVITY.MANAGE`, `CRM.ACTIVITY.VIEW`, `CRM.PIPELINE.VIEW`,
`CRM.STAGE.MANAGE`, `CUSTOMER.MANAGE`, `CUSTOMER.VIEW`, `CUSTOMER.IMPORT`, `AGENT.MANAGE`,
`AGENT.VIEW`, `ROUTE.MANAGE`, `ROUTE.VIEW`, `ROUTE.ASSIGN`, `AR.VIEW`, `AR.INVOICE.VIEW`,
`AR.STATEMENT.VIEW`, `AR.DISPUTE.MANAGE`, `STOCK.VIEW`, `APPROVALS.DECIDE`, `APPROVALS.REQUEST.VIEW`,
`BI.VIEW`, `BI.CRM.VIEW`, `TAXRATE.VIEW`, `PRODUCT.VIEW`, `UOM.VIEW`, `CURRENCY.VIEW`,
`PAYMENTTERMS.VIEW`, `DOCUMENT.VIEW`.

**BRANCH_MANAGER** — `SALES.QUOTE.VIEW`, `SALES.ORDER.VIEW`, `SALES.INVOICE.VIEW`,
`SALES.INVOICE.OVERRIDE`, `SALES.INVOICE.VOID`, `SALES.CREDIT.OVERRIDE`, `POS.SESSION.VIEW`,
`POS.SESSION.RECONCILE`, `POS.SALE.VOID`, `POS.SALE.AGE_OVERRIDE`, `POS.TILL.MANAGE`, `POS.TILL.VIEW`,
`CASH.COUNT.MANAGE`, `CASH.COUNT.VIEW`, `PETTY_CASH.VIEW`, `CASH.VIEW`, `STOCK.VIEW`, `STOCK.ADJUST`,
`STOCK.COUNT.VIEW`, `STOCK.COUNT.POST`, `STOCK.TRANSFER.VIEW`, `STOCK.LOCATION.VIEW`,
`INVENTORY.VALUATION.VIEW`, `INVENTORY.EXPIRY.VIEW`, `PURCHASE.ORDER.VIEW`, `PURCHASE.ORDER.APPROVE`,
`PURCHASE.REQUISITION.VIEW`, `PURCHASE.REQUISITION.APPROVE`, `PURCHASE.GOODS_RECEIPT.VIEW`, `AR.VIEW`,
`AP.VIEW`, `APPROVALS.DECIDE`, `APPROVALS.REQUEST.VIEW`, `APPROVALS.POLICY.VIEW`, `BI.VIEW`,
`BI.OPS.VIEW`, `BI.FINANCE.VIEW`, `REPORT.VIEW`, `REPORT.PL.VIEW`, `REPORT.BS.VIEW`, `REPORT.EXPORT`,
`CUSTOMER.VIEW`, `SUPPLIER.VIEW`, `ROUTE.VIEW`, `PRODUCT.VIEW`, `UOM.VIEW`, `DOCUMENT.VIEW`.
*(Intended to be **assigned branch-scoped** via `user_role.branch_id` — the grant set is broad, the
data reach is one branch. It deliberately omits IAM, company config, GL close, payroll and master
restructuring — it approves and oversees, it does not administer.)*

**PROCUREMENT_OFFICER** — `PURCHASE.REQUISITION.CREATE`, `PURCHASE.REQUISITION.VIEW`,
`PURCHASE.RFQ.MANAGE`, `PURCHASE.RFQ.VIEW`, `PURCHASE.ORDER.CREATE`, `PURCHASE.ORDER.VIEW`,
`PURCHASE.RECEIVE`, `PURCHASE.GOODS_RECEIPT.VIEW`, `PURCHASE.RETURN.CREATE`, `PURCHASE.RETURN.VIEW`,
`PURCHASE.LANDEDCOST.MANAGE`, `PURCHASE.LANDEDCOST.VIEW`, `SUPPLIER.MANAGE`, `SUPPLIER.VIEW`,
`SUPPLIER.IMPORT`, `PAYMENTTERMS.VIEW`, `STOCK.VIEW`, `PRODUCT.VIEW`, `UOM.VIEW`, `CURRENCY.VIEW`,
`DOCUMENT.VIEW`.

**PROCUREMENT_MANAGER** — **all of PROCUREMENT_OFFICER**, **plus** `PURCHASE.ORDER.APPROVE`,
`PURCHASE.REQUISITION.APPROVE`, `PURCHASE.ORDER.VOID`, `PURCHASE.VOID`, `PURCHASE.SETTINGS.MANAGE`,
`APPROVALS.DECIDE`, `APPROVALS.REQUEST.VIEW`, `BI.VIEW`, `BI.OPS.VIEW`.

**HR_PAYROLL_MANAGER** — `HR.EMPLOYEE.MANAGE`, `HR.EMPLOYEE.VIEW`, `HR.EMPLOYEE.PAYEE.VIEW`,
`HR.LEAVE.MANAGE`, `HR.LEAVE.APPROVE`, `HR.LEAVE.VIEW`, `HR.LOAN.MANAGE`, `HR.PAYCOMPONENT.MANAGE`,
`HR.PAYROLL.RUN`, `HR.PAYROLL.APPROVE`, `HR.PAYROLL.POST`, `HR.PAYROLL.DISBURSE`, `HR.PAYROLL.REVERSE`,
`HR.PAYROLL.VIEW`, `HR.PAYSLIP.VIEW`, `HR.STATUTORY.MANAGE`, `HR.SELF.VIEW`, `CURRENCY.VIEW`,
`DOCUMENT.VIEW`. *(Bundles maker + checker — RUN/APPROVE/POST/DISBURSE — see SoD note §5.)*

**FINANCE_DIRECTOR** — **all of ACCOUNTANT**, **plus** `GL.MANAGE`, `GL.PERIOD.CLOSE`, `GL.YEAR.CLOSE`,
`VAT.RETURN.FILE`, `FX.REVALUE`, `CURRENCY.MANAGE`, `FA.CATEGORY.MANAGE`, `FA.REGISTER.MANAGE`,
`FA.DEPRECIATE`, `FA.DISPOSE`, `FA.VERIFY`, `BUDGETING.BUDGET.MANAGE`, `BUDGETING.BUDGET.SUBMIT`,
`BUDGETING.BUDGET.APPROVE`, `BUDGETING.BUDGET.VIEW`, `BUDGETING.REPORT.VIEW`,
`BUDGETING.REPORT.EXPORT`, `COSTING.MANAGE`, `APPROVALS.DECIDE`, `APPROVALS.POLICY.MANAGE`,
`APPROVALS.POLICY.VIEW`, `APPROVALS.REQUEST.VIEW`, `APPROVALS.ADMIN`, `BI.OPS.VIEW`, `BI.CRM.VIEW`,
`COMPANY.CURRENCY.CHANGE`. *(Finance super-user short of IAM. Deliberately **not** `USER.MANAGE`,
`ROLE.ADMIN`, `COMPANY.MANAGE`, `BRANCH.MANAGE` — org administration stays `ORG_ADMIN`.)*

**PRODUCTION_MANAGER** — `WORKORDER.MANAGE`, `WORKORDER.RELEASE`, `WORKORDER.CLOSE`, `WORKORDER.QC`,
`MANUFACTURING.VIEW`, `BOM.MANAGE`, `BOM.VIEW`, `STOCK.VIEW`, `STOCK.TRANSFER.CREATE`,
`STOCK.TRANSFER.RECEIVE`, `STOCK.TRANSFER.VIEW`, `INVENTORY.BATCH.VIEW`, `INVENTORY.VALUATION.VIEW`,
`COSTING.VIEW`, `COSTING.TAG`, `PRODUCT.VIEW`, `UOM.VIEW`, `DOCUMENT.VIEW`.

### 3.3 Closure check against ADR-0047

Cross-checked against `backend/src/main/resources/security/screen-read-closure.json`: every
manifest screen a role is meant to operate has its **required, closure-bearing** reads present, e.g.
`CASHIER`/`FIELD_SALES_AGENT` carry `CUSTOMER.VIEW` + `AR.VIEW` (record-receipt); `ACCOUNTANT` carries
`SUPPLIER.VIEW` + `AP.VIEW` (record-payment) and `CASH.VIEW`+`GL.VIEW` (record-entry); `STOREKEEPER`
carries `PURCHASE.ORDER.VIEW` + `PURCHASE.RECEIVE` (goods-receipt). Member-floor reads
(`PRODUCT.VIEW` `hasOrMember`, `BRANCH.VIEW` `scopedOrMember`) pass for any granted user anyway.
A `RolePermissionClosureTest`-style advisory (ADR-0047 §"Grant-time validator") over the seeded
bundles is a suggested build guard (§6).

---

## 4. Seeding approach

### 4.1 Where — extend `R__seed_permissions.sql` (recommended), not a sibling file

Recommend appending a **"default operational role bundles"** section to the existing repeatable
`R__seed_permissions.sql`, immediately after the `ORG_ADMIN` `CROSS JOIN` grant.

**Why in-place, not a sibling `R__seed_roles.sql`:** the grant `INSERT` FKs to `permissions(id)`, so
**every permission code must already exist** when the grants run. In one file that ordering is
*guaranteed* — the catalogue is upserted at the top, the bundles below it, in a single script.
A sibling file relies on Flyway's rule that repeatables run **in description order** (`seed
permissions` < `seed roles`, so `p` < `r` — correct today), but that is a *fragile* dependency: a
rename, or a future third seed file, could reorder them, and if the roles seed ran first its
`JOIN permissions` would match **nothing**, silently grant zero rows, and — being repeatable — not
re-run until its own checksum next changed. That is a latent, hard-to-spot RBAC hole. In-place removes
the ordering variable entirely. The cost — the RBAC file now carries two concerns and every bundle
tweak re-runs the (idempotent, ~230-row) catalogue upsert — is trivial; the `ORG_ADMIN` grant already
lives in this file, so operational bundles sit naturally beside it. (Sibling-file separation is a real
alternative; see ADR-0057 Alternatives.)

**No versioned migration.** Both the `roles` rows and the `role_permission` grants go in the repeatable
seed (upsert), so the frozen versioned schema (ADR-0043) is **untouched** — the whole feature is one
edit to one repeatable file. This improves on `V1`, which split the `ORG_ADMIN` row (versioned) from
its grants (repeatable); in the frozen-schema era, seed-owned reference rows belong wholly in the
convergent repeatable.

### 4.2 How — idempotent, convergent, self-healing

Same posture as the `ORG_ADMIN` grant: **additive floor** — grants are upserted `ON CONFLICT DO
NOTHING`, never revoked here. The seed guarantees the least-privilege **floor** is always present and
self-heals if a row is lost; it does not fight a tenant admin who *adds* an extra grant to a system
role. (Whether to instead **converge/prune** to the exact list is OQ-2.)

Illustrative SQL **shape** (not a committed migration):

```sql
-- ==========================================================================
-- Default operational role bundles (ADR-0057). Org-wide (no company_id),
-- is_system => undeletable (BR-7). Runs AFTER the permission catalogue is
-- upserted above, so every referenced code exists (FK-safe). Additive floor.
-- ==========================================================================

-- (1) Role rows. Fixed seed uids: 10 zero chars + Crockford-base32 tail
--     (alphabet excludes I L O U), same convention as ORG_ADMIN's
--     '0000000000XVKF7J9FAGX51RMQ'. Mint one valid ULID per role.
INSERT INTO roles (uid, code, name, description, is_system) VALUES
  ('0000000000<mint-01>', 'SALESPERSON',         'Salesperson',          'Quotes, orders, invoices, deliveries, returns; fiscal receipts.', true),
  ('0000000000<mint-02>', 'CASHIER',             'Cashier',              'POS till, customer receipting, cash count, petty cash.',         true),
  ('0000000000<mint-03>', 'FIELD_SALES_AGENT',   'Field Sales Agent',    'Route/van selling, receipting and van-stock reconciliation.',    true),
  ('0000000000<mint-04>', 'STOREKEEPER',         'Storekeeper',          'Stock control, counts, transfers, goods receipt.',               true),
  ('0000000000<mint-05>', 'ACCOUNTANT',          'Accountant',           'GL journals, AR/AP, cash & bank, VAT prep, WHT, reports.',        true),
  ('0000000000<mint-06>', 'SALES_MANAGER',       'Sales Manager',        'Full sales incl. overrides, pricing, CRM, approvals.',           true),
  ('0000000000<mint-07>', 'BRANCH_MANAGER',      'Branch Manager',       'Cross-module branch oversight, approvals and sign-offs.',        true),
  ('0000000000<mint-08>', 'PROCUREMENT_OFFICER', 'Procurement Officer',  'Requisition, RFQ, PO, receive, returns, suppliers.',             true),
  ('0000000000<mint-09>', 'PROCUREMENT_MANAGER', 'Procurement Manager',  'Procurement officer plus approve, void and settings.',           true),
  ('0000000000<mint-10>', 'HR_PAYROLL_MANAGER',  'HR & Payroll Manager', 'Employees, leave, payroll run/approve/post/disburse.',           true),
  ('0000000000<mint-11>', 'FINANCE_DIRECTOR',    'Finance Director',     'Accountant plus GL close, VAT file, FA, budgeting, policy.',     true),
  ('0000000000<mint-12>', 'PRODUCTION_MANAGER',  'Production Manager',    'Work orders, BOMs, material movements, WIP costing.',            true)
ON CONFLICT (code) DO UPDATE
  SET name = EXCLUDED.name, description = EXCLUDED.description, is_system = true;

-- (2) Grants. Declare (role_code, permission_code) pairs once; resolve to ids.
INSERT INTO role_permission (role_id, permission_id)
SELECT r.id, p.id
FROM (VALUES
    ('SALESPERSON','SALES.QUOTE.CREATE'),
    ('SALESPERSON','SALES.ORDER.CREATE'),
    -- ... every pair from §3.2, one per line ...
    ('CASHIER','POS.SALE.CREATE'),
    ('CASHIER','AR.RECEIPT.RECORD'),
    -- ...
    ('FINANCE_DIRECTOR','GL.YEAR.CLOSE')
) AS g(role_code, perm_code)
JOIN roles       r ON r.code = g.role_code
JOIN permissions p ON p.code = g.perm_code
ON CONFLICT DO NOTHING;
```

**Self-healing on a new permission code (the deliberate behaviour):** a newly-added permission does
**not** auto-flow into any operational bundle — only `ORG_ADMIN` absorbs it (its `CROSS JOIN` lives in
this same file, which re-runs when the catalogue changes). Flowing a new capability into a
least-privilege bundle is a **reviewed** decision: add its `(role_code, perm_code)` pair(s) to the
`VALUES` list. Editing the list changes the file checksum → the repeatable re-runs → the new floor is
established. This extends the existing engineering rule "a new permission-gated endpoint must seed its
code" with a second clause: **"…and decide which default bundles, if any, receive it."**

**Module-wildcard grants were considered and rejected** as the grant mechanism (e.g. "grant every
`sales` permission to `SALESPERSON`"): the modules do **not** map to job functions. The `sales` module
holds both `SALES.QUOTE.CREATE` (salesperson) and `SALES.INVOICE.VOID` / `SALES.CREDIT.OVERRIDE` /
`SALES.SETTINGS.MANAGE` / `TAXRATE.MANAGE` / `POS.*` (manager/cashier). Wildcarding would break
least-privilege. Explicit pair lists are the only mechanism that expresses least-privilege; the price
is that new codes need an explicit bundle decision (which is correct).

---

## 5. Missing-permission-code gaps

**No persona in the owner's list is blocked by a missing permission code.** Every capability the 12
roles need maps to a code that exists in `R__seed_permissions.sql` (verified 2026-07-05). The gaps are
scope and segregation observations, not code gaps:

1. **Projects module has no persona/role** (`PROJECTS.*` — project create/manage, timesheets, issue,
   costing, tags). Codes exist but no persona was listed, so **only `ORG_ADMIN`** holds them. If
   Projects is in scope, a `PROJECT_MANAGER` role is a clean follow-up.
2. **No generic Employee self-service role.** `HR.SELF.VIEW` (own payslips/leave) is granted only to
   `HR_PAYROLL_MANAGER`. Rank-and-file employees who log in to view their own payslip/submit leave have
   no default bundle. If ESS is wanted, either a base `EMPLOYEE` role holding
   `HR.SELF.VIEW` + `NOTIFICATION.*` or an auto-grant on employee-user creation (OQ-3). Not a missing
   code — a missing bundle decision.
3. **Segregation-of-duties compression in three single-role defaults** (a design trade, not a code
   gap): `HR_PAYROLL_MANAGER` bundles RUN+APPROVE+POST+DISBURSE (maker=checker); `PROCUREMENT_MANAGER`
   bundles create+approve; `STOREKEEPER` bundles count+POST; `CASHIER`/`BRANCH_MANAGER` hold
   `POS.SESSION.RECONCILE`. This matches the reality that a fresh/small tenant runs each function with
   one person. A tenant needing strict SoD composes narrower **custom** (non-system) roles from the
   same codes — the defaults are a working baseline, not a compliance ceiling. Flagged as OQ-5.

---

## 6. Open design questions for the owner

- **OQ-1 — Org-wide shared vs per-company copies.** Recommend **org-wide shared** (one catalogue, all
  companies assign the same roles; matches ADR-0001 and the `ORG_ADMIN` precedent; customization =
  new custom role). The alternative — seed a private copy of each role per company so tenants can edit
  independently — needs a per-company seed keyed off `companies` and breaks the "roles are org-wide"
  invariant. **Decision needed.**
- **OQ-2 — Additive floor vs convergent (prune).** Recommend **additive** (`ON CONFLICT DO NOTHING`,
  never revoke — like `ORG_ADMIN`): the seed guarantees the floor and never fights a tenant's added
  grant. Consequence: **tightening** a shipped bundle (removing a code from the list) will **not**
  revoke it on existing DBs — that needs an explicit one-off revoke. The alternative — **converge/prune**
  to exactly the list — makes tightenings take effect automatically but forbids tenant edits to system
  roles (any edit is reverted on migrate) and puts destructive `DELETE` in a seed. **Decision needed:
  are system-role grant sets seed-owned/frozen, or a customizable starting point?**
- **OQ-3 — Auto-assignment.** Recommend **no auto-assignment** — the seed makes roles *exist and
  ready to assign*; `CompanyProvisioningService` does not auto-grant any default role to any user (the
  bootstrap admin is already root/`ORG_ADMIN`). Should provisioning instead auto-assign, e.g. the
  bootstrap admin some starter role, or auto-grant `HR.SELF.VIEW` to every employee-user (OQ related
  to gap §5.2)? **Decision needed.**
- **OQ-4 — Naming/prefix convention.** Recommend **bare business names** (`SALESPERSON`, `CASHIER`, …)
  matching `ORG_ADMIN`, with `is_system = true` as the "shipped/undeletable" marker. Alternative: a
  `SYS_`/`STD_` prefix to visually separate shipped roles from tenant roles in the UI. **Decision
  needed** (drives the seed codes; codes are stable once shipped).
- **OQ-5 — SoD posture of the compressed roles** (§5.3): accept the pragmatic single-role defaults, or
  split `HR_PAYROLL_MANAGER` into preparer/approver and move `STOCK.COUNT.POST` /
  `POS.SESSION.RECONCILE` to a supervisory bundle? **Decision needed.**
- **OQ-6 — Editability of system roles in the IAM UI.** Does `ROLE.ADMIN` permit editing a system
  role's *permissions* (BR-7 only blocks deletion)? The answer interacts with OQ-2: if system-role
  grants are convergent/pruned, the UI must forbid editing them (or edits vanish on next migrate).
  **Decision needed.**

---

## 7. Delivery cost (facts for the PM; no dates)

- **Migration:** one edit to the repeatable `R__seed_permissions.sql` (roles rows + grants). **No**
  versioned `V<n>`, **no** schema change (ADR-0043 honoured). Re-runs convergently on every environment
  (local/QA/prod) on next migrate; durable DBs are not wiped.
- **Backend:** none required to *ship* the roles. Optional guard: a `DefaultRoleBundlesSeededTest`
  (surefire, Dockerless) asserting each seeded default holds its declared closure, sibling to
  `PermissionCodesSeededTest`/`RolePermissionClosureTest`.
- **Web:** none — the existing Role management screen lists whatever roles exist; the defaults simply
  appear. (UX nicety: badge `is_system` roles as "Standard / read-only".)
- **Test harness:** the persona-UAT/sim harness can stop hand-building `*_190194` roles and assign the
  shipped bundles; retire the keyword-matching role synthesis in `e2e/sim/sim-data.js`.
- **Risk:** low. Additive seed on a durable DB only inserts missing rows. The only irreversible-ish
  choice is the **role codes** (OQ-4) — stable once shipped, since tenants/assignments reference them.
```
