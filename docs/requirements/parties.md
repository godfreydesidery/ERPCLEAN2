# Requirements — Parties (Customers, Suppliers, Sales Agents)

> Status: **RATIFIED (2026-06-06)** — the owner has ruled on all open party-model decisions
> (party model, scope, branch association, agent kinds, identifiers, typing, v1 catalogue).
> Author: system-analyst · Domain: `parties` (master data). This is the business-level spec.
> Schema, API shapes, table layout, and the realisation of associations are the
> solutions-architect's job **next** — see [DATA-MODEL.md] (TBD). Do not infer a schema from this
> document; it describes business entities, rules, and relationships, not tables.

## 1. Business context & why now

The operational modules — **Sales**, **Purchases**, and **Stock** — all transact *with* or *through*
outside actors: we sell to **customers**, we buy from **suppliers**, and some sales are introduced
or closed by **sales agents** who earn commission. Before any of those modules can be modelled, the
business must be able to *name and hold* these actors as master data: who they are, how to identify
them for Tanzanian tax and contact purposes, which company they belong to, and which of our branches
may transact with them. This is the **party master**: the people and organisations our books face
outward to.

Parties sit in the dependency chain **after IAM** (which gives us organisation → company → branch and
the scoping spine) and **before** Sales/Purchases/Stock (which consume parties on documents). A sales
order cannot name a customer that does not exist; a purchase cannot name a supplier that does not
exist. So the party master is specified now, ahead of the transactions that reference it.

### Vocabulary distinction (read this first)

Three words look similar and are **not** interchangeable. The team must keep them apart:

- **App user** — a *login identity* inside our ERP (IAM `user`). Someone who signs in to operate the
  system. Defined in [iam.md](iam.md).
- **Employee** — an HR record of a member of our staff. **Not yet modelled** (HR is deferred per
  iam.md §9). Where this document needs "a member of our staff," it means *an app user* until HR
  exists.
- **Party** — an *external* (or quasi-external) actor our business transacts with: a customer, a
  supplier, an agent. A party is **not** a login and is **not** an HR record. A party never logs in
  *as a party* (an internal agent may separately be an app user — see §5 FR-PARTY-13 — but that is
  the user logging in, not the party).

> One nuance the owner ruled on: an **internal** sales agent *is* a member of our staff (an app user)
> who also exists as an agent party record for commission purposes. The agent record **references**
> the IAM user; it does not replace it. See Decision D4 and FR-PARTY-13.

## 2. Scope

### In scope (v1 — "the parties we transact with now")

The v1 party catalogue (full definitions in §3):

- **Customer** — who we sell to. Two sub-kinds: **cash / walk-in** and **credit / account**.
- **Supplier** — who we buy from. Two sub-kinds: **goods** and **service**.
- **Sales Agent** — who introduces/closes sales for commission. Two kinds: **internal** (a member of
  staff / app user) and **external** (an outside broker).
- **Other / Misc party** — a generic, lightly-typed safety-valve for a counterparty that must be
  recorded now but does not yet fit customer / supplier / agent.

Cross-cutting for all of the above:

- **Per-company scope** — every party belongs to one company (carries the company association),
  consistent with how IAM scopes company-bound master data.
- **Multi-branch association** — a party is associated with **many** branches of its company; a
  branch sees and uses only the parties associated with it.
- **Individual-vs-business typing** — each party is either an individual (person) or a
  business / organisation; the type drives which identifiers are mandatory.
- **Tanzanian identifiers** — TIN, VRN, mobile-money number, BRELA number, plus standard contact
  (phone, email, physical/postal address, region/district).
- Standard master-data lifecycle: create, read, update, **archive** (soft-delete), restore.

### Out of scope for v1 — Deferred parties (captured, not built)

The following counterparty kinds are **recognised** but **not** modelled in v1. They are listed in
§3.2 so the vocabulary is captured and nothing is silently lost; each becomes its own requirement
when prioritised:

