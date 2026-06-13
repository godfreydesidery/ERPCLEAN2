# FX/Multi-currency + #20d — codebase recon (drives ADR-0036)


## money-currency-model

## Verdict on the core question

NO entity stores an exchange_rate or base_amount today. A full-codebase grep for `exchange_rate|exchangeRate|base_amount|baseAmount|fx_rate|fxRate|rate_to_base|base_currency_amount` returns ZERO hits in `backend/`. Everything is single-currency at an implied rate of 1. The base-currency triple (base_amount, rate, rate_at) that ADR-0005 D-5 reserves was deliberately NOT built (D-8 defers it). There is also NO Currency master entity/table yet (grep for `class Currency` / `@Table("currencies")` = none) â€” currency codes are unvalidated free `CHAR(3)`/`VARCHAR(3)` strings.

## The two co-existing money representations

There are TWO distinct money patterns in the code, and the transactional tables do NOT use the ADR-0005 embeddable:

1. `Money` @Embeddable (ADR-0005 D-1) â€” `com.erp.platform.common.money.Money` (also a deprecated duplicate at `com.erp.modules.parties.domain.dto.MoneyDto`). Fields: `amount` BigDecimal (NUMERIC(19,4)) + `currency` String CHAR(3). Value object, both-null-or-both-set, equality by `compareTo`+code. Crucially the javadoc states arithmetic (plus/minus/compareTo) is intentionally OMITTED and "the FX engine that makes them safe is deferred (ADR-0005 D-8)". So `Money` has NO conversion/arithmetic today.
   - Embedded in only 3 master entities: `Product.cost`, `ProductPrice.price`, `Customer.creditLimit`. NOT used on any transactional header/line.

2. Bare `currency` String column + plain `BigDecimal` amount columns â€” this is what ALL transactional entities use. The header carries ONE `currency` column and every amount is a sibling `*_amount` BigDecimal sharing that single currency (the "single header currency" rule). This is technically a deviation from ADR-0005 D-1 (which mandated `Money` everywhere), but it is the de-facto pattern for sales/AP/AR/GL.

## Exact columns/fields that carry currency on transactional entities

- SalesInvoice (`sales_invoices`, `SalesInvoice.java`): `currency` CHAR(3) NOT NULL (field `private String currency`, set via constructor from `req.currency()`); amounts `doc_discount_amount`, `net_total_amount`, `vat_total_amount`, `gross_total_amount` (all BigDecimal NUMERIC(19,4), no currency of their own). Doc says "All monetary columns share this currency (BR-SALES-04)".
- SalesInvoiceLine (`sales_invoice_lines`): `currency` denormalised from parent invoice at construction (`this.currency = invoice.getCurrency()`); amounts `list_price_amount`, `unit_price_amount`, `line_discount_amount`, `net_amount`, `vat_amount`, `gross_amount`.
- SupplierBill (`supplier_bills`, AP): `currency` VARCHAR(3) NOT NULL updatable=false; amounts `net_amount`, `vat_amount`, `gross_amount`, `outstanding_amount`.
- ApPayment (`ap_payments`): `currency` VARCHAR(3) NOT NULL updatable=false; amount `amount`.
- ArReceipt (`ar_receipts`): `currency` VARCHAR(3) NOT NULL updatable=false; amounts `amount`, `unallocated_amount`.
- JournalLine (`journal_lines`, GL): `currency` CHAR(3) NOT NULL per line; amounts `debit_amount`, `credit_amount`. Entity javadoc: "All amounts in company base currency." Append-only (no updated_* cols).
- Allocation tables (ArReceiptAllocation, ApPaymentAllocation, BillMatch) carry amounts but inherit currency from their parent doc.

## Base-currency config (ADR-0005 D-4)

`Company.baseCurrency` â€” `com.erp.modules.iam.domain.entity.Company`, column `base_currency VARCHAR(3) NOT NULL DEFAULT 'TZS'`, Java default `= "TZS"`, with `@Setter`. Added by migration V10 (`ALTER TABLE companies ADD COLUMN base_currency VARCHAR(3) NOT NULL DEFAULT 'TZS'`). Read via `Company.getBaseCurrency()`.

## Where currency is set / validated (the seam logic today)

- SalesInvoiceServiceImpl.create(): currency comes straight from the REQUEST (`new SalesInvoice(..., req.currency(), ...)`) â€” it is NOT defaulted to or checked against `company.getBaseCurrency()` at the sales layer. SalesInvoiceServiceImpl line 518 only checks payment currency == invoice currency.
- ArReceiptServiceImpl.create() (line 122-124): derives currency as `companies.findById(companyId).map(c -> c.getBaseCurrency()).orElse("TZS")` â€” i.e. AR receipts are forced to base currency. This `.orElse("TZS")` hard-coded fallback recurs in ArAgeingQuery (61,84), ArCreditNoteServiceImpl (88), ArBalanceServiceImpl (42), ArReceiptServiceImpl (123), ArOpeningBalanceServiceImpl (74), ArWriteOffServiceImpl (86), ArReconciliationQuery (48) â€” a known violation of ADR-0005 D-4's "never hard-code TZS" rule and an FX touch point.
- GLPostingServiceImpl.post() is the ENFORCEMENT chokepoint: `resolveBaseCurrency(companyId)` reads `company.getBaseCurrency()`, then `validateLine()` THROWS `IllegalArgumentException` if `!baseCurrency.equals(ld.currency())` (BR-GL-06, D-9). So today the GL layer HARD-REJECTS any journal line not in company base currency. Every poster (sales, AP bill match, AR receipt/credit-note/write-off/opening-balance) passes the document `currency` into `LineDraft.currency` â€” and since documents are base-currency in practice, it passes.

## DTO fields that carry currency on the wire

- MoneyDto record `(String amount, String currency)` â€” amount as STRING to avoid JS precision loss (ADR-0005 D-7). `from(Money)` / `toMoney(MoneyDto)`. Used only for the embeddable-backed master fields (cost/price/creditLimit).
- Transactional DTOs carry a flat `currency` String + flat BigDecimal amounts (e.g. SalesOrderDto, SupplierQuoteDto, ArInvoiceDto, JournalLineDto `(... debitAmount, creditAmount, currency, lineMemo)`, JournalEntryDraft.LineDraft `(accountId, debitAmount, creditAmount, currency, lineMemo, ...dimension/project ids)`). No baseAmount/rate/rateAt fields anywhere.

## Where a base-currency amount would need to be derived/stored (gap analysis)

ADR-0005 D-5 specifies the reserved-but-unbuilt shape: each foreign-currency money field should additionally carry `<field>_base_amount` NUMERIC(19,4) + base code, `<field>_rate` NUMERIC(19,8), and a `rate_at` timestamp, captured at txn time and IMMUTABLE (BR-CUR-05). None of these columns exist on any table today. The cleanest single chokepoint to derive base amounts is the GL boundary â€” JournalLine already declares "all amounts in company base currency", so a conversion must happen BEFORE/AT LineDraft construction in each poster, OR JournalLine must gain document-currency + base-currency dual columns. The sub-ledger open-item balances that need base equivalents for revaluation are `SupplierBill.outstandingAmount` and `ArReceipt.unallocatedAmount` (and the AR invoice outstanding).

### FX touch points
- GLPostingServiceImpl.validateLine() â€” currently THROWS when ld.currency() != company.baseCurrency (BR-GL-06). FX engine must replace this hard-reject with a conversion step that stamps a base-currency equivalent + rate onto each JournalLine before posting.
- GLPostingServiceImpl.resolveBaseCurrency(Long companyId) â€” the single existing read of company.getBaseCurrency() in the posting path; the natural place to obtain the 'to' currency for conversion.
- JournalLine entity (journal_lines) â€” needs dual-currency columns: keep document currency/amount, add base_debit_amount/base_credit_amount + rate + rate_at (or a ConvertedMoney embeddable per ADR-0005 D-5). Today only single base-currency debit_amount/credit_amount + currency.
- JournalEntryDraft.LineDraft record â€” add baseAmount/rate/rateAt fields (or wrap), since every poster builds drafts here; currently only carries flat (debitAmount, creditAmount, currency).
- SalesInvoiceServiceImpl.create() â€” currency taken raw from req.currency() with NO base-currency reconciliation; this is where a sales document's rate+base equivalent would be captured at finalise.
- ArReceiptServiceImpl.create() (and ArCreditNote/Writeoff/OpeningBalance/Ageing/Balance/Reconciliation) â€” replace the hard-coded `.orElse("TZS")` base-currency fallback with proper currency master / base lookup; AR receipt currency is currently forced to base.
- SupplierBill.outstandingAmount and ArReceipt.unallocatedAmount â€” the open-item balances that period-end revaluation (FR-CUR-12) and cross-currency settlement (FR-CUR-11) would need base-currency equivalents + a recorded rate for.
- Company.baseCurrency (companies.base_currency) â€” the config the whole FX engine pivots on; already config-driven, but several AR queries bypass it with a literal 'TZS' fallback that FX work should eliminate.
- Money @Embeddable (platform.common.money.Money) â€” arithmetic (plus/minus/compareTo) and a CurrencyMismatchException are intentionally absent (ADR-0005 D-6/D-8); the FX/conversion service is the designated home for safe cross-currency arithmetic. A future ForeignMoney/ConvertedMoney embeddable (ADR-0005 D-5 note) would package the base triple.
- Currency master â€” does NOT exist yet (no currencies table/entity); ADR-0005 D-8 item 1. Needed to validate codes, supply minor-unit decimals for rounding, and feed an FX rate table.
- MoneyDto wire contract â€” ADR-0005 D-7 reserves baseAmount/baseCurrency/rate/rateAt alongside {amount,currency}; these fields are not yet emitted and would be added when the engine lands.

