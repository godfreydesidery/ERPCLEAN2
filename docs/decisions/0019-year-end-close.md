# 0019 — Year-End Close: a GL-depth operation that auto-closes the year's periods, posts ONE balanced closing journal rolling each P&L account direct to 3900 Retained Earnings (no Income Summary), and freezes the fiscal year — plus an append-only reopen that reverses the closing journal and flips the year back OPEN — additive over the frozen V1–V15, no Reporting change

- **Status:** Accepted
- **Date:** 2026-06-10
- **Deciders:** solutions-architect (owner-ratified Year-End Close requirements 2026-06-10 — all six scoping forks resolved; the closing-entry-vs-period-gate ordering and the mid-year-opened-account handling are ADR *decisions*, not requirements blockers; no ADR-0019-blocking open question remains, year-end-close.md §11)
- **Context source:** [docs/requirements/year-end-close.md](../requirements/year-end-close.md) (RATIFIED 2026-06-10 — FR-CLOSE-01..08, BR-CLOSE-01..14, US-CLOSE-01..04, §3 the operation, §7 flows, §10 accepted boundary, §11 OQ-CLOSE log; the ground truth for every rule below). [ADR-0013](0013-general-ledger-data-model.md) + the **shipped** `com.erp.modules.gl` — READ AGAINST THE ACTUAL CODE:
  - **`GLPostingService.post(JournalEntryDraft)`** (`GLPostingServiceImpl`) — the single synchronous double-entry engine: validates ≥2 lines, one-sided lines, active+same-company accounts, base currency, `Σ debit == Σ credit` (`BigDecimal.compareTo`), resolves the OPEN period via `FiscalPeriodResolver.resolveOpen(companyId, postingDate)` (**rejects if none/CLOSED — BR-GL-03**), allocates `JB-####`, persists batch+entry+lines, audits `GL.JOURNAL.POST`. **The closing entry posts through this engine unchanged** — so it MUST post while the `end_date`'s period is still OPEN (the ordering crux, D-5).
  - **`JournalEntryDraft(companyId, branchId, postingDate, description, sourceType, sourceRef, reversalOfId, postedBy, List<LineDraft>)`** + **`LineDraft(accountId, debitAmount, creditAmount, currency, lineMemo)`** — verified field names; the closing entry is built as one of these (D-4). `reversalOfId` is already wired through `post(...)` (line 119–121 of `GLPostingServiceImpl`).
  - **`GLPostingService.postReversal(originalEntryUid, reversalDate, sourceType, sourceRef, postedBy)`** — verified: it re-reads the original's lines, **swaps debit↔credit on every line** (balanced by construction, BR-GL-11), sets `reversalOfId = original.id`, and posts via `post(...)`. **This IS the reopen primitive** — reuse it (D-6); do NOT hand-build an inverse entry.
  - **`FiscalCalendarServiceImpl.closePeriod(uid)` / `reopenPeriod(uid)`** — verified: flips one `fiscal_periods` row OPEN/CLOSED, sets/clears `closed_at`/`closed_by`, audits `GL.PERIOD_CLOSE`/`GL.PERIOD_OPEN`. Reused for auto-close/auto-reopen of the year's periods (D-2/D-6).
  - **Entities/enums/repos:** `FiscalYear` (verified columns: `company_id, year_code, start_month, start_date, end_date, status` (`PeriodStatus{OPEN,CLOSED}`), `version`, audit — **NO `closed_at`/`closed_by`/`closing_journal_uid`**, so V16 adds them, D-7); `FiscalPeriod` (`status`, `closed_at`, `closed_by` exist). `FiscalYearRepository` (`findByUid`, `findByCompanyIdAndYearCode`, `findByCompanyIdOrderByStartDateDesc`, `findCompanyIdByUid`). `FiscalPeriodRepository` (`findByFiscalYearIdOrderByPeriodNo`, `findOpenPeriodForDate`). `JournalEntryRepository.findByCompanyIdAndSourceTypeAndSourceRef(companyId, sourceType, sourceRef)` (**locates the closing journal for reopen**, D-6) + `existsByReversalOfId`. `JournalLineRepository` (the balance reads, D-3). `GlConfigKey` (verified — **NO RETAINED_EARNINGS**; add it, D-9) + `GLConfigResolver.resolve(companyId, key)` (throws on missing/inactive — BR-GL-10). `GlConfigServiceImpl.seedDefaults` (the `DEFAULT_MAPPINGS` map new companies/ITs get — extend it, D-9). `JournalSourceType` (verified — **NO YEAR_END_CLOSE**; add it + decide the reversal representation, D-10). `ChartOfAccount` (`accountType ∈ {ASSET,LIABILITY,EQUITY,INCOME,EXPENSE}`, `normalBalance ∈ {DEBIT,CREDIT}`, `isActive`).
  [ADR-0018](0018-financial-reporting-read-model.md) **D-6** — the inception-to-date equity fold; the close↔reporting consistency is **already** handled there (the closed year's P&L nets to zero incl. the closing entry; the rolled net sits in posted 3900; the fold's forward-compatibility note explicitly anticipates this close). Confirmed in D-12 — **NO Reporting change**. [ADR-0005](0005-money-and-currency.md) (base currency only; `BigDecimal`, no float). [PROJECT-CONVENTIONS.md](../../PROJECT-CONVENTIONS.md) §2 (`ModuleBoundaryTest`), §3.2 (tenant predicate / `assertCanActIn`), §3.6 (append-only / reversing — never edit or delete a posted journal, BR-GL-02). [[db-naming-convention]] (masters plural, constraints singular-root `chk_`/`fk_`/`uq_`, perms `ON CONFLICT` upsert). **Latest shipped migration: V15 (reporting perms). Year-End Close = `V16__year_end_close.sql`** — additive only; V1–V15 are FROZEN. Next ADR is 0020.

This ADR is the **technical operation design** for Year-End Close (a GL-depth slice — NOT a new module). It translates the ratified spec into the service home, the two operations' exact algorithms (the closing-entry `LineDraft` construction; the period-gate ordering; the reopen reversal), the P&L-balance read, the `RETAINED_EARNINGS` config key + the service-seeder change, the `YEAR_END_CLOSE` source type + its reversal representation, the additive `V16` migration (the `fiscal_years` columns, the #12-safe `RETAINED_EARNINGS` seed, the source-type CHECK widen, the `GL.YEAR.CLOSE` perm + grant), the guards, scope/perm/audit, the close↔reporting consistency proof, and the ArchUnit boundary — **concrete enough that the backend engineer writes the operations + the closing-entry construction + `V16__year_end_close.sql` + the reopen without guessing a business rule**. It writes **no production code, no migration SQL** — that is the engineer's next step. **Nothing ratified is re-litigated.**

## Context

Year-End Close is the last Tier-1 finance piece (PATH-TO-FULL-ERP Phase A). Everything it consumes is shipped: `GLPostingService` posts a balanced journal synchronously; `FiscalCalendarService` opens years and closes/reopens periods; the period-status gate rejects posting into a CLOSED period; **3900 Retained Earnings** is seeded (V10); the Reporting equity fold is built inception-to-date precisely so a close needs no Reporting change (ADR-0018 D-6). The central architectural force is therefore **drive the proven GL primitives — do not re-implement posting, numbering, the period model, or the reversal mechanic; resolve only the genuinely new operation-ordering and construction questions a year-end close introduces.** Those new questions, and the forces around each:

