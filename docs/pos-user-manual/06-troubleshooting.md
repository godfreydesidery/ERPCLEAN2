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
