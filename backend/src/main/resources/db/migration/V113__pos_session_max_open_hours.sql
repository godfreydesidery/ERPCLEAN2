-- ============================================================================
-- Per-company limit on how long a till session may stay open and still take sales
-- (owner decision 2026-11-07, approved DDL).
--
-- PosSaleServiceImpl refuses a sale on a session opened longer ago than the limit, so a Z-read
-- covers one trading day. Until now the limit was server-wide only
-- (erp.pos.session.max-open-age, default 36 h). Cafe Tulia hit it at the pay screen with a
-- customer waiting.
--
--   NULL (default) = use the server setting — every existing company keeps today's behaviour,
--                    so no backfill is needed.
--   0              = no limit: a session may stay open indefinitely (owner decision 2026-11-07 —
--                    some businesses prefer it; the trade-off is a Z-read spanning several days).
--                    Same meaning as zero in the server setting.
--   12..168        = this company's own limit, in hours (half a day to a week).
--
-- sales_settings has one row per company, so the ADD COLUMN and its CHECK validation are instant;
-- the lock timeout still applies by convention (CI rule 3).
-- ============================================================================

SET LOCAL lock_timeout = '10s';

ALTER TABLE sales_settings
    ADD COLUMN pos_session_max_open_hours INTEGER NULL
        CONSTRAINT ck_sales_settings_pos_session_max_open_hours
        CHECK (pos_session_max_open_hours IS NULL
               OR pos_session_max_open_hours = 0
               OR pos_session_max_open_hours BETWEEN 12 AND 168);
