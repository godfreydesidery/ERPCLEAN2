# POS Client PRD — Part 4: Architecture, Dependencies, Risks & Roadmap

> **Part of:** the Product Requirements Document for the **external POS client** — a separate
> desktop / mobile / kiosk application that a developer will build to run a retail till. The client
> **owns no database and no business logic**; it operates entirely by calling the ERP's REST API
> (`/api/v1/...`). This part covers PRD **sections 8–12**: the technical architecture the client must
> adopt, the backend changes the client depends on for later phases, the operational risks, the
> release plan, and the success-metrics / traceability roll-up.
>
> **Ground truth.** Every claim below is traced to the verified API reference
> ([`../../00`–`12`](../README.md)), the use-case catalogue ([`../../use-cases/`](../use-cases/README.md)),
> and — for every gap — [§12 Known Limitations](../12-known-limitations.md). The **hard rule of this
> PRD** is honoured throughout: a requirement is **v1** only if the API supports it **today**; anything
> that depends on a current API gap is either a clearly-stated v1 **workaround** or a **later-phase**
> requirement that names the **backend change** it depends on (see §9).
>
> **MoSCoW key:** **M** = Must (v1), **S** = Should (v1 if capacity allows), **C** = Could (nice-to-have),
> **W** = Won't-for-v1 (explicitly out of the first release; usually backend-blocked). Requirements
> carry IDs `FR-8x`/`FR-9x` (functional) and `NFR-8x` (non-functional); backend asks carry `BR-x`.

---

## 8. Integration & Technical Architecture

The POS client is a **stateless API consumer** of a stateless JWT resource server. It holds **no
authoritative state**: the ERP allocates invoice numbers, resolves prices, posts the ledger, and owns
the session lifecycle. The client's job is to (a) authenticate and keep a token fresh, (b) present a
fast selling UI built from cached reference data, (c) submit a well-formed sale and treat the returned
invoice as the source of truth, and (d) survive connectivity faults without double-posting. This
section specifies the architecture that makes that possible on the API **as it exists today**.

### 8.1 Architectural principles

| ID | MoSCoW | Requirement | Trace |
|----|--------|-------------|-------|
| FR-80 | **M** | The client MUST treat the ERP REST API as the single source of truth for invoice numbers, line prices, VAT, totals, and session/drawer state. It MUST NOT compute or persist any value the API returns as if it were authoritative — e.g. it MUST render the receipt from the returned `SalesInvoiceDto`, not from its own cart maths. | API §09; UC-C7 |
| FR-81 | **M** | The client MUST be designed as **online-first**: a successful sale requires a live authenticated request, a server-resolved OPEN session, and synchronous invoice-number allocation. There is **no** offline/batch sale-ingest endpoint, so the client MUST NOT present an offline sale as "completed" until the ERP returns a `201`. | API §11.4.3; §12 |
| FR-82 | **M** | The client MUST branch all control flow on **HTTP status codes**, never on the text of `errors[]` (which is user-display-only and may reword between releases). | API §11.1, §11.2 |
| FR-83 | **M** | The client MUST send `Content-Type: application/json` and `Authorization: Bearer <accessToken>` on every protected call, and a per-logical-operation `X-Request-Id` for log correlation. | API §00, §01.3, §11.1 |
| NFR-80 | **S** | The selling screen SHOULD remain responsive while reference data refreshes in the background (catalogue/price/customer/agent reads are advisory caches, not on the critical path of ringing a sale). | API §03, §04, §05; UC-C1 |

### 8.2 Suggested client architecture (layered, offline-aware)

The reference architecture is a thin layered client. Nothing here is mandated by the API — it is the
shape that satisfies the requirements above with the least risk.

```
+-----------------------------------------------------------------------+
|  Presentation / Till UI    (cart, tender pad, receipt, X/Z screens)   |
+-----------------------------------------------------------------------+
|  Application services                                                  |
|   - SaleOrchestrator   (build PosSaleRequest, own retry-safety)       |
|   - SessionManager     (open / x-read / payout / close / reconcile)  |
|   - CatalogService     (search products/units/prices — advisory)     |
|   - ReceiptService     (render + reprint from stored SalesInvoiceDto) |
+-----------------------------------------------------------------------+
|  API client / HTTP layer                                              |
|   - AuthInterceptor    (attach bearer; refresh on 401; single-flight) |
|   - StatusRouter       (map HTTP status -> retry/terminal/ambiguous)  |
|   - X-Request-Id stamper                                              |
+-----------------------------------------------------------------------+
|  Local store (the client's ONLY persistence)                         |
|   - secure token store (access + rotating refresh)                    |
|   - reference cache (products, units, prices, customers, agents)      |
|   - outbound sale journal (clientSaleRef, payload, status, result)    |
|   - finalised receipts (SalesInvoiceDto keyed by clientSaleRef)       |
|   - config (base URL, till uid, scope)                                |
+-----------------------------------------------------------------------+
                       |  HTTPS  application/json
                       v
              ERP REST API  /api/v1  (stateless JWT resource server)
```