- broker / distributor (as a *formalised* party type, beyond "external agent")
- manufacturer
- clearing & forwarding / import agent
- carrier / transporter
- 3PL / warehouse operator
- consignor / consignee
- bank (as a party)
- mobile-money provider (as a party, distinct from a party's mobile-money *number*)
- TRA (Tanzania Revenue Authority) as a party
- employee-as-payee (awaits HR)
- insurer
- landlord
- government body
- inter-branch "branch-as-party" (one of our branches treated as a counterparty)

### Explicitly NOT parties

- **Debtor** and **Creditor** are **not** party types. They are **finance lenses** over a balance:
  a *debtor* is a customer who currently owes us money; a *creditor* is a supplier we currently owe.
  The same customer is a debtor only while a receivable balance stands. These are views/reports that
  the Finance module derives from customer/supplier balances — **never** a separate master record.
  See glossary "Debtor-as-lens" / "Creditor-as-lens".

## 3. The party catalogue

### 3.1 v1 parties (built now)

- **Party** — the umbrella term for any external counterparty we hold as master data. In v1 there is
  **no single unified party record** (see Decision D1); "party" is a *category of master data*
  comprising the separate Customer, Supplier, and Sales Agent records, plus the generic Other party.
- **Customer** — a person or organisation we sell goods/services to. Self-contained record with its
  own identity, contact, and tax fields. Sub-kinds:
  - **Cash / walk-in customer** — pays at point of sale; no credit terms; may be lightly identified
    (an individual buying over the counter). The default "walk-in" customer may be reused for
    anonymous counter sales.
  - **Credit / account customer** — buys on account with a balance and (later) credit terms; must be
    more fully identified (typically a business with TIN).
- **Supplier** — a person or organisation we buy from. Self-contained record with its own identity,
  contact, and tax fields. Sub-kinds:
  - **Goods supplier** — supplies stockable goods (feeds Purchases → Stock).
  - **Service supplier** — supplies services (no stock movement; e.g. transport, utilities, repairs).
- **Sales Agent** — a person or organisation that introduces or closes sales and earns commission.
  Self-contained record. Two **kinds** (Decision D4):
  - **Internal agent** — a member of our staff (an app user); the agent record **references** an IAM
    user. Commission accrues to staff.
  - **External agent / broker** — an outside party; a standalone agent record with its own identity
    and tax/contact fields; commission is payable out to them.
- **Other / Misc party** — a generic counterparty record for something that must be recorded now but
  is not a customer, supplier, or agent. Lightly typed; same identity/contact/tax fields available;
  exists so operators are never blocked, and so the deferred party types above can be tracked
  informally until formalised.

### 3.2 Deferred parties (recognised, NOT in v1)

See §2 "Deferred parties." These are named here only so the domain vocabulary is captured. They are
**out of scope for v1**; do not build records or behaviour for them. When a deferred type is
prioritised it gets its own requirements round, at which point we also decide whether it is a new
typed party or a refinement of an existing one (e.g. a distributor may turn out to be a customer
sub-kind, an import agent a supplier sub-kind).

## 4. Actors / personas

- **Master-data administrator** — creates and maintains customer/supplier/agent records, assigns
  parties to branches, sets identifiers, archives obsolete parties. Acts within the company/branch
  scope their IAM roles permit.
- **Branch operator (cashier / sales clerk / purchasing clerk)** — selects existing parties on
  transactions within their active branch; may (with permission) quick-create a party. Sees only
  parties associated with their active branch.
- **Finance / accounts user** — reads parties through the debtor/creditor *lens* (balances), not as a
  separate master. (Defined fully when Finance is specified; named here for completeness.)
- **Sales agent (subject, not operator)** — an internal agent is also an app user who may operate the
  system per their own IAM roles; an external agent is a subject of records only and does not log in.

## 5. Functional requirements

> IDs are `FR-PARTY-NN`. Each is a crisp, testable statement. "Party" used alone below means *any* of
> customer / supplier / agent / other, unless a specific kind is named.

### Core records & lifecycle

- **FR-PARTY-01** The system maintains **Customer** records as a self-contained master: create, view,
  list/search, update, archive (soft-delete), and restore. A customer carries its own identity,
  contact, and tax fields independently of any supplier or agent record.
- **FR-PARTY-02** The system maintains **Supplier** records as a self-contained master with the same
  lifecycle (create, view, list/search, update, archive, restore) and its own identity/contact/tax
  fields, independent of any customer or agent record.
- **FR-PARTY-03** The system maintains **Sales Agent** records as a self-contained master with the
  same lifecycle, its own fields, and an **agent kind** of `internal` or `external` (FR-PARTY-13).
- **FR-PARTY-04** The system maintains a generic **Other / Misc party** record with the same
  lifecycle and the same identity/contact/tax fields available, for counterparties not yet typed as
  customer, supplier, or agent.
- **FR-PARTY-05** Each party record is **soft-deletable**: archiving sets the record inactive without
  destroying history; archived parties are excluded from selection on new transactions (BR-PARTY-09)
  but remain on historical documents and remain restorable.

### Sub-kinds

- **FR-PARTY-06** A **Customer** is one of two sub-kinds: **cash / walk-in** or **credit / account**.
  The sub-kind is recorded on the customer and is visible when selecting the customer on a sale.
- **FR-PARTY-07** A **Supplier** is one of two sub-kinds: **goods** or **service**. The sub-kind is
  recorded on the supplier and is visible when selecting the supplier on a purchase.

### Per-company scope

- **FR-PARTY-08** Every party (customer, supplier, agent, other) **belongs to exactly one company**
  and carries that company association. A party is created within a company and is never company-less.
- **FR-PARTY-09** Party lists, searches, and selection are **scoped by company**: a user working in a
  company sees only that company's parties. (A given legal entity transacting with two of our
  companies is held as **two** separate party records — see Decision D2 and the accepted-risk note.)

