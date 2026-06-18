-- V18 — Sales Orders / Order-to-Cash spine (ADR-0021, Stage 1 + Stage 2 DDL):
-- (1) quotations + quotation_lines
-- (2) sales_orders + sales_order_lines (progress tracking, reserved qty)
-- (3) deliveries + delivery_lines
-- (4) sales_returns + sales_return_lines (Stage-2 DDL pulled forward; code deferred)
-- (5) ALTER stock_on_hand ADD reserved_qty
-- (6) ALTER sales_invoices ADD origin + source_order_uid + source_delivery_uid
-- (7) chk_ar_credit_note_origin widen (add RETURN)
-- (8) permission seed + ORG_ADMIN grant
-- Additive only. V1–V17 are FROZEN.

-- ============================================================================
-- (1) quotations + quotation_lines  (ADR-0021 D-3)
-- ============================================================================

CREATE TABLE quotations (
    id                   BIGSERIAL PRIMARY KEY,
    uid                  VARCHAR(26)     NOT NULL,
    company_id           BIGINT          NOT NULL,
    branch_id            BIGINT          NOT NULL,
    quote_number         VARCHAR(30),
    status               VARCHAR(20)     NOT NULL DEFAULT 'DRAFT',
    customer_id          BIGINT          NOT NULL,
    agent_id             BIGINT,
    currency             VARCHAR(3)      NOT NULL,
    quote_date           DATE            NOT NULL,
    valid_until          DATE            NOT NULL,
    customer_po_number   VARCHAR(60),                    -- P2-M3: customer's own PO reference
    revision_no          INT,                            -- P2-M3: plain revision counter
    probability          NUMERIC(5,2),                   -- P2-M3: win-probability percent
    payment_terms_id     BIGINT,                         -- P2 D1: soft-FK payment_terms(id)
    doc_discount_amount  NUMERIC(19,4),
    doc_discount_percent NUMERIC(9,4),
    net_total_amount     NUMERIC(19,4)   NOT NULL DEFAULT 0,
    vat_total_amount     NUMERIC(19,4)   NOT NULL DEFAULT 0,
    gross_total_amount   NUMERIC(19,4)   NOT NULL DEFAULT 0,
    notes                VARCHAR(500),
    sent_at              TIMESTAMPTZ,
    accepted_at          TIMESTAMPTZ,
    rejected_at          TIMESTAMPTZ,
    expired_at           TIMESTAMPTZ,
    converted_order_uid  VARCHAR(26),
    version              BIGINT          NOT NULL DEFAULT 0,
    created_at           TIMESTAMPTZ     NOT NULL DEFAULT now(),
    created_by           BIGINT,
    updated_at           TIMESTAMPTZ,
    updated_by           BIGINT,
    CONSTRAINT uq_quotation_uid              UNIQUE (uid),
    CONSTRAINT uq_quotation_company_number   UNIQUE (company_id, quote_number),
    CONSTRAINT fk_quotation_company          FOREIGN KEY (company_id) REFERENCES companies(id),
    CONSTRAINT fk_quotation_branch           FOREIGN KEY (branch_id)  REFERENCES branches(id),
    CONSTRAINT fk_quotation_customer         FOREIGN KEY (customer_id) REFERENCES customers(id),
    CONSTRAINT fk_quotation_agent            FOREIGN KEY (agent_id)   REFERENCES agents(id),
    CONSTRAINT chk_quotation_status          CHECK (status IN ('DRAFT','SENT','ACCEPTED','EXPIRED','REJECTED')),
    CONSTRAINT chk_quotation_validity        CHECK (valid_until >= quote_date),
    CONSTRAINT chk_quotation_doc_discount    CHECK (doc_discount_percent IS NULL OR doc_discount_percent BETWEEN 0 AND 100),
    CONSTRAINT chk_quotation_number_when_sent CHECK (
        (status = 'DRAFT' AND quote_number IS NULL)
        OR (status <> 'DRAFT' AND quote_number IS NOT NULL))
);

CREATE INDEX ix_quotations_company_id   ON quotations (company_id);
CREATE INDEX ix_quotations_status       ON quotations (company_id, status);
CREATE INDEX ix_quotations_customer_id  ON quotations (customer_id);

