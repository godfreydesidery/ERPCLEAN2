# Receipts and Refunds

This chapter explains what happens after you take payment: the receipt that OrbixPOS shows you, how to print it, how to give a customer a price-free **gift receipt**, how to find and reprint an earlier receipt, and how to refund a whole sale when something has gone wrong. It also explains — clearly — what OrbixPOS does **not** let you do, and what to use instead.

If you have not yet read the chapter on ringing a sale and taking payment, read that first. This chapter picks up the moment the sale is finished and the receipt appears.

---

## 1. The receipt screen

**What it is.** The receipt screen is the small printed-style slip that pops up the instant a sale completes. Its header reads **Sale complete** with a green tick. Below that is a black-on-white slip — the company name, the branch, the invoice number, the lines you sold, the totals, and how the customer paid — laid out exactly as it would print on paper.

**Why it exists.** The receipt is the customer's proof of purchase and your proof that the sale was recorded. It is built from the **finalised invoice** that the ERP server sent back — not from the figures you saw on the till while ringing the basket. This matters: the prices, VAT and totals on the screen while you were scanning are a helpful preview, but the server is the authority on money. Once the sale finalises, the server returns the official invoice, and the receipt screen shows *that*. We call the finalised invoice the **receipt of record** — it is the single, true version of what was sold.

**When it happens.** The receipt screen appears automatically after a successful payment. You do not have to ask for it. It also appears whenever you reprint an earlier sale (see Section 4).

**How it works.** OrbixPOS takes the finalised invoice from the server, formats it as a slip, and shows it to you in a dialog with action buttons along the bottom. Nothing on this screen changes the money — printing, showing a gift receipt, or reprinting are all read-only. The only button that changes anything is **Refund / reverse** (Section 5), and that one is carefully controlled.

### 1.1 Reading the receipt

The slip is laid out top to bottom like a paper till roll:

| Line on the slip | What it means |
|---|---|
| Company name (large, centred) | The trading company this sale belongs to. |
| Branch name (centred, under it) | The shop or outlet you are working in. |
| **Invoice** | The invoice number the server assigned, e.g. `INV-2026-000042`. This is the unique reference for the sale — quote it on any query. |
| **Date** | The date and time the sale was finalised, in your local time. |
| **Cashier** | Your name (the signed-in user). |
| **Customer** | The customer's name if one was attached, otherwise a customer reference. A walk-in sale may show a default customer. |
| The line items | One block per product: the product name on its own line, then the quantity × unit price on the left and the line total on the right. If a discount was applied to a line, a green **less disc** line shows the amount taken off. |
| **Net** | The total before VAT. |
| **VAT** | The tax added. |
| **TOTAL** | The grand total the customer owed, shown with the currency, in bold. |
| Tender lines | One line per payment method used — **Cash**, **Card**, **Mobile** (mobile money), **Cheque** — each with the amount taken on it. A split sale shows several. |
| **Change** | Shown only when the customer paid cash and was owed change. |
| Thank you! | The footer. |

> **Note.** Quantities print without decimals for whole numbers (for example a quantity of `3`) and with decimals for weighed or fractional goods (for example `0.750` kg). This comes straight from the invoice, so it always matches what the customer was charged.

> **Tip.** If a line shows `(line detail not loaded)` instead of the products, the totals are still correct and the receipt is still valid — only the itemised breakdown could not be fetched on this device. Reprint it from **Today's sales** (Section 4.1) to pull the full detail from the server.

### 1.2 Closing the receipt

When you are done with the receipt, press **Done** (or the **✕** close button in the top-right corner). This clears the screen and returns you to the register, ready for the next customer. Pressing **Done** does **not** cancel or change the sale — the sale is already recorded on the server. It simply puts the slip away.

---

## 2. Printing a receipt

**What it is.** **Print** sends the receipt to the receipt printer attached to your till.

**Why it exists.** Most customers want a paper receipt, and many places are legally required to give one. Print is the everyday button you reach for at the end of almost every sale.

**When it happens.** Press **Print** whenever the customer wants paper — usually straight away on the receipt screen, but you can also reprint older receipts later (Section 4).

**How it works.** Press **Print** on the receipt screen.

1. The receipt screen is showing (it appears automatically after a sale, or after you reprint one).
2. Press **Print** (the button with the printer icon, on the left of the action row).
3. The slip is sent to the printer.

