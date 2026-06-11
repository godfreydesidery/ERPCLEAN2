# Requirements — CRM (leads → opportunities/pipeline → activities/tasks → convert to quote/SO)

> Status: **PROPOSED (owner ratification pending).** This is the business-level spec for the **CRM module**
> (ERPCLEAN2 area 12, PATH-TO-FULL-ERP §3.9 / Phase D). It is a **thin, pre-sales pipeline layer** that sits
> *in front of* the shipped Order-to-Cash spine: capture a **lead**, qualify it, work it as an **opportunity**
> through **pipeline stages** (with value + probability), log **activities / tasks** against it, and — on a
> win — **convert** the opportunity to a **quotation** (or directly a **sales order**) by calling the shipped
> O2C services (ADR-0021). CRM is **mostly read-on-Parties and feeds Sales**: it **reads** `customers`
> (Parties V2) for the account/contact link and **writes** almost nothing cross-module beyond the
> convert-to-O2C call.
>
> Author: system-analyst (house style) · Domain: new module `crm` (`com.erp.modules.crm`). Business-level
> spec only. **No schema, no API shapes, no tables/columns, no code** — those are the solutions-architect's, in
> **ADR-0031** (next step). Do not infer a data model from this document.
>
> **Depends on:** **Parties** (ADR-0006 / V2 — `customers` master with `customer_kind` CASH_WALK_IN /
> CREDIT_ACCOUNT, `agents`; CRM **reads** these by uid/id, makes **no Party schema change**, adds **no contact
> table to Parties**); **Sales / Order-to-Cash** (ADR-0008 / V5 + ADR-0021 / V18-V19 — the shipped
> `QuotationService.create` / `SalesOrderService.create`, `QUOTE-####` / `SO-####` numbering, the
> `InvoiceTotalsCalculator` discount/VAT math; CRM **converts** an opportunity to a quote/SO by calling these
> services, it does **not** reimplement quoting or ordering); **IAM** (ADR-0002/0006 — RBAC `@perm.has` /
> `@perm.scoped`, `RequestContext`, `ScopeGuard.assertCanActIn`, audit, `code_sequence` numbering); **Outbox**
> (ADR-0009 — `domain_events` + `IdempotencyGuard` for the one cross-module effect: opportunity-won →
> convert); **Money** (ADR-0005 — base currency for the opportunity value); **Products** (ADR-0007 — sellable
> products for the opportunity line products carried into the quote). All shipped. **Latest migration is V19;
> CRM is V51-V55 (coordinator-assigned additive range).**

## 1. Business context & why now

ERPCLEAN2 can take an **order** and run it to cash (quote → SO → reserve → deliver → invoice → return,
ADR-0021), keep books, and value inventory. What it **cannot** do is manage the **work that happens before the
order exists** — the pre-sales pipeline. Today a salesperson's prospecting, the deals they are chasing, the
calls and meetings they log, and the forecast of what will close this quarter all live in spreadsheets or a
separate CRM, disconnected from the ERP that will actually fulfil and bill the won deals.

CRM closes that front-of-funnel gap. It is the system of record for:

- a **lead** — an unqualified prospect (a name, a company, a contact, a source) that may or may not become a
  real sales opportunity;
- an **opportunity** — a qualified, named deal with an **estimated value**, a **pipeline stage**, a **win
  probability**, and an **expected close date** — the unit the sales pipeline and forecast are built from;
- **activities / tasks** — the calls, emails, meetings, and notes logged against a lead or opportunity (the
  interaction history), and the follow-up **tasks** with due dates and owners;
- the **conversion** — on a win, the opportunity becomes a **quotation** (or directly a **sales order**),
  handing the deal off to the shipped Order-to-Cash spine; the quote/SO carries back the link to the
  opportunity it came from.

The value: a single funnel from prospect → won deal → fulfilled order, owned by the same agents who already
sell in the system, scoped per company/branch, audited, and feeding the same pipeline/forecast reports the
owner reads. CRM is **deliberately thin in v1** — pipeline + activities + convert — because the heavy
automation (campaign management, email integration, case/ticket service, marketing attribution) is deferred
(§2) and the deep value is the **clean seam into O2C**: an opportunity that wins becomes a real quote without
re-keying.

### Where CRM sits relative to Parties and Sales (read this before anything else)

CRM is **upstream of the customer record, then it joins it**. Two states matter:

- **A lead is NOT yet a customer.** A lead is a prospect the business is still qualifying — it may have just a
  name, a company, and a phone. CRM stores the lead's own contact details **in CRM** (it does **not** create a
  Parties `customers` row for an unqualified prospect — that would pollute the customer master with tyre-
  kickers). On **qualification**, the lead may be **linked to an existing customer** (if the prospect is
  already a customer) or **promoted to a new customer** (created in Parties via the shipped `CustomerService` —
  CRM calls it, does not write the customers table directly).
- **An opportunity is ALWAYS against a customer.** A qualified opportunity references a **Parties customer**
  (`customer_id` / `customerUid`) — because winning it means quoting/ordering, and a quote/SO needs a customer.
  An opportunity that came from a lead carries the customer the lead resolved to.