CREATE TABLE quotation_lines (
    id                   BIGSERIAL PRIMARY KEY,
    uid                  VARCHAR(26)     NOT NULL,
    quotation_id         BIGINT          NOT NULL,
    company_id           BIGINT          NOT NULL,
    branch_id            BIGINT          NOT NULL,
    line_no              SMALLINT        NOT NULL,
    product_id           BIGINT          NOT NULL,
    product_code         VARCHAR(60)     NOT NULL,
    product_name         VARCHAR(120)    NOT NULL,
    unit_id              BIGINT          NOT NULL,
    unit_name            VARCHAR(60)     NOT NULL,
    quantity             NUMERIC(19,6)   NOT NULL,
    qty_in_base          NUMERIC(19,6)   NOT NULL,
    list_price_amount    NUMERIC(19,4)   NOT NULL,
    unit_price_amount    NUMERIC(19,4)   NOT NULL,
    line_discount_amount  NUMERIC(19,4),
    line_discount_percent NUMERIC(9,4),
    vat_status           VARCHAR(20)     NOT NULL,
    vat_rate             NUMERIC(9,4)    NOT NULL,
    net_amount           NUMERIC(19,4)   NOT NULL DEFAULT 0,
    vat_amount           NUMERIC(19,4)   NOT NULL DEFAULT 0,
    gross_amount         NUMERIC(19,4)   NOT NULL DEFAULT 0,
    currency             VARCHAR(3)      NOT NULL,
    -- P3 (X15): promotion provenance — soft-FK to promotions(id); nullable.
    promotion_id         BIGINT,
    version              BIGINT          NOT NULL DEFAULT 0,
    created_at           TIMESTAMPTZ     NOT NULL DEFAULT now(),
    created_by           BIGINT,
    updated_at           TIMESTAMPTZ,
    updated_by           BIGINT,
    CONSTRAINT uq_quotation_line_uid    UNIQUE (uid),
    CONSTRAINT uq_quotation_line_no     UNIQUE (quotation_id, line_no),
    CONSTRAINT fk_quotation_line_header FOREIGN KEY (quotation_id) REFERENCES quotations(id),
    CONSTRAINT fk_quotation_line_product FOREIGN KEY (product_id) REFERENCES products(id),
    CONSTRAINT fk_quotation_line_unit   FOREIGN KEY (unit_id) REFERENCES units_of_measure(id),
    CONSTRAINT chk_quotation_line_qty   CHECK (quantity > 0 AND qty_in_base > 0),
    CONSTRAINT chk_quotation_line_discount CHECK (line_discount_percent IS NULL OR line_discount_percent BETWEEN 0 AND 100)
);

CREATE INDEX ix_quotation_lines_quotation_id ON quotation_lines (quotation_id);

-- ============================================================================
-- (2) sales_orders + sales_order_lines  (ADR-0021 D-3/D-4)
-- ============================================================================

CREATE TABLE sales_orders (
    id                   BIGSERIAL PRIMARY KEY,
    uid                  VARCHAR(26)     NOT NULL,
    company_id           BIGINT          NOT NULL,
    branch_id            BIGINT          NOT NULL,
    order_number         VARCHAR(30)     NOT NULL,
    status               VARCHAR(30)     NOT NULL DEFAULT 'DRAFT',
    customer_id          BIGINT          NOT NULL,
    agent_id             BIGINT,
    currency             VARCHAR(3)      NOT NULL,
    order_date           DATE            NOT NULL,
    customer_po_number   VARCHAR(60),                    -- P2-M3: customer's own PO reference
    requested_delivery_date DATE,                        -- P2-M3: customer-requested delivery date
    promised_date        DATE,                           -- P2-M3: promised delivery date
    payment_terms_id     BIGINT,                         -- P2 D1: soft-FK payment_terms(id)
    source_quotation_uid VARCHAR(26),
    doc_discount_amount  NUMERIC(19,4),
    doc_discount_percent NUMERIC(9,4),
    net_total_amount     NUMERIC(19,4)   NOT NULL DEFAULT 0,
    vat_total_amount     NUMERIC(19,4)   NOT NULL DEFAULT 0,
    gross_total_amount   NUMERIC(19,4)   NOT NULL DEFAULT 0,
    confirmed_at         TIMESTAMPTZ,
    cancelled_at         TIMESTAMPTZ,
    cancel_reason        VARCHAR(255),
    notes                VARCHAR(500),
    version              BIGINT          NOT NULL DEFAULT 0,
    created_at           TIMESTAMPTZ     NOT NULL DEFAULT now(),
    created_by           BIGINT,
    updated_at           TIMESTAMPTZ,
    updated_by           BIGINT,
    CONSTRAINT uq_sales_order_uid             UNIQUE (uid),
    CONSTRAINT uq_sales_order_company_number  UNIQUE (company_id, order_number),
    CONSTRAINT fk_sales_order_company         FOREIGN KEY (company_id) REFERENCES companies(id),
    CONSTRAINT fk_sales_order_branch          FOREIGN KEY (branch_id)  REFERENCES branches(id),
    CONSTRAINT fk_sales_order_customer        FOREIGN KEY (customer_id) REFERENCES customers(id),
    CONSTRAINT fk_sales_order_agent           FOREIGN KEY (agent_id)   REFERENCES agents(id),
    CONSTRAINT chk_sales_order_status         CHECK (status IN (
        'DRAFT','CONFIRMED','PARTIALLY_FULFILLED','FULFILLED',
        'PARTIALLY_INVOICED','INVOICED','CLOSED','CANCELLED')),
    CONSTRAINT chk_sales_order_doc_discount   CHECK (doc_discount_percent IS NULL OR doc_discount_percent BETWEEN 0 AND 100)
);

