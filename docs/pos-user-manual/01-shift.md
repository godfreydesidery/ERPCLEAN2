# Starting and Ending a Shift

Every selling day at the till runs inside a *shift*. This chapter explains what a till and a cash session are, how to open your shift in the morning, how to get back into a shift that is still open after the app closed or the power went off, how long a shift may stay open, how to use the session menu through the day (mid-shift reports, cash payouts, till expenses, looking up sales), and how to count down and close the drawer at the end. It is written for cashiers; the steps marked **supervisor** are for the person who is allowed to approve reports and finalise the day.

You should already be signed in and at the **Open shift** screen. If you are not yet signed in — or the app is asking you to set the ERP host and **Test connection** — see the *Getting Started* chapter (Chapter 1) first, then come back here.

> **Waiters do not open shifts.** A waiter's login goes straight to the restaurant floor: waiters take orders and print bills, and the cashier's shift takes the money. Everything in this chapter is for the person who runs the till.

---

## What a till and a cash session are

**What a till is.** A *till* (also called a register) is a named point-of-sale station on your branch — for example **Front counter 1** or **Pharmacy desk**. It is set up by your store manager and lives on the ERP server, not on this device. Several devices can, over the day, use the same till, but only one cashier can have it open at a time.

**What a cash session is.** A *cash session* (your *shift*) is one cashier's turn on one till. It begins when you declare how much cash is in the drawer to start (the *opening float*), it gathers every sale and every cash payout you make, and it ends when you count the drawer and the server works out whether the cash matches. A session has a human-readable number (for example `POS-0001`) and moves through exactly three states, one way only:

```
OPEN  →  CLOSED  →  RECONCILED
```

| State | What it means |
|---|---|
| **OPEN** | The shift is live. You can ring sales, take payment, record payouts, and run a mid-shift report. |
| **CLOSED** | The drawer has been counted. No more sales can be rung against this session. The server has calculated the difference between counted and expected cash (the *variance*). The till is free for the next shift. |
| **RECONCILED** | A supervisor has finalised the session and posted any cash difference to the accounts. The session is locked for good. |

**Why this exists.** The session is how the business accounts for cash. Because it records the float you started with, every cash sale, and every payout, the server always knows how much money *should* be in the drawer. At close, that expected figure is compared against what you actually counted, so over- and short-drawers are caught on the same shift they happen.

**Who is authoritative.** OrbixPOS owns no money figures of its own. The ERP server is the authority for sales totals, expected cash, and the variance. The figures that count are the ones the server sends back at X-read, close, and reconcile.

> **Only a counted close ends a shift.** A shift never closes by itself — not when you sign out, not when the app is closed, not when the power goes off. It stays OPEN on the server until someone counts the drawer and closes it. This protects the cash: the system never invents a count. It also means that if the app closes unexpectedly, your shift is waiting for you — see **Getting back into a shift that is still open** below.

---

## Opening your shift

**What opening a shift is.** Opening a shift creates a new OPEN session: you choose how the till behaves (the business mode), pick which till you are working on, and declare the starting cash. From the moment it opens, every sale you ring belongs to this session.

**When it happens.** Once at the start of each shift, before you ring your first sale. If you sign in and you have no open shift, the app takes you straight to the **Open shift** screen. (If you *do* still have a shift open, it takes you straight back to your register instead.)

**How it works.** The screen shows the company and branch you are working in at the top, with your name and **@username** on the right (on a phone, just your avatar). Below that are three things to set, in order: the **Business mode**, the till (**Choose a till**), and the **Opening float**. When you press **Open session** the app asks the server to create the session and then drops you onto the register, ready to sell.

### Step by step

1. Check the header. Under the **Open shift** title you will see your **company · branch**. Make sure this is the branch you mean to sell on. (To change who you are, use the **Sign out** icon at the top right.) If an amber strip says "Using branch … — we couldn't confirm your usual branch", check that the branch named is correct before you go on.

   > **Only want to look at sales?** If your account may view sales invoices (the standard Cashier role and the manager roles can), two buttons sit under the company · branch line: **Sales dashboard** and **Today's sales**. They open straight from this screen — **you do not need to choose a till or open a shift**. Opening a shift just to look would start a real cash session that someone then has to close and reconcile. Close the dashboard (the back arrow) to come back here.
2. Under **Business mode**, choose how this till should behave by tapping one of the cards:

   | Mode | Card label | Best for |
   |---|---|---|
   | 🛒 | **Supermarket** | Fast scanner-first grocery checkout |
   | 💊 | **Pharmacy** | Dispensing with patient & Rx capture |
   | 🍽 | **Restaurant** | Table service with order tickets |

   The selected card is highlighted. You can change mode later from the register's top bar, but only while no sale is in progress — so it is best to pick the right one now. On a till set to *Restaurant only*, only the **Restaurant** card is shown.