### Files of interest
- d:/My_Works/ERP/ERPCLEAN2/backend/src/main/java/com/erp/platform/common/money/Money.java
- d:/My_Works/ERP/ERPCLEAN2/backend/src/main/java/com/erp/platform/common/money/MoneyDto.java
- d:/My_Works/ERP/ERPCLEAN2/backend/src/main/java/com/erp/modules/parties/domain/dto/MoneyDto.java
- d:/My_Works/ERP/ERPCLEAN2/backend/src/main/java/com/erp/modules/iam/domain/entity/Company.java
- d:/My_Works/ERP/ERPCLEAN2/backend/src/main/java/com/erp/modules/gl/service/GLPostingServiceImpl.java
- d:/My_Works/ERP/ERPCLEAN2/backend/src/main/java/com/erp/modules/gl/domain/entity/JournalLine.java
- d:/My_Works/ERP/ERPCLEAN2/backend/src/main/java/com/erp/modules/gl/domain/dto/JournalEntryDraft.java
- d:/My_Works/ERP/ERPCLEAN2/backend/src/main/java/com/erp/modules/sales/domain/entity/SalesInvoice.java
- d:/My_Works/ERP/ERPCLEAN2/backend/src/main/java/com/erp/modules/sales/domain/entity/SalesInvoiceLine.java
- d:/My_Works/ERP/ERPCLEAN2/backend/src/main/java/com/erp/modules/sales/service/SalesInvoiceServiceImpl.java
- d:/My_Works/ERP/ERPCLEAN2/backend/src/main/java/com/erp/modules/ap/domain/entity/SupplierBill.java
- d:/My_Works/ERP/ERPCLEAN2/backend/src/main/java/com/erp/modules/ap/domain/entity/ApPayment.java
- d:/My_Works/ERP/ERPCLEAN2/backend/src/main/java/com/erp/modules/ar/domain/entity/ArReceipt.java
- d:/My_Works/ERP/ERPCLEAN2/backend/src/main/java/com/erp/modules/ar/service/ArReceiptServiceImpl.java
- d:/My_Works/ERP/ERPCLEAN2/docs/decisions/0005-money-and-currency.md
- d:/My_Works/ERP/ERPCLEAN2/docs/requirements/multicurrency.md
- d:/My_Works/ERP/ERPCLEAN2/backend/src/main/resources/db/migration/V10__general_ledger.sql
- d:/My_Works/ERP/ERPCLEAN2/backend/src/main/resources/db/migration/V11__accounts_receivable.sql
- d:/My_Works/ERP/ERPCLEAN2/backend/src/main/resources/db/migration/V12__accounts_payable.sql

## gl-posting-engine

## The single posting engine

`GLPostingService` (interface) / `GLPostingServiceImpl` (impl, `@Service @Transactional`) is the ONLY write path into the append-only ledger (journal_batches â†’ journal_entries â†’ journal_lines). Both manual posting (`JournalServiceImpl.postManual`) and every auto-poster (event handlers via `GLPostingSafeInvoker`) build a `JournalEntryDraft` and call `GLPostingServiceImpl.post(JournalEntryDraft)`. There is a second method `postReversal(originalEntryUid, reversalDate, sourceType, sourceRef, postedBy)` that re-fetches the original lines, swaps debitâ†”credit, sets `reversalOfId`, and routes back through `post(...)` â€” so it is balanced by construction and re-runs all validation.

## The draft / line model

Input is the record `JournalEntryDraft(companyId, branchId, postingDate, description, sourceType, sourceRef, reversalOfId, postedBy, List<LineDraft> lines)`. Each `JournalEntryDraft.LineDraft` carries: `accountId, debitAmount, creditAmount, currency, lineMemo` + four nullable dimension ids (`costCentreValueId, departmentValueId, dimension3ValueId, dimension4ValueId`) + three nullable project tags (`projectId, projectTaskId, projectCostType`). Convenience 5-arg and 9-arg constructors default the extra tags to null (zero-regression). A LineDraft is one-sided: exactly one of `debitAmount`/`creditAmount` is > 0.

Persisted entity `JournalLine` (table `journal_lines`) is append-only (no updated_* columns) with columns `debit_amount NUMERIC(19,4)`, `credit_amount NUMERIC(19,4)`, `currency VARCHAR(3)`, `account_id`, `line_no`, plus the dimension/project tag columns. Built via static factories `JournalLine.debit(...)` / `JournalLine.credit(...)`. `JournalEntry` (table `journal_entries`) holds `posting_date`, `fiscal_period_id`, `source_type`, `source_ref`, `reversal_of_id` (self-FK), `posted_at`, `posted_by` (NULL for SYSTEM auto-poster).

## post(...) sequence (GLPostingServiceImpl.post)

1. **â‰¥2 lines** check (BR-GL-01) â€” throws IllegalArgumentException if fewer.
2. **resolveBaseCurrency(companyId)** â†’ `companies.findById(companyId).getBaseCurrency()` (Company.baseCurrency, column `companies.base_currency VARCHAR(3)`, default 'TZS'). Then `validateLine(ld, companyId, baseCurrency, allowInactive)` for each line. `allowInactive` is true ONLY when `draft.sourceType() == YEAR_END_CLOSE`.
3. **validateDimensions(...)** â€” cost-centre/project dimension assertions (no-op when none configured).
4. **Balance validation (THE method):** Î£debits and Î£credits are each computed with a Java stream `reduce(BigDecimal.ZERO, BigDecimal::add)` over the draft lines (null amount â†’ BigDecimal.ZERO), then compared with `totalDebit.compareTo(totalCredit) != 0` â†’ throws IllegalArgumentException with the exact difference. This is value-equality (compareTo), not equals, so scale differences do not matter. Done on the DRAFT amounts BEFORE persistence; nothing partial is written on rejection. There is NO currency dimension in this sum â€” it adds all line amounts as plain BigDecimals regardless of currency code (safe today because validateLine forces every line to base currency).
5. **FiscalPeriodResolver.resolveOpen(companyId, postingDate)** â€” must find an OPEN period (BR-GL-03).
6. `JournalBatchNumberGenerator.next(companyId)` â†’ JB-#### number.
7. Persist JournalBatch, then JournalEntry (sets fiscalPeriodId, reversalOfId), then each JournalLine via debit()/credit() factories with `lineNo` 1..n.
8. Audit `GL_JOURNAL_POST`. Returns `JournalEntryDto`.

## validateLine(...) â€” the per-line gate (exact rules)

- **One-sided:** `!(hasDebit ^ hasCredit)` â†’ reject (BR-GL-08). hasDebit = debit.compareTo(ZERO) > 0.
- **Non-negative** amounts.
- **Account exists**, **same company** (`account.getCompanyId().equals(companyId)`, BR-GL-05).
- **Active account** unless `allowInactiveAccount` (BR-GL-04).
- **Currency gate (the key FX line):** `if (!baseCurrency.equals(ld.currency())) throw ...` (BR-GL-06). This is the single hard-wired "base currency only" rule in the engine. Every posting line MUST equal the company base currency today; a foreign-currency line is currently rejected outright.

## Posting currency decided today = ALWAYS company base

