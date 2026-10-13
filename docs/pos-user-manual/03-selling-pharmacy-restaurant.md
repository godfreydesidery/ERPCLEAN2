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
