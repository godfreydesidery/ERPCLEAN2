# Entity Attribute Gap Review

> **Purpose.** A fix-oriented review of every JPA entity in the ERP: what attributes exist, what they
> mean, and what is **missing** versus what a production ERP needs. Each gap is prioritised so it can be
> turned into backlog items.
>
> **Status.** Living document — extended module group by module group.
> **Started:** 2026-06-17. **Author:** data-model review pass.

## How to read this

- Priorities: 🔴 **P1** (correctness / accounting integrity / compliance) · 🟡 **P2** (operational completeness) · 🟢 **P3** (nice-to-have / consistency).
- "Gap" = attribute absent from the entity class. **Caveat:** this review reads the **JPA entity classes only**.
  Unique constraints, indexes, FKs, check constraints and some defaults live in Flyway migrations / the DB,
  so *"not on the entity"* ≠ *"absent from the schema"*. Items that should be verified against migrations are tagged **(verify-migration)**.

## Platform conventions (apply to ~all entities; not repeated per entity)

- **`id`** (BIGINT identity) internal PK · **`uid`** (ULID, 26) external reference · **`version`** optimistic lock. Junction/line tables often use a plain `id` and skip `uid`/`version`.
- **`companyId`** tenant isolation (mandatory, immutable) · **`branchId`** branch scope (usually nullable).
- **Audit quad** `createdAt/createdBy/updatedAt/updatedBy` declared per-entity (not in the base class); append-only ledgers keep only `created*`. A separate audit-log module sits on top.
- **Money** `BigDecimal(19,4)` · **qty** `(19,6)` · **fx rate** `(19,8)`. **Enums** persisted as `STRING`. **Cross-module links** stored as the target's `uid` string (no hard FK).

---

## Cross-cutting findings (fix once, benefits everywhere)

