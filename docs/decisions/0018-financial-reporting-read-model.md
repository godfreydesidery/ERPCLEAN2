# 0018 — Financial Reporting read-model: the three primary statements (P&L / Balance Sheet / Cash-Flow indirect) + the GL account-ledger drill-down, auto-derived from `account_type` + code-range over `journal_lines`, comparative, server-side PDF + XLSX export — a pure read-only query+presentation layer over GL that posts nothing, owns no business table, and structurally asserts the three reconciliation bars

- **Status:** Accepted
- **Date:** 2026-06-10
- **Deciders:** solutions-architect (owner-ratified Reporting requirements 2026-06-10 — all Reporting scoping forks resolved; no ADR-0018-blocking open question remains, reporting.md §11)
- **Context source:** [docs/requirements/reporting.md](../requirements/reporting.md) (RATIFIED 2026-06-10 — FR-REP-01..08, BR-REP-01..10, NFR-REP-01..08, US-REP-01..07, §3 derivation + reconciliation bars, §7 flows, §10 accepted boundary, §11 OQ log; the ground truth for every rule below). [ADR-0013](0013-general-ledger-data-model.md) + the **shipped** `com.erp.modules.gl`: **`TrialBalanceQuery`** (`compute(companyId)` / `computeForPeriod(companyId, periodId)` — `journal_lines GROUP BY account_id`, `assertCanActIn` first, enriches via `ChartOfAccountRepository`, builds `TrialBalanceDto`), **`TrialBalanceDto`** (`companyId, List<TrialBalanceRowDto>, totalDebits, totalCredits`) + **`TrialBalanceRowDto`** (`accountId, accountUid, accountCode, accountName, accountType, normalBalance, totalDebit, totalCredit, net`), **`JournalLineRepository`** (verified queries: `trialBalanceSums`, `trialBalanceSumsByPeriod` joining `JournalEntry e ON e.id = l.entryId WHERE e.fiscalPeriodId = :periodId`, `grandTotals`, **`accountBalance(companyId, accountId)`** = `SUM(debit) − SUM(credit)`, `findByEntryIdOrderByLineNo`), **`FiscalPeriodResolver.resolveOpen`** + **`FiscalPeriodRepository`** (`findOpenPeriodForDate`, `findByCompanyIdOrderByStartDateAsc`, `findByCompanyIdAndUid`, `findByFiscalYearIdOrderByPeriodNo`), the entities `JournalEntry` (verified fields **`postingDate` LocalDate, `fiscalPeriodId`, `companyId`, `sourceType`, `sourceRef`**), `JournalLine` (**`accountId`, `debitAmount`, `creditAmount`, `entryId`, `companyId`**), `ChartOfAccount` (**`accountCode` VARCHAR(20), `accountType` ∈ {ASSET,LIABILITY,EQUITY,INCOME,EXPENSE}, `normalBalance` ∈ {DEBIT,CREDIT}, `isActive`**). [ADR-0016](0016-cash-and-bank-data-model.md) + the **shipped** `com.erp.modules.cashbank`: **`cash_bank_accounts.gl_account_id`** (FK → `chart_of_accounts(id)`, **one-to-one per company**, NOT NULL) is the authoritative "cash & cash-equivalent" GL-account set the CF tie-out reads; **`CashBankAccountRepository.findByCompanyId`** returns those accounts; the shipped **`CashGlReconciliationQuery`** is the exact precedent (a `cashbank` read that reaches into `gl.repository.JournalLineRepository.accountBalance` + `gl.repository.ChartOfAccountRepository` — the leaf-reader-into-gl.read pattern Reporting mirrors). [ADR-0005](0005-money-and-currency.md) (`Money` NUMERIC(19,4)+currency; statements are **base currency only** — BR-REP-09; `BigDecimal` value compare, no float — NFR-REP-06). [PROJECT-CONVENTIONS.md](../../PROJECT-CONVENTIONS.md) §2 (module layout + `ModuleBoundaryTest`), §3.2 (tenant predicate), §3.3 (uid/id — N/A here: no addressed reporting entity), §3.6 (append-only — Reporting writes nothing). [[db-naming-convention]] (perms table shape verified against V14: `permissions(code, module, description)`, `roles(code)`, `role_permission(role_id, permission_id)`, `ON CONFLICT` upserts). **The shipped `ModuleBoundaryTest`** (controllers→repository forbidden; services→controllers forbidden; module domain/service/repository not imported across modules except `domain.dto`/`domain.enums` — Reporting reads `gl`/`cashbank` `repository`+`entity` exactly as the shipped `CashGlReconciliationQuery` already reads `gl`, so the cross-module-read allowance is the documented stance, D-12). **Latest shipped migration is `V14__vat_return.sql`** (lexical `ls` shows `V9` last — IGNORE; real latest is V14) **→ Reporting is `V15__reporting_permissions.sql` (PERMS ONLY — no new business tables).** Finding **#12** (migration seed-uid overflow on keep-data deploys): V15 seeds **no `uid`-bearing rows** (perms have no `uid`), so the overflow trap does not apply — but the discipline is noted (D-10). Next ADR is 0019. Backend is **Spring Boot 3.3.5 / Java 17 / Jakarta**; **no PDF or spreadsheet library is on the classpath today** — Reporting introduces them (D-9).

This ADR is the **technical read-model + integration + export design** for the Financial Reporting module (ROADMAP T2.3 / PATH-TO-FULL-ERP Phase A — the first Reporting slice). It translates the ratified business spec into: the module placement + internal layout; the GL aggregation design (reusing `TrialBalanceQuery` + new period-windowed projections, aggregated **in SQL**); the **exact** auto-derive `account_type` + code-range → statement-section mapping (worked against the shipped TZ CoA); the current-year-earnings-into-equity computation; the indirect cash-flow construction + the cash tie-out self-check; the comparative; the server-side PDF + XLSX export approach with dependency coordinates, endpoints, and a statement-agnostic renderer; the permission set + the `V15` perm-seed; read-only/scope/`assertCanActIn`; performance/indexes; the DTO shapes; and the `reporting → gl.read` / `reporting → cashbank.read` ArchUnit boundary. It is **concrete enough that the backend engineer writes the `com.erp.modules.reporting` module + the queries + the export renderers + `V15__reporting_permissions.sql` without guessing a business rule.** It writes **no production code, no entities, no migration SQL** — that is the engineer's next step. **Nothing ratified is re-litigated.**

## Context

The books exist, balance, and already read a trial balance (`TrialBalanceQuery`, gl.md FR-GL-16). Reporting turns the balanced ledger into the statements a business reads — and it does so as a **pure read model**: no entity, no repository that owns a table, no posting, no `code_sequence` number (BR-REP-08, NFR-REP-03). The central architectural force is therefore **be a disciplined leaf reader over GL's read surface — reuse `TrialBalanceQuery` and add the few period-windowed aggregations the statements need (in SQL, never re-summing raw lines in Java), classify accounts by `account_type` + code range in a single derivation object, and make the three reconciliation bars structural self-checks the service computes from the same aggregates it presents.** The genuinely new questions, and the forces around each:

