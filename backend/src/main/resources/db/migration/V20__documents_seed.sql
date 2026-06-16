-- V24 — Document branding + template registry backfill for existing companies (ADR-0023 D-12).
-- Idempotent: uses NOT IN / NOT EXISTS guards + ON CONFLICT DO NOTHING.
-- #12-safe seed-uids: 'XX' || lpad(company_id::text,6,'0') || substr(md5(stable-key),1,12) ≤ 26 chars.
-- The DOCUMENT code_sequence kind is lazy — NOT seeded here (first use creates it, D-8).

-- ============================================================================
-- (5) Backfill document_branding — one row per existing company (D-2 / BR-DOC-06)
-- uid: 'DB' || lpad(id,6,'0') || substr(md5('branding'),1,12) = 2+6+12 = 20 chars
-- ============================================================================

INSERT INTO document_branding (uid, company_id, display_name, legal_name, tax_id, created_at)
SELECT
    'DB' || lpad(c.id::text, 6, '0') || substr(md5('branding'), 1, 12),
    c.id,
    c.name,
    c.legal_name,
    c.tax_id,
    now()
FROM companies c
WHERE c.id NOT IN (SELECT company_id FROM document_branding)
ON CONFLICT (company_id) DO NOTHING;

-- ============================================================================
-- (6) Backfill document_templates — six v1 types per existing company (D-3 / FR-DOC-02)
-- uid: 'DT' || lpad(id,6,'0') || substr(md5('tmpl:' || doc_type),1,12) = 2+6+12 = 20 chars
-- branding_id = the company's document_branding.id (or NULL = use default)
-- ============================================================================

INSERT INTO document_templates (
    uid, company_id, document_type, renderer_key, branding_id, title, status, created_at
)
SELECT
    'DT' || lpad(c.id::text, 6, '0') || substr(md5('tmpl:' || dt.document_type), 1, 12),
    c.id,
    dt.document_type,
    dt.renderer_key,
    db.id,
    dt.title,
    'ACTIVE',
    now()
FROM companies c
CROSS JOIN (VALUES
    ('INVOICE',        'TRANSACTIONAL_PDF', 'TAX INVOICE'),
    ('AR_STATEMENT',   'STATEMENT_PDF',     'CUSTOMER STATEMENT'),
    ('PURCHASE_ORDER', 'TRANSACTIONAL_PDF', 'PURCHASE ORDER'),
    ('GOODS_RECEIPT',  'TRANSACTIONAL_PDF', 'GOODS RECEIVED NOTE'),
    ('DELIVERY_NOTE',  'TRANSACTIONAL_PDF', 'DELIVERY NOTE'),
    ('CREDIT_NOTE',    'TRANSACTIONAL_PDF', 'CREDIT NOTE')
) AS dt(document_type, renderer_key, title)
LEFT JOIN document_branding db ON db.company_id = c.id
WHERE NOT EXISTS (
    SELECT 1 FROM document_templates t
    WHERE t.company_id = c.id AND t.document_type = dt.document_type
)
ON CONFLICT (company_id, document_type) DO NOTHING;