| # | Priority | Finding | Affected |
|---|----------|---------|----------|
| X1 | 🔴 P1 | **Base/functional-currency amounts inconsistent.** `base*` amounts exist on `ArInvoice`/`SupplierBill` but **not** on `JournalLine`, `ArReceipt`, `ApPayment`, `CashTransaction` (only on allocations). Multi-currency GL reporting & reval need base amount + fx rate on every monetary line/header. | GL, AR, AP, cashbank |
| X2 | 🟡 P2 | **Enum-vs-String drift.** Free-text where an enum belongs: `tenderType` (AR/AP), `ApDebitNote.origin` (String) vs `ArCreditNote.origin` (enum), fx `rateType`/`status`, `VatReturnBand.taxBand`, `FxRevaluationRunLine.sourceType`. | AR, AP, tax, fx |
| X3 | 🟡 P2 | **Master soft-delete drift.** Some masters use `MasterStatus` (`ChartOfAccount`), others a boolean `active` (`CashBankAccount`, `WhtType`, `Currency`). Pick one convention. | many |
| X4 | 🟡 P2 | **Missing document `status`/lifecycle** on `JournalEntry`, `ApPayment`, `ArCreditNote`, `ApDebitNote` (no draft/void/applied states). | GL, AR, AP |
| X5 | 🔴 P1 | **WHT under-wired into AP.** Bills/payments don't reference `WhtType`/withheld amount; `WhtTransaction` lacks `tin` and remittance tracking — certificate & filing loop incomplete. | AP, tax |
| X6 | 🔴 P1 | **VAT bad-debt relief** not captured on `ArWriteOff`; `VatReturnBand` ignores the input side. | AR, tax |
| X7 | 🟡 P2 | **Dimensions stop at header** on `SupplierBill` (lines can't carry cost centre/department) while `JournalLine` has the full set. | AP |
| X8 | 🟡 P2 | **On-account asymmetry** — `ArReceipt` tracks `unallocatedAmount`; `ApPayment` and credit/debit notes don't track an unapplied balance. | AR, AP |

---

# Module: GL — General Ledger

### ChartOfAccount `chart_of_accounts`
Account master. `accountCode`+`name` identify; `accountType` (ASSET/LIABILITY/EQUITY/INCOME/EXPENSE) classifies; `normalBalance` (DR/CR) sets sign; `parentId` builds the hierarchy; `active`/`status` soft-delete.

| Priority | Missing attribute | Why it matters |
|----------|-------------------|----------------|
| 🔴 P1 | `allowManualPosting` / `isPostable` | Nothing prevents posting to a parent/summary account; leaf-only posting is a core integrity control. |
| 🔴 P1 | `isControlAccount` / controlType | AR/AP/bank control accounts must reconcile to subledgers and block direct manual postings. |
| 🟡 P2 | dimension-requirement flags (`requireCostCentre/Department/Project`) | Force analysis tagging (e.g. every expense line needs a cost centre). |
| 🟡 P2 | `currency` / currency-mode | Restrict an account to one currency (bank/clearing accounts). |
| 🟡 P2 | financial-statement mapping / `reportingGroup` / cash-flow class | BS/P&L/cash-flow generation beyond coarse `accountType`. |
| 🟢 P3 | `defaultTaxCode`, `effectiveFrom/To`, `description`, derived `level`/`isLeaf` | Convenience / reporting. |

### FiscalYear `fiscal_years`
`yearCode`, `startMonth`, `startDate/endDate`, `status`, close audit (`closedAt/closedBy`), `closingJournalUid` (year-end retained-earnings roll). Fairly complete.

| Priority | Missing attribute | Why it matters |
|----------|-------------------|----------------|
| 🟡 P2 | reopen audit (`reopenedAt/By`) | Reopening a closed year is sensitive and should be tracked. |
| 🟢 P3 | `name`, explicit adjustment-period concept | Convenience. |

### FiscalPeriod `fiscal_periods`
`periodNo`, `startDate/endDate`, `status`, close audit under a year. Posting gated to OPEN.

| Priority | Missing attribute | Why it matters |
|----------|-------------------|----------------|
| 🟡 P2 | module-level soft-close (e.g. AP locked, GL open) | One `status` can't express per-module locks. |
| 🟢 P3 | `name` ("Jan 2026"), reopen audit | Convenience / control. |

### GlConfig `gl_configs`
Maps logical `configKey` (AR_CONTROL, VAT_OUTPUT, FX_GAIN, RETAINED_EARNINGS…) → `accountId`. Automation backbone.

| Priority | Missing attribute | Why it matters |
|----------|-------------------|----------------|
| 🔴 P1 | `branchId` override | Mappings are company-wide only; multi-branch needs per-branch bank/clearing accounts. |
| 🟡 P2 | effective dating | No history when a mapping changes mid-year. |
| 🟡 P2 | unique `(companyId, configKey)` **(verify-migration)** | One mapping per key. |

### JournalBatch `journal_batches` (append-only)
`batchNumber`, `sourceType`, `postedAt/postedBy` (null = SYSTEM auto-poster).

| Priority | Missing attribute | Why it matters |
|----------|-------------------|----------------|
| 🟡 P2 | control totals (`totalDebit/totalCredit`) | Fast integrity checks. |
| 🟢 P3 | batch-level reversal linkage | Traceability. |

### JournalEntry `journal_entries` (append-only)
`batchId`, `entryNo`, `postingDate`, `fiscalPeriodId`, `description`, `sourceType`/`sourceRef`, `reversalOfId`, `postedAt/postedBy`.

| Priority | Missing attribute | Why it matters |
|----------|-------------------|----------------|
| 🟡 P2 | `reversedByEntryId` / `reversed` flag | Cheaply tell an entry *has been* reversed (inverse of `reversalOfId`). |
| 🟡 P2 | header currency + control totals | Currency only on lines; no header txn currency / `totalDebit/Credit`. |
| 🟡 P2 | `status`/DRAFT | Manual journals can't be parked/approved before posting. |
| 🟢 P3 | `entryType`, `valueDate`, external ref, attachment link | Classification / convenience. |

### JournalLine `journal_lines` (append-only)
`accountId`, `debitAmount`/`creditAmount`, `currency`, `lineMemo`, dimensions (`costCentreValueId`, `departmentValueId`, `dimension3/4ValueId`), project tags (`projectId`, `projectTaskId`, `projectCostType`).

| Priority | Missing attribute | Why it matters |
|----------|-------------------|----------------|
| 🔴 P1 | base/functional amount + line `fxRate` | Line holds DR/CR in `currency` but no base equivalent — see X1. Biggest systemic gap. |
| 🟡 P2 | subledger key (`partyType/partyId`) | Open-item drill-down from GL. |
| 🟡 P2 | line `taxCode/taxAmount` | Tax tagging at posting level. |
| 🟢 P3 | statistical `quantity/uom`, GL reconciliation marker | Quantity postings / account recon. |

---

# Module: AR — Accounts Receivable

### ArInvoice `ar_invoices`
`customerId`, `source`/`sourceInvoiceUid`, `documentNo`, `originalAmount` vs `outstandingAmount` (aging), `invoiceDate/dueDate`, `status`, full multi-currency (`fxRate`,`baseOriginalAmount`,`baseOutstandingAmount`,`rateAt`).

| Priority | Missing attribute | Why it matters |
|----------|-------------------|----------------|
| 🔴 P1 | credit-control: `dunningLevel`/`lastReminderDate`, `disputed`/`onHold`+reason | No collections workflow without these. |
| 🟡 P2 | `paymentTermsId`, settlement discount (`discountDueDate`/`discountAmount`) | Terms computed into `dueDate` but not stored; early-payment discounts. |
| 🟢 P3 | net/VAT split, `glControlAccountId` | Tax-on-payment, multi-control. |

### ArReceipt `ar_receipts`
`amount` vs `unallocatedAmount` (on-account), `tenderType`, `bankReference`, `cashBankAccountId`, `glEntryUid`, `status`, `fxRate/rateAt`.

| Priority | Missing attribute | Why it matters |
|----------|-------------------|----------------|
| 🔴 P1 | `baseAmount`/`baseUnallocatedAmount` | Has `fxRate` but no base amounts — inconsistent with `ArInvoice` (X1). |
| 🟡 P2 | `tenderType` as enum + instrument link (`chequeId`, mobile-money ref, card last-4) | Stronger typing; ties to `Cheque`. |
| 🟡 P2 | bounce/reversal (`reversedAt`/reversalOf) | Dishonoured-cheque handling. |
| 🟢 P3 | banking/cash-up batch id, payer name | Cash-up workflow. |

### ArReceiptAllocation `ar_receipt_allocations`
`receiptId`→`arInvoiceId`, `allocatedAmount`, `baseAllocatedAmount`, `settlementRate` (realized FX), `allocatedAt/By`.

| Priority | Missing attribute | Why it matters |
|----------|-------------------|----------------|
| 🟡 P2 | explicit `realizedFxGainLoss` | Audit-friendly (currently derivable). |
| 🟡 P2 | `discountAmount`/`writeOffAmount` at allocation | Settlement discount / residual write-off. |
| 🟡 P2 | unallocate/reversal audit | Reversing an allocation. |

### ArCreditNote `ar_credit_notes`
`arInvoiceId` (nullable), `amount`/`netAmount`/`vatAmount`, `reason`, `origin` (enum), `glEntryUid`.

| Priority | Missing attribute | Why it matters |
|----------|-------------------|----------------|
| 🔴 P1 | `outstandingAmount`/applied tracking (+ allocation table) | If applied over time, no unapplied balance (unlike receipts). |
| 🟡 P2 | `fxRate`/base amounts, `status` | Inconsistent with invoice; no lifecycle. |

### ArWriteOff `ar_write_offs`
`arInvoiceId`, `amount`, `reason`, `glEntryUid`.

| Priority | Missing attribute | Why it matters |
|----------|-------------------|----------------|
| 🔴 P1 | VAT bad-debt relief amount | Write-off usually reclaims output VAT (X6). |
| 🟡 P2 | approval linkage, `writeOffType`, recovery/reversal | Sensitive action; bad-debt vs small-balance; later recovery. |
| 🟢 P3 | `fxRate`/base amount | Multi-currency. |

---

# Module: AP — Accounts Payable

### SupplierBill `supplier_bills`
`supplierId`, `supplierInvoiceNo`+`billNumber`, `source`/`purchaseOrderUid`, `netAmount`/`vatAmount`/`grossAmount`/`outstandingAmount`, `status` (DRAFT default), 3-way match (`matchedAt/By`, `postedGlEntryUid`), header dimensions, full multi-currency. Most complete sub-module.

| Priority | Missing attribute | Why it matters |
|----------|-------------------|----------------|
| 🔴 P1 | WHT fields (`whtTypeId`/`whtAmount`) | Can't express withholding to deduct at payment (X5). |
| 🟡 P2 | `supplierBankAccountId`, payment hold (`onHold`+reason), `paymentTermsId`+discount | Pay-run essentials. |
| 🟡 P2 | `taxPointDate`/`receivedDate` | VAT point & processing SLA distinct from `billDate`. |

### SupplierBillLine `supplier_bill_lines`
`poLineUid`/`grLineUid`/`landedCostUid`, `productId`, `billedQty`, `unitCostAmount`, `lineNetAmount`, project tags.

| Priority | Missing attribute | Why it matters |
|----------|-------------------|----------------|
| 🔴 P1 | line-level VAT (`vatCode/vatRate/vatAmount`) | Header-only VAT can't represent mixed-rate bills. |
| 🔴 P1 | `glAccountId` on line | Where the debit goes for service/non-stock lines. |
| 🟡 P2 | line dimensions (`costCentreValueId/departmentValueId`) | Header-only dimensions can't split across cost centres (X7). |
| 🟢 P3 | `uom`, `lineDiscountAmount`, `assetId`/capitalization flag | Completeness. |

### ApPayment `ap_payments`
`supplierId` (nullable for run), `kind`, `amount`, `tenderType`, `cashBankAccountId`, `glEntryUid`, `fxRate/rateAt`.

| Priority | Missing attribute | Why it matters |
|----------|-------------------|----------------|
| 🔴 P1 | `unallocatedAmount` | Asymmetry with `ArReceipt`; supplier prepayments can't track remaining (X8). |
| 🔴 P1 | `baseAmount`, `whtAmount`, `chequeId` | No base on header (X1); withholding; cheque instrument link. |
| 🟡 P2 | `status`, `paymentRunId`, `tenderType` as enum | Void/cleared; batch runs. |

### ApPaymentAllocation `ap_payment_allocations`
`apPaymentId`→`supplierBillId`, `allocatedAmount`, `baseAllocatedAmount`, `settlementRate`.

| Priority | Missing attribute | Why it matters |
|----------|-------------------|----------------|
| 🟡 P2 | `allocatedAt/By` | AR allocation has them; here only `created*`. |
| 🟡 P2 | `discountAmount`/`writeOffAmount`, explicit realized FX, reversal audit | Settlement completeness. |

### ApDebitNote `ap_debit_notes`
`supplierBillId` (nullable), `amount`/`netAmount`/`vatAmount`, `reason`, `glEntryUid`, `origin`.

| Priority | Missing attribute | Why it matters |
|----------|-------------------|----------------|
| 🟡 P2 | `origin` as enum (currently String(100)) | Inconsistent with `ArCreditNote.origin` enum (X2). |
| 🟡 P2 | outstanding/applied tracking, `fxRate`/base, `status` | Parity with credit note. |
| 🟢 P3 | link to `PurchaseReturn`, dimensions | Traceability. |

### BillMatch `bill_match`
3-way match per line: `poUnitCostAmount`, `grReceivedQty`, `billedQty`, `priceVarianceAmount`/`Pct`, `qtyVariance`, `matchStatus`, tolerances, acceptance (`acceptedBy/At`). Javadoc references `uq_bill_match_line` **(verify-migration)**.

| Priority | Missing attribute | Why it matters |
|----------|-------------------|----------------|
| 🟡 P2 | split price- vs qty-match status; `matchType` (2-way/3-way); variance reason/code | Currently one `matchStatus`; disputes/accruals. |
| 🟢 P3 | explicit `poLineUid/grLineUid`, accrual/GRNI entry link, currency | Audit. |

---

# Module: Cashbank — Cash & Bank

### CashBankAccount `cash_bank_accounts`
`code`/`name`, `accountType` (CASH/BANK), bank details, `currency`, `glAccountId`, `isDefault`, `active`.

| Priority | Missing attribute | Why it matters |
|----------|-------------------|----------------|
| 🟡 P2 | international ids (`iban`/`swift`/`bic`/`sortCode`) | Only `bankAccountNo`+`bankBranch`. |
| 🟡 P2 | `openingBalance`/openingDate, `glClearingAccountId` | Reconciliation start; undeposited-funds/in-transit. |
| 🟡 P2 | treasury controls (`overdraftLimit`, `minimumBalance`), `lastReconciledDate/Balance` | Treasury. |
| 🟢 P3 | `MasterStatus` instead of boolean `active` | Consistency (X3). |

### CashTransaction `cash_transactions`
`direction` (IN/OUT), `amount`, `txnType`, `sourceRef`, `counterGlAccountId`, `journalEntryRef`, `cleared`+`clearedInReconciliationId`, `memo`.

| Priority | Missing attribute | Why it matters |
|----------|-------------------|----------------|
| 🔴 P1 | `fxRate`/`baseAmount` | Multi-currency movements (X1). |
| 🟡 P2 | `valueDate`, `statementLineRef`, `chequeId` | Bank value date; statement-line link. |
| 🟢 P3 | cached `runningBalance`, counterparty name, reversal | Convenience. |

### CashTransfer `cash_transfers`
`sourceAccountId`/`destinationAccountId`, `amount`, `reference`, paired `outTxnId`/`inTxnId`, `journalEntryRef`.

| Priority | Missing attribute | Why it matters |
|----------|-------------------|----------------|
| 🔴 P1 | cross-currency support (`sourceAmount/destinationAmount`+`fxRate`) | Single `amount`+`currency` assumes same currency. |
| 🟡 P2 | bank-fee fields (`chargeAmount`+fee GL account), `status` | Fees; in-transit transfers. |

### Cheque `cheques`
`payee`, `amount`, `issueDate`/`valueDate`, `status`, links `apPaymentUid`/`cashTransactionUid`, `clearedAt`/`cancelledAt`.

| Priority | Missing attribute | Why it matters |
|----------|-------------------|----------------|
| 🔴 P1 | inbound (received) cheque modelling (no `direction`) | Only outgoing; customer-cheque banking/bounce unmodelled. |
| 🟡 P2 | bounce (`bouncedAt`/`bounceReason`/`representedCount`), cheque-book/range, `staleDate`, drawee `bankName` | Dishonour; stock; auto-stale. |
| 🟢 P3 | print ref, void reason | Operations. |

### BankReconciliation `bank_reconciliations`
`statementDate`, `statementClosingBalance`, `clearedBookBalance`, `status`, `reconciledBy`/`completedAt`.

| Priority | Missing attribute | Why it matters |
|----------|-------------------|----------------|
| 🔴 P1 | `statementOpeningBalance` + computed reconciling `difference`/`unreconciledAmount` | Continuity check; the core rec output. |
| 🟡 P2 | outstanding cheques/deposits totals, `adjustmentJournalUid`, statement-file ref | Bank charges/interest found during rec. |

---

# Module: Tax — VAT & Withholding

### VatReturn `vat_returns`
`periodYear/Month`, `periodStart/End`, `dueDate`, `outputVat`/`inputVat`/`adjustmentsTotal`, `openingCredit`→`netVat`→`closingCredit`, `priorReturnId` (credit chain), filing audit. Solid.

| Priority | Missing attribute | Why it matters |
|----------|-------------------|----------------|
| 🟡 P2 | payment tracking (`paidAt`/`paidAmount`/`paymentReference`) | Filed ≠ paid. |
| 🟡 P2 | turnover figures (total sales/purchases incl. zero-rated/exempt) | Returns require turnover, not just VAT. |
| 🟡 P2 | amendment (`amendedReturnId`/`isAmendment`), penalty/interest | Corrections; late filing. |

### VatReturnBand `vat_return_bands`
`taxBand` (string), `taxableBase`, `outputVat`.

| Priority | Missing attribute | Why it matters |
|----------|-------------------|----------------|
| 🟡 P2 | input side (`inputBase`/`inputVat`) | Only output captured per band (X6). |
| 🟢 P3 | `bandRatePct`, `taxBand` as enum | Audit; typing (X2). |

### VatAdjustment `vat_adjustments`
`reason` (enum), `sign` (+/−), `amount`, `narrative`.

| Priority | Missing attribute | Why it matters |
|----------|-------------------|----------------|
| 🟡 P2 | `sourceRef`, input-vs-output indicator, GL/approval linkage | Traceability of the adjustment. |

### WhtType `wht_types`
`code`/`name`, `kind`, `ratePct`, `active`.

| Priority | Missing attribute | Why it matters |
|----------|-------------------|----------------|
| 🔴 P1 | `glAccountId` | WHT payable/receivable control per type. |
| 🟡 P2 | rate effective-dating, `thresholdAmount`, resident/non-resident scope | Rate history; min threshold. |
| 🟢 P3 | `MasterStatus` vs boolean `active` | Consistency (X3). |

### WhtTransaction `wht_transactions`
`whtTypeId`, `kind`, `partyKind`/`partyId`/`partyName`, `sourceRef`, `taxableBase`/`whtAmount`, `currency`, `certificateDate`, `journalEntryRef`.

| Priority | Missing attribute | Why it matters |
|----------|-------------------|----------------|
| 🔴 P1 | `tin` (party tax ID), remittance tracking (`remitted`/`remittanceRef`/period) | Essential on certificate; filing loop (X5). |
| 🟡 P2 | `ratePct` snapshot, `baseAmount`, distinct `certificateNumber` | Rate can change; base; cert numbering. |

---

# Module: FX — Currency & Revaluation

### Currency `currencies` (global, no `companyId`)
`code`, `name`, `symbol`, `minorUnits`, `active`, `status` (String).

| Priority | Missing attribute | Why it matters |
|----------|-------------------|----------------|
| 🟡 P2 | `isBaseCurrency`/functional flag, `numericCode`, rounding rule | Base currency; ISO numeric; cash rounding. |
| 🟢 P3 | `status` as `MasterStatus` | Consistency (X2/X3). |

### CurrencyRate `currency_rates` (per-tenant)
`fromCurrency`/`toCurrency`, `rate`, `effectiveDate`, `rateType` (SPOT default), `source`, `active`.

| Priority | Missing attribute | Why it matters |
|----------|-------------------|----------------|
| 🟡 P2 | `effectiveTo`, bid/ask/mid, `rateType` as enum | Validity range; rate kinds; typing. |
| 🟡 P2 | unique `(company, from, to, effectiveDate, rateType)` **(verify-migration)** | One rate per key. |

### FxRevaluationRun `fx_revaluation_runs`
`fiscalPeriodId`, `postingDate`/`spotRateDate`, `status`, `totalGainAmount`/`totalLossAmount`/`netAdjustmentAmount`, `glEntryUid`+`reversalGlEntryUid`, `executedAt`.

| Priority | Missing attribute | Why it matters |
|----------|-------------------|----------------|
| 🟡 P2 | `rateType` used, scope summary, `reversalDate/PeriodId`, explicit `executedBy` | Which rate basis; what was included. |

### FxRevaluationRunLine `fx_revaluation_run_lines`
`sourceType` (AR/AP/CASH), `currency`, `controlAccountId`, `outstandingTxnAmount`, `carryingBaseAmount`, `spotRate`, `revaluedBaseAmount`, `adjustmentAmount`.

| Priority | Missing attribute | Why it matters |
|----------|-------------------|----------------|
| 🟡 P2 | per-open-item detail, `priorRate`, GL line linkage | Drill-down; rate trail. |
| 🟢 P3 | `sourceType` as enum | Typing (X2). |

---

## Cross-cutting findings — Sales O2C

| # | Priority | Finding | Affected |
|---|----------|---------|----------|
| X9 | 🔴 P1 | **No contacts / addresses child tables for parties.** `PartyBase` carries a single phone/email + one physical/postal address. Real customers/suppliers need multiple contacts and multiple **bill-to / ship-to** addresses. Knock-on: sales/delivery docs have no `shipToAddressId`/`billToAddressId`. | parties, sales |
| X10 | 🔴 P1 | **Supplier master is too thin.** `Supplier` adds only `supplierKind` — no payment terms (Customer has `paymentTermsDays`), no bank account, no default WHT type, no lead time. Blocks AP due-date calc & payments. | parties, AP |
| X11 | 🔴 P1 | **Product lacks inventory-planning + tracking attributes.** No `reorderLevel/min/max/safetyStock/leadTimeDays`, no `batchTracked/serialTracked/expiryTracked` flags, no `purchasable` flag / preferred supplier, no per-product `costingMethod`. | products, stock |
| X12 | 🟡 P2 | **`category` is a free String, not a master.** On `Product` and `Promotion.targetCategory` — no category hierarchy, inconsistent grouping, no per-category GL mapping. | products |
| X13 | 🟡 P2 | **Pricing effective-dating inconsistent.** `CustomerPrice` has `effectiveFrom/To`; `PriceTier` and `ProductPrice` don't. `PriceTier` has `minQty` but no `maxQty`. | products |
| X14 | 🟡 P2 | **No `customerPoNumber` on sales docs.** Quotation/SalesOrder/SalesInvoice can't record the customer's own PO reference (a near-universal requirement). | sales |
| X15 | 🟡 P2 | **Applied promotion not linked.** Order/invoice lines have discount fields but no `promotionId` recording *which* promotion produced the discount. | sales, products |
| X16 | 🟡 P2 | **Two composition mechanisms.** `ProductComponent` (kit) and `Bom`/`BomComponent` (manufacturing) overlap; clarify which is authoritative for which scenario. `BomComponent.qtyPer` has **no UoM**. | products |
| X17 | 🟢 P3 | **`SalesReturnLine.vatStatus` persisted as plain String** (peers use the `VatStatus` enum) — see X2. | sales |

---

# Module: Parties

> All party masters extend **`PartyBase`** (shared): `code`, `partyType`, `displayName`, `legalName`, `tin`, `vatRegistered`, `vrn`, `businessRegNo`, `mobileMoneyNo`, `phone`, `email`, `physicalAddress`, `postalAddress`, `region`, `district`, `status`, audit quad. Gaps below are **in addition to** the shared X9 (contacts/addresses) finding.

### PartyBase (shared superclass)
| Priority | Missing attribute | Why it matters |
|----------|-------------------|----------------|
| 🔴 P1 | child **contacts** + **addresses** tables | Single contact/address can't model multiple people or bill-to/ship-to (X9). |
| 🟡 P2 | `country` | Only region/district (TZ-centric); international parties need country. |
| 🟢 P3 | `website`, `notes`, `imageUrl` | Profile completeness. |

### Customer `customers` (adds: `customerKind`, `creditLimit` (Money), `paymentTermsDays`)
| Priority | Missing attribute | Why it matters |
|----------|-------------------|----------------|
| 🔴 P1 | `creditStatus`/`onHold`/credit-block flag | Credit limit exists but no enforcement state (over-limit hold, stop-supply). |
| 🟡 P2 | default `priceListId`/`priceTierId` | No way to assign a customer's standard price list (only per-product `CustomerPrice`). |
| 🟡 P2 | `defaultAgentId`, default `routeId`, `territoryId`, `customerGroup`/segment | Sales-rep, routing, segmentation & group pricing. |
| 🟡 P2 | `taxExempt`/exemption ref, `defaultCurrency` | Tax handling; FC customers. |
| 🟢 P3 | `creditRating`, `onboardingDate`, loyalty | CRM/credit. |

### Supplier `suppliers` (adds: `supplierKind` only)
| Priority | Missing attribute | Why it matters |
|----------|-------------------|----------------|
| 🔴 P1 | `paymentTermsDays`/terms | No terms → AP due dates can't be derived (Customer has it; Supplier doesn't) — X10. |
| 🔴 P1 | bank account details (or child table) | Can't pay the supplier; ties to `SupplierBill.supplierBankAccountId` gap. |
| 🟡 P2 | default `whtTypeId`, `defaultCurrency`, `leadTimeDays`, `minOrderValue` | Withholding; FC; procurement planning. |
| 🟢 P3 | our `creditLimit` with supplier, `priceListId` | Purchasing terms. |

