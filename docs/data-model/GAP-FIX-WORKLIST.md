# Data-Model Gap-Fix Worklist

**Date:** 2026-06-17

**Method:** This worklist triages the data-model gap register against the *actual* shipped code (JPA entities), Flyway migrations (V1..V65), and the ratified ADRs. Every register row was reclassified into one of three statuses — `ALREADY_PRESENT` (the attribute/constraint already exists in schema or code, so the register read was stale), `DELIBERATE_ADR` (the absence is an explicit, accepted design decision recorded in an ADR), or `GENUINE_GAP` (truly missing and not deliberately declined). Genuine gaps are further tagged `MECHANICAL` (a plain additive column / enum-swap / entity-field-sync) or `DESIGN` (introduces a new concept, table, or posting/workflow rule that needs a modelling decision). The actionable backlog below contains **only** the genuine gaps; everything skipped is listed with its reason so nothing is silently dropped.

---

## Summary: counts by status x priority

| Status | P1 | P2 | P3 | Total |
|---|---:|---:|---:|---:|
| ALREADY_PRESENT | 3 | 12 | 7 | 22 |
| DELIBERATE_ADR | 11 | 40 | 5 | 56 |
| GENUINE_GAP | 13 | 81 | 28 | 122 |
| **Total** | **27** | **133** | **40** | **200** |

---

## Skip (not actionable)

These rows require no work: either the attribute/constraint is already shipped (`ALREADY_PRESENT`) or its absence is an explicit accepted ADR decision (`DELIBERATE_ADR`).

### ALREADY_PRESENT

| Module | Entity | Attribute | Why skipped |
|---|---|---|---|
| gl | GlConfig | unique (companyId, configKey) | V10 uq_gl_config_company_key already enforces it. |
| ar | ArInvoice | multi-currency base triple (fxRate, base amounts, rateAt) | All four FX cols present (ArInvoice.java:94-111, V62); ADR-0036 D-4. |
| ar | ArCreditNote | origin as enum | Already @Enumerated ArCreditNoteOrigin (V11/V17); the GOOD example in X2. |
| tax | WhtTransaction | distinct certificateNumber | wht_number IS the certificate number (ADR-0017 D-2e; uq_wht_transaction_company_number). |
| tax | VatAdjustment | VAT bad-debt relief reason (X6 tax-side) | VatAdjustmentReason.BAD_DEBT_RELIEF already exists; tax-side mechanism present. |
| fx | CurrencyRate | unique (company, from, to, effectiveDate, rateType) | V61 uq_currency_rate already exactly this key. |
| fx | FxRevaluationRun | status as enum (X2) | Already FxRevaluationRunStatus @Enumerated (V64 chk). Stale finding. |
| sales | SalesInvoice | base amount + fxRate (X1) | V62 added fx_rate + base_gross_total_amount + rate_at (SalesInvoice.java:140-158). |
| sales | SalesInvoice | currency as CurrencyCode | All sales entities already use CurrencyCode (ADR-0039). |
| parties | PartyCodeSequence | (complete for purpose) | Matches ADR-0006 D-7; no gap row raised. |
| products | Product | tracking flags batchTracked/serialTracked | V35 added lot_tracked/serial_tracked + chk_product_tracking_exclusive (schema present; entity field sync is the residue — see backlog). |
| products | CodeSequence | (complete for purpose) | Matches V3 DDL; no gap row raised. |
| stock | X11 | Product tracking flags (stock side) | V35 shipped lot_tracked/serial_tracked + child tables; entity field-map is the residue. |
| stock | X21 | StockTransferLine/StockCountLine currency length | Now CurrencyCode VARCHAR(3) (V33/V34); ADR-0039 D-5 fixed the length-10 drift. |
| stock | StockOnHand | per-batch breakdown reconciliation | By design: stock_batches with BR-INVD-10 Σ-invariant (ADR-0028 D-7). |
| hr | EmployeeLoanInstallment | status as enum (X28) | DB CHECK already present (V52); only Java type untyped — see backlog (MECHANICAL). |
| hr | StatutoryRateSet | basis as enum (X28) | DB CHECK already present (V51); only Java type untyped — see backlog (MECHANICAL). |
| budgeting | BudgetLine | currency as CurrencyCode | Already CurrencyCode VARCHAR(3); no X21 drift. |
| approvals | ApprovalDecision | (complete for purpose) | Append-only, matches ADR-0022 D-4. No gap. |
| documents | DocumentTemplate | version (optimistic-lock) | @Version via UidEntity + V19 version col already present. |
| documents | DocumentTemplate | isDefault per type | uq_document_template_company_type guarantees one row per type = the default. |
| documents | DocumentBranding | isDefault | Singleton per company (uq_document_branding_company); nothing to default among. |
| notifications | NotificationScanMarker | (complete) | Fire-once/re-arm fully modelled (V21, ADR-0024 D-7). |
| routes | Route | description | Served by location_identifier VARCHAR(500) (ADR-0012 D-2); separate col would duplicate. |
| iam | Organisation/Company/Branch/AppUser | audit columns (X30) | created/updated_at/by exist in V1 (schema-present); entity-mapping is the residue — see backlog. |
| iam | Role | audit columns (X30) | created/updated_at/by exist in V1; entity-mapping residue — see backlog. |
| iam | Company | baseCurrency (X35) | Company.base_currency shipped (V10, ADR-0005 D-4). Informational note. |

> Note on entity-mapping residue (X30/X11): the DB columns for IAM audit fields and the Product tracking flags are present in migrations; the JPA entities simply do not map them. Those mechanical entity-field syncs are carried into the actionable backlog (they are real code work even though no schema change is needed).

### DELIBERATE_ADR

