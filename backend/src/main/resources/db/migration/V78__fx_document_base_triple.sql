-- V78 — FX/Multi-currency: additive base-triple columns on the four source-document headers
--        + allocation-junction base-capture columns (ADR-0036 D-4, Tranche 2)
--
-- Documents that originate foreign-currency value:
--   sales_invoices  — the AR/Sales sub-ledger header
--   ar_invoices     — the AR open item (separate physical table from sales_invoices)
--   supplier_bills  — the AP sub-ledger header
--   ar_receipts     — settlement rate capture
--   ap_payments     — settlement rate capture
--   ar_receipt_allocations  — per-allocation base capture (needed by Tranche 3 realized-FX leg)
--   ap_payment_allocations  — per-allocation base capture (needed by Tranche 3 realized-FX leg)
--
-- ALL new columns:
--   - DEFAULT 1 / DEFAULT face → existing single-currency rows remain correct at rate=1 (D-8a).
--   - Back-filled immediately so no NULL rows exist after migration.
--   - fx_rate / base_* columns on headers are IMMUTABLE after stamp (BR-CUR-05);
--     base_outstanding_amount on ar_invoices / supplier_bills moves with outstanding.
--
-- Additive only. V1–V77 are FROZEN. No edit to any prior migration.

-- ============================================================================
-- (1) sales_invoices — the sales sub-ledger header (D-4)
--   fx_rate        : effective rate at finalise (units of base per 1 foreign unit)
--   base_gross_total_amount : gross total converted to base (the amount GL posts to AR)
--   rate_at        : timestamp when rate was stamped (immutable; BR-CUR-05)
-- ============================================================================
ALTER TABLE sales_invoices
    ADD COLUMN IF NOT EXISTS fx_rate               NUMERIC(19,8)  NOT NULL DEFAULT 1,
    ADD COLUMN IF NOT EXISTS base_gross_total_amount NUMERIC(19,4),
    ADD COLUMN IF NOT EXISTS rate_at               TIMESTAMPTZ;

-- Back-fill: existing rows are single-currency, base == face at rate=1
UPDATE sales_invoices
SET    base_gross_total_amount = gross_total_amount
WHERE  base_gross_total_amount IS NULL;

-- ============================================================================
-- (2) ar_invoices — the AR open item (D-4)
--   fx_rate               : rate at open-item creation (immutable)
--   base_original_amount  : original amount in base (immutable)
--   base_outstanding_amount: outstanding in base (updated when outstanding changes)
--   rate_at               : timestamp (immutable)
-- ============================================================================
ALTER TABLE ar_invoices
    ADD COLUMN IF NOT EXISTS fx_rate                 NUMERIC(19,8)  NOT NULL DEFAULT 1,
    ADD COLUMN IF NOT EXISTS base_original_amount    NUMERIC(19,4),
    ADD COLUMN IF NOT EXISTS base_outstanding_amount NUMERIC(19,4),
    ADD COLUMN IF NOT EXISTS rate_at                 TIMESTAMPTZ;

-- Back-fill: rate=1 → base == face
UPDATE ar_invoices
SET    base_original_amount    = original_amount,
       base_outstanding_amount = outstanding_amount
WHERE  base_original_amount IS NULL;

-- ============================================================================
-- (3) supplier_bills — the AP sub-ledger header (D-4)
--   fx_rate               : rate at match/post (immutable)
--   base_gross_amount     : gross in base (immutable — the amount GL posts to AP)
--   base_outstanding_amount: outstanding in base (updated when outstanding changes)
--   rate_at               : timestamp (immutable)
-- ============================================================================
ALTER TABLE supplier_bills
    ADD COLUMN IF NOT EXISTS fx_rate                 NUMERIC(19,8)  NOT NULL DEFAULT 1,
    ADD COLUMN IF NOT EXISTS base_gross_amount       NUMERIC(19,4),
    ADD COLUMN IF NOT EXISTS base_outstanding_amount NUMERIC(19,4),
    ADD COLUMN IF NOT EXISTS rate_at                 TIMESTAMPTZ;

-- Back-fill: rate=1 → base == face
UPDATE supplier_bills
SET    base_gross_amount       = gross_amount,
       base_outstanding_amount = outstanding_amount
WHERE  base_gross_amount IS NULL;

-- ============================================================================
-- (4) ar_receipts — settlement rate capture (D-4)
--   fx_rate  : rate at receipt record (immutable; settlement rate for realized-FX in Tranche 3)
--   rate_at  : timestamp (immutable)
-- ============================================================================
ALTER TABLE ar_receipts
    ADD COLUMN IF NOT EXISTS fx_rate  NUMERIC(19,8)  NOT NULL DEFAULT 1,
    ADD COLUMN IF NOT EXISTS rate_at  TIMESTAMPTZ;

-- No base amount on ar_receipts header; per-allocation capture is in ar_receipt_allocations (below).

-- ============================================================================
-- (5) ap_payments — settlement rate capture (D-4)
--   fx_rate  : rate at payment (immutable; settlement rate for realized-FX in Tranche 3)
--   rate_at  : timestamp (immutable)
-- ============================================================================
ALTER TABLE ap_payments
    ADD COLUMN IF NOT EXISTS fx_rate  NUMERIC(19,8)  NOT NULL DEFAULT 1,
    ADD COLUMN IF NOT EXISTS rate_at  TIMESTAMPTZ;

-- ============================================================================
-- (6) ar_receipt_allocations — per-allocation base capture (D-4, graft from Proposal B)
--   base_allocated_amount : amount settled in base at the settlement rate
--   settlement_rate       : the rate used for this allocation (receipt fx_rate snapshot)
-- Both immutable once written. Back-fill: rate=1 → base == face.
-- ============================================================================
ALTER TABLE ar_receipt_allocations
    ADD COLUMN IF NOT EXISTS base_allocated_amount NUMERIC(19,4),
    ADD COLUMN IF NOT EXISTS settlement_rate       NUMERIC(19,8);

-- Back-fill existing rows (single-currency; face == base at rate=1)
UPDATE ar_receipt_allocations
SET    base_allocated_amount = allocated_amount,
       settlement_rate       = 1
WHERE  base_allocated_amount IS NULL;

-- ============================================================================
-- (7) ap_payment_allocations — per-allocation base capture (D-4, graft from Proposal B)
--   base_allocated_amount : amount paid in base at the settlement rate
--   settlement_rate       : the rate used for this allocation (payment fx_rate snapshot)
-- Both immutable once written. Back-fill: rate=1 → base == face.
-- ============================================================================
ALTER TABLE ap_payment_allocations
    ADD COLUMN IF NOT EXISTS base_allocated_amount NUMERIC(19,4),
    ADD COLUMN IF NOT EXISTS settlement_rate       NUMERIC(19,8);

-- Back-fill existing rows
UPDATE ap_payment_allocations
SET    base_allocated_amount = allocated_amount,
       settlement_rate       = 1
WHERE  base_allocated_amount IS NULL;
