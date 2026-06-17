# 0040 — P1 data-model gap design decisions (11 themes)

- **Status:** Accepted
- **Date:** 2026-06-17
- **Deciders:** owner + solutions-architect. Synthesised from 11 parallel mini-ADR proposals (one per P1 design theme), each grounded in the shipped entities/migrations/ADRs. Three consequential forks were ruled by the owner (see below); the other eight adopt the proposed standard-aligned default under the "decide-and-proceed" mandate.
- **Context:** the P1 *design* items from [docs/data-model/GAP-FIX-WORKLIST.md](../data-model/GAP-FIX-WORKLIST.md) (the de-conflicted gap backlog). P1 *mechanical* quick-wins are handled separately (in the currency feature branch). All changes respect: soft-FK cross-module convention (ADR-0039/0036), base-only ledger (ADR-0013/0036), `CurrencyCode`/`Money` (ADR-0039), uid/id duality, [[db-naming-convention]], and the dev-phase editable-migrations rule ([[dev-phase-migrations-editable]]).

## Owner rulings (the three consequential forks)

- **R1 — Parties contacts/addresses: per-kind child tables (8 tables).** Consistent with ADR-0006's separate-masters / no-polymorphic-FK stance; full referential integrity; Java duplication absorbed by a `@MappedSuperclass`. (Not the 2-table shared/polymorphic form.)
- **R2 — Credit control: hard-block at Sales Order confirmation** (overridable by `SALES.CREDIT.OVERRIDE`, audited), in addition to the existing invoice-finalise check. (Not warn-only-at-confirm.)
- **R3 — Employee net-pay disbursement: single bulk `NET_WAGES_PAYABLE` transfer + a per-employee EFT/bank-batch export file** the bank fans out. (Not per-employee cash fan-out.) GL run journal unchanged.

---

## D-1 — GL control accounts + per-branch GlConfig (risk: MEDIUM)
Add `chart_of_accounts.control_type` (nullable enum `AR|AP|BANK|CASH|INVENTORY|TAX|PAYROLL_CLEARING|FX_CLEARING`; NULL = ordinary) + derived `isControlAccount()`. The seeder stamps it on the seeded control accounts and sets `allow_manual_posting=false` so the **already-shipped** posting gate enforces the block with no new code path. **Split:** ship `control_type` now (P1, near-zero blast radius); treat per-branch `gl_configs.branch_id` override (NULL=company default + most-specific-first resolution) as a **separate, later slice** (reworks the unique constraint + every resolver call site). Respects ADR-0013 (branch stays an analysis tag, not a ledger).

**Refinement (implementation, 2026-06-17): classification ≠ manual-posting block.** `control_type` is a *classification* marker on all sub-ledger accounts (useful for reporting/grouping); the manual-posting block is a *separate* policy. Only the genuine sub-ledger/reconciliation controls block direct manual journals — **AR, AP, INVENTORY, TAX, PAYROLL_CLEARING, FX_CLEARING** (`allow_manual_posting=false`). **CASH and BANK are classified controls but stay manually postable** (`allow_manual_posting=true`): they are reconciled through the cash/bank module + bank reconciliation, which *expect* manual journals (bank charges, interest, corrections). This is standard-aligned — SAP reconciliation accounts ≈ AR/AP; NetSuite/QuickBooks both permit bank/cash journals — and blocking them broke nothing real, only test fixtures that used Cash as a generic posting target. Encoded as `ControlType.blocksManualPosting()` (returns false only for CASH/BANK); both the seeder and the `GLPostingServiceImpl` belt-and-suspenders control-type gate consult it. The migrate-seeded path (`V10`) mirrors the new-company `seedDefaults` path.

## D-2 — Shared PaymentTerms master (risk: LOW)
New `payment_terms` master in **parties** (`code`, `name`, `basis` `DAYS_AFTER_INVOICE|DAYS_AFTER_MONTH_END|DUE_ON_RECEIPT`, `net_days`, optional `discount_days`+`discount_percent` NUMERIC(5,2), `status`). Customer/Supplier reference it by scalar `payment_terms_id` soft-FK; the existing `payment_terms_days` integers stay as a **deprecated fallback** (no forced data migration). Due-date derivation reads the linked term, else the integer, else net-on-receipt. **Phase 1:** wire customers/suppliers only; document-level FKs (SO/SI/bill/PO/AR-invoice) are a later mechanical tranche. `discount_percent` is plain NUMERIC, not Money (base-only ledger untouched).