### Agent `agents` (adds: `agentKind`, `appUserId`)
| Priority | Missing attribute | Why it matters |
|----------|-------------------|----------------|
| 🟡 P2 | `commissionRate`/commission scheme | Agents/reps usually have commission terms — none modelled. |
| 🟡 P2 | default `routeId`/`territoryId`, sales target/quota | Routing & performance. |

### OtherParty `other_parties` (adds: `otherKind` String)
| Priority | Missing attribute | Why it matters |
|----------|-------------------|----------------|
| 🟢 P3 | `otherKind` as enum | Free String — typing/consistency (X2). |

### CustomerBranch / SupplierBranch / AgentBranch / OtherPartyBranch `*_branch`
Junctions: party `@ManyToOne` + `branchId` + `assignedAt/assignedBy`.
| Priority | Missing attribute | Why it matters |
|----------|-------------------|----------------|
| 🟢 P3 | `active`/`unassignedAt` | Can't deactivate an assignment without deleting the row. |

### PartyCodeSequence `party_code_sequence`
Per-`partyKind` counter (`nextValue`, `@Version`). Complete for its purpose.

---

# Module: Products

### Product `products`
`code`, `name`, `description`, `type`, `sellable`, `stockable`, `baseUnit` (→UoM), `cost` (Money), `vatStatus`, `status`, `category` (String).

| Priority | Missing attribute | Why it matters |
|----------|-------------------|----------------|
| 🔴 P1 | inventory planning: `reorderLevel`, `reorderQty`, `minStock`, `maxStock`, `safetyStock`, `leadTimeDays` | No reorder/replenishment data anywhere on the product (X11). |
| 🔴 P1 | tracking flags: `batchTracked`, `serialTracked`, `expiryTracked`, `shelfLifeDays` | Stock has batch/serial entities but the product doesn't declare what it tracks (X11). |
| 🔴 P1 | `purchasable` flag + `preferredSupplierId` | Has `sellable`/`stockable` but no buyable flag or default supplier. |
| 🟡 P2 | `costingMethod` (FIFO/MA/STD) | Single `cost` Money; per-product valuation method not captured (planned moving-avg build). |
| 🟡 P2 | `categoryId` (master FK, not String) | Category hierarchy + per-category GL mapping (X12). |
| 🟡 P2 | GL mapping: `incomeAccountId`/`cogsAccountId`/`inventoryAccountId` (product or category) | Where sales/COGS/inventory post (likely config today; per-product override is standard). |
| 🟡 P2 | `brand`, `manufacturer`, `weight`/`volume`/dimensions, `hsCode` | Logistics, customs, search. |
| 🟢 P3 | `imageUrl`, `salesUnit`/`purchaseUnit` defaults, `notes` | UX/operations. |

### UnitOfMeasure `units_of_measure`
`code`, `name`, `status`.
| Priority | Missing attribute | Why it matters |
|----------|-------------------|----------------|
| 🟡 P2 | `dimensionType` (weight/volume/count), `decimalPlaces`/`isFractional` | Conversions are per-product (`ProductBulkPack`); no global UoM family or fractional control. |
| 🟢 P3 | `symbol` | Display. |

### ProductBranch `product_branch`
Junction: `product` + `branchId` + `assignedAt/By`.
| Priority | Missing attribute | Why it matters |
|----------|-------------------|----------------|
| 🟢 P3 | branch-level overrides (`active`, branch reorder levels, branch price) | Per-branch product behaviour. |

### ProductBarcode `product_barcodes`
`product`, `companyId`, `barcode`, `primary`.
| Priority | Missing attribute | Why it matters |
|----------|-------------------|----------------|
| 🟡 P2 | `uomId`/pack ref, `barcodeType` (EAN/UPC/CODE128) | A barcode usually identifies a specific pack size & symbology. |

### ProductBulkPack `product_bulk_packs`
`product`, `unit`, `factorToBase` (pack→base conversion).
| Priority | Missing attribute | Why it matters |
|----------|-------------------|----------------|
| 🟡 P2 | `barcode` per pack, `isPurchaseDefault`/`isSaleDefault` | Pack-level identity & default selection. |

### ProductComponent `product_components`
Kit composition: `composedProduct`, `componentProduct`, `quantity`.
| Priority | Missing attribute | Why it matters |
|----------|-------------------|----------------|
| 🟡 P2 | clarify overlap with `Bom`; add `lineNo`, `unitId` | Two composition mechanisms (X16). |

### PriceList `price_lists`
`code`, `name`, `status`.
| Priority | Missing attribute | Why it matters |
|----------|-------------------|----------------|
| 🟡 P2 | `currency`, `effectiveFrom/To`, `priceIncludesVat`, `isDefault`, scope (customer group) | List-level currency/validity/tax-mode. |

### PriceTier `price_tiers`
Quantity-break price: `productId`, `priceListId`, `minQty`, `unitPriceAmount`, `currency`, `status`.
| Priority | Missing attribute | Why it matters |
|----------|-------------------|----------------|
| 🟡 P2 | `maxQty`, `effectiveFrom/To` | Only `minQty`; no validity window (X13). |

### ProductPrice `product_prices`
Per-list price: `product`, `priceList`, `price` (Money, bare embed).
| Priority | Missing attribute | Why it matters |
|----------|-------------------|----------------|
| 🟡 P2 | `effectiveFrom/To`, relationship to `PriceTier` | Overlap & no dating (X13). |

### CustomerPrice `customer_prices`
`customerId`, `productId`, `unitPriceAmount`, `currency`, `effectiveFrom/To`, `status`. (Has dating — good.)
| Priority | Missing attribute | Why it matters |
|----------|-------------------|----------------|
| 🟢 P3 | `minQty`, `priceListId` link | Volume + provenance. |

### Promotion `promotions`
`code`, `name`, `target`, `targetProductId`, `targetCategory`, `effect`, `effectValue`, `effectiveFrom/To`, `priority`, `status`.
| Priority | Missing attribute | Why it matters |
|----------|-------------------|----------------|
| 🟡 P2 | customer scope/segment, `minQty`/`minAmount` threshold, `usageLimit`/`couponCode`, branch scope, `combinable` | Targeting & guardrails for promotions. |

