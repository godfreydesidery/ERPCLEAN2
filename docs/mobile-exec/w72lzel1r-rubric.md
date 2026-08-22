## OrbixERP Executive Reporting — Doctrine, Scoring Rubric, and Transformation Playbook
*The hard filter for the Owner & Top-Management mobile app. Applied row by row to the proposed catalogue. Anything below the pass bar is cut or reframed — no exceptions, no "ship it and improve later."*

---

# Part A — The Doctrine

Eight principles. Read them aloud to the owner; he should nod at every one.

1. **Every screen is a question he actually asks, and it answers it in one sentence before it shows a single number.** "Are we going to hit the month?" — not "Sales Analysis."
2. **No number travels alone.** Every figure carries what it is being judged against — plan, last month, last year, the other branches, or the policy limit. A number with nothing to compare it to is trivia, and he will phone the accountant instead.
3. **Show what is wrong before what is normal.** The top of the screen belongs to the three things that need him today. The group total is context and sits underneath. If nothing is wrong, the screen says "nothing needs you today" and stops.
4. **One arrow can lie; a shape cannot.** Every headline number shows at least six periods of history, so a slow four-month slide cannot hide behind a small monthly change.
5. **Cash before profit, always.** Profit is an opinion; cash is a fact. Every profit statement is followed by where the cash actually went — into stock, into customers' pockets, into TRA.
6. **Every number says when it is from and how sure we are.** *Posted*, *provisional*, or *estimated*, plus a real clock time. A figure he cannot vouch for in front of his accountant is a liability, not a report.
7. **His money, his words, his branch names.** TZS in millions and billions, three significant figures, VAT basis stated, no cents, no account codes, no GRNI. If we would not say it out loud to him, we do not print it.
8. **Every metric names a decision and a person.** If nobody acts on it within 24 hours of it turning red, it is furniture — delete it. Transactions, invoice numbers and ledgers live three taps down, never on the opening screen.

### The Smell Test
Ask these five of any proposed report. **One failure means it is operational.**

1. **Can he read the top of the screen in six seconds and say a sentence about the business out loud?** If the first thing on screen is a table, a filter, or a bare number — no.
2. **Compared to what?** Point at every figure. No baseline, no tile.
3. **What decision does this change, and who makes it?** If the honest answer is "good to know," it belongs in the web Reports tab, not on his phone.
4. **Does it survive the accountant's audit and the owner's vocabulary at the same time?** No double-counted intercompany, no mixed VAT bases, no unclosed period passed off as final — *and* no word he would not use.
5. **When he taps it, does it explain — or just show more rows?** Tap one must land on a cause, an outlier, and a name to call. Not a list.

---

# Part B — The Scoring Rubric

Score each proposed report row on all seven criteria. **Weighted score = Σ (score × weight). Maximum 40.**

| # | Criterion | Wt | **0 — Operational** | **1 — Partial** | **2 — Fully executive** |
|---|---|---|---|---|---|
| **1** | **Answers one question** | 3 | Title is a noun phrase ("Sales Report", "Stock Position"); the report *presents an area* and leaves the query to the reader | Title is a question but the screen answers it with a table or several competing numbers; no single verdict line | Title is a question an owner says out loud, and the top line is a plain-language verdict answering it (*"Short 48M on the month, all of it Kariakoo"*) |
| **2** | **Carries a comparison** | 3 | Any headline figure appears with no benchmark | Headline has one comparison, but supporting tiles are naked, **or** the comparison is unfair (part-month vs full month, no like-for-like days) | Every figure carries ≥1 of plan / prior period / same period last year / peer branch / policy threshold, comparisons are like-for-like, **and** the headline shows ≥6 periods of trend |
| **3** | **Exception-led** | 3 | Totals first; breaches buried in a list or absent. All rows shown regardless of size | Exceptions present but below the totals, **or** no materiality gate (every branch shows a red/green arrow) | Breaches at the top, ranked, ≤10 rows, residual stated ("14 others within tolerance"); materiality rule (≥5% **and** ≥TZS 2M) published on screen; when clean, screen is one line long |
| **4** | **One phone screen, no transactions** | 3 | Any document/invoice/receipt number at entry level, **or** horizontal scroll, **or** a grid whose purpose is export | Fits vertically but >7 objects, **or** tap-one lands on a transaction list (drill-down to nowhere) | ≤7 objects (1 verdict + ≤4 tiles + 1 exception list + 1 action), zero horizontal scroll at the smallest supported device, transactions only at L3, reachable in ≤3 taps and returnable in 1 |
| **5** | **Triggers a named decision by a named person** | 3 | No decision stated; a ratio or a total nobody owns | Decision stated in the spec but no accountable person on the screen, **or** no action affordance (read-only) | Spec records *decision · accountable person · action · response time*; the person's name appears on the exception and the action is a tap (Call, Suspend credit, Approve, WhatsApp) |
| **6** | **As-of time and trust stated** | 3 | Undated, or "Live"/"just now"; unclosed period rendered with the confidence of a final figure | Timestamp present but no POSTED/PROVISIONAL/ESTIMATED split, or no coverage note (branches reporting, tills open) | Absolute local stamp (*"as of 14:20 EAT, 18 Aug"*), explicit POSTED / PROVISIONAL / ESTIMATED bands, coverage stated ("11 of 11 branches"), projections label their assumption, and restatements are announced |
| **7** | **Owner vocabulary and units** | 2 | Account codes, ledger jargon (GRNI, accrual, contra), raw 9–10 digit figures, cents, `CO_002 / BR_00017` | Plain-ish labels but inconsistent units (412M beside 3,900,000), or VAT basis unstated, or FX with no rate/date | TZS in M/B, ≤3 significant figures, no cents above L3, pp for point changes, VAT basis on every revenue and margin figure, FX rate + date shown, branches and companies by the names used in the office |

