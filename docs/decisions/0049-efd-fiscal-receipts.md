# ADR-0049: EFD / fiscal-receipt persistence + fiscalisation seam (TRA)

- **Status:** Proposed (2026-07-04) — awaits owner sign-off on the `V82` DDL (migration-approval standing rule) and on the flagged decisions below.
- **Deciders:** Owner + Solutions Architect
- **Deferred item:** D-6 (`docs/DEFERRED-ITEMS.md`). **Effort:** L. **Migrations:** `V82` (provisional — re-verify next-free vs `origin/develop` at build; V80/V81 are taken by D-1).
- **Related:** ADR-0008 (sales invoice lifecycle + finalise), ADR-0029 (POS quick-sale reuses the invoice path), ADR-0042 (POS transaction integrity/idempotency), ADR-0004 (audit aspect / append-only), ADR-0009 (transactional outbox), ADR-0043 (schema freeze / durable DB — additive-only), ADR-0044 (supermarket readiness — fiscal/EFD flagged there as a gap).

## Context

Counter and POS sales in Tanzania must issue a **fiscal (EFD/VFD) receipt** registered with the TRA — a tax-authority-signed receipt number + verification URL/QR. Today the ERP has **no fiscalisation record and no device/API integration**; "EFD receipt" is a persona expectation (Sabina, round-2 interviews) with no backing feature (`docs/DEFERRED-ITEMS.md` §D-6, ADR-0044 flagged it as critical for the supermarket vertical).

**Hard reality constraint driving this ADR:** we cannot integrate a real TRA VFD/EFD device or API in this codebase — no credentials, no device, no vendor spec. Fabricating a fake fiscal number would be worse than nothing: a fake TRA receipt number is a compliance and legal hazard (it looks like a filed tax document but is not). So D-6 here is scoped to the **honest, buildable slice**: the persistence spine, a provider seam a real TRA adapter drops into later, an honest default that never fabricates, a manual issue+retry flow with a clear status lifecycle, and status UI on the invoice. The real adapter, async queue, and offline buffering are explicitly out (named follow-ups below).

Facts from the code that shape the design (verified 2026-07-04):

1. **A fiscal receipt is per *finalised* invoice.** `SalesInvoiceServiceImpl.finalise` (line 235) assigns the invoice number, freezes totals + the tax summary, stamps the FX triple, and emits `SALE.FINALISED` on the outbox. Only after finalise does an invoice have the number + frozen totals a fiscal receipt needs.
2. **POS is covered for free.** `PosSaleServiceImpl.processSale` builds a DRAFT invoice, adds lines/tenders, and calls `invoiceService.finalise(...)` (line 189). A fiscal flow attached to FINALISED sales invoices therefore covers POS with no POS-specific code.
3. **The `'invoice'` scope target already exists.** `ScopeGuard.companyIdOf` maps `case "invoice" -> salesInvoices.findCompanyIdByUid(uid)` (line 496). Fiscal endpoints addressed by the invoice uid reuse it — no new `ScopeGuard` target, no route-guard/scope-key drift (the standing parity rule).
4. **Seller tax identity exists; buyer does not.** `Company` carries `taxId` (TIN) + `vrn` (line 44/49). `Customer` has `taxExempt`/`taxExemptionRef` but **no buyer TIN**. The seam DTO carries `customerTin` as nullable; capturing it is a named follow-up.
5. **`ModuleBoundaryTest` enforces controller↛repository, service↛web, and audit-append-only only** — the "no cross-module entity import" convention is not machine-enforced (sales already imports products per ADR-0048). We still keep the seam clean so the real adapter carries no sales dependency.
6. **The permission seed auto-grants ORG_ADMIN** via a CROSS JOIN (`R__seed_permissions.sql` line 251). Adding `FISCAL.*` there grants ORG_ADMIN automatically and self-heals (repeatable migration).

## Decision

### 1. Trigger model — manual, decoupled from finalise

Fiscalisation is a **manual "Issue EFD receipt" action on a FINALISED invoice**, a separate retryable record — **not** wired into `finalise()`.

