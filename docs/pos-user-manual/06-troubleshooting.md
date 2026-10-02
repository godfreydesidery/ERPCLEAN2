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