3. Under **Choose a till**, tap the till you are working on. Only **active** tills for your branch are shown. Each tile shows the till **name** and a coloured dot:

   | Tile shows | Meaning |
   |---|---|
   | The till code and a **green** dot | The till is free. Tap it to select it; the tile highlights. |
   | The till code, **· Your shift**, and an **amber** dot | You already have a shift open on this till. Tap it to resume or close it — see **Getting back into a shift that is still open**. |
   | A colleague's name, a **red** dot, and a faded tile | Someone else has a shift open on this till. Tap it to see who and what to do — see **When a till is in use by someone else**. |

   Use **Refresh** (above the tiles, on the right) to reload the list — for example after a supervisor frees a till. On a touch screen you can also pull the screen down to refresh. You never need to restart the app to see a change.

   > If the list reads **No active tills on this branch yet**, ask your store manager to create or activate a till for you. Store managers (anyone allowed to manage tills) see a **New till** button next to **Refresh**: it asks for a **Till name** and creates the till on the spot (**Till created.**). A till that was taken out of use (deactivated) is switched back on in the ERP — see the *For Supervisors and Store Managers* chapter (Chapter 8).
   >
   > If the till list shows an error message with a **Retry** button, your connection to the server dropped. Tap **Retry**. If the till may sell offline, a card under the message offers to open an offline shift instead — see the *Selling Offline* chapter (Chapter 9).
4. In **Opening float**, type the amount of cash you are starting the drawer with, in your branch currency (the field label shows the currency, e.g. **Opening float (TZS)**). Count the float first and enter the exact figure, digits only (for example `50000`). If you are starting with an empty drawer, leave it as `0`.
5. Press **Open session**.

The app creates the session and takes you to the register. The button shows a busy spinner while it works; if anything goes wrong, a short message appears at the bottom of the screen — read it, fix the cause, and try again.

| What you see | What to do |
|---|---|
| **Pick a till first.** | You pressed **Open session** without selecting a till tile. Tap a free till, then try again. |
| **This till already has an OPEN session.** | Someone opened a shift on that till a moment ago. Tap **Refresh**, then pick a free till. |
| **You do not have permission for this action.** | Your account is not allowed to open a shift. Ask your administrator to check your role. |
| An amber strip: "We couldn't check whether you already have a shift open. If your till shows as in use, open it again from the list below." | The till could not look up your open shift when you signed in. If one of the tiles shows **· Your shift**, tap it and choose **Resume shift**. You can close the strip with its **×**. |

---

## How long a shift can stay open

**What it is.** Your company sets a **till shift time limit**: how long a shift may stay open and still take sales. Unless your company changed it, a shift takes sales for **36 hours** after it was opened. The limit exists so that each cash count and Z-read covers one trading day.

**What you see.** Once a shift is older than the limit, the till refuses the next sale with a red banner on the payment screen: *This till session has been open too long to take new sales. Close and reconcile it, then open a new session.* Nothing is charged.

**What to do.** Close the session (count the drawer), have a supervisor reconcile it, open a new shift, and ring the sale again. Open restaurant bills are not lost: they are not tied to a shift and can be paid from the new one.

> **Changing the limit.** A manager sets it in the ERP under **Sales › Sales Settings**, **Till shift time limit**: *Use the server setting* (normally 36 hours), *Set my own limit* (12 to 168 hours), or *No limit*. With **No limit** a shift can stay open for days, and its cash count and Z-read then cover every day it was open. The change applies to the next sale on every till; no till update or restart is needed.

---

## Getting back into a shift that is still open

**What it is.** If OrbixPOS was closed in the middle of a shift — the app was shut, the PC restarted, the power failed, or you simply signed out — your shift is still OPEN on the server, with the float and every sale safely recorded. OrbixPOS lets you carry straight on with it, or count the drawer and close it.

**Why it exists.** Because only a counted close ends a shift, a shift you could not get back into would lock you out of your own till. Resuming puts you back exactly where you were.

**How it works.** In most cases you do not have to do anything: when you sign in, OrbixPOS looks for an open shift of yours and, if it finds one, opens the register in that shift straight away.

