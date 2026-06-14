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

The ERP is multi-branch. Every transaction and piece of data is scoped to the branch you are currently working in.

- On login the system activates your **default branch** automatically.
- If you are assigned to more than one branch, click the branch name in the top bar to open a dropdown. Click any branch in the list to switch to it. The list shows only your active, assigned branches — by name, not by any internal code.
- Switching branches takes effect immediately; your permissions and the data you see may change depending on your role grants.
- Selecting the branch you are already in is a no-op — the menu simply closes.

> If you have no active branch (for example, your only branch was removed or archived), you will be in a read-only state. Contact your administrator to be assigned to an active branch.

---

## How Permissions Shape What You See

The system controls access by **permission**. Your administrator creates roles, assigns specific permissions to each role, and then grants those roles to you.

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

- **Money** is always shown with the currency code and two decimal places, for example `TZS 1,234.56` or `USD 200.00`. You never need to type a currency symbol — the system knows the currency from context.
- **Dates** are shown in your local timezone. When entering dates use the date picker provided — never type raw date strings.

### Creating, editing, and saving records

The general flow for creating or editing a record is:

1. Click **Create** (or open an existing record).
2. Fill in the form. Required fields are marked. The system validates as you go and shows inline messages if something is wrong.
3. Click **Save** (or the specific action button, for example **Confirm** for a sales order).
4. A brief success notification (a "toast") appears at the top of the screen to confirm the action was saved. If something went wrong, an error message appears in the form itself or as an alert — read it, correct the issue, and try again.

> The system does not hard-delete records. Deactivating a user, archiving a product, or cancelling an order leaves the record in the system in an inactive or historical state. You can always review past records.

---

## Signing In as rootadmin

The `rootadmin` account bypasses all permission checks and sees every screen and every action in every company and branch. It is reserved for initial system setup and emergency recovery. Normal day-to-day work should use named user accounts with appropriate roles.

---

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

---

# Master Data

Master data is the reference information shared across the system: the parties you trade with, the products you sell or buy, the prices you charge, the currencies you transact in, the taxes you apply, and the routes your sales team covers. Set this up first; every transaction in Sales, Procurement, Inventory, and Finance depends on it.

All master data screens are under the **Admin** section of the navigation. Your access depends on the permissions assigned to your role — the sections below note which permission is required for each area.

---

## Customers

**Navigation:** Admin > Customers | **Permission to view:** `CUSTOMER.VIEW` | **Permission to create / edit:** `CUSTOMER.MANAGE`

Customers are the parties you sell to. Each customer belongs to one company and carries a system-generated code (`CUST-0001`, `CUST-0002`, …). You never enter or see the internal uid — the system uses that behind the scenes.

### Customer types

Every customer record has two classification fields set at creation time:

| Field | Options | Notes |
|---|---|---|
| **Party Type** | Individual, Business | Business customers must have a TIN. |
| **Customer Kind** | Cash / Walk-in, Credit Account | Credit account customers carry a credit limit and payment terms. |

Once saved, Party Type and Customer Kind can be changed on the detail edit form.

### How to create a customer

1. Navigate to **Admin > Customers**.
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

On the Customers list:

- Type in the **Search** box. Name search is case-insensitive and matches any part of the name.
- Searching by **TIN**, **Phone**, or **Code** requires an exact match.
- The list resets to the first page when you start a new search.
- Click **Clear** to return to the full unfiltered list.

### How to view and edit a customer

1. Click on any row in the customer list to open the detail page.
2. The URL contains the customer's uid (`/admin/customers/uid/…`) — you do not need to read or type this.
3. Edit any field in the form. The **Code** and **Company** fields are read-only (they are set at creation and cannot change).
4. If Customer Kind is **Cash / Walk-in**, the Credit Limit and Payment Terms fields are hidden. Switch to Credit Account to reveal them.
5. Click **Save** to apply changes.

### How to archive and restore a customer

An archived customer remains in the database for historical reporting but is not available for new transactions.

1. Open the customer detail page.
2. Click **Archive**. The status badge changes to **Archived**.
3. To reverse, click **Restore**. The status returns to **Active**.

Archiving and restoring are both immediate and do not require a reason.

### Branch associations

A customer can be associated with specific branches of your company. This determines which branches can see the customer in their scoped views.

1. Open the customer detail page.
2. Scroll to the **Branch Associations** panel.
3. Select the **Company** from the first dropdown, then select the **Branch** (shown as `code — name`) from the second.
4. Click **Assign**. The branch appears in the association list with the date it was assigned.
5. To remove a branch, click **Remove** on the relevant row.

You need the `PARTY.BRANCH.ASSIGN` permission to assign or remove branches. You can only assign branches that belong to the same company as the customer.

---

## Suppliers

**Navigation:** Admin > Suppliers | **Permission to view:** `SUPPLIER.VIEW` | **Permission to create / edit:** `SUPPLIER.MANAGE`

Suppliers are the parties you purchase from. The data structure mirrors customers, with one difference: the kind field distinguishes **Goods** suppliers from **Service** suppliers (there are no credit limit or payment terms fields on a supplier record).

Supplier codes are prefixed `SUPP-` (for example, `SUPP-0001`).

### How to create a supplier

1. Navigate to **Admin > Suppliers**.
2. Click **New Supplier**.
3. Enter **Display Name** (required), **Party Type**, and **Supplier Kind** (Goods or Service).
4. If Party Type is Business, enter the **TIN**.
5. Fill in optional contact details and VAT fields as described in the Customers section above.
6. Click **Submit**.

The same rules apply: TIN required for Business parties, VRN only when VAT Registered is ticked.

### Search, edit, archive, restore, and branch associations

These work exactly as described for Customers above, substituting the Suppliers screen and the `SUPPLIER.MANAGE` / `PARTY.BRANCH.ASSIGN` permissions.

---

## Other Parties

**Navigation:** Admin > Other Parties | **Permission to view:** `OTHERPARTY.VIEW` | **Permission to create / edit:** `OTHERPARTY.MANAGE`

Other Parties covers any third party that is not a customer, supplier, or agent — for example, landlords, regulatory bodies, utility providers, or freight companies. Other Party codes are prefixed `OTHR-`.

The key difference from customers and suppliers is the **Other Kind** field, which is free text (not a fixed list). You can type any label, such as "Landlord", "Utility", or "Freight Forwarder". The field is optional.

All other behaviour — TIN rule for Business parties, VAT/VRN pairing, archive/restore lifecycle, and branch associations — is identical to Customers and Suppliers.

---

## Sales Agents

**Navigation:** Admin > Sales Agents | **Permission to view:** `AGENT.VIEW` | **Permission to create / edit:** `AGENT.MANAGE`

Sales agents represent the people or organisations that sell on your behalf. Agent codes are prefixed `AGNT-`.

### Agent kinds

| Kind | Meaning | User link |
|---|---|---|
| **Internal** | An employee who is also an app user | Must be linked to an active user in the same company |
| **External** | A third-party agent, not an app user | Must NOT be linked to an app user |

### How to create an agent

1. Navigate to **Admin > Sales Agents**.
2. Click **New Agent**.
3. Enter **Display Name**, **Party Type**, and **Agent Kind** (Internal or External).
4. If Kind is **Internal**, a **User** selector appears. Choose the user by name from the list. The system stores the link internally — you do not type a user id.
5. If Kind is **External**, the user selector is hidden.
6. Click **Submit**.

### Switching an agent between Internal and External

On the agent detail page, changing Kind from Internal to External clears the user link automatically on save. Changing from External to Internal requires you to select a user before saving.

### Search, edit, archive, restore, and branch associations

These work as described for Customers, using the `AGENT.MANAGE` and `PARTY.BRANCH.ASSIGN` permissions.

---

## Products

**Navigation:** Admin > Products | **Permission to view:** `PRODUCT.VIEW` | **Permission to create / edit:** `PRODUCT.MANAGE`

Products are the items you sell, buy, or manufacture. Each product belongs to one company and carries a system-generated code (for example, `PROD-0001`) unless you supply your own code at creation time.

### Product types

