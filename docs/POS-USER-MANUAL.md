# OrbixPOS — User Manual

_ERPCLEAN2 — modular-monolith ERP (Spring Boot + Angular + PostgreSQL). Generated from the live codebase + the verified test-case suite._

## Contents

1. [Getting Started](#getting-started)
    - [What OrbixPOS Is](#what-orbixpos-is)
    - [Installing OrbixPOS on a Till PC](#installing-orbixpos-on-a-till-pc)
    - [Trusting Your Server's Certificate (https only)](#trusting-your-servers-certificate-https-only)
    - [First-Run Setup — Pointing the Till at Your Server and Printer](#first-run-setup--pointing-the-till-at-your-server-and-printer)
    - [Signing In](#signing-in)
    - [The Screen Layout](#the-screen-layout)
    - [The Three Business Modes](#the-three-business-modes)
    - [Signing Out](#signing-out)
    - [Where to Go Next](#where-to-go-next)
2. [Starting and Ending a Shift](#starting-and-ending-a-shift)
    - [What a till and a cash session are](#what-a-till-and-a-cash-session-are)
    - [Opening your shift](#opening-your-shift)
    - [Getting back into a shift that is still open](#getting-back-into-a-shift-that-is-still-open)
    - [When a till is in use by someone else](#when-a-till-is-in-use-by-someone-else)
    - [The session number and status chips](#the-session-number-and-status-chips)
    - [The session menu](#the-session-menu)
    - [Manager approval](#manager-approval)
    - [X-read — a mid-shift drawer report](#x-read--a-mid-shift-drawer-report)
    - [Recording a cash payout](#recording-a-cash-payout)
    - [Recording a till expense](#recording-a-till-expense)
    - [Closing the session](#closing-the-session)
    - [Reconcile / Z-read (supervisor only)](#reconcile--z-read-supervisor-only)
    - [Printing drawer reports](#printing-drawer-reports)
    - [End-of-shift checklist](#end-of-shift-checklist)
3. [Selling — Supermarket](#selling--supermarket)
    - [1. The supermarket screen at a glance](#1-the-supermarket-screen-at-a-glance)
    - [2. Adding items](#2-adding-items)
    - [3. Working with lines in the grid](#3-working-with-lines-in-the-grid)
    - [4. Age-restricted items](#4-age-restricted-items)
    - [5. Choosing the customer](#5-choosing-the-customer)
    - [6. Reading the running total](#6-reading-the-running-total)
    - [7. Finishing the sale — Pay](#7-finishing-the-sale--pay)
    - [8. Quick troubleshooting](#8-quick-troubleshooting)
4. [Selling — Pharmacy and Restaurant](#selling--pharmacy-and-restaurant)
    - [1. Switching modes](#1-switching-modes)
    - [2. The Pharmacy register](#2-the-pharmacy-register)
    - [3. The Restaurant register](#3-the-restaurant-register)
    - [4. Quick reference](#4-quick-reference)
5. [Taking Payment](#taking-payment)
    - [Opening the Payment screen](#opening-the-payment-screen)
    - [What you see on the Payment screen](#what-you-see-on-the-payment-screen)
    - [The four tender types](#the-four-tender-types)
    - [Task: a simple cash sale](#task-a-simple-cash-sale)
    - [Task: a single non-cash payment](#task-a-single-non-cash-payment)
    - [Task: split or multi-tender payment](#task-split-or-multi-tender-payment)
    - [The change preview](#the-change-preview)
    - [Completing the sale](#completing-the-sale)
    - ["Did that go through?" — the safe retry](#did-that-go-through--the-safe-retry)
    - [After payment: the receipt](#after-payment-the-receipt)
    - [Quick reference](#quick-reference)
6. [Receipts and Refunds](#receipts-and-refunds)
    - [1. The receipt screen](#1-the-receipt-screen)
    - [2. Printing a receipt](#2-printing-a-receipt)
    - [3. Gift receipts (hiding prices)](#3-gift-receipts-hiding-prices)
    - [4. Reprinting an earlier receipt](#4-reprinting-an-earlier-receipt)
    - [5. Refunding (reversing) a whole sale](#5-refunding-reversing-a-whole-sale)
    - [6. What OrbixPOS does *not* do — partial and single-line refunds](#6-what-orbixpos-does-not-do--partial-and-single-line-refunds)
    - [7. There is no cash-drawer refund payout](#7-there-is-no-cash-drawer-refund-payout)
    - [8. Quick reference and troubleshooting](#8-quick-reference-and-troubleshooting)
7. [Troubleshooting and Good Practice](#troubleshooting-and-good-practice)
    - [How OrbixPOS tells you something went wrong](#how-orbixpos-tells-you-something-went-wrong)
    - [Cannot reach the server](#cannot-reach-the-server)
    - [Sign-in problems](#sign-in-problems-1)
    - [Shift and till problems](#shift-and-till-problems)
    - [You do not have permission](#you-do-not-have-permission)
    - [A sale is refused or its outcome is unclear](#a-sale-is-refused-or-its-outcome-is-unclear)
    - [Scanning and search problems](#scanning-and-search-problems)
    - [Printer and cash drawer problems](#printer-and-cash-drawer-problems)
    - [Reprinting a receipt (and why it never makes a new sale)](#reprinting-a-receipt-and-why-it-never-makes-a-new-sale)
    - [Good habits that prevent problems](#good-habits-that-prevent-problems)
    - [Quick reference — symptom to fix](#quick-reference--symptom-to-fix)
8. [For Supervisors and Store Managers](#for-supervisors-and-store-managers)
    - [What each role can and cannot do](#what-each-role-can-and-cannot-do)
    - [Manager approval at the till](#manager-approval-at-the-till)
    - [Segregation of duties — why two people, not one](#segregation-of-duties--why-two-people-not-one)
    - [Close, reconcile and the Z-read](#close-reconcile-and-the-z-read)
    - [X-read, payouts and reviewing a shift](#x-read-payouts-and-reviewing-a-shift)
    - [Freeing a till that is in use](#freeing-a-till-that-is-in-use)
    - [Operating multiple tills and branches](#operating-multiple-tills-and-branches)
    - [Creating and retiring tills](#creating-and-retiring-tills)
    - [Provisioning cashiers — who is allowed to sell](#provisioning-cashiers--who-is-allowed-to-sell)
    - [End-of-day close-out across tills](#end-of-day-close-out-across-tills)
    - [Quick reference](#quick-reference-1)

---

# Getting Started

Welcome to OrbixPOS — the till app you use to ring up sales, take payment, and print receipts. This chapter is for a first-time cashier and for whoever sets up the till PC. It explains what OrbixPOS is and the devices it runs on, how to install it on a Windows till, how to connect it to your ERP server and your receipt printer, how to sign in (and what to do when sign-in fails), how to read the screen, the three business modes, and how to sign out.

You do not need to read it cover to cover before your first shift. A cashier can skim the headings and read **Signing In** and **The Screen Layout**. The person setting up the till should work through **Installing OrbixPOS**, **Trusting Your Server's Certificate** (only if the server address starts with `https://`), and **First-Run Setup** once.

---

## What OrbixPOS Is

**What it is.** OrbixPOS is the front-of-counter till program. You use it to scan or pick items, take a customer's payment (cash, card, mobile money, cheque, or a split of several), and hand over a printed receipt. It is the screen you will spend your whole shift on.

**Why it exists.** A shop needs a fast, reliable till that any cashier can pick up in minutes. OrbixPOS is built for exactly that: a clean, scanner-first counter screen that does the ringing and the paying, and leaves the bookkeeping to the central system behind it.

**When you use it.** Every shift, from the moment you sign in and open your till to the moment you count your drawer and close it at the end of the day.

**How it works.** OrbixPOS does not store your shop's prices, stock, or customers itself. It is a *client*: every time you add an item or take a payment, it asks your ERP server — the central system your shop runs on — over the network, and shows you what the server says. This matters in one important way:

> The server is the single source of truth for price, VAT (tax), totals, and your cash variance. The money you see on screen **before** you take payment is a helpful preview, not the final word. The figures that matter — and the printed receipt — come from the finalised invoice the server sends back **after** you take payment. If a preview total and a printed total ever differ, the printed receipt is correct.

Because OrbixPOS leans on the server for everything important, it needs to know where that server is. That is the first thing you set up.

### The devices it runs on

OrbixPOS is one app that can run on three kinds of device. The screens, buttons, and steps in this manual are the same on all three, with one important difference: **only the Windows desktop app can print** receipts and drawer reports, and so only Windows can open the cash drawer.

| Device | Notes |
|---|---|
| **Windows desktop** | The main way counters run OrbixPOS. A USB barcode scanner, a receipt printer and (through the printer) a cash drawer plug in here. |
| **Web browser** | The same till, opened as a web page. You can ring and take payment, but there is no printing. |
| **Android** | A phone or tablet till. You can ring and take payment, but there is no printing. |

> **About the barcode scanner.** The scanner works as a *keyboard wedge*: a USB scanner that simply "types" the barcode into whatever field is focused and presses Enter, exactly as if you had typed it yourself very fast. You do not configure it in OrbixPOS — you just make sure the right field is focused (the app does this for you on the sell screen) and scan.

> **About the printer and cash drawer.** On the Windows app, OrbixPOS prints receipts and drawer reports straight to a receipt printer installed in Windows — usually an 80 mm or 58 mm thermal printer. A cash drawer plugged into the printer's drawer port can be opened automatically when a sale's receipt prints. Both are set up once in **Setup & diagnostics** (see **Setting up the receipt printer** below). Weighing scales are not connected to OrbixPOS; weighed items come through weight barcodes or a typed quantity.

---

## Installing OrbixPOS on a Till PC

*This section is for the person who sets up the till.*

**What it is.** OrbixPOS for Windows is delivered as a single zip file named like `OrbixPOS-1.5.4+12-windows.zip`. There is no installer: you unzip the folder and run the program inside it.

**Before you start.** Install the receipt printer in Windows first, using the driver that came with it (or the one your supplier recommends), and print a Windows test page so you know the printer itself works. Make sure the till PC is connected to the same network as the ERP server.

To install:

1. Unzip the whole folder to a fixed place on the till PC, for example `C:\OrbixPOS`.
2. Open the folder and run **pos_app.exe**. The window opens titled **Orbix POS**. You may want to create a desktop shortcut to `pos_app.exe` for the cashiers.
3. Keep every file in the folder together. `pos_app.exe` will not start if it is copied somewhere on its own.
4. Carry on with **First-Run Setup** below.

To upgrade to a new version:

1. Close OrbixPOS.
2. Copy the new version's files over the old folder's files, replacing them.
3. Start `pos_app.exe` again. Your server setup, printer setup and sign-in are kept.

> **Keep your certificate files when you upgrade.** If you created a `certs` folder or an `erp-ca.pem` file in the OrbixPOS folder (see the next section), they are not part of the zip. Copy the new files *over* the old folder rather than deleting the folder first, or put the certificate files back afterwards.

> **Which version is installed?** The folder contains a `README.txt` that names the version and what changed (the full history is in `Docs\RELEASE-NOTES.txt`). You can also right-click `pos_app.exe`, choose **Properties**, open the **Details** tab and read **Product version**. OrbixPOS also shows its version on screen, in small grey text — on the sign-in screen just under the **Server setup** link, and at the bottom of the **Session** menu — for example **OrbixPOS 1.5.4+12**. When reporting a problem, always say which version the till runs.

> **One setup per Windows user.** OrbixPOS remembers its settings for the Windows user account that ran it. If the till PC has more than one Windows login, do the First-Run Setup under each login that will run the till.

---

## Trusting Your Server's Certificate (https only)

*This section is for the person who sets up the till. Skip it if your ERP address starts with `http://`.*

**What it is.** When the ERP address starts with `https://`, the connection is encrypted and OrbixPOS checks the server's security certificate before it will talk to it. A server with a normal public certificate is trusted automatically. Many shops' servers, however, use a **private certificate** issued by their own certificate authority — common when the server has no public web address. OrbixPOS will refuse to connect to such a server until it is told to trust that authority.

**Why it matters.** Until the certificate is trusted, the till simply cannot reach the server: **Test connection** reports **Could not reach the ERP at this host.** even though the server is perfectly healthy. Trusting the authority fixes this without switching off any security check.

**How it works.** Your administrator gives you the server's *root certificate* — a small file ending in `.pem` or `.crt`. Some servers run by the OrbixPOS team are already trusted by the app; your administrator will tell you if no file is needed.

To trust a server's certificate:

1. Close OrbixPOS.
2. In the OrbixPOS folder (the one holding `pos_app.exe`), create a folder named `certs`.
3. Copy the certificate file into `certs`. If this till talks to more than one server, put one file per server in the same folder — any number of `.pem` and `.crt` files is fine.
   - As an alternative to the `certs` folder, you can name a single file `erp-ca.pem` and place it directly beside `pos_app.exe`.
4. Start OrbixPOS again. It reads the certificate files only when it starts, so a file added while it is running has no effect until you restart it.
5. Open **Server setup** and press **Test connection** (see below). It should now turn green.

> **Use the server name the certificate was issued for.** Type the ERP host exactly as your administrator gives it — usually a name, not a number. A certificate issued for a name does not match the server's numeric IP address, and the connection is refused.

> **If a working till suddenly cannot connect over https.** If the server was rebuilt, it may have created a new certificate authority, and the old file no longer matches. Ask your administrator for the new root certificate, drop it into `certs`, and restart OrbixPOS. No reinstall is needed.

> **For IT staff.** Certificate files can also be supplied through the `POS_ERP_CA_FILE` environment variable (one or more file paths, separated by `;`). This is added to the `certs` folder and `erp-ca.pem`, not instead of them.

---

## First-Run Setup — Pointing the Till at Your Server and Printer

**What it is.** A one-time step where you tell OrbixPOS the network address of your ERP server — called the **ERP host** — and which receipt printer to use.

**Why it exists.** OrbixPOS owns no data — it calls the server for everything. Until it knows the server's address it cannot sign you in or ring a single sale, and until it knows the printer it cannot print.

**When it happens.** Once, when OrbixPOS is installed on a new device — typically done for you by the person who set up your till. You will only need it yourself if you are setting up a brand-new device, if the till says it cannot reach the server (for example after a network change), or if the printer changes.

**How it works.** You open the **Setup & diagnostics** dialog from the sign-in screen, type the host, press **Test connection**, choose the printer settings, press **Test print**, and **Save**. Everything is remembered on the device, so you do not repeat it every day.

> **Setup is only on the sign-in screen.** To change the server or printer during a shift, sign out (your shift stays open), click **Server setup**, make the change, **Save**, and sign in again. OrbixPOS takes you straight back to your open shift.

### Setting the ERP host

1. On the sign-in screen, click **Server setup** (the small link with a gear icon below the **Sign in** button). The **Setup & diagnostics** dialog opens, with the line "Point the till at your ERP server and receipt printer."
2. In the **ERP host** field, type the address your administrator gave you. Type the scheme and the server **only** — `http://` or `https://`, the server name or IP address, and a port if your administrator gave one. For example:
   - `http://192.168.1.10:8081` — a server on your shop's network;
   - `http://localhost:8081` — the ERP running on this same PC;
   - `https://erp.yourshop.co.tz` — a server with its own web address.

   Do **not** add `/api/v1` or any other path after the address — the dialog reminds you: "The /api/v1 path is added automatically." Typing `/api/v1` yourself makes the till look in the wrong place and the test fails. A trailing slash is harmless; the till removes it.
3. Click **Test connection**. The till tries to reach the server and tells you the result:

| What the dialog says | What it means | What to do |
|---|---|---|
| **Reachable — ERP is UP.** (green) | The till reached the server and the server is healthy. | Carry on to the printer settings, then **Save**. |
| **Reached host, status unclear.** (red) | The till reached *something* at that address, but it did not answer as a healthy ERP. | The server may still be starting — wait a minute and test again. If it persists, check the address and port with your administrator. |
| **Could not reach the ERP at this host.** (red) | The till could not get an answer from an ERP at that address. | Check for typos, make sure you did not add `/api/v1`, check the network cable or Wi-Fi, and — for an `https://` address — check the certificate (see the previous section). Then ask your administrator whether the server is running. |

> When the dialog first opens, the ERP host may already show `http://localhost:8081`. That is only correct if the ERP runs on the till PC itself; otherwise replace it with your server's address.

### Setting up the receipt printer

Below the host, the **Receipt printer** section holds four settings. (On the web and Android versions the printer list is empty and the line "No printers detected (Windows desktop only)." appears — those versions cannot print.)

| Setting | What to choose |
|---|---|
| **Printer** | The receipt printer, picked from the printers installed in Windows. **— none —** means no printer: the till still sells, but **Print** only reminds you to set one up. |
| **Paper width** | **80 mm · 48 cols** or **58 mm · 32 cols** — match the paper roll in the printer. The wrong width makes lines wrap or look squashed. |
| **Print mode** | **Thermal (ESC/POS + cut)** for a thermal receipt printer — it prints and cuts the paper. **Plain text** for any other printer that accepts plain text; it prints the text and ejects the page, with no cut. |
| **Open cash drawer after printing** | Tick this if a cash drawer is plugged into the printer's drawer port. The drawer then opens when a sale's receipt prints for the first time, straight after the sale. It does not open on a reprint, a gift receipt, a second copy or a reversed sale, and drawer reports (X-read, Z-read) never open it. It works only with **Thermal (ESC/POS + cut)** mode. |

Then check it:

1. Click **Test print**. A sample receipt is sent to the chosen printer. You will see **Test sent to …** followed by the printer's name. If you have not picked a printer you will see **Choose a printer first.**
2. Check the paper: the text should be clean, the lines should fit the paper width, and (in thermal mode) the paper should be cut. If you ticked the drawer option, the drawer should open.
3. If the test page prints strange symbols or nothing at all, see the printer section of the *Troubleshooting* chapter (Chapter 7).

### Saving

Click **Save**. The dialog closes and the host and printer settings are stored on this device together. Click **Cancel** instead to leave everything unchanged.

> **Save does not check the connection.** The dialog lets you save an address that failed its test. Always get a green **Reachable — ERP is UP.** before you save.

---

## Signing In

**What it is.** Signing in proves who you are to the system. You give the username and password your administrator created for you; the system checks them and starts your personal session. From that moment on, everything you ring is recorded against *you*.

**Why it exists.** Only named people should be able to take money and open a till, and the shop needs to know which cashier did what. Signing in is what ties each sale, each drawer, and each receipt to a real person.

**When it happens.** At the start of every shift, and again any time your session ends — for example if you sign out, or if the server ends the session.

**How it works.** When your username and password are accepted, OrbixPOS loads your details, the company and branch you work in, and what you are allowed to do (your permissions). Actions you are not permitted to use simply do not appear, so you never see a button you cannot use. Then:

- if you already have a shift open on a till (for example the app was closed or the power went off mid-shift), OrbixPOS takes you **straight back to your register** in that shift;
- otherwise it opens the **Open shift** screen, where you pick your mode and till and start your shift.

To sign in:

1. Open OrbixPOS. The **Sign in** screen appears, headed "Sign in" with the line "Open your till and start your shift."
2. In the **Username** field, type the username your administrator gave you. (The cursor starts here for you; press **Enter** to move to the password.)
3. In the **Password** field, type your password. It is hidden as you type.
4. Click **Sign in** (the large button), or press **Enter** in the password field.

The button shows a brief spinner while the till checks your details, then the next screen opens. Opening a shift is covered in the next chapter.

> **If OrbixPOS was closed without signing out**, it may sign you straight back in when it reopens, without asking for your password, as long as your sign-in is still valid. Always sign out when you leave the till for good.

> Keep your password to yourself. Because every sale and every drawer count is recorded against the person signed in, never sign in for a colleague or let someone use your session.

### Sign-in problems

If something is wrong, a red banner appears just above the **Sign in** button with a short message. Here is how to read the common ones:

| What you see | What to do |
|---|---|
| A red banner saying your details were not accepted | Re-type your username and password carefully (mind the Caps Lock key). If it still fails, or the message says your account is locked, ask your administrator. |
| **Your session ended. Please sign in again.** or **Your session has expired. Please sign in again.** | The server ended your previous session. Just sign in again. Nothing you finalised is lost, and an open shift is still open. |
| **Cannot reach the ERP. Check the connection and host.** | The till cannot reach the server. Open **Server setup**, press **Test connection**, and follow the table above. |
| **The server certificate was rejected.** | The till does not trust the server's https certificate. See **Trusting Your Server's Certificate** above. |
| **The server did not respond in time. The request may or may not have completed.** | The network or server is slow. Wait a moment and sign in again. |
| **This user is not assigned to any company.** or **No branches found for …** | Your account is not yet attached to a company or branch. Ask your administrator. |
| **You do not have permission for this action.** right after signing in | Your account lacks one of the basic rights the till needs to start (for example, to see your branch). Ask your administrator to check your role. |
| An amber strip: "Using branch … — we couldn't confirm your usual branch. Check this is correct before selling." | The till could not confirm your usual branch and picked another. Make sure the branch named is the shop you are standing in before you sell; if it is wrong, sign out and ask your administrator. |

> **Every sale is credited to you.** Each sale is recorded against the person signed in at the till, as their own sale. There is nothing to set up first: if your account has never sold before, the system creates your sales-agent record for you on your first sale. The shop's top-level super-admin account is not a salesperson and cannot ring sales — sell with the personal cashier account your administrator gave you, never a shared admin login. If a sale is refused with a message about a sales agent, see the *Troubleshooting* chapter (Chapter 7).

---

## The Screen Layout

Once you have signed in and opened a shift, you spend your shift on the **register** (the sell screen). The middle and lower part of this screen changes with your business mode — that is covered under **The Three Business Modes** below. The strip along the very top, the **top bar**, is the same in every mode.

From left to right, the top bar contains:

- **The OrbixPOS brand** — the diamond mark (◆) and the word **OrbixPOS** on the far left. It is just a label.
- **The branch chip** — a small pill with a shop icon showing the **branch** you are working in, with your **company** name beside it. A branch is the specific shop or location this till belongs to. You do not switch branches from here.
- **The session chip** — a green pill reading **Session** followed by your session number (for example `POS-0001`). It confirms your till session is open. If it shows a dash (**—**) instead of a number, the till reopened your shift without being able to load its details — see the *Starting and Ending a Shift* chapter (Chapter 2).
- **The mode switcher** — a pill-shaped switch in the centre showing the three business modes (**🛒 Supermarket**, **💊 Pharmacy**, **🍽 Restaurant**). The mode you are in is highlighted.
- **The session menu button** — the **☰** icon toward the right (tooltip **Session menu**). Click it to open the **Session** panel that slides in from the right. This holds the shift actions: **X-read**, **Cash payout**, **Till expense**, **Today's sales**, **Recent receipts**, **Close session**, **Reconcile (Z-read)**, and **Z-read (reprint)** — each one only if your account is allowed to use it. These are covered in Chapter 2.
- **Your avatar** — a small round badge on the far right showing your initials, so you can see at a glance who is signed in.

> **Messages.** Short messages ("toasts") appear for a few seconds near the bottom of the screen — dark for information and problems, green when something succeeded. Read them; the *Troubleshooting* chapter (Chapter 7) explains each one.

> The **Session** panel (opened from **☰**) also has its own **Sign out** button at the bottom.

---

## The Three Business Modes

**What they are.** OrbixPOS comes in three flavours of sell screen — called **business modes** — each tuned for a different kind of shop. The way you take payment, print receipts, and run your session is identical in all three; only the *register* (how you add items to the sale) changes.

**Why they exist.** A grocery cashier wants to scan barcodes fast; a pharmacy needs patient and prescription details; a restaurant works in tables and order tickets. One till would feel wrong for all three, so OrbixPOS gives each its own register while keeping everything else the same — so the skills you learn in one mode carry straight over.

**When you choose a mode.** You pick a mode when you open your shift (covered in the next chapter), and you can switch between modes during your shift using the switcher in the top bar — as long as you are not in the middle of a sale.

**How it works.** The three modes are:

| Mode | Best for | What the register looks like |
|---|---|---|
| **🛒 Supermarket** | Fast, scanner-first grocery checkout | A wide, Excel-style grid of sale lines on the left with a number pad on the right. You scan or type a code and the item drops in; you can adjust quantity and discount inline. |
| **💊 Pharmacy** | Dispensing with prescriptions | A patient/prescriber header (patient, prescriber, prescription number) above a dispensing line table, with the running totals down the side. |
| **🍽 Restaurant** | Table service | A floor/table picker and a menu grid that build an order ticket, with a **Send to kitchen** action. |

### Switching mode

1. Make sure the current sale is finished or cleared — you cannot switch mode while a sale has items in it.
2. In the top bar, click the mode you want (**🛒 Supermarket**, **💊 Pharmacy**, or **🍽 Restaurant**). The new mode highlights and the register changes immediately.

> If you try to switch while a sale has items in it, OrbixPOS will not switch and shows the message **"Finish or clear the current sale before switching mode."** Complete or clear the sale first, then switch.

---

## Signing Out

**What it is.** Signing out ends your sign-in and returns OrbixPOS to the sign-in screen, ready for the next person.

**Why it exists.** Because every action is recorded against the signed-in person, you should sign out when you step away or finish, so nobody can ring sales under your name.

**When you do it.** At the end of your shift — normally after you have closed your session — or any time you are leaving the till and want to secure it.

**How it works.** Signing out clears your sign-in on this device and shows the **Sign in** screen again. It does not undo any sale: every sale you finalised is safely on the server. There are two places to sign out:

1. **From the Open shift screen** (before you have opened a session): click the **Sign out** icon (the door-with-arrow icon) at the top right, next to your name.
2. **From the Session panel** (any time during your shift): click the **☰** session menu button in the top bar, then click **Sign out** at the bottom of the panel.

> **Signing out does not close your shift.** If you sign out with the drawer still open, the shift stays open on the server, and the next time you sign in OrbixPOS takes you straight back into it. At the end of the day the right order is: **Close session** (count the drawer) → **Reconcile (Z-read)** (a supervisor step) → **Sign out**. These steps are covered in the *Starting and Ending a Shift* chapter (Chapter 2).

---

## Where to Go Next

Now that you can connect the till, sign in, read the screen, and choose a mode, you are ready to start your day:

- **Opening your shift** — picking a till, entering your opening float, and getting back into a shift that is still open (Chapter 2).
- **Ringing a sale** — adding items in each mode (Chapters 3 and 4).
- **Taking payment** — cash, card, mobile money, cheque, and split payments, and what to do when you are not sure a sale went through (Chapter 5).
- **Receipts** — printing, gift receipts, reprints, and refunds (Chapter 6).
- **Troubleshooting** — every message, its cause, and the fix (Chapter 7).

---

# Starting and Ending a Shift

Every selling day at the till runs inside a *shift*. This chapter explains what a till and a cash session are, how to open your shift in the morning, how to get back into a shift that is still open after the app closed or the power went off, how to use the session menu through the day (mid-shift reports, cash payouts, till expenses), and how to count down and close the drawer at the end. It is written for cashiers; the steps marked **supervisor** are for the person who is allowed to approve reports and finalise the day.

You should already be signed in and at the **Open shift** screen. If you are not yet signed in — or the app is asking you to set the ERP host and **Test connection** — see the *Getting Started* chapter (Chapter 1) first, then come back here.

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

**How it works.** The screen shows the company and branch you are working in at the top, with your name and **@username** on the right. Below that are three things to set, in order: the **Business mode**, the till (**Choose a till**), and the **Opening float**. When you press **Open session** the app asks the server to create the session and then drops you onto the register, ready to sell.

### Step by step

1. Check the header. Under the **Open shift** title you will see your **company · branch**. Make sure this is the branch you mean to sell on. (To change who you are, use the **Sign out** icon at the top right.) If an amber strip says "Using branch … — we couldn't confirm your usual branch", check that the branch named is correct before you go on.
2. Under **Business mode**, choose how this till should behave by clicking one of the three cards:

   | Mode | Card label | Best for |
   |---|---|---|
   | 🛒 | **Supermarket** | Fast scanner-first grocery checkout |
   | 💊 | **Pharmacy** | Dispensing with patient & Rx capture |
   | 🍽 | **Restaurant** | Table service with order tickets |

   The selected card is highlighted. You can change mode later from the register's top bar, but only while no sale is in progress — so it is best to pick the right one now.
3. Under **Choose a till**, click the till you are working on. Only **active** tills for your branch are shown. Each tile shows the till **name** and a coloured dot:

   | Tile shows | Meaning |
   |---|---|
   | The till code and a **green** dot | The till is free. Click it to select it; the tile highlights. |
   | The till code, **· Your shift**, and an **amber** dot | You already have a shift open on this till. Click it to resume or close it — see **Getting back into a shift that is still open**. |
   | A colleague's name, a **red** dot, and a faded tile | Someone else has a shift open on this till. Click it to see who and what to do — see **When a till is in use by someone else**. |

   Use **Refresh** (above the tiles, on the right) to reload the list — for example after a supervisor frees a till. You never need to restart the app to see a change.

   > If the list reads **No active tills on this branch yet**, ask your store manager to create or activate a till for you. Store managers (anyone allowed to manage tills) see a **New till** button next to **Refresh**: it asks for a **Till name** and creates the till on the spot (**Till created.**).
   >
   > If the till list shows an error message with a **Retry** button, your connection to the server dropped. Click **Retry**.
4. In **Opening float**, type the amount of cash you are starting the drawer with, in your branch currency (the field label shows the currency, e.g. **Opening float (TZS)**). Count the float first and enter the exact figure, digits only (for example `50000`). If you are starting with an empty drawer, leave it as `0`.
5. Press **Open session**.

The app creates the session and takes you to the register. The button shows a busy spinner while it works; if anything goes wrong, a short message appears at the bottom of the screen — read it, fix the cause, and try again.

| What you see | What to do |
|---|---|
| **Pick a till first.** | You pressed **Open session** without selecting a till tile. Click a free till, then try again. |
| **This till already has an OPEN session.** | Someone opened a shift on that till a moment ago. Click **Refresh**, then pick a free till. |
| **You do not have permission for this action.** | Your account is not allowed to open a shift. Ask your administrator to check your role. |
| An amber strip: "We couldn't check whether you already have a shift open. If your till shows as in use, open it again from the list below." | The till could not look up your open shift when you signed in. If one of the tiles shows **· Your shift**, click it and choose **Resume shift**. You can close the strip with its **×**. |

---

## Getting back into a shift that is still open

**What it is.** If OrbixPOS was closed in the middle of a shift — the app was shut, the PC restarted, the power failed, or you simply signed out — your shift is still OPEN on the server, with the float and every sale safely recorded. OrbixPOS lets you carry straight on with it, or count the drawer and close it.

**Why it exists.** Because only a counted close ends a shift, a shift you could not get back into would lock you out of your own till. Resuming puts you back exactly where you were.

**How it works.** In most cases you do not have to do anything: when you sign in, OrbixPOS looks for an open shift of yours and, if it finds one, opens the register in that shift straight away.

If you land on the **Open shift** screen instead, your till shows **· Your shift** with an amber dot. Click it. A dialog titled **Your shift is still open** says "You already have a shift open on …" (the till's name and the time it was opened) "… Carry on where you left off, or count the drawer and close it." It offers:

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

If the shift cannot be reopened at all, a dialog titled **Couldn't reopen your shift** says "Your shift on … is still open, so nothing has been lost. Ask a supervisor for POS session access, or count the drawer and close the shift." Choose **Not now** to leave it, or **Close shift** (if shown) to count up. If a dialog titled **Your shift** says it "could not load it just now", click **Refresh** on the Open shift screen and try again.

### Closing an abandoned shift from the till list

Use this when you do not want to carry on with the old shift — for example it was left open overnight. This is a real cash-up, exactly like closing at the end of a shift.

1. Count all the cash in that till's drawer.
2. Click your **· Your shift** tile, then **Close shift**.
3. In the **Close your shift** dialog ("Count the cash in the … drawer and enter the total. This is what the shift is settled against."), type the count into **Counted cash (TZS)**. The field starts empty on purpose — type what you actually counted.
4. Click **Close shift**. The dialog shows **Shift closed** with the **Expected**, **Counted** and **Variance** figures (variance in red when short) and the note "A supervisor reconciles the shift to post the variance. The till is free to use now."
5. Click **Done**. You see **Shift closed. The till is free again.** and the till list refreshes. You can now open a fresh shift.

If you leave **Counted cash** empty you are told **Count the drawer and enter the cash total.**

---

## When a till is in use by someone else

**What you see.** The tile shows a colleague's name with a red dot and looks faded. Clicking it opens **Till in use**: "… has a shift open on …" (the colleague's name, the till, and since when) "… A shift only ends once the drawer is counted — that cashier can close it from their own sign-in, or an administrator can close it in the ERP." Click **Got it**.

**What to do.**

- Pick a different free till, **or**
- ask that cashier to sign in and close their shift (counting the drawer), **or**
- if that cashier is not available, ask a **branch manager** or an administrator to close it. They do this in the ERP, on the **POS Sessions** screen, by counting the drawer and entering the count. Once it is closed, click **Refresh** and the till turns green.

> Nobody should close another person's shift without physically counting that drawer. A guessed count hides a real shortage or overage.

---

## The session number and status chips

Once your shift is open, look at the **top bar** of the register. From the left you will see:

- The **OrbixPOS** brand mark.
- A **store** chip showing your **branch** and **company**.
- A green **Session** chip showing your session number (for example `POS-0001`) — your at-a-glance confirmation that a shift is live on this device. A dash means the shift was resumed without its details (see above).
- In the middle, the **mode switcher** (🛒 Supermarket / 💊 Pharmacy / 🍽 Restaurant) — switching is blocked while a sale is in progress.
- On the right, the **Session menu** button (the ☰ icon) and your avatar.

---

## The session menu

**What it is.** The session menu is a side panel that holds every shift-level action that is not part of ringing a sale: drawer reports, cash payouts, till expenses, receipt look-ups, and closing or reconciling the drawer.

**How to open it.** On the register's top bar, click the **Session menu** button — the ☰ icon near your avatar on the right. The **Session** panel slides in from the right. Click the **✕** at its top (tooltip "Close the session menu"), or click the dimmed area beside it, to close it again.

**What it shows.** At the top, a small grid of facts about the current session:

| Tile | Meaning |
|---|---|
| **Session** | The session number (e.g. `POS-0001`). |
| **Status** | `OPEN`, `CLOSED`, or `RECONCILED`. |
| **Opened** | The time the shift was opened. |
| **Float** | The opening cash you declared. |

Below the facts is the list of actions. **You only see the actions your account is allowed to use** — an action you have no permission for is not shown at all. An action you *are* allowed but cannot use right now stays in the list, faded, with the reason underneath (for example "Close the session first").

| Action | What it does | Who sees it |
|---|---|---|
| **X-read** | A mid-shift drawer report. Resets nothing. | Anyone working the till. A cashier without permission to view sessions needs a manager's approval each time. |
| **Cash payout** | Records cash leaving the drawer (a paid-out, for example a drop to the safe). Reason required; a manager approves it at the till. | Cashiers allowed to open shifts. Only while the session is OPEN. |
| **Till expense** | Records cash paid out of the drawer for a business expense, under a category (transport, cleaning, …). A manager approves it at the till. | Anyone allowed to record till expenses (the standard Cashier role is). Only while the session is OPEN. |
| **Today's sales** | Lists today's sales at this branch and reprints a receipt. | Anyone allowed to view sales invoices. |
| **Recent receipts** | Reprints a receipt saved on **this device** (works offline). | Everyone. |
| **Close session** | Count the drawer; the server computes the variance. | Anyone allowed to close sessions. Only while OPEN. |
| **Reconcile (Z-read)** | Posts the variance to the accounts and finalises the session. | **Supervisors only.** Only after the session is CLOSED. |
| **Z-read (reprint)** | Shows and reprints the final figures again. | Anyone working the till (a manager's approval may be needed). Only after the session is RECONCILED. |

At the very bottom of the panel is **Sign out**. Signing out does **not** close your session — your shift stays OPEN on the server until you actually close it. Under **Sign out**, in small grey text, is the version of OrbixPOS on this till (for example **OrbixPOS 1.5.4+12**) — quote it when you call for support.

---

## Manager approval

Some actions need a supervisor or manager standing at the till. When that is the case, a dialog titled **Manager approval — …** opens (for example **Manager approval — X-read**). It says what is being approved and for which session.

1. The manager types their own **Manager username** and **Manager password**.
2. The manager clicks **Approve**. (**Cancel** backs out; nothing happens.)

The till stays signed in as you throughout — the dialog says "You stay signed in — this only checks the manager’s authority for this one action." The approval covers that **one** action only; the next report asks again.

| What the dialog shows | What it means |
|---|---|
| **Enter the manager username and password.** | One of the fields is empty. |
| **Those details were not accepted. Check the username and password and try again.** | Wrong username or password. Only the password is cleared, so the manager can retype it. |
| **That user is not allowed to approve this action.** | The password was right, but that person may not approve this — or the person signed in at the till tried to approve their own action. The approver must be a **different person** who holds the supervisor right for this action. |
| **Too many failed approval attempts. Please wait a moment and try again.** | Several wrong attempts in a row. Wait, then try again. |

For drawer reports, the approver must be someone allowed to reconcile tills (a supervisor or branch manager). If a report is still refused after approval, the till shows **That approval was not accepted. Ask a supervisor who can reconcile the till.**

---

## X-read — a mid-shift drawer report

**What it is.** An *X-read* is a snapshot of where the drawer stands right now: total sales by tender, the float you started with, cash sales, payouts, and the cash the server expects to be in the drawer. It is read-only.

**Why it exists.** It lets you (or a supervisor) check the drawer mid-shift — for a spot count, a cash drop decision, or a shift handover — without ending the session. It changes nothing and can be run as many times as you like.

**When it happens.** Any time while the session is **OPEN** — and also after it is **CLOSED**, until it is reconciled. Once the session is **RECONCILED**, use the Z-read instead (the till says the session "has been reconciled — open its Z-read for the final figures").

**How it works.** Depending on your account, one of two things happens when you click **X-read**:

- if you are allowed to view sessions, the report opens straight away;
- if not, a **Manager approval — X-read** dialog opens first ("Show the mid-shift drawer report for this session."). Once a manager approves, the report opens for that one view.

### Step by step

1. Open the **Session menu** (the ☰ button).
2. Click **X-read**. If asked, have a manager approve it.
3. Read the report:

   | Line | What it means |
   |---|---|
   | **Sales (all tenders)** | Everything taken on this session so far, whatever the payment type. Under it, **By tender** splits it — for example `Cash 32,020.00 · Mobile 14,000.00`. |
   | **Opening float** | The cash you declared at open. |
   | **Cash sales** | The cash part of your sales — the only part that ends up in the drawer. |
   | **Payouts** | Cash that has left the drawer, shown as a negative, with a line for each type (for example **Paid out (2)**, **Expense (1)**). |
   | **Expected cash** | What the server says should be in the drawer right now: float + cash sales − payouts. **Shown only to a supervisor** (someone allowed to reconcile the till) or when a manager approved this X-read at the till. A cashier counts the drawer *blind* — without knowing the figure — so the count is honest. |

   The footer shows how many invoices have been rung (for example `23 invoices`) and the reminder "An X-read does not close the shift and resets nothing."
4. To print it, click **Print** (Windows till with a receipt printer only). You see **Printed.** — or **No receipt printer set — configure one in Setup.** if no printer is set up.
5. Click **Close** to dismiss the report. Nothing has changed — your shift is still open.

> An X-read is a **cash-drawer** report, not an accounting report. **Sales (all tenders)** is usually bigger than the cash in the drawer because card, mobile money and cheque payments do not go into the drawer.

---

## Recording a cash payout

**What it is.** A *cash payout* (a **Paid out**) records money physically leaving the drawer during the shift that is not change on a sale and not a business expense — for example a drop to the safe. It reduces the cash the server expects in the drawer, and it is booked to the ledger as an expense against the drawer, filed under the reason you type — so the reason matters.

> **A cash payout is never a refund.** To give a customer their money back, open the sale in **Today's sales** (or its receipt) and use **Refund / reverse** — see Chapter 6. That puts the stock back and corrects the sales and VAT figures; a payout does none of that. The till no longer offers a "Refund" payout.

> Paying for something the business needs out of the till — transport, cleaning, a small repair — is a **till expense**, not a cash payout. Use **Till expense** (see **Recording a till expense** below) so the cost is filed under the right category.

**Why it exists.** If you take cash out of the drawer without telling the system, your end-of-shift count will look short by that amount. Recording the payout keeps the expected figure matched to reality, so a genuine over/short is not masked.

**When it happens.** Whenever cash leaves the drawer for a reason that is not change on a sale. The session must be **OPEN**. A manager approves every payout at the till (unless you are yourself allowed to reconcile the till).

### Step by step

1. Open the **Session menu** and click **Cash payout** ("Cash paid out of the drawer — manager approves").
2. In **Amount (TZS)**, type how much cash is leaving, digits only. It must be greater than zero.
3. In **Reason (required)**, type what the cash is for — for example `drawer-to-safe drop`. A few words at least.
4. Click **Record**. The **Manager approval — cash payout** prompt opens: the manager types their own username and password and clicks **Approve**.
5. Count out and remove the cash from the drawer.

You will see **Payout recorded.** (or **Payout recorded and posted to the ledger.**), and the dialog closes. The amount is now subtracted from your expected cash, and the manager who approved it is named on the record. To cancel without recording anything, click **Cancel**.

| What you see | What to do |
|---|---|
| **Enter an amount.** | The amount was blank or zero. Type a positive amount and click **Record** again. |
| **Say what the cash is for (at least a few words).** | The reason is missing or too short. Type a clear reason. |
| **That manager is not authorised to approve cash out of the till.** | The person who approved may not settle the till. Ask a manager who can reconcile the till. |
| **Cash payout** is faded with "Only while the session is open" | The shift has already been closed or reconciled — payouts are only allowed while OPEN. |
| A message saying the session is not OPEN | Same cause: the shift was closed. |

---

## Recording a till expense

**What it is.** A *till expense* records cash you pay out of the drawer for something the business needs — a boda-boda for a stock run, cleaning supplies, a small repair, a meal for staff on a long day. Like a payout, it reduces the cash the server expects in the drawer. Unlike a payout, it is filed under a **category**, so the managers can see what the till money was spent on.

**Why it exists.** Small expenses paid from the till add up. Recording each one under its category keeps your drawer count honest *and* tells the managers what the money was spent on, instead of a pile of "paid out" lines they have to read one by one.

**When it happens.** Whenever you pay a business cost in cash from the drawer. The session must be **OPEN**. You see **Till expense** in the Session menu only if your account is allowed to record till expenses — the standard Cashier role is.

### Step by step

1. Pay the cash and keep the paper receipt from the seller, if there is one, for your supervisor.
2. Open the **Session menu** and click **Till expense** ("Cash paid out for the business — by category").
3. In the **Till expense** dialog, type the amount into **Amount (TZS)**, digits only. It must be greater than zero.
4. Under **Category (required)**, tap one of the quick choices — **Transport**, **Cleaning**, **Repairs**, **Meals**, **Utilities**, **Stationery** — or type your own category (2 to 40 letters), for example `Security`.
5. In **What was it for? (required)**, say in a few words what you paid for — for example `boda to collect sugar from depot`.
6. Click **Record**. The **Manager approval — till expense** prompt opens: the manager types their own username and password and clicks **Approve** (you are not asked if you are yourself allowed to reconcile the till).

You will see **Expense recorded and posted to the ledger.** and the dialog closes. The amount is now subtracted from your expected cash. To back out without recording anything, click **Cancel**.

The expense appears on the **X-read** and **Z-read** as its own **Expense** line under **Payouts**, and is posted to the ledger under its category.

| What you see | What to do |
|---|---|
| **Enter an amount.** | The amount was blank or zero. Type a positive amount. |
| **Choose or type a category (2 to 40 letters).** | The category is empty or too long. Tap a quick choice or type a short category. |
| **Say what the cash is for (at least a few words).** | **What was it for?** is missing or too short. |
| **Till expense** is faded with "Only while the session is open" | The shift has already been closed — expenses can only be recorded while it is OPEN. |
| **Till expense** is not in the menu | Your account is not allowed to record till expenses. Ask your supervisor. |

> **Cash payout** records only a **Paid out** — there is no expense or refund choice there. Business expenses always go through **Till expense**; refunds always go through **Refund / reverse**.

---

## Closing the session

**What it is.** *Closing* the session ends selling for the shift. You physically count the cash in the drawer and enter the total; the server compares it against the expected figure and works out the **variance** — the difference between what you counted and what should be there.

**Why it exists.** The close is the moment of truth for the drawer. It pins down, on the spot, whether the till is balanced, over, or short, and it freezes the session so no further sales can be added. It also frees the till for the next shift.

**When it happens.** Once, at the end of your shift, after the last sale and any final payouts. The session must be **OPEN**. **No money is posted to the accounts at this step** — that is the separate reconcile step that follows.

**How it works.** You enter the counted cash; OrbixPOS sends it to the server. The server computes:

- **Expected cash** = opening float + cash sales − payouts
- **Variance** = counted cash − expected cash

A **positive** variance means the drawer is **over** (more cash than expected); a **negative** variance means it is **short** (less cash than expected); **zero** means it balances exactly.

### Step by step

1. Count all the cash in the drawer carefully, including the opening float.
2. Open the **Session menu** and click **Close session** ("Count the drawer → variance").
3. In the **Close session** dialog ("Count the drawer and enter the cash total."), type the figure into **Counted cash (TZS)**, digits only.
4. Click **Close session**.

The dialog now reads **Session closed** and shows the result:

| Line | What it means |
|---|---|
| **Expected** | What the server expected to be in the drawer. A note explains: "Expected = float + cash sales − payouts (cash tenders only; card & mobile money settle separately)." |
| **Counted** | The figure you entered. |
| **Variance** | Counted − expected. Shown in **green** when the drawer balances or is over, and in **red** when it is short. |

**Expected** and **Variance** are shown only to someone allowed to reconcile the till. A cashier sees just the **Counted** figure and the note "Session closed. A supervisor will check the count and settle the drawer." — the supervisor sees the result when they reconcile.

Under the variance, a note reminds you: *"Reconcile (Z-read) posts this variance — supervisor."*

5. Click **Done**.

**What next.** The session is now CLOSED and no more sales can be rung on it.

- If you are a supervisor allowed to reconcile, carry straight on with **Reconcile (Z-read)** below.
- If not, open the **Session menu** and **Sign out**. A supervisor reconciles your closed session in the ERP (the **POS Sessions** screen). When you or the next cashier sign in, the **Open shift** screen appears and the till is free.

| What you see | What to do |
|---|---|
| **Enter the counted cash.** | The **Counted cash** field was empty. Enter your count and click **Close session** again. |
| A red (short) variance | The drawer is short of what was expected. Note the shortfall and follow your branch's cash-handling procedure. The figure is already recorded; it cannot be re-entered. |
| A message that the session is not OPEN, or **Close session** is faded with "The session is already closed" | The session was already closed. It cannot be closed twice. |

> Counting honestly matters more than counting "to balance". The variance is meant to surface real differences. If you are short or over, record the true count — do not adjust your figure to hide it. Count twice *before* you click **Close session**.

---

## Reconcile / Z-read (supervisor only)

**What it is.** *Reconcile* is the final step that closes the books on a session. It posts the cash variance to the accounts and moves the session from CLOSED to RECONCILED. The **Z-read** is the formal end-of-shift report it produces.

**Why it exists.** Reconcile turns a counted drawer into an accounting fact. It is deliberately kept separate from the count so that finalising the money is a supervisor's decision, not the cashier's.

**When it happens.** Once, after the session has been **CLOSED**, by a supervisor (a user allowed to reconcile). Cashiers without that right do not see **Reconcile (Z-read)** in their menu at all.

**How it works.** The supervisor confirms, and the server posts a balanced entry for any non-zero variance (an **over** to cash-over, a **short** to cash-short), then returns the Z-read. If the variance is exactly zero, nothing is posted. **This cannot be undone.**

> In OrbixPOS, **Reconcile (Z-read)** works on the session that is open on this till under the signed-in account — typically a supervisor who worked the till themselves. To reconcile a cashier's session, a supervisor normally uses the **POS Sessions** screen in the ERP.

### Step by step (supervisor)

1. Make sure the session has already been **closed** (its status reads **CLOSED**). Until then the menu row is faded with "Close the session first".
2. Open the **Session menu** and click **Reconcile (Z-read)** ("Post the variance — this is final").
3. Read the **Reconcile session** confirmation: *"This finalises the session and posts the cash variance to the general ledger. This cannot be undone — but the Z-read can be reprinted afterwards, so nothing is lost if the paper jams."*
4. Click **Reconcile** to proceed, or **Cancel** to back out.

The dialog now shows the **Z-read** report:

| Line | What it means |
|---|---|
| **Sales (all tenders)** | Everything taken on the session, with the **By tender** split underneath. |
| **Opening float** | The cash declared at open. |
| **Cash sales** | The cash part of the sales. |
| **Payouts** | Cash paid out, as a negative, with a line per payout type. |
| **Expected** | The expected drawer cash. |
| **Counted** | The cash counted at close. |
| **Variance** | Counted − expected (red if short). |

The footer shows the invoice count for the session.

5. To print the Z-read, click **Print**. Printing a Z-read is a manager's job, and since you are allowed to reconcile tills (a Branch Manager is, by default), you are that manager: it prints straight away, with no approval dialog. (A cashier who prints a Z-read *reprint* is asked for a manager's approval instead — see **Reprinting the Z-read** below.)
6. Click **Finish shift**. The session is RECONCILED and OrbixPOS returns you to the **Open shift** screen, ready for the next shift.

| What you see | What to do |
|---|---|
| **Reconcile (Z-read)** is not in the menu | Your account cannot reconcile. Ask a supervisor. |
| **Session must be CLOSED before reconciliation.** | The session has not been counted yet. Close it first. |
| An error about missing accounts setup | The cash-over/short accounts are not set up on the server, so the reconcile is refused rather than posting an incomplete entry. Ask your administrator to complete the accounts setup, then try again. |

> Once a session is RECONCILED it is final and locked. To keep selling, open a fresh session (a new shift) on the till.

### Reprinting the Z-read

If the Z-read paper jams or you need another copy, use **Z-read (reprint)** in the Session menu ("The final figures for a reconciled session") **before** you click **Finish shift** — once you finish, the till moves on to the next shift. If you are not allowed to view sessions, a manager approves opening the reprint first.

When you click **Print** on the reprint:

- If you are allowed to reconcile tills (a Branch Manager, by default), it prints straight away — no approval dialog.
- If a manager already approved opening this reprint, that approval covers the print too.
- Anyone else — for example a cashier who can view the session but not reconcile it — sees a **Manager approval — Z-read** dialog first ("Print the end-of-shift Z-read for this session."). The approver must be a different person, who is allowed to reconcile tills. After printing you see **Approved by …** with the manager's name.

The reprint shows the note "This is a reprint. The figures are identical to the original — the Z-read is read-only and posts nothing." and prints marked as a reprint. After **Finish shift**, the session's Z-read can still be viewed in the ERP, on the **POS Sessions** screen.

---

## Printing drawer reports

The X-read and Z-read print on the same receipt printer as your receipts, at the paper width set in **Setup & diagnostics**. Things to know:

- **Print** appears only on the Windows till. The web and Android versions cannot print.
- If no printer is set up you see **No receipt printer set — configure one in Setup.** Setup is on the sign-in screen (see Chapter 1).
- Printing a report **never opens the cash drawer**.
- If the printer is off or out of paper, you see a message naming the printer — see the *Troubleshooting* chapter (Chapter 7).

---

## End-of-shift checklist

Work through this every time you finish a shift:

1. **Finish open work.** Complete or clear any sale in progress, and answer any **Unfinished sale** prompt.
2. **Record any last payouts and expenses.** If you removed cash for a safe drop, record it via **Cash payout** (refunds go through **Refund / reverse**, never a payout); if you paid a business cost from the till, record it via **Till expense** — so the expected figure is right.
3. **(Optional) Run an X-read.** **Session menu → X-read** to preview the expected cash before you count.
4. **Count the drawer.** Count all cash, including the opening float. Count twice.
5. **Close the session.** **Session menu → Close session**, enter **Counted cash**, click **Close session**. Note the **Variance** (green = balanced/over, red = short), then **Done**.
6. **Reconcile (supervisor).** A supervisor runs **Reconcile (Z-read) → Reconcile**, prints the Z-read (no approval needed — the supervisor is the manager), and clicks **Finish shift** — or reconciles the session later in the ERP.
7. **Hand over the cash** per your branch's procedure, then **Sign out** (from the **Session menu** or the **Open shift** screen).

> Reprinting a receipt — from **Today's sales** or **Recent receipts** — never creates a new sale, never changes your drawer figures, and never opens the cash drawer. The drawer opens only on the first print of a sale's own receipt — see Chapter 6.

---

# Selling — Supermarket

This chapter walks you through ringing up a sale on the **Supermarket** register: the fast, scanner-first till mode built for grocery and convenience shops. By the end you will be able to add items by scanning, searching, or typing a code; sell by the carton or box as well as by the piece; fix a quantity or apply a line discount (and get it approved when needed); void or remove a line; clear an age check; choose a customer; and finish with **PAY**.

Before you can sell, you must be signed in and have an **open shift** (a cash session on a till). If you have not done that yet, see the *Starting and Ending a Shift* chapter (Chapter 2). Switching register modes is blocked once a sale is in progress, so the till stays in the mode you opened the shift in until the basket is empty.

> **A note that runs through this whole chapter.** Every amount you see on screen *before you take payment* is a **preview** — a helpful estimate that lets you and the customer see what is owed. The prices come from the ERP's own price lists, so the preview normally matches the bill exactly. Even so, the ERP server is the authority for price, VAT, discounts and the final total. When you complete the sale at the payment screen, the server works everything out again and returns the real figures; the printed receipt is built from that finalised sale, not from the preview. If a previewed price ever looks different from the receipt, the receipt is the one that counts.

---

## 1. The supermarket screen at a glance

**What it is.** The supermarket register fills the screen with two areas side by side: a wide **line grid** on the left (a spreadsheet-style list of everything in the basket) and a narrower **number pad** panel on the right.

**Why it exists.** Grocery checkout is high-volume and fast. The grid keeps the whole basket visible at a glance like a till roll, and the number pad lets you fix a quantity or discount without leaving the counter. Most sales need nothing but the scanner and the **PAY** button.

**When you use it.** Every supermarket-style sale, from a single chocolate bar to a full trolley.

**How it is laid out.**

| Area | Where | What it does |
|---|---|---|
| Search / scan bar | Top of the left side | Shows *Scan a barcode or search by code / name…*. Scans land here and you type codes or names here. It stays focused so the scanner can fire straight into it. |
| Line grid | Left, below the search bar | One row per basket line, with the columns **# · CODE · ITEM · UNIT · QTY · PRICE · DISC · TOTAL** and two action columns. |
| Total card | Top-right | The big running total under **TOTAL (TZS)**, the line and item counts, and the reminder *preview — ERP is authoritative*. |
| Customer chip | Right, under the total | Shows who the sale is for. Tap to change. |
| **× Qty** / **− Disc** toggle | Right, mid-panel | Chooses what the number pad will set on the selected line. |
| **Approve discount…** | Right, under the toggle | Appears only when the selected line has a discount. See section 3.5. |
| Number box and keys | Right, lower | The number you are keying, the digits **0–9**, the **.** key, a backspace key, plus **Clear** and **Set qty** / **Set disc**. |
| **PAY** button | Bottom-right, dark blue | Shows the preview total and opens payment for the basket. |

> **Tip.** When the basket is empty the grid shows a shopping-cart icon and the words **Scan or search to add items**. That is your cue that nothing has been added yet.

---

## 2. Adding items

There are several ways to put a product in the basket, and the search bar handles all of them. You do not pick a method — you just scan or type, and the till works out what you meant:

1. If what you entered **looks like a barcode** (only digits, at least 6 of them), the till first asks the ERP for that barcode.
2. Otherwise — or if no barcode matches — the till **searches** the product list on the ERP server. An exact product **code** match is added straight away; a single match is added straight away; several matches open a list for you to choose from.

Products and prices are always read fresh from the ERP, so a price change made in the back office shows at the till straight away.

> **The search bar is always listening.** After every item you add, the field clears itself and the cursor returns to it, ready for the next scan. You should rarely need to click into it.

### 2.1 Scan a barcode

**What it is.** Reading the product's barcode with the handheld scanner.

**How it works.** The scanner behaves like a fast keyboard: it "types" the barcode into the focused search bar and presses Enter for you. You do not press any button — just aim and pull the trigger.

1. Make sure the basket screen is showing (the search bar shows a small scanner icon on the left).
2. Point the scanner at the product's barcode and scan.
3. The item drops into the grid as a new line, any open search list closes, and the search bar clears, ready for the next scan.

If you scan the **same** ordinary product twice, the till does not create two rows — it adds one to the quantity on the existing line.

> **A scan always adds the item you scanned.** Even if a search list is open from something you typed earlier, scanning a barcode closes that list and adds the scanned product — never a row from the old list.

> **Note.** The scanner is a plug-in USB device that types codes into whichever field has focus. If a scan seems to "go nowhere", click once inside the search bar so it has focus, then scan again.

### 2.2 Carton and box barcodes

Some products are sold both by the piece and in a bulk pack — a carton of 24, a box of 12, a bale. If your administrator has set up the pack and given it its own barcode, **scanning the pack's barcode rings one pack**, not one piece. The **UNIT** column then shows the pack's unit (for example **CTN**) in bold blue, so you can see at a glance that the line is a pack.

You can also change a line to a pack by hand — see section 3.4.

### 2.3 Scale labels (weighed items)

**What it is.** Loose fruit, meat, cheese and similar goods are weighed at a scale that prints a barcode label with the **weight** built into it.

**How it works.** Scan the scale label exactly as you would any barcode. The item is added as its **own** line with the weighed quantity already filled in (for example 1.250 kg). It is never merged into another line, because each weighed pack is different.

> **Price labels are not accepted.** Some scales print a label with the **price** built in instead of the weight. The till refuses these and shows *Price-embedded labels aren't supported yet — enter [product] manually.* Nothing is added. Search for the product by name or code instead and key the quantity yourself (section 3.3).

> **Tip.** If a scale label is wrong, weigh the item again and scan the new label, then remove the old line (see section 3.7).

### 2.4 Search by code or name

**What it is.** Finding a product by typing part of its code or name — useful when there is no barcode, the barcode will not read, or you are not sure of the exact item.

**How it works.** As you type, a list of matches drops down under the search bar. Each row shows:

| On the row | What it means |
|---|---|
| Code | The product code, on the left. |
| Name | The product name. |
| Price | The selling price for one base unit (one piece, one kg …), **including VAT**. *no price* (in orange) means the product has no price set — tell your supervisor before you sell it. |
| Stock | *N available* or *N in stock* for this branch, or **Out of stock** in red. |
| Age badge | **18+** or **21+** in orange if the item is age-restricted. |

1. Start typing part of the product **code** or **name**, for example `milk` or `BR-0123`.
2. The list appears with the first row highlighted.
3. Pick the product: click its row, **or** use the **↑** and **↓** arrow keys to move the highlight and press **Enter**.
4. The product is added to the basket, the list closes and the search bar clears.

If you type and press **Enter** before the list has appeared, the till adds the product straight away when your text is an exact product code or matches only one product. If nothing matches, you see *No match for "…"*.

### 2.5 Type an exact code

**What it is.** Keying in a product's exact code and pressing Enter — the fastest add when you know the code.

1. Click into the search bar.
2. Type the exact product code.
3. Press **Enter** (or tap the blue **+** button at the right end of the bar). The item is added.

> **What the + button does.** Tapping **+** sends whatever is in the search bar through the same barcode → search steps described at the start of this section. If the search list is already showing for what you typed, **Enter** adds the highlighted row instead.

---

## 3. Working with lines in the grid

Each row in the grid is one **line** — one product, in one unit, with a quantity, a price preview, an optional discount, and a line total.

### 3.1 Reading a line

| Column | What it shows |
|---|---|
| **#** | The line number (1, 2, 3 …). |
| **CODE** | The product code. |
| **ITEM** | The product name, with an orange age badge (**18+** / **21+**) if it is restricted, and a red tag such as *only 5 left* or *out of stock* if the branch does not hold enough for this line. |
| **UNIT** | The unit the line is sold in (for example **PCS**, **KG**, or a pack such as **CTN** in bold blue). Tap it to change the unit. |
| **QTY** | How many. Shows three decimals for items sold by weight; whole numbers otherwise. |
| **PRICE** | The previewed price for one unit, including VAT. A dash (—) means the price is still loading or none was found. |
| **DISC** | The line discount amount. A middle dot (·) means no discount. A small green shield beside the amount means a manager has approved it. |
| **TOTAL** | The previewed line total (quantity × price, minus any discount). |
| (blank) | The **remove** column — a small **×**. |
| **V** | The **void** column — a tick box. |

> **The red stock tag is a warning, not a block.** It tells you before payment that the branch may not hold enough. Whether the sale is allowed is decided by the ERP when you complete it (your company may or may not allow selling into negative stock). For a pack line the tag counts in pieces — for example *only 30 PCS left* against 2 cartons of 24.

### 3.2 Selecting a line

**What it is.** Picking the line you want to work on. The selected line is highlighted, and the number pad always acts on it.

**How it works.** Tap anywhere on a line to select it. To edit a quantity or discount, tap directly on that line's **QTY** or **DISC** cell — this selects the line *and* points the number pad at that field (the **× Qty** / **− Disc** toggle flips to match, and the cell shows a soft highlight).

### 3.3 Changing the quantity

**What it is.** Setting how many of an item the customer is buying.

**How it works.** You key the new quantity and apply it — this *replaces* the quantity (it does not add to it). You can use the on-screen keys **or** the keyboard.

1. Tap the line's **QTY** cell. The toggle on the right now reads **× Qty**.
2. Key the quantity — on the on-screen keys, or by typing on the keyboard (the number box is ready for typing as soon as you tap the cell). Use **.** for a decimal, for example `1.250` for a weighed item.
3. Press **Set qty** (or **Enter** on the keyboard). The line's **QTY** and **TOTAL** update, and the cursor goes back to the search bar for the next scan.

> **Note.** A quantity of zero or less is not allowed — the till sets it to 1 instead. To take the item off the sale entirely, remove or void the line (section 3.7).

### 3.4 Selling by the pack (changing the unit)

**What it is.** Switching a line between the single piece and a bulk pack — for example from **PCS** to **Carton (×24)**.

**Why it exists.** A customer buying a whole carton should be charged the carton price, and the stock should go down by 24 pieces, without you keying 24.

1. Tap the line's **UNIT** cell (it has a small drop-down arrow).
2. A dialog headed **Sell by** opens, with the product name under it. It lists the base unit (marked *base unit*) and every pack set up for the product, for example **Carton (×24)** — *CTN · 24 per pack*. A tick marks the unit the line is in now.
3. Tap the unit you want. The dialog closes, the **UNIT** cell changes, and the price is looked up again for that unit (a dash shows for a moment).

> **The quantity is not converted.** If the line said **2** and you switch it to Carton, it now means **2 cartons**. Check the **QTY** after changing the unit.

If the product has no pack set up, you see *[product] is only sold in [unit].* and nothing changes. If the same product is already in the basket in the unit you picked, the two lines are joined into one.

### 3.5 Applying a line discount

**What it is.** Taking a money amount off a single line — for example knocking TZS 500 off a slightly damaged pack.

**How it works.** The discount is a **money amount for the whole line**, not a percentage. You key the amount and press **Set disc**; the line total drops by that amount.

1. Tap the line's **DISC** cell (or tap the line and flip the toggle to **− Disc**).
2. Key the discount amount on the on-screen keys or the keyboard.
3. Press **Set disc** (or **Enter**). The **DISC** column shows the amount in blue and the **TOTAL** drops.

To remove a discount, set it back to `0` and press **Set disc** again.

> **Warning.** A discount can never make a line total go below zero on screen. The ERP has the final say on whether a discount is allowed.

#### When a discount needs a manager

Your company can set a limit on how much discount a cashier may give alone. (By default there is no limit; your administrator switches it on in the ERP.) The till does not know the limit — the ERP checks it when you complete the sale. If a discount is over the limit, the payment screen refuses the sale, says nothing was charged, and offers **Get manager approval for the discount** (see the *Taking Payment* chapter, Chapter 5).

If you already know a discount will need approval and the manager is standing next to you, you can get it before you go to payment:

1. Select the discounted line. An **Approve discount…** button appears under the **× Qty** / **− Disc** toggle.
2. Tap **Approve discount…**. A **Manager approval — discount** box opens, naming the product and the discount.
3. The manager types their **Manager username** and **Manager password** and presses **Approve**. You stay signed in throughout.
4. The button is replaced by a green note *Discount approved by [name]*, and a small green shield appears in the line's **DISC** cell.

> **Changing the discount cancels the approval.** If you edit the discount after it was approved, the approval is dropped and must be given again for the new amount. The manager must be a different person from the cashier and must hold the discount-override right (see the *For Supervisors and Store Managers* chapter, Chapter 8).

### 3.6 Clearing the number box

If you mistype a number before pressing **Set qty** / **Set disc**, press **Clear** to empty the number box, or use the backspace key (bottom-right of the digits, or the Backspace key on the keyboard) to delete one digit at a time.

### 3.7 Void versus remove — two different columns

These two action columns look similar but do different things.

| Column | Icon | What it does | Use it when |
|---|---|---|---|
| **Remove** (blank header) | **×** | Deletes the line completely — it disappears from the grid. | You added the wrong item, or you want it gone entirely. |
| **Void** (**V** header) | tick box | Marks the line **voided** — it stays visible, struck through and greyed out, but is left out of the total and out of the sale sent to the ERP. | The customer changed their mind but you want the line kept on screen, or you may bring it back. |

1. To **remove** a line, tap the **×** in the last-but-one column. It vanishes.
2. To **void** a line, tap the tick box in the **V** column. The line greys out with a line through it and stops counting toward the total. Tap the box again to bring it back.

> **Tip.** A voided line is *not* charged. Voiding a selected line also un-selects it, so the number pad cannot change a line that is not part of the sale.

---

## 4. Age-restricted items

**What it is.** Some products — alcohol, tobacco and similar — carry a legal minimum age. On the till these show an orange badge (**18+** or **21+**) next to the product name, both in the search list and on the basket line. Whether a product is age-restricted is set on the product in the ERP; many shops have none.

**Why it exists.** It is your responsibility, and the law's requirement, to confirm the customer is old enough before selling these items.

**When it happens.** The badge only tells you an item is restricted. The **check itself appears later**, on the **Payment** screen, when you press **Complete sale** (not when you press **PAY**). If the basket contains at least one restricted item, a box headed **Age-restricted items** appears before the sale can finish.

**How it works.** The box says, for example, *This basket contains 18+ items. Confirm you have verified the customer meets the minimum age.* Check the customer's ID, then press **Age verified** to continue, or **Cancel** to stop the sale (you see *Sale stopped: age not verified.*).

> **Where the check is fully explained.** The step-by-step is in the *Taking Payment* chapter (Chapter 5). Only press **Age verified** when you have actually seen acceptable proof of age — the confirmation is recorded with the sale.

---

## 5. Choosing the customer

**What it is.** The **Customer** chip, under the total card on the right, names who the sale is for. Every sale must have a customer. When your company has a walk-in (cash) customer set up in the ERP, the till starts every sale with it, and the chip shows its name (for example *Walk-in Customer*) — exactly right for most supermarket sales.

**Why it exists.** Some customers are registered (account holders, regulars, businesses). Attaching a registered customer ties the sale to their record. For a casual shopper, the walk-in customer is all you need.

**When you change it.** Only when the customer is a registered account you want this sale linked to. If in doubt, leave it on the walk-in customer.

**How it works.**

1. Tap the **Customer** chip on the right.
2. The **Customer** picker opens with a search box reading *Search name / code / phone…*.
3. Type part of the customer's **name**, **code**, or **phone number**. The list updates as you type.
4. Tap the customer you want. The picker closes and the chip shows their name. A tick marks the currently selected customer in the list. If the basket already has items, the till first re-reads their prices **for this customer** — an account customer may have their own price list or contract prices — so the total you read out is the one they will be charged.

To go back to an anonymous sale, open the picker again and choose the walk-in entry (shown with a "walking person" icon and *· Walk-in* after its code).

> **If the chip says "Select customer".** No walk-in customer could be found for your company (or your account cannot read the customer list). When you press **PAY** the till shows *Select a customer before completing the sale.* and opens the picker for you. Choose a customer to carry on. Ask your administrator to set up a walk-in customer so this does not happen on every sale.

> **Tip.** You can set the customer before or after adding items — the items stay, and their prices are updated for the customer you choose.

> **Every new sale starts on the walk-in customer.** After a sale is paid, the next basket goes back to the walk-in customer automatically, so a named customer is never carried onto the next shopper's sale.

---

## 6. Reading the running total

**What it is.** The dark **total card** at the top of the right-hand panel shows the live state of the basket: **TOTAL (TZS)** with the big figure under it, then a line such as *3 lines · 7 items*.

**Why it exists.** It is the at-a-glance figure you read out to the customer and watch climb as you scan.

**How it works.** The total updates instantly every time you add, void, remove, re-quantity, discount or change the unit of a line. Voided lines do not count. The prices already include VAT — whether your price list is entered with VAT included or without, the till shows the VAT-inclusive price, the same way the receipt will. Underneath you always see the small reminder **preview — ERP is authoritative**.

> **Remember.** This total is a preview. The amount the customer actually pays is the one the ERP returns when you complete the sale, and that finalised figure prints on the receipt.

---

## 6a. Putting a sale on hold — Hold and Recall

**What it is.** **Hold** (under the number pad, beside **Recall**) puts the whole basket aside so you can serve the next customer — for example when a shopper goes back for an item or to fetch money. **Recall** brings it back.

**How it works.**

1. With items in the basket, press **Hold**. You see *Sale on hold. Use Recall when the customer is back.* and the basket empties for the next customer. The **Recall** key shows how many sales are waiting, for example **Recall (2)**.
2. When the customer returns, finish or hold the sale you are on, then press **Recall**. The **Sales on hold** list shows each basket's first item, the customer, the time it was held and its total. Tap the one you want.
3. The items and customer come back into the basket and the prices are read again from the ERP (they may have changed while the basket waited). Take payment as usual.

> Held sales are kept **on this till only**, for **this shift**, and for at most a day. They are not sales yet: nothing is charged, no stock moves and nothing reaches the ERP until you recall the basket and take payment. A line discount comes back with the basket, but a manager's approval for it does not — ask again if the till needs one.

---

## 7. Finishing the sale — Pay

When everything is in the basket, you take payment.

1. Press the dark blue **PAY** button at the bottom-right (it shows the preview total). It is disabled while the basket is empty or every line is voided.
2. If no customer is set, the till asks you to pick one first (section 5).
3. The **Payment** screen opens. This is where you choose how the customer pays — **Cash**, **Mobile** (mobile money), **Cheque**, **Card**, or a split across several — and press **Complete sale**.
4. If the basket has any age-restricted items, the **Age-restricted items** check (section 4) appears here before the sale can complete.
5. When the sale completes, the basket clears and the **Sale complete** receipt opens so you can print it.

Taking payment, change, split tenders, and what to do when the outcome of a sale is unclear are covered in the *Taking Payment* chapter (Chapter 5). The receipt, reprints and refunds are in the *Receipts and Refunds* chapter (Chapter 6).

> **Note.** Pressing **PAY** does not charge anything — it only opens the payment screen. The sale is made only when you complete it there. If you close the payment screen without completing, the basket is still on screen, unchanged.

---

## 8. Quick troubleshooting

| What you see | What to do |
|---|---|
| The basket shows **Scan or search to add items** and nothing happens when you scan. | The search bar may not have focus. Click once inside it, then scan again. |
| A scan or search shows **No match for "…"**. | The code is not in this company's product list, or the barcode did not read cleanly. Try searching by name, or type the exact product code and press Enter. |
| *Price-embedded labels aren't supported yet — enter … manually.* | The scale label carries a price, not a weight. Search for the product and key the quantity instead. |
| A search row says *no price*, or a line's **PRICE** stays a dash (—). | The product has no selling price in the ERP for this unit. Do not sell it until your supervisor or administrator sets a price — the ERP may refuse the sale. |
| You typed a number and nothing changed on the line. | Press **Set qty** / **Set disc** (or Enter) to apply it. If you see *Select a line first.*, tap the line, then try again. |
| A line is greyed out with a line through it. | It is **voided** — it will not be charged. Tap its **V** tick box to bring it back, or its **×** to remove it for good. |
| The same product scanned twice but only one row appears. | That is correct for ordinary items — the quantity went up by one on the existing row. Check the **QTY** column. |
| A carton was rung as one piece (or the other way round). | Tap the line's **UNIT** cell and pick the right unit in **Sell by**. Then check the quantity — it is not converted. |
| *… is only sold in …* when you tap the unit. | The product has no pack set up. Ask your administrator to add the pack unit to the product in the ERP. |
| A red *only N left* or *out of stock* tag on a line. | The branch may not hold enough. Check the shelf; the ERP decides at payment whether the sale can go through. |
| **PAY** is greyed out. | The basket is empty (or every line is voided). Add at least one item. |
| *Select a customer before completing the sale.* | Choose a customer in the picker that opens (the walk-in entry is fine for a cash sale). |
| Adding an item shows *"…: no usable unit configured."* | The product has no sellable unit set up. Ask your supervisor or administrator to fix the product's unit of measure; it cannot be sold until then. |
| The age-check box blocked the sale. | Verify the customer's age from photo ID. Press **Age verified** to continue, or **Cancel** and remove the restricted item. |
| The payment screen refused the sale because of a discount. | Press **Get manager approval for the discount** and have a manager approve it, or reduce the discount. |

> **If a sale is refused with a message about a sales agent.** Every sale is credited to you, the person signed in; if you have no sales-agent record yet, one is created for you on your first sale. A refusal means your sales agent was archived (ask your administrator to reactivate it) or you are signed in with an account that cannot sell, such as the top-level super-admin. Use your own cashier login.

---

# Selling — Pharmacy and Restaurant

This chapter covers OrbixPOS's two other tills: the **Pharmacy** register (a dispensing counter with a patient and prescription header) and the **Restaurant** register (table service, with a table picker, a menu grid and an order ticket). It also covers how to **switch between modes** — and the guard that stops you switching in the middle of a sale.

If you work a supermarket checkout, see the previous chapter, *Selling — Supermarket* (Chapter 3). The way you take payment, print the receipt, and open and close your shift is **the same in all three modes** — only the part of the screen where you build the basket changes.

> **Read this first — these two modes are simpler than the supermarket till.** The Pharmacy and Restaurant registers ring real sales: payment, receipts, stock and the cash drawer work exactly as in the supermarket. But they have fewer tools. Neither has a number pad, line discounts or the void tick box, and neither does anything a dedicated pharmacy or restaurant system would do on top of selling (no prescription records, no kitchen printer, no saved table orders). The limits are listed at the end of each section so you are never surprised. If your shop is a supermarket, use the **Supermarket** register.

> **Before you read on.** A "register" (also called a "till mode") is the central selling screen. OrbixPOS has three of them. You pick one when you open your shift. Nothing in this chapter changes prices, tax or totals: the on-screen total is always a **preview**, and the ERP server works out the real figures for the receipt.

---

## 1. Switching modes

**What it is.** The mode switcher is the pill-shaped control in the centre of the top bar. It shows all three registers side by side — **🛒 Supermarket**, **💊 Pharmacy** and **🍽 Restaurant** — with the one you are using highlighted in a dark pill.

**Why it exists.** One device can serve more than one counter. Rather than installing three apps, you switch the register in place. The shift, the till, the open cash session and your sign-in all stay exactly as they are — only the selling screen changes.

**When it happens.** You normally pick your mode once, on the **Open shift** screen, by tapping one of the three **Business mode** cards before you press **Open session** (see the *Starting and Ending a Shift* chapter, Chapter 2). You only use the top-bar switcher if you genuinely need to change counter mid-shift.

**How it works.** Tap the mode you want in the switcher pill. The selling area below the top bar redraws into that register immediately. There is no reload and no re-login.

### 1.1 The mid-sale guard

> **You cannot switch modes while a sale is in progress.** If the current basket has any items on it and you tap a different mode, OrbixPOS shows **"Finish or clear the current sale before switching mode."** and the mode does not change.

| What you see | What to do |
|---|---|
| **"Finish or clear the current sale before switching mode."** | The basket has items on it. Either take payment for the current sale (**PAY**), or remove every line first, then tap the mode again. |
| You tap your current mode and nothing happens | That is normal — you are already in that mode. |

To clear an unwanted sale, remove each line with the **✕** on every line. Once the basket is empty, the switcher lets you change mode.

---

## 2. The Pharmacy register

The Pharmacy register is for a dispensing counter. A **patient and prescription header** runs across the top, a **search box** sits below it, and a **dispensing line table** fills the main area. A summary panel with the **PAY** button is fixed down the right-hand side.

> **What "dispensing" means here.** Dispensing is simply ringing up the medicines and goods a customer is collecting. OrbixPOS records *what* was sold and *to whom*; it does not replace a pharmacist's checks, and it keeps no prescription register. The patient, prescriber and prescription number are saved with the sale as a note — they do **not** change the price.

### 2.1 The patient / prescription header

**What it is.** The bar across the top, with three parts left to right: a **Patient** tile, a **Prescriber** box, and an **Rx #** box.

**When you fill it in.** At the start of the sale, before or while you add the medicines. Leave any part blank if you do not have it (for an over-the-counter sale, leave **Prescriber** and **Rx #** empty).

**How it works.** When you press **PAY**, OrbixPOS writes one note onto the sale in the form `Patient: <name> | Prescriber: <name> | Rx: <number>`, using only the parts you filled in. The note is kept with the sale in the ERP, where the back office can look it up. **It is not printed on the receipt.**

#### Set the patient

1. Tap the **Patient** tile.
2. The **Customer** picker opens. Type a name, code or phone number in the **Search name / code / phone…** box.
3. Tap the patient in the list. A tick marks the one currently selected.
4. The picker closes and the **Patient** tile shows the chosen name.

> **Walk-in is fine.** If the customer is not registered, leave the tile on **Walk-in**. A walk-in patient is not written into the note; only a named, registered patient is.

> **If you are asked to select a customer at PAY.** The **Patient** tile reads **Walk-in** even when the till could not find your company's walk-in customer. In that case, pressing **PAY** shows *Select a customer before completing the sale.* and opens the picker — choose the walk-in entry or the patient to carry on.

#### Type the prescriber and Rx number

1. Click the **Prescriber** box and type the prescriber's name.
2. Click the **Rx #** box and type the prescription number from the paper script.

Both are free text.

> **The header clears when you leave the payment screen.** As soon as the payment screen closes, OrbixPOS empties **Prescriber** and **Rx #** and puts the cursor back in the search box. This happens **even if you closed the payment screen without completing the sale** — if you go back to finish the sale, type the prescriber and Rx number again before you press **PAY**.

### 2.2 Adding medicines to the sale

**What it is.** The **Scan or search a drug…** box, under the header, is how every line gets onto the table.

1. Make sure the cursor is in the **Scan or search a drug…** box (click it if not).
2. **Scan** the pack's barcode, **or** type the product code or part of the name and press **Enter**.
3. The medicine appears as a new line with quantity **1**. The box clears itself, ready for the next item.

OrbixPOS finds your item like this:

| What you scan or type | What happens |
|---|---|
| A barcode | The product is added. A box or carton barcode set up for the product rings that pack. A scale label with a weight sets the quantity. |
| A scale label with a **price** in it | Refused: *Price-embedded labels aren't supported yet — enter [product] manually.* |
| An exact product code | That product is added. |
| Part of a name or code | The **first** match is added — there is no list to choose from. |
| Something with no match | *No match for "…"*. Nothing is added. |

> **Check what was added when you search by name.** Unlike the supermarket till, the pharmacy box does not show a list of matches — it adds the first one it finds. Typing `para` may add a different paracetamol from the one you meant. Look at the new line, and if it is wrong, remove it (**✕**) and type more of the name, or scan or type the exact code.

### 2.3 The dispensing line table

Each row is one medicine on the sale. From left to right a row shows: the **name** (with the product code and the unit underneath), a **quantity stepper**, the **line amount**, and a **✕** to remove the line. Until you add anything, the table shows a medicine icon and **Scan or search to dispense**.

Small warnings on a line:

- An **amber triangle** next to the name means the item is age-restricted. You will be asked to confirm the customer's age when you press **Complete sale** (see the *Taking Payment* chapter, Chapter 5).
- A red tag such as *only 5 left* or *out of stock* means the branch may not hold enough. The ERP decides at payment whether the sale can go through.

#### Change a quantity

1. Tap **–** to lower the quantity by one (it never goes below 1; to take the item off, use **✕**).
2. Tap **+** to raise it by one.

There is no number pad in this mode, so for a large quantity tap **+** repeatedly — or sell by the box (next).

#### Sell by the box or strip

Tap the code-and-unit line under the medicine's name (it has a small drop-down arrow). The **Sell by** dialog opens and lists the base unit and any pack set up for the product, for example **Box (×20)**. Tap the one you want. The quantity is **not** converted — "2" switched to Box means 2 boxes. If the product has no pack, you see *[product] is only sold in [unit].*

#### Remove a line

Tap the **✕** at the right-hand end of the line. To abandon the whole sale, remove each line in turn.

### 2.4 The running total and PAY

The panel on the right lists **Items** and **Lines**, then **Total (TZS)**, the reminder **preview — ERP is authoritative**, and the **PAY** button showing the preview amount.

1. Check the lines and the **Total** with the customer.
2. Tap **PAY**. (It is disabled while the table is empty.)
3. The payment screen opens. Choose how the customer is paying and complete the sale — see the *Taking Payment* chapter (Chapter 5).

When the sale is complete, the table clears and the cursor returns to the search box for the next patient.

### 2.5 What the Pharmacy register does not do

- No list of search matches — a name search adds the first match (section 2.2).
- No line discounts, no void tick box, no number pad and no typing a quantity; quantities change one at a time with **–** / **+**.
- No prescription register, no batch or expiry selection, no dosage labels.
- The patient, prescriber and Rx number are saved as a note on the sale but are not printed on the receipt.

---

## 3. The Restaurant register

The Restaurant register is for table service. On the **left** are a **Pick a table** button, a **Search the menu…** box, and a grid of **item tiles**; on the **right** is the **order ticket** with a **Send to kitchen** button and the **PAY** button at the bottom.

> **How it differs.** Here you build the order by **tapping tiles**, not by scanning. As in the other modes, the total is only a preview until you take payment.

> **One open order at a time.** The ticket on screen is the only order the till holds. There is no way to park Table 3's order, start Table 5's, and come back — an order lives only until it is paid or cleared. If the till restarts, the ticket is lost. Most restaurants using this mode take payment as each order is placed (a counter or quick-service style), or keep table orders on paper and ring each bill when the table pays.

### 3.1 Pick a table

**What it is.** The **Pick a table** button at the top-left. Once you choose, it shows **Table N**, and the order ticket on the right is titled with the same table.

**How it works.** Tapping **Pick a table** opens the **Choose a table** dialog: a grid of tables numbered **1** to **12**, each labelled *seats 4*. The floor is fixed — it is the same 12 tables in every shop and cannot be changed. Tap a table to select it. The table number is saved with the sale as a note (for example `Table 5`). **It is not printed on the receipt.**

1. Tap **Pick a table** (top-left).
2. In the **Choose a table** dialog, tap the table number.
3. The dialog closes. The button now reads **Table N**, and the order ticket is titled **Table N**.

To change tables, tap the button again and pick a different one.

> **The table resets when you leave the payment screen.** When the payment screen closes, the button goes back to **Pick a table** — even if you closed it without completing the sale. If you go back to finish that order, pick the table again so the screen shows it.

### 3.2 Browse and search the menu

**What it is.** The grid of tiles, one per item, each showing a dish icon, the item name and its code. Tiles do not show prices — the price appears on the order ticket once you add the item.

**How it works.** The grid shows the first part of your company's **whole product list** (up to 120 items) — there is no separate menu, so everything the company sells can appear here. To find an item, type part of its name or code in **Search the menu…**; the grid updates as you type. Clear the box to see the starting grid again. If nothing matches, the grid shows **No menu items**.

### 3.3 Build the order ticket

**What it is.** The panel on the right, titled **Order ticket** (or **Table N** once a table is picked). It lists each item ordered, with a quantity stepper, the line amount and a **✕** to remove it. Until you add anything it reads **Tap menu items to build the order**.

> **About the count in the ticket header.** The figure such as *3 items* counts **lines on the ticket**, not portions. Two lines of 4 sodas and 1 burger read *2 items*.

- **Add an item:** tap its tile. It appears on the ticket with quantity **1**. Tapping the same tile again adds one more to that line.
- **Change a quantity:** use **–** / **+** on the ticket line (never below 1).
- **Remove an item:** tap **✕** at the end of the ticket line. To abandon the order, remove every line — this also frees the mode switcher.

Items are always sold in their base unit in this mode; there is no carton or pack choice.

### 3.4 Send to kitchen

**What it is.** The **Send to kitchen** button, just above the total. It appears once the ticket has at least one item.

**How it works.** Tap **Send to kitchen**. A green confirmation **Sent to kitchen.** appears, and the button changes to **Sent to kitchen ✓**. If you add another item afterwards, the button goes back to **Send to kitchen** so you can confirm the updated order.

> **Important — Send to kitchen is a screen tick only.** It does **not** print to a kitchen printer, send to a kitchen screen, or record anything in the ERP. It does not take payment. Pass the order to the kitchen by your usual means (a paper docket, or calling it through).

### 3.5 The total and PAY

The footer of the ticket shows **Total (TZS)** and the **PAY** button, which shows the preview amount.

1. Confirm the order and the **Total** with the customer.
2. Tap **PAY** (disabled while the ticket is empty).
3. The payment screen opens. Choose the tender(s) and complete the sale — see the *Taking Payment* chapter (Chapter 5).

When the sale completes, the ticket clears and the table resets, ready for the next order.

### 3.6 What the Restaurant register does not do

- Holds only one open order; orders cannot be parked per table, split, or transferred.
- The floor is a fixed 12 tables; table names and seat counts cannot be set.
- The "menu" is the general product list; there are no menu categories, modifiers or prices on the tiles.
- **Send to kitchen** is a screen tick only — nothing reaches the kitchen.
- No line discounts, no void tick box, no pack units.
- The table number is saved as a note on the sale but is not printed on the receipt.

---

## 4. Quick reference

| Task | Pharmacy | Restaurant |
|---|---|---|
| Add an item | Scan, or type in **Scan or search a drug…** and press **Enter** (adds the first match) | Tap a tile in the menu grid |
| Find an item | Type a code or name; check the line that was added | Type in **Search the menu…**; the grid updates |
| Set who/where the sale is for | Tap the **Patient** tile (customer picker); type **Prescriber** and **Rx #** | Tap **Pick a table**, choose 1–12 |
| Change a quantity | **–** / **+** on the line | **–** / **+** on the ticket line |
| Sell by box/pack | Tap the unit under the name → **Sell by** | Not available |
| Remove a line | **✕** at the end of the line | **✕** at the end of the ticket line |
| Fire the order to the kitchen | — | **Send to kitchen** (screen tick only) |
| Take payment | **PAY** (right-hand panel) | **PAY** (bottom of the ticket) |
| Switch register | Top-bar switcher — only when the sale is empty | Top-bar switcher — only when the ticket is empty |

> **One more reminder.** The patient/prescriber/Rx details and the table number are saved as a **note** on the sale for the back office. They are not printed on the receipt and they do not change the price. The ERP server is always the authority on price, VAT and totals.

---

*Next: see the *Taking Payment* chapter (Chapter 5) for tenders, split payment, change, age confirmation and the receipt; and the *Starting and Ending a Shift* chapter (Chapter 2) for opening, the session menu, closing and reconciling the drawer.*

---

# Taking Payment

This chapter covers the moment of truth at the till: turning a basket of items into money in the drawer (or on the card terminal) and a receipt in the customer's hand. It explains the **Payment** screen, the four ways a customer can pay, how to take a simple cash sale, how to split one sale across several payments, and — importantly — what to do when you are not sure whether a payment went through.

Everything here works the same way in all three OrbixPOS modes — Supermarket, Pharmacy and Restaurant. The register differs, but the **Payment** screen is identical, because all three share one payment, receipt and session flow.

> Before you can take payment you must be signed in and have an **open** shift (an open session on a till). If you have not opened a shift yet, see the *Starting and Ending a Shift* chapter (Chapter 2). The **PAY** button is greyed out while the basket is empty.

---

## Opening the Payment screen

**What it is.** The **Payment** screen is a single panel that appears on top of your register when you are ready to collect money. On the left it shows the amount the customer owes and a running list of what they have paid so far; on the right it shows the amount box, a number pad and the buttons that finish the sale.

**Why it exists.** Ringing items and taking money are two separate steps on purpose. You build the basket first, check it with the customer, and only then move money.

**When it happens.** Once every item is in the basket and the customer is ready to pay.

**How it works.** When you press the **PAY** button on the register (it also shows the basket total), OrbixPOS makes two quick checks and then opens the **Payment** screen with the basket total as the amount due. Nothing is sent to the server yet. The sale is only created when you press **Complete sale**.

To open it:

1. Finish adding items to the basket on the register.
2. Read the total back to the customer.
3. Press **PAY** (bottom-right of the register).

Before the **Payment** screen opens, the till may stop you with one of these:

| What appears | Why | What to do |
|---|---|---|
| An **Unfinished sale** dialog | An earlier sale on this till was interrupted before the till knew whether it went through. It must be settled first, so the same customer is never charged twice. | Press **Check sale** — see **An unfinished sale from before** below. |
| **Select a customer before completing the sale.** and the **Customer** list | Every sale needs a customer, and this basket has none (usually because your company's walk-in customer could not be found). | Pick the customer — usually the walk-in / cash customer — from the list. You can search by name, code or phone. If you close the list without choosing, the Payment screen does not open. |

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
| **Total / Paid / Change** | A short summary at the bottom: **Total** (the amount due), **Paid** (the payments you have added so far), and **Change** (shown only when the customer has handed over more than the total). |

### The right half — the amount box and number pad

| Control | What it does |
| --- | --- |
| Amount box | A dark box at the top where the amount you are entering appears. It shows a faint **0** until you enter something. The cursor is already in it, so you can **type the amount on the keyboard** — digits and one decimal point, no commas (type `10000`, not `10,000`). Pressing **Enter** here is the same as pressing **Complete sale**. |
| Quick-cash buttons | A grid of suggested amounts: **Exact** (the exact total) and the total rounded up to common notes — for a total of 12,500 the buttons are **Exact**, **13,000**, **14,000**, **15,000** and **20,000**. One tap fills the amount box. |
| Number pad | Keys **0**–**9** for typing an amount by touch or mouse. **C** clears the amount box; the backspace key (⌫) deletes the last digit. The pad and the keyboard edit the same box. |
| **Add tender** | Records the amount in the box as one payment of the selected type, and adds it to the tender list. If the box is empty, it adds the **remaining balance** as that type. Use this for split payments (see below). |
| **Complete sale** | The big green button (it also shows the total) that finishes the sale, sends it to the server, and shows the receipt. |

> The amount you enter, the quick-cash buttons, and the **Change** figure are a **preview** to help you and the customer. The real price, tax and totals are calculated by the ERP server when you press **Complete sale**, and the receipt is built from what the server sends back. If a server figure ever differs from the on-screen preview, the server figure is the correct one.

> **If a line has no price yet,** the on-screen total cannot be known, so the till shows no **Change** figure and lets the server price the sale. If the product truly has no price, the server refuses the sale and tells you why.

---

## The four tender types

**What a tender is.** A *tender* is one way of paying — the form the money takes. OrbixPOS accepts four:

| Tender | Button label | What it means |
| --- | --- | --- |
| Cash | **Cash** | Notes and coins handed across the counter, paid into the drawer. |
| Mobile money | **Mobile** | A mobile-money transfer (for example M-Pesa, Tigo Pesa or Airtel Money), confirmed on the customer's or the shop's phone. |
| Cheque | **Cheque** | A bank cheque. |
| Card | **Card** | A debit or credit card, processed on your separate card terminal. |

**Why there are four.** Customers do not all pay the same way, and one customer may use more than one method for a single basket. Recording the method per payment means the receipt and the books show exactly how the money arrived — and only **Cash** counts towards the cash expected in your drawer.

**How it works.** Click a tender-type button to select it; it highlights. Whatever you do next (enter an amount, press **Add tender**, or press **Complete sale**) applies to that type. **Cash** is selected when the screen opens.

> **Card data is never typed into OrbixPOS.** When the customer pays by **Card**, you run the card on your external card terminal (the physical card machine beside the till). OrbixPOS only records *that* a card payment of a given amount was taken — it never asks for, stores, or shows a card number, PIN, or CVV.

> **Confirm mobile money before you complete.** OrbixPOS does not send a payment request to the customer's phone. Wait until the transfer is confirmed on the phone, then record it as **Mobile**.

---

## Task: a simple cash sale

This is the everyday case — the customer pays the whole basket in cash.

1. With the basket ready, press **PAY** on the register. The **Payment** screen opens with **Cash** already selected and **AMOUNT DUE** showing the total.
2. Take the cash from the customer.
3. Enter how much cash they gave you, in one of these ways:
   - press a **quick-cash** button that matches (for example **Exact** if they paid the exact total, or **20,000** if they handed over a 20,000 note); **or**
   - type the amount on the keyboard or the number pad.
4. Check the **Change** figure at the bottom-left. If the customer gave you more than the total, OrbixPOS shows the change you owe them. If they paid the exact amount, no change is shown.
5. Press **Complete sale** (or press **Enter**).
6. Hand over the change shown, then give the customer their receipt (see **After payment** below).

> For an exact-cash sale you do not have to enter anything at all — just press **Complete sale** and the till settles the basket in cash for its exact total. Entering the cash given is only needed so the till can show you the **Change**.

> If you enter **less** than the total and press **Complete sale**, the till stops and says *"Tendered … is less than the total …. Use Add tender to split, or key the full amount."* Enter the full amount, or take the rest by another method (see split payments).

**What happens behind the scenes.** When you press **Complete sale**, OrbixPOS sends the basket and the payment to the ERP server. The server prices every line, adds tax, finalises the sale, and returns a finished invoice. OrbixPOS clears the basket, saves the receipt on this device, and shows it to you.

---

## Task: a single non-cash payment

If the customer pays the whole basket by **Card**, **Mobile** or **Cheque**:

1. Press **PAY**.
2. Click the tender type (for example **Mobile**).
3. Take the payment on the card terminal or confirm it on the phone, for the exact total.
4. Press **Complete sale**. With nothing entered, the whole total is recorded under the type you selected — it is never silently recorded as cash.

If you do enter an amount, it must be the full total; for part-payments use **Add tender** (next section).

---

## Task: split or multi-tender payment

**What it is.** A *split* (or *multi-tender*) payment is one sale paid with two or more payments — for example part on **Card** and the rest in **Cash**, or a mobile-money transfer plus some cash.

**Why it exists.** Customers do not always have enough of one tender. Splitting lets a single basket be settled however the customer can actually pay, while still producing one sale and one receipt.

**How it works.** You add each payment one at a time with **Add tender**. The **Paid** figure grows as you add tenders; you keep adding until **Paid** reaches (or passes) the **Total**. Then you press **Complete sale**.

To take a split payment:

1. Press **PAY** to open the **Payment** screen.
2. Choose the tender type for the first payment (for example click **Mobile**).
3. Enter that payment's amount (for example the mobile-money portion).
4. Press **Add tender**. The payment appears in the tender list on the left, and **Paid** increases by that amount.
5. For the last payment, click its tender type (for example **Cash**) and either enter the cash handed over, or leave the box empty — **Add tender** with an empty box adds exactly the remaining balance.
6. Press **Add tender** again, and watch **Paid**. When it reaches the **Total**, the sale is fully covered. (If the last tender is cash and the customer over-pays, the **Change** figure tells you what to give back.)
7. Press **Complete sale**.

> **Removing a payment you added by mistake.** Each row in the tender list has a small **×** on the right. Click it to remove that payment before you complete the sale. The **Paid** total updates immediately.

> **Keep non-cash payments exact.** Make any **Card**, **Mobile** or **Cheque** payment the precise amount for that portion, and let **Cash** be the one that absorbs any over-payment (the change). Change is given only on cash; over-paying on a non-cash tender is refused by the server.

### Split-payment troubleshooting

| What you see | What to do |
| --- | --- |
| *"Tendered … is less than the total …."* when you press **Complete sale** | The payments you added do not yet cover the **Total**. Add another tender for the shortfall, then press **Complete sale** again. |
| You added the wrong amount or wrong type | Click the **×** on that row in the tender list to remove it, then add it again correctly. |
| The customer changes their mind about how to pay | Remove the tenders with **×** and start the payment again, or close the **Payment** screen (the **×** at the top, or **Esc**) to return to the basket. |

---

## The change preview

**What it is.** The **Change** figure on the **Payment** screen is how much money you owe the customer back when they have handed over more than the basket total.

**Why it is a preview.** The figure is calculated on the till as a convenience so you can count out the change quickly. The receipt prints the change too, taken from the finalised sale.

**How it appears.** **Change** is shown at the bottom-left, under **Total** and **Paid**, only when there is change to give:

- in a plain cash sale, it is the cash you entered minus the total;
- in a split sale, it is the total of the payments added minus the total owed.

> If **Change** does not appear, there is no change to give — the customer paid the exact amount, has not yet covered the total, or a line has no price yet.

---

## Completing the sale

**What it is.** **Complete sale** is the button that turns your prepared payment into a real, recorded sale on the ERP server and produces the receipt.

**Why it is the only step that commits.** Up to this point everything on the **Payment** screen is local preparation. **Complete sale** is the single action that sends the sale to the server and finalises the invoice. Nothing is charged before you press it.

**How it works.** When you press **Complete sale**:

1. OrbixPOS checks that the payments cover the total. If they do not, it warns you (*"Tendered … is less than the total …"*) and stops — fix the payments and try again.
2. If the basket contains an **age-restricted** item, you are asked to confirm the customer's age first (see below).
3. The sale is sent to the server. The button is disabled, and the **Payment** screen cannot be closed, until the server answers.
4. On success, the basket clears, the receipt is saved on this device, and the **Sale complete** receipt appears.

> **Press it once and wait.** While the sale is being sent, the button is disabled so a second press cannot fire. If the connection is slow, give it a moment rather than pressing repeatedly.

### Age-restricted items

**What it is.** Some products (for example alcohol, tobacco or certain medicines) have a minimum legal age. OrbixPOS flags these and asks you to confirm the customer is old enough before the sale can complete.

**How it works.** If the basket contains a restricted item, pressing **Complete sale** opens an **Age-restricted items** prompt: "This basket contains 18+ items. Confirm you have verified the customer meets the minimum age." (or 21+).

1. Verify the customer's age — by checking ID where required.
2. If they meet the minimum age, press **Age verified** and the sale continues.
3. If they do not, press **Cancel**. The sale is stopped (you will see *"Sale stopped: age not verified."*); remove the restricted item before completing.

> Only press **Age verified** once you have genuinely checked. Pressing it records that the check was done.

> **Supervisors with the age-override right:** **Cancel** always stops the sale, for everyone. An account allowed to override the age check also sees a third button, **Override without check**, in the **Age-restricted items** prompt. Pressing it completes the sale without the age confirmation. Use it only when your shop's policy allows it.

### When the server refuses the sale

If the server definitely refuses the sale — for example the session has been closed, an item is out of stock, or a discount is larger than you may give — a red banner appears on the left with the server's reason and the line **"Nothing was charged. Fix it and try again."** The same reason appears briefly at the bottom of the screen. The basket is still there.

1. Read the reason.
2. Fix the cause (close the Payment screen to change the basket if needed).
3. Press **Complete sale** again.

If the refusal is about a **discount** above what a cashier may give alone, the banner also offers **Get manager approval for the discount**. A manager types their username and password in the **Manager approval — discount** dialog; the till then retries the same sale with the approval attached and shows **Approved by …** with the manager's name.

If the till says **"This basket is too old to complete. Nothing was charged — ring it again."**, the basket waited too long before reaching the server. Nothing was charged; ring it again.

---

## "Did that go through?" — the safe retry

**What it is.** Sometimes you press **Complete sale** and the till cannot tell whether the sale succeeded — the network drops, the server takes too long to answer, or the server reports an error after it may already have saved the sale. OrbixPOS handles this so you can safely try again **without charging the customer twice**.

**Why it exists.** A slow or broken network at the moment money changes hands is a real risk: retry the wrong way and you might charge twice; do not retry and you might lose the sale. OrbixPOS removes that dilemma.

**How it works.** Every sale carries a hidden reference that stays the same across every retry, and the till saves it on the device *before* sending the sale. The server remembers the reference. If the first attempt actually reached the server, retrying with the same reference simply returns that *same* sale — it does not create a second one. If it never reached the server, the retry creates the sale normally. Either way you end up with exactly one sale.

When the outcome is unknown, a yellow banner appears on the left:

> *"No answer from the ERP, so we cannot tell whether this sale went through. Press Retry — it is safe. If the sale was already recorded you will get that same receipt back, never a second charge."*

At the same time the green button changes from **Complete sale** to **Retry this sale**. If the server did send an explanation, it is shown in smaller text under the banner, starting **"The ERP said:"** — for example that a product has no price yet.

What to do:

1. Read the yellow banner. It means: the till is not sure whether the sale was recorded.
2. Press **Retry this sale**.
3. One of two things happens:
   - if the sale had gone through, the original receipt appears — no second charge;
   - if it had not, the sale is created now and the receipt appears.
4. You can press **Retry this sale** as many times as it takes; it is always safe.
5. If it keeps failing, read the **"The ERP said:"** line and show it to your supervisor. A retry cannot fix a problem such as an unpriced product — the product must be fixed in the ERP first.

| What you see | What it means | What to do |
| --- | --- | --- |
| Yellow *"No answer from the ERP…"* banner and a **Retry this sale** button | The till could not confirm the sale. | Press **Retry this sale**. It returns the original sale if it went through, or completes it if it did not. |
| The same, plus *"The ERP said: …"* | The server gave a reason, but the till still cannot be sure nothing was saved. | Retry once. If it fails again with the same reason, fix that reason (ask your supervisor). |
| Red banner with *"Nothing was charged. Fix it and try again."* | The server definitely refused the sale. Nothing was saved. | Fix the cause and press **Complete sale** again. |
| The receipt appears (**Sale complete**) | The sale succeeded. | Hand over change and the receipt; you are done. |

> Do **not** clear the basket and ring the items again after an unknown outcome. Always use **Retry this sale** on the same **Payment** screen — that is what protects against a double charge.

### An unfinished sale from before

If you close the **Payment** screen while the outcome is still unknown — or the app is closed, the PC restarts, or the power fails in the middle of a sale — the till remembers that sale. The next time you reach the register, or press **PAY**, it shows an **Unfinished sale** dialog: "A sale of TZS … started at … was interrupted before we knew whether it went through."

| Button | What it does |
| --- | --- |
| **Check sale** | Asks the ERP what happened. It only reads — it charges nothing. This is the one to press. |
| **Not now** | Leaves the question open. The till will not let you take another payment until it is settled. |
| **Leave unresolved…** | Gives up without an answer. It needs a manager's approval and is recorded on the ERP. Use it only when the ERP cannot answer and a supervisor decides. |

After **Check sale**, one of these follows:

| What the till says | What to do |
| --- | --- |
| **That sale went through as …** (the receipt number) **Nothing more to do.** and the receipt | The customer was charged. Give them the receipt if they are still there. Do not ring it again. |
| **Nothing was recorded** — "The ERP has no sale under this reference, so the customer was not charged." | If the customer is still there and wants the goods, press **Complete the sale** — it is sent under the same reference, so it can never charge twice. If they have left, press **Discard** (*"Discarded — nothing was charged."*). |
| **That sale is still going through. Give it a moment, then check again.** | The ERP is still working on it. Wait a few seconds and press **Check sale** again. |
| **Cannot reach the ERP to check. Try again when the connection is back.** | The till is offline. Fix the connection, then check again. |

If a manager approves **Leave unresolved…**, the till says *"Left unresolved … Check Today's sales before ringing it again."* Do exactly that: look for the sale in **Today's sales** before ringing the customer's goods again.

---

## After payment: the receipt

When the sale completes, OrbixPOS shows the **Sale complete** receipt straight away — on screen, laid out exactly as it will print. It is **not printed automatically**: press **Print** to print it on the receipt printer. If the till is set to open the cash drawer, the drawer opens as this first copy prints (it does not open again for a second copy or a reprint).

From the same screen you can produce a **Gift receipt** (which hides prices), or — with the right permission and while your session is open — **Refund / reverse** the whole sale (for a cashier, a manager approves it). The receipt is also saved on this device so you can reprint it later without creating a new sale.

If no printer is set up, **Print** shows **No receipt printer set — configure one in Setup.** The sale is still complete and the receipt can be reprinted later.

The full set of receipt actions — printing, gift receipts, reprints and refunds — is covered in the next chapter, *Receipts and Refunds* (Chapter 6).

---

## Quick reference

| You want to… | Do this |
| --- | --- |
| Open the payment screen | Press **PAY** on the register (basket must not be empty). Settle any **Unfinished sale** first. |
| Take exact cash | Press **Complete sale** (no need to enter anything). |
| Take cash and give change | Enter the cash given (keyboard, pad, or a quick-cash button), check **Change**, press **Complete sale** or **Enter**. |
| Take one card / mobile / cheque payment | Click the tender type, take the payment for the exact total, press **Complete sale**. |
| Split across methods | Pick a type, enter the amount, press **Add tender**; repeat (an empty box adds the remaining balance); press **Complete sale**. |
| Remove a payment you added | Click the **×** on its row in the tender list. |
| Sell an age-restricted item | Press **Complete sale**, check ID, then **Age verified**. |
| Recover from an unknown outcome | Press **Retry this sale** under the yellow banner — it never double-charges. |
| Settle an interrupted sale | **Unfinished sale → Check sale**, then follow what it says. |
| Get a discount approved | **Get manager approval for the discount** on the red banner; a manager signs in to the approval dialog. |
| Print the receipt | Press **Print** on the **Sale complete** screen. |
| Abandon the payment | Press the **×** at the top of the **Payment** screen (or **Esc**) to return to the basket. |

---

# Receipts and Refunds

This chapter explains what happens after you take payment: the receipt that OrbixPOS shows you and what each line on it means, how to print it, how to give a customer a price-free **gift receipt**, how to find and reprint an earlier receipt, and how to refund a whole sale with a manager's approval when something has gone wrong. It also explains — clearly — what OrbixPOS does **not** let you do, and what to use instead.

If you have not yet read the chapters on ringing a sale and taking payment, read those first. This chapter picks up the moment the sale is finished and the receipt appears.

> **This is a sales receipt, not a TRA fiscal receipt.** The OrbixPOS receipt is laid out like a familiar Tanzanian supermarket receipt, but it is an **ordinary sales receipt**. It is not produced by an EFD or VFD and it carries none of the fiscal markings — no "legal receipt" banner, no EFD serial or UIN, no Z number, no verification code and no QR code. It cannot be verified on the TRA portal. Your shop's EFD/VFD arrangement is separate from OrbixPOS: if a customer needs a fiscal receipt, issue it the way your shop normally does.

---

## 1. The receipt screen

**What it is.** The receipt screen pops up the instant a sale completes. Its header reads **Sale complete** with a green tick. Below that is a white slip showing the receipt **exactly as it prints** — the same words, the same columns — followed by the action buttons.

**Why it exists.** The receipt is the customer's proof of purchase and your proof that the sale was recorded. It is built from the **finalised sale** that the ERP server sent back — not from the figures you saw while ringing the basket. The prices, VAT and totals on the till while you were scanning are a preview; once the sale finalises, the ERP returns the official figures and the receipt shows *those*. This finalised sale is the **receipt of record** — the single, true version of what was sold.

**When it happens.** Automatically after a successful payment. It also appears whenever you reprint an earlier sale (section 4).

**How it works.** Nothing on this screen changes the money. **Print**, **Gift receipt** and reprinting only read the sale. The only button that changes anything is **Refund / reverse** (section 5), and that one needs a manager.

### 1.1 Reading the receipt

The slip is laid out top to bottom like a paper till roll:

| Part of the slip | What it means |
|---|---|
| Company name (capitals, centred) | The trading company this sale belongs to. |
| Company details (centred) | The address, town and region, country, **Tel:**, **Email:**, **TIN:** and **VRN:** — taken from your company's record in the ERP. Only the details that have been filled in are printed; if your TIN or VRN is missing, ask your administrator to add it to the company record. |
| Branch name (centred) | The shop or outlet you are working in. |
| **CUSTOMER NAME:** | The customer on the sale, for example *Walk-in Customer*, or *n/a* if no name is available. |
| **RECEIPT NO:** | The number the ERP gave the sale, for example `INV-2026-004218`. This is the unique reference for the sale — quote it on any query or refund. |
| **RECEIPT DATE:** / **RECEIPT TIME:** | When the sale was finalised, as day-month-year and hours:minutes:seconds. |
| **CASHIER:** | The name of the person who rang the sale — also on a reprint (see the note in section 4). |
| **Description · Qty · Amount** | One row per product: the name, the quantity, the line amount including VAT, and a **tax letter** at the end of the row (see below). A long name wraps onto the next line. |
| `@ 1,000.00` under a row | The price for one unit, shown when the quantity is not 1. |
| `less disc 500.00` under a row | A discount taken off that line. |
| **TOTAL EXCL OF TAX:** | The total before VAT. |
| **TAX A-18%** | The VAT charged on standard-rated items, at the rate shown. |
| **TOTAL TAX:** | All VAT on the sale. |
| **TOTAL INCL OF TAX:** | The total the customer owed, with the currency (for example `TZS 25,000.00`). |
| Tender lines | One line per way the customer paid — **CASH**, **CARD**, **MOBILE MONEY** or **CHEQUE** — with the amount. A split payment shows several. |
| **CHANGE** | Shown only when change was given. |
| *Thank you!* | The footer. |

**The tax letters.** Each item row ends with a letter that tells you how VAT was applied to it:

| Letter | Meaning |
|---|---|
| **A** | Standard rate — VAT charged (18%). These items make up the **TAX A-18%** line. |
| **C** | Zero-rated — VAT applies at 0%. |
| **E** | Exempt — no VAT. |
| (blank) | An older receipt reprinted from this till, saved before the letters were added, for an item with no VAT. |

> **Note.** Quantities print without decimals for whole numbers (for example `6`) and with decimals for weighed goods (for example `1.255`). They come straight from the finalised sale, so they always match what the customer was charged.

> **Tip.** If the item area shows `(line detail not loaded)` instead of the products, the totals are still correct and the sale is still recorded — only the itemised breakdown could not be fetched at that moment. Reprint it from **Today's sales** (section 4.1) to pull the full detail from the ERP.

### 1.2 A sample receipt

This is what a receipt looks like on 80 mm paper (48 characters wide). The company, address and numbers are examples; yours will show your own. On 58 mm paper the same information is squeezed into 32 characters, so long names and large amounts wrap onto extra lines.

```text
             SAMPLE SUPERMARKET LTD
             Plot 00, Market Street
               Moshi, Kilimanjaro
                    Tanzania
             Tel: +255 27 275 0000
                TIN: 123-456-789
                VRN: 40-012345-A
               Town Centre Branch
================================================
CUSTOMER NAME:                  Walk-in Customer
================================================
RECEIPT NO:                      INV-2026-004218
RECEIPT DATE:                         02-10-2026
RECEIPT TIME:                           14:07:32
CASHIER:                            Neema Joseph
================================================
Description                      Qty    Amount
Coca-Cola 500ml                    6  6,000.00 A
  @ 1,000.00
Drinking Water 1.5L x 6 (carton)   1  4,000.00 A
  less disc 500.00
Maize Flour 5kg                    1 12,000.00 E
Fresh Milk 1L                      2  3,000.00 C
  @ 1,500.00
================================================
TOTAL EXCL OF TAX:                     23,474.58
TAX A-18%                               1,525.42
TOTAL TAX:                              1,525.42
TOTAL INCL OF TAX:                 TZS 25,000.00
================================================
MOBILE MONEY                           10,000.00
CASH                                   20,000.00
CHANGE                                  5,000.00

                   Thank you!
```

In this example the customer paid TZS 10,000 by mobile money and handed over TZS 20,000 in cash for the remaining TZS 15,000, so TZS 5,000 change was given.

### 1.3 Closing the receipt

When you are done with the receipt, press **Done** (or the **✕** in the top-right corner). This returns you to the register, ready for the next customer. Pressing **Done** does **not** cancel or change the sale — the sale is already recorded in the ERP. It simply puts the slip away.

---

## 2. Printing a receipt

**What it is.** **Print** sends the receipt to the receipt printer attached to your till.

**When it happens.** Press **Print** whenever the customer wants paper — usually straight away, but you can also reprint older receipts later (section 4).

1. With the receipt showing, press **Print** (the printer icon, top-left of the buttons).
2. The receipt prints and you see *Printed.*

| Message | What it means |
|---|---|
| *Printed.* | The receipt went to the printer. |
| *No receipt printer set — configure one in Setup.* | This till has no printer chosen. Sign out, press **Server setup** on the sign-in screen, choose the printer, the paper width (**58 mm · 32 cols** or **80 mm · 48 cols**) and the print mode, and press **Test print** to check it. Usually your administrator does this once when the till is installed. |
| *Could not print the receipt.* (or a message from the printer) | The printer did not accept the job. Check it is switched on, has paper and is connected, then press **Print** again. The sale is not affected. |

> **The cash drawer.** If your till is set up to **Open cash drawer after printing**, the drawer opens **once per sale**: on the first print of the sale's own receipt, straight after the sale, so you can put the money in and give change. It does **not** open when you print a second copy, a gift receipt, a reversed sale, or a reprint from **Today's sales** or **Recent receipts** — no money is going in, so the drawer stays shut. If the drawer is not set up to open, use its key or release lever as usual.

> **Paper width.** The screen always shows the 80 mm layout. The printed copy follows the paper width chosen in setup, so on 58 mm paper some lines wrap differently from the screen — the figures are the same.

---

## 3. Gift receipts (hiding prices)

**What it is.** A **gift receipt** is the same receipt with all the money removed. It lists what was bought and how many, but no amounts, unit prices, tax letters, totals, tenders or change.

**Why it exists.** When someone buys a present, they do not want the recipient to see the price. A gift receipt lets the recipient return or exchange the item (your store policy permitting) without learning what was paid.

**How it works.**

1. With the receipt showing, press **Gift receipt** (the gift-card icon).
2. The slip redraws with the prices hidden. Where the totals were, it shows `* gift receipt - prices hidden *`. The receipt number, date, customer and cashier are still shown, so the item can be traced for a return.
3. Press **Print** to print this price-free copy.
4. To bring the prices back, press the same button — it now reads **Show prices**.

> **Note.** Switching to gift view changes only what is shown and printed. It does **not** change the sale or anything in the ERP. You can flip between **Gift receipt** and **Show prices** as often as you like.

---

## 4. Reprinting an earlier receipt

Sometimes a customer comes back later — they lost the slip, the printer jammed, or they need a copy. OrbixPOS gives you two ways to find an earlier receipt. Both are in the **Session** menu (press the **☰** button in the top bar).

**The golden rule:** reprinting **never creates a new sale**. It shows the same finalised sale again. The customer is not charged a second time, stock is not touched, and no new receipt number is created. You can reprint a receipt as many times as you need.

| Source | Where it looks | Use it when |
|---|---|---|
| **Today's sales** | The ERP server | You want a sale rung **today at this branch**, even one rung on a different till or by a different cashier. Needs a network connection. |
| **Recent receipts** | This till only | You want a sale that was rung **on this till**, and you may be offline. Works without the network. |

> **The CASHIER line on a reprint.** A reprint prints the name of the cashier who **rang** the sale, not the person reprinting it. If you reprint a colleague's receipt, their name appears on the **CASHIER:** line, not yours. On a reprint of an older receipt — one saved on the till before OrbixPOS 1.5.4 — the till may not know who rang it; it then leaves the **CASHIER:** line off rather than print the wrong name. The ERP still records who really made the sale.

### 4.1 Today's sales (look up on the ERP)

1. Press **☰** to open the **Session** panel.
2. Press **Today's sales** (*Look up & reprint a receipt*).
3. OrbixPOS fetches **today's** till sales at **this branch** from the ERP — every sale since midnight on the till's clock, from any till and any cashier at the branch, newest first (up to the latest 100). Each row shows the receipt number, the time and the name of the cashier who rang it, and the total.
4. Tap the sale you want. The full receipt loads and the receipt screen opens.
5. Press **Print** (or **Gift receipt** then **Print**) as normal.

> **Note.** **Today's sales** needs the network. If the connection is down, use **Recent receipts** instead. If the list shows *No sales at this branch today.*, nothing has been sold at this branch since midnight. A sale from an earlier day is not listed — find it under **Recent receipts** on the till that rang it, or ask the back office. A sale that has been **reversed** stays in the list, marked **· Reversed** after its receipt number, so a refund never makes a sale disappear from the day; it reprints with the header **Sale reversed** and the `*** REVERSED ***` stamp. If you do not see **Today's sales** in the menu at all, your account is not allowed to look up sales; ask your supervisor.

### 4.2 Recent receipts (this till, works offline)

**What it is.** The last 50 receipts completed **on this till**, kept on the till itself.

1. Press **☰** to open the **Session** panel.
2. Press **Recent receipts** (*Reprint from this device (offline)*).
3. A list headed **Recent receipts (this device)** appears — each row shows the receipt number, the date and time, and the total.
4. Tap the receipt you want. The receipt screen opens.
5. Press **Print** (or **Gift receipt** then **Print**) as normal.

> **Note.** **Recent receipts** only holds sales rung **on this particular till**. If the list shows *No receipts on this device yet.*, this till has not completed any sales since its local history was last cleared. A sale that was reversed on this till opens with the header **Sale reversed** and the `*** REVERSED ***` stamp.

> **Tip.** Reprints from either source open the *same* receipt screen with the *same* buttons, so you can print, switch to a gift receipt, or — if allowed — refund the sale.

---

## 5. Refunding (reversing) a whole sale

**What it is.** A **refund / reverse** cancels an entire sale that has already been recorded. It gives the money back, puts the stock back, and undoes the VAT and revenue in the ERP.

**Why it exists.** Mistakes happen — the wrong item was scanned, a customer changes their mind right after paying, a basket was rung twice. Reversing the sale is the clean, fully-accounted way to put everything back, with a record of who did it, who approved it, and why.

**Who can do it.** A refund always involves a **manager**:

- **A cashier** can start a refund only on a sale rung on **their own** open till session, and a manager must approve it on the spot by typing their username and password at the till. On a colleague's sale the **Refund / reverse** button does not appear at all — fetch a supervisor instead.
- **A supervisor or manager** whose account holds the invoice-void right (by default the **Sales Manager** and **Branch Manager** roles) is the approver. Normally they walk over and approve at the cashier's till. If a supervisor with this right is running a till shift themselves, they see **Refund / reverse** on any sale while their own shift is open, and they are not asked for a second approval. The ERP still accepts the reversal only while the shift that rang the sale is open.

The approving manager must be a **different person** from the one signed in. Nobody can approve their own refund.

### 5.1 How to reverse a sale

1. Open the sale's receipt. This can be the receipt that just appeared after the sale, or one you reprinted from **Today's sales** or **Recent receipts** (section 4).
2. Press **Refund / reverse** (the red button with the undo arrow). It appears only when a refund is allowed — see section 5.2.
3. A box headed **Reverse this sale?** appears: *This voids the whole sale and reverses revenue, VAT, cash and stock. Allowed while the session is open, and it needs a manager.*
4. Type a short **Reason** — what happened, for example "wrong size, customer swap" or "rang twice in error". It goes into the record. (If you leave it blank, the reason is saved as "POS reversal".)
5. Press **Continue**, or **Cancel** to back out.
6. **If you are a cashier**, a **Manager approval — refund** box opens. It says *Reverse this sale and return the money to the customer.* and names the receipt and amount, for example *Receipt INV-2026-004218 — TZS 25,000.00*. The manager types their **Manager username** and **Manager password** and presses **Approve**. You stay signed in — this only checks the manager's authority for this one refund.
7. On success you see *Sale reversed — approved by [manager's name].* (or just *Sale reversed.* when a supervisor did it on their own authority). The receipt header changes to **Sale reversed** and the slip is stamped `*** REVERSED ***`, followed by `Approved by [manager's name]` when a manager approved it.
8. Give the customer their money. The sale's cash automatically drops out of your drawer's expected total, so you do **not** record a separate cash payout for it.

If the manager presses **Cancel** in the approval box, you see *Not approved — the sale stands.* and nothing changes.

> **If the manager's details are refused.** The approval box stays open and shows why — for example *Those details were not accepted. Check the username and password and try again.* or *That user is not allowed to approve this action.* (the user does not hold the refund-approval right, or is the same person who is signed in). The manager can retype, or you can press **Cancel**.

### 5.2 When the Refund / reverse button is available

The **Refund / reverse** button only shows when **all** of these are true:

| Condition | Why |
|---|---|
| Your account may start refunds (the till refund right, held by cashiers by default) **or** approve them (the invoice-void right, held by sales and branch managers) | Without either, the button is hidden. |
| The shift on **this till** is still **open** | The refund comes out of the open drawer. Once the shift is closed, the cash is settled. |
| **For a cashier:** the sale was rung on **your own** open shift. (A supervisor with the invoice-void right skips this check.) | A cashier may only reverse their own sales. On a colleague's sale the button is simply not shown, so nobody calls a manager over for a refund that would be refused. |
| The sale has **not already** been reversed | A sale can only be reversed once. |

Even when the button shows, the ERP checks again when you confirm:

| The ERP refuses with … | Meaning |
|---|---|
| *You can only reverse sales rung on your own till session. Ask a supervisor to reverse this one.* | A cashier tried to refund a colleague's sale. A supervisor must do it. Normally the till hides the button on a colleague's sale, so you should rarely see this — it can still appear, for example when the till is connected to an older ERP server that does not tell it which shift rang the sale. |
| *This refund needs a supervisor's approval. Ask a supervisor to approve it at the till, then try again.* | The approval did not reach the ERP or was not accepted. Start again from **Refund / reverse**. |
| A message saying the session is not open | The shift that rang this sale has already been closed. It must be cancelled in the back office instead (see below). |
| A message saying the invoice is not a POS sale | The sale was not made at a till; it must be cancelled in the back office. |

In every refused case the sale is left untouched.

> **A sale from a shift that has already been closed.** OrbixPOS cannot reverse it, because the drawer it belonged to is settled. The sale has to be cancelled in the back-office ERP, where the cash difference is handled as a reconciliation matter. Ask your supervisor or store manager.

---

## 6. What OrbixPOS does *not* do — partial and single-line refunds

**OrbixPOS cannot refund part of a sale.** There is **no** way to refund a single line, one item out of five, or part of a quantity. **Refund / reverse** is all-or-nothing.

When a customer wants to return just one item out of a larger basket, you have two correct options, depending on your store's policy:

| Situation | What to do |
|---|---|
| Customer returns **one item** from a multi-item sale, and the sale's shift is still open | **Reverse the whole sale** (section 5), then **ring a fresh sale** for the items the customer is keeping. The net effect is that only the returned item is refunded. |
| You must **hand cash back** that is not tied to a reversible sale (a goodwill cash-back, or a return for a sale from an already-closed shift) | Send the customer to the back office for a sales return — the till has no cash refund payout any more (section 7). |

> **Tip.** "Reverse the whole sale, then re-ring the rest" keeps the books accurate, because each step is a complete, properly-accounted transaction. It takes a few more steps, but it is the right way. The re-rung sale gets a new receipt number.

---

## 7. There is no cash-drawer refund payout

Earlier versions of OrbixPOS let a cashier hand cash back as a **Refund** *cash payout*. That is gone. A refund payout took money out of the drawer on a typed reason alone: the goods stayed "sold", stock was not returned, VAT and revenue were not reversed, and the drawer still balanced — so nobody could see it.

**What to do instead.** Refund the sale with **Refund / reverse** (section 5). It needs a manager's approval, and it puts the stock back and corrects the sales, VAT and ledger in one step. If the sale cannot be reversed at the till (for example it was rung on a shift that is already closed, or only some items are coming back), send the customer to the back office for a sales return.

> A till that has not been updated yet may still show **Refund** in the **Cash payout** dialog. The server now refuses it with *"A cash refund needs a supervisor. To give a customer their money back, reverse the sale from Today's sales instead."* A supervisor who is allowed to reverse sales can still record one from their own login, and it is recorded with their name.

---

## 8. Quick reference and troubleshooting

| What you see | What to do |
|---|---|
| The **Sale complete** receipt appeared after payment | The sale is recorded. Press **Print** for paper, **Gift receipt** to hide prices, then **Done** to move on. |
| A customer asks for a TRA / EFD receipt | The OrbixPOS receipt is not a fiscal receipt. Issue the fiscal receipt through your shop's EFD/VFD arrangement. |
| A customer wants a present receipt with no prices | Press **Gift receipt**, then **Print**. Press **Show prices** to switch back. |
| A customer lost a receipt from earlier today (any till at this branch) | **☰** › **Today's sales** › tap the sale › **Print**. Needs the network. |
| A customer lost a receipt and you are offline | **☰** › **Recent receipts** › tap the sale › **Print**. Works without the network (this till's sales only). |
| You reprinted a receipt — was the customer charged again? | No. Reprinting never creates a new sale and never charges anyone. |
| *No receipt printer set — configure one in Setup.* | A printer has not been chosen for this till. Set it in **Server setup** on the sign-in screen (usually an administrator's job). |
| The company address, TIN or VRN is missing from the receipt | It has not been filled in on the company record in the ERP. Ask your administrator. |
| You need to undo a whole sale and the shift is still open | On the receipt, press **Refund / reverse**, enter a reason, press **Continue**, and have a manager approve it. |
| **Refund / reverse** is missing | The shift on this till is already closed (the back office must handle it), the sale was already reversed, the sale was rung on a colleague's shift (a cashier can only reverse their own — ask a supervisor), or your account has no refund rights (ask a supervisor). |
| *You can only reverse sales rung on your own till session.* | It is a colleague's sale. Ask a supervisor to reverse it. (Rare — normally the button is not shown on a colleague's sale.) |
| *Not approved — the sale stands.* | The approval was cancelled. Nothing changed. |
| Customer wants to return just one item from a bigger sale | Reverse the whole sale, then re-ring the items they are keeping (section 6). |
| You must hand cash back but there is no reversible sale | Ask the back office for a sales return — there is no till refund payout (section 7). |
| The item area shows `(line detail not loaded)` | Totals are still correct. Reprint from **Today's sales** to pull the full breakdown. |

> **Remember the four rules of this chapter:**
> 1. The receipt is built from the **finalised sale** — the ERP's official record. It is an ordinary sales receipt, **not** a TRA fiscal receipt.
> 2. **Reprinting never creates a new sale** and never charges the customer.
> 3. Refunds at the till are **whole-sale only**, only while the shift is open, and always **approved by a manager** (or done by one).
> 4. For anything else, reverse-and-re-ring, or ask the back office for a sales return.

---

# Troubleshooting and Good Practice

Even on a good day, a till sometimes hesitates: the network blips, a barcode finds nothing, the printer is out of paper, a screen says you cannot do something. This chapter is your first-aid kit. It is written so that, when a message appears, you can find the exact wording, understand what it means, and know the next thing to press — without calling for help every time. Supervisors and whoever looks after the till PC will find the setup, certificate and printer fixes here too.

It also covers a few **good habits** that keep your drawer clean and your sales safe: ringing one sale at a time, checking the printed total against the screen, and — most important — never re-ringing a sale when you are not sure whether it went through.

> **The golden rule of this chapter.** OrbixPOS is a *till*, not the books. The ERP server is the single source of truth for price, VAT, totals, and your cash variance. Anything you see in money before you take payment is a **preview**. The **receipt of record** is built from the finalised invoice the server sends back. When something looks wrong, the question is almost always "did the server hear me?" — and this chapter shows you how to find out safely.

At the end of the chapter, **Quick reference — symptom to fix** lists every problem in one table.

---

## How OrbixPOS tells you something went wrong

**What it is.** Whenever the till asks the server to do something — sign you in, load tills, ring a sale, close a session — the server answers. If the answer is anything other than "done", OrbixPOS shows you a short message:

- on the **Sign in** screen, as a red banner above the **Sign in** button;
- on the **Payment** screen, as a yellow banner (outcome unknown) or a red banner (refused);
- on the **Open shift** screen, as an amber strip for a warning that does not stop you;
- everywhere else, as a small notice (a **toast**) near the bottom of the screen for a few seconds — dark for a problem, green for success.

**Why it exists.** You should never have to read a technical error code. When the server explains *why* it refused something — "this product has no price", "this till already has an open session" — OrbixPOS shows you the server's own words. When it has no explanation, the till shows a plain sentence based on what kind of failure it was.

**How to use it.** Read the message, find it in the tables below, take the matching action, and try again. If you ever see raw code or a long block of technical text, note it and report it — that is a fault, not a normal message.

> Most failures are *transient* — a brief hiccup that fixes itself on the next try. The tables below tell you which ones to simply retry, which need you to change something, and the one case (an **unknown outcome**) where you must **not** start over but press **Retry this sale**.

---

## Cannot reach the server

**What it is.** OrbixPOS holds no data of its own. Every action travels over your network to the ERP server. If the till cannot reach that server — the network is down, the cable is out, the address is wrong, the server's certificate is not trusted, or the server itself is off — nothing can be rung.

**When you see it.** On the **Sign in** screen, or as the toast **Cannot reach the ERP. Check the connection and host.** during the day. In **Setup & diagnostics**, **Test connection** shows **Could not reach the ERP at this host.**

**How it works.** OrbixPOS reaches the server at a single address called the **ERP host** — the scheme and server only, for example `http://192.168.1.10:8081` or `https://erp.yourshop.co.tz`. The till adds `/api/v1` itself. **Setup & diagnostics** lets you check that address with **Test connection**.

### Check the server, step by step

1. If you are signed in, sign out (your shift stays open). **Setup & diagnostics** is only on the sign-in screen.
2. On the **Sign in** screen, click **Server setup** (the small link with a gear icon below the **Sign in** button).
3. Look at the **ERP host** field. It should hold exactly the address your administrator gave you.
4. Click **Test connection** and read the coloured result:

| What you see | What it means | What to do |
|---|---|---|
| **Reachable — ERP is UP.** (green) | The till reached the server and the server is healthy. | Click **Save**, then sign in as normal. |
| **Reached host, status unclear.** (red) | *Something* answered at that address, but not a healthy ERP. | The server may be starting up. Wait a minute and test again. If it persists, the address or port may point at the wrong program — check with your administrator. |
| **Could not reach the ERP at this host.** (red) | No ERP answered at that address. | Work through the checklist below. |

5. When the result is green, click **Save**. To leave without changing anything, click **Cancel**.

### If "Test connection" stays red

| Check | What to do |
|---|---|
| **The address ends in `/api/v1`** (or any other path). | Remove it. Type only the scheme and server, for example `http://192.168.1.10:8081`. The till adds `/api/v1` itself, so typing it gives `/api/v1/api/v1` and the test fails. |
| **The address has a typo**, or a wrong port. | Compare it character by character with the one your administrator gave you. A single wrong digit fails. |
| **`http` vs `https`.** | Use exactly the scheme your administrator gave you. A server that expects `https://` will not answer `http://`, and the other way round. |
| **The address is `https://` and the server uses its own private certificate.** | The till must be told to trust the server's certificate authority. Put the root certificate file in the `certs` folder beside `pos_app.exe` (or name it `erp-ca.pem` beside it) and **restart OrbixPOS** — see *Getting Started* (Chapter 1), **Trusting Your Server's Certificate**. |
| **The certificate file is in place, but it still fails.** | Make sure you restarted OrbixPOS after adding it. Make sure the ERP host uses the server **name** the certificate was issued for, not its IP address. If the server was rebuilt recently, its certificate authority may have changed — ask for the new root certificate. |
| **The device is off the network**, or on the wrong Wi-Fi. | Check the network cable or Wi-Fi. Can other things on this device reach the network? |
| **The ERP runs on another PC, but the host says `localhost`.** | `localhost` means "this PC". Replace it with the server's address. |
| **Everything looks right but it still fails.** | The server itself may be down. Note the time and tell your supervisor or administrator — this is not something you can fix at the till. |

> **Certificate problems look like network problems.** When the till does not trust an `https://` server, it usually reports **Could not reach the ERP at this host.** or **Cannot reach the ERP. Check the connection and host.**, and sometimes **The server certificate was rejected.** If a till that worked over `https://` suddenly stops while other devices still reach the server, suspect the certificate first.

---

## Sign-in problems

| What you see | What it means | What to do |
|---|---|---|
| A red banner saying your details were not accepted, or your account is locked | Wrong username or password, or the account is locked or disabled. | Re-type carefully (mind Caps Lock). If it persists, ask your administrator. |
| **Cannot reach the ERP. Check the connection and host.** | The till cannot reach the server. | See **Cannot reach the server** above. |
| **The server did not respond in time. The request may or may not have completed.** | The network or server is slow. | Wait a moment and try again. |
| **This user is not assigned to any company.** | Your account is not attached to a company yet. | Ask your administrator. |
| **No branches found for …** | Your company has no branch the till can use. | Ask your administrator. |
| **You do not have permission for this action.** straight after **Sign in** | Your account lacks one of the basic rights the till needs to start (for example, to see your branch). | Ask your administrator to check your role. |
| Amber strip: "Using branch … — we couldn't confirm your usual branch. Check this is correct before selling." | The till could not confirm your usual branch and picked another. | Check the branch named is the shop you are in. If not, sign out and ask your administrator before selling. |

### Your session has expired / you were signed out

**What it is.** When you sign in, the server gives the till a time-limited pass, which the till renews quietly in the background while you work. If it cannot be renewed — for example the server ended it, or your account was changed — you are returned to the **Sign in** screen with **Your session ended. Please sign in again.** (or **Your session has expired. Please sign in again.**). A brief network drop does **not** sign you out.

**What to do.** Sign in again. Nothing you finalised is lost, and **your shift is still open** — OrbixPOS takes you straight back to your register in it. A basket you had not yet paid for is cleared, so re-ring those items.

> A sign-in ending is not the same as your *cash session* (your shift) closing. Your shift stays **open** on the server until you count the drawer and close it.

---

## Shift and till problems

### The shift will not open

| What you see | What it means | What to do |
|---|---|---|
| **Pick a till first.** | No till tile was selected. | Click a free till (green dot), then **Open session**. |
| **This till already has an OPEN session.** | Someone opened a shift on that till moments ago. | Click **Refresh**, then pick a free till. |
| **No active tills on this branch yet.** | No till is set up or active for your branch. | Ask your store manager to create or activate one (managers see **New till**). |
| An error in the till list, with **Retry** | The till list could not be loaded. | Click **Retry**. If it keeps failing, see **Cannot reach the server**. |
| **You do not have permission for this action.** | Your account may not open shifts. | Ask your administrator. |

### The till is stuck "in use"

| What you see | What it means | What to do |
|---|---|---|
| Your till shows **· Your shift** with an amber dot | You still have a shift open there — usually because the app was closed, the PC restarted or the power failed mid-shift. | Click it, then **Resume shift** to carry on, or **Close shift** to count the drawer and close it. |
| A dialog **Couldn't reopen your shift** | The till could not reopen your shift. Nothing is lost. | Ask a supervisor for session access, or choose **Close shift** to count up. |
| A dialog **Your shift** saying it "could not load it just now" | The till knows the shift is yours but could not load it. | Click **Refresh** on the Open shift screen and try again. |
| A faded tile with a colleague's name and a red dot; **Till in use** | Another cashier has a shift open on this till. | Use another till, or ask that cashier to close their shift. If they are not available, a **branch manager** or administrator closes it in the ERP (**POS Sessions**) after counting that drawer. Then click **Refresh**. |
| Amber strip: "We couldn't check whether you already have a shift open…" | The till could not look up your open shift when you signed in. | If a tile shows **· Your shift**, click it and **Resume shift**. |

> **Shifts never close themselves.** Only a counted close ends a shift, so a till stays "in use" until someone counts its drawer. This is deliberate — it stops the system ever inventing a cash count.

### Other shift problems

| What you see | What it means | What to do |
|---|---|---|
| The **Session** chip shows **—**, and the menu says "Shift figures unavailable — reconnect to refresh." | Your shift was reopened without its details (no permission to view sessions, or the network was down at that moment). | You can keep selling. The figures come back once the till can read the session; ask a supervisor if your account should be allowed to view sessions. |
| After **Close session**, you are still on the register and sales are refused | The session is CLOSED — no more sales can go on it. | If you are a supervisor, carry on with **Reconcile (Z-read)**. Otherwise **Sign out**; when you sign in again, the **Open shift** screen lets you start a new shift. |
| **Session must be CLOSED before reconciliation.** | You tried to reconcile an open session. | Close it (count the drawer) first. |
| A message that the session "has been reconciled — open its Z-read for the final figures" | X-read is not available after reconciling. | Use **Z-read (reprint)** instead. |
| **Enter the counted cash.** / **Count the drawer and enter the cash total.** | The count field was empty. | Type the counted amount. |
| **Enter an amount.** / **Say what the cash is for (at least a few words).** | A cash payout or till expense is missing its amount or reason. | Fill in both. |
| **Choose or type a category (2 to 40 letters).** | A till expense has no category, or a very long one. | Tap a quick choice (**Transport**, **Cleaning**, …) or type a short category. |
| **Till expense** is missing from the Session menu | Your account is not allowed to record till expenses. | Ask your supervisor how the expense should be recorded, or ask your administrator to review your role. |

---

## You do not have permission

**What it is.** OrbixPOS shows actions based on what your account is allowed to do. A cashier can sell and open and close their own shift. Reconciling (the Z-read), approving refunds and large discounts, and creating tills need higher rights, usually a supervisor or store manager.

**Why it exists.** This separation protects the business: the person who counts the drawer should not necessarily be the person who posts the variance to the books, and not everyone should be able to reverse a completed sale.

**How it shows.**

- **Hidden actions.** An action your account may not use is simply **not shown** — for example **Reconcile (Z-read)** is missing from a cashier's Session menu, and **New till** is missing from the Open shift screen. That is by design, not a fault.
- **Manager approval.** Some actions stay visible but ask a manager to approve at the till: a **Manager approval — …** dialog asks for the manager's username and password (X-read for a cashier who may not view sessions, printing the Z-read for someone who may not reconcile, a cashier's refund, large discounts, leaving an unfinished sale unresolved). A supervisor who holds the right for the action is not asked — they are the manager the dialog looks for.
- **A refusal.** You click something and see **You do not have permission for this action.**

| What you see | What to do |
|---|---|
| An action is missing from the menu | Your account does not have that right. Ask a supervisor to do it, or ask your administrator to review your role. |
| **You do not have permission for this action.** | The server refused this action for your account. Do not keep retrying — ask your administrator to review your role. |
| **That user is not allowed to approve this action.** (in an approval dialog) | The manager's password was right, but they do not hold the right for this action — or the person signed in tried to approve their own action. A **different** person with the right must approve. |
| **Those details were not accepted. Check the username and password and try again.** | The manager mistyped. Only the password is cleared; retype it. |
| **Too many failed approval attempts. Please wait a moment and try again.** | Several wrong attempts in a row. Wait, then try again. |
| **That approval was not accepted. Ask a supervisor who can reconcile the till.** | A drawer report was approved by someone who may not approve drawer reports. Ask a supervisor who can reconcile. |
| **Not approved — the sale stands.** | A refund was cancelled or not approved. The sale is unchanged. |

> **Who can print the Z-read.** A user who is allowed to reconcile tills (a Branch Manager, by default) prints the Z-read straight away — no approval dialog, because they are the manager it would ask for. Anyone else, for example a cashier printing a **Z-read (reprint)**, sees the **Manager approval — Z-read** dialog and needs a manager — a different person who may reconcile tills — to approve the print. If no such manager is at the till, read the Z-read on screen; while the reconciled shift is still on the till (before **Finish shift**), **Z-read (reprint)** lets you print it once a manager arrives, and after that the Z-read can be viewed in the ERP.

---

## A sale is refused or its outcome is unclear

### Slow network or a "blip" mid-sale — the unknown outcome

**What it is.** Sometimes a sale leaves the till and the answer never comes back — the network stalls, the server is slow, or it reports an error after it may already have saved the sale. The sale **may** have been recorded, or **may** not have. The till genuinely does not know which.

**Why this is special.** This is the one situation where the obvious move — "it failed, let me ring it again" — is dangerous, because if the first attempt *did* go through, ringing it again would charge the customer twice. OrbixPOS is built so that, as long as you retry the *same* sale, this cannot happen.

**When you see it.** You press **Complete sale**, and instead of a receipt a yellow banner appears in the **Payment** window:

> **No answer from the ERP, so we cannot tell whether this sale went through. Press Retry — it is safe. If the sale was already recorded you will get that same receipt back, never a second charge.**

The big green button changes to **Retry this sale**. If the server did give a reason, it is shown in smaller text underneath, starting **The ERP said:**.

**What to do.**

1. Do **not** close the window and do **not** start a new sale.
2. Press **Retry this sale**.
3. The receipt appears — either the original or the newly recorded one. You are done.
4. If it is still unclear, wait a few seconds for the network to settle and press **Retry this sale** again. It is always safe.
5. If it keeps failing with the same **The ERP said:** reason, retrying will not fix it — that reason must be fixed first (for example a product with no price must be priced in the ERP). Show the message to your supervisor.

> **Warning.** Never react to an unknown outcome by clearing the basket and ringing the items again as a brand-new sale. That is the *only* way to double-charge — and **Retry this sale** exists precisely so you never have to.

### The "Unfinished sale" prompt

**When you see it.** When the register opens, or when you press **PAY**, a dialog titled **Unfinished sale** says a sale "was interrupted before we knew whether it went through". This happens after the app was closed, the PC restarted or the power failed mid-sale, or after you closed the Payment screen while the outcome was unknown.

**What to do.** Press **Check sale**. It only asks the ERP what happened — it charges nothing.

| What the till says next | What to do |
|---|---|
| **That sale went through as …** and the receipt | It was recorded. Give the customer the receipt if they are there. Do **not** ring it again. |
| **Nothing was recorded** | The customer was not charged. If they are still there, press **Complete the sale** (safe — it can never charge twice). If they left, press **Discard**. |
| **That sale is still going through. Give it a moment, then check again.** | Wait a few seconds, then **Check sale** again. |
| **Cannot reach the ERP to check. Try again when the connection is back.** | Fix the connection (see **Cannot reach the server**), then check again. |
| **Still no answer from the ERP. Check the connection and try again.** | The completion attempt got no answer. Check the connection and try again. |
| **That basket is too old to complete. Nothing was charged — ring it again.** | The sale waited too long. Nothing was charged; ring it again as a new sale. |

**Not now** leaves the question open, and the till will not take another payment until it is settled. **Leave unresolved…** gives up without an answer; it needs a manager's approval, is recorded on the ERP, and the till then tells you to check **Today's sales** before ringing those goods again.

### The sale was refused

**When you see it.** A red banner in the **Payment** window shows the server's reason, followed by **Nothing was charged. Fix it and try again.** The basket is still there.

| Typical reason | What to do |
|---|---|
| The session is not OPEN | Your shift was closed. You cannot sell on it — sign out and open a new shift. |
| An item is out of stock, or below the allowed price | Follow your shop's procedure; a supervisor may need to adjust stock or price in the ERP. |
| A discount is larger than you may give | Press **Get manager approval for the discount** on the banner; a manager approves in the dialog and the sale retries. |
| **No sales agent could be determined for this sale…** | See **"No sales agent could be determined"** below. |
| The payments do not cover the total | The server's total is higher than the payments you added. Add a tender for the difference. |

### "Select a customer before completing the sale."

**What it is.** Every sale needs a customer. Normally the till uses your company's walk-in (cash) customer automatically. If it could not find one, it asks you to choose.

**What to do.** In the **Customer** list that opens, pick the walk-in / cash customer (shown with **· Walk-in**) or the named customer, then carry on. If the list is empty or this happens on every sale, tell your supervisor — the walk-in customer may be missing in the ERP, or your account may not be allowed to see customers.

### "No sales agent could be determined"

**What it is.** Every sale is credited to the person signed in, through their own sales-agent record. Nobody has to set this up: if you have no record yet, the system creates one for you on your first sale. So this message is rare — a sale is refused only when that cannot happen, for example because you are signed in on the super-admin account, your account is not an active member of this company, or your sales-agent record was archived.

**When you see it.** The red banner reads **No sales agent could be determined for this sale. Select a sales agent, or ask an administrator to link an internal sales agent to your user account before ringing sales.** — or **Your sales agent record is no longer active, so sales cannot be recorded under it. Ask an administrator to reactivate your sales agent.**

**What to do.**

1. Note the message exactly as shown, and stop trying to ring on this account — pressing **Complete sale** again will fail the same way.
2. If you are signed in with a shared or admin account, sign out and sign back in with your own cashier account. The **super-admin** account is not a salesperson and cannot ring sales.
3. Otherwise show the message to your administrator. If it says your sales agent is **no longer active**, they reactivate it; if it says no sales agent could be determined, they check that your user account is active in this company. Once that is done, sign in and ring as normal.

### "Tendered … is less than the total …"

**When you see it.** A toast such as **Tendered 8,000.00 is less than the total 12,500.00.** when you press **Complete sale** without covering the full amount. (If you entered a single amount without using **Add tender**, it adds "Use Add tender to split, or key the full amount.")

**What to do.** Look at **Total** and **Paid**, then add more tender — choose the tender type, enter the amount (or leave it empty to add the remaining balance) and press **Add tender**, or use a quick-cash button such as **Exact**. When **Paid** covers **Total**, press **Complete sale**.

### Age not verified stops a sale

**What it is.** Age-restricted products (for example alcohol or tobacco) carry a small amber **18+** or **21+** marker. Pressing **Complete sale** on a basket with one opens the **Age-restricted items** dialog.

**What to do.** Check the customer's age (ask for ID if in any doubt). If they qualify, press **Age verified**. If not, press **Cancel** — the till shows **Sale stopped: age not verified.** — then remove the restricted line and complete the rest of the sale.

> **Warning.** Pressing **Age verified** is a statement that you checked. Never press it just to clear the dialog. **Cancel** always stops the sale, whoever is signed in. (An account with the age-override right also sees **Override without check**, which completes the sale without the age confirmation — use it only when your shop's policy allows.)

---

## Scanning and search problems

### A scan or search finds nothing

**What it is.** In the Supermarket register, the field at the top — **Scan a barcode or search by code / name…** — is how you add items. For something that looks like a barcode, the till first looks up the barcode (including weight barcodes); then it searches the catalogue, where an exact product code wins, a single match is added at once, and several matches open a list to pick from.

| What you see | What to do |
|---|---|
| **No match for "…".** after a scan | Scan again — the first read may have been partial. Hold the scanner steady and aim at the whole barcode. |
| **No match for "…".** after typing | Check for a typo. Try part of the **product name** instead. |
| A drop-down list of items appears | Several products matched. Click the right one (or use the arrow keys and **Enter**). |
| The item is found by name, but its barcode always says no match | The barcode is not registered against the product in the ERP — sometimes it was typed into the product's code instead of its barcodes. Tell your supervisor; master-data staff need to add the barcode to the product. Meanwhile, add the item by name. |
| **Price-embedded labels aren't supported yet — enter … manually.** | The label carries a price inside the barcode, which the till cannot use. Search for the item by name and enter the quantity. |
| Still nothing for an item on the shelf | The product may not be in the catalogue. Tell your supervisor. |

### The scan adds the wrong item

**Symptom.** After a scan the search list stays open over an empty box, and the *next* scan adds the **previous** item again instead of the one you just scanned.

**Cause and fix.** This was a fault in OrbixPOS versions before **1.5.2**, fixed in 1.5.2. To see which version your till runs, look under **Server setup** on the sign-in screen or at the bottom of the **Session** menu (for example **OrbixPOS 1.5.4+12**). Ask whoever looks after the till to upgrade (see *Getting Started*, Chapter 1, **Installing OrbixPOS**). Until then, check each line after scanning and remove any wrong line with the **×** on its row.

### The scanner is a "keyboard wedge" — keep the field focused

**What it is.** The barcode scanner behaves like a fast keyboard: when you scan, it *types* the barcode into whatever field has the cursor, then presses Enter.

**Why it matters.** The **search field must hold the cursor** for scanning to work. If the cursor is somewhere else — say you just used the number pad, or a dialog is open — a scan lands in the wrong place or does nothing. The Supermarket register puts the cursor back in the search field after each item, after a quantity change and after payment, but if you click elsewhere you may need to click back.

| What you see | What to do |
|---|---|
| You scan and nothing happens | Click once inside the search field, then scan again. |
| The barcode digits appear in the number pad or another box | Clear that box, click the search field, and re-scan. |
| A scan lands in an open dialog | Finish or close the dialog first, then scan. |

---

## Printer and cash drawer problems

Printing works on the **Windows** till only. The printer, paper width, print mode and drawer option are set in **Setup & diagnostics** on the sign-in screen (see Chapter 1). To change them mid-shift, sign out (your shift stays open), change them, **Save**, and sign in again.

| What you see | Cause | What to do |
|---|---|---|
| **No receipt printer set — configure one in Setup.** | No printer is chosen in Setup. | Sign out → **Server setup** → choose the **Printer** → **Test print** → **Save** → sign in. The sale itself is complete; reprint it afterwards. |
| **Choose a printer first.** (on **Test print**) | The **Printer** box is **— none —**. | Pick the receipt printer from the list. |
| **No printers detected (Windows desktop only).** | No printer is installed in Windows, or you are on the web or Android version. | Install the printer in Windows (with its driver), then close and reopen **Server setup** so the list refreshes. Web and Android cannot print. |
| **Cannot open printer "…". Is it installed and online?** | The printer was removed or renamed in Windows, or is switched off. | Switch it on and check its cable. If it was reinstalled under a new name, choose it again in Setup. |
| **Printer "…" did not accept the job (offline or out of paper?).** | The printer is offline, out of paper, or its cover is open. | Load paper, close the cover, switch it on, then press **Print** again. |
| **Printer "…" did not start the page.** / **Failed to send the receipt to "…".** | The printer stopped part-way. | Check the printer, clear any stuck jobs in Windows, and print again. |
| **Could not print the receipt.** / **Could not print the report.** / **Could not print the test receipt.** | An unexpected printing problem. | Try **Test print** in Setup. If it fails too, restart the printer and the till PC. |
| **Receipt printing is only available on the Windows desktop app.** | You are on the web or Android version. | Print from a Windows till, or reprint later from **Recent receipts** / **Today's sales** there. |
| The receipt prints strange symbols, stray letters at the start, or a page of rubbish | **Thermal (ESC/POS + cut)** mode is sending printer commands to a printer that does not understand them (an office laser or inkjet printer, for example). | Set **Print mode** to **Plain text**. If the printer still prints nothing useful, it cannot take raw text through its driver — ask your supplier for a receipt printer, or install it with the Windows "Generic / Text Only" driver. |
| Lines wrap, totals break onto two lines, or the receipt looks squashed into the left half | The **Paper width** setting does not match the paper. | Choose **80 mm · 48 cols** or **58 mm · 32 cols** to match the roll, then **Test print**. |
| Accented letters or symbols print as **?** | Receipts print plain letters, digits and punctuation only. | Expected. Names with special characters print with **?** in their place. |
| The paper is not cut | **Plain text** mode does not cut, or the printer has no cutter. | Use **Thermal (ESC/POS + cut)** with a thermal printer that has a cutter. |
| The cash drawer does not open after a sale | The drawer option is off, the mode is **Plain text**, the drawer cable is not in the printer's drawer port, or you did not press **Print**. | Tick **Open cash drawer after printing** with **Thermal (ESC/POS + cut)** mode, check the cable, and **Test print**. The drawer opens only when a sale's receipt prints — use the drawer key if you are not printing. |
| The drawer does not open on a reprint, a gift receipt, a second copy or a reversed sale | By design. The drawer opens only once per sale: on the first print of the sale's own receipt, straight after the sale. A reprint from **Today's sales** or **Recent receipts**, a gift receipt, a second copy and a reversed sale put no money in, so the drawer stays shut. Drawer reports (X-read, Z-read) never open it either. | Nothing to fix. If you genuinely need the drawer open, use the drawer key and follow your branch's cash-handling rules. |
| There is no **Print** button on the X-read or Z-read | You are on the web or Android version. | Use a Windows till to print reports. |

> A printing problem never affects the sale. The sale is already complete on the server, and the receipt is saved on this device — reprint it from **Recent receipts** or **Today's sales** once the printer is fixed.

---

## Reprinting a receipt (and why it never makes a new sale)

**What it is.** You can reprint any receipt without creating a new sale. There are two sources, both in the **Session** menu (the **☰** icon in the top bar):

- **Recent receipts** — the last 50 receipts saved on **this device**. Works even when the network is down. Click one to open it, then **Print**.
- **Today's sales** — today's till sales at **this branch**, looked up from the **server** (needs the network, and the right to view sales invoices). It lists every sale since midnight, from any till at the branch, newest first, each with its time and the cashier who rang it; a reversed sale stays in the list marked **· Reversed** and reprints as REVERSED. Click one to open it, then **Print**. Use this to confirm that a doubtful sale really went through before you consider ringing it again. If it reads **No sales at this branch today.**, nothing has been sold at the branch since midnight.

A reprint prints the name of the cashier who **rang** the sale on its **CASHIER:** line, not yours. (A reprint of an old receipt saved before OrbixPOS 1.5.4 may leave the **CASHIER:** line off.) A reprint never opens the cash drawer.

**Why it matters.** Customers ask for a second copy; a receipt jams; you need to confirm a sale posted. Reprinting only re-shows or re-prints an existing receipt. It can **never** post a new sale or charge anyone again.

> **Refunds.** OrbixPOS reverses a **whole** sale (the **Refund / reverse** button on a receipt, while the session is open; a cashier needs a manager's approval). A cashier sees the button only on sales rung on their own open shift — on a colleague's sale it is not shown, so ask a supervisor. It does not do partial or single-line refunds. To return one item from a multi-item sale, either reverse the whole sale and ring the rest again, or pay the cash back with a **Refund** cash payout in the **Session** menu, following your shop's policy. See Chapter 6.

---

## Good habits that prevent problems

### One sale at a time

Finish the sale in front of you — ring, take payment, hand over the receipt — before starting the next. OrbixPOS stops you from switching register **mode** while a sale is in progress (**Finish or clear the current sale before switching mode.**). Treat that as a reminder of the habit.

### Verify the printed total matches the screen

The money you see while building a basket is a **preview**. After you complete the sale, glance at the receipt's total and confirm it matches what you expected and what the customer is paying. If they differ, do **not** improvise — the receipt (from the finalised invoice) is the truth; investigate before handing over change.

### Never re-ring a sale when you are unsure — retry the *same* one

This is the single most important habit on the till. If a sale's outcome is ever in doubt, **do not start a new sale**. Press **Retry this sale** on the same payment, answer **Unfinished sale → Check sale**, or look the sale up in **Today's sales** first.

### Keep the search field focused

The scanner types into the focused field. Before each scan, make sure the cursor sits in the **Scan a barcode or search by code / name…** field.

### Close the shift, don't just close the app

Closing the app, signing out or switching the PC off leaves your shift open. At the end of the day always **Close session** with a real count, so the till is free for the next person.

### Check, don't guess

If a message stops you, read it and match it to a table in this chapter. If it is a permission or setup issue (no sales agent, a missing action, a host that will not connect, a certificate), it needs your administrator, supervisor or store manager — not repeated retries. Note the exact wording, the time and the OrbixPOS version (shown in small grey text under **Server setup** on the sign-in screen and at the bottom of the **Session** menu, for example **OrbixPOS 1.5.4+12**), and hand it on.

---

## Quick reference — symptom to fix

| Symptom or message | Likely cause | Fix |
|---|---|---|
| **Could not reach the ERP at this host.** (in Setup) | Wrong address, `/api/v1` added, wrong `http`/`https`, untrusted certificate, network down, server off. | Correct the host (scheme and server only), check the network, add the certificate and restart; else tell your administrator. |
| **Reached host, status unclear.** | Server starting up, or wrong port/program. | Wait and test again; check the address. |
| **Cannot reach the ERP. Check the connection and host.** | Network down or wrong host. | Sign out → **Server setup → Test connection**. |
| **The server certificate was rejected.** | The till does not trust the server's certificate. | Put the root certificate in `certs` beside `pos_app.exe`, restart OrbixPOS. |
| A till that worked over `https://` stops connecting | The server's certificate authority changed. | Get the new root certificate, drop it in `certs`, restart. |
| **The server did not respond in time…** | Slow network or server. | Wait and retry. During a sale, use **Retry this sale**. |
| **Your session ended / has expired. Please sign in again.** | The server ended your sign-in. | Sign in again; your shift is still open. |
| **This user is not assigned to any company.** / **No branches found for …** | Account not set up. | Ask your administrator. |
| **You do not have permission for this action.** | Your account lacks the right. | Ask a supervisor or your administrator; don't retry. |
| An action is missing from the menu | Your account does not have that right. | Ask a supervisor. |
| **That user is not allowed to approve this action.** | Approver lacks the right, or tried to approve their own action. | A different person with the right approves. |
| **Pick a till first.** | No till selected. | Click a free till. |
| **This till already has an OPEN session.** | Till taken a moment ago. | **Refresh**, pick another till. |
| Till shows **· Your shift** | Your shift is still open (app closed, power cut). | Click it → **Resume shift** or **Close shift**. |
| **Till in use** with a colleague's name | Someone else's shift is open there. | Use another till, or have it closed (by them, or a branch manager in the ERP **POS Sessions**). |
| **Couldn't reopen your shift** | Shift could not be reopened. | Ask a supervisor for session access, or **Close shift** with a count. |
| Session chip shows **—** / "Shift figures unavailable…" | Shift reopened without its details. | Keep selling; figures return when the session can be read. |
| Sales refused after **Close session** | The session is closed. | Supervisor: reconcile. Cashier: sign out, sign in, open a new shift. |
| Yellow **No answer from the ERP…** banner / **Retry this sale** | Network blip mid-sale. | Press **Retry this sale** — never re-ring as a new sale. |
| **The ERP said: …** under the yellow banner | The server gave a reason it could not be sure about. | Retry once; if the same reason returns, fix it (supervisor). |
| Red banner **Nothing was charged. Fix it and try again.** | Server definitely refused the sale. | Fix the reason shown, press **Complete sale** again. |
| **Unfinished sale** dialog | An earlier sale was interrupted. | **Check sale**, then follow what it says. |
| **This basket is too old to complete. Nothing was charged — ring it again.** | The sale waited too long. | Ring it again. |
| **Select a customer before completing the sale.** | No walk-in customer found. | Pick the walk-in customer; tell your supervisor if it repeats. |
| **No sales agent could be determined for this sale…** | You are on the super-admin account, or your account is not active in this company. (A missing sales agent is created automatically on your first sale.) | Use your own cashier account; otherwise ask your administrator to check your account. |
| **Tendered … is less than the total …** | Underpaid. | Add tender until **Paid** covers **Total**. |
| **Age-restricted items** / **Sale stopped: age not verified.** | Restricted item in basket; **Cancel** was pressed. | Check ID → **Age verified**, or remove the line. |
| **No match for "…".** | Barcode, code or name not found. | Re-scan, search by name, pick from the list; report missing barcodes. |
| Next scan adds the previous item; list stays open | Fault in versions before 1.5.2. | Upgrade to 1.5.2 or later; check lines meanwhile. |
| Scan does nothing / lands in the wrong box | Cursor not in the search field. | Click the search field, then scan. |
| **No receipt printer set — configure one in Setup.** | No printer chosen. | Sign out → **Server setup** → pick printer → **Save**. |
| **Cannot open printer "…"** / **did not accept the job** | Printer off, offline, out of paper, renamed. | Check power, paper, cable; reselect it in Setup. |
| Receipt prints rubbish or symbols | Thermal mode on a non-thermal printer. | **Print mode → Plain text**, or use a proper receipt printer. |
| Lines wrap or look squashed | Wrong paper width. | Match **Paper width** to the roll. |
| Cash drawer does not open after a sale | Option off, plain mode, cable, or no print. | Tick the drawer option in thermal mode; check the cable; press **Print**. |
| Cash drawer does not open on a reprint or gift receipt | By design — it opens only on the first print of a sale's own receipt. | Nothing to fix; use the drawer key if needed. |
| **Refund / reverse** missing on a colleague's sale | A cashier can reverse only sales from their own open shift. | Ask a supervisor to reverse it. |
| **Finish or clear the current sale before switching mode.** | Sale in progress. | Finish or clear the sale, then switch. |

When in doubt, remember the two anchors of safe till work: **the server is the truth**, and **retry the same sale, never a new one.**

---

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
| Record a **till expense** (by category) | Yes | No | No |
| **Close** a session (count the drawer) | Own shift | No | Yes — any session, in the ERP web app |
| Open the **X-read** and **Z-read (reprint)** without an approval | Yes | Yes | Yes |
| Look up and reprint from **Today's sales** | Yes | Yes | Yes |
| Start a **Refund / reverse** | Only sales from their own open shift, with a manager's approval | Yes, on any sale while their shift is open; no second approval needed | Yes, on any sale while their shift is open; no second approval needed |
| **Approve** a refund at a cashier's till | No | Yes | Yes |
| **Approve** a discount above the company limit | No | Yes | Yes |
| **Print** a Z-read without an approval | No | No | Yes |
| **Approve** printing a Z-read, or an X-read for a cashier without report rights | No | No | Yes |
| **Approve** leaving an unfinished sale unresolved | No | Yes | Yes |
| **Reconcile (Z-read)** — post the variance to the books | No | No | Yes |
| **Create** tills (**New till**) | No | No | Yes |
| Sell age-restricted items without the age confirmation (**Override without check**) | No | No | Yes |

> **A manager usually approves at the cashier's till rather than running one.** The standard Sales Manager and Branch Manager roles do not include opening a shift or ringing sales. A manager who also works a till needs the Cashier role as well — ask your administrator.

If you expect an action and it is not there, your account does not hold the permission for it. That is a deliberate control, not a fault. Ask your administrator to grant it if your job genuinely needs it.

---

## Manager approval at the till

**What it is.** Some actions a cashier can start but not finish alone. When the cashier gets to that point, OrbixPOS opens a **Manager approval** box on the cashier's own screen. A manager walks over, types **their own** username and password, and presses **Approve**. The cashier stays signed in the whole time.

**Why it exists.** It puts a manager on every action that moves money or closes a shift's figures — a refund, a big discount, the Z-read — without the cashier having to sign out and the manager sign in.

**How it works.**

1. The cashier's screen shows a box titled, for example, **Manager approval — refund**. Under the title is one line saying what is being approved (for example *Reverse this sale and return the money to the customer.*) and, usually, the specific receipt, item or amount.
2. The manager types their **Manager username** and **Manager password**.
3. The manager presses **Approve** (or **Cancel** to refuse).
4. If the details are accepted, the box closes and the action goes ahead. Where it applies, the manager's name is shown — for example *Approved by Peter Mollel* on a reversed receipt, or *Discount approved by …* under the discount.

The rules the ERP enforces every time:

- **A different person.** Nobody can approve their own action. If a manager is the one signed in, they cannot approve by typing their own password — they get *That user is not allowed to approve this action.* In practice a manager who is signed in and already holds the right for a refund or a Z-read print is not shown the box at all: they are the manager it looks for.
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
| **Printing** a Z-read (any copy) when the person signed in does not hold the session-reconcile permission — for example a cashier printing a reprint | **Manager approval — Z-read** | the session-reconcile permission | Branch Manager |
| **Leave unresolved…** on an unfinished sale whose outcome is unknown | **Manager approval — leave a sale unresolved** | the invoice-void permission | Sales Manager, Branch Manager |

Notes on the table:

- **Refunds.** A supervisor who holds the invoice-void permission and is running a till shift themselves is not asked for a second approval when they reverse a sale. A cashier may only refund sales from **their own** open shift, even with an approval — on a colleague's sale the **Refund / reverse** button does not appear at all, so you will not be called over to approve a refund the ERP would then refuse. To reverse such a sale, either the cashier who rang it does so from their own shift (with your approval), or you do it yourself from a till where **your own** shift is open. See the *Receipts and Refunds* chapter (Chapter 6).
- **Discounts.** The discount limit is set per company in the ERP and is **off** unless your administrator switches it on. The till does not know the limit; the ERP checks it when the sale is completed. See the *Selling — Supermarket* chapter (Chapter 3).
- **Drawer reports.** By default the Cashier role *can* read its own X-read and Z-read, so the report approvals only appear if your shop has removed that right from cashiers. **Printing** a Z-read is a manager's job. A user who holds the session-reconcile permission (a Branch Manager, by default) prints it straight away with no approval box — they *are* the manager the box would ask for, so a shop with only one manager on duty can still print its Z-read. Anyone else, such as a cashier printing a **Z-read (reprint)**, sees the **Manager approval — Z-read** box and needs a Branch Manager to approve.
- **Unfinished sales.** The till first asks the ERP one last time whether the sale went through. An approval is only asked for when the ERP still cannot say.
- **Age-restricted items** are not a manager approval: the cashier confirms the customer's age at **Complete sale** in the **Age-restricted items** box. **Cancel** always stops the sale (*Sale stopped: age not verified.*). A user whose role holds the age-override permission (Branch Manager by default) also sees a third button, **Override without check**, which completes the sale without the age confirmation. A manager cannot approve the override for a cashier — only the person signed in can use it.

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
   | **Payouts** | Cash paid out of the drawer (shown as a deduction), with a split such as *Refund (1)*, *Paid out (2)* and *Expense (1)*. Till expenses have their own **Expense** line. |
   | **Expected** | Float + cash sales − payouts. |
   | **Counted** | What was counted at close. |
   | **Variance** | Counted − Expected. Shown in red if the drawer was short. |
   | **N invoices** | How many sales the shift rang. |

5. To print it, tap **Print**. Because you hold the reconcile permission, it prints straight away — no approval box.
6. Tap **Finish shift**. The till returns to the **Open shift** screen and is free for the next shift.

### Reprinting a Z-read

After reconciling on the till, **☰** › **Z-read (reprint)** (*The final figures for a reconciled session*) shows the same figures again, marked as a reprint: *This is a reprint. The figures are identical to the original — the Z-read is read-only and posts nothing.* Before reconciliation the row reads *Available once the session is reconciled*. Printing the reprint needs no approval if you hold the reconcile permission yourself, or if a manager already approved opening that copy; otherwise it asks for a manager's approval (**Manager approval — Z-read**).

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

**What it is.** A record of money taken *out* of the drawer mid-shift. There are two kinds: **Paid out** (for example a drop to the safe) and **Refund** (cash handed back to a customer). A business expense paid from the drawer is recorded separately, as a **Till expense** (below). Any cash that leaves the drawer must be recorded, or the expected cash — and therefore the variance — will be wrong at close.

1. Open the session menu (**☰**).
2. Tap **Cash payout** (*Refund or drawer drop — reason required*). It is only available while the shift is open.
3. Choose **Paid out** or **Refund** (the box opens on **Paid out**).
4. Enter the **Amount (TZS)** and the **Reason (required)** — a few words saying what the cash is for. *A paid-out is booked to the ledger as an expense against the drawer, so the reason is what the entry is filed under.*
5. Tap **Record**. OrbixPOS confirms *Payout recorded.* or *Payout recorded and posted to the ledger.*

> A **Refund** payout is also the way to give money back when a whole-sale reverse is not possible — for example a return from a shift that has already closed. OrbixPOS does **not** do partial or single-line refunds; see the *Receipts and Refunds* chapter (Chapter 6).

### Till expense — business costs paid from the drawer

**What it is.** A record of cash paid out of the drawer for something the business needs — transport, cleaning, a small repair — filed under a **category**. It reduces the expected cash exactly like a payout, but because it carries a category, it appears as its own **Expense** line on the X-read and Z-read and is posted to the ledger under its category, so you can see what till money was spent on without reading every paid-out reason.

**Who can record it.** Anyone whose role holds the till-expense permission — the standard Cashier role does. The **Till expense** row (*Cash paid out for the business — by category*) is only shown to those users, and only works while the session is open.

1. Open the session menu (**☰**) and tap **Till expense**.
2. Enter the **Amount (TZS)**.
3. Under **Category (required)**, tap one of **Transport**, **Cleaning**, **Repairs**, **Meals**, **Utilities** or **Stationery**, or type another category (2 to 40 letters).
4. Under **What was it for? (required)**, say in a few words what was bought.
5. Tap **Record**. OrbixPOS confirms *Expense recorded and posted to the ledger.*

> **Check the categories.** Ask cashiers to use the quick-pick categories wherever they fit. A category typed differently each time (for example *Taxi*, *taxi fare*, *Boda*) splits the same kind of cost into several categories. It also helps to have cashiers keep the seller's paper receipt for you.

### Reviewing a shift's sales

- **Today's sales** (in the session menu) lists **today's** till sales at **this branch** from the **ERP** — every sale since midnight on the till's clock, from any till at the branch, newest first. Each line shows the receipt number, the time, the cashier who rang it, and the total. A reversed sale stays in the list, marked **· Reversed**, and reprints as REVERSED. If nothing has been sold yet it reads *No sales at this branch today.* Tap any line to reprint it; the reprint's **CASHIER:** line names the cashier who rang the sale. Reprinting never creates a new sale and never opens the cash drawer.
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

**Sales agents look after themselves.** Every sale is credited to the person signed in at the till. A cashier who has no sales-agent record gets one automatically on their first sale, named after them, so there is nothing to set up. An administrator can still create or rename the record in the ERP web app (**Parties → Sales Agents**). Archiving a cashier's sales agent is how you stop them selling: the till then refuses their sales until the agent is reactivated.

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
| The sale is refused: *Your sales agent record is no longer active…* | That cashier's sales agent was archived. If they should sell, ask your administrator to reactivate it in **Parties → Sales Agents**. |
| The sale is refused: *No sales agent could be determined for this sale…* | The user is not an active member of this company (or is the root account). Check their user account and company membership in the ERP web app. |
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
| Record a business expense paid from the till | Session menu → **Till expense** | Cashier |
| Review / reprint a sale | Session menu → **Today's sales** or **Recent receipts** | Anyone signed in |
| Close (count) a drawer | Session menu → **Close session** | Cashier (own shift) |
| Free a till a cashier left open | ERP web app → **POS Sessions** → **Close Session** | Branch Manager |
| Reconcile (post the variance) | ERP web app → **POS Sessions** → **Reconcile** (or on the till, for your own shift) | Branch Manager |
| Set up a cashier to sell | ERP web app | Administrator |
