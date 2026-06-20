# OrbixPOS — User Manual

_ERPCLEAN2 — modular-monolith ERP (Spring Boot + Angular + PostgreSQL). Generated from the live codebase + the verified test-case suite._

## Contents

1. Getting Started
2. Starting and Ending a Shift
3. Selling — Supermarket
4. Selling — Pharmacy and Restaurant
5. Taking Payment
6. Receipts and Refunds
7. Troubleshooting and Good Practice
8. For Supervisors and Store Managers

---

# Getting Started

Welcome to OrbixPOS — the till app you use to ring up sales, take payment, and print receipts. This chapter is for a first-time cashier. It explains what OrbixPOS is and the devices it runs on, how to point it at your ERP server the first time, how to sign in (and what to do when sign-in fails), how to read the screen, the three business modes and how to switch between them, and how to sign out.

You do not need to read it cover to cover before your first shift. Skim the headings, do the **First-run setup** and **Signing in** steps once, then come back to the rest as you need it.

---

## What OrbixPOS Is

**What it is.** OrbixPOS is the front-of-counter till program. You use it to scan or pick items, take a customer's payment (cash, card, mobile money, cheque, or a split of several), and hand over a printed receipt. It is the screen you will spend your whole shift on.

**Why it exists.** A shop needs a fast, reliable till that any cashier can pick up in minutes — and one that never accidentally charges a customer twice. OrbixPOS is built for exactly that: a clean, scanner-first counter screen that does the ringing and the paying, and leaves the heavy bookkeeping to the central system behind it.

**When you use it.** Every shift, from the moment you sign in and open your till to the moment you count your drawer and close it at the end of the day.

**How it works.** OrbixPOS does not store your shop's prices, stock, or customers itself. It is a *client*: every time you add an item or take a payment, it asks your ERP server — the central system your shop runs on — over the network, and shows you what the server says. This matters in one important way:

> The server is the single source of truth for price, VAT (tax), totals, and your cash variance. The money you see on screen **before** you take payment is a helpful preview, not the final word. The figures that matter — and the printed receipt — come from the finalised invoice the server sends back **after** you take payment. If a preview total and a printed total ever differ, the printed receipt is correct.

Because OrbixPOS leans on the server for everything important, it needs to know where that server is. That is the very first thing you set up.

### The devices it runs on

OrbixPOS is one app that runs on three kinds of device. The screens, buttons, and steps in this manual are the same on all three.

| Device | Notes |
|---|---|
| **Windows desktop** | The main way most counters run OrbixPOS. A USB barcode scanner and a receipt printer usually plug in here. |
| **Web browser** | The same till, opened as a web page. Handy for a quick station or a back-office check. |
| **Android** | A phone or tablet till — useful for table-side or mobile selling. |

> **About the barcode scanner.** In this version the scanner works as a *keyboard wedge*: a USB scanner that simply "types" the barcode into whatever field is focused, exactly as if you had typed it yourself very fast. You do not configure it in OrbixPOS — you just make sure the right field is focused (the app does this for you on the sell screen) and scan.

> **About the printer, cash drawer, and scale.** In this version these are not yet driven directly. When you press **Print**, OrbixPOS shows a confirmation that the receipt is ready rather than physically printing. Real printer, drawer, and scale drivers are planned for a later release. Everything else — ringing, paying, receipts, closing — works fully today.

---

## First-Run Setup — Pointing the Till at Your Server

**What it is.** A one-time step where you tell OrbixPOS the network address of your ERP server. This is called the **ERP host**.

**Why it exists.** OrbixPOS owns no data — it calls the server for everything. Until it knows the server's address, it cannot sign you in or ring a single sale. Setting the host once, correctly, is what connects the till to your shop.

**When it happens.** Once, when OrbixPOS is installed on a new device — typically done for you by the person who set up your till. You will only need it yourself if you are setting up a brand-new device, or if the till says it cannot reach the server (for example after a network change).

**How it works.** You open the **Server setup** dialog, type or check the host address, press **Test connection** to confirm the till can reach the server, and **Save**. OrbixPOS adds the technical `/api/v1` part of the address for you, so you only type the plain host. The address is remembered on the device, so you do not repeat this every day.

To set or check the ERP host:

1. On the sign-in screen, click **Server setup** (the small link with a gear icon below the **Sign in** button). The **Setup & diagnostics** dialog opens.
2. In the **ERP host** field, type the address your administrator gave you, for example `http://erp.yourshop.com:8081` or `http://localhost:8081`. Type only the host — do **not** add `/api/v1` yourself; the till adds it automatically (the dialog reminds you of this).
3. Click **Test connection**. The till tries to reach the server and tells you the result:

| What the dialog says | What it means | What to do |
|---|---|---|
| **Reachable — ERP is UP.** (green) | The till reached the server and the server is healthy. | Click **Save**. You are ready to sign in. |
| **Reached host, status unclear.** | The till reached *something* at that address, but it did not answer as a healthy ERP. | Double-check the address with your administrator — you may have the wrong host or port. |
| **Could not reach the ERP at this host.** (red) | The till could not reach anything at that address. | Check the address for typos, check that the device is on the right network, and ask your administrator if the server is running. |

4. When the test is green, click **Save**. The dialog closes and the address is stored on this device. (Click **Cancel** instead to leave the address unchanged.)

> You can reopen **Server setup** any time from the sign-in screen to check or change the host — for example if your shop's server moves to a new address.

---

## Signing In

**What it is.** Signing in proves who you are to the system. You give the username and password your administrator created for you; the system checks them and starts your personal session. From that moment on, everything you ring is recorded against *you*.

**Why it exists.** Only named people should be able to take money and open a till, and the shop needs to know which cashier did what. Signing in is what ties each sale, each drawer, and each receipt to a real person.

**When it happens.** At the start of every shift, and again any time your session ends — for example if you sign out, or if the till is left idle long enough that the server ends the session.

**How it works.** When your username and password are accepted, OrbixPOS loads your details and the shop (company and branch) you belong to, then takes you straight to the **Open shift** screen to start your day. It also reads what you are allowed to do (your permissions): actions you are not permitted to use are hidden or greyed out, so you never see a button you cannot press.

To sign in:

1. Open OrbixPOS. The **Sign in** screen appears, headed "Sign in" with the line "Open your till and start your shift."
2. In the **Username** field, type the username your administrator gave you. (The cursor starts here for you.)
3. In the **Password** field, type your password. It is hidden as you type.
4. Click **Sign in** (the large indigo button). You can also just press **Enter** from the password field.

If your details are correct, the button shows a brief spinner and then OrbixPOS opens the **Open shift** screen, where you pick your mode and till and start your session. Opening a shift is covered in the next chapter.

> Keep your password to yourself. Because every sale and every drawer count is recorded against the person signed in, never sign in for a colleague or let someone use your session.

### Sign-in problems

If something is wrong, a red banner appears just above the **Sign in** button with a short message. Here is how to read the common ones:

| What you see | What to do |
|---|---|
| A red banner saying your username or password is wrong | Re-type your username and password carefully (mind the Caps Lock key). If it still fails, ask your administrator to confirm your account and reset your password. |
| "Your session ended. Please sign in again." | Your previous session was ended by the server (for example, after being idle, or the server restarted). Just sign in again. Nothing you finalised is lost — finalised sales live on the server. |
| A banner mentioning the server could not be reached, or sign-in seems to hang | The till cannot reach the server. Open **Server setup**, press **Test connection**, and follow the **First-Run Setup** table above. Check your network and ask your administrator if the server is running. |
| Sign-in succeeds but you are told you cannot ring sales | Your user account is not yet linked to a sales-agent record, which the shop requires before you can sell. Ask your administrator to link one to your account (this is a quick fix for them). See the note below. |

> **You must be set up as a cashier to sell.** The shop requires every selling cashier's user account to be linked to an internal sales-agent record. This is done once by your administrator. The shop's top-level super-admin account cannot be a sales agent, so it cannot ring sales — sell with the personal cashier account your administrator gave you, not a shared admin login. If a sale is ever refused with a message about a missing agent, that is the cause: ask your administrator to link an agent to your account.

---

## The Screen Layout

Once you have signed in and opened a shift, you spend your shift on the **register** (the sell screen). The middle and lower part of this screen changes with your business mode — that is covered under **The Three Business Modes** below. The strip along the very top, the **top bar**, is the same in every mode. Learn it once and you can find your way around anywhere in the app.

From left to right, the top bar contains:

- **The OrbixPOS brand** — the diamond mark (◆) and the word **OrbixPOS** on the far left. It is just a label; it does nothing when clicked.
- **The branch chip** — a small pill with a shop icon showing the **branch** you are working in (its name), with your **company** name beside it. A branch is the specific shop or location this till belongs to. This is shown for your awareness; you do not switch branches from here.
- **The session chip** — a green pill reading **Session** followed by your session number. This appears once you have opened your shift, and confirms your till session is open. A session is your run on this till — from opening the drawer to closing and reconciling it.
- **The mode switcher** — a pill-shaped switch in the centre showing the three business modes (**🛒 Supermarket**, **💊 Pharmacy**, **🍽 Restaurant**). The mode you are in is highlighted. See **The Three Business Modes** below.
- **The session menu button** — the **☰** (menu) icon toward the right, tooltip **Session menu**. Click it to open the **Session** panel that slides in from the right. This is where you find mid-shift and end-of-shift actions: **X-read** (a mid-shift drawer report), **Cash payout**, **Today's sales**, **Recent receipts**, **Close session**, and **Reconcile (Z-read)**. These are covered in the *Starting and Ending a Shift* chapter (Chapter 2).
- **Your avatar** — a small round badge on the far right showing your initials. It tells you, at a glance, who is signed in.

> The **Session** panel (opened from **☰**) also has its own **Sign out** button at the bottom — handy when you have finished a shift, since you usually reach it just after closing your session.

---

## The Three Business Modes

**What they are.** OrbixPOS comes in three flavours of sell screen — called **business modes** — each tuned for a different kind of shop. The way you take payment, print receipts, and run your session is identical in all three; only the *register* (how you add items to the sale) changes.

**Why they exist.** A grocery cashier wants to scan barcodes fast; a pharmacy needs patient and prescription details; a restaurant works in tables and order tickets. One till would feel wrong for all three, so OrbixPOS gives each its own purpose-built register while keeping everything else the same — so the skills you learn in one mode carry straight over.

**When you choose a mode.** You pick a mode when you open your shift (covered in the next chapter), and you can switch between modes during your shift using the switcher in the top bar — as long as you are not in the middle of a sale.

**How it works.** The three modes are:

| Mode | Best for | What the register looks like |
|---|---|---|
| **🛒 Supermarket** | Fast, scanner-first grocery checkout | A wide, Excel-style grid of sale lines on the left with a number pad on the right. You scan or type a code and the item drops in; you can adjust quantity and discount inline. |
| **💊 Pharmacy** | Dispensing with prescriptions | A patient/prescriber header (patient, prescriber, prescription number) above a dispensing line table, with the running totals down the side. |
| **🍽 Restaurant** | Table service | A floor/table picker and a menu grid that build an order ticket, with a **Send to kitchen** action. |

### Switching mode

1. Make sure the current sale is finished or cleared — you cannot switch mode while a sale is in progress (this prevents items from one mode being stranded in another).
2. In the top bar, click the mode you want in the mode switcher (**🛒 Supermarket**, **💊 Pharmacy**, or **🍽 Restaurant**). The new mode highlights and the register changes immediately.

> If you try to switch while a sale has items in it, OrbixPOS will not switch and shows the message **"Finish or clear the current sale before switching mode."** Complete or clear the sale first, then switch.

---

## Signing Out

**What it is.** Signing out ends your session and returns OrbixPOS to the sign-in screen, ready for the next person.

**Why it exists.** Because every action is recorded against the signed-in person, you should sign out when you step away or finish, so nobody can ring sales under your name. It also keeps the till tidy for the next cashier.

**When you do it.** At the end of your shift — normally after you have closed and reconciled your session — or any time you are leaving the till and want to secure it.