| Field | Options | Rules |
|---|---|---|
| **Type** | Goods, Service | Service products cannot be stockable (the Stockable checkbox is forced off). |
| **Stockable** | Yes / No | Only Goods products can be stockable. |
| **Sellable** | Yes / No | Controls whether the product appears in sales flows. |
| **VAT Status** | Standard, Zero-rated, Exempt | Defaults to Standard. |

### How to create a product

1. Navigate to **Admin > Products**.
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

1. Click a product row to open the detail page.
2. Modify fields as needed. The **Code** field is read-only on the detail page.
3. Click **Save**.

If you change Type from Goods to Service, the Stockable checkbox is forced off automatically.

### How to archive and restore a product

Open the product detail page and click **Archive** (to make it unavailable) or **Restore** (to make it active again). Archived products are excluded from order lines and component pickers.

### Branch associations

Works exactly as described for Customers. The permission required is `PRODUCT.BRANCH.ASSIGN`.

### Barcodes

In the **Barcodes** panel on the product detail page:

1. Type the barcode value.
2. Tick **Primary** if this is the product's primary barcode.
3. Click **Add Barcode**.
4. To remove a barcode, click **Remove** on the relevant row.

### Bulk packs

Bulk packs define how many base units fit into a larger packaging unit (for example, 24 `EA` in a `CTN — Carton`).

1. In the **Bulk Packs** panel, select the **Unit** (the larger packaging unit) from the dropdown by code and name.
2. Enter the **Factor** — the number of base units in one pack (must be greater than zero).
3. Click **Add**.
4. To remove a bulk pack, click **Remove**.

### Product prices

You can set a selling price for this product on each of your price lists.

1. In the **Prices** panel, select the **Price List** by its code and name.
2. Enter the **Amount** and **Currency**.
3. Click **Set Price**.

Setting a price on a price list that already has a price for this product overwrites the existing price. To remove a price, click **Remove** on the row.

### Product components (recipe)

Components define the ingredients or sub-products that make up this product — used in manufacturing or bundled sales.

1. In the **Components / Recipe** panel, start typing a product name in the search box.
2. Select the component product from the results (shown as `code — name`). The product itself and archived products are excluded from the list.
3. Enter the **Quantity** (must be greater than zero).
4. Click **Add Component**.
5. To remove a component, click **Remove** on the row.

---

## Units of Measure

**Navigation:** Admin > Units of Measure | **Permission to view:** `UOM.VIEW` | **Permission to create / edit:** `UOM.MANAGE`

Units of measure (UoM) are the quantity labels used on products, bulk packs, and order lines — for example, `EA` (Each), `KG` (Kilogram), `CTN` (Carton).

### How to create a unit

1. Navigate to **Admin > Units of Measure**.
2. Click **New Unit**.
3. Enter the **Code** (for example, `CTN`) and the **Name** (for example, `Carton`). Both are required and the code must be unique within the company.
4. Click **Submit**.

### How to edit a unit

Click **Edit** on a row, change the **Name** (the Code is read-only after creation), and click **Save**.

### Archive and restore

Click **Archive** to deactivate a unit. Archived units are removed from product and bulk-pack dropdowns — only active units are selectable. Click **Restore** to make the unit active again.

---

## Price Lists

**Navigation:** Admin > Price Lists | **Permission to view:** `PRICELIST.VIEW` | **Permission to create / edit:** `PRICELIST.MANAGE`

Price lists group selling prices. You might have a Retail list (`RETAIL`), a Wholesale list (`WHOLESALE`), and a Distributor list. Customers and orders are assigned a price list, and the system looks up the price from there.

### How to create a price list

1. Navigate to **Admin > Price Lists**.
2. Click **New Price List**.
3. Enter a **Code** (for example, `RETAIL`) and a **Name** (for example, `Retail Price List`). Both are required and the code must be unique within the company.
4. Click **Submit**.

### Edit, archive, restore

Click **Edit** on a row to change the name (code is read-only after creation). Archive and restore work as on all master records.

---

## Currencies and FX Rates

**Navigation:** Admin > FX Rates | **Permission to view:** `CURRENCY.VIEW` | **Permission to add rates:** `CURRENCY.MANAGE`

The system's base currency is **TZS**. You can record foreign exchange rates to support transactions in other currencies (USD, EUR, KES, and others).

### Currency list

Currencies are global reference data — you cannot create or delete them. The available currencies (TZS, USD, EUR, KES, and others) are seeded by the system and visible in the From / To pickers on the FX Rates screen.

### How to add an FX rate

1. Navigate to **Admin > FX Rates**.
2. Click **New Rate**.
3. Select the **From** currency and the **To** currency. They must be different.
4. Enter the **Rate** (must be greater than zero).
5. Set the **Effective Date** (required; format `YYYY-MM-DD`).
6. Set **Rate Type** (for example, `SPOT`) and **Source** (for example, `MANUAL`).
7. Click **Submit**.

FX rates are **append-only**: you cannot edit a rate in place. To correct a rate, add a new row with the corrected value and the correct effective date. The system uses the latest effective-dated rate for each currency pair when converting amounts.

The rates list is sorted newest-first and is paginated.

---

## Tax Rates

**Navigation:** Admin > Tax Rates | **Permission to view:** `TAXRATE.VIEW` | **Permission to edit:** `TAXRATE.MANAGE`

Three VAT bands are seeded per company:

| Band | Default rate |
|---|---|
| Standard | 18% (0.18) |
| Zero-rated | 0% (0.00) |
| Exempt | 0% (0.00) |

You can edit the rate for each band. There is no create or archive on tax rates — the three bands are fixed.

### How to edit a tax rate

1. Navigate to **Admin > Tax Rates**.
2. Click **Edit** on the relevant band row.
3. Enter the new rate as a decimal between 0 and 0.9999 (for example, `0.18` for 18%).
4. Click **Save**.

The rate applies to all future transactions that reference this VAT band on a product.

---

## Distribution Routes

**Navigation:** Admin > Routes | **Permission to view:** `ROUTE.VIEW` | **Permission to create / edit / assign branches:** `ROUTE.MANAGE` | **Permission to assign customers and agents:** `ROUTE.ASSIGN`

Routes represent geographic or logical delivery areas used to group customers and assign agents. Each route has a system-generated code, a name, and an optional location identifier.

### How to create a route

1. Navigate to **Admin > Routes**.
2. Click **New Route**.
3. Enter the **Name** (required) and optionally a **Location Identifier**.
4. Click **Submit**.

The system assigns a code. Status defaults to Active.

### How to edit a route

1. Click a route row to open the detail page.
2. Change the name or location identifier (code and company are read-only).
3. Click **Save**.

### Archive and restore

Click **Archive** on the route detail page to deactivate it. Click **Restore** to reactivate.

### Assigning customers to a route

1. Open the route detail page.
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

# Sales and Point of Sale

This chapter covers everything from quoting a customer through to collecting payment, including recurring and blanket agreements, advanced pricing, and the Point of Sale cashier workflow.

---

## Overview

The sales module follows the order-to-cash (O2C) path:

```
Quotation → Sales Order → Delivery → Sales Invoice → Payment
```

Walk-in cash sales skip the first three steps and begin directly with a Sales Invoice or a POS sale.

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

A quotation is an offer sent to a customer. When accepted it becomes a Sales Order automatically.

### 1.1 Create a quotation

1. Navigate to **Sales → Quotations**.
2. Click **New Quotation**.
3. In the **Customer** field, type part of the customer name or code and select the correct entry from the list. Do not type or paste a raw ID.
4. Set **Quote Date** (today by default) and **Valid Until** (the date the offer expires).
5. Click **Save**. The quotation is saved in **DRAFT** status. A quote number is assigned later when you send it.

**Required fields:** Customer, Quote Date, Valid Until.

### 1.2 Add lines to a quotation

1. Open the draft quotation.
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

## 2. Sales Orders

A Sales Order (SO) can be created in two ways: automatically when a quotation is accepted, or directly from **Sales → Sales Orders → New Order**.

### 2.1 Create a standalone Sales Order

1. Navigate to **Sales → Sales Orders**.
2. Click **New Order**.
3. Pick the **Customer** by name.
4. Set **Order Date**. Optionally set a **Document Discount** (percentage or amount — not both).
5. Click **Save**. The order is created in **DRAFT**.

