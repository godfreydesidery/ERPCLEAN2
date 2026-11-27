# Getting Started

Welcome to OrbixPOS — the till app you use to ring up sales, take payment, and print receipts. This chapter is for a first-time cashier or waiter and for whoever sets up the till. It explains what OrbixPOS is and the devices it runs on, how to install it on a Windows till or an Android device, how to connect it to your ERP server and your receipt printer, how to sign in (and what to do when sign-in fails), how to read the screen, the three business modes, and how to sign out.

You do not need to read it cover to cover before your first shift. A cashier can skim the headings and read **Signing In** and **The Screen Layout**. The person setting up the till should work through **Installing OrbixPOS on a Windows till** or **Installing OrbixPOS on an Android device**, **Trusting Your Server's Certificate** (only if the server address starts with `https://`), and **First-Run Setup** once.

---

## What OrbixPOS Is

**What it is.** OrbixPOS is the front-of-counter till program. You use it to scan or pick items, take a customer's payment (cash, card, mobile money, cheque, a split of several, or a complimentary give-away), and hand over a printed receipt. In a restaurant it also runs the floor: tables, kitchen and bar slips, and bills. It is the screen you will spend your whole shift on.

**Why it exists.** A shop needs a fast, reliable till that any cashier can pick up in minutes. OrbixPOS is built for exactly that: a clean, scanner-first counter screen that does the ringing and the paying, and leaves the bookkeeping to the central system behind it.

**When you use it.** Every shift, from the moment you sign in and open your till to the moment you count your drawer and close it at the end of the day.

**How it works.** OrbixPOS does not keep your shop's prices, stock, or customers itself. It is a *client*: every time you add an item or take a payment, it asks your ERP server — the central system your shop runs on — over the network, and shows you what the server says. This matters in one important way:

> The server is the single source of truth for price, VAT (tax), totals, and your cash variance. The money you see on screen **before** you take payment is a helpful preview, not the final word. The figures that matter — and the printed receipt — come from the finalised invoice the server sends back **after** you take payment. If a preview total and a printed total ever differ, the printed receipt is correct.

Because OrbixPOS leans on the server for everything important, it needs to know where that server is. That is the first thing you set up. (A till that your manager has allowed to *sell offline* can keep taking simple cash, mobile money and card sales while the server cannot be reached — see the *Selling Offline* chapter, Chapter 9.)

> **Your branch's own menu and prices.** The till shows only the items your branch sells, at your branch's prices. A company with several branches can sell some items at one branch only, or charge a different price at each branch; the till always follows the branch you are signed in to.

### The devices it runs on

OrbixPOS is one app that runs on Windows tills, on Android POS devices, tablets and phones, and (where your supplier provides it) in a web browser. The screens, buttons and steps in this manual are the same everywhere; the screen simply rearranges itself to fit (see **Phones and tablets** below). What differs is how each device prints:

| Device | Receipt printer | Kitchen and bar printers (restaurant) | Cash drawer |
|---|---|---|---|
| **Windows desktop till** | A receipt printer installed in Windows (USB or network), usually an 80 mm or 58 mm thermal printer. | A network printer set up in the ERP, or a printer plugged into this PC. | Plugged into the receipt printer's drawer port; opens when a sale's receipt prints. |
| **Android POS device, tablet or phone** | A **paired Bluetooth printer**. The built-in printer of most Android POS devices (Sunmi, iMin, Telpo and similar) shows up as a paired Bluetooth printer — on Sunmi it is called *InnerPrinter*. A standalone Bluetooth receipt printer works the same way once it is paired in Android's Bluetooth settings. | A network printer set up in the ERP, or a paired Bluetooth printer. | Opens only if the printer has a drawer port and the drawer is plugged into it. |
| **Web browser** | None — the web version cannot print receipts, bills, slips or drawer reports. | None. | None. |

> **About the barcode scanner.** A USB scanner on a Windows till works as a *keyboard wedge*: it simply "types" the barcode into whatever field is focused and presses Enter, exactly as if you had typed it yourself very fast. The built-in scanner of an Android POS device works the same way. You do not configure the scanner in OrbixPOS — you just make sure the right field is focused (the app does this for you on the sell screen) and scan. On Android the on-screen keyboard does not pop up after every scan; tap the search box when you want to type a name.

