# 0002 — RBAC enforcement: permission AND scope (close the cross-company hole)

- **Status:** Accepted
- **Date:** 2026-06-06
- **Deciders:** solutions-architect
- **Context source:** Security review BLOCKER on IAM Slice 3; extends [0001](0001-iam-architecture.md)
  D-A / D-E / D-F; [docs/requirements/iam.md](../requirements/iam.md) FR-IAM-11, 13, 20, 21.

## Context

`@PreAuthorize("hasPermission('COMPANY.MANAGE')")` (the 1-arg form wired to
[PermissionResolver.hasPermission](../../backend/src/main/java/com/erp/platform/security/PermissionResolver.java))
answers **what** the caller may do in their *active* scope — it never compares that scope to the
**target** of the operation. The resolver is correctly company-scoped (ADR-0001 D-E), so the gap is
purely at the call site: `CompanyController.update/archive/get` address **any** company by uid, and
`BranchController` addresses **any** branch by uid. A user with `COMPANY.MANAGE` in active company A
passes the gate and can then edit/archive company B. This is a horizontal-privilege-escalation
(cross-tenant) hole and must close in Slice 3, not be deferred.

Forces:
- **Deny-by-default / fail-closed** (FR-IAM-20): a forgotten scope check must fail *closed*, so the
  check belongs somewhere a method *cannot silently skip*, not in copy-pasted service prologues.
- **DRY**: root bypass + "active company == target company else 403" is one rule; it must have one home.
- **Boundaries** (PROJECT-CONVENTIONS §2, [ModuleBoundaryTest](../../backend/src/test/java/com/erp/architecture/ModuleBoundaryTest.java)):
  controllers may not touch repositories; the security layer already may (precedent: `PermissionResolver`
  uses `UserRoleRepository`).
- **Single-org, multi-company v1** — keep it lean; the boundary that actually exists today is the
  **company**, so scope == company. No org-level or branch-level scope assertion is needed yet.

## Decision

### D-1 — Two enforcement shapes, both declarative on the controller

- **No target (create / list):** keep the 1-arg form. Scope comes from the active context (the active
  company is the implicit target).
  `@PreAuthorize("hasPermission('COMPANY.MANAGE')")`
- **Existing target by uid:** add a 2-arg Spring form
  `@PreAuthorize("hasPermission(#uid, 'company', 'COMPANY.MANAGE')")`, backed by a custom
  `org.springframework.security.access.PermissionEvaluator` whose
  `hasPermission(auth, targetId, targetType, permission)` does **both** checks: the permission
  (delegating to the existing `PermissionResolver`) **and** scope (target's owning company ==
  active company, else deny).

Enable with `@EnableMethodSecurity` and register a `MethodSecurityExpressionHandler` whose
`PermissionEvaluator` is our component (Slice 3 wiring in
[SecurityConfig](../../backend/src/main/java/com/erp/platform/security/config/SecurityConfig.java)).
The 1-arg `hasPermission('CODE')` SpEL stays a thin call into `PermissionResolver` via a security
bean (`@PreAuthorize("@perm.has('CODE')")` style or a custom expression root); the 2-arg goes through
the `PermissionEvaluator` contract.

**Why the evaluator, not a per-service assertion, for target ops:** the check is *declarative and
co-located with the gate it complements* — one annotation states both "what" and "where", and a
method with no annotation is denied by `.anyRequest().authenticated()` + reviewer-visible absence,
rather than silently transacting because someone forgot a `assertSameCompany(...)` line in the
service body. Fail-closed is structural, not disciplinary. The target→company lookup is a repository
read, which **belongs in the security layer** — exact precedent already exists: `PermissionResolver`
injects `UserRoleRepository`. ADR-0001 D-A explicitly chose "permission + scope checks" as IAM's
isolation mechanism (there is no row predicate on IAM tables); the evaluator is where that scope
check lives.

### D-2 — Target uid → owning company, per target type

The evaluator resolves the target's **owning company id** and compares it to the active
`companyId` from `RequestContext`. Resolution rule per `targetType`:

| `targetType` | uid resolves via | owning company |
| --- | --- | --- |
| `organisation` | `OrganisationRepository.findByUid` | **no company scope** — org is global (D-A). Root-only; not gated by the company-scope evaluator. See D-4. |
| `company` | `CompanyRepository.findByUid` | the company's **own** id |
| `branch` | `BranchRepository.findByUid` | `branch.company_id` |
| `user` (grant/revoke) | **not** resolved from the user | the GRANT's `companyId`, from the **request body** (a user spans companies, D5) — **not** a target-uid check. Scoped in the service. See D-3. |

A uid that resolves to nothing ⇒ evaluator returns `false` (deny) and the controller surfaces the
existing 404/403 path; never "allow because target unknown". Branch `create` takes `companyUid` in
the body, so its scope check is the 1-arg active-company form plus a body-vs-active-company assertion
in `BranchServiceImpl.create` (same pattern as D-3), because there is no existing target uid to gate on.

