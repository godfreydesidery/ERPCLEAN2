This is a large update. Shops told us that some everyday jobs were missing or harder than they
should be, and that some figures needed correcting by hand every day. We went through every
area of the system with that in mind. Most of the fixes below come from that review.

**Please read "Before and after updating" at the end before you install it.**

### Selling & pack sizes

- **Crates and cartons are billed correctly.** A sales order delivered and invoiced in crates
  used to bill the number of bottles at the crate price (one crate could be invoiced as 25).
  Orders, deliveries and invoices now keep each line in its own unit.
- **Each customer is charged their own price.** Selling prices are now taken from the
  customer's own contract price first, then the customer's price list, then the company's
  default price list. Price lists that are archived or outside their dates are ignored. This
  applies to invoices, sales orders, quotations and the till. The price hint on a new invoice
  shows that customer's price for the unit you chose.
- **Void and refund a counter sale.** A counter sale, or a till sale from a shift that is
  already closed, can now be voided in full with a refund ("Void & refund"). Stock goes back,
  and the sales, VAT and ledger figures are reversed. Voiding an invoice raised from a sales
  order now frees its quantities so the goods can be invoiced again.
- **Draft invoices:** cancel a draft you no longer need, and change a line on a draft. The
  status and customer filters on the invoice list now work.
- **Credit limits:** a sale paid in full at the counter is no longer refused because of the
  customer's credit limit. Only what is left unpaid counts. When a sale is refused, the message
  shows the limit, the balance and the amount.
- **Partial invoices** share a fixed line discount fairly instead of taking it all on the first
  invoice.
- **Amounts typed with commas** (for example 1,800 or 68,300) are now read correctly
  everywhere, including invoices, the web till, goods receipts and payments.
- **First sale on a new system:** the owner's administrator account can now ring a sale. It is
  credited to a "Counter" sales agent that is created automatically. New Invoice now explains
  what leaving the sales agent blank means.
- The "Fiscal receipt not found" error box no longer appears on finalised invoices or when a
  barcode is not recognised.

### Buying & receiving

- **Receive in the unit you ordered.** Receiving against a purchase order now shows and books
  each line in the order's unit (cartons stay cartons). Before, a carton line could be refused
  as an over-receipt, or book 12 times the stock.
- **Purchase returns in the right unit.** Returning one crate now removes one crate, at the
  crate's value. A return can no longer exceed what was received.
- **Print a purchase return** as a debit note for the supplier, or export it to Excel or CSV.
- **Goods receipts in US dollars** are now valued in shillings at the exchange rate of the
  receipt date. Before, USD 1,000 was valued as TZS 1,000.
- **Voiding a goods receipt** is refused once a supplier bill claims it, once goods were
  returned against it, or once some of its stock has been sold. Correct it with a purchase
  return or a stock adjustment instead.
- **A purchase return now credits the supplier correctly.** The debit note carries VAT and is
  in the order's currency.
- The **printed goods receipt** compares pack cost with pack price, so the margin is right.
- The purchase order **status filter works**. The receive picker lists each open order once,
  and the goods receipt pickers search the whole list, newest first.
- You are asked to confirm before a purchase order is closed.
- The approval limit on a foreign-currency purchase order is checked in shillings.
- Returns and landed costs are booked at the branch that received the goods.

### Stock & transfers

- **Adjust stock again.** The stock adjustment form and "Set to counted qty" save again. There
  is a new **Adjust Stock** menu entry, and you can choose the location when you adjust.
- **Pack sizes on stock screens.** Adjustments, stock counts and opening stock can be entered
  in cartons or crates as well as pieces.
- **Opening stock is valued.** Opening stock entered from Product Master or Stock On-Hand now
  carries its cost. Before, it was booked at zero value. Storekeepers can now enter that cost.
- **After a transfer**, adjusting, importing or setting opening stock for that product at the
  receiving branch works again. Before, it was refused with "held at more than one location".
- **Who may move stock between branches:** a transfer is sent from the sending branch and
  received at the receiving branch.
- **Stock on its way is not for sale.** Stock in transit or in quarantine no longer counts as
  stock you can sell. The stock report has a new **In transit** column.
- **Stock counts:** the difference is booked to the accounts once (it used to be booked
  twice). It is worked out as at the moment each line was counted, so sales made while the
  count was in progress no longer show as extra stock. Cycle counts now count only the
  products you pick.
- **Reorder levels** set in Product Master now drive the Low stock flag, the reorder report and
  the alerts. Expect more items to show as Low stock.
- The transfer list can be filtered and sorted.
- A location that still holds stock cannot be made inactive. A transfer refuses service items
  and the same product twice.

### Customers, receipts & cash

