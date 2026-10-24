-- ============================================================================
-- Approved by the owner 2026-10-24. See ADR-0066 (Appendix A).
--
-- Restaurant phase 3: modifiers (priced add-ons become child order lines), courses with
-- hold-and-fire, and the kitchen display's "ready" stamp.
--
-- SAFE ON A POPULATED DATABASE: three NEW tables; on restaurant_order_lines (created by V107,
-- shipped in the same release) only nullable columns or constant defaults - no table rewrite.
-- ============================================================================

-- Flyway runs this migration in one transaction. The foreign keys below take SHARE ROW EXCLUSIVE
-- on the tables they reference; if something is holding a write open on one of them, fail fast
-- (the whole migration rolls back) instead of queueing every till behind it. lock_timeout bounds
-- each statement; retry the deploy if it trips.
SET LOCAL lock_timeout = '10s';

-- V108 — Restaurant phase 3 (ADR-0066): modifier groups/options, the item → group link, and on
-- restaurant_order_lines: child lines for priced add-ons, course + hold, and the kitchen "ready" stamp.
-- Additive only. restaurant_order_lines was created by V107; every column added here is nullable or
-- carries a constant default (metadata-only in PostgreSQL 11+, no table rewrite).

-- (1) modifier_groups — a choice offered on menu items
CREATE TABLE modifier_groups (
    id           BIGSERIAL PRIMARY KEY,
    uid          VARCHAR(26)  NOT NULL,
    company_id   BIGINT       NOT NULL,
    name         VARCHAR(60)  NOT NULL,
    min_select   SMALLINT     NOT NULL DEFAULT 0,
    max_select   SMALLINT,                       -- null = no upper limit
    sort_order   INTEGER      NOT NULL DEFAULT 0,
    status       VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE',
    version      BIGINT       NOT NULL DEFAULT 0,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_by   BIGINT,
    updated_at   TIMESTAMPTZ,
    updated_by   BIGINT,
    CONSTRAINT uq_modifier_group_uid          UNIQUE (uid),
    CONSTRAINT uq_modifier_group_company_name UNIQUE (company_id, name),
    CONSTRAINT fk_modifier_group_company      FOREIGN KEY (company_id) REFERENCES companies(id),
    CONSTRAINT chk_modifier_group_min         CHECK (min_select >= 0),
    CONSTRAINT chk_modifier_group_max         CHECK (max_select IS NULL OR max_select >= GREATEST(min_select, 1)),
    CONSTRAINT chk_modifier_group_status      CHECK (status IN ('ACTIVE','INACTIVE','ARCHIVED'))
);
CREATE INDEX ix_modifier_groups_company_id ON modifier_groups (company_id);

-- (2) modifier_options — one choice; with a product it is a priced add-on (a child line)
CREATE TABLE modifier_options (
    id                 BIGSERIAL PRIMARY KEY,
    uid                VARCHAR(26)  NOT NULL,
    company_id         BIGINT       NOT NULL,
    modifier_group_id  BIGINT       NOT NULL,
    label              VARCHAR(60)  NOT NULL,
    product_id         BIGINT,                   -- null = an instruction only, added to the note
    sort_order         INTEGER      NOT NULL DEFAULT 0,
    status             VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE',
    version            BIGINT       NOT NULL DEFAULT 0,
    created_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_by         BIGINT,
    updated_at         TIMESTAMPTZ,
    updated_by         BIGINT,
    CONSTRAINT uq_modifier_option_uid         UNIQUE (uid),
    CONSTRAINT uq_modifier_option_group_label UNIQUE (modifier_group_id, label),
    CONSTRAINT fk_modifier_option_company     FOREIGN KEY (company_id)        REFERENCES companies(id),
    CONSTRAINT fk_modifier_option_group       FOREIGN KEY (modifier_group_id) REFERENCES modifier_groups(id),
    CONSTRAINT fk_modifier_option_product     FOREIGN KEY (product_id)        REFERENCES products(id),
    CONSTRAINT chk_modifier_option_status     CHECK (status IN ('ACTIVE','INACTIVE','ARCHIVED'))
);
CREATE INDEX ix_modifier_options_group_id ON modifier_options (modifier_group_id);

-- (3) product_modifier_group — which groups a menu item offers (junction, singular)
CREATE TABLE product_modifier_group (
    id                 BIGSERIAL PRIMARY KEY,
    company_id         BIGINT       NOT NULL,
    product_id         BIGINT       NOT NULL,
    modifier_group_id  BIGINT       NOT NULL,
    sort_order         INTEGER      NOT NULL DEFAULT 0,
    created_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_by         BIGINT,
    CONSTRAINT uq_product_modifier_group          UNIQUE (product_id, modifier_group_id),
    CONSTRAINT fk_product_modifier_group_company  FOREIGN KEY (company_id)        REFERENCES companies(id),
    CONSTRAINT fk_product_modifier_group_product  FOREIGN KEY (product_id)        REFERENCES products(id),
    CONSTRAINT fk_product_modifier_group_group    FOREIGN KEY (modifier_group_id) REFERENCES modifier_groups(id)
);
CREATE INDEX ix_product_modifier_group_group ON product_modifier_group (modifier_group_id);

-- (4) restaurant_order_lines — child lines, course + hold, kitchen ready stamp
ALTER TABLE restaurant_order_lines ADD COLUMN parent_line_id BIGINT;
ALTER TABLE restaurant_order_lines ADD COLUMN course         SMALLINT NOT NULL DEFAULT 1;
ALTER TABLE restaurant_order_lines ADD COLUMN held           BOOLEAN  NOT NULL DEFAULT FALSE;
ALTER TABLE restaurant_order_lines ADD COLUMN ready_at       TIMESTAMPTZ;
ALTER TABLE restaurant_order_lines ADD COLUMN ready_by       BIGINT;

ALTER TABLE restaurant_order_lines
    ADD CONSTRAINT fk_restaurant_order_line_parent   FOREIGN KEY (parent_line_id) REFERENCES restaurant_order_lines(id);
ALTER TABLE restaurant_order_lines
    ADD CONSTRAINT fk_restaurant_order_line_ready_by FOREIGN KEY (ready_by)       REFERENCES app_users(id);
ALTER TABLE restaurant_order_lines
    ADD CONSTRAINT chk_restaurant_order_line_course  CHECK (course BETWEEN 1 AND 9);
-- Only something already sent can be ready.
ALTER TABLE restaurant_order_lines
    ADD CONSTRAINT chk_restaurant_order_line_ready   CHECK (ready_at IS NULL OR status <> 'PENDING');

-- restaurant_order_lines exists only since V107 and is tiny at this point, so a plain index is safe here.
CREATE INDEX ix_restaurant_order_lines_parent ON restaurant_order_lines (parent_line_id);
