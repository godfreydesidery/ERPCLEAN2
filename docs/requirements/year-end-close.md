# Requirements — Year-End Close (freeze the year, reset P&L to zero, roll profit into retained earnings)

> Status: **RATIFIED (owner-confirmed 2026-06-10).** The owner ratified all six Year-End Close forks —
> **(1)** the closing entry **zeroes each P&L account direct to Retained Earnings** (DR every INCOME account
> by its year balance, CR every EXPENSE account by its year balance, with the net profit/loss to **3900
> Retained Earnings**; CR 3900 for a net profit, DR 3900 for a net loss) — **no Income Summary intermediate
> account**, one balanced closing journal dated at the fiscal year's `end_date`, adding a **RETAINED_EARNINGS**
> `gl_config` key → 3900; **(2)** **reopen is allowed and auto-reversing** — reopening a CLOSED fiscal year
> posts a **reversal of the closing journal** (restoring the P&L balances + backing out the retained-earnings
> roll), flips the year (and its periods) back to OPEN, and the year becomes re-closeable (append-only — the
> reopen is a NEW reversing entry, never an edit/delete of the closing journal); **(3)** **auto-close remaining
> periods at year-close** — closing the fiscal year first CLOSES any still-OPEN periods within it, then posts
> the closing entry, then marks the year CLOSED, in **one** end-to-end action (reopen flips the periods back
> to OPEN); **(4)** **ordering guards** — a year can be closed only if the immediately prior fiscal year is
> already CLOSED, a year already CLOSED cannot be closed again, a year with no periods cannot be closed, and
> **only the most-recently-closed year may be reopened**; **(5)** a distinct **GL.YEAR.CLOSE** permission
> covers BOTH closing and reopening (sensitive — separate from GL.PERIOD.CLOSE); **(6)** **base currency,
> append-only, double-entry** — all GL invariants hold. Each is reflected below as a fixed v1 requirement;
> everything not chosen has moved to the **Deferred** list (§2). **No ADR-0019-blocking open question remains**
> (the closing-entry-vs-period-gate posting ordering and the mid-year-opened-account handling are ADR
> *decisions*, not requirements blockers — flagged for the architect).
>
> Author: system-analyst · Domain: `gl` (financial / posting — a GL-depth operation, **inside** the existing
> module). Business-level spec only. **No schema, no API shapes, no tables/columns, no code** — those are the
> solutions-architect's, in **ADR-0019** (next step). Do not infer a data model from this document.
>
> **This is Year-End Close — the LAST Tier-1 finance piece (docs/PATH-TO-FULL-ERP.md Phase A "year-end close
> automation + closed-period policy"; docs/ROADMAP.md GL depth).** The rest of Tier-1 finance is DONE — GL
> (ADR-0013/V10), AR (ADR-0014/V11), AP (ADR-0015/V12), Cash & Bank (ADR-0016/V13), VAT return + WHT
> (ADR-0017/V14), and Financial Reporting (ADR-0018/V15) all ship. What is missing is the **year-end CLOSE
> operation**: the one that posts the closing entry rolling P&L → Retained Earnings, freezes the year, and
> resets the P&L for the new year. It is a **GL-depth slice**, not a new module — it adds an operation, one
> `gl_config` key, and one source type to the **existing `com.erp.modules.gl`**.
>
> **Depends on:** **GL** (the books — ADR-0013 / V10; the engine this slice drives): the shipped
> **`PeriodStatus` enum {OPEN, CLOSED}**, **`fiscal_years.status` + `fiscal_periods.status` + `closed_at` /
> `closed_by`**, the **`FiscalCalendarService`** (`openFiscalYear` / `closePeriod(uid)` / `reopenPeriod(uid)`
> / `seedCurrentYear`), the **period status posting gate** (a CLOSED period rejects all new postings; the
> `FiscalPeriodResolver` resolves an OPEN period for the posting date — BR-GL-03), and the **synchronous
> `GLPostingService.post(JournalEntryDraft)`** double-entry engine (validates balance, OPEN period, active
> accounts — the AR/AP/Cash/VAT precedent). The chart of accounts ships **3900 Retained Earnings** (V10) and
> **3100 Opening Balance Equity**; `gl_configs` has SALES_REVENUE / VAT_PAYABLE / … / OPENING_BALANCE_EQUITY
> but **NO RETAINED_EARNINGS key**, and `JournalSourceType` has MANUAL / SALES / AR_* / AP_* / CASH_* /
> VAT_RETURN but **NO closing source type**. **Money** (ADR-0005 — base currency only in v1). **Financial
> Reporting** (ADR-0018 / V15 — the close↔reporting consistency guarantee, §6, no Reporting change).
> **RBAC / `ScopeGuard` / audit** (the platform spine); per-company scope; `assertCanActIn` on every read
> path; append-only / reversing entries — **NEVER edit or delete a posted journal** (BR-GL-02). All shipped.
> **The central operation:** close a fiscal year = auto-close its periods → post one balanced closing journal
> (P&L → 3900) → mark the year CLOSED; reopen = post the reversal of that closing journal → flip the year (and
> periods) back to OPEN → re-closeable. One `RETAINED_EARNINGS` key + one `YEAR_END_CLOSE` source type widen
> the existing GL additively (the small **V16** migration). Reporting needs **no change**.

## 1. Business context & why now

The books are **continuously open**: GL has posted every financial effect since inception (sales revenue +
VAT, AR receipts, AP payments, Cash & Bank movements, the VAT settlement), and Reporting already reads a
**Profit & Loss**, a **Balance Sheet**, and a **Cash-Flow Statement** from those `journal_lines`. But the
INCOME and EXPENSE accounts have **never been zeroed** — every year's profit is still sitting un-closed on the
P&L accounts. Reporting copes with this **at read time**: its Balance-Sheet equity fold is an **inception-to-
date net INCOME − EXPENSE** presentation derivation, so a mid-year (or year 2+) Balance Sheet still balances
without any closing entry (reporting.md BR-REP-05, ADR-0018 D-6). That is correct for *reporting*, but it is
not the **books**. The standard annual accounting cycle requires that, at the end of a fiscal year, the year's
profit be **posted** out of the P&L accounts and **into retained earnings**, leaving every INCOME and EXPENSE
account at **zero** to start the new year fresh, and **freezing** the closed year's figures.

**Year-End Close closes that gap.** It is a **GL-depth operation** (not a new module) that, **per company per
fiscal year**:

- **Auto-closes** any still-OPEN periods within the year (so the whole year is locked in one action);
- **Posts one balanced closing journal** dated at the fiscal year's `end_date`: **DEBIT each INCOME account**
  by its year balance and **CREDIT each EXPENSE account** by its year balance (bringing every P&L account to
  **zero**), with the **net profit or loss rolled to 3900 Retained Earnings** — **CR 3900** for a net profit,
  **DR 3900** for a net loss — balanced double-entry the `GLPostingService` enforces;
- **Marks the fiscal year CLOSED** — its figures frozen, all further posting into it blocked (the existing
  period-status gate, BR-GL-03);
- and, for late adjustments, supports a **REOPEN**: a permissioned, audited operation that posts the **reversal
  of the closing journal** (restoring the P&L balances and backing out the retained-earnings roll), flips the
  year and its periods **back to OPEN**, and makes the year **re-closeable** — append-only throughout (the
  reopen is a NEW reversing entry, never an edit or delete of the closing journal, BR-GL-02).

What it needs as inputs **already exists**: the `GLPostingService` posts a balanced journal synchronously (the
AR/AP/Cash/VAT precedent); the `FiscalCalendarService` already opens years and closes / reopens periods; the
period-status gate already rejects posting into a CLOSED period; **3900 Retained Earnings** is seeded (V10).
What is **missing** is the close operation itself, plus a **RETAINED_EARNINGS** `gl_config` key → 3900 (so the
closing entry resolves the roll account from config, not a hard-coded code — the BR-GL-10 discipline) and a
**YEAR_END_CLOSE** source type (so the closing / reversing journals are identifiable as system close entries).
Both widen the existing GL **additively** (the small V16 migration).

### The close↔reporting consistency guarantee (read this before anything else)

The non-obvious interaction is between the **closing entry** and the **Reporting Balance-Sheet equity fold**,
and it is why both stay correct with **no Reporting change**:

- The closing entry **zeroes the P&L accounts** — it posts reversing INCOME / EXPENSE lines that bring every
  INCOME and EXPENSE account's year balance to zero, and rolls the net to posted **3900**.
- The Reporting equity fold is **inception-to-date** net INCOME − EXPENSE over the **actual journal lines**
  (ADR-0018 D-6). After a close, the closed year's P&L lines **net to zero** (the original postings plus the
  closing entry's reversing lines), so the inception-to-date fold naturally covers **only the still-open
  year's P&L** — and the rolled earnings now sit in the **posted 3900 balance** that the equity section already
  reads. The Balance Sheet therefore keeps balancing with **no double-count** (the profit is counted once: as
  posted 3900, not also as un-closed P&L movement), and **Reporting needs no change**.
- This is exactly **why** the fold was built inception-to-date (ADR-0018 D-6's forward-compatibility note):
  before a close, the fold carries the un-closed P&L; after a close, the same fold formula carries only the
  post-close P&L plus the now-posted 3900 — the same balancing identity, with or without a close. **State this
  as a business rule / assumption (BR-CLOSE-12); do NOT redesign Reporting.**

> **Flag for the architect (ADR-0019):** the load-bearing decisions are all **operation / ordering design**,
> not business behaviour. ADR-0019 must (1) place the operation **inside `com.erp.modules.gl`** (a
> `YearEndCloseService` driving `closeFiscalYear` / `reopenFiscalYear`, reusing `GLPostingService` +
> `FiscalCalendarService` — no new module); (2) design the **closing-entry construction** (read each P&L
> account's year balance, build the DR-INCOME / CR-EXPENSE lines + the 3900 roll line, balanced by
> construction); (3) add the **RETAINED_EARNINGS** `gl_config` key → 3900 and the **YEAR_END_CLOSE** (and the
> reversal — `YEAR_END_CLOSE_REVERSAL` *or* a reversal-of the closing entry) source type, widening the
> existing CHECK / enum additively; (4) resolve the **closing-entry-posting-vs-period-gate ordering** — the
> closing entry is dated at `end_date` (within the year being closed), so it must be posted **before** the
> periods are flipped to CLOSED, **or** on a system path that bypasses the just-closed gate (OQ-CLOSE-03 — an
> ADR detail); (5) the **auto-close-periods** sub-step (close all still-OPEN periods in the year, reusing
> `closePeriod`) and the **reopen** reversal (reuse the period-reopen + post the reversing journal); (6) the
> **ordering guards** (prior-year-closed, no-double-close, no-empty-year, reopen-only-latest); (7) the
> **GL.YEAR.CLOSE** permission + the **V16** migration (RETAINED_EARNINGS seed → 3900 + GL.YEAR.CLOSE perm +
> the YEAR_END_CLOSE source-type CHECK widen); (8) audit on close + reopen (with the posted journal uid);
> (9) an **ArchUnit** assertion that the operation **stays within `gl`** (it reads / posts GL only; Reporting
> is untouched). State these; do not design the tables here. **None blocks the requirements** — the closing
> behaviour and the guards are fixed; the ordering / construction mechanics are the ADR's.

### Vocabulary (read this first)

- **Fiscal year close (year-end close)** — the **annual** operation that **freezes** a fiscal year: it
  auto-closes the year's still-OPEN periods, posts the **closing entry** (P&L → retained earnings), and marks
  the **fiscal year CLOSED** (`fiscal_years.status = CLOSED`). One end-to-end action, per company per year.
  After it, all posting into the year is blocked (its periods are CLOSED).
- **Closing entry (closing journal)** — the **one balanced journal** the close posts, dated at the fiscal
  year's **`end_date`**: **DEBIT each INCOME account** by its year balance, **CREDIT each EXPENSE account** by
  its year balance (zeroing every P&L account), with the **net profit/loss to 3900 Retained Earnings** (CR 3900
  for a profit, DR 3900 for a loss). Σ debits == Σ credits (`GLPostingService` enforces). Source type
  **YEAR_END_CLOSE**. **No Income Summary intermediate account** — the roll is **direct to 3900**.
- **Retained earnings roll** — the act of moving the year's **net profit or loss** into **3900 Retained
  Earnings** as the balancing figure of the closing entry. Sequential year-on-year (the prior year must be
  closed first, BR-CLOSE-04), so 3900 accumulates each closed year's net.
- **Net profit / loss for the year** — the year's **INCOME − EXPENSE** (the P&L net over the fiscal year). The
  balancing figure rolled to 3900: a **profit** (INCOME > EXPENSE) **credits** 3900; a **loss** (EXPENSE >
  INCOME) **debits** 3900.
- **P&L reset (zeroing)** — the effect of the closing entry on the **INCOME (4xxx) and EXPENSE (5xxx)**
  accounts: each is brought to a **zero** year-end balance, so the new fiscal year starts the P&L fresh. (The
  account ledger shows the closing line that takes it to zero — drill-down via Reporting, BR-CLOSE-13.)
- **Reopen (close reversal)** — the operation that **un-freezes** a CLOSED fiscal year for a late adjustment:
  it posts the **reversal of the closing journal** (restoring the P&L balances + backing out the 3900 roll),
  flips the **fiscal year and its periods back to OPEN**, and makes the year **re-closeable**. Append-only —
  a **new reversing entry** (source type **YEAR_END_CLOSE_REVERSAL** or a reversal-of the closing entry),
  **never** an edit/delete of the closing journal (BR-GL-02). Permission-gated (sensitive), audited.
- **Closed-period posting gate** — the **existing** GL rule (BR-GL-03): a CLOSED fiscal period rejects all new
  postings; the `FiscalPeriodResolver` resolves an OPEN period for a posting date. Year-end close **uses** this
  gate (after close, the year's periods are CLOSED → posting blocked); the closing / reversing entries
  themselves are **system-posted as part of the operation**, dated at year-end (OQ-CLOSE-03 — the ordering is
  the ADR's).
- **Reverse-then-adjust-then-re-close** — the **late-adjustment flow**: a CLOSED year is **reopened** (reversal
  posts, P&L restored, year OPEN), the accountant **posts the adjusting journal(s)** into the now-open year,
  then **re-closes** the year (a fresh closing entry posts the corrected net to 3900). The append-only way to
  correct a closed year — no closing journal is ever edited.
- **Fiscal year / fiscal period** — *(carried from gl.md)* the 12-period accounting year of a company and its
  monthly periods, each **OPEN** or **CLOSED**; the year-start month is configurable per company. Year-end
  close operates on the **fiscal year** and its periods.

> **Word discipline (carried into the glossary):** a **period close** (`GL.PERIOD.CLOSE`, the existing monthly
> open/close of one `fiscal_period`) is **not** a **year close** (`GL.YEAR.CLOSE`, this slice — the annual
> freeze + closing entry + retained-earnings roll). A **closing entry** (this slice's P&L → 3900 journal) is
> **not** an **opening-balance journal** (gl.md FR-GL-13, a manual capital/equity entry) and **not** the
> Reporting **current-year-earnings fold** (a read-time presentation derivation, NOT a posted entry —
> reporting.md BR-REP-05). A **reopen** (this slice — re-open a closed *year*, posting the closing reversal)
> is **not** a period **reopen** (gl.md FR-GL-15 — re-open one closed *period*, posting nothing). The
> retained-earnings roll posts to **3900 Retained Earnings** (EQUITY); it is **not** **3100 Opening Balance
> Equity** (the opening-capital account) and **not** an "Income Summary" account (v1 uses **none** — the roll
> is direct to 3900). A close **freezes** a year; a reopen **un-freezes** it — neither ever **deletes** a
> posted journal (BR-GL-02).

## 2. Scope

> Every line below is **ratified v1** (owner-confirmed 2026-06-10). This is **Year-End Close — a GL-depth
> slice (Phase A, the last Tier-1 finance item)**: close a fiscal year (auto-close periods + post the closing
> entry P&L → 3900 + mark CLOSED), reopen a closed year (reverse + OPEN), the ordering guards, the
> RETAINED_EARNINGS config key + the YEAR_END_CLOSE source type, the GL.YEAR.CLOSE permission, and the
> close↔reporting consistency guarantee. It **drives** the existing GL engine; it adds **no new module**, **no
> Income Summary account**, and **no Reporting change**.

### In scope (v1 — "close a fiscal year to a posted closing entry, reset the P&L, roll profit to retained earnings, and reopen for late adjustments")

- **Close a fiscal year (one end-to-end operation), per company.** A user with `GL.YEAR.CLOSE` closes an OPEN
  fiscal year: the system **(1)** auto-CLOSES any still-OPEN periods within the year (reusing the period close,
  one operation — BR-CLOSE-06), **(2)** posts **one balanced closing journal** dated at the year's `end_date`
  (DR each INCOME account by its year balance, CR each EXPENSE account by its year balance, net profit/loss to
  **3900** — BR-CLOSE-01/02/03), and **(3)** marks the **fiscal year CLOSED**. After close, **all** posting
  into the year is blocked (its periods are CLOSED — the existing gate, BR-CLOSE-09).
- **The closing entry zeroes every P&L account, rolls the net to 3900 (no Income Summary).** DEBIT each INCOME
  (4xxx) account by its year balance, CREDIT each EXPENSE (5xxx) account by its year balance — bringing every
  P&L account to a **zero** year-end balance — with the **net profit/loss to 3900 Retained Earnings** (**CR
  3900** for a net profit, **DR 3900** for a net loss). The journal is **balanced** (Σ debits == Σ credits,
  `GLPostingService` enforces). **No Income Summary intermediate account** — the roll is **direct to 3900**.
  Each P&L account's ledger shows the closing line bringing it to zero (BR-CLOSE-01/02/03/13).
- **RETAINED_EARNINGS `gl_config` key → 3900.** The closing entry resolves the roll account from a new
  **RETAINED_EARNINGS** `gl_config` key (mapped to **3900 Retained Earnings**), the BR-GL-10 "no hard-coded
  account codes" discipline. Seeded in the **V16** migration (FR-CLOSE-07, BR-CLOSE-11).
- **YEAR_END_CLOSE source type (+ the reversal).** The closing journal carries source type **YEAR_END_CLOSE**;
  the reopen's reversal carries **YEAR_END_CLOSE_REVERSAL** (or is recorded as a reversal-of the closing entry
  — the ADR's choice). The `JournalSourceType` CHECK / enum widens additively in V16 (FR-CLOSE-07).
- **Reopen a closed fiscal year (auto-reversing), per company.** A user with `GL.YEAR.CLOSE` reopens a CLOSED
  fiscal year: the system posts the **reversal of the closing journal** (restoring every P&L account's balance
  + backing out the 3900 roll), flips the **fiscal year and its periods back to OPEN**, and makes the year
  **re-closeable**. **Append-only** — the reopen is a **NEW reversing entry**, never an edit/delete of the
  closing journal (BR-CLOSE-07/08, BR-GL-02). The **reverse-then-adjust-then-re-close** flow is the supported
  late-adjustment path (§7.2).
- **Ordering / sequencing guards.** A fiscal year may be closed **only if the immediately prior fiscal year is
  already CLOSED** (the retained-earnings roll is sequential; the prior year's figures are frozen —
  BR-CLOSE-04); a year **already CLOSED** cannot be closed again (BR-CLOSE-05); a year **with no periods**
  cannot be closed (BR-CLOSE-05); **only the most-recently-closed year may be reopened** (an older closed year
  cannot be reopened while a later one is closed — keeps the roll chain sound — BR-CLOSE-10). *(The
  prior-year-closed requirement and the reopen-only-latest rule are recommended defaults, flagged OQ-CLOSE-01/
  OQ-CLOSE-02 — they stand unless the owner overrides.)*
- **View a year's close status + the closing journal.** A user with `GL.VIEW` reads a fiscal year's **close
  status** (OPEN / CLOSED, who closed it + when via `closed_at` / `closed_by`), the **net profit/loss rolled**,
  and the **closing journal** (its `JB-####` batch + lines), drilling each P&L account to its account ledger to
  see the closing line that zeroed it (via Reporting, FR-CLOSE-06, BR-CLOSE-13).
- **Close↔reporting consistency (no Reporting change).** After a close, the Reporting Balance Sheet keeps
  balancing with **no double-count**: the closed year's P&L lines net to zero (incl. the closing entry), the
  inception-to-date equity fold covers only the still-open year's P&L, and the rolled earnings sit in posted
  3900 (BR-CLOSE-12). **Reporting needs no change** (this is why ADR-0018 D-6 built the fold inception-to-date).
- **Base currency, append-only, double-entry — all GL invariants hold.** The closing / reversing journals are
  in the company **base currency** (BR-GL-06), **balanced** (BR-GL-01), posted into the year being closed/
  reopened, and **immutable** once posted (corrected only by the reopen reversal — BR-GL-02). Per-company
  isolation (BR-GL-05).
- **Permissions** — a distinct **`GL.YEAR.CLOSE`** permission covers **both** closing and reopening a fiscal
  year (sensitive — **separate** from `GL.PERIOD.CLOSE`); `GL.VIEW` reads close status + the closing journal.
  Per-company scope; `assertCanActIn` on every read path; **audit** on close + reopen (with the posted journal
  uid). Seeded via the **V16** migration (FR-CLOSE-08, BR-CLOSE-11).

### Deferred (recognised, NOT built in v1 — separate later slices)

- **Income-summary method.** v1 rolls each P&L account **direct to 3900** (no intermediate account). The
  alternative **Income Summary** account method (close P&L to an Income Summary account, then Income Summary to
  retained earnings) is **NOT** built — it is the same arithmetic with an extra hop; the direct method is
  simpler and the ratified choice. *(Deferred / not-planned, OQ-CLOSE not raised — the owner ratified direct.)*
- **Automatic opening-balance carry-forward (a posted new-year opening journal).** v1 **does not need** a
  posted opening-balance carry-forward — the balance-sheet (asset / liability / equity) accounts are
  **continuous** (their inception-to-date balances simply carry into the next year on the books; there is no
  per-year ledger), and the P&L reset is the closing entry. An explicit **posted opening-balance journal** for
  the new year (mirroring the manual opening balances of gl.md FR-GL-13) is **NOT** posted by the close —
  deferred (the continuous ledger makes it unnecessary in v1). *(Forward-compatible; not precluded.)*
- **Partial-year close / interim close.** v1 closes a **full fiscal year** to its `end_date`. A partial-year
  or interim (e.g. quarter-end) close is **deferred** — the full-year close model does not preclude it.
- **Multi-year batch close.** v1 closes **one year per operation** (and requires the prior year closed first —
  BR-CLOSE-04). Closing several years in a single batch action is **deferred** — running the single-year close
  in sequence achieves the same; a batch convenience is additive.
- **Consolidation close / group year-end.** v1 closes **per company**. A **consolidated / group** year-end
  close (intercompany elimination, group reporting currency) is **deferred** (gl.md OQ-CUR-01) — the per-company
  model does not preclude it.
- **Automatic provisions / accruals / adjustments at close.** v1 posts **only** the P&L → 3900 closing entry.
  Automatic year-end **provisions / accruals / deferrals / depreciation catch-up** at close are **NOT** posted
  — the accountant posts any such adjustments as **manual journals before the close** (or reopens, adjusts,
  re-closes). Deferred (ties to Recurring journals / Accruals automation, PATH-TO-FULL-ERP §3.1).
- **Period-level locking beyond what exists.** v1 uses the **existing** period OPEN/CLOSED gate (BR-GL-03).
  Richer locking (e.g. a "soft-close" sub-ledger lock, a period-reopen approval workflow, per-module close
  checklists) is **deferred** — beyond the existing two-state gate.
- **Closing-entry reversal on a non-latest year (reopen an older year while a later one is closed).** v1
  permits reopening **only the most-recently-closed year** (BR-CLOSE-10). Reopening an arbitrary older closed
  year (re-deriving the roll chain) is **deferred** — it would unwind the sequential roll; the latest-only rule
  keeps the chain sound.

### Explicitly NOT this slice

- **A new module.** Year-End Close is a **GL-depth operation** inside `com.erp.modules.gl` — it adds a
  service operation, one `gl_config` key, and one source type. It is **not** a new top-level module.
- **The GL posting engine itself.** **GL** (ADR-0013) owns `GLPostingService`, the chart of accounts, the
  fiscal calendar, and the period gate; Year-End Close **drives** them (posts the closing entry via
  `GLPostingService`, closes periods via `FiscalCalendarService`). It does **not** re-implement posting,
  numbering, or the period model.
- **Financial Reporting.** **Reporting** (ADR-0018) reads the books and **posts nothing**; it **presents**
  current-year net income in equity as a read-time fold (BR-REP-05). Year-End Close **posts** the closing
  entry; the two stay consistent (BR-CLOSE-12) with **no Reporting change**. Reporting does not close anything;
  Year-End Close does not read or change a statement.
- **The sub-ledgers (AR / AP / Cash & Bank / VAT).** Their balances are control-account totals on the books;
  Year-End Close operates on the **GL** (the P&L → 3900 roll). It does **not** close an AR period, an AP run,
  or a VAT return — those are their modules' lifecycles. (A VAT return is filed and locked per its own rule —
  vat-return.md; year-end close does not touch it.)
- **The manual opening-balance journal.** gl.md FR-GL-13 (the accountant's manual opening-balance / capital
  journal) is its own thing; the close does **not** post it and does not replace it.

## 3. The operation: close, the closing entry, reopen, the guards — and the reporting consistency

### 3.1 Close a fiscal year (one end-to-end operation)

A user with `GL.YEAR.CLOSE` closes an **OPEN** fiscal year. The operation is **atomic and ordered**:

1. **Guard** — the immediately **prior** fiscal year is **CLOSED** (BR-CLOSE-04, OQ-CLOSE-01); the year is
   **OPEN** (not already CLOSED — BR-CLOSE-05); the year **has periods** (BR-CLOSE-05). Else **rejected** with
   a clear message; nothing is posted or changed.
2. **Auto-close periods** — any still-**OPEN** `fiscal_periods` within the year are **CLOSED** (reusing
   `FiscalCalendarService.closePeriod`), so the whole year is locked (BR-CLOSE-06). *(The closing entry is
   dated at `end_date`, within the year — the ordering of "post the closing entry vs flip the periods CLOSED"
   is the ADR's: post before the flip, or on a system path that bypasses the just-closed gate — OQ-CLOSE-03.)*
3. **Post the closing entry** — **one balanced journal** dated at the year's `end_date`, source type
   **YEAR_END_CLOSE**, via `GLPostingService`: DR each INCOME account by its year balance, CR each EXPENSE
   account by its year balance, the **net to 3900** (CR 3900 net profit / DR 3900 net loss — §3.2). Balanced by
   construction; `GLPostingService` re-validates (BR-CLOSE-01/02/03).
4. **Mark the fiscal year CLOSED** — `fiscal_years.status = CLOSED`; record who/when. The year's figures are
   **frozen**; all posting into it is blocked (its periods are CLOSED — BR-CLOSE-09).
5. **Audit** — the close (with the posted closing-journal uid + the net rolled) is written to the audit trail
   (NFR-CLOSE-03).

### 3.2 The closing entry (P&L → 3900, direct, no Income Summary)

The closing journal, dated at the fiscal year's **`end_date`**, is built from each P&L account's **year
balance** (the account's net movement over the fiscal year):

- For **each INCOME account** (4xxx, normally a credit balance): post a **DEBIT** equal to its year balance →
  the account nets to **zero**.
- For **each EXPENSE account** (5xxx, normally a debit balance): post a **CREDIT** equal to its year balance →
  the account nets to **zero**.
- The **balancing figure** is the **net profit or loss** for the year (Σ INCOME − Σ EXPENSE), posted to **3900
  Retained Earnings** (resolved via the `RETAINED_EARNINGS` `gl_config` key): a **net profit** (INCOME >
  EXPENSE) **CREDITS** 3900; a **net loss** (EXPENSE > INCOME) **DEBITS** 3900.

The entry is **balanced** (the DR-INCOME total + the DR-3900-if-loss == the CR-EXPENSE total + the
CR-3900-if-profit; Σ debits == Σ credits) and `GLPostingService` enforces it (BR-GL-01). **No Income Summary
intermediate account** — the roll is **direct to 3900**. After it, every INCOME and EXPENSE account carries a
**zero** balance for the start of the new year (the P&L reset), and 3900 carries the year's net (the retained-
earnings roll). Each P&L account's ledger shows the closing line that brought it to zero — drillable via
Reporting's account-ledger (BR-CLOSE-13).

### 3.3 Reopen a closed fiscal year (auto-reversing) + reverse-then-adjust-then-re-close

A user with `GL.YEAR.CLOSE` reopens a **CLOSED** fiscal year for a late adjustment. The operation is
**append-only and ordered**:

1. **Guard** — the year is **CLOSED**, and it is the **most-recently-closed** year (no later closed year exists
   — BR-CLOSE-10, OQ-CLOSE-02). Else **rejected**.
2. **Post the closing reversal** — a **NEW reversing entry** (source type **YEAR_END_CLOSE_REVERSAL** or a
   reversal-of the closing entry) that **negates** the closing journal: it **restores** every P&L account's
   year balance and **backs out** the 3900 roll. Balanced by construction (it swaps the closing entry's debits
   and credits — BR-GL-11). **Never** an edit/delete of the closing journal (BR-CLOSE-07/08, BR-GL-02).
3. **Flip the year (and its periods) back to OPEN** — `fiscal_years.status = OPEN`; the periods that the close
   auto-CLOSED flip back to OPEN (BR-CLOSE-06). The year is now **re-closeable**.
4. **Audit** — the reopen (with the posted reversal-journal uid) is written to the audit trail (NFR-CLOSE-03).

**The reverse-then-adjust-then-re-close flow (§7.2):** reopen the year (reversal posts, P&L restored, year
OPEN) → the accountant posts the **adjusting journal(s)** into the now-open year → **re-close** the year (a
fresh closing entry posts the **corrected** net to 3900). The append-only way to correct a closed year — no
closing journal is ever edited; the original closing entry, its reversal, and the re-close entry all stand on
the books.

### 3.4 The ordering / sequencing guards

- **Prior year must be closed first (BR-CLOSE-04, OQ-CLOSE-01).** A fiscal year may be closed **only if** the
  immediately prior fiscal year is already **CLOSED** — so the retained-earnings roll is **sequential** and the
  prior year's figures are **frozen** before this year rolls. (If no prior fiscal year exists — the first year
  on the books — the guard is satisfied vacuously.)
- **No double close (BR-CLOSE-05).** A fiscal year that is **already CLOSED** cannot be closed again
  (reopen-then-re-close is the path for a correction).
- **No empty-year close (BR-CLOSE-05).** A fiscal year **with no periods** cannot be closed (there is nothing
  to close; the calendar must be seeded first).
- **Reopen only the latest closed year (BR-CLOSE-10, OQ-CLOSE-02).** Only the **most-recently-closed** fiscal
  year may be reopened — an older closed year cannot be reopened while a later one is closed, which keeps the
  sequential roll chain sound. (To correct an older year, the later year(s) would first be reopened in
  reverse-chronological order — a deliberate, audited unwind, not a single-step jump.)

### 3.5 The close↔reporting consistency guarantee (no Reporting change)

After a close, the books and the Reporting statements stay consistent **automatically** (§1, BR-CLOSE-12):

- The closed year's INCOME / EXPENSE accounts net to **zero** (their original postings + the closing entry's
  reversing lines), and the rolled net sits in **posted 3900**.
- Reporting's Balance-Sheet equity fold is **inception-to-date** net INCOME − EXPENSE over the actual journal
  lines (ADR-0018 D-6); after the close it covers **only** the still-open year's P&L (the closed year nets to
  zero), and the closed earnings are already in the posted 3900 the equity section reads.
- So the **Balance Sheet keeps balancing with no double-count** (the profit is counted once), the **P&L for the
  closed year still reports its INCOME − EXPENSE** (the original postings are on the books beside the closing
  entry; the P&L reads movement, not closing-adjusted balances — and the closing entry's date at `end_date`
  means a P&L run *for the closed year* includes both the trading lines and the zeroing close, netting the
  reported period correctly per the date window — an ADR detail to confirm, OQ-CLOSE-04), and **Reporting needs
  no change**.

## 4. Actors / personas

- **Accountant / bookkeeper** — posts the year's **adjusting journals before the close** (accruals,
  provisions, corrections), reviews the year's P&L, and (in many small businesses) **runs the close** itself.
  Reads the close status + the closing journal (`GL.VIEW`). May hold `GL.YEAR.CLOSE` if the deployment lets the
  accountant close.
- **Financial controller / finance manager** — the senior authority who **owns the year-end close**:
  **closes** the fiscal year (`GL.YEAR.CLOSE` — auto-close periods + post the closing entry + mark CLOSED) and
  **reopens** it for a late adjustment (`GL.YEAR.CLOSE` — the sensitive reversal). Confirms the net rolled to
  3900 and that the books reconcile post-close. The persona that holds `GL.YEAR.CLOSE` by default.
- **Owner / General Manager** — reviews the **closed year's** result (the net profit rolled to retained
  earnings) and the new-year fresh P&L; reads close status + statements (`GL.VIEW` / `REPORT.*`). Does not
  typically run the close, but in a one-person finance shop the owner may hold `GL.YEAR.CLOSE`.