| Module | Entity | Attribute | ADR / reason |
|---|---|---|---|
| gl | JournalLine | base/functional amount + line fxRate | ADR-0036: dual-amount ledger consciously declined; base triple lives on doc headers (BR-GL-06). |
| gl | JournalLine | X1 base amount + fx rate (cross-cut, GL scope) | ADR-0036 single-functional-currency posture for the ledger. |
| ar | ArInvoice | currency as String (X2) | ADR-0039: already CurrencyCode value type. |
| ar | ArReceipt | baseAmount / baseUnallocatedAmount (X1) | ADR-0036 D-4: base captured per-allocation, not on header (V62). |
| ar | ArReceiptAllocation | explicit realizedFxGainLoss | ADR-0036 D-5: realized FX is a derived balancing PLUG leg, not stored. |
| ar | ArReceiptAllocation | unallocate / reversal audit | ADR-0014 D / BR-AR-12: junction append-only, re-alloc = delete+reinsert. |
| ar | ArWriteOff | VAT bad-debt relief amount (X6) | ADR-0017 / OQ-VAT-03: relief via manual vat_adjustments; auto-derive deferred. |
| ap | ApPayment | baseAmount (header) | ADR-0036 D-4: only fx_rate + rate_at; base per-allocation. |
| tax | VatReturnBand | input side (inputBase/inputVat per band) | ADR-0017 D-2b/D-6: input VAT is a single period scalar; per-band deferred (OQ-VAT-06). |
| tax | VatAdjustment | sourceRef, in/out indicator, GL/approval linkage | ADR-0017 OQ-VAT-03/D-8: manual lines, rides return figure, no own GL leg. |
| tax | WhtType | glAccountId (per-type control) | ADR-0017 D-2d/D-5/D-9: two shared gl_configs keys per kind, not per type (OQ-VAT-02). |
| tax | WhtType | rate effective-dating, threshold, residency scope | ADR-0017 D-2d: thin master; full WHT matrix + rate history deferred (OQ-VAT-02). |
| tax | WhtTransaction | baseAmount (functional-currency) | ADR-0017 BR-VAT-13/OQ-VAT-07: tax module base-currency only; multi-currency deferred. |
| tax | WhtType/WhtTransaction (X5) | bills/payments reference WhtType + capture loop | ADR-0017 D-9: WHT rides cash leg via request DTOs + WhtCaptureService; capture loop exists. |
| fx | Currency | isBaseCurrency / functional flag | ADR-0005 D-4 / ADR-0039: base = Company.baseCurrency; default doc currency = company/branch_currency.is_default. Flag on global currencies rejected. |
| fx | Currency | rounding rule (separate from minor_units) | ADR-0039 OQ-CCY-07 resolved: single minor_units = rounding scale; no separate field. |
| fx | CurrencyRate | bid/ask/mid rate columns | ADR-0036: one effective-dated rate per (company,from,to) suffices in v1. |
| fx | CurrencyRate | rateType as enum (X2) | ADR-0036: enum rejected as speculative; typed-and-checked String column shipped. |
| fx | FX (module-wide) | currency columns as String (X2) | ADR-0039: CurrencyCode value object (not Java enum, not FK); already applied. |
| sales | SalesInvoice | outstandingAmount/paidAmount cache | ADR-0008/0021: AR is source of truth; header cache deliberately declined. |
| parties | Customer | creditStatus/onHold/credit-block flag | ADR-0006 OQ-PARTY-02: enforcement deferred to Sales/Finance; col records limit only. |
| parties | Agent | commissionRate / commission scheme | ADR-0006 OQ-PARTY-03: commission is a Sales concern; deferred. |
| parties | OtherParty | otherKind as enum (X2) | ADR-0006 OQ-PARTY: deliberately free-text so Other does not block on a fixed list. |
| parties | *Branch junctions | active / unassignedAt | ADR-0006 D-4: links exist-or-removed (hard delete); no status by design. |
| products | Product | costingMethod (FIFO/MA/STD) | ADR-0020: moving weighted-average only on stock_on_hand.avg_cost; others deferred. |
| products | Product | categoryId master + hierarchy (X12) | ADR-0007 D-6/OQ-PROD-04: category is free VARCHAR; master deferred. |
| products | Product | GL mapping income/cogs/inventory account | ADR-0020 D-3/D-4: posts via gl_configs keys; per-product override deferred (OQ-PROD-05). |
| products | ProductComponent | overlap with Bom; lineNo, unitId (X16) | ADR-0026 D-5/OQ-BOM-01: deliberate coexistence; base-unit qty; consolidation is future. |
| products | Bom | bomType/routingId/std-cost rollup | ADR-0026 §2/OQ-BOM-08: phantom/routing/persisted std-cost deferred; rollup is derived. |
| products | BomComponent | unitId for qtyPer, operationSeq, optional/substitute (X16) | ADR-0026 BR-BOM-06: qty in base units; routing/alternates deferred (OQ-BOM-08). |
| purchases | PurchaseOrder | net/vat/gross tax breakdown | ADR-0011 OQ-PURCH-04/BR-PURCH-08: no purchase VAT in v1; reserved additive (X18). |
| purchases | PurchaseOrderLine | line VAT (vatStatus/vatRate/vatAmount) | ADR-0011: purchase-line VAT declined for v1 (X18). |
| purchases | PurchaseOrder | receiving stockLocationId / delivery address | ADR-0027: single-location v1; multi-location deferred (X23). |
| purchases | PurchaseOrder | fxRate / base amount | ADR-0027/0011: PO single-currency; procurement multi-currency deferred. |
| purchases | PurchaseOrderLine | receiving stockLocationId | ADR-0027: single-location v1 (X23). |
| purchases | PurchaseRequisition | costCentreValueId/departmentValueId (FK) | ADR-0027 OQ-PROC-06: free-text cost_centre_code in v1; FK deferred (X22). |
| purchases | PurchaseRequisition | convertedToType / approvalStatus as enum | ADR-0027 D-6: thin engine-seam String mirror by intent (X22). |
| purchases | X18 (cross-cut) | PO/PO-line tax breakdown | ADR-0011 explicit deferral; SupplierBill VAT shipped, PO side remains open mini-ADR. |
| purchases | X22 (cross-cut) | String where enum/FK belongs (requisition) | ADR-0027 OQ-PROC-06/D-6: deliberate free-text + engine-seam strings. |
| purchases | X23 (cross-cut) | receiving stockLocationId on PO/PO line | ADR-0027: single-location v1, deferred. |
| stock | X19 (cross-cut) | StockMovement.batchId/serialId/lotNumber | ADR-0028 D-7/OQ-INVD-05: lot/serial in child tables, not on the movement ledger. |
| stock | StockMovement | batchId/serialId/lotNumber | Same ADR-0028 D-7/OQ-INVD-05 decision. |
| stock | StockBatch | status, batch unitCost/value, provenance | ADR-0028 D-7: identity-only, no per-lot cost layer; quarantine via LocationType. |
| stock | StockSerial | unitCost, warranty, sold-to, supplier, batch link | ADR-0028 D-7: identity-only, no per-serial cost layer. |
| stock | StockTransfer | in-transit/glEntryUid/transferMode-enum (load-bearing parts) | ADR-0028 D-2/D-5: transfer posts no GL; in-transit via internal location. |
| stock | StockTransferLine | batchId/serialNo, currency length (done parts) | ADR-0028 OQ-INVD-05: lot/serial on transfer lines deferred; X21 done. |
| stock | StockCountLine | batchId/serialNo, currency length (done parts) | ADR-0028 OQ-INVD-05: lot/serial on count lines deferred; X21 done. |
| hr | EmploymentContract (currency) | currency as CurrencyCode | Already typed CurrencyCode; ADR-0039 N/A. |
| hr | PayeBandSet | effectiveTo (open-ended set) | ADR-0032 D-3: append-only sets superseded by next effective_from. |
| hr | StatutoryRateSet | effectiveTo | ADR-0032 D-3: append-only effective-dated sets. (floor part is a genuine MECHANICAL gap — see backlog.) |
| hr | PayrollRun | paymentBatchUid + runType/period (deliberate parts) | ADR-0032 D-9/§430: no disbursement table (reuse cash_transactions.source_ref); off-cycle/freq deferred. |
| hr | PayrollLine | workedDays/LOP/overtime (deliberate parts) | ADR-0032 OQ-HR-01: salaried-only v1, attendance deferred. |
| hr | PayrollLineItem | quantity/rate (deliberate parts) | ADR-0032 OQ-HR-01: overtime qty×rate deferred with attendance. (itemKind-enum + snapshot are residue — see backlog.) |
| fixedassets | AssetCategory | disposalGain/Loss/revalReserve account | ADR-0030 D-6/D-11/OQ-FA-04: resolved via company GlConfig keys, not per-category (V40). |
| fixedassets | FixedAsset | location/costCentreId as FK | ADR-0030 D-2/OQ-FA-07: free-text location + scalar cost_centre_id reserved for dimension framework. |
| fixedassets | AssetTransfer (entity) | dedicated transfer record | ADR-0030 D-6(f)/FR-FA-16: transfer = register edit, no GL, audit-only. |
| crm | Lead | rating / score | ADR-0031 OQ-CRM-08: lead scoring/auto-assignment deferred. |
| crm | Opportunity | weightedValue (est×prob) | ADR-0031 D-9: computed in read model, not stored. |
| crm | Opportunity | competitorId, campaignId | ADR-0031 D-1/OQ-CRM-08: campaign object deferred; both need new masters. |
| crm | Activity | generic parent (entityType/entityId) | ADR-0031 BR-CRM-07/D-6: exactly one lead XOR opportunity parent; Parties depth deferred. |
| projects | Project | billingType (FIXED/T&M/MILESTONE) | ADR-0033 OQ-PROJ-08: billing/recognition deferred. |
| projects | Project | contractValue / revenueRecognised | ADR-0033 D-6/D-8: WIP reported, recognition deferred; PROJECT_WIP key reserved. |
| projects | Project | percentComplete | ADR-0033: POC deferred (OQ-PROJ-08). |
| projects | Project | costToDate | ADR-0033 D-6: derived in ProjectCostingQuery, not stored. |
| projects | Project | parentProjectId (sub-projects) | ADR-0033: multi-level WBS deferred. |
| projects | Project | cost-vs-revenue budget split | ADR-0033 D-2: single budget_amount; budget-by-type deferred. |
| projects | ProjectTask | percentComplete | ADR-0033: POC/scheduling deferred. |
| projects | ProjectTask | dependsOnTaskId | ADR-0033 D-2: one level; Gantt/scheduling deferred. |
| projects | ProjectTask | milestone flag | ADR-0033: milestone billing deferred. |
| projects | ProjectTimesheet | approvalStatus/approvedBy | ADR-0033 OQ-PROJ-05: timesheets informational; approval presupposes billing loop. |
| projects | ProjectTimesheet | billed / invoiceUid | ADR-0033 OQ-PROJ-05: T&M billing deferred. |
| projects | ProjectTimesheet | costRateAmount | ADR-0033 OQ-PROJ-05: cost = tagged GL; cost-rate split deferred. |
| manufacturing | WorkOrder | scheduledStart/End/dueDate/priority | ADR-0035 OQ-MFG-06/OQ-MFG-10: scheduling/MRP/capacity deferred. |
| manufacturing | WorkOrder | variance split (material/labour/overhead) | ADR-0035 OQ-MFG-10: single variance_amount; split needs deferred standard-costing. |
| manufacturing | WorkOrderOperation | workCentreId (FK) | ADR-0035 D-3: free-text work_centre; no work-centre master in v1. |
| manufacturing | WorkOrderOperation | time tracking (planned/actual/setup/run) | ADR-0035 OQ-MFG-05: flat applied amount; time×rate deferred. |
| manufacturing | WorkOrderOperation | operatorId/startedAt/completedAt | ADR-0035 OQ-MFG-06: execution/shop-floor tracking deferred. |
| manufacturing | WorkOrderOperation | status (operation lifecycle) | ADR-0035: boolean `applied` is the intended v1 state; full status deferred with execution tracking. |
| budgeting | Budget | generic dimensionValueId | ADR-0034 D-3/OQ-BUD-09: consumes COST_CENTRE slot only; multi-dimensional deferred. |
| budgeting | BudgetLine | dimensionValueId (per-line) | ADR-0034 D-3: cost-centre at header; per-line multi-dimensional deferred. |
| approvals | ApprovalPolicy | conditions beyond amount (supplier/category) | ADR-0022 OQ-APR-02: amount-band + branch only in v1; richer conditions deferred. |
| approvals | ApprovalPolicyStep | approverUserId (named individual) | ADR-0022 OQ-APR-05: role-only routing; named/group deferred. |
| approvals | ApprovalPolicyStep | quorum / minApprovers (N-of-M) | ADR-0022 line 417: single-approver v1; quorum deferred. |
| approvals | ApprovalPolicyStep | slaHours / escalation | ADR-0022 line 417: SLA/escalation deferred. |
| approvals | ApprovalPolicyStep | allowSelfApproval | ADR-0022 OQ-APR-03: SoD enforced; per-company toggle deferred. |
| approvals | ApprovalRequest | dueDate / SLA | ADR-0022 line 417: depends on deferred step-SLA model. |
| approvals | ApprovalRequest | escalatedAt | ADR-0022 line 417: deferred with escalation feature. |
| approvals | ApprovalRequestStep | assignedUserId | ADR-0022 OQ-APR-05: pull-inbox role model; assignment deferred. |
| approvals | ApprovalRequestStep | dueAt | ADR-0022 line 417: deferred SLA/escalation. |
| approvals | ApprovalRequestStep | delegatedTo | ADR-0022 line 417: delegation deferred (needs delegation table). |
| approvals | ApprovalEngine (X32) | named user/quorum/SLA/delegation/conditions | ADR-0022: umbrella of the above; all deferred-by-design seams. |
| documents | DocumentTemplate | templateBody / content | ADR-0023 D-3: code-driven renderer_key; user-editable templates deferred. |
| documents | DocumentTemplate | locale / language | ADR-0023: multi-language deferred. |
| documents | DocumentTemplate | version (content) | ADR-0023 D-3: code-driven immutable per type; moot until templateBody exists. |
| documents | DocumentBranding | branchId (per-branch branding) | ADR-0023 D-2: company 1:1 singleton; per-branch deferred. |
| documents | DocumentBranding | signatureRef | ADR-0023: digital signatures deferred. |
| documents | DocumentBranding | isDefault | ADR-0023 D-2: singleton profile; nothing to default among. |
| documents | GeneratedDocument | storageRef / file URL (X34) | ADR-0023 D-4/OQ-DOC-03: re-render on download; no blob store. |
| documents | GeneratedDocument | version / supersededBy | ADR-0023 D-4/BR-DOC-08: append-only; reissue = new row. |
| documents | GeneratedDocument | emailedAt / delivered-to | ADR-0023 D-7: delivery owned by Notifications, not the render log. |
| notifications | NotificationType | severity as enum (X2) | ADR-0024 D-2: enum-as-string + CHECK convention. |
| notifications | NotificationType | defaultChannels as enum | ADR-0024 D-2a: CSV-of-enum-names (a SET), not a single enum column. |
| notifications | NotificationType | throttle/dedup window config | ADR-0024 line 627: digests/throttle/quiet-hours deferred. |
| notifications | Notification | channel as enum (X2) | ADR-0024 enum-as-string + CHECK. |
| notifications | Notification | severity as enum (X2) | ADR-0024 enum-as-string + CHECK. |
| notifications | Notification | dismissedAt / archivedAt | ADR-0024 BR-NOTIF-05: row immutable except isRead; lifecycle deferred. |
| notifications | NotificationDelivery | providerMessageId | ADR-0024 D-6: no external provider in v1; upgrade seam. |
| notifications | NotificationDelivery | nextRetryAt | ADR-0024 D-6: append-only retry rows; poller-drained scheduling deferred. |
| notifications | NotificationPreference | quietHoursStart/End | ADR-0024 line 627: quiet-hours deferred. |
| notifications | NotificationPreference | digestFrequency | ADR-0024 line 627: digests deferred. |
| notifications | CROSS-CUTTING X2 | enum-vs-String drift (4 cols) | ADR-0024 D-2/D-3: deliberate enum-as-string + CHECK; behaviour-neutral. |
| routes | Route | visitDays / schedule | requirements §2/§13 + ADR-0012: journey plans / visit days deferred. |
| routes | RouteCustomer | visitSequence / visitFrequency | requirements §2/§13 + ADR-0012 D-3: unordered set; sequencing deferred (X33). |
| routes | X33 (cross-cut) | RouteCustomer no visit sequence | Same as above; deferred by ADR-0012 D-3. |
| costing | (note) | — | (no DELIBERATE_ADR rows; all costing rows are genuine gaps) |

