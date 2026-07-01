# ADR-0046: Authoritative user↔company membership (assign-company-first)

- **Status:** Accepted (2026-06-26)
- **Date:** 2026-06-26
- **Deciders:** Owner + Solutions Architect
- **Supersedes (in part):** ADR-0045 — the *non-authoritative / additive* stance and the
  auto-create-on-grant behaviour. The `user_company` table, the `USER.COMPANY.MANAGE` permission,
  and the explicit assign/remove API from ADR-0045 are **retained**.
- **Re-decides:** BR-6 (branch assignment decoupled from role grants) — see Decision §2.
- **Related:** ADR-0001 (IAM; users org-wide, default company from default branch), ADR-0002
  (permission/grant scoping), ADR-0045 (explicit membership). UI delivered in PR #142.

## Context

ADR-0045 shipped `user_company` as a **non-authoritative, additive** membership oracle: a user is
"in" company C if they hold an active `user_role` in C **or** an active `user_branch` in C **or** an
active `user_company(user, C)` row, and granting a role / assigning a branch **auto-creates** the
membership row (`UserCompanyServiceImpl.ensureMembership`, `REQUIRES_NEW`). ADR-0045 explicitly
deferred the authoritative phase to "a future ADR + owner sign-off". This is that ADR.

Forces now pushing toward authoritative:

1. **A new product requirement (PR #142).** The user "Assignments" screen presents the workflow as
   *assign a company → then its branches/roles become available*; branch/role company pickers are
   scoped to the user's memberships. Today that is a **UI-only** restriction — the backend still
   auto-creates membership, so a direct API call (or any future client) can still grant a role /
   assign a branch in a company the user was never assigned to. The UI promises a guarantee the
   backend does not keep.
2. **A single, intentional membership decision.** Authoritative membership makes "which companies is
   this user part of?" a deliberate admin action rather than a side effect of the first grant, which
   matches how the owner wants tenancy administered.

Constraints bounding the solution: the schema is **frozen / additive-only** and the DB is durable
in every environment (no wipe); data fixes go through **app-code provisioning, not Flyway data
migrations**; company **isolation** is already sound and was just hardened (do not regress it);
**root** is always exempt from tenancy.

## Decision

Make `user_company` **authoritative for the write path**: a user must be an explicit member of a
company before any branch or role can be assigned in that company.

### 1. Assign-company-first (write-path gate)
- `UserRoleServiceImpl.grant` and `UserBranchServiceImpl.assign` **require an active
  `user_company(user, targetCompany)` row**, else reject with **409** and a friendly message
  (e.g. *"Assign this user to the company before granting roles / assigning branches."*).
- **Remove** the `ensureMembership(...)` auto-create calls from both methods. Gaining a role or a
  branch no longer fabricates membership; membership is granted only via the explicit
  `POST /api/v1/user-companies` (`USER.COMPANY.MANAGE`).

### 2. Re-decide BR-6
BR-6 decoupled branch assignment from role grants. That **independence is preserved** — you still do
not need a role to assign a branch, nor a branch to grant a role. What changes is that **both now
share one prerequisite: prior company membership.** BR-6 is amended to:
> *Branch assignment and role grant remain independent of each other, but each requires the user to
> already be an explicit member (`user_company`) of the target company.*

### 3. Company removal becomes guarded (block, not cascade)
`UserCompanyServiceImpl.remove` **rejects with 409 while the user still has any active role grant or
branch assignment in that company** (*"Remove this user's roles and branches in <company> first."*).
No silent cascade-revoke — stripping access must be an explicit, visible action. (A future explicit
"remove from company **and** all its access" power-action with confirmation is out of scope here.)

### 4. Read/isolation oracle stays additive (defensive superset)
`AppUserRepository.existsUserInCompany` and `CompanyServiceImpl` keep the additive predicate
(role OR branch OR `user_company`). After §1, every active role/branch **implies** a membership row,
so additive ≡ `user_company`-only — but keeping it additive means **no change to the
just-hardened isolation reads** and no risk of a legacy row locking a user out. Authoritativeness is
enforced where it matters (writes); reads remain a safe superset.

### 5. Data coverage before enforcement (no migration)
Enforcement only flips after every `(user, company)` reachable by an active role/branch has a
`user_company` row, so no existing user is blocked. Coverage today should already be complete
(V77 backfill + the auto-create that has run since). Per the durable-DB **"provisioning over data
migrations"** rule we make `UserCompanyBackfill` an **idempotent reconcile** (insert any missing
membership for existing active grants/branches; safe to re-run) and run it at deploy **before**
the gate goes live. **No Flyway migration and no schema change** — `V77` already provides the table
and the partial-unique index. Enforcement is **service-layer** (consistent with how all company
scoping is enforced), not a DB trigger/constraint.

### 6. Scope of the gate / unchanged
- The membership prerequisite applies to **all callers** — it concerns the *target user's* data
  consistency, not the caller's authority. **Root** continues to bypass tenant **scope**
  (`ScopeGuard.assertCanActIn`) but still follows assign-company-first (it does not auto-create the
  membership). This keeps the invariant truly invariant (no orphan grants from any path).
- **Bootstrap/seeders are unaffected:** they persist `UserBranch`/`UserRole` rows **directly**
  (e.g. `BootstrapRunner` saves the root admin's default branch), never through the gated
  `UserBranchService.assign` / `UserRoleService.grant`, which are reached only from the admin REST
  controllers. So startup never hits the gate.
- Unchanged: permissions (`USER.COMPANY.MANAGE`, `BRANCH.ASSIGN`, `ROLE.MANAGE`); the existing root
  tenant-scope exemption; default company derived from the default branch (ADR-0001 D-B);
  `X-Branch-Uid` branch-switch validation.

## Consequences

- **Positive:** the UI's "assign a company first" promise is now actually enforced; membership is a
  deliberate, queryable fact; "half-provisioned" users (access with no explicit membership) can no
  longer be created via any path; no isolation regression (reads unchanged).
- **Breaking (intended) API behaviour change:** clients that previously relied on grant/assign
  auto-creating membership now get a **409** until the company is assigned. This is the point of the
  change; document it in the IAM API guide and surface the message calmly in the UI (PR #142 already
  shows the empty-state hint, so the happy path avoids the 409).
- **Removal is stricter:** removing a company membership is blocked while roles/branches remain
  (previously a no-cascade soft-revoke). Admins must clear access first.
- **Watch:** the idempotent reconcile must run before the gate in every environment; if it ever finds
  gaps, that indicates a prior path that bypassed auto-create — log and reconcile, do not fail boot.
- **Reversibility:** moderate. Reverting = restore the two `ensureMembership` calls and drop the
  three guards; no data is destroyed (membership rows remain valid under the additive oracle too).

## Alternatives considered

1. **UI-only filtering (no backend change).** Scope the pickers in PR #142 and stop there.
   Rejected: bypassable via the API, and it makes the UI assert a guarantee the backend doesn't hold
   — exactly the confused-state we have been removing this cycle.
2. **DB-level enforcement (trigger / cross-table constraint).** Forbid a `user_role`/`user_branch`
   without a matching `user_company` in the database. Rejected: cross-table invariants need triggers
   (not in this codebase's vocabulary), it is a schema change against a frozen schema, and it
   duplicates enforcement that the service layer already owns everywhere else.
3. **Cascade-revoke on company removal.** Removing a company silently revokes its roles/branches.
   Rejected as the default: destructive and easy to fire by accident; silent access changes are a
   security smell. Blocking with a clear message is safer and reversible; an explicit confirmed
   power-action can be added later if desired.

## Amendment (2026-07-01, F27) — create-time membership

Creating a user **as a non-root admin** now establishes that user's membership in the **creator's
active company**, in the same transaction as the user save (`UserServiceImpl.create`). This is the
create-time analogue this ADR did not cover: ADR-0046 removed the *implicit, grant-triggered*
auto-membership, but user creation is itself the explicit, deliberate moment a company admin brings a
user into their own scope. Without it, a non-root admin's newly-created user had no company and was
invisible in the company-scoped `list()` (visible only to root) — see finding F27. Root / no-company
creators still leave the user unassigned. The authoritative-membership invariants (assign-company-first
for role/branch grants; remove-blocks-while-access-remains) are unchanged.