`JournalServiceImpl.postManual` calls `resolveCurrency(companyId)` = `company.getBaseCurrency()` (fallback 'TZS') for every line. `GLPostingSafeInvoker.postSaleInNewTx` passes the document `currency` through, but the engine's validateLine rejects it unless it equals base. So in practice every journal line currency == company base currency. The ledger is single-currency-per-company; ADR-0005 (money & currency) explicitly defers the FX engine (D-8: rate sourcing, conversion service, revaluation, realized/unrealized gain-loss, cross-currency settlement). No exchange-rate, FX-gain, or FX-loss code exists anywhere in the codebase today â€” only docs (ADR-0005, multicurrency.md) describe the deferred shape.

## DB-level balance/invariant backstops (V10__general_ledger.sql)

- `chk_journal_line_one_side`: `(debit_amount > 0 AND credit_amount = 0) OR (credit_amount > 0 AND debit_amount = 0)` â€” single-row backstop for one-sidedness. A zero/zero line is illegal, so any FX leg must be > 0 on exactly one side.
- `chk_journal_line_nonneg`. There is NO database-level Î£debit==Î£credit constraint â€” entry-level balance is enforced ONLY in `GLPostingServiceImpl.post` (step 4 above). The DB cannot see the whole entry's balance; the service is the sole guarantor.

## gl_configs keyâ†’account mechanism

`GlConfigKey` (enum, EnumType.STRING) is the posting-role key. `GlConfig` entity (table `gl_configs`, columns `company_id`, `config_key VARCHAR(40)`, `account_id` FKâ†’chart_of_accounts) maps one role â†’ one account per company; unique on (company_id, config_key). `GLConfigResolver.resolve(companyId, GlConfigKey)` (`@Transactional(MANDATORY)`) returns the active ChartOfAccount or throws IllegalStateException if the mapping is missing / account deleted / account inactive (BR-GL-10) â€” auto-posters then return null and the outbox handles it. `GlConfigServiceImpl.set(...)` lets GL.MANAGE remap a key; `seedDefaults(companyId)` seeds the DEFAULT_MAPPINGS account-code map for a new company.

## Existing GlConfigKey keys (enum, all currently defined)

SALES_REVENUE, VAT_PAYABLE, ACCOUNTS_RECEIVABLE, CASH, INVENTORY, COGS, ACCOUNTS_PAYABLE, BAD_DEBT_EXPENSE, OPENING_BALANCE_EQUITY, PURCHASES, VAT_INPUT, VAT_DUE, WHT_PAYABLE, WHT_RECEIVABLE, RETAINED_EARNINGS, GRNI, STOCK_ADJUSTMENT, POS_CASH_OVER, POS_CASH_SHORT, FIXED_ASSETS, FIXED_ASSET_CLEARING, ACCUMULATED_DEPRECIATION, DEPRECIATION_EXPENSE, GAIN_LOSS_ON_DISPOSAL, REVALUATION_RESERVE, LANDED_COST_CLEARING, SALARY_EXPENSE, EMPLOYER_STATUTORY_EXPENSE, PAYE_PAYABLE, NSSF_PAYABLE, WCF_PAYABLE, SDL_PAYABLE, HESLB_PAYABLE, NET_WAGES_PAYABLE, EMPLOYEE_LOAN_RECEIVABLE, WIP_INVENTORY, FINISHED_GOODS, LABOUR_APPLIED, OVERHEAD_APPLIED, MANUFACTURING_VARIANCE.

No FX_GAIN / FX_LOSS / FX_GAIN_LOSS key exists. The DB `chk_gl_config_key` CHECK constraint is the gating list and is widened additively per increment (latest superset in V74; the canonical widen pattern is V56__hr_gl_keys.sql / V46__fixed_assets_gl_config.sql â€” DROP CONSTRAINT IF EXISTS then ADD CONSTRAINT with the FULL union of all prior keys plus the new ones). Note the enum already has all keys but the DB CHECK is the real gate; a new key NOT in the latest CHECK union cannot be persisted.

## Where FX keys + a realized/unrealized FX leg plug in

The pattern is fully additive and mirrors every prior GL increment. There is exactly one structural blocker: the base-currency-only rule in `validateLine`. For a realized/unrealized FX leg the engine itself needs NO change IF the FX adjustment is posted as base-currency lines (the FX gain/loss amount in base) â€” the FX poster computes the base-currency difference and posts a normal balanced 2-leg entry (DR/CR the realized-FX account vs the control account), all in base currency, which passes the existing balance and currency gates untouched. The harder design choice (true foreign-currency lines on the ledger) requires relaxing/redesigning the `validateLine` currency check and the balance sum to be currency-aware â€” see fxTouchPoints.

## Migration version note

Latest migration is V76. A new FX increment migration (e.g. V77) would: (1) seed FX gain/loss CoA accounts per company, (2) DROP+ADD `chk_gl_config_key` with the full prior union + FX_GAIN/FX_LOSS, (3) DROP+ADD `chk_journal_batch_source_type` and `chk_journal_entry_source_type` with the full union + any new FX source token (e.g. FX_REVALUATION / FX_SETTLEMENT), (4) seed gl_configs mappings.

### FX touch points
- GlConfigKey enum (backend/.../gl/domain/enums/GlConfigKey.java): add FX_GAIN, FX_LOSS (or a single FX_GAIN_LOSS) â€” additive, follows the existing increment comment pattern.
- DB CHECK chk_gl_config_key (new migration V77+, copy the V56/V46 DROP-IF-EXISTS + ADD with FULL union pattern): without adding the new key strings to this CHECK, GlConfig rows for FX keys cannot be inserted even though the enum has them.
- GlConfigServiceImpl.DEFAULT_MAPPINGS (backend/.../gl/service/GlConfigServiceImpl.java): add FX_GAIN/FX_LOSS â†’ new account codes so seedDefaults wires them for every company; the new migration must also seed the CoA accounts + gl_configs rows for existing companies.
- GLConfigServiceImpl seed migration: seed CoA accounts (e.g. an INCOME 'Realized/Unrealized FX Gain' and EXPENSE 'FX Loss', or one GAIN_LOSS account) like V46 section (1), then gl_configs section (3).
- GLConfigResolver.resolve(companyId, GlConfigKey.FX_GAIN/FX_LOSS): the FX poster resolves the gain/loss account through the existing resolver â€” no resolver change needed.
- GLPostingServiceImpl.validateLine() currency gate `if (!baseCurrency.equals(ld.currency())) throw` (the BR-GL-06 line): THE blocker for true foreign-currency lines. For a base-currency-only FX-adjustment leg (recommended first step) NO change is needed â€” post the FX gain/loss amount in base currency. For genuine foreign-currency journal lines this check must be relaxed (allow currency != base when a base-equivalent + rate are supplied).
- GLPostingServiceImpl.post() balance validation (the totalDebit/totalCredit reduce + compareTo block): currency-agnostic today. If foreign-currency lines are ever allowed on one entry, this Î£ must become currency-aware (balance per base-equivalent amount, not raw line amount) â€” otherwise it would sum mixed currencies. For base-currency FX-adjustment entries it works as-is.
- JournalEntryDraft.LineDraft record + JournalLine entity + journal_lines table: if foreign-currency lines are supported, this is where base-equivalent amount, exchange rate, and rate_at columns/fields would be added (ADR-0005 D-5 recording shape: <field>_base_amount NUMERIC(19,4), <field>_rate NUMERIC(19,8), rate_at). For a pure base-currency FX gain/loss leg, no new column is required.
- JournalSourceType enum + chk_journal_batch_source_type / chk_journal_entry_source_type CHECKs: add a new source token (e.g. FX_REVALUATION for unrealized period-end, FX_SETTLEMENT for realized) and widen both CHECKs additively (V56 pattern). The unrealized revaluation entry at period end would post under FX_REVALUATION and its reopen reversal via postReversal(...).
- GLPostingSafeInvoker (REQUIRES_NEW wrapper): a new FX poster (e.g. period-end revaluation handler or settlement handler) should resolve accounts + build the draft + call postingService.post inside postInNewTx / a new postFxInNewTx, mirroring postSaleInNewTx, so a missing FX gl_config returns null instead of poisoning the dispatch TX.
- YEAR_END_CLOSE allowInactive precedent in post(): if FX revaluation must post to a deactivated FX account during close, the same `allowInactive = sourceType == ...` switch is the established hook.

