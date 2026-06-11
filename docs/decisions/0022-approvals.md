# 0022 — Approvals Workflow Engine data model: a generic, document-agnostic governance spine in a NEW `com.erp.modules.approvals` module — amount-threshold + branch-scoped + multi-step approval **policies** (a per-company master with an ordered step chain of IAM **approver roles**), runtime **approval requests** that freeze a snapshot of the matched policy's steps at submit time, append-only **decisions**, a deterministic policy-match (branch-scoped beats company-wide; non-overlapping half-open `[min,max)` bands; no match → auto-approve), the **integration hook** every other module calls (`submitForApproval` idempotent per (type,uid) + `getApprovalState` synchronous gate) plus an `APPROVAL.RESOLVED` outbox event for asynchronous consumers — posting **NOTHING** to the books (it gates, it does not transact), all on the existing IAM / RBAC / ScopeGuard / outbox / code_sequence / MasterStatus / audit spine, additive as `V20–V22` on the frozen V1–V19

- **Status:** Proposed (architect owner-style defaults; the three ★ load-bearing OQs in approvals.md §11 to be owner-confirmed before the first consumer — Procurement PO-approval — ships; none blocks this build)
- **Date:** 2026-06-11
- **Deciders:** solutions-architect. This is a **cross-cutting platform enabler** (PATH-TO-FULL-ERP §3.12 X.5, §4 critical-dependency #7, §3.4 the first consumer). The eight design seams — the **module placement** (D-1), the **policy match function** (D-3), the **frozen-snapshot request** (D-5), the **step lifecycle + reject semantics** (D-6), the **integration hook contract** (D-7), the **step routing / SoD** (D-8), the **no-posting / no-GL stance** (D-9), and the **no-consumer-coupling ArchUnit edge** (D-12) — are the **decisions this ADR makes**; the *behaviour* is fixed by the requirements.
- **Context source:** [docs/requirements/approvals.md](../requirements/approvals.md) (architect-assumed owner-style defaults 2026-06-11 — FR-APR-01..15, BR-APR-01..12, NFR-APR-01..08, US-APR-01..08, §7 flows, §10 accepted boundary, §11 OQ log; the ground truth for every rule below) + [USER-STORIES.md](../../USER-STORIES.md). Verified against the **shipped** platform:
  - **IAM / RBAC** ([ADR-0001](0001-iam-architecture.md) / [ADR-0002](0002-rbac-enforcement.md) / V1): `roles` (with `code` — e.g. `ORG_ADMIN`), `permissions(code, module, description)`, `role_permission(role_id, permission_id)`, `app_users`, `branches`, `user_branch` (many-branches + default-branch, ADR-0001); `PermissionResolver.hasPermission(principal, code, nowMs)`; `PermissionChecks` bean `@perm` with `has(code)` / `scoped(uid, targetType, code)`; `@PreAuthorize("@perm.has('…')")` / `@perm.scoped(#uid,'…','…')` — **NEVER `hasAuthority`** (the engine's gates use `@perm` exactly). The **step approver role** references an existing `roles.code` — no new role concept (D-8).
  - **ScopeGuard** ([ScopeGuard.java](../../backend/src/main/java/com/erp/platform/security/ScopeGuard.java)): the `companyIdOf(targetType, uid)` switch + `assertCanActIn(principal, companyId)` (REQUIRES_NEW root-bypass audit, ISSUES-REGISTER #11-safe for read-only query paths); this ADR adds **`case "approvalpolicy"` + `case "approvalrequest"`** (D-11) following the V18/ADR-0021 pattern (`quotations`/`salesOrders`/`deliveryRepo` injected, switch case added).
  - **Transactional outbox** ([ADR-0009](0009-transactional-outbox.md)): `OutboxPublisher.publish(eventType, aggregateType, aggregateId, aggregateUid, companyId, branchId, payload)` in the caller's TX; `DomainEventType` constants ([DomainEventType.java](../../backend/src/main/java/com/erp/platform/events/DomainEventType.java) — `SALE.FINALISED`/`DELIVERY.CONFIRMED`/… ; this ADR adds **`APPROVAL.SUBMITTED`** + **`APPROVAL.RESOLVED`** + aggregate **`APPROVAL_REQUEST`**); `DomainEventHandler.eventType()/handle()`; `IdempotencyGuard.alreadyProcessed(consumer, uid)`/`markProcessed`; `processed_events(consumer, event_uid)`. The engine is a pure **producer** of `APPROVAL.*` events (no GL consumer here); consumers of `APPROVAL.RESOLVED` live in the **consuming** modules, not here.
  - **`code_sequence` numbering** ([ADR-0007](0007-products-data-model.md) D-6; the shipped `CodeSequence` + `ProductCodeGenerator`, and `OrderToCashNumberGenerator` (V18) / `ApBillNumberGenerator` / `JournalBatchNumberGenerator` precedents): row-locked per-company `(company_id, entity_kind)` allocation. This ADR adds one new lazy `entity_kind` — **`APPROVAL`** (`APPROVAL-%04d`) — no seed row (created on first use, the shipped mechanism).
  - **MasterStatus** ([ADR-0006](0006-parties-data-model.md) / used by `chart_of_accounts.status`, `tax_rates.status`): the `status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE'` soft-delete column on a master. `approval_policies` carries it (D-2).
  - **Money** ([ADR-0005](0005-money-and-currency.md)): base currency only (TZS), `NUMERIC(19,4)`, HALF_UP. The threshold amount is base-currency `NUMERIC(19,4)`.
  - **Audit** ([ADR-0004](0004-iam-audit-trail.md)): `AuditService.record(...)`; append-only audit on every write. Decisions are themselves an append-only domain log (D-4) *in addition to* the audit trail.
  - **GL** ([ADR-0013](0013-general-ledger-data-model.md)): named **only to assert the engine touches none of it** — no `GlConfigKey`, no CoA account, no `GLPostingService` call (D-9).
  - [[db-naming-convention]] verified against V1–V19 (plural masters/owned-children `approval_policies`/`approval_policy_steps`/`approval_requests`/`approval_request_steps`/`approval_decisions`; singular constraint roots `uq_`/`fk_`/`chk_` on the singular entity; plural `ix_` indexes; `uid VARCHAR(26)` ULID; `company_id`/`branch_id` BIGINT scalar; additive `INSERT … ON CONFLICT DO NOTHING` perm seeds + `roles × permissions` CROSS JOIN grant). **ISSUES-REGISTER #12:** every per-company CROSS-JOIN seed-uid MUST be `'XX' || lpad(company_id::text,6,'0') || substr(md5(key),1,12)` (≤26 chars) — **never** `|| key`. **This ADR has NO per-company seed-uid inserts** (no per-company master rows seeded; policies are user-created, numbering is lazy) — the only seed is the uid-less permission grant, so **V20–V22 are #12-non-exposed**. **Latest shipped migration is `V19__sales_returns.sql` → Approvals is `V20`+ (additive; V1–V19 FROZEN). Next ADR after 0021 is 0022.**

This ADR is the **technical data model + integration design** for the Approvals Workflow Engine (PATH-TO-FULL-ERP §3.12 X.5 / §4 #7). It translates the assumed-ratified spec into: a NEW `com.erp.modules.approvals` module, five tables (`approval_policies` + `approval_policy_steps` master; `approval_requests` + `approval_request_steps` runtime; `approval_decisions` append-only log), four enums + their service-guarded transitions, the deterministic policy-match function, the frozen-snapshot request mechanism, the append-only decision flow with single-reject-kills-chain semantics, the **integration hook contract** (`submitForApproval` / `getApprovalState` + the `APPROVAL.RESOLVED` event) every other module calls, the step-routing + segregation-of-duties rule, the explicit **no-GL/no-posting** stance, the V20–V22 migration ordering with **#12-safe seeds**, the ArchUnit edges (the engine has **no** outbound edge to any consumer — the load-bearing reusability invariant), the perms, and the ScopeGuard cases. It is **concrete enough that the backend engineer writes the migration + the policy/request/step/decision model + the match + the hook + the events without guessing a business rule.** It writes **no production code, no entities, no migration SQL** — that is the engineer's next step. Nothing in the requirements is re-litigated.

## Context

Procurement depth (requisitions, RFQ, multi-step PO approval) is the next operational slice and it **cannot ship its governance story** without an approval engine; the platform plan made Approvals a gate (PATH-TO-FULL-ERP §4 #7) precisely so each high-value flow (PO, payment run, journal, stock write-off, production-order release) does **not** grow its own ad-hoc approval. The forces:

- **REUSABILITY IS THE WHOLE POINT (the top invariant — get it wrong and the engine becomes procurement-specific; NFR-APR-01).** The engine must be **document-agnostic**: it stores a `document_type` string + a `document_uid` + an `amount` + a `branch`, never a PO/Payment/Journal entity. The dependency must point **into** the engine (consumers depend on approvals; approvals depends on no consumer) so there is no cycle and the engine is reusable. The risk is the opposite edge sneaking in — an `import com.erp.modules.purchases.*` to "read the PO". Resolved in **D-1 / D-7 / D-12** — an ArchUnit rule forbidding any `approvals → <consumer>` edge.

- **The policy match must be deterministic (BR-APR-01/02).** A submission `(documentType, amount, branch)` must match **exactly zero or one** active policy. The forces: how branch-specificity is resolved (branch-scoped beats company-wide), how band overlap is prevented (a save-time non-overlap validation on half-open `[min,max)` bands), and what happens on no match (auto-approve — OQ-APR-01 default). Resolved in **D-3**.

- **An in-flight request must not change when its policy is edited (BR-APR-05).** A six-month-old governance instance must be reproducible; editing the policy (adding a step, changing a role) must not retroactively alter a PENDING request. The boring, proven answer is the **snapshot** discipline sales lines take of price: copy the matched policy's steps onto the request at submit time (`approval_request_steps`) and never read the policy again for that request. Resolved in **D-5**.

- **Steps are sequential, single-approver, and one reject kills the chain (BR-APR-04, OQ-APR-04 default).** The lifecycle is the genuinely new state machine. A decision is allowed only on the current open step (lowest-sequence PENDING); APPROVE advances or resolves; REJECT resolves the whole request REJECTED. The forces: where the request status is stored vs derived, the concurrency guard on the step transition (two approvers racing), and the terminal-immutability backstop. Resolved in **D-6**.

- **The engine posts NOTHING (BR-APR-11, D-9).** Unlike every prior financial increment, Approvals touches **no GL** — no `gl_config` key, no CoA account, no `GLPostingService`. It gates transactions other modules post; it does not post. This is a deliberate non-decision worth stating loudly so a future reader does not "wire it to GL". Resolved in **D-9**.

- **Step routing reuses RBAC without inventing a role concept (D-8).** A step names an **existing IAM role code**; any holder of that role, in scope, may decide (plus SoD: not the submitter). The engine reads role membership via the shipped `PermissionResolver` / a role-membership query — it does **not** add a role table or a new permission model. Resolved in **D-8**.

- **Schema freeze / direction.** IAM=V1 … Sales Returns=V19, all frozen. Approvals is a **new** module landing as additive **V20–V22**: five new tables, the five permissions + grant, two new `DomainEventType` constants, one new `ScopeGuard` target group, one new lazy `code_sequence` kind. It references only `companies`/`branches`/`app_users`/`roles` (frozen V1) for scope/audit/routing FKs. It posts to no module's tables; it emits events any module may consume.

## Decision

### D-1 — Module placement: a NEW `com.erp.modules.approvals` module; controllers flat in `com.erp.api`; the engine has NO outbound edge to any consumer

The engine lives in its **own new module** `com.erp.modules.approvals` (not under `platform`, not folded into a consumer). Rationale: it is a domain module with its own master + runtime tables + lifecycle + RBAC, exactly like the other `com.erp.modules.*` peers — `platform` is for the cross-cutting spine (security, outbox, audit, common), and the approvals engine *uses* that spine, it is not part of it. It is **not** under `purchases` because it is reusable by payments/journals/stock/manufacturing — the whole point. A flat peer is what `ModuleBoundaryTest` reasons about.

Internal layout:

```
com.erp.modules.approvals
├── domain.entity   ApprovalPolicy, ApprovalPolicyStep,
│                   ApprovalRequest, ApprovalRequestStep, ApprovalDecision
├── domain.dto      ApprovalPolicyDto / CreateApprovalPolicyRequest / UpdateApprovalPolicyRequest /
│                   ApprovalPolicyStepDto / PolicyStepInput,
│                   ApprovalRequestDto / ApprovalRequestStepDto / ApprovalDecisionDto,
│                   SubmitForApprovalRequest        (the hook input — D-7),
│                   DecideRequest                   (approve/reject + comment — D-6),
│                   ApprovalResolvedPayload         (NEW outbox payload — D-10),
│                   ApprovalSubmittedPayload        (NEW outbox payload — D-10)
├── domain.enums    PolicyBranchScope, ApprovalRequestStatus, ApprovalStepStatus, DecisionAction (D-2)
├── repository      ApprovalPolicyRepository, ApprovalPolicyStepRepository,
│                   ApprovalRequestRepository, ApprovalRequestStepRepository,
│                   ApprovalDecisionRepository
└── service         ApprovalPolicyService(+Impl)        — policy master CRUD + non-overlap validation (D-3)
                    ApprovalEngine(+Impl)               — THE PUBLIC HOOK: submitForApproval / getApprovalState (D-7)
                    ApprovalDecisionService(+Impl)      — decide / recall / cancel + the step lifecycle (D-6)
                    ApprovalPolicyMatcher               — the deterministic match function (D-3)
                    ApprovalInboxQuery                  — the "awaiting my decision" read (D-8 / FR-APR-12)
                    ApprovalNumberGenerator             — APPROVAL-#### via code_sequence (D-13)
                    StepApproverResolver                — "may this user decide this step's role, in scope" (D-8)
```

Controllers stay flat in `com.erp.api`: `ApprovalPolicyController`, `ApprovalRequestController` (decide/recall/cancel/view + the inbox). They touch only services (`ModuleBoundaryTest`). **`ApprovalEngine` is the public integration interface** other modules' services inject (the only inbound edge consumers take — D-7); it lives in `approvals.service` and is imported by consumer services exactly as `ap.service` imports `gl.service.GLPostingService` (the shipped cross-module-service-call stance).

**Boundary note (D-12):** the engine imports **no** consumer entity/repository/service. The `document_type` + `document_uid` it stores are **opaque scalar strings** — there is **no FK** from `approval_requests` into any consumer table (it cannot have one — the consumer is document-agnostic to the engine). Scope/routing FKs (`company_id`, `branch_id`, `submitted_by`, role lookups) reference only frozen V1 IAM tables.

### D-2 — Enums (the exact sets + transitions)

Four new enums in `approvals.domain.enums`. Every transition is **service-guarded, audited, append-only** (NFR-APR-03/04); status is **never free-set**.

**`PolicyBranchScope`** (D-3 match):
```
COMPANY_WIDE   — branch_id NULL on the policy; matches a submission from ANY branch
BRANCH         — branch_id set; matches ONLY a submission originating in that branch (more specific, wins)
```

**`ApprovalRequestStatus`** (FR-APR-04/05/07/08/10/15, BR-APR-07):
```
PENDING ──(all steps approved)──▶ APPROVED          (terminal)
   │     ──(any step rejected)───▶ REJECTED          (terminal)
   │     ──(submitter/admin recall)─▶ RECALLED        (terminal)
   │     ──(admin cancel)─────────▶ CANCELLED         (terminal)
   └─ created directly APPROVED when no policy matched (auto_approved = true, no steps — BR-APR-09)
```
Terminal set = {APPROVED, REJECTED, RECALLED, CANCELLED}; a terminal request accepts no further action (BR-APR-07). `chk_approval_request_status CHECK (status IN ('PENDING','APPROVED','REJECTED','RECALLED','CANCELLED'))`.

**`ApprovalStepStatus`** (FR-APR-07/08, BR-APR-04):
```
PENDING ──approve──▶ APPROVED   (step closed; the next-sequence step opens, or the request resolves APPROVED)
   │     ──reject───▶ REJECTED   (step closed; the whole request resolves REJECTED)
   └─ a step set to SKIPPED when the request resolves before it is reached (a reject kills later steps)
```
`chk_approval_request_step_status CHECK (status IN ('PENDING','APPROVED','REJECTED','SKIPPED'))`. **The current open step** = the lowest-`sequence` step with `status = 'PENDING'`; the only step a decision may target.

**`DecisionAction`** (FR-APR-07/08, append-only D-4):
```
APPROVE | REJECT
```
`chk_approval_decision_action CHECK (action IN ('APPROVE','REJECT'))`.

### D-3 — The policy master (`approval_policies` + `approval_policy_steps`) + the deterministic match (BR-APR-01/02)

All tables: plural names; `id` BIGINT IDENTITY PK; `uid` VARCHAR(26) ULID `uq_<root>_uid`; `company_id` BIGINT NOT NULL (tenant); standard audit cols (`created_at`/`created_by`/`updated_at`/`updated_by`, `*_by → app_users.id`, no FK — the shipped system-write pattern); `@Version` on the policy header. Money columns `NUMERIC(19,4)`.

#### `approval_policies` (master, per company)

| column | type | null | notes |
|---|---|---|---|
| `id` | BIGINT IDENTITY PK | NO | |
| `uid` | VARCHAR(26) | NO | `uq_approval_policy_uid`; URLs address by uid; `ScopeGuard case "approvalpolicy"` resolves on this (D-11) |
| `company_id` | BIGINT | NO | tenant; `fk_approval_policy_company` → `companies(id)`; never updated |
| `document_type` | VARCHAR(60) | NO | the consumer-owned routing key (`PURCHASE_ORDER`, `PAYMENT_RUN`, …); a free string, NOT an enum (document-agnostic) |
| `name` | VARCHAR(160) | NO | human label (e.g. "High-value PO approval") |
| `branch_scope` | VARCHAR(20) | NO | `PolicyBranchScope`; DEFAULT `'COMPANY_WIDE'`; `chk_approval_policy_branch_scope CHECK (branch_scope IN ('COMPANY_WIDE','BRANCH'))` |
| `branch_id` | BIGINT | YES | set iff `branch_scope = 'BRANCH'`; `fk_approval_policy_branch` → `branches(id)`; the branch this policy is scoped to (more specific) |
| `min_amount` | NUMERIC(19,4) | NO | inclusive lower bound, base currency; DEFAULT 0; `chk_approval_policy_min CHECK (min_amount >= 0)` |
| `max_amount` | NUMERIC(19,4) | YES | **exclusive** upper bound; NULL = unbounded (top band); `chk_approval_policy_band CHECK (max_amount IS NULL OR max_amount > min_amount)` |
| `currency` | VARCHAR(3) | NO | = base currency |
| `is_active` | BOOLEAN | NO | DEFAULT true; only active policies match (BR-APR-01); deactivating stops NEW matches (in-flight unaffected) |
| `status` | VARCHAR(32) | NO | `MasterStatus`; DEFAULT `'ACTIVE'`; the soft-delete lifecycle (`is_active` is the match gate, `status` the lifecycle — the `chart_of_accounts` precedent) |
| `notes` | VARCHAR(500) | YES | |
| `version` + audit cols | | | |

Constraints: `uq_approval_policy_uid UNIQUE (uid)`; `fk_approval_policy_company`/`_branch`; the two CHECKs above; `chk_approval_policy_branch_consistency CHECK ((branch_scope = 'COMPANY_WIDE' AND branch_id IS NULL) OR (branch_scope = 'BRANCH' AND branch_id IS NOT NULL))`.

Indexes:
```
CREATE INDEX ix_approval_policies_match ON approval_policies (company_id, document_type, is_active);  -- the match working set
CREATE INDEX ix_approval_policies_company ON approval_policies (company_id);
```

> **Band overlap is enforced at SAVE, not by a DB CHECK (BR-APR-02).** A no-overlap invariant over sibling rows (does this `[min,max)` overlap an existing active band for the same `company_id, document_type, branch_scope, branch_id`?) is a *cross-row* check a single-row CHECK cannot see — the same class as GL's `Σdebit==Σcredit` (ADR-0013 D-2). `ApprovalPolicyService.create/update` runs the half-open overlap query and rejects an overlap with a friendly error; the architect does **not** add a DB exclusion constraint in v1 (a Postgres `EXCLUDE USING gist` with a range type is the heavier option — flagged in Alternatives; the save-time check is the boring choice and adjacent bands `[0,1M)`/`[1M,100M)` tile cleanly). Bands not overlapping is what makes the match (BR-APR-01) return at most one band.

#### `approval_policy_steps` (child of the policy — the ordered chain)

| column | type | null | notes |
|---|---|---|---|
| `id` / `uid` | | NO | `uq_approval_policy_step_uid` |
| `approval_policy_id` | BIGINT | NO | `fk_approval_policy_step_policy` → `approval_policies(id)` |
| `company_id` | BIGINT | NO | denormalised (tenant predicate without join — the ADR-0008 D-2 pattern) |
| `sequence` | SMALLINT | NO | 1..N, dense from 1; `uq_approval_policy_step_seq UNIQUE (approval_policy_id, sequence)`; `chk_approval_policy_step_seq CHECK (sequence >= 1)` |
| `approver_role_code` | VARCHAR(64) | NO | references an existing IAM `roles.code` (e.g. `FINANCE_MANAGER`) — a **scalar string reference**, NOT an FK (roles are global/per-org; the engine validates the code exists at policy-save against `roles`, but stores the code, not the id, so a role rename is the admin's concern — D-8); `chk_approval_policy_step_role CHECK (length(trim(approver_role_code)) > 0)` |
| audit cols | | | |

(No `@Version` on steps — they are children edited only via the policy aggregate; the policy header's `@Version` guards the aggregate.)

**The match function (`ApprovalPolicyMatcher.match(companyId, documentType, amount, branchId) → Optional<ApprovalPolicy>`), BR-APR-01:**
1. candidate set = active policies (`is_active = true AND status = 'ACTIVE'`) for `(company_id, document_type)` whose band contains the amount: `min_amount <= amount AND (max_amount IS NULL OR amount < max_amount)`.
2. partition by branch-specificity: a `BRANCH`-scoped policy with `branch_id = <submission branch>` is **more specific** than a `COMPANY_WIDE` one. **Prefer the branch-scoped match; fall back to company-wide.**
3. within the same specificity, bands do not overlap (BR-APR-02) → at most one. Return it.
4. none → `Optional.empty()` → the engine auto-approves (BR-APR-09 / D-7).
This is a total function returning zero or one policy.

### D-4 — The append-only decision log (`approval_decisions`)

Each approver act on a step is an immutable row (NFR-APR-04 / the ADR-0004 append-only posture).

#### `approval_decisions` (append-only log)

| column | type | null | notes |
|---|---|---|---|
| `id` / `uid` | | NO | `uq_approval_decision_uid` |
| `approval_request_id` | BIGINT | NO | `fk_approval_decision_request` → `approval_requests(id)` |
| `approval_request_step_id` | BIGINT | NO | `fk_approval_decision_step` → `approval_request_steps(id)`; the step decided |
| `company_id` / `branch_id` | BIGINT | NO/YES | denormalised tenant + the request's originating branch |
| `action` | VARCHAR(10) | NO | `DecisionAction`; `chk_approval_decision_action CHECK (action IN ('APPROVE','REJECT'))` |
| `decided_by` | BIGINT | NO | `app_users.id` (the approver; never the submitter — D-8 SoD) |
| `decided_at` | TIMESTAMPTZ | NO | DEFAULT now() |
| `comment` | VARCHAR(1000) | YES | optional rationale |
| audit cols (created_at/created_by) | | | (decisions are insert-only; no `updated_*`, no `version`) |

Indexes: `ix_approval_decisions_request ON approval_decisions (approval_request_id)`; `ix_approval_decisions_step ON approval_decisions (approval_request_step_id)`. **No UPDATE/DELETE** — the table is append-only; a mistaken approval is corrected by the consumer raising a fresh document (BR-APR-07). (A DB-level no-update/no-delete grant is the F11 audit-table precedent; v1 enforces append-only in the service + leaves the DB grant as a hardening follow-up consistent with the other transactional tables.)

### D-5 — The runtime request + its frozen step snapshot (`approval_requests` + `approval_request_steps`) (BR-APR-05/08)

#### `approval_requests` (runtime instance, per submitted document)

| column | type | null | notes |
|---|---|---|---|
| `id` / `uid` | | NO | `uq_approval_request_uid`; `ScopeGuard case "approvalrequest"` resolves on this |
| `company_id` | BIGINT | NO | tenant; `fk_approval_request_company` |
| `branch_id` | BIGINT | NO | the branch the document originated in (the submitter passes it); `fk_approval_request_branch`; drives branch-scoped routing (D-8) |
| `request_number` | VARCHAR(30) | NO | `APPROVAL-####`; allocated at create (D-13); `uq_approval_request_company_number UNIQUE (company_id, request_number)` |
| `document_type` | VARCHAR(60) | NO | opaque consumer key (= the matched policy's, or the submitter's, for auto-approve) |
| `document_uid` | VARCHAR(26) | NO | **opaque** reference to the consumer's document — **no FK** (document-agnostic, D-12) |
| `amount` | NUMERIC(19,4) | NO | the amount the consumer submitted (the match input; the engine does not recompute it — BR-APR-10) |
| `currency` | VARCHAR(3) | NO | = base currency |
| `status` | VARCHAR(20) | NO | `ApprovalRequestStatus`; DEFAULT `'PENDING'`; `chk_approval_request_status` (the 5-value set, D-2) |
| `auto_approved` | BOOLEAN | NO | DEFAULT false; true iff no policy matched (BR-APR-09) — terminal APPROVED with no steps |
| `source_policy_id` | BIGINT | YES | `fk_approval_request_policy` → `approval_policies(id)`; the matched policy (trace only; the steps are snapshotted — D-5); NULL when `auto_approved` |
| `source_policy_uid` | VARCHAR(26) | YES | scalar uid of the matched policy (trace; survives a policy hard-delete should one ever occur) |
| `summary` | VARCHAR(500) | YES | a human label the consumer passes ("PO to Acme") for the inbox |
| `submitted_by` | BIGINT | NO | `app_users.id`; the SoD anchor (D-8) |
| `submitted_at` | TIMESTAMPTZ | NO | DEFAULT now() |
| `resolved_at` | TIMESTAMPTZ | YES | set when the request goes terminal |
| `resolved_by` | BIGINT | YES | `app_users.id` of the last decider / recaller / canceller |
| `version` + audit cols | | | `@Version` guards the request aggregate + the step-advance race (NFR-APR-05) |

Constraints: `uq_approval_request_uid`; `uq_approval_request_company_number`; **`uq_approval_request_document UNIQUE (company_id, document_type, document_uid)`** — the **idempotency backstop** (BR-APR-08: one request per document); `chk_approval_request_status`; `chk_approval_request_amount CHECK (amount >= 0)`; `chk_approval_request_auto CHECK ((auto_approved = false) OR (auto_approved = true AND source_policy_id IS NULL))` (an auto-approved request matched no policy); `fk_approval_request_company`/`_branch`/`_policy`.

Indexes:
```
CREATE UNIQUE INDEX uq_approval_requests_document ON approval_requests (company_id, document_type, document_uid);  -- = the UNIQUE above (idempotency + the getApprovalState lookup, D-7)
CREATE INDEX ix_approval_requests_status ON approval_requests (company_id, status);                                -- list/inbox working set
CREATE INDEX ix_approval_requests_submitter ON approval_requests (company_id, submitted_by);                       -- "my submissions"
```

#### `approval_request_steps` (the FROZEN snapshot of the policy steps — the request's own chain)

| column | type | null | notes |
|---|---|---|---|
| `id` / `uid` | | NO | `uq_approval_request_step_uid` |
| `approval_request_id` | BIGINT | NO | `fk_approval_request_step_request` → `approval_requests(id)` |
| `company_id` / `branch_id` | BIGINT | NO/YES | denormalised tenant + branch |
| `sequence` | SMALLINT | NO | copied from the policy step; `uq_approval_request_step_seq UNIQUE (approval_request_id, sequence)`; `chk_approval_request_step_seq CHECK (sequence >= 1)` |
| `approver_role_code` | VARCHAR(64) | NO | **snapshotted** from the policy step at submit time (BR-APR-05) — a later policy edit does NOT change it; `chk_approval_request_step_role CHECK (length(trim(approver_role_code)) > 0)` |
| `status` | VARCHAR(20) | NO | `ApprovalStepStatus`; DEFAULT `'PENDING'`; `chk_approval_request_step_status` (the 4-value set, D-2) |
| `resolved_by` | BIGINT | YES | `app_users.id` of the decider when the step is APPROVED/REJECTED |
| `resolved_at` | TIMESTAMPTZ | YES | |
| `resolving_decision_id` | BIGINT | YES | `fk_approval_request_step_decision` → `approval_decisions(id)`; the decision that closed this step (audit trace) |
| audit cols | | | (no `@Version` — the request header's `@Version` guards the advance race) |

Indexes: `ix_approval_request_steps_open ON approval_request_steps (approval_request_id, status, sequence)` — the **current-open-step** lookup (lowest-sequence PENDING) and the inbox join (NFR-APR-06).

**Submit creates both atomically (D-7):** `ApprovalEngine.submitForApproval` matches the policy (D-3), and in **one transaction** writes the `approval_requests` row + a `approval_request_steps` row per matched-policy step (snapshot), allocates `APPROVAL-####`, and publishes `APPROVAL.SUBMITTED`. On **no match**, it writes the request terminal APPROVED (`auto_approved = true`, no step rows) and publishes `APPROVAL.RESOLVED`. The `uq_approval_request_document` UNIQUE makes a concurrent double-submit fail the second insert → the service catches it and returns the existing request (idempotency, BR-APR-08).

### D-6 — The step lifecycle: decide / recall / cancel, single-reject-kills-chain, the concurrency guard (FR-APR-07/08/10/15, BR-APR-04/07, NFR-APR-05)

`ApprovalDecisionService`, every action in its own `@Transactional` method under the request's `@Version`:

| action | actor gate (D-8) | effect | guard |
|---|---|---|---|
| **APPROVE** the open step | holds the open step's `approver_role_code`, in scope, **not the submitter** | insert an `approval_decisions(APPROVE)` row; set the open step APPROVED (+ `resolving_decision_id`); **advance**: if a higher-sequence PENDING step exists it becomes the new open step (request stays PENDING); else the **request resolves APPROVED**, set `resolved_*`, publish `APPROVAL.RESOLVED` | request must be PENDING; the targeted step must be the current open step (BR-APR-04) — else reject "not the current step" / "already decided" |
| **REJECT** the open step | same | insert an `approval_decisions(REJECT)` row; set the open step REJECTED; set every later PENDING step `SKIPPED`; the **request resolves REJECTED**, set `resolved_*`, publish `APPROVAL.RESOLVED` (OQ-APR-04 default — one reject kills the chain) | request must be PENDING; targeted step is the open step |
| **RECALL** | the **submitter**, or `APPROVALS.ADMIN` | request → RECALLED, set every PENDING step `SKIPPED`, `resolved_*`, publish `APPROVAL.RESOLVED` | request must be PENDING (cannot recall a resolved request — BR-APR-07) |
| **CANCEL** (admin) | `APPROVALS.ADMIN` | request → CANCELLED, SKIP PENDING steps, `resolved_*`, publish `APPROVAL.RESOLVED` | request must be non-terminal |

**The request `status` is stored, not derived** (a single filterable column — the ADR-0021 D-2 precedent of "store the headline, expose the detail"), and is recomputed by the service from the step states on each transition; the step states are the authority. A request is APPROVED iff every step is APPROVED; REJECTED iff any step is REJECTED; PENDING while any step is PENDING and none REJECTED.

**Concurrency (NFR-APR-05):** two approvers both holding the open step's role click APPROVE at once. Both load the request under its `@Version`; the first commits (step APPROVED, version bumps); the second's save throws `ObjectOptimisticLockingFailureException` → **one retry** re-reads fresh state, finds the step no longer PENDING, and returns a clean "this step has already been decided" (409/422) — no double-advance, no second decision row resolving the same step. (The optimistic-lock-one-retry mechanism is the shipped `StockPostingService` / ADR-0020 D-2 precedent.)

### D-7 — The integration hook contract (the public `ApprovalEngine` interface every consumer calls) (FR-APR-04/05/06, BR-APR-08/09)

`ApprovalEngine` (interface in `approvals.service`, `Impl` package-private) is the **only** inbound surface a consuming module's service injects:

```java
public interface ApprovalEngine {
    /**
     * Submit a document for approval. Idempotent per (documentType, documentUid): a re-submit returns
     * the existing request. Matches the most-specific active policy (D-3); on a match creates a PENDING
     * request with a frozen step snapshot + publishes APPROVAL.SUBMITTED; on NO match creates a terminal
     * APPROVED request (auto_approved=true, no steps) + publishes APPROVAL.RESOLVED. Called by the consumer
     * IN ITS OWN transaction (synchronous; the consumer holds its document while PENDING).
     */
    ApprovalRequestDto submitForApproval(SubmitForApprovalRequest req);   // {documentType, documentUid, amount, currency, branchUid, submittedByUserId, summary}

    /** The synchronous gate: read a document's approval verdict. Empty = never submitted. Scope-checked. */
    Optional<ApprovalRequestDto> getApprovalState(String documentType, String documentUid);
}
```

- **DTO-only.** `SubmitForApprovalRequest` carries scalars + uids; `ApprovalRequestDto` carries the status + steps + decisions. The engine imports no consumer entity; the consumer imports `approvals.service.ApprovalEngine` + `approvals.domain.dto.*` only (D-12). The cross-module edge is `<consumer>.service → approvals.service` — the **same direction** `ap.service → gl.service` takes (the engine is the leaf, like GL).
- **The consumer gates ITSELF.** The engine never reaches into the consumer to flip a PO to ORDERED. At its finalise transition the consumer either (a) polls `getApprovalState` and proceeds only on `APPROVED`, or (b) subscribes to `APPROVAL.RESOLVED` (D-10) and advances itself. Both are the consumer's increment, designed to this contract, built later by Procurement/AP/GL.
- **Submit is NOT separately `@perm`-gated** (FR-APR-14 / approvals.md §2): it is invoked by the consumer's service as a consequence of an act the consumer already gated (raising a PO is gated by the PO permission). `submitForApproval` calls `assertCanActIn(principal, companyId)` (the document's company) but does not require an approvals permission — the caller is the consuming module acting on the user's behalf. `getApprovalState` is likewise scope-checked, not approvals-perm-gated (a consumer reads its own document's verdict).

### D-8 — Step routing + segregation of duties (`StepApproverResolver`) (FR-APR-07/09, BR-APR-06, D-8)

A user may decide the current open step iff **all** of:
1. they hold `APPROVALS.DECIDE` (the coarse RBAC gate — `@perm.has('APPROVALS.DECIDE')` on the controller);
2. they hold the open step's `approver_role_code` — checked via a **role-membership query** (`UserRoleRepository` / `PermissionResolver`-adjacent: does this user have this role code, active, in this company?) — the **fine** routing check on top of the coarse permission;
3. `ScopeGuard.assertCanActIn(principal, request.companyId)` passes;
4. for a `BRANCH`-scoped request (the request's `branch_id` derives from a branch-scoped policy or the submission), the user has access to that branch via `user_branch` (IAM) — the branch-access check;
5. **they are not the submitter** (`decided_by != approval_requests.submitted_by`) — segregation of duties (BR-APR-06, OQ-APR-03 default = enforce). The engine refuses with a 409/422 "you cannot approve a request you submitted".

`StepApproverResolver.canDecide(principal, request, openStep) → boolean` encapsulates (2)+(4)+(5); the controller `@perm.has('APPROVALS.DECIDE')` is (1); `assertCanActIn` is (3). **The engine adds no new role** — `approver_role_code` is an existing IAM `roles.code`; the engine reads role membership, it does not own roles (D-1 boundary).

**The inbox (`ApprovalInboxQuery`, FR-APR-12):** "PENDING requests whose current open step's `approver_role_code` is a role the caller holds, in the caller's company, and (for branch-scoped requests) in a branch the caller can access, excluding requests the caller submitted." A scoped, paginated read joining `approval_requests` (PENDING) → its current open step → the caller's role codes. `assertCanActIn` on the company; the role/branch filter in the query.

### D-9 — The engine posts NOTHING: no GL, no `gl_config` key, no CoA account, no movement (BR-APR-11, the deliberate non-decision)

**Stated loudly so a future reader does not wire it to the books:** the Approvals engine introduces **zero** GL coupling. It adds **no `GlConfigKey` value**, **no chart-of-accounts account**, **no `JournalSourceType`**, **no `GLPostingService` call**, **no `gl_configs` row**. It mutates only its own five tables and emits `APPROVAL.*` events. It is the **first** post-GL increment that touches no finance posting — by design: an approval is a **governance gate**, not a transaction. A consuming module may **react** to `APPROVAL.RESOLVED` and *then* post (e.g. AP executes a payment run once its approval resolves APPROVED) — but that posting is the **consumer's**, gated by the consumer's own GL config, owned by the consumer's increment. The engine never imports `gl.*`. (This is why the structured summary below reports **empty** `newGlConfigKeys` / `newCoaAccountCodes`.)

### D-10 — Events: `APPROVAL.SUBMITTED` + `APPROVAL.RESOLVED` (new `DomainEventType` constants) (FR-APR-11)

Two new constants in `platform.events.DomainEventType` + one new aggregate type:
```
DomainEventType.APPROVAL_SUBMITTED = "APPROVAL.SUBMITTED"   (NEW — emitted at submit, for Notifications later)
DomainEventType.APPROVAL_RESOLVED  = "APPROVAL.RESOLVED"    (NEW — emitted when a request goes terminal; the consumer-reaction trigger)
DomainEventType.AGG_APPROVAL_REQUEST = "APPROVAL_REQUEST"   (NEW aggregate type)
```

Payloads (records in `approvals.domain.dto`, published via `OutboxPublisher.publish` in the resolving TX):
```
ApprovalSubmittedPayload(requestUid, documentType, documentUid, companyId, branchId, amount, submittedByUserId, submittedAt)
ApprovalResolvedPayload (requestUid, documentType, documentUid, companyId, branchId, finalStatus /* APPROVED|REJECTED|RECALLED|CANCELLED */, resolvedByUserId, resolvedAt)
```

The engine is a pure **producer**. **No handler is built in this module** — the consumers of `APPROVAL.RESOLVED` (the PO module auto-advancing to ORDERED, AP releasing a payment run) are each the consuming module's increment, registering a `DomainEventHandler` under their own `IdempotencyGuard` consumer name (e.g. `"PURCHASES.APPROVAL_REACT"`), reading `finalStatus`. At-least-once + consumer idempotency (ADR-0009). Notifications (X.2, deferred) will later subscribe to `APPROVAL.SUBMITTED` to push "you have an approval waiting".

### D-11 — ScopeGuard cases (`approvalpolicy` + `approvalrequest`)

Add two repository injections (`ApprovalPolicyRepository`, `ApprovalRequestRepository`, each with `findCompanyIdByUid(uid)`) and two switch cases to `ScopeGuard.companyIdOf` (the V18/ADR-0021 pattern — the file already grew `quotation`/`salesorder`/`delivery`/`salesreturn`):
```
case "approvalpolicy"   -> approvalPolicies.findCompanyIdByUid(uid);
case "approvalrequest"  -> approvalRequests.findCompanyIdByUid(uid);
```
So `@perm.scoped(#uid,'approvalpolicy','APPROVALS.POLICY.MANAGE')` and `@perm.scoped(#uid,'approvalrequest','APPROVALS.DECIDE')` gate path-uid ops. (Steps + decisions are addressed via their parent request's uid, not directly — no separate case needed.)

### D-12 — ArchUnit edges (the reusability invariant — NO cycle, NO consumer edge)

- **`<consumer>.service → approvals.service.ApprovalEngine`** (+ `approvals.domain.dto`) — the inbound edge each consuming module takes. **Allowed** — the leaf-engine stance `ap.service → gl.service` already takes (the engine is a leaf, like GL).
- **`approvals.events`/`approvals.service` → `platform.events.OutboxPublisher`** — the engine publishes `APPROVAL.*`. **Allowed** — every module publishes to the outbox.
- **`approvals.service` → IAM read** (`PermissionResolver` / a role-membership repository, `user_branch` access) — to route steps to approvers (D-8). **Allowed** — reading the RBAC spine mirrors `ScopeGuard`/`PermissionChecks` (the cross-cutting security spine, not a peer-module cycle; the ADR-0002 stance).
- **NEW ArchUnit rule (the load-bearing one, NFR-APR-01):** **`approvals` MUST NOT depend on any consumer module** — no `import com.erp.modules.{purchases, ap, gl, stock, sales, manufacturing, …}` anywhere under `com.erp.modules.approvals`. Add a `ModuleBoundaryTest` rule: classes in `..approvals..` may depend on `..approvals..`, `..platform..`, `..iam..` (RBAC read), and JDK/Spring — **not** on any other `com.erp.modules.*`. This is what keeps the engine reusable; it is the single most important architectural test for this module. **No cycle** — consumers depend on approvals; approvals depends on no consumer.
- The shipped `ModuleBoundaryTest` enforces controller↛repository, service↛controller, audit-append-only — **none of these edges violates an active rule**.

### D-13 — Numbering: one new lazy `code_sequence` kind (`APPROVAL`)

`ApprovalNumberGenerator` reuses the shipped `code_sequence` row-locked allocation (ADR-0007 D-6) with one new `entity_kind` value: `APPROVAL` (`APPROVAL-%04d`), per company, concurrency-safe (NFR-APR-06). Allocated at request **create** (submit). No new numbering table — only a new `entity_kind` row, created lazily with `next_value = 1` on first use (the shipped mechanism). The `uq_approval_request_company_number` constraint backstops generator bugs. **No seed row → no #12 seed-uid exposure** for numbering.

## Migration plan (V20–V22, additive on frozen V1–V19, #12-safe)

The requirements scope (approvals.md §2) names the migration range **V20–V22**. The DDL is one logical, atomic, additive unit; the work *stages* by code, not by table. Allocation across the three free slots:

- **`V20__approvals_engine.sql`** — the **core engine** DDL + seeds (everything below, blocks 1–6). The architect's recommendation: the five tables + perms + grant land together in V20 (DDL is cheap and additive; a single migration is atomic). The full engine schema ships in V20.
- **`V21__approvals_consumer_hooks.sql`** *(reserved, may be a no-op marker)* — held for any **additive column a first consumer needs** when Procurement wires PO-approval (e.g. should the PO want a denormalised `approval_request_uid` on `purchase_orders`, that ALTER lives in the **consumer's** migration, not here — so V21 may simply be an idempotent re-assert marker like V19 was for V18, or be claimed by the consumer increment). Reserved so the range is honoured; the engine needs no V21 DDL.
- **`V22__approvals_hardening.sql`** *(reserved)* — held for the **append-only DB grant** on `approval_decisions` + `approval_request_steps` (the F11 no-update/no-delete grant precedent) once the operational shape is confirmed; a hardening follow-up, not a v1 blocker. Reserved.

> **Why three slots for one engine:** the instruction fixes the V20–V22 range; the engine's schema is a single additive unit (V20). V21/V22 are **reserved** so a first-consumer additive column and the append-only hardening grant have a home **without** re-opening V20 (V20 freezes the moment it ships). If the PM prefers a single migration, V20 carries it all and V21/V22 stay empty markers. Either way the table/column/constraint names below are fixed.

### `V20__approvals_engine.sql`, in order (each block additive; never edits V1–V19):

1. **CREATE `approval_policies`** (+ `uq_approval_policy_uid`, `fk_approval_policy_company`/`_branch`, the band/branch CHECKs, `ix_approval_policies_match`/`_company`) — D-3.
2. **CREATE `approval_policy_steps`** (+ `uq_approval_policy_step_uid`, `uq_approval_policy_step_seq`, `fk_approval_policy_step_policy`, the seq/role CHECKs) — D-3.
3. **CREATE `approval_requests`** (+ `uq_approval_request_uid`, `uq_approval_request_company_number`, **`uq_approval_request_document`** idempotency UNIQUE, the status/amount/auto CHECKs, `fk_approval_request_company`/`_branch`/`_policy`, `ix_approval_requests_status`/`_submitter`) — D-5.
4. **CREATE `approval_request_steps`** (+ `uq_approval_request_step_uid`, `uq_approval_request_step_seq`, `fk_approval_request_step_request`/`_decision`, the status/seq/role CHECKs, `ix_approval_request_steps_open`) — D-5.
5. **CREATE `approval_decisions`** (+ `uq_approval_decision_uid`, `fk_approval_decision_request`/`_step`, the action CHECK, `ix_approval_decisions_request`/`_step`) — D-4. (`approval_request_steps.resolving_decision_id` FK is added/validated after `approval_decisions` exists — order step 5 before any FK that points to it; a deferred `ALTER … ADD CONSTRAINT fk_approval_request_step_decision` after both tables exist resolves the mutual reference cleanly.)
6. **permission seed + `ORG_ADMIN` grant** — INSERT the five permissions (module `approvals`) `ON CONFLICT (code) DO NOTHING`; grant all to `ORG_ADMIN` via the `roles × permissions` CROSS JOIN `ON CONFLICT DO NOTHING` (the V7/V12/V14/V17/V19 pattern). Permissions have no `uid` — **#12 N/A**.

```sql
-- block 6 (shape, per the V19 precedent)
INSERT INTO permissions (code, module, description) VALUES
    ('APPROVALS.POLICY.VIEW',    'approvals', 'View approval policies'),
    ('APPROVALS.POLICY.MANAGE',  'approvals', 'Create/edit/deactivate approval policies + their step chains'),
    ('APPROVALS.REQUEST.VIEW',   'approvals', 'View approval requests'),
    ('APPROVALS.DECIDE',         'approvals', 'Approve/reject approval-request steps + see the approvals inbox'),
    ('APPROVALS.ADMIN',          'approvals', 'Recall/cancel any approval request; override a stuck chain')
ON CONFLICT (code) DO NOTHING;

INSERT INTO role_permission (role_id, permission_id)
SELECT r.id, p.id
FROM   roles r CROSS JOIN permissions p
WHERE  r.code = 'ORG_ADMIN'
  AND  p.code IN ('APPROVALS.POLICY.VIEW','APPROVALS.POLICY.MANAGE','APPROVALS.REQUEST.VIEW','APPROVALS.DECIDE','APPROVALS.ADMIN')
ON CONFLICT DO NOTHING;
```

**`code_sequence` kind `APPROVAL`** is **not** pre-seeded — created lazily on first use by `ApprovalNumberGenerator` (D-13). **No per-company CROSS-JOIN seed-uid in V20–V22** (no per-company master rows seeded — policies are user-created): therefore **#12-non-exposed**. `MigrationKeepDataIT` extends to V20 (five new empty tables + the additive permission rows are keep-data-safe; no back-fill of existing rows). **No `gl_config` key, no CoA account, no `JournalSourceType` widen, no movement type** — the engine posts nothing (D-9).

## API surface (controllers flat in `com.erp.api`)

`ApiResponse<T>` envelope, `Dto`-suffixed bodies, uid-addressed URLs, paginated lists (the shipped conventions).

**`ApprovalPolicyController`** (`/approvals/policies`):
| method | path | perm | purpose |
|---|---|---|---|
| POST | `/approvals/policies` | `@perm.has('APPROVALS.POLICY.MANAGE')` | create a policy + its steps (FR-APR-01) |
| PUT | `/approvals/policies/{uid}` | `@perm.scoped(#uid,'approvalpolicy','APPROVALS.POLICY.MANAGE')` | edit band/branch/steps/active (FR-APR-02) |
| POST | `/approvals/policies/{uid}/deactivate` | `@perm.scoped(#uid,'approvalpolicy','APPROVALS.POLICY.MANAGE')` | soft-delete (MasterStatus) (FR-APR-02) |
| GET | `/approvals/policies` | `@perm.has('APPROVALS.POLICY.VIEW')` | list (scoped, filter by type/branch/active) (FR-APR-13) |
| GET | `/approvals/policies/{uid}` | `@perm.scoped(#uid,'approvalpolicy','APPROVALS.POLICY.VIEW')` | view one |

**`ApprovalRequestController`** (`/approvals/requests`):
| method | path | perm | purpose |
|---|---|---|---|
| GET | `/approvals/requests` | `@perm.has('APPROVALS.REQUEST.VIEW')` | list (scoped, filter type/status/branch) (FR-APR-13) |
| GET | `/approvals/requests/{uid}` | `@perm.scoped(#uid,'approvalrequest','APPROVALS.REQUEST.VIEW')` | view one (status + steps + decisions) (FR-APR-06 read view) |
| GET | `/approvals/requests/inbox` | `@perm.has('APPROVALS.DECIDE')` | the "awaiting my decision" inbox (FR-APR-12) |
| POST | `/approvals/requests/{uid}/approve` | `@perm.scoped(#uid,'approvalrequest','APPROVALS.DECIDE')` | APPROVE the open step (+ comment); `StepApproverResolver` + SoD enforced in service (FR-APR-07) |
| POST | `/approvals/requests/{uid}/reject` | `@perm.scoped(#uid,'approvalrequest','APPROVALS.DECIDE')` | REJECT the open step → request REJECTED (FR-APR-08) |
| POST | `/approvals/requests/{uid}/recall` | `@perm.scoped(#uid,'approvalrequest','APPROVALS.REQUEST.VIEW')` | submitter recalls own PENDING request (service checks submitter-or-admin) (FR-APR-10) |
| POST | `/approvals/requests/{uid}/cancel` | `@perm.scoped(#uid,'approvalrequest','APPROVALS.ADMIN')` | admin cancel (FR-APR-15) |

> **`submitForApproval` / `getApprovalState` are NOT REST endpoints** — they are the `ApprovalEngine` **service** interface consumers call in-process (D-7). There is no `/approvals/submit` HTTP endpoint in v1 (a document is submitted by its owning module's flow, not by a generic UI). If a future need arises for an out-of-band submit (e.g. a manual "send for approval" admin action), it is an additive controller method — not built in v1.

## Angular nav routes

Under a new "Approvals" nav section (lazy-loaded `approvals` feature module), guarded by the perms:
- **`/approvals/inbox`** — my-approvals work queue (`APPROVALS.DECIDE`); the landing screen — approve/reject with a comment dialog.
- **`/approvals/requests`** — all requests list + filters (`APPROVALS.REQUEST.VIEW`); drill to a request detail showing the step chain + the append-only decision history.
- **`/approvals/requests/:uid`** — request detail (`APPROVALS.REQUEST.VIEW`) — steps, decisions, status, recall/cancel actions per perm.
- **`/approvals/policies`** — policy admin list + editor (`APPROVALS.POLICY.VIEW` / `APPROVALS.POLICY.MANAGE`) — document type, band, branch scope, the ordered step chain (sequence + approver role picker reading IAM roles).

## Consequences

**Positive**
- A **single reusable governance spine** ships once; Procurement (PO-approval), AP (payment-run approval), GL (journal approval), Stock (write-off approval), and Manufacturing (production-order release) each wire to it with **one service call + one gate** — no per-module ad-hoc approval. The engine is the leaf; the dependency points into it.
- **Reusability is enforced, not hoped for** — the new `ModuleBoundaryTest` rule (D-12) fails the build if `approvals` ever imports a consumer module. The document-agnostic `document_type`/`document_uid` reference (no FK) is what makes the engine reusable.
- **The request is a frozen snapshot** (D-5): a policy edit can never retroactively change an in-flight or historical governance instance — the audit story is reproducible six months out (BR-APR-05).
- **Deterministic match** (D-3): branch-scoped beats company-wide, non-overlapping half-open bands, no-match → auto-approve. A total function; no ambiguity, and adding the engine never silently blocks a flow with no policy (OQ-APR-01 default).
- **Zero finance coupling** (D-9): the engine posts nothing — no GL config to seed, no account, no posting handler to get wrong. The smallest possible blast radius for a cross-cutting enabler.
- Additive and surgical: 5 new tables, 5 permissions + grant, 2 new event constants, 2 new ScopeGuard cases, 1 lazy numbering kind. **V1–V19 frozen. #12-non-exposed** (no per-company seed-uids).

**Negative / costs**
- **The hook is in-process, synchronous (D-7).** A consumer calling `submitForApproval` in its own TX couples the consumer's transaction to the engine's write (same as `ap → gl`). This is the right boundary (the engine is a leaf), but it means the engine must be available for the consumer's submit to succeed — acceptable in a modular monolith (same JVM), and the *resolution* is async via the event, decoupling the long-running approval from the submit.
- **Stored-status recompute on every transition (D-6)** is a denormalisation that must stay tied to the step states; a service bug could desync `approval_requests.status` from its steps. The step states are the authority; tests must assert the status↔steps tie after every transition (the ADR-0021 D-2 same-class risk).
- **Role-routing via a role-membership read (D-8)** couples the engine to IAM's role model (reading `roles.code` membership). This is the RBAC spine (allowed, D-12), but a role rename in IAM orphans a policy step's `approver_role_code` (stored as a string, not an FK). Mitigation: the policy save validates the role code exists; an admin renaming a role must update affected policies (an operational note, flagged OQ; a future enhancement could FK the policy step to `roles.id` with a denormalised code — additive).
- **The first consumer's wiring is a separate increment** — this slice ships the engine but **demonstrates** it only through tests; the value lands when Procurement wires PO-approval. The PM should sequence the PO-approval increment right after this so the engine is exercised end-to-end.

**Neutral / deferred**
- Single-approver-per-step, sequential, role-routed, pull-inbox, one-reject-kills-chain, amount+branch matching, base currency (approvals.md §2). Named approvers, groups, quorum/N-of-M, parallel steps, delegation, escalation/SLA, push notifications, richer conditions, multi-currency thresholds — all deferred, none precluded (NFR-APR-08): the v1 row shape survives each additive extension (a `condition` column, an `approver_user_id` beside the role, a `quorum` on the step, a delegation table, a `branch`-list).

## Alternatives considered

- **Module placement — own module vs under `platform` vs under `purchases`.** *Decided: own `com.erp.modules.approvals`.* `platform` is the cross-cutting spine the engine *uses* (security/outbox/audit), not a home for a domain module with masters + lifecycle + RBAC. Under `purchases` would make it procurement-specific — the exact anti-goal (NFR-APR-01). A flat peer module is what `ModuleBoundaryTest` reasons about and keeps the engine reusable.
- **Document reference — opaque string+uid (no FK) vs a polymorphic FK / per-consumer join table.** *Decided: opaque `document_type` (VARCHAR) + `document_uid` (VARCHAR), no FK.* A real FK is impossible without the engine knowing every consumer table (a cycle + the reusability-killer). A per-consumer join table would make the engine grow a table per consumer. The opaque reference is the document-agnostic choice; the `uq_(company, type, uid)` idempotency backstop is the only integrity the engine needs (it does not need referential integrity into a consumer it must not know).
- **Request steps — snapshot the policy vs read the live policy.** *Decided: snapshot at submit (frozen `approval_request_steps`).* Reading the live policy would let a mid-flight policy edit change an in-flight or historical request — breaking reproducibility and the audit story (BR-APR-05). Snapshot is the sales-line-price discipline; one extra table, total auditability.
- **Reject semantics — one reject kills the chain vs reject-bounces-a-step.** *Decided: one reject resolves the whole request REJECTED (OQ-APR-04 default).* The bounce-back loop (reject sends the document back to the submitter / previous step to revise) is a richer state machine deferred to §2; the simple kill is unambiguous and the consumer re-submits an amended document. Flagged for owner confirm.
- **No-policy-match — auto-approve vs fail-closed (block).** *Decided: auto-approve (OQ-APR-01 default).* Auto-approve means adding the engine never blocks a flow with no policy configured (a safe rollout). Fail-closed is the stricter governance posture but blocks every document of a type once it has any policy — a heavier operational stance. Flagged ★ for owner confirm; the model supports either (a one-line branch in `submitForApproval`).
- **Band overlap prevention — save-time validation vs a Postgres `EXCLUDE USING gist` range constraint.** *Decided: save-time validation (BR-APR-02).* A `tstzrange`/`numrange` GIST exclusion constraint is the DB-enforced option but adds a range type + a GIST index + brittleness around the half-open/unbounded `max=NULL` top band. The save-time half-open overlap query is the boring choice; adjacent bands tile cleanly. (If overlap defects surface in practice, the GIST constraint is an additive hardening.)
- **The hook — synchronous service call vs event-driven submit.** *Decided: synchronous `submitForApproval` + event-driven `APPROVAL.RESOLVED`.* Submit is a query-shaped command the consumer makes in its own TX (it needs the request back immediately to hold its document); the long-running resolution is the async event. An event-driven submit would force the consumer to poll for "did my submit land" — needless. (OQ-APR-08 confirmed.)
- **Status — stored single enum vs derived-from-steps on read.** *Decided: stored, recomputed on transition.* A derived-on-read status would re-scan the steps every list/filter; a stored, filterable column (recomputed each transition, the steps authoritative) is the ADR-0021 D-2 precedent — clean filtering, the steps as the source of truth.

## Open items (OQ-APR — architect owner-style defaults adopted; the ★ load-bearing ones to owner-confirm before the first consumer ships)

- **★ OQ-APR-01 — no-policy-match: auto-approve (adopted default) vs fail-closed.** Settled to **auto-approve**; owner to confirm (a `submitForApproval` one-line branch flips it; a per-company config flag could offer both).
- **★ OQ-APR-03 — segregation of duties: enforce (adopted default — submitter may not approve own) vs allow.** Settled to **enforce**; owner to confirm (a per-company flag for single-operator companies if both postures are needed).
- **★ OQ-APR-04 — reject: one reject kills the chain (adopted default) vs bounce-a-step.** Settled to **kill-the-chain**; owner to confirm v1 simplicity is acceptable.
- **OQ-APR-02 — match conditions beyond amount + branch.** Adopted **amount band + branch only** in v1; additive condition columns later. Owner confirm none needed for PO-approval.
- **OQ-APR-05 — step routing: role (adopted) vs named individual vs group.** Settled to **role**; named/group deferred (§2). Owner confirm role-routing suffices.
- **OQ-APR-06 — re-submit of a terminal request's document uid.** Adopted **blocked — the consumer raises a new document (new uid)** (clean idempotency). The supersede-with-a-new-chain alternative is more convenient but muddies BR-APR-08; confirm at consumer-wiring time.
- **OQ-APR-role-rename — a renamed IAM role orphans a policy step's `approver_role_code` (string, not FK).** Adopted **policy-save validates the code; admin updates affected policies on rename** (an operational note). A future FK to `roles.id` + denormalised code is an additive hardening if rename churn becomes a problem.
- **OQ-APR-08 — submit synchronous vs event-driven.** Settled to **synchronous submit + event-driven resolution** (confirmed; flagged so a future consumer does not reinvent it).

---

## Summary

ADR-0022 designs the **Approvals Workflow Engine** as a NEW, document-agnostic `com.erp.modules.approvals` module: five tables (`approval_policies` + `approval_policy_steps` master; `approval_requests` + `approval_request_steps` runtime with a **frozen step snapshot**; `approval_decisions` append-only log), four enums with service-guarded transitions, a deterministic policy-match (branch-scoped beats company-wide; non-overlapping half-open `[min,max)` bands; no match → auto-approve), the **integration hook** every other module calls (`ApprovalEngine.submitForApproval` idempotent per (type,uid) + `getApprovalState` synchronous gate), an append-only decision flow with single-reject-kills-chain semantics and an optimistic-lock step-advance guard, role-routed step approval with enforced segregation of duties, and a pull inbox.

**The reusability invariant (D-1/D-7/D-12):** the engine stores an **opaque** `document_type` + `document_uid` (no FK into any consumer), exposes `ApprovalEngine` as the only inbound surface (the leaf-engine stance `ap → gl` takes), and a **new `ModuleBoundaryTest` rule forbids any `approvals → <consumer>` edge** — no cycle, the dependency points only into the engine. **The engine posts NOTHING (D-9):** no `gl_config` key, no CoA account, no GL posting — it gates, it does not transact; consumers react to the new `APPROVAL.RESOLVED` outbox event and post on their own.

**Readiness:** the ADR is concrete enough to build V20 + the full model without guessing a rule — every table, column, constraint name, enum, transition, match formula, snapshot mechanism, decision flow, hook signature, event/payload, ScopeGuard case, perm, and ArchUnit edge is specified. **Additive on frozen V1–V19. #12-non-exposed** (no per-company seed-uid inserts — policies are user-created, the `APPROVAL` numbering kind is lazy, the only seed is the uid-less permission grant). **Depends on: nothing not shipped** (IAM/RBAC/ScopeGuard/outbox/code_sequence/MasterStatus/audit). **Gates: Procurement depth** (PO/requisition approval — the first consumer, its own increment); **reusable by** payment-run/journal/stock-write-off/production-order-release approval. **New shared-contract identifiers:** events `APPROVAL.SUBMITTED` + `APPROVAL.RESOLVED` (+ aggregate `APPROVAL_REQUEST`); ScopeGuard cases `approvalpolicy` + `approvalrequest`; perms `APPROVALS.POLICY.VIEW`/`APPROVALS.POLICY.MANAGE`/`APPROVALS.REQUEST.VIEW`/`APPROVALS.DECIDE`/`APPROVALS.ADMIN`; nav routes `/approvals/inbox`, `/approvals/requests`, `/approvals/requests/:uid`, `/approvals/policies`; `code_sequence` kind `APPROVAL`. **No new gl_config keys, no new CoA accounts, no new JournalSourceType, no new movement types.**
