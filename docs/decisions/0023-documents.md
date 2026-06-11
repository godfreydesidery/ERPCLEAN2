# 0023 — Document / PDF Generation data model: a per-company **template registry** + a **branding profile** + a side-effect-free **render service** that loads source documents via their read-service DTOs and renders branded PDFs through the **shared render pipeline lifted from Reporting** (one OpenPDF code path, no duplication), a **`generated_documents`** append-only render log addressed by `uid` with a `DOC-####` handle, a single **`/api/documents`** download/render controller, and a **`DOCUMENT.GENERATED`** outbox event the future Notifications enabler consumes — all read-only on the source (no GL, no stock, no financial mutation), additive as `V23__documents.sql` / `V24__documents_seed.sql` on the frozen migrations

- **Status:** Proposed
- **Date:** 2026-06-11
- **Deciders:** solutions-architect (requirements distilled by the architect from the PATH-TO-FULL-ERP §3.12 X.1 backlog item; **no system-analyst elicitation preceded this** — the load-bearing scope/policy forks (module placement OQ-DOC-01, renderable-state OQ-DOC-02, persist-vs-re-render OQ-DOC-03, branding storage OQ-DOC-04, the event payload OQ-DOC-05, numbering OQ-DOC-06, the permission shape OQ-DOC-07) are the **decisions this ADR makes**, with owner-style defaults adopted and flagged in [docs/requirements/documents.md](../requirements/documents.md) §8 for owner ratification. The *behaviour* — render branded PDFs of the six v1 document types, one pipeline, zero financial side effects, emit a generation event for Notifications — is the requirement.)
- **Context source:** [docs/requirements/documents.md](../requirements/documents.md) (DRAFT — FR-DOC-01..17, BR-DOC-01..10, NFR-DOC-01..09, §6 flows, §8 OQ log; the ground truth for every rule below) + [docs/PATH-TO-FULL-ERP.md](../PATH-TO-FULL-ERP.md) §3.12 X.1 / §4 #6 / §5 Phase B. Verified against the **shipped** code:
  - **Reporting export pipeline (the pipeline this ADR lifts)** ([ADR-0018](0018-financial-reporting-read-model.md) D-9 / V15): `com.erp.modules.reporting.export.ReportExporter.export(StatementRenderModel, ExportFormat)→ExportResult` (the facade), `PdfStatementRenderer.render(model)→byte[]` (OpenPDF — `com.lowagie.text.*`, A4, section/line/subtotal/total/reconciliation rows), `XlsxStatementRenderer` (Apache POI), `CsvStatementRenderer`, `StatementRenderModel(title, companyName, currency, periodLabel, comparativeLabel, generatedAt, List<Row>)` + `Row(RowType{SECTION_HEADER,LINE,SUBTOTAL,TOTAL,RECONCILIATION}, label, current, comparative, reconciliationBar, ties)`, `ExportResult(content byte[], contentType, filename)`, `ExportFormat{PDF,XLSX,CSV}`; `ReportingController.download(ExportResult)→ResponseEntity<byte[]>` (`Content-Disposition: attachment`); the OpenPDF + POI deps are **already in `backend/pom.xml`** (ADR-0018 D-9 — OpenPDF LGPL, NOT iText/AGPL).
  - **Source read services (DTO-only) the render service calls** — Sales: `SalesInvoiceService` (`SalesInvoiceDto` header + lines + `tax_summary`, `status` ∈ {DRAFT,FINALISED,VOID}, `invoice_number`), `DeliveryService`/`DeliveryDto` + lines (ADR-0021 D-7 — **no pricing on delivery lines**), `findCompanyIdByUid` projections on the sales repos; AR: `ArStatementController`/statement query + `ArCreditNoteService`/`ArCreditNoteDto`; Purchases: `PurchaseOrderService`/`PurchaseOrderDto` + lines, `GoodsReceiptService`/`GoodsReceiptDto` + lines.
  - **Platform spine** — `ScopeGuard.assertCanActIn(principal, companyId)` + `companyIdOf(targetType, uid)` switch (the `case "..."` lines this ADR extends), `PermissionChecks` (`@perm.has` / `@perm.scoped`), `RequestContext.get()`; `AuditService.record(...)` / `AuditEvent.of(action, targetType, targetId, ...)`; the **transactional outbox** ([ADR-0009](0009-transactional-outbox.md)) — `OutboxPublisher.publish(eventType, aggregateType, aggregateId, aggregateUid, companyId, branchId, payload)` in the caller's TX, `DomainEventType` constants (this ADR adds `DOCUMENT.GENERATED` + `AGG_GENERATED_DOCUMENT`), `IdempotencyGuard.alreadyProcessed/markProcessed` + `processed_events(consumer, event_uid)` (for the **future** Notifications consumer; **no consumer ships here** — documents is a pure producer); `code_sequence` ([ADR-0007](0007-products-data-model.md) D-6) row-locked per-company `entity_kind` allocation; `Money` ([ADR-0005](0005-money-and-currency.md)) — display formatting only, base currency TZS.
  - **Companies (frozen V1)** — `companies(id, uid, name, legal_name, tax_id, …)` — **no logo/address/letterhead/footer columns**; this ADR adds a `document_branding` table (does NOT alter the frozen `companies`).
  - [[db-naming-convention]] verified against V1–V19 (plural masters/owned-children, singular constraint roots `uq_`/`fk_`/`chk_`, plural `ix_` indexes, `uid VARCHAR(26)` ULID, `company_id` BIGINT scalar, `MasterStatus` soft-delete `status`/`status_changed_*`, audit cols, `@Version`). **ISSUES-REGISTER #12:** every per-company CROSS-JOIN seed-uid MUST be `'XX' || lpad(company_id::text,6,'0') || substr(md5(key),1,12)` (≤26 chars) — **never** `|| key`. **Migration range: `V23__documents.sql` (tables) + `V24__documents_seed.sql` (per-company seeds + grants), additive on the frozen V1–V19** (V20–V22 are claimed by concurrent modules; this migration references only frozen V1 tables — `companies`/`roles`/`permissions`/`role_permission` — and its own new tables, so it is order-independent of V20–V22). **ADR number 0023.**

This ADR is the **technical data model + integration design** for Document/PDF Generation (PATH-TO-FULL-ERP X.1). It translates the requirements into: the two registry/branding tables + the `generated_documents` render log, the `DocumentType`/`RenderableState`/`MasterStatus` enums, the render service + the shared render pipeline placement, the source-DTO load + per-type render-model builders, the API surface, the new `DOCUMENT.GENERATED` outbox event + payload (producer only), the `DOC-####` numbering, the `ScopeGuard` case + the `DOCUMENT.*` permissions, the V23/V24 migration ordering with #12-safe seed-uids, the ArchUnit edges (no cycle), and the cross-module touch-points. It is **concrete enough that the backend engineer writes V23/V24 + the registry/branding model + the render service + the six renderers + the download controller without guessing a rule.** It writes **no production code, no entities, no migration SQL** — that is the engineer's next step.

