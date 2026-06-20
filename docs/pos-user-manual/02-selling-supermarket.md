# Selling — Supermarket

This chapter walks you through ringing up a sale on the **Supermarket** register: the fast, scanner-first till mode built for grocery and convenience shops. By the end you will be able to add items by scanning, searching, or typing a code; fix a quantity or apply a line discount; void or remove a line; clear an age check; choose a customer; and finish with **PAY**.

Before you can sell, you must be signed in and have an **open shift** (a cash session on a till). If you have not done that yet, see the *Starting and Ending a Shift* chapter (Chapter 2). Switching register modes is blocked once a sale is in progress, so the till stays in the mode you opened the shift in until the basket is empty.

> **A note that runs through this whole chapter.** Every amount you see on screen *before you take payment* is a **preview** — a helpful estimate that lets you and the customer see roughly what is owed. The ERP server is the authority for price, VAT, discounts and the final total. When you press **PAY** and complete the sale, the server re-calculates everything and returns the real figures; the printed receipt is built from that finalised invoice, not from the preview. So if a previewed price looks slightly off, the figure on the receipt is the one that counts.

---

## 1. The supermarket screen at a glance

**What it is.** The supermarket register fills the screen with two areas side by side: a wide **line grid** on the left (a spreadsheet-style list of everything in the basket) and a narrower **number pad** panel on the right.

**Why it exists.** Grocery checkout is high-volume and fast. The grid keeps the whole basket visible at a glance like a till roll, and the number pad lets you fix a quantity or discount without leaving the keyboard. The layout is designed so most sales need nothing but the scanner and the **PAY** button.

**When you use it.** Every supermarket-style sale, from a single chocolate bar to a full trolley.

**How it is laid out.**

| Area | Where | What it does |
|---|---|---|
| Search / scan bar | Top of the left side | Where scans land and where you type a code or name. It stays focused so a scanner can fire straight into it. |
| Line grid | Left, below the search bar | One row per basket line, with columns **# · Code · Item · Unit · Qty · Price · Disc · Total** and two action columns. |
| Total card | Top-right | The big running **TOTAL**, the line and item counts, and the "preview — ERP is authoritative" reminder. |
| Customer chip | Right, under the total | Shows who the sale is for. Defaults to **Walk-in**. Tap to change. |
| Qty / Disc toggle | Right, mid-panel | Chooses what the number pad will set on the selected line. |
| Number pad | Right, lower | Digits, decimal point and backspace, plus **Clear** and **Set qty** / **Set disc**. |
| **PAY** button | Bottom-right, green | Opens payment for the basket. |

> **Tip.** When the basket is empty the grid shows a shopping-cart icon and the words **Scan or search to add items**. That is your cue that nothing has been added yet.

---

## 2. Adding items

There are three ways to put a product in the basket, and the search bar handles all three. You do not pick a method — you just scan or type, and the till works out what you meant in this order: first it checks for an exact product code, then it asks the server for a barcode match, and finally it does a name/code search.

> **The search bar is always listening.** After every item you add, the field clears itself and the cursor returns to it automatically, ready for the next scan. You should rarely need to click into it.

### 2.1 Scan a barcode

**What it is.** Reading the product's barcode with the handheld scanner.

**How it works.** The scanner behaves like a fast keyboard: it "types" the barcode into the focused search bar and presses Enter for you. You do not press any button — just aim and pull the trigger.

1. Make sure the basket screen is showing (the search bar shows a small scanner icon on the left).
2. Point the scanner at the product's barcode and scan.
3. The item drops into the grid as a new line and the search bar clears, ready for the next scan.

If you scan the **same** ordinary product twice, the till does not create two rows — it adds to the quantity on the existing line and keeps one tidy row.

> **Note.** The scanner is a plug-in USB device that types codes into whichever field has focus. If a scan seems to "go nowhere", click once inside the search bar so it has focus, then scan again.

### 2.2 Scale labels with an embedded weight or price

**What it is.** Some products — loose fruit, meat, cheese — are weighed at a deli scale that prints a special barcode label. That label has the weight (or the priced amount) baked into the barcode itself.

**Why it exists.** You cannot type a weight for every banana bunch. The scale already weighed it, so the label carries that information and the till reads it straight off the scan.

**How it works.** When you scan a scale label, the server decodes it and tells the till both the product *and* the weight or amount printed on it:

- An **embedded-weight** label sets the line's quantity to the weighed amount (for example 1.250 kg) automatically.
- An **embedded-price** label sets the line's price to the amount printed on the label.

1. Scan the scale label exactly as you would any barcode.
2. The item is added as its **own** line with the weight or price already filled in — it is never merged into another line, because each weighed pack is unique.
3. Carry on scanning the next item.

> **Tip.** You do not edit the weight on a scale-label line by hand. If the label is wrong, weigh the item again and scan the new label, then remove the old line (see *Removing a line* below).

### 2.3 Search by code or name

**What it is.** Finding a product by typing part of its code or name — useful when there is no barcode, the barcode will not read, or you are not sure of the exact item.

**How it works.** As you type, a drop-down list of matches appears under the search bar showing each product's **code**, its **name**, and an age badge if it is restricted.

1. Click into the search bar (or just start typing — it is usually already focused).
2. Type part of the product **code** or **name**, for example `milk` or `BR-0123`.
3. A results list appears. Tap the product you want; it is added to the basket and the field clears.

If your text matches **exactly one** product, the till adds it straight away without showing the list. If nothing matches, you get a short message: *No match for "…"*.

### 2.4 Type an exact code

**What it is.** Keying in a product's exact code and pressing Enter — handy when you know the code and want the fastest possible add.

**How it works.** If what you type is an exact, known product code, the till adds that product immediately — it does not wait for the search list.

1. Click into the search bar.
2. Type the exact product code.
3. Press **Enter** (or tap the indigo **+** button at the right end of the bar). The item is added.

> **What the + button does.** Pressing **Enter** and tapping **+** do the same thing: they submit whatever is in the search bar through the same code → barcode → search logic described above.

---

## 3. Working with lines in the grid

Each row in the grid is one **line** — one product, at one unit, with a quantity, a price preview, an optional discount, and a line total.

### 3.1 Reading a line

| Column | What it shows |
|---|---|
| **#** | The line number (1, 2, 3 …). |
| **Code** | The product code. |
| **Item** | The product name, with an orange age badge (**18+** / **21+**) if it is restricted. |
| **Unit** | The unit of measure (for example PCS, KG). |
| **Qty** | How many. Shows decimals for items sold by weight; whole numbers otherwise. |
| **Price** | The previewed unit price. A dash (—) means the price is still being fetched. |
| **Disc** | The line discount amount. A middle dot (·) means no discount. |
| **Total** | The previewed line total (quantity × price, minus any discount). |
| (blank) | The **remove** column — a small **×**. |
| **V** | The **void** column — a tick box. |

### 3.2 Selecting a line

**What it is.** Picking the line you want to work on. The selected line is highlighted, and the number pad always acts on it.

**How it works.** Tap anywhere on a line to select it. To edit a quantity or discount, tap directly on that line's **Qty** or **Disc** cell — doing so both selects the line *and* points the number pad at that field (the **Qty** / **Disc** toggle on the right flips to match, and the cell shows a soft highlight).

1. Tap the line you want.
2. The row highlights and the number pad is now aimed at it.

> **Tip.** If you tap the **Qty** cell, the number pad is set to change quantity. If you tap the **Disc** cell, it is set to change the discount. You will see the small **× Qty** / **− Disc** toggle on the right switch accordingly.

### 3.3 Changing the quantity

**What it is.** Setting how many of an item the customer is buying.

**How it works.** You type the new quantity on the number pad and press **Set qty** — this *replaces* the quantity (it does not add to it).

1. Select the line (tap its **Qty** cell to point the pad at quantity).
2. Make sure the toggle on the right reads **× Qty**.
3. Key the quantity on the number pad — for example `3`, or `1.250` for a weighed item (use the **.** key for the decimal).
4. Press **Set qty**. The line's **Qty** and **Total** update.

> **Note.** A quantity of zero or less is not allowed — the till keeps the quantity at a minimum of 1. To take the item off the sale entirely, remove the line instead (see below).

### 3.4 Applying a line discount

**What it is.** Taking a money amount off a single line — for example knocking TZS 500 off a slightly damaged pack.

**Why it exists.** Cashiers sometimes need to adjust a single item's price for a markdown or a goodwill gesture, without touching the rest of the basket.

**How it works.** The discount is a **money amount**, not a percentage. You type the amount on the number pad and press **Set disc**; the line total drops by that amount.

1. Select the line and tap its **Disc** cell (or flip the right-hand toggle to **− Disc**).
2. Key the discount amount on the number pad.
3. Press **Set disc**. The **Disc** column shows the amount in indigo and the **Total** drops.

To remove a discount, set it back to `0` and press **Set disc** again.

> **Warning.** A discount can never make a line total go below zero — the previewed total floors at zero. As always, the server has the final say on whether a discount is allowed; the preview is only a guide.

### 3.5 Clearing the number pad

If you mistype a number before pressing **Set qty** / **Set disc**, press **Clear** to wipe the number pad display back to `0`, or use the backspace key (the ⌫ key in the bottom-right of the digits) to delete one digit at a time.

### 3.6 Void versus remove — two different columns

These two action columns look similar but do different things, and it matters which you use.

| Column | Icon | What it does | Use it when |
|---|---|---|---|
| **Remove** (blank header) | **×** | Deletes the line completely — it disappears from the grid. | You added the wrong item, or you want it gone entirely. |
| **Void** (**V** header) | tick box | Marks the line **voided** — it stays visible, struck through and greyed out, but is left out of the total and out of the sale sent to the server. | The customer changed their mind but you want a record on screen that the item was scanned, or you may un-void it again. |

1. To **remove** a line, tap the **×** in the line's last-but-one column. It vanishes.
2. To **void** a line, tap the tick box in the **V** column. The line greys out with a line through it and stops counting toward the total. Tap the box again to bring it back.

> **Tip.** A voided line is *not* charged — it is excluded from the total and from what gets sent to the server when you take payment. Voiding is the safe way to "park" an item without deleting it.

---

## 4. Age-restricted items

**What it is.** Some products — alcohol, tobacco and similar — carry a legal minimum age. On the till these show an orange badge (**18+** or **21+**) next to the product name, both in the search list and on the basket line.

**Why it exists.** It is your responsibility, and the law's requirement, to confirm the customer is old enough before selling these items. The till makes that check an explicit, deliberate step so it cannot be skipped by accident.

**When it happens.** The badge is shown in the register so you know an item is restricted while you ring it, but the **confirmation prompt itself appears later** — on the **Payment** screen, when you press **Complete sale** (not when you press **PAY**). If the basket contains at least one restricted item, a dialog headed **Age-restricted items** appears before the sale can finish.

**How it works.** At **Complete sale**, the **Age-restricted items** dialog names the restriction (for example "This basket contains 18+ items") and asks you to confirm you have verified the customer's age. You check ID, then press **Age verified** to continue or **Cancel** to stop the sale (you will see *Sale stopped: age not verified.*).

> **Where the check is fully explained.** Because the prompt fires at checkout, the step-by-step is in the *Taking Payment* chapter (Chapter 5), under **Age-restricted items**. Only press **Age verified** when you have actually seen acceptable proof of age — the confirmation is recorded against the sale.

---

## 5. Choosing the customer

**What it is.** The **Customer** chip, under the total card on the right, names who the sale is for. Every sale has a customer; by default it is **Walk-in** — an anonymous cash customer — which is exactly right for most supermarket sales.

**Why it exists.** Some customers are registered (account holders, regulars on a loyalty scheme, businesses buying on terms). Attaching a registered customer ties the sale to their record. For a casual shopper, **Walk-in** is all you need.

