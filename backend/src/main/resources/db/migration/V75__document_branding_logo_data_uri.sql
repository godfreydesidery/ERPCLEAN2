-- V75: company logo for printed document headers.
-- Stored as a base64 data URI (e.g. data:image/png;base64,...) so the web preview and the
-- API transport are a single string; the PDF renderer decodes and embeds it.
-- Additive + nullable: no backfill, no seed. The logo is a normal runtime upload.
-- Capped at ~72,000 chars (~70 KB base64 ~= a ~52 KB image — a small ~2x2 cm, low-res logo).
ALTER TABLE document_branding
    ADD COLUMN logo_data_uri TEXT;

ALTER TABLE document_branding
    ADD CONSTRAINT ck_document_branding_logo_size
    CHECK (logo_data_uri IS NULL OR char_length(logo_data_uri) <= 72000);
