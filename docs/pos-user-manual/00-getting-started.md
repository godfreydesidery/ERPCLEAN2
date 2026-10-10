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

**What it is.** OrbixPOS for Windows is delivered as a single zip file named like `OrbixPOS-1.6.0+13-windows.zip`. There is no installer: you unzip the folder and run the program inside it.

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

> **Which version is installed?** The folder contains a `README.txt` that names the version and what changed (the full history is in `Docs\RELEASE-NOTES.txt`). You can also right-click `pos_app.exe`, choose **Properties**, open the **Details** tab and read **Product version**. OrbixPOS also shows its version on screen, in small grey text — on the sign-in screen just under the **Server setup** link, and at the bottom of the **Session** menu — for example **OrbixPOS 1.6.0+13**. When reporting a problem, always say which version the till runs.

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