**When you change it.** Only when the customer is a registered account you want this sale linked to. If in doubt, leave it on **Walk-in**.

**How it works.**

1. Tap the **Customer** chip on the right.
2. The **Customer** picker opens with a search box reading *Search name / code / phone…*.
3. Type part of the customer's **name**, **code**, or **phone number**. The list filters as you type.
4. Tap the customer you want. The picker closes and the chip now shows their name; a tick marks the currently selected customer in the list.

To go back to an anonymous sale, open the picker again and choose the walk-in entry (shown with a "walking person" icon and labelled **Walk-in**).

> **Tip.** You can set the customer before or after adding items — it does not affect what is in the basket.

---

## 6. Reading the running total

**What it is.** The dark **total card** at the top of the right-hand panel shows the live state of the basket: the big **TOTAL** in the shift's currency, then a line below it counting how many lines and how many items are in the basket.

**Why it exists.** It is the at-a-glance figure you read out to the customer and watch climb as you scan.

**How it works.** The total updates instantly every time you add, void, remove, re-quantity, or discount a line. Voided lines do not count. Underneath the total you will always see the small grey reminder **preview — ERP is authoritative**.

> **Remember.** This total is a preview. The amount the customer actually pays is the one the server returns when you complete the sale at the payment screen, and it is that finalised figure that prints on the receipt.

---

## 7. Finishing the sale — Pay

When everything is in the basket, you take payment.

1. Press the green **PAY** button at the bottom-right. (It is disabled while the basket is empty — add at least one item first; if you press an empty basket you will see *Add an item first*.)
2. The **Payment** screen opens. This is where you choose how the customer pays — cash, card, mobile money, cheque, or a split across several — and complete the sale.
3. If the basket has any age-restricted items, the **Age-restricted items** check (section 4) appears here before the sale can complete.
4. When the sale completes, the basket clears, the receipt is built from the finalised invoice, and the receipt view opens so you can print it.

Taking payment, change, split tenders, the safe-retry behaviour for an unknown outcome, and printing the receipt are all covered in the *Taking Payment* chapter (Chapter 5) and the *Receipts and Refunds* chapter (Chapter 6).

> **Note.** Pressing **PAY** does not yet charge anything — it only opens the payment screen. The sale is only made when you complete it there.

---

## 8. Quick troubleshooting

| What you see | What to do |
|---|---|
| The basket shows **Scan or search to add items** and nothing happens when you scan. | The search bar may not have focus. Click once inside it, then scan again. |
| A scan shows **No match for "…"**. | The code is not in this company's catalogue, or the barcode did not read cleanly. Try searching by name, or type the exact product code and press Enter. |
| You typed a number and nothing changed on the line. | You must press **Set qty** or **Set disc** to apply it. Also check a line is selected — if you see **Select a line first**, tap the line, then try again. |
| The **Price** column shows a dash (—). | The price preview is still loading from the server. It fills in a moment later; you can keep scanning meanwhile. The server prices the line for real at payment time regardless. |
| A line is greyed out with a line through it. | It is **voided** — it will not be charged. Tap its **V** tick box to bring it back, or its **×** to remove it for good. |
| The same product scanned twice but only one row appears. | That is correct for ordinary items — the quantity went up by one on the existing row. Check the **Qty** column. |
| **PAY** is greyed out. | The basket is empty (or every line is voided). Add at least one item. |
| Adding an item shows *"…: no usable unit configured."* | The product has no sellable unit set up. Ask your supervisor or administrator to fix the product's unit of measure; it cannot be sold until then. |
| The age-check dialog blocked the sale. | Verify the customer's age from photo ID. Press **Age verified** to continue, or **Cancel** and remove the restricted item. |

> **If a sale is refused with a message about a sales agent.** Every cashier who rings sales must have a sales-agent record linked to their user account. If you get a message that no agent is linked, you cannot ring sales until your administrator links one to you — ask them to do so. (The top-level super-admin account cannot be a sales agent and so cannot ring sales; use your own cashier login.)
