# Requirements — Notifications (in-app + email alerts, event-driven, per-user preferences, delivery log)

> Status: **DRAFT (architect-authored, owner-style assumptions made).** This is a **cross-cutting platform
> capability** (docs/PATH-TO-FULL-ERP.md §3.12 "Email / SMS notifications" + "In-app notifications / user
> alerts", area 15; the X.2 enabler many modules want — Sales, Procurement, AR/AP, HR, Reporting). It is
> authored by the solutions-architect because no separate discovery pass ran for it; the load-bearing
> business decisions are flagged as Open Questions (§11) with **recommended defaults adopted** so the ADR
> (0024) can proceed. Where a rule is genuinely a business policy the owner must ratify, it is marked
> **OQ-NOTIF-NN (owner)** — none of them blocks the v1 build.
>
> Author: solutions-architect · Domain: a NEW platform-cross-cutting module `com.erp.modules.notifications`.
> Business-level spec only. **No schema, no API shapes, no tables/columns, no code** — those are in
> **ADR-0024** (the next step). Do not infer the data model from this document.
>
> **Scope sentence:** deliver **in-app** and **email** notifications to users, driven by **domain events
> over the transactional outbox** (the events modules already emit) plus a small set of **time-based
> triggers** (a scheduled scanner for overdue / low-stock), routed to recipients by a **notification type →
> audience** rule and **per-user preferences** (mute / channel choice), with an append-only **delivery log**
> recording every dispatch attempt and outcome. Examples in scope: **low-stock**, **approval-pending**,
> **invoice-overdue**, **payment-received**.
>
> **Depends on:** the **transactional outbox** (ADR-0009 — `DomainEventHandler` + `IdempotencyGuard` +
> `processed_events`; the notification dispatcher is a NEW outbox **consumer**); **IAM** (ADR-0001/0002 —
> `app_users` with `email`, `user_branch` many-branch membership, roles → permission codes, `ScopeGuard`,
> `PermissionResolver.resolvePermissionCodes(userId, companyId, branchId)` — the recipient-by-permission
> resolution mechanism); **audit** (append-only trail). **Soft-depends on (optional, for attachments):**
> the **documents / PDF** capability (PATH-TO-FULL-ERP X.1 — NOT yet built); v1 notifications carry a
> **deep-link** to the source document, not a rendered attachment (the attachment hook is designed but
> deferred, §2). **This module gates: none** — it is a pure consumer/sink; no other module waits on it.
>
> **The load-bearing reality (read this first).** The notification *examples* the owner named are NOT all
> emitted as domain events today. The outbox currently carries only six event types
> (`SALE.FINALISED`, `SALE.VOIDED`, `STOCK.RECEIVED`, `STOCK.RECEIPT.VOIDED`, `DELIVERY.CONFIRMED`,
> `DELIVERY.RETURNED`) — there is **no** `payment-received`, `approval-pending`, `invoice-overdue`, or
> `low-stock` event in the system. So notifications cannot simply "subscribe to existing events" for the
> headline examples. The v1 design therefore has **three trigger sources** (§3.2): (1) the **existing**
> business events (additive subscription — zero change to producers); (2) a **handful of NEW notification
> trigger events** that producing modules emit additively at the relevant moment (e.g. AR receipt records a
> `payment-received` trigger) — each a one-line `outbox.publish` in the producer; (3) a **scheduled scanner**
> for the inherently time-based triggers that have no event moment (invoice-overdue, low-stock) — it queries
> the source modules' read models on a cadence and raises an in-app/email notification when a threshold is
> crossed, deduped so it fires once per condition. The owner approves which trigger sources ship in the
> first slice (OQ-NOTIF-01).

## 1. Business context & why now

Every operational and financial module now produces events the business *should* be told about, but the
system has **no way to tell anyone**. A storekeeper does not know stock dropped below reorder level until
they look; a credit controller does not know an invoice went overdue until they run the ageing report; a
buyer does not know a PO is waiting for approval (once approvals ship); a cashier's manager does not see that
a customer payment landed. Today the only "notification" is the screen the user happens to be on. As the ERP
grows past the finance core into operational depth (Sales Orders, Procurement, eventually Manufacturing /
HR), the absence of a notification spine becomes the thing that makes the system feel inert — correct, but
silent.

**Notifications closes that gap with a single, reusable spine.** It is a **platform capability**, not a
feature of one module: any module raises a notification by emitting a domain event (the producers already
do this for the cross-module effects), and the notifications module turns that event into the right messages
to the right people on the right channels, recording every send. The design deliberately reuses the proven
**transactional-outbox consumer** pattern (the notification dispatcher is just another `DomainEventHandler`,
exactly like the stock and GL handlers) so it inherits at-least-once delivery, idempotency, crash-safety, and
per-event retry **for free** — a notification is never "lost on a crash" the way an in-memory
`ApplicationEventPublisher` listener would be.

Two channels in v1: **in-app** (a per-user inbox the Angular shell badges and lists — the primary channel,
always on, costs nothing external) and **email** (best-effort via SMTP — the channel for things a user needs
to know when not logged in). SMS / push / webhooks are deferred (§2). The headline use cases the owner named
— **low-stock**, **approval-pending**, **invoice-overdue**, **payment-received** — are the acceptance set;
they exercise all three trigger sources (an existing event, a new trigger event, and the scheduled scanner)
and both channels.

### Vocabulary (read this first)

- **Notification** — a single message addressed to **one recipient user** on **one channel**, belonging to a
  **notification type**, scoped to one **company** (and optionally a **branch**), carrying a **title**, a
  **body**, and a **deep-link** to the source entity. The atomic unit the inbox lists and the delivery log
  records. (One business event may fan out to **many** notifications — one per recipient × enabled channel.)
- **Notification type** — the catalogue entry that classifies a notification (e.g. `LOW_STOCK`,
  `INVOICE_OVERDUE`, `PAYMENT_RECEIVED`, `APPROVAL_PENDING`). It carries the **default audience rule** (who
  should get it — by permission code), the **default channels**, a **severity**, and the **template** for the
  title/body. The seeded, system-owned classification users tune their preferences against. New types are
  added additively by the owning module; users do not create types in v1.
- **Trigger** — the moment a notification type fires: a **domain event** (existing or a new notification
  trigger event) consumed from the outbox, **or** a **scheduled scan** condition crossing a threshold. A
  trigger produces zero-or-more notifications (after audience resolution + preference filtering).
- **Audience rule** — how a notification type resolves its **recipients**: the set of users in the source
  company who **hold a named permission** (e.g. `INVOICE_OVERDUE` → users with `AR.VIEW`), optionally narrowed
  to the source **branch**. Resolved at fire time via the IAM permission resolver. (v1 audience is
  permission-based; explicit per-type subscriber lists are deferred, §2.)
- **Channel** — the delivery medium of one notification: **`IN_APP`** (the per-user inbox) or **`EMAIL`**
  (SMTP to `app_users.email`). v1 has exactly these two. (SMS / PUSH / WEBHOOK reserved, deferred.)
- **Per-user preference** — a user's override of a notification type's default routing: **mute** the type
  entirely, or enable/disable **per channel** (e.g. "in-app yes, email no for `PAYMENT_RECEIVED`"). Absent a
  preference, the type's defaults apply. Scoped per (user, company) so a user in two companies tunes each.
- **Delivery log** — the **append-only** record of every dispatch attempt for every notification: which
  channel, when, the outcome (`PENDING` / `SENT` / `FAILED` / `SUPPRESSED`), and the error on failure. The
  audit/diagnostic trail; never edited (a retry is a new attempt, not an edit). In-app "delivery" is the
  notification row itself being readable; email "delivery" is the SMTP hand-off result.
- **In-app inbox** — the per-user list of `IN_APP` notifications, each **unread / read**, badge-counted in the
  shell, markable read (one or all), and deep-linking to the source. The primary v1 channel.
- **Deep-link** — the stable in-app route (by entity **uid**) a notification points at (e.g. the AR invoice,
  the SO awaiting approval, the stock-on-hand row). v1 carries the link; a **rendered PDF attachment** is the
  deferred documents-module hook (§2).
- **Scheduled scanner** — the `@Scheduled` job that, on a cadence, queries source read models for **time-based
  conditions** (invoices past due, stock below reorder) and raises a notification once per newly-crossed
  condition (deduped — it does not re-notify the same overdue invoice every run). The trigger source for the
  examples that have **no event moment**.

> **Word discipline.** A **notification** is the user-facing message; a **domain event** (outbox) is the
> system fact that may *trigger* one — they are not the same (one event → many notifications, or zero). A
> **notification type** is the catalogue classification; a **channel** is the medium. A **preference** is the
> user's routing override; an **audience rule** is the type's default recipient resolution. The **delivery
> log** is the send record; the **audit trail** (IAM) is the security record — notifications write the
> delivery log; security-interesting reads/writes still audit as usual. "Email is **best-effort**": a failed
> SMTP send is logged `FAILED` and retried within a cap, but it never rolls back the business transaction or
> the in-app notification (BR-NOTIF-09).

## 2. Scope

> v1 is **a reusable notification spine: two channels (in-app + email), event-driven via the outbox + a
> scheduled scanner for time-based triggers, a seeded notification-type catalogue with permission-based
> audience rules, per-user preferences (mute / per-channel), and an append-only delivery log** — delivering
> the four named example types end-to-end. Everything richer is deferred.

### In scope (v1)

- **The notifications module** `com.erp.modules.notifications` — a NEW module that owns the notification,
  notification-type, preference, and delivery-log tables, the dispatcher (an outbox consumer), the scheduled
  scanner, the email sender, and the in-app inbox API. Controllers flat in `com.erp.api`.
- **Two channels: `IN_APP` and `EMAIL`.** In-app is the per-user inbox (always available, the primary
  channel). Email is best-effort SMTP to `app_users.email` (skipped/suppressed if the user has no email).
- **Three trigger sources (§3.2), owner picks the first-slice subset (OQ-NOTIF-01):**
  1. **Existing domain events** — subscribe additively to events the outbox already carries
     (`STOCK.RECEIVED`, `DELIVERY.CONFIRMED`, `SALE.FINALISED`, …) with **zero change to producers**.
  2. **New notification trigger events** — a small set the producing modules emit additively at the moment of
     interest (e.g. AR receipt → `payment-received` trigger; the approvals engine → `approval-pending`
     trigger when it ships). Each is one `outbox.publish` line in the producer (designed in ADR-0024 as the
     cross-module touch-point; the producer-side line is the producing module's PR).
  3. **A scheduled scanner** — for time-based conditions with no event moment: **invoice-overdue** (AR open
     items past due date) and **low-stock** (stock-on-hand below reorder level). Deduped: fires once per
     newly-crossed condition, not every run.
- **A seeded notification-type catalogue** classifying notifications, each with: a **default audience rule**
  (a permission code, optionally branch-narrowed), **default channels**, a **severity** (`INFO` / `WARNING` /
  `CRITICAL`), and a **template** (title + body with placeholders). v1 ships the four example types plus a
  few obvious ones (goods-received, delivery-confirmed). Types are system-owned; users tune preferences, not
  the catalogue.
- **Permission-based audience resolution.** A type's recipients = the users in the source **company** who
  **hold the type's audience permission**, optionally narrowed to the source **branch** (resolved via the IAM
  `resolvePermissionCodes(userId, companyId, branchId)` query). No notification crosses company scope.
- **Per-user preferences (mute + per-channel).** A user may **mute** a type (no notifications) or
  enable/disable each **channel** for a type, per (user, company). Absent a preference, the type's defaults
  apply. A muted/disabled channel is recorded `SUPPRESSED` in the delivery log (so "why didn't I get it" is
  answerable), not silently dropped.
- **The in-app inbox API** — list my notifications (paged, filterable by unread / type / severity), the
  **unread count** (for the shell badge), **mark one read**, **mark all read**. Scoped to the calling user;
  a user reads only their own inbox.
- **Email sending (best-effort, async, retried).** The dispatcher hands an `EMAIL` notification to an SMTP
  sender; success/failure is logged in the delivery log; a transient failure retries within a cap (the
  outbox dispatcher's own retry covers the consume; the email send itself records its attempts). A permanent
  failure (no email, hard bounce surrogate) is `FAILED`/`SUPPRESSED` — never blocks the in-app channel.
- **Append-only delivery log** — one row per (notification, channel) dispatch attempt, with outcome + error.
  Diagnostic + the basis for "resend" later (deferred). Never edited or deleted.
- **Admin: type catalogue read + (optional) per-type default toggle.** A `notifications` admin may **view**
  the type catalogue and its defaults, and (v1, light) **enable/disable a type globally per company** (e.g.
  "this company does not want payment-received emails at all"). Editing templates/audiences is deferred.
- **Platform reuse, all invariants:** outbox consumer (`DomainEventHandler` + `IdempotencyGuard`), per-company
  scope + `assertCanActIn` on every read path, `uid` VARCHAR(26) ULID on every externally-exposed row,
  `MasterStatus` soft-delete on the type catalogue, `@perm.has` / `@perm.scoped` gating (never `hasAuthority`),
  append-only delivery log, audit on admin actions. **Money is not involved** (notifications post no GL, hold
  no monetary amount beyond a formatted display string in a template — see BR-NOTIF-13).

### Deferred (recognised, NOT built in v1)

- **SMS / push / webhook channels.** v1 is in-app + email. The channel enum reserves the others; adding one is
  a new sender + a channel value, additive (NFR-NOTIF-07).
- **Rendered document attachments (the documents/PDF hook).** v1 carries a **deep-link**, not a PDF. When the
  documents capability (PATH-TO-FULL-ERP X.1) ships, an email notification can attach the rendered document —
  designed as the optional `depends-on`, not built (the attachment-reference column is reserved, OQ-NOTIF-07).
- **Explicit per-type subscriber lists / distribution groups.** v1 audience is permission-based + per-user
  preference. Named subscriber lists ("always CC the FD on CRITICAL") are deferred.
- **User-authored / admin-authored notification types + template editing.** v1 types are seeded + system-owned;
  the admin toggles a type on/off per company. A full template editor / custom-type builder is deferred.
- **Digest / batching / quiet-hours / rate-limiting per user.** v1 sends each notification as it fires. Daily
  digests, quiet hours, and per-user send throttles are deferred (NFR-NOTIF-04 notes the volume cap that
  keeps v1 safe meanwhile).
- **Read receipts / acknowledgement workflows / escalation.** v1 has unread/read on the in-app channel. "User
  must acknowledge", escalation-if-unread, and SLA timers are deferred.
- **Resend / retry-from-UI.** v1 retries email within the dispatcher's cap automatically; a manual "resend"
  button is deferred (the delivery log is the foundation for it).
- **Notification preferences at role/company level (defaults beyond the catalogue).** v1 defaults live on the
  type; per-role default overrides are deferred.
- **Localisation / i18n of templates (Swahili).** v1 templates are English (PATH-TO-FULL-ERP §3.13 i18n is a
  separate cross-cutting pass); template text is data, so localisation is additive.
- **In-app real-time push (WebSocket/SSE).** v1 in-app inbox is **poll/badge-on-load** (the shell fetches the
  unread count on navigation / a light poll). Live WebSocket push is deferred (NFR-NOTIF-05).

### Explicitly NOT this slice

- **A new event bus / messaging infrastructure.** Notifications **reuses the existing transactional outbox**
  (ADR-0009) as its trigger transport — the dispatcher is a `DomainEventHandler`. It does **not** introduce
  Kafka/RabbitMQ/Spring `ApplicationEventPublisher` (the latter is explicitly forbidden — loses events on
  crash, PROJECT-CONVENTIONS). It does not change the outbox.
- **Changing how producing modules work.** Subscribing to an **existing** event changes **nothing** in the
  producer. The **new trigger events** are additive one-line publishes in the producer's existing transaction
  — they do not change the producer's behaviour, only add an event row. Notifications never reaches into a
  producing module's tables.
- **The approvals engine.** `approval-pending` is a named example, but the **approvals workflow** (PATH-TO-FULL
  -ERP X.5) is a separate, not-yet-built module. v1 notifications designs to the approvals engine's **expected
  contract** (it will emit an `APPROVAL.PENDING`-style trigger event) and ships the `APPROVAL_PENDING`
  notification type ready to consume it; if approvals is not built when notifications ships, that type simply
  never fires until approvals lands (no coupling, no blocker — §9, OQ-NOTIF-02).
- **GL posting / money movement.** Notifications post no journals and move no money. A `payment-received`
  notification *reports* an amount (a formatted string in the body) but holds no `Money`, no FK to a journal.

## 3. How it works (behaviour the ADR turns into a design)

### 3.1 The shape of a notification's life

1. **A trigger fires** (an outbox event is consumed, or the scanner crosses a threshold) for a notification
   **type**, in a **company** (and maybe a **branch**), about a **source entity** (carrying its uid + a small
   set of template values, e.g. invoice number + amount + due date).
2. **Audience resolution** — the type's audience rule resolves the **recipient users**: those in the source
   company holding the type's permission code, narrowed to the source branch if the rule says so. (Zero
   recipients → nothing is created; the trigger is still marked processed so it does not re-run.)
3. **Per recipient, per enabled channel** — the recipient's **preference** for the type is read; for each
   channel the type defaults to (and the company-level type toggle allows) and the user has not disabled:
   - **`IN_APP`** → a notification row is created **unread** in the recipient's inbox (this *is* the
     delivery — logged `SENT` immediately for the in-app channel).
   - **`EMAIL`** → a notification row is created and an email send is attempted to `app_users.email`; the
     attempt is logged `PENDING`→`SENT`/`FAILED` in the delivery log. No email → logged `SUPPRESSED`.
   - A muted type or a user-disabled channel → logged `SUPPRESSED` (so the absence is explained), no send.
