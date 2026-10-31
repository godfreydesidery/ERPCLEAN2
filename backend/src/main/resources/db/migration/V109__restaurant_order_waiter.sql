-- V109 — the waiter now holding a restaurant order (ADR-0067, owner-approved 2026-10-31).
--
-- A table belongs to the waiter serving it; a manager may transfer it to another waiter (shift
-- change, a break, a section reassigned). opened_by stays who opened it, for audit; served_by is
-- who holds it since the last transfer. NULL = still the waiter who opened it, so existing orders
-- need no backfill and nothing is rewritten.

-- Fail fast rather than queue every till behind a held lock (convention, CI rule 3): the foreign
-- key below locks app_users and restaurant_orders.
SET LOCAL lock_timeout = '10s';

ALTER TABLE restaurant_orders ADD COLUMN served_by    BIGINT;
ALTER TABLE restaurant_orders ADD COLUMN served_since TIMESTAMPTZ;
ALTER TABLE restaurant_orders
    ADD CONSTRAINT fk_restaurant_order_served_by
    FOREIGN KEY (served_by) REFERENCES app_users(id);