### Files of interest
- d:\My_Works\ERP\ERPCLEAN2\backend\src\main\java\com\erp\modules\gl\service\GLPostingService.java
- d:\My_Works\ERP\ERPCLEAN2\backend\src\main\java\com\erp\modules\gl\service\GLPostingServiceImpl.java
- d:\My_Works\ERP\ERPCLEAN2\backend\src\main\java\com\erp\modules\gl\domain\dto\JournalEntryDraft.java
- d:\My_Works\ERP\ERPCLEAN2\backend\src\main\java\com\erp\modules\gl\domain\entity\JournalLine.java
- d:\My_Works\ERP\ERPCLEAN2\backend\src\main\java\com\erp\modules\gl\domain\entity\JournalEntry.java
- d:\My_Works\ERP\ERPCLEAN2\backend\src\main\java\com\erp\modules\gl\domain\enums\GlConfigKey.java
- d:\My_Works\ERP\ERPCLEAN2\backend\src\main\java\com\erp\modules\gl\domain\enums\JournalSourceType.java
- d:\My_Works\ERP\ERPCLEAN2\backend\src\main\java\com\erp\modules\gl\domain\entity\GlConfig.java
- d:\My_Works\ERP\ERPCLEAN2\backend\src\main\java\com\erp\modules\gl\service\GLConfigResolver.java
- d:\My_Works\ERP\ERPCLEAN2\backend\src\main\java\com\erp\modules\gl\service\GlConfigServiceImpl.java
- d:\My_Works\ERP\ERPCLEAN2\backend\src\main\java\com\erp\modules\gl\service\JournalServiceImpl.java
- d:\My_Works\ERP\ERPCLEAN2\backend\src\main\java\com\erp\modules\gl\service\GLPostingSafeInvoker.java
- d:\My_Works\ERP\ERPCLEAN2\backend\src\main\java\com\erp\modules\gl\events\SalesPostingHandler.java
- d:\My_Works\ERP\ERPCLEAN2\backend\src\main\java\com\erp\modules\iam\domain\entity\Company.java
- d:\My_Works\ERP\ERPCLEAN2\backend\src\main\resources\db\migration\V10__general_ledger.sql
- d:\My_Works\ERP\ERPCLEAN2\backend\src\main\resources\db\migration\V56__hr_gl_keys.sql
- d:\My_Works\ERP\ERPCLEAN2\backend\src\main\resources\db\migration\V46__fixed_assets_gl_config.sql
- d:\My_Works\ERP\ERPCLEAN2\docs\decisions\0005-money-and-currency.md
- d:\My_Works\ERP\ERPCLEAN2\docs\requirements\multicurrency.md

## ar-ap-settlement

OVERALL: The system is single-currency end-to-end today. There is NO FX machinery anywhere â€” no exchange_rate / base_amount / fx_gain / fx_loss columns in any migration, no GlConfigKey for realized FX, and the GL posting engine HARD-REJECTS any line whose currency != company base currency. So settlement at a different rate cannot even be represented today; FX is a greenfield add.

=== AR RECEIPT SETTLEMENT (ArReceiptServiceImpl.recordAndAllocate) ===
File: backend/src/main/java/com/erp/modules/ar/service/ArReceiptServiceImpl.java
Flow of recordAndAllocate(RecordReceiptRequest):
1. Resolves companyId from companyUid, scopeGuard.assertCanActIn.
2. Resolves Customer via customers.findByCompanyIdAndUid.
3. CRITICAL: currency is taken from companies.findById(companyId).getBaseCurrency() (line ~122), defaulting to "TZS". req.currency() on RecordReceiptRequest is IGNORED â€” the receipt is forced to base currency. So an AR receipt cannot currently be in a foreign currency.
4. Creates ArReceipt header; unallocated_amount starts == amount.
5. Builds allocation set: if req.allocations() empty -> autoAllocate (oldest-first, BR-AR-03); else manualAllocate.
   - autoAllocate(receipt, companyId, customerId): loads open items via invoices.findOpenForUpdateByCompanyAndCustomer (PESSIMISTIC_WRITE lock, status IN OPEN/PARTIAL, ORDER BY dueDate ASC, id ASC). Greedily takes slice = remaining.min(inv.getOutstandingAmount()), builds ArReceiptAllocation rows.
   - manualAllocate: resolves each AllocationLineRequest.arInvoiceUid, builds ArReceiptAllocation with line.allocatedAmount().
6. Apply allocation loop (lines ~155-173): for each ArReceiptAllocation, loads ArInvoice, guards allocatedAmount <= inv.getOutstandingAmount() (BR-AR-04 IllegalStateException), then inv.setOutstandingAmount(outstanding - allocated), inv.setStatus(deriveInvoiceStatus(...)), saves. allocations.save(alloc).
7. Guard totalAllocated <= receipt.getAmount() (BR-AR-04).
8. receipt.setUnallocatedAmount(amount - totalAllocated); receipt.setStatus(deriveReceiptStatus(...)).
9. GL POSTING (this is the settlement cash leg, lines ~189-243): resolves cash/bank via cashBankAccountResolver.resolve(companyId, req.cashBankAccountUid()) -> CashAccountGlResolutionDto; resolves AR control via glConfig.resolve(companyId, GlConfigKey.ACCOUNTS_RECEIVABLE). Builds glLines:
   - DR cashRes.glAccountId() = cashDr (= receipt.amount, less whtAmount if WHT)
   - (optional) DR whtResult.glAccountId() = whtAmount (WHT_RECEIVABLE)
   - CR arAcct.getId() = receipt.getAmount() ("AR control")
   So the AR settlement legs are: DR Cash + (DR WHT) / CR AR, all in `currency` (= base). Posted via glPosting.post(draft) with JournalSourceType.AR_RECEIPT. receipt.setGlEntryUid(posted.uid()).
   9c: cashTxnRecorder.recordSettlement(... CashTxnType.AR_RECEIPT, CashTxnDirection.IN ...).
NOTE: There is NO separate settlement-posting method â€” the GL legs are built inline inside recordAndAllocate (no postReceiptToGl helper). The AR amounts in the GL CR-AR leg equal the receipt amount; there is no concept of "original invoice value in base vs cash value in base", so no FX gain/loss line is ever computed.

reallocate(receiptUid, newAllocations): restores outstanding on previously allocated invoices, deletes allocations, re-applies. Posts NOTHING to GL (allocation itself is GL-neutral per BR-AR-12 â€” only the cash leg posted at creation).

=== AP PAYMENT SETTLEMENT (ApPaymentServiceImpl) ===
File: backend/src/main/java/com/erp/modules/ap/service/ApPaymentServiceImpl.java
paySingle(PaySingleBillRequest):
1. resolveCompany + scopeGuard.
2. bills.findOpenByUids(companyId, [uid]) â€” PESSIMISTIC_WRITE lock, status IN MATCHED/APPROVED/PARTIALLY_PAID (no-double-pay, NFR-AP-04).
3. toAllocate = req.amount().min(bill.getOutstandingAmount()).
4. currency = bill.getCurrency() (NOTE: AP takes currency from the BILL, not from base currency â€” unlike AR. Still must equal base or GL rejects it).
5. Creates ApPayment(kind=SINGLE, amount=toAllocate). Creates ApPaymentAllocation(companyId, paymentId, billId, toAllocate). bill.setOutstandingAmount(outstanding - toAllocate); bill.setStatus(billStatusAfterPayment).
6. Calls postPaymentToGl(...).
paymentRun(PaymentRunRequest): selects open bills via findOpenForPayment / findOpenForPaymentAllSuppliers / findOpenByUids (all PESSIMISTIC_WRITE). totalPaid = Î£ outstanding. For each bill: ApPaymentAllocation = full outstanding, bill.setOutstandingAmount(ZERO), status=PAID. One postPaymentToGl for the batch total.

THE SETTLEMENT GL POSTING METHOD (by name): ApPaymentServiceImpl.postPaymentToGl(ApPayment payment, Long companyId, Long branchId, String currency, String cashBankAccountUid, Long supplierId, String whtTypeUid, BigDecimal whtAmount) (lines ~267-336). Legs:
   - DR apAcct.getId() (glConfig.resolve ACCOUNTS_PAYABLE) = payment.getAmount() (gross)
   - CR cashRes.glAccountId() = cashCr (= payment.amount, less whtAmount if WHT)
   - (optional) CR whtResult.glAccountId() = whtAmount (WHT_PAYABLE)
   i.e. DR AP / CR Cash (+ CR WHT). Posted via glPosting.post(draft) with JournalSourceType.AP_PAYMENT. Records cashTxnRecorder.recordSettlement(... CashTxnType.AP_PAYMENT, CashTxnDirection.OUT ...). No FX leg.