4. **The recipient reads it** — the in-app inbox lists it; the shell badge counts unread; the user marks it
   read (or clicks the deep-link, which marks it read and navigates). Email arrives best-effort.
5. **Everything is recorded** — the delivery log carries every attempt + outcome; admin can read it for
   diagnostics. The notification row is **immutable except** its read state (and the delivery log is fully
   append-only).

### 3.2 The three trigger sources (the load-bearing design choice — §1)

**(A) Existing domain events (subscribe additively, zero producer change).** The dispatcher consumes events
the outbox already carries. Useful v1 mappings: `STOCK.RECEIVED` → a `GOODS_RECEIVED` notification (audience
`STOCK.VIEW`); `DELIVERY.CONFIRMED` → a `DELIVERY_CONFIRMED` notification (audience `SALES.DELIVERY.VIEW`).
These cost nothing on the producer side — the dispatcher re-reads the source by uid for template values (the
GL handler precedent: the payload carries the uid, the consumer re-reads detail).

**(B) New notification trigger events (additive one-line publish in the producer).** For moments the business
cares about that have no event today, the producing module emits a new trigger event in its existing
transaction. v1 set:
- **`payment-received`** — AR receipt recording (and AP payment, if wanted) publishes a trigger event so the
  notifications dispatcher raises a `PAYMENT_RECEIVED` notification (audience `AR.VIEW`). The AR module adds
  one `outbox.publish` line in its receipt-record service.