> **Important — about printing in this build.** The receipt printer is not yet wired to a real driver. In the current release, pressing **Print** shows a confirmation message — *"Sent to printer (peripheral stub)."* — to confirm the action worked, but no paper comes out yet. Real printer support is coming. Until then, if a customer needs paper today, follow your store's interim procedure (for example, your administrator may have a separate printing arrangement). The receipt data itself is complete and correct; only the physical print step is pending.

---

## 3. Gift receipts (hiding prices)

**What it is.** A **gift receipt** is the same receipt with all the money removed — no net, no VAT, no total, no tenders, no change. It lists what was bought but not what it cost.

**Why it exists.** When someone buys a present, they do not want the recipient to see the price. A gift receipt lets the recipient return or exchange the item (your store policy permitting) without ever learning what was paid for it.

**When it happens.** Give a gift receipt when a customer asks for one — usually for a present. You can switch any receipt to gift view on the spot.

**How it works.** Press **Gift receipt** on the receipt screen.

1. With the receipt showing, press **Gift receipt** (the button with the gift-card icon).
2. The slip redraws with the prices hidden. In their place you see *"\* gift receipt — prices hidden \*"*.
3. Press **Print** to print this price-free version for the customer to put in the gift.
4. To bring the prices back, press the same button — it now reads **Show prices**. The slip returns to the full priced view.

> **Note.** Switching to gift view changes only what is displayed and printed. It does **not** change the sale, the totals, or anything on the server. The full priced invoice is still the record of the sale. You can flip between **Gift receipt** and **Show prices** as often as you like.

---

## 4. Reprinting an earlier receipt

Sometimes a customer comes back later — they lost the slip, or the printer jammed, or they need a copy for their records. OrbixPOS gives you two ways to find an earlier receipt and print it again. Both open through the **Session** menu (press the **☰** menu button in the top bar).

**The golden rule:** reprinting **never creates a new sale**. It just shows the same finalised invoice again. The customer is not charged a second time, stock is not touched, and no new invoice number is created. You can reprint a receipt as many times as you need.

There are two sources, for two situations:

| Source | Where it looks | Use it when |
|---|---|---|
| **Today's sales** | The ERP server | You want any of today's sales from **this branch**, even one rung on a different till or by a different cashier. Needs a network connection. |
| **Recent receipts** | This device only | You want a sale that was rung **on this till**, and you may be offline. Works without the network. |

### 4.1 Today's sales (look up on the server)

**What it is.** A list of every sale finalised today for your branch, fetched live from the server.

**Why it exists.** It lets you find and reprint a receipt even if it was not rung on your own till — for example, a customer who paid at a different register and lost their slip.

**How it works.**

1. Press the **☰** menu in the top bar to open the **Session** drawer.
2. Press **Today's sales** ("Look up & reprint a receipt").
3. OrbixPOS fetches the day's invoices from the server. Each row shows the invoice number, the time it was finalised, and the total.
4. Tap the sale you want.
5. OrbixPOS loads the full finalised receipt from the server and shows the receipt screen.
6. Press **Print** (or **Gift receipt** then **Print**) as normal.

> **Note.** **Today's sales** needs the network — it reads from the server. If the connection is down, use **Recent receipts** instead (Section 4.2), or try again when you are back online. If the list shows *"No sales yet."*, no sales have been finalised today for this branch.

### 4.2 Recent receipts (this device, works offline)

**What it is.** A list of the receipts that were rung **on this device**, kept locally on the till itself.

**Why it exists.** The network is not always up. Because OrbixPOS keeps a copy of each receipt it produces on the device, you can still reprint a recent one even with no connection — perfect for a customer who returns minutes after a sale during an outage.

**How it works.**

1. Press the **☰** menu in the top bar to open the **Session** drawer.
2. Press **Recent receipts** ("Reprint from this device (offline)").
3. A list headed **Recent receipts (this device)** appears — each row shows the invoice number, the date and time, and the total.
4. Tap the receipt you want.
5. The receipt screen opens.
6. Press **Print** (or **Gift receipt** then **Print**) as normal.