=== OPEN-ITEM MODEL ===
There is NO ar_open_items table by that name. The open-item ledgers are:
- AR open item = ar_invoices (entity ArInvoice, table ar_invoices). Columns: original_amount, outstanding_amount, currency (VARCHAR(3), updatable=false), status (OPEN/PARTIAL/PAID/WRITTEN_OFF), invoice_date, due_date. CHECK chk_ar_invoice_amounts: outstanding_amount >= 0 AND <= original_amount. Created by ArSalePostedHandler (source=SALE) or ArOpeningBalanceServiceImpl (source=OPENING_BALANCE).
- AP open item = supplier_bills (entity SupplierBill, table supplier_bills). Columns: net_amount, vat_amount, gross_amount, outstanding_amount, currency (VARCHAR(3) updatable=false), status (DRAFT/MATCHED/HELD/APPROVED/PARTIALLY_PAID/PAID). outstanding starts ZERO, set to gross on match/post. CHECK: outstanding_amount <= gross_amount.

=== ALLOCATION ENTITIES (junctions, no uid, append-only, GL-neutral) ===
- ar_receipt_allocations / ArReceiptAllocation: columns company_id, receipt_id, ar_invoice_id, allocated_amount (NUMERIC(19,4)), allocated_at, allocated_by. UNIQUE(receipt_id, ar_invoice_id). CHECK allocated_amount > 0. Re-allocation = delete + re-insert.
- ap_payment_allocations / ApPaymentAllocation: columns company_id, ap_payment_id, supplier_bill_id, allocated_amount (NUMERIC(19,4) updatable=false). UNIQUE(ap_payment_id, supplier_bill_id). CHECK allocated_amount > 0.
Both allocation tables store only a single allocated_amount with NO currency and NO rate â€” they assume invoice currency == receipt/payment currency == base.

=== WHERE A FOREIGN-CURRENCY SETTLEMENT AT A DIFFERENT RATE WOULD PRODUCE REALIZED FX GAIN/LOSS ===
Conceptually the realized FX would arise at settlement when the base-currency value of the foreign-currency amount allocated to an open item (at the settlement/spot rate) differs from the base-currency value originally booked to AR/AP control when the invoice/bill was raised (at the invoice rate). The exact arithmetic points where the offsetting GL line would have to be injected:
- AR: ArReceiptServiceImpl.recordAndAllocate, GL-leg builder block lines ~215-238. Today CR AR = receipt.getAmount() in base. With FX, the CR-AR per-allocation must be at the ORIGINAL invoice rate (the base value that was originally debited to AR), DR Cash at the settlement rate, and the residual DR/CR a REALIZED_FX gain/loss account to balance. The natural hook is per-ArReceiptAllocation inside the allocation loop (lines ~155-173) where each invoice's outstanding is reduced â€” that loop is where the original invoice rate is known.
- AP: ApPaymentServiceImpl.postPaymentToGl, GL-leg builder lines ~295-310. Today DR AP = payment.getAmount() in base. With FX, DR AP must reverse the original bill rate, CR Cash at settlement rate, balance via REALIZED_FX. The per-bill allocation in paySingle (lines ~134-141) and the paymentRun loop (lines ~207-216) are where each bill's original rate would be needed.
HARD BLOCKER to be aware of: GLPostingServiceImpl.validateLine (lines ~327-366) enforces `if (!baseCurrency.equals(ld.currency())) throw ... (BR-GL-06)`. Every LineDraft must currently be base currency. So FX cannot post a foreign-currency line; the design must either (a) keep posting only base amounts (convert in the poster, store rate on the source doc) or (b) relax BR-GL-06 to carry a txn-currency + base-amount pair on JournalLine. Today JournalEntryDraft.LineDraft and JournalLine carry a single amount + currency only â€” no base_amount / rate fields.

### FX touch points
- ArReceiptServiceImpl.recordAndAllocate â€” GL leg builder (CR AR = receipt.getAmount(), lines ~215-238): inject REALIZED FX gain/loss leg; CR-AR must use original invoice base value not receipt amount
- ArReceiptServiceImpl.recordAndAllocate â€” allocation loop (lines ~155-173) where inv.outstandingAmount is reduced: per-allocation is where original invoice rate vs settlement rate diff is computable
- ArReceiptServiceImpl.recordAndAllocate line ~122 â€” currency is forced to company base currency, IGNORING req.currency(); must read txn currency + a settlement rate to support FX receipts
- ApPaymentServiceImpl.postPaymentToGl (lines ~267-336) â€” DR AP = payment.getAmount() base; inject REALIZED FX leg; DR-AP must reverse original bill base value
- ApPaymentServiceImpl.paySingle allocation (lines ~134-141) and paymentRun loop (lines ~207-216) â€” per-bill original rate needed to compute realized FX
- GLPostingServiceImpl.validateLine (lines ~327-366) â€” BR-GL-06 hard-rejects any line whose currency != company base currency; must relax or design FX poster to emit only base-currency legs
- GlConfigKey enum â€” add a REALIZED_FX_GAIN / REALIZED_FX_LOSS (or single REALIZED_FX) posting-role key; none exists today; also widen gl_configs chk_gl_config_key CHECK + seed CoA accounts (mirror V11/V12 seed pattern)
- JournalEntryDraft.LineDraft + JournalLine â€” currently single (amount, currency); add base_amount/txn_amount + rate if foreign-currency lines are to be stored
- ArInvoice / SupplierBill entities + ar_invoices/supplier_bills tables â€” add invoice/bill exchange_rate (and base_amount) columns to record the original rate so settlement can compute the FX delta; currency column is updatable=false
- ar_receipt_allocations / ap_payment_allocations tables + entities â€” allocated_amount has no currency/rate; add settlement rate or base-allocated-amount to capture per-allocation FX
- CashTransactionRecorder.recordSettlement â€” called in both AR and AP settlement; cash amount is in base today; revisit if cash is foreign-currency
- Money embeddable (platform/common/money/Money.java) â€” explicitly documents FX engine is deferred (ADR-0005 D-8); arithmetic helpers to be added under the FX ADR

### Files of interest
- d:/My_Works/ERP/ERPCLEAN2/backend/src/main/java/com/erp/modules/ar/service/ArReceiptServiceImpl.java
- d:/My_Works/ERP/ERPCLEAN2/backend/src/main/java/com/erp/modules/ap/service/ApPaymentServiceImpl.java
- d:/My_Works/ERP/ERPCLEAN2/backend/src/main/java/com/erp/modules/ar/domain/entity/ArInvoice.java
- d:/My_Works/ERP/ERPCLEAN2/backend/src/main/java/com/erp/modules/ar/domain/entity/ArReceipt.java
- d:/My_Works/ERP/ERPCLEAN2/backend/src/main/java/com/erp/modules/ap/domain/entity/SupplierBill.java
- d:/My_Works/ERP/ERPCLEAN2/backend/src/main/java/com/erp/modules/ap/domain/entity/ApPayment.java
- d:/My_Works/ERP/ERPCLEAN2/backend/src/main/java/com/erp/modules/ar/domain/entity/ArReceiptAllocation.java
- d:/My_Works/ERP/ERPCLEAN2/backend/src/main/java/com/erp/modules/ap/domain/entity/ApPaymentAllocation.java
- d:/My_Works/ERP/ERPCLEAN2/backend/src/main/java/com/erp/modules/gl/service/GLPostingServiceImpl.java
- d:/My_Works/ERP/ERPCLEAN2/backend/src/main/java/com/erp/modules/gl/domain/dto/JournalEntryDraft.java
- d:/My_Works/ERP/ERPCLEAN2/backend/src/main/java/com/erp/modules/gl/domain/enums/GlConfigKey.java
- d:/My_Works/ERP/ERPCLEAN2/backend/src/main/java/com/erp/modules/ar/repository/ArInvoiceRepository.java
- d:/My_Works/ERP/ERPCLEAN2/backend/src/main/java/com/erp/modules/ap/repository/SupplierBillRepository.java
- d:/My_Works/ERP/ERPCLEAN2/backend/src/main/java/com/erp/platform/common/money/Money.java
- d:/My_Works/ERP/ERPCLEAN2/backend/src/main/resources/db/migration/V11__accounts_receivable.sql
- d:/My_Works/ERP/ERPCLEAN2/backend/src/main/resources/db/migration/V12__accounts_payable.sql

## period-close-revaluation

