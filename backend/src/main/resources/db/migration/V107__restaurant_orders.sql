-- ============================================================================
-- Approved by the owner 2026-10-24. See ADR-0065 (Appendix A).
--
-- J COB (CAFE TULIA), 2026-10-24: "kwanza unatoa order ina print either bar au kitchen ...
-- baada ya hapo inaprint bill ... baada ya mteja kulipa ndio unaprint payment ambayo ndio
-- ina clear hiyo order." Order -> kitchen/bar slips -> bill -> payment clears the order.
--
-- SAFE ON A POPULATED DATABASE: five NEW, empty tables. No ALTER on any existing table, no
-- data written. The money and stock effects of a restaurant order happen only at payment,
-- through the existing POS sale (sales_invoices), so no existing table changes meaning.
-- ============================================================================

-- V107 — Restaurant orders (ADR-0065): floor tables, prep stations + category routing,
-- open orders (checks) and their lines. Additive only: five new tables, no ALTER.

-- (1) restaurant_tables — the per-branch floor
CREATE TABLE restaurant_tables (
    id            BIGSERIAL PRIMARY KEY,
    uid           VARCHAR(26)   NOT NULL,
    company_id    BIGINT        NOT NULL,
    branch_id     BIGINT        NOT NULL,
    name          VARCHAR(40)   NOT NULL,
    seats         SMALLINT      NOT NULL DEFAULT 4,
    area          VARCHAR(40),
    sort_order    INTEGER       NOT NULL DEFAULT 0,
    status        VARCHAR(20)   NOT NULL DEFAULT 'ACTIVE',
    version       BIGINT        NOT NULL DEFAULT 0,
    created_at    TIMESTAMPTZ   NOT NULL DEFAULT now(),
    created_by    BIGINT,
    updated_at    TIMESTAMPTZ,
    updated_by    BIGINT,
    CONSTRAINT uq_restaurant_table_uid         UNIQUE (uid),
    CONSTRAINT uq_restaurant_table_branch_name UNIQUE (branch_id, name),
    CONSTRAINT fk_restaurant_table_company     FOREIGN KEY (company_id) REFERENCES companies(id),
    CONSTRAINT fk_restaurant_table_branch      FOREIGN KEY (branch_id)  REFERENCES branches(id),
    CONSTRAINT chk_restaurant_table_seats      CHECK (seats > 0),
    CONSTRAINT chk_restaurant_table_status     CHECK (status IN ('ACTIVE','INACTIVE','ARCHIVED'))
);
CREATE INDEX ix_restaurant_tables_company_id ON restaurant_tables (company_id);
CREATE INDEX ix_restaurant_tables_branch_id  ON restaurant_tables (branch_id);

-- (2) prep_stations — kitchen / bar; where slips print
CREATE TABLE prep_stations (
    id            BIGSERIAL PRIMARY KEY,
    uid           VARCHAR(26)   NOT NULL,
    company_id    BIGINT        NOT NULL,
    branch_id     BIGINT        NOT NULL,
    name          VARCHAR(60)   NOT NULL,
    printer_host  VARCHAR(255),
    printer_port  INTEGER       NOT NULL DEFAULT 9100,
    is_default    BOOLEAN       NOT NULL DEFAULT FALSE,
    status        VARCHAR(20)   NOT NULL DEFAULT 'ACTIVE',
    version       BIGINT        NOT NULL DEFAULT 0,
    created_at    TIMESTAMPTZ   NOT NULL DEFAULT now(),
    created_by    BIGINT,
    updated_at    TIMESTAMPTZ,
    updated_by    BIGINT,
    CONSTRAINT uq_prep_station_uid         UNIQUE (uid),
    CONSTRAINT uq_prep_station_branch_name UNIQUE (branch_id, name),
    CONSTRAINT fk_prep_station_company     FOREIGN KEY (company_id) REFERENCES companies(id),
    CONSTRAINT fk_prep_station_branch      FOREIGN KEY (branch_id)  REFERENCES branches(id),
    CONSTRAINT chk_prep_station_port       CHECK (printer_port BETWEEN 1 AND 65535),
    CONSTRAINT chk_prep_station_status     CHECK (status IN ('ACTIVE','INACTIVE','ARCHIVED'))
);
-- At most one ACTIVE default (catch-all) station per branch.
CREATE UNIQUE INDEX ux_prep_station_one_default
    ON prep_stations (branch_id) WHERE is_default AND status = 'ACTIVE';