### 2.2 Add lines to a Sales Order

The same process as adding quotation lines. Lines can only be added, edited, or removed while the order is in DRAFT.

### 2.3 Confirm an order

Confirming an order reserves stock for every GOODS line.

1. Open the draft order (which must have at least one line).
2. Click **Confirm**.
3. The status changes to **CONFIRMED** and each line shows its reserved quantity.

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

A delivery records that goods have physically left the warehouse. Deliveries can only be created against a **CONFIRMED** or **PARTIALLY_FULFILLED** order.

### 3.1 Create a delivery

1. Navigate to **Sales → Deliveries** and click **New Delivery**, or open a confirmed Sales Order and use the **Create Delivery** action.
2. Pick the **Sales Order** by order number.
3. The form shows all open (undelivered) lines with the remaining quantity pre-filled.
4. Adjust individual line quantities if you are making a **partial delivery** (backorder). The quantity you enter cannot exceed the open balance.
5. Set **Delivery Date** and click **Submit**.

Deliveries are created immediately in **CONFIRMED** status and cannot be undone. Each delivery is assigned a DELIVERY-#### number.

### 3.2 Partial delivery (backorder)

Enter a quantity less than the open balance on any line to create a partial delivery. The Sales Order status moves to **PARTIALLY_FULFILLED**. Create another delivery later for the remaining quantity.

### 3.3 Generate an invoice from a delivery

Once goods are delivered, you can invoice the customer for that delivery:

1. Open the delivery.
2. Click **Create Invoice from Delivery**.
3. A draft **Sales Invoice** is created automatically with the delivered lines. The doc discount from the source order is pro-rated to the delivered quantity.

Proceed to section 4 to finalise the invoice.

---

## 4. Sales Invoices

An invoice is the formal billing document. There are two origins:

- **From a delivery** (origin: SALES_ORDER) — created via section 3.3 above.
- **Direct walk-in** (origin: DIRECT) — created manually for cash customers without a prior order.

### 4.1 Create a direct (walk-in) invoice

1. Navigate to **Sales → Invoices**.
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

A finalised invoice can be voided if it was issued in error:

1. Open the finalised invoice.
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

## 5. Sales Returns (RMA)

A sales return records goods coming back from the customer. Returns are always against a specific delivery and immediately generate a credit note.

### 5.1 Create a return

1. Navigate to **Sales → Sales Returns** and click **New Return**.
2. Pick the **Delivery** by its delivery number.
3. The form shows the delivered lines. Enter the **Quantity Returned** for each line being returned (cannot exceed the quantity delivered minus what has already been returned).
4. Set the **Return Date** and enter a **Reason**.
5. Click **Submit**.

Returns are created directly in **CONFIRMED** status. Stock is returned to the branch. A credit note is raised automatically (pro-rated to the returned quantity).

### 5.2 Returnable quantity

Each return reduces the returnable balance for that delivery line. You can process multiple returns against the same delivery line until the full delivered quantity has been returned.

---

## 6. Blanket Orders

A blanket order is a framework agreement with a customer that commits to supplying a total quantity at a fixed unit price over a validity window. Actual deliveries are created as **releases** (draw-downs) against the blanket.

### 6.1 Create a blanket order

1. Navigate to **Sales → Blanket Orders** and click **New Blanket Order**.
2. Select the **Company** and **Branch**.
3. Pick the **Customer** by name.
4. Set **Currency**, **Valid From**, and **Valid To** dates.
5. Add one or more **Lines**: for each, pick the product by name, choose a unit, and enter the committed quantity and unit price.
6. Optionally add notes (up to 500 characters).
7. Click **Save**.

The blanket order is created with status **ACTIVE** and assigned an order number.

### 6.2 Create a release (draw-down)

When the customer calls off part of their commitment:

1. Open the blanket order.
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

## 7. Standing Orders (Recurring)

A standing order is a recurring template that generates a new Sales Order automatically on a schedule (daily, weekly, bi-weekly, or monthly). It is useful for regular supply contracts.

### 7.1 Create a standing order

1. Navigate to **Sales → Standing Orders** and click **New Standing Order**.
2. Pick the **Branch**, **Customer**, and set **Currency**.
3. Choose a **Frequency**: Daily, Weekly, Bi-Weekly, or Monthly.
4. Set a **Start Date**. Optionally set an **End Date**; leave it blank for open-ended.
5. Add lines: pick each product and unit by name, enter quantity and unit price.
6. Click **Save**.

The standing order is created with status **ACTIVE** and the first `Next Run Date` is set.

### 7.2 Pause and resume

- **Pause** — open the standing order and click **Pause**. No Sales Orders are generated while the order is paused.
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

## 8. Pricing Rules

Pricing rules let you set volume-break discounts and customer-specific contract prices. They are configured under **Sales → Pricing Rules**.

### 8.1 Price tiers (quantity breaks)

A price tier gives a lower unit price when a customer orders at least a minimum quantity of a product on a given price list.

**To create a tier:**

1. Open **Pricing Rules** and go to the **Price Tiers** tab.
2. Click **New Tier**.
3. Pick the **Product** and **Price List** by name.
4. Enter **Min Quantity**, **Unit Price**, and **Currency**.
5. Click **Save**.

The tier status is **ACTIVE**. To deactivate a tier, click the **Deactivate** button on the row; the tier is soft-deactivated and no longer applied to new transactions.

You cannot have two active tiers for the same product, price list, and minimum quantity combination.

### 8.2 Customer prices (contract prices)

A customer price sets a fixed unit price for a specific product for a specific customer, overriding the standard price list.

**To create a customer price:**

1. Open **Pricing Rules** and go to the **Customer Prices** tab.
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

## 9. Point of Sale

POS is used for face-to-face retail transactions. A **till** is a physical cash register position. Each till must be opened in a **session** before sales can be processed. The session is closed and reconciled at end of day.

### 9.1 Roles

| Role | Typical permissions |
|---|---|
| Cashier | Open session, ring sales, view sessions |
| Manager | All cashier permissions plus create/deactivate tills, close sessions, reconcile |

Your administrator assigns the appropriate POS permissions to your role. Contact them if POS is not visible in your menu.

### 9.2 Set up a till

This is a one-time setup task done by a manager.

1. Navigate to **Point of Sale → POS Tills**.
2. Click **New Till**.
3. Enter a **Till Name** (e.g. "Counter 1").
4. Pick the **Branch** by name.
5. Click **Create Till**.

The till is created with status **ACTIVE**. To deactivate a till, click **Deactivate** on its row.

### 9.3 Open a session (start of day)

1. Navigate to **Point of Sale → POS Sessions**.
2. Click **Open Session**.
3. Pick the **Till** by name (only ACTIVE tills are listed).
4. Enter the **Opening Float** — the cash amount placed in the drawer at the start of the day.
5. Click **Open Session**.

A new session is created with status **OPEN**. Only one session can be open on a till at a time.

### 9.4 Ring a sale

1. Navigate to **Point of Sale → Point of Sale** (the checkout screen).
2. If your organisation has more than one company, select the correct company.
3. Pick the **Session** — only OPEN sessions are listed.
4. Pick the **Customer** by name.
5. Pick the **Agent** by name (required).
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

A payout records cash leaving the drawer during the session — for example, a drop to the safe or a petty-cash refund.

1. Open the session detail (**Point of Sale → POS Sessions**, click **View** on the OPEN session).
2. Click **Record Payout**.
3. Select the **Type**: Paid Out (cash removed from the drawer) or Refund (customer cash refund).
4. Enter the **Amount** and a **Reason**.
5. Click **Record**.

Both payout types reduce the expected closing cash. The live X-read total updates automatically.

### 9.6 X-Read (live totals during the day)

The **X-Read** card on the session detail page shows running totals without closing the session:

| Field | Meaning |
|---|---|
| Sales Total | Sum of all POS sale totals in this session |
| Payouts | Sum of all payouts (PAID_OUT + REFUND) |
| Expected Cash | Opening Float + Sales Total − Payouts |
| Invoice Count | Number of sales processed |

Click the refresh icon to reload the X-read at any time.

### 9.7 Close a session (end of day)

Closing records the physical cash count.

1. Open the session detail.
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

