# IAM — Build Plan

> Owner: project-manager · Date: 2026-06-05 · Module: `iam`
> Inputs: [iam.md](requirements/iam.md) (RATIFIED) · [ADR-0001](decisions/0001-iam-architecture.md) ·
> [DATA-MODEL.md](../DATA-MODEL.md) · [ARCHITECTURE.md](../ARCHITECTURE.md) · [USER-STORIES.md](../USER-STORIES.md)

## Headline
Build IAM in **7 slices**, dependency-ordered. Slice 0 scaffolds both apps; slices 1–6 deliver the
module bottom-up (structure → identity → access → assignment → switching → audit/admin UI). Each
slice is independently deployable and exercises the full stack relevant to it. Estimates are in
**days** for a one-engineer-plus-review cadence (one logical change per PR). Total ≈ **15–20 days**.

> Sequencing principle: nothing transactional is built before the spine it depends on. Within IAM:
> org/company/branch and the security plumbing come before users; users before roles; roles before
> assignment; assignment before branch-switching; everything before the admin UI that drives it.

---

## Slice 0 — Project scaffold & platform spine  *(prereq for all)*
**Goal:** an empty-but-running Spring Boot API + Angular app, wired to Postgres, with the
cross-cutting plumbing every slice needs. No business feature yet.
- **Backend:** Maven project, `com.erp` base, Spring Boot 3.3 / Java 21; `application.yml` +
  `application-dev.yml`; Flyway baseline (empty); `ApiResponse<T>` + `ResponseBodyAdvice` wrapper;
  `UidEntity` base + ULID generator; global error model; `IdLongAsString` Jackson config;
  `ModuleBoundaryTest` (ArchUnit) skeleton; health endpoint.
- **Web:** `ng new` standalone + routing + strict TS; `core/` (HTTP interceptor that unwraps
  `ApiResponse<T>`, attaches JWT + `X-Branch-Uid`, error toast, spinner); `layout/` shell; lint/prettier.
- **DevOps:** `docker-compose.yml` (Postgres 15 + the API); `.dockerignore`; `.gitignore`; a `README`
  run section. *(devops-engineer)*
- **Prereqs:** none. **Touches:** new repos/dirs. **Agents:** backend, frontend, devops.
- **Acceptance:** `mvn test` green (ArchUnit + health), `ng build` green, `docker compose up` serves
  the health endpoint and the empty web app; web can call the health endpoint through the interceptor.
- **Est:** 2–3 d.

## Slice 1 — Org structure: organisation → company → branch  *(US-IAM-004)*
**Goal:** the scoping tree exists and is manageable; the company-default-branch invariant holds.
- **Backend:** migrations + entities for `organisation`, `company`, `branch`; the
  `uq_branch_company_default` partial unique index; services with the "set default → clear old in one
  TX" rule; CRUD controllers (`companies`, `branches`) by uid; `BRANCH.MANAGE`/`COMPANY.MANAGE`
  permission seeds (gates inert until Slice 3 wires auth — see note).
- **Web:** company list/edit, branch list/edit (with set-default), under `features/admin`.
- **Prereqs:** Slice 0. **Acceptance:** create company → create branches → set one default; second
  default clears the first (DB-enforced); axe green on the screens.
- **Est:** 2–3 d.
- *Note:* permission gates are seeded now but only **enforced** once Slice 3 lands. Until then admin
  endpoints are dev-open; do **not** deploy past dev without Slice 3.

## Slice 2 — Identity & authentication  *(US-IAM-001, US-IAM-002, US-IAM-009)*
**Goal:** users can be created and can log in/out; tokens + lockout + bootstrap work. This is the
security backbone.
- **Backend:** `app_user`, `refresh_token` migrations + entities; bcrypt (cost ≥ 12); password policy
  validator; JWT issue/verify (RS256; dev in-memory key); `auth/login|refresh|logout` with rotation,
  reuse detection, and the **5-fail/15-min lockout**; access-token denylist (in-memory dev);
  **bootstrap** runner (org + company + default branch + root user from env, idempotent); `RequestContext`
  filter populating user/company/branch from the JWT (override header validation deferred to Slice 5).
- **Web:** login page, token storage + refresh handling in `core/`, logout, "locked/disabled" messages.
- **DevOps:** bootstrap env contract (`ERP_BOOTSTRAP_*`) wired into compose + `.env.example`. *(devops)*
- **Prereqs:** Slice 0 (+ Slice 1 for the bootstrap company/branch). **Acceptance:** fresh DB boots a
  root admin from env; login issues tokens and lands in the default branch; 5 bad passwords → 15-min
  lock; refresh rotates and reuse is rejected; logout revokes. **security-engineer reviews** auth path.
- **Est:** 3–4 d.

## Slice 3 — Access control: roles, permissions, enforcement  *(US-IAM-007, US-IAM-008)*
**Goal:** RBAC is real — endpoints are gated by permission resolved per active scope; super-admin works.
- **Backend:** `permission`, `role`, `role_permission`, `user_role` migrations + entities; seed the
  org-wide permission catalogue + `ORG_ADMIN` (and obvious roles); `PermissionResolver`
  (effective perms per user+company+branch, short-TTL cache, bust on grant/revoke);
  `@PreAuthorize("hasPermission(...)")` wired and **turned on across IAM endpoints**; `is_root`
  short-circuit; role CRUD + grant/revoke endpoints; the both-required rule (assignment + role) stubbed
  (full check completes in Slice 4/5).