> **Word discipline (carried into the glossary):** a **lead** (an unqualified prospect, may not be a customer)
> is **not** an **opportunity** (a qualified, valued deal against a customer) and **not** a **customer** (a
> Parties master record). A **pipeline stage** (where an opportunity sits in the sales process) is **not** a
> **status** (OPEN / WON / LOST — the opportunity's lifecycle). An **activity** (a logged interaction:
> call/email/meeting/note) is **not** a **task** (a future to-do with a due date and an owner). **Converting**
> an opportunity (creating a quote/SO from it) is **not** the same as the opportunity's **win** — winning marks
> the deal WON; converting produces the O2C document (a win may convert to a quote, then later to an SO; or
> convert straight to an SO). CRM **reads** Parties customers and **calls** Sales O2C services — it does **not**
> own customers, quotes, orders, or any GL/AR/stock effect.

### What CRM does NOT touch (the financial/operational spine is unchanged)

CRM posts **no GL**, moves **no stock**, creates **no AR open item**, and reserves **nothing**. It is a
pre-sales CRUD + pipeline + convert layer. The financial and inventory consequences begin **only** when the
converted **quotation / sales order** runs through the shipped O2C spine (ADR-0021) — which CRM **triggers**
but does not own. There are **no new `gl_config` keys, no new CoA accounts, no new GL postings** introduced by
CRM (it is the only greenfield module in the backlog that touches no money).

## 2. Scope

> Every line below is **proposed v1**. This is a **thin pre-sales pipeline**: lead capture/qualification,
> opportunity/pipeline management with value+probability+forecast, activities/tasks, and **convert to
> quote/SO**. It **reads** Parties and **feeds** Sales O2C; it does **not** rebuild Parties, Sales, or any
> financial module. Everything not chosen has moved to the **Deferred** list.

### In scope (v1 — "capture a prospect, qualify it, work the deal through the pipeline, log the touches, and turn a win into a quote/order")

- **Lead capture & qualification.** A **lead** (`LEAD-####`) — prospect contact details (name, company,
  contact person, phone, email), a **lead source** (e.g. WEBSITE, REFERRAL, WALK_IN, CAMPAIGN, COLD_CALL,
  OTHER), an optional **owner** (the agent working it), and **notes**. Lifecycle **NEW → CONTACTED → QUALIFIED
  → CONVERTED / DISQUALIFIED**. On **qualify**, the lead may be **linked to an existing customer** or **promoted
  to a new customer** (via the shipped `CustomerService`); qualifying then lets an opportunity be created
  against that customer (FR-CRM-01..04).
- **Opportunity / pipeline management.** An **opportunity** (`OPP-####`) against a **Parties customer** —
  carrying a **name/title**, an **estimated value** (a `Money`, base currency), a **pipeline stage**, a **win
  probability** (%, defaulted from the stage), an **expected close date**, the **owning agent**, and optional
  **opportunity lines** (product + estimated qty + estimated unit price — the basis the quote is built from).
  Lifecycle **OPEN → WON / LOST** with the open opportunity moving through **pipeline stages** (FR-CRM-05..08).
- **Pipeline stages (configurable per company).** An ordered set of **pipeline stages** (e.g. QUALIFICATION →
  NEEDS_ANALYSIS → PROPOSAL → NEGOTIATION → CLOSING), each with a **default probability** and a **display
  order**, seeded with a sensible default set per new company and **editable** (add/rename/reorder/deactivate)
  by an admin. An opportunity sits at exactly one stage; advancing the stage is an audited transition
  (FR-CRM-06, FR-CRM-09).
- **Win / loss.** Marking an opportunity **WON** (with a won date) makes it convertible; marking it **LOST**
  (with a **loss reason**) closes it (terminal). A WON/LOST opportunity is **immutable** except for activities
  logged against it (FR-CRM-07).
- **Activities & tasks.** **Activities** (`ACT-####`) — logged interactions of type CALL / EMAIL / MEETING /
  NOTE / TASK, against a **lead or an opportunity**, with a subject, a body/notes, an actor, and a timestamp;
  a **TASK**-type activity additionally carries a **due date**, an **assignee**, and a **done** flag (open →
  done). Activities are the interaction history and the follow-up to-do list (FR-CRM-10..12).
- **Convert opportunity → quotation / sales order (THE SEAM).** A user with `CRM.OPPORTUNITY.CONVERT` may
  **convert** a WON (or OPEN, per policy — OQ-CRM-04) opportunity into either a **quotation** (`QUOTE-####`) or
  a **sales order** (`SO-####`) by **calling the shipped O2C services** (ADR-0021 `QuotationService.create` /
  `SalesOrderService.create`): the opportunity's customer, agent, and lines (product + estimated qty + price)
  seed the quote/SO; the created quote/SO carries back the **source opportunity uid**; the opportunity is
  marked **CONVERTED** with the resulting document uid. CRM **does not** post anything — the quote/SO runs the
  shipped O2C flow from there (FR-CRM-13, BR-CRM-05).
- **Pipeline / funnel read model.** A **pipeline view** (open opportunities grouped by stage, Σ value and Σ
  weighted value (value × probability) per stage) and a simple **forecast** (Σ weighted value of open
  opportunities with expected-close in a period). Win-rate / cycle-time analytics are a thin read (FR-CRM-14).
  *(Deep dashboards/BI live in the Reporting module — CRM exposes the queryable read model.)*
- **Permissions** — `CRM.LEAD.VIEW / MANAGE / QUALIFY`, `CRM.OPPORTUNITY.VIEW / MANAGE / CONVERT`,
  `CRM.ACTIVITY.VIEW / MANAGE`, `CRM.PIPELINE.VIEW`, `CRM.STAGE.MANAGE` (`MODULE.RESOURCE.ACTION`). Per-company
  + per-branch scope; `assertCanActIn` on **every read path**; **audit** on every state transition (lead
  qualified/converted/disqualified; opportunity stage-advanced/won/lost/converted; activity created/completed)
  (FR-CRM-15, FR-CRM-16).