> **About the printer and cash drawer.** OrbixPOS prints receipts, bills and drawer reports straight to the receipt printer chosen in **Setup & diagnostics** (see **Setting up the receipt printer** below). A cash drawer plugged into the printer's drawer port can be opened automatically when a sale's receipt prints. Weighing scales are not connected to OrbixPOS; weighed items come through weight barcodes or a typed quantity.

### Phones and tablets

OrbixPOS fits phones, tablets, Android POS devices and every common till screen. On a narrow screen — a phone, or a tablet held upright — the screens rearrange themselves:

- the top bar hides the branch and session chips (the session number is still in the **Session** menu), and the mode switch becomes a small drop-down showing the current mode;
- the **Open shift** screen stacks the mode cards and puts **Open session** full width;
- the supermarket register becomes one column: the search box at the top, the basket as cards, and the customer, **Hold**, **Recall** and **PAY** at the bottom; the number pad opens with the keypad button and folds away again;
- the pharmacy register puts the total and **PAY** under the lines;
- the restaurant order screen shows the menu and the order as two tabs, **Menu** and **Order (N)**;
- the payment screen is one column that scrolls, with **Complete sale** always in view.

A phone held sideways shows the controls beside the basket instead. Everything else works exactly as described in this manual.

---

## Installing OrbixPOS on a Windows Till

*This section is for the person who sets up the till.*

**What it is.** OrbixPOS for Windows is delivered as a single zip file named like `OrbixPOS-1.14.1+37-windows.zip`. There is no installer: you unzip the folder and run the program inside it.

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

> **Which version is installed?** The folder contains a `README.txt` that names the version and what changed (the full history is in `Docs\RELEASE-NOTES.txt`). You can also right-click `pos_app.exe`, choose **Properties**, open the **Details** tab and read **Product version**. OrbixPOS also shows its version on screen, in small grey text — at the bottom of the sign-in screen, and at the bottom of the **Session** menu — for example **OrbixPOS 1.14.1+37**. When reporting a problem, always say which version the till runs.

> **One setup per Windows user.** OrbixPOS remembers its settings for the Windows user account that ran it. If the till PC has more than one Windows login, do the First-Run Setup under each login that will run the till.

> **Only one OrbixPOS at a time.** Opening OrbixPOS while it is already running brings the open till to the front instead of starting a second copy.

---

## Installing OrbixPOS on an Android Device

*This section is for the person who sets up the device.*

**What it is.** OrbixPOS for Android is delivered as one app file named like `OrbixPOS-1.14.1+37-android.apk`. It runs on Android POS devices (Sunmi, iMin, Telpo and similar), Android tablets and phones.

**Before you start.** If the device will print, pair the printer first in Android's **Bluetooth** settings — a built-in printer of an Android POS device is usually already listed there (for example as *InnerPrinter*). Make sure the device is on the same network (Wi-Fi) as the ERP server.

To install:

1. Copy the APK file to the device and open it.
2. If Android asks, allow installing apps from this source, then install.
3. Open **OrbixPOS**. The first time, Android asks to allow **Nearby devices** — answer **Allow**, or printing cannot reach the Bluetooth printer.
4. Carry on with **First-Run Setup** below: tap the version text at the bottom of the sign-in screen seven times to open **Server setup**.

To upgrade, install the new APK over the old one. Your server setup, printer setup and sign-in are kept. The version shows at the bottom of the sign-in screen and of the **Session** menu.

> **A plain `http://` server works on Android too**, for example `http://192.168.1.10:8081` on the shop's own network.

---

## Trusting Your Server's Certificate (https only)

*This section is for the person who sets up the till. Skip it if your ERP address starts with `http://`.*

**What it is.** When the ERP address starts with `https://`, the connection is encrypted and OrbixPOS checks the server's security certificate before it will talk to it. A server with a normal public certificate is trusted automatically. Many shops' servers, however, use a **private certificate** issued by their own certificate authority — common when the server has no public web address. OrbixPOS will refuse to connect to such a server until it is told to trust that authority.

**Why it matters.** Until the certificate is trusted, the till simply cannot reach the server: **Test connection** reports **Could not reach the ERP at this host.** even though the server is perfectly healthy. Trusting the authority fixes this without switching off any security check.

