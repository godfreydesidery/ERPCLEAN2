# User Stories

Stories are `US-<MODULE>-NNN`. Acceptance criteria are Given/When/Then and must be testable.
Source of truth for what a screen/endpoint should do. Authored by system-analyst.

---

## IAM — Identity & Access Management

Requirements: [docs/requirements/iam.md](docs/requirements/iam.md). Status: DRAFT pending sign-off.

### US-IAM-001 — Log in
**As a** user **I want** to log in with my username and password **so that** I can access the ERP
scoped to my company and branch.
- **AC1** Given valid credentials, when I log in, then I receive an access token (15 min) and a
  refresh token (7 days) and land in my **default branch** (and its company).
- **AC2** Given invalid credentials, when I log in, then I am refused with a generic error (no hint
  which of username/password was wrong) and my failed-attempt counter increments.
- **AC3** Given 5 consecutive failures, when I attempt a 6th, then the account is locked for 15
  minutes and I am told it is locked.
- **AC4** Given a successful login, then my failed-attempt counter resets to 0.

### US-IAM-002 — Refresh & log out
**As a** user **I want** my session to refresh seamlessly and to log out **so that** I stay signed
in safely and can end my session.
- **AC1** Given a valid refresh token, when it is used, then a new access+refresh pair is issued and
  the used refresh token is invalidated (single-use rotation).
- **AC2** Given a refresh token already used once, when it is presented again, then it is rejected.
- **AC3** Given I log out, then my refresh token is revoked and my access token is denied on next use.

### US-IAM-003 — Switch branch without re-login
**As a** user assigned to several branches **I want** to switch my active branch **so that** I can
work across the locations I cover.
- **AC1** Given I am assigned to branches A and B, when I switch to B (branch-override header), then
  subsequent requests are scoped to B without re-login.
- **AC2** Given I request a branch I am **not** assigned to, then the request is refused.
- **AC3** Given my role does not cover the target branch, then actions there are refused even though
  I am "present" at the branch.

### US-IAM-004 — Administer companies & branches
**As an** administrator **I want** to create companies and branches **so that** the org structure
reflects the business.
- **AC1** I can create a company under the organisation.
- **AC2** I can create a branch under a company with a code unique within that company.
- **AC3** Exactly one branch per company is the default; setting a new default clears the old.

### US-IAM-005 — Manage users
**As an** administrator **I want** to create and manage user accounts **so that** staff can log in.
- **AC1** I can create a user with a username unique org-wide; a duplicate is rejected.
- **AC2** I can set/reset a user's password (admin-driven); the password must meet policy
  (≥ 8 chars, letters + number); a weak/common password is rejected.
- **AC3** I can disable a user; a disabled user cannot log in.
- **AC4** I can unlock a locked-out user.

### US-IAM-006 — Assign branches & set default
**As an** administrator **I want** to assign a user to many branches and mark one default **so
that** the user lands in the right place and can cover several locations.
- **AC1** I can assign a user to multiple branches.
- **AC2** Exactly one assignment is marked default; marking a new default clears the old.
- **AC3** I cannot set a default branch that is not among the user's assignments.
- **AC4** When I remove a user's current default branch, the system auto-promotes the
  **earliest-assigned** remaining branch to default.
- **AC5** When I remove a user's last remaining branch, the user has no active branch; on next
  login they reach a read-only "no branch assigned — contact admin" state and cannot transact until
  reassigned.
- **AC6** I can assign a user to a branch even if they have no role in that company yet; the user is
  "present" but cannot act there until a role covers it.

### US-IAM-007 — Roles & permissions
**As an** administrator **I want** to define roles from permissions and assign roles to users
**so that** access matches the org chart.
- **AC1** Permissions exist as an org-wide seeded catalogue; they cannot be invented at runtime.
- **AC2** I can create a role and attach permissions to it (org-wide).
- **AC3** I can assign a role to a user scoped to a company, optionally to one branch.
- **AC4** A user with a company-wide role assignment has it across all branches they are assigned to
  in that company; a branch-scoped assignment applies only to that branch.
- **AC5** An endpoint gated by a permission the user lacks (for the active company/branch) returns
  forbidden.

### US-IAM-008 — Super-admin
**As a** super-admin **I want** to manage all companies/branches and recover accounts **so that** I
can set up and support the deployment.
- **AC1** The super-admin can act across all companies and branches regardless of scoping.
- **AC2** Every super-admin action is written to the audit log.
- **AC3** The root permission set / system roles cannot be deleted.

### US-IAM-009 — Fresh-DB bootstrap
**As the** deployment owner **I want** the system to self-bootstrap on a fresh DB **so that** there
is a way in without a manual wizard.
- **AC1** Given an empty DB with bootstrap enabled, on first start the system creates organisation +
  first company + that company's default branch + a root admin.
- **AC2** Given the bootstrap admin password env var is missing/too short/a known placeholder, the
  app refuses to start with a clear message.
- **AC3** Given bootstrap has already run (data exists), it does not run again.

### US-IAM-010 — IAM audit trail
**As an** administrator/auditor **I want** IAM actions recorded **so that** I can see who changed
access and when.
- **AC1** User create/disable, role grant/revoke, branch assign/remove, default-branch change,
  password reset, lockout/unlock, and login success/failure are each written to an append-only
  audit log with actor, action, target, timestamp, and company/branch context.
- **AC2** Audit records cannot be edited or deleted through the application.

---

## Parties — Customers, Suppliers, Sales Agents

Requirements: [docs/requirements/parties.md](docs/requirements/parties.md). Status: DRAFT pending
sign-off. Parties are scoped per company and associated with many of that company's branches.

### US-PARTY-01 — Create a credit customer (business) scoped to a company, on two branches
**As a** master-data administrator **I want** to create a credit/account customer with its tax
identifiers, scoped to a company and associated with two branches **so that** those branches can sell
to it on account.
- **AC1** Given I am in company C with permission to manage customers, when I create a customer typed
  **business**, **credit/account** sub-kind, with name, TIN, and VRN (marked VAT-registered), then the
  customer is saved under company C.
- **AC2** Given the customer is a business, when I omit the TIN, then save is rejected with a clear
  "TIN required for a business" message (BR-PARTY-04).
- **AC3** Given I mark the customer VAT-registered, when I enter a VRN, then it is accepted; when the
  customer is NOT VAT-registered, then a VRN is refused (BR-PARTY-06).
- **AC4** Given the customer is saved, when I associate it with branches B1 and B2 of company C, then
  both associations are recorded and both must belong to company C (BR-PARTY-01).
- **AC5** Given I try to associate it with a branch of a different company, then the association is
  refused.

### US-PARTY-02 — Create an individual walk-in/cash customer with minimal data
**As a** cashier (with quick-create permission) **I want** to create a walk-in cash customer with
just a name **so that** I can complete a counter sale without full tax details.
- **AC1** Given an **individual**, **cash/walk-in** customer, when I save with only a name (and
  optionally a phone), then it is accepted with no TIN / registration number / VRN required (BR-PARTY-05).
- **AC2** The customer is created in my active company and associated with my active branch.

### US-PARTY-03 — Create a goods supplier
**As a** purchasing administrator **I want** to create a goods supplier with its tax identifiers
**so that** purchases of stock can name it.
- **AC1** Given I create a supplier typed **business**, sub-kind **goods**, with name and TIN, then it
  is saved under my company.
- **AC2** Given I set the sub-kind to **service**, then the supplier is recorded as a service supplier
  (no stock movement expected) and is selectable on service purchases.
- **AC3** The supplier record is independent of any customer record, even if the same legal entity is
  also a customer (BR-PARTY-03) — creating it does not link to or update a customer.

### US-PARTY-04 — Register an internal sales agent linked to a user
**As a** master-data administrator **I want** to register an internal sales agent that references a
staff member's app user **so that** commission accrues to that staff identity.
- **AC1** Given agent kind **internal**, when I create the agent referencing an **active** IAM user,
  then the agent is saved with that user reference (FR-PARTY-13, BR-PARTY-10).
- **AC2** Given the referenced user is **disabled**, when I try to select the internal agent on a new
  sale, then it is not selectable (BR-PARTY-10); the agent record is not deleted.
- **AC3** Given agent kind internal, when I try to also enter standalone external identity instead of
  a user reference, then the form requires the IAM user reference (BR-PARTY-11 applies inversely).

### US-PARTY-05 — Register an external broker (agent)
**As a** master-data administrator **I want** to register an external sales agent/broker as a
standalone party **so that** we can pay out commission to an outside introducer.
- **AC1** Given agent kind **external**, when I create the agent with its own name, contact, and tax
  identifiers and **no** IAM user reference, then it is saved as a standalone party (BR-PARTY-11).
- **AC2** Given agent kind external, when I try to attach an IAM user reference, then it is refused.

### US-PARTY-06 — Associate a party with branches and manage the list
**As a** master-data administrator **I want** to browse and edit which branches a party is associated
with **so that** I control where the party can be used.
- **AC1** Given a party in company C, when I open its branch associations, then I see the branches of C
  it is currently associated with.
- **AC2** I can add a branch (of C) and remove a branch; only branches of C are offered (BR-PARTY-01).
- **AC3** Given a party associated with no branch, then it exists but is selectable on no transaction
  until associated with at least one branch (BR-PARTY-12).

### US-PARTY-07 — Branch operator sees only their branch's parties
**As a** branch operator **I want** party selection to show only parties associated with my active
branch **so that** I do not pick another branch's or company's customer by mistake.
- **AC1** Given parties P1 (branches B1,B2) and P2 (branch B3) in company C, when I am active in B1
  and pick a customer, then I see P1 but not P2 (FR-PARTY-12).
- **AC2** Given a party in a different company, then it never appears in my selection (FR-PARTY-09).
- **AC3** Given I switch my active branch (IAM branch-override) from B1 to B3, then my party selection
  updates to B3's associated parties without re-login.

### US-PARTY-08 — Archive and restore a party
**As a** master-data administrator **I want** to archive an obsolete party and restore it if needed
**so that** it stops appearing on new transactions without losing history.
- **AC1** Given an active party, when I archive it, then it is excluded from new-transaction selection
  lists (BR-PARTY-09) but still shows on historical documents.
- **AC2** Given an archived party, when I restore it, then it becomes selectable again.
- **AC3** Archiving never deletes the record or its history.

### US-PARTY-09 — Create a generic "Other / Misc" party
**As a** master-data administrator **I want** to record a counterparty that is not yet a customer,
supplier, or agent **so that** I am never blocked when something must be captured now.
- **AC1** Given a counterparty that fits none of the typed kinds, when I create an **Other/Misc**
  party with the available identity/contact/tax fields, then it is saved, company-scoped, and
  branch-associable like any party.
- **AC2** The Other party follows the same individual-vs-business typing and identifier rules.

### US-PARTY-10 — Identifier validation by type
**As a** master-data administrator **I want** the system to enforce identifier rules based on
individual-vs-business type **so that** records are tax-complete where they must be.
- **AC1** Given a **business** party, when I save without a TIN, then it is rejected (BR-PARTY-04).
- **AC2** Given an **individual** party, when I save without TIN / registration number / VRN, then it is accepted
  (BR-PARTY-05).
- **AC3** Given a duplicate **VRN** within the same company, then it is flagged (BR-PARTY-13).
- **AC4** Given a duplicate **TIN** within the same company across customer and supplier, then it is
  **warned** but allowed (separate-records model, BR-PARTY-13 / §9).
- **AC5** Given a party **code** that already exists in the same company, then it is rejected
  (BR-PARTY-08); the same code in a different company is allowed.

---

## Sales — selling to customers

Requirements: [docs/requirements/sales.md](docs/requirements/sales.md). Status: **RATIFIED
(owner-confirmed 2026-06-07).** v1 = Invoice channel only; VAT computed per line from per-product VAT
status (TRA fiscalisation deferred); cash + mobile-money tenders, split allowed, paid in full at
finalise; sales agent mandatory + auto-default (commission captured not computed); company default
price list optionally overridden per customer; permissioned audited price override; line + document
discounts before VAT; **no stock deduction (accepted risk)**; permissioned void only; credit deferred.
Remaining detail-level OQs (numbering scheme OQ-SALES-12, VAT-inclusive/-exclusive OQ-SALES-03b,
override threshold OQ-SALES-10, rounding OQ-CUR-03) refine values, not scope. Depends on IAM, Parties,
Products, Multicurrency (ADR-0005). Sales are scoped per company and per active branch.

### US-SALES-01 — Create and finalise a cash invoice (happy path)
**As a** sales clerk **I want** to ring up a customer's products, apply VAT, and finalise the invoice
**so that** the sale is recorded with correct tax and a unique document number.
- **AC1** Given I am logged in with `SALES.CREATE` and an active branch, when I start a new sale, then
  it opens scoped to my company + branch (FR-SALES-01, NFR-SALES-01).
- **AC2** Given I add a sellable, non-archived product associated with my branch with a quantity and
  unit, then a line is created and the quantity converts to base units per Products (FR-SALES-04).
- **AC3** Given the product is on the applicable price list, then the line unit price defaults from
  that list (FR-SALES-07); given the product has **no** price, then the line is rejected with a
  "product not priced" message (FR-SALES-05, BR-SALES-03).
