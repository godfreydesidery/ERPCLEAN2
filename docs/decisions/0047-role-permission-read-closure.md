# 0047 — Role-grant read-closure: a screen-read manifest + closure test

- **Status:** Accepted
- **Date:** 2026-06-28
- **Deciders:** solutions-architect (decision), security-engineer (owns the role/closure data), qa-engineer (builds the guard)
- **Context source:** [docs/simulation/ISSUES-REGISTER.md](../simulation/ISSUES-REGISTER.md) ISSUE-008; [docs/security/findings.md](../security/findings.md) F21/F22; [docs/simulation/SIM-RUN-REPORT.md](../simulation/SIM-RUN-REPORT.md)

## Context

The 2026-06-28 business-operations simulation drove the product as 16 real, minimally-granted
personas (never root, never `ORG_ADMIN`). 26 of 28 blockers were one mechanism: a custom operational
role held a screen's **primary verb** but not a **supporting read** the same screen fires on load, so
a side-load 403'd and blanked the screen. The personas read it as "the page won't open." Root and
`ORG_ADMIN` never see it — root short-circuits RBAC and the seed CROSS JOINs the whole catalogue onto
`ORG_ADMIN` (`R__seed_permissions.sql`), so neither ever 403s. This is the fourth member of a known
family: **phantom codes** (code never seeded), **route-guard↔endpoint parity** (guard code ≠ endpoint
code), **code-seeded** — and now **role-grant vs screen-read-dependency closure**: every code is right,
every gate is right, but the *role's grant set is not closed over the reads its screens fire*.

The forces:

1. **Roles are runtime data, not seed data.** Only `ORG_ADMIN` is seeded (the CROSS JOIN); every
   operational role is composed at deployment/onboarding time. So a build-time test that "checks all
   roles" cannot exist — there is no compile-time list of roles to check. Whatever we build must
   assert a property of the **screen→read contract**, not of any particular tenant's role catalogue.