### Bom `boms`
Versioned: `parentProductId`, `versionNo`, `status`, `outputQty`, `yieldPercent`, `effectiveFrom/To`, `sourceBomUid`, `activatedAt/archivedAt`. Strong.
| Priority | Missing attribute | Why it matters |
|----------|-------------------|----------------|
| 🟡 P2 | `bomType` (manufacturing/phantom/kit), `routingId`, std-cost rollup snapshot | Type clarity; link to operations. |

### BomComponent `bom_components`
`bomId`, `lineNo`, `componentProductId` (+code/name snapshot), `qtyPer`, `sourcing` (BUY/MAKE), `scrapPercent`, `reference`.
| Priority | Missing attribute | Why it matters |
|----------|-------------------|----------------|
| 🟡 P2 | `unitId` for `qtyPer`, `operationSeq`, `optional`/substitute | No UoM on component qty; routing link; alternates. |

### CodeSequence `code_sequence`
Per-`entityKind` counter (`nextValue`, `@Version`). Complete for purpose.

---

# Module: Sales (O2C documents)

> Sales lines consistently **snapshot** `productCode/productName/unitId/unitName` and keep both entered-unit qty and **base-unit qty** — good for immutability. Common gap across docs: **no `customerPoNumber`** (X14), **no `shipTo/billTo` address** (X9), **no applied `promotionId`** (X15).

### Quotation `quotations`
`quoteNumber`, `status`, `customerId`, `agentId`, `currency`, `quoteDate`, `validUntil`, doc-level discount, net/vat/gross totals, lifecycle stamps (`sentAt/acceptedAt/rejectedAt/expiredAt`), `convertedOrderUid`, `sourceOpportunityUid`.
| Priority | Missing attribute | Why it matters |
|----------|-------------------|----------------|
| 🟡 P2 | `customerPoNumber`, `paymentTermsId`, `revisionNo`, `probability` | Customer ref; terms; quote revisions; CRM. |

### QuotationLine `quotation_lines`
`productId`(+snapshot), `quantity`/`qtyInBase`, `listPriceAmount`/`unitPriceAmount`, line discount, `vatStatus`/`vatRate`, net/vat/gross.
| Priority | Missing attribute | Why it matters |
|----------|-------------------|----------------|
| 🟢 P3 | `promotionId`, `costAmount`/margin preview | Promotion link; margin at quote. |

### SalesOrder `sales_orders`
Rich lifecycle + source links (`sourceQuotation/Opportunity/Blanket/StandingUid`), totals, `confirmedAt/cancelledAt/cancelReason`, project tags.
| Priority | Missing attribute | Why it matters |
|----------|-------------------|----------------|
| 🟡 P2 | `customerPoNumber`, `requestedDeliveryDate`/`promisedDate`, `paymentTermsId`, `shipToAddressId`/`billToAddressId`, default `warehouseId`/`stockLocationId` | Customer ref, delivery promising, terms, addresses, fulfilment source. |

### SalesOrderLine `sales_order_lines`
Very rich: `qtyOrdered`+base, `qtyFulfilledBase`/`qtyInvoicedBase`/`qtyReservedBase`, price + `priceOverridden`/`overriddenBy`, `vatStatus`/`vatRate`, `fulfilmentMode` (OWN_STOCK/dropship + `dropshipSupplierId`/`dropshipPoUid`/`dropshipUnitCostAmount`), `priceSource`, project tags.
| Priority | Missing attribute | Why it matters |
|----------|-------------------|----------------|
| 🟡 P2 | `promotionId`, per-line `requestedDate`, `stockLocationId`, `discountReason` | Promo link; line-level promising & sourcing. |

### SalesInvoice `sales_invoices`
Very rich: `documentType`, `status`, `customerId`/`agentId`, totals, **`taxSummary` (jsonb)**, finalise/void audit, multi-currency (`fxRate`/`baseGrossTotalAmount`/`rateAt`), `origin`, `sourceOrderUid`/`sourceDeliveryUid`, `routeId`, dimensions, project, `posSessionId`.
| Priority | Missing attribute | Why it matters |
|----------|-------------------|----------------|
| 🟡 P2 | `dueDate`/`paymentTermsId`, `customerPoNumber`, `shipTo/billTo` snapshot | Due date lives only in AR; customer ref; address snapshot for the printed doc. |
| 🟢 P3 | header `outstandingAmount`/`paidAmount` cache | Convenience (AR is source of truth). |

### SalesInvoiceLine `sales_invoice_lines`
Snapshot + qty/price + override + `vatStatus`/`vatRate` + net/vat/gross + `priceSource` + project tags. (Owns `uid` via `@PrePersist`; not a `UidEntity`.)
| Priority | Missing attribute | Why it matters |
|----------|-------------------|----------------|
| 🟡 P2 | `costAmount`/COGS per line (margin), `promotionId`, `stockLocationId` | No line-level cost → no invoice-time margin reporting. |

### SalesInvoicePayment `sales_invoice_payments`
POS/immediate payment: `tenderType`, `amount`, `changeAmount`, `reference`, `receivedAt/By`. (created-only audit.)
| Priority | Missing attribute | Why it matters |
|----------|-------------------|----------------|
| 🟡 P2 | `cashBankAccountId`/till, `chequeId`, structured mobile-money/card fields | Generic `reference` only; ties payment to an account/instrument. |

### Delivery `deliveries`
`deliveryNumber`, `salesOrderId`/`Uid`, `status`, `customerId`, `deliveryDate`, `confirmedAt`, `cogsGlEntryUid`, project tags.
| Priority | Missing attribute | Why it matters |
|----------|-------------------|----------------|
| 🟡 P2 | `shipToAddress`, carrier/vehicle/driver/`trackingNo`, `dispatchedAt`/POD `receivedAt`, `routeId` | No logistics/proof-of-delivery data. |

### DeliveryLine `delivery_lines`
`qtyDelivered`+base, `qtyInvoicedBase`, `returnedQtyBase`, `issueValueAmount` (COGS), snapshot.
| Priority | Missing attribute | Why it matters |
|----------|-------------------|----------------|
| 🟡 P2 | `stockLocationId`/`batchId`/`serialNo` issued | Which bin/batch/serial was picked (ties to stock traceability). |

### SalesReturn `sales_returns`
`returnNumber`, `status`, `deliveryId`/`Uid`, `salesOrderUid`, `customerId`, `returnDate`, `creditNoteUid`, `cogsReversalGlEntryUid`, `reason`, net/vat/gross.
| Priority | Missing attribute | Why it matters |
|----------|-------------------|----------------|
| 🟡 P2 | `restockLocationId`, `condition` (resalable/damaged), `refundMethod` | Where/whether goods re-enter stock. |

### SalesReturnLine `sales_return_lines`
`qtyReturned`+base, price/discount, **`vatStatus` as String**, net/vat/gross.
| Priority | Missing attribute | Why it matters |
|----------|-------------------|----------------|
| 🟢 P3 | `vatStatus` as enum (X17), `batchId`/`serialNo`, `restocked` flag | Typing; restock traceability. |

### TaxRate `tax_rates`
`vatStatus` → `rate`, `status`.
| Priority | Missing attribute | Why it matters |
|----------|-------------------|----------------|
| 🟡 P2 | `effectiveFrom/To` (rate history), `name`, `taxType` | Rate changes over time; VAT-only today. |

### PosTill `pos_tills`
`code`, `name`, `branchId`, `cashBankAccountId`, `status`.
| Priority | Missing attribute | Why it matters |
|----------|-------------------|----------------|
| 🟢 P3 | `defaultPriceListId`, device/terminal id | POS config. |

### PosSession `pos_sessions`
`posTillId`, `cashierId`, `sessionNumber`, `status`, `openedAt`/`closedAt`/`reconciledAt`, `openingFloatAmount`, `countedCashAmount`/`expectedCashAmount`/`varianceAmount`, `varianceJournalId`. (Uses `openedAt` instead of `createdAt`.)
| Priority | Missing attribute | Why it matters |
|----------|-------------------|----------------|
| 🟡 P2 | per-tender expected/counted (card/mobile, not just cash) | Only cash is reconciled at close. |

### PosSessionPayout `pos_session_payouts`
`payoutType`, `amount`, `reason`. Complete for purpose.

### BlanketOrder `blanket_orders`
`orderNumber`, `customerId`, `currency`, `validFrom/To`, `totalCommittedAmount`/`totalDrawnAmount`, `status`.
| Priority | Missing attribute | Why it matters |
|----------|-------------------|----------------|
| 🟢 P3 | price-protection/fixed-price flag, min/max release qty | Contract terms. |

### BlanketOrderLine `blanket_order_lines`
`committedQtyBase`/`drawnQtyBase`, `unitPriceAmount`, snapshot. Complete for purpose.

### StandingOrder `standing_orders`
`orderNumber`, `customerId`, `frequency`, `startDate`/`endDate`/`nextRunDate`, `status`.
| Priority | Missing attribute | Why it matters |
|----------|-------------------|----------------|
| 🟡 P2 | `lastRunDate`, `occurrencesGenerated`/`maxOccurrences`, `autoConfirm` | Recurrence bookkeeping & control. |

### StandingOrderLine `standing_order_lines`
`qty`/`qtyBase`, `unitPriceAmount`, snapshot. Complete for purpose.

---

## Cross-cutting findings — Purchases P2P

| # | Priority | Finding | Affected |
|---|----------|---------|----------|
| X18 | 🔴 P1 | **PO has no tax breakdown.** `PurchaseOrder`/`PurchaseOrderLine` carry only `orderTotalAmount`/`lineTotalAmount` (no net/vat/gross or `vatStatus`/`vatRate`) — unlike sales orders. Weakens commitment accuracy and 3-way matching. | purchases |
| X19 | 🟡 P2 | **StockMovement doesn't reference batch/serial.** The append-only movement ledger has no `batchId`/`serialId`/`lotNumber`, so lot/serial traceability isn't carried through the ledger even though `StockBatch`/`StockSerial` exist. | stock |
| X20 | 🟡 P2 | **No on-order / available-to-promise.** `StockOnHand` tracks `reservedQty` but not incoming qty on open POs, so ATP can't be computed. | stock, purchases |
| X21 | 🟡 P2 | **Currency length inconsistency.** `StockTransferLine`/`StockCountLine` use `currency length=10`; everywhere else it's `length=3`. | stock |
| X22 | 🟡 P2 | **String where enum/FK belongs.** `transferMode`, `countType`, requisition `convertedToType`/`approvalStatus` are Strings; `PurchaseRequisition.costCentreCode` is a String, not a `dimension_values` FK like GL. | purchases, stock |
| X23 | 🟡 P2 | **No receiving location on PO/PO line.** Own-stock receipts have no target `stockLocationId` on the order. | purchases, stock |

---

# Module: Purchases (P2P documents)

> Purchase lines snapshot `productCode/productName/unitId/unitName` and keep entered + base qty, consistent with sales.

### PurchaseRequisition `purchase_requisitions`
Full workflow: `requisitionNumber`, `status`, `requiredByDate`, `costCentreCode`, approval (`approvalRequestUid`/`approvalStatus`), conversion (`convertedToType`/`convertedToUid`), lifecycle stamps + actor/reason for submit/approve/reject/convert/cancel.
| Priority | Missing attribute | Why it matters |
|----------|-------------------|----------------|
| 🟡 P2 | `costCentreValueId`/`departmentValueId` (FK, not String code) | Align with GL dimensions (X22). |
| 🟡 P2 | `priority`/urgency, `budgetLineId`/budget check, `preferredSupplierId` | Prioritisation; budget control at request time. |
| 🟢 P3 | header `totalEstimatedAmount` | Quick value view. |