### Scoring bands

| Weighted score | Verdict |
|---|---|
| **30–40** | **SHIP** (the pass bar) |
| **22–29** | **REFRAME** — the underlying question is sound but the artefact is still operational. Rewrite the row using Part C and re-score. One reframe attempt; if it fails again, cut. |
| **0–21** | **CUT** — move to the web Reports tab for the people who process transactions. |

### Pass bar (all three must hold)

1. **Weighted score ≥ 30 / 40**, and
2. **No zero on criteria 1, 2, 3 or 5** — question, comparison, exception-led, and named decision are load-bearing; a zero on any one is not compensable by strength elsewhere, and
3. **Score ≥ 1 on every criterion** — no blind spots.

### Automatic reject — no score computed, the row is cut on sight

- Its name is, or paraphrases, a banned artefact: **invoice register · stock ledger · trial balance · GL account listing · full debtor/creditor ageing · transaction feed · "recent activity" · anything whose primary purpose is export.**
- **It would still make sense as a CSV.** The disqualifier: if the answer to "what does he do after seeing this?" is "scroll", delete it.
- **It requires configuration before it shows anything** — a company picker, branch picker, or date range gating the first paint.
- **It shows a "group" total without intercompany elimination**, or labels a combined total "consolidated". (Correct interim label: *"Combined — includes intercompany"*, in plain words, on the screen.)
- **It mixes VAT bases**, or shows POS/EFD revenue gross alongside net revenue from other channels.
- **It computes its figure through a different code path than the corresponding web report.** One definition, one query, one source, with a test asserting equality to the cent. A divergence is a P1 outage.
- **It fills a data gap with a proxy or a partial scope without saying so** (e.g. margin over the 59% of SKUs that have costs, unfootnoted).
- **It is a vanity metric with no outcome pair** — revenue without margin, volume without profit, invoice counts, "top-selling product" by units.
- **It sends more than one scheduled push per day**, or pushes an operational threshold (reorder level, credit limit breach) to the owner.
- **It cannot name its mode** — reassurance, investigation, or decision. Target suite mix: ~70 / 20 / 10.

*Scoring note for the catalogue pass: record for each row the seven scores, the weighted total, the mode, and — for anything scoring 22–29 — the single sentence describing the reframe. Rows are scored against what is written in the catalogue, not against what the author intended.*

---

# Part C — The Transformation Playbook

The six operational shapes an ERP always tries to smuggle onto the phone, and what each must become.

### 1. Invoice Register → **"Are we going to hit the month?"**
- **Before:** paginated list of every posted invoice — number, date, customer, gross, VAT, net. Sorted by document number. Exportable.
- **After.** *Question:* will we hit plan? *Hero:* **"TZS 412M in 18 days — on pace for 655M against a 700M plan. Short 45M."** *Comparison:* same 18 days last month (378M, +9%), same month last year (588M), plan. *Trend:* 12-month bar with the current partial month hatched. *Exception:* "Arusha is the whole gap: −22% vs its own last month; eight branches within ±5%." *On tap:* → branch bridge showing which branch and which product group moved the number → (tap 2) top 5 customers driving that branch's fall, with a Call button. Invoices, if he insists, are tap 3.
- **Mode:** reassurance. **Accountable:** Sales Director.

