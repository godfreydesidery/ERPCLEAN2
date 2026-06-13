# Issues Register — surfaced during verification & E2E

Running log of issues found while verifying the system (browser-verify, security review, and the
large-scale E2E seed/flow run). Captured here to be **worked on later** — not all are fixed.
Newest first. Severity: BLOCKER > HIGH > MEDIUM > LOW. Status: OPEN / FIXED / WONTFIX.

| # | Sev | Status | Area | Issue | Evidence / where |
|---|-----|--------|------|-------|------------------|
| 1 | HIGH | **FIXED** | Outbox / Stock | `DomainEventDispatcher.poll()` called `this.dispatchOne()` as a self-invocation, bypassing the Spring proxy → `@Transactional(REQUIRES_NEW)` never engaged → `MANDATORY` stock handlers threw "no existing transaction" → **stock never moved at runtime**. All 371 unit tests passed (they called `dispatchOne` directly, masking it). | Found by browser/API verify; fix `fix(outbox)` commit `4f61037`, regression test `poll_dispatchesMandatoryHandler_throughProxyTransaction`. |
| 2 | MEDIUM | **FIXED** | API / error handling | Malformed requests (missing required `@RequestParam`, path-var type mismatch, unreadable body) fell through to the catch-all → generic **500** with no logged stack, instead of **400**. Surfaced via `GET /companies` with no `organisationUid`. | Fix `fix(api)` commit `75336e1`; handler added to `GlobalExceptionHandler`; regression `rootToken_listCompanies_missingOrganisationUid_returns400`. |
| 3 | LOW | OPEN | API consistency | **DTO company-reference inconsistency.** Parties create DTOs (`CreateCustomerRequest`, `CreateSupplierRequest`, `CreateAgentRequest`) take **`companyId` (Long)**, while newer masters (`products`, `price-lists`, `routes`, `units`) take **`companyUid` (String)**. Wire convention is uid-in-body; parties are un-retrofitted. Confusing for API consumers; worth harmonising to `companyUid`. | Observed building the E2E seeder (had to handle both shapes). Pre-existing (Parties module, ADR-0006). |
| 4 | LOW | OPEN | Observability | The catch-all `@ExceptionHandler(Exception.class)` in `GlobalExceptionHandler` returns a generic 500 **without logging the exception/stack** (there's a `TODO(logging)`). Any unexpected 500 is invisible in logs — made diagnosing #2 harder. Should log at ERROR with the exception before returning the safe envelope. | `GlobalExceptionHandler` catch-all; noticed during #2 diagnosis. |
| 5 | INFO | OPEN | Test integrity | Outbox/stock unit tests drive `dispatchOne()` directly (inside a test TX), which **masked #1**. The scheduled `poll()` path was untested until the new regression test. Audit other "drive the internal method directly" tests for the same blind spot (anything relying on an ambient TX the real entrypoint must open). | Root cause analysis of #1. |
| 6 | HARNESS | **FIXED** | E2E driver (`e2e/qa-ui-drive`) | **Loop re-created records (2× per item).** The customers/suppliers loop navigates to the list ONCE then loops toggle-fill-save without re-navigating; the form state carried over so the create sequence effectively ran ~twice → 100 customer rows for 50 intended names (timestamps 28s apart, non-adjacent codes → NOT a double-submit). **App was correct** (100 unique, contiguous codes CUST-0001..0100). Fix the driver: re-open a fresh form per record / assert the row landed before the next. | QA Playwright run 2026-06-08; createdAt analysis. |
| 7 | HARNESS | **FIXED** | E2E driver | **Unit create step wrong + unnecessary.** Driver clicked `New Unit of Measure` (actual button is `New Unit`) AND the fresh bootstrap **pre-seeds 17 units** — so unit creation should be skipped entirely. 3 false HIGHs. | qa-drive.js units phase. |
| 8 | HARNESS | **FIXED** | E2E driver | **User create reported failure but 4/5 persisted.** The user form's submit button doesn't match the generic `button:has-text("Save")` Save locator → 30s timeout, logged HIGH — but qauser1–4 were actually created. Driver needs the user form's real submit selector + verify-by-list instead of button-wait. | qa-drive.js users phase vs API count. |

## Application verdict from the QA UI run (2026-06-08, fresh DB, real typed entry)

**The application behaved correctly for every record the UI actually submitted.** All "failures" in
the Playwright run were **test-harness defects** (#6–#8 above), not product bugs:
- Customer codes `CUST-0001..0100`: **100 unique, fully contiguous** — per-company `code_sequence`
  held under rapid UI creates. Products 20, suppliers 19, routes 5, price-list 1, users 4 persisted.
- **Zero console errors, zero API 5xx** across the entire browser session.
- Login, navigation, and every create form that the driver targeted correctly **rendered and saved**.

Net: real browser data entry against the deployed stack is **functionally sound**. The harness needs
the three fixes above before the next UI run (per-record fresh form + correct unit/user selectors).
Data left on QA for tester inspection (fresh bootstrap + this run's typed data).

## Corrected UI run (2026-06-08, fresh DB, `e2e/qa-ui-drive.js`) — CLEAN

Harness issues #6–#8 fixed (+ a 4th found & fixed: the route Save is a `type="button"`
`(click)="create()"`, not a form submit, so Enter doesn't submit it — must click Save). Re-deployed
QA fresh and re-ran 100% typed UI entry. **Exact counts, no doubling, no app issues:**

| Entity | Intended | Persisted on QA | |
|---|---|---|---|
| Customers | 50 | **50** (codes CUST-0001..0050, unique, contiguous) | ✓ |
| Products | 10 | **10** | ✓ |
| Suppliers | 10 | **10** | ✓ |
| Users | 5 | **5** (+rootadmin = 6) | ✓ |
| Routes | 3 | **3** (ROUTE-0001..0003) | ✓ |
| Price lists | 1 | **1** | ✓ |

**0 console errors, 0 API 5xx.** The doubling (#6) is gone (per-record fresh-form + wait-for-close),
units skipped (#7), users create cleanly (#8), routes fixed (click Save). Driver issues #6–#9 are
now **FIXED in `e2e/qa-ui-drive.js`**. Application verdict stands: real browser data entry is sound.

## E2E run summary (2026-06-08, throwaway stack, main @ db46205 + fixes)

**Result: PASS — 0 BLOCKER / 0 HIGH / 0 MEDIUM / 0 LOW.**

- **Scale seeded:** 100 users (branch-assigned + role-granted), 1000 customers, 50 suppliers,
  50 products (priced), 20 EXTERNAL agents, 10 routes (agent+customer assigned), 6 branches,
  1 operator role (35 perms).
- **RBAC / multi-actor:** rootadmin bootstrapped, then a **non-root operator** created the entire
  catalogue + parties and ran the full purchase→stock→sale loop on its branch (root stepped back).
- **Correctness asserted:** customer count = 1000 ✓; stock math `received 1000 − sold 40 = 960`
  on-hand exact ✓; invoice numbers unique ✓.
- **Performance (informal):** create latency flat under load — customer avg **12 ms** / max 41 ms
  across 1000; product 19.8 ms; supplier 12.5 ms. Outbox kept pace.

### Coverage gaps / things this run did NOT exercise (candidates for a future pass)

- **Cross-tenant isolation at scale** — the run used a single company; a 2nd company + 2nd operator
  would assert no cross-company leakage on list/search (the F12–F16 class) under volume. The
  automated ITs cover this functionally; an at-scale check would be additive.
- **Concurrency** — actors ran sequentially. Parallel non-root operators on the same branch/product
  would exercise stock-on-hand row contention + the `@Version` optimistic-lock paths.
- **Negative/RBAC-denial paths at scale** — verifying a user WITHOUT a permission is blocked
  (covered by ITs; not re-checked here).
- **List/search pagination & filtering** with 1000+ rows — only count was asserted; response-shape
  and deep-page latency not profiled.
- **Goods-receipt partial / over-receipt, sale void → stock restore** at scale — single-shot only.

## GL online E2E (2026-06-09, fresh QA deploy of feat/gl-module)

Deployed GL (V10) fresh to QA, then ran the master-data UI E2E (`qa-ui-drive.js` — 0 issues,
50 customers/10 suppliers/10 products/5 users/3 routes all typed via UI) and a new GL UI E2E
(`gl-ui-drive.js`). **GL acceptance bar PASSED live:** Chart of Accounts shows the 13 seeded TZ
accounts; a balanced manual journal (DR 50,000 / CR 50,000) posted through the post-journal editor
(balance indicator + Post-enable worked); the **trial balance then showed Balanced with the 50,000**.

| # | Sev | Status | Area | Issue | Evidence |
|---|-----|--------|------|-------|----------|
| 10 | MEDIUM | **FIXED** | web / GL trial balance | `trial-balance.component.html` assumed money fields are **strings** (`row.net.startsWith('-')`, `row.totalDebit !== '0.00'`), but BigDecimal serializes as a JSON **number** on the wire → `TypeError: net.startsWith is not a function` threw mid-row, **blanking the per-row DEBIT/CREDIT/NET cells** (footer Totals + Balanced banner still rendered, computed separately). The unit spec mocked `net` as a string so it never caught it. | Found by `gl-ui-drive.js` (console error + screenshot showing empty NET column). Fixed: number-safe `+row.net < 0` / `+row.totalDebit !== 0`; added a render-with-numeric-money regression test to trial-balance.component.spec. |

Note (latent, LOW): the GL DTO money fields are typed `string` in the Angular models but arrive as
numbers — other GL screens coerce with `parseFloat`/`Number.parseFloat(String(..))` so they're safe;
only the trial-balance template assumed string. Consider normalising money to a single wire type
(string everywhere, per the Long-as-string convention) — recorded for later, not blocking.

## How to reproduce

See [`e2e/README.md`](../../e2e/README.md). Scripts: `e2e/seed-and-flow.js`, `e2e/qa-ui-drive.js`
(typed master-data UI entry), `e2e/gl-ui-drive.js` (GL: post a balanced journal + verify trial
balance), `e2e/ui-smoke.js` (browser smoke), `e2e/static-proxy-server.js` (SPA+API origin).

## Finding #11 — ROOT_BYPASS audit write fails on read-only query transactions (2026-06-09)

| # | Sev | Status | Area | Issue | Evidence |
|---|-----|--------|------|-------|----------|
| 11 | MEDIUM | **FIXED** | platform / security | `ScopeGuard.assertCanActIn` writes a ROOT_BYPASS audit row when **root acts outside its active company** (ScopeGuard.java:197-201). Its comment assumes the caller is a read-WRITE `@Transactional` method — but read-only query services (`ArAgeingQuery`, GL `TrialBalanceQuery`, AR statement/balance) are `@Transactional(readOnly=true)`. So **a root admin viewing ANOTHER company's read-only financial report (AR ageing/statement/balance, GL trial balance) gets a 500** — `JpaSystemException: cannot execute INSERT in a read-only transaction` — instead of the data. Narrow (root + cross-company + read-only path) but real. Root acting in its OWN company is fine (no bypass audit fires). | Surfaced by `ArAgeingQueryIT.ageing_crossTenant_blocked` while testing isolation. |

**FIXED** (branch fix/scopeguard-readonly-audit): added `AuditService.recordIndependent` (REQUIRES_NEW); ScopeGuard's ROOT_BYPASS audit now uses it, so it commits in its own transaction regardless of the caller's read-only/read-write status. Regression test `ArAgeingQueryIT.ageing_rootCrossCompany_onReadOnlyPath_succeeds`. **The fix also exposed two FALSE-PASS cross-tenant tests** (GL `ChartOfAccountServiceIT.getByUid_crossTenantRead_blocked` and the original AR ageing test) that only "threw" because of this 500 — both now correctly assert denial with a NON-ROOT principal (root legitimately bypasses). Original fix direction was: write the ROOT_BYPASS audit in its own `REQUIRES_NEW` transaction (the bypass audit is an independent concern and should commit regardless of the caller's read-only/read-write TX), OR have read-only query services tolerate it. Touches security-platform code used by every `assertCanActIn` caller — deserves its own branch + security review. NOTE: a non-root cross-tenant denial throws `ForbiddenException` BEFORE any audit write (ScopeGuard.java:191), so the tenant-isolation guarantee itself is intact — this is purely the root-bypass-audit-on-read-only-TX path.

## Finding #12 — migration seed-uid overflow on existing-company DBs (keep-data deploys) (2026-06-09)

| # | Sev | Status | Area | Issue | Evidence |
|---|-----|--------|------|-------|----------|
| 12 | HIGH | **FIXED** | migrations / V10–V13 gl_config + cash seeds | The per-company CROSS-JOIN seeds in V11 (AR), V12 (AP), V13 (Cash) — and latent in V10 (GL) — build a deterministic `uid` as `'XXCD' \|\| lpad(company_id,8,'0') \|\| config_key`. For long keys this exceeds the **`uid VARCHAR(26)`** column: `'ARCD'+8+'OPENING_BALANCE_EQUITY'` = 34 chars, `BAD_DEBT_EXPENSE` = 28, `ACCOUNTS_RECEIVABLE` = 31 → Postgres `ERROR: value too long for type character varying(26)`, migration fails, app crash-loops. **Only fires when the DB already has companies at migration time** (a keep-data deploy) — the Testcontainers/CI DB has NO companies when migrations run, so the seed inserts 0 rows and the bug is invisible to the test suite. Fresh/clean deploys are unaffected (BootstrapRunner seeds via the app's ULID generator after migration). | Hit on the 2026-06-09 keep-data deploy of main to QA (DB at V10 → V11 failed, crash-loop). Diagnosed from `docker logs`. |

**FIXED** (branch `fix/migration-seed-uid-overflow`, commit 4b03b24): the 3 gl_config seed uids in V10/V11/V12 now use `'XX' || lpad(company,6) || substr(md5(config_key),1,12)` (≤20 chars). Regression test `MigrationKeepDataIT` migrates an isolated schema to V9, inserts an org+company, then migrates to head — the gap that hid this. Full suite 501 green. Caveat: editing shipped migrations changes their Flyway checksum, so the next QA deploy must be **fresh (wipe)** or run `flyway repair` (QA applied V10–V13 on an empty DB → 0 seed rows, no data divergence).

**Impact (original):** any **keep-data** deploy onto a DB that already has ≥1 company breaks (can't upgrade an existing/production database). Clean deploys are fine, which is why QA was recovered with a fresh deploy.
**Fix direction (needs care — V10–V13 are merged/shipped):** the seed-uid generation must stay ≤26 chars, e.g. `'ARC' \|\| lpad(company_id::text,6,'0') \|\| substr(md5(config_key),1,12)` (21 chars, deterministic, unique per company+key). Because the only persistent DB (QA) has NOT successfully applied V11+ (it failed) and CI DBs are ephemeral, editing the V11–V13 seed lines in place is the justified pragmatic fix (no persistent successful checksum to break) — OR a forward-only repair migration. **MUST add a regression test that runs the migrations against a DB seeded with a company** (the gap that hid this) — e.g. an IT that inserts a company before Flyway runs, or asserts seed-uid length ≤26. Also covers V10's latent same-pattern overflow (ACCOUNTS_RECEIVABLE/PAYABLE keys).

## Finding #13 — Reporting (ADR-0018) adversarial review batch (2026-06-10)

Found by a multi-agent adversarial review of the Financial Reporting increment (5 dimensions → verify each finding: 11 confirmed / 10 refuted). All three actionable defects were invisible to the happy-path ITs because they are *presentation/edge* bugs, not *total* bugs — the balance + tie-out + P&L self-checks operate on totals, which still held.

| # | Sev | Status | Area | Issue | Evidence |
|---|-----|--------|------|-------|----------|
| 13a | HIGH | **FIXED** | reporting / StatementClassifier | Account **1500 (WHT Receivable)** fell in the non-current asset band (1500–1999) → presented as NON_CURRENT_ASSETS on the Balance Sheet and INVESTING on the Cash-Flow, but per ADR-0018 D-4/D-7 the VAT/WHT control accounts (1400/1500) are short-term working capital → CURRENT_ASSETS / OPERATING. Totals still balanced (so ITs passed); only the *section* was wrong. | Adversarial review (3 independent reviewers). |
| 13b | HIGH | **FIXED** | reporting / CsvStatementRenderer | **CSV formula injection** — `escape()` did not neutralise cells starting with `= + - @` (user-controlled account/company names → formula executes when the CSV is opened in Excel). Also a pre-existing **double-escape** of row labels. | Adversarial review (export dimension). |
| 13c | BLOCKER | **FIXED** | reporting / ReportingController | `accountLedgerExport` passed `Integer.MAX_VALUE` as the page size → an unbounded query materialising a multi-year ledger (potentially millions of lines) into memory → OOM (NFR-REP-02). | Adversarial review (export dimension); self-introduced in the export endpoint. |

**FIXED** (branch `feat/reporting-module`): (13a) non-current asset band shifted to **1600**–1999 in `StatementClassifier` (both `classifyAsset` + `classifyForCashFlow`), so 1400/1500 present as current/operating consistently across both statements; regression `StatementClassifierTest`. (13b) `escape()` now formula-guards text cells (prefix `'` for leading `= + - @`/TAB/CR) while leaving numeric amounts un-mangled (negatives keep their `-`); double-escape removed; regression `CsvStatementRendererTest`. (13c) export capped at `LEDGER_EXPORT_MAX_ROWS = 10_000`. Full suite **551 green**.

**Deferred (LOW — speculative DB-corruption hardening, not current defects; the GL write path enforces these invariants):**
- P&L self-check doesn't detect entries posted to the *wrong account type* (e.g. crediting 3100 instead of 4100) — would need an EQUITY/ASSET/LIABILITY-movement-in-P&L-period alarm. Manifests only on a mis-posting/corruption.
- `IncomeStatementBuilder.presentedAmount` trusts `ChartOfAccount.normalBalance` without re-asserting the type↔normal-balance invariant (enforced at write by `ChartOfAccountService`). Manifests only on DB corruption.
*(Recorded for a future hardening pass; both require data already violating a GL write-path invariant to bite.)*

## Finding #14 — Year-End Close (ADR-0019) adversarial review batch (2026-06-10)

Found by a multi-agent adversarial review of the Year-End Close increment (3 dimensions → verify each: 1 confirmed / 3 refuted). The 3 refuted were break-even / mid-year-opened-account false alarms the verifiers correctly dismissed against the spec — the closing-entry math is sound.

| # | Sev | Status | Area | Issue | Evidence |
|---|-----|--------|------|-------|----------|
| 14 | HIGH | **FIXED** | gl / YearEndClose × GLPostingService | A P&L account **deactivated while it still carries a current-year balance** (which BR-GL-07 explicitly permits — "deactivate instead of delete") makes the year-end closing journal try to post a zeroing line to an inactive account → `GLPostingService` rejects it (BR-GL-04 "inactive; cannot post to it") → the **entire year-close fails and rolls back**, leaving the year unclosable. Reachable via `ChartOfAccountService.deactivate`. | Adversarial review (reopen-guards dimension). |

**FIXED** (branch `feat/year-end-close`): the reviewer's first-cut fix (skip inactive P&L accounts in the close query) was **rejected as accounting-wrong** — it would strand a real balance on the P&L and miscompute the retained-earnings roll. Correct fix: **`YEAR_END_CLOSE` is the one `JournalSourceType` permitted to post to an INACTIVE account** (it must be able to *clear* a deactivated account that still holds a balance — consistent with BR-GL-07). `GLPostingServiceImpl.validateLine` gained an `allowInactiveAccount` flag set only when `draft.sourceType() == YEAR_END_CLOSE` (the closing journal + its reopen reversal); **BR-GL-04 stays enforced for every other source type**. Regression: `YearEndCloseServiceIT.closeFiscalYear_zeroesInactivePlAccount_afterDeactivation` (deactivate a P&L account with a live balance → close still succeeds + zeroes it). Full suite green.

**Refuted (correctly — no defect):** break-even closing entry omits the 3900 line (spec D-4 permits it; the P&L zeroing lines balance among themselves); the `draftLines>=2` guard at break-even (handled per spec); mid-year-opened zero-movement account skipped (correct — balance-driven, OQ-CLOSE-05).

---

## QA e2e — clean main deploy (2026-06-10)

Comprehensive API e2e against live QA (`http://16.170.11.41/api/v1`), main branch, V1–V16 stack
(GL, AR, AP, Cash&Bank, VAT+WHT, Financial Reporting, Year-End Close). Script: `C:/Users/Godfrey/AppData/Local/Temp/erp-full-e2e.js`.
Run 3 times with progressive script fixes to eliminate harness artifacts; all non-bug flags were
confirmed script errors (wrong enum value, missing required field, dirty-DB idempotency). One genuine
app bug survived all fixes.

| # | Sev | Status | Area | Issue | Evidence |
|---|-----|--------|------|-------|----------|
| 15 | HIGH | OPEN | ap / BillMatchServiceImpl | `BillMatchServiceImpl.runMatch` transitions the bill from DRAFT → MATCHED and calls `bills.save(bill)` without assigning a `bill_number` first. The DB CHECK constraint `chk_supplier_bill_number_when_posted` (`status <> 'DRAFT' AND bill_number IS NOT NULL`) fires → `ConstraintViolationException` → generic 500 ("An unexpected error occurred."). `ApBillNumberGenerator` is injected into `ApDebitNoteServiceImpl`, `ApPaymentServiceImpl`, and `ApOpeningBalanceServiceImpl` but is **absent from `BillMatchServiceImpl`**. Every direct-bill (no-PO) match call is broken. | Reproduced 3× on QA (bills `01KTRXAWRVYJZXH3J7G5SAWDBB`, `01KTRXTVTKV9MHCHHXF9J2DBAX`, and a third debug bill) with a freshly-created INDIVIDUAL supplier + no-PO bill. All periods OPEN, all GL configs present, base currency TZS — the GL post path is fine; the constraint fires on `bills.save` when status becomes MATCHED. Stack swallowed by the `GlobalExceptionHandler` catch-all (Issue #4). Fix: inject `ApBillNumberGenerator` into `BillMatchServiceImpl`; call `bill.setBillNumber(numbers.next(bill.getCompanyId()))` before `bill.setStatus(MATCHED)` in `postMatchedBillToGl` (and in `acceptVariance` when all lines resolve). Add regression `BillMatchServiceImplTest#runMatch_directBill_assignsBillNumber` asserting `result.billNumber() != null` and `status == MATCHED`. |

**E2e-script artifacts (not bugs — app correctly rejected or dirty-DB state):**
- `CreateSupplierRequest` field gaps: initial script used `partyType: 'ORGANISATION'` (not in `PartyType` enum — valid: `INDIVIDUAL`/`BUSINESS`) and missing `supplierKind`; then `partyType: 'BUSINESS'` without `tin` (BR-PARTY-04 correctly requires TIN for BUSINESS). Fix: use `INDIVIDUAL`. App correctly returned 400.
- Cash 2nd bank account 409: GL account 1100 already linked from a prior run (`E2E Bank` account from `erp-acct-loop.js`). App correctly enforced uniqueness. Script fixed to look up existing account by `glAccountId`.
- VAT return 409: period 2026-06 already has a FILED return from the first run. BR-VAT-01 correctly rejects duplicates. Script fixed to look up existing return and exercise lock-check only.
- Price list absent on fresh DB: bootstrap does not seed a price list (by design — business data). Script fixed to create one before pricing the product.

**Flows that passed cleanly (no genuine app issues):**

| Flow | Result |
|------|--------|
| 1. Baseline — login, org/company/branch/unit resolution, trial balance | PASS |
| 2. Master data — customer, agent, supplier, product, price-list price | PASS (after script fix) |
| 3. Cash & Bank — default account, 2nd bank account, direct entry IN, transfer, GL reconciliation (book==GL both accounts) | PASS |
| 4. AR — credit invoice, line add, finalize, outbox wait, AR open item exists, receipt | PASS |
| 5. AP list endpoints (GET supplier-bills, GET payments) | PASS |
| 5. AP bill enter + match → | **FINDING #15 (500)** |
| 6. VAT return open/recompute/file, lock enforced (re-file → 409), WHT register 200 | PASS |
| 6. Trial balance balanced throughout all flows | PASS |
| 7. Income statement (reconciliation.ties=true), balance sheet (ASSETS==LIAB+EQUITY), cash flow (ties=true), account ledger, CSV export (200, text/csv, non-empty) | PASS |
| 8. Year-end close (→ CLOSED), balance sheet post-close still ties, reopen (→ OPEN, QA left clean) | PASS |

**Verdict: HAS-FINDINGS — 1 HIGH (BLOCKER-adjacent: all AP bill match/post calls fail with 500). All other V1–V16 flows PASS.**

## Finding #16 — Inventory Valuation (ADR-0020) adversarial review batch (2026-06-10)

Multi-agent adversarial review of the Inventory Valuation + COGS increment (5 dimensions → verify each: 18 confirmed / 11 refuted; several "confirmed" overlapped or over-stated the impact — see triage). 7 distinct genuine issues FIXED (commit 2c851fb); 3 deliberately NOT changed (rationale below); full suite 584 green.

| # | Sev | Status | Area | Issue |
|---|-----|--------|------|-------|
| 16a | HIGH | **FIXED** | stock / InventoryValuationServiceImpl.reverseReceipt | New avg computed with the PRE-reversal qty (`soh.getQuantity()`) — reverseReceipt runs before the qty delta posts → reversing a receipt that empties on-hand set avg=0 instead of keeping last-known. Fix: `qty.subtract(originalQty.abs())`. |
| 16b | HIGH | **FIXED** | stock / InventoryGlPoster | GL `configResolver.resolve` ran in the OUTER (handler MANDATORY) TX → a missing/inactive account marked the dispatch TX rollback-only, undoing the physical stock movement (defeating the null-on-anomaly isolation). Fix: each event post method is now `@Transactional(REQUIRES_NEW)` doing resolve+build+post+catch→null inside the boundary (dropped GLPostingSafeInvoker for these). |
| 16c | MEDIUM | **FIXED** | stock / StockServiceImpl.adjust | ADJUSTMENT movement recorded null unit_cost/value (movement cols are updatable=false; revalueAdjustment ran after). Fix: compute avg×|qty| BEFORE posting.post so the movement carries the cost (exact-reversal + ledger completeness). |
| 16d | MEDIUM | **FIXED** | stock / InventoryValuationServiceImpl | costIssue/reverseIssue/reverseReceipt/revalueAdjustment lacked the one-retry-on-ObjectOptimisticLockingFailureException wrapper that recomputeOnReceipt has (ADR-0020 D-2 / NFR-INV-05). Added (resilience; single-instance QA never raced, but multi-instance needs it). |
| 16e | MEDIUM | **FIXED** | stock / StockValuationQuery | Per-row report avgCost = `MAX(avg_cost)` across branches → use implied `Σon_hand_value / Σqty` (matters once multi-location lands; recon total unaffected). |
| 16f | MEDIUM | **FIXED** | stock / SaleIssueStockHandler + costIssue | Division-by-zero guard on a zero-qty issued component (recipe explosion edge). |
| 16g | MEDIUM | **FIXED** | ap / BillMatchServiceImpl | postMatchedBillToGl had no idempotency guard → a re-run match double-posts the GL. Fix: short-circuit if a journal entry already exists for (companyId, AP_BILL, bill.uid). |

**Deliberately NOT changed (intentional; would destabilise working code or are non-defects):**
- SALE_ISSUE `value_amount` stored as a positive magnitude (spec D-2 says signed) — GL postings, reversals, and the recon bar ALL tie today; reversal logic reads it as a magnitude. Changing the sign risks the working reversal flow for a purely-cosmetic data-model-contract gain. Documented convention; no behavioural change. (OQ — revisit if a signed-ledger audit report is ever added.)
- The zero-cost / null-cost-receipt CHECK constraint — zero-cost receipts are intentionally allowed (ADR-0020 D-2 edge).
- The SALE_ISSUE unit-cost re-derivation (value/qty vs stored avg) — rounding-equivalent at 4dp; LOW.

**Refuted (11 — correctly, no defect):** the migration #12-safe seed-uids, the journal source-type CHECK completeness, the perms seed+grant, the report/opening/adjustment per-company scope (assertCanActIn on every path), the gl_config JOIN mapping, and the zero-cost-receipt recon (the weighted-avg preserves Σqty×avg == GL 1300) were all verified safe.

## Finding #17 — Sales Orders / Order-to-Cash (ADR-0021) adversarial review batch (2026-06-11)

Multi-agent adversarial review of the full O2C increment (Stage 1+2: quote/SO/reservation/delivery/COGS-seam/partial-invoicing/discounts/returns) — 6 dimensions, 52 agents, **46 findings → 28 confirmed / 18 refuted**. After dedup/triage, 4 distinct fixes applied (commit 4a41945); full suite **602 green**.

| # | Sev | Status | Issue |
|---|-----|--------|-------|
| 17a | HIGH | **FIXED** | **The big one (≈6 findings collapsed here):** composed/recipe products never persisted `delivery_line.issue_value_amount` — `DeliveryIssueStockHandler.processSimpleLine` wrote it but the `processComponent` (BOM) loop did not. So `SalesReturnServiceImpl.proRateIssueValue` returned null → a return of a KIT product restored qty but **skipped the COGS reversal** (inventory overstated, phantom P&L gain). Fix: `processComponent` returns its issued value, `processLine` accumulates + writes the total back (mirrors simple lines). IT added: deliver a kit → return → asserts COGS reversed at original cost. |
| 17b | MEDIUM | **FIXED** | No discount validation — negative and >100% discounts accepted (line + order). New `DiscountValidator` rejects them at quotation/SO/invoice create+addLine+updateLine. |
| 17c | HIGH | **FIXED** | `createInvoiceFromDelivery` copied the FULL order-level `docDiscountAmount` onto a PARTIAL invoice (subset of lines) → wrong net/VAT. Fix: pro-rate `docDiscountAmount` by `invoicedRawNet / soTotalRawNet`. (VAT-on-discounted-net was already correct — `InvoiceTotalsCalculator` applies VAT on discounted nets.) |
| 17d | MEDIUM | **FIXED** | Concurrency (ADR-0021 D-5 posture = `@Version` + ONE retry, NOT pessimistic locks): `confirm`/`cancel`/`delivery.create` mutate SalesOrderLine + stock reserved_qty without the retry wrapper `StockReservationService` has → double-reserve / over-fulfill / qty_reserved corruption under concurrent ops. Added one-retry-on-`ObjectOptimisticLockingFailureException` wrappers (confirm keeps `assertDraft`, so a sequential re-confirm is rejected). |

**Verified already-correct (no change):** `DeliveryReturnedPayload.ReturnLineItem.productUid` (set to the numeric product id, used directly — field name misleading but value correct); the VAT-on-discounted-net path.

**Deliberately NOT changed (accepted; would over-engineer or are within tolerance):** the `StockReservationService` negative-reserved silent CLAMP (defensible safety net + DB CHECK backstop), the order-discount apportionment double-rounding edge (MEDIUM), the line-level cancellation-after-partial-delivery guard (LOW), integer/scale truncation (LOW), and the "async write-back race" (the delivery is confirmed + the event dispatched before any return can reference it; 17a closes the null path). No pessimistic DB locks added (single-instance posture).

**Refuted (18 — correctly, no defect):** the seam double-count (a sale traverses exactly one issue path — delivery for SO via `issuesStock=false`, finalise for DIRECT — proven by the seam IT), the V18/V19 #12-safe seeds + the `chk_ar_credit_note_origin` additive widen, the perms seed+grant, per-company scope (assertCanActIn on every read path), and most of the over-stated multi-instance concurrency scenarios beyond the bounded retry above.

## Finding #18 — QA browser-e2e session on the clean `develop` deploy (2026-06-11)

QA box `16.170.11.41` **redeployed from `develop` @ 843f407 with CLEAN DATA** (volume wiped → fresh env-bootstrap; 19 migrations V1–V19 validated; login + health UP). Ran the `e2e/` Playwright drivers against the live site (operator tools, not CI). Result: **the core + all shipped modules render and work; the O2C browser flow surfaced one real frontend bug, now fixed + re-verified green.**

| Driver | Result |
|---|--------|
| `ui-smoke.js` | 7/7 PASS (login + stock/PO/goods-receipts/routes render; Route create+detail), 0 console errors, 0 API 5xx |
| `qa-ui-drive.js` (typed CRUD) | 0 issues — 8 products / 15 customers / 6 suppliers / 3 users / 2 routes / 1 price-list typed through the real forms |
| `sales-o2c-ui-drive.js` (NEW — full Order-to-Cash, API stock-in prereq + browser flow) | After the fix below: **12/12 steps PASS** — quote→send→accept→SO→confirm/reserve→partial delivery (COGS posted)→invoice-from-delivery→return (RET CONFIRMED)→credit note raised. 0 BLOCKER/HIGH, 0 console errors, 0 5xx. |

| # | Sev | Status | Issue |
|---|-----|--------|-------|
| 18a | HIGH | **FIXED + re-verified** | **Sales Return create screen crashed** — `TypeError: qtyInput.trim is not a function`. `<input type="number">` + `[(ngModel)]` coerces the bound value to a JS *number* at runtime, but `sales-return-create.component.ts` called `.trim()` on it (`lineQtyError` + `submit` × 3 sites) → the form threw before submitting, so the **entire Sales Return / credit-note / COGS-reversal leg was unusable from the UI**. Unit/IT tests missed it (the backend return flow is correct — only the web form crashed); the **browser e2e caught it**. Fix (develop `78c59be`): `String(x ?? '').trim()` at the 3 sites. Re-deployed + re-ran → return leg green (RET-0001 CONFIRMED + credit note raised). |

**The `e2e/sales-o2c-ui-drive.js` driver** (API-seeds a stocked product, then drives quote→SO→deliver→invoice→return in a real browser) is a reusable O2C regression check — kept in `e2e/` (uncommitted operator tool, per the harness convention).

## Finding #19 — Phase-B 14-module backend adversarial review batch (2026-06-13)

After building the entire remaining ERP backend in waves (the 14 Phase-B modules: approvals, documents,
notifications, cost-centre/costing-dimension, products-BOM, procurement-depth, inventory-depth,
sales-depth/POS, fixed-assets, CRM, HR-payroll, projects, budgeting, manufacturing) and integrating them
green on `develop`, a **13-module multi-agent adversarial review** (one fan-out per module reading the
cited code + ADR, plus structured-output synthesis cross-checked against the actual fix diffs) surfaced
**62 findings**. After triage/dedup, **every confirmed BLOCKER and HIGH was fixed** across 13
worktree-isolated `feat/fix-*` branches, all merged to `develop`; 19 fix-induced regressions resolved
(commit `8486b2a`); a post-merge **adversarial-verify pass** on 3 flagged PARTIAL fixes found 2 more
genuine HIGHs (fixed) + 1 MEDIUM (deferred). **Full suite green** (`mvn verify` BUILD SUCCESS, 685 surefire
+ 98 failsafe).

### BLOCKER / HIGH — all FIXED (with a proving test each)

| # | Module | Sev | Issue → Fix |
|---|--------|-----|-------------|
| 19-hr1 | hr-payroll | BLOCKER | **Unbalanced GL — loan-repayment deductions had no CR leg** (ΣDR > ΣCR by loan total). Fix: iterate `itemKind=DEDUCTION` line items, group by gl_account, post each as a CR leg. IT asserts `sumDebit==sumCredit` + CR on loan-receivable. |
| 19-hr2 | hr-payroll | BLOCKER | **Voluntary deductions ignored** — recorded as EARNINGs, never reduced net, no CR leg (net overstated + GL unbalanced). Fix: `calculate()` branches on `PayComponentKind`; DEDUCTION items accumulate into `voluntaryDeductTotal` and post as CR. |
| 19-hr3 | hr-payroll | BLOCKER | **PAYE/WCF/SDL computed on full gross with no unpaid-leave pro-rata** (FR-HR-13) — overstated tax for staff on unpaid leave. Fix: `grossForPeriod = gross×(workingDays−unpaidDays)/workingDays`; queries approved unpaid days for the period. |
| 19-hr4 | hr-payroll | BLOCKER | **`disburse()` never posted cash** — flipped to PAID without DR NET_WAGES_PAYABLE / CR bank, leaving the payable open after "payment". Fix: `CashDirectEntryService.recordDirectEntry(OUT, netTotal)` before PAID. |
| 19-hr5..9 | hr-payroll | HIGH | approve() didn't block FLAGGED lines (BR-HR-07 bypass); percent-allowance heuristic mis-paid (10% read as flat 10); negative-net forced to ZERO (corrupted record); payslip numbers collided across runs (per-run counter → global `HR_PAYSLIP` sequence); department snapshot null. All fixed + tested. |
| 19-cc1 | cost-centre | BLOCKER | **`GLPostingService.post()` validated NO dimension values** — journals posted with inactive/cross-company/wrong-slot dimensions and missing mandatory dims (governance + cross-tenant tag leak; ADR-0025 D-4 step never wired into GL). Fix: `validateDimensions()` (active + correct slot + same company + mandatory enforcement) in `post()`; `postReversal()` delegates to `post()` so reversals inherit it. IT: mandatory-untagged → reject, inactive-value → reject. |
| 19-sd1 | sales-depth | BLOCKER | **Drop-ship COGS never recognised** — `DROPSHIP.FULFILLED` had no consumer; revenue posted, cost stayed 0 (phantom margin). Fix: new `DropshipFulfilCogsHandler` posts DR COGS / CR GRNI at supplier cost, idempotent. |
| 19-sd2..4 | sales-depth | HIGH | POS payout enum/sign inverted (payouts ADDED to expected cash → wrong variance direction); variance GL hardcoded USD vs company base TZS; variance posting date `now()` not session-close (wrong period). All fixed + tested. |
| 19-pj1 | projects | BLOCKER | **Project P&L recon bar was a tautology** (`revenue.compareTo(revenue)==0`) — always green, could never surface a divergence. Fix: real computed-vs-GL values; balanced documented structural-by-construction (roll-up IS the GL GROUP BY). |
| 19-pj2..5 | projects | HIGH | Multi-project service bills lost per-line project tags (one aggregated DR PURCHASES); sales revenue leg never threaded `project_id` (revenue excluded from project P&L); costing/WIP read paths scoped on company only → **cross-branch financial leak**; `ISSUE_TO_PROJECT` stored negative `value_amount` vs the positive-cost contract. All fixed + tested. |
| 19-pr1 | procurement-depth | BLOCKER | **`placeOrder()` injected the approval gate but never invoked it** — over-threshold POs went DRAFT→ORDERED with no approval (control bypass, FR-PROC-13). Fix: gate check before the status transition. |
| 19-pr2..3 | procurement-depth | BLOCKER/HIGH | **Purchase return never raised the AP debit note** (`debit_note_uid` null; inventory reversed but payable unchanged — the "AP subscribes" comment described a non-existent handler). Fix: raise the debit note synchronously in `confirm()`. |
| 19-pb1..4 | products-bom | BLOCKER/HIGH | **`explode()` + `rollUp()` had NO `assertCanActIn`** — a foreign `bomUid`/`branchUid` leaked another tenant's BOM structure + standard cost (the controller javadoc falsely claimed the guard existed). Fix: `scopeGuard.assertCanActIn` before any data read; corrected the javadoc. |
| 19-mf1 | manufacturing | BLOCKER | **`cancel()` reversed nothing** — issued-component stock stayed depleted + WIP GL inflated (recon permanently broken). Fix: `WorkOrderCostingService.cancel` posts PRODUCTION_ISSUE_REVERSAL + GL reversal + zeroes accumulators. |
| 19-mf2..4 | manufacturing | BLOCKER/HIGH | IT suite only covered the happy path (added 5: cancel-reversal, double-issue, zero-WIP completion); zero-openWip completion skipped GL leaving phantom WIP balance; divide-then-multiply rounding residual accumulated into variance. All fixed + tested. |
| 19-iv1 | inventory-depth | BLOCKER | **Moving-avg recompute on receipt used the deprecated single-location finder** — multi-location avg diverged per-location, breaking Σ on_hand_value == GL 1300. Fix: aggregate ALL location rows, one company-product avg synced + re-attributed value to every row. |
| 19-iv2 | inventory-depth | BLOCKER | **Cost-issue/reversal read avg from an arbitrary location row** post-V37 → COGS costed at the wrong avg / misallocated value. Fix: resolve company-product avg from all rows; location-aware retry re-read. |
| 19-iv3 | inventory-depth | HIGH | In-transit lookup silently fell back to the WAREHOUSE default (destroyed in-transit semantics). Fix: `orElseThrow` instead of fallback. |
| 19-fa1..4 | fixed-assets | BLOCKER/HIGH | **Disposal posted GL without first charging outstanding depreciation** (BR-FA-10 — stale accum-dep, wrong gain/loss); revaluation-regenerate divided new base by ORIGINAL life not remaining periods (under-charged, never plugged to salvage); `gl_entry_uid` nullable allowed orphaned rows blocking idempotent retry (→ NOT NULL). All fixed + tested. |
| 19-bg1..3 | budgeting | HIGH | **DIRECT + ANNUAL_SPREAD line edits mutated entities without `save()`** — edits silently lost under flush-mode dirty-checking; company-wide variance never folded the unallocated bucket into actual (untagged spend showed actual=0). All fixed + tested. |
| 19-ap1 | approvals | HIGH | **Reject-kills-chain used an entity loop that could leave a later step PENDING** (stuck/inconsistent chain). Fix: single atomic JPQL UPDATE PENDING→SKIPPED. |
| 19-ap2 | approvals | HIGH | **Inbox query filtered company+role but NOT branch** — a user could see/approve requests from branches they cannot act in (cross-branch leak). Fix: LEFT JOIN UserBranch mirroring `StepApproverResolver.canDecide`. |
| 19-crm1..2 | crm | HIGH | **Pipeline-stage read endpoints gated on an unseeded permission** (`CRM.STAGE.VIEW` → every read 403); pipeline analytics gated on `CRM.OPPORTUNITY.VIEW` instead of the seeded `CRM.PIPELINE.VIEW` (permission-segregation gap). Both re-pointed to seeded perms + tested. |
| 19-nt1..2 | notifications | HIGH | **Scan marker saved BEFORE `raise()`** — a transient raise failure permanently suppressed retry (lost overdue notifications); PAYMENT.RECEIVED payload carried raw `1180.0000` not formatted `TZS 1,180.00`. Both fixed + tested. |
| 19-iv4 | inventory-depth | HIGH | **POST-REVIEW VERIFY:** `applyLandedCost` left on the single-location finder — capitalised onto ONE branch row + recomputed avg from that row's qty (per-location avg divergence) and threw `NonUniqueResultException` once 2+ location rows existed (rolled back the landed-cost TX). Fix: aggregate all rows (mirrors recompute-on-receipt). IT: landed cost across 2 locations → both rows avg=600, Σ rises by exactly the allocation. |
| 19-iv5 | inventory-depth | HIGH | **POST-REVIEW VERIFY:** `costIssue` (value leg) + `reverseIssue` on the single-location finder → `NonUniqueResultException` poisons SALE.FINALISED / SALE.VOIDED / DELIVERY.RETURNED handlers once an in-branch transfer creates a 2nd location row, stranding COGS on the P&L. Fix: target the branch DEFAULT location row (where the qty leg lands); avg untouched per D-5. IT: 2-location finalise→void recon ties. |

### Deliberately DEFERRED (MEDIUM — real but no sacred-invariant break; rationale recorded)

| # | Module | Issue | Why safe to defer |
|---|--------|-------|-------------------|
| 19-d1 | procurement-depth | **Billed-receipt return posts DR GRNI / CR INVENTORY** instead of DR AP / CR INVENTORY (re-opens the GRNI clearing account the bill-match already zeroed; `billed` flag added but bill-match lookup hardcoded `false`, WARN-surfaced). | **Neither sacred invariant breaks** (verified): every journal is balanced (ΣDR==ΣCR), and GL 1300 == Σ on_hand_value holds (reverseReceipt aggregates all rows correctly). The damage is two equal-and-opposite wrong contra accounts (stranded GRNI debit + spurious PURCHASES credit) — the trial balance still balances. This is exactly ADR-0027 **OQ-RETURN-GL**, the known build-time trade-off; surfaced via WARN for finance to clear. Fix when scheduled: compute real `billed` from the matched bill + branch the GL leg. |
| 19-d2 | projects | WIP `cost−billed` naming inconsistent with ADR D-6 (`billedRevenue`). | Comment/naming nit; math correct (floored at 0). Cosmetic. |
| 19-d3 | projects | `supplier_bill_lines.project_id` has no DB CHECK that the project's company matches the bill's. | Backstopped at the service layer (`ProjectTagResolver` validates same-company); a correlated-subquery CHECK isn't reliably re-evaluated in Postgres → no protection over the enforced write path. |
| 19-d4 | budgeting | Concurrent upsert race on `(version, account, period)` → 500 instead of clean 409. | DB unique constraint is a hard backstop (no data corruption); only a 500-vs-409 UX nit on a rare race. |
| 19-d5 | approvals | Status mutations rely on dirty-checking, not explicit `save()`. | `save()` on a managed entity is a no-op; dirty-check flushes on commit. Not a bug. |
| 19-d6 | approvals | `approval_request_steps` inherits a `@Version` column despite ADR D-6 saying steps have no independent version guard. | Column is inert (header `@Version` guards the advance race); `ddl-auto=validate` passes; removing needs a destructive migration. ADR design nit. |
| 19-d7 | procurement-depth | Return-cost reads `goods_receipt_lines.unit_cost_amount` snapshot rather than the GOODS_RECEIPT movement rows. | ADR-0027 D-7 explicitly permits the line-cost convenience; only a manual data-fix to the line cost could diverge it. |

**Method note:** the synthesis cross-checked each claimed fix against the actual merged `feat/fix-*` diff (only counted as fixed if the diff genuinely addressed it). The two `inventory-depth` post-review HIGHs (19-iv4/iv5) were caught precisely *because* the synthesis flagged the original fixes as PARTIAL and an adversarial-verify pass confirmed them against the Σ on_hand_value == GL 1300 invariant — exactly the failure mode a single review pass would have shipped. The 14th module (**documents**, ADR-0023) had no confirmed BLOCKER/HIGH in its review and was not in the fix fan-out.

## Finding #20 — QA fresh-data full e2e (Phase-B all-module seed, 2026-06-13)

QA box `16.170.11.41` **wiped + redeployed fresh from `develop`** (all 14 backends + their new
frontends), then seeded with LIVE data across every module so QA can browse populated screens:
Tier-1 bulk (`e2e/seed-and-flow.js` — 6 branches, 100 users, 50 products, 50 suppliers, **1000
customers**, PO→receive→20 sales invoices; **0 issues**, stock + invoice-number assertions pass) +
a new **Phase-B seeder** (`e2e/phaseb-seed.js`, 10 authored module seed-fns) + a focused **top-up**
(`e2e/phaseb-topup.js`) for CRM + cost-centre. The all-module seed surfaced **3 genuine backend
bugs** the 785-test suite missed (all FIXED on `develop` + regression-tested) plus 1 config-interaction
finding.

| # | Sev | Status | Area | Issue → Fix |
|---|-----|--------|------|-------------|
| 20a | BLOCKER | **FIXED** (9798a66) | crm / OpportunityServiceImpl.create | `opportunities.save(opp)` ran BEFORE `setOpportunityNumber(number)`; `opportunity_number` is NOT NULL and the audit query's autoflush attempted the INSERT with a null number first → `null value violates not-null constraint` → **every opportunity create 500'd** (CRM unusable). Fix: assign the number BEFORE save (the documented autoflush-ordering trap; same class as AP bill-number #15). Regression `OpportunityServiceIT` (e7a556b) asserts a non-null OPP- number persists. Verified live on QA (OPP-0007 created). |
| 20b | BLOCKER | **FIXED** (9798a66) | documents / GeneratedDocument.sourceParams | String mapped to a JSONB column with only `columnDefinition="JSONB"` → Hibernate bound it as varchar, Postgres rejected the implicit varchar→jsonb cast → **every parameterised document render 500'd**. Fix: add `@JdbcTypeCode(SqlTypes.JSON)` (the house pattern from SalesInvoice/AuditLog/DomainEvent). Regression `DocumentSourceParamsJsonbIT` (e7a556b) round-trips a JSON sourceParams through the real JSONB column. |
| 20c | HIGH | **FIXED** (9955437) | costing / BootstrapRunner | `DimensionSeeder.seedBuiltIns()` (the COST_CENTRE + DEPARTMENT built-ins, ADR-0025 D-9) existed with a javadoc saying "Called from CompanyService.create or BootstrapRunner" but was wired into **NEITHER** → a freshly-bootstrapped company had **zero costing dimensions**, making the entire cost-centre module (dimension values, GL dimension tagging) unusable on a fresh install. Fix: call `dimensionSeeder.seedBuiltIns(company.getId())` in `BootstrapRunner` with the other seeders (idempotent). Verified live: COST_CENTRE + DEPARTMENT now seed at bootstrap → cost-centre seed then created 22 dimension values. |
| 20d | MEDIUM | **OPEN** (config-interaction; documented) | costing / GL posters | Making a dimension **mandatory** (`PATCH /dimensions/uid/{uid}/mandatory {mandatory:true}`) causes the GL dimension-validation (the ADR-0025 cost-centre BLOCKER gate) to **reject every automated GL posting that doesn't tag that dimension** — observed: `STOCK.RECEIVED` from a goods-receipt became a poison event (`UnexpectedRollbackException` → retried forever) because `InventoryGlPoster` does not supply a COST_CENTRE value. The automated posters (inventory, and by inspection sales/AP/payroll) post GL without dimension tags, so a mandatory dimension breaks document-driven postings, not just manual journals. The validation itself is correct (it's the D-4 gate working); the gap is that the automated posters don't carry dimension context, so **mandatory dimensions are only safe once every poster threads a dimension value (or mandatory is scoped to manual journals only)**. WORKAROUND on QA: kept the dimension OPTIONAL (un-set mandatory, poison event then drained). The `phaseb-seed` cost-centre fn should NOT set a dimension mandatory until this is resolved. Worth an ADR-0025 follow-up: either default-tag automated postings (e.g. a branch's default cost centre) or make `mandatory` apply only to user-entered journals. |

**Seed-script (NOT app) notes:** the generated CRM seed-fn's stage-creation 500'd because bootstrap
already seeds the 5 pipeline stages (`uq_pipeline_stage_company_order` dup — app correctly rejects,
though as a 500 not a 409 — minor), and its later steps gated on the created-count rather than the
fetched-stage list, so it reported 0; the `phaseb-topup.js` script (using the bootstrap stages)
created **16 leads + 10 opportunities** live. Dimensions are seeded built-ins (no POST endpoint) —
the topup creates dimension *values* only.

**Live-data inventory on QA after the run** (`http://16.170.11.41/`, login `rootadmin`): customers
1000 · products 59 · employees 4 + 2 payroll runs + 2 loans · fixed-assets 7 (+ categories,
depreciation/disposal/reval) · work-orders 6 (released/issued/completed/cancelled) · projects 4 (+ tasks,
timesheets) · budgets 4 (+ 6 versions, approvals/rejections/recalls) · approval-policies 5 · CRM 16 leads
+ 10 opportunities · cost-centre 2 dimensions + 22 values · documents (branding + 6 templates + rendered) ·
notifications (preferences + type toggles + delivery log). UI smoke 6/7 (0 console errors, 0 API 5xx;
the 1 "login" fail is a driver-selector quirk — login returns a JWT and all authed screens render).

**A re-seed convenience:** `e2e/phaseb-seed.js` + `e2e/phaseb-topup.js` are reusable operator tools
(kept in `e2e/`, uncommitted like the other drivers) to repopulate a freshly-wiped QA box across all
Phase-B modules.