- **A statement is not a trial balance — it is the TB grouped into sections, but the section assignment is a derivation, not a stored template (BR-REP-07).** The owner ratified **auto-derive** (no template table). The new question is *where* the derivation lives (a single `StatementClassifier` keyed on `account_type` + `account_code`), *exactly* which code bands map to which section, and how an out-of-band account is handled (default to the type's primary section — never dropped). Resolved in D-4/D-5.

- **A mid-year Balance Sheet only balances if the current-year net income is folded into equity (BR-REP-05).** There is no year-end close in v1 (gl.md §10.6) — INCOME/EXPENSE accounts are not zeroed — so the posted 3xxx balances alone are short by exactly the year's net income. The new question is the *precise* computation: the year-to-date INCOME − EXPENSE movement, from the FY-start of the fiscal year containing the as-at date through the as-at date, presented as one equity line. Resolved in D-6.

- **The cash-flow net change must equal the Cash + Bank GL movement (BR-REP-04) — and "Cash + Bank" is not two hard-coded codes, it is `cash_bank_accounts.gl_account_id` (the shipped one-to-one set).** The new questions: how each section is computed from the *change* in account balances between two dates; the sign convention; which control accounts count as working capital (OQ-REP-02); and the tie-out self-check the service asserts. Resolved in D-7.

- **Period vs as-at, and the comparative window.** P&L/CF are *flows* (a date range → movement); BS is a *stock* (as-at → cumulative balance). Each carries a comparative window. The new question is the default derivation (prior period of equal length / prior as-at) and that it is a request parameter. Resolved in D-8.

- **Export is the real dependency decision (OQ-REP-05) — no PDF/spreadsheet library is on the classpath.** The new questions: server-side vs client-side; which PDF library (licensing matters — iText 5 is AGPL, iText 7 commercial); XLSX (Apache POI) vs CSV-only in v1; and how a statement DTO renders to each format. Resolved in D-9.

- **Reporting reads `gl` and `cashbank` internals (repositories + entities), which the naive reading of `ModuleBoundaryTest` forbids — but the shipped `CashGlReconciliationQuery` already does exactly this.** The new question is documenting the intended **read-only cross-module allow-set** (`reporting → gl`, `reporting → cashbank`) and the ArchUnit assertion that Reporting has **no write path** and no module depends back on `reporting` (no cycle). Resolved in D-12.

- **Schema freeze / migration ordering.** IAM=V1 … VAT=V14 — all frozen and shipped. Reporting adds **no business table**; its only migration is the **additive `V15__reporting_permissions.sql`** (perms + `ORG_ADMIN` grant). It must not edit V1–V14.

## Decision

### D-1 — Module placement: one `com.erp.modules.reporting` module; controllers flat in `com.erp.api`

Reporting lives under **`com.erp.modules.reporting`** — a flat sibling of `gl`/`ar`/`ap`/`cashbank`/`tax` (PROJECT-CONVENTIONS §2; the same reasoning ADR-0013 D-1 / ADR-0016 D-1 used to reject a nested umbrella). It is the first **read-only** module: it has **no `domain.entity`, no repository that owns a table** — it reads GL's and Cash & Bank's read surfaces. Internal layout:

```
com.erp.modules.reporting
├── domain.dto       IncomeStatementDto, BalanceSheetDto, CashFlowStatementDto, AccountLedgerDto
│                    + section/line records:
│                      StatementHeaderDto (companyName, currency, periodLabel, comparativeLabel,
│                                          fromDate?, toDate?, asAtDate?, generatedAt)
│                      AmountPairDto (current, comparative)                 — every figure is a pair
│                      StatementLineDto (accountId, accountUid, accountCode, accountName, AmountPairDto)
│                      StatementSectionDto (sectionKey, title, List<StatementLineDto>, AmountPairDto subtotal)
│                      ReconciliationDto (label, computed AmountPairDto, expected AmountPairDto,
│                                         difference AmountPairDto, boolean ties)   — the bar self-check
│                      AccountLedgerRowDto (postingDate, sourceType, sourceRef, entryUid, lineMemo,
│                                           debit, credit, runningBalance)
│                    (NO *Request bodies that mutate — every input is a query param / a ReportQuery)
├── domain.enums     StatementType (INCOME_STATEMENT|BALANCE_SHEET|CASH_FLOW|ACCOUNT_LEDGER),
│                    StatementSection (P&L: REVENUE|COST_OF_SALES|OPERATING_EXPENSES;
│                                      BS:  CURRENT_ASSETS|NON_CURRENT_ASSETS|
│                                           CURRENT_LIABILITIES|NON_CURRENT_LIABILITIES|EQUITY;
│                                      CF:  OPERATING|INVESTING|FINANCING),
│                    ExportFormat (PDF|XLSX|CSV)
├── service          ReportingService(+Impl)            — orchestrates the four statements + the ledger,
│                                                          assertCanActIn first on every method (D-11),
│                    StatementClassifier                — account_type + account_code → StatementSection
│                                                          (the auto-derive rule — pure function, D-4/D-5),
│                    AccountMovementQuery               — period-windowed + as-at SUM aggregations in SQL,
│                                                          reuses/extends gl.JournalLineRepository (D-3),
│                    CashEquivalentAccountResolver      — reads cashbank.gl_account_id set for CF (D-7),
│                    IncomeStatementBuilder             — assembles the P&L from movements (D-5),
│                    BalanceSheetBuilder                — assembles the BS + equity fold (D-6),
│                    CashFlowStatementBuilder           — indirect CF + tie-out self-check (D-7),
│                    AccountLedgerQuery                 — running-balance ledger for one account (D-3),
│                    ComparativeWindowResolver          — default comparative derivation (D-8)
├── export           ReportExporter                     — facade: (statementDto, format) → byte[] + mime,
│                    PdfStatementRenderer               — OpenPDF; statement-agnostic table layout (D-9),
│                    XlsxStatementRenderer              — Apache POI; one sheet per statement (D-9),
│                    CsvStatementRenderer               — plain CSV fallback (D-9),
│                    StatementRenderModel               — the flat row model both renderers consume (D-9)
└── (no events package — Reporting has no poster, no outbox consumer, no scheduled run; every read is
    an in-request, on-demand human read — reporting.md §4)
```

Controllers stay flat in `com.erp.api` — **per statement** (clearer `@PreAuthorize` per perm than one fat controller): `IncomeStatementController`, `BalanceSheetController`, `CashFlowController`, `AccountLedgerController`, and `ReportExportController` (or each statement controller exposes its own `/export` sub-path — see D-9). Each touches only `ReportingService` (`ModuleBoundaryTest`). Like the shipped `TrialBalanceController`, the read endpoints **return the DTO directly** (the trial-balance precedent — these are query reads, not the mutating `ApiResponse<T>`-enveloped command endpoints); the export endpoints return a raw `byte[]` body with explicit `Content-Type`/`Content-Disposition` (D-9). There is **no new `ScopeGuard` target type** — reports are computed per `companyId` (a request param, validated by `assertCanActIn`), and the only uid the ledger addresses is a `chart_of_accounts.uid`, already `case "account"` in the shipped `ScopeGuard`.

### D-2 — Read-only over GL: reuse `TrialBalanceQuery`, add period-windowed + as-at aggregations; aggregate in SQL, never in Java

Reporting **posts nothing, owns no table, allocates no number** (BR-REP-08, NFR-REP-03). It reads three GL surfaces and one Cash & Bank surface:

1. **`TrialBalanceQuery`** (shipped) — reused directly for the *all-time* / *single-period* TB where a built-in period sum suffices (e.g. a quick whole-company TB behind the BS). It is the proven `GROUP BY account_id` + `assertCanActIn` + account-enrichment path; Reporting does **not** re-implement it.
2. **`JournalLineRepository`** (shipped) — `accountBalance(companyId, accountId)` (used by the CF tie-out, exactly as `CashGlReconciliationQuery` uses it) and `findByEntryIdOrderByLineNo` (the ledger drill).
3. **New aggregation queries** (D-3) — period-windowed and as-at SUMs grouped by account / account_type, added to a **new `reporting`-owned read query object** (`AccountMovementQuery`) that calls **GL repositories** (the documented cross-module read, D-12). These are the only genuinely new queries; everything else reuses shipped GL reads.
4. **`CashBankAccountRepository.findByCompanyId`** (shipped) — the `gl_account_id` set for the CF cash basis (D-7).

**Aggregate in SQL, not in Java (NFR-REP-02, the firm rule).** Every statement figure is a `SUM(debit_amount)`/`SUM(credit_amount)` `GROUP BY` over `journal_lines` (optionally joined to `journal_entries` for the `posting_date` window or to `chart_of_accounts` for `account_type`). The service **never** loads raw `journal_lines` and sums them in Java (the one exception is the account-ledger drill, which is inherently row-by-row for the running balance — and that paginates, D-3). This keeps a realistic company-year (tens of thousands of lines) inside the interactive envelope.

### D-3 — The new aggregation projections (the only new queries; `reporting`-owned, calling GL repositories)

`AccountMovementQuery` (a `reporting.service` `@Component`, `@Transactional(readOnly=true)`, `assertCanActIn` first) issues these reads. They extend the *exact* shape of the shipped `trialBalanceSums` / `trialBalanceSumsByPeriod`, adding a **date window** (filtering `journal_entries.posting_date`, which hits the shipped `ix_journal_entries_company_date`) and an optional `account_type` grouping. Engineer note: these may be added as `@Query` methods on a small `reporting` repository interface that extends `Repository<JournalLine, Long>` (read-only, no save/delete exposed), **or** as JPQL in the query object — either way they are reads only. Field names verified against the shipped entities (`JournalEntry.postingDate`, `JournalLine.accountId/debitAmount/creditAmount/entryId/companyId`, `ChartOfAccount.accountType`).

**(a) Period movement by account — the P&L and the CF working-capital deltas (a *flow* between two dates):**
```jpql
SELECT l.accountId, SUM(l.debitAmount) AS d, SUM(l.creditAmount) AS c
FROM   JournalLine l
JOIN   JournalEntry e ON e.id = l.entryId
WHERE  l.companyId = :companyId
  AND  e.postingDate BETWEEN :fromDate AND :toDate
GROUP  BY l.accountId
```
Returns `[accountId, sumDebit, sumCredit]`; the builder nets per the account's `normalBalance` (D-5). For the P&L this is run over `[fromDate, toDate]`; for the CF working-capital change it is run as the **as-at delta** = (as-at `toDate` cumulative) − (as-at `fromDate−1` cumulative) per account (see (b)).

**(b) Cumulative balance as-at a date by account — the Balance Sheet and the CF opening/closing balances (a *stock*):**
```jpql
SELECT l.accountId, SUM(l.debitAmount) AS d, SUM(l.creditAmount) AS c
FROM   JournalLine l
JOIN   JournalEntry e ON e.id = l.entryId
WHERE  l.companyId = :companyId
  AND  e.postingDate <= :asAtDate
GROUP  BY l.accountId
```
Returns the cumulative `[accountId, sumDebit, sumCredit]` from inception through `:asAtDate`. The BS uses this directly; the CF computes a per-account *change* as `balanceAsAt(toDate) − balanceAsAt(fromDate − 1 day)` (the period delta) using two calls or a single windowed call — the builder is explicit about the boundary (the period includes both endpoints; the comparative-prior balance is as-at `fromDate − 1`).

**(c) Movement by `account_type` over a window — the P&L net / equity-fold reconciliation (one row per type, the cheapest aggregate):**
```jpql
SELECT a.accountType, SUM(l.debitAmount) AS d, SUM(l.creditAmount) AS c
FROM   JournalLine l
JOIN   JournalEntry e ON e.id = l.entryId
JOIN   ChartOfAccount a ON a.id = l.accountId
WHERE  l.companyId = :companyId
  AND  e.postingDate BETWEEN :fromDate AND :toDate
GROUP  BY a.accountType
```
This single query yields the period INCOME and EXPENSE totals → the P&L net (BR-REP-03 self-check) and the equity-fold input (BR-REP-05), without re-walking lines in Java.

**(d) The account-ledger drill (the one inherently row-by-row read — paginated):** opening balance as-at `fromDate − 1` (query (b) for one account = `accountBalance` windowed, or `JournalLineRepository.accountBalance` for all-time), then the period's lines in posting-date order with a running balance computed as the rows stream. The line read joins `journal_lines` → `journal_entries` for `(posting_date, source_type, source_ref, entry.uid)` for one `account_id`, ordered by `posting_date, entry_no, line_no`, **paginated** (`Pageable`) so a deep ledger streams rather than loading unbounded (NFR-REP-02, §7.4). The running balance for a page continues from the prior page's closing (opening + Σ prior pages).

### D-4 — The auto-derive mapping: `StatementClassifier`, a pure function of `account_type` + `account_code` (no template table)

`StatementClassifier` is a stateless `reporting.service` component (a pure function — no DB, no state) that maps a `(AccountType, accountCode)` to a `StatementSection`. This **is** the auto-derive rule (BR-REP-07); there is **no `statement_templates` table**. `account_type` is the authority for the statement + top section (gl.md BR-GL-12); the **numeric code band** (parsed as an integer from the leading digits of `account_code`) drives the sub-grouping. The code is a `VARCHAR(20)` string in the CoA — the classifier parses the **leading numeric run** to an `int` (e.g. `"5150"` → 5150); a non-numeric or empty code falls back to the **type's primary section** (the §7.4 "default band for its type" rule).

**The exact bands (OQ-REP-01 default — fixed here, finance-reviewable):**

| `account_type` | code band | `StatementSection` | statement |
| --- | --- | --- | --- |
| ASSET | `1000–1499` | `CURRENT_ASSETS` | Balance Sheet |
| ASSET | `1500–1999` | `NON_CURRENT_ASSETS` | Balance Sheet |
| ASSET | (other / outside) | `CURRENT_ASSETS` (type default) | Balance Sheet |
| LIABILITY | `2000–2499` | `CURRENT_LIABILITIES` | Balance Sheet |
| LIABILITY | `2500–2999` | `NON_CURRENT_LIABILITIES` | Balance Sheet |
| LIABILITY | (other / outside) | `CURRENT_LIABILITIES` (type default) | Balance Sheet |
| EQUITY | any `3xxx` | `EQUITY` | Balance Sheet |
| INCOME | any `4xxx` | `REVENUE` | P&L |
| EXPENSE | `5100–5199` | `COST_OF_SALES` | P&L |
| EXPENSE | `5200–5999` (and any other EXPENSE) | `OPERATING_EXPENSES` | P&L |

> **The 5150 Purchases treatment (decided).** `5150 Purchases` is EXPENSE in the `5100–5199` band → **`COST_OF_SALES`**. So gross profit = Sales (4100) − (COGS 5100 + Purchases 5150). This matches reporting.md §3.1/§3.2 (Purchases is "cost of sales (gross-profit)"). Rationale: for a small-business periodic-inventory shop, purchases *are* the cost of goods bought to sell; folding them into cost of sales gives a meaningful gross margin. When perpetual-COGS posting matures and Purchases becomes a pure inventory-clearing account, moving the band boundary is a one-line classifier change (no migration — the mapping is code, not data) — flagged OQ-REP-01.

> **Worked TZ CoA (the acceptance fixture the engineer asserts in a unit test of `StatementClassifier`):** 1000/1100/1200/1300/1400/1500 → `CURRENT_ASSETS`; (no `1500–1999` non-current yet); 2100/2200/2300/2400 → `CURRENT_LIABILITIES`; 3000/3100/3900 → `EQUITY`; 4100 → `REVENUE`; 5100/5150 → `COST_OF_SALES`; 5200/5300/5400 → `OPERATING_EXPENSES`. Note **3100 Opening Balance Equity** (seeded V11/V12) classifies as `EQUITY` — it carries opening capital postings and belongs in the posted-equity block (D-6).

### D-5 — Income Statement: revenue − cost of sales = gross profit; − operating expenses = net profit; the P&L-to-ledger self-check

The P&L is the **period movement** (D-3(a)) of INCOME and EXPENSE accounts over `[fromDate, toDate]`, classified by `StatementClassifier` (D-4). For each account the builder computes the **period net presented positive** per `normalBalance`: an INCOME account (CREDIT-normal) presents `creditMovement − debitMovement`; an EXPENSE account (DEBIT-normal) presents `debitMovement − creditMovement`. Structure:

1. **REVENUE** = Σ INCOME accounts (4xxx), presented positive.
2. less **COST_OF_SALES** = Σ EXPENSE accounts in `5100–5199` (5100 COGS + 5150 Purchases) → **Gross profit = REVENUE − COST_OF_SALES**.
3. less **OPERATING_EXPENSES** = Σ EXPENSE accounts `5200–5999` (5200/5300/5400) → **Net profit = Gross profit − OPERATING_EXPENSES** (operating profit and net profit coincide in v1 — no finance/tax lines yet).

Each line is a `StatementLineDto` (account + `AmountPairDto{current, comparative}`); each section a `StatementSectionDto` with a subtotal; gross profit and net profit are subtotal lines.

**The self-check (BR-REP-03, a structural assertion):** the builder computes `netProfit` two ways — (i) the assembled `REVENUE − COST_OF_SALES − OPERATING_EXPENSES`, and (ii) the type-level aggregate (D-3(c)) `Σ INCOME net − Σ EXPENSE net`. It asserts (i) `compareTo` (ii) `== 0` (`BigDecimal`, NFR-REP-06). A mismatch means an account was dropped or double-counted by the classifier → surfaced as a data-integrity alarm (§7.4), never silently presented. The check is exposed on the DTO as a `ReconciliationDto` (`label="P&L net == period INCOME − EXPENSE movement"`, `ties=true/false`) so the web + the export show the green bar.

### D-6 — Balance Sheet: ASSET = LIABILITY + EQUITY as-at a date; current-year net income folded into equity (the exact computation)

The BS is the **cumulative balance as-at `asAtDate`** (D-3(b)) of every ASSET/LIABILITY/EQUITY account, classified by `StatementClassifier` (D-4), presented per `normalBalance` (ASSET DEBIT-normal → `debit − credit`; LIABILITY/EQUITY CREDIT-normal → `credit − debit`). Sections: `CURRENT_ASSETS`, `NON_CURRENT_ASSETS`, `CURRENT_LIABILITIES`, `NON_CURRENT_LIABILITIES`, `EQUITY`.

**The equity fold (BR-REP-05) — the precise computation:**

1. Determine the **fiscal-year start** containing `asAtDate`: resolve the `fiscal_periods` row covering `asAtDate` (the shipped `FiscalPeriodRepository.findByCompanyIdOrderByStartDateAsc` / or `findOpenPeriodForDate` without the OPEN filter — a small read added if needed), take its `fiscal_year_id`, and read that year's **earliest period `start_date`** (`findByFiscalYearIdOrderByPeriodNo` → period 1's `start_date`) = `fyStart`. If no fiscal year covers `asAtDate` (a date before any seeded year), `fyStart` defaults to `asAtDate`'s calendar-year start (Jan 1) — a safe fallback.
2. **Fold the FULL net P&L balance as-at the date — inception-to-date, NOT just fiscal-year-to-date.** Because ERPCLEAN2 has **no year-end close yet** (gl.md OQ-GL-03 — deferred), INCOME/EXPENSE accounts are **never zeroed**, so *every* prior fiscal year's net income is still sitting un-closed on the P&L accounts. The equity fold must therefore equal the **entire** `Σ INCOME net − Σ EXPENSE net` for **`posting_date <= asAtDate`** (inception through the as-at date), via the D-3(c) type-aggregate. This is exactly the residual double-entry leaves on the P&L accounts, so folding it makes the BS balance **unconditionally**. (FY-to-date alone would omit prior unclosed years → the BS would NOT balance in year 2+ — a defect, not an acceptable "surfaced drift".)
3. The `EQUITY` section presents each posted 3xxx account balance as-at (3000 Owner's Equity, 3100 Opening Balance Equity, 3900 Retained Earnings) **plus** the folded P&L residual, split for presentation into two synthetic lines: **"Retained earnings — prior years (unclosed)"** = `Σ INCOME − Σ EXPENSE` for `posting_date < fyStart`, and **"Current-year earnings"** = `Σ INCOME − Σ EXPENSE` over `[fyStart, asAtDate]`. The two synthetic lines sum to the inception-to-date residual of step 2; the equity subtotal includes both. (Presentation only — the split is cosmetic; the balance guarantee depends only on the *total* inception-to-date fold.)

**Why this balances (BR-REP-02, the structural guarantee).** As-at any date, GL double-entry gives `Σ all debits == Σ all credits` (gl.md BR-GL-01). Rearranged by type: `(ASSET_dr − ASSET_cr) + (EXPENSE_dr − EXPENSE_cr)` (the debit-normal side) `== (LIAB_cr − LIAB_dr) + (EQUITY_cr − EQUITY_dr) + (INCOME_cr − INCOME_dr)` (the credit-normal side). The BS presents `ASSET_net` on the left and `LIAB_net + EQUITY_posted_net` on the right; the gap is exactly `EXPENSE_net − INCOME_net` (cumulative, all dates) = −(inception-to-date net income). Folding **+inception-to-date net income** (= `INCOME_net − EXPENSE_net` for all `posting_date <= asAtDate`) into equity closes the gap exactly, so **ASSET == LIABILITY + EQUITY** holds for *any* as-at date **and any number of fiscal years**, with or without a year-end close. **Forward-compatibility with year-end close:** when close-automation later lands, it will post a closing entry moving a year's P&L into 3900 and zeroing those P&L accounts for the closed period; the inception-to-date P&L-account balance then only covers *post-close* periods (the closed earnings now sit in the posted 3900 balance), so the same formula keeps balancing with **no double-count** and no change to this builder.

**The self-check (BR-REP-02, structural assertion):** the builder computes `totalAssets` and `totalLiabilities + totalEquity(incl. fold)` and asserts `compareTo == 0` (`BigDecimal`). Exposed as `ReconciliationDto` (`label="ASSET == LIABILITY + EQUITY"`, `ties`). A non-balancing BS renders the alarm (it does **not** plug a balancing figure — Reporting is read-only, §7.4).

### D-7 — Cash-Flow Statement (indirect): construction, sign convention, the cash-equivalent set, the tie-out self-check

The CF is built **indirectly** over `[fromDate, toDate]`. The **cash basis** is the set of GL accounts linked from Cash & Bank: `CashEquivalentAccountResolver.cashAccountIds(companyId)` = `cashBankAccountRepository.findByCompanyId(companyId)` → `map(CashBankAccount::getGlAccountId)` → distinct set (the shipped one-to-one set, ADR-0016 D-1 — generalises "Cash 1000 + Bank 1100" to *every* linked cash/bank GL account, so a company with three tills and two banks ties correctly). This is the same surface `CashGlReconciliationQuery` reads.

**Per-account period change** = `balanceAsAt(toDate) − balanceAsAt(fromDate − 1)` (D-3(b)), signed as the account's **net debit movement** (`Δdebit − Δcredit`) so all accounts share one sign convention.

**Sections (classified by `StatementClassifier` + the working-capital rule):**

- **OPERATING** = `netIncome` (the P&L net over the period, D-5) **+ non-cash add-backs** (none material in v1 — no depreciation) **± working-capital changes.** Working-capital accounts = the **current operating** accounts: AR (1200), Inventory (1300), AP (2100), and the **VAT/WHT control accounts (1400, 1500, 2200, 2300, 2400)** — **treated as working capital in OPERATING (OQ-REP-02 default, decided here)**, *excluding* the cash-equivalent accounts themselves. Sign: a **rise in an operating ASSET** (AR/Inventory/VAT Input/WHT Receivable, debit-normal Δ > 0) **uses** cash → **subtract** the net-debit change; a **rise in an operating LIABILITY** (AP/VAT Payable/VAT Due/WHT Payable, credit-normal, net-debit Δ < 0) **provides** cash → its negative net-debit change **adds**. Uniform rule: each working-capital account contributes **`−(Δdebit − Δcredit)`** to operating cash (a positive net-debit change reduces cash; a positive net-credit change increases it). This single rule handles both asset and liability working-capital accounts correctly by sign.
- **INVESTING** = `−(Δ net-debit)` of **non-current ASSET** accounts (`NON_CURRENT_ASSETS`, `1500–1999`) — sparse in v1 (no Fixed Assets).
- **FINANCING** = `−(Δ net-debit)` of **EQUITY** accounts (3xxx) **excluding the current-year-earnings fold** (owner capital injections/drawings: 3000, 3100; **not** retained-earnings movement that is just this period's profit) **+** non-current LIABILITY (`2500–2999`, borrowings — sparse, none yet). Sign: an equity injection (credit, net-debit Δ < 0) **provides** cash → adds; a drawing (debit) **uses** cash → subtracts — the same `−(Δ net-debit)` rule.

> **Net income vs the equity-fold double-count (the subtlety the builder must get right).** OPERATING already starts from `netIncome` (the period INCOME − EXPENSE). FINANCING must therefore **exclude** any equity movement that is itself this period's earnings (otherwise the profit is counted twice). In v1 with no year-end close, the 3xxx accounts carry **only** owner capital + opening + prior retained earnings — the current-year profit is **not yet posted** to 3900 — so the period Δ on 3xxx is pure financing (capital in/out), and there is no double-count. The builder uses the **posted 3xxx period change only**; it never adds the equity-fold figure to financing. (When year-end close lands and posts profit to 3900, the close entry's `posting_date` is the year-end — the builder excludes the close entry from financing by source-type filter, or the close falls outside the reporting window; flagged for the close-automation increment, additive.)

**= Net change in cash** = OPERATING + INVESTING + FINANCING. The statement opens with **opening cash** = Σ `balanceAsAt(fromDate − 1)` over the cash-equivalent set, closes with **closing cash** = Σ `balanceAsAt(toDate)`.

**The tie-out self-check (BR-REP-04, the structural assertion).** The builder computes, independently, `Δcash = closingCash − openingCash` (the direct movement on the cash-equivalent GL accounts, via D-3(b) over `cashAccountIds`). It asserts `netChangeInCash.compareTo(Δcash) == 0` (`BigDecimal`, NFR-REP-06). Exposed as `ReconciliationDto` (`label="CF net change == Cash + Bank GL movement"`, `computed=netChangeInCash`, `expected=Δcash`, `ties`). A mismatch means a working-capital classification or the net-income figure is wrong → surfaced (§7.4), never plugged. The investing/financing sparseness does **not** relax this bar — if everything trades through cash and operating, the bar still ties to the cash movement.

### D-8 — Comparative window: a request parameter with a default derived per statement type

Every statement carries a **comparative** column — every figure is an `AmountPairDto{current, comparative}` (D-1). The comparative window is a **request parameter** (the reader may override, FR-REP-06); `ComparativeWindowResolver` supplies the default (OQ-REP-03, decided):

- **P&L / Cash-Flow** (flows): default comparative = the **immediately prior period of equal length** — `[fromDate − len, fromDate − 1]` where `len = toDate − fromDate + 1` days. (Override: prior fiscal year same period — the reader passes explicit `compareFrom`/`compareTo`.)
- **Balance Sheet** (stock): default comparative = the **prior period start** — as-at `fromDate − 1` of the selected period, i.e. the opening position; where the request is a fiscal-period quick-select, the prior year-end. (Override: explicit `compareAsAt`.)

The comparative is computed by running the **same builder** over the comparative window — no separate code path (BR-REP-01). A statement with no postings in either window renders zero/empty sections (a valid result, not an error — §7.4).

**Period selection (FR-REP-07).** The request accepts either explicit dates (`fromDate`/`toDate` for P&L/CF, `asAtDate` for BS) **or** a fiscal quick-select (`fiscalYearUid` / `fiscalPeriodUid` / a named token like `THIS_MONTH`/`THIS_QUARTER`/`THIS_FY`) that `ReportingService` resolves to dates **server-side** via the shipped `FiscalPeriodRepository` (`findByCompanyIdAndUid`, `findByFiscalYearIdOrderByPeriodNo`). Server-side resolution keeps the date math in one place and ties the quick-select to the company's actual fiscal calendar.

### D-9 — Export: server-side, OpenPDF (PDF) + Apache POI (XLSX) + CSV; statement-agnostic renderer; endpoint signatures + dependency coordinates

**Server-side generation, returning a download (decided — OQ-REP-05).** The server is the single source of truth for the figures and the reconciliation bars; a client-side renderer would re-implement the layout and risk divergence from the on-screen statement (NFR-REP-04 forbids divergence). Server-side guarantees the PDF/XLSX carry **exactly** the DTO the screen rendered.

**Libraries + licensing (decided):**
- **PDF → OpenPDF** (`com.github.librepdf:openpdf`). LGPL/MPL dual-licensed — **safe for a commercial closed-source product** (linkable). Explicitly **not iText 5** (AGPL — viral) and **not iText 7** (commercial paid). OpenPDF is the maintained LGPL fork of iText 4, table/PDF generation is first-class, and it is the boring, license-clean choice. Coordinate: `com.github.librepdf:openpdf:1.3.35` (or the current 1.3.x; pin in `backend/pom.xml`).
- **XLSX → Apache POI** (`org.apache.poi:poi-ooxml`). Apache-2.0 — license-clean; the standard JVM `.xlsx` writer. Coordinate: `org.apache.poi:poi-ooxml:5.3.0` (pulls `poi` + `poi-ooxml-lite` + `xmlbeans`). Heavier (~10 MB of transitive jars) but it is the only real `.xlsx` option and the modules will reuse it for any future spreadsheet export.
- **CSV → no dependency** — a tiny hand-rolled `CsvStatementRenderer` over the same `StatementRenderModel` (commons-csv is optional; not required).

> **The recommended trim, evaluated and NOT taken:** the brief allows CSV+PDF, deferring XLSX, if POI is too heavy. **Decision: ship all three (PDF + XLSX + CSV).** POI's weight is a build/jar concern, not a runtime risk, and the accountant persona explicitly "exports to Excel for further work" (reporting.md §4) — CSV-only would miss the primary spreadsheet use. If the dependency footprint is later a problem, dropping POI (keep PDF + CSV) is a clean, reversible trim. The renderer abstraction (below) makes adding/removing a format a single class.

**Renderer structure (statement-agnostic).** All four statement DTOs flatten to one **`StatementRenderModel`**: a header (company, currency, period + comparative labels, generated-at) + an ordered list of rows, each row a `{label, level (section/line/subtotal/total), currentAmount, comparativeAmount, isReconciliationBar}`. The builders produce this model once; each renderer consumes it:
- `PdfStatementRenderer` — a print-faithful A4 table: company + period header, two amount columns (current / comparative), section headers bold, subtotals/totals ruled, the reconciliation bar at the foot (green "ties" / red "does not balance"), base-currency label (NFR-REP-04).
- `XlsxStatementRenderer` — one sheet, the same rows, amounts as numbers (not strings) so the spreadsheet is workable, the header in a frozen top region.
- `CsvStatementRenderer` — the same rows, comma-delimited, amounts raw.

`ReportExporter.export(StatementRenderModel, ExportFormat)` → `{ byte[] content, String contentType, String filename }`.

**Endpoints (per statement; each `@PreAuthorize`'d on its view perm for the read and `REPORT.EXPORT` for the export):**
```
GET /api/v1/reporting/income-statement
      ?companyId&fromDate&toDate[&compareFrom&compareTo | &fiscalPeriodUid | &period=THIS_MONTH]
      @PreAuthorize @perm.has('REPORT.PL.VIEW')            → IncomeStatementDto
GET /api/v1/reporting/balance-sheet
      ?companyId&asAtDate[&compareAsAt | &fiscalPeriodUid]
      @PreAuthorize @perm.has('REPORT.BS.VIEW')            → BalanceSheetDto
GET /api/v1/reporting/cash-flow
      ?companyId&fromDate&toDate[&compareFrom&compareTo]
      @PreAuthorize @perm.has('REPORT.CASHFLOW.VIEW')      → CashFlowStatementDto
GET /api/v1/reporting/account-ledger
      ?companyId&accountUid&fromDate&toDate&page&size
      @PreAuthorize @perm.has('REPORT.LEDGER.VIEW')        → AccountLedgerDto (paginated rows)

GET /api/v1/reporting/income-statement/export?…&format=PDF|XLSX|CSV
      @PreAuthorize @perm.has('REPORT.EXPORT') and @perm.has('REPORT.PL.VIEW')
GET /api/v1/reporting/balance-sheet/export?…&format=…       (REPORT.EXPORT + REPORT.BS.VIEW)
GET /api/v1/reporting/cash-flow/export?…&format=…           (REPORT.EXPORT + REPORT.CASHFLOW.VIEW)
GET /api/v1/reporting/account-ledger/export?…&format=…      (REPORT.EXPORT + REPORT.LEDGER.VIEW)
```
Export responses set `Content-Type` (`application/pdf` | `application/vnd.openxmlformats-officedocument.spreadsheetml.sheet` | `text/csv`) and `Content-Disposition: attachment; filename="income-statement_2026-06.pdf"`, body = the `byte[]` (`ResponseEntity<byte[]>`). Export requires **both** the statement's view perm and `REPORT.EXPORT` — you cannot export what you may not view.

### D-10 — Permissions + the V15 perm-seed (perms only; NO business tables)

**The finer split (decided — OQ-REP-04).** Financial statements are sensitive; an owner may let a clerk drill a ledger but not see the company P&L. Seed the granular set plus a coarse umbrella:

| code | module | description |
| --- | --- | --- |
| `REPORT.VIEW` | reporting | Coarse grant: view all financial statements + the account-ledger drill-down |
| `REPORT.PL.VIEW` | reporting | View the Income Statement / P&L |
| `REPORT.BS.VIEW` | reporting | View the Balance Sheet |
| `REPORT.CASHFLOW.VIEW` | reporting | View the Cash-Flow Statement |
| `REPORT.LEDGER.VIEW` | reporting | Drill into the GL account-ledger from a statement line |
| `REPORT.EXPORT` | reporting | Export any statement / the ledger to PDF / Excel / CSV |

**Enforcement note (for the engineer):** `@perm.has('X')` is single-perm. The endpoints in D-9 gate on the **specific** perm (`REPORT.PL.VIEW` etc.). `REPORT.VIEW` is the coarse umbrella a simple deployment grants instead of the four fine perms. Two options for honouring the umbrella, engineer's pick (boring wins): (a) grant the four fine perms to any role that holds `REPORT.VIEW` at seed/role-config time; or (b) gate with `@PreAuthorize("@perm.has('REPORT.PL.VIEW') or @perm.has('REPORT.VIEW')")`. Recommend **(b)** — explicit at the call site, no implicit grant magic. `REPORT.EXPORT` is always required *in addition* for the export endpoints.

**`V15__reporting_permissions.sql` (the only migration — additive, perms only, no business tables; mirrors V14 §10 exactly):**
```sql
-- V15 — Financial Reporting permissions (ADR-0018). PERMS ONLY — Reporting owns no business table.
-- Additive only. V1–V14 are FROZEN.
INSERT INTO permissions (code, module, description) VALUES
    ('REPORT.VIEW',          'reporting', 'View all financial statements + the account-ledger drill-down (coarse)'),
    ('REPORT.PL.VIEW',       'reporting', 'View the Income Statement / Profit & Loss'),
    ('REPORT.BS.VIEW',       'reporting', 'View the Balance Sheet'),
    ('REPORT.CASHFLOW.VIEW', 'reporting', 'View the Cash-Flow Statement (indirect)'),
    ('REPORT.LEDGER.VIEW',   'reporting', 'Drill into the GL account-ledger from a statement line'),
    ('REPORT.EXPORT',        'reporting', 'Export any statement / the ledger to PDF / Excel / CSV')
ON CONFLICT (code) DO NOTHING;

INSERT INTO role_permission (role_id, permission_id)
SELECT r.id, p.id
FROM   roles r
CROSS JOIN permissions p
WHERE  r.code = 'ORG_ADMIN'
AND    p.code IN (
    'REPORT.VIEW','REPORT.PL.VIEW','REPORT.BS.VIEW',
    'REPORT.CASHFLOW.VIEW','REPORT.LEDGER.VIEW','REPORT.EXPORT'
)
ON CONFLICT DO NOTHING;
```

> **Finding #12 (seed-uid overflow) does NOT apply to V15.** That trap bites `uid`-bearing seed rows where a `'PREFIX' || lpad(company_id) || key` expression overflows `VARCHAR(26)`. **`permissions` and `role_permission` carry no `uid`** — V15 seeds only `(code, module, description)` and `(role_id, permission_id)`. No `uid` is constructed, so there is nothing to overflow. The discipline is noted only to confirm it was checked.

### D-11 — Read-only / scope / `assertCanActIn` on every read path

Every `ReportingService` method (and every query object: `AccountMovementQuery`, `AccountLedgerQuery`, `CashEquivalentAccountResolver`) calls **`scopeGuard.assertCanActIn(RequestContext.get(), companyId)` first**, before any read — the shipped `TrialBalanceQuery` / `CashGlReconciliationQuery` pattern (NFR-REP-01, FR-REP-08, BR-REP-10). `companyId` is a validated request param; no read crosses company scope. The account-ledger drill resolves `accountUid → companyId` (or simply scopes the line query by the validated `companyId` and asserts the account belongs to it) and `assertCanActIn` on that company — the `ScopeGuard case "account"` precedent. Reporting holds **no write path**: no `save`/`delete`, no `GLPostingService` call, no `code_sequence`, no entity. **Audit (NFR-REP-05, OQ — decided):** reads need no mutation audit (nothing mutates). Recommend a **light audit row on export** only — `AuditService.record("REPORT.EXPORT", statementType, companyId, period)` — because an exported financial statement leaves the system (an owner/auditor walks out with the P&L PDF); on-screen views rely on platform read-access logging. This is the one optional audit touch; if the platform read-access log already captures it, skip it (engineer confirms; the light export audit is the recommended default, low cost).

### D-12 — ArchUnit boundary: `reporting → gl.read` + `reporting → cashbank.read`, leaf reader, no write, no cycle

Reporting depends on **GL** and **Cash & Bank** read surfaces — and unlike pure DTO-crossing, it reads their **repositories + entities** (`JournalLineRepository`, `ChartOfAccountRepository`, `FiscalPeriodRepository`, `JournalEntry`/`JournalLine`/`ChartOfAccount`, `CashBankAccountRepository`, `CashBankAccount`). **This is the documented, allowed cross-module read** — the *exact* stance the shipped `CashGlReconciliationQuery` already takes (`cashbank.service` reading `gl.repository` + `gl.domain.entity`). The intended allow-set:

- **Allowed:** `com.erp.modules.reporting..` may depend on `com.erp.modules.gl.repository..`, `com.erp.modules.gl.service..` (e.g. `TrialBalanceQuery`, `FiscalPeriodResolver`), `com.erp.modules.gl.domain..`, and `com.erp.modules.cashbank.repository..` + `com.erp.modules.cashbank.domain.entity..` (the `gl_account_id` set) — **read-only**.
- **Forbidden (the ArchUnit assertions to add):**
  1. `reporting` must **not** depend on `GLPostingService` / any posting writer / `code_sequence` / `JournalBatchNumberGenerator` (no posting from reporting). A `noClasses().that().resideInAPackage("com.erp.modules.reporting..").should().dependOnClassesThat().haveSimpleName("GLPostingService")` (and the other writers) assertion.
  2. No class outside `com.erp.platform.audit..` touches `AuditRepository` (the shipped rule already covers reporting — reporting writes audit only via `AuditService`, D-11).
  3. **No cycle:** `gl` and `cashbank` must **not** depend on `reporting` (`noClasses().that().resideInAPackage("com.erp.modules.gl..").should().dependOnClassesThat().resideInAPackage("com.erp.modules.reporting..")`, and the same for `cashbank`). Reporting is a leaf consumer; nothing depends back on it.
  4. Reporting owns **no JPA `@Entity`** and **no repository that extends `JpaRepository<X,…>` for a reporting-owned table** — its read query objects extend at most a read-only `Repository<JournalLine,…>` projection over GL's tables (it owns no table). An assertion that `com.erp.modules.reporting..` contains no `@Entity` documents the read-only stance.

The existing `controllersDoNotAccessRepositories` rule still holds — the `com.erp.api` reporting controllers touch only `ReportingService`.

## Consequences

**Positive**
- **The three reconciliation bars are structural, computed from the same aggregates the statements present** (D-5/D-6/D-7): the P&L net, the BS balance, and the CF cash tie-out each have an independent self-check exposed as a `ReconciliationDto` (`ties` flag). A broken bar is surfaced as a data-integrity alarm, never plugged — the finance-grade correctness the spec demands (BR-REP-02/03/04, NFR-REP-01) is built in, not hoped for.
- **Pure read model, fully additive.** No new business table, no entity, no posting, no `code_sequence`; the only migration is `V15` perms. Zero risk to the books; the module can be removed without touching a single posted row.
- **Reuses the proven GL read surface** (`TrialBalanceQuery`, `JournalLineRepository.accountBalance`, `FiscalPeriodResolver`) and the proven `cashbank → gl.read` leaf-reader pattern (`CashGlReconciliationQuery`) — minimal new query surface (D-3), all aggregated in SQL.
- **License-clean, server-side export** (OpenPDF LGPL + POI Apache-2.0) with a statement-agnostic renderer — adding a format or a future statement is one class; the PDF/XLSX cannot diverge from the on-screen DTO (NFR-REP-04).
- **Auto-derive mapping is code, not data** — re-banding (OQ-REP-01) or moving Purchases out of cost-of-sales is a one-line `StatementClassifier` change, no migration.
- **Forward-compatible** (NFR-REP-07): the `AmountPairDto` comparative mechanism is the seam for budget-vs-actual; the equity-fold is the foundation for the Statement of Changes in Equity; company-level scoping does not preclude dimensions/consolidation.

**Negative / costs**
- **Two new dependencies** (OpenPDF, Apache POI) — POI adds ~10 MB of transitive jars to the backend. Accepted (D-9); reversible (drop POI → PDF+CSV).
- **Reporting reads GL/Cash-Bank internals** (repositories + entities), a wider coupling than DTO-only crossing. Mitigated: it is the documented, ArchUnit-asserted read-only allow-set (D-12), already established by `CashGlReconciliationQuery`; the direction is one-way (no cycle).
- **The equity-fold is inception-to-date** (D-6, revised) — it folds the FULL net P&L-account residual as-at the date, so the BS balances unconditionally across any number of fiscal years even with no year-end close (the presentation splits it into prior-year-retained + current-year). Forward-compatible: when close-automation (gl.md OQ-GL-03) later zeroes closed P&L into 3900, the same formula keeps balancing with no double-count.
- **The account-ledger drill is the one row-by-row read** (running balance) — mitigated by pagination (D-3(d), NFR-REP-02).
- **CF investing/financing are sparse** (no Fixed Assets/Loans) — owner-accepted (reporting.md §10.2); the structure is ready and the tie-out bar holds regardless.

## Alternatives considered

- **Auto-derive mapping vs a configurable `statement_templates` table — DECIDED: auto-derive (owner ratification, BR-REP-07).** Recorded here for the trail: a template table (rows mapping accounts/ranges to custom statement lines, saved layouts) is the more flexible long-term model, but it is a *configuration UI + a data model* the v1 slice does not need — the TZ small-business CoA derives cleanly from `account_type` + code band. Auto-derive ships the statements now; the template table is an additive later slice (OQ-REP-06) that the `StatementClassifier` seam does not preclude (a future classifier can consult a template table first, fall back to the derivation).
- **Server-side vs client-side export — DECIDED: server-side.** Client-side (build the PDF/XLSX in Angular) avoids the backend libraries but re-implements the layout and the figures, risking divergence from the server's reconciled DTO (NFR-REP-04 forbids divergence) and duplicating the section/subtotal logic. Server-side keeps one source of truth.
- **OpenPDF vs iText 5 vs iText 7 vs a HTML→PDF engine (Flying Saucer / openhtmltopdf) — DECIDED: OpenPDF.** iText 5 is **AGPL** (viral — unacceptable for a closed product); iText 7 is **commercial** (paid licence). openhtmltopdf is viable (render an HTML template to PDF) but adds an HTML-templating layer and CSS-print fidelity quirks; OpenPDF's programmatic table API gives precise, boring control over a financial-statement layout with a clean LGPL licence. OpenPDF wins on licence + directness.
- **Apache POI (XLSX) vs CSV-only-in-v1 — DECIDED: POI + CSV both.** CSV-only is lighter and the recommended trim if POI's weight bites, but the accountant persona's "export to Excel for further work" (reporting.md §4) wants a real `.xlsx`; POI is the only credible JVM option. Both ship; POI is the reversible drop if needed.
- **Aggregate in SQL vs sum raw lines in Java — DECIDED: SQL (NFR-REP-02).** Loading `journal_lines` and summing in Java is simple to write but scans/transfers tens of thousands of rows per statement and blows the interactive envelope. `GROUP BY` in Postgres over `ix_journal_lines_company_account` / `ix_journal_entries_company_date` keeps it in seconds. The only Java-side row walk is the (paginated) ledger running balance.

## Open items (recommended defaults stand unless the owner overrides)

- **OQ-REP-01 — Code-range banding boundaries.** Default fixed in D-4 (current assets 1000–1499 / non-current 1500–1999; current liab 2000–2499 / non-current 2500–2999; cost-of-sales 5100–5199 / operating 5200–5999; out-of-band → type default). 5150 Purchases → cost-of-sales (D-4 note). *Decider:* architect (this ADR), finance review. *Tunable in code* (the classifier), no migration.
- **OQ-REP-02 — CF treatment of VAT/WHT control accounts.** Default decided in D-7: 1400/1500/2200/2300/2400 are **working-capital items in OPERATING**. The tie-out bar holds either way.
- **OQ-REP-03 — Comparative default window.** Default decided in D-8: prior period of equal length (P&L/CF), prior period start / prior year-end (BS); overridable per request.
- **OQ-REP-05 — Export library / approach.** Decided in D-9: server-side; OpenPDF (PDF) + Apache POI (XLSX) + CSV. Aligns with the future cross-cutting Documents/PDF capability (X.1) — when X.1 lands, the renderer can delegate to it (additive).
- **OQ-REP-04 — Permission granularity.** Decided in D-10: the finer split (`REPORT.PL.VIEW`/`REPORT.BS.VIEW`/`REPORT.CASHFLOW.VIEW`/`REPORT.LEDGER.VIEW`/`REPORT.EXPORT`) plus the coarse `REPORT.VIEW`; granted to `ORG_ADMIN` in V15.
- **Audit-on-export (NFR-REP-05).** Decided in D-11: a **light audit row on export** only (the statement leaves the system); on-screen views rely on platform read-access logging. Confirm the platform read-access log scope; if it covers export, skip the explicit row.
- **OQ-CUR-03 (carried) — Rounding mode & TZS decimals.** Half-up, TZS = 0 dp per ADR-0005 D-2 / NFR-REP-06; the statement aggregations, the BS balance check, the P&L self-check, and the CF tie-out must round **identically** to the GL figures they tie to (exact `compareTo`, no tolerance). *Confirm before go-live* — does not block the model.

---

## Summary

ADR-0018 designs **Financial Reporting as a pure read model over GL**: a flat `com.erp.modules.reporting` module with **no entity, no owned table, no posting, no `code_sequence`** that reuses the shipped **`TrialBalanceQuery`** + **`JournalLineRepository`** + **`FiscalPeriodResolver`** and adds a small set of **SQL-aggregated** period-windowed / as-at projections (D-3) to build the **Income Statement, Balance Sheet, Cash-Flow (indirect), and the account-ledger drill-down** — each with a **comparative** column (`AmountPairDto`), classified by a pure-function **`StatementClassifier`** on `account_type` + code band (the auto-derive mapping, no template table; exact bands in D-4 worked against the TZ CoA). The **current-year net income** folds into Balance-Sheet equity as a precise FY-to-date `INCOME − EXPENSE` presentation derivation (D-6); the **indirect cash-flow** is built from per-account balance changes with a uniform `−(Δ net-debit)` working-capital sign rule and ties to the **`cash_bank_accounts.gl_account_id` set** (D-7). Export is **server-side** — **OpenPDF** (LGPL) for PDF + **Apache POI** (Apache-2.0) for XLSX + a CSV fallback — behind a statement-agnostic `StatementRenderModel`/`ReportExporter` (D-9, dependency coordinates given). Permissions are the finer split (`REPORT.*`) seeded by the **perms-only `V15__reporting_permissions.sql`** and granted to `ORG_ADMIN` (D-10); `assertCanActIn` guards every read (D-11); the `reporting → gl.read` / `reporting → cashbank.read` leaf-reader boundary is the documented, ArchUnit-asserted allow-set with no cycle and no write path (D-12).

**Ready for build.** The module layout, the exact query shapes (verified against the shipped entity field names), the classifier bands, the equity-fold and CF constructions with their sign conventions, the comparative defaults, the export libraries + endpoint signatures + renderer structure, the perm set + the V15 SQL, and the boundary assertions are all concrete — the engineer writes the module, the queries, the renderers, and `V15__reporting_permissions.sql` without guessing a business rule.

**Read-only / additive — confirmed.** Reporting posts nothing, owns no business table, allocates no number; the **only** schema change is the additive **`V15__reporting_permissions.sql` (perms + `ORG_ADMIN` grant, no `uid` rows → finding #12 N/A)**. V1–V14 are untouched.

**The reconciliation bars are structurally guaranteed and asserted:** the **Balance Sheet balances** (ASSET == LIABILITY + EQUITY, the equity fold closes the double-entry gap — D-6 self-check), the **Cash-Flow ties to cash** (net change == movement on the cash/bank-linked GL accounts — D-7 self-check), and the **P&L net == the period INCOME − EXPENSE GL movement** (D-5 self-check) — each computed independently from the same SQL aggregates the statement presents, exposed as a `ReconciliationDto.ties` flag, surfaced (never plugged) on failure.
