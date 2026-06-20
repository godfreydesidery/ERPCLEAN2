# Starting and Ending a Shift

Every selling day at the till runs inside a *shift*. This chapter explains what a till and a cash session are, how to open your shift in the morning, how to use the session menu through the day (mid-shift reports, cash payouts), and how to count down and close the drawer at the end. It is written for cashiers; the steps marked **supervisor** are for the person who is allowed to finalise the day.

You should already be signed in and at the **Open shift** screen. If you are not yet signed in — or the app is asking you to set the ERP host and **Test connection** — see the *Getting Started* chapter (Chapter 1) first, then come back here.

---

## What a till and a cash session are

**What a till is.** A *till* (also called a register) is a named point-of-sale station on your branch — for example **Front counter 1** or **Pharmacy desk**. It is set up once by your store manager and lives on the ERP server, not on this device. Several devices can, over the day, use the same till, but only one cashier can have it open at a time.

**What a cash session is.** A *cash session* is one cashier's shift on one till. It begins when you declare how much cash is in the drawer to start (the *opening float*), it gathers every sale and every cash payout you make, and it ends when you count the drawer and the server works out whether the cash matches. A session has a human-readable number (for example `POS-0001`) and moves through exactly three states, one way only:

```
OPEN  →  CLOSED  →  RECONCILED
```

| State | What it means |
|---|---|
| **OPEN** | The shift is live. You can ring sales, take payment, record payouts, and run a mid-shift report. |
| **CLOSED** | The drawer has been counted. No more sales can be rung against this session. The server has calculated the difference between counted and expected cash (the *variance*). |
| **RECONCILED** | A supervisor has finalised the session and posted any cash difference to the accounts. This is the end of the road — the session is locked for good. |

**Why this exists.** The session is how the business accounts for cash. Because it records the float you started with, every cash sale, and every payout, the server always knows how much money *should* be in the drawer at any moment. At close, that expected figure is compared against what you actually counted, so over- and short-drawers are caught on the same shift they happen.

**Who is authoritative.** OrbixPOS owns no money figures of its own. The ERP server is the authority for sales totals, expected cash, and the variance. Numbers you see on screen before the server replies are a helpful preview — the figures that count are the ones the server sends back at X-read, close, and reconcile.

> A till can have **only one OPEN session at a time.** If someone left a session open on the till you want, you will see a message that the till already has an open session. Either pick a different till, or have that earlier session closed and reconciled first.

---

## Opening your shift

**What opening a shift is.** Opening a shift creates a new OPEN session: you choose how the till behaves (the business mode), pick which till you are working on, and declare the starting cash. From the moment it opens, every sale you ring belongs to this session.

**When it happens.** Once at the start of each shift, before you ring your first sale. If you sign in and there is no open session for you, the app takes you straight to the **Open shift** screen.

**How it works.** The screen shows the company and branch you are working in at the top, with your name and **@username** on the right. Below that are three things to set, in order: the **Business mode**, the till, and the **Opening float**. When you press **Open session** the app asks the server to create the session and then drops you onto the register, ready to sell.

### Step by step

1. Check the header. Under the **Open shift** title you will see your **company · branch**. Make sure this is the branch you mean to sell on. (To change who you are, use the **Sign out** icon at the top right.)
2. Under **Business mode**, choose how this till should behave by tapping one of the three cards:

   | Mode | Card label | Best for |
   |---|---|---|
   | 🛒 | **Supermarket** | Fast scanner-first grocery checkout |
   | 💊 | **Pharmacy** | Dispensing with patient & Rx capture |
   | 🍽 | **Restaurant** | Table service with order tickets |

   The selected card is highlighted. You can change mode later from the register's top bar, but only while no sale is in progress — so it is best to pick the right one now.
3. Under **Choose a till**, tap the till you are working on. Only **active** tills for your branch are shown; each tile shows the till **name**, its short **code**, and a small green dot. The tile you pick is highlighted.

   > If the list reads **No active tills on this branch yet**, ask your store manager to create or activate a till for you. Store managers (anyone with the till-management permission) see a **New till** button on this screen and can add one on the spot.
   >
   > If it reads **Could not load tills**, your connection to the server dropped. Tap **Retry**.
