# Kilimanjaro 2026-09-12 — what needs a decision

Triage of the six issues on the client's sheet. Four were fixed without schema and are in the
same PR as this note. **One needs a migration and your approval** ([V105 draft](V105__purchase_vat_treatment.sql)).
Two things are judgement calls that are yours, not mine.

## 1. Approve or reject: `V105__purchase_vat_treatment.sql`

**The defect.** The printed Goods Received Note computes `total = net + VAT`, unconditionally,
treating every entered cost as VAT-exclusive. The client enters costs off supplier invoices that
already include VAT, so the note adds 18% twice. There is no setting to say otherwise — the
`price_includes_vat` flag exists only for sales price lists.

**Blast radius is the document only.** The receipt posts no VAT (purchase VAT is posted from the
supplier bill), and stock is valued at the stored line cost either way. Nothing in the ledger is
wrong today. What is wrong is a piece of paper the client reconciles against.

**The DDL.** One column on `purchase_settings` — one row per company, so single-digit rows.
`NOT NULL DEFAULT 'EXCLUSIVE'` is a metadata-only change on PostgreSQL 11+: no table rewrite,
no backfill, and every existing company keeps today's behaviour until somebody changes it.

```sql
ALTER TABLE purchase_settings
    ADD COLUMN purchase_vat_treatment VARCHAR(20) NOT NULL DEFAULT 'EXCLUSIVE';
ALTER TABLE purchase_settings
    ADD CONSTRAINT chk_purchase_settings_vat_treatment
    CHECK (purchase_vat_treatment IN ('EXCLUSIVE', 'INCLUSIVE', 'NONE'));
```

**Why three values and not a boolean.** "Our costs already include VAT" and "we are not VAT
registered, stop printing a VAT band at us" are different complaints, and I cannot tell from the
sheet which one this is. `NONE` costs nothing to support and covers the second.

| Value | Printed note | For |
|---|---|---|
| `EXCLUSIVE` *(default)* | `net = Σ lines`, `vat = net × rate`, `total = net + vat` | today's behaviour, unchanged |
| `INCLUSIVE` | `net = amount ÷ (1 + rate)`, `vat = amount − net`, `total = amount` | costs typed off a VAT-inclusive invoice |
| `NONE` | no VAT band; `total = net` | not VAT registered |

**Zero-migration alternative, if you would rather not touch the schema:** drop the VAT band from
the note entirely and print only goods value. It ends the confusion but also removes the check
figure the clerk uses against the supplier's invoice. I do not recommend it, but it needs no
approval and I can ship it in an afternoon.

**One thing to confirm with the client before either fix:** ask whether Kilimanjaro is VAT
registered. If they are, and they are entering VAT-inclusive costs, then their **stock is valued
including VAT** — recoverable input tax is sitting inside cost of sales and their margin is
understated. That is a real accounting issue and a bigger conversation than this column. It is out
of scope here, but you should know it is there.

## 2. Your call: value on the printed stock transfer

The transfer document still shows no money, by the deliberate decision recorded in
`StockTransferController` — a sheet travelling with the goods should not tell whoever receives them
what you paid. The client asked for "amount and total value" on transfers.

What shipped in this PR: the **on-screen** transfer now shows a real per-line value and a total,
because the value was never being captured at all (it stored `null` and the screen rendered it as
`0.00`). The **printed** document is unchanged.

If the client meant the printed one, say so and I will add it — it is a column and a totals row,
no schema. It is your decision because it is a disclosure decision, not a technical one.

## 3. Your call: unit selection on transfers

Transfers are captured as product + quantity, always in the product's base unit; the API accepts
no unit. This PR stores and displays that base unit, so a quantity is no longer ambiguous on
screen or on paper.

What it does **not** do is let a storekeeper transfer "2 cartons" instead of "24 pieces". The
columns for it already exist (`stock_transfer_lines.unit_id`, `qty_transferred` vs
`qty_transferred_base`), so **it needs no migration either** — but it does need conversion, and
validation that the converted base quantity is actually available at the source. That is a
feature, not a fix, and I did not want to slip it in unasked.

## Already built, never delivered

Issues #1 (item inquiry) and #6 (profitability report) were built on `develop` on 2026-09-01 and
deployed to QA. They are **not on `main` and not in any `dist/release` package** — the newest is
1.9.3. The client is asking for them again because they have never received them. Nothing to build;
this is a release decision.
