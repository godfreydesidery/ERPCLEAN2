# For Supervisors and Store Managers

This chapter is for the people who run the floor rather than only ring sales: **shift supervisors** and **store managers**. It explains the jobs a plain cashier cannot do — approving refunds, discounts and drawer reports at a cashier's till, reconciling a session and reading its variance, freeing a till a cashier left open, keeping the person who counts the drawer separate from the person who signs off the count, running several tills, creating and retiring tills, and getting every cashier set up so they can actually sell. It ends with a step-by-step end-of-day close-out you can follow across all the tills in your store.

Read the earlier chapters first — you need to know how to sign in, open a shift, ring a sale, take payment and print a receipt before the supervisor jobs below make sense. Everything here uses the same screens; the difference is that your account carries extra rights, so OrbixPOS shows you actions a cashier never sees — or asks you to approve them on a cashier's till.

> OrbixPOS owns no money of its own. The **ERP server** is the single source of truth for price, VAT, totals and — the number you care about most here — the **cash variance** at the end of a shift. The figures OrbixPOS shows while a shift is open are previews; the variance posted to the books is the one the ERP works out when the session is closed and reconciled.

---

## What each role can and cannot do

**What roles are.** OrbixPOS has no hard-wired "cashier" and "manager" buttons. Every action is controlled by a named **permission** (a right) that your administrator grants to user accounts in the ERP. A **role** is a bundle of permissions. The ERP comes with standard roles — **Cashier**, **Sales Manager** and **Branch Manager** are the ones that matter at the till — and your administrator can adjust them.

**Why it exists.** Tills handle cash, and cash needs controls. Tying each sensitive action to its own permission lets the business have a cashier ring sales and close their own drawer, while reserving refunds, large discounts and signing off the count for someone more senior.

**How it shows on screen.** When you sign in, OrbixPOS asks the ERP what your account is allowed to do and shapes the screens to match:

- An action you are **not allowed** to do is simply **not shown** — there is no greyed-out button or padlock for it.
- An action you are allowed to do but that does not apply **right now** stays visible, dimmed, with the reason underneath (for example *Close the session first*).
- **One exception:** the **X-read** and **Z-read (reprint)** rows stay visible to anyone working the till, even a cashier whose role does not include drawer reports. For that cashier, opening a report asks a manager to approve it at the till (see *Manager approval at the till* below).

The table shows what the **standard** roles can do. If your administrator has changed the roles, your shop may differ.

| Capability | Cashier | Sales Manager | Branch Manager |
|---|---|---|---|
| Open a shift and ring sales | Yes | No | No |
| Record a **cash payout** (refund or paid-out) | Yes | No | No |
| **Close** a session (count the drawer) | Own shift | No | Yes — any session, in the ERP web app |
| Open the **X-read** and **Z-read (reprint)** without an approval | Yes | Yes | Yes |
| Look up and reprint from **Today's sales** | Yes | Yes | Yes |
| Start a **Refund / reverse** | Own shift, with a manager's approval | Yes, no second approval needed | Yes, no second approval needed |
| **Approve** a refund at a cashier's till | No | Yes | Yes |
| **Approve** a discount above the company limit | No | Yes | Yes |
| **Approve** printing a Z-read, or an X-read for a cashier without report rights | No | No | Yes |
| **Approve** leaving an unfinished sale unresolved | No | Yes | Yes |
| **Reconcile (Z-read)** — post the variance to the books | No | No | Yes |
| **Create** tills (**New till**) | No | No | Yes |
| Sell age-restricted items without the age confirmation | No | No | Yes |

> **A manager usually approves at the cashier's till rather than running one.** The standard Sales Manager and Branch Manager roles do not include opening a shift or ringing sales. A manager who also works a till needs the Cashier role as well — ask your administrator.

If you expect an action and it is not there, your account does not hold the permission for it. That is a deliberate control, not a fault. Ask your administrator to grant it if your job genuinely needs it.

---

## Manager approval at the till

**What it is.** Some actions a cashier can start but not finish alone. When the cashier gets to that point, OrbixPOS opens a **Manager approval** box on the cashier's own screen. A manager walks over, types **their own** username and password, and presses **Approve**. The cashier stays signed in the whole time.