- **AC4** Given the lines are entered, when I view totals, then the system shows **net, VAT (by rate
  band), and gross**, each amount carrying its currency and rounded to the currency's minor units
  (FR-SALES-11, BR-SALES-04). `[rounding mode OQ-CUR-03]`
- **AC5** Given I finalise the sale, then it receives a **document number unique within the company**
  (allocated from the generic `code_sequence`), and its commercial content becomes immutable
  (FR-SALES-23, BR-SALES-08, BR-SALES-12).
- **AC6** Given two clerks finalise simultaneously, then they receive **distinct** document numbers
  (NFR-SALES-04).
- **AC7** Given the sale is finalised, then create→finalise is written to the audit trail with actor,
  branch, and timestamp (NFR-SALES-03).

### US-SALES-02 — Anonymous counter sale to the walk-in customer
**As a** cashier **I want** to sell to an anonymous walk-in without keying full customer details
**so that** counter sales are fast.
- **AC1** Given a walk-in sale, when I select the reusable **walk-in customer** (Parties OQ-PARTY-06),
  then the sale proceeds with no customer tax details required (BR-SALES-10).
- **AC2** Given v1 is paid-at-sale only (credit deferred), when I sell to the walk-in customer, then the
  sale is **paid in full at finalise** like any v1 sale; no receivable is created (FR-SALES-20).
- **AC3** Given credit sales arrive in a later round, then a walk-in customer must **never** be able to
  take credit (BR-SALES-10, BR-PARTY-07) — recorded now so the future credit feature enforces it.

### US-SALES-03 — Attach a sales agent (auto-default when the operator is an internal agent)
**As a** sales clerk who is also an internal sales agent **I want** the sale's agent to default to me
**so that** every sale is correctly attributed without extra keying.
- **AC1** Given I am logged in and my user is referenced by an **internal** agent record, when I start
  a sale, then the sale's agent **auto-defaults to my agent record** (FR-SALES-15).
- **AC2** Given I have permission, when I change the agent, then I may select another **selectable**
  agent (branch-associated; if internal, its IAM user active) (FR-SALES-14, BR-SALES-06).
- **AC3** Given an internal agent whose IAM user is **disabled**, then it is **not selectable**
  (Parties BR-PARTY-10).
- **AC4** Given a sale with no agent (agent is **mandatory**), when I finalise, then it is blocked until
  an agent is attached (FR-SALES-14, BR-SALES-06).
- **AC5** Given the sale is finalised, then the agent attachment **and a commission record** are
  captured, but **no commission is computed or accrued** in v1 (FR-SALES-16). `[rates OQ-PARTY-03]`

### US-SALES-04 — VAT by product status (standard / zero-rated / exempt)
**As a** sales clerk **I want** each line taxed by its product's VAT status **so that** the invoice's
tax is correct for TRA.
- **AC1** Given a **standard-rated** product (VAT status carried on the product master, OQ-PROD-05
  resolved = yes), then the line bears VAT at the **maintained** TZ standard rate (not hard-coded)
  (FR-SALES-10, BR-SALES-05).
- **AC2** Given a **zero-rated** product, then the line bears 0% VAT but is counted as a taxable
  supply in the VAT summary (BR-SALES-05).
- **AC3** Given an **exempt** product, then the line bears no VAT and is excluded from the taxable base
  (BR-SALES-05).
- **AC4** Given a mix of statuses on one sale, then the document shows a **VAT summary by rate band**
  (FR-SALES-11), and the printed invoice carries the seller VRN and per-line tax (FR-SALES-13).
- **AC5** Given v1, then the invoice is a **VAT invoice but NOT a TRA EFD/VFD fiscal receipt** (no
  fiscal number/signing) — fiscalisation deferred, an accepted gap (FR-SALES-13, §10).
- **AC6** Given line prices are entered VAT-exclusive or VAT-inclusive (entry mode OQ-SALES-03b), then
  net/VAT/gross compute consistently with the chosen mode (FR-SALES-12). `[OQ-SALES-03b]`

### US-SALES-05 — Price override and line discount (permissioned, recorded)
**As a** sales clerk with override permission **I want** to adjust a line's price or apply a discount
**so that** I can honour an agreed price, with the change auditable.
- **AC1** Given a line, then the unit price **defaults from the company default price list, or the
  customer's price list if the customer has one** (FR-SALES-07).
- **AC2** Given `SALES.OVERRIDE`, when I change a line's unit price, then the override is accepted and
  **both the original list price and the applied price are recorded and audited** with operator + time
  (FR-SALES-08, BR-SALES-09, NFR-SALES-03).
- **AC3** Given I lack `SALES.OVERRIDE`, when I try to change the price, then it is refused
  (FR-SALES-25).
- **AC4** Given a **line discount** (percent or amount), then it is applied to the taxable base
  **before VAT** (FR-SALES-09).
- **AC5** Given a **document-level discount** (percent or amount), then it too is applied **before VAT**
  and the VAT recompute reflects it (FR-SALES-09).
- **AC6** Given a configured approval threshold and an override beyond it, then finalisation is blocked
  pending supervisor approval (BR-SALES-09). `[threshold value OQ-SALES-10]`

### US-SALES-06 — Take payment (cash + mobile money, split allowed) and issue a receipt
**As a** cashier **I want** to settle a sale by one or more tenders **so that** the customer pays and
gets a receipt.
- **AC1** Given a finalised sale, when I take payment by **cash** and/or **mobile money** covering the
  gross total, then the sale becomes **settled** and a **receipt** is produced (FR-SALES-17/18).
- **AC2** Given a split (part cash + part mobile money) that covers the total, then it is accepted as
  one settlement (FR-SALES-17).
- **AC3** Given **cash over-tender**, then **change** is computed and shown; the balance does not go
  negative (BR-SALES-07).
- **AC4** Given a payment, then it settles the sale **in the sale's own currency**; a different
  currency is refused (FR-SALES-19, BR-CUR-06).
- **AC5** Given credit is deferred in v1, then a sale must be **paid in full at finalise** to settle;
  there is **no partial-payment / outstanding-balance** state (FR-SALES-18).
- **AC6** Given tenders covering **less than** the gross total, then the sale **cannot finalise as
  settled** — it is held or cancelled (no receivable created) (FR-SALES-18, FR-SALES-20).

### US-SALES-07 — Sale records quantities but does not move stock (v1 — ACCEPTED RISK)
**As the** deployment owner **I want** v1 sales to record quantities without deducting stock **so
that** Sales ships before the Stock module — an **accepted risk** I have signed off (2026-06-07).
- **AC1** Given a finalised sale, then quantities sold are recorded but **no stock-on-hand is deducted
  anywhere** — an explicit accepted risk (FR-SALES-21, BR-SALES-11, §10).
- **AC2** Given a **composed product**, then it is sold as a **single priced line** and its recipe
  components are **not** deducted or cost-rolled-up (FR-SALES-06, Products §9).
- **AC3** Given the future Stock module, then finalising a sale is designed to emit a stock-deduction
  effect (composed-product component deduction included) via the **transactional outbox** without
  reworking the sale model (NFR-SALES-07).

### US-SALES-08 — Void a finalised sale (basic correction)
**As a** sales supervisor **I want** to void a wrongly-finalised sale within the permitted window **so
that** mistakes are corrected without editing finalised records.
- **AC1** Given `SALES.VOID` and a sale within the void window, when I void it, then the sale (and any
  receipt) is reversed and the void is audited (FR-SALES-22, NFR-SALES-03). `[void-window value: architect detail]`
- **AC2** Given I lack `SALES.VOID`, when I try to void a sale, then it is refused (FR-SALES-25).
- **AC3** Given a finalised sale, when I try to **edit** its lines/prices directly, then it is refused;
  the **only** v1 correction is a void (FR-SALES-03, BR-SALES-08).
- **AC4** Given v1, then **returns, credit notes, and refunds are out of scope** — void is the sole
  correction path (§12).

### US-SALES-09 — Sales are branch-scoped end to end
**As a** branch operator **I want** sales and their pick-lists confined to my active branch **so that**
I cannot transact across branches or companies by mistake.
- **AC1** Given I am active in branch B1, when I create a sale, then it is recorded at B1 in B1's
  company, and customer/agent/product selection shows only B1-associated, same-company records
  (FR-SALES-24, Parties FR-PARTY-12, Products FR-PROD-22).
- **AC2** Given I switch my active branch via the IAM branch-override header, then my sales view and
  pick-lists update to the new branch without re-login (mirrors US-IAM-003, US-PARTY-07).
- **AC3** Given any attempt to read or write a sale outside my scope, then it is refused; cross-tenant
  sale data never appears (NFR-SALES-01, BR-SALES-01).

---

## Stock — inventory on-hand & movement

