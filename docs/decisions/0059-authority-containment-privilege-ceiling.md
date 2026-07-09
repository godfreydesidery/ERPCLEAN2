# 0059 — Authority-containment: a privilege ceiling on every conferral boundary

- **Status:** Accepted
- **Date:** 2026-07-09
- **Deciders:** Owner (godfrey.desidery), Claude (security review)
- **Context source:** Security audit 2026-07-09 (vertical privilege-escalation finding); owner report ("a user without org-admin can create an org-admin and log in as them; a user can assign himself a privileged role")

## Context

RBAC enforces two authorization dimensions, but only one of them is actually guarded:

- **Horizontal (tenant / company)** — `ScopeGuard.assertCanActIn` confines every write to the
  caller's active company. Solid, enforced everywhere.
- **Vertical (privilege level)** — *nothing.* No code asks "is the authority I am conferring within
  the authority I myself hold?"

Because the vertical question is never asked, any principal who can confer authority can confer
**more than they possess**. The 2026-07-09 audit (31-agent workflow, adversarially verified)
confirmed this at four boundaries, all reachable by a *non-root* caller:

1. `UserRoleServiceImpl.grant()` — a `ROLE.MANAGE` holder grants `ORG_ADMIN` (or any role) to any
   user, **including themselves**. The only checks are scope, membership, and duplicate.
2. `RoleServiceImpl.setPermissions()` — a `ROLE.ADMIN` holder attaches **any** permission to a role,
   including codes they do not hold. `ROLE.ADMIN` alone self-escalates by editing a non-system role
   the caller already holds; because roles are org-global this can bleed across companies.
3. `UserServiceImpl.create` + `setPasswordByUid` — a `USER.MANAGE` holder creates an auto-active user
   with a known password, grants it a privileged role, and logs in as it (the "puppet admin").
4. `setPasswordByUid` on a peer — resetting a more-privileged same-company user's password takes over
   their account outright.

What already holds (verified, out of scope for this ADR): `is_root` is unreachable via the API (only
`BootstrapRunner` mints it), root accounts cannot be read/reset by non-root callers, and
cross-*company* grants are blocked by `ScopeGuard`. So escalation tops out at **within-tenant
`ORG_ADMIN`**, never super-admin and (grant path) never cross-tenant.

The threat is intrinsic to the model the product invites: tenants mint their own delegated admin
roles. The moment a role carrying `ROLE.MANAGE`/`ROLE.ADMIN` is delegated, its holder can bootstrap
to full `ORG_ADMIN`. In a pristine DB only `ORG_ADMIN`/root hold these capabilities, so the hole is
*latent* until the first delegation — but delegation is a first-class, expected operation.

Constraints: schema is frozen / additive-only; the fix must fit the existing `PermissionResolver` /
`ScopeGuard` spine; `is_root` must stay exempt (ADR-0001 D-E); seeded `ORG_ADMIN` (whole catalogue,
`R__seed_permissions.sql`) must keep working unchanged.

## Decision

Introduce a single **vertical** guard, `com.erp.platform.security.AuthorityCeiling`, the sibling of
`ScopeGuard`, and call it at every authority-conferring boundary. It enforces one invariant:

> **A non-root caller may only confer authority that is a SUBSET of their own effective permissions
> in their active scope.** Root is exempt; a caller with no resolved permissions can confer nothing
> (fail closed).

On top of the subset rule, a **reserved-permission floor** (owner decision, 2026-07-09): the
"power to delegate" is not itself delegable below org-admin tier. The reserved codes are

```
USER.MANAGE  USER.COMPANY.MANAGE  ROLE.MANAGE  ROLE.ADMIN  BRANCH.ASSIGN
```

To confer *any* reserved code (grant a role containing it, or attach it to a role) the caller must be
root **or hold every reserved code** (i.e. be org-admin-tier). A delegated admin may hand out their
operational powers but never the power to administer users or roles. The reserved set lives in code,
not seed, so a tenant cannot edit it.

Call sites (all service-layer, after the existing scope/membership checks):

