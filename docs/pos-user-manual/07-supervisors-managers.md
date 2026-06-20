# For Supervisors and Store Managers

This chapter is for the people who run the floor rather than only ring sales: **shift supervisors** and **store managers**. It explains the jobs that a plain cashier cannot do — reconciling a session and reading its variance, keeping the cashier who counts the drawer separate from the person who signs off the count, running several tills (and several branches), creating and retiring tills, and getting every cashier set up so they are actually allowed to sell. It ends with a step-by-step end-of-day close-out you can follow across all the tills in your store.

If you have not yet read the earlier chapters, do that first — you need to know how to sign in, open a shift, ring a sale, take payment and print a receipt before the supervisor jobs below will make sense. Everything here builds on the same screens; the difference is that your account carries extra permissions, so OrbixPOS shows you actions a cashier never sees.

> Throughout this manual, OrbixPOS owns no money of its own. The **ERP server** is the single source of truth for price, VAT, totals and — the number you care about most here — the **cash variance** at the end of a shift. The figures OrbixPOS shows you while a shift is open are previews to help you work; the variance that gets posted to the books is the one the server computes when you close and reconcile.

---

## What each role can and cannot do

**What roles are.** OrbixPOS does not have a fixed list of "cashier" and "manager" buttons hard-wired into the app. Instead, every action is controlled by a named **permission** that your administrator grants to your user account in the ERP (for example, the permission to reconcile a session, or the permission to create a till). A "role" is just the bundle of permissions your administrator has given you.

**Why it exists.** Tills handle cash, and cash needs controls. By tying each sensitive action to its own permission, the business can let a cashier ring sales and close their own drawer, while reserving the act of signing off the count — and pushing the difference into the accounts — for someone more senior. This is the foundation of *segregation of duties*, explained in its own section below.

**When it matters.** Every time you open the session menu or the open-shift screen, OrbixPOS checks what your account is allowed to do and shapes the screen to match. An action you lack permission for is **dimmed and unavailable**, and the reconcile action shows a small padlock so you can see at a glance that it is reserved for a supervisor.

**How it works.** When you sign in, OrbixPOS asks the server for your effective permissions and remembers them for the session. It then enables or disables each action accordingly. You never see a raw permission code on screen — you simply find that some actions are available and others are locked.

The three operating roles map to permissions like this:

| Capability | Cashier | Supervisor | Store manager |
|---|---|---|---|
| Sign in, open and close their **own** shift | Yes | Yes | Yes |
| Ring sales and take payment | Yes | Yes | Yes |
| Run an **X-read** (mid-shift drawer report) | Yes | Yes | Yes |
| Record a **cash payout** (refund or paid-out) | Yes | Yes | Yes |
| Reprint from **Today's sales** / **Recent receipts** | Yes | Yes | Yes |
| **Reverse** a whole sale while the session is open | No | Yes | Yes (where granted) |
| **Reconcile (Z-read)** — post the variance to the ledger | No | Yes | Yes |
| **Create** and **retire** tills | No | No | Yes |
| Oversee several tills / branches | No | Partly | Yes |

> A supervisor is a cashier *plus* the right to reconcile and (usually) reverse a sale. A store manager is a supervisor *plus* the right to create and retire tills and oversee the branch. There is no separate "manager mode" — the same OrbixPOS app simply unlocks more actions for a more senior account.

If you expect to see an action and it is dimmed or carries a padlock, your account is missing the permission for it. That is a deliberate control, not a fault. Ask your administrator to grant it if your job genuinely requires it.

---

## Segregation of duties — why two people, not one

**What segregation of duties is.** It is the practice of splitting a sensitive task between two people so that no single person can both create a discrepancy and approve it. At the till, the sensitive task is the end-of-shift cash count: one person physically **counts the drawer**, and a different, more senior person **reconciles** that count — confirming it and posting any shortfall or surplus to the accounts.

**Why it exists.** If the same person who counted the cash were also the one who signed the count off into the ledger, a missing amount could be quietly absorbed with no second pair of eyes. Keeping the two steps with two people means every variance is reviewed by someone who did not handle the drawer. It protects honest staff from suspicion just as much as it deters dishonesty.

**When it happens.** At the **close** of every shift. The cashier closes the session by counting the drawer; the supervisor (or manager) reconciles it afterwards. The two steps are deliberately separate in OrbixPOS — closing does not reconcile, and reconciling is a second, permission-gated action.