- **The closing entry is dated INSIDE the year being closed, but the same operation locks that year.** The closing journal posts at `fiscal_years.end_date`, which falls in period 12 of the year being closed. The same operation auto-closes all the year's periods and marks the year CLOSED. `GLPostingService.post` resolves an **OPEN** period for `postingDate` and rejects a CLOSED one (BR-GL-03, verified in `GLPostingServiceImpl` line 102). So the order of "post the closing entry" vs "flip the periods CLOSED" is load-bearing: post first into an OPEN year, then lock — or post on a gate-bypassing system path. Resolved in D-5 (post-then-close; no bypass).

- **The reopen must post a reversal dated at `end_date` into a year whose periods are CLOSED.** The symmetric problem: the reopen reverses the closing journal (dated `end_date`), but at reopen time the year's periods are CLOSED, so `post(...)` would reject the reversal. Resolved in D-6 (reopen the periods to OPEN first, then post the reversal — the mirror of D-5).

- **Computing each P&L account's year balance.** The close needs each INCOME/EXPENSE account's net movement over `[start_date, end_date]`. This is a `SUM(debit)/SUM(credit) GROUP BY account` over `journal_lines` joined to `journal_entries` on the posting-date window — the exact shape Reporting's P&L already runs (ADR-0018 D-3(a)), but the close lives in `gl`, not `reporting`. Resolved in D-3 (add ONE windowed projection to `gl`'s own `JournalLineRepository` — `gl` may not depend on `reporting`).

- **Where the roll lands and how it is identified.** The net rolls to 3900, resolved from config not a hard-coded code (BR-GL-10); the closing/reversing journals must be identifiable as system close entries. Resolved in D-9 (a `RETAINED_EARNINGS` key → 3900) and D-10 (a `YEAR_END_CLOSE` source type; the reversal reuses `postReversal` with the same `YEAR_END_CLOSE` source type — no separate enum value, decided).

- **Storing the close result on the year.** `fiscal_years` carries only `status` today — no `closed_at`/`closed_by`/closing-journal link. The view requirement (FR-CLOSE-06: who/when, the closing journal) and the reopen (which journal to reverse) both need it. Resolved in D-7 (three additive columns on `fiscal_years`).

- **Schema freeze / migration ordering.** IAM=V1 … VAT=V14, Reporting perms=V15 — all frozen and shipped. Year-End Close adds **no new table**; its only migration is the additive **`V16__year_end_close.sql`** (three `fiscal_years` columns, the `RETAINED_EARNINGS` config seed, the source-type CHECK widen, the `GL.YEAR.CLOSE` perm + grant). It must not edit V1–V15.

## Decision

### D-1 — Operation home: a dedicated `YearEndCloseService(+Impl)` in `com.erp.modules.gl.service`; controller flat in `com.erp.api`

The two operations live in a **new `YearEndCloseService` (+`YearEndCloseServiceImpl`)** under `com.erp.modules.gl.service`, **not** bolted onto `FiscalCalendarService`. Year-End Close is a distinct, cohesive responsibility (read P&L balances → construct + post a closing journal → drive the year/period state machine → reverse on reopen) that orchestrates **four** collaborators; `FiscalCalendarService` is the period/year CRUD + open/close-period primitive and should stay that. A dedicated service keeps each at one responsibility (the same reasoning ADR-0018 D-1 used for `reporting`). It is a GL-depth operation — **no new module** (NFR-CLOSE-06); it sits inside `gl` beside the engine it drives.

```
com.erp.modules.gl.service
└── YearEndCloseService(+Impl)        — closeFiscalYear(fiscalYearUid) + reopenFiscalYear(fiscalYearUid)
        depends on:
          GLPostingService            — post the closing entry / postReversal for reopen (D-4/D-6)
          GLConfigResolver            — resolve RETAINED_EARNINGS → active 3900 (D-9)
          FiscalCalendarService       — closePeriod(uid) / reopenPeriod(uid) reuse (D-2/D-6)
          FiscalYearRepository        — load the year, flip status, stamp closed_at/by/closing_journal_uid
          FiscalPeriodRepository      — list the year's periods (findByFiscalYearIdOrderByPeriodNo)
          ChartOfAccountRepository    — list the company's INCOME/EXPENSE accounts (D-3)
          JournalLineRepository       — the per-account year-balance read (D-3)
          JournalEntryRepository      — locate the closing journal on reopen (findBy…SourceTypeAndSourceRef)
          CompanyRepository           — base currency for the LineDraft currency field
          ScopeGuard + AuditService   — assertCanActIn + audit close/reopen (D-11)
```

**Controller:** a flat **`YearEndCloseController`** in `com.erp.api` (a clean sibling of `FiscalPeriodController` — year-close is a different perm `GL.YEAR.CLOSE` and a distinct, sensitive verb, so a separate controller reads cleaner than overloading `FiscalPeriodController`). Two endpoints, addressing the year by `uid`, gated on `GL.YEAR.CLOSE`, returning the updated `FiscalYearDto`:

```
POST /api/v1/gl/year-end/fiscal-years/uid/{uid}/close
      @PreAuthorize("@perm.scoped(#uid,'fiscalyear','GL.YEAR.CLOSE')")   → FiscalYearDto
POST /api/v1/gl/year-end/fiscal-years/uid/{uid}/reopen
      @PreAuthorize("@perm.scoped(#uid,'fiscalyear','GL.YEAR.CLOSE')")   → FiscalYearDto
```

> **ScopeGuard `case "fiscalyear"` — one-line add.** `@perm.scoped(#uid,'fiscalyear',…)` needs `ScopeGuard` to resolve a `fiscal_years.uid` → `company_id`. `FiscalYearRepository.findCompanyIdByUid(uid)` **already exists** (verified); the engineer adds the one switch arm `case "fiscalyear" -> fiscalYears.findCompanyIdByUid(uid);` in `ScopeGuard` (mirrors the shipped `case "fiscalperiod"`/`case "journalentry"`). The `FiscalYearRepository` is already a `ScopeGuard` collaborator candidate; wire it if not already injected.

> **`FiscalYearDto` — surface the close result (additive fields).** The shipped `FiscalYearDto` is `(id, uid, companyId, yearCode, startMonth, startDate, endDate, status)`. To satisfy FR-CLOSE-06 (who/when + the closing journal), the engineer adds the three new fields (`closedAt`, `closedBy`, `closingJournalUid`) to the record and maps them in `FiscalCalendarServiceImpl.toYearDto` + the new service. Additive to a DTO record — no contract break for existing readers that ignore the extra fields. (The net-rolled figure is derivable from the closing journal lines on drill; v1 need not store it as a column — see D-7 note.)

### D-2 — `closeFiscalYear(fiscalYearUid)` — the exact algorithm (one TX, atomic — NFR-CLOSE-04)

`@Transactional` (default propagation; the whole operation is one TX — a failure at any step rolls back, leaving the year OPEN and nothing posted, NFR-CLOSE-04). Steps in order:

1. **Resolve + scope.** `FiscalYear year = years.findByUid(fiscalYearUid)` (404 if absent). `scopeGuard.assertCanActIn(RequestContext.get(), year.getCompanyId())` (NFR-CLOSE-01, every read path).
2. **Guards (D-8), reject before any write:**
   - year `status == OPEN` (else `ConflictException("fiscal year already closed")` — BR-CLOSE-05);
   - the year **has periods**: `periods.findByFiscalYearIdOrderByPeriodNo(year.id)` non-empty (else `ConflictException("fiscal year has no periods")` — BR-CLOSE-05);
   - the **immediately prior** fiscal year is CLOSED **or there is none** (BR-CLOSE-04, D-8) — else `ConflictException("prior fiscal year must be closed first")`.
3. **Compute each P&L account's year balance** (D-3): for every INCOME/EXPENSE account of the company, the net movement over `[year.startDate, year.endDate]`. Build the in-memory list of `(account, yearBalanceSignedToZero)` for the **non-zero** accounts only (OQ-CLOSE-05 — zero-movement and never-touched accounts contribute no line).
4. **Construct the ONE closing `JournalEntryDraft`** (D-4), dated `year.endDate`, `sourceType = YEAR_END_CLOSE`, `sourceRef = year.uid`, `postedBy = actorId()`, `branchId = null` (a company-level journal — the books are company-level, ADR-0013 D-7). Balanced by construction.
5. **Post the closing entry FIRST, while the period is still OPEN** (D-5): `JournalEntryDto closing = glPostingService.post(closingDraft);`. The `end_date` resolves to period 12, which is still OPEN at this point, so `FiscalPeriodResolver.resolveOpen` succeeds. The engine re-validates the balance (BR-CLOSE-03).
6. **Auto-close every still-OPEN period** of the year (BR-CLOSE-06): iterate `periods.findByFiscalYearIdOrderByPeriodNo(year.id)`; for each with `status == OPEN`, call `fiscalCalendarService.closePeriod(period.getUid())` (reuse — it stamps `closed_at`/`closed_by`, audits `GL.PERIOD.CLOSE`). (After step 5 posted, locking is safe.)
7. **Mark the fiscal year CLOSED:** `year.setStatus(CLOSED); year.setClosedAt(Instant.now()); year.setClosedBy(actorId()); year.setClosingJournalUid(closing.uid()); year.setUpdatedAt/By(...)` → JPA flush (the entity is managed; no explicit save needed but `years.save(year)` is fine).
8. **Audit the close** (NFR-CLOSE-03, D-11): `audit.record(AuditEvent.of("GL.YEAR.CLOSE", "fiscal_years", year.id, year.uid).detail(Map.of("yearCode", year.yearCode, "closingJournalUid", closing.uid(), "netRolled", <the 3900 line amount, signed>)))`.
9. **Return** `toYearDto(year)` (now CLOSED, with the closing-journal uid).

> **Why post-then-close, not close-then-post-on-a-bypass (D-5 is the headline; this is the step where it lands).** Step 5 runs before step 6. The closing entry posts into the **still-OPEN** period 12; only then are the periods locked. This needs **zero** change to `GLPostingService` (no system-bypass flag, no second posting path), which is the boring, correct answer (OQ-CLOSE-03 resolved — see D-5).

### D-3 — The P&L-balance read: ONE new windowed projection on `gl`'s `JournalLineRepository`; classify by `account_type`, not code range

The close needs each INCOME/EXPENSE account's net movement over the fiscal year. **`gl` must not depend on `reporting`** (no module cycle; `reporting` is the leaf reader of `gl`, ADR-0018 D-12), so the close cannot call `AccountMovementQuery`. Add the windowed aggregate to `gl`'s own `JournalLineRepository` (it already owns `trialBalanceSums` / `accountBalance` — this is the same family):

```jpql
SELECT l.accountId, SUM(l.debitAmount) AS d, SUM(l.creditAmount) AS c
FROM   JournalLine l
JOIN   JournalEntry e ON e.id = l.entryId
WHERE  l.companyId = :companyId
  AND  e.postingDate BETWEEN :fromDate AND :toDate
GROUP  BY l.accountId
```
named e.g. `periodMovementByAccount(companyId, fromDate, toDate)` → `List<Object[]>` `[accountId, sumDebit, sumCredit]`. Hits the shipped `ix_journal_entries_company_date` for the date window and `ix_journal_lines_company_account` for the group. The window is `[year.startDate, year.endDate]` inclusive.

**Selecting the P&L accounts (by TYPE, not code — the spec's explicit instruction, §8 line 612):** the close reads each account's `account_type` to decide whether it is a P&L account, not its code band. The service lists the company's accounts (`ChartOfAccountRepository` — add a `findByCompanyIdAndAccountTypeIn(companyId, List.of(INCOME, EXPENSE))` finder, or filter `findByCompanyId` in memory; recommend the finder for clarity). For each such account, look up its movement from the `periodMovementByAccount` result map.

**Net per account, signed to zero (the LineDraft direction — D-4):**
- An **INCOME** account is CREDIT-normal; its year balance (credit-heavy) = `sumCredit − sumDebit`. To zero it, post a line that puts that net on the **opposite (debit)** side: `debitAmount = sumCredit − sumDebit`. (If the figure is negative — an income account with a net debit, e.g. heavy refunds — flip to a credit of the absolute value. Service handles the sign generically via `signedMovementDebitNormal` below.)
- An **EXPENSE** account is DEBIT-normal; its year balance (debit-heavy) = `sumDebit − sumCredit`. To zero it, post a **credit** of that net: `creditAmount = sumDebit − sumCredit`.

A clean, sign-safe formulation the engineer can implement once for both types: for each P&L account compute `netDebit = sumDebit − sumCredit` (its net movement expressed debit-positive). The **zeroing line is the negation**: if `netDebit > 0`, post a **credit** of `netDebit`; if `netDebit < 0`, post a **debit** of `−netDebit`; if `netDebit == 0`, **skip** the account (no line — OQ-CLOSE-05). This single rule zeroes both INCOME and EXPENSE accounts regardless of their normal balance or an unusual contra-balance, and is verified against the shipped `LineDraft` (debit-or-credit, exactly one positive). The **3900 balancing line** is `Σ over P&L lines` of the opposite — see D-4.

### D-4 — The closing-entry `LineDraft` construction (per-account zeroing + the 3900 balancing line)

The closing journal is one `JournalEntryDraft` whose lines are:

1. **One zeroing line per non-zero P&L account** (D-3): for each INCOME/EXPENSE account with `netDebit = sumDebit − sumCredit != 0`,
   ```
   new LineDraft(account.id,
                 netDebit < 0 ? netDebit.negate() : null,   // debit if the account is net-credit
                 netDebit > 0 ? netDebit          : null,   // credit if the account is net-debit
                 baseCurrency, "Year-end close FY… — zero <code>")
   ```
   (Exactly one of debit/credit positive; the other 0/null — the shipped engine maps null→0 and asserts one-sided. The `lineMemo` is optional.)
2. **One balancing 3900 Retained-Earnings line** — the net profit/loss:
   - Let `netProfit = Σ(INCOME netCredit) − Σ(EXPENSE netDebit)` = the year's INCOME − EXPENSE. Computed directly as `−(Σ over all P&L accounts of netDebit)` (because the sum of every P&L account's net-debit movement, negated, is exactly INCOME − EXPENSE).
   - Resolve 3900 via `glConfigResolver.resolve(companyId, RETAINED_EARNINGS)` (D-9) — **throws if unmapped/inactive → the close fails, nothing posted** (BR-CLOSE-11, FR-CLOSE-07, the §7.3 unhappy path).
   - **Net profit (`netProfit > 0`): CREDIT 3900** by `netProfit`.
   - **Net loss (`netProfit < 0`): DEBIT 3900** by `−netProfit`.
   - **Exactly break-even (`netProfit == 0`):** no 3900 line is needed, but then the P&L zeroing lines already balance among themselves (Σ debit == Σ credit) — the entry still has ≥2 lines as long as ≥2 P&L accounts moved. (Edge: a single P&L account with a non-zero balance and a zero net is impossible; if exactly one account moved, `netProfit != 0` and the 3900 line is the 2nd line — ≥2 satisfied. The engineer asserts ≥2 lines defensively; an all-zero year is caught by step 3 yielding no lines → see the note.)

