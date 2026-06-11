# Requirements — Document / PDF Generation (the template registry → render service → downloads spine)

> Status: **DRAFT (architect-authored from the PATH-TO-FULL-ERP §3.12 X.1 backlog item, owner-style
> assumptions made and flagged).** This is a **cross-cutting platform enabler**, not a transactional
> module: it turns the documents the business already produces (invoices, statements, POs, GRNs,
> delivery notes, credit notes, …) into **branded, downloadable PDFs** through one reusable
> rendering pipeline, and exposes a **download endpoint** every module links to. It **changes no
> financial logic** — it is a read-only projection of data the source modules already own, rendered
> to paper. Emailing the rendered output is **out of scope here** and hooks into the (not-yet-built)
> **Notifications** enabler (X.2) over the outbox; this module only produces the bytes and the
> generation event Notifications will consume.
>
> Author: solutions-architect (requirements distilled from the backlog; no system-analyst elicitation
> session preceded this — the load-bearing scope/policy choices are flagged as Open Questions for
> owner ratification). Domain: a **new** `com.erp.modules.documents` module. Business-level spec;
> **no schema, no API shapes, no tables/columns, no code** — those are the architect's, in **ADR-0023**
> (the companion data-model ADR, next step). Do not infer a data model from this document.
>
> **This is Document / PDF Generation — cross-cutting enabler X.1 (docs/PATH-TO-FULL-ERP.md area 15 /
> §3.12, critical-dependency #6).** It is **additive output**: it depends on the source modules being
> built (they all are, for v1's document set) and **gates nothing** — no module is blocked waiting on
> it; it makes the system usable in the real world (a customer wants a PDF invoice, a supplier wants a
> printed PO).
>
> **Depends on (all SHIPPED):** the **source modules** whose documents v1 renders — **Sales** (ADR-0008/
> V5 `sales_invoices` + lines + payments; ADR-0021/V18 deliveries + credit-via-AR), **AR** (ADR-0014/V11
> customer statements + credit notes), **AP** (ADR-0015/V12 supplier bills/POs context), **Purchases**
> (ADR-0011/V8 `purchase_orders` + `goods_receipts`), **GL/Reporting** (ADR-0013/0018 — the **existing**
> `ReportExporter` / `PdfStatementRenderer` / `StatementRenderModel` / `ExportFormat` pipeline this
> module **generalises rather than duplicates**). Plus the platform spine: **IAM** (RBAC `@perm.has`/
> `@perm.scoped`, `ScopeGuard.assertCanActIn`, audit), the **transactional outbox** (ADR-0009 —
> `DomainEventType` + `IdempotencyGuard` + `processed_events`) for the generation event Notifications
> will later consume, **`code_sequence`** numbering, **Money** (display formatting only — base currency
> TZS, 0-dp display), and the existing **OpenPDF + Apache POI** libraries already in the POM (ADR-0018 D-9).
>
> **The single most important framing:** the Reporting module already PDF-renders financial statements
> (`PdfStatementRenderer` over a flat `StatementRenderModel`, packaged by `ReportExporter` into a
> `download(...)` `ResponseEntity<byte[]>`). v1 of this module **lifts that pipeline into a shared
> platform service** and adds a **template registry** (which document types render, where the header/
> branding comes from) and a **generic transactional-document render path** (invoice/PO/GRN/delivery
> note/credit note are richer than a statement — line tables, totals, tax summaries). It does **not**
> re-invent PDF rendering.

---

## 1. Business context & why now

ERPCLEAN2 keeps correct books and runs a full order-to-cash and procure-to-pay loop, but it cannot yet
**hand a customer a printed invoice**, **email a supplier a purchase order**, or **give a customer a PDF
statement** outside of the few financial statements Reporting already exports. Every real ERP user
expects a document they can save, print, attach to an email, and file. Today:

- Sales finalises an `INV-####` invoice with full line/tax/total data — but there is **no PDF of it**.
- AR computes a customer statement and an ageing — Reporting can PDF the **statement** via its own
  renderer, but it is welded into the reporting module, not reusable for an invoice or a PO.
- Purchases places a `PO-####` — there is **no document to send the supplier** (PATH-TO-FULL-ERP §3.4
  explicitly flags "PO PDF / document export — needed to send POs to suppliers").
- AR raises a credit note, the warehouse confirms a GRN / a delivery — **no printable artefact** exists.

The data is all there and correct; what is missing is a **rendering layer**. The constraint that makes
this an enabler rather than a feature: there must be **exactly one** rendering pipeline and **one**
download endpoint, so that adding "render a debit note" later is a registry entry plus a template, not
a new bespoke controller. The Reporting export pipeline already proves the shape (OpenPDF, a flat render
model, a `download` helper); v1 **promotes it to a platform service** and extends it from statements to
the richer transactional documents.

**Why now:** Phase B (PATH-TO-FULL-ERP §5) pulls Documents/PDF (X.1) and Notifications (X.2) forward
because Sales and Procurement depth need them — an order-to-cash flow that cannot produce an invoice PDF
or a delivery note, and a procure-to-pay flow that cannot produce a PO PDF, are not field-usable.

---

## 2. Scope

### 2.1 In scope (v1)

1. **A template registry** — a per-company catalogue of which **document types** the system can render
   (INVOICE, AR_STATEMENT, PURCHASE_ORDER, GOODS_RECEIPT, DELIVERY_NOTE, CREDIT_NOTE), each mapped to a
   **renderer** and a **branding profile**. Seeded with the v1 document types on company create; the
   admin can toggle a type **active/inactive** and (later) override the branding. Soft-delete
   (`MasterStatus`) — never hard-delete a registry row that documents may reference.
2. **A render service** — given a `(documentType, sourceUid)`, load the source document via the owning
   module's **read service (DTO only)**, build the type's render model, render it to **PDF** (the v1
   format; XLSX/CSV reuse the existing renderers where they make sense, e.g. statements), and return the
   bytes + MIME + filename. **Idempotent and side-effect-free on the source** — rendering never mutates
   the source document, never posts GL, never moves stock.
3. **A generated-document record + downloads endpoint** — every render is logged to a `generated_documents`
   row (who, when, which type, which source, the resulting `DOC-####` number, size, a content hash) and
   exposed via a single **`GET /api/documents/{uid}/download`** (and a **render-on-demand**
   `POST /api/documents/render` returning the bytes inline). The record is the audit + re-download anchor.
   **v1 does NOT persist the PDF bytes** — it persists the *record* and **re-renders on download** (the
   source is the system of record; see OQ-DOC-03). A `content_hash` lets a caller detect that a re-render
   would differ from a prior render (e.g. the source was voided).
4. **Per-company branding** — a `document_branding` profile (company display name, address block, tax id,
   logo reference, footer terms, contact line) used in every document header/footer. Seeded from the
   `companies` row (name, legal_name, tax_id) on create; admin-editable. **One default profile per
   company** in v1 (the per-document-type override is deferred).
5. **The six v1 document types**, each a templated PDF:
   - **INVOICE** — a sales-invoice (`INV-####`) with header (branding + customer + invoice meta), the
     line table (product, qty, unit price, discount, VAT, line total), the tax summary by band, and the
     totals block (net / VAT / gross) — the VAT-invoice printout the Sales module already computes.
   - **AR_STATEMENT** — a customer statement (open items + ageing) — **reuses the existing statement
     render pipeline**, lifted to the platform service.
   - **PURCHASE_ORDER** — a `PO-####` with supplier + line table + totals, for sending to the supplier.
   - **GOODS_RECEIPT** — a `GRN`/goods-receipt note (received lines + quantities).
   - **DELIVERY_NOTE** — a `DEL-####` delivery document (delivered lines + quantities; **no prices** —
     it is a shipment document, matching ADR-0021 D-7 where the delivery line carries no pricing).
   - **CREDIT_NOTE** — an AR credit note (`CN-####`) reversing revenue/VAT, with reason.
6. **A generation event over the outbox** — on a successful render (or on a "please generate and notify"
   request), the module **publishes `DOCUMENT.GENERATED`** to the outbox in the render transaction, so the
   future **Notifications** enabler (X.2) can consume it and email the document. v1 ships the **producer**;
   no consumer exists yet (designed to the Notifications contract — §6 assumption).
7. **RBAC + tenant scope + audit** — `DOCUMENT.*` permissions; `assertCanActIn` on **every** read/render
   path (the source document's company must equal the caller's active company); every render audited.
8. **The shared platform render pipeline** — the existing `StatementRenderModel` / renderers move/extend
   into the documents module's `render` package (or stay in reporting and are imported — ADR call, D-1),
   so there is **one** PDF code path. Reporting's existing export endpoints are **unchanged** (no
   regression to shipped behaviour).
9. **Angular** — a small "Documents" surface: a **download/print button** added conceptually to each
   source screen (invoice, PO, statement, delivery, credit note) that hits the download endpoint, plus a
   **document-branding admin screen** (edit the company branding profile) and a **template-registry admin
   screen** (toggle types active/inactive). (Wiring the buttons into each source screen is the source
   module's web work; this module owns the branding + registry admin screens and the download integration.)

### 2.2 Deferred (explicitly out of v1 — none precluded; all additive later)

- **Emailing / SMS dispatch** of documents — Notifications (X.2). v1 emits the event only.
- **Persisting the rendered bytes** (a document store / file backend) — v1 re-renders on download
  (OQ-DOC-03). The `file_attachments` enabler (§3.12) is the home for stored blobs later.
- **Payslip rendering (PAYSLIP)** — explicitly later (HR/Payroll, area 3.7, not built). The registry
  enum reserves it; v1 does not render it.
- **User-editable templates / WYSIWYG template designer / HTML templates** — v1 templates are
  **code-defined renderers** (the OpenPDF builder approach already shipped), not data-driven. A template
  markup language is a large deferred item.
- **Per-document-type branding override** (different letterhead per type) — v1 is one branding profile
  per company.
- **Batch / bulk document generation** (e.g. "PDF all statements for month-end") — Bulk export (X.4).
- **Scheduled / recurring document generation** — needs the scheduler + Notifications.
- **Multi-language documents (Swahili)** — i18n (§3.13), deferred.
- **Digital signatures / PDF/A archival / fiscalised receipts (TRA EFD/VFD)** — deferred (the EFD item
  is tracked under Sales OQ-SALES-03).
- **XLSX/CSV for the transactional documents** (invoice/PO as a spreadsheet) — v1 renders transactional
  documents to **PDF only**; statements keep their existing PDF/XLSX/CSV via the reporting pipeline.
- **Watermarks / DRAFT overlays, custom page sizes, multi-currency document presentation** — deferred.

---

## 3. Actors

| Actor | Interest |
|---|---|
| **Sales clerk / cashier** | Download/print the invoice, delivery note, credit note for a customer. |
| **Buyer / procurement officer** | Download/print the purchase order to send to a supplier; the GRN. |
| **Accounts-receivable clerk** | Download/print a customer statement and credit notes. |
| **Warehouse operator** | Print the delivery note / goods-receipt note for the physical hand-off. |
| **Company admin** | Maintain the company branding profile (logo, address, footer terms) and toggle which document types are active. |
| **System (outbox dispatcher)** | (Future) consume `DOCUMENT.GENERATED` to email the document via Notifications. |

---

## 4. Functional requirements (FR-DOC-NN)

- **FR-DOC-01** — The system SHALL maintain a **template registry** per company listing the renderable
  document types, each with a renderer key, a branding profile reference, and an active/inactive status.
- **FR-DOC-02** — On company creation the registry SHALL be **seeded** with the v1 document types
  (INVOICE, AR_STATEMENT, PURCHASE_ORDER, GOODS_RECEIPT, DELIVERY_NOTE, CREDIT_NOTE), all active.
- **FR-DOC-03** — An admin with `DOCUMENT.TEMPLATE.MANAGE` SHALL be able to toggle a document type
  active/inactive. An **inactive** type SHALL be refused for rendering with a clear error.
- **FR-DOC-04** — The system SHALL maintain a **branding profile** per company (display name, legal name,
  address block, tax id, logo reference, contact line, footer terms), seeded from the `companies` row.
- **FR-DOC-05** — An admin with `DOCUMENT.BRANDING.MANAGE` SHALL be able to edit the company branding
  profile. Edits SHALL be audited.
- **FR-DOC-06** — The system SHALL render a **PDF** for a given `(documentType, sourceUid)`, loading the
  source via the owning module's **read service (DTO)**, applying the company branding, and returning the
  bytes, MIME type, and a suggested filename.
- **FR-DOC-07** — Rendering SHALL be **read-only on the source**: it SHALL NOT mutate the source document,
  post to GL, move stock, or change any financial state.
- **FR-DOC-08** — Each render SHALL create a **`generated_documents`** record (the document `uid`, a
  `DOC-####` number, the type, the source type + source uid, the rendering user, timestamp, byte size, a
  content hash, the branding-profile uid used).
- **FR-DOC-09** — The system SHALL expose **`GET /api/documents/{uid}/download`** returning the rendered
  bytes as an attachment (re-rendering from the source — OQ-DOC-03), and **`POST /api/documents/render`**
  to render a `(documentType, sourceUid)` on demand and return the record + a download link (or the bytes).
- **FR-DOC-10** — The system SHALL render the **six v1 document types** with the layouts in §2.1.5 (header
  branding block, type-specific body, totals/tax where applicable; the delivery note carries **no prices**).
- **FR-DOC-11** — The **AR_STATEMENT** type SHALL **reuse** the existing statement render pipeline
  (`StatementRenderModel` + renderers) — no parallel statement renderer.
- **FR-DOC-12** — On a successful render the system SHALL **publish `DOCUMENT.GENERATED`** to the
  transactional outbox **in the render transaction**, carrying the document uid, type, source ref,
  company/branch, and the rendering actor (the payload Notifications will consume to email it).
- **FR-DOC-13** — A render SHALL be refused (clear error, no record) if: the source document does not
  exist or is not in the caller's active company (`assertCanActIn`); the document type is inactive
  (FR-DOC-03); or the source is in a state the type forbids (e.g. rendering an INVOICE that is still DRAFT
  — only a FINALISED/VOID invoice has a number to print — OQ-DOC-02).
- **FR-DOC-14** — Every render and every branding/registry change SHALL be **audited** (actor, action,
  target uid, detail) via the platform audit service.
- **FR-DOC-15** — All list/read/render endpoints SHALL be **company-scoped** (`assertCanActIn` on the
  resolved source company on every read path) and RBAC-gated by `DOCUMENT.*` permissions.
- **FR-DOC-16** — The `generated_documents` log SHALL be **listable/filterable** (by type, source, date,
  actor) with pagination, gated by `DOCUMENT.VIEW`, for re-download and audit.
- **FR-DOC-17** — The document number `DOC-####` SHALL be allocated via the shared `code_sequence`
  mechanism, per company, concurrency-safe.

---

## 5. Business rules (BR-DOC-NN)

- **BR-DOC-01** — Documents are a **projection, not a source of truth**: the rendered content is derived
  entirely from the live source document at render time. The system of record is the source module; the
  documents module stores **no financial figures of its own** (only a content hash + metadata).
- **BR-DOC-02** — Rendering **never changes financial state**. No GL post, no stock move, no source
  mutation. (This is the headline invariant — a print must never have a side effect on the books.)
- **BR-DOC-03** — A document number `DOC-####` is the **render-event identifier**, distinct from the
  source's own business number (`INV-####`, `PO-####`, …). The source's number is printed **on** the
  document; `DOC-####` identifies the **render record**. Re-rendering the same source produces a **new**
  `generated_documents` record with a new `DOC-####` (each render is an event).
- **BR-DOC-04** — A document type that is **inactive** in the registry cannot be rendered (FR-DOC-03).
- **BR-DOC-05** — A source document must be in a **renderable state** for its type: an invoice must be
  FINALISED or VOID (it has a number); a DRAFT invoice/quotation has no number and is refused (OQ-DOC-02).
  A VOID document MAY be rendered (stamped/labelled VOID) for audit, at the architect's display discretion.
- **BR-DOC-06** — Branding is **per company** and applies to **all** document types in v1; a missing
  branding profile falls back to the `companies` row fields (name, legal_name, tax_id) so a render never
  fails for lack of branding.
- **BR-DOC-07** — The **delivery note carries no monetary values** (it is a shipment document — ADR-0021
  D-7); the **purchase order** and **invoice** carry full pricing/totals; the **GRN** carries received
  quantities (cost is internal — not printed for the supplier copy in v1).
- **BR-DOC-08** — `generated_documents` is an **append-only log** (no update, no delete of a render
  record); a re-render appends. (Soft-delete / archival of the log is a deferred retention concern.)
- **BR-DOC-09** — Money on documents is formatted in the document/source **currency** (base currency TZS
  in v1) using the platform Money display convention; the documents module does no arithmetic on figures —
  it formats the source's already-computed amounts (no rounding, no re-derivation — NFR-DOC-02).
- **BR-DOC-10** — The `DOCUMENT.GENERATED` event is emitted **only on a committed successful render**, in
  the same transaction as the `generated_documents` insert (transactional outbox — never a best-effort
  in-memory publish), so a crash cannot lose the event nor emit it for a render that rolled back.

---

## 6. Key flows

### 6.1 Happy path — render & download an invoice PDF

1. Sales clerk on the invoice screen clicks **Download PDF** → `POST /api/documents/render`
   `{ documentType: "INVOICE", sourceUid: "<invoiceUid>" }` (or a convenience
   `GET /api/documents/render?type=INVOICE&source=<uid>`).
2. The render service: resolves the source company via the Sales **read service DTO**;
   `assertCanActIn(principal, sourceCompanyId)`; checks the INVOICE type is **active** in the registry;
   checks the invoice is FINALISED/VOID (renderable); loads the invoice DTO (header, lines, tax summary,
   totals) + the company branding profile.
3. Builds the INVOICE render model; renders to PDF (OpenPDF, via the shared pipeline).
4. Allocates `DOC-####`; writes a `generated_documents` record (size, content hash, branding uid);
   **publishes `DOCUMENT.GENERATED`** to the outbox in the same TX; audits the render; commits.
5. Returns the record + a download link **or** streams the bytes (`Content-Disposition: attachment`).
6. (Future) the outbox dispatcher hands `DOCUMENT.GENERATED` to the Notifications consumer, which emails
   the customer the PDF.

### 6.2 Happy path — re-download a previously generated document

1. User opens the document log (or clicks a stored link) → `GET /api/documents/{uid}/download`.
2. The service loads the `generated_documents` record; `assertCanActIn`; **re-renders from the live
   source** (OQ-DOC-03) using the recorded type + source ref; streams the bytes.
3. If the re-render's content hash differs from the recorded hash (the source changed since — e.g. an
   invoice was voided), the download still succeeds (it reflects current truth) and the new hash is noted
   (a diagnostic; the architect decides whether to append a fresh record — D-decision).

### 6.3 Admin — edit company branding

1. Admin opens the branding screen → `GET /api/documents/branding` (the company profile).
2. Edits logo reference / address / footer terms → `PUT /api/documents/branding`
   (`@perm.has('DOCUMENT.BRANDING.MANAGE')`, `assertCanActIn`, optimistic `@Version`, audited).
3. Subsequent renders pick up the new branding (no re-render of past documents — they re-render live).

### 6.4 Unhappy paths

- **Source not found / wrong company** → `assertCanActIn` / not-found → 403/404, **no record written**.
- **DRAFT invoice (no number)** → refused with "document not in a renderable state" (BR-DOC-05).
- **Inactive document type** → refused "document type is not enabled" (BR-DOC-04).
- **Missing branding profile** → falls back to `companies` fields; render succeeds (BR-DOC-06).
- **Renderer failure (malformed source / null amount)** → the render TX rolls back; **no record, no
  event** (BR-DOC-10); the error is logged (observability) and surfaced as a 5xx with a safe message.
- **Permission denied** → `@perm.has('DOCUMENT.RENDER'...)` fails → 403, no record.

---

## 7. Non-functional requirements (NFR-DOC-NN)

- **NFR-DOC-01 — Single pipeline.** There is exactly **one** PDF rendering code path (the shared
  render pipeline); adding a document type is a registry entry + a renderer, not a new controller stack.
  No second copy of the OpenPDF plumbing (reuse/extend the ADR-0018 D-9 renderers).
- **NFR-DOC-02 — Zero financial side effects, zero re-derivation.** Rendering reads DTOs and formats
  already-computed amounts; it performs **no** money arithmetic, posts no GL, moves no stock, mutates no
  source. A documents-module defect can at worst produce a wrong-looking PDF, never wrong books.
- **NFR-DOC-03 — Tenant isolation.** Every read/render path resolves the source company and calls
  `assertCanActIn`; a caller can never render another company's document. The `generated_documents` log
  is company-scoped.
- **NFR-DOC-04 — Performance.** A single-document render is interactive (< ~1.5s for a typical invoice);
  rendering is in-request (synchronous) in v1 — bulk/async generation is deferred. The download endpoint
  streams bytes; large statements bound their row count (the existing reporting export cap pattern).
- **NFR-DOC-05 — Module boundaries (no cycle).** The documents module reads source modules **via their
  read services / DTOs only** (never their entities or repositories), and produces the outbox event;
  source modules do **not** depend on documents (no module → documents edge). `ModuleBoundaryTest`
  (controller↛repository, DTO-only cross-module) holds.
- **NFR-DOC-06 — Idempotent & crash-safe event.** `DOCUMENT.GENERATED` rides the transactional outbox;
  the (future) consumer is idempotent under `IdempotencyGuard`. A render either fully commits (record +
  event) or fully rolls back (BR-DOC-10).
- **NFR-DOC-07 — Auditability.** Every render and branding/registry change writes an audit row; the
  `generated_documents` log gives a complete "who printed what, when" trail.
- **NFR-DOC-08 — Additive & non-regressive.** The migration is additive on the frozen migrations; the
  existing Reporting export endpoints and renderers keep working unchanged.
- **NFR-DOC-09 — Library hygiene.** PDF rendering uses the already-vendored **OpenPDF (LGPL/MPL)** — not
  iText/AGPL (ADR-0018 D-9). No new heavyweight PDF dependency for v1.

---

## 8. Open questions (OQ-DOC-NN — owner-style defaults adopted; load-bearing ones flagged)

- **OQ-DOC-01 (load-bearing) — module placement: a new `com.erp.modules.documents` module vs extending
  `com.erp.modules.reporting`.** The existing PDF pipeline lives in `reporting.export`. Documents is a
  broader cross-cutting concern (renders transactional docs from Sales/Purchases/AR, not just GL reports).
  **Recommended default:** a **new `documents` module** that **reuses/lifts** the shared render primitives
  (a clean home, no reporting↔sales coupling forced through reporting). The ADR decides whether the
  shared `StatementRenderModel`/renderers move to a `platform.common` / `documents.render` package or stay
  in reporting and are imported. *Resolve in ADR-0023 D-1.*
- **OQ-DOC-02 (load-bearing) — renderable-state policy.** Which source states are printable? Default:
  an invoice must be **FINALISED or VOID** (DRAFT has no number); a VOID document renders **stamped VOID**;
  a quotation prints in SENT/ACCEPTED. The architect fixes the per-type renderable-state table.
- **OQ-DOC-03 (load-bearing) — persist bytes vs re-render on download.** Default: **re-render on download**
  (the source is the system of record; no blob store needed; always reflects current truth). The cost is
  that a re-download of a since-changed source shows new content — acceptable and arguably correct.
  Persisting bytes is the right call once `file_attachments` (X.1 companion) lands and immutability of the
  *as-sent* copy matters (e.g. legal/audit). *Default re-render; flag the immutability trade-off.*
- **OQ-DOC-04 — branding storage: a `document_branding` table vs adding columns to `companies`.** Default:
  a **separate `document_branding` table** (company-scoped, 1:1), so branding evolves (logo, footer terms,
  per-type override later) without touching the frozen `companies` table. Logo is stored as a **reference
  (URL / path / small base64)** in v1 — not a blob column (file storage is deferred). *Resolve in D-2.*
- **OQ-DOC-05 — `DOCUMENT.GENERATED` payload shape & whether render always emits.** Default: emit on
  **every** committed render (so any render is notifiable), payload carries `{ documentUid, documentType,
  sourceType, sourceUid, companyId, branchId, generatedBy, generatedAt }` — **no bytes** (the consumer
  re-renders or fetches via the download endpoint). Owner may prefer a "notify" flag so only explicitly
  requested renders emit; default is emit-always (cheap, the consumer filters). *Confirm with Notifications.*
- **OQ-DOC-06 — `DOC-####` numbering vs no number.** Default: allocate `DOC-####` per render via
  `code_sequence` (a stable handle for the log + re-download). Owner may decide the render record needs no
  human number (uid suffices); default keeps a number for support/audit legibility.
- **OQ-DOC-07 — which permissions, and whether RENDER is per-source-module.** Default: a small
  `DOCUMENT.*` set (`DOCUMENT.RENDER`, `DOCUMENT.VIEW`, `DOCUMENT.BRANDING.MANAGE`,
  `DOCUMENT.TEMPLATE.MANAGE`) — **not** a permission per source type (the source screen already gates who
  sees the invoice/PO; if you can view it you can print it, plus the coarse `DOCUMENT.RENDER`). Owner may
  want render gated by the source module's own VIEW perm instead/as-well; default is the coarse
  `DOCUMENT.RENDER` + the source screen's existing gate. *Flag — this is a policy choice.*
- **OQ-DOC-08 — payslip & other future types.** Reserved in the type enum, **not** rendered in v1 (HR not
  built). No action; documented so the enum is forward-shaped.

---

## 9. Acceptance criteria (the v1 done-bar)

- [ ] A FINALISED invoice renders a branded PDF with correct header, line table, tax summary, and totals,
      downloadable via the download endpoint; a `generated_documents` record + a `DOC-####` exist; an
      audit row + a `DOCUMENT.GENERATED` outbox event are written in the same transaction.
- [ ] A PO, GRN, delivery note (no prices), credit note, and customer statement each render to PDF; the
      statement **reuses** the existing render pipeline (no parallel renderer).
- [ ] Rendering another company's document is refused (`assertCanActIn`); a DRAFT invoice is refused
      (renderable-state); an inactive type is refused.
- [ ] Editing the company branding profile changes subsequent renders; the change is audited.
- [ ] Reporting's existing export endpoints/renderers are unchanged (no regression).
- [ ] No render mutates any source, posts GL, or moves stock (NFR-DOC-02 verified by test).
- [ ] `ModuleBoundaryTest` passes (no documents↔source-module cycle; DTO-only reads); the migration is
      additive on the frozen migrations.