---

## Actionable backlog (GENUINE_GAP only)

Grouped by priority (P1 first), then module. `kind` = MECHANICAL (plain column / enum-swap / entity-field sync) or DESIGN (needs a modelling/posting/workflow decision — see the mini-ADR list below).

### P1

| Module | Entity | Attribute | Kind | Note |
|---|---|---|---|---|
| gl | ChartOfAccount | allowManualPosting / isPostable | MECHANICAL | Single boolean flag; posting gate already reads is_active, so the validation hook exists. Leaf/summary posting is enforced nowhere today. |
| gl | ChartOfAccount | isControlAccount / controlType | DESIGN | Introduce a control-account concept + reconcile-to-subledger / block-manual-posting rules; interacts with allowManualPosting + subledger drill-down. |
| gl | GlConfig | branchId override | DESIGN | Changes the unique key and the auto-poster resolution/fallback logic. |
| ar | ArInvoice | dunningLevel / lastReminderDate, disputed/onHold + reason | DESIGN | Credit-control states + reason codes + reminder log; ADR-0014 D-7 deferred the loop but the columns are genuinely absent. |
| ar | ArCreditNote | outstandingAmount / applied tracking (+ allocation table) | DESIGN | Needs a child allocation table + unapplied-balance column (unlike receipts, a CN cannot track partial application today). |
| ap | SupplierBill | whtTypeId / whtAmount (declare WHT at bill entry) | DESIGN | WHT is wired only at payment; declaring/planning at bill needs snapshot-rate + payable/receivable scope (X5). |
| ap | SupplierBillLine | line-level VAT (vatCode/vatRate/vatAmount) | DESIGN | VAT is header-only; mixed-rate bills can't be represented; touches bill VAT model + 3-way match. |
| ap | SupplierBillLine | glAccountId on the line | DESIGN | Per-line expense/GL account for service/non-stock lines; today debits the PURCHASES config key. |
| ap | ApPayment | unallocatedAmount (on-account / prepayment) | DESIGN | AP has no supplier-prepayment concept; implies on-account apply/refund workflow (X8). |
| ap | ApPayment | whtAmount (withheld on payment header) | DESIGN | Withheld value lives only on WhtTransaction; persisting + certificate/remittance loop (X5). |
| ap | ApPayment | chequeId (cheque instrument link) | DESIGN | Forward link missing; cross-module uid-vs-FK + inbound cheque modelling decision. |
| cashbank | Cheque | inbound (received) cheque modelling — no direction field | DESIGN | Cheque models only outgoing; customer-cheque banking/bounce unmodelled (direction + dishonour). |
| cashbank | BankReconciliation | statementOpeningBalance + reconciling difference / unreconciledAmount | MECHANICAL | Opening balance (continuity) + the core rec output figure are plain columns (compared in-memory today). |
| sales | Customer (knock-on) | creditStatus / onHold / credit-block flag | DESIGN | SO confirmation cannot stop-supply an over-limit customer; credit-hold workflow decision. |
| sales | Sales docs (X9) | contacts/addresses child tables on parties | DESIGN | Root cause of missing shipTo/billTo across SO/SI/Delivery; upstream Parties decision. |
| parties | PartyBase | child contacts + addresses tables (bill-to/ship-to) | DESIGN | New party_contact + party_address tables; knock-on to sales addresses (X9). |
| parties | Supplier | paymentTermsDays / terms | MECHANICAL | Mirror Customer.paymentTermsDays; blocks AP due-date derivation (X10). |
| parties | Supplier | bank account details (or child table) | DESIGN | supplier_bank_account child table; also satisfies SupplierBill.supplierBankAccountId (X10). |
| products | Product | inventory planning: reorderQty/min/max/safety/leadTime (X11) | DESIGN | Per-location reorder_level exists on stock_on_hand; the full planning set needs a mini-ADR on where it lives. |
| products | Product | purchasable flag + preferredSupplierId (X11) | DESIGN | purchasable alone is mechanical, but preferredSupplierId is a products→parties link (FK vs child-with-terms). |
| hr | Employee | contact details: phone/email/address/emergency contact (X24) | DESIGN | Best modelled as embeddable/child rows mirroring parties; ADR-0032 D-4 omitted contact. |
| hr | Employee | bank/payment disbursement: bankName/accountNo/method/mobile-money (X25) | DESIGN | Per-employee EFT changes the single-transfer NET_WAGES_PAYABLE disbursement model. |
| tax | WhtTransaction | tin (party tax ID) + remittance tracking (remitted/ref/period) | DESIGN | TIN is mechanical; the remittance loop needs a small model (X5 residual). |