Requirements: [docs/requirements/stock.md](docs/requirements/stock.md). Status: **Ratified
(owner-confirmed 2026-06-07).** Built **with Purchases** this round to close the "a sale must update
stock" gap. v1 = on-hand quantity per (stockable product, branch) in base units + an append-only
movement ledger; **overselling allowed (on-hand may go negative, flagged)**; composed-product sale
**explodes the recipe** to deduct components; **quantities only — NO valuation/COGS** (deferred). Stock
is the **first consumer of the transactional outbox** — it consumes `SALE.FINALISED` / `SALE.VOIDED`
(from Sales, ADR-0008 D-9) and `STOCK.RECEIVED` (from Purchases' **Goods Receipt**). Depends on IAM,
Products (stockable/base unit/recipe §9), the outbox (built this round, OQ-STOCK-09 RESOLVED). Scoped
per company + active branch. (All OQ-STOCK-01..10 RESOLVED — see open-questions.md.)

### US-STOCK-01 — Receive goods from a purchase increases on-hand (stock IN)
**As the** deployment owner **I want** finalising a purchase Goods Receipt (against a PO) to increase
on-hand **so that** inventory reflects what was actually received — the gap Sales left open.
- **AC1** Given a **Goods Receipt** is finalised against a PO in Purchases (US-PURCH-02), then a
  `STOCK.RECEIVED` outbox event is written in the same transaction with lines of `productId` +
  `qtyInBase` (FR-PURCH-08, FR-STOCK-06).
- **AC2** Given Stock consumes the event, then for each **stockable** line it posts a `GOODS_RECEIPT`
  in-movement (signed +, base units) and **increments on-hand** for that (product, branch)
  (FR-STOCK-06, BR-STOCK-01).
- **AC3** Given a line names a **non-stockable** product, then **no** stock movement is posted for it
  and the skip is recorded (BR-STOCK-02).
- **AC4** Given the event is **redelivered**, then on-hand is **not** incremented again (idempotent;
  FR-STOCK-13, BR-STOCK-08).
- **AC5** Given the receipt posts, then on-hand at the branch equals the signed sum of its movements
  (BR-STOCK-01, NFR-STOCK-02).

### US-STOCK-02 — Finalising a sale decreases on-hand (stock OUT)
**As the** deployment owner **I want** finalising a sale to decrease on-hand **so that** selling
draws inventory down — closing the accepted risk in sales.md §10.
- **AC1** Given a sale is finalised in Sales, then a `SALE.FINALISED` outbox event is written (payload
  per ADR-0008 D-9), and Stock consumes it (FR-STOCK-07).
- **AC2** Given a **simple stockable** product line, then Stock posts a `SALE_ISSUE` out-movement
  (signed −, `qtyInBase`) and **decrements on-hand** (FR-STOCK-07).
- **AC3** Given the issue exceeds on-hand, then on-hand goes **negative**, the negative level is
  **flagged**, and the sale is **not** blocked or reversed (overselling allowed; FR-STOCK-04,
  BR-STOCK-03).
- **AC4** Given a **non-stockable** product line (not composed), then **no** movement is posted
  (BR-STOCK-02).
- **AC5** Given the event is **redelivered**, then on-hand is **not** decremented again (idempotent;
  FR-STOCK-13).

### US-STOCK-03 — Selling a composed product deducts its components (recipe explosion)
**As the** deployment owner **I want** selling a composed product to deduct its recipe components
**so that** a restaurant dish draws down its ingredients, not a phantom "dish" stock.
- **AC1** Given a `SALE.FINALISED` line names a **composed** product (Products FR-PROD-14), then Stock
  reads its single-level recipe and posts a `SALE_ISSUE` for **each component** (qty = line
  `qtyInBase` × recipe qty, in the component's base unit), **not** for the composed product itself
  (FR-STOCK-08, BR-STOCK-04).
- **AC2** Given a recipe component is itself **non-stockable**, then it is **skipped** (no on-hand to
  deduct) and the skip is recorded; stockable components are still deducted (BR-STOCK-04,
  `[OQ-STOCK-03]`).
- **AC3** Given the composed product itself is non-stockable (e.g. a service dish), then it gets **no**
  movement of its own (only its components do) (BR-STOCK-04).
- **AC4** Given Products is single-level (FR-PROD-16), then no nested explosion occurs in v1.

### US-STOCK-04 — Void a sale reverses the stock issue (compensation)
**As the** deployment owner **I want** voiding a sale to put the issued stock back **so that** a
corrected sale does not permanently understate on-hand.
- **AC1** Given a finalised sale is voided in Sales, then a `SALE.VOIDED` outbox event is emitted and
  Stock consumes it (FR-STOCK-12).
- **AC2** Given Stock consumes it, then it posts `SALE_REVERSAL` in-movements reversing the original
  `SALE_ISSUE`(s) for that sale (including component issues of a composed product), restoring on-hand
  (FR-STOCK-12, BR-STOCK-06).
- **AC3** Given the void event is **redelivered**, then the reversal happens **only once** (idempotent;
  FR-STOCK-13).
- **AC4** Given a movement is reversed, then it is reversed by a **compensating movement**, never by
  editing or deleting the original (append-only; BR-STOCK-06).

### US-STOCK-05 — Adjust stock manually with a reason (permissioned)
**As a** stock controller **I want** to record a manual ± adjustment with a reason **so that** counts,
damage, and shrinkage are corrected and traceable.
- **AC1** Given `STOCK.ADJUST` and an active branch, when I post a signed (±) adjustment against a
  (product, branch) with a **mandatory reason**, then an `ADJUSTMENT` movement is posted and on-hand
  updated (FR-STOCK-09, BR-STOCK-05).
- **AC2** Given I omit the reason, then the adjustment is **rejected** (BR-STOCK-05).
- **AC3** Given I lack `STOCK.ADJUST`, then the adjustment is refused (FR-STOCK-15).
- **AC4** Given an adjustment posts, then it is written to the audit trail with actor, product/branch,
  signed quantity, reason, and timestamp (NFR-STOCK-05). `[reason set / approval threshold OQ-STOCK-04]`

### US-STOCK-06 — Seed an opening balance
**As a** stock controller **I want** to seed an initial on-hand for a product at a branch **so that**
pre-existing physical stock is reflected from go-live.
- **AC1** Given a never-tracked stockable product at my branch, when I record an `OPENING_BALANCE`
  in-movement, then on-hand is seeded to that quantity (FR-STOCK-10).
- **AC2** Given on-hand was already seeded/moved, then a further opening balance is handled per policy
  (recommended: treat additional changes as adjustments, not a second opening) (`[OQ-STOCK-05]`).
- **AC3** Given the seed posts, then on-hand equals the signed sum of its movements (BR-STOCK-01).

### US-STOCK-07 — View on-hand and movement history; negative/low flagged
**As a** stock controller **I want** to see current on-hand and the movement history per product at my
branch **so that** I can monitor levels and investigate negatives.
- **AC1** Given my active branch, when I view on-hand, then I see current quantity per stockable
  product, with a **negative flag** where on-hand < 0 (FR-STOCK-11, FR-STOCK-04).
- **AC2** Given a product, when I open its movement history, then I see the chronological ledger (type,
  signed qty, source reference, actor, timestamp) for that (product, branch) (FR-STOCK-11).
- **AC3** Given a reorder level is set (if adopted), then on-hand below it is flagged **low**
  (indicator-only, no auto-reorder) (`[OQ-STOCK-06]`).
- **AC4** Given I switch my active branch (IAM branch-override), then on-hand and history update to the
  new branch without re-login (FR-STOCK-14, mirrors US-IAM-003).

### US-STOCK-08 — Stock is quantity-only in v1 (NO valuation — owner-ruled)
**As the** deployment owner **I want** v1 Stock to track quantities without valuation **so that** the
module ships before Finance — a ruling I have made (2026-06-07).
- **AC1** Given any on-hand or movement, then it carries **quantity only** — no stock value, no unit
  cost, no COGS anywhere in v1 (FR-STOCK-16, §10).
- **AC2** Given a composed product is sold, then its components are deducted by **quantity**; **no**
  cost is rolled up from components (FR-STOCK-16, Products §9).
- **AC3** Given a purchase records cost on its GRN, then that cost is **not** carried into a stock
  value in v1 (BR-STOCK-10, BR-PURCH-09).
- **AC4** Given the future Finance round, then the v1 quantity model does **not preclude** adding
  per-movement cost and a valuation method (FIFO/avg) and COGS (NFR-STOCK-06).

### US-STOCK-09 — Stock is branch-scoped and tenant-isolated end to end
**As a** branch operator **I want** stock confined to my active branch **so that** I never see or move
another branch's or company's inventory.
- **AC1** Given I am active in branch B1, then on-hand, movements, and adjustments are for B1 in B1's
  company only (FR-STOCK-14, BR-STOCK-07).
- **AC2** Given any attempt to read or move stock outside my scope, then it is refused; cross-tenant
  stock never appears (NFR-STOCK-01).
- **AC3** Given branch-to-branch transfers are **deferred** (OQ-STOCK-08), then v1 moves stock only
  in (receipt), out (sale), and ± (adjustment) within a single branch.

---

## Purchases — buying from suppliers

Requirements: [docs/requirements/purchases.md](docs/requirements/purchases.md). Status: **Ratified
(owner-confirmed 2026-06-07).** Built **with Stock** this round. v1 is a **two-document** flow (owner
ruling, OQ-PURCH-01 RESOLVED): a **Purchase Order (PO)** is raised first (the commitment to buy;
ordered lines; `PO-####`; moves no stock), then a separate **Goods Receipt (GR/GRN)** is recorded
**against the PO** (`GRN-####`) to receive some or all of the ordered quantity — **the Goods Receipt
pushes stock IN** via the `STOCK.RECEIVED` outbox event (real stock-in from day one). **Partial
receipts** (multiple GRs per PO) with received-vs-ordered (outstanding) tracking are supported. v1
records **cost (money) on the PO/GR** but computes **no stock valuation, no VAT, no payable** (AP
deferred). Multi-step PO approval, supplier invoices/AP + the 3-way-match invoice leg,
returns-to-supplier, and landed cost are **deferred**. Depends on IAM, Parties (Supplier master),
Products, Multicurrency (ADR-0005), the outbox (built this round). Scoped per company + active branch.
(All OQ-PURCH-01..08 RESOLVED — see open-questions.md.)

### US-PURCH-01 — Raise a Purchase Order to a supplier (no stock effect)
**As a** purchasing officer **I want** to raise a Purchase Order with ordered quantities and costs
**so that** the supplier is committed and we have a record of what is on order before goods arrive.
- **AC1** Given `PURCHASE.CREATE` and an active branch, when I start a new PO, then it opens scoped to
  my company + branch (FR-PURCH-01a, NFR-PURCH-01).
- **AC2** Given I select a **supplier** associated with my branch (same company), then it is accepted;
  an archived or non-branch supplier is not selectable (FR-PURCH-03, BR-PURCH-02).
- **AC3** Given I add a product associated with my branch with an **ordered quantity + unit** and a
  **unit cost**, then a PO line is created, the quantity converts to base units (Products FR-PROD-06),
  and the cost carries its currency (FR-PURCH-04/05, BR-PURCH-04).
- **AC4** Given I **place the order**, then the PO gets a **number unique within the company**
  (`PO-####` via `code_sequence`), its ordered lines **freeze**, and it moves to **ORDERED**; **no
  stock moves** (FR-PURCH-02a, FR-PURCH-12, BR-PURCH-05).
- **AC5** Given the order is placed, then each PO line's **outstanding quantity = ordered** (nothing
  received yet) (FR-PURCH-07).
- **AC6** Given two officers place orders simultaneously, then they get **distinct** `PO-####` numbers
  (NFR-PURCH-04).
- **AC7** Given the PO is placed, then create→order is written to the audit trail (NFR-PURCH-03).

### US-PURCH-02 — Receive goods against a PO, in full or in part (stock IN)
**As a** storekeeper **I want** to record a Goods Receipt against a PO when goods arrive — receiving
all or only part of what was ordered **so that** on-hand reflects the actual delivery and the
remainder stays on order.
- **AC1** Given `PURCHASE.RECEIVE` and an active branch, when I start a **Goods Receipt against an
  outstanding PO**, then it opens with the PO's lines and their **outstanding quantities**
  (FR-PURCH-01b, FR-PURCH-07).
- **AC2** Given I enter, per line, a **received quantity ≤ the PO line's outstanding quantity**, then it
  is accepted; an over-receipt (more than outstanding) is **rejected** (FR-PURCH-07, BR-PURCH-10).
- **AC3** Given I **receive** (finalise) the Goods Receipt, then it gets a **number unique within the
  company** (`GRN-####` via `code_sequence`) and its content becomes immutable (FR-PURCH-12,
  BR-PURCH-05, BR-PURCH-07).
- **AC4** Given the Goods Receipt is received, then in the **same transaction** a `STOCK.RECEIVED`
  outbox event is written (lines of `productId` + `qtyInBase`) and Stock increments on-hand
  (FR-PURCH-08, US-STOCK-01).
- **AC5** Given a **partial receipt**, then the PO advances to **partially RECEIVED**, the received
  quantity is deducted from each line's outstanding, and the remainder can be received on a **later
  Goods Receipt against the same PO** until fully received (FR-PURCH-02a, FR-PURCH-07).
- **AC6** Given all lines are fully received, then the PO is **fully RECEIVED** (and may be CLOSED)
  (FR-PURCH-02a).
- **AC7** Given two storekeepers receive against the same PO simultaneously, then they get **distinct**
  `GRN-####` numbers and the outstanding quantity stays consistent — no over-receipt (NFR-PURCH-04,
  NFR-PURCH-07).
- **AC8** Given the Goods Receipt is received, then it is written to the audit trail (NFR-PURCH-03).

### US-PURCH-03 — Cost is recorded but inventory is not valued (v1)
**As the** deployment owner **I want** the PO/GR to record what goods cost without valuing inventory
**so that** Purchases ships before Finance, consistent with quantity-only Stock.
- **AC1** Given a PO line, then a **unit cost** (a monetary amount) is recorded and the PO totals the
  lines; a Goods Receipt inherits the PO line's cost (FR-PURCH-05, FR-PURCH-06).
- **AC2** Given a Goods Receipt is received, then the recorded cost is **not** carried into a stock
  value, and no COGS or valuation is computed anywhere (FR-PURCH-13, BR-PURCH-09, stock.md §10).
- **AC3** Given a placed PO / received GR, then it creates **no accounts-payable balance and takes no
  payment** and **no input VAT is computed**; the cost is captured for the record and the future
  AP/valuation rounds (BR-PURCH-08). `[OQ-PURCH-04 RESOLVED = no VAT, cost required; OQ-PURCH-05
  RESOLVED = AP deferred]`
- **AC4** Given a goods line, then a **unit cost is required** (zero only for a free/sample line with a
  reason) (FR-PURCH-05, OQ-PURCH-04 RESOLVED).
- **AC5** Given the future Finance round, then the v1 model does **not preclude** carrying cost into
  valuation or raising a payable (NFR-PURCH-05).

### US-PURCH-04 — A purchase line may name a non-stockable product
**As a** purchasing officer **I want** to record buying a non-stockable item on a PO/GR **so that** the
purchase is captured even when it moves no stock.
- **AC1** Given a line names a **non-stockable** product, then it is recorded on the PO/GR with its cost
  (BR-PURCH-03).
- **AC2** Given the Goods Receipt is received, then the non-stockable line emits **no** stock movement
  (Stock skips it) while stockable lines still increment on-hand (FR-PURCH-08, BR-STOCK-02).
- **AC3** Given service/expense purchases are **deferred** (OQ-PURCH-08 RESOLVED), then v1's PO/GR are
  intended for **goods that move stock**; buying a pure service is out of v1 scope.

### US-PURCH-05 — Void a received Goods Receipt (reverses the stock-in)
**As a** branch manager **I want** to void a wrongly-received Goods Receipt **so that** the erroneous
stock-in is backed out without editing a finalised document, and the PO returns to outstanding.
- **AC1** Given `PURCHASE.VOID` and a received Goods Receipt within the permitted window, when I void
  it, then the GR is reversed, a compensating stock event is emitted, and Stock reverses the
  `GOODS_RECEIPT` (FR-PURCH-09).
- **AC2** Given a Goods Receipt is voided, then the received quantity is **restored to the PO lines'
  outstanding**, so the PO can be re-received (FR-PURCH-09, BR-PURCH-10).
- **AC3** Given I lack `PURCHASE.VOID`, then the void is refused (FR-PURCH-11).
- **AC4** Given a received Goods Receipt, when I try to **edit** its lines/costs directly, then it is
  refused; the only v1 correction is a void (BR-PURCH-05).
- **AC5** Given v1, then **returns to supplier / debit notes** are out of scope — void is the sole
  correction path (OQ-PURCH-06 RESOLVED).
- **AC6** Given the void event is redelivered, then Stock reverses only once (idempotent, BR-PURCH-06).

### US-PURCH-06 — Purchases are branch-scoped and tenant-isolated
**As a** branch operator **I want** POs, GRs, and their pick-lists confined to my active branch **so
that** I cannot purchase across branches or companies by mistake.
- **AC1** Given I am active in branch B1, when I create a PO or a Goods Receipt, then it is recorded at
  B1 in B1's company, and supplier/product/PO selection shows only B1-associated, same-company records
  (FR-PURCH-10, Parties FR-PARTY-12, Products FR-PROD-22).
- **AC2** Given I switch my active branch via the IAM branch-override header, then my PO/GR views and
  pick-lists update to the new branch without re-login (mirrors US-IAM-003).
- **AC3** Given any attempt to read or write a PO/GR outside my scope, then it is refused; cross-tenant
  purchase data never appears (NFR-PURCH-01, BR-PURCH-01).

## Routes — sales areas / zones for external field agents

Requirements: [docs/requirements/routes.md](docs/requirements/routes.md). Status: **RATIFIED
(owner-confirmed 2026-06-08).** v1 = a per-company **Route** master (code `ROUTE-####`, name, free-text
location, MasterStatus, audit), sibling to Customer/Agent; **route↔customer = many-to-many** (all
customers routable); **route↔agent = many-to-many, EXTERNAL agents only** (+ optional advisory primary
agent); **route↔branch** company-owned, branch-filtered, can span branches; **geography = free-text only**
(not bound to region/district); the **sales invoice gains a nullable route**, **defaulted from the selling
agent's primary route, editable, optional** (never blocks a sale); permissions `ROUTE.VIEW` /
`ROUTE.MANAGE` / `ROUTE.ASSIGN`; per-company scope, `assertCanActIn` on every read, audit on every
mutation. **Captured-not-validated** invoice route is an accepted risk (routes.md §10). Deferred:
agent-must-match-customer validation, route/sales-by-route reporting, scheduling/visit-days, geo-hierarchy
(`route_geography`), sequencing, van-stock. Depends on IAM, Parties (EXTERNAL Agent FR-PARTY-13, Customer,
the `customer_branch`/`agent_branch` pattern), Sales (ADR-0008 — adds the nullable invoice route). Mirrors
the Products master+junction+RBAC pattern. Next: solutions-architect ADR-0012 (routes data model, `V9`) +
the small additive Sales invoice route field.

### US-ROUTE-01 — Define a route with a free-text location
**As a** route administrator / sales manager **I want** to create a named sales area with a description
**so that** the territories my field agents cover are explicit master data.
- **AC1** Given I am logged in with `ROUTE.MANAGE` and an active branch, when I create a route with a name
  and a **free-text location identifier**, then it is saved scoped to my company and numbered
  **`ROUTE-####`** from the per-company `code_sequence` (FR-ROUTE-01/16, BR-ROUTE-06, NFR-ROUTE-01).
- **AC2** Given the route is created, then its **geography is the free text only** — it is **not** bound to
  any customer's region/district nor to a geo-hierarchy (FR-ROUTE-03, BR-ROUTE-08).
- **AC3** Given I associate the route with one or more **branches of its company**, then it becomes
  visible/selectable at those branches and may **span branches** (FR-ROUTE-11).
- **AC4** Given I archive a route, then it is excluded from new assignments and new invoices but stays on
  historical invoices and is restorable (FR-ROUTE-02, BR-ROUTE-07).
- **AC5** Given create/edit/archive, then each is written to the audit trail with actor, branch, and
  timestamp (NFR-ROUTE-03).

### US-ROUTE-02 — Assign customers to a route (many-to-many)
**As a** route administrator **I want** to group customers into a route **so that** a field agent knows
which customers fall in their area.
- **AC1** Given `ROUTE.ASSIGN`, when I add customers to a route, then they become members; a **customer may
  belong to several routes** and a route holds many customers (FR-ROUTE-04/05).
- **AC2** Given **all customer sub-kinds are routable**, when I add a **cash/walk-in** or a
  **credit/account** customer, then both are accepted (FR-ROUTE-04).
- **AC3** Given a customer of **another company**, when I try to add it, then it is **rejected** — a route's
  customers must share its company (BR-ROUTE-03).
- **AC4** Given a customer's region/district is set, then that **does not** assign them to any route, and
  assigning a route **does not** change their address — membership is a separate explicit assignment
  (FR-ROUTE-06, BR-ROUTE-08).
- **AC5** Given each add/remove, then the assignment mutation is audited (NFR-ROUTE-03).

### US-ROUTE-03 — Assign EXTERNAL agents to a route (internal excluded) with an optional primary
**As a** route administrator **I want** to assign external field agents to a route and optionally mark one
primary **so that** territory coverage is recorded and sales default to the right route.
- **AC1** Given `ROUTE.ASSIGN`, when I add an **EXTERNAL** agent to a route, then it is assigned; an
  external agent may cover **several** routes and a route may have **several** external agents (FR-ROUTE-07/08).
- **AC2** Given an **INTERNAL** agent, when I try to assign it to a route, then it is **rejected** — only
  external agents are route-assignable (BR-ROUTE-02).
- **AC3** Given a route's assigned external agents, when I flag one as **primary**, then it is recorded as
  the route's **at-most-one advisory primary** — non-exclusive (other assigned agents still cover the
  route) and used only to default the invoice route (FR-ROUTE-09, BR-ROUTE-04).
