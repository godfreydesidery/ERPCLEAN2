-- V25 — Notifications framework (ADR-0024, additive on frozen V1–V19, in-flight V20–V24).
-- Five tables + per-company type-catalogue seed + permissions + ORG_ADMIN/operational grants.
-- No GL posting (BR-NOTIF-13). ISSUES-REGISTER #12-safe per-company seed uids.
-- Order: notification_types → notifications → notification_preferences →
--        notification_deliveries → notification_scan_markers →
--        per-company type seed → permissions + grants.

-- ============================================================
-- 1. notification_types (catalogue — seeded, per company)
-- ============================================================
CREATE TABLE notification_types (
    id                  BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    uid                 VARCHAR(26)     NOT NULL,
    company_id          BIGINT          NOT NULL,
    type_key            VARCHAR(40)     NOT NULL,
    display_name        VARCHAR(120)    NOT NULL,
    description         VARCHAR(300),
    audience_permission VARCHAR(60)     NOT NULL,
    branch_scoped       BOOLEAN         NOT NULL DEFAULT false,
    default_channels    VARCHAR(60)     NOT NULL,
    severity            VARCHAR(10)     NOT NULL,
    title_template      VARCHAR(200)    NOT NULL,
    body_template       VARCHAR(1000)   NOT NULL,
    link_route_template VARCHAR(200)    NOT NULL,
    attachment_ref      VARCHAR(60),
    company_enabled     BOOLEAN         NOT NULL DEFAULT true,
    status              VARCHAR(32)     NOT NULL DEFAULT 'ACTIVE',
    version             BIGINT          NOT NULL DEFAULT 0,
    created_at          TIMESTAMPTZ     NOT NULL DEFAULT now(),
    created_by          BIGINT,
    updated_at          TIMESTAMPTZ,
    updated_by          BIGINT,

    CONSTRAINT uq_notification_type_uid         UNIQUE (uid),
    CONSTRAINT uq_notification_type_company_key UNIQUE (company_id, type_key),
    CONSTRAINT fk_notification_type_company     FOREIGN KEY (company_id) REFERENCES companies(id),
    CONSTRAINT chk_notification_type_key        CHECK (type_key IN (
        'LOW_STOCK','INVOICE_OVERDUE','PAYMENT_RECEIVED','APPROVAL_PENDING',
        'GOODS_RECEIVED','DELIVERY_CONFIRMED'
    )),
    CONSTRAINT chk_notification_type_severity   CHECK (severity IN ('INFO','WARNING','CRITICAL')),
    CONSTRAINT chk_notification_type_channels   CHECK (default_channels <> '')
);

CREATE INDEX ix_notification_types_company ON notification_types (company_id);

-- ============================================================
-- 2. notifications (the message — one per recipient × channel)
-- ============================================================
CREATE TABLE notifications (
    id                      BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    uid                     VARCHAR(26)     NOT NULL,
    company_id              BIGINT          NOT NULL,
    branch_id               BIGINT,
    notification_type_id    BIGINT          NOT NULL,
    type_key                VARCHAR(40)     NOT NULL,
    recipient_user_id       BIGINT          NOT NULL,
    channel                 VARCHAR(10)     NOT NULL,
    severity                VARCHAR(10)     NOT NULL,
    title                   VARCHAR(200)    NOT NULL,
    body                    VARCHAR(1000)   NOT NULL,
    source_aggregate_type   VARCHAR(40),
    source_uid              VARCHAR(26),
    link_route              VARCHAR(200),
    trigger_key             VARCHAR(80)     NOT NULL,
    is_read                 BOOLEAN         NOT NULL DEFAULT false,
    read_at                 TIMESTAMPTZ,
    version                 BIGINT          NOT NULL DEFAULT 0,
    created_at              TIMESTAMPTZ     NOT NULL DEFAULT now(),
    created_by              BIGINT,

    CONSTRAINT uq_notification_uid                      UNIQUE (uid),
    CONSTRAINT uq_notification_trigger_recipient_channel UNIQUE (company_id, trigger_key, recipient_user_id, channel),
    CONSTRAINT fk_notification_company                  FOREIGN KEY (company_id) REFERENCES companies(id),
    CONSTRAINT fk_notification_type                     FOREIGN KEY (notification_type_id) REFERENCES notification_types(id),
    CONSTRAINT fk_notification_recipient                FOREIGN KEY (recipient_user_id) REFERENCES app_users(id),
    CONSTRAINT chk_notification_channel                 CHECK (channel IN ('IN_APP','EMAIL','SMS','PUSH','WEBHOOK')),
    CONSTRAINT chk_notification_severity                CHECK (severity IN ('INFO','WARNING','CRITICAL'))
);