**How it works.** Signing out clears your session on this device and shows the **Sign in** screen again. It does not undo any sale: every sale you finalised is safely on the server. There are two places to sign out:

1. **From the Open shift screen** (before you have opened a session): click the **Sign out** icon (the door-with-arrow icon) at the top right, next to your name.
2. **From the Session panel** (any time during your shift): click the **☰** session menu button in the top bar, then click **Sign out** at the bottom of the panel that slides in.

> **Close and reconcile your session before you sign out at end of shift.** Signing out does not close your till session — it only ends your sign-in. If you sign out with the drawer still open, the session stays open on the server and must still be counted and closed. The right end-of-shift order is: **Close session** (count the drawer) → **Reconcile (Z-read)** (a supervisor step) → **Sign out**. These steps are covered in the *Starting and Ending a Shift* chapter (Chapter 2).

---

## Where to Go Next

Now that you can connect the till, sign in, read the screen, and choose a mode, you are ready to start your day:

- **Opening your shift** — picking a till and entering your opening float to begin selling.
- **Ringing a sale and taking payment** — adding items and handling cash, card, mobile money, cheque, and split payments.
- **Receipts** — printing, gift receipts, and reprinting from **Today's sales** or **Recent receipts**.
- **Sessions** — X-read, cash payouts, and closing and reconciling your drawer at end of shift.

Each of these has its own chapter in this manual.

---

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

---

# Selling — Supermarket

This chapter walks you through ringing up a sale on the **Supermarket** register: the fast, scanner-first till mode built for grocery and convenience shops. By the end you will be able to add items by scanning, searching, or typing a code; fix a quantity or apply a line discount; void or remove a line; clear an age check; choose a customer; and finish with **PAY**.

Before you can sell, you must be signed in and have an **open shift** (a cash session on a till). If you have not done that yet, see the *Starting and Ending a Shift* chapter (Chapter 2). Switching register modes is blocked once a sale is in progress, so the till stays in the mode you opened the shift in until the basket is empty.

> **A note that runs through this whole chapter.** Every amount you see on screen *before you take payment* is a **preview** — a helpful estimate that lets you and the customer see roughly what is owed. The ERP server is the authority for price, VAT, discounts and the final total. When you press **PAY** and complete the sale, the server re-calculates everything and returns the real figures; the printed receipt is built from that finalised invoice, not from the preview. So if a previewed price looks slightly off, the figure on the receipt is the one that counts.

---

## 1. The supermarket screen at a glance

**What it is.** The supermarket register fills the screen with two areas side by side: a wide **line grid** on the left (a spreadsheet-style list of everything in the basket) and a narrower **number pad** panel on the right.

**Why it exists.** Grocery checkout is high-volume and fast. The grid keeps the whole basket visible at a glance like a till roll, and the number pad lets you fix a quantity or discount without leaving the keyboard. The layout is designed so most sales need nothing but the scanner and the **PAY** button.

**When you use it.** Every supermarket-style sale, from a single chocolate bar to a full trolley.

**How it is laid out.**

| Area | Where | What it does |
|---|---|---|
| Search / scan bar | Top of the left side | Where scans land and where you type a code or name. It stays focused so a scanner can fire straight into it. |
| Line grid | Left, below the search bar | One row per basket line, with columns **# · Code · Item · Unit · Qty · Price · Disc · Total** and two action columns. |
| Total card | Top-right | The big running **TOTAL**, the line and item counts, and the "preview — ERP is authoritative" reminder. |
| Customer chip | Right, under the total | Shows who the sale is for. Defaults to **Walk-in**. Tap to change. |
| Qty / Disc toggle | Right, mid-panel | Chooses what the number pad will set on the selected line. |
| Number pad | Right, lower | Digits, decimal point and backspace, plus **Clear** and **Set qty** / **Set disc**. |
| **PAY** button | Bottom-right, green | Opens payment for the basket. |

> **Tip.** When the basket is empty the grid shows a shopping-cart icon and the words **Scan or search to add items**. That is your cue that nothing has been added yet.

---

## 2. Adding items

There are three ways to put a product in the basket, and the search bar handles all three. You do not pick a method — you just scan or type, and the till works out what you meant in this order: first it checks for an exact product code, then it asks the server for a barcode match, and finally it does a name/code search.

> **The search bar is always listening.** After every item you add, the field clears itself and the cursor returns to it automatically, ready for the next scan. You should rarely need to click into it.

### 2.1 Scan a barcode

**What it is.** Reading the product's barcode with the handheld scanner.

**How it works.** The scanner behaves like a fast keyboard: it "types" the barcode into the focused search bar and presses Enter for you. You do not press any button — just aim and pull the trigger.

1. Make sure the basket screen is showing (the search bar shows a small scanner icon on the left).
2. Point the scanner at the product's barcode and scan.
3. The item drops into the grid as a new line and the search bar clears, ready for the next scan.

If you scan the **same** ordinary product twice, the till does not create two rows — it adds to the quantity on the existing line and keeps one tidy row.

> **Note.** The scanner is a plug-in USB device that types codes into whichever field has focus. If a scan seems to "go nowhere", click once inside the search bar so it has focus, then scan again.

### 2.2 Scale labels with an embedded weight or price

**What it is.** Some products — loose fruit, meat, cheese — are weighed at a deli scale that prints a special barcode label. That label has the weight (or the priced amount) baked into the barcode itself.

**Why it exists.** You cannot type a weight for every banana bunch. The scale already weighed it, so the label carries that information and the till reads it straight off the scan.

**How it works.** When you scan a scale label, the server decodes it and tells the till both the product *and* the weight or amount printed on it:

- An **embedded-weight** label sets the line's quantity to the weighed amount (for example 1.250 kg) automatically.
- An **embedded-price** label sets the line's price to the amount printed on the label.

1. Scan the scale label exactly as you would any barcode.
2. The item is added as its **own** line with the weight or price already filled in — it is never merged into another line, because each weighed pack is unique.
3. Carry on scanning the next item.

> **Tip.** You do not edit the weight on a scale-label line by hand. If the label is wrong, weigh the item again and scan the new label, then remove the old line (see *Removing a line* below).

### 2.3 Search by code or name

**What it is.** Finding a product by typing part of its code or name — useful when there is no barcode, the barcode will not read, or you are not sure of the exact item.

**How it works.** As you type, a drop-down list of matches appears under the search bar showing each product's **code**, its **name**, and an age badge if it is restricted.

1. Click into the search bar (or just start typing — it is usually already focused).
2. Type part of the product **code** or **name**, for example `milk` or `BR-0123`.
3. A results list appears. Tap the product you want; it is added to the basket and the field clears.

If your text matches **exactly one** product, the till adds it straight away without showing the list. If nothing matches, you get a short message: *No match for "…"*.

### 2.4 Type an exact code

**What it is.** Keying in a product's exact code and pressing Enter — handy when you know the code and want the fastest possible add.

**How it works.** If what you type is an exact, known product code, the till adds that product immediately — it does not wait for the search list.

1. Click into the search bar.
2. Type the exact product code.
3. Press **Enter** (or tap the indigo **+** button at the right end of the bar). The item is added.

> **What the + button does.** Pressing **Enter** and tapping **+** do the same thing: they submit whatever is in the search bar through the same code → barcode → search logic described above.

---

## 3. Working with lines in the grid

Each row in the grid is one **line** — one product, at one unit, with a quantity, a price preview, an optional discount, and a line total.

### 3.1 Reading a line

| Column | What it shows |
|---|---|
| **#** | The line number (1, 2, 3 …). |
| **Code** | The product code. |
| **Item** | The product name, with an orange age badge (**18+** / **21+**) if it is restricted. |
| **Unit** | The unit of measure (for example PCS, KG). |
| **Qty** | How many. Shows decimals for items sold by weight; whole numbers otherwise. |
| **Price** | The previewed unit price. A dash (—) means the price is still being fetched. |
| **Disc** | The line discount amount. A middle dot (·) means no discount. |
| **Total** | The previewed line total (quantity × price, minus any discount). |
| (blank) | The **remove** column — a small **×**. |
| **V** | The **void** column — a tick box. |

### 3.2 Selecting a line

**What it is.** Picking the line you want to work on. The selected line is highlighted, and the number pad always acts on it.

**How it works.** Tap anywhere on a line to select it. To edit a quantity or discount, tap directly on that line's **Qty** or **Disc** cell — doing so both selects the line *and* points the number pad at that field (the **Qty** / **Disc** toggle on the right flips to match, and the cell shows a soft highlight).

1. Tap the line you want.
2. The row highlights and the number pad is now aimed at it.

> **Tip.** If you tap the **Qty** cell, the number pad is set to change quantity. If you tap the **Disc** cell, it is set to change the discount. You will see the small **× Qty** / **− Disc** toggle on the right switch accordingly.

### 3.3 Changing the quantity

**What it is.** Setting how many of an item the customer is buying.

**How it works.** You type the new quantity on the number pad and press **Set qty** — this *replaces* the quantity (it does not add to it).

1. Select the line (tap its **Qty** cell to point the pad at quantity).
2. Make sure the toggle on the right reads **× Qty**.
3. Key the quantity on the number pad — for example `3`, or `1.250` for a weighed item (use the **.** key for the decimal).
4. Press **Set qty**. The line's **Qty** and **Total** update.

> **Note.** A quantity of zero or less is not allowed — the till keeps the quantity at a minimum of 1. To take the item off the sale entirely, remove the line instead (see below).

### 3.4 Applying a line discount

**What it is.** Taking a money amount off a single line — for example knocking TZS 500 off a slightly damaged pack.

**Why it exists.** Cashiers sometimes need to adjust a single item's price for a markdown or a goodwill gesture, without touching the rest of the basket.

**How it works.** The discount is a **money amount**, not a percentage. You type the amount on the number pad and press **Set disc**; the line total drops by that amount.

1. Select the line and tap its **Disc** cell (or flip the right-hand toggle to **− Disc**).
2. Key the discount amount on the number pad.
3. Press **Set disc**. The **Disc** column shows the amount in indigo and the **Total** drops.

To remove a discount, set it back to `0` and press **Set disc** again.

> **Warning.** A discount can never make a line total go below zero — the previewed total floors at zero. As always, the server has the final say on whether a discount is allowed; the preview is only a guide.

### 3.5 Clearing the number pad

If you mistype a number before pressing **Set qty** / **Set disc**, press **Clear** to wipe the number pad display back to `0`, or use the backspace key (the ⌫ key in the bottom-right of the digits) to delete one digit at a time.

### 3.6 Void versus remove — two different columns

These two action columns look similar but do different things, and it matters which you use.

| Column | Icon | What it does | Use it when |
|---|---|---|---|
| **Remove** (blank header) | **×** | Deletes the line completely — it disappears from the grid. | You added the wrong item, or you want it gone entirely. |
| **Void** (**V** header) | tick box | Marks the line **voided** — it stays visible, struck through and greyed out, but is left out of the total and out of the sale sent to the server. | The customer changed their mind but you want a record on screen that the item was scanned, or you may un-void it again. |

1. To **remove** a line, tap the **×** in the line's last-but-one column. It vanishes.
2. To **void** a line, tap the tick box in the **V** column. The line greys out with a line through it and stops counting toward the total. Tap the box again to bring it back.

> **Tip.** A voided line is *not* charged — it is excluded from the total and from what gets sent to the server when you take payment. Voiding is the safe way to "park" an item without deleting it.

---

## 4. Age-restricted items

**What it is.** Some products — alcohol, tobacco and similar — carry a legal minimum age. On the till these show an orange badge (**18+** or **21+**) next to the product name, both in the search list and on the basket line.

**Why it exists.** It is your responsibility, and the law's requirement, to confirm the customer is old enough before selling these items. The till makes that check an explicit, deliberate step so it cannot be skipped by accident.

**When it happens.** The badge is shown in the register so you know an item is restricted while you ring it, but the **confirmation prompt itself appears later** — on the **Payment** screen, when you press **Complete sale** (not when you press **PAY**). If the basket contains at least one restricted item, a dialog headed **Age-restricted items** appears before the sale can finish.

