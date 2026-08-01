# POS Client PRD — Part 3: Non-Functional Requirements & UX

> **What this part is.** This is part 3 of the Product Requirements Document for the
> **external Point-of-Sale (POS) client** — a standalone desktop and/or mobile/kiosk
> application a developer will build to run a retail till. The client **owns no database
> and no business logic**: it operates entirely by calling this ERP's REST API. Parts 1–2
> cover scope, personas, and the functional requirements; this part covers the
> **non-functional requirements** (§6) and the **UX / key-screen inventory** (§7).
>
> **Ground truth & honesty rule.** Every requirement here is anchored to the verified API
> reference (`../00-overview-and-conventions.md` … `../12-known-limitations.md`), the
> use-case catalogue (`../use-cases/`), and especially the **known API gaps** in
> [`../12-known-limitations.md`](../12-known-limitations.md). Where the POS needs a
> capability the API does not yet provide, the requirement is written either as a clearly
> labelled **v1 workaround** or as a **later-phase requirement with the named backend
> dependency** — never as a silent assumption. The four backend gaps that constrain this
> part are, in shorthand:
>
> - **GAP-1 — sale idempotency: CLOSED (f08fb08, 2026-06-20).** `POST /pos/sales` now accepts
>   an optional `Idempotency-Key` header (reserve-before-process; replay returns the original
>   invoice; an in-flight duplicate returns a retryable `409`). Omitting the key is the legacy
>   path (no dedup), so the reconcile-before-resend flow below is now only the **no-key
>   fallback**, not the sole option. See §12 #1.
> - **GAP-2 — whole-sale POS reversal / refund / void: CLOSED (f08fb08, 2026-06-20).**
>   `POST /pos/sales/uid/{uid}/reverse` (perm `POS.SALE.VOID`, OPEN session) reverses
>   revenue + VAT + cash + stock + COGS and drops the sale out of the drawer. **Still
>   deferred:** partial / line-level refunds (closed-session sales use the back-office void).
>   See §12 #2.
> - **GAP-3 — multi-tender: CLOSED (f08fb08, 2026-06-20).** `POST /pos/sales` accepts an
>   optional `tenders[]` list (CASH / CARD / MOBILE_MONEY / CHEQUE, split, sum ≥ gross); a
>   card / mobile-money / split sale posts real ledger payments. See §12 #3.
> - **GAP-4 — `unitPrice` resolved by design.** `unitPrice` / `agentId` `@NotNull` is relaxed
>   (both now optional); `unitPrice` is accepted but ignored — the server re-derives price + VAT.
>   A manual price override is still not a feature; express reductions via `lineDiscountAmount`.
>   See §12 #4.
> - Plus two non-§12 realities used below: **`agentId` is accepted but not forwarded** (the
>   invoice's agent defaults to the logged-in user — UC-C5), and **no draft / hold / park**
>   endpoint exists (UC-C9).
>
> **MoSCoW.** Each requirement is tagged **MUST**, **SHOULD**, **COULD**, or
> **WON'T (v1)**. "MUST" = required for the controlled, attended, cash-only v1 pilot that
> the current API supports. "WON'T (v1)" items name the backend change they wait on.
>
> > **NOTE (2026-06-20):** GAP-1/GAP-2/GAP-3 are now CLOSED (f08fb08). The "cash-only v1
> > pilot" framing was gated on those gaps — multi-tender, whole-sale reversal, and sale
> > idempotency now ship. This pilot scope/sequencing should be revisited by the owner.
>
> **Acceptance criteria** are written to be atomic and testable. Cross-references use
> `UC-xx` for use cases and `§NN` for API reference sections.

---

## 6. Non-Functional Requirements

This section defines the quality attributes the POS client must meet. Functional behaviour
(what the client does) lives in part 2; this section governs **how well** it must do it.
Requirements are grouped by attribute and carry stable IDs `NFR-<n>`.

### 6.1 Performance

The POS is interactive and latency-sensitive: a cashier rings dozens of items per minute,
and any per-item stall is felt directly. The client cannot make the network or the server
faster, so these requirements govern **client-side responsiveness, caching, and perceived
latency** around calls whose round-trip it cannot control.

| ID | MoSCoW | Requirement | Acceptance criteria |
|----|--------|-------------|---------------------|
| **NFR-1** | MUST | **Ring/scan-to-line latency.** Adding a line to the in-memory cart (after a barcode scan resolves locally or after the cashier picks a cached product) must feel instant. | With the catalog cached locally (NFR-4), the time from a scan/keypress to the line appearing in the on-screen cart is **≤ 150 ms at the 95th percentile** on target hardware, measured with no network call on the hot path. Barcode resolution that *requires* a server call (`GET /products/barcode-lookup`, §03) is excluded from this budget but covered by NFR-3. |
| **NFR-2** | MUST | **Sale-submit feedback.** Submitting a sale (`POST /pos/sales`, §09) blocks finalisation on the server; the UI must give immediate, non-blocking feedback and never appear frozen. | On submit, a busy/progress state appears **within 100 ms**; the cashier cannot double-submit the same cart (the pay/confirm control is disabled until a terminal outcome — 201 or a clean 4xx — is received). See NFR-9 / NFR-10 for the ambiguous-outcome handling that this state hands off to. |
| **NFR-3** | SHOULD | **Server-call budget for lookups.** Catalog search (`GET /products`, §03), barcode lookup, and stock-availability checks (§06) must show progress and degrade gracefully under slow networks. | Any single foreground lookup shows a spinner if it exceeds **300 ms**, and is cancellable/abandonable by the cashier without locking the till. A lookup timeout surfaces a retry affordance, never a crash. |
| **NFR-4** | MUST | **Catalog pre-load & cache.** The client must pre-load and cache the sellable catalog and unit map so ringing does not depend on a per-line product fetch. | Following the catalog-load flow in §03, on shift start the client pages `GET /units?companyId=…` and `GET /products?companyId=…`, keeps only `sellable == true && status == "ACTIVE"`, resolves each `baseUnitUid` → numeric `unitId`, and caches name/code/`vatStatus`/price for search. After load, ringing a cached item triggers **zero** product/unit GETs. A cold catalog of **5,000 sellable products loads in ≤ 30 s** on target hardware. |
| **NFR-5** | SHOULD | **Catalog refresh without blocking the till.** The cache must be refreshable on demand and on a schedule without interrupting an in-progress sale. | A manual "refresh catalog" action and a background refresh (e.g. on each shift open) re-page the product/unit lists; a refresh in progress never blocks adding lines to the current cart, and never discards an uncommitted cart. |
| **NFR-6** | COULD | **Price-preview caching.** Displayed prices (list price, tier/customer/promotion previews from §04) may be cached to avoid per-line pricing calls. | When shown, previews are clearly labelled as *previews*; the receipt always uses the server-computed totals from the 201 `SalesInvoiceDto`, never the cached preview (consistent with GAP-4 / §04 — server is authoritative). |
| **NFR-39** | MUST | **Multi-till concurrency & sizing assumptions.** Several tills may sell against the **same branch** concurrently; on-hand reads are point-in-time and not reserved (§06, FR-STK-1/2), so concurrent tills can **oversell** the same stock. The client must operate correctly under this concurrency, and the PRD must state the scale envelope it is designed for. | (a) The client makes **no assumption that its advisory on-hand is exclusive** — it never hard-blocks on a stale on-hand and tolerates the **on-hand oversell race** (the server does not reserve stock at ring time; the async stock issue may go negative). It surfaces oversell only as a soft warning (FR-STK-2) and relies on the server outcome as truth. (b) The build documents **sizing assumptions**: target number of concurrent **tills per branch**, concurrent **cashiers**, and **catalogue size** (e.g. ~5,000 sellable products per NFR-4), so caching, paging, and refresh strategy can be validated against them. Concurrent same-record edits surface as the retryable optimistic-lock `409` (FR-OFF-5). |

### 6.2 Reliability & Resilience

This group is safety-critical for retry handling. **GAP-1 is now CLOSED (f08fb08): the sale
path accepts an optional `Idempotency-Key` header that makes a replay safe server-side.** When
the key is **omitted** (legacy path), a lost response on a sale that actually committed still
cannot be safely retried by replaying the bytes, so the client must own retry safety end to end
(§11 §4.2) for that fallback. With the key sent, a replay returns the original invoice and an
in-flight duplicate returns a retryable `409`.

> **NOTE (2026-06-20):** GAP-1 is now CLOSED (f08fb08). The NFR-8/9/10/11 client-owned
> reconcile-before-resend design below was shaped to *compensate* for the absent server
> idempotency; with `Idempotency-Key` available it is now the **no-key fallback** rather than
> the only safe path. The owner should revisit whether the POS adopts the key as the primary
> retry-safety mechanism.

| ID | MoSCoW | Requirement | Acceptance criteria |
|----|--------|-------------|---------------------|
| **NFR-7** | MUST | **Basket persistence across restart, crash, and token refresh.** An uncommitted cart must survive app restart, OS/app crash, and a mid-sale token refresh without data loss. | The current cart (lines, quantities, discounts, customer/agent selection, chosen currency, and the local client-transaction-id from NFR-8) is durably persisted to local storage on every mutation. After a force-kill or device reboot mid-sale, relaunching restores the exact cart. A 401 → refresh (UC-C10, NFR-13) never clears the cart. |
| **NFR-8** | MUST | **Durable local client-transaction-id per logical sale.** Before the first `POST /pos/sales` attempt, the client generates and persists a local id that is reused as `X-Request-Id` on every retry of the *same* logical sale and survives restarts. | A logical sale has exactly one persisted client-transaction-id; it is sent as `X-Request-Id` on attempt 1 and every subsequent attempt of that same cart; a genuinely new sale uses a fresh id (§11 §4.2 rule 3). Note (updated 2026-06-20): on the **legacy (no-key) path**, `X-Request-Id` is logging-only and does **not** dedupe server-side — it only makes a double-post findable in the ERP logs. To get true server-side dedup, the client sends the same value as the optional `Idempotency-Key` header (GAP-1 CLOSED, f08fb08), which reserves-before-process and replays the original invoice. |
| **NFR-9** | MUST | **No blind auto-retry on an ambiguous sale outcome.** On timeout, dropped connection, `500`, or any non-clean outcome, the client must NOT resend the same sale bytes automatically. | Given an ambiguous outcome on `POST /pos/sales`, the client performs **zero** automatic resends. It transitions the sale to a "needs reconcile" state and invokes the reconcile-before-resend flow (NFR-10). A 409 is auto-retried **only** when `errors[0]` is exactly the optimistic-lock string `"This record was modified by another transaction. Please reload and try again."` (§11 §2.1); all other 409/4xx are treated as terminal and surfaced to the cashier. |
| **NFR-10** | MUST | **Reconcile-before-resend.** Before re-sending a possibly-committed sale, the client must check whether the sale already exists and only resend if it is absent. | On ambiguity, the client (a) confirms the session is still OPEN via `GET /pos/sessions/uid/{uid}` (§08), and (b) queries `GET /sales-invoices?companyId={id}` newest-first and matches on amount + line snapshot + the client-transaction-id / timestamp window (§11 §4.2 rule 2). If a matching finalised invoice exists, the client treats the original as **succeeded**, reprints from that invoice, and does **not** resend. Only when no match is found may the cashier choose to resend. |
| **NFR-11** | MUST | **Crash recovery to a known state.** After any crash, on relaunch the client must reconcile its persisted state with the server before allowing new sales. | On relaunch with a persisted "in-flight" or "needs reconcile" sale, the client runs NFR-10 before enabling a new sale, and re-checks the active session status; if the session is no longer OPEN it blocks ringing and surfaces an "open a session" path (UC-B1). No new sale can be rung while a prior in-flight sale is unresolved. |
| **NFR-12** | SHOULD | **Offline cart capture (no offline posting).** Because there is **no offline/batch ingest endpoint** (§11 §4.3), the client may capture carts while offline but MUST NOT claim a sale is final until the server confirms it. | While offline, the client allows building/holding carts locally and clearly labels them "not yet sold". On reconnect it **replays them one at a time, serialized**, applying NFR-10 to each, and stops on the first terminal error for cashier review (§11 §4.3). The client never prints a *fiscal/final* receipt for a cart that has not received a 201. Acknowledged dependency: true offline posting is a **WON'T (v1)** capability requiring a backend offline-ingest endpoint (GAP, §11 §4.3). |
| **NFR-13** | MUST | **Transparent token refresh during a shift.** Access-token expiry mid-shift must not interrupt a sale or force a re-login while a valid refresh token exists. | When a protected call returns `401` (or pre-emptively before the 15-min access-token TTL, §01), the client refreshes via `POST /auth/refresh`, **replaces the stored refresh token with the rotated one** (single-use rotation, §01 §4), and retries the original call once (UC-C10). The cart is preserved throughout (NFR-7). If refresh fails (expired/reused token), the client routes to re-login without losing the persisted cart. |
| **NFR-14** | SHOULD | **Session-clock awareness.** The client must not attempt to post sales to a session that is no longer OPEN. | Before posting (and before replaying a held cart), the client treats a `409 "This POS session is not OPEN."` as terminal for that session, surfaces it, and offers opening a new session (UC-B1, UC-E2); it never loops resends against a non-OPEN session. |

> **Honesty note (Reliability).** On the **legacy (no-key) path**, no amount of client logic
> fully closes the "server-committed but client-didn't-learn" window; NFR-8/9/10 mitigate it.
> That backend change has now **landed**: guaranteed exactly-once posting via the optional
> `Idempotency-Key` header + dedup store on the sale path is **CLOSED (f08fb08, §12 #1)** — the
> client closes the window by sending the key. Likewise, **at-till whole-sale reversal / refund /
> void is now available** (`POST /pos/sales/uid/{uid}/reverse`, GAP-2 CLOSED, §12 #2) for
> OPEN-session sales; **partial / line-level refunds** and **true offline / batch sale posting**
> remain out of v1 scope (§12 #5, §12 #6, §11 §4.3).

### 6.3 Security

The API is a stateless JWT resource server (§01). Tokens are bearer credentials and the POS
runs on shared, physically exposed devices, so token handling and device lock are the
primary risks.

| ID | MoSCoW | Requirement | Acceptance criteria |
|----|--------|-------------|---------------------|
| **NFR-15** | MUST | **Secure token storage on the device.** Access and refresh tokens must be stored using the platform's secure credential store, never in plaintext. | Tokens live in the OS keystore/keychain (or equivalent encrypted store), not in plaintext files, logs, or app preferences. The single-use refresh token is overwritten in place on each rotation (§01 §4) and is never written to logs or telemetry. |
| **NFR-16** | MUST | **TLS-only transport.** All API traffic must use HTTPS; plaintext HTTP is rejected. | The client connects only over TLS; any non-HTTPS base URL is refused at configuration time. Certificate validation is enabled (no "accept all certificates"); certificate-pinning is a **SHOULD** (NFR-21). |
| **NFR-17** | MUST | **Auto-lock / idle lock.** An unattended till must auto-lock to prevent unauthorised ringing under the cashier's token. | After a configurable idle timeout (default ≤ 5 minutes), the client locks to a screen requiring re-authentication (PIN/biometric quick-unlock backed by a held valid token, or full re-login). Locking preserves the in-progress cart (NFR-7) and the open session. |
| **NFR-18** | MUST | **Least-privilege POS role.** The client must operate under a cashier role holding only the permissions it needs, and must verify them at login. | The client requests/expects only the cashier set `POS.SALE.CREATE`, `POS.SESSION.OPEN`, `POS.SESSION.CLOSE`, `POS.SESSION.VIEW`, `POS.TILL.VIEW` plus the cross-module reads it uses (`PRODUCT.VIEW`, `UOM.VIEW`, `CURRENCY.VIEW`, `CUSTOMER.VIEW`, `STOCK.VIEW`, `SALES.INVOICE.VIEW`, and `TAXRATE.VIEW`/`PRICELIST.VIEW`/`SALES.PRICING.RULE.VIEW` if it shows price previews) — see §01 §9 and the use-case actors legend. It does **not** require `POS.TILL.MANAGE` or `POS.SESSION.RECONCILE` for a plain cashier. After login it reads `GET /auth/me` and disables any action whose permission is absent (and `isRoot` is false), so a `403` (which never names the missing code, §01 §9) is pre-empted in the UI. |
| **NFR-19** | MUST | **No sensitive data in client logs.** Client logs/telemetry must not contain credentials, tokens, or full PII. | Passwords and tokens are never logged. Customer PII in logs is minimised/redacted. Log entries reference the correlation id (NFR-26), not raw secrets. |
| **NFR-20** | MUST | **Immediate effect of account changes.** The client must handle the server disabling/locking an account at any time. | A `401 "User account is no longer active."` (re-checked every request, §01 §3) immediately locks the client out of transacting and routes to login; it is never auto-retried as the same user. |
| **NFR-21** | SHOULD | **Certificate pinning & secure config.** For production deployments the client should pin the server certificate/CA and protect its configured base URL. | The deployment supports pinning the ERP's certificate/public-key and protects the configured base URL from casual tampering on the device. |
| **NFR-22** | WON'T (v1) | **Card / PCI scope.** Capturing or transmitting payment-card **data** through the POS client. | Out of scope for v1. **Correction (2026-06-20): GAP-3 is now CLOSED (f08fb08).** The sale path now accepts an optional `tenders[]` list (CASH / CARD / MOBILE_MONEY / CHEQUE, split, sum ≥ gross), so a card / mobile-money / split tender **does** post real ledger payments — the earlier claim that "the sale path posts a single exact CASH tender only; no card/mobile-money tender reaches the ledger" is no longer true. The WON'T-v1 boundary here is **PCI scope** (handling raw card data on the device), which stands independently of GAP-3: any card handling must be designed PCI-DSS-aware — ideally via an external, certified payment terminal so card data never enters the POS client's scope (see NFR-31). <br>> NOTE (2026-06-20): GAP-3 (multi-tender) is now CLOSED (f08fb08) — the dependency this WON'T-v1 was originally bound to has lifted. The PCI-scope WON'T-v1 decision is a deliberate scope boundary, not a gap; the owner should revisit whether/how a certified-terminal card flow enters scope now that `tenders[]` is live. |
| **NFR-38** | MUST | **Data retention, purge & at-rest encryption of cached data.** The local store holds customer **PII** (names, phone, TIN), **finalised receipts** (`SalesInvoiceDto`), and a **pending-sale queue**, on a **shared, physically exposed** device — so cached business data, not just tokens, must be protected and bounded. | The client (a) defines and enforces a **retention/purge policy** for cached reference data, finalised receipts, and the outbound sale journal (e.g. purge receipts and resolved queue entries after a bounded audit window; never retain indefinitely on the device); (b) **encrypts at rest** the cached PII and reference data, not only the tokens (NFR-15) — using the platform's encrypted store or an app-managed encryption key in the OS keystore; (c) wipes cached PII / queued sales on logout or device de-provisioning, preserving only what an unresolved in-flight sale needs for reconcile (NFR-10). PII in any export honours NFR-19 (redaction). |

### 6.4 Usability & Accessibility

The POS is operated at speed, often on a touch screen, frequently with a barcode scanner,
sometimes one-handed. Usability defects directly slow checkout.

| ID | MoSCoW | Requirement | Acceptance criteria |
|----|--------|-------------|---------------------|
| **NFR-23** | MUST | **Touch-first targets.** All primary controls must be comfortably operable by touch on the target device. | Primary interactive targets are **≥ 44 × 44 px** (or the platform's recommended minimum) with adequate spacing; the numeric keypad, quantity, and pay controls are reachable without precision pointing. |
| **NFR-24** | MUST | **Keyboard / scanner-first ringing.** A trained cashier must be able to ring a full sale without a mouse, using keyboard and barcode scanner. | Scanning a barcode adds the line and returns focus to the scan field; common actions (search, set quantity, apply line discount, pay, print) have keyboard shortcuts; tab/focus order follows the ringing flow. A scan that resolves via `GET /products/barcode-lookup` (§03) adds the line; a 404 ("Barcode not found …", §03) routes to manual search, not an error dead-end. |
| **NFR-25** | SHOULD | **Accessibility (a11y).** The client should meet WCAG 2.1 AA where applicable to a till context. | Text meets AA contrast; controls have accessible names/roles; the app is operable with screen-reader and keyboard-only navigation for non-time-critical flows (login, session open/close, lookup). Error messages from `errors[]` are rendered as a readable list (they are user-safe, §11 §1) and are announced to assistive tech. |

### 6.5 Localisation & Currency

The ERP is multi-company/multi-branch with per-scope enabled currencies (§04 §5). The client
must respect the server's currency allow-list to avoid the 422 trap.

| ID | MoSCoW | Requirement | Acceptance criteria |
|----|--------|-------------|---------------------|
| **NFR-26** | MUST | **Enabled-currency picker.** The currency selector must be constrained to the scope's enabled currencies and default to the resolved default. | On scope/login the client reads `GET /fx/currencies/enabled?companyUid=…[&branchUid=…]` (§04 §5.1), pre-fills the sale `currency` with `resolvedDefault`, and restricts the picker to `enabled`. Selecting an unenabled currency is impossible from the UI, pre-empting the `422 CurrencyNotEnabledException` on the sale (§04 §5.3, UC-E4). |
| **NFR-27** | MUST | **Decimal & rounding alignment with the server.** Money display must match the server's computed totals; the receipt always uses the server figures. | Any local subtotal preview mirrors the server algorithm (round per line, discount before VAT, VAT per line, `HALF_UP`; default scale 0 for TZS) per §04 §6, but the printed/stored receipt totals are taken verbatim from the 201 `SalesInvoiceDto`. Quantity precision respects each unit's `decimalPlaces`/`fractional` flags (§03 §5). |
| **NFR-28** | SHOULD | **Locale-aware number & date formatting.** Numbers, currency symbols, and dates are formatted per the operator locale; timestamps from the API are UTC ISO-8601 and must be displayed in local time. | Currency uses the currency's symbol/`minorUnits` from the currency master (§04 §5.2); API timestamps (e.g. `finalisedAt`, `openedAt`) are ISO-8601 UTC strings and are rendered in the till's local timezone for the cashier. |
| **NFR-29** | COULD | **UI language localisation.** The client UI should support translation/localisation of its own labels. | The client's static UI strings are externalised for translation; server-returned `errors[]` strings are displayed as-is (they are server-localised user-safe messages and must not be parsed, §11 §1). |

### 6.6 Observability

The client cannot see server internals, but it must produce enough local signal to support
incident triage, and must propagate the correlation id the server expects.

| ID | MoSCoW | Requirement | Acceptance criteria |
|----|--------|-------------|---------------------|
| **NFR-30** | MUST | **`X-Request-Id` correlation on every call.** Every API request carries a correlation id; for sales it is the durable client-transaction-id. | The client sends `X-Request-Id` on every request and records the value the server echoes back (§01 §0, §11 §4.1). For `POST /pos/sales`, the id is the persisted per-logical-sale id from NFR-8 (so a double-post is traceable across the ERP server logs). On the legacy (no-key) path `X-Request-Id` is **not** used for dedup; for true server-side dedup the client also sends it as the optional `Idempotency-Key` header (GAP-1 CLOSED, f08fb08, §12 #1). |
| **NFR-31** | MUST | **Structured client event/error log.** The client maintains a local, structured log of API outcomes sufficient to reconcile a disputed sale. | Each sale attempt logs: timestamp, session uid, client-transaction-id, HTTP status, and (on success) the returned `invoiceNumber`/invoice `uid`; ambiguous outcomes (NFR-9) are flagged. Logs are PII/secret-safe (NFR-19) and exportable for support. |
| **NFR-32** | SHOULD | **Health/connectivity indicator.** The cashier can see whether the till is online and authenticated. | A visible indicator reflects connectivity to the ERP and token validity; loss of connectivity is shown promptly and drives the offline-capture behaviour (NFR-12). `GET /health` (public, §01 §1) may back the connectivity probe. |

### 6.7 Hardware & Peripherals

A retail till integrates physical devices. The API has no peripheral surface, so all device
integration is **client-side**; only the sale data flows to the API.

| ID | MoSCoW | Requirement | Acceptance criteria |
|----|--------|-------------|---------------------|
| **NFR-33** | MUST | **Barcode scanner.** The client must accept input from a barcode scanner (typically HID keyboard-wedge) and resolve scans against the catalog. | A scan populates the scan field and is resolved either from the local catalog cache (NFR-4) or via `GET /products/barcode-lookup?companyId=…&barcode=…` (§03 §4), using the returned `productId` (and `uomId` when present) to add the line; an unknown barcode (404) routes to manual search (NFR-24). |
| **NFR-34** | MUST | **Receipt printer.** The client must print a sale receipt from the finalised invoice. | After a 201, the client renders and prints a receipt from the `SalesInvoiceDto` (number, lines, server-computed net/VAT/gross, currency, timestamp; for a cash tender, change is computed client-side from the optional `tenderedAmount` — *not* stored on the invoice, §12 #3). Multi-tender now ships (GAP-3 CLOSED, f08fb08): when a `tenders[]` mix is submitted, the receipt reflects the tender breakdown the client sent. Receipt rendering is idempotent and reprintable from the locally persisted invoice (UC-C7/UC-C8) without re-POSTing (NFR-7/NFR-8). The client functions if no printer is attached (on-screen / e-receipt fallback). |
| **NFR-35** | SHOULD | **Cash drawer.** The client should trigger the cash drawer on a cash sale and on authorised no-sale opens. | On a completed cash sale (and on a supervisor-authorised drawer open), the client sends the kick signal to a connected drawer; drawer presence is optional and its absence does not block ringing. Drawer activity is the client's responsibility — the API has no drawer endpoint; cash movements that must hit the ledger use session payouts (§08). |
| **NFR-36** | COULD | **Customer-facing display / scale.** The client may drive a secondary customer display and/or integrate a weighing scale for weight-priced items. | If present, the customer display mirrors the running total/line; a scale supplies quantity for `fractional` units (§03 §5). Both are optional and degrade gracefully when absent. |
| **NFR-37** | WON'T (v1) | **Integrated card / payment terminal.** Driving an EMV/card terminal and recording its tender on the sale. | Out of scope for v1. **Correction (2026-06-20): GAP-3 is now CLOSED (f08fb08).** The API field exists — `PosSaleRequest` now accepts a `tenders[]` list (CASH / CARD / MOBILE_MONEY / CHEQUE, split), so the sale path **does** record a card / mobile-money / split tender; the earlier "single exact CASH tender; there is no API field" statement is no longer true. With `tenders[]` live, the client can integrate an external certified terminal (keeping card data out of POS scope, NFR-22) and submit the tender mix. <br>> NOTE (2026-06-20): GAP-3 is now CLOSED (f08fb08) — the dependency this WON'T-v1 was bound to has lifted. The remaining out-of-scope element is the physical EMV-terminal *device integration*, not the ledger field; the owner should revisit whether terminal integration enters scope now. |

### 6.8 Platform & Build

The API is platform-agnostic (plain JSON over HTTPS), so the client's target framework, packaging,
update mechanism, and supported OS versions are **client decisions** the build spec must pin — or
explicitly defer as an Open Question rather than leave implicit.

| ID | MoSCoW | Requirement | Acceptance criteria |
|----|--------|-------------|---------------------|
| **NFR-40** | MUST | **Pinned platform/build spec (or explicit deferral).** The build must fix — or explicitly defer as a named Open Question — the **target framework / runtime**, the **packaging/distribution** model, the **auto-update mechanism**, and the **minimum OS versions per platform**. | The PRD/build spec states, per supported platform (desktop / mobile / kiosk): the chosen UI framework and runtime; how the app is packaged and distributed (e.g. installer, app store, kiosk image); how updates are delivered (auto-update channel + rollback) and how an enforced-minimum-version policy is applied; and the **minimum OS version** supported. Any of these not yet decided is recorded as a flagged **Open Question** (owner + the phase it blocks), not silently assumed. The minimum-OS choice is consistent with the secure-store (NFR-15) and at-rest-encryption (NFR-38) requirements (the platform must provide them). |

### 6.9 Non-functional traceability (gap-bound items)

For auditability, the table below lists the non-functional requirements whose shape is
dictated by a current API gap, with the named backend dependency.

| NFR | Gap | What v1 does instead | Backend change that lifts the constraint |
|-----|-----|----------------------|------------------------------------------|
| NFR-8, NFR-9, NFR-10, NFR-11 | **GAP-1 — CLOSED (f08fb08, §12 #1)** | Reconcile-before-resend is now the **no-key fallback**; send the optional `Idempotency-Key` header for server-side dedup (replay returns the original invoice) | **Delivered:** `Idempotency-Key` header + dedup store on `POST /pos/sales` |
| NFR-12 | No offline ingest (§12 #6, §11 §4.3) | Capture carts offline, replay serially online; never claim final until 201 | A batch/offline sale-ingest endpoint (**still open**, §12 #6) |
| NFR-22, NFR-37 | **GAP-3 — CLOSED (f08fb08, §12 #3)** | Multi-tender ships; card / mobile-money / split posts real ledger payments. (PCI scope — raw card data on device — remains a deliberate WON'T-v1 boundary, NFR-22.) | **Delivered:** `tenders[]` on `PosSaleRequest` (CASH / CARD / MOBILE_MONEY / CHEQUE, split) |
| NFR-6, NFR-27 | **GAP-4** server-authoritative pricing — by design (§12 #4) | Show prices as preview; receipt from server DTO; reductions via `lineDiscountAmount` | None planned; `unitPrice` accepted-but-ignored is the intended design (manual override not a feature) |

> **Correction (2026-06-20): GAP-2 is now CLOSED (f08fb08).** Whole-sale at-till correction is
> **available** — `POST /pos/sales/uid/{uid}/reverse` (perm `POS.SALE.VOID`, OPEN session)
> reverses revenue + VAT + cash + stock + COGS and drops the sale out of the drawer (§12 #2).
> The earlier statement that "at-till correction depends on GAP-2 (no POS reversal/refund/void
> endpoint)" is no longer true for OPEN-session sales. **Still deferred:** partial / line-level
> refunds (§12 #5); closed-session sales still use the back-office void path. The cash-drawer
> `REFUND` payout + back-office correcting entry (UC-B9, UC-D1, UC-D2) remains the treatment for
> those deferred cases — see part 2 for the functional treatment.
>
> > NOTE (2026-06-20): GAP-2 (whole-sale reversal) is now CLOSED (f08fb08). The decision to
> > keep refund/void NFRs absent from v1 was gated on that gap; the owner should revisit
> > whether at-till reversal NFRs now belong in v1.

---

## 7. UX & Key Screens

This section is a **high-level screen inventory and wireflow** — structure and behaviour in
words and ASCII, **no pixel detail**. It exists so a developer can see how the functional
and non-functional requirements compose into screens, and how the cashier moves between
them. Visual design is deliberately out of scope.

### 7.1 Screen inventory

| # | Screen | Purpose | Key API calls (ref) | Drives |
|---|--------|---------|---------------------|--------|
| S1 | **Login** | Authenticate the operator; discover scope & permissions. | `POST /auth/login`, `GET /auth/me`, `GET /auth/my-branches` (§01) | NFR-13, NFR-18, NFR-26 |
| S2 | **Shift Open** | Pick a till, declare opening float, open the session. | `GET /pos/tills` (§07), `POST /pos/sessions` (§08), `GET /fx/currencies/enabled` (§04) | UC-A4, UC-B1, NFR-4 (catalog pre-load), NFR-26 |
| S3 | **Sell / Cart** | The main ringing screen: scan/search, build the cart, manage lines. | `GET /products`, `/products/barcode-lookup`, `/units`, `/products/uid/{uid}/bulk-packs` (§03); optional `GET /stock/...` (§06), price previews (§04) | UC-C1–C6, NFR-1, NFR-23, NFR-24, NFR-33 |
| S4 | **Payment** | Take payment (cash, or a `tenders[]` mix — GAP-3 CLOSED, f08fb08), compute change locally for cash, confirm. | (none until confirm) | UC-C1, §12 #3 (multi-tender), NFR-2 |
| S5 | **Submit / Receipt** | Finalise the sale, handle the outcome, print the receipt. | `POST /pos/sales` (§09); on ambiguity `GET /pos/sessions/uid/{uid}` + `GET /sales-invoices` (§11) | UC-C7, UC-E2, NFR-7–NFR-11, NFR-34 |
| S6 | **Lookup / Reprint** | Find and reprint a past sale; check stock; look up/create a customer. | `GET /sales-invoices` (§09), `GET /stock/...` (§06), `GET/POST /customers` (§05) | UC-C8, UC-C3, UC-C6 |
| S7 | **Session Close & Reconcile** | Mid-shift X-read, close with counted cash, (supervisor) Z-read reconcile. | `GET .../x-read`, `POST .../close`, `POST .../reconcile`, `POST .../payouts` (§08) | UC-B4, UC-B5, UC-B6, UC-B3, NFR-18 |
| S8 | **Lock** | Idle/auto-lock overlay; quick unlock or re-login. | `POST /auth/refresh` or `POST /auth/login` (§01) | NFR-7, NFR-13, NFR-17, NFR-20 |
| S9 | **Held Carts** *(SHOULD)* | List locally-held / offline-captured carts; resume one. | none (local) until resumed → S4/S5 | UC-C9 (client-side only), NFR-12 |

> **No server draft/hold (UC-C9).** S9 is purely **client-side basket state** — there is
> **no** draft/park/hold endpoint in the API; a held cart is local data, never a server
> document, until it is rung through S5. This is a non-§12 absence (UC-C9 note).

### 7.2 Primary wireflow (the happy path + the load-bearing branches)

```
            ┌────────────┐
            │  S1 Login  │  POST /auth/login → GET /auth/me (permissions, scope)
            └─────┬──────┘  GET /auth/my-branches (optional branch switch)
                  │  hasBranch=false → "not provisioned to transact" (block, contact admin)
                  ▼
            ┌────────────┐
            │ S2 Shift   │  GET /pos/tills → pick till
            │   Open     │  GET /fx/currencies/enabled → default+allow-list currency
            └─────┬──────┘  POST /pos/sessions (opening float) → session OPEN
                  │  (background: NFR-4 catalog + unit pre-load/refresh)
                  ▼
   ┌───────────────────────────────────────────────────────┐
   │ S3 Sell / Cart  (the loop a cashier lives in)          │
   │  scan ──► local cache hit ─────────────► add line      │
   │   │        miss ► GET /barcode-lookup ─► add line       │
   │   │                 404 ► manual search (GET /products) │
   │  set qty / apply lineDiscountAmount (only price lever — GAP-4) │
   │  pick/create customer (S6) ; pick currency (allow-list)│
   │  optional: GET /stock advisory ; price preview (§04)   │
   └───────────────┬───────────────────────────────────────┘
                   │  cart not empty → "Pay"
                   ▼
            ┌────────────┐
            │ S4 Payment │  CASH or tenders[] mix (GAP-3 CLOSED, f08fb08). For cash,
            │            │  change computed LOCALLY (tenderedAmount is receipt-only).
            └─────┬──────┘
                  │  confirm  (control disabled until terminal outcome — NFR-2)
                  ▼
            ┌─────────────────────────────────────────────┐
            │ S5 Submit / Receipt                          │
            │  generate+persist client-tx-id (NFR-8)       │
            │  POST /pos/sales (X-Request-Id = client-tx)  │
            ├───────────────┬──────────────┬───────────────┤
            │ 201           │ clean 4xx     │ AMBIGUOUS     │
            │ persist DTO,  │ (400/403/404/ │ (timeout/500/ │
            │ print receipt │ 409-business/ │ drop)         │
            │ (NFR-34),     │ 415/422):     │ → NFR-9/10:   │
            │ reset to S3   │ show errors[],│ NO blind retry│
            │               │ fix & resend  │ GET session + │
            │               │ (terminal,    │ GET sales-    │
            │               │ no auto-retry)│ invoices;     │
            │               │ 409 optimistic│ if found →    │
            │               │ -lock → retry │ treat as done,│
            │               │               │ reprint; else │
            │               │               │ offer resend  │
            └───────────────┴──────────────┴───────────────┘
                  │
                  ▼  (any time: idle → S8 Lock ; 401 → refresh/relogin, cart kept — NFR-7/13)
            ┌─────────────────────────────┐
            │ S7 Session Close & Reconcile│  GET x-read (mid-shift, UC-B4)
            │  POST .../payouts (UC-B3)   │  POST .../close (counted cash → variance)
            │  POST .../reconcile (Z-read,│  supervisor only, POS.SESSION.RECONCILE
            │   posts variance to GL)     │  (NFR-18 segregation)
            └─────────────────────────────┘
```

### 7.3 Screen-by-screen behaviour notes (no pixel detail)

- **S1 Login.** Username/password → `POST /auth/login`. On success, immediately call
  `GET /auth/me` and gate the UI on the returned `permissions` (or `isRoot`), per NFR-18 —
  hide/disable actions whose `POS.*` code is absent rather than letting the cashier hit a
  generic `403`. If `hasBranch == false` (§01 §2), show a "not provisioned to transact"
  state and stop. Offer branch selection from `GET /auth/my-branches` when the operator has
  more than one (`X-Branch-Uid` scope, §01 §8). Lockout (`401 Account is locked …`, §01 §2)
  is shown as a distinct, non-retryable message.

- **S2 Shift Open.** List tills with `GET /pos/tills?companyId=&branchId=` (§07) and let the
  cashier pick one; declare the opening float; `POST /pos/sessions`. A `409 "Till … already
  has an OPEN session."` (§08) is surfaced with a path to view/close the existing session,
  not silently retried. On success, store the session `uid` (it is required on every sale)
  and kick off the NFR-4 catalog/unit pre-load and the NFR-26 currency allow-list fetch.

- **S3 Sell / Cart.** The persistent ringing surface: a scan/search field (scanner-first,
  NFR-24/NFR-33), the cart line list, and per-line controls for quantity and
  `lineDiscountAmount` (the **only** price lever — GAP-4; there is no price override at the
  POS). Prices shown are **previews** (§04); stock figures shown are **advisory** (§06,
  UC-C6). Customer selection links to S6 (walk-in default; registered customer via
  `GET/POST /customers`, §05, UC-C3). Currency picker is constrained to the enabled list
  (NFR-26). Cart state is durably persisted on every change (NFR-7).

- **S4 Payment.** **Multi-tender now ships (GAP-3 CLOSED, f08fb08):** the sale accepts an
  optional `tenders[]` list (CASH / CARD / MOBILE_MONEY / CHEQUE, split, sum ≥ gross), so
  card / mobile-money / split tenders post real ledger payments (§12 #3). For a cash tender
  the cashier enters the tendered amount and the client computes change **locally** —
  `tenderedAmount` is receipt-only and is **not** stored on the invoice (§04 §6). The earlier
  "cash only / card-mobile are not v1 tenders / out-of-band + back-office" framing no longer
  applies to the ledger. **PCI note:** handling raw card *data* on the device remains a
  deliberate WON'T-v1 boundary (NFR-22) — prefer an external certified terminal. The confirm
  control is disabled the moment it is pressed until a terminal outcome (NFR-2) to prevent
  double submission.

- **S5 Submit / Receipt.** Generates/loads the durable client-transaction-id (NFR-8),
  sends `POST /pos/sales` with it as `X-Request-Id`, and branches strictly on HTTP status
  (NFR-9, §11 §2.1). On **201**, persist the `SalesInvoiceDto` and print from it (NFR-34);
  reprints come from this persisted copy (UC-C7/C8), never a re-POST. On a **clean 4xx**,
  render `errors[]` as a list (user-safe, §11 §1) and let the cashier fix and resend
  (terminal — no auto-retry), except the optimistic-lock `409` which may be retried. On an
  **ambiguous outcome**, run reconcile-before-resend (NFR-10) before offering any resend.

- **S6 Lookup / Reprint.** Find a past sale via `GET /sales-invoices?companyId=` and reprint
  from it (UC-C8); look up or create a customer (§05, UC-C3); check stock advisory (§06,
  UC-C6). This screen never posts a sale.

- **S7 Session Close & Reconcile.** Mid-shift **X-read** (`GET .../x-read` →
  `totalSalesAmount`, `totalPayoutsNetAmount`, `expectedCashAmount`, `invoiceCount`, §08,
  UC-B4) for a non-finalising progress check; **close** with the counted-cash declaration
  (`POST .../close` → server computes `expectedCashAmount` and `varianceAmount`, §08,
  UC-B5); and a **supervisor-only Z-read reconcile** (`POST .../reconcile`, posts variance
  to GL, requires `POS.SESSION.RECONCILE`, UC-B6) — segregated from the cashier per NFR-18.
  Cash drops/refunds during the shift are `POST .../payouts` (UC-B3); note a `REFUND` payout
  is drawer bookkeeping only and does **not** reverse stock/GL/AR. For an actual whole-sale
  reversal (revenue + VAT + cash + stock + COGS, dropped out of the drawer) the client now
  calls `POST /pos/sales/uid/{uid}/reverse` (perm `POS.SALE.VOID`, OPEN session — GAP-2 CLOSED,
  f08fb08, §12 #2); partial / line-level refunds remain deferred (§12 #5).

- **S8 Lock.** Reachable from idle timeout (NFR-17) or manual lock. Preserves the cart
  (NFR-7) and the open session; unlock via quick-auth or `POST /auth/refresh`/re-login. A
  `401 "User account is no longer active."` forces full re-login (NFR-20).

- **S9 Held Carts (SHOULD).** Lists locally held / offline-captured carts (NFR-12). Resuming
  one returns to S4/S5. The screen states clearly that held carts are **not yet sold** and
  carry no fiscal/final receipt until a 201 is received — there is no server-side draft
  (UC-C9).

### 7.4 Cross-cutting UX rules

- **Branch on status, render messages.** UI control flow keys off HTTP status codes
  (NFR-9, §11 §2.1); the `errors[]` strings are displayed to the cashier but never parsed
  (§11 §1). Messages may be a list (one per invalid field) and must render as such (NFR-25).
- **The receipt is the server's truth.** Every printed total comes from the 201
  `SalesInvoiceDto` (NFR-27, §04 §6); local previews are labelled as previews.
- **Never lose the cart.** Restart, crash, token refresh, and lock all preserve the
  in-progress cart (NFR-7, NFR-11, NFR-13, NFR-17).
- **One logical sale, one client-tx-id.** The id created in S5 is the unit of
  reconciliation across crashes and retries (NFR-8, NFR-10).

---

*End of Part 3 (sections 6–7). Functional requirements are in part 2; scope and personas in
part 1. All API references (`§NN`) point to `../00-…-12` in this folder's parent; use cases
(`UC-xx`) to `../use-cases/`; gaps to [`../12-known-limitations.md`](../12-known-limitations.md).*
