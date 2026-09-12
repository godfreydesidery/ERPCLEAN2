-- ============================================================================
-- DRAFT — NOT ACTIVE. Awaiting owner approval (migration-approval rule).
--
-- Kilimanjaro, 2026-09-12, issue #3:
--   "Receiving goods inajiongeza VAT tena wakati wa printing causing confusion."
--
-- WHAT IS ACTUALLY WRONG
-- GoodsReceiptPrintQuery takes each line's stored cost as VAT-EXCLUSIVE, always:
--     net   = SUM(line amounts)
--     vat   = net x rate          (per VAT band, from tax_rates x product vat_status)
--     total = net + vat
-- There is no way to tell it otherwise. The sales side has price_lists.price_includes_vat;
-- the purchase side has no equivalent. So a storekeeper typing the cost off a supplier
-- invoice that already includes VAT gets 18% added on top of a figure that already has it.
--
-- SCOPE OF THE DAMAGE: the printed note only. The goods receipt posts NO VAT — purchase VAT
-- is posted from the supplier bill — so the ledger is unaffected. This is a document defect.
--
-- WHAT THIS MIGRATION DOES
-- Adds one column to purchase_settings (one row per company) saying how the costs entered on
-- this company's receipts should be read. Three values, not a boolean, because "we are not
-- VAT registered" is a different answer from "our costs include it":
--
--   EXCLUSIVE  costs are net of VAT; the note adds VAT on top       <- today's behaviour
--   INCLUSIVE  costs already contain VAT; the note EXTRACTS it:
--                net = amount / (1 + rate), vat = amount - net, total = amount
--   NONE       show no VAT band at all; net = total
--
-- Defaulted to EXCLUSIVE, so every existing company behaves exactly as it does today and
-- nothing changes until somebody chooses otherwise.
--
-- SAFE ON A POPULATED TABLE: PostgreSQL 11+ adds a NOT NULL column with a constant DEFAULT
-- without rewriting the table. purchase_settings holds one row per company (single digits).
-- ============================================================================

ALTER TABLE purchase_settings
    ADD COLUMN purchase_vat_treatment VARCHAR(20) NOT NULL DEFAULT 'EXCLUSIVE';

ALTER TABLE purchase_settings
    ADD CONSTRAINT chk_purchase_settings_vat_treatment
    CHECK (purchase_vat_treatment IN ('EXCLUSIVE', 'INCLUSIVE', 'NONE'));

COMMENT ON COLUMN purchase_settings.purchase_vat_treatment IS
    'How unit costs entered on goods receipts are read for the PRINTED note only: EXCLUSIVE adds '
    'VAT on top, INCLUSIVE extracts it from the amount, NONE shows no VAT band. Never affects '
    'posting - the receipt posts no VAT; purchase VAT comes from the supplier bill.';
