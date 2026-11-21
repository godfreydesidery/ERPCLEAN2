-- ============================================================================
-- V117 — POS partial (item-level) returns. DDL approved by the owner 2026-11-21.
-- Additive: two new tables; no change to any existing column or row, no backfill.
-- Returned qty per sold line is derived from pos_sale_return_lines (no column on sales_invoice_lines).
-- ============================================================================
SET LOCAL lock_timeout = '10s';   -- the FKs below briefly lock the referenced tables

CREATE TABLE pos_sale_returns (
    id                BIGSERIAL PRIMARY KEY,
    uid               VARCHAR(26)   NOT NULL,
    return_no         VARCHAR(30)   NOT NULL,                 -- PRT-0001, per company
    company_id        BIGINT        NOT NULL,
    branch_id         BIGINT        NOT NULL,
    pos_session_id    BIGINT        NOT NULL,                 -- session the refund was paid from
    sales_invoice_id  BIGINT        NOT NULL,                 -- the original POS sale
    reason            VARCHAR(255)  NOT NULL,
    net_amount        NUMERIC(19,4) NOT NULL,
    discount_amount   NUMERIC(19,4) NOT NULL DEFAULT 0,
    vat_amount        NUMERIC(19,4) NOT NULL,
    gross_amount      NUMERIC(19,4) NOT NULL,                 -- = cash refunded
    currency          VARCHAR(3)    NOT NULL DEFAULT 'TZS',
    refund_tender     VARCHAR(20)   NOT NULL DEFAULT 'CASH',
    payout_id         BIGINT,                                 -- the REFUND payout that took the cash out
    authorised_by     BIGINT,                                 -- approving supervisor; NULL = caller was supervisor
    version           BIGINT        NOT NULL DEFAULT 0,
    created_at        TIMESTAMPTZ   NOT NULL DEFAULT now(),
    created_by        BIGINT,
    updated_at        TIMESTAMPTZ,
    updated_by        BIGINT,
    CONSTRAINT uq_pos_sale_return_uid     UNIQUE (uid),
    CONSTRAINT uq_pos_sale_return_no      UNIQUE (company_id, return_no),
    CONSTRAINT fk_pos_sale_return_session FOREIGN KEY (pos_session_id)   REFERENCES pos_sessions (id),
    CONSTRAINT fk_pos_sale_return_invoice FOREIGN KEY (sales_invoice_id) REFERENCES sales_invoices (id),
    CONSTRAINT fk_pos_sale_return_payout  FOREIGN KEY (payout_id)        REFERENCES pos_session_payouts (id),
    CONSTRAINT chk_pos_sale_return_amounts CHECK (net_amount >= 0 AND vat_amount >= 0 AND gross_amount > 0),
    CONSTRAINT chk_pos_sale_return_tender  CHECK (refund_tender IN ('CASH'))
);
CREATE INDEX ix_pos_sale_returns_invoice ON pos_sale_returns (sales_invoice_id);
CREATE INDEX ix_pos_sale_returns_session ON pos_sale_returns (pos_session_id);

CREATE TABLE pos_sale_return_lines (
    id                     BIGSERIAL PRIMARY KEY,
    uid                    VARCHAR(26)   NOT NULL,
    pos_sale_return_id     BIGINT        NOT NULL,
    company_id             BIGINT        NOT NULL,
    line_no                SMALLINT      NOT NULL,
    sales_invoice_line_id  BIGINT        NOT NULL,            -- the sold line being returned
    product_id             BIGINT        NOT NULL,
    qty_returned           NUMERIC(19,6) NOT NULL,            -- in the sold line's unit
    qty_returned_base      NUMERIC(19,6) NOT NULL,
    net_amount             NUMERIC(19,4) NOT NULL,
    discount_amount        NUMERIC(19,4) NOT NULL DEFAULT 0,
    vat_amount             NUMERIC(19,4) NOT NULL,
    gross_amount           NUMERIC(19,4) NOT NULL,
    cost_amount            NUMERIC(19,4),                     -- issue cost put back; NULL for non-stock
    version                BIGINT        NOT NULL DEFAULT 0,
    created_at             TIMESTAMPTZ   NOT NULL DEFAULT now(),
    created_by             BIGINT,
    CONSTRAINT uq_pos_sale_return_line_uid  UNIQUE (uid),
    CONSTRAINT uq_pos_sale_return_line_no   UNIQUE (pos_sale_return_id, line_no),
    CONSTRAINT fk_pos_sale_return_line_hdr  FOREIGN KEY (pos_sale_return_id)    REFERENCES pos_sale_returns (id),
    CONSTRAINT fk_pos_sale_return_line_sil  FOREIGN KEY (sales_invoice_line_id) REFERENCES sales_invoice_lines (id),
    CONSTRAINT fk_pos_sale_return_line_prod FOREIGN KEY (product_id)            REFERENCES products (id),
    CONSTRAINT chk_pos_sale_return_line_qty CHECK (qty_returned > 0 AND qty_returned_base > 0),
    CONSTRAINT chk_pos_sale_return_line_amt CHECK (net_amount >= 0 AND vat_amount >= 0 AND gross_amount >= 0)
);
CREATE INDEX ix_pos_sale_return_lines_sil ON pos_sale_return_lines (sales_invoice_line_id);
