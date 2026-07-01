# Triage Process — Technical Team

> **Status:** Canonical process bible for the **technical team**. This is how the
> engineering agents in [`.claude/agents/`](../../.claude/agents/) turn a
> **business-voice User Problem Report (UPR)** into reproducible engineering
> artifacts — an Issue, a Fix Plan, a verified fix, and a closed UPR.
>
> **Read order:** [COMPANY-SCENARIO.md](COMPANY-SCENARIO.md) (who reports, in what
> words) → this file (how we interpret it) → the relevant module docs.
>
> *Sikia kero, tafsiri kwa uhandisi* — "Hear the complaint, translate it into engineering."

---

## 0. Cast — who does what

Two populations meet here:

- **Business voice (the reporter).** A named persona from the scenario — Sabina
  Aloyce (salesperson), Hamisi Ngassa (route agent), Amina Mwanga (accountant),
  Grace Mhina (CFO), Saidi Karume (storekeeper), etc. — or an external party's
  complaint *translated by* a staff persona. They speak in **outcomes and
  frustration**, never in stack traces. The `end-user` agent role-plays this voice.

- **Technical team (the interpreters).** The eight build/operate agents:

  | Agent | Interprets / owns in triage |
  |---|---|
  | **system-analyst** | "Is this a bug or a missing/ambiguous requirement?" Owns UPRs that turn out to be *unspecified behaviour*. |
  | **solutions-architect** | Cross-module / data-model / design-rule calls; decides when a fix needs an ADR. |
  | **project-manager** | Runs the triage queue: prioritises, assigns, tracks UPR→close, reports status. |
  | **backend-engineer** | API / service / Flyway / posting / 500-error issues. |
  | **frontend-engineer** | Angular form / state / display / route-guard-UX issues. |
  | **qa-engineer** | Reproduces, writes the regression test, runs the verification gate, signs off. |
  | **security-engineer** | Auth / RBAC 403 / permission-code / tenant-&-branch-isolation issues. |
  | **devops-engineer** | Config / env / migration-apply / deploy / CI / data-not-provisioned issues. |

The **project-manager** chairs triage; the **qa-engineer** is the gatekeeper at
close. Nobody self-closes a UPR — the reporter (or `end-user` standing in) confirms.

---

## 1. The flow

```
   BUSINESS VOICE                 TECHNICAL TEAM
   ─────────────                  ──────────────
                                                        artifact
   ┌──────────────┐
   │     UPR      │  business voice: "what I tried,      UPR-<n>
   │ (User        │   what I expected, what happened,    (reporter-owned,
   │  Problem     │   why it matters" — no jargon         business words)
   │  Report)     │
   └──────┬───────┘
          │  filed by a persona / end-user
          ▼
   ┌──────────────┐  project-manager + the likely owner:
   │   TRIAGE     │  reproduce? classify layer & module,    triage note
   │              │  set severity, split/merge, assign      on the UPR
   └──────┬───────┘  (or bounce: WORKS-AS-DESIGNED /
          │           NEEDS-REQUIREMENT / DUPLICATE /
          │           CANNOT-REPRODUCE)
          ▼
   ┌──────────────┐  qa-engineer + owning agent:
   │    ISSUE     │  technical, reproducible: exact steps,   ISSUE-<n>
   │              │  expected vs actual, evidence, suspected (links UPR-<n>)
   └──────┬───────┘  layer, severity, assignee
          │
          ▼
   ┌──────────────┐  owning engineer (+ architect if design):
   │  FIX PLAN    │  root-cause hypothesis, the change,      FIXPLAN-<n>
   │              │  test to add, risk, rollout, verifier    (links ISSUE-<n>)
   └──────┬───────┘
          │  implement on a branch → PR (one logical change)
          ▼
   ┌──────────────┐  qa-engineer:
   │ VERIFICATION │  new regression test goes red→green;     verification
   │              │  reporter's exact steps now pass;        record
   └──────┬───────┘  full gate (mvn test + npm test) green
          │
          ▼
   ┌──────────────┐  reporter / end-user confirms in their
   │  CLOSE UPR   │  own words: "I can assign Hamisi now."   UPR closed
   └──────────────┘
```