- *(No SYSTEM auto-closer — year-end close is a **deliberate, human-initiated** operation (`GL.YEAR.CLOSE`),
  **not** an outbox auto-post or a scheduled run. The closing-entry GL post is **synchronous in-request**
  (the AR/AP/Cash/VAT precedent), posted by the close operation under the operator's company context — the
  closing / reversing entries are system-constructed journals, but the **operation** is human-triggered and
  permission-gated.)*

## 5. Functional requirements

> IDs are `FR-CLOSE-NN`. Each is a crisp, testable, **ratified** statement. "Close a fiscal year" = the
> end-to-end operation (auto-close periods → post the closing entry → mark CLOSED). "The closing entry" = the
> one balanced journal dated at the year's `end_date` (DR INCOME / CR EXPENSE / net to 3900), source type
> YEAR_END_CLOSE, posted via `GLPostingService`. "Reopen" = post the closing reversal → flip the year + periods
> to OPEN. "Year balance" = an account's net movement over the fiscal year.

### Close a fiscal year

- **FR-CLOSE-01** A user with `GL.YEAR.CLOSE` may **close an OPEN fiscal year** in **one end-to-end operation**:
  the system **(a)** auto-CLOSES any still-OPEN periods within the year (reusing the period close —
  BR-CLOSE-06), **(b)** posts the **closing entry** (FR-CLOSE-02), and **(c)** marks the **fiscal year CLOSED**
  (`fiscal_years.status = CLOSED`, recording who/when). After close, **all** posting into the year is blocked
  (its periods are CLOSED — BR-CLOSE-09). The whole operation is **atomic** — a failure at any step leaves the
  year OPEN and nothing posted.
