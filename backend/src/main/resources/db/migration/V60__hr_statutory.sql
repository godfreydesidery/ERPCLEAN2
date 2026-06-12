-- V60 — HR & Payroll: statutory rate tables + default TZ rate seeds (ADR-0032 D-3)
-- Creates: paye_band_sets, paye_bands, statutory_rate_sets
-- Seeds default TZ 2025/26 rates for every existing company (#12-safe uids).
-- New companies get these via HrStatutorySeeder.
-- Additive only. V1–V59 FROZEN.

-- ============================================================================
-- paye_band_sets (header)
-- ============================================================================
CREATE TABLE paye_band_sets (
    id                  BIGSERIAL     PRIMARY KEY,
    uid                 VARCHAR(26)   NOT NULL,
    company_id          BIGINT        NOT NULL,
    effective_from      DATE          NOT NULL,
    tax_free_threshold  NUMERIC(19,4) NOT NULL,
    description         VARCHAR(160),
    version             BIGINT        NOT NULL DEFAULT 0,
    created_at          TIMESTAMPTZ   NOT NULL DEFAULT now(),
    created_by          BIGINT,
    updated_at          TIMESTAMPTZ,
    updated_by          BIGINT,
    CONSTRAINT uq_paye_band_set_uid               UNIQUE (uid),
    CONSTRAINT uq_paye_band_set_company_effective UNIQUE (company_id, effective_from),
    CONSTRAINT chk_paye_band_set_threshold        CHECK  (tax_free_threshold >= 0),
    CONSTRAINT fk_paye_band_set_company           FOREIGN KEY (company_id) REFERENCES companies(id)
);

CREATE INDEX ix_paye_band_sets_company_eff ON paye_band_sets (company_id, effective_from DESC);

-- ============================================================================
-- paye_bands (child — ordered marginal bands)
-- ============================================================================
CREATE TABLE paye_bands (
    id                  BIGSERIAL     PRIMARY KEY,
    uid                 VARCHAR(26)   NOT NULL,
    paye_band_set_id    BIGINT        NOT NULL,
    company_id          BIGINT        NOT NULL,
    band_no             SMALLINT      NOT NULL,
    lower_bound         NUMERIC(19,4) NOT NULL,
    marginal_rate       NUMERIC(9,4)  NOT NULL,
    cumulative_fixed_tax NUMERIC(19,4) NOT NULL,
    version             BIGINT        NOT NULL DEFAULT 0,
    created_at          TIMESTAMPTZ   NOT NULL DEFAULT now(),
    created_by          BIGINT,
    CONSTRAINT uq_paye_band_uid        UNIQUE (uid),
    CONSTRAINT uq_paye_band_set_no     UNIQUE (paye_band_set_id, band_no),
    CONSTRAINT chk_paye_band_rate      CHECK  (marginal_rate BETWEEN 0 AND 100),
    CONSTRAINT chk_paye_band_lower     CHECK  (lower_bound >= 0),
    CONSTRAINT chk_paye_band_cum_tax   CHECK  (cumulative_fixed_tax >= 0),
    CONSTRAINT fk_paye_band_set        FOREIGN KEY (paye_band_set_id) REFERENCES paye_band_sets(id),
    CONSTRAINT fk_paye_band_company    FOREIGN KEY (company_id)        REFERENCES companies(id)
);

CREATE INDEX ix_paye_bands_set ON paye_bands (paye_band_set_id, band_no);

-- ============================================================================
-- statutory_rate_sets (NSSF / WCF / SDL / HESLB — one row per type per eff date)
-- ============================================================================
CREATE TABLE statutory_rate_sets (
    id                   BIGSERIAL    PRIMARY KEY,
    uid                  VARCHAR(26)  NOT NULL,
    company_id           BIGINT       NOT NULL,
    rate_type            VARCHAR(10)  NOT NULL,
    effective_from       DATE         NOT NULL,
    employee_rate        NUMERIC(9,4),
    employer_rate        NUMERIC(9,4),
    basis                VARCHAR(16)  NOT NULL,
    ceiling_amount       NUMERIC(19,4),
    headcount_threshold  SMALLINT,
    active               BOOLEAN      NOT NULL DEFAULT true,
    description          VARCHAR(160),
    version              BIGINT       NOT NULL DEFAULT 0,
    created_at           TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_by           BIGINT,
    updated_at           TIMESTAMPTZ,
    updated_by           BIGINT,
    CONSTRAINT uq_statutory_rate_set_uid                    UNIQUE (uid),
    CONSTRAINT uq_statutory_rate_set_company_type_eff       UNIQUE (company_id, rate_type, effective_from),
    CONSTRAINT chk_statutory_rate_set_type    CHECK (rate_type IN ('NSSF','WCF','SDL','HESLB')),
    CONSTRAINT chk_statutory_rate_set_basis   CHECK (basis     IN ('GROSS','PENSIONABLE','BASIC')),
    CONSTRAINT chk_statutory_rate_set_ee_rate CHECK (employee_rate IS NULL OR employee_rate BETWEEN 0 AND 100),
    CONSTRAINT chk_statutory_rate_set_er_rate CHECK (employer_rate IS NULL OR employer_rate BETWEEN 0 AND 100),
    CONSTRAINT fk_statutory_rate_set_company  FOREIGN KEY (company_id) REFERENCES companies(id)
);

CREATE INDEX ix_statutory_rate_sets_company_type_eff
    ON statutory_rate_sets (company_id, rate_type, effective_from DESC);

-- ============================================================================
-- Seed default TZ 2025/26 PAYE band sets + bands per existing company (#12-safe uids)
-- uid for band_set: 'PBS' || lpad(company_id,6,'0') || substr(md5('2025-07-01'),1,12) = 3+6+12=21
-- uid for bands:   'PBD' || lpad(company_id,6,'0') || substr(md5('B' || band_no::text),1,12) = 21
-- ============================================================================
INSERT INTO paye_band_sets (uid, company_id, effective_from, tax_free_threshold, description, version, created_at)
SELECT 'PBS' || lpad(c.id::text, 6, '0') || substr(md5('2025-07-01'), 1, 12),
       c.id,
       '2025-07-01',
       270000.0000,
       'TZ PAYE Bands FY 2025/26 (seed, owner-confirmable)',
       0,
       now()
FROM companies c
ON CONFLICT (company_id, effective_from) DO NOTHING;

-- Band 1: 270,000 @ 8% (tax-free threshold = 270,000)
INSERT INTO paye_bands (uid, paye_band_set_id, company_id, band_no, lower_bound, marginal_rate, cumulative_fixed_tax, version, created_at)
SELECT 'PBD' || lpad(c.id::text, 6, '0') || substr(md5('B1'), 1, 12),
       pbs.id, c.id, 1, 270000.0000, 8.0000, 0.0000, 0, now()
FROM companies c
JOIN paye_band_sets pbs ON pbs.company_id = c.id AND pbs.effective_from = '2025-07-01'
ON CONFLICT (paye_band_set_id, band_no) DO NOTHING;

-- Band 2: 520,000 @ 20%, cumulative 20,000
INSERT INTO paye_bands (uid, paye_band_set_id, company_id, band_no, lower_bound, marginal_rate, cumulative_fixed_tax, version, created_at)
SELECT 'PBD' || lpad(c.id::text, 6, '0') || substr(md5('B2'), 1, 12),
       pbs.id, c.id, 2, 520000.0000, 20.0000, 20000.0000, 0, now()
FROM companies c
JOIN paye_band_sets pbs ON pbs.company_id = c.id AND pbs.effective_from = '2025-07-01'
ON CONFLICT (paye_band_set_id, band_no) DO NOTHING;

-- Band 3: 760,000 @ 25%, cumulative 68,000
INSERT INTO paye_bands (uid, paye_band_set_id, company_id, band_no, lower_bound, marginal_rate, cumulative_fixed_tax, version, created_at)
SELECT 'PBD' || lpad(c.id::text, 6, '0') || substr(md5('B3'), 1, 12),
       pbs.id, c.id, 3, 760000.0000, 25.0000, 68000.0000, 0, now()
FROM companies c
JOIN paye_band_sets pbs ON pbs.company_id = c.id AND pbs.effective_from = '2025-07-01'
ON CONFLICT (paye_band_set_id, band_no) DO NOTHING;

-- Band 4: 1,000,000 @ 30%, cumulative 128,000
INSERT INTO paye_bands (uid, paye_band_set_id, company_id, band_no, lower_bound, marginal_rate, cumulative_fixed_tax, version, created_at)
SELECT 'PBD' || lpad(c.id::text, 6, '0') || substr(md5('B4'), 1, 12),
       pbs.id, c.id, 4, 1000000.0000, 30.0000, 128000.0000, 0, now()
FROM companies c
JOIN paye_band_sets pbs ON pbs.company_id = c.id AND pbs.effective_from = '2025-07-01'
ON CONFLICT (paye_band_set_id, band_no) DO NOTHING;

-- ============================================================================
-- Seed default statutory rate sets per existing company (#12-safe uids)
-- uid: 'SRS' || lpad(company_id,6,'0') || substr(md5(rate_type),1,12) = 3+6+12=21
-- ============================================================================
-- NSSF: employee 10%, employer 10%, basis PENSIONABLE, no ceiling
INSERT INTO statutory_rate_sets (uid, company_id, rate_type, effective_from, employee_rate, employer_rate,
                                  basis, ceiling_amount, headcount_threshold, active, description, version, created_at)
SELECT 'SRS' || lpad(c.id::text, 6, '0') || substr(md5('NSSF'), 1, 12),
       c.id, 'NSSF', '2025-07-01', 10.0000, 10.0000,
       'PENSIONABLE', NULL, NULL, true, 'TZ NSSF 10%/10% (seed, owner-confirmable)', 0, now()
FROM companies c
ON CONFLICT (company_id, rate_type, effective_from) DO NOTHING;

-- WCF: employer 0.5%, basis GROSS (private sector — owner-confirmable)
INSERT INTO statutory_rate_sets (uid, company_id, rate_type, effective_from, employee_rate, employer_rate,
                                  basis, ceiling_amount, headcount_threshold, active, description, version, created_at)
SELECT 'SRS' || lpad(c.id::text, 6, '0') || substr(md5('WCF'), 1, 12),
       c.id, 'WCF', '2025-07-01', NULL, 0.5000,
       'GROSS', NULL, NULL, true, 'TZ WCF 0.5% employer (seed, owner-confirmable)', 0, now()
FROM companies c
ON CONFLICT (company_id, rate_type, effective_from) DO NOTHING;

-- SDL: employer 3.5%, basis GROSS, headcount_threshold 10 (owner-confirmable rate/threshold)
INSERT INTO statutory_rate_sets (uid, company_id, rate_type, effective_from, employee_rate, employer_rate,
                                  basis, ceiling_amount, headcount_threshold, active, description, version, created_at)
SELECT 'SRS' || lpad(c.id::text, 6, '0') || substr(md5('SDL'), 1, 12),
       c.id, 'SDL', '2025-07-01', NULL, 3.5000,
       'GROSS', NULL, 10, true, 'TZ SDL 3.5% employer headcount>=10 (seed, owner-confirmable)', 0, now()
FROM companies c
ON CONFLICT (company_id, rate_type, effective_from) DO NOTHING;

-- HESLB: employee 15%, basis BASIC (owner-confirmable; active=false by default — opt-in per borrower)
INSERT INTO statutory_rate_sets (uid, company_id, rate_type, effective_from, employee_rate, employer_rate,
                                  basis, ceiling_amount, headcount_threshold, active, description, version, created_at)
SELECT 'SRS' || lpad(c.id::text, 6, '0') || substr(md5('HESLB'), 1, 12),
       c.id, 'HESLB', '2025-07-01', 15.0000, NULL,
       'BASIC', NULL, NULL, true, 'TZ HESLB 15% of basic (seed, owner-confirmable)', 0, now()
FROM companies c
ON CONFLICT (company_id, rate_type, effective_from) DO NOTHING;
