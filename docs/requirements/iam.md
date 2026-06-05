# Requirements — IAM (Identity & Access Management)

> Status: **RATIFIED (2026-06-05)** — all discovery rounds + open questions closed with the owner.
> Author: system-analyst · Module: `iam`. This is the business-level spec. Schema, API shapes, and
> package layout are the solutions-architect's job next — see [DATA-MODEL.md] (TBD).

## 1. Business context & why first

IAM is the **spine** of the ERP. It owns *who can log in* (identity), *what they can do* (access
control), and the *organisation → company → branch* structure that every other module is scoped
by. No transactional module can be built before it: every transactional table carries
`company_id` + `branch_id` and every protected endpoint is gated by a permission IAM defines.

## 2. Scope

### In scope (v1 — "lean spine")
- Org structure: organisation → company → branch, with default branch and branch assignment.
- Identity: app user, login / logout, password policy, **admin-driven** password reset, account
  lockout, JWT access + refresh tokens.
- Access control: role, permission, role→permission, user→role (company-wide or branch-scoped).
- User↔branch assignment with exactly one default branch per user (the headline requirement).
- Super-admin (root) that transcends company/branch scoping.
- Fresh-DB bootstrap: auto-create organisation + company + default branch + root admin from env.
- Audit of IAM actions.

### Out of scope (deferred — flagged, not built in v1)
- MFA / 2FA · SSO / external IdP (Google, LDAP/AD) · API keys / service accounts ·
  **self-service** profile editing and self-service password reset by email ·
  employee↔user linkage (HR). Each becomes its own requirement when prioritised.

## 3. Actors / personas
- **Super-admin (root)** — manages all companies/branches, seeds data, recovers lockouts. Bypasses
  scoping. Bootstrap-created; tightly held; every action audited.
- **Company / org administrator** — manages users, roles, and branch assignments within the
  companies they administer.
- **Standard user** — logs in, works within their assigned company/branch, switches branch.

## 4. Decisions ratified (rounds 1–3)

| # | Decision | Ruling |
|---|---|---|
| D1 | IAM v1 depth | **Lean spine** (see scope). MFA/SSO/API-keys/self-service deferred. |
| D2 | Multi-company | **One organisation, many companies.** Full org→company→branch built now. |
| D3 | Role scoping | **Both** — a `user_role` is company-wide (all branches) *or* one branch. |
| D4 | Default-branch removed | **Auto-fallback** to the **earliest-assigned** remaining branch. |
| D5 | User × company | **A user may span multiple companies.** Needs a default company too. |
| D6 | Super-admin | **Yes** — root bypasses scoping; bootstrap-created; fully audited. |
| D7 | Login identity | **Username**, unique **org-wide**. Email optional, for reset only. |
| D8 | Lockout | **5 failed attempts → 15-minute lock.** Counter resets on success. Admin can unlock early. |
| D9 | Token lifetimes | **Access 15 min · refresh 7 days**, refresh rotated (single-use) on each refresh. |
| D10 | Password policy | **≥ 8 chars, basic complexity** (letters + number), bcrypt cost ≥ 12, block common passwords. |
| D11 | Role/permission scope | **Permissions org-wide; roles org-wide**, *assigned* per-company (optionally per-branch). |

## 5. Functional requirements

### Org structure
- **FR-IAM-01** The system supports one **organisation** per deployment.
- **FR-IAM-02** An organisation has one or more **companies** (legal entities). Company-bound master
  data and transactions are scoped by `company_id`.
- **FR-IAM-03** A company has one or more **branches** (physical locations). Each branch has a
  `code` unique within its company.
- **FR-IAM-04** Exactly one branch per company is the company default; a company always has at
  least one branch.

### Identity & authentication
- **FR-IAM-05** A user authenticates with a **username** (unique org-wide) and password. Email is
  optional and used only for password reset.
- **FR-IAM-06** Login issues a **JWT access token (15 min)** + a **refresh token (7 days)**. The
  refresh token is single-use and rotated on each refresh; the old one is invalidated.
- **FR-IAM-07** Logout revokes the refresh token; the access token is denied on its next use
  (server-side revocation list until natural expiry).
- **FR-IAM-08** Passwords: ≥ 8 chars with basic complexity (letters + at least one number), hashed
  with **bcrypt cost ≥ 12**, common passwords rejected. Plaintext is never stored or logged.
- **FR-IAM-09** **Account lockout**: 5 consecutive failed logins lock the account for 15 minutes.
  A successful login resets the counter. An admin or super-admin can unlock early.
- **FR-IAM-10** **Password reset is admin-driven** in v1: an administrator (or super-admin) sets a
  user's password / triggers a reset. Self-service email reset is deferred.

