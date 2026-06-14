# ERPCLEAN2 — User Manual

_ERPCLEAN2 — modular-monolith ERP (Spring Boot + Angular + PostgreSQL). Generated from the live codebase + the verified test-case suite._

## Contents

1. Getting Started
2. Administration and Access
3. Master Data
4. Sales and Point of Sale
5. Procurement (Procure-to-Pay)
6. Inventory and Manufacturing
7. Fixed Assets
8. Projects
9. Finance & Accounting
10. CRM — Customer Relationship Management
11. Reporting and Business Intelligence
12. HR & Payroll, Budgeting, and Platform Services

---

# Getting Started

Welcome to the ERP system. This chapter explains how to sign in, find your way around, and use the common patterns you will meet on every screen.

---

## Signing In

**What signing in is.** Authentication is the process of proving your identity to the system. You present a username and password; the system checks them, issues you a short-lived access token (a cryptographically signed credential, invisible to you), and loads your personal session. Everything you do after this is recorded against your identity.

**Why it exists.** Without authentication, anyone who could reach the URL could read, create, or delete business data. The system needs to know *who* you are before it can decide *what* you are allowed to do.

**When it happens.** Every time you open the application in a fresh browser tab, after your session expires (timeout is set by your administrator), or after you sign out.

**How it works.** On successful sign-in the system reads your default branch assignment and resolves your effective permissions for that branch. The main menu is built from those permissions — screens you cannot access are never shown. The access token is refreshed automatically in the background; when it cannot be refreshed (for example, your account was disabled mid-session) you are returned to the login page.

1. Open your browser and navigate to the URL your administrator gave you (for example, `http://erp.yourcompany.com`). The login page appears automatically.
2. Enter your **username** in the first field. Usernames are not case-sensitive.
3. Enter your **password** in the second field.
4. Click **Sign In**.

If your credentials are correct you are taken straight to the main dashboard. The system reads your assigned permissions and builds your personal menu — you will only see the sections you are allowed to use.

### Sign-in problems

| What you see | What to do |
|---|---|
| "Invalid credentials" | Check your username and password. The message is the same whether the username or the password is wrong — this is intentional. |
| "Account is locked. Try again later or contact an administrator." | Your account was locked after too many failed attempts. Ask an administrator to unlock it for you. |
| "Your session has expired. Please sign in again." | Your session timed out. Sign in again. Any unsaved work will be lost. |

> Your account is automatically locked for 15 minutes after 5 consecutive wrong-password attempts. A successful sign-in resets the counter.

### Signing out

Click your name or initials in the top-right corner of the screen, then click **Logout**. You are returned to the login page and your session is ended immediately.

---

## The App Layout

Once signed in you see three main areas.

### Top bar

The horizontal bar across the top of every screen contains:

- **Brand / logo** on the left.
- **Active branch indicator** — a button showing the name of the branch you are currently working in. Click it to switch to a different branch if you are assigned to more than one (see the Branch Switcher section below).
- **Your name / initials** on the right. Click to open a small menu with a **Logout** option.
- A small coloured dot showing whether the system service is reachable (green = healthy).

### Sidebar navigation

A dark panel on the left (or opened by the menu icon on small screens) groups all available screens by business area, for example **Administration**, **Sales**, **Inventory**, **Accounting**, and so on. Click any group heading to expand it, then click the screen name to navigate there.

The sidebar is **personalised** — items you do not have permission to see are simply not shown. If you cannot find a screen you expect, you probably lack the required permission. Contact your system administrator.

Press **Escape** at any time to close the sidebar on a small screen.

### Main content area

The large area to the right of the sidebar is where each screen loads. The current route is reflected in the browser address bar.

---

## The Branch Switcher

**What a branch is.** A branch is a physical or logical operating unit within a company — for example, a shop, a warehouse, a regional office, or a cost centre. Every transaction you create is stamped with the branch you were working in at the time.

**Why branch switching exists.** A single user may work across multiple locations or departments. Rather than logging in and out with different accounts, you stay logged in and tell the system which branch context to use for your current task. The system then shows you data scoped to that branch and enforces the permissions that apply there.

**When it matters.** On login the system activates your **default branch** automatically. If you are assigned to more than one branch, you can switch before performing a transaction to ensure it is recorded in the correct location.

**How it works.** Switching branches does not re-issue your login token. Instead the system records your active branch choice and attaches it to every subsequent request. Your effective permissions are re-resolved for the new branch's company on the very next call — permissions can differ between branches if your roles are scoped differently.

- On login the system activates your **default branch** automatically.
- If you are assigned to more than one branch, click the branch name in the top bar to open a dropdown. Click any branch in the list to switch to it. The list shows only your active, assigned branches — by name, not by any internal code.
- Switching branches takes effect immediately; your permissions and the data you see may change depending on your role grants.
- Selecting the branch you are already in is a no-op — the menu simply closes.

> If you have no active branch (for example, your only branch was removed or archived), you will be in a read-only state. Contact your administrator to be assigned to an active branch.

---

## How Permissions Shape What You See

**What permissions and roles are.** A permission is a named capability — for example, `USER.MANAGE` or `GL.POST`. A role is a named bundle of permissions: for instance, an "Accountant" role might bundle all the GL, AR, AP, and cash permissions that a typical accountant needs. Users are not assigned permissions directly; they are assigned roles, and those roles carry the permissions.

**Why this model exists.** Assigning individual permissions to each user does not scale when you have dozens of staff and hundreds of capabilities. Roles let you define a job function once and then grant or revoke it from any number of people in a single action. It also makes auditing straightforward: you can read a role's permission set and know exactly what every holder can do.

**When permissions are evaluated.** Every time you navigate to a screen or perform an action, the system checks your effective permissions for your active branch. If you switch branches, your permissions are re-checked against the roles you hold in the new branch's context.

**How it works.** Your administrator creates roles, assigns specific permissions to each role, and then grants those roles to you scoped to a company (and optionally a specific branch). The system computes the union of all permissions from all your active role grants in the current branch's context.

- **Nav items** you lack permission for are hidden entirely — you will not see a greyed-out item, just no item at all.
- If you type a URL directly for a screen you cannot access, the system redirects you to the home page quietly.
- If you are on a screen and try to perform an action you lack permission for (for example, clicking a button that is visible to you), the system prevents it.

The special account **rootadmin** is the system superuser and sees every screen and every action regardless of role assignment. This account is used by IT administrators only.

---

## Common UI Patterns

### Name pickers — choosing a related record

Throughout the system, whenever one record links to another (for example, a sales order links to a customer, or a user is assigned to a branch), you choose the related record **by name** — you never type codes or internal identifiers.

The picker works like this:

1. Click or focus the picker field (labelled with what you are choosing, for example **Customer**).
2. Start typing the name. The list filters as you type.
3. Click the matching item in the list.

If there are more than 12 options a filter input appears automatically; just keep typing to narrow the list.

### List screens — search and pagination

Every list screen (for example, **Sales Orders** or **Users**) behaves the same way:

- A **search** or filter bar at the top lets you narrow results by keyword, status, date range, or other criteria relevant to that list.
- A **pager** at the bottom shows page numbers and **First / Previous / Next / Last** controls. If all results fit on one page the pager hides itself.
- Column headings may be clicked to sort the list (where supported).

### The four screen states

Every data screen can be in one of four states. The system displays a distinct visual for each:

| State | What you see |
|---|---|
| **Loading** | A spinner or skeleton while data is being fetched. |
| **Empty** | A clear message that there are no records matching your criteria. This is not an error. |
| **Error** | A message explaining that something went wrong, with a prompt to try again. |
| **No access** | If you navigate directly to a screen you cannot use, you are redirected to the home page silently. |

### Money and date formats

**What currency-aware money means.** Every monetary value in this system is stored and displayed as a pair: an amount and its currency code (for example, `TZS 1,234.56` or `USD 200.00`). A bare number with no currency is never used. This matters because a figure of `1500` means something completely different in TZS than in USD.

**Why this design.** The system is built for organisations that may trade in multiple currencies. Attaching the currency to every amount from the start prevents a class of errors where amounts in different currencies are accidentally compared or summed. It also allows a second company under the same organisation to operate in a different base currency without any data migration.

- **Money** is always shown with the currency code and two decimal places, for example `TZS 1,234.56` or `USD 200.00`. You never need to type a currency symbol — the system knows the currency from context.
- **Dates** are shown in your local timezone. When entering dates use the date picker provided — never type raw date strings.

### Creating, editing, and saving records

The general flow for creating or editing a record is:

1. Click **Create** (or open an existing record).
2. Fill in the form. Required fields are marked. The system validates as you go and shows inline messages if something is wrong.
3. Click **Save** (or the specific action button, for example **Confirm** for a sales order).
4. A brief success notification (a "toast") appears at the top of the screen to confirm the action was saved. If something went wrong, an error message appears in the form itself or as an alert — read it, correct the issue, and try again.

### Record status and soft-delete

**What soft-delete means.** The system does not permanently erase records. When you deactivate a user, archive a product, or cancel a transaction, the record's status changes to `INACTIVE` or `ARCHIVED` but the record itself remains in the database and in audit history. This is called a soft-delete.

**Why this exists.** Business records have legal and operational significance beyond their active life. A cancelled invoice must still be traceable; a former employee's username must still appear in audit logs. Keeping the record preserves that history. It also means mistakes can be corrected by re-enabling a record rather than recreating it.

**How statuses work.** Most master records (users, roles, companies, branches) follow the `ACTIVE → INACTIVE / ARCHIVED` lifecycle. An `INACTIVE` record cannot be used in new transactions. An `ARCHIVED` record is additionally excluded from selection pickers and branch-switching lists. You can view inactive and archived records in the relevant administration screens by adjusting the status filter.

> The system does not hard-delete records. Deactivating a user, archiving a product, or cancelling an order leaves the record in the system in an inactive or historical state. You can always review past records.

---

## Signing In as rootadmin

**What rootadmin is.** `rootadmin` is the system superuser — a special account created once during deployment. It bypasses all permission checks and operates across every company and branch without needing role assignments.

**Why it exists.** Every system needs a recovery mechanism. If all administrator accounts were somehow locked or misconfigured, `rootadmin` is the account that can restore access. It is also used for the initial bootstrapping of the organisation, companies, and first administrative users before any role grants exist.

**When to use it.** Exclusively for initial system setup and emergency recovery. Every action taken as `rootadmin` — including any cross-company operations — is recorded in the audit trail. Normal day-to-day work must use named user accounts with appropriate roles so that audit logs are meaningful and access is scoped correctly.

The `rootadmin` account bypasses all permission checks and sees every screen and every action in every company and branch. It is reserved for initial system setup and emergency recovery. Normal day-to-day work should use named user accounts with appropriate roles.

---

# Administration and Access

This chapter is for administrators — typically users who hold the **ORG_ADMIN** role. It covers how to set up the organisation hierarchy (companies and branches), manage user accounts, define roles and their permissions, assign roles and branches to users, and review the audit trail.

> **Permissions required.** You need specific permissions for each section below. If a menu item or button is not visible to you, your role does not include that permission. See the table in each section.

---

## Organisation, Companies, and Branches

**What the three-level hierarchy is.** The system is structured in three nested levels. An **organisation** is the top-level entity representing your entire business group — think of it as the holding entity that owns everything else. A **company** is a distinct legal entity under the organisation (for example, a registered limited company or a subsidiary with its own tax ID). A **branch** is a physical or logical operating unit under a company — a shop, warehouse, regional office, or cost centre.

**Why this structure exists.** Different businesses have different legal and operational structures. A retail chain might operate a single registered company with many store branches. A group of businesses might operate several legally distinct companies sharing one ERP platform. Separating these levels allows each company to have its own data, its own base currency, and its own set of branches, while all of them are administered through one system.

**When each level matters.**
- The organisation is set up once during deployment and is resolved automatically — you never need to type an organisation identifier.
- Companies are created by an administrator with `COMPANY.MANAGE` permission, typically during the initial rollout or when a new legal entity is formed.
- Branches are created whenever a new location or operating unit is needed.

**How the levels connect.** Every transaction in the system is tagged with the branch it was performed in. A branch belongs to exactly one company, and from there the company's base currency, fiscal calendar, and legal details apply. A user's session is always scoped to an active branch; switching branches changes the company context as well (the active company is always the company that owns the active branch).

The system is structured in three levels:

- **Organisation** — the top-level entity representing your business group. It is configured by IT during deployment and is resolved automatically; you never type an organisation ID.
- **Company** — a legal entity under the organisation (for example, a registered company or subsidiary). Each company has its own data.
- **Branch** — a physical or logical office, store, or cost centre under a company. Transactions are scoped to a branch.

### Viewing companies

**Required permission:** `COMPANY.VIEW`

Navigate to **Administration › Companies** (`/admin/companies`) in the sidebar.

The list shows each company's code, name, and status (Active or Archived). Your view is limited to companies within your active organisation and, for non-admin users, to companies you are scoped to act in.

### Creating a company

**What a company record is.** A company record represents one legal entity — a separately registered business with its own identity, tax registration, and (optionally) its own functional currency. Creating a company in the system establishes the container under which that entity's branches, users, and transactions will live.

**Why you would create one.** You create a new company when a new legal entity joins your group, or when you first deploy the system and need to register your existing companies. Without at least one company, no branches can exist and no users can be assigned a working context.

**Required permission:** `COMPANY.MANAGE`

1. On the Companies list, click **Create Company**.
2. Fill in the form:
   - **Code** — a short, unique code (up to 20 characters). Cannot be changed after creation.
   - **Name** — the company display name (up to 160 characters).
   - Optional: **Legal Name**, **Tax ID**, **Timezone**.
3. Click **Save**.

The new company appears in the list with status **Active**. The organisation is resolved automatically; you do not choose it.

### Editing a company

1. Click the company row to open its details.
2. Update the fields you want to change (name, legal name, tax ID, timezone). The code and organisation cannot be changed.
3. Click **Save**.

### Archiving a company

**What archiving means.** Archiving a company marks its status as `ARCHIVED`. The record and all its data are preserved — nothing is deleted. An archived company cannot be used for new transactions, and its branches are removed from users' branch-switching lists.

**When to archive.** Archive a company when a legal entity is wound down, dissolved, or otherwise ceases operations. Do not archive a company simply because it is inactive for a period — use this as a permanent marker.

Click **Archive** on the company detail screen. The company's status changes to **Archived** (the record is not deleted). Archived companies cannot be used for new transactions, and their branches become unavailable for user sessions.

> Archiving a company affects all users whose default branch belongs to that company — they will have no active branch on their next login.

---

### Viewing branches

**Required permission:** `BRANCH.VIEW`

Navigate to **Administration › Companies** (`/admin/companies`), click a company to open its detail, then click **Branches** to open the branch list at `/admin/companies/<companyUid>/branches`.

### Creating a branch

**What a branch record is.** A branch is the smallest organisational unit in the system. It represents one operating location or logical division — a physical store, a warehouse, a regional sales office, or an accounting department. Every transaction (a sales invoice, a goods receipt, a payment) is tagged to the branch that was active when it was created.

**Why branches exist.** Without branches, all transactions for a company would be lumped together with no way to separate one location's performance from another's. Branches let management analyse stock levels by store, compare revenue by region, control what each staff member can see and do (a storekeeper at one branch cannot access another branch's data), and ensure that GL postings land in the right cost centre.

**When to create one.** Create a branch when a new physical location opens, when a new department or cost centre is established, or during initial setup to represent your existing offices and stores.

**How a branch interacts with users.** A user must be assigned to at least one branch to have an active session. One of their assigned branches is marked as their **default branch** — this is the branch that activates automatically on login. A user can be assigned to many branches and can switch between them during a session without logging out.

**Required permission:** `BRANCH.MANAGE`

1. From the branch list of a company, click **Create Branch**.
2. Fill in:
   - **Code** — a unique code within this company (up to 20 characters).
   - **Name** — the branch display name (up to 160 characters).
   - **Timezone** — optional, defaults to the company timezone.
   - **Set as default** — check this to make the new branch the company's default branch. If another branch was already the default, that branch's default flag is cleared automatically.
3. Click **Save**.

### Setting the company default branch

**What the company default branch is.** Each company has exactly one branch flagged as its default. This default is the branch that new users in this company start in when they have no personal default of their own, and it is used by the system in contexts where a company-level default is needed (for example, documents that auto-populate a branch).

**Why only one default per company.** Having more than one "default" is ambiguous — the system would not know which one to apply. The uniqueness rule (enforced at the database level) ensures there is always one unambiguous answer.

Only one branch per company can be the default. The default branch is the one users are taken to on login when no other preference is active.

1. On the branch list, find the branch you want to make default.
2. Click **Set Default** on that row.

The previously default branch is cleared automatically.

### Editing and archiving a branch

- To edit: click the branch row, update the name or timezone, and click **Save**.
- To archive: click **Archive** on the branch detail. The branch status changes to **Archived** and is removed from branch-selector lists. Any users whose default was this branch will lose their active branch on next login.

> The default flag can only be changed through the dedicated **Set Default** action, not through the general edit form.

---

## Users

**What a user account is.** A user account is a named login identity in the system. It consists of a username (the login name), a display name (shown in the interface and in audit logs), a password, and optional contact details (email, phone). A user is an organisation-wide record — the same account can be active in multiple companies, though its permissions depend on which company and branch it is working in at any given moment.

**Why user accounts exist.** Shared logins (for example, a single "accountant" password passed around the team) make audit trails meaningless — the log shows "accountant did X" and you cannot know who actually did it. Named individual accounts mean every action is attributable to a real person, accounts can be individually disabled without disrupting others, and each person can be given exactly the permissions their job requires.

**When to create a user.** Create a user when a new employee joins, when a contractor needs access, or when a role needs an automated service account. After creating the account you must also assign the user to at least one branch and grant them at least one role; a user with no branches and no roles can log in but will see no menu and have no active branch.

**How the user lifecycle works.** A user starts `ACTIVE`. An administrator can `DISABLE` the account (status becomes `INACTIVE`) to prevent login while preserving the account and its history — for example, during a leave of absence or pending investigation. `ENABLE` restores it to `ACTIVE`. The `rootadmin` account cannot be disabled. If too many wrong-password attempts are made, the account is automatically locked for 15 minutes; an administrator can clear this with **Unlock**. User accounts are never hard-deleted.

**Required permission to view:** `USER.VIEW`
**Required permission to create/edit/disable/unlock/reset password:** `USER.MANAGE`

Navigate to **Administration › Users** (`/admin/users`) in the sidebar.

### The users list

The list shows each user's username, display name, and status. Use the search bar to filter by name. Click **Manage branches** on any row to open the user's detail page at `/admin/users/uid/<uid>`.

### Creating a user

1. On the Users list, click **Create User**.
2. Fill in the create form:
   - **Username** — must be unique. Stored in lowercase. Up to 80 characters.
   - **Display Name** — the name shown in the UI. Up to 160 characters.
   - **Password** — a temporary password. Must be at least 8 characters and contain at least one letter and one number. Common passwords (such as `password1` or `admin123`) are rejected.
   - **Email** and **Phone** are optional contact fields.
3. Click **Save**.

The user is created with status **Active** and no role or branch assignments. Assign roles and branches next (see below).

> Usernames are compared case-insensitively. `Alice.Smith` and `alice.smith` refer to the same account.

### Disabling and enabling a user

- **Disable** — click **Disable** on the user's row. The user's status changes to **Inactive** and they can no longer sign in. The account and its history are preserved.
- **Enable** — click **Enable** on the row to restore the user to **Active** status.

The **rootadmin** account cannot be disabled.

### Unlocking a locked account

**What account locking is.** After 5 consecutive wrong-password attempts, the system locks the account for 15 minutes — a security measure to slow down automated guessing attacks. While locked, even the correct password is rejected and the user sees a specific message.

**When to unlock manually.** If a user cannot wait 15 minutes (for example, during a time-sensitive business operation), an administrator with `USER.MANAGE` can clear the lockout immediately. The failed-attempt counter resets on unlock.

If a user has been locked out after too many failed sign-in attempts, a locked indicator appears on their row. Click **Unlock** to clear the lockout. The user can then sign in again with the correct password.

### Resetting a user's password

**When to reset.** Reset a password when a user forgets theirs, when you suspect a password has been compromised, or when a new user needs to change the temporary password set on account creation.

1. On the Users list, expand the row's **Set Password** form.
2. Enter a new password that meets the policy (at least 8 characters, at least one letter and one number, not a common password).
3. Click **Save**.

The user can sign in immediately with the new password. Passwords are never stored in plain text and are not shown in audit logs.

### Editing user contact details

Navigate to the user's detail page (`/admin/users/uid/<uid>`) by clicking **Manage branches** from the list. You can update the display name, email, and phone number. The username and status are changed via their dedicated actions, not here.

---

## Roles

**What a role is.** A role is a named, reusable bundle of permissions. For example, an `ACCOUNTANT` role might include permissions such as `GL.POST`, `AR.VIEW`, `AP.BILL.ENTER`, and `CASH.RECONCILE`. Once defined, the role can be granted to any number of users. If the business needs to change what accountants can do, the administrator updates the role once and the change takes effect for every holder immediately.

**Why roles exist — RBAC.** This design is called Role-Based Access Control (RBAC). It exists because managing permissions per-user does not scale: a company with 50 staff and 185 permission codes would require thousands of individual permission grants, each needing manual maintenance. With roles you manage a small set of job functions, not a large matrix of individual grants. RBAC also makes compliance simpler: you can demonstrate to an auditor exactly which capabilities any given role confers.

**When roles are created.** Roles are created during initial setup (to match the job functions in your organisation) and updated whenever those functions evolve. A small set of **system roles** (such as `ORG_ADMIN`) are seeded during deployment and cannot be archived; custom roles can be freely created and modified.

**How a role's permissions take effect.** When a role's permission set is saved, the system invalidates its permission cache. Users who hold the role see the change on their very next request — there is no need to log out and back in.

**The effective permission set.** A user may hold multiple roles. Their effective permissions at any moment are the **union** of all permissions from all their active role grants in the current company and branch context. If Role A grants `GL.VIEW` and Role B grants `GL.POST`, a user with both roles has both.

**Required permission to view:** `ROLE.VIEW`
**Required permission to create/edit/set permissions/archive:** `ROLE.MANAGE`

Navigate to **Administration › Roles** (`/admin/roles`) in the sidebar.

Roles are named bundles of permissions. A user can be granted one or more roles; the effective permissions are the union of all granted roles in the active branch context.

### The roles list

The list shows each role's code, name, and whether it is a **System** role (pre-defined and cannot be archived) or a custom role. Click a role's code or name to open its edit page at `/admin/roles/uid/<uid>`.

### Creating a role

1. Click **Create Role**.
2. Fill in:
   - **Code** — a short identifier, unique within the organisation (up to 40 characters). Cannot be changed after creation.
   - **Name** — a human-readable label (up to 120 characters).
   - **Description** — optional notes.
3. Click **Save**.

The new role is created with no permissions. Assign permissions next.

### Editing a role's name or description

Open the role's edit page (`/admin/roles/uid/<uid>`) and update the name or description fields, then click **Save details**. The code cannot be changed.

### Setting a role's permissions

**What the permission catalogue is.** A permission is the finest-grained unit of access control in the system — a named capability that says "the holder may perform this specific action." Permissions are grouped by module (for example, all `GL.*` permissions belong to the General Ledger module). The full catalogue contains over 185 codes covering every module.

**How the "replace" save works.** When you click **Save permissions**, the system replaces the role's entire permission set with exactly the codes you have checked. Unchecking a box removes that permission. This means saving an empty selection leaves the role with no permissions — which is valid and means the role grants no access.

On the role edit page, the permissions panel lists every available permission grouped by module (for example, **iam**, **sales**, **accounting**).

1. Check the boxes for the permissions this role should have.
2. Uncheck any permissions to remove them.
3. Click **Save permissions**.

Saving replaces the role's entire permission set with the checked selections. Removing a permission takes effect for users who hold this role on their next request (the system re-resolves permissions promptly after changes).

> The permission catalogue contains over 185 codes across all modules. An empty permission set is valid — it means the role grants no access.

### Archiving a role

Click **Archive** on the role edit page. The role status changes to **Archived** (not deleted). System roles (for example **ORG_ADMIN**) cannot be archived.

---

## Assigning Roles to Users

**What a role grant is.** A role grant is a record that links a specific user to a specific role, scoped to a company and optionally to a single branch. It is not a permanent property of the user; it is an explicit, revocable assignment. A user can hold many grants, and each grant has its own scope.

**Why grants are scoped to a company (and optionally a branch).** A user might be an accountant in the Head Office branch and a read-only viewer in the Mwanza branch. Scoping the grant to a company-and-branch means the right permissions apply in the right context. A company-wide grant (no branch restriction) gives the role's permissions in every branch of that company. A branch-scoped grant gives those permissions only when the user is active in that specific branch.

**When to grant a role.** After creating a new user, before they can do meaningful work. Also when an existing user takes on new responsibilities. Role grants take effect immediately on the user's next request — no re-login required.

**How revocation works.** Revoking a grant marks it as revoked (the record is kept for audit purposes) and removes its permissions from the user's effective set immediately. The user's currently open session will lose those permissions on their next request.

**Required permission:** `ROLE.MANAGE`

Navigate to **Administration › Role Grants** (`/admin/role-grants`) in the sidebar.

This screen lets you grant a role to a user for a specific company, optionally restricted to a single branch.

### Granting a role

1. On the Role Grants screen (`/admin/role-grants`), choose the **User** by typing their name in the picker.
2. Choose the **Role** by name.
3. Choose the **Company** by name (the system resolves this to your active company by default).
4. Optionally choose a **Branch** to restrict the grant to one branch. Leave blank to grant the role across all branches of that company.
5. Click **Grant**.

The grant appears in the grants list. The user's effective permissions update on their next request.

> You can only grant roles within a company you are active in. The `rootadmin` account can grant across companies.

### Revoking a role grant

On the Role Grants screen, look up the user's current grants by selecting their name. Each active grant is listed. Click **Revoke** on the grant row you want to remove.

The grant is revoked immediately. The user loses those permissions on their next request.

> Revoking a grant that was already revoked is a no-op.

---

## Assigning Branches to Users

**What a branch assignment is.** A branch assignment is a record that links a user to a specific branch, granting them the ability to switch to that branch and operate within it. Without at least one branch assignment, a user has no active branch after login and can only view limited screens.

**Why branch assignments are separate from role grants.** Branch access (which branches you can switch to) and permission access (what you can do in each branch) are two different concerns that can be managed independently. You might assign a user to a branch for data-visibility reasons without granting them any elevated permissions there, or you might grant a company-wide role that applies to all branches simultaneously while still controlling which branches the user can physically switch to.

**The default branch rule.** Each user has exactly one default branch — the branch that becomes active on login. The system enforces this at the database level: you cannot have two defaults for the same user simultaneously. If you set a new default, the previous one is cleared automatically. If a user's default branch is removed or archived, the system automatically promotes the earliest-assigned remaining branch as the new default. If no branches remain, the user has no active branch (read-only session) until an administrator assigns one.

**When to use "Make default".** Tick **Make default** when assigning the branch that should be this user's primary working location — typically their home branch or the branch they will use most often. You can change the default at any time.

**Required permission to view branch assignments:** `USER.VIEW`
**Required permission to assign / change default / remove:** `BRANCH.ASSIGN`

Branch assignments control which branches a user can switch to and which data they can access. Open a user's detail page (`/admin/users/uid/<uid>`) by clicking **Manage branches** from the **Administration › Users** (`/admin/users`) list.

### Assigning a user to a branch

1. On the user detail page, find the **Branch Assignments** panel.
2. Choose the **Company** by name (only companies you are active in are shown).
3. Once a company is selected, the **Branch** picker loads that company's branches. Choose one by name.
4. Check **Make default** if you want this branch to become the user's new default.
5. Click **Assign**.

The branch appears in the user's assignments list. If **Make default** was checked and the user already had a different default branch, the old default is cleared automatically — a user can only have one default at a time.

### Changing the default branch

In the user's branch assignments list, click **Set Default** on the row for the branch you want to make default. The previous default is cleared automatically.

### Removing a branch assignment

Click **Remove** on the assignment row. If you remove the user's current default branch:

- If other branches remain, the system automatically promotes the earliest-assigned remaining branch as the new default.
- If no branches remain, the user will have no active branch on their next login (read-only session).

> You can only assign or remove branches within a company you are active in.

---

**Example — Create the ACCOUNTANT role and assign it to a new user scoped to the Head Office branch**

This example walks through the complete new-staff onboarding flow for Amina Juma, who joins as an accountant at the Head Office branch of Orbix Trading Co.

**Step 1 — Create the role (if it does not already exist)**

1. Navigate to **Administration › Roles** (`/admin/roles`).
2. Click **Create Role**.
3. Enter Code `ACCOUNTANT`, Name `Accountant`, Description `GL posting, AR, AP, Cash & Bank, Tax`.
4. Click **Save**. The role is created with no permissions.
5. Click the `ACCOUNTANT` row to open `/admin/roles/uid/<uid>`.
6. In the permissions panel, check the following codes: `GL.VIEW`, `GL.POST`, `AR.VIEW`, `AR.RECEIPT.RECORD`, `AR.STATEMENT.VIEW`, `AP.VIEW`, `AP.BILL.ENTER`, `AP.PAYMENT.RUN`, `CASH.VIEW`, `CASH.ENTRY.RECORD`, `CASH.TRANSFER`, `CASH.RECONCILE`, `VAT.VIEW`, `TAXRATE.VIEW`, `REPORT.PL.VIEW`, `REPORT.BS.VIEW`, `REPORT.CASHFLOW.VIEW`, `REPORT.LEDGER.VIEW`.
7. Click **Save permissions**. The panel refreshes showing 18 codes saved.

**Step 2 — Create the user account**

1. Navigate to **Administration › Users** (`/admin/users`).
2. Click **Create User**.
3. Enter Username `amina.juma`, Display Name `Amina Juma`, Password `Amina2024#`, Email `amina.juma@orbixtrading.co.tz`.
4. Click **Save**. The row appears with status **Active**.

**Step 3 — Assign the Head Office branch**

1. Click **Manage branches** on Amina Juma's row to open `/admin/users/uid/<uid>`.
2. In the **Branch Assignments** panel, select Company `Orbix Trading Co.`, Branch `HO — Head Office`.
3. Check **Make default**.
4. Click **Assign**. The branch appears in the list marked as default.

**Step 4 — Grant the ACCOUNTANT role**

1. Navigate to **Administration › Role Grants** (`/admin/role-grants`).
2. Select User `Amina Juma`, Role `ACCOUNTANT`, Company `Orbix Trading Co.`, Branch `HO — Head Office`.
3. Click **Grant**. The grant row appears immediately.

**Step 5 — Verify**

1. Navigate to **Administration › Audit** (`/admin/audit`).
2. Filter by Actor `rootadmin` (or whichever admin performed these steps). Confirm four audit entries: `ROLE.CREATE`, `ROLE.PERMISSIONS.SET`, `USER.CREATE`, `ROLE.GRANT`.
3. Sign in as `amina.juma` / `Amina2024#`. Confirm the **Accounting** sidebar group is visible and items such as **Chart of Accounts** (`/admin/gl/accounts`) and **Payables** (`/admin/ap/supplier-bills`) are accessible.

---

## Audit Trail

**What the audit trail is.** The audit trail is a chronological, append-only log of every significant action performed in the system. Each record captures who did it (the actor), what they did (the action code), which record was affected (the target), in which company and branch, and when. It cannot be edited, backdated, or deleted — not even by `rootadmin`.

**Why it exists.** The audit trail serves several business and legal purposes. It creates accountability: every change to a user account, every permission grant, every password reset has a named, timestamped owner. It supports security investigations: if an account is suspected of misuse, the audit log shows exactly what actions it took and when. It satisfies compliance requirements: many financial regulations require evidence that access controls were applied and that privilege changes were authorised.

**When audit records are written.** Every create, update, status change (such as enabling or disabling a user), role grant, and role revocation generates an audit record. Login successes, failures, and lockout events are also recorded. Actions by `rootadmin` that cross company boundaries produce an additional `ROOT.BYPASS` record. Audit records are written in the same database transaction as the action they record — if the action rolls back, the audit record rolls back with it.

**What is and is not recorded.** Action codes and target identifiers are always recorded. For profile-field edits (email, phone, display name) only the fact of the change is recorded — not the old or new values — to minimise personal data in the audit store. Passwords and token values are never recorded.

**Required permission:** `AUDIT.VIEW`

Navigate to **Administration › Audit** (`/admin/audit`) in the sidebar.

The audit trail is an append-only log of every significant action performed in the system — who did it, what they did, and when. It cannot be edited or deleted.

### What the audit trail records

Every create, update, state change (such as enabling or disabling a user), grant, and revoke generates an audit record. Records include:

- The **action** (for example, `USER.CREATE`, `ROLE.GRANT`, `BRANCH_UNASSIGN`).
- The **actor** — the username who performed the action.
- The **target** — the type and identifier of the affected record.
- The **timestamp** (date and time).
- For cross-company actions by `rootadmin`, a special `ROOT.BYPASS` entry is also recorded.

### Reviewing the audit log

1. Navigate to **Administration › Audit** (`/admin/audit`).
2. Use the filters at the top to narrow by action type, actor, date range, or target type.
3. The list shows the most recent events first. Use the pager to browse older records.

Audit records show usernames and action codes — not raw internal identifiers. Sensitive data (such as password hashes) is never included in audit details.

---

## Key Concepts Reference

### uid vs id — why records have two identifiers

**What they are.** Every record in the system carries two numeric identifiers. The `id` is an internal database sequence number used only for database-level joins between tables — you never see it in the interface or in URLs. The `uid` is a ULID (Universally Unique Lexicographically Sortable Identifier) — a 26-character string such as `01HY7FKMQ5T3V6NP8A2X4BQERD` — used in every URL and in every place where a record is referenced outside the database.

**Why two identifiers.** Sequential numeric IDs in URLs expose information about record counts (a competitor could learn how many orders you have by looking at the URL of the latest one). ULIDs are opaque and reveal nothing about volume or sequence. They are also time-sortable, collision-resistant, and URL-safe without encoding. The internal `id` remains for efficient database foreign-key joins, which are performance-sensitive. This duality is a deliberate design choice throughout the system.

**What this means for you.** You never need to know or type a uid. The system passes uids between pages in URLs automatically. When you pick a user, a branch, or a role by name in a form, the picker stores the uid in the background — the interface always works by name.

### Fiscal period

**What a fiscal period is.** A fiscal period is a defined accounting time window — typically a calendar month — within a financial year. Transactions (invoices, payments, journal entries) are posted to an open period; once a period is closed, historical records in it cannot be altered.

**Why fiscal periods matter.** They are the foundation of financial reporting: a profit-and-loss statement, a balance sheet, and a cash-flow report all aggregate transactions by period. Closing a period locks the numbers so that prior-period reports are stable and comparable. Without fiscal periods, a transaction entered late could silently change a figure that was already reported.

**When you interact with periods.** Accountants and finance managers interact with fiscal periods when posting journal entries, running period-end reports, and performing the year-end close. Transactions in operational modules (sales, purchases, inventory) automatically post to the current open period of the active branch's company.

---

# Master Data

Master data is the reference information shared across the system: the parties you trade with, the products you sell or buy, the prices you charge, the currencies you transact in, the taxes you apply, and the routes your sales team covers. Set this up first; every transaction in Sales, Procurement, Inventory, and Finance depends on it.

All master data screens are under the **Admin** section of the navigation. Your access depends on the permissions assigned to your role — the sections below note which permission is required for each area.

---

## Customers

**Navigation:** **Parties › Customers** (`/admin/customers`) | **Permission to view:** `CUSTOMER.VIEW` | **Permission to create / edit:** `CUSTOMER.MANAGE`

A **customer** is any person or organisation that your business sells to. The customer record is the permanent, reusable identity for that buyer: it carries their legal details, contact information, VAT registration, and credit terms, and it is referenced by every sales document you raise against them. Without a customer record you cannot create a quotation, a sales order, or an invoice for that buyer.

**Why it exists.** Storing buyer details once — rather than re-entering them on every sale — gives you consistent names on documents, a single place to update a phone number or credit limit, an audit trail of all transactions with that party, and the foundation for aged-debtor reporting. The customer record is also the control point for credit: a customer classified as a credit-account holder carries a credit limit the sales process can check.

**When it is used.** A customer record is created by a sales administrator or master-data manager before (or during) the first sale to that party. It is used every time a quotation, sales order, or invoice is raised, and every time a payment or receipt is applied to that buyer's account.

**How it works.** A customer is created with status **Active**, assigned a system-generated code (`CUST-0001`, `CUST-0002`, …), and scoped to one company. It is then associated with one or more branches so that those branches can see and select it in sales flows. Archiving a customer makes it unavailable for new transactions but preserves it in historical records. The record can be restored at any time.

Customers are the parties you sell to. Each customer belongs to one company and carries a system-generated code (`CUST-0001`, `CUST-0002`, …). You never enter or see the internal uid — the system uses that behind the scenes.

### Customer types

Every customer record has two classification fields set at creation time:

| Field | Options | Notes |
|---|---|---|
| **Party Type** | Individual, Business | Business customers must have a TIN. |
| **Customer Kind** | Cash / Walk-in, Credit Account | Credit account customers carry a credit limit and payment terms. |

**Party Type** distinguishes a private individual from a registered legal entity. For a business, a Tax Identification Number (TIN) — the government-issued taxpayer reference — is required because it must appear on formal tax invoices. Individuals are exempt.

**Customer Kind** describes the trading relationship. A **Cash / Walk-in** customer pays at the point of sale; no ongoing credit account is maintained. A **Credit Account** customer is extended a line of credit: the business ships goods or delivers services now and expects payment within agreed terms (for example, 30 days). Credit account customers therefore carry a **credit limit** (the maximum outstanding balance the business will allow) and **payment terms** (the number of days before payment is due). These two fields appear only when Credit Account is selected and are absent for walk-in customers.

Once saved, Party Type and Customer Kind can be changed on the detail edit form.

### How to create a customer

1. Navigate to **Parties › Customers** (`/admin/customers`).
2. Click **New Customer**. An inline form appears below the toolbar.
3. Enter the **Display Name** (required).
4. Select **Party Type** (Individual or Business).
   - If you choose Business, a **TIN** field becomes required.
5. Select **Customer Kind** (Cash / Walk-in or Credit Account).
   - If you choose Credit Account, a **Credit Limit** (amount and currency) and a **Payment Terms (days)** field appear. These are optional — you can leave them blank and set them later.
6. Optionally fill in Phone, Email, Address, Region, District.
7. If the customer is VAT-registered, tick **VAT Registered** and then enter the **VRN**. You cannot enter a VRN unless VAT Registered is ticked.
8. Click **Submit**.

The system assigns a unique code and sets the status to **Active**. The new row appears in the list immediately.

### How to search for a customer

On the **Parties › Customers** (`/admin/customers`) list:

- Type in the **Search** box. Name search is case-insensitive and matches any part of the name.
- Searching by **TIN**, **Phone**, or **Code** requires an exact match.
- The list resets to the first page when you start a new search.
- Click **Clear** to return to the full unfiltered list.

### How to view and edit a customer

1. Click on any row in the customer list to open the detail page (`/admin/customers/uid/<uid>`).
2. The URL contains the customer's uid — you do not need to read or type this.
3. Edit any field in the form. The **Code** and **Company** fields are read-only (they are set at creation and cannot change).
4. If Customer Kind is **Cash / Walk-in**, the Credit Limit and Payment Terms fields are hidden. Switch to Credit Account to reveal them.
5. Click **Save** to apply changes.

### How to archive and restore a customer

An archived customer remains in the database for historical reporting but is not available for new transactions.

1. Open the customer detail page (`/admin/customers/uid/<uid>`).
2. Click **Archive**. The status badge changes to **Archived**.
3. To reverse, click **Restore**. The status returns to **Active**.

Archiving and restoring are both immediate and do not require a reason.

### Branch associations

A customer can be associated with specific branches of your company. This determines which branches can see the customer in their scoped views.

A **branch association** links a party to a specific operating location within the company. Because your business may have multiple branches (offices, warehouses, or sales points), each transaction is tied to the branch that raised it. A customer that has not been associated with any branch will not appear in selection lists at any branch, even though the record exists in the system. Associating a customer with a branch makes them available to that branch's sales team.

1. Open the customer detail page (`/admin/customers/uid/<uid>`).
2. Scroll to the **Branch Associations** panel.
3. Select the **Company** from the first dropdown, then select the **Branch** (shown as `code — name`) from the second.
4. Click **Assign**. The branch appears in the association list with the date it was assigned.
5. To remove a branch, click **Remove** on the relevant row.

You need the `PARTY.BRANCH.ASSIGN` permission to assign or remove branches. You can only assign branches that belong to the same company as the customer.

---

**Example — Register a new credit-account business customer**

Scenario: Sales admin Fatuma Msongo is on-boarding Karibu Wholesale Ltd, a new B2B buyer on 30-day credit terms.

1. Navigate to **Parties › Customers** (`/admin/customers`). Click **New Customer**.
2. Enter Display Name `Karibu Wholesale Ltd`, Party Type `Business`, TIN `100-456-789`.
3. Select Customer Kind `Credit Account`. Enter Credit Limit `TZS 5,000,000`, Payment Terms `30` days.
4. Enter Phone `+255 22 211 0099`, Email `orders@karibuwholesale.co.tz`, Region `Dar es Salaam`.
5. Tick **VAT Registered**, enter VRN `40-045678-H`.
6. Click **Submit**. The system assigns code `CUST-0012` and status **Active**.
7. Click the `CUST-0012` row to open `/admin/customers/uid/<uid>`.
8. In the **Branch Associations** panel, select Company `Orbix Trading Co.`, Branch `DSM — Dar es Salaam Branch`. Click **Assign**. The branch association is saved.

Karibu Wholesale Ltd is now available as a customer on all sales flows for the DSM branch.

---

## Suppliers

**Navigation:** **Parties › Suppliers** (`/admin/suppliers`) | **Permission to view:** `SUPPLIER.VIEW` | **Permission to create / edit:** `SUPPLIER.MANAGE`

A **supplier** is any person or organisation that your business purchases from. The supplier record is the permanent identity for that vendor: their legal details, tax registration, contact information, and the kind of goods or services they provide. Without a supplier record you cannot raise a purchase order, record a goods receipt, or register an invoice from that vendor.

**Why it exists.** Centralising supplier details ensures that purchase orders always go to the right party with the right tax and legal details, that every procurement transaction is traceable back to an approved supplier, and that accounts-payable balances can be correctly allocated. It also enables three-way matching: matching a purchase order to a goods receipt to a supplier invoice — the core control that prevents paying for goods you did not order or receive.

**When it is used.** A procurement officer or master-data manager creates the supplier record before (or at the time of) the first purchase from that vendor. It is referenced on every purchase order, goods receipt, and supplier invoice.

**How it works.** Suppliers follow the same lifecycle as customers: created **Active**, assigned a `SUPP-####` code, scoped to one company, associated with branches, and archivable. The key difference from a customer is the **Supplier Kind** field — Goods or Service — which indicates the nature of supply. There are no credit-limit or payment-terms fields on a supplier record; those are managed on the AP (Accounts Payable) side.

Suppliers are the parties you purchase from. The data structure mirrors customers, with one difference: the kind field distinguishes **Goods** suppliers from **Service** suppliers (there are no credit limit or payment terms fields on a supplier record).

Supplier codes are prefixed `SUPP-` (for example, `SUPP-0001`).

### How to create a supplier

1. Navigate to **Parties › Suppliers** (`/admin/suppliers`).
2. Click **New Supplier**.
3. Enter **Display Name** (required), **Party Type**, and **Supplier Kind** (Goods or Service).
4. If Party Type is Business, enter the **TIN**.
5. Fill in optional contact details and VAT fields as described in the Customers section above.
6. Click **Submit**.

The same rules apply: TIN required for Business parties, VRN only when VAT Registered is ticked.

### Search, edit, archive, restore, and branch associations

These work exactly as described for Customers above, substituting the **Parties › Suppliers** (`/admin/suppliers`) screen and the detail page at `/admin/suppliers/uid/<uid>`, using the `SUPPLIER.MANAGE` / `PARTY.BRANCH.ASSIGN` permissions.

---

**Example — Register a goods supplier**

Scenario: Procurement officer Hassan Kamau adds Tembo Industries Ltd as a VAT-registered goods supplier.

1. Navigate to **Parties › Suppliers** (`/admin/suppliers`). Click **New Supplier**.
2. Enter Display Name `Tembo Industries Ltd`, Party Type `Business`, TIN `100-789-321`, Supplier Kind `Goods`.
3. Tick **VAT Registered**, enter VRN `40-078901-T`.
4. Enter Phone `+255 27 254 4400`, Region `Arusha`.
5. Click **Submit**. System assigns code `SUPP-0008` and status **Active**.

---

## Other Parties

**Navigation:** **Parties › Other Parties** (`/admin/other-parties`) | **Permission to view:** `OTHERPARTY.VIEW` | **Permission to create / edit:** `OTHERPARTY.MANAGE`

An **other party** is any third party that your business has a financial or operational relationship with but that does not fit neatly into the customer or supplier categories. Common examples include landlords (you pay rent to them), utility providers (you pay electricity or water bills), regulatory bodies (you pay licence fees or levies), and freight or clearing companies (you pay logistics costs). Without an other-party record, these payments would have no addressable counterpart in the system.

**Why it exists.** The customer and supplier masters are purpose-built for sales and procurement flows. Forcing every conceivable counterpart into those categories would pollute the selection lists that sales and procurement teams use daily. Other Parties is a catch-all master that keeps the core lists clean while still giving every payable a named, traceable counterpart for accounting and audit purposes.

**When it is used.** A finance administrator or master-data manager creates an other-party record when a new type of expenditure or relationship arises that is not covered by the supplier master — for example, when setting up a monthly rent payment to a landlord for the first time.

**How it works.** Other parties follow the same lifecycle as customers and suppliers: created **Active**, assigned an `OTHR-####` code, scoped to one company, and archivable. The only structural difference is the **Other Kind** field, which is free text rather than a fixed list. You can type any descriptive label (for example, `Landlord`, `Utility`, `Freight Forwarder`) to classify the party informally.

Other Parties covers any third party that is not a customer, supplier, or agent — for example, landlords, regulatory bodies, utility providers, or freight companies. Other Party codes are prefixed `OTHR-`.

The key difference from customers and suppliers is the **Other Kind** field, which is free text (not a fixed list). You can type any label, such as "Landlord", "Utility", or "Freight Forwarder". The field is optional.

All other behaviour — TIN rule for Business parties, VAT/VRN pairing, archive/restore lifecycle, and branch associations — is identical to Customers and Suppliers. The detail page for an other party is at `/admin/other-parties/uid/<uid>`.

---

## Sales Agents

**Navigation:** **Parties › Sales Agents** (`/admin/agents`) | **Permission to view:** `AGENT.VIEW` | **Permission to create / edit:** `AGENT.MANAGE`

A **sales agent** is the person or organisation responsible for bringing in a sale. An agent is credited on sales documents (quotations, orders, invoices) and is the link between a customer and the company's sales team. Agents are referenced by distribution routes, by opportunities in the CRM module, and by sales invoices — where the agent's primary route is automatically carried across to provide a geographic reference for the sale.

**Why it exists.** Tracking which agent made which sale enables commission reporting, performance management, and territory analysis. The agent is also the connection between the geographic route structure and individual sales staff: assigning an agent to a route as its primary agent means that any sale to a customer on that route is automatically tagged with the correct route on the invoice.

**When it is used.** A master-data manager or HR administrator creates an agent record when onboarding a new sales representative (internal) or registering a new external reseller or freelance agent (external). The agent is then assigned to routes and used on sales documents.

**How it works.** The agent has a status lifecycle identical to customers and suppliers (Active → Archived → Active). An important distinction governs the agent's relationship to the system's user accounts:

Sales agents represent the people or organisations that sell on your behalf. Agent codes are prefixed `AGNT-`.

### Agent kinds

| Kind | Meaning | User link |
|---|---|---|
| **Internal** | An employee who is also an app user | Must be linked to an active user in the same company |
| **External** | A third-party agent, not an app user | Must NOT be linked to an app user |

An **Internal** agent is a staff member who logs in to the system. Linking the agent record to a user account enables the system to associate that person's sales activity with their login identity — useful for task lists, permission-gated views, and commission attribution. The linked user must be active and belong to the same company.

An **External** agent is a freelance representative, a distributor, or a third-party reseller who does not have a login to your ERP system. They are tracked as a party for document and reporting purposes only.

### How to create an agent

1. Navigate to **Parties › Sales Agents** (`/admin/agents`).
2. Click **New Agent**.
3. Enter **Display Name**, **Party Type**, and **Agent Kind** (Internal or External).
4. If Kind is **Internal**, a **User** selector appears. Choose the user by name from the list. The system stores the link internally — you do not type a user id.
5. If Kind is **External**, the user selector is hidden.
6. Click **Submit**.

### Switching an agent between Internal and External

On the agent detail page (`/admin/agents/uid/<uid>`), changing Kind from Internal to External clears the user link automatically on save. Changing from External to Internal requires you to select a user before saving.

### Search, edit, archive, restore, and branch associations

These work as described for Customers, using the **Parties › Sales Agents** (`/admin/agents`) screen and the `AGENT.MANAGE` and `PARTY.BRANCH.ASSIGN` permissions.

---

**Example — Create an external field agent and assign them to a route**

Scenario: Operations manager registers Juma Rashidi as a freelance distribution agent for the Coast route.

1. Navigate to **Parties › Sales Agents** (`/admin/agents`). Click **New Agent**.
2. Enter Display Name `Juma Rashidi`, Party Type `Individual`, Agent Kind `External`.
3. Click **Submit**. System assigns code `AGNT-0004`.
4. Open the route at **Parties › Routes** (`/admin/routes`), click the **Coast Distribution Route** row.
5. In the **Agents** panel, type `Juma` and select `AGNT-0004 — Juma Rashidi`. Tick **Primary**. Click **Assign**.

---

## Products

**Navigation:** **Products › Products** (`/admin/products`) | **Permission to view:** `PRODUCT.VIEW` | **Permission to create / edit:** `PRODUCT.MANAGE`

A **product** is any item or service that your business sells, buys, or manufactures. The product record is the central catalogue entry that links a name and code to its cost, its selling prices, its unit of measurement, and — for stocked goods — its inventory tracking. Every sales line, purchase line, and stock movement references a product record.

**Why it exists.** Without a product catalogue, every transaction would require staff to invent descriptions, prices, and codes on the spot — leading to inconsistency, mispricing, and an inability to report on what was sold or bought. The product master is the single source of truth for what the business trades in: it enforces consistent naming, links prices to agreed price lists, defines the packaging hierarchy (base unit and bulk packs), and controls whether an item appears in sales or procurement flows.

**When it is used.** A catalogue manager or product administrator creates product records before the first transaction involving those items. Products are used on every sales quotation and order (if sellable), every purchase order and goods receipt (if a goods product), every stock movement (if stockable), and every manufacturing or assembly job (if it has a recipe).

**How it works.** A product is created **Active** with a `PROD-####` code (or a custom code you supply), scoped to one company, and associated with branches. Its lifecycle is Active → Archived → Active. Once created, you can add barcodes for scanning at the point of sale, define bulk-pack conversions (for example, 50 kg bags per carton), set selling prices on each of your price lists, and define a component recipe for manufactured or bundled items.

Products are the items you sell, buy, or manufacture. Each product belongs to one company and carries a system-generated code (for example, `PROD-0001`) unless you supply your own code at creation time.

### Product types

| Field | Options | Rules |
|---|---|---|
| **Type** | Goods, Service | Service products cannot be stockable (the Stockable checkbox is forced off). |
| **Stockable** | Yes / No | Only Goods products can be stockable. |
| **Sellable** | Yes / No | Controls whether the product appears in sales flows. |
| **VAT Status** | Standard, Zero-rated, Exempt | Defaults to Standard. |

**Type** determines the fundamental nature of the item. A **Goods** product is a physical item that can be received into stock, transferred between locations, and dispatched to customers. A **Service** product is an intangible deliverable — consulting, installation, transport — that cannot be stocked or counted in a warehouse. This distinction matters because inventory and stock-movement rules apply only to goods.

**Stockable** controls whether the system maintains an inventory balance for this product. A non-stockable good might be a consumable expensed immediately on purchase; a non-stockable service is an intangible. Only goods can be stockable — the system prevents a service product from being marked stockable because there is nothing physical to count.

**Sellable** controls whether the product appears on sales quotations and orders. An internal intermediate product used only in manufacturing recipes would typically not be sellable.

**VAT Status** determines how value-added tax is calculated on sales lines for this product. **Standard** applies the current standard VAT rate (18%). **Zero-rated** applies 0% — the line is technically within the VAT system but taxed at nil (common for basic food items in some jurisdictions). **Exempt** items are outside the VAT system entirely and produce no VAT entry. These statuses drive the tax lines on invoices and the VAT return.

### How to create a product

1. Navigate to **Products › Products** (`/admin/products`).
2. Click **New Product**.
3. Optionally enter a **Code**. If you leave it blank the system assigns `PROD-####`. If you type a code it is trimmed of spaces and converted to upper case.
4. Enter the **Name** (required).
5. Select **Type** (Goods or Service). If you select Service, the Stockable checkbox becomes unavailable.
6. Select the **Base Unit** from the dropdown by its code and name (for example, `EA — Each`). Only active units of measure are offered.
7. Enter the **Cost** (amount and currency).
8. Select the **VAT Status**.
9. Click **Submit**.

### How to set a custom code

Type the code in the **Code** field. The system converts it to upper case (so `sku-001` becomes `SKU-001`). Codes must be unique within the company — if you enter a duplicate you will see an error after you submit.

### How to edit a product

1. Click a product row to open the detail page (`/admin/products/uid/<uid>`).
2. Modify fields as needed. The **Code** field is read-only on the detail page.
3. Click **Save**.

If you change Type from Goods to Service, the Stockable checkbox is forced off automatically.

### How to archive and restore a product

Open the product detail page (`/admin/products/uid/<uid>`) and click **Archive** (to make it unavailable) or **Restore** (to make it active again). Archived products are excluded from order lines and component pickers.

### Branch associations

Works exactly as described for Customers. The permission required is `PRODUCT.BRANCH.ASSIGN`.

### Barcodes

A **barcode** is a scannable value printed on product packaging — EAN-13, UPC, QR code, or a supplier's own code. Registering barcodes against a product enables point-of-sale staff to scan an item and have the system identify it instantly, rather than searching by name or code. One barcode is designated **primary** — it is the default identifier used on documents and the one that scanning resolves to first.

In the **Barcodes** panel on the product detail page:

1. Type the barcode value.
2. Tick **Primary** if this is the product's primary barcode.
3. Click **Add Barcode**.
4. To remove a barcode, click **Remove** on the relevant row.

### Bulk packs

A **bulk pack** defines how a product is packaged for storage or sale in larger quantities than its base unit. For example, if the base unit is `EA` (Each), a carton might contain 24 units. Bulk packs are used in procurement (ordering by the carton), in warehousing (counting by pallet or crate), and in wholesale sales (pricing by the case). The **factor** is the number of base units in one pack — the conversion ratio that lets the system translate between units.

Bulk packs define how many base units fit into a larger packaging unit (for example, 24 `EA` in a `CTN — Carton`).

1. In the **Bulk Packs** panel, select the **Unit** (the larger packaging unit) from the dropdown by code and name.
2. Enter the **Factor** — the number of base units in one pack (must be greater than zero).
3. Click **Add**.
4. To remove a bulk pack, click **Remove**.

### Product prices

A **product price** is the selling price of this product on a specific price list. A price must be set on a price list before the product can be sold at that list's rate. You can maintain different prices on different lists — for example, a higher retail price and a lower wholesale price for the same product.

You can set a selling price for this product on each of your price lists.

1. In the **Prices** panel, select the **Price List** by its code and name.
2. Enter the **Amount** and **Currency**.
3. Click **Set Price**.

Setting a price on a price list that already has a price for this product overwrites the existing price. To remove a price, click **Remove** on the row.

### Product components (recipe)

A **product component** (also called a recipe or bill of materials) records the constituent parts of a composed product — for example, the raw materials needed to assemble a finished good, or the individual items bundled together in a gift set. In the current version, the recipe records the structure only: it does not automatically trigger stock movements or cost calculations. That behaviour is reserved for the manufacturing module.

Components define the ingredients or sub-products that make up this product — used in manufacturing or bundled sales.

1. In the **Components / Recipe** panel, start typing a product name in the search box.
2. Select the component product from the results (shown as `code — name`). The product itself and archived products are excluded from the list.
3. Enter the **Quantity** (must be greater than zero).
4. Click **Add Component**.
5. To remove a component, click **Remove** on the row.

---

**Example — Set up Sugar (1 kg) with a retail price, a carton bulk pack, and a barcode**

Scenario: Catalogue manager sets up a new FMCG line before the first purchase order.

1. Navigate to **Products › Products** (`/admin/products`). Click **New Product**.
2. Leave Code blank. Enter Name `Sugar 1kg`, Type `Goods`, Base Unit `KG — Kilogram`, Cost `TZS 1,800`, VAT Status `Standard`. Click **Submit**. System assigns `PROD-0034`.
3. Click `PROD-0034` to open `/admin/products/uid/<uid>`.
4. **Barcodes panel:** Enter `6009876543210`, tick **Primary**, click **Add Barcode**.
5. **Bulk Packs panel:** Select Unit `CTN — Carton`, Factor `50`. Click **Add**. (50 kg bags per carton.)
6. **Prices panel:** Select Price List `RETAIL — Retail Price List`, Amount `TZS 2,500`, Currency `TZS`. Click **Set Price**.
7. **Prices panel:** Select Price List `WHOLESALE — Wholesale Price List`, Amount `TZS 2,200`, Currency `TZS`. Click **Set Price**.

The product `PROD-0034 — Sugar 1kg` is now available for sale at the correct retail price and will appear in stock movements tracked in kilograms.

---

## Units of Measure

**Navigation:** **Products › Units of Measure** (`/admin/units`) | **Permission to view:** `UOM.VIEW` | **Permission to create / edit:** `UOM.MANAGE`

A **unit of measure (UoM)** is the label attached to a quantity: it defines what one "unit" of a product means. Examples include `EA` (Each), `KG` (Kilogram), `LTR` (Litre), and `CTN` (Carton). Every product must be assigned a base unit, and every order line, stock movement, and bulk pack references a unit.

**Why it exists.** Without defined units, quantities on documents are ambiguous — does "10" mean ten individual items, ten kilograms, or ten cartons? Consistent units ensure that stock balances are measured correctly, that picking and packing instructions are unambiguous, and that unit conversions (via bulk packs) are mathematically reliable. Centralising units in a master also provides a single pick-list that avoids the "pcs vs piece vs pieces" label drift that arises when staff type units freehand.

**When it is used.** A master-data manager creates units before creating products, because every product requires a base unit. Units are also referenced when defining bulk packs (the larger packaging unit) and on order lines where a specific packaging unit is selected.

**How it works.** Each unit has a short **Code** (used as the label on documents) and a **Name** (the display name). Units can be archived to remove them from selection dropdowns; archived units are excluded from product creation but remain on existing records for historical accuracy.

Units of measure (UoM) are the quantity labels used on products, bulk packs, and order lines — for example, `EA` (Each), `KG` (Kilogram), `CTN` (Carton).

### How to create a unit

1. Navigate to **Products › Units of Measure** (`/admin/units`).
2. Click **New Unit**.
3. Enter the **Code** (for example, `CTN`) and the **Name** (for example, `Carton`). Both are required and the code must be unique within the company.
4. Click **Submit**.

### How to edit a unit

Click **Edit** on a row, change the **Name** (the Code is read-only after creation), and click **Save**.

### Archive and restore

Click **Archive** to deactivate a unit. Archived units are removed from product and bulk-pack dropdowns — only active units are selectable. Click **Restore** to make the unit active again.

---

## Price Lists

**Navigation:** **Products › Price Lists** (`/admin/price-lists`) | **Permission to view:** `PRICELIST.VIEW` | **Permission to create / edit:** `PRICELIST.MANAGE`

A **price list** is a named set of selling prices. Rather than storing a single price on each product, the system lets you maintain multiple lists — for example, a Retail list, a Wholesale list, and a Distributor list — each with different prices for the same product. When a sales document is created, the system looks up the product's price from the price list assigned to that customer or order, ensuring that different categories of buyer are automatically charged at their agreed rates.

**Why it exists.** Different customer segments — retail walk-ins, wholesale buyers, key distributors — typically receive different pricing. Without named price lists, a business would have to manually enter prices on every order line and hope for consistency. Price lists enforce pricing discipline: the price is looked up, not typed, so discrepancies and pricing errors are structurally prevented.

**When it is used.** A pricing manager or catalogue administrator creates price lists once, then sets prices on each product for each list (in the Product detail page). Price lists are assigned to customers or selected on individual orders at sale time.

**How it works.** A price list has a short **Code** (such as `RETAIL`) and a **Name** (such as `Retail Price List`). Both are fixed at creation; the code is unique within the company. The list can be archived to prevent it from being selected on new orders; archiving does not remove prices already set on products.

Price lists group selling prices. You might have a Retail list (`RETAIL`), a Wholesale list (`WHOLESALE`), and a Distributor list. Customers and orders are assigned a price list, and the system looks up the price from there.

### How to create a price list

1. Navigate to **Products › Price Lists** (`/admin/price-lists`).
2. Click **New Price List**.
3. Enter a **Code** (for example, `RETAIL`) and a **Name** (for example, `Retail Price List`). Both are required and the code must be unique within the company.
4. Click **Submit**.

### Edit, archive, restore

Click **Edit** on a row to change the name (code is read-only after creation). Archive and restore work as on all master records.

---

## Currencies and FX Rates

**Navigation:** **FX / Currency › Exchange Rates** (`/admin/fx/rates`) | **Permission to view:** `CURRENCY.VIEW` | **Permission to add rates:** `CURRENCY.MANAGE`

A **currency** is a monetary unit of account — Tanzanian Shillings (TZS), US Dollars (USD), Euros (EUR), Kenyan Shillings (KES), and so on. Every monetary amount in this system is recorded as a pair: a number and a currency code. This means the system is currency-aware from the start, so transactions in foreign currencies are recorded correctly alongside local-currency ones.

**Why currencies are always explicit.** Storing a bare number without a currency — for example, "1,000" with an implied TZS — is a source of silent errors: import prices in USD would be compared directly with local costs in TZS, and reports would add unlike amounts. Every price, cost, credit limit, and invoice total in this system therefore carries its currency code alongside the number. The base (home) currency is **TZS**.

An **FX rate** (foreign exchange rate) is the conversion factor between two currencies on a given date. When you receive a supplier invoice in USD, or raise a customer invoice in USD, the system needs to know how many TZS equal one USD on that particular day in order to record the correct local-currency equivalent in the general ledger and for reporting.

**Why FX rates exist.** Without exchange rates, foreign-currency transactions cannot be translated into the company's reporting currency. The rate on the day of the transaction is the authoritative rate for that transaction; a rate entered later cannot retroactively fix a document. Recording each day's rate as an immutable append-only row gives a permanent audit trail that regulators and auditors can verify.

**When they are used.** The finance officer or treasury administrator enters FX rates each day (or each time a foreign-currency transaction is expected). The system uses the most recent effective-dated rate for each currency pair when converting amounts.

**How it works.** Currencies are global reference data — you cannot create or delete them. FX rates are **append-only**: you add a new row for each rate change; you never edit a past rate. If you discover an error, you add a corrected row with the right date and value. The list is sorted newest-first. A rate between two currencies is selected by finding the row with the latest effective date on or before the transaction date.

The system's base currency is **TZS**. You can record foreign exchange rates to support transactions in other currencies (USD, EUR, KES, and others).

### Currency list

Currencies are global reference data — you cannot create or delete them. The available currencies (TZS, USD, EUR, KES, and others) are seeded by the system and visible in the From / To pickers on the FX Rates screen.

### How to add an FX rate

1. Navigate to **FX / Currency › Exchange Rates** (`/admin/fx/rates`).
2. Click **New Rate**.
3. Select the **From** currency and the **To** currency. They must be different.
4. Enter the **Rate** (must be greater than zero).
5. Set the **Effective Date** (required; format `YYYY-MM-DD`).
6. Set **Rate Type** (for example, `SPOT`) and **Source** (for example, `MANUAL`).
7. Click **Submit**.

FX rates are **append-only**: you cannot edit a rate in place. To correct a rate, add a new row with the corrected value and the correct effective date. The system uses the latest effective-dated rate for each currency pair when converting amounts.

The rates list is sorted newest-first and is paginated.

---

**Example — Record today's USD buying rate**

Scenario: Finance officer records the Bank of Tanzania mid-rate on 14 June 2026 for USD invoices received from an overseas supplier.

1. Navigate to **FX / Currency › Exchange Rates** (`/admin/fx/rates`). Click **New Rate**.
2. From `USD`, To `TZS`, Rate `2542.50`, Effective Date `2026-06-14`, Rate Type `SPOT`, Source `MANUAL`.
3. Click **Submit**. The row `USD → TZS @ 2,542.50 (2026-06-14)` appears at the top of the list.

Tomorrow, if the rate changes to `2,548.00`, simply click **New Rate** again and submit the new row — the old record is preserved for historical reporting.

---

## Tax Rates

**Navigation:** **Sales › Tax Rates** (`/admin/tax-rates`) | **Permission to view:** `TAXRATE.VIEW` | **Permission to edit:** `TAXRATE.MANAGE`

A **tax rate** is the percentage applied to a sale line to calculate value-added tax (VAT). VAT is a consumption tax collected by the business on behalf of the tax authority: the business charges the customer a price plus VAT, then remits the VAT element to the government. Getting the rate right on every transaction is a legal obligation, not an option.

**Why tax rates exist as a configurable master.** The VAT rate in Tanzania (and in many countries) is set by law and can change. Hardcoding 18% into the software would require a code change every time the rate changed. Instead, the system maintains three configurable VAT bands per company — Standard, Zero-rated, and Exempt — each with an editable rate. When the government adjusts the rate, the finance manager updates the single master record and all future transactions use the new rate automatically.

**The three bands explained:**
- **Standard** — the normal VAT rate, currently 18% in Tanzania. Applied to most goods and services. The tax amount on a sale line is the net price multiplied by this rate.
- **Zero-rated** — technically within the VAT system but taxed at 0%. Businesses selling zero-rated goods can still reclaim input VAT on their purchases. Common for staple food items in many jurisdictions.
- **Exempt** — outside the VAT system entirely. No VAT is charged and no VAT can be reclaimed on inputs. Different from zero-rated because exempt status completely removes the item from the VAT computation.

**When it is used.** A finance manager or system administrator reviews and (if required) adjusts the rates when the tax authority changes them. The rates are applied automatically to every sales and purchase line based on the product's VAT status (set on the product record).

**How it works.** The three bands are seeded when a company is created; you cannot add new bands or delete existing ones. You can only edit the rate of each band. The updated rate applies to all future transactions that reference that band; past transactions retain the rate that was in effect when they were created.

Three VAT bands are seeded per company:

| Band | Default rate |
|---|---|
| Standard | 18% (0.18) |
| Zero-rated | 0% (0.00) |
| Exempt | 0% (0.00) |

You can edit the rate for each band. There is no create or archive on tax rates — the three bands are fixed.

### How to edit a tax rate

1. Navigate to **Sales › Tax Rates** (`/admin/tax-rates`).
2. Click **Edit** on the relevant band row.
3. Enter the new rate as a decimal between 0 and 0.9999 (for example, `0.18` for 18%).
4. Click **Save**.

The rate applies to all future transactions that reference this VAT band on a product.

---

## Distribution Routes

**Navigation:** **Parties › Routes** (`/admin/routes`) | **Permission to view:** `ROUTE.VIEW` | **Permission to create / edit / assign branches:** `ROUTE.MANAGE` | **Permission to assign customers and agents:** `ROUTE.ASSIGN`

A **distribution route** (or simply a route) is a named geographic or logical territory that groups a set of customers and assigns the sales agent or agents responsible for serving them. Routes answer the question "which customers does this agent visit, and on which road or region?" They provide an organising layer above individual customers and are the bridge between the customer master, the agent master, and the sales invoice.

**Why routes exist.** In distribution-heavy businesses — FMCG, wholesale, van-sales — a sales team covers fixed territories. Without a route structure, there is no way to know which agent is responsible for which customers, to plan delivery runs efficiently, or to report sales performance by territory. Routes solve these problems by grouping customers under a named area and assigning one or more agents to that area, with a **primary** agent designated as the default for invoices raised against customers on that route.

**When they are used.** A distribution or operations manager creates routes when setting up the company's sales territories, then assigns customers and agents to those routes. Once set up, routes are largely static — they are updated when territory boundaries change, when customers are transferred between routes, or when agents change. On every sales invoice, the system automatically carries across the selling agent's primary route, so invoices are tagged geographically without any manual entry by the sales team.

**How it works.** A route is created with a name and an optional free-text location identifier (describing the geography informally). It is then associated with branches (so branch-level users can see it), with customers (so those customers appear in the route's list for run-planning), and with agents (so the agent is responsible for that route). Only **External** agents can be assigned to routes — internal agents work within the application and do not need a field territory assignment. One agent can be marked **Primary** on a route; this agent's route is the default on invoices, making geographic reporting automatic. The route lifecycle follows the same Active/Archived pattern as other masters.

Routes represent geographic or logical delivery areas used to group customers and assign agents. Each route has a system-generated code, a name, and an optional location identifier.

### How to create a route

1. Navigate to **Parties › Routes** (`/admin/routes`).
2. Click **New Route**.
3. Enter the **Name** (required) and optionally a **Location Identifier**.
4. Click **Submit**.

The system assigns a code. Status defaults to Active.

### How to edit a route

1. Click a route row to open the detail page (`/admin/routes/uid/<uid>`).
2. Change the name or location identifier (code and company are read-only).
3. Click **Save**.

### Archive and restore

Click **Archive** on the route detail page (`/admin/routes/uid/<uid>`) to deactivate it. Click **Restore** to reactivate.

### Assigning customers to a route

1. Open the route detail page (`/admin/routes/uid/<uid>`).
2. In the **Customers** panel, start typing a customer name in the search box.
3. Select the customer from the results (shown as `code — displayName`).
4. Click **Assign**.
5. To remove a customer from the route, click **Remove** on the row.

Only active customers from the same company appear in the picker. You need the `ROUTE.ASSIGN` permission.

### Assigning agents to a route

1. In the **Agents** panel, start typing an agent name.
2. Select the agent from the results. Only **External** agents are available — internal agents cannot be assigned to a route.
3. Tick **Primary** if this agent is the primary agent for this route.
4. Click **Assign**.
5. To remove an agent, click **Remove**.

You need the `ROUTE.ASSIGN` permission.

### Assigning branches to a route

1. In the **Branches** panel, select the **Company** from the first dropdown, then select the **Branch** (shown as `code — name`).
2. Click **Assign**.
3. To remove a branch, click **Remove**.

You need the `ROUTE.MANAGE` permission (not `ROUTE.ASSIGN`) to manage branch assignments on a route.

---

**Example — Set up the Northern Route with customers and an agent**

Scenario: Operations manager creates the Arusha / Moshi distribution route before the first delivery run.

1. Navigate to **Parties › Routes** (`/admin/routes`). Click **New Route**.
2. Enter Name `Northern Route`, Location Identifier `Arusha–Moshi Corridor`. Click **Submit**. System assigns code `RTE-0003`.
3. Click `RTE-0003` to open `/admin/routes/uid/<uid>`.
4. **Branches panel:** Company `Orbix Trading Co.`, Branch `ARU — Arusha Branch`. Click **Assign**.
5. **Customers panel:** type `Kilimanjaro`, select `CUST-0007 — Kilimanjaro Stores Ltd`. Click **Assign**. Repeat for `CUST-0011 — Moshi Distributors`.
6. **Agents panel:** type `Baraka`, select `AGNT-0004 — Baraka Hamisi` (External). Tick **Primary**. Click **Assign**.

The Northern Route is now ready. The delivery team can filter orders and customers by route, and the agent Baraka Hamisi appears as the primary contact on route-based reports.

---

# Sales and Point of Sale

This chapter covers everything from quoting a customer through to collecting payment, including recurring and blanket agreements, advanced pricing, and the Point of Sale cashier workflow.

---

## Overview

The sales module follows the order-to-cash (O2C) path:

```
Quotation → Sales Order → Delivery → Sales Invoice → Payment
```

Walk-in cash sales skip the first three steps and begin directly with a Sales Invoice or a POS sale.

**What "order-to-cash" means.** Order-to-cash is the end-to-end business process that starts the moment a customer expresses intent to buy and ends when the business has received and accounted for the money. Each step in the chain creates a document that serves as a control point: stock is only committed when an order is confirmed, goods only leave the warehouse when a delivery is recorded, and revenue is only recognised when an invoice is finalised. Without this chain, businesses would have no audit trail, no way to match what was promised to what was shipped, and no reliable basis for the accounts receivable ledger.

**Required permissions** — the navigation menu only shows items your role includes. Key permission groups:

| Activity | Permission codes required |
|---|---|
| Quotations | `SALES.QUOTE.VIEW`, `SALES.QUOTE.CREATE`, `SALES.QUOTE.SEND`, `SALES.QUOTE.ACCEPT` |
| Sales Orders | `SALES.ORDER.VIEW`, `SALES.ORDER.CREATE`, `SALES.ORDER.CONFIRM`, `SALES.ORDER.CANCEL` |
| Deliveries | `SALES.DELIVERY.VIEW`, `SALES.DELIVERY.CREATE` |
| Sales Invoices | `SALES.INVOICE.VIEW`, `SALES.INVOICE.CREATE`, `SALES.INVOICE.SETTLE`, `SALES.INVOICE.VOID` |
| Sales Returns | `SALES.RETURN.VIEW`, `SALES.RETURN.CREATE` |
| Blanket Orders | `SALES.BLANKET.VIEW`, `SALES.BLANKET.CREATE` |
| Standing Orders | `SALES.STANDING.VIEW`, `SALES.STANDING.CREATE` |
| Pricing Rules | `SALES.PRICING.RULE.VIEW`, `SALES.PRICING.RULE.MANAGE` |
| POS (tills) | `POS.TILL.VIEW`, `POS.TILL.MANAGE` |
| POS (cashier) | `POS.SESSION.OPEN`, `POS.SALE.CREATE`, `POS.SESSION.VIEW` |
| POS (close/reconcile) | `POS.SESSION.CLOSE`, `POS.SESSION.RECONCILE` |

Contact your administrator if an expected menu item is missing.

---

## 1. Quotations

Navigate to **Sales › Quotations** (`/admin/quotations`).

**What a quotation is.** A quotation (also called a quote or a sales proposal) is a formal written offer that the business sends to a customer. It states the products, quantities, unit prices, any discounts, and a validity period — that is, the date by which the customer must respond if the offered price is to be honoured.

**Why quotations exist.** Without a quotation, pricing agreements between a salesperson and a customer exist only verbally. A quotation creates a timestamped, auditable record of what was offered at what price, protects the business from disputes, and gives management visibility of the sales pipeline (how many offers are outstanding, what value, and when they expire). It also means that once a customer accepts, the system can convert the offer into a Sales Order automatically, carrying the agreed prices across without any re-entry.

**When a quotation is used.** A quotation is raised when a customer asks "what will it cost me?" before committing to buy — typically by a salesperson or sales assistant. It sits at the very beginning of the O2C chain: nothing is reserved from stock and no financial entry is made; the quotation is a promise, not a transaction.

**How a quotation flows.** A quotation begins as a `DRAFT` (editable, no number yet). When it is sent to the customer the status moves to `SENT` and a `QUOTE-####` number is assigned. If the customer accepts, the quotation moves to `ACCEPTED` and a Sales Order is created automatically with the same lines and agreed prices. If the customer declines it is `REJECTED`; if the validity date passes without a response the system marks it `EXPIRED` and acceptance is blocked.

### 1.1 Create a quotation

1. Navigate to **Sales › Quotations** (`/admin/quotations`).
2. Click **New Quotation**.
3. In the **Customer** field, type part of the customer name or code and select the correct entry from the list. Do not type or paste a raw ID.
4. Set **Quote Date** (today by default) and **Valid Until** (the date the offer expires).
5. Click **Save**. The quotation is saved in **DRAFT** status. A quote number is assigned later when you send it.

**Required fields:** Customer, Quote Date, Valid Until.

### 1.2 Add lines to a quotation

1. Open the draft quotation (navigate to **Sales › Quotations** then click the quotation row, or go to `/admin/quotations/uid/{uid}`).
2. In the **Lines** section, search for the product by name or code and select it.
3. Choose a **Unit**, enter **Quantity**, and optionally enter a **Line Discount** (either a percentage or a fixed amount — not both).
4. Click **Add Line**. The system calculates net amount, VAT, and gross from the configured price list.

Repeat for each product. You can also add **Service** products; these are priced the same way but do not affect stock.

To remove a line, click the delete icon on the line row. Lines can only be changed while the quotation is in DRAFT.

### 1.3 Send a quotation

When the quotation is ready to share with the customer:

1. Open the draft quotation.
2. Click **Send**.
3. The status changes to **SENT** and a quote number (QUOTE-####) is assigned.

**Prerequisites:** The quotation must have at least one line, and the Valid Until date must be today or in the future.

### 1.4 Accept or reject a quotation

When the customer responds:

- **Accept** — click **Accept** on the sent quotation. A **Sales Order** is created automatically with the same lines and discounts. The quotation status changes to **ACCEPTED**. A success message shows the new order number and provides a link to it.
- **Reject** — click **Reject**. The quotation status changes to **REJECTED**.

Both actions require the `SALES.QUOTE.ACCEPT` permission. If the Valid Until date has already passed, the system prevents acceptance and marks the quotation **EXPIRED**.

### 1.5 Quotation statuses

| Status | Meaning |
|---|---|
| DRAFT | Being prepared; lines can be added or removed |
| SENT | Sent to customer; awaiting response |
| ACCEPTED | Customer accepted; Sales Order created |
| REJECTED | Customer declined |
| EXPIRED | Valid Until date passed before acceptance |

---

**Example — Quotation for Karibu Supermarkets:**

Salesperson Ali opens **Sales › Quotations** (`/admin/quotations`) and clicks **New Quotation**. He types "Karibu" in the Customer field and selects **Karibu Supermarkets Ltd**. He sets Quote Date to **2026-06-14** and Valid Until to **2026-07-14**, then saves. The quotation is created in DRAFT with no number yet.

Ali adds two lines:
- Product **Unga wa Ngano 2kg**, Unit **CARTON (12 pcs)**, Qty **50**, Line Discount **0%** — system prices at TZS 18,000 per carton = TZS 900,000 net.
- Product **Mafuta ya Kupikia 1L**, Unit **CARTON (12 pcs)**, Qty **30**, Line Discount **5%** — list price TZS 22,000; after 5% = TZS 20,900 per carton = TZS 627,000 net.

VAT at 18% is added by the system: total gross = TZS 1,535,400 + VAT. Ali clicks **Send** — status becomes SENT and the number **QUOTE-0047** is assigned.

Karibu calls back and accepts. Ali clicks **Accept**. The system creates **Sales Order SO-0112** from the same lines and shows a link. Quotation status is now ACCEPTED.

---

## 2. Sales Orders

Navigate to **Sales › Sales Orders** (`/admin/sales-orders`).

**What a Sales Order is.** A Sales Order (SO) is the internal document that records a customer's confirmed purchase intent. It lists the products, quantities, agreed prices, and any discounts. Unlike a quotation (which is an offer), a Sales Order is a commitment: the business has agreed to supply, and the customer has agreed to buy.

**Why Sales Orders exist.** The Sales Order is the control centre of the fulfilment process. Two things happen that do not happen at the quotation stage: first, confirming the order **reserves stock** so those goods cannot be sold to someone else; second, the order creates the traceability link between the customer's request, the delivery that ships the goods, and the invoice that bills them. Without Sales Orders, a warehouse would not know what to pick, finance would have no basis for revenue recognition, and there would be no way to track partial deliveries or backorders systematically.

**When a Sales Order is used.** A Sales Order is created either automatically (when a customer accepts a quotation) or directly (when a salesperson or order-desk clerk enters it fresh — for example, a telephone order that was never quoted). It is used any time a customer is buying goods that need to be fulfilled from stock and billed after delivery, as opposed to a walk-in cash purchase which goes straight to an invoice.

**How a Sales Order flows.** An SO begins as `DRAFT` (a `SO-####` number is assigned immediately at creation, even in draft). When the user confirms it, the status moves to `CONFIRMED` and stock is soft-reserved. As deliveries are made against the order the status tracks fulfilment progress (`PARTIALLY_FULFILLED` → `FULFILLED`). As invoices are raised from those deliveries it tracks invoicing progress (`PARTIALLY_INVOICED` → `CLOSED`). Cancellation at any point releases the reservations.

**Stock reservation explained.** When you confirm a Sales Order, the system writes a "soft reservation" against each product in the warehouse. The reserved quantity is not physically moved — the goods stay on the shelf — but they are marked as committed. This means the available-to-promise figure (what can still be sold to other customers) is reduced immediately. A reservation prevents double-selling: two salespeople cannot independently confirm orders for the same last 10 units. When a delivery is made, the reservation for the delivered quantity is released (because the goods have actually left) and the on-hand balance is reduced instead.

### 2.1 Create a standalone Sales Order

1. Navigate to **Sales › Sales Orders** (`/admin/sales-orders`).
2. Click **New Order**.
3. Pick the **Customer** by name.
4. Set **Order Date**. Optionally set a **Document Discount** (percentage or amount — not both).
5. Click **Save**. The order is created in **DRAFT**.

### 2.2 Add lines to a Sales Order

The same process as adding quotation lines. Lines can only be added, edited, or removed while the order is in DRAFT.

### 2.3 Confirm an order

Confirming an order reserves stock for every GOODS line.

1. Open the draft order at **Sales › Sales Orders** then click the order row (or navigate to `/admin/sales-orders/uid/{uid}`).
2. The order must have at least one line.
3. Click **Confirm**.
4. The status changes to **CONFIRMED** and each line shows its reserved quantity.

This requires the `SALES.ORDER.CONFIRM` permission. A user who can create orders but not confirm them will not see this button.

### 2.4 Cancel an order

Cancelling an order releases any stock reservations.

1. Open the order.
2. Click **Cancel**, enter an optional reason, and confirm.

Cancellation is allowed from any status except **CANCELLED** and **CLOSED**.

### 2.5 Order status lifecycle

| Status | Meaning |
|---|---|
| DRAFT | Being prepared; no stock reserved |
| CONFIRMED | Stock reserved |
| PARTIALLY_FULFILLED | At least one delivery made; not all lines delivered |
| FULFILLED | All lines delivered |
| PARTIALLY_INVOICED | Some deliveries invoiced |
| CLOSED | Fully fulfilled and fully invoiced |
| CANCELLED | Cancelled; reservations released |

---

## 3. Deliveries

Navigate to **Sales › Deliveries** (`/admin/deliveries`).

**What a Delivery is.** A Delivery is the document that records goods physically leaving the warehouse and being shipped or handed to the customer. It references the Sales Order it fulfils and specifies the exact quantities dispatched on that date. It is also sometimes called a "dispatch note" or "delivery note."

**Why Deliveries exist.** Without a delivery document there is no system record of when goods actually left — only what was ordered. The Delivery is the trigger for two critical events: it reduces the physical stock balance (goods have left), and it becomes the source document for the customer's invoice (you invoice what you delivered, not what was ordered — because partial deliveries are common). The delivery is also the moment that the cost of those goods is posted to the Profit and Loss account as Cost of Goods Sold (COGS), matching the cost to the revenue period in which the goods are billed.

**When a Delivery is used.** A delivery is created by a warehouse or logistics clerk once goods are ready to ship, always against a confirmed Sales Order. Multiple deliveries can be made against a single order (backorders), and each delivery generates its own invoice.

**How a Delivery flows.** A delivery can only be created against a `CONFIRMED` or `PARTIALLY_FULFILLED` Sales Order. When created it is immediately `CONFIRMED` (there is no separate pick/confirm step in the current version). The delivery is immutable — once confirmed it cannot be edited; corrections are handled through a Sales Return. Stock is reduced at the branch and the SO line counters are updated. An invoice is generated from the delivery as a separate action.

**Full delivery vs partial delivery (backorder).** If you deliver less than the full ordered quantity on any line, the system creates a partial delivery and the order moves to `PARTIALLY_FULFILLED`. The remaining undelivered quantity is the **backorder**. You create a second delivery later for the remaining quantity. Each delivery is independent and can be invoiced separately.

### 3.1 Create a delivery

1. Navigate to **Sales › Deliveries** (`/admin/deliveries`) and click **New Delivery**, or open a confirmed Sales Order and use the **Create Delivery** action.
2. The delivery create form is at `/admin/deliveries/create`. Pick the **Sales Order** by order number.
3. The form shows all open (undelivered) lines with the remaining quantity pre-filled.
4. Adjust individual line quantities if you are making a **partial delivery** (backorder). The quantity you enter cannot exceed the open balance.
5. Set **Delivery Date** and click **Submit**.

Deliveries are created immediately in **CONFIRMED** status and cannot be undone. Each delivery is assigned a DELIVERY-#### number.

### 3.2 Partial delivery (backorder)

Enter a quantity less than the open balance on any line to create a partial delivery. The Sales Order status moves to **PARTIALLY_FULFILLED**. Create another delivery later for the remaining quantity.

### 3.3 Generate an invoice from a delivery

Once goods are delivered, you can invoice the customer for that delivery:

1. Open the delivery (navigate to **Sales › Deliveries**, click the row, or go to `/admin/deliveries/uid/{uid}`).
2. Click **Create Invoice from Delivery**.
3. A draft **Sales Invoice** is created automatically with the delivered lines. The doc discount from the source order is pro-rated to the delivered quantity.

Proceed to section 4 to finalise the invoice.

---

## 4. Sales Invoices

Navigate to **Sales › Invoices** (`/admin/sales-invoices`).

**What a Sales Invoice is.** A Sales Invoice is the formal billing document sent to the customer. It is the legal record of the sale: it states what was sold, at what price, the VAT due, and the amount the customer owes. Once finalised, a sales invoice is immutable — it cannot be edited, only voided (which raises a reversing credit note).

**Why Sales Invoices exist.** The invoice is the document that creates the customer's obligation to pay. In accounting terms, finalising an invoice posts the revenue to the General Ledger (DR Accounts Receivable or Cash / CR Sales Revenue and VAT Payable). For credit customers it opens an AR (Accounts Receivable) item — a record of the amount owed — which is then tracked and aged until payment is received. Without invoices, the business has no formal claim on the customer and no basis for its revenue figures or tax filings.

**Direct invoices vs SO-sourced invoices.** There are two origins for a sales invoice:

- **`DIRECT` (walk-in):** created manually for a cash customer who is buying on the spot with no prior order. Stock is issued and revenue posted at the moment of finalisation.
- **`SALES_ORDER`-sourced:** created from a Delivery (section 3.3). These invoices post revenue only — stock was already issued when the delivery was confirmed. This distinction prevents the same goods from being costed twice.

**Why the origin matters.** If a `SALES_ORDER`-sourced invoice also issued stock, the Cost of Goods Sold would be posted twice: once at delivery and once at invoicing. The system prevents this by tracking the origin on every invoice and skipping the stock-issue step for SO-sourced invoices. A walk-in invoice (DIRECT) has no prior delivery, so it must issue stock at finalisation — that is the only point at which goods leave.

**The VAT calculation.** All prices are entered tax-exclusive (net). The system calculates VAT per line using each product's VAT status (Standard 18%, Zero-Rated 0%, or Exempt 0%). The VAT rate is snapshotted onto the line at sale time so a later rate change cannot silently alter a historical invoice. The invoice prints a VAT analysis breaking down the tax by rate band.

**Price snapshots.** When you add a product line to an invoice (or any sales document), the system reads the current price from the price list and records it permanently on that line. If the price list is updated tomorrow, the historical invoice is unaffected — it retains the price that applied at sale time. This is called a "price snapshot" and is mandatory for any document that is legally an audit record.

An invoice is an offer sent to a customer. When accepted it becomes a Sales Order automatically.

### 4.1 Create a direct (walk-in) invoice

1. Navigate to **Sales › Invoices** (`/admin/sales-invoices`).
2. Click **New Invoice**.
3. Pick the **Customer** by name. Optionally pick an **Agent** and a **Route**; if omitted the system uses the logged-in user's linked agent and that agent's primary route.
4. Click **Save**. A draft invoice is created.

### 4.2 Add lines to an invoice

Same process as adding lines to a quotation or order. Lines can only be added, edited, or removed while the invoice is in DRAFT.

### 4.3 Record a payment

Payments can be recorded on a draft invoice before it is finalised.

1. In the **Payments** panel, click **Add Payment**.
2. Choose the **Tender Type**: Cash or Mobile Money.
3. Enter the **Amount**. For Mobile Money, enter the transaction reference.
4. Click **Add**.

Recording payments requires the `SALES.INVOICE.SETTLE` permission (separate from the permission to create lines).

For **cash / walk-in customers** the total payments must equal the invoice gross before you can finalise. Credit customers may have a balance that becomes an open AR item.

### 4.4 Finalise an invoice

1. Open the draft invoice.
2. Click **Finalise**.
3. The status changes to **FINALISED** and an invoice number is assigned.

After finalisation:
- For DIRECT invoices, stock is issued from the branch.
- For invoices from a delivery, revenue is posted (stock was already issued at delivery).
- For credit customers, an AR open item is created for any unpaid balance.

**Paid-in-full rule:** walk-in (cash) customers must be fully paid before finalisation is allowed.

**Credit limit:** if a credit customer's outstanding balance plus this invoice would exceed their credit limit, finalisation is blocked unless you hold the `SALES.CREDIT.OVERRIDE` permission.

### 4.5 Void an invoice

**What voiding means.** Voiding a finalised invoice reverses its financial effect: the revenue is reversed, the AR item is cancelled, and VAT is adjusted. The original invoice number is retained on the record (voiding is not deletion — the document remains as evidence that the transaction happened and was corrected). A reversing credit note is raised automatically. Use voiding only when an invoice was issued in error; for goods returned by the customer use a Sales Return (section 5) instead.

A finalised invoice can be voided if it was issued in error:

1. Open the finalised invoice (navigate to **Sales › Invoices**, click the row, or go to `/admin/sales-invoices/uid/{uid}`).
2. Click **Void**, enter a mandatory reason, and confirm.
3. The invoice status changes to **VOID** and a reversing credit note is posted.

The original invoice number is retained on the voided record. Voiding is not the same as deletion.

### 4.6 Invoice status reference

| Status | Meaning |
|---|---|
| DRAFT | Being prepared; lines and payments editable |
| FINALISED | Issued; posted to AR and GL; immutable |
| VOID | Reversed; credit note posted; number retained |

---

**Example — Full O2C: Karibu Supermarkets (credit account):**

Continuing from section 1's example, Sales Order **SO-0112** was created from the accepted quotation. The warehouse confirms goods are ready.

1. **Confirm SO:** Ali opens **Sales › Sales Orders** (`/admin/sales-orders`), clicks SO-0112, and clicks **Confirm**. Status becomes CONFIRMED; stock reserved — 50 cartons Unga + 30 cartons Mafuta.

2. **Deliver:** Ali navigates to **Sales › Deliveries** (`/admin/deliveries`), clicks **New Delivery**, picks **SO-0112**. He delivers the full quantity (50 + 30 cartons) on 2026-06-15 and submits. Delivery **DELIVERY-0089** is created; SO status → FULFILLED.

3. **Invoice from delivery:** Ali opens DELIVERY-0089 at `/admin/deliveries/uid/{uid}` and clicks **Create Invoice from Delivery**. A DRAFT invoice is created. Since Karibu is a CREDIT_ACCOUNT customer, Ali clicks **Finalise** without adding a payment — the unpaid balance of TZS 1,535,400 (plus 18% VAT = TZS 1,811,772 gross) becomes an open AR item. Invoice number **INV-0203** is assigned.

**Example — Walk-in direct invoice (cash customer):**

Cashier Fatuma opens **Sales › Invoices** (`/admin/sales-invoices`) and clicks **New Invoice**. She picks customer **Amina Hassan (walk-in)**. She adds one line: **Sukari 1kg**, Unit **KG**, Qty **5**, price TZS 2,200/kg = TZS 11,000 net; VAT 18% = TZS 1,980; gross = TZS 12,980. In the Payments panel she adds **Cash, Amount TZS 12,980**. She clicks **Finalise** — status becomes FINALISED, invoice number **INV-0204** is assigned, stock is issued, and the cash is recorded.

---

## 5. Sales Returns (RMA)

Navigate to **Sales › Sales Returns** (`/admin/sales-returns`).

**What a Sales Return is.** A Sales Return (also called an RMA — Return Merchandise Authorisation) is the document that records goods coming back from the customer. It is always tied to a specific delivery so the system knows exactly which shipment is being reversed.

**Why Sales Returns exist.** When a customer returns goods — because they are damaged, wrong, or surplus — several things need to happen simultaneously: the stock must come back into the warehouse, the customer's account must be credited (so they do not owe money for goods they no longer have), the revenue must be reversed, and the cost of those goods must be put back. Doing these four things as separate manual steps would be error-prone and would leave the accounts temporarily out of balance. A Sales Return handles all four atomically: on creation, stock is returned to the branch, a credit note is raised automatically, revenue and VAT are reversed, and (for a credit customer) the AR open item is reduced.

**When a Sales Return is used.** A Sales Return is created by a warehouse clerk or sales supervisor when goods arrive back from the customer. It can only reference a previous delivery — you cannot return more than was delivered on that delivery, and returns against the same delivery can be processed in multiple batches up to the full delivered quantity.

**How a Sales Return flows.** A Sales Return is created and immediately `CONFIRMED` in a single step. There is no draft stage. The return number (`RET-####`) is assigned at creation. A credit note is raised in the same transaction.

**What a credit note is.** A credit note is the financial document that reduces what the customer owes. If an invoice says "you owe us TZS 100,000," a credit note for TZS 20,000 on the same account means the customer's balance is reduced to TZS 80,000. Credit notes are raised automatically by the system on a Sales Return (for the returned goods) and on a void (for a fully reversed invoice); they cannot be raised manually through the sales return screen.

### 5.1 Create a return

1. Navigate to **Sales › Sales Returns** (`/admin/sales-returns`) and click **New Return**, or go directly to `/admin/sales-returns/create`.
2. Pick the **Delivery** by its delivery number.
3. The form shows the delivered lines. Enter the **Quantity Returned** for each line being returned (cannot exceed the quantity delivered minus what has already been returned).
4. Set the **Return Date** and enter a **Reason**.
5. Click **Submit**.

Returns are created directly in **CONFIRMED** status. Stock is returned to the branch. A credit note is raised automatically (pro-rated to the returned quantity).

### 5.2 Returnable quantity

Each return reduces the returnable balance for that delivery line. You can process multiple returns against the same delivery line until the full delivered quantity has been returned.

---

**Example — Partial sales return (Karibu Supermarkets):**

Two days after delivery, Karibu reports 5 cartons of Mafuta ya Kupikia arrived leaking. The stock controller opens **Sales › Sales Returns** (`/admin/sales-returns`), clicks **New Return**, and picks delivery **DELIVERY-0089**. She enters **Qty Returned = 5** on the Mafuta line, sets return date **2026-06-17**, reason **"Damaged packaging — leaking oil"**, and submits. Return **RET-0031** is created in CONFIRMED status. Five cartons of Mafuta stock are returned to the warehouse and a credit note for TZS 104,500 (5 × TZS 20,900) plus VAT is automatically raised against INV-0203.

---

## 6. Blanket Orders

Navigate to **Sales › Blanket Orders** (`/admin/blanket-orders`).

**What a Blanket Order is.** A Blanket Order is a framework supply agreement with a customer that fixes the unit price for a product and commits to a total quantity over a defined validity window. Instead of raising a new Sales Order with price negotiations each time the customer buys, both parties agree upfront: "you will buy up to 1,000 bags at TZS 6,500 each over the next six months." Each actual purchase draws down against this agreement — these draws are called **releases** or **call-offs**.

**Why Blanket Orders exist.** Regular customers who buy in predictable volumes benefit from negotiated prices locked in for a period, while the business gains revenue predictability and avoids repeated pricing discussions. Without a blanket order, each purchase is independent — a busy sales desk might accidentally apply inconsistent prices to the same customer, or forget what was agreed. The blanket order is the single source of truth for the agreed terms. It also automatically prevents over-delivery: the system tracks how much has been called off against the committed quantity and refuses to draw more than the total commitment.

**When a Blanket Order is used.** A blanket order is created by a sales manager when a long-term supply contract is signed with a customer. Once active, the sales team creates call-off Sales Orders against it whenever the customer exercises part of their commitment.

**How a Blanket Order flows.** A blanket order is `ACTIVE` from creation. Call-offs (draw-downs) produce ordinary Sales Orders that flow through the normal O2C chain (confirm → deliver → invoice). The blanket itself tracks the remaining committed quantity on each product line. When all quantities are fully drawn, the validity window expires, or a manager closes it manually, the blanket becomes `CANCELLED` (no further releases). The blanket document itself posts no stock and no GL entries — only the resulting Sales Orders do.

### 6.1 Create a blanket order

1. Navigate to **Sales › Blanket Orders** (`/admin/blanket-orders`) and click **New Blanket Order**, or go directly to `/admin/blanket-orders/create`.
2. Select the **Company** and **Branch**.
3. Pick the **Customer** by name.
4. Set **Currency**, **Valid From**, and **Valid To** dates.
5. Add one or more **Lines**: for each, pick the product by name, choose a unit, and enter the committed quantity and unit price.
6. Optionally add notes (up to 500 characters).
7. Click **Save**.

The blanket order is created with status **ACTIVE** and assigned an order number.

### 6.2 Create a release (draw-down)

When the customer calls off part of their commitment:

1. Open the blanket order (navigate to `/admin/blanket-orders/uid/{uid}`).
2. Click **Draw Release** (visible only when the blanket is ACTIVE and you hold the manage permission).
3. Enter the **Branch ID** for the delivery branch.
4. Pick the **Agent** by name.
5. For each line you want to include, tick it and enter the **Draw Quantity** (cannot exceed the remaining committed quantity).
6. Click **Create Release**.

A **Sales Order** is created and linked to the blanket. The committed remaining quantity on each drawn line decreases.

### 6.3 Cancel a blanket order

Open the blanket order and click **Cancel** then confirm. The status changes to **CANCELLED**. Previously generated Sales Orders from this blanket are unaffected.

### 6.4 Blanket order statuses

| Status | Meaning |
|---|---|
| ACTIVE | Agreement in force; releases can be drawn |
| CANCELLED | Cancelled; no further releases |

---

**Example — Blanket supply agreement:**

Duka Kuu Ltd signs a 6-month supply deal for 1,000 bags of Mchele wa Zambia at TZS 6,500/bag. The sales manager opens **Sales › Blanket Orders** (`/admin/blanket-orders`), creates a new blanket for **Duka Kuu Ltd**, Valid From **2026-07-01** to **2026-12-31**, adds one line: **Mchele wa Zambia 10kg**, Unit **BAG**, Qty **1,000**, Unit Price **6,500**. Order is saved as ACTIVE.

In July, Duka Kuu calls off 200 bags. The sales manager opens the blanket, clicks **Draw Release**, draws 200 bags → Sales Order **SO-0145** is created. Remaining committed quantity on the blanket is now 800 bags.

---

## 7. Standing Orders (Recurring)

Navigate to **Sales › Standing Orders** (`/admin/standing-orders`).

**What a Standing Order is.** A Standing Order (also called a recurring order or repeat order) is a template that tells the system to generate a new Sales Order automatically on a regular schedule — weekly, bi-weekly, or monthly. It holds the customer, the products, the quantities, and the prices for a typical delivery cycle.

**Why Standing Orders exist.** Some customers receive the same goods on the same schedule every week or month — a hotel that takes 50 loaves of bread every Monday, or a distributor that replenishes the same five products on the first of each month. Without standing orders, the sales desk must manually create the same Sales Order repeatedly, risking forgetting, using the wrong quantities, or applying the wrong prices. A standing order removes the repetitive work and ensures consistent, timely order creation without manual intervention.

**When a Standing Order is used.** Standing orders are set up by a sales manager or sales administrator for customers with regular, predictable buying patterns. Once active, the cashier or sales desk does not need to do anything — orders appear automatically. The standing order can be paused if supply is interrupted and resumed when normal service resumes.

**How a Standing Order flows.** A standing order is `ACTIVE` from creation. The system runs a nightly check and generates a new Sales Order (in `DRAFT` status — a human must confirm it deliberately) for every active standing order whose next run date is today or earlier. After generation, the next run date advances by one period. A standing order can be `PAUSED` (no generation while paused) and `RESUMED`, or permanently `CANCELLED`. The generated Sales Orders flow through the normal O2C chain.

### 7.1 Create a standing order

1. Navigate to **Sales › Standing Orders** (`/admin/standing-orders`) and click **New Standing Order**, or go directly to `/admin/standing-orders/create`.
2. Pick the **Branch**, **Customer**, and set **Currency**.
3. Choose a **Frequency**: Daily, Weekly, Bi-Weekly, or Monthly.
4. Set a **Start Date**. Optionally set an **End Date**; leave it blank for open-ended.
5. Add lines: pick each product and unit by name, enter quantity and unit price.
6. Click **Save**.

The standing order is created with status **ACTIVE** and the first `Next Run Date` is set.

### 7.2 Pause and resume

- **Pause** — open the standing order (navigate to `/admin/standing-orders/uid/{uid}`) and click **Pause**. No Sales Orders are generated while the order is paused.
- **Resume** — click **Resume** to make it active again.

### 7.3 Trigger a run manually

Click **Trigger Now** to generate a Sales Order immediately (without waiting for the scheduled run). The next run date advances by the configured frequency.

### 7.4 Cancel a standing order

Click **Cancel** to stop the standing order permanently. The status changes to **CANCELLED** and no further Sales Orders are generated.

### 7.5 Automatic generation

The system checks every night at midnight and generates Sales Orders for all ACTIVE standing orders whose next run date is today or earlier. Each generated order advances the next run date by one period.

### 7.6 Standing order statuses

| Status | Meaning |
|---|---|
| ACTIVE | Generating on schedule |
| PAUSED | Temporarily stopped; can be resumed |
| CANCELLED | Permanently stopped |

---

**Example — Weekly bread delivery for Hoteli ya Pwani:**

Hoteli ya Pwani orders 50 loaves of bread every Monday. The sales rep opens **Sales › Standing Orders** (`/admin/standing-orders`), creates a new standing order for **Hoteli ya Pwani**, Frequency **Weekly**, Start Date **2026-06-16**, no end date. Line: **Mkate Mzima**, Unit **PCS**, Qty **50**, Unit Price **TZS 800**. The system auto-generates **Sales Order SO-0151** on Monday 16 June, then **SO-0158** on 23 June, and so on every week without manual action.

---

## 8. Pricing Rules

Navigate to **Sales › Pricing Rules** (`/admin/pricing-rules`).

**What pricing rules are.** Pricing rules are pre-configured exceptions to the standard price list. Without any rules, every customer is charged the standard list price for a product. Rules let the business offer lower prices automatically under specific conditions — for example, a lower price per bag when a customer orders more than 100 bags at once (a quantity break), or a privately negotiated price that applies only to one specific customer.

**Why pricing rules exist.** Manual price overrides by sales staff are error-prone and untraceable. A salesperson might give a loyal customer a discount one day and forget it the next, or apply the wrong discount tier. Pricing rules encode the business's commercial agreements in the system so that the correct price is applied automatically and consistently every time, without needing the salesperson to remember or calculate. They also create an audit trail: when a line is priced, the system records which rule was applied (standard list, a tier, or a customer-specific price) as a diagnostic field on the line.

**How pricing rules resolve.** When a product line is added to any sales document (quotation, order, invoice, or POS sale), the system runs a single price-resolution check in the following priority order, applying the first rule that matches:

1. **Customer price** (highest priority) — a contract price for this exact customer and product
2. **Active promotion** — a time-limited offer matching the product or product category
3. **Price tier** — a volume-break price if the ordered quantity meets the tier's minimum
4. **Standard list price** — the product's price on the customer's assigned price list
5. **No price found** — the line is rejected; the product cannot be sold without a price

Once the price is resolved, the standard totals calculation (net, VAT, gross) runs unchanged — pricing rules only affect the unit price input.

### 8.1 Price tiers (quantity breaks)

**What a price tier is.** A price tier is a volume-break discount: if a customer orders at least a minimum quantity of a specific product, they receive a lower unit price than the standard list price. For example, the standard price for a 50 kg bag of cement is TZS 15,200, but any order of 100 or more bags is priced at TZS 14,500 per bag.

**Why tiers exist.** Volume pricing rewards large orders and encourages customers to consolidate purchases. Without tiers, a salesperson would have to manually override the price and justify the discount each time — an inconsistent and unaudited process. Tiers make the volume price automatic, consistent, and visible on the price list.

A price tier gives a lower unit price when a customer orders at least a minimum quantity of a product on a given price list.

**To create a tier:**

1. Open **Sales › Pricing Rules** (`/admin/pricing-rules`) and go to the **Price Tiers** tab.
2. Click **New Tier**.
3. Pick the **Product** and **Price List** by name.
4. Enter **Min Quantity**, **Unit Price**, and **Currency**.
5. Click **Save**.

The tier status is **ACTIVE**. To deactivate a tier, click the **Deactivate** button on the row; the tier is soft-deactivated and no longer applied to new transactions.

You cannot have two active tiers for the same product, price list, and minimum quantity combination.

### 8.2 Customer prices (contract prices)

**What a customer price is.** A customer price (also called a contract price or a customer-specific price) is a fixed unit price agreed between the business and one specific customer for one specific product. It overrides every other pricing rule — including tiers and promotions — and applies regardless of quantity, as long as it is active and within its effective date window.

**Why customer prices exist.** Key accounts and long-term customers often negotiate individualised prices as part of a supply agreement — prices that are lower than the standard list but not published generally. Storing these as customer prices means the correct price is applied automatically on every transaction for that customer, with no risk of the wrong price being used by a different salesperson who does not know the agreement.

A customer price sets a fixed unit price for a specific product for a specific customer, overriding the standard price list.

**To create a customer price:**

1. Open **Sales › Pricing Rules** (`/admin/pricing-rules`) and go to the **Customer Prices** tab.
2. Click **New Customer Price**.
3. Pick the **Customer** and **Product** by name.
4. Enter the **Unit Price** and **Currency**.
5. Optionally set **Effective From** and **Effective To** dates for a time-limited contract.
6. Click **Save**.

Only one customer price record can exist per customer-and-product pair. Deactivate the existing record before creating a new one is not possible (the unique constraint is status-agnostic); raise a support request to change an existing contract price.

### 8.3 Pricing resolution order

When a sale line is priced the system applies the first matching rule in this priority:

1. Customer price (highest priority)
2. Active promotion (managed via the API; no UI currently)
3. Price tier
4. Standard list price
5. No price configured (the line is rejected)

---

**Example — Volume tier for cement:**

The sales manager opens **Sales › Pricing Rules** (`/admin/pricing-rules`), goes to **Price Tiers**, and clicks **New Tier**. He picks product **Saruji 50kg**, Price List **Wholesale TZS**, Min Quantity **100**, Unit Price **TZS 14,500**, Currency **TZS**. Any order for 100+ bags on the Wholesale price list will now use TZS 14,500 instead of the standard TZS 15,200.

**Example — Contract price for Karibu Supermarkets:**

Under the **Customer Prices** tab the manager creates: Customer **Karibu Supermarkets Ltd**, Product **Unga wa Ngano 2kg**, Unit Price **TZS 17,500** (negotiated). From the next sale, whenever a sales line is added for this customer and product, TZS 17,500 is applied — regardless of the price list.

---

## 9. Point of Sale

Navigate to the **Point of Sale** group in the sidebar.

**What the Point of Sale module is.** Point of Sale (POS) is the in-store face-to-face retail workflow. It provides a cashier-facing checkout screen to ring up products, accept cash, and issue receipts. Everything processed through POS is ultimately a sales invoice — POS wraps the invoice channel with till management and session-level drawer accountability.

**Why POS exists as a distinct module.** A back-office sales invoice is fine for credit-account customers who receive goods on account and pay later. Counter retail is different: a cashier is processing many small transactions rapidly, cash is flowing in and out of a physical drawer, and at end of day the business needs to verify that the cash in the drawer matches what the system says was collected. The POS module adds the `till` and `session` layer on top of the invoice to manage this accountability — without it, cash sales would have no way to reconcile the physical drawer to the books.

**What a till is.** A till is a physical cash register position at a branch (for example, "Counter 1" or "Counter 2"). In the system a till is a named record tied to a branch and to a bank/cash account that represents the drawer. Multiple tills can operate at the same branch simultaneously. A till must be `ACTIVE` before a session can be opened on it.

**What a session is.** A session is the till's working period — typically one business day or one shift. Before a cashier can ring sales, they open a session by declaring the opening float (the cash placed in the drawer to make change). During the session every POS sale, refund, and payout is tracked against that session. At end of day the cashier or manager closes the session by counting the cash in the drawer, and then a manager reconciles the session to post any variance to the General Ledger.

**What a POS sale is.** A POS sale is a cash counter transaction. It produces a finalised `DIRECT`-origin sales invoice: stock is issued from the branch and revenue is posted in the same step. The invoice number (`INV-####`) is assigned on the spot. No quotation, sales order, or delivery step is involved — POS is designed for speed at the counter.

POS is used for face-to-face retail transactions. A **till** is a physical cash register position. Each till must be opened in a **session** before sales can be processed. The session is closed and reconciled at end of day.

### 9.1 Roles

| Role | Typical permissions |
|---|---|
| Cashier | Open session, ring sales, view sessions |
| Manager | All cashier permissions plus create/deactivate tills, close sessions, reconcile |

Your administrator assigns the appropriate POS permissions to your role. Contact them if POS is not visible in your menu.

### 9.2 Set up a till

This is a one-time setup task done by a manager.

1. Navigate to **Point of Sale › POS Tills** (`/admin/pos/tills`).
2. Click **New Till**.
3. Enter a **Till Name** (e.g. "Counter 1").
4. Pick the **Branch** by name.
5. Click **Create Till**.

The till is created with status **ACTIVE**. To deactivate a till, click **Deactivate** on its row.

### 9.3 Open a session (start of day)

**What opening a session means.** Opening a session declares the start of a cashier's working period on a specific till. The opening float is the starting cash in the drawer (coins and notes placed there before the first sale so the cashier can make change). The system records this amount and uses it as the baseline for the end-of-day cash reconciliation. Only one session can be open on a till at a time — you cannot accidentally open a second session on the same counter without closing the first.

1. Navigate to **Point of Sale › POS Sessions** (`/admin/pos/sessions`).
2. Click **Open Session**.
3. Pick the **Till** by name (only ACTIVE tills are listed).
4. Enter the **Opening Float** — the cash amount placed in the drawer at the start of the day.
5. Click **Open Session**.

A new session is created with status **OPEN**. Only one session can be open on a till at a time.

### 9.4 Ring a sale

**What "ringing a sale" means.** This is the cashier's checkout step: entering the products and quantities the customer is buying, taking the cash the customer hands over, and completing the transaction. The system calculates the total, computes the change due, and — on completion — finalises the sales invoice, issues the stock, posts the revenue, and issues the receipt.

**What the tendered amount is.** The tendered amount is the cash the customer physically hands to the cashier — often a round number larger than the total. If the total is TZS 13,000 and the customer hands over TZS 20,000, the tendered amount is TZS 20,000 and the change is TZS 7,000. The system calculates the change and the cashier returns it. A sale cannot be submitted if the tendered amount is less than the total.

1. Navigate to **Point of Sale › Point of Sale** (`/admin/pos/sell`) — this is the checkout screen.
2. If your organisation has more than one company, select the correct company.
3. Pick the **Session** — only OPEN sessions are listed.
4. Pick the **Customer** by name.
5. Pick the **Agent** by name (required — leaving Agent blank will cause the sale to be rejected).
6. Set the **Currency**.
7. Click **Add Line**. Pick the **Product** by name; confirm or adjust the **Unit**, enter **Quantity** and **Unit Price**, and optionally a line **Discount**.
8. Add further lines as needed. The **Total** updates in the footer.
9. Enter the **Tendered Amount** (the cash handed over by the customer). The **Change** is calculated immediately. The sale cannot be submitted if the tendered amount is less than the total.
10. Click **Complete Sale**.

A success receipt is displayed showing the invoice number and total. Click **View Invoice** to open the full invoice, or **New Sale** to start the next transaction.

**Notes:**
- POS sales are always settled in cash. There is no tender-type selector; payment is recorded as Cash automatically.
- The agent field is mandatory on the backend; leaving it blank will cause the sale to be rejected.

### 9.5 Record a payout

**What a payout is.** A payout is any cash that leaves the drawer during the session that is not change given to a customer. The two types are:
- **Paid Out:** a safe drop (moving excess cash from the drawer to the safe mid-shift) or a petty-cash payment made from the drawer.
- **Refund:** cash paid back to a customer as a refund.

Both types reduce the expected closing cash and are recorded so the end-of-day reconciliation remains accurate. Without recording payouts, the drawer would appear short at close-of-day even though the cash was accounted for.

A payout records cash leaving the drawer during the session — for example, a drop to the safe or a petty-cash refund.

1. Open the session detail (**Point of Sale › POS Sessions** (`/admin/pos/sessions`), click **View** on the OPEN session, or navigate to `/admin/pos/sessions/uid/{uid}`).
2. Click **Record Payout**.
3. Select the **Type**: Paid Out (cash removed from the drawer) or Refund (customer cash refund).
4. Enter the **Amount** and a **Reason**.
5. Click **Record**.

Both payout types reduce the expected closing cash. The live X-read total updates automatically.

### 9.6 X-Read (live totals during the day)

**What an X-Read is.** An X-Read (from the retail term "X-reading the register") is a snapshot of running totals for the current session without closing or resetting it. Cashiers and managers use it to verify the session is on track during the day — for example, after a safe drop, to confirm the expected cash figure has decreased correctly. Unlike a Z-Read (see section 9.8), an X-Read does not close anything.

The **X-Read** card on the session detail page shows running totals without closing the session:

| Field | Meaning |
|---|---|
| Sales Total | Sum of all POS sale totals in this session |
| Payouts | Sum of all payouts (PAID_OUT + REFUND) |
| Expected Cash | Opening Float + Sales Total − Payouts |
| Invoice Count | Number of sales processed |

Click the refresh icon to reload the X-read at any time.

### 9.7 Close a session (end of day)

**What closing a session means.** Closing a session is the end-of-shift step where the cashier physically counts the cash in the drawer and enters the counted amount. The system compares this to the expected cash (computed from the opening float plus all sales minus all payouts) and calculates the variance. A zero variance means the drawer balances perfectly. A positive variance (more cash than expected) is a till surplus. A negative variance (less cash than expected) is a till shortage. The session moves to `CLOSED` but the variance is not yet posted to the General Ledger — that happens at reconciliation.

Closing records the physical cash count.

1. Open the session detail (navigate to `/admin/pos/sessions/uid/{uid}`).
2. Click **Close Session**.
3. Enter the **Counted Cash** — the amount physically in the drawer.
4. Optionally add closing notes.
5. Click **Close**.

The session status changes to **CLOSED** and a **variance** is computed:

```
Variance = Counted Cash − Expected Cash
```

- **Positive variance** (over): more cash in the drawer than expected.
- **Negative variance** (short): less cash than expected.
- **Zero variance**: drawer balances exactly.

### 9.8 Reconcile a session (Z-Read)

**What reconciliation is.** Reconciliation is the final accounting step for a session. A manager reviews the closed session, confirms the figures are correct, and posts the cash variance — if any — to the General Ledger. After reconciliation the session is permanently locked and no further changes are possible. The result is called the **Z-Read** (again from retail terminology: the Z-read "zeroes" the register for the next session).

**What the GL posting means.** If the drawer is over (more cash than expected), the excess is income — the business has more cash than it should, which is a gain. The system debits the Cash account and credits a Till Surplus income account. If the drawer is short, the shortfall is an expense — the business is missing cash. The system debits a Till Shortage expense account and credits Cash. A zero variance produces no journal entry.

Reconciliation posts the variance to the general ledger and produces the final Z-Read report.

1. Open a **CLOSED** session (navigate to `/admin/pos/sessions/uid/{uid}`).
2. Click **Reconcile**.
3. Optionally add notes.
4. Click **Reconcile**.

The session status changes to **RECONCILED**. The **Z-Read** card shows all session figures plus the variance and (if non-zero) the journal reference:

- **Over variance** — debit Cash, credit income account 4900 (Till Surplus).
- **Short variance** — debit expense account 5170 (Till Shortage), credit Cash.
- **Zero variance** — no journal posted.

After reconciliation the session is read-only and no further sales or payouts can be recorded.

### 9.9 Cash variance explained

**What cash variance is.** Cash variance is the difference between the cash that should be in the drawer (the expected cash, calculated by the system) and the cash that is actually in the drawer (the counted cash, declared by the cashier). Every business aims for zero variance — a perfectly balanced drawer — but small discrepancies occur in practice due to rounding on change, counting errors, or occasional till errors.

The formula is:

```
Expected Cash = Opening Float + Sum of all cash sales in the session − Sum of all payouts
Variance = Counted Cash − Expected Cash
```

A variance greater than zero means there is more cash in the drawer than the sales records account for (a surplus — perhaps the cashier made change errors that favoured the business). A variance less than zero means there is less cash than expected (a shortage — perhaps an error or a discrepancy). Both are posted to the GL at reconciliation so the books always reflect the actual cash held.

### 9.10 Session lifecycle

| Status | Meaning |
|---|---|
| OPEN | Sales and payouts can be recorded |
| CLOSED | Session counted; reconciliation pending |
| RECONCILED | Final Z-read produced; GL posted; session closed |

Transitions are one-way: OPEN → CLOSED → RECONCILED. A session cannot be re-opened.

### 9.11 Daily workflow summary

1. **Open** a session on your till with the day's opening float.
2. **Ring sales** as customers arrive.
3. **Record payouts** for any cash removed from the drawer.
4. Check the **X-Read** at any time for running totals.
5. At end of day, **count** the cash in the drawer.
6. **Close** the session by entering the counted amount.
7. A manager **reconciles** the closed session; the system posts any variance to the GL.

---

**Example — Walk-in cash sale (full POS day):**

Cashier Jane starts her shift at Duka Moja. She navigates to **Point of Sale › POS Sessions** (`/admin/pos/sessions`) and clicks **Open Session**. She picks till **Counter 1** (Branch: Dar es Salaam Main) and enters Opening Float **TZS 100,000**. Session **SES-0041** opens with status OPEN.

During the morning Jane processes three customers at **Point of Sale › Point of Sale** (`/admin/pos/sell`):

1. She picks session **SES-0041**, customer **Mteja wa Kawaida**, agent **Omar Salim**, currency TZS. She adds: **Sukari 1kg** × 2 @ TZS 2,500 = TZS 5,000; **Mafuta ya Kupikia 1L** × 1 @ TZS 8,000 = TZS 8,000. Total TZS 13,000. Customer hands over TZS 20,000 — Change shown as TZS 7,000. Jane clicks **Complete Sale** — Invoice **INV-0211** issued.

2. Second sale: **Unga wa Ngano 2kg** × 3 @ TZS 3,200 = TZS 9,600. Tendered TZS 10,000, change TZS 400. Invoice INV-0212 issued.

3. Third sale: **Chumvi 500g** × 5 @ TZS 500 = TZS 2,500. Tendered exact. Invoice INV-0213 issued.

At midday Jane does a safe drop: she opens session detail (`/admin/pos/sessions/uid/{uid}`), clicks **Record Payout**, Type **Paid Out**, Amount **TZS 20,000**, Reason "Midday safe drop". Expected cash now: TZS 100,000 + TZS 25,100 − TZS 20,000 = **TZS 105,100**.

Jane checks the X-Read: Sales Total TZS 25,100, Payouts TZS 20,000, Expected Cash TZS 105,100, Invoice Count 3. Correct.

At end of day Jane counts the drawer: TZS 105,200 (TZS 100 over). She clicks **Close Session**, enters Counted Cash **TZS 105,200** — Variance is **+TZS 100.00** (over).

Manager Rehema opens the session detail, clicks **Reconcile**. Status → RECONCILED. Z-Read confirms the +TZS 100 variance and shows Journal **JNL-0519**: DR Cash 100 / CR Till Surplus (4900) 100.

---

# Procurement (Procure-to-Pay)

This chapter covers the full procure-to-pay (P2P) chain from raising a purchase request through to settling the supplier's invoice, including goods receipt, landed costs, purchase returns, and purchase settings.

---

## Overview

**What the P2P chain is and why it exists.**
Every business that buys goods or services needs a structured buying process. Without it, anyone could commit the business to purchases without authorisation, prices would go unverified, goods might be received without a matching order, and the business would have no audit trail when a supplier dispute arose. The Procure-to-Pay chain is the end-to-end control framework for buying: it starts with an internal request, works through supplier selection, raises a formal commitment to buy, records what actually arrived, validates the supplier's invoice against what was ordered and received, and ends with a payment that clears the liability. Each step is a gate — the next step cannot start until the previous one is completed and, where required, approved. This is how the system enforces budget control, prevents fraud, and supports accurate financial reporting.

The P2P chain follows this path:

```
Purchase Requisition → RFQ → Supplier Quotes → Award → Purchase Order
    → Goods Receipt → [Landed Cost] → Supplier Bill → 3-Way Match → AP Payment
```

For direct purchases without a sourcing process, the chain can start at the Purchase Order (created from a converted requisition or from the RFQ award).

**Required permissions** — the navigation menu only shows items your role includes:

| Activity | Permission codes |
|---|---|
| Requisitions | `PURCHASE.REQUISITION.VIEW`, `PURCHASE.REQUISITION.CREATE`, `PURCHASE.REQUISITION.APPROVE` |
| RFQ | `PURCHASE.RFQ.VIEW`, `PURCHASE.RFQ.CREATE`, `PURCHASE.RFQ.AWARD` |
| Supplier Quotes | `PURCHASE.QUOTE.VIEW`, `PURCHASE.QUOTE.CREATE` |
| Purchase Orders | `PURCHASE.ORDER.VIEW`, `PURCHASE.ORDER.CREATE`, `PURCHASE.ORDER.VOID`, `PURCHASE.ORDER.APPROVE` |
| Goods Receipt | `PURCHASE.GOODS_RECEIPT.VIEW`, `PURCHASE.RECEIVE` |
| Landed Cost | `PURCHASE.LANDED_COST.VIEW`, `PURCHASE.LANDED_COST.CREATE`, `PURCHASE.LANDED_COST.CONFIRM` |
| Supplier Bills / AP | `AP.VIEW`, `AP.BILL.ENTER`, `AP.BILL.MATCH` |
| Purchase Returns | `PURCHASE.RETURN.VIEW`, `PURCHASE.RETURN.CREATE`, `PURCHASE.RETURN.CONFIRM` |
| Purchase Settings | `PURCHASE.SETTINGS.VIEW`, `PURCHASE.SETTINGS.EDIT` |

Contact your administrator if an expected menu item is missing.

---

## 1. Purchase Requisitions

Navigate to **Purchasing › Purchase Requisitions** (`/admin/purchase-requisitions`).

**What a purchase requisition is.**
A purchase requisition (also called a "purchase request" or PR) is a formal internal document raised by a member of staff to request that the business buys goods or services. It is not sent to a supplier — it is an internal request that must be reviewed and approved before any external commitment is made. Think of it as a "permission to buy" request.

**Why it exists.**
Without a requisition step, any employee could initiate a purchase directly, bypassing budget checks, management oversight, and cost-centre accountability. The requisition creates a written record of what is needed, when it is needed, and at what estimated cost. This allows management to prioritise spending, check that the purchase fits the budget, and maintain an audit trail from the first idea to the final payment.

**When it is used.**
A requisition is raised whenever a department or individual needs to buy something and does not have pre-authorised standing orders in place. Common triggers are low stock (detected by the Low Stock flag in Inventory), a project requirement, or routine scheduled re-ordering. The person raising the requisition is typically a storekeeper, department head, or anyone with the `PURCHASE.REQUISITION.CREATE` permission.

**How it flows.**
A requisition starts as a DRAFT (being prepared) and must be submitted before it enters the approval queue (SUBMITTED). An authorised approver then approves or rejects it. An APPROVED requisition can be converted — either directly into a Purchase Order if the supplier is already known, or into an RFQ if prices need to be gathered from multiple suppliers first. Once converted, the requisition status becomes CONVERTED and no further action is possible on it; the work continues on the PO or RFQ that was created.

### 1.1 Create a requisition

1. Navigate to **Purchasing › Purchase Requisitions** (`/admin/purchase-requisitions`).
2. Click **New Requisition**, or go directly to `/admin/purchase-requisitions/create`.
3. Set the **Required By** date and optionally a cost centre and notes.
4. Add lines: for each item, pick the **Product** by name, choose a **Unit**, and enter the **Requested Quantity** and an **Estimated Unit Cost**.
5. Click **Save**. The requisition is saved in **DRAFT**.

### 1.2 Submit a requisition

When the requisition is complete and ready for approval:

1. Open the draft requisition (navigate to `/admin/purchase-requisitions/uid/{uid}`).
2. Click **Submit**.
3. The status changes to **SUBMITTED** and the requisition is routed for approval.

### 1.3 Approve or reject a requisition

An approver (a user with `PURCHASE.REQUISITION.APPROVE`) reviews submitted requisitions at **Purchasing › Purchase Requisitions** (`/admin/purchase-requisitions`).

- **Approve** — open the submitted requisition and click **Approve**. Status → **APPROVED**. The Convert action becomes available.
- **Reject** — click **Reject**, enter a mandatory reason, and confirm. Status → **REJECTED**. The requisitioner is notified via the audit trail.

### 1.4 Convert a requisition

An approved requisition can be converted into either a Purchase Order or an RFQ:

1. Open the approved requisition (navigate to `/admin/purchase-requisitions/uid/{uid}`).
2. Click **Convert**.
3. Choose the target type:
   - **Purchase Order** — a DRAFT PO is created immediately from the requisition lines.
   - **RFQ** — a DRAFT RFQ is created; proceed to section 2 to send it to suppliers.
4. Confirm. A link to the created document appears. The requisition status changes to **CONVERTED**.

### 1.5 Cancel a requisition

A requisition can be cancelled from any non-final status (DRAFT, SUBMITTED, APPROVED):

1. Open the requisition.
2. Click **Cancel**, enter an optional reason, and confirm. Status → **CANCELLED**.

### 1.6 Requisition status reference

| Status | Meaning |
|---|---|
| DRAFT | Being prepared |
| SUBMITTED | Awaiting approval |
| APPROVED | Approved; ready to convert |
| REJECTED | Rejected by approver |
| CONVERTED | Converted to PO or RFQ |
| CANCELLED | Cancelled |

---

**Example — Requisition for office stationery:**

Store clerk Amani opens **Purchasing › Purchase Requisitions** (`/admin/purchase-requisitions`) and clicks **New Requisition**. He sets Required By **2026-06-20**, notes "Monthly stationery re-order", and adds two lines:

- Product **Karatasi A4 (Ream)**, Unit **REAM**, Qty **20**, Estimated Cost **TZS 8,500** each.
- Product **Kalamu Nyeusi**, Unit **BOX**, Qty **5**, Estimated Cost **TZS 3,200** each.

He saves — requisition **REQ-0072** is created in DRAFT. He clicks **Submit** — status → SUBMITTED.

Purchasing manager Neema opens the requisition and clicks **Approve** — status → APPROVED, estimated total TZS 186,000. She clicks **Convert** and picks **RFQ** — RFQ **RFQ-0031** is created in DRAFT.

---

## 2. RFQ (Request for Quotation)

Navigate to **Purchasing › RFQs / Sourcing** (`/admin/rfqs`).

**What an RFQ is.**
An RFQ (Request for Quotation) is a document sent to one or more suppliers asking them to submit their prices and delivery terms for a specified list of goods or services. It is not a commitment to buy — it is a competitive enquiry. The business collects the responses (supplier quotes), compares them, and chooses the best offer.

**Why it exists.**
Without a sourcing step, the business might always buy from the same supplier at whatever price they name, with no mechanism to check whether better value is available elsewhere. An RFQ enforces competitive sourcing: multiple suppliers are asked the same question at the same time, their responses are recorded in the system, and the selection is documented — protecting the business from claims of favouritism and ensuring value for money.

**When it is used.**
An RFQ is used when the buying price is not already fixed by contract or catalogue and at least one competitive comparison is warranted. It is typically triggered by an approved purchase requisition (the Convert → RFQ path) or raised directly by a purchasing officer when restocking at scale. The person sending the RFQ and capturing quotes holds the `PURCHASE.RFQ.CREATE` and `PURCHASE.QUOTE.CREATE` permissions; awarding it requires `PURCHASE.RFQ.AWARD`.

**How it flows.**
An RFQ is created in DRAFT with the product lines and the invited suppliers. When sent (SENT), suppliers are notified to respond. As each supplier responds with a price, a **Supplier Quote** is captured against the RFQ (QUOTES_RECEIVED). The purchasing officer then compares the quotes and awards the RFQ to the preferred supplier (AWARDED). Awarding automatically creates a Purchase Order in DRAFT at the winning quote's prices — the sourcing stage is complete and the buying stage begins.

### 2.1 Create an RFQ

An RFQ can be created directly or by converting an approved requisition (see section 1.4).

**To create directly:**

1. Navigate to **Purchasing › RFQs / Sourcing** (`/admin/rfqs`) and click **New RFQ**, or go to `/admin/rfqs/create`.
2. Set the **Response Due Date** and optionally add notes.
3. In the **Invited Suppliers** section, pick each supplier by name. Invite at least one supplier.
4. Add lines: pick each product by name, choose a unit, and enter the required quantity.
5. Click **Save**. The RFQ is created in **DRAFT**.

### 2.2 Send an RFQ to suppliers

1. Open the DRAFT RFQ (navigate to `/admin/rfqs/uid/{uid}`).
2. Click **Send**. Status → **SENT**. Suppliers are notified that they should submit a quote.

### 2.3 Capture supplier quotes

**What a supplier quote is.**
A supplier quote (also called a quotation or bid) is the formal price response a supplier submits in reply to the RFQ. It states the price per unit, any lead time, and any validity period. The system captures these responses electronically so they can be compared side-by-side.

When a supplier responds with a price:

1. Open the SENT RFQ (navigate to `/admin/rfqs/uid/{uid}`).
2. Click **Capture Quote**.
3. Pick the **Supplier** by name (only invited suppliers are listed).
4. Optionally set a valid-until date, lead time in days, and notes.
5. For each RFQ line, enter the **Quoted Quantity** and **Unit Price**.
6. Click **Save**. The quote is recorded with status **RECEIVED** and the RFQ status moves to **QUOTES_RECEIVED**.

Repeat for each responding supplier. You can compare their prices side-by-side in the quotes panel on the RFQ detail page.

### 2.4 Award the RFQ

To select the winning supplier and create a Purchase Order:

1. In the quotes panel on the RFQ detail page (`/admin/rfqs/uid/{uid}`), identify the preferred quote (usually the lowest compliant price).
2. Click **Award** on that quote row.
3. The winning quote status changes to **AWARDED** and all other quotes become **NOT_AWARDED**. The RFQ status changes to **AWARDED**.
4. A **Purchase Order** is created in DRAFT from the awarded quote lines and prices. A link to the PO is shown.

### 2.5 Cancel an RFQ

Open the RFQ (navigate to `/admin/rfqs/uid/{uid}`) and click **Cancel**. Status → **CANCELLED**. An awarded RFQ cannot be cancelled.

### 2.6 RFQ status reference

| Status | Meaning |
|---|---|
| DRAFT | Being prepared |
| SENT | Sent to suppliers; awaiting responses |
| QUOTES_RECEIVED | At least one supplier quote captured |
| AWARDED | Winning supplier selected; PO created |
| CANCELLED | Cancelled |

---

**Example — RFQ for cement (continuing from requisition example, fresh scenario):**

A warehouse requisition for 500 bags of **Saruji 50kg** has been approved and converted to RFQ **RFQ-0031**. Purchasing officer Zawadi opens **Purchasing › RFQs / Sourcing** (`/admin/rfqs`), opens RFQ-0031, and adds two invited suppliers: **Tanzania Cement Distributors** and **Simba Cement Ltd**. Response Due Date is set to **2026-06-17**. She clicks **Send** — RFQ goes to SENT.

Both suppliers respond. Zawadi captures two quotes:
- **Tanzania Cement Distributors**: 500 bags @ TZS 14,800 each = TZS 7,400,000 (lead time 3 days).
- **Simba Cement Ltd**: 500 bags @ TZS 14,500 each = TZS 7,250,000 (lead time 5 days).

After review, Zawadi awards the RFQ to **Simba Cement Ltd** (cheaper price, acceptable lead time). Purchase Order **PO-0088** is created in DRAFT at TZS 14,500/bag.

---

## 3. Purchase Orders

Navigate to **Purchasing › Purchase Orders** (`/admin/purchase-orders`).

**What a Purchase Order is.**
A Purchase Order (PO) is the formal, legally binding document that a business sends to a supplier to commit to buying specific goods or services at agreed prices and quantities. It defines what is being ordered, how many units, at what price, and by when. Once placed, it is the reference document for everything that follows — the goods receipt checks deliveries against it, the supplier invoice is matched against it, and the payment settles it.

**Why companies use Purchase Orders.**
Without a PO, the business has no formal record of what it committed to buy. The supplier could deliver the wrong quantity or charge a different price, and there would be no agreed baseline to dispute it. POs provide commitment control (approvals before spending), a budget anchor (the ordered amount is known), an audit trail (who ordered what, when, at what price), and the document foundation for both the goods receipt (what was ordered versus what arrived) and the 3-way match (ordered, received, billed — all three must agree). They also protect the business legally: a supplier cannot claim an order was placed if no PO exists.

**When a PO is raised.**
A PO is raised after a purchase has been authorised — either by converting an approved requisition directly into a PO, or by awarding an RFQ which creates the PO automatically at the winning supplier's quoted prices. There is no standalone "New PO" form in the UI; every PO originates from one of these two paths.

**How a PO flows.**
A PO starts as a DRAFT (lines can be edited freely). When the lines are finalised, the PO is placed (ORDERED), which sends it to the supplier, locks the lines, and assigns the PO number. Goods arrive and are recorded against the PO via Goods Receipts — the PO tracks how many units remain outstanding and moves through PARTIALLY_RECEIVED to RECEIVED as deliveries arrive. Once fully received (or if the business accepts a shortfall), the PO can be closed (CLOSED). If the PO is no longer needed before all goods are received, it can be voided (VOID). If a PO approval threshold is enabled in Purchase Settings, POs above the configured amount require an additional approval before goods can be received.

### 3.1 View and manage a DRAFT Purchase Order

1. Navigate to **Purchasing › Purchase Orders** (`/admin/purchase-orders`).
2. Open the DRAFT PO (navigate to `/admin/purchase-orders/uid/{uid}`).
3. While the PO is in DRAFT you can:
   - **Add a line** — pick the product by name, choose a unit, enter the ordered quantity and unit cost.
   - **Edit a line** — change quantity or cost on an existing line.
   - **Remove a line** — click the delete icon on the line row.

### 3.2 Place a Purchase Order

Placing the PO sends it to the supplier and locks the lines.

1. Open the DRAFT PO (it must have at least one line).
2. Click **Place**.
3. Status → **ORDERED** and a PO number (PO-####) is assigned.

### 3.3 Close a Purchase Order

Closing finalises the PO without receiving all goods (for example, if a partial shipment is accepted as complete).

1. Open the PO (navigate to `/admin/purchase-orders/uid/{uid}`) — status ORDERED, PARTIALLY_RECEIVED, or RECEIVED.
2. Click **Close**.
3. Status → **CLOSED**. The PO is read-only.

### 3.4 Void a Purchase Order

Voiding cancels the PO if goods have not all been received.

1. Open the PO (status DRAFT, ORDERED, or PARTIALLY_RECEIVED).
2. Click **Void**, enter a mandatory reason, and confirm.
3. Status → **VOID**.

### 3.5 PO approval (if enabled)

If your administrator has enabled PO approval thresholds in Purchase Settings, Purchase Orders above the configured amount enter a **PENDING** approval state after being placed. An approver with `PURCHASE.ORDER.APPROVE` must then approve or reject the PO before goods can be received.

PO approval actions are currently only available via the API; contact your administrator or a system manager if a PO is stuck awaiting approval.

### 3.6 PO status reference

| Status | Meaning |
|---|---|
| DRAFT | Being prepared; lines editable |
| ORDERED | Placed; sent to supplier; lines locked |
| PARTIALLY_RECEIVED | Some lines received; outstanding qty remains |
| RECEIVED | All lines fully received |
| CLOSED | Manually closed |
| VOID | Cancelled before full receipt |

---

**Example — Placing the cement PO:**

Zawadi opens **Purchasing › Purchase Orders** (`/admin/purchase-orders`), finds PO-0088 (DRAFT, 500 bags @ TZS 14,500), reviews the line, and clicks **Place**. Status → ORDERED. The formal PO number is confirmed and the document is locked for editing. A PDF can be generated and sent to Simba Cement Ltd.

---

## 4. Goods Receipt

Navigate to **Purchasing › Goods Receipts** (`/admin/goods-receipts`).

**What a Goods Receipt is.**
A Goods Receipt (GR), sometimes called a Goods Received Note (GRN), is the document that records the physical arrival of goods from a supplier. It is raised by the storekeeper or receiving officer at the moment goods are checked in, linking the delivery to the Purchase Order that authorised it. The GR is the point at which inventory increases: the quantities received are added to stock on-hand at the branch.

**Why it exists.**
A Goods Receipt serves three critical purposes. First, it records what actually arrived — not what was ordered, not what was billed, but what the storekeeper physically counted and accepted. Second, it updates the stock ledger immediately so the business knows what it holds (an important distinction: ordering goods does not increase stock; receiving them does). Third, it forms the third document in the 3-way match: the supplier's invoice can only be paid once the system confirms that the goods billed were both ordered (PO) and received (GR). Without a GR, the business could pay for goods it never received.

**When it is used.**
A GR is created by the storekeeper or receiving officer each time a supplier delivers goods against an outstanding Purchase Order. If a supplier delivers in multiple shipments, a separate GR is created for each delivery. The permission required is `PURCHASE.RECEIVE`. Only placed Purchase Orders (ORDERED or PARTIALLY_RECEIVED) can have a GR raised against them.

**How it flows.**
The storekeeper picks the PO and the system shows all outstanding (unreceived) lines pre-filled with the remaining quantities. The storekeeper adjusts the quantities if the delivery is partial, sets the receipt date, and submits. The GR is created with status RECEIVED, a GRN number is assigned, stock increases at the branch, and the PO's outstanding quantities are updated. The PO moves to PARTIALLY_RECEIVED or RECEIVED depending on whether all lines are now complete. A GR cannot be edited after submission; errors are corrected by voiding the GR (an API-level operation) or by raising a Purchase Return (section 7).

### 4.1 Receive goods

1. Navigate to **Purchasing › Goods Receipts** (`/admin/goods-receipts`) and click **New Goods Receipt**, or go directly to `/admin/goods-receipts/create`.
2. Pick the **Purchase Order** by its PO number.
3. The form lists all open (unreceived) lines with the outstanding quantity pre-filled.
4. Adjust individual quantities if you are receiving a **partial shipment**. The quantity cannot exceed the outstanding balance on each line.
5. Set the **Receipt Date**.
6. Click **Submit**.

The goods receipt is created with status **RECEIVED** and assigned a GRN-#### number. Stock is added to the branch. The PO status updates:

- Partial receipt → PO status **PARTIALLY_RECEIVED**
- Full receipt → PO status **RECEIVED**

### 4.2 Partial receipts (multiple deliveries)

If the supplier delivers in stages, create a separate goods receipt for each delivery. Each GRN records the quantity received on that date. The PO tracks the cumulative received and outstanding quantities across all GRNs.

### 4.3 Goods receipt status reference

| Status | Meaning |
|---|---|
| RECEIVED | Active receipt; stock increased |
| VOID | Voided (reversed); stock decremented (API only) |

---

**Example — Receiving cement:**

Simba Cement delivers 500 bags on 2026-06-22. Storekeeper John opens **Purchasing › Goods Receipts** (`/admin/goods-receipts`), clicks **New Goods Receipt**, and picks PO **PO-0088**. The form shows 500 bags Saruji 50kg outstanding. John enters Receipt Date **2026-06-22** and keeps all 500 bags. He submits — GRN **GRN-0061** is created (status RECEIVED), 500 bags added to stock at the branch, PO-0088 status → RECEIVED.

**Partial receipt scenario:** If Simba had delivered only 300 bags on day 1, John would receive 300 bags (GRN-0061), PO → PARTIALLY_RECEIVED, outstanding = 200 bags. When the remaining 200 arrive, John creates GRN-0062 for 200 bags, PO → RECEIVED.

---

## 5. Landed Costs

Navigate to **Purchasing › Landed Costs** (`/admin/landed-costs`).

**What landed costs are.**
Landed cost is the total cost of getting an imported or shipped product to your warehouse — not just the purchase price, but all the additional charges incurred along the way: freight, customs duty, port clearing fees, insurance, and other incidentals. The "landed cost" is what the goods actually cost you once they are physically in your possession.

**Why they are captured.**
If only the purchase price is recorded as the inventory cost, the business undervalues its stock and understates the true cost of goods sold (COGS). For example, cement bought at TZS 14,500/bag but with TZS 2,900/bag in freight and clearing costs actually costs TZS 17,400/bag to hold. Selling it at any price below TZS 17,400 is a loss — but a business recording only TZS 14,500 would not see that loss until the end of the period. Capitalising landed costs into inventory value ensures the stock is valued at its true cost, the cost-of-goods-sold figure is accurate, and the balance sheet reflects the real investment in inventory.

**When it is used.**
A landed cost is entered after the goods have been received (a GRN exists) and the incidental charges are known — either at the time of receipt or when the freight/clearing invoice arrives. The accountant or purchasing officer enters the charges against the relevant GRN(s) and confirms the document. The permission required is `PURCHASE.LANDED_COST.CREATE` and `PURCHASE.LANDED_COST.CONFIRM`.

**How it flows.**
A landed cost document is created (DRAFT) with the allocation basis (By Value or By Quantity), linked to one or more GRNs, and the charge lines (Freight, Duty, Clearing, Insurance, Other) are entered. On confirmation (CONFIRMED), the system allocates each charge proportionally to the GR lines and capitalises the allocated amount into the inventory value of each product — raising the moving-average cost and posting the GL entry. The accounting entry at confirmation is: **DR Inventory (1300) / CR Landed Cost Clearing (2160)**. When the freight or duty invoice later arrives from the supplier and is bill-matched, the clearing account is debited back: **DR Landed Cost Clearing / CR Accounts Payable** — leaving a zero balance in the clearing account. A confirmed landed cost is immutable.

### 5.1 Create a landed cost

1. Navigate to **Purchasing › Landed Costs** (`/admin/landed-costs`) and click **New Landed Cost**, or go directly to `/admin/landed-costs/create`.
2. Select the **Allocation Basis**:
   - **By Value** — charges are spread proportionally to the value of each GR line.
   - **By Quantity** — charges are spread proportionally to the quantity received on each GR line.
3. Pick the **Goods Receipt(s)** by GRN number. You can include multiple GRNs in one landed cost document.
4. Add one or more **Charges**: select the charge type (Freight, Duty, Clearing, Insurance, or Other) and enter the amount.
5. Click **Save**. The landed cost is created in **DRAFT**.

### 5.2 Confirm a landed cost

Confirming allocates the charges to the GR lines and posts the cost adjustment to the GL.

1. Open the DRAFT landed cost (navigate to `/admin/landed-costs/uid/{uid}`).
2. Click **Confirm**.
3. Status → **CONFIRMED**. The allocation per GR line is shown in the detail.

A confirmed landed cost cannot be edited. If there is an error, contact your administrator.

### 5.3 Landed cost status reference

| Status | Meaning |
|---|---|
| DRAFT | Being prepared; charges editable |
| CONFIRMED | Charges allocated; GL posted; immutable |

---

**Example — Landed cost for imported cement:**

The cement shipment also incurred TZS 850,000 in port clearing fees and TZS 600,000 in freight. Accountant Sarah opens **Purchasing › Landed Costs** (`/admin/landed-costs`), clicks **New Landed Cost**, selects Allocation Basis **By Quantity**, and picks GRN **GRN-0061** (500 bags). She adds two charges:

- Type **Clearing**, Amount TZS 850,000.
- Type **Freight**, Amount TZS 600,000.

Total landed cost TZS 1,450,000. She saves (DRAFT), reviews the per-bag allocation (TZS 2,900/bag), and clicks **Confirm**. Status → CONFIRMED. The moving-average cost for Saruji 50kg increases by TZS 2,900/bag, and the GL is posted accordingly.

---

## 6. Supplier Bills and 3-Way Bill Match

Navigate to **Accounting › Payables** (`/admin/ap/supplier-bills`).

**What a supplier bill is.**
A supplier bill (also called a purchase invoice or vendor invoice) is the invoice the supplier sends requesting payment for goods delivered or services rendered. It is the supplier's claim against the business. In the system, it is entered as a formal financial document that creates an accounts payable liability — the business now owes the supplier money.

**Why a supplier bill must be matched before payment.**
A supplier could, accidentally or deliberately, send an invoice for more units than were delivered, at a higher price than agreed, or for items never ordered at all. Paying it without verification means the business overpays. The 3-way match is the systematic check that prevents this: it compares the bill to both the Purchase Order (what was agreed) and the Goods Receipt (what was actually received). All three must align within an acceptable tolerance before payment is authorised. This process is called "3-way matching" because it matches three documents: the bill, the PO, and the GR.

**When it is used.**
A supplier bill is entered when the supplier's invoice arrives, after the goods have been received and (optionally) landed costs applied. It is entered by an accounts payable clerk with the `AP.BILL.ENTER` permission. Matching is triggered automatically when the bill is entered (if a PO is linked) or can be run from the bills list.

**How the 3-way match works.**
The system compares each bill line against the corresponding PO line (agreed price and quantity) and GR line (received quantity). If the billed price and billed quantity are within the configured tolerance of the ordered and received values, the line is MATCHED. If either is outside tolerance, the line is HELD (HELD_PRICE_VARIANCE or HELD_QTY_VARIANCE). A held bill cannot be approved for payment until each held line is either corrected or manually accepted (VARIANCE_ACCEPTED) by a user with `AP.BILL.MATCH`. The GL entry posted at bill-match for goods lines is: **DR GRNI (2150) / CR Accounts Payable (2100)** — this clears the GRNI bridge set up at the goods receipt. Service bill lines (no GR) post: **DR Purchases (5150) / CR Accounts Payable**.

### 6.1 Enter a supplier bill

1. Navigate to **Accounting › Enter Bill** (`/admin/ap/supplier-bills/enter`).
2. Pick the **Supplier** by name.
3. Enter the supplier's own **Invoice Number**, **Bill Date**, and **Due Date**.
4. Set the **Currency**.
5. Optionally pick the **Purchase Order** by number. Linking the PO enables the 3-way match (see section 6.2). For service bills with no PO, leave this blank.
6. Add **Bill Lines**: for each, pick the product, enter the billed quantity and unit price.
7. Click **Enter Bill & Match**. The bill is created and the 3-way match runs automatically.

### 6.2 Understanding the 3-way match

The 3-way match compares three documents for each bill line:

```
Supplier Bill line  ←→  Purchase Order line  ←→  Goods Receipt line
    (billed qty/price)       (ordered qty/price)      (received qty/price)
```

The match result for each line is one of:

| Match Status | Meaning |
|---|---|
| MATCHED | Quantities and prices are within tolerance; line approved |
| HELD_PRICE_VARIANCE | Bill unit price is outside the acceptable tolerance versus the PO price |
| HELD_QTY_VARIANCE | Billed quantity is outside the acceptable tolerance versus the received quantity |
| VARIANCE_ACCEPTED | A held variance was reviewed and manually accepted |

The overall bill status depends on its lines:

| Bill Status | Meaning |
|---|---|
| MATCHED | All lines matched; bill ready for payment approval |
| HELD | One or more lines have an unresolved variance |

### 6.3 Accept a variance

If a bill line is HELD due to a price or quantity variance:

1. Open the bill (navigate to `/admin/ap/supplier-bills/uid/{uid}`) or use the **Match** action on the bills list at **Accounting › Payables** (`/admin/ap/supplier-bills`).
2. Review the variance amount and percentage shown on the held line.
3. If the variance is acceptable, click **Accept Variance** on that line.
4. When all held lines are resolved, the bill status moves to **MATCHED**.

Accepting variances requires the `AP.BILL.MATCH` permission.

### 6.4 Re-run match from the bills list

For a bill that was entered without running a match (or needs re-matching after a correction):

1. Navigate to **Accounting › Payables** (`/admin/ap/supplier-bills`).
2. Click **Match** on the bill row.
3. The match result is displayed inline.

### 6.5 Service bills (no PO)

For invoices from service suppliers where there is no corresponding PO or GR:

- Leave the Purchase Order field blank when entering the bill.
- No 3-way match is run.
- The bill is entered for manual review and approval.

### 6.6 Bill status reference

| Status | Meaning |
|---|---|
| DRAFT | Entered but not yet matched |
| MATCHED | All lines matched; ready for payment |
| HELD | One or more lines have an open variance |
| APPROVED | Approved for payment by the AP team |
| PARTIALLY_PAID | Payment partially applied |
| PAID | Fully paid |

### 6.7 Record an AP payment

**What an AP payment is.**
An Accounts Payable (AP) payment is the settlement of a supplier bill — the act of transferring funds to the supplier to clear the liability created when the bill was entered. Recording the payment in the system updates the bill status and reduces the AP balance, completing the P2P cycle.

Payments against supplier bills are managed in the Accounts Payable module. Navigate to **Accounting › Record Payment** (`/admin/ap/payments/record`) to record a payment. See the Finance chapter for details on recording and reconciling AP payments.

---

**Example — Supplier bill for Simba Cement (clean 3-way match):**

Simba Cement sends Invoice **SIM/2026/1041**, Bill Date 2026-06-22, Due Date 2026-07-22, for 500 bags @ TZS 14,500 each = TZS 7,250,000 net.

Sarah opens **Accounting › Enter Bill** (`/admin/ap/supplier-bills/enter`), picks supplier **Simba Cement Ltd**, enters Invoice No **SIM/2026/1041**, Bill Date 2026-06-22, Due Date 2026-07-22. She links PO **PO-0088** and adds one bill line: **Saruji 50kg**, Qty 500, Unit Price TZS 14,500. She clicks **Enter Bill & Match**.

The system runs the 3-way match:
- Bill line: 500 bags @ 14,500
- PO line: 500 bags @ 14,500 ✓
- GRN line: 500 bags received ✓

All lines → **MATCHED**. Bill status → MATCHED. Bill **BILL-0051** is ready for payment.

**Example — Bill with price variance (held):**

A different shipment arrives and the supplier bills at TZS 14,900/bag (TZS 400 over the PO price). After 3-way match, the bill line shows **HELD_PRICE_VARIANCE** with variance TZS 200,000. The AP manager opens the bill, reviews the variance, decides it is within business tolerance, and clicks **Accept Variance**. Line moves to VARIANCE_ACCEPTED; bill → MATCHED.

---

## 7. Purchase Returns

Navigate to **Purchasing › Purchase Returns** (`/admin/purchase-returns`).

**What a purchase return is.**
A purchase return is the formal process of sending goods back to the supplier — typically because the goods arrived damaged, were incorrect, failed quality inspection, or are surplus to requirements. It is the reverse of a goods receipt: where a GR increases stock, a confirmed purchase return decreases stock and triggers the AP module to expect a credit note from the supplier.

**Why it exists.**
Without a formal return process, the business would need to adjust stock manually (which lacks a clear link to the supplier transaction) and would have no systematic way to claim money back from the supplier. A purchase return document creates an auditable record of what was returned, why, and at what value — forming the basis for the AP debit note that reduces the amount owed to the supplier. It also keeps inventory accurate: goods sent back should not remain in the stock count.

**When it is used.**
A purchase return is raised after a goods receipt has been confirmed (RECEIVED) and the goods in question have been identified for return — for example, after inspection reveals damage, or after a quality failure is reported. The storekeeper or purchasing manager raises the return against the specific GRN, and a purchasing manager or authorised user confirms it. The permissions required are `PURCHASE.RETURN.CREATE` and `PURCHASE.RETURN.CONFIRM`.

**How it flows.**
A purchase return starts as a DRAFT referencing the original GRN and specifying the quantities being returned (which cannot exceed what was received on that GRN). A mandatory reason must be entered. When confirmed (CONFIRMED), two things happen simultaneously: stock decreases by the returned quantity (a reversal of the original goods receipt movement at the original cost), and the AP module records a debit note against the supplier — a document that reduces the business's payable to the supplier by the value of the returned goods. A confirmed return cannot be edited.

### 7.1 Create a purchase return

1. Navigate to **Purchasing › Purchase Returns** (`/admin/purchase-returns`) and click **New Purchase Return**, or go directly to `/admin/purchase-returns/create`.
2. Pick the **Goods Receipt** by GRN number (the GR must have status RECEIVED).
3. Enter a mandatory **Reason**.
4. For each line being returned, enter the **Returned Quantity** (cannot exceed the quantity originally received on that GR line).
5. Click **Save**. The return is created in **DRAFT**.

### 7.2 Confirm a purchase return

Confirming the return physically ships the goods back and adjusts stock.

1. Open the DRAFT purchase return (navigate to `/admin/purchase-returns/uid/{uid}`).
2. Click **Confirm**.
3. Status → **CONFIRMED**. Stock is removed from the branch and a purchase return event is posted.

### 7.3 Purchase return status reference

| Status | Meaning |
|---|---|
| DRAFT | Being prepared; quantities editable |
| CONFIRMED | Return confirmed; stock decremented; supplier debit notified |

---

**Example — Purchase return for damaged cement:**

After receiving GRN-0061, the storekeeper discovers 20 bags of cement arrived wet and unusable. He opens **Purchasing › Purchase Returns** (`/admin/purchase-returns`), clicks **New Purchase Return**, picks GRN **GRN-0061**, enters Reason **"20 bags arrived wet — product damaged"**, and sets Returned Quantity **20** on the Saruji 50kg line. He saves — return **PRET-0018** is created in DRAFT.

The purchasing manager reviews and clicks **Confirm** — status → CONFIRMED. Stock decreases by 20 bags (480 bags remain). The AP module is notified to expect a supplier credit note for 20 × TZS 14,500 = TZS 290,000 from Simba Cement.

---

## 8. Purchase Settings

Navigate to **Purchasing › Purchase Settings** (`/admin/purchase-settings`).

**What purchase settings are.**
Purchase settings are the company-level configuration controls that govern how the procurement workflow operates — specifically, whether Purchase Orders above a certain value require a second-level approval before goods can be received.

**Why a PO approval threshold exists.**
For low-value purchases, requiring a manager to approve every PO would create unnecessary bottlenecks. For high-value purchases, however, committing the business without a second review is a financial control risk. The approval threshold is the balance: below the threshold, POs flow through automatically; above it, they pause for authorisation. This is a common internal control required by auditors and risk frameworks.

Purchase settings control the PO approval workflow.

### 8.1 PO approval threshold

| Setting | Description |
|---|---|
| PO Approval Enabled | When turned on, Purchase Orders above the threshold amount require approval before goods can be received |
| PO Approval Threshold | The minimum order total that triggers the approval requirement |
| Currency | The currency of the threshold amount |

To change these settings, navigate to **Purchasing › Purchase Settings** (`/admin/purchase-settings`), click **Edit**, update the values, and click **Save**.

When PO approval is enabled, a user with `PURCHASE.ORDER.APPROVE` must approve or reject POs that exceed the threshold.

---

**Example — Enabling PO approval:**

The CFO wants all purchase orders above TZS 5,000,000 to require a second-level approval. She opens **Purchasing › Purchase Settings** (`/admin/purchase-settings`), clicks **Edit**, sets **PO Approval Enabled** to ON, **PO Approval Threshold** to **5,000,000**, **Currency** to **TZS**, and saves. From now on any placed PO with a total above TZS 5,000,000 enters PENDING approval status and cannot proceed to goods receipt until an authorised approver acts on it.

---

## 9. End-to-end procure-to-pay example

The following steps illustrate a complete P2P cycle for a stock purchase with real sample values.

**Scenario: Warehouse restocking — 500 bags of Saruji 50kg from Simba Cement Ltd**

---

**Step 1 — Raise a Requisition**

Storekeeper John opens **Purchasing › Purchase Requisitions** (`/admin/purchase-requisitions`), clicks **New Requisition**, sets Required By **2026-06-18**, notes "Stock replenishment — cement for construction projects". He adds one line: **Saruji 50kg**, Unit **BAG**, Qty **500**, Estimated Cost **TZS 14,800**. He saves (REQ-0080 = DRAFT) and clicks **Submit** (status → SUBMITTED).

**Step 2 — Approve**

Purchasing manager Neema opens REQ-0080 and clicks **Approve** (status → APPROVED).

**Step 3 — Convert to RFQ**

Neema clicks **Convert**, selects **RFQ** — RFQ-0031 is created in DRAFT.

**Step 4 — Invite suppliers and send**

Neema opens **Purchasing › RFQs / Sourcing** (`/admin/rfqs`), opens RFQ-0031, adds invited suppliers **Tanzania Cement Distributors** and **Simba Cement Ltd**, sets Response Due Date **2026-06-17**, and clicks **Send** (status → SENT).

**Step 5 — Capture supplier quotes**

Two suppliers respond:
- Tanzania Cement Distributors: 500 bags @ TZS 14,800 = TZS 7,400,000.
- Simba Cement Ltd: 500 bags @ TZS 14,500 = TZS 7,250,000 (lead time 5 days).

Purchasing officer Zawadi captures both quotes on RFQ-0031. RFQ status → QUOTES_RECEIVED.

**Step 6 — Award the RFQ**

Zawadi clicks **Award** on the Simba Cement quote (lower price). RFQ status → AWARDED. Purchase Order **PO-0088** (DRAFT, 500 bags @ TZS 14,500) is created automatically.

**Step 7 — Place the PO**

Zawadi opens **Purchasing › Purchase Orders** (`/admin/purchase-orders`), finds PO-0088, reviews the line, and clicks **Place** (status → ORDERED, total TZS 7,250,000).

**Step 8 — Receive goods**

On 2026-06-22, 500 bags arrive. Storekeeper John opens **Purchasing › Goods Receipts** (`/admin/goods-receipts`), clicks **New Goods Receipt**, picks PO-0088, enters Receipt Date 2026-06-22, keeps 500 bags, and submits. GRN-0061 created (RECEIVED); PO-0088 status → RECEIVED; 500 bags added to stock.

**Step 9 — Allocate landed costs**

Port clearing TZS 850,000 + freight TZS 600,000 are entered as a landed cost against GRN-0061 (Basis: By Quantity). Accountant Sarah opens **Purchasing › Landed Costs** (`/admin/landed-costs`), creates the landed cost, and clicks **Confirm** — TZS 2,900/bag added to the moving-average cost.

**Step 10 — Enter the supplier bill and run 3-way match**

Simba Cement's invoice arrives: SIM/2026/1041, 500 bags @ TZS 14,500. Sarah opens **Accounting › Enter Bill** (`/admin/ap/supplier-bills/enter`), links PO-0088, enters the bill, and clicks **Enter Bill & Match**. All lines → MATCHED. BILL-0051 is ready for payment.

**Step 11 — Record AP payment**

Finance officer David opens **Accounting › Record Payment** (`/admin/ap/payments/record`), picks BILL-0051 (TZS 7,250,000 due 2026-07-22), records a bank transfer payment on 2026-07-20. The bill status moves to PAID and the AP balance for Simba Cement is cleared.

**Step 12 — Purchase return (if needed)**

If 20 bags arrived damaged, John opens **Purchasing › Purchase Returns** (`/admin/purchase-returns`), creates a return against GRN-0061 for 20 bags, and the manager confirms it — stock decreases by 20 bags and the AP module notes a TZS 290,000 credit note expected from Simba Cement.

---

# Inventory and Manufacturing

This chapter covers all inventory screens and the manufacturing (Bill of Materials and Work Orders) screens. All screens are available from the **Inventory** and **Manufacturing** groups in the left-hand navigation.

---

## 1. Permissions quick reference

Before starting, confirm that the required permission codes have been granted to your role.

| Task | Permission code required |
|---|---|
| View stock on-hand | `STOCK.VIEW` |
| Record adjustments / set reorder levels | `STOCK.ADJUST` |
| Record opening balances | `STOCK.OPENING` |
| View stock locations | `STOCK.LOCATION.VIEW` |
| Manage stock locations | `STOCK.LOCATION.MANAGE` |
| View stock transfers | `STOCK.TRANSFER.VIEW` |
| Create / dispatch / cancel transfers | `STOCK.TRANSFER.CREATE` |
| Receive a transfer | `STOCK.TRANSFER.RECEIVE` |
| View stock counts | `STOCK.COUNT.VIEW` |
| Create / enter / cancel stock counts | `STOCK.COUNT.CREATE` |
| Post a stock count | `STOCK.COUNT.POST` |
| View expiring batches | `INVENTORY.EXPIRY.VIEW` |
| View inventory valuation report | `INVENTORY.VALUATION.VIEW` |
| Set opening valuation | `INVENTORY.OPENING.SET` |
| View / manage Bills of Materials | `BOM.VIEW` / `BOM.MANAGE` |
| View Work Orders / cost report / WIP | `MANUFACTURING.VIEW` |
| Create / edit / cancel Work Orders | `WORKORDER.MANAGE` |
| Release a Work Order | `WORKORDER.RELEASE` |
| Complete / close a Work Order | `WORKORDER.CLOSE` |

Navigation items are hidden when the corresponding permission is absent. Attempting to access a route directly without the permission shows a **Forbidden** message.

---

## 2. Stock on-hand

**What "stock on-hand" means.**
Stock on-hand is the quantity of a product that is physically present and available at a branch right now. It is the central fact the business needs to answer questions like "how many bags of cement do we have?", "can we fulfil this order?", and "are we running low on cooking oil?" The system maintains this number in real time: every goods receipt adds to it, every sale or delivery deducts from it, and every adjustment, transfer, or stock count correction changes it. The on-hand figure is in the product's base unit (e.g. kilogrammes, pieces, bags) and is accurate to three decimal places.

**Why it is maintained as a running balance, not derived from history.**
The system stores both a maintained on-hand balance and an append-only movement ledger. The maintained balance gives an instant O(1) answer to "what do we have right now" — crucial for fast sales processing and re-order decisions. The ledger provides the full history and lets the balance be independently verified (on-hand should always equal the sum of all movements). Both are always in sync: every movement updates both the ledger and the balance in the same database transaction, so they can never diverge.

### 2.1 Viewing the on-hand list

Navigate to **Inventory > Stock On-Hand** (`/admin/stock`).

The table shows every stockable product that has had at least one movement at the active branch. Each row contains the product code, product name, unit of measure, quantity on-hand (to three decimal places), reorder level, and two derived flags:

- **Negative** — the quantity has gone below zero (an overselling indicator; the system does not hard-block it).
- **Low stock** — the quantity is at or below the reorder level. This flag is blank when no reorder level has been set.

**Filtering and pagination.** Use the search box to filter by product name or code (the list refreshes after a short pause). Use the paginator controls (First, Previous, page numbers, Next, Last) to move between pages. The paginator hides itself when there is only one page.

**Switching views.** The list offers three view modes via a toggle at the top:

- **By product** (default) — one row per product, summed across all locations.
- **By location** — one row per product-location combination. A branch must be selected.
- **By product (single product drill-down)** — pick a product from the search picker to see its quantity broken down by every location holding it.

### 2.2 Recording a manual adjustment

**What a stock adjustment is.**
A stock adjustment is a direct correction to the on-hand quantity of a specific product when the physical stock and the system quantity do not agree for a reason other than a formal stock count. Adjustments are used for damage, spoilage, theft, unexplained shrinkage, or errors discovered after the fact. Every adjustment is permanent, carries a mandatory reason, and is reflected in the movement ledger immediately.

**Why adjustments exist separately from stock counts.**
An adjustment is a single-product, immediate correction — useful for fixing a known discrepancy right away without pausing all other operations. A stock count (section 5) is a systematic, multi-product reconciliation exercise at a location that freezes a snapshot and allows bulk entry across multiple sessions before committing. Use adjustments for one-off corrections; use stock counts for periodic reconciliation.

Use an adjustment to correct a stock quantity that is wrong for any reason other than a physical count (which has its own workflow — see section 5).

1. On the on-hand list, find the product row and click **Adjust**.
2. The product is pre-selected. Choose the **Reason** from the dropdown:

| Reason code | When to use |
|---|---|
| `COUNT_CORRECTION` | Correcting after an informal count |
| `DAMAGE` | Goods physically damaged |
| `SHRINKAGE` | Unexplained loss |
| `EXPIRY` | Goods past their expiry date |
| `RECEIPT_CORRECTION` | Correcting a goods-receipt error |
| `OTHER` | Any other reason — add a note |

3. Enter the **Quantity** — positive to increase, negative to decrease.
4. Optionally add a free-text note.
5. Click **Submit**.

The system creates a new stock movement (`ADJUSTMENT`) and reloads the on-hand list. Adjustments are permanent records; they cannot be deleted or edited after posting.

**Validation.** Quantity must be non-zero. Reason is required. A role with only `STOCK.VIEW` cannot see the Adjust button; a direct API call returns 403.

### 2.3 Recording an opening balance

**What an opening balance is.**
An opening balance is the initial stock quantity entered for a product at a branch that has no prior movement history. It is the "starting point" for that product at that location — the quantity that existed before the system began tracking it. This is a one-time operation; once a product has any movement at a branch, its on-hand can only be changed by the normal transaction flows (receipts, sales, adjustments, etc.).

**When to use it.**
Opening balances are entered at go-live (when migrating from a previous system or manual records), when a new branch is opened, or when a new product is added and stock already exists that needs to be brought onto the books.

An opening balance sets the initial quantity for a product that has never had any movement at this branch. Use this task at go-live or when adding a new branch or product.

1. On the on-hand list, click **Opening Balance**.
2. Pick the product from the picker (search by name or code).
3. Enter the quantity. Must be greater than zero.
4. Optionally add a note such as `go-live`.
5. Click **Submit**.

**Important.** The system rejects an opening balance if the product already has any prior movement at the active branch. A second opening balance on the same product at the same branch is not permitted. To adjust existing stock, use the Adjust flow (section 2.2).

### 2.4 Setting a reorder level

**What a reorder level is.**
A reorder level (also called a reorder point or minimum stock level) is the quantity at which a product should be reordered. When on-hand falls to or below the reorder level, the system flags the product row with the **Low Stock** indicator. This is a monitoring tool — the flag is a signal to the purchasing team to raise a requisition; it does not automatically place an order.

A reorder level triggers the Low Stock flag when the on-hand quantity reaches or falls below it.

1. On the on-hand list, click the inline edit icon in the **Reorder Level** column.
2. Enter a positive number and save. To remove the reorder level, clear the field and save.

The Low Stock flag recalculates immediately after saving.

### 2.5 Viewing movement history

**What the movement ledger is.**
The movement ledger is the append-only record of every quantity change for a product at a branch — every goods receipt, every sale issue, every adjustment, every transfer in or out, every opening balance, every production issue and receipt. It is the audit trail for on-hand. Because movements are append-only (never edited or deleted), the ledger is tamper-evident: the on-hand balance can always be recomputed by summing all movements.

Click **Movements** on a product row to open the movement ledger drawer. Movements are displayed in chronological order with:

- Movement type (Goods Receipt, Sale Issue, Adjustment, Opening Balance, Transfer In/Out, etc.)
- Direction (IN or OUT)
- Signed quantity
- Date and time
- Reason code or note where applicable

The drawer has its own paginator. Movements are append-only records; there is no edit or delete.

---

## 3. Stock locations

Navigate to **Inventory > Stock Locations** (`/admin/stock/locations`).

**What a stock location is.**
A stock location is a named physical area within a branch where stock is stored and counted. Locations let a business track stock at a finer level than the branch — for example, distinguishing between the main warehouse, the shop floor, a quarantine area for goods awaiting inspection, and a van for a mobile sales team. Every stock movement and stock count is associated with a specific location, so the system can answer not just "how many bags of cement does the Dar es Salaam branch have?" but "how many are in the Warehouse versus the Store?"

**Why locations exist.**
Without locations, the business knows only how much stock is at a branch in aggregate. With locations, it can see where exactly the stock is, which is essential for efficient warehousing, picking, physical counting, and segregating goods that should not be issued until inspected. Locations are also the boundary for stock counts — a count covers one location at a time.

### Location types

| Type | Typical use |
|---|---|
| `WAREHOUSE` | Main storage area |
| `STORE` | Shop floor / retail |
| `VAN` | Mobile / vehicle storage |
| `QUARANTINE` | Held goods pending inspection |
| `OTHER` | Any other purpose |

### 3.1 Creating a location

1. Click **Create**.
2. Enter a short **Code** (up to 30 characters, unique within the branch) and a **Name** (up to 120 characters).
3. Choose the **Location Type**.
4. Pick the **Branch** from the picker.
5. Tick **Make default** if this should be the primary location for the branch. There can be only one default location per branch — making a new location the default automatically clears the prior one.
6. Click **Submit**.

New locations are created in **Active** status.

### 3.2 Editing a location

Click the edit icon on a row. You can change the name and location type. The code is not editable after creation.

### 3.3 Marking as default

Click **Set default** on any active, non-default location. The previous default is cleared automatically.

### 3.4 Deactivating and reactivating

Click **Deactivate** to set the location to **Inactive**. It no longer appears in pickers used by transfers and counts. Click **Reactivate** to restore it to Active.

Locations are never hard-deleted. An Inactive location and its history remain in the list (visible with the status filter).

---

## 4. Stock transfers

Navigate to **Inventory > Stock Transfers** (`/admin/stock-transfers`).

**What a stock transfer is.**
A stock transfer is a document that moves stock from one physical location to another — either between two locations within the same branch (for example, from the Warehouse to the Store) or between two different branches (for example, from the Arusha branch to the Dar es Salaam branch). A transfer records a physical movement of goods without buying or selling them; it re-attributes stock from one place to another.

**Why transfers do not affect the income statement.**
A transfer does not create a sale (no revenue, no COGS) and is not a purchase (no supplier, no invoice). It is a value-preserving internal movement: the cost of the goods is the same before and after the transfer — it has simply moved to a different location. Because the moving-average cost is maintained per product at the company level (not per location), the transfer does not change the financial value of inventory — it only re-attributes it between locations. No GL journal entry is posted for a standard same-cost-grain transfer.

**When transfers are used.**
Transfers are used when stock needs to be redistributed — to replenish a retail store from a warehouse, to move goods to a van for a field sales team, or to consolidate slow-moving inventory at one location. The `STOCK.TRANSFER.CREATE` permission is required to initiate and dispatch; `STOCK.TRANSFER.RECEIVE` is required to receive.

**How transfers flow — two modes.**
An **Instant** transfer is for moves between two locations within the same branch; it completes in a single step with no in-transit period. An **In-transit** transfer is for cross-branch moves: when dispatched, the source location's stock decreases immediately (goods are "in the truck") and the destination location's stock only increases when the destination operator confirms receipt. Between dispatch and receipt, the goods are "in transit" — not counted at either location. This two-step model prevents double-counting and gives each branch an accurate view of what it physically holds.

A stock transfer moves stock from one location to another. Two modes are available:

- **Instant** — for transfers between two locations within the same branch. Stock moves in a single step.
- **In-transit** — for transfers that cross branches. The transfer follows a Dispatch → Receive workflow; stock is in transit between the two events.

### Transfer status lifecycle

```
DRAFT
  |-- (Instant mode)  --> COMPLETED
  |-- (In-transit mode) -> DISPATCHED --> RECEIVED
  \-- (any mode, before dispatch) --> CANCELLED
```

### 4.1 Creating a transfer

1. Navigate to **Stock Transfers > Create** (`/admin/stock-transfers/create`).
2. Pick the **Source Branch** and **Source Location**.
3. Pick the **Destination Branch** and **Destination Location**. Source and destination must be different locations.
4. Set the **Transfer Date**.
5. Choose the **Transfer Mode**: Instant (same-branch) or In-transit (cross-branch).
6. Click **Add Line** for each product to transfer. Pick the product by name and enter the quantity.
7. Click **Submit**.

The transfer is created with status **Draft** and a system-generated transfer number. The screen navigates to the transfer detail.

**Validation.** Source and destination locations must differ. At least one line is required. Quantity per line must be positive. Transfer date is required.

### 4.2 Dispatching an in-transit transfer

On a Draft, In-transit transfer, click **Dispatch**. The status changes to **Dispatched** and the source location's stock decreases immediately.

The Dispatch button is only available when the transfer is in Draft status and the mode is In-transit. Dispatching requires the `STOCK.TRANSFER.CREATE` permission.

### 4.3 Receiving an in-transit transfer

On a Dispatched transfer, the destination operator clicks **Receive**. The status changes to **Received** and the destination location's stock increases.

Receiving requires the `STOCK.TRANSFER.RECEIVE` permission. This allows organisations to separate the dispatcher and receiver roles.

### 4.4 Completing an instant transfer

On a Draft, Instant transfer, click **Complete instant**. The transfer completes in a single step; both locations update simultaneously.

### 4.5 Cancelling a transfer

On a Draft transfer, click **Cancel**. The status changes to **Cancelled** and no stock movement is recorded. Only Draft transfers can be cancelled.

### 4.6 Viewing the transfer list and detail

The list shows transfer number, source and destination, transfer date, mode, and status. Click any row to open the detail view. The transfer is referenced by its human transfer number throughout the UI; the internal identifier appears only in the browser address bar.

---

**Example — In-transit dispatch from Arusha Warehouse to DSM Store:**

Storekeeper Grace Mwenda at Arusha branch needs to send 200 bags of Pembe Flour and 50 cartons of Cooking Oil to the Dar es Salaam main store.

1. Navigate to **Inventory › Stock Transfers › Create** (`/admin/stock-transfers/create`).
2. Source Branch: `Arusha Branch`; Source Location: `Arusha Warehouse`.
3. Destination Branch: `DSM Branch`; Destination Location: `DSM Main Store`.
4. Transfer Date: `2026-06-10`; Transfer Mode: **In-transit**.
5. Add lines:
   - Product: `Pembe Flour 2kg (FLR-002)`, Qty: `200`.
   - Product: `Cooking Oil 3L (OIL-003)`, Qty: `50`.
6. Click **Submit**. Transfer `TRF-0042` is created with status **Draft**.
7. Grace reviews the lines and clicks **Dispatch**. Status becomes **Dispatched**. The Arusha Warehouse stock for both items decreases immediately (200 bags and 50 cartons deducted).
8. The following day, DSM storekeeper Omari Njau opens **Inventory › Stock Transfers** (`/admin/stock-transfers`), finds `TRF-0042` with status Dispatched, and clicks the row to open the detail.
9. Omari counts the physical delivery — both lines match — and clicks **Receive**. Status becomes **Received**. DSM Main Store stock increases by 200 bags and 50 cartons.
10. Both storekeepers can now see `TRF-0042` with status **Received** in the transfer list. No cancellation is possible at this stage.

---

## 5. Stock counts

Navigate to **Inventory > Stock Counts** (`/admin/stock-counts`).

**What a stock count is.**
A stock count (also called a physical inventory or stocktake) is a scheduled exercise where a team physically counts the items held at a location and compares the counted quantities to the quantities the system believes are there. Any discrepancy (a "variance") is recorded and — after review — posted as an adjustment to bring the system records into alignment with physical reality.

**Why stock counts are necessary.**
Even in a well-run warehouse, small discrepancies accumulate over time: items get damaged and not immediately reported, products are picked without being scanned, counting errors occur during receipts, or theft occurs. Without periodic counts, these discrepancies compound silently and the business makes decisions (ordering, sales commitments, valuations) based on wrong numbers. A stock count is the mechanism to catch and correct these discrepancies systematically. Unlike ad-hoc adjustments, a count involves a controlled snapshot of the entire location's stock at a point in time, multi-session entry, and formal posting — producing an auditable record of what was found versus what was expected.

**How variances affect the books.**
When a count is posted, each line with a variance is converted into a stock adjustment movement at the current moving-average cost. The value difference is posted to the `STOCK_ADJUSTMENT` expense account (5160) against the `INVENTORY` account (1300). A negative variance (less found than expected) is an expense; a positive variance is a credit to the adjustment account. This ensures the inventory balance on the balance sheet and the GL always stay in sync.

**When it is used.**
Stock counts are run periodically — monthly, quarterly, or annually depending on the business's risk appetite and the volatility of the products. **Full** counts cover all products at a location. **Cycle** counts cover a rotating subset of products (for example, high-value or fast-moving lines), allowing more frequent reconciliation without counting everything at once. The `STOCK.COUNT.CREATE` permission is needed to create and enter counts; `STOCK.COUNT.POST` (typically held by an accountant or supervisor) is needed to post the variances.

A stock count records what is physically present at a location and reconciles it against the system quantity. Any variance is posted as a stock adjustment and a GL entry.

### Count status lifecycle

```
COUNTING --> POSTED
     \-----> CANCELLED
```

When a count is created the system immediately freezes the on-hand quantities (the snapshot) and moves the document to **Counting** status.

### 5.1 Creating a stock count

1. Navigate to **Stock Counts > Create** (`/admin/stock-counts/create`).
2. Select the **Company** and **Branch**.
3. Pick the **Location** from the picker.
4. Set the **Count Date** (defaults to today).
5. Choose the **Count Type**:
   - **Full** — all products held at the location are included.
   - **Cycle** — a subset of products. Use the product pickers to choose which products to count.
6. Click **Submit**.

The count is created with status **Counting** and a system-generated count number. The system records the current on-hand quantity for each product as the **System Qty** snapshot. This snapshot is frozen and cannot change.

### 5.2 Entering counted quantities

Open the count detail. For each product line:

1. Enter the physically counted quantity in the **Counted Qty** column.
2. Optionally choose a reason code for lines that have a variance.
3. Click **Enter / Save**.

The **Variance** column shows `Counted Qty − System Qty`. A positive variance means more stock was found than expected; a negative variance means less was found.

The document stays in Counting status after saving. You can enter counts in multiple sessions.

### 5.3 Posting a count

Posting creates stock adjustment movements for every line with a variance and generates a single GL variance journal.

1. Open a count in Counting status.
2. Click **Post**.
3. Enter the **Posting Date**.
4. Confirm.

Posting requires the `STOCK.COUNT.POST` permission (typically held by an accountant or supervisor). After posting, the document is read-only.

### 5.4 Cancelling a count

Open a Counting count and click **Cancel**. No stock movements or GL entries are created. A Posted count cannot be cancelled. If corrections are needed after posting, create a new count.

---

**Example — Cycle count of sugar and rice with a variance posted:**

Accountant supervisor Boniface Kessy schedules a cycle count of two fast-moving products at the DSM Main Store.

1. Navigate to **Inventory › Stock Counts › Create** (`/admin/stock-counts/create`).
2. Company: `Kijenge Trading Ltd`; Branch: `DSM Branch`; Location: `DSM Main Store`; Count Date: `2026-06-12`; Count Type: **Cycle**.
3. Add products to count: `Sembe Sugar 1kg (SGR-001)` and `Jasmine Rice 5kg (RCE-005)`. Click **Submit**.
4. Count `CNT-0009` is created with status **Counting**. The system records the snapshot quantities: Sugar = 850 bags, Rice = 240 bags.
5. Storekeeper Omari Njau physically counts the shelves. He opens `CNT-0009` and enters:
   - Sugar counted: `843` (variance: −7 bags).
   - Rice counted: `245` (variance: +5 bags).
   - For Sugar he selects Reason: `SHRINKAGE`. For Rice no reason is needed (positive variance — unrecorded receipt correction).
   Click **Enter / Save**.
6. Boniface reviews the variances and clicks **Post**. Posting Date: `2026-06-12`. Confirms.
7. The system posts two stock adjustment movements:
   - Sugar: −7 bags (ADJUSTMENT, reason SHRINKAGE).
   - Rice: +5 bags (ADJUSTMENT).
   A single GL variance journal posts: DR Inventory Variance / CR Inventory for the sugar loss (valued at moving-average cost); the rice surplus reverses this direction.
8. The count document is now read-only with status **Posted**. On-hand quantities at DSM Main Store are now: Sugar = 843, Rice = 245.

---

## 6. Batches and lot tracking

Navigate to **Inventory > Stock Batches** (`/admin/stock/batches`).

**What a batch (lot) is.**
A batch — also called a lot — is a group of units of the same product that were manufactured or received together and share the same identity attributes, most importantly an expiry date and a manufacture date. For example, a batch of medicines all manufactured on the same day with the same expiry date is one lot. Batch tracking allows the business to know exactly which physical batch a unit came from — critical for food, pharmaceutical, and chemical products where recall or expiry management is required.

**Why batch tracking matters.**
Without lot tracking, if a product recall is announced (for example, a contaminated batch of cooking oil), the business cannot identify which specific units on its shelves belong to the recalled batch. With batch tracking, the system can identify every unit of that batch, where it is held, and how much remains — enabling targeted removal without wasting uninvolved stock. Batch tracking is also necessary for FEFO (First Expired, First Out) stock rotation: the system ensures that stock with the earliest expiry date is issued first, minimising spoilage and waste.

**How batches are created.**
Batches are created automatically by the purchasing flow when lot-tracked products are received via a Goods Receipt — the system assigns a lot number and records the manufacture and expiry dates at that point. You cannot create batches manually on this screen.

Batches (lots) are created automatically when lot-tracked products are received. This screen provides a read-only view; you cannot create or edit batches directly.

### 6.1 Viewing batches by location and product

1. Pick a **Location** from the picker.
2. Pick a **Product** from the picker.
3. The table lists all batches at that location for that product, showing lot number, manufacture date, expiry date, quantity on-hand, and an expiry flag.

### 6.2 Expiring batches report

Click the **Expiring Soon** tab. Set a horizon date (default: 30 days from today). The report lists all batches whose expiry date falls on or before the horizon and whose quantity is greater than zero.

- Batches already past their expiry date are flagged in red.
- Batches expiring before the horizon are flagged as a warning.

The expiring batches tab requires the `INVENTORY.EXPIRY.VIEW` permission.

> **Known limitation.** The batch detail screen (`STOCK.BATCH.VIEW`) and serial detail screen (`STOCK.SERIAL.VIEW`) are accessible to superuser (`rootadmin`) only on seeded data. ORG_ADMIN and other roles will see a Forbidden message on those views until a permission-code fix is deployed. The Expiring Soon tab is unaffected and works for ORG_ADMIN.

---

## 7. Serial number tracking

Navigate to **Inventory > Stock Serials** (`/admin/stock/serials`).

**What serial number tracking is.**
Serial number tracking assigns a unique identifier to each individual unit of a product — for example, every laptop, refrigerator, or generator has its own serial number. Unlike batches (which group many units of the same type), a serial identifies one specific physical item. The system records where each serial is, whether it is in stock, has been issued to a customer, or has been returned, giving full unit-level traceability.

**Why it is used.**
Serial tracking is valuable for high-value items, warranty management, and theft prevention. When a laptop is sold, the system records which serial number left the warehouse and to which customer. If a customer returns a laptop claiming it is faulty, the system confirms whether that serial was genuinely sold to them. Serial numbers also help with insurance claims (proving what was held) and regulatory compliance.

**How serials are managed.**
Serial numbers are created and updated automatically by the purchasing (goods receipt), sales (delivery), and transfer flows. This screen is a read-only view and lookup tool; you cannot create or modify serials directly here.

Serial numbers are assigned to individual units of serialised products. This screen is read-only; serials are created and updated by the purchasing, sales, and transfer flows.

### 7.1 Viewing serials by location

1. Select **By Location** mode.
2. Pick a **Location** and a **Product**.
3. Optionally filter by **Status**: All, In Stock, Issued, Returned.
4. The table shows serial number, current status, and the related documents.

### 7.2 Viewing serial history by product

Switch to **By Product** mode. Pick a product to see all of its serials across all statuses and locations.

### 7.3 Looking up a serial number

Switch to **Lookup** mode. Pick a product, then type the serial number and click **Look up**. The system returns the current status and location, or shows a Not Found message if the serial does not exist for that product.

---

## 8. Inventory valuation

**What inventory valuation is.**
Inventory valuation is the process of assigning a monetary value to the goods held in stock. The business needs to know not just how many units it has but what those units are worth — for the balance sheet (Inventory is an asset), for the cost of goods sold when items are sold (COGS reduces profit), and for management decisions (is this product profitable to sell?). The system uses the **moving-average cost method**: the average unit cost is recalculated each time stock is received, blending the new purchase cost with the existing average. This means all units of a product at a branch carry the same average cost, regardless of when they were purchased.

**How the moving average is maintained.**
When a goods receipt is posted, the system computes the new average as: `(existing stock value + new receipt value) / (existing quantity + received quantity)`. This weighted average is then applied to all units held. When goods are sold, the COGS is the quantity sold multiplied by the current average cost at the moment of the sale. When stock is adjusted, the adjustment value is computed at the current average. This means the Inventory account on the balance sheet always equals the sum of (on-hand quantity × average cost) across all products — a relationship the valuation report verifies.

### 8.1 Valuation report

Navigate to **Inventory > Valuation** (`/admin/stock/valuation`). Requires the `INVENTORY.VALUATION.VIEW` permission.

The report shows every stockable product with its average cost, quantity, and calculated inventory value. A reconciliation bar at the top compares the sum of on-hand values (the stock ledger) against the GL inventory account balance:

- **Reconciled to GL** (green) — the stock ledger and GL agree.
- **Does not reconcile** (red) — there is a discrepancy. The difference amount is shown. Finance review is required.

### 8.2 Setting an opening valuation

**What opening valuation is.**
Opening valuation is the one-time act of assigning an initial monetary cost to stock that already has a quantity on-hand but no established cost. This occurs at system go-live (when stock was loaded via opening balances before the cost data was entered) or when a new product is added and given an opening balance. Until an average cost is established, the system cannot post COGS for sales of that product — it will issue the stock but leave the cost leg blank, flagging the anomaly.

Navigate to **Inventory > Opening Valuation** (`/admin/stock/valuation/opening`). Requires the `INVENTORY.OPENING.SET` permission.

Use this screen to assign an initial cost to products that have a quantity on-hand but no established average cost.

1. The screen lists all on-hand rows that are currently unvalued.
2. Find the product row and enter the **Opening Cost per unit**.
3. Click **Submit**.

The system posts a GL entry (DR Inventory / CR Opening Balance Equity) and the product's average cost is established. Opening valuation is a one-time operation per on-hand row. Once a row has been valued it no longer appears on this screen.

---

## 9. Bills of Materials

Navigate to **Manufacturing > Bills of Materials** (`/admin/boms`).

**What a Bill of Materials is.**
A Bill of Materials (BOM) is the formal recipe or formula that defines exactly what components — and in what quantities — are needed to produce one run of a finished product. For example, a BOM for "Ugali Pack 1kg" might specify 1.05 kg of maize flour (the extra 5% is scrap allowance), or a BOM for a piece of furniture might specify 2 pieces of timber, 4 bolts, 1 m² of fabric, and 200 g of adhesive. The BOM is the production blueprint; without it, a work order cannot know what to consume.

**Why BOMs are versioned.**
Products change: a recipe might be reformulated, a component supplier might change, or the manufacturing process might be improved. Each change requires a new BOM version. The system keeps all historical BOM versions so that an old production run can be reproduced exactly as it was originally planned — using the BOM that was active at the time the work order was released — even if the current BOM is different.

**When a BOM is used.**
A BOM is created and maintained by the production or engineering team. It becomes active when activated (with an effective-from date), at which point it can be referenced by Work Orders. Only one BOM can be active at a time per finished product — activating a new version automatically archives the previous one. A BOM must be set up before any Work Order for that finished product can be released.

**How it connects to manufacturing.**
When a Work Order is released, the system looks up the active BOM for the finished product, explodes it to its leaf components (recursively resolving any sub-assemblies), and materialises the planned component lines on the work order. The BOM is then pinned to that work order — subsequent changes to the BOM do not affect work orders that are already in progress.

A Bill of Materials (BOM) defines what components and quantities are needed to produce a given quantity of a finished product. One BOM can be active at a time for each finished product; creating and activating a new BOM version automatically archives the previous one.

### BOM status lifecycle

```
DRAFT --> (activate) --> ACTIVE --> (archive) --> ARCHIVED
```

Only a DRAFT BOM can be activated. ARCHIVED is a permanent terminal state.

### 9.1 Creating a BOM

1. On the BOM list, click **New BOM**.
2. Pick the **Finished Product** from the picker (search by name or code). The product must be a GOODS type and must be active.
3. Enter the **Output Quantity** (how many units the BOM produces per run) and optionally the **Yield %** (default 100%).
4. Optionally add notes.
5. Click **Submit**.

The BOM is created in **Draft** status with the next version number for that finished product (v1 for the first BOM, v2 for the next, etc.).

**Validation.** Output quantity must be positive. Yield must be between 0.0001% and 100%.

### 9.2 Adding components

**What a BOM component is.**
A BOM component is one ingredient or raw material in the recipe. Each component line specifies the product to consume, the quantity required per one run of the BOM output, and an optional scrap percentage (an allowance for material that is consumed but does not make it into the finished good — for example, offcuts when cutting fabric). A component is classified as either **MAKE** (the component is itself manufactured — the system will look for its own BOM) or **BUY** (the component is purchased from a supplier and is a raw material).

Open a Draft BOM detail and click **Add Component**.

1. Pick the **Component Product** from the picker.
2. Enter **Qty Per** (quantity of the component per one run of the BOM output).
3. Optionally enter **Scrap %** (allowance for waste, 0–99.9999%).
4. Optionally enter a **Reference** (for engineering cross-reference).
5. Choose **Sourcing**:
   - **Auto (derive)** — the system determines whether the component is made internally (MAKE) or purchased (BUY) based on whether it has an Active BOM.
   - **MAKE** — the component is itself manufactured.
   - **BUY** — the component is purchased from a supplier.
6. Click **Submit**.

Components can be added, edited, or removed only while the BOM is in Draft status.

### 9.3 Editing a BOM header

On a Draft BOM detail, click **Edit**. You can change Output Quantity, Yield %, and Notes. On an Active BOM only Notes can be changed; structural fields are frozen.

### 9.4 Activating a BOM

A BOM must have at least one component before it can be activated.

1. Open a Draft BOM.
2. Click **Activate**.
3. Enter an **Effective From** date.
4. Confirm.

Activating a BOM automatically archives the current Active BOM (if any) for the same finished product. Only one BOM can be Active per product at a time.

**Validation.** Effective From date is required. The BOM must have at least one component. A circular BOM (where a component's BOM ultimately references this product back) is rejected.

### 9.5 Archiving a BOM

On a Draft or Active BOM, click **Archive**. The BOM moves to Archived status permanently. Header and component editing controls disappear.

---

## 10. Work Orders

Navigate to **Manufacturing > Work Orders** (`/admin/work-orders`).

**What a Work Order is.**
A Work Order (WO) is the production document that authorises the manufacture of a specified quantity of a finished product. It is to manufacturing what a Purchase Order is to procurement: a formal instruction to produce. The work order drives the full production accounting cycle — it specifies what to make, what materials to consume, and how much labour and overhead to apply, and it records the cost of everything consumed in producing the finished goods.

**Why Work Orders are used.**
Without a Work Order, there is no formal record of what was produced, what materials were consumed, or what the finished goods cost. The business could not track whether production is efficient (planned versus actual component usage), could not correctly value the finished goods entering inventory, and could not identify variances between the standard (budgeted) cost and the actual cost. Work Orders also create the link between the BOM (the recipe) and the actual production run — allowing the system to issue the right components out of stock and receive the finished goods back into stock at their true cost.

**How the Work-in-Progress (WIP) account works.**
During production, costs accumulate in a temporary balance sheet account called **Work-in-Progress** (WIP). When components are issued from the storeroom to the production floor, their value moves: DR WIP / CR Inventory (components leave the warehouse, enter the production area). When labour and overhead costs are applied to the order, they also accumulate in WIP: DR WIP / CR the relevant cost account. When production is complete and the finished goods are received back into the warehouse, the accumulated WIP is relieved: DR Inventory (finished goods) / CR WIP. Any residual WIP at close (due to rounding or variance) is cleared to a Manufacturing Variance account. The net effect: raw materials enter, finished goods come out, and WIP returns to zero for a closed order.

**What COGS means in manufacturing context.**
When a manufactured finished good is later sold, the Cost of Goods Sold (COGS) posted by the sale is the moving-average cost of the finished good — which was set when the work order was completed (WIP divided by the good quantity produced). The COGS therefore reflects the actual cost of production, not just the purchase price of raw materials.

**When it is used.**
A Work Order is created by the production planner or manufacturing supervisor when a production run is scheduled. It is released (which locks the BOM and generates the component plan), components are issued from stock, labour and overhead are applied, the finished goods are completed and received into stock, and the order is closed. The lifecycle covers five states: PLANNED → RELEASED → IN_PROGRESS → COMPLETED → CLOSED.

A Work Order authorises the production of a specified quantity of a finished product and tracks the cost of materials, labour, and overhead consumed.

### Work Order status lifecycle

```
PLANNED --> (release) --> RELEASED --> (first issue) --> IN_PROGRESS
        --> (cancel)  --> CANCELLED
                                  \--> (complete) --> COMPLETED
                                                  \--> (close) --> CLOSED
                                  \--> (cancel)   --> CANCELLED
```

CANCELLED, COMPLETED (after close), and CLOSED are terminal. A COMPLETED Work Order must be closed before any other action.

### 10.1 Creating a Work Order

1. On the Work Orders list, click **New Work Order**.
2. Pick the **Finished Product** from the picker.
3. Pick the **Branch** from the picker.
4. Enter the **Planned Quantity**.
5. Optionally pin a specific **BOM version** via the picker (if blank, the system uses the product's current Active BOM at release time).
6. Optionally enter a **Planned Date** and **Notes**.
7. Click **Submit**.

The Work Order is created in **Planned** status with a generated Work Order number.

### 10.2 Editing a Work Order

A Work Order can only be edited while in Planned status. Open the Work Order detail and click **Edit**. You can change the Planned Quantity, Branch, Planned Date, and Notes.

### 10.3 Adding and removing operations

**What operations are.**
Operations are the discrete steps in the production process — for example, Cutting, Mixing, Assembly, Finishing. Each operation can carry an estimated and actual labour cost and overhead cost, giving the business a breakdown of where production costs are incurred within the work order. Operations are optional; a work order can be costed with a single bulk labour/overhead application if step-level detail is not needed.

Operations represent discrete production steps (e.g. Cutting, Assembly) with associated labour and overhead cost estimates. They can be added to a Work Order at any status before it is Closed or Cancelled.

- **Add operation**: Enter sequence number, description, work centre, and optional labour/overhead amounts. Click **Submit**.
- **Remove operation**: Click **Remove** on an operation row. An operation that has already had costs applied to it cannot be removed.

### 10.4 Releasing a Work Order

**What releasing means.**
Releasing a Work Order is the act of committing to produce. At this point the system resolves and locks the BOM (so the recipe is frozen for this production run), explodes it to all leaf-level raw material components, and generates the planned component lines on the work order — the list of what will need to be issued from stock. No stock movement or GL posting happens at release; it is a planning step. Once released, the work order is ready for component issue.

Releasing a Work Order locks the BOM and generates the component plan.

1. Open a Planned Work Order.
2. Click **Release**.
3. Optionally override the BOM via the picker.
4. Confirm.

Status changes to **Released**. The system emits a production event. No stock movements or GL entries are posted yet.

**Validation.** The finished product must have an Active BOM (or a BOM must be pinned). Releasing requires the `WORKORDER.RELEASE` permission.

### 10.5 Issuing components

**What component issue means.**
Issuing components is the physical act of taking raw materials from the stock location and bringing them to the production area. In the system, this deducts the components from inventory and charges them to the Work-in-Progress account. The GL posting is: **DR WIP / CR Inventory** for each component at its current moving-average cost. If any component has no established average cost (it has never been received or opened), the quantity deduction still posts but the WIP cost leg is skipped and the incomplete-cost flag is set on the work order — the production team should investigate and correct the missing cost.

Issuing deducts the component materials from stock and accumulates costs in the Work-in-Progress (WIP) account.

1. Open a Released or In-Progress Work Order.
2. Enter the **Posting Date**.
3. Click **Issue Components**.

The system issues all un-issued component lines simultaneously (full issue). Status moves to **In-Progress** on the first issue.

Stock movements of type `PRODUCTION_ISSUE` are posted for each component. GL entries: DR WIP / CR Inventory.

**Validation.** Posting date is required. If a component's average cost is not yet established, that component is cost-skipped (the quantity still moves but no GL leg is posted). An incomplete-cost indicator appears on the Work Order header when any component was cost-skipped.

### 10.6 Applying labour and overhead costs

**What labour and overhead costs are.**
Labour costs are the wages and salaries paid to the workers who produce the goods. Overhead costs are the indirect production costs that cannot be assigned to a single unit but are incurred as part of running the factory — energy, depreciation of machinery, supervision, etc. Both are debited to WIP when applied to a Work Order: **DR WIP / CR the relevant cost account**. Applying these costs ensures that the finished good's cost reflects all the inputs that went into making it, not just the raw materials.

1. Open a Released or In-Progress Work Order.
2. In the **Apply Cost** section, enter a **Labour Amount** and/or an **Overhead Amount** and a **Posting Date**.
3. Optionally link the cost to a specific operation via the Operation picker.
4. Click **Submit**.

GL entries: DR WIP / CR the relevant cost account. An operation can only have costs applied to it once; a second attempt is rejected.

### 10.7 Completing a Work Order

**What completion does.**
Completing a Work Order records that production has finished and the finished goods are ready to move from the production area back into the finished goods warehouse. The system computes the unit cost of the finished good as: total WIP debited divided by the good quantity produced. This computed unit cost is passed to the moving-average recompute for the finished product — so the finished good acquires its average cost through the same engine that handles purchase receipts. The GL posting is: **DR Inventory (finished goods) / CR WIP** for the value relieved. Scrap (units produced but rejected) is recorded informationally; only good quantity enters inventory.

Completing records the finished goods receipt and calculates the unit cost.

1. Open an In-Progress Work Order.
2. In the **Complete** section, enter **Good Quantity** produced, **Scrap Quantity** (if any), and a **Posting Date**.
3. If the combined good and scrap quantities exceed the planned quantity, tick **Allow Over-run**.
4. Click **Submit**.

Status changes to **Completed**. A `PRODUCTION_RECEIPT` stock movement is posted for the finished goods. The computed unit cost is the total WIP debit divided by the good quantity. GL entries: DR Finished Goods / CR WIP.

**Validation.** Good quantity must be positive. If good + scrap exceeds planned quantity and Allow Over-run is not ticked, the submission is rejected.

### 10.8 Closing a Work Order

**What closing does.**
Closing a Work Order is the final step that clears any remaining WIP balance. After completion, there may be a small residual WIP balance due to rounding or small variances between the planned and actual costs. Closing posts this residual to the **Manufacturing Variance** account — a P&L account that captures the difference between what production was expected to cost (based on the BOM and standard costs) and what it actually cost. After closing, the WIP balance for this order is zero and the order is read-only.

Closing clears any residual WIP balance (rounding or variance) and marks the order as final.

1. Open a Completed Work Order.
2. In the **Close** section, enter a **Posting Date**.
3. Click **Submit**.

Status changes to **Closed**. Any residual WIP is posted to the Manufacturing Variance account. GL entries: DR or CR Manufacturing Variance / CR or DR WIP (depending on sign).

### 10.9 Cancelling a Work Order

A Work Order can be cancelled from Planned, Released, or In-Progress status.

1. Open the Work Order.
2. Click **Cancel** and enter an optional reason.
3. Confirm.

If components have already been issued, the system reverses all issue movements and GL entries automatically (`PRODUCTION_ISSUE_REVERSAL`). Applied labour and overhead costs are also reversed. No reversal is needed for Planned Work Orders (nothing has moved).

**Why reversals are at the original issue cost.** The system reverses each component issue at the exact cost it was issued at (read from the original movement record), not at the current average. This ensures the cancellation is symmetric — the books return to their exact pre-issue state with no phantom gain or loss introduced.

A Completed or Closed Work Order cannot be cancelled.

### 10.10 Work Order cost report

From the Work Order detail, click **Cost Report** or navigate directly to `/admin/work-orders/uid/:uid/cost-report`.

The report shows:

| Section | Contents |
|---|---|
| Components | Planned vs actual component lines, quantity, unit cost, total value |
| Labour | Applied labour costs |
| Overhead | Applied overhead costs |
| WIP summary | Total WIP debits, total WIP credits, net WIP balance |
| Unit cost | Computed unit cost (WIP debit / good qty) |
| Variance | Residual variance (cleared at close) |

An incomplete-cost indicator appears when any component was cost-skipped.

---

## 11. WIP reconciliation

Navigate to **Manufacturing > WIP Reconciliation** (`/admin/manufacturing/wip-reconciliation`). Requires the `MANUFACTURING.VIEW` permission.

**What WIP reconciliation is.**
The WIP reconciliation report is the manufacturing equivalent of the inventory valuation report's GL reconciliation bar. It compares the total WIP balance accumulated across all open Work Orders (RELEASED, IN_PROGRESS, and COMPLETED orders that have not yet been closed) against the WIP account balance (account 1320) in the General Ledger. They must agree at all times — if they do not, it means a posting was made to the WIP account that was not recorded on a Work Order, or vice versa, which indicates a data integrity problem requiring investigation.

1. Select the **Company**.
2. The report compares the sum of open Work Order WIP balances (the manufacturing ledger) against the WIP Inventory GL account balance (account 1320).

A **Balanced** indicator means the two totals agree. A **Does Not Balance** alert means there is a discrepancy and a finance review is required.

---

## 12. Frequently asked questions

**Can I adjust stock below zero?**
Yes. The system records negative on-hand and flags the row with the Negative indicator, but it does not block the transaction. The overselling indicator is a monitoring tool; you should investigate and correct the root cause.

**What is the difference between an adjustment and a stock count?**
An adjustment corrects a single product's quantity immediately. A stock count covers all products at a location, freezes the system quantities as a snapshot, lets you enter physical counts across multiple sessions, and only posts variances when you explicitly post the count.

**Why do I see Forbidden on the Batches and Serials screens?**
There is a known permission-code mismatch in the current seed data. Only the superuser (`rootadmin`) can access the by-location and by-detail views for batches and serials until a fix is deployed. The Expiring Batches tab remains functional for ORG_ADMIN.

**Can I have more than one active BOM for a product?**
No. Only one BOM can be active at a time per product. Activating a new version automatically archives the previous one. Historical archived versions remain visible.

**Can I cancel a Work Order after it is completed?**
No. Once a Work Order reaches Completed status it can only be Closed. Use the Close action to clear any remaining WIP balance.

**Why does the average cost change when I receive goods?**
The system uses a moving-average cost method. Each time goods are received, the new receipt cost is blended with the existing inventory value to produce a new weighted average: `(old value + receipt value) / (old quantity + received quantity)`. This means all units of a product always carry the same average cost, which changes with each new receipt.

**What happens to COGS if a product has no average cost?**
If a product has never been received and has no established average cost, the system will still issue it out of stock (the quantity deducts) but it will skip the COGS GL leg and flag the anomaly. You should use the Opening Valuation screen to establish the cost before selling costed goods.

---

# Fixed Assets

**What is the Fixed Assets module?**
A fixed asset is a tangible item a business buys and uses over multiple years — machinery, vehicles, computers, office furniture. Unlike stock, which is sold and replaced constantly, a fixed asset sits on the company's balance sheet as long as it is in use. Because the asset is consumed gradually over its useful life, its cost is spread across accounting periods as **depreciation**: a periodic charge that reduces the asset's book value and recognises the consumption on the profit and loss account. Without a formal asset register, capital purchases get mis-coded as expenses (overstating costs and understating the balance sheet), depreciation goes unrecorded, and the financial statements do not reflect the real value of the business. The Fixed Assets module (ADR-0030) provides the register, the depreciation engine, and the GL integration that keeps the balance sheet and the profit and loss account accurate.

This chapter covers registering and managing fixed assets, running depreciation, transferring assets between branches, and disposing of or writing off assets. All screens are available from the **Fixed Assets** navigation group.

---

## 1. Permissions quick reference

| Task | Permission code required |
|---|---|
| View asset categories | `FA.CATEGORY.VIEW` |
| Create / edit / archive asset categories | `FA.CATEGORY.MANAGE` |
| View the asset register, schedule, revaluations, depreciation runs, reconciliation | `FA.VIEW` |
| Register, edit, place in service, transfer assets | `FA.REGISTER.MANAGE` |
| Dispose, write off, or revalue an asset | `FA.DISPOSE` |
| Preview and post depreciation runs | `FA.DEPRECIATE` |

Navigation items are hidden when the corresponding permission is absent.

---

## 2. Asset categories

Navigate to **Fixed Assets > Asset Categories** (`/admin/asset-categories`).

**What is an asset category, and why does it exist?**
An asset category is a classification template that groups assets of the same type together — for example "Motor Vehicles", "Machinery", or "Office Furniture". It is used because assets of the same type typically depreciate at the same rate, have the same useful life, and should post to the same General Ledger (GL) accounts. Rather than setting the depreciation method, useful life, and three GL account codes on every individual asset, you set them once on the category and every asset in that category inherits them. This ensures consistency, reduces data-entry errors, and means a change in accounting policy (such as adjusting the useful life for a class of machinery) can be applied at the category level without re-editing each asset. Before any asset can be registered the relevant category must exist.

An asset category defines the depreciation method, useful life, and GL accounts used for assets of a particular type (e.g. Machinery, Vehicles, Furniture). Categories must be set up before any asset can be registered.

### 2.1 Creating a category

1. Click **New Category**.
2. Enter a unique **Code** (e.g. `MACH`) and a **Name** (e.g. Machinery).
3. Choose the **Depreciation Method**:
   - **Straight Line** — equal charge each period.
   - **Reducing Balance** — percentage of the remaining book value each period. Requires a **Reducing Rate** (e.g. `0.25` for 25%).
4. Enter the **Default Life Periods** — the standard useful life in accounting periods.
5. Enter the three **GL Account IDs** by their numeric identifier:
   - Asset Account (the balance-sheet asset account, e.g. 1510)
   - Accumulated Depreciation Account (the contra account, e.g. 1515)
   - Depreciation Expense Account (the P&L charge account, e.g. 6510)
6. Click **Submit**.

New categories are created with **Active** status.

**Validation.** Code must be unique within the company. Reducing Balance requires a Reducing Rate. All three GL account IDs are required.

### 2.2 Editing a category

Open the category detail (navigate from the list). Click **Edit**, change the name, method, life, or account IDs, and save. The code is not editable after creation.

### 2.3 Archiving a category

Open the category detail and click **Archive**. The status changes to **Archived**. Archived categories are hidden from the category dropdown on the asset-registration form. An archived category is not deleted; its history and associated assets remain.

---

## 3. Asset register

Navigate to **Fixed Assets > Fixed Assets** (`/admin/fixed-assets`).

**What is the asset register?**
The asset register is the master list of every fixed asset the company owns. It is the single source of truth for capital investment: it records the original cost of each asset, the depreciation accumulated against it so far, and the resulting **net book value (NBV)** — the carrying value shown on the balance sheet. Every purchase of a capital item must be entered here (not coded to expense) so that the balance sheet correctly shows the asset, the profit and loss account receives only the proportionate depreciation charge each period, and the year-end accounts accurately reflect the company's capital base. The register is used by the finance team and reviewed by auditors to verify that assets exist, are in service, and are depreciated appropriately. The system keeps the register in step with the GL: every capitalisation, depreciation run, revaluation, and disposal posts a matching GL entry, and the FA-to-GL reconciliation screen (section 9) confirms the two agree.

The register lists all fixed assets for the selected company. Use the status filter to show assets by state: Draft, In Service, Disposed, or Written Off.

### Asset status lifecycle

```
DRAFT
  |-- (place in service) --> IN_SERVICE
                              |-- (dispose/SALE)     --> DISPOSED
                              |-- (write-off)        --> WRITTEN_OFF
                              \-- (transfer)         --> IN_SERVICE (branch changes)
```

DISPOSED and WRITTEN_OFF are terminal states.

### 3.1 Registering an asset

1. Navigate to **Fixed Assets > Register Asset** (`/admin/fixed-assets/create`).
2. Select the **Company**.
3. Choose the **Category** from the dropdown (only Active categories are listed).
4. Pick the **Branch** from the picker (search by name).
5. Enter the **Asset Name** (e.g. Lathe #1).
6. Enter the **Acquisition Cost** (the purchase price, excluding VAT).
7. Enter the **Salvage Value** (the estimated residual value at the end of useful life; enter 0 if none).
8. Choose the **Depreciation Method** (defaults from the category, can be overridden).
9. For Reducing Balance, enter the **Reducing Rate**.
10. Enter the **Life Periods** (can be overridden from the category default).
11. Enter the **Acquisition Date** and **Depreciation Start Date** (ISO format yyyy-MM-dd).
12. Optionally enter an **Asset Tag**, **Location**, **Cost Centre ID**, and **Notes**.
13. Click **Submit**.

The asset is created with status **Draft** and a system-generated asset number (e.g. `AST-0001`). No GL posting occurs at this stage.

**Validation.** All required fields must be present. Reducing Balance requires a Reducing Rate. Life Periods must be at least 1.

### 3.2 Editing asset details

Non-financial fields (name, location, asset tag, cost centre) can only be edited while the asset is in **Draft** status. Open the asset detail, click **Edit**, make changes, and save.

Financial fields (acquisition cost, method, life, dates) cannot be changed after the asset is registered. To correct these, you must dispose of or write off the asset and register a new one.

### 3.3 Viewing the asset detail

Open any asset from the list. The detail screen shows:

- Header: asset number, name, category, branch, status badge, acquisition cost, accumulated depreciation, net book value (NBV), and (if revalued) the revaluation reserve balance.
- **Depreciation Schedule** tab (available when In Service) — a line for each period showing planned charge, accumulated depreciation after, NBV after, and a posted flag.
- **Revaluations** tab (available when In Service) — history of all revaluations in date order.

The asset number is the human identifier shown throughout the UI. The internal identifier appears only in the browser address bar.

---

## 4. Placing an asset in service

**What does "placing in service" mean?**
A Draft asset has been registered but not yet capitalised: it exists in the register but has no effect on the books. Placing an asset in service is the act of formally recognising it on the balance sheet — the moment the business acknowledges it owns an asset with an economic value. When you place an asset in service, two things happen simultaneously: (1) a GL journal entry is posted that moves the cost onto the Fixed Assets account (the balance-sheet impact), and (2) the full depreciation schedule is generated for the asset's entire useful life, so the system knows exactly how much to charge in each future period. The posting date must fall within an open fiscal period because it is a real accounting event. Until an asset is placed in service, it produces no depreciation and appears nowhere on the financial statements.

Placing an asset in service capitalises it: the system posts a GL entry and generates the depreciation schedule.

1. Open a **Draft** asset.
2. Click **Place in Service**.
3. Enter the **Posting Date** (must fall in an open fiscal period).
4. Confirm.

Status changes to **In Service**. A capitalisation GL entry is posted (DR Asset Account / CR Cash or AP Clearing). The depreciation schedule is generated for the full useful life.

**Validation.** Posting date is required. The fiscal period containing the posting date must be open. The action is available only on Draft assets.

---

## 5. Transferring an asset

**What is an asset transfer?**
A transfer is a purely administrative change that moves an asset from one branch or cost centre to another — for example, when a vehicle is reassigned from the Dar es Salaam branch to the Arusha branch. It has no accounting effect: the asset's cost, accumulated depreciation, and NBV remain unchanged, and no GL entry is posted. The purpose is to keep the register accurate so that each branch's asset list reflects what is physically present there, which matters for insurance, physical verification, and cost-centre reporting.

A transfer changes the branch or cost centre of an asset without affecting its financial values. No GL entry is posted.

1. Open a **Draft** or **In Service** asset.
2. Click **Transfer**.
3. Enter the target **Branch ID** and optionally a new **Location** and **Cost Centre ID**.
4. Confirm.

The asset's branch and location are updated immediately. Disposed and Written-Off assets cannot be transferred.

> **Note.** The transfer form accepts branch and cost centre as typed numeric IDs rather than pickers. Check with your system administrator for the correct numeric IDs if you do not know them.

---

## 6. Depreciation

**What is depreciation, and why is it run periodically?**
Depreciation is the systematic allocation of an asset's cost over its useful life. A delivery van costing TZS 24,000,000 that is expected to last 4 years does not cost the business TZS 24,000,000 in year one — it costs roughly TZS 6,000,000 per year (on the straight-line method). Recording that annual charge on the profit and loss account gives a realistic view of operating costs and ensures the balance sheet shows the asset at its current economic value, not its original price. Without running depreciation, the P&L understates costs, profits are overstated, and the balance sheet carries assets at inflated values. The system enforces one depreciation run per fiscal period per company: once a period's charges are posted, they cannot be doubled-up.

### 6.1 Supported methods

| Method | Behaviour |
|---|---|
| **Straight Line** | Equal charge each period: (Acquisition Cost − Salvage Value) / Life Periods |
| **Reducing Balance** | Percentage of the closing book value each period: NBV × Reducing Rate |

**Straight Line** is simpler and produces equal charges — appropriate for assets that provide roughly equal benefit in each period (office furniture, computers). **Reducing Balance** produces a higher charge early and a lower charge later — appropriate for assets that lose value quickly in the first years of use (vehicles, plant). In both cases the final period's charge is a residual plug that ensures the asset reaches exactly its salvage value: there is no rounding drift over the asset's life.

### 6.2 Previewing a depreciation run

**What is a depreciation run preview?**
A preview is a read-only simulation: it shows you exactly which assets would be charged and what amount each would attract if you were to post the run right now. No journal is posted and no data is changed. This is the recommended step before posting, because once a run is posted for a period it cannot be reversed or re-run. Reviewing the preview lets you catch anomalies — an unexpected zero charge, a newly capitalised asset you forgot to check — before they reach the books.

Before posting, preview the run to see what charges will be created.

1. Navigate to **Fixed Assets > Run Depreciation** (`/admin/depreciation-runs/post`).
2. Select the **Company**.
3. Enter the **Fiscal Period UID** for the period you want to depreciate.
4. Click **Preview**.

The preview table lists each eligible asset with its planned charge for the period, plus a total. Nothing is posted.

### 6.3 Posting a depreciation run

**What happens when you post a depreciation run?**
Posting a depreciation run does four things at once: (1) it creates a `DEPR-####` run record that acts as the audit trail for the period; (2) it posts a single consolidated GL journal — one Debit to Depreciation Expense and one Credit to Accumulated Depreciation per asset category — covering every eligible asset; (3) it marks each asset's schedule line for the period as posted and increases each asset's accumulated depreciation balance; and (4) it makes the run idempotent: re-running the same period is a safe no-op (the system returns the existing run without posting twice). This idempotency guarantee means you can safely retry a run if a network error occurs during posting, with no risk of double-charging.

After reviewing the preview:

1. Enter the **Posting Date** (must fall within the selected open fiscal period).
2. Click **Post**.

The system creates a depreciation run with status **Posted** and a run number (e.g. `DEPR-0001`). A single consolidated GL entry is posted covering all eligible assets. Each asset's accumulated depreciation balance increases. The schedule lines for the period are marked as posted.

**Validation.** Only one depreciation run is allowed per company per fiscal period. Attempting a second run for the same period is rejected.

### 6.4 Viewing depreciation runs

Navigate to **Fixed Assets > Depreciation Runs** (`/admin/depreciation-runs`). The list shows all posted runs in reverse date order. Click a run to see the detail, which includes per-asset lines showing the charge amount, accumulated depreciation after the run, and NBV after the run.

---

## 7. Revaluing an asset

**What is an asset revaluation, and when is it needed?**
An asset revaluation adjusts the carrying value of an asset to reflect its current fair market value, typically when an independent appraisal shows that the asset is worth significantly more or less than its book value. An upward revaluation increases the asset's carrying value on the balance sheet and creates a credit to a **Revaluation Reserve** (an equity account): the company is wealthier on paper, but the gain is deferred in equity rather than taken to income. A downward revaluation reduces the carrying value and is charged to the profit and loss account (a loss). In both cases the remaining depreciation schedule is regenerated from the new carrying value over the remaining useful life, so future depreciation charges reflect the revised base. Revaluation is done by the finance team when an appraisal indicates the book value is materially different from market value — typically at year-end or when preparing the accounts for a transaction such as a disposal or a valuation exercise.

Revaluation adjusts the carrying cost of an In Service asset to its current fair value. The depreciation schedule is regenerated after a revaluation.

1. Open an **In Service** asset.
2. Click **Revalue**.
3. Choose the **Direction**: Up or Down.
4. Enter the **Delta Amount** (the change in carrying cost, always a positive number).
5. Enter the **Revaluation Date**.
6. Enter a **Reason** (e.g. market appraisal).
7. Confirm.

For an **Up** revaluation: carrying cost increases by the delta; the revaluation reserve increases.

For a **Down** revaluation: carrying cost decreases by the delta. The delta must not reduce the carrying cost below the accumulated depreciation balance (the NBV cannot go below zero due to a revaluation).

The revaluation is recorded in the Revaluations tab. The depreciation schedule is regenerated from the new carrying cost over the remaining useful life.

---

## 8. Disposing of an asset

**What is an asset disposal?**
A disposal is the formal removal of an asset from the register when it is sold or scrapped. When an asset leaves the business, its gross cost must be removed from the Fixed Assets account, its accumulated depreciation must be cleared from the contra account, and any difference between the proceeds received and the asset's net book value at that date is recognised as a **gain or loss on disposal** on the profit and loss account. Failing to record a disposal leaves "ghost" assets on the balance sheet — assets the company no longer owns, overstating the balance sheet and inflating accumulated depreciation. The disposal also posts any outstanding scheduled depreciation up to the disposal date, ensuring the NBV used to calculate the gain or loss is accurate.

### 8.1 Disposal by sale

Use this option when the asset is sold.

1. Open an **In Service** asset.
2. Click **Dispose**.
3. Enter the **Disposal Date** (must fall in an open fiscal period).
4. Enter the **Proceeds Amount** (the sale price; enter `0` if the asset is given away for nothing).
5. Enter an optional reason.
6. Confirm.

The system first posts any depreciation charges that are scheduled up to the disposal date but have not yet been posted. It then calculates the net book value at the disposal date and computes the gain or loss:

`Gain / (Loss) = Proceeds − NBV at disposal`

Status changes to **Disposed**. A disposal GL entry is posted. An asset can only be disposed of once.

### 8.2 Write-off

**What is a write-off?**
A write-off is used when an asset is scrapped, lost, stolen, or so impaired that it has no recoverable value — so no sale proceeds are received. It is identical to a disposal by sale except the proceeds are forced to zero, meaning the entire remaining NBV becomes a loss on the profit and loss account. Common examples include equipment damaged beyond repair, assets destroyed in a fire, or obsolete technology with zero resale value.

Use this option when the asset is scrapped, lost, or fully impaired and no proceeds are received.

1. Open an **In Service** asset.
2. Click **Write Off**.
3. Enter the **Write-Off Date** and an optional reason.
4. Confirm.

The loss equals the full NBV at the write-off date (proceeds are forced to zero). The same final-period depreciation logic applies. Status changes to **Written Off**.

---

## 9. FA to GL reconciliation

**What is the FA-to-GL reconciliation, and why does it matter?**
The reconciliation screen confirms that the asset register and the General Ledger agree. Because every capitalisation, depreciation run, revaluation, and disposal in this module posts a matching GL journal, the sum of all asset costs in the register should always equal the balance on the Fixed Assets GL account, and the sum of all accumulated depreciation in the register should always equal the balance on the Accumulated Depreciation GL account. A discrepancy means someone has posted a manual journal directly to one of those GL accounts, bypassing the register — a data-integrity problem that must be investigated. A green "Ties" indicator confirms the books are clean; a red "Does Not Tie" indicator is a flag for the finance team to investigate before month-end or year-end close.

Navigate to **Fixed Assets > Reconciliation** (`/admin/fixed-assets/reconciliation`). Requires the `FA.VIEW` permission.

1. Select the **Company**.
2. The report compares two balances:
   - **Register Cost** — the sum of acquisition costs in the asset register.
   - **GL Cost Balance** — the total of all asset GL accounts.
   - **Register Accumulated Depreciation** — the sum of accumulated depreciation in the register.
   - **GL Accumulated Depreciation Balance** — the total of all accumulated-depreciation GL accounts.

Both bars show a green **Ties** indicator when the register and GL agree. A red **Does Not Tie** indicator means there is a discrepancy. A mismatch typically indicates a manual GL journal was posted directly to an asset account, which bypasses the register.

---

## 10. Frequently asked questions

**When does a GL entry get posted for a new asset?**
No GL entry is posted when the asset is registered (Draft). The capitalisation entry is posted when you click Place in Service.

**Can I change the depreciation method after placing an asset in service?**
No. Method and financial parameters are fixed at registration time. If a correction is needed, dispose of the asset and register a new one.

**What happens to scheduled depreciation at the time of disposal?**
The system automatically posts any depreciation that is scheduled and not yet posted, up to the disposal date. This ensures NBV is accurate before the gain/loss is calculated.

**Can I run depreciation more than once for the same period?**
No. The system enforces one run per company per fiscal period. Use the preview function first to confirm the charges before posting.

**Does a branch transfer post a GL entry?**
No. A transfer is a location update only and has no accounting effect.

---

# Projects

**What is the Projects module?**
A project (also called a job) is a discrete unit of work undertaken for a customer or for internal purposes, with its own budget and a defined scope. The Projects module gives the business a **job-costing lens**: it tags costs (materials issued, supplier bills, labour timesheets) and revenues (sales invoices) with a project identifier so that the profit or loss on each individual job can be tracked — not just the company's overall profit. Without job costing, a company knows it made a profit last month but cannot tell which jobs were profitable and which were loss-making. This module does not create a separate cost ledger; instead it adds an analytical tag on the same journal lines the financial modules already post, and the Project P&L is a filtered view of the General Ledger grouped by project. This design guarantees that the project figures always agree with the company's financial statements (ADR-0033).

This chapter covers creating and managing projects, adding tasks, recording time, issuing materials to a project, and viewing the project P&L and the cross-project WIP report. All screens are available from the **Projects** navigation group.

---

## 1. Permissions quick reference

| Task | Permission code required |
|---|---|
| View the project list and project detail | `PROJECTS.PROJECT.VIEW` |
| Create a project | `PROJECTS.PROJECT.CREATE` |
| Edit a project and manage its lifecycle | `PROJECTS.PROJECT.MANAGE` |
| View project tasks | `PROJECTS.TASK.VIEW` |
| Create, edit, and deactivate tasks | `PROJECTS.TASK.MANAGE` |
| View timesheets for a project | `PROJECTS.TIMESHEET.VIEW` |
| Record a timesheet entry | `PROJECTS.TIMESHEET.RECORD` |
| Issue materials to a project | `PROJECTS.ISSUE.CREATE` |
| View the project P&L and WIP report | `PROJECTS.COSTING.VIEW` |

Navigation items are hidden when the corresponding permission is absent. A user who has `PROJECTS.COSTING.VIEW` but not `PROJECTS.PROJECT.VIEW` sees only the WIP Report link.

---

## 2. Project lifecycle

**Why does a project have a lifecycle?**
A project lifecycle controls what actions can be taken at each stage. A Draft project is being set up; costs and timesheets cannot yet be recorded against it. An Active project is open for work. On Hold pauses activity while still allowing ad-hoc material issues if needed. Completed and Cancelled are terminal: once a job is done or abandoned, no more costs can be added (which would distort the final profitability figure). The lifecycle exists to prevent accidental cost postings to the wrong job state and to create a clear audit trail showing when a project was open for charges.

A project passes through a defined set of statuses. The allowed transitions are:

```
DRAFT
  |-- (activate)  --> ACTIVE
  |                     |-- (hold)     --> ON_HOLD
  |                     |                     |-- (resume) --> ACTIVE
  |                     |-- (complete) --> COMPLETED (terminal)
  \-- (cancel)    --> CANCELLED (terminal)
                 (cancel also from ACTIVE, ON_HOLD)
```

- Only **Active** and **On Hold** projects accept material issues and timesheets.
- **Completed** and **Cancelled** are terminal; no further lifecycle transitions are possible.
- A project cannot be moved back to Draft from any other status.

---

## 3. Creating a project

Navigate to **Projects > Projects** (`/admin/projects`) and click **New Project**.

1. Enter the **Project Name** (required, up to 160 characters).
2. Optionally enter **Planned Start** and **End** dates, a **Budget**, and **Notes**.
3. Click **Submit**.

The project is created with status **Draft** and a system-generated project number (e.g. `PRJ-0001`). The success alert shows the project number.

To set the customer and project manager, open the project detail after creation (see section 4.2).

**Validation.** Project name is required. Name must not exceed 160 characters.

---

## 4. Project detail

Click any project row to navigate to the project detail screen (`/admin/projects/uid/:uid`). The detail screen is divided into panels:

- **Header** — project number, name, status badge, dates, budget.
- **Tasks** — the list of tasks assigned to this project.
- **Timesheets** — paged list of time entries.
- **Issue to Job** — material and cost issue panel.
- **P&L** — project profit and loss button (requires `PROJECTS.COSTING.VIEW`).

The project number is the human identifier shown throughout the UI. The internal identifier appears only in the browser address bar.

### 4.1 Lifecycle actions

The buttons shown depend on the current status:

| Current status | Buttons available |
|---|---|
| Draft | Activate, Cancel |
| Active | Hold, Complete, Cancel |
| On Hold | Resume, Complete, Cancel |
| Completed | (none — terminal) |
| Cancelled | (none — terminal) |

Click the relevant button and confirm. A reason is not required for any transition.

### 4.2 Editing the project

Click **Edit** on the project header (available when the user has `PROJECTS.PROJECT.MANAGE`).

The following fields can be edited at any time before the project becomes terminal:

- **Name**
- **Planned Start Date** and **End Date**
- **Budget** (in base currency)
- **Notes** (up to 500 characters)
- **Customer** — chosen via the customer picker (search by display name).
- **Project Manager** — chosen via the user picker (search by name; hint shows username).

Click **Save** to apply changes. Clearing the Customer or Manager picker removes the link.

### 4.3 Archiving a project

Click **Archive** to move the project to **Archived** master status. Archived projects are hidden from the default list view. They can be retrieved by selecting **Archived** in the status filter.

Archiving does not change the project status (a DRAFT project stays DRAFT; it is simply hidden from the normal list).

---

## 5. Project tasks

**What is a project task?**
A task is a sub-division of a project — a discrete work package within the job. Tasks allow costs and time to be recorded at a more granular level than the project as a whole. For example, a construction project might have tasks for "Foundation Works", "Structural Frame", and "Electrical Installation". When materials are issued or timesheets are recorded, they can be linked to a specific task, which lets the project manager see which parts of the job are over budget or behind schedule. Tasks are optional: if a project is simple enough, all costs and time can be recorded against the project without specifying a task.

Tasks are managed within the **Tasks** panel on the project detail screen. There is no standalone task list screen.

### 5.1 Creating a task

1. In the Tasks panel, click **Add Task**.
2. Enter a **Task Code** (up to 30 characters, unique within the project) and a **Task Name** (up to 160 characters).
3. Enter optional **Planned Hours**.
4. Tick **Billable** if time spent on this task is billable to the customer.
5. Click **Submit**.

The task is created with **Active** status.

### 5.2 Editing a task

Click the edit icon on a task row. You can change the task code, name, planned hours, and billable flag. Click **Save**.

### 5.3 Deactivating a task

Click **Deactivate** on a task row. The task moves to **Inactive** status and disappears from the default (Active) task list. Inactive tasks are not deleted and can be viewed by filtering for Inactive tasks via the API. Deactivation is a soft operation.

---

## 6. Timesheets

**What is a timesheet entry?**
A timesheet entry records the hours a person worked on a project on a given day. In this module, timesheet entries are **informational** rather than financial: they are stored and shown on the project but they do not post a labour cost to the General Ledger (in v1, actual labour cost reaches the project P&L through payroll journals tagged to the project, not through timesheet entries directly). Timesheets are used to track planned vs actual hours, monitor workforce utilisation, and support billing for time-and-materials projects. Entries are permanent once recorded: they cannot be edited or deleted.

Timesheets record hours worked against a project (and optionally a specific task). They are managed within the **Timesheets** panel on the project detail screen.

### 6.1 Recording a timesheet entry

1. In the Timesheets panel, click **Record Time**.
2. Enter the **User ID** (the numeric user identifier — ask your administrator if you do not know it).
3. Enter the **Work Date** (yyyy-MM-dd).
4. Enter the **Hours** (decimal; minimum 0.01).
5. Tick **Billable** if the time is billable.
6. Optionally pick a **Task** from the picker to link the entry to a specific task.
7. Click **Submit**.

The timesheet entry is appended to the panel list. Time entries are permanent; they cannot be edited or deleted after recording.

**Validation.** User ID, Work Date, and Hours are required. Hours must be greater than zero.

### 6.2 Viewing timesheets

The Timesheets panel shows entries in pages of 20. Use the paginator (First, Previous, page numbers, Next, Last) to move between pages.

---

## 7. Issuing materials to a project

**What is a material issue to a job?**
Issuing materials to a project is the act of transferring stock items from the warehouse to a specific job. When you issue materials, three things happen simultaneously: (1) the stock quantity is reduced at the current branch; (2) the stock value (based on the product's current moving-average cost) is transferred from the Inventory balance sheet account to Cost of Sales on the profit and loss account; and (3) the GL entry is tagged with the project identifier, so the cost appears in the project P&L under the "Material" cost type. This is how the cost of physical materials consumed on a job is tracked. Without issuing materials, materials pulled from the store for a job would remain as stock on the balance sheet even though they have been consumed, overstating inventory and understating job costs.

The **Issue to Job** panel on the project detail screen records the issue of stock items to the project. The issue deducts stock and posts a COGS entry tagged to the project.

Materials can only be issued to **Active** or **On Hold** projects. The Issue panel is hidden for Draft, Completed, and Cancelled projects.

### 7.1 Recording an issue

1. Open the project detail (status must be Active or On Hold).
2. In the Issue to Job panel, click **Issue Materials**.
3. Click **Add Line** for each item:
   - Pick the **Product** from the picker (search by name; hint shows product code). Only GOODS (stockable) products are valid.
   - Enter the **Quantity**.
4. Optionally enter an **Issue Date** and a **Reason**.
5. Click **Submit**.

The system generates an issue number (e.g. `PJI-0001`). The success alert shows the issue number.

For each line, the system:

1. Deducts the quantity from stock at the current branch.
2. Values the issue at the product's current moving-average cost.
3. Posts a GL entry: DR Cost of Sales / CR Inventory.
4. Tags the stock movement and GL entry with the project (and task if specified) dimension.

**Cost-skipped lines.** If a product has no established average cost, the quantity is still deducted from stock but no GL cost entry is posted. An anomaly is logged. The line appears in the issue with a value of zero.

**Validation.** At least one line is required. Product and quantity are required per line. Quantity is treated as a positive magnitude regardless of sign.

---

**Example — Issue materials to a construction job and verify stock deduction:**

Project manager Salma Abdallah is running project `PRJ-0007` (Kariakoo Office Fit-Out), status **Active**. The site team needs electrical cables and paint for the first week.

1. Navigate to **Projects › Projects** (`/admin/projects`), click on `PRJ-0007` to open the detail at `/admin/projects/uid/:uid`.
2. Scroll to the **Issue to Job** panel. Click **Issue Materials**.
3. Add lines:
   - Product: `Electrical Cable 2.5mm (ELC-025)`, Qty: `150` (metres).
   - Product: `Interior Paint 20L (PNT-INT)`, Qty: `8` (tins).
4. Issue Date: `2026-06-10`; Reason: `Week 1 site works`. Click **Submit**.
5. System generates issue `PJI-0014`. For each line:
   - 150 metres of cable deducted from stock at DSM Branch at the cable's current moving-average cost (TZS 4,200/m = TZS 630,000).
   - 8 tins of paint deducted at TZS 38,500/tin = TZS 308,000.
   - GL entries posted: DR Cost of Sales / CR Inventory, each tagged to project `PRJ-0007`.
6. Total materials issued: TZS 938,000. The project's P&L now reflects this cost under the **Material** cost type.

---

## 8. Project P&L

**What is the Project P&L, and what does it show?**
The Project P&L (Profit and Loss) is a filtered view of the General Ledger that shows only the income and costs tagged to a single project. Revenue is the total of sales invoices tagged to the project; cost is broken down by type — Material (stock issues and goods purchases), Labour (payroll entries tagged to the project), Subcontract (service supplier bills), Overhead (other expense bills), and Other. The margin is the difference between revenue and total cost. The **WIP (Work in Progress)** figure shows how much cost has been incurred that has not yet been matched by billing: it represents work done but not yet invoiced, which sits as an asset on the balance sheet until the customer is billed. The budget variance shows whether the job is tracking above or below its planned cost. A Reconciliation bar confirms that the P&L figures are consistent with the underlying GL postings.

From the project detail screen, click **View P&L** (requires `PROJECTS.COSTING.VIEW`). The P&L report loads as a panel showing:

| Section | Contents |
|---|---|
| Revenue | Total income tagged to this project from GL |
| Cost by type | Subtotals per cost type (Material, Labour, Overhead, Subcontract, Other) |
| Total cost | Sum of all cost lines |
| Gross margin | Revenue − Total cost |
| Margin % | Gross margin / Revenue × 100 (blank if no revenue) |
| Budget | The planned budget set on the project |
| Budget variance | Budget − Total cost |
| WIP | max(0, Total cost − Revenue) — unbilled cost |
| Reconciliation | Computed cost from the project ledger vs GL account totals |

The reconciliation bar shows **Balanced** when the two totals agree. A mismatch here indicates a data integrity issue requiring finance review.

---

**Example — View the project P&L mid-project and check the WIP balance:**

Three weeks into project `PRJ-0007` (Kariakoo Office Fit-Out), Salma Abdallah wants to check profitability before the final billing.

1. Open the project detail at `/admin/projects/uid/:uid` for `PRJ-0007`.
2. Click **View P&L** (requires `PROJECTS.COSTING.VIEW`). The P&L panel loads:

| Section | Amount (TZS) |
|---|---|
| Revenue | 3,500,000 |
| Cost — Material | 2,175,000 |
| Cost — Labour | 840,000 |
| Cost — Overhead | 120,000 |
| Total Cost | 3,135,000 |
| Gross Margin | 365,000 |
| Margin % | 10.4% |
| Budget | 4,200,000 |
| Budget Variance | +1,065,000 (cost below budget) |
| WIP | 0 (Revenue > Cost) |

The Reconciliation bar shows **Balanced** — the project ledger ties to the GL account totals. Revenue of TZS 3.5M was posted via a sales invoice tagged to this project; costs include the two material issues (TZS 938,000 from week 1 + TZS 1,237,000 from week 2) plus labour timesheets. Since revenue exceeds total cost, WIP is zero. Salma notes the healthy margin and continues to the next billing milestone.

---

## 9. Cross-project WIP report

**What is the WIP report, and who uses it?**
The WIP (Work in Progress) report is a company-wide summary that shows, for every project, how much cost has been incurred versus how much has been billed. WIP represents costs that have been spent but not yet recovered from the customer — it is an asset (money owed back to the company through future billing) and it appears on the balance sheet. Finance managers and project directors use the WIP report at month-end to understand the total unbilled exposure across all jobs, to flag jobs that are heavily over-cost relative to billing, and to support the preparation of interim billing or progress claims. A project with high WIP and low revenue may indicate that billing is overdue.

Navigate to **Projects > WIP Report** (`/admin/projects/wip-report`). Requires `PROJECTS.COSTING.VIEW`.

1. Select the **Company**.
2. Click **Load Report**.

The report lists all projects for the company that have cost incurred, showing:

| Column | Contents |
|---|---|
| Project # | Human project number |
| Name | Project name |
| Cost Incurred | Total cost posted to the project |
| Billed | Total revenue or billings tagged to the project |
| WIP | max(0, Cost Incurred − Billed) |

A footer row shows the totals across all projects.

The WIP report is not paginated. All projects are shown in a single list.

---

## 10. Frequently asked questions

**Can I issue materials to a project that is On Hold?**
Yes. Both Active and On Hold projects accept material issues and timesheet entries. Issue is blocked only for Draft, Completed, and Cancelled projects.

**What happens if I complete a project with open WIP?**
Completing a project changes its status to Completed but does not post any accounting entries. Open WIP remains on the balance sheet until it is cleared by a billing entry or a journal.

**Can I record timesheets against a Completed project?**
No. Timesheet recording (and material issue) requires the project to be Active or On Hold.

**Why does my issue have zero value for some lines?**
The product had no established moving-average cost at the time of issue. The quantity was deducted from stock, but the COGS entry was skipped. Set an opening valuation for the product (see the Inventory chapter) and then record the cost via a manual GL journal for this issue.

**What does the WIP balance on the P&L represent?**
WIP (Work in Progress) is the cost incurred on the project that has not yet been matched by revenue or billing. It represents an asset on the balance sheet — costs that are recoverable but not yet recognised as expense. Once the project is billed and revenue is recognised, the WIP reduces to zero.

**Can I reopen a Completed or Cancelled project?**
No. These are terminal statuses. If you need to continue work on the project, create a new project and reference the original project number in the notes.

---

# Finance & Accounting

This chapter covers every module available under the **Accounting** navigation group: General Ledger, Accounts Receivable, Accounts Payable, Cash & Bank, Tax, and Foreign Exchange (FX). The chapter is written for finance staff — accountants, AP/AR clerks, and treasury officers — who use the system day-to-day.

---

## General Ledger

The **General Ledger (GL)** is the central book of record for your company's finances. Think of it as the master filing system into which every financial event — a sale, a payment, a bank transfer, a year-end adjustment — is eventually recorded as a pair of entries. Every other finance module in this system (AR, AP, Cash & Bank, VAT) feeds its financial effect into the GL. If you want to know "where does the company stand financially right now?", you read the GL; if you want to understand what produced that position, you trace back through the documents that posted to it.

The GL works on the principle of **double-entry bookkeeping**, explained below. Two prerequisites must exist before any posting can happen: a **Chart of Accounts** (the master list of ledger accounts) and at least one open **Fiscal Period** (the calendar gate that controls which dates accept entries).

---

### Chart of Accounts

**What it is.** The Chart of Accounts (CoA) is the master list of all GL accounts for your company. Every financial event in the system is expressed as movements between two or more of these accounts. An account is simply a named bucket that collects amounts of a particular kind — "Cash", "Accounts Receivable", "Sales Revenue", "VAT Payable", etc.

**Why it exists.** Without a structured account list, the books would be an unclassified mass of transactions with no way to produce a trial balance, profit-and-loss statement, or balance sheet. The CoA is the taxonomy that turns a log of transactions into a set of readable financial statements. Each account is assigned one of five **types** (ASSET, LIABILITY, EQUITY, INCOME, EXPENSE), which determines where it appears on financial reports and what its **normal balance** is.

**Understanding account types and normal balances.** The five types map to the two sides of the balance sheet and the profit-and-loss statement:

| Type | What it represents | Normal Balance |
|---|---|---|
| ASSET | Things the company owns or is owed (cash, receivables, inventory) | DEBIT |
| LIABILITY | Amounts the company owes to others (payables, VAT due) | CREDIT |
| EQUITY | The owners' stake in the business (capital, retained earnings) | CREDIT |
| INCOME | Revenue earned | CREDIT |
| EXPENSE | Costs incurred | DEBIT |

A "normal balance" tells you which side — debit or credit — makes the account go up. An ASSET account increases with a debit and decreases with a credit; a LIABILITY account increases with a credit and decreases with a debit. The system derives and stores the normal balance automatically from the account type, so you never need to set it manually.

**Double-entry bookkeeping in plain language.** Every financial event is recorded as at least two entries — one account is debited (left side) and another is credited (right side) — and the total debits across all lines must always equal the total credits. This is the fundamental rule: **debits = credits in every transaction**. The system enforces this; the Post button is disabled until the entry balances. Why? Because a debit to one account must come from somewhere, and a credit to another must go somewhere. Money does not appear or disappear — it moves. A sale, for example, debits Accounts Receivable (the customer owes more) and credits Sales Revenue (income goes up) and VAT Payable (the tax liability goes up). The two sides always balance because they are two perspectives on the same event.

**When it is used.** The CoA is set up before any other finance work begins and is maintained by a user with the `GL.MANAGE` permission whenever a new account category is needed. Once created, accounts are available immediately for posting.

Navigate to **Accounting > Chart of Accounts** (`/admin/gl/accounts`).

The table shows:

| Column | Meaning |
|---|---|
| Code | Unique account code assigned at creation |
| Name | Human-readable account name |
| Type | One of: **ASSET, LIABILITY, EQUITY, INCOME, EXPENSE** |
| Normal Balance | Derived from type — ASSET and EXPENSE accounts carry a **DEBIT** normal balance; LIABILITY, EQUITY, and INCOME accounts carry a **CREDIT** normal balance. Not user-editable. |
| Status | ACTIVE or INACTIVE |

**To create an account** (requires permission `GL.MANAGE`):

1. Click **Add account**.
2. Enter a unique account code and account name.
3. Choose the account type. The system derives the normal balance automatically.
4. Click **Save**. The new account is immediately available for journal posting and GL config mapping.

**To edit an account** (requires `GL.MANAGE`):

1. Click the edit action on the account row.
2. Update the name or type as needed.
3. Save. The normal balance is recalculated if the type changes.

**To deactivate an account** (requires `GL.MANAGE`):

1. Click **Deactivate** on the row.
2. The account becomes inactive and disappears from all posting pickers.
3. An inactive account can be reactivated by editing it and setting it back to active.

> **Note:** Deactivation is soft — the account record is never deleted. No posting can be made to an inactive account (business rule BR-GL-04).

---

### Posting a Manual Journal

**What it is.** A journal entry is the fundamental unit of posting: a dated, described set of two or more lines that debit and credit specific accounts in balanced amounts. A **manual journal** is one that you compose directly, as opposed to a journal that the system creates automatically (for example, when a sale is finalised or a receipt is recorded). Manual journals are used for accounting adjustments, accruals (recording an expense before the invoice arrives), prepayment amortisation, and error corrections.

**Why it exists.** The automated posting paths cover the main transaction types, but accountants always need a mechanism to make entries the system cannot anticipate — month-end accruals, depreciation write-downs, inter-account reclassifications, and period-end corrections. Manual journals provide this escape valve under controlled, permission-gated conditions.

**When it is used.** Typically at month-end by an accountant or finance manager who holds the `GL.POST` permission. Common triggers include: preparing for period close, recording a provision, or correcting a misposting discovered in review.

**How it works.** A manual journal posts directly (there is no draft state). You compose the lines, verify that debits equal credits, and click Post. The system validates the balance, checks that each account is active, and checks that the posting date falls inside an open fiscal period. If everything passes, a batch number (`JB-####`) is assigned, the entry is written to the ledger, and it is immediately immutable. Corrections are made by reversal (see below), never by editing. The journal is then visible in the journal list and feeds the trial balance.

Navigate to **Accounting > Journals** (`/admin/gl/journals`) and click **Post journal** (`/admin/gl/journals/post`).

**Requirements before posting (requires permission `GL.POST`):**

- At least two active accounts must exist.
- The posting date must fall inside an **OPEN** fiscal period.
- Total debits must equal total credits (the entry must be balanced — business rule BR-GL-01).

**Steps:**

1. Set the **Posting date** (defaults to today). Verify it falls within an open period.
2. Enter a **Description** summarising the purpose of the entry.
3. Each line requires exactly one of a debit or credit amount (not both — business rule BR-GL-08).
   - Use the account dropdown on each line to select an account **by name or code**. Only active accounts are listed.
   - Enter the debit or credit amount for that line.
4. The form shows running **Debits**, **Credits**, and **Difference** totals. The **Post** button remains disabled until the difference is exactly zero.
5. Click **Post**. A success message shows the generated batch number (`JB-####`). You are redirected to the journal detail page.

**Adding and removing lines:**

- Click **Add line** to insert another line.
- Click the remove icon on a line to delete it. The minimum is two lines.

**Validation errors surfaced by the server:**

- Unbalanced entry (BR-GL-01) — the amounts do not sum to zero.
- Inactive account (BR-GL-04) — choose an active account.
- Wrong company account (BR-GL-05) — the account belongs to a different company.
- Closed period (BR-GL-03) — the posting date is in a closed or missing fiscal period.

---

### Reversing a Manual Journal

**What it is.** A reversal is a new journal entry that mirrors an existing one exactly but with every debit and credit swapped. The result is that the two entries cancel each other out on every account, leaving the books as if the original entry had never been made.

**Why it exists.** The GL is **append-only**: once a journal is posted, it cannot be edited or deleted. This is not a limitation — it is a deliberate design principle that protects the integrity of the audit trail. Any change to a posted entry would make it impossible to reconstruct what the books showed at a prior date. Reversal solves the problem by adding a counteracting entry, so the historical record shows both the original entry and the correction, and investigators can see exactly what happened.

**When it is used.** When you discover that a manual journal was posted to the wrong account, with the wrong amount, or in error. The reversal is initiated by the same user who posted (or any user with `GL.POST`), typically at month-end during review.

**How it works.** A reversal uses today's date (the reversal date), references the original entry via a `Reversal Of` link, and posts with source type MANUAL. Because it is the exact swap of a balanced entry, the reversal is balanced by construction. It lands in its own open fiscal period. The original and the reversal coexist permanently in the ledger.

Corrections to a posted journal are always made by **reversal** — a new entry with every line's debit and credit swapped. The ledger is append-only; the original entry is never modified.

**To reverse a journal (requires `GL.POST`):**

1. Open the journal detail from **Accounting > Journals**.
2. If the entry has `Source Type = MANUAL` and is not itself a reversal, the **Reverse** button is visible.
3. Click **Reverse**. A new journal is created immediately (using today as the reversal date) with all amounts swapped. The reversal entry links back to the original via its `Reversal Of` field.

> System-posted entries (source types such as SALES, OPENING\_BALANCE, YEAR\_END\_CLOSE) cannot be reversed here. Correct those through their originating module.

---

### Fiscal Periods (Open/Close)

**What they are.** The fiscal calendar divides the financial year into monthly accounting periods. Each period has a start date, an end date, and a status (OPEN or CLOSED). A **fiscal year** groups twelve such periods.

**Why they exist.** Without period gates, journals could be posted with any date — including dates months or years in the past — which would silently change already-reported figures. Closing a period locks it: no new posting can land in a closed period, so the financial statements for that period are frozen once it closes. This is essential for accurate monthly reporting, auditing, and regulatory filing.

**When they are used.** The finance manager opens a new fiscal year once before it begins (or at system setup). Periods are closed at month-end by a user with the `GL.PERIOD.CLOSE` permission, usually after reconciliations are complete and the month's reports have been approved.

**How they work.** Each fiscal period covers one calendar month. A posting is accepted only when its posting date falls inside an OPEN period. The system derives which period a date belongs to automatically. Closing a period is reversible (a period can be reopened if a late adjustment is needed); closing the entire fiscal year is a separate, more final operation (see Year-End Close below).

Navigate to **Accounting > Fiscal Periods** (`/admin/gl/periods`).

The screen shows two panels:

- **Fiscal Years** — each year with its code and current status (OPEN or CLOSED).
- **Fiscal Periods** — the twelve monthly periods within the selected year, each showing period number, date range, and status.

**Opening a new fiscal year (requires `GL.MANAGE`):**

1. Click **Open fiscal year**.
2. Enter a unique year code (e.g. `FY2027`), the start month (1 = January), and the calendar year.
3. Submit. Twelve monthly periods are created, all in OPEN status.

**Closing a fiscal period (requires `GL.PERIOD.CLOSE`):**

1. On a period row, click **Close**.
2. The period status changes to CLOSED. No further journal postings can be made into a closed period.

**Reopening a period (requires `GL.PERIOD.CLOSE`):**

1. On a CLOSED period row, click **Reopen**.
2. The period returns to OPEN and accepts journal postings again.

> Closing a period is reversible. Closing a fiscal **year** is a separate, more permanent action (see Year-End Close below).

---

### Trial Balance

**What it is.** The Trial Balance is a summary report that lists every GL account with its total debits, total credits, and net balance for a selected period. It is the most direct proof that double-entry has been maintained: if the system's books are correct, the grand total of all debit balances must equal the grand total of all credit balances.

**Why it exists.** The trial balance is the starting point for preparing financial statements (profit-and-loss, balance sheet) and for period-end review. It lets an accountant see every account's movement in one view, spot unexpected balances, and confirm that no unbalanced entries have slipped through.

**When it is used.** Typically at month-end review and before period close, by an accountant or finance manager holding the `AR.VIEW` permission. It can also be run at any time for a diagnostic check.

**How it works.** The system aggregates all journal line amounts by account, grouping them by the account type in canonical order (ASSET, LIABILITY, EQUITY, INCOME, EXPENSE). A balanced set of books shows total debits = total credits in the footer. A non-zero difference is a finance-grade defect requiring investigation.

Navigate to **Accounting > Trial Balance** (`/admin/gl/trial-balance`).

- Select your company (if multi-company).
- Optionally select a specific **fiscal period** to view only that period's movements.
- The table groups accounts by type in canonical order (ASSET, LIABILITY, EQUITY, INCOME, EXPENSE) and shows each account's code, name, total debit, total credit, and net balance.
- The footer shows total debits, total credits, and a **Balanced** indicator. A balanced set of books shows equal debits and credits.

Permission required: `GL.VIEW`.

---

### GL Posting-Account Config

**What it is.** The GL Config is a mapping table that tells the system which specific account in your Chart of Accounts to use when it needs to post automatically. For example, when a sales invoice is finalised, the system needs to know which account is your "Accounts Receivable" control account and which is your "Sales Revenue" account. GL Config provides those answers.

**Why it exists.** Hardcoding account codes into the system software would force every business to use identical account numbers, which is impractical. GL Config externalises that mapping, letting you point each posting role to whatever account you have created in your CoA. A missing mapping fails the posting loudly (an error is raised) rather than posting to a wrong or null account.

**When it is used.** Set up once during initial configuration by a user with `GL.MANAGE` permission. Revisited when account restructuring changes the CoA.

**How it works.** Each config key represents a posting role. The system resolves the relevant key at the moment it needs to post, reads the mapped active account, and uses it as the debit or credit leg of the automatic journal. If the mapped account is inactive or the mapping is missing, the posting fails and the operator is notified to fix the mapping.

Navigate to **Accounting > GL Config** (`/admin/gl/config`).

Permission required: `GL.MANAGE`.

The table shows each configuration key and the currently mapped account. The keys relevant to the core modules include:

- `ACCOUNTS_RECEIVABLE` — the AR control account
- `SALES_REVENUE` — the revenue account for sales auto-posting
- `VAT_PAYABLE` — the output VAT control account
- `CASH` — the default cash posting account
- `RETAINED_EARNINGS` — required for the year-end close

**To set or change a posting account:**

1. Click **Set** on the key row.
2. Pick the account by name from the account picker. Only active accounts are listed.
3. Save. The mapping takes effect immediately.

> All four sales keys (`ACCOUNTS_RECEIVABLE`, `SALES_REVENUE`, `VAT_PAYABLE`, `CASH`) must be configured before sales invoices can be auto-posted to the GL.

---

### Cost-Centre Dimensions

**What they are.** Dimensions (also called **cost centres** or **department codes**) are analysis tags that can be attached to journal lines. They do not change which account a posting hits — the account, amount, and double-entry balance are completely unaffected. Instead, they let you slice the books by a management category: "Which department incurred this expense?", "Which cost centre drove this revenue?".

**Why they exist.** The main GL accounts give a company-level view of the books, but management typically needs to see performance broken down by department, branch, project, or profit centre. Dimensions provide that without multiplying the number of GL accounts (one account per department would make the CoA unmanageable). They are the analytical layer on top of the financial layer.

**When they are used.** Dimension values are tagged on manual journal lines (per line) and inherited automatically from source documents (sales invoices, supplier bills, stock adjustments). Finance or operations staff with `COSTING.MANAGE` permission maintain the dimension value master. Reporting users with `COSTING.VIEW` and `GL.VIEW` run the dimension-sliced trial balance.

**How they work.** The system seeds two built-in dimension types: **Cost Centre** and **Department**. You create the actual values (e.g. "Sales Dept", "Nairobi Branch"). A dimension type can be made **mandatory** on manual journal entries, in which case every manually posted line must carry a value for that slot — system-automated postings (sales, year-end, etc.) are exempt. The dimension-sliced trial balance groups account balances by dimension value, giving a department-level or cost-centre-level P&L.

Navigate to **Accounting > Cost Centre > Dimensions** (`/admin/cost-centre/dimensions`).

**Dimension types** are pre-seeded per company (Cost Centre and Department are built-in). You cannot create or delete dimension types; you can only toggle whether they are **mandatory** on manual journal entries. Navigate to **Accounting > Cost Centre > Values** (`/admin/cost-centre/values`) to manage the actual dimension values.

**To create a dimension value (requires `COSTING.MANAGE`):**

1. Select the dimension type from the type picker.
2. Click **Add value**.
3. Enter a unique code and name. Optionally select a parent value to build a hierarchy.
4. Save.

**Mandatory enforcement:** if a dimension is set to mandatory, every manually posted journal line must include that dimension slot. System-posted entries (sales, year-end, etc.) are exempt.

**Viewing the dimension-sliced trial balance:** Navigate to **Accounting > Cost Centre > Report** (`/admin/cost-centre/report`). Requires both `COSTING.VIEW` and `GL.VIEW`. Select a slot (Cost Centre or Department), optionally filter to a specific value, toggle **Roll up** to include descendants, and click **Run**.

---

### Year-End Close

**What it is.** The year-end close is an accounting operation performed once at the end of each fiscal year. It posts a special journal entry that transfers the net profit or loss for the year into the **Retained Earnings** account on the balance sheet, and simultaneously zeros out all income and expense accounts so they start the new year at zero. The fiscal year is then locked (CLOSED).

**Why it exists.** Income and expense accounts accumulate balances over the course of a year. At year-end, those balances need to be moved to equity (retained earnings) so that the new year starts fresh. Without this close, the income and expense accounts would carry over prior-year totals and the P&L for the new year would be polluted by prior-year figures. The year-end close is also the event that legally "locks the books" for the year, preventing backdated adjustments to a period whose financial statements have been approved and filed.

**When it is used.** Once per year, after all period 12 journals have been posted and reviewed, by a user with the `GL.YEAR.CLOSE` permission. All fiscal periods within the year must have been closed first, and prior fiscal years must already be closed (you cannot close year N if year N-1 is still open).

**How it works.** The system reads the net balance of every INCOME and EXPENSE account for the year, builds one balanced closing journal (source type `YEAR_END_CLOSE`), and posts it. Each income account is debited to zero and each expense account is credited to zero; the net difference (profit or loss) is posted to the Retained Earnings equity account as either a credit (profit) or a debit (loss). All periods in the year are then closed and the year status becomes CLOSED. The closing journal is visible in the journal list and is permanently linked to the fiscal year record. If the close needs to be undone, a reopen operation (available on the most-recently-closed year only) reverses the closing journal as a new append-only entry and reopens all periods.

Navigate to **Accounting > Year-End Close** (`/admin/gl/year-end`).

Permission required: `GL.YEAR.CLOSE`.

**Prerequisites:**

- The `RETAINED_EARNINGS` GL config key must be mapped to an active EQUITY account.
- All prior fiscal years must already be CLOSED (you cannot close year N if year N-1 is still open — business rule BR-CLOSE-04).
- The year must have at least one fiscal period.

**To close a fiscal year:**

1. On the year row showing OPEN, click **Close**.
2. Review the confirmation panel, which describes the retained-earnings posting that will be made.
3. Confirm. The system:
   - Posts a `YEAR_END_CLOSE` journal zeroing every INCOME and EXPENSE account.
   - Credits (net profit) or debits (net loss) the Retained Earnings account.
   - Closes all periods within the year.
   - Sets the year status to CLOSED.
4. A success message appears referencing the closing journal number.

**To reopen a fiscal year (requires `GL.YEAR.CLOSE`):**

Only the most-recently-closed year may be reopened. Click **Reopen** on the CLOSED year row. The system reverses the closing journal (as a new append-only entry) and reopens all periods.

---

## Accounts Receivable

**What it is.** Accounts Receivable (AR) is the module that tracks money owed to your company by customers. When a credit-sale invoice is finalised, the system creates an **AR open item** — a record of the amount the customer owes. Every subsequent receipt, credit note, or write-off against that invoice is tracked here. Together these records form the **AR sub-ledger**: the customer-level detail behind a single GL control account (account 1200 Accounts Receivable).

**Why it exists.** The GL control account tells you the total amount owed to the company, but it does not tell you which customer owes what, how long the balance has been outstanding, or which specific invoice is unpaid. The AR sub-ledger provides that customer-level detail. It also enforces the reconciliation invariant: the sum of all open AR balances in the sub-ledger always equals the balance on account 1200 in the GL. If these two figures disagree, there is a posting error that must be investigated.

**When it is used.** Every time a credit sale is finalised (automatically), or whenever an AR clerk records a receipt, issues a credit note, writes off a bad debt, or loads an opening balance brought forward from a prior system.

**How it works.** Each open item carries an original amount (the full invoice value) and a current outstanding amount (reduced every time a receipt is allocated, a credit note is applied, or a write-off is made). The status (OPEN, PARTIAL, PAID, WRITTEN_OFF) is derived automatically from the outstanding balance. Receipts are posted synchronously to both the AR sub-ledger and the GL in a single operation, so the two are always in agreement.

AR tracks amounts owed to your company by customers. Open items (invoices) are created automatically when a sales invoice is finalised, or manually via the opening-balance screen.

### AR Invoices (Open Items)

**What they are.** An AR invoice (also called an **open item**) is the sub-ledger record of a specific amount a customer owes. For credit sales, open items are created automatically when the sales invoice is finalised. Opening-balance invoices can also be loaded manually to represent debts brought forward from a prior system.

**Why they exist.** The open item is the unit the AR module tracks through its lifecycle — from creation (OPEN) through partial payment (PARTIAL) to full settlement (PAID) or write-off (WRITTEN_OFF). All receipt allocations, credit notes, and write-offs reference the open item and reduce its outstanding balance. Without this per-invoice tracking, you could not determine which specific debts are unpaid, how old they are, or what the ageing exposure looks like.

**When they are used.** Created automatically on credit-sale finalisation, or manually loaded as opening balances. Viewed and managed by AR clerks and finance staff with `AR.VIEW` permission.

Navigate to **Accounting > Receivables** (`/admin/ar/invoices`).

The list shows all AR open items for the company: document number, customer name, original amount, outstanding amount, currency, invoice date, due date, and status.

**Invoice statuses:**

| Status | Meaning |
|---|---|
| OPEN | Unpaid; full outstanding balance remains |
| PARTIAL | A receipt has been applied but a balance remains |
| PAID | Fully settled |
| WRITTEN\_OFF | Outstanding amount written off as uncollectable |

**Filtering:** use the customer picker (search by name) and the status dropdown to narrow the list. The customer is selected by name — no uid is typed.

---

### Recording a Receipt

**What it is.** A receipt records money received from a customer. It consists of two parts: the **cash leg** (which GL account the money went into) and the **allocation** (which open invoice or invoices the money is applied against).

**Why it exists.** Receiving money from a customer is a separate event from issuing the invoice, and the two must be matched (allocated) to reduce the outstanding balance. A receipt that is recorded but not allocated to any invoice is held **on account** — the customer has a credit balance but no specific invoice is settled. An automatic oldest-first allocation distributes the receipt across the customer's oldest unpaid invoices first, which is standard practice.

**When it is used.** By an AR clerk when a customer makes a payment — by cash, bank transfer, mobile money, or cheque. Requires the `AR.RECEIPT.RECORD` permission. The receipt triggers a GL posting immediately (DR Cash / CR Accounts Receivable).

**How it works.** The cash leg posts to the GL in the same transaction as the sub-ledger write, so the control account and the open-item balances are always in agreement at every committed moment. Re-allocating an existing receipt between invoices (changing which invoice the money is applied to) does NOT create a new GL posting — it is a sub-ledger-only change.

Navigate to **Accounting > Record Receipt** (`/admin/ar/receipts/record`). Permission required: `AR.RECEIPT.RECORD`.

1. Pick the **customer** by name in the typeahead.
2. Enter the **receipt amount**, select the **currency**, and set the **receipt date**.
3. Choose the **tender type** (Cash, Mobile Money, Bank Transfer, Cheque, Other). For mobile or bank payments, optionally enter the bank/mobile reference.
4. The customer's open invoices load in the **allocation editor**.
   - Click **Auto oldest-first** to distribute the receipt against invoices starting from the oldest outstanding.
   - Or manually enter allocation amounts against individual invoices.
   - The editor shows the receipt total, allocated total, and unallocated balance. The Submit button is disabled if any allocation line exceeds the invoice's outstanding balance.
5. Optionally add a **WHT** amount (see WHT section below).
6. Click **Submit**. The receipt is recorded and the allocated invoices update their outstanding balances.

**Receipt statuses:**

| Status | Meaning |
|---|---|
| UNALLOCATED | No invoices have been allocated |
| PARTIAL | Part of the receipt amount is allocated; a balance remains |
| ALLOCATED | The full receipt amount has been applied |

**WHT on receipt:** if your company withholds tax from customer receipts, select the WHT type (kind = `WHT_ON_RECEIPT`) and enter the WHT amount. The GL posts: Cash DR (amount minus WHT), WHT Receivable DR (WHT amount), AR Control CR (full amount).

**Viewing receipts:** Navigate to **Accounting > Receipts** (`/admin/ar/receipts`). The list is paged and can be filtered by customer. Click any row to open the receipt detail, which shows the header and allocation lines.

---

### Credit Notes

**What it is.** A credit note is a document that reduces the amount a customer owes. It is issued when goods are returned, when a billing error has been made, or when a discount is agreed after the fact.

**Why it exists.** Mistakes happen — an invoice may have been overcharged, or goods may be returned after the invoice was raised. Deleting or editing the original invoice would break the audit trail (the ledger is append-only). A credit note is the correct mechanism: it creates a new, countervailing document that reduces the outstanding balance and posts a contra entry to the GL (reversing the relevant portion of revenue and VAT).

**When it is used.** By a user with the `AR.CREDITNOTE` permission, initiated from the invoice list when an overcharge or return is identified.

**How it works.** A credit note reduces the invoice's outstanding balance by the credited amount. The GL posts a reversal of the original revenue and VAT components (DR Sales Revenue, DR VAT Payable, CR Accounts Receivable). The invoice status updates automatically (OPEN, PARTIAL, or PAID depending on the remaining balance).

A credit note reduces a customer's outstanding balance. It is raised from the invoices list. Permission required: `AR.CREDITNOTE`.

1. On **Accounting > Receivables**, find the target invoice row.
2. Click **Credit note** (visible only when `AR.CREDITNOTE` is held).
3. In the modal, enter the net amount, VAT amount, and reason.
4. Submit. The invoice outstanding is reduced and a GL contra posting is made.

---

### Write-Offs

**What it is.** A write-off removes an uncollectable balance from AR. When a debt cannot be collected — the customer has gone bankrupt, the debt has been litigated unsuccessfully, or it is simply too old to pursue — the outstanding balance is written off to a Bad Debt Expense account.

**Why it exists.** Carrying uncollectable balances on the books overstates the company's assets (accounts receivable) and makes financial statements misleading. A write-off acknowledges the economic reality: the money is not coming and the loss should be recognised as an expense. The audit trail is preserved — the original invoice and the write-off record coexist permanently.

**When it is used.** By a user with the `AR.WRITEOFF` permission, after management has decided a specific debt is uncollectable. Should not be used as a routine alternative to chasing payments.

**How it works.** The invoice's outstanding amount is set to zero and its status becomes WRITTEN_OFF. The GL posts DR Bad Debt Expense / CR Accounts Receivable for the written-off amount. Both the open item and the write-off record are retained for audit purposes.

A write-off removes an uncollectable balance from AR. Permission required: `AR.WRITEOFF`.

1. On **Accounting > Receivables**, find the OPEN or PARTIAL invoice.
2. Click **Write off**.
3. Enter a reason and confirm the date.
4. Submit. The invoice moves to WRITTEN\_OFF status; the outstanding balance is posted to the Bad Debt Expense account.

Invoices already PAID or WRITTEN\_OFF cannot be written off again.

---

### AR Opening Balances

**What it is.** An opening balance is an AR invoice that represents a debt that existed before this system was put into use. When a company migrates from a prior accounting system, the outstanding customer balances that already exist need to be loaded so that the new system shows the correct receivables position from day one.

**Why it exists.** Without loading opening balances, the new system would show zero receivables even though customers actually owe money. Opening balances are treated as ordinary AR open items — they age, can be receipted against, and appear in customer statements — the only difference is their source is `OPENING_BALANCE` rather than `SALE`.

**When it is used.** Once, during system go-live or at the start of a new fiscal year, by a user with the `AR.OPENING.SET` permission.

**How it works.** The opening balance creates an AR invoice (source = OPENING_BALANCE) and posts a GL entry (DR Accounts Receivable / CR Opening Balance Equity) to bring the control account into agreement with the sub-ledger from the first day.

To load balances brought forward from a prior system, navigate to **Accounting > AR Opening Balance** (`/admin/ar/opening-balance`). Permission required: `AR.OPENING.SET`.

1. Pick the customer by name.
2. Enter the original amount, currency, invoice date, and an optional due date and document number.
3. Submit. An opening-balance invoice (source = `OPENING_BALANCE`) is created and posted to the AR control account.

---

### Customer Statements and Ageing

**What they are.** A **customer statement** is a snapshot of a specific customer's full AR position: their outstanding invoices, recent receipts, and ageing breakdown. **Ageing** classifies outstanding balances by how many days they are overdue, providing a practical indicator of collection risk.

**Why they exist.** AR management is not just about recording receipts — it is about proactively chasing overdue debts. The ageing report identifies which customers are overdue and by how much, allowing the AR team to prioritise collection calls. Customer statements can also be shared with customers as a formal record of what they owe and what they have paid.

**When they are used.** By AR clerks and finance managers reviewing collections. The statement can be reviewed internally or shared with a customer to resolve a dispute. Requires `AR.STATEMENT.VIEW` for the statement, `AR.VIEW` for the ageing lookup.

**How they work.** Ageing is calculated dynamically by comparing each open invoice's due date to the current date. The system places each outstanding amount in the appropriate bucket. The balance lookup shows the net AR balance for a specific customer (open invoices minus any unallocated receipt balance).

**Customer statement:** Navigate to **Accounting > Customer Statement** (`/admin/ar/statement`). Permission required: `AR.STATEMENT.VIEW`. Pick a customer by name to view total outstanding, ageing breakdown, open items, and recent receipts.

**Ageing buckets:**

| Bucket | Days Overdue |
|---|---|
| Current | 0 or not yet due |
| 1–30 | 1 to 30 days past due date |
| 31–60 | 31 to 60 days past due date |
| 61–90 | 61 to 90 days past due date |
| 90+ | More than 90 days past due date |

**Customer balance lookup:** on the **Accounting > AR Ageing** screen (`/admin/ar/ageing`), use the balance lookup section to check a specific customer's net balance (outstanding invoices minus unallocated receipts). Permission required: `AR.VIEW`.

---

## Accounts Payable

**What it is.** Accounts Payable (AP) is the module that tracks money your company owes to suppliers. When a supplier invoice is entered and matched against the purchase order and goods receipt, the system creates a **payable** in the AP sub-ledger. Payments against that payable are recorded here. The AP sub-ledger is the supplier-level detail behind the GL control account 2100 Accounts Payable.

**Why it exists.** Just as AR tracks what customers owe you, AP tracks what you owe suppliers. Without it, the company might pay the same invoice twice, miss a payment, or have no systematic way to match supplier invoices against what was ordered and received. AP also provides the first GL posting for a purchase — unlike the goods receipt (which records a stock movement only), the matched supplier bill is the point at which the purchase cost hits the books.

**When it is used.** By AP clerks when a supplier invoice arrives. The bill is entered, matched, and eventually paid. Requires AP permissions (by default, ORG\_ADMIN holds these).

**How it works.** A supplier bill goes through a lifecycle: entered as DRAFT, then matched via a 3-way match (bill vs purchase order vs goods receipt). If it matches within tolerance, it posts immediately to the GL (DR Purchases / CR Accounts Payable). If there is a variance, it is held (HELD) for a finance user to review and accept. Payments reduce the outstanding balance and post the cash leg to the GL.

AP tracks amounts your company owes to suppliers. Only users with the appropriate AP permissions can access this module. By default, only the ORG\_ADMIN role is granted AP permissions.

### Entering a Supplier Bill

**What it is.** A **supplier bill** (also called an invoice from a supplier) is the formal demand for payment that a supplier sends after goods or services have been delivered. Entering the bill in this system registers it as a payable and triggers the 3-way match.

**Why it exists.** Entering the bill and running the 3-way match is the control that prevents your company from paying for goods it did not order, did not receive, or was charged incorrectly for. The bill is the third leg of the match: purchase order (what you ordered at what price) + goods receipt (what you actually received) + supplier bill (what the supplier says you owe). Discrepancies are surfaced as variances requiring explicit approval, not silently accepted.

**When it is used.** By an AP clerk when a supplier's invoice arrives, after the goods receipt has been entered. Requires `AP.BILL.ENTER` permission.

**How it works (3-way match).** The system compares each bill line against the corresponding purchase order line (price tolerance, default 2%) and the goods receipt line (quantity). If all lines are within tolerance, the bill moves to MATCHED and the GL posts DR Purchases (or the configured purchases account) / CR Accounts Payable. If any line exceeds tolerance, the bill moves to HELD, flagging which lines have a price or quantity variance. A user with `AP.BILL.MATCH` must review and accept each variance before the bill can match and post.

Navigate to **Accounting > Enter Bill** (`/admin/ap/supplier-bills/enter`). Permission required: `AP.BILL.ENTER`.

1. Pick the **supplier** by name in the typeahead.
2. Enter the **Supplier Invoice No.**, **Bill Date**, and **Due Date**.
3. Select the **currency**. For foreign-currency bills, an FX rate for the bill date must exist.
4. Add one or more lines. For goods supplied against a Purchase Order:
   - Select the PO number and the matching PO line for each bill line.
   - Enter the billed quantity and unit cost.
5. Submit. The system runs a **3-way match** automatically:
   - If all lines are within the price and quantity tolerance (default 2%), the bill moves to **MATCHED** and a GL posting is made (DR Purchases / CR AP Control).
   - If any line exceeds tolerance, the bill is **HELD** with a price or quantity variance flag.

**Accepting a variance (requires `AP.BILL.MATCH`):**

On a HELD bill, each variance line shows the variance amount and percentage. Click **Accept variance** to approve the line. When all variance lines are accepted the bill moves to MATCHED and the GL posts.

**Service bills (no PO):** leave the PO field blank and enter free-text line descriptions.

---

### Viewing and Navigating Bills

Navigate to **Accounting > Payables** (`/admin/ap/supplier-bills`). The list shows all bills with status, outstanding amount, and source. Click a bill number to open its detail screen, which shows the header, lines, and match result.

**Bill statuses:**

| Status | Meaning |
|---|---|
| DRAFT | Entered but not yet matched |
| MATCHED | 3-way match passed; GL posted |
| HELD | Match variance requires acceptance |
| APPROVED | Explicitly approved for payment |
| PARTIALLY\_PAID | One or more payments made; balance remains |
| PAID | Fully paid |

---

### Payments

**What they are.** A payment is the settlement of a supplier bill — transferring money from the company's cash or bank account to the supplier. Payments can be made for a single bill or as a **payment run** covering multiple bills for the same supplier.

**Why they exist.** The payment closes out the payable: it reduces the outstanding balance on the bill and posts the cash leg to the GL (DR Accounts Payable / CR Cash/Bank). Without recording the payment, the AP sub-ledger would continue to show amounts owed even after the supplier has been paid, and the bank/cash accounts would not reflect the outflow.

**When they are used.** By a user with `AP.PAYMENT.RUN` permission, typically when the company's payment schedule falls due (weekly or monthly payment runs are common). A payment run is a batch operation that pays all selected outstanding bills for a supplier in one action.

**How they work.** Each payment allocates a specified amount against one or more bills, reducing each bill's outstanding balance. If the payment covers the full outstanding amount, the bill moves to PAID; otherwise it becomes PARTIALLY_PAID. The GL posts immediately in the same transaction as the sub-ledger write (DR Accounts Payable / CR the chosen cash/bank account), keeping the control account and the sub-ledger in agreement.

**Single-bill payment:** From the **Accounting > Payments** list (`/admin/ap/payments`), use the inline pay form. Permission required: `AP.PAYMENT.RUN`.

1. Select the bill to pay (by bill number).
2. Enter the payment amount (can be partial), payment date, and tender type.
3. Submit. The GL posts DR AP Control / CR Cash.

**Payment run (multiple bills):** Navigate to **Accounting > Record Payment** (`/admin/ap/payments/record`).

1. Pick the supplier by name.
2. Their payable bills (MATCHED, APPROVED, or PARTIALLY\_PAID) load as a checkbox list.
3. Select the bills to pay. Use **Select all** to pay all outstanding bills for that supplier.
4. Set the payment date and tender type.
5. Optionally add a **WHT on payment** amount (see WHT section below).
6. Submit. A payment run record (`PAYRUN-####`) is created covering all selected bills.

**WHT on payment:** select a WHT type (kind = `WHT_ON_PAYMENT`) and enter the WHT amount. The GL reduces the cash credit by the withheld amount.

---

### Debit Notes

**What it is.** A debit note is a document that reduces the amount owed to a supplier. It is issued when goods are returned to the supplier, when you were overcharged, or when a credit is agreed after the bill has been matched.

**Why it exists.** Just as a customer credit note reduces a receivable, a debit note reduces a payable — symmetrically. The supplier has charged too much or goods have been returned, so the amount owed must be reduced. The debit note is the formal, auditable record of that reduction, posting a contra entry to the GL (DR Accounts Payable / CR Purchases).

**When it is used.** By a user with `AP.DEBITNOTE` permission, when a return or billing dispute is resolved after the bill has been matched.

**How it works.** The debit note reduces the bill's outstanding amount. The GL posts DR Accounts Payable / CR Purchases for the debit note amount. If the reduction brings the outstanding to zero, the bill moves to PAID.

A debit note reduces the amount owed to a supplier. Raised from the payables list. Permission required: `AP.DEBITNOTE`.

1. On **Accounting > Payables**, find a MATCHED, APPROVED, or PARTIALLY\_PAID bill.
2. Click **Debit note**.
3. Enter the note date, net amount, optional VAT, and reason.
4. Submit. The bill outstanding is reduced and the GL posts DR AP / CR Purchases.

---

### AP Opening Balances

**What it is.** An AP opening balance is a supplier bill that represents a debt the company already owed when it started using this system — a balance brought forward from a prior system.

**Why it exists.** Without loading opening balances, the AP sub-ledger would show no amounts owed to suppliers on day one, even though real debts exist. Opening balances create proper payable records so that subsequent payments are correctly recorded against them.

**When it is used.** Once, at system go-live, by a user with `AP.OPENING.SET` permission.

Navigate to **Accounting > AP Opening Balance** (`/admin/ap/opening-balance`). Permission required: `AP.OPENING.SET`.

1. Pick the supplier by name.
2. Enter the gross amount, bill date, due date, and optional supplier invoice number.
3. Submit. An opening-balance supplier bill is created (source = `OPENING_BALANCE`).

---

### Supplier Statement

**What it is.** The supplier statement shows a specific supplier's full AP position: total outstanding, ageing breakdown, open bills, and a reconciliation between the AP sub-ledger and the GL control account.

**Why it exists.** Supplier statements serve two purposes. First, they help AP staff track what is owed to each supplier and how overdue the balances are (useful before payment runs). Second, the **reconciliation** section compares the AP sub-ledger total against the GL 2100 Accounts Payable balance — a zero difference confirms the books are in agreement; a non-zero difference is a finance-grade discrepancy that must be investigated and corrected before period close.

**When it is used.** By AP clerks and finance managers before payment runs, at month-end, or when resolving a supplier query. Requires `AP.VIEW` permission.

Navigate to **Accounting > Supplier Statement** (`/admin/ap/statement`). Permission required: `AP.VIEW`.

Pick a supplier by name to view:

- **Outstanding balance** — total of unpaid bills.
- **Ageing breakdown** — same bucket structure as AR (Current, 1–30, 31–60, 61–90, 90+).
- **Open bills** — all bills with a remaining balance.
- **Reconciliation** — compares the AP sub-ledger total against the GL AP control account. A zero difference confirms the books are in agreement. A non-zero difference is a finance-grade discrepancy requiring investigation.

---

## Cash & Bank

**What it is.** Cash & Bank is the module that manages the company's named money locations — petty cash boxes, tills, and bank accounts. Each cash/bank account is linked to a specific GL asset account, and every movement through the account (a receipt from a customer, a payment to a supplier, a bank transfer, or a direct entry for interest/charges) is recorded here and posted to the linked GL account in the same operation.

**Why it exists.** Without a dedicated cash/bank module, the company has no structured way to track the balance of individual accounts, match book records against a bank statement, or manage cheques. The key acceptance criterion is the **reconciliation invariant**: a cash/bank account's book balance must always equal its linked GL asset account balance. Because every movement posts synchronously to both the cash module and the GL, they are always in agreement at every committed moment.

**When it is used.** Every time money moves into or out of a named account: on recording a customer receipt, running a supplier payment, making a bank transfer, recording a bank charge or interest entry, or performing the monthly bank reconciliation.

---

### Cash and Bank Accounts

**What they are.** A cash or bank account in this system represents a physical money location (a till, a petty cash box, or a bank account). Each account is linked one-to-one with a GL asset account, so the module balance and the GL balance always track together.

**Why they exist.** Different money locations need to be tracked separately — the head-office petty cash has a different balance from the main bank account, and the company needs to know the balance of each location independently. Linking each account to its own GL asset account (rather than all sharing a single `CASH` mapping) means the books are accurate at the location level, not just in aggregate.

**When they are used.** Created by a user with `CASH.ACCOUNT.MANAGE` permission during system setup or when a new physical account is opened. The default account is used as the cash leg when no specific account is selected on a payment or receipt.

Navigate to **Accounting > Cash & Bank Accounts** (`/admin/cash/accounts`). Permission required: `CASH.VIEW` to view; `CASH.ACCOUNT.MANAGE` to create or set the default.

The list shows all cash and bank accounts for the company: code, name, type (CASH or BANK), linked GL account, currency, default flag, and active status.

**To create an account (requires `CASH.ACCOUNT.MANAGE`):**

1. Click **New account**.
2. Enter the account name and select the account type.
   - For **BANK** accounts, also enter the bank name (required), bank account number, and branch.
3. Select the linked **GL Asset account** from the picker (only ASSET-type accounts are listed).
4. Optionally tick **Set as default account**.
5. Save. The account code is generated automatically.

**To set the default account:** click **Set default** on any non-default row.

---

### Cash Transfers

**What it is.** A cash transfer moves funds from one cash or bank account to another within the company — for example, from the main bank account to the petty cash box, or between two bank accounts.

**Why it exists.** Physically moving cash between accounts needs to be recorded so that the book balances of both accounts update correctly and the GL reflects both the outflow from one account and the inflow to the other. Without recording the transfer, one account would show a higher balance than it actually has and the other would show a lower balance.

**When it is used.** By a user with `CASH.TRANSFER` permission, whenever funds are moved between two accounts. Common at month-end replenishment of petty cash or when consolidating bank account balances.

**How it works.** A transfer records one movement OUT of the source account and one movement IN to the destination account, and posts a single balanced GL journal (DR destination account's GL asset / CR source account's GL asset). The transfer is given a unique reference number (`CBT-####`).

To move funds between two accounts, navigate to **Accounting > Cash Transfer** (`/admin/cash/transfers/record`). Permission required: `CASH.TRANSFER`.

1. Select the **Source account** and **Destination account** from the pickers (by code — name). Source and destination must differ.
2. Enter the **amount**, **transfer date**, and an optional **reference**.
3. Submit. A transfer number (`CBT-####`) is generated. The GL posts a balanced entry covering the two accounts.

View the transfers list at **Accounting > Transfers** (`/admin/cash/transfers`). Click a row to see the transfer detail.

---

### Direct Cash/Bank Entries

**What they are.** A direct entry records a transaction that moves money into or out of a cash or bank account but does not originate from an AR receipt, AP payment, or inter-account transfer. The most common examples are bank interest credited by the bank, bank charges debited by the bank, and direct income receipts that bypass the AR module.

**Why they exist.** Not every cash movement is driven by a sales invoice or supplier bill. Bank charges, interest, returned cheque fees, and similar items are imposed by the bank and need to be recorded directly. Without direct entries, these amounts would never appear in the books and the cash account statement would not reconcile to the bank statement.

**When they are used.** By a user with `CASH.ENTRY.RECORD` permission, when a bank statement item cannot be matched to an AR receipt or AP payment.

**How they work.** The entry records the direction (IN or OUT), the amount, and a counter GL account (the other side of the double entry — typically an income, expense, or equity account). The GL is posted in the same transaction, so the cash module balance and the linked GL account balance stay in agreement.

For transactions that do not originate from AP, AR, or a transfer (e.g. bank interest, bank charges), navigate to **Accounting > Cash / Bank Entry** (`/admin/cash/entries/record`). Permission required: `CASH.ENTRY.RECORD`.

1. Select the **Cash/Bank account** by name.
2. Choose the **direction** (IN for money received by the account, OUT for money leaving the account).
3. Enter the **amount** and **transaction date**.
4. Select a **Counter GL account** from the picker. The picker lists INCOME, EXPENSE, and EQUITY accounts.
5. Enter an optional **memo**.
6. Submit.

Direct entries appear in the account statement but are not shown in a separate list screen.

---

### Bank Reconciliation

**What it is.** Bank reconciliation is the process of comparing the company's book records for a bank account against the bank's own statement. The goal is to confirm that every transaction in the books matches a transaction on the bank statement, and that the closing balance agrees.

**Why it exists.** The bank's records and the company's records are maintained independently and can differ for legitimate reasons (outstanding cheques not yet presented, deposits in transit, timing differences) or for error reasons (a transaction recorded in the books but not on the bank statement, or vice versa). Reconciliation surfaces those differences. Completing a reconciliation with a zero difference is a strong control that reduces the risk of fraud and ensures the bank balance on the balance sheet is accurate.

**When it is used.** Monthly, by a user with `CASH.RECONCILE` permission, after the bank statement for the period is received. Only BANK-type accounts can be reconciled (CASH-type accounts do not have a bank statement to match against).

**How it works.** The reconciliation opens with the account's uncleared book transactions. You mark each transaction as cleared when it appears on the bank statement. The system tracks the cleared book balance and computes the difference against the statement closing balance. When all matched transactions are ticked and the difference reaches zero, the reconciliation can be completed. A completed reconciliation is locked and cannot be modified.

Bank reconciliation matches your book records against your bank statement. Navigate to **Accounting > Bank Reconciliation** (`/admin/cash/reconciliations`). Permission required: `CASH.RECONCILE`.

**Opening a reconciliation:**

1. Select a **BANK** account (only bank accounts can be reconciled).
2. The account's uncleared transactions load.
3. Click **Open reconciliation**.
4. Enter the **Statement Date** and the **Statement Closing Balance** from your bank statement.
5. Submit. A reconciliation is opened in **DRAFT** status.

**Marking transactions cleared:**

1. Tick the **Cleared** checkbox against each transaction that appears on the bank statement.
2. The **Cleared book balance** and **Difference** update in real time.
   - Difference = cleared book balance − statement closing balance.

**Completing the reconciliation:**

1. When the difference is exactly zero, the **Complete** button becomes active.
2. Click **Complete**. The reconciliation moves to **COMPLETED** status and is locked.

A completed reconciliation cannot be modified. The difference must be zero to complete — a non-zero difference means there are unidentified items on either side.

---

### Cheque Register

**What it is.** The cheque register tracks cheques that have been written and issued against a bank account. Each cheque goes through a simple lifecycle: ISSUED (written and handed out), CLEARED (presented to the bank and cleared), or CANCELLED (voided or stopped).

**Why it exists.** Issued cheques that have not yet cleared the bank are "outstanding cheques" — they are not on the bank statement yet but are a real liability. Tracking them in the register means they can be identified during bank reconciliation as legitimate outstanding items rather than unexplained differences. Cancelled cheques provide an audit trail of voided instruments.

**When it is used.** By a user with `CHEQUE.MANAGE` permission whenever a cheque is written. Status is updated when the cheque clears or is cancelled.

Track issued cheques at **Accounting > Cheques** (`/admin/cash/cheques`). Permission required: `CHEQUE.MANAGE`.

**Registering a cheque:**

1. Click **Register cheque**.
2. Select the **BANK account** (only bank accounts issue cheques).
3. Enter the cheque number, payee, amount, issue date, and value date.
4. Submit. The cheque is recorded with status **ISSUED**.

**Cheque lifecycle:**

- ISSUED — cheque has been written and handed out.
- Click **Clear** when the cheque has been presented and cleared the bank → status becomes **CLEARED**.
- Click **Cancel** if the cheque is lost, stopped, or voided → status becomes **CANCELLED**.

CLEARED and CANCELLED are terminal states; no further transitions are possible.

---

### Cash Account Statement

**What it is.** The cash account statement shows the transaction history of a cash or bank account with a running balance. It also shows a GL reconciliation — a comparison between the account's book balance and the linked GL asset account balance.

**Why it exists.** A running statement lets treasury staff see the account's full activity in date order, trace individual transactions, and confirm that the cash module and the GL are in agreement. A non-zero GL reconciliation difference is a posting anomaly requiring investigation.

**When it is used.** By finance staff with `CASH.VIEW` permission, during daily cash management or at month-end review.

Navigate to **Accounting > Cash Statement** (`/admin/cash/statement`). Permission required: `CASH.VIEW`.

Select an account by name to view:

- **Current balance** — the running book balance.
- **Transaction history** — each cash transaction in date order with a running balance column (IN transactions increase the balance; OUT transactions decrease it).
- **GL reconciliation** — compares the account's book balance against the linked GL asset account balance. A zero difference confirms agreement. A non-zero difference requires investigation.

---

## Tax

**What it is.** The Tax module covers two statutory obligations: the monthly **VAT return** (filed with TRA) and the **WHT (Withholding Tax) register**. Both work from the same underlying transaction data — sales invoices for output VAT, supplier bills for input VAT, and AR/AP payment legs for WHT — but they are separate filings with separate regulatory purposes.

**Why it exists.** Tanzania (and most countries) requires businesses to collect VAT on sales (output VAT), claim VAT paid on qualifying purchases (input VAT), and remit the net difference to the revenue authority monthly. Without a VAT return module, the company would need to aggregate these figures manually from the GL each month, increasing the risk of error and late filing. The WHT register similarly provides the structured record needed for regulatory compliance.

---

### VAT Returns

**What it is.** A VAT return is a monthly declaration to TRA of your company's output VAT (collected from customers on sales), input VAT (paid to suppliers on qualifying purchases), and the net amount due (output minus input). A positive net is remitted to TRA; a negative net (input exceeds output) is carried forward as a credit.

**Why it exists.** VAT is a pass-through tax: the company collects it from customers on behalf of TRA and may recover it from TRA on qualifying business purchases. Without a monthly return, the company has no formal mechanism to net these obligations, report them to TRA, or settle the balance. The return module automates the computation from the system's existing sales and purchase records, produces the net figure, handles manual adjustments for exceptional items, and locks the return once filed to create an auditable record.

**When it is used.** By a user with `VAT.RETURN.PREPARE` permission each month, after all sales invoices and supplier bills for the period have been entered. The return must be filed with `VAT.RETURN.FILE` permission once ready.

**How it works.** Output VAT (on account 2200 VAT Payable) accumulates continuously as sales are finalised; input VAT (on the VAT_INPUT control account) accumulates as supplier bills are matched. The return reads the period's movements on both control accounts and computes the net. Any prior-period credit is carried forward from the last FILED return. Manual adjustments can be added for items like credit note VAT or bad debt relief. Filing locks the return (FILED), posts a settlement journal to clear both control accounts to a dedicated VAT_DUE liability, and records the TRA filing reference.

Navigate to **Accounting > Tax > VAT Returns** (`/admin/tax/vat-returns`). Permission required: `VAT.VIEW`.

The list shows all VAT returns for the company with their period, status, and key amounts.

**VAT return statuses:**

| Status | Meaning |
|---|---|
| DRAFT | Being prepared; editable |
| FILED | Submitted to TRA; locked |

**Opening a new VAT return (requires `VAT.RETURN.PREPARE`):**

1. Click **New VAT Return**.
2. Select the **year** and **month**.
3. Submit. The system creates a DRAFT return for the period and performs an initial computation. The opening credit (if any) is carried forward from the most recent FILED prior-period return.

**VAT return detail:** click a return row to open its detail. The detail screen shows:

- **Output VAT** — VAT collected on sales, broken down by tax band (Standard 18%, Zero-rated, Exempt).
- **Input VAT** — VAT paid on purchases.
- **Manual Adjustments** — optional signed adjustment lines (see below).
- **Opening Credit b/f** — carry-forward from the prior FILED return.
- **Net VAT** — output VAT − input VAT + adjustments − opening credit.
- The net label shows **"Payable to TRA"** (net > 0), **"Credit carried forward"** (net < 0), or **"Nil"** (net = 0).

**Recomputing a DRAFT return (requires `VAT.RETURN.PREPARE`):**

Click **Recompute** on the detail screen to re-read the current sales and purchase figures. This is useful after new invoices or bills have been entered for the period.

---

### VAT Adjustments

**What they are.** A VAT adjustment is a signed correction line added to a DRAFT VAT return to account for items that do not flow through the standard sales or purchase figures — for example, VAT relief on a bad debt that has been written off, VAT corrections for prior-period errors, or the VAT component of a credit note issued after the relevant period was filed.

**Why they exist.** Not every VAT correction can be handled by recomputing the sales and purchase figures. TRA rules allow for specific adjustment types (bad debt relief, prior-period corrections, credit/debit note VAT) to be reflected in the return as signed adjustment lines, each with an identifiable reason and narrative.

**When they are used.** By a user with `VAT.ADJUST` permission, on a DRAFT return, when a specific regulatory adjustment is identified before filing.

Adjustments can be added to a DRAFT return to correct prior-period errors or reflect credit/debit note VAT amounts. Permission required: `VAT.ADJUST`.

**To add an adjustment:**

1. On the DRAFT return detail, click **Add Adjustment**.
2. Choose the **Reason**:
   - Bad Debt Relief
   - Prior Period Correction
   - Credit Note VAT
   - Debit Note VAT
   - Other
3. Choose the **Effect** (Increase VAT or Decrease VAT).
4. Enter a positive **Amount** and an optional narrative.
5. Submit. The net VAT recalculates immediately.

To remove an adjustment, click the remove icon on the adjustment row. Adjustments cannot be added or removed from a FILED return.

---

### Filing a VAT Return

**What it is.** Filing is the act of submitting the prepared VAT return to TRA and locking it in the system. Filing is irreversible — a filed return cannot be edited.

**Why it exists.** Filing separates preparation (a workflow step, editable) from submission (a regulatory commitment, locked). Once filed, the return is a permanent record with a TRA reference number. The GL settlement journal it posts clears the VAT control accounts, so the new period starts with only that period's VAT movements on the accounts.

**When it is used.** By a user with `VAT.RETURN.FILE` permission, after the return has been reviewed, any adjustments added, and the amount payable confirmed. All prior-period returns must be FILED before the current one can be filed.

**How it works.** Filing runs a final recompute, posts the settlement journal (DR VAT_PAYABLE output amount / CR VAT_INPUT input amount / net to VAT_DUE), records the TRA filing reference and date, and sets the return status to FILED.

Filing locks the return and posts the settlement journal to the GL. Permission required: `VAT.RETURN.FILE`.

**Requirements:**

- The return must be in DRAFT status.
- All prior-period returns for the same company must be FILED (you cannot file period N while period N-1 is still DRAFT).
- The GL config keys `VAT_PAYABLE`, `VAT_INPUT`, and `VAT_DUE` must be mapped to active accounts.

**Steps:**

1. On the DRAFT return detail, click **File Return**.
2. Enter the **TRA Filing Reference** and **Filing Date**.
3. Click **Confirm File**. The system:
   - Runs a final recompute.
   - Posts a settlement journal (DR VAT\_PAYABLE output amount / CR VAT\_INPUT input amount / balancing leg to VAT\_DUE).
   - Sets the return to FILED and records the filing date and reference.
   - Shows a link to the posted journal.

A nil-activity return (output and input both zero) files and locks without posting a journal.

---

### WHT Types and Register

**What WHT is.** Withholding Tax (WHT) is tax that one party deducts from a payment before remitting the balance to the other party. It works in two directions. When your company **pays a supplier**, it may be required by TRA regulations to withhold a percentage of the payment, remit that withheld amount to TRA, and issue the supplier a WHT certificate (`WHT_ON_PAYMENT`). When a customer **pays your company**, the customer may withhold tax from the receipt; you receive less than the invoice amount and are issued a WHT certificate in return — a tax credit you can use against your own tax liability (`WHT_ON_RECEIPT`).

**Why WHT types exist.** Different categories of payment attract different WHT rates under Tanzanian tax law (professional fees, rent, interest, etc.). WHT types let you configure the rate for each category once and select the appropriate type on each payment or receipt, ensuring the correct amount is withheld and the correct GL accounts are used.

**When they are used.** WHT types are maintained by a user with `WHT.MANAGE` permission during initial setup or when a new rate category is needed. WHT is applied optionally on individual AP payments and AR receipts by selecting a WHT type and amount during recording.

**What the register shows.** The WHT register is the period summary of all WHT certificates — how much was withheld on supplier payments (payable to TRA) and how much was withheld by customers from your receipts (a receivable credit against your tax bill). It is the data source for preparing the WHT remittance to TRA.

**WHT Types:** Navigate to **Accounting > Tax > WHT Types** (`/admin/tax/wht-types`). Permission required: `WHT.VIEW` to view; `WHT.MANAGE` to create, edit, and deactivate.

WHT types define the rates at which tax is withheld. Each type has:

- A unique code and name.
- A kind: **WHT\_ON\_PAYMENT** (withheld when paying a supplier) or **WHT\_ON\_RECEIPT** (withheld by the customer from your receipt).
- A rate percentage.

To create a WHT type:

1. Click **New WHT Type**.
2. Enter the code, name, kind, and rate percentage (0 or greater).
3. Save.

The kind is fixed at creation and cannot be changed. To deactivate a type, click **Deactivate** on its row. An inactive type is excluded from the pickers in the AP payment and AR receipt screens.

**WHT Register:** Navigate to **Accounting > Tax > WHT Register** (`/admin/tax/wht-register`). Permission required: `WHT.VIEW`.

The register shows all WHT certificates in a period, grouped into two sections:

- **WHT Payable to TRA** — certificates from supplier payments (`WHT_ON_PAYMENT`).
- **WHT Receivable** — certificates from customer receipts (`WHT_ON_RECEIPT`).

Select the period by choosing **Month** mode (year + month) or **Range** mode (start and end dates), then click **Load**.

---

## Foreign Exchange (FX)

**What it is.** The FX module enables your company to issue sales invoices, enter supplier bills, and record receipts and payments in foreign currencies (USD, EUR, KES, GBP) while keeping the GL in the company's **base currency** (TZS). Every foreign-currency document is converted to TZS at the effective exchange rate on the document date; the GL always carries TZS amounts only.

**Why it exists.** Many businesses transact in foreign currencies — exporting in USD, importing from Europe in EUR — but keep statutory accounts in TZS. Without the FX module, the company would have to manually convert every foreign transaction before posting, with no systematic rate history, no automatic recognition of exchange gains and losses, and no way to revalue open foreign balances at period-end. FX makes multi-currency transacting systematic and auditable while preserving the integrity of the base-currency ledger.

**When it is used.** Any time a sales invoice, supplier bill, receipt, or payment is denominated in a currency other than TZS. The FX module must be configured first (exchange rates entered) before any foreign-currency document can be posted.

**Key concepts:**

- **Base currency.** The currency in which the company keeps its books — TZS for all companies in this system. All GL postings are in TZS regardless of the document currency.
- **Exchange rate.** The conversion rate between a foreign currency and TZS, expressed as "1 unit of foreign currency = X TZS" (e.g. 1 USD = 2,500 TZS). Rates are effective-dated: the system uses the most recent SPOT rate on or before the document date.
- **Realized gain/loss.** When a foreign-currency invoice is settled (received or paid), the TZS equivalent at the settlement rate may differ from the TZS equivalent when the invoice was raised. That difference is a **realized FX gain or loss** — it crystallises at the point of settlement and is posted to the books automatically (no manual action).
- **Unrealized gain/loss.** Open foreign-currency balances (unpaid invoices, unsettled bills) gain or lose TZS value as exchange rates move. At period-end, these open balances are **revalued** to the current spot rate. The resulting unrealized gain or loss is posted as a provisional GL entry and reversed at the start of the next period (because it is provisional — it only becomes realized when the invoice is actually settled).

---

### Maintaining Currencies and Rates

**What it is.** The exchange rate master is a per-company, effective-dated list of rates between each foreign currency and TZS. Rates are entered manually and are append-only — a correction is a new rate row with the correct value, not an edit of the existing row.

**Why it exists.** Without an accurate, dated rate history, the system cannot convert foreign documents at the right rate, cannot compute realized FX on settlement, and cannot revalue open balances at period-end. The effective-dating ensures that a document dated in the past uses the rate that was in effect on that date, not today's rate.

**When it is used.** By a user with `CURRENCY.MANAGE` permission whenever an exchange rate needs to be entered or updated — typically daily or at the start of each period.

Navigate to **Accounting > FX > Exchange Rates** (`/admin/fx/rates`). Permission required: `CURRENCY.VIEW` to view; `CURRENCY.MANAGE` to add rates.

The available currencies (TZS, USD, EUR, KES, GBP) are seeded at system setup. The rate list shows all effective-dated exchange rates for the company, newest first.

**To add a new rate (requires `CURRENCY.MANAGE`):**

1. Click **New rate**.
2. Select the **From currency** (the foreign currency) and **To currency** (must equal the company base currency, TZS).
3. Enter the **rate** (expressed as: 1 unit of foreign currency = X units of TZS), the **effective date**, and optionally the rate type (defaults to SPOT).
4. Submit. The rate is effective from that date for documents and revaluations.

> Rate entry is append-only — there is no edit-in-place. To correct a rate, add a new row with the corrected value and the correct effective date. If a rate for the same currency, date, and type already exists, the entry is rejected.

The system uses the most recent SPOT rate on or before the document date when converting foreign-currency documents to base TZS.

---

### Foreign-Currency Documents

**What they are.** A foreign-currency document is any sales invoice, supplier bill, receipt, or payment that is denominated in a currency other than TZS.

**Why conversion happens at the document boundary.** The GL is strictly base-currency-only. Every journal line must carry TZS amounts. The conversion from foreign currency to TZS therefore happens at the moment of posting — inside the AR/AP/sales services, before the journal lines are built — not inside the GL engine itself. This design means the GL's double-entry integrity rules (debits = credits in TZS) are never weakened or complicated by multi-currency concerns.

**How it works.** When you enter a sales invoice, supplier bill, or receipt in a foreign currency, the system converts all GL postings to TZS using the effective SPOT rate for the document date. The document stores the face amounts in the foreign currency; all GL ledger entries are in TZS. The conversion rate and the TZS base amount are captured on the document at the point of creation and are immutable — they will not change even if new rates are added later.

When you enter a sales invoice, supplier bill, or receipt in a foreign currency (e.g. USD), the system automatically converts all GL postings to the company base currency (TZS) using the effective SPOT rate for the document date. The document stores the face amounts in the foreign currency; all GL ledger entries are in TZS.

If no rate exists for the document's currency on or before the document date, the posting is rejected with a rate-not-found error.

---

### Period-End Revaluation Run

**What it is.** A revaluation run is a period-end operation that adjusts the TZS value of open foreign-currency balances (unpaid AR invoices and unsettled AP bills) to the current spot rate. The adjustment is posted as an unrealized FX gain or loss, and a corresponding reversal is automatically scheduled for the first day of the next period.

**Why it exists.** If a USD invoice was raised when 1 USD = 2,500 TZS and the rate is now 2,600 TZS at period-end, the receivable on the books (2,500 TZS) is understated — the company could receive 2,600 TZS if paid today. The revaluation corrects the book value to 2,600 TZS and recognises the 100 TZS unrealized gain. This is an **accounting standards requirement** (IFRS/IAS 21): period-end statements must reflect current exchange rates on foreign balances. The gain is labelled "unrealized" because the invoice has not yet been paid — it reverses at the start of the next period so the actual settlement computes the real (realized) gain or loss against the original invoice rate, with no double-counting.

**When it is used.** At period-end, by a user with `FX.REVALUE` permission, after all foreign-currency invoices and bills for the period have been entered and before the period is closed. The run is idempotent — running it twice for the same period produces one run (the second attempt is rejected as already completed).

**How it works.** A preview (dry run) shows you the would-be adjustments without posting anything. Once you confirm, the system posts a single balanced GL journal (DR/CR the relevant control account / CR/DR the UNREALIZED_FX_GAIN or UNREALIZED_FX_LOSS account). If the next fiscal period is already open, the reversal is posted immediately; otherwise the system records the intent and posts the reversal when that period is opened.

At period end, open foreign-currency balances (AR invoices and AP bills not yet settled) must be revalued at the current spot rate. Navigate to **Accounting > FX > Revaluation Runs** (`/admin/fx/revaluation-runs`). Permission required: `FX.EXPOSURE.VIEW` to view runs; `FX.REVALUE` to preview and post.

**Running a preview (dry run):**

1. Select the company and click **Preview**.
2. Choose the **fiscal period** from the picker.
3. Optionally enter a **spot rate date** (defaults to the period end date).
4. Click **Run preview**. The system shows each open foreign item with its carrying base amount, revalued base amount, and adjustment (gain or loss). No GL is posted.

**Posting the revaluation:**

1. After reviewing the preview, click **Post**.
2. Enter the **posting date** and confirm.
3. A revaluation run record is created and a balanced unrealized FX journal posts to the GL.
4. If the next fiscal period is already open, the system automatically schedules and posts a reversal on the first day of the next period.
5. If the next period is not yet open, the run status is **POSTED** and the reversal can be triggered manually later (see below).

**Manually reversing a run:**

On a POSTED run in the runs list, click **Reverse**, enter the reversal date, and confirm. The reversal journal posts and the run status moves to **REVERSED**.

**Realized FX gains and losses** are posted automatically when a foreign-currency invoice is settled. The difference between the original invoice rate and the settlement rate is posted to the `REALIZED_FX_GAIN` or `REALIZED_FX_LOSS` accounts configured in GL config. No manual action is needed.

---

# CRM — Customer Relationship Management

The CRM module helps your sales team track every potential customer from first contact through to a closed sale. It is organised around three concepts:

- **Leads** — an initial expression of interest, before you know whether the person will become a customer.
- **Opportunities** — a qualified, qualified sales chance with an estimated value and a pipeline stage.
- **Activities** — any interaction logged against a lead or opportunity (calls, emails, meetings, notes, and tasks).

The CRM section also provides a **Pipeline Dashboard** showing deal value across stages, a **Forecast** for a chosen date range, and **Pipeline Stages** settings where an administrator can customise the stage list.

**Why CRM exists.** Without a systematic way to track potential sales, deals fall through the cracks: a promising contact made at a trade fair is forgotten, a follow-up call that was never made costs the company a contract, and the sales manager has no visibility of what the team is working on or what revenue to expect next quarter. CRM gives every prospect a permanent record, every interaction a logged entry, and every deal a position in the pipeline — so nothing is lost and performance is measurable.

**What CRM does and does not do.** CRM is a pre-sales layer: it captures prospects, works them through a pipeline, and — on a win — converts the opportunity into a formal sales document (quotation or sales order) that then runs through the standard order-to-cash process. CRM itself posts **no entries to the general ledger, moves no stock, and opens no accounts-receivable balance**. All financial and inventory effects occur in the sales and finance modules once the converted document is processed there.

**Navigation:** Sidebar **CRM** group — **Leads**, **Opportunities**, **Pipeline Dashboard**, **Pipeline Stages**, **CRM Activities**.

Each item in the CRM nav group is hidden if you do not have the required permission. The sections below state the required permission for each action.

---

## Leads

Navigate to **CRM › Leads** (`/admin/crm/leads`).

**View:** `CRM.LEAD.VIEW` | **Create / edit / contact / disqualify:** `CRM.LEAD.MANAGE` | **Qualify:** `CRM.LEAD.QUALIFY`

A **lead** is an early-stage record of someone who has expressed interest in your products or services but has not yet been confirmed as a genuine sales prospect. Think of it as a person or company at the "awareness" stage: you know they exist and they are interested, but you have not yet verified that they have a real budget, decision-making authority, or a genuine need. A lead is not a customer — it is a prospect.

**Why leads exist as a separate concept from customers.** If every enquiry were immediately converted into a customer record, the customer master would fill up with unqualified contacts — tyre-kickers, wrong numbers, and dead ends — obscuring the real buyers and inflating debtor and pricing reports. Leads are kept separate so that the customer master remains a curated list of verified trading parties. Only after a lead is assessed and confirmed as a real prospect is it **qualified** and linked to a customer record.

**When a lead is created.** Any member of the sales team (with the `CRM.LEAD.MANAGE` permission) creates a lead when a new enquiry arrives — a website form submission, a referral from an existing client, a walk-in, a cold call, or a trade-show contact. The **Lead Source** field records the origin so the business can later measure which channels generate the most qualified prospects.

**How a lead works — lifecycle.** A lead starts as **New** and moves through a series of statuses as the sales team engages with it. Once a lead reaches a terminal status (Converted or Disqualified) it is locked and cannot be edited further. The lead is always scoped to the branch where it was created.

### Lead status lifecycle

A lead passes through the following statuses:

```
NEW → CONTACTED → QUALIFIED → CONVERTED
              ↓                    ↑
         DISQUALIFIED       (via opportunity)
```

| Status | Meaning |
|---|---|
| **New** | Freshly captured; no contact made yet. |
| **Contacted** | You have made initial contact. |
| **Qualified** | Linked to a customer record; ready to become an opportunity. |
| **Converted** | An opportunity was created from this lead. Terminal — no further edits. |
| **Disqualified** | Ruled out. Terminal — no further edits. |

Once a lead reaches **Converted** or **Disqualified** it is locked: you cannot edit it, contact it, qualify it, or disqualify it again.

### How to capture a lead

1. Navigate to **CRM › Leads** (`/admin/crm/leads`).
2. Click **New Lead**. An inline form appears.
3. Enter the **Display Name** (required).
4. Select the **Lead Source** from the dropdown (Website, Referral, Walk-in, Campaign, Cold Call, Existing Customer, or Other).
5. Optionally enter Company Name, Contact, Phone, Email, and Notes.
6. Click **Submit**.

The system assigns a **Lead Number** (for example, `LEAD-0001`) and sets the status to **New**. The lead is stamped with your active branch.

### How to mark a lead as contacted

1. Open the lead from the list (`/admin/crm/leads/uid/:uid`).
2. Click **Mark as Contacted** (only available when status is New).
3. The status changes to **Contacted**.

### How to qualify a lead

**Qualification** is the process of confirming that a lead represents a real sales opportunity. This step links the lead to a customer record — either an existing customer already in the system, or a newly created one — and moves the lead to **Qualified** status. You need the `CRM.LEAD.QUALIFY` permission.

Qualifying a lead links it to a customer record and moves it to **Qualified** status. You need the `CRM.LEAD.QUALIFY` permission.

1. Open a New or Contacted lead (`/admin/crm/leads/uid/:uid`).
2. Click **Qualify**.
3. Choose one of the two modes:

**Link an existing customer:**
- Select **Link existing customer**.
- Choose the customer by name from the picker. The customer must belong to the same company as the lead.
- Click **Submit**.

**Create a new customer from this lead:**
- Select **Create new customer**.
- Enter the new customer's **Name** (required) and select **Customer Kind** (Cash / Walk-in or Credit Account).
- Optionally enter Phone, Email, and Address.
- Click **Submit**. A new customer record is created automatically and the lead is linked to it.

After qualifying, the status badge changes to **Qualified** and the linked customer name is shown on the detail page.

### How to disqualify a lead

**Disqualification** is the formal rejection of a lead — the conclusion that this prospect will not become a customer, at least not from this enquiry. Recording a reason is required so the business can learn which types of leads are typically unsuitable and refine its lead-generation strategy.

1. Open any non-terminal lead (New, Contacted, or Qualified) at `/admin/crm/leads/uid/:uid`.
2. Click **Disqualify**.
3. Enter a **Reason** (required — for example, "Budget too low" or "Not the right fit").
4. Click **Submit**.

The status changes to **Disqualified**. The reason is stored and displayed on the detail page.

### Editing a lead

Open the lead detail page and change any editable fields (display name, source, contact details, notes). Click **Save**. Editing is not available once the lead is Converted or Disqualified.

### Searching leads

On the Leads list, the search box filters by name. Pagination controls appear when the list exceeds 20 rows. Use the NEXT / PREVIOUS / page-number / FIRST / LAST controls to move between pages.

---

**Example — Capture a referral lead and qualify it to a new customer:**

Sales executive Amina Msangi at Kijenge branch receives a phone call from Juma Banda, who was referred by an existing client and wants to discuss buying office furniture in bulk.

1. Navigate to **CRM › Leads** (`/admin/crm/leads`). Click **New Lead**.
2. Display Name: `Juma Banda`; Lead Source: `Referral`; Phone: `+255754001122`; Notes: `Referred by Baraka Supplies — bulk office furniture interest`.
3. Click **Submit**. System creates `LEAD-0005`, status **New**.
4. Next day, Amina calls Juma. She opens `LEAD-0005` and clicks **Mark as Contacted**. Status becomes **Contacted**.
5. After the call confirms he runs a legitimate business, Amina clicks **Qualify**. She selects **Create new customer**, enters Name: `Banda Office Solutions`, Customer Kind: `Credit Account`, Phone: `+255754001122`. Clicks **Submit**.
6. A new customer record "Banda Office Solutions" is created. Lead status flips to **Qualified**. The linked customer name appears on the detail page.
7. Amina can now create an opportunity from this lead (see Opportunities section).

---

## Opportunities

Navigate to **CRM › Opportunities** (`/admin/crm/opportunities`).

**View:** `CRM.OPPORTUNITY.VIEW` | **Create / edit / stage / win / lose:** `CRM.OPPORTUNITY.MANAGE` | **Convert to document:** `CRM.OPPORTUNITY.CONVERT`

An **opportunity** is a specific, identifiable sales deal being pursued with a known customer. Where a lead is a vague expression of interest, an opportunity is a concrete proposal: it has a named customer, an estimated monetary value, an expected close date, and a position in the sales pipeline indicating how far through the sales process the deal has progressed. An opportunity can also carry individual product lines — the specific items and quantities the customer is likely to buy.

**Why opportunities exist.** Opportunities bridge the gap between the customer master and the order-to-cash process. A sales team may have dozens of active deals at any time; without a systematic record of each one, deals lose momentum, forecasts are guesswork, and management has no way to prioritise effort. The opportunity record is where all of that is centralised: the value, the probability of winning, the stage, the history of interactions, and — at the end — the formal quotation or sales order that results from the win.

**When an opportunity is created.** A sales representative or manager creates an opportunity when a qualified lead turns into a real, pursuable deal, or directly against a known customer when a sales initiative begins. The opportunity must always be attached to a customer record (not a raw lead contact).

**How an opportunity works — lifecycle.** An opportunity starts **Open** and has two possible terminal outcomes: **Won** (the deal was closed in your favour) or **Lost** (the deal did not proceed). While Open, the opportunity moves through **pipeline stages** — configurable steps such as Qualification, Needs Analysis, Proposal, and Negotiation — each with a default win probability percentage. The stage drives the weighted pipeline forecast. Once Won, the opportunity can be **converted** to a quotation or sales order in the order-to-cash module.

### Opportunity status lifecycle

```
OPEN → WON
OPEN → LOST
```

Once an opportunity is Won or Lost it is closed. Closed opportunities cannot be edited, and lines cannot be added or removed. Conversion to a quotation or sales order is still available on a closed opportunity (with the restrictions described below).

### How to create an opportunity

1. Navigate to **CRM › Opportunities** (`/admin/crm/opportunities`).
2. Click **New Opportunity** (or navigate to **CRM › Opportunities › Create** at `/admin/crm/opportunities/create`).
3. Select the **Customer** using the picker. Type part of the customer name to search; select from the results.
4. Select the **Pipeline Stage** from the dropdown. Only active stages are offered. The stage's default win probability is applied automatically unless you override it.
5. Enter the **Title** (required).
6. Select the **Currency** (defaults to TZS).
7. Optionally enter an **Estimated Value**, **Expected Close Date**, and **Win Probability** override.
8. Optionally select a **Source Lead** using the picker — only Qualified leads appear in this list. Selecting a source lead converts that lead to **Converted** status.
9. Click **Submit**.

The opportunity is created with status **Open** and an automatically assigned number (for example, `OPP-0001`). You land on the opportunity detail page (`/admin/crm/opportunities/uid/:uid`).

### How to add lines to an opportunity

**Opportunity lines** are the individual products or services the customer is expected to buy. Adding lines serves two purposes: it gives the sales team a precise record of what the deal covers, and it pre-populates the resulting quotation or sales order when the opportunity is later converted — eliminating the need to re-enter every item.

Lines represent the products or services you expect to sell. You can add them while the opportunity is Open.

1. Open the opportunity detail page.
2. In the **Lines** section, type a product name into the search box and select the product (shown as `code — name`).
3. Select the **Unit** from the units dropdown.
4. Enter the **Quantity** (must be greater than zero).
5. Optionally enter the **Unit Price** and a **Discount %** (0–100).
6. Click **Add**.

To remove a line, click **Remove** on the row.

### How to advance the pipeline stage

**Advancing the stage** moves the opportunity forward in the sales funnel. Each stage represents a milestone in the sales process — for example, moving from "Needs Analysis" to "Proposal" means you have finished diagnosing the customer's requirements and are now ready to present a formal proposal. The stage's default win probability is suggested automatically; you can override it to reflect the specific circumstances of this deal.

1. Open the opportunity detail page (must be Open).
2. Click **Advance Stage**.
3. Select the **Target Stage** from the active-stages dropdown.
4. Optionally set a **Win Probability** to override the stage default.
5. Click **Submit**.

The stage and win probability update immediately.

### How to mark an opportunity as Won

1. Open the opportunity detail page (must be Open).
2. Click **Won**.
3. Optionally set the **Won Date** (defaults to today).
4. Click **Submit**.

Status changes to **Won**. Edit, add-line, advance-stage, win, and lose actions are no longer available. The Convert action remains available.

### How to mark an opportunity as Lost

1. Open an Open opportunity.
2. Click **Lose**.
3. Enter a **Loss Reason** (required — for example, "Lost to competitor on price").
4. Click **Submit**.

Status changes to **Lost**.

### How to convert an opportunity to a quotation or sales order

**Conversion** is the moment a CRM deal becomes a formal commercial document. When you convert an opportunity, the system calls the order-to-cash module to create a quotation or sales order, pre-populated with the opportunity's customer, currency, and all of the lines you entered. The sales team can then take the resulting document through the normal approval, delivery, and invoicing workflow without re-entering any data. Conversion is **idempotent**: clicking Convert a second time returns the document already created rather than making a duplicate.

- A **Quotation** is appropriate when the deal is still being negotiated — you are giving the customer a formal price offer but have not yet received a commitment. An Open or Won opportunity can be converted to a quotation.
- A **Sales Order** is the binding commercial commitment — the customer has agreed to buy. Only a **Won** opportunity can be converted to a sales order, because converting to an SO implies the deal is closed.

Conversion creates a Sales document (Quotation or Sales Order) pre-populated with the opportunity's customer, currency, and lines. You need the `CRM.OPPORTUNITY.CONVERT` permission.

**Requirements before converting:**
- The opportunity must have at least one line.
- To convert to a **Quotation**: opportunity must be Open or Won.
- To convert to a **Sales Order**: opportunity must be Won.

**Steps:**
1. Open the opportunity detail page.
2. Click **Convert**.
3. Select the **Target** (Quotation or Sales Order).
4. For a Quotation, optionally set a **Valid Until** date (defaults to today + 30 days).
5. Click **Convert**.

The system creates the document and shows a link to it (referenced by the document number, not a uid). Clicking the link navigates to the new Quotation or Sales Order.

Conversion is idempotent: if you click Convert a second time, the system returns the document that was already created rather than making a duplicate.

### Editing an opportunity

Open the detail page (must be Open). Change title, estimated value, expected close date, win probability, or stage. Click **Save**. Editing is blocked once the opportunity is Won or Lost.

---

**Example — Full pipeline journey: lead → opportunity through stages → won → convert to sales order:**

Sales manager Benson Kileo at Dar es Salaam branch handles a qualified lead for Banda Office Solutions (created in the lead example above).

1. Navigate to **CRM › Opportunities › Create** (`/admin/crm/opportunities/create`).
2. Customer: `Banda Office Solutions`; Pipeline Stage: `Qualification`; Title: `Bulk Office Furniture — Q3 2026`; Currency: `TZS`; Estimated Value: `4,500,000`; Expected Close Date: `2026-09-30`; Source Lead: `LEAD-0005 — Juma Banda` (auto-converts that lead to Converted).
3. Click **Submit**. Opportunity `OPP-0012` created, status **Open**.
4. Add lines to `OPP-0012`:
   - Executive Desk EXD-01, Unit: EA, Qty: 5, Unit Price: 480,000 = TZS 2,400,000.
   - Ergonomic Chair CHR-02, Unit: EA, Qty: 20, Unit Price: 105,000 = TZS 2,100,000.
5. After a needs-analysis call, Benson clicks **Advance Stage**, selects `Needs Analysis` (default probability 25%). Clicks **Submit**.
6. After sending a detailed proposal, Benson advances to `Proposal` (50%). After negotiation the stage moves to `Negotiation` (75%).
7. Juma accepts the quote. Benson opens the opportunity, clicks **Won**, sets Won Date: `2026-08-15`. Status becomes **Won**.
8. Click **Convert**, Target: `Sales Order`. System creates `SO-0034` with all lines pre-filled. Benson clicks the link to open the new Sales Order and proceeds with delivery.

---

## Pipeline Dashboard

Navigate to **CRM › Pipeline Dashboard** (`/admin/crm/pipeline`). **Permission:** `CRM.PIPELINE.VIEW`.

The **pipeline dashboard** is a management view that shows the current health of your sales funnel in real time. It answers three questions at a glance: where are your deals right now (the board), how much revenue can you expect in a given period (the forecast), and how effective is the team at closing deals (the KPIs)?

**Why the pipeline dashboard exists.** A sales manager without visibility of the pipeline is flying blind: they cannot see which stages are bottlenecks, whether the team has enough deals to meet the quarter's target, or whether the win rate has deteriorated. The dashboard distils the raw opportunity data into actionable numbers so management can intervene early, redirect effort, or adjust the forecast before it is too late.

The pipeline dashboard shows the current state of all open opportunities across your sales pipeline. It is scoped to a company and branch — select both to load the data.

### Board summary

The board shows each active pipeline stage with the count of open opportunities in that stage and their combined estimated value.

### Weighted forecast

The **weighted forecast** is a more realistic estimate of expected revenue than a simple sum of all open deal values. It multiplies each open opportunity's estimated value by its win probability (expressed as a percentage) and sums the results. For example, an opportunity worth TZS 10,000,000 at a 50% probability stage contributes TZS 5,000,000 to the weighted forecast. This gives sales managers a probability-adjusted revenue estimate that accounts for the fact that not all open deals will close.

The forecast section calculates expected revenue for a date range, weighting each opportunity's estimated value by its win probability. Set the **From** and **To** dates and click **Apply**.

### Win-rate and cycle-time KPIs

The KPI panel shows:
- **Win Rate** — the percentage of closed opportunities marked Won in the selected period.
- **Average Cycle Time** — the average number of days from opportunity creation to close.

**Win Rate** measures the sales team's effectiveness at closing deals. A low win rate may indicate that the team is pursuing too many unqualified leads, that the product-market fit is poor, or that competitors are winning on price. **Average Cycle Time** measures how long deals take to close — a rising cycle time may indicate bottlenecks in the proposal or approval process. Both KPIs are calculated for a user-selected date range so that trends over time can be observed.

Set the date range and click **Apply** to recalculate.

---

**Example — Reading the pipeline board and setting a forecast:**

Branch manager Zawadi Ngowi opens the **CRM › Pipeline Dashboard** (`/admin/crm/pipeline`), selects company `Kijenge Trading Ltd` and branch `DSM Main`. The board shows:

| Stage | Open deals | Combined value |
|---|---|---|
| Qualification | 3 | TZS 8,200,000 |
| Needs Analysis | 5 | TZS 21,500,000 |
| Proposal | 4 | TZS 18,750,000 |
| Negotiation | 2 | TZS 9,600,000 |
| Closing | 1 | TZS 4,500,000 |

Zawadi sets From: `2026-07-01`, To: `2026-09-30` and clicks **Apply** on the Forecast panel. The weighted forecast shows TZS 29,340,000 (each deal's estimated value × its win probability). The KPI panel shows Win Rate: 62% and Average Cycle Time: 34 days for deals closed in Q2 2026.

---

## Pipeline Stages (Settings)

Navigate to **CRM › Pipeline Stages** (`/admin/crm/settings/pipeline-stages`). **Permission to view the settings screen:** `CRM.STAGE.MANAGE` | **Permission to read stages via API:** `CRM.OPPORTUNITY.VIEW`

**Pipeline stages** are the named milestones in your sales process — the steps a deal must pass through between "new opportunity" and "closed sale." Stages are not universal: a software company might use stages called Discovery, Demo, Evaluation, and Negotiation, while a building-materials distributor might use Route Visit, Sample Sent, Proposal, and Closing. The system therefore makes stages **configurable per company** rather than hard-coding them.

**Why stages are configurable.** Every business has a different sales process. A fixed, one-size-fits-all set of stages would force companies to map their real process onto arbitrary labels, making the pipeline board meaningless. Configurable stages mean the board reflects the actual milestones the sales team uses, making stage-based reporting and coaching practical.

**The default stages.** When a company is first created, five stages are seeded automatically: Qualification (10% probability), Needs Analysis (25%), Proposal (50%), Negotiation (75%), and Closing (90%). These cover the most common B2B sales process and can be used immediately. They can be renamed, reordered, supplemented, or deactivated without affecting historical opportunity records.

**The default probability.** Each stage has a **default win probability** — the system's best guess at the likelihood of closing a deal that has reached this stage. This default is applied automatically when an opportunity is placed at that stage and drives the weighted forecast calculation. Sales reps can override the probability on individual opportunities to reflect the specific situation.

Pipeline stages define the steps in your sales process. Five stages are seeded per company: Qualification, Needs Analysis, Proposal, Negotiation, and Closing. You can add, rename, reorder, change probabilities, and deactivate stages.

### How to create a stage

1. Navigate to **CRM › Pipeline Stages** (`/admin/crm/settings/pipeline-stages`).
2. Click **New Stage**.
3. Enter the **Name** (must be unique within the company).
4. Enter the **Display Order** (a number; must be unique within the company).
5. Enter the **Default Probability** (0–100).
6. Click **Submit**.

### How to edit a stage

Click **Edit** on a row. Change the name, display order, default probability, or the **Active** toggle. Click **Save**.

### How to deactivate a stage

**Deactivating** a stage removes it from the stage selection dropdown when creating or advancing an opportunity, while keeping all historical opportunities that were in that stage intact. This is the correct action when a stage is no longer part of the sales process — for example, if a "Demo" stage is eliminated because demos are now handled differently. Deactivation is reversible.

Click **Deactivate** (or use the Active toggle in the edit form). The stage record is kept but marked inactive. Inactive stages:
- No longer appear in the stage selection dropdowns when creating or advancing an opportunity.
- Are rejected if you attempt to use them via the API.
- Still appear in historical records.

Deactivation is not permanent — you can reactivate a stage by editing it and switching Active back on.

### Stage validation rules

- Name must be unique within the company.
- Display order must be a positive number and unique within the company.
- Default probability must be between 0 and 100 (whole number).

---

## Activities

Navigate to **CRM › CRM Activities** (`/admin/crm/activities`) for the open-task inbox. Activities are also embedded on Lead and Opportunity detail pages.

**View activities:** `CRM.ACTIVITY.VIEW` | **Log / complete activities:** `CRM.ACTIVITY.MANAGE`

An **activity** is a logged record of an interaction with a prospect or customer in the context of a specific lead or opportunity. Activities capture the history of a deal: the calls made, the emails sent, the meetings held, and the notes taken. They are also the mechanism for assigning follow-up **tasks** — future actions that need to be completed — and for tracking whether those tasks have been done.

**Why activities exist.** A sales cycle typically involves many touchpoints over days or weeks before a deal closes. Without a structured activity log, the sales team relies on memory and personal notes — which are unreliable, invisible to the manager, and lost when a rep leaves. The activity log on each lead or opportunity gives every team member and manager a complete, timestamped record of what happened and what still needs to happen. The open-task inbox surfaces all outstanding tasks across the whole pipeline so nothing slips through.

**When activities are used.** A sales representative logs an activity immediately after each interaction — after a call, after sending an email, after a meeting. A follow-up task is created when the next action is identified — for example, "Call back on Thursday to confirm the budget." The task appears in the open-task inbox until it is completed.

**How activities work.** Every activity is attached to exactly one parent: either a lead or an opportunity — not both, and not neither. There are five activity types. Four (Call, Email, Meeting, Note) are **historical records** — they record something that happened and have no completion state. The fifth (Task) is a **forward-looking action item** with a due date; only Tasks appear in the open-task inbox and only Tasks can be completed.

An activity records an interaction or a task related to a lead or opportunity. Every activity is attached to exactly one parent: either a lead or an opportunity — not both, and not neither.

### Activity types

| Type | Has due date | Can be completed |
|---|---|---|
| Call | No | No |
| Email | No | No |
| Meeting | No | No |
| Note | No | No |
| Task | Yes (required) | Yes |

Only **Task** activities appear in the open-task inbox. Only Tasks can be completed.

### How to log an activity on a lead or opportunity

1. Open the lead (`/admin/crm/leads/uid/:uid`) or opportunity (`/admin/crm/opportunities/uid/:uid`) detail page.
2. Scroll to the **Activity** panel.
3. Click **Log Activity**.
4. Select the **Type** (Call, Email, Meeting, Note, or Task).
5. Enter the **Subject** (required).
6. Optionally enter a **Body** / notes and an **Occurred At** date.
7. If Type is **Task**, enter the **Due Date** (required for Tasks).
8. Click **Submit**.

The activity appears at the top of the panel list (latest first), and the system assigns an activity number (for example, `ACT-0001`).

### Activity panel pagination

The activity panel on a lead or opportunity detail page shows 10 activities per page. Use the paginator controls to move between pages if there are more than 10.

### How to complete a task

**Completing a task** marks it as done and removes it from the open-task inbox. This is the formal acknowledgement that the action was taken — for example, that the follow-up call was made. You cannot undo a completion once recorded.

A task can be completed from the open-task inbox or from the activity panel on the parent lead or opportunity.

1. Find the task (either on the detail page or in **CRM › CRM Activities** at `/admin/crm/activities`).
2. Click **Complete** on the task row.

The task is marked done and disappears from the open-task inbox. You cannot complete an activity that is not a Task, and you cannot complete a Task that is already done.

### Open-task inbox

Navigate to **CRM › CRM Activities** (`/admin/crm/activities`). **Permission:** `CRM.ACTIVITY.VIEW` (view) / `CRM.ACTIVITY.MANAGE` (complete).

The **open-task inbox** is a unified list of all incomplete tasks across every lead and opportunity in the company — a personal and team-wide to-do list for the sales pipeline. It allows a sales manager to see at a glance what follow-up actions are pending, and allows each rep to check what they need to do today without opening every individual lead or opportunity record.

The CRM Activities screen lists all open (not-yet-done) Tasks for the selected company, across all leads and opportunities. It is scoped to the company you select; you can optionally filter by assignee.

The list is paginated (20 per page). Use the paginator controls to browse. When you complete a task, it is removed from the inbox and the list refreshes.

---

**Example — Log activities across the sales journey and manage the task inbox:**

Sales rep Farida Hassan is managing opportunity `OPP-0012` (Banda Office Solutions). She logs activities at each step.

1. After the initial qualification call, she opens `OPP-0012` at `/admin/crm/opportunities/uid/:uid`, scrolls to the Activity panel, clicks **Log Activity**: Type `Call`, Subject `Initial qualification call — confirmed budget TZS 4.5M`, Occurred At `2026-07-03`. Clicks **Submit**. Activity `ACT-0018` appears.
2. She sends a proposal by email: Type `Email`, Subject `Proposal email sent — 5 desks + 20 chairs`, Occurred At `2026-07-10`.
3. After the proposal, she needs a follow-up. She creates a task: Type `Task`, Subject `Follow up on proposal — confirm decision`, Due Date `2026-07-17`. Activity `ACT-0021` created.
4. On 2026-07-17, Farida opens **CRM › CRM Activities** (`/admin/crm/activities`). She sees `ACT-0021` in the open-task inbox. After a productive call, she clicks **Complete**. The task disappears from the inbox.
5. A meeting is later held: back on `OPP-0012`, Type `Meeting`, Subject `Site visit — DSM Main showroom`, Occurred At `2026-07-22`. The activity panel shows all four interactions, newest first.

---

# Reporting and Business Intelligence

**What is the Reporting and BI module?**
Reporting and Business Intelligence (BI) is the read-only analytical layer of the system. It does not create, change, or post anything — it reads what the financial and operational modules have already recorded and presents the results in standard formats that management and external stakeholders (auditors, banks, tax authorities) can read and act on. The four financial statements summarise the company's performance and position in internationally recognised forms; the account ledger lets you drill into the individual transactions behind any figure; the BI dashboard composes key indicators from across all modules into a single at-a-glance view. Because all reports are computed on demand from the live General Ledger, a report run at any moment reflects the current state of the books. Nothing is stored or posted when you run a report (ADR-0018, ADR-0037).

This chapter describes the financial statements, the GL account-ledger drill-down, and the analytics dashboard. All reports are read-only and computed on demand — nothing is stored or posted when you run a report.

---

## Financial Statement Reports

**What are financial statements, and why do companies produce them?**
Financial statements are standardised summaries of a company's financial activity and position. They are the language that businesses, investors, lenders, and regulators use to assess financial health. Every trading company is legally required to produce them at least annually. In this system they are generated directly from the General Ledger and carry a reconciliation bar that confirms the figures tie back to the underlying journal entries — so there is no separate spreadsheet to maintain and no risk of a mismatch between the books and the reports.

The four financial statements are available from the **Accounting** navigation group. Each report requires the relevant permission and a company and period selection before it can be run.

**Common controls on every statement screen:**

- **Company selector** — if your organisation has more than one company, choose which company to report on by name.
- **Period inputs** — date fields specifying the reporting period.
- **Run button** — computes and displays the statement.
- **Export buttons** (PDF, Excel, CSV) — download the statement in the chosen format. Requires the additional `REPORT.EXPORT` permission. The buttons are hidden if you do not hold that permission.
- **Comparative period** — most statements accept an optional comparative period or date to populate a second column.
- **Reconciliation indicator** — a green **"Reconciled"** bar confirms the computed figures tie back to the underlying GL movement. A red **data-integrity alarm** means the figures do not agree and the books require investigation; the report is shown but no automatic correction is made.

---

### Profit & Loss (Income Statement)

**What is the Profit and Loss statement, and what does it tell you?**
The Profit and Loss statement (also called the Income Statement) shows how much revenue the company earned and how much it spent over a period of time, arriving at a net profit or loss. Revenue is income from sales and services; Cost of Sales is the direct cost of what was sold (purchases, materials, production); Operating Expenses are the overhead costs of running the business (salaries, rent, utilities). Gross Profit is Revenue minus Cost of Sales — a measure of trading margin. Net Profit is what remains after all operating expenses. The P&L answers the question: "Did the business make money this period, and where did the money come from and go?" It is the report most used for management decisions, bank covenants, and tax assessments. The comparative column lets you benchmark the current period against a prior period (same quarter last year, for example) to spot trends.

Navigate to **Accounting › Income Statement** (`/admin/reporting/income-statement`). Permission required: `REPORT.PL.VIEW`.

1. Select the company by name.
2. Set **Period from** and **Period to** (date inputs).
3. Optionally set a **Comparative from** and **Comparative to** to add a prior-period column.
4. Click **Run**.

The statement shows:

- Revenue lines grouped under **REVENUE**.
- Cost of sales lines under **COST\_OF\_SALES**.
- Operating expense lines under **OPERATING\_EXPENSES**.
- Section subtotals.
- A footer with **Gross Profit** and **Net Profit** rows, each carrying the current period amount and — if a comparative was set — the prior-period amount.

**Drill-through to the account ledger:** any account name shown as a link in the detail rows can be clicked to open the Account Ledger pre-filtered to that account and period.

**Export:** after running the statement, click **PDF**, **Excel**, or **CSV** in the export toolbar. The downloaded file is named `income-statement_<from>_<to>.<ext>`.

---

**Example — Run a comparative P&L for two quarters and export to Excel:**

Chief accountant Rehema Mwangi needs to compare Q1 2026 performance against Q1 2025 for the board report.

1. Navigate to **Accounting › Income Statement** (`/admin/reporting/income-statement`).
2. Company: `Kijenge Trading Ltd`.
3. Period from: `2026-01-01`; Period to: `2026-03-31`.
4. Comparative from: `2025-01-01`; Comparative to: `2025-03-31`.
5. Click **Run**.

The statement loads. The green **Reconciled** bar confirms net profit ties to the INCOME − EXPENSE GL movement. Results:

| Section | Q1 2026 | Q1 2025 |
|---|---|---|
| Revenue | TZS 48,250,000 | TZS 39,100,000 |
| Cost of Sales | TZS 29,340,000 | TZS 24,600,000 |
| Gross Profit | TZS 18,910,000 | TZS 14,500,000 |
| Operating Expenses | TZS 9,720,000 | TZS 8,850,000 |
| Net Profit | TZS 9,190,000 | TZS 5,650,000 |

Rehema clicks **Excel** in the export toolbar. The file `income-statement_2026-01-01_2026-03-31.xlsx` downloads with both columns. She forwards it to the board.

To drill into "Sales Revenue", she clicks the account name link — the Account Ledger opens pre-filled with that account and the Q1 2026 period, showing every posted journal line and a running balance.

---

### Balance Sheet

**What is the Balance Sheet, and what does it tell you?**
The Balance Sheet (also called the Statement of Financial Position) shows what the company owns and what it owes at a single point in time. **Assets** are what the company owns — cash, trade receivables, stock, fixed assets, and other resources. **Liabilities** are what the company owes — supplier payables, loans, tax obligations. **Equity** is the residual interest of the owners — the difference between assets and liabilities, representing the net worth of the business. A correctly prepared balance sheet always satisfies the fundamental accounting equation: Assets = Liabilities + Equity. If this equation does not balance, something has been mis-posted. The Balance Sheet answers the question: "What is the company worth, and how solvent is it?" It is used by banks to assess creditworthiness, by investors to evaluate the business, and by management to monitor liquidity. The comparative "as at" date lets you compare financial position at two year-ends side by side.

Navigate to **Accounting › Balance Sheet** (`/admin/reporting/balance-sheet`). Permission required: `REPORT.BS.VIEW`.

1. Select the company by name.
2. Set the **As-at date**.
3. Optionally set a **Compare as-at** date to add a prior-date column.
4. Click **Run**.

The statement shows sections for Current Assets, Non-Current Assets, Current Liabilities, Non-Current Liabilities, and Equity, each with detail lines, subtotals, and three totals: **Total Assets**, **Total Liabilities**, **Total Equity**. A balanced set of books shows **Total Assets = Total Liabilities + Total Equity** (green reconciliation bar).

**Drill-through:** click any real account name link to open the Account Ledger for that account as at the selected date.

**Export:** file is named `balance-sheet_<asAt>.<ext>`.

---

**Example — Run a comparative balance sheet at year-end:**

Rehema Mwangi needs the balance sheet as at 30 June 2026 compared with 30 June 2025.

1. Navigate to **Accounting › Balance Sheet** (`/admin/reporting/balance-sheet`).
2. Company: `Kijenge Trading Ltd`; As-at date: `2026-06-30`; Compare as-at: `2025-06-30`.
3. Click **Run**.

The green Reconciled bar appears ("total assets == total liabilities + total equity"). Rehema spots that "Trade Receivables" has grown from TZS 12.4M to TZS 19.7M year-on-year. She clicks the "Trade Receivables" account name to open its ledger for the full fiscal year and reviews each transaction. She then exports to PDF for the audit file.

---

### Cash-Flow Statement

**What is the Cash-Flow Statement, and what does it tell you?**
The Cash-Flow Statement shows how cash moved into and out of the business over a period, organised into three categories. **Operating activities** are cash flows from the company's main trading activities — collecting from customers, paying suppliers, paying wages. **Investing activities** are cash flows from buying or selling long-term assets — purchasing a vehicle or machinery, receiving proceeds from selling an asset. **Financing activities** are cash flows from raising or repaying capital — new loans drawn, loan repayments, equity injections. The statement reconciles the opening and closing cash balance, confirming that the movement in the company's bank accounts is fully explained. The Cash-Flow Statement answers the question: "Where did the cash come from, and where did it go?" It is particularly important for businesses that are profitable on paper but cash-constrained in practice — a common situation when customers pay late or large capital purchases are made. The system uses the **indirect method** (starting from net profit and adjusting for non-cash items), which is the most common format for external reporting.

Navigate to **Accounting › Cash-Flow Statement** (`/admin/reporting/cash-flow`). Permission required: `REPORT.CASHFLOW.VIEW`.

1. Select the company by name.
2. Set **Period from** and **Period to**.
3. Optionally set a comparative period.
4. Click **Run**.

The indirect-method statement shows movements in three sections:

- **OPERATING** — cash generated from or used in trading activities.
- **INVESTING** — cash spent on or received from capital assets.
- **FINANCING** — cash from borrowings, equity, or repayments.

The footer shows **Opening Cash**, **Net Change in Cash**, and **Closing Cash**. A reconciled bar confirms the net change matches the change in cash-equivalent GL account balances.

**Export:** file is named `cash-flow_<from>_<to>.<ext>`.

---

**Example — Cash-flow analysis for H1 2026:**

1. Navigate to **Accounting › Cash-Flow Statement** (`/admin/reporting/cash-flow`).
2. Company: `Kijenge Trading Ltd`; Period from: `2026-01-01`; Period to: `2026-06-30`.
3. Click **Run**.

Results show Opening Cash: TZS 6,800,000; Operating inflow: TZS 11,250,000; Investing outflow: TZS −4,200,000 (purchase of delivery van); Financing outflow: TZS −1,500,000 (loan repayment); Net Change: TZS 5,550,000; Closing Cash: TZS 12,350,000. The green Reconciled bar confirms the net change ties to the actual movement in the bank account GL balances.

---

### Account-Ledger Drill-Down

**What is the Account Ledger, and when do you use it?**
The Account Ledger shows every individual journal line posted to a single GL account within a date range, with a running balance. It is the most granular view available in the system: while the financial statements show totals and subtotals, the ledger shows the individual transactions behind each total. It is the primary tool for investigating a balance — for example, if Trade Receivables on the balance sheet is higher than expected, you open the ledger for that account to see every invoice and receipt that has been posted. The ledger is also the standard tool for preparing a bank reconciliation (compare the bank account ledger to the bank statement) and for answering auditor queries about specific transactions. The opening balance is the account's position before the chosen date range, so every line in the report can be traced back to a source document.

Navigate to **Accounting › Account Ledger** (`/admin/reporting/account-ledger`). Permission required: `REPORT.LEDGER.VIEW`.

The account ledger shows every posted journal line for a single GL account within a date range, with a running balance.

1. Select the company by name.
2. In the **account picker**, start typing an account name or code and select from the suggestions. Accounts are chosen by name; no uid is typed.
3. Set **Period from** and **Period to**.
4. Click **Run**.

The report shows:

- An **opening balance** (the account's balance as at the day before the from date).
- Every journal line in date order: batch number, posting date, description, debit amount, credit amount, and running balance. Negative running balances are shown in red.
- A **closing balance** (the account's balance at the end of the to date).

**Pagination:** if the account has more than 50 lines in the period, the shared paginator appears at the bottom. Use FIRST, PREVIOUS, page numbers, NEXT, and LAST to navigate.

**Export:** the export is bounded at 10,000 rows per download. For very busy accounts spanning long periods, narrow the date range and export in segments. File is named `account-ledger_<accountCode>_<from>_<to>.<ext>`. Export requires `REPORT.EXPORT`.

---

**Example — Investigate the bank account movements for April 2026:**

1. Navigate to **Accounting › Account Ledger** (`/admin/reporting/account-ledger`).
2. Company: `Kijenge Trading Ltd`.
3. Account picker: type `Bank` — select **Bank — Main Current (1100)**.
4. Period from: `2026-04-01`; Period to: `2026-04-30`.
5. Click **Run**.

Opening balance: TZS 12,350,000. The ledger shows 28 lines — 15 customer receipts credited and 13 payments debited, with a closing balance of TZS 14,890,000. The paginator is hidden (fewer than 50 lines). Rehema exports to CSV for the bank reconciliation working paper.

---

### Trial Balance

The Trial Balance is covered fully in the Finance chapter (Accounting › Trial Balance, `/admin/gl/trial-balance`). It can also be reached from the Accounting navigation group. Permission required: `GL.VIEW`. See the General Ledger section for full usage.

---

## Business Intelligence Dashboard

**What is the BI Dashboard, and who uses it?**
The Business Intelligence Dashboard is a single-screen summary that composes key performance indicators (KPIs) from Finance, Operations, and CRM into one view. Rather than opening the income statement, then the AR list, then the stock valuation report separately, a finance director or general manager can open the dashboard and see the essential health indicators at a glance: is the trial balance balanced? Are the AR and AP sub-ledgers in agreement with the GL? How much cash is in the accounts? What is the current pipeline forecast? Each panel has a health badge (green `[OK]` / red `[!]`) that instantly signals whether the underlying sub-ledger ties to the GL control account — a critical integrity check the finance team would otherwise have to perform manually. Drill-through links let the reader navigate directly to the relevant detail screen with a single click. The dashboard is permission-gated at the panel level: a user with only operations permissions sees the stock panel but not the finance panel, and gets a calm "no permission" message for the panels they cannot access (ADR-0037).

Navigate to **Analytics › Dashboard** (`/admin/dashboard`). Permission required: `BI.VIEW`.

The dashboard is a composite view of key performance indicators drawn from Finance, Operations, and CRM data. Each panel loads independently and has its own permission. If you hold `BI.VIEW` but lack a panel-specific permission, that panel shows a calm "no permission" message rather than blocking the whole page.

**Filters at the top of the page:**

- **Company** — the active company (determined by your login context).
- **Branch** — optionally filter data to a specific branch (chosen by code — name).
- **From / To dates** — the reporting date range (defaults to the current month). Change dates and click the **Refresh** button to re-fetch all panels.

---

**Example — Read the dashboard KPIs and drill through to source screens:**

Finance director Gideon Moshi logs in, navigates to **Analytics › Dashboard** (`/admin/dashboard`). The company `Kijenge Trading Ltd` and branch `DSM Main` auto-select; dates default to the current month (2026-06-01 to 2026-06-14).

1. **Health strip** — all five badges (AR, AP, Cash, Stock, TB) show green `[OK]`. No reconciliation issues.

2. **Finance panel** — Revenue: TZS 9,850,000; OpEx: TZS 4,200,000; Net Profit: TZS 3,480,000. Trial Balance status: Balanced. Gideon clicks **Income Statement** in the Finance panel — this drills through to `/admin/reporting/income-statement` where he can run a full P&L.

3. **Cash Position panel** — Cash balance across all accounts: TZS 14,890,000. He clicks **Cash Accounts** to open the cash & bank accounts list.

4. **Working Capital panel** — Outstanding AR: TZS 19,700,000 (AR sub-ledger reconciles to GL). Outstanding AP: TZS 6,450,000. He clicks **View Receivables** to drill into the AR invoices list.

5. **Inventory panel** — Total stock value: TZS 38,250,000 (stock sub-ledger ties to GL inventory account). Clicking **Inventory** opens the stock valuation screen.

6. **CRM pipeline panel** — 15 open deals across five stages; Win Rate: 62%; Weighted Forecast for the period: TZS 29,340,000. He clicks **CRM** to open the pipeline dashboard.

7. Gideon changes the **Branch** to `Arusha Branch` and clicks **Refresh**. All panels re-fetch and show Arusha-scoped figures.

8. He selects format **Excel** in the export dropdown and clicks **Download**. File `dashboard.xlsx` downloads with the currently visible panel data. (Requires `BI.EXPORT`.)

---

### KPI Panels

**What are KPI panels?**
Each KPI panel on the dashboard is a self-contained summary of one operational or financial domain, sourced from the module that owns that data. The panels display figures that have already been computed by the underlying modules (the AR reconciliation query, the stock valuation query, the CRM pipeline query, etc.); the dashboard simply composes them into one screen. A health badge (`[OK]` or `[!]`) accompanies any panel whose data has a GL tie-out — it tells you at a glance whether the sub-ledger agrees with the General Ledger. A red badge is a prompt for the finance team to investigate before closing the period.

**Health strip** — a row of colour-coded badges (AR, AP, Cash, Stock, Trial Balance) that show whether each sub-ledger reconciles with the GL control account. A green badge with `[OK]` means the sub-ledger ties; a red badge with `[!]` means there is a discrepancy. These badges provide a quick finance-health summary.

**Finance panel (requires `BI.FINANCE.VIEW`):**

- Revenue, Operating Expenses, and Net Profit for the selected period.
- Trial Balance status — shows whether total debits equal total credits.
- Click **Income Statement** to drill into the P&L report.
- Click **Trial Balance** to open the GL trial balance.

**Cash Position panel (requires `BI.FINANCE.VIEW`):**

- Summary cash balance across cash and bank accounts.
- Click **Cash Accounts** to open the Cash & Bank accounts list.

**Working Capital panel (requires `BI.FINANCE.VIEW`):**

- Outstanding AR balance and sub-ledger/GL reconciliation status.
- Outstanding AP balance and sub-ledger/GL reconciliation status.
- Click **View Receivables** or **View Payables** to drill into AR/AP lists.

**Inventory panel (requires `BI.OPS.VIEW`):**

- Total stock value and a flag showing whether the stock sub-ledger ties to the GL inventory account.
- Click **Inventory** to open the stock valuation screen.

**CRM pipeline panel (requires `BI.CRM.VIEW`):**

- Pipeline bar chart by stage.
- Win-rate KPI and revenue forecast for the period.
- Click **CRM** to open the sales pipeline.

**Revenue trend and Net Profit trend (requires `BI.FINANCE.VIEW`):**

- Bar charts showing the last 12 periods of revenue and net profit. Each bar represents one fiscal period.

---

### Drill-Through

Each panel contains one or more links that navigate directly to the relevant detail screen. Clicking a drill link takes you to the live module (AR, AP, GL, Inventory, CRM) with your current company and branch context preserved.

The target screen has its own permission guard. If you do not hold the necessary permission for the target screen, you will be redirected to an access-denied page.

---

### Exporting the Dashboard

Requires `BI.EXPORT`. A export toolbar appears at the top of the page.

1. Choose a format from the dropdown: **PDF**, **Excel**, or **CSV**.
2. Click **Download**.

The file is named `dashboard.<ext>` and includes the currently visible panel data for the selected company, branch, and date range.

---

## Analytical Reports

The following specialised reports sit under the **Budgeting** and **Costing** navigation groups but are described here because they are reporting outputs, not data-entry screens.

### Budget Variance Report

**What is the Budget Variance Report, and why is it produced?**
A budget variance report compares what the business planned to spend (or earn) against what actually happened. A variance is the difference: if you budgeted TZS 3,200,000 for fuel but spent TZS 3,850,000, the variance is TZS 650,000 **adverse** (worse than plan). For income accounts, spending more than budgeted is **favourable** (you earned more than expected). This report is the primary tool for **management by exception** — the finance team and department heads review it monthly to identify lines that have gone significantly off-plan and investigate why. It drives conversations about cost control, re-forecasting, and budget reallocation. For the report to show non-zero budget figures, at least one budget version covering the selected fiscal year and scope must have been **approved** (see Part 2 — Budgeting, in the HR, Budgeting, and Platform chapter).

Navigate to **Budgeting › Budget Variance Report** (`/admin/budgeting/variance`). Permission required: `BUDGETING.REPORT.VIEW`.

The report compares GL actuals against an approved budget version.

1. Select the company by name.
2. Enter the **Fiscal Year UID** (available from the budget detail screen).
3. Set the **Period range** (1–12; from must be ≤ to).
4. Optionally filter by **Account Type** (Income, Expense, Asset, Liability, Equity) and enter a cost-centre value UID.
5. Click **Run**.

The report shows account-level rows with budget amount, actual amount, variance, and a **Favourable** or **Adverse** label. For income accounts, actual > budget is favourable. For expense accounts, actual < budget is favourable.

---

**Example — Run a budget variance report for the first half of the fiscal year:**

Management accountant Yasmin Juma navigates to **Budgeting › Budget Variance Report** (`/admin/budgeting/variance`).

1. Company: `Kijenge Trading Ltd`.
2. Fiscal Year UID: copied from the approved budget at **Budgeting › Budgets**.
3. Period from: `1`; Period to: `6` (January through June).
4. Account Type: `Expense` (to focus the board on cost discipline).
5. Click **Run**.

Results show that "Fuel & Transport" (actual TZS 3,850,000 vs budget TZS 3,200,000) is marked **Adverse** by TZS 650,000, while "Office Supplies" (actual TZS 480,000 vs budget TZS 600,000) is **Favourable** by TZS 120,000. Yasmin notes the fuel over-run for discussion in the monthly management meeting.

---

### Departmental Actuals Report

**What is the Departmental Actuals Report?**
The Departmental Actuals Report shows real GL postings broken down by cost centre and account, without any budget comparison. It answers the question: "How much did each department actually spend on each expense type?" It is useful when a department manager wants to understand their spending in detail, or when the finance team needs to review allocations across departments without the distraction of a budget column. Cost centres are assigned to journal entries when transactions are posted; entries posted without a cost-centre tag appear as **Unallocated**.

Navigate to **Budgeting › Departmental Actuals** (`/admin/budgeting/departmental-actuals`). Permission required: `BUDGETING.REPORT.VIEW`.

Shows actual GL postings broken down by cost centre and account for the chosen fiscal year and period range. The inputs are the same as the variance report. This report has no budget comparison — it shows actuals only, useful for analysing spending by department or cost centre.

### Dimension-Sliced Trial Balance

Navigate to **Costing › Sliced Trial Balance** (`/admin/cost-centre/report`). Requires both `COSTING.VIEW` and `GL.VIEW`.

See the General Ledger section (Cost-Centre Dimensions) in the Finance chapter for full usage instructions.

---

# HR & Payroll, Budgeting, and Platform Services

This chapter covers three cross-cutting domains: the Human Resources and Payroll module (departments, employees, contracts, leave, loans, pay components, payroll runs, and statutory setup), the Budgeting module (budget creation, version lifecycle, line entry, and management reports), and the Platform services used by all other modules (document generation, notifications, the approval engine, and the audit trail).

---

## Part 1 — Human Resources and Payroll

**What is the HR & Payroll module, and why does it exist?**
The HR & Payroll module is "Accounts Payable for staff". Just as AP manages what the business owes to suppliers, payroll manages what it owes to employees. It brings together the employee master (who works here, on what terms), the statutory framework (what the government requires to be deducted and remitted — PAYE income tax, NSSF pension, HESLB loan repayments, WCF worker-compensation fund, SDL skills-development levy), voluntary deductions (loans, savings schemes), and the periodic calculation that produces a payslip and a balanced GL journal. Without a formal payroll system, salary payments are unstructured (prone to error and duplication), statutory obligations are hard to track (creating tax and compliance risk), and the cost of labour does not appear correctly in the profit and loss account. The module uses a run lifecycle (DRAFT → CALCULATED → APPROVED → POSTED → PAID) that enforces separation of duties: the person who calculates payroll is not the same person who approves or posts it (ADR-0032).

The HR & Payroll module is accessible from the **HR & Payroll** navigation group. What appears in that group depends on your permissions.

### Permission requirements

| Screen | View permission | Manage / act permission |
|---|---|---|
| Departments | `HR.EMPLOYEE.VIEW` | `HR.EMPLOYEE.MANAGE` |
| Employees | `HR.EMPLOYEE.VIEW` | `HR.EMPLOYEE.MANAGE` |
| Employee Contracts | `HR.EMPLOYEE.VIEW` | `HR.EMPLOYEE.MANAGE` |
| Leave Requests | `HR.LEAVE.VIEW` | `HR.LEAVE.MANAGE` (submit), `HR.LEAVE.APPROVE` (decide) |
| Employee Loans | `HR.LOAN.MANAGE` | `HR.LOAN.MANAGE` |
| Pay Components | `HR.PAYCOMPONENT.MANAGE` | `HR.PAYCOMPONENT.MANAGE` |
| Payroll Runs | `HR.PAYROLL.VIEW` | `HR.PAYROLL.RUN` / `APPROVE` / `POST` / `DISBURSE` / `REVERSE` |
| Statutory Setup | `HR.STATUTORY.MANAGE` | `HR.STATUTORY.MANAGE` |

A user holding none of the HR permissions will not see the **HR & Payroll** nav group and will be blocked from accessing any HR route directly.

---

### Departments

**What is a department, and why is it needed?**
A department is a logical grouping of employees within the company — for example Finance, Operations, or Sales. Departments serve two purposes. First, they appear on payroll reports and payslips, making it easy to see the cost of each part of the business. Second, they act as a cost-centre anchor: when payroll is posted to the General Ledger, the salary expense can be tagged with a department so that management accounts show the labour cost by business unit, not just as one undifferentiated total. Departments are company-level reference data that must be set up before employees can be registered.

Navigate to **HR & Payroll > Departments** (`/admin/hr/departments`).

Departments are company-level reference data. They are assigned to employees and appear on payroll reports.

**Creating a department:**

1. Click **New department**.
2. Enter a **Code** (up to 30 characters) and a **Name** (up to 120 characters).
3. Click **Save**.

**Editing a department:** click the department row to open the edit form, change the name, and save.

**Deactivating a department:** click **Deactivate** on the department row. The record is soft-deactivated, not deleted. Active employees in that department are not affected — the department reference is retained for historical records.

---

### Employees

**What is an employee record, and what is it used for?**
An employee record is the master data entry for a person employed by the company. It holds the information needed to calculate their pay (hire date, department, job title), satisfy statutory reporting requirements (national ID, TIN, NSSF number, HESLB number), and produce payslips. The system assigns an employee number automatically (`EMP-000001` format) that is used throughout HR and payroll screens. The employee record is created when the person joins and is archived (not deleted) when they leave, so that historical payroll records remain intact. Only one status is set at creation (ACTIVE); the only change available through the UI is archiving to TERMINATED.

Navigate to **HR & Payroll > Employees** (`/admin/hr/employees`).

The list shows employee number, name, department name, and employment status. Use the paginator to navigate through large lists.

**Creating an employee (minimum required fields):**

1. Click **New employee**.
2. Enter **First name**, **Last name**, and **Hire date**.
3. Choose a **Department** by searching for its name in the department picker.
4. Optionally fill in national ID, TIN, NSSF number, HESLB number, date of birth, gender, and job title.
5. Click **Save**.

The system assigns an **employee number** automatically (format `EMP-000001`). The employee's status is set to **ACTIVE** on creation.

**Viewing and editing an employee:** click the employee row in the list to open the detail page. If you hold `HR.EMPLOYEE.MANAGE`, you can edit the employee's fields and save changes.

**Archiving an employee:** on the employee detail page, click **Archive**. This changes the status to **TERMINATED** and marks the record inactive. The employee record is retained for historical and payroll purposes. There is no way to restore an archived employee through the UI — contact your system administrator if this is needed.

**Employment status:** only **ACTIVE** (on create) and **TERMINATED** (on archive) are reachable through the HR screens. The statuses ON_LEAVE and SUSPENDED exist in the system but cannot be set from these screens.

---

### Employment Contracts

**What is an employment contract, and why are contract types important?**
An employment contract records the formal terms under which a person is employed: their type of engagement, base salary, start date, and — for fixed-term arrangements — end date. The contract type (PERMANENT, FIXED_TERM, CASUAL, or PROBATION) matters for statutory compliance: permanent and confirmed employees are typically subject to full PAYE and NSSF deductions, while casual workers may be treated differently. The statutory flags on the contract (PAYE resident, NSSF member, HESLB member) directly control which deductions are calculated during the payroll run. An employee can have at most one active contract at a time; when terms change (a salary review, a change from probation to permanent), the current contract is terminated and a new one is created — preserving the full history of contractual changes.

Navigate to **HR & Payroll > Employee Contracts** (`/admin/hr/contracts`).

The contracts screen is employee-picker driven: you choose an employee first and their contracts are listed in a panel below.

**Viewing contracts for an employee:**

1. Select the **Company** if shown.
2. In the **employee picker**, start typing the employee's name or number and select them.
3. The panel shows all contracts for that employee: contract type, base salary (TZS), start date, end date, and active status.

**Creating a contract:**

An employee may have at most one active contract at a time. Creating a second contract while one is active will be rejected.

1. With the employee selected, click **New contract**.
2. Choose the **Contract type**: PERMANENT, FIXED_TERM, CASUAL, or PROBATION.
3. Enter the **Base salary** (stored in TZS) and **Start date**.
4. For FIXED_TERM contracts, enter an **End date**.
5. Set the statutory flags: **PAYE resident**, **NSSF member**, **HESLB member**. These control which statutory deductions are applied during payroll calculation.
6. Click **Save**.

The pay frequency is fixed at MONTHLY (v1). Currency is fixed at TZS.

**Terminating a contract:** click **Terminate** on the contract row. The contract becomes inactive (`active = false`). Once the active contract is terminated, a new one can be created for the employee.

---

### Leave Requests

**What is a leave request, and how does it affect payroll?**
A leave request is the formal record of an employee's application for time off — annual leave, sick leave, maternity leave, or any other type configured by the administrator. The approval workflow (PENDING → APPROVED or REJECTED) ensures that time off is authorised before it is recorded as taken. For **unpaid leave** (where the leave type is flagged as unpaid), the approval has a direct financial consequence: approved unpaid leave days that overlap a payroll period automatically reduce the employee's basic salary pro-rata when the payroll run is calculated (the system uses 22 working days per month as the standard period). This ensures the payroll accurately reflects the actual days worked. Without a formal leave system, unpaid leave deductions would have to be applied manually, risking errors, disputes, and payroll miscalculations.

Navigate to **HR & Payroll > Leave Requests** (`/admin/hr/leave-requests`).

The list shows employee name, leave type, dates, number of days, and status. Use the paginator for large lists.

**Submitting a leave request (requires `HR.LEAVE.MANAGE`):**

Leave types are configured by the system administrator in the database. The leave type's ID is required when submitting.

1. Click **New leave request**.
2. Pick the **employee** by name.
3. Select the **leave type** from the dropdown (leave types are seeded by your administrator).
4. Enter **From date**, **To date**, and the number of **Days**.
5. Optionally enter a **Reason**.
6. Click **Submit**. The request status is set to **PENDING**.

**Deciding a leave request (requires `HR.LEAVE.APPROVE`):**

1. Open the leave request from the list (link goes to `/admin/hr/leave-requests/uid/:uid`).
2. Click **Approve** or **Reject**.
3. Enter a decision note (required for Reject, optional for Approve).
4. Confirm.

The only valid decisions are **APPROVED** or **REJECTED**. PENDING and CANCELLED are not valid decision values and will be rejected.

**Leave request statuses:**

| Status | Meaning |
|---|---|
| PENDING | Submitted, awaiting a decision |
| APPROVED | Approved; approved unpaid leave days reduce the employee's basic salary pro-rata in the payroll run |
| REJECTED | Declined |
| CANCELLED | Withdrawn before decision |

**Note on unpaid leave:** if the leave type is marked as unpaid (configured by the administrator), approved leave days that overlap a payroll period will reduce that employee's basic salary pro-rata. The system uses 22 working days per month as the standard period.

---

### Employee Loans

**What is an employee loan, and how does repayment work?**
An employee loan is a cash advance made by the company to an employee, to be repaid through regular deductions from their net pay. Examples include salary advances, housing loans, or emergency personal loans. The loan record tracks the original principal, the agreed monthly instalment, and the outstanding balance. Once the loan is approved and becomes ACTIVE, the payroll calculation engine automatically includes the instalment as a deduction in each payroll run until the balance reaches zero — at which point only the remaining balance is deducted rather than the full instalment. This prevents payroll errors caused by forgetting to stop a deduction. The GL account linked to the loan records the outstanding balance on the balance sheet as an asset (money owed to the company by the employee).

Navigate to **HR & Payroll > Employee Loans** (`/admin/hr/loans`).

The list shows employee name, loan number, principal, installment amount, outstanding balance, and status. Viewing and managing loans both require `HR.LOAN.MANAGE`.

**Creating a loan:**

1. Click **New loan**.
2. Pick the **employee** by name.
3. Enter **Principal**, **Monthly installment**, and **Start date**.
4. Pick the **GL account** by name (the loan will be posted to this account).
5. Click **Save**. The loan is created with its outstanding balance equal to the principal.

**Approving a loan:**

1. Open the loan from the list (`/admin/hr/loans/uid/:uid`).
2. Click **Approve**. The loan status changes to **ACTIVE**.

Once ACTIVE, the loan installment is automatically deducted from the employee's net pay during each payroll calculation. If the outstanding balance is less than the installment, only the remaining outstanding amount is deducted.

**Loan statuses:** ACTIVE loans are picked up by payroll. SETTLED and CANCELLED statuses exist in the system but can only be set by the system administrator — there is no Settle or Cancel button on the UI in this version.

---

### Pay Components

**What is a pay component, and why is it needed?**
A pay component is a named earning or deduction that is applied to employees during payroll calculation — for example "Housing Allowance" (an earning), "Medical Scheme Contribution" (a deduction), or "Transport Allowance" (an earning calculated as a percentage of basic salary). Pay components allow the payroll engine to handle the variety of terms in employment contracts without hard-coding allowances or deductions into the system. Each component is configured once (with its GL account, its basis — fixed amount or percentage of basic salary — and its tax/pension flags) and then assigned to specific employees as recurring items. This ensures that every employee's payslip is built from a consistent, auditable set of named items rather than ad-hoc adjustments.

Navigate to **HR & Payroll > Pay Components** (`/admin/hr/pay-components`).

Pay components define the earnings and deductions applied to employees during payroll calculation. They are company-level reference data. The list is not paginated. Viewing and managing pay components both require `HR.PAYCOMPONENT.MANAGE`.

**Creating a pay component:**

1. Click **New pay component**.
2. Enter a **Code** and a **Name**.
3. Set the **Kind**: EARNING (adds to gross) or DEDUCTION (reduces net).
4. Set the **Basis**: FIXED (a fixed amount per run) or PERCENT\_OF\_BASIC (a percentage of the employee's basic salary).
5. Check **Taxable** if this component is subject to PAYE.
6. Check **Pensionable** if this component is included in the pension-contribution base.
7. Pick the **GL account** by name (earnings and deductions post to this account).
8. Click **Save**.

**Editing and deactivating:** open the component by clicking its row (`/admin/hr/pay-components/uid/:uid`). Edit the fields and save, or click **Deactivate** to soft-deactivate the component (it becomes inactive and will no longer appear in payroll calculations going forward).

**Per-employee recurring items** (the amounts for PERCENT\_OF\_BASIC components and any fixed amounts applied to specific employees) are configured by the administrator directly in the system. These are applied automatically during payroll calculation and do not have a separate UI screen.

---

### Payroll Runs

**What is a payroll run, and what does it produce?**
A payroll run is the process of computing every employee's pay for a given month and producing the payslips, the GL journal, and the cash disbursement that physically pays the employees. The run gathers all relevant inputs — base salaries from contracts, deductions from approved unpaid leave, loan instalments, voluntary pay-component items — and applies the current statutory rates (PAYE bands, NSSF rates, HESLB rates, WCF, SDL) to produce a balanced journal entry and a payslip for every employee. The lifecycle (DRAFT → CALCULATED → APPROVED → POSTED → PAID) enforces a four-eyes check: one person prepares and calculates, a second person approves, a third posts to the books, and a fourth authorises the actual payment. A POSTED run can be reversed if an error is found after posting. Only one run can be active per period — you cannot accidentally pay the same month twice.

Navigate to **HR & Payroll > Payroll Runs** (`/admin/hr/payroll-runs`).

A payroll run computes gross pay, statutory deductions, voluntary deductions, and loan repayments for all employees with an active contract in a given period.

**Payroll run lifecycle:**

```
DRAFT → CALCULATED → APPROVED → POSTED → PAID
                                         ↓
                                      REVERSED
```

Each step requires a different permission. Only one active run can exist per period.

**Step 1 — Create a run (requires `HR.PAYROLL.RUN`):**

1. Click **New payroll run**.
2. Enter **Period month** (1–12), **Period year**, and **Pay date**.
3. Optionally pick a **Branch** by name if the run covers a specific branch.
4. Click **Save**. The run is created in **DRAFT** status with zero totals.

**Step 2 — Calculate (requires `HR.PAYROLL.RUN`):**

1. Open the run (`/admin/hr/payroll-runs/uid/:uid`).
2. Click **Calculate**. The system builds one payroll line per ACTIVE employee who has an ACTIVE contract:
   - Basic salary earning.
   - PAYE income tax (from the effective PAYE band set for the pay date, if `payeResident = true`).
   - NSSF deduction (employee share, if `nssfMember = true`).
   - HESLB deduction (if `heslbMember = true`).
   - Employer contributions (NSSF/WCF/SDL employer shares from the effective statutory rate sets).
   - Any voluntary pay-component recurring items configured for the employee.
   - Loan repayment deductions for any ACTIVE loans with an outstanding balance.
   - Pro-rata reduction for any approved unpaid leave overlapping the period.
3. The run status moves to **CALCULATED** and the Lines tab populates.

You can recalculate from DRAFT, CALCULATED, or APPROVED status — recalculation rebuilds all lines from scratch.

**Reviewing lines:** click the **Lines** tab to review each employee's line. A line showing a **FLAGGED** badge means the employee's net pay is negative after deductions. You must resolve flagged lines before the run can be approved — for example by reducing a loan installment and then recalculating.

**Step 3 — Approve (requires `HR.PAYROLL.APPROVE`):**

1. With the run in CALCULATED status and zero FLAGGED lines, click **Approve**.
2. Status moves to **APPROVED**.

**Step 4 — Post (requires `HR.PAYROLL.POST`):**

1. With the run in APPROVED status, click **Post**.
2. Status moves to **POSTED**. The GL journal is written asynchronously via the payroll posting handler. Payslips are generated (one per employee line).

**Step 5 — Disburse (requires `HR.PAYROLL.DISBURSE`):**

1. With the run in POSTED status, click **Disburse**.
2. Pick the **Cash or bank account** by name from which the net wages will be paid.
3. Optionally enter a **Transaction date** (defaults to the run's pay date).
4. Click **Submit**. Status moves to **PAID**. A Cash & Bank OUT entry is recorded (debit Net Wages Payable, credit the chosen bank/cash account).

**Reversing a run (requires `HR.PAYROLL.REVERSE`):**

A POSTED or PAID run can be reversed if needed (for example, a posting error). Click **Reverse** on the run detail. The status moves to **REVERSED** and a reversing GL journal is posted.

**Legal action matrix:**

| From status | Calculate | Approve | Post | Disburse | Reverse |
|---|---|---|---|---|---|
| DRAFT | Allowed | Blocked | Blocked | Blocked | Blocked |
| CALCULATED | Allowed (recalc) | Allowed (if no FLAGGED) | Blocked | Blocked | Blocked |
| APPROVED | Allowed (recalc) | Blocked | Allowed | Blocked | Blocked |
| POSTED | Blocked | Blocked | Blocked | Allowed (if net > 0) | Allowed |
| PAID | Blocked | Blocked | Blocked | Blocked | Allowed |
| REVERSED | Blocked | Blocked | Blocked | Blocked | Blocked |

---

### Statutory Setup

**What is the Statutory Setup, and why are the rates held in updatable tables?**
Statutory setup holds the tax bands and levy rates mandated by Tanzanian law: PAYE (Pay As You Earn income tax), NSSF (National Social Security Fund pension contributions), HESLB (Higher Education Students' Loans Board repayments), WCF (Workers' Compensation Fund), and SDL (Skills and Development Levy). These rates are set by the government and change periodically with each budget announcement. Because they are stored as **effective-dated data** in the system — not hard-coded in software — the administrator can add a new rate set with a future effective date when a budget announcement is made, and the payroll engine will automatically apply the correct rates when the pay date falls in the new period. This means a payroll run always reproduces exactly what the law required on that pay date, regardless of subsequent rate changes. Without effective-dated rate tables, every budget announcement would require a software update to change hard-coded constants.

Navigate to **HR & Payroll > Statutory Setup** (`/admin/hr/statutory`). Requires `HR.STATUTORY.MANAGE`.

The statutory setup screen shows two sections: **PAYE band sets** and **Statutory rate sets**. These sets determine how income tax and levies are calculated during payroll calculation.

**Creating a PAYE band set:**

**What is a PAYE band set?** A PAYE band set is a schedule of income tax rates that applies a progressive rate to different slices of monthly income. For example, the first TZS 270,000 per month might be tax-free, the next slice taxed at 9%, the next at 20%, and so on. Each band defines the lower income threshold at which the rate starts and the cumulative tax already payable on income up to that threshold (to avoid re-computing all lower bands for every employee). The system selects the most recently effective band set whose effective date is on or before the payroll run's pay date, ensuring the correct bands apply to each period.

1. Click **New PAYE band set**.
2. Enter an **Effective from** date, a **Tax-free threshold** (the monthly income amount below which no PAYE applies), and an optional description.
3. Add one or more bands. Each band requires: band number (ascending), lower bound (monthly income where this rate starts), marginal rate (decimal, e.g. `0.20` for 20%), and cumulative fixed tax (the tax already accumulated on income up to this band's lower bound).
4. Click **Save**.

The system uses the **most recently effective** band set whose effective date is on or before the payroll run's pay date.

**Creating a statutory rate set:**

**What is a statutory rate set?** A statutory rate set holds the percentage rates for one of the non-PAYE levies: NSSF, WCF, SDL, or HESLB. Each set records the employee rate, the employer rate (where applicable), the basis for the calculation (gross salary or basic salary), and — for SDL — a headcount threshold (SDL only applies to companies above a minimum employee count). Like PAYE band sets, rate sets are effective-dated so that rate changes can be scheduled in advance without software updates.

1. Click **New rate set**.
2. Choose the **Rate type**: NSSF, WCF, SDL, or HESLB.
3. Enter the **Effective from** date and **Basis** (e.g. GROSS or BASIC).
4. Enter the applicable rates (employee rate and/or employer rate, as a decimal).
5. For SDL, enter a **Headcount threshold** (SDL applies only when the company headcount equals or exceeds this number).
6. Click **Save**.

Contract statutory flags control which rate sets apply to each employee:
- `NSSF member` → NSSF deductions apply.
- `PAYE resident` → PAYE income tax applies.
- `HESLB member` → HESLB deduction applies.
- WCF and SDL are employer-only contributions; no contract flag controls them (they apply to the run if an effective rate set exists and the SDL headcount threshold is met).

---

## Part 2 — Budgeting

**What is a budget, and how does the module work?**
A budget is a forward-looking financial plan: it states how much the business expects to earn and spend in a future period, account by account. It exists to give management a target to work towards, a benchmark to compare against actual results, and a tool for anticipating cash needs. Budgets in this system are **planning records only** — they never post to the General Ledger. Instead, the approved budget lines are held separately and compared at report time against actual GL postings, producing the **Budget Variance Report** (how far actuals diverged from plan). The system supports multiple **versions** of a budget so that the business can revise the plan during the year without losing the original, and each version goes through an approval lifecycle (DRAFT → SUBMITTED → APPROVED) to ensure the plan is authorised before it is used as a benchmark (ADR-0034).

The Budgeting module is accessible from the **Budgeting** navigation group. Budgets are planning tools only — they do not post to the General Ledger. GL actuals are read at report time for comparison purposes.

### Permission requirements

| Action | Permission |
|---|---|
| View budgets, versions, lines | `BUDGETING.BUDGET.VIEW` |
| Create and edit budgets, versions, lines | `BUDGETING.BUDGET.MANAGE` |
| Submit and recall versions | `BUDGETING.BUDGET.SUBMIT` |
| Approve and reject versions | `BUDGETING.BUDGET.APPROVE` |
| Run variance and actuals reports | `BUDGETING.REPORT.VIEW` |

---

### Creating a Budget

Navigate to **Budgeting > Budgets** (`/admin/budgets`). Requires `BUDGETING.BUDGET.MANAGE`.

**What is a budget header?** The budget header is the container for all the planning work. It identifies the fiscal year being budgeted and the scope — either the whole company, or a specific cost centre (a department or business unit). You create one budget per fiscal year per scope, and within it you manage one or more versions as the plan evolves. A company-wide budget covers all income and expense accounts; a cost-centre-scoped budget covers only the activity attributed to that centre.

A budget covers a specific fiscal year and may be scoped to a specific cost centre (dimension value) or set as company-wide.

1. Click **New budget**.
2. Enter a **Name** for the budget.
3. Enter the **Fiscal Year UID** of the fiscal year you are budgeting for. (This is a direct text entry — obtain the UID from your administrator or from the fiscal-year setup screen.)
4. Optionally enter a **Cost Centre UID** to scope the budget to a department or cost centre. Leave blank for a company-wide budget.
5. Click **Create**.

The system creates the budget and automatically creates **Version 1** in DRAFT status. There can be only one budget per fiscal year and cost-centre scope combination.

The budget list shows each budget's name, fiscal year, latest version number, and latest version status.

---

### Budget Versions and the Version Lifecycle

**What is a budget version, and why are multiple versions needed?**
A budget version is a specific iteration of the plan. The first version (V1) is the original budget prepared at the start of the year. If actual events require the plan to be revised — a new product launch, an unexpected cost increase, a change in strategy — a new version (V2, V3, etc.) is created. The version lifecycle ensures that each revision is authorised before it replaces the previous plan: the preparer submits the version for approval, the approver reviews and approves or rejects it, and only one version is APPROVED (active) at any time. All prior approved versions are moved to SUPERSEDED (kept for reference) when a new one is approved. Rejected versions are kept but cannot be used as a benchmark; a new version must be created to revise after a rejection. Lines can only be edited on DRAFT versions — once submitted, the plan is locked.

Each budget can have multiple versions representing revisions to the plan. Versions go through an approval cycle before becoming the active plan.

**Version statuses:**

| Status | Meaning |
|---|---|
| DRAFT | Under construction — lines can be edited |
| SUBMITTED | Submitted for approval — lines are locked |
| APPROVED | The active approved plan; supersedes any prior approved version |
| REJECTED | Declined — create a new version to revise |
| SUPERSEDED | A previously approved version replaced by a newer APPROVED version |

**Version lifecycle transitions:**

```
DRAFT → SUBMITTED (submit, requires ≥1 line)
SUBMITTED → DRAFT (recall)
SUBMITTED → APPROVED (approve — also supersedes the prior APPROVED version)
SUBMITTED → REJECTED (reject, reason required)
```

APPROVED, REJECTED, and SUPERSEDED are terminal — no further edits or lifecycle actions can be taken on a version in these states. To re-plan after a rejection, create a new version.

**Opening the budget detail:**

Click a budget row in the list to open its detail (`/admin/budgets/uid/:uid`). The detail shows the budget header and all versions, listed newest first. Each version row shows its version number ("V1", "V2", etc.), label, status badge, and line count.

**Creating a new version (Re-plan):**

1. On the budget detail, click **New version**.
2. Optionally enter a **label** for this version.
3. To start from a prior version's lines, pick the source version from the **Seed from version** picker by its version label and status (e.g. "V1 — FY2026 base"). Leave blank to start with an empty version.
4. Click **Create**. The new version is created in DRAFT status.

---

### Entering Budget Lines

**What is a budget line?**
A budget line is the atomic planning unit: it links one GL account to one fiscal period and states the planned amount for that account in that period. For example, a line might say "Account: 5400 Fuel & Transport, Period: March 2026, Amount: TZS 3,200,000". The sum of all lines for an account across all periods is that account's annual budget. Lines are stored at the period grain (month by month) so that the variance report can show monthly deviations, not just annual totals. Lines can only be added, changed, or deleted when the version is in DRAFT status.

Open the version detail by clicking a version row (`/admin/budget-versions/uid/:uid`). The lines table shows account, period, amount (TZS), and memo. Lines are editable only when the version is in DRAFT status.

Click **Edit Lines (Replace All)** to open the line editor. Choose one of three entry modes:

**DIRECT — enter each line individually:**

1. Click **Add line**.
2. In the **Account** picker, choose the GL account by name.
3. In the **Period** picker, choose the fiscal period (e.g. "P3 – March 2026").
4. Enter the **Amount** in TZS (must be ≥ 0).
5. Optionally enter a **Memo**.
6. Repeat for additional lines.
7. Click **Replace Lines**. The new lines replace all prior lines for this version.

**ANNUAL\_SPREAD — enter an annual total and spread it evenly across 12 periods:**

**When is this useful?** When the budget planner knows the full-year target for an account but does not want to apportion it manually month by month. The system splits the annual amount into 12 equal monthly lines, using HALF_UP rounding and adding any cent-level residual to the last period so that the 12 lines sum exactly to the annual total.

1. In the **Account** picker, choose the GL account by name.
2. Enter the **Annual amount** in TZS.
3. Click **Replace Lines**. The system creates 12 lines (one per period), spreading the annual amount as evenly as possible (HALF_UP rounding; any remainder is added to the last period so the sum equals the annual total exactly).

The fiscal year must have exactly 12 periods to use ANNUAL\_SPREAD mode.

**SEED — copy lines from another version:**

**When is this useful?** When creating a revised budget version that starts from the same lines as a prior version. Rather than re-entering all lines from scratch, you seed from V1 and then edit only the accounts that have changed. This also works across fiscal years: you can seed V1 of the FY2027 budget from the approved V2 of FY2026 as a starting point.

1. In the **Seed from version** picker, choose the source version by its label and status.
2. Click **Replace Lines**. All lines from the source version are copied to this version, replacing any existing lines.

**Note:** editing lines is blocked when the version is not in DRAFT status. If you need to edit a SUBMITTED version's lines, recall it to DRAFT first.

---

### Submitting, Approving, and Rejecting Versions

**Submit (requires `BUDGETING.BUDGET.SUBMIT`):**

1. On the budget detail, click **Submit** next to the DRAFT version.
2. The version must have at least one line. If it has no lines, submission is rejected.
3. Status moves to SUBMITTED. Lines are locked.

**Recall (requires `BUDGETING.BUDGET.SUBMIT`):**

If you need to revise a SUBMITTED version, click **Recall** to return it to DRAFT. The submission timestamp is cleared and lines become editable again.

**Approve (requires `BUDGETING.BUDGET.APPROVE`):**

1. Click **Approve** next to the SUBMITTED version.
2. Optionally enter an approval note.
3. Confirm.

The version status moves to APPROVED. If there was a previously APPROVED version for the same scope, it is automatically moved to SUPERSEDED. Only one version can be APPROVED at any time per scope.

**Reject (requires `BUDGETING.BUDGET.APPROVE`):**

1. Click **Reject** next to the SUBMITTED version.
2. Enter a **rejection reason** (required).
3. Confirm.

The version status moves to REJECTED. The rejection reason is recorded for reference. To re-plan, create a new DRAFT version.

---

### Budget Reports

Both reports require `BUDGETING.REPORT.VIEW` and are accessible from the **Budgeting** nav group.

**Budget Variance Report** (`/admin/budgeting/variance`):

Compares the approved budget lines against actual GL postings for the selected period range.

1. Select the **Company**.
2. Enter the **Fiscal Year UID**.
3. Set the **From period** and **To period** (1–12; from must be ≤ to).
4. Optionally filter by **Account Type** (Income, Expense, Asset, Liability, Equity) and enter a **Cost Centre UID** to limit results to a specific centre.
5. Click **Run**.

The report shows account-level rows with budget amount, actual amount, variance (actual − budget), and a Favourable/Adverse label. For income accounts, actual > budget is favourable. For expense accounts, actual < budget is favourable.

If no APPROVED version exists for the selected scope, the report is returned with all budget amounts as zero and a "no approved budget" notice — the report is never silently wrong.

**Departmental Actuals Report** (`/admin/budgeting/departmental-actuals`):

Shows actual GL postings grouped by cost centre and account, with no budget comparison. Useful for monitoring departmental spending.

1. Select the **Company**.
2. Enter the **Fiscal Year UID**.
3. Set the **From period** and **To period**.
4. Click **Run**.

A null cost centre (transactions posted without a cost-centre dimension) appears as an **Unallocated** row.

---

## Part 3 — Platform Services

**What are Platform Services?**
Platform services are cross-cutting capabilities that every other module uses — they are not specific to Finance, HR, or Operations. Document generation produces the printable PDFs from data that already exists in the system. Notifications tells users what has happened that they need to act on. The approval engine intercepts high-value actions and routes them through a human authorisation chain. The audit trail records every state-changing action so that nothing can be silently altered. These services are the governance and communication spine of the ERP.

Platform services provide cross-cutting functionality used by all modules: document generation and management, notifications, the approval engine, and the audit trail.

---

### Documents

**What is the Document Generation module, and what does it produce?**
The Document Generation module renders formally formatted, branded PDF documents from transactions that already exist in the system. A sales invoice stored in the AR module, a purchase order in the Procurement module, or a goods receipt in the Inventory module all contain the data for a printable document, but that data is not yet in the layout a customer or supplier expects to receive. This module reads the source transaction as a read-only snapshot, merges it with the company's branding (logo, address, bank details, footer text), applies the chosen template, and produces a download-ready PDF. The generated document is stored in a log for audit purposes — you can re-download a document issued months ago without regenerating it. Every render is append-only: the log is never edited, and rendering a document never changes the source transaction. The six supported types in v1 are: Invoice, AR Statement, Purchase Order, Goods Receipt, Delivery Note, and Credit Note (ADR-0023).

#### Generated Documents Log

Navigate to **Documents > Generated Documents** (`/admin/documents`). Requires `DOCUMENT.VIEW`.

The log lists every document that has been rendered for the active company, with document number, type badge, source, and generated-at timestamp. Use the **Type** filter dropdown to narrow results by document type (Invoice, AR Statement, Purchase Order, Goods Receipt, Delivery Note, Credit Note).

#### Rendering a Document

Requires `DOCUMENT.RENDER`. The render form is on the same Generated Documents screen.

Six document types can be rendered in v1:

| Document type | Source |
|---|---|
| Invoice | A finalised sales invoice |
| AR Statement | A customer (with from/to date range) |
| Purchase Order | A confirmed purchase order |
| Goods Receipt | A received GRN |
| Delivery Note | A delivery record |
| Credit Note | A sales return/credit |

To render a document:

1. Click **Render document**.
2. Choose the **Document type** from the dropdown.
3. For all types except AR Statement: pick the **source record** by its number (invoice number, PO number, etc.) in the source picker.
4. For AR Statement only: enter the **customer** (chosen by name) and the **from date** and **to date** in the params section.
5. Click **Render**. A new row appears in the log.

To download a rendered document, click the **Download** button on the log row or detail page (also requires `DOCUMENT.RENDER`).

#### Document Templates

**What is a document template?**
A document template controls the layout and structure of a rendered document type. The system ships with a default template for each of the six renderable types. The template can be activated or deactivated — deactivating it prevents new renders of that type. Template content (the actual layout formatting) is maintained by the system administrator; the UI allows you to toggle the template's status and update its display title.

Navigate to **Documents > Document Templates** (`/admin/document-templates`). Requires `DOCUMENT.TEMPLATE.MANAGE`.

The template registry lists one row per renderable document type. You can change the template's **title** and toggle its **status** (ACTIVE or INACTIVE) by clicking the row and saving. Deactivating a template does not delete it.

#### Document Branding

**What is Document Branding?**
Document branding is the per-company configuration that controls what appears in the header and footer of every rendered PDF. Without branding, a PDF would carry no company name, address, tax ID, or bank details — it would be unacceptable as a formal document. The branding profile is a single set of settings per company (a "singleton"): there is no list to navigate, just one form that you edit and save. Changes take effect immediately on all subsequent renders; previously generated documents are not retroactively changed (the log is append-only).

Navigate to **Documents > Document Branding** (`/admin/document-branding`). Requires `DOCUMENT.BRANDING.MANAGE`.

The branding profile is a per-company singleton (one set of settings per company, no list). It controls what appears in the header and footer of rendered PDF documents.

1. Open the screen. The current branding values load into the form.
2. Edit: **Display name**, **Legal name**, **Tax ID**, **Address**, **Contact phone**, **Contact email**, **Website**, **Footer terms text**, and **Bank details**.
3. Click **Save**.

Changes take effect on all subsequent document renders. Previously generated documents are not changed (the log is append-only).

---

### Notifications

**What is the Notifications module, and how does it work?**
The Notifications module is the system's alerting spine. It listens for events that other modules emit — a payment received, an approval submitted, a payroll posted — and delivers an in-app message (and optionally an email) to the users who need to know. It also runs a scheduled background scanner for time-based conditions that have no single event trigger (such as an invoice becoming overdue overnight, or stock falling below its reorder level). Without notifications, users must actively poll every module to find out what has happened; notifications inverts this by pushing relevant information to the right person at the right time. Each notification type has an audience defined by permission (for example, an approval-submitted notification goes to all users who hold the approver role), and each user can customise their preferences — muting types they do not need, or disabling email delivery for types they prefer to see only in-app (ADR-0024).

#### Notification Inbox

Navigate to **Notifications > Inbox** (`/admin/notifications`). Requires `NOTIFICATION.VIEW`.

The inbox shows notifications sent to you within the active company, with title, message body, severity badge (INFO / WARNING / CRITICAL), and created-at timestamp.

- Toggle **Unread only** to filter to unread items only.
- Click **Mark read** on an individual row to mark it as read.
- Click **Mark all read** to clear the unread count at once.

The shell navigation bar shows a badge with the count of unread notifications. The badge reflects `GET /api/v1/notifications/unread-count`.

#### Notification Preferences

Navigate to **Notifications > Preferences** (`/admin/notification-preferences`). Requires `NOTIFICATION.PREFERENCE.MANAGE`.

Each notification type listed here can be tuned per user:

- **Muted** — suppress all delivery for this type for you.
- **Channels enabled** — choose which channels (IN\_APP, EMAIL) should deliver this type to you.

Click a preference row, adjust the settings, and save.

#### Notification Type Catalogue (Admin)

Navigate to **Notifications > Type Catalogue** (`/admin/notification-types`). Requires `NOTIFICATION.ADMIN`.

The catalogue lists all notification types registered in the system for the active company. Each type shows display name, audience permission, severity, default channels, and the company-level enabled/disabled toggle.

To disable a notification type company-wide (all users in the company stop receiving it), click the row, toggle **Company enabled** to off, and save. Any delivery attempts while the type is disabled will be recorded in the delivery log with suppression reason **COMPANY\_TYPE\_OFF**.

#### Notification Delivery Log (Admin)

Navigate to **Notifications > Delivery Log** (`/admin/notification-deliveries`). Requires `NOTIFICATION.ADMIN`.

The delivery log shows every notification delivery attempt with its outcome and, if suppressed, the reason. Use the **Channel** and **Outcome** filters to diagnose delivery problems.

| Suppression reason | Meaning |
|---|---|
| MUTED | The recipient muted this type |
| CHANNEL\_DISABLED | The channel (EMAIL/SMS) is not active |
| NO\_EMAIL | The recipient has no email address configured |
| COMPANY\_TYPE\_OFF | The type is disabled for this company |
| NO\_AUDIENCE | No eligible recipients found |

---

### Approvals

**What is the Approval Engine, and why is it a shared platform service rather than module-specific?**
The approval engine is a generic governance layer that intercepts certain high-value actions across the system and requires one or more human sign-offs before the action proceeds. Examples include confirming a large purchase order, posting a payroll run, or approving a budget version. Rather than each module building its own approval screen (which would lead to inconsistent behaviour and duplicate maintenance), the approval engine is a single shared service that any module can delegate to. A policy defines when approval is needed (which document type, above what monetary threshold, at which branch) and who must approve (which role). When a matching action is submitted, the engine creates an approval request, routes it to the appropriate approvers in sequence, and releases the action only when all steps are completed. If no policy matches, the action is auto-approved instantly. The engine posts nothing to the books; its sole purpose is to gate other modules' actions (ADR-0022).

Approval requests are created automatically by the relevant modules — there is no "create approval request" screen.

#### Approval Policies

**What is an approval policy?**
An approval policy defines the rule that triggers human approval: it says "for documents of type X, in the amount band [min, max), at scope Y, require approval from role Z". A policy can be company-wide (applies to all branches) or scoped to a specific branch (a branch-scoped policy takes priority when both match). The amount bands within a policy type must not overlap, and there can only be one active policy per (type, scope, band) combination — this ensures that every submission matches exactly one policy, making the outcome deterministic.

Navigate to **Approvals > Approval Policies** (`/admin/approvals/policies`). Requires `APPROVALS.POLICY.VIEW`.

Policies define when human approval is required and who must approve. Each policy targets one document type and a monetary band.

**Creating a policy (requires `APPROVALS.POLICY.MANAGE`):**

1. Click **New policy**.
2. Enter a **Name** and choose the **Document type** (e.g. Purchase Order).
3. Set the **Branch scope**:
   - **COMPANY\_WIDE** — applies to the entire company. Do not enter a branch.
   - **BRANCH** — applies to one specific branch. Pick the branch by name from the branch picker. A branch-scoped policy takes priority over a company-wide policy when both match the same request.
4. Set **Min amount** and **Max amount** (TZS). Leave **Max amount** blank for an unbounded top band (applies to all amounts ≥ Min amount).
5. Add one or more **Approval steps**. Each step has a **sequence number** (dense from 1) and an **Approver role code** (the permission role whose holders will see this request in their inbox). Click **Add step** to add another level of approval.
6. Click **Save**.

Policy changes only affect future submissions. In-flight PENDING requests continue under the policy that existed when they were created.

**Deactivating a policy:** open the policy detail (`/admin/approvals/policies/uid/:uid`), click **Deactivate**. Status moves to INACTIVE. Inactive policies are not matched on new submissions.

#### Approval Inbox (My Inbox)

**What is the Approval Inbox?**
The inbox shows every approval request that is currently waiting for your decision — specifically, requests where the current open step is routed to one of your permission roles. It is the daily working screen for managers, finance directors, and senior staff who hold approver roles. You see only the requests assigned to your role; you do not see requests routed to other roles. Approving moves the request to its next step (or resolves it if it was the final step); rejecting ends the entire request immediately and marks all remaining steps as skipped.

Navigate to **Approvals > My Inbox** (`/admin/approvals/inbox`). Requires `APPROVALS.DECIDE`.

The inbox shows PENDING requests whose current open step is routed to one of your roles. These are the requests waiting for your decision.

1. Click a request to open its detail.
2. Review the request: document type, amount, submitter, submission date, and step chain.
3. Click **Approve** (and optionally add a comment) or **Reject** (comment required).
4. Confirm.

When you approve a step:
- If this was the last step in the chain, the request moves to **APPROVED** and the originating document is released.
- If there are further steps, the request remains PENDING and the next step becomes the active one.

When you reject a step, the whole request moves to **REJECTED** and all remaining steps are marked **SKIPPED**.

#### All Requests

Navigate to **Approvals > All Requests** (`/admin/approvals/requests`). Requires `APPROVALS.REQUEST.VIEW`.

Shows all approval requests for the active company regardless of who the approver is. Filter by status (PENDING / APPROVED / REJECTED / RECALLED / CANCELLED) to narrow the list.

**Request statuses:**

| Status | Terminal? | Meaning |
|---|---|---|
| PENDING | No | Awaiting a decision on the current open step |
| APPROVED | Yes | All steps approved (or auto-approved) |
| REJECTED | Yes | A step was rejected; remaining steps skipped |
| RECALLED | Yes | Withdrawn by the submitter |
| CANCELLED | Yes | Cancelled by an administrator |

Terminal requests cannot be acted on further — all action buttons are hidden on a terminal request detail.

**Recalling a request (requires `APPROVALS.REQUEST.VIEW`, submitter only):**

The original submitter can recall their own PENDING request from the All Requests list. Open the request detail and click **Recall**. Status moves to RECALLED.

**Cancelling a request (requires `APPROVALS.ADMIN`):**

An administrator can cancel any non-terminal PENDING request. Open the request detail and click **Cancel**. Status moves to CANCELLED.

---

### Audit Trail

**What is the Audit Trail, and why is it append-only?**
The audit trail is the system's immutable record of every state-changing action — who did what, to which record, when, and from where. It is the primary tool for investigating a suspicious change, resolving a dispute ("who approved this payment?"), and satisfying auditor and regulatory requirements for a documented chain of custody. The audit trail is append-only: no record can be deleted or edited, not even by a system administrator. This property is fundamental to its integrity — an editable audit trail is no audit trail at all. Every module writes to the same audit trail so that you can search across the entire system in one place.

Navigate to **Audit** (`/admin/audit`). Requires `AUDIT.VIEW`.

The audit trail is a read-only, append-only log of all state-changing actions in the system. No records can be added, edited, or deleted from the UI.

The list shows:
- **Actor** (username)
- **Action** (event code, e.g. `USER.CREATE`, `PAYROLL.POST`, `APPROVAL.STEP.DECIDE`)
- **Target type and identifier**
- **Company and branch**
- **Timestamp** and IP address

Rows are sorted newest first (default page size 50, maximum 200 per page).

**Filters:**

- **Actor** — choose a user from the user picker by name.
- **Action** — enter or select an action code.
- **Target type** and **Target UID** — filter to records affecting a specific resource.
- **From date** and **To date** — filter by time range.

Non-root users are confined to their own active company's audit rows. The system administrator (`rootadmin`) can view audit records across all companies.