CREATE INDEX ix_prep_stations_company_id ON prep_stations (company_id);
CREATE INDEX ix_prep_stations_branch_id  ON prep_stations (branch_id);

-- (3) prep_station_categories — product category label -> station, per branch
CREATE TABLE prep_station_categories (
    id               BIGSERIAL PRIMARY KEY,
    uid              VARCHAR(26)   NOT NULL,
    company_id       BIGINT        NOT NULL,
    branch_id        BIGINT        NOT NULL,
    prep_station_id  BIGINT        NOT NULL,
    category         VARCHAR(60)   NOT NULL,  -- normalised: trimmed, inner spaces collapsed
    version          BIGINT        NOT NULL DEFAULT 0,
    created_at       TIMESTAMPTZ   NOT NULL DEFAULT now(),
    created_by       BIGINT,
    updated_at       TIMESTAMPTZ,
    updated_by       BIGINT,
    CONSTRAINT uq_prep_station_category_uid     UNIQUE (uid),
    CONSTRAINT fk_prep_station_category_company FOREIGN KEY (company_id)      REFERENCES companies(id),
    CONSTRAINT fk_prep_station_category_branch  FOREIGN KEY (branch_id)       REFERENCES branches(id),
    CONSTRAINT fk_prep_station_category_station FOREIGN KEY (prep_station_id) REFERENCES prep_stations(id)
);
-- A category routes to exactly one station within a branch (case-insensitive).
CREATE UNIQUE INDEX ux_prep_station_category_branch_category
    ON prep_station_categories (branch_id, lower(category));
CREATE INDEX ix_prep_station_categories_station_id ON prep_station_categories (prep_station_id);

-- (4) restaurant_orders — the open check
CREATE TABLE restaurant_orders (
    id                   BIGSERIAL PRIMARY KEY,
    uid                  VARCHAR(26)    NOT NULL,
    company_id           BIGINT         NOT NULL,
    branch_id            BIGINT         NOT NULL,
    order_number         VARCHAR(30)    NOT NULL,
    order_type           VARCHAR(20)    NOT NULL DEFAULT 'DINE_IN',
    restaurant_table_id  BIGINT,
    guest_count          SMALLINT,
    customer_id          BIGINT,
    status               VARCHAR(20)    NOT NULL DEFAULT 'OPEN',
    opened_by            BIGINT         NOT NULL,
    opened_at            TIMESTAMPTZ    NOT NULL DEFAULT now(),
    billed_at            TIMESTAMPTZ,
    billed_total_amount  NUMERIC(19,4),
    bill_print_count     INTEGER        NOT NULL DEFAULT 0,
    paid_at              TIMESTAMPTZ,
    sales_invoice_id     BIGINT,
    sales_invoice_uid    VARCHAR(26),
    cancelled_at         TIMESTAMPTZ,
    cancelled_by         BIGINT,
    cancel_reason        VARCHAR(255),
    notes                VARCHAR(500),
    version              BIGINT         NOT NULL DEFAULT 0,
    created_at           TIMESTAMPTZ    NOT NULL DEFAULT now(),
    created_by           BIGINT,
    updated_at           TIMESTAMPTZ,
    updated_by           BIGINT,
    CONSTRAINT uq_restaurant_order_uid            UNIQUE (uid),
    CONSTRAINT uq_restaurant_order_company_number UNIQUE (company_id, order_number),
    CONSTRAINT fk_restaurant_order_company        FOREIGN KEY (company_id)          REFERENCES companies(id),
    CONSTRAINT fk_restaurant_order_branch         FOREIGN KEY (branch_id)           REFERENCES branches(id),
    CONSTRAINT fk_restaurant_order_table          FOREIGN KEY (restaurant_table_id) REFERENCES restaurant_tables(id),
    CONSTRAINT fk_restaurant_order_customer       FOREIGN KEY (customer_id)         REFERENCES customers(id),
    CONSTRAINT fk_restaurant_order_opened_by      FOREIGN KEY (opened_by)           REFERENCES app_users(id),
    CONSTRAINT fk_restaurant_order_cancelled_by   FOREIGN KEY (cancelled_by)        REFERENCES app_users(id),
    CONSTRAINT fk_restaurant_order_invoice        FOREIGN KEY (sales_invoice_id)    REFERENCES sales_invoices(id),
    CONSTRAINT chk_restaurant_order_type          CHECK (order_type IN ('DINE_IN','TAKEAWAY')),
    CONSTRAINT chk_restaurant_order_status        CHECK (status IN ('OPEN','BILLED','PAID','CANCELLED')),
    CONSTRAINT chk_restaurant_order_table_needed  CHECK (order_type <> 'DINE_IN' OR restaurant_table_id IS NOT NULL),
    CONSTRAINT chk_restaurant_order_guests        CHECK (guest_count IS NULL OR guest_count > 0),
    CONSTRAINT chk_restaurant_order_paid          CHECK (status <> 'PAID' OR (sales_invoice_id IS NOT NULL AND paid_at IS NOT NULL)),
    CONSTRAINT chk_restaurant_order_cancelled     CHECK (status <> 'CANCELLED' OR cancelled_at IS NOT NULL)
);
-- The floor and the open-orders monitor read only live checks.
CREATE INDEX ix_restaurant_orders_branch_live ON restaurant_orders (branch_id, status)
    WHERE status IN ('OPEN','BILLED');