**Rules of the flow:**

1. **One UPR can fan out to many Issues** (a single "I can't open Purchase Orders"
   may be a 403 *and* a stale config). **Many UPRs can merge to one Issue** (the
   set-default 409 hit ~8 endpoints from one root cause). Track the links both ways.
2. **A UPR is never "fixed" — it is *closed*.** The Issue is fixed; the UPR is
   closed only when the reporter's original outcome works. Closing the Issue without
   closing the UPR is the classic miss (root-cause fixed, user still blocked by a
   second layer).
3. **Triage can reject up the chain, not just down.** A UPR that is really a missing
   requirement bounces to `system-analyst` (becomes a `US-<MODULE>-NNN`), not to an
   engineer. Forcing an undefined behaviour into a "fix" is how you ship the wrong thing.
4. **Root is a liar for permission bugs.** Reproduce permission/visibility issues as
   the **reporting persona's role** (e.g. `FIELD_SALES_AGENT`), never as root —
   root bypasses RBAC and tenant scope and will show green on a real bug. (See the
   phantom-permission and route-guard-parity memories.)

---

## 2. Triage — the decision a UPR goes through

The **project-manager** runs each new UPR through these gates, pulling the likely
owning agent in early. Output is a short **triage note** appended to the UPR.

**Gate A — Is it actionable?**
- Enough to attempt a repro? (who/role, which branch, which screen, what they did).
- If not → status `NEEDS-INFO`, one targeted question back to the reporter. Don't guess.