4. In **Opening float**, type the amount of cash you are starting the drawer with, in your branch currency (the field label shows the currency, e.g. **Opening float (TZS)**). Count the float first and enter the exact figure. If you are starting with an empty drawer, leave it as `0`.
5. Press **Open session**.

The app creates the session and takes you to the register. The button shows a busy spinner while it works; if anything goes wrong, a short message appears at the bottom of the screen — read it, fix the cause, and try again.

| What you see | What to do |
|---|---|
| "Pick a till first." | You pressed **Open session** without selecting a till tile. Tap a till, then try again. |
| "Till … already has an OPEN session." | Another session is still open on that till. Pick a different till, or have the open one closed and reconciled first. |
| A message about a missing sales agent | Your user account is not linked to a sales agent, so you cannot ring sales. Ask your administrator to link one to you. (The super-admin / root account can never be a sales agent.) |

---

## The session number and status chips

Once your shift is open, look at the **top bar** of the register. From the left you will see:

- The **OrbixPOS** brand mark.
- A **store** chip showing your **branch** and **company**.
- A green **Session** chip showing your session number (for example `POS-0001`). This green chip is your at-a-glance confirmation that a shift is live on this device.
- In the middle, the **mode switcher** (🛒 Supermarket / 💊 Pharmacy / 🍽 Restaurant) — switching is blocked while a sale is in progress.
- On the right, the **Session menu** button (the ☰ menu icon) and your avatar.

> If you ever lose track of which till or session you are on, open the **Session menu** (below). The top of that panel always shows the **Session** number, the **Status**, the time it was **Opened**, and the opening **Float**.

---

## The session menu

**What it is.** The session menu is a side panel that holds every shift-level action that is not part of ringing a sale: mid-shift reports, cash payouts, receipt look-ups, and closing or reconciling the drawer.

**How to open it.** On the register's top bar, tap the **Session menu** button — the ☰ menu icon near your avatar on the right. The panel slides in from the right. Tap the **✕** at its top, or tap the dimmed area beside it, to close it again without doing anything.

**What it shows.** At the top, a small grid of facts about the current session:

| Tile | Meaning |
|---|---|
| **Session** | The session number (e.g. `POS-0001`). |
| **Status** | `OPEN`, `CLOSED`, or `RECONCILED`. |
| **Opened** | The time the shift was opened. |
| **Float** | The opening cash you declared. |

Below the facts is the list of actions:

| Action | What it does | Who can use it |
|---|---|---|
| **X-read** | A mid-shift drawer report. Does **not** close the session. | Anyone who can view the session. |
| **Cash payout** | Records cash leaving the drawer — a refund or a paid-out. | The cashier on the shift. |
| **Today's sales** | Looks up and reprints any receipt from the server. | Anyone. |
| **Recent receipts** | Reprints a receipt saved on **this device** (works offline). | Anyone. |
| **Close session** | Count the drawer; the server computes the variance. | The cashier (or a supervisor). |
| **Reconcile (Z-read)** | Posts the variance to the accounts and finalises the day. | **Supervisors only.** |

> Actions you do not have permission for are dimmed and cannot be tapped. **Reconcile (Z-read)** also carries a small padlock for cashiers, because only a supervisor may finalise the session. The panel also has a **Sign out** button at the bottom — use it only when you are done and your session is closed.

There is also a **Sign out** action at the very bottom of the panel. Signing out does **not** close your session — your shift stays OPEN on the server until you actually close it.

---

## X-read — a mid-shift drawer report

**What it is.** An *X-read* is a snapshot of where the drawer stands right now: the float you started with, sales taken so far, payouts made, and the cash the server expects to be in the drawer. It is read-only.

**Why it exists.** It lets you (or a supervisor) check the drawer mid-shift — for a spot count, a cash drop decision, or a shift handover — without ending the session. Unlike the close at the end of the day, an X-read changes nothing and can be run as many times as you like.

**When it happens.** Any time during an open shift. The session must still be **OPEN**; once it is closed, use the Z-read at reconcile instead.

**How it works.** OrbixPOS asks the server for the live totals and shows them in a small **X-read** box. The figures come from the server, so they reflect finalised sales tagged to this session.

### Step by step