If you land on the **Open shift** screen instead, your till shows **· Your shift** with an amber dot. Tap it. A dialog titled **Your shift is still open** says "You already have a shift open on …" (the till's name and the time it was opened) "… Carry on where you left off, or count the drawer and close it." It offers:

| Button | What it does |
|---|---|
| **Resume shift** | Re-enters the register in that shift, in the business mode selected on the screen. Carry on selling. |
| **Close shift** | Counts the drawer and closes the shift right here (see **Closing an abandoned shift from the till list** below). Shown only if you are allowed to close sessions; otherwise the dialog says "If you're not carrying on, ask a supervisor to close it for you." |
| **Cancel** | Does nothing. |

> **After resuming, check for an unfinished sale.** If the app closed while a payment was going through, the register asks about that **Unfinished sale** as soon as it opens. Answer it before ringing anything else — see the *Taking Payment* chapter (Chapter 5).

### When resuming cannot load the shift's details

Sometimes the till can reopen your shift but cannot read its details — for example your account is not allowed to view sessions, or the network dropped at that moment. OrbixPOS still lets you in, so you are never locked out of your own till, but:

- the **Session** chip in the top bar shows a dash (**—**) instead of the session number;
- the session menu hides the **Session** number and **Float** tiles and shows "Shift figures unavailable — reconnect to refresh."

You can sell normally. The figures reappear once the till can read the session again (for example after you record a payout while connected).

If the shift cannot be reopened at all, a dialog titled **Couldn't reopen your shift** says "Your shift on … is still open, so nothing has been lost. Ask a supervisor for POS session access, or count the drawer and close the shift." Choose **Not now** to leave it, or **Close shift** (if shown) to count up. If a dialog titled **Your shift** says it "could not load it just now", tap **Got it**, then **Refresh** on the Open shift screen and try again.

### Closing an abandoned shift from the till list

Use this when you do not want to carry on with the old shift — for example it was left open overnight. This is a real cash-up, exactly like closing at the end of a shift.

1. Count all the cash in that till's drawer.
2. Tap your **· Your shift** tile, then **Close shift**.
3. In the **Close your shift** dialog ("Count the cash in the … drawer and enter the total. This is what the shift is settled against."), type the count into **Counted cash (TZS)**. The field starts empty on purpose — type what you actually counted.
4. Tap **Close shift**. The dialog changes to **Shift closed** with the note "A supervisor reconciles the shift to post the variance. The till is free to use now."
5. Tap **Done**. You see **Shift closed. The till is free again.** and the till list refreshes. You can now open a fresh shift.

If you leave **Counted cash** empty you are told **Count the drawer and enter the cash total.** If the shift still has offline sales on this device that have not reached the server, a **Sync before closing** box stops you first (see **Closing the session** below).

---

## When a till is in use by someone else

**What you see.** The tile shows a colleague's name with a red dot and looks faded. Tapping it opens **Till in use**: "… has a shift open on …" (the colleague's name, the till, and since when) "… A shift only ends once the drawer is counted — that cashier can close it from their own sign-in, or an administrator can close it in the ERP." Tap **Got it**.

**What to do.**

- Pick a different free till, **or**
- ask that cashier to sign in and close their shift (counting the drawer), **or**
- if that cashier is not available, ask a **branch manager** or an administrator to close it. They do this in the ERP, on the **POS Sessions** screen, by counting the drawer and entering the count. Once it is closed, tap **Refresh** and the till turns green.

> Nobody should close another person's shift without physically counting that drawer. A guessed count hides a real shortage or overage.

---

## The session number and status chips

Once your shift is open, look at the **top bar** of the register. From the left you will see:

- The **OrbixPOS** brand mark.
- A **store** chip showing your **branch** and **company**.
- A green **Session** chip showing your session number (for example `POS-0001`) — your at-a-glance confirmation that a shift is live on this device. A dash means the shift was resumed without its details (see above).
- In the middle, the **mode switcher** (🛒 Supermarket / 💊 Pharmacy / 🍽 Restaurant) — switching is blocked while a sale is in progress.
- On the right, the **Session menu** button (the ☰ icon) and your avatar.

On a narrow screen the store and session chips are left out; the session number is at the top of the session menu.

---

## The session menu

**What it is.** The session menu is a side panel that holds every shift-level action that is not part of ringing a sale: drawer reports, cash payouts, till expenses, sales look-ups, offline sales, and closing or reconciling the drawer.

**How to open it.** On the register's top bar, tap the **Session menu** button — the ☰ icon near your avatar on the right. The **Session** panel slides in from the right. Tap the **✕** at its top (tooltip "Close the session menu"), or tap the dimmed area beside it, to close it again.

**What it shows.** At the top, a small grid of facts about the current session:

| Tile | Meaning |
|---|---|
| **Session** | The session number (e.g. `POS-0001`). |
| **Status** | `OPEN`, `CLOSED`, or `RECONCILED`. |
| **Opened** | The time the shift was opened. |
| **Float** | The opening cash you declared. |

Below the facts is the list of actions. **You only see the actions your account is allowed to use** — an action you have no permission for is not shown at all. An action you *are* allowed but cannot use right now stays in the list, faded, with the reason underneath (for example "Close the session first", or "Needs the server — the till is offline" while the till sells offline).

