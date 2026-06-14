# PLATFORM / CROSS-CUTTING SERVICES — Test Cases

Exhaustive UI-driven test cases for the Platform domain: document render/templates/branding (PDF generation
log, template registry, company branding profile), notifications (in-app inbox, per-user preferences, admin
type-catalogue toggle + delivery log), the approvals engine (policy master CRUD, request lifecycle + the
approval inbox), and the read-only audit trail. All cases run against the deployed QA Angular app
(http://16.170.11.41/), navigate by ROUTE, interact by accessible role/label, pick resources by NAME via the
shared `<app-uid-picker>` (never by typed uid), assert the four screen states + pagination + axe, and check
the system conventions C1–C9.

## Modules/submodules covered

| Submodule | Frontend route | API base path / endpoints | Controller |
|---|---|---|---|
| Generated documents log + render | `/admin/documents`, `/admin/documents/uid/:uid` | `POST /api/v1/documents/render`, `GET /api/v1/documents/render` (inline), `GET /api/v1/documents/uid/{uid}/download`, `GET /api/v1/documents` (list), `GET /api/v1/documents/uid/{uid}` | `DocumentController` |
| Document template registry | `/admin/document-templates` | `GET /api/v1/documents/templates`, `PUT /api/v1/documents/templates/{uid}` | `DocumentTemplateController` |
| Document branding profile (singleton/company) | `/admin/document-branding` | `GET /api/v1/documents/branding`, `PUT /api/v1/documents/branding` | `DocumentBrandingController` |
| Notification inbox (per-user) | `/admin/notifications` | `GET /api/v1/notifications` (`?unread=`), `GET /api/v1/notifications/unread-count`, `POST /api/v1/notifications/uid/{uid}/read`, `POST /api/v1/notifications/read-all` | `NotificationController` |
| Notification preferences (per-user) | `/admin/notification-preferences` | `GET /api/v1/notification-preferences`, `PUT /api/v1/notification-preferences/{typeKey}` | `NotificationPreferenceController` |
| Notification type catalogue (admin) | `/admin/notification-types` | `GET /api/v1/admin/notifications/types`, `PUT /api/v1/admin/notifications/types/{typeKey}/state` | `NotificationAdminController` |
| Notification delivery log (admin) | `/admin/notification-deliveries` | `GET /api/v1/admin/notifications/deliveries` (`?channel=`, `?outcome=`) | `NotificationAdminController` |
| Approval policies (master CRUD) | `/admin/approvals/policies`, `/admin/approvals/policies/uid/:uid` | `POST /api/v1/approvals/policies`, `PUT .../uid/{uid}`, `POST .../uid/{uid}/deactivate`, `GET .../uid/{uid}`, `GET .../` (list) | `ApprovalPolicyController` |
| Approval requests (lifecycle + inbox) | `/admin/approvals/inbox`, `/admin/approvals/requests`, `/admin/approvals/requests/uid/:uid` | `GET .../inbox`, `GET .../uid/{uid}`, `GET .../` (list), `POST .../uid/{uid}/approve`, `POST .../uid/{uid}/reject`, `POST .../uid/{uid}/recall`, `POST .../uid/{uid}/cancel` | `ApprovalRequestController` |
| Audit trail (read-only) | `/admin/audit` | `GET /api/v1/audit` (`actorUid`, `action`, `targetType`, `targetUid`, `from`, `to`, `page`, `size`) | `AuditController` |

Navigation (verified in `web/src/app/layout/shell/shell.component.ts`):
- **Approvals** group → *My Inbox* (`/admin/approvals/inbox`, `APPROVALS.DECIDE`), *All Requests* (`/admin/approvals/requests`, `APPROVALS.REQUEST.VIEW`), *Approval Policies* (`/admin/approvals/policies`, `APPROVALS.POLICY.VIEW`).
- **Documents** group → *Generated Documents* (`/admin/documents`, `DOCUMENT.VIEW`), *Document Templates* (`/admin/document-templates`, `DOCUMENT.TEMPLATE.MANAGE`), *Document Branding* (`/admin/document-branding`, `DOCUMENT.BRANDING.MANAGE`).
- **Notifications** group → *Inbox* (`/admin/notifications`, `NOTIFICATION.VIEW`), *Preferences* (`/admin/notification-preferences`, `NOTIFICATION.PREFERENCE.MANAGE`), *Type Catalogue* (`/admin/notification-types`, `NOTIFICATION.ADMIN`), *Delivery Log* (`/admin/notification-deliveries`, `NOTIFICATION.ADMIN`).
- **Audit** (`/admin/audit`, `AUDIT.VIEW`).

### Backend-only / no-standalone-UI notes (verified — do NOT write UI-create cases for these)
- **Approval requests are NEVER submitted from a UI in this domain.** `submitForApproval` / `getApprovalState`
  are the in-process `ApprovalEngine` service interface that consuming modules call (PO, payroll, budget, etc.) —
  there is **no REST submit endpoint and no "create approval request" screen** (see the class javadoc on
  `ApprovalRequestController`). Approval requests in scope are therefore **seeded by performing a gated action in
  another module** (e.g. confirming a PO that crosses a policy amount band), then exercised here from the inbox /
  request screens. The frontend has list/inbox/detail screens only — no request-create component.
- **Approval policy CREATE happens inline on the list screen** (`approval-policy-list.component` "New policy" form);
  policy EDIT/DEACTIVATE happens on the detail screen. There is no separate `/create` route.
- **Document render** is exposed in the UI as the "Render document" inline form on `/admin/documents`. The GET
  `/render` inline-stream endpoint and the AR_STATEMENT parameterised render are used by the same screen; the
  remaining `DocumentType` values **PAYSLIP, QUOTATION, DEBIT_NOTE are reserved and NOT rendered in v1** (only
  INVOICE, AR_STATEMENT, PURCHASE_ORDER, GOODS_RECEIPT, DELIVERY_NOTE, CREDIT_NOTE render).
- **Branding is a per-company singleton** — GET/PUT take no uid (company resolved from `RequestContext`); the
  screen is an edit-form, not a list.
- **Notification "send" has no UI.** Notifications are produced server-side by triggers; the in-app inbox + delivery
  log are read views (inbox also marks-read). Inbox rows are seeded by triggering a notifiable event server-side.
- **Audit is append-only and read-only** — `AuditController` has no write endpoint; the screen has filter + paginate only.

## Permission codes in scope (exact — verified in `V20__approvals_engine.sql`, `V23__documents.sql`, `V25__notifications.sql`, `V1__baseline.sql`)

| Code | Module | Used by (endpoint) |
|---|---|---|
| `DOCUMENT.VIEW` | documents | list log, get-by-uid |
| `DOCUMENT.RENDER` | documents | render (POST + inline GET), download (scoped to `generateddocument`) |
| `DOCUMENT.TEMPLATE.MANAGE` | documents | list templates, update template (scoped to `documenttemplate`) |
| `DOCUMENT.BRANDING.MANAGE` | documents | get + update branding |
| `NOTIFICATION.VIEW` | notifications | inbox list, unread-count, mark-read (scoped to `notification`), read-all |
| `NOTIFICATION.PREFERENCE.MANAGE` | notifications | list + set preferences |
| `NOTIFICATION.ADMIN` | notifications | list types, toggle type state, delivery-log list |
| `APPROVALS.POLICY.MANAGE` | approvals | create policy, update (scoped `approvalpolicy`), deactivate |
| `APPROVALS.POLICY.VIEW` | approvals | policy list, policy get-by-uid (scoped) |
| `APPROVALS.DECIDE` | approvals | inbox, approve + reject (scoped `approvalrequest`) |
| `APPROVALS.REQUEST.VIEW` | approvals | request list, request get-by-uid (scoped), recall (scoped) |
| `APPROVALS.ADMIN` | approvals | cancel (scoped `approvalrequest`) |
| `AUDIT.VIEW` | platform | audit search/list |

> RBAC is by permission CODE, never role name. `@perm.has('CODE')` gates company-list reads;
> `@perm.scoped(#uid,'<resourceType>','CODE')` gates per-resource actions. A holder lacking the code → 403
> (nav item hidden / screen forbidden). `rootadmin` bypasses all checks and is org-wide — use only for positive setup, never negative-auth.

## Enums in scope (exact values, read from source)

- `DocumentType` = **{ INVOICE, AR_STATEMENT, PURCHASE_ORDER, GOODS_RECEIPT, DELIVERY_NOTE, CREDIT_NOTE, PAYSLIP, QUOTATION, DEBIT_NOTE }** — only the first six render in v1; last three are reserved and rejected.
- `RendererKey` = **{ TRANSACTIONAL_PDF, STATEMENT_PDF }** (TRANSACTIONAL_PDF for the doc types; STATEMENT_PDF for AR_STATEMENT).
- `NotificationChannel` = **{ IN_APP, EMAIL, SMS, PUSH, WEBHOOK }** — v1 sends only IN_APP + EMAIL; SMS/PUSH/WEBHOOK reserved.
- `NotificationSeverity` = **{ INFO, WARNING, CRITICAL }**.
- `DeliveryOutcome` = **{ PENDING, SENT, FAILED, SUPPRESSED }**.
- `SuppressionReason` = **{ MUTED, CHANNEL_DISABLED, NO_EMAIL, COMPANY_TYPE_OFF, NO_AUDIENCE }**.
- `ApprovalRequestStatus` = **{ PENDING, APPROVED, REJECTED, RECALLED, CANCELLED }** — terminal set = {APPROVED, REJECTED, RECALLED, CANCELLED}; PENDING is the only non-terminal state.
- `ApprovalStepStatus` = **{ PENDING, APPROVED, REJECTED, SKIPPED }** (SKIPPED set on never-reached steps after a reject).
- `DecisionAction` = **{ APPROVE, REJECT }**.
- `PolicyBranchScope` = **{ COMPANY_WIDE, BRANCH }** — BRANCH-scoped beats COMPANY_WIDE on a tie (branch-specificity wins).
- `MasterStatus` (policies, templates) = **{ ACTIVE, INACTIVE, ARCHIVED }**.

## Core business rules (asserted by cases)
- **Approval lifecycle:** a terminal request accepts no further decisions/recalls/edits (BR-APR-07). One **reject kills the whole chain** (later steps → SKIPPED). A decision is allowed **only on the current open step** = lowest-sequence PENDING step (BR-APR-04). APPROVED includes human-approved (full chain) and auto-approved (no policy matched).
- **Recall** is submitter-only (or `APPROVALS.ADMIN`) and only on PENDING (FR-APR-10). **Cancel** is `APPROVALS.ADMIN` only and only on a non-terminal request (FR-APR-15). Policy **edits affect only future submissions** — in-flight requests are unaffected (BR-APR-05).
- **Policy validation:** `branchScope=BRANCH` ⇒ `branchUid` required; `branchScope=COMPANY_WIDE` ⇒ `branchUid` must be null; `minAmount ≥ 0`; `maxAmount` null = unbounded top band; steps non-empty, sequences dense from 1 + unique.
- **Documents:** render of a reserved type (PAYSLIP/QUOTATION/DEBIT_NOTE) or a missing/cross-tenant source is rejected; the log is append-only (no edit/delete); download re-renders from the live source. `documentNumber` is the human label; `uid` only in URL.
- **Notifications:** mark-read enforces recipient-is-me (BR-NOTIF-04); inbox + count are scoped to caller's company; delivery log shows outcome + suppressionReason so a user can answer "why didn't I get it?". Type toggle is per-company.
- **Audit:** read-only/append-only; a non-root `AUDIT.VIEW` holder is confined to their **active company's** rows (root reads org-wide); filterable by actor/action/targetType/targetUid/date; sorted newest-first; wire carries only uids/usernames (no internal ids).

## Type/role variations exercised

| Dimension | Variations covered |
|---|---|
| User type | `rootadmin` (org-wide setup); `ORG_ADMIN` (holds APPROVALS.*/DOCUMENT.*/NOTIFICATION.ADMIN/AUDIT.VIEW); `ACCOUNTANT` / `PURCHASE_OFFICER` / `SALES_MANAGER` (decide steps by role code); a CUSTOM role (subset, e.g. DOCUMENT.VIEW only, no RENDER); a NO-PERMISSION user (forbidden + empty nav) |
| Permission split | VIEW-only vs MANAGE/DECIDE/ADMIN; e.g. DOCUMENT.VIEW without DOCUMENT.RENDER; APPROVALS.REQUEST.VIEW without APPROVALS.DECIDE; NOTIFICATION.VIEW without NOTIFICATION.ADMIN |
| DocumentType | each renderable type (INVOICE, AR_STATEMENT, PURCHASE_ORDER, GOODS_RECEIPT, DELIVERY_NOTE, CREDIT_NOTE) + a reserved type (PAYSLIP) |
| Approval policy | branchScope = COMPANY_WIDE vs BRANCH; bounded vs unbounded (maxAmount null) band; single-step vs multi-step chain |
| Approval request status | PENDING → APPROVED, PENDING → REJECTED, PENDING → RECALLED, PENDING → CANCELLED; auto-approved; illegal transitions on terminal requests |
| NotificationChannel/Severity/Outcome | filter delivery log by channel (EMAIL) and outcome (FAILED/SUPPRESSED); severity badge INFO/WARNING/CRITICAL; suppression reasons |
| Branch/company | single-branch vs multi-branch; default vs non-default branch policy; cross-tenant isolation (company A vs B); audit company-confinement |

---

# DOCUMENTS

### TC-PLAT-001 — Generated-documents log lists for the active company (DOCUMENT.VIEW)
- **Type:** Automated (Playwright)
- **Priority:** P1
- **Module / Submodule:** Generated documents (`/admin/documents` · `GET /api/v1/documents`)
- **Permission / Role:** `DOCUMENT.VIEW` — runs as `ORG_ADMIN`; also as NO-PERMISSION user → expect nav item hidden + route forbidden
- **Preconditions / Seed:** at least one rendered document exists for the company (seed via TC-PLAT-004 or API render).
- **Steps:**
  1. Login as ORG_ADMIN; navigate to `/admin/documents`.
  2. Observe the company selector defaults to the first company; the list loads.
  3. Read the table columns (document number, type, source, generated-at, download action).
- **Test Data:** company = "Acme Co (default)".
- **Expected Result:** table renders rows; each row shows `documentNumber` and a `documentType` badge; `meta` envelope drives pagination; HTTP 200, `ApiResponse<List<GeneratedDocumentDto>>` with `meta {page,size,totalElements,totalPages,hasNext}`.
- **Convention Assertions:** C2 envelope+meta; C4 all four states reachable; C5 paginator present (FIRST/PREV/numbers/NEXT/LAST), self-hidden when 1 page; C6 axe clean; C1 no raw uid column shown (uid only in the row link target); C8 dates ISO-formatted.
- **Negative / Edge:** NO-PERMISSION user → `/admin/documents` shows forbidden state and the Documents nav group items are hidden; direct API call returns 403.

### TC-PLAT-002 — Documents list four-state coverage (loading / empty / error / forbidden)
- **Type:** Both
- **Priority:** P2
- **Module / Submodule:** Generated documents (`/admin/documents`)
- **Permission / Role:** `DOCUMENT.VIEW` — runs as ORG_ADMIN; forbidden variant as NO-PERMISSION user
- **Preconditions / Seed:** a brand-new company with no rendered documents (for the empty state).
- **Steps:**
  1. Switch the company selector to the empty company → observe the empty state ("no documents") not a blank table.
  2. Throttle/deny the network and reload → observe the error state with a retry affordance.
  3. As NO-PERMISSION user, hit the route → forbidden state.
- **Expected Result:** loading spinner during fetch; distinct empty message; distinct error panel; distinct forbidden panel — never a silent blank.
- **Convention Assertions:** C4 four states distinct; C6 axe on each state; C3 forbidden path returns 403.
- **Negative / Edge:** empty + error must not be conflated; pagination hidden when empty.

### TC-PLAT-003 — Filter the documents log by DocumentType
- **Type:** Automated (Playwright)
- **Priority:** P2
- **Module / Submodule:** Generated documents (`/admin/documents` · `GET /api/v1/documents?type=`)
- **Permission / Role:** `DOCUMENT.VIEW` — runs as ORG_ADMIN
- **Variation:** DocumentType = INVOICE, then AR_STATEMENT
- **Preconditions / Seed:** rendered docs of mixed types exist.
- **Steps:**
  1. On `/admin/documents`, choose "Invoice" in the Type filter (label, not value).
  2. Assert only INVOICE rows show; URL/request carries `type=INVOICE`.
  3. Switch to "AR Statement" → assert AR_STATEMENT rows only.
  4. Switch to "All types" → full list returns.
- **Test Data:** filter values from the typed select (Invoice, AR Statement, Purchase Order, Goods Receipt, Delivery Note, Credit Note, All types).
- **Expected Result:** server-side filter applied (page resets to 0); badge per type; correct subset returned.
- **Convention Assertions:** C5 page resets to 0 on filter change; C2 meta reflects filtered count; C6 axe.
- **Negative / Edge:** filter with no matches → empty state; reserved types (PAYSLIP/QUOTATION/DEBIT_NOTE) are not even offered in the filter select.

### TC-PLAT-004 — Render an INVOICE document, source chosen via picker (DOCUMENT.RENDER)
- **Type:** Automated (Playwright)
- **Priority:** P1
- **Module / Submodule:** Render (`/admin/documents` inline form · `POST /api/v1/documents/render`)
- **Permission / Role:** `DOCUMENT.RENDER` — runs as ORG_ADMIN; also as a user with `DOCUMENT.VIEW` only → render control hidden/disabled, POST 403
- **Variation:** DocumentType = INVOICE; source = a FINALISED sales invoice
- **Preconditions / Seed:** a sales invoice exists (number visible in picker).
- **Steps:**
  1. On `/admin/documents`, click "Render document"; choose Type = Invoice.
  2. In the source picker (`<app-uid-picker>`) choose the invoice BY its invoice number (not by uid).
  3. Submit; observe success toast with the returned `documentNumber`; the new row appears at the top of the log.
- **Test Data:** invoice number "INV-2026-0007".
- **Expected Result:** `POST /render` returns `GeneratedDocumentDto` (with `downloadUrl`); HTTP 200; the log reloads showing the new row.
- **Convention Assertions:** C1 source selected via picker by name, uid stored under the hood — NO typed uid, no raw uid on screen; C3 RBAC (VIEW-only user cannot render); C2 envelope (single object auto-unwrapped); C6 axe on the form.
- **Negative / Edge:** submit with no source selected → inline "Source UID is required" validation, no request sent; VIEW-only user → render button absent.

### TC-PLAT-005 — Render each renderable DocumentType (parameterised)
- **Type:** Automated (Playwright, data-driven)
- **Priority:** P1
- **Module / Submodule:** Render (`/admin/documents` · `POST /api/v1/documents/render`)
- **Permission / Role:** `DOCUMENT.RENDER` — runs as ORG_ADMIN
- **Variation:** DocumentType ∈ { PURCHASE_ORDER, GOODS_RECEIPT, DELIVERY_NOTE, CREDIT_NOTE } (one render each; INVOICE in TC-PLAT-004, AR_STATEMENT in TC-PLAT-006)
- **Preconditions / Seed:** one source record per type (PO, goods receipt, delivery, sales return → credit note).
- **Steps:** for each type: open render form, select type, pick the source by its human number, submit.
- **Test Data:** PO "PO-0003", GRN "GRN-0002", delivery "DEL-0005", return "RET-0001".
- **Expected Result:** each render succeeds; correct `documentType` badge on the new row; correct source linkage.
- **Convention Assertions:** C1 picker-by-name for every source; C2 envelope; C6 axe.
- **Negative / Edge:** the picker option list for a type is scoped to that type (e.g. choosing GOODS_RECEIPT shows GRNs, not invoices).

### TC-PLAT-006 — Render an AR_STATEMENT (parameterised render, JSON params required)
- **Type:** Both
- **Priority:** P2
- **Module / Submodule:** Render (`/admin/documents` · `POST /api/v1/documents/render` with `sourceParams`)
- **Permission / Role:** `DOCUMENT.RENDER` — runs as ACCOUNTANT (holds DOCUMENT.RENDER)
- **Variation:** DocumentType = AR_STATEMENT (RendererKey STATEMENT_PDF; uses params not a single source uid)
- **Preconditions / Seed:** a customer with open AR items exists.
- **Steps:**
  1. Open render form; choose Type = AR Statement.
  2. The form switches to the params input (source uid field hidden); enter the JSON params (customerUid/fromDate/toDate).
  3. Submit.
- **Test Data:** params `{"customerUid":"<picked>","fromDate":"2026-01-01","toDate":"2026-06-30"}` (customer chosen by name via picker into the JSON).
- **Expected Result:** statement renders; row shows AR_STATEMENT badge; success toast.
- **Convention Assertions:** C8 dates ISO yyyy-MM-dd in params; C1 customer chosen by name; C6 axe.
- **Negative / Edge:** AR_STATEMENT submitted with empty params → inline "Source params (JSON) are required for AR Statement" validation; non-statement type submitted with no source uid → "Source UID is required".

### TC-PLAT-007 — Render rejects a reserved (non-v1) DocumentType
- **Type:** Manual
- **Priority:** P3
- **Module / Submodule:** Render (`POST /api/v1/documents/render`)
- **Permission / Role:** `DOCUMENT.RENDER` — runs as ORG_ADMIN
- **Variation:** DocumentType = PAYSLIP / QUOTATION / DEBIT_NOTE (reserved, not rendered in v1)
- **Preconditions / Seed:** none.
- **Steps:** call `POST /api/v1/documents/render` with `documentType=PAYSLIP` (these are not selectable in the UI form, so this is an API-level guard check).
- **Expected Result:** request is rejected (error envelope, 4xx) — no log row created. The UI never offers these types.
- **Convention Assertions:** C2 error envelope `{errors:[...]}`; C9 nothing persisted.
- **Negative / Edge:** confirms the v1 renderable set is enforced server-side, not just hidden in the UI.

### TC-PLAT-008 — Download a generated PDF (re-render from live source)
- **Type:** Automated (Playwright)
- **Priority:** P1
- **Module / Submodule:** Download (`/admin/documents` row action · `GET /api/v1/documents/uid/{uid}/download`)
- **Permission / Role:** `DOCUMENT.RENDER` (download is scoped to `generateddocument` + `DOCUMENT.RENDER`) — runs as ORG_ADMIN; also as DOCUMENT.VIEW-only user → expect 403 on download
- **Preconditions / Seed:** a generated-document row exists (TC-PLAT-004).
- **Steps:**
  1. On `/admin/documents`, click the row's "Download" action.
  2. Assert a PDF blob downloads with filename `<type>-<documentNumber>.pdf` and `application/pdf` content type.
- **Expected Result:** HTTP 200 `application/pdf`, `Content-Disposition: attachment; filename="invoice-INV-2026-0007.pdf"`.
- **Convention Assertions:** C1 the action targets the row's uid in the URL but the user never sees/types it; C3 RBAC — DOCUMENT.VIEW alone cannot download (needs DOCUMENT.RENDER).
- **Negative / Edge:** cross-tenant uid → 403/404 (scope guard on `generateddocument`); DOCUMENT.VIEW-only user → download fails 403.

### TC-PLAT-009 — Document detail by uid shows the log record (no raw uid in body)
- **Type:** Automated (Playwright)
- **Priority:** P2
- **Module / Submodule:** Document detail (`/admin/documents/uid/:uid` · `GET /api/v1/documents/uid/{uid}`)
- **Permission / Role:** `DOCUMENT.VIEW` (scoped to `generateddocument`) — runs as ORG_ADMIN
- **Preconditions / Seed:** a generated-document row.
- **Steps:** from the list, click a row to open its detail; read the metadata (number, type, source, generated-at, byte size, mime).
- **Expected Result:** detail renders the `GeneratedDocumentDto`; download action available.
- **Convention Assertions:** C1 uid appears only in the URL path, never as visible text/label; C8 generated-at ISO; C4 loading/error states on detail.
- **Negative / Edge:** unknown/cross-tenant uid → error/forbidden state.

### TC-PLAT-010 — Document template registry lists + toggle active/inactive (DOCUMENT.TEMPLATE.MANAGE)
- **Type:** Both
- **Priority:** P1
- **Module / Submodule:** Templates (`/admin/document-templates` · `GET/PUT /api/v1/documents/templates`)
- **Permission / Role:** `DOCUMENT.TEMPLATE.MANAGE` — runs as ORG_ADMIN; also as a user without it → nav item hidden + route forbidden
- **Preconditions / Seed:** seeded template registry rows for the company (one per renderable type).
- **Steps:**
  1. Navigate to `/admin/document-templates`; the registry list loads (NOT paginated — full array).
  2. Pick a row (e.g. INVOICE → TRANSACTIONAL_PDF); change its `status` to INACTIVE and/or edit its `title`; save.
  3. Reload → assert the change persisted.
- **Test Data:** title "Tax Invoice"; status toggle ACTIVE→INACTIVE→ACTIVE.
- **Expected Result:** `PUT /templates/{uid}` returns the updated `DocumentTemplateDto`; status reflected; HTTP 200.
- **Convention Assertions:** C1 update targets the template uid in the URL (scoped `documenttemplate`), chosen from a row not typed; C9 soft state via MasterStatus (no hard delete); C3 RBAC; C6 axe.
- **Negative / Edge:** stale `version` (optimistic-lock) → conflict error; user without DOCUMENT.TEMPLATE.MANAGE → 403 on PUT.

### TC-PLAT-011 — Template registry shows DocumentType × RendererKey mapping
- **Type:** Manual
- **Priority:** P3
- **Module / Submodule:** Templates (`/admin/document-templates`)
- **Permission / Role:** `DOCUMENT.TEMPLATE.MANAGE` — runs as ORG_ADMIN
- **Preconditions / Seed:** seeded registry.
- **Steps:** read the registry; verify each renderable type maps to the correct renderer (INVOICE/PO/GRN/DELIVERY_NOTE/CREDIT_NOTE → TRANSACTIONAL_PDF; AR_STATEMENT → STATEMENT_PDF).
- **Expected Result:** RendererKey column matches the documented mapping.
- **Convention Assertions:** C2 plain array (not paged) — no paginator shown for this screen.
- **Negative / Edge:** N/A.

### TC-PLAT-012 — Edit the company branding profile (DOCUMENT.BRANDING.MANAGE)
- **Type:** Both
- **Priority:** P1
- **Module / Submodule:** Branding (`/admin/document-branding` · `GET/PUT /api/v1/documents/branding`)
- **Permission / Role:** `DOCUMENT.BRANDING.MANAGE` — runs as ORG_ADMIN; also as a user without it → nav hidden + forbidden
- **Variation:** per-company singleton (no uid in URL)
- **Preconditions / Seed:** a company with an existing/empty branding profile.
- **Steps:**
  1. Navigate to `/admin/document-branding`; the singleton profile loads into an edit form.
  2. Edit display name, legal name, tax id, address, contact phone/email, website, footer terms, bank details.
  3. Save; reload → values persisted.
- **Test Data:** displayName "Acme Trading", legalName "Acme Trading Ltd", taxId "TIN-123456789", contactEmail "ar@acme.test".
- **Expected Result:** `PUT /branding` returns `DocumentBrandingDto` with `updatedAt` advanced; HTTP 200.
- **Convention Assertions:** C7 scoped to the active company (`scopeGuard.assertCanActIn`); C8 `updatedAt` ISO; C3 RBAC; C6 axe; C4 loading/error.
- **Negative / Edge:** stale `version` → conflict; acting in a company the user cannot act in → scope-guard denial; user without permission → 403.

### TC-PLAT-013 — Branding edit is reflected in a freshly rendered document
- **Type:** Manual
- **Priority:** P2
- **Module / Submodule:** Branding × Render (cross-feature)
- **Permission / Role:** `DOCUMENT.BRANDING.MANAGE` + `DOCUMENT.RENDER` — runs as ORG_ADMIN
- **Preconditions / Seed:** an invoice source exists.
- **Steps:** change the branding display name; render a new INVOICE; download and open the PDF.
- **Expected Result:** the rendered PDF header shows the updated branding display name (branding is referenced at render time).
- **Convention Assertions:** C9 prior generated docs are unchanged (append-only log); the new render reflects current branding.
- **Negative / Edge:** N/A (visual/manual verification).

---

# NOTIFICATIONS

### TC-PLAT-014 — In-app inbox lists the caller's notifications, scoped to active company (NOTIFICATION.VIEW)
- **Type:** Automated (Playwright)
- **Priority:** P1
- **Module / Submodule:** Inbox (`/admin/notifications` · `GET /api/v1/notifications`)
- **Permission / Role:** `NOTIFICATION.VIEW` (broad) — runs as ACCOUNTANT; also as NO-PERMISSION user → forbidden
- **Preconditions / Seed:** notifications addressed to the caller exist (seed by triggering a notifiable event for that user/company).
- **Steps:**
  1. Login as the recipient; navigate to `/admin/notifications`.
  2. Assert rows show title, body, severity badge, created-at; rows are the caller's own.
- **Expected Result:** `ApiResponse<List<NotificationDto>>` + meta; HTTP 200; only this user's company notifications.
- **Convention Assertions:** C7 scoped to caller + active company; C2 envelope+meta; C4 four states; C5 paginator; C6 axe; C8 created-at ISO.
- **Negative / Edge:** another user's notifications never appear; NO-PERMISSION user → forbidden.

### TC-PLAT-015 — Inbox unread filter + unread-count badge
- **Type:** Automated (Playwright)
- **Priority:** P2
- **Module / Submodule:** Inbox (`/admin/notifications` · `?unread=true`, `GET /unread-count`)
- **Permission / Role:** `NOTIFICATION.VIEW` — runs as ACCOUNTANT
- **Preconditions / Seed:** a mix of read + unread notifications.
- **Steps:**
  1. Toggle the "Unread only" filter on → only unread rows show; request carries `unread=true`.
  2. Read the shell unread-count badge value; cross-check against `GET /unread-count`.
- **Expected Result:** filtered list matches; badge `UnreadCountDto.count` equals number of unread.
- **Convention Assertions:** C5 page resets on filter toggle; C2 count envelope auto-unwrapped.
- **Negative / Edge:** with zero unread → unread filter shows empty state; badge shows 0 / hidden.

### TC-PLAT-016 — Mark a single notification read (recipient-is-me enforced)
- **Type:** Both
- **Priority:** P1
- **Module / Submodule:** Inbox (`POST /api/v1/notifications/uid/{uid}/read`)
- **Permission / Role:** `NOTIFICATION.VIEW` (scoped to `notification`) — runs as ACCOUNTANT
- **Preconditions / Seed:** an unread notification for the caller; another user's notification uid (for negative).
- **Steps:**
  1. On `/admin/notifications`, click "Mark read" on an unread row.
  2. Row flips to read (badge/state updates), unread count decrements; endpoint returns 204 No Content.
- **Expected Result:** HTTP 204; row marked read; `readAt` set on next fetch.
- **Convention Assertions:** C1 the read action targets the row uid in URL — not typed; C3 scoped permission.
- **Negative / Edge:** marking-read a notification that is NOT the caller's (BR-NOTIF-04) → denied; already-read row → no-op.

### TC-PLAT-017 — Mark all read
- **Type:** Automated (Playwright)
- **Priority:** P2
- **Module / Submodule:** Inbox (`POST /api/v1/notifications/read-all`)
- **Permission / Role:** `NOTIFICATION.VIEW` — runs as ACCOUNTANT
- **Preconditions / Seed:** several unread notifications.
- **Steps:** click "Mark all read"; observe success toast; list reloads; unread count → 0.
- **Expected Result:** HTTP 204; all caller notifications read; badge cleared.
- **Convention Assertions:** C4 loading state during the call; C2 unread-count refetch reflects 0.
- **Negative / Edge:** with no unread → no-op success.

### TC-PLAT-018 — Per-user notification preferences: list + upsert (NOTIFICATION.PREFERENCE.MANAGE)
- **Type:** Both
- **Priority:** P1
- **Module / Submodule:** Preferences (`/admin/notification-preferences` · `GET` + `PUT /{typeKey}`)
- **Permission / Role:** `NOTIFICATION.PREFERENCE.MANAGE` (broad) — runs as ACCOUNTANT; also as a user without it → nav hidden + forbidden
- **Preconditions / Seed:** notification type catalogue seeded (so typeKeys exist).
- **Steps:**
  1. Navigate to `/admin/notification-preferences`; the caller's preferences load (plain array).
  2. For a type key (e.g. LOW_STOCK), toggle `muted` on and set `channelsEnabled` (e.g. "IN_APP").
  3. Save; reload → preference persisted for this user.
- **Test Data:** typeKey "LOW_STOCK", muted=true, channelsEnabled="IN_APP".
- **Expected Result:** `PUT /{typeKey}` returns `NotificationPreferenceDto`; HTTP 200; per-user scope.
- **Convention Assertions:** C7 scoped to caller+company; C3 RBAC; C6 axe; preference is per typeKey (not uid in URL — typeKey is a human key like LOW_STOCK).
- **Negative / Edge:** unknown typeKey → handled error; user without the permission → 403.

### TC-PLAT-019 — Admin notification type catalogue: list + toggle company state (NOTIFICATION.ADMIN)
- **Type:** Both
- **Priority:** P1
- **Module / Submodule:** Type catalogue (`/admin/notification-types` · `GET /types`, `PUT /types/{typeKey}/state`)
- **Permission / Role:** `NOTIFICATION.ADMIN` — runs as ORG_ADMIN; also as NOTIFICATION.VIEW-only user → nav item hidden + route forbidden
- **Preconditions / Seed:** seeded type catalogue for the company.
- **Steps:**
  1. Navigate to `/admin/notification-types`; types list with `displayName`, `audiencePermission`, `severity`, `defaultChannels`, `companyEnabled`.
  2. Toggle a type's company state OFF (`enabled=false`); save.
  3. Reload → `companyEnabled=false`; (optionally verify suppression downstream: a triggered notification of this type yields a delivery row with `suppressionReason=COMPANY_TYPE_OFF`).
- **Test Data:** typeKey "LOW_STOCK", enabled=false then true.
- **Expected Result:** `PUT /types/{typeKey}/state` returns updated `NotificationTypeDto`; HTTP 200; per-company scope.
- **Convention Assertions:** C7 company-scoped (`scopeGuard.assertCanActIn`); C3 RBAC (VIEW-only cannot administer); C6 axe.
- **Negative / Edge:** NOTIFICATION.VIEW user → 403 on toggle; toggling a type respects active-company only (other company's state unchanged → C7 isolation).

### TC-PLAT-020 — Admin delivery log lists + filters by channel and outcome (NOTIFICATION.ADMIN)
- **Type:** Automated (Playwright)
- **Priority:** P1
- **Module / Submodule:** Delivery log (`/admin/notification-deliveries` · `GET /deliveries?channel=&outcome=`)
- **Permission / Role:** `NOTIFICATION.ADMIN` — runs as ORG_ADMIN
- **Variation:** channel = EMAIL; outcome = FAILED, then SUPPRESSED
- **Preconditions / Seed:** delivery attempts of mixed channel/outcome exist (seed by triggering notifiable events with one disabled email recipient).
- **Steps:**
  1. Navigate to `/admin/notification-deliveries`; the paged delivery log loads.
  2. Filter channel = EMAIL → only EMAIL rows; request carries `channel=EMAIL`.
  3. Clear channel; filter outcome = FAILED → only FAILED rows; then outcome = SUPPRESSED → assert `suppressionReason` column populated (e.g. NO_EMAIL / CHANNEL_DISABLED / COMPANY_TYPE_OFF / MUTED / NO_AUDIENCE).
- **Test Data:** channel EMAIL; outcomes FAILED then SUPPRESSED.
- **Expected Result:** `ApiResponse<List<NotificationDeliveryDto>>` + meta; correct filtered subset; outcome + suppressionReason shown so a user can answer "why didn't I get it?".
- **Convention Assertions:** C2 envelope+meta; C5 paginator; C7 company-scoped; C6 axe.
- **Negative / Edge:** channel and outcome are mutually-applied per the service (channel takes precedence when both set) — assert UI sends one effective filter; empty filter result → empty state.

### TC-PLAT-021 — Notification severity rendering (INFO / WARNING / CRITICAL)
- **Type:** Manual
- **Priority:** P3
- **Module / Submodule:** Inbox (`/admin/notifications`)
- **Permission / Role:** `NOTIFICATION.VIEW` — runs as ACCOUNTANT
- **Preconditions / Seed:** notifications of each severity.
- **Steps:** view the inbox; confirm each severity renders a distinct badge.
- **Expected Result:** INFO/WARNING/CRITICAL visually distinguished (CRITICAL = danger styling).
- **Convention Assertions:** C6 badge has accessible text label, not colour-only.
- **Negative / Edge:** unknown severity falls back to a neutral badge.

---

# APPROVALS — POLICIES

### TC-PLAT-022 — Approval policy list for the active company (APPROVALS.POLICY.VIEW)
- **Type:** Automated (Playwright)
- **Priority:** P1
- **Module / Submodule:** Policies (`/admin/approvals/policies` · `GET /api/v1/approvals/policies`)
- **Permission / Role:** `APPROVALS.POLICY.VIEW` — runs as ORG_ADMIN; also as NO-PERMISSION user → nav hidden + forbidden
- **Preconditions / Seed:** at least one policy exists.
- **Steps:** navigate to `/admin/approvals/policies`; company selector defaults; list loads with name, documentType, branchScope, amount band, status.
- **Expected Result:** `ApiResponse<List<ApprovalPolicyDto>>` + meta; HTTP 200.
- **Convention Assertions:** C2 envelope+meta; C4 four states; C5 paginator; C6 axe; C8 amount band formatted; C1 no raw uid in cells (row links by uid).
- **Negative / Edge:** NO-PERMISSION user → forbidden + nav hidden.

### TC-PLAT-023 — Filter policies by documentType
- **Type:** Automated (Playwright)
- **Priority:** P2
- **Module / Submodule:** Policies (`/admin/approvals/policies?documentType=`)
- **Permission / Role:** `APPROVALS.POLICY.VIEW` — runs as ORG_ADMIN
- **Variation:** documentType = "PURCHASE_ORDER"
- **Preconditions / Seed:** policies for ≥2 documentTypes.
- **Steps:** enter documentType filter; apply; assert only matching policies; clear → all return.
- **Expected Result:** server filter `documentType=PURCHASE_ORDER`; page resets to 0.
- **Convention Assertions:** C5 page reset; C2 meta reflects filtered count.
- **Negative / Edge:** unknown documentType → empty state.

### TC-PLAT-024 — Create a COMPANY_WIDE single-step policy (APPROVALS.POLICY.MANAGE)
- **Type:** Automated (Playwright)
- **Priority:** P1
- **Module / Submodule:** Policies (inline create on `/admin/approvals/policies` · `POST /api/v1/approvals/policies`)
- **Permission / Role:** `APPROVALS.POLICY.MANAGE` — runs as ORG_ADMIN; also as APPROVALS.POLICY.VIEW-only user → create form hidden, POST 403
- **Variation:** branchScope = COMPANY_WIDE; bounded band; single step
- **Preconditions / Seed:** a seeded role code to use as approverRoleCode (e.g. ACCOUNTANT).
- **Steps:**
  1. Click "New policy"; set documentType, name, branchScope = COMPANY_WIDE.
  2. Set minAmount 0, maxAmount 1,000,000; add 1 step with approverRoleCode = ACCOUNTANT.
  3. Submit.
- **Test Data:** documentType "PURCHASE_ORDER", name "PO ≤ 1M → Accountant", minAmount "0", maxAmount "1000000", step1 role "ACCOUNTANT".
- **Expected Result:** `POST` returns `ApprovalPolicyDto` (HTTP 201); list reloads with the new ACTIVE policy.
- **Convention Assertions:** C3 RBAC (VIEW-only cannot create); C2 created object; C6 axe; C8 amounts formatted.
- **Negative / Edge:** missing name/documentType/minAmount → inline validation, no POST; empty steps → "At least one approval step is required".

### TC-PLAT-025 — Create a BRANCH-scoped policy, branch chosen via picker (by name)
- **Type:** Automated (Playwright)
- **Priority:** P1
- **Module / Submodule:** Policies (inline create · `POST /api/v1/approvals/policies`)
- **Permission / Role:** `APPROVALS.POLICY.MANAGE` — runs as ORG_ADMIN
- **Variation:** branchScope = BRANCH (branchUid required); multi-branch company; non-default branch
- **Preconditions / Seed:** a multi-branch company; a non-default ACTIVE branch.
- **Steps:**
  1. New policy; set branchScope = BRANCH; the branch picker (`<app-uid-picker>`, ACTIVE branches only) appears.
  2. Choose the non-default branch BY NAME; fill the rest; submit.
- **Test Data:** branch = "Mwanza Depot" (non-default), documentType "PURCHASE_ORDER", step role "PURCHASE_OFFICER".
- **Expected Result:** policy created with `branchScope=BRANCH`, `branchId` set to the picked branch; HTTP 201.
- **Convention Assertions:** C1 branch selected via picker by name, uid stored under the hood — no typed uid; C7 branch belongs to the active company; C6 axe.
- **Negative / Edge:** branchScope = BRANCH with no branch chosen → inline "Branch UID is required when branch scope is BRANCH"; branchScope COMPANY_WIDE must NOT send a branchUid (server rejects mismatch).

### TC-PLAT-026 — Create a multi-step approval chain (dense sequences)
- **Type:** Both
- **Priority:** P1
- **Module / Submodule:** Policies (inline create · `POST /api/v1/approvals/policies`)
- **Permission / Role:** `APPROVALS.POLICY.MANAGE` — runs as ORG_ADMIN
- **Variation:** multi-step chain (sequences 1..N dense, unique)
- **Preconditions / Seed:** ≥2 role codes (ACCOUNTANT, ORG_ADMIN).
- **Steps:** new policy; "Add step" twice → steps 1,2; set roles; remove the middle step → remaining resequenced to dense 1..; submit.
- **Test Data:** step1 ACCOUNTANT, step2 ORG_ADMIN.
- **Expected Result:** policy created with steps in order; UI keeps sequences dense from 1 (the "removeStep" handler resequences).
- **Convention Assertions:** C2 created object includes `steps`; C6 axe.
- **Negative / Edge:** a step with blank approverRoleCode → "Step N: approver role code is required"; non-dense/duplicate sequences are prevented by the UI's resequencing and rejected by the service if forced.

### TC-PLAT-027 — Create an unbounded top-band policy (maxAmount null)
- **Type:** Manual
- **Priority:** P2
- **Module / Submodule:** Policies (inline create)
- **Permission / Role:** `APPROVALS.POLICY.MANAGE` — runs as ORG_ADMIN
- **Variation:** maxAmount left blank → unbounded top band
- **Steps:** new policy; minAmount = 1,000,000; leave maxAmount blank; submit.
- **Expected Result:** policy created with `maxAmount=null` (top band); list shows "≥ 1,000,000" / unbounded indicator.
- **Convention Assertions:** C8 band display; C2 created object `maxAmount` null.
- **Negative / Edge:** minAmount blank → required; minAmount negative → DecimalMin("0") rejection.

### TC-PLAT-028 — Policy detail: view + edit (APPROVALS.POLICY.MANAGE) — edits affect only future submissions
- **Type:** Both
- **Priority:** P1
- **Module / Submodule:** Policy detail (`/admin/approvals/policies/uid/:uid` · `GET .../uid/{uid}`, `PUT .../uid/{uid}`)
- **Permission / Role:** `APPROVALS.POLICY.VIEW` (view) + `APPROVALS.POLICY.MANAGE` (save) — runs as ORG_ADMIN; view also as POLICY.VIEW-only (edit controls hidden)
- **Preconditions / Seed:** an ACTIVE policy with an in-flight PENDING request created under its current shape.
- **Steps:**
  1. Open the policy detail by clicking the list row; verify the form patches with current values.
  2. Change name / amount band / steps; save.
  3. Verify the previously-submitted PENDING request is UNCHANGED (BR-APR-05).
- **Test Data:** rename "PO ≤ 1M v2", change step1 role.
- **Expected Result:** `PUT` returns updated `ApprovalPolicyDto`; the in-flight request keeps its original chain.
- **Convention Assertions:** C1 uid only in URL; C5/C2 not a list; C3 RBAC (VIEW-only cannot save); C6 axe.
- **Negative / Edge:** blank required field → inline validation; POLICY.VIEW-only user → 403 on PUT; setting BRANCH scope without re-entering branch → validation.

### TC-PLAT-029 — Deactivate a policy (status ACTIVE → INACTIVE), and illegal re-deactivate
- **Type:** Both
- **Priority:** P1
- **Module / Submodule:** Policy detail (`POST /api/v1/approvals/policies/uid/{uid}/deactivate`)
- **Permission / Role:** `APPROVALS.POLICY.MANAGE` (scoped `approvalpolicy`) — runs as ORG_ADMIN
- **Variation:** MasterStatus lifecycle ACTIVE → INACTIVE
- **Preconditions / Seed:** an ACTIVE policy.
- **Steps:**
  1. On policy detail, the Deactivate control is enabled only while status = ACTIVE.
  2. Click Deactivate → status flips to INACTIVE; success toast.
  3. Verify the Deactivate control is now hidden/disabled (already inactive).
- **Expected Result:** `deactivate` returns the policy with `status=INACTIVE`; HTTP 200.
- **Convention Assertions:** C9 soft-deactivate (MasterStatus), never hard delete; C1 uid in URL only; C3 RBAC.
- **Negative / Edge:** deactivating an already-INACTIVE policy → control absent (illegal transition guarded in UI); user without MANAGE → 403.

### TC-PLAT-030 — Cross-tenant policy access denied
- **Type:** Manual
- **Priority:** P2
- **Module / Submodule:** Policies (`GET /api/v1/approvals/policies/uid/{uid}`)
- **Permission / Role:** `APPROVALS.POLICY.VIEW` — runs as ORG_ADMIN of company A
- **Variation:** cross-tenant (company B's policy uid)
- **Preconditions / Seed:** a policy in company B.
- **Steps:** as company A admin, open `/admin/approvals/policies/uid/<companyB-policy-uid>`.
- **Expected Result:** error/forbidden — scope guard on `approvalpolicy` denies cross-company access.
- **Convention Assertions:** C7 multi-tenant isolation; C3 403/404.
- **Negative / Edge:** the list endpoint for company A never returns company B's policies.

---

# APPROVALS — REQUESTS, INBOX, LIFECYCLE

### TC-PLAT-031 — Approval inbox lists PENDING requests the caller can decide (APPROVALS.DECIDE)
- **Type:** Automated (Playwright)
- **Priority:** P1
- **Module / Submodule:** Inbox (`/admin/approvals/inbox` · `GET /api/v1/approvals/requests/inbox`)
- **Permission / Role:** `APPROVALS.DECIDE` — runs as ACCOUNTANT (the approver role for the seeded policy step); also as a user without APPROVALS.DECIDE → nav item hidden + forbidden
- **Preconditions / Seed:** a PENDING request whose current open step routes to the ACCOUNTANT role (seed by performing a gated action in a consuming module that matches a policy — e.g. confirm a PO above the band).
- **Steps:**
  1. Login as ACCOUNTANT; navigate to `/admin/approvals/inbox` (no company selector — implicitly scoped to caller's company/roles/branches).
  2. Assert the PENDING request appears with requestNumber, documentType, amount, status badge.
- **Expected Result:** `ApiResponse<List<ApprovalRequestDto>>` + meta; only requests whose current open step the caller can decide; HTTP 200.
- **Convention Assertions:** C7 implicitly scoped to caller (no companyId param); C2 envelope+meta; C4 four states; C5 paginator; C6 axe.
- **Negative / Edge:** a user not on the current step's role does NOT see the request in their inbox; NO-DECIDE user → forbidden.

### TC-PLAT-032 — All-requests list + filter by status (APPROVALS.REQUEST.VIEW)
- **Type:** Automated (Playwright)
- **Priority:** P1
- **Module / Submodule:** Requests (`/admin/approvals/requests` · `GET /api/v1/approvals/requests?status=`)
- **Permission / Role:** `APPROVALS.REQUEST.VIEW` — runs as ORG_ADMIN; also as NO-PERMISSION user → forbidden
- **Variation:** status ∈ { PENDING, APPROVED, REJECTED, RECALLED, CANCELLED }
- **Preconditions / Seed:** requests across multiple statuses.
- **Steps:** navigate to `/admin/approvals/requests`; select status = PENDING → only PENDING; then APPROVED; then REJECTED; clear → all.
- **Expected Result:** server filter `status=...`; correct subset each time; page resets to 0.
- **Convention Assertions:** C5 page reset + paginator; C2 envelope+meta; C6 axe; status badge per row.
- **Negative / Edge:** invalid status value → handled (empty/error); company-scoped (only the active company's requests).

### TC-PLAT-033 — Request detail shows the step chain + current open step (APPROVALS.REQUEST.VIEW)
- **Type:** Automated (Playwright)
- **Priority:** P1
- **Module / Submodule:** Request detail (`/admin/approvals/requests/uid/:uid` · `GET .../uid/{uid}`)
- **Permission / Role:** `APPROVALS.REQUEST.VIEW` (scoped `approvalrequest`) — runs as ORG_ADMIN
- **Preconditions / Seed:** a multi-step PENDING request.
- **Steps:** open the request detail; read header (requestNumber, documentType, documentUid, amount, currency, status, submittedBy/At, sourcePolicy) and the steps table with per-step status badges.
- **Expected Result:** detail renders `ApprovalRequestDto` incl. `steps[]`; current open step = lowest-sequence PENDING.
- **Convention Assertions:** C1 uid in URL only — no raw uid as visible text; C8 amount + dates formatted; C4 loading/error.
- **Negative / Edge:** cross-tenant request uid → forbidden (scope guard).

### TC-PLAT-034 — Approve the current open step (PENDING → still PENDING or APPROVED on last step)
- **Type:** Automated (Playwright)
- **Priority:** P1
- **Module / Submodule:** Request detail (`POST /api/v1/approvals/requests/uid/{uid}/approve`)
- **Permission / Role:** `APPROVALS.DECIDE` (scoped `approvalrequest`) — runs as ACCOUNTANT (current-step approver)
- **Variation:** single-step chain (request → APPROVED); multi-step chain (request stays PENDING, advances to next step)
- **Preconditions / Seed:** a PENDING request on a step routed to ACCOUNTANT.
- **Steps:**
  1. On request detail, the Approve control is enabled (canDecide && isPending).
  2. Click Approve; optionally add a comment; submit.
  3. Single-step: status → APPROVED. Multi-step: this step → APPROVED, request stays PENDING with the next step now open.
- **Test Data:** comment "Looks good".
- **Expected Result:** `approve` returns updated `ApprovalRequestDto`; correct status; the step the caller approved shows APPROVED.
- **Convention Assertions:** C1 uid in URL only; C3 RBAC — only the current-step approver may decide; C2 updated object.
- **Negative / Edge:** approving when NOT on the current open step → rejected (BR-APR-04); a non-approver with APPROVALS.DECIDE but wrong role for this step → service routing denial.

### TC-PLAT-035 — Reject the current open step (PENDING → REJECTED) kills the chain (later steps → SKIPPED)
- **Type:** Both
- **Priority:** P1
- **Module / Submodule:** Request detail (`POST /api/v1/approvals/requests/uid/{uid}/reject`)
- **Permission / Role:** `APPROVALS.DECIDE` (scoped) — runs as ACCOUNTANT (current-step approver)
- **Variation:** multi-step chain — reject on step 1
- **Preconditions / Seed:** a multi-step PENDING request.
- **Steps:**
  1. Open detail; click Reject; add a comment; submit.
  2. Request status → REJECTED; step 1 → REJECTED; remaining steps → SKIPPED.
- **Test Data:** comment "Budget exceeded".
- **Expected Result:** `reject` returns the request with `status=REJECTED`; later steps `SKIPPED`; this is terminal.
- **Convention Assertions:** C1 uid in URL only; C2 updated object; one reject kills the whole chain (OQ-APR-04 default).
- **Negative / Edge:** after REJECT, Approve/Reject/Recall controls are all hidden (terminal); attempting another decision → rejected (BR-APR-07).

### TC-PLAT-036 — Recall own PENDING request (submitter-only) (PENDING → RECALLED)
- **Type:** Both
- **Priority:** P1
- **Module / Submodule:** Request detail (`POST /api/v1/approvals/requests/uid/{uid}/recall`)
- **Permission / Role:** `APPROVALS.REQUEST.VIEW` (scoped) — runs as the SUBMITTER; also attempt as a different non-admin user → denied
- **Variation:** PENDING → RECALLED
- **Preconditions / Seed:** a PENDING request submitted by the test user.
- **Steps:**
  1. Login as the submitter; open the request detail; click Recall (enabled while PENDING).
  2. Status → RECALLED (terminal); success toast.
- **Expected Result:** `recall` returns `status=RECALLED`; HTTP 200.
- **Convention Assertions:** C3 submitter-only enforced server-side; C1 uid in URL only.
- **Negative / Edge:** a non-submitter (without APPROVALS.ADMIN) recalling → denied; recalling a terminal request → control hidden / rejected.

### TC-PLAT-037 — Admin cancel a non-terminal request (PENDING → CANCELLED) (APPROVALS.ADMIN)
- **Type:** Both
- **Priority:** P1
- **Module / Submodule:** Request detail (`POST /api/v1/approvals/requests/uid/{uid}/cancel`)
- **Permission / Role:** `APPROVALS.ADMIN` (scoped `approvalrequest`) — runs as ORG_ADMIN (holds APPROVALS.ADMIN); also as APPROVALS.DECIDE-only user → cancel control hidden, POST 403
- **Variation:** PENDING (non-terminal) → CANCELLED
- **Preconditions / Seed:** a PENDING request.
- **Steps:**
  1. Open detail as an APPROVALS.ADMIN holder; Cancel control enabled only when `!isTerminal()`.
  2. Click Cancel → status → CANCELLED (terminal); toast.
- **Expected Result:** `cancel` returns `status=CANCELLED`; HTTP 200.
- **Convention Assertions:** C3 APPROVALS.ADMIN only; C9 append-only (cancel is a state transition, not a delete); C1 uid in URL only.
- **Negative / Edge:** cancel on a terminal (APPROVED/REJECTED/RECALLED/CANCELLED) request → control hidden + service rejects; non-admin → 403.

### TC-PLAT-038 — Illegal lifecycle transitions on terminal requests are blocked
- **Type:** Automated (Playwright, negative matrix)
- **Priority:** P1
- **Module / Submodule:** Request detail (approve/reject/recall/cancel)
- **Permission / Role:** `APPROVALS.DECIDE` + `APPROVALS.ADMIN` — runs as ORG_ADMIN
- **Variation:** terminal states {APPROVED, REJECTED, RECALLED, CANCELLED} × actions {approve, reject, recall, cancel}
- **Preconditions / Seed:** one request in each terminal state.
- **Steps:** for each terminal request, open detail and confirm Approve/Reject/Recall/Cancel controls are all hidden/disabled; attempt each action via API.
- **Expected Result:** UI exposes no action on terminal requests; any forced API call is rejected (BR-APR-07, terminal accepts no further transitions).
- **Convention Assertions:** C2 error envelope on forced calls; C4 detail still renders read-only.
- **Negative / Edge:** this is the core illegal-transition matrix for the request lifecycle.

### TC-PLAT-039 — Auto-approved request (no policy matched) shows APPROVED + autoApproved
- **Type:** Manual
- **Priority:** P2
- **Module / Submodule:** Requests (`/admin/approvals/requests`, detail)
- **Permission / Role:** `APPROVALS.REQUEST.VIEW` — runs as ORG_ADMIN
- **Preconditions / Seed:** perform a gated action whose amount/scope matches NO policy → engine auto-approves.
- **Steps:** trigger the action; open the resulting request; read status + autoApproved flag + (empty/auto) step chain.
- **Expected Result:** `status=APPROVED`, `autoApproved=true`; the consuming document proceeds.
- **Convention Assertions:** C2 DTO `autoApproved`; APPROVED includes both human-approved and auto-approved.
- **Negative / Edge:** an action that DOES match a policy must NOT auto-approve (creates a PENDING chain instead).

### TC-PLAT-040 — Branch-specificity wins on policy match (BRANCH beats COMPANY_WIDE)
- **Type:** Manual
- **Priority:** P2
- **Module / Submodule:** Policies × Requests (engine match)
- **Permission / Role:** `APPROVALS.POLICY.MANAGE` (setup) + `APPROVALS.REQUEST.VIEW` (verify) — runs as ORG_ADMIN
- **Variation:** one COMPANY_WIDE and one BRANCH policy that both match the same documentType/amount, in the request's branch
- **Preconditions / Seed:** create both policies (TC-PLAT-024 + TC-PLAT-025) for the same documentType/band; non-default branch for the BRANCH policy.
- **Steps:** trigger a gated action in that branch within the overlapping band; open the resulting request.
- **Expected Result:** `sourcePolicy` on the request is the BRANCH-scoped policy (branch-specificity wins on a tie, ADR-0022 D-3).
- **Convention Assertions:** C7 branch scoping drives selection; C2 `sourcePolicyUid` reflects the BRANCH policy.
- **Negative / Edge:** an action in a different branch (where only COMPANY_WIDE matches) → uses the COMPANY_WIDE policy.

---

# AUDIT TRAIL

### TC-PLAT-041 — Audit trail lists newest-first, read-only (AUDIT.VIEW)
- **Type:** Automated (Playwright)
- **Priority:** P1
- **Module / Submodule:** Audit (`/admin/audit` · `GET /api/v1/audit`)
- **Permission / Role:** `AUDIT.VIEW` — runs as ORG_ADMIN; also as NO-PERMISSION user → nav item hidden + route forbidden
- **Preconditions / Seed:** audit rows exist (login + any masters action generates them).
- **Steps:**
  1. Navigate to `/admin/audit`; list loads (default size 50), sorted by time DESC.
  2. Read columns: actor (username), action, target type/uid, company/branch, time, ip.
  3. Confirm there are NO create/edit/delete controls (append-only).
- **Expected Result:** `ApiResponse<List<AuditLogDto>>` + meta; rows show `actorUsername` (not internal id); HTTP 200; newest first.
- **Convention Assertions:** C2 envelope+meta; C4 four states; C5 paginator (FIRST/PREV/numbers/NEXT/LAST, size 50); C6 axe; C9 append-only (no write UI); C1 target uid shown only truncated as reference text, never user-typed/relied-on (the screen displays a short uid for reference; no resource is *chosen* by uid here).
- **Negative / Edge:** NO-PERMISSION user → forbidden + nav hidden; page size capped at 200 server-side (MAX_PAGE_SIZE).

### TC-PLAT-042 — Filter audit by actor (chosen via user picker, by name)
- **Type:** Automated (Playwright)
- **Priority:** P1
- **Module / Submodule:** Audit (`/admin/audit?actorUid=`)
- **Permission / Role:** `AUDIT.VIEW` — runs as ORG_ADMIN
- **Preconditions / Seed:** actions by ≥2 distinct users.
- **Steps:**
  1. In the actor filter, open the user picker (`<app-uid-picker>`) and choose a user BY display name/username.
  2. Apply → only that actor's rows; request carries `actorUid=<picked>`.
  3. Clear filters → full list.
- **Test Data:** actor = "ORG_ADMIN (orgadmin)".
- **Expected Result:** filtered to the chosen actor; the actor uid is resolved server-side; HTTP 200.
- **Convention Assertions:** C1 actor selected via picker by name, uid stored under the hood — never typed; C5 page resets to 0; C6 axe.
- **Negative / Edge:** an unknown actor uid resolves to "match nothing" (server `resolveActorId` returns -1) → empty state.

### TC-PLAT-043 — Filter audit by action code, targetType, targetUid, and date range
- **Type:** Automated (Playwright)
- **Priority:** P1
- **Module / Submodule:** Audit (`/admin/audit?action=&targetType=&targetUid=&from=&to=`)
- **Permission / Role:** `AUDIT.VIEW` — runs as ORG_ADMIN
- **Preconditions / Seed:** rows for a known action (e.g. `LOGIN.SUCCESS`, `USER.CREATE`, or an approvals action like `APPROVAL.STEP.DECIDE`).
- **Steps:**
  1. Select an action from the action filter (codes from the known catalogue, e.g. LOGIN.SUCCESS / USER.CREATE / ROLE.GRANT).
  2. Add a targetType and/or targetUid; set from/to dates (datetime-local → ISO instant on the wire).
  3. Apply → only matching rows.
- **Test Data:** action "USER.CREATE", from "2026-06-01T00:00", to "2026-06-30T23:59".
- **Expected Result:** combined AND filter applied; rows match all active criteria; dates sent as ISO-8601 instants (Z).
- **Convention Assertions:** C8 dates ISO on the wire; C5 page reset; blank filters are dropped (not sent as null binds).
- **Negative / Edge:** from > to → empty result; combining filters that intersect to nothing → empty state, not error.

### TC-PLAT-044 — Audit is confined to the caller's active company (non-root); root reads org-wide
- **Type:** Both
- **Priority:** P1
- **Module / Submodule:** Audit (`GET /api/v1/audit`)
- **Permission / Role:** `AUDIT.VIEW` — runs as a non-root ORG_ADMIN of company A; compared against `rootadmin`
- **Variation:** multi-company isolation
- **Preconditions / Seed:** audit rows in company A and company B.
- **Steps:**
  1. As company A's ORG_ADMIN (non-root), open `/admin/audit` → only company A's rows.
  2. As `rootadmin`, open `/admin/audit` → rows across all companies.
- **Expected Result:** non-root caller's results are filtered to `companyId = active company`; root sees org-wide. A non-root caller with no active company matches nothing (fail-closed), never org-wide.
- **Convention Assertions:** C7 company confinement enforced server-side (AuditReadService scope); C3 AUDIT.VIEW required.
- **Negative / Edge:** company A admin can never see company B audit rows even by setting a company B targetUid filter (still scoped to A).

### TC-PLAT-045 — Audit list pagination (FIRST/PREV/numbers/NEXT/LAST) and size cap
- **Type:** Automated (Playwright)
- **Priority:** P2
- **Module / Submodule:** Audit (`/admin/audit?page=&size=`)
- **Permission / Role:** `AUDIT.VIEW` — runs as ORG_ADMIN
- **Preconditions / Seed:** > 50 audit rows (to span ≥2 pages at default size 50).
- **Steps:** load page 0; click NEXT → page 1; click LAST; click FIRST; click a numbered page; click PREV.
- **Expected Result:** correct page slices; `meta.hasNext` drives NEXT; paginator hidden when only one page; server caps size to 200.
- **Convention Assertions:** C5 full paginator control set; C2 meta drives controls; C6 axe.
- **Negative / Edge:** requesting size > 200 → server clamps to 200; size < 1 → clamps to 1.

### TC-PLAT-046 — Audit captures platform-domain actions (approvals + notifications type toggle)
- **Type:** Manual
- **Priority:** P3
- **Module / Submodule:** Audit × Approvals/Notifications (cross-feature)
- **Permission / Role:** `AUDIT.VIEW` + the acting permission — runs as ORG_ADMIN
- **Preconditions / Seed:** perform: create a policy, decide a step, recall a request, toggle a notification type.
- **Steps:** after each action, filter audit by its action code and confirm a row appears.
- **Test Data:** action codes `APPROVAL.POLICY.CREATE`, `APPROVAL.STEP.DECIDE`, `APPROVAL.REQUEST.RECALL`, `APPROVAL.REQUEST.CANCEL`, `NOTIFICATION.TYPE.TOGGLE` (verified in `AuditActions.java`).
- **Expected Result:** each action produces an audit row with the right action code, actor, target, and timestamp.
- **Convention Assertions:** C9 append-only trail; C2 envelope; the wire carries actor username + target uid, not internal ids.
- **Negative / Edge:** read-only audit views (e.g. opening a list) do NOT generate audit rows; only state-changing actions do.

---

## Coverage map (controller endpoint → cases)

| Endpoint | Cases |
|---|---|
| `POST /documents/render` | 004, 005, 006, 007 |
| `GET /documents/render` (inline) | 006 (statement stream path) |
| `GET /documents/uid/{uid}/download` | 008 |
| `GET /documents` (list) | 001, 002, 003 |
| `GET /documents/uid/{uid}` | 009 |
| `GET/PUT /documents/templates(/{uid})` | 010, 011 |
| `GET/PUT /documents/branding` | 012, 013 |
| `GET /notifications` (+unread) | 014, 015 |
| `GET /notifications/unread-count` | 015 |
| `POST /notifications/uid/{uid}/read` | 016 |
| `POST /notifications/read-all` | 017 |
| `GET/PUT /notification-preferences(/{typeKey})` | 018 |
| `GET /admin/notifications/types`, `PUT .../state` | 019 |
| `GET /admin/notifications/deliveries` | 020 |
| (severity rendering) | 021 |
| `GET /approvals/policies` (+filter) | 022, 023 |
| `POST /approvals/policies` | 024, 025, 026, 027 |
| `GET /approvals/policies/uid/{uid}` | 028, 030 |
| `PUT /approvals/policies/uid/{uid}` | 028 |
| `POST .../uid/{uid}/deactivate` | 029 |
| `GET /approvals/requests/inbox` | 031 |
| `GET /approvals/requests` (+status) | 032 |
| `GET /approvals/requests/uid/{uid}` | 033, 039 |
| `POST .../uid/{uid}/approve` | 034, 038 |
| `POST .../uid/{uid}/reject` | 035, 038 |
| `POST .../uid/{uid}/recall` | 036, 038 |
| `POST .../uid/{uid}/cancel` | 037, 038 |
| (engine match / auto-approve / branch-specificity) | 039, 040 |
| `GET /audit` | 041–046 |