CREATE INDEX ix_sales_orders_company_id   ON sales_orders (company_id);
CREATE INDEX ix_sales_orders_status       ON sales_orders (company_id, status);
CREATE INDEX ix_sales_orders_customer_id  ON sales_orders (customer_id);

CREATE TABLE sales_order_lines (
    id                    BIGSERIAL PRIMARY KEY,
    uid                   VARCHAR(26)     NOT NULL,
    sales_order_id        BIGINT          NOT NULL,
    company_id            BIGINT          NOT NULL,
    branch_id             BIGINT          NOT NULL,
    line_no               SMALLINT        NOT NULL,
    product_id            BIGINT          NOT NULL,
    product_code          VARCHAR(60)     NOT NULL,
    product_name          VARCHAR(120)    NOT NULL,
    unit_id               BIGINT          NOT NULL,
    unit_name             VARCHAR(60)     NOT NULL,
    qty_ordered           NUMERIC(19,6)   NOT NULL,
    qty_ordered_base      NUMERIC(19,6)   NOT NULL,
    qty_fulfilled_base    NUMERIC(19,6)   NOT NULL DEFAULT 0,
    qty_invoiced_base     NUMERIC(19,6)   NOT NULL DEFAULT 0,
    qty_reserved_base     NUMERIC(19,6)   NOT NULL DEFAULT 0,
    list_price_amount     NUMERIC(19,4)   NOT NULL,
    unit_price_amount     NUMERIC(19,4)   NOT NULL,
    price_overridden      BOOLEAN         NOT NULL DEFAULT false,
    overridden_by         BIGINT,
    line_discount_amount  NUMERIC(19,4),
    line_discount_percent NUMERIC(9,4),
    discount_reason       VARCHAR(160),                  -- P2-M3: free-text reason for the line discount
    requested_date        DATE,                          -- P2-M3: customer-requested date for this line
    vat_status            VARCHAR(20)     NOT NULL,
    vat_rate              NUMERIC(9,4)    NOT NULL,
    net_amount            NUMERIC(19,4)   NOT NULL DEFAULT 0,
    vat_amount            NUMERIC(19,4)   NOT NULL DEFAULT 0,
    gross_amount          NUMERIC(19,4)   NOT NULL DEFAULT 0,
    currency              VARCHAR(3)      NOT NULL,
    version               BIGINT          NOT NULL DEFAULT 0,
    created_at            TIMESTAMPTZ     NOT NULL DEFAULT now(),
    created_by            BIGINT,
    updated_at            TIMESTAMPTZ,
    updated_by            BIGINT,
    CONSTRAINT uq_sales_order_line_uid      UNIQUE (uid),
    CONSTRAINT uq_sales_order_line_no       UNIQUE (sales_order_id, line_no),
    CONSTRAINT fk_sales_order_line_header   FOREIGN KEY (sales_order_id) REFERENCES sales_orders(id),
    CONSTRAINT fk_sales_order_line_product  FOREIGN KEY (product_id) REFERENCES products(id),
    CONSTRAINT fk_sales_order_line_unit     FOREIGN KEY (unit_id) REFERENCES units_of_measure(id),
    CONSTRAINT chk_sales_order_line_qty     CHECK (qty_ordered > 0 AND qty_ordered_base > 0),
    CONSTRAINT chk_sales_order_line_progress CHECK (
        qty_fulfilled_base >= 0
        AND qty_invoiced_base >= 0
        AND qty_fulfilled_base <= qty_ordered_base
        AND qty_invoiced_base <= qty_fulfilled_base),
    CONSTRAINT chk_sales_order_line_reserved CHECK (qty_reserved_base >= 0),
    CONSTRAINT chk_sales_order_line_discount CHECK (line_discount_percent IS NULL OR line_discount_percent BETWEEN 0 AND 100)
);

