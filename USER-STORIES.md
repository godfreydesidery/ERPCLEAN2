# User Stories

Stories are `US-<MODULE>-NNN`. Acceptance criteria are Given/When/Then and must be testable.
Source of truth for what a screen/endpoint should do. Authored by system-analyst.

---

## IAM — Identity & Access Management

Requirements: [docs/requirements/iam.md](docs/requirements/iam.md). Status: DRAFT pending sign-off.

### US-IAM-001 — Log in
**As a** user **I want** to log in with my username and password **so that** I can access the ERP
scoped to my company and branch.
- **AC1** Given valid credentials, when I log in, then I receive an access token (15 min) and a
  refresh token (7 days) and land in my **default branch** (and its company).
- **AC2** Given invalid credentials, when I log in, then I am refused with a generic error (no hint
  which of username/password was wrong) and my failed-attempt counter increments.
- **AC3** Given 5 consecutive failures, when I attempt a 6th, then the account is locked for 15
  minutes and I am told it is locked.
- **AC4** Given a successful login, then my failed-attempt counter resets to 0.

### US-IAM-002 — Refresh & log out
**As a** user **I want** my session to refresh seamlessly and to log out **so that** I stay signed
in safely and can end my session.
- **AC1** Given a valid refresh token, when it is used, then a new access+refresh pair is issued and
  the used refresh token is invalidated (single-use rotation).
- **AC2** Given a refresh token already used once, when it is presented again, then it is rejected.
- **AC3** Given I log out, then my refresh token is revoked and my access token is denied on next use.

### US-IAM-003 — Switch branch without re-login
**As a** user assigned to several branches **I want** to switch my active branch **so that** I can
work across the locations I cover.
- **AC1** Given I am assigned to branches A and B, when I switch to B (branch-override header), then
  subsequent requests are scoped to B without re-login.
- **AC2** Given I request a branch I am **not** assigned to, then the request is refused.
- **AC3** Given my role does not cover the target branch, then actions there are refused even though
  I am "present" at the branch.

### US-IAM-004 — Administer companies & branches
**As an** administrator **I want** to create companies and branches **so that** the org structure
reflects the business.
- **AC1** I can create a company under the organisation.
- **AC2** I can create a branch under a company with a code unique within that company.
- **AC3** Exactly one branch per company is the default; setting a new default clears the old.

### US-IAM-005 — Manage users
**As an** administrator **I want** to create and manage user accounts **so that** staff can log in.
- **AC1** I can create a user with a username unique org-wide; a duplicate is rejected.
- **AC2** I can set/reset a user's password (admin-driven); the password must meet policy
  (≥ 8 chars, letters + number); a weak/common password is rejected.
- **AC3** I can disable a user; a disabled user cannot log in.
- **AC4** I can unlock a locked-out user.

### US-IAM-006 — Assign branches & set default
**As an** administrator **I want** to assign a user to many branches and mark one default **so
that** the user lands in the right place and can cover several locations.
- **AC1** I can assign a user to multiple branches.
- **AC2** Exactly one assignment is marked default; marking a new default clears the old.
- **AC3** I cannot set a default branch that is not among the user's assignments.
- **AC4** When I remove a user's current default branch, the system auto-promotes the
  **earliest-assigned** remaining branch to default.
- **AC5** When I remove a user's last remaining branch, the user has no active branch; on next
  login they reach a read-only "no branch assigned — contact admin" state and cannot transact until
  reassigned.
- **AC6** I can assign a user to a branch even if they have no role in that company yet; the user is
  "present" but cannot act there until a role covers it.

### US-IAM-007 — Roles & permissions
**As an** administrator **I want** to define roles from permissions and assign roles to users
**so that** access matches the org chart.
- **AC1** Permissions exist as an org-wide seeded catalogue; they cannot be invented at runtime.
- **AC2** I can create a role and attach permissions to it (org-wide).
- **AC3** I can assign a role to a user scoped to a company, optionally to one branch.
- **AC4** A user with a company-wide role assignment has it across all branches they are assigned to
  in that company; a branch-scoped assignment applies only to that branch.
- **AC5** An endpoint gated by a permission the user lacks (for the active company/branch) returns
  forbidden.

### US-IAM-008 — Super-admin
**As a** super-admin **I want** to manage all companies/branches and recover accounts **so that** I
can set up and support the deployment.
- **AC1** The super-admin can act across all companies and branches regardless of scoping.
- **AC2** Every super-admin action is written to the audit log.
- **AC3** The root permission set / system roles cannot be deleted.

### US-IAM-009 — Fresh-DB bootstrap
**As the** deployment owner **I want** the system to self-bootstrap on a fresh DB **so that** there
is a way in without a manual wizard.
- **AC1** Given an empty DB with bootstrap enabled, on first start the system creates organisation +
  first company + that company's default branch + a root admin.
- **AC2** Given the bootstrap admin password env var is missing/too short/a known placeholder, the
  app refuses to start with a clear message.
- **AC3** Given bootstrap has already run (data exists), it does not run again.

### US-IAM-010 — IAM audit trail
**As an** administrator/auditor **I want** IAM actions recorded **so that** I can see who changed
access and when.
- **AC1** User create/disable, role grant/revoke, branch assign/remove, default-branch change,
  password reset, lockout/unlock, and login success/failure are each written to an append-only
  audit log with actor, action, target, timestamp, and company/branch context.
- **AC2** Audit records cannot be edited or deleted through the application.
