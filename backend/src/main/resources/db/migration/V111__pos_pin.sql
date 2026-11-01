-- V111 — Waiter POS PIN (4 digits, hashed). Wrong PINs reuse the existing lockout columns
-- (failed_login_attempts / locked_until). Nullable columns: no table rewrite.
SET LOCAL lock_timeout = '10s';

ALTER TABLE app_users
    ADD COLUMN pos_pin_hash   VARCHAR(100),
    ADD COLUMN pos_pin_set_at TIMESTAMPTZ;