### 2. Stock Ledger → **"What is my money sleeping in?"**
- **Before:** every stock movement, in/out/balance, by SKU by date, at cost. Thousands of rows.
- **After.** *Hero:* **"Stock worth TZS 1.31bn — 6.0 months of sales cover, up from 4.4 in March."** *Comparison:* cover vs the 4-month policy ceiling; value vs last month. *Exception:* "TZS 340M hasn't moved in 90 days — 12 SKUs are 70% of it." Second exception: "Cement covers 2 days; the 22 Aug container slipped to 3 Sept." *Trust line:* "as of 06:00 · 11 of 11 branches reported · 14 receipts missing cost (~TZS 8M unvalued) — assign cost." *On tap:* → the 12 dead SKUs, valued, with age and last movement date, and a **[Mark for clearance]** action.
- **Mode:** investigation. **Accountable:** Head of Purchasing.

### 3. Trial Balance → **"Did we make money, and where did it go?"**
- **Before:** 240 account rows, Dr/Cr columns, class subtotals. An instrument of proof, addressed to an accountant.
- **After — four sentences in his grammar.** **We sold TZS 1.24bn** (excl. VAT) · **it cost us 902M** to buy and make · **we spent 190M** running the place · **we kept 148M (11.9%)**. *Comparison:* last month 13.4%; plan 13.0%; 12-month margin line. *Exception:* "the 1.5pp drop is 100% freight and packaging in Manufacturing." *Second block, mandatory (Principle 5):* **"Profit 148M, but cash −22M"** — stock up 96M, customers owe 61M more, suppliers paid down 38M, tax 31M. *On tap:* → the margin bridge (price / mix / landed cost / volume) → the division and the cost line that moved.
- **Mode:** reassurance. **Accountable:** Finance Director.

### 4. AR Ageing Detail → **"Who owes me money that is late — and who do I call?"**
- **Before:** 317 customers × 7 ageing buckets, alphabetical, horizontal scroll.
- **After.** *Hero:* **"TZS 318M overdue — TZS 142M over 60 days, up 38M this month. Policy limit on 60+ is 30%; we are at 45%."** *Exception, ranked:* three names are 62% of it — *Mo Hardware 74M, 91 days, was 40M last month · Tembo Construction 68M, 60 days, promised 22 Aug · Njiro Traders 55M, 120+ days, no contact 6 weeks.* Residual: "307 others, TZS 82M, none over 30 days." *Owner named on the exception:* J. Mushi, Credit Control. *Actions:* **[Call]** **[Suspend credit]** **[WhatsApp the figure]**. *On tap:* → one customer's payment behaviour over 12 months (pattern before incident) → invoices at tap 3.
- **Mode:** decision. **Accountable:** Credit Control.

### 5. PO Listing → **"What have I committed to spend, and what needs me?"**
- **Before:** all open POs — number, supplier, date, value, status, approver.
- **After.** *Hero:* **"TZS 486M committed and not yet received — 210M lands this week."** *Comparison:* vs last month's commitment, vs cash available in the same window. *Exception:* **"2 approvals waiting for you, both above your TZS 20M threshold"**, each as a decision card carrying its own evidence — supplier, amount, against which budget line, last price paid, who already approved — with **[Approve] [Decline + reason]**, offline-queued and never lost. Below: "6 LPOs raised by Purchasing under threshold, no action needed." *FX rule:* import POs show the rate and date, and separate shilling movement from supplier price movement.
- **Mode:** decision. **Accountable:** the owner personally.

### 6. GL Account Ledger → **"Will I run out of cash?"**
- **Before:** every posting to a nominated account, running balance, journal references.
- **After.** *Hero:* **"Cash TZS 96.4M across bank, till, safe and mobile money — 5.4 weeks of runway at the current burn."** *Trust, on the face of the screen:* "POSTED to 17 Aug 23:59 · plus PROVISIONAL 8.1M (2 tills unposted, 1 M-Pesa feed pending)." *Reconciliation, the single most trust-building line in the app:* "System 12.4M · Bank statement 12.4M · **matched**" — or the difference with its named cause ("2 deposits in transit, 900,000"). *Tiles:* receivable due ≤7 days, payable due ≤7 days, **VAT payable to TRA 61.2M due 20 Sept**, EFD transmissions healthy/failing. *Exception:* "Bank balance will cross your 40M floor on 26 Aug unless Mo Hardware pays." *On tap:* → the 13-week cash line, with its assumption printed (*"assumes collections continue at the 90-day average of 71%"*).
- **Mode:** reassurance, escalating to decision. **Accountable:** Finance Director + owner.