- `finalise()` is untouched and never blocks or fails on fiscalisation. The invoice aggregate's transaction stays free of any external call.
- Guard: only `FINALISED` invoices can be fiscalised (a `DRAFT` has no number/frozen totals; a `VOID` must not be fiscalised).
- POS uses the same action. Auto-issue-at-finalise (print at the till) is a follow-up gated on a real adapter + config — a synchronous device call inside the finalise TX is exactly the coupling we refuse today.

**Why decoupled, not auto-on-finalise:** (a) no device is wired, so auto would either block sales (unacceptable) or spray `NOT_CONFIGURED` rows on every sale (noise); (b) it matches the counter workflow — the receipt is issued on demand at the till; (c) it keeps an external, failure-prone, latency-bearing call out of the money-path transaction. If fiscalisation failed inside `finalise()` and we blocked, a device outage would halt all sales; if we swallowed the failure, we'd hide it. A separate record with an explicit status is the honest model.

### 2. Default provider — honest `NOT_CONFIGURED`, never fabricate

The active `FiscalisationProvider` defaults to **`NotConfiguredFiscalisationProvider`**, which returns a `NOT_CONFIGURED` outcome — it **never** invents a fiscal number, URL, or signature. A `NOT_CONFIGURED` receipt row records that fiscalisation was attempted and no device was configured; it is not, and never looks like, a real TRA receipt (its `provider_code` and `status` say so).

A **simulated** provider (deterministic fake number + placeholder verification URL, `provider_code = SIMULATED`) is available **opt-in for dev/QA only**, behind `erp.fiscal.provider=simulated`. It is **never** the prod default, and the config **fails fast under the `prod` profile** (mirrors the bootstrap-password guard) so a demo provider can never masquerade as production fiscalisation. A `SIMULATED` row is visibly marked and never confusable with `TRA_VFD`.

**Provider selection** is a `@ConfigurationProperties(prefix = "erp.fiscal")` `provider` value: `none` (default → `NotConfiguredFiscalisationProvider`), `simulated` (dev/QA), and later `tra` (the real adapter). A small `@Configuration` picks the active bean; absent config resolves to `none`.

### 3. Seam placement — port in `platform.fiscal`, spine in the `sales` module

- **The seam (port) lives in `com.erp.platform.fiscal`:** the `FiscalisationProvider` interface, its input DTO (`FiscalInvoiceDataDto`), its output (`FiscalisationResult` + `FiscalisationOutcome` enum), the `NotConfiguredFiscalisationProvider` default, the optional `SimulatedFiscalisationProvider`, and `FiscalisationProperties`. Platform is cross-cutting infra everything may depend on (precedent: `FxDocumentConverter` in `platform.common.money`, `OutboxPublisher` in `platform.events`). The input is a **provider-agnostic snapshot record** — plain strings/BigDecimals — never a sales entity.
- **The persistence + orchestration spine lives in the `sales` module:** `FiscalReceipt` entity + `FiscalReceiptStatus` enum (`domain.entity`/`domain.enums`), `FiscalReceiptRepository`, `FiscalReceiptService` + `FiscalReceiptServiceImpl`, and `FiscalReceiptDto`. The service owns the invoice data (it reads `SalesInvoiceRepository`/`SalesInvoiceLineRepository` **in-module**), maps `SalesInvoice` + lines → `FiscalInvoiceDataDto`, injects the `FiscalisationProvider` bean from platform, and persists the outcome.
- **The real TRA adapter (follow-up) is a drop-in** `@Component implements FiscalisationProvider` in `com.erp.platform.fiscal.tra` — it depends only on the platform seam (interface + DTOs), never on the sales module. Selecting it is a config change (`erp.fiscal.provider=tra`), not a code change to sales.
- **Controller** `FiscalReceiptController` in flat `com.erp.api`, addressed by the invoice uid.

This satisfies `ModuleBoundaryTest`: the controller goes through the service (no repository), the service never touches the web layer, and the seam introduces no controller→repository or cross-module entity coupling. Sales→platform is an allowed dependency; the adapter→platform-seam dependency keeps the real integration free of any sales import.

