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