### Access control
- **FR-IAM-11** A **permission** is the atomic access unit, identified by a dot-separated code
  (e.g. `USER.MANAGE`, `BRANCH.ASSIGN`). Permissions are defined **org-wide** (one catalogue),
  seeded via migration. `@PreAuthorize` checks reference permission codes, never role names.
- **FR-IAM-12** A **role** is a named bundle of permissions, defined **org-wide** and reusable
  (e.g. `CASHIER`, `BRANCH_MANAGER`, `ACCOUNTANT`).
- **FR-IAM-13** A **user→role assignment** binds a role to a user **scoped to a company**, and
  **optionally to a single branch**. Branch unset ⇒ the role applies to all branches of that
  company that the user is assigned to.
- **FR-IAM-14** A user may hold different roles in different companies (consequence of D5).

### Branch assignment & default branch (headline)
- **FR-IAM-15** A user can be assigned to **many branches** (across the companies they belong to).
- **FR-IAM-16** A user has **exactly one default branch** at any time. On login the user lands in
  their default branch (and its company = the user's default company).
- **FR-IAM-17** The default branch **must** be one of the user's current assignments. The system
  must not allow a default that isn't assigned.
- **FR-IAM-18** A user may **switch** to any other assigned branch at runtime via a branch-override
  header — **without re-login**. The server verifies the target branch is in the user's
  assignments and that their role scope covers it; otherwise the request is refused.
- **FR-IAM-19** If a user's default branch is **removed** from their assignments, the system
  **auto-promotes** the **earliest-assigned** remaining branch to default. If no assignment
  remains, the user has no active branch and cannot transact until reassigned. **Login still
  succeeds** into a read-only "no branch assigned — contact admin" state (ratified, was OQ-IAM-02).
- **FR-IAM-24** **Branch assignment is decoupled from roles** (ratified, was OQ-IAM-01): an admin may
  assign a user to a branch as "present" without the user holding a role in that company. The user
  can only *act* at a branch where a role also covers it (FR-IAM-20 still requires both for actions).
  Therefore BR-6 is **relaxed**: a branch assignment does **not** require a pre-existing role.

### Authorisation enforcement
- **FR-IAM-20** Authorisation for any company/branch-scoped action requires **both** (a) an active
  branch assignment covering the target branch, **and** (b) a `user_role` whose scope covers that
  company/branch. Failing either ⇒ refuse (fail closed).
- **FR-IAM-21** The **super-admin (root)** transcends company/branch scoping for administration,
  data seeding, and lockout recovery. Super-admin actions are always audited.

### Bootstrap
- **FR-IAM-22** On a fresh database with bootstrap enabled, the system creates organisation +
  first company + that company's default branch + a **root admin** user, with the root password
  taken from an env var (minimum length enforced, common placeholders refused, or the app refuses
  to start). No interactive wizard.

### Audit
- **FR-IAM-23** IAM-significant events are audited (append-only): user create/disable, role grant /
  revoke, branch assignment add/remove, default-branch change, password change/reset, lockout and
  unlock, login success/failure. Each record carries actor, action, target, timestamp, and
  company/branch context.

## 6. Business rules (invariants)
- **BR-1** At most one default branch per user (`is_default` true on exactly one assignment).
- **BR-2** At most one default branch per company.
- **BR-3** A user's default branch ∈ that user's current branch assignments (FR-IAM-17).
- **BR-4** Username is unique across the whole organisation.
- **BR-5** A role assignment's branch (when set) must belong to that assignment's company.
- **BR-6** ~~A branch assignment's branch must belong to a company the user has at least one role
  in.~~ **RELAXED (ratified):** branch assignment is decoupled from roles — a user may be assigned
  to a branch without a role; they simply cannot *act* there without one (FR-IAM-20, FR-IAM-24).
- **BR-7** System roles and the root permission set cannot be deleted (audit anchor).

## 7. Non-functional
- Multi-tenant isolation by `company_id` + `branch_id`, enforced at the repository base
  (PROJECT-CONVENTIONS.md §3.2). Tenant leakage is a release blocker.
- All passwords bcrypt cost ≥ 12; tokens RS256-signed; secrets never committed.
- IAM admin screens meet WCAG 2.1 AA (axe gate).

## 8. Assumptions
- Single-tenant **deployment** (one organisation per running instance); multi-company is *within*
  the organisation, not multi-instance.
- Locale (currency, tax, date format, language) is set in a separate discovery round; IAM itself is
  locale-light (timestamps in UTC, displayed in branch/company time zone).

## 9. Out of scope for v1 (deferred)
MFA, SSO/external IdP, API keys / service accounts, self-service profile + self-service password
reset, employee↔user (HR) linkage. Tracked for a later requirements round.