### 4. Status lifecycle + retry/idempotency

`FiscalReceiptStatus`: **`PENDING`, `ISSUED`, `FAILED`, `NOT_CONFIGURED`, `VOID`.** (The proposed DDL omits `NOT_CONFIGURED` — it must be **added** to the enum and the `CHECK` constraint; see §5.)

- **PENDING** — reserved for the async/queued path (follow-up). In the synchronous slice a row is inserted PENDING and resolved within the same request/TX; it is not persisted PENDING on a committed success path. Kept as the column DEFAULT (safety net) and for the async follow-up.
- **ISSUED** — provider returned a real fiscal number + verification data. Terminal success; `fiscal_number`/`verification_url`/`signature`/`device_serial`/`issued_at`/`provider_code` populated. **Not re-fiscalisable** (re-issuing would double-report to TRA).
- **FAILED** — a real adapter attempted and errored (offline/rejected/timeout). `error_detail` set. **Retryable.**
- **NOT_CONFIGURED** — the honest default ran; nothing fabricated. `error_detail` carries a friendly "no device configured" message. **Retryable** (a later adapter wiring makes a retry succeed).
- **VOID** — reserved: set if the invoice is voided or a fiscal void is issued. The device round-trip for a fiscal void is a follow-up; the slice may set the status but does not call a device.

**One receipt row per invoice** — `UNIQUE(sales_invoice_id)`. **Issue and retry are the same idempotent operation** (a single endpoint):

- no row → create PENDING, attempt, resolve.
- row in `{FAILED, NOT_CONFIGURED, PENDING}` → **retry in place** on the same row (update status/fields, `provider_code`, `attempt_count++`, `last_attempt_at`, bump `@Version`).
- row `ISSUED` → **idempotent no-op**, return the existing receipt (never re-fiscalise).
- row `VOID` → 409, blocked.

Collapsing issue+retry into one idempotent call is safer than two endpoints (no "which do I call?" ambiguity, no path that can re-fiscalise an ISSUED invoice). A split `:retry` endpoint is a viable alternative (see Alternatives).

### 5. `V82` DDL (proposed — pending owner approval)

Plain **transactional** migration (no `CREATE INDEX CONCURRENTLY` — this repo has no non-transactional migration mode; the V78 / D-1 V81 lesson). `fiscal_receipts` is a new/empty table, so constraints + index build instantly. Adjustments vs `docs/proposed-migrations/V82__fiscal_receipts.sql`: **add `NOT_CONFIGURED`** to the status check, **add `provider_code`**, **add `attempt_count` + `last_attempt_at`**, **add a `(company_id, status)` worklist index**.

```sql
CREATE TABLE fiscal_receipts (
    id               BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    uid              VARCHAR(26)   NOT NULL,
    company_id       BIGINT        NOT NULL,
    branch_id        BIGINT        NOT NULL,
    sales_invoice_id BIGINT        NOT NULL,
    status           VARCHAR(20)   NOT NULL DEFAULT 'PENDING',   -- PENDING|ISSUED|FAILED|NOT_CONFIGURED|VOID
    provider_code    VARCHAR(30),                                -- NOT_CONFIGURED | SIMULATED | TRA_VFD (which provider produced this row)
    fiscal_number    VARCHAR(60),                                -- device receipt / verification number (ISSUED only)
    verification_url VARCHAR(500),                               -- TRA verification / QR URL (ISSUED only)
    signature        VARCHAR(512),                               -- device signature (ISSUED only)
    device_serial    VARCHAR(60),                                -- issuing device serial (ISSUED only)
    issued_at        TIMESTAMPTZ,                                -- device-reported issue time (ISSUED only)
    attempt_count    INT           NOT NULL DEFAULT 0,           -- retry diagnostics
    last_attempt_at  TIMESTAMPTZ,
    error_detail     VARCHAR(500),                               -- last failure/not-configured reason (bounded, non-leaky)
    version          BIGINT        NOT NULL DEFAULT 0,
    created_at       TIMESTAMPTZ   NOT NULL DEFAULT now(),
    created_by       BIGINT,
    updated_at       TIMESTAMPTZ,
    updated_by       BIGINT,

    CONSTRAINT uq_fiscal_receipt_uid     UNIQUE (uid),
    CONSTRAINT uq_fiscal_receipt_invoice UNIQUE (sales_invoice_id),   -- one fiscal receipt per invoice
    CONSTRAINT fk_fiscal_receipt_company FOREIGN KEY (company_id)       REFERENCES companies (id),
    CONSTRAINT fk_fiscal_receipt_branch  FOREIGN KEY (branch_id)        REFERENCES branches (id),
    CONSTRAINT fk_fiscal_receipt_invoice FOREIGN KEY (sales_invoice_id) REFERENCES sales_invoices (id),
    CONSTRAINT chk_fiscal_receipt_status CHECK (status IN ('PENDING','ISSUED','FAILED','NOT_CONFIGURED','VOID'))
);

CREATE INDEX ix_fiscal_receipt_company_status ON fiscal_receipts (company_id, status);
```