### PurchaseRequisitionLine `purchase_requisition_lines`
`productId`(+snapshot), `requestedQty`+base, `estimatedUnitCost`, `currency`, `note`.
| Priority | Missing attribute | Why it matters |
|----------|-------------------|----------------|
| 🟡 P2 | per-line `requiredByDate`, `suggestedSupplierId`, `budgetLineId`, converted-PO-line link | Line-level promising, sourcing & budget. |

### Rfq `rfqs`
`rfqNumber`, `status`, `sourceRequisitionUid`, `responseDueDate`, `awardedQuoteUid`/`awardedPoUid`, lifecycle stamps.
| Priority | Missing attribute | Why it matters |
|----------|-------------------|----------------|
| 🟡 P2 | `awardReason`/justification, requested `paymentTerms`/`deliveryTerms` | Audit of award; terms being solicited. |

### RfqLine `rfq_lines`
`productId`(+snapshot), `quantity`+base.
| Priority | Missing attribute | Why it matters |
|----------|-------------------|----------------|
| 🟡 P2 | `requiredByDate`, `specification`/notes | RFQ lines usually carry detailed specs. |

### RfqSupplier `rfq_supplier`
Junction: `rfqId`, `supplierId`, `sentAt`. (created-only audit.)
| Priority | Missing attribute | Why it matters |
|----------|-------------------|----------------|
| 🟡 P2 | `respondedAt`/`responseStatus` (responded/declined), quote link | Track who responded vs not. |

### SupplierQuote `supplier_quotes`
`quoteNumber`, `rfqId`/`Uid`, `supplierId`(+snapshot), `status`, `validUntil`, **`leadTimeDays`**, `quoteTotalAmount`, `currency`.
| Priority | Missing attribute | Why it matters |
|----------|-------------------|----------------|
| 🟡 P2 | `paymentTerms`/`deliveryTerms` (incoterms), supplier's own quote ref, evaluation `score`/rank, warranty | Bid evaluation & terms capture. |

### SupplierQuoteLine `supplier_quote_lines`
`rfqLineId`, `productId`(+snapshot), `quotedQty`+base, `unitPriceAmount`, `lineTotalAmount`, `currency`.
| Priority | Missing attribute | Why it matters |
|----------|-------------------|----------------|
| 🟡 P2 | per-line `leadTimeDays`, `minOrderQty`, `discountPercent`, VAT, spec-compliance note | Richer comparison. |

### PurchaseOrder `purchase_orders`
`orderNumber`, `status`, `supplierId`(+snapshot), `currency`, `orderTotalAmount`, `expectedDate`, lifecycle (`ordered/closed/voided` + actor/reason), `approvalStatus`/`approvalRequestUid`, source links (`sourceQuoteUid`/`sourceRequisitionUid`), drop-ship (`shipToCustomerId`/`sourceSalesOrderUid`).
| Priority | Missing attribute | Why it matters |
|----------|-------------------|----------------|
| 🔴 P1 | net/vat/gross tax breakdown | Only `orderTotalAmount` — see X18. |
| 🟡 P2 | `paymentTermsId`/`deliveryTerms`, receiving `stockLocationId`/delivery address, `buyerId`, `fxRate`/base amount | Terms, where to receive (X23), FC commitment. |
| 🟡 P2 | `invoicedAmount`/billing status at header | Track billed vs ordered. |

### PurchaseOrderLine `purchase_order_lines`
`productId`(+snapshot), `orderedQty`+base, `receivedQtyInBase`, `unitCostAmount`, `lineTotalAmount`, `currency`. (Owns `uid`; `@ManyToOne PurchaseOrder`.)
| Priority | Missing attribute | Why it matters |
|----------|-------------------|----------------|
| 🔴 P1 | line VAT (`vatStatus`/`vatRate`/`vatAmount`) | Consistent with X18. |
| 🟡 P2 | `billedQtyInBase` (invoiced tracking), receiving `stockLocationId`, `glAccountId`/expense for non-stock, line dimensions, `requiredByDate`, `cancelledQty` | Matching, sourcing, analysis. |

### PurchaseSettings `purchase_settings`
Singleton: `poApprovalThresholdAmount`, `poApprovalEnabled`, `currency`.
| Priority | Missing attribute | Why it matters |
|----------|-------------------|----------------|
| 🟡 P2 | default `paymentTermsId`, default receiving location, **3-way-match tolerance defaults**, auto-close tolerance, requisition-approval settings | Central P2P policy (tolerances currently only per `BillMatch` line). |

---

# Module: Stock / Inventory

### StockLocation `stock_locations`
`code`, `name`, `locationType`, `isDefault`, `status`.
| Priority | Missing attribute | Why it matters |
|----------|-------------------|----------------|
| 🟡 P2 | `parentLocationId` (warehouse→zone→bin), `allowNegative`, `pickable`/`sellable`, per-location `glAccountId` | Hierarchy, negative-stock policy, ATP, inventory account override. |
| 🟢 P3 | address, capacity | Logistics. |

### StockOnHand `stock_on_hand`
Per location+product balance: `quantity` (signed), `reorderLevel`, **`avgCost`** (moving average), **`onHandValue`**, **`reservedQty`**, `@Version`. Solid valuation core.
| Priority | Missing attribute | Why it matters |
|----------|-------------------|----------------|
| 🟡 P2 | `incomingQty`/on-order, `availableQty` (or derive), `maxQty`, `lastMovementAt`/`lastCountedAt` | ATP (X20); min/max planning; activity timestamps. |
| 🟢 P3 | per-batch breakdown note | On-hand is per location+product; batch qty lives in `StockBatch` (reconcile). |

### StockMovement `stock_movements` (append-only)
`movementType`, `quantity` (signed), source refs (`sourceEventUid`/`sourceDocumentType`/`sourceDocumentUid`), `reasonCode`, `note`, `unitCostAmount`, `valueAmount`, dimensions (cost-centre/dept/project), `occurredAt`. Rich ledger.
| Priority | Missing attribute | Why it matters |
|----------|-------------------|----------------|
| 🟡 P2 | `batchId`/`serialId`/`lotNumber` | Lot/serial traceability not carried through the ledger (X19). |
| 🟢 P3 | `balanceAfter`/running balance, counterparty location for transfers | Audit/reporting convenience. |

### StockBatch `stock_batches`
`lotNumber`, `manufactureDate`, `expiryDate`, `qtyOnHand` (per location+product).
| Priority | Missing attribute | Why it matters |
|----------|-------------------|----------------|
| 🟡 P2 | `status` (quarantine/released/expired), batch `unitCost`/value, `supplierId`/source `grnUid`, `countryOfOrigin` | Quality holds; batch valuation; provenance. |

### StockSerial `stock_serials`
`serialNumber`, `serialStatus`, `locationId` (null when issued), `receivedDocumentUid`/`issuedDocumentUid`.
| Priority | Missing attribute | Why it matters |
|----------|-------------------|----------------|
| 🟡 P2 | `batchId` link, `warrantyExpiry`, sold-to `customerId`, `unitCost`, `supplierId` | Warranty/recall, valuation, provenance. |

### StockTransfer `stock_transfers`
`transferNumber`, `status`, `transferMode` (String), source/dest branch+location, `transferDate`, `dispatchedAt`/`receivedAt`, `notes`.
| Priority | Missing attribute | Why it matters |
|----------|-------------------|----------------|
| 🟡 P2 | in-transit location/handling, `dispatchedBy`/`receivedBy`, `glEntryUid` (inter-branch), `expectedArrivalDate`, `transferMode` as enum (X22) | In-transit ownership, audit, GL for inter-branch. |

### StockTransferLine `stock_transfer_lines`
`productId`(+snapshot), `qtyTransferred`+base, `valueAmount`, `currency` (len 10).
| Priority | Missing attribute | Why it matters |
|----------|-------------------|----------------|
| 🟡 P2 | `qtyDispatched` vs `qtyReceived` (partial receipt), `batchId`/`serialNo`, `unitCostAmount`; fix `currency` length (X21) | Partial transfers; lot tracking; precision consistency. |

### StockCount `stock_counts`
`countNumber`, `status`, `countType` (String), `locationId`, `countDate`, `frozenAt`/`postedAt`/`cancelledAt`, `varianceGlEntryUid`, `notes`.
| Priority | Missing attribute | Why it matters |
|----------|-------------------|----------------|
| 🟡 P2 | `countType` as enum (FULL/CYCLE/SPOT — X22), `countedBy`/`approvedBy`, scope/category filter, `recountRequired` | Typing; segregation of duties; cycle-count scoping. |

### StockCountLine `stock_count_lines`
`systemQty`, `countedQty`, `varianceQty`, `unitCostAmount`, `varianceValue`, `reasonCode`, `movementUid`, `currency` (len 10).
| Priority | Missing attribute | Why it matters |
|----------|-------------------|----------------|
| 🟡 P2 | `batchId`/`serialNo`, `recountedQty`/second count, `countedBy`; fix `currency` length (X21) | Batch counting; recount workflow. |

---

## Cross-cutting findings — HR / Payroll

> Module is clearly **Tanzania-localized** (PAYE bands, NSSF, HESLB, WCF, SDL, TIN) — good. Statutory rate/band sets are effective-dated and snapshotted per payroll line (`PayrollStatutorySnapshot`), which is excellent for auditability.

| # | Priority | Finding | Affected |
|---|----------|---------|----------|
| X24 | 🔴 P1 | **Employee master lacks contact details.** No phone, email, address, emergency contact / next-of-kin. | hr |
| X25 | 🔴 P1 | **No salary disbursement details.** `Employee`/`EmploymentContract` carry no bank account / mobile-money / payment method, so payroll net pay has no payee target. | hr, cashbank |
| X26 | 🟡 P2 | **No position / job-grade master, no department hierarchy/manager.** `jobTitle` is free text on the employee; `Department` has no parent or head. | hr |
| X27 | 🟡 P2 | **No attendance / worked-days for proration.** `PayrollLine` has no `workedDays`/`absentDays`/LOP, so prorated pay & overtime quantities aren't captured (projects has timesheets, HR doesn't). | hr |
| X28 | 🟡 P2 | **String where enum belongs.** `EmployeeLoanInstallment.status`, `PayrollLineItem.itemKind`, `StatutoryRateSet.basis` are Strings (X2). |
| X29 | 🟢 P3 | **Effective-dated sets have no `effectiveTo`.** `PayeBandSet`/`StatutoryRateSet` are open-ended (superseded by next `effectiveFrom`). | hr |

---

# Module: HR

### Department `departments`
`code`, `name`, `active`.
| Priority | Missing attribute | Why it matters |
|----------|-------------------|----------------|
| 🟡 P2 | `parentDepartmentId`, `managerId`, `costCentreValueId`, `branchId` | Org hierarchy, head, GL-dimension link. |

### Employee `employees`
`employeeNumber`, `firstName`/`lastName`, `nationalId`, `tin`, `nssfNumber`, `heslbNumber`, `dateOfBirth`, `gender`, `hireDate`, `departmentId`, `jobTitle`, `status`, `userId`.
| Priority | Missing attribute | Why it matters |
|----------|-------------------|----------------|
| 🔴 P1 | contact: `phone`, `email`, `address`, emergency contact/next-of-kin | None present (X24). |
| 🔴 P1 | bank/payment: `bankName`, `bankAccountNo`, `paymentMethod`, mobile-money | Net pay has no disbursement target (X25). |
| 🟡 P2 | `terminationDate`/reason, `confirmationDate`/probation, `managerId`, `positionId`/`grade`, `maritalStatus`, `nationality` | Lifecycle, org, statutory. |
| 🟢 P3 | `middleName`, `photo`, dependents child table | Completeness/benefits. |