**How it works.** At **Complete sale**, the **Age-restricted items** dialog names the restriction (for example "This basket contains 18+ items") and asks you to confirm you have verified the customer's age. You check ID, then press **Age verified** to continue or **Cancel** to stop the sale (you will see *Sale stopped: age not verified.*).

> **Where the check is fully explained.** Because the prompt fires at checkout, the step-by-step is in the *Taking Payment* chapter (Chapter 5), under **Age-restricted items**. Only press **Age verified** when you have actually seen acceptable proof of age — the confirmation is recorded against the sale.

---

## 5. Choosing the customer

**What it is.** The **Customer** chip, under the total card on the right, names who the sale is for. Every sale has a customer; by default it is **Walk-in** — an anonymous cash customer — which is exactly right for most supermarket sales.

**Why it exists.** Some customers are registered (account holders, regulars on a loyalty scheme, businesses buying on terms). Attaching a registered customer ties the sale to their record. For a casual shopper, **Walk-in** is all you need.

**When you change it.** Only when the customer is a registered account you want this sale linked to. If in doubt, leave it on **Walk-in**.

**How it works.**

1. Tap the **Customer** chip on the right.
2. The **Customer** picker opens with a search box reading *Search name / code / phone…*.
3. Type part of the customer's **name**, **code**, or **phone number**. The list filters as you type.
4. Tap the customer you want. The picker closes and the chip now shows their name; a tick marks the currently selected customer in the list.

To go back to an anonymous sale, open the picker again and choose the walk-in entry (shown with a "walking person" icon and labelled **Walk-in**).

> **Tip.** You can set the customer before or after adding items — it does not affect what is in the basket.

---

## 6. Reading the running total

**What it is.** The dark **total card** at the top of the right-hand panel shows the live state of the basket: the big **TOTAL** in the shift's currency, then a line below it counting how many lines and how many items are in the basket.

**Why it exists.** It is the at-a-glance figure you read out to the customer and watch climb as you scan.

**How it works.** The total updates instantly every time you add, void, remove, re-quantity, or discount a line. Voided lines do not count. Underneath the total you will always see the small grey reminder **preview — ERP is authoritative**.

> **Remember.** This total is a preview. The amount the customer actually pays is the one the server returns when you complete the sale at the payment screen, and it is that finalised figure that prints on the receipt.

---

## 7. Finishing the sale — Pay

When everything is in the basket, you take payment.

1. Press the green **PAY** button at the bottom-right. (It is disabled while the basket is empty — add at least one item first; if you press an empty basket you will see *Add an item first*.)
2. The **Payment** screen opens. This is where you choose how the customer pays — cash, card, mobile money, cheque, or a split across several — and complete the sale.
3. If the basket has any age-restricted items, the **Age-restricted items** check (section 4) appears here before the sale can complete.
4. When the sale completes, the basket clears, the receipt is built from the finalised invoice, and the receipt view opens so you can print it.

Taking payment, change, split tenders, the safe-retry behaviour for an unknown outcome, and printing the receipt are all covered in the *Taking Payment* chapter (Chapter 5) and the *Receipts and Refunds* chapter (Chapter 6).

> **Note.** Pressing **PAY** does not yet charge anything — it only opens the payment screen. The sale is only made when you complete it there.

---

## 8. Quick troubleshooting

| What you see | What to do |
|---|---|
| The basket shows **Scan or search to add items** and nothing happens when you scan. | The search bar may not have focus. Click once inside it, then scan again. |
| A scan shows **No match for "…"**. | The code is not in this company's catalogue, or the barcode did not read cleanly. Try searching by name, or type the exact product code and press Enter. |
| You typed a number and nothing changed on the line. | You must press **Set qty** or **Set disc** to apply it. Also check a line is selected — if you see **Select a line first**, tap the line, then try again. |
| The **Price** column shows a dash (—). | The price preview is still loading from the server. It fills in a moment later; you can keep scanning meanwhile. The server prices the line for real at payment time regardless. |
| A line is greyed out with a line through it. | It is **voided** — it will not be charged. Tap its **V** tick box to bring it back, or its **×** to remove it for good. |
| The same product scanned twice but only one row appears. | That is correct for ordinary items — the quantity went up by one on the existing row. Check the **Qty** column. |
| **PAY** is greyed out. | The basket is empty (or every line is voided). Add at least one item. |
| Adding an item shows *"…: no usable unit configured."* | The product has no sellable unit set up. Ask your supervisor or administrator to fix the product's unit of measure; it cannot be sold until then. |
| The age-check dialog blocked the sale. | Verify the customer's age from photo ID. Press **Age verified** to continue, or **Cancel** and remove the restricted item. |

> **If a sale is refused with a message about a sales agent.** Every cashier who rings sales must have a sales-agent record linked to their user account. If you get a message that no agent is linked, you cannot ring sales until your administrator links one to you — ask them to do so. (The top-level super-admin account cannot be a sales agent and so cannot ring sales; use your own cashier login.)

---

# Selling — Pharmacy and Restaurant

This chapter shows you how to ring up a sale in OrbixPOS's two specialist tills: the **Pharmacy** register (for a dispensary, with a patient and prescription header) and the **Restaurant** register (for table service, with a floor plan, a menu grid and a kitchen ticket). It also covers how to **switch between modes** — and the guard that stops you switching in the middle of a sale.

If you work a supermarket checkout, see the previous chapter, *Selling — Supermarket* (Chapter 3). The way you take payment, print the receipt, and open and close your shift is **the same in all three modes** — only the part of the screen where you build the basket changes. Payment and receipts are covered in their own chapters; here we focus on building the basket and getting to the **PAY** button.

> **Before you read on.** A "register" (also called a "till mode") is the central selling screen — the place where you add items to the sale. OrbixPOS has three of them. Your store decides which one fits your business, and you pick it when you open your shift. Nothing in this chapter changes prices, tax or totals: the on-screen total is always a **preview**. The ERP server works out the real figures and prints them on the receipt.

---

## 1. Switching modes

**What it is.** The mode switcher is the pill-shaped control in the centre of the top bar. It shows all three registers side by side — **🛒 Supermarket**, **💊 Pharmacy** and **🍽 Restaurant** — with the one you are currently using highlighted in a dark pill.

**Why it exists.** A single device can serve more than one counter. A site might run a pharmacy counter and a small café from the same hardware, or a manager might want to demonstrate each layout. Rather than installing three apps, you switch the register in place. The shift, the till, the open cash session and your sign-in all stay exactly as they are — only the selling screen changes.

**When it happens.** You normally pick your mode once, when you **Open session** at the start of the shift (see the *Starting and Ending a Shift* chapter, Chapter 2). You only use the top-bar switcher if you genuinely need to change counter mid-shift.

**How it works.** Tap the mode you want in the switcher pill. The selling area below the top bar redraws into that register immediately. There is no reload and no re-login.

### 1.1 The mid-sale guard

> **You cannot switch modes while a sale is in progress.** If the current basket has any lines on it and you tap a different mode, OrbixPOS blocks the change and shows the message **"Finish or clear the current sale before switching mode."** The mode does not change.

This guard protects you: the three registers build the basket in slightly different ways, and carrying half-built lines across would leave the sale in a confusing state. To switch, first deal with what is on screen.

| What you see | What to do |
|---|---|
| The message **"Finish or clear the current sale before switching mode."** | The basket has items on it. Either take payment for the current sale (**PAY**), or remove every line first, then tap the mode again. |
| You tap your current mode and nothing happens | That is normal — you are already in that mode; tapping it again does nothing. |
| The mode you want is greyed-out or missing | Your administrator may have set this device up for a different counter. Ask your supervisor. |

To clear an unwanted sale, remove each line (the **✕** on every line — see the sections below). Once the basket is empty, the switcher lets you change mode.

---

## 2. The Pharmacy register

The Pharmacy register is for a dispensary counter. It looks like a clean dispensing worksheet: a **patient and prescription header** runs across the top, a **search box** sits below it, and a **dispensing line table** fills the main area. A summary panel with the **PAY** button is fixed down the right-hand side.

> **What "dispensing" means here.** Dispensing is simply ringing up the medicines and goods a customer is collecting. OrbixPOS records *what* was sold and *to whom*; it does not replace a pharmacist's clinical checks. The patient name, the prescriber and the prescription number you type are captured for the record and travel with the sale — they do **not** change the price.

### 2.1 The Rx / patient header

**What it is.** The bar across the top of the Pharmacy register. It has three parts, left to right: a **Patient** tile, a **Prescriber** field, and an **Rx #** field.

**Why it exists.** A dispensary needs to know who the medicine is for and who prescribed it. Recording the patient, the prescriber's name and the prescription (Rx) number gives the sale a traceable context for later look-up, queries and audits — without forcing you to register a full customer account for every walk-in.