## D-3 — Parties contacts + addresses (X9) — per-kind child tables (R1) (risk: MEDIUM)
Per-kind `*_contacts` and `*_addresses` child tables for customer/supplier/agent/other_party (8 tables; Java via `@MappedSuperclass`). Addresses carry `address_role` `BILL_TO|SHIP_TO|GENERAL` + `is_default` (partial-unique ≤1 default per owner+role) + `country` (closes the PartyBase.country P2 gap); contacts carry `is_primary` (≤1). PartyBase single-line phone/email/address stay as the always-present primary (additive, no data migration). **Sales knock-on:** SalesOrder/SalesInvoice gain `ship_to_address_id`/`bill_to_address_id` (scalar soft-FK) **+ snapshot text columns** (immutable printed address, like line product-name snapshots); Delivery gains ship-to. Owned children omit `uid` (junction convention). Reuse existing `*.MANAGE` perms.

## D-4 — Supplier bank account (X10) (risk: LOW)
New `supplier_bank_account` child table (addressable sub-master, **has uid**): `bank_name`, `account_name`, `account_no`, `bank_branch`, `iban`, `swift_bic`, `currency` (CurrencyCode), `is_default` (partial-unique ≤1/supplier), `status`; `CHECK (account_no OR iban NOT NULL)`. AP side: scalar `supplier_bank_account_uid` (no FK) on `supplier_bills` + `ap_payments` (captured beneficiary); ownership validated in AP service. **v1 = bank-only EFT** (mobile-money via existing `supplier.mobile_money_no`). Payment-terms half of X10 already shipped.

## D-5 — Credit control — hard-block at SO confirm (R2) (risk: MEDIUM)
`customers`: `credit_status` enum `OK|WARNING|ON_HOLD|STOPPED` (default OK) + `manual_hold` + `credit_hold_reason` (reason required when held). New **hard block in `SalesOrderServiceImpl.doConfirm`**: a CREDIT customer that is ON_HOLD/STOPPED (manual or over-limit, balance via `ArBalanceService`) is blocked, overridable by `SALES.CREDIT.OVERRIDE` (audited) — mirrors the finalise-time check. `ar_invoices`: `dunning_level`, `last_reminder_date`, `disputed`+`dispute_reason`, `on_hold`+`hold_reason` (recording columns; the dunning *loop* stays deferred per ADR-0014 D-7; disputed/on-hold excluded from future auto-dunning). New `AR.DISPUTE.MANAGE` perm.

## D-6 — AR credit-note application (risk: MEDIUM)
Give the CN receipt-parity: `ar_credit_notes` gains `unapplied_amount` (+ base mirror, `fx_rate`/`rate_at`, `status` `UNAPPLIED|PARTIAL|APPLIED`) + a new `ar_credit_note_allocations` junction (CN→invoice, no uid, append-only). **GL timing:** the CN posts its full contra **once at raise** (DR Revenue/VAT / CR AR); **applying posts nothing** (sub-ledger move relieving invoice outstanding), realized-FX captured per allocation at apply (reuses REALIZED_FX_* keys). The existing raise-against-invoice path becomes raise+auto-apply-full (no regression). Balance reads net `− Σ unapplied`. (AP `ApDebitNote` is the parity sibling — separate follow-up.)

## D-7 — WHT end-to-end (X5) (risk: LOW)
Additive on the shipped ADR-0017 WHT spine (no posting change; WHT realized on the cash leg as today). `supplier_bills`: nullable `wht_type_id`/`wht_taxable_base`/`wht_amount` (plan/snapshot, non-posting). `ap_payments`: `wht_amount` + `wht_transaction_uid` (withheld on header). `wht_transactions`: `tin` (party tax id snapshot), `rate_pct` snapshot, remittance block (`remitted`+`remittance_period`+`remittance_ref`+`remitted_at/by`, CHECK mirrors VAT-return filed-fields). `wht_number` already serves as the certificate number. New `WHT.REMIT` perm. **v1 = flag-on-row remittance** (no remittance-run table).