### Multi-branch association

- **FR-PARTY-10** A party is **associated with one or more branches of its company**. This is a
  many-to-many *business association* between a party and our branches (a party may be associated with
  several branches; a branch has many associated parties). Describe this as a relationship, not a
  table.
- **FR-PARTY-11** An administrator can **browse a party's branch associations and add or remove
  branches** (within the party's company), so that the party becomes usable at, or hidden from, a
  given branch.
- **FR-PARTY-12** Party selection on transactions is **filtered by the active branch**: a branch
  operator selecting a customer/supplier/agent sees **only** the parties associated with their active
  branch, not other branches' parties (and not other companies' parties — FR-PARTY-09).

### Sales agent: internal vs external

- **FR-PARTY-13** A Sales Agent has an **agent kind**:
  - **Internal** — the agent record **references an active IAM user** (a member of staff). The agent
    is selected on sales the way an external agent is, but commission accrues to that staff identity.
  - **External** — the agent is a **standalone party** (outside broker) with its own identity,
    contact, and tax fields and no IAM reference.
  An agent's kind is set at creation and the record holds the appropriate linkage for that kind
  (IAM user reference for internal; standalone identity for external).

### Tanzanian identifiers & contact

- **FR-PARTY-14** Each party record can capture the following **Tanzanian identifiers** (v1):
  - **TIN** — Taxpayer Identification Number.
  - **VRN** — VAT Registration Number (only for VAT-registered parties).
  - **Mobile-money number** — M-Pesa / Tigo Pesa / Airtel Money number used for payments.
  - **BRELA number** — Business Registrations and Licensing Agency registration number (registered
    businesses).
- **FR-PARTY-15** Each party record can capture **standard contact** details: phone, email,
  physical address, postal address, **region** and **district**.
- **FR-PARTY-16** The system enforces which identifiers are **mandatory vs optional** based on the
  party's individual-vs-business type and (for customers) sub-kind — see §6 BR-PARTY-04..07. Mandatory
  fields are validated on save; optional fields may be left blank.

### Individual vs business typing

- **FR-PARTY-17** Each party is typed as either an **individual** (a natural person) or a
  **business / organisation**. The type is recorded on the party and drives mandatory-identifier
  rules (BR-PARTY-04..07).
