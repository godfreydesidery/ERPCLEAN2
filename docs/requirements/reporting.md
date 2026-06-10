# Requirements — Financial Reporting (the three primary statements + the GL account-ledger drill-down)

> Status: **RATIFIED (owner-confirmed 2026-06-10).** The owner answered all Reporting scoping forks —
> **auto-derive the statement→account mapping** from `account_type` + account-code ranges (**NO** configurable
> statement-template table); the **three primary statements in v1** (Income Statement / P&L over a period;
> Balance Sheet as-at a date; **Cash-Flow Statement — INDIRECT method**) **plus** the **GL account-ledger
> drill-down** (the drill target from any statement line); **comparative columns** on every statement (the
> selected period/date and a prior comparative); **export to PDF and Excel/CSV** for each statement + the
> ledger; **arbitrary date-range / as-at period selection** with **fiscal-period / fiscal-year quick-selects**;
> **base currency only** (no FX in v1); and **read-only over GL** (Reporting **POSTS NOTHING**) with hard
> **reconciliation/correctness bars** (the BS must balance; the cash-flow net must equal the Cash+Bank GL
> movement; the P&L net must equal the period's INCOME−EXPENSE GL movement; every figure drills to GL journal
> lines). Each is reflected below as a fixed v1 requirement; everything not chosen has moved to the
> **Deferred** list (§2). **No ADR-0018-blocking open question remains** — the code-range banding boundaries,
> the cash-flow treatment of the VAT/WHT control accounts, and the export-library choice are **ADR decisions**,
> not requirements blockers.
>
> Author: system-analyst · Domain: `reporting` (financial / read-side). Business-level spec only.
> **No schema, no API shapes, no tables/columns, no code** — those are the solutions-architect's, in
> **ADR-0018** (next step). Do not infer a data model from this document.
>
> **This is Financial Reporting — ROADMAP T2.3 (first slice) / docs/PATH-TO-FULL-ERP.md Phase A Reporting.**
> The GL already delivers the **trial balance** (the period `GROUP BY account, SUM(debit − credit)` over
> `journal_lines`, gl.md FR-GL-16); Reporting turns the balanced books into the **financial statements** a
> business actually reads: a **Profit & Loss**, a **Balance Sheet**, a **Cash-Flow Statement**, and the
> **account-ledger drill-down** beneath every line. It is **READ-ONLY over GL** — it introduces **no new
> posting, no new business tables, no sub-ledger**; it aggregates `journal_lines` and presents them.
> *This is the moment ERPCLEAN2 is demonstrably an ERP, not just balanced books* (PATH-TO-FULL-ERP §5 Phase A,
> the milestone at lines ~442–443 / ~471).
>
> **Depends on:** **GL** (the books — ADR-0013 / V10; **the single source of every figure**): the existing
> `TrialBalanceQuery` + `TrialBalanceDto` / `TrialBalanceRowDto` (the period trial balance the statements
> build on); `chart_of_accounts` with `account_type` ∈ {ASSET, LIABILITY, EQUITY, **INCOME**, EXPENSE} (note:
> **INCOME**, not "REVENUE") + `normal_balance` ∈ {DEBIT, CREDIT}; `journal_entries` / `journal_lines`
> (`posting_date`, debit/credit — the source of all balances); `fiscal_periods` / fiscal years (the period
> windows + the `FiscalPeriodResolver` for quick-selects). All financial data already posts to GL — Sales,
> AR, AP, Cash & Bank, VAT (ADR-0013/0014/0015/0016/0017). **Money** (ADR-0005 — base currency only in v1).
> **RBAC / `ScopeGuard` / audit** (the platform spine); per-company scope; `assertCanActIn` on **every read
> path**. All shipped. **The central integration:** Reporting is a pure **read model over GL** —
> `journal_lines` aggregated by account, the accounts classified into statement sections by
> `account_type` + code range, presented with a comparative column and exportable to PDF / Excel.

## 1. Business context & why now

The books exist and balance: every financial effect — Sales revenue + VAT (gl.md §3.1), AR receipts, AP
payments, Cash & Bank movements, the VAT settlement — posts a balanced double-entry to GL, and GL already
reads a **trial balance**: every account with its total debits / total credits over a period, netting to
zero when the books are sound (gl.md FR-GL-16). **But a trial balance is not a financial statement.** A
business owner, a controller, an auditor, and a bank do not read a list of 18 accounts and their net
balances; they read a **Profit & Loss** ("did we make money this period, and how does it compare with last?"),
a **Balance Sheet** ("what do we own and owe right now, and does it balance?"), and a **Cash-Flow Statement**
("where did the cash actually go?"). Today ERPCLEAN2 can prove its books are sound but **cannot produce the
statements that make it recognisable as an ERP.**

**Financial Reporting closes that gap.** It is a **read-only** module over GL that turns the balanced ledger
into the three primary statements and the drill-down beneath them:

- a **Profit & Loss / Income Statement** — INCOME − EXPENSE over a period, presented as **gross profit**
  (Sales − COGS), then **operating expenses**, then **net profit**, with a **comparative** prior period;
- a **Balance Sheet** — **ASSET = LIABILITY + EQUITY** as-at a date, grouped **current vs non-current**, with
  the period's **net profit folded into equity** (retained earnings + current-year net income), and a
  **comparative** as-at a prior date;
- a **Cash-Flow Statement (indirect method)** — net income ± non-cash adjustments ± working-capital changes
  (ΔAR, ΔAP, ΔInventory, ΔVAT/WHT) = **operating**, ± non-current-asset changes = **investing**, ± equity /
  borrowing changes = **financing**, whose **net change in cash ties to the movement on the Cash + Bank GL
  accounts** between the two dates, with a **comparative**;
- a **GL account-ledger drill-down** — for any account, the running ledger (opening balance, each journal
  line in the period with date / source / reference / debit / credit, running balance, closing balance) —
  the **drill target** from any statement line; and
- **export** of each statement and the ledger to a **faithful printable PDF** and to a **spreadsheet
  (Excel / CSV)**.

### The reconciliation guarantees (read this before anything else)

Reporting earns its trust by **tying back to GL** — and because GL is double-entry, the ties are exact, not
approximate. These are the **correctness bars** every statement asserts (the §6 business rules):

- **The Balance Sheet MUST balance.** **ASSET == LIABILITY + EQUITY** for *any* as-at date, because GL is
  double-entry (Σ debits == Σ credits, gl.md BR-GL-01) — so the sum of debit-balance accounts equals the sum
  of credit-balance accounts at every instant. A non-balancing Balance Sheet is **not** a presentation quirk;
  it is a **defect / data-integrity alarm** (BR-REP-02).
- **The P&L net == the period's INCOME − EXPENSE movement in GL.** The net profit a P&L reports for a date
  range equals the net movement of all INCOME and EXPENSE accounts over that range on `journal_lines`
  (BR-REP-03). The statement cannot invent or drop a figure the ledger does not carry.
- **The Cash-Flow net change in cash == the Cash + Bank GL account movement** between the two dates
  (BR-REP-04) — the indirect-method reconciliation, the cash-flow equivalent of the VAT return's
  output-vs-`2200` tie (vat-return.md BR-VAT-08) and Cash & Bank's book-vs-linked-GL tie (cash-and-bank.md
  BR-CASH-02). The bottom of the cash-flow statement reconciles to the cash and bank balances; if it does not
  tie, the statement is wrong.
- **Net profit folds into Balance-Sheet equity.** The as-at equity reflects **retained earnings + the
  current-year net income to date** (the standard presentation), so the Balance Sheet balances *before* any
  year-end closing entry exists (BR-REP-05). This is how a mid-year Balance Sheet balances when the year's
  P&L has not yet been closed to retained earnings.
- **Every figure traces to GL journal lines.** Each statement line is the aggregation of specific accounts,
  and each account drills to its **account ledger** (the journal lines that produced the balance) — so any
  number on any statement is **explainable down to the posting** (BR-REP-06). No black-box figures.

### The auto-derived mapping (owner decision #1 — no template table)

The statement→account mapping is **auto-derived from `account_type` + account-code ranges** — there is **NO
configurable statement-template table** in v1 (BR-REP-07). The derivation is a fixed rule:

- **`account_type` drives the statement and the top-level section** (the authority — gl.md BR-GL-12): **INCOME
  and EXPENSE → the P&L**; **ASSET, LIABILITY, EQUITY → the Balance Sheet**.
- **Account-code ranges drive the sub-grouping within a statement** — current vs non-current
  assets/liabilities by code band, and operating / investing / financing classification for the cash-flow
  statement. Worked against the shipped TZ CoA (§3.1), the **default banding** is:

| Section | Statement | Accounts (shipped TZ CoA) | Derivation |
| --- | --- | --- | --- |
| **Current assets** | Balance Sheet | 1000 Cash · 1100 Bank · 1200 AR · 1300 Inventory · 1400 VAT Input · 1500 WHT Receivable | ASSET, code 1000–1499 (current band) |
| **Non-current assets** | Balance Sheet | *(none seeded yet — Fixed Assets deferred)* | ASSET, code 1500–1999 (non-current band) |
| **Current liabilities** | Balance Sheet | 2100 AP · 2200 VAT Payable · 2300 VAT Due · 2400 WHT Payable | LIABILITY, code 2000–2499 (current band) |
| **Non-current liabilities** | Balance Sheet | *(none seeded yet — Loans deferred)* | LIABILITY, code 2500–2999 (non-current band) |
| **Equity** | Balance Sheet | 3000 Owner's Equity · 3900 Retained Earnings · **+ current-year net income** | EQUITY (all 3xxx) + the P&L net folded in (BR-REP-05) |
| **Revenue / gross-profit** | P&L | 4100 Sales Revenue (income) · 5100 COGS · 5150 Purchases (cost of sales) | INCOME + the cost-of-sales expense band → **gross profit** |
| **Operating expenses** | P&L | 5200 Rent · 5300 Salaries · 5400 Utilities | EXPENSE, the operating band (below cost-of-sales) → **net profit** after gross profit |
| **Operating (cash-flow)** | Cash-Flow | net income + ΔAR + ΔAP + ΔInventory + ΔVAT/WHT (working capital) | indirect: net income ± changes in current operating accounts |
| **Investing (cash-flow)** | Cash-Flow | non-current-asset changes *(sparse — no Fixed Assets yet)* | changes in non-current ASSET accounts |
| **Financing (cash-flow)** | Cash-Flow | equity / borrowing changes *(sparse — no Loans yet)* | changes in EQUITY + non-current LIABILITY accounts |

> **The exact code-range boundaries are an ADR/config detail (OQ-REP-01), not a requirements blocker.** This
> document fixes the **principle** (`account_type` for the statement + top section; code range for the
> sub-grouping; the worked TZ-CoA default above) and a **sensible default banding**; ADR-0018 fixes the exact
> boundaries (e.g. whether the current/non-current asset split is 1000–1499 / 1500–1999, and which expense
> band is cost-of-sales vs operating). The cash-flow operating/investing/financing classification is derived
> the same way (BR-REP-07).

> **The reality to state plainly (the cash-flow sparseness, owner-accepted).** There is **no Fixed-Assets and
> no Loans module yet** (ROADMAP T3.3 / Tier-3), so the cash-flow **investing** and **financing** sections —
> and the P&L depreciation add-back — are **currently sparse** (few or no postings flow to them). This is
> **expected, not a defect.** The requirement is that the **structure is correct and ready** (the three
> sections exist, classified by the auto-derive rule) and that the **bar holds**: the **net change in cash
> equals the Cash + Bank GL account movement for the period** (BR-REP-04, the indirect-method tie-out,
> mirroring vat-return.md BR-VAT-08). As Fixed Assets / Loans land, their postings populate the investing /
> financing sections additively — no rework (NFR-REP-06).