**How it works.** Your administrator gives you the server's *root certificate* — a small file ending in `.pem` or `.crt`. Some servers run by the OrbixPOS team are already trusted by the app; your administrator will tell you if no file is needed.

To trust a server's certificate on a **Windows** till:

1. Close OrbixPOS.
2. In the OrbixPOS folder (the one holding `pos_app.exe`), create a folder named `certs`.
3. Copy the certificate file into `certs`. If this till talks to more than one server, put one file per server in the same folder — any number of `.pem` and `.crt` files is fine.
   - As an alternative to the `certs` folder, you can name a single file `erp-ca.pem` and place it directly beside `pos_app.exe`.
4. Start OrbixPOS again. It reads the certificate files only when it starts, so a file added while it is running has no effect until you restart it.
5. Open **Server setup** and press **Test connection** (see below). It should now turn green.

> **On Android** there is no `certs` folder to fill. An Android device reaches a server with a public certificate, a server whose certificate is already built into the app, or a plain `http://` address on the shop's network. If an `https://` server with a private certificate cannot be reached from an Android device, ask your administrator which address to use.

> **Use the server name the certificate was issued for.** Type the ERP host exactly as your administrator gives it — usually a name, not a number. A certificate issued for a name does not match the server's numeric IP address, and the connection is refused.

> **If a working till suddenly cannot connect over https.** If the server was rebuilt, it may have created a new certificate authority, and the old file no longer matches. Ask your administrator for the new root certificate, drop it into `certs`, and restart OrbixPOS. No reinstall is needed.

> **For IT staff.** On Windows, certificate files can also be supplied through the `POS_ERP_CA_FILE` environment variable (one or more file paths, separated by `;`). This is added to the `certs` folder and `erp-ca.pem`, not instead of them.

---

## First-Run Setup — Pointing the Till at Your Server and Printer

**What it is.** A one-time step where you tell OrbixPOS the network address of your ERP server — called the **ERP host** — and which receipt printer to use.

**Why it exists.** OrbixPOS owns no data — it calls the server for everything. Until it knows the server's address it cannot sign you in or ring a single sale, and until it knows the printer it cannot print.

**When it happens.** Once, when OrbixPOS is installed on a new device — typically done for you by the person who set up your till. You will only need it yourself if you are setting up a brand-new device, if the till says it cannot reach the server (for example after a network change), or if the printer changes.

**How it works.** You open the **Setup & diagnostics** dialog from the sign-in screen, type the host, press **Test connection**, choose the printer settings, press **Test print**, and **Save**. Everything is remembered on the device, so you do not repeat it every day.

> **Setup is only on the sign-in screen, and it is hidden.** So that nobody changes the server or printer by accident, there is no Server setup button in normal use: tap the version text at the bottom of the sign-in screen (for example *OrbixPOS 1.14.1+37*) **seven times** in quick succession. From the fourth tap the till says how many taps are left (for example *3 more taps for Server setup.*); taps more than two seconds apart start the count again. The one exception: when the till cannot reach the ERP at all, a **Server setup** link appears below **Sign in** by itself, so a till pointed at the wrong server can always be put right.
>
> To change the server or printer during a shift, sign out (your shift stays open), open **Server setup**, make the change, **Save**, and sign in again. OrbixPOS takes you straight back to your open shift.

### Setting the ERP host

1. On the sign-in screen, tap the version text at the bottom seven times in quick succession (or tap the **Server setup** link, if the till has just failed to reach the ERP). The **Setup & diagnostics** dialog opens, with the line "Point the till at your ERP server and receipt printer."
2. In the **ERP host** field, type the address your administrator gave you. Type the scheme and the server **only** — `http://` or `https://`, the server name or IP address, and a port if your administrator gave one. For example:
   - `http://192.168.1.10:8081` — a server on your shop's network;
   - `http://localhost:8081` — the ERP running on this same PC;
   - `https://erp.yourshop.co.tz` — a server with its own web address.

   Do **not** add `/api/v1` or any other path after the address — the dialog reminds you: "The /api/v1 path is added automatically." Typing `/api/v1` yourself makes the till look in the wrong place and the test fails. A trailing slash is harmless; the till removes it.
3. Tap **Test connection**. The till tries to reach the server and tells you the result:

| What the dialog says | What it means | What to do |
|---|---|---|
| **Reachable — ERP is UP.** (green) | The till reached the server and the server is healthy. | Carry on to the printer settings, then **Save**. |
| **Reached host, status unclear.** (red) | The till reached *something* at that address, but it did not answer as a healthy ERP. | The server may still be starting — wait a minute and test again. If it persists, check the address and port with your administrator. |
| **Could not reach the ERP at this host.** (red) | The till could not get an answer from an ERP at that address. | Check for typos, make sure you did not add `/api/v1`, check the network cable or Wi-Fi, and — for an `https://` address — check the certificate (see the previous section). Then ask your administrator whether the server is running. |

> When the dialog first opens, the ERP host may already show `http://localhost:8081`. That is only correct if the ERP runs on the till PC itself; otherwise replace it with your server's address.

### Setting up the receipt printer

Below the host, the **Receipt printer** section holds four settings.

| Setting | What to choose |
|---|---|
| **Printer** | The receipt printer. On Windows the list shows the printers installed in Windows. On Android it shows the **paired Bluetooth printers**, each with its name and address (for example *InnerPrinter · 00:11:22:33:44:55*). **— none —** means no printer: the till still sells, but **Print** only reminds you to set one up. |
| **Paper width** | **80 mm · 48 cols** or **58 mm · 32 cols** — match the paper roll in the printer. The wrong width makes lines wrap or look squashed. |
| **Print mode** | **Thermal (ESC/POS + cut)** for a thermal receipt printer — it prints and cuts the paper. **Plain text** for any other printer that accepts plain text; it prints the text and ejects the page, with no cut. |
| **Open cash drawer after printing** | Tick this if a cash drawer is plugged into the printer's drawer port. The drawer then opens when a sale's receipt prints for the first time, straight after the sale. It does not open on a reprint, a gift receipt, a second copy or a reversed sale, and drawer reports (X-read, Z-read) never open it. It works only with **Thermal (ESC/POS + cut)** mode. |

If the printer list is empty, a line under it explains why:

- on Android: "No paired printers. Pair the printer in Android's Bluetooth settings, allow "Nearby devices" for OrbixPOS, then open this screen again.";
- on the web version: "No printers detected (Windows desktop only)." — the web version cannot print.

Then check it:

1. Tap **Test print**. A sample receipt is sent to the chosen printer. You will see **Test sent to …** followed by the printer's name. If you have not picked a printer you will see **Choose a printer first.**
2. Check the paper: the text should be clean, the lines should fit the paper width, and (in thermal mode) the paper should be cut. If you ticked the drawer option, the drawer should open.
3. If the test page prints strange symbols or nothing at all, see the printer section of the *Troubleshooting* chapter (Chapter 7).

> **Android: try each paired printer.** Some Android POS devices list more than one paired printer. Try **Test print** on each and keep the one that prints. A Bluetooth printer takes one device at a time, so make sure no other phone or till is connected to it.

Restaurant kitchen and bar printers are not set here; they are chosen in the restaurant's **Settings** on the floor (see the *Selling — Pharmacy and Restaurant* chapter, Chapter 4).

### Saving

Tap **Save**. The dialog closes and the host and printer settings are stored on this device together. Tap **Cancel** instead to leave everything unchanged.

> **Save does not check the connection.** The dialog lets you save an address that failed its test. Always get a green **Reachable — ERP is UP.** before you save.

> **Offline sales block a server change.** If this till still holds sales it made offline that have not reached the server, **Save** refuses a new ERP host with a message such as *2 offline sales are still waiting for the current server. Sync them before changing the server.* Sync them first (Chapter 9).

---

## Signing In

**What it is.** Signing in proves who you are to the system. You give the username and password your administrator created for you (a waiter may use a 4-digit PIN instead); the system checks them and starts your personal session. From that moment on, everything you ring is recorded against *you*.

**Why it exists.** Only named people should be able to take money and open a till, and the shop needs to know which cashier or waiter did what. Signing in is what ties each sale, each drawer, and each receipt to a real person.

**When it happens.** At the start of every shift, and again any time your session ends — for example if you sign out, or if the server ends the session.

**How it works.** When your details are accepted, OrbixPOS loads your details, the company and branch you work in, and what you are allowed to do (your permissions). Actions you are not permitted to use simply do not appear, so you never see a button you cannot use. Then:

- if you already have a shift open on a till (for example the app was closed or the power went off mid-shift), OrbixPOS takes you **straight back to your register** in that shift;
- a **waiter** (a login that takes restaurant orders but does not run a till) goes straight to the restaurant **Floor** — there is no shift to open;
- a **kitchen** login goes straight to the **Kitchen printer** screen (Chapter 4);
- everyone else lands on the **Open shift** screen, where you pick your mode and till and start your shift.

To sign in with a username and password:

1. Open OrbixPOS. The **Sign in** screen appears, headed "Sign in" with the line "Open your till and start your shift."
2. In the **Username** field, type the username your administrator gave you. (Press **Enter** to move to the password.)
3. In the **Password** field, type your password. It is hidden as you type.
4. Tap **Sign in** (the large button), or press **Enter** in the password field.

The button shows a brief spinner while the till checks your details, then the next screen opens. Opening a shift is covered in the next chapter.

> **A till without a keyboard.** Tap **Show keyboard** under the **Sign in** button to bring up on-screen keys (letters, a **?123** key for numbers and symbols, and **space**); **Hide keyboard** folds them away.

### Waiters: tap your name

In a restaurant, the sign-in screen also shows the branch's **Waiters** as tiles — "Tap your name, then enter your PIN or password." (The tiles show everyone with the **Waiter** role at this branch. The till refreshes the list each time someone signs in on it, so a new waiter appears after the next sign-in.)

1. Tap your name.
2. **If you have a PIN**, a number pad opens with your name and "Enter your PIN". Type your four digits — you are signed in as soon as the fourth digit is in. A wrong PIN shows a message and the pad clears for another try. **Use password instead** takes you to the password box; **Cancel** backs out.
3. **If you have no PIN** (or chose **Use password instead**), your username fills in and the on-screen keyboard comes up. Type your password and press **Sign in** on the keys.

With more than six waiters a **Search waiters** box appears above the tiles; with more than twelve the tiles become compact and an **A–Z** strip appears — tap a letter to see only the names starting with it, or **All** to see everyone.

> **PINs are for waiters only.** Cashiers and managers always sign in with their password, and a PIN can never approve anything a manager approves. A manager sets, resets or removes a waiter's PIN in the ERP (**Users**, the waiter's **POS PIN**); a waiter can change their own on the till (Session menu → **Change my PIN**, see Chapter 4). Easy PINs such as 1111 or 1234 are refused, and five wrong PINs in a row lock that waiter out for 15 minutes, the same rule as passwords.

> **If OrbixPOS was closed without signing out**, it may sign you straight back in when it reopens, without asking for your password, as long as your sign-in is still valid. Always sign out when you leave the till for good.

> Keep your password and PIN to yourself. Because every sale and every drawer count is recorded against the person signed in, never sign in for a colleague or let someone use your session.

### Signing in when the server cannot be reached

If the till cannot reach the ERP and it is allowed to sell offline, a red box appears under **Sign in**: **No connection to the ERP** — "If you signed in on this till while it was online in the last 14 days, you can sign in offline and keep selling. Sales are kept on the till and sent once you sign in online again." Type your username and password (or tap your tile and enter your PIN) and tap **Sign in offline**. See the *Selling Offline* chapter (Chapter 9).

### Sign-in problems

If something is wrong, a red banner appears just above the **Sign in** button with a short message. Here is how to read the common ones:

| What you see | What to do |
|---|---|
| A red banner saying your details were not accepted | Re-type your username and password carefully (mind the Caps Lock key). If it still fails, or the message says your account is locked, ask your administrator. |
| **Your session ended. Please sign in again.** or **Your session has expired. Please sign in again.** | The server ended your previous session. Just sign in again. Nothing you finalised is lost, and an open shift is still open. |
| **Cannot reach the ERP. Check the connection and host.** | The till cannot reach the server. Tap the **Server setup** link that appears below **Sign in**, press **Test connection**, and follow the table above. |
| **The server certificate was rejected.** | The till does not trust the server's https certificate. See **Trusting Your Server's Certificate** above. |
| **The server did not respond in time. The request may or may not have completed.** | The network or server is slow. Wait a moment and sign in again. |
| **This user is not assigned to any company.** or **No branches found for …** | Your account is not yet attached to a company or branch. Ask your administrator. |
| **You do not have permission for this action.** right after signing in | Your account lacks one of the basic rights the till needs to start (for example, to see your branch). Ask your administrator to check your role. |
| An amber strip: "Using branch … — we couldn't confirm your usual branch. Check this is correct before selling." | The till could not confirm your usual branch and picked another. Make sure the branch named is the shop you are standing in before you sell; if it is wrong, sign out and ask your administrator. |
| **The server is back. Sign in to send the offline sales and carry on.** | You were signed in offline and the server answers again. Sign in as usual; the offline sales are then sent (Chapter 9). |