| Action | What it does | Who sees it |
|---|---|---|
| **Offline sales** | How many offline sales are waiting to be sent, the last sync, sales the server held, and the offline switch. See Chapter 9. | Tills allowed to sell offline (and any till that still holds offline sales). Not shown to waiters. |
| **Change my PIN** | Sets or changes your own 4-digit sign-in PIN (see Chapter 4). | Waiters. |
| **X-read** | A mid-shift drawer report. Resets nothing. A cashier's X-read leaves out the expected cash. | Anyone working the till. A cashier without permission to view sessions needs a manager's approval each time. |
| **Cash payout** | Records cash leaving the drawer (a paid-out, for example a drop to the safe). Reason required; a manager approves it at the till. | Cashiers allowed to open shifts. Only while the session is OPEN. |
| **Till expense** | Records cash paid out of the drawer for a business expense, under a category (transport, cleaning, …). A manager approves it at the till. | Anyone allowed to record till expenses (the standard Cashier role is). Only while the session is OPEN. |
| **Today's sales** | Lists today's sales at this branch, reprints a receipt, and opens it for a refund or item return. | Anyone allowed to view sales invoices (the standard Cashier role is). |
| **Sales dashboard** | This branch's sales for today, yesterday, the last 7 days, this month or chosen dates (see below). Also on the **Open shift** screen, without a shift. | Anyone allowed to view sales invoices. |
| **Add a product** | Creates a new product from the till: name, price, department, unit, VAT, track stock, barcode and code (see Chapter 3). | Anyone allowed to manage products (usually purchasing staff and administrators). |
| **Recent receipts** | Reprints a receipt saved on **this device** (works offline). | Everyone. |
| **Close session** | Count the drawer; the server computes the variance. | Anyone allowed to close sessions. Only while OPEN. |
| **Reconcile (Z-read)** | Posts the variance to the accounts and finalises the session. | **Supervisors only.** Only after the session is CLOSED. |
| **Z-read (reprint)** | Shows and reprints the final figures again. | Anyone working the till (a manager's approval may be needed). Only after the session is RECONCILED. |

At the very bottom of the panel is **Sign out**. Signing out does **not** close your session — your shift stays OPEN on the server until you actually close it. Under **Sign out**, in small grey text, is the version of OrbixPOS on this till (for example **OrbixPOS 1.14.1+37**) — quote it when you call for support.

### The sales dashboard

**Sales dashboard** shows this branch's sales without opening anything else. Choose the period along the top — **Today**, **Yesterday**, **Last 7 days**, **This month** or **Choose dates…** — and read:

- **Sales (TZS)**, **Number of sales** and **Average sale**;
- **By day** (when the period covers more than one day), with the number of sales each day;
- **By payment type** (cash, mobile money, card, cheque, complimentary);
- **By department** — tap a department to see the items sold in it, with quantities and amounts.

No cost or profit is shown. The refresh button at the top reloads the figures; **No sales in these dates.** means nothing was sold in the period. Close it with the back arrow.

---

## Manager approval

Some actions need a supervisor or manager standing at the till. When that is the case, a dialog titled **Manager approval — …** opens (for example **Manager approval — X-read**). It says what is being approved and for which session.

1. The manager types their own **Manager username** and **Manager password**.
2. The manager taps **Approve**. (**Cancel** backs out; nothing happens.)

The till stays signed in as you throughout — the dialog says "You stay signed in — this only checks the manager’s authority for this one action." The approval covers that **one** action only; the next report asks again. A waiter's PIN never approves anything here — the manager always types their password.

| What the dialog shows | What it means |
|---|---|
| **Enter the manager username and password.** | One of the fields is empty. |
| **Those details were not accepted. Check the username and password and try again.** | Wrong username or password. Only the password is cleared, so the manager can retype it. |
| **That user is not allowed to approve this action.** | The password was right, but that person may not approve this — or the person signed in at the till tried to approve their own action. The approver must be a **different person** who holds the supervisor right for this action. |
| **Too many failed approval attempts. Please wait a moment and try again.** | Several wrong attempts in a row. Wait, then try again. |

For drawer reports, cash payouts and till expenses, the approver must be someone allowed to reconcile tills (a supervisor or branch manager). If a report is still refused after approval, the till shows **That approval was not accepted. Ask a supervisor who can reconcile the till.** The full list of actions that need a manager is in the *For Supervisors and Store Managers* chapter (Chapter 8).

> **Cash leaving the drawer always has a second name on it.** Every **Cash payout** and **Till expense** asks for a manager's approval before it is recorded, and the manager who approved it is named on the record. If you are yourself allowed to reconcile tills (a supervisor or branch manager working a till), you are not asked — the payout is recorded in your own name.

---

## X-read — a mid-shift drawer report

**What it is.** An *X-read* is a snapshot of where the drawer stands right now: total sales by tender, the float you started with, cash sales, payouts, and the cash the server expects to be in the drawer. It is read-only.

**Why it exists.** It lets you (or a supervisor) check the drawer mid-shift — for a spot count, a cash drop decision, or a shift handover — without ending the session. It changes nothing and can be run as many times as you like.

**When it happens.** Any time while the session is **OPEN** — and also after it is **CLOSED**, until it is reconciled. Once the session is **RECONCILED**, use the Z-read instead (the till says the session "has been reconciled — open its Z-read for the final figures"). The X-read needs the server, so it is faded while the till sells offline.

**How it works.** Depending on your account, one of two things happens when you tap **X-read**:

- if you are allowed to view sessions, the report opens straight away;
- if not, a **Manager approval — X-read** dialog opens first ("Show the mid-shift drawer report for this session."). Once a manager approves, the report opens for that one view.

### Step by step

1. Open the **Session menu** (the ☰ button).
2. Tap **X-read**. If asked, have a manager approve it.
3. Read the report:

   | Line | What it means |
   |---|---|
   | **Sales (all tenders)** | Everything taken on this session so far, whatever the payment type. Under it, **By tender** splits it — for example `Cash 32,020.00 · Mobile 14,000.00`. |
   | **Opening float** | The cash you declared at open. |
   | **Cash sales** | The cash part of your sales — the only part that ends up in the drawer. |
   | **Payouts** | Cash that has left the drawer, shown as a negative, with a line for each type — **Paid out**, **Expense**, and **Refund** (cash given back for returned items) — with how many of each, for example **Paid out (2)**. |
   | **Expected cash** | What the server says should be in the drawer right now: float + cash sales − payouts. **Shown only to a supervisor** (someone allowed to reconcile the till) or when a manager approved this X-read at the till. A cashier counts the drawer *blind* — without knowing the figure — so the count is honest. |

   The footer shows how many invoices have been rung (for example `23 invoices`) and the reminder "An X-read does not close the shift and resets nothing."
4. To print it, tap **Print** (on a Windows till or an Android device; not on the web version). You see **Printed.** — or **No receipt printer set — configure one in Setup.** if no printer is set up.
5. Tap **Close** to dismiss the report. Nothing has changed — your shift is still open.

> An X-read is a **cash-drawer** report, not an accounting report. **Sales (all tenders)** is usually bigger than the cash in the drawer because card, mobile money and cheque payments do not go into the drawer, and complimentary sales bring in no money at all.

---

## Recording a cash payout

**What it is.** A *cash payout* (a **Paid out**) records money physically leaving the drawer during the shift that is not change on a sale and not a business expense — for example a drop to the safe. It reduces the cash the server expects in the drawer, and it is booked to the ledger as an expense against the drawer, filed under the reason you type — so the reason matters.

> **A cash payout is never a refund.** To give a customer their money back, open the sale in **Today's sales** (or its receipt) and use **Return items** or **Refund / reverse** — see Chapter 6. That puts the stock back and corrects the sales and VAT figures; a payout does none of that. The **Cash payout** box says so at the top.

> Paying for something the business needs out of the till — transport, cleaning, a small repair — is a **till expense**, not a cash payout. Use **Till expense** (see **Recording a till expense** below) so the cost is filed under the right category.

**Why it exists.** If you take cash out of the drawer without telling the system, your end-of-shift count will look short by that amount. Recording the payout keeps the expected figure matched to reality, so a genuine over/short is not masked.

**When it happens.** Whenever cash leaves the drawer for a reason that is not change on a sale. The session must be **OPEN** and the till online. A manager approves every payout at the till (unless you are yourself allowed to reconcile the till).

### Step by step

1. Open the **Session menu** and tap **Cash payout** ("Cash paid out of the drawer — manager approves").
2. In **Amount (TZS)**, type how much cash is leaving, digits only. It must be greater than zero.
3. In **Reason (required)**, type what the cash is for — for example `drawer-to-safe drop`. A few words at least.
4. Tap **Record**. The **Manager approval — cash payout** prompt opens: the manager types their own username and password and taps **Approve**.
5. Count out and remove the cash from the drawer.

You will see **Payout recorded.** (or **Payout recorded and posted to the ledger.**), and the dialog closes. The amount is now subtracted from your expected cash, and the manager who approved it is named on the record. To cancel without recording anything, tap **Cancel**.

| What you see | What to do |
|---|---|
| **Enter an amount.** | The amount was blank or zero. Type a positive amount and tap **Record** again. |
| **Say what the cash is for (at least a few words).** | The reason is missing or too short. Type a clear reason. |
| **That manager is not authorised to approve cash out of the till.** | The person who approved may not settle the till. Ask a manager who can reconcile the till. |
| **Cash payout** is faded with "Only while the session is open" | The shift has already been closed or reconciled — payouts are only allowed while OPEN. |
| A message saying the session is not OPEN | Same cause: the shift was closed. |

---

## Recording a till expense

**What it is.** A *till expense* records cash you pay out of the drawer for something the business needs — a boda-boda for a stock run, cleaning supplies, a small repair, a meal for staff on a long day. Like a payout, it reduces the cash the server expects in the drawer. Unlike a payout, it is filed under a **category**, so the managers can see what the till money was spent on.

**Why it exists.** Small expenses paid from the till add up. Recording each one under its category keeps your drawer count honest *and* tells the managers what the money was spent on, instead of a pile of "paid out" lines they have to read one by one.

**When it happens.** Whenever you pay a business cost in cash from the drawer. The session must be **OPEN** and the till online. You see **Till expense** in the Session menu only if your account is allowed to record till expenses — the standard Cashier role is.

### Step by step

1. Pay the cash and keep the paper receipt from the seller, if there is one, for your supervisor.
2. Open the **Session menu** and tap **Till expense** ("Cash paid out for the business — by category").
3. In the **Till expense** dialog, type the amount into **Amount (TZS)**, digits only. It must be greater than zero.
4. Under **Category (required)**, tap one of the quick choices — **Transport**, **Cleaning**, **Repairs**, **Meals**, **Utilities**, **Stationery** — or type your own category (2 to 40 letters), for example `Security`.
5. In **What was it for? (required)**, say in a few words what you paid for — for example `boda to collect sugar from depot`.
6. Tap **Record**. The **Manager approval — till expense** prompt opens: the manager types their own username and password and taps **Approve** (you are not asked if you are yourself allowed to reconcile the till).

You will see **Expense recorded and posted to the ledger.** and the dialog closes. The amount is now subtracted from your expected cash. To back out without recording anything, tap **Cancel**.

The expense appears on the **X-read** and **Z-read** as its own **Expense** line under **Payouts**, and is posted to the ledger under its category.

| What you see | What to do |
|---|---|
| **Enter an amount.** | The amount was blank or zero. Type a positive amount. |
| **Choose or type a category (2 to 40 letters).** | The category is empty or too long. Tap a quick choice or type a short category. |
| **Say what the cash is for (at least a few words).** | **What was it for?** is missing or too short. |
| **Till expense** is faded with "Only while the session is open" | The shift has already been closed — expenses can only be recorded while it is OPEN. |
| **Till expense** is not in the menu | Your account is not allowed to record till expenses. Ask your supervisor. |

> **Cash payout** records only a **Paid out** — there is no expense or refund choice there. Business expenses always go through **Till expense**; money back to a customer always goes through **Return items** or **Refund / reverse** on the sale.

---

## Closing the session

**What it is.** *Closing* the session ends selling for the shift. You physically count the cash in the drawer and enter the total; the server compares it against the expected figure and works out the **variance** — the difference between what you counted and what should be there.

**Why it exists.** The close is the moment of truth for the drawer. It pins down, on the spot, whether the till is balanced, over, or short, and it freezes the session so no further sales can be added. It also frees the till for the next shift.

**When it happens.** Once, at the end of your shift, after the last sale and any final payouts. The session must be **OPEN** and the till online. **No money is posted to the accounts at this step** — that is the separate reconcile step that follows.

**How it works.** You enter the counted cash; OrbixPOS sends it to the server. The server computes:

- **Expected cash** = opening float + cash sales − payouts
- **Variance** = counted cash − expected cash

A **positive** variance means the drawer is **over** (more cash than expected); a **negative** variance means it is **short** (less cash than expected); **zero** means it balances exactly.

### Step by step

1. Count all the cash in the drawer carefully, including the opening float.
2. Open the **Session menu** and tap **Close session** ("Count the drawer → variance").
3. The till may stop you first with one of these:
   - **Sync before closing** — the shift still has offline sales that have not reached the server ("Close it once they have synced, so the Z-report includes them."). Tap **Sync now**, wait until they have gone, and try again. See Chapter 9.
   - **N restaurant orders are still open** — it lists the tables and takeaways, and says "They stay open for the next shift and are paid from any till. Close this session anyway?" Tap **Back** to go and settle them, or **Close session** to carry on; the orders are not lost.
4. In the **Close session** dialog ("Count the drawer and enter the cash total."), type the figure into **Counted cash (TZS)**, digits only.
5. Tap **Close session**.

The dialog now reads **Session closed** and shows the result:

| Line | What it means |
|---|---|
| **Expected** | What the server expected to be in the drawer. A note explains: "Expected = float + cash sales − payouts (cash tenders only; card & mobile money settle separately)." |
| **Counted** | The figure you entered. |
| **Variance** | Counted − expected. Shown in **green** when the drawer balances or is over, and in **red** when it is short. |

**Expected** and **Variance** are shown only to someone allowed to reconcile the till. A cashier sees just the **Counted** figure and the note "Session closed. A supervisor will check the count and settle the drawer." — the supervisor sees the result when they reconcile.

Under the variance, a note reminds the supervisor: *"Reconcile (Z-read) posts this variance — supervisor."*

6. Tap **Done**.

**What next.** The session is now CLOSED and no more sales can be rung on it.

- If you are a supervisor allowed to reconcile, carry straight on with **Reconcile (Z-read)** below.
- If not, open the **Session menu** and **Sign out**. A supervisor reconciles your closed session in the ERP (the **POS Sessions** screen). When you or the next cashier sign in, the **Open shift** screen appears and the till is free.

| What you see | What to do |
|---|---|
| **Enter the counted cash.** | The **Counted cash** field was empty. Enter your count and tap **Close session** again. |
| A red (short) variance | The drawer is short of what was expected. Note the shortfall and follow your branch's cash-handling procedure. The figure is already recorded; it cannot be re-entered. |
| A message that the session is not OPEN, or **Close session** is faded with "The session is already closed" | The session was already closed. It cannot be closed twice. |

> Counting honestly matters more than counting "to balance". The variance is meant to surface real differences. If you are short or over, record the true count — do not adjust your figure to hide it. Count twice *before* you tap **Close session**.

---

## Reconcile / Z-read (supervisor only)

**What it is.** *Reconcile* is the final step that closes the books on a session. It posts the cash variance to the accounts and moves the session from CLOSED to RECONCILED. The **Z-read** is the formal end-of-shift report it produces.

**Why it exists.** Reconcile turns a counted drawer into an accounting fact. It is deliberately kept separate from the count so that finalising the money is a supervisor's decision, not the cashier's.

**When it happens.** Once, after the session has been **CLOSED**, by a supervisor (a user allowed to reconcile). Cashiers without that right do not see **Reconcile (Z-read)** in their menu at all.

**How it works.** The supervisor confirms, and the server posts a balanced entry for any non-zero variance (an **over** to cash-over, a **short** to cash-short), then returns the Z-read. If the variance is exactly zero, nothing is posted. **This cannot be undone.**

> In OrbixPOS, **Reconcile (Z-read)** works on the session that is open on this till under the signed-in account — typically a supervisor who worked the till themselves. To reconcile a cashier's session, a supervisor normally uses the **POS Sessions** screen in the ERP.

### Step by step (supervisor)

1. Make sure the session has already been **closed** (its status reads **CLOSED**). Until then the menu row is faded with "Close the session first".
2. Open the **Session menu** and tap **Reconcile (Z-read)** ("Post the variance — this is final").
3. Read the **Reconcile session** confirmation: *"This finalises the session and posts the cash variance to the general ledger. This cannot be undone — but the Z-read can be reprinted afterwards, so nothing is lost if the paper jams."*
4. Tap **Reconcile** to proceed, or **Cancel** to back out.

The dialog now shows the **Z-read** report:

| Line | What it means |
|---|---|
| **Sales (all tenders)** | Everything taken on the session, with the **By tender** split underneath. |
| **Opening float** | The cash declared at open. |
| **Cash sales** | The cash part of the sales. |
| **Payouts** | Cash paid out, as a negative, with a line per payout type (**Paid out**, **Expense**, **Refund** for returned items). |
| **Expected** | The expected drawer cash. |
| **Counted** | The cash counted at close. |
| **Variance** | Counted − expected (red if short). |

The footer shows the invoice count for the session.

### The printed Z-read

The paper Z-read carries the same drawer figures, with the session number, cashier, and when the shift was opened, closed, reconciled and printed. Under the variance it spells the result out — *Drawer balances exactly.*, *Drawer is OVER by …* or *Drawer is SHORT by …*. Then, when the server is up to date, it shows **what the shift sold**:

| Section | What it shows |
|---|---|
| **SUMMARY** | Gross sales, discounts, net sales, the VAT inside them (*of which VAT*), and the number of transactions. |
| **CATEGORIES** | Sales per item category. |
| **DEPARTMENTS** | Sales per kitchen or bar station the items were sent to. Items never sent to a station (for example counter sales) show as **Unassigned**. |
| **TEAM MEMBERS** | Sales per waiter — or the cashier, for counter sales — with how many sales each one made, for example *Asha (14)*. |

Each section adds up to the shift's total sales. A section with nothing in it is left out, and every line fits 58 mm and 80 mm paper. The Z-read ends with signature lines for the **Cashier** and the **Manager**, and *\*\*\* NOT A FISCAL RECEIPT \*\*\**. These sales sections appear on the printed Z-read; the on-screen Z-read shows the drawer figures.

5. To print the Z-read, tap **Print** (on a Windows till or an Android device). Printing a Z-read is a manager's job, and since you are allowed to reconcile tills (a Branch Manager is, by default), you are that manager: it prints straight away, with no approval dialog. (A cashier who prints a Z-read *reprint* is asked for a manager's approval instead — see **Reprinting the Z-read** below.)
6. Tap **Finish shift**. The session is RECONCILED and OrbixPOS returns you to the **Open shift** screen, ready for the next shift.

