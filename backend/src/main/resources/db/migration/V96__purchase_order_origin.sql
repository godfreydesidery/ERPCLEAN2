-- ============================================================================
-- V96 — Provenance on a purchase order, for direct goods receipt without an LPO
--       (K3, Kilimanjaro 2026-08-08).
--
-- WHY: the client receives stock that was never ordered through the system (cash purchases, walk-in
-- supplier deliveries) and today has no way to record it as a PURCHASE. The workaround they fell into —
-- stock adjustment / bulk import — raises on-hand but records no supplier, no document and no purchase
-- cost: it values the movement at the CURRENT moving average (zero for a new product) and posts a
-- variance hit instead of DR INVENTORY / CR GRNI. Inventory ends up under-valued and cost overstated.
--
-- APPROVED DESIGN (owner, 2026-08-08): a direct receipt AUTO-CREATES a purchase order behind the scenes
-- and receives against it, rather than making goods_receipts.purchase_order_id nullable. Everything
-- downstream — 3-way match, GRNI clearing, purchase returns, outstanding tracking, PO status — keeps
-- working untouched, and the NOT NULL FKs on goods_receipts / goods_receipt_lines stay as they are.
-- The cost is that these auto-POs would otherwise clutter the purchase-order list, which is exactly
-- what this column exists to prevent.
--
--   MANUAL         — raised by a person through the normal requisition/PO flow (every existing row).
--   DIRECT_RECEIPT — synthesised by the direct-receipt service; hidden from the PO list by default.
--
-- Metadata-only ADD COLUMN ... DEFAULT on the populated purchase_orders table — additive, no backfill
-- needed, safe on Postgres 11+. The CHECK is added as a second statement so it validates against rows
-- that already carry the default; every existing row is 'MANUAL', so it validates instantly.
--
-- NOTE: this does NOT change the PO approval threshold. Whether a direct receipt may bypass spend
-- approval is a separate owner decision; until it is taken, the direct-receipt service applies the same
-- threshold rules as any other PO.
-- ============================================================================

ALTER TABLE purchase_orders
    ADD COLUMN origin VARCHAR(16) NOT NULL DEFAULT 'MANUAL';

ALTER TABLE purchase_orders
    ADD CONSTRAINT chk_purchase_order_origin
    CHECK (origin IN ('MANUAL','DIRECT_RECEIPT'));