> **Every sale is credited to you.** Each sale is recorded against the person signed in at the till, as their own sale. There is nothing to set up first: if your account has never sold before, the system creates your sales-agent record for you on your first sale. The shop's top-level super-admin account is not a salesperson and cannot ring sales — sell with the personal cashier account your administrator gave you, never a shared admin login. If a sale is refused with a message about a sales agent, see the *Troubleshooting* chapter (Chapter 7).

---

## The Screen Layout

Once you have signed in and opened a shift, you spend your shift on the **register** (the sell screen). The middle and lower part of this screen changes with your business mode — that is covered under **The Three Business Modes** below. The strip along the very top, the **top bar**, is the same in every mode.

From left to right, the top bar contains:

- **The OrbixPOS brand** — the diamond mark (◆) and the word **OrbixPOS** on the far left. It is just a label (hidden on a phone).
- **The branch chip** — a small pill with a shop icon showing the **branch** you are working in, with your **company** name beside it. A branch is the specific shop or location this till belongs to. You do not switch branches from here.
- **The session chip** — a green pill reading **Session** followed by your session number (for example `POS-0001`). It confirms your till session is open. If it shows a dash (**—**) instead of a number, the till reopened your shift without being able to load its details — see the *Starting and Ending a Shift* chapter (Chapter 2).
- For a waiter, a **Waiter · Orders only** chip instead; for a kitchen login, **Kitchen printer · Prints slips**.
- **The mode switcher** — a pill-shaped switch in the centre showing the business modes (**🛒 Supermarket**, **💊 Pharmacy**, **🍽 Restaurant**). The mode you are in is highlighted. On a narrow screen it is a drop-down showing the current mode (tooltip **Change mode**). A till set to *Restaurant only* shows only **🍽 Restaurant**, and a waiter or kitchen login has no mode switcher at all.
- **The session menu button** — the **☰** icon toward the right (tooltip **Session menu**). Tap it to open the **Session** panel that slides in from the right. This holds the shift actions — **Offline sales**, **X-read**, **Cash payout**, **Till expense**, **Today's sales**, **Sales dashboard**, **Add a product**, **Recent receipts**, **Close session**, **Reconcile (Z-read)** and **Z-read (reprint)**, plus **Change my PIN** for a waiter — each one only if your account is allowed to use it. These are covered in Chapter 2.
- **Your avatar** — a small round badge on the far right showing your initials, so you can see at a glance who is signed in.

On a screen narrower than a full till (a tablet held upright, a phone), the branch and session chips are left out to make room.

> **Messages.** Short messages ("toasts") appear for a few seconds near the bottom of the screen — dark for information and problems, green when something succeeded. Read them; the *Troubleshooting* chapter (Chapter 7) explains each one.

> **Coloured strips under the top bar.** A strip may appear just under the top bar: amber **No connection to the ERP.** when the server cannot be reached, red **OFFLINE — sales are kept on this till until the server is back** while the till sells offline, or a blue line counting offline sales still waiting to be sent. See Chapter 9.

> **Slow connection.** When the network is slow, a request can take up to about 45 seconds. After 5 seconds of waiting, an amber line appears — **Slow connection — still waiting for the ERP…** — under the supermarket search box, on the payment screen and on the restaurant screens. Wait for it to go away by itself; do not tap again or close the app.

> The **Session** panel (opened from **☰**) also has its own **Sign out** button at the bottom.

---

## The Three Business Modes

**What they are.** OrbixPOS comes in three flavours of sell screen — called **business modes** — each tuned for a different kind of shop. The way you take payment, print receipts, and run your session is identical in all three; only the *register* (how you add items to the sale) changes.

