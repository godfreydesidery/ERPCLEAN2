# 0017 — VAT Return & Withholding Tax data model: the monthly accrual-basis VAT return that nets output (Sales) against input (AP), files DRAFT→FILED with a synchronous GL settlement to a VAT-due liability + credit carry-forward, the additive AP-bill split of input VAT onto a new VAT_INPUT control account, and lean WHT capture on the AP-payment / AR-receipt cash legs

- **Status:** Accepted
- **Date:** 2026-06-10
- **Deciders:** solutions-architect (owner-ratified VAT-return requirements 2026-06-09 — all VAT scoping forks resolved; no ADR-0017-blocking open question remains, vat-return.md §11; the `VAT_INPUT` account + AP-input-VAT seam is the decision this ADR makes, not a requirements blocker)
- **Context source:** [docs/requirements/vat-return.md](../requirements/vat-return.md) (RATIFIED 2026-06-09 — FR-VAT-01..15, BR-VAT-01..13, US-VAT-01..06, §7 flows, §10 accepted boundary, §11 OQ log; ground truth for every rule below). [ADR-0013](0013-general-ledger-data-model.md) + the **shipped** `com.erp.modules.gl`: `GLPostingService.post(JournalEntryDraft)` (the synchronous double-entry engine — validates ≥2 lines, balance, OPEN period, active accounts, base currency; writes batch+entry+lines atomically; returns a `JournalEntryDto` carrying the new `journal_entries.uid`), the `JournalEntryDraft(companyId, branchId, postingDate, description, sourceType, sourceRef, reversalOfId, postedBy, List<LineDraft>)` + `LineDraft(accountId, debitAmount, creditAmount, currency, memo)` internal DTOs (verified), `GLConfigResolver.resolve(companyId, GlConfigKey)` (throws if the mapping is missing or the account inactive — BR-GL-10), `GlConfigKey` (`SALES_REVENUE`/`VAT_PAYABLE`/`ACCOUNTS_RECEIVABLE`/`CASH`/`INVENTORY`/`COGS`/`ACCOUNTS_PAYABLE`/`BAD_DEBT_EXPENSE`/`OPENING_BALANCE_EQUITY`/`PURCHASES` — **NO `VAT_INPUT`, NO VAT-due key**), `JournalSourceType` (admits `MANUAL`/`SALES`/`SALES_REVERSAL`/`OPENING_BALANCE`/`AR_*`/`AP_*`/`CASH_*`; reserved `AR`/`AP`/`COGS`/`CASH`/`PAYROLL`/`DEPRECIATION`), `FiscalPeriodResolver`. The TZ CoA seeded in V10: `1000 Cash`, `1100 Bank`, `1200 AR`, `1300 Inventory`, `2100 AP`, `2200 VAT Payable`, `3000 Owner's Equity`, `3900 Retained Earnings`, `4100 Sales`, `5100 COGS`, `5200/5300/5400` expenses; V12 added `5150 Purchases` + `3100 Opening Balance Equity`. [ADR-0008](0008-sales-data-model.md) + [V5__sales.sql](../../backend/src/main/resources/db/migration/V5__sales.sql) (output VAT: `sales_invoices.vat_total_amount` + per-band `tax_summary` JSONB, `finalised_at`, `status`; the shipped `SalesPostingHandler` already **CR `VAT_PAYABLE` (2200)** on finalise — output VAT sits on 2200 continuously). [ADR-0015](0015-accounts-payable-data-model.md) + the **shipped** `com.erp.modules.ap.service.BillMatchServiceImpl.postMatchedBillToGl` (verified, lines 265-308): on bill-match it posts **DR `PURCHASES` (5150) net · [DR `VAT_PAYABLE` (2200) vat if > 0] · CR `ACCOUNTS_PAYABLE` (2100) gross** — i.e. today **input VAT is debited to `VAT_PAYABLE`**, netting against output on 2200 (the OQ-AP-04 v1 stopgap, ADR-0015 D-6, explicitly deferred to T1.5). This ADR makes T1.5's split live. [ADR-0016](0016-cash-and-bank-data-model.md) + the **shipped** `com.erp.modules.ar.service.ArReceiptServiceImpl.recordAndAllocate` (verified, lines 99-222: DR chosen cash/bank `gl_account_id` / CR `ACCOUNTS_RECEIVABLE`; `RecordReceiptRequest` carries `cashBankAccountUid`) and `com.erp.modules.ap.service.ApPaymentServiceImpl.postPaymentToGl` (verified, lines 259-294: DR `ACCOUNTS_PAYABLE` / CR chosen cash/bank `gl_account_id`; `PaySingleBillRequest`/`PaymentRunRequest` carry `cashBankAccountUid`; both call `CashBankAccountResolver` + `CashTransactionRecorder`). [ADR-0005](0005-money-and-currency.md) (`Money` NUMERIC(19,4)+currency; base currency only — `companies.base_currency`, BR-VAT-13). [ADR-0007](0007-products-data-model.md) (`code_sequence(company_id, entity_kind)` row-locked numbering). [ADR-0002](0002-rbac-and-scope.md)/`ScopeGuard` (the `companyIdOf(targetType, uid)` switch + per-module repository deps; the verified GL/AR/AP/Cash additions). **CRITICAL — [ISSUES-REGISTER #12](../testing/ISSUES-REGISTER.md) (FIXED commit 4b03b24):** every per-company CROSS-JOIN seed-uid for `gl_configs` (and any uid-bearing seed) MUST be bound ≤ `uid VARCHAR(26)`; the sanctioned pattern is `'XX' || lpad(company_id::text,6,'0') || substr(md5(key),1,12)` — **never** `… || config_key` (overflows for `OPENING_BALANCE_EQUITY`/`ACCOUNTS_RECEIVABLE`). The bug fires only on **keep-data** deploys (existing companies at migration time); CI/Testcontainers DBs have no companies so it is invisible to the suite — V14 honours the bound and `MigrationKeepDataIT` must extend to it. [[db-naming-convention]] verified against shipped V1–V13 (plural masters/logs, singular junctions, singular constraint roots `uq_`/`fk_`/`chk_`, plural index names `ix_`, `uid VARCHAR(26)` ULID, `company_id` scalar BIGINT, audit cols, partial-unique pattern, append-only posting, the `chk_*_source_type` / `chk_gl_config_key` additive `DROP/ADD CONSTRAINT` widen). **Latest shipped migration is `V13__cash_and_bank.sql` → VAT is `V14__vat_return.sql`** (additive; never edits V1–V13). Next ADR is 0018.

This ADR is the **technical data model + integration design** for the VAT Return / Tax module (T1.5, the last Tier-1 finance piece). It translates the ratified spec into tables, columns, types, keys, indexes, the enforcement split, the computation queries, the synchronous filing GL settlement, the **additive AP-bill split of input VAT to a new `VAT_INPUT` control account**, the **lean WHT touches** on the shipped AR-receipt / AP-payment cash legs (request-DTO fields + the GL postings), the credit carry-forward link, lifecycle/lock, permissions/audit/scope, and the `V14` migration ordering with the **#12-safe ≤26-char seed-uid pattern** — concrete enough that the backend engineer writes `V14__vat_return.sql` + the entities + the filing posting + the AP/AR/WHT touches **without guessing a business rule**. It writes **no production code, no entities, no migration SQL** — that is the engineer's next step. Nothing ratified is re-litigated.

## Context

The VAT return is the **bridge** from "VAT is computed on every invoice and bill" to "the company files one VAT return a month and knows what it owes TRA" (vat-return.md §1). The platform is shipped and consumed unchanged: GL gives the posting engine + `GLConfigResolver` + `chart_of_accounts` + `fiscal_periods`; Sales gives output VAT on `2200` (the `SalesPostingHandler` credits it on finalise); AP gives `supplier_bills.vatAmount` + `bill_date`; Cash & Bank routes the AR/AP cash legs the WHT touch rides; Money fixes base currency; `code_sequence` gives `VATR-####`/`WHT-####`. The central forces:

- **Output VAT already lives on `2200`; input VAT lives nowhere separable on the books (the load-bearing seam, OQ-VAT-01).** The `SalesPostingHandler` credits `VAT_PAYABLE` (2200) on every finalise (verified, shipped) — output VAT sits on a control account continuously. But the shipped AP bill-match **debits the bill's input VAT to that same `VAT_PAYABLE` (2200)** (`BillMatchServiceImpl:274-280`, the OQ-AP-04 stopgap), so 2200 today holds a *net* VAT position, not gross output, and input VAT is not separable. The return needs input VAT as a distinct figure that **reconciles to a control-account movement** (BR-VAT-08). The decision: introduce a `VAT_INPUT` recoverable asset account + key, and **make the AP bill-match split the input VAT onto it** (DR `VAT_INPUT`, not `VAT_PAYABLE`) — so input VAT sits on `1400` continuously, exactly as output sits on `2200`, and the filing journal simply nets the two control accounts. This is an **additive change to one shipped AP method**, not an AP rewrite. Resolved in D-7 (the decision) + D-5 (the accounts/keys).

- **The chief acceptance bar is GL reconciliation (BR-VAT-08, NFR-VAT-01).** A *filed* return must reconcile: period output == the period's `2200` movement; period input == the period's `VAT_INPUT` movement; the filing settlement entry's net == the return's net. With output on `2200` (Sales) and input on `VAT_INPUT` (the AP split, D-7), the return is a **read-and-net over two control accounts + manual adjustments + the opening credit**, posting **one balanced settlement journal on filing** (D-8). Resolved in D-6 (computation) + D-8 (settlement).

- **The settlement target — a dedicated VAT-due liability vs netting on `2200`.** Filing clears output and input; the net must land somewhere. Netting it back onto `2200` keeps `2200` as a single VAT liability but conflates "output VAT charged this period" with "net VAT owed to TRA after filing" and muddies the next period's output movement. A **dedicated `VAT_DUE` (VAT payable to TRA) liability** keeps `2200` purely the output-VAT control (clean per-period movement for BR-VAT-08) and gives the AP-payment-to-TRA a clear settlement account. Resolved in D-8 (recommend dedicated `VAT_DUE`).

- **WHT is an additive touch on the shipped cash legs, direction AP/AR → VAT/WHT (NFR-VAT-06).** The shipped AR-receipt and AP-payment services already resolve a cash/bank account and post the cash leg (verified). WHT rides those: an AP payment may withhold (CR `WHT_PAYABLE`, reduce cash); an AR receipt may be withheld-from (DR `WHT_RECEIVABLE`, reduce cash). The forces: keep the VAT/WHT module a leaf that AR/AP *call* for the WHT account + register write, never a module that imports AR/AP entities (mirror the Cash & Bank AR/AP touch, ADR-0016 D-8/D-11); add the WHT inputs as nullable request-DTO fields (the leanest additive shape, vat-return.md §3.7 flag). Resolved in D-9.

- **The return is a computed aggregate, not a sub-ledger.** Unlike AR/AP, the VAT return owns no per-transaction detail — it **reads** Sales/AP and stores period totals + a breakdown + manual adjustments. The forces: a stored lines table for the output breakdown vs computed-on-read with stored totals. Resolved in D-3 (stored totals + a stored per-band breakdown snapshot frozen at filing; adjustments as real rows; WHT as a real register).

- **Schema freeze / migration ordering.** IAM=V1 … Cash & Bank=V13 — all frozen and shipped. VAT is **additive `V14__vat_return.sql`**; it FKs only frozen `companies`/`branches`/`app_users`/`chart_of_accounts` (and intra-module VAT tables); it references `sales_invoices`/`supplier_bills`/`ar_receipts`/`ap_payments`/`journal_entries` by **scalar uid**, never a cross-module FK (the `stock_movements.source_document_uid` discipline). It adds CoA accounts + `gl_configs` keys + a source-type CHECK widen + the AP-bill posting change (Java, no schema) + the WHT request-DTO fields (Java, no schema) — all additive. **The #12 seed-uid bound is honoured** (D-12).