PERIOD MODEL (module com.erp.modules.gl)
- FiscalYear (table fiscal_years): companyId, yearCode, startMonth (default 1), startDate, endDate, status (PeriodStatus OPEN/CLOSED), plus V16 close stamps closedAt, closedBy, closingJournalUid (ULID of the closing journal). 12 periods per year.
- FiscalPeriod (table fiscal_periods): companyId (denormalised for tenant predicate), fiscalYearId, periodNo 1..12, startDate, endDate, status (OPEN/CLOSED is the posting gate), closedAt, closedBy. No FX/rate columns.
- PeriodStatus has only OPEN and CLOSED. FiscalPeriodResolver.resolveOpen(companyId, postingDate) is the single posting gate (Propagation.MANDATORY); throws if no OPEN period covers the date. Every GL post goes through it.

CLOSE FLOW (FiscalCalendarServiceImpl + YearEndCloseServiceImpl)
- Period close is a pure status flip (FiscalCalendarServiceImpl.closePeriod) with NO posting and NO revaluation hook. The only year-boundary posting is YearEndCloseServiceImpl.closeFiscalYear(fiscalYearUid):
  1. requireYear + scopeGuard.assertCanActIn(RequestContext.get(), year.getCompanyId()) (mandatory tenant guard on every path).
  2. Guards: reject if CLOSED, reject if no periods, checkPriorYearClosed (BR-CLOSE-04).
  3. Compute P&L movement: accounts.findByCompanyIdAndAccountTypeIn(companyId, [INCOME,EXPENSE]); buildMovementMap calls JournalLineRepository.periodMovementByAccount(companyId, startDate, endDate) returning [accountId, SUM(debit), SUM(credit)] grouped by account over the window. THIS windowed-aggregate-then-post is the pattern an FX revaluation should mirror.
  4. Build a JournalEntryDraft of zeroing LineDraft rows balanced against RETAINED_EARNINGS resolved by glConfigResolver.resolve(companyId, GlConfigKey.RETAINED_EARNINGS).
  5. glPostingService.post(draft) with JournalSourceType.YEAR_END_CLOSE WHILE PERIODS STILL OPEN (must post before closing them so the period gate passes).
  6. Auto-close still-OPEN periods via fiscalCalendarService.closePeriod(uid).
  7. Mark year CLOSED, stamp closedAt/closedBy/closingJournalUid; audit GL_YEAR_CLOSE.
- reopenFiscalYear: reopens periods FIRST, then glPostingService.postReversal(closingJournalUid, year.getEndDate(), YEAR_END_CLOSE, year.getUid(), actorId) with idempotency guard journalEntries.existsByReversalOfId(...). This reverse-by-posting-a-negating-entry-into-an-OPEN-period is exactly the mechanism the FX reversal-next-period leg would reuse.

GL POSTING PRIMITIVE (FX must call this, no bypass)
- GLPostingServiceImpl.post(JournalEntryDraft) is the ONLY ledger write path. Validates >=2 lines, one-sided lines, active accounts (YEAR_END_CLOSE is the lone source allowed to post to INACTIVE accounts), same company, Sigma debit == Sigma credit, and CRITICALLY line.currency == company base currency (BR-GL-06) â€” every journal line is base currency only.
- postReversal(originalEntryUid, reversalDate, sourceType, sourceRef, postedBy) swaps debit/credit per line and sets reversalOfId â€” the ready-made reverse-next-period tool (call with next period date).
- JournalEntry has reversalOfId self-FK but NO auto-reverse/reverseOn flag; reversal is an explicit second posting. JournalLine has debit_amount/credit_amount/currency only â€” NO base_amount or rate column.

FX STATE OF THE WORLD (headline)
- Unrealized FX revaluation does NOT exist and has essentially no infrastructure. ADR-0005 (docs/decisions/0005-money-and-currency.md) D-8 explicitly defers by name: (4) revaluation of open foreign-currency balances at period close, (5) realized/unrealized gain-loss posting, (2) currency_rate table, (3) conversion service.
- NO currency_rates/exchange_rates table in any migration (only companies.base_currency exists). NO conversion service. NO FX_GAIN/FX_LOSS GlConfigKey. NO FX JournalSourceType.
- Money embeddable (platform.common.money.Money) is a bare (amount, currency) holder; Javadoc states arithmetic and the FX engine are deferred.
- The ADR-0005 D-5 base-equivalent triple (base_amount + rate + rate_at) was NOT implemented on documents: ArInvoice has originalAmount/outstandingAmount + a single currency column (no base/rate); CashBankAccount has a single currency column. So today document currency == base currency, rate == 1 in practice. A real FX revaluation must FIRST introduce the rate source + open-balance valuation data, not merely the posting run.

BATCH/JOB PATTERNS TO MIRROR
1. ON-DEMAND PERIOD BATCH POST (closest analogue) â€” DepreciationRunServiceImpl.post(RunDepreciationRequest): scopeGuard.assertCanActIn -> requirePeriod -> IDEMPOTENCY guard runs.findByCompanyIdAndFiscalPeriodId(companyId, periodId) -> periodResolver.resolveOpen gate -> gather eligible rows -> aggregate into per-account map -> post ONE GL journal via a dedicated GlPoster -> persist run header (DepreciationRun: runNumber, fiscalPeriodId, postingDate, status, totalChargeAmount, glEntryUid) + run lines -> outbox DEPRECIATION_RUN_EXECUTED -> audit FA_DEPRECIATION_RUN. Has preview() dry-run and REST trigger DepreciationRunController (POST /api/v1/fixed-assets/depreciation-runs, @PreAuthorize FA.DEPRECIATE). Clone this for an FxRevaluationRun.
2. SCHEDULED SCANNER â€” NotificationScanner.scan() @Scheduled(cron=${erp.notifications.scanner.cron:0 0 * * * *}); @EnableScheduling in platform.events.OutboxSchedulingConfig (gated by erp.outbox.scheduling-enabled). Time-driven loop over companies with per-company try/catch + dedup markers. Use only if a scheduled auto-run is wanted; pattern #1 is the better mirror for an operator-triggered period-end run.
3. AssetRevaluationServiceImpl is a third precedent (single-asset revalue -> periodResolver.resolveOpen -> glPoster.postRevaluation -> persist AssetRevaluation) but it is FIXED-ASSET revaluation (REVALUATION_RESERVE/FA_REVALUATION), unrelated to FX â€” do not conflate.

### FX touch points
- YearEndCloseServiceImpl.closeFiscalYear â€” model the FX revaluation run on this windowed-aggregate-then-post flow; run it at period-end BEFORE FiscalCalendarServiceImpl.closePeriod flips status to CLOSED so the period is still OPEN for the GLPostingService gate
- GLPostingService.post(JournalEntryDraft) â€” the only ledger write path; FX revaluation journals must go through it. Blocker: it rejects any line whose currency != company base currency (BR-GL-06), so the FX delta must be expressed as base-currency amounts on the lines
- GLPostingService.postReversal(originalEntryUid, reversalDate, sourceType, sourceRef, postedBy) â€” the exact mechanism for the reverse-next-period leg; call with the first day of next period as reversalDate (no stored auto-reversing flag exists, so reversal must be posted explicitly by a job)
- FiscalPeriodResolver.resolveOpen(companyId, postingDate) â€” the gate both the revaluation post (period-end date) and its reversal (next-period date) must satisfy
- com.erp.modules.gl.domain.enums.GlConfigKey â€” ADD FX_GAIN / FX_LOSS (or FX_GAIN_LOSS) posting-role keys, resolved via GLConfigResolver.resolve(companyId, key) exactly like RETAINED_EARNINGS in the close
- com.erp.modules.gl.domain.enums.JournalSourceType â€” ADD an FX_REVALUATION source type and widen the DB CHECK additively; the reversal can reuse the token with reversalOfId set (mirroring YEAR_END_CLOSE)
- com.erp.platform.audit.AuditActions â€” ADD an FX.REVALUATION.RUN (and/or FX.RATE.SET) action constant for the run's audit.record call
- JournalLineRepository.accountBalance(companyId, accountId) and periodMovementByAccount(...) â€” read primitives to source the base-currency carrying balance of AR/AP/foreign-cash control accounts to revalue against spot rate
- DepreciationRunServiceImpl.post + DepreciationRun entity + DepreciationRunController â€” clone this run-header/run-lines/preview/idempotency(findByCompanyIdAndFiscalPeriodId)/outbox/audit structure for the FX revaluation run
- NEW infrastructure required first (currently absent): a currency_rates/exchange_rates table + a rate-lookup/conversion service (ADR-0005 D-8 items 2,3), and the per-document base-equivalent triple (base_amount/rate/rate_at) on ArInvoice and CashBankAccount which were never implemented â€” without these there is no foreign-vs-base delta to compute