- **FR-CLOSE-02** The close posts **one balanced closing journal** dated at the fiscal year's **`end_date`**,
  source type **YEAR_END_CLOSE**, via `GLPostingService` (the synchronous double-entry engine): **DEBIT each
  INCOME account** by its year balance, **CREDIT each EXPENSE account** by its year balance (bringing every P&L
  account to **zero**), with the **net profit/loss to 3900 Retained Earnings** — **CR 3900** for a net profit,
  **DR 3900** for a net loss. Σ debits == Σ credits (BR-CLOSE-01/02/03, BR-GL-01). **No Income Summary
  intermediate account** — the roll is **direct to 3900** (resolved via the `RETAINED_EARNINGS` `gl_config`
  key, FR-CLOSE-07).
- **FR-CLOSE-03** The close enforces the **ordering guards**: it **rejects** the close if the immediately prior
  fiscal year is **not CLOSED** (BR-CLOSE-04), if the year is **already CLOSED** (BR-CLOSE-05), or if the year
  **has no periods** (BR-CLOSE-05) — with a clear message; nothing is posted or changed.

### Reopen a closed fiscal year

- **FR-CLOSE-04** A user with `GL.YEAR.CLOSE` may **reopen a CLOSED fiscal year**: the system posts the
  **reversal of the closing journal** (a NEW reversing entry, source type **YEAR_END_CLOSE_REVERSAL** or a
  reversal-of the closing entry — restoring every P&L account's year balance + backing out the 3900 roll),
  flips the **fiscal year and its periods back to OPEN**, and makes the year **re-closeable**. **Append-only** —
  the closing journal is **never** edited or deleted (BR-CLOSE-07/08, BR-GL-02). The reopen **rejects** a year
  that is **not the most-recently-closed** (BR-CLOSE-10). Audited with the reversal-journal uid (NFR-CLOSE-03).
- **FR-CLOSE-05** After a reopen, the year supports the **reverse-then-adjust-then-re-close** flow: the
  accountant posts adjusting journal(s) into the now-OPEN year (the normal `GL.POST` path), then **re-closes**
  the year (FR-CLOSE-01) — a fresh closing entry posts the **corrected** net to 3900. The original closing
  entry, its reversal, and the re-close entry all stand on the books (append-only).

### View

- **FR-CLOSE-06** A user with `GL.VIEW` may **read a fiscal year's close status** (OPEN / CLOSED, the
  `closed_at` / `closed_by`), the **net profit/loss rolled** to 3900, and the **closing journal** (its
  `JB-####` batch + lines), and may **drill each P&L account** to its account ledger to see the closing line
  that zeroed it (via Reporting's account-ledger drill-down, BR-CLOSE-13). Scoped to their company; no read
  crosses company scope (BR-CLOSE-14, NFR-CLOSE-01).

### Configuration & permissions

- **FR-CLOSE-07** The system adds a **`RETAINED_EARNINGS` `gl_config` key** (mapped to **3900 Retained
  Earnings**) — the closing entry resolves the roll account from it (no hard-coded code, BR-GL-10) — and a
  **YEAR_END_CLOSE** (and the reversal — `YEAR_END_CLOSE_REVERSAL` or a reversal-of) **`JournalSourceType`**, so
  the closing / reversing journals are identifiable as system close entries. Both widen the existing GL
  **additively** via the **V16** migration (BR-CLOSE-11). If `RETAINED_EARNINGS` is **unmapped** or maps to an
  **inactive** account at close time, the close **fails** rather than mis-posting (BR-GL-10).
- **FR-CLOSE-08** Year-end close + reopen are **gated by a distinct `GL.YEAR.CLOSE` permission** (covering
  **both** close and reopen — sensitive, **separate** from `GL.PERIOD.CLOSE`); `GL.VIEW` reads close status +
  the closing journal. Per-company scope; `assertCanActIn` on every read path; **audit on close + reopen** (with
  the posted journal uid). Exact codes are seeded with the slice (V16; FR-IAM-11). The close operation runs
  under the **operator's** company context (it is human-triggered, not a SYSTEM auto-post).

## 6. Business rules (invariants)

> Ratified. These are the Year-End Close invariants; a violation that leaves the closing entry unbalanced,
> mis-rolls the net, closes out of sequence, edits a posted journal, or breaks the close↔reporting consistency
> is a **finance-grade defect** (a release blocker).

- **BR-CLOSE-01 — The closing entry zeroes every P&L account.** The close posts a journal that brings **every**
  INCOME (4xxx) and EXPENSE (5xxx) account's **year balance** to **zero** (DR each INCOME by its balance, CR
  each EXPENSE by its balance) — the P&L reset (FR-CLOSE-02). After the close, no P&L account carries a non-zero
  **year-end** balance.