- **Numbering** — `code_sequence` kinds **LEAD** (`LEAD-####`), **OPPORTUNITY** (`OPP-####`), **ACTIVITY**
  (`ACT-####`), per company, concurrency-safe (the shipped `code_sequence` mechanism). The converted quote/SO
  keep their own `QUOTE-####` / `SO-####` (FR-CRM-17).
- **Migration footprint (V51-V55, additive).** The new lead / opportunity / opportunity-line / activity /
  pipeline-stage tables; the pipeline-stage seed per new company; the new permissions; the new `code_sequence`
  kinds. **No Parties change, no Sales change beyond the additive `source_opportunity_uid` back-link the ADR
  designs, no GL/AR/stock change. V1–V19 frozen.**

### Deferred (recognised, NOT built in v1 — separate later increments)

- **Campaign management & marketing attribution.** Marketing campaigns, campaign membership, response tracking,
  cost-per-lead, ROI — deferred (PATH-TO-FULL-ERP §3.9). The lead's `lead_source` captures the coarse origin;
  a full campaign object is later.
- **Case / ticket / service management (post-sale support).** Support tickets, SLAs, escalation — a separate
  service-desk slice (PATH-TO-FULL-ERP §3.9), not pre-sales pipeline.
- **Email / SMS integration & communication sync.** Two-way email sync, sending email from CRM, SMS — depends
  on the Notifications enabler (X.2, PATH-TO-FULL-ERP §3.12); v1 logs activities **manually**.
- **Customer segmentation & targeting lists.** Saved segments, list building, bulk actions on a segment —
  deferred; v1 reads the Parties customer as-is.
- **CRM dashboards & rich KPI visualisations.** Win-rate trends, conversion-funnel charts, leaderboards — the
  *visualisation* layer is the Reporting module's (T2.3); CRM v1 exposes the **queryable pipeline/forecast read
  model**, not the charts.
- **Lead scoring / auto-assignment / routing rules.** Automatic lead scoring, round-robin assignment, territory
  routing — deferred; v1 assigns the owner manually.
- **Quotes/contacts depth.** Multiple named contacts per account with roles, contact-level activities, org-
  charts — deferred; v1's opportunity references the Parties customer and carries a single primary-contact
  snapshot on the lead. **No `contacts` table is added to Parties** (the load-bearing boundary — §1).
- **Recurring/subscription opportunities, multi-currency opportunities, multi-agent split on a deal** — all
  deferred (base currency, single owning agent, one-shot deal in v1).

### Explicitly NOT this module

- **Quotations, sales orders, the invoice channel, the O2C lifecycle** — **Sales / Order-to-Cash** (ADR-0008 /
  ADR-0021) owns quotes, orders, deliveries, invoices, returns, the reservation, and all stock/COGS/revenue
  postings. CRM **calls** `QuotationService.create` / `SalesOrderService.create` on conversion and **reads** the
  resulting document uid — it does **not** own or reimplement any O2C document.
- **The customer master** — **Parties** (ADR-0006) owns `customers` (and `agents`). CRM **reads** customers and
  **calls** `CustomerService.create` to promote a qualified lead — it does **not** write the customers table
  directly and **adds no column or table to Parties**.
- **Any GL / AR / stock / valuation effect** — CRM posts **nothing**, moves **no stock**, opens **no AR item**.
  The financial spine begins only when the converted quote/SO runs the shipped O2C flow.
- **Pipeline dashboards / BI charts** — **Reporting** (ADR-0018) owns visualisation; CRM owns the read model.

## 3. The model: lead, opportunity, pipeline stage, activity/task, conversion

### 3.1 Lead → qualification (CRM-owned contact, then joins Parties)

A **lead** (`LEAD-####`) is an unqualified prospect with its **own** contact details held **in CRM** (name,
company name, contact person, phone, email), a **lead source**, an optional **owning agent**, and notes.
Lifecycle:

- **NEW** — just captured; being triaged.
- **CONTACTED** — the owner has made first contact (a logged activity).
- **QUALIFIED** — the lead is a real prospect; it is **linked to a Parties customer** — either an **existing**
  customer (the prospect is already on file) or a **new** customer **promoted** via the shipped
  `CustomerService.create` (CRM calls it; the new customer carries the lead's details). From a QUALIFIED lead,
  an **opportunity** may be created against the linked customer.
- **CONVERTED** — an opportunity was created from the lead (terminal for the lead; the work continues on the
  opportunity).
- **DISQUALIFIED** — not a real prospect (with a reason); terminal.

A lead **reads/creates a Parties customer only on qualify** — never before. An unqualified lead does **not**
appear in the customer master (§1).

### 3.2 Opportunity + pipeline stage + win probability

An **opportunity** (`OPP-####`) is a qualified, valued deal against a **Parties customer**:

- a **name/title** (e.g. "Q3 wholesale order — Acme Ltd"), the **customer** (required), the **owning agent**
  (defaulted like the invoice's mandatory agent, sales.md FR-SALES-15);
- an **estimated value** (a `Money`, base currency) — the expected deal size;
- a **pipeline stage** (one of the company's configured stages) and a **win probability** (%, defaulted from
  the stage's default, overridable on the opportunity);
- an **expected close date** (drives the forecast);
- optional **opportunity lines** — product + estimated qty + estimated unit price (defaulted from the price
  list); these seed the quote/SO on conversion. An opportunity with **no lines** is allowed (a value-only deal);
  conversion then produces an empty quote the user fills, or requires lines (OQ-CRM-03).

Lifecycle: **OPEN → WON / LOST**. While OPEN, the opportunity moves through **pipeline stages** (an audited
stage transition; the probability follows the stage default unless overridden). **WON** (with a won date) makes
it convertible; **LOST** (with a loss reason) closes it. WON/LOST is terminal; the opportunity is immutable
thereafter except for activities logged against it.

### 3.3 Pipeline stages (configurable, seeded per company)

A company has an **ordered set of pipeline stages**, each with a **name**, a **display order**, and a **default
probability**. A sensible default set is **seeded per new company** (QUALIFICATION 10% → NEEDS_ANALYSIS 25% →
PROPOSAL 50% → NEGOTIATION 75% → CLOSING 90%) and is **editable** (`CRM.STAGE.MANAGE`): add, rename, reorder,
deactivate (a deactivated stage holds no new opportunities but keeps historical ones). Stages are **per
company**, like the CoA and tax rates. WON/LOST are **opportunity statuses**, **not** stages (a stage is a
position in the open pipeline; the close is the status — §1 word discipline).

### 3.4 Activities & tasks (the interaction history + follow-ups)

An **activity** (`ACT-####`) is a logged interaction against **a lead or an opportunity** (exactly one parent):

- **type** ∈ CALL / EMAIL / MEETING / NOTE / TASK;
- a **subject**, a **body/notes**, an **actor**, and an **occurred-at** timestamp;
- a **TASK** additionally carries a **due date**, an **assignee** (an agent/user), and a **done** flag (the
  follow-up to-do); a CALL/EMAIL/MEETING/NOTE is a historical record (no due date).

Activities are the timeline on the lead/opportunity detail and the open-task list per owner. They post nothing
and move nothing — they are pure CRM records.

### 3.5 Conversion (THE SEAM into Order-to-Cash)

**Converting** an opportunity (`CRM.OPPORTUNITY.CONVERT`) creates a **quotation** or a **sales order** from it
by **calling the shipped O2C services** (ADR-0021):

- the opportunity's **customer**, **owning agent**, and **lines** (product + estimated qty + estimated unit
  price + any discount) seed the new quote/SO; the O2C `InvoiceTotalsCalculator` computes the totals (CRM does
  not compute money);
- the created **quote/SO carries back the source opportunity uid** (an additive back-link on the O2C document —
  the ADR designs the exact column);
- the **opportunity is marked CONVERTED** with the resulting document uid (and the originating lead, if any,
  is already CONVERTED);
- CRM **posts nothing, reserves nothing, moves no stock** — the quote/SO is now a real O2C document that runs
  the shipped flow (a quote is sent/accepted → SO; an SO is confirmed → reserves; etc., ADR-0021).

The conversion **target** (quote vs SO) and **gate** (must the opportunity be WON first, or may an OPEN
opportunity convert to a quote while still negotiating?) are the architect's policy (OQ-CRM-04; recommended:
**OPEN may convert to a quote** — quoting *is* part of negotiating; **conversion to an SO requires WON** — an
SO is a commitment). The convert call is **idempotent / guarded** so a double-click does not create two quotes
(BR-CRM-06).

## 4. Actors / personas

- **Sales agent / business development officer** — captures **leads** (`CRM.LEAD.MANAGE`), works them
  (`CONTACTED`), **qualifies** them (`CRM.LEAD.QUALIFY` — links/promotes the customer), creates and works
  **opportunities** (`CRM.OPPORTUNITY.MANAGE` — advancing stages), logs **activities/tasks**
  (`CRM.ACTIVITY.MANAGE`), and **converts** a deal to a quote/SO (`CRM.OPPORTUNITY.CONVERT`). The front line of
  the pipeline.
- **Sales manager / supervisor** — oversees the **pipeline and forecast** (`CRM.PIPELINE.VIEW`), reassigns
  opportunity owners, marks **WON / LOST**, manages the **pipeline stages** (`CRM.STAGE.MANAGE`), and reviews
  win-rate / cycle-time. The authority over the funnel.
- **Owner / general manager** — reads the **pipeline and forecast** (weighted value by stage, expected closes
  this period) to plan; the consumer of the funnel's output.
- *(No new human actor on any posting — CRM posts nothing. The only cross-module effect is the
  **convert-to-O2C** call, performed under the converting user's company/branch scope through the shipped
  Quotation/SalesOrder services, audited as a CRM transition + the O2C document's own creation audit.)*

## 5. Functional requirements

> IDs are `FR-CRM-NN`. Each is a crisp, testable statement. "Read Parties" = read the shipped `customers` /
> `agents` masters by uid/id (no Parties write except the qualify-promote call to `CustomerService.create`);
> "feed Sales" = call the shipped `QuotationService.create` / `SalesOrderService.create` on conversion; CRM
> **posts no GL, moves no stock, opens no AR**.

### Lead

- **FR-CRM-01** A user with `CRM.LEAD.MANAGE` may create a **lead** (`LEAD-####`) with prospect contact details
  (name, company, contact person, phone, email), a **lead source**, an optional **owning agent**, and notes. A
  lead **does not** create a Parties customer (BR-CRM-01).
- **FR-CRM-02** A lead has the lifecycle **NEW → CONTACTED → QUALIFIED → CONVERTED / DISQUALIFIED**. Edits to a
  lead's details are allowed while OPEN (NEW/CONTACTED/QUALIFIED); CONVERTED/DISQUALIFIED is terminal
  (BR-CRM-01).
- **FR-CRM-03** A user with `CRM.LEAD.QUALIFY` may **qualify** a lead, **linking it to an existing Parties
  customer** or **promoting it to a new customer** via the shipped `CustomerService.create` (CRM calls the
  service; it does not write `customers` directly). A qualified lead carries its linked `customerUid`
  (FR-CRM-04, BR-CRM-02).
- **FR-CRM-04** From a **QUALIFIED** lead, a user with `CRM.OPPORTUNITY.MANAGE` may create an **opportunity**
  against the linked customer; the lead becomes **CONVERTED** (BR-CRM-02).

### Opportunity & pipeline

- **FR-CRM-05** A user with `CRM.OPPORTUNITY.MANAGE` may create an **opportunity** (`OPP-####`) against a
  **Parties customer**, with a name/title, an **estimated value** (`Money`, base currency), an **owning agent**,
  an **expected close date**, an initial **pipeline stage** + **win probability** (defaulted from the stage),
  and optional **opportunity lines** (product + estimated qty + estimated unit price) (BR-CRM-03).
- **FR-CRM-06** While **OPEN**, an opportunity moves through the company's **pipeline stages** (`CRM.OPPORTUNITY
  .MANAGE`); advancing/changing the stage updates the probability to the stage default unless overridden, and is
  an **audited transition** (FR-CRM-09, BR-CRM-04).
- **FR-CRM-07** A user with `CRM.OPPORTUNITY.MANAGE` may mark an opportunity **WON** (with a won date) or
  **LOST** (with a **loss reason**). WON/LOST is terminal; the opportunity is immutable thereafter except for
  activities (BR-CRM-04).
- **FR-CRM-08** An opportunity is **scoped per company + branch**; its estimated value is a `Money` in the base
  currency; its lines reuse the Products price-list default for the estimated unit price (CRM reads Products, it
  does not own pricing) (NFR-CRM-01/03).

### Pipeline stages

- **FR-CRM-09** A company has an **ordered set of pipeline stages** (name, display order, default probability),
  **seeded per new company** with a default set and **editable** by a user with `CRM.STAGE.MANAGE` (add, rename,
  reorder, deactivate). An opportunity references exactly one **active** stage; a deactivated stage keeps its
  historical opportunities (BR-CRM-04).

### Activities & tasks

- **FR-CRM-10** A user with `CRM.ACTIVITY.MANAGE` may log an **activity** (`ACT-####`) of type CALL / EMAIL /
  MEETING / NOTE / TASK against **a lead or an opportunity** (exactly one parent), with a subject, body, actor,
  and occurred-at timestamp (BR-CRM-07).
- **FR-CRM-11** A **TASK** activity additionally carries a **due date**, an **assignee**, and a **done** flag;
  it may be marked **done** (`CRM.ACTIVITY.MANAGE`). A user may list their **open tasks** (assignee = self) and
  a lead/opportunity's **activity timeline** (`CRM.ACTIVITY.VIEW`) (BR-CRM-07).
- **FR-CRM-12** Activities **post nothing, move nothing** — they are pure CRM interaction records; they are
  **audited** on create/complete (NFR-CRM-04).

### Conversion (the seam)

- **FR-CRM-13** A user with `CRM.OPPORTUNITY.CONVERT` may **convert** an opportunity into a **quotation**
  (`QUOTE-####`) or a **sales order** (`SO-####`) by **calling the shipped O2C services** (ADR-0021): the
  opportunity's customer, agent, and lines seed the quote/SO; the created document carries the **source
  opportunity uid**; the opportunity becomes **CONVERTED** with the resulting document uid. CRM **posts
  nothing** (BR-CRM-05). The conversion **gate** (OPEN→quote allowed; SO requires WON) and **target** are the
  architect's policy (OQ-CRM-04). The convert is **idempotent** — a retry does not create a duplicate document
  (BR-CRM-06, NFR-CRM-02).

### Pipeline read model

- **FR-CRM-14** A user with `CRM.PIPELINE.VIEW` may read the **pipeline** (open opportunities grouped by stage,
  with Σ value and Σ weighted value = Σ(value × probability) per stage) and a **forecast** (Σ weighted value of
  open opportunities with expected-close in a period), and basic **win-rate** (WON / (WON+LOST)) and
  **cycle-time** (created → won) figures — all scoped per company/branch (NFR-CRM-01).

### Scope, permissions, numbering

- **FR-CRM-15** CRM is **scoped per company + branch**; every lead, opportunity, opportunity line, activity, and
  pipeline stage belongs to exactly one company; **no read crosses company scope**; `assertCanActIn` guards
  **every read path**; **audit** records **every state transition** (lead qualified/converted/disqualified;
  opportunity stage-advanced/won/lost/converted; activity created/completed) with actor, action, target, and
  company context (BR-CRM-08, NFR-CRM-01/04).
- **FR-CRM-16** The new operations are **gated by IAM permissions**: `CRM.LEAD.VIEW / MANAGE / QUALIFY`,
  `CRM.OPPORTUNITY.VIEW / MANAGE / CONVERT`, `CRM.ACTIVITY.VIEW / MANAGE`, `CRM.PIPELINE.VIEW`,
  `CRM.STAGE.MANAGE` (`MODULE.RESOURCE.ACTION`); gated with `@perm.has` / `@perm.scoped` (never `hasAuthority`).
  The converted quote/SO rides the existing `SALES.QUOTE.*` / `SALES.ORDER.*` perms of the converting user
  (FR-CRM-13).
- **FR-CRM-17** Numbering uses `code_sequence` kinds **LEAD** (`LEAD-####`), **OPPORTUNITY** (`OPP-####`),
  **ACTIVITY** (`ACT-####`), per company, concurrency-safe; the converted quote/SO keep `QUOTE-####` /
  `SO-####`. Codes/kinds are seeded with the module (the V51-V55 migration).

## 6. Business rules (invariants)

> Proposed. CRM is a pre-sales pipeline with **one** cross-module effect (convert → O2C). The invariants below
> protect the Parties boundary, the convert seam, and tenant isolation. A violation that creates a duplicate
> O2C document on convert, writes a customer for an unqualified lead, or leaks across companies is a defect.

- **BR-CRM-01 — A lead is not a customer; it holds its own contact details in CRM.** Capturing a lead creates
  **no** Parties `customers` row; the lead's contact details live in CRM until qualification (FR-CRM-01/02).
- **BR-CRM-02 — Qualification links or promotes a Parties customer; an opportunity is always against a
  customer.** On qualify, a lead is **linked to an existing** customer or **promoted to a new** one via the
  shipped `CustomerService.create`; an **opportunity always references a Parties customer** (FR-CRM-03/04/05).
- **BR-CRM-03 — An opportunity carries an estimated value, a stage, a probability, and an expected close;
  status is OPEN/WON/LOST.** The pipeline stage is a position in the open funnel; WON/LOST is the lifecycle
  close (distinct axes); a WON/LOST opportunity is immutable except for activities (FR-CRM-05/06/07).
- **BR-CRM-04 — Stage is a configurable per-company position; status (OPEN/WON/LOST) is the lifecycle.** Stages
  are seeded per company and editable; an opportunity references exactly one active stage; advancing the stage
  and marking WON/LOST are **audited** transitions, never silent (FR-CRM-06/07/09).
- **BR-CRM-05 — Convert calls the shipped O2C services and posts nothing in CRM.** Converting an opportunity
  creates a **real** quotation/sales order through `QuotationService.create` / `SalesOrderService.create`
  (ADR-0021) — CRM moves no stock, posts no GL, opens no AR; the created document carries the **source
  opportunity uid** and the opportunity becomes CONVERTED (FR-CRM-13).
- **BR-CRM-06 — Convert is idempotent; one opportunity yields at most one converted document per convert
  action.** A retried/double-clicked convert does **not** create a duplicate quote/SO; the opportunity records
  the resulting document uid and a second convert is rejected (or returns the existing document) (FR-CRM-13,
  NFR-CRM-02).
- **BR-CRM-07 — Activities are pure CRM records against exactly one lead or opportunity.** An activity has
  exactly one parent (a lead **or** an opportunity), posts nothing, and is audited; a TASK adds a due date,
  assignee, and done flag (FR-CRM-10/11/12).
- **BR-CRM-08 — Per-company + per-branch isolation.** Every lead, opportunity, opportunity line, activity, and
  pipeline stage **belongs to exactly one company**; no read or derived figure crosses company scope.
  Cross-company CRM leakage is a **release blocker** (NFR-CRM-01), as for Sales/Parties.
- **BR-CRM-09 — No Parties schema change; CRM reads Parties, it does not extend it.** CRM adds **no** column or
  table to Parties; it reads `customers`/`agents` and calls `CustomerService.create`. A lead's contact details
  live in CRM, not in a new Parties `contacts` table (the deferred contacts depth, §2).
- **BR-CRM-10 — Append-only state transitions; CRM owns no reversible financial effect.** Lead/opportunity
  transitions are audited and append-only; because CRM posts nothing, there is **no reversal mechanic** to own
  (a converted quote/SO is reversed in O2C, not CRM) (NFR-CRM-04).

## 7. Process flows (happy path + main unhappy paths), proposed v1

### 7.1 Lead → qualify → opportunity — happy path
1. A sales agent captures a **lead** (`CRM.LEAD.MANAGE`): name, company, contact, phone, email, source NEW
   (FR-CRM-01). `LEAD-####` allocated; audited.
2. The agent logs a **call** activity (`CRM.ACTIVITY.MANAGE`) and moves the lead to **CONTACTED** (FR-CRM-10).
3. The agent **qualifies** the lead (`CRM.LEAD.QUALIFY`): the prospect is new, so CRM **promotes** it to a
   Parties customer via `CustomerService.create`; the lead is **QUALIFIED**, linked to the new customer
   (FR-CRM-03, BR-CRM-02). Audited.
4. The agent creates an **opportunity** (`CRM.OPPORTUNITY.MANAGE`) against that customer — value, stage
   QUALIFICATION (prob 10%), expected close, lines (FR-CRM-05); the lead becomes **CONVERTED** (FR-CRM-04).

### 7.2 Work the pipeline → win — happy path
1. The agent advances the opportunity stage QUALIFICATION → PROPOSAL (`CRM.OPPORTUNITY.MANAGE`); the probability
   follows the stage default (50%) unless overridden (FR-CRM-06). Audited.
2. The agent logs a **meeting** and a follow-up **task** (due next week, assigned to self) (FR-CRM-10/11).
3. The deal closes; the agent marks the opportunity **WON** with a won date (FR-CRM-07). It is now convertible.

### 7.3 Convert → quotation → sales order — happy path (THE seam)
1. The agent **converts** the WON opportunity to a **sales order** (`CRM.OPPORTUNITY.CONVERT`): CRM calls
   `SalesOrderService.create` with the opportunity's customer, agent, and lines (FR-CRM-13, BR-CRM-05).
2. A real **SO** (`SO-####`) is created (ADR-0021), carrying the **source opportunity uid**; the opportunity is
   **CONVERTED** with the SO's uid (BR-CRM-05). Audited. CRM posts nothing.
3. The SO runs the shipped O2C flow from here (confirm → reserve → deliver → invoice) — outside CRM.

### 7.4 Convert OPEN opportunity to a quote (negotiating) — happy path
1. While **OPEN** at PROPOSAL, the agent converts to a **quotation** (`CRM.OPPORTUNITY.CONVERT`, target QUOTE):
   CRM calls `QuotationService.create`; a `QUOTE-####` is created with the opportunity's lines (FR-CRM-13,
   OQ-CRM-04 — OPEN→quote allowed). The opportunity stays OPEN (quoting is part of negotiating); the quote
   carries the source opportunity uid.
2. The customer accepts the quote → the quote converts to an SO in O2C (ADR-0021); the agent marks the
   opportunity WON. *(The quote→SO conversion is O2C's, not CRM's.)*

### 7.5 Main unhappy paths
- **Create an opportunity from an unqualified lead** (7.1.4 before 7.1.3) → **rejected**; an opportunity needs a
  linked customer, which qualification provides (BR-CRM-02).
- **Convert an opportunity twice** (7.3.1 retried) → the **idempotency guard** short-circuits; the opportunity
  already records its converted document uid — a second convert is rejected or returns the existing document; no
  duplicate quote/SO (BR-CRM-06, NFR-CRM-02).
- **Convert an OPEN opportunity directly to an SO** (7.3.1 while OPEN) → **rejected** under the recommended
  policy (an SO is a commitment; mark WON first) — OPEN may convert only to a **quote** (OQ-CRM-04). Owner may
  relax this.
- **Edit a WON/LOST opportunity's value/stage** → **rejected**; a closed opportunity is immutable except for
  activities (BR-CRM-03).
- **Mark LOST without a reason** → **rejected**; a loss reason is mandatory (FR-CRM-07).
- **Qualify a lead to a customer in another company** → **rejected**; `assertCanActIn` denies a cross-company
  customer link (BR-CRM-08, NFR-CRM-01).
- **Convert an opportunity whose customer was archived** → handled per the O2C service's own validation (CRM
  passes the customer uid; Sales validates) — surfaced as the O2C error (OQ-CRM-05).