**Gate B — Reproduce it (as the reporter's role).**
- `qa-engineer` (or owning agent) replays the steps as the persona's `erpRole` on the
  relevant branch. Result is one of:
  - **Reproduced** → continue.
  - **CANNOT-REPRODUCE** → capture environment delta (role, branch, data state, build);
    often a config/provisioning gap → route to `devops-engineer`, keep UPR open.

**Gate C — Bug, or not-a-bug?**
- **WORKS-AS-DESIGNED** → close UPR with a plain-language explanation + (if the UI
  misled the user) a *separate* UX UPR for `frontend-engineer`/`end-user`.
- **NEEDS-REQUIREMENT** (behaviour was never specified / is ambiguous) → route to
  `system-analyst`; becomes a user story, not an Issue.
- **DUPLICATE** → link to the existing UPR/Issue; inherit its status.
- Otherwise → it's a defect; promote to an **Issue**.

**Gate D — Classify & assign.**
- Suspected **layer** (web / api / data / permission / config) and **module** drive
  the owner via the [mapping table](#6-mapping-table--problem-area--owning-agent).
- Set **severity** (Blocker / High / Medium / Low — see template).
- Assign a single **owning agent**. Pull `solutions-architect` if it crosses modules
  or touches the data model / a design rule (it may need an ADR before any code).

---

## 3. ISSUE template

> The technical restatement of a UPR: reproducible, evidence-backed, layer-tagged.
> Authored by the **qa-engineer** with the owning agent. One Issue = one defect.

```
ISSUE-<n>
Links UPR:        UPR-<n>   (one or more; record merges/splits)
Title:            <imperative, specific — "Sales order does not persist
                   assigned agent on save">

Reproduction (UI):
  Login as:       <username> (<ERP role>)        e.g. dkessy (BRANCH_MANAGER)
  Branch:         <active branch / X-Branch-Uid>  e.g. Dar es Salaam HQ
  Data state:     <preconditions>                 e.g. an open sales order exists
  Steps:
    1. <click-by-click, screen names from the actual UI>
    2. ...
    3. ...

Expected:         <what should happen — tie to the user story if one exists>
Actual:           <what happens instead>

Evidence:
  Console:        <browser console error / Angular error, verbatim>
  API:            <METHOD path → HTTP status; relevant response body / errors[]>
  Server log:     <exception class + first frame, if a 500>
  Screenshot:     <path or reference>

Suspected layer: [ web | api | data | permission | config ]   (one primary;
                  note secondary if it spans two)
Module:          <sales | purchases | stock | gl | ar | ap | cash-bank | tax |
                  fixed-assets | hr-payroll | manufacturing | iam | ... >
Severity:        [ Blocker | High | Medium | Low ]
                  Blocker = cannot do the core job, no workaround (e.g. can't post,
                            can't log in, data loss, cross-tenant leak).
                  High    = core job blocked but a workaround exists, or wrong
                            numbers a user would act on.
                  Medium  = a flow is painful / partially broken; non-core.
                  Low     = cosmetic, copy, polish; no functional impact.
Assigned agent:  <one of the eight — from the mapping table>
```

---

## 4. FIX PLAN template

> The owning engineer's plan *before* coding. Reviewed by `qa-engineer` (test) and,
> when design is involved, `solutions-architect`. Authored by the owning agent.

```
FIXPLAN-<n>
Links Issue:        ISSUE-<n>

Root-cause hypothesis:
  <the single most likely cause, stated falsifiably — what code/state produces the
   Actual. If >1 plausible, list and say how you'll confirm which.>

The change (files / area):
  <the smallest change that fixes the cause, by layer>
  - backend:  <files / service / repository / migration — name them>
  - web:      <component / service / guard — name them>
  - migration: <new V<n>__*.sql ONLY if schema change; OWNER APPROVAL required
               per migration-approval rule — else "none">

Test to add:
  <the regression test that fails today and passes after — name the class/spec and
   the assertion. This is non-negotiable; a fix without a test that pins it is rejected.>
  - <e.g. qa: SalesOrderSetAgentIT — assert agent uid persisted & re-read on GET>

Risk:
  <blast radius: what else touches this code/path; any data-integrity / posting /
   permission implication; backward-compat. State "low/contained" only if true.>

Rollout:
  <branch off develop → PR (one logical change) → merge to develop → deploy to QA.
   Note feature flag / config toggle / data backfill if any. No DB wipe (durable DB).>

Who verifies:
  <qa-engineer runs the gate + the reporter's exact steps; reporter/end-user confirms
   the UPR outcome in business words.>
```

---

## 5. Worked example

A real flow, end to end, using the scenario cast.

### 5.1 The UPR (business voice)

```
UPR-014
Reporter:  Daudi Kessy — Group Sales Manager (dkessy, BRANCH_MANAGER), Dar es Salaam HQ
Title:     I can't put Hamisi onto the Mwanza orders

What I did:
  Hamisi Ngassa runs the Mwanza route. I opened one of the route sales orders for
  Joseph Ulimboka's duka to set Hamisi as the agent so the order is his and the
  commission lands right. I picked him from the list and saved.

What I expected:
  The order shows Hamisi as the sales agent, and stays that way.

What happened:
  After I save and come back, the agent is blank again. It's like it never took.
  None of the Mwanza route orders have an agent, so Hamisi says they're "not his"
  and his collections don't reconcile.

Why it matters:
  Every route order on his round is affected. His cash collection can't be matched
  to his orders, and I can't run the agent-performance report properly.
```

### 5.2 Triage note (project-manager)

```
Gate A: actionable — role, branch, screen, action all given. OK.
Gate B: reproduce as BRANCH_MANAGER on a Mwanza sales order (NOT as root — root would
        mask any scope/permission angle). qa-engineer to replay.
Gate C: not WAD — assigning an agent is specified (US-SALES, set/change-agent). Defect.
Gate D: Two candidate layers — (1) web doesn't send the chosen agent, or (2) api
        accepts but doesn't persist. Suspected primary: api/data (value lost on save).
        Module: sales. Severity: High (core sales attribution broken across a whole
        route; workaround = none that fixes attribution). Owner: backend-engineer,
        with qa-engineer to pin it; pull frontend-engineer only if repro shows the
        request omits the agent.
→ Promote to ISSUE-031, owner backend-engineer.
```

### 5.3 The Issue (technical)

```
ISSUE-031
Links UPR:        UPR-014
Title:            Sales order does not persist assigned agent (set-agent action no-ops)

Reproduction (UI):
  Login as:       dkessy (BRANCH_MANAGER)
  Branch:         Mwanza (X-Branch-Uid = Mwanza)
  Data state:     an open sales order for customer Joseph Ulimboka exists
  Steps:
    1. Sales → Sales Orders → open the order.
    2. Click "Set agent", choose "Hamisi Ngassa", Save.
    3. Toast shows success; reload the order (or re-open from the list).

Expected:         Order detail shows Sales agent = Hamisi Ngassa, persisted across reload.
Actual:           Sales agent field is blank again after reload.

Evidence:
  Console:        (none — no client error; request returns 200)
  API:            PUT /api/v1/sales-orders/uid/{uid}/agent → 200, but subsequent
                  GET /api/v1/sales-orders/uid/{uid} returns "salesAgentUid": null
  Server log:     (no exception)
  Screenshot:     docs/simulation/evidence/upr-014-agent-blank.png

Suspected layer: api  (secondary: data — value not written on the aggregate)
Module:          sales
Severity:        High
Assigned agent:  backend-engineer
```

### 5.4 The Fix Plan

```
FIXPLAN-031
Links Issue:        ISSUE-031

Root-cause hypothesis:
  The set-agent endpoint resolves the agent but never assigns it to the SalesOrder
  aggregate before save (or saves a detached copy), so the column stays null. The
  200 is the controller returning before the field is set. Confirm by asserting the
  persisted salesAgentId on the re-read entity in a service test.

The change (files / area):
  - backend: SalesOrderServiceImpl#setAgent — set salesAgent on the loaded aggregate
             and persist; ensure mapping to SalesOrderDto exposes salesAgentUid.
             (controller api/SalesOrderController.java already wires the action.)
  - web:     none expected (request already carries the agent uid — confirm in repro).
  - migration: none (column exists).

Test to add:
  - qa: SalesOrderSetAgentIT — given an open order, PUT set-agent, then re-load the
        aggregate and assert salesAgentId is the chosen agent; and GET DTO exposes
        salesAgentUid. Fails today (null), passes after.

Risk:
  Contained to the sales-order set-agent path. No posting/GL effect. No schema change.
  Check the agent-eligibility rule (internal-agent prereq) still enforced — don't
  regress BR-SALES-06.

Rollout:
  Branch fix/sales-set-agent off develop → PR (one logical change) → develop → QA.
  No flag, no backfill, no DB change.

Who verifies:
  qa-engineer: SalesOrderSetAgentIT red→green + full mvn test / npm test gate green,
  then replays ISSUE-031 steps. Reporter (Daudi via end-user) confirms UPR-014:
  "Hamisi sticks on the order now."
```

### 5.5 Verify & close

- `qa-engineer` confirms the new IT fails on the base commit and passes on the fix,
  the reporter's exact steps now show Hamisi persisted, and the gate is green.
- `end-user` (as Daudi Kessy) re-runs the original flow and confirms in business
  words. **UPR-014 closed.** ISSUE-031 fixed; the link records that one Issue closed
  one UPR. (Had other route orders been a *separate* symptom, they'd be checked here
  too before close.)

> This mirrors a real fix already shipped (`feat(sales): add set/change-agent action
> for sales orders`, commit fb29858) — the template is descriptive of how the team
> actually works, not aspirational.

---

## 6. Mapping table — problem area → owning agent

The **suspected layer + symptom** picks the owner. When two apply, the **primary
symptom** wins; the secondary owner is a reviewer.

| Problem area / symptom (business voice → tell) | Suspected layer | Owning agent |
|---|---|---|
| "It says I'm not allowed / access denied" · HTTP **403** · button/menu missing for a role | permission | **security-engineer** |
| "Can't open the page" that's really a **route-guard ↔ endpoint permission mismatch** | permission | **security-engineer** (with frontend-engineer on the guard) |
| Feature works for root/admin but **not for a normal role** (phantom permission code) | permission | **security-engineer** |
| "I can see another company's / branch's data" · cross-tenant or branch leak | permission / data | **security-engineer** |
| "Login / token / session" problems, password reset, refresh-token issues | permission | **security-engineer** |
| Form **won't save / loses what I typed** · validation too strict/lax · field blank after save (web side) | web | **frontend-engineer** |
| Wrong **display**: bad currency/date/number format, stale value, wrong status pill | web | **frontend-engineer** |
| Missing **empty / loading / error** state · confusing copy / labels / sequencing | web | **frontend-engineer** (UX with **end-user**) |
| Picker/dropdown shows nothing or wrong options (client filtering/binding) | web | **frontend-engineer** |
| **"Something went wrong" red modal** · HTTP **500** · stack trace | api | **backend-engineer** |
| **Won't post / out of balance / wrong total** · journal/GL/AR/AP/stock posting wrong | api / data | **backend-engineer** |
| Value **accepted but not persisted** · DTO/mapping gap · aggregate not updated | api / data | **backend-engineer** |
| Server-side **business rule** wrong or missing (threshold, status transition, BR-…) | api | **backend-engineer** (rule source from **system-analyst**) |
| **Numbers don't tie** across modules · outbox/cross-module side effect not firing | data / api | **backend-engineer** (design via **solutions-architect**) |
| "This whole thing was never built / behaves arbitrarily" — **undefined requirement** | — | **system-analyst** (→ user story) |
| Two valid behaviours clash / **ambiguous spec** · "is this even supposed to do that?" | — | **system-analyst** |
| Cross-module / data-model / design-rule change · needs an **ADR** | data | **solutions-architect** |
| **CANNOT-REPRODUCE** tied to env · config/secret/JWT-mode · migration not applied | config | **devops-engineer** |
| **Per-tenant data not provisioned** (role/permission/reference data missing on a company) | config / data | **devops-engineer** (provisioning) / **system-analyst** if it's a spec gap |
| **Deploy / CI / image / Postgres-up** failures · "it works locally not on QA" | config | **devops-engineer** |
| Reproduction, the **regression test**, the verification gate, sign-off | (all) | **qa-engineer** |
| Queue order, priority, who-owns-what, UPR→close tracking, status to the owner | (all) | **project-manager** |

**Tie-breakers:**
- **403 vs 500 vs blank-but-saved** is the fastest splitter: 403 → security; 500 →
  backend; saved-then-empty → backend (persist) unless the request itself omits the
  field → frontend.
- A **"cannot open"** report is ambiguous by nature — capture the **runtime status as
  the reporting role** (403 → security; 500 → backend; 404 → backend/config) before
  assigning. Don't assign on the word "open" alone.
- If the symptom is "wrong behaviour" but **no one can point to the spec**, it's a
  `system-analyst` requirement gap before it's anyone's bug.

---

## 7. Standing rules the triage must honour

- **Test as a non-root user.** Permission, visibility, and tenant/branch issues are
  invisible to root and ORG_ADMIN. Reproduce with the reporter's actual `erpRole`.
- **Scope from the loaded entity, not a caller param** — when an Issue touches
  tenant/branch scoping, the fix derives company/branch from the persisted record.
- **Error-message hygiene.** A fix that surfaces an error must keep it friendly and
  leak **no** system internals (no ULIDs, field names, BR-/ADR- codes, exception
  text); technical detail goes to logs. Expected business validations surface calmly
  inline, never via the red "Something went wrong" modal.
- **Migrations are frozen / additive-only and need owner approval.** No Fix Plan
  creates or edits a migration without explicit owner sign-off on the DDL + version;
  the DB is durable in every environment and is never wiped.
- **Every fix carries a regression test** that fails before and passes after — pinned
  by `qa-engineer`. No test, no close.
- **One logical change per PR**, branched off `develop`; the owner merges to `main`.
  Conventional Commits; the branch/PR references the UPR/Issue.
- **The reporter closes the UPR, not the engineer.** Fixing the Issue is necessary,
  not sufficient — the original outcome must work in the reporter's own words.

---

*End of triage process. Every UPR resolves to a status above; every Issue resolves to
an owning agent in the mapping table; every fix resolves to a verified, reporter-confirmed close.*