Reconciliation posts the variance to the general ledger and produces the final Z-Read report.

1. Open a **CLOSED** session.
2. Click **Reconcile**.
3. Optionally add notes.
4. Click **Reconcile**.

The session status changes to **RECONCILED**. The **Z-Read** card shows all session figures plus the variance and (if non-zero) the journal reference:

- **Over variance** — debit Cash, credit income account 4900 (Till Surplus).
- **Short variance** — debit expense account 5170 (Till Shortage), credit Cash.
- **Zero variance** — no journal posted.

After reconciliation the session is read-only and no further sales or payouts can be recorded.

### 9.9 Session lifecycle

| Status | Meaning |
|---|---|
| OPEN | Sales and payouts can be recorded |
| CLOSED | Session counted; reconciliation pending |
| RECONCILED | Final Z-read produced; GL posted; session closed |

Transitions are one-way: OPEN → CLOSED → RECONCILED. A session cannot be re-opened.

### 9.10 Daily workflow summary

1. **Open** a session on your till with the day's opening float.
2. **Ring sales** as customers arrive.
3. **Record payouts** for any cash removed from the drawer.
4. Check the **X-Read** at any time for running totals.
5. At end of day, **count** the cash in the drawer.
6. **Close** the session by entering the counted amount.
7. A manager **reconciles** the closed session; the system posts any variance to the GL.

---

# Procurement (Procure-to-Pay)

This chapter covers the full procure-to-pay (P2P) chain from raising a purchase request through to settling the supplier's invoice, including goods receipt, landed costs, purchase returns, and purchase settings.

---

## Overview

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

A purchase requisition is an internal request for goods or services. It must be approved before a purchase order or RFQ can be raised.

### 1.1 Create a requisition

1. Navigate to **Procurement → Purchase Requisitions**.
2. Click **New Requisition**.
3. Set the **Required By** date and optionally a cost centre and notes.
4. Add lines: for each item, pick the **Product** by name, choose a **Unit**, and enter the **Requested Quantity** and an **Estimated Unit Cost**.
5. Click **Save**. The requisition is saved in **DRAFT**.

### 1.2 Submit a requisition

When the requisition is complete and ready for approval:

1. Open the draft requisition.
2. Click **Submit**.
3. The status changes to **SUBMITTED** and the requisition is routed for approval.

### 1.3 Approve or reject a requisition

An approver (a user with `PURCHASE.REQUISITION.APPROVE`) reviews submitted requisitions.

- **Approve** — click **Approve**. Status → **APPROVED**. The Convert action becomes available.
- **Reject** — click **Reject**, enter a mandatory reason, and confirm. Status → **REJECTED**. The requisitioner is notified via the audit trail.

### 1.4 Convert a requisition

An approved requisition can be converted into either a Purchase Order or an RFQ:

1. Open the approved requisition.
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

## 2. RFQ (Request for Quotation)

An RFQ invites one or more suppliers to submit prices for a defined list of items.

### 2.1 Create an RFQ

An RFQ can be created directly or by converting an approved requisition (see section 1.4).

**To create directly:**

1. Navigate to **Procurement → RFQs** and click **New RFQ**.
2. Set the **Response Due Date** and optionally add notes.
3. In the **Invited Suppliers** section, pick each supplier by name. Invite at least one supplier.
4. Add lines: pick each product by name, choose a unit, and enter the required quantity.
5. Click **Save**. The RFQ is created in **DRAFT**.

### 2.2 Send an RFQ to suppliers

1. Open the DRAFT RFQ.
2. Click **Send**. Status → **SENT**. Suppliers are notified that they should submit a quote.

### 2.3 Capture supplier quotes

When a supplier responds with a price:

1. Open the SENT RFQ.
2. Click **Capture Quote**.
3. Pick the **Supplier** by name (only invited suppliers are listed).
4. Optionally set a valid-until date, lead time in days, and notes.
5. For each RFQ line, enter the **Quoted Quantity** and **Unit Price**.
6. Click **Save**. The quote is recorded with status **RECEIVED** and the RFQ status moves to **QUOTES_RECEIVED**.

Repeat for each responding supplier. You can compare their prices side-by-side in the quotes panel on the RFQ detail page.

### 2.4 Award the RFQ

To select the winning supplier and create a Purchase Order:

1. In the quotes panel, identify the preferred quote (usually the lowest compliant price).
2. Click **Award** on that quote row.
3. The winning quote status changes to **AWARDED** and all other quotes become **NOT_AWARDED**. The RFQ status changes to **AWARDED**.
4. A **Purchase Order** is created in DRAFT from the awarded quote lines and prices. A link to the PO is shown.

### 2.5 Cancel an RFQ

Open the RFQ and click **Cancel**. Status → **CANCELLED**. An awarded RFQ cannot be cancelled.

### 2.6 RFQ status reference

| Status | Meaning |
|---|---|
| DRAFT | Being prepared |
| SENT | Sent to suppliers; awaiting responses |
| QUOTES_RECEIVED | At least one supplier quote captured |
| AWARDED | Winning supplier selected; PO created |
| CANCELLED | Cancelled |

---

## 3. Purchase Orders

A Purchase Order (PO) is the formal commitment to buy from a supplier. POs are created from a converted requisition or from an awarded RFQ. There is no standalone "New PO" form in the UI.

### 3.1 View and manage a DRAFT Purchase Order

1. Navigate to **Procurement → Purchase Orders**.
2. Open the DRAFT PO.
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

1. Open the PO (status ORDERED, PARTIALLY_RECEIVED, or RECEIVED).
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

## 4. Goods Receipt

A goods receipt (GR) records the physical arrival of goods from the supplier. Creating a GR increases stock and updates the PO outstanding quantities.

### 4.1 Receive goods

1. Navigate to **Procurement → Goods Receipts** and click **New Goods Receipt**.
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

## 5. Landed Costs

Landed costs allocate incidental import charges (freight, duty, insurance, clearing fees, and other charges) to the items received. Landed costs are applied to one or more goods receipts and allocated to individual GR lines.

### 5.1 Create a landed cost

1. Navigate to **Procurement → Landed Costs** and click **New Landed Cost**.
2. Select the **Allocation Basis**:
   - **By Value** — charges are spread proportionally to the value of each GR line.
   - **By Quantity** — charges are spread proportionally to the quantity received on each GR line.
3. Pick the **Goods Receipt(s)** by GRN number. You can include multiple GRNs in one landed cost document.
4. Add one or more **Charges**: select the charge type (Freight, Duty, Clearing, Insurance, or Other) and enter the amount.
5. Click **Save**. The landed cost is created in **DRAFT**.

### 5.2 Confirm a landed cost

Confirming allocates the charges to the GR lines and posts the cost adjustment to the GL.

1. Open the DRAFT landed cost.
2. Click **Confirm**.
3. Status → **CONFIRMED**. The allocation per GR line is shown in the detail.

A confirmed landed cost cannot be edited. If there is an error, contact your administrator.

### 5.3 Landed cost status reference

| Status | Meaning |
|---|---|
| DRAFT | Being prepared; charges editable |
| CONFIRMED | Charges allocated; GL posted; immutable |

---

## 6. Supplier Bills and 3-Way Bill Match

A supplier bill is the invoice received from the supplier. It is entered into the system and then matched against the Purchase Order and Goods Receipt to verify quantities and prices before payment is approved.

### 6.1 Enter a supplier bill

1. Navigate to **Procurement → Supplier Bills** and click **Enter Bill**.
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

1. Open the bill (or use the **Match** action on the bills list).
2. Review the variance amount and percentage shown on the held line.
3. If the variance is acceptable, click **Accept Variance** on that line.
4. When all held lines are resolved, the bill status moves to **MATCHED**.

Accepting variances requires the `AP.BILL.MATCH` permission.

### 6.4 Re-run match from the bills list

For a bill that was entered without running a match (or needs re-matching after a correction):

1. Navigate to **Procurement → Supplier Bills**.
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

Payments against supplier bills are managed in the Accounts Payable module. See the Finance chapter for details on recording and reconciling AP payments.

---

## 7. Purchase Returns

A purchase return records goods being sent back to the supplier (for example, damaged or incorrect items received). Creating a confirmed return decreases stock and notifies the AP module to expect a supplier credit.