- **BR-CLOSE-02 — The net profit/loss rolls to 3900 Retained Earnings, direct (no Income Summary).** The
  balancing figure of the closing entry — the year's **net profit/loss** (Σ INCOME − Σ EXPENSE) — posts to
  **3900 Retained Earnings** (resolved via `RETAINED_EARNINGS`): **CR 3900** for a net profit, **DR 3900** for a
  net loss (FR-CLOSE-02). **No Income Summary intermediate account.** The Σ rolled to 3900 **==** the year's net
  profit/loss.
- **BR-CLOSE-03 — The closing entry is balanced.** The closing journal obeys BR-GL-01: Σ debits == Σ credits
  (the DR-INCOME total + DR-3900-if-loss == the CR-EXPENSE total + CR-3900-if-profit). `GLPostingService`
  re-validates; an unbalanced closing entry is **rejected** (a defect — the construction must balance).
- **BR-CLOSE-04 — Prior year must be closed first.** A fiscal year may be closed **only if** the immediately
  **prior** fiscal year is already **CLOSED** (FR-CLOSE-03) — so the retained-earnings roll is **sequential**
  and the prior year's figures are **frozen**. (No prior fiscal year → satisfied vacuously.) *(Recommended
  default, OQ-CLOSE-01.)*
- **BR-CLOSE-05 — Cannot close twice; cannot close an empty year.** A fiscal year that is **already CLOSED**
  cannot be closed again, and a year **with no periods** cannot be closed (FR-CLOSE-03). Both are **rejected**.