### P2

| Module | Entity | Attribute | Kind | Note |
|---|---|---|---|---|
| gl | ChartOfAccount | dimension-requirement flags (require CostCentre/Dept/Project) | DESIGN | Per-account requiredness enforced at posting time across all posters. |
| gl | ChartOfAccount | currency / currency-mode | MECHANICAL | Master-data convenience (lock a bank/clearing account to one currency); nullable currency + optional mode. |
| gl | ChartOfAccount | financial-statement mapping / reportingGroup / cash-flow class | DESIGN | Reporting taxonomy / mapping model feeding BS/P&L/cash-flow. |
| gl | FiscalYear | reopen audit (reopenedAt/reopenedBy) | MECHANICAL | reopenPeriod clears close cols; dedicated reopen trail missing (two-column add). |
| gl | FiscalPeriod | module-level soft-close (per-module locks) | DESIGN | Child lock table or per-module status cols + poster enforcement. |
| gl | GlConfig | effective dating | DESIGN | Changes the unique constraint; poster resolves as-of posting date. |
| gl | JournalBatch | control totals (totalDebit/totalCredit) | MECHANICAL | Denormalised-sum convenience columns. |
| gl | JournalEntry | reversedByEntryId / reversed flag | MECHANICAL | Inverse-direction link to cheaply tell an entry has been reversed. |
| gl | JournalEntry | header currency + control totals | MECHANICAL | Informational header currency + denormalised totals (ledger is base-only). |
| gl | JournalEntry | status / DRAFT lifecycle | DESIGN | Conflicts with append-only post-only model (BR-GL-02); ties to ADR-0022 approvals. |
| gl | JournalLine | subledger key (partyType/partyId) | DESIGN | Drill from GL line to open AR/AP item; interacts with control-account concept. |
| gl | JournalLine | line taxCode / taxAmount | MECHANICAL | Nullable tax tagging at posting line (GL tax derived upstream). |
| ar | ArInvoice | paymentTermsId, settlement discount (discountDueDate/Amount) | DESIGN | Needs payment-terms master + settlement-discount at allocation. |
| ar | ArReceipt | tenderType as enum + instrument link (cheque/mobile/card) | DESIGN | Enum swap mechanical; instrument link is a design addition (X2). |
| ar | ArReceipt | bounce/reversal (reversedAt/reversalOf) for dishonoured cheques | DESIGN | New lifecycle + reversal linkage (ties to inbound Cheque). |
| ar | ArReceiptAllocation | discountAmount / writeOffAmount at allocation | DESIGN | Settlement-discount / residual write-off; couples to ArInvoice settlement-discount. |
| ar | ArCreditNote | fxRate / base amounts, status (lifecycle) | DESIGN | CN excluded from V62 base-triple; no status enum (X4 + FX consistency). |
| ar | ArWriteOff | approval linkage, writeOffType, recovery/reversal | DESIGN | Sensitive action; wire to Approvals engine (ADR-0022). |
| ap | SupplierBill | supplierBankAccountId, payment hold, paymentTermsId + discount | DESIGN | Pay-run essentials; needs supplier-master + bank-account modelling (X10). |
| ap | SupplierBill | taxPointDate / receivedDate | MECHANICAL | Plain nullable DATE columns. |
| ap | SupplierBillLine | line dimensions (costCentreValueId/departmentValueId) | MECHANICAL | Same FK pattern as journal_lines (V23); header has them, line doesn't (X7). |
| ap | ApPayment | status (void/cleared), paymentRunId, tenderType enum | DESIGN | No lifecycle / run grouping today; workflow change (X4 + X2). |
| ap | ApPaymentAllocation | allocatedAt / allocatedBy | MECHANICAL | AR has them; simple column add. |
| ap | ApPaymentAllocation | discountAmount/writeOffAmount, explicit realizedFxGainLoss, reversal audit | DESIGN | Settlement-workflow decisions (base capture already present). |
| ap | ApDebitNote | origin as enum | MECHANICAL | Currently VARCHAR(100) carrying a uid suffix; clean enum needs a separate origin-ref col (X2). |
| ap | ApDebitNote | outstanding/applied tracking, fxRate/base, status | DESIGN | Parity with credit note; allocation/lifecycle decision (X4). |
| ap | BillMatch | split price/qty match status, matchType (2/3-way), variance reason | DESIGN | Changes the match model + CHECK constraints. |
| cashbank | CashBankAccount | iban / swift / bic / sortCode | MECHANICAL | Nullable column adds for BANK accounts. |
| cashbank | CashBankAccount | openingBalance/openingDate/glClearingAccountId | DESIGN | Clearing account introduces a second GL routing leg (undeposited/in-transit). |
| cashbank | CashBankAccount | overdraftLimit/minimumBalance/lastReconciledDate/Balance | MECHANICAL | Plain treasury columns / cached rec figures. |
| cashbank | CashTransaction | valueDate / chequeId | MECHANICAL | valueDate + cheque link are genuine simple gaps (statementLineRef is deferred). |
| cashbank | CashTransfer | bank-fee fields (chargeAmount + fee GL account) + status (in-transit) | DESIGN | Fee leg changes posting; needs fee GL routing decision. |
| cashbank | Cheque | bounce, cheque-book/range, staleDate, drawee bankName | DESIGN | Simple cols + cheque-stock concept + bounce lifecycle states. |
| cashbank | BankReconciliation | outstanding cheques/deposits totals, adjustmentJournalUid, statement-file ref | DESIGN | adjustmentJournalUid implies in-rec posting workflow (not built). |
| tax | VatReturn | payment tracking (paidAt/paidAmount/paymentReference) | MECHANICAL | Cash-settlement-to-TRA columns ('filed != paid'). |
| tax | VatReturn | turnover figures (sales/purchases incl. zero-rated/exempt) | MECHANICAL | Statutory gross turnover; additive columns / extend band snapshot. |
| tax | VatReturn | amendment (amendedReturnId/isAmendment), penalty/interest | DESIGN | New AMENDED state + self-link + penalty/interest fields. |
| tax | WhtTransaction | ratePct snapshot | MECHANICAL | Certificate can't reconstruct its rate after a type's rate changes. |
| fx | Currency | numericCode (ISO-4217 numeric) | MECHANICAL | Nullable display/integration column. |
| fx | CurrencyRate | effectiveTo (validity end / explicit range) | DESIGN | Closed range would change the as-of lookup semantics. |
| fx | FxRevaluationRun | executedBy | MECHANICAL | Distinct actor column. |
| fx | FxRevaluationRun | reversalDate / reversalPeriodId | MECHANICAL | Track 'posts on next-period open' intent for not-yet-open periods. |
| fx | FxRevaluationRun | rateType used / scope summary | MECHANICAL | Record which rate basis + scope; low priority. |
| fx | FxRevaluationRunLine | per-open-item detail (drill-down child rows) | DESIGN | Lines are aggregated; drill-down needs a new child table. |
| fx | FxRevaluationRunLine | priorRate / GL line linkage | MECHANICAL | Prior-rate snapshot + per-line journal-line trail. |
| sales | Quotation | customerPoNumber (X14) | MECHANICAL | Nullable VARCHAR. |
| sales | Quotation | paymentTermsId | DESIGN | Needs PaymentTerms master (no master exists). |
| sales | Quotation | revisionNo | MECHANICAL | Plain revision counter. |
| sales | Quotation | probability | MECHANICAL | CRM win-probability percent (Opportunity already has winProbability). |
| sales | SalesOrder | customerPoNumber (X14) | MECHANICAL | Nullable VARCHAR. |
| sales | SalesOrder | requestedDeliveryDate/promisedDate | MECHANICAL | Two nullable DATE columns. |
| sales | SalesOrder | paymentTermsId | DESIGN | PaymentTerms master decision. |
| sales | SalesOrder | shipToAddressId/billToAddressId (X9) | DESIGN | Blocked by PartyBase contacts/addresses decision. |
| sales | SalesOrder | warehouseId/stockLocationId (default fulfilment) | DESIGN | Fulfilment-source pin; ties to StockLocation hierarchy. |
| sales | SalesOrderLine | promotionId (X15) | DESIGN | Provenance link decided uniformly across all sales lines. |
| sales | SalesOrderLine | requestedDate (per-line) | MECHANICAL | Nullable DATE. |
| sales | SalesOrderLine | stockLocationId | DESIGN | Line-level sourcing location pin. |
| sales | SalesOrderLine | discountReason | MECHANICAL | Nullable VARCHAR. |
| sales | SalesInvoice | dueDate/paymentTermsId | DESIGN | PaymentTerms master decision (due date lives on ArInvoice today). |
| sales | SalesInvoice | customerPoNumber (X14) | MECHANICAL | Nullable VARCHAR. |
| sales | SalesInvoice | shipTo/billTo snapshot (X9) | DESIGN | Print-doc snapshot; blocked by PartyBase addresses. |
| sales | SalesInvoiceLine | costAmount/COGS per line (margin) | DESIGN | Costing-snapshot decision (which basis, when stamped). |
| sales | SalesInvoiceLine | promotionId (X15) | DESIGN | Provenance link. |
| sales | SalesInvoiceLine | stockLocationId | DESIGN | StockLocation pin. |
| sales | SalesInvoicePayment | cashBankAccountId/till | DESIGN | How non-POS immediate payments bind to an account. |
| sales | SalesInvoicePayment | chequeId / structured mobile-money/card fields | DESIGN | Instrument-link modelling (ties to cashbank Cheque). |
| sales | Delivery | shipToAddress | DESIGN | Blocked by PartyBase addresses (X9). |
| sales | Delivery | carrier/vehicle/driver/trackingNo, dispatch/POD, routeId | DESIGN | Logistics/POD sub-model. |
| sales | DeliveryLine | stockLocationId/batchId/serialNo issued | DESIGN | Picking lot/serial/bin traceability (X19). |
| sales | SalesReturn | restockLocationId, condition, refundMethod | DESIGN | Returns-handling modelling. |
| sales | TaxRate | effectiveFrom/To (rate history) | DESIGN | Rate-change-over-time model + date-based snapshot resolution. |
| sales | TaxRate | name, taxType | MECHANICAL | name plain; taxType a small enum to generalise beyond VAT. |
| sales | PosSession | per-tender expected/counted (card/mobile, not just cash) | DESIGN | Child table or repeating cols + extended close/reconcile. |
| sales | StandingOrder | lastRunDate, occurrencesGenerated/maxOccurrences, autoConfirm | MECHANICAL | Recurrence bookkeeping the @Scheduled generator maintains. |
| parties | PartyBase | country | MECHANICAL | Nullable col on all four master tables (only region/district today). |
| parties | Customer | default priceListId / priceTierId | DESIGN | Cross-module FK to a price-list master + pricing-resolution. |
| parties | Customer | defaultAgentId, routeId, territoryId, customerGroup/segment | DESIGN | Implies new master concepts (territory/segment) + group-pricing. |
| parties | Customer | taxExempt / exemption ref | MECHANICAL | Boolean + optional ref. |
| parties | Customer | defaultCurrency | MECHANICAL | CHAR(3)/CurrencyCode; not covered by ADR-0039. |
| parties | Supplier | default whtTypeId, defaultCurrency, leadTimeDays, minOrderValue | DESIGN | whtTypeId ties to WHT wiring (X5); others simpler. |
| parties | Agent | default routeId/territoryId, sales target/quota | DESIGN | New master concepts + performance-tracking. |
| products | UnitOfMeasure | dimensionType, decimalPlaces/isFractional | DESIGN | UoM family/dimension + fractional control; cross-product conversion semantics. |
| products | ProductBarcode | uomId/pack ref, barcodeType (EAN/UPC/CODE128) | MECHANICAL | Symbology enum + optional bulk-pack FK. |
| products | ProductBulkPack | barcode per pack, isPurchaseDefault/isSaleDefault | MECHANICAL | Additive columns/flags. |
| products | PriceList | currency, effectiveFrom/To, priceIncludesVat, isDefault, scope | DESIGN | Affects pricing-resolution semantics. |
| products | PriceTier | maxQty, effectiveFrom/To (X13) | MECHANICAL | maxQty + nullable DATE columns. |
| products | ProductPrice | effectiveFrom/To (X13) | MECHANICAL | Nullable DATE columns. |
| products | Product | brand, manufacturer, weight/volume/dimensions, hsCode | MECHANICAL | Plain logistics/customs columns. |
| products | Promotion | customer/branch scope, min threshold, usageLimit/coupon, combinable | DESIGN | Targeting + guardrails + usage-tracking table. |
| purchases | PurchaseOrder | paymentTermsId / deliveryTerms | DESIGN | Needs Supplier payment-terms master (X10). |
| purchases | PurchaseOrder | buyerId | MECHANICAL | Nullable FK. |
| purchases | PurchaseOrder | invoicedAmount / billing status at header | MECHANICAL | Header roll-up of billed-vs-ordered. |
| purchases | PurchaseOrderLine | billedQtyInBase | MECHANICAL | Maintained column (mirrors received_qty_in_base). |
| purchases | PurchaseOrderLine | glAccountId / expense account for non-stock lines | DESIGN | Service-line GL routing on the PO line. |
| purchases | PurchaseOrderLine | line dimensions (costCentre/department) | DESIGN | Needs dimension-framework wiring (X22). |
| purchases | PurchaseOrderLine | requiredByDate | MECHANICAL | Nullable DATE. |
| purchases | PurchaseOrderLine | cancelledQty | MECHANICAL | Interacts with line-immutability rule. |
| purchases | PurchaseRequisition | priority/urgency | MECHANICAL | Simple enum/short column. |
| purchases | PurchaseRequisition | budgetLineId / budget check | DESIGN | Commitment/encumbrance link (deferred by ADR-0027). |
| purchases | PurchaseRequisition | preferredSupplierId | MECHANICAL | Nullable supplier FK. |
| purchases | PurchaseRequisitionLine | requiredByDate | MECHANICAL | Nullable DATE. |
| purchases | PurchaseRequisitionLine | suggestedSupplierId | MECHANICAL | Nullable supplier FK. |
| purchases | PurchaseRequisitionLine | budgetLineId | DESIGN | Same budget/encumbrance deferral. |
| purchases | PurchaseRequisitionLine | convertedToPoLineUid | MECHANICAL | Per-line traceability scalar uid. |
| purchases | Rfq | awardReason/justification, requested terms | DESIGN | Terms-solicitation + award-audit modelling. |
| purchases | RfqLine | requiredByDate, specification/notes | MECHANICAL | Nullable DATE + text. |
| purchases | RfqSupplier | respondedAt/responseStatus, quote link | MECHANICAL | Response tracking columns. |
| purchases | SupplierQuote | terms/incoterms, supplier quote ref, score/rank, warranty | DESIGN | Structured terms/score is a bid-evaluation decision. |
| purchases | SupplierQuoteLine | per-line leadTime, minOrderQty, discount, VAT, spec note | DESIGN | Line VAT ties to X18; comparison fields are bid-eval. |
| purchases | PurchaseSettings | default terms/location, match tolerances, auto-close, requisition-approval | DESIGN | Central P2P policy defaults; depends on payment-terms master. |
| purchases | X20 (cross-cut) | on-order/ATP (incomingQty on StockOnHand) | DESIGN | Maintained incoming col or PO-line open-qty rollup; spans stock+purchases. |
| stock | X20 (cross-cut) | StockOnHand on-order/incoming + ATP-incl-inbound | DESIGN | reserved_qty present; inbound aggregation missing. |
| stock | X22 (cross-cut) | StockTransfer.transferMode + StockCount.countType as enum | MECHANICAL | DB CHECK present; sibling status fields are enums; low-risk swap. |
| stock | X23 (cross-cut) | receiving stockLocationId on PO/PO line | DESIGN | Belongs to purchases backlog (stock entities have location_id). |
| stock | StockLocation | parentLocationId, allowNegative, pickable/sellable, per-loc glAccountId | DESIGN | Hierarchy + negative policy + inventory-account override. |
| stock | StockOnHand | maxQty, lastMovementAt, lastCountedAt | MECHANICAL | Plain additive columns. |
| stock | StockTransfer | dispatchedBy/receivedBy, expectedArrivalDate | MECHANICAL | Minor actor/date adds (glEntryUid/in-transit are by design). |
| stock | StockTransferLine | qtyDispatched vs qtyReceived (partial receipt) | DESIGN | Partial transfers aren't modelled; workflow decision. |
| stock | StockCount | countedBy/approvedBy, scope/category filter, recountRequired | DESIGN | SoD actors + cycle-scope + recount workflow. |
| stock | StockCountLine | recountedQty/second count, per-line countedBy | DESIGN | Recount workflow decision (lot/serial + currency are ADR-handled). |
| hr | Employee | terminationDate/reason, confirmation/probation, managerId, positionId/grade, marital, nationality | DESIGN | positionId/grade needs a position/job-grade master (X26). |
| hr | Department | parentDepartmentId, managerId, costCentreValueId, branchId (X26) | DESIGN | Org hierarchy (self-FK) + manager + GL-dimension links. |
| hr | EmploymentContract | probation/notice, working hours/days, jobGrade, signed-doc link | DESIGN | Hours/days meaningful only with deferred attendance; signed-doc → documents. |
| hr | EmployeeLoan | interestRate/schedule, installments/term, loanType, approvedBy, disbursed | DESIGN | Interest changes installment math + schedule child; approvedBy → approvals. |
| hr | EmployeeLoanInstallment | status as enum (X28) | MECHANICAL | DB CHECK exists; add LoanInstallmentStatus enum + @Enumerated. |
| hr | EmployeeLoanInstallment | deductedAmount (partial), dueDate/paidAt | MECHANICAL | Additive columns. |
| hr | LeaveType | carryForward, requiresApproval, gender/eligibility, maxConsecutive | DESIGN | Changes accrual/balance computation + leave_balances shape. |
| hr | LeaveBalance | carriedForwardDays, accruedDays, pendingDays, adjustmentDays | DESIGN | Persistence side of the carry-forward/accrual policy (design with LeaveType). |
| hr | LeaveRequest | half-day flags, coveringEmployeeId, attachment, approvalRequestUid | DESIGN | approvalRequestUid → approvals engine; attachment → documents. |
| hr | PayComponent | wcf/sdlApplicable, displayOrder, formula/computed, proRatable | DESIGN | Granular statutory inclusion + formula components are modelling changes. |
| hr | PayeBandSet | resident-vs-non-resident scope | DESIGN | Only payeResident boolean on contract; no separate non-resident band set (X29). |
| hr | StatutoryRateSet | basis as enum (X28) | MECHANICAL | DB CHECK exists; add StatutoryBasis enum + @Enumerated. |
| hr | StatutoryRateSet | floorAmount/minimum (X29) | MECHANICAL | ceiling_amount exists; floor does not (small additive column). |
| hr | PayrollLine | contractId snapshot | MECHANICAL | Pin the active contract id on the line for audit (other parts deferred). |
| hr | PayrollLineItem | taxable/pensionable snapshot; itemKind as enum (X28) | MECHANICAL | itemKind DB CHECK exists; type the field + add snapshot audit cols. |
| fixedassets | FixedAsset | custodianId / responsible employee | DESIGN | HR employee soft-link + register-accountability workflow. |
| fixedassets | FixedAsset | serialNumber/model/manufacturer/barcode | MECHANICAL | Plain nullable scalar columns. |
| fixedassets | FixedAsset | warrantyExpiry/insuredValue+policy, parentAssetId (components) | DESIGN | Componentisation + insurance/maintenance tracking. |
| fixedassets | AssetDisposal | buyerName/buyerId, proceeds-receipt/arInvoiceUid link, approvedBy | DESIGN | Buyer capture + approvals (no approvals wiring in module). |
| fixedassets | AssetRevaluation | valuerName/valuationRef, approvedBy | DESIGN | Valuation provenance + approval (reserve-vs-P&L split is ADR-deferred). |
| crm | Lead | estimatedValue, nextFollowUpDate, industry/region | MECHANICAL | Basic lead-qualification scalars (rating/score is ADR-deferred). |
| crm | Opportunity | nextStep/nextActionDate, stageChangedAt | MECHANICAL | Enables 'days in current stage' funnel ageing. |
| crm | Opportunity | lossReason as enum (X2) | MECHANICAL | Free-text VARCHAR today; enum + CHECK swap. |
| crm | Activity | outcome/result, reminderAt, durationMinutes | MECHANICAL | Simple nullable scalar adds. |
| crm | Activity | participants | DESIGN | One-to-many → new activity_participants child table. |
| projects | Project | actualStartDate / actualEndDate | MECHANICAL | Two nullable DATE columns (planned dates exist). |
| projects | ProjectTask | plannedStartDate / plannedEndDate | MECHANICAL | Bare task dates (scheduling machinery is deferred). |
| projects | ProjectTask | actualHours | MECHANICAL | Nullable col (or documented derive-from-timesheets). |
| projects | ProjectTask | assigneeUserId | MECHANICAL | Nullable FK → app_users. |
| projects | ProjectTimesheet | overtimeHours | MECHANICAL | Plain additive nullable column. |
| manufacturing | WorkOrder | salesOrderUid (make-to-order link) | DESIGN | Scalar uid vs FK, single vs many — small modelling call. |
| manufacturing | WorkOrder | targetLocationId (FG receipt destination) | MECHANICAL | Nullable FK → stock_locations; receipt call already takes a locationId. |
| manufacturing | WorkOrderComponent | batchId/serialNo issued (X19) | DESIGN | Component-issue lot traceability; carrier decision (line vs movement ledger). |
| manufacturing | WorkOrderComponent | returnedQty / scrapQty | MECHANICAL | Additive NUMERIC columns (partial return vs full reversal). |
| manufacturing | WorkOrderComponent | unitId (UoM for plannedQty/issuedQty) | MECHANICAL | FK → units_of_measure + name snapshot (X16). |
| manufacturing | Bom (products) | bomType/routingId/std-cost rollup snapshot | DESIGN | routingId blocked by missing routing/work-centre master; bomType relates to X16. |
| manufacturing | BomComponent (products) | unitId for qtyPer, operationSeq, optional/substitute (X16) | DESIGN | UoM/operation link + alternates modelling. |
| manufacturing | ProductComponent vs Bom (X16) | authoritative-composition clarification | DESIGN | Reconcile kit (ProductComponent) vs manufacturing (Bom) overlap. |
| manufacturing | WorkOrder/Component (X19) | PRODUCTION_ISSUE/RECEIPT carry no batch/serial/lot | DESIGN | Movement-ledger X19 fix is prerequisite for production lot traceability. |
| budgeting | Budget | budgetType (operating/capital/cash) | DESIGN | Enum + modelling decision; never mentioned in ADR-0034. |
| budgeting | Budget | branchId (branch scoping) | DESIGN | Changes uniqueness/single-active-version invariants (two partial unique indexes). |
| budgeting | BudgetLine | quantity (non-monetary budgets) | DESIGN | Needs UoM/quantity modelling. |
| approvals | ApprovalPolicy | effectiveFrom / effectiveTo | MECHANICAL | Two date columns + overlap-validity tweak. |
| approvals | ApprovalPolicy | priority (overlapping-policy precedence) | DESIGN | Only needed if same-specificity overlaps allowed; changes match function. |
| approvals | ApprovalRequest | currentStepSequence | MECHANICAL | Denormalised listing convenience (derived lookup exists). |
| iam | Organisation | contact / address | DESIGN | Tenant-root contact details (child table or embedded block). |
| iam | Organisation | subscription / plan | DESIGN | SaaS tenant-admin scope (plan tier, limits, billing). |
| iam | Company | address / contact | DESIGN | Statutory-document address/contact for a legal entity. |
| iam | Company | vrn (VAT registration number) | MECHANICAL | Separate from tax_id; simple column. |
| iam | Company | logoRef | MECHANICAL | Company-level branding ref (partial overlap with DocumentBranding). |
| iam | Company | fiscalYearStartMonth | MECHANICAL | Doc-default convenience (FiscalYear.startMonth exists; arguably redundant). |
| iam | Branch | address / contact | DESIGN | Physical-location address/contact. |
| iam | Branch | managerId | MECHANICAL | Scalar FK to app_users/employee. |
| iam | Branch | branchType | MECHANICAL | Typed enum/String classification. |
| iam | AppUser | MFA (mfaEnabled/mfaSecret) (X31) | DESIGN | TOTP secret + recovery codes + enrolment state (possibly child table). |
| iam | AppUser | mustChangePassword | MECHANICAL | Simple boolean flag. |
| iam | AppUser | passwordExpiresAt | MECHANICAL | Simple timestamp. |
| iam | AppUser | lastLoginIp | MECHANICAL | Convenience denormalisation (IP captured in audit_logs). |
| iam | AppUser | employeeId (HR linkage) | MECHANICAL | Scalar cross-module link to HR employee master. |
| iam | RefreshToken | deviceInfo/userAgent/ipAddress, lastUsedAt | MECHANICAL | Session/device-management columns. |
| iam | (X30) | audit columns entity-mapping (Org/Company/Branch/AppUser) | MECHANICAL | Columns exist in V1; expose on entities (add fields or shared Auditable superclass). |
| products | (X11) | Product tracking-flag entity-field sync (lotTracked/serialTracked) | MECHANICAL | Columns shipped in V35; Product.java lacks the @Column fields. |

