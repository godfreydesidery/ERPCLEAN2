-- V23 — Document / PDF Generation (ADR-0023):
-- (1) document_branding — per-company branding profile (D-2)
-- (2) document_templates — per-company renderable-type registry (D-3)
-- (3) generated_documents — append-only render log (D-4)
-- (4) permission seed + ORG_ADMIN grant (D-10)
-- Additive only. V1–V19 are FROZEN.
-- References only frozen V1 tables: companies, app_users, roles, permissions, role_permission.
-- Order-independent of V20–V22 (those are concurrent modules; V23 references no V20–V22 tables).

-- ============================================================================
-- (1) document_branding
-- ============================================================================

CREATE TABLE document_branding (
    id               BIGSERIAL PRIMARY KEY,
    uid              VARCHAR(26)      NOT NULL,
    company_id       BIGINT           NOT NULL,
    display_name     VARCHAR(160)     NOT NULL,
    legal_name       VARCHAR(200),
    tax_id           VARCHAR(60),
    address_line1    VARCHAR(200),
    address_line2    VARCHAR(200),
    city             VARCHAR(120),
    region           VARCHAR(120),
    country          VARCHAR(120),
    postal_code      VARCHAR(40),
    contact_phone    VARCHAR(160),
    contact_email    VARCHAR(160),
    website          VARCHAR(200),
    logo_ref         VARCHAR(500),
    footer_terms     VARCHAR(1000),
    bank_details     VARCHAR(500),
    version          BIGINT           NOT NULL DEFAULT 0,
    created_at       TIMESTAMPTZ      NOT NULL DEFAULT now(),
    created_by       BIGINT,
    updated_at       TIMESTAMPTZ,
    updated_by       BIGINT,
    CONSTRAINT uq_document_branding_uid     UNIQUE (uid),
    CONSTRAINT uq_document_branding_company UNIQUE (company_id),
    CONSTRAINT fk_document_branding_company FOREIGN KEY (company_id) REFERENCES companies(id)
);

CREATE INDEX ix_document_brandings_company ON document_branding (company_id);

-- ============================================================================
-- (2) document_templates
-- ============================================================================

CREATE TABLE document_templates (
    id                  BIGSERIAL PRIMARY KEY,
    uid                 VARCHAR(26)      NOT NULL,
    company_id          BIGINT           NOT NULL,
    document_type       VARCHAR(30)      NOT NULL,
    renderer_key        VARCHAR(40)      NOT NULL,
    branding_id         BIGINT,
    title               VARCHAR(120)     NOT NULL,
    status              VARCHAR(32)      NOT NULL DEFAULT 'ACTIVE',
    status_changed_at   TIMESTAMPTZ,
    status_changed_by   BIGINT,
    version             BIGINT           NOT NULL DEFAULT 0,
    created_at          TIMESTAMPTZ      NOT NULL DEFAULT now(),
    created_by          BIGINT,
    updated_at          TIMESTAMPTZ,
    updated_by          BIGINT,
    CONSTRAINT uq_document_template_uid          UNIQUE (uid),
    CONSTRAINT uq_document_template_company_type UNIQUE (company_id, document_type),
    CONSTRAINT fk_document_template_company      FOREIGN KEY (company_id)  REFERENCES companies(id),
    CONSTRAINT fk_document_template_branding     FOREIGN KEY (branding_id) REFERENCES document_branding(id),
    CONSTRAINT chk_document_template_type CHECK (document_type IN (
        'INVOICE','AR_STATEMENT','PURCHASE_ORDER','GOODS_RECEIPT',
        'DELIVERY_NOTE','CREDIT_NOTE','PAYSLIP','QUOTATION','DEBIT_NOTE'
    )),
    CONSTRAINT chk_document_template_renderer CHECK (renderer_key IN (
        'TRANSACTIONAL_PDF','STATEMENT_PDF'
    )),
    CONSTRAINT chk_document_template_status CHECK (status IN ('ACTIVE','INACTIVE','ARCHIVED'))
);

CREATE INDEX ix_document_templates_company ON document_templates (company_id);

-- ============================================================================
-- (3) generated_documents (append-only render log)
-- ============================================================================

CREATE TABLE generated_documents (
    id               BIGSERIAL PRIMARY KEY,
    uid              VARCHAR(26)      NOT NULL,
    version          BIGINT       NOT NULL DEFAULT 0,
    company_id       BIGINT           NOT NULL,
    branch_id        BIGINT,
    document_number  VARCHAR(30)      NOT NULL,
    document_type    VARCHAR(30)      NOT NULL,
    source_type      VARCHAR(30)      NOT NULL,
    source_uid       VARCHAR(26),
    source_params    JSONB,
    branding_id      BIGINT,
    content_hash     VARCHAR(64),
    byte_size        INTEGER,
    mime_type        VARCHAR(80)      NOT NULL DEFAULT 'application/pdf',
    generated_by     BIGINT           NOT NULL,
    generated_at     TIMESTAMPTZ      NOT NULL DEFAULT now(),
    created_at       TIMESTAMPTZ      NOT NULL DEFAULT now(),
    created_by       BIGINT,
    CONSTRAINT uq_generated_document_uid            UNIQUE (uid),
    CONSTRAINT uq_generated_document_company_number UNIQUE (company_id, document_number),
    CONSTRAINT fk_generated_document_company        FOREIGN KEY (company_id)  REFERENCES companies(id),
    CONSTRAINT fk_generated_document_branding       FOREIGN KEY (branding_id) REFERENCES document_branding(id),
    CONSTRAINT chk_generated_document_type CHECK (document_type IN (
        'INVOICE','AR_STATEMENT','PURCHASE_ORDER','GOODS_RECEIPT',
        'DELIVERY_NOTE','CREDIT_NOTE','PAYSLIP','QUOTATION','DEBIT_NOTE'
    )),
    CONSTRAINT chk_generated_document_source CHECK (
        source_uid IS NOT NULL OR source_params IS NOT NULL
    )
);

CREATE INDEX ix_generated_documents_company_type  ON generated_documents (company_id, document_type);
CREATE INDEX ix_generated_documents_source        ON generated_documents (source_type, source_uid);
CREATE INDEX ix_generated_documents_generated_at  ON generated_documents (company_id, generated_at);