CREATE INDEX ix_notifications_recipient_company ON notifications (recipient_user_id, company_id, created_at DESC);
CREATE INDEX ix_notifications_unread            ON notifications (recipient_user_id, company_id) WHERE is_read = false AND channel = 'IN_APP';
CREATE INDEX ix_notifications_company_type      ON notifications (company_id, type_key);

-- ============================================================
-- 3. notification_preferences (per user, per company, per type)
-- ============================================================
CREATE TABLE notification_preferences (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    uid             VARCHAR(26)     NOT NULL,
    company_id      BIGINT          NOT NULL,
    user_id         BIGINT          NOT NULL,
    type_key        VARCHAR(40)     NOT NULL,
    muted           BOOLEAN         NOT NULL DEFAULT false,
    channels_enabled VARCHAR(60),
    version         BIGINT          NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ     NOT NULL DEFAULT now(),
    created_by      BIGINT,
    updated_at      TIMESTAMPTZ,
    updated_by      BIGINT,

    CONSTRAINT uq_notification_preference_uid               UNIQUE (uid),
    CONSTRAINT uq_notification_preference_user_company_type UNIQUE (company_id, user_id, type_key),
    CONSTRAINT fk_notification_preference_user              FOREIGN KEY (user_id) REFERENCES app_users(id)
);

CREATE INDEX ix_notification_preferences_user_company ON notification_preferences (user_id, company_id);

-- ============================================================
-- 4. notification_deliveries (append-only delivery log)
-- ============================================================
CREATE TABLE notification_deliveries (
    id                  BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    uid                 VARCHAR(26)     NOT NULL,
    notification_id     BIGINT,
    company_id          BIGINT          NOT NULL,
    type_key            VARCHAR(40)     NOT NULL,
    recipient_user_id   BIGINT,
    channel             VARCHAR(10)     NOT NULL,
    outcome             VARCHAR(12)     NOT NULL,
    suppression_reason  VARCHAR(20),
    attempt_no          SMALLINT        NOT NULL DEFAULT 1,
    error               VARCHAR(1000),
    trigger_key         VARCHAR(80)     NOT NULL,
    attempted_at        TIMESTAMPTZ     NOT NULL DEFAULT now(),
    completed_at        TIMESTAMPTZ,
    version             BIGINT          NOT NULL DEFAULT 0,

    CONSTRAINT uq_notification_delivery_uid         UNIQUE (uid),
    CONSTRAINT fk_notification_delivery_company     FOREIGN KEY (company_id) REFERENCES companies(id),
    CONSTRAINT fk_notification_delivery_notification FOREIGN KEY (notification_id) REFERENCES notifications(id),
    CONSTRAINT chk_notification_delivery_outcome    CHECK (outcome IN ('PENDING','SENT','FAILED','SUPPRESSED')),
    CONSTRAINT chk_notification_delivery_suppression
        CHECK ((outcome <> 'SUPPRESSED') OR suppression_reason IS NOT NULL)
);

CREATE INDEX ix_notification_deliveries_company      ON notification_deliveries (company_id, attempted_at DESC);
CREATE INDEX ix_notification_deliveries_notification ON notification_deliveries (notification_id);

-- ============================================================
-- 5. notification_scan_markers (scanner dedup state)
-- ============================================================
CREATE TABLE notification_scan_markers (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    uid             VARCHAR(26)     NOT NULL,
    company_id      BIGINT          NOT NULL,
    type_key        VARCHAR(40)     NOT NULL,
    condition_key   VARCHAR(120)    NOT NULL,
    last_notified_at TIMESTAMPTZ    NOT NULL DEFAULT now(),
    armed           BOOLEAN         NOT NULL DEFAULT false,
    version         BIGINT          NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ     NOT NULL DEFAULT now(),
    created_by      BIGINT,
    updated_at      TIMESTAMPTZ,
    updated_by      BIGINT,

    CONSTRAINT uq_notification_scan_marker_uid       UNIQUE (uid),
    CONSTRAINT uq_notification_scan_marker_condition UNIQUE (company_id, type_key, condition_key),
    CONSTRAINT fk_notification_scan_marker_company   FOREIGN KEY (company_id) REFERENCES companies(id)
);

CREATE INDEX ix_notification_scan_markers_company_type ON notification_scan_markers (company_id, type_key);