- **BR-CLOSE-06 — Close auto-closes the year's periods; reopen flips them back.** Closing the fiscal year
  first **CLOSES** any still-OPEN `fiscal_periods` within it (reusing `FiscalCalendarService.closePeriod`), in
  the one operation (FR-CLOSE-01). **Reopening** flips those periods (and the year) **back to OPEN**
  (FR-CLOSE-04). The whole year's posting gate follows the year's state.
- **BR-CLOSE-07 — Reopen reverses the closing journal (auto-reversing).** Reopening a CLOSED year posts the
  **reversal** of the closing journal — restoring every P&L account's balance + backing out the 3900 roll
  (FR-CLOSE-04). Balanced by construction (it swaps the closing entry's debits and credits — BR-GL-11). The
  year becomes re-closeable.
- **BR-CLOSE-08 — Append-only: the closing journal is never edited or deleted.** A posted closing entry is
  **immutable** (BR-GL-02); the reopen is a **NEW reversing entry** (YEAR_END_CLOSE_REVERSAL / reversal-of),
  **never** an edit or delete of the closing journal. The original closing entry, its reversal, and any
  re-close entry all stand on the books. Correction of a closed year is **reverse-then-adjust-then-re-close**
  (FR-CLOSE-05), never an in-place edit.
- **BR-CLOSE-09 — A closed year blocks all further posting.** After a close, **all** posting (manual and
  automatic) into the year is **blocked** — its periods are CLOSED, and the existing posting gate (BR-GL-03)
  rejects any entry dated within the year. The **only** postings allowed at close/reopen time are the
  **closing / reversing entries themselves** (system-posted as part of the operation, dated at `end_date`
  within the year — the posting-vs-period-gate ordering is the ADR's, OQ-CLOSE-03).
- **BR-CLOSE-10 — Reopen only the most-recently-closed year.** Only the **latest** CLOSED fiscal year may be
  reopened (FR-CLOSE-04); an older closed year cannot be reopened while a later one is closed — this keeps the
  sequential retained-earnings roll chain sound. *(Recommended default, OQ-CLOSE-02.)*
- **BR-CLOSE-11 — RETAINED_EARNINGS config + YEAR_END_CLOSE source type, additive.** The close resolves the
  roll account from the new **`RETAINED_EARNINGS`** `gl_config` key → **3900** (no hard-coded code, BR-GL-10);
  the closing / reversing journals carry the new **YEAR_END_CLOSE** (and reversal) source type. Both widen the
  existing GL **additively** (the **V16** migration: RETAINED_EARNINGS seed + GL.YEAR.CLOSE perm + the
  YEAR_END_CLOSE source-type CHECK widen). A missing/inactive RETAINED_EARNINGS mapping **fails the close**.
- **BR-CLOSE-12 — Close↔reporting consistency (no double-count, no Reporting change).** After a close, the
  closed year's INCOME/EXPENSE accounts net to **zero** (their postings + the closing entry), the rolled net
  sits in **posted 3900**, and Reporting's **inception-to-date** equity fold (ADR-0018 D-6) covers only the
  still-open year's P&L — so the **Balance Sheet keeps balancing with no double-count** (the profit is counted
  once: as posted 3900, not also as un-closed P&L movement), and **Reporting needs no change**. This is the
  reason the fold was built inception-to-date.
- **BR-CLOSE-13 — Each P&L account's ledger shows the closing line that zeroed it.** The closing entry's lines
  are normal `journal_lines`; each P&L account's account ledger (Reporting's drill-down, FR-REP-04) shows the
  closing line bringing its year balance to zero — every figure traces to the posting (the BR-REP-06 trace
  discipline applies to the close lines too).