## Decision

### D-1 — Module placement: one `com.erp.modules.tax` module; controllers flat in `com.erp.api`

The VAT Return / WHT module lives under **`com.erp.modules.tax`** — a flat sibling of `gl`/`ar`/`ap`/`cashbank`/`sales`/`purchases` (PROJECT-CONVENTIONS §2; the same reasoning ADR-0013 D-1 used to reject a nested `accounting.gl`). **`tax`, not `vat`:** the module owns **two** statutory obligations on shared machinery — the **VAT return** (output−input net) **and** the **WHT register** (a separate withholding remittance, BR-VAT-12) — plus it is the natural home for the later additive tax pieces (cash-basis VAT, partial-exemption, WHT-by-type matrix, e-filing — NFR-VAT-08). `vat` alone would read as VAT-return-only and mis-home WHT. `tax` is the durable, flat name. Internal layout:

```
com.erp.modules.tax
├── domain.entity   VatReturn, VatReturnBand, VatAdjustment, WhtType, WhtTransaction
├── domain.dto      VatReturnDto, VatReturnBandDto, VatAdjustmentDto,
│                   OpenVatReturnRequest, RecomputeVatReturnRequest, FileVatReturnRequest,
│                   AddVatAdjustmentRequest,
│                   WhtTypeDto, WhtTransactionDto, WhtRegisterRowDto, WhtRegisterDto,
│                   CaptureWhtRequest                     (the AP/AR WHT touch carries WHT fields inline — D-9),
│                   VatReturnComputationDto               (output-by-band + input totals from the read queries — D-6)
├── domain.enums    VatReturnStatus (DRAFT|FILED),
│                   VatAdjustmentSign (INCREASE|DECREASE),
│                   VatAdjustmentReason (BAD_DEBT_RELIEF|PRIOR_PERIOD_CORRECTION|
│                                        CREDIT_NOTE_VAT|DEBIT_NOTE_VAT|OTHER),
│                   WhtKind (WHT_ON_PAYMENT|WHT_ON_RECEIPT)
├── repository      VatReturnRepository, VatReturnBandRepository, VatAdjustmentRepository,
│                   WhtTypeRepository, WhtTransactionRepository
├── service         VatReturnService(+Impl)        — open/recompute/file (the lifecycle + lock, D-4),
│                   VatReturnComputationReader      — output-by-band + input read queries (D-6),
│                   VatReturnFilingPoster           — the synchronous settlement post (D-8),
│                   VatAdjustmentService(+Impl)     — add/remove adjustment lines on a DRAFT (D-3),
│                   WhtTypeService(+Impl)           — manage WHT rates/types (lean, D-9),
│                   WhtCaptureService(+Impl)        — the AR/AP-called capture: register row + cert + GL accts (D-9),
│                   WhtRegisterQuery                — the period WHT register read (D-9),
│                   VatReturnNumberGenerator        — VATR-#### via code_sequence (D-11),
│                   WhtNumberGenerator              — WHT-#### via code_sequence (D-11)
└── (no events package — the return is human-prepared/filed and WHT is an in-request AR/AP touch;
     every GL post is synchronous — D-8, the AR/AP/Cash precedent, vat-return.md §4 note)
```