| What you see | What to do |
|---|---|
| **Reconcile (Z-read)** is not in the menu | Your account cannot reconcile. Ask a supervisor. |
| **Session must be CLOSED before reconciliation.** | The session has not been counted yet. Close it first. |
| An error about missing accounts setup | The cash-over/short accounts are not set up on the server, so the reconcile is refused rather than posting an incomplete entry. Ask your administrator to complete the accounts setup, then try again. |

> Once a session is RECONCILED it is final and locked. To keep selling, open a fresh session (a new shift) on the till.

### Reprinting the Z-read

If the Z-read paper jams or you need another copy, use **Z-read (reprint)** in the Session menu ("The final figures for a reconciled session") **before** you tap **Finish shift** — once you finish, the till moves on to the next shift. Before reconciliation the row reads "Available once the session is reconciled". If you are not allowed to view sessions, a manager approves opening the reprint first.

When you tap **Print** on the reprint:

- If you are allowed to reconcile tills (a Branch Manager, by default), it prints straight away — no approval dialog.
- If a manager already approved opening this reprint, that approval covers the print too.
- Anyone else — for example a cashier who can view the session but not reconcile it — sees a **Manager approval — Z-read** dialog first ("Print the end-of-shift Z-read for this session."). The approver must be a different person, who is allowed to reconcile tills. After printing you see **Approved by …** with the manager's name.