CREATE INDEX ix_sales_order_lines_order_id   ON sales_order_lines (sales_order_id);
CREATE INDEX ix_sales_order_lines_product_id ON sales_order_lines (product_id);

-- ============================================================================
-- (3) deliveries + delivery_lines  (ADR-0021 D-7)
-- ============================================================================

CREATE TABLE deliveries (
    id               BIGSERIAL PRIMARY KEY,
    uid              VARCHAR(26)     NOT NULL,
    company_id       BIGINT          NOT NULL,
    branch_id        BIGINT          NOT NULL,
    delivery_number  VARCHAR(30)     NOT NULL,
    sales_order_id   BIGINT          NOT NULL,
    sales_order_uid  VARCHAR(26)     NOT NULL,
    status           VARCHAR(20)     NOT NULL DEFAULT 'CONFIRMED',
    customer_id      BIGINT          NOT NULL,
    delivery_date    DATE            NOT NULL,
    confirmed_at     TIMESTAMPTZ     NOT NULL DEFAULT now(),
    cogs_gl_entry_uid VARCHAR(26),
    notes            VARCHAR(500),
    version          BIGINT          NOT NULL DEFAULT 0,
    created_at       TIMESTAMPTZ     NOT NULL DEFAULT now(),
    created_by       BIGINT,
    updated_at       TIMESTAMPTZ,
    updated_by       BIGINT,
    CONSTRAINT uq_delivery_uid              UNIQUE (uid),
    CONSTRAINT uq_delivery_company_number   UNIQUE (company_id, delivery_number),
    CONSTRAINT fk_delivery_company          FOREIGN KEY (company_id)   REFERENCES companies(id),
    CONSTRAINT fk_delivery_branch           FOREIGN KEY (branch_id)    REFERENCES branches(id),
    CONSTRAINT fk_delivery_sales_order      FOREIGN KEY (sales_order_id) REFERENCES sales_orders(id),
    CONSTRAINT fk_delivery_customer         FOREIGN KEY (customer_id)  REFERENCES customers(id),
    CONSTRAINT chk_delivery_status          CHECK (status IN ('DRAFT','CONFIRMED'))
);

CREATE INDEX ix_deliveries_company_id      ON deliveries (company_id);
CREATE INDEX ix_deliveries_sales_order_id  ON deliveries (sales_order_id);
CREATE INDEX ix_deliveries_customer_id     ON deliveries (customer_id);