- **`approval-pending`** — when the approvals engine ships, it publishes a trigger on every pending approval
  step (audience = the approver permission). Designed-to-contract; not built by notifications (§9).

**(C) The scheduled scanner (time-based, no event moment).** A `@Scheduled` job (mirroring the outbox
dispatcher's scheduling) runs per company on a cadence (default hourly, configurable) and:
- **invoice-overdue** — finds AR open items whose due date has passed and that are not yet flagged
  "overdue-notified", raises an `INVOICE_OVERDUE` notification (audience `AR.VIEW`) once per invoice, and
  records that it notified (dedup — does not re-fire next run). A fresh crossing (a newly-overdue invoice)
  fires; an already-notified one does not.
- **low-stock** — finds stock-on-hand rows at/below reorder level not yet "low-notified", raises a
  `LOW_STOCK` notification (audience `STOCK.VIEW`) once per (product, branch) crossing, records it; clears the
  dedup flag when stock recovers above reorder so a later dip re-fires.

The scanner reads the source modules **through their read models / repositories** (it is a query, not a
write into them) and raises notifications through the same internal path as the event triggers. Its dedup
state lives in the notifications module (a "last notified" marker per condition), so it never touches a
source table.

### 3.3 The four example types, end-to-end (acceptance set)

| type | trigger source | audience (permission) | severity | channels (default) | deep-link |
|---|---|---|---|---|---|
| `LOW_STOCK` | scanner (C) | `STOCK.VIEW` (branch-narrowed) | WARNING | IN_APP, EMAIL | stock-on-hand row |
| `INVOICE_OVERDUE` | scanner (C) | `AR.VIEW` | WARNING | IN_APP, EMAIL | the AR invoice |
| `PAYMENT_RECEIVED` | new trigger event (B) | `AR.VIEW` | INFO | IN_APP | the AR receipt |
| `APPROVAL_PENDING` | new trigger event (B), approvals engine | the approver perm (e.g. `PURCHASE.PO.APPROVE`) | WARNING | IN_APP, EMAIL | the doc awaiting approval |

(The exact audience permission per type is data on the seeded type row; the table is the v1 default, tunable
by the owner before go-live — OQ-NOTIF-03.)

## 4. Actors / personas

- **Any operational user (recipient)** — receives in-app notifications in their inbox, optionally email;
  reads them, clicks through to the source; tunes their own **preferences** (mute a type, turn off email for
  a type). Holds `NOTIFICATION.VIEW` (read own inbox) + `NOTIFICATION.PREFERENCE.MANAGE` (own preferences) —
  both granted broadly (every user gets their own inbox).
- **Storekeeper / inventory clerk** — the `LOW_STOCK` / `GOODS_RECEIVED` audience (holds `STOCK.VIEW`).
- **Credit controller / accounts clerk** — the `INVOICE_OVERDUE` / `PAYMENT_RECEIVED` audience (holds
  `AR.VIEW`).
- **Approver (buyer / manager)** — the `APPROVAL_PENDING` audience (holds the relevant approve permission),
  once approvals ships.
- **Notifications administrator** — views the type catalogue + delivery log; enables/disables a type per
  company; diagnoses "why didn't X get notified" from the delivery log. Holds `NOTIFICATION.ADMIN`.
- **SYSTEM (the dispatcher + the scanner)** — not a human persona: the outbox dispatcher consumes trigger
  events and the `@Scheduled` scanner runs the time-based triggers, both under the source company's context
  (from the event's `company_id`, or the company being scanned), creating notifications + writing the delivery
  log. No human initiates a fan-out; recipients only *consume*.

## 5. Functional requirements

> IDs are `FR-NOTIF-NN`. Crisp, testable. "Notification" = one message to one recipient on one channel.
> "Type" = the catalogue classification. "Trigger" = an outbox event or a scanner condition. "Audience" =
> the recipients resolved by the type's permission rule in the source company/branch.

### Triggering & fan-out

- **FR-NOTIF-01** The system raises notifications from **three trigger sources**: (a) **existing** outbox
  domain events (subscribed additively, no producer change); (b) **new notification trigger events** emitted
  additively by producing modules (a one-line `outbox.publish`); (c) a **scheduled scanner** for time-based
  conditions (invoice-overdue, low-stock). Which subset ships in the first slice is the owner's call
  (OQ-NOTIF-01); the design supports all three.
- **FR-NOTIF-02** On a trigger for a notification **type** in a **company** (optionally a **branch**), the
  system **resolves the audience** = the users in that company who **hold the type's audience permission**
  (narrowed to the branch if the type's rule says so), via the IAM permission resolution. Zero recipients →
  no notifications created; the trigger is still marked processed (no re-run, BR-NOTIF-07).
- **FR-NOTIF-03** For each resolved recipient, for each **channel** the type defaults to (and the company-level
  type toggle permits) that the recipient has **not disabled**, the system creates a **notification** and
  attempts delivery on that channel. A **muted** type or a **disabled** channel for that recipient produces a
  delivery-log row marked **`SUPPRESSED`** (the absence is recorded, never silent — BR-NOTIF-06).
- **FR-NOTIF-04** **In-app delivery:** an `IN_APP` notification is created **unread** in the recipient's inbox;
  this constitutes delivery (logged `SENT` for the in-app channel immediately). **Email delivery:** an
  `EMAIL` notification triggers a **best-effort** SMTP send to `app_users.email`, logged
  `PENDING`→`SENT`/`FAILED`; a recipient with **no email** is logged `SUPPRESSED` (no send attempted).
- **FR-NOTIF-05** The **scheduled scanner** runs on a configurable cadence (default hourly) per company and
  raises: `INVOICE_OVERDUE` (one per AR open item newly past due), `LOW_STOCK` (one per (product, branch)
  newly at/below reorder). It **dedupes** — it fires **once** per newly-crossed condition and does not
  re-notify an already-notified condition; for low-stock it **clears** the dedup marker when stock recovers
  above reorder so a later dip re-fires (BR-NOTIF-08).

### In-app inbox

- **FR-NOTIF-06** A user with `NOTIFICATION.VIEW` reads **their own** in-app inbox: a **paged**, filterable
  (unread-only / by type / by severity) list of their `IN_APP` notifications, newest first, each with title,
  body, severity, type, source deep-link, created-at, and read state. A user reads **only** their own inbox
  (BR-NOTIF-04).
- **FR-NOTIF-07** A user reads their **unread count** (for the shell badge) cheaply (a counted query, not a
  full list).
- **FR-NOTIF-08** A user **marks a notification read** (single) or **marks all read**; following a
  notification's deep-link marks it read and navigates. Read state is the **only** mutable field on a
  notification (BR-NOTIF-05); everything else is immutable.

### Preferences

- **FR-NOTIF-09** A user with `NOTIFICATION.PREFERENCE.MANAGE` reads and sets **their own** preferences per
  notification type, per (user, company): **mute** the type, or enable/disable each **channel** for the type.
  Absent a preference, the type's defaults apply (BR-NOTIF-06). A user sets only their own preferences.
- **FR-NOTIF-10** The system exposes the **notification-type catalogue** (read) so the preferences screen can
  list every tunable type with its default channels + severity + description.

### Administration

- **FR-NOTIF-11** A user with `NOTIFICATION.ADMIN` **views the type catalogue** (defaults, audience, channels,
  severity) and **enables/disables a type per company** (a company-level on/off that overrides the type
  defaulting on — when off, the type never fires for that company, logged `SUPPRESSED` at company level).
  Editing templates / audiences is deferred (§2).
- **FR-NOTIF-12** A user with `NOTIFICATION.ADMIN` **reads the delivery log** (paged, filterable by channel /
  outcome / type / recipient / date), scoped to their company, to diagnose delivery (who got what, when, and
  why a send failed or was suppressed). The log is **append-only** — no edit/delete (BR-NOTIF-10).

### Configuration & platform

- **FR-NOTIF-13** Notification **types** are **seeded** (system-owned) with their audience permission, default
  channels, severity, and templates, additively in the migration; the four example types
  (`LOW_STOCK`, `INVOICE_OVERDUE`, `PAYMENT_RECEIVED`, `APPROVAL_PENDING`) plus the obvious event-driven ones
  (`GOODS_RECEIVED`, `DELIVERY_CONFIRMED`) ship in v1. New types are added by their owning module additively
  (a seed row + a producer trigger), never at runtime in v1.
- **FR-NOTIF-14** Notifications, types, preferences, and delivery-log reads/writes are **gated by
  `@perm.has` / `@perm.scoped`** on `NOTIFICATION.*` permission codes (never `hasAuthority`), **scoped per
  company** with `assertCanActIn` on **every** read path. Admin actions (type enable/disable) are **audited**
  (NFR-NOTIF-03). The dispatcher/scanner run as SYSTEM under the source company context and create
  notifications without a human caller (the fan-out itself is not a permissioned user action; the recipients'
  *reads* are permissioned).

## 6. Business rules (invariants)

> Ratified-style invariants. A violation that leaks a notification across companies, loses a trigger,
> double-fires a deduped condition, blocks a business transaction on an email failure, or makes the delivery
> log non-append-only is a **defect**.

- **BR-NOTIF-01 — Reuse the outbox; never the in-memory bus.** Notifications are triggered via the
  **transactional outbox** (the dispatcher is a `DomainEventHandler`) — at-least-once, idempotent,
  crash-safe. Spring `ApplicationEventPublisher` is **forbidden** (loses events on crash, PROJECT-CONVENTIONS).
  The new trigger events are published **in the producer's own transaction** (the outbox guarantee).
- **BR-NOTIF-02 — Per-company isolation.** Every notification, preference, delivery-log row, and read belongs
  to **exactly one company**; audience resolution is **within the source company only**; no read or fan-out
  crosses company scope. `assertCanActIn` guards every read path. Cross-company leakage is a **release
  blocker** (NFR-NOTIF-01).
- **BR-NOTIF-03 — Audience = holders of the type's permission in the source company (+ branch).** The
  recipients of a notification type are resolved as the users **holding the type's audience permission code**
  in the source company (narrowed to the source branch when the rule says so), via IAM permission resolution
  (`resolvePermissionCodes`). No notification is sent to a user without the relevant permission (so a
  notification never reveals something the user could not otherwise see).
- **BR-NOTIF-04 — A user reads only their own inbox + preferences.** The in-app inbox and preference reads are
  scoped to the **calling user** (in their active company); a user cannot read another user's notifications or
  set another user's preferences.
- **BR-NOTIF-05 — A notification is immutable except its read state.** Once created, a notification's type,
  company, branch, recipient, channel, title, body, deep-link, and severity are **immutable**; only
  **read/unread** (for the in-app channel) changes. There is no edit/delete of a notification (a stale one is
  just read or ignored).
- **BR-NOTIF-06 — Preference precedence, recorded suppression.** Routing = the type's defaults, overridden by
  the company-level type toggle (off → suppressed company-wide), overridden by the user's per-type mute /
  per-channel preference. A suppressed delivery (muted, channel-disabled, no email, company-off) is recorded
  **`SUPPRESSED`** in the delivery log — never silently dropped (so "why didn't I get it" is answerable).
- **BR-NOTIF-07 — A trigger is processed exactly once (idempotent), even with zero recipients.** Each trigger
  (event or scan condition) is deduped via `IdempotencyGuard` (events) / the scanner's last-notified marker
  (scans). A redelivered event or a re-run scan does **not** create duplicate notifications. A trigger with
  zero recipients is still marked processed (it does not re-run).
- **BR-NOTIF-08 — Time-based triggers fire once per crossing.** The scanner notifies **once** per newly-crossed
  condition (a newly-overdue invoice; a (product, branch) newly at/below reorder), recording it notified;
  it does **not** re-notify an already-notified condition on subsequent runs. Low-stock **re-arms** when stock
  recovers above reorder (a later dip re-fires); invoice-overdue does not re-fire for the same invoice (it is
  overdue once until paid/settled).
- **BR-NOTIF-09 — Email is best-effort and never blocks anything.** An email send failure is logged `FAILED`
  and retried within the dispatcher's cap, but it **never** rolls back the business transaction, the trigger
  consume, or the in-app notification. The in-app channel is the reliable channel; email is best-effort.
- **BR-NOTIF-10 — The delivery log is append-only.** Every dispatch attempt is a new row; outcomes transition
  `PENDING`→`SENT`/`FAILED` on the **same attempt row** via the dispatcher (a controlled state machine, not a
  user edit); a retry is a **new** attempt row. No user-facing edit/delete of the log (NFR-NOTIF-06).
- **BR-NOTIF-11 — Types are seeded + system-owned; soft-deleted, never hard-deleted.** The notification-type
  catalogue is seeded additively and carries `MasterStatus`; a retired type is soft-deleted (`INACTIVE`),
  never physically removed (historical notifications keep their type reference). Users tune preferences against
  types; they do not create/delete types in v1.
- **BR-NOTIF-12 — Deep-link by uid, not id.** A notification's source link addresses the source entity by its
  stable **uid** (the URL/route identity), never the numeric id, consistent with the platform uid/id duality.
- **BR-NOTIF-13 — No money, no GL.** Notifications hold **no `Money`** and post **no journals**. A monetary
  figure in a body (e.g. a received-payment amount) is a **formatted display string** baked into the template
  at fire time, not a stored `Money` or a journal FK. Notifications are a sink, not a financial actor.
- **BR-NOTIF-14 — Designed to the approvals contract, not coupled to it.** The `APPROVAL_PENDING` type is
  seeded and ready, but notifications has **no build dependency** on the approvals engine: if approvals is not
  present, the type never fires (no error). When approvals ships, it emits the trigger event and the type
  begins firing — no notifications change needed (forward-compatible, §9).

## 7. Process flows (happy path + main unhappy paths)

### 7.1 Low-stock (scanner trigger, IN_APP + EMAIL) — happy path
1. The `@Scheduled` scanner runs (hourly) for company C; it queries stock-on-hand and finds product P at
   branch B has dropped to/below reorder level and is **not** flagged low-notified.
2. It raises a `LOW_STOCK` trigger (type `LOW_STOCK`, company C, branch B, source = the stock-on-hand uid,
   template values = product name, on-hand qty, reorder level).
3. Audience resolves to the users in C holding `STOCK.VIEW` (narrowed to branch B). For each, per channel
   (`IN_APP`, `EMAIL`) not disabled: an in-app notification is created **unread** (logged `SENT`); an email is
   sent best-effort (logged `SENT`/`FAILED`/`SUPPRESSED`).
4. The scanner records product P @ branch B as **low-notified** (dedup). Next run does **not** re-fire while it
   stays low. When P @ B recovers above reorder, the marker clears (re-armed).
5. The storekeeper sees the badge increment, opens the inbox, clicks the notification → navigates to the
   stock-on-hand row (marks read).

### 7.2 Invoice-overdue (scanner trigger) — happy path
1. The scanner finds AR invoice INV-123 in company C past its due date, not yet overdue-notified.
2. Raises `INVOICE_OVERDUE` (audience `AR.VIEW`, source = the AR invoice uid, values = invoice number,
   customer, balance, days overdue). Recipients get in-app + email per their preferences.
3. Records INV-123 overdue-notified; does **not** re-fire next run. (When the invoice is settled, the open
   item closes — it is no longer in the overdue scan set.)

### 7.3 Payment-received (new trigger event, IN_APP) — happy path
1. A credit controller records an AR receipt for customer X in company C; the AR receipt service, **in its own
   transaction**, publishes a `payment-received` trigger event (one `outbox.publish` line) carrying the
   receipt uid + amount + customer.
2. The outbox dispatches the event to the notifications dispatcher (a `DomainEventHandler`), under company C's
   context, idempotently.
3. The dispatcher raises a `PAYMENT_RECEIVED` notification (audience `AR.VIEW`, IN_APP only by default);
   recipients see it in-app. The receipt recording itself committed independently (BR-NOTIF-09) — the
   notification rides the outbox, so even a dispatcher crash re-delivers it.

### 7.4 Approval-pending (designed-to-contract) — happy path
1. (When approvals ships) a PO crosses a threshold needing approval; the approvals engine, in its transaction,
   publishes an `approval-pending` trigger naming the approver permission + the doc uid.
2. The notifications dispatcher raises an `APPROVAL_PENDING` notification to the holders of the approver
   permission. (Before approvals ships, no such event exists; the type sits dormant — no error.)

### 7.5 Main unhappy paths
- **Zero recipients** (no user holds the audience permission in the company) → no notifications created; the
  trigger is **marked processed** (BR-NOTIF-07); the delivery log may record a company-level "no audience"
  diagnostic (optional). No re-run.
- **Recipient has muted the type / disabled the channel** → a `SUPPRESSED` delivery-log row is written for that
  (recipient, channel); no in-app row / no email (BR-NOTIF-06). The user can see in their own log why (admin
  can too).
- **Recipient has no email** (`EMAIL` channel) → logged `SUPPRESSED`; the in-app channel still delivers.
- **SMTP send fails** → logged `FAILED`, retried within the cap; the in-app notification + the business
  transaction are unaffected (BR-NOTIF-09). Exhausted retries stay `FAILED` (a future manual resend is the
  deferred hook).
- **Redelivered trigger event** (outbox at-least-once) → `IdempotencyGuard` short-circuits; no duplicate
  notifications (BR-NOTIF-07).
- **Scanner runs twice / overlaps** → the last-notified marker dedupes; no duplicate overdue/low-stock
  notifications (BR-NOTIF-08); the scanner is safe to run on any single instance (multi-instance scheduling
  hardening is a platform concern, NFR-NOTIF-05).
- **Company has the type disabled** (admin toggle off) → the trigger resolves no deliveries for that company;
  a company-level `SUPPRESSED` is recorded (BR-NOTIF-06); other companies are unaffected.
- **Stale deep-link** (the source entity later voided) → the notification still lists; the link resolves to
  the (now-voided) entity's view (or a "no longer available" state). Notifications are not transactionally
  tied to source lifecycle (a notification is a point-in-time message, BR-NOTIF-05).

## 8. Non-functional

- **NFR-NOTIF-01 — Tenant isolation.** Every notification, preference, delivery-log row, audience resolution,
  and read is scoped by `company_id`; `assertCanActIn` guards every read path; audience resolution is within
  the source company only. Cross-company leakage is a **release blocker**.
- **NFR-NOTIF-02 — Idempotent, crash-safe triggering.** Event triggers use `IdempotencyGuard` +
  `processed_events`; scanner triggers use the last-notified marker. At-least-once delivery never produces a
  duplicate notification for the same trigger (BR-NOTIF-07/08). A crash mid-fan-out re-delivers the trigger
  and resumes without duplicating already-created notifications (the per-recipient creation is itself
  idempotent on (trigger, recipient, channel)).
- **NFR-NOTIF-03 — Audit.** Admin actions (enable/disable a type per company) are written to the IAM
  append-only audit trail (actor, action, company, target type). The **delivery log** is the operational
  record of sends (distinct from the security audit). Notification *reads* by a user are not individually
  audited (high volume, low security interest) beyond the inbox itself.
- **NFR-NOTIF-04 — Volume safety.** Fan-out is bounded: the audience is the holders of one permission in one
  company (tens, not thousands, at v1 scale); the scanner is deduped (one notification per crossing). v1 has
  no digest/throttle, so the type defaults must avoid spammy channels (e.g. `PAYMENT_RECEIVED` defaults
  IN_APP-only). A pathological volume is mitigated by the per-type company toggle (admin can turn a noisy type
  off). Digest/throttle is the deferred richer answer (§2).
- **NFR-NOTIF-05 — Scheduling & delivery model.** The scanner is `@Scheduled` (the outbox dispatcher's
  pattern); the in-app inbox is **poll/badge-on-load** (no WebSocket in v1). The scanner is single-instance-
  safe via its dedup markers; multi-instance scheduling coordination (one runner) rides the platform's
  outbox-scaling work (deferred, PATH-TO-FULL-ERP §3.13) — not a notifications concern.
- **NFR-NOTIF-06 — Append-only delivery log + immutable notifications.** The delivery log is append-only
  (no edit/delete); a notification is immutable except its read flag (BR-NOTIF-05/10). Structural, not policy.
- **NFR-NOTIF-07 — Forward-compatibility.** The channel enum reserves SMS/PUSH/WEBHOOK; the type row reserves
  an attachment-reference column (the documents hook); the audience model can extend to explicit subscriber
  lists; templates are data (localisable). Building these is deferred; precluding them is a defect.
- **NFR-NOTIF-08 — Performance.** The unread-count query is a cheap counted index read; the inbox list is
  paged + indexed on (recipient, company, created-at) and a partial index on unread. The scanner queries are
  bounded by the source read models' existing indexes (AR open items, stock-on-hand reorder). No notification
  read or scan table-scans at production volume.
- **NFR-NOTIF-09 — No coupling that forms a cycle.** Notifications **consumes** events (depends on
  `platform.events`) and **reads** source modules' DTO/read models for template values + the scanner queries;
  no source module depends on `notifications`. Producing a new trigger event is the producer publishing to the
  platform outbox (it does not import notifications). The module is a **leaf consumer** (ArchUnit: no edge
  back into notifications), like GL/Stock are leaf consumers of sales events.

## 9. Assumptions

- The **transactional outbox** (ADR-0009) exists and is consumed as designed: a new `DomainEventHandler` is
  auto-discovered and invoked per matching event in a per-event TX, with `IdempotencyGuard` +
  `processed_events`. The notification dispatcher is exactly such a consumer.
- **IAM** provides: `app_users` with a nullable `email`; `user_branch` many-branch membership; roles →
  permission codes; and a resolution query for "the permission codes a user holds in (company, branch)"
  (`PermissionResolver.resolvePermissionCodes`) usable **outside a request** (the dispatcher/scanner run as
  SYSTEM) — i.e. a repository-level query that takes (userId, companyId, branchId), independent of
  `RequestContext`. (If the only resolution path is request-bound, ADR-0024 adds a repository query for the
  fan-out — an additive IAM read, not a behaviour change. Flagged OQ-NOTIF-04.)
- **AR** exposes a read model for **open items past due** (the ageing query already exists — reporting / AR
  ageing) the scanner can query for invoice-overdue; **AR receipt recording** has a service method where a
  one-line trigger publish can be added (for payment-received). Both are additive to AR (its PR), not a
  notifications change.
- **Stock** exposes stock-on-hand with **reorder level** (it does — the low-stock indicator exists, ADR-0010)
  the scanner queries for low-stock.
- **Email infrastructure**: an SMTP provider + `spring-boot-starter-mail` (NOT currently in the backend pom —
  ADR-0024 adds it as the email-sender dependency). Provider credentials are environment config (the secrets
  pass, PATH-TO-FULL-ERP §3.13). Until configured, email sends are best-effort and log `FAILED`/skip; the
  in-app channel is unaffected.
- The **approvals engine** is **not** required for v1 to ship; the `APPROVAL_PENDING` type is designed to its
  expected trigger-event contract and lies dormant until approvals lands (BR-NOTIF-14).
- The **documents/PDF** capability is **not** required for v1; notifications carry a deep-link, and the
  attachment hook is reserved (the optional `depends-on`).
- All platform invariants hold: `uid` VARCHAR(26) ULID, `company_id` scalar, `MasterStatus` on the type
  catalogue, `@perm` gating, `assertCanActIn`, additive Flyway migration, audit on admin actions.

## 10. Accepted boundary (the v1 line, explicit)

- **In:** in-app + email channels; outbox-event triggers (existing + new) + a scheduled scanner; a seeded
  notification-type catalogue with permission-based audience + default channels + severity + templates;
  per-user preferences (mute / per-channel); per-company type enable/disable (admin); an append-only delivery
  log; the four example types + the obvious event-driven ones; the in-app inbox API (list / unread-count /
  mark-read) + the preferences API + the admin catalogue/log API.
- **Out (deferred, not precluded):** SMS/push/webhook; rendered document attachments; explicit subscriber
  lists/distribution groups; user/admin-authored types + template editing; digests/batching/quiet-hours/
  per-user throttle; read receipts/acknowledgement/escalation; manual resend; role/company default overrides;
  i18n/Swahili templates; real-time WebSocket/SSE in-app push.
- **Out (other modules' jobs):** the approvals engine (a separate module emitting the approval trigger); the
  documents/PDF renderer (a separate capability); the producing modules' own behaviour (notifications only
  adds an additive trigger publish where source (B) is chosen).

## 11. Open questions (recommended defaults adopted; none blocks the ADR/build)

- **OQ-NOTIF-01 (owner) — which trigger sources ship in the first slice?** Recommended default: **all three**
  (existing events + the payment-received new trigger + the scanner for overdue/low-stock) so the four example
  types all work end-to-end. If the owner wants a thinner first cut, **start with the scanner + existing
  events** (no producer changes at all) and add the new trigger events in a fast follow. **Load-bearing** —
  it sizes the first slice and the cross-module touch list. Default: all three.
- **OQ-NOTIF-02 (owner) — is `approval-pending` in v1 or deferred with approvals?** Recommended default:
  **seed the `APPROVAL_PENDING` type now, do not build the trigger** (it ships with the approvals engine).
  v1 then delivers low-stock + invoice-overdue + payment-received fully and approval-pending lights up later.
  Default: type seeded, trigger deferred to approvals.
- **OQ-NOTIF-03 (owner) — the audience permission per type.** The §3.3 table is the recommended default
  (`STOCK.VIEW` for stock types, `AR.VIEW` for AR types, the approve perm for approval). Owner confirms these
  are the right audiences (e.g. should `INVOICE_OVERDUE` go to `AR.VIEW` broadly or a narrower
  credit-controller permission?). Tunable as seed data; default stands.
- **OQ-NOTIF-04 (architect/ADR) — recipient resolution outside a request.** The fan-out runs as SYSTEM (no
  `RequestContext`), so it needs a **repository-level** "users holding permission P in company C (branch B)"
  query. Recommended: add an IAM repository query (additive read) ADR-0024 specifies; the dispatcher uses it.
  An ADR decision, not a business blocker.
- **OQ-NOTIF-05 (owner) — default channels per type / spam posture.** Recommended default: `PAYMENT_RECEIVED`
  is **IN_APP-only** (high frequency, low urgency); warnings (`LOW_STOCK`, `INVOICE_OVERDUE`, `APPROVAL_PENDING`)
  are **IN_APP + EMAIL**. Tunable as seed + per-user preference. Default stands; owner confirms before go-live.
- **OQ-NOTIF-06 (architect/ADR) — scanner cadence + dedup state placement.** Recommended: hourly (configurable),
  dedup markers in the notifications module (a per-condition "last notified" record), so the scanner never
  writes to source tables. An ADR decision; default hourly.
- **OQ-NOTIF-07 (owner) — attachment hook timing.** Reserve the attachment-reference column now (documents
  module not built); wire actual attachments when documents/PDF ships. Recommended: reserve now, build later.
  Default stands.
- **OQ-NOTIF-08 (owner) — should AP payments also notify (`PAYMENT_RECEIVED`/a `PAYMENT_MADE` type)?** v1
  default: AR receipts notify `PAYMENT_RECEIVED`; AP payment notifications are deferred (additive: a new type
  + an AP trigger). Default: AR only in v1.