**When you fill it in.** Set the header at the start of the sale, before or while you add the drugs. You can leave any part blank if you do not have it (for an over-the-counter sale with no prescription, leave **Prescriber** and **Rx #** empty).

**How it works.** Whatever you enter here is gathered into the **sale notes** when you press **PAY**, so it is attached to the finalised invoice. It is a note for the record, not a pricing input.

> **Where it ends up.** When you take payment, OrbixPOS builds one note line from the header, in the form `Patient: <name> | Prescriber: <name> | Rx: <number>`. Only the parts you filled in appear. A walk-in patient with no prescription leaves no note.

#### Set the patient

1. Tap the **Patient** tile (it shows **Walk-in** until you choose someone).
2. The **Customer** picker opens. Start typing a name, code or phone number in the **Search name / code / phone…** box.
3. Tap the patient in the results list. A green tick marks the one currently selected.
4. The picker closes and the **Patient** tile now shows the chosen name.

> **Walk-in is fine.** If the customer is not registered, just leave the tile on **Walk-in** — most counter sales are walk-in. A walk-in patient is *not* written into the sale notes; only a named, registered patient is. To go back to walk-in after choosing someone, open the picker again and select the walk-in entry.

#### Type the prescriber and Rx number

1. Tap the **Prescriber** field and type the prescriber's name (for example, the doctor who wrote the script).
2. Tap the **Rx #** field and type the prescription number from the paper script.

Both fields are free text. Leave either blank when it does not apply.

> **The header clears itself after each sale.** Once you have taken payment, OrbixPOS empties the **Prescriber** and **Rx #** fields and puts the cursor back in the search box, ready for the next customer. The patient resets to the shift's default for the new sale. You do not need to clear the header by hand.

### 2.2 Adding drugs to the sale

**What it is.** The single **Scan or search a drug…** box, just under the header, is how every line gets onto the worksheet.

**How it works.** The box is always focused, so a barcode scanner (which types the code into the focused field, then presses Enter for you) drops items straight in. You can also type and press **Enter**.

1. Make sure the cursor is in the **Scan or search a drug…** box (tap it if not).
2. **Scan** the pack's barcode, **or** type the product code or part of the name and press **Enter**.
3. The matching drug appears as a new line in the table below, with quantity **1**. The box clears itself and re-focuses for the next item.

OrbixPOS finds your item in this order, automatically:

| What you scan or type | What happens |
|---|---|
| An exact product code | That product is added immediately. |
| A barcode the server recognises | The product is added; for a weight- or price-embedded barcode, the quantity and amount are taken from the barcode. |
| Part of a name or code | The best match is added. |
| Something with no match | A short message appears: **No match for "…"**. Nothing is added — check the spelling and try again. |

> **Tip.** The cursor returns to the search box after every add, so you can scan a whole basket one pack after another without touching the mouse.

### 2.3 The dispensing line table

**What it is.** The list filling the centre of the screen. Each row is one drug on the sale. From left to right a row shows: the **drug name** (with its code and unit underneath), a **quantity stepper**, the **line amount**, and a **✕** to remove the line.

Until you add anything, the table shows a medicine icon and the prompt **"Scan or search to dispense"**.

> **The amber warning triangle.** If a drug is age-restricted, a small amber triangle appears next to its name. It is a reminder that you will be asked to confirm the customer's age before the sale completes. Age confirmation happens at payment time (when you press **Complete sale**) and is covered in the *Taking Payment* chapter (Chapter 5).

#### Change a quantity

Use the stepper on the line:

1. Tap **–** to lower the quantity (down to one; removing the last unit is done with the **✕**).
2. Tap **+** to raise the quantity by one.

The number between the buttons is the current quantity, and the line amount updates as you change it. Most pharmacy items count in whole units; a unit that can be split shows two decimal places.

#### Remove a line

Tap the **✕** at the right-hand end of the line. The line disappears and the totals update. To clear the whole sale (for example, to abandon it), remove each line in turn.

### 2.4 The running total and PAY

**What it is.** The panel pinned to the right of the Pharmacy register. It lists the number of **Items** and **Lines**, then the **Total** in the session currency, and ends with the green **PAY** button.

> **The total is a preview.** Right under the figure you will see **"preview — ERP is authoritative."** This is the running on-screen estimate to help you and the customer. The amount the customer actually pays — with the correct VAT — is worked out by the ERP server when you take payment, and that is what prints on the receipt.

To take payment:

1. Check the lines and the **Total** with the customer.
2. Tap **PAY** (it shows the preview amount).
3. The payment screen opens. Choose how the customer is paying and complete the sale — see the *Taking Payment* chapter (Chapter 5).

> **Add an item first.** **PAY** is disabled while the basket is empty. If you somehow reach it with nothing on the sale, OrbixPOS reminds you with **"Add an item first."**

When the sale is complete, the basket clears, the header empties, and the cursor returns to the search box for the next patient.

---

## 3. The Restaurant register

The Restaurant register is for table service. The screen is in two halves: on the **left**, a **Pick a table** button, a **menu search** box, and a grid of **menu item tiles**; on the **right**, the **order ticket** — the running list of what the table has ordered — with a **Send to kitchen** button and the green **PAY** button at the bottom.

> **How it differs from the supermarket and pharmacy tills.** Here you build the order by **tapping tiles**, not by scanning. You also choose a **table** so the order is tagged to where the customer is sitting. As in the other modes, the total is only a preview until you take payment.

### 3.1 Pick a table

**What it is.** The **Pick a table** button at the top-left. Once you choose, it shows the table number (for example **Table 5**), and the order ticket on the right is titled with the same table.

**Why it exists.** In a restaurant, an order belongs to a place, not a person. Tagging the order with its table tells you which ticket goes where, and keeps the kitchen and the bill straight.

**When you do it.** Pick the table at the start of the order, before you take payment. You can add menu items first and pick the table afterwards if you prefer — but choosing it first makes the ticket clear from the start.

**How it works.** Tapping **Pick a table** opens the **Choose a table** floor dialog: a grid of numbered tables (each labelled *seats 4*). Tap a table to select it. The table number is then written into the sale notes, so it travels with the finalised invoice.

To pick a table:

1. Tap **Pick a table** (top-left).
2. In the **Choose a table** dialog, tap the table number the customer is at.
3. The dialog closes. The button now reads **Table N**, and the order ticket on the right is re-titled **Table N**.

> **To change tables**, tap the button again (it now shows the current table) and pick a different one from the floor. The note updates to the new table.

### 3.2 Browse and search the menu

**What it is.** The grid of tiles on the left, one tile per menu item, each showing a dish icon, the item name and its code. Above the grid is a **Search the menu…** box.

**How it works.** The grid loads with the menu when you open the register. To narrow it down, start typing in the **Search the menu…** box — the grid filters as you type to match the name or code. Clear the box to see the full menu again.

To find an item:

1. Scroll the grid, **or** type part of the dish name or code into **Search the menu…**.
2. The tiles update to match. If nothing matches, the grid shows **"No menu items."**

### 3.3 Build the order ticket

**What it is.** The panel on the right, titled **Order ticket** (or **Table N** once a table is picked). It lists each item the table has ordered, with a quantity stepper, the line amount, and a **✕** to remove it. The header also shows a running **N items** count. Until you add anything it reads **"Tap menu items to build the order."**

#### Add an item

Tap its tile in the menu grid. The item appears on the order ticket with quantity **1**. Tap the same tile again to think of it as a second helping — or use the stepper (below) to raise the count.

#### Change a quantity

On the ticket line, use the stepper:

1. Tap **–** to lower the quantity by one.
2. Tap **+** to raise it by one.

The number between the buttons is the quantity, and the line amount and the **Total** update as you change it.

#### Remove an item

Tap the **✕** at the end of the ticket line. The item leaves the ticket and the totals update. To abandon the whole order, remove every line — this also frees the mode switcher if you need to change register.

### 3.4 Send to kitchen

**What it is.** The **Send to kitchen** button, just above the totals on the order ticket. It appears once the ticket has at least one item.

**Why it exists.** It is the waiter's "fire the order" action — the moment you tell the kitchen to start cooking, separately from settling the bill at the end of the meal.

**When you use it.** Tap it after you have built the order and the customer has decided, but typically *before* you take payment (a meal is usually paid for at the end).

**How it works.** Tap **Send to kitchen**. A green confirmation **"Sent to kitchen."** appears, and the button changes to **Sent to kitchen ✓** so you can see the order has been fired.

> **Important — what Send to kitchen does and does not do.** In this build, **Send to kitchen is a screen confirmation only.** It marks the ticket as sent on this device and shows the tick — it does **not** yet print to a kitchen printer, send to a kitchen display, or record anything on the ERP server. It does **not** take payment and does **not** finalise the sale. Treat it as a visual prompt for now; relay the order to the kitchen by your usual means. Real kitchen routing is planned for a later release.

> **Adding more after sending.** If you add another item after sending, the **Sent to kitchen ✓** tick clears back to **Send to kitchen**, so you can fire the new items. Tap it again to confirm the updated order.

### 3.5 The total and PAY

**What it is.** The footer of the order ticket: the **Total** in the session currency, and the green **PAY** button (which also shows the preview amount).

> **The total is a preview.** As in the other modes, this figure is an on-screen estimate. The ERP server calculates the real amount and VAT when you take payment, and that is what the receipt shows.

To settle the bill:

1. Confirm the order and the **Total** with the customer.
2. Tap **PAY**.
3. The payment screen opens. Choose the tender(s) and complete the sale — see the *Taking Payment* chapter (Chapter 5).

> **Add an item first.** **PAY** is disabled on an empty ticket. If you try anyway, OrbixPOS shows **"Add an item first."**

When the sale completes, the ticket clears and the table selection resets, ready for the next order.

---

## 4. Quick reference

| Task | Pharmacy | Restaurant |
|---|---|---|
| Add an item | Scan or type in **Scan or search a drug…**, press **Enter** | Tap a tile in the menu grid |
| Find an item | Type in the search box | Type in **Search the menu…**; the grid filters |
| Set who/where the sale is for | Tap the **Patient** tile (customer picker); type **Prescriber** and **Rx #** | Tap **Pick a table**, choose from the floor |
| Change a quantity | **–** / **+** stepper on the line | **–** / **+** stepper on the ticket line |
| Remove a line | **✕** at the end of the line | **✕** at the end of the ticket line |
| Fire the order to the kitchen | — (not applicable) | **Send to kitchen** (screen confirmation only) |
| Take payment | **PAY** (right-hand panel) | **PAY** (bottom of the ticket) |
| Switch register | Top-bar switcher — only when the sale is empty | Top-bar switcher — only when the ticket is empty |

> **One more reminder.** The patient/prescriber/Rx details and the table number are saved as **sale notes** for the record. They do not change the price. The ERP server is always the authority on price, VAT and totals — the receipt it returns is the one that counts.

---

*Next: see the *Taking Payment* chapter (Chapter 5) for tenders, split payment, change, age confirmation and the receipt; and the *Starting and Ending a Shift* chapter (Chapter 2) for opening, the session menu, closing and reconciling the drawer.*

---

# Taking Payment

This chapter covers the moment of truth at the till: turning a basket of items into money in the drawer (or on the card terminal) and a printed receipt in the customer's hand. It explains the **Payment** screen, the four ways a customer can pay, how to take a simple cash sale, how to split one sale across several payments, and — importantly — what to do when you are not sure whether a payment went through.

Everything here works the same way in all three OrbixPOS modes — Supermarket, Pharmacy and Restaurant. The register on the left differs, but the **Payment** screen is identical, because all three share one payment, receipt and session flow.

> Before you can take payment you must be signed in and have an **open** shift (an open session on a till). If you have not opened a shift yet, see the *Starting and Ending a Shift* chapter (Chapter 2). The **PAY** button is greyed out while the basket is empty.

---

## Opening the Payment screen

**What it is.** The **Payment** screen is a single panel that appears on top of your register when you are ready to collect money. On the left it shows the amount the customer owes and a running list of what they have paid so far; on the right it shows a number pad and the buttons that finish the sale.

**Why it exists.** Ringing items and taking money are two separate steps on purpose. You build the basket first, check it with the customer, and only then move money. Keeping payment on its own screen means you can take your time over the amount and the change without accidentally adding more items.

**When it happens.** Once every item is in the basket and the customer is ready to pay.

**How it works.** When you press the green **PAY** button on the register, OrbixPOS opens the **Payment** screen and locks in the current basket total as the amount due. Nothing is sent to the server yet — you are still preparing the payment. The sale is only created when you press **Complete sale**.

To open it:

1. Finish adding items to the basket on the register.
2. Read the total back to the customer.
3. Press the green **PAY** button (bottom-right of the register). The **Payment** screen opens.

> You cannot open **Payment** with an empty basket — the **PAY** button is dimmed and does nothing until at least one line is on the ticket.

---

## What you see on the Payment screen

The **Payment** screen has two halves.

### The left half — what is owed and what is paid

| Area | What it shows |
| --- | --- |
| **AMOUNT DUE** | A large highlighted figure at the top, labelled **AMOUNT DUE** with the currency in brackets (for example **AMOUNT DUE (TZS)**). This is the basket total the customer must cover. |
| Tender-type buttons | A row of four buttons — **Cash**, **Mobile**, **Cheque**, **Card** — used to choose how the *next* payment is being made. The selected one is highlighted. |
| Tender list | The space below the buttons lists each payment you have added so far. Until you add one it reads *"Quick-cash or add a tender →"*. |
| **Total / Paid / Change** | A short summary at the bottom: **Total** (the amount due), **Paid** (what you have entered so far), and **Change** (shown only when the customer has handed over more than the total). |

### The right half — the number pad

| Control | What it does |
| --- | --- |
| Amount display | A dark box at the top showing the figure you are currently typing. It reads **0** until you key something in. |
| Quick-cash buttons | A grid of suggested amounts — for example **Exact**, and rounded notes such as **5,000** or **10,000** — so you can fill in a common cash amount with one tap instead of typing it. |
| Number pad | Keys **0**–**9** and a decimal point for typing an amount. **C** clears the typed amount; the backspace key (⌫) deletes the last digit. |
| **Add tender** | Records the amount you have typed as one payment of the selected type, and adds it to the tender list. Use this for split payments (see below). |
| **Complete sale** | The big green button that finishes the sale, sends it to the server, and shows the receipt. |

> The amount you type with the number pad, the quick-cash buttons, and the **Change** figure are all a **preview** to help you and the customer. The real price, tax and totals are calculated by the ERP server when you press **Complete sale**, and the printed receipt is built from what the server sends back. If a server figure ever differs from the on-screen preview, the server figure is the correct one.

---

## The four tender types

**What a tender is.** A *tender* is one way of paying — the form the money takes. OrbixPOS accepts four:

| Tender | Button label | What it means |
| --- | --- | --- |
| Cash | **Cash** | Notes and coins handed across the counter, paid into the drawer. |
| Card | **Card** | A debit or credit card, processed on your separate card terminal. |
| Mobile money | **Mobile** | A mobile-money transfer (for example a phone-wallet payment). |
| Cheque | **Cheque** | A bank cheque. |

**Why there are four.** Customers do not all pay the same way, and one customer may use more than one method for a single basket. Letting you pick the method per payment means the receipt and the books record exactly how the money arrived.

**How it works.** The tender-type buttons sit in a row on the left of the **Payment** screen. The one you tap is highlighted; whatever you do next (type an amount, press a quick-cash button, or press **Complete sale**) applies to that type. **Cash** is selected by default when the screen opens.

> **Card data is never typed into OrbixPOS.** When the customer pays by **Card**, you run the card on your external card terminal (the physical card machine beside the till). OrbixPOS only records *that* a card payment of a given amount was taken — it never asks for, stores, or shows a card number, PIN, or CVV. Treat the card machine as the place where card details live; the till just notes the amount.

---

## Task: a simple cash sale

This is the everyday case — the customer pays the whole basket in cash.

1. With the basket ready, press **PAY** on the register. The **Payment** screen opens with **Cash** already selected and **AMOUNT DUE** showing the total.
2. Take the cash from the customer.
3. Enter how much cash they gave you, in one of these ways:
   - Press a **quick-cash** button that matches (for example **Exact** if they paid the exact total, or **10,000** if they handed over a 10,000 note); **or**
   - Type the amount on the number pad.
4. Check the **Change** figure at the bottom-left. If the customer gave you more than the total, OrbixPOS shows the change you owe them. If they paid the exact amount, no change is shown.
5. Press **Complete sale**.
6. Hand over the change shown, then give the customer their receipt (see the *Receipts and Refunds* chapter, Chapter 6).

> For an exact-cash sale you do not have to type anything at all — you can simply press **Complete sale** and the till settles the basket for its exact total. Typing the cash given is only needed so the till can show you the **Change**.

**What happens behind the scenes.** When you press **Complete sale**, OrbixPOS sends the basket and the cash amount to the ERP server. The server prices every line, adds tax, finalises the sale, and returns a finished invoice. OrbixPOS clears the basket, saves the receipt on this device, and shows it to you.

---

## Task: split or multi-tender payment

**What it is.** A *split* (or *multi-tender*) payment is one sale paid with two or more payments — for example part on **Card** and the rest in **Cash**, or two cards, or a mobile-money transfer plus some cash.

**Why it exists.** Customers do not always have enough of one tender. Splitting lets a single basket be settled however the customer can actually pay, while still producing one sale and one receipt.

**When it happens.** Whenever the customer wants to use more than one method, or you simply choose to record cash and non-cash portions separately.

**How it works.** You add each payment one at a time with **Add tender**. The **Paid** figure grows as you add tenders; you keep adding until **Paid** reaches (or passes) the **Total**. Then you press **Complete sale**.

To take a split payment:

1. Press **PAY** to open the **Payment** screen.
2. Choose the tender type for the first payment (for example tap **Card**).
3. Type that payment's amount on the number pad (for example the card portion).
4. Press **Add tender**. The payment appears in the tender list on the left, and **Paid** increases by that amount.
5. Repeat for each further payment: tap its tender type (for example **Cash**), type its amount, and press **Add tender**.
6. Watch the **Paid** total. When it reaches the **Total**, the sale is fully covered. (If the last tender is cash and the customer over-pays, the **Change** figure tells you what to give back.)
7. Press **Complete sale**.

> **Removing a payment you added by mistake.** Each row in the tender list has a small **×** on the right. Tap it to remove that payment from the list before you complete the sale. The **Paid** total updates immediately.

> **Keep non-cash payments exact.** Make any **Card**, **Mobile** or **Cheque** payment the precise amount for that portion, and let **Cash** be the one that absorbs any over-payment (the change). The server gives change only on cash; over-paying on a card or mobile-money tender is rejected.

### Split-payment troubleshooting

| What you see | What to do |
| --- | --- |
| The message *"Tendered … is less than the total …"* when you press **Complete sale** | The payments you added do not yet cover the **Total**. Add another tender for the shortfall, then press **Complete sale** again. |
| You added the wrong amount or wrong type | Tap the **×** on that row in the tender list to remove it, then add it again correctly. |
| The customer changes their mind about how to pay | Remove the tenders with **×** and start the payment again, or close the **Payment** screen (the **×** at the top) to return to the basket. |

---

## The change preview

**What it is.** The **Change** figure on the **Payment** screen is how much money you owe the customer back when they have handed over more than the basket total.

**Why it is a preview.** The figure is calculated on the till as a convenience so you can count out the change quickly. The amount of cash the customer gave is not part of the sale record — it is used only to work out the change to show. The receipt prints the change as well, taken from the finalised sale.

**How it appears.** **Change** is shown at the bottom-left, under **Total** and **Paid**, only when there is change to give:
- In a plain cash sale, it is the cash you typed minus the total.
- In a split sale, it is the total paid minus the total owed.

> If **Change** does not appear, there is no change to give — the customer paid the exact amount (or has not yet covered the total).

---

## Completing the sale

**What it is.** **Complete sale** is the button that turns your prepared payment into a real, recorded sale on the ERP server and produces the receipt.

**Why it is the only step that commits.** Up to this point everything on the **Payment** screen is local preparation. **Complete sale** is the single action that sends the sale to the server, takes the money in the books, and finalises the invoice. Nothing is charged before you press it.

**How it works.** When you press **Complete sale**:

1. OrbixPOS checks that the payments cover the total. If they do not, it warns you (*"Tendered … is less than the total …"*) and stops — fix the payments and try again.
2. If the basket contains an **age-restricted** item, you are asked to confirm the customer's age first (see below).
3. The sale is sent to the server. The button is disabled briefly while it waits.
4. On success, the basket clears, the receipt is saved on this device, and the **Sale complete** receipt appears.

To complete a sale, simply press **Complete sale** once the payments cover the **Total**.

> **Press it once and wait.** While the sale is being sent, the button is disabled so a second tap cannot fire. Let it finish. If the connection is slow, give it a moment rather than tapping repeatedly.

### Age-restricted items

**What it is.** Some products (for example alcohol or certain medicines) have a minimum legal age. OrbixPOS flags these and asks you to confirm the customer is old enough before the sale can complete.

**How it works.** If the basket contains a restricted item, pressing **Complete sale** opens an **Age-restricted items** prompt naming the restriction (for example *18+* or *21+*).

1. Verify the customer's age — by checking ID where required.
2. If they meet the minimum age, press **Age verified** and the sale continues.
3. If they do not, press **Cancel**. The sale is stopped (you will see *"Sale stopped: age not verified."*); remove the restricted item before completing.

> Only complete an age-restricted sale once you have genuinely checked. Pressing **Age verified** records that the check was done.

---

## "Did that go through?" — the safe-retry guarantee

**What it is.** Sometimes you press **Complete sale** and the till cannot tell you whether the sale succeeded — the network drops, or the server takes too long to answer. OrbixPOS handles this so that you can safely try again **without ever charging the customer twice**.

**Why it exists.** A slow or broken network at the worst possible moment — just as money changes hands — used to be a real risk: retry and you might double-charge; do not retry and you might lose the sale. OrbixPOS removes that dilemma.

**How it works.** Every sale carries a hidden, durable identifier that stays the same across retries. The server remembers it. If the original attempt actually reached the server, retrying with the same identifier simply returns that *same* original sale — it does not create a second one. If the original never reached the server, the retry creates the sale normally. Either way you end up with exactly one sale.

When the outcome is unknown, the **Payment** screen tells you plainly. A yellow banner appears on the left:

> *"The outcome was unknown (network/timeout). Press Complete again — the same idempotency key returns the original sale if it went through."*

At the same time, the green button changes its label from **Complete sale** to **RETRY (same key)**.

What to do:

1. Read the yellow banner. It means: the till is not sure whether the sale was recorded.
2. Press the green **RETRY (same key)** button.
3. One of two things happens:
   - If the sale had gone through, the original receipt appears — no second charge, no duplicate sale.
   - If it had not, the sale is created now and the receipt appears.
4. You can press **RETRY (same key)** as many times as it takes; it is always safe.

| What you see | What it means | What to do |
| --- | --- | --- |
| Yellow *"outcome was unknown"* banner and a **RETRY (same key)** button | The till could not confirm the sale (network or timeout) | Press **RETRY (same key)**. It returns the original sale if it went through, or completes it if it did not. Never double-charges. |
| A plain error message (red toast) and the button still reads **Complete sale** | The sale was rejected for a clear reason (for example the session is no longer open, or a price is missing) | Read the message and fix the cause, then try again. This is not a "retry the same thing" situation. |
| The receipt appears (**Sale complete**) | The sale succeeded | Hand over change and the receipt; you are done. |

> Do **not** open a fresh sale or re-ring the basket after an unknown outcome. Always use **RETRY (same key)** on the same **Payment** screen — that is what protects against a double charge. Only start over if you deliberately closed the **Payment** screen.

---

## After payment: the receipt

When the sale completes, OrbixPOS shows the **Sale complete** receipt straight away. From there you can **Print** it, produce a **Gift receipt** (which hides prices), or — with the right permission and while the session is open — **Refund / reverse** the whole sale. The receipt is also saved on this device so you can reprint it later without creating a new sale.

The full set of receipt actions — printing, gift receipts, reprints and refunds — is covered in the next chapter, *Receipts and Refunds* (Chapter 6).

> **A note on the printer.** In the current build, pressing **Print** shows a confirmation (*"Sent to printer (peripheral stub)."*) rather than driving a physical printer; real printer, cash-drawer and scale drivers are planned for a later release. The receipt is still saved and can be viewed and reprinted on screen.

---

## Quick reference

| You want to… | Do this |
| --- | --- |
| Open the payment screen | Press the green **PAY** on the register (basket must not be empty). |
| Take exact cash | Press **Complete sale** (no need to type anything). |
| Take cash and give change | Type the cash given (or tap a quick-cash button), check **Change**, press **Complete sale**. |
| Choose how the customer pays | Tap **Cash**, **Mobile**, **Cheque**, or **Card** before entering the amount. |
| Split across methods | Pick a type, type the amount, press **Add tender**; repeat until **Paid** covers **Total**; press **Complete sale**. |
| Remove a payment you added | Tap the **×** on its row in the tender list. |
| Sell an age-restricted item | Press **Complete sale**, then **Age verified** after checking the customer's age. |
| Recover from an unknown outcome | Press **RETRY (same key)** under the yellow banner — it never double-charges. |
| Take a card payment | Run the card on the external card terminal; OrbixPOS records only the amount, never card details. |
| Abandon the payment | Press the **×** at the top of the **Payment** screen to return to the basket. |

---

# Receipts and Refunds

This chapter explains what happens after you take payment: the receipt that OrbixPOS shows you, how to print it, how to give a customer a price-free **gift receipt**, how to find and reprint an earlier receipt, and how to refund a whole sale when something has gone wrong. It also explains — clearly — what OrbixPOS does **not** let you do, and what to use instead.

If you have not yet read the chapter on ringing a sale and taking payment, read that first. This chapter picks up the moment the sale is finished and the receipt appears.

---

## 1. The receipt screen

**What it is.** The receipt screen is the small printed-style slip that pops up the instant a sale completes. Its header reads **Sale complete** with a green tick. Below that is a black-on-white slip — the company name, the branch, the invoice number, the lines you sold, the totals, and how the customer paid — laid out exactly as it would print on paper.

**Why it exists.** The receipt is the customer's proof of purchase and your proof that the sale was recorded. It is built from the **finalised invoice** that the ERP server sent back — not from the figures you saw on the till while ringing the basket. This matters: the prices, VAT and totals on the screen while you were scanning are a helpful preview, but the server is the authority on money. Once the sale finalises, the server returns the official invoice, and the receipt screen shows *that*. We call the finalised invoice the **receipt of record** — it is the single, true version of what was sold.

**When it happens.** The receipt screen appears automatically after a successful payment. You do not have to ask for it. It also appears whenever you reprint an earlier sale (see Section 4).

**How it works.** OrbixPOS takes the finalised invoice from the server, formats it as a slip, and shows it to you in a dialog with action buttons along the bottom. Nothing on this screen changes the money — printing, showing a gift receipt, or reprinting are all read-only. The only button that changes anything is **Refund / reverse** (Section 5), and that one is carefully controlled.

### 1.1 Reading the receipt

The slip is laid out top to bottom like a paper till roll:

| Line on the slip | What it means |
|---|---|
| Company name (large, centred) | The trading company this sale belongs to. |
| Branch name (centred, under it) | The shop or outlet you are working in. |
| **Invoice** | The invoice number the server assigned, e.g. `INV-2026-000042`. This is the unique reference for the sale — quote it on any query. |
| **Date** | The date and time the sale was finalised, in your local time. |
| **Cashier** | Your name (the signed-in user). |
| **Customer** | The customer's name if one was attached, otherwise a customer reference. A walk-in sale may show a default customer. |
| The line items | One block per product: the product name on its own line, then the quantity × unit price on the left and the line total on the right. If a discount was applied to a line, a green **less disc** line shows the amount taken off. |
| **Net** | The total before VAT. |
| **VAT** | The tax added. |
| **TOTAL** | The grand total the customer owed, shown with the currency, in bold. |
| Tender lines | One line per payment method used — **Cash**, **Card**, **Mobile** (mobile money), **Cheque** — each with the amount taken on it. A split sale shows several. |
| **Change** | Shown only when the customer paid cash and was owed change. |
| Thank you! | The footer. |

> **Note.** Quantities print without decimals for whole numbers (for example a quantity of `3`) and with decimals for weighed or fractional goods (for example `0.750` kg). This comes straight from the invoice, so it always matches what the customer was charged.

> **Tip.** If a line shows `(line detail not loaded)` instead of the products, the totals are still correct and the receipt is still valid — only the itemised breakdown could not be fetched on this device. Reprint it from **Today's sales** (Section 4.1) to pull the full detail from the server.

### 1.2 Closing the receipt

When you are done with the receipt, press **Done** (or the **✕** close button in the top-right corner). This clears the screen and returns you to the register, ready for the next customer. Pressing **Done** does **not** cancel or change the sale — the sale is already recorded on the server. It simply puts the slip away.

---

## 2. Printing a receipt

**What it is.** **Print** sends the receipt to the receipt printer attached to your till.

**Why it exists.** Most customers want a paper receipt, and many places are legally required to give one. Print is the everyday button you reach for at the end of almost every sale.

**When it happens.** Press **Print** whenever the customer wants paper — usually straight away on the receipt screen, but you can also reprint older receipts later (Section 4).

**How it works.** Press **Print** on the receipt screen.

1. The receipt screen is showing (it appears automatically after a sale, or after you reprint one).
2. Press **Print** (the button with the printer icon, on the left of the action row).
3. The slip is sent to the printer.

> **Important — about printing in this build.** The receipt printer is not yet wired to a real driver. In the current release, pressing **Print** shows a confirmation message — *"Sent to printer (peripheral stub)."* — to confirm the action worked, but no paper comes out yet. Real printer support is coming. Until then, if a customer needs paper today, follow your store's interim procedure (for example, your administrator may have a separate printing arrangement). The receipt data itself is complete and correct; only the physical print step is pending.

---

## 3. Gift receipts (hiding prices)

**What it is.** A **gift receipt** is the same receipt with all the money removed — no net, no VAT, no total, no tenders, no change. It lists what was bought but not what it cost.

**Why it exists.** When someone buys a present, they do not want the recipient to see the price. A gift receipt lets the recipient return or exchange the item (your store policy permitting) without ever learning what was paid for it.

**When it happens.** Give a gift receipt when a customer asks for one — usually for a present. You can switch any receipt to gift view on the spot.

**How it works.** Press **Gift receipt** on the receipt screen.

1. With the receipt showing, press **Gift receipt** (the button with the gift-card icon).
2. The slip redraws with the prices hidden. In their place you see *"\* gift receipt — prices hidden \*"*.
3. Press **Print** to print this price-free version for the customer to put in the gift.
4. To bring the prices back, press the same button — it now reads **Show prices**. The slip returns to the full priced view.

> **Note.** Switching to gift view changes only what is displayed and printed. It does **not** change the sale, the totals, or anything on the server. The full priced invoice is still the record of the sale. You can flip between **Gift receipt** and **Show prices** as often as you like.

---

## 4. Reprinting an earlier receipt

Sometimes a customer comes back later — they lost the slip, or the printer jammed, or they need a copy for their records. OrbixPOS gives you two ways to find an earlier receipt and print it again. Both open through the **Session** menu (press the **☰** menu button in the top bar).

**The golden rule:** reprinting **never creates a new sale**. It just shows the same finalised invoice again. The customer is not charged a second time, stock is not touched, and no new invoice number is created. You can reprint a receipt as many times as you need.

There are two sources, for two situations:

| Source | Where it looks | Use it when |
|---|---|---|
| **Today's sales** | The ERP server | You want any of today's sales from **this branch**, even one rung on a different till or by a different cashier. Needs a network connection. |
| **Recent receipts** | This device only | You want a sale that was rung **on this till**, and you may be offline. Works without the network. |

### 4.1 Today's sales (look up on the server)

**What it is.** A list of every sale finalised today for your branch, fetched live from the server.

**Why it exists.** It lets you find and reprint a receipt even if it was not rung on your own till — for example, a customer who paid at a different register and lost their slip.

**How it works.**

1. Press the **☰** menu in the top bar to open the **Session** drawer.
2. Press **Today's sales** ("Look up & reprint a receipt").
3. OrbixPOS fetches the day's invoices from the server. Each row shows the invoice number, the time it was finalised, and the total.
4. Tap the sale you want.
5. OrbixPOS loads the full finalised receipt from the server and shows the receipt screen.
6. Press **Print** (or **Gift receipt** then **Print**) as normal.

> **Note.** **Today's sales** needs the network — it reads from the server. If the connection is down, use **Recent receipts** instead (Section 4.2), or try again when you are back online. If the list shows *"No sales yet."*, no sales have been finalised today for this branch.

### 4.2 Recent receipts (this device, works offline)

**What it is.** A list of the receipts that were rung **on this device**, kept locally on the till itself.

**Why it exists.** The network is not always up. Because OrbixPOS keeps a copy of each receipt it produces on the device, you can still reprint a recent one even with no connection — perfect for a customer who returns minutes after a sale during an outage.

**How it works.**

1. Press the **☰** menu in the top bar to open the **Session** drawer.
2. Press **Recent receipts** ("Reprint from this device (offline)").
3. A list headed **Recent receipts (this device)** appears — each row shows the invoice number, the date and time, and the total.
4. Tap the receipt you want.
5. The receipt screen opens.
6. Press **Print** (or **Gift receipt** then **Print**) as normal.

> **Note.** **Recent receipts** only contains sales rung **on this particular till**. A sale rung on another register will not be here — use **Today's sales** for those (when you have a connection). If the list shows *"No receipts on this device yet."*, this till has not completed any sales since the device's local history was last cleared.

> **Tip.** Reprints from either source open the *same* receipt screen with the *same* buttons, so you can print, switch to a gift receipt, or — if it is allowed — refund the sale, exactly as you could when it was first rung.

---

## 5. Refunding (reversing) a whole sale

**What it is.** A **refund / reverse** cancels an entire sale that has already been recorded. It undoes the whole thing: it gives the money back, puts the stock back, and unwinds the tax and revenue on the server.

**Why it exists.** Mistakes happen — the wrong item was scanned, a customer changes their mind right after paying, a basket was rung twice. Reversing the sale is the clean, fully-accounted way to put everything back exactly as it was, with an audit trail of who did it and why.

**When it happens.** You reverse a sale from its receipt screen, while the till session that rang it is **still open** (that is, before the shift has been closed and reconciled). Because reversing is sensitive, it is **supervisor-gated** — only a user whose role includes the refund permission can do it. If you do not have that permission, the **Refund / reverse** button does not appear at all; ask a supervisor.

**How it works.** Press **Refund / reverse** on the receipt screen.

1. Open the sale you want to reverse. This can be the receipt that just appeared after a sale, or one you reprinted from **Today's sales** or **Recent receipts** (Section 4).
2. Press **Refund / reverse** (the red button with the undo arrow). It appears only when reversing is allowed (see the conditions below).
3. A confirmation box headed **Reverse this sale?** appears. It explains: *"This voids the whole sale and reverses revenue, VAT, cash and stock. Allowed while the session is open."*
4. Type a short **Reason** — say what happened, e.g. "wrong size, customer swap" or "rang twice in error". A reason is good practice and goes into the audit record. (If you leave it blank, a default note is used.)
5. Press **Reverse** to confirm, or **Cancel** to back out.
6. On success you see *"Sale reversed."*, and the slip is stamped **\*\*\* REVERSED \*\*\*** in red. The money, stock, VAT and revenue are all unwound on the server, and the cash for this sale automatically drops out of your drawer's expected total — so you do **not** need to record a separate cash payout for it.

### 5.1 When the Refund / reverse button is available

The **Refund / reverse** button only shows when **all** of these are true:

| Condition | Why |
|---|---|
| You have the refund permission (`POS.SALE.VOID`) | Reversing is supervisor-gated. Without it the button is hidden. |
| The session is still **open** | The reversal puts the cash back into the *open* drawer. Once the shift is closed/reconciled, the cash is settled and the till can no longer absorb it. |
| The sale has **not already** been reversed | A sale can only be reversed once. After it is stamped **REVERSED**, the button is gone. |
| The invoice is not already void | You cannot reverse something that is already cancelled. |

If the button is missing, check these in order. The most common reasons are: you are a cashier without supervisor rights (ask a supervisor), the shift has already been closed (the sale must be handled by the back office now — see the next note), or the sale was already reversed.

> **Note — a sale from a session that has already been closed.** If the original shift has already been closed and reconciled, OrbixPOS cannot reverse the sale at the till, because the drawer it belonged to is settled. In that situation the sale has to be cancelled in the back-office ERP instead, where the cash difference is treated as a reconciliation matter. Ask your supervisor or store manager to handle it from the office system.

> **Important — the refused-message case.** If you press **Reverse** and the server refuses (for example because the session has just closed, or the sale was not a till sale), OrbixPOS shows the reason in a short message and the sale is left untouched. Read the message; it tells you what to do next.

---

## 6. What OrbixPOS does *not* do — partial and single-line refunds

This is an important limit to understand, so you are never stuck mid-transaction.

**OrbixPOS cannot refund part of a sale.** There is **no** way to refund a single line, refund one item out of five, or refund part of the quantity. The **Refund / reverse** button is all-or-nothing: it reverses the **whole** sale or nothing.

So what do you do when a customer wants to return just one item out of a larger basket? You have two correct options, depending on your store's policy:

| Situation | What to do |
|---|---|
| Customer returns **one item** from a multi-item sale, and the sale's session is still open | **Reverse the whole sale** (Section 5), then **ring a fresh sale** for the items the customer is keeping. The net effect is that only the returned item is refunded. |
| You just need to **hand cash back** that is not tied to a specific reversible sale (a goodwill cash-back, or a return for a sale from an already-closed shift) | Record a **cash payout** of type **Refund** instead (Section 7). |

> **Tip.** The "reverse the whole sale, then re-ring the rest" approach keeps the books perfectly accurate, because each step is a complete, properly-accounted transaction. It feels like extra steps, but it is the right way, and the idempotency safety on sales means re-ringing is safe.

---

## 7. The cash-drawer refund payout (the alternative)

**What it is.** A **cash payout** records cash physically leaving the drawer. One of its types is **Refund** — money handed back to a customer that is **not** linked to reversing a particular sale.

**Why it exists.** Sometimes you must give cash back but there is no open, reversible sale to undo — for example, a customer returns goods bought during a shift that has already closed, or your manager authorises a goodwill cash-back. A refund payout keeps your drawer honest: it tells the system that cash left, so when you count the till at close, the figures still add up.

**When it happens.** Use a refund payout only when the proper whole-sale reversal (Section 5) is not available or not appropriate. If the sale is reversible (its session is still open and you have permission), **always prefer Refund / reverse** — it handles cash *and* stock *and* tax, which a payout does not.

**How it works.** Record it from the **Session** menu.

1. Press the **☰** menu in the top bar to open the **Session** drawer.
2. Press **Cash payout** ("Refund or drawer drop").
3. At the top, choose the type. Pick **Refund** for cash returned to a customer. (The other type, **Paid out**, is for a drawer-to-safe drop or petty-cash payout — not a customer refund.)
4. Enter the **Amount** (in your currency).
5. Type a **Reason** — say why the cash is leaving, e.g. "Cash refund, returned goods, ref INV-2026-000042". Quote the original receipt number if you have it.
6. Press **Record**.
7. You see *"Payout recorded."*. The amount is now subtracted from the cash your drawer is expected to hold at close.

> **Warning — what a refund payout does *not* do.** A refund payout is **cash bookkeeping only**. It does **not** put stock back, it does **not** reverse VAT or revenue, and it is **not** linked to any invoice. It only keeps your drawer's expected cash correct. If the goods are coming back into the shop and the sale could be reversed, use **Refund / reverse** (Section 5) instead — a payout is the fallback for when that is not possible.

---

## 8. Quick reference and troubleshooting

| What you see | What to do |
|---|---|
| The receipt screen appeared after payment | The sale is recorded. Press **Print** for paper, **Gift receipt** to hide prices, then **Done** to move on. |
| A customer wants a present receipt with no prices | Press **Gift receipt**, then **Print**. Press **Show prices** to switch back. |
| A customer lost a receipt from earlier today (any till) | **☰** menu › **Today's sales** › tap the sale › **Print**. Needs the network. |
| A customer lost a receipt and you are offline | **☰** menu › **Recent receipts** › tap the sale › **Print**. Works without the network (this till's sales only). |
| You reprinted a receipt — did the customer get charged again? | No. Reprinting never creates a new sale and never charges anyone. |
| You need to undo a whole sale, shift still open, and you have permission | On the receipt, press **Refund / reverse**, enter a reason, press **Reverse**. |
| **Refund / reverse** button is missing | You may lack the supervisor permission (ask a supervisor), the shift may already be closed (back office must handle it), or the sale was already reversed. |
| Customer wants to return just one item from a bigger sale | Reverse the whole sale, then re-ring the items they are keeping (Section 6). |
| You must hand cash back but there is no reversible sale | **☰** menu › **Cash payout** › type **Refund** › enter amount and reason › **Record** (Section 7). |
| **Print** showed "Sent to printer (peripheral stub)" but nothing printed | Expected in this build — the physical printer driver is not wired yet. The receipt data is correct; follow your store's interim printing procedure. |
| The line items show "(line detail not loaded)" | Totals are still correct. Reprint from **Today's sales** to pull the full breakdown from the server. |

> **Remember the three rules of this chapter:**
> 1. The receipt is built from the **finalised invoice** — the server's official record of the sale.
> 2. **Reprinting never creates a new sale** and never charges the customer.
> 3. Refunds at the till are **whole-sale only** and **supervisor-gated**, while the session is open; for anything else, reverse-and-re-ring or record a **Refund** cash payout.

---

# Troubleshooting and Good Practice

Even on a good day, a till sometimes hesitates: the network blips, a barcode finds nothing, a screen says you cannot do something. This chapter is your first-aid kit. It is written so that, when a message appears, you can find the exact wording, understand what it means, and know the next thing to press — without calling for help every time.

It also covers a few **good habits** that keep your drawer clean and your sales safe: ringing one sale at a time, checking the printed total against the screen, and — most important — never re-keying a sale when you are not sure whether it went through.

> **The golden rule of this chapter.** OrbixPOS is a *till*, not the books. The ERP server is the single source of truth for price, VAT, totals, and your cash variance. Anything you see in money before you take payment is a **preview**. The **receipt of record** is built from the finalised invoice the server sends back. When something looks wrong, the question is almost always "did the server hear me?" — and this chapter shows you how to find out safely.

---

## How OrbixPOS tells you something went wrong

**What it is.** Whenever the till asks the server to do something — sign you in, load tills, ring a sale, close a session — the server answers. If the answer is anything other than "done", OrbixPOS turns it into a short, plain-language message and shows it to you, usually as a small pop-up notice (a **toast**) at the edge of the screen, or as a red banner on the screen you are on.

**Why it exists.** You should never have to read a technical error code or guess what a number means. The till translates every failure into one friendly sentence aimed at you, the cashier, and decides what to do next based on *what kind* of failure it was — not on the words. The words are there so you know what happened; the till's behaviour is driven by the underlying status.

**When it happens.** On any failed action. A red banner appears under the **Sign in** button when login fails. A toast appears for most other failures (a failed scan, a refused sale, a payout that did not save).

**How it works.** The message is short and specific. Read it, take the matching action from the tables below, and try again. Messages never leak anything secret or technical — if you ever see raw code or a long stack of text, report it; that is a bug, not a normal message.

> Most failures are *transient* — a brief hiccup that fixes itself on the next try. The tables below tell you which ones to simply retry, which need you to change something, and the one case (an **unknown outcome**) where you must **not** start over but press the same button again.

---

## Cannot reach the server

**What it is.** OrbixPOS holds no data of its own. Every action travels over your network to the ERP server. If the till cannot reach that server — the network is down, the cable is out, the server address is wrong, or the server itself is off — nothing can be rung.

**Why it matters.** This is the most common showstopper, and it is almost always something simple: a loose network cable, Wi-Fi that dropped, or a server address that was typed wrong during setup. You can check and fix most of it yourself from the **Server setup** dialog.

**When you see it.** On the **Sign in** screen if the server is unreachable before you even log in; or as a toast like **Cannot reach the ERP. Check the connection and host.** during the day.

**How it works.** OrbixPOS reaches the server at a single address called the **ERP host** (for example, `http://erp.yourcompany:8081`). The `/api/v1` path is added automatically — you only set the host. The **Server setup** dialog lets you type that host and press **Test connection** to confirm the server is alive and reachable from this device before you rely on it.

### Check the server, step by step

1. On the **Sign in** screen, click **Server setup** (the small settings link below the **Sign in** button).
2. The **Setup & diagnostics** dialog opens. Look at the **ERP host** field. It should hold the address your administrator gave you — for example `http://erp.yourcompany:8081`. Do not add `/api/v1`; the till adds that for you.
3. Click **Test connection**.
4. Wait for the coloured result box:

| What you see | What it means | What to do |
|---|---|---|
| **Reachable — ERP is UP.** (green) | The till reached the server and the server is healthy. | Click **Save**, then sign in as normal. |
| **Reached host, status unclear.** (green-ish) | The address is right and *something* answered, but it did not report a clean "healthy". | The server may be starting up. Wait a minute and **Test connection** again; if it persists, tell your supervisor. |
| **Could not reach the ERP at this host.** (red) | Nothing answered at that address. | Check the address for typos, check your network, then **Test connection** again. See the checklist below. |

5. When the result is green, click **Save** to store the host. To leave without changing anything, click **Cancel**.

> **Tip — the host format.** It is `http://` (or `https://`) then the server name or IP, then a colon and the port (often `8081`) — for example `http://erp.yourcompany:8081`. No trailing slash is needed; the till trims one for you if you add it. Never invent a host: use exactly the one your administrator gave you.

### If "Test connection" stays red

| Check | What to do |
|---|---|
| The address has a typo. | Compare it character-by-character with the one your administrator gave you. A single wrong digit will fail. |
| Your device is off the network. | Check the network cable or Wi-Fi. Can other things on this device reach the network? |
| The till is on Wi-Fi that dropped. | Reconnect to the correct network, then **Test connection** again. |
| Everything looks right but it still fails. | The server itself may be down. Note the time and tell your supervisor or administrator — this is not something you can fix at the till. |

---

## Your session has expired / you were signed out

**What it is.** When you sign in, the server gives the till a time-limited pass. The till renews that pass quietly in the background for as long as you keep working. If it cannot be renewed — you were idle a long time, the server restarted, or your account was changed mid-shift — the pass lapses and you are returned to the **Sign in** screen.

**Why it exists.** Time-limited passes are a security measure: an unattended till cannot be used indefinitely by someone who walks up to it.

**When you see it.** A message reading **Your session has expired. Please sign in again.** and a return to the **Sign in** screen. It can also happen silently after a long idle period.

**How it works.** Being signed out does **not** lose a completed sale — every finalised sale already lives on the server. You simply prove who you are again and carry on.

1. At the **Sign in** screen, enter the username and password your administrator gave you.
2. Click **Sign in**.
3. If your shift was still open, the till brings you back to your register. Your session number is shown in the top bar; your open sale basket, if any was not yet paid, is cleared — re-ring those items.

> **Note.** A session timing out is not the same as your *cash session* (your shift) closing. Your shift on the till stays **open** on the server until you close it. Signing back in reconnects you to the same open shift.

---

## You do not have permission

**What it is.** OrbixPOS shows or hides actions based on what your account is allowed to do. A cashier can sell and open and close their own shift. Reconciling (the **Z-read**) and reversing a sale need higher permission, usually a supervisor. Creating or retiring tills is a store-manager job.

**Why it exists.** This separation protects the business: the person who counts the drawer should not necessarily be the person who posts the variance to the books, and not everyone should be able to reverse a completed sale.

**When you see it.** Two ways:

- The action is **dimmed or hidden** before you ever click it. In the **Session** menu, **Reconcile (Z-read)** shows a small **lock** icon and the subtitle **Post variance — supervisor** when you lack that permission; the **Refund / reverse** button simply does not appear on a receipt you may not reverse.
- You click something and get the toast **You do not have permission for this action.**

**How it works.** The till asks the server, and the server decides. There is nothing wrong with your till.

| What you see | What to do |
|---|---|
| **Reconcile (Z-read)** is locked. | This is supervisor-gated. Ask a supervisor to reconcile, or hand over for that step. |
| No **Refund / reverse** button on a receipt. | Reversing needs permission and an open session. Ask a supervisor to do the reversal. |
| **You do not have permission for this action.** | The server refused this specific action for your account. If you believe you should be allowed, ask your administrator to review your role — do not keep retrying. |
| **New till** does not appear on the **Open shift** screen. | Creating tills is a store-manager permission. Ask your store manager to set up the till. |

> The till never lets you do something it knows you cannot. If a button is missing, that is by design — it is not broken.

---

## Age not verified stops a sale

**What it is.** Some products — for example alcohol or tobacco — are **age-restricted**. The till flags them with a small amber pill (such as **18+**) on the line and in the search results. Before such a sale can complete, the till asks you to confirm you have checked the customer is old enough.

**Why it exists.** Selling an age-restricted item to someone under the minimum age is against the law and against store policy. The prompt is a deliberate stop so the check is never skipped by habit.

**When you see it.** When you press **Complete sale** (or **PAY** then **Complete sale**) on a basket that contains one or more age-restricted items. A dialog titled **Age-restricted items** appears, listing the kinds in the basket (for example, "This basket contains 18+ items").

**How it works.** You must look at the customer — and ask for ID if there is any doubt — *before* you answer.

1. The **Age-restricted items** dialog appears at checkout.
2. Check the customer meets the minimum age.
3. If they do, click **Age verified** to continue to payment.
4. If they do not — or you are unsure and cannot verify — click **Cancel**. The till shows **Sale stopped: age not verified.** and returns you to the basket. Remove the restricted line (press the **×** on its row), or set that line aside, then complete the rest of the sale.

> **Warning.** Pressing **Age verified** is a statement that you checked. Never press it to clear the dialog without actually verifying. If a customer cannot prove their age, you must not sell them the restricted item.

---

## "Your user has no sales-agent record"

**What it is.** Every cashier who rings sales must have an **internal sales-agent record** linked to their user account on the ERP. This links each sale to a real, accountable salesperson. If your account has no such linked agent, the server refuses the sale.

**Why it exists.** The business requires every sale to name a salesperson. The link is set up once, by an administrator, when your account is provisioned for the till. It is not something you create yourself.

**When you see it.** You ring up a basket, press **Complete sale**, and instead of a receipt you get a toast carrying the server's explanation — that the sale cannot be posted because your user is not linked to a sales agent. (For the same reason, a **super-admin / root** account can never ring sales: by rule, the root account cannot be a sales agent.)

**How it works.** This is a one-time setup gap on the server side, not a fault you can fix at the till. Re-pressing **Complete sale** will keep failing the same way until the link exists.

1. Note the message exactly as shown.
2. Stop trying to ring on this account.
3. Ask your administrator to **link an internal sales-agent record to your user account** (your store manager's chapter explains how). If you were signed in with a shared or admin account, sign out and sign back in with your own cashier account.
4. Once the link is in place, sign in and ring as normal — no further change is needed at the till.

> If you only ever see this on the root/admin login, that is expected. Use a real cashier account to sell.

---

## A scan or search finds nothing

**What it is.** In the Supermarket register, the field at the top — labelled **Scan a barcode or search by code / name…** — is how you add items. You scan a barcode, type a product code, or type part of a name. The till first tries an exact product code, then a barcode lookup (including embedded weight/price barcodes), then a name search.

**Why it matters.** A "no match" almost always means the code is not in the catalogue, the barcode was mis-read, or you typed a name the catalogue does not use.

**When you see it.** The toast **No match for "<what you scanned>".** appears, and nothing is added to the grid.

**How it works.** The till keeps showing you what it could not find so you can correct it.

| What you see | What to do |
|---|---|
| **No match for "…".** after a scan. | Scan again — the first read may have been partial. Hold the scanner steady and aim at the whole barcode. |
| **No match for "…".** after typing a code. | Re-check the code for a typo. Try typing part of the **product name** instead — a single match adds itself; several matches show a list to pick from. |
| A drop-down list of items appears. | Your search matched more than one product. Click the right one in the list to add it. |
| Still nothing for an item you can see on the shelf. | The product may not be in the catalogue, or its barcode is not registered. Tell your supervisor; the item may need adding by master-data staff. |

> **Tip.** When several products match your text, the till shows a list rather than guessing. Pick from the list instead of retyping — it is faster and avoids mistakes.

---

## The scanner is a "keyboard wedge" — keep the field focused

**What it is.** In this build, the barcode scanner behaves like a fast keyboard: when you scan, it *types* the barcode into whatever field currently has the cursor, then presses Enter. This is called a **keyboard wedge**.

**Why it matters.** Because the scanner types into the focused field, the **search field must hold the cursor** for scanning to work. If the cursor is somewhere else — say you just tapped the number pad, or a dialog is open — a scan will land in the wrong place or do nothing.

**When it matters.** All day, every scan. The Supermarket register tries hard to keep the search field focused for you: it grabs focus when the register opens, and returns focus to it after each item is added and after you finish a payment. But if you click elsewhere, you may need to click back.

**How it works.** Keep one simple habit: **the search field is home.** Before each scan, make sure the cursor is in the **Scan a barcode or search by code / name…** field (you will see the cursor blinking there).

| What you see | What to do |
|---|---|
| You scan and nothing happens. | Click once inside the search field to put the cursor there, then scan again. |
| The barcode digits appear in the number pad or another box. | The cursor was in the wrong place. Clear that box, click the search field, and re-scan. |
| Scanning works, then stops after you adjust a quantity. | Click the search field again to return the cursor home, then carry on scanning. |

> **Good habit.** After any action that takes the cursor away — editing a quantity, picking a customer, opening a menu — glance at the search field and click it before your next scan. The till tries to do this for you, but a quick check costs nothing.

---

## Slow network or a "blip" mid-sale — the unknown-outcome case

**What it is.** Sometimes a request leaves the till and the answer never comes back — the network stalls, or the connection drops at exactly the wrong moment. The sale **may** have reached the server and posted, or it **may** not have. The till genuinely does not know which.

**Why this is special.** This is the one situation where the obvious move — "it failed, let me do it again" — is dangerous, because if the first attempt *did* post, doing it again would charge the customer twice. OrbixPOS is built so this cannot happen.

**When you see it.** You press **Complete sale**, and instead of a receipt you get an amber banner inside the **Payment** window:

> **The outcome was unknown (network/timeout). Press Complete again — the same idempotency key returns the original sale if it went through.**

You may also see the message **The server did not respond in time. The request may or may not have completed.** The big green button changes its label to **RETRY (same key)**.

**How it works — the safe sale.** Each sale carries a hidden, durable identifier that the till reuses on every retry. The server remembers it. So when you press the button again:

- If the first attempt **did** post, the server recognises the identifier and simply hands back the **original** sale and receipt. No second charge.
- If the first attempt **did not** post, this attempt posts it once. Still no double charge.

Either way you end with exactly one sale.

1. When you see the amber **unknown outcome** banner, do **not** close the window and do **not** start a new sale.
2. Press the green **RETRY (same key)** button (it was **Complete sale** before).
3. Wait. The receipt appears — either the original or the newly posted one. You are done.
4. If it is still unknown, wait a few seconds for the network to settle and press **RETRY (same key)** again. The same identifier keeps it safe no matter how many times you press it.

> **Warning.** Never react to an unknown outcome by cancelling and re-ringing the whole basket as a brand-new sale. That is the *only* way to double-charge — and the **RETRY (same key)** button exists precisely so you never have to. When in doubt, retry the same sale; do not start a fresh one.

If you ever do suspect a customer was charged once but the receipt did not print, look the sale up in **Today's sales** (from the **Session** menu) before re-ringing — see Reprinting, below.

---

## "Tendered is less than the total"

**What it is.** At the **Payment** window you choose how the customer pays — **Cash**, **Card**, **Mobile** (mobile money), **Cheque**, or a split across several. If the amounts you enter add up to less than the amount due, the till will not let the sale complete.

**When you see it.** A toast such as **Tendered 8,000 is less than the total 12,500.** when you press **Complete sale** without covering the full amount.

**How it works.**

1. Look at the **Total**, **Paid**, and (for cash) **Change** rows in the payment window.
2. Add more tender: type an amount on the keypad, choose the tender type, and press **Add tender** — or use a quick-cash preset button (the **Exact** button matches the total exactly).
3. When **Paid** is at least the **Total**, press **Complete sale**. For cash, any **Change** to give back is shown for you.

> Card payments are taken on your **external card terminal**, never typed into OrbixPOS. In the till you record that a card tender of the right amount was taken; the card details stay on the terminal.

---

## Reprinting a receipt (and why it never makes a new sale)

**What it is.** You can reprint any receipt without creating a new sale. There are two sources: **Today's sales** (looked up from the server) and **Recent receipts** (saved on this device).

**Why it exists.** Customers ask for a second copy; a receipt jams in the printer; you need to confirm a sale really posted. Reprinting must never ring the sale again.

**When you use it.** Any time after a sale — including, importantly, when you are not sure a sale went through after a network blip.

**How it works.** Open the **Session** menu (the **☰** icon in the top bar), then:

- **Today's sales** — lists sales the **server** has for today. Click one to open and reprint it. Use this to confirm a doubtful sale actually posted before you consider re-ringing.
- **Recent receipts** — lists receipts saved on **this device**. This works even when the network is down (it does not call the server). Click one to reprint.

Reprinting only re-shows or re-prints an existing receipt. It can **never** post a new sale or charge anyone again.

> **Refunds.** OrbixPOS reverses a **whole** sale (the **Refund / reverse** button on a receipt, supervisor-permitted, while the session is open). It does **not** do partial or single-line refunds. To return one item from a multi-item sale, either reverse the whole sale and re-ring the rest, or record a cash refund through **Cash payout** in the **Session** menu, following your store's policy.

---

## The printer, cash drawer, and scale in this build

**What it is.** Receipt printers, the cash drawer, and weighing scales are **stubbed** in the current build. The buttons and flows are all there, but the real hardware drivers are not yet connected.

**Why it matters.** So you are not surprised: when you press **Print**, the till confirms the action but does not yet drive a physical printer. The drawer does not pop on its own, and weighed items are entered through barcodes/quantities rather than read live from a scale.

**When you see it.** Pressing **Print** on a receipt shows a confirmation toast: **Sent to printer (peripheral stub).**

**How it works (for now).**

| Peripheral | Today's behaviour | What to do |
|---|---|---|
| Receipt printer | **Print** confirms but does not produce paper yet. | Use your store's interim arrangement for paper copies. The on-screen receipt is complete and can be reprinted any time. |
| Cash drawer | Does not open automatically. | Open the drawer manually as your store directs. |
| Scale | No live weight read. | Use embedded-weight barcodes (the scanner handles these) or enter the quantity on the number pad. |

> Real printer, drawer, and scale drivers are planned for a later release. Until then, the on-screen receipt — and the ability to reprint it from **Today's sales** or **Recent receipts** — is your reliable record.

---

## Good habits that prevent problems

A few simple practices remove most of the trouble before it starts.

### One sale at a time

Finish the sale in front of you — ring, take payment, hand over the receipt — before starting the next. OrbixPOS deliberately stops you from switching register **mode** (Supermarket, Pharmacy, Restaurant) while a sale is in progress: if you try, it tells you **Finish or clear the current sale before switching mode.** Treat that as a reminder of the habit, not a nuisance.

### Verify the printed total matches the screen

The money you see while building a basket is a **preview**. The real, final figures come from the server when you complete the sale. After you complete it, glance at the receipt's **TOTAL** and confirm it matches what you expected and what the customer is paying. If they differ, do **not** improvise — the receipt (from the finalised invoice) is the truth; investigate before handing over change.

### Never re-key a sale when you are unsure — retry the *same* one

This is the single most important habit on the till. If a sale's outcome is ever in doubt — a blip, a timeout, a frozen moment — **do not start a new sale**. Press **RETRY (same key)** on the same payment, or look the sale up in **Today's sales** first. The till is built to never double-charge as long as you retry the same sale rather than ringing a fresh one.

### Keep the search field focused

The scanner types into the focused field. Before each scan, make sure the cursor sits in the **Scan a barcode or search by code / name…** field. A one-second check saves a mis-scanned item.

### Check, don't guess

If a message stops you, read it and match it to a table in this chapter. If it is a permission or setup issue (no sales-agent link, a locked **Z-read**, a host that will not connect), it needs your administrator, supervisor, or store manager — not repeated retries. Note the exact wording and the time, and hand it on.

---

## Quick reference — message to action

| Message or symptom | Likely cause | Your next move |
|---|---|---|
| **Cannot reach the ERP. Check the connection and host.** | Network down or wrong host. | **Server setup → Test connection**; check network and address. |
| **Could not reach the ERP at this host.** (in Setup) | Wrong host or server down. | Fix the host; if right, tell your supervisor. |
| **Your session has expired. Please sign in again.** | Timed-out pass. | Sign in again; your shift is still open on the server. |
| **You do not have permission for this action.** | Account not allowed. | Ask a supervisor/administrator; don't retry. |
| **Reconcile (Z-read)** is locked. | Supervisor-only step. | Have a supervisor reconcile. |
| **Age-restricted items** dialog / **Sale stopped: age not verified.** | Restricted item in basket. | Verify age → **Age verified**, or remove the line. |
| Sale refused — your user has no linked sales agent. | Account not provisioned to sell. | Ask your administrator to link an internal sales agent; use a cashier account, not root. |
| **No match for "…".** | Code/barcode/name not found. | Re-scan; try the name; pick from the list; report if missing. |
| Scan does nothing / lands in the wrong box. | Cursor not in the search field. | Click the **Scan a barcode or search…** field, then scan. |
| **The outcome was unknown (network/timeout).** / **RETRY (same key)** | Network blip mid-sale. | Press **RETRY (same key)** — never re-ring as a new sale. |
| **Tendered … is less than the total ….** | Underpaid. | Add tender until **Paid** ≥ **Total**, then **Complete sale**. |
| **Sent to printer (peripheral stub).** | Printer driver not wired yet. | Use the on-screen/reprintable receipt; follow store policy for paper. |

When in doubt, remember the two anchors of safe till work: **the server is the truth**, and **retry the same sale, never a new one.**

---

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
