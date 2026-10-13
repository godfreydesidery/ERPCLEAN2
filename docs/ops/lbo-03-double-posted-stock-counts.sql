-- =====================================================================================
-- LBO-03 — stock counts whose variance was posted to the GL twice (READ-ONLY)
-- =====================================================================================
--
-- Before the LBO-03 fix, posting a stock count wrote:
--   (a) one STOCK_ADJUSTMENT journal PER VARIANCE LINE, source_ref = the line's movement uid
--       (InventoryValuationServiceImpl.revalueAdjustment), AND
--   (b) one net STOCK_ADJUSTMENT journal for the whole count, source_ref = the count uid
--       (stock_counts.variance_gl_entry_uid).
-- (a) and (b) carry the same value, so Inventory and the stock-adjustment account moved twice.
-- From the fix on, only (b) is written. The per-line journals (a) are the duplicates.
--
-- This file only READS. It changes nothing. Repair is a MANUAL reversing journal per company,
-- entered by the accountant through the normal journal screen, using the per-account nets that
-- query 2 prints (post the opposite side of each net). Do not edit or delete posted journals:
-- GL postings are append-only.
--
-- Run against a restored copy first. Parameters: none (all companies). Add
-- "AND sc.company_id = <id>" to narrow.
-- =====================================================================================

-- 1) One row per affected count: the count's own journal and the duplicate per-line journals.
SELECT sc.company_id,
       sc.count_number,
       sc.uid                                   AS count_uid,
       sc.posted_at,
       sc.variance_gl_entry_uid                 AS count_journal_uid,
       cj.total_debit                           AS count_journal_amount,
       COUNT(DISTINCT je.id)                    AS duplicate_line_journals,
       SUM(je.total_debit)                      AS duplicate_gross_amount
FROM stock_counts sc
JOIN stock_count_lines scl ON scl.stock_count_id = sc.id
                          AND scl.movement_uid IS NOT NULL
JOIN journal_entries je    ON je.company_id  = sc.company_id
                          AND je.source_type = 'STOCK_ADJUSTMENT'
                          AND je.source_ref  = scl.movement_uid
                          AND je.reversed    = FALSE
LEFT JOIN journal_entries cj ON cj.company_id = sc.company_id
                            AND cj.uid        = sc.variance_gl_entry_uid
WHERE sc.status = 'POSTED'
GROUP BY sc.company_id, sc.count_number, sc.uid, sc.posted_at,
         sc.variance_gl_entry_uid, cj.total_debit
ORDER BY sc.company_id, sc.posted_at;

-- 2) What the reversing journal must undo, per company and account: the net of the duplicate
--    per-line journals (debit − credit). Reverse it: a positive net is CREDITED, a negative
--    net is DEBITED. Expect two accounts per company — Inventory and Stock Adjustment.
SELECT je.company_id,
       coa.account_code,
       coa.name                                         AS account_name,
       SUM(jl.debit_amount)                             AS duplicate_debits,
       SUM(jl.credit_amount)                            AS duplicate_credits,
       SUM(jl.debit_amount) - SUM(jl.credit_amount)     AS net_to_reverse
FROM stock_counts sc
JOIN stock_count_lines scl ON scl.stock_count_id = sc.id
                          AND scl.movement_uid IS NOT NULL
JOIN journal_entries je    ON je.company_id  = sc.company_id
                          AND je.source_type = 'STOCK_ADJUSTMENT'
                          AND je.source_ref  = scl.movement_uid
                          AND je.reversed    = FALSE
JOIN journal_lines jl      ON jl.entry_id = je.id
JOIN chart_of_accounts coa ON coa.id = jl.account_id
WHERE sc.status = 'POSTED'
GROUP BY je.company_id, coa.account_code, coa.name
ORDER BY je.company_id, coa.account_code;