### D-3 — User-targeted grant/revoke: scope the GRANT's company, in the service

`hasPermission(#userUid, 'user', 'USER.MANAGE')` is **wrong** for grant/revoke: a user is global and
spans companies (D5), so the user uid has no single owning company. The scope that matters is the
company the assignment is **made in** — `companyUid` in the grant request body (FR-IAM-13: an
assignment "binds a role to a user scoped to a company"). Therefore:

- Controller gate is the **1-arg permission check only**: `@PreAuthorize("hasPermission('ROLE.GRANT')")`
  (and `'ROLE.REVOKE'`). It confirms the *what*.
- The **scope** assertion ("the grant's company == active company, else 403") lives in
  `UserRoleServiceImpl.grant/revoke`, via the shared guard in D-4, called on the body's `companyUid`.

This is the one case where scope is service-side, because the scoping key is in the *payload*, not a
path uid the evaluator can resolve. It still uses the same DRY guard, so it cannot drift from the
evaluator's rule.

### D-4 — One home for root-bypass + same-company assertion

A single platform component owns the rule so it cannot be forgotten or re-implemented two ways:

- **`ScopeGuard`** (new, `com.erp.platform.security`) exposes:
  - `assertActiveCompany(Long targetCompanyId)` — root short-circuits to allow (FR-IAM-21, audited
    elsewhere); else `RequestContext.get().companyId().equals(targetCompanyId)` or throw 403
    (a `ForbiddenException` mapped to 403 by the existing advice). A null active company (no-branch
    state, FR-IAM-19) ⇒ 403.
  - `companyIdOf(targetType, uid)` — the D-2 resolution table; the **only** place the per-type map lives.
- The **`PermissionEvaluator`** (target ops) calls: root → allow; else
  `PermissionResolver.hasPermission(code)` **AND** `companyIdOf(...)` equals active company. Returning
  `false` (not throwing) is the evaluator contract; Spring renders 403.
- **Services** (the grant/revoke body case, D-3) call `ScopeGuard.assertActiveCompany(grantCompanyId)`
  directly.

Both paths converge on `ScopeGuard`. Root bypass is implemented **once** (mirrors the existing
`PermissionResolver.hasPermission` root short-circuit) and the same-company predicate exists **once**.

### D-5 — Evaluator for target ops; service guard only for the body-scoped case

Recommendation: **evaluator-first.** Every operation with a target **uid in the path** is gated
declaratively (`hasPermission(#uid, type, 'CODE')`). The **only** service-layer scope assertions are
the two payload-scoped cases — `UserRole.grant/revoke` and `Branch.create` — where the scoping
company is in the body, not the path. This keeps list/create-vs-target enforcement uniform (all
declarative) while honouring the deny-by-default concern, and avoids the over-engineering of pushing
*everything* through services (which would re-introduce the "forgot the prologue" failure mode this
ADR exists to kill). Sized for v1: ~2 new platform classes (`ScopeGuard`,
`CompanyScopePermissionEvaluator`) + the 1-arg expression bean + `@EnableMethodSecurity` wiring.

## Consequences

- **Easier / safer:** the cross-company hole closes; "what + where" is one reviewable annotation;
  fail-closed is structural (no annotation ⇒ no access). Branch switching (Slice 5, D-F) will move the
  active company per request and these checks track it automatically — no change needed here.
- **Harder / to watch:** target ops cost **one extra indexed `findByUid`** per request (company/branch
  by `uid`, already unique-indexed per D-G); negligible, not cached (cheap, and correctness > a second
  cache to invalidate). Reviewers must learn the two shapes: **path-uid → evaluator 2-arg**;
  **body-company → service `ScopeGuard`**. The `'targetType'` string is a stringly-typed contract —
  the resolution table (D-2) is the authority; an unknown type ⇒ deny (fail closed), and a unit test
  asserts each known type resolves.
- **Audit (FR-IAM-21/23):** root bypass through `ScopeGuard` is the audit point for cross-company
  admin actions; flagged for the audit slice — `ScopeGuard` is where the "root acted out of scope"
  event is emitted.
- **Delivery cost:** no migration; no DTO/contract change. Controller change = add `@PreAuthorize`
  lines. Plus `@EnableMethodSecurity` + expression-handler bean in `SecurityConfig`. Test cost: an IT
  proving a user with `COMPANY.MANAGE` in company A gets **403** editing company B, and root gets 200.

## ArchUnit / boundary check