1. Open the **Session menu** (the ☰ button).
2. Tap **X-read**.
3. Read the report:

   | Line | What it means |
   |---|---|
   | **Opening float** | The cash you declared at open. |
   | **Sales** | The total of cash sales rung on this session so far. |
   | **Payouts** | Cash that has left the drawer (refunds and paid-outs), shown as a negative. |
   | **Expected cash** | What the server says should be in the drawer right now: float + sales − payouts. |

   The footer shows how many invoices have been rung (for example `23 invoices`).
4. Tap **Close** to dismiss the report. Nothing has changed — your shift is still open.

> An X-read is a **cash-drawer** report, not an accounting report. It tells you what cash to expect in the till; it does not mean the wider stock and ledger postings for those sales have all completed yet.

---

## Recording a cash payout

**What it is.** A *cash payout* records money physically leaving the drawer during the shift, so the expected-cash figure stays honest. There are two kinds.

| Type | Card label | Use it for |
|---|---|---|
| **Refund** | **Refund** | Cash handed back to a customer (for example, a cash-drawer refund when you are not reversing a whole sale). |
| **Paid out** | **Paid out** | Any other cash that leaves the drawer — a drop to the safe, or a small petty payment. |

Both kinds **reduce** the cash the server expects in the drawer; there is no "cash in" — the drawer only ever gains cash through sales.

**Why it exists.** If you take cash out of the drawer without telling the system, your end-of-shift count will look short by that amount. Recording the payout keeps the expected figure matched to reality, so a genuine over/short is not masked.

**When it happens.** Whenever cash leaves the drawer for a reason that is not change on a sale — a refund paid in cash, a drop to the safe, or a petty payment. The session must be **OPEN**.

### Step by step

1. Count out and remove the cash from the drawer first.
2. Open the **Session menu** and tap **Cash payout**.
3. At the top of the **Cash payout** dialog, choose the type: **Refund** or **Paid out**.
4. In **Amount**, type how much cash is leaving (in your branch currency). It must be greater than zero.
5. In **Reason**, type a short note — for example `drawer-to-safe drop` or `cash refund, damaged item`. This helps anyone reviewing the drawer later.
6. Tap **Record**.

You will see **Payout recorded**, and the dialog closes. The amount is now subtracted from your expected cash. To cancel without recording anything, tap **Cancel**.

| What you see | What to do |
|---|---|
| "Enter an amount." | The amount was blank or zero. Type a positive amount and tap **Record** again. |
| A message that the session is not open | The shift has already been closed or reconciled — payouts are only allowed while OPEN. |

> A cash payout is **not** the way to reverse a whole sale. To reverse a sale, open its receipt and use **Refund / reverse** (a supervisor action — see the *Receipts and Refunds* chapter, Chapter 6). Use a payout only for cash that leaves the drawer outside the normal sale flow.

---

## Closing the session

**What it is.** *Closing* the session ends selling for the shift. You physically count the cash in the drawer and enter the total; the server compares it against the expected figure and works out the **variance** — the difference between what you counted and what should be there.

**Why it exists.** The close is the moment of truth for the drawer. It pins down, on the spot, whether the till is balanced, over, or short, and it freezes the session so no further sales can be added.

**When it happens.** Once, at the end of your shift, after the last sale and any final payouts. The session must be **OPEN** to close it. **No money is posted to the accounts at this step** — that is the separate reconcile step that follows.

**How it works.** You enter the counted cash; OrbixPOS sends it to the server. The server computes:

- **Expected cash** = opening float + cash sales − payouts
- **Variance** = counted cash − expected cash

A **positive** variance means the drawer is **over** (more cash than expected); a **negative** variance means it is **short** (less cash than expected); **zero** means it balances exactly.

### Step by step

1. Count all the cash in the drawer carefully, including the opening float.
2. Open the **Session menu** and tap **Close session**.
3. In the **Close session** dialog, under "Count the drawer and enter the cash total", type the figure into **Counted cash** (in your branch currency).
4. Tap **Close session**.

The dialog now reads **Session closed** and shows the result:

| Line | What it means |
|---|---|
| **Expected** | What the server expected to be in the drawer. |
| **Counted** | The figure you entered. |
| **Variance** | Counted − expected. Shown in **green** when the drawer balances or is over, and in **red** when it is short. |