### P3

| Module | Entity | Attribute | Kind | Note |
|---|---|---|---|---|
| gl | ChartOfAccount | defaultTaxCode, effectiveFrom/To, description, level/isLeaf | MECHANICAL | Convenience/reporting columns (level/isLeaf derivable from parent_id). |
| gl | FiscalYear | name, explicit adjustment-period concept | MECHANICAL | name simple; adjustment-period is a minor P3 note (true 13th period would be DESIGN). |
| gl | FiscalPeriod | name ("Jan 2026"), reopen audit | MECHANICAL | Same reopen pattern as FiscalYear. |
| gl | JournalBatch | batch-level reversal linkage | MECHANICAL | Nullable self-FK/uid (entry-level reversal exists). |
| gl | JournalEntry | entryType, valueDate, external ref, attachment link | MECHANICAL | Classification/convenience columns. |
| gl | JournalLine | statistical quantity/uom, GL reconciliation marker | MECHANICAL | Quantity postings + cleared marker columns. |
| ar | ArInvoice | net/VAT split, glControlAccountId (multi-control) | DESIGN | Tax-on-payment + multi-control unmodelled (single AR control today). |
| ar | ArReceipt | banking/cash-up batch id, payer name | DESIGN | Cash-up/banking-batch workflow unmodelled. |
| ar | ArWriteOff | fxRate / base amount | MECHANICAL | Base relievable off ar_invoices.base_outstanding_amount; additive cols. |
| ap | SupplierBillLine | uom, lineDiscountAmount, assetId/capitalization flag | MECHANICAL | uom/discount simple; assetId leans to FA integration but low priority. |
| ap | ApDebitNote | link to PurchaseReturn (typed), line dimensions | MECHANICAL | origin carries the ref today; structured-ref col + dimension FKs. |
| ap | BillMatch | poLineUid/grLineUid, accrual/GRNI entry link, currency | MECHANICAL | Audit-convenience columns. |
| cashbank | CashBankAccount | MasterStatus enum instead of boolean active (X3) | MECHANICAL | Consistency gap; enum swap if platform standardises X3. |
| cashbank | CashTransaction | counterparty name / reversal linkage | MECHANICAL | reversalOf pointer + counterparty (runningBalance is by design). |
| fx | Currency | status as MasterStatus (vs boolean+String) (X3/X2) | MECHANICAL | Carries both boolean active and String status; enum swap. |
| fx | FxRevaluationRunLine | sourceType as enum (X2) | MECHANICAL | DB CHECK present; trivial enum swap. |
| sales | QuotationLine | promotionId (X15) | DESIGN | Line→promotion provenance uniformly across sales lines. |
| sales | QuotationLine | costAmount/marginPreview | DESIGN | Cost snapshot + costing-source decision at quote time. |
| sales | SalesReturnLine | vatStatus as enum (X17/X2) | MECHANICAL | Plain String today; siblings use @Enumerated VatStatus. |
| sales | SalesReturnLine | batchId/serialNo, restocked flag | DESIGN | Restock traceability (lot/serial sub-model, X19). |
| sales | PosTill | defaultPriceListId, device/terminal id | MECHANICAL | Nullable FK + nullable VARCHAR. |
| sales | BlanketOrder | price-protection/fixed-price flag, min/max release qty | DESIGN | Frame-contract terms; min/max also belongs on the line. |
| parties | PartyBase | website, notes, imageUrl | MECHANICAL | Nullable profile-completeness columns. |
| parties | Customer | creditRating, onboardingDate, loyalty | MECHANICAL | CRM nice-to-have columns. |
| parties | Supplier | our creditLimit, priceListId | MECHANICAL | Purchasing-terms columns (priceListId soft FK). |
| products | Product | imageUrl, salesUnit/purchaseUnit defaults, notes | MECHANICAL | Default sales/purchase unit FKs + image + notes. |
| products | UnitOfMeasure | symbol | MECHANICAL | Plain display column. |
| products | ProductBranch | branch-level overrides (active, reorder, price) | DESIGN | Turns pure junction into a richer association. |
| products | CustomerPrice | minQty, priceListId link | MECHANICAL | Volume break + price-list provenance FK. |
| fixedassets | AssetCategory | parentCategoryId (hierarchy) | DESIGN | Flat-vs-tree category model decision. |
| fixedassets | DepreciationRun | executedBy; reversal (reversedAt/reversalOfRunUid) | DESIGN | executedBy mechanical; run reversal needs lifecycle/posting decision. |
| crm | PipelineStage | isWonStage/isLostStage/stageType | DESIGN | Must reconcile with orthogonal Opportunity.opportunity_status. |
| hr | Employee | middleName, photo, dependents child table | DESIGN | middleName/photo mechanical; dependents is a new child table. |
| hr | EmployeeRecurringItem | isPercent flag, cap/maxAmount, note | MECHANICAL | Partly redundant (PayComponent.basis disambiguates); cap/note simple. |
| hr | Payslip | more YTD, generated-PDF documentUid, deliveredAt/emailedAt | MECHANICAL | Additive columns (documentUid references documents module by uid). |
| projects | (none) | — | — | (all remaining projects rows are P2 or DELIBERATE_ADR) |
| costing | Dimension | requireOnAccountTypes | DESIGN | Conditional-mandatory-by-account-type extends is_mandatory enforcement. |
| costing | Dimension | description | MECHANICAL | Single nullable text column. |
| costing | DimensionValue | effectiveFrom/effectiveTo | DESIGN | Time-boxing interacts with is_active gate + immutable journal_lines tags. |
| costing | DimensionValue | managerId | MECHANICAL | Nullable scalar owner reference. |
| notifications | Notification | expiresAt (TTL / auto-expiry) | MECHANICAL | Single nullable TIMESTAMPTZ; not in ADR-0024 deferred list. |
| routes | RouteBranch/RouteAgent/RouteCustomer | junction active / unassignedAt | MECHANICAL | Nullable boolean + timestamp; needs unique-constraint review for re-add. |
| iam | Role | roleScope | MECHANICAL | Roles org-wide by ADR-0001 D-A; scope-typing field absent. |
| iam | UserRole | expiresAt (time-limited grants) | MECHANICAL | Auto-expiring temporary access. |
| iam | UserBranch | revokedAt / active (soft-delete) | MECHANICAL | Unassignment is hard delete today. |
| iam | Role | audit columns entity-mapping (X30) | MECHANICAL | Columns exist in V1; expose on Role.java. |