## D-8 — AP supplier-bill line VAT + per-line GL account (risk: LOW)
`supplier_bill_lines`: `vat_status` (reuse products `VatStatus`), `vat_rate`, `line_vat_amount`, nullable `gl_account_id` (intra-DB FK → chart_of_accounts). Header net/vat/gross become the service-enforced Σ of lines (mixed-rate bills representable). Match-poster: a **service line** (`gr_line_uid IS NULL`) with `gl_account_id` set debits that account instead of the `PURCHASES` key; goods lines keep clearing GRNI. **v1 = data-only** — input VAT still aggregates to `VAT_INPUT` (no per-rate GL legs). No GL-engine / 3-way-match change.

## D-9 — AP on-account + bidirectional cheque (risk: MEDIUM)
Mirror the shipped AR pattern: `ap_payments` gains `unallocated_amount` + `status` `UNALLOCATED|PARTIAL|ALLOCATED` + scalar `cheque_uid`; apply = append-only allocation that posts nothing; **refund/on-account nets via contra-2100 (match AR)**, not a separate prepayment asset account. `cheques` becomes bidirectional: `direction` `OUTBOUND|INBOUND`, `ar_receipt_uid` (scalar), `deposited_at`/`bounced_at`/`bounce_reason`/`represent_count`, status widened `+DEPOSITED,+BOUNCED`. A bounce posts a reversing entry on the owning AR receipt / AP payment (append-only). AP balance read nets `− Σ unallocated`. (Inbound-cheque direction is the carrier for the AR receipt bounce/reversal gap — design together.)

## D-10 — Product planning + sourcing (X11) (risk: LOW)
`products`: planning defaults `reorder_level`/`reorder_qty`/`min_stock`/`max_stock`/`safety_stock` (NUMERIC 19,6, nullable) + `lead_time_days` + `purchasable BOOLEAN DEFAULT true` (buy-side twin of sellable/stockable) + scalar `preferred_supplier_id` FK→suppliers. Per-location `stock_on_hand.reorder_level` (already shipped) stays the operational override; the product field is the planner fallback. **lead-time two-level:** product = catalogue default, Supplier.leadTimeDays (separate P2) = sourcing override. Tracking flags already shipped.

## D-11 — Employee contact + bank, bulk EFT disbursement (R3) (X24/X25) (risk: MEDIUM)
`employees`: contact columns (`phone`, `email`, `address_line`, `region`, `district`, `postal_address`) + payee profile (`payment_method` `BANK_TRANSFER|MOBILE_MONEY|CASH|CHEQUE`, `bank_name`, `bank_branch`, `bank_account_no`, `bank_account_name`, `mobile_money_no`; CHECK payee target present for method). New owned child `employee_next_of_kin` (uid, `is_primary` partial-unique). `payroll_lines` snapshot the resolved payee (`payee_method`/`payee_account_ref`/`payee_bank_name`/`payee_account_name`) at calculate; a missing-target line FLAGs (blocks approve). **Disbursement (R3): keep one `NET_WAGES_PAYABLE` transfer; generate a per-employee EFT/bank-batch export file** — `disburse()` largely unchanged, GL journal unchanged (D-7 of ADR-0032 intact). New `HR.EMPLOYEE.PAYEE.VIEW` perm (bank details sensitive, mirrors salary gating).

---

## Sequencing
Implement low-risk independent themes first (D-2 payment-terms, D-4 supplier-bank, D-7 WHT, D-8 AP line-VAT, D-10 product-planning), then the medium-risk ones (D-1 control-accounts, D-3 contacts/addresses + sales knock-on, D-5 credit-control, D-6 AR-CN, D-9 AP-on-account/cheque, D-11 employee). Each theme: edit migrations in place (dev-phase), entity/DTO/service/test, keep the build + IT suite green, commit per wave.

## Consequences
Closes the 13 P1 genuine gaps. Two cross-module enablers land early (D-2 PaymentTerms master, D-3 parties contacts/addresses) that unblock many P2 document-level rows. No GL-engine change; base-only ledger and the sacred Σ-gate untouched. All additive/dev-phase migrations.

## Open follow-ups (deferred, recorded)
- Per-branch `gl_configs.branch_id` override (D-1 second half).
- Document-level `payment_terms_id` FKs on SO/SI/bill/PO/AR-invoice (D-2 Phase 2).
- `ApDebitNote` application parity (D-6 sibling).
- WHT remittance-run table + posting (D-7, if a true batch remit-to-TRA is wanted).
- Per-rate input-VAT GL legs (D-8, when the input-VAT return lands).