### EmploymentContract `employment_contracts`
Strong: `contractType`, `baseSalaryAmount`, `currency`, `payFrequency`, `startDate`/`endDate`, statutory flags (`payeResident`, `nssfMember`, `heslbBorrower`, `wcfCovered`, `sdlCounted`), `active`.
| Priority | Missing attribute | Why it matters |
|----------|-------------------|----------------|
| 🟡 P2 | `probationMonths`/`noticePeriodDays`, `workingHoursPerWeek`/`daysPerWeek`, `jobGrade`, signed-doc link | Notice/overtime calc; document trail. |

### EmployeeLoan `employee_loans`
`loanNumber`, `principalAmount`, `installmentAmount`, `outstandingAmount`, `glAccountId`, `status`, `startDate`, `currency`.
| Priority | Missing attribute | Why it matters |
|----------|-------------------|----------------|
| 🟡 P2 | `interestRate`/interest schedule, `numberOfInstallments`/`termMonths`, `loanType`, `approvedBy`, `disbursedAt`/method | Interest, term, approval, disbursement. |

### EmployeeLoanInstallment `employee_loan_installments`
`installmentNo`, `dueAmount`, `duePeriod`, `deductedInRunUid`, `status` (String).
| Priority | Missing attribute | Why it matters |
|----------|-------------------|----------------|
| 🟡 P2 | `status` as enum (X28), `deductedAmount` (partial), `dueDate`/`paidAt` | Partial deductions; scheduling. |

### EmployeeRecurringItem `employee_recurring_items`
`payComponentId`, `amountOrPercent`, `effectiveFrom`/`effectiveTo`.
| Priority | Missing attribute | Why it matters |
|----------|-------------------|----------------|
| 🟢 P3 | explicit `isPercent` flag, cap/`maxAmount`, `note` | Disambiguate amount vs percent; caps. |

### LeaveType `leave_types`
`code`, `name`, `paid`, `annualEntitlementDays`, `accrualMethod`, `active`.
| Priority | Missing attribute | Why it matters |
|----------|-------------------|----------------|
| 🟡 P2 | `carryForwardAllowed`/`maxCarryForwardDays`, `requiresApproval`, gender/eligibility, `maxConsecutiveDays` | Carry-forward, maternity/paternity rules. |

### LeaveBalance `leave_balances`
`asOfYear`, `entitledDays`, `takenDays`, `balanceDays`.
| Priority | Missing attribute | Why it matters |
|----------|-------------------|----------------|
| 🟡 P2 | `carriedForwardDays`, `accruedDays`, `pendingDays`, `adjustmentDays` | Accrual & pending visibility. |

### LeaveRequest `leave_requests`
`fromDate`/`toDate`, `days`, `status`, `decidedBy`/`decidedAt`, `reason`, `decisionNote`.
| Priority | Missing attribute | Why it matters |
|----------|-------------------|----------------|
| 🟡 P2 | half-day flags, `coveringEmployeeId`, attachment (sick note), `approvalRequestUid` | Half-days; cover; documentation; approvals link. |

---

# Module: Payroll

### PayComponent `pay_components`
`code`, `name`, `kind`, `basis`, `glAccountId`, `taxable`, `pensionable`, `active`.
| Priority | Missing attribute | Why it matters |
|----------|-------------------|----------------|
| 🟡 P2 | `wcfApplicable`/`sdlApplicable`, `displayOrder`, `formula`/computed, `proRatable` | Granular statutory inclusion; ordering; computed components. |

### PayeBand `paye_bands` (created-only, immutable)
`bandNo`, `lowerBound`, `marginalRate`, `cumulativeFixedTax`. Complete for purpose.

### PayeBandSet `paye_band_sets`
`effectiveFrom`, `taxFreeThreshold`, `description`.
| Priority | Missing attribute | Why it matters |
|----------|-------------------|----------------|
| 🟡 P2 | `effectiveTo` (X29), resident-vs-non-resident scope, `status` | Explicit validity; separate non-resident bands. |

### StatutoryRateSet `statutory_rate_sets`
`rateType`, `effectiveFrom`, `employeeRate`/`employerRate`, `basis` (String), `ceilingAmount`, `headcountThreshold`, `active`, `description`.
| Priority | Missing attribute | Why it matters |
|----------|-------------------|----------------|
| 🟡 P2 | `effectiveTo` (X29), `basis` as enum (X28), `floorAmount`/minimum | Validity; typing; floors. |

### PayrollRun `payroll_runs`
Excellent: `periodYear`/`periodMonth`, `payDate`, `status`, totals (gross/deduction/net/employerCost), full lifecycle (`calculated/approved/posted/paid/reversed` + actors), `glEntryUid`, `reversalOfRunUid`.
| Priority | Missing attribute | Why it matters |
|----------|-------------------|----------------|
| 🟡 P2 | `runType` (regular/bonus/off-cycle/final), disbursement/`paymentBatchUid`, `employeeCount`, explicit `periodStart/End` | Off-cycle runs; payment batch; non-monthly periods. |

### PayrollLine `payroll_lines`
Excellent per-employee breakdown: gross/taxable/net, `payeAmount`, `nssfEmployeeAmount`, `heslbAmount`, `voluntaryDeductionTotal`, `loanDeductionTotal`, employer `nssf/wcf/sdl`, `status`/`flagReason`, snapshots.
| Priority | Missing attribute | Why it matters |
|----------|-------------------|----------------|
| 🟡 P2 | `workedDays`/`absentDays`/LOP, `overtimeAmount`, `bankAccountNo` snapshot, `contractId` snapshot | Proration/overtime (X27); disbursement. |

### PayrollLineItem `payroll_line_items`
`payComponentId`, `itemKind` (String), `label`, `amount`, `glAccountId`.
| Priority | Missing attribute | Why it matters |
|----------|-------------------|----------------|
| 🟡 P2 | `itemKind` as enum (X28), `quantity`/`rate`, `taxable`/`pensionable` snapshot | qty×rate items (overtime hrs); tax treatment trail. |

### Payslip `payslips`
Has YTD: `ytdGross`/`ytdPaye`/`ytdNssfEmployee`/`ytdNet`.
| Priority | Missing attribute | Why it matters |
|----------|-------------------|----------------|
| 🟢 P3 | more YTD (heslb, employer cost), generated-PDF `documentUid`, `deliveredAt`/`emailedAt` | Statement completeness & delivery. |

### PayrollStatutorySnapshot `payroll_statutory_snapshots`
Snapshots applied band/rate-set uids per line. Complete for purpose (great for audit).

---

# Module: Fixed Assets

> One of the more complete modules: category-level GL mapping, versioned depreciation schedule, depreciation runs with line detail, disposals with gain/loss, and IFRS-style revaluation (reserve balance on the asset). Main gaps are **asset-register control** (custodian, physical ID) and a couple of **GL mappings** (disposal/reval accounts).

### AssetCategory `asset_categories`
`code`, `name`, `defaultMethod`, `defaultLifePeriods`, `defaultReducingRate`, GL mapping (`assetAccountId`, `accumDepAccountId`, `depExpenseAccountId`), `status`.
| Priority | Missing attribute | Why it matters |
|----------|-------------------|----------------|
| 🟡 P2 | `disposalGainAccountId`/`disposalLossAccountId`/`revalReserveAccountId` | Disposal & revaluation postings need accounts (likely via `GlConfig` — verify). |
| 🟢 P3 | `parentCategoryId` | Category hierarchy. |

### FixedAsset `fixed_assets`
Rich: `assetNumber`, `categoryId`, `name`, `status`, `acquisitionCost`, `salvageValue`, `depreciationMethod`/`lifePeriods`/`reducingRate`, `acquisitionDate`/`depreciationStartDate`, `carryingCost`, `accumulatedDepreciation`, `revaluationReserveBalance`, `supplierId`/`sourceBillUid`, `location`, `costCentreId`, `assetTag`, `capitalisedGlEntryUid`, `disposedAt`. (NBV computed, not stored.)
| Priority | Missing attribute | Why it matters |
|----------|-------------------|----------------|
| 🟡 P2 | `custodianId`/responsible employee | Asset-register accountability — who holds it. |
| 🟡 P2 | physical ID: `serialNumber`, `model`, `manufacturer`, `barcode` | Identification beyond `assetTag`. |
| 🟡 P2 | `location`/`costCentreId` as FK (location master / `dimension_values`) | Currently free String / un-FK'd scalar (consistency). |
| 🟡 P2 | `warrantyExpiry`, `insuredValue`/policy ref, `parentAssetId` (components) | Maintenance, insurance, componentisation. |

### DepreciationScheduleLine `depreciation_schedule_lines`
Versioned plan: `periodSeq`, `scheduleVersion`, `periodDate`, `plannedCharge`, `accumulatedAfter`, `nbvAfter`, `posted`, `depreciationRunId`. Complete for purpose.

### DepreciationRun `depreciation_runs`
`runNumber`, `fiscalPeriodId`, `postingDate`, `status`, `totalChargeAmount`, `assetCount`, `glEntryUid`, `currency`, `executedAt`. (Javadoc `uq_depreciation_run_company_period` — verify-migration.)
| Priority | Missing attribute | Why it matters |
|----------|-------------------|----------------|
| 🟢 P3 | `executedBy`, reversal (`reversedAt`/`reversalOfRunUid`) | Actor; depreciation reversal. |

### DepreciationRunLine `depreciation_run_lines`
`fixedAssetId`, `scheduleLineId`, `chargeAmount`, `accumDepAfter`, `nbvAfter`. Complete for purpose.

### AssetDisposal `asset_disposals`
`disposalType`, `disposalDate`, `fiscalPeriodId`, `proceedsAmount`, `nbvAtDisposal`, `gainLossAmount` (signed), `glEntryUid`, `currency`, `reason`. (Javadoc `uq_asset_disposal_asset` — verify-migration.)
| Priority | Missing attribute | Why it matters |
|----------|-------------------|----------------|
| 🟡 P2 | `buyerName`/`buyerId`, proceeds receipt/`arInvoiceUid` link, `approvedBy` | Who bought it; was cash/credit received; approval. |

### AssetRevaluation `asset_revaluations`
`revaluationDate`, `fiscalPeriodId`, `direction`, `deltaAmount`, `carryingBefore`/`carryingAfter`, `glEntryUid`, `currency`, `reason`. (IFRS revaluation.)
| Priority | Missing attribute | Why it matters |
|----------|-------------------|----------------|
| 🟡 P2 | `valuerName`/`valuationRef`, reserve-vs-P&L split, `approvedBy` | IFRS treats up/down asymmetrically; valuation provenance. |

> **Note:** No **AssetTransfer** entity (moving an asset between branches/custodians) — minor gap if asset relocation is needed.

---

# Module: CRM

> Solid lead→opportunity→conversion pipeline with stages, probability and full lifecycle stamps. Main gaps: **activities only attach to Lead/Opportunity** (not general parties) and some enrichment fields.

### PipelineStage `pipeline_stages`
`name`, `displayOrder`, `defaultProbability`, `active`, `status`.
| Priority | Missing attribute | Why it matters |
|----------|-------------------|----------------|
| 🟢 P3 | `isWonStage`/`isLostStage`/`stageType` | Identify closed-won/lost stages for funnel reporting. |