No rule violation. `CompanyScopePermissionEvaluator` and `ScopeGuard` live in `com.erp.platform.security`
(not `com.erp.api..`), so the controller→repository ban
([ModuleBoundaryTest.controllersDoNotAccessRepositories](../../backend/src/test/java/com/erp/architecture/ModuleBoundaryTest.java))
is untouched — controllers still call only services and SpEL beans. The security layer reading IAM
repositories is the **established** pattern (`PermissionResolver` → `UserRoleRepository`); the
controller→service→repository layer order is preserved. **One note for the per-module rule** (the
"a module's `repository` is not imported by another module" rule that lands as modules grow):
`com.erp.platform.security` will import `com.erp.modules.iam.repository`
(`Company/Branch/Organisation/UserRoleRepository`). When that rule is authored, **`platform.security`
must be an explicit allowed importer of `iam.repository`** (platform is the cross-cutting spine, not a
peer module) — the same exception `PermissionResolver` already needs. Record it as an `ignoreDependency`
/ allowlist when the rule is written; no exception is needed for today's active rules.

## Rejected alternatives

1. **Scope assertion in every service method** (no evaluator). Shares one path with list/create, but
   re-introduces the exact failure this ADR closes: a method missing the `assertSameCompany(...)`
   prologue transacts silently — fails *open* by omission. Rejected: not fail-closed; not reviewer-visible.
2. **2-arg `hasPermission(#userUid,'user','USER.MANAGE')` for grant/revoke.** Wrong scoping key: a
   user spans companies (D5), so the user uid has no single owning company; the grant's company
   (request body) is the real scope. Rejected as incorrect, not merely inelegant. (See D-3.)
3. **Embed active company + a target-company claim in the JWT and compare in a filter.** No per-request
   DB read, but the target company isn't known until the path uid is resolved, and it duplicates
   ADR-0001 D-E's deliberate resolve-per-scope. Rejected: pushes business lookup into the token/filter
   and fights D-E.
4. **Blanket tenant row-predicate on IAM tables** (so a cross-company target simply isn't found).
   Already rejected in ADR-0001 D-A: IAM administration is inherently cross-branch and the super-admin
   must reach every company; a row predicate fights the use case. The scope check is the right tool here.

## Concrete controller contracts (Slice 3)

```text
# CompanyController
GET    /companies                      list      @PreAuthorize("hasPermission('COMPANY.VIEW')")     # scope = active company / org-read
GET    /companies/uid/{uid}            get       @PreAuthorize("hasPermission(#uid,'company','COMPANY.VIEW')")
POST   /companies                      create    @PreAuthorize("hasPermission('COMPANY.MANAGE')")   # active company is target
PUT    /companies/uid/{uid}            update    @PreAuthorize("hasPermission(#uid,'company','COMPANY.MANAGE')")
DELETE /companies/uid/{uid}            archive   @PreAuthorize("hasPermission(#uid,'company','COMPANY.MANAGE')")

# BranchController
GET    /branches?companyUid=...        list      @PreAuthorize("hasPermission('BRANCH.VIEW')")       # + service: companyUid == active company
GET    /branches/uid/{uid}             get       @PreAuthorize("hasPermission(#uid,'branch','BRANCH.VIEW')")
POST   /branches                       create    @PreAuthorize("hasPermission('BRANCH.MANAGE')")     # + BranchServiceImpl.create: ScopeGuard.assertActiveCompany(company.id)
PUT    /branches/uid/{uid}             update    @PreAuthorize("hasPermission(#uid,'branch','BRANCH.MANAGE')")
PUT    /branches/uid/{uid}/default     setDefault@PreAuthorize("hasPermission(#uid,'branch','BRANCH.MANAGE')")
DELETE /branches/uid/{uid}             archive   @PreAuthorize("hasPermission(#uid,'branch','BRANCH.MANAGE')")

# OrganisationController  (global, read-only, single-org v1)
GET    /organisations                  list      @PreAuthorize("hasPermission('ORG.VIEW')")          # no company-scope (D-2: org is global)
GET    /organisations/current          current   @PreAuthorize("hasPermission('ORG.VIEW')")

# Role / UserRole controllers (Slice 3)
POST   /user-roles                     grant     @PreAuthorize("hasPermission('ROLE.GRANT')")        # + UserRoleServiceImpl.grant: ScopeGuard.assertActiveCompany(body.companyId)
DELETE /user-roles/uid/{uid}           revoke    @PreAuthorize("hasPermission('ROLE.REVOKE')")       # + service: ScopeGuard.assertActiveCompany(assignment.companyId)
# Role catalogue is org-wide (FR-IAM-12); role CRUD gates on ROLE.MANAGE with no company scope.
```

> `COMPANY.VIEW`/`BRANCH.VIEW`/`ORG.VIEW`/`ROLE.GRANT`/`ROLE.REVOKE` must exist in the seeded
> permission catalogue (FR-IAM-11); confirm the seed migration before wiring. `COMPANY.MANAGE` /
> `BRANCH.MANAGE` are already seeded.