- **FR-PARTY-18** A **business** party can additionally capture a registered/legal name distinct from
  a trading name; an **individual** party captures a person name. (Field set differs by type;
  exact fields are the architect's detail — this FR only fixes that the two types differ.)

### Identification & search

- **FR-PARTY-19** Each party has a human-usable **code/identifier unique within its company** for
  selection and reference on documents (BR-PARTY-08). The system supports search by code, name, TIN,
  and phone when selecting a party.

### Permissions (gating)

- **FR-PARTY-20** All party operations are gated by IAM permissions (e.g. a `CUSTOMER.MANAGE`-style
  permission to create/edit, a read permission to view/select). Exact permission codes are seeded with
  the module; this FR only fixes that party operations are permission-gated per IAM (FR-IAM-11).

## 6. Business rules (invariants)

- **BR-PARTY-01** A party's **associated branches must all belong to the party's company.** A party
  cannot be associated with a branch of another company.
- **BR-PARTY-02** A party **belongs to exactly one company** and that company never changes by edit
  (re-homing a party to another company is not an update; it is a new record in that company).
- **BR-PARTY-03** **Separate records, no automatic linkage.** A Customer, a Supplier, and an Agent are
  independent records even when they represent the same real-world entity; the system does not
  auto-merge or auto-link them (see accepted-risk note §9).
- **BR-PARTY-04** A **business** party (individual-vs-business = business) **must have a TIN**, and
  **should have a BRELA number**; TIN is mandatory, BRELA strongly recommended (configurable as
  mandatory later). (Owner ruling D6.)
- **BR-PARTY-05** An **individual** party **may have neither TIN nor BRELA**; neither is mandatory for
  an individual. A walk-in cash customer in particular may carry only a name and optionally a phone.
- **BR-PARTY-06** **VRN is only meaningful for a VAT-registered party.** A VRN may be captured only
  when the party is marked VAT-registered; a VRN without VAT-registration is invalid.
- **BR-PARTY-07** A **credit / account customer** must be sufficiently identified (at minimum a name
  and, if a business, a TIN per BR-PARTY-04) before it can be used on a credit sale. A **cash /
  walk-in customer** has no such minimum beyond a name.
- **BR-PARTY-08** A party's **code/identifier is unique within its company** (per party master).
  Uniqueness is per company, not org-wide (two companies may each have a customer coded `C001`).
- **BR-PARTY-09** An **archived (inactive) party cannot be selected on a new transaction.** It remains
  on historical documents and can be restored. Selection lists exclude archived parties by default.
- **BR-PARTY-10** An **internal sales agent must reference an active IAM user.** If the referenced
  user is disabled, the internal agent cannot be selected on new sales (commission would have no live
  payee); the agent record is not deleted.
- **BR-PARTY-11** An **external sales agent must not reference an IAM user**; it is a standalone party.
- **BR-PARTY-12** A party must be **associated with at least one branch of its company to be usable**
  on a transaction. A party associated with no branch exists but is selectable nowhere (it is, in
  effect, parked) until associated with a branch.
- **BR-PARTY-13** **VRN, where present, should be unique within its company** (a VAT registration
  identifies one entity); duplicate VRNs within a company are flagged. (TIN duplication is *warned*
  but not blocked, because the separate-records model intentionally permits the same entity as both a
  customer and a supplier — see §9.)

## 7. Non-functional

- Party master inherits the multi-tenant isolation rule: every party record is scoped by
  `company_id` and its branch associations by branch, enforced at the repository base
  (PROJECT-CONVENTIONS.md §3.2). Cross-company / cross-branch party leakage is a release blocker.
- Party master screens meet WCAG 2.1 AA (axe gate), consistent with IAM admin screens.
- Search over parties (by code, name, TIN, phone) must remain responsive as the master grows
  (indexing detail is the architect's; the requirement is "fast party lookup at point of sale").

## 8. Assumptions

- "Member of staff" = an **app user** until an HR/Employee record exists (HR deferred, iam.md §9).
  An internal agent therefore references an IAM user, not an HR employee, in v1.
- Currency, VAT rate, and tax treatment are set in the locale/tax discovery round; this document only
  fixes *which identifiers are captured*, not tax *calculation*.
- Soft-delete uses the standard master `status` lifecycle (`ACTIVE` / `INACTIVE` / `ARCHIVED`) per
  PROJECT-CONVENTIONS.md §3.2; "archive" in this doc maps to that lifecycle.

## 9. Accepted risk — separate-records duplication (Decision D1)

The owner has **knowingly accepted** that parties are modelled as **separate, self-contained
records** (independent Customer, Supplier, and Agent), not a unified party-with-roles. The accepted
tradeoff: the **same legal entity may exist as both a customer and a supplier** (and possibly an
agent) as **separate records**, so its tax IDs (TIN/VRN), contacts, and addresses **may diverge**
across those records and there is no single "golden" party view. This is acceptable for v1 in
exchange for simpler, decoupled modules; should a unified-360-view need arise later (e.g. net-off a
customer balance against a supplier balance for the same entity), it becomes a new requirement. No
mitigation is built in v1 beyond the soft duplicate-TIN warning (BR-PARTY-13). Noted once; not
re-litigated.

## 10. Out of scope for v1 (deferred)

Deferred party types (§2 / §3.2); employee/HR linkage for internal agents (uses app user instead);
credit-limit and credit-terms enforcement (Finance/Sales); commission tiers/calculation (Sales); a
unified party-360 view; party de-duplication/merge tooling. Each tracked for a later round.