**Why it exists.** It puts a second person on every action that moves money or closes a shift's figures — a refund, a big discount, the Z-read — without the cashier having to sign out and the manager sign in.

**How it works.**

1. The cashier's screen shows a box titled, for example, **Manager approval — refund**. Under the title is one line saying what is being approved (for example *Reverse this sale and return the money to the customer.*) and, usually, the specific receipt, item or amount.
2. The manager types their **Manager username** and **Manager password**.
3. The manager presses **Approve** (or **Cancel** to refuse).
4. If the details are accepted, the box closes and the action goes ahead. Where it applies, the manager's name is shown — for example *Approved by Peter Mollel* on a reversed receipt, or *Discount approved by …* under the discount.

The rules the ERP enforces every time:

- **A different person.** Nobody can approve their own action. If a manager is the one signed in, they cannot approve by typing their own password — they get *That user is not allowed to approve this action.*
- **The right permission.** The approver must genuinely hold the permission for that action (see the table below), in this company, and their account must be active.
- **One action only.** An approval covers that single action. Nothing is remembered — the next refund or report asks again. The manager is never signed in on the cashier's till.
- **Mistyped details** show *Those details were not accepted. Check the username and password and try again.* The box stays open so the manager can retype. After several failures in a row you see *Too many failed approval attempts. Please wait a moment and try again.*

### Which actions need a manager

| Action at the till | Box title | The approver needs | Standard roles that can approve |
|---|---|---|---|
| **Refund / reverse** a sale (when a cashier starts it) | **Manager approval — refund** | the invoice-void permission | Sales Manager, Branch Manager |
| A line discount above the company's limit | **Manager approval — discount** | the discount-override permission | Sales Manager, Branch Manager |
| Opening the **X-read** for a cashier whose role has no report rights | **Manager approval — X-read** | the session-reconcile permission | Branch Manager |
| Opening the **Z-read (reprint)** for a cashier whose role has no report rights | **Manager approval — Z-read** | the session-reconcile permission | Branch Manager |
| **Printing** a Z-read (any copy, by anyone) | **Manager approval — Z-read** | the session-reconcile permission | Branch Manager |
| **Leave unresolved…** on an unfinished sale whose outcome is unknown | **Manager approval — leave a sale unresolved** | the invoice-void permission | Sales Manager, Branch Manager |

Notes on the table:

- **Refunds.** A supervisor who holds the invoice-void permission and is running a till shift themselves is not asked for a second approval when they reverse a sale. A cashier may only refund sales from **their own** shift, even with an approval. See the *Receipts and Refunds* chapter (Chapter 6).
- **Discounts.** The discount limit is set per company in the ERP and is **off** unless your administrator switches it on. The till does not know the limit; the ERP checks it when the sale is completed. See the *Selling — Supermarket* chapter (Chapter 3).
- **Drawer reports.** By default the Cashier role *can* read its own X-read and Z-read, so the report approvals only appear if your shop has removed that right from cashiers. **Printing** a Z-read, however, always asks for an approval — including when a Branch Manager is the one who just reconciled it, because nobody can approve themselves. If you are the only manager on duty, view the Z-read in the ERP web app instead (see *Reconcile* below), or have a second manager approve the print.
- **Unfinished sales.** The till first asks the ERP one last time whether the sale went through. An approval is only asked for when the ERP still cannot say.
- **Age-restricted items** are not a manager approval: the cashier confirms the customer's age at **Complete sale**. A user whose role holds the age-override permission (Branch Manager by default) may complete the sale without that confirmation.

---

## Segregation of duties — why two people, not one

**What it is.** Splitting a sensitive task between two people so that no single person can both create a discrepancy and approve it. At the till, one person physically **counts the drawer** (closes the session), and a different, more senior person **reconciles** that count — confirming it and posting any shortfall or surplus to the accounts.

**Why it exists.** If the person who counted the cash also signed the count off into the books, a missing amount could be quietly absorbed with no second pair of eyes. Two people means every variance is reviewed by someone who did not handle the drawer. It protects honest staff from suspicion just as much as it deters dishonesty.

**How OrbixPOS supports it.**