**Multi-tenancy stance:** transactional table — `company_id` + `branch_id` NOT NULL + FK, copied from the invoice at row creation; the blanket tenant predicate applies. **Identity:** `uid` (ULID) + numeric `id`, per `UidEntity`. **Audit:** append-only `audit_log` via `AuditService` (§6). **Optimistic lock:** `version`.

### 6. Permissions + audit

- Add to `R__seed_permissions.sql` (repeatable; ORG_ADMIN auto-granted via the CROSS JOIN):
  - `FISCAL.MANAGE` (module `sales`) — issue / retry / void a fiscal receipt.
  - `FISCAL.VIEW` (module `sales`) — read a fiscal receipt / status.
- Gates reuse the `'invoice'` scope target (endpoints addressed by the invoice uid): `@perm.scoped(#uid,'invoice','FISCAL.MANAGE')` / `...'FISCAL.VIEW'`.
- Every attempt + outcome is audited (`AuditService.record`) — fiscalisation touches a tax authority, so `FISCAL_RECEIPT_ISSUE_ATTEMPT` / `FISCAL_RECEIPT_ISSUED` / `FISCAL_RECEIPT_FAILED` (+ `NOT_CONFIGURED`) rows are mandatory, append-only.
- **No outbox event** in the slice — fiscalisation produces no cross-module side effect (no GL/stock). The async worker (follow-up) is where the outbox/queue enters.

### 7. Frontend (invoice-detail)

On a `FINALISED` invoice, a "Fiscal receipt" panel: fetch `GET .../fiscal-receipt`, render a status badge + action:

- absent/`PENDING` → "Not fiscalised" + **Issue EFD receipt** button (gated `FISCAL.MANAGE`).
- `ISSUED` → green "Fiscalised" + `fiscal_number` + `verification_url` link (QR image = follow-up).
- `FAILED` → red badge + friendly error + **Retry**.
- `NOT_CONFIGURED` → neutral badge + guidance ("No fiscal device is configured for this environment; contact your administrator") + Retry allowed.

Button visibility mirrors the existing finalize/void gating on `session` permissions. QR rendering + print/PDF layout are follow-ups.

### 8. Scope cut — explicitly OUT (named follow-ups)

1. **The real TRA VFD/EFD adapter** + credentials + the actual TRA device/API protocol (the bulk of the real integration).
2. **Async/outbox issuance queue** + a background worker that moves the provider call out of the request transaction (required before a real network adapter ships — see the known constraint below).
3. **Offline buffering / store-and-forward** when the device or TRA is unreachable.
4. **Z-report / daily fiscal summary / X-read** fiscalisation.
5. **Device drivers / local VFD hardware bridge.**
6. **QR-code image rendering + fiscal-receipt print/PDF** (the slice shows number + verification URL as text/link).
7. **Fiscal void round-trip** to the device on invoice void (the slice may set VOID status, not call a device).
8. **Auto-issue on finalise / POS-till auto-print** (gated on a real adapter + config).
9. **Buyer TIN capture** on the customer master / per invoice (the seam carries `customerTin` as nullable now).

