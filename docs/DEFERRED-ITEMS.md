# Deferred engineering items

Items consciously deferred (owner-approved) to be addressed on a later day. Each needs a design
decision (ADR) and/or a schema migration, so they are tracked here rather than rushed.

> Process: before implementing any of these, present the proposed approach + DDL + version number
> to the owner for approval (per the migration-approval standing rule), and write the ADR.

## Migration status (updated 2026-07-04)

**ALL deferred items (D-1…D-8) are BUILT and deployed to QA.** The 5 schema-bound items shipped
first (build order D-4 → D-1 → D-6 → D-7 → D-8, migrations `V79`–`V85`); the 3 code-only items
(D-2, D-3, D-5) followed in one **no-schema** PR (#205 → develop, #206 develop → main). The
`proposed-migrations/` folder is empty. QA (`main`) is live at the latest with no schema change.

| Item | Migration? | Version | State |
|------|-----------|---------|-------|
| D-1 · multi-unit pricing | yes (2) | `V80`, `V81` | ✅ **built** (ADR-0048, PR #198) |
| D-2 · GRN batch/serial reversal | **no** | — | ✅ **built** (PR #205) — payload-enriched symmetric batch + serial reversal on void |
| D-3 · requisition Convert | **no** | — | ✅ **built** (PR #205) — Convert creates the real RFQ/PO, prompts + validates supplier, sets `converted_to_uid` |
| D-4 · SO auto-threshold | yes (1) | `V79` | ✅ **built** (PR #192) |
| D-5 · default agent to user | **no** | — | ✅ **built** (PR #205) — `GET /agents/mine` + SO/invoice create-form pre-select |
| D-6 · EFD/fiscal receipt | yes (1) | `V82` | ✅ **built** (ADR-0049, PR #199) |
| D-7 · cash count + petty cash | yes (2) | `V83`, `V84` | ✅ **built** (ADR-0050, PR #200 + #201) |
| D-8 · van reconciliation | yes (1) | `V85` | ✅ **built** (ADR-0051, PR #202) — record-only worksheet; `VAN` location type already existed |

**Nothing open — all 8 deferred items are built, on `main`, and deployed to QA.**

---

## D-1 · Per-pack / multi-unit pricing (weight/volume goods)

**Deferred:** 2026-06-24 (owner). **Effort:** L. **Needs:** ADR + migration.

**The gap.** A product can have only ONE price per price list — `product_prices` is
`UNIQUE(product_id, price_list_id)` with no unit/pack column. Units of measure already support
dimensions (COUNT/WEIGHT/VOLUME/LENGTH/TIME) and `product_bulk_packs` already model multiple pack
sizes over a base unit via `factor_to_base`, but pricing is strictly **linear** in base units. So
you cannot price, e.g., a chocolate sold by grams where a 500 g pack = 5,000 and a 1,000 g pack =
6,000 (non-linear) — the 1,000 g pack is always exactly 2× the per-gram list price.

**Way forward (outline).**
- Add a nullable `unit_id` (or `bulk_pack_id`) to `product_prices`; widen the unique key to
  `(product_id, price_list_id, unit_id)`, where `NULL unit_id` = the existing per-base-unit price
  (back-compat).
- `PriceResolutionServiceImpl` resolves price by product + price list + selected unit, falling back
  to the base-unit row × factor when no pack-specific row exists.
- Thread the selected unit into price resolution across sales order / invoice / quotation / POS.
- Secondary: fix the UoM seeders — GRAM/KG/LITRE/ML are seeded with the COUNT default dimension and
  `decimal_places = 0`; they should be WEIGHT/VOLUME with sensible decimals/`is_fractional`.

**Key files:** `ProductPrice` + `product_prices` (V3), `PriceResolutionServiceImpl`,
`SalesOrderServiceImpl.resolveListPrice`/`appliedPrice` (+ invoice/quotation/POS equivalents),
`UnitOfMeasureSeeder` / `V4__units_of_measure.sql`.

---

## D-2 · Batch / serial(IMEI) reversal on GRN void

**Deferred:** 2026-06-24 (from the V76 GRN-tracking work). **Effort:** M. **Needs:** ADR.
**Status (2026-07-04): ✅ BUILT (PR #205), no schema change, on QA.**

**The gap.** Goods receipt now writes `stock_batches` (lot/expiry) and `stock_serials` (per-unit
IMEI) on receipt (V76). Voiding a receipt correctly reverses **quantity + GL**, but does **not**
back out those batch/serial rows — they retain their original receipt values until addressed. A
clear `TODO` is in `GoodsReceiptReversalStockHandler`.

**What shipped.** The void payload (`StockReceiptVoidedPayload.LineItem`, a DTO — **not** schema)
was enriched with `lotNumber`/`manufactureDate`/`expiryDate`/`serialNumbers`/`lotTracked`, populated
by `GoodsReceiptServiceImpl.voidReceipt`. The reversal handler now backs out both sub-ledgers
symmetrically with the forward path, soft (a tracking hiccup never poisons the qty/GL reversal TX):
- `StockBatchService.reverseReceiptQty(companyId, branchId, locationId, productId, lotNumber, qty, actorId)` — find-only, negate; reverses by the matched line's own qty (not the ledger movement) and mirrors the forward `"UNTRACKED"` sentinel for lot-tracked products received with no lot; uses the receipt-time (per-movement) location.
- `StockSerialService.removeReceived(companyId, productId, serialNumber, receiptUid)` — deletes only an `IN_STOCK` serial whose `receivedDocumentUid` matches; never touches an `ISSUED`/`RETURNED` one.

Adversarial review caught + fixed two real data-integrity bugs here (wrong lot/qty pairing on
multi-lot receipts; the `UNTRACKED` sentinel batch never being reversed). Covered by
`GoodsReceiptReversalStockHandlerTest` + the two service unit tests.

**Key files:** `GoodsReceiptReversalStockHandler`, `StockBatchServiceImpl`, `StockSerialServiceImpl`,
both `StockReceiptVoidedPayload` copies, `GoodsReceiptServiceImpl.voidReceipt`.

---

## D-3 · Requisition "Convert" that actually creates the RFQ / PO

**Deferred:** 2026-07-03 (round-2 persona interviews — Yusuf). **Effort:** M. **Needs:** design (ADR); **no migration** (confirmed).
**Status (2026-07-04): ✅ BUILT (PR #205), no schema change, on QA.**

**The gap.** The purchase-requisition **Convert** action (`RequisitionServiceImpl.convert`) sets
the requisition status to `CONVERTED` and returns a success toast, **but never creates the target
RFQ or PO** and never sets `convertedToUid` — so the "View RFQ/PO" link is a dead end. The claimed
document does not exist.

**What shipped.** `convert(uid, ConvertRequisitionRequest{targetType, supplierUids, supplierUid, currency})`
now creates the real target **in one TX**, sets `converted_to_uid` + `converted_to_type`, and returns
the created doc uid — the dead "View RFQ/PO" link now works. **RFQ** reuses `RfqService.create` with
the requisition as `sourceRequisitionUid` (lines → productId/unitId/qty, no price); **PO** uses a new
`PurchaseOrderServiceImpl.createFromRequisition` mirroring `createFromQuote` (`estimatedUnitCost` →
`unitCost` with null→ZERO + auto-note, currency defaults to company base, stamps each line's
`convertedToPoLineUid`). The requisition carries no usable supplier, so Convert **prompts** for it and
**validates** (unknown/foreign/ARCHIVED rejected on both branches). Web convert form gained the
supplier picker (RFQ multi-invite / PO single + currency). **No migration** — the RFQ/PO tables and
`purchase_requisitions.converted_to_uid`/`converted_to_type` already existed (V26/V27).

**Key files:** `PurchaseRequisitionServiceImpl.convert`, `PurchaseOrderServiceImpl.createFromRequisition`,
`RfqServiceImpl` (reused), `ConvertRequisitionRequest`, the requisition-detail web component.

---

## D-4 · Sales-order approval — automatic threshold gate

**Deferred:** 2026-07-03. **Effort:** M. **Needs:** migration (`V79`) + ADR.
**Status (2026-07-04): BUILT, pending owner review** — `V79__sales_settings.sql` + `SalesSettings` +
`SalesApprovalGate` wired into `SalesOrderServiceImpl.doConfirm` + a Sales Settings UI, on branch
`feat/sales-order-approval-threshold` (not yet committed/merged).

**The gap.** A **manual, no-schema** sales-order approval flow shipped this session (a
"Submit for approval" action routes to the generic approval engine as document type `SALES_ORDER`;
`SalesOrderDto.approvalStatus` is engine-derived on read; `confirm()` is blocked while the request
is PENDING/REJECTED). What it does **not** do is *automatically require* approval for orders above a
configurable value — submission is manual, because there is no sales-side settings/threshold table
(POs use `purchase_settings.po_approval_threshold_amount`; sales has no equivalent).

**Way forward (outline).** Mirror the PO seam: add a `sales_settings` row (or reuse a company-scoped
settings table) with `so_approval_enabled` + `so_approval_threshold_amount`, and a
`SalesApprovalGate` that auto-submits over-threshold orders at confirm time (exactly like
`PoApprovalGate.requiresApproval` / `submit`). Then confirm hard-blocks over-threshold orders until
approved instead of relying on a manual click.

**Key files:** `PoApprovalGate` (template), `SalesOrderServiceImpl.doConfirm`, new
`SalesApprovalGate` + `SalesSettings` entity/migration.

---

## D-5 · Default the sales agent to the logged-in user

**Deferred:** 2026-07-03 (round-2 persona interviews — Hamisi). **Effort:** S. **Needs:** **NO migration** — code-only.
**Status (2026-07-04): ✅ BUILT (PR #205), no schema change, on QA.**

**The gap.** Route agents want a new sales order / invoice to pre-fill the **agent = themselves**.

**What shipped.** The **backend already auto-defaulted** the agent on save when none was supplied
(SO/SI/POS resolve the caller's ACTIVE INTERNAL agent via `AgentRepository.findInternalAgentIdByCompanyAndUser`).
This item added the **UX pre-fill** so the route agent SEES agent = self (still editable): a new
`GET /api/v1/agents/mine?companyUid=` (reuses `AGENT.VIEW`; returns the caller's own agent or empty
for root / no internal agent — strictly scoped to `RequestContext.userId()`), and the SO + invoice
create forms pre-select it on form-open. `agents.app_user_id` already existed (V2), so **no migration**.

**Key files:** `AgentController` (`/mine`), `AgentServiceImpl.myAgent`, `AgentRepository`
(`findFirstBy…` finder), `SalesOrderServiceImpl`/`SalesInvoiceServiceImpl` (existing auto-default),
the SO/invoice create web forms.

---

## D-6 · EFD / fiscal receipt integration (TRA)

**Deferred:** 2026-07-03 (round-2 persona interviews — Sabina). **Effort:** L. **Needs:** ADR + migration + external device/API.

**The gap.** Counter sales need a **fiscal (EFD) receipt** issued to the tax authority (TRA VFD/EFD
in TZ). The system has no fiscalisation record or device/API integration; "EFD receipt" is a persona
expectation with no backing feature.

**Way forward (outline).** Design a fiscalisation seam (device/VFD API abstraction), persist a
`fiscal_receipt` record per invoice (fiscal number, verification URL/QR, signature, status), and a
retry/queue for offline. This is a substantial external-integration workstream — scope separately.

**Key files:** new `fiscal` concern; `SalesInvoiceServiceImpl` (post-issue hook), invoice detail
web (receipt render/print).

---

## D-7 · End-of-Day Cash Count + Petty Cash

**Deferred:** 2026-07-03 (round-2 persona interviews — John). **Effort:** M–L. **Needs:** ADR + migration.

**The gap.** The cashier has receipt posting but **no end-of-day cash-count/reconciliation** (counted
cash vs expected, over/short) and **no petty-cash** workflow (float, disbursements, replenishment).

**Way forward (outline).** Add `cash_count` (session, denominations, expected vs counted, variance →
GL) and a `petty_cash` float/disbursement model with approval + replenishment; wire both into the
cash & bank module and reporting.

**Key files:** new cash-count / petty-cash entities + migrations; cash & bank service + web screens.

---

## D-8 · Van-stock reconciliation (route sales)

**Deferred:** 2026-07-03 (round-2 persona interviews — Hamisi). **Effort:** L. **Needs:** ADR + migration (`V85`, reduced).

**The gap.** Route agents load stock onto a van, sell off it on the round, and must **reconcile van
stock** (loaded − sold − returned = on-van) at day end. There is no reconciliation flow.

**Correction (2026-07-04):** `stock_locations.location_type` **already includes `VAN`**, so no
location-type migration is needed — the only new schema (proposed `V85`) is the reconciliation
session table (+lines) and an optional `stock_locations.agent_id` link.

**Way forward (outline).** Use a VAN stock location per agent; load = transfer to van, route sales
issue from the van location, day-end reconciliation posts variances. Integrates stock + sales + the
route module.

**Key files:** stock location model (van type), `StockTransfer*`, route-sales issue path, new
van-reconciliation service + web screen.

---

## D-9 · The installer's password lottery — 1 fresh install in 33 hangs for 15 minutes

**Deferred:** 2026-08-15 (owner: "skip them, we will fix them when we have time"). **Effort:** XS.
**Needs:** nothing — two lines in two shipped scripts, plus one line of documentation. No schema, no ADR.

**The gap.** `install.sh:352-356` draws the first administrator's password as 20 characters from
`A-Za-z0-9` **with no guarantee of a digit**. `PasswordPolicy.validate` rejects a digit-free password
and `BootstrapRunner` calls it, so the application throws `WeakPasswordException` at startup and
crash-loops. `install.ps1:275-282` (`New-Secret`) uses the identical alphabet, so Windows is equally
exposed.

**Measured, not estimated:** 3,000 draws produced **91 digit-free — 3.03%**, against a theoretical
`(52/62)^20 = 2.97%`. Roughly one install in thirty-three.

**Why it is worse than a retry.** `container_health()` (`orbixerp.sh:134-142`) reports `stopped` only
when the container status is not `running` — a container crash-looping under `restart: unless-stopped`
reads as `running`, so `wait_healthy`'s fast-fail branch never fires. The install **burns the full
900 s** and dies with *"The system did not become ready within 15 minutes"*, a message that names
nothing. The schema is fully migrated and `organisations` is 0, so it is recoverable by putting a
password containing a digit in `.env` and restarting — but nothing tells the customer that.

**The documentation currently points the wrong way.** `dist/bundle/docs/TROUBLESHOOTING.md` covers
that exact message and attributes it to slow disk or insufficient memory, advising more RAM. For this
case that advice is wrong, and following it means retrying the install while the real cause sits in
the API log.

**Way forward (outline).** Make the generator guarantee at least one digit and one letter (draw, test,
redraw — or compose deterministically), in both scripts. Separately, make `container_health()` treat a
climbing restart count as unhealthy so the installer fails in seconds with the real error rather than
after fifteen minutes with none. Add the triage line to TROUBLESHOOTING.md.

**Triage rule until then.** If an install hangs 15 minutes and dies, grep the API log for
`Password must contain letters and at least one number` **before** suspecting a migration regression.

**Key files:** `dist/bundle/install.sh`, `dist/bundle/install.ps1`, `dist/bundle/orbixerp.sh`
(`container_health`), `dist/bundle/docs/TROUBLESHOOTING.md`. Full evidence:
[`docs/ops/fresh-install-rehearsal.md`](ops/fresh-install-rehearsal.md).
