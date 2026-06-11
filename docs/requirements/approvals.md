# Requirements — Approvals Workflow Engine (a generic, reusable engine that lets ANY module submit a document for multi-step approval against a policy, and query its decision state)

> Status: **RATIFIED-EQUIVALENT (architect-assumed, owner-style defaults — 2026-06-11).** This is a
> **cross-cutting platform enabler** (docs/PATH-TO-FULL-ERP.md §3.12 X.5 "Approval workflow (threshold-based
> multi-step)"; §4 critical-dependency #7 "Approvals — required before Manufacturing ships and for high-value
> PO/payment governance"; §3.4 "Multi-step PO approval workflow (thresholds)"). There is no prior
> owner-ratification meeting for this slice; the system-analyst's standard discovery has not yet produced a
> ratified `approvals.md`. **The architect has therefore made owner-style assumptions for the genuinely
> business-level choices** (what gets approved, who approves, what a threshold is, what happens on
> reject/recall) and **flagged the load-bearing open questions** (§11) that the owner must confirm before the
> ADR's first slice goes to build. None of the flagged OQs blocks writing the data model — the *behaviour* is
> fixed by these assumptions; the owner can flip a default cheaply later.
>
> Author: solutions-architect (standing in for system-analyst on a cross-cutting enabler) · Domain: a **NEW
> platform-adjacent module** `com.erp.modules.approvals` (the engine + its policy/request/step/decision tables
> + the integration hook every other module calls). Business-level spec. **No schema, no API shapes, no
> tables/columns, no code** — those are the solutions-architect's, in **ADR-0022** (next step). Do not infer a
> data model from this document.
>
> **This is the Approvals Workflow Engine — a reusable governance spine, NOT a procurement feature.** The
> headline framing: a high-value purchase order, a payment run, a credit-note reversal, a manual journal, a
> stock write-off — all of these are *documents that may need someone's sign-off before they take effect*. Today
> ERPCLEAN2 has **no approval concept at all**: a PO goes straight to ORDERED, a payment run executes, a journal
> posts — gated only by a coarse RBAC permission ("you may do this kind of thing") with **no amount threshold,
> no second pair of eyes, no audit trail of who approved what**. This module builds the **generic engine** that
> any module plugs into: a module **submits a document** (by type + amount + the submitter's branch) for
> approval; the engine **matches a policy**, **creates an approval request with the required steps**, routes it
> to approvers, records their **decisions**, and exposes the request's **state** so the submitting module can
> gate its own finalise transition ("don't let this PO go ORDERED until its approval request is APPROVED"). The
> engine is **document-type-agnostic** — it stores a `document_type` + `document_uid` + `amount` + `branch`, not
> a PO or a payment. Procurement (PO/requisition approval) is the **first consumer**, and payments/journals are
> the obvious next ones, but the engine knows nothing about any of them.
>
> **Depends on:** **nothing not already shipped.** It builds entirely on the platform spine: **IAM** (ADR-0001:
> org → company → branch; `app_users`; roles as permission bundles; the `user_branch` many-branches /
> default-branch model — the engine routes to approvers *by role* and *by branch*); **RBAC** (ADR-0002:
> `@perm.has` / `@perm.scoped`, `PermissionResolver` — the engine reuses role-membership to find approvers);
> **`ScopeGuard` / `RequestContext`** (every read path `assertCanActIn`); **the transactional outbox** (ADR-0009:
> `DomainEventHandler` / `IdempotencyGuard` / `processed_events` / `OutboxPublisher` — the engine **emits**
> `APPROVAL.*` events when a request resolves, so a consuming module can react asynchronously *as well as* poll
> synchronously); **`code_sequence` numbering** (ADR-0007 D-6 — `APPROVAL-####`); **`MasterStatus`** soft-delete
> on the policy master; **audit** (ADR-0004 — every decision audited); **Money** (ADR-0005 — the threshold
> amount is base-currency `NUMERIC(19,4)` HALF_UP). It does **NOT** depend on GL (the engine posts **nothing** to
> the books — it gates, it does not transact), does NOT depend on any consuming module (the dependency points the
> other way — consumers depend on the engine), and introduces **no new GL accounts, no `gl_config` keys, no GL
> postings** (§10).
>
> **This module GATES:** procurement depth — multi-step PO / purchase-requisition approval (PATH-TO-FULL-ERP §3.4
> "blocks PO ORDERED transition"); and is **reusable by** payment runs (AP), credit-note reversals, manual
> journals (GL), stock write-offs, and ultimately Manufacturing production-order release (PATH-TO-FULL-ERP §4 #7).
> Those consumers are **out of scope here** — this slice ships the **engine + the integration contract** they call;
> wiring each consumer is that consumer's own increment.

## 1. Business context & why now

ERPCLEAN2 today enforces *authority* (RBAC: "this user may run a payment") but not *governance* ("a payment over
5,000,000 needs the finance manager's sign-off, and a second sign-off over 50,000,000"). Every consequential
action — placing a purchase order, executing a payment run, reversing a credit note, posting a large manual
journal, writing off stock — fires the moment a sufficiently-permissioned user clicks it. There is **no concept
of a pending approval, no amount-threshold escalation, no multi-step chain, and no record of who approved**.
That is acceptable for a small single-operator company; it is a hard blocker for a multi-branch organisation
where the person who *raises* a high-value document must not be the person who *approves* it (segregation of
duties), and where the approval requirement **escalates with the amount** (a branch manager can sign off
1,000,000; the CFO is required above 100,000,000).

The platform plan named this explicitly: PATH-TO-FULL-ERP §3.12 lists "Approval workflow (threshold-based
multi-step)" as cross-cutting enabler **X.5**, "Required before Manufacturing/high-value payments"; §3.4 lists
"Multi-step PO approval workflow (thresholds)" as the first concrete consumer ("blocks PO ORDERED transition");
§4 critical-dependency #7 makes it a gate for Procurement depth and Manufacturing. **Now** is the time because
Procurement depth (requisitions, RFQ, PO approval) is the next operational slice, and it cannot ship its
governance story without this engine — building a PO-specific approval would bake procurement assumptions into
what must be a **reusable** spine, and then payments/journals would each grow their own ad-hoc copy. Build the
**generic engine once**.

The design imperative: the engine must be **completely document-agnostic**. It must never `import` a
PurchaseOrder, a Payment, or a Journal. It stores a *reference* to a document (a type string + a uid + the
amount + the originating branch) and a *result* (the request's status). The consuming module owns its document
and its own finalise transition; it **asks** the engine two things — "create an approval request for this
document" and "is this document's approval request resolved, and how?" — and gates itself on the answer. This is
the same DTO-only, no-cross-module-entity discipline every other module boundary already holds.

### Vocabulary (read this first)

- **Approval policy** — a configured rule, per company, that says: *for this document type, when the amount is in
  this band (and optionally this branch), the following ordered steps of approval are required.* A policy is a
  **master** (created/edited/deactivated by an admin; `MasterStatus` soft-delete). Example: "Purchase Order,
  amount 1,000,000–100,000,000, any branch → Step 1: PURCHASING_MANAGER role; Step 2: FINANCE_MANAGER role."
- **Policy step** — one ordered rung of a policy's approval chain: *who* may approve it (an **approver role** —
  a reference to an IAM role, e.g. `FINANCE_MANAGER`) and its **sequence** (1, 2, 3 …). Steps are approved **in
  order** (step 2 cannot be decided until step 1 is APPROVED). v1 routes by **role + branch**, not by named
  individual (§2 deferred: named approvers, approver groups, delegation).
- **Amount band / threshold** — the `[min, max)` money range on a policy that decides whether the policy applies
  to a submitted document. A document with amount `A` matches the policy whose band contains `A` for that
  document type (and branch, if the policy is branch-scoped). **A document below the lowest band's minimum needs
  no approval** (auto-approved — see "no-policy-match"). Thresholds are base-currency `NUMERIC(19,4)`, HALF_UP.
- **Approval request** — the **runtime instance** the engine creates when a module submits a document: it pins
  the matched policy, the document type/uid/amount/branch, the submitter, and a **status** (PENDING →
  APPROVED / REJECTED / RECALLED / CANCELLED). It is the thing a consuming module polls. `APPROVAL-####` numbered.
- **Approval step (request step)** — the **runtime copy** of each policy step, materialised onto the request at
  submit time (so a later policy edit cannot change an in-flight request — the request is a **frozen snapshot**
  of the policy it matched, the same snapshot discipline sales lines take of price). Each carries its sequence,
  its approver role, and its own status (PENDING → APPROVED / REJECTED).
- **Approval decision** — an **append-only** record of a single approver acting on a single request step: APPROVE
  or REJECT, by whom, when, with an optional comment. A step's status is driven by its decision(s); v1 is
  **single-approver-per-step** (one APPROVE resolves the step; deferred: quorum / N-of-M, §2).
- **Submit (for approval)** — the act, by a consuming module, of asking the engine to start governance for a
  document: "here is a `PURCHASE_ORDER` with uid X, amount A, in branch B — create its approval request." The
  engine matches a policy and returns the request (or signals **no approval needed**). Idempotent per
  (document type, document uid) — re-submitting the same document returns the existing request (BR-APR-08).
- **Recall (withdraw)** — the **submitter** withdrawing a still-PENDING request (e.g. "I made a mistake, I'll
  re-raise the PO"). Terminal; the document is back in the submitter's hands, un-approved.
- **Decide (approve / reject)** — an **approver** acting on the current open step of a PENDING request. APPROVE
  advances to the next step (or resolves the whole request APPROVED if it was the last step); REJECT resolves
  the **whole request** REJECTED immediately (one rejection kills the chain — v1 default, §11 OQ-APR-04).
- **Resolved / terminal state** — a request in APPROVED / REJECTED / RECALLED / CANCELLED. Once resolved a
  request is **immutable** (corrections are a new submission of a new/amended document — the append-only
  posture every transactional module holds). The consuming module reads the terminal status to gate itself.
- **No-policy-match (auto-approve)** — when a submitted document's (type, amount, branch) matches **no** active
  policy, the engine returns a request in a terminal **APPROVED** state with `auto_approved = true` and **no
  steps** (the document needs no human sign-off — e.g. a PO below any threshold). The consuming module treats
  this identically to a human-approved request: it may finalise. (The alternative — fail-closed / block — is the
  flagged OQ-APR-01; the **assumed default is auto-approve-when-no-policy**, so adding the engine never blocks an
  existing flow that has no policy configured.)
- **Integration hook** — the **service contract** the engine exposes to consuming modules: `submitForApproval(...)`
  (returns the request DTO, or the auto-approved terminal request), `getApprovalState(documentType, documentUid)`
  (returns the request DTO / status), and the `APPROVAL.RESOLVED` outbox event (so a consumer can *also* react
  asynchronously). DTO-only; the engine imports no consumer entity, the consumer imports no engine entity.

> **Word discipline (carried into the glossary):** an **approval policy** (the configured rule — a master) is
> **not** an **approval request** (a runtime instance of governance for one document). A **policy step** (the
> rule's rung — who-may-approve at sequence N) is **not** an **approval step** / request step (the runtime copy
> on a request) and **not** a **decision** (one approver's append-only act on a step). **Submit** (a module asks
> the engine to start governance) is **not** **decide** (an approver acts) and **not** **recall** (the submitter
> withdraws). **Auto-approve** (no policy matched → terminal APPROVED, no steps) is **not** **APPROVED** by a
> human (a chain completed) — both are terminal APPROVED and gate identically, but `auto_approved` distinguishes
> them for audit. The engine **gates** (says yes/no on whether a document may proceed); it **does not transact**
> — it posts no GL, moves no stock, executes no payment. The **consuming module** owns the document and its own
> finalise transition; the engine owns only the governance verdict.

## 2. Scope

> Every line below is **assumed-v1** (architect owner-style defaults, §11 flags the load-bearing ones). This is
> the **generic Approvals Workflow Engine** — a reusable governance spine: amount-threshold, multi-step,
> role-and-branch-routed approval policies; runtime approval requests with frozen step snapshots; append-only
> decisions; an idempotent submit/query integration hook + a resolution event. It is a **new module**; it adds
> no GL, no stock, no consumer-specific logic.

### In scope (v1 — "let any module submit a document for policy-matched, multi-step, threshold-based approval, record the decisions, and expose the verdict")

- **Approval-policy master (per company), CRUD + deactivate.** A user with `APPROVALS.POLICY.MANAGE` creates a
  policy: `document_type` (a free string the consuming module owns — e.g. `PURCHASE_ORDER`, `PAYMENT_RUN`,
  `MANUAL_JOURNAL`), an **amount band** `[min, max)` (base currency), an optional **branch scope** (one branch,
  or company-wide / all branches), and an **active flag** + `MasterStatus`. Editing a policy does **not** affect
  in-flight requests (they snapshotted it — BR-APR-05). Per-company scope; `assertCanActIn` on every read/write
  (FR-APR-01, FR-APR-02).
- **Policy steps (ordered approval chain on a policy).** Each policy carries 1..N **ordered steps**; each step
  names an **approver role** (a reference to an IAM role code, e.g. `FINANCE_MANAGER`) and a **sequence**. Steps
  are unique per (policy, sequence) and dense from 1. v1 routes by **role** (any user holding that role, in
  scope — see step-routing below); named-individual approvers, approver groups, and quorum/N-of-M are deferred
  (FR-APR-03, BR-APR-03).
- **Submit a document for approval (the integration hook — idempotent).** A consuming module calls
  `submitForApproval(documentType, documentUid, amount, branchUid, submittedByUserId, summary)`. The engine:
  (a) matches the **most-specific active policy** for (documentType, amount, branch) — branch-scoped beats
  company-wide; within equal specificity, the policy whose band contains the amount (BR-APR-01); (b) if a policy
  matches, creates an **approval request** PENDING with a **frozen copy** of the matched policy's steps
  (request steps), allocates `APPROVAL-####`, and emits **`APPROVAL.SUBMITTED`**; (c) if **no** policy matches,
  creates a terminal **APPROVED** request with `auto_approved = true` and no steps (no human sign-off needed —
  OQ-APR-01 default), and emits **`APPROVAL.RESOLVED`**. **Idempotent**: re-submitting the same (documentType,
  documentUid) returns the **existing** request, never a duplicate (BR-APR-08) (FR-APR-04, FR-APR-05).
- **Query a document's approval state (the integration hook).** `getApprovalState(documentType, documentUid)`
  returns the request DTO (status + steps + decisions) or empty if never submitted. This is the synchronous gate
  a consuming module's finalise transition calls ("is this document APPROVED?"). Scope-checked (FR-APR-06).
- **Decide on the current open step (approve / reject).** A user holding the current open step's **approver role**
  (and in the request's company/branch scope) APPROVEs or REJECTs the step, with an optional comment. APPROVE
  closes the step and **advances** to the next step (or resolves the whole request APPROVED if it was the last);
  REJECT resolves the **whole request** REJECTED (one rejection kills the chain — OQ-APR-04 default). The decision
  is **append-only** and audited. **Segregation of duties:** the submitter may **not** approve their own request
  (BR-APR-06, OQ-APR-03 default = enforce) (FR-APR-07, FR-APR-08, FR-APR-09).
- **Recall a pending request (submitter withdraws).** The submitter (or `APPROVALS.ADMIN`) recalls a still-PENDING
  request; it goes terminal RECALLED; the document is un-approved. Cannot recall a resolved request (FR-APR-10).
- **Resolution event (outbox).** When a request reaches a terminal state, the engine emits **`APPROVAL.RESOLVED`**
  (payload: documentType, documentUid, requestUid, final status, company/branch) over the transactional outbox, so
  a consuming module can react **asynchronously** (e.g. auto-advance a PO to ORDERED on APPROVED) **in addition to**
  the synchronous `getApprovalState` poll. At-least-once + consumer idempotency (the ADR-0009 contract) (FR-APR-11).
- **My-approvals inbox (read).** A user with `APPROVALS.DECIDE` reads the list of requests **currently awaiting
  their decision** — PENDING requests whose current open step's role the user holds, in their scope. The work-queue
  that makes the engine usable (FR-APR-12).
- **List / view requests + policies (read).** Paginated, scoped, filterable by document type / status / branch.
  `APPROVALS.REQUEST.VIEW` (requests, incl. the submitter's own), `APPROVALS.POLICY.VIEW` (policies) (FR-APR-13).
- **Permissions** — `APPROVALS.POLICY.VIEW` / `APPROVALS.POLICY.MANAGE` (the policy master), `APPROVALS.REQUEST.VIEW`
  (read requests), `APPROVALS.DECIDE` (approve/reject + the inbox), `APPROVALS.ADMIN` (recall any / cancel /
  override). **Submit is NOT separately permissioned** — it is invoked **by the consuming module's service** as a
  consequence of an act already gated by that module's own permission (raising a PO is gated by the PO permission;
  the PO service calls `submitForApproval` internally). Per-company scope; `assertCanActIn` on every read path;
  audit on submit / decide / recall / cancel (NFR-APR-03, FR-APR-14).
- **Migration footprint (V20–V22, additive).** Four new tables (`approval_policies`, `approval_policy_steps`,
  `approval_requests`, `approval_request_steps`) + one append-only decisions table (`approval_decisions`); the
  five permissions + `ORG_ADMIN` grant; a new `ScopeGuard` target type; two new `DomainEventType` constants; a new
  `code_sequence` kind (`APPROVAL`, lazy). **No GL, no `gl_config` key, no CoA account.** V1–V19 frozen.

### Deferred (recognised, NOT built in v1 — separate later increments)

- **Named-individual approvers + approver groups.** v1 routes a step to a **role** (any holder, in scope). Routing
  to a specific named user, or to an approver **group** with its own membership, is deferred (OQ-APR-05).
- **Quorum / N-of-M / parallel steps.** v1 is **single-approver-per-step, sequential** (one APPROVE resolves a
  step; steps run in order). Quorum (2-of-3 must approve), parallel steps (two steps that can be decided in any
  order), and conditional branching are deferred (OQ-APR-04 covers the reject semantics for v1).
- **Delegation / deputy / out-of-office.** "While I am away, my approvals go to X" (`approval_delegations`, the
  PATH-TO-FULL-ERP §3.4 item "Approval delegation (deputy/proxy)") is deferred — an IAM-depth add, additive on this
  engine.
- **Escalation / SLA / reminders.** Auto-escalate to the next approver after N hours, reminder notifications, SLA
  tracking — deferred; depends on Notifications (X.2) and a general scheduler (PATH-TO-FULL-ERP §3.12).
- **In-app / email notification of pending approvals.** v1 exposes a **pull** inbox (`APPROVALS.DECIDE` list). Push
  notification ("you have an approval waiting") is deferred to Notifications (X.2) — it will subscribe to
  `APPROVAL.SUBMITTED` / step-advance events, additive.
- **Multi-currency thresholds.** v1 thresholds are **base currency** only (Money base-only, ADR-0005). FX-aware
  thresholds ride the deferred multicurrency framework (X.6).
- **Conditions beyond amount + branch.** v1 matches on `document_type` + amount band + branch. Richer matching
  (by customer/supplier category, by product, by GL account, by submitter role) is deferred — the policy model
  does not preclude adding condition columns later (OQ-APR-02).
- **Re-submission / amend-and-resubmit workflow.** v1: a rejected/recalled document is the consuming module's
  problem — it amends its document and **submits afresh** (a new request). A first-class "revise this request"
  loop is deferred.
- **Approval analytics / dashboards.** Cycle time, approval rates, bottleneck analysis — Reporting depth (T2.3),
  reading the request/decision tables.
- **The consuming-module wiring itself.** PO-approval, payment-run approval, journal approval, stock-write-off
  approval, production-order-release approval — **each is its own increment** in its own module; this slice ships
  only the engine + the contract they call (§"Explicitly NOT this module").

### Explicitly NOT this module

- **Any consuming document.** This engine never owns or imports a `PurchaseOrder`, `PurchaseRequisition`,
  `ApPayment`, `JournalEntry`, `StockMovement`, or `ProductionOrder`. It stores a `document_type` string + a
  `document_uid` + an `amount` + a `branch` — opaque references. **The PO module owns the PO and its ORDERED gate;**
  this engine owns only the verdict the PO module reads. Wiring the PO (or any consumer) to call `submitForApproval`
  and gate on `getApprovalState` is **that module's increment**, not this one (it is a one-method touch on the
  consumer's finalise path + the new dependency edge — designed to the contract in ADR-0022, built by Procurement).
- **The General Ledger / any posting.** The engine **posts nothing**. It does not touch GL, has no `gl_config`
  key, adds no CoA account, moves no money. It **gates** transactions that other modules post; it does not post.
- **RBAC itself.** IAM (ADR-0001/0002) owns roles, permissions, `user_branch`, the `PermissionResolver`. This
  engine **reads** role membership (to find who may decide a step) via the existing resolver / a role-membership
  query; it does not define roles or change the RBAC model. A "step approver role" is a **reference to an existing
  IAM role code**, not a new role concept.
- **Notifications.** v1 is pull (the inbox). Push (email/in-app "you have an approval") is Notifications (X.2),
  deferred — it subscribes to this engine's events.
- **Workflow beyond approval.** This is an **approval** engine (a yes/no governance gate with a chain), not a
  general business-process / BPMN workflow engine (state machines, forms, scripted transitions). General workflow
  is explicitly out of scope and not planned as part of this slice.

## 3. The model: policies, the match, requests + frozen steps, decisions, the lifecycle, and the integration hook

### 3.1 Approval policy (the configured rule — a per-company master)

A policy answers: *for documents of this type, in this amount band, (optionally) in this branch — what chain of
approval is required?* A policy is a master: created/edited/deactivated, soft-deleted (`MasterStatus`), audited.
Its parts:

- **`document_type`** — a free string the consuming module owns (`PURCHASE_ORDER`, `PAYMENT_RUN`, …). The engine
  does not validate it against an enum (it is document-agnostic) — it is a routing key. A consuming module is
  expected to use a single stable constant for its document type.
- **amount band** `[min_amount, max_amount)` — base currency. `min` inclusive, `max` exclusive (so adjacent bands
  tile without overlap or gap: `[0, 1M)`, `[1M, 100M)`, `[100M, ∞)`). `max` null = unbounded (the top band).
- **branch scope** — either **a specific branch** (the policy applies only to documents originating in that
  branch) or **company-wide** (null branch — applies to all branches). A branch-scoped policy is **more specific**
  and wins over a company-wide one for the same (type, amount) (BR-APR-01).
- **active flag** — only active policies match; deactivating a policy stops it matching **new** submissions
  (in-flight requests are unaffected — they snapshotted it).

**Policy-match precedence (BR-APR-01) — deterministic, no ambiguity:** for a submitted (documentType, amount,
branch), among **active** policies of that document type whose band contains the amount:
1. a policy scoped to **exactly that branch** beats a **company-wide** policy;
2. (within the same branch-specificity) bands do not overlap by construction (validated at policy save — see
   BR-APR-02), so at most one band contains the amount;
3. if still none, **no policy matches → auto-approve** (OQ-APR-01 default).
This is a total function: exactly zero or one policy matches a given submission.

### 3.2 Policy steps (the ordered approval chain)

A policy carries 1..N **steps**, each `{ sequence, approver_role_code }`, sequences dense from 1, unique per
policy. A step's `approver_role_code` references an **existing IAM role** (e.g. `FINANCE_MANAGER`). v1 routing:
*any user who holds that role in the request's company (and, for a branch-scoped request, has access to that
branch via `user_branch`)* may decide the step (§3.5 step-routing). Steps are decided **in sequence** — step 2
opens only when step 1 is APPROVED.

### 3.3 Approval request + request steps (the runtime instance — a frozen snapshot)

When a module submits, the engine creates an **approval request** that **pins everything it needs to be
self-contained**, so a later policy edit cannot mutate an in-flight governance instance:
- the **document reference**: `document_type`, `document_uid`, `amount`, `branch`, an optional human `summary`;
- the **matched policy** (by uid, for trace) — but the steps are **copied** onto the request as **request steps**
  (a frozen snapshot of the policy's steps at submit time — BR-APR-05). If an admin later edits the policy
  (adds a step, changes a role), **this request is unchanged**; only new submissions see the new policy.
- a **status** (PENDING → terminal), the submitter, timestamps, `APPROVAL-####`.

Each **request step** carries its `sequence`, its (snapshotted) `approver_role_code`, its own status (PENDING →
APPROVED / REJECTED), and (when decided) a pointer to the resolving decision. **The current open step** is the
lowest-sequence PENDING step; that is the only step on which a decision may be made.

### 3.4 Approval decision (append-only)

Each act by an approver on a request step is an **append-only** `approval_decision` row: the step, the actor,
the action (APPROVE / REJECT), the timestamp, an optional comment. v1 is single-approver-per-step — one APPROVE
row closes the step APPROVED and advances; one REJECT row closes the step REJECTED and the whole request REJECTED.
Decisions are **never edited or deleted** (the append-only posture); a mistaken approval is corrected by the
consuming module raising a fresh document (not by mutating the decision).

### 3.5 Step routing (who may decide the current open step)

The current open step names an `approver_role_code`. A user may decide it iff **all** of: (a) they hold that role
(via the existing `PermissionResolver` / role-membership — IAM); (b) they may act in the request's company
(`ScopeGuard.assertCanActIn`); (c) for a branch-scoped request, they have access to that branch (`user_branch` —
IAM); and (d) **they are not the submitter** (segregation of duties, BR-APR-06 / OQ-APR-03 default). The engine
holds `APPROVALS.DECIDE` as the coarse RBAC gate; the **specific role** for the open step is the fine routing
check on top. (So `APPROVALS.DECIDE` says "this user is an approver in general"; the step's `approver_role_code`
says "this user is the *right* approver for *this* step".)

### 3.6 The integration hook (the contract every consumer calls)

Three touch-points, all DTO-only:
1. **`submitForApproval(documentType, documentUid, amount, branchUid, submittedByUserId, summary) → ApprovalRequestDto`**
   — idempotent per (documentType, documentUid); returns the PENDING request, or the terminal auto-approved
   request when no policy matched. The consumer calls this **in its own finalise/submit transaction** (e.g. the
   PO service calls it when the PO is sent for approval) — synchronous, same TX as the consumer's state change.
2. **`getApprovalState(documentType, documentUid) → Optional<ApprovalRequestDto>`** — the synchronous gate the
   consumer's finalise transition reads ("only let the PO go ORDERED if state is APPROVED"). Returns the request
   + its status. Empty = never submitted.
3. **`APPROVAL.RESOLVED` outbox event** — emitted when a request goes terminal; lets the consumer react
   **asynchronously** (auto-advance the document) as an *alternative* to polling. (`APPROVAL.SUBMITTED` is also
   emitted at submit, for Notifications later.)

The consuming module **gates itself** — the engine never reaches into the consumer to flip the PO to ORDERED;
the PO module either polls `getApprovalState` at its ORDERED transition or subscribes to `APPROVAL.RESOLVED` and
advances itself. Both directions of dependency point **into** the engine; the engine depends on no consumer.

## 4. Actors

- **Policy administrator** (`APPROVALS.POLICY.MANAGE`) — configures approval policies + their step chains for the
  company (typically an ORG_ADMIN or a finance/controlling lead). Decides *what needs approval and by whom*.
- **Approver** (`APPROVALS.DECIDE`, holding the step's role) — the person who signs off (or rejects) a request
  step: a branch manager, a purchasing manager, a finance manager, a CFO. Sees their inbox; decides.
- **Submitter** (no approvals permission needed — they are acting in a consuming module) — the user who raised the
  underlying document (the buyer who created the PO). Their act triggers `submitForApproval` via the consuming
  module's service. May **recall** their own pending request.
- **Approvals admin** (`APPROVALS.ADMIN`) — can recall/cancel any request, override a stuck chain; the escape
  hatch for operational issues. Sparingly granted.
- **Consuming module (system actor)** — the PO/payment/journal service that calls `submitForApproval` and gates on
  `getApprovalState`. Not a human; the integration client.

## 5. Functional requirements

> `FR-APR-NN`. Each is v1 unless marked. "The engine" = `com.erp.modules.approvals`.

- **FR-APR-01** — A user with `APPROVALS.POLICY.MANAGE` can **create an approval policy** for their company:
  document type, amount band `[min, max)`, optional branch scope, 1..N ordered steps (each a sequence + an IAM
  approver-role code), active flag. Scope-checked + audited.
- **FR-APR-02** — A policy administrator can **edit** (band, branch, steps, active) and **deactivate** a policy.
  Edits affect **only future** submissions — never in-flight requests (BR-APR-05). Soft-delete via `MasterStatus`.
- **FR-APR-03** — A policy carries an **ordered step chain** (1..N), each naming an **approver role**; sequences
  dense from 1, unique per policy; at least one step on an active policy (BR-APR-03).
- **FR-APR-04** — A consuming module can **submit a document for approval** via `submitForApproval(documentType,
  documentUid, amount, branchUid, submittedByUserId, summary)`. The engine matches the most-specific active policy
  (BR-APR-01) and, on a match, creates a **PENDING approval request** with a **frozen snapshot of the policy's
  steps**, allocates `APPROVAL-####`, and emits `APPROVAL.SUBMITTED`.
- **FR-APR-05** — When **no active policy matches** a submission, the engine creates a **terminal APPROVED request**
  with `auto_approved = true` and **no steps**, and emits `APPROVAL.RESOLVED` (OQ-APR-01 default; the consumer may
  finalise immediately).
- **FR-APR-06** — A consuming module can **query a document's approval state** via `getApprovalState(documentType,
  documentUid)`, returning the request DTO (status + steps + decisions) or empty. The synchronous gate.
- **FR-APR-07** — An **approver** holding the **current open step's role** (in scope, not the submitter) can
  **APPROVE** the step, optionally with a comment; APPROVE advances to the next step, or resolves the whole request
  **APPROVED** if it was the last step (then emits `APPROVAL.RESOLVED`). Append-only + audited.
- **FR-APR-08** — An approver can **REJECT** the current open step, optionally with a comment; REJECT resolves the
  **whole request REJECTED** immediately (one rejection kills the chain — OQ-APR-04 default), and emits
  `APPROVAL.RESOLVED`. Append-only + audited.
- **FR-APR-09** — The engine **enforces segregation of duties**: the **submitter may not decide** their own
  request's steps (BR-APR-06, OQ-APR-03 default = enforce).
- **FR-APR-10** — The **submitter** (or `APPROVALS.ADMIN`) can **recall** a still-PENDING request → terminal
  RECALLED; emits `APPROVAL.RESOLVED`. A resolved request cannot be recalled.
- **FR-APR-11** — On reaching any terminal state, the engine emits **`APPROVAL.RESOLVED`** over the transactional
  outbox (documentType, documentUid, requestUid, final status, company/branch) for asynchronous consumer reaction;
  at-least-once + consumer idempotency.
- **FR-APR-12** — A user with `APPROVALS.DECIDE` can read their **approvals inbox**: PENDING requests whose current
  open step's role they hold, in their scope. Paginated, scoped.
- **FR-APR-13** — Users can **list/view** approval requests (`APPROVALS.REQUEST.VIEW`; a submitter always sees
  their own) and policies (`APPROVALS.POLICY.VIEW`), paginated, scoped, filterable (type/status/branch).
- **FR-APR-14** — Every act (policy create/edit/deactivate, submit, decide, recall, admin cancel) is **audited**
  (ADR-0004) with actor, timestamp, and the affected request/policy uid.
- **FR-APR-15** *(admin)* — `APPROVALS.ADMIN` can **cancel** any non-terminal request (an operational escape hatch
  distinct from recall) → terminal CANCELLED; emits `APPROVAL.RESOLVED`. Audited.

## 6. Business rules

> `BR-APR-NN`. The invariants the engine enforces (service + DB-CHECK where a CHECK can see it).

- **BR-APR-01** — **Policy match is deterministic.** For a submission, among active policies of that document type
  whose band contains the amount: a branch-scoped policy beats a company-wide one; bands do not overlap (BR-APR-02);
  exactly zero or one policy matches. Zero → auto-approve (BR-APR-09).
- **BR-APR-02** — **No overlapping bands** for the same (document_type, branch-scope) on a company: the policy save
  rejects a new/edited band that overlaps an existing active band for the same type + branch-scope (a half-open
  `[min, max)` overlap check). Adjacent bands (one's max == the next's min) are allowed (they tile).
- **BR-APR-03** — **An active policy has ≥ 1 step**; steps are sequenced dense from 1, unique per policy; each step
  names a non-blank IAM role code.
- **BR-APR-04** — **Steps are decided in order.** A decision is allowed **only** on the current open step (the
  lowest-sequence PENDING request step). A later step cannot be decided while an earlier is PENDING.
- **BR-APR-05** — **A request is a frozen snapshot.** Its steps are copied from the matched policy at submit time;
  editing/deactivating the policy afterward does **not** change the request. Trace to the source policy by uid.
- **BR-APR-06** — **Segregation of duties:** the submitter may not approve or reject their own request (OQ-APR-03
  default = enforce; the owner may relax for single-operator companies).
- **BR-APR-07** — **Terminal is immutable.** A request in APPROVED / REJECTED / RECALLED / CANCELLED accepts no
  further decisions, recalls, or edits. Corrections = a new submission of an amended document.
- **BR-APR-08** — **Submit is idempotent** per (document_type, document_uid): a second submit for the same document
  returns the **existing** request, never a duplicate (a `UNIQUE (company_id, document_type, document_uid)`
  backstop on `approval_requests` enforces it at the DB).
- **BR-APR-09** — **Auto-approve when no policy matches** (OQ-APR-01 default): the request is created terminal
  APPROVED, `auto_approved = true`, no steps. The consumer treats it identically to a human-approved request.
- **BR-APR-10** — **The amount is the submitter's amount.** The engine matches on the amount the consumer passes; it
  does **not** recompute or validate it against any document (it cannot — it is document-agnostic). The consumer is
  responsible for passing the correct, final amount at submit time. (If the consumer's document amount later changes,
  the consumer must recall + re-submit — BR-APR-07.)
- **BR-APR-11** — **The engine posts nothing.** No GL entry, no stock movement, no payment. It only mutates its own
  request/step/decision tables and emits events. (A consuming module may *react* to `APPROVAL.RESOLVED` and post —
  but that posting belongs to the consumer, not the engine.)
- **BR-APR-12** — **Scope:** every policy, request, step, decision carries `company_id`; a request also carries the
  `branch_id` it originated in. Every read path calls `assertCanActIn`; a decision additionally checks branch
  access for a branch-scoped request.

## 7. Key flows

### 7.1 Happy path — high-value PO needs two approvals, gets them, goes ORDERED

1. Admin has configured policy: `PURCHASE_ORDER`, band `[1,000,000, 100,000,000)`, company-wide, Step 1
   `PURCHASING_MANAGER`, Step 2 `FINANCE_MANAGER`.
2. A buyer creates a PO for 12,000,000 and sends it for approval. **The PO module's service** calls
   `submitForApproval("PURCHASE_ORDER", poUid, 12_000_000, branchUid, buyerId, "PO to Acme")` inside its own TX.
3. The engine matches the policy, creates request `APPROVAL-0007` PENDING with two frozen steps (Step 1 PENDING,
   Step 2 PENDING), emits `APPROVAL.SUBMITTED`. The PO is held (the PO module keeps it in a PENDING_APPROVAL state
   — *the PO module's* state, not the engine's).
4. The purchasing manager opens their **inbox** (`APPROVALS.DECIDE`), sees `APPROVAL-0007`, **APPROVEs** Step 1 with
   a comment. Step 1 → APPROVED; Step 2 becomes the current open step. (Not the submitter — SoD holds.)
5. The finance manager sees it in their inbox, **APPROVEs** Step 2. It was the last step → the **request resolves
   APPROVED**; the engine emits `APPROVAL.RESOLVED`.
6. The PO module either (a) polls `getApprovalState("PURCHASE_ORDER", poUid)` at its ORDERED transition and sees
   APPROVED, or (b) had subscribed to `APPROVAL.RESOLVED` and auto-advances the PO to ORDERED. Either way the PO is
   now ORDERED. **Every approval is on the append-only decisions log, audited.**

### 7.2 Happy path — small PO below any threshold, auto-approved

1. A buyer creates a PO for 200,000 (below the lowest band's 1,000,000) and submits it.
2. The engine finds **no matching policy** → creates request terminal **APPROVED**, `auto_approved = true`, no steps;
   emits `APPROVAL.RESOLVED`.
3. The PO module reads APPROVED and proceeds to ORDERED immediately — **adding the engine did not block the
   small-PO flow** (OQ-APR-01 default). No human was involved; the audit shows an auto-approval.

### 7.3 Unhappy path — rejection kills the chain

1. PO for 12,000,000 submitted; request PENDING with two steps.
2. The purchasing manager **REJECTs** Step 1 with comment "wrong supplier". Step 1 → REJECTED; the **whole request
   resolves REJECTED**; `APPROVAL.RESOLVED` emitted; Step 2 is never opened (OQ-APR-04 default).
3. The PO module reads REJECTED, keeps the PO out of ORDERED (e.g. moves it to a REJECTED/DRAFT state of its own).
   The buyer amends the PO and **submits a fresh document** (a new request) — the rejected request is immutable.

### 7.4 Unhappy path — submitter tries to approve their own request (SoD)

1. The buyer who submitted `APPROVAL-0007` happens to also hold `PURCHASING_MANAGER` and tries to APPROVE Step 1.
2. The engine **refuses** (BR-APR-06 / SoD): the submitter may not decide their own request → 409/422 "you cannot
   approve a request you submitted". Another holder of the role must decide.

### 7.5 Unhappy path — recall, and the idempotent re-submit

1. The buyer realises the PO is wrong and **recalls** the still-PENDING request → RECALLED; `APPROVAL.RESOLVED`.
2. The PO module reads RECALLED, returns the PO to DRAFT.
3. The buyer fixes the PO and re-submits. Because the **document_uid is the same**, the idempotency backstop
   (BR-APR-08) would return the existing (RECALLED, terminal) request — so the consumer must submit the **amended
   document under a new document_uid** (or the engine, on a re-submit of a terminal request, creates a **new**
   request superseding it — OQ-APR-06 flags this exact semantics; the assumed default: **a terminal request blocks
   re-submit of the same uid; the consumer raises a new document** — clean and unambiguous).

### 7.6 Edge — policy edited mid-flight

1. Request `APPROVAL-0007` is PENDING at Step 1 (snapshotted: Step 1 PM, Step 2 FM).
2. An admin edits the policy to add a third step (CFO). **`APPROVAL-0007` is unchanged** (BR-APR-05) — it still has
   two steps. The new third step applies only to **new** submissions. (This is why the request snapshots its steps.)

## 8. Non-functional requirements

- **NFR-APR-01 — Document-agnostic / zero consumer coupling.** The engine imports **no** consumer module's entity,
  repository, or service. It stores opaque `document_type` + `document_uid` references. ArchUnit forbids
  `approvals → {purchases, ap, gl, stock, sales, …consumer modules}` (no outbound edge to a consumer). The
  dependency points **into** the engine only.
- **NFR-APR-02 — Idempotent submit + at-least-once events.** Submit is idempotent per (type, uid) (DB UNIQUE
  backstop). The `APPROVAL.*` events follow the ADR-0009 at-least-once + consumer-idempotency contract; a consumer
  reacting to `APPROVAL.RESOLVED` uses `IdempotencyGuard`.
- **NFR-APR-03 — Scope + RBAC + audit on every path.** Every read path calls `assertCanActIn`; every write is
  `@perm`-gated (`@perm.has` / `@perm.scoped` on the request/policy uid) and audited. Submit rides the consumer's
  own permission (not separately gated). Branch-scoped decisions additionally check `user_branch` access.
- **NFR-APR-04 — Append-only governance trail.** Decisions are never updated/deleted; a request's terminal state is
  immutable. The decisions table is append-only (the ADR-0004 / §3.6 posture).
- **NFR-APR-05 — Concurrency.** Two approvers racing on the same open step: the request header carries `@Version`;
  the step transition (APPROVE/REJECT) is guarded so exactly one decision resolves the step (optimistic lock, the
  shipped pattern; one retry then a clean "already decided" error). No double-advance.
- **NFR-APR-06 — Performance.** The inbox query (PENDING requests for the caller's roles, scoped) and the
  `getApprovalState` lookup are indexed: `(company_id, status)`, `(company_id, document_type, document_uid)` UNIQUE,
  and the current-open-step lookup `(approval_request_id, status, sequence)`. Paginated lists everywhere.
- **NFR-APR-07 — Additive migration.** V20–V22 additive on frozen V1–V19; no edit to a shipped migration; #12-safe
  (no per-company CROSS-JOIN seed-uids — the only seeds are uid-less permission grants; the `APPROVAL` numbering
  kind is lazy, no seed row).
- **NFR-APR-08 — Extensibility without rework.** The deferred items (named approvers, quorum, delegation,
  escalation, richer conditions) are **additive** on this model — a `condition` column, an `approver_user_id`
  alongside `approver_role_code`, a `quorum` column on the step, a delegation table. None requires reshaping the
  v1 tables. The model is designed so the v1 row shape survives.

## 9. User stories

- **US-APR-01** — As a **policy administrator**, I configure that purchase orders over 1,000,000 need my purchasing
  manager's sign-off and over 100,000,000 also need the CFO's, so high-value spend is controlled by amount.
- **US-APR-02** — As a **buyer**, when I send a high-value PO, it goes for approval automatically and I can see who
  it is waiting on; small POs go straight through without bothering anyone.
- **US-APR-03** — As an **approver**, I open my inbox and see exactly the requests waiting for *my* decision, with
  the document summary and amount, and I approve or reject each with a comment.
- **US-APR-04** — As a **finance manager**, I am the second sign-off on large POs; I only see a request after the
  purchasing manager has approved it first (steps in order).
- **US-APR-05** — As an **auditor**, I can see, for any document, the full approval history: who submitted it, every
  approver, every decision, the comments, and the final verdict — append-only, tamper-evident.
- **US-APR-06** — As a **buyer**, I cannot approve my own PO even though I hold the purchasing-manager role
  (segregation of duties).
- **US-APR-07** — As a **buyer**, I can recall a PO I sent for approval before anyone has acted, fix it, and re-raise.
- **US-APR-08** — As a **developer wiring a new module** (payments, journals), I call one method to submit a document
  and one method to read its verdict; the engine knows nothing about my module.

## 10. Accepted boundary (what v1 deliberately does NOT do)

- **No consumer wiring.** The PO/payment/journal/stock-write-off/production-order consumers are each their own
  increment; v1 ships the engine + the contract. (Procurement's PO-approval increment is the first to consume it.)
- **No GL / no posting.** The engine gates; it never posts. No `gl_config` key, no CoA account, no journal.
- **No named approvers / groups / quorum / delegation / escalation / push notifications.** v1 = role-routed,
  single-approver-per-step, sequential, pull-inbox. All richer routing is deferred (§2), additive on this model.
- **No multi-currency thresholds.** Base currency only.
- **No conditions beyond amount + branch.** Richer matching deferred (OQ-APR-02), additive.
- **No general workflow / BPMN.** Approval gating only, not arbitrary business-process orchestration.

## 11. Open questions (architect owner-style defaults adopted; the load-bearing ones flagged for owner confirm)

> None blocks writing ADR-0022 — each has a chosen default the data model is built to. The **load-bearing** ones
> (★) the owner should confirm before the engine's first consumer (Procurement PO-approval) ships.

- **★ OQ-APR-01 — no-policy-match behaviour: auto-approve vs fail-closed (block).** *Default: auto-approve* (a
  document matching no policy needs no sign-off; adding the engine never blocks a flow with no policy configured).
  *Load-bearing* because the opposite (fail-closed) means **every** document of a type with **any** policy is
  blocked until a policy exists for its band — a very different operational posture. Owner confirm. (A middle option
  — auto-approve only if the document type has **no** policy at all, but fail-closed if it has policies but none
  matches the band — is a third stance worth the owner's view.)
- **★ OQ-APR-03 — segregation of duties: enforce (submitter may not approve own) vs allow.** *Default: enforce.*
  Load-bearing for governance credibility; but a single-operator company may need to relax it (the submitter *is*
  the only approver). Owner confirm; recommend a per-company config flag if both postures are needed.
- **★ OQ-APR-04 — reject semantics: one reject kills the whole chain vs reject-sends-back-a-step.** *Default: one
  reject resolves the whole request REJECTED.* The alternative (a reject bounces back to the previous step / the
  submitter to revise) is a richer loop (deferred §2). Owner confirm the simple-kill default is acceptable for v1.
- **OQ-APR-02 — match conditions beyond amount + branch.** *Default: amount band + branch only* in v1. Richer
  conditions (supplier/customer category, GL account, submitter role) deferred; the policy model leaves room
  (additive condition columns). Owner confirm no other condition is needed for the PO-approval first consumer.
- **OQ-APR-05 — step routing: role vs named individual vs group.** *Default: role* (any holder in scope). Named
  individuals + groups deferred (§2). Owner confirm role-routing suffices for v1.
- **OQ-APR-06 — re-submit of a terminal request's document uid.** *Default: blocked — the consumer raises a new
  document (new uid) to re-seek approval* (clean, unambiguous; matches the append-only posture). The alternative
  (a re-submit supersedes the terminal request with a new chain under the same uid) is more convenient for consumers
  but muddies the idempotency invariant (BR-APR-08). Owner / architect confirm at consumer-wiring time.
- **OQ-APR-07 — does the engine expose a "current approver candidates" read?** *Default: yes, implicitly via the
  inbox* (a user sees what is waiting on them); an explicit "who can approve step N" admin read is deferred (a
  Reporting/admin nicety). No blocker.
- **OQ-APR-08 — should `submitForApproval` be synchronous-only, or also offer an event-driven submit?** *Default:
  synchronous service call only* (the consumer calls it in its own TX; it is a query-shaped command, not a
  cross-module side effect that needs the outbox). The **resolution** is event-driven (`APPROVAL.RESOLVED`), the
  **submission** is a direct call. Confirmed by the architect; flagged so a future consumer does not reinvent it.

---

*This document is the business-level requirements for the Approvals Workflow Engine. The technical data model —
tables, columns, constraints, enums, the service contract, events, ScopeGuard case, permissions, the V20–V22
migration, ArchUnit edges — is **ADR-0022** (the architect's next step). Owner: confirm the three ★ load-bearing
OQs before the first consumer (Procurement PO-approval) ships; the others have safe defaults the model is built to.*