- **AC4** Given I flag an agent **not** assigned to the route as primary, then it is **rejected** — the
  primary must be one of the route's assigned external agents (BR-ROUTE-04).
- **AC5** Given an agent of **another company**, when I try to assign it, then it is **rejected**
  (BR-ROUTE-03).

### US-ROUTE-04 — Route defaults onto a sale from the agent's primary route (optional, editable)
**As a** sales clerk **I want** the sale's route to default from the selling agent's primary route
**so that** field sales are attributed to an area without extra keying — but I can change or clear it.
- **AC1** Given the selling **external** agent has a **primary route** associated with my active branch,
  when I start a sale, then the invoice **route defaults to that route** (FR-ROUTE-13, FR-ROUTE-12).
- **AC2** Given the route↔customer link is many-to-many, then the route is **NOT** auto-derived from the
  customer; it defaults from the **agent's primary route** (FR-ROUTE-14).
- **AC3** Given the selling agent has **no primary route** (or is internal, or none assigned), then the
  invoice route defaults to **blank**, which is valid (FR-ROUTE-14, BR-ROUTE-05).
- **AC4** Given any default, when I **edit or clear** the route, then my choice is kept; the route is
  **optional** and a **blank route never blocks finalisation** (FR-ROUTE-13, BR-ROUTE-05).
- **AC5** Given I finalise the sale, then the route is **captured on the invoice but NOT validated** against
  the customer's or agent's route memberships — captured-not-validated (FR-ROUTE-15, BR-ROUTE-09,
  routes.md §10 accepted risk).
- **AC6** Given an **archived** route, then it is not offered as a route on a new sale (BR-ROUTE-07).

### US-ROUTE-05 — Routes are branch-scoped and tenant-isolated end to end
**As a** branch operator **I want** routes and their pick-lists confined to my active branch and company
**so that** I cannot see or attribute sales to another branch's or company's routes.
- **AC1** Given I am active in a branch, when I select a route on a sale, then I see **only routes
  associated with my active branch** and only my company's routes (FR-ROUTE-12, FR-ROUTE-10).
- **AC2** Given I switch my active branch via the IAM branch-override header, then my route pick-list
  updates to the new branch without re-login (mirrors US-IAM-003).
- **AC3** Given any attempt to read or write a route, assignment, or branch association outside my scope,
  then it is refused (`assertCanActIn` on every read path); cross-tenant route data never appears
  (NFR-ROUTE-01, BR-ROUTE-01/03).

---

## GL — General Ledger / Financial Accounting (the books)

Requirements: [docs/requirements/gl.md](docs/requirements/gl.md). Status: **RATIFIED (owner-confirmed
2026-06-08).** GL **Increment 1** of the full-ERP roadmap (docs/ROADMAP.md T1.1 / §5) — the critical-path
gate: nothing reports until the books exist. v1 = a **per-company chart of accounts** (numeric ranges,
**system-seeded** standard TZ small-business set, **editable**, account **type** drives statement
placement + normal balance, can't delete a posted-to account); **manual journal entries** (must balance
Σ debits == Σ credits before posting; incl. **opening balances**); **automatic posting of a sale on
finalise** (a `SalesPostingHandler` consuming **`SALE.FINALISED`** over the outbox — **DR AR/Cash, CR
Sales Revenue, CR VAT Payable** via a configurable **`gl_configs`** account map; idempotent) and
**reversal on void** (**`SALE.VOIDED`**); a **fiscal calendar** (12 monthly periods, **configurable
fiscal-year start month**, **open/close**, **closed-period posting rejected**); an **append-only
immutable ledger** (corrections are **reversing entries**, never edit/delete — PROJECT-CONVENTIONS §3.6);
a **trial balance** read that **nets to zero**; **base-currency-only** posting (FX revaluation deferred).
Permissions `GL.VIEW` / `GL.MANAGE` (CoA + config) / `GL.POST` (manual journals) / `GL.PERIOD.CLOSE`;
per-company scope; `assertCanActIn` on every read; audit on every post and close. **Deferred (separate
later increments):** AR/AP control-account sub-ledger posting & reconciliation (T1.2/T1.3), COGS/inventory
posting (T2.2), Cash/Bank posting (T1.4), VAT return (T1.5), FX revaluation (X.6), year-end-close
automation, per-category revenue/VAT mapping, P&L/Balance-Sheet statements (Reporting T2.3). Consumes
`SALE.FINALISED`/`SALE.VOIDED` from Sales via the outbox (ADR-0009, **DTO-only**); reuses Money (ADR-0005),
RBAC, audit, `code_sequence` (journal batch). Next: solutions-architect **ADR-0013** (GL data model:
chart_of_accounts, journal_batches/entries/lines, fiscal_periods, gl_configs; **V10** migration; the
`SalesPostingHandler` + `SaleVoidingHandler`; `ScopeGuard` "account" case; TZ CoA seed).

### US-GL-01 — Seed and maintain the chart of accounts
**As a** financial controller **I want** a ready-made chart of accounts I can edit **so that** my books
start with a standard Tanzanian small-business structure I can tailor without building it from scratch.
- **AC1** Given a new company, when it is set up, then a **standard TZ small-business chart of accounts is
  seeded per company** — organised by numeric range (1000s Assets, 2000s Liabilities, 3000s Equity, 4000s
  Income, 5000s Expenses) and including at minimum Cash, Bank, Accounts Receivable, Inventory, Accounts
  Payable, VAT Payable, Owner's Equity, Retained Earnings, Sales Revenue, and Cost of Goods Sold
  (FR-GL-01/02).
- **AC2** Given `GL.MANAGE`, when I add an account with a **code unique within my company**, a name, and a
  valid **account type** (ASSET/LIABILITY/EQUITY/INCOME/EXPENSE), then it is saved scoped to my company;
  a duplicate code is rejected (FR-GL-03, BR-GL-05).
- **AC3** Given an account's **type**, then it drives **statement placement** (INCOME/EXPENSE → P&L;
  ASSET/LIABILITY/EQUITY → Balance Sheet) and **normal balance** (ASSET/EXPENSE = debit;
  LIABILITY/EQUITY/INCOME = credit) — the type, not the range alone, is the authority (FR-GL-05, BR-GL-12).
- **AC4** Given an account **with postings**, when I try to **delete** it, then it is **refused** — I may
  only **deactivate** it; a deactivated account is excluded from new postings but stays on historical
  entries and the trial balance (FR-GL-03/04, BR-GL-04/07).
- **AC5** Given each add / edit / deactivate, then it is written to the audit trail with actor, company,
  and timestamp (NFR-GL-06).
- **AC6** Given any read of the chart of accounts, then I see **only my company's** accounts
  (`assertCanActIn`); cross-company accounts never appear (NFR-GL-01, BR-GL-05).

### US-GL-02 — Post a manual journal entry (balanced-or-rejected)
**As an** accountant **I want** to post balanced double-entry journals **so that** I can record accruals,
adjustments, and reclassifications the automatic postings don't cover.
- **AC1** Given `GL.POST` and an active company, when I compose a journal with a date, a description, and
  **two or more lines** — each naming **one active account** with a **debit OR a credit** amount — then I
  see the running debit/credit totals and whether it balances (FR-GL-06).
- **AC2** Given the entry **balances** (Σ debits == Σ credits, ≥ 2 lines, date in an **OPEN** period, every
  account active, every line one-sided), when I post, then it is written to the **append-only books** under
  a **`JB-####`** batch and the post is audited (FR-GL-06/07, BR-GL-01, NFR-GL-05/06).
- **AC3** Given the entry **does not balance**, when I post, then it is **rejected** with the
  debit/credit difference shown and **nothing is written** (FR-GL-07, BR-GL-01).
- **AC4** Given a line names an **inactive** account, or carries **both** a debit and a credit (or
  **neither**), when I post, then it is **rejected** (FR-GL-09, BR-GL-04/08).
- **AC5** Given the entry date falls in a **CLOSED** period, when I post, then it is **rejected** until the
  period is reopened or the date moved to an open period (FR-GL-08, BR-GL-03).

### US-GL-03 — Finalising a sale auto-posts a balanced journal entry
**As a** financial controller **I want** every finalised sale to post itself to the books **so that**
revenue and VAT are recorded without anyone keying a journal.
- **AC1** Given a sale **finalises** and emits **`SALE.FINALISED`**, when the `SalesPostingHandler`
  consumes it, then a **balanced** journal entry is posted using the **`gl_configs`** account map:
  **DR Accounts Receivable / Cash** for the **gross**, **CR Sales Revenue** for the **net**, **CR VAT
  Payable** for the **VAT** — derived from the invoice's net/VAT/gross totals (FR-GL-10).