## Context

The books are correct and the full O2C / P2P loop runs, but the system **cannot produce a printable document** for the things it transacts (documents.md §1). Reporting already PDF-renders **financial statements** through a clean, shipped pipeline (`PdfStatementRenderer` over a flat `StatementRenderModel`, packaged by `ReportExporter`, served by a `download(...)` helper) — but that pipeline is welded into `reporting.export` and only knows about statements. Sales has all the data for a VAT invoice, Purchases for a PO, AR for a credit note — none of it can be rendered. This slice adds a **render layer**: one pipeline, a registry of which document types render, per-company branding, a render log, a download endpoint, and a generation event for the future Notifications enabler. The forces:

- **One pipeline, not two (the central force, NFR-DOC-01).** The OpenPDF plumbing already exists. The cardinal sin would be a second, parallel PDF code path in a new module. The render *primitives* (the OpenPDF cell/table/font helpers, the flat render model, the `ExportResult`, the `download` helper) must be **shared**. The question is whether they move to a neutral package or stay in reporting and are imported — a module-edge decision (D-1). Either way: **one** rendering code path.

- **Documents must have zero financial side effects (the headline invariant, BR-DOC-02 / NFR-DOC-02).** A print must never post GL, move stock, or mutate a source. This is what makes the module safe to bolt on: it is a **read-only projection**. The render service reads **DTOs** from source read services, formats already-computed amounts (no arithmetic, no re-derivation), and the only thing it *writes* is its own log row + the outbox event. The design must structurally prevent a render from reaching a posting path.

- **Transactional documents are richer than statements.** A statement is a flat list of rows the `StatementRenderModel` captures well. An invoice/PO is a **header + a line table + a tax summary + a totals block** — a different shape. The pipeline needs either (a) a richer render model that both statements and transactional docs flatten into, or (b) per-type renderers that share the OpenPDF primitives but build their own layout. The shipped `StatementRenderModel` is deliberately statement-shaped; forcing an invoice through it would distort both. Resolved in D-5: keep `StatementRenderModel` for statements (reused verbatim) and add a richer `DocumentRenderModel` for transactional documents, both rendered by primitives in the shared pipeline.

- **Branding has no home today.** The frozen `companies` row has `name`/`legal_name`/`tax_id` but no logo/address/footer. A document header needs all of it. A new `document_branding` table (company-scoped, 1:1, additive) is the home — never an ALTER of the frozen `companies` (D-2).

- **The generation event is for a consumer that does not exist yet (Notifications, X.2).** Documents must emit `DOCUMENT.GENERATED` over the **transactional outbox** (never an in-memory publish — it would lose the event on crash, the exact failure the outbox exists to prevent) so the future `NotificationDispatcher` can consume it and email the PDF. Documents ships the **producer only**; the consumer is designed-to-contract (D-7).

- **The render must be tenant-safe and audited (NFR-DOC-03/07).** The source document lives in a company; the render service must `assertCanActIn` on the **resolved source company** on every read path, and audit every render. A new `ScopeGuard` case `generateddocument` resolves the log row's company; the source's company is resolved via the source module's existing `findCompanyIdByUid` projection (D-9).

- **Schema freeze / direction.** IAM=V1 … Sales-Returns=V19, all frozen; V20–V22 claimed by concurrent modules. Documents is additive **V23** (tables) + **V24** (per-company seeds + permission grants). It adds three new tables, two enums-as-CHECKs, a `DOCUMENT.GENERATED` event constant, four `DOCUMENT.*` permissions, one `code_sequence` kind (lazy — no seed), one `ScopeGuard` case. It **imports no source-module entity** — it reads source DTOs through read services and the `companyIdOf` projection (the GL-reads-Sales / AP-reads-GL precedent). It posts **no GL, moves no stock**. (NFR-DOC-05/08.)

## Decision

### D-1 — Module placement: a new `com.erp.modules.documents` module; the shared render pipeline is lifted to a neutral `documents.render` package (or `platform.common.render`); Reporting imports it (no reporting↔documents cycle)

**Decision (OQ-DOC-01): a new `com.erp.modules.documents` module.** Documents is a cross-cutting concern that renders transactional documents owned by **Sales / Purchases / AR** — placing it inside `reporting` would force `reporting → sales/purchases/ar` edges that reporting does not otherwise need, and would mis-name a Sales-invoice renderer as a reporting artefact. A new leaf module that reads source DTOs is the clean home.

**The shared render pipeline (NFR-DOC-01 — the load-bearing reuse).** The OpenPDF primitives + flat models + result/download types currently in `reporting.export` are **lifted to a neutral package** so there is exactly one PDF code path:

- **Decision:** move the **statement-agnostic render primitives** — `StatementRenderModel`, `ExportResult`, `ExportFormat`, and the three renderers (`PdfStatementRenderer`, `XlsxStatementRenderer`, `CsvStatementRenderer`) + the `ReportExporter` facade — into **`com.erp.modules.documents.render`** (the documents module owns the rendering substrate); **Reporting imports them from there** (a `reporting → documents.render` edge — reporting already depends on its export classes; this just changes the package). This keeps one pipeline, gives documents the substrate it needs, and makes documents the natural owner of "rendering to a file."
  - *Alternative considered & rejected:* leave the pipeline in `reporting.export` and have `documents` import it (a `documents → reporting.export` edge). Rejected because it inverts ownership (documents is the rendering concern, not reporting) and risks a future `reporting → documents` need creating a cycle. The neutral-owner-in-documents choice is the boring, cycle-free one.
  - *Lighter alternative if the move is deemed risky mid-stream:* place the primitives in **`com.erp.platform.common.render`** (a platform utility, depended on by both reporting and documents). Acceptable; the ADR's recommendation is `documents.render` (a module, not platform, since rendering is a feature not a kernel concern), but `platform.common.render` is a valid fallback the engineer may take with the architect if the ArchUnit edge for `reporting → documents` is contentious. **Either way: one pipeline.** The engineer migrates the `reporting.export` imports in the same PR (a package move, no behaviour change — the existing reporting export tests are the regression guard, NFR-DOC-08).

Internal layout:

```
com.erp.modules.documents
├── domain.entity   DocumentTemplate            (the registry row)
│                   DocumentBranding            (per-company branding profile)
│                   GeneratedDocument           (the append-only render log)
├── domain.dto      DocumentTemplateDto / UpdateDocumentTemplateRequest,
│                   DocumentBrandingDto / UpdateDocumentBrandingRequest,
│                   GeneratedDocumentDto,
│                   RenderDocumentRequest(documentType, sourceUid),
│                   DocumentGeneratedPayload    (NEW outbox payload — producer only, D-7)
├── domain.enums    DocumentType (the 6 v1 + reserved PAYSLIP/QUOTATION/DEBIT_NOTE, D-3),
│                   RenderableState helper rules (per-type, D-6 — encoded in the builder, not a column),
│                   MasterStatus (reused platform enum for the registry soft-delete)
├── render          (the LIFTED shared pipeline — D-1)
│                   StatementRenderModel, ExportResult, ExportFormat,
│                   PdfStatementRenderer, XlsxStatementRenderer, CsvStatementRenderer, ReportExporter,
│                   DocumentRenderModel          (NEW richer model for transactional docs, D-5),
│                   DocumentPdfRenderer          (NEW — renders a DocumentRenderModel via OpenPDF primitives, D-5)
├── repository      DocumentTemplateRepository, DocumentBrandingRepository, GeneratedDocumentRepository
└── service         DocumentTemplateService(+Impl), DocumentBrandingService(+Impl),
                    DocumentRenderService(+Impl)        — the orchestrator: resolve→scope→load DTO→build model→render→log→event (D-4),
                    DocumentModelBuilder                — per-DocumentType: source DTO → DocumentRenderModel / StatementRenderModel (D-5),
                    DocumentNumberGenerator             — DOC-#### via code_sequence (D-8),
                    DocumentBrandingSeeder              — seeds branding + registry rows for a new company (D-10)
```

Controllers stay flat in `com.erp.api`: **`DocumentController`** (render + download + the log list), **`DocumentBrandingController`** (the branding admin), **`DocumentTemplateController`** (the registry admin). They touch only services (`ModuleBoundaryTest`).

**Boundary stance (D-9 / NFR-DOC-05):** `documents.service` reads **DTOs only** from `sales.service` / `purchases.service` / `ar.service` (the GL-reads-Sales / AP-reads-GL precedent), never their entities/repositories. It resolves the source company via the source repo's existing `findCompanyIdByUid` projection (a read-only scalar lookup, exposed through the source read service or a narrow projection). It publishes `DOCUMENT.GENERATED` to the outbox. **No source module depends on documents** — the only inbound edge to documents is `reporting → documents.render` (the pipeline move, D-1). Direction: `documents → sales/purchases/ar/iam/platform`, `reporting → documents.render`. **No cycle** (sales/purchases/ar do not depend on documents).

### D-2 — `document_branding` table (per-company 1:1 branding profile; additive; never alters frozen `companies`)

**Decision (OQ-DOC-04): a separate `document_branding` table**, company-scoped, one row per company, so branding evolves (logo, footer terms, future per-type override) without touching frozen V1. The logo is a **reference** (URL / path / small inline data) — **not** a blob column (file storage is the deferred `file_attachments` enabler). All columns: `id` BIGINT IDENTITY PK; `uid` VARCHAR(26) ULID; `company_id` BIGINT NOT NULL; standard audit cols; `@Version`.

#### `document_branding`

| column | type | null | notes |
|---|---|---|---|
| `id` | BIGINT IDENTITY PK | NO | |
| `uid` | VARCHAR(26) | NO | `uq_document_branding_uid` |
| `company_id` | BIGINT | NO | tenant; `fk_document_branding_company`; `uq_document_branding_company UNIQUE (company_id)` (one profile per company) |
| `display_name` | VARCHAR(160) | NO | the name on documents; seeded from `companies.name` |
| `legal_name` | VARCHAR(200) | YES | seeded from `companies.legal_name` |
| `tax_id` | VARCHAR(60) | YES | seeded from `companies.tax_id` (the TIN/VAT no. printed on invoices) |
| `address_line1` / `address_line2` | VARCHAR(200) | YES | the address block |
| `city` / `region` / `country` | VARCHAR(120) | YES | |
| `postal_code` | VARCHAR(40) | YES | |
| `contact_phone` / `contact_email` | VARCHAR(160) | YES | the contact line |
| `website` | VARCHAR(200) | YES | |
| `logo_ref` | VARCHAR(500) | YES | reference to the logo (URL/path); NULL = no logo (text-only header — BR-DOC-06 fallback) |
| `footer_terms` | VARCHAR(1000) | YES | the footer terms/notes printed on every document |
| `bank_details` | VARCHAR(500) | YES | optional pay-to bank line (for invoices) |
| `version` + audit cols | | | |

Constraints: `uq_document_branding_uid`, `uq_document_branding_company UNIQUE (company_id)`, `fk_document_branding_company FOREIGN KEY (company_id) REFERENCES companies(id)`. **No `MasterStatus`** on branding (it is a singleton profile, not a catalogue — it is edited, never deactivated). Index `ix_document_brandings_company ON document_branding (company_id)`.

**Fallback (BR-DOC-06):** if a company has no `document_branding` row (e.g. a company created before this migration, before the V24 backfill ran — the backfill covers them, but defensively), the render service falls back to the `companies` row's `name`/`legal_name`/`tax_id` via a `companies` DTO read. A render never fails for lack of branding.

### D-3 — `document_templates` registry table + the `DocumentType` enum

**Decision:** a per-company registry row per renderable document type, carrying the renderer key, the branding reference, and a `MasterStatus` soft-delete (the platform catalogue pattern). The set of renderable types is the **`DocumentType`** enum (a DB CHECK on the registry row); a registry row makes a type **active/inactive per company** (FR-DOC-03).

**`DocumentType`** (enum in `documents.domain.enums`; the registry `document_type` CHECK admits exactly these):

```
INVOICE          — sales invoice (sales_invoices, FINALISED/VOID)            → DocumentPdfRenderer (transactional)
AR_STATEMENT     — customer statement (open items + ageing)                 → PdfStatementRenderer (REUSE the lifted pipeline)
PURCHASE_ORDER   — purchase order (purchase_orders)                         → DocumentPdfRenderer (transactional)
GOODS_RECEIPT    — goods-receipt note (goods_receipts)                      → DocumentPdfRenderer (transactional, qty-only)
DELIVERY_NOTE    — delivery document (deliveries; NO prices — ADR-0021 D-7) → DocumentPdfRenderer (transactional, qty-only)
CREDIT_NOTE      — AR credit note (ar_credit_notes)                         → DocumentPdfRenderer (transactional)
-- reserved, NOT rendered in v1 (forward-shaped enum):
PAYSLIP          — payroll payslip (HR not built — OQ-DOC-08)
QUOTATION        — sales quotation (deferred display tweak)
DEBIT_NOTE       — AP debit note (deferred)
```

#### `document_templates`