### Lead `leads`
`leadNumber`, `leadStatus`, `leadSource`, `displayName`, `companyName`, `contactPerson`, `phone`, `email`, `ownerUserId`, `customerId`/`customerUid`, `disqualifyReason`, lifecycle stamps.
| Priority | Missing attribute | Why it matters |
|----------|-------------------|----------------|
| 🟡 P2 | `estimatedValue`, `rating`/score, `nextFollowUpDate`, `industry`/`region` | Lead qualification & prioritisation. |

### Opportunity `opportunities`
Rich: `opportunityStatus`, `title`, `customerId`, `agentId`, `ownerUserId`, `sourceLead*`, `pipelineStageId`, `winProbability`, `estimatedValueAmount`, `currency`, `expectedCloseDate`, won/lost stamps + `lossReason`, converted-doc link.
| Priority | Missing attribute | Why it matters |
|----------|-------------------|----------------|
| 🟡 P2 | `weightedValue` (est×prob), `nextStep`/`nextActionDate`, `stageChangedAt`, `competitorId`, `campaignId` | Forecasting, ageing, marketing attribution. |
| 🟢 P3 | `lossReason` as enum | Typing (X2). |

### OpportunityLine `opportunity_lines`
`productId`(+snapshot), `estimatedQty`, `estimatedUnitPriceAmount`, line discounts, `currency`. Complete for purpose.

### Activity `activities`
`activityType`, polymorphic parent (`leadId` XOR `opportunityId`), `subject`, `body`, `occurredAt`, `dueDate`, `assigneeUserId`, `done`/`doneAt`, `status`. (`chk_activity_parent` — verify-migration.)
| Priority | Missing attribute | Why it matters |
|----------|-------------------|----------------|
| 🟡 P2 | generic parent (`entityType`/`entityId`) to also log against customers/suppliers | Activities can't attach to a party directly. |
| 🟡 P2 | `outcome`/result, `reminderAt`, `durationMinutes`, participants | Activity completeness. |

---

# Module: Projects

> Has a real timesheet entity. Main gaps: **planned-only (no actuals / %-complete / scheduling) on tasks**, and **no billing-type / revenue-recognition** model.

### Project `projects`
`projectNumber`, `name`, `customerId` (null=internal), `managerUserId`, `projectStatus`, `plannedStartDate`/`plannedEndDate`, `budgetAmount`, `currency`, lifecycle stamps.
| Priority | Missing attribute | Why it matters |
|----------|-------------------|----------------|
| 🟡 P2 | `billingType` (fixed/T&M/milestone), `contractValue`/revenue-recognised, `actualStart/End`, `percentComplete`/`costToDate` | Project billing & tracking model is undefined. |
| 🟢 P3 | `parentProjectId`, cost-vs-revenue budget split | Sub-projects; richer budgeting. |

### ProjectTask `project_tasks`
`taskCode`, `name`, `parentId` (reserved), `plannedHours`, `billable`, `status`.
| Priority | Missing attribute | Why it matters |
|----------|-------------------|----------------|
| 🟡 P2 | `plannedStart/EndDate`, `actualHours`, `assigneeUserId`, `percentComplete`, `dependsOnTaskId`, `milestone` | No scheduling/Gantt/dependencies; planned hours only. |

### ProjectTimesheet `project_timesheets`
`projectId`/`projectTaskId`, `userId`, `workDate`, `hours`, `billable`, `plannedRateAmount`, `notes`, `status`.
| Priority | Missing attribute | Why it matters |
|----------|-------------------|----------------|
| 🟡 P2 | `approvalStatus`/`approvedBy`, `billed`/`invoiceUid`, `costRateAmount` (vs billing rate), `overtimeHours` | Timesheets usually need approval before billing; cost vs revenue. |

---

# Module: Manufacturing

> Strong WIP costing on the work order (material/labour/overhead applied, computed unit cost, variance). Main gaps: **no work-centre/routing master**, **operations track cost but not time**, and **components don't carry batch/serial** (same traceability gap as X19).

### WorkOrder `work_orders`
Rich: `woNumber`, `finishedProductId`(+snapshot), `bomId`/`bomUid`, `plannedQty`/`goodQty`/`scrapQty`, `status`, WIP totals (`wipDebitTotal`/`wipCreditTotal`/`labourAppliedTotal`/`overheadAppliedTotal`), `computedUnitCost`, `varianceAmount`, `incompleteCost`, `costCentreValueId`, `plannedDate`, lifecycle stamps.
| Priority | Missing attribute | Why it matters |
|----------|-------------------|----------------|
| 🟡 P2 | make-to-order `salesOrderUid`, finished-goods `targetLocationId`, `scheduledStart/End`/`dueDate`, `priority` | Demand link, FG destination, scheduling. |
| 🟡 P2 | variance split (material/labour/overhead) | Only a single `varianceAmount`. |

### WorkOrderComponent `work_order_components`
`componentProductId`(+snapshot), `plannedQty`, `issuedQty`, `issuedValue`, `unitCostAtIssue`, `costSkipped`, `status`.
| Priority | Missing attribute | Why it matters |
|----------|-------------------|----------------|
| 🟡 P2 | `batchId`/`serialNo` issued, `returnedQty`/`scrapQty`, `unitId` | Lot traceability (X19); returns; UoM. |

### WorkOrderOperation `work_order_operations`
`seqNo`, `description`, `workCentre` (String), `labourAmount`, `overheadAmount`, `applied`.
| Priority | Missing attribute | Why it matters |
|----------|-------------------|----------------|
| 🟡 P2 | `workCentreId` (FK to a work-centre master — none exists), time tracking (`plannedHours`/`actualHours`/`setup`/`run`), `operatorId`, `startedAt`/`completedAt`, `status` | No resource/capacity/scheduling; operations are cost-only. |

---

## Cross-cutting findings — Platform / remaining modules