### 7.1 Create a purchase return

1. Navigate to **Procurement → Purchase Returns** and click **New Purchase Return**.
2. Pick the **Goods Receipt** by GRN number (the GR must have status RECEIVED).
3. Enter a mandatory **Reason**.
4. For each line being returned, enter the **Returned Quantity** (cannot exceed the quantity originally received on that GR line).
5. Click **Save**. The return is created in **DRAFT**.

### 7.2 Confirm a purchase return

Confirming the return physically ships the goods back and adjusts stock.

1. Open the DRAFT purchase return.
2. Click **Confirm**.
3. Status → **CONFIRMED**. Stock is removed from the branch and a purchase return event is posted.

### 7.3 Purchase return status reference

| Status | Meaning |
|---|---|
| DRAFT | Being prepared; quantities editable |
| CONFIRMED | Return confirmed; stock decremented; supplier debit notified |

---

## 8. Purchase Settings

Purchase settings control the PO approval workflow. Navigate to **Procurement → Purchase Settings** to view or edit them.

### 8.1 PO approval threshold

| Setting | Description |
|---|---|
| PO Approval Enabled | When turned on, Purchase Orders above the threshold amount require approval before goods can be received |
| PO Approval Threshold | The minimum order total that triggers the approval requirement |
| Currency | The currency of the threshold amount |

To change these settings, click **Edit**, update the values, and click **Save**.

When PO approval is enabled, a user with `PURCHASE.ORDER.APPROVE` must approve or reject POs that exceed the threshold.

---

## 9. End-to-end procure-to-pay example

The following steps illustrate a complete P2P cycle for a stock purchase:

1. **Requisition** — a department raises a purchase requisition for 100 bags of cement.
2. **Submit and Approve** — the requisition is submitted and approved by the purchasing manager.
3. **Convert to RFQ** — the approved requisition is converted to an RFQ.
4. **Send RFQ** — the RFQ is sent to two shortlisted suppliers.
5. **Capture quotes** — prices are received from both suppliers and entered as supplier quotes.
6. **Award** — the cheaper supplier is awarded the RFQ; a Draft PO is created automatically.
7. **Place PO** — the PO is placed (status: ORDERED; PO number assigned).
8. **Receive goods** — when the cement arrives at the warehouse, a goods receipt is created for the delivered quantity. The PO status updates to RECEIVED.
9. **Landed cost** — freight and duty charges for the shipment are entered as a landed cost against the GR and confirmed.
10. **Enter supplier bill** — the supplier's invoice is entered against the PO. The 3-way match confirms quantities and prices match.
11. **AP payment** — the matched and approved bill is paid through the Accounts Payable module.
12. **Purchase return** (if needed) — any damaged bags are returned to the supplier by creating and confirming a purchase return against the GR.

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

An opening balance sets the initial quantity for a product that has never had any movement at this branch. Use this task at go-live or when adding a new branch or product.

1. On the on-hand list, click **Opening Balance**.
2. Pick the product from the picker (search by name or code).
3. Enter the quantity. Must be greater than zero.
4. Optionally add a note such as `go-live`.
5. Click **Submit**.

**Important.** The system rejects an opening balance if the product already has any prior movement at the active branch. A second opening balance on the same product at the same branch is not permitted. To adjust existing stock, use the Adjust flow (section 2.2).

### 2.4 Setting a reorder level

A reorder level triggers the Low Stock flag when the on-hand quantity reaches or falls below it.

1. On the on-hand list, click the inline edit icon in the **Reorder Level** column.
2. Enter a positive number and save. To remove the reorder level, clear the field and save.

The Low Stock flag recalculates immediately after saving.

### 2.5 Viewing movement history

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

A stock location is a named physical area within a branch where stock is stored. Every stock movement and count is associated with a location.

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

## 5. Stock counts

Navigate to **Inventory > Stock Counts** (`/admin/stock-counts`).

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

## 6. Batches and lot tracking

Navigate to **Inventory > Stock Batches** (`/admin/stock/batches`).

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

### 8.1 Valuation report

Navigate to **Inventory > Valuation** (`/admin/stock/valuation`). Requires the `INVENTORY.VALUATION.VIEW` permission.

The report shows every stockable product with its average cost, quantity, and calculated inventory value. A reconciliation bar at the top compares the sum of on-hand values (the stock ledger) against the GL inventory account balance:

- **Reconciled to GL** (green) — the stock ledger and GL agree.
- **Does not reconcile** (red) — there is a discrepancy. The difference amount is shown. Finance review is required.

### 8.2 Setting an opening valuation

Navigate to **Inventory > Opening Valuation** (`/admin/stock/valuation/opening`). Requires the `INVENTORY.OPENING.SET` permission.

Use this screen to assign an initial cost to products that have a quantity on-hand but no established average cost.

1. The screen lists all on-hand rows that are currently unvalued.
2. Find the product row and enter the **Opening Cost per unit**.
3. Click **Submit**.

The system posts a GL entry (DR Inventory / CR Opening Balance Equity) and the product's average cost is established. Opening valuation is a one-time operation per on-hand row. Once a row has been valued it no longer appears on this screen.

---

## 9. Bills of Materials

Navigate to **Manufacturing > Bills of Materials** (`/admin/boms`).

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

Operations represent discrete production steps (e.g. Cutting, Assembly) with associated labour and overhead cost estimates. They can be added to a Work Order at any status before it is Closed or Cancelled.

- **Add operation**: Enter sequence number, description, work centre, and optional labour/overhead amounts. Click **Submit**.
- **Remove operation**: Click **Remove** on an operation row. An operation that has already had costs applied to it cannot be removed.

### 10.4 Releasing a Work Order

Releasing a Work Order locks the BOM and generates the component plan.

1. Open a Planned Work Order.
2. Click **Release**.
3. Optionally override the BOM via the picker.
4. Confirm.

Status changes to **Released**. The system emits a production event. No stock movements or GL entries are posted yet.

**Validation.** The finished product must have an Active BOM (or a BOM must be pinned). Releasing requires the `WORKORDER.RELEASE` permission.

### 10.5 Issuing components

Issuing deducts the component materials from stock and accumulates costs in the Work-in-Progress (WIP) account.

1. Open a Released or In-Progress Work Order.
2. Enter the **Posting Date**.
3. Click **Issue Components**.

The system issues all un-issued component lines simultaneously (full issue). Status moves to **In-Progress** on the first issue.

Stock movements of type `PRODUCTION_ISSUE` are posted for each component. GL entries: DR WIP / CR Inventory.

**Validation.** Posting date is required. If a component's average cost is not yet established, that component is cost-skipped (the quantity still moves but no GL leg is posted). An incomplete-cost indicator appears on the Work Order header when any component was cost-skipped.

### 10.6 Applying labour and overhead costs

1. Open a Released or In-Progress Work Order.
2. In the **Apply Cost** section, enter a **Labour Amount** and/or an **Overhead Amount** and a **Posting Date**.
3. Optionally link the cost to a specific operation via the Operation picker.
4. Click **Submit**.

GL entries: DR WIP / CR the relevant cost account. An operation can only have costs applied to it once; a second attempt is rejected.

### 10.7 Completing a Work Order

Completing records the finished goods receipt and calculates the unit cost.

1. Open an In-Progress Work Order.
2. In the **Complete** section, enter **Good Quantity** produced, **Scrap Quantity** (if any), and a **Posting Date**.
3. If the combined good and scrap quantities exceed the planned quantity, tick **Allow Over-run**.
4. Click **Submit**.

Status changes to **Completed**. A `PRODUCTION_RECEIPT` stock movement is posted for the finished goods. The computed unit cost is the total WIP debit divided by the good quantity. GL entries: DR Finished Goods / CR WIP.

**Validation.** Good quantity must be positive. If good + scrap exceeds planned quantity and Allow Over-run is not ticked, the submission is rejected.

### 10.8 Closing a Work Order

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

---

# Fixed Assets

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

Placing an asset in service capitalises it: the system posts a GL entry and generates the depreciation schedule.

1. Open a **Draft** asset.
2. Click **Place in Service**.
3. Enter the **Posting Date** (must fall in an open fiscal period).
4. Confirm.