---

## Design items needing a mini-ADR

These are the DESIGN-kind genuine gaps. They introduce a new concept, table, constraint change, or posting/workflow rule and should not be built without a short ratifying decision. Consolidated and clustered by theme (cross-references in brackets):

**A. Subledger / control-account & GL drill-down**
- GL ChartOfAccount: isControlAccount / controlType (block-manual-posting + reconcile-to-subledger).
- GL JournalLine: subledger key (partyType/partyId) — interacts with A above.
- GL ChartOfAccount: financial-statement mapping / reportingGroup / cash-flow class (reporting taxonomy).
- GL ChartOfAccount: dimension-requirement flags (per-account require CostCentre/Dept/Project at posting).

**B. GL configuration & period control**
- GL GlConfig: branchId override (changes unique key + poster fallback) [P1].
- GL GlConfig: effective dating (changes unique constraint + as-of resolution).
- GL FiscalPeriod: module-level soft-close (per-module lock matrix + poster enforcement).
- GL JournalEntry: DRAFT lifecycle (conflicts with append-only post-only; ties to approvals).

**C. Payment terms & settlement discounts (recurring across modules)**
- A PaymentTerms master is the shared blocker for: Quotation/SalesOrder/SalesInvoice.paymentTermsId, ArInvoice.paymentTermsId + settlement discount, SupplierBill/PurchaseOrder.paymentTermsId, ArReceiptAllocation/ApPaymentAllocation discount+writeOff. Decide the master once, then the FKs become mechanical.

