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
4. Tap the customer you want. The picker closes and the chip shows their name. A tick marks the currently selected customer in the list.

To go back to an anonymous sale, open the picker again and choose the walk-in entry (shown with a "walking person" icon and *· Walk-in* after its code).

> **If the chip says "Select customer".** No walk-in customer could be found for your company (or your account cannot read the customer list). When you press **PAY** the till shows *Select a customer before completing the sale.* and opens the picker for you. Choose a customer to carry on. Ask your administrator to set up a walk-in customer so this does not happen on every sale.

> **Tip.** You can set the customer before or after adding items — it does not affect what is in the basket.

---

## 6. Reading the running total

**What it is.** The dark **total card** at the top of the right-hand panel shows the live state of the basket: **TOTAL (TZS)** with the big figure under it, then a line such as *3 lines · 7 items*.

**Why it exists.** It is the at-a-glance figure you read out to the customer and watch climb as you scan.

**How it works.** The total updates instantly every time you add, void, remove, re-quantity, discount or change the unit of a line. Voided lines do not count. The prices already include VAT — whether your price list is entered with VAT included or without, the till shows the VAT-inclusive price, the same way the receipt will. Underneath you always see the small reminder **preview — ERP is authoritative**.

> **Remember.** This total is a preview. The amount the customer actually pays is the one the ERP returns when you complete the sale, and that finalised figure prints on the receipt.

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

> **If a sale is refused with a message about a sales agent.** Every cashier who rings sales must have a sales-agent record linked to their user account in the ERP. If you get a message that no agent is linked, you cannot ring sales until your administrator links one to you. (The top-level super-admin account cannot be a sales agent and so cannot ring sales; use your own cashier login.)