- **AC2** Given the entry is composed, then **net + VAT == gross**, so the entry is **balanced by
  construction** (Σ debits == Σ credits) and posted under the **originating event's company/branch
  context** (FR-GL-10, BR-GL-01).
- **AC3** Given the **same `SALE.FINALISED` is redelivered**, when the handler runs again, then it posts
  **no second entry** — the books move **once** (idempotency marker, FR-GL-11, BR-GL-09, NFR-GL-03).
- **AC4** Given the required **`gl_configs` mappings are missing** (SALES_REVENUE / VAT_PAYABLE / AR or
  CASH), when a `SALE.FINALISED` arrives, then the handler **fails the event** (retry/park per the outbox)
  rather than posting to a null/wrong account; once finance sets the mapping (`GL.MANAGE`), the replayed
  event posts the sale (FR-GL-18, BR-GL-10).
- **AC5** Given the handler consumes the event, then it reads the invoice as a **DTO / event payload** and
  **imports no Sales entity** (NFR-GL-07).
- **AC6** Given the post, then it is audited as a **SYSTEM** action (no logged-in user) bounded by the
  event's company context (NFR-GL-06, FR-GL-19).

### US-GL-04 — Voiding a sale posts the reversing entry
**As a** financial controller **I want** a voided sale to reverse itself on the books **so that** the
books never carry a sale that was undone, without anyone deleting a posting.
- **AC1** Given a sale is **voided** and emits **`SALE.VOIDED`**, when the `SaleVoidingHandler` consumes
  it, then it posts the **reversing entry** for the original sales journal (the original DR becomes a CR and
  vice versa) so the net effect on every account is **zero** (FR-GL-12, BR-GL-11).
- **AC2** Given the reversal posts, then the **original entry is retained** beside it — the void
  **reverses, never deletes** (append-only, BR-GL-02, NFR-GL-04).
- **AC3** Given the **same `SALE.VOIDED` is redelivered**, when the handler runs again, then it posts **no
  second reversal** (idempotency, FR-GL-12, BR-GL-09, NFR-GL-03).
- **AC4** Given a `SALE.VOIDED` for a sale that was **never posted** (out-of-order), when the handler runs,
  then it records an **anomaly** for review rather than posting a phantom reversal (FR-GL-12).

### US-GL-05 — Open and close fiscal periods
**As a** financial controller **I want** to open and close monthly fiscal periods **so that** posting into
a finalised month is prevented and the year can be closed cleanly.
- **AC1** Given my company's **fiscal-year start month is configured** (e.g. January or July), then the
  year has **12 monthly periods** from that start (FR-GL-14).
- **AC2** Given `GL.PERIOD.CLOSE`, when I **close** an open period, then it is marked CLOSED and the act is
  audited; subsequent postings dated in it are **rejected** (FR-GL-15, FR-GL-08, BR-GL-03, NFR-GL-06).
- **AC3** Given a **closed** period, when I **reopen** it (`GL.PERIOD.CLOSE`), then postings dated in it
  are accepted again, audited (FR-GL-15).
- **AC4** Given I **close period 12**, then the fiscal year's end state is available to seed the next
  year's **opening balances** (entered as a manual opening-balance journal — full year-end-close
  automation is deferred, FR-GL-15, gl.md §10.6).
- **AC5** Given an **auto-post** (`SALE.FINALISED`) would fall in a **closed** period, then the handler
  **fails-and-retries** (closed-period rejection applies to automatic posting too) until the period is
  reopened — no sale posts to a closed period (BR-GL-03, OQ-GL-01).

### US-GL-06 — View the trial balance (the books prove out)
**As an** accountant / auditor **I want** a trial balance for my company **so that** I can confirm the
books are balanced and feed the financial statements.
- **AC1** Given `GL.VIEW`, when I request a trial balance **as-at a date** or **over a period**, then I see
  every account with its **total debits and total credits** (and net balance), scoped to my company
  (FR-GL-16/17).
- **AC2** Given a sound set of books, then the trial balance's **total debits == total credits** (the TB
  **nets to zero**) — the acceptance proof (FR-GL-16).
- **AC3** Given a finalised sale has auto-posted, then it appears on the trial balance (AR/Cash debit =
  Sales Revenue + VAT Payable credit); given that sale is then voided, the reversal nets it back out and
  the TB still balances (FR-GL-10/12/16 — roadmap Increment 1 acceptance bar).
- **AC4** Given any trial-balance or journal read, then it returns **only my company's** books
  (`assertCanActIn`); cross-company figures never appear (NFR-GL-01, BR-GL-05).

### US-GL-07 — Enter opening balances
**As an** accountant **I want** to enter the books' opening balances **so that** the ledger starts from the
business's actual position when GL goes live.
- **AC1** Given `GL.POST`, when I enter opening balances as a **manual journal** (assets debited,
  liabilities/equity credited, the balancing figure to equity / retained earnings) into the **first open
  period**, then it posts like any other journal (FR-GL-13, FR-GL-06).
- **AC2** Given the opening-balance journal **does not balance** (Σ debits ≠ Σ credits), when I post, then
  it is **rejected** — opening balances obey the same double-entry invariant (BR-GL-01).
- **AC3** Given opening balances are posted, then the trial balance reflects them and **nets to zero**
  (FR-GL-16).
- **AC4** Given a posted opening-balance entry needs correcting, then I correct it with a **reversing
  entry** then a correct re-post — never by editing the posted entry (BR-GL-02).

---

## AR — Accounts Receivable (the customer sub-ledger)

Requirements: [docs/requirements/accounts-receivable.md](docs/requirements/accounts-receivable.md). Status:
**RATIFIED (owner-confirmed 2026-06-09).** AR is half of **Increment 2** of the full-ERP roadmap
(docs/ROADMAP.md T1.2 / §5), built in parallel with [AP](#ap--accounts-payable-the-supplier-sub-ledger). AR
is the **customer sub-ledger** — the per-customer detail (open items, receipts, allocations, balances,
ageing) behind the GL **`1200 Accounts Receivable`** control account. v1 = **open items from credit sales**
(a finalised **credit-account** sale auto-creates an open item via `SALE.FINALISED`; a **cash** sale creates
none); **receipts + allocation** (**auto oldest-open-first** by default, **manual override**, **on-account /
unapplied** receipts allowed, over-allocation rejected); **balances + ageing** (Current / 1–30 / 31–60 /
61–90 / 90+ by due date); **customer statements** (view/print); **write-offs** (bad-debt) and **credit
notes** (reduce a receivable); **opening balances**; and a **credit-limit check on the Sales finalise path**
(**warn + allow with `SALES.CREDIT.OVERRIDE`**, audited — an additive Sales touch). **The reconciliation
rule:** a credit sale's GL entry **already** debited the AR control (Sales' `SalesPostingHandler`) — so AR
creating the open item **must NOT post to GL again** (no double-post); a **receipt** IS a new event → AR
records receipt+allocation **and** posts **DR Cash/Bank / CR AR control**. The AR sub-ledger total must
**reconcile** to the GL AR control account at all times. Permissions `AR.VIEW` / `AR.INVOICE.VIEW` /
`AR.RECEIPT.RECORD` / `AR.RECEIPT.ALLOCATE` / `AR.WRITEOFF` / `AR.STATEMENT.VIEW` / `AR.OPENING.SET` (+
`SALES.CREDIT.OVERRIDE`); per-company scope; `assertCanActIn` on every read; audit on every mutation;
receipts numbered `RCT-####` via `code_sequence`. **Deferred:** Cash & Bank module (T1.4 — v1 posts the
receipt cash leg directly to a Cash/Bank GL account), payment-terms master, dunning / statement emailing /
overdue interest, multi-currency AR / FX revaluation, full returns machinery (Sales T2.1). Consumes
`SALE.FINALISED` / `SALE.VOIDED` (ADR-0009, **DTO-only**); posts to the existing GL (ADR-0013) control
accounts; reuses Money (ADR-0005), RBAC, audit, `code_sequence`. Next: solutions-architect **ADR-0014** (AR
data model, **V11**) incl. the sub-ledger⇄GL-control reconciliation design, the GL-posting mechanism choice,
and the Sales credit-limit additive touch.

### US-AR-01 — A finalised credit sale creates a receivable (system-driven, no GL double-post)
**As a** credit controller **I want** every credit sale to create its own receivable automatically **so
that** I always know who owes us, without re-keying the invoice or double-counting it on the books.
- **AC1** Given a **credit-account** sale finalises and emits **`SALE.FINALISED`**, when the AR open-item
  handler consumes it, then an **AR open item** is created for the **gross** amount, with an invoice date
  and a **due date** (from customer terms, else net-on-receipt / 0 days), under the event's company/branch
  context (FR-AR-01/03).
- **AC2** Given a **cash / walk-in** sale finalises, when the handler runs, then **no AR open item** is
  created — a cash sale is settled at the till (FR-AR-02, BR-AR-01).
- **AC3** Given the open item is created, then **AR posts NOTHING to GL** — the credit sale's
  `SalesPostingHandler` already debited the **AR control** account; AR records only the sub-ledger detail
  (FR-AR-05, BR-AR-02 — no double-post).
- **AC4** Given the **same `SALE.FINALISED` is redelivered**, when the handler runs again, then **no second
  open item** is created — one open item per invoice (idempotency, FR-AR-04, BR-AR-08, NFR-AR-04).
- **AC5** Given the handler runs, then it reads the invoice/customer as a **DTO / event payload** and
  **imports no Sales or Parties entity** (NFR-AR-06); the create is audited as a **SYSTEM** action
  (NFR-AR-03).
- **AC6** **(Reconciliation bar)** Given a credit sale has finalised, then the new **AR open item amount
  equals the AR-control debit** Sales posted (same amount) — the sub-ledger total reconciles to the GL
  `1200 Accounts Receivable` control balance (FR-AR-18, NFR-AR-01).

### US-AR-02 — Record a receipt and allocate it oldest-open-first (with manual override)
**As a** cashier / receipts clerk **I want** to record a customer's payment and allocate it to their open
invoices **so that** their balance and ageing are correct and the cash is on the books.
- **AC1** Given `AR.RECEIPT.RECORD`, when I record a **receipt** (`RCT-####`) for an amount in the sale
  currency, then it is allocated **oldest-open-first by default** (pays the oldest-due open items until
  exhausted) (FR-AR-06/07, BR-AR-03).
- **AC2** Given `AR.RECEIPT.ALLOCATE`, when I **manually override** the allocation (re-pick which open items
  the receipt settles), then the receipt is re-applied per my selection; a fully-allocated open item is
  **closed** (FR-AR-07).
- **AC3** Given the receipt is recorded, then GL posts **once**: **DR Cash/Bank** (`gl_configs` `CASH`) **/
  CR the AR control** for the **receipt amount**; the sub-ledger open items drop by the **allocated** amount
  (FR-AR-06/16, BR-AR-12).
- **AC4** **(Reconciliation bar)** Given a credit sale (US-AR-01) and then a receipt that settles it, when
  both have posted, then the **customer's AR balance is reduced correctly** AND the **GL AR control nets
  correctly** (control debit from the sale − control credit from the receipt = remaining open balance);
  the sub-ledger total equals the control balance (FR-AR-18, NFR-AR-01).
- **AC5** Given I try to **over-allocate** (total allocated > receipt amount), when I save, then it is
  **rejected**; the remainder may stay on-account (FR-AR-10, BR-AR-04).
- **AC6** Given I **re-allocate** an already-recorded receipt (move its applied amount between open items),
  then it is a **sub-ledger-only** change and **nothing further posts to GL** (FR-AR-11, BR-AR-12); both the
  record and the re-allocation are audited (NFR-AR-03).

### US-AR-03 — Take an on-account (unapplied) receipt
**As a** receipts clerk **I want** to accept money that isn't yet matched to an invoice **so that** a
customer can pay in advance or over-pay and the credit is held against their account.
- **AC1** Given `AR.RECEIPT.RECORD`, when I record a receipt with **no allocation** (or allocate less than
  the receipt), then the unallocated amount stands as an **on-account credit balance** on the customer
  (FR-AR-09, BR-AR-05).
- **AC2** Given an on-account credit exists, when a later open item is created or selected, then the credit
  can be **applied** to it (`AR.RECEIPT.ALLOCATE`), reducing that open item (FR-AR-09).
- **AC3** Given the receipt was recorded, then GL was posted **DR Cash/Bank / CR AR control** for the full
  receipt amount once — the on-account portion is a sub-ledger credit, not a separate GL event
  (FR-AR-16, BR-AR-12).

### US-AR-04 — View and print a customer statement (open items + ageing)
**As a** credit controller **I want** a customer statement **so that** I can see what a customer owes, how
overdue, and chase it.
- **AC1** Given `AR.STATEMENT.VIEW`, when I open a customer's **statement** as at a date, then I see the
  customer's **open items** and an **ageing** breakdown — **Current / 1–30 / 31–60 / 61–90 / 90+** days by
  **due date** — plus recent receipts/credit notes (FR-AR-08/12).
- **AC2** Given the statement, when I print it, then it renders for view/print (no emailing / dunning in
  v1) (FR-AR-12).
- **AC3** Given any statement or balance read, then it returns **only my company's** receivables
  (`assertCanActIn`); cross-company figures never appear (FR-AR-20, BR-AR-07, NFR-AR-01).