| # | Priority | Finding | Affected |
|---|----------|---------|----------|
| X30 | 🟡 P2 | **Core IAM masters lack audit columns.** `Organisation`, `Company`, `Branch`, `AppUser`, `Role` have **no** `createdAt/createdBy/updatedAt/updatedBy` — unlike every other module. For security-sensitive entities, who-created-when matters (audit-log module may cover changes, but the entities don't carry it). | iam |
| X31 | 🟡 P2 | **No MFA / password-expiry on AppUser.** No `mfaEnabled`/secret, `mustChangePassword`, `passwordExpiresAt`, `lastLoginIp`. | iam |
| X32 | 🟡 P2 | **Approvals are role-only.** Steps reference an `approverRoleCode` only — no specific-user approver, quorum/multiple approvers, SLA/escalation, or delegation. Policy conditions are amount-band only. | approvals |
| X33 | 🟡 P2 | **RouteCustomer has no visit sequence.** A route is an unordered set of customers — no stop order/frequency for delivery-route planning. | routes |
| X34 | 🟡 P2 | **GeneratedDocument has no storage/blob ref.** `contentHash`/`byteSize` but no `storageRef`/file URL — unclear where the PDF bytes live (regenerated vs stored). | documents |
| X35 | ✅ note | **`Company.baseCurrency` is the functional-currency anchor** (default `TZS`). Resolves the FX "isBaseCurrency" question (X-FX) — `Currency` correctly omits it. | fx, iam |

---

# Module: Budgeting

### Budget `budgets`
`budgetNumber`, `name`, `fiscalYearId`, `costCentreValueId` (null=company-wide), `notes`.
| Priority | Missing attribute | Why it matters |
|----------|-------------------|----------------|
| 🟡 P2 | generic `dimensionValueId` (not just cost-centre), `budgetType` (operating/capital/cash), `branchId` | Budget by department/project, not only cost centre. |

### BudgetVersion `budget_versions`
Excellent: `versionNo`, `status`, `label`, `seededFromVersionId`, full submit/approve/reject/supersede lifecycle. Complete for purpose.

### BudgetLine `budget_lines` (created-only)
`accountId`, `fiscalPeriodId`, `amount`, `currency`, `lineMemo`.
| Priority | Missing attribute | Why it matters |
|----------|-------------------|----------------|
| 🟡 P2 | `dimensionValueId`, `quantity` (qty budgets) | Dimensioned budgeting; non-monetary budgets. |

---

# Module: Costing (Dimensions)

> Clean, generic analytical-dimension framework (slots + hierarchical values). Used by GL, stock, budgeting.

### Dimension `dimensions`
`slot`, `code`, `name`, `builtIn`, `mandatory`, `status`. Solid.
| Priority | Missing attribute | Why it matters |
|----------|-------------------|----------------|
| 🟢 P3 | `requireOnAccountTypes`, `description` | Conditional mandatory rules. |

### DimensionValue `dimension_values`
`dimensionId`, `code`, `name`, `parentId` (hierarchy), `active`, `status`. Solid.
| Priority | Missing attribute | Why it matters |
|----------|-------------------|----------------|
| 🟢 P3 | `effectiveFrom/To`, `managerId` (owner) | Time-boxed values; cost-centre ownership. |

---

# Module: Approvals

> Threshold-based policy → request → steps → decisions, with auto-approve and snapshotted steps. Main gap: **role-only, single-approver, no SLA/escalation/delegation** (X32).

### ApprovalPolicy `approval_policies`
`documentType`, `name`, `branchScope`/`branchId`, `minAmount`/`maxAmount`, `currency`, `active`, `status`.
| Priority | Missing attribute | Why it matters |
|----------|-------------------|----------------|
| 🟡 P2 | `effectiveFrom/To`, conditions beyond amount (supplier/category), `priority` | Richer routing; overlapping-policy precedence. |

### ApprovalPolicyStep `approval_policy_steps` (created-only)
`sequence`, `approverRoleCode` (String).
| Priority | Missing attribute | Why it matters |
|----------|-------------------|----------------|
| 🟡 P2 | specific `approverUserId`, quorum/`minApprovers`, `slaHours`/escalation, `allowSelfApproval` | Role-only today (X32). |

### ApprovalRequest `approval_requests`
`documentType`/`documentUid` (opaque), `amount`, `status`, `autoApproved`, `sourcePolicy*`, submit/resolve stamps.
| Priority | Missing attribute | Why it matters |
|----------|-------------------|----------------|
| 🟡 P2 | `currentStepSequence`, `dueDate`/SLA, `escalatedAt` | Pending-step visibility & SLA. |

### ApprovalRequestStep `approval_request_steps` (created-only)
Snapshotted `sequence`/`approverRoleCode`, `status`, `resolvedBy/At`, `resolvingDecisionId`.
| Priority | Missing attribute | Why it matters |
|----------|-------------------|----------------|
| 🟡 P2 | `assignedUserId`, `dueAt`, `delegatedTo` | Assignment, SLA, delegation. |

### ApprovalDecision `approval_decisions` (append-only)
`action`, `decidedBy`, `decidedAt`, `comment`. Complete for purpose.

---

# Module: Documents

### DocumentTemplate `document_templates`
`documentType`, `rendererKey`, `brandingId`, `title`, `status`, status-change audit.
| Priority | Missing attribute | Why it matters |
|----------|-------------------|----------------|
| 🟡 P2 | `templateBody`/content, `locale`/language, `version`, `isDefault` per type | Templates appear code-driven (renderer key) — not user-editable; no multi-language. |

### DocumentBranding `document_branding`
Rich: legal/tax IDs, full address, contacts, `logoRef`, `footerTerms`, `bankDetails`.
| Priority | Missing attribute | Why it matters |
|----------|-------------------|----------------|
| 🟡 P2 | `branchId` (per-branch branding), `signatureRef`, `isDefault` | Branch-specific letterheads. |

### GeneratedDocument `generated_documents`
`documentNumber`, `documentType`, `sourceType`/`sourceUid`, `sourceParams` (jsonb), `brandingId`, `contentHash`, `byteSize`, `mimeType`, `generatedBy/At`.
| Priority | Missing attribute | Why it matters |
|----------|-------------------|----------------|
| 🟡 P2 | `storageRef`/file URL, `version`/`supersededBy`, `emailedAt`/delivered-to | Where the bytes live (X34); reissue & delivery. |

---

# Module: Notifications

> Complete in-app notification engine (types with templates, per-user delivery tracking, preferences, scan markers). Main drift: **channel/severity stored as Strings** (X2).

### NotificationType `notification_types`
Rich: `typeKey`, templates (`title`/`body`/`linkRoute`), `audiencePermission`, `branchScoped`, `defaultChannels`, `severity`, `companyEnabled`, `status`.
| Priority | Missing attribute | Why it matters |
|----------|-------------------|----------------|
| 🟡 P2 | `defaultChannels`/`severity` as enum, throttle/dedup window config | Typing; rate-limit config. |

### Notification `notifications` (created-only, immutable)
`recipientUserId`, `channel`, `severity`, `title`/`body`, source refs, `triggerKey`, `isRead`/`readAt`.
| Priority | Missing attribute | Why it matters |
|----------|-------------------|----------------|
| 🟢 P3 | `channel`/`severity` as enum, `dismissedAt`/`archivedAt`, `expiresAt` | Typing; lifecycle beyond read. |

### NotificationDelivery `notification_deliveries`
`channel`, `outcome`, `suppressionReason`, `attemptNo`, `error`, `triggerKey`, `attemptedAt`/`completedAt`.
| Priority | Missing attribute | Why it matters |
|----------|-------------------|----------------|
| 🟢 P3 | `providerMessageId`, `nextRetryAt` | External-provider correlation; retry scheduling. |

### NotificationPreference `notification_preferences`
`userId`, `typeKey`, `muted`, `channelsEnabled`.
| Priority | Missing attribute | Why it matters |
|----------|-------------------|----------------|
| 🟢 P3 | `quietHoursStart/End`, `digestFrequency` | Do-not-disturb; digests. |

### NotificationScanMarker `notification_scan_markers`
`typeKey`, `conditionKey`, `lastNotifiedAt`, `armed`. Internal scan state — complete.

---

# Module: Routes

### Route `routes`
`code`, `name`, `locationIdentifier`, `status`.
| Priority | Missing attribute | Why it matters |
|----------|-------------------|----------------|
| 🟡 P2 | `visitDays`/schedule, `description` | Route scheduling. |

### RouteBranch / RouteAgent / RouteCustomer `route_*`
Junctions: `route` `@ManyToOne` + scalar fk + `assignedAt/By`; `RouteAgent.isPrimary`.
| Priority | Missing attribute | Why it matters |
|----------|-------------------|----------------|
| 🟡 P2 | `RouteCustomer.visitSequence`/`visitFrequency` | Routes are unordered sets — no stop order (X33). |
| 🟢 P3 | junction `active`/`unassignedAt` | Deactivate without delete. |

---

# Module: IAM

> Tenancy (Organisation→Company→Branch) + users/roles/permissions + scoped grants + refresh-token rotation. **`Company.baseCurrency`** is the system's functional-currency anchor. Main gaps: **no audit columns on core masters** (X30) and **no MFA/password-expiry** (X31).

### Organisation `organisations`
`name`, `legalName`, `defaultTimeZone`, `status`.
| Priority | Missing attribute | Why it matters |
|----------|-------------------|----------------|
| 🟡 P2 | audit columns (X30), contact/address, subscription/plan | Tenant admin & audit. |

### Company `companies`
`organisation`, `code`, `name`, `legalName`, `taxId`, `timeZone`, **`baseCurrency`** (TZS), `status`.
| Priority | Missing attribute | Why it matters |
|----------|-------------------|----------------|
| 🟡 P2 | audit columns (X30), address/contact, `vrn`, `logoRef`, `fiscalYearStartMonth` | Statutory identity, audit, doc defaults. |

### Branch `branches`
`company`, `code`, `name`, `timeZone`, `isDefault`, `status`.
| Priority | Missing attribute | Why it matters |
|----------|-------------------|----------------|
| 🟡 P2 | audit columns (X30), address/contact, `managerId`, `branchType` | Branch ops & audit. |

### AppUser `app_users`
`username`, `passwordHash`, `displayName`, `email`, `phone`, `root`, `failedLoginCount`, `lockedUntil`, `lastLoginAt`, `passwordChangedAt`, `status`.
| Priority | Missing attribute | Why it matters |
|----------|-------------------|----------------|
| 🟡 P2 | MFA (`mfaEnabled`/secret), `mustChangePassword`, `passwordExpiresAt`, `lastLoginIp`, `employeeId`, audit columns | Security hardening (X31); HR linkage. |

### Role `roles`
`code`, `name`, `description`, `system`, `status`, `permissions` (`@ManyToMany` via `role_permission`).
| Priority | Missing attribute | Why it matters |
|----------|-------------------|----------------|
| 🟢 P3 | audit columns (X30), `roleScope` | Audit; scope typing. |

### Permission `permissions` (not a UidEntity)
`id`, `code`, `module`, `description`. Static catalog — complete.

### UserRole `user_role`
`userId`, `role`, `companyId`, `branchId` (null=all), `grantedAt/By`, `revokedAt`. Scoped, revocable grant — solid.
| Priority | Missing attribute | Why it matters |
|----------|-------------------|----------------|
| 🟢 P3 | `expiresAt` (time-limited grants) | Temporary access. |

### UserBranch `user_branch`
`userId`, `branch`, `isDefault`, `assignedAt/By`.
| Priority | Missing attribute | Why it matters |
|----------|-------------------|----------------|
| 🟢 P3 | `revokedAt`/active | Deactivate without delete. |

### RefreshToken `refresh_tokens` (not a UidEntity)
`userId`, `tokenHash`, `issuedAt`/`expiresAt`/`rotatedAt`/`revokedAt`, `replacedById`, `clientCompanyId`/`clientBranchId`. Good rotation chain.
| Priority | Missing attribute | Why it matters |
|----------|-------------------|----------------|
| 🟡 P2 | `deviceInfo`/`userAgent`/`ipAddress`, `lastUsedAt` | Session/device management & security. |

---

# Module: Platform / Infrastructure

> Not domain entities but real persisted tables (plus one embeddable value type). Deliberately minimal "plumbing" — gaps here are mostly nice-to-haves. The **system-wide `AuditLog`** complements the per-entity audit columns and is why IAM masters can arguably skip their own (X30) — though entity-level created/updated is still recommended.

### AuditLog `audit_logs` (append-only, not a UidEntity)
`actorUserId`, `action` (e.g. `USER.CREATE`), `targetType`, `targetId`/`targetUid`, `companyId`/`branchId`, `detail` (jsonb before/after), `at`, `ip`.
| Priority | Missing attribute | Why it matters |
|----------|-------------------|----------------|
| 🟢 P3 | `userAgent`/session id, `requestId`/correlation id | Richer forensic context (ip already captured). |

### DomainEvent `domain_events` (transactional outbox, own `@Version`)
`uid`, `eventType` (e.g. `SALE.FINALISED`), `aggregateType`/`aggregateId`/`aggregateUid`, `companyId`/`branchId`, `payload` (jsonb), `status`, `occurredAt`, `dispatchedAt`, `attemptCount`, `lastError`. Solid SKIP-LOCKED-ready outbox.
| Priority | Missing attribute | Why it matters |
|----------|-------------------|----------------|
| 🟢 P3 | `nextRetryAt`/backoff, `traceId` | Explicit retry scheduling & cross-service tracing (retries are next-poll today). |

### ProcessedEvent `processed_events` (consumer idempotency marker)
`consumer`, `eventUid`, `processedAt`. Dedup key `uq_processed_event(consumer, event_uid)`. Complete for purpose.

### Money `@Embeddable` (value type)
`amount` `(19,4)` + `currency` `(3)` — inseparable pair (both-null or both-set).
| Priority | Missing attribute | Why it matters |
|----------|-------------------|----------------|
| 🟡 P2 | **inconsistent usage** — only a few fields embed `Money` (`Customer.creditLimit`, `Product.cost`, `ProductPrice.price`); most monetary fields use bare `BigDecimal amount` + `String currency` pairs | Pick one convention; bare pairs miss the both-null/both-set invariant `Money` enforces. **→ Decided: [ADR-0039](../decisions/0039-currency-code-value-type.md)** — currency becomes a `CurrencyCode` value type, `Money.currency: CurrencyCode`; `Money` for standalone values, one row-level `CurrencyCode` for multi-amount documents. |
| 🟢 P3 | arithmetic (plus/minus) deliberately deferred to the FX engine (ADR-0005 D-8) | Known, intentional. |

---

# P1 rollup (the highest-value fixes)

A single list of the 🔴 P1 items across all modules, grouped by theme:

### Accounting integrity
- **Multi-currency base amounts** missing on `JournalLine`, `ArReceipt`, `ApPayment`, `CashTransaction` (X1).
- **ChartOfAccount**: `allowManualPosting`/leaf-only + `isControlAccount` controls.
- **GlConfig**: per-`branchId` account overrides.
- **CashTransfer**: cross-currency support; **BankReconciliation**: opening balance + reconciling difference.

### Tax / compliance
- **WHT wiring**: `SupplierBill.whtTypeId/whtAmount`; `WhtTransaction.tin` + remittance tracking (X5).
- **VAT bad-debt relief** on `ArWriteOff`; input side on `VatReturnBand` (X6).
- **WhtType.glAccountId**.

### Documents accuracy (sales/purchases)
- **Line-level VAT + `glAccountId`** on `SupplierBillLine`; **PO/PO-line tax breakdown** (X18).
- **Credit/debit notes**: applied/outstanding tracking (`ArCreditNote`, `ApDebitNote`).
- **ApPayment.unallocatedAmount** (on-account symmetry, X8).

### Masters completeness
- **Parties**: contacts + bill-to/ship-to addresses (X9); **Supplier** payment terms + bank account (X10).
- **Product**: inventory planning (reorder/min/max/lead-time) + tracking flags (batch/serial/expiry) + `purchasable`/preferred supplier (X11).
- **Employee**: contact details (X24) + salary bank/payment details (X25).

### Traceability
- **StockMovement / WorkOrderComponent**: carry `batchId`/`serialId` (X19).

---

## Appendix — coverage (verified complete)

Verified against the source: **173 `@Entity` classes** + **1 `@MappedSuperclass`** (`PartyBase`, shared by the 4 party masters) + **1 `@Embeddable`** (`Money`) — **all reviewed**.

- 23 domain modules: GL, AR, AP, cashbank, tax, fx, sales, parties, products, purchases, stock, hr (incl. payroll), fixedassets, crm, projects, manufacturing, budgeting, costing, approvals, documents, notifications, routes, iam.
- Platform/infrastructure: `AuditLog`, `DomainEvent` (outbox), `ProcessedEvent`, `Money`.

Method: `grep @Entity` across `backend/src/main/java` (excluding worktrees) returned 173; the only non-`domain/entity` hits were 3 platform entities (covered above) and 2 false positives (`@EntityGraph` in `BranchRepository`/`RoleRepository`). The single non-`@Entity` file under `domain/entity` is `PartyBase` (mapped superclass, covered).

Not in scope (by design — not persisted attribute-bearing entities): enums, DTOs, repositories, services, config/properties classes.

*End of review — system coverage complete.*