-- ============================================================
-- 6. Per-company type-catalogue seed (ISSUES-REGISTER #12-safe)
--    uid = 'NT' || lpad(company_id::text,6,'0') || substr(md5(type_key),1,12)  (≤26 chars)
-- ============================================================
INSERT INTO notification_types (
    uid, company_id, type_key, display_name, description,
    audience_permission, branch_scoped, default_channels,
    severity, title_template, body_template, link_route_template,
    company_enabled, status
)
SELECT
    'NT' || lpad(c.id::text,6,'0') || substr(md5(t.type_key),1,12),
    c.id,
    t.type_key,
    t.display_name,
    t.description,
    t.audience_permission,
    t.branch_scoped,
    t.default_channels,
    t.severity,
    t.title_template,
    t.body_template,
    t.link_route_template,
    true,
    'ACTIVE'
FROM companies c
CROSS JOIN (
    VALUES
        ('LOW_STOCK',
         'Low stock',
         'Stock on hand has dropped to or below the reorder level.',
         'STOCK.VIEW',
         true,
         'IN_APP,EMAIL',
         'WARNING',
         'Low stock: {productName}',
         'Stock for {productName} at branch {branchName} has dropped to {onHandQty} (reorder level: {reorderLevel}).',
         '/stock/on-hand/{sourceUid}'),
        ('INVOICE_OVERDUE',
         'Invoice overdue',
         'An AR invoice has passed its due date and remains unpaid.',
         'AR.VIEW',
         false,
         'IN_APP,EMAIL',
         'WARNING',
         'Invoice overdue: {invoiceNumber}',
         'Invoice {invoiceNumber} for {customerName} is {daysOverdue} day(s) overdue. Outstanding: {outstandingAmount}.',
         '/ar/invoices/{sourceUid}'),
        ('PAYMENT_RECEIVED',
         'Payment received',
         'A customer payment (AR receipt) has been recorded.',
         'AR.VIEW',
         false,
         'IN_APP',
         'INFO',
         'Payment received from {customerName}',
         'A payment of {amountFormatted} was received from {customerName}.',
         '/ar/receipts/{sourceUid}'),
        ('APPROVAL_PENDING',
         'Approval pending',
         'A document is awaiting your approval.',
         'PURCHASE.PO.APPROVE',
         false,
         'IN_APP,EMAIL',
         'WARNING',
         'Approval required: {documentType} {documentUid}',
         '{documentType} submitted by {submittedBy} requires your approval.',
         '/approvals/{sourceUid}'),
        ('GOODS_RECEIVED',
         'Goods received',
         'A goods receipt has been recorded against a purchase order.',
         'STOCK.VIEW',
         true,
         'IN_APP',
         'INFO',
         'Goods received: {receiptReference}',
         'Goods receipt {receiptReference} was recorded at branch {branchName}.',
         '/purchases/goods-receipts/{sourceUid}'),
        ('DELIVERY_CONFIRMED',
         'Delivery confirmed',
         'A sales delivery has been confirmed and goods dispatched.',
         'SALES.DELIVERY.VIEW',
         true,
         'IN_APP',
         'INFO',
         'Delivery confirmed: {deliveryReference}',
         'Delivery {deliveryReference} to {customerName} has been confirmed.',
         '/sales/deliveries/{sourceUid}')
) AS t(type_key, display_name, description, audience_permission, branch_scoped,
       default_channels, severity, title_template, body_template, link_route_template)
ON CONFLICT (company_id, type_key) DO NOTHING;

-- ============================================================
-- 7. Permissions + grants (module: notifications)
-- ============================================================
INSERT INTO permissions (code, module, description)
VALUES
    ('NOTIFICATION.VIEW',              'notifications', 'Read own in-app inbox, unread count, mark-read'),
    ('NOTIFICATION.PREFERENCE.MANAGE', 'notifications', 'Read and set own per-type notification preferences'),
    ('NOTIFICATION.ADMIN',             'notifications', 'View notification type catalogue, delivery log; enable/disable types per company')
ON CONFLICT (code) DO NOTHING;

-- Grant NOTIFICATION.VIEW + NOTIFICATION.PREFERENCE.MANAGE to all operational roles + ORG_ADMIN
INSERT INTO role_permission (role_id, permission_id)
SELECT r.id, p.id
FROM roles r
CROSS JOIN permissions p
WHERE r.code IN ('ORG_ADMIN','SALES_MANAGER','SALES_REP','ACCOUNTANT','STOREKEEPER','PURCHASE_OFFICER')
  AND p.code IN ('NOTIFICATION.VIEW','NOTIFICATION.PREFERENCE.MANAGE')
ON CONFLICT DO NOTHING;

-- Grant NOTIFICATION.ADMIN to ORG_ADMIN only
INSERT INTO role_permission (role_id, permission_id)
SELECT r.id, p.id
FROM roles r
CROSS JOIN permissions p
WHERE r.code = 'ORG_ADMIN'
  AND p.code = 'NOTIFICATION.ADMIN'
ON CONFLICT DO NOTHING;
