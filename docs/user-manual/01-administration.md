# Administration and Access

This chapter is for administrators — typically users who hold the **ORG_ADMIN** role. It covers how to set up the organisation hierarchy (companies and branches), manage user accounts, define roles and their permissions, assign roles and branches to users, and review the audit trail.

> **Permissions required.** You need specific permissions for each section below. If a menu item or button is not visible to you, your role does not include that permission. See the table in each section.

---

## Organisation, Companies, and Branches

The system is structured in three levels:

- **Organisation** — the top-level entity representing your business group. It is configured by IT during deployment and is resolved automatically; you never type an organisation ID.
- **Company** — a legal entity under the organisation (for example, a registered company or subsidiary). Each company has its own data.
- **Branch** — a physical or logical office, store, or cost centre under a company. Transactions are scoped to a branch.

### Viewing companies

**Required permission:** `COMPANY.VIEW`

Navigate to **Administration > Companies** in the sidebar.

The list shows each company's code, name, and status (Active or Archived). Your view is limited to companies within your active organisation and, for non-admin users, to companies you are scoped to act in.

### Creating a company

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

Click **Archive** on the company detail screen. The company's status changes to **Archived** (the record is not deleted). Archived companies cannot be used for new transactions, and their branches become unavailable for user sessions.

> Archiving a company affects all users whose default branch belongs to that company — they will have no active branch on their next login.

---

### Viewing branches

**Required permission:** `BRANCH.VIEW`

From the Companies list, click a company to open its detail, then click **Branches** to see the list of branches under that company.

### Creating a branch

**Required permission:** `BRANCH.MANAGE`

1. From the branch list of a company, click **Create Branch**.
2. Fill in:
   - **Code** — a unique code within this company (up to 20 characters).
   - **Name** — the branch display name (up to 160 characters).
   - **Timezone** — optional, defaults to the company timezone.
   - **Set as default** — check this to make the new branch the company's default branch. If another branch was already the default, that branch's default flag is cleared automatically.
3. Click **Save**.

### Setting the company default branch

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

**Required permission to view:** `USER.VIEW`
**Required permission to create/edit/disable/unlock/reset password:** `USER.MANAGE`

Navigate to **Administration > Users**.

### The users list

The list shows each user's username, display name, and status. Use the search bar to filter by name. Click **Manage branches** on any row to open the user's detail page.

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

If a user has been locked out after too many failed sign-in attempts, a locked indicator appears on their row. Click **Unlock** to clear the lockout. The user can then sign in again with the correct password.

### Resetting a user's password

1. On the Users list, expand the row's **Set Password** form.
2. Enter a new password that meets the policy (at least 8 characters, at least one letter and one number, not a common password).
3. Click **Save**.

The user can sign in immediately with the new password. Passwords are never stored in plain text and are not shown in audit logs.

### Editing user contact details

Navigate to the user's detail page (click **Manage branches** from the list). You can update the display name, email, and phone number. The username and status are changed via their dedicated actions, not here.

---

## Roles

**Required permission to view:** `ROLE.VIEW`
**Required permission to create/edit/set permissions/archive:** `ROLE.MANAGE`

Navigate to **Administration > Roles**.

Roles are named bundles of permissions. A user can be granted one or more roles; the effective permissions are the union of all granted roles in the active branch context.

### The roles list

The list shows each role's code, name, and whether it is a **System** role (pre-defined and cannot be archived) or a custom role. Click a role's code or name to open its edit page.

### Creating a role

1. Click **Create Role**.
2. Fill in:
   - **Code** — a short identifier, unique within the organisation (up to 40 characters). Cannot be changed after creation.
   - **Name** — a human-readable label (up to 120 characters).
   - **Description** — optional notes.
3. Click **Save**.

The new role is created with no permissions. Assign permissions next.

### Editing a role's name or description

Open the role's edit page and update the name or description fields, then click **Save details**. The code cannot be changed.

### Setting a role's permissions

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

**Required permission:** `ROLE.MANAGE`

Navigate to **Administration > Role Grants**.

This screen lets you grant a role to a user for a specific company, optionally restricted to a single branch.

### Granting a role

1. On the Role Grants screen, choose the **User** by typing their name in the picker.
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

**Required permission to view branch assignments:** `USER.VIEW`
**Required permission to assign / change default / remove:** `BRANCH.ASSIGN`

Branch assignments control which branches a user can switch to and which data they can access. Open a user's detail page by clicking **Manage branches** from the Users list.

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

## Audit Trail

**Required permission:** `AUDIT.VIEW`

Navigate to **Administration > Audit**.

The audit trail is an append-only log of every significant action performed in the system — who did it, what they did, and when. It cannot be edited or deleted.

### What the audit trail records

Every create, update, state change (such as enabling or disabling a user), grant, and revoke generates an audit record. Records include:

- The **action** (for example, `USER.CREATE`, `ROLE.GRANT`, `BRANCH_UNASSIGN`).
- The **actor** — the username who performed the action.
- The **target** — the type and identifier of the affected record.
- The **timestamp** (date and time).
- For cross-company actions by `rootadmin`, a special `ROOT.BYPASS` entry is also recorded.

### Reviewing the audit log

1. Navigate to **Administration > Audit**.
2. Use the filters at the top to narrow by action type, actor, date range, or target type.
3. The list shows the most recent events first. Use the pager to browse older records.

Audit records show usernames and action codes — not raw internal identifiers. Sensitive data (such as password hashes) is never included in audit details.