The reprint shows the note "This is a reprint. The figures are identical to the original — the Z-read is read-only and posts nothing." and prints marked `*** REPRINT ***`. After **Finish shift**, the session's Z-read can still be viewed in the ERP, on the **POS Sessions** screen.

---

## Printing drawer reports

The X-read and Z-read print on the same receipt printer as your receipts, at the paper width set in **Setup & diagnostics**. Things to know:

- **Print** appears on a Windows till and on an Android device with a paired Bluetooth printer. The web version has no **Print** button.
- If no printer is set up you see **No receipt printer set — configure one in Setup.** Setup is on the sign-in screen (see Chapter 1).
- Printing a report **never opens the cash drawer**.
- Every report ends with *\*\*\* NOT A FISCAL RECEIPT \*\*\**; the X-read also says *X-read does not close the shift and resets nothing.*
- If the printer is off or out of paper, you see a message naming the printer — see the *Troubleshooting* chapter (Chapter 7).

---

## End-of-shift checklist

Work through this every time you finish a shift:

1. **Finish open work.** Complete or clear any sale in progress, recall and finish or clear any sale on hold, and answer any **Unfinished sale** prompt. In a restaurant, check which tables are still open — they carry over to the next shift, but make sure none is forgotten.
2. **Send offline sales.** If the till sold offline, make sure **Offline sales** shows nothing waiting — a shift with unsent offline sales cannot be closed.
3. **Record any last payouts and expenses.** If you removed cash for a safe drop, record it via **Cash payout** (money back to a customer goes through **Return items** or **Refund / reverse**, never a payout); if you paid a business cost from the till, record it via **Till expense** — so the expected figure is right.
4. **(Optional) Run an X-read.** **Session menu → X-read** to check the sales and payouts so far. A cashier's X-read does not show the expected cash: count the drawer *blind* and let the supervisor compare.
5. **Count the drawer.** Count all cash, including the opening float. Count twice.
6. **Close the session.** **Session menu → Close session**, enter **Counted cash**, tap **Close session**, then **Done**. A supervisor also sees the **Variance** here (green = balanced/over, red = short); a cashier sees only the counted figure.
7. **Reconcile (supervisor).** A supervisor runs **Reconcile (Z-read) → Reconcile**, prints the Z-read (no approval needed — the supervisor is the manager), and taps **Finish shift** — or reconciles the session later in the ERP.
8. **Hand over the cash** per your branch's procedure, then **Sign out** (from the **Session menu** or the **Open shift** screen).

> Reprinting a receipt — from **Today's sales** or **Recent receipts** — never creates a new sale, never changes your drawer figures, and never opens the cash drawer. The drawer opens only on the first print of a sale's own receipt — see Chapter 6.