| column | type | null | notes |
|---|---|---|---|
| `id` | BIGINT IDENTITY PK | NO | |
| `uid` | VARCHAR(26) | NO | `uq_document_template_uid` |
| `company_id` | BIGINT | NO | tenant; `fk_document_template_company` |
| `document_type` | VARCHAR(30) | NO | `DocumentType`; `chk_document_template_type CHECK (document_type IN ('INVOICE','AR_STATEMENT','PURCHASE_ORDER','GOODS_RECEIPT','DELIVERY_NOTE','CREDIT_NOTE','PAYSLIP','QUOTATION','DEBIT_NOTE'))` |
| `renderer_key` | VARCHAR(40) | NO | which renderer builds this type (`TRANSACTIONAL_PDF` \| `STATEMENT_PDF`); `chk_document_template_renderer CHECK (renderer_key IN ('TRANSACTIONAL_PDF','STATEMENT_PDF'))` |
| `branding_id` | BIGINT | YES | FK → `document_branding(id)`; NULL = use the company default profile (v1 always the default — per-type override deferred) |
| `title` | VARCHAR(120) | NO | the document title printed at the top (e.g. "TAX INVOICE", "PURCHASE ORDER"); seeded per type |
| `status` | VARCHAR(32) | NO | `MasterStatus` (shipped `VARCHAR(32)` string-stored enum = `ACTIVE`/`INACTIVE`/`ARCHIVED`); DEFAULT `'ACTIVE'`; `chk_document_template_status CHECK (status IN ('ACTIVE','INACTIVE','ARCHIVED'))` |
| `status_changed_at` / `status_changed_by` | TIMESTAMPTZ / BIGINT | YES | soft-delete audit (the `MasterStatus` pattern) |
| `version` + audit cols | | | |

Constraints: `uq_document_template_uid`; `uq_document_template_company_type UNIQUE (company_id, document_type)` (one registry row per type per company); `fk_document_template_company`; `fk_document_template_branding FOREIGN KEY (branding_id) REFERENCES document_branding(id)`; the two CHECKs above + the status CHECK. Index `ix_document_templates_company ON document_templates (company_id)`.

**Inactive-type rule (FR-DOC-03 / BR-DOC-04):** rendering resolves the `(company_id, document_type)` registry row; if it is missing or `status <> 'ACTIVE'` the render is refused ("document type not enabled"). The V24 seed inserts all six v1 types ACTIVE per company.

### D-4 — `generated_documents` render log + the `DocumentRenderService` orchestration

**Decision (OQ-DOC-03): persist the render *record*, re-render the *bytes* on download** (the source is the system of record; no blob store needed; the download always reflects current truth). The log carries a `content_hash` so a diverged re-render is detectable. (Persisting bytes is the right call once `file_attachments` lands and an immutable as-sent copy matters — additive then, NFR-DOC-08.)

#### `generated_documents` (append-only render log, BR-DOC-08)

