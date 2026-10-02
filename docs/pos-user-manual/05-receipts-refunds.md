# Receipts and Refunds

This chapter explains what happens after you take payment: the receipt that OrbixPOS shows you and what each line on it means, how to print it, how to give a customer a price-free **gift receipt**, how to find and reprint an earlier receipt, and how to refund a whole sale with a manager's approval when something has gone wrong. It also explains — clearly — what OrbixPOS does **not** let you do, and what to use instead.

If you have not yet read the chapters on ringing a sale and taking payment, read those first. This chapter picks up the moment the sale is finished and the receipt appears.

> **This is a sales receipt, not a TRA fiscal receipt.** The OrbixPOS receipt is laid out like a familiar Tanzanian supermarket receipt, but it is an **ordinary sales receipt**. It is not produced by an EFD or VFD and it carries none of the fiscal markings — no "legal receipt" banner, no EFD serial or UIN, no Z number, no verification code and no QR code. It cannot be verified on the TRA portal. Your shop's EFD/VFD arrangement is separate from OrbixPOS: if a customer needs a fiscal receipt, issue it the way your shop normally does.

---

## 1. The receipt screen

**What it is.** The receipt screen pops up the instant a sale completes. Its header reads **Sale complete** with a green tick. Below that is a white slip showing the receipt **exactly as it prints** — the same words, the same columns — followed by the action buttons.

**Why it exists.** The receipt is the customer's proof of purchase and your proof that the sale was recorded. It is built from the **finalised sale** that the ERP server sent back — not from the figures you saw while ringing the basket. The prices, VAT and totals on the till while you were scanning are a preview; once the sale finalises, the ERP returns the official figures and the receipt shows *those*. This finalised sale is the **receipt of record** — the single, true version of what was sold.

**When it happens.** Automatically after a successful payment. It also appears whenever you reprint an earlier sale (section 4).

**How it works.** Nothing on this screen changes the money. **Print**, **Gift receipt** and reprinting only read the sale. The only button that changes anything is **Refund / reverse** (section 5), and that one needs a manager.

### 1.1 Reading the receipt

The slip is laid out top to bottom like a paper till roll:

| Part of the slip | What it means |
|---|---|
| Company name (capitals, centred) | The trading company this sale belongs to. |
| Company details (centred) | The address, town and region, country, **Tel:**, **Email:**, **TIN:** and **VRN:** — taken from your company's record in the ERP. Only the details that have been filled in are printed; if your TIN or VRN is missing, ask your administrator to add it to the company record. |
| Branch name (centred) | The shop or outlet you are working in. |
| **CUSTOMER NAME:** | The customer on the sale, for example *Walk-in Customer*, or *n/a* if no name is available. |
| **RECEIPT NO:** | The number the ERP gave the sale, for example `INV-2026-004218`. This is the unique reference for the sale — quote it on any query or refund. |
| **RECEIPT DATE:** / **RECEIPT TIME:** | When the sale was finalised, as day-month-year and hours:minutes:seconds. |
| **CASHIER:** | The name of the person who rang the sale — also on a reprint (see the note in section 4). |
| **Description · Qty · Amount** | One row per product: the name, the quantity, the line amount including VAT, and a **tax letter** at the end of the row (see below). A long name wraps onto the next line. |
| `@ 1,000.00` under a row | The price for one unit, shown when the quantity is not 1. |
| `less disc 500.00` under a row | A discount taken off that line. |
| **TOTAL EXCL OF TAX:** | The total before VAT. |
| **TAX A-18%** | The VAT charged on standard-rated items, at the rate shown. |
| **TOTAL TAX:** | All VAT on the sale. |
| **TOTAL INCL OF TAX:** | The total the customer owed, with the currency (for example `TZS 25,000.00`). |
| Tender lines | One line per way the customer paid — **CASH**, **CARD**, **MOBILE MONEY** or **CHEQUE** — with the amount. A split payment shows several. |
| **CHANGE** | Shown only when change was given. |
| *Thank you!* | The footer. |

**The tax letters.** Each item row ends with a letter that tells you how VAT was applied to it:

| Letter | Meaning |
|---|---|
| **A** | Standard rate — VAT charged (18%). These items make up the **TAX A-18%** line. |
| **C** | Zero-rated — VAT applies at 0%. |
| **E** | Exempt — no VAT. |
| (blank) | An older receipt reprinted from this till, saved before the letters were added, for an item with no VAT. |

> **Note.** Quantities print without decimals for whole numbers (for example `6`) and with decimals for weighed goods (for example `1.255`). They come straight from the finalised sale, so they always match what the customer was charged.

