-- V112 — free-text customer name on a restaurant order, printed on the bill.
SET LOCAL lock_timeout = '10s';

ALTER TABLE restaurant_orders ADD COLUMN customer_name VARCHAR(120);
