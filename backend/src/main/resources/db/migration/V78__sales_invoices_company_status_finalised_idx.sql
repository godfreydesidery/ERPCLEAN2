-- Performance index for the BI "Sales by branch" aggregate and the finalised-sales-in-period reads.
-- Hot path:  WHERE company_id = ? AND status = 'FINALISED'
--              AND finalised_at >= ? AND finalised_at < ?   GROUP BY branch_id
-- The (company_id, status) equality + finalised_at range is served directly by this composite.
--
-- Plain (transactional) CREATE INDEX — additive and idempotent (IF NOT EXISTS); no data change.
-- Chosen over CREATE INDEX CONCURRENTLY because this repo has no non-transactional migration
-- pattern and the table is not large enough to need a lock-free build; a plain build takes only a
-- brief share lock. If sales_invoices grows very large, switch to a CONCURRENTLY build in its own
-- non-transactional migration.
--
-- Note: this is a left-prefix superset of the existing ix_sales_invoices_status (company_id, status)
-- from V5, which it makes redundant. Dropping that older index is left to a separate, deliberate
-- decision (not done here to avoid removing a pre-existing index as a side effect).
CREATE INDEX IF NOT EXISTS ix_sales_invoices_company_status_finalised
    ON sales_invoices (company_id, status, finalised_at);
