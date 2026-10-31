# Selling — Pharmacy and Restaurant

This chapter covers OrbixPOS's two other tills: the **Pharmacy** register (a dispensing counter with a patient and prescription header) and the **Restaurant** register (table service and takeaway, with orders kept on the ERP, kitchen and bar slips, and a bill before payment). It also covers how to **switch between modes** — and the guard that stops you switching in the middle of a sale.

If you work a supermarket checkout, see the previous chapter, *Selling — Supermarket* (Chapter 3). The way you take payment, print the receipt, and open and close your shift is **the same in all three modes** — only the part of the screen where you build the basket changes.

> **Read this first.** The Pharmacy and Restaurant registers ring real sales: payment, receipts, stock and the cash drawer work exactly as in the supermarket. The Pharmacy register has fewer tools than the supermarket till (no number pad, line discounts or void tick box, no prescription records). The Restaurant register works differently: orders stay open on the ERP while the table eats, slips go to the kitchen and bar, and the bill is printed before payment. The limits are listed at the end of each section so you are never surprised. If your shop is a supermarket, use the **Supermarket** register.

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
4. The picker closes and the **Patient** tile shows the chosen name. If medicines are already on the sale, their prices are read again **for this patient** — a registered customer may have their own prices.

After each sale the tile goes back to **Walk-in** by itself, so the next customer never inherits the last one's name or prices.

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
| Anything, while the network is down | **Can't reach the ERP — check the connection.** Nothing is added. The product may exist — check the network and try again. |

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
- No **Hold** / **Recall** — putting a sale on hold is on the Supermarket register only.
- No prescription register, no batch or expiry selection, no dosage labels.
- The patient, prescriber and Rx number are saved as a note on the sale but are not printed on the receipt.

---

## 3. The Restaurant register

The Restaurant register is for table service and takeaway. Orders are kept **on the ERP server**, not on the till, so every till, every waiter's tablet and the kitchen see the same orders. One table can stay open for the whole meal while other tables are served. If a till restarts, nothing is lost.

The flow is the standard one:

1. **Open an order** at a table (or as a takeaway).
2. **Add items** and tap **Send**: a slip prints at each station that makes something on the order — the kitchen for food, the bar or barista for drinks.
3. When the guests ask for the bill, tap **Bill**: the bill prints on **this till's receipt printer only**.
4. Tap **PAY** and take payment as usual. The order is closed and the table is free again.

> **Set up once, in the ERP.** A manager sets up the tables, the kitchen and bar stations and their printers, and which item categories go to which station in the ERP under **Point of Sale → Restaurant Setup**. The till reads all of that; nothing is typed on the till except which printer each station uses on *this* device (3.10).

### 3.1 The floor

When you open the Restaurant register you see the **Floor**: one tile per table with its name and seats, and its state.

- **Free** — no open order.
- **Occupied** — an order is open. A small number on the tile means more than one order is open at that table (for example two groups at a long table).
- **Bill printed** — the bill has been printed and the table is waiting to pay.

An occupied table also shows **who is serving it** (the waiter who opened the order), so the cashier knows whose bill it is at a glance.

On the right (above the tables on a tablet) is the **Open orders** list: every order not yet paid, tables and takeaways, each with its waiter, the number of items, and the bill amount once printed. Orders whose bill is printed come first. When more than one waiter has orders open, choose a name in **All waiters** to see only that waiter's orders. Tap an order to open it.

Along the top are **Settings** (3.10), **Refresh** (reloads the floor — useful when another till has opened or paid an order) and **New takeaway**.

If the floor says **No tables are set up for this branch**, a manager has not added tables yet; takeaway still works. If it says **Restaurant orders are not enabled for your login**, ask a manager to give you the restaurant permission.

### 3.2 Open an order

- **At a table:** tap a **Free** table. Type **How many?** guests (or skip) and the order opens. Tapping an **Occupied** table opens its order; if it has more than one, choose which, or choose **New order at this table**.
- **Takeaway:** tap **New takeaway**. To come back to it, tap it in **Open orders**.

The order screen shows the **menu** on the left and the **order** on the right (on a narrow tablet they are two tabs, **Menu** and the order). The order header shows the table (or *Takeaway*), the order number (for example `ORD-0012`), the number of guests and the waiter. **Floor** (top-left) goes back to the floor — the order stays open.

### 3.3 Add items

The menu tiles show each item's name and **price** (VAT included). Type in **Search the menu…** to find an item by name or code.