Status changes to **In Service**. A capitalisation GL entry is posted (DR Asset Account / CR Cash or AP Clearing). The depreciation schedule is generated for the full useful life.

**Validation.** Posting date is required. The fiscal period containing the posting date must be open. The action is available only on Draft assets.

---

## 5. Transferring an asset

A transfer changes the branch or cost centre of an asset without affecting its financial values. No GL entry is posted.

1. Open a **Draft** or **In Service** asset.
2. Click **Transfer**.
3. Enter the target **Branch ID** and optionally a new **Location** and **Cost Centre ID**.
4. Confirm.

The asset's branch and location are updated immediately. Disposed and Written-Off assets cannot be transferred.

> **Note.** The transfer form accepts branch and cost centre as typed numeric IDs rather than pickers. Check with your system administrator for the correct numeric IDs if you do not know them.

---

## 6. Depreciation

### 6.1 Supported methods

| Method | Behaviour |
|---|---|
| **Straight Line** | Equal charge each period: (Acquisition Cost − Salvage Value) / Life Periods |
| **Reducing Balance** | Percentage of the closing book value each period: NBV × Reducing Rate |

### 6.2 Previewing a depreciation run

Before posting, preview the run to see what charges will be created.

1. Navigate to **Fixed Assets > Run Depreciation** (`/admin/depreciation-runs/post`).
2. Select the **Company**.
3. Enter the **Fiscal Period UID** for the period you want to depreciate.
4. Click **Preview**.

The preview table lists each eligible asset with its planned charge for the period, plus a total. Nothing is posted.

### 6.3 Posting a depreciation run

After reviewing the preview:

1. Enter the **Posting Date** (must fall within the selected open fiscal period).
2. Click **Post**.

The system creates a depreciation run with status **Posted** and a run number (e.g. `DEPR-0001`). A single consolidated GL entry is posted covering all eligible assets. Each asset's accumulated depreciation balance increases. The schedule lines for the period are marked as posted.

**Validation.** Only one depreciation run is allowed per company per fiscal period. Attempting a second run for the same period is rejected.

### 6.4 Viewing depreciation runs

Navigate to **Fixed Assets > Depreciation Runs** (`/admin/depreciation-runs`). The list shows all posted runs in reverse date order. Click a run to see the detail, which includes per-asset lines showing the charge amount, accumulated depreciation after the run, and NBV after the run.

---

## 7. Revaluing an asset

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

Use this option when the asset is scrapped, lost, or fully impaired and no proceeds are received.

1. Open an **In Service** asset.
2. Click **Write Off**.
3. Enter the **Write-Off Date** and an optional reason.
4. Confirm.

The loss equals the full NBV at the write-off date (proceeds are forced to zero). The same final-period depreciation logic applies. Status changes to **Written Off**.

---

## 9. FA to GL reconciliation

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

## 8. Project P&L

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

## 9. Cross-project WIP report

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

### Chart of Accounts

The Chart of Accounts (CoA) is the master list of all GL accounts for your company. Navigate to **Accounting > Chart of Accounts** (`/admin/gl/accounts`).

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

Manual journal entries let you record corrections, accruals, and adjustments directly to the GL. Navigate to **Accounting > Journals** (`/admin/gl/journals`) and click **Post journal** (`/admin/gl/journals/post`).

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

Corrections to a posted journal are always made by **reversal** — a new entry with every line's debit and credit swapped. The ledger is append-only; the original entry is never modified.

**To reverse a journal (requires `GL.POST`):**

1. Open the journal detail from **Accounting > Journals**.
2. If the entry has `Source Type = MANUAL` and is not itself a reversal, the **Reverse** button is visible.
3. Click **Reverse**. A new journal is created immediately (using today as the reversal date) with all amounts swapped. The reversal entry links back to the original via its `Reversal Of` field.

> System-posted entries (source types such as SALES, OPENING\_BALANCE, YEAR\_END\_CLOSE) cannot be reversed here. Correct those through their originating module.

---

### Fiscal Periods (Open/Close)

The fiscal calendar determines which dates are available for posting. Navigate to **Accounting > Fiscal Periods** (`/admin/gl/periods`).

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

The Trial Balance report summarises every GL account's total debits and credits. Navigate to **Accounting > Trial Balance** (`/admin/gl/trial-balance`).

- Select your company (if multi-company).
- Optionally select a specific **fiscal period** to view only that period's movements.
- The table groups accounts by type in canonical order (ASSET, LIABILITY, EQUITY, INCOME, EXPENSE) and shows each account's code, name, total debit, total credit, and net balance.
- The footer shows total debits, total credits, and a **Balanced** indicator. A balanced set of books shows equal debits and credits.

Permission required: `GL.VIEW`.

---

### GL Posting-Account Config

The GL Config maps system roles (e.g. "accounts receivable control account") to specific accounts in your CoA. Navigate to **Accounting > GL Config** (`/admin/gl/config`).

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

Dimension types (Cost Centre, Department) allow you to tag journal lines for management reporting. Navigate to **Accounting > Cost Centre > Dimensions** (`/admin/cost-centre/dimensions`).

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

The year-end close posts a closing journal that zeros all income and expense accounts and transfers the net profit (or loss) to the **Retained Earnings** account. Navigate to **Accounting > Year-End Close** (`/admin/gl/year-end`).

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

AR tracks amounts owed to your company by customers. Open items (invoices) are created automatically when a sales invoice is finalised, or manually via the opening-balance screen.

### AR Invoices (Open Items)

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

A credit note reduces a customer's outstanding balance. It is raised from the invoices list. Permission required: `AR.CREDITNOTE`.

1. On **Accounting > Receivables**, find the target invoice row.
2. Click **Credit note** (visible only when `AR.CREDITNOTE` is held).
3. In the modal, enter the net amount, VAT amount, and reason.
4. Submit. The invoice outstanding is reduced and a GL contra posting is made.

---

### Write-Offs

A write-off removes an uncollectable balance from AR. Permission required: `AR.WRITEOFF`.

1. On **Accounting > Receivables**, find the OPEN or PARTIAL invoice.
2. Click **Write off**.
3. Enter a reason and confirm the date.
4. Submit. The invoice moves to WRITTEN\_OFF status; the outstanding balance is posted to the Bad Debt Expense account.

Invoices already PAID or WRITTEN\_OFF cannot be written off again.

---

### AR Opening Balances

To load balances brought forward from a prior system, navigate to **Accounting > AR Opening Balance** (`/admin/ar/opening-balance`). Permission required: `AR.OPENING.SET`.

1. Pick the customer by name.
2. Enter the original amount, currency, invoice date, and an optional due date and document number.
3. Submit. An opening-balance invoice (source = `OPENING_BALANCE`) is created and posted to the AR control account.

---

### Customer Statements and Ageing

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

AP tracks amounts your company owes to suppliers. Only users with the appropriate AP permissions can access this module. By default, only the ORG\_ADMIN role is granted AP permissions.

### Entering a Supplier Bill

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

A debit note reduces the amount owed to a supplier. Raised from the payables list. Permission required: `AP.DEBITNOTE`.

1. On **Accounting > Payables**, find a MATCHED, APPROVED, or PARTIALLY\_PAID bill.
2. Click **Debit note**.
3. Enter the note date, net amount, optional VAT, and reason.
4. Submit. The bill outstanding is reduced and the GL posts DR AP / CR Purchases.

---

### AP Opening Balances

Navigate to **Accounting > AP Opening Balance** (`/admin/ap/opening-balance`). Permission required: `AP.OPENING.SET`.

1. Pick the supplier by name.
2. Enter the gross amount, bill date, due date, and optional supplier invoice number.
3. Submit. An opening-balance supplier bill is created (source = `OPENING_BALANCE`).

---

### Supplier Statement

Navigate to **Accounting > Supplier Statement** (`/admin/ap/statement`). Permission required: `AP.VIEW`.

Pick a supplier by name to view:

- **Outstanding balance** — total of unpaid bills.
- **Ageing breakdown** — same bucket structure as AR (Current, 1–30, 31–60, 61–90, 90+).
- **Open bills** — all bills with a remaining balance.
- **Reconciliation** — compares the AP sub-ledger total against the GL AP control account. A zero difference confirms the books are in agreement. A non-zero difference is a finance-grade discrepancy requiring investigation.

