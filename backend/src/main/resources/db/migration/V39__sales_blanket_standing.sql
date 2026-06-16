-- V45 — Sales Depth: Blanket & Standing Orders (ADR-0029, Stage 2b)
-- blanket_orders + blanket_order_lines (drawn <= committed guard);
-- standing_orders + standing_order_lines;
-- ALTER sales_orders ADD source_blanket_uid + source_standing_uid;
-- permission seed + ORG_ADMIN grant.
-- Additive only. V1–V44 (excl. in-flight V20–V41) are FROZEN.

-- ============================================================================
-- (1) blanket_orders — framework commitment header
-- ============================================================================
CREATE TABLE blanket_orders (
    id                     BIGSERIAL PRIMARY KEY,
    uid                    VARCHAR(26)    NOT NULL,
    company_id             BIGINT         NOT NULL,
    branch_id              BIGINT         NOT NULL,
    order_number           VARCHAR(30)    NOT NULL,
    customer_id            BIGINT         NOT NULL,
    currency               VARCHAR(3)     NOT NULL,
    valid_from             DATE           NOT NULL,
    valid_to               DATE           NOT NULL,
    total_committed_amount NUMERIC(19,4)  NOT NULL DEFAULT 0,
    total_drawn_amount     NUMERIC(19,4)  NOT NULL DEFAULT 0,
    status                 VARCHAR(20)    NOT NULL DEFAULT 'ACTIVE',
    notes                  VARCHAR(500),
    version                BIGINT         NOT NULL DEFAULT 0,
    created_at             TIMESTAMPTZ    NOT NULL DEFAULT now(),
    created_by             BIGINT,
    updated_at             TIMESTAMPTZ,
    updated_by             BIGINT,
    CONSTRAINT uq_blanket_order_uid            UNIQUE (uid),
    CONSTRAINT uq_blanket_order_company_number UNIQUE (company_id, order_number),
    CONSTRAINT fk_blanket_order_company        FOREIGN KEY (company_id)  REFERENCES companies(id),
    CONSTRAINT fk_blanket_order_branch         FOREIGN KEY (branch_id)   REFERENCES branches(id),
    CONSTRAINT fk_blanket_order_customer       FOREIGN KEY (customer_id) REFERENCES customers(id),
    CONSTRAINT chk_blanket_order_status        CHECK (status IN ('ACTIVE','EXHAUSTED','CANCELLED')),
    CONSTRAINT chk_blanket_order_window        CHECK (valid_to >= valid_from)
);

CREATE INDEX ix_blanket_orders_company_id  ON blanket_orders (company_id);
CREATE INDEX ix_blanket_orders_customer_id ON blanket_orders (company_id, customer_id);

-- ============================================================================
-- (2) blanket_order_lines — per-product committed quantity
-- ============================================================================
CREATE TABLE blanket_order_lines (
    id                       BIGSERIAL PRIMARY KEY,
    uid                      VARCHAR(26)   NOT NULL,
    blanket_order_id         BIGINT        NOT NULL,
    company_id               BIGINT        NOT NULL,
    branch_id                BIGINT        NOT NULL,
    line_no                  SMALLINT      NOT NULL,
    product_id               BIGINT        NOT NULL,
    product_code             VARCHAR(60)   NOT NULL,
    product_name             VARCHAR(120)  NOT NULL,
    unit_id                  BIGINT        NOT NULL,
    unit_name                VARCHAR(60)   NOT NULL,
    committed_qty_base       NUMERIC(19,6) NOT NULL,
    drawn_qty_base           NUMERIC(19,6) NOT NULL DEFAULT 0,
    unit_price_amount        NUMERIC(19,4) NOT NULL,
    currency                 VARCHAR(3)    NOT NULL,
    version                  BIGINT        NOT NULL DEFAULT 0,
    created_at               TIMESTAMPTZ   NOT NULL DEFAULT now(),
    created_by               BIGINT,
    updated_at               TIMESTAMPTZ,
    updated_by               BIGINT,
    CONSTRAINT uq_blanket_order_line_uid    UNIQUE (uid),
    CONSTRAINT uq_blanket_order_line_no     UNIQUE (blanket_order_id, line_no),
    CONSTRAINT fk_blanket_order_line_blanket FOREIGN KEY (blanket_order_id) REFERENCES blanket_orders(id),
    CONSTRAINT chk_blanket_order_line_committed_qty CHECK (committed_qty_base > 0),
    CONSTRAINT chk_blanket_order_line_drawn         CHECK (drawn_qty_base >= 0 AND drawn_qty_base <= committed_qty_base)
);