| column | type | null | notes |
|---|---|---|---|
| `id` | BIGINT IDENTITY PK | NO | |
| `uid` | VARCHAR(26) | NO | `uq_generated_document_uid` — the download address (`/api/documents/{uid}/download`) |
| `company_id` | BIGINT | NO | tenant (the **source's** company, resolved + scope-checked at render); `fk_generated_document_company` |
| `branch_id` | BIGINT | YES | the source's branch (analysis tag; nullable — some sources are company-level) |
| `document_number` | VARCHAR(30) | NO | `DOC-####` (D-8); `uq_generated_document_company_number UNIQUE (company_id, document_number)` |
| `document_type` | VARCHAR(30) | NO | `DocumentType`; `chk_generated_document_type` (same IN-list as the registry) |
| `source_type` | VARCHAR(30) | NO | the source aggregate kind (e.g. `SALES_INVOICE`, `PURCHASE_ORDER`, `GOODS_RECEIPT`, `DELIVERY`, `AR_CREDIT_NOTE`, `AR_STATEMENT`) — for diagnostics + the re-render dispatch |
| `source_uid` | VARCHAR(26) | YES | the source document uid (the re-render key). **Nullable** because a parameterised render (e.g. a statement for a customer over a date range) has no single source uid — see `source_params` |
| `source_params` | JSONB | YES | parameters for a parameterised render (e.g. statement `{ customerUid, fromDate, toDate }`); NULL for a single-source-uid render. (Postgres `JSONB` per PROJECT-CONVENTIONS — flexible attributes.) |
| `branding_id` | BIGINT | YES | the branding profile used (audit trace); `fk_generated_document_branding` |
| `content_hash` | VARCHAR(64) | YES | SHA-256 (hex) of the rendered bytes at render time — lets a re-download detect divergence |
| `byte_size` | INTEGER | YES | the rendered size in bytes (diagnostic) |
| `mime_type` | VARCHAR(80) | NO | `application/pdf` (v1) |
| `generated_by` | BIGINT | NO | FK → `app_users(id)` — who rendered |
| `generated_at` | TIMESTAMPTZ | NO | DEFAULT now() |
| audit cols | | | (no `@Version` — append-only, never updated) |

Constraints: `uq_generated_document_uid`; `uq_generated_document_company_number`; `fk_generated_document_company`; `fk_generated_document_branding FOREIGN KEY (branding_id) REFERENCES document_branding(id)`; `chk_generated_document_type`; `chk_generated_document_source CHECK (source_uid IS NOT NULL OR source_params IS NOT NULL)` (a render targets either a single source or a parameter set). Indexes: `ix_generated_documents_company_type ON (company_id, document_type)`, `ix_generated_documents_source ON (source_type, source_uid)`, `ix_generated_documents_generated_at ON (company_id, generated_at)` (the log list filters/sorts).

**Append-only (BR-DOC-08):** `generated_documents` is never updated or deleted (no `@Version`); a re-render appends a new row. (A DB-grant no-update/no-delete hardening, like the audit table's F11 grant, is a follow-up, not v1-blocking.)

**`DocumentRenderService.render(RenderDocumentRequest)` — the orchestration (one `@Transactional` method, FR-DOC-06..14):**
1. resolve the `documentType`'s registry row for the active company; reject if missing/inactive (BR-DOC-04).
2. resolve the **source company** via the source module's `findCompanyIdByUid` projection (D-9); `ScopeGuard.assertCanActIn(principal, sourceCompanyId)` (NFR-DOC-03). For a parameterised render, scope-check the parameter entity (e.g. the customer's company).
3. load the source **DTO** via the source read service; assert the **renderable state** for the type (D-6) — reject DRAFT invoice etc. (BR-DOC-05).
4. load the company `document_branding` (or fall back to `companies` fields — BR-DOC-06).
5. the `DocumentModelBuilder` builds the type's render model (`DocumentRenderModel` for transactional types, `StatementRenderModel` for `AR_STATEMENT` — D-5).
6. render to PDF via the `renderer_key`'s renderer (`DocumentPdfRenderer` or `PdfStatementRenderer`); compute `content_hash` + `byte_size`.
7. allocate `DOC-####` (D-8); insert the `generated_documents` row; **publish `DOCUMENT.GENERATED`** to the outbox in the same TX (D-7); audit (`DOCUMENT.RENDER`); commit.
8. return the `GeneratedDocumentDto` (+ optionally stream the bytes inline for the convenience render).

`render` does **no** money arithmetic, **no** GL post, **no** stock move (NFR-DOC-02). The only writes are the log row + the audit row + the outbox row. The download path (`download(uid)`) re-runs steps 2–6 from the recorded `source_type/source_uid/source_params` and streams the bytes (OQ-DOC-03) — it does **not** insert a new log row by default (a re-download is not a new render event; the architect may choose to append a row on a hash-divergence — a diagnostic refinement, not v1-blocking).

### D-5 — Two render models, one pipeline: `StatementRenderModel` (reused) for `AR_STATEMENT`, a new `DocumentRenderModel` for the transactional documents

**Decision (the richness force):** keep the shipped `StatementRenderModel` **unchanged** for `AR_STATEMENT` (it is exactly statement-shaped and already renders via `PdfStatementRenderer`); add a **`DocumentRenderModel`** for the transactional documents (invoice/PO/GRN/delivery/credit note), rendered by a new **`DocumentPdfRenderer`** that **reuses the same OpenPDF primitives** (`com.lowagie.text.*`, the cell/table/font helpers) as `PdfStatementRenderer`. Both renderers live in the lifted `documents.render` package — **one pipeline, two layouts** (NFR-DOC-01).

**`DocumentRenderModel`** (record in `documents.render`) — captures a branded header + a line table + a totals/tax block:

```
DocumentRenderModel(
    title            String,        // "TAX INVOICE" / "PURCHASE ORDER" / "DELIVERY NOTE" …
    branding         BrandingBlock( displayName, legalName, taxId, addressLines List<String>,
                                    contactLine, logoRef, footerTerms, bankDetails ),
    meta             List<MetaPair(label, value)>,   // doc number, date, customer/supplier, terms, etc.
    counterparty     PartyBlock( name, addressLines List<String>, taxId ),   // bill-to / ship-to / supplier
    lines            List<DocLine( lineNo, code, description, qty, unit,
                                   unitPrice (nullable), discount (nullable),
                                   taxLabel (nullable), lineTotal (nullable) )>,  // prices NULL for qty-only docs (delivery/GRN)
    taxSummary       List<TaxRow(bandLabel, base, rate, vat)>,  // empty for qty-only docs
    totals           List<TotalRow(label, amount, emphasised)>, // net / VAT / gross; empty for qty-only docs
    currency         String,        // for display formatting (base TZS)
    generatedAt      String,
    voidLabel        String         // null, or "VOID" stamp text when the source is VOID (D-6)
)
```

- **Per-type builders (in `DocumentModelBuilder`):**
  - **INVOICE** ← `SalesInvoiceDto`: branding + bill-to (customer) + meta (`INV-####`, date, agent, terms); lines with full price/discount/VAT/line-total; `taxSummary` from the invoice `tax_summary` JSONB; totals net/VAT/gross. The amounts are **copied from the DTO** — no recomputation (NFR-DOC-02 / BR-DOC-09).
  - **PURCHASE_ORDER** ← `PurchaseOrderDto`: branding + supplier block + meta (`PO-####`, date, delivery terms); lines with cost/qty/line-total; totals.
  - **GOODS_RECEIPT** ← `GoodsReceiptDto`: branding + supplier + meta (GRN ref, date, against-PO); lines **qty-only** (received qty); no `taxSummary`/`totals` (the supplier copy is qty-only — BR-DOC-07).
  - **DELIVERY_NOTE** ← `DeliveryDto`: branding + ship-to (customer) + meta (`DEL-####`, date, against-SO); lines **qty-only** (delivered qty) — **no prices** (ADR-0021 D-7 — the delivery line carries no pricing); no `taxSummary`/`totals`.
  - **CREDIT_NOTE** ← `ArCreditNoteDto`: branding + customer + meta (`CN-####`, date, reason, against-invoice); the credited net/VAT/gross; lines if the credit note carries them, else a single net line.
- **AR_STATEMENT** ← the existing statement query → `StatementRenderModel` (REUSE — FR-DOC-11). `DocumentModelBuilder` delegates to the existing statement flattener; `PdfStatementRenderer` renders it. No new statement code.

`DocumentPdfRenderer.render(DocumentRenderModel)→byte[]` draws: the branding header (logo if `logoRef` resolvable, the address block, tax id), the title, the meta + counterparty blocks, the line table (price columns omitted when all line prices are null — qty-only docs), the tax summary + totals (omitted when empty), the footer terms, and a "VOID" watermark/label when `voidLabel` is set. It uses the **same** `addCell`/font/`PdfPTable` helpers `PdfStatementRenderer` uses (extract the shared OpenPDF helpers into a small `PdfPrimitives` util in `documents.render` so both renderers share them — NFR-DOC-01).

### D-6 — Renderable-state rules per type (OQ-DOC-02; encoded in the builder, not a column)

**Decision:** the renderable-state rule is **per type, enforced in the model builder** (not a stored column), refusing the render with a clear error before any work (BR-DOC-05 / FR-DOC-13):

| DocumentType | renderable when source status is | refused when |
|---|---|---|
| INVOICE | FINALISED, VOID | DRAFT (no `INV-####` yet) — refuse "invoice not finalised" |
| CREDIT_NOTE | any posted state | — (a credit note is posted on raise) |
| PURCHASE_ORDER | ORDERED (and beyond), VOID | DRAFT — refuse "PO not placed" (the supplier copy needs the `PO-####`) |
| GOODS_RECEIPT | RECEIVED, VOID | DRAFT |
| DELIVERY_NOTE | CONFIRMED | DRAFT (ADR-0021 D-2 — v1 deliveries are created CONFIRMED, so always renderable) |
| AR_STATEMENT | n/a (parameterised — always renderable for a valid customer + range) | — |

A **VOID** source renders with the `voidLabel = "VOID"` stamp (D-5) for audit (a voided invoice is still printable, marked void). The engineer reads the source DTO's `status` and applies this table; the exact source status enum values are the source modules' (Sales `DRAFT/FINALISED/VOID`, Purchases GR `DRAFT/RECEIVED/VOID`, PO `DRAFT/ORDERED/.../VOID`, Delivery `CONFIRMED`).

### D-7 — `DOCUMENT.GENERATED` outbox event (PRODUCER ONLY; designed to the Notifications contract)

**Decision:** on a committed successful render, `DocumentRenderService` **publishes `DOCUMENT.GENERATED`** to the transactional outbox **in the render TX** (BR-DOC-10 — never an in-memory publish). Documents ships the **producer**; the **consumer is the future Notifications enabler (X.2)** — designed-to-contract, none ships here.

```
DomainEventType.DOCUMENT_GENERATED = "DOCUMENT.GENERATED"     (NEW constant in platform.events.DomainEventType)
DomainEventType.AGG_GENERATED_DOCUMENT = "GENERATED_DOCUMENT"  (NEW aggregate-type constant)

OutboxPublisher.publish(
    DOCUMENT_GENERATED, AGG_GENERATED_DOCUMENT,
    generatedDocument.getId(), generatedDocument.getUid(),
    companyId, branchId,
    new DocumentGeneratedPayload(
        documentUid, documentType, sourceType, sourceUid,
        companyId, branchId, generatedBy, generatedAt
    )   // NO bytes — the consumer re-renders or fetches via /api/documents/{uid}/download
)
```

- **`DocumentGeneratedPayload`** lives in `documents.domain.dto` (the producer owns the payload — the `SaleFinalisedPayload`-in-`sales.domain.dto` precedent). It carries **no bytes** (events are small; the consumer fetches the PDF via the download endpoint or re-renders). The future `NotificationDispatcher implements DomainEventHandler` (in the notifications module) consumes `DOCUMENT.GENERATED` under `IdempotencyGuard("NOTIFICATIONS.DOCUMENT_GENERATED", event.uid)`, looks up the recipient (e.g. the invoice's customer email), and dispatches — **all future work, in the Notifications module, importing `documents.domain.dto` as a DTO dependency** (the stock-imports-sales-payload precedent, no cycle since notifications is a new leaf consumer).
- **OQ-DOC-05 default:** emit on **every** committed render. The owner may later prefer a "notify" flag on `RenderDocumentRequest` so only explicitly-requested renders emit; that is an additive request field + a publish guard (the consumer can also filter). Default is emit-always (cheap; the consumer decides whether to act).

### D-8 — Numbering: one new `code_sequence` kind `DOCUMENT` (`DOC-####`), lazy (no seed)

`DocumentNumberGenerator` reuses the shipped `code_sequence` row-locked per-company allocation (ADR-0007 D-6) with **one new `entity_kind` value `DOCUMENT`** → `DOC-%04d`, allocated at render. The kind is **created lazily** on first use (`next_value = 1`) — **no seed row, no #12 seed-uid exposure** for numbering. The `uq_generated_document_company_number` constraint backstops generator bugs. (OQ-DOC-06 default: keep a `DOC-####` for log legibility; the uid is the canonical handle.)

### D-9 — `ScopeGuard` case `generateddocument` + source-company resolution

- A new `ScopeGuard.companyIdOf` case: `case "generateddocument" -> generatedDocuments.findCompanyIdByUid(uid);` (the `GeneratedDocumentRepository` gains a `findCompanyIdByUid` projection — the shipped pattern). Used for `GET /api/documents/{uid}/download` (`@perm.scoped(uid, 'generateddocument', 'DOCUMENT.VIEW')`) and the log read.
- A new case `documentbranding -> documentBrandings.findCompanyIdByUid(uid)` and `documenttemplate -> documentTemplates.findCompanyIdByUid(uid)` for the admin edits.
- For the **render** path, the scope check is on the **source** company, resolved via the source module's existing `companyIdOf` case (e.g. `"invoice"`, `"purchaseorder"`, `"goodsreceipt"`, `"delivery"`) — `DocumentRenderService` calls `ScopeGuard.assertCanActIn(principal, sourceCompanyId)` with the company resolved from the source uid + source type. (No new ScopeGuard case is needed for the source — it reuses the source's existing case via `companyIdOf(sourceType, sourceUid)`.)

### D-10 — Permissions (`DOCUMENT.*`) + the new-company seeder + ORG_ADMIN grant

**Decision (OQ-DOC-07): a coarse `DOCUMENT.*` set** — render is gated by `DOCUMENT.RENDER` *plus* the source screen's own VIEW gate (you reach the render from the source screen, which already gates who sees the invoice/PO). Four permissions (module `documents`):

| permission | gates |
|---|---|
| `DOCUMENT.RENDER` | render / download a document (`POST /render`, `GET /{uid}/download`) |
| `DOCUMENT.VIEW` | view/list the `generated_documents` render log |
| `DOCUMENT.BRANDING.MANAGE` | edit the company branding profile |
| `DOCUMENT.TEMPLATE.MANAGE` | toggle document types active/inactive in the registry |

All seeded `ON CONFLICT (code) DO NOTHING` and granted to `ORG_ADMIN` via the `roles × permissions` CROSS JOIN `ON CONFLICT DO NOTHING` (the V7/V12/V14/V17/V18 pattern). **Permissions have no `uid` — #12 N/A.**

**`DocumentBrandingSeeder`** (the `ApGlSeeder`/`InventoryGlSeeder` per-module new-company seeder pattern, wired into `BootstrapRunner` + `CompanyService.create`): on a new company, inserts one `document_branding` row (seeded from the `companies` fields) + the six v1 `document_templates` rows (all ACTIVE). The V24 migration runs the **same** seed for **existing** companies (the backfill — D-12).

### D-11 — The API surface (controllers flat in `com.erp.api`)

**`DocumentController`** (`/api/documents`):

| method | path | perm | body / params | returns |
|---|---|---|---|---|
| POST | `/api/documents/render` | `@perm.has('DOCUMENT.RENDER')` (+ `assertCanActIn` on source) | `RenderDocumentRequest{ documentType, sourceUid, sourceParams? }` | `ApiResponse<GeneratedDocumentDto>` (the record + download link) |
| GET | `/api/documents/{uid}/download` | `@perm.scoped(uid, 'generateddocument', 'DOCUMENT.RENDER')` | — | `ResponseEntity<byte[]>` (`Content-Disposition: attachment`, re-rendered — D-4) |
| GET | `/api/documents/render` | `@perm.has('DOCUMENT.RENDER')` (+ `assertCanActIn`) | `?type=&source=` | `ResponseEntity<byte[]>` (convenience inline render+stream) |
| GET | `/api/documents` | `@perm.has('DOCUMENT.VIEW')` | `?type=&sourceUid=&from=&to=` paged | `ApiResponse<Page<GeneratedDocumentDto>>` (the log) |
| GET | `/api/documents/{uid}` | `@perm.scoped(uid, 'generateddocument', 'DOCUMENT.VIEW')` | — | `ApiResponse<GeneratedDocumentDto>` |

**`DocumentBrandingController`** (`/api/documents/branding`):

| method | path | perm | returns |
|---|---|---|---|
| GET | `/api/documents/branding` | `@perm.has('DOCUMENT.BRANDING.MANAGE')` (the active company's profile) | `ApiResponse<DocumentBrandingDto>` |
| PUT | `/api/documents/branding` | `@perm.has('DOCUMENT.BRANDING.MANAGE')` (+ `assertCanActIn`) | `ApiResponse<DocumentBrandingDto>` |

**`DocumentTemplateController`** (`/api/documents/templates`):

| method | path | perm | returns |
|---|---|---|---|
| GET | `/api/documents/templates` | `@perm.has('DOCUMENT.TEMPLATE.MANAGE')` | `ApiResponse<List<DocumentTemplateDto>>` (the company's registry) |
| PUT | `/api/documents/templates/{uid}` | `@perm.scoped(uid, 'documenttemplate', 'DOCUMENT.TEMPLATE.MANAGE')` | `ApiResponse<DocumentTemplateDto>` (toggle active/inactive, set title/branding) |

All responses use the platform `ApiResponse<T>` envelope (the `ApiResponseAdvice` wraps them). Controllers touch services only.

### D-12 — Migration ordering (V23 tables + V24 seed; additive; V1–V19 frozen; #12-safe)

**`V23__documents.sql`** (DDL — additive; references only frozen V1 `companies`/`app_users`):
1. **CREATE** `document_branding` (+ constraints/index — D-2).
2. **CREATE** `document_templates` (+ constraints/index — D-3).
3. **CREATE** `generated_documents` (+ constraints/indexes — D-4).
4. **permission seed** — INSERT `DOCUMENT.RENDER`/`DOCUMENT.VIEW`/`DOCUMENT.BRANDING.MANAGE`/`DOCUMENT.TEMPLATE.MANAGE` (module `documents`) `ON CONFLICT (code) DO NOTHING`; grant to `ORG_ADMIN` via the CROSS JOIN `ON CONFLICT DO NOTHING`. (No uid — #12 N/A.)

**`V24__documents_seed.sql`** (per-company backfill for **existing** companies — the new-company path is `DocumentBrandingSeeder`):
5. **backfill `document_branding`** — `INSERT INTO document_branding (uid, company_id, display_name, legal_name, tax_id, created_by) SELECT 'DB' || lpad(c.id::text,6,'0') || substr(md5('branding'),1,12), c.id, c.name, c.legal_name, c.tax_id, <system> FROM companies c WHERE c.id NOT IN (SELECT company_id FROM document_branding)` — **#12-safe seed-uid** (`'DB' || lpad(company_id,6,'0') || substr(md5(<stable-key>),1,12)` ≤ 26 chars; **never** `|| company_id::text || key` raw-concat). One profile per company (`uq_document_branding_company` makes the insert idempotent on re-run via the `NOT IN` guard).
6. **backfill `document_templates`** — for each existing company × each of the six v1 types, INSERT a registry row ACTIVE. **#12-safe seed-uid:** `'DT' || lpad(c.id::text,6,'0') || substr(md5('tmpl:' || dt.document_type),1,12)` where `dt.document_type` ranges over a `VALUES` list of the six types — md5-bounded per (company, type), **never** raw `|| document_type`. Guard with `WHERE NOT EXISTS (... company_id, document_type ...)` (idempotent; `uq_document_template_company_type` backstops). `branding_id` set to the company's `document_branding.id` (or NULL = use default).

**Why two migrations:** V23 is the pure DDL + permission seed (the part that must land before the entities); V24 is the per-company data backfill (the part that depends on the existing `companies` rows). Splitting keeps the DDL atomic and the data backfill re-runnable-safe; equally the engineer may fold both into a single `V23` (DDL is cheap) — **the table/column/constraint names above are fixed either way**. The `DOCUMENT` `code_sequence` kind is **not** seeded (lazy — D-8). **No `gl_config` key, no CoA account, no `JournalSourceType`, no movement type, no FK into a frozen transactional table** — documents posts nothing and references frozen tables only by scalar uid at runtime (NFR-DOC-02). `MigrationKeepDataIT` extends to V23/V24 (the new tables + the backfill are keep-data-safe — additive, idempotent inserts).

### D-13 — ArchUnit module-edge rules (no cycle)

- **`documents.service` → `sales.service` / `purchases.service` / `ar.service`** (DTO-only reads — the GL-reads-Sales / AP-reads-GL precedent). **Allowed.** documents imports source DTOs + read-service interfaces, **never** source entities/repositories.
- **`documents.service` → `platform.events`** (`OutboxPublisher`, `DomainEventType`) for `DOCUMENT.GENERATED`. **Allowed** (every producer module has this edge).
- **`documents.service` → `platform.security`** (`ScopeGuard`, `RequestContext`, `PermissionChecks`) + **`platform.audit`** (`AuditService`). **Allowed** (the cross-cutting spine).
- **`reporting → documents.render`** (the pipeline move — D-1). **Allowed.** This is the **only inbound edge to documents**; it is a render-substrate dependency, not a cycle (documents does not depend on reporting).
- **`ScopeGuard` → `documents.repository`** (the `companyIdOf` cases — D-9). **Allowed** — the documented ScopeGuard exception (ScopeGuard reads module repositories as the cross-cutting spine, ADR-0002 / the shipped pattern with every module's repos).
- **`platform.events.DomainEventType`** gains the `DOCUMENT.GENERATED` constant (a constants holder, no module edge).
- **No edge `sales/purchases/ar → documents`** (source modules never depend on documents) and **no edge `documents → reporting`** (the substrate moved into documents). **No cycle.** The shipped `ModuleBoundaryTest` (controller↛repository, service↛controller, DTO-only cross-module, audit-append-only) holds — none of these edges violates an active rule.

## Consequences

**Positive**
- One PDF code path (NFR-DOC-01): the shipped OpenPDF pipeline is lifted, reused for statements verbatim, and extended (not duplicated) for transactional documents. Adding a future document type (debit note, payslip) is a `DocumentType` value + a `DocumentModelBuilder` case + a registry seed row — no new controller stack.
- **Zero financial risk** (BR-DOC-02 / NFR-DOC-02): documents reads DTOs and formats already-computed amounts; it posts no GL, moves no stock, mutates no source, does no money arithmetic. A documents defect can at worst produce a wrong-looking PDF, never wrong books. The boundary (DTO-only, no source-entity import) structurally enforces this.
- **Crash-safe notifiability** (BR-DOC-10): `DOCUMENT.GENERATED` rides the transactional outbox in the render TX; the future Notifications consumer is idempotent. No in-memory publish that could lose the event.
- Additive and non-regressive: 3 new tables, 4 permissions, 1 event constant, 1 `code_sequence` kind (lazy), 1 ScopeGuard case (+2 admin cases), a package move of the reporting export classes (regression-guarded by the existing reporting tests). **V1–V19 frozen; references frozen tables by scalar uid only.**
- Branding has a proper home (`document_branding`) that evolves without touching frozen `companies`, with a `companies`-field fallback so a render never fails for lack of a profile.

**Negative / costs**
- The pipeline package move (`reporting.export → documents.render`, D-1) touches Reporting's imports in the same PR. It is a mechanical move (no behaviour change), but it is a cross-module edit — flagged in the touch list; the existing reporting export tests are the guard.
- Re-render-on-download (OQ-DOC-03) means a re-download of a since-changed source reflects current truth, not the as-rendered-then bytes. Acceptable (arguably correct) for v1; the `content_hash` makes divergence detectable. When an immutable as-sent copy is legally required, persisting bytes (via `file_attachments`) is the additive follow-up.
- The six per-type `DocumentModelBuilder` cases each read a source DTO shape; if a source DTO changes (e.g. Sales adds a line field), the builder may need a tweak — but only a presentational one (no financial coupling). The builders are the per-type maintenance surface.
- `generated_documents` grows one row per render (append-only). At scale this is a log table needing a retention policy (the deferred data-archival enabler); not v1-blocking, flagged.

**Neutral / deferred**
- PDF-only for transactional docs; statements keep PDF/XLSX/CSV. Emailing, byte persistence, payslips, user-editable templates, per-type branding, batch/scheduled generation, multi-language, digital signatures/PDF-A/EFD — all deferred (documents.md §2.2), none precluded (NFR-DOC-08). The `DocumentType` enum and the `document_templates.renderer_key`/`branding_id` columns are the forward-shaped seams these build on.

## Alternatives considered

- **Module placement — new `documents` module vs extend `reporting` (OQ-DOC-01).** *Decided: new module, pipeline lifted into it.* Extending reporting would force `reporting → sales/purchases/ar` edges and mis-own a Sales-invoice renderer. A new leaf module reading source DTOs is the clean home; the pipeline move makes documents the rendering substrate owner. (Fallback: `platform.common.render` if the `reporting → documents` edge is contentious — acceptable, same one-pipeline outcome.)
- **Render model — one universal model vs statement + transactional (D-5).** *Decided: keep `StatementRenderModel` for statements, add `DocumentRenderModel` for transactional docs, share the OpenPDF primitives.* Forcing an invoice through the statement model would distort both; a separate richer model with shared primitives keeps one pipeline without contorting either layout.
- **Persist bytes vs re-render on download (OQ-DOC-03).** *Decided: re-render on download, persist the record + hash.* No blob store needed; always current truth; the hash detects divergence. Persisting bytes is right once `file_attachments` + an immutable-copy requirement land — additive then.
- **Branding storage — `document_branding` table vs ALTER `companies` (OQ-DOC-04).** *Decided: a separate table.* Never alter the frozen `companies`; a dedicated table lets branding grow (logo, footer, per-type override) and keeps a clean 1:1 with a `companies`-field fallback.
- **Event always vs notify-flag (OQ-DOC-05).** *Decided: emit on every committed render; the consumer filters.* Cheaper and more flexible than a producer-side flag; the owner may add a `notify` request field additively if it wants producer-side suppression.
- **Permission shape — coarse `DOCUMENT.*` vs per-source-type render perm (OQ-DOC-07).** *Decided: coarse `DOCUMENT.RENDER` + the source screen's existing VIEW gate.* A per-type render permission multiplies the permission catalogue with little benefit (the source screen already gates who sees the document). The owner may layer the source module's VIEW perm into the render check additively.
- **In-memory `ApplicationEventPublisher` for the generation event vs the outbox.** *Decided: the transactional outbox.* The in-memory publisher loses events on crash — the exact failure the outbox exists to prevent (PROJECT-CONVENTIONS). The render record + the event must commit together.

## Open items (OQ-DOC — owner-style defaults adopted; none blocks the build)

- **OQ-DOC-01 — module placement:** adopted **new `com.erp.modules.documents`**, pipeline lifted to `documents.render`; fallback `platform.common.render`. Settled (architect default; owner may confirm the package home).
- **OQ-DOC-02 — renderable-state:** adopted the **per-type table in D-6** (INVOICE FINALISED/VOID, PO ORDERED+/VOID, GRN RECEIVED/VOID, DELIVERY CONFIRMED, CREDIT_NOTE posted, STATEMENT parameterised; VOID renders stamped). Settled.
- **OQ-DOC-03 — persist vs re-render:** adopted **re-render on download + record/hash**. Settled; the immutable-copy trade-off flagged for `file_attachments`.
- **OQ-DOC-04 — branding storage:** adopted **`document_branding` table**, logo as a reference (not a blob). Settled.
- **OQ-DOC-05 — event payload / emit-always:** adopted **emit on every committed render**, payload no bytes; **confirm with the Notifications enabler contract** when X.2 is designed (the payload field set is the contract documents commits to).
- **OQ-DOC-06 — numbering:** adopted **`DOC-####` via `code_sequence`** (lazy kind). Settled.
- **OQ-DOC-07 — permission shape:** adopted **coarse `DOCUMENT.*`** + the source screen's VIEW gate. Owner may layer source-module VIEW into the render check (additive). Flagged.
- **OQ-DOC-08 — future types (PAYSLIP/QUOTATION/DEBIT_NOTE):** reserved in the enum, **not rendered in v1**. No action; forward-shaped.

---

## Summary

ADR-0023 designs the **Document/PDF Generation** enabler in a new `com.erp.modules.documents` module: three additive tables (`document_branding` — per-company 1:1 profile; `document_templates` — the per-company renderable-type registry with a `MasterStatus` soft-delete; `generated_documents` — the append-only render log addressed by `uid` with a `DOC-####` handle, persisting the **record + content hash** and **re-rendering bytes on download**), the six v1 document types (INVOICE, AR_STATEMENT, PURCHASE_ORDER, GOODS_RECEIPT, DELIVERY_NOTE, CREDIT_NOTE), a **side-effect-free `DocumentRenderService`** that resolves + scope-checks the source company, loads the source **DTO** via its read service, builds a `DocumentRenderModel` (transactional) or reuses the shipped `StatementRenderModel` (statement), and renders through **one OpenPDF pipeline lifted from Reporting** (`documents.render`; Reporting imports it — no cycle).

**The invariant (BR-DOC-02 / NFR-DOC-02):** rendering is **read-only on the source** — no GL post, no stock move, no money arithmetic, no source mutation; documents writes only its own log row, an audit row, and the outbox event. On a committed render it **publishes `DOCUMENT.GENERATED`** to the transactional outbox (producer only; the future Notifications enabler consumes it to email the PDF — designed-to-contract, no consumer ships here).

**Readiness:** every table, column, constraint name, enum, render model, per-type builder, renderable-state rule, event/payload, numbering kind, ScopeGuard case, permission, API endpoint, and the migration ordering with #12-safe seed-uids is specified — concrete enough to build V23/V24 + the registry/branding model + the render service + the six renderers + the download controller without guessing a rule. **Additive on frozen V1–V19** (order-independent of the concurrent V20–V22 — references only frozen V1 tables + its own). **#12-safe** (the only per-company seed-uids — branding + template backfills in V24 — use `'XX' || lpad(company_id,6,'0') || substr(md5(<stable-key>),1,12)`; the `DOCUMENT` numbering kind is lazy). **Cross-module touch list:** (1) **documents → sales/purchases/ar** — DTO-only source reads via read services; (2) **reporting → documents.render** — the one-time pipeline package move (regression-guarded by the existing reporting export tests); (3) **platform.events** — the `DOCUMENT.GENERATED` + `AGG_GENERATED_DOCUMENT` constants; (4) **ScopeGuard** — the `generateddocument`/`documenttemplate`/`documentbranding` cases; (5) **BootstrapRunner / CompanyService.create** — wiring `DocumentBrandingSeeder` (the per-module new-company seeder pattern). **No GL config key, no CoA account, no DomainEventType beyond `DOCUMENT.GENERATED`, no stock movement type — documents posts nothing.**
