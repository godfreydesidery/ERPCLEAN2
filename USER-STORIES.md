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