CREATE INDEX ix_restaurant_orders_company_id  ON restaurant_orders (company_id);
CREATE INDEX ix_restaurant_orders_table_id    ON restaurant_orders (restaurant_table_id);
CREATE INDEX ix_restaurant_orders_invoice_id  ON restaurant_orders (sales_invoice_id);

-- (5) restaurant_order_lines
CREATE TABLE restaurant_order_lines (
    id                   BIGSERIAL PRIMARY KEY,
    uid                  VARCHAR(26)    NOT NULL,
    restaurant_order_id  BIGINT         NOT NULL,
    company_id           BIGINT         NOT NULL,
    branch_id            BIGINT         NOT NULL,
    line_no              INTEGER        NOT NULL,
    product_id           BIGINT         NOT NULL,
    unit_id              BIGINT         NOT NULL,
    quantity             NUMERIC(19,4)  NOT NULL,
    note                 VARCHAR(200),
    status               VARCHAR(20)    NOT NULL DEFAULT 'PENDING',
    fire_round           SMALLINT,
    prep_station_id      BIGINT,
    sent_at              TIMESTAMPTZ,
    sent_by              BIGINT,
    voided_at            TIMESTAMPTZ,
    voided_by            BIGINT,
    void_authorised_by   BIGINT,
    void_reason          VARCHAR(255),
    version              BIGINT         NOT NULL DEFAULT 0,
    created_at           TIMESTAMPTZ    NOT NULL DEFAULT now(),
    created_by           BIGINT,
    updated_at           TIMESTAMPTZ,
    updated_by           BIGINT,
    CONSTRAINT uq_restaurant_order_line_uid        UNIQUE (uid),
    CONSTRAINT uq_restaurant_order_line_no         UNIQUE (restaurant_order_id, line_no),
    CONSTRAINT fk_restaurant_order_line_order      FOREIGN KEY (restaurant_order_id) REFERENCES restaurant_orders(id),
    CONSTRAINT fk_restaurant_order_line_product    FOREIGN KEY (product_id)          REFERENCES products(id),
    CONSTRAINT fk_restaurant_order_line_unit       FOREIGN KEY (unit_id)             REFERENCES units_of_measure(id),
    CONSTRAINT fk_restaurant_order_line_station    FOREIGN KEY (prep_station_id)     REFERENCES prep_stations(id),
    CONSTRAINT fk_restaurant_order_line_sent_by    FOREIGN KEY (sent_by)             REFERENCES app_users(id),
    CONSTRAINT fk_restaurant_order_line_voided_by  FOREIGN KEY (voided_by)           REFERENCES app_users(id),
    CONSTRAINT fk_restaurant_order_line_void_auth  FOREIGN KEY (void_authorised_by)  REFERENCES app_users(id),
    CONSTRAINT chk_restaurant_order_line_qty       CHECK (quantity > 0),
    CONSTRAINT chk_restaurant_order_line_status    CHECK (status IN ('PENDING','SENT','VOIDED')),
    -- Only a sent line can be voided (an unsent line is deleted), so SENT and VOIDED both carry the send stamp.
    CONSTRAINT chk_restaurant_order_line_sent      CHECK (status = 'PENDING' OR (fire_round IS NOT NULL AND sent_at IS NOT NULL)),
    CONSTRAINT chk_restaurant_order_line_voided    CHECK (status <> 'VOIDED' OR (voided_at IS NOT NULL AND void_reason IS NOT NULL))
);
CREATE INDEX ix_restaurant_order_lines_order_id ON restaurant_order_lines (restaurant_order_id);
CREATE INDEX ix_restaurant_order_lines_product  ON restaurant_order_lines (product_id);