---

## Cash & Bank

### Cash and Bank Accounts

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

To move funds between two accounts, navigate to **Accounting > Cash Transfer** (`/admin/cash/transfers/record`). Permission required: `CASH.TRANSFER`.

1. Select the **Source account** and **Destination account** from the pickers (by code — name). Source and destination must differ.
2. Enter the **amount**, **transfer date**, and an optional **reference**.
3. Submit. A transfer number (`CBT-####`) is generated. The GL posts a balanced entry covering the two accounts.

View the transfers list at **Accounting > Transfers** (`/admin/cash/transfers`). Click a row to see the transfer detail.

---

### Direct Cash/Bank Entries

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

Navigate to **Accounting > Cash Statement** (`/admin/cash/statement`). Permission required: `CASH.VIEW`.

Select an account by name to view:

- **Current balance** — the running book balance.
- **Transaction history** — each cash transaction in date order with a running balance column (IN transactions increase the balance; OUT transactions decrease it).
- **GL reconciliation** — compares the account's book balance against the linked GL asset account balance. A zero difference confirms agreement. A non-zero difference requires investigation.

---

## Tax

### VAT Returns

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

### Maintaining Currencies and Rates

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

When you enter a sales invoice, supplier bill, or receipt in a foreign currency (e.g. USD), the system automatically converts all GL postings to the company base currency (TZS) using the effective SPOT rate for the document date. The document stores the face amounts in the foreign currency; all GL ledger entries are in TZS.

If no rate exists for the document's currency on or before the document date, the posting is rejected with a rate-not-found error.

---

### Period-End Revaluation Run

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

**Navigation:** Shell > CRM group — Leads, Opportunities, Pipeline Dashboard, Pipeline Stages, CRM Activities.

Each item in the CRM nav group is hidden if you do not have the required permission. The sections below state the required permission for each action.

---

## Leads

**Navigation:** CRM > Leads | **View:** `CRM.LEAD.VIEW` | **Create / edit / contact / disqualify:** `CRM.LEAD.MANAGE` | **Qualify:** `CRM.LEAD.QUALIFY`

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

1. Navigate to **CRM > Leads**.
2. Click **New Lead**. An inline form appears.
3. Enter the **Display Name** (required).
4. Select the **Lead Source** from the dropdown (Website, Referral, Walk-in, Campaign, Cold Call, Existing Customer, or Other).
5. Optionally enter Company Name, Contact, Phone, Email, and Notes.
6. Click **Submit**.

The system assigns a **Lead Number** (for example, `LEAD-0001`) and sets the status to **New**. The lead is stamped with your active branch.

### How to mark a lead as contacted

1. Open the lead from the list.
2. Click **Mark as Contacted** (only available when status is New).
3. The status changes to **Contacted**.

### How to qualify a lead

Qualifying a lead links it to a customer record and moves it to **Qualified** status. You need the `CRM.LEAD.QUALIFY` permission.

1. Open a New or Contacted lead.
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

1. Open any non-terminal lead (New, Contacted, or Qualified).
2. Click **Disqualify**.
3. Enter a **Reason** (required — for example, "Budget too low" or "Not the right fit").
4. Click **Submit**.

The status changes to **Disqualified**. The reason is stored and displayed on the detail page.

### Editing a lead

Open the lead detail page and change any editable fields (display name, source, contact details, notes). Click **Save**. Editing is not available once the lead is Converted or Disqualified.

### Searching leads

On the Leads list, the search box filters by name. Pagination controls appear when the list exceeds 20 rows. Use the NEXT / PREVIOUS / page-number / FIRST / LAST controls to move between pages.

---

## Opportunities

**Navigation:** CRM > Opportunities | **View:** `CRM.OPPORTUNITY.VIEW` | **Create / edit / stage / win / lose:** `CRM.OPPORTUNITY.MANAGE` | **Convert to document:** `CRM.OPPORTUNITY.CONVERT`

### Opportunity status lifecycle

```
OPEN → WON
OPEN → LOST
```

Once an opportunity is Won or Lost it is closed. Closed opportunities cannot be edited, and lines cannot be added or removed. Conversion to a quotation or sales order is still available on a closed opportunity (with the restrictions described below).

### How to create an opportunity

1. Navigate to **CRM > Opportunities**.
2. Click **New Opportunity** (or navigate to **CRM > Opportunities > Create**).
3. Select the **Customer** using the picker. Type part of the customer name to search; select from the results.
4. Select the **Pipeline Stage** from the dropdown. Only active stages are offered. The stage's default win probability is applied automatically unless you override it.
5. Enter the **Title** (required).
6. Select the **Currency** (defaults to TZS).
7. Optionally enter an **Estimated Value**, **Expected Close Date**, and **Win Probability** override.
8. Optionally select a **Source Lead** using the picker — only Qualified leads appear in this list. Selecting a source lead converts that lead to **Converted** status.
9. Click **Submit**.

The opportunity is created with status **Open** and an automatically assigned number (for example, `OPP-0001`). You land on the opportunity detail page.

### How to add lines to an opportunity

Lines represent the products or services you expect to sell. You can add them while the opportunity is Open.

1. Open the opportunity detail page.
2. In the **Lines** section, type a product name into the search box and select the product (shown as `code — name`).
3. Select the **Unit** from the units dropdown.
4. Enter the **Quantity** (must be greater than zero).
5. Optionally enter the **Unit Price** and a **Discount %** (0–100).
6. Click **Add**.

To remove a line, click **Remove** on the row.

### How to advance the pipeline stage

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

## Pipeline Dashboard

**Navigation:** CRM > Pipeline Dashboard | **Permission:** `CRM.PIPELINE.VIEW`

The pipeline dashboard shows the current state of all open opportunities across your sales pipeline. It is scoped to a company and branch — select both to load the data.

### Board summary

The board shows each active pipeline stage with the count of open opportunities in that stage and their combined estimated value.

### Weighted forecast

The forecast section calculates expected revenue for a date range, weighting each opportunity's estimated value by its win probability. Set the **From** and **To** dates and click **Apply**.

### Win-rate and cycle-time KPIs

The KPI panel shows:
- **Win Rate** — the percentage of closed opportunities marked Won in the selected period.
- **Average Cycle Time** — the average number of days from opportunity creation to close.

Set the date range and click **Apply** to recalculate.

---

## Pipeline Stages (Settings)

**Navigation:** CRM > Pipeline Stages | **Permission to view the settings screen:** `CRM.STAGE.MANAGE` | **Permission to read stages via API:** `CRM.OPPORTUNITY.VIEW`

Pipeline stages define the steps in your sales process. Five stages are seeded per company: Qualification, Needs Analysis, Proposal, Negotiation, and Closing. You can add, rename, reorder, change probabilities, and deactivate stages.

### How to create a stage

1. Navigate to **CRM > Pipeline Stages**.
2. Click **New Stage**.
3. Enter the **Name** (must be unique within the company).
4. Enter the **Display Order** (a number; must be unique within the company).
5. Enter the **Default Probability** (0–100).
6. Click **Submit**.

### How to edit a stage

Click **Edit** on a row. Change the name, display order, default probability, or the **Active** toggle. Click **Save**.

### How to deactivate a stage

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

**Navigation:** CRM > CRM Activities (open-task inbox) | Activities are also embedded on Lead and Opportunity detail pages.

**View activities:** `CRM.ACTIVITY.VIEW` | **Log / complete activities:** `CRM.ACTIVITY.MANAGE`

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

1. Open the lead or opportunity detail page.
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

A task can be completed from the open-task inbox or from the activity panel on the parent lead or opportunity.

1. Find the task (either on the detail page or in **CRM > CRM Activities**).
2. Click **Complete** on the task row.

The task is marked done and disappears from the open-task inbox. You cannot complete an activity that is not a Task, and you cannot complete a Task that is already done.

### Open-task inbox

**Navigation:** CRM > CRM Activities | **Permission:** `CRM.ACTIVITY.VIEW` (view) / `CRM.ACTIVITY.MANAGE` (complete)

The CRM Activities screen lists all open (not-yet-done) Tasks for the selected company, across all leads and opportunities. It is scoped to the company you select; you can optionally filter by assignee.