**How it works.** OrbixPOS enforces the split through permissions:

- A cashier holds the permission to **close** a session but **not** the permission to **reconcile** it. So a cashier can count and close their own drawer, and then the **Reconcile (Z-read)** action appears with a padlock — visible, but locked.
- A supervisor or manager holds the reconcile permission, so for them the same action is unlocked.

This means the natural workflow is: the cashier closes, then hands over to you to reconcile. You should reconcile a drawer you did **not** count. Even though OrbixPOS will technically let a supervisor both close and reconcile (a supervisor holds both permissions), good practice — and usually store policy — is to have the cashier close and a *different* person reconcile.

> **Note.** Reconciling is final. It posts the variance to the general ledger and **cannot be undone**, and a closed or reconciled session cannot be re-opened or edited. Always confirm the counted figure with the cashier before you reconcile.

---

## Reconcile / Z-read — reading and posting the variance

**What reconcile (Z-read) is.** Reconcile is the end-of-shift step that **finalises** a cash session and posts its cash variance to the accounts. Its printed summary is traditionally called a **Z-read** — the end-of-day "zeroing" report for that drawer. The variance is simply *counted cash minus expected cash*: a positive number means the drawer is **over** (more cash than expected), a negative number means it is **short**.

**Why it exists.** Sales, the opening float and any cash payouts together tell the server how much cash *should* be in the drawer. The cashier's physical count tells you how much *actually* is. Reconciling records the difference permanently so the books match reality and any pattern of shortages can be investigated.

**When it happens.** Once per shift, after the cashier has **closed** the session (counted the drawer). Reconcile is the supervisor-gated step that comes immediately after close.

**How it works.** The server already computed the expected cash and the variance at close. When you reconcile, the server finalises the session, moves it from **CLOSED** to **RECONCILED**, and posts the variance to the general ledger. OrbixPOS then shows you the Z-read summary.

### Reading the close screen (what the cashier sees)

When the cashier closes the session, OrbixPOS shows a small variance panel. It is worth knowing exactly what it means, because it is the same figure you are about to post:

| Line | Meaning |
|---|---|
| **Expected** | What the server calculates *should* be in the drawer: opening float + cash sales − cash payouts. |
| **Counted** | What the cashier physically counted and typed in. |
| **Variance** | Counted minus Expected. A green panel means the drawer balances or is over; a red panel means it is short. |

The close panel ends with the reminder *"Reconcile (Z-read) posts this variance — supervisor."* That is your cue.

### How to reconcile a session

1. Make sure the cashier has already **closed** the session (the count is done) and has told you the counted figure.
2. Open the **session menu** using the menu button (**☰**) in the register's top bar. The **Session** panel slides in from the right.
3. Find **Reconcile (Z-read)** near the bottom of the list. Its subtitle reads *"Post variance — supervisor."* If it carries a padlock, your account lacks the permission — see the troubleshooting table below.
4. Tap **Reconcile (Z-read)**. A confirmation dialog appears: *"This finalises the session and posts the cash variance to the general ledger. This cannot be undone."*
5. Review the figures one last time, then tap **Reconcile**.
6. OrbixPOS shows the **Z-read** summary. Read it top to bottom:

   | Z-read line | What it tells you |
   |---|---|
   | **Opening float** | The cash the drawer started with. |
   | **Sales** | Total cash takings for the shift. |
   | **Payouts** | Cash paid out of the drawer during the shift (shown as a deduction). |
   | **Expected** | Float + sales − payouts. |
   | **Counted** | What the cashier counted. |
   | **Variance** | Counted − Expected. Shown in red if the drawer was short. |
   | **<n> invoices** | How many sales the shift rang. |

7. Tap **Finish shift**. The session is now **RECONCILED** and the till is free for the next shift.

> **Tip.** A small variance (a coin or two) is normal rounding. A large or repeated variance on the same till or cashier is worth investigating. Use **Today's sales** in the session menu to review the shift's invoices, and the **X-read** earlier in the shift to see whether the drawer drifted at a particular time.

### If reconcile is locked or fails

