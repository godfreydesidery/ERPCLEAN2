# 0063 — How the printed goods-received note reads the cost that was entered

- **Status:** Accepted
- **Date:** 2026-09-12
- **Deciders:** Owner (godfrey.desidery), Claude (triage, implementation)
- **Context source:** Kilimanjaro issue sheet, 2026-09-12, item #3 — *"Receiving goods inajiongeza
  VAT tena wakati wa printing causing confusion."*
- **Migration:** `V105__purchase_vat_treatment.sql`
- **Relates to:** the K9 printed GRN (PR #308), which introduced the VAT band this ADR now qualifies.

## Context

`GoodsReceiptPrintQuery` derived the foot of the printed Goods Received Note as:

```
net   = Σ line_cost_amount
vat   = net × rate          (per band, product vat_status × tax_rates)
total = net + vat
```

unconditionally. Every entered cost was therefore assumed to be **net of VAT**.

The client enters costs by copying them off the supplier's invoice, and those figures already
contain VAT. The note then added 18% to a number that already had 18% in it, and the printed total
did not match the invoice it was supposed to be checked against. There was no way to tell the system
otherwise: `price_includes_vat` exists on `price_lists`, and has no purchase-side counterpart.

Two facts bound the problem:

1. **Nothing is posted from this.** A goods receipt posts no VAT — purchase VAT is a supplier-bill
   concept and is posted from the bill. The GRN's VAT band is a *check figure* for the clerk
   comparing a delivery against an invoice (a convention established by the K9 work and preserved
   here). So no ledger, no return and no valuation was ever wrong. **The defect was confined to a
   printed document.**
2. **We could not tell which of two complaints this was.** "Our costs already include VAT" and "we
   are not VAT registered, stop printing a VAT band at us" produce the same symptom — a foot that
   looks wrong — and the sheet did not distinguish them.

## Decision

### D-1 — The treatment is a per-company setting, not a global rule or a per-receipt choice

`purchase_settings.purchase_vat_treatment`, one row per company.

Per-receipt would be worse, not more flexible: how a business enters costs is a property of its
bookkeeping, not of one delivery, and asking the storekeeper on every receipt invites a wrong answer
on the day it matters. Global would be wrong the moment two companies in one organisation differ.

### D-2 — Three values, not a boolean

| Value | Printed note | For |
|---|---|---|
| `EXCLUSIVE` *(default)* | `net = Σ amounts`, `vat = net × rate`, `total = net + vat` | the behaviour every receipt had before V105 |
| `INCLUSIVE` | `net = amounts − vat`, `vat` extracted per band, `total = amounts` | costs copied from a VAT-inclusive invoice |
| `NONE` | no bands at all, `total = net` | a business that is not VAT registered |

`NONE` prints **no band**, not a band of zeros. A business with no VAT to declare does not have zero
VAT — it has none, and a row of `0.00` invites the reader to wonder what went wrong.

### D-3 — Under `INCLUSIVE`, VAT comes out by subtraction

Per band: `goods = gross ÷ (1 + rate)` rounded HALF_UP, then `vat = gross − goods`.

The obvious alternative — computing `vat = goods × rate` after deriving `goods` — rounds twice
independently, so `goods + vat` can miss the gross by a cent. The whole point of this mode is that
the printed total equals the supplier's invoice; a foot that misses it by a cent sends a shopkeeper
back to the supplier and costs more than the original bug. The invariant is asserted directly:
`goods + vat == gross` across a range of awkward figures.

A zero-rated or exempt band has nothing to take back out and is left alone, so `INCLUSIVE` and
`EXCLUSIVE` agree on it.

### D-4 — `EXCLUSIVE` is the column default, so no existing install changes

`NOT NULL DEFAULT 'EXCLUSIVE'` — metadata-only on PostgreSQL 11+, no table rewrite, no backfill.
Every company that has never been asked the question keeps exactly the note it printed yesterday.
The lenient reader `PurchaseVatTreatment.orDefault` applies the same fallback to stored data.

### D-5 — A value arriving from the form is validated, not defaulted

`PurchaseSettingsServiceImpl` **refuses** an unrecognised treatment rather than falling back to
`EXCLUSIVE`. Leniency is right for data already in the column (where yesterday's behaviour is the
safe answer) and wrong for a request: a shop that asks for `INCLUSIVE` and is quietly given
`EXCLUSIVE` finds out from a printed note that still double-counts.

### D-6 — This never touches posting or valuation

Stated in the enum, the entity, the column comment and the UI hint, because it is the fact most
likely to be forgotten by whoever reads this next. Stock is valued at `line_cost_amount` whatever
this setting says.

## Consequences

- The client sets **VAT included** and the note reconciles to the supplier invoice.
- **Out of scope, and flagged to the owner:** if the client is VAT registered *and* enters
  VAT-inclusive costs, then recoverable input tax is sitting inside `line_cost_amount`, and
  therefore inside stock value and cost of sales — their margin is understated. This ADR does not
  address that; it is a valuation question, not a printing one, and it needs the client's VAT
  status before anyone acts on it.
- A future "purchase costs are entered inclusive, so strip VAT before valuing stock" decision would
  build on this column rather than introduce another.
