# 0041 — P2 design decisions (D1–D7)

- **Status:** Accepted
- **Date:** 2026-06-18
- **Deciders:** solutions-architect under the owner's decide-and-proceed mandate ("fix all gaps, P1→P2→P3"). Synthesised from a 7-theme parallel design survey grounded in the shipped P1/P2-mechanical entities. Standard-aligned defaults chosen; consequential forks recorded with their ruling; heavy/conflicting items explicitly deferred.
- **Context:** the P2 *design* items of [docs/data-model/GAP-FIX-WORKLIST.md](../data-model/GAP-FIX-WORKLIST.md) (waves D1–D7 in [docs/data-model/P2-WAVE-PLAN.md](../data-model/P2-WAVE-PLAN.md)). P2 *mechanical* (M1–M6, ~148 cols/enums) already shipped. All changes respect: soft-FK cross-module convention, base-only GL ledger + sacred Σ-balance gate, CurrencyCode/Money, [[db-naming-convention]], dev-phase edit-owning-migration-in-place ([[dev-phase-migrations-editable]]).

---

## D1 — Document payment-terms + settlement discount (risk: MEDIUM) — DO-NOW
Wire the shipped `PaymentTerms` master (P1 D-2) to documents. Add scalar `payment_terms_id` soft-FK to `quotations`, `sales_orders`, `sales_invoices`, `purchase_orders`, `supplier_bills` (ar_invoices inherits from the source SI). Add `settlement_discount_due_date` + `settlement_discount_amount` to `ar_invoices` and `supplier_bills` (computed at raise from the term's basis/discount_days/discount_percent). Add `discount_amount` + `write_off_amount` to `ar_receipt_allocations` and `ap_payment_allocations` (immutable at persist). New reusable `PaymentTermsDueDateCalculator` (basis DAYS_AFTER_INVOICE|DAYS_AFTER_MONTH_END|DUE_ON_RECEIPT → date) called from SI.finalise / bill.post / SO.confirm / PO.confirm; falls back to the existing integer `payment_terms_days`.
- **FORK (ruled): settlement-discount GL leg → data-only v1.** Taking a discount records `discount_amount` on the allocation (sub-ledger), posts NO GL discount leg (preserves the allocation's ledger-neutral boundary + the Σ-gate; matches mainstream "capture for reporting" v1). A `DR SETTLEMENT_DISCOUNT_EXPENSE / CR AR` leg is a **Phase-2** change (new GlConfig key + seeding) — deferred.

## D2 — Sales ship-to/bill-to snapshot population (risk: LOW) — DO-NOW
Columns already exist (P1 D-3 / V67). Wire only: create/confirm requests accept `shipToAddressUid`/`billToAddressUid`; service resolves uid→id, validates the address belongs to the document's customer, and snapshots the formatted address text (immutable) onto SO/SI/Delivery. SI with origin=SALES_ORDER **inherits** the SO's addresses+snapshots automatically.
- **FORKS (ruled):** allow ship/bill override on the confirm request (DO-NOW); **no auto-fill** of the customer's default SHIP_TO/BILL_TO when the uid is omitted (DEFER — v1 requires an explicit uid or manual text; auto-default is a later convenience).

## D3 — Instrument links + receipt/payment lifecycle + cheque-bounce reversal (risk: MEDIUM) — DO-NOW
Builds on P1 D-9 (Cheque already bidirectional with direction/bounce fields). 
- `ar_receipts`: `tender_type` enum (+ `CHEQUE`; CASH|CHEQUE|MOBILE_MONEY|CARD) + instrument link `cheque_uid` + bounce/reversal (`reversed_at`, `reversal_of_receipt_uid`).
- `ap_payments`: `status` (already has UNALLOCATED/...; add void/cleared lifecycle as needed), `payment_run_id`, `tender_type` enum.
- `sales_invoice_payments`: `cash_bank_account_id`, `cheque_id` + structured mobile-money/card ref.
- `ap_debit_notes`: outstanding/applied tracking + `fx_rate`/base + `status` + a new `ap_debit_note_allocations` junction (parity with AR credit-note D-6; raise posts full contra, apply = sub-ledger move + realized-FX plug).
- New lightweight **`payment_runs`** master (uid, status DRAFT|POSTED, company/branch) — grouping for bulk AP payment runs.
- **FORK (ruled): cheque-bounce GL reversal → post the reversing JournalEntry immediately on the BOUNCED transition** (via the cashbank→AR/AP outbox), reversing the original receipt/payment cash leg (append-only, a reversing entry — never mutate). This **closes the deferred D-9 follow-up.** (Not: bounce-as-data-only.)

## D4 — Dimension requirement flags + document-line dimensions (risk: MEDIUM) — DO-NOW (scoped)
Extends the existing dimensions framework (`dimension_values`, `journal_lines` cost_centre/department cols, GLPosting Step-2 validity + Step-3 mandatory-slot already MANUAL-only). 
- `chart_of_accounts`: `require_cost_centre`, `require_department`, `require_project` BOOLEAN flags (default false). 
- Enforcement: at posting, a line to an account with a requirement flag must carry that dimension — **enforced for user-entered MANUAL journals only** (mirrors the D-1 control-account gate + the existing Step-3 rule; event-driven/system posters are exempt to avoid poisoning automated postings, per ADR-0025).
- Wire the already-added `supplier_bill_lines` dimension cols (+ new `purchase_order_lines` cost_centre/department cols) through to the journal lines those documents generate.
- **FORK (ruled):** requiredness applies to MANUAL only in v1 (not all posters). A per-document-type enforcement matrix is a later refinement.

## D5 — Master-data defaults: Tier-1 DO-NOW / Tier-2 DEFER (risk: MEDIUM)
- **Tier 1 (DO-NOW, mechanical soft-FK/enum):** Customer `default_price_list_id`/`default_agent_id`; Supplier `default_currency`/`lead_time_days`/`min_order_value`/`default_wht_type_id`; Agent `sales_target`/`quota`; UnitOfMeasure `dimension_type`/`decimal_places`/`is_fractional`; PriceList `currency`/`effective_from`/`effective_to`/`price_includes_vat`/`is_default`/`scope` (enum GLOBAL|CUSTOMER|BRANCH|SEGMENT); Promotion targeting (`customer/branch scope`, `min_threshold`, `usage_limit`, `coupon`, `combinable`) + new `promotion_usages` child.
- **Tier 2 (DEFER):** **Territory** and **CustomerSegment/Group** as rich masters are HEAVY (assignment logic, geo-reporting, per-segment pricing). v1: `customer.segment` as a simple enum (RETAIL|WHOLESALE|GOVERNMENT|OTHER); `territory_id`/`route_id` columns deferred to a later master-data ADR.
- **FORK (ruled):** Promotion combinability = **warn-only** at SO-confirm in v1 (block-rules later); PriceList scope = enum on the row (not a configurable GlConfig tiebreak).

## D6 — HR org + payroll policy (risk: MEDIUM) — DO-NOW + minimal Position master
- **Mechanical cols (DO-NOW):** Employee `termination_date`/`termination_reason`, `confirmation_date`/probation, `manager_id`, `marital_status`, `nationality`; Department `parent_department_id` (self-FK), `manager_id`, `cost_centre_value_id`, `branch_id`; EmploymentContract probation/notice/working-hours-days/job_grade/signed-doc-ref; EmployeeLoan `interest_rate` (single)/`loan_type`/`approved_by`/`installments`/`term`; LeaveType `carry_forward`/`requires_approval`/`gender_eligibility`/`max_consecutive`; LeaveBalance `carried_forward_days`/`accrued_days`/`pending_days`/`adjustment_days`; PayComponent `wcf_applicable`/`sdl_applicable`/`display_order`/`pro_ratable`; PayeBandSet resident-vs-non-resident scope.
- **New minimal master (DO-NOW):** `positions` (company-scoped, flat: code/name/description/job_grade/status) + `employees.position_id` soft-FK.
- **DEFER:** monthly leave-accrual posting logic (columns only now), loan dynamic rate-change schedule (single rate v1; new loan if it changes), position hierarchy (flat v1), attendance-dependent contract fields.

## D7 — Remaining design grab-bag: v1 column-adds DO-NOW / heavy items DEFER (risk: MEDIUM)
- **DO-NOW (additive cols / light enums):** ArWriteOff/AssetDisposal/AssetRevaluation approval-linkage + provenance (`approved_by`, valuer/buyer fields); CurrencyRate `effective_to` (NULL = still active); BillMatch `match_type`(2/3-way)/`variance_reason`(varchar v1); new `purchase_settings` (1 row/company policy defaults); Rfq `award_reason`/requested terms; SupplierQuote terms/incoterms/score/rank/warranty; FixedAsset `parent_asset_id`(componentisation)/warranty/insurance; VatReturn `amended_return_id`/`is_amendment`/penalty/interest; budgeting `budget_type`/`branch_id`/BudgetLine `quantity`; StockLocation `parent_location_id`/`allow_negative`/`pickable`/`sellable`/per-loc `gl_account_id`; StockTransferLine `qty_dispatched`/`qty_received` (partial); StockCount `counted_by`/`approved_by`/scope/`recount_required` + StockCountLine `recounted_qty`; IAM Org/Company/Branch contact+address (child or block), Org `subscription`/plan, AppUser MFA (`mfa_enabled`/`mfa_secret` + recovery — possibly child); FxRevaluationRunLine drill-down detail.
- **DEFER (design/P3):** JournalEntry DRAFT status lifecycle (conflicts append-only BR-GL-02 — needs an approval-batch-posting design); manufacturing BOM `routing_id`/work-centre + production lot/serial (X16/X19 — needs a routing/work-centre master); GL financial-statement-mapping master (reporting taxonomy); on-order/ATP commitment model (X20); FiscalPeriod module soft-close; GlConfig effective-dating; JournalLine subledger-key.

---

## Sequencing
Implement low-risk/ready first (D2 wiring, D1, D5-Tier1, D7-v1 columns), then the medium posting-adjacent themes (D3 instrument/bounce, D4 dimension enforcement, D6 + Position master). Each wave: edit owning migrations in place, entity/DTO/service, full IT verify, commit, merge develop → push. Defers above land in a later ADR (P2-deferred / P3).

## Consequences
Closes the bulk of the 36 P2 design gaps with standard-aligned v1s; one genuine GL-behaviour addition (D3 cheque-bounce reversal, append-only); one scoped posting-gate extension (D4 MANUAL-only dimension requiredness). No change to the base-only ledger or the Σ-gate. Two new minimal masters (`payment_runs`, `positions`) + `promotion_usages`/`ap_debit_note_allocations` children. Heavy masters (Territory, Segment, work-centre/routing, FS-mapping) explicitly deferred.
