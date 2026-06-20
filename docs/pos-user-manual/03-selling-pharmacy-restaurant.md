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