**D. Parties contacts/addresses & credit control (X9 + credit-control) [P1]**
- PartyBase: child contacts + addresses tables (bill-to/ship-to) — root cause of all sales shipTo/billTo gaps.
- Customer/Sales: creditStatus/onHold/credit-block flag + stop-supply at SO confirmation.

**E. Supplier master enrichment (X10) [P1 bank-accounts]**
- Supplier: bank account details / supplier_bank_account child table (also satisfies SupplierBill.supplierBankAccountId).
- Supplier: default whtTypeId (ties to WHT wiring), defaultCurrency, leadTime, minOrderValue.

**F. Withholding-tax end-to-end (X5) [P1]**
- SupplierBill: whtTypeId/whtAmount (declare at bill entry; snapshot rate, payable/receivable scope).
- ApPayment: whtAmount on the payment header.
- WhtTransaction: TIN + remittance tracking loop (remitted/ref/period).

**G. AP / cash instruments & lifecycle**
- ApPayment: chequeId link + status (void/cleared) + paymentRunId [chequeId P1].
- ApPayment: unallocatedAmount / on-account prepayment workflow (X8) [P1].
- Cashbank Cheque: inbound (received) cheque direction + banking + bounce/dishonour lifecycle [P1].
- Cashbank CashTransfer: bank-fee leg + in-transit status (fee GL routing).
- Cashbank CashBankAccount: glClearingAccountId (undeposited/in-transit second GL leg).
- AR ArReceipt: bounce/reversal for dishonoured cheques + tender instrument link.
- AR ArCreditNote: outstanding/applied tracking + allocation table + status/FX [P1 allocation].
- AP ApDebitNote: outstanding/applied tracking + status/FX (credit-note parity).
- AP BillMatch: split price/qty match status + matchType (2/3-way) + variance reason.