### Files of interest
- d:\My_Works\ERP\ERPCLEAN2\backend\src\main\java\com\erp\modules\gl\service\YearEndCloseServiceImpl.java
- d:\My_Works\ERP\ERPCLEAN2\backend\src\main\java\com\erp\modules\gl\service\FiscalCalendarServiceImpl.java
- d:\My_Works\ERP\ERPCLEAN2\backend\src\main\java\com\erp\modules\gl\service\FiscalPeriodResolver.java
- d:\My_Works\ERP\ERPCLEAN2\backend\src\main\java\com\erp\modules\gl\service\GLPostingServiceImpl.java
- d:\My_Works\ERP\ERPCLEAN2\backend\src\main\java\com\erp\modules\gl\service\GLConfigResolver.java
- d:\My_Works\ERP\ERPCLEAN2\backend\src\main\java\com\erp\modules\gl\domain\entity\FiscalYear.java
- d:\My_Works\ERP\ERPCLEAN2\backend\src\main\java\com\erp\modules\gl\domain\entity\FiscalPeriod.java
- d:\My_Works\ERP\ERPCLEAN2\backend\src\main\java\com\erp\modules\gl\domain\entity\JournalEntry.java
- d:\My_Works\ERP\ERPCLEAN2\backend\src\main\java\com\erp\modules\gl\domain\entity\JournalLine.java
- d:\My_Works\ERP\ERPCLEAN2\backend\src\main\java\com\erp\modules\gl\domain\enums\GlConfigKey.java
- d:\My_Works\ERP\ERPCLEAN2\backend\src\main\java\com\erp\modules\gl\domain\enums\JournalSourceType.java
- d:\My_Works\ERP\ERPCLEAN2\backend\src\main\java\com\erp\modules\gl\repository\JournalLineRepository.java
- d:\My_Works\ERP\ERPCLEAN2\backend\src\main\java\com\erp\modules\fixedassets\service\DepreciationRunServiceImpl.java
- d:\My_Works\ERP\ERPCLEAN2\backend\src\main\java\com\erp\api\DepreciationRunController.java
- d:\My_Works\ERP\ERPCLEAN2\backend\src\main\java\com\erp\modules\fixedassets\service\AssetRevaluationServiceImpl.java
- d:\My_Works\ERP\ERPCLEAN2\backend\src\main\java\com\erp\modules\notifications\service\NotificationScanner.java
- d:\My_Works\ERP\ERPCLEAN2\backend\src\main\java\com\erp\platform\events\OutboxSchedulingConfig.java
- d:\My_Works\ERP\ERPCLEAN2\backend\src\main\java\com\erp\platform\common\money\Money.java
- d:\My_Works\ERP\ERPCLEAN2\backend\src\main\java\com\erp\modules\ar\domain\entity\ArInvoice.java
- d:\My_Works\ERP\ERPCLEAN2\backend\src\main\java\com\erp\modules\cashbank\domain\entity\CashBankAccount.java
- d:\My_Works\ERP\ERPCLEAN2\docs\decisions\0005-money-and-currency.md
- d:\My_Works\ERP\ERPCLEAN2\backend\src\main\java\com\erp\platform\audit\AuditActions.java

## dimension-gate-20d

## How the mandatory-dimension gate works today (the ADR-0025 D-4 gate)

The gate lives entirely in `GLPostingServiceImpl.validateDimensions(Long companyId, List<JournalEntryDraft.LineDraft> draftLines)` (backend/src/main/java/com/erp/modules/gl/service/GLPostingServiceImpl.java, lines 254-289), invoked unconditionally from `post(JournalEntryDraft)` at line 112 â€” so EVERY journal (manual + every event-driven poster) flows through it. `postReversal(...)` (line 230) re-enters `post()`, so reversals inherit it.

It has two steps:
- Step 2 (value validity, lines 256-262): for each non-null `*ValueId` on each line, calls `assertDimensionValueValid(companyId, slot, valueId)` (lines 297-319) which reads `dimensionValues.findByIdAndCompanyId(valueId, companyId)` (rejects cross-company / unknown), checks `v.isActive()` (rejects inactive), and `dim.getSlot() != expectedSlot` (rejects wrong-slot). No-op when valueId is null (line 298-300).
- Step 3 (MANDATORY enforcement, lines 264-288): `List<DimensionSlot> mandatory = dimensionTypes.findMandatorySlots(companyId)` â€” the query `DimensionRepository.findMandatorySlots` = `SELECT d.slot FROM Dimension d WHERE d.companyId = :companyId AND d.mandatory = true` (backend/.../costing/repository/DimensionRepository.java line 27-28). If empty it RETURNS immediately (lines 266-268) â€” the zero-regression no-op (NFR-CC-01). Otherwise, for every line and every mandatory slot it reads the slot's value via a `switch` (lines 275-280: COST_CENTREâ†’costCentreValueId, DEPARTMENTâ†’departmentValueId, DIMENSION_3â†’dimension3ValueId, DIMENSION_4â†’dimension4ValueId); if `valueId == null` it throws `IllegalArgumentException("Dimension slot ... is mandatory ...")` (lines 281-286) which FAILS THE WHOLE POST.

Mandatory is toggled per-company per-dimension via `Dimension.mandatory` (column `dimensions.is_mandatory`, default false; entity at costing/domain/entity/Dimension.java lines 48-54), set through `PATCH /dimensions/uid/{uid}/mandatory` -> `DimensionController.setMandatory` (api/DimensionController.java lines 44-51, perm COSTING.MANAGE). There is NO DB CHECK constraint â€” ADR-0025 (line 124, 309) deliberately keeps mandatory enforcement in the SERVICE so it can be per-company-toggled.

## Exactly how #20d breaks postings

When a slot is made mandatory, Step 3 throws for every line lacking that slot value. For event-driven posters the throw propagates out of the inner GL TX; for handlers using REQUIRES_NEW safe-invokers it is swallowed and the GL leg is silently dropped (returns null) â€” but for the goods-receipt path observed in #20d it surfaced as `UnexpectedRollbackException` and the `STOCK.RECEIVED` event became a poison event retried forever (ISSUES-REGISTER #20d, line 313). The validation itself is CORRECT (the gate working as designed); the gap is that posters don't carry dimension context.

## Every automated poster's dimension-tagging behavior (verified)

- `GLPostingSafeInvoker.postSaleInNewTx` (SALES) â€” gl/service/GLPostingSafeInvoker.java: tags ONLY the CR Sales Revenue P&L leg (line 85-87, threads costCentreValueId/departmentValueId from `InvoicePostingTotalsDto`); DR Cash/AR (line 83) and CR VAT (line 90) post UNTAGGED. So if COST_CENTRE is mandatory the cash/AR/VAT legs FAIL.
- `SalesPostingHandler` (gl/events/SalesPostingHandler.java line 126-131) feeds those dimension ids; works only if the invoice header has them set.
- `InventoryGlPoster` (stock/service/InventoryGlPoster.java) â€” STOCK_RECEIPT/COGS receipt/reversal/landed-cost/purchase-return legs all use the 5-arg `LineDraft` (UNTAGGED, lines 88-93, 136-141, 232-238, 274-280, 406-412, 451-457). Only `postAdjustmentDirect` (lines 359, 373) and `postCogsForProjectInNewTx` (line 188-192, project tag) carry any tags. The goods-receipt path that broke in #20d is fully untagged.
- `PayrollPostingHandler` (gl/events/PayrollPostingHandler.java) â€” EVERY line uses the 5-arg `LineDraft` (lines 180-224). PAYROLL is entirely untagged; mandatory COST_CENTRE breaks all payroll posting.
- `FixedAssetGlPoster` (fixedassets/service/FixedAssetGlPoster.java) â€” EVERY line uses the 5-arg `LineDraft` (acquisition/depreciation/disposal/revaluation, lines 54-216). Fully UNTAGGED.
- `ManufacturingGlPoster` (manufacturing/service/ManufacturingGlPoster.java) â€” PARTIAL: labour/overhead/variance legs tag via `wo.getCostCentreValueId()` (lines 105, 111, 163, 255, 261) but WIP/component/FG/inventory legs are untagged (lines 70-74, 137-140, 197-201). DEPARTMENT mandatory breaks everything; COST_CENTRE breaks the untagged legs.
- AR posters (ar/service/ArReceiptServiceImpl, ArWriteOffServiceImpl, ArCreditNoteServiceImpl, ArOpeningBalanceServiceImpl), Cash/Bank (cashbank/service/CashTransferServiceImpl, CashDirectEntryServiceImpl), Tax (tax/service/VatReturnFilingPoster) â€” confirmed NONE reference costCentreValueId/departmentValueId; all use 5-arg `LineDraft` -> UNTAGGED.
- AP BillMatch (ap/service/BillMatchServiceImpl.java) â€” tags ONLY the Purchases P&L leg with ccId/deptId (line 341-344); GRNI, VAT, AP-control legs untagged (lines 351-367).
- The MANUAL journal path: `JournalServiceImpl.postManual` (gl/service/JournalServiceImpl.java lines 63-73) builds lines with the 5-arg `LineDraft` and `PostJournalLineRequest` (gl/domain/dto/PostJournalLineRequest.java) has NO dimension fields. So even manual journals currently CANNOT carry a dimension value â€” mandatory enforcement today breaks manual journals too unless the REST DTO is extended.