- **BR-CLOSE-14 — Per-company isolation; base currency.** Every close, closing/reversing journal, and close
  status **belongs to exactly one company**; no operation or read crosses company scope (NFR-CLOSE-01). The
  closing / reversing journals are in the company **base currency** (BR-GL-06). Cross-company close leakage is
  a **release blocker**, as for GL/AR/AP/Cash/VAT/Reporting.

## 7. Process flows (happy path + main unhappy paths), ratified v1

### 7.1 Close FY2025 — periods auto-close → closing journal posts → P&L zeroed, 3900 rolled, year CLOSED — happy path
1. A controller (`GL.YEAR.CLOSE`, active company) confirms the prior fiscal year (FY2024) is **CLOSED**
   (BR-CLOSE-04) and FY2025 is **OPEN** with **periods** (BR-CLOSE-05), then **closes FY2025**.
2. The system **auto-CLOSES** any still-OPEN periods within FY2025 (reusing `closePeriod` — BR-CLOSE-06), so
   the whole year is locked.
3. The system **posts the closing journal** dated at FY2025's `end_date` (source YEAR_END_CLOSE, via
   `GLPostingService`): **DR each INCOME account** by its year balance, **CR each EXPENSE account** by its year
   balance, and the **net profit to 3900** (CR 3900) — balanced (BR-CLOSE-01/02/03). Every P&L account is now
   **zero**; 3900 carries FY2025's net.
