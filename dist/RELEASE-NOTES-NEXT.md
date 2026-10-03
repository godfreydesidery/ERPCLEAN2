### New reports

Every new report can be printed or downloaded as PDF, Excel or CSV. Downloading needs the report's
own permission plus **Export reports**.

- **Customers and suppliers:** printable customer and supplier statements, aged balances for both,
  and a check that the supplier ledger agrees with the accounts.
- **Cash, VAT and WHT:** printable cash/bank statements, VAT return and WHT register.
- **Purchasing:** Goods Received Register, Purchases by Supplier, Open Purchase Orders and
  Purchase Price Variance.
- **Sales:** Sales Summary, grouped by customer, agent, route, branch, day or cashier.
- **Till cash-up:** Payment Summary, the day's takings by payment method and cashier.
- **Stock:** Reorder Report and Stock Ageing.
- **Accounts:** Profit & Loss, Balance Sheet and Cash-Flow for one branch; Statement of Changes in
  Equity; Financial Ratios.
- **Payroll and assets:** payroll statutory summary (PAYE, NSSF, WCF, SDL, HESLB), a bank-file
  download on posted payroll runs, and the Fixed Asset Register.

### Who can see what

- A user assigned to one branch can no longer read another branch's figures by choosing that
  branch in a report filter.
- The **Payment Summary (cash-up)** shows every cashier's takings, so it is now for managers:
  Sales Manager, Branch Manager, Accountant and Finance Director. Cashiers still see their own
  shift's X and Z reports.
- The Finance Director can now open the stock value reports, and the HR & Payroll Manager can
  export the payroll reports.

### Corrections to figures

These corrections apply to new transactions from this version on. Past records are not changed.

- **Stock value after a void.** Voiding a goods receipt, or making a purchase return, left the
  receipt's value in stock. That raised the average cost and the cost of later sales. A void now
  removes exactly what the receipt added.
- **Sales in US dollars.** Sales reports added dollar invoices into shilling totals as if they
  were shillings. They are now converted at each invoice's own rate. Dollar VAT now keeps its
  cents, and the VAT return converts foreign VAT to shillings.
- **VAT on sales returns** was about one hundredth of the correct figure. It now matches the
  original sale.
- **Customer and supplier balances in more than one currency** are now shown per currency.
  The credit-limit check prices a foreign balance at today's rate.
- **Withholding tax.** A payment with WHT now shows the amount actually paid in the cash book.
  The WHT register shows the supplier's name.
- **Depreciation** is now charged to the branch that holds the asset. The rate field is labelled
  "% per month" to match how it is applied.
- **Payroll.** Paying out a posted payroll run now works on every company, and the bank file is
  produced only for posted or paid runs.
- **Account Ledger.** The running balance is now correct on every page.

### The till

There is no new OrbixPOS version with this update. **OrbixPOS 1.5.4** remains current and works
with this server unchanged.