## Consequences

- **Positive:** an honest, shippable fiscal spine + a clean drop-in seam; the real TRA adapter is a config change, not a sales-module change; POS is covered with zero POS code; no compliance hazard (nothing fabricates a fiscal number); the money-path (`finalise`) is untouched and never blocked by fiscalisation; reuses the existing `'invoice'` scope target and the auto-grant permission seed.
- **Known constraint (flagged for the follow-up):** the slice calls the provider **synchronously inside the service transaction**. That is fine for `NotConfigured`/`Simulated` (no I/O). **When the real TRA adapter lands, the network call must move out of the DB transaction** (claim PENDING in a short TX → call the provider with no TX → persist the result in a second short TX, or route via the outbox/async worker). This is called out so no one wires a blocking HTTP call into a row-locking transaction.
- **Contract additions:** new `FiscalReceiptDto`; new endpoints under `/api/v1/sales-invoices/uid/{uid}/fiscal-receipt`; `SalesInvoiceDto` unchanged (fiscal status is a separate fetch, keeping the invoice DTO stable). New config keys `erp.fiscal.provider` (+ future TRA endpoint/credentials keys).
- **Migration:** one additive transactional `V82`. Re-verify next-free vs `origin/develop` at build (V80/V81 are D-1). `scripts/check-migrations.sh` passes (no dup, additive). DDL requires owner approval before the file is created (standing rule).
- **Web:** one panel + two service methods + one TS model; axe-gated.

## Decisions requiring the owner

1. **Trigger model** — confirm **manual, decoupled** issue on a FINALISED invoice (recommended), not auto-on-finalise. (§1)
2. **Default provider** — confirm the honest **`NOT_CONFIGURED`** default + **simulated opt-in, prod-blocked** (recommended). Alternative: no simulated provider at all (dev sees only NOT_CONFIGURED). (§2)
3. **Issue = retry, one idempotent endpoint** (recommended) vs a separate `:retry` endpoint. (§4)
4. **Approve the `V82` DDL + version** (with `NOT_CONFIGURED`, `provider_code`, `attempt_count`/`last_attempt_at`, and the worklist index added) before the migration file is created. (§5)
5. **Permission granularity** — `FISCAL.MANAGE` + `FISCAL.VIEW` (recommended), tagged module `sales`. Alternative: fold VIEW into `SALES.INVOICE.VIEW`. (§6)
6. **Buyer TIN** — accept it as OUT for the slice (nullable seam field), captured in a follow-up when a real adapter defines the B2B requirement. (§8.9)

## Alternatives considered

- **Auto-fiscalise inside `finalise()`** — rejected: couples an external, failure-prone call to the money-path TX; with no device it either blocks sales or sprays NOT_CONFIGURED rows. A decoupled retryable record is honest and safe.
- **Simulated provider as the default** (fake number/QR for demos) — rejected as a default: a fabricated TRA number is a compliance hazard and risks a fake receipt reaching a customer. Kept only as an explicit, prod-blocked opt-in.
- **A standalone `com.erp.modules.fiscal` module owning the entity** — rejected for the slice: it would need the invoice's line/tax data, forcing either a cross-module entity import or a sales→fiscal service call for a side effect (both worse than keeping the spine in sales). The **seam** already lives in neutral `platform.fiscal`, which is what makes the real adapter module-independent; a dedicated fiscal module can absorb the spine later if fiscalisation grows beyond sales invoices.
- **Two endpoints (`:issue` + `:retry`)** — viable, but a single idempotent operation removes the ambiguity and the only path that could re-fiscalise an ISSUED invoice.
- **Storing fiscal fields directly on `sales_invoices`** — rejected: bloats the immutable invoice header, has no room for retry/attempt state or a status lifecycle, and couples the invoice's optimistic-lock version to fiscalisation retries. A child aggregate with its own version is correct.