**H. Line-level VAT / tax & GL routing on documents**
- SupplierBillLine: line-level VAT + glAccountId for non-stock lines [P1].
- PurchaseOrder / PurchaseOrderLine: tax breakdown (X18 — AP/VAT round shipped, PO commitment side still open).
- PurchaseOrderLine: service-line glAccountId + line dimensions (X22).
- VatReturn: amendment lifecycle + penalty/interest.
- TaxRate: effectiveFrom/To rate history + date-based snapshot resolution.

**I. FX revaluation depth**
- FxRevaluationRunLine: per-open-item drill-down child table.
- CurrencyRate: effectiveTo closed range (changes as-of lookup semantics).

**J. Inventory traceability, locations & ATP (X19, X20, X23)**
- WorkOrderComponent / StockMovement (X19): lot/serial on production issue/receipt — movement-ledger carrier decision is the prerequisite.
- StockOnHand / Purchases (X20): on-order/incoming qty for true ATP (spans stock+purchases).
- StockLocation: hierarchy (parentLocationId) + allowNegative + pickable/sellable + per-location GL.
- StockTransferLine: qtyDispatched vs qtyReceived (partial-receipt model).
- StockCount/StockCountLine: SoD actors + cycle-scope filter + recount/second-count workflow.
- DeliveryLine / SalesReturnLine: picking & restock lot/serial/bin (X19).
- PO/PO-line receiving stockLocationId (X23 — stock entities already carry location_id).

**K. Pricing & promotions (X13, X15)**
- PriceList: currency/validity/tax-inclusive/default/scope (pricing-resolution semantics).
- Promotion: customer/branch scope + thresholds + usageLimit/coupon + combinable (+ usage-tracking table).
- Promotion provenance (X15): a uniform line→promotionId link across all sales lines (Quotation/SO/SI/Return lines).
- Customer: default priceListId/priceTierId (cross-module pricing FK).
- SalesInvoiceLine: costAmount/COGS snapshot (which basis, when stamped) — also Quotation margin preview.

**L. BOM / manufacturing composition (X16)**
- BomComponent: unitId for qtyPer + operationSeq + optional/substitute alternates.
- Bom: bomType + routingId (blocked by missing routing/work-centre master).
- ProductComponent vs Bom: reconcile kit-vs-manufacturing overlap (documentation/modelling).
- WorkOrder: salesOrderUid (make-to-order demand link).

**M. HR org structure, policy & disbursement (X24-X29)**
- Employee: contact details (X24) + bank/payment disbursement (X25) — changes the single-transfer model [both P1].
- Department: org hierarchy + manager + costCentreValueId + branchId (X26).
- LeaveType + LeaveBalance: carry-forward/accrual/eligibility policy (design together).
- EmployeeLoan: interest schedule + loanType (+ schedule child).
- PayComponent: granular WCF/SDL applicability + formula/computed components.
- PayeBandSet: resident-vs-non-resident scope (X29).

**N. Approvals wiring into sensitive actions**
- AR ArWriteOff, FA AssetDisposal/AssetRevaluation, HR EmployeeLoan/LeaveRequest: approvedBy / approvalRequestUid wiring to the ADR-0022 engine (the engine exists; these consumers aren't wired).
- ApprovalPolicy: priority (only if same-specificity overlaps are ever allowed).

**O. Fixed assets register depth**
- FixedAsset: custodianId, componentisation (parentAssetId), insurance/maintenance (warrantyExpiry/insuredValue).
- AssetDisposal: buyer identity + proceeds-receipt link.
- AssetRevaluation: valuation provenance.
- AssetCategory: parentCategoryId hierarchy; DepreciationRun reversal lifecycle.

**P. Budgeting, costing & CRM structure**
- Budget: budgetType (operating/capital/cash) + branchId scoping (changes uniqueness invariants).
- BudgetLine: quantity (non-monetary budgets).
- Dimension: requireOnAccountTypes; DimensionValue: effectiveFrom/To (interacts with is_active + immutable tags).
- CRM PipelineStage: isWon/isLost/stageType (reconcile with orthogonal opportunity_status).
- CRM Activity: participants child table.

**Q. IAM tenant & security**
- AppUser: MFA model (X31 — TOTP secret + recovery codes + enrolment).
- Organisation: subscription/plan (SaaS tenant-admin) + contact/address.
- Company/Branch: address/contact blocks (statutory/physical-location modelling).