> **Note.** **Recent receipts** only contains sales rung **on this particular till**. A sale rung on another register will not be here — use **Today's sales** for those (when you have a connection). If the list shows *"No receipts on this device yet."*, this till has not completed any sales since the device's local history was last cleared.

> **Tip.** Reprints from either source open the *same* receipt screen with the *same* buttons, so you can print, switch to a gift receipt, or — if it is allowed — refund the sale, exactly as you could when it was first rung.

---

## 5. Refunding (reversing) a whole sale

**What it is.** A **refund / reverse** cancels an entire sale that has already been recorded. It undoes the whole thing: it gives the money back, puts the stock back, and unwinds the tax and revenue on the server.

**Why it exists.** Mistakes happen — the wrong item was scanned, a customer changes their mind right after paying, a basket was rung twice. Reversing the sale is the clean, fully-accounted way to put everything back exactly as it was, with an audit trail of who did it and why.

**When it happens.** You reverse a sale from its receipt screen, while the till session that rang it is **still open** (that is, before the shift has been closed and reconciled). Because reversing is sensitive, it is **supervisor-gated** — only a user whose role includes the refund permission can do it. If you do not have that permission, the **Refund / reverse** button does not appear at all; ask a supervisor.

**How it works.** Press **Refund / reverse** on the receipt screen.

1. Open the sale you want to reverse. This can be the receipt that just appeared after a sale, or one you reprinted from **Today's sales** or **Recent receipts** (Section 4).
2. Press **Refund / reverse** (the red button with the undo arrow). It appears only when reversing is allowed (see the conditions below).
3. A confirmation box headed **Reverse this sale?** appears. It explains: *"This voids the whole sale and reverses revenue, VAT, cash and stock. Allowed while the session is open."*
4. Type a short **Reason** — say what happened, e.g. "wrong size, customer swap" or "rang twice in error". A reason is good practice and goes into the audit record. (If you leave it blank, a default note is used.)
5. Press **Reverse** to confirm, or **Cancel** to back out.
6. On success you see *"Sale reversed."*, and the slip is stamped **\*\*\* REVERSED \*\*\*** in red. The money, stock, VAT and revenue are all unwound on the server, and the cash for this sale automatically drops out of your drawer's expected total — so you do **not** need to record a separate cash payout for it.

### 5.1 When the Refund / reverse button is available

The **Refund / reverse** button only shows when **all** of these are true:

| Condition | Why |
|---|---|
| You have the refund permission (`POS.SALE.VOID`) | Reversing is supervisor-gated. Without it the button is hidden. |
| The session is still **open** | The reversal puts the cash back into the *open* drawer. Once the shift is closed/reconciled, the cash is settled and the till can no longer absorb it. |
| The sale has **not already** been reversed | A sale can only be reversed once. After it is stamped **REVERSED**, the button is gone. |
| The invoice is not already void | You cannot reverse something that is already cancelled. |

If the button is missing, check these in order. The most common reasons are: you are a cashier without supervisor rights (ask a supervisor), the shift has already been closed (the sale must be handled by the back office now — see the next note), or the sale was already reversed.

> **Note — a sale from a session that has already been closed.** If the original shift has already been closed and reconciled, OrbixPOS cannot reverse the sale at the till, because the drawer it belonged to is settled. In that situation the sale has to be cancelled in the back-office ERP instead, where the cash difference is treated as a reconciliation matter. Ask your supervisor or store manager to handle it from the office system.

> **Important — the refused-message case.** If you press **Reverse** and the server refuses (for example because the session has just closed, or the sale was not a till sale), OrbixPOS shows the reason in a short message and the sale is left untouched. Read the message; it tells you what to do next.

---

## 6. What OrbixPOS does *not* do — partial and single-line refunds

This is an important limit to understand, so you are never stuck mid-transaction.

**OrbixPOS cannot refund part of a sale.** There is **no** way to refund a single line, refund one item out of five, or refund part of the quantity. The **Refund / reverse** button is all-or-nothing: it reverses the **whole** sale or nothing.

So what do you do when a customer wants to return just one item out of a larger basket? You have two correct options, depending on your store's policy:

| Situation | What to do |
|---|---|
| Customer returns **one item** from a multi-item sale, and the sale's session is still open | **Reverse the whole sale** (Section 5), then **ring a fresh sale** for the items the customer is keeping. The net effect is that only the returned item is refunded. |
| You just need to **hand cash back** that is not tied to a specific reversible sale (a goodwill cash-back, or a return for a sale from an already-closed shift) | Record a **cash payout** of type **Refund** instead (Section 7). |

> **Tip.** The "reverse the whole sale, then re-ring the rest" approach keeps the books perfectly accurate, because each step is a complete, properly-accounted transaction. It feels like extra steps, but it is the right way, and the idempotency safety on sales means re-ringing is safe.

---

## 7. The cash-drawer refund payout (the alternative)

**What it is.** A **cash payout** records cash physically leaving the drawer. One of its types is **Refund** — money handed back to a customer that is **not** linked to reversing a particular sale.

**Why it exists.** Sometimes you must give cash back but there is no open, reversible sale to undo — for example, a customer returns goods bought during a shift that has already closed, or your manager authorises a goodwill cash-back. A refund payout keeps your drawer honest: it tells the system that cash left, so when you count the till at close, the figures still add up.

**When it happens.** Use a refund payout only when the proper whole-sale reversal (Section 5) is not available or not appropriate. If the sale is reversible (its session is still open and you have permission), **always prefer Refund / reverse** — it handles cash *and* stock *and* tax, which a payout does not.

**How it works.** Record it from the **Session** menu.

1. Press the **☰** menu in the top bar to open the **Session** drawer.
2. Press **Cash payout** ("Refund or drawer drop").
3. At the top, choose the type. Pick **Refund** for cash returned to a customer. (The other type, **Paid out**, is for a drawer-to-safe drop or petty-cash payout — not a customer refund.)
4. Enter the **Amount** (in your currency).
5. Type a **Reason** — say why the cash is leaving, e.g. "Cash refund, returned goods, ref INV-2026-000042". Quote the original receipt number if you have it.
6. Press **Record**.
7. You see *"Payout recorded."*. The amount is now subtracted from the cash your drawer is expected to hold at close.

> **Warning — what a refund payout does *not* do.** A refund payout is **cash bookkeeping only**. It does **not** put stock back, it does **not** reverse VAT or revenue, and it is **not** linked to any invoice. It only keeps your drawer's expected cash correct. If the goods are coming back into the shop and the sale could be reversed, use **Refund / reverse** (Section 5) instead — a payout is the fallback for when that is not possible.

---

## 8. Quick reference and troubleshooting

| What you see | What to do |
|---|---|
| The receipt screen appeared after payment | The sale is recorded. Press **Print** for paper, **Gift receipt** to hide prices, then **Done** to move on. |
| A customer wants a present receipt with no prices | Press **Gift receipt**, then **Print**. Press **Show prices** to switch back. |
| A customer lost a receipt from earlier today (any till) | **☰** menu › **Today's sales** › tap the sale › **Print**. Needs the network. |
| A customer lost a receipt and you are offline | **☰** menu › **Recent receipts** › tap the sale › **Print**. Works without the network (this till's sales only). |
| You reprinted a receipt — did the customer get charged again? | No. Reprinting never creates a new sale and never charges anyone. |
| You need to undo a whole sale, shift still open, and you have permission | On the receipt, press **Refund / reverse**, enter a reason, press **Reverse**. |
| **Refund / reverse** button is missing | You may lack the supervisor permission (ask a supervisor), the shift may already be closed (back office must handle it), or the sale was already reversed. |
| Customer wants to return just one item from a bigger sale | Reverse the whole sale, then re-ring the items they are keeping (Section 6). |
| You must hand cash back but there is no reversible sale | **☰** menu › **Cash payout** › type **Refund** › enter amount and reason › **Record** (Section 7). |
| **Print** showed "Sent to printer (peripheral stub)" but nothing printed | Expected in this build — the physical printer driver is not wired yet. The receipt data is correct; follow your store's interim printing procedure. |
| The line items show "(line detail not loaded)" | Totals are still correct. Reprint from **Today's sales** to pull the full breakdown from the server. |

> **Remember the three rules of this chapter:**
> 1. The receipt is built from the **finalised invoice** — the server's official record of the sale.
> 2. **Reprinting never creates a new sale** and never charges the customer.
> 3. Refunds at the till are **whole-sale only** and **supervisor-gated**, while the session is open; for anything else, reverse-and-re-ring or record a **Refund** cash payout.