- The Cashier role holds the permission to **close** a session but **not** to **reconcile** it. A cashier counts and closes their own drawer, and the **Reconcile (Z-read)** row never appears in their menu.
- The Branch Manager role holds the reconcile permission. **In practice the branch manager reconciles a cashier's session in the ERP web app**, on the **POS Sessions** screen. In OrbixPOS, **Reconcile (Z-read)** only appears for the person who closed the session on that till during the same sign-in — once the cashier signs out, the session can no longer be reached from the till.

> **Note.** The standard Branch Manager role can close **and** reconcile a session, so one person *can* do both. Good practice — and usually store policy — is still that a manager reconciles a drawer they did **not** count.

> **Reconciling is final.** It posts the variance to the general ledger and **cannot be undone**, and a reconciled session cannot be reopened or edited. Always confirm the counted figure with the cashier before you reconcile.

---

## Close, reconcile and the Z-read

**What reconcile (Z-read) is.** Reconcile is the end-of-shift step that **finalises** a cash session and posts its cash variance to the accounts. Its summary is the **Z-read** — the end-of-day "zeroing" report for that drawer. The variance is *counted cash minus expected cash*: a positive number means the drawer is **over**, a negative number means it is **short**.

### Reading the close screen (what the cashier sees)

When the cashier closes the session (**☰** › **Close session** › type the **Counted cash (TZS)** › **Close session**), OrbixPOS shows a **Session closed** panel. It is the same figure you are about to post:

| Line | Meaning |
|---|---|
| **Expected** | What the ERP calculates *should* be in the drawer. Under it: *Expected = float + cash sales − payouts (cash tenders only; card & mobile money settle separately).* |
| **Counted** | What the cashier counted and typed in. |
| **Variance** | Counted minus Expected. A green panel means the drawer balances or is over; a red panel means it is short. |

The panel ends with *Reconcile (Z-read) posts this variance — supervisor.* The cashier presses **Done**, then signs out (**☰** › **Sign out**). The session is now **CLOSED** and waiting for you.

### How to reconcile a cashier's session (ERP web app)

1. Confirm the counted figure with the cashier.
2. In the ERP web app, open **POS Sessions**. Filter by status **Closed** to see sessions waiting for you.
3. Open the session and check the figures.
4. Press **Reconcile** and confirm. The session moves to **RECONCILED** and the variance is posted.
5. The **Z-Read (Final Shift Report)** for the session is shown on the same page.

### Reconciling in OrbixPOS (when you ran and closed the shift yourself)

If your account holds the reconcile permission and you closed the session on this till yourself, without signing out:

1. Open the session menu (**☰**). **Reconcile (Z-read)** shows *Post the variance — this is final* (before the session is closed it reads *Close the session first*).
2. Tap it. A **Reconcile session** box says: *This finalises the session and posts the cash variance to the general ledger. This cannot be undone — but the Z-read can be reprinted afterwards, so nothing is lost if the paper jams.*
3. Tap **Reconcile**.
4. The **Z-read** appears. Read it top to bottom:

   | Z-read line | What it tells you |
   |---|---|
   | **Sales (all tenders)** | Total takings for the shift, all payment types. Under it, **By tender** splits it, for example *Cash 32,020.00 · Mobile 14,000.00*. |
   | **Opening float** | The cash the drawer started with. |
   | **Cash sales** | The cash part of the takings. |
   | **Payouts** | Cash paid out of the drawer (shown as a deduction), with a split such as *Refund (1)* and *Paid out (2)*. |
   | **Expected** | Float + cash sales − payouts. |
   | **Counted** | What was counted at close. |
   | **Variance** | Counted − Expected. Shown in red if the drawer was short. |
   | **N invoices** | How many sales the shift rang. |

5. To print it, tap **Print** — a different manager must approve the print (see *Manager approval at the till*).
6. Tap **Finish shift**. The till returns to the **Open shift** screen and is free for the next shift.

### Reprinting a Z-read

After reconciling on the till, **☰** › **Z-read (reprint)** (*The final figures for a reconciled session*) shows the same figures again, marked as a reprint: *This is a reprint. The figures are identical to the original — the Z-read is read-only and posts nothing.* Before reconciliation the row reads *Available once the session is reconciled*. Printing the reprint asks for a manager's approval, unless a manager already approved opening that copy.

> **Tip.** A small variance (a coin or two) is normal. A large or repeated variance on the same till or cashier is worth investigating. Use **Today's sales** to review the shift's sales, and the **X-read** earlier in the shift to see whether the drawer drifted at a particular time.

### If reconcile is missing or fails

| What you see | What to do |
|---|---|
| **Reconcile (Z-read)** is not in the menu | Your account does not hold the reconcile permission, or this is not the session you closed yourself. Reconcile in the ERP web app (**POS Sessions**). |
| **Reconcile (Z-read)** is dimmed with *Close the session first* | The session is still open. It must be counted and closed first. |
| An error message appears after tapping **Reconcile** | Read the message — it comes from the ERP. A common cause is that the session was already reconciled. |
| You reconciled the wrong session by mistake | Reconcile cannot be undone. Contact your administrator or accountant to correct it in the ERP. |

---

## X-read, payouts and reviewing a shift

### X-read — a mid-shift drawer check

**What it is.** A snapshot of the drawer *so far*, taken without closing or resetting anything. Use it to sanity-check a till mid-shift — before a cashier hands over, or if you suspect a problem.

1. On the till, open the session menu (**☰**).
2. Tap **X-read** (*Mid-shift drawer report — resets nothing*).
3. If the cashier's role has no report rights, a **Manager approval — X-read** box opens; a Branch Manager approves it. (With the standard Cashier role, the report opens straight away.)
4. Read the report: **Sales (all tenders)** with the **By tender** split, **Opening float**, **Cash sales**, **Payouts** (as a deduction, with the split by type), **Expected cash**, and the number of invoices. It ends with *An X-read does not close the shift and resets nothing.*
5. Tap **Print** for a paper copy (no approval is needed to print an X-read), or **Close**. The shift carries on unchanged.

An X-read works while the session is open or closed. Once it is reconciled, use the Z-read instead.

### Cash payout — recording cash that leaves the drawer

**What it is.** A record of money taken *out* of the drawer mid-shift. There are two kinds: **Paid out** (a business expense paid from the drawer, or a drop to the safe) and **Refund** (cash handed back to a customer). Any cash that leaves the drawer must be recorded, or the expected cash — and therefore the variance — will be wrong at close.

1. Open the session menu (**☰**).
2. Tap **Cash payout** (*Refund or drawer drop — reason required*). It is only available while the shift is open.
3. Choose **Paid out** or **Refund** (the box opens on **Paid out**).
4. Enter the **Amount (TZS)** and the **Reason (required)** — a few words saying what the cash is for. *A paid-out is booked to the ledger as an expense against the drawer, so the reason is what the entry is filed under.*
5. Tap **Record**. OrbixPOS confirms *Payout recorded.* or *Payout recorded and posted to the ledger.*

> **Expenses.** The till records a business expense as a **Paid out** with its reason. There is no expense category to pick at the till.

> A **Refund** payout is also the way to give money back when a whole-sale reverse is not possible — for example a return from a shift that has already closed. OrbixPOS does **not** do partial or single-line refunds; see the *Receipts and Refunds* chapter (Chapter 6).

### Reviewing a shift's sales

- **Today's sales** (in the session menu) lists recent finalised sales from the **ERP**, with receipt number, time and total. Tap any line to reprint it. Reprinting never creates a new sale.
- **Recent receipts** lists the last receipts stored on **this till**, so it works even if the network is down — but only for sales rung on this till.
- For a full view of a session's sales, payouts and figures, open it in the ERP web app (**POS Sessions**).

---

## Freeing a till that is in use

**What it is.** Only one shift can be open on a till at a time. If a cashier's shift is still open — they went home without closing, or the till app was shut down — nobody else can open that till until the shift is counted and closed.

**What the Open shift screen shows.** Each till tile has a coloured dot:

| Tile | Meaning |
|---|---|
| Green dot, till code | Free — tap to select it. |
| Amber dot, till code followed by *· Your shift* | Your own shift is still open on it. |
| Red dot, faded, with a person's name | Someone else's shift is open on it. |

Tap **Refresh** (next to *Choose a till*) to re-check the tills — for example after a manager has freed one.

**If it is your own shift.** Tapping the tile opens **Your shift is still open**. Press **Resume shift** to carry on where you left off, or **Close shift** to count the drawer now: the **Close your shift** box asks for the **Counted cash (TZS)** — count it physically; the box is deliberately left empty. After **Close shift** you see the variance, and then *Shift closed. The till is free again.* The session still needs reconciling by a manager.

**If it is someone else's shift.** Tapping the tile opens **Till in use**, naming who holds it and since when: *A shift only ends once the drawer is counted — that cashier can close it from their own sign-in, or an administrator can close it in the ERP.*

**How a branch manager frees it.** The standard Branch Manager role can close any cashier's session — from the ERP web app, not from the till:

1. Count the cash in that till's drawer (ideally with a witness).
2. In the ERP web app, open **POS Sessions**, find the open session for that till and open it.
3. Press **Close Session** and enter the counted cash.
4. Reconcile it as usual (it is now **CLOSED**).
5. On the till, press **Refresh** — the tile turns green and the till can be opened again.

---

## Operating multiple tills and branches

**What this is.** A store manager rarely watches just one register. Each till runs its own independent cash session, and each session must be opened, closed and reconciled in its own right — there is no single button that closes "the whole store."

- **One session per till.** Opening a shift ties the device to one till and its session.
- **Each till reconciles separately.** There is no store-wide reconcile.
- **Tills are listed per branch.** The **Open shift** screen shows only the **active** tills of the branch you are working in.
- **Your branch comes from your account.** OrbixPOS works in your default branch. If it could not confirm your usual branch, a yellow strip says *Using branch … — we couldn't confirm your usual branch. Check this is correct before selling.* If you see tills you do not expect, or none at all, check your branch assignment with your administrator.

> **Tip.** Give your tills clear, recognisable names when you create them. At end of day it is far easier to confirm "Front 1, Front 2 and Pharmacy are all reconciled" than to puzzle over codes.

---

## Creating and retiring tills

**What a till is.** A **till** (or register) is the record in the ERP that a cash session attaches to. It has a name and a code, belongs to a branch, and is linked to the cash account where its takings are booked.

### Creating a till

If your account holds the till-management permission (Branch Manager by default), a **New till** button appears beside the **Choose a till** heading on the **Open shift** screen.

1. Sign in and reach the **Open shift** screen.
2. Tap **New till** (next to **Refresh**). If you do not see it, your account lacks the till-management permission.
3. In the **New till** box, type a clear **Till name** — for example *Front 1*.
4. Tap **Create**.
5. OrbixPOS confirms *Till created.* and the new till appears in the grid.

> The ERP attaches the new till to your company's default cash account automatically. You can also create tills in the ERP web app, on the **POS Tills** screen (**New Till**).

### Retiring a till

When a till should no longer be used, it is **deactivated** rather than deleted, so its history stays intact. There is no retire button in OrbixPOS. In the ERP web app, open **POS Tills** and press **Deactivate** on the till (or ask your administrator).

- A deactivated till **disappears from the Open shift list** — the grid only shows active tills.
- Its past sessions are unaffected and stay in the ERP.

> **Note.** If a cashier suddenly cannot find their usual till, a recent deactivation is a likely cause.

---

## Provisioning cashiers — who is allowed to sell

This is the most common reason a new cashier cannot ring sales. It is set up in the **ERP web app**, normally by an administrator; as store manager you should know what is needed so you can spot the problem.

A cashier can only ring sales when **all** of these are true:

1. They have an **active user account**.
2. They are **assigned to the branch** they will sell in (with that branch as their default).
3. They have the **Cashier** role (or a role with the same till permissions).
4. They have an **internal sales-agent record linked to their user account**.

The fourth point is the one that catches people out. **Every user who rings sales must have an internal sales-agent record linked to their account.** Without it, the ERP refuses the sale.

> **The super-admin (root) cannot sell.** The top-level super-admin account cannot be a sales agent and therefore cannot ring sales. Do not run a register signed in as the root administrator — set up a real cashier user instead.

Three company-level settings also affect every till. Check them once when a shop goes live:

| Setting in the ERP | If it is missing |
|---|---|
| A **walk-in (cash) customer** for the company | Every sale shows **Select customer** and asks the cashier to pick a customer before payment. |
| The company's **address, phone, TIN and VRN** on the company record | Those lines are left off the receipt. |
| A **receipt printer** chosen on each till (**Server setup** on the sign-in screen) | **Print** shows *No receipt printer set — configure one in Setup.* |

### What it looks like when a cashier is not provisioned

| What you see | What to do |
|---|---|
| The sale is refused with a message about a missing sales agent | Ask your administrator to link an **internal sales-agent record** to that user in the ERP web app. |
| The sale is refused while signed in as root / super-admin | The root account cannot sell. Sign in as a properly set-up cashier. |
| A cashier sees no tills, or cannot open a shift | Check the user is assigned to this branch and has the Cashier role. |
| An expected action is not in the menu | The user lacks that permission. Grant the appropriate role in the ERP. |

---

## End-of-day close-out across tills

The routine to square away every till in your store at the end of trading. Each till session is independent, so you work through the tills one at a time. The cashier counts and closes; a manager reconciles.

Do this for **each** active till in the store:

1. **Stop selling on that till.** Make sure no sale is in progress.
2. **(Optional) X-read.** On the till, **☰** › **X-read** to see the expected cash before counting.
3. **Cashier closes the session.** **☰** › **Close session** (*Count the drawer → variance*), count the drawer, type the total into **Counted cash (TZS)**, and tap **Close session**. The **Session closed** panel shows Expected, Counted and Variance.
4. **Note the variance.** Record or photograph the panel if your store policy needs a paper trail. Then the cashier taps **Done** and signs out. The session is **CLOSED**, not yet reconciled.
5. **Manager reconciles.** A manager with the reconcile permission — ideally **not** the person who counted — opens the session in the ERP web app (**POS Sessions**), checks the figures and presses **Reconcile**. The session is now **RECONCILED** and the variance is posted.
6. **Move on.** Repeat for the next till.

When every till's session is **RECONCILED**, the store's cash is accounted for the day. The **POS Sessions** list in the ERP web app, filtered by status, is the quickest way to check that no session is left **Open** or **Closed**.

### End-of-day checklist

| For each till | Done? |
|---|---|
| Selling stopped; no sale in progress | ☐ |
| Drawer counted; **Counted cash** entered | ☐ |
| Session **closed**; variance noted; cashier signed out | ☐ |
| Session **reconciled** by a manager (not the person who counted) | ☐ |
| Session shows **RECONCILED** in **POS Sessions** | ☐ |

> **Tip.** On a till, the session's status is at the top of the session menu (the **Status** field: OPEN, CLOSED or RECONCILED).

> **Note.** Once a session is reconciled it is **final** — it cannot be reopened or edited, and you cannot top up a float mid-shift (the float is set only when the session is opened). If a reconciled session genuinely needs correcting, that is an accounting task for your administrator in the ERP.

---

## Quick reference

| Job | Where | Who can do it (standard roles) |
|---|---|---|
| Open a shift / pick a till | **Open shift** screen → mode, till, **Opening float (TZS)**, **Open session** | Cashier |
| Approve a refund, discount or unfinished sale at a till | The **Manager approval** box on the cashier's screen | Sales Manager, Branch Manager |
| Approve a Z-read print, or a report for a cashier without report rights | The **Manager approval** box on the cashier's screen | Branch Manager |
| Create a till | **Open shift** screen → **New till**, or ERP web **POS Tills** | Branch Manager |
| Retire a till | ERP web app → **POS Tills** → **Deactivate** | Branch Manager / administrator |
| Mid-shift drawer check | Session menu (**☰**) → **X-read** | Cashier (or with a Branch Manager's approval) |
| Record cash leaving the drawer | Session menu → **Cash payout** | Cashier |
| Review / reprint a sale | Session menu → **Today's sales** or **Recent receipts** | Anyone signed in |
| Close (count) a drawer | Session menu → **Close session** | Cashier (own shift) |
| Free a till a cashier left open | ERP web app → **POS Sessions** → **Close Session** | Branch Manager |
| Reconcile (post the variance) | ERP web app → **POS Sessions** → **Reconcile** (or on the till, for your own shift) | Branch Manager |
| Set up a cashier to sell | ERP web app | Administrator |