### US-AR-05 — Write off a bad debt
**As a** credit controller **I want** to write off an uncollectable receivable **so that** the books and the
sub-ledger stop carrying a debt we won't collect.
- **AC1** Given `AR.WRITEOFF`, when I write off an open item (with a reason), then the open item is
  **closed** in the sub-ledger and GL posts **DR bad-debt expense** (`gl_configs`) **/ CR the AR control**
  for the written-off amount (FR-AR-13, BR-AR-06).
- **AC2** Given the write-off posts, then the customer's balance and the GL AR control drop by the **same
  amount** (reconciled, FR-AR-18); the write-off is **audited** (NFR-AR-03).
- **AC3** Given a posted write-off needs correcting, then I correct it with a **reversal / credit
  adjustment**, never by editing the posted entry (append-only, BR-AR-09).

### US-AR-06 — Enter AR opening balances at go-live
**As an** accountant **I want** to enter customers' pre-existing receivables **so that** AR starts from the
business's actual debtor position when it goes live.
- **AC1** Given `AR.OPENING.SET`, when I enter each customer's pre-existing receivable (amount, invoice
  date, due date) as an **opening open item**, then it is recorded in the sub-ledger (FR-AR-15).
- **AC2** Given opening open items are entered, then the **sum equals the AR control account's opening
  balance** (the GL side is the opening-balance journal, gl.md FR-GL-13) — reconciliation holds from day
  one (FR-AR-15, BR-AR-02).
- **AC3** Given an opening open item is wrong, then it is corrected via a **reversal / credit note**, not by
  editing a posted entry (BR-AR-09).

### US-AR-07 — Credit-limit check on the Sales finalise path (warn + override)
**As a** sales clerk **I want** to be warned when a credit sale would push a customer over their credit
limit **so that** we don't over-extend a customer without a manager's say-so.
- **AC1** Given a **credit** sale is finalised, when the finalise path computes **(current AR balance) +
  (this sale's gross)** and it **exceeds** the customer's `credit_limit_amount`, then I am **warned**
  (FR-AR-19, BR-AR-10).
- **AC2** Given I am warned and I **hold `SALES.CREDIT.OVERRIDE`**, when I confirm, then the sale is
  **allowed** and the override is **audited** (customer, balance, limit, amount, operator, time)
  (FR-AR-19, NFR-AR-03).
- **AC3** Given I am warned and I do **not** hold `SALES.CREDIT.OVERRIDE`, when I try to finalise, then the
  credit sale is **blocked** until the balance is reduced or the limit raised (FR-AR-19, BR-AR-10).
- **AC4** Given a **cash** sale, when it is finalised, then the credit-limit check is **not** applied (no
  receivable arises) (FR-AR-02, FR-AR-19).

---

## AP — Accounts Payable (the supplier sub-ledger)

Requirements: [docs/requirements/accounts-payable.md](docs/requirements/accounts-payable.md). Status:
**RATIFIED (owner-confirmed 2026-06-09).** AP is half of **Increment 2** of the full-ERP roadmap
(docs/ROADMAP.md T1.3 / §5), built in parallel with [AR](#ar--accounts-receivable-the-customer-sub-ledger).
AP is the **supplier sub-ledger** — the per-supplier detail (bills, payables, payments, balances) behind the
GL **`2100 Accounts Payable`** control account. v1 is **bill-entry-driven**: an operator **enters a supplier
bill** (`BILL-####`); it is **3-way matched** against the **PO** and the **Goods Receipt** (**quantity AND
price**) within a **tolerance**; a bill **within tolerance** becomes a **payable** and **posts to GL**; a
bill **outside tolerance** is **held for review**. **A goods receipt alone does NOT create a payable** (no
GRN accrual in v1 — accepted risk: the liability is not on the books between receipt and bill entry). v1
also has **single bill payment + payment runs** (`PAYRUN-####` batch-selects due/matched bills → one
payment), **debit notes/adjustments** (reduce an open payable), and **opening balances**. **The
reconciliation rule (mirror of AR):** the goods receipt posted **Stock only, NOT GL** — so the **AP bill
match is the FIRST GL posting for the purchase** (**DR Inventory-or-Purchases / CR AP control**); a
**payment** posts **DR AP control / CR Cash/Bank**. The AP sub-ledger total must **reconcile** to the GL AP
control account at all times. **Inventory valuation + COGS are DEFERRED (T2.2)** — v1 books the bill debit
to inventory-or-expense per `gl_configs` **without** a COGS roll-up. Permissions `AP.VIEW` / `AP.BILL.ENTER`
/ `AP.BILL.MATCH` / `AP.PAYMENT.RUN` / `AP.DEBITNOTE` / `AP.OPENING.SET`; per-company scope; `assertCanActIn`
on every read; audit on every mutation; `BILL-####` / `PAYRUN-####` via `code_sequence`. **Deferred:** GRNI
accrual, inventory valuation/COGS (T2.2), Cash & Bank module (T1.4 — v1 posts the payment bank leg directly
to a Cash/Bank GL account), input-VAT recovery + VAT return (T1.5), payment-terms master, payment approval
workflow, multi-currency AP / FX revaluation. Reads Purchases PO/GRN (ADR-0011, **DTO-only**) for matching;
posts to the existing GL (ADR-0013) control accounts; reuses Money (ADR-0005), RBAC, audit, `code_sequence`.
Next: solutions-architect **ADR-0015** (AP data model, **V12**) incl. the sub-ledger⇄GL-control
reconciliation design, the GL-posting mechanism choice, and the **bill debit account choice** (inventory
value vs a purchases / GRNI-clearing account).

### US-AP-01 — Enter a supplier bill and 3-way match it (the first GL posting for the purchase)
**As an** AP clerk **I want** to enter a supplier's bill and match it to our PO and goods receipt **so that**
we owe only what we actually ordered and received, at the agreed price, and the liability lands on the books.
- **AC1** Given `AP.BILL.ENTER`, when I enter a supplier bill (`BILL-####`) with lines (product, qty, unit
  cost), the total, bill/due dates, and the **PO + Goods Receipt(s)** it bills against, then it is recorded
  for matching (FR-AP-01).
- **AC2** Given the bill is matched (`AP.BILL.MATCH`), when each line's **quantity** (bill vs received vs
  ordered) **and price** (bill unit cost vs PO unit cost) agree **within tolerance**, then the bill
  **matches**, becomes a **payable**, and **posts to GL**: **DR Inventory-or-Purchases (`gl_configs`) [+ DR
  VAT input if stated] / CR the AP control** for the bill total (FR-AP-03/04/06, BR-AP-03).
- **AC3** Given a goods receipt had pushed stock in but **did not post to GL**, then this matched bill is
  the **FIRST GL posting for the purchase** — the liability appears on the books now, not at receipt
  (FR-AP-06, BR-AP-02).
- **AC4** Given the bill books a debit, then v1 books it to inventory-or-expense **per `gl_configs` WITHOUT
  a COGS roll-up** — no cost layer, no COGS (inventory valuation + COGS are T2.2) (BR-AP-11).
- **AC5** **(Reconciliation bar)** Given a matched bill posts, then the **supplier's AP balance** rises by
  the bill total AND the **GL AP control** is credited by the same amount; the sub-ledger total equals the
  GL `2100 Accounts Payable` control balance (FR-AP-08, NFR-AP-01).
- **AC6** Given matching, then AP reads the **PO / Goods Receipt** as **DTOs** and imports no Purchases
  entity (NFR-AP-06); the bill entry + match are audited (NFR-AP-03).

### US-AP-02 — A bill over tolerance is held for review
**As an** AP clerk **I want** a bill whose price or quantity is off to be held **so that** we never silently
pay a supplier more than we agreed.
- **AC1** Given a bill whose **price or quantity** is **beyond tolerance** vs the PO/GR, when it is matched,
  then it is **held for review** and **nothing posts to GL** (FR-AP-04, BR-AP-04).
- **AC2** Given a held bill and `AP.BILL.MATCH`, when I **accept the variance**, then the bill posts as a
  payable (US-AP-01 AC2) and the acceptance is **audited** (FR-AP-04, NFR-AP-03).
- **AC3** Given a held bill, when I **reject** it, then no payable is created and nothing posts to GL
  (FR-AP-04).
- **AC4** Given the recommended default tolerance (price within ~2% or a small absolute, quantity = received
  qty), then it is a **configurable setting** confirmed before go-live; the *held-not-auto-posted* behaviour
  is fixed regardless of the value (FR-AP-05, OQ-AP-01).

### US-AP-03 — Pay suppliers via a payment run (batch) and as a single payment
**As a** payments officer **I want** to pay many due bills in one run, or pay a single bill **so that**
suppliers are settled efficiently and the cash side hits the books.
- **AC1** Given `AP.PAYMENT.RUN`, when I run a **payment run** (`PAYRUN-####`) that **batch-selects** due /
  matched bills (by supplier, due date), then they are paid in **one payment** and each payable is settled
  (FR-AP-10).
- **AC2** Given the payment, then GL posts **DR the AP control / CR Cash/Bank** (`gl_configs`) for the
  total, with the per-bill split recorded in the sub-ledger (FR-AP-10, BR-AP-05).
- **AC3** Given a fully-settled payable, when a later run is built, then it is **excluded** — **no payable is
  paid twice**; a partly-paid payable shows its **remaining** balance and over-payment is rejected
  (FR-AP-11, BR-AP-06, NFR-AP-04).
- **AC4** **(Reconciliation bar)** Given a matched bill (US-AP-01) then its payment, when both have posted,
  then the **supplier's AP balance** is reduced correctly AND the **GL AP control nets correctly** (control
  credit from the bill − control debit from the payment = remaining payable); the sub-ledger total equals
  the control balance (FR-AP-08, NFR-AP-01).
- **AC5** Given `AP.PAYMENT.RUN`, when I pay a **single** matched/due bill, then that payable is settled and
  GL posts **DR AP control / CR Cash/Bank** for the paid amount (FR-AP-09); the payment is audited
  (NFR-AP-03).

### US-AP-04 — Raise a debit note / adjustment against an open payable
**As an** AP clerk **I want** to reduce what we owe a supplier when they credit us **so that** the payable
and the books reflect a return or an over-charge correction.
- **AC1** Given `AP.DEBITNOTE` and an open payable, when I raise a **debit note** (with a reason), then the
  payable is **reduced** in the sub-ledger and GL posts **DR the AP control / CR
  Inventory-or-Purchases-or-VAT** for the credited amount (FR-AP-12, BR-AP-07).
- **AC2** Given the debit note posts, then the supplier's balance and the GL AP control drop by the **same
  amount** (reconciled, FR-AP-08); the debit note is **audited** (NFR-AP-03).
- **AC3** Given a posted bill/payment needs correcting, then it is corrected via a **reversal / debit
  note**, never by editing a posted entry (append-only, BR-AP-09).

### US-AP-05 — Enter AP opening balances at go-live
**As an** accountant **I want** to enter suppliers' pre-existing payables **so that** AP starts from the
business's actual creditor position when it goes live.
- **AC1** Given `AP.OPENING.SET`, when I enter each supplier's pre-existing payable (amount, bill date, due
  date) as an **opening payable**, then it is recorded in the sub-ledger (FR-AP-13).
- **AC2** Given opening payables are entered, then the **sum equals the AP control account's opening
  balance** (the GL side is the opening-balance journal, gl.md FR-GL-13) — reconciliation holds from day
  one (FR-AP-13, BR-AP-02).
- **AC3** Given an opening payable is wrong, then it is corrected via a **reversal / debit note**, not by
  editing a posted entry (BR-AP-09).

### US-AP-06 — The goods receipt does NOT create a payable (the accepted bill-driven gap)
**As a** financial controller **I want** to understand that the liability appears only when the bill is
entered **so that** nobody expects a payable the moment goods arrive.
- **AC1** Given a **Goods Receipt** is finalised (stock pushed in via `STOCK.RECEIVED`), when no bill has
  been entered, then **no payable** exists and **nothing posts to GL** for the liability (FR-AP-02,
  BR-AP-01).
- **AC2** Given goods are received but not yet billed, then the amount owed is **not on the books** — the
  accepted bill-driven-AP gap (no GRNI accrual in v1) (BR-AP-01, accounts-payable.md §10.1).
- **AC3** Given the supplier's bill is later entered and matched (US-AP-01), then the liability appears on
  the books at that point — the **first GL posting for the purchase** (FR-AP-06, BR-AP-02).
- **AC4** Given any AP read or balance, then it returns **only my company's** payables (`assertCanActIn`);
  cross-company figures never appear (FR-AP-14, BR-AP-08, NFR-AP-01).

---

## Cash & Bank — the cash book + bank book

Requirements: [docs/requirements/cash-and-bank.md](docs/requirements/cash-and-bank.md). Status: RATIFIED
2026-06-09. Increment 3 (T1.4) — named cash/bank accounts each linked to a GL `1xxx` account; transfers;
direct entries; cheque register; per-account statement & balance; manual bank reconciliation; the additive
AR/AP cash-account-routing touch. Every cash/bank movement posts to GL synchronously and reconciles to both
its linked GL account and the bank statement. Next step: solutions-architect ADR-0016 / V13.

