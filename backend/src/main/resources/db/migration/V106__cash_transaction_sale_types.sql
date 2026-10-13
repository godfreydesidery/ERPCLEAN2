-- ###########################################################################
-- ## V106 — cash book: sale, sale-refund, POS payout and POS variance types.
-- ## Gap review wave 3 (ARC-08 / ARC-01), owner-approved 2026-10-10.
-- ##
-- ## Widens chk_cash_transaction_type (V13) so the cash book can carry the cash
-- ## side of sales (SALE_TENDER), sale voids (SALE_REFUND), POS till payouts /
-- ## expenses (POS_PAYOUT) and POS session over/short (POS_VARIANCE). Each row
-- ## mirrors a GL entry those flows already post. Additive only: every existing
-- ## value stays allowed and no existing row is touched or backfilled.
-- ###########################################################################

-- Flyway wraps this migration in one transaction. Take the table lock ONCE, up front, so the
-- DROP and ADD of the CHECK happen atomically under a single ACCESS EXCLUSIVE lock (no insert can
-- slip between them) and a long-running transaction makes this fail fast instead of queueing
-- every other writer behind it. lock_timeout bounds each statement; retry the deploy if it trips.
SET LOCAL lock_timeout = '10s';
LOCK TABLE cash_transactions IN ACCESS EXCLUSIVE MODE;
ALTER TABLE cash_transactions DROP CONSTRAINT chk_cash_transaction_type;
ALTER TABLE cash_transactions ADD CONSTRAINT chk_cash_transaction_type CHECK (txn_type IN (
    'AR_RECEIPT','AP_PAYMENT','TRANSFER_IN','TRANSFER_OUT','DIRECT_ENTRY',
    'SALE_TENDER','SALE_REFUND','POS_PAYOUT','POS_VARIANCE'));