The list is paginated (20 per page). Use the paginator controls to browse. When you complete a task, it is removed from the inbox and the list refreshes.

---

# Reporting and Business Intelligence

This chapter describes the financial statements, the GL account-ledger drill-down, and the analytics dashboard. All reports are read-only and computed on demand — nothing is stored or posted when you run a report.

---

## Financial Statement Reports

The four financial statements are available from the **Reporting** navigation group. Each report requires the relevant permission and a company and period selection before it can be run.

**Common controls on every statement screen:**

- **Company selector** — if your organisation has more than one company, choose which company to report on by name.
- **Period inputs** — date fields specifying the reporting period.
- **Run button** — computes and displays the statement.
- **Export buttons** (PDF, Excel, CSV) — download the statement in the chosen format. Requires the additional `REPORT.EXPORT` permission. The buttons are hidden if you do not hold that permission.
- **Comparative period** — most statements accept an optional comparative period or date to populate a second column.
- **Reconciliation indicator** — a green **"Reconciled"** bar confirms the computed figures tie back to the underlying GL movement. A red **data-integrity alarm** means the figures do not agree and the books require investigation; the report is shown but no automatic correction is made.

---

### Profit & Loss (Income Statement)

Navigate to **Reporting > Income Statement** (`/admin/reporting/income-statement`). Permission required: `REPORT.PL.VIEW`.

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

### Balance Sheet

Navigate to **Reporting > Balance Sheet** (`/admin/reporting/balance-sheet`). Permission required: `REPORT.BS.VIEW`.

1. Select the company by name.
2. Set the **As-at date**.
3. Optionally set a **Compare as-at** date to add a prior-date column.
4. Click **Run**.

The statement shows sections for Current Assets, Non-Current Assets, Current Liabilities, Non-Current Liabilities, and Equity, each with detail lines, subtotals, and three totals: **Total Assets**, **Total Liabilities**, **Total Equity**. A balanced set of books shows **Total Assets = Total Liabilities + Total Equity** (green reconciliation bar).

**Drill-through:** click any real account name link to open the Account Ledger for that account as at the selected date.

**Export:** file is named `balance-sheet_<asAt>.<ext>`.

---

### Cash-Flow Statement

Navigate to **Reporting > Cash-Flow Statement** (`/admin/reporting/cash-flow`). Permission required: `REPORT.CASHFLOW.VIEW`.

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

### Account-Ledger Drill-Down

Navigate to **Reporting > Account Ledger** (`/admin/reporting/account-ledger`). Permission required: `REPORT.LEDGER.VIEW`.

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

### Trial Balance

The Trial Balance is covered fully in the Finance chapter (Accounting > Trial Balance, `/admin/gl/trial-balance`). It can also be reached from the Reporting navigation group. Permission required: `GL.VIEW`. See the General Ledger section for full usage.

---

## Business Intelligence Dashboard

Navigate to **Dashboard** (`/admin/dashboard`). Permission required: `BI.VIEW`.

The dashboard is a composite view of key performance indicators drawn from Finance, Operations, and CRM data. Each panel loads independently and has its own permission. If you hold `BI.VIEW` but lack a panel-specific permission, that panel shows a calm "no permission" message rather than blocking the whole page.

**Filters at the top of the page:**

- **Company** — the active company (determined by your login context).
- **Branch** — optionally filter data to a specific branch (chosen by code — name).
- **From / To dates** — the reporting date range (defaults to the current month). Change dates and click the **Refresh** button to re-fetch all panels.

---

### KPI Panels

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

The following specialised reports sit under the **Budgeting** and **Cost Centre** navigation groups but are described here because they are reporting outputs, not data-entry screens.

### Budget Variance Report

Navigate to **Budgeting > Budget Variance** (`/admin/budgeting/variance`). Permission required: `BUDGETING.REPORT.VIEW`.

The report compares GL actuals against an approved budget version.

1. Select the company by name.
2. Enter the **Fiscal Year UID** (available from the budget detail screen).
3. Set the **Period range** (1–12; from must be ≤ to).
4. Optionally filter by **Account Type** (Income, Expense, Asset, Liability, Equity) and enter a cost-centre value UID.
5. Click **Run**.

The report shows account-level rows with budget amount, actual amount, variance, and a **Favourable** or **Adverse** label. For income accounts, actual > budget is favourable. For expense accounts, actual < budget is favourable.

### Departmental Actuals Report

Navigate to **Budgeting > Departmental Actuals** (`/admin/budgeting/departmental-actuals`). Permission required: `BUDGETING.REPORT.VIEW`.

Shows actual GL postings broken down by cost centre and account for the chosen fiscal year and period range. The inputs are the same as the variance report. This report has no budget comparison — it shows actuals only, useful for analysing spending by department or cost centre.

### Dimension-Sliced Trial Balance

Navigate to **Accounting > Cost Centre > Report** (`/admin/cost-centre/report`). Requires both `COSTING.VIEW` and `GL.VIEW`.

See the General Ledger section (Cost-Centre Dimensions) in the Finance chapter for full usage instructions.

---

# HR & Payroll, Budgeting, and Platform Services

This chapter covers three cross-cutting domains: the Human Resources and Payroll module (departments, employees, contracts, leave, loans, pay components, payroll runs, and statutory setup), the Budgeting module (budget creation, version lifecycle, line entry, and management reports), and the Platform services used by all other modules (document generation, notifications, the approval engine, and the audit trail).

---

## Part 1 — Human Resources and Payroll

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

Navigate to **HR & Payroll > Statutory Setup** (`/admin/hr/statutory`). Requires `HR.STATUTORY.MANAGE`.

The statutory setup screen shows two sections: **PAYE band sets** and **Statutory rate sets**. These sets determine how income tax and levies are calculated during payroll calculation.

**Creating a PAYE band set:**

1. Click **New PAYE band set**.
2. Enter an **Effective from** date, a **Tax-free threshold** (the monthly income amount below which no PAYE applies), and an optional description.
3. Add one or more bands. Each band requires: band number (ascending), lower bound (monthly income where this rate starts), marginal rate (decimal, e.g. `0.20` for 20%), and cumulative fixed tax (the tax already accumulated on income up to this band's lower bound).
4. Click **Save**.

The system uses the **most recently effective** band set whose effective date is on or before the payroll run's pay date.

**Creating a statutory rate set:**

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

1. In the **Account** picker, choose the GL account by name.
2. Enter the **Annual amount** in TZS.
3. Click **Replace Lines**. The system creates 12 lines (one per period), spreading the annual amount as evenly as possible (HALF_UP rounding; any remainder is added to the last period so the sum equals the annual total exactly).

The fiscal year must have exactly 12 periods to use ANNUAL\_SPREAD mode.

**SEED — copy lines from another version:**

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

Platform services provide cross-cutting functionality used by all modules: document generation and management, notifications, the approval engine, and the audit trail.

---

### Documents

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

Navigate to **Documents > Document Templates** (`/admin/document-templates`). Requires `DOCUMENT.TEMPLATE.MANAGE`.

The template registry lists one row per renderable document type. You can change the template's **title** and toggle its **status** (ACTIVE or INACTIVE) by clicking the row and saving. Deactivating a template does not delete it.

#### Document Branding

Navigate to **Documents > Document Branding** (`/admin/document-branding`). Requires `DOCUMENT.BRANDING.MANAGE`.

The branding profile is a per-company singleton (one set of settings per company, no list). It controls what appears in the header and footer of rendered PDF documents.

1. Open the screen. The current branding values load into the form.
2. Edit: **Display name**, **Legal name**, **Tax ID**, **Address**, **Contact phone**, **Contact email**, **Website**, **Footer terms text**, and **Bank details**.
3. Click **Save**.

Changes take effect on all subsequent document renders. Previously generated documents are not changed (the log is append-only).

---

### Notifications

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

The approval engine intercepts specific actions in other modules (purchase order confirmation, payroll posting, budget submission, etc.) and routes them through a human-approval chain if a matching policy exists. If no policy matches, the action is auto-approved immediately.

Approval requests are created automatically by the relevant modules — there is no "create approval request" screen.

#### Approval Policies

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