- **Choose where the money goes.** When you record a customer receipt, choose the cash, bank
  or M-Pesa account it lands in. The M-Pesa or bank reference is kept.
- **Record Receipt** shows only the chosen customer's invoices, and the customer and status
  filters on Receivables and Receipts work.
- **Money left on account:** amounts you do not allocate stay on the customer's account. You
  can apply them to later invoices from the receipt page.
- **Reverse a wrong receipt** from its page, with a reason. The invoices it paid are owed
  again.
- **Refund a customer** money held on their account (a deposit or an over-payment) from the
  receipt page.
- The **Credit Note** button on Receivables works (it used to fail with "Customer not found").
- **Customer opening balances** keep their due date and branch, and can be in US dollars.
- The customer screen shows the customer's balance. A customer who still owes money cannot be
  archived.
- **Expenses paid in cash** are easy to find: **Cash / Bank Entry** (search for "expense")
  starts on money out, lists the entries already made, and can record input VAT on the
  expense.
- **The cash book is complete.** It now records sales, voids, till payouts and till over/short
  as they happen, and a bounced cheque takes the money back out.
- **Cash counts on the sales till** work again (see "Before and after updating").
- **Petty cash reaches the accounts.** Spending from petty cash now needs an expense account
  and is booked to the ledger. A top-up asks which account the money came from.
- **Bank reconciliation** completes from the second month onwards.

### Suppliers & payments

- **Supplier bills are matched like for like.** Billed cartons are compared with received
  cartons, and the same received line cannot be billed twice.
- A bill that is **held or failed its match** can be matched again, accepted or deleted. A
  failed match says why.
- **Due dates** follow the supplier's payment terms.
- **Part-pay bills** in a payment run. Payments are allocated oldest bill first, and a payment
  larger than the bill's balance is refused.
- **Choose the cash or bank account** a supplier payment leaves from. Payments in US dollars
  are recorded in the cash book in the account's own currency.
- **Reverse a wrong supplier payment** from its page.
- **Pay** on a bill opens Record Payment for that supplier and bill. The success screen after
  a payment shows the payment correctly.
- A **debit note** reduces only its own supplier's bills, in its own currency.
- **Bills for things that are not stock** (services, repairs, equipment) can name the expense
  or asset account on each line. The system's own control accounts are refused on bill lines.
- Saving a supplier no longer clears its payment terms, currency or withholding tax setting.
  Supplier opening balances can be in US dollars.
- **AP Ageing** (new menu entry) shows what you owe every supplier, by age.
- When a bill and its goods receipt differ in price or exchange rate, the difference now goes
  to stock or cost of sales instead of staying in the goods-received account.
- A missing exchange rate gives a clear message instead of an error.

### Accounting, VAT & the books

- **The 2027 financial year opens by itself.** Each year the next year is opened
  automatically, so postings keep working after 31 December. Overlapping years are refused.
- **Nothing is lost silently.** If an automatic posting fails (for example the period is
  closed), it is now listed under **Posting Exceptions** (new menu entry). Fix the cause and
  re-post it from there; each one posts only once. The same screen compares sales with the
  ledger.
- **Takings go to the right account.** A card, mobile money or cheque payment taken into a
  named bank or M-Pesa account is booked to that account, not to Cash. A deposit on a credit sale is booked to cash, and only
  the unpaid part goes to the customer's account. Voiding a credit sale clears what the
  customer owed.
- **The VAT return** now includes credit notes, supplier debit notes, returns and voided sales
  automatically, and filing it clears the VAT accounts to zero.
- **Record paying** VAT, withholding tax, PAYE, NSSF, SDL and WCF. Export the VAT return's
  sales and purchases lists.
- **Dates follow your time zone.** Sales made between midnight and 3 a.m. now belong to that
  new day (and month, for VAT). Default dates and printed dates also use your local time.
- **Trial Balance** can be run as at a date, with opening balance, movement and closing
  balance, by branch.
- The **journal list** can be searched and filtered, and shows the source document number.
- **Reversing a journal** asks for a date and a reason. A journal that the system created for
  a sale, receipt or other document cannot be reversed on its own; correct the document
  instead.
- Control accounts are marked in the manual journal account picker.
- Clearer messages when a posting date has no period, when the period is closed, or when the
  accounts are not set up.

### Reports

- **Quantities in base units.** Sales and profitability reports add up pieces correctly when
  some sales were in crates. Each product appears once, under its current name.
- **Discounts include VAT** and are the same on the Sales Report, Sales Summary and
  Profitability report.
- **Staff see their own branches.** For staff limited to some branches, "All branches" means
  all of their branches. The Sales Report has a branch picker, and every export states which
  branches it covers.
