# ADR-0045: Explicit, non-authoritative user↔company membership (`user_company`)

- **Status:** Accepted (2026-06-25)
- **Superseded in part by:** ADR-0046 (the *authoritative phase* — flips the write path to
  assign-company-first and drops auto-create; the table, permission, and assign/remove API here stay).
- **Deciders:** Owner + Solutions Architect
- **Supersedes (in part):** ADR-0001 D-A reasoning that company membership is *purely derived*.
- **Related:** ADR-0001 (IAM architecture; users org-wide, default company from default branch), ADR-0002 (permission/grant scoping), BR-6 (branch assignment decoupled from role grants).

## Context

A user's company membership has been **derived implicitly**: a user is "in" company C if they
hold an active `user_role` (`company_id = C`) **or** an active `user_branch` whose branch belongs
to C. There was no first-class "this user belongs to company C" record. This caused two practical
problems:

1. **Definition divergence.** The company picker (`CompanyServiceImpl.listAccessibleByOrganisationUid`)
   keyed off role grants *only*, while the user-list scoping (shipped 2026-06-25) used role *or*
   branch — so a branch-only user appeared in lists but could not see their own company in the picker.
2. **No "assign a user to a company" operation.** Admins had to separately create a branch
   assignment and a role grant; there was no single, explicit membership concept, and half-provisioned
   users were easy to create (a grant in a company with no usable branch → permissions resolve but the
   user can never obtain an active context there).

The owner wants an explicit **many-to-many** "stick users to specific companies" model, root exempt.

## Decision

Add an explicit `user_company` junction (M:N), **non-authoritative for now** (authoritative
enforcement is a deliberate future phase — see below).

- **Additive membership oracle.** A user is a member of company C if they have an active
  `user_role` in C **OR** an active `user_branch` in C **OR** an active `user_company(user, C)` row.
  `user_company` only ever *adds* members; it never blocks a grant/branch and never strips access.
- **Auto-create.** Granting a role (`UserRoleServiceImpl.grant`) or assigning a branch
  (`UserBranchServiceImpl.assign`) calls `UserCompanyService.ensureMembership(...)` so a membership
  row always exists when access is gained. `ensureMembership` runs in its **own transaction**
  (`REQUIRES_NEW`) and is idempotent + exception-safe, so it can never fail the surrounding grant.
- **Explicit assign/remove** via `POST/DELETE /api/v1/user-companies`, gated by the new permission
  `USER.COMPANY.MANAGE`. **Remove is a soft-revoke of the membership row only** (no cascade to
  roles/branches) — appropriate while non-authoritative.
- **Predicate unified.** The company picker and the user-list now both use the additive oracle
  (role OR branch OR `user_company`), eliminating the divergence above.
- **Root** bypasses membership entirely (consistent with existing tenancy).
- **Default company stays derived** from the default branch (ADR-0001 D-B); `user_company` carries
  no `is_default` to avoid two competing "default company" sources.
- **Backfill** is done in application code (`UserCompanyBackfill`, guarded by `count() == 0`,
  uids via `UidEntity`), not a SQL data migration — consistent with the durable-DB "provisioning over
  data migrations" practice. At t0 the backfilled set equals the existing derived set → no behaviour
  change.

Schema: `V77__user_company.sql` (additive, created empty → inline constraints; partial unique index
`(user_id, company_id) WHERE revoked_at IS NULL`). Permission: `USER.COMPANY.MANAGE` in the
repeatable `R__seed_permissions.sql`.

## Why non-authoritative (not a hard gate) — for now

Making `user_company` **authoritative** (a hard "must be assigned to the company before any
grant/branch") would:
- not improve isolation — that is already enforced by the company-scoped `PermissionResolver`,
  `ScopeGuard`, per-request `X-Branch-Uid` validation, and the user-list scoping; and
- **reverse BR-6**, which deliberately decoupled branch assignment from role grants.

So authoritative enforcement is deferred to a future ADR + owner sign-off. The additive model gives
the explicit "stick users to companies" capability and fixes the divergence **without** reversing a
ratified decision or introducing a silent denier.

## Consequences

- **Positive:** explicit, queryable membership; a single unified membership predicate; a "Companies"
  tab on the user-detail screen; "assign a user to a company before granting" is now possible; no
  behaviour regression (additive); no isolation change.
- **Negative / watch:** `user_company` is a third membership source that must stay roughly in sync
  (mitigated by auto-create + backfill); a `REQUIRES_NEW` membership row may persist if the outer
  grant later rolls back (benign — it's the "assigned but not yet granted" state we support).
- **Future (authoritative phase):** flip the oracle to `user_company`-only, enforce "assigned before
  grant", and switch Remove to a (soft) cascade — recorded in a follow-up ADR, with BR-6 explicitly
  re-decided.