> **Tip.** If the item area shows `(line detail not loaded)` instead of the products, the totals are still correct and the sale is still recorded — only the itemised breakdown could not be fetched at that moment. Reprint it from **Today's sales** (section 4.1) to pull the full detail from the ERP.

### 1.2 A sample receipt

This is what a receipt looks like on 80 mm paper (48 characters wide). The company, address and numbers are examples; yours will show your own. On 58 mm paper the same information is squeezed into 32 characters, so long names and large amounts wrap onto extra lines.

```text
             SAMPLE SUPERMARKET LTD
             Plot 00, Market Street
               Moshi, Kilimanjaro
                    Tanzania
             Tel: +255 27 275 0000
                TIN: 123-456-789
                VRN: 40-012345-A
               Town Centre Branch
================================================
CUSTOMER NAME:                  Walk-in Customer
================================================
RECEIPT NO:                      INV-2026-004218
RECEIPT DATE:                         02-10-2026
RECEIPT TIME:                           14:07:32
CASHIER:                            Neema Joseph
================================================
Description                      Qty    Amount
Coca-Cola 500ml                    6  6,000.00 A
  @ 1,000.00
Drinking Water 1.5L x 6 (carton)   1  4,000.00 A
  less disc 500.00
Maize Flour 5kg                    1 12,000.00 E
Fresh Milk 1L                      2  3,000.00 C
  @ 1,500.00
================================================
TOTAL EXCL OF TAX:                     23,474.58
TAX A-18%                               1,525.42
TOTAL TAX:                              1,525.42
TOTAL INCL OF TAX:                 TZS 25,000.00
================================================
MOBILE MONEY                           10,000.00
CASH                                   20,000.00
CHANGE                                  5,000.00

                   Thank you!
```

In this example the customer paid TZS 10,000 by mobile money and handed over TZS 20,000 in cash for the remaining TZS 15,000, so TZS 5,000 change was given.

### 1.3 Closing the receipt

When you are done with the receipt, press **Done** (or the **✕** in the top-right corner). This returns you to the register, ready for the next customer. Pressing **Done** does **not** cancel or change the sale — the sale is already recorded in the ERP. It simply puts the slip away.

---

## 2. Printing a receipt

**What it is.** **Print** sends the receipt to the receipt printer attached to your till.

**When it happens.** Press **Print** whenever the customer wants paper — usually straight away, but you can also reprint older receipts later (section 4).

1. With the receipt showing, press **Print** (the printer icon, top-left of the buttons).
2. The receipt prints and you see *Printed.*

| Message | What it means |
|---|---|
| *Printed.* | The receipt went to the printer. |
| *No receipt printer set — configure one in Setup.* | This till has no printer chosen. Sign out, press **Server setup** on the sign-in screen, choose the printer, the paper width (**58 mm · 32 cols** or **80 mm · 48 cols**) and the print mode, and press **Test print** to check it. Usually your administrator does this once when the till is installed. |
| *Could not print the receipt.* (or a message from the printer) | The printer did not accept the job. Check it is switched on, has paper and is connected, then press **Print** again. The sale is not affected. |

> **The cash drawer.** If your till is set up to **Open cash drawer after printing**, the drawer opens **once per sale**: on the first print of the sale's own receipt, straight after the sale, so you can put the money in and give change. It does **not** open when you print a second copy, a gift receipt, a reversed sale, or a reprint from **Today's sales** or **Recent receipts** — no money is going in, so the drawer stays shut. If the drawer is not set up to open, use its key or release lever as usual.

> **Paper width.** The screen always shows the 80 mm layout. The printed copy follows the paper width chosen in setup, so on 58 mm paper some lines wrap differently from the screen — the figures are the same.

---

## 3. Gift receipts (hiding prices)

**What it is.** A **gift receipt** is the same receipt with all the money removed. It lists what was bought and how many, but no amounts, unit prices, tax letters, totals, tenders or change.

**Why it exists.** When someone buys a present, they do not want the recipient to see the price. A gift receipt lets the recipient return or exchange the item (your store policy permitting) without learning what was paid.

**How it works.**

1. With the receipt showing, press **Gift receipt** (the gift-card icon).
2. The slip redraws with the prices hidden. Where the totals were, it shows `* gift receipt - prices hidden *`. The receipt number, date, customer and cashier are still shown, so the item can be traced for a return.
3. Press **Print** to print this price-free copy.
4. To bring the prices back, press the same button — it now reads **Show prices**.