**Balanced by construction (BR-CLOSE-03).** The 3900 line is the algebraic complement of the P&L zeroing lines: `Σ debits(P&L) + DR-3900-if-loss == Σ credits(P&L) + CR-3900-if-profit`. `GLPostingService.post` re-validates `Σ debit == Σ credit` (`BigDecimal.compareTo`) and rejects an unbalanced entry — a construction defect is surfaced, never half-written.

> **Empty-year-of-trading edge (no P&L movement at all).** If the year had no INCOME/EXPENSE movement, step 3 yields zero lines and `netProfit == 0`. There is nothing to close. **Recommended:** the service treats this as a no-op closing journal **not posted** (you cannot post a <2-line entry), but the year still **closes** (periods auto-close, `status=CLOSED`, `closing_journal_uid = NULL`). Document this: a year with periods but no P&L activity closes without a closing journal. (The reopen of such a year — D-6 — has no journal to reverse; it just flips state. The `closing_journal_uid` nullable column already models this.) This is the correct, boring handling; it does not contradict BR-CLOSE-01 (every P&L account *with a non-zero balance* is zeroed — there are none).

> **The net-rolled figure in audit.** The `netRolled` audit detail (NFR-CLOSE-03) = the signed 3900 line amount (`+netProfit` credit / `−|loss|` debit), or `0` for the no-journal edge.

### D-5 — The period-gate ordering (THE load-bearing decision): POST the closing entry, THEN flip the periods CLOSED, THEN mark the year CLOSED — no gate bypass (OQ-CLOSE-03 resolved)

The closing entry is dated at `end_date`, inside period 12 of the year being closed. `GLPostingService.post` resolves an **OPEN** period for the posting date and rejects a CLOSED one (verified, `GLPostingServiceImpl` line 102, BR-GL-03). Two ways to reconcile "post at `end_date`" with "lock the year":

**Decision: post the closing entry into the still-OPEN year FIRST (D-2 step 5), then auto-close the periods (step 6), then mark the year CLOSED (step 7).** When the closing entry posts, period 12 is still OPEN, so `resolveOpen` succeeds and the engine applies every invariant unchanged. Only after the journal is on the books are the periods locked. This requires **no change to `GLPostingService`** — no system-bypass flag, no second posting path, no "post into a closed period" exception. It is the literal order-of-operations reading of the operation ("close the books" = record the closing entry, *then* lock), and the recommended default in OQ-CLOSE-03. Both orderings yield the same end state (closing entry on the books, year CLOSED); the post-first ordering achieves it without weakening the period gate that protects every other posting path. **Rejected:** a gate-bypassing system post (Alternatives) — it adds a privileged code path into the append-only engine purely to avoid an obvious sequencing, increasing the surface where a bug could post into a genuinely closed period.

The reopen (D-6) is the exact mirror: reopen the periods to OPEN **before** posting the reversal, then flip the year OPEN.

### D-6 — `reopenFiscalYear(fiscalYearUid)` — the exact algorithm (one TX, append-only — BR-CLOSE-07/08)

`@Transactional`. Steps:

1. **Resolve + scope.** `FiscalYear year = years.findByUid(uid)` (404); `assertCanActIn(…, year.companyId)`.
2. **Guards (D-8):**
   - year `status == CLOSED` (else `ConflictException("fiscal year is not closed")`);
   - it is the **most-recently-closed** year — i.e. **no later-starting fiscal year is CLOSED** (BR-CLOSE-10, D-8). Check via `years.findByCompanyIdOrderByStartDateDesc(companyId)`: walking from the most-recent, the first CLOSED year encountered must be *this* year; equivalently, no year with `startDate > year.startDate` has `status == CLOSED`. Else `ConflictException("only the most-recently-closed year may be reopened")`.
3. **Reopen the year's periods FIRST** (the mirror of D-5 — so the reversal can post at `end_date`): iterate `periods.findByFiscalYearIdOrderByPeriodNo(year.id)`; for each `CLOSED`, call `fiscalCalendarService.reopenPeriod(period.getUid())` (reuse — clears `closed_at`/`closed_by`, audits `GL.PERIOD.OPEN`).
4. **Post the closing-journal reversal** (append-only — a NEW reversing entry, BR-CLOSE-07/08, never an edit/delete):
   - If `year.closingJournalUid != null`: `glPostingService.postReversal(year.closingJournalUid, year.endDate, YEAR_END_CLOSE, year.uid, actorId())`. The shipped `postReversal` re-reads the closing journal's lines, swaps debit↔credit (restoring every P&L balance + backing out the 3900 roll), sets `reversalOfId = closingEntry.id`, posts via `post(...)` into the now-OPEN period 12. **Reuse it — do not hand-build the inverse.**
   - If `year.closingJournalUid == null` (the no-trading edge, D-4): no journal to reverse; skip this step.
   - **Idempotency guard (recommended):** before reversing, assert the closing entry has not already been reversed — `journalEntries.existsByReversalOfId(closingEntry.id)` should be false. (A re-close after a reopen posts a *fresh* closing journal with a *new* uid, which becomes the new `closing_journal_uid`; so each reopen reverses exactly the current closing journal.)