- `UserRoleServiceImpl.grant()` — load the role *with* its permissions; a non-root caller may not
  grant a **system** role at all (blocks `ORG_ADMIN`), else subset + reserved floor over the role's
  permission codes. This one check also collapses the puppet-admin chain transitively: a puppet can
  only ever receive a role the creator already holds. Self-elevation is blocked as an *emergent*
  property (you cannot grant yourself what you lack) — no separate "no self-grant" rule is needed.
- `RoleServiceImpl.setPermissions()` — subset + reserved floor over the requested codes (strict
  whole-set subset; a non-root author is capped at their own authority).
- `UserServiceImpl.setPasswordByUid()` — resolve the *target's* effective permissions
  (branch-agnostic, in the caller's active company) and require them to be a subset of the caller's:
  you may not reset the password of a user who outranks you. Applied to `setPasswordByUid` **only** —
  `update`/`disable`/`enable`/`unlock` confer no authority and gating them would break help-desk work
  for no escalation gain.

Detective layer: a privileged (reserved-carrying) grant emits a distinct `ROLE.GRANT.PRIVILEGED`
audit action in addition to `ROLE.GRANT`.

Regression net: a unit test for the invariant, refusal integration tests at all three boundaries
(the pre-existing ITs only asserted grants *succeed* — the exact blind spot that let this ship), and
a negative seed-fence assertion that no shipped operational bundle carries a reserved code.

## Consequences

- **Closes** all confirmed vertical-escalation paths with one invariant, no schema change, no new
  permission code, no DTO/controller change. Pure service layer; reuses `PermissionResolver.resolve`
  (same cache/TTL/`invalidate`) so the ceiling can never diverge from what the caller can actually do.
- **`ORG_ADMIN` and root are unaffected** — `ORG_ADMIN` holds the whole catalogue so every subset
  check passes within its tenant; root short-circuits everywhere.
- **Delegated administration is now capped.** A tenant that genuinely wants a sub-`ORG_ADMIN` user or
  role administrator must grant `ORG_ADMIN` (or wait for the deferred explicit-delegation model,
  below). This is the intended trade-off of the reserved floor.
- **Behaviour change surfaces as 403** on the (previously silently-succeeding) escalation attempts;
  the message never names the offending code (error-hygiene rule).
- The subset ceiling is **scope-relative** (resolved in the caller's active company/branch). A grant
  is already forced by `ScopeGuard` to the caller's active company, so this is correct — but a future
  "grant into another company" feature must re-derive the ceiling in the *target* scope.
- **Deferred (not in this change), tracked for a later ADR:** (Phase 2) decouple credential control
  from user creation (invite flow, forced rotation, token-revoke on admin reset — needs an additive
  `invite_token` migration and owner approval); (Phase 3) an explicit "grantable roles" delegation
  model and/or two-person approval for privileged grants via the approvals engine; an ArchUnit
  tripwire that fails the build if a new conferral path skips the ceiling.

## Alternatives considered

- **Pure subset, no reserved floor.** Simpler (one rule). Rejected by the owner: it still lets two
  peers who both hold `ROLE.MANAGE` keep conferring it to each other (horizontal admin spread), which
  is exactly the "a user assigns himself/others a privileged role" concern. The reserved floor is the
  small extra rule that makes admin non-self-propagating.
- **Block `is_system` roles only (the naive fix).** Would stop the direct `grant(ORG_ADMIN)` path but
  miss the build-your-own-superrole variant (`ROLE.ADMIN` mints a non-system role with the full
  catalogue, then grants it). The subset invariant is what closes both; the `is_system` block is kept
  only as a clearer, defence-in-depth failure for the `ORG_ADMIN` case.
- **Explicit "administrable roles" allow-list per admin role.** A first-class delegation model
  (a role declares which roles/permissions its holder may confer). More expressive, but needs schema
  and a policy-seed and is overkill for closing the threat. Deferred to a later ADR; the implicit
  subset rule is the correct default now.
- **Two-person / approval-engine gate on privileged grants.** Real segregation of duties, but adds a
  pending-grant state machine and workflow latency. Deferred; not required to close the hole.