4. The system **marks FY2025 CLOSED** (records who/when); all posting into FY2025 is now blocked (BR-CLOSE-09).
5. The close (with the closing-journal uid + the net rolled) is **audited** (NFR-CLOSE-03). The new year starts
   with a **fresh (zero) P&L**, and the Reporting Balance Sheet keeps balancing with **no double-count**
   (BR-CLOSE-12) — **no Reporting change**.

### 7.2 Reopen FY2025 for a late adjustment → reversal posts → P&L restored → adjust → re-close — happy path
1. A late adjustment is needed for FY2025 (already CLOSED, and the **most-recently-closed** year — BR-CLOSE-10).
   The controller (`GL.YEAR.CLOSE`) **reopens FY2025**.
2. The system posts the **reversal of the closing journal** (a NEW reversing entry — YEAR_END_CLOSE_REVERSAL /
   reversal-of; restoring every P&L account's FY2025 balance + backing out the 3900 roll — BR-CLOSE-07/08),
   then flips **FY2025 and its periods back to OPEN** (BR-CLOSE-06). The reopen is **audited** (NFR-CLOSE-03).
3. The accountant **posts the adjusting journal(s)** into the now-OPEN FY2025 (the normal `GL.POST` path).
4. The controller **re-closes FY2025** (§7.1): a **fresh** closing entry posts the **corrected** net to 3900.
   The original closing entry, its reversal, the adjusting journal(s), and the re-close entry **all stand on the
   books** (append-only — BR-CLOSE-08).

### 7.3 Main unhappy paths
- **Prior year still OPEN** (7.1.1) → the close is **rejected** ("prior fiscal year must be closed first");
  nothing posts or changes (BR-CLOSE-04). Finance closes the prior year first.
- **Year already CLOSED** (7.1.1) → the close is **rejected** ("fiscal year already closed"); to correct it,
  **reopen** then re-close (BR-CLOSE-05, §7.2).
- **Year with no periods** (7.1.1) → the close is **rejected** ("fiscal year has no periods"); the calendar
  must be seeded first (BR-CLOSE-05).
- **`RETAINED_EARNINGS` unmapped / mapped to an inactive account** (7.1.3) → the close **fails** (no partial /
  wrong post); finance sets the mapping (`GL.MANAGE`), then retries (BR-GL-10, BR-CLOSE-11).
- **Reopen a year that is NOT the latest closed** (7.2.1) → **rejected** ("only the most-recently-closed year
  may be reopened"); reopen the later closed year(s) first, in reverse-chronological order (BR-CLOSE-10).
- **Attempt to edit/delete the closing journal** (any) → **refused**; the only correction is the **reopen
  reversal** then a re-close (BR-CLOSE-08, BR-GL-02).
- **Attempt to post into a CLOSED year** (any) → **rejected** by the existing period gate (BR-CLOSE-09,
  BR-GL-03); reopen the year to post into it.
- **The closing entry would not balance** (construction error) → `GLPostingService` **rejects** it
  (BR-CLOSE-03, BR-GL-01); nothing partial is written — a defect in the construction, surfaced.

## 8. Non-functional

- **NFR-CLOSE-01 — Tenant isolation.** Every close, closing/reversing journal, and close-status read is scoped
  by `company_id` and goes through the tenant-predicate path; `assertCanActIn` guards **every read path**.
  Cross-company close leakage is a **release blocker** (BR-CLOSE-14), as for GL/AR/AP/Cash/VAT/Reporting.
- **NFR-CLOSE-02 — Money correctness.** The closing entry's amounts are `Money` (amount + currency, ADR-0005)
  in the company **base currency**; the balance check (Σ debits == Σ credits) and the net rolled use
  `BigDecimal` value comparison (no float). The Σ rolled to 3900 **==** the year's net profit/loss **exactly**
  (no tolerance); a rounding discrepancy that leaves the closing entry unbalanced is a defect (NFR-GL-02,
  OQ-CUR-03 — half-up, TZS = 0 dp).
- **NFR-CLOSE-03 — Audit.** Every **close** and every **reopen** is written to the IAM append-only audit trail
  with the actor, action (`GL.YEAR.CLOSE` close / reopen), the target fiscal year, the **posted journal uid**
  (the closing entry / the reversal), the **net rolled**, the timestamp, and the company context (mirrors
  NFR-GL-06). The close runs under the operator's context (human-triggered).
- **NFR-CLOSE-04 — Atomicity.** The close operation (auto-close periods → post the closing entry → mark CLOSED)
  is **atomic** — a failure at any step leaves the year **OPEN** and **nothing posted** (no half-closed year,
  no orphan closing entry). The reopen (post the reversal → flip OPEN) is likewise atomic.
- **NFR-CLOSE-05 — Append-only integrity.** The closing / reversing journals are **append-only** (BR-GL-02 /
  BR-CLOSE-08): the operation offers **no** edit/delete of a posted closing entry; correction is the reopen
  reversal then a re-close. Structural, not policy — a defect if an edit/delete path exists.
- **NFR-CLOSE-06 — Stays within `gl`; no Reporting change.** The operation lives **inside `com.erp.modules.gl`**
  (it reads / posts GL only, reusing `GLPostingService` + `FiscalCalendarService`); it does **not** touch the
  Reporting module, and Reporting requires **no change** (the close↔reporting consistency is automatic,
  BR-CLOSE-12). An ArchUnit assertion keeps the operation within `gl` (a note for ADR-0019).
- **NFR-CLOSE-07 — Forward-compatibility.** The v1 model must not preclude the deferred slices that build on it:
  automatic opening-balance carry-forward, partial-year / interim close, multi-year batch close, consolidation
  close, automatic provisions/accruals at close, and the Statement of Changes in Equity (reporting.md §10 — the
  equity fold + the close are its foundation). Building these is deferred; precluding them is a defect.

## 9. Assumptions

- The dependency platform exists and is consumed as designed: the **`GLPostingService`** posts a balanced
  journal synchronously (the AR/AP/Cash/VAT precedent); the **`FiscalCalendarService`** opens years and
  closes/reopens periods; the **period-status posting gate** (BR-GL-03) rejects posting into a CLOSED period;
  **3900 Retained Earnings** is seeded (V10); **Money** + the company base currency are in place; **RBAC /
  `ScopeGuard` / audit** are the spine. All shipped.
- The **fiscal year carries an `end_date`** (gl.md / ADR-0013 D-2b — `fiscal_years.end_date`) the closing entry
  is dated at; the year's 12 **periods** exist and carry OPEN/CLOSED status the close drives (BR-CLOSE-06).
- The **P&L accounts are identifiable by `account_type`** (INCOME / EXPENSE — gl.md BR-GL-12); the close reads
  each P&L account's **year balance** (its net movement over the fiscal year) — an aggregate over
  `journal_lines` for the year, exactly the shape Reporting's P&L already computes (reporting.md §3.2). The
  close does **not** re-derive placement from code ranges; it uses the **type**.
- The **base-currency-only** v1 holds (sales.md / gl.md): the closing entry is in TZS in practice; the
  convert-at-entry step (BR-GL-06) is identity in practice. FX at close is **deferred** (gl.md §10.5).
- The Reporting **equity fold is inception-to-date** (ADR-0018 D-6) — the assumption the close↔reporting
  consistency (BR-CLOSE-12) rests on. This is **confirmed shipped** (ADR-0018 D-6 / the Reporting build); the
  close relies on it and requires **no Reporting change**.
- The owner ratified the **direct-to-3900** method (no Income Summary), **reopen allowed** (auto-reversing),
  **auto-close periods** at year-close, the **ordering guards**, and a **single `GL.YEAR.CLOSE`** permission for
  both close and reopen (2026-06-10).

## 10. ACCEPTED SCOPE BOUNDARY — what Year-End Close v1 deliberately does NOT do (owner-accepted 2026-06-10)

> **Read this before building or relying on Year-End Close.** v1 delivers the **close operation** (auto-close
> periods + the P&L → 3900 closing entry + mark CLOSED), the **reopen** (auto-reversing), the **ordering
> guards**, the **RETAINED_EARNINGS** config key + the **YEAR_END_CLOSE** source type, the **GL.YEAR.CLOSE**
> permission, and the **close↔reporting consistency** (no Reporting change). The following are **deliberate
> boundaries**, owner-accepted; nobody may quietly assume otherwise.

1. **No Income Summary intermediate account** — the roll is **direct to 3900** (the ratified method). The
   Income-Summary two-hop method is not built (same arithmetic, extra hop).
2. **No automatic posted opening-balance carry-forward** — the balance-sheet accounts are **continuous** on the
   books (their inception-to-date balances carry into the next year); the close posts **only** the P&L → 3900
   entry. An explicit posted new-year opening journal is not posted (unnecessary in the continuous ledger) —
   deferred, not precluded.
3. **No partial-year / interim close** — v1 closes a **full fiscal year** to its `end_date`. A quarter-end /
   interim close is deferred.
4. **No multi-year batch close** — v1 closes **one year per operation** (prior year first, BR-CLOSE-04).
   Closing several years in a single batch is deferred (run the single-year close in sequence).
5. **No consolidation / group year-end close** — v1 closes **per company** (gl.md OQ-CUR-01). Group close with
   intercompany elimination is deferred.
6. **No automatic provisions / accruals / depreciation catch-up at close** — v1 posts **only** the P&L → 3900
   closing entry; year-end adjustments are **manual journals before the close** (or reopen-adjust-re-close).
   Deferred (ties to Recurring journals / Accruals automation).
7. **No period-level locking beyond the existing OPEN/CLOSED gate** — v1 uses the shipped two-state period gate;
   soft-close locks, reopen approval workflows, and per-module close checklists are deferred.
8. **Reopen only the most-recently-closed year** — reopening an arbitrary older closed year (re-deriving the
   roll chain in one step) is not built (BR-CLOSE-10); the latest-only rule keeps the sequential roll sound.

All eight are additive by design (NFR-CLOSE-07); none is precluded by the v1 model. Year-End Close v1 is the
close + reopen operation **now**; the richer close depth is later, **into** this same GL engine.

## 11. Open questions — status after ratification (2026-06-10)

> The **six Year-End Close forks** the owner ratified (the direct-to-3900 closing entry; reopen allowed /
> auto-reversing; auto-close periods at year-close; the ordering guards; the GL.YEAR.CLOSE permission; base
> currency / append-only / double-entry) are **RESOLVED** (recorded in `docs/requirements/open-questions.md`
> under Year-End Close). This also **closes gl.md OQ-GL-03** (year-end-close automation depth) — the deferred
> slice is now specified. **No ADR-0019-blocking open question remains.** What stays open is **non-blocking**
> detail / an ADR design decision with a recommended default that stands unless the owner overrides — confirm
> during build / before go-live, not before ADR-0019.

### Still open — NON-blocking detail (recommended defaults stand; do NOT block ADR-0019)

- **OQ-CLOSE-01 — Require the immediately prior fiscal year CLOSED before closing this one?** *Recommended
  default:* **YES** — a year may be closed only if the prior year is already CLOSED (BR-CLOSE-04), so the
  retained-earnings roll is sequential and the prior year's figures are frozen. *Decider:* owner (finance).
  *Blocks ADR-0019:* **NO** — the prior-year-closed guard is the default; relaxing it (allow out-of-sequence
  close) would be an additive policy and is not recommended.
- **OQ-CLOSE-02 — Reopen only the most-recently-closed year?** *Recommended default:* **YES** — only the latest
  CLOSED year may be reopened (BR-CLOSE-10), keeping the sequential roll chain sound; to correct an older year,
  reopen the later year(s) first in reverse-chronological order. *Decider:* owner (finance). *Blocks ADR-0019:*
  **NO** — the latest-only rule is the default; reopening an arbitrary older year is a deferred (and discouraged)
  capability.
- **OQ-CLOSE-03 — Closing-entry posting vs the period-gate ordering.** The closing entry is dated at the year's
  `end_date` (within the year being closed); the year's periods are CLOSED by the same operation. Does the
  operation **post the closing entry BEFORE flipping the periods to CLOSED**, or **post it on a system path that
  bypasses the just-closed gate**? *Recommended default:* **post the closing entry first, then flip the periods
  CLOSED** (the boring, order-of-operations answer — the closing entry posts into a still-OPEN year, then the
  year locks). *Decider:* **architect (ADR-0019)** — this is an operation-ordering design detail. *Blocks
  ADR-0019:* **NO** — it **is** an ADR decision; either ordering yields the same end state (closing entry on the
  books, year CLOSED).
- **OQ-CLOSE-04 — How a P&L run *for the closed year* reads after the close.** The closing entry is dated at
  `end_date`, so a P&L for the closed year's date window includes both the trading lines and the zeroing close.
  *Recommended default:* a P&L **over the fiscal year's date range** reads the year's INCOME − EXPENSE
  **movement** (trading lines), and the closing entry — also dated in-range — nets the P&L accounts to zero by
  `end_date`; the **net profit reported for the year equals the net rolled to 3900** (the reconciliation tie).
  Reporting reads movement, not closing-adjusted balances, so the P&L for the closed year still reports its
  result correctly. *Decider:* **architect (ADR-0019)** confirms the exact date-window treatment (whether the
  P&L excludes the YEAR_END_CLOSE source-type lines for a "trading P&L" view, or includes them — either is
  consistent). *Blocks ADR-0019:* **NO** — Reporting is unchanged; this is a presentation confirmation.
- **OQ-CLOSE-05 — Handling accounts opened mid-year (a P&L account created after the year started).** A P&L
  account added partway through the fiscal year carries only its post-creation movement. *Recommended default:*
  the close zeroes **every** P&L account that has a **non-zero year balance** (an account with no movement
  contributes nothing; a mid-year-opened account is closed by its actual balance) — no special-casing; the
  balance, not the open date, drives the closing line. *Decider:* owner (finance) / architect. *Blocks
  ADR-0019:* **NO** — the balance-driven rule handles it; nothing special is needed.
- **OQ-CUR-03 — *(carried)* Rounding mode & TZS decimals.** The closing-entry amounts, the balance check, and
  the net rolled to 3900 must round identically to the GL figures they zero (NFR-CLOSE-02). *Recommended
  default:* half-up, TZS = 0 dp. *Decider:* owner (finance input). *Blocks ADR-0019:* **NO** for the model;
  **confirm before go-live**.

## 12. Out of scope for v1 (deferred — restated)

Income-summary method (the direct-to-3900 method is ratified); automatic posted opening-balance carry-forward
(continuous ledger makes it unnecessary in v1); partial-year / interim close; multi-year batch close;
consolidation / group year-end close (gl.md OQ-CUR-01); automatic provisions / accruals / depreciation catch-up
at close (ties to Recurring journals / Accruals automation); period-level locking beyond the existing OPEN/CLOSED
gate (soft-close, reopen approval workflow, per-module checklists); reopening a non-latest closed year; the
Statement of Changes in Equity (Reporting, reporting.md §10 — the equity fold + the close are its foundation);
and FX at close (gl.md §10.5 / X.6). Each is tracked for a later slice; none is precluded by the v1 model
(NFR-CLOSE-07).