5. **Flip the year OPEN + clear the close stamps:** `year.setStatus(OPEN); year.setClosedAt(null); year.setClosedBy(null); year.setClosingJournalUid(null); year.setUpdatedAt/By(...)`. (Clearing `closing_journal_uid` is correct: the original closing entry and its reversal both stand on the books — append-only, BR-CLOSE-08 — but the year no longer has an *effective* closing journal; a re-close will set a new one. The audit trail + the reversal's `reversalOfId` preserve the history. **Decided: clear it** rather than keep it, for a clean "is this year closed?" read; the journal history is the durable record. See Alternatives.)
6. **Audit the reopen** (NFR-CLOSE-03): `audit.record(AuditEvent.of("GL.YEAR.CLOSE", "fiscal_years", year.id, year.uid).detail(Map.of("action","reopen","reversalJournalUid", <reversal.uid or "none">, "reversedClosingJournalUid", <prior closingJournalUid>)))`.
7. **Return** `toYearDto(year)` (now OPEN).

The **reverse-then-adjust-then-re-close** flow (§7.2) falls out for free: after reopen the year is OPEN, the accountant posts adjusting journals via the normal `GL.POST` path, then calls `closeFiscalYear` again — a fresh closing entry posts the corrected net to 3900, a new `closing_journal_uid` is stamped. The original closing entry, its reversal, the adjusting journals, and the re-close all stand on the books (append-only).

### D-7 — `fiscal_years` columns (V16, additive): `closed_at`, `closed_by`, `closing_journal_uid`

The shipped `fiscal_years` carries only `status` (verified V10). To satisfy FR-CLOSE-06 (who/when + which journal) and the reopen (which journal to reverse), V16 adds three nullable columns — mirroring the shape already on `fiscal_periods` (`closed_at`/`closed_by`) plus the `posted_journal_uid` pattern already on `vat_returns` (V14):

| column | type | null? | notes |
| --- | --- | --- | --- |
| `closed_at` | `TIMESTAMPTZ` | YES | set on close, cleared on reopen (mirrors `fiscal_periods.closed_at`) |
| `closed_by` | `BIGINT` | YES | FK → `app_users(id)`; who closed (audited); cleared on reopen |
| `closing_journal_uid` | `VARCHAR(26)` | YES | the posted closing `journal_entries.uid`; NULL when OPEN, when a no-trading year closed, or after reopen; the reopen reads it to reverse. **No FK** (the uid is a stable external ref, consistent with `vat_returns.posted_journal_uid` which is also an unconstrained `VARCHAR(26)`) |

`ALTER TABLE fiscal_years ADD COLUMN … ;` ×3 + `ALTER TABLE fiscal_years ADD CONSTRAINT fk_fiscal_year_closed_by FOREIGN KEY (closed_by) REFERENCES app_users (id);`. No data backfill (every existing year is OPEN → all three NULL). The `FiscalYear` entity gains the three `@Column` fields (+ setters, since both are mutated by the operation).

> **No `net_rolled` column (decided).** The net profit/loss rolled to 3900 is fully recoverable from the closing journal's 3900 line (drill via the `closing_journal_uid`), and the audit row records it. Storing it as a column duplicates a posted figure (a denormalisation that can drift) for no read that needs it as a column. The view (FR-CLOSE-06) reads it from the journal. (Additive later if a report wants it indexed — not now.)

### D-8 — The guards (BR-CLOSE), service-enforced, each rejecting before any write

| guard | rule | where | mechanism |
| --- | --- | --- | --- |
| **Prior year closed first** (BR-CLOSE-04, OQ-CLOSE-01) | a year closes only if the immediately prior fiscal year is CLOSED, or there is no prior year | `closeFiscalYear` step 2 | among `years.findByCompanyIdOrderByStartDateDesc(companyId)`, the year with the greatest `startDate < this.startDate` must have `status == CLOSED`; if none exists, satisfied vacuously |
| **No double close** (BR-CLOSE-05) | a CLOSED year cannot be closed again | `closeFiscalYear` step 2 | `year.status == OPEN` required, else `ConflictException` |
| **No empty-year close** (BR-CLOSE-05) | a year with no periods cannot be closed | `closeFiscalYear` step 2 | `findByFiscalYearIdOrderByPeriodNo(year.id)` non-empty |
| **Reopen only the latest closed** (BR-CLOSE-10, OQ-CLOSE-02) | only the most-recently-closed year may be reopened | `reopenFiscalYear` step 2 | no year with `startDate > this.startDate` has `status == CLOSED` |
| **Reopen requires CLOSED** | a non-CLOSED year cannot be reopened | `reopenFiscalYear` step 2 | `year.status == CLOSED` required |
| **Closed year blocks posting** (BR-CLOSE-09) | after close, all posting into the year is rejected | the **existing** period gate | unchanged — `FiscalPeriodResolver.resolveOpen` rejects a CLOSED period (BR-GL-03); no new code |
| **Required RETAINED_EARNINGS mapping** (BR-CLOSE-11) | a missing/inactive 3900 mapping fails the close | `closeFiscalYear` step 4 (D-4) | `GLConfigResolver.resolve` throws → TX rolls back, year stays OPEN |
| **Append-only** (BR-CLOSE-08) | the closing journal is never edited/deleted | structural | the only correction is the reopen reversal; no update/delete path exists on the ledger (ADR-0013 D-3) |

All guards reject with a clear `ConflictException`/`IllegalStateException` message **before** any post or status change (atomicity, NFR-CLOSE-04).

### D-9 — `RETAINED_EARNINGS` `gl_config` key → 3900 (the roll account, no hard-coded code)

- **Enum:** widen `GlConfigKey` with `RETAINED_EARNINGS` (a new value; verified absent today). The closing entry resolves the 3900 account through `GLConfigResolver.resolve(companyId, RETAINED_EARNINGS)` — never a hard-coded `"3900"` (BR-GL-10). The resolver already throws on missing/inactive (the close-fails path, BR-CLOSE-11).
- **Service seeder (the NEW-company path):** extend `GlConfigServiceImpl.DEFAULT_MAPPINGS` with `Map.entry(GlConfigKey.RETAINED_EARNINGS, "3900")`. **Confirmed: 3900 Retained Earnings is in the V10 CoA default seed** (verified, V10 line 334), so `seedDefaults` will resolve it for every new company / IT-bootstrapped company. (This is the path NEW companies and integration-test bootstraps take; the V16 INSERT below covers EXISTING companies.)
- **CHECK widen:** `gl_configs.chk_gl_config_key` must admit `RETAINED_EARNINGS` (V16, D-11) — the sanctioned DROP/ADD widen, union of all V14 keys + `RETAINED_EARNINGS`.

### D-10 — `YEAR_END_CLOSE` source type; the reversal reuses it (no separate enum value — decided)

- **Enum:** widen `JournalSourceType` with `YEAR_END_CLOSE` (a new value; verified absent today). The closing journal carries `sourceType = YEAR_END_CLOSE`, `sourceRef = year.uid` (so the reopen locates it via `findByCompanyIdAndSourceTypeAndSourceRef(companyId, YEAR_END_CLOSE, year.uid)`).
- **The reversal representation — reuse `YEAR_END_CLOSE` with `reversalOfId` set, NOT a separate `YEAR_END_CLOSE_REVERSAL` (decided).** The shipped `postReversal(originalUid, date, sourceType, sourceRef, postedBy)` posts the reversal with whatever `sourceType` it is handed, setting `reversalOfId` to the original. The reopen passes `sourceType = YEAR_END_CLOSE`. The reversal is then unambiguously identifiable as **"a YEAR_END_CLOSE entry that has a non-null `reversalOfId`"** — distinct from the original (whose `reversalOfId` is null). This avoids a second enum value and a second CHECK token, and mirrors how the SALES path already works (`SALES` original vs `SALES_REVERSAL`)… **except** the brief offered `SALES_REVERSAL` as precedent for a distinct value. We deviate deliberately: the `reversalOfId` discriminator is already present and sufficient, so a dedicated `YEAR_END_CLOSE_REVERSAL` adds an enum value + a CHECK token for zero new information. (If a future report wants to filter "close reversals" without joining on `reversalOfId IS NOT NULL`, adding the dedicated value is a trivial additive later — not now.) **One source-type token (`YEAR_END_CLOSE`) is added to the CHECK and the enum.**
- **CHECK widen:** the `chk_journal_batch_source_type` + `chk_journal_entry_source_type` CHECKs widen to admit `YEAR_END_CLOSE` (V16, D-11) — the DROP/ADD pattern, union of all V14 tokens + `YEAR_END_CLOSE`.

### D-11 — `V16__year_end_close.sql` (additive only; V1–V15 FROZEN)

Five sections, in order. **No new table.** (SQL described precisely; the engineer writes it.)

**(1) `fiscal_years` columns (D-7).**
```
ALTER TABLE fiscal_years ADD COLUMN closed_at           TIMESTAMPTZ;
ALTER TABLE fiscal_years ADD COLUMN closed_by           BIGINT;
ALTER TABLE fiscal_years ADD COLUMN closing_journal_uid VARCHAR(26);
ALTER TABLE fiscal_years ADD CONSTRAINT fk_fiscal_year_closed_by
        FOREIGN KEY (closed_by) REFERENCES app_users (id);
```

**(2) `gl_configs` CHECK widen — admit `RETAINED_EARNINGS` (D-9).** DROP `chk_gl_config_key`, ADD it back with the full V14 IN-list **plus** `'RETAINED_EARNINGS'` (union — do not drop any existing token):
```
'SALES_REVENUE','VAT_PAYABLE','ACCOUNTS_RECEIVABLE','CASH','INVENTORY','COGS','ACCOUNTS_PAYABLE',
'BAD_DEBT_EXPENSE','OPENING_BALANCE_EQUITY','PURCHASES','VAT_INPUT','VAT_DUE','WHT_PAYABLE',
'WHT_RECEIVABLE','RETAINED_EARNINGS'
```

**(3) `RETAINED_EARNINGS` config seed per EXISTING company → 3900 (#12-safe uid, D-9).** Mirror V14 §8's seed exactly — join the just-confirmed 3900 CoA row, with the **#12-safe** seed-uid `'YEC' || lpad(company_id,6,'0') || substr(md5('RETAINED_EARNINGS'),1,12)` = **3 + 6 + 12 = 21 chars** (≤ `VARCHAR(26)` — never `|| config_key`, which would overflow):
```sql
INSERT INTO gl_configs (uid, company_id, config_key, account_id, version, created_at)
SELECT 'YEC' || lpad(coa.company_id::text, 6, '0') || substr(md5('RETAINED_EARNINGS'), 1, 12),
       coa.company_id, 'RETAINED_EARNINGS', coa.id, 0, now()
FROM   chart_of_accounts coa
WHERE  coa.account_code = '3900'
ON CONFLICT (company_id, config_key) DO NOTHING;
```
(`3900 Retained Earnings` is seeded for every company by V10, so the join resolves for all; `ON CONFLICT` makes the seed idempotent on a keep-data re-deploy.)

**(4) journal source-type CHECK widen — admit `YEAR_END_CLOSE` (D-10).** DROP/ADD `chk_journal_batch_source_type` **and** `chk_journal_entry_source_type`, each with the full V14 IN-list **plus** `'YEAR_END_CLOSE'`:
```
'MANUAL','SALES','SALES_REVERSAL','OPENING_BALANCE',
'AR_RECEIPT','AR_WRITEOFF','AR_CREDIT_NOTE',
'AP_BILL','AP_PAYMENT','AP_DEBIT_NOTE',
'CASH_TRANSFER','CASH_DIRECT','VAT_RETURN','YEAR_END_CLOSE'
```

**(5) `GL.YEAR.CLOSE` permission + `ORG_ADMIN` grant (D-12, mirror V14 §10 / V15).** One permission (covers both close and reopen — sensitive, separate from `GL.PERIOD.CLOSE`):
```sql
INSERT INTO permissions (code, module, description) VALUES
    ('GL.YEAR.CLOSE', 'gl', 'Close / reopen a fiscal year — the year-end closing entry + the reopen reversal')
ON CONFLICT (code) DO NOTHING;

INSERT INTO role_permission (role_id, permission_id)
SELECT r.id, p.id
FROM   roles r CROSS JOIN permissions p
WHERE  r.code = 'ORG_ADMIN' AND p.code = 'GL.YEAR.CLOSE'
ON CONFLICT DO NOTHING;
```

> **Finding #12 (seed-uid overflow) — handled.** The only `uid`-bearing seed row in V16 is the `gl_configs` `RETAINED_EARNINGS` row, whose uid is built `'YEC' || lpad(company_id,6,'0') || substr(md5(key),1,12)` = 21 chars (the V14-proven pattern), **never** `|| config_key`. The `permissions`/`role_permission`/`fiscal_years`-`ALTER` rows carry no constructed uid. No overflow path exists.

> **AuditActions.** Add `GL_YEAR_CLOSE = "GL.YEAR.CLOSE"` to `AuditActions` (the action string both close and reopen emit; the reopen distinguishes via the `detail("action","reopen")` payload). Mirrors the existing GL action constants.

### D-12 — Scope, permission, audit, and the close↔reporting consistency (NO Reporting change — with the proof)

- **Permission:** both operations gate on **`GL.YEAR.CLOSE`** (`@perm.scoped(#uid,'fiscalyear','GL.YEAR.CLOSE')`). Distinct from `GL.PERIOD.CLOSE` (FR-CLOSE-08). `GL.VIEW` reads the close status + (via Reporting's account-ledger drill, unchanged) the closing journal.
- **Scope:** `assertCanActIn(RequestContext.get(), year.companyId)` first on both operations and on the `FiscalYearDto` read paths (NFR-CLOSE-01, BR-CLOSE-14). The operation runs under the operator's company context — human-triggered, not a SYSTEM auto-post (the closing/reversing journals are system-*constructed* but the *operation* is the operator's, FR-CLOSE-08).
- **Audit:** every close + reopen writes `GL.YEAR.CLOSE` to the append-only audit trail with the actor, target year, the posted journal uid (closing entry on close; reversal on reopen), the net rolled, timestamp, company (NFR-CLOSE-03). The reused `closePeriod`/`reopenPeriod` calls additionally emit their own `GL.PERIOD.CLOSE`/`GL.PERIOD.OPEN` rows — acceptable (a full audit of the sub-steps).
- **Close↔reporting consistency — NO Reporting change, with the proof (BR-CLOSE-12).** Confirmed against ADR-0018 D-6:
  1. After a close, the closed year's INCOME/EXPENSE accounts net to **zero** over the year: the original trading lines + the closing entry's reversing lines (the closing entry is dated `end_date`, inside the year) sum to zero per P&L account.
  2. Reporting's Balance-Sheet equity fold is **inception-to-date** `Σ INCOME − Σ EXPENSE` over all `posting_date <= asAtDate` (ADR-0018 D-6). After the close, that fold over the closed year contributes **zero** (the closed year's P&L lines net to zero), so the fold naturally carries only the **still-open** year's P&L — and the closed year's net now sits in the **posted 3900 balance** the equity section already reads. The Balance Sheet keeps balancing with **no double-count** (the profit is counted once: posted 3900, not also un-closed P&L movement).
  3. The Cash-Flow builder (ADR-0018 D-7) already anticipates the close: it uses the posted 3xxx period change for FINANCING and excludes the close entry (the close's `posting_date` is the year-end; a CF for the closed year either excludes the close by source-type/window, or the close nets to zero on the P&L it folds into net income — either way the tie-out holds). ADR-0018 D-6/D-7's forward-compatibility notes were written **for exactly this close** — so **Reporting needs no change**, and ADR-0019 must not touch it. (OQ-CLOSE-04: a P&L *for the closed year* reads INCOME − EXPENSE movement and the closing entry — also in-range — nets the P&L accounts to zero by `end_date`; the net reported for the year equals the net rolled to 3900. Reporting reads movement, not closing-adjusted balances, so the closed year's P&L still reports its result. No Reporting change; a presentation confirmation only.)

### D-13 — ArchUnit / module boundary: the operation stays within `gl`; `gl.service → gl.repository` only; no `gl → reporting`

- The `YearEndCloseService` lives in `com.erp.modules.gl.service` and depends only on `gl` collaborators + `platform` (`ScopeGuard`, `AuditService`, `RequestContext`) + `iam` (`CompanyRepository`, the shipped `GLPostingServiceImpl`/`FiscalCalendarServiceImpl` precedent). It does **not** import `com.erp.modules.reporting..` (no cycle — `reporting` is the leaf reader of `gl`, ADR-0018 D-12). The new P&L-balance read (D-3) is added to **`gl`'s own** `JournalLineRepository`, precisely so the close has no reason to reach into `reporting`.
- The shipped `ModuleBoundaryTest` rules hold unchanged: the new `YearEndCloseController` (in `com.erp.api`) touches only `YearEndCloseService` (controllers-do-not-access-repositories); `gl.service` may use `gl.repository` (intra-module, allowed). **Add (recommended)** a one-line ArchUnit assertion: `noClasses().that().resideInAPackage("com.erp.modules.gl..").should().dependOnClassesThat().resideInAPackage("com.erp.modules.reporting..")` (the close must not introduce a `gl → reporting` edge) — the mirror of ADR-0018 D-12's `reporting`-side rule.

## Consequences

**Positive**
- **Drives the proven primitives — minimal new surface.** No new module, no new table, no new posting path, no re-implemented reversal. The close *constructs a `JournalEntryDraft` and calls the shipped `post(...)`*; the reopen *calls the shipped `postReversal(...)`*; the period auto-close/reopen *reuses `closePeriod`/`reopenPeriod`*. The only genuinely new code is the orchestration service, one repository query (D-3), three `fiscal_years` columns, two enum values, and the V16 widens/seeds.
- **The period-gate ordering is resolved cleanly (D-5).** Post-then-close needs no weakening of the period gate that guards every other posting path — the closing entry posts into the still-OPEN year, then the year locks. The reopen mirrors it (reopen-then-reverse).
- **Append-only throughout (BR-CLOSE-08).** The reopen is a NEW reversing entry (`postReversal` → `reversalOfId`), never an edit/delete; the original closing entry, its reversal, and any re-close all stand on the books. The reverse-then-adjust-then-re-close flow falls out for free.
- **Close↔reporting consistency is automatic and proven (D-12).** The inception-to-date fold (ADR-0018 D-6) was built for this; the closed year's P&L nets to zero, the net sits in posted 3900, the BS keeps balancing with no double-count — **Reporting needs no change.**
- **#12-safe and additive.** The one uid-bearing seed (the `RETAINED_EARNINGS` config row) uses the 21-char `md5`-suffix pattern, idempotent on keep-data re-deploys; V1–V15 are untouched.
- **Forward-compatible (NFR-CLOSE-07).** The `closing_journal_uid` + the source-type discriminator are the seam for a future Statement of Changes in Equity, multi-year batch close, partial-year close, and a dedicated reversal source type — none precluded.

**Negative / costs**
- **`fiscal_years` gains three columns + the `FiscalYearDto` gains three fields.** Additive; the DTO change is backward-tolerant (existing readers ignore extra record fields). The entity gains setters (the year's status/close-stamps are mutated — the only mutable GL master state beyond `chart_of_accounts`).
- **`closing_journal_uid` is cleared on reopen (D-6 step 5).** The "which journal was the close" history then lives in the audit trail + the reversal's `reversalOfId`, not on the year row. Accepted (a clean "is this year effectively closed?" read; the durable record is the journals). Alternative (keep it, add a `reopened_at`) examined below.
- **No `net_rolled` column (D-7).** The figure is read from the closing journal on drill / from the audit row, not as an indexed column. Accepted; additive later.
- **A privileged human can reopen a closed year** (sensitive). Mitigated: `GL.YEAR.CLOSE` is a distinct, separately-grantable perm; every reopen is audited with the reversal uid; only the most-recently-closed year is reopenable (BR-CLOSE-10).

## Alternatives considered

- **A dedicated `YearEndCloseService` vs extending `FiscalCalendarService` — DECIDED: dedicated (D-1).** Folding `closeFiscalYear`/`reopenFiscalYear` into `FiscalCalendarService` keeps one fewer class but conflates period/year CRUD with a four-collaborator posting orchestration; the dedicated service is the cohesive home (the ADR-0018 D-1 reasoning). Reversible either way (both live in `gl.service`).
- **Direct-to-3900 vs an Income-Summary intermediate account — DECIDED: direct (owner ratification, §10.1).** The Income-Summary two-hop method (P&L → Income Summary → 3900) is the same arithmetic with an extra posted hop and an extra seeded account; the direct method is simpler and ratified. Recorded for the trail; not built.
- **Post-then-close vs a system-bypass posting path — DECIDED: post-then-close (D-5).** A bypass (let the close post into a just-closed period via a privileged flag) avoids thinking about order but adds a privileged path into the append-only engine that could, if mis-used, post into a genuinely closed period. Post-first achieves the identical end state with no engine change. Post-then-close wins on safety + simplicity.
- **Reopen-reversal via `postReversal`/`reversalOfId` vs a fresh hand-built inverse entry — DECIDED: `postReversal` (D-6).** Hand-building the inverse re-derives the swap logic the shipped `postReversal` already does correctly (swap debit↔credit per line, set `reversalOfId`, post through the validating engine). Reusing it is the boring, tested path; a hand-built inverse risks a sign bug and loses the `reversalOfId` link.
- **A distinct `YEAR_END_CLOSE_REVERSAL` source type vs reusing `YEAR_END_CLOSE` + `reversalOfId` — DECIDED: reuse (D-10).** The brief offered the distinct value (the `SALES_REVERSAL` precedent). We deviate: `reversalOfId IS NOT NULL` already discriminates the reversal unambiguously, so a dedicated value adds an enum + CHECK token for no new information. Additive later if a report needs the bare token.
- **Clearing `closing_journal_uid` on reopen vs keeping it (+ a `reopened_at`) — DECIDED: clear it (D-6).** Keeping the uid + adding a `reopened_at` makes the year-row a fuller mini-history but complicates the "is this year closed?" read and the re-close stamp (which uid is current?). Clearing it keeps the row a clean current-state record; the journals + audit are the durable history. Reversible (re-adding a kept-history column is additive).
- **The P&L-balance read on `gl`'s `JournalLineRepository` vs reusing `reporting`'s `AccountMovementQuery` — DECIDED: `gl`-owned (D-3).** Reusing `reporting`'s query would create a `gl → reporting` dependency (a module cycle — `reporting` already reads `gl`). The windowed aggregate belongs on `gl`'s own repository (it already owns the trial-balance family). One small JPQL method, no cycle.
- **Storing the close result on `fiscal_years` vs a separate `fiscal_year_closures` table — DECIDED: columns on `fiscal_years` (D-7).** A separate table would model a full close/reopen history (one row per close cycle). v1 closes/reopens are append-only on the *journals*; the year-row needs only current state (who/when/which-journal). Three nullable columns suffice; a history table is additive later if multi-cycle reporting wants it. Mirrors `vat_returns.posted_journal_uid` (a scalar uid on the master, not a side table).

## Open items (recommended defaults stand unless the owner overrides)

- **OQ-CLOSE-01 — Prior-year-closed guard.** Default **YES** (D-8, BR-CLOSE-04): a year closes only if the immediately prior year is CLOSED (or none exists). *Decider:* owner (finance). Relaxing it (out-of-sequence close) is additive policy, not recommended.
- **OQ-CLOSE-02 — Reopen only the latest closed year.** Default **YES** (D-8, BR-CLOSE-10). *Decider:* owner (finance). Reopening an arbitrary older year is deferred/discouraged.
- **OQ-CLOSE-03 — Closing-entry posting vs period-gate ordering.** **DECIDED here (D-5): post the closing entry first (into the still-OPEN period 12), then auto-close the periods, then mark the year CLOSED — no gate bypass.** Reopen mirrors it (reopen periods, then post the reversal). *Decider:* architect (this ADR).
- **OQ-CLOSE-04 — How a P&L *for the closed year* reads after the close.** Default (D-12): a P&L over the year's date range reads INCOME − EXPENSE movement; the closing entry (in-range) nets the P&L accounts to zero by `end_date`; the net reported for the year equals the net rolled to 3900. Reporting reads movement, not closing-adjusted balances, so it still reports the year's result. *Decider:* architect — confirms the date-window treatment; either including or excluding the `YEAR_END_CLOSE` lines for a "trading P&L" view is consistent. No Reporting change; presentation confirmation only.
- **OQ-CLOSE-05 — Accounts opened mid-year.** Default (D-3/D-4): the close zeroes **every P&L account with a non-zero year balance**; a zero-movement (or never-touched) account contributes no line. Balance-driven, no special-casing. *Decider:* owner/architect.
- **OQ-CUR-03 (carried) — Rounding mode & TZS decimals.** Half-up, TZS = 0 dp (ADR-0005 / NFR-CLOSE-02). The closing-entry amounts, the balance check, and the net rolled use `BigDecimal` and must round identically to the GL figures they zero (exact `compareTo`, no tolerance). *Confirm before go-live;* does not block the model.

---

## Summary

ADR-0019 designs **Year-End Close as a GL-depth operation** — a new **`YearEndCloseService(+Impl)` in `com.erp.modules.gl.service`** (NOT a new module) with two operations and a flat **`YearEndCloseController`** gated on a new **`GL.YEAR.CLOSE`** permission:

- **`closeFiscalYear(uid)`** (one atomic TX): guard (prior year CLOSED-or-none, year OPEN, year has periods) → compute each INCOME/EXPENSE account's net movement over `[start_date, end_date]` via **one new windowed `JournalLineRepository.periodMovementByAccount`** (selecting P&L accounts by `account_type`, not code) → build **ONE balanced closing `JournalEntryDraft`** dated `end_date`, `sourceType=YEAR_END_CLOSE`, `sourceRef=year.uid`: one zeroing `LineDraft` per non-zero P&L account (the negation of its net-debit movement — credit a net-debit account, debit a net-credit account) plus the **balancing 3900 line** (CR net profit / DR net loss, 3900 resolved via the new **`RETAINED_EARNINGS`** config key) → **`GLPostingService.post(...)` FIRST while period 12 is still OPEN**, then auto-close every period (reuse `closePeriod`), then mark `fiscal_years.status=CLOSED` and stamp `closed_at`/`closed_by`/`closing_journal_uid` → audit.
- **`reopenFiscalYear(uid)`** (one atomic TX, append-only): guard (year CLOSED, it is the most-recently-closed) → **reopen the periods FIRST** (reuse `reopenPeriod`) → **`GLPostingService.postReversal(closing_journal_uid, end_date, YEAR_END_CLOSE, year.uid, actor)`** (the shipped reversal — swaps debit↔credit, sets `reversalOfId`, restores P&L + backs out 3900) → flip `status=OPEN`, clear the close stamps → audit. Reverse-then-adjust-then-re-close falls out for free.

The **period-gate ordering is decided (D-5/OQ-CLOSE-03): post-then-close on close, reopen-then-reverse on reopen — no change to `GLPostingService`, no gate bypass.** The reversal **reuses the `YEAR_END_CLOSE` source type + `reversalOfId`** (no separate `YEAR_END_CLOSE_REVERSAL` value, D-10). The roll account is **`RETAINED_EARNINGS` → 3900**, both seeded for existing companies in V16 and added to `GlConfigServiceImpl.DEFAULT_MAPPINGS` for new companies (3900 confirmed in the V10 CoA seed).

**`V16__year_end_close.sql` — additive only, V1–V15 FROZEN:** three `fiscal_years` columns (`closed_at`, `closed_by`, `closing_journal_uid` + the `closed_by` FK); the `gl_configs` CHECK widen to admit `RETAINED_EARNINGS`; the **#12-safe** `RETAINED_EARNINGS`→3900 seed per existing company (`'YEC'||lpad(company_id,6,'0')||substr(md5('RETAINED_EARNINGS'),1,12)` = 21 chars, never `||config_key`, `ON CONFLICT DO NOTHING`); the journal source-type CHECK widen (both `chk_journal_batch_source_type` and `chk_journal_entry_source_type`) to admit `YEAR_END_CLOSE`; the `GL.YEAR.CLOSE` permission + `ORG_ADMIN` grant. The enums gain `GlConfigKey.RETAINED_EARNINGS` + `JournalSourceType.YEAR_END_CLOSE`; `AuditActions` gains `GL_YEAR_CLOSE`; `ScopeGuard` gains `case "fiscalyear"` (the repo finder already exists); `FiscalYearDto` gains the three close fields.

**Ready for build.** The service home, the two algorithms (step-by-step), the `LineDraft` loop with the exact per-account zeroing rule and the 3900 net-profit/loss direction, the period-gate ordering (and the reopen mirror), the reuse of `postReversal`, the windowed P&L-balance query (verified field names), the `RETAINED_EARNINGS` key + the service-seeder change, the `YEAR_END_CLOSE` source type + the reversal-via-`reversalOfId` representation, the V16 statements, the guards, scope/perm/audit, and the ArchUnit boundary are all concrete — the engineer writes the operations + the closing-entry construction + `V16__year_end_close.sql` + the reopen without guessing a business rule.

**Additive / #12-safe / no Reporting change — confirmed.** V16 is additive on the frozen V1–V15; the one uid-bearing seed uses the 21-char `md5`-suffix pattern (finding #12 handled); the **period-gate ordering is post-then-close / reopen-then-reverse (D-5)**; and **Reporting requires no change** — the closed year's P&L nets to zero (incl. the closing entry), the rolled net sits in posted 3900, and ADR-0018 D-6's inception-to-date equity fold (built for exactly this close) keeps the Balance Sheet balancing with no double-count (D-12, proven).