| What you see | What to do |
|---|---|
| **Reconcile (Z-read)** is dimmed and shows a padlock | Your account lacks the reconcile permission. A supervisor or manager must do it, or ask your administrator to grant you the permission. |
| The action does nothing | There is no open or closed session loaded on this device. Confirm the cashier closed the session on **this** till. |
| An error message appears after tapping **Reconcile** | Read the message — it comes straight from the server. A common cause is that the session was already reconciled. Re-open the session menu to check its status. |
| You reconciled the wrong session by mistake | Reconcile cannot be undone. Contact your administrator/accountant to handle the correction in the ERP. |

---

## X-read, payouts and reviewing a shift

A supervisor often needs to check on a drawer **without** closing it. Two session-menu actions exist for exactly this, and both are available to cashiers and supervisors alike.

### X-read — a mid-shift drawer check

**What it is.** The **X-read** is a snapshot of the drawer *so far*, taken without closing or resetting anything. **Why it exists.** It lets you sanity-check a till mid-shift — for example before a cashier hands over, or if you suspect a problem — without ending the session. **When it happens.** Any time during an open shift, as often as you like. **How it works.** OrbixPOS asks the server for the running totals and shows them; nothing is posted and the running figures keep accumulating afterwards.

1. Open the session menu (**☰**).
2. Tap **X-read** (*"Mid-shift drawer report"*).
3. Read the report: **Opening float**, **Sales**, **Payouts** (as a deduction), **Expected cash**, and a footer with the **invoice count**.
4. Tap **Close** to dismiss it. The shift continues unchanged.

### Cash payout — recording cash that leaves the drawer

**What it is.** A **cash payout** records money taken *out* of the drawer mid-shift. There are two kinds: a **Refund** (cash handed back to a customer) and a **Paid out** (a drawer drop or petty-cash payment). **Why it exists.** Any cash that leaves the drawer must be recorded, or the expected-cash figure — and therefore the variance — will be wrong at close. **When it happens.** Whenever cash physically leaves the drawer for a reason other than change on a sale. **How it works.** The payout is booked against the open session and subtracts from the expected cash the server will calculate at close.

1. Open the session menu (**☰**).
2. Tap **Cash payout** (*"Refund or drawer drop"*).
3. Choose the type: **Refund** or **Paid out**.
4. Enter the **Amount** and a short **Reason**.
5. Tap **Record**. OrbixPOS confirms *"Payout recorded."*

> A **cash-drawer refund payout** is also the supported way to give money back when a *whole-sale reverse* is not the right tool — for example, refunding a single line of a multi-line sale. OrbixPOS does **not** support partial or single-line refunds; you either reverse the entire sale (while the session is open, with the reverse action) or record a cash refund payout here.

### Reviewing a shift's sales

To look back over what a till rang:

- **Today's sales** (in the session menu) lists the shift's finalised invoices from the **server**, newest figures and times shown. Tap any line to reprint that receipt. Reprinting never creates a new sale.
- **Recent receipts** lists receipts stored on **this device**, so it works even if the network is down. It is per-device, so it only shows sales rung on this particular till.

---

## Operating multiple tills and branches

**What this is.** A store manager rarely watches just one register. You may be responsible for several tills in one shop, and sometimes for tills across more than one branch of the company. **Why it matters.** Each till runs its own independent cash session, and each session must be opened, closed and reconciled in its own right — there is no single button that closes "the whole store." **When it applies.** Throughout the trading day, and especially at end-of-day close-out (the routine at the end of this chapter). **How it works.** OrbixPOS always works on **one** till session at a time on the device in front of you. To act on a different till, you open or load that till's session.

Key facts to keep in mind:

- **One session per till.** Opening a shift binds the device to a single till and its session. To work a second till, open (or move to) that till's session — typically on the device standing at that register.
- **Each till reconciles separately.** There is no store-wide reconcile. Every open session must be closed and reconciled on its own before the day is truly squared away.
- **Tills are listed per branch.** The open-shift screen shows only the **active** tills that belong to the branch you are currently working in. Tills from other branches do not appear until you are working in that branch.
- **Your branch comes from your account.** OrbixPOS works in the branch your account is set up for (your default branch). If you are responsible for more than one branch, the tills you see change with the branch your account is operating in. If you cannot see tills you expect for another branch, you are not currently operating in that branch — check with your administrator about your branch assignment.

