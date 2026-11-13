-- ============================================================================
-- V115 — Tenant subscription plans (ADR-0069). DDL approved by the owner 2026-11-13.
--
--   (1) organisation_subscriptions — one row per tenant: plan, add-ons, cap overrides, trial /
--       paid-until dates and the grace / read-only ladder lengths.
--   (2) subscription_payments      — append-only record of payments the operator enters.
--
-- Additive only: two new tables, no change to any existing column or row, no backfill. A tenant
-- with no subscription row stays unrestricted (ADR-0069 D-10), so nothing changes for anyone until
-- the platform operator assigns a plan. Both FKs reference organisations (a handful of rows); the
-- lock timeout makes a held lock fail fast instead of queueing.
-- ============================================================================

SET LOCAL lock_timeout = '10s';   -- the FKs below lock organisations briefly

CREATE TABLE organisation_subscriptions (
    id                      BIGSERIAL PRIMARY KEY,
    uid                     VARCHAR(26)  NOT NULL,
    organisation_id         BIGINT       NOT NULL,
    plan_code               VARCHAR(20)  NOT NULL,           -- BASIC | STANDARD | PREMIUM | ENTERPRISE
    addon_restaurant        BOOLEAN      NOT NULL DEFAULT false,
    addon_orbixhq           BOOLEAN      NOT NULL DEFAULT false,
    max_users_override      INTEGER,                         -- NULL = the plan's cap
    max_branches_override   INTEGER,
    max_tills_override      INTEGER,
    max_companies_override  INTEGER,
    billing_cycle           VARCHAR(20)  NOT NULL DEFAULT 'MONTHLY',
    trial_ends_on           DATE,
    paid_until              DATE,
    grace_days              INTEGER      NOT NULL DEFAULT 7,
    read_only_days          INTEGER      NOT NULL DEFAULT 7,
    notes                   VARCHAR(500),
    version                 BIGINT       NOT NULL DEFAULT 0,
    created_at              TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_by              BIGINT,
    updated_at              TIMESTAMPTZ,
    updated_by              BIGINT,
    CONSTRAINT uq_org_subscription_uid  UNIQUE (uid),
    CONSTRAINT uq_org_subscription_org  UNIQUE (organisation_id),
    CONSTRAINT fk_org_subscription_org  FOREIGN KEY (organisation_id) REFERENCES organisations(id),
    CONSTRAINT chk_org_subscription_plan
        CHECK (plan_code IN ('BASIC','STANDARD','PREMIUM','ENTERPRISE')),
    CONSTRAINT chk_org_subscription_cycle
        CHECK (billing_cycle IN ('MONTHLY','QUARTERLY','HALF_YEARLY','ANNUAL','CUSTOM')),
    CONSTRAINT chk_org_subscription_overrides CHECK (
        (max_users_override     IS NULL OR max_users_override     >= 0) AND
        (max_branches_override  IS NULL OR max_branches_override  >= 0) AND
        (max_tills_override     IS NULL OR max_tills_override     >= 0) AND
        (max_companies_override IS NULL OR max_companies_override >= 0)),
    CONSTRAINT chk_org_subscription_ladder
        CHECK (grace_days BETWEEN 0 AND 365 AND read_only_days BETWEEN 0 AND 365)
);

-- Append-only: a correction is a new row, never an edit.
CREATE TABLE subscription_payments (
    id               BIGSERIAL PRIMARY KEY,
    uid              VARCHAR(26)   NOT NULL,
    organisation_id  BIGINT        NOT NULL,
    amount           NUMERIC(19,2) NOT NULL,
    currency_code    VARCHAR(3)    NOT NULL,
    paid_on          DATE          NOT NULL,
    paid_until       DATE          NOT NULL,                -- the subscription's new paid-until
    reference        VARCHAR(100),
    note             VARCHAR(500),
    created_at       TIMESTAMPTZ   NOT NULL DEFAULT now(),
    created_by       BIGINT,
    CONSTRAINT uq_subscription_payment_uid UNIQUE (uid),
    CONSTRAINT fk_subscription_payment_org FOREIGN KEY (organisation_id) REFERENCES organisations(id),
    CONSTRAINT chk_subscription_payment_amount CHECK (amount >= 0)
);
CREATE INDEX ix_subscription_payments_org ON subscription_payments (organisation_id, paid_on DESC);