Controllers flat in `com.erp.api` — `VatReturnController`, `VatAdjustmentController`, `WhtTypeController`, `WhtRegisterController` — touching only services (`ModuleBoundaryTest`). **No outbox consumer** (the filing post is synchronous in-request, not an outbox auto-post — unlike GL's sales auto-poster; WHT capture is an in-request act on the AR/AP command — D-8/D-9).

### D-2 — The five table groups: `vat_returns` + `vat_return_bands`, `vat_adjustments`, `wht_types`, `wht_transactions`

All masters/logs plural; the band breakdown is a child of the return; the WHT type is a small per-company master. Every table carries `company_id` (BR-VAT-07) and participates in the §3.2 tenant predicate. The return is **company-level** (`branch_id` is a nullable analysis tag only on WHT, which rides a branch-tagged payment/receipt; the return itself is company-level — VAT is a company obligation, NFR-GL-01). Cross-module references (`journal_entries.uid`, `ar_receipts.uid` / `ap_payments.uid`, supplier/customer party) are **scalar VARCHAR(26)/BIGINT, no cross-module FK** (D-10). FKs into `chart_of_accounts`/`companies`/`branches`/`app_users` are intra-DB FKs to frozen masters.

> **Why stored totals + a stored per-band breakdown frozen at filing, not pure computed-on-read (the pick, D-3).** A DRAFT return is **recomputable** (FR-VAT-02) — its figures are derived from Sales/AP reads each time it is opened/recomputed. But a **FILED** return is **immutable and must reconcile to a fixed GL settlement entry forever** (BR-VAT-02/08): if its figures were always recomputed from the live sub-ledgers, a later-voided sale or a back-dated bill match would silently change a *filed* period's displayed output/input, breaking the "filed return == its GL entry" reconciliation bar. So the totals (`output_vat`, `input_vat`, `adjustments_total`, `opening_credit`, `net_vat`, `closing_credit`) and the per-band breakdown (`vat_return_bands`) are **stored on the return, frozen at FILE**, and are the authoritative figures thereafter; while DRAFT they are refreshed on each recompute. This is the GL-grade "freeze the figures on the irreversible act" pattern (the AR/AP `posted_gl_entry_uid` precedent). Pure computed-on-read is rejected because a filed statutory return cannot be allowed to drift.

#### (a) `vat_returns` (the monthly return — master, per company-month)

| column | type | null? | notes |
| --- | --- | --- | --- |
| `id` | `BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY` | NO | bands/adjustments FK this |
| `uid` | `VARCHAR(26)` | NO | ULID; `uq_vat_return_uid`; URLs address by uid; `ScopeGuard case "vatreturn"` (D-11) |
| `company_id` | `BIGINT` | NO | FK → `companies(id)`; tenant scope; never updated |
| `return_number` | `VARCHAR(30)` | NO | `VATR-####` from `code_sequence (company_id,'VAT_RETURN')` (D-11); `uq_vat_return_company_number` |
| `period_year` | `SMALLINT` | NO | calendar year, e.g. 2026; CHECK `BETWEEN 2000 AND 2100` |
| `period_month` | `SMALLINT` | NO | calendar month 1..12; CHECK `BETWEEN 1 AND 12` |
| `period_start` | `DATE` | NO | first day of the month (derived, stored for the range query — D-6) |
| `period_end` | `DATE` | NO | last day of the month; CHECK `period_end >= period_start` |
| `due_date` | `DATE` | NO | the **20th of the following month** (FR-VAT-01); carried for the operator (no auto-remind in v1) |
| `status` | `VARCHAR(10)` | NO | `VatReturnStatus`: `DRAFT`\|`FILED`; DEFAULT `'DRAFT'`; CHECK below |
| `output_vat` | `NUMERIC(19,4)` | NO | Σ output by band (D-6); DEFAULT 0; refreshed on recompute, frozen at FILE |
| `input_vat` | `NUMERIC(19,4)` | NO | Σ matched-bill input VAT in the period (D-6); DEFAULT 0 |
| `adjustments_total` | `NUMERIC(19,4)` | NO | signed Σ of `vat_adjustments` (INCREASE +, DECREASE −); DEFAULT 0 (D-3) |
| `opening_credit` | `NUMERIC(19,4)` | NO | credit carried IN from the prior period's `closing_credit` (D-4 carry-forward); DEFAULT 0; `>= 0` |
| `net_vat` | `NUMERIC(19,4)` | NO | `output_vat − input_vat + adjustments_total − opening_credit` (FR-VAT-06); **signed** (positive = payable, negative = credit) |
| `closing_credit` | `NUMERIC(19,4)` | NO | `net_vat < 0 ? −net_vat : 0` — the credit carried OUT to the next period (D-4); DEFAULT 0; `>= 0` |
| `prior_return_id` | `BIGINT` | YES | FK → `vat_returns(id)` (self) — the prior period's return this one carried its `opening_credit` from (the carry-forward link, D-4); NULL for the first return |
| `filing_reference` | `VARCHAR(80)` | YES | the operator-entered TRA acknowledgement reference (no e-filing in v1); NULL while DRAFT |
| `filing_date` | `DATE` | YES | the business filing date; NULL while DRAFT |
| `posted_journal_uid` | `VARCHAR(26)` | YES | the `journal_entries.uid` of the filing settlement entry (scalar, no FK — D-10); NULL while DRAFT |
| `filed_at` / `filed_by` | `TIMESTAMPTZ`/`BIGINT` | YES | set on FILE (`filed_by` → `app_users.id`) |
| `version` | `BIGINT` | NO | optimistic lock, DEFAULT 0 |
| audit cols | `TIMESTAMPTZ`/`BIGINT` | mixed | `created_at`/`created_by`/`updated_at`/`updated_by` (`*_by` → `app_users.id`, no FK — system-write pattern); a FILED return takes no further `update` beyond the file itself (BR-VAT-02, service-enforced) |

**Constraints:**
- `uq_vat_return_uid UNIQUE (uid)`; `uq_vat_return_company_number UNIQUE (company_id, return_number)`.
- **`uq_vat_return_company_period UNIQUE (company_id, period_year, period_month)`** — **one return per company per month** (BR-VAT-01); a second open for the same company-month is rejected at the DB (the finance-grade backstop; the service also checks).
- `fk_vat_return_company FOREIGN KEY (company_id) REFERENCES companies (id)`.
- `fk_vat_return_prior FOREIGN KEY (prior_return_id) REFERENCES vat_returns (id)` (self; nullable; the carry-forward link).
- `fk_vat_return_filed_by FOREIGN KEY (filed_by) REFERENCES app_users (id)`.
- `chk_vat_return_status CHECK (status IN ('DRAFT','FILED'))`.
- `chk_vat_return_period_month CHECK (period_month BETWEEN 1 AND 12)`; `chk_vat_return_period_year CHECK (period_year BETWEEN 2000 AND 2100)`; `chk_vat_return_dates CHECK (period_end >= period_start)`.
- `chk_vat_return_credits CHECK (opening_credit >= 0 AND closing_credit >= 0)`.
- `chk_vat_return_filed_fields CHECK ((status = 'DRAFT' AND filing_reference IS NULL AND filing_date IS NULL AND posted_journal_uid IS NULL) OR (status = 'FILED' AND filing_reference IS NOT NULL AND filing_date IS NOT NULL AND posted_journal_uid IS NOT NULL))` — a FILED return carries its filing ref + date + posted journal; a DRAFT carries none (the AP `chk_supplier_bill_number_when_posted` pattern).

**Indexes:**
```
CREATE INDEX ix_vat_returns_company        ON vat_returns (company_id);
CREATE INDEX ix_vat_returns_company_period ON vat_returns (company_id, period_year, period_month);  -- prior-period lookup (carry-forward) + the period list
CREATE INDEX ix_vat_returns_status         ON vat_returns (company_id, status);                      -- open-draft / filed lists
-- the one-per-month uniqueness is served by uq_vat_return_company_period
```

#### (b) `vat_return_bands` (the output breakdown by tax band — child of the return)

The output-VAT face by band (STANDARD 18 / ZERO_RATED 0 / EXEMPT — FR-VAT-03), stored so the FILED return's face is frozen (D-3). One row per band that has activity in the period.

| column | type | null? | notes |
| --- | --- | --- | --- |
| `id` | `BIGINT IDENTITY PK` | NO | |
| `vat_return_id` | `BIGINT` | NO | FK → `vat_returns(id)` |
| `company_id` | `BIGINT` | NO | FK → `companies(id)`; denormalised (tenant predicate) |
| `tax_band` | `VARCHAR(20)` | NO | the `tax_rates` band code: `STANDARD`\|`ZERO_RATED`\|`EXEMPT` (read from Sales `tax_summary` — D-6); CHECK below |
| `taxable_base` | `NUMERIC(19,4)` | NO | the net (tax-exclusive) sales in this band for the period; DEFAULT 0 |
| `output_vat` | `NUMERIC(19,4)` | NO | the output VAT in this band (Σ of the band's `tax_summary` amounts); DEFAULT 0 |
| `created_at`/`created_by` | `TIMESTAMPTZ`/`BIGINT` | mixed | append-only; rebuilt on recompute (delete+reinsert the band rows for a DRAFT) |

- `uq_vat_return_band UNIQUE (vat_return_id, tax_band)` — one row per band per return.
- `fk_vat_return_band_return FOREIGN KEY (vat_return_id) REFERENCES vat_returns (id)`; `fk_vat_return_band_company FOREIGN KEY (company_id) REFERENCES companies (id)`.
- `chk_vat_return_band CHECK (tax_band IN ('STANDARD','ZERO_RATED','EXEMPT'))`.
- `chk_vat_return_band_amounts CHECK (taxable_base >= 0 AND output_vat >= 0)`.
- Index: `ix_vat_return_bands_return (vat_return_id)`.

> **Note — the bands table is a frozen snapshot, the return's `output_vat` is its sum.** `Σ vat_return_bands.output_vat == vat_returns.output_vat` (service-maintained on recompute). On a DRAFT recompute, the service deletes the return's band rows and reinserts them from the fresh read (D-6); on FILE the rows are frozen with the return. The breakdown is a stored snapshot (not computed-on-read) for the same BR-VAT-02/08 reason as the totals (D-3).

#### (c) `vat_adjustments` (manual adjustment lines on a DRAFT)

| column | type | null? | notes |
| --- | --- | --- | --- |
| `id` | `BIGINT IDENTITY PK` | NO | |
| `uid` | `VARCHAR(26)` | NO | ULID; `uq_vat_adjustment_uid`; `ScopeGuard case "vatadjustment"` (D-11) |
| `vat_return_id` | `BIGINT` | NO | FK → `vat_returns(id)` — the DRAFT return adjusted |
| `company_id` | `BIGINT` | NO | FK → `companies(id)`; denormalised (tenant predicate) |
| `reason` | `VARCHAR(30)` | NO | `VatAdjustmentReason`: `BAD_DEBT_RELIEF`\|`PRIOR_PERIOD_CORRECTION`\|`CREDIT_NOTE_VAT`\|`DEBIT_NOTE_VAT`\|`OTHER`; CHECK below |
| `sign` | `VARCHAR(10)` | NO | `VatAdjustmentSign`: `INCREASE`\|`DECREASE` (the effect on net VAT — FR-VAT-05); CHECK below |
| `amount` | `NUMERIC(19,4)` | NO | the always-positive magnitude; CHECK `> 0`; the signed contribution to `adjustments_total` is `sign = INCREASE ? +amount : −amount` |
| `narrative` | `VARCHAR(255)` | YES | the operator's free-text note (audited) |
| `version` | `BIGINT` | NO | DEFAULT 0 |
| audit cols | `TIMESTAMPTZ`/`BIGINT` | mixed | standard; added/removed only while the return is DRAFT (BR-VAT-09, service-enforced) |

- `uq_vat_adjustment_uid UNIQUE (uid)`.
- `fk_vat_adjustment_return FOREIGN KEY (vat_return_id) REFERENCES vat_returns (id)`; `fk_vat_adjustment_company FOREIGN KEY (company_id) REFERENCES companies (id)`.
- `chk_vat_adjustment_reason CHECK (reason IN ('BAD_DEBT_RELIEF','PRIOR_PERIOD_CORRECTION','CREDIT_NOTE_VAT','DEBIT_NOTE_VAT','OTHER'))`.
- `chk_vat_adjustment_sign CHECK (sign IN ('INCREASE','DECREASE'))`.
- `chk_vat_adjustment_amount CHECK (amount > 0)`.
- Indexes: `ix_vat_adjustments_return (vat_return_id)`; `ix_vat_adjustments_company (company_id)`.

> **Adjustments are real rows; the DRAFT-only / FILED-locked rule is service-enforced.** No DB CHECK can see the parent return's status from the adjustment row at insert time without a trigger; v1 keeps it in the service (`VatAdjustmentService` rejects add/remove if the parent is FILED — BR-VAT-09), consistent with the GL/AR/AP service-owned cross-row invariants. `adjustments_total` on the return is recomputed from the live adjustment rows on each recompute and frozen at FILE.

#### (d) `wht_types` (the lean WHT rate/type master — per company)

| column | type | null? | notes |
| --- | --- | --- | --- |
| `id` | `BIGINT IDENTITY PK` | NO | |
| `uid` | `VARCHAR(26)` | NO | ULID; `uq_wht_type_uid`; `ScopeGuard case "whttype"` (D-11) |
| `company_id` | `BIGINT` | NO | FK → `companies(id)`; tenant scope |
| `code` | `VARCHAR(30)` | NO | operator code, e.g. `WHT-2PCT`, `WHT-VAT`; `uq_wht_type_company_code` |
| `name` | `VARCHAR(120)` | NO | e.g. "2% Withholding", "Withholding VAT" |
| `kind` | `VARCHAR(20)` | NO | `WhtKind`: `WHT_ON_PAYMENT` (we withhold paying a supplier → a liability) \| `WHT_ON_RECEIPT` (a customer withholds paying us → a receivable); CHECK below |
| `rate_pct` | `NUMERIC(9,4)` | NO | the withholding rate as a percent, e.g. 2.0000; CHECK `>= 0` |
| `active` | `BOOLEAN` | NO | DEFAULT TRUE; an inactive type takes no new capture |
| `version` | `BIGINT` | NO | DEFAULT 0 |
| audit cols | … | mixed | standard |

- `uq_wht_type_uid UNIQUE (uid)`; `uq_wht_type_company_code UNIQUE (company_id, code)`.
- `fk_wht_type_company FOREIGN KEY (company_id) REFERENCES companies (id)`.
- `chk_wht_type_kind CHECK (kind IN ('WHT_ON_PAYMENT','WHT_ON_RECEIPT'))`.
- `chk_wht_type_rate CHECK (rate_pct >= 0)`.
- Index: `ix_wht_types_company (company_id)`.

> **WHT type is a thin master, not the full matrix (OQ-VAT-02).** v1 stores a small set of owner-configurable rate/types (the 2% withholding + withholding VAT), each with a `kind` (payment vs receipt) and a `rate_pct`. The full Tanzanian WHT matrix (payment type × residency × treaty) is deferred — the model does not preclude it (additional columns / a richer type table land additively). The `rate_pct` is a convenience default; the actual withheld amount is captured per transaction (an operator may override), so a rate change does not retro-alter past captures.

#### (e) `wht_transactions` (the WHT register — one row per withholding event; the WHT certificate)

| column | type | null? | notes |
| --- | --- | --- | --- |
| `id` | `BIGINT IDENTITY PK` | NO | |
| `uid` | `VARCHAR(26)` | NO | ULID; `uq_wht_transaction_uid`; `ScopeGuard case "whttransaction"` (D-11) |
| `company_id` | `BIGINT` | NO | FK → `companies(id)`; tenant scope |
| `branch_id` | `BIGINT` | YES | FK → `branches(id)`; analysis tag (the originating payment/receipt's branch) |
| `wht_number` | `VARCHAR(30)` | NO | `WHT-####` from `code_sequence (company_id,'WHT')` (D-11); the **certificate number**; `uq_wht_transaction_company_number` |
| `wht_type_id` | `BIGINT` | NO | FK → `wht_types(id)` — the rate/type applied |
| `kind` | `VARCHAR(20)` | NO | `WhtKind`: `WHT_ON_PAYMENT`\|`WHT_ON_RECEIPT`; denormalised from the type (the register groups on it); CHECK below |
| `party_kind` | `VARCHAR(10)` | NO | `SUPPLIER` (for WHT_ON_PAYMENT) \| `CUSTOMER` (for WHT_ON_RECEIPT); CHECK below |
| `party_id` | `BIGINT` | NO | the supplier/customer id (intra-DB FK to the frozen Parties master — `suppliers(id)` or `customers(id)`; resolved by `party_kind`); see note |
| `party_name` | `VARCHAR(160)` | NO | snapshot of the party display name (the certificate face; survives a later party rename) |
| `source_ref` | `VARCHAR(26)` | NO | the **`ap_payments.uid`** (WHT_ON_PAYMENT) or **`ar_receipts.uid`** (WHT_ON_RECEIPT) the WHT rode (scalar, no cross-module FK — D-10) |
| `taxable_base` | `NUMERIC(19,4)` | NO | the amount the withholding was computed on (the gross bill/invoice settled); CHECK `>= 0` |
| `wht_amount` | `NUMERIC(19,4)` | NO | the withheld amount (= cash reduction = the liability/receivable booked); CHECK `> 0` |
| `currency` | `VARCHAR(3)` | NO | = company base currency (BR-VAT-13) |
| `certificate_date` | `DATE` | NO | the WHT certificate date (= the payment/receipt date) |
| `journal_entry_ref` | `VARCHAR(26)` | YES | the `journal_entries.uid` of the AP-payment / AR-receipt entry the WHT leg is part of (scalar, no FK — the WHT leg rides the existing entry, D-9); set by the AR/AP caller |
| `version` | `BIGINT` | NO | DEFAULT 0 |
| audit cols | … | mixed | standard; append-only (a reversal is a contra capture, not an edit) |

- `uq_wht_transaction_uid UNIQUE (uid)`; `uq_wht_transaction_company_number UNIQUE (company_id, wht_number)`.
- `fk_wht_transaction_company`/`_branch`; `fk_wht_transaction_type FOREIGN KEY (wht_type_id) REFERENCES wht_types (id)`.
- `chk_wht_transaction_kind CHECK (kind IN ('WHT_ON_PAYMENT','WHT_ON_RECEIPT'))`.
- `chk_wht_transaction_party_kind CHECK (party_kind IN ('SUPPLIER','CUSTOMER'))`.
- `chk_wht_transaction_amounts CHECK (taxable_base >= 0 AND wht_amount > 0)`.
- Indexes:
  ```
  CREATE INDEX ix_wht_transactions_company        ON wht_transactions (company_id);
  CREATE INDEX ix_wht_transactions_company_period ON wht_transactions (company_id, certificate_date);  -- the period register read
  CREATE INDEX ix_wht_transactions_kind           ON wht_transactions (company_id, kind);                -- payable vs receivable split
  CREATE INDEX ix_wht_transactions_source         ON wht_transactions (company_id, source_ref);          -- trace to the payment/receipt
  CREATE INDEX ix_wht_transactions_party          ON wht_transactions (company_id, party_kind, party_id);
  ```

> **`party_id` as a kind-discriminated scalar, not two nullable FKs.** A WHT capture is against exactly one party (a supplier for a payment, a customer for a receipt). v1 stores `party_kind` + `party_id` + a `party_name` snapshot rather than two nullable FK columns (`supplier_id` / `customer_id`), keeping the register uniform and the certificate face self-contained. The `tax` module reads supplier/customer **via a Parties DTO** at capture time (the WHT caller passes the resolved party id + name — D-9) and stores the snapshot; it imports no Parties entity (NFR-VAT-06). (If a future round wants a hard FK, two nullable FK columns + a CHECK that exactly one is set is the additive shape; v1's kind-discriminated scalar is the lean choice that matches the lean-WHT mandate, OQ-VAT-02.)

### D-3 — The return is computed totals + a stored band snapshot + real adjustment rows; the DB-vs-service enforcement split

**Grain.** A `vat_returns` row is the unit (one per company-month). Its **totals are stored** (`output_vat`/`input_vat`/`adjustments_total`/`opening_credit`/`net_vat`/`closing_credit`), refreshed on each DRAFT recompute and **frozen at FILE** (D-2 note). The **output breakdown** is stored child rows (`vat_return_bands`), rebuilt on recompute, frozen at FILE. **Adjustments** are real rows (`vat_adjustments`), summed into `adjustments_total`. **WHT** is a separate register (`wht_transactions`), **not** part of the return's net (BR-VAT-12).

**The enforcement split (DB-can't / service-must) — the load-bearing table:**

| invariant | enforcement | mechanism |
| --- | --- | --- |
| BR-VAT-01 one return per company per month | **DB** | `uq_vat_return_company_period` (+ service pre-check for a clean error) |
| BR-VAT-02 a FILED return is immutable / period locked | **service + structural** | `VatReturnService` rejects recompute/adjust/refile on a FILED return; `chk_vat_return_filed_fields` makes a DRAFT-with-filing-ref / FILED-without impossible |
| BR-VAT-11 a period cannot be filed twice | **service** | `VatReturnService.file` rejects if `status = FILED` (the lock) |
| FR-VAT-06 net = output − input + adjustments − opening credit | **service** | `VatReturnService` computes `net_vat` (BigDecimal, NFR-VAT-02); `closing_credit = max(−net_vat, 0)` |
| BR-VAT-05 output only from FINALISED invoices in the period | **service (read query, D-6)** | `VatReturnComputationReader` sums `sales_invoices` WHERE `status='FINALISED'` AND `finalised_at` in [start,end] |
| BR-VAT-04 input only from matched/approved bills DATED in the period | **service (read query, D-6)** | sums `supplier_bills.vat_amount` WHERE matched/approved AND `bill_date` in [start,end] |
| BR-VAT-06 filing posts a balanced settlement entry | **structural (sync post)** | `GLPostingService.post` validates Σdebit==Σcredit; clears 2200 + VAT_INPUT, books net to VAT_DUE (D-8) |
| BR-VAT-08 a filed return reconciles to GL | **structural + IT** | output==2200 movement, input==VAT_INPUT movement, settlement net==return net; an IT pins it (D-8) |
| BR-VAT-09 adjustments only on a DRAFT, audited, signed | **service** | `VatAdjustmentService` rejects add/remove on a FILED return; `chk_vat_adjustment_sign`/`_amount` per row |
| BR-VAT-03 net credit carries forward | **service** | `closing_credit` → the next period's `opening_credit` + `prior_return_id` link (D-4) |
| BR-VAT-07 per-company isolation | **DB + service** | `company_id` NOT NULL + FK on every table; tenant predicate; `assertCanActIn` on every read path |
| BR-VAT-12 WHT books a liability/receivable, reduces cash, separate from the net | **service (the AR/AP touch)** | `WhtCaptureService` returns the WHT GL account + register row; the AR/AP leg balances cash − WHT (D-9) |
| BR-VAT-13 base-currency only | **service** | every amount currency == `companies.base_currency` |
| append-only / correct via next-period adjustment or reversal | **structural** | no delete path on a FILED return / posted journal; a correction is the next period's adjustment (BR-VAT-10) |

### D-4 — Return lifecycle: open → recompute (DRAFT) → file (lock + settlement) → carry credit forward

**Open (`VatReturnService.open`, FR-VAT-01, `VAT.RETURN.PREPARE`).** Create a `vat_returns` row for a company-month: allocate `VATR-####`; set `period_year`/`period_month`/`period_start`/`period_end`/`due_date` (the 20th of the next month); status `DRAFT`; resolve `opening_credit` + `prior_return_id` from the **immediately prior period's** return (the most recent FILED return for the company with the largest (year,month) < this period — its `closing_credit` becomes this `opening_credit`); run the first compute (below). `uq_vat_return_company_period` rejects a duplicate (BR-VAT-01).

**Recompute (`VatReturnService.recompute`, FR-VAT-02, `VAT.RETURN.PREPARE`).** Only while DRAFT. Re-run the read queries (D-6); rebuild the `vat_return_bands` rows (delete+reinsert); refresh `output_vat`/`input_vat`; re-sum `adjustments_total` from live `vat_adjustments`; recompute `net_vat`/`closing_credit`. **No GL post** (only filing posts — FR-VAT-08). A FILED return rejects recompute (BR-VAT-02).

**File (`VatReturnService.file`, FR-VAT-08, `VAT.RETURN.FILE`).** Only from DRAFT, in one TX:
1. Final recompute (the figures freeze as computed now).
2. Record `filing_reference` (operator-entered), `filing_date`, `filed_at`, `filed_by`.
3. Post the **synchronous GL settlement journal** via `VatReturnFilingPoster` → `GLPostingService.post` (D-8); store the returned `journal_entries.uid` in `posted_journal_uid`.
4. Set `status = FILED` (the lock). `closing_credit` is now frozen for the next period to carry.
5. Audit `VAT.RETURN.FILE` (with the GL entry uid + the carry-forward). A GL failure (missing `gl_configs` key, closed period) **rolls back the whole file** (NFR-VAT-04, BR-GL-10 / FR-VAT-09).

**Carry-forward (the link, BR-VAT-03/FR-VAT-07).** A net **credit** (`net_vat < 0`) sets `closing_credit = −net_vat` on the filed return. The **next** period's `open` reads the prior FILED return's `closing_credit` into its `opening_credit` and sets `prior_return_id` to it. **No GL post for the carry-forward itself** — it is a return-to-return link (the credit already sits on the VAT control accounts; the settlement journal in a credit period leaves a debit balance on `VAT_DUE`/credit on the controls that the next period's settlement absorbs — D-8). The chain `prior_return_id` makes the credit lineage auditable.

> **Filing in order — recommend "prior period must be FILED first" (OQ default).** vat-return.md leaves "can a period be filed out of order?" open. The **recommended default: a period may be opened/computed at any time, but may be FILED only after the immediately prior period is FILED** (so `opening_credit` is always a *frozen* figure, never a moving DRAFT). Rationale: the carry-forward chain (`prior_return_id`) is only sound if the prior `closing_credit` is locked; allowing out-of-order filing would let a later period file against a still-DRAFT (recomputable) prior credit. `VatReturnService.file` enforces "prior period (if any) is FILED" (a service check; the very first period has no prior). This is a recommended default the owner may relax to independent filing (flagged OQ-VAT — non-blocking; the chain is built either way).

### D-5 — The new CoA accounts + `gl_configs` keys (the VAT_INPUT seam + VAT_DUE + WHT accounts)

ADR-0017 adds **four** new CoA accounts + **four** new `gl_configs` keys (the `GlConfigKey` enum widens additively, as ADR-0013 D-13 anticipated). All seeded per company (D-12); resolved via `GLConfigResolver` (a missing/inactive mapping fails the post — BR-GL-10).

| `gl_configs` key (NEW) | CoA account (NEW) | type / normal balance | role |
| --- | --- | --- | --- |
| **`VAT_INPUT`** | `1400 VAT Input (Recoverable)` | ASSET / DEBIT | input VAT recoverable — the AP bill-match debits it (D-7); the filing journal credits it to clear the period (D-8) |
| **`VAT_DUE`** | `2300 VAT Due (Payable to TRA)` | LIABILITY / CREDIT | the net VAT payable to TRA the filing journal books (D-8); the AP-payment-to-TRA later debits it (deferred remittance path) |
| **`WHT_PAYABLE`** | `2400 WHT Payable (to TRA)` | LIABILITY / CREDIT | WHT we withheld on a supplier payment, to remit to TRA (D-9, WHT_ON_PAYMENT) |
| **`WHT_RECEIVABLE`** | `1500 WHT Receivable` | ASSET / DEBIT | WHT a customer withheld from us, a claim against our own tax (D-9, WHT_ON_RECEIPT) |

`1400`/`1500` slot into the asset range (after `1300 Inventory`); `2300`/`2400` into the liability range (after `2200 VAT Payable`, before `3000`). All four are seeded as **active** accounts a company may rename/keep; the key→account mapping is config, so the owner can remap (e.g. `VAT_DUE` → reuse `2200` if they prefer netting — D-8 Alternatives) by one `gl_configs` row with no code change.

### D-6 — The computation queries (accrual basis, the read level)

`VatReturnComputationReader` (a `tax`-module service, `@Transactional(readOnly=true)`) reads Sales + AP as **DTO / scalar-id projections** (NFR-VAT-06), company-scoped (`assertCanActIn`). Two reads, both by **business date in [period_start, period_end]** (accrual basis):

**Output VAT by band (FR-VAT-03, BR-VAT-05).** From `sales_invoices` where `company_id = :companyId` AND `status = 'FINALISED'` AND `finalised_at::date BETWEEN :start AND :end`, sum `vat_total_amount` and the per-band amounts from the `tax_summary` JSONB. The per-band breakdown drives `vat_return_bands`; the total is `vat_returns.output_vat`. **Recommended shape:** a Sales-owned read method (`SalesInvoiceService.findVatSummaryForPeriod(companyId, start, end)` returning a `VatOutputSummaryDto { byBand: Map<band, {taxableBase, outputVat}>, total }`) — an additive Sales service method + DTO (the GL→Sales `findPostingTotalsByUidAndCompany` precedent), no Sales schema change; the `tax` module never imports a Sales entity. **Band sum discipline (OQ-VAT-08b):** sum the already-rounded per-invoice band amounts (no re-rounding of an already-computed line VAT), half-up, TZS 0 dp — so the band totals equal the source invoices' band totals and the `2200` movement (BR-VAT-08).

**Input VAT (FR-VAT-04, BR-VAT-04).** From `supplier_bills` where `company_id = :companyId` AND `status IN ('MATCHED','APPROVED','PARTIALLY_PAID','PAID')` (a posted payable — a `DRAFT`/`HELD` bill is excluded) AND `bill_date BETWEEN :start AND :end`, sum `vat_amount`. **Recommended shape:** an AP-owned read method (`SupplierBillService.sumInputVatForPeriod(companyId, start, end)` returning the total) — an additive AP service method, no AP schema change; the `tax` module never imports an AP entity. The total is `vat_returns.input_vat`. **Partial-period bills (OQ-VAT-08a):** a bill **dated in the period but matched later** enters the period of its **bill date** (accrual); a bill matched after that period's return is FILED is corrected via the **next** period's adjustment (BR-VAT-10) — the read at recompute naturally includes any matched bill whose `bill_date` falls in the (still-DRAFT) period.

> **The reads agree with the control-account movements by construction (the reconciliation bar, BR-VAT-08).** Because the `SalesPostingHandler` credits `2200` from the same `vat_total_amount` and (after D-7) the AP bill-match debits `VAT_INPUT` from the same `vat_amount`, the period's `Σ sales_invoices.vat_total_amount` == the period's `2200` credit movement, and the period's `Σ supplier_bills.vat_amount` == the period's `VAT_INPUT` debit movement (subject to the OQ-VAT-08b rounding discipline). The return therefore *reconciles* to GL rather than re-deriving it; an IT pins the equality (D-8).

### D-7 — The additive AP-bill-posting change: split input VAT to `VAT_INPUT` (the recommended decision)

**Decision: change the shipped AP bill-match posting to debit the bill's input VAT to the new `VAT_INPUT` (1400) account instead of `VAT_PAYABLE` (2200).** This is the OQ-VAT-01 seam, resolved as recommended in vat-return.md §11 (book input VAT to a recoverable control account at bill-match, so the books carry input VAT continuously and the return reconciles to the period movement — mirroring how output VAT already sits on `2200`).

**The minimal additive AP touch (one shipped method, no AP schema change).** In `com.erp.modules.ap.service.BillMatchServiceImpl.postMatchedBillToGl` (verified, lines 274-280), the input-VAT line is currently:
```
DR VAT_PAYABLE (2200)  vat_amount     // the OQ-AP-04 stopgap — netted output vs input
```
Change it to:
```
DR VAT_INPUT (1400)    vat_amount     // input VAT now on its own recoverable control account
```
i.e. `glConfig.resolve(companyId, GlConfigKey.VAT_PAYABLE)` for the VAT line becomes `glConfig.resolve(companyId, GlConfigKey.VAT_INPUT)`. **Nothing else changes** — the net/gross/`PURCHASES`/`ACCOUNTS_PAYABLE` legs are unchanged; the bill match still posts `DR Purchases net · DR VAT_INPUT vat · CR AP gross`. The `ap_debit_notes` contra leg (CR VAT on a debit note, `ApDebitNoteServiceImpl`) likewise re-points its VAT leg from `VAT_PAYABLE` to `VAT_INPUT` (the contra of the bill — a debit note reduces a recoverable input, so `CR VAT_INPUT`). The `JournalSourceType` (`AP_BILL`/`AP_DEBIT_NOTE`) and all other AP behaviour are unchanged.

**Why split-at-bill-match over derive-at-filing (the Alternatives fork).**
1. **Symmetry with output VAT.** Output sits on `2200` continuously (Sales auto-poster); making input sit on `VAT_INPUT` continuously means the books always carry the gross VAT position, and the return is a pure **read-and-net over two control accounts** — the cleanest reconciliation (BR-VAT-08).
2. **The filing journal is trivial and correct.** It clears `2200` (period output) and `VAT_INPUT` (period input) to `VAT_DUE` (the net) — every leg maps to a real prior movement. With derive-at-filing, input VAT would never be on the books until the filing journal invented it, and a period with no return filed would show input VAT nowhere — a misleading balance sheet between bill-match and filing.
3. **It is genuinely additive and reversible.** One `glConfig.resolve` key swap in one method (+ the debit-note contra); the key is config, so if the owner ever wanted the net-on-2200 model they remap `VAT_INPUT` → `2200` with no code change. No AP table, DTO, or flow changes.

**Migration consideration (historical bills).** Bills matched **before** V14 debited their input VAT to `2200` (the old behaviour); bills matched **after** V14 debit `VAT_INPUT`. v1 does **not** retro-reclassify historical `2200` input-VAT debits to `1400` (a data migration of posted journals would violate append-only — BR-GL-02). The consequence: the **first** VAT return after go-live may include input from pre-V14 bills whose VAT sits on `2200` not `1400`, so its input figure (read from `supplier_bills.vat_amount`) will not perfectly equal the `VAT_INPUT` movement for that transitional period. **Recommended handling:** seed `VAT_INPUT` and switch the AP posting at the **same** go-live as the VAT module, and treat the VAT return as authoritative from the first full period after the switch; a transitional opening adjustment (a manual `vat_adjustments` line or a one-off reclassifying manual journal `DR VAT_INPUT / CR VAT_PAYABLE` for the pre-switch input balance) reconciles the opening position. Flag this in go-live runbook (OQ — non-blocking; the steady state is clean).

### D-8 — The filing GL settlement posting (synchronous, exact lines + keys)

On FILE, `VatReturnFilingPoster` posts **one balanced journal** via the shipped `GLPostingService.post(JournalEntryDraft)` (sync, same TX as the lock — NFR-VAT-04; the AR/AP/Cash precedent), `sourceType = VAT_RETURN` (NEW — D-13), `sourceRef = vat_returns.uid`, `postingDate = filing_date` (must fall in an OPEN fiscal period — BR-GL-03 / FR-VAT-09). The journal **settles the period's VAT control accounts to the VAT-due liability:**

Let `O = output_vat`, `I = input_vat`, `A = adjustments_total` (signed), `OC = opening_credit`, `net = O − I + A − OC`.

| leg | account (`gl_configs` key) | debit | credit | meaning |
| --- | --- | --- | --- | --- |
| 1 | `VAT_PAYABLE` (2200) | `O` | | clear the period's output VAT off the output control account |
| 2 | `VAT_INPUT` (1400) | | `I` | clear the period's input VAT off the recoverable control account |
| 3 | `VAT_DUE` (2300) | (if net < 0) `−net` | (if net > 0) `net` | book the **net payable to TRA** (credit) or the **carried credit** (debit) |
| 4* | `VAT_DUE` (2300) | (OC > 0) part of the net | | the opening credit is already absorbed in `net`; see note |

**The balanced shape, by case** (the adjustments + opening-credit fold into the net on leg 3; legs are emitted only when non-zero — a zero leg violates `chk_journal_line_one_side`):

- **Net payable (`net > 0`):** `DR VAT_PAYABLE O · CR VAT_INPUT I · CR VAT_DUE net` — and if `A` or `OC` make the arithmetic need a balancing leg, the adjustments are posted to `VAT_DUE` as part of `net` (see note). Σdebit (`O`) == Σcredit (`I + net`) because `net = O − I + A − OC`; when `A` and `OC` are zero this is exactly `O = I + net`. When `A`/`OC` are non-zero, an **adjustments/credit leg** carries the difference (note below).
- **Net credit (`net < 0`):** `DR VAT_PAYABLE O · DR VAT_DUE (−net) · CR VAT_INPUT I` — the `VAT_DUE` debit is the credit carried (a debit balance on `VAT_DUE` representing recoverable/owed-to-us, absorbed by the next period's settlement; the carry-forward link D-4 tracks it return-to-return). Balanced: `O + (−net) = I`.

> **Handling adjustments + opening credit in the settlement (the precise rule).** `output_vat` and `input_vat` map 1:1 to the `2200` and `VAT_INPUT` movements (legs 1–2). **Adjustments (`A`)** and the **opening credit (`OC`)** are *return-level* figures that do not correspond to a `2200`/`VAT_INPUT` movement, so they are posted to **`VAT_DUE`** as part of the net (they adjust what is owed to TRA, not the control-account movements). The engineer computes `net` first, then emits: leg 1 `DR VAT_PAYABLE O`; leg 2 `CR VAT_INPUT I`; and a **single balancing `VAT_DUE` leg** = `O − I` on the side that balances (`CR VAT_DUE (O−I)` if `O>I`, else `DR VAT_DUE (I−O)`). Because `A` and `OC` change `net` but **not** the control-account movements, v1's settlement clears the *control accounts* (O and I) to `VAT_DUE` — and the **`net` (incl. A and OC) is the figure the operator reconciles / pays TRA**, carried on the return, not a separate GL leg in v1. *Where the owner wants adjustments on the books*, an adjustment posts a small manual journal (e.g. `DR/CR VAT_DUE / CR/DR the adjustment counter account`) — recommended as the additive path; v1's settlement leg clears O and I to VAT_DUE and the return's `net`/`closing_credit` carry the A/OC effect (OQ — non-blocking; the control-account clearing + the balanced O/I/VAT_DUE entry is fixed; whether A posts a GL leg or rides the return figure is the owner's call, defaulting to riding the return figure for a lean v1). **The invariant that does not move:** the entry is **balanced** (`GLPostingService` enforces Σdebit==Σcredit), it **clears the period's output (2200) and input (VAT_INPUT)**, and it **books the result to VAT_DUE** (BR-VAT-06).

**Why a dedicated `VAT_DUE` (2300) over netting on `2200` (the Alternatives fork).** Netting the result back onto `2200` would keep `2200` as the single VAT liability but conflate "output VAT charged this period" (the next period's BR-VAT-08 movement) with "net VAT owed to TRA after filing" — muddying the per-period output reconciliation and giving the eventual VAT-payment-to-TRA no clean target. A **dedicated `VAT_DUE`** keeps `2200` purely the output control (its period movement == the return's output, cleanly), gives the deferred VAT-remittance path a clear account to debit, and reads correctly on the balance sheet (VAT due to TRA as a distinct liability). Recommended; remappable to `2200` by config if the owner prefers netting.

### D-9 — The WHT additive touches: AP-payment + AR-receipt (request DTO fields + GL postings)

WHT rides the **shipped** AR-receipt / AP-payment cash legs (verified: `ArReceiptServiceImpl.recordAndAllocate`, `ApPaymentServiceImpl.paySingle`/`paymentRun`). Direction is **AR/AP → tax** (mirror the Cash & Bank AR/AP touch, ADR-0016 D-8/D-11): AR/AP *call* a `tax`-module service to resolve the WHT account + write the register row + cert; the `tax` module imports no AR/AP entity (NFR-VAT-06).

**(1) Request DTOs gain optional WHT fields (additive nullable record components — back-compat overloads, the `cashBankAccountUid` precedent).**
- `RecordReceiptRequest` (AR) gains `String whtTypeUid` (nullable) + `BigDecimal whtAmount` (nullable) — a customer-withheld receipt names the WHT type and the withheld amount.
- `PaySingleBillRequest` and `PaymentRunRequest` (AP) each gain `String whtTypeUid` (nullable) + `BigDecimal whtAmount` (nullable) — we withhold on the payment.
- All absent (null) → no WHT (unchanged behaviour for existing callers — the receipt/payment posts exactly as today).

**(2) AR/AP resolve the WHT account + write the register through a `tax` service.** A NEW **`WhtCaptureService`** (`com.erp.modules.tax.service`):
- `WhtCaptureService.captureOnPayment(companyId, branchId, whtTypeUid, partySupplierId, partyName, apPaymentUid, taxableBase, whtAmount, currency, certificateDate, journalEntryUid, actorId)` → resolves the `wht_types` row (same company, active, `kind = WHT_ON_PAYMENT`), allocates `WHT-####`, inserts a `wht_transactions` row (the certificate), resolves `WHT_PAYABLE` (via `GLConfigResolver`), and returns a `WhtCaptureResultDto { whtPayableGlAccountId, whtTransactionUid, certificateNumber }`. **It does not post GL itself** — it returns the account id so the AP payment includes the WHT leg in its own entry (one entry, one TX).
- `WhtCaptureService.captureOnReceipt(...)` → symmetric, `kind = WHT_ON_RECEIPT`, resolves `WHT_RECEIVABLE`, returns `{ whtReceivableGlAccountId, … }`.
- The `tax` module reads the party display name via a Parties DTO (or the caller passes the already-resolved supplier/customer id + name — recommended, since AP/AR already hold the party); stores the `party_name` snapshot. Runs **in the AR/AP service TX** (atomic — NFR-VAT-04).

**(3) The GL postings (the WHT leg rides the existing AP/AR entry — no separate journal).**

*AP payment with WHT (`ApPaymentServiceImpl`, FR-VAT-10, BR-VAT-12).* The supplier is paid **less** by the withheld amount; the withheld amount is a liability to TRA. The payment's GL entry becomes:
```
DR ACCOUNTS_PAYABLE (2100)   gross        // the full payable settled (unchanged)
CR cash/bank gl_account      gross − wht  // the reduced cash actually paid (the chosen account, ADR-0016)
CR WHT_PAYABLE (2400)        wht          // the withheld amount owed to TRA
```
Balanced: `gross = (gross − wht) + wht`. The `ap_payment_allocations` still settle the bill at `gross` (the bill is fully settled; the WHT is a settlement of part of the cash via a tax liability). The cash leg the existing code posts (`CR cash gl_account amount`) is reduced by `wht`, and the WHT leg is appended; `amount` on the payment row stays the full settled amount, with the cash reduction reflected in the cash leg (the engineer threads `whtAmount` into `postPaymentToGl`).

*AR receipt with WHT (`ArReceiptServiceImpl`, FR-VAT-11, BR-VAT-12).* The customer pays **less** by the withheld amount; the shortfall is a receivable claim. The receipt's GL entry becomes:
```
DR cash/bank gl_account      received     // the reduced cash actually received (the chosen account)
DR WHT_RECEIVABLE (1500)     wht          // the withheld amount, a claim against our tax
CR ACCOUNTS_RECEIVABLE (1200) invoice settled  // the full amount the receipt settles against AR
```
Balanced: `(received) + wht = invoice-settled`. The receipt's allocation still reduces the AR open item(s) by the full settled amount; the cash actually received is `settled − wht`.

> **WHT amount discipline (BR-VAT-12, NFR-VAT-02).** The cash reduction **exactly equals** the WHT liability/receivable (`wht_transactions.wht_amount`). The `WhtCaptureService` computes/validates `wht_amount` (the operator may pass it directly, or it is `taxable_base × wht_types.rate_pct / 100` rounded half-up TZS 0 dp); a capture whose cash leg + liability/receivable do not net is a defect (the unhappy path, vat-return.md §7.4). WHT is **separate from the VAT output−input net** (it is its own remittance — the WHT register, FR-VAT-12); the VAT return never reads `wht_transactions`.

**(4) The WHT register read (`WhtRegisterQuery`, FR-VAT-12, `WHT.VIEW`).** `wht_transactions` for a company + period (by `certificate_date`), grouped by `kind`: `WHT_ON_PAYMENT` (the WHT-payable to remit to TRA) and `WHT_ON_RECEIPT` (the WHT-receivable claimed). `assertCanActIn` on the read. The register is the basis for remittance; e-filing is deferred (OQ-VAT-02).

### D-10 — Module boundary: `tax` is a leaf reader/poster; AR/AP depend on `tax` for the WHT touch; no cycle

`ModuleBoundaryTest` discipline (PROJECT-CONVENTIONS §2, NFR-VAT-06). The intended allow-set (the active ArchUnit rules today are controller→repository, service→controller, audit-repo isolation; the per-module acyclic rule is documented as the intended allow-set when it lands — the ADR-0016 D-11 stance):

- **`tax` → `gl.service.GLPostingService` + `gl.service.GLConfigResolver` + `gl.domain.dto.JournalEntryDraft` + `gl.domain.enums.JournalSourceType`** — the synchronous filing-settlement edge (D-8), leaf→service, DTO/service-interface only, never a GL entity beyond the posting contract. The AR/AP/Cash precedent — reuse the allow-rule.
- **`tax` → `gl.repository.ChartOfAccountRepository`** (or a GL service read) — only if a read of the control-account movement is wired for the reconciliation read (BR-VAT-08); the seed already maps the keys, so the resolver suffices. Document if used.
- **`tax` → `sales.domain.dto` + `SalesInvoiceService`** — the output-VAT-by-period read (D-6), the established GL→Sales DTO-read pattern; never a Sales entity. **One additive Sales touch:** a `findVatSummaryForPeriod` service method + `VatOutputSummaryDto` (no Sales schema change).
- **`tax` → `ap.domain.dto` + `SupplierBillService`** — the input-VAT-by-period read (D-6); never an AP entity. **One additive AP touch:** a `sumInputVatForPeriod` service method (no AP schema change) — **plus** the D-7 change to `BillMatchServiceImpl`/`ApDebitNoteServiceImpl` (a `glConfig.resolve` key swap, inside AP, no new edge).
- **`tax` → `parties.domain.dto`** — the WHT party-name read at capture (D-9), DTO-only; or the AR/AP caller passes the resolved party id + name (recommended — no `tax`→Parties edge needed).
- **`tax` → `companies`/`branches`/`app_users`/`chart_of_accounts` (intra-DB FKs)** — `vat_returns`/`vat_adjustments`/`wht_*` FK frozen masters (the accepted AR/AP `customers`/`suppliers` pattern). `wht_transactions.party_id` is a kind-discriminated scalar FK to `suppliers(id)`/`customers(id)` (intra-DB FK to a frozen Parties master).
- **AR → `tax.service.WhtCaptureService` + `tax.domain.dto.WhtCaptureResultDto`** — the WHT touch (D-9): a NEW **AR→tax** edge, DTO/service-only, never a `tax` entity.
- **AP → `tax.service.WhtCaptureService` + `tax.domain.dto.WhtCaptureResultDto`** — the same NEW **AP→tax** edge.
- **No outbox edge** (the filing post is synchronous, D-8; WHT is an in-request AR/AP touch). `tax` consumes no event.
- **No cross-module FK** into `sales_invoices`/`supplier_bills`/`ar_receipts`/`ap_payments`/`journal_entries`: `source_ref`/`journal_entry_ref`/`posted_journal_uid` are plain `VARCHAR(26)` scalars (the `stock_movements.source_document_uid` discipline).
- **No cycle.** The directions are: **`tax` → GL/Sales/AP** (reads + posting); **AR/AP → `tax`** (the WHT capture). These do **not** form a cycle: `tax` reads AP's *bill* data (`SupplierBillService.sumInputVatForPeriod`) and AP calls `tax`'s *WHT capture* (`WhtCaptureService`) — two distinct service surfaces, no mutual import of the same package in a loop. Concretely, `tax` depends on `ap.service.SupplierBillService` (a read) while `ap.service.ApPaymentServiceImpl` depends on `tax.service.WhtCaptureService` (a write) — the `ap` module both exposes a read to `tax` and consumes a `tax` service, which **is** a module-level cycle (`ap ↔ tax`) if the acyclic rule is strict. **Resolution (the cleaner direction):** the **input-VAT read does not need to call AP at all** — the `tax` module can compute input VAT by a **direct scalar read of `supplier_bills`** (the `vat_amount` + `bill_date` + `status` columns) via its own repository projection (a read-only native/JPQL query scoped by `company_id`, the `stock`-reads-`sales_invoices` precedent), exactly as it reads output without importing Sales entities. This keeps the **only** `tax`↔AP coupling one-directional: **AP → `tax`** (WHT capture). For output, prefer the Sales DTO method (Sales does not depend on `tax`, so `tax → sales` is acyclic); for input, prefer the **direct scalar projection** to avoid the `ap ↔ tax` cycle. Document the ArchUnit stance: `tax → gl.service`/`gl.domain.dto`, `tax → sales.domain.dto`/`SalesInvoiceService`, `tax` direct scalar read of `supplier_bills`/`sales_invoices` (no AP/Sales entity import); `ar/ap → tax.service.WhtCaptureService`/`tax.domain.dto`. No `tax → ap.service` edge (avoid the cycle).

> **The boundary decision in one line:** read **input VAT by a direct scalar projection of `supplier_bills`** (not via an AP service), so the only cross-edge with AP is the **AP → `tax`** WHT call — no `ap ↔ tax` cycle. Read **output VAT via a Sales DTO method** (Sales has no edge back to `tax`, so `tax → sales` is safe).

### D-11 — ScopeGuard additions + numbering (`code_sequence` kinds)

`ScopeGuard.companyIdOf` gains the `tax` target types (the verified switch + constructor-dep pattern — add five repository deps and five cases):
```java
case "vatreturn"       -> vatReturns.findCompanyIdByUid(uid);
case "vatadjustment"   -> vatAdjustments.findCompanyIdByUid(uid);
case "whttype"         -> whtTypes.findCompanyIdByUid(uid);
case "whttransaction"  -> whtTransactions.findCompanyIdByUid(uid);
```
(`vat_return_bands` has no `uid` — it is a child of the return, addressed via the parent; no ScopeGuard case.) Each backed by a `findCompanyIdByUid` projection. `ScopeGuard` gains the `tax` repository constructor deps (the accepted cross-cutting-spine pattern — same as the GL/AR/AP/Cash additions). `assertCanActIn` on **every read path** (NFR-VAT-01): the return face, the band breakdown, the adjustment list, the WHT register, the WHT-type list, and inside `WhtCaptureService` (so an AR/AP caller cannot capture against another company's WHT type).

**`code_sequence` kinds** (created on first use, no seeded row — the shipped pattern): `VAT_RETURN` (`VATR-####`), `WHT` (`WHT-####`, the certificate / register entry number). Both per-company, row-locked allocation (NFR-VAT-05).

### D-12 — Migration: additive `V14__vat_return.sql`, never a V1–V13 edit; ordering; the #12-safe seed-uid

IAM=V1 … Cash & Bank=V13 — all frozen. VAT is **`V14__vat_return.sql`**, purely additive. Ordering (FK dependencies):
1. **`vat_returns`** (FKs `companies`/`app_users`/self `prior_return_id`) → **`vat_return_bands`** (FKs `vat_returns`/`companies`) → **`vat_adjustments`** (FKs `vat_returns`/`companies`).
2. **`wht_types`** (FKs `companies`) → **`wht_transactions`** (FKs `companies`/`branches`/`wht_types`; `party_id` intra-DB FK to `suppliers`/`customers` is resolved by `party_kind` — v1 keeps `party_id` a plain BIGINT scalar with **no** hard FK, since a single column cannot FK two tables; the kind-discriminated scalar, D-2 note).
3. **Indexes** for all of the above (D-2).
4. **CoA seed** — add the four new accounts per existing company (CROSS JOIN; deterministic seed-uid; the V12 `5150 Purchases` pattern):
   - `1400 VAT Input (Recoverable)` ASSET/DEBIT, `1500 WHT Receivable` ASSET/DEBIT, `2300 VAT Due (Payable to TRA)` LIABILITY/CREDIT, `2400 WHT Payable (to TRA)` LIABILITY/CREDIT.
   - The CoA seed-uid uses the **account_code** suffix (short — `'VATC' || lpad(c.id::text,6,'0') || '1400'` = 14 chars, well under 26), the verified V12 shape; `ON CONFLICT (company_id, account_code) DO NOTHING`.
5. **`gl_configs` CHECK widen + key seed** — widen `chk_gl_config_key` (additive `DROP/ADD CONSTRAINT`, the union of all prior keys + `VAT_INPUT`/`VAT_DUE`/`WHT_PAYABLE`/`WHT_RECEIVABLE`); seed the four keys per company joining the just-seeded CoA on `(company_id, account_code)`. **The seed-uid MUST honour ISSUES-REGISTER #12** — bound ≤ `uid VARCHAR(26)`:
   ```
   'VTC' || lpad(coa.company_id::text, 6, '0') || substr(md5(m.config_key), 1, 12)
   ```
   = 3 + 6 + 12 = **21 chars** (the V12 `'APC' || lpad(…,6) || substr(md5(key),1,12)` pattern exactly). **Do NOT use `… || config_key`** — `WHT_RECEIVABLE` (14) / `WHT_PAYABLE` (11) / `VAT_INPUT` (9) would overflow with a longer prefix, the #12 class of bug. `ON CONFLICT (company_id, config_key) DO NOTHING`.
6. **`journal_*` source-type CHECK widen** — add **`VAT_RETURN`** (and reserve `WHT` if a WHT capture ever posts a *separate* journal — in v1 the WHT leg rides the AP/AR entry under `AP_PAYMENT`/`AR_RECEIPT`, so **only `VAT_RETURN` is needed** for the filing settlement). Additive `DROP/ADD CONSTRAINT` on `journal_batches` + `journal_entries` (the union of all prior source values + `VAT_RETURN`), the sanctioned ADR-0013 D-13 pattern; never a V10–V13 edit.
7. **Permission seed** (`ON CONFLICT (code) DO NOTHING`) + `ORG_ADMIN` `role_permission` CROSS-JOIN grant (the V7/V11/V12 pattern):
   - `VAT.VIEW`, `VAT.RETURN.PREPARE`, `VAT.RETURN.FILE`, `VAT.ADJUST`, `WHT.VIEW`, `WHT.MANAGE`.

No `code_sequence` row seeded (kinds created on first use). No outbox table, no FK into `domain_events`/`sales_invoices`/`supplier_bills`/`ar_receipts`/`ap_payments`/`journal_entries` (cross-module scalars). No trigger. **No frozen-table ALTER** — unlike Cash & Bank (which added nullable columns to `ar_receipts`/`ap_payments`), the VAT/WHT touch needs **no schema change to AR/AP tables**: the WHT inputs are request-DTO fields (Java), the WHT register is the new `wht_transactions` table (linked by scalar `source_ref`), and the AP-bill VAT split (D-7) is a Java key-swap in `BillMatchServiceImpl` (no column change). Table style follows shipped V10–V13 exactly (`BIGINT GENERATED BY DEFAULT AS IDENTITY`, `uid VARCHAR(26)`, plural tables, singular constraint roots, plural `ix_`, `NUMERIC(19,4)` money). All FK targets exist in frozen V1/V10.

> **#12 regression coverage.** `MigrationKeepDataIT` (the IT that migrates to V9, inserts an org+company, then migrates to head — the gap that hid #12) **must extend to V14**: the four `gl_configs` seed-uids and the four CoA seed-uids must be asserted ≤ 26 chars against a DB that already has a company at migration time (the keep-data path the CI ephemeral DB does not exercise). The CoA seed-uid (`'VATC'+6+code` = 14) and the `gl_configs` seed-uid (`'VTC'+6+12` = 21) are both safely under the bound by construction; the IT pins it so a future key rename cannot silently reintroduce the overflow.

### D-13 — `JournalSourceType` + permission catalogue + audit emit points

**`JournalSourceType`** gains **`VAT_RETURN`** (the filing settlement source). The WHT leg rides the existing `AP_PAYMENT` / `AR_RECEIPT` entries (no new source type for WHT in v1). The DB `chk_journal_*_source_type` widen admits `VAT_RETURN` (D-12 step 6).

**Permissions (FR-VAT-15, seeded in V14, granted to `ORG_ADMIN`):**

| code | module | description |
| --- | --- | --- |
| `VAT.VIEW` | tax | View VAT returns (face + band breakdown) and the WHT register |
| `VAT.RETURN.PREPARE` | tax | Open / compute / recompute a DRAFT VAT return |
| `VAT.RETURN.FILE` | tax | File a VAT return — the lock + the synchronous GL settlement post |
| `VAT.ADJUST` | tax | Add / remove adjustment lines on a DRAFT return |
| `WHT.VIEW` | tax | Read WHT transactions / the WHT register |
| `WHT.MANAGE` | tax | Manage WHT rates/types; capture WHT on an AP payment / AR receipt; issue certificates |

The WHT capture on an AP payment / AR receipt is gated by **both** the existing `AP.PAYMENT.RUN` / `AR.RECEIPT.RECORD` (it is part of the payment/receipt command) **and** `WHT.MANAGE` (the withholding act) — the AR/AP service checks `WHT.MANAGE` when `whtTypeUid` is present (the additive gate; no WHT → no extra permission needed).

**Audit emit points (NFR-VAT-03 — every mutation, IAM append-only audit):**

| action | when | target_type / target |
| --- | --- | --- |
| `VAT.RETURN.PREPARE` | return opened / recomputed | `vat_returns` / id |
| `VAT.RETURN.FILE` | return filed (+ the GL settlement post + carry-forward) | `vat_returns` / id |
| `VAT.ADJUST` | adjustment added / removed | `vat_returns` / id (detail: the adjustment uid) |
| `WHT.TYPE.MANAGE` | WHT type created / edited / deactivated | `wht_types` / id |
| `WHT.CAPTURE` | WHT captured on a payment / receipt (actor = the AP/AR operator) | `wht_transactions` / id |

## Consequences

**Easier / safer:**
- **The last Tier-1 finance piece lands as a read-and-net, not a new ledger** (D-3/D-6): the return reads output (Sales `2200`) against input (AP `VAT_INPUT`), takes manual adjustments, and posts one balanced settlement on filing. It owns no per-transaction detail and never recomputes a line's VAT.
- **Input VAT finally has a home on the books** (D-7): the one-method AP key-swap (`VAT_PAYABLE` → `VAT_INPUT`) puts input VAT on a recoverable control account continuously, exactly as output sits on `2200` — so the return reconciles to two clean control-account movements (BR-VAT-08), and the filing journal is the trivial clear-both-to-`VAT_DUE`. The change is additive and config-reversible (remap the key).
- **A dedicated `VAT_DUE` keeps the books readable** (D-8): `2200` stays purely the output control (clean per-period movement), `VAT_DUE` is the distinct liability the company pays TRA — and the eventual VAT-remittance path has a clear account to debit.
- **The WHT touch is genuinely additive and lean** (D-9): two nullable request-DTO fields on each of three shipped DTOs, the WHT leg appended to the existing AP/AR entry (no separate journal), a `WhtCaptureService` AR/AP call (the Cash & Bank `CashTransactionRecorder` precedent), and a self-contained `wht_transactions` register. **No frozen-table ALTER at all** (D-12) — leaner than Cash & Bank's two nullable columns.
- **The lifecycle lock + carry-forward are structural** (D-4): `chk_vat_return_filed_fields` + the service make a FILED return immutable; `prior_return_id` + `closing_credit`→`opening_credit` make the credit chain auditable; "prior must be FILED first" keeps the chain sound.
- **The #12 seed-uid bound is honoured by construction** (D-12): the `gl_configs` seed-uid is the verified 21-char `'VTC'+lpad(6)+md5(12)` pattern, the CoA seed-uid is the 14-char code-suffix pattern — both under 26; `MigrationKeepDataIT` extends to V14.

**Harder / to watch:**
- **The split-input-VAT change touches a shipped, tested AP path** (D-7): `BillMatchServiceImpl.postMatchedBillToGl` and `ApDebitNoteServiceImpl` re-point one VAT leg. The AP ITs that asserted the old `DR VAT_PAYABLE` on a bill match **must be updated** to assert `DR VAT_INPUT` — a test change, not a behaviour regression, but it must land with V14 (the `VAT_INPUT` key must be seeded before the new posting runs, or a bill match fails with a missing-config error). Sequence: V14 seeds `VAT_INPUT` **and** the AP posting switches in the same release.
- **The transitional period** (D-7 migration note): pre-V14 bills' input VAT sits on `2200`, post-V14 on `1400`. The first return after go-live may need a one-off reclassifying manual journal / opening adjustment so its input reconciles. Documented in the go-live runbook; the steady state is clean.
- **Adjustments + opening credit do not map to a control-account movement** (D-8 note): v1's settlement clears O and I to `VAT_DUE` and the return's `net`/`closing_credit` carry the A/OC effect (the operator reconciles/pays the net). Whether an adjustment posts its own small GL journal is the owner's call (the recommended additive path); v1 defaults to the lean "A rides the return figure." Surface this in the reconciliation read.
- **The reconciliation bar is rounding-sensitive** (OQ-VAT-08b, NFR-VAT-02): the period output must sum the already-rounded per-invoice band amounts (no re-rounding) to equal the `2200` movement. An IT must pin output==`2200` movement and input==`VAT_INPUT` movement for a period.
- **The `ap ↔ tax` cycle is avoided only by the direct-scalar input read** (D-10): if a future engineer wires input VAT through an `ap.service` method instead of a direct `supplier_bills` projection, the module graph gains an `ap ↔ tax` cycle (AP calls `tax` for WHT; `tax` would call AP for input). The ADR fixes the direction: **read input by a direct scalar projection**, keep the only AP edge as **AP → `tax`**.

**Migration / delivery cost:**
- 1 additive Flyway file (`V14__vat_return.sql`): **5 new tables** (`vat_returns`, `vat_return_bands`, `vat_adjustments`, `wht_types`, `wht_transactions`) + FKs/uniques/CHECKs + ~14 indexes; **4 new CoA accounts**/company (`1400`/`1500`/`2300`/`2400`); **`gl_configs` CHECK widen + 4 key seeds**/company (the #12-safe 21-char seed-uid); **journal source-type CHECK widen** (`VAT_RETURN`); **6 permission seeds + grant**. **No frozen-table ALTER.** No outbox table, no `code_sequence` row, no trigger. Depends only on frozen V1/V10.
- Backend (`tax` module): the `com.erp.modules.tax` set per D-1 — 5 entities + enums, 5 repositories (each with `findCompanyIdByUid` + the direct `supplier_bills`/`sales_invoices` scalar projections for the reads), the services (return lifecycle + computation reader + filing poster + adjustment + WHT type + WHT capture + register/number generators), ~4 controllers. **No events handler.**
- Backend (AP touch — D-7): re-point the input-VAT leg in `BillMatchServiceImpl.postMatchedBillToGl` and `ApDebitNoteServiceImpl` from `VAT_PAYABLE` to `VAT_INPUT` (one `glConfig.resolve` key per method); update the AP ITs to assert `DR VAT_INPUT`.
- Backend (AR/AP WHT touch — D-9): add `whtTypeUid`/`whtAmount` to `RecordReceiptRequest`/`PaySingleBillRequest`/`PaymentRunRequest` (nullable, back-compat overloads); thread the WHT leg into `postPaymentToGl` / the receipt entry (reduce cash by `wht`, add the `WHT_PAYABLE`/`WHT_RECEIVABLE` leg); call `WhtCaptureService` to write the register + cert; gate on `WHT.MANAGE`. One AR→tax + one AP→tax service dependency.
- Backend (Sales touch — D-6/D-10): a `SalesInvoiceService.findVatSummaryForPeriod` read method + `VatOutputSummaryDto` (no Sales schema change). (Input VAT is a direct `supplier_bills` scalar projection in `tax` — no AP read method needed, D-10.)
- Backend (platform touch): `ScopeGuard` gains 4 `tax` cases + repo deps (D-11); `JournalSourceType` gains `VAT_RETURN`; ArchUnit allow-list gains the `tax → gl.service`/`sales.domain.dto` edges + the `ar/ap → tax.service.WhtCaptureService` edges (D-10).
- Web: VAT return list + open/recompute, the return face (output by band, input, adjustments, opening credit, net, payable-or-credit), the adjustment editor (DRAFT only), the file action (filing ref + date → lock + GL post), the WHT type master, the WHT register (period, payable vs receivable); AR receipt + AP payment screens gain an optional WHT type + amount input — `ApiResponse<T>`, Long-as-string, address by uid.
- Deployment risk: **low-medium** — additive on frozen schema (no ALTER), reuses the synchronous-posting machinery; the **one watch-item is the D-7 AP posting switch** (must land with the `VAT_INPUT` seed in the same release, and the AP ITs updated) and the transitional-period reclassification at go-live.

## Alternatives considered

- **Derive input VAT at filing (the filing journal first separates input VAT) instead of splitting it at AP bill-match (D-7).** AP keeps debiting `2200` (net VAT position); the VAT return reads `supplier_bills.vat_amount` and the filing journal is the first time input VAT touches a `VAT_INPUT` account. **Rejected (D-7):** input VAT would be on no recoverable account between bill-match and filing (a misleading balance sheet, and no continuous control-account symmetry with output on `2200`); the return could not reconcile input to a control-account movement (BR-VAT-08) for an unfiled period. The split-at-bill-match is one additive key-swap, gives continuous symmetry, and makes the filing journal a trivial clear-both-to-`VAT_DUE`. Both reconcile arithmetically; the split is the cleaner books.
- **Net the filing result onto `2200` (no dedicated `VAT_DUE`) (D-8).** Keep a single VAT liability account. **Rejected (D-8):** conflates "output VAT charged this period" (the next period's BR-VAT-08 movement) with "net owed to TRA after filing," muddying the per-period output reconciliation and leaving the VAT-remittance-to-TRA no clean target. A dedicated `VAT_DUE` keeps `2200` pure and reads correctly on the balance sheet; it remaps to `2200` by config if the owner prefers netting.
- **A `vat_return_lines` detail table (a row per source invoice/bill) instead of stored totals + a band snapshot (D-3).** Full line-level traceability on the return. **Rejected (D-3):** the return is a *periodic aggregate*, not a sub-ledger — Sales/AP already own the per-document detail, and a line table would duplicate it and risk drift. Stored totals + a frozen per-band breakdown (rebuilt on DRAFT recompute, frozen at FILE) give the statutory face and the reconciliation bar without re-owning the sub-ledgers; the drill-down to source documents is a Sales/AP read by period (the same query D-6 runs).
- **Pure computed-on-read totals (no stored figures) (D-3).** Always re-derive output/input from the live sub-ledgers. **Rejected (D-3):** a FILED statutory return must be immutable and reconcile to a fixed GL entry forever (BR-VAT-02/08); a later-voided sale or back-dated bill would silently change a filed period's displayed figures. Freezing the figures at FILE is the GL-grade pattern.
- **Full WHT-by-type matrix in v1 (payment type × residency × treaty) (D-2d).** Complete Tanzanian WHT modelling. **Rejected (OQ-VAT-02, owner-accepted lean v1):** v1 captures a small owner-configurable rate/type set (the 2% withholding + withholding VAT) booking a single `WHT_PAYABLE` + single `WHT_RECEIVABLE` account, with a register + certificate. The full matrix + WHT e-filing are deferred; the thin `wht_types` master does not preclude them (additional columns / a richer type table land additively).
- **A separate WHT journal (WHT leg in its own GL entry) instead of riding the AP/AR entry (D-9).** A standalone WHT post the payment/receipt references. **Rejected (D-9):** the WHT *is* part of the same money movement (the cash paid/received is reduced by the withheld amount in the same act); a separate journal would open a window where the cash leg and the WHT liability disagree and double the posting machinery. The WHT leg in the existing AP/AR entry (balanced: cash + WHT = gross) is atomic and minimal — the leanest correct shape.
- **A `tax → ap.service` input-VAT read (D-10).** Read input VAT via an AP service method (the symmetric Sales-DTO pattern). **Rejected (D-10):** it creates an `ap ↔ tax` module cycle (AP calls `tax` for WHT; `tax` would call AP for input). The direct scalar projection of `supplier_bills` in `tax` (the `stock`-reads-`sales_invoices` precedent) keeps the only AP edge one-directional (**AP → `tax`**), no cycle.

## Open / flagged items (do NOT block the build; recommended defaults stand — vat-return.md §11)

1. **OQ-VAT-01 — `VAT_INPUT` account + AP-input-VAT seam.** **Decided (D-5/D-7):** add `VAT_INPUT` (→ `1400`) + key; split input VAT to it at AP bill-match (the recommended default). The decision this ADR makes; not a blocker.
2. **OQ-VAT-02 — WHT scope depth.** **Default (D-2d/D-9):** lean — a small owner-configurable `wht_types` set (2% + WHT-VAT), single `WHT_PAYABLE`/`WHT_RECEIVABLE`, register + certificate; the full matrix + e-filing deferred. *Blocks build:* **NO.**
3. **OQ-VAT-03 — Adjustment sources.** **Default (D-3):** manual `vat_adjustments` lines (reason + amount + sign); auto-deriving bad-debt VAT relief from AR write-offs is a later additive convenience. *Blocks build:* **NO.**
4. **OQ-VAT-04 — Cash-basis VAT.** **Default (D-6):** accrual basis (output on finalise, input on bill date); cash-basis deferred. *Blocks build:* **NO.**
5. **OQ-VAT-05 — Multi-rate / historical rate changes.** **Default:** the three `tax_rates` bands; rate-effective-dating deferred. *Blocks build:* **NO.**
6. **OQ-VAT-06 — Partial-exemption / input apportionment.** **Default:** full input recovery from matched bills; apportionment deferred. *Blocks build:* **NO.**
7. **OQ-VAT-07 — Multi-currency VAT.** **Default (BR-VAT-13):** base currency (TZS); FX deferred. *Blocks build:* **NO.**
8. **OQ-VAT-08 — Partial-period bills + VAT rounding per band.** **Default (D-6):** (a) a bill enters the period of its **bill_date** (accrual); a bill matched after that period is FILED → next-period adjustment. (b) sum already-rounded per-invoice band amounts (no re-rounding), half-up, TZS 0 dp. *Blocks build:* **NO**; confirm before go-live.
9. **Filing order (D-4).** **Default:** a period may be FILED only after the immediately prior period is FILED (so the carried `opening_credit` is always frozen). Relaxable to independent filing by the owner. *Blocks build:* **NO.**
10. **Adjustments on the books (D-8 note).** **Default:** the settlement clears O and I to `VAT_DUE`; the return's `net`/`closing_credit` carry the adjustments + opening-credit effect (the operator reconciles/pays the net). Posting an adjustment as its own GL journal is the additive path if the owner wants A on the books. *Blocks build:* **NO.**
11. **Transitional input-VAT reclassification (D-7 note).** **Default:** seed `VAT_INPUT` + switch the AP posting at the same go-live; reconcile the opening position with a one-off `DR VAT_INPUT / CR VAT_PAYABLE` journal for pre-switch input. Go-live runbook item. *Blocks build:* **NO.**
12. **OQ-CUR-03 (carried) — Rounding & TZS decimals.** **Default:** HALF_UP, TZS 0 dp; the output/input sums, the net, the settlement legs, and the WHT legs round identically (`BigDecimal.compareTo`). *Blocks build:* **NO** for the model; confirm before go-live.

None of the above changes the five-table schema, the split-input-VAT decision, the dedicated-`VAT_DUE` settlement, the synchronous filing post, the lean WHT touch, or the #12-safe seed-uid; all are policy/tuning/additive choices the design is built to.

## Summary

This ADR is the technical design for **VAT Return / Tax Increment (T1.5)** — the **monthly accrual-basis VAT return + lean WHT** in `com.erp.modules.tax`, defined in additive **`V14__vat_return.sql`** (never editing frozen V1–V13). **Five tables:** `vat_returns` (one per company-month via `uq_vat_return_company_period`, `VATR-####`, stored totals `output_vat`/`input_vat`/`adjustments_total`/`opening_credit`/`net_vat`/`closing_credit`, `status DRAFT|FILED` with `chk_vat_return_filed_fields` lock, `prior_return_id` carry-forward link, `posted_journal_uid` scalar) + `vat_return_bands` (the output breakdown by `STANDARD|ZERO_RATED|EXEMPT`, a frozen snapshot); `vat_adjustments` (manual DRAFT lines — reason + sign + amount, audited); `wht_types` (the lean per-company rate/type master — `kind WHT_ON_PAYMENT|WHT_ON_RECEIPT`, `rate_pct`); `wht_transactions` (the WHT register + certificate — `WHT-####`, `kind`, `party_kind`+`party_id`+`party_name` snapshot, `source_ref`/`journal_entry_ref` scalars). **Computation (D-6, accrual):** output = Σ `sales_invoices.vat_total_amount` by band for FINALISED invoices in [start,end] (via a Sales DTO method); input = Σ `supplier_bills.vat_amount` for matched bills DATED in the period (a **direct scalar projection** in `tax`, not an AP service — avoids the `ap↔tax` cycle, D-10). **The VAT_INPUT seam (D-5/D-7, the load-bearing decision):** add CoA `1400 VAT Input` + key `VAT_INPUT`, and **change the shipped AP bill-match to debit input VAT to `VAT_INPUT` instead of `VAT_PAYABLE`** (one `glConfig.resolve` key-swap in `BillMatchServiceImpl.postMatchedBillToGl` + the debit-note contra) — so input VAT sits on a recoverable control account continuously, mirroring output on `2200`, and the return reconciles to two clean movements (BR-VAT-08). **Filing settlement (D-8, synchronous via `GLPostingService.post`, `sourceType VAT_RETURN`):** `DR VAT_PAYABLE (2200) output · CR VAT_INPUT (1400) input · DR/CR VAT_DUE (2300) the net` — a **dedicated `VAT_DUE` liability** (NEW `2300`/key), recommended over netting on `2200`, balanced, locks the return. **Credit carry-forward (D-4):** a net credit → `closing_credit` → the next period's `opening_credit` via `prior_return_id`; no GL post for the carry itself; file only after the prior period is FILED. **WHT touch (D-9, additive, AR/AP → tax):** nullable `whtTypeUid`/`whtAmount` on `RecordReceiptRequest`/`PaySingleBillRequest`/`PaymentRunRequest`; AP payment with WHT = `DR AP gross · CR cash (gross−wht) · CR WHT_PAYABLE (2400) wht`; AR receipt with WHT = `DR cash (settled−wht) · DR WHT_RECEIVABLE (1500) wht · CR AR settled` — the WHT leg **rides the existing AP/AR entry** (no separate journal); `WhtCaptureService` writes the register + certificate in the AR/AP TX. NEW CoA `1500`/`2400` + keys `WHT_RECEIVABLE`/`WHT_PAYABLE`. **Scope/security:** `ScopeGuard` gains `vatreturn`/`vatadjustment`/`whttype`/`whttransaction`; `assertCanActIn` on every read path (incl. `WhtCaptureService`); perms `VAT.VIEW`/`VAT.RETURN.PREPARE`/`VAT.RETURN.FILE`/`VAT.ADJUST`/`WHT.VIEW`/`WHT.MANAGE`; audit on prepare/file/adjust/WHT capture. **`code_sequence` kinds** `VAT_RETURN`/`WHT`. **Module boundary (D-10):** `tax → gl.service.GLPostingService`/`GLConfigResolver` + `sales.domain.dto`/`SalesInvoiceService` + direct scalar reads of `supplier_bills`/`sales_invoices`; `ar/ap → tax.service.WhtCaptureService`; **no `ap↔tax` cycle**; no outbox consumer; no cross-module FK (scalar uids). **Migration ordering (D-12):** tables → indexes → CoA seed (4 accounts, code-suffix seed-uid ≤14) → `gl_configs` CHECK widen + 4 key seeds (**the #12-safe `'VTC' || lpad(company_id::text,6,'0') || substr(md5(config_key),1,12)` = 21-char seed-uid — NEVER `|| config_key`**) → journal source-type CHECK widen (`VAT_RETURN`) → permission seed + grant; `MigrationKeepDataIT` extends to V14. **Ready for build:** the five tables, the computation reads, the split-input-VAT AP change, the filing settlement (exact legs + the four NEW accounts/keys + `VAT_DUE` vs net-on-2200 decided), the lean WHT AR/AP touches (request DTOs + GL legs + register), the carry-forward link, the lifecycle/lock, scope/perms/audit, and the migration ordering are concrete enough to write without guessing a business rule. **Additive on frozen V1–V13:** confirmed — 5 new tables, 4 new CoA accounts, 4 new `gl_configs` keys, additive journal-source-type CHECK widen (the sanctioned `chk_gl_config_key`/`chk_*_source_type` pattern), **NO frozen-table ALTER** (the WHT touch is request-DTO fields + a new register table + a Java key-swap, not a column change), and the **ISSUES-REGISTER #12 ≤26-char seed-uid pattern is honoured** by construction (`md5`-bounded `gl_configs` uid, code-suffix CoA uid) with `MigrationKeepDataIT` coverage extended to V14.
