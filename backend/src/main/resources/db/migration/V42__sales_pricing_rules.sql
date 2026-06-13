-- V42 — Sales Depth: Advanced Pricing Rules (ADR-0029, Stage 1a — products module)
-- price_tiers, customer_prices, promotions;
-- ALTER sales_invoice_lines + sales_order_lines ADD price_source;
-- permission seed + ORG_ADMIN grant: SALES.PRICING.RULE.VIEW / MANAGE.
-- Additive only. V1–V19 + V20–V41 are FROZEN.

-- ============================================================================
-- (1) price_tiers — quantity-break tiers on a (product, price_list) price
-- ============================================================================
CREATE TABLE price_tiers (
    id                   BIGSERIAL PRIMARY KEY,
    uid                  VARCHAR(26)     NOT NULL,
    company_id           BIGINT          NOT NULL,
    product_id           BIGINT          NOT NULL,
    price_list_id        BIGINT          NOT NULL,
    min_qty              NUMERIC(19,6)   NOT NULL,
    unit_price_amount    NUMERIC(19,4)   NOT NULL,
    currency             VARCHAR(3)      NOT NULL,
    status               VARCHAR(20)     NOT NULL DEFAULT 'ACTIVE',
    version              BIGINT          NOT NULL DEFAULT 0,
    created_at           TIMESTAMPTZ     NOT NULL DEFAULT now(),
    created_by           BIGINT,
    updated_at           TIMESTAMPTZ,
    updated_by           BIGINT,
    CONSTRAINT uq_price_tier_uid          UNIQUE (uid),
    CONSTRAINT uq_price_tier_break        UNIQUE (product_id, price_list_id, min_qty),
    CONSTRAINT fk_price_tier_company      FOREIGN KEY (company_id)    REFERENCES companies(id),
    CONSTRAINT fk_price_tier_product      FOREIGN KEY (product_id)    REFERENCES products(id),
    CONSTRAINT fk_price_tier_pricelist    FOREIGN KEY (price_list_id) REFERENCES price_lists(id),
    CONSTRAINT chk_price_tier_min_qty     CHECK (min_qty > 0),
    CONSTRAINT chk_price_tier_price       CHECK (unit_price_amount >= 0),
    CONSTRAINT chk_price_tier_status      CHECK (status IN ('ACTIVE','INACTIVE','ARCHIVED'))
);

CREATE INDEX ix_price_tiers_company_id ON price_tiers (company_id);
CREATE INDEX ix_price_tiers_lookup     ON price_tiers (product_id, price_list_id, min_qty);

-- ============================================================================
-- (2) customer_prices — contract price for (customer, product)
-- ============================================================================
CREATE TABLE customer_prices (
    id                   BIGSERIAL PRIMARY KEY,
    uid                  VARCHAR(26)     NOT NULL,
    company_id           BIGINT          NOT NULL,
    customer_id          BIGINT          NOT NULL,
    product_id           BIGINT          NOT NULL,
    unit_price_amount    NUMERIC(19,4)   NOT NULL,
    currency             VARCHAR(3)      NOT NULL,
    effective_from       DATE,
    effective_to         DATE,
    status               VARCHAR(20)     NOT NULL DEFAULT 'ACTIVE',
    version              BIGINT          NOT NULL DEFAULT 0,
    created_at           TIMESTAMPTZ     NOT NULL DEFAULT now(),
    created_by           BIGINT,
    updated_at           TIMESTAMPTZ,
    updated_by           BIGINT,
    CONSTRAINT uq_customer_price_uid      UNIQUE (uid),
    CONSTRAINT uq_customer_price_scope    UNIQUE (customer_id, product_id),
    CONSTRAINT fk_customer_price_company  FOREIGN KEY (company_id)  REFERENCES companies(id),
    CONSTRAINT fk_customer_price_customer FOREIGN KEY (customer_id) REFERENCES customers(id),
    CONSTRAINT fk_customer_price_product  FOREIGN KEY (product_id)  REFERENCES products(id),
    CONSTRAINT chk_customer_price_amount  CHECK (unit_price_amount >= 0),
    CONSTRAINT chk_customer_price_window  CHECK (effective_to IS NULL OR effective_from IS NULL OR effective_to >= effective_from),
    CONSTRAINT chk_customer_price_status  CHECK (status IN ('ACTIVE','INACTIVE','ARCHIVED'))
);