- **Tap** a tile to add one. Tap again to add another.
- **Long-press** a tile to add it with **choices** (for example *Milk: oat*, *No sugar*), a **course**, **Hold until fired**, or a **note for the kitchen**. Items that must have a choice (a manager sets these up as *modifiers*) open the choices on a normal tap.
- A choice with a price is added as its own line under the item (shown as **+ Extra cheese**), so the bill shows what it cost.

New items appear under **NEW — NOT SENT YET**. Until they are sent you can change them freely: **–** / **+** for the quantity, **✕** to remove, tap **Course N** to move the item to the next course, tap **Hold** to keep it back.

The total at the bottom says **Estimated** until the bill is printed; the ERP works out the exact figure.

### 3.4 Send to the kitchen and bar

Tap **Send (N)**. Each station that makes something on the order gets its own slip, with the table, the order number, who is serving, the items with their choices and notes, and the course. The items move under **SENT**, and a message confirms where they went (for example *Sent to Kitchen and Bar.*).

Only the new items are sent: adding a coffee later and tapping **Send** again prints a slip with just the coffee.

**If a slip does not print**, the till lists which station failed and why, with **Print again** for each. The order **is** sent — only the paper is missing. Reprint, or tell the station by voice. **More → Reprint last kitchen/bar slips** prints them again at any time.

If a station has no printer at all, nothing prints at the till: the station gets the order on the **Kitchen Display** in the ERP, or from a PC at the station that prints for every till (3.10).

### 3.5 Courses: hold and fire

To send starters now and mains later, give the mains **Course 2** (tap the course tag, or choose it on a long-press) and tap **Hold**. **Send** then sends only the starters. When the table is ready for the mains, tap **Fire (N)** (and choose the course if more than one is held): the held items are sent with a *COURSE 2* heading on the slip.

### 3.6 Change or void a sent item

A sent item cannot be edited — the kitchen already has it. Tap **Void** on the line, choose how many and a reason (**Wrong item**, **Customer changed mind**, **Food ready**…). If your login may not void, a manager approves it on the till with their own sign-in. A **VOID** slip prints at the station so they stop making it, and the item comes off the bill.

### 3.7 Bill and pay

- **Bill** prints the bill on this till's receipt printer (the receipt printer from *Setup*). If any items have not been sent yet, the till asks whether to send them first. The table turns **Bill printed** and the total reads **Bill**. If the printer fails, the bill is shown on screen so you can still tell the customer the total; **Reprint bill** prints it again.
- **PAY** opens the normal payment screen (Chapter 5) with the bill total; its title names the table, the order and the waiter. **Split evenly** (**÷2**, **÷3**, **÷4**) divides the amount into equal payments, each taken by any tender. When payment completes, the receipt prints, the order closes and you return to the floor. The receipt shows **WAITER** and **TABLE / ORDER** under the cashier.

If an item is added after the bill was printed, the order goes back to open and the bill must be printed again. If prices changed in the ERP since the bill, the payment is refused with a message — print the bill again.

### 3.8 Split, merge, move and cancel

All four are under **More** (⋮) in the order header.