Under the variance, a note reminds you: *"Reconcile (Z-read) posts this variance — supervisor."*

5. Tap **Done** to dismiss the result.

| What you see | What to do |
|---|---|
| "Enter the counted cash." | The **Counted cash** field was empty or not a number. Enter your count and tap **Close session** again. |
| A red (short) variance | The drawer is short of what was expected. Re-count the cash before reconciling; note the shortfall and follow your branch's cash-handling procedure. |
| A message that the session is not open | The session was already closed (or reconciled). It cannot be closed twice. |

> Counting honestly matters more than counting "to balance". The variance is meant to surface real differences. If you are short or over, record the true count — do not adjust your figure to hide it. The reconcile step will post the difference to the accounts, where it can be reviewed.

---

## Reconcile / Z-read (supervisor only)

**What it is.** *Reconcile* (the *Z-read*) is the final step that closes the books on a session. It posts the cash variance to the accounts and moves the session from CLOSED to RECONCILED. The Z-read is the formal end-of-shift report.

**Why it exists.** Reconcile is what turns a counted drawer into an accounting fact. Posting the over/short to the ledger keeps the company's cash records accurate, and it is deliberately kept separate from the count so that finalising the money is a supervisor's decision, not the cashier's.

**When it happens.** Once, after the session has been **CLOSED**, by a supervisor (a user with the reconcile permission). A cashier without that permission will see **Reconcile (Z-read)** with a padlock and cannot run it — find a supervisor.

**How it works.** The supervisor confirms, and the server posts a balanced journal for any non-zero variance (an **over** increases cash and records a cash-over; a **short** records a cash-short), then returns the Z-read. If the variance is exactly zero, nothing is posted. **This cannot be undone.**

### Step by step (supervisor)

1. Make sure the session has already been **closed** (its status reads **CLOSED**).
2. Open the **Session menu** and tap **Reconcile (Z-read)**.
3. Read the confirmation: *"This finalises the session and posts the cash variance to the general ledger. This cannot be undone."*
4. Tap **Reconcile** to proceed, or **Cancel** to back out.

The dialog now shows the **Z-read** report:

| Line | What it means |
|---|---|
| **Opening float** | The cash declared at open. |
| **Sales** | Total cash sales for the session. |
| **Payouts** | Cash paid out (refunds and paid-outs), as a negative. |
| **Expected** | The expected drawer cash. |
| **Counted** | The cash the cashier counted at close. |
| **Variance** | Counted − expected (red if short). |

The footer shows the invoice count for the session.

5. Tap **Finish shift**. The session is now **RECONCILED** and OrbixPOS returns you to the **Open shift** screen, ready for the next shift.

| What you see | What to do |
|---|---|
| **Reconcile (Z-read)** is dimmed with a padlock | You lack the reconcile permission. Ask a supervisor to finalise the session. |
| A message that the session must be CLOSED first | The session has not been counted yet. Close it (count the drawer) before reconciling. |
| An error about a missing accounts configuration | The cash-over/short accounts are not set up on the server. The reconcile is refused so it cannot post an incomplete entry — ask your administrator to complete the accounts setup, then try again. |

> Once a session is RECONCILED it is final and locked. To keep selling, open a fresh session (a new shift) on the till.

---

## End-of-shift checklist

Work through this every time you finish a shift:

1. **Finish open work.** Complete or clear any sale in progress — you cannot switch mode or close cleanly with a sale half-rung.
2. **Record any last payouts.** If you removed cash for a safe drop or a cash refund, record it via **Cash payout** so the expected figure is right.
3. **(Optional) Run an X-read.** Open the **Session menu → X-read** to preview the expected cash before you count.
4. **Count the drawer.** Count all cash, including the opening float.
5. **Close the session.** **Session menu → Close session**, enter **Counted cash**, tap **Close session**. Note the **Variance** (green = balanced/over, red = short).
6. **Reconcile (supervisor).** Have a supervisor run **Reconcile (Z-read) → Reconcile → Finish shift** to post the variance and finalise the day.
7. **Hand over the cash** per your branch's procedure, then **Sign out** (from the **Session menu** or the **Open shift** screen).

> Reprinting a receipt — from **Today's sales** or **Recent receipts** — never creates a new sale and never affects your drawer count, so it is always safe to do, before or after you close.
