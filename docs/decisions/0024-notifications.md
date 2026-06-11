# 0024 — Notifications data model: a cross-cutting `com.erp.modules.notifications` module — in-app + email alerts driven by the transactional outbox (a new `NotificationDispatcher` consumer) plus a `@Scheduled` scanner for time-based triggers (invoice-overdue / low-stock), a seeded notification-type catalogue with permission-based audience rules, per-user preferences (mute / per-channel), and an append-only delivery log; additive as `V25__notifications.sql` + `V26__notification_triggers.sql` (V1–V19 frozen), a new `NotificationDispatcher implements DomainEventHandler`, one new `PAYMENT.RECEIVED` trigger event type (+ consumption of the existing `APPROVAL.SUBMITTED` emitted by approvals/ADR-0022), and a one-line `outbox.publish` touch-point in AR receipt recording

> **Amended 2026-06-11 (Wave-2 collision resolution):** the original title declared **two** new trigger event types `PAYMENT.RECEIVED` + `APPROVAL.PENDING`. Per the Wave-2 coordination plan (`wave2-build-coordination.md` collision #2), `APPROVAL.PENDING` was a name collision — the approvals engine (ADR-0022 D-10) emits **`APPROVAL.SUBMITTED`** (at submit, explicitly "for Notifications later") and `APPROVAL.RESOLVED`; it never emits `APPROVAL.PENDING`. Notifications now **consumes the existing `APPROVAL.SUBMITTED`** (no producer change). So this increment adds exactly **one** new `DomainEventType` constant (`PAYMENT.RECEIVED`, from AR). See the amendment notes in D-4 / D-8 / D-14.

- **Status:** Accepted
- **Date:** 2026-06-11
- **Deciders:** solutions-architect (architect-authored requirements 2026-06-11 — notifications.md FR-NOTIF-01..14, BR-NOTIF-01..14, NFR-NOTIF-01..09, §3 behaviour, §7 flows, §11 OQ log; the recommended defaults are adopted, the eight OQs are this ADR's decisions / owner-confirmable seed data, none blocks the build).
- **Context source:** [docs/requirements/notifications.md](../requirements/notifications.md) (the business spec — the ground truth for every rule below). [docs/PATH-TO-FULL-ERP.md](../PATH-TO-FULL-ERP.md) §3.12 (area 15 cross-cutting — "Email / SMS notifications" + "In-app notifications / user alerts", the X.2 enabler) + §4.6 (notifications as a cross-cutting enabler many modules want). Verified against the **shipped** code:
  - **Transactional outbox** ([ADR-0009](0009-transactional-outbox.md) / V6): `OutboxPublisher.publish(eventType, aggregateType, aggregateId, aggregateUid, companyId, branchId, payload)` (in the caller's TX, serialises payload → JSONB); `DomainEventHandler` (`String eventType()` + `void handle(DomainEvent)`, auto-discovered, invoked per matching event in the dispatcher's per-event TX); `DomainEvent` (carries `uid`, `eventType`, `aggregateType`, `aggregateId`, `aggregateUid`, `companyId`, `branchId`, `payload` JSONB — **the consumer establishes context from `companyId`/`branchId` on the event alone, no JWT**); `IdempotencyGuard.alreadyProcessed(consumer, eventUid)` / `markProcessed(consumer, eventUid)` + `processed_events(consumer, event_uid)`; `DomainEventType` constants (today: `SALE.FINALISED`, `SALE.VOIDED`, `STOCK.RECEIVED`, `STOCK.RECEIPT.VOIDED`, `DELIVERY.CONFIRMED`, `DELIVERY.RETURNED` — **this ADR adds `PAYMENT.RECEIVED`** [Amended 2026-06-11 — was `PAYMENT.RECEIVED` + `APPROVAL.PENDING`; `APPROVAL.PENDING` removed, notifications consumes the **existing** `APPROVAL.SUBMITTED` (ADR-0022)] and the `AGG_AR_RECEIPT` aggregate constant; the approvals aggregate `AGG_APPROVAL` is **already declared by ADR-0022** — notifications does not re-declare it); `DomainEventDispatcher` is `@Scheduled` (the polling pattern the notification **scanner** mirrors).
  - **IAM** ([ADR-0001](0001-iam-architecture.md)/[0002](0002-rbac-enforcement.md) / V1): `app_users` (`id`, `uid` VARCHAR(26), `username`, `display_name`, **`email` VARCHAR(160) NULLABLE**, `phone`, `is_root`, `status`; **no `company_id` — users are global, scoped via `user_branch`**); `user_branch` (`user_id`, `branch_id`, `is_default`; a user is in many branches across companies); `roles` + `role_permission` + `permissions(code, module, description)`; `PermissionResolver.resolve(userId, companyId, branchId, now)` + the underlying `UserRoleRepository.resolvePermissionCodes(userId, companyId, branchId)` (**the recipient-by-permission mechanism — but request-bound today via `PermissionResolver`; the dispatcher needs the repository query directly, D-9**); `ScopeGuard.assertCanActIn(principal, companyId)` (the read-path guard) + `companyIdOf(targetType, uid)` switch (**this ADR adds `notification` / `notificationtype` / `notificationpref`**); `@perm.has(code)` / `@perm.scoped(uid, targetType, code)` gating via `PermissionChecks` (NEVER `hasAuthority`).
  - **AR** ([ADR-0014](0014-accounts-receivable-data-model.md) / V11): `ar_invoices` (open items with `due_date`, `balance`, `status` — the **invoice-overdue scanner read**), the AR ageing query, and the **AR receipt recording service** (`ArReceiptService`, where the one-line `PAYMENT.RECEIVED` publish lands — D-8). AR is the producer of the `payment-received` trigger. **[Amended 2026-06-11: shipped AR does NOT emit `PAYMENT.RECEIVED` today — adding the `OutboxPublisher.publish` is an explicit producer-side implementation task on a shipped module, D-8/D-14.]**
  - **Stock** ([ADR-0010](0010-stock-data-model.md) / V7): `stock_on_hand` (`quantity`, optional **`reorder_level`** + a low flag — the **low-stock scanner read**, per (company, branch, product)). Stock exposes the on-hand read; the scanner queries it.
  - **Money** ([ADR-0005](0005-money-and-currency.md)): NOT used by notifications (BR-NOTIF-13 — a monetary figure in a body is a formatted display string at fire time, no `Money`, no journal FK).
  - **Audit** ([ADR-0004](0004-iam-audit-trail.md)): the append-only audit trail; admin actions (type enable/disable) audit via `AuditService` (NFR-NOTIF-03).
  - [[db-naming-convention]] verified against V1–V19 (plural masters/owned-children: `notifications`, `notification_types`, `notification_preferences`, `notification_deliveries`, `notification_scan_markers`; singular constraint roots `uq_`/`fk_`/`chk_` on the singular entity; plural `ix_` indexes; `uid VARCHAR(26)` ULID; `company_id` BIGINT scalar; additive `DROP/ADD CONSTRAINT` widen). **ISSUES-REGISTER #12:** every per-company CROSS-JOIN seed-uid MUST be `'XX' || lpad(company_id::text,6,'0') || substr(md5(key),1,12)` (≤26 chars) — **never** `|| key`. **#12 applies here** — the per-company notification-type rows are a `companies × type-catalogue` CROSS JOIN and MUST use the md5-bounded seed-uid (D-14). **Latest shipped migration is `V19__sales_returns.sql`. This ADR uses `V25__notifications.sql` + `V26__notification_triggers.sql`** (additive; V1–V19 + the in-flight V20–V24 range FROZEN/reserved for sibling modules — the coordinator assigned V25–V26 to notifications). Next ADR after this is 0025.

This ADR is the **technical data model + integration design** for the Notifications cross-cutting capability (PATH-TO-FULL-ERP X.2 / area 15). It translates the ratified-style spec into: the new `com.erp.modules.notifications` module, the five tables (`notification_types` catalogue, `notifications`, `notification_preferences`, `notification_deliveries`, `notification_scan_markers`), the channel/severity/outcome enums, the `NotificationDispatcher` (an outbox `DomainEventHandler` consuming a configured set of trigger events), the `@Scheduled NotificationScanner` (invoice-overdue + low-stock, deduped via `notification_scan_markers`), the **permission-based audience resolution** (a new IAM repository query usable outside a request), the per-user preference precedence, the best-effort `EmailSender`, the in-app inbox + preferences + admin API, the two new `DomainEventType` constants + the **one-line AR producer touch-point**, the seeded type catalogue (#12-safe per-company seed-uids), the new `gl_config` keys = **NONE** / new CoA codes = **NONE** (notifications post no GL), the new `ScopeGuard` cases + perms + Angular nav routes, the ArchUnit leaf-consumer edges, and the migration ordering. It is **concrete enough that an engineer builds without guessing a business rule.** It writes **no production code, no entities, no migration SQL** — that is the engineer's next step. Nothing in the spec is re-litigated.

## Context

The system emits operational/financial events but has **no way to tell a human**. Notifications is the
reusable spine that fixes that. The central architectural reality — and the load-bearing decision — is that
the four headline examples are **not all emitted as events today**: the outbox carries only six event types,
**none** of which is `payment-received`, `approval-pending`, `invoice-overdue`, or `low-stock` (verified
against `DomainEventType`). So "subscribe to existing events" does not deliver the named use cases. The
forces:

- **THE TRIGGER-SOURCE PROBLEM (the top design decision; notifications.md §3.2).** Three kinds of trigger
  exist: (A) events the outbox already carries (subscribe, zero producer change); (B) moments with no event
  today that a producer can emit additively (payment-received — AR adds one `outbox.publish`); (C)
  inherently time-based conditions with **no event moment** at all (invoice-overdue, low-stock — there is no
  "invoice became overdue" transaction; it is the passage of time). (C) cannot be event-driven; it needs a
  **scheduled scanner**. Resolved in **D-4/D-7/D-8**: one `NotificationDispatcher` (outbox consumer) for (A)+(B),
  one `@Scheduled NotificationScanner` for (C), both feeding a single internal `NotificationRaiser`.

- **Recipient resolution must run OUTSIDE a request (NFR-NOTIF-09, OQ-NOTIF-04).** The dispatcher and scanner
  run as SYSTEM (from the event's `company_id`, or the company being scanned) — there is **no `RequestContext`,
  no JWT**. The shipped `PermissionResolver` is request-bound (it resolves *the current principal's* codes).
  The fan-out needs the inverse query: "**which users hold permission P in company C (optionally branch B)**".
  This is a new IAM repository read. Resolved in **D-9**.

- **Reuse the outbox, never the in-memory bus (BR-NOTIF-01).** The dispatcher is a `DomainEventHandler`,
  inheriting at-least-once + idempotency + crash-safety. Spring `ApplicationEventPublisher` is forbidden.
  The new trigger events publish in the producer's TX (the outbox guarantee). Resolved in **D-4/D-8**.

- **Idempotency across BOTH trigger kinds (BR-NOTIF-07/08).** Event triggers dedupe via `IdempotencyGuard`
  (consumer + event uid). Scan triggers have no event uid — they dedupe via a **per-condition last-notified
  marker** (`notification_scan_markers`), which also gives the "fire once per crossing, re-arm on recovery"
  semantics. And the per-recipient creation must itself be idempotent on (trigger, recipient, channel) so a
  re-delivered trigger mid-fan-out does not duplicate. Resolved in **D-2/D-7**.

- **Per-company isolation with global users (BR-NOTIF-02/04).** `app_users` has **no `company_id`** (users are
  global, scoped via `user_branch`). A notification, by contrast, is **company-scoped** (it is about a
  company's invoice/stock). So `notifications.company_id` is the company the notification is *about* +
  `recipient_user_id` is the global user; the inbox read is `WHERE recipient_user_id = me AND company_id =
  myActiveCompany`, and audience resolution is "users with permission P **in** company C". Resolved in **D-2/D-9**.

- **Email is best-effort, never blocks (BR-NOTIF-09).** A failed SMTP send logs `FAILED` + retries within the
  cap; it never rolls back the consume or the in-app notification. The dispatcher's per-event TX commits the
  in-app rows; email is dispatched and its outcome recorded on the delivery row. Resolved in **D-6/D-10**.

- **No money, no GL (BR-NOTIF-13).** Notifications post **no journals**, hold **no `Money`**, need **no
  `gl_config` key and no new CoA account**. This is the one financial module-adjacent slice that touches the
  books **zero**. Stated explicitly so a reviewer does not look for the GL posting (there is none).

- **Schema freeze / direction.** IAM=V1 … Sales Returns=V19 shipped; V20–V24 reserved for sibling in-flight
  modules (the coordinator's range allocation). Notifications is additive **V25** (the five tables + the
  per-company type seed + permissions) **+ V26** (the new trigger-event `DomainEventType` constants are code,
  not schema; V26 carries only the additive AR producer-side seed/marker scaffolding if any — see D-14). It
  imports **no source-module entity**: it reads AR/Stock through their **DTO/read-model/repository projections**
  for the scanner + template values (the GL-handler-re-reads-sales precedent), and it is a **leaf consumer**
  (nothing depends back on notifications). Resolved in **D-13**.

## Decision

### D-1 — Module placement: a new cross-cutting `com.erp.modules.notifications`; controllers flat in `com.erp.api`

Notifications is a **new module** under `com.erp.modules.notifications` (a flat peer, per PROJECT-CONVENTIONS
§2 — not `platform.notifications`: it owns business tables, a catalogue, an API, and a lifecycle, so it is a
module like `gl`/`stock`, not platform plumbing like the outbox itself). It is a **leaf consumer** of the
outbox (like GL and Stock) and a **read-only client** of AR/Stock read models.

```
com.erp.modules.notifications
├── domain.entity   NotificationType, Notification, NotificationPreference,
│                   NotificationDelivery, NotificationScanMarker
├── domain.dto      NotificationDto / NotificationListItemDto / UnreadCountDto,
│                   NotificationTypeDto, NotificationPreferenceDto / SetPreferenceRequest,
│                   NotificationDeliveryDto,
│                   SetCompanyTypeStateRequest (admin per-company type toggle),
│                   PaymentReceivedPayload   (the AR trigger payload shape — lives in ar.domain.dto, D-8)
│                   // Amended 2026-06-11: ApprovalPendingPayload removed — the approvals trigger consumes the
│                   // EXISTING APPROVAL.SUBMITTED + approvals.domain.dto.ApprovalSubmittedPayload (ADR-0022), D-8
├── domain.enums    NotificationChannel (IN_APP|EMAIL; reserved SMS|PUSH|WEBHOOK),
│                   NotificationSeverity (INFO|WARNING|CRITICAL),
│                   DeliveryOutcome (PENDING|SENT|FAILED|SUPPRESSED),
│                   SuppressionReason (MUTED|CHANNEL_DISABLED|NO_EMAIL|COMPANY_TYPE_OFF|NO_AUDIENCE)
├── repository      NotificationTypeRepository, NotificationRepository,
│                   NotificationPreferenceRepository, NotificationDeliveryRepository,
│                   NotificationScanMarkerRepository
├── service         NotificationRaiser(+Impl)         — the single fan-out engine (audience→prefs→create→deliver, D-7),
│                   NotificationInboxService(+Impl)    — the user's in-app inbox reads + mark-read (D-11),
│                   NotificationPreferenceService(+Impl)— per-user preference read/set (D-11),
│                   NotificationTypeService(+Impl)     — catalogue read + per-company toggle (admin, D-11),
│                   NotificationDeliveryQuery          — admin delivery-log read (D-11),
│                   AudienceResolver                   — permission→recipient-user-ids in (company, branch) (D-9),
│                   TemplateRenderer                   — title/body placeholder substitution (D-5),
│                   EmailSender                        — best-effort SMTP, records the delivery outcome (D-6),
│                   NotificationScanner                — @Scheduled overdue + low-stock (D-7)
└── events          NotificationDispatcher             — DomainEventHandler for the configured trigger set (D-4)
```

Controllers stay flat in `com.erp.api`: `NotificationController` (the inbox), `NotificationPreferenceController`,
`NotificationAdminController` (catalogue + delivery log + per-company toggle). They touch only services
(`ModuleBoundaryTest`). The `events.NotificationDispatcher` is a notifications bean implementing
`platform.events.DomainEventHandler` (the only cross-cutting coupling — D-13), exactly as the Stock/GL
handlers do.

### D-2 — The five tables

All tables: plural names; `id` BIGINT IDENTITY PK; `uid` VARCHAR(26) ULID `uq_<root>_uid`; standard audit
cols. **No `Money` anywhere** (BR-NOTIF-13). Severity/channel/outcome are short VARCHARs with CHECKs (the
shipped enum-as-string pattern).

#### (a) `notification_types` (catalogue — seeded, system-owned, per company)

The classification + routing defaults. **Per-company** (so the admin per-company toggle + future per-company
template overrides have a home), seeded for every company (D-14). One logical type (`LOW_STOCK`) → one row
per company.

| column | type | null | notes |
|---|---|---|---|
| `id` | BIGINT IDENTITY PK | NO | |
| `uid` | VARCHAR(26) | NO | `uq_notification_type_uid`; **#12-safe per-company seed-uid** (D-14) |
| `company_id` | BIGINT | NO | tenant; `fk_notification_type_company` |
| `type_key` | VARCHAR(40) | NO | the logical key, e.g. `LOW_STOCK`; `uq_notification_type_company_key UNIQUE (company_id, type_key)`; `chk_notification_type_key` (the seeded closed set, widened additively as new types ship) |
| `display_name` | VARCHAR(120) | NO | e.g. "Low stock" |
| `description` | VARCHAR(300) | YES | shown on the preferences screen |
| `audience_permission` | VARCHAR(60) | NO | the permission code whose holders are the recipients (e.g. `STOCK.VIEW`); the audience rule (D-9) |
| `branch_scoped` | BOOLEAN | NO | DEFAULT false; when true, narrow the audience to the source branch (D-9) |
| `default_channels` | VARCHAR(60) | NO | comma-joined channel set, e.g. `IN_APP,EMAIL` (a small fixed set; parsed to `NotificationChannel`); `chk_notification_type_channels` (non-empty) |
| `severity` | VARCHAR(10) | NO | `NotificationSeverity`; `chk_notification_type_severity CHECK (severity IN ('INFO','WARNING','CRITICAL'))` |
| `title_template` | VARCHAR(200) | NO | placeholder string, e.g. `Low stock: {productName}` (D-5) |
| `body_template` | VARCHAR(1000) | NO | placeholder string (D-5) |
| `link_route_template` | VARCHAR(200) | NO | the deep-link route by uid, e.g. `/stock/on-hand/{sourceUid}` (BR-NOTIF-12) |
| `attachment_ref` | VARCHAR(60) | YES | **RESERVED** for the documents/PDF hook (OQ-NOTIF-07); NULL in v1 |
| `company_enabled` | BOOLEAN | NO | DEFAULT true; the admin per-company on/off (FR-NOTIF-11); when false the type never fires for this company (logged `SUPPRESSED` reason `COMPANY_TYPE_OFF`) |
| `status` | VARCHAR(32) | NO | `MasterStatus`; DEFAULT `'ACTIVE'`; soft-delete (BR-NOTIF-11) |
| `version` + audit cols | | | |

Constraints: `uq_notification_type_uid`, `uq_notification_type_company_key`, `fk_notification_type_company`,
`chk_notification_type_key CHECK (type_key IN ('LOW_STOCK','INVOICE_OVERDUE','PAYMENT_RECEIVED',
'APPROVAL_PENDING','GOODS_RECEIVED','DELIVERY_CONFIRMED'))` (the v1 seeded set; **widened additively** as new
types ship, the V-widen pattern), `chk_notification_type_severity`.
Indexes: `ix_notification_types_company (company_id)`.

#### (b) `notifications` (the message — one per recipient × channel)

| column | type | null | notes |
|---|---|---|---|
| `id` / `uid` | | NO | `uq_notification_uid` |
| `company_id` | BIGINT | NO | the company the notification is **about**; tenant; `fk_notification_company` |
| `branch_id` | BIGINT | YES | source branch (analysis/narrowing); nullable for company-level |
| `notification_type_id` | BIGINT | NO | FK → `notification_types(id)` |
| `type_key` | VARCHAR(40) | NO | denormalised for fast filtering without a join |
| `recipient_user_id` | BIGINT | NO | FK → `app_users(id)`; the **global** user this message is for |
| `channel` | VARCHAR(10) | NO | `NotificationChannel`; `chk_notification_channel CHECK (channel IN ('IN_APP','EMAIL','SMS','PUSH','WEBHOOK'))` (full set; v1 writes only IN_APP/EMAIL) |
| `severity` | VARCHAR(10) | NO | snapshot from the type at fire time |
| `title` | VARCHAR(200) | NO | rendered (D-5) |
| `body` | VARCHAR(1000) | NO | rendered (D-5) |
| `source_aggregate_type` | VARCHAR(40) | YES | e.g. `AR_INVOICE`, `STOCK_ON_HAND` (diagnostic) |
| `source_uid` | VARCHAR(26) | YES | the source entity uid (the deep-link target, BR-NOTIF-12) |
| `link_route` | VARCHAR(200) | YES | the rendered deep-link route |
| `trigger_key` | VARCHAR(80) | NO | the dedup key of the originating trigger (the event uid, or the scan condition key) — drives the per-recipient idempotency (D-7) |
| `is_read` | BOOLEAN | NO | DEFAULT false; **the only mutable field** (in-app channel) (BR-NOTIF-05) |
| `read_at` | TIMESTAMPTZ | YES | set on mark-read |
| `created_at` / `created_by` | | | `created_by` = the SYSTEM/dispatcher actor (no human) |

Constraints: `uq_notification_uid`; `fk_notification_company`; `fk_notification_type FOREIGN KEY
(notification_type_id) REFERENCES notification_types (id)`; `fk_notification_recipient FOREIGN KEY
(recipient_user_id) REFERENCES app_users (id)`; `chk_notification_channel`;
**`uq_notification_trigger_recipient_channel UNIQUE (company_id, trigger_key, recipient_user_id, channel)`** —
the **per-recipient idempotency backstop** (BR-NOTIF-07/NFR-NOTIF-02): a re-delivered trigger mid-fan-out
cannot create a second row for the same (trigger, recipient, channel); the `NotificationRaiser` does an
upsert/`ON CONFLICT DO NOTHING`-style insert keyed on this.
Indexes:
```
CREATE INDEX ix_notifications_recipient_company  ON notifications (recipient_user_id, company_id, created_at DESC);  -- inbox list
CREATE INDEX ix_notifications_unread             ON notifications (recipient_user_id, company_id) WHERE is_read = false AND channel = 'IN_APP';  -- unread count / unread filter
CREATE INDEX ix_notifications_company_type       ON notifications (company_id, type_key);  -- admin filter
```

#### (c) `notification_preferences` (per user, per company, per type)

| column | type | null | notes |
|---|---|---|---|
| `id` / `uid` | | NO | `uq_notification_preference_uid` |
| `company_id` | BIGINT | NO | the company this preference applies in (a user tunes per company) |
| `user_id` | BIGINT | NO | FK → `app_users(id)` |
| `type_key` | VARCHAR(40) | NO | the type this preference overrides |
| `muted` | BOOLEAN | NO | DEFAULT false; mute the whole type (no channel) |
| `channels_enabled` | VARCHAR(60) | YES | the channel set the user allows for this type, e.g. `IN_APP` (NULL = use type defaults); a channel **not** in this set is disabled |
| `version` + audit cols | | | |

Constraints: `uq_notification_preference_uid`;
`uq_notification_preference_user_company_type UNIQUE (company_id, user_id, type_key)` (one preference row per
(user, company, type)); `fk_notification_preference_user`. **Absent a row → type defaults apply**
(BR-NOTIF-06); the row is created lazily on first set.
Index: `ix_notification_preferences_user_company (user_id, company_id)`.

#### (d) `notification_deliveries` (the append-only delivery log — one row per dispatch attempt)

| column | type | null | notes |
|---|---|---|---|
| `id` / `uid` | | NO | `uq_notification_delivery_uid` |
| `notification_id` | BIGINT | YES | FK → `notifications(id)`; **NULL** for a company-level `NO_AUDIENCE` diagnostic (no notification created) |
| `company_id` | BIGINT | NO | tenant; `fk_notification_delivery_company` |
| `type_key` | VARCHAR(40) | NO | denormalised |
| `recipient_user_id` | BIGINT | YES | NULL for a company-level diagnostic |
| `channel` | VARCHAR(10) | NO | the channel attempted (IN_APP records `SENT` immediately) |
| `outcome` | VARCHAR(12) | NO | `DeliveryOutcome`; `chk_notification_delivery_outcome CHECK (outcome IN ('PENDING','SENT','FAILED','SUPPRESSED'))` |
| `suppression_reason` | VARCHAR(20) | YES | `SuppressionReason` when `outcome='SUPPRESSED'`; `chk_notification_delivery_suppression CHECK ((outcome <> 'SUPPRESSED') OR suppression_reason IS NOT NULL)` |
| `attempt_no` | SMALLINT | NO | DEFAULT 1; a retry is a NEW row with `attempt_no+1` (append-only, BR-NOTIF-10) |
| `error` | VARCHAR(1000) | YES | the failure message on `FAILED` (truncated, user-safe) |
| `trigger_key` | VARCHAR(80) | NO | the originating trigger (joins a delivery to its trigger for diagnostics) |
| `attempted_at` | TIMESTAMPTZ | NO | DEFAULT now() |
| `completed_at` | TIMESTAMPTZ | YES | set when PENDING→SENT/FAILED on the **same row** (the controlled state machine, BR-NOTIF-10 — this is the one in-place transition the dispatcher owns; users never edit) |

Constraints: `uq_notification_delivery_uid`; `fk_notification_delivery_company`;
`fk_notification_delivery_notification FOREIGN KEY (notification_id) REFERENCES notifications (id)`;
`chk_notification_delivery_outcome`; `chk_notification_delivery_suppression`.
Index: `ix_notification_deliveries_company (company_id, attempted_at DESC)`,
`ix_notification_deliveries_notification (notification_id)`.

> **Append-only nuance (BR-NOTIF-10).** The email path inserts the delivery row `PENDING` then transitions it
> to `SENT`/`FAILED` on the **same row** (a controlled two-state machine the `EmailSender` owns, mirroring the
> outbox's own `markDispatched`/`recordFailure` on `domain_events`). A **retry** is a **new** delivery row
> (`attempt_no+1`), never an edit of the prior attempt. There is **no user-facing edit/delete**. The DB grant
> may forbid `DELETE` on this table (the audit-log precedent, F11) — recommended, not mandatory for v1.

#### (e) `notification_scan_markers` (the scanner dedup state — per condition)

| column | type | null | notes |
|---|---|---|---|
| `id` / `uid` | | NO | `uq_notification_scan_marker_uid` |
| `company_id` | BIGINT | NO | tenant |
| `type_key` | VARCHAR(40) | NO | `INVOICE_OVERDUE` or `LOW_STOCK` |
| `condition_key` | VARCHAR(120) | NO | the unique condition this marker dedupes: for overdue = the AR invoice uid; for low-stock = `productUid:branchId` |
| `last_notified_at` | TIMESTAMPTZ | NO | when this condition last fired |
| `armed` | BOOLEAN | NO | DEFAULT false; **low-stock re-arm flag** — set false on notify, set true (the marker cleared/re-armed) when the condition recovers (stock back above reorder), so a later dip re-fires (BR-NOTIF-08) |
| `version` + audit cols | | | |

Constraints: `uq_notification_scan_marker_uid`;
`uq_notification_scan_marker_condition UNIQUE (company_id, type_key, condition_key)` — one marker per
condition (the dedup key); `fk_notification_scan_marker_company`.
Index: `ix_notification_scan_markers_company_type (company_id, type_key)`.

> **Dedup semantics (BR-NOTIF-08).** **invoice-overdue:** on a newly-overdue invoice the scanner upserts a
> marker (insert-if-absent) and notifies **only** when it created the marker (the invoice was not previously
> notified). The invoice is overdue once until paid; the marker stays. (When the invoice settles it leaves the
> overdue scan set; an optional sweep removes stale markers, cosmetic.) **low-stock:** the scanner notifies on
> a (product, branch) crossing into low and sets the marker `armed=false`; on a run where the same (product,
> branch) is **above** reorder, it sets `armed=true` (re-armed) so the **next** crossing fires again. The
> scanner never writes to `stock_on_hand` / `ar_invoices` — the dedup state lives entirely here (OQ-NOTIF-06).

### D-3 — Enums (the exact sets)

Four enums in `notifications.domain.enums` (the enum-as-string + CHECK pattern):

- **`NotificationChannel`** = `IN_APP, EMAIL` (v1) + **reserved** `SMS, PUSH, WEBHOOK` (the CHECK admits the
  full set so adding a sender is additive — NFR-NOTIF-07; v1 code writes only IN_APP/EMAIL).
- **`NotificationSeverity`** = `INFO, WARNING, CRITICAL`.
- **`DeliveryOutcome`** = `PENDING, SENT, FAILED, SUPPRESSED`.
- **`SuppressionReason`** = `MUTED, CHANNEL_DISABLED, NO_EMAIL, COMPANY_TYPE_OFF, NO_AUDIENCE` (why a delivery
  was suppressed — answers "why didn't I get it", BR-NOTIF-06).

### D-4 — The `NotificationDispatcher`: ONE outbox `DomainEventHandler` for the configured trigger set (the (A)+(B) sources)

`NotificationDispatcher implements DomainEventHandler` (in `notifications.events`). **One subtlety:**
`DomainEventHandler.eventType()` returns **one** event-type string, and the dispatcher needs to consume
**several** (the configured trigger set). **Decision: register one dispatcher bean per consumed event type**
(the boring, framework-aligned choice — the dispatcher infra invokes a handler per matching event by its
`eventType()`), OR a thin per-type adapter bean delegating to a shared `NotificationDispatcherCore`. The
engineer registers a small set of `DomainEventHandler` beans (one per trigger event), each delegating to
`NotificationDispatcherCore.dispatch(event, typeKey)` — the legible, no-new-infra option (Alternatives). The
v1 consumed set:

| consumed event | source | → notification type | audience perm |
|---|---|---|---|
| `STOCK.RECEIVED` (existing) | Stock (A) | `GOODS_RECEIVED` | `STOCK.VIEW` |
| `DELIVERY.CONFIRMED` (existing) | Sales (A) | `DELIVERY_CONFIRMED` | `SALES.DELIVERY.VIEW` |
| `PAYMENT.RECEIVED` (**NEW**, D-8) | AR (B) | `PAYMENT_RECEIVED` | `AR.VIEW` |
| `APPROVAL.SUBMITTED` (**existing**, ADR-0022 D-10) | Approvals (A) | `APPROVAL_PENDING` | the approver perm (from the payload) |

> **Amended 2026-06-11 (Wave-2 collision resolution):** the approvals row originally consumed a **new** `APPROVAL.PENDING` event (source kind (B), "designed-to-contract"). Per coordination collision #2, approvals (ADR-0022 D-10) already emits **`APPROVAL.SUBMITTED`** at submit — explicitly intended for Notifications. So the approvals trigger is now a **source-kind (A)** subscription to the **existing** `APPROVAL.SUBMITTED` (zero producer change; approvals does not add `APPROVAL.PENDING`). The notification *type key* stays `APPROVAL_PENDING` (the user-facing "you have an approval pending" message); only the *consumed outbox event* changes from the (never-built) `APPROVAL.PENDING` to the shipped `APPROVAL.SUBMITTED`. The dynamic-audience-from-payload behaviour (D-8) is unchanged — `ApprovalSubmittedPayload` (ADR-0022) carries the approver routing.

Each handler:
1. `IdempotencyGuard.alreadyProcessed("NOTIFICATIONS.<TYPE>", event.uid())` → if processed, no-op (BR-NOTIF-07).
2. Establish company/branch context from the **event's `companyId`/`branchId`** (no JWT — D-1).
3. **Re-read the source by uid** for template values where the payload is thin (the GL-handler-re-reads-sales
   precedent — e.g. `STOCK.RECEIVED` carries the GR uid; the dispatcher reads the GR/product names via the
   Stock DTO read). For the new trigger events (B) the payload carries the display values directly (D-8).
4. Call `NotificationRaiser.raise(NotificationTrigger{ companyId, branchId, typeKey, audiencePermission(or
   from payload), sourceAggregateType, sourceUid, templateValues, triggerKey = event.uid })` (D-7).
5. `IdempotencyGuard.markProcessed(...)` — committed in the dispatcher's per-event TX with the in-app rows.

The mapping `consumed event → typeKey + audience` is config/code in the dispatcher (a small switch/table), so
adding a subscription (a future event → a future type) is a one-line addition.

### D-5 — Templates: placeholder substitution, no `Money` (BR-NOTIF-13)

`title_template` / `body_template` / `link_route_template` carry `{placeholder}` tokens. `TemplateRenderer`
substitutes from the trigger's `templateValues` map (a `Map<String,String>` — **already-formatted strings**,
e.g. `amount = "TZS 1,250,000"`, `dueDate = "2026-05-31"`, `daysOverdue = "12"`). **No `Money`, no `BigDecimal`
arithmetic** — the producer/scanner formats the value when building the trigger; the renderer only substitutes
text (BR-NOTIF-13). A missing placeholder renders empty (defensive) and logs a WARN (a template/values
mismatch is a config defect, not a runtime failure). `link_route` is rendered the same way with `{sourceUid}`.

### D-6 — `EmailSender`: best-effort SMTP, never blocks (BR-NOTIF-09)

`EmailSender.send(notification, recipientEmail)` uses Spring Mail (`JavaMailSender` —
**`spring-boot-starter-mail` is NOT in the backend pom today; ADR-0024's build adds it**, D-14). It:
- inserts a `notification_deliveries` row `PENDING` (channel `EMAIL`),
- attempts the SMTP send,
- transitions the row → `SENT` (success) or `FAILED` (exception, error truncated) on the **same row**
  (the controlled state machine, BR-NOTIF-10).
- A recipient with **no `app_users.email`** → no send; a `SUPPRESSED` row, reason `NO_EMAIL` (FR-NOTIF-04).

**Crucially, the email send is decoupled from the consume TX (BR-NOTIF-09):** the in-app notifications + the
`PENDING` email delivery rows commit in the dispatcher's per-event TX; the actual SMTP hand-off and the
`SENT`/`FAILED` transition happen **after** (an `@Async` send, or a second outbox-style poll over `PENDING`
email deliveries — **Decision: `@Async` send with the delivery row as the record**; a transient SMTP failure
records `FAILED` and a bounded retry re-sends as a new attempt row). An email failure **never** rolls back
the consume or the in-app channel. (If the deployment has no SMTP configured, the send fast-fails `FAILED`
and the in-app channel is unaffected — the system degrades to in-app-only cleanly.)

> **Why not put email on its own outbox event?** Considered (Alternatives). It is the more crash-safe design
> (the `PENDING` email delivery row is itself a durable work item a poller drains). **Decision for v1: `@Async`
> + the delivery row + bounded retry** — simpler, and email is explicitly best-effort (a lost email on a crash
> mid-async-send is acceptable per BR-NOTIF-09; the in-app channel is the reliable one). The delivery row +
> `attempt_no` is the seam to upgrade to a poller-drained outbox-style sender later (additive, NFR-NOTIF-07).

### D-7 — `NotificationRaiser`: the single fan-out engine (audience → preferences → create → deliver)

`NotificationRaiser.raise(NotificationTrigger)` is the **one** path both the dispatcher (D-4) and the scanner
(below) call. Steps:
1. Load the `notification_types` row for (company, typeKey). If `status != ACTIVE` or `company_enabled =
   false` → write a company-level `SUPPRESSED` delivery (reason `COMPANY_TYPE_OFF`) and return (BR-NOTIF-06).
2. **Audience resolution (D-9):** `AudienceResolver.recipients(companyId, branchId or null, audiencePermission,
   branchScoped)` → the set of `recipient_user_id`. Zero recipients → optional company-level `SUPPRESSED`
   (reason `NO_AUDIENCE`) diagnostic; return (BR-NOTIF-07 — the trigger is still marked processed by the
   caller).
3. **Per recipient, per channel** in the effective channel set (type `default_channels` ∩ the user's
   `channels_enabled` if a preference row exists, minus a `muted` type):
   - muted type → `SUPPRESSED` (reason `MUTED`); channel disabled by preference → `SUPPRESSED` (reason
     `CHANNEL_DISABLED`); else:
   - **idempotent insert** of the `notifications` row keyed on `uq_notification_trigger_recipient_channel`
     (`ON CONFLICT DO NOTHING` — a re-delivered trigger does not duplicate, NFR-NOTIF-02). If the row already
     existed (conflict), skip delivery (already done on the prior attempt).
   - `IN_APP` → the row IS the delivery; write a `SENT` delivery row.
   - `EMAIL` → hand to `EmailSender` (D-6), which writes the `PENDING`→`SENT`/`FAILED`/`NO_EMAIL` delivery.
4. Render title/body/link via `TemplateRenderer` (D-5) from the trigger's `templateValues`.

The whole step-3 fan-out for the **in-app** rows runs in the caller's TX (the dispatcher's per-event TX or the
scanner's per-condition TX); email is `@Async` after commit (D-6).

**`NotificationScanner` (`@Scheduled`, the (C) source).** A per-company scheduled job (default hourly,
configurable via a property — OQ-NOTIF-06; mirrors `DomainEventDispatcher`'s `@Scheduled`):
- **invoice-overdue:** for each company, query AR open items past `due_date` (via the AR read model /
  ageing query — D-13). For each, upsert a `notification_scan_markers` row (key = invoice uid); notify **only**
  if the marker was newly created (not previously notified). Build the trigger (typeKey `INVOICE_OVERDUE`,
  audience `AR.VIEW`, source = invoice uid, templateValues = number/customer/balance/daysOverdue formatted)
  and call `NotificationRaiser.raise`. `triggerKey = "scan:INVOICE_OVERDUE:" + invoiceUid`.
- **low-stock:** for each company, query `stock_on_hand` at/below `reorder_level` (via the Stock read model).
  For each (product, branch), read/upsert the marker (key = `productUid:branchId`); notify if not already
  in the low state (marker `armed=false` means already-notified-and-still-low → skip; absent or `armed=true`
  → notify + set `armed=false`). For each (product, branch) currently **above** reorder with an existing
  marker → set `armed=true` (re-arm). `triggerKey = "scan:LOW_STOCK:" + productUid + ":" + branchId + ":" +
  date`.
The scanner is **single-instance-safe** via the markers (a double-run dedupes); multi-instance scheduler
coordination rides the platform's outbox-scaling work (deferred, NFR-NOTIF-05).

### D-8 — The new trigger events + the AR producer touch-point (the (B) source)

**One new `DomainEventType` constant** (declared under ADR-0024's `DomainEventType` block, the shared-file append protocol §1):
```
DomainEventType.PAYMENT_RECEIVED  = "PAYMENT.RECEIVED"    (NEW; AR emits — the producer-side change is an implementation task, see below + D-14)
```
plus the aggregate constant `AGG_AR_RECEIPT = "AR_RECEIPT"`.

> **Amended 2026-06-11 (Wave-2 collision resolution).** Two changes here:
> 1. **`APPROVAL.PENDING` removed (collision #2).** The original declared a second new constant `APPROVAL_PENDING = "APPROVAL.PENDING"`. The approvals engine (ADR-0022 D-10) emits **`APPROVAL.SUBMITTED`** + `APPROVAL.RESOLVED` — **never** `APPROVAL.PENDING`. Notifications **consumes the existing `APPROVAL.SUBMITTED`** (which already carries `ApprovalSubmittedPayload`, ADR-0022) and raises the `APPROVAL_PENDING` *notification type*. **No new approval event constant is declared by ADR-0024**, and `AGG_APPROVAL` is **already owned by ADR-0022** (not re-declared here).
> 2. **`PAYMENT.RECEIVED` is a producer-side implementation task (collision #3).** Shipped AR (ADR-0014) does **NOT** emit `PAYMENT.RECEIVED` today. The notifications increment must therefore **add a one-line `OutboxPublisher.publish(PAYMENT.RECEIVED, …)` to `ArReceiptService`** (the receipt-record method) — a **cross-branch touch on a shipped module**. This is the explicit producer-side change below; until it lands, the `PAYMENT_RECEIVED` subscription is dormant (no event fires) and nothing breaks.

**`PaymentReceivedPayload`** (lives in `ar.domain.dto` — the producer owns the payload, the
`SaleFinalisedPayload` precedent; the notifications dispatcher imports it, the GL-imports-sales-dto direction):
```
record PaymentReceivedPayload(
    String receiptUid, Long companyId, Long branchId,
    String customerName, String amountFormatted, String currency, Instant receivedAt)
```
**The one cross-module touch-point (D-13):** AR's receipt-record service method adds **one line** in its
existing transaction:
```java
outbox.publish(DomainEventType.PAYMENT_RECEIVED, DomainEventType.AGG_AR_RECEIPT,
               receipt.getId(), receipt.getUid(), companyId, branchId,
               new PaymentReceivedPayload(receipt.getUid(), companyId, branchId,
                   customerName, money.format(amount), currency, Instant.now()));
```
This is **additive** (a new event row in AR's TX; AR's behaviour is unchanged) and lands in **AR's PR**, not
notifications' — notifications consumes it (NFR-NOTIF-09). If OQ-NOTIF-01 chooses the thin first cut, this
line is the fast-follow; the dispatcher subscription is ready either way.

**`ApprovalSubmittedPayload`** (**owned by approvals — ADR-0022 D-1/D-10**; the notifications dispatcher imports it from `approvals.domain.dto`, the consumer-reads-producer-payload direction):
```
// shape per ADR-0022 (ApprovalSubmittedPayload). The dispatcher reads the approver-routing
// permission + the document summary from this payload; the exact field names follow ADR-0022's record.
```
The dispatcher consumes the **existing** `APPROVAL.SUBMITTED` (ADR-0022 D-10 — emitted in the approvals
engine's submit TX), reads the approver routing/permission **from the payload** (the audience for this type is
dynamic — the approver perm of the pending step, not a fixed `notification_types.audience_permission`), and
raises the `APPROVAL_PENDING` notification type. **Until the approvals engine ships, no `APPROVAL.SUBMITTED`
event is published and the type lies dormant** (BR-NOTIF-14) — zero coupling, zero blocker. Notifications does
**not** define the approval payload; it consumes ADR-0022's.

> **Amended 2026-06-11 (Wave-2 collision resolution):** the original designed a notifications-defined
> `ApprovalPendingPayload` consumed off a new `APPROVAL.PENDING` event. Per collision #2, neither exists — the
> approvals engine (ADR-0022) already emits `APPROVAL.SUBMITTED` with its own `ApprovalSubmittedPayload`.
> Notifications consumes that existing event + payload. The notifications `domain.dto` no longer declares an
> `ApprovalPendingPayload`; the dispatcher imports `approvals.domain.dto.ApprovalSubmittedPayload`.

### D-9 — Audience resolution outside a request: a new IAM repository read (OQ-NOTIF-04)

The fan-out runs as SYSTEM (no `RequestContext`). The shipped `PermissionResolver` resolves *the current
principal's* codes; the fan-out needs the **inverse**: the users who hold permission P in (company, branch).
**Decision: add an additive IAM repository query** `UserRoleRepository.findUserIdsWithPermission(companyId,
branchId, permissionCode)` (and a branch-agnostic `findUserIdsWithPermissionInCompany(companyId,
permissionCode)` for `branch_scoped = false` types) — a read-only JPQL/native query over `user_branch` ⋈
`user_role` ⋈ `role_permission` ⋈ `permissions`, returning distinct `app_users.id`. `AudienceResolver`
(in notifications) calls it via the `iam` repository (a notifications → iam.repository read edge — same
direction `ScopeGuard` already reads the IAM repositories from the security spine; documented D-13). This is
an **additive IAM read**, no behaviour change. (`is_root` users are **not** auto-recipients — a notification
targets *operational* holders of the permission, not the global super-admin; root receives a notification
only if explicitly granted the audience permission in that company. Recommended; OQ-NOTIF-03-adjacent.)

### D-10 — In-app inbox / preferences / admin API (the controllers)

All gated `@perm.has` / `@perm.scoped` (D-12), all `assertCanActIn` on every read path (NFR-NOTIF-01),
all paginated.

**`NotificationController`** (`/notifications`, the user's own inbox — `NOTIFICATION.VIEW`):
- `GET /notifications` — paged list of **my** in-app notifications (filter `unread`, `typeKey`, `severity`),
  newest first; scoped to `recipient_user_id = me AND company_id = activeCompany` (BR-NOTIF-04).
- `GET /notifications/unread-count` — my unread in-app count (the shell badge; the partial-index read,
  NFR-NOTIF-08).
- `POST /notifications/{uid}/read` — mark one read (`@perm.scoped(#uid,'notification','NOTIFICATION.VIEW')`
  + the row's `recipient_user_id` must equal me — a service check beyond company scope, BR-NOTIF-04).
- `POST /notifications/read-all` — mark all my unread read.

**`NotificationPreferenceController`** (`/notification-preferences` — `NOTIFICATION.PREFERENCE.MANAGE`):
- `GET /notification-preferences` — my preferences (joined to the type catalogue so the screen lists every
  tunable type + its defaults) in my active company.
- `PUT /notification-preferences/{typeKey}` — set my mute / channels for a type (upsert the row).

**`NotificationAdminController`** (`/admin/notifications` — `NOTIFICATION.ADMIN`):
- `GET /admin/notifications/types` — the company's type catalogue (defaults, audience, channels, severity,
  `company_enabled`).
- `PUT /admin/notifications/types/{typeKey}/state` — enable/disable a type for the company (FR-NOTIF-11);
  **audited** (NFR-NOTIF-03).
- `GET /admin/notifications/deliveries` — the delivery log (paged; filter channel/outcome/typeKey/recipient/
  date), company-scoped (FR-NOTIF-12).

### D-11 — Services (the responsibilities)

`NotificationRaiser` (D-7 — fan-out), `NotificationScanner` (D-7 — `@Scheduled`), `AudienceResolver` (D-9),
`TemplateRenderer` (D-5), `EmailSender` (D-6), `NotificationInboxService` (the inbox reads + mark-read —
enforces recipient-is-me, BR-NOTIF-04), `NotificationPreferenceService` (per-user upsert), `NotificationType
Service` (catalogue read + per-company toggle, audits the toggle), `NotificationDeliveryQuery` (admin log
read). Interface + `Impl` per convention.

### D-12 — Permissions (the codes) + `ScopeGuard` cases

**New permissions** (module `notifications`), seeded in V25 + granted (D-14):

| code | who | covers |
|---|---|---|
| `NOTIFICATION.VIEW` | every user (broad grant) | read own in-app inbox + unread count + mark-read |
| `NOTIFICATION.PREFERENCE.MANAGE` | every user (broad grant) | read/set own preferences |
| `NOTIFICATION.ADMIN` | ORG_ADMIN | view type catalogue, per-company type enable/disable, read delivery log |

`NOTIFICATION.VIEW` + `NOTIFICATION.PREFERENCE.MANAGE` are granted **broadly** (every operational role / all
authenticated users get their own inbox + preferences) — the seed grants them to `ORG_ADMIN` and the standard
operational roles (the V7/V12/V14/V17 grant pattern; the exact role set is the seed's, owner-tunable).
`NOTIFICATION.ADMIN` → `ORG_ADMIN` only.

**`ScopeGuard.companyIdOf` new cases** (so `@perm.scoped(#uid,'<type>',...)` works):
```
case "notification"      -> notifications.findCompanyIdByUid(uid);
case "notificationtype"  -> notificationTypes.findCompanyIdByUid(uid);
case "notificationpref"  -> notificationPreferences.findCompanyIdByUid(uid);
```
(The delivery log is read only via the admin list endpoint scoped by company — no per-uid scoped op — so no
`notificationdelivery` case is required in v1; add it additively if a per-delivery endpoint appears.)

### D-13 — ArchUnit edges (leaf consumer; no cycle)

- **`notifications.events` → `platform.events`** (`DomainEventHandler`, `IdempotencyGuard`, `OutboxPublisher`)
  — the outbox-consumer coupling, the Stock/GL precedent. **Allowed.**
- **`notifications.events` → `ar.domain.dto`** (`PaymentReceivedPayload`) and **`approvals.domain.dto`**
  (`ApprovalSubmittedPayload`, ADR-0022 — the **existing** approvals payload) — the consumer reads the
  producer's **payload DTO**, the exact direction `stock.events` imports `sales.domain.dto.SaleFinalisedPayload`.
  **Allowed.** [Amended 2026-06-11 — was the never-built `ApprovalPendingPayload`; now ADR-0022's shipped `ApprovalSubmittedPayload`.]
- **`notifications.service` → `ar` read model + `stock` read model** (DTO/projection reads for the scanner +
  template re-reads) and **→ `iam.repository`** (the audience query, D-9) — DTO/read-only, no entity import,
  no write into a source table. **Allowed** (the GL-re-reads-sales + ScopeGuard-reads-IAM precedents).
- **The producer side:** AR's receipt service `→ platform.events.OutboxPublisher` + `→ ar.domain.dto`
  (its own payload) — that is AR publishing to the outbox, **not** importing notifications. **No module
  imports `notifications`.**
- **No edge back into `notifications` from any module.** Direction: `notifications → platform.events`,
  `notifications → ar/stock/iam (reads)`; producers `→ platform.events`. **No cycle** — notifications is a
  **leaf consumer/sink** (NFR-NOTIF-09), like GL and Stock are leaf consumers of sales events. The shipped
  `ModuleBoundaryTest` (controller↛repository, service↛controller) is **not** violated by any edge above.

### D-14 — Migration ordering (V25 + V26; additive; V1–V19 frozen; #12-safe seeds)

**`V25__notifications.sql`** (the module — tables + per-company seed + perms), in order:
1. **CREATE** `notification_types` (+ constraints/indexes, D-2a).
2. **CREATE** `notifications` (+ the `uq_notification_trigger_recipient_channel` idempotency constraint +
   indexes, D-2b).
3. **CREATE** `notification_preferences` (D-2c).
4. **CREATE** `notification_deliveries` (D-2d). *(Optional: a `DELETE`-revoke grant, the audit-log F11
   precedent — recommended.)*
5. **CREATE** `notification_scan_markers` (D-2e).
6. **Per-company type-catalogue seed** — for every existing company, INSERT the six v1 types
   (`LOW_STOCK`, `INVOICE_OVERDUE`, `PAYMENT_RECEIVED`, `APPROVAL_PENDING`, `GOODS_RECEIVED`,
   `DELIVERY_CONFIRMED`) via a `companies CROSS JOIN (the 6 type definitions)` insert. **#12-SAFE seed-uid:**
   each row's `uid` MUST be `'NT' || lpad(company_id::text,6,'0') || substr(md5(type_key),1,12)` (≤26 chars)
   — **NEVER** `|| type_key` (ISSUES-REGISTER #12). `ON CONFLICT (company_id, type_key) DO NOTHING`. New
   companies created post-V25 receive the seed via the BootstrapRunner path the other per-company seeds use
   (the cash-default / CoA-seed precedent — the engineer wires the type seed into bootstrap, an additive
   bootstrap step, mirroring how V13's per-company cash default + V10's CoA seed reach new companies).
7. **Permission seed + grant** — INSERT `NOTIFICATION.VIEW`, `NOTIFICATION.PREFERENCE.MANAGE`,
   `NOTIFICATION.ADMIN` (module `notifications`) `ON CONFLICT (code) DO NOTHING`; grant `NOTIFICATION.VIEW` +
   `NOTIFICATION.PREFERENCE.MANAGE` to the operational roles + `ORG_ADMIN`, and `NOTIFICATION.ADMIN` to
   `ORG_ADMIN`, via the `roles × permissions` CROSS JOIN `ON CONFLICT DO NOTHING` (the V7/V12/V14/V17
   pattern). Permissions have no `uid` — #12 N/A for the perm rows.

**`V26__notification_triggers.sql`** (the cross-module trigger scaffolding — additive, may be empty schema):
- The one new `DomainEventType` constant (`PAYMENT.RECEIVED`) is **code, not schema** (the
  `domain_events.event_type` column is a free VARCHAR(60); no CHECK to widen — verified). So V26 carries **no
  schema for the event itself**. [Amended 2026-06-11 — was "two new constants … `PAYMENT.RECEIVED`, `APPROVAL.PENDING`"; `APPROVAL.PENDING` removed (notifications consumes the existing `APPROVAL.SUBMITTED`), so only `PAYMENT.RECEIVED` is new.]
- **Producer-side implementation task (NOT a migration — code, lands in this increment):** shipped AR
  (ADR-0014) does **not** emit `PAYMENT.RECEIVED`. Add a **one-line
  `OutboxPublisher.publish(DomainEventType.PAYMENT_RECEIVED, …)` to `ArReceiptService`** (the receipt-record
  method, in AR's existing TX) — a **cross-branch touch on a shipped module**. This is additive and
  behaviour-neutral for AR (a new outbox row in AR's TX; AR's posting/behaviour is unchanged) and is the
  explicit producer-side change the coordinator must sequence (D-8). It may land in a tiny AR patch or in the
  notifications branch's AR touch; either way it is a *code* change, not schema. V26 exists to (a) hold any additive index the scanner needs on the
  source read models *if* a missing index is found (e.g. an `ar_invoices (company_id, due_date)` partial index
  for the overdue scan, or a `stock_on_hand (company_id) WHERE quantity <= reorder_level` partial index for
  low-stock — **additive, on frozen tables, index-only, allowed**), and (b) keep the trigger/producer
  migration logically separate from the module migration so OQ-NOTIF-01's first-cut staging is clean. **If no
  new index is needed, V26 is a no-op placeholder and may be folded into V25** (the engineer's call with the
  PM — the table/column/constraint names above are fixed regardless).

> **Migration #12 + freeze notes.** The per-company `notification_types` seed is the **only** #12-vulnerable
> insert (a `companies × types` CROSS JOIN with a per-company uid) — it MUST use the md5-bounded seed-uid
> `'NT' || lpad(company_id::text,6,'0') || substr(md5(type_key),1,12)` (D-14 step 6). The permission grant is
> uid-less (#12 N/A). `notifications`/`notification_deliveries`/`notification_scan_markers` rows are created at
> **runtime** by the dispatcher/scanner (ULID via the entity `@PrePersist`, the shipped `Ulid.next()` path) —
> **no migration seed-uid exposure**. `MigrationKeepDataIT` extends to V25/V26 (the five new tables + the seed
> are keep-data-safe — pure additive CREATE + INSERT). **No `gl_config` key, no CoA account, no
> `JournalSourceType`, no movement type** — notifications posts no GL (BR-NOTIF-13). The build also adds
> **`spring-boot-starter-mail`** to the backend pom (the `EmailSender` dependency, D-6) — a pom/dependency
> change, not a migration.

### D-15 — Angular nav routes

New lazy-loaded feature area `notifications` under the shell (the `app.routes.ts` children pattern):
- **`/notifications`** — the in-app inbox (list + mark-read; the shell bell/badge reads `unread-count`).
- **`/notifications/preferences`** — the per-user preferences screen.
- **`/admin/notifications`** — the admin catalogue + delivery-log screen (gated `NOTIFICATION.ADMIN`).

The **shell** gains a notification **bell + unread badge** (polls `GET /notifications/unread-count` on
navigation / a light interval — NFR-NOTIF-05, no WebSocket in v1) linking to `/notifications`.

## Consequences

**Positive**
- A single reusable spine: any module raises a notification by emitting a domain event (or the scanner picks
  up a condition); notifications turns it into in-app + email for the right people, recording every send. The
  dispatcher is just another outbox `DomainEventHandler` — **at-least-once, idempotent, crash-safe for free**;
  a notification is never lost on a crash (the explicit reason the in-memory bus is forbidden, BR-NOTIF-01).
- The three-trigger-source design honestly addresses that the headline examples are **not** events today:
  existing events (zero producer change), a one-line additive AR publish (payment-received), and a scanner
  for the time-based ones (overdue, low-stock) — each deduped (idempotency guard / scan markers).
- **Zero GL/financial coupling** (BR-NOTIF-13): no `gl_config` key, no CoA account, no journal — the module
  is a pure sink. A reviewer does not look for a posting; there is none.
- Additive + surgical: 5 new tables, 3 new permissions, 2 new `DomainEventType` constants (code-only — the
  event-type column has no CHECK to widen), 3 new `ScopeGuard` cases, 1 new IAM repository read, 1 one-line
  AR producer publish, the `spring-boot-starter-mail` dependency. **V1–V19 frozen; V20–V24 untouched.**
- Per-company isolation holds despite global users: the notification is company-scoped, the recipient is the
  global user, audience resolution is permission-in-company, the inbox read is recipient+company.
- Email best-effort never blocks: the in-app channel + the business TX are immune to SMTP failure
  (BR-NOTIF-09); the system degrades to in-app-only cleanly if no SMTP is configured.

**Negative / costs**
- **The dispatcher needs a new IAM read (D-9)** — `findUserIdsWithPermission(company, branch, perm)`. Additive,
  but it lands in IAM's repository and must be tested for the multi-branch / role-permission join correctness
  (a wrong audience query = the wrong people notified, or a leak — covered by NFR-NOTIF-01 tests).
- **The (B) trigger lands in AR's PR** (the one-line publish) — a cross-module coordination point (flagged in
  the touch list). It is additive and behaviour-neutral for AR, but it is not notifications' own code; if
  OQ-NOTIF-01 defers it, payment-received simply does not fire until AR adds the line.
- **The scanner is a polled query, not event-driven** (the (C) source is inherently time-based). Its dedup
  correctness (fire-once / re-arm) rests on the `notification_scan_markers` discipline — a marker bug =
  duplicate or missed overdue/low-stock notifications. Tests must assert the once-per-crossing + re-arm
  semantics (BR-NOTIF-08). Multi-instance scheduler coordination is deferred (single-instance-safe for now).
- **Email send is `@Async` + a delivery row, not a poller-drained outbox** (D-6) — a crash mid-async-send can
  lose an in-flight email (acceptable per best-effort BR-NOTIF-09; the in-app channel is reliable). The
  delivery row + `attempt_no` is the upgrade seam if email must become guaranteed later.
- **Per-company type catalogue rows** (one per type per company) — a small denormalisation that gives the
  per-company toggle + future per-company template overrides a home, at the cost of the #12-vulnerable seed
  (handled by the md5-bounded seed-uid) + the bootstrap step for new companies.

**Neutral / deferred**
- SMS/push/webhook (channel enum reserved), document attachments (column reserved), subscriber lists, custom
  types/template editing, digests/throttle/quiet-hours, read receipts/escalation, manual resend, i18n
  templates, WebSocket in-app push — all deferred, none precluded (NFR-NOTIF-07). The `APPROVAL_PENDING` type
  is seeded and dormant until the approvals engine emits `APPROVAL.SUBMITTED` (BR-NOTIF-14) [Amended
  2026-06-11 — consumes the existing ADR-0022 `APPROVAL.SUBMITTED`, not a new `APPROVAL.PENDING`].

## Alternatives considered

- **Trigger transport — outbox consumer vs Spring `ApplicationEventPublisher` vs a message broker.**
  *Decided: the transactional outbox (a `DomainEventHandler`).* `ApplicationEventPublisher` is **forbidden**
  (in-memory, loses events on crash — PROJECT-CONVENTIONS / BR-NOTIF-01). A broker (Kafka/Rabbit) is the
  enterprise answer but is new infrastructure with no current need at this scale; the outbox already gives
  at-least-once + idempotency + crash-safety and is the established pattern. Reject the bus; defer the broker.
- **Time-based triggers — a scheduled scanner vs forcing an event.** *Decided: a `@Scheduled` scanner for
  overdue/low-stock.* There is **no transaction** at the moment an invoice becomes overdue or stock crosses
  reorder (it is the passage of time / a prior write's cumulative effect) — there is nothing to attach an
  outbox publish to. A scanner querying the source read models on a cadence, deduped via scan markers, is the
  honest design. (Low-stock *could* be event-driven off every stock movement, but that fires on every
  receipt/issue and needs the same dedup — the scanner is simpler and covers both time-based cases uniformly.)
- **One dispatcher consuming many event types vs one handler bean per event type.** *Decided: one
  `DomainEventHandler` bean per consumed event type, each delegating to a shared `NotificationDispatcherCore`.*
  `DomainEventHandler.eventType()` returns a single type; the dispatcher infra routes per type. A small set of
  thin per-type beans is the framework-aligned, no-new-infra choice; a single multi-type handler would need a
  dispatcher-infra change (out of scope) or a meta-handler that subverts the routing. Reject the single
  multi-type handler.
- **Email — `@Async` + delivery row vs a second outbox-drained email queue.** *Decided: `@Async` + the
  delivery row + bounded retry for v1.* The queue-drained design is more crash-safe but heavier; email is
  explicitly best-effort (BR-NOTIF-09), so a lost in-flight email on a crash is acceptable, and the delivery
  row + `attempt_no` is the seam to upgrade to a drained queue later (additive). Reject the heavier design for
  v1; keep the upgrade path.
- **Notification-type catalogue — per-company rows vs one global catalogue + per-company override table.**
  *Decided: per-company type rows.* A global catalogue + a separate per-company override table is normalised
  but adds a join + a second table for the toggle. Per-company rows give the toggle + future per-company
  template overrides a single home; the cost (the #12-vulnerable seed) is handled by the md5-bounded seed-uid.
  At the company count this system targets, the denormalisation is the lean choice.
- **Recipient resolution — a new IAM repository read vs reusing `PermissionResolver`.** *Decided: a new
  additive IAM repository query.* `PermissionResolver` is request-bound (resolves the current principal); the
  fan-out runs as SYSTEM with no principal and needs the inverse (users-holding-permission-in-company). The
  additive read is the correct, testable mechanism; bending `PermissionResolver` to run principal-less would
  muddy the security spine.

## Open items (OQ-NOTIF — recommended defaults adopted; none blocks the build)

- **OQ-NOTIF-01 (owner) — first-slice trigger subset.** Adopted **all three sources** (existing events +
  payment-received new trigger + the scanner). Thin alternative: scanner + existing events first, payment-
  received as a fast follow. **Load-bearing for sizing**; default = all three.
- **OQ-NOTIF-02 (owner) — approval-pending in v1?** Adopted **seed the `APPROVAL_PENDING` type, defer the
  trigger to the approvals engine** (dormant until approvals ships, BR-NOTIF-14). Settled.
- **OQ-NOTIF-03 (owner) — audience permission per type + root recipiency.** Adopted the §3.3 table
  (`STOCK.VIEW`/`AR.VIEW`/approver-perm) as seed data; `is_root` is **not** auto-recipient. Owner confirms the
  audiences before go-live (tunable seed). Default stands.
- **OQ-NOTIF-04 (architect/ADR) — recipient resolution outside a request.** Resolved (D-9) — an additive IAM
  repository read. Settled.
- **OQ-NOTIF-05 (owner) — default channels / spam posture.** Adopted `PAYMENT_RECEIVED` IN_APP-only; warnings
  IN_APP+EMAIL (seed + per-user preference). Owner confirms before go-live. Default stands.
- **OQ-NOTIF-06 (architect/ADR) — scanner cadence + dedup placement.** Resolved (D-7) — hourly (configurable),
  dedup in `notification_scan_markers`, never writes to source tables. Settled.
- **OQ-NOTIF-07 (owner) — attachment hook timing.** Adopted **reserve `notification_types.attachment_ref` now,
  wire actual attachments when documents/PDF ships**. Settled.
- **OQ-NOTIF-08 (owner) — AP payments notify too?** Adopted **AR only in v1** (a `PAYMENT_MADE` type + an AP
  trigger is additive later). Default stands.

---

## Summary

ADR-0024 designs the **Notifications** cross-cutting module `com.erp.modules.notifications`: five tables
(`notification_types` catalogue, `notifications`, `notification_preferences`, `notification_deliveries`,
`notification_scan_markers`), four enums (`NotificationChannel` IN_APP/EMAIL + reserved SMS/PUSH/WEBHOOK,
`NotificationSeverity`, `DeliveryOutcome`, `SuppressionReason`), and the fan-out spine. **Triggers come from
three sources** (D-4/D-7/D-8): existing outbox events (subscribe, zero producer change — including the
**existing `APPROVAL.SUBMITTED`** from approvals/ADR-0022, dormant until the approvals engine ships), **one new
trigger event** (`PAYMENT.RECEIVED` from AR via a one-line additive `OutboxPublisher.publish` in
`ArReceiptService` — a producer-side implementation task on shipped AR), and a `@Scheduled
NotificationScanner` for the inherently [Amended 2026-06-11 (Wave-2 collisions #2/#3): was "two new trigger events `PAYMENT.RECEIVED`/`APPROVAL.PENDING`"; `APPROVAL.PENDING` removed — notifications consumes the existing `APPROVAL.SUBMITTED`.]
time-based conditions (invoice-overdue, low-stock) deduped via `notification_scan_markers` (fire-once-per-
crossing, low-stock re-arms on recovery). The `NotificationDispatcher` is an outbox `DomainEventHandler`
(at-least-once, idempotent via `IdempotencyGuard`, crash-safe — the in-memory bus is forbidden). Audience =
the users holding the type's permission in the source company (+ branch), via a **new additive IAM repository
read** (D-9, the SYSTEM-context inverse of `PermissionResolver`). Per-user preferences (mute / per-channel)
override the type defaults; suppression is **recorded** (never silent). Email is **best-effort** SMTP
(`@Async` + delivery row + bounded retry; `spring-boot-starter-mail` added) and **never blocks** the in-app
channel or the business TX. **No GL, no `Money`, no `gl_config` key, no CoA account** (BR-NOTIF-13) — a pure
sink. **Additive on frozen V1–V19** as `V25__notifications.sql` (tables + #12-safe per-company type seed +
perms) + `V26__notification_triggers.sql` (additive scanner indexes / a no-op placeholder; the one new event
constant `PAYMENT.RECEIVED` is code, not schema). **Leaf consumer, no cycle** (D-13). **Cross-module touch
list:** (1) **AR → outbox (producer-side implementation task)** — add the one-line `OutboxPublisher.publish(
PAYMENT.RECEIVED, …)` to `ArReceiptService` (shipped AR does NOT emit it today; cross-branch touch) +
`PaymentReceivedPayload` in `ar.domain.dto`; the approvals trigger needs **no producer change** — notifications
consumes the **existing `APPROVAL.SUBMITTED`** (ADR-0022); (2) **IAM** — the additive `findUserIdsWithPermission` repository read; (3)
**notifications → ar/stock read models** — the scanner queries + template re-reads (DTO/read-only); (4) the
**shell** — a bell + unread badge polling `unread-count`. **Readiness:** every table, column, constraint name,
enum, trigger source, dispatcher mapping, scanner dedup rule, audience query, preference precedence, API
endpoint, permission, `ScopeGuard` case, nav route, and the #12-safe seed is specified — an engineer builds
the module + V25/V26 without guessing a rule.