---

# Part D — The One-Screen Anatomy

The canonical executive screen. Every report in the suite is a variation on this skeleton; deviations must be justified in writing.

```
┌──────────────────────────────────────────────────┐
│  Are we going to hit the month?            [•••] │  ← 1. TITLE = THE QUESTION
│  All companies ▾   This month ▾                  │     Chip row: filters EDIT a live
│                                                  │     result, never gate it. Screen
│                                                  │     opens already answered.
│  Short TZS 45M on plan — all of it Arusha.       │  ← 2. VERDICT LINE, plain words,
│                                                  │     readable at arm's length, in
│                                                  │     sunlight, before any number.
│      TZS 412M                                    │  ← 3. HERO NUMBER. One per screen.
│      in 18 days · on pace 655M                   │     3 sig figs. Tap = exact figure.
│      ▲ +9% vs same 18 days last month            │  ← 4. THE COMPARISON. Like-for-like
│      ▼ −45M vs plan 700M                         │     days. Plan AND prior period.
│      ▁▂▄▃▅▆▅▇▆█▇▅  12 months                    │  ← 5. THE SHAPE. ≥6 periods, in the
│                                                  │     same tile as the number.
│  as of 14:20 EAT, 18 Aug · PROVISIONAL           │  ← 6. AS-OF + TRUST BAND. Always
│  11 of 11 branches · 3 tills open, final ~21:00  │     visible, never a tooltip.
│  Sales = fiscalised invoices net of VAT and      │     Definition on tap/long-press.
│  credit notes, intercompany eliminated.     (i)  │
│                                                  │
│  ─── 2 THINGS NEED YOU ───────────────────────   │  ← 7. THE EXCEPTION LIST, ABOVE
│  Arusha −22% (−48M)          Owner: P. Kimaro    │     the supporting detail. Ranked
│     margin 11%, floor is 18%          [Call]     │     by money at risk. Each row:
│  Mo Hardware  74M, 91 days     Owner: J. Mushi   │     what · how much · WHO OWNS IT
│     was 40M last month     [Call] [Suspend]      │     · a one-tap ACTION.
│                                                  │
│  8 branches within tolerance (≥5% and ≥TZS 2M)   │  ← 8. THE RESIDUAL + the published
│                                                  │     materiality rule. Never a
│  ─── SUPPORTING ─────────────────────────────    │     23-row enumeration.
│  Margin 21.4%   Cash 96.4M   Overdue 318M        │  ← 9. ≤4 supporting tiles, each
│  ▼0.3pp/mo      5.4 wks      45% is 60+          │     with its own comparison.
│                                                  │
│         [ Why is Arusha down? ]                  │  ← 10. THE SINGLE TAP-THROUGH,
│                                                  │     in the thumb zone. Lands on
└──────────────────────────────────────────────────┘     CAUSE + OUTLIER + NAME —
                                                         L2 explanation, not L3 rows.
```

**Deliberately NOT on this screen**

- **No invoice, receipt, GRN, LPO or journal numbers.** Any document number at L1 is an automatic fail.
- **No filter form, no Run button, no empty state.** Zero-tap entry; last cached snapshot paints instantly offline, stamped and then refreshed. A spinner is a failure state.
- **No horizontal scroll, at any breakpoint, for any table, chart or tile.**
- **No twelfth equal tile.** Seven objects is the ceiling; twelve equal things means nothing is important.
- **No account codes, no `CO_002 / BR_00017`, no cents, no GRNI, no "accrued liabilities".**
- **No decorative colour.** Red means bad for the business, green means good — never red for "negative number"; a fall in debtors is green. Never colour alone: sign or icon as well.
- **No pie of nine slices, no dual axis, no 3D, no gauge without a target.**
- **No naked "Live" badge.** If we cannot vouch for it, it is labelled PROVISIONAL or it does not appear.

**And the reason it works:** the whole screen must also be readable *without opening the app* — the 06:30 brief renders lines 2, 3 and 7 into the notification itself: *"Yesterday: Sales TZS 41.2M (+8% vs last Tue) · Cash 12.4M · 2 approvals waiting."* If he reads only that and never opens the app, the product has succeeded.