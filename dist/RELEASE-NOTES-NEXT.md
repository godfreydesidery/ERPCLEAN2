### Receiving goods no longer adds VAT twice

If you type costs straight from a supplier invoice that already includes VAT, the Goods Received
Note used to add VAT *again* on top, so its total never matched the invoice you were checking it
against.

There is now a setting for this. Go to **Purchase Settings**, find **"When receiving goods, the cost
I enter is…"**, and choose:

- **VAT included** — if you copy costs from an invoice that already has VAT in it. The note will show
  the VAT contained *inside* your cost, and the total will equal the invoice.
- **Before VAT** — if you enter costs without VAT. The note adds it. This is how the system behaved
  before, and it stays the setting until you change it.
- **Not a VAT matter** — if you are not VAT registered. No VAT is shown on the note at all.

**Nothing in your accounts changes** whichever you pick. This affects only what is printed: a goods
receipt has never posted VAT — that comes from the supplier's bill.

### Item Inquiry: one place to answer "what is this item?"

A new screen under Stock. Type an item code, part of a name, or scan a barcode, and it tells you the
description, department, supplier, unit, how many are left, what it costs you and what you sell it
for — without opening three screens.

You can also **pick one item from the dropdown** and see just that item on its own.

It is a lookup only: you cannot change anything from this screen, which is deliberate. It is meant
to be safe to open with a customer standing in front of you.

### Ordering now shows what you already have

When you add a line to a purchase order, the system now shows how much of that item is already in
stock, in which locations, before you decide how much to order.

### The goods received list answers who, when and how much

The list used to show only the receipt number and date, so you had to open each one to find the
supplier and the value. Supplier and amount are now columns on the list itself.

### Stock transfers: value, unit price, and cartons

Transfers now record and show what the goods are worth:

- Each line shows the **price of one** and the **line value**, with a total.
- The printed transfer carries your **company logo**, and says **who printed it and when**.
- You can transfer in **cartons or boxes** instead of counting single pieces, as long as the item has
  a pack size recorded. Choose the unit on the line and the system converts it for you.

Previously every transfer showed a blank unit and a value of 0.00, which read as though the goods
being moved were worth nothing.

### Profitability report

A new report showing, for the period you choose: gross sales, discount, net sales, VAT, cost and
profit — with margin and markup percentages, **grouped by department**.

### Where a figure is not known, the system now says so

You will see a dash instead of a number in a few places. This is on purpose.

If an item was sold or moved before anybody recorded what it cost, the system cannot work out the
profit on it. It used to treat that cost as zero, which reported the whole sale as profit and made
margins look far better than they were. It now leaves those figures blank and tells you how many
items it had to leave out.

**Expect some profit totals to be lower than you are used to.** That is the correction, not a loss —
the old figure was counting profit that had not been earned. To settle an item for good, give it a
cost: receive stock at a cost price, or set an opening cost.

### Before you use the new screens

Some of the new columns will be empty until the information exists on each item:

- **Department** and **Supplier** in Item Inquiry, and the department grouping on the profitability
  report, come from each item's record. Set them under **Product Master**.
- **Cartons on transfers** need the item's pack size recorded — how many pieces a carton holds.