- **Web:** roles list/edit (attach permissions), assign/revoke role to user (scope: company, optional
  branch), under `features/admin`.
- **Prereqs:** Slices 1–2. **Acceptance:** a user lacking a permission gets 403; granting the role
  flips it to 200; super-admin bypasses scoping; **every IAM endpoint now permission-gated** (the
  dev-open window from Slices 1–2 closes here). **security-engineer permission audit.**
- **Est:** 3 d.

## Slice 4 — Branch assignment & default branch  *(US-IAM-006)*
**Goal:** the headline requirement — a user assigned to many branches with exactly one default;
decoupled from roles; auto-fallback on removal.
- **Backend:** `user_branch` migration + entity; `uq_user_branch_default` partial unique index;
  assign/remove/set-default endpoints; "set default → clear old in one TX"; **default-must-be-assigned**
  (structural); **auto-fallback to earliest-assigned** on removal (`assigned_at` + id); assignment
  **decoupled** from roles (present-without-role allowed).
- **Web:** on the user screen — assign branches (multi), mark default (clear marker on the rest),
  remove (showing the fallback result).
- **Prereqs:** Slices 1–3. **Acceptance (the money tests):** assign 3 branches, set one default;
  setting another clears the first; can't set an unassigned default; remove the default → earliest of
  the remaining becomes default; remove the last → user has no active branch; assign-without-role
  succeeds but the user can't act there. **qa-engineer** writes these as integration tests on real
  Postgres + an e2e.
- **Est:** 2–3 d.

## Slice 5 — Branch switching at runtime  *(US-IAM-003)*
**Goal:** a multi-branch user switches active branch without re-login; the override is validated and
permissions re-resolve.
- **Backend:** `RequestContext` honours `X-Branch-Uid`; **validate the branch is in the caller's active
  assignments** (else 403); active company = override branch's company; `PermissionResolver`
  re-resolves for the new scope. No DB write on switch.
- **Web:** branch selector in the shell (reads the user's assigned branches, persists the active one,
  sends the header); "no branch" read-only state for no-assignment users.
- **Prereqs:** Slices 2–4. **Acceptance:** switch to an assigned branch → requests scoped to it;
  switch to an unassigned branch → refused; a permission that exists only at branch B is denied at A
  and allowed after switching. **security-engineer reviews** the override boundary (highest-risk surface).
- **Est:** 2 d.

## Slice 6 — IAM audit trail & admin polish  *(US-IAM-010, US-IAM-005 finish)*
**Goal:** every access change is recorded append-only; admin surface is complete and accessible.
- **Backend:** `audit_log` migration; the **audit aspect** emitting on user/role/branch/default/
  password/lockout/login events; deploy grants the app DB role no UPDATE/DELETE on `audit_log`;
  finish `users` (set-password, unlock) if not already.
- **Web:** audit view (read-only, filterable), finish user admin (disable/unlock), empty/error/loading
  states across IAM screens.
- **Prereqs:** Slices 1–5. **Acceptance:** each listed action writes an audit row with actor/action/
  target/scope/time; audit rows can't be edited/deleted via the app; full IAM admin walkthrough by
  **end-user** + **qa-engineer** gate (tests + axe green).
- **Est:** 2–3 d.

---

## Dependency graph
```
S0 scaffold ──► S1 org/company/branch ──► S2 identity/auth ──► S3 RBAC ──► S4 branch assignment ──► S5 switching ──► S6 audit/polish
                       (S2 bootstrap needs S1's company/branch)        (S5 needs S4 assignments)
```

## Cross-cutting gates (every slice)
- ArchUnit `ModuleBoundaryTest` green · `mvn test` green · web unit + Playwright/axe green for any UI.
- **security-engineer** review on auth (S2), RBAC (S3), and the override boundary (S5) before those
  slices sign off.
- Pre-stable schema: **edit the baseline + recreate DB**, don't stack migrations, until IAM stabilises
  (after S6) — then freeze and switch to additive migrations.

## Risks & open items
| # | Risk | Mitigation | Owner |
|---|---|---|---|
| R1 | Permission gates seeded in S1 but enforced in S3 → a dev-open window | Don't deploy past dev before S3; track as a release blocker | PM |
| R2 | Production JWT key is in-memory (dev) → everyone logged out on restart, not prod-safe | Stable RS256 from a secret store before any prod deploy | security + devops |
| R3 | `PermissionResolver` cache staleness on grant/revoke | Bust cache on write; short TTL backstop; integration test the flip | backend |
| R4 | Auto-fallback edge (concurrent removal of two assignments) | Do removal+fallback in one TX; `assigned_at`+id total order | backend |
| R5 | Scope creep from deferred items (MFA/SSO/self-service) leaking into v1 | Out-of-scope list in iam.md is the contract; new asks → new requirement round | PM + analyst |

## What I need from the owner to start
- **Go/no-go on Slice 0 scaffold** (this is the only thing blocking build). My recommendation: yes —
  start S0 now; it's pure plumbing to the ratified conventions, no business risk.
- Product/brand name + base package: still `com.erp` placeholder. Not a blocker for S0–S6 (rename is a
  one-time ADR later), but cheaper to set before S0 if you have a name.