CREATE INDEX ix_blanket_order_lines_blanket_id ON blanket_order_lines (blanket_order_id);

-- ============================================================================
-- (3) standing_orders — recurring order template header
-- ============================================================================
CREATE TABLE standing_orders (
    id             BIGSERIAL PRIMARY KEY,
    uid            VARCHAR(26)   NOT NULL,
    company_id     BIGINT        NOT NULL,
    branch_id      BIGINT        NOT NULL,
    order_number   VARCHAR(30)   NOT NULL,
    customer_id    BIGINT        NOT NULL,
    currency       VARCHAR(3)    NOT NULL,
    frequency      VARCHAR(20)   NOT NULL,
    start_date     DATE          NOT NULL,
    end_date       DATE,
    next_run_date  DATE          NOT NULL,
    status         VARCHAR(20)   NOT NULL DEFAULT 'ACTIVE',
    notes          VARCHAR(500),
    version        BIGINT        NOT NULL DEFAULT 0,
    created_at     TIMESTAMPTZ   NOT NULL DEFAULT now(),
    created_by     BIGINT,
    updated_at     TIMESTAMPTZ,
    updated_by     BIGINT,
    CONSTRAINT uq_standing_order_uid            UNIQUE (uid),
    CONSTRAINT uq_standing_order_company_number UNIQUE (company_id, order_number),
    CONSTRAINT fk_standing_order_company        FOREIGN KEY (company_id)  REFERENCES companies(id),
    CONSTRAINT fk_standing_order_branch         FOREIGN KEY (branch_id)   REFERENCES branches(id),
    CONSTRAINT fk_standing_order_customer       FOREIGN KEY (customer_id) REFERENCES customers(id),
    CONSTRAINT chk_standing_order_status        CHECK (status IN ('ACTIVE','PAUSED','CANCELLED')),
    CONSTRAINT chk_standing_order_frequency     CHECK (frequency IN ('DAILY','WEEKLY','BIWEEKLY','MONTHLY'))
);

CREATE INDEX ix_standing_orders_company_id  ON standing_orders (company_id);
CREATE INDEX ix_standing_orders_customer_id ON standing_orders (customer_id);
CREATE INDEX ix_standing_orders_due         ON standing_orders (status, next_run_date);

-- ============================================================================
-- (4) standing_order_lines — recurring basket lines
-- ============================================================================
CREATE TABLE standing_order_lines (
    id                BIGSERIAL PRIMARY KEY,
    uid               VARCHAR(26)   NOT NULL,
    standing_order_id BIGINT        NOT NULL,
    company_id        BIGINT        NOT NULL,
    branch_id         BIGINT        NOT NULL,
    line_no           SMALLINT      NOT NULL,
    product_id        BIGINT        NOT NULL,
    product_code      VARCHAR(60)   NOT NULL,
    product_name      VARCHAR(120)  NOT NULL,
    unit_id           BIGINT        NOT NULL,
    unit_name         VARCHAR(60)   NOT NULL,
    qty               NUMERIC(19,6) NOT NULL,
    qty_base          NUMERIC(19,6) NOT NULL,
    unit_price_amount NUMERIC(19,4) NOT NULL,
    currency          VARCHAR(3)    NOT NULL,
    version           BIGINT        NOT NULL DEFAULT 0,
    created_at        TIMESTAMPTZ   NOT NULL DEFAULT now(),
    created_by        BIGINT,
    updated_at        TIMESTAMPTZ,
    updated_by        BIGINT,
    CONSTRAINT uq_standing_order_line_uid      UNIQUE (uid),
    CONSTRAINT uq_standing_order_line_no       UNIQUE (standing_order_id, line_no),
    CONSTRAINT fk_standing_order_line_standing FOREIGN KEY (standing_order_id) REFERENCES standing_orders(id),
    CONSTRAINT chk_standing_order_line_qty     CHECK (qty_base > 0)
);

CREATE INDEX ix_standing_order_lines_standing_id ON standing_order_lines (standing_order_id);

-- ============================================================================
-- (5) ALTER sales_orders — add blanket + standing back-reference columns
-- ============================================================================
ALTER TABLE sales_orders
    ADD COLUMN source_blanket_uid  VARCHAR(26),
    ADD COLUMN source_standing_uid VARCHAR(26);