> **Note.** Switching to gift view changes only what is shown and printed. It does **not** change the sale or anything in the ERP. You can flip between **Gift receipt** and **Show prices** as often as you like.

---

## 4. Reprinting an earlier receipt

Sometimes a customer comes back later — they lost the slip, the printer jammed, or they need a copy. OrbixPOS gives you two ways to find an earlier receipt. Both are in the **Session** menu (press the **☰** button in the top bar).

**The golden rule:** reprinting **never creates a new sale**. It shows the same finalised sale again. The customer is not charged a second time, stock is not touched, and no new receipt number is created. You can reprint a receipt as many times as you need.

| Source | Where it looks | Use it when |
|---|---|---|
| **Today's sales** | The ERP server | You want a sale rung **today at this branch**, even one rung on a different till or by a different cashier. Needs a network connection. |
| **Recent receipts** | This till only | You want a sale that was rung **on this till**, and you may be offline. Works without the network. |

> **The CASHIER line on a reprint.** A reprint prints the name of the cashier who **rang** the sale, not the person reprinting it. If you reprint a colleague's receipt, their name appears on the **CASHIER:** line, not yours. On a reprint of an older receipt — one saved on the till before OrbixPOS 1.5.4 — the till may not know who rang it; it then leaves the **CASHIER:** line off rather than print the wrong name. The ERP still records who really made the sale.

### 4.1 Today's sales (look up on the ERP)

1. Press **☰** to open the **Session** panel.
2. Press **Today's sales** (*Look up & reprint a receipt*).
3. OrbixPOS fetches **today's** till sales at **this branch** from the ERP — every sale since midnight on the till's clock, from any till and any cashier at the branch, newest first (up to the latest 100). Each row shows the receipt number, the time and the name of the cashier who rang it, and the total.
4. Tap the sale you want. The full receipt loads and the receipt screen opens.
5. Press **Print** (or **Gift receipt** then **Print**) as normal.

> **Note.** **Today's sales** needs the network. If the connection is down, use **Recent receipts** instead. If the list shows *No sales at this branch today.*, nothing has been sold at this branch since midnight. A sale from an earlier day is not listed — find it under **Recent receipts** on the till that rang it, or ask the back office. A sale that has been **reversed** stays in the list, marked **· Reversed** after its receipt number, so a refund never makes a sale disappear from the day; it reprints with the header **Sale reversed** and the `*** REVERSED ***` stamp. If you do not see **Today's sales** in the menu at all, your account is not allowed to look up sales; ask your supervisor.

### 4.2 Recent receipts (this till, works offline)

**What it is.** The last 50 receipts completed **on this till**, kept on the till itself.

1. Press **☰** to open the **Session** panel.
2. Press **Recent receipts** (*Reprint from this device (offline)*).
3. A list headed **Recent receipts (this device)** appears — each row shows the receipt number, the date and time, and the total.
4. Tap the receipt you want. The receipt screen opens.
5. Press **Print** (or **Gift receipt** then **Print**) as normal.

> **Note.** **Recent receipts** only holds sales rung **on this particular till**. If the list shows *No receipts on this device yet.*, this till has not completed any sales since its local history was last cleared. A sale that was reversed on this till opens with the header **Sale reversed** and the `*** REVERSED ***` stamp.

> **Tip.** Reprints from either source open the *same* receipt screen with the *same* buttons, so you can print, switch to a gift receipt, or — if allowed — refund the sale.

---

## 5. Refunding (reversing) a whole sale

**What it is.** A **refund / reverse** cancels an entire sale that has already been recorded. It gives the money back, puts the stock back, and undoes the VAT and revenue in the ERP.

**Why it exists.** Mistakes happen — the wrong item was scanned, a customer changes their mind right after paying, a basket was rung twice. Reversing the sale is the clean, fully-accounted way to put everything back, with a record of who did it, who approved it, and why.

**Who can do it.** A refund always involves a **manager**:

- **A cashier** can start a refund only on a sale rung on **their own** open till session, and a manager must approve it on the spot by typing their username and password at the till. On a colleague's sale the **Refund / reverse** button does not appear at all — fetch a supervisor instead.
- **A supervisor or manager** whose account holds the invoice-void right (by default the **Sales Manager** and **Branch Manager** roles) is the approver. Normally they walk over and approve at the cashier's till. If a supervisor with this right is running a till shift themselves, they see **Refund / reverse** on any sale while their own shift is open, and they are not asked for a second approval. The ERP still accepts the reversal only while the shift that rang the sale is open.

The approving manager must be a **different person** from the one signed in. Nobody can approve their own refund.