### US-CASH-01 — Open a bank account mapped to a GL account
**As a** treasurer **I want** to create a named cash/bank account linked to a GL account **so that** the
money in each location is tracked and reconciles to the books — replacing the single hard-wired cash account.
- **AC1** Given `CASH.ACCOUNT.MANAGE`, when I create a cash/bank account (name, type CASH | BANK, bank
  details for BANK, currency = base, and a **link to a GL `1xxx` asset account**), then it is saved with a
  `CB-####` code, set ACTIVE, and the create is audited (FR-CASH-01, NFR-CASH-03).
- **AC2** Given the account is created, then it **maps to exactly one GL account** (mandatory link), which
  **replaces the single `gl_configs` `CASH` account** with a real named account (FR-CASH-03, BR-CASH-01).
- **AC3** Given I create it, when I flag it the **company default**, then at most one default exists per
  company and AR/AP route to it when no account is named (FR-CASH-04, BR-CASH-09).
- **AC4** **(Reconciliation bar)** Given a new account, then its **book balance == its linked GL account
  balance** (both zero at open); they move together on every later transaction (FR-CASH-17, BR-CASH-02).
- **AC5** Given the account later has transactions, when I try to **delete** it, then it is **refused** — I
  may only deactivate it (a deactivated account keeps its history, takes no new transactions) (FR-CASH-02,
  BR-CASH-13).

### US-CASH-02 — An AR receipt lands in a chosen cash/bank account
**As an** AR cashier **I want** to choose which cash/bank account a customer's receipt lands in **so that**
the money is recorded in the right location and the right GL account is debited.
- **AC1** Given I record a customer **receipt** and **choose a cash/bank account**, when it posts, then the
  GL **debit is the chosen account's linked GL account** and the **credit is `1200 Accounts Receivable`**;
  the chosen account's **book balance rises** by the receipt amount (FR-CASH-05, §3.2).
- **AC2** Given I record a receipt and **do not choose** an account, then the **company default cash/bank
  account** is used (FR-CASH-05, BR-CASH-09).
- **AC3** Given I name an **inactive** account, or none is named and there is **no company default**, then
  the receipt **fails with a clear message** rather than posting to a wrong/null account (FR-CASH-07,
  BR-CASH-09).
- **AC4** **(Reconciliation bar)** Given the receipt posts, then the chosen account's **book balance == its
  linked GL account balance** (FR-CASH-17, BR-CASH-02); the cash leg posts **synchronously, in one TX**
  (NFR-CASH-04, BR-CASH-03).
- **AC5** Given an AP **payment** chooses a cash/bank account, then symmetrically the GL **credit is the
  chosen account's linked GL account** (debit `2100`), the account's **book balance falls**, default if
  unspecified (FR-CASH-06, BR-CASH-09).

### US-CASH-03 — Transfer money between cash/bank accounts
**As an** accountant **I want** to move money between our own accounts (bank → petty cash, cash deposit →
bank) **so that** the balances reflect where the money actually is.
- **AC1** Given `CASH.TRANSFER`, when I record a transfer (`CBT-####`) with a **source** and a
  **destination** account, an amount, a date, and a reference, then it posts **DR the destination account's
  GL account / CR the source account's GL account**, balanced (FR-CASH-08, BR-CASH-04).
- **AC2** Given the transfer posts, then the **source book balance falls** and the **destination book
  balance rises** by the same amount (FR-CASH-08).
- **AC3** Given source == destination, or the accounts are in **different companies**, when I submit, then
  the transfer is **rejected** (BR-CASH-04).
- **AC4** **(Reconciliation bar)** Given the transfer posts, then **both** accounts' book balances **==**
  their linked GL account balances; the transfer is **audited** (FR-CASH-17, NFR-CASH-03).

### US-CASH-04 — Record a direct bank-charge entry (not tied to AR/AP)
**As an** accountant **I want** to record ad-hoc money movements like bank charges and interest **so that**
sundry receipts/payments hit the right GL account and the cash/bank balance is accurate.
- **AC1** Given `CASH.ENTRY.RECORD`, when I record a **direct entry** (a cash/bank account, direction, amount,
  date, reference, and a **GL counter-account**), then it posts the **balanced double-entry** — the cash/bank
  account's GL account on one side, the counter-account on the other (FR-CASH-09, BR-CASH-05).
- **AC2** Given a **bank charge**, when it posts, then **DR bank-charges expense / CR the bank account's GL
  account**; the bank account's **book balance falls** by the charge (§3.4).
- **AC3** Given a missing/inactive counter-account or linked GL account, then the entry **fails** rather than
  mis-posting (FR-CASH-16, gl.md BR-GL-10).
- **AC4** **(Reconciliation bar)** Given the entry posts, then the account's **book balance == its linked GL
  account balance** (FR-CASH-17, BR-CASH-02); a posted entry is corrected only by a **reversing entry**, never
  edited (BR-CASH-10).

### US-CASH-05 — Reconcile a bank account to the statement (mark cleared + balance check)
**As a** treasurer **I want** to mark transactions cleared and confirm our balance matches the bank's **so
that** I can trust the bank book and catch missing or duplicate transactions.
- **AC1** Given `CASH.RECONCILE`, when I open a reconciliation, then I enter a **statement date** and a
  **statement closing balance** and **mark** the account's transactions as **CLEARED** against the statement
  (FR-CASH-13).
- **AC2** Given I mark transactions cleared, then the system computes the **book balance of cleared
  transactions** (FR-CASH-13).
- **AC3** Given the **book balance == the statement closing balance**, when I complete the reconciliation,
  then it **completes** and is audited (FR-CASH-14, BR-CASH-06, NFR-CASH-03).
- **AC4** Given **book ≠ statement**, when I try to complete, then it is **refused** — the reconciliation
  stays open until I resolve the discrepancy (FR-CASH-14, BR-CASH-06).
- **AC5** Given a transaction is part of a **completed** reconciliation, when I try to un-clear or edit it,
  then it is **refused** — the cleared flag is **immutable**; I correct via a reversing entry / a new
  reconciliation (FR-CASH-15, BR-CASH-07/10).
- **AC6** Given v1, then reconciliation is **manual** — there is **no statement file import** (OQ-CASH-01).

### US-CASH-06 — Issue and clear a cheque
**As a** treasurer **I want** a cheque register that tracks issued, cleared, cancelled, and post-dated
cheques **so that** I know which cheques are outstanding and when they clear.
- **AC1** Given `CHEQUE.MANAGE`, when I register a cheque against a bank-account payment (cheque number,
  drawing bank account, the payment it settles, issue date, value date), then it is saved with status
  **ISSUED** (FR-CASH-10).
- **AC2** Given the **value date is later than the issue date**, then it is a **post-dated cheque**, tracked
  as ISSUED until it clears on/after its value date (FR-CASH-10, §3.5).
- **AC3** Given a cheque number already exists on the **same bank account**, when I register another with
  that number, then it is **rejected** — cheque number is unique per bank account (FR-CASH-10, BR-CASH-12).
- **AC4** Given the bank honours the cheque, when I move it **ISSUED → CLEARED**, then the clearing is
  recorded and audited; a stopped cheque goes **ISSUED → CANCELLED** (FR-CASH-11).
- **AC5** Given a cancelled cheque whose payment must be undone, then the **payment is reversed via a
  reversing entry** (append-only) — the register is not edited in place (FR-CASH-11, BR-CASH-10).
- **AC6** Given cheque **printing**, then it **depends on the cross-cutting PDF capability (X.1)** and is
  deferred to it; the register data is captured so printing is additive (OQ-CASH-02).

### US-CASH-07 — View a cash/bank account statement & balance
**As a** cashier or accountant **I want** a running statement and current balance per account **so that** I
can see every movement and what each location holds.
- **AC1** Given `CASH.VIEW`, when I open an account's **statement**, then I see every (non-void) transaction
  (receipts, payments, transfers, direct entries) in date order with a **running balance** (FR-CASH-12).
- **AC2** Given the statement, then the account's **current balance** is its **book balance** (the running
  sum of its transactions) (FR-CASH-12, glossary).
- **AC3** **(Reconciliation bar)** Given any account, then its **current/book balance == its linked GL
  account balance** (FR-CASH-17, BR-CASH-02).
- **AC4** Given any read, then it returns **only my company's** cash/bank accounts and transactions
  (`assertCanActIn`); cross-company figures never appear (FR-CASH-18, BR-CASH-08, NFR-CASH-01).

---

## VAT Return / Tax — the monthly VAT obligation (output vs input, filed to TRA)

Requirements: [docs/requirements/vat-return.md](docs/requirements/vat-return.md). Status: **RATIFIED
2026-06-09** — the last Tier-1 finance piece (T1.5 / Phase A). A **monthly, accrual-basis** VAT return that
nets **output VAT** (finalised sales) against **input VAT** (supplier bills), takes manual adjustments,
files **DRAFT → FILED** with a **synchronous GL settlement** + the period lock + a **net-credit
carry-forward**, plus **withholding-tax (WHT)** capture + a WHT register. Reads Sales output VAT (ADR-0008)
+ AP input VAT (ADR-0015); posts to GL (ADR-0013) on filing (needs a new `VAT_INPUT` account/`gl_configs`
key — flagged for ADR-0017). TRA EFD/e-filing deferred.

### US-VAT-01 — Prepare a monthly VAT return
**As an** accountant **I want** to open and compute the VAT return for a calendar month **so that** I can
see what the company owes TRA (or carries forward) before filing.
- **AC1** Given `VAT.RETURN.PREPARE` and an active company, when I open the return for a company-month, then
  a `VATR-####` return is created in status **DRAFT** with a **due date = the 20th of the following month**
  (FR-VAT-01).
- **AC2** Given the period, when it computes, then **output VAT** = the sum of `sales_invoices.vat_total_amount`
  (by tax band, from `tax_summary`) for invoices **FINALISED in the period** (FR-VAT-03, BR-VAT-05), and
  **input VAT** = the sum of `supplier_bills.vatAmount` for **matched/approved bills DATED in the period**
  (FR-VAT-04, BR-VAT-04) — **payment-independent** (accrual basis).
- **AC3** Given a **DRAFT** return, when more invoices finalise / more bills land in the period, then
  re-computing **refreshes** output and input; recompute does **not** post to GL (FR-VAT-02).
- **AC4** Given a company-month that already has a return, when I try to open a second, then it is
  **rejected** — one return per company per month (BR-VAT-01).
- **AC5** Given any read, then it returns **only my company's** returns (`assertCanActIn`); cross-company
  figures never appear (FR-VAT-14, BR-VAT-07, NFR-VAT-01).

### US-VAT-02 — Review the output / input breakdown
**As a** tax officer **I want** to see output VAT by band and input VAT against source **so that** I can
trust the return before it is filed.
- **AC1** Given `VAT.VIEW`, when I open a return, then I see **output VAT by band** (STANDARD 18 / ZERO_RATED
  0 / EXEMPT), **input VAT**, **adjustments**, the **opening credit carried forward**, the **net**, and
  whether it is **payable** or a **credit** (FR-VAT-13, FR-VAT-06).
- **AC2** Given a **DRAFT** return, then **only FINALISED** sales contribute output (a DRAFT/voided invoice
  contributes nothing — BR-VAT-05) and **only matched** bills contribute input (a **held / over-tolerance**
  bill is excluded until it matches — BR-VAT-04).
- **AC3** **(Reconciliation bar)** Given the period's output, then it **agrees with the period's `2200 VAT
  Payable` GL movement** from sales auto-posting (BR-VAT-08).
- **AC4** Given a FILED return, then I also see its **filing reference** + **filing date** (FR-VAT-13).

### US-VAT-03 — Add a VAT adjustment to a draft return
**As an** accountant **I want** to add manual adjustment lines to a draft return **so that** bad-debt VAT
relief, prior-period corrections, and credit/debit-note VAT are reflected in the net.
- **AC1** Given `VAT.ADJUST` and a **DRAFT** return, when I add an adjustment (a **reason**, an **amount**, a
  **sign**), then it is recorded, **audited**, and the **net updates** (FR-VAT-05, FR-VAT-06, NFR-VAT-03).
- **AC2** Given a DRAFT return, when I remove an adjustment, then it is removed and the net recomputes; both
  add and remove are allowed **only while DRAFT** (BR-VAT-09).
- **AC3** Given a **FILED** return, when I try to add/remove an adjustment, then it is **refused** — the
  return is locked; the correction goes to the **next period's adjustment** (BR-VAT-09/10).

### US-VAT-04 — File a VAT return → locks + posts to GL
**As a** financial controller **I want** to file the return so it is locked and the period's VAT is settled
on the books **so that** the books reflect what we owe TRA and the period is closed.
- **AC1** Given `VAT.RETURN.FILE` and a **DRAFT** return, when I file it, then I record a **filing reference**
  + a **filing date**, the figures **freeze**, and the return moves **DRAFT → FILED** and is **LOCKED**
  (FR-VAT-08, BR-VAT-02).
- **AC2** Given filing, then a **synchronous GL settlement journal** posts — **DR `2200 VAT Payable`** (clear
  output), **CR the `VAT_INPUT` recoverable account** (clear input), book the **net to a VAT-due liability**
  (net positive) or carry the credit (net negative), **balanced** (FR-VAT-08, BR-VAT-06); the exact accounts
  are ADR-0017.