2. **There is already a sensitivity gradient, and two opposite remediations are live.** F21 relaxed
   the **low-sensitivity ambient pickers** (`branches`, `products`, `wht/types`) to a within-tenant
   read floor (`@perm.hasOrMember` / `@perm.scopedOrMember`) and broadened the PO list to
   `PURCHASE.ORDER.VIEW or AP.BILL.ENTER` for the bill matcher — so those reads **no longer require
   closure**: any provisioned member of the company passes, tenant isolation unchanged. F22 ruled the
   **opposite** for `customers`/`suppliers` (and, in the deep run, `AR.VIEW`): party master carries
   TIN/VRN/mobile-money/credit-limit/balance — disclosure-grade data — so the gate **stays strict**
   and the fix is **role composition**, i.e. the role MUST carry the read. A pure "relax every gate"
   answer is wrong (it would dissolve F22's least-privilege line); a pure "grant every read to every
   role" answer is wrong (over-gating, and still nothing keeps it from regressing).

3. **Required vs optional reads must be distinguishable.** A receipt screen's customer picker is
   **required** — you cannot record a receipt without selecting a customer. Its WHT dropdown is
   **optional** — degrade to "no withholding". The model must not force a cashier to hold `WHT.VIEW`
   just to open Record-Receipt, or it over-gates exactly the way F21 fought.

4. **The "what reads does screen X fire" knowledge is implicit in component code today.** It can drift
   silently: a screen adds a `GET /something` on load and no test notices the new dependency. We need
   **one** authoritative declaration and a test that keeps it honest against the **real controller
   gates** (so the manifest can't claim a read is `hasOrMember` when the controller says `has`).

5. **No migration.** Schema and seed are frozen ([ADR-0043](0043-schema-freeze-durable-db.md)); all
   the codes are already seeded. The fix is a test + a manifest + a provisioning discipline, shippable
   in one PR.

## Decision

**Adopt a single declarative screen-read manifest plus a build-time `RolePermissionClosureTest`, and
explicitly do NOT add a runtime route-guard closure check.** Concretely:

1. **One authoritative manifest** — `backend/src/test/resources/security/screen-read-closure.json`
   (test-resources; it constrains a build gate, not runtime behaviour). It declares, per guarded
   screen: the **route permission** that opens it, and the reference-data reads it fires on load, each
   tagged `required` or `optional` **and** carrying the controller gate it hits. One file, version-
   controlled, reviewed in the same PR as any screen that adds a load.

2. **`RolePermissionClosureTest` (security, surefire — no DB)** asserts the **closure invariant as a
   property of the manifest against the live controller gates**, in three checks:
   - **(a) Gate-form honesty.** For every read in the manifest, the named controller handler's actual
     `@PreAuthorize` gate-form is one of: `has(CODE)` / `scoped(...,CODE)` (a **closure-bearing**
     gate — the holder must carry `CODE`), or `hasOrMember(CODE)` / `scopedOrMember(...,CODE)` /
     a disjunction `has(A) or has(B)` (a **member-floor / adjacent-verb** gate — closure satisfied by
     membership or the adjacent verb). The manifest's declared gate must **equal** the code in the
     real annotation. This is the anti-drift anchor: if a screen's read endpoint changes its gate, or
     the manifest names the wrong code, the build goes red. It reuses the exact scan/regex the
     existing `PermissionCodesSeededTest` uses, so the parser is shared, not reinvented.
   - **(b) Required-read closure is reachable.** For every `required` read whose gate is
     **closure-bearing** (`has`/`scoped`), assert the named `CODE` is **seeded**
     (`R__seed_permissions.sql`) — so a role *can* be composed to satisfy it. (A required read behind
     a `hasOrMember` floor needs no closure: membership covers it. A required read behind a
     disjunction is satisfied by either disjunct.) This is the F22 spine: it makes "this screen needs
     this strict read" a checked, named fact rather than tribal knowledge.
   - **(c) No phantom in the manifest.** Every code the manifest names resolves in the seed (a
     manifest-scoped echo of `PermissionCodesSeededTest`, so the manifest can't introduce a phantom).

3. **A documented role-composition rule (security-engineer owns it).** When composing or editing any
   operational role, grant the **closure of every closure-bearing required read** of the screens the
   role is meant to operate. The manifest is the lookup table for that closure. This is a provisioning
   discipline, applied via the IAM admin UI / onboarding seed — not a migration.

**Why this split and not the alternatives:** the route-guard-closure option (require the whole read
closure to *open* a screen) is rejected because F21 already removed the runtime block for the
low-sensitivity reads at the **gate** layer (the right place — the picker itself decides), and adding
a second, frontend, copy of "what reads does this screen need" would create a *new* drift surface and
a *new* parity bug class (route-guard-closure ↔ actual-loads), the very family this ADR closes. A
grant-time role-composition *validator* (block a grant that leaves a screen's closure open) is
attractive but needs the same manifest plus runtime wiring into the grant path and a "which screens is
this role meant to operate" input the system doesn't have — more surface, deferred as a future
enhancement, not needed to stop the bleeding. The manifest + CI test is the **minimum honest guard**:
it would have turned ISSUE-001..006 and the F22 deep-run gap **red at build time**, it needs no DB and
no migration, and it has one source of truth that a code reviewer can read.

## Consequences

**Easier**
- A screen that adds a load on open must add a manifest row in the same PR, or check (a) fails the
  moment a reviewer wires its route — the implicit knowledge becomes an explicit, reviewed contract.
- Security-engineer composing a role has a lookup table: "operate screen X ⇒ grant these strict
  reads." The F22 "grant `CUSTOMER.VIEW` to cash/AR, `SUPPLIER.VIEW` to AP" rule stops being a memo
  in a findings file and becomes a checked manifest entry.
- The test is **fast and Dockerless** (surefire, classpath scan + seed-file read + JSON parse), so it
  runs in the PR gate next to `EndpointAuthorizationTest` / `PermissionCodesSeededTest`, completing
  the four-link parity chain (reachable → guard parity → seeded → **read-closure**).

**Harder / constrained**
- The manifest must be **maintained**. Its honesty rests on check (a) tying each row to a real
  controller gate; a screen whose load is *not* in the manifest is *not* protected. We therefore seed
  it with the simulation-exercised screens now and treat "new transaction screen ⇒ manifest row" as a
  review checklist item (mirrors "new gated endpoint ⇒ seed the code").
- The manifest does **not** know the full set of every screen's loads automatically — it is a curated
  contract, not a runtime trace. That is deliberate: a runtime tracer would couple the gate to the
  frontend and re-open drift. The trade is curation effort for a single, readable source of truth.
- This guards the **closure contract**, not any tenant's actual role catalogue (which is runtime
  data). A deployment can still compose a role that omits a required read; the test cannot see that
  role. What it *does* guarantee is that the closure each screen needs is **named, seeded, and gate-
  consistent**, so the composition discipline has a correct, drift-proof table to work from, and the
  `ReferenceDataReadClosureIT` continues to pin the gate-layer behaviour for the F21 reads.

**Honours the prior decisions**
- **F21/F22 gradient preserved.** Low-sensitivity reads stay `hasOrMember` (no closure required —
  check (a) records them as member-floor and check (b) skips them). Sensitive reads
  (`CUSTOMER.VIEW`, `SUPPLIER.VIEW`, `AR.VIEW`) stay `has` and are asserted seeded + closure-bearing —
  exactly F22's "the gate is right, the role must carry the read." A future PR that "helpfully" relaxes
  a sensitive gate to `hasOrMember` flips that read from closure-bearing to member-floor; the manifest
  row (declared `has`) then disagrees with the annotation and check (a) goes red — the manifest pins
  the F22 line the same way `ReferenceDataReadClosureIT`'s strict customer/supplier tests do.
- **Route-guard parity & phantom-code lessons.** No new frontend permission surface is introduced
  (no new parity risk). The manifest cross-checks against the seed (no new phantom surface). The test
  reuses the existing scanner/regex, so there is one permission-parsing implementation, not two.
- **No migration; codes already seeded; one PR.**

## Alternatives considered

1. **Route-guard read-closure (require the full closure to open the screen).** Declare each screen's
   required reads on its Angular route; the guard blocks navigation unless the user holds them all, so
   no mid-screen 403. *Rejected:* it re-introduces the runtime block F21 deliberately removed at the
   gate for low-sensitivity pickers (a member would again be barred from a screen they may use), and it
   creates a **new** drift/parity surface — the route's declared closure vs the component's actual
   loads — which is the exact bug family this ADR exists to close. It also pushes a security-critical
   "what does this screen need" decision into the frontend, away from the gate that owns it. Higher
   risk, lower reversibility (every screen's route now carries closure metadata), no migration saved.

2. **Grant-time role-composition validator (block/warn on an open closure when granting a role).**
   At grant time, compute the read closure of the role's intended screens and refuse/warn if a strict
   read is missing. *Rejected for now (deferred, not dead):* it needs the same manifest **plus** a new
   runtime input the system lacks — "which screens is this role meant to operate" — and wiring into the
   grant path. It is the strongest *eventual* guard (it catches the actual tenant's mis-composed role,
   which the CI test structurally cannot), but it is more surface than ISSUE-008 needs to stop shipping
   the gap, and it can be layered on top of the manifest later without rework. The manifest this ADR
   creates is the prerequisite it would consume.

3. **Just keep widening gates (relax every supporting read to `hasOrMember`).** Make the whole problem
   disappear by lowering every picker to a member floor. *Rejected:* it dissolves the F22 line — party
   master (TIN/VRN/credit-limit/balance) and AR open-items would become readable by any role-holder in
   the company (a stock clerk, a POS cashier with no customer remit), a least-privilege regression the
   findings log explicitly declined. It also leaves **no** guard: the next sensitive read added behind
   a strict gate would re-create the gap with nothing to catch it.

4. **A pure capability/integration test per persona (assert each known role can use its screens).**
   *Rejected as the primary mechanism:* persona role sets are runtime/onboarding data, not in the repo,
   so the test would either hard-code a snapshot that drifts from real deployments or need a live
   onboarded DB. Useful as the **sim's** verification (it already is — `ReferenceDataReadClosureIT` +
   the persona re-run), but it cannot be the build-gate guard because there is no canonical role list
   to assert against. The manifest-property test sidesteps that by asserting the screen→read contract,
   which *is* in the repo.

## Grant-time validator (follow-up — now implemented)

> **Status of this section:** Accepted, 2026-06-28. This **flips Alternative #2 ("Grant-time
> role-composition validator"), which the original decision deferred.** The build-time
> `RolePermissionClosureTest` pins the screen→read contract but is structurally blind to a tenant's
> actual mis-composed role (Consequences §"Harder/constrained", final bullet). This follow-up adds the
> **runtime, advisory** half — it inspects a real role's grant set and surfaces the gaps the CI test
> cannot see. It "consumes the same manifest," exactly as the deferral promised: **no new declaration
> surface, no new drift class.**

The validator resolves the input the deferral said the system lacked — *"which screens is this role
meant to operate"* — **not** by adding a new input, but by deriving it: a role is meant to operate
screen X **iff it already holds X's `accessPermission`** (the route-guard permission that opens the
screen). That makes the check a **pure function of `(role grants, manifest)`** with no extra state.

**Three design calls (and why):**

1. **Manifest moves to `backend/src/main/resources/security/screen-read-closure.json` (single copy,
   no second source).** The validator needs it at runtime; a duplicated test-resource copy would be a
   new drift surface — the precise failure family this ADR exists to close. `RolePermissionClosureTest`
   is retargeted to read the **main-resources** copy (main is on the test classpath already), so the
   build gate and the runtime validator share one file. No second JSON, ever.

2. **Each screen gains an `accessPermission` field** = the screen's route-guard / primary-action
   permission, sourced from the controller action's `@PreAuthorize` (verified per screen — see the
   spec's table). It is the same code the Angular `requirePermission(...)` route guard uses, which is
   the existing route-guard↔endpoint parity invariant — so no *new* code is introduced, only named.

3. **Advisory, never blocking.** The validator is a **read-only report**, surfaced on the role-edit
   screen and via a new read-only endpoint. It does **not** gate `PUT .../permissions` — blocking a
   grant would break legitimate compositions (a role may intentionally be view-only for one screen and
   operate another) and regress the F21 least-privilege posture. It informs the admin; the admin
   decides.

**The check (pure function).** For a role with granted codes `G` and manifest `M`: for each screen
`s ∈ M` where `s.accessPermission ∈ G`, compute `missing(s) = { read.code | read ∈ s.reads,
read.necessity = required, gateForm(read) ∈ {has, scoped}, read.code ∉ G }`. (Member-floor
`hasOrMember`/`scopedOrMember`, `disjunction`, and `optional` reads are satisfied otherwise — the
**identical classification** check (b) uses.) Output: `[{ screen, accessPermission, missingReads[] }]`
for every screen with a non-empty `missing(s)`. Root/`ORG_ADMIN`-style blanket holders naturally have
empty gaps because they hold every read; the report is meaningful exactly for the minimally-granted
operational roles that ISSUE-008 was about.

**Surface.** New gated read-only endpoint `GET /api/v1/roles/uid/{uid}/read-closure-gaps`
(`@perm.has('ROLE.VIEW')` — same gate as role read; already seeded, no migration), returning
`List<ScreenReadGapDto>`. The role-edit component renders an advisory panel:
*"This role can open **Record receipt** but is missing **CUSTOMER.VIEW**, **AR.VIEW** — users with
only this role will be blocked on that screen."* Permission codes are shown verbatim (this is an admin
RBAC screen — codes are the working vocabulary; the error-hygiene rule that hides ULIDs/internals from
end users does not apply to admins reasoning about grants). No ULIDs, no exception text.

**Why this is now safe to build (vs the original "more surface than ISSUE-008 needs"):** the manifest
already exists and is gate-honest (checks a–c keep it so), the `accessPermission` is the route-guard
code we already maintain, and the surface is a read-only report — no grant-path mutation, no new
drift class, no migration. It is the layered enhancement the deferral foresaw, not a re-architecture.
See the implementation spec (handed to backend-engineer + web-engineer) for package/endpoint/component
names and the per-screen `accessPermission` verification table.