| ID | MoSCoW | Requirement | Trace |
|----|--------|-------------|-------|
| FR-84 | **M** | The HTTP layer MUST expose a single **StatusRouter** that classifies every response into one of: **success** (`2xx`), **terminal-4xx** (`400/403/404/415/422`), **retryable-conflict** (`409` whose `errors[0]` is exactly `"This record was modified by another transaction. Please reload and try again."`), **auth-expired** (`401`), or **ambiguous** (`500` / network failure / no clean response). Each class has exactly one prescribed action (§8.5, §10). | API §11.2.1, §11.5 |
| FR-85 | **M** | The **SaleOrchestrator** MUST own retry-safety for `POST /pos/sales`. When it sends the `Idempotency-Key` header (BR-1, now shipped — see note in §8.5), a safe retry replays to the original invoice; when the header is **omitted**, the server provides no dedup and the orchestrator MUST run the reconcile-before-resend procedure (§8.5) instead of blindly retrying. | API §11.4.1–§11.4.2; [§12 #1](../12-known-limitations.md#1-sale-idempotency-on-sale-creation--closed-idempotency-key-header) |
| FR-86 | **S** | Reference reads (`GET /products`, `/units`, `/products/uid/{uid}/prices`, `/products/barcode-lookup`, `/customers`, `/agents`, `/currencies`, `/stock/on-hand`) SHOULD go through a **CatalogService** cache so the till keeps selling during brief catalogue-endpoint blips. Cached prices are a **preview only**; the `201` invoice is authoritative. | API §03–§06; [§12 #4](../12-known-limitations.md#4-server-authoritative-pricing--by-design-not-a-limitation); UC-C6 |

### 8.3 Authentication & token-refresh flow

The API is a stateless JWT resource server. Access tokens last **15 minutes**; refresh tokens last
**7 days** and are **single-use / rotated** — presenting a consumed refresh token revokes the entire
chain (fail-closed). The client must manage this lifecycle without disrupting an in-progress sale.

| ID | MoSCoW | Requirement | Trace |
|----|--------|-------------|-------|
| FR-87 | **M** | On cashier sign-in the client MUST call `POST /api/v1/auth/login`, store both tokens securely, and read `accessTokenExpiresAt` as **epoch seconds** (not millis). | API §01.2 |
| FR-88 | **M** | The client MUST proactively refresh via `POST /api/v1/auth/refresh` **before** the access token expires (e.g. at ~80% of TTL), and MUST **replace its stored refresh token with the new one** on every refresh (rotation). It MUST never use the same refresh token from two client instances. | API §01.4 |
| FR-89 | **M** | On any `401`, the client MUST attempt **one** refresh-then-retry of the original request; if the refresh itself returns `401` (`"Refresh token already used…"`, expired, or user disabled), the client MUST force re-login and surface the reason to the cashier. The access token MUST NOT be reused after logout (it is not server-revoked; it simply expires within 15 min). | API §01.3–§01.5, §11.5 |
| FR-90 | **M** | Refresh MUST be **single-flight**: concurrent `401`s during a token rollover MUST share one in-flight refresh, never fire parallel refreshes (which would burn the rotating token and revoke the chain). | API §01.4 |
| FR-91 | **M** | Immediately after login the client MUST call `GET /api/v1/auth/me` to read effective `permissions[]` and active scope, and gate UI actions on the `POS.*` codes (or `isRoot`). It MUST treat `hasBranch=false` as "not provisioned to transact" and block selling. (The `403` gate is the real enforcement; `me` only drives UI.) | API §01.6; UC-A1, UC-A2 |
| FR-92 | **S** | The client SHOULD call `GET /api/v1/auth/my-branches` and, for a multi-branch operator, allow per-request scope override via the `X-Branch-Uid` header rather than re-login. | API §01.7–§01.8; UC-E3 |
| NFR-81 | **M** | Tokens MUST be held in a secure platform store (OS keychain / encrypted store), never in plaintext logs or world-readable config. The refresh token is a bearer credential for 7 days. | API §01.3; §10.5 |
| NFR-82 | **S** | In dev/test (`dev-in-memory` signing) the client SHOULD expect that a backend restart invalidates all tokens and SHOULD recover by re-login on the resulting `401`. | API §01.3 |

**Refresh sequence (single-flight):**

```
request -> 401 ?
  no  -> proceed
  yes -> is a refresh already in flight?
           yes -> await it, then retry original once
           no  -> POST /auth/refresh (with stored refresh token)
                    200 -> store NEW access+refresh; retry original once
                    401 -> wipe tokens; route cashier to re-login (show reason)
```

### 8.4 The synchronous-invoice vs. asynchronous-posting model

This is the single most important behavioural fact the client must internalise. A `POST /pos/sales`
that returns `201` has produced a **FINALISED, fully-paid (CASH) invoice synchronously** — but the
**stock issue, GL journal, and AR posting run asynchronously** via a transactional-outbox poller
(~1 s, retried on failure). The ledger is **not** posted when the `201` returns.

| ID | MoSCoW | Requirement | Trace |
|----|--------|-------------|-------|
| FR-93 | **M** | The client MUST treat the `201` `SalesInvoiceDto` (uid, `invoiceNumber`, line snapshots, VAT, `grossTotalAmount`) as the **authoritative, complete** basis for the receipt and the drawer entry — it MUST NOT wait for, or assume, stock/GL/AR posting at response time. | API §09; README §6–§7 |
| FR-94 | **M** | The client MUST NOT read back stock-on-hand and expect it to reflect a just-rung sale immediately; on-hand reads are advisory and lag the sale by the poll interval. | API §06, §11.4.1; UC-C6 |
| FR-95 | **S** | Where the client surfaces post-sale ledger state (e.g. a back-office reconcile screen), it SHOULD reconcile against the **eventual** postings (via `GET /api/v1/sales-invoices?companyId={id}`), not against the HTTP response. | API §11.4.2; UC-E2 |

### 8.5 Reconcile-before-resend (the client's idempotency substitute)

> **NOTE (2026-06-20): BR-1 is now DELIVERED (`f08fb08`, ADR-0042).** The API now accepts an optional
> `Idempotency-Key` header on `POST /pos/sales` (reserve-before-process, `pos_sale_idempotency`
> UNIQUE per company, Flyway V70); a replay with the same key returns the **original** invoice, and an
> in-flight duplicate returns a **retryable `409`**. Whole-sale reversal/void (BR-2) also shipped (see
> §9). The factual premise of this section — that the API has "no idempotency key and no dedup" and
> "no reversal/void" — is therefore **superseded**: reconcile-before-resend is now the **fallback for
> the header-omitted (legacy) path only**, not the sole option. The original workaround text is
> preserved below; the owner should revisit whether it remains the *primary* path or is demoted to a
> degraded-mode fallback (this touches §11 Phase 2 sequencing — flagged there).

Historically, because the API had **no idempotency key and no dedup** on sale creation, and **no
reversal/void** endpoint to undo an accidental double-post, the client carried the entire burden of
exactly-once-ish behaviour. This is a v1 **workaround** for
[§12 #1](../12-known-limitations.md#1-sale-idempotency-on-sale-creation--closed-idempotency-key-header);
the proper fix is the backend ask **BR-1** (§9) — **now delivered** (see note above).

| ID | MoSCoW | Requirement | Trace |
|----|--------|-------------|-------|
| FR-96 | **M** | Before the first attempt of a logical sale, the client MUST generate and **durably persist** a `clientSaleRef` (a local transaction id) in the outbound sale journal, surviving app restarts, and send it as `X-Request-Id` on **every** attempt of that same sale. (It does not dedupe server-side — it makes a double-post **findable** in ERP logs and in the client journal.) | API §11.4.2 (rule 3) |
| FR-97 | **M** | The client MUST NOT auto-retry `POST /pos/sales` on an **ambiguous** outcome (timeout / dropped connection / `500` / no clean `201` or terminal 4xx) **when no `Idempotency-Key` was sent**. In that header-omitted path it MUST mark the journal entry `UNKNOWN` and run the reconcile procedure (FR-98) before any resend. (With the `Idempotency-Key` header now shipped — BR-1, §8.5 note — a safe retry of the **same key** is replay-safe; the owner should decide whether to make keyed retry the primary path.) | API §11.4.2 (rule 1); [§12 #1](../12-known-limitations.md#1-sale-idempotency-on-sale-creation--closed-idempotency-key-header) |
| FR-98 | **M** | On an ambiguous outcome the client MUST reconcile before resending: (1) confirm the session is still OPEN (`GET /pos/sessions/uid/{uid}`); (2) query `GET /api/v1/sales-invoices?companyId={id}` newest-first and match on amount, line snapshot, and timestamp window. If a matching finalised invoice exists, treat the original as **succeeded** (store it, reprint) and do **not** resend; only resend if absent. | API §11.4.2 (rule 2); UC-E2 |
| FR-99 | **M** | The client MUST auto-retry a `409` **only** when `errors[0]` is exactly the optimistic-lock string; all other `409`s (session not OPEN, under-tender, credit-limit, empty cart, unique/FK) are **terminal** and MUST be surfaced to the cashier, not retried. | API §11.2.1, §11.5 |
| FR-9A | **M** | Receipt printing/reprinting MUST be idempotent client-side: persist the finalised `SalesInvoiceDto` keyed by `clientSaleRef`, and serve reprints from the local store or `GET /api/v1/sales-invoices` — **never** by re-POSTing the sale. | API §11.4.2 (rule 5); UC-C8 |

### 8.6 Local store & offline cache

The client's local store is its **only** persistence and exists for resilience and UX, not as a
shadow ledger.

| ID | MoSCoW | Requirement | Trace |
|----|--------|-------------|-------|
| FR-9B | **M** | The local store MUST persist: the secure token pair; the active config (base URL, till `uid`, company/branch scope); finalised receipts keyed by `clientSaleRef`; and an **outbound sale journal** row per logical sale (`clientSaleRef`, request payload, status ∈ {PENDING, SUCCEEDED, UNKNOWN, FAILED}, and the returned invoice uid/number on success). | API §11.4.2–§11.4.3 |
| FR-9C | **S** | The client SHOULD cache reference data (products, units, prices, customers, agents, enabled currencies) to keep the selling UI usable during brief read-endpoint outages, refreshing opportunistically. Cached prices are **preview-only**. | API §03–§05; [§12 #4](../12-known-limitations.md#4-server-authoritative-pricing--by-design-not-a-limitation); UC-C6 |
| FR-9D | **S** | While the server is unreachable, the client MAY let the cashier **build a cart locally** but MUST clearly mark it "not yet sold"; on reconnect it MUST **replay queued sales one at a time** (serialized, not parallel), applying the reconcile-before-resend rule (FR-98) to each, and MUST stop on the first terminal error for cashier review. | API §11.4.3; UC-C9 (basket-state note) |
| FR-9E | **M** | Before replaying any queued sale, the client MUST re-check `GET /pos/sessions/uid/{uid}`; if the session is no longer OPEN (it may have been closed/reconciled during the outage), the queued sale **cannot** be posted to it and MUST be escalated as an exception (open a new session / supervisor review), never silently dropped. | API §11.4.3 |
| NFR-83 | **C** | The outbound sale journal and receipt store COULD be retained for an audit window (e.g. 90 days) to support double-post investigations and end-of-day reconciliation against the ERP. | API §11.4.2 (rule 3) |

> **Honesty note (W for v1).** A "true offline till" that rings sales with no connectivity and
> guarantees exactly-once posting on reconnect is **Won't-for-v1**: it is fundamentally limited by the
> absence of an offline/batch sale-ingest endpoint. The v1 client is online-first with graceful
> degradation, not an offline POS. See §11 Phase 3.
>
> **NOTE (2026-06-20):** the server idempotency key (BR-1) this note originally also cited as a blocker
> is now **DELIVERED** (`f08fb08`); a durable client-side `Idempotency-Key` makes reconnect-replay
> exactly-once even on the legacy path. The remaining true blocker is the **offline-ingest endpoint**
> (still open — [§12 #6](../12-known-limitations.md#6-client-side-offline-ingest--open)). The owner
> should revisit whether this changes the Phase 3 offline-robustness calculus.

### 8.7 Configuration

| ID | MoSCoW | Requirement | Trace |
|----|--------|-------------|-------|
| FR-9F | **M** | The client MUST externalise configuration: API **base URL** (host + `/api/v1`), and the bound till **`uid`**. It MUST resolve and store the operating **company/branch** scope from login (`activeCompanyUid`/`activeBranchUid`) and the numeric `companyId`/`branchId` (via `GET /companies`, `GET /branches`) needed for till and invoice-list lookups. | API §00, §02; README §3 |
| FR-9G | **M** | The client MUST be configurable with the **walk-in customer id** and a **default agent id**. `customerId` is `@NotNull` on every sale; **`agentId` is now optional** (its `@NotNull` was relaxed in `f08fb08`) and attribution is server-authoritative — so a default agent id is now a convenience, not a validation requirement. (See §9 BR-4 / §10 R-6, now resolved by design.) | API §11.3.1; UC-C5 |
| FR-9H | **S** | The client SHOULD support toggling `X-Request-Id` correlation and (in dev) tolerate `dev-in-memory` token invalidation on restart. It SHOULD allow pointing at the Swagger surface (`/swagger-ui`) only in non-production builds. | API §00; §01.3 |
| NFR-84 | **M** | All API traffic MUST be over **HTTPS**. The client MUST NOT log bearer tokens, refresh tokens, or full request bodies containing them. | API §00; §01.3 |

---

## 9. Dependencies & Backend Asks

The v1 client is built **only** on capabilities the API supports today (§11 Phase 1). Several
high-value retail behaviours are **backend-blocked** — they cannot be delivered by any amount of client
cleverness. This section turns the [§12 Known Limitations](../12-known-limitations.md) into explicit,
prioritised **backend requirements** that the POS client depends on, each tagged with the **client
phase it unblocks**. These are asks **on the ERP team**, not client work.

> **NOTE (2026-06-20): BR-1, BR-2 and BR-3 are now DELIVERED** (`f08fb08`, ADR-0042), and BR-4's
> premise was **resolved by design**. The factual status in the table below has been updated to
> reflect the shipped contract (`Idempotency-Key` header; `POST /pos/sales/uid/{uid}/reverse` with
> `POS.SALE.VOID`; optional `tenders[]` list). However, the **"Unblocks (client phase)"** mapping —
> i.e. *which* client phase each ask gates — is **planning**, not fact: it is now stale because the
> backend asks it was waiting on have landed. **This re-sequencing is the owner's call**; the phase
> mapping is left as originally written and flagged in §11 for re-planning.

| Ask | Priority | Backend change (the §12 gap it closes) | Unblocks (client phase) | Maps to |
|-----|----------|----------------------------------------|-------------------------|---------|
| **BR-1** | **P0 — ✅ DELIVERED (`f08fb08`)** | **Idempotency on `POST /pos/sales`.** ~~Accept~~ **Accepts** an optional `Idempotency-Key` header (reserve-before-process, `pos_sale_idempotency` UNIQUE per company, Flyway V70), persisted unique per company, **returning the original invoice on replay** instead of minting a second; an in-flight duplicate gets a retryable `409`. (Omitting the header ⇒ legacy non-idempotent path.) | **Phase 2** (safe retries; foundation for robust offline in Phase 3) — *phase mapping now stale, see §11 flag* | §12 #1; API §11.4 |
| **BR-2** | **P0 — ✅ DELIVERED (`f08fb08`, whole-sale)** | **POS reversal / refund / void endpoint.** Shipped as `POST /api/v1/pos/sales/uid/{uid}/reverse`, perm `POS.SALE.VOID`, requires an **OPEN** session, reverses **revenue + VAT + cash + stock + COGS atomically** and drops the sale out of the drawer; audited. **Whole-sale only** — partial / line-level refunds remain **deferred** (BR-2a below). Closed-session sales still use the back-office void. | **Phase 2** (in-till refunds, void mis-rung sale) — *phase mapping now stale, see §11 flag* | §12 #2; UC-B9, UC-D1, UC-D2 |
| **BR-2a** | **P2 (Medium) — still open** | **Partial / line-level POS refunds.** Per-line / partial-quantity refunds against a POS invoice (the part of BR-2 that ADR-0042 explicitly **deferred**). | Phase 3 (partial returns) | §12 #5 |
| **BR-3** | **P1 — ✅ DELIVERED (`f08fb08`)** | **Multi / split tender + non-cash.** `PosSaleRequest` ~~carry~~ **carries** an optional `tenders[]` list (`CASH`, `CARD`, `MOBILE_MONEY`, `CHEQUE`), supports split, validates sum ≥ gross; a card/mobile-money/split sale posts real ledger payments. (PCI/card-data handling remains a client-side concern — use an external certified terminal.) | **Phase 3** (card / mobile money / split tender, ledger-accurate change) — *phase mapping now stale, see §11 flag* | §12 #3; UC-B10 |
| **BR-4** | **P2 — RESOLVED BY DESIGN (`f08fb08`)** | **`agentId` handling.** The `@NotNull` on `agentId` (and `unitPrice`) was **relaxed** — both are now optional. `agentId` attribution semantics are server-side by design; the "required but ignored" premise no longer applies. | Phase 2 (accurate agent attribution / commissions) | §12 #4 (agent note); UC-C5 |
| **BR-5** | **P3 (Low) — partly RESOLVED BY DESIGN (`f08fb08`)** | **Manual price override at POS (optional).** The `unitPrice` field is now **accepted but deliberately ignored** — the server re-derives price + VAT (server-authoritative pricing, by design; the `@NotNull` was relaxed so the field is optional). What is **still not a feature** is a **permission-gated manual override** (`POS.SALE.PRICE_OVERRIDE`, audited). Until/unless that ships, negotiated reductions are expressible **only** as `lineDiscountAmount`. | Phase 3 (manual price override only) | §12 #4; UC-C4 |
| **BR-6** | **P3 (Low)** | **Optional draft / hold (park & recall).** A POS draft/hold/park endpoint so a sale can be suspended server-side and recalled. (No catalogued §12 gap — it is simply **absent** from the POS API; the v1 workaround is purely client-side basket state.) | Phase 3 (true suspend/recall) | UC-C9 |
| **BR-7** | **P3 (Low)** | **Mid-shift float top-up / cash-in.** A cash-in counterpart to the payout endpoint (today only cash-**out** payouts exist). | Phase 3 (drawer top-up) | UC-B7 |
| **BR-8** | **P3 (Low)** | **Re-open / amend a closed or reconciled session.** Currently terminal once CLOSED/RECONCILED. | Phase 3 (correct a mis-closed shift) | UC-B8 |

> **Dependency rule honoured.** No v1 requirement in this PRD assumes BR-1…BR-8. Each backend ask is
> the named precondition for the later-phase requirement that needs it (see the per-phase entry/exit
> criteria in §11). The client team MUST NOT begin a phase whose backend dependency is unmet — it would
> force a silent assumption of a capability the API lacks, which this PRD forbids.
>
> **NOTE (2026-06-20):** the **backend preconditions for Phase 2 (BR-1, BR-2) and the primary one for
> Phase 3 (BR-3) are now MET** (`f08fb08`, ADR-0042). Under this rule, those phases are no longer
> gated on a missing capability. **Whether that collapses or re-sequences the phasing is the owner's
> re-planning decision** — flagged in §11; phases are left as written here.

---

## 10. Risks & Mitigations

Risks are scored **Likelihood × Impact** and mapped to the requirement(s) and/or backend ask that
mitigate them. "v1 mitigation" = what the client must do now on today's API; "Full fix" = the backend
change that actually removes the risk.

| ID | Risk | L | I | v1 mitigation (client) | Full fix |
|----|------|---|---|------------------------|----------|
| R-1 | **Offline / ambiguous double-post.** A sale commits server-side but the client never sees the `201` (timeout/crash/radio drop); a blind retry creates a **second finalised invoice** — duplicate stock issue + GL/AR. *(2026-06-20: the "no in-till way to reverse it" clause is **no longer true** — whole-sale `/reverse` ships, BR-2.)* | M | **High** | FR-96…FR-9A: persistent `clientSaleRef`, never auto-retry on ambiguity, **reconcile-before-resend** via `GET /sales-invoices`, idempotent receipts. | **BR-1** (idempotency key) + **BR-2** (reversal to undo any slip-through). |
| | <br>> **NOTE (2026-06-20):** **BR-1 and BR-2 are now DELIVERED** (`f08fb08`). With the `Idempotency-Key` header a blind retry no longer double-posts (replay returns the original invoice), and any slip-through is reversible in-till via `/reverse`. The **L/I rating and the reliance on the reconcile-only mitigation should be revisited by the owner** — the structural fix now exists. | | | | |
| R-2 | **Connectivity loss mid-shift.** No offline sale-ingest endpoint; the till cannot sell while disconnected. | M | Med | FR-81/FR-9D: online-first; build cart locally marked "not sold"; serialized replay with reconcile on reconnect; FR-9E re-checks the session is still OPEN. | Server offline-ingest (out of scope; superset of BR-1). |
| R-3 | **~~Cash-only limitation.~~** *(2026-06-20: factually superseded.)* The original premise — "no card / mobile money / split tender" — is **no longer true**: BR-3 ships an optional `tenders[]` list and a card/mobile-money/split sale posts real ledger payments. | **H** | Med | Restrict v1 to attended **cash** sales; compute change locally for the receipt only; state the limitation to stakeholders. | **BR-3** (multi/split tender) — **DELIVERED**. |
| | <br>> **NOTE (2026-06-20):** **BR-3 is DELIVERED** (`f08fb08`). Multi/split + non-cash tender posts to the ledger; tender mix is auditable. This risk is **largely mitigated** — the **H likelihood and the "restrict v1 to cash" mitigation should be revisited by the owner**. PCI/card-data handling (use an external certified terminal) remains a valid client-side concern. | | | | |
| R-4 | **~~No in-till refund / void.~~** *(2026-06-20: factually superseded for open-session sales.)* The original premise — "a cashier cannot correct a wrong sale or process a return at the till" — is **no longer true** for an **OPEN session**: `POST /pos/sales/uid/{uid}/reverse` (perm `POS.SALE.VOID`) reverses revenue + VAT + cash + stock + COGS atomically and drops the sale out of the drawer. **Still true** for **closed-session** sales (back-office void) and for **partial / line-level** refunds (deferred, BR-2a). | M | **High** | UC-D1/UC-D2 workaround: record a `REFUND` payout **and** mandate a back-office credit-note / correcting journal + stock adjustment; put the original receipt # in `reason`. Dedupe the payout too (it has no idempotency either). | **BR-2** (POS reversal endpoint) — **DELIVERED** (whole-sale, open session). |
| | <br>> **NOTE (2026-06-20):** **BR-2 (whole-sale reversal) is DELIVERED** (`f08fb08`). The factual claim above is corrected, but the **Impact rating is left at High** — this NOTE **flags it for the owner to re-rate**, since the gap is now closed for open-session whole-sale reversals while partial/line-level refunds and closed-session corrections remain back-office-only. **The rating is NOT silently downgraded.** | | | | |
| R-5 | **Price / stock drift.** Cached catalogue prices or on-hand can be stale; `unitPrice` sent by the client is **accepted but ignored** (server-authoritative pricing — now **by design**, and the `@NotNull` relaxed so the field is optional); a line **fails** if the product has no price row for the company. | M | Med | FR-86/FR-93: treat cached price/stock as **preview**; use the returned `SalesInvoiceDto` as truth; handle the no-price `400`/`IllegalArgumentException` as a terminal, fixable error. | Pricing integrity needs no fix (the ignore is **intentional by design**, `f08fb08`); only a **permission-gated manual override** remains a future ask (BR-5). |
| R-6 | **Agent (mis-)attribution.** *(2026-06-20: premise updated.)* `agentId` is **no longer required** — the `@NotNull` was relaxed (`f08fb08`), so it need not be sent merely to pass validation; agent attribution is server-side by design. | **H** | Low | FR-9G + R-7: treat returned `agentId/agentName` as truth; do **not** rely on POS for client-asserted agent attribution. | **BR-4** — **resolved by design** (`agentId` now optional; attribution is server-authoritative). |
| | <br>> **NOTE (2026-06-20):** BR-4's "required but ignored" premise was **resolved by design** (`f08fb08`). The **H likelihood should be revisited by the owner** now that the field is optional and the validation-only send is unnecessary. | | | | |
| R-7 | **Treating all `409`s as retryable.** Credit-limit, under-tender, empty-cart, and session-not-OPEN are `409`s; blind retry would hammer the server on a genuinely rejected sale. | M | Med | FR-99: auto-retry a `409` **only** when `errors[0]` is exactly the optimistic-lock string; everything else is terminal. | n/a (correct client behaviour). |
| R-8 | **Security: token theft / leakage.** A 7-day rotating refresh token is a powerful bearer credential; reuse from two clients revokes the chain (fail-closed) and locks the cashier out. | L | **High** | NFR-81/NFR-84/FR-90: secure store, HTTPS-only, no token logging, single-flight refresh, one refresh token per client instance; disabling a user takes effect immediately (re-checked every request). | n/a (platform behaviour; operate within it). |
| R-9 | **Stale session on long outage.** A queued sale's session may be closed/reconciled by the time the client reconnects. | M | Med | FR-9E: re-check session status before replay; escalate if not OPEN. | n/a. |
| R-10 | **Misreading the async ledger.** Assuming stock/GL/AR are posted at `201` time leads to wrong reconciliation. | M | Med | FR-93…FR-95: receipt + drawer off the `201`; reconcile back-office expectations against the eventual postings, not the response. | n/a (documented behaviour). |

---

## 11. Release Plan & Phasing

Three phases, gated by backend availability. **Phase 1 ships entirely on today's API** and is the
committed MVP; Phases 2 and 3 **must not start** until their named backend asks (§9) are delivered and
verified, because they each depend on a capability the API currently lacks.

> **NOTE (2026-06-20): the backend asks that gated Phases 2 and 3 have now LANDED** (`f08fb08`,
> ADR-0042): **BR-1** (idempotency), **BR-2** (whole-sale reversal/void), and **BR-3** (multi/split
> tender) are all delivered; BR-4 was resolved by design. The phase **goals and entry/exit criteria
> below were written assuming these were still pending**, so the **sequencing, the "must not start"
> gates, and the "cash-only / no-refund" Phase-1 scope boundary now warrant owner re-planning** — e.g.
> Phase 1's cash-only restriction and no-in-till-refund stance are no longer forced by the API, and
> Phase 2/3 entry-gates that wait on BR-1/2/3 are already satisfied. **The phases below are left
> exactly as authored**; the owner should re-plan deliberately rather than have them silently
> re-ordered here. Genuinely-still-pending asks: partial/line-level refunds (BR-2a, deferred) and
> offline ingest (BR-6/§12 #6).

### Phase 1 — MVP: attended, cash-only, online-first (today's API)

> **NOTE (2026-06-20):** the **"cash-only" and "no in-till refund" boundaries of this phase are no
> longer forced by the API** — BR-1/BR-2/BR-3 shipped (`f08fb08`), so multi-tender and whole-sale
> reversal are now available. Whether the MVP *still chooses* a cash-only / no-refund pilot is now a
> **deliberate scope decision for the owner** (it may remain sensible for a controlled pilot, e.g. for
> PCI/operational reasons), not an API constraint. The phase text is left unchanged for the owner to
> re-scope (see the §11 flag and OQ-7).

**Goal.** A controlled, attended, **cash-only** till that a cashier can run end-to-end against the
current API: login → resolve scope → pick/create till → open session → ring cash sales → print →
X-read → close → reconcile, with disciplined retry-safety.

**In scope (Must):** the full happy path on supported capabilities —
provisioning awareness (FR-91), auth + single-flight refresh (FR-87…FR-90), session lifecycle
(open / payout / x-read / close / reconcile, UC-B1…B6), catalogue/price/customer/agent reads as
preview (FR-86, FR-9C), **cash** sale with line discounts (UC-C1…C4), receipt + reprint
(UC-C7/C8, FR-9A), the StatusRouter + reconcile-before-resend (FR-84…FR-99), local store (FR-9B),
config (FR-9F…FR-9H), and the cash-drawer `REFUND` **payout workaround** with the mandatory
back-office-correction caveat clearly surfaced (R-4).

**Explicitly out (Won't-for-v1, backend-blocked):** in-till refund/void (BR-2), card / mobile money /
split tender (BR-3), server-guaranteed exactly-once posting (BR-1), forwarded agent attribution (BR-4),
manual price override (BR-5), true suspend/recall (BR-6), float top-up (BR-7), session re-open (BR-8),
and a true offline till.

> **NOTE (2026-06-20):** of this "backend-blocked" list, **BR-1 (exactly-once posting), BR-2 (in-till
> whole-sale refund/void) and BR-3 (card/mobile/split tender) are no longer backend-blocked** —
> they shipped in `f08fb08` (BR-4 resolved by design). Keeping them out of the MVP is now a **scope
> choice**, not an API limitation. **Still genuinely backend-blocked:** partial/line-level refunds
> (BR-2a, deferred), and a true offline till (offline-ingest endpoint, §12 #6). Owner to re-scope —
> left unchanged here.

| Gate | Criteria |
|------|----------|
| **Entry** | API reachable at a known base URL; a cashier user provisioned per UC-A1 (`hasBranch=true`, holds the cashier `POS.*` set); a walk-in customer id and default agent id configured. |
| **Exit** | End-to-end cash loop demonstrated; **no path auto-retries `POST /pos/sales` on ambiguity** (verified by fault injection); reconcile-before-resend proven to detect a committed-but-unacknowledged sale and avoid the duplicate (R-1); receipts rendered from the `201` and reprinted without re-POST; refresh rotation + single-flight verified; the cash-only and no-refund limitations documented for stakeholders; X-read/close/reconcile totals match drawer counts in a pilot shift. |

### Phase 2 — Idempotent sales + in-till refund/void (needs BR-1, BR-2)

> **NOTE (2026-06-20): this phase's backend entry-gate (BR-1 + BR-2) is now SATISFIED** (`f08fb08`,
> ADR-0042) — idempotency key and whole-sale `/reverse` (perm `POS.SALE.VOID`) are deployed. The
> phase is no longer blocked. **Whether it now folds into the MVP or stays a distinct phase is the
> owner's re-planning call** — gate text left as written. (Note: partial/line-level refunds, BR-2a,
> remain deferred and would stay out of this phase.)

**Goal.** Remove the two highest-severity data-integrity gaps: make sale posting safely retryable and
let a cashier reverse a mistake at the till.

| Gate | Criteria |
|------|----------|
| **Entry (backend)** | **BR-1** (idempotency key/`clientSaleRef` returning the original `201` on replay) **and** **BR-2** (atomic POS reversal/refund/void with a `POS.SALE.REFUND`/`POS.SALE.VOID` permission) are deployed and verified. *(BR-4 — forward `agentId` — SHOULD land here too for accurate attribution.)* |
| **Scope** | Client sends the idempotency key on every sale attempt and **may now safely auto-retry** ambiguous outcomes against the keyed endpoint (replacing the reconcile-only workaround as the primary path, while keeping reconcile as a fallback). In-till refund/void UI gated on the new permission (UC-B9, UC-D1, UC-D2 become POS-resolvable). If BR-4 ships, agent attribution is taken from the returned invoice without caveat. |
| **Exit** | A replayed sale provably yields **one** invoice (no duplicate stock/GL/AR); a refund/void reverses stock + GL + AR atomically and is audited; the back-office-correction workaround (R-4) is retired for in-scope cases; permission gating verified via a cashier who lacks `POS.SALE.REFUND` (gets `403`). |

### Phase 3 — Multi-tender / card + offline-robust (needs BR-3; benefits BR-5…BR-8)

> **NOTE (2026-06-20): the primary backend entry-gate for this phase (BR-3 multi/split tender) is now
> SATISFIED** (`f08fb08`); BR-1 (which this phase leverages for offline robustness) is also delivered.
> The remaining "offline-robust" goal still depends on an **offline-ingest endpoint** (§12 #6, still
> open). **Re-sequencing the tender vs. offline work is the owner's call** — gate text left as written.

**Goal.** Real payment coverage and resilient operation.

| Gate | Criteria |
|------|----------|
| **Entry (backend)** | **BR-3** (multi/split tender + non-cash, with persisted tender breakdown and ledger-recorded change) deployed and verified. *(BR-5 manual price override, BR-6 draft/hold, BR-7 float top-up, BR-8 session re-open are independent enhancers, each pulled in as delivered.)* |
| **Scope** | Card / mobile money / cheque and **split tender** at the till; over-tender change recorded on the ledger; tender breakdown on the receipt. With BR-1 from Phase 2, hardened offline operation: durable local queue, serialized keyed replay, exactly-once on reconnect. Optional: manual price override (BR-5), true park & recall (BR-6), float top-up (BR-7). |
| **Exit** | A split-tender sale (e.g. part cash + part card) posts with the correct tender mix and change auditable on the invoice; offline queue replays exactly-once across an induced outage (no duplicates, leveraging BR-1); any pulled-in enhancer (BR-5…BR-8) verified end-to-end and permission-gated. |

---

## 12. Success Metrics, Open Questions & Traceability

### 12.1 Success metrics (roll-up)

| Metric | Target | Phase | Why it matters |
|--------|--------|-------|----------------|
| **Duplicate-post rate** (second invoice for one logical sale) | **0** confirmed duplicates per pilot shift in Phase 1; structurally **0** in Phase 2+ | 1→2 | Directly measures the R-1 mitigation / BR-1 fix. |
| **Ambiguous-outcome auto-retries** of `POST /pos/sales` | **0** (must always reconcile first) | 1 | The non-negotiable safety invariant (FR-85, FR-97). |
| **Sale round-trip latency** (request → `201`) | p95 ≤ 2 s on a healthy link | 1 | Cashier throughput. |
| **Receipt availability** (printable from local store without a server call) | 100% of finalised sales | 1 | FR-9A; reprints never re-POST. |
| **Refresh-induced sign-outs** (chain revoked by token reuse) | **0** | 1 | Validates single-flight refresh (FR-90). |
| **Drawer variance at reconcile** | within tolerance per finance policy | 1 | Validates session/payout handling. |
| **Refunds resolved in-till** (no back-office correction needed) | 0% in Phase 1 (by design) → ≥ target% in Phase 2 | 2 | Measures the BR-2 payoff. |
| **Non-cash / split-tender share** accepted at the till | 0% in Phase 1 (by design) → tracked in Phase 3 | 3 | Measures the BR-3 payoff. |
| **Offline queue exactly-once** (duplicates after replay) | **0** | 3 | Validates offline robustness on BR-1. |

### 12.2 Open questions

> **⚠ Highest-visibility open question — Fiscalisation (OQ-8).** The pilot's context is **Tanzania**
> (TZS, **18% VAT**, `INV-####`). Tanzanian retail typically **must** integrate a TRA **VFD/EFD** and
> issue a **fiscal receipt with a verification code** — and this PRD currently treats the server
> `SalesInvoiceDto` as the receipt of record, which would **not** satisfy a fiscal mandate. Whether v1
> must fiscalise is an explicit **scope decision** that should be resolved early: it is **likely a
> backend / integration dependency (not client-only)** and, if required, becomes a new backend ask
> alongside BR-1…BR-8. The mandated **legal receipt fields** are a related open question (**OQ-9**).
> See OQ-8/OQ-9 in the table below.

> **NOTE (2026-06-20): OQ-1, OQ-2, OQ-3 and OQ-5 are now ANSWERED by the shipped contract** (`f08fb08`,
> ADR-0042): BR-1 is an **`Idempotency-Key` header** (OQ-1); BR-2 is the **dedicated
> `POST /pos/sales/uid/{uid}/reverse`** with perm `POS.SALE.VOID` (OQ-2); BR-3 ships an optional
> **`tenders[]` list** (OQ-3 — the exact change-modelling detail to be read off the shipped DTO);
> `unitPrice` is **accepted-but-ignored, not dropped** (OQ-5 — only a permission-gated *override*
> remains a future question). **OQ-7** (cash-drawer-payout-plus-back-office as the interim returns
> policy) is now moot for **open-session whole-sale** returns since `/reverse` exists. The rows are
> left for the owner's record; OQ-8/OQ-9 (fiscalisation) and OQ-10 remain genuinely open.

| # | Question | Owner | Blocks |
|---|----------|-------|--------|
| OQ-1 | Will **BR-1** be an `Idempotency-Key` **header** or a `clientSaleRef` **field**? The client's journal design (FR-96) is compatible with either, but the wire shape must be fixed before Phase 2. *(2026-06-20: ANSWERED — shipped as the `Idempotency-Key` header, `f08fb08`.)* | ERP team | Phase 2 |
| OQ-2 | Will **BR-2** be a dedicated `/pos/sales/uid/{uid}/reverse` or a credit-note path accepting a POS invoice as origin? Affects the refund UI flow and permission model. *(2026-06-20: ANSWERED — shipped as `POST /pos/sales/uid/{uid}/reverse`, perm `POS.SALE.VOID`, `f08fb08`.)* | ERP team | Phase 2 |
| OQ-3 | For **BR-3**, will change on over-tender be modelled as a negative `CASH` tender or a separate change field? Affects receipt rendering. *(2026-06-20: BR-3 shipped with a `tenders[]` list, `f08fb08`; read the exact change modelling off the shipped DTO.)* | ERP / Finance | Phase 3 |
| OQ-4 | Until **BR-4**, is POS agent attribution acceptable to **finance/commissions** as "always the logged-in cashier"? If not, Phase 1 must restrict who logs in per till. *(2026-06-20: BR-4 resolved by design — `agentId` is now optional and attribution is server-authoritative, `f08fb08`; the "until BR-4" framing no longer applies.)* | Finance | Phase 1 caveat |
| OQ-5 | **BR-5**: honour `unitPrice` as a gated override, or drop the field? Determines whether v1 documents "discounts only via `lineDiscountAmount`". *(2026-06-20: PARTLY ANSWERED — the field is **kept but ignored** (server re-derives price), so v1 still documents "discounts only via `lineDiscountAmount`"; the open part is only whether to add a permission-gated manual override.)* | ERP team | Phase 1 doc / Phase 3 |
| OQ-6 | What is the production JWT signing mode at the pilot site (`file` vs `dev-in-memory`)? Determines whether token-on-restart invalidation (NFR-82) is a real operational concern. | Ops | Phase 1 |
| OQ-7 | Is a cash-drawer `REFUND` payout + **mandatory** back-office correction an acceptable interim returns policy for the pilot, or must returns be withheld from the till entirely until BR-2? *(2026-06-20: BR-2 whole-sale `/reverse` shipped, `f08fb08`; for open-session whole-sale returns the interim payout workaround is no longer the only option — owner to decide the pilot returns policy. Partial/line-level returns remain back-office.)* | Finance | Phase 1 |
| **OQ-8** | **Fiscalisation (high-visibility scope decision).** The deployment context is **Tanzania** (TZS, **18% VAT**, `INV-####` series). TZ retail typically **must** integrate a TRA **VFD/EFD** (Virtual/Electronic Fiscal Device) and print a **fiscal receipt + verification code**. This PRD currently treats the server `SalesInvoiceDto` as the **receipt of record**, which is **not sufficient** if fiscalisation is mandated. Decide whether v1 must fiscalise. **Likely a backend / integration dependency, not client-only** — TRA integration (signing, fiscal counters, device/online registration) generally belongs server-side or in a certified middleware, so it would join the BR-x backend-ask register if required. | ERP / Finance / Ops | Phase 1 (scope) |
| **OQ-9** | **Receipt legal content.** The mandated **fiscal/legal receipt fields** — seller **TIN/VRN**, business name & address, fiscal device id, **verification code/URL**, and any TRA-required formatting — are **unspecified** in this PRD and the API reference. Specify them (related to and dependent on **OQ-8**). | ERP / Finance | Phase 1 (scope) |
| **OQ-10** | **`GET /api/v1/agents` documentation gap.** The client's agent picker (FR-9G, UC-C5) reads `GET /api/v1/agents`, but this endpoint has **no first-class section** in the 00–12 API reference (it appears only in UC-C5 / the quickstart). Recommend the ERP/docs team **add a dedicated reference section** for it (request/response shape, permission `AGENT.VIEW`, paging). | ERP / docs team | Phase 1 (doc) |

### 12.3 Traceability summary (FR → UC → API / §12)

| Requirement(s) | Use case(s) | API / gap reference |
|----------------|-------------|---------------------|
| FR-80, FR-93–FR-95 | UC-C7, UC-E2 | API §09 (sync invoice / async posting); README §6–§7 |
| FR-81, FR-9D, FR-9E | UC-C9 | API §11.4.3 (offline constraints) |
| FR-82, FR-84, FR-99 | UC-E1 | API §11.1, §11.2, §11.2.1, §11.5 |
| FR-83, FR-9F | UC-A2 | API §00, §02; README §2–§3 |
| FR-87–FR-90, NFR-81, NFR-82 | UC-C10 | API §01.2–§01.5, §11.5 |
| FR-91, FR-92 | UC-A1, UC-A2, UC-E3 | API §01.6–§01.8 |
| FR-85, FR-96–FR-9A | UC-E2, UC-C8 | API §11.4; **[§12 #1](../12-known-limitations.md#1-sale-idempotency-on-sale-creation--closed-idempotency-key-header) → BR-1 (DELIVERED)** |
| FR-86, FR-9C, FR-94, R-5 | UC-C6 | API §03–§06; **[§12 #4](../12-known-limitations.md#4-server-authoritative-pricing--by-design-not-a-limitation) (pricing by design) → BR-5 (override only)** |
| FR-9B, NFR-83 | UC-E2 | API §11.4.2–§11.4.3 |
| FR-9G, R-6 | UC-C5 | API §11.3.1; **[§12 #4](../12-known-limitations.md#4-server-authoritative-pricing--by-design-not-a-limitation) (agent note) → BR-4 (by design)** |
| Cash-only restriction (Phase 1), R-3 | UC-B10 | **[§12 #3](../12-known-limitations.md#3-multi-tender--non-cash-payments--closed-tenders-list) → BR-3 (DELIVERED)** |
| Refund payout workaround, R-4 | UC-B9, UC-D1, UC-D2 | API §10.3; **[§12 #2](../12-known-limitations.md#2-whole-sale-pos-reversal--refund--void--closed-reverse-endpoint) → BR-2 (DELIVERED, whole-sale)** |
| Line-discount-only pricing, R-5 | UC-C4 | API §04; **[§12 #4](../12-known-limitations.md#4-server-authoritative-pricing--by-design-not-a-limitation) → BR-5 (override only)** |
| Session lifecycle (open/x-read/close/reconcile) | UC-B1–B6 | API §08 |
| Multi-branch scope override | UC-E3 | API §01.8 (`X-Branch-Uid`) |

> **Closure check.** Every §12 item is represented. **NOTE (2026-06-20):** §12 was rewritten with
> `f08fb08` (ADR-0042) and the first three gaps are now **CLOSED** —
> **[#1](../12-known-limitations.md#1-sale-idempotency-on-sale-creation--closed-idempotency-key-header)**
> (idempotency) → **BR-1 DELIVERED** (`Idempotency-Key` header), the FR-96…FR-9A reconcile path is now
> the header-omitted fallback;
> **[#2](../12-known-limitations.md#2-whole-sale-pos-reversal--refund--void--closed-reverse-endpoint)**
> (reversal/refund) → **BR-2 DELIVERED** (whole-sale `/reverse`); the R-4 payout workaround is retired
> for open-session whole-sale reversals (partial/line-level still deferred);
> **[#3](../12-known-limitations.md#3-multi-tender--non-cash-payments--closed-tenders-list)**
> (multi-tender) → **BR-3 DELIVERED** (`tenders[]` list), Phase 1's cash-only is now a scope choice not
> a constraint;
> **[#4](../12-known-limitations.md#4-server-authoritative-pricing--by-design-not-a-limitation)**
> (server-authoritative pricing / `unitPrice` ignored) is **by design, not a gap** (a permission-gated
> manual override remains a future ask, BR-5). Genuinely still open:
> **[#5](../12-known-limitations.md#5-partial--line-level-pos-refunds--deferred)** partial/line-level
> refunds (BR-2a) and **[#6](../12-known-limitations.md#6-client-side-offline-ingest--open)** offline
> ingest. The non-§12 enhancers — no draft/hold (BR-6), no float top-up (BR-7), no session re-open
> (BR-8) — are each carried as an explicit later-phase ask. **No requirement in this PRD assumes a
> capability the API lacks** — and several it once treated as gaps are now shipped (owner to re-plan
> phasing, see §11 flag).