- **AC3** **(Reconciliation bar)** Given a FILED return, then the **filing settlement entry's net == the
  return's net**, the period's **output == the `2200` movement**, and the **input == the `VAT_INPUT`
  movement** (BR-VAT-08, NFR-VAT-01); the return and the GL post **commit in one transaction** (NFR-VAT-04).
- **AC4** Given an already-**FILED** return, when I try to file again or edit it, then it is **rejected** —
  the VAT period is closed and cannot be filed twice (BR-VAT-11, BR-VAT-02); undoing the GL post is a
  **reversing entry**, never an edit (BR-VAT-10, gl.md BR-GL-02).
- **AC5** Given filing would post into a **closed GL period**, or a required `gl_configs` mapping
  (`VAT_PAYABLE`, the new `VAT_INPUT`, the VAT-due account) is **missing**, then the **file fails** rather
  than mis-posting; finance fixes it and retries (FR-VAT-09, gl.md OQ-GL-01 / BR-GL-10).

### US-VAT-05 — A net credit carries forward to the next period
**As an** accountant **I want** a period's net VAT credit to carry forward **so that** it offsets next
period's liability without a cash-refund claim.
- **AC1** Given a period nets to a **credit** (input + carried credit + reducing adjustments > output), when
  it is filed, then the credit is recorded as a **VAT credit**, **not** a cash refund (FR-VAT-07, BR-VAT-03).
- **AC2** Given the **next** period's return is opened, then the prior period's credit appears as its
  **opening credit carried forward** and **offsets** that period's net (FR-VAT-06/07, BR-VAT-03).
- **AC3** Given v1, then a cash **refund claim** to TRA is **not** offered (deferred); the carry-forward is
  the only v1 path (§10.2).

### US-VAT-06 — Record withholding tax on a payment + the WHT register
**As a** tax accountant **I want** to capture withholding tax on supplier payments (and customer receipts)
and see a WHT register **so that** I can remit the withheld tax to TRA and issue/keep certificates.
- **AC1** Given `WHT.MANAGE` on a supplier **AP payment**, when I apply a **WHT rate/type** (incl.
  withholding VAT), then the supplier is **paid less** by the withheld amount, the system **books a WHT
  liability** and **reduces the cash leg** by the withheld amount, and a **WHT certificate** (`WHT-####`) is
  produced (FR-VAT-10, BR-VAT-12).
- **AC2** Given a customer **withheld** on an **AR receipt**, when I capture it (`WHT.MANAGE`), then the
  receipt is **less** than the invoice by the withheld amount, the system **books a WHT receivable/asset**,
  and the customer's **WHT certificate** is recorded (FR-VAT-11, BR-VAT-12).
- **AC3** **(Balance bar)** Given a WHT capture, then the **cash reduction == the WHT liability/receivable
  booked** (the legs net — NFR-VAT-02, BR-VAT-12).
- **AC4** Given `WHT.VIEW`, when I open the **WHT register** for a period, then I see WHT **withheld** (to
  remit to TRA) and **received** (a receivable) — the basis for remittance (FR-VAT-12).
- **AC5** Given the WHT register, then it is a **sibling** to the VAT return and is **NOT** part of the
  output−input VAT net (BR-VAT-12).
- **AC6** Given v1 WHT, then it is **lean** — a configurable rate/type + capture + track + register +
  certificate; the **full WHT-by-type matrix + WHT e-filing** are deferred (OQ-VAT-02, §10.3).

---

## Financial Reporting — the three primary statements + the GL account-ledger drill-down

Requirements: [docs/requirements/reporting.md](docs/requirements/reporting.md). Status: **RATIFIED
2026-06-10** — the first Reporting slice (T2.3 / Phase A). The moment ERPCLEAN2 is demonstrably an ERP, not
just balanced books. **Read-only over GL**: turns the balanced ledger into a **Profit & Loss**, a **Balance
Sheet**, a **Cash-Flow Statement (indirect)**, and the **GL account-ledger drill-down** beneath every line,
with **comparative** columns and **PDF / Excel-CSV export**. The statement→account mapping is **auto-derived**
from `account_type` + account-code range (no template table). Reads GL (ADR-0013: `TrialBalanceQuery` /
`journal_lines` / `chart_of_accounts.account_type` + `normal_balance` / `fiscal_periods`); base currency
only (ADR-0005). **Posts nothing, owns no new business table** — only a V15 perm-seed for `REPORT.*`. The
correctness bars are testable: the BS balances; the cash-flow net == the Cash+Bank GL movement; the P&L net
== the period's INCOME−EXPENSE GL movement; every figure drills to journal lines.

### US-REP-01 — View an Income Statement / P&L with a comparative
**As an** owner or accountant **I want** a Profit & Loss for a period with a prior-period comparative **so
that** I can see whether the business made money and how it compares with last period.
- **AC1** Given `REPORT.VIEW` / `REPORT.PL.VIEW` and an active company, when I run a P&L for a **date range**
  (or a fiscal-period quick-select), then I see **revenue** less **cost of sales** = **gross profit**, less
  **operating expenses** = **net profit**, grouped by the auto-derive rule (FR-REP-01, BR-REP-07).
- **AC2** Given the statement, then it shows a **comparative** prior period alongside the selected period
  (FR-REP-06, BR-REP-01); the comparative is computed exactly as the primary over the prior window.
- **AC3** **(Reconciliation bar)** Given the P&L, then its **net profit == the period's INCOME − EXPENSE
  movement on `journal_lines`** for that range (BR-REP-03); the statement is the ledger, grouped — it cannot
  show a net the ledger does not carry.
- **AC4** Given the selected window has **no postings**, then the P&L renders with **zero / empty sections**
  (a valid empty result), not an error (FR-REP-01).
- **AC5** Given any read, then it returns **only my company's** figures (`assertCanActIn`); cross-company
  figures never appear (FR-REP-08, BR-REP-10, NFR-REP-01).

### US-REP-02 — View a Balance Sheet that balances
**As a** financial controller **I want** a Balance Sheet as-at a date that balances, with a comparative **so
that** I can trust the financial position and share it externally.
- **AC1** Given `REPORT.VIEW` / `REPORT.BS.VIEW`, when I run a Balance Sheet **as-at a date**, then I see
  **assets** (current vs non-current) = **liabilities** (current vs non-current) + **equity**, grouped by the
  auto-derive rule (FR-REP-02, BR-REP-07).
- **AC2** Given the equity section, then it includes **retained earnings + the current-year net income to
  date** folded in (BR-REP-05) — a presentation derivation, not a posted closing entry.
- **AC3** **(Reconciliation bar)** Given the Balance Sheet, then **ASSET == LIABILITY + EQUITY** for the
  as-at date (BR-REP-02); it **balances**, because GL is double-entry and the year's net income is folded in.
- **AC4** Given the statement, then it shows a **comparative** as-at a prior date (FR-REP-06, BR-REP-01).
- **AC5** Given (in a correct double-entry system this should not happen) the BS does **not** balance, then
  the imbalance is **surfaced as a data-integrity alarm** for finance to investigate — Reporting is read-only
  and never silently adjusts a figure (BR-REP-02, BR-REP-08).

### US-REP-03 — View a Cash-Flow Statement that ties to cash
**As an** accountant **I want** an indirect Cash-Flow Statement whose net change in cash ties to the cash +
bank movement **so that** I can see where the cash went and trust the bottom line.
- **AC1** Given `REPORT.VIEW` / `REPORT.CASHFLOW.VIEW`, when I run a Cash-Flow Statement (indirect) for a
  **date range**, then I see **operating** (net income ± working-capital changes — ΔAR, ΔAP, ΔInventory,
  ΔVAT/WHT), **investing** (± non-current-asset changes), and **financing** (± equity / borrowing changes),
  classified by the auto-derive rule (FR-REP-03, BR-REP-07).
- **AC2** **(Reconciliation bar)** Given the statement, then the **net change in cash == the movement on the
  Cash + Bank GL accounts (1000 + 1100)** between the two dates (BR-REP-04); the three sections sum to that
  net change.
- **AC3** Given v1 has **no Fixed Assets / Loans**, then the **investing / financing sections are sparse**
  (few or no postings) — this is expected, not a defect, and the **tie-out bar (AC2) holds regardless**
  (§10.2, BR-REP-04).
- **AC4** Given the statement, then it shows a **comparative** prior period (FR-REP-06, BR-REP-01).
- **AC5** Given the net change **does not** tie to the Cash + Bank movement, then it is **surfaced** as a
  defect for investigation — Reporting never "balances" it by writing (BR-REP-04, BR-REP-08).

### US-REP-04 — Drill from a statement line into the GL account ledger
**As an** auditor or accountant **I want** to drill from any statement line into the underlying GL account's
ledger **so that** I can trace every figure down to the journal lines that produced it.
- **AC1** Given `REPORT.VIEW` / `REPORT.LEDGER.VIEW` and a statement line, when I drill into it, then I see
  the account's **opening balance**, each **journal line** in the period (posting date, source, reference,
  debit, credit) in posting-date order, the **running balance**, and the **closing balance** (FR-REP-04).
- **AC2** **(Trace bar)** Given the account ledger, then the **closing balance == the figure the statement
  line showed** for that account (BR-REP-06); every statement figure traces to the journal lines.
- **AC3** Given the ledger, then each line names its **source** (a sales auto-post, an AR receipt, an AP
  payment, a Cash & Bank transfer, a manual journal, the VAT settlement) and its **reference** (the `JB-####`
  batch / source document) (FR-REP-04, §3.5).
- **AC4** Given the ledger is for a GL account, then it is the **GL account ledger** (over `journal_lines`),
  **not** the AR/AP sub-ledger (per-party detail) (FR-REP-04, glossary).
- **AC5** Given a very **large** account ledger, then it **paginates / streams** within the performance
  envelope — it does not time out or load unbounded (NFR-REP-02).

### US-REP-05 — Export a statement to PDF / Excel
**As an** owner or accountant **I want** to export any statement and the account ledger to PDF and Excel/CSV
**so that** I can print it, file it, or work with the figures in a spreadsheet.
- **AC1** Given `REPORT.EXPORT`, when I export a statement (P&L, Balance Sheet, Cash-Flow) or the account
  ledger to **PDF**, then I get a **print-faithful** rendering — the same figures, the same sections, the
  comparative column, and a header carrying the **company name + period / as-at date + base currency**
  (FR-REP-05, NFR-REP-04).
- **AC2** Given `REPORT.EXPORT`, when I export to **Excel / CSV**, then I get the same figures in a
  spreadsheet-usable form (rows/columns, the comparative) (FR-REP-05).
- **AC3** **(Fidelity bar)** Given an export, then it **matches the on-screen statement exactly** (figures,
  sections, comparative) — a divergence is a defect (NFR-REP-04).
- **AC4** Given the export library / generation approach, then it is the **architect's ADR call** (OQ-REP-05);
  the requirement is a print-faithful PDF + a spreadsheet export.

### US-REP-06 — Select the reporting window and comparative
**As an** accountant **I want** to choose the reporting window and the comparative **so that** I can report
any period or as-at date and compare against the right prior window.
- **AC1** Given a P&L / Cash-Flow, when I select the window, then I may choose an **arbitrary date range**;
  given a Balance Sheet, I select an **as-at date** (FR-REP-07).
- **AC2** Given the selector, then I have **fiscal-period / fiscal-year quick-selects** (this month, this
  quarter, this fiscal year, a named period) resolved through GL `fiscal_periods` / `FiscalPeriodResolver`
  (FR-REP-07, gl.md FR-GL-14).
- **AC3** Given the **comparative**, then it defaults to the **immediately prior period of the same length**
  (P&L / Cash-Flow) / the **prior year-end or the period start** (Balance Sheet), and I may **override** it to
  a chosen prior window — e.g. prior-year same-period (FR-REP-06, OQ-REP-03).
- **AC4** Given any statement, then all figures are in the **company base currency** (TZS in practice); there
  is **no FX / presentation-currency** translation in v1 (FR-REP-07, BR-REP-09).

### US-REP-07 — Reporting is read-only and permission-gated
**As a** business owner **I want** Reporting to read the books without ever writing, and to gate who sees
which statement **so that** sensitive financial statements are protected and the books are never altered by a
report.
- **AC1** Given Reporting, then it **posts nothing, creates no business table, allocates no number** — it
  reads `journal_lines` / `chart_of_accounts` / `fiscal_periods` only (BR-REP-08, NFR-REP-03).
- **AC2** Given the permission split, then a user with `REPORT.LEDGER.VIEW` but **not** `REPORT.PL.VIEW` may
  **drill a ledger** but **not** open the company P&L (FR-REP-08, OQ-REP-04).
- **AC3** Given a user **without** the relevant `REPORT.*` permission, when they request a statement, then it
  is **refused** by RBAC (FR-REP-08).
- **AC4** Given the module ships, then the `REPORT.*` permissions are **seeded via a small V15 migration**
  (no new business tables) and granted to `ORG_ADMIN` (FR-REP-08; flagged for ADR-0018).
- **AC5** Given Reporting performs **no mutation**, then there is **no post/close/adjust to audit**; read
  access to sensitive statements is logged per the platform read-access policy (NFR-REP-05).