This is consistent with ADR-0025's own OQ-CC-05 (docs/decisions/0025-cost-centre.md lines 297, 318): "Mandatory enforcement is partial ... applies only to the four wired posters; un-wired posters (AR receipt, cash, payroll) post untagged regardless. ... Documented limitation."

## The two fix options

(a) DEFAULT cost-centre threaded by automated posters.
- There is NO existing storage for a default cost-centre. No `default_cost_centre` column or setting exists anywhere (searched company/branch/settings tables; only an unrelated `PurchaseSettings` exists). So this requires a NEW config home. Cleanest precedent: a per-company `gl_config`-style mapping â€” but `GlConfigKey` maps to ChartOfAccount ids, not dimension values, so it's a poor fit. A dedicated per-company "default dimension value per slot" (e.g. a new column on `dimensions` pointing at a fallback `dimension_values` id, or a small `dimension_defaults` table, or per-branch on the Branch master) is the natural shape. Then every poster (8+ posters, ~30 line-build sites) would resolve and stamp the default onto each line. High blast radius; also semantically questionable (a "default" cost centre on AR/Cash/VAT control legs is meaningless â€” ADR-0025 line 196 explicitly decided balance-sheet control legs post untagged).

(b) Scope mandatory enforcement to user-entered manual journals only.
- `post()` already receives the discriminator: `JournalEntryDraft.sourceType()` (enum `JournalSourceType`, gl/domain/enums/JournalSourceType.java). Manual = `JournalSourceType.MANUAL` (the manual path sets it in JournalServiceImpl line 76-77; every automated poster sets a non-MANUAL source: SALES, STOCK_RECEIPT, COGS, PAYROLL, FA_*, AP_BILL, AR_*, CASH_*, etc.). Additionally `JournalEntryDraft.postedBy()` is non-null for human posts and NULL for SYSTEM auto-posters (documented at JournalEntryDraft.java line 20). So `post()` ALREADY knows whether a call is manual/user-entered vs event-driven â€” no schema change, no new flag needed. The fix is to guard Step 3 with `if (draft.sourceType() == JournalSourceType.MANUAL) { ...enforce... }` (or `postedBy != null`). Step 2 (value validity) stays unconditional. This is a ~3-line change confined to GLPostingServiceImpl.validateDimensions and matches ADR-0025's stated intent (wired-posters-only / governance applies to user entry).

## Recommendation

Option (b) is materially cleaner given the code. The discriminator already exists (`JournalEntryDraft.sourceType()` / `postedBy()`), so the change is a single guarded branch in `validateDimensions` with zero schema work and zero poster changes, and it aligns with ADR-0025's documented OQ-CC-05 limitation. Option (a) needs a new persistence home for the default (none exists), edits to 8+ posters / ~30 line-build sites, and would tag balance-sheet control legs that ADR-0025 deliberately leaves untagged. If FX later needs guaranteed dimension coverage on P&L legs for budgeting, that is better delivered as an FX enhancement that extends the wired posters' default-inheritance (option a, scoped to P&L legs only), NOT by globally enforcing mandatory on event-driven posts.

NOTE for FX builders: even under option (b), making a slot mandatory still requires extending `PostJournalRequest`/`PostJournalLineRequest` + `JournalServiceImpl.postManual` to actually carry dimension value uids, otherwise manual journals would be unable to satisfy the gate.

### FX touch points
- GLPostingServiceImpl.validateDimensions(Long, List<LineDraft>) â€” Step 3 mandatory loop (lines 264-288): guard with draft.sourceType()==JournalSourceType.MANUAL (or postedBy!=null) for option (b)
- GLPostingServiceImpl.post(JournalEntryDraft) line 112 â€” the single call site; would need to pass sourceType into validateDimensions (currently only companyId + lines are passed)
- JournalEntryDraft.sourceType() and JournalEntryDraft.postedBy() â€” existing discriminators distinguishing MANUAL/user posts from SYSTEM event-driven posts (postedBy NULL for auto-posters)
- JournalSourceType.MANUAL â€” the enum value that identifies user-entered journals (all automated posters use non-MANUAL sources)
- Dimension.mandatory / dimensions.is_mandatory column + DimensionController.setMandatory (PATCH /dimensions/uid/{uid}/mandatory) â€” where mandatory is toggled
- DimensionRepository.findMandatorySlots(companyId) â€” the query driving Step 3
- JournalServiceImpl.postManual (lines 63-83) + PostJournalLineRequest DTO â€” for option (b) to be usable, these must be extended to carry per-line dimension value uids (currently cannot)
- For option (a): NO existing default-cost-centre store â€” a new home is required (candidate: new dimension_defaults table, or default_value_id column on dimensions, or per-Branch setting); then thread into InventoryGlPoster, PayrollPostingHandler, FixedAssetGlPoster, GLPostingSafeInvoker.postSaleInNewTx, BillMatchServiceImpl, AR/Cash/Tax posters
- GlConfigKey enum â€” note it maps to ChartOfAccount ids, NOT dimension values, so it is NOT a fit for storing a default cost-centre

### Files of interest
- d:/My_Works/ERP/ERPCLEAN2/backend/src/main/java/com/erp/modules/gl/service/GLPostingServiceImpl.java
- d:/My_Works/ERP/ERPCLEAN2/backend/src/main/java/com/erp/modules/gl/domain/dto/JournalEntryDraft.java
- d:/My_Works/ERP/ERPCLEAN2/backend/src/main/java/com/erp/modules/gl/domain/enums/JournalSourceType.java
- d:/My_Works/ERP/ERPCLEAN2/backend/src/main/java/com/erp/modules/costing/repository/DimensionRepository.java
- d:/My_Works/ERP/ERPCLEAN2/backend/src/main/java/com/erp/modules/costing/domain/entity/Dimension.java
- d:/My_Works/ERP/ERPCLEAN2/backend/src/main/java/com/erp/modules/costing/service/DimensionResolverImpl.java
- d:/My_Works/ERP/ERPCLEAN2/backend/src/main/java/com/erp/modules/gl/service/GLPostingSafeInvoker.java
- d:/My_Works/ERP/ERPCLEAN2/backend/src/main/java/com/erp/modules/gl/events/SalesPostingHandler.java
- d:/My_Works/ERP/ERPCLEAN2/backend/src/main/java/com/erp/modules/gl/events/PayrollPostingHandler.java
- d:/My_Works/ERP/ERPCLEAN2/backend/src/main/java/com/erp/modules/stock/service/InventoryGlPoster.java
- d:/My_Works/ERP/ERPCLEAN2/backend/src/main/java/com/erp/modules/fixedassets/service/FixedAssetGlPoster.java
- d:/My_Works/ERP/ERPCLEAN2/backend/src/main/java/com/erp/modules/manufacturing/service/ManufacturingGlPoster.java
- d:/My_Works/ERP/ERPCLEAN2/backend/src/main/java/com/erp/modules/ap/service/BillMatchServiceImpl.java
- d:/My_Works/ERP/ERPCLEAN2/backend/src/main/java/com/erp/modules/gl/service/JournalServiceImpl.java
- d:/My_Works/ERP/ERPCLEAN2/backend/src/main/java/com/erp/modules/gl/domain/dto/PostJournalLineRequest.java
- d:/My_Works/ERP/ERPCLEAN2/backend/src/main/java/com/erp/api/DimensionController.java
- d:/My_Works/ERP/ERPCLEAN2/docs/decisions/0025-cost-centre.md
- d:/My_Works/ERP/ERPCLEAN2/docs/testing/ISSUES-REGISTER.md
