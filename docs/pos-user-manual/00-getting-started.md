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

**Why they exist.** A grocery cashier wants to scan barcodes fast; a pharmacy needs patient and prescription details; a restaurant works in tables and courses. One till would feel wrong for all three, so OrbixPOS gives each its own purpose-built register while keeping everything else the same — so the skills you learn in one mode carry straight over.

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