- **Cost and margin** are shown only to staff allowed to see stock values. Cashiers and
  salespeople no longer see them, and the Profitability report needs that permission.
- A report whose end date is before its start date is refused with a clear message.
- **New:** AP Ageing, Trial Balance as at a date, VAT return sales and purchases lists, and the
  sales-to-ledger comparison on Posting Exceptions.

### Products & setup

- **Product Master saves everything.** Category, brand, tracking, reorder level, preferred
  supplier and the new **age restriction (18+)** are now kept when you save.
- **Carton and crate barcodes** added in Product Master keep their unit, so the till rings a
  crate, not one bottle.
- **Excel imports** keep barcodes and codes exactly as typed (leading zeros are not lost).
  Uploading the same file again updates the records instead of creating copies.
- **Pricing Rules** now say that quantity tiers are not yet applied to sales.

### Staff access & sign-in

- **Change your own password** from the user menu. A user created or reset by an
  administrator must choose a new password at their next sign-in.
- **Shorter sign-in names.** On a single-company installation, staff can sign in with their
  name alone, without "@organisation". After a failed sign-in, the screen hints at the full
  name.
- **Empty lists explain themselves.** A drop-down that is empty because you do not have
  access now says so, instead of looking empty for no reason.
- **Fixed for staff accounts (not only the administrator):** procurement staff can set selling
  prices; cashiers can choose their till for a cash count; accountants can enter supplier bills
  against orders and receipts; field agents can use van reconciliation; storekeepers find
  Bulk Import for stock.
- **Role updates:** Branch managers can create and receive transfers, start stock counts and
  view statements, returns and deliveries. Accountants can view sales, till sessions, stock
  and returns. Sales managers can export reports. Production managers can view stock
  locations. Accountants and finance directors can reverse receipts and supplier payments and
  refund customers.
- The logo and a new Home link take you back to the start page.

### The till

- The web till prices each line for the sale's customer, and its total matches the receipt on
  discounted lines.
- **OrbixPOS 1.6.0** goes with this update: customer prices at the till, a manager's approval
  for cash paid out of the drawer, a blind cash-up, Hold and Recall, and no more "Refund"
  payout. See its own release notes.

### Before and after updating

#### Before you update

- **The update changes the database (one small change).** It takes a moment. The update takes
  a backup first, as always, and stops if the backup fails. Keep that backup until you have
  checked the system after the update.
- **Update OrbixPOS on every till to 1.6.0 together with the server.** Older tills keep selling,
  but on them a "Refund" payout is refused for cashiers, cashiers see "Expected cash 0.00" on
  the X-read, paid-outs are not approved by a manager, and account customers see the normal
  price on screen while they are charged their own.

#### After you update

- **Financial year 2027 opens automatically** the first time the system starts. Nothing to do.
- **Petty cash:** petty cash is now part of the accounts, but the money already in each fund is
  not. Ask your accountant to post one opening journal for each existing petty-cash fund:
  debit Petty Cash, credit the account the money originally came from, for the fund's current
  balance.
- **VAT returns:** stop entering credit-note and debit-note VAT adjustments by hand. The return
  now picks up credit notes, supplier debit notes and voided sales by itself. Use an adjustment
  only for a document that is not in the system.
- **Passwords:** users created or reset by an administrator must change their password at
  their next sign-in. Tell your staff to expect this.
- **Report figures change.** Discounts now include VAT, quantities are in base units (pieces,
  not a mix of crates and pieces), and products show under their current names. Figures for
  past periods may differ from reports you printed before.
- **Night sales:** sales made between 00:00 and 03:00 now belong to that new day (and month)
  in reports, the ledger and VAT. Earlier postings are not moved.
- **Cash counts on the sales till:** the cash book of the sales till records sales from the
  moment of the update. To make up for older sales the cash book never recorded, the
  difference from before the update is carried into the expected cash, so your first count
  does not show months of takings as "cash over". A count for a day before the update cannot
  be entered on the sales till.
- **US dollar goods receipts:** goods receipts in US dollars entered before this update were
  valued as if dollars were shillings. If you have any, ask your accountant to check stock
  value and the goods-received account, and post a correcting adjustment where needed.
- **Journals:** a journal the system created for a document can no longer be reversed on its
  own, and control accounts are refused on supplier bill lines.
- **Low stock:** more items may show as Low stock, because reorder levels set in Product Master
  are now used.
- **Transfers on the way:** stock that is still in transit can no longer be sold at either
  branch. Receive any transfer that has arrived, so its stock becomes available.
- **Draft purchase returns** for carton or crate items that were saved before the update may
  hold the wrong unit. Do not confirm them; enter those returns again.

**The till: OrbixPOS 1.6.0 is required with this update.**