> **Tip.** Because each till session is independent, give your tills clear, recognisable names when you create them (next section). At end-of-day it is far easier to confirm "Front 1, Front 2 and Pharmacy are all reconciled" than to puzzle over codes.

---

## Creating and retiring tills

**What a till is.** A **till** (or register) is the record in the ERP that a cash session attaches to. It has a name and a code, belongs to a branch, and is linked to a cash account where its takings are booked. **Why it exists.** Every sale and every session has to be tied to a specific register so that cash, sales and variances can be tracked per till. **When you create one.** When you add a new physical register to a branch, or set up a new lane. **When you retire one.** When a register is removed, replaced, or should no longer be opened. **How it works.** Creating and retiring tills is reserved for accounts that hold the till-management permission — that is, store managers.

### Creating a till

You can create a till directly from the open-shift screen — but only if your account holds the till-management permission. If it does, a **New till** button appears beside the *Choose a till* heading.

1. Sign in and reach the **Open shift** screen (the screen where you pick a mode and a till).
2. Look for the **New till** button (a small **+** with the label **New till**) to the right of the **Choose a till** heading. If you do not see it, your account lacks the till-management permission.
3. Tap **New till**. A dialog titled **New till** opens.
4. Type a clear **Till name** — for example, *Front 1* or *Pharmacy counter*.
5. Tap **Create**.
6. OrbixPOS confirms *"Till created."* and the new till appears in the grid as an active till you can select.

> The new till is created with sensible defaults — the server attaches it to your company's default cash account automatically. There is no need to configure an account from OrbixPOS.

### Retiring a till

When a till should no longer be used, it is **retired** (deactivated) rather than deleted, so its history stays intact. The current build of OrbixPOS does not put a retire button on the till grid; retiring a till is done by your **administrator in the ERP**. The effect, once done, is immediate and visible in OrbixPOS:

- A retired till is **no longer active**, so it **disappears from the open-shift list** — the *Choose a till* grid only ever shows active tills.
- Existing, already-reconciled sessions on that till are unaffected; their history remains in the ERP.

To retire a till, ask your administrator to deactivate it in the ERP management web app. If you need to stop a till being used immediately, that is the route to take.

> **Note.** Because a retired till vanishes from the open-shift list, retire a till only when it is genuinely out of service. If a cashier suddenly cannot find their usual till, a recent retirement is a likely cause — check with whoever administers your tills.

---

## Provisioning cashiers — who is allowed to sell

This is the single most common reason a new cashier cannot ring sales, so it is worth understanding clearly. It is set up not in OrbixPOS but in the **ERP management web app** (the browser-based admin application your administrator uses), and it is normally an administrator's job. As the store manager you should know what is required so you can spot the problem and ask for the right fix.

**What provisioning is.** Provisioning is the one-time setup that turns a person's login into a user who is actually permitted to sell at a till. **Why it exists.** Selling moves stock and books revenue against a *salesperson*, so the ERP insists that every selling user is tied to an internal **sales-agent** record — the business identity that the sale is attributed to. **When it happens.** Once, before a new cashier's first sale. **How it works.** In the ERP management web app an administrator creates the user, assigns them to the branch, grants them the right role, and links them to an internal sales-agent record.

A cashier can only ring sales when **all** of these are true:

1. They have a **user account** that is active.
2. They are **assigned to the branch** they will sell in (with that branch set as their default).
3. They have a **role** that grants the POS permissions (sell, open and close a session, view tills, plus the catalogue reads a till needs).
4. They have an **internal sales-agent record linked to their user account**.

That fourth point is the one that catches people out. **Every user who rings sales must have an internal sales-agent record linked to their account.** Without it, the server refuses the sale.

> **The super-admin (root) cannot sell.** The bootstrap super-admin / root account deliberately **cannot** be a sales agent, and therefore **cannot ring sales** at the till. Do not try to run a register signed in as the root administrator — set up a real cashier user instead. This is by design: the root account is for administration, not for trading.

### What it looks like when a cashier is not provisioned

If a user without a linked sales-agent record tries to complete a sale, OrbixPOS shows a clear error message from the server explaining that the sale was refused. The fix is always the same:

| What you see | What to do |
|---|---|
| The sale is refused with a message about a missing agent / sales-agent record | Ask your administrator to link an **internal sales-agent record** to that user's account in the ERP management web app. |
| The sale is refused while signed in as the root / super-admin | The root account cannot sell. Sign in as a properly provisioned cashier instead. |
| A cashier sees no tills, or cannot open a shift | Check the user is assigned to this branch and holds the POS permissions. Your administrator sets these in the ERP. |
| An expected action (reconcile, create till) is dimmed/padlocked | The user lacks that permission. Grant the appropriate role in the ERP. |

> **Where this is done.** All four steps — create user, assign branch, grant role, link an internal sales agent — happen in the **ERP management web app**, not in OrbixPOS. OrbixPOS only *uses* the result. If a cashier cannot sell, the fix is in the ERP, and your administrator is the person to action it.

---

## End-of-day close-out across tills

This is the routine to follow at the end of trading to square away every till in your store. It combines the steps above into one repeatable procedure. Because each till session is independent, you work through the tills one at a time.

**What close-out is.** The end-of-day procedure that closes, counts and reconciles every open till session so the day's cash is fully accounted for. **Why it exists.** It ensures no drawer is left open or unreconciled overnight, and that every variance is reviewed and posted the same day. **When it happens.** At the end of each trading day (or at the end of each shift block, depending on store policy). **How it works.** For each till, the cashier closes and counts, then a supervisor reconciles — keeping the two duties separate.

Do this for **each** active till in the store:

1. **Stop selling on that till.** Make sure no sale is in progress on the register.
2. **(Optional) Run an X-read first.** On that till's device, open the session menu (**☰**) and tap **X-read** to see the expected cash before counting. This gives the cashier a target to count against.
3. **Cashier closes the session.** On that till, open the session menu, tap **Close session** (*"Count the drawer → variance"*), physically count the drawer, type the total into **Counted cash**, and tap **Close session**. OrbixPOS shows the variance panel (Expected, Counted, Variance).
4. **Note the variance.** Record or photograph the variance panel if your store policy requires a paper trail. The session is now **CLOSED** but not yet reconciled.
5. **Supervisor reconciles.** A supervisor or manager — ideally **not** the cashier who counted — opens the session menu and taps **Reconcile (Z-read)**, reviews the Z-read summary, taps **Reconcile**, then **Finish shift**. The session is now **RECONCILED** and the variance is posted to the ledger.
6. **Confirm and move on.** The till is now squared away. Move to the next till and repeat from step 1.

When every till has reached **RECONCILED**, the store's cash is fully accounted for the day.

### End-of-day checklist

| For each till | Done? |
|---|---|
| Selling stopped; no sale in progress | ☐ |
| Drawer counted; **Counted cash** entered | ☐ |
| Session **closed**; variance noted | ☐ |
| Session **reconciled (Z-read)** by a supervisor (not the counter) | ☐ |
| Session status shows **RECONCILED**; shift finished | ☐ |

> **Tip.** A till's session status is visible at the top of the session menu (the **Status** field). Use it to confirm each till is genuinely **RECONCILED** before you sign off the day. If any till is still **OPEN** or **CLOSED**, it has not finished close-out.

> **Note.** Once a session is reconciled it is **final** — it cannot be re-opened or edited, and you cannot top up a float mid-shift (the float is set only when the session is opened). Plan the count carefully; if you genuinely need to correct a reconciled session, that is an ERP/accounting task for your administrator, not something OrbixPOS can reverse.

---

## Quick reference

| Job | Where in OrbixPOS | Who can do it |
|---|---|---|
| Open a shift / pick a till | **Open shift** screen → pick mode, pick till, set float, **Open session** | Cashier, supervisor, manager |
| Create a till | **Open shift** screen → **New till** | Manager (till-manage permission) |
| Retire a till | ERP management web app (not in OrbixPOS) | Administrator |
| Mid-shift drawer check | Session menu (**☰**) → **X-read** | Cashier, supervisor, manager |
| Record cash leaving the drawer | Session menu → **Cash payout** | Cashier, supervisor, manager |
| Review / reprint a sale | Session menu → **Today's sales** or **Recent receipts** | Anyone signed in |
| Close (count) a drawer | Session menu → **Close session** | Cashier (own shift), supervisor, manager |
| Reconcile (post variance) | Session menu → **Reconcile (Z-read)** | Supervisor, manager |
| Set up a cashier to sell | ERP management web app | Administrator |