- **Split the bill…** — choose items (and how many of each) to move to a **new check** at the same table; each check is billed and paid on its own. To split one amount equally instead, use **÷2 ÷3 ÷4** at payment.
- **Merge another order into this…** — tables pushed together, or a takeaway that sat down: the other order's items move into this one.
- **Move to another table** — the whole order moves; the old table is free.
- **Cancel order** — closes the order without a sale. If anything was already sent, it needs a reason and the void permission (or a manager's approval), and VOID slips print at the stations.

### 3.9 Waiters

There are two ways to work with waiters; both can run in the same restaurant.

- **Waiters on their own tablet or phone.** A login with the **Waiter** role opens straight onto the floor. A waiter opens orders, sends them and prints bills, but **PAY** is replaced by **Payment is taken at the cashier**: the cashier opens the same order on the till and takes payment. **Sign out** on the floor ends the waiter's session.
- **Waiters signing in on the till itself.** The sign-in screen shows the branch's waiters as **tiles**. A waiter taps their name — the username fills in — and types their password on the **on-screen keyboard**, which appears once a name (or a field) is tapped. With many waiters there is a **Search waiters** box and an **A–Z** strip: tap a letter to see only the names starting with it. On a shared till (below), a waiter signed in this way is signed out after 3 minutes without use, so the next waiter finds the tiles. The tiles come from the ERP each time someone signs in on the till, so a new waiter appears after the next sign-in.
- **Waiters sharing the cashier's till.** A manager chooses **Who is serving?** in **Settings** (3.10):
  - **Don't ask** — orders go to whoever is signed in on the till.
  - **Ask before a new order** — before opening a table or takeaway, the waiter taps their name and types their password. The top of the floor shows **Serving: Name**; the till forgets the waiter after 3 minutes without use, or when you tap **✕** on the chip.
  - **Ask every time a table is opened** — the waiter taps their name and types their password **every time** they open a table, a takeaway or an order from the list. Nothing is remembered. The cashier signed in on the till is a tile too (*Name (cashier)*) and confirms with their own password, so the till always knows who is standing at it.

  **Who is serving?** shows the same tiles, search and keyboard as the sign-in screen. The waiter's name is on the order, the slips and the bill.

A login with only the **Kitchen** role opens the **Kitchen printer** screen (3.10) instead of the floor.

### Whose table is it?

A table belongs to the **waiter serving it** — the one who opened it, or the one it was last moved to. Their name is on the table tile, the order, the bill and the receipt, and the waiter reports credit the bill to them.

- **Another waiter's table opens read-only.** A waiter who opens a colleague's table sees the order with the note **Served by Name — ask a manager to move the table to you**. They cannot add, send, void, bill, split, merge, move or cancel it.
- **Cashiers and managers can work any table** — to take payment, print the bill or fix a mistake.
- **Moving a table to another waiter** (a shift change, a break): open the order, then **More → Transfer to another waiter…** and tap the waiter taking it over. A cashier or manager does this alone; when a waiter does it, a manager approves on the till with their own sign-in. From then on the new waiter serves the table and is credited with its bill when it is paid.
- **Team service.** Some restaurants let any waiter serve any table. A manager can switch on **Team service** in **Settings** for that till; the tables then open normally for every waiter, and the bill is still credited to the waiter holding the table.

### 3.10 Settings — printers on this device

**Settings** on the floor opens **Restaurant settings — this device**. For each station (Kitchen, Bar…) it shows where its slips print from this till, with a **Test** button:

- **Printer:** leave it on the station's network printer from the ERP, or choose **This device — *printer name*** for a printer plugged into this computer by USB.
- **Paper:** 58 mm or 80 mm.
- **Print ALL *station* slips here, from every till** — tick this on the **PC the station printer is plugged into** (for example a kitchen PC with a USB printer). Keep OrbixPOS open on that PC: it fetches new slips from the ERP every few seconds and prints them, whichever till or tablet sent the order. The floor then shows **Printing for: all tills**.

A manager also sets **Who is serving?** and **Team service** here (3.9), and **Restaurant only — this till**: switched on, this till shows only the Restaurant register — Supermarket and Pharmacy are hidden for everyone who signs in on it, and a new shift opens in Restaurant mode.

These settings are kept on this device only.

### 3.11 What the Restaurant register does not do

- Line discounts and pack units are not available on restaurant orders.
- Table layout, stations, routing and choices are set up in the ERP, not on the till.
- The till cannot print to a printer it cannot reach: a network printer must be on the same network, and a USB printer must be on this computer or on a PC running OrbixPOS with **Print ALL slips here** ticked.


---

## 4. Quick reference

| Task | Pharmacy | Restaurant |
|---|---|---|
| Add an item | Scan, or type in **Scan or search a drug…** and press **Enter** (adds the first match) | Tap a tile; long-press for choices, course, hold or a note |
| Find an item | Type a code or name; check the line that was added | Type in **Search the menu…**; the grid updates |
| Set who/where the sale is for | Tap the **Patient** tile (customer picker); type **Prescriber** and **Rx #** | Tap a table on the **Floor**, or **New takeaway** |
| Change a quantity | **–** / **+** on the line | **–** / **+** before sending; **Void** after |
| Sell by box/pack | Tap the unit under the name → **Sell by** | Not available |
| Remove a line | **✕** at the end of the line | **✕** before sending; **Void** after (a VOID slip prints) |
| Fire the order to the kitchen | — | **Send (N)**; held courses with **Fire (N)** |
| Print the bill | — | **Bill** (this till's receipt printer) |
| Take payment | **PAY** (right-hand panel) | **PAY** (bottom of the order); **÷2 ÷3 ÷4** to split evenly |
| Switch register | Top-bar switcher — only when the sale is empty | Top-bar switcher — open orders stay on the ERP |

> **One more reminder.** The patient/prescriber/Rx details are saved as a **note** on the sale for the back office; they are not printed on the receipt and do not change the price. The ERP server is always the authority on price, VAT and totals.

---

*Next: see the *Taking Payment* chapter (Chapter 5) for tenders, split payment, change, age confirmation and the receipt; and the *Starting and Ending a Shift* chapter (Chapter 2) for opening, the session menu, closing and reconciling the drawer.*