> **Flag for the architect (ADR-0018):** the load-bearing decisions are all **read-model design**, not
> business behaviour. ADR-0018 must (1) place the module at **`com.erp.modules.reporting`** with a
> `ReportingService` + `ReportingController` (no `ReportingController` exists today — ROADMAP T2.3); (2)
> design the **GL aggregation** for P&L / BS / CF over `journal_lines` (building on the existing
> `TrialBalanceQuery` — reuse it, do not re-implement the period sum); (3) implement the **auto-derive
> `account_type` + code-range → section mapping** (OQ-REP-01 banding) — a derivation, not a stored template;
> (4) construct the **indirect cash-flow** (net income + working-capital deltas) and the **cash tie-out**
> (net change == Cash+Bank movement, BR-REP-04); (5) the **comparative** (two period/as-at windows, run side
> by side); (6) the **PDF + Excel/CSV export** approach + the library/dependency (a faithful printable PDF +
> a spreadsheet export — OQ-REP-05; library is the architect's call); (7) the **perms** + a small **V15
> perm-seed migration** that seeds the `REPORT.*` permissions and grants them to `ORG_ADMIN` — even though
> Reporting creates **no new business tables**; (8) `ScopeGuard` / read-only — Reporting **never** posts, so
> there is **no** new `ScopeGuard` target type for a writable aggregate (it reads GL accounts, already
> `case "account"`); (9) an **ArchUnit boundary test** that Reporting depends only on GL's **read** surface
> (`reporting → gl.read`), never on a GL posting entity or any sub-ledger entity (gl.md NFR-GL-07 discipline).
> State these; do not design the tables here. **None blocks the requirements** — the statements' content and
> the reconciliation bars are fixed; the read-model + export mechanics are the ADR's.

### Vocabulary (read this first)

- **Financial statement** — a structured presentation of the books for an external/management reader, derived
  **read-only** from GL `journal_lines`. The three primary statements in v1 are the **Income Statement / P&L**,
  the **Balance Sheet**, and the **Cash-Flow Statement**. A statement is **not** a trial balance (a flat
  account list) — it groups accounts into meaningful sections by the auto-derive rule (BR-REP-07).
- **Income Statement / Profit & Loss (P&L)** — the statement of **INCOME − EXPENSE over a period** (a date
  range): revenue, less cost of sales = **gross profit**; less operating expenses = **net profit**. A
  **period** (flow) statement, not an as-at (stock) statement. Reads the INCOME (4xxx) and EXPENSE (5xxx)
  accounts (gl.md FR-GL-05).
- **Gross profit** — **revenue (INCOME) − cost of sales** (the cost-of-sales expense band, e.g. 5100 COGS +
  5150 Purchases). The first subtotal on the P&L.
- **Operating profit / net profit** — **gross profit − operating expenses** (5200 Rent, 5300 Salaries, 5400
  Utilities, …). In v1 with no separate finance/tax lines yet, the bottom line is **net profit** (operating
  profit and net profit coincide until interest/tax lines exist).
- **Balance Sheet** — the statement of financial position **as-at a date** (a stock statement): **ASSET =
  LIABILITY + EQUITY**, grouped **current vs non-current**. Reads the ASSET (1xxx), LIABILITY (2xxx), and
  EQUITY (3xxx) accounts plus the **current-year net income** folded into equity (BR-REP-05). Must balance
  (BR-REP-02).
- **Current vs non-current** — the Balance-Sheet sub-grouping by realisation horizon: **current** assets /
  liabilities are realised / settled within ~12 months (cash, AR, inventory, AP, tax payable); **non-current**
  beyond (fixed assets, long-term loans — sparse in v1). Derived from the account-code band (BR-REP-07,
  OQ-REP-01).
- **Retained earnings** — the EQUITY account (3900) carrying accumulated prior-period profit. On the Balance
  Sheet, **as-at equity = retained earnings + the current-year net income to date** (the P&L net for the
  current fiscal year), so the BS balances before any year-end closing entry posts (BR-REP-05).
- **Current-year net income** — the P&L **net profit for the current fiscal year up to the as-at date**,
  **folded into Balance-Sheet equity** so ASSET = LIABILITY + EQUITY holds mid-year (BR-REP-05). It is a
  **presentation derivation** (the year's INCOME − EXPENSE movement), **not** a posted closing entry (v1 has
  no automated year-end roll-up — gl.md §10.6 / OQ-GL-03).
- **Cash-Flow Statement (indirect method)** — the statement of how cash moved over a period, built
  **indirectly**: start from **net income**, add back **non-cash items** (e.g. depreciation — sparse in v1),
  adjust for **working-capital changes** (ΔAR, ΔAP, ΔInventory, ΔVAT/WHT control accounts) → **operating**
  cash flow; adjust for **non-current-asset changes** → **investing**; for **equity / borrowing changes** →
  **financing**. The **net change in cash** must equal the **Cash + Bank GL account movement** between the two
  dates (BR-REP-04).
- **Operating / investing / financing** — the three cash-flow sections. **Operating** = cash from trading
  (net income ± working-capital changes); **investing** = cash for/from long-term assets (sparse, no Fixed
  Assets yet); **financing** = cash from/to owners and lenders (sparse, no Loans yet). Classified from
  `account_type` + code range (BR-REP-07).
- **Working-capital change** — the period **change** (closing − opening balance) in a current operating
  account (AR, AP, Inventory, VAT/WHT control accounts), used in the indirect operating section: e.g. a
  **rise in AR** *uses* cash (subtract), a **rise in AP** *provides* cash (add). The cash-flow equivalent of
  comparing two trial balances.
- **Comparative period** — the **prior** period / as-at date shown **alongside** the selected one on every
  statement (this period vs prior period for P&L/CF; this date vs a prior date for BS) — the standard
  accounting presentation (BR-REP-01). The comparative is computed exactly as the primary, over the prior
  window.
- **Account ledger / drill-down** — for a single GL account, the **running ledger** over a period: the
  **opening balance**, each **journal line** (posting date, source, reference, debit, credit), the **running
  balance** after each line, and the **closing balance**. The **drill target** from any statement line — the
  bridge from an aggregated figure to the postings behind it (FR-REP-04, BR-REP-06).
- **As-at vs period** — a **Balance Sheet** is **as-at a date** (a point-in-time stock: balances accumulated
  from inception to that date); a **P&L / Cash-Flow** is **over a period** (a flow between two dates). The
  period selector differs accordingly (FR-REP-07).
- **Base currency** — every statement figure is in the **company base currency** (TZS in practice; ADR-0005
  D-4 / gl.md BR-GL-06). No FX / presentation-currency translation in v1 (BR-REP-09).
- **Read-only over GL** — Reporting **reads** `journal_lines` / `chart_of_accounts` / `fiscal_periods` and
  **posts nothing** (BR-REP-08). It owns **no business table, no sub-ledger, no numbered entity** — it is a
  query + presentation layer over the books.

> **Word discipline (carried into the glossary):** a **financial statement** (a grouped, presented view) is
> **not** a **trial balance** (a flat account list — gl.md) — the TB is the raw input, the statement is the
> presentation. The **P&L net** (a period flow) is **not** the **Balance-Sheet equity** (an as-at stock) —
> but the P&L net **folds into** the BS equity (BR-REP-05). **As-at** (a point in time) is **not** **over a
> period** (between two dates) — a Balance Sheet is as-at; a P&L is over a period. A **comparative** column
> (the prior window, shown alongside) is **not** a budget (a plan — Budgeting, T3.6, deferred). A **drill-down**
> reaches the **account ledger** (the journal lines behind a figure); the account ledger is **not** a
> **sub-ledger** (the AR/AP per-party detail — those are AR/AP modules). An **account** (a GL bucket) is
> **not** a **cash/bank account** (a money location — Cash & Bank) and **not** a **party** — the
> three-distinct-things rule (gl.md).

## 2. Scope

> Every line below is **ratified v1** (owner-confirmed 2026-06-10). This is **Financial Reporting — the first
> Reporting slice (ROADMAP T2.3 / Phase A)**: the three primary statements (P&L, Balance Sheet, Cash-Flow
> indirect), the GL account-ledger drill-down, comparative columns, and PDF / Excel-CSV export — all
> **read-only over GL**, with the auto-derived `account_type` + code-range mapping. It **reads** the books;
> it **posts nothing** and owns **no new business table**.

### In scope (v1 — "turn the balanced books into the three primary statements + the drill-down, comparative, exportable")

- **Income Statement / P&L over a period (with comparative).** INCOME − EXPENSE for a selected **date range**:
  revenue less cost of sales = **gross profit**, less operating expenses = **net profit**; shown with a
  **comparative** prior period (FR-REP-01). The P&L net **reconciles** to the period's INCOME − EXPENSE GL
  movement (BR-REP-03).
- **Balance Sheet as-at a date (with comparative).** **ASSET = LIABILITY + EQUITY** as-at a selected date,
  grouped **current vs non-current**, with the **current-year net income folded into equity** (retained
  earnings + current-year net income — BR-REP-05); shown with a **comparative** as-at a prior date
  (FR-REP-02). The Balance Sheet **must balance** (BR-REP-02).
- **Cash-Flow Statement — INDIRECT method (with comparative).** Net income + non-cash adjustments ±
  working-capital changes (ΔAR, ΔAP, ΔInventory, ΔVAT/WHT) = **operating**; ± non-current-asset changes =
  **investing**; ± equity / borrowing changes = **financing**; shown with a **comparative** prior period
  (FR-REP-03). The **net change in cash == the Cash + Bank GL account movement** between the two dates
  (BR-REP-04, the tie-out). The investing / financing sections are **structurally present but sparse** in v1
  (no Fixed Assets / Loans yet — §1, accepted).
- **GL account-ledger drill-down.** For any account, over a period: **opening balance**, each **journal line**
  (posting date, source, reference, debit, credit), the **running balance**, and the **closing balance** — the
  **drill target** from any statement line (FR-REP-04). Every statement figure traces to the ledger
  (BR-REP-06).
- **Comparative columns on every statement.** Each statement shows the **selected** period/date **and** a
  **comparative** (prior period / prior year / opening) — the standard accounting presentation (FR-REP-06,
  BR-REP-01). The comparative window defaults to the **immediately prior period of the same length** for
  P&L/CF, and the **prior year-end (or the start of the selected period)** for BS (OQ-REP-03).
- **Period selection.** Statements run for an **arbitrary date range** (P&L / Cash-Flow) or an **as-at date**
  (Balance Sheet), with **fiscal-period / fiscal-year quick-selects** (this month, this quarter, this fiscal
  year, a named period) resolved through the GL `fiscal_periods` / `FiscalPeriodResolver` (FR-REP-07).
- **Export to PDF and Excel/CSV.** Each statement (P&L, BS, Cash-Flow) **and** the account-ledger drill-down
  is exportable to a **faithful printable PDF** and to a **spreadsheet (Excel / CSV)** (FR-REP-05). The export
  is a **faithful rendering** of the on-screen statement (same figures, same sections, the comparative
  column, the company name + period header). *(The library / where-generated is the architect's ADR call —
  OQ-REP-05; the requirement is the printable PDF + the spreadsheet.)*
- **Read-only over GL — POSTS NOTHING.** Reporting **reads** `journal_lines` / `chart_of_accounts` /
  `fiscal_periods` (building on `TrialBalanceQuery`) and **posts no journal, creates no business table, owns
  no numbered entity** (BR-REP-08). The correctness bars (§6) hold *because* the books are double-entry; a
  statement that breaks a bar is a **read-side defect / data-integrity alarm**, surfaced — never "fixed" by
  Reporting (it cannot write).
- **Base currency only.** Every figure is in the **company base currency** (TZS in practice; ADR-0005 D-4 /
  gl.md BR-GL-06). No FX / presentation-currency translation in v1 (BR-REP-09).
- **Auto-derived statement → account mapping.** P&L groups, Balance-Sheet sections, and cash-flow
  classification are **derived** from `account_type` (the statement + top section) + account-code ranges (the
  sub-grouping), against the shipped TZ CoA (§3.1) — **NO configurable statement-template table** (BR-REP-07).
- **Permissions** — `REPORT.VIEW` (view statements + the drill-down) at minimum, with a **recommended finer
  split**: `REPORT.PL.VIEW` (P&L), `REPORT.BS.VIEW` (Balance Sheet), `REPORT.CASHFLOW.VIEW` (Cash-Flow),
  `REPORT.LEDGER.VIEW` (the account-ledger drill-down), and `REPORT.EXPORT` (export to PDF / Excel) — because
  financial statements can be sensitive (an owner may let a clerk see the ledger but not the company P&L)
  (FR-REP-08, OQ-REP-04). Per-company scope; `assertCanActIn` on **every read path**. Perms are seeded via a
  small **V15 migration** (even with no new tables) and granted to `ORG_ADMIN` — flagged for ADR-0018.

### Deferred (recognised, NOT built in v1 — separate later increments)

- **Configurable statement templates.** v1 **auto-derives** the statement → account mapping from `account_type`
  + code range (BR-REP-07). A user-configurable **statement-template / report-builder** (drag accounts into
  custom statement lines, define custom subtotals, save layouts) is **deferred** (OQ-REP-06) — the auto-derive
  model does not preclude it (NFR-REP-07).
- **Segment / cost-centre / dimension reporting.** v1 reports at **company level** (a posting may carry an
  originating branch as a tag, gl.md NFR-GL-01, but the statements are company-level). Per-cost-centre /
  per-segment / per-project P&L and the **departmental Balance Sheet** wait on the **cost-centre / dimension
  framework** (ROADMAP T3.5 / T3.6, PATH-TO-FULL-ERP §4.4) — deferred (OQ-REP-07).
- **Consolidated / multi-company group reporting.** v1 reports **per company** (BR-REP-10). A **consolidated**
  statement across companies (group reporting currency, intercompany elimination) is deferred (gl.md
  OQ-CUR-01) — the per-company model does not preclude it.
- **Budget vs actual.** v1 shows **actual vs a prior comparative** (BR-REP-01). **Budget-vs-actual** (and
  variance) waits on the **Budgeting module** (ROADMAP T3.6) — deferred. The comparative-column mechanism is
  built so a budget column is additive.
- **Ratio analysis / KPI dashboards / analytics.** v1 delivers the three statements + the ledger. Financial
  **ratios** (current ratio, gross-margin %, gearing), **KPI dashboards**, and **sales / stock / purchase
  analytics** are the **Reporting depth / dashboards** slice (ROADMAP T2.3 depth, PATH-TO-FULL-ERP Phase D) —
  deferred (OQ-REP-08).
- **Statement of Changes in Equity.** v1 ships the three primary statements (P&L, BS, Cash-Flow). The
  **Statement of Changes in Equity** (the fourth IFRS statement) is deferred (PATH-TO-FULL-ERP §6 lists it as
  a "full ERP" exit item) — the equity-folding logic (BR-REP-05) is the foundation it builds on.
- **Multi-currency / presentation-currency translation.** v1 is **base currency (TZS)** (BR-REP-09). A
  foreign-currency / presentation-currency statement (translate balances at a closing/average rate) is
  deferred to the FX cross-cutting item (ROADMAP X.6 / gl.md §10.5, ADR-0005 D-8) — OQ-REP-09. Not precluded.
- **Scheduled / emailed reports.** v1 is **on-demand** (run + view + export). **Scheduling** a statement (run
  monthly, email the PDF) waits on the **Notifications** cross-cutting capability (ROADMAP X.2,
  PATH-TO-FULL-ERP §6 "key reports schedulable/emailable") — deferred (OQ-REP-10).
- **Cash-Flow direct method.** v1 uses the **indirect** method (net income + working-capital changes,
  BR-REP-04). A **direct-method** cash-flow (gross operating receipts and payments) is deferred — the indirect
  model does not preclude it.

### Explicitly NOT this module

- **The General Ledger itself + any posting.** **GL** (ADR-0013) owns the books, the chart of accounts, the
  fiscal periods, and the **trial balance** read (gl.md FR-GL-16). Reporting **reads** GL and **posts
  nothing** (BR-REP-08); it never edits a journal, never closes a period, never seeds an account. The
  **year-end close** (P&L → retained earnings closing entry, opening carry-forward) is **GL depth** (gl.md
  §10.6 / OQ-GL-03), not Reporting — Reporting *presents* current-year net income in equity (BR-REP-05)
  **without** posting a closing entry.
- **The sub-ledgers (AR / AP / Cash & Bank / VAT).** AR/AP own the per-party detail and ageing; Cash & Bank
  owns the per-account statement + balance + bank reconciliation; VAT owns the return face + WHT register.
  Reporting reads the **GL control-account totals** these post, **not** the per-party / per-account detail
  (the **drill-down reaches the GL account ledger**, not the sub-ledger — FR-REP-04). Customer ageing, the
  bank reconciliation, the VAT return face are their own modules' reads.
- **Operational / non-financial analytics.** Sales-by-customer, sales-by-product, stock ageing, purchase
  variance, and the role-based dashboards are the **Reporting analytics / dashboards** slice (ROADMAP T2.3
  depth) — **not** this financial-statements slice (OQ-REP-08, deferred).
- **Document numbering / `code_sequence`.** Reporting produces **no numbered entity** (a statement is a
  read, not a document) — it does **not** allocate a `code_sequence` number (unlike GL's `JB-####`, VAT's
  `VATR-####`, Cash & Bank's `CB-####`). The exported PDF carries the company + period header, not a document
  number.

## 3. The statements: derivation, content, the reconciliation bars

### 3.1 The shipped TZ chart of accounts (the worked example for the auto-derive mapping)

The auto-derive mapping (BR-REP-07) is worked against the **shipped** TZ small-business CoA (V10 seed +
V12 + V14 additions — gl.md §3.1, the authoritative seed):

| Code | Name | `account_type` | `normal_balance` | Statement / section (auto-derived) |
| --- | --- | --- | --- | --- |
| **1000** | Cash | ASSET | DEBIT | Balance Sheet · current assets |
| **1100** | Bank | ASSET | DEBIT | Balance Sheet · current assets |
| **1200** | Accounts Receivable | ASSET | DEBIT | Balance Sheet · current assets (CF working capital) |
| **1300** | Inventory | ASSET | DEBIT | Balance Sheet · current assets (CF working capital) |
| **1400** | VAT Input | ASSET | DEBIT | Balance Sheet · current assets (CF working capital) |
| **1500** | WHT Receivable | ASSET | DEBIT | Balance Sheet · current assets (CF working capital) |
| **2100** | Accounts Payable | LIABILITY | CREDIT | Balance Sheet · current liabilities (CF working capital) |
| **2200** | VAT Payable | LIABILITY | CREDIT | Balance Sheet · current liabilities (CF working capital) |
| **2300** | VAT Due (to TRA) | LIABILITY | CREDIT | Balance Sheet · current liabilities (CF working capital) |
| **2400** | WHT Payable (to TRA) | LIABILITY | CREDIT | Balance Sheet · current liabilities (CF working capital) |
| **3000** | Owner's Equity / Capital | EQUITY | CREDIT | Balance Sheet · equity (CF financing) |
| **3900** | Retained Earnings | EQUITY | CREDIT | Balance Sheet · equity (+ current-year net income — BR-REP-05) |
| **4100** | Sales Revenue | INCOME | CREDIT | P&L · revenue (gross-profit) |
| **5100** | Cost of Goods Sold | EXPENSE | DEBIT | P&L · cost of sales (gross-profit) |
| **5150** | Purchases | EXPENSE | DEBIT | P&L · cost of sales (gross-profit) |
| **5200** | Rent Expense | EXPENSE | DEBIT | P&L · operating expenses (net-profit) |
| **5300** | Salaries & Wages | EXPENSE | DEBIT | P&L · operating expenses (net-profit) |
| **5400** | Utilities | EXPENSE | DEBIT | P&L · operating expenses (net-profit) |

The mapping is a **derivation, not a stored table**: `account_type` decides the statement + top section
(gl.md BR-GL-12 — the type is the authority, not the code); the **code band** decides the sub-grouping
(current vs non-current; cost-of-sales vs operating; the CF section). The Cash + Bank accounts (1000, 1100)
are the **cash basis the cash-flow statement ties to** (BR-REP-04). The current/non-current boundaries and
the cost-of-sales vs operating split are the **ADR/config detail** (OQ-REP-01) — the principle and the
default banding are fixed here.

### 3.2 Income Statement / P&L (period, comparative)

The P&L is INCOME − EXPENSE **over a selected date range**, presented as:

1. **Revenue** — the INCOME accounts (4xxx; the period's credit-side net movement, presented positive).
2. less **cost of sales** — the cost-of-sales expense band (5100 COGS + 5150 Purchases) → **= Gross profit**.
3. less **operating expenses** — the operating expense band (5200 Rent, 5300 Salaries, 5400 Utilities, …)
   → **= Net profit** (operating profit and net profit coincide until finance/tax lines exist).

A **comparative** prior period (same length, immediately prior — OQ-REP-03) is shown alongside. The P&L
reads only INCOME / EXPENSE accounts, by `account_type` (BR-REP-07). **The P&L net for the period == the
period's INCOME − EXPENSE movement on `journal_lines`** (BR-REP-03) — the statement is exactly the ledger,
grouped. Each line drills to its account ledger (FR-REP-04).

### 3.3 Balance Sheet (as-at, comparative)

The Balance Sheet is the financial position **as-at a selected date**:

- **Assets** — the ASSET accounts (1xxx), grouped **current** (1000–1499 band: Cash, Bank, AR, Inventory,
  VAT Input, WHT Receivable) vs **non-current** (1500–1999 band: sparse — no Fixed Assets yet).
- **Liabilities** — the LIABILITY accounts (2xxx), grouped **current** (2000–2499: AP, VAT Payable, VAT Due,
  WHT Payable) vs **non-current** (2500–2999: sparse — no Loans yet).
- **Equity** — the EQUITY accounts (3xxx: Owner's Equity, Retained Earnings) **+ the current-year net income
  to date** folded in (BR-REP-05).

**ASSET == LIABILITY + EQUITY** for any as-at date (BR-REP-02) — it **must** balance, because GL is
double-entry: the sum of debit-balance accounts (assets + expenses) equals the sum of credit-balance accounts
(liabilities + equity + income), and the current-year net income (income − expense) folded into equity makes
the as-at identity hold **before** any year-end closing entry exists (BR-REP-05). A non-balancing Balance
Sheet is a **defect / data-integrity alarm**, not a presentation choice. A **comparative** as-at a prior date
(the prior year-end, or the start of the selected period — OQ-REP-03) is shown alongside.

> **Why the current-year net income must fold into equity (the non-obvious bit).** Mid-year, the year's
> profit sits as movement on the INCOME / EXPENSE accounts; it has **not** yet been closed to Retained
> Earnings (v1 has no automated year-end roll-up — gl.md §10.6). If the Balance Sheet showed only the posted
> 3xxx balances, it would be **out of balance by exactly the year's net income**. So the Balance Sheet
> **computes** the current-year net income (the year-to-date INCOME − EXPENSE) and **presents it within
> equity** (a "current-year earnings" line under retained earnings) — a **presentation derivation**, not a
> posted entry. This is the standard "retained earnings + current-year net income" presentation, and it is
> what makes a mid-year BS balance (BR-REP-05).

### 3.4 Cash-Flow Statement — indirect method (period, comparative)

The Cash-Flow Statement, built **indirectly** over a selected date range:

- **Operating** — start from **net income** (the period P&L net), add back **non-cash items** (e.g.
  depreciation — sparse in v1, no Fixed Assets), then adjust for **working-capital changes** (the period
  **change** in current operating accounts): a rise in **AR / Inventory / VAT Input / WHT Receivable** *uses*
  cash (subtract); a rise in **AP / VAT Payable / VAT Due / WHT Payable** *provides* cash (add).
- **Investing** — the period **change** in **non-current ASSET** accounts (fixed-asset purchases/disposals —
  sparse in v1).
- **Financing** — the period **change** in **EQUITY + non-current LIABILITY** accounts (owner capital
  injections / drawings, loan draws/repayments — sparse in v1).
- **= Net change in cash**, which **must equal the movement on the Cash + Bank GL accounts (1000 + 1100)**
  between the two dates (BR-REP-04, the tie-out). The statement opens with the opening cash + bank balance
  and closes with the closing cash + bank balance; the change between them is the net change in cash, and the
  three sections must sum to it.

The classification (operating / investing / financing) is **auto-derived** from `account_type` + code range
(BR-REP-07). The investing / financing sections are **structurally present but currently sparse** — no Fixed
Assets, no Loans yet (§1, accepted) — but the **structure is correct and ready**, and the **net-change-equals-
Cash+Bank-movement bar holds regardless** (BR-REP-04). A **comparative** prior period is shown alongside. The
VAT / WHT control accounts (1400, 1500, 2200, 2300, 2400) are treated as **working-capital** items in the
operating section by default (OQ-REP-02).

### 3.5 The GL account-ledger drill-down (the drill target)

From any statement line, the reader **drills into** the underlying account's **ledger** over the statement's
period: the account's **opening balance** (as-at the period start), then **each journal line** in the period
in posting-date order — the **posting date**, the **source** (the module / event that posted it: a sales
auto-post, an AR receipt, an AP payment, a Cash & Bank transfer, a manual journal, the VAT settlement), the
**reference** (the journal batch `JB-####` / source document), the **debit** and the **credit** — with a
**running balance** after each line, ending at the **closing balance** (= the figure the statement line
showed for that account). This is how **every statement figure traces to GL journal lines** (BR-REP-06): the
drill-down is the bridge from the aggregated statement line to the postings that produced it. The ledger is a
**read** over `journal_lines` for one account; it is **not** the AR/AP sub-ledger (per-party detail) — it is
the **GL** account ledger.

## 4. Actors / personas

- **Owner / General Manager** — reads the **P&L** ("did we make money, vs last period?") and the **Balance
  Sheet** ("what do we own and owe?") to run the business; exports a PDF for a meeting or the bank. The
  primary consumer of the statements. Holds `REPORT.VIEW` (or the finer `REPORT.PL.VIEW` / `REPORT.BS.VIEW` /
  `REPORT.EXPORT`).
- **Accountant / bookkeeper** — runs all three statements, **drills into the account ledger** to verify a
  figure or investigate a balance, and exports to Excel for further work. The day-to-day operator of the
  reads. Holds `REPORT.VIEW` + `REPORT.LEDGER.VIEW` + `REPORT.EXPORT`.
- **Financial controller** — owns the integrity of the statements: confirms the **Balance Sheet balances**,
  the **cash-flow ties to cash**, the **P&L reconciles to the ledger**, and reviews the comparative
  movements before sharing externally. The senior finance authority reading the books through the statements.
  Holds all `REPORT.*`.
- **Auditor / external reviewer (read-only)** — reads the statements and **drills every figure down to the
  journal lines** to trace and verify; posts nothing, exports for the audit file. Holds `REPORT.VIEW` +
  `REPORT.LEDGER.VIEW` + `REPORT.EXPORT` (read-only).
- *(No SYSTEM actor — Reporting has **no auto-poster and no scheduled run** in v1; every statement is an
  **in-request, on-demand read** by a human. Reporting reads GL; it never writes — there is no outbox
  consumer, no synchronous post, no numbered entity created here.)*

## 5. Functional requirements

> IDs are `FR-REP-NN`. Each is a crisp, testable, **ratified** statement. "Read GL" = query
> `journal_lines` / `chart_of_accounts` / `fiscal_periods` (building on the existing `TrialBalanceQuery` —
> gl.md FR-GL-16); Reporting **posts nothing** (BR-REP-08). "Period" = a date range (P&L / Cash-Flow);
> "as-at" = a single date (Balance Sheet). "Comparative" = the prior window shown alongside (BR-REP-01).

### Income Statement / P&L

- **FR-REP-01** A user with the P&L permission (`REPORT.VIEW` / `REPORT.PL.VIEW`) may **run an Income
  Statement / P&L for a date range**: **revenue** (INCOME accounts) less **cost of sales** (the cost-of-sales
  expense band) = **gross profit**, less **operating expenses** (the operating expense band) = **net profit**
  — computed as the period movement of INCOME / EXPENSE accounts on `journal_lines`, grouped by the
  auto-derive rule (BR-REP-07). The statement shows a **comparative** prior period (BR-REP-01). The P&L net
  **reconciles** to the period's INCOME − EXPENSE GL movement (BR-REP-03).

### Balance Sheet

- **FR-REP-02** A user with the BS permission (`REPORT.VIEW` / `REPORT.BS.VIEW`) may **run a Balance Sheet
  as-at a date**: **assets** (ASSET accounts, current vs non-current) = **liabilities** (LIABILITY accounts,
  current vs non-current) + **equity** (EQUITY accounts + the **current-year net income** folded in —
  BR-REP-05), grouped by the auto-derive rule (BR-REP-07). The statement shows a **comparative** as-at a prior
  date (BR-REP-01). **ASSET == LIABILITY + EQUITY** must hold (BR-REP-02); a non-balancing Balance Sheet is a
  **data-integrity alarm** surfaced for investigation.

### Cash-Flow Statement (indirect)

- **FR-REP-03** A user with the Cash-Flow permission (`REPORT.VIEW` / `REPORT.CASHFLOW.VIEW`) may **run a
  Cash-Flow Statement (indirect method) for a date range**: **operating** (net income + non-cash adjustments
  ± working-capital changes — ΔAR, ΔAP, ΔInventory, ΔVAT/WHT), **investing** (± non-current-asset changes),
  **financing** (± equity / borrowing changes), classified by the auto-derive rule (BR-REP-07). The statement
  shows a **comparative** prior period (BR-REP-01). The **net change in cash == the Cash + Bank GL account
  (1000 + 1100) movement** between the two dates (BR-REP-04, the tie-out). The investing / financing sections
  are **structurally present but sparse** in v1 (no Fixed Assets / Loans — §1).

### GL account-ledger drill-down

- **FR-REP-04** A user with the ledger permission (`REPORT.VIEW` / `REPORT.LEDGER.VIEW`) may **drill from any
  statement line into the GL account ledger** for the underlying account over the statement's period: the
  **opening balance**, each **journal line** (posting date, source, reference, debit, credit) in posting-date
  order, the **running balance**, and the **closing balance**. Every statement figure **traces** to these
  journal lines (BR-REP-06). The ledger is a **read** over `journal_lines` for one account (not the AR/AP
  sub-ledger).

### Export

- **FR-REP-05** A user with `REPORT.EXPORT` may **export** any statement (P&L, Balance Sheet, Cash-Flow) and
  the account-ledger drill-down to a **faithful printable PDF** and to a **spreadsheet (Excel / CSV)**. The
  export is a **faithful rendering** of the on-screen statement — the same figures, the same sections, the
  comparative column, and a header carrying the **company name + the period / as-at date + the base
  currency** (the PDF is **print-faithful**, NFR-REP-04). *(The library / generation approach is the
  architect's — OQ-REP-05.)*

### Period selection / comparative

- **FR-REP-06** Every statement shows the **selected** window **and** a **comparative** window side by side
  (BR-REP-01): this period vs the prior period (P&L / Cash-Flow), this as-at date vs a prior as-at date
  (Balance Sheet). The comparative defaults to the immediately prior period of the same length (P&L / CF) /
  the prior year-end or the period start (BS), and may be **overridden** to a chosen prior window (OQ-REP-03).
- **FR-REP-07** A user may **select the reporting window**: an **arbitrary date range** (P&L / Cash-Flow) or
  an **as-at date** (Balance Sheet), with **fiscal-period / fiscal-year quick-selects** (this month, this
  quarter, this fiscal year, a named period) resolved through the GL `fiscal_periods` / `FiscalPeriodResolver`
  (gl.md FR-GL-14). All figures are in the **company base currency** (BR-REP-09).

### Scope & permissions

- **FR-REP-08** All Reporting reads are **scoped per company** and **gated by IAM permissions**: at minimum
  `REPORT.VIEW` (statements + drill-down), with the recommended finer split `REPORT.PL.VIEW` /
  `REPORT.BS.VIEW` / `REPORT.CASHFLOW.VIEW` / `REPORT.LEDGER.VIEW` / `REPORT.EXPORT` (financial statements can
  be sensitive — OQ-REP-04). Exact codes are seeded with the module via a small **V15 perm-seed migration**
  (no new business tables) and granted to `ORG_ADMIN` (FR-IAM-11; flagged for ADR-0018). Per-company scope;
  `assertCanActIn` guards **every read path**; **no read crosses company scope** (BR-REP-10, NFR-REP-01).
  Reporting **posts nothing and creates no entity**, so there is **no mutation to audit** beyond the platform
  read-access logging (NFR-REP-05).

## 6. Business rules (invariants)

> Ratified. These are the Reporting correctness / reconciliation bars; a violation that breaks the
> Balance-Sheet balance, the cash-flow tie-out, or the P&L-to-ledger reconciliation is a **finance-grade
> defect / data-integrity alarm** (a release blocker). Because Reporting is read-only, a broken bar means the
> **books or the read query is wrong** — Reporting surfaces it; it cannot "fix" it by writing.

- **BR-REP-01 — Every statement is comparative.** Each statement shows the **selected** window **and** a
  **comparative** prior window (prior period / prior year / opening) — the standard accounting presentation
  (FR-REP-06). The comparative is computed exactly as the primary, over the prior window.
- **BR-REP-02 — The Balance Sheet must balance.** **ASSET == LIABILITY + EQUITY** for **any** as-at date
  (FR-REP-02), because GL is double-entry (Σ debits == Σ credits per entry — gl.md BR-GL-01) and the
  current-year net income is folded into equity (BR-REP-05). A **non-balancing** Balance Sheet is a
  **defect / data-integrity alarm**, never a presentation tolerance.
- **BR-REP-03 — The P&L net == the period's INCOME − EXPENSE GL movement.** The net profit a P&L reports for
  a date range **equals** the net movement of all INCOME and EXPENSE accounts over that range on
  `journal_lines` (FR-REP-01). The statement cannot show a net the ledger does not carry.
- **BR-REP-04 — The Cash-Flow net change in cash == the Cash + Bank GL account movement.** The bottom-line
  net change in cash on the indirect cash-flow statement **equals** the movement on the Cash + Bank GL
  accounts (1000 + 1100) between the two dates (FR-REP-03) — the indirect-method tie-out (the cash-flow
  analogue of vat-return.md BR-VAT-08 / cash-and-bank.md BR-CASH-02). A cash-flow that does not tie to the
  cash + bank movement is a **defect**. The investing / financing sparseness (no FA / Loans yet) does **not**
  relax this bar — the bar holds regardless.
- **BR-REP-05 — Current-year net income folds into Balance-Sheet equity.** The as-at equity **=** the posted
  EQUITY-account balances (Owner's Equity + Retained Earnings) **+ the current-year net income to date** (the
  year-to-date INCOME − EXPENSE), a **presentation derivation** (not a posted closing entry — v1 has no
  automated year-end roll-up, gl.md §10.6 / OQ-GL-03). This is what makes a **mid-year** Balance Sheet balance
  (BR-REP-02) before the year is closed.
- **BR-REP-06 — Every statement figure traces to GL journal lines.** Each statement line is the aggregation
  of named accounts, and each account **drills** to its account ledger (the `journal_lines` that produced the
  balance) — every number is **explainable to the posting** (FR-REP-04). No statement figure is a black box.
- **BR-REP-07 — Auto-derived mapping from `account_type` + code range; no template table.** The statement →
  account mapping is **derived**: `account_type` decides the statement + top section (INCOME/EXPENSE → P&L;
  ASSET/LIABILITY/EQUITY → Balance Sheet; the type is the authority — gl.md BR-GL-12), and the **account-code
  range** decides the sub-grouping (current vs non-current; cost-of-sales vs operating; the CF
  operating/investing/financing section). There is **NO configurable statement-template table** in v1
  (FR-REP-01/02/03). The exact code-range boundaries are an ADR/config detail (OQ-REP-01).
- **BR-REP-08 — Read-only: Reporting posts nothing and owns no business table.** Reporting **reads**
  `journal_lines` / `chart_of_accounts` / `fiscal_periods` and **posts no journal, creates no business table,
  allocates no `code_sequence` number, owns no sub-ledger** (FR-REP-01..08). It is a query + presentation
  layer; a write path in the reporting module is a **defect** (NFR-REP-03).
- **BR-REP-09 — Base-currency statements (v1).** Every figure on every statement and the ledger is in the
  **company base currency** (TZS in practice; ADR-0005 D-4 / gl.md BR-GL-06). No FX / presentation-currency
  translation in v1 (deferred, OQ-REP-09).
- **BR-REP-10 — One company's statements are isolated.** Every statement and drill-down is **scoped to exactly
  one company**; no figure or read crosses company scope (FR-REP-08). Cross-company reporting leakage is a
  **release blocker** (NFR-REP-01), as for GL/AR/AP/Cash/VAT. (A posting may carry an originating branch as a
  tag, but the statements are **company-level** — gl.md NFR-GL-01; per-segment/branch reporting is deferred,
  OQ-REP-07.)

## 7. Process flows (happy path + main unhappy paths), ratified v1

### 7.1 Run a P&L → drill into an account ledger → export — happy path
1. The owner / accountant (`REPORT.VIEW` / `REPORT.PL.VIEW`, active company) **selects a P&L** for a **date
   range** (or a fiscal-period quick-select — FR-REP-07) and runs it.
2. The system reads `journal_lines` (via `TrialBalanceQuery` over the range), groups INCOME / EXPENSE accounts
   by the auto-derive rule (BR-REP-07), and presents **revenue → gross profit → operating expenses → net
   profit**, with a **comparative** prior period alongside (FR-REP-01, BR-REP-01).
3. The reader **drills into** a line (e.g. Salaries) → the **account ledger** for 5300 over the period:
   opening balance, each journal line (date / source / reference / debit / credit), running balance, closing
   balance (FR-REP-04). The closing balance **equals** the figure the P&L line showed (BR-REP-06).
4. The reader (`REPORT.EXPORT`) **exports** the P&L to **PDF** (print-faithful, company + period header) and
   to **Excel/CSV** (FR-REP-05). The **P&L net == the period's INCOME − EXPENSE GL movement** (BR-REP-03, the
   reconciliation bar).

### 7.2 Run a Balance Sheet → verify it balances — happy path
1. The controller (`REPORT.VIEW` / `REPORT.BS.VIEW`) **selects a Balance Sheet as-at a date** (FR-REP-07) and
   runs it.
2. The system reads account balances as-at the date, groups ASSET / LIABILITY / EQUITY by the auto-derive
   rule (current vs non-current — BR-REP-07), and **folds the current-year net income into equity**
   (retained earnings + current-year net income — BR-REP-05), with a **comparative** as-at a prior date
   (BR-REP-01).
3. The Balance Sheet shows **ASSET == LIABILITY + EQUITY** (BR-REP-02) — it **balances**, because GL is
   double-entry and the year's net income is folded in. The controller exports it (FR-REP-05) and drills any
   line to its account ledger to verify (FR-REP-04, BR-REP-06).

### 7.3 Run a Cash-Flow (indirect) → tie to the cash movement — happy path
1. The accountant (`REPORT.VIEW` / `REPORT.CASHFLOW.VIEW`) **selects a Cash-Flow Statement for a date range**
   (FR-REP-07) and runs it (indirect method).
2. The system starts from **net income** (the period P&L net), adjusts for **working-capital changes** (ΔAR,
   ΔAP, ΔInventory, ΔVAT/WHT) → **operating**; classifies **non-current-asset changes** → **investing**;
   **equity / borrowing changes** → **financing** (BR-REP-07); the **investing / financing sections are
   sparse** (no Fixed Assets / Loans — §1).
3. The **net change in cash == the movement on the Cash + Bank GL accounts (1000 + 1100)** between the two
   dates (BR-REP-04, the tie-out): the statement opens with the opening cash + bank balance and closes with
   the closing balance, and the three sections sum to the change. A **comparative** prior period is shown
   (BR-REP-01). The accountant exports it (FR-REP-05).

### 7.4 Main unhappy paths
- **Balance Sheet does not balance** (7.2.3) → **surfaced as a data-integrity alarm** (ASSET ≠ LIABILITY +
  EQUITY — BR-REP-02). Because Reporting is **read-only**, it does **not** silently adjust a figure to make it
  balance; it reports the imbalance for finance to investigate (the cause is a books / read-query defect, not
  a Reporting fix — BR-REP-08). *(In a correct double-entry system this cannot happen; if it does, the books
  or the equity-fold logic is wrong.)*
- **Cash-Flow net change ≠ Cash + Bank GL movement** (7.3.3) → **surfaced** (BR-REP-04); the indirect
  reconciliation failed — a working-capital classification or the equity-fold is wrong. Investigated, never
  "balanced" by Reporting (read-only).
- **P&L net ≠ the period's INCOME − EXPENSE movement** (7.1.4) → **surfaced** (BR-REP-03); the grouping
  dropped or double-counted an account. Investigated.
- **No postings in the selected window** (any) → the statement renders **with zero / empty sections** (a
  valid empty result), **not** an error; the comparative likewise (FR-REP-06). A new company with an
  unposted period shows a balanced (zero) Balance Sheet.
- **A new account type / code outside the seeded bands** (any) → it is classified by its **`account_type`**
  (the authority — BR-REP-07, gl.md BR-GL-12) and, where the code falls outside a named band, by the **default
  band for its type** (e.g. an ASSET with no current/non-current band defaults to current — OQ-REP-01); never
  dropped from the statement.
- **A user without the statement permission** (any) → the read is **refused** by RBAC (`REPORT.*`); a user
  with `REPORT.LEDGER.VIEW` but not `REPORT.PL.VIEW` may drill a ledger but not open the company P&L
  (FR-REP-08, OQ-REP-04).
- **A cross-company read attempt** (any) → **refused** (`assertCanActIn`); no figure crosses company scope
  (BR-REP-10, NFR-REP-01).
- **Export of a large statement / a deep ledger** (any) → returns the export within the performance envelope
  (NFR-REP-02); a very large account ledger paginates / streams (it does not time out or load unbounded).

## 8. Non-functional

- **NFR-REP-01 — Tenant isolation & read-correctness.** Every Reporting read is scoped by `company_id`
  through the tenant-predicate repository base (ARCHITECTURE.md §5, PROJECT-CONVENTIONS §3.2);
  `assertCanActIn` guards **every read path** (FR-REP-08). Cross-company reporting leakage is a **release
  blocker**, as for GL/AR/AP/Cash/VAT. The reconciliation bars (BR-REP-02/03/04) are correctness invariants:
  a statement that breaks one is a **finance-grade defect** surfaced, never silently corrected (Reporting is
  read-only, BR-REP-08).
- **NFR-REP-02 — Performance over many journal lines.** The statement queries **aggregate `journal_lines`**
  (potentially many tens of thousands of rows for an active company-year); they must run within an
  interactive envelope (a statement renders in **seconds, not minutes**) and a **large account-ledger
  drill-down** must **paginate / stream** rather than load unbounded. The aggregation reuses / extends the
  existing `TrialBalanceQuery` rather than re-summing per request where a period sum suffices; the indexing /
  query plan is the architect's (ADR-0018), but the **NFR is interactive performance on a realistic
  company-year of postings**.
- **NFR-REP-03 — Read-only / no write path.** Reporting **posts nothing, writes no business table, allocates
  no number** (BR-REP-08). An ArchUnit test asserts the reporting module has **no** posting / repository-write
  path and depends only on GL's **read** surface (`reporting → gl.read`), never on a GL posting entity or a
  sub-ledger entity (gl.md NFR-GL-07; flagged for ADR-0018). A write path in `com.erp.modules.reporting` is a
  defect.
- **NFR-REP-04 — Print-faithful PDF + spreadsheet export.** The PDF export is a **faithful, printable**
  rendering of the on-screen statement (same figures, sections, the comparative column, a company + period
  header, base currency) — laid out for print (page size, headers, totals aligned). The Excel/CSV export
  carries the same figures in a spreadsheet-usable form (rows/columns, the comparative). A divergence between
  the on-screen statement and its export is a defect (FR-REP-05).
- **NFR-REP-05 — Access logging (no mutation to audit).** Reporting performs **no mutation**, so there is no
  post / close / adjust to write to the IAM append-only audit trail; **read access** to sensitive financial
  statements is logged per the platform read-access policy (the same surface that gates `REPORT.*`). (Unlike
  GL/AR/AP/Cash/VAT, Reporting has **no NFR for mutation audit** — there is nothing it mutates.)
- **NFR-REP-06 — Money correctness & base currency.** Every figure is a `Money` (amount + currency, ADR-0005)
  in the company base currency; sums use `BigDecimal` value comparison (no float), rounding per ADR-0005 D-2 /
  gl.md NFR-GL-02 (OQ-CUR-03 — half-up, TZS = 0 dp). The Balance-Sheet balance check, the P&L-to-ledger
  reconciliation, and the cash-flow tie-out compare **exactly** (a rounding discrepancy that breaks a bar is a
  defect, not a tolerance — the same standard as gl.md NFR-GL-02).
- **NFR-REP-07 — Forward-compatibility.** The v1 model (read-only, auto-derived mapping) must **not preclude**
  the later increments that build on Reporting: **configurable statement templates** (OQ-REP-06);
  **segment / cost-centre / dimension reporting** (T3.5/T3.6, OQ-REP-07); **consolidated multi-company**
  reporting (gl.md OQ-CUR-01); **budget-vs-actual** (T3.6, the comparative-column mechanism is the foundation);
  **ratio analysis / KPI dashboards / analytics** (T2.3 depth, OQ-REP-08); the **Statement of Changes in
  Equity** (the equity-fold, BR-REP-05, is its foundation); **multi-currency / presentation-currency**
  statements (X.6 / gl.md §10.5, OQ-REP-09); **scheduled / emailed reports** (X.2, OQ-REP-10); and the
  **cash-flow direct method**. Building these is deferred; precluding them is a defect.
- **NFR-REP-08 — Timestamps & business dates.** Timestamps are UTC, displayed per company time zone
  (Africa/Dar_es_Salaam default, iam.md locale). The reporting **window** (the selected date range / as-at
  date) and the **comparative** window are **business dates** (driving which `posting_date` journal lines are
  included), distinct from the request timestamp.

## 9. Assumptions

- The dependency platform exists and is consumed as designed: **GL** (ADR-0013 / V10) is shipped with
  `chart_of_accounts` (`account_type` ∈ {ASSET, LIABILITY, EQUITY, INCOME, EXPENSE} + `normal_balance`),
  `journal_entries` / `journal_lines` (`posting_date`, debit/credit), `fiscal_periods` + `FiscalPeriodResolver`,
  and the existing **`TrialBalanceQuery` + `TrialBalanceDto` / `TrialBalanceRowDto`** the statements build on
  (gl.md FR-GL-16); **all financial data already posts to GL** — Sales + VAT (ADR-0013), AR (ADR-0014), AP
  (ADR-0015), Cash & Bank (ADR-0016), the VAT settlement + WHT (ADR-0017 / V14); **Money** (ADR-0005) +
  **RBAC / `ScopeGuard` / audit** are in place. All shipped.
- The **CoA `account_type` is the authority for statement placement** (gl.md BR-GL-12): INCOME/EXPENSE → P&L,
  ASSET/LIABILITY/EQUITY → Balance Sheet; Reporting reads the type, never re-deriving placement from the code
  alone. The **code ranges** drive only the **sub-grouping** (current/non-current; cost-of-sales/operating;
  CF section) — the worked TZ-CoA banding (§3.1) is the default; the exact boundaries are ADR-0018's
  (OQ-REP-01).
- **The books balance because GL is double-entry** (gl.md BR-GL-01) — so the Balance-Sheet balance bar
  (BR-REP-02) and the trial-balance-nets-to-zero property (gl.md FR-GL-16) are guaranteed by the posting
  engine, not re-enforced by Reporting. Reporting's job is to **present and tie back**, not to re-balance.
- **The Cash + Bank GL accounts (1000, 1100) are the cash basis** the cash-flow statement ties to (BR-REP-04);
  every cash movement posted to one of them (sales cash sale, AR receipt, AP payment, transfer, direct entry,
  VAT settlement cash impact — cash-and-bank.md BR-CASH-02). Reporting reads their period movement as the
  net-change-in-cash check.
- **There is no automated year-end close in v1** (gl.md §10.6 / OQ-GL-03), so the **current-year net income**
  is a **presentation derivation** in the Balance Sheet (BR-REP-05), not a posted closing entry. When the
  year-end roll-up lands (GL depth), the closing entry posts the P&L net to retained earnings, and the BS
  presentation aligns naturally — additive, not a rework (NFR-REP-07).
- **There is no Fixed-Assets and no Loans module yet** (ROADMAP T3.3 / Tier-3), so the cash-flow **investing**
  and **financing** sections and the P&L depreciation add-back are **sparse** in v1 (§1) — the structure is
  built and ready; the postings to populate it land with those modules (NFR-REP-07).
- **Document currency = company base (TZS)** in practice for v1 (sales.md §9 / gl.md BR-GL-06); statements are
  base currency (BR-REP-09). The FX shape is deferred (OQ-REP-09).
- **Reporting creates no new business table** — it reads GL. The only migration is a small **V15 perm-seed**
  for the `REPORT.*` permissions + the `ORG_ADMIN` grant (FR-REP-08; flagged for ADR-0018).

## 10. ACCEPTED RISK & accepted scope boundary — what Financial Reporting v1 deliberately does NOT do (owner-accepted 2026-06-10)

> **Read this before building or consuming Reporting.** Reporting v1 delivers the **three primary statements**
> (P&L, Balance Sheet, Cash-Flow indirect), the **GL account-ledger drill-down**, **comparative columns**, and
> **PDF / Excel-CSV export** — all **read-only over GL**, with the **auto-derived** `account_type` + code-range
> mapping (no template table). The following are **deliberate boundaries**, owner-accepted.

1. **Auto-derived mapping, NOT a configurable statement-template table.** The statement → account mapping is
   **derived** from `account_type` + code range (BR-REP-07); a user-configurable statement-template /
   report-builder is **deferred** (OQ-REP-06). The exact code-range boundaries are the **ADR's** to fix
   (OQ-REP-01) — a design detail, not a v1 gap.

2. **Cash-flow investing / financing are structurally present but SPARSE.** No Fixed Assets, no Loans yet
   (Tier-3) → the investing / financing sections and the P&L depreciation add-back have **few or no
   postings** in v1. This is **expected, not a defect**: the structure is correct and ready, and the
   **net-change-in-cash == Cash + Bank movement bar holds regardless** (BR-REP-04). Those sections populate
   additively as Fixed Assets / Loans land (NFR-REP-07).

3. **Current-year net income is a PRESENTATION derivation, not a posted closing entry.** v1 folds the
   year-to-date INCOME − EXPENSE into Balance-Sheet equity for presentation (BR-REP-05); the **automated
   year-end close** (posting the P&L net to retained earnings + opening carry-forward) is **GL depth** (gl.md
   §10.6 / OQ-GL-03), not Reporting. The presentation aligns with the posted close when that lands.

4. **Company-level only — no segment / cost-centre / consolidated reporting.** v1 reports **per company** at
   company level (BR-REP-10). Per-cost-centre / per-segment / per-branch P&L (the dimension framework, T3.5/
   T3.6) and **consolidated multi-company** group reporting (gl.md OQ-CUR-01) are **deferred** (OQ-REP-07) —
   not precluded (NFR-REP-07).

5. **Actual + comparative only — no budget-vs-actual.** v1 shows **actual vs a prior comparative** (BR-REP-01).
   **Budget-vs-actual** waits on the **Budgeting module** (T3.6) — the comparative-column mechanism is built so
   a budget column is additive.

6. **The three primary statements only — no Statement of Changes in Equity, no ratio/KPI dashboards in this
   slice.** v1 ships P&L + BS + Cash-Flow + the ledger drill-down. The **Statement of Changes in Equity**, the
   **ratio analysis / KPI dashboards**, and the **sales / stock / purchase analytics** are **later Reporting
   slices** (OQ-REP-08, PATH-TO-FULL-ERP §6 / Phase D) — this slice is the **financial-statements** core.

7. **On-demand only — no scheduled / emailed reports; base currency only.** v1 statements are **run + view +
   export on demand**; **scheduling / emailing** a statement waits on **Notifications** (X.2, OQ-REP-10). All
   figures are **base currency (TZS)**; **multi-currency / presentation-currency** translation waits on FX
   (X.6 / gl.md §10.5, OQ-REP-09).

All are additive by design (NFR-REP-07); none is precluded by the v1 model.

## 11. Open questions — status after ratification (2026-06-10)

> The **Reporting scoping forks** the owner answered (auto-derive the mapping — no template table; the three
> primary statements + the ledger drill-down; comparative columns; PDF + Excel/CSV export; arbitrary
> date-range / as-at + fiscal quick-selects; base currency; read-only with the reconciliation bars; the
> permission split) are **RESOLVED** (recorded in `docs/requirements/open-questions.md` under Reporting).
> **No ADR-0018-blocking open question remains.** What stays open is **non-blocking** detail with a
> recommended default that stands unless the owner overrides — the three architecturally meaty items (the
> code-range banding boundaries, the CF treatment of the VAT/WHT control accounts, and the export-library
> choice) are **decisions ADR-0018 makes**, not requirements blockers (the *behaviour* — the statements'
> content and the reconciliation bars — is fixed).

### The ADR-0018 design seam (decisions the architect makes — do NOT block the requirements)

- **OQ-REP-01 — The code-range banding boundaries (current/non-current; cost-of-sales/operating; CF section).**
  The auto-derive principle is fixed (BR-REP-07): `account_type` → statement + top section; code range →
  sub-grouping. The **exact boundaries** are the ADR's. *Recommended default* (the worked TZ-CoA banding, §3.1):
  current assets 1000–1499 / non-current 1500–1999; current liabilities 2000–2499 / non-current 2500–2999;
  cost-of-sales 5100–5199 / operating 5200–5999; CF operating = current operating accounts (AR/AP/Inventory/
  VAT/WHT), investing = non-current ASSET, financing = EQUITY + non-current LIABILITY; an account outside a
  named band defaults to the **type's default band** (e.g. ASSET → current). *Decider:* **architect
  (ADR-0018)**, with finance review. *Blocks ADR-0018:* **NO** — it **is** the banding the ADR fixes; the
  principle + default stand.

### Still open — NON-blocking detail (recommended defaults stand; do NOT block ADR-0018)

- **OQ-REP-02 — Cash-flow treatment of the VAT / WHT control accounts.** Where do 1400 VAT Input, 1500 WHT
  Receivable, 2200 VAT Payable, 2300 VAT Due, 2400 WHT Payable sit on the indirect cash-flow statement?
  *Recommended default:* treat them as **working-capital items in the operating section** (a change in VAT/WHT
  owed-or-recoverable is an operating cash effect — the standard treatment for indirect cash flow); not a
  separate "tax cash flow" section in v1. *Decider:* owner (finance). *Blocks ADR-0018:* **NO** — the
  operating-working-capital default stands; the bar (BR-REP-04) holds either way.
- **OQ-REP-03 — Comparative default window.** Is the default comparative the **prior period** (the
  immediately preceding range of the same length) or the **prior fiscal year** (same period last year)?
  *Recommended default:* **prior period of the same length** for P&L / Cash-Flow (e.g. this month vs last
  month) and the **prior year-end (or the period start)** for the Balance Sheet; the reader may **override**
  to prior-year (same period last year) where wanted (FR-REP-06). *Decider:* owner. *Blocks ADR-0018:* **NO**
  — the prior-period default stands; prior-year is a selectable override (the same comparative machinery).
- **OQ-REP-04 — Permission granularity (single `REPORT.VIEW` vs the finer split).** *Recommended default:*
  seed the **finer split** — `REPORT.VIEW` (a coarse grant covering all read) **plus** `REPORT.PL.VIEW` /
  `REPORT.BS.VIEW` / `REPORT.CASHFLOW.VIEW` / `REPORT.LEDGER.VIEW` / `REPORT.EXPORT` — so an owner *can*
  restrict who sees the company P&L vs who may drill a ledger (financial statements can be sensitive); a
  deployment that wants it simple grants `REPORT.VIEW` to all finance roles. *Decider:* owner. *Blocks
  ADR-0018:* **NO** — the perm set is seeded in V15; the granularity is the owner's policy, the codes are
  fixed.
- **OQ-REP-05 — Export library / generation approach (PDF + Excel/CSV).** *Recommended default:* a
  server-side **PDF** renderer producing a print-faithful statement (the same renderer the cross-cutting
  Documents/PDF capability X.1 will standardise — Reporting may ship a lean PDF first and align with X.1 when
  it lands) and a server-side **Excel/CSV** writer; the exact library (e.g. a PDF templating library, a
  spreadsheet library) is the architect's choice. *Decider:* **architect (ADR-0018)**, aligning with X.1.
  *Blocks ADR-0018:* **NO** — the requirement is a print-faithful PDF + a spreadsheet export (FR-REP-05,
  NFR-REP-04); the library is the design decision.
- **OQ-REP-06 — Configurable statement templates.** v1 **auto-derives** the mapping (BR-REP-07).
  *Recommended default:* auto-derive in v1; a user-configurable statement-template / report-builder is a later
  additive slice. *Decider:* owner. *Blocks ADR-0018:* **NO** — auto-derive is fixed; templates are additive.
- **OQ-REP-07 — Segment / cost-centre / branch / consolidated reporting.** v1 is **company-level** (BR-REP-10).
  *Recommended default:* company-level statements in v1; per-cost-centre / per-segment / per-branch reporting
  lands with the **dimension framework** (T3.5/T3.6), and **consolidated multi-company** with group reporting
  (gl.md OQ-CUR-01). *Decider:* owner. *Blocks ADR-0018:* **NO** — deferred, not precluded (NFR-REP-07).
- **OQ-REP-08 — Ratio analysis / KPI dashboards / operational analytics.** v1 = the three statements + the
  ledger. *Recommended default:* statements first; ratios, dashboards, and sales/stock/purchase analytics are
  the **Reporting depth** slice (T2.3 depth / Phase D). *Decider:* owner. *Blocks ADR-0018:* **NO** — a later
  slice.
- **OQ-REP-09 — Multi-currency / presentation-currency statements.** v1 is **base currency (TZS)** (BR-REP-09).
  *Recommended default:* base-currency statements in v1; a foreign / presentation currency translation lands
  with FX (X.6 / gl.md §10.5). *Decider:* owner. *Blocks ADR-0018:* **NO** — deferred, not precluded.
- **OQ-REP-10 — Scheduled / emailed reports.** v1 is **on-demand**. *Recommended default:* on-demand run +
  view + export in v1; scheduling / emailing a statement lands with **Notifications** (X.2). *Decider:* owner.
  *Blocks ADR-0018:* **NO** — additive on the on-demand model.
- **OQ-CUR-03 (carried) — Rounding mode & TZS decimals.** Confirm rounding mode (half-up vs banker's) and TZS
  decimal places (0 in practice) — the statement aggregations, the Balance-Sheet balance check, the
  P&L-to-ledger reconciliation, and the cash-flow tie-out must round identically to the GL figures they tie to
  (NFR-REP-06). *Recommended default:* half-up, TZS = 0 dp. *Decider:* owner (finance input). *Blocks
  ADR-0018:* **NO** for the model; **confirm before go-live**.

## 12. Out of scope for v1 (deferred — restated)

Configurable statement templates / report-builder (OQ-REP-06 — v1 auto-derives the mapping); **segment /
cost-centre / dimension reporting** (T3.5/T3.6, OQ-REP-07 — v1 is company-level); **consolidated
multi-company / group-reporting-currency** statements (gl.md OQ-CUR-01); **budget-vs-actual** (T3.6 — v1
shows actual + a prior comparative); **ratio analysis / KPI dashboards / sales-stock-purchase analytics**
(T2.3 depth / Phase D, OQ-REP-08); the **Statement of Changes in Equity** (a later statement — the
equity-fold BR-REP-05 is its foundation); **multi-currency / presentation-currency** translation (X.6 / gl.md
§10.5, OQ-REP-09 — v1 is base currency); **scheduled / emailed reports** (X.2, OQ-REP-10 — v1 is on-demand);
the **cash-flow direct method** (v1 is indirect); and the **automated year-end close** (P&L → retained
earnings closing entry + opening carry-forward — GL depth, gl.md §10.6 / OQ-GL-03; v1 *presents* current-year
net income in equity without posting a closing entry). Each is tracked for a later increment; none is
precluded by the v1 model (NFR-REP-07).
