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