**Why they exist.** A grocery cashier wants to scan barcodes fast; a pharmacy needs patient and prescription details; a restaurant works in tables and order tickets. One till would feel wrong for all three, so OrbixPOS gives each its own register while keeping everything else the same — so the skills you learn in one mode carry straight over.

**When you choose a mode.** You pick a mode when you open your shift (covered in the next chapter), and you can switch between modes during your shift using the switcher in the top bar — as long as you are not in the middle of a sale.

**How it works.** The three modes are:

| Mode | Best for | What the register looks like |
|---|---|---|
| **🛒 Supermarket** | Fast, scanner-first grocery checkout | A wide, Excel-style grid of sale lines on the left with a number pad on the right. You scan or type a code and the item drops in; you can adjust quantity and discount inline. Category buttons let you browse by department. |
| **💊 Pharmacy** | Dispensing with prescriptions | A patient/prescriber header (patient, prescriber, prescription number) above a dispensing line table, with the running totals down the side. |
| **🍽 Restaurant** | Table service | The floor of tables and takeaways; orders stay open on the ERP, **Send** prints kitchen and bar slips, **Bill** prints the bill, **PAY** closes the order. |

> **Restaurant-only tills.** A manager can set a till to *Restaurant only* (in the restaurant **Settings**, Chapter 4). That till then shows only the Restaurant register, for everyone who signs in on it, and a new shift opens in Restaurant mode.

### Switching mode

1. Make sure the current sale is finished or cleared — you cannot switch mode while a sale has items in it.
2. In the top bar, tap the mode you want (**🛒 Supermarket**, **💊 Pharmacy**, or **🍽 Restaurant**), or pick it from the drop-down on a narrow screen. The new mode highlights and the register changes immediately.

> If you try to switch while a sale has items in it, OrbixPOS will not switch and shows the message **"Finish or clear the current sale before switching mode."** Complete or clear the sale first, then switch.

---

## Signing Out

**What it is.** Signing out ends your sign-in and returns OrbixPOS to the sign-in screen, ready for the next person.

**Why it exists.** Because every action is recorded against the signed-in person, you should sign out when you step away or finish, so nobody can ring sales under your name.

**When you do it.** At the end of your shift — normally after you have closed your session — or any time you are leaving the till and want to secure it.

**How it works.** Signing out clears your sign-in on this device and shows the **Sign in** screen again. It does not undo any sale: every sale you finalised is safely on the server. There are three places to sign out:

1. **From the Open shift screen** (before you have opened a session): tap the **Sign out** icon (the door-with-arrow icon) at the top right, next to your name.
2. **From the Session panel** (any time during your shift): tap the **☰** session menu button in the top bar, then tap **Sign out** at the bottom of the panel.
3. **A waiter on the Floor**: tap **Sign out** at the top of the floor.

> **Signing out does not close your shift.** If you sign out with the drawer still open, the shift stays open on the server, and the next time you sign in OrbixPOS takes you straight back into it. At the end of the day the right order is: **Close session** (count the drawer) → **Reconcile (Z-read)** (a supervisor step) → **Sign out**. These steps are covered in the *Starting and Ending a Shift* chapter (Chapter 2).

> **Signing out while offline.** If the till is offline, or still holds offline sales waiting to be sent, a **Sign out?** box explains what that means first (only people who signed in on this till while it was online in the last 14 days can sign in again until the connection is back). The offline sales stay safely on the device. Tap **Sign out** to go ahead, or **Back**.

---

## Where to Go Next

Now that you can connect the till, sign in, read the screen, and choose a mode, you are ready to start your day:

- **Opening your shift** — picking a till, entering your opening float, and getting back into a shift that is still open (Chapter 2).
- **Ringing a sale** — adding items in each mode, and running the restaurant floor (Chapters 3 and 4).
- **Taking payment** — cash, card, mobile money, cheque, complimentary and split payments, and what to do when you are not sure a sale went through (Chapter 5).
- **Receipts** — printing, gift receipts, reprints, refunds and item returns (Chapter 6).
- **Troubleshooting** — every message, its cause, and the fix (Chapter 7).
- **Supervisors and managers** — approvals, reconciling, tills and staff set-up (Chapter 8).
- **Selling offline** — what to do when the internet is down (Chapter 9).