CREATE TABLE delivery_lines (
    id                      BIGSERIAL PRIMARY KEY,
    uid                     VARCHAR(26)     NOT NULL,
    delivery_id             BIGINT          NOT NULL,
    sales_order_line_id     BIGINT          NOT NULL,
    sales_order_line_uid    VARCHAR(26)     NOT NULL,
    company_id              BIGINT          NOT NULL,
    branch_id               BIGINT          NOT NULL,
    line_no                 SMALLINT        NOT NULL,
    product_id              BIGINT          NOT NULL,
    product_code            VARCHAR(60)     NOT NULL,
    product_name            VARCHAR(120)    NOT NULL,
    unit_id                 BIGINT          NOT NULL,
    unit_name               VARCHAR(60)     NOT NULL,
    qty_delivered           NUMERIC(19,6)   NOT NULL,
    qty_delivered_base      NUMERIC(19,6)   NOT NULL,
    qty_invoiced_base       NUMERIC(19,6)   NOT NULL DEFAULT 0,
    returned_qty_base       NUMERIC(19,6)   NOT NULL DEFAULT 0,
    issue_value_amount      NUMERIC(19,4),
    currency                VARCHAR(3)      NOT NULL,
    version                 BIGINT          NOT NULL DEFAULT 0,
    created_at              TIMESTAMPTZ     NOT NULL DEFAULT now(),
    created_by              BIGINT,
    updated_at              TIMESTAMPTZ,
    updated_by              BIGINT,
    CONSTRAINT uq_delivery_line_uid         UNIQUE (uid),
    CONSTRAINT uq_delivery_line_no          UNIQUE (delivery_id, line_no),
    CONSTRAINT fk_delivery_line_header      FOREIGN KEY (delivery_id)         REFERENCES deliveries(id),
    CONSTRAINT fk_delivery_line_sol         FOREIGN KEY (sales_order_line_id) REFERENCES sales_order_lines(id),
    CONSTRAINT fk_delivery_line_product     FOREIGN KEY (product_id) REFERENCES products(id),
    CONSTRAINT fk_delivery_line_unit        FOREIGN KEY (unit_id)    REFERENCES units_of_measure(id),
    CONSTRAINT chk_delivery_line_qty        CHECK (qty_delivered > 0 AND qty_delivered_base > 0),
    CONSTRAINT chk_delivery_line_invoiced   CHECK (qty_invoiced_base >= 0 AND qty_invoiced_base <= qty_delivered_base),
    CONSTRAINT chk_delivery_line_returned   CHECK (returned_qty_base >= 0 AND returned_qty_base <= qty_delivered_base)
);

CREATE INDEX ix_delivery_lines_delivery_id ON delivery_lines (delivery_id);
CREATE INDEX ix_delivery_lines_sol_id      ON delivery_lines (sales_order_line_id);

-- ============================================================================
-- (4) sales_returns + sales_return_lines  (ADR-0021 D-11, Stage-2 DDL)
-- ============================================================================

CREATE TABLE sales_returns (
    id                         BIGSERIAL PRIMARY KEY,
    uid                        VARCHAR(26)     NOT NULL,
    company_id                 BIGINT          NOT NULL,
    branch_id                  BIGINT          NOT NULL,
    return_number              VARCHAR(30)     NOT NULL,
    status                     VARCHAR(20)     NOT NULL DEFAULT 'CONFIRMED',
    delivery_id                BIGINT          NOT NULL,
    delivery_uid               VARCHAR(26)     NOT NULL,
    sales_order_uid            VARCHAR(26),
    customer_id                BIGINT          NOT NULL,
    return_date                DATE            NOT NULL,
    credit_note_uid            VARCHAR(26),
    cogs_reversal_gl_entry_uid VARCHAR(26),
    reason                     VARCHAR(255),
    net_amount                 NUMERIC(19,4)   NOT NULL DEFAULT 0,
    vat_amount                 NUMERIC(19,4)   NOT NULL DEFAULT 0,
    gross_amount               NUMERIC(19,4)   NOT NULL DEFAULT 0,
    currency                   VARCHAR(3)      NOT NULL,
    version                    BIGINT          NOT NULL DEFAULT 0,
    created_at                 TIMESTAMPTZ     NOT NULL DEFAULT now(),
    created_by                 BIGINT,
    updated_at                 TIMESTAMPTZ,
    updated_by                 BIGINT,
    CONSTRAINT uq_sales_return_uid            UNIQUE (uid),
    CONSTRAINT uq_sales_return_company_number UNIQUE (company_id, return_number),
    CONSTRAINT fk_sales_return_company        FOREIGN KEY (company_id)  REFERENCES companies(id),
    CONSTRAINT fk_sales_return_branch         FOREIGN KEY (branch_id)   REFERENCES branches(id),
    CONSTRAINT fk_sales_return_delivery       FOREIGN KEY (delivery_id) REFERENCES deliveries(id),
    CONSTRAINT fk_sales_return_customer       FOREIGN KEY (customer_id) REFERENCES customers(id),
    CONSTRAINT chk_sales_return_status        CHECK (status IN ('DRAFT','CONFIRMED'))
);

CREATE INDEX ix_sales_returns_company_id   ON sales_returns (company_id);
CREATE INDEX ix_sales_returns_delivery_id  ON sales_returns (delivery_id);