## 8. Non-functional

- **NFR-CRM-01 — Tenant isolation.** Every lead, opportunity, opportunity line, activity, pipeline stage, and
  derived pipeline/forecast figure is scoped by `company_id` (+ `branch_id`) and goes through the
  tenant-predicate repository base (PROJECT-CONVENTIONS §3.2); `assertCanActIn` guards **every read path**.
  Cross-company CRM leakage is a **release blocker**, as for Sales/Parties (BR-CRM-08).
- **NFR-CRM-02 — Idempotent conversion.** The convert → O2C effect rides the shipped idempotency discipline
  (`IdempotencyGuard` / `processed_events` if event-driven, or a unique source-opportunity constraint on the
  O2C document if synchronous — the architect's mechanism, OQ-CRM-02): a retried convert creates the document
  **once**. An integration test must convert the same opportunity twice and assert **one** quote/SO.
- **NFR-CRM-03 — Money correctness.** The opportunity's estimated value and line estimated prices are `Money`
  (amount + currency, ADR-0005) in the base currency; on conversion the O2C `InvoiceTotalsCalculator` computes
  the real totals (CRM does not compute money — it passes the estimates as the quote/SO line inputs).
- **NFR-CRM-04 — Audit on every state transition.** Every lead/opportunity transition (qualified, converted,
  disqualified, stage-advanced, won, lost) and every activity create/complete is written to the append-only
  audit trail with actor, action, target, timestamp, and company context (NFR-CRM-01, the shipped audit
  discipline).
- **NFR-CRM-05 — Performance / pagination.** Lead and opportunity lists, the activity timeline, and the
  pipeline/forecast read are **paginated** and indexed by `(company_id, branch_id, status)` / `(company_id,
  stage_id)` / `(parent, occurred_at)` — no unbounded scans (the shipped pagination discipline).
- **NFR-CRM-06 — Additive, no regression.** The module is **purely additive** (V51-V55) on the frozen V1–V19;
  it makes **no Parties change** and **at most one additive back-link column** on the O2C quote/SO (the
  architect's call — OQ-CRM-01); existing Sales/Parties behaviour is unchanged.

## 9. User stories

- **US-CRM-01** As a **sales agent**, I capture a **lead** with the prospect's details and source, so I have a
  record of every prospect without polluting the customer master (FR-CRM-01).
- **US-CRM-02** As a **sales agent**, I **qualify** a lead — linking or creating the customer — so a real
  prospect becomes a deal I can quote (FR-CRM-03/04).
- **US-CRM-03** As a **sales agent**, I create and work an **opportunity** through the **pipeline stages**, so
  the deal's value, stage, and likelihood are tracked (FR-CRM-05/06).
- **US-CRM-04** As a **sales agent**, I log **calls, meetings, and tasks** against a deal, so the interaction
  history and my follow-ups are in one place (FR-CRM-10/11).
- **US-CRM-05** As a **sales agent**, I **convert** a won opportunity to a **quotation/sales order** in one
  click, so the deal hands off to fulfilment without re-keying (FR-CRM-13).
- **US-CRM-06** As a **sales manager**, I read the **pipeline and forecast** — weighted value by stage, expected
  closes this quarter, win rate — so I can plan and coach (FR-CRM-14).
- **US-CRM-07** As a **sales manager**, I **manage the pipeline stages** for my company, so the funnel matches
  how we sell (FR-CRM-09).
- **US-CRM-08** As the **owner**, I see the whole funnel from prospect to won deal to fulfilled order, scoped to
  my company, so I trust the forecast (FR-CRM-14/15).

## 10. Build staging

The increment is **M-L** and naturally stages:

- **Stage 1 — the pipeline core (leads → opportunities → stages → activities), no convert:** lead capture +
  qualify (link/promote customer), opportunity + pipeline stages + win/loss, activities/tasks, the pipeline/
  forecast read. Perms `CRM.LEAD.*` / `CRM.OPPORTUNITY.{VIEW,MANAGE}` / `CRM.ACTIVITY.*` / `CRM.PIPELINE.VIEW` /
  `CRM.STAGE.MANAGE`. This is self-contained CRM with **no cross-module write** beyond the qualify-promote
  `CustomerService.create` read/call.
- **Stage 2 — the convert seam into O2C:** `CRM.OPPORTUNITY.CONVERT`; the call into `QuotationService.create` /
  `SalesOrderService.create`; the additive `source_opportunity_uid` back-link on the O2C document; the
  idempotency guard. This is the one cross-module effect and the load-bearing seam.

## 11. Open questions (recommended owner-style defaults adopted; flag the load-bearing ones for ADR-0031)

> CRM has no financial invariant, so its OQs are lighter than a posting module's. The **load-bearing** ones are
> the **convert seam mechanism** (OQ-CRM-01/02) and the **convert gate/target** (OQ-CRM-04) — none blocks the
> requirements; the *behaviour* (an opportunity converts to a real quote/SO, idempotently) is fixed.

- **OQ-CRM-01 (load-bearing) — the convert back-link: where does the opportunity↔quote/SO link live?** A
  single additive `source_opportunity_uid` column on the O2C `quotations` / `sales_orders` headers (recommended
  — the leanest, mirrors ADR-0021's `source_quotation_uid`), **or** a CRM-side `opportunity_conversions` link
  table (no O2C change). Recommended: the additive column on the O2C documents (one column each, additive on
  the frozen-but-extensible sales tables). **Architect's decision (ADR-0031).**
- **OQ-CRM-02 (load-bearing) — convert mechanism: synchronous service call vs outbox event?** A **synchronous**
  call to `QuotationService.create` / `SalesOrderService.create` in the convert request (recommended — the user
  expects the quote/SO immediately; the same `crm → sales.service` shape Sales takes to `stock.service`/
  `ar.service`), with idempotency via a unique `source_opportunity_uid` on the created document; **or** an
  outbox `OPPORTUNITY.WON` event a Sales handler consumes (decoupled but async — the user does not see the quote
  immediately). Recommended: **synchronous call** with a uniqueness backstop. **Architect's decision.**
- **OQ-CRM-03 — opportunity lines optional or required for convert?** May an opportunity with no lines be
  converted (producing an empty quote the user fills), or must it have lines? Recommended: **lines optional on
  the opportunity; convert requires at least one line** (a quote/SO needs a product) — surfaced as a friendly
  validation. Owner confirms.
- **OQ-CRM-04 (load-bearing) — convert gate + target.** May an **OPEN** opportunity convert, and to what?
  Recommended: **OPEN may convert to a QUOTE** (quoting is part of negotiating), **conversion to an SO requires
  WON** (an SO is a commitment); a WON opportunity may convert to either. Owner confirms (a one-line service
  guard either way).
- **OQ-CRM-05 — convert with an archived/credit-blocked customer.** The convert passes the customer uid to the
  O2C service, which applies its own validation (archived customer, credit limit at SO confirm — sales.md).
  Recommended: **CRM surfaces the O2C service's error** (it does not duplicate the validation). Owner confirms.
- **OQ-CRM-06 — lead↔customer cardinality.** May two leads qualify to the **same** existing customer (e.g. two
  enquiries from one firm)? Recommended: **yes** (a customer may have many leads/opportunities over time); the
  lead→customer link is many-to-one. Owner confirms.
- **OQ-CRM-07 — activity assignee/owner identity.** Is an opportunity owner / task assignee an **agent**
  (Parties) or an **app_user** (IAM), or either? Recommended: **app_user** for the assignee/owner (the person
  who logs in and works the deal), with the **agent** captured for commission continuity on the converted
  quote/SO (which carries the agent). Architect confirms the FK target (ADR-0031).
- **OQ-CRM-08 (deferred) — campaigns, email sync, lead scoring, case management, contacts depth** — all
  deferred (§2), none precluded by the v1 model (NFR-CRM-06).

---

> This document is the business spec for CRM. The **data model, the convert-seam mechanism, the exact tables /
> columns / enums / API surface / events / perms / ScopeGuard cases / nav routes / migration (V51-V55)** are the
> solutions-architect's, in **ADR-0031**. Do not infer a schema from this document.