### 5.1 How to reverse a sale

1. Open the sale's receipt. This can be the receipt that just appeared after the sale, or one you reprinted from **Today's sales** or **Recent receipts** (section 4).
2. Press **Refund / reverse** (the red button with the undo arrow). It appears only when a refund is allowed — see section 5.2.
3. A box headed **Reverse this sale?** appears: *This voids the whole sale and reverses revenue, VAT, cash and stock. Allowed while the session is open, and it needs a manager.*
4. Type a short **Reason** — what happened, for example "wrong size, customer swap" or "rang twice in error". It goes into the record. (If you leave it blank, the reason is saved as "POS reversal".)
5. Press **Continue**, or **Cancel** to back out.
6. **If you are a cashier**, a **Manager approval — refund** box opens. It says *Reverse this sale and return the money to the customer.* and names the receipt and amount, for example *Receipt INV-2026-004218 — TZS 25,000.00*. The manager types their **Manager username** and **Manager password** and presses **Approve**. You stay signed in — this only checks the manager's authority for this one refund.
7. On success you see *Sale reversed — approved by [manager's name].* (or just *Sale reversed.* when a supervisor did it on their own authority). The receipt header changes to **Sale reversed** and the slip is stamped `*** REVERSED ***`, followed by `Approved by [manager's name]` when a manager approved it.
8. Give the customer their money. The sale's cash automatically drops out of your drawer's expected total, so you do **not** record a separate cash payout for it.

If the manager presses **Cancel** in the approval box, you see *Not approved — the sale stands.* and nothing changes.

> **If the manager's details are refused.** The approval box stays open and shows why — for example *Those details were not accepted. Check the username and password and try again.* or *That user is not allowed to approve this action.* (the user does not hold the refund-approval right, or is the same person who is signed in). The manager can retype, or you can press **Cancel**.

### 5.2 When the Refund / reverse button is available

The **Refund / reverse** button only shows when **all** of these are true:

| Condition | Why |
|---|---|
| Your account may start refunds (the till refund right, held by cashiers by default) **or** approve them (the invoice-void right, held by sales and branch managers) | Without either, the button is hidden. |
| The shift on **this till** is still **open** | The refund comes out of the open drawer. Once the shift is closed, the cash is settled. |
| **For a cashier:** the sale was rung on **your own** open shift. (A supervisor with the invoice-void right skips this check.) | A cashier may only reverse their own sales. On a colleague's sale the button is simply not shown, so nobody calls a manager over for a refund that would be refused. |
| The sale has **not already** been reversed | A sale can only be reversed once. |

Even when the button shows, the ERP checks again when you confirm:

| The ERP refuses with … | Meaning |
|---|---|
| *You can only reverse sales rung on your own till session. Ask a supervisor to reverse this one.* | A cashier tried to refund a colleague's sale. A supervisor must do it. Normally the till hides the button on a colleague's sale, so you should rarely see this — it can still appear, for example when the till is connected to an older ERP server that does not tell it which shift rang the sale. |
| *This refund needs a supervisor's approval. Ask a supervisor to approve it at the till, then try again.* | The approval did not reach the ERP or was not accepted. Start again from **Refund / reverse**. |
| A message saying the session is not open | The shift that rang this sale has already been closed. It must be cancelled in the back office instead (see below). |
| A message saying the invoice is not a POS sale | The sale was not made at a till; it must be cancelled in the back office. |

In every refused case the sale is left untouched.

> **A sale from a shift that has already been closed.** OrbixPOS cannot reverse it, because the drawer it belonged to is settled. The sale has to be cancelled in the back-office ERP, where the cash difference is handled as a reconciliation matter. Ask your supervisor or store manager.

---

## 6. What OrbixPOS does *not* do — partial and single-line refunds

**OrbixPOS cannot refund part of a sale.** There is **no** way to refund a single line, one item out of five, or part of a quantity. **Refund / reverse** is all-or-nothing.

When a customer wants to return just one item out of a larger basket, you have two correct options, depending on your store's policy:

| Situation | What to do |
|---|---|
| Customer returns **one item** from a multi-item sale, and the sale's shift is still open | **Reverse the whole sale** (section 5), then **ring a fresh sale** for the items the customer is keeping. The net effect is that only the returned item is refunded. |
| You must **hand cash back** that is not tied to a reversible sale (a goodwill cash-back, or a return for a sale from an already-closed shift) | Record a **cash payout** of type **Refund** instead (section 7). |

> **Tip.** "Reverse the whole sale, then re-ring the rest" keeps the books accurate, because each step is a complete, properly-accounted transaction. It takes a few more steps, but it is the right way. The re-rung sale gets a new receipt number.

---

## 7. The cash-drawer refund payout (the alternative)

**What it is.** A **cash payout** records cash physically leaving the drawer. One of its two types is **Refund** — money handed back to a customer that is **not** linked to reversing a particular sale. (The other type, **Paid out**, is for cash leaving the drawer for another reason, such as a drop to the safe. A business expense paid from the till is recorded with **Till expense** instead — see the *Starting and Ending a Shift* chapter, Chapter 2.)

**When it happens.** Only when the proper whole-sale reversal (section 5) is not available or not appropriate. If the sale can be reversed, **always prefer Refund / reverse** — it handles cash *and* stock *and* tax, which a payout does not.

**How it works.**

1. Press **☰** to open the **Session** panel.
2. Press **Cash payout** (*Refund or drawer drop — reason required*). It is only available while the shift is open.
3. At the top, choose **Refund**. (The box opens on **Paid out** — make sure you switch it.)
4. Enter the **Amount (TZS)**.
5. Type the **Reason (required)** — say why the cash is leaving, in a few words, for example "Cash refund, returned goods, ref INV-2026-004218". Quote the original receipt number if you have it.
6. Press **Record**.
7. You see *Payout recorded.* (or *Payout recorded and posted to the ledger.*). The amount is now taken off the cash your drawer is expected to hold at close.

If the reason is too short you see *Say what the cash is for (at least a few words).*; if the amount is empty, *Enter an amount.*

> **Warning — what a refund payout does *not* do.** A refund payout is **cash bookkeeping only**. It does **not** put stock back, it does **not** reverse VAT or revenue, and it is **not** linked to any receipt. It only keeps your drawer's expected cash correct. If the goods are coming back into the shop and the sale could be reversed, use **Refund / reverse** instead.

---

## 8. Quick reference and troubleshooting

| What you see | What to do |
|---|---|
| The **Sale complete** receipt appeared after payment | The sale is recorded. Press **Print** for paper, **Gift receipt** to hide prices, then **Done** to move on. |
| A customer asks for a TRA / EFD receipt | The OrbixPOS receipt is not a fiscal receipt. Issue the fiscal receipt through your shop's EFD/VFD arrangement. |
| A customer wants a present receipt with no prices | Press **Gift receipt**, then **Print**. Press **Show prices** to switch back. |
| A customer lost a receipt from earlier today (any till at this branch) | **☰** › **Today's sales** › tap the sale › **Print**. Needs the network. |
| A customer lost a receipt and you are offline | **☰** › **Recent receipts** › tap the sale › **Print**. Works without the network (this till's sales only). |
| You reprinted a receipt — was the customer charged again? | No. Reprinting never creates a new sale and never charges anyone. |
| *No receipt printer set — configure one in Setup.* | A printer has not been chosen for this till. Set it in **Server setup** on the sign-in screen (usually an administrator's job). |
| The company address, TIN or VRN is missing from the receipt | It has not been filled in on the company record in the ERP. Ask your administrator. |
| You need to undo a whole sale and the shift is still open | On the receipt, press **Refund / reverse**, enter a reason, press **Continue**, and have a manager approve it. |
| **Refund / reverse** is missing | The shift on this till is already closed (the back office must handle it), the sale was already reversed, the sale was rung on a colleague's shift (a cashier can only reverse their own — ask a supervisor), or your account has no refund rights (ask a supervisor). |
| *You can only reverse sales rung on your own till session.* | It is a colleague's sale. Ask a supervisor to reverse it. (Rare — normally the button is not shown on a colleague's sale.) |
| *Not approved — the sale stands.* | The approval was cancelled. Nothing changed. |
| Customer wants to return just one item from a bigger sale | Reverse the whole sale, then re-ring the items they are keeping (section 6). |
| You must hand cash back but there is no reversible sale | **☰** › **Cash payout** › **Refund** › amount and reason › **Record** (section 7). |
| The item area shows `(line detail not loaded)` | Totals are still correct. Reprint from **Today's sales** to pull the full breakdown. |

> **Remember the four rules of this chapter:**
> 1. The receipt is built from the **finalised sale** — the ERP's official record. It is an ordinary sales receipt, **not** a TRA fiscal receipt.
> 2. **Reprinting never creates a new sale** and never charges the customer.
> 3. Refunds at the till are **whole-sale only**, only while the shift is open, and always **approved by a manager** (or done by one).
> 4. For anything else, reverse-and-re-ring, or record a **Refund** cash payout.