CREATE TABLE sales_return_lines (
    id                   BIGSERIAL PRIMARY KEY,
    uid                  VARCHAR(26)     NOT NULL,
    sales_return_id      BIGINT          NOT NULL,
    delivery_line_id     BIGINT          NOT NULL,
    delivery_line_uid    VARCHAR(26)     NOT NULL,
    company_id           BIGINT          NOT NULL,
    branch_id            BIGINT          NOT NULL,
    line_no              SMALLINT        NOT NULL,
    product_id           BIGINT          NOT NULL,
    product_code         VARCHAR(60)     NOT NULL,
    product_name         VARCHAR(120)    NOT NULL,
    unit_id              BIGINT          NOT NULL,
    unit_name            VARCHAR(60)     NOT NULL,
    qty_returned         NUMERIC(19,6)   NOT NULL,
    qty_returned_base    NUMERIC(19,6)   NOT NULL,
    unit_price_amount    NUMERIC(19,4)   NOT NULL,
    line_discount_amount  NUMERIC(19,4),
    line_discount_percent NUMERIC(9,4),
    vat_status           VARCHAR(20)     NOT NULL,
    vat_rate             NUMERIC(9,4)    NOT NULL,
    net_amount           NUMERIC(19,4)   NOT NULL DEFAULT 0,
    vat_amount           NUMERIC(19,4)   NOT NULL DEFAULT 0,
    gross_amount         NUMERIC(19,4)   NOT NULL DEFAULT 0,
    currency             VARCHAR(3)      NOT NULL,
    version              BIGINT          NOT NULL DEFAULT 0,
    created_at           TIMESTAMPTZ     NOT NULL DEFAULT now(),
    created_by           BIGINT,
    updated_at           TIMESTAMPTZ,
    updated_by           BIGINT,
    CONSTRAINT uq_sales_return_line_uid     UNIQUE (uid),
    CONSTRAINT uq_sales_return_line_no      UNIQUE (sales_return_id, line_no),
    CONSTRAINT fk_sales_return_line_header  FOREIGN KEY (sales_return_id)  REFERENCES sales_returns(id),
    CONSTRAINT fk_sales_return_line_del_ln  FOREIGN KEY (delivery_line_id) REFERENCES delivery_lines(id),
    CONSTRAINT fk_sales_return_line_product FOREIGN KEY (product_id) REFERENCES products(id),
    CONSTRAINT fk_sales_return_line_unit    FOREIGN KEY (unit_id)    REFERENCES units_of_measure(id),
    CONSTRAINT chk_sales_return_line_qty    CHECK (qty_returned > 0 AND qty_returned_base > 0)
);

CREATE INDEX ix_sales_return_lines_return_id ON sales_return_lines (sales_return_id);

-- ============================================================================
-- (5) ALTER stock_on_hand — add reserved_qty  (ADR-0021 D-5)
-- ============================================================================
ALTER TABLE stock_on_hand
    ADD COLUMN reserved_qty NUMERIC(19,6) NOT NULL DEFAULT 0;

ALTER TABLE stock_on_hand
    ADD CONSTRAINT chk_stock_on_hand_reserved_nonneg CHECK (reserved_qty >= 0);

-- ============================================================================
-- (6) ALTER sales_invoices — add origin + source refs  (ADR-0021 D-8)
-- ============================================================================
ALTER TABLE sales_invoices
    ADD COLUMN origin              VARCHAR(20) NOT NULL DEFAULT 'DIRECT',
    ADD COLUMN source_order_uid    VARCHAR(26),
    ADD COLUMN source_delivery_uid VARCHAR(26);

ALTER TABLE sales_invoices
    ADD CONSTRAINT chk_sales_invoice_origin
        CHECK (origin IN ('DIRECT','SALES_ORDER'));

ALTER TABLE sales_invoices
    ADD CONSTRAINT chk_sales_invoice_origin_refs
        CHECK (
            (origin = 'DIRECT' AND source_order_uid IS NULL AND source_delivery_uid IS NULL)
            OR (origin = 'SALES_ORDER' AND source_order_uid IS NOT NULL)
        );

-- ============================================================================
-- (7) chk_ar_credit_note_origin widen — add RETURN  (ADR-0021 D-11)
-- ============================================================================
ALTER TABLE ar_credit_notes
    DROP CONSTRAINT IF EXISTS chk_ar_credit_note_origin;

ALTER TABLE ar_credit_notes
    ADD CONSTRAINT chk_ar_credit_note_origin
        CHECK (origin IN ('STANDALONE','SALE_VOID','RETURN'));
