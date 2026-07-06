-- V88 — Case-insensitive, trimmed NAME uniqueness on reference/config masters.
--
-- Business rule: within its natural scope (per company, or the natural parent),
-- a master's human name must be unique — "iPhone 14", "IPHONE 14" and "iphone 14 "
-- are the SAME name. The label is normalised with lower(btrim(name)); these are
-- IMMUTABLE functions so they are index-safe. Uniqueness spans ALL statuses
-- (an ARCHIVED name still blocks a new active one), matching the service guards.
--
-- Pre-existing duplicates were resolved (renamed with a -N suffix) in every durable
-- environment BEFORE this migration, so the index builds cleanly. Tables are small;
-- a plain (non-CONCURRENT) unique index inside the Flyway transaction is fine.

-- ── per company ───────────────────────────────────────────────────────────────
CREATE UNIQUE INDEX uq_product_company_name_ci        ON products           (company_id, lower(btrim(name)));
CREATE UNIQUE INDEX uq_unit_company_name_ci           ON units_of_measure   (company_id, lower(btrim(name)));
CREATE UNIQUE INDEX uq_price_list_company_name_ci     ON price_lists        (company_id, lower(btrim(name)));
CREATE UNIQUE INDEX uq_coa_company_name_ci            ON chart_of_accounts  (company_id, lower(btrim(name)));
CREATE UNIQUE INDEX uq_dimension_company_name_ci      ON dimensions         (company_id, lower(btrim(name)));
CREATE UNIQUE INDEX uq_asset_category_company_name_ci ON asset_categories   (company_id, lower(btrim(name)));
CREATE UNIQUE INDEX uq_position_company_name_ci       ON positions          (company_id, lower(btrim(name)));
CREATE UNIQUE INDEX uq_leave_type_company_name_ci     ON leave_types        (company_id, lower(btrim(name)));
CREATE UNIQUE INDEX uq_pay_component_company_name_ci  ON pay_components      (company_id, lower(btrim(name)));
CREATE UNIQUE INDEX uq_promotion_company_name_ci      ON promotions         (company_id, lower(btrim(name)));
CREATE UNIQUE INDEX uq_route_company_name_ci          ON routes             (company_id, lower(btrim(name)));
CREATE UNIQUE INDEX uq_payment_term_company_name_ci   ON payment_terms      (company_id, lower(btrim(name)));
CREATE UNIQUE INDEX uq_wht_type_company_name_ci       ON wht_types          (company_id, lower(btrim(name)));
CREATE UNIQUE INDEX uq_branch_company_name_ci         ON branches           (company_id, lower(btrim(name)));
CREATE UNIQUE INDEX uq_cash_bank_company_name_ci      ON cash_bank_accounts (company_id, lower(btrim(name)));

-- ── natural parent scope ──────────────────────────────────────────────────────
CREATE UNIQUE INDEX uq_dimension_value_dim_name_ci    ON dimension_values   (dimension_id, lower(btrim(name)));
CREATE UNIQUE INDEX uq_stock_location_branch_name_ci  ON stock_locations    (company_id, branch_id, lower(btrim(name)));