CREATE INDEX ix_customer_prices_company_id ON customer_prices (company_id);
CREATE INDEX ix_customer_prices_scope      ON customer_prices (customer_id, product_id);

-- ============================================================================
-- (3) promotions — time-boxed discount/override rules
-- ============================================================================
CREATE TABLE promotions (
    id                   BIGSERIAL PRIMARY KEY,
    uid                  VARCHAR(26)     NOT NULL,
    company_id           BIGINT          NOT NULL,
    code                 VARCHAR(30)     NOT NULL,
    name                 VARCHAR(120)    NOT NULL,
    target               VARCHAR(20)     NOT NULL,
    target_product_id    BIGINT,
    target_category      VARCHAR(60),
    effect               VARCHAR(20)     NOT NULL,
    effect_value         NUMERIC(19,4)   NOT NULL,
    effective_from       DATE            NOT NULL,
    effective_to         DATE            NOT NULL,
    priority             SMALLINT        NOT NULL DEFAULT 0,
    status               VARCHAR(20)     NOT NULL DEFAULT 'ACTIVE',
    version              BIGINT          NOT NULL DEFAULT 0,
    created_at           TIMESTAMPTZ     NOT NULL DEFAULT now(),
    created_by           BIGINT,
    updated_at           TIMESTAMPTZ,
    updated_by           BIGINT,
    CONSTRAINT uq_promotion_uid           UNIQUE (uid),
    CONSTRAINT uq_promotion_company_code  UNIQUE (company_id, code),
    CONSTRAINT fk_promotion_company       FOREIGN KEY (company_id)         REFERENCES companies(id),
    CONSTRAINT fk_promotion_product       FOREIGN KEY (target_product_id)  REFERENCES products(id),
    CONSTRAINT chk_promotion_target       CHECK (target IN ('PRODUCT','CATEGORY','ALL')),
    CONSTRAINT chk_promotion_effect       CHECK (effect IN ('PERCENT_DISCOUNT','AMOUNT_DISCOUNT','OVERRIDE_PRICE')),
    CONSTRAINT chk_promotion_effect_value CHECK (effect_value >= 0),
    CONSTRAINT chk_promotion_pct          CHECK (effect <> 'PERCENT_DISCOUNT' OR effect_value BETWEEN 0 AND 100),
    CONSTRAINT chk_promotion_window       CHECK (effective_to >= effective_from),
    CONSTRAINT chk_promotion_status       CHECK (status IN ('ACTIVE','INACTIVE','ARCHIVED')),
    CONSTRAINT chk_promotion_target_ref   CHECK (
        (target = 'PRODUCT'   AND target_product_id IS NOT NULL)
        OR (target = 'CATEGORY' AND target_category IS NOT NULL)
        OR (target = 'ALL')
    )
);

CREATE INDEX ix_promotions_company_id ON promotions (company_id);
CREATE INDEX ix_promotions_active     ON promotions (company_id, target, effective_from, effective_to);

-- ============================================================================
-- (4) ALTER products — add optional category string (OQ-SD-05)
-- Additive: existing products back-fill NULL (no category). Used by promotion CATEGORY target.
-- ============================================================================
ALTER TABLE products
    ADD COLUMN category VARCHAR(60);

-- ============================================================================
-- (5) ALTER sales_invoice_lines + sales_order_lines — add price_source diagnostic
-- ============================================================================
ALTER TABLE sales_invoice_lines
    ADD COLUMN price_source VARCHAR(20);

ALTER TABLE sales_order_lines
    ADD COLUMN price_source VARCHAR(20);

-- ============================================================================
-- (6) permission seed + ORG_ADMIN grant
-- ============================================================================
INSERT INTO permissions (code, module, description) VALUES
    ('SALES.PRICING.RULE.VIEW',   'products', 'View pricing rules (tiers, customer prices, promotions)'),
    ('SALES.PRICING.RULE.MANAGE', 'products', 'Create and manage pricing rules (tiers, customer prices, promotions)')
ON CONFLICT (code) DO NOTHING;

INSERT INTO role_permission (role_id, permission_id)
SELECT r.id, p.id
FROM   roles r
CROSS JOIN permissions p
WHERE  r.code = 'ORG_ADMIN'
  AND  p.code IN ('SALES.PRICING.RULE.VIEW', 'SALES.PRICING.RULE.MANAGE')
ON CONFLICT DO NOTHING;
