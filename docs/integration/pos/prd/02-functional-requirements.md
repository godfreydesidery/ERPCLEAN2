# POS Client PRD — Part 2: Functional Requirements

> **Document scope.** This is **Section 5 — Functional Requirements** of the Product
> Requirements Document for the **external Point-of-Sale (POS) client** — a separate desktop
> and/or mobile/kiosk application a developer will build to run a retail till against this ERP's
> REST API. The POS client **owns no database and no business logic of its own**: it authenticates,
> reads master data, and posts transactions entirely through the ERP REST API documented in the
> sibling reference set (`../00`–`../12`) and exercised by the use-case catalogue (`../use-cases/`).
> The other PRD parts (vision, personas, non-functional requirements, rollout) live in the
> companion files in this directory.

## How to read this section

Each requirement is **atomic and testable** and carries:

- **ID** — `FR-<AREA>-<n>` (stable; do not renumber).
- **Statement** — what the client must do.
- **Priority** — MoSCoW: **Must** / **Should** / **Could** / **Won't-for-v1**. "Won't-for-v1"
  means out of scope for the first release; where the reason is a backend gap, the requirement
  records the **specific API change it depends on**.
- **Acceptance criteria** — observable, verifiable conditions.
- **Traceability** — the use case(s) (`UC-xx`) and/or API section(s) (`§nn`, all under `../`) the
  requirement derives from. Backend-gap dependencies cite `§12 #n` (`../12-known-limitations.md`).

### The four constraints that shaped every area (read first)

> **STATUS UPDATE (2026-06-20, commit `f08fb08`, ADR-0042).** Three of the four original "hard
> constraints" below — **sale idempotency (§12 #1)**, **whole-sale POS reversal/refund/void
> (§12 #2)**, and **multi-tender / non-cash payments (§12 #3)** — are now **CLOSED** in the shipped
> API. The fourth — **server-authoritative pricing (§12 #4)** — is now documented as **by design**,
> not a gap (`unitPrice`/`agentId` are informational, no longer `@NotNull`). The table below is
> retained for the historical record but now reflects the **shipped** behaviour. The only genuinely
> open items are **partial / line-level refunds** (explicitly deferred by ADR-0042) and **client-side
> offline ingest**. The POS client must still never silently assume a capability the API lacks.

These were the **constraints** (`../12-known-limitations.md`) that originally shaped this section.
Each requirement below that touches them now states the **shipped capability** (or, where still
deferred, its backend dependency).

| Constraint | Shipped status | Where it bites |
|-----|--------------------------|----------------|
| **§12 #1 — sale idempotency / dedup** on `POST /pos/sales` | **CLOSED** — optional `Idempotency-Key` header (reserve-before-process, `pos_sale_idempotency` V70); replay returns the original invoice; in-flight duplicate gets a retryable `409`. Omitting the header ⇒ legacy non-idempotent behaviour (reconcile-before-resend is the no-key fallback only). | Cart submit, offline replay, token-expiry retry |
| **§12 #2 — POS refund / void / reversal** endpoint | **CLOSED for whole-sale** — `POST /pos/sales/uid/{uid}/reverse` (perm `POS.SALE.VOID`, OPEN session, FINALISED, POS-origin) reverses revenue + VAT + cash + stock + COGS and drops the sale out of the drawer. **Still deferred:** partial / line-level refunds; closed-session sales use the back-office void. | Returns, mistake correction |
| **§12 #3 — multi-tender / non-cash payments** | **CLOSED** — optional `tenders[]` list (`CASH`/`CARD`/`MOBILE_MONEY`/`CHEQUE`, split, sum ≥ gross); each tender posts a real ledger payment. Omitting `tenders` ⇒ today's single exact-CASH behaviour. | Payments, tender |
| **§12 #4 — server-authoritative pricing (`unitPrice`, `agentId` informational)**; no manual price override | **By design (not a gap).** The server re-derives price + VAT per line from its own price list (client's `unitPrice` is accepted but ignored; the only reduction lever is `lineDiscountAmount`) and attributes the sale to the logged-in user. `@NotNull` on `unitPrice`/`agentId` was relaxed (now optional). | Pricing, line edits, agent attribution |

> **NOTE (2026-06-20): GAP-1/2/3 are now CLOSED (`f08fb08`) — the "cash-only pilot" framing below
> should be revisited by the owner.** A multi-tender, safe-retry, at-till-whole-sale-refund POS is
> now viable on the shipped API; the original cash-only-pilot recommendation predates ADR-0042. Left
> in place for the owner to re-plan deliberately.

A controlled, attended, **cash-only** pilot is fully viable on the API as it stands; these four gaps
bound the v1 scope.

---

## 5.1 Authentication & Session

The POS client is a **stateless JWT client**: login → bearer token on every call → refresh before
expiry → logout. The token is scoped to the operator's **default branch**; multi-branch operators
switch scope per-request with the `X-Branch-Uid` header. Source: `§01`, `§02`, UC-A1, UC-A2,
UC-C10, UC-E3.

### FR-AUTH-1 — Operator login

- **Statement.** The client must let an operator log in with username + password via
  `POST /api/v1/auth/login` and securely persist the returned access token, refresh token, and
  `accessTokenExpiresAt`.
- **Priority.** Must.
- **Acceptance criteria.**
  - A successful login (HTTP 200) stores `accessToken`, `refreshToken` (single-use), and the
    `AuthUser` profile; subsequent protected calls send `Authorization: Bearer <accessToken>`.
  - Invalid credentials / locked / disabled accounts return HTTP 401 and the client shows the
    server's user-safe message without enumerating accounts; after the configured lockout the
    operator is told to wait/contact an admin.
  - Blank username or password is rejected client-side (and 400 from the server is handled).
- **Traceability.** `§01 §2`, UC-A1 (step 6), UC-C1 (preconditions).

### FR-AUTH-2 — Capability discovery after login

- **Statement.** Immediately after login the client must call `GET /api/v1/auth/me` and gate its UI
  on the returned effective `permissions[]` (or `isRoot`).
- **Priority.** Must.
- **Acceptance criteria.**
  - Actions are exposed only when the matching `POS.*` code is present (e.g. "Ring sale" requires
    `POS.SALE.CREATE`; "Open session" requires `POS.SESSION.OPEN`; "Reconcile" requires
    `POS.SESSION.RECONCILE`), or when `isRoot` is true.
  - The client treats the server `403` as the real enforcement gate and degrades gracefully (hides
    or disables, never crashes) when a code is absent.
- **Traceability.** `§01 §6`, `§01 §9`, UC-A1, use-cases README (actors & permissions legend).

### FR-AUTH-3 — Detect a non-transactable scope

- **Statement.** The client must detect `hasBranch == false` (no usable default branch) at login and
  block all transacting actions, surfacing "not provisioned for transacting — contact an admin".
- **Priority.** Must.
- **Acceptance criteria.**
  - When `AuthUser.hasBranch` is false (or `activeBranchUid` is null), session-open, sale, payout,
    close, and reconcile actions are disabled and an explanatory message is shown.
  - The operator can still log out and re-login after provisioning is fixed.
- **Traceability.** `§01 §2`, UC-A1 (alternate flow: skip branch default → read-only landing).

### FR-AUTH-4 — Proactive + reactive token refresh mid-shift

- **Statement.** The client must keep the session alive across the short access-token TTL by
  refreshing via `POST /api/v1/auth/refresh`, both proactively (before `accessTokenExpiresAt`) and
  reactively (on a `401`), and must **rotate** the stored refresh token on every refresh.
- **Priority.** Must.
- **Acceptance criteria.**
  - The client refreshes before expiry using `accessTokenExpiresAt` (epoch **seconds**).
  - On any `401` that is not a refresh-token failure, the client refreshes once and retries the
    original request transparently; the in-progress cart is preserved across the refresh.
  - After each refresh the client **replaces** the stored refresh token with the new one and
    discards the old (single-use rotation); it never refreshes the same token twice / from two
    instances.
  - A `401` from the refresh call itself (rotated/expired/revoked, or user no longer ACTIVE) routes
    the operator back to login, preserving the local cart.
- **Traceability.** `§01 §3`, `§01 §4` (single-use rotation), UC-C10, UC-E2 (token expired during a
  drop).

### FR-AUTH-5 — Do not blindly retry a sale POST that returned 401

- **Statement.** When a `POST /api/v1/pos/sales` fails with `401`, the client must refresh/re-login
  and then resend safely — by reusing the same `Idempotency-Key` (§12 #1, CLOSED) or, if no key was
  sent, **reconcile before resending** (per FR-OFF-3) — rather than blindly retrying, because the
  sale may already have committed.
- **Priority.** Must.
- **Acceptance criteria.**
  - A 401 on the sale endpoint never triggers an automatic resend of the same basket; it triggers
    a same-key resend (idempotency-protected) or, absent a key, the reconcile-before-resend flow.
- **Traceability.** UC-C10 (notes), `§12 #1`, `§11 §4`.

### FR-AUTH-6 — Logout

- **Statement.** The client must log out via `POST /api/v1/auth/logout` (revoking the refresh token)
  and discard all tokens locally when the operator signs off.
- **Priority.** Must.
- **Acceptance criteria.**
  - Logout sends the stored refresh token and treats HTTP `204` as success.
  - After logout, no token is retained; the access token is discarded client-side (it is not
    server-revoked and simply expires within its TTL).
  - The client warns / blocks logout while a session is OPEN with an un-submitted cart, to avoid
    losing an in-progress (client-only) basket.
- **Traceability.** `§01 §5`.

### FR-AUTH-7 — Branch scope selection for multi-branch operators

- **Statement.** For an operator assigned to multiple branches, the client must let them choose the
  acting branch (from `GET /api/v1/auth/my-branches`) and send `X-Branch-Uid` on scoped calls.
- **Priority.** Should.
- **Acceptance criteria.**
  - The branch picker is populated from `my-branches` (self-scoped; needs no extra permission).
  - The chosen `branchUid` is sent as `X-Branch-Uid`; a rejected branch returns `403` and the
    client surfaces "you are not assigned to that branch" and reverts to the default scope.
  - Single-branch operators are not shown a picker (default scope is used).
- **Traceability.** `§01 §7`, `§01 §8`, UC-E3.

### FR-AUTH-8 — Treat token loss on backend restart (dev) gracefully

- **Statement.** The client must handle a sudden invalidation of all tokens (a `401` storm) by
  routing to login without data loss.
- **Priority.** Should.
- **Acceptance criteria.**
  - On repeated `401`s where refresh also fails, the client returns to login and preserves any
    local cart and locally-stored sale results.
- **Traceability.** `§01 §3` (dev `dev-in-memory` key rotation), UC-A1 (notes).

---

## 5.2 Shift & Till Management

A *session* is one cashier's shift on one till, with a strict one-way lifecycle
`OPEN → CLOSED → RECONCILED`; every state-violating call returns `409`. A till may have **at most one
OPEN session**. Source: `§07`, `§08`, UC-A3–A5, UC-B1–B6, UC-E5.

### FR-SHIFT-1 — Select an ACTIVE till for the shift

- **Statement.** The client must list tills via `GET /api/v1/pos/tills?companyId=&branchId=` and let
  the operator pick one, filtering **client-side** to `status == "ACTIVE"`.
- **Priority.** Must.
- **Acceptance criteria.**
  - The list call uses the **numeric** `companyId` and `branchId` (not uids).
  - INACTIVE / ARCHIVED tills (which the endpoint still returns) are hidden from the session-open
    picker.
  - An empty list, or a list with no ACTIVE tills, shows a "no usable till — contact a manager"
    state (creating a till needs `POS.TILL.MANAGE`, a back-office code).
  - The selected till's **`uid`** is carried forward to session-open.
- **Traceability.** `§07 §3`, UC-A4, UC-E3.

### FR-SHIFT-2 — Create a till (manager-gated)

- **Statement.** When the operator holds `POS.TILL.MANAGE`, the client may create a till via
  `POST /api/v1/pos/tills` with `{ companyUid, branchId (numeric), name }`.
- **Priority.** Should.
- **Acceptance criteria.**
  - The create form sends `companyUid` (string) plus the **numeric** `branchId` (the odd-one-out
    body shape) and a `name` ≤ 60 chars.
  - On `409` "no cash/bank account for company", the client surfaces the message and routes to
    back-office cash-account setup.
  - The "Create till" action is hidden when `POS.TILL.MANAGE` is absent.
- **Traceability.** `§07 §1`, UC-A3.

### FR-SHIFT-3 — Retire a till (manager-gated); no rename/move/reactivate

- **Statement.** When the operator holds `POS.TILL.MANAGE`, the client may deactivate a till via
  `DELETE /api/v1/pos/tills/uid/{uid}` (soft-delete), and must **not** offer rename, move-branch, or
  reactivate.
- **Priority.** Could.
- **Acceptance criteria.**
  - Deactivation treats HTTP `200` (empty body) as success and removes the till from ACTIVE pickers.
  - The UI offers no "edit till", "move till", or "reactivate" controls (the API has none); the
    documented workaround (deactivate + recreate) is surfaced as guidance only.
- **Traceability.** `§07 §4`, UC-A5, UC-A5 (not-supported table).

### FR-SHIFT-4 — Open a session with an opening float

- **Statement.** The client must open a session via `POST /api/v1/pos/sessions` with
  `{ tillUid, openingFloatAmount }` and persist the returned `uid` and `sessionNumber`.
- **Priority.** Must.
- **Acceptance criteria.**
  - `openingFloatAmount` is validated `>= 0.00` (0.00 allowed); `tillUid` is non-blank.
  - On `201`, the client stores `PosSessionDto.uid` (used by every sale/payout/X-read/close/
    reconcile) and shows `sessionNumber` (e.g. `POS-0001`) and `status: "OPEN"`.
  - A `409` "Till … already has an OPEN session" routes the operator to resume/close the existing
    open session (discovered via the session list) rather than opening a second.
  - The client does **not** send `openedAt` or `cashierId` (server-stamped).
- **Traceability.** `§08 §3`, UC-B1, UC-E3.

### FR-SHIFT-5 — Resume / view the current session

- **Statement.** The client must be able to recover and display the current session state after an
  app restart, via `GET /api/v1/pos/sessions/uid/{uid}` (held uid) or by listing
  `GET /api/v1/pos/sessions?companyId=&sort=openedAt,desc` and filtering for the till's `OPEN`
  session.
- **Priority.** Must.
- **Acceptance criteria.**
  - With a held uid, get-by-uid returns the `PosSessionDto` and the client resumes the shift.
  - Without a uid (e.g. after a crash), the client lists sessions (paged; `companyId` required) and
    filters `posTillId == <till>` and `status == "OPEN"` to recover the uid.
  - An empty list returns HTTP `200` with `data: []` and is handled as "no open session", not an
    error.
- **Traceability.** `§08 §4–§5`, UC-B2.

### FR-SHIFT-6 — Record a cash payout / drawer drop (outflow only)

- **Statement.** During an OPEN session the client must record cash outflows via
  `POST /api/v1/pos/sessions/uid/{uid}/payouts` with `{ payoutType, amount, reason? }`, where
  `payoutType ∈ { PAID_OUT, REFUND }`.
- **Priority.** Must.
- **Acceptance criteria.**
  - `amount` is validated `>= 0.01`; `reason` ≤ 255 chars; `payoutType` is one of the two enum
    values.
  - A `200` (empty `data`) is treated as success; the client records its own reference (no payout
    uid is returned) and reflects the reduced expected cash on the next X-read.
  - A `REFUND` payout is clearly labelled in the UI as **drawer bookkeeping only** — it posts no
    stock/VAT/revenue/AR reversal (see FR-RET-1). Note (2026-06-20): a true at-till **whole-sale
    reversal** now exists (`/reverse`, §12 #2 CLOSED) and posts the full stock/VAT/revenue reversal;
    the `REFUND` payout remains the cash-only drawer lever and is **not** the way to refund a sale.
  - The payout is **not** auto-retried on a timeout (it is not idempotent); the client confirms via
    X-read before any resend.
- **Traceability.** `§08 §6`, UC-B3, UC-D1, `§12 #2`, `§12 #1`.

### FR-SHIFT-7 — Float top-up / cash-in mid-shift

- **Statement.** Add cash to the drawer (or correct the opening float) during an open shift.
- **Priority.** Won't-for-v1.
- **Reason / dependency.** The float is fixed at open and `PosPayoutType` has **outflow values
  only** (`PAID_OUT`, `REFUND`); there is no `CASH_IN` type. **Backend change required:** add a
  `CASH_IN` payout type (or a float-adjust endpoint) on the session API.
- **Acceptance criteria.** The client offers no mid-shift cash-in control; if extra cash must be
  added, it surfaces the documented workaround (close + reopen with a corrected float, or a
  back-office movement).
- **Traceability.** UC-B7, `§08 §6`.

### FR-SHIFT-8 — Mid-shift X-read

- **Statement.** The client must provide a mid-shift X-read via
  `GET /api/v1/pos/sessions/uid/{uid}/x-read` (session must be OPEN) and display the running drawer
  totals.
- **Priority.** Must.
- **Acceptance criteria.**
  - The X-read displays `openingFloatAmount`, `totalSalesAmount`, `totalPayoutsNetAmount`,
    `expectedCashAmount` (= float + sales − payouts), and `invoiceCount`.
  - The X-read is labelled as a **cash-drawer report, not a ledger report**; the client does not
    claim the ledger is posted (stock/GL/AR are eventual, ~1s).
  - On a non-OPEN session the call returns `409` and the client offers the Z-read path instead.
- **Traceability.** `§08 §7`, UC-B4, UC-E5.

### FR-SHIFT-9 — Close the session (declare counted cash)

- **Statement.** The client must close a session via
  `POST /api/v1/pos/sessions/uid/{uid}/close` with `{ countedCashAmount, notes? }` and display the
  server-computed expected cash and variance.
- **Priority.** Must.
- **Acceptance criteria.**
  - The client prompts the cashier to physically count the drawer and submits `countedCashAmount`
    (`@NotNull`).
  - On `200` it shows `status: "CLOSED"`, `expectedCashAmount`, and `varianceAmount` (positive =
    over, negative = short); it does **not** attempt further sales/payouts/X-reads on this session.
  - The UI states that close is **one-way** (no re-open) and that no GL posting happens at close.
  - A second close returns `409`; the client re-fetches the session to confirm the first close
    landed rather than retrying blindly.
- **Traceability.** `§08 §8`, UC-B5, UC-E5.

### FR-SHIFT-10 — Reconcile / Z-read (supervisor-gated)

- **Statement.** When the operator holds `POS.SESSION.RECONCILE`, the client must reconcile a CLOSED
  session via `POST /api/v1/pos/sessions/uid/{uid}/reconcile` (optional `{ notes }`) and display the
  returned `ZReadDto`.
- **Priority.** Must.
- **Acceptance criteria.**
  - The reconcile action is hidden when `POS.SESSION.RECONCILE` is absent (segregation of duties
    from the cashier).
  - The variance is computed **server-side**; the client sends **no amount**, but does send
    `Content-Type: application/json` (an empty `{}` body is acceptable).
  - On `200` the client shows the Z-read totals and `varianceJournalId` (null for a zero variance);
    `status` is `RECONCILED` (terminal — no edit/undo offered).
  - A `409` "Session must be CLOSED before reconciliation." routes to close-first; a `409` from a
    missing GL config (`POS_CASH_OVER` / `POS_CASH_SHORT`) is surfaced as a fail-fast back-office
    fix, leaving the session CLOSED.
- **Traceability.** `§08 §9`, UC-B6, UC-E5.

### FR-SHIFT-11 — End-of-day close-out across tills (iterative)

- **Statement.** The client must support closing/reconciling **each** open session in turn (there is
  no bulk close), discovered via the session list.
- **Priority.** Should.
- **Acceptance criteria.**
  - The client lists open sessions for the company and walks the operator through close → reconcile
    per session uid.
  - The client makes no "close all tills" call (none exists) and handles each session's `409`/`403`
    independently.
- **Traceability.** UC-E5 (no bulk close-out), `§08 §5`.

### FR-SHIFT-12 — Re-open or edit a closed/reconciled session

- **Statement.** Undo a premature close, fix a mis-counted drawer, or reverse a reconcile from the
  POS.
- **Priority.** Won't-for-v1.
- **Reason / dependency.** The lifecycle is strictly one-way; every backward transition is `409`.
  **Backend change required:** a re-open / amend endpoint (out of scope today). Workaround is a
  back-office GL correction.
- **Acceptance criteria.** The client offers no re-open/edit control and points to the back-office
  correction path.
- **Traceability.** UC-B8.

---

## 5.3 Catalog & Product Search

There is **no "POS catalog" or "sellable-only" endpoint**; the client loads the shared product/unit
masters and filters client-side. A POS line needs **numeric** `productId` + `unitId`. Source: `§03`,
UC-C1, UC-C6.

### FR-CAT-1 — Load and cache the catalog at shift start

- **Statement.** The client must page through `GET /api/v1/products?companyId=…` and build a local,
  searchable cache of **sellable, active** products.
- **Priority.** Must.
- **Acceptance criteria.**
  - The client keeps only products where `sellable == true && status == "ACTIVE"` (the endpoint
    returns all products regardless of flag/status).
  - Each cached product retains `id`, `name`, `code`, `baseUnitUid`, `vatStatus`, and stock-tracking
    flags.
  - `companyId` is sent on every catalog call; a `403` (out of scope) and `400` (missing
    `companyId`) are handled.
- **Traceability.** `§03 §1`, `§03` (recommended catalog-load flow).

### FR-CAT-2 — Build a unit-id map

- **Statement.** The client must page through `GET /api/v1/units?companyId=…` and build a
  `unitUid → { id, code, symbol, decimalPlaces, fractional }` map so it can fill the sale line's
  **numeric** `unitId` from the product's `baseUnitUid`.
- **Priority.** Must.
- **Acceptance criteria.**
  - The client resolves each product's `baseUnitUid` to a numeric `unitId` via the map before
    ringing.
  - The client holds **both** `PRODUCT.VIEW` and `UOM.VIEW` (distinct permissions); a `403` on
    `/units` is surfaced as a provisioning issue.
- **Traceability.** `§03 §3`, `§03 §5`.

### FR-CAT-3 — Search products by name or code

- **Statement.** The client must offer search-as-you-type using `GET /api/v1/products?...&q=…`
  (server: case-insensitive `name` contains OR exact `code`) and/or its local cache.
- **Priority.** Must.
- **Acceptance criteria.**
  - Searching by partial name and by exact code both surface the right product.
  - Results are filtered to sellable/active before display.
- **Traceability.** `§03 §1`.

### FR-CAT-4 — Barcode / SKU scan lookup

- **Statement.** The client must resolve a scanned barcode via
  `GET /api/v1/products/barcode-lookup?companyId=…&barcode=…` and add the resulting product as a
  cart line, using the returned `uomId` for `unitId` when present (else the product's base unit).
- **Priority.** Must.
- **Acceptance criteria.**
  - A known barcode resolves to `productId` (+ optional `uomId`) and adds a line.
  - A `404` ("barcode not found") is handled as "unknown item — search/type manually", not a crash.
  - The client does **not** rely on the product `q` search to match barcodes (it does not search
    them).
- **Traceability.** `§03 §4`.

### FR-CAT-5 — Sell by pack / bulk unit

- **Statement.** The client should let the cashier sell by a bulk-pack unit, fetching
  `GET /api/v1/products/uid/{uid}/bulk-packs` and using the pack's unit id for `unitId`.
- **Priority.** Should.
- **Acceptance criteria.**
  - Selecting a carton/case sets `unitId` to the bulk-pack unit; the server converts to base via
    `factorToBase` (the client need not compute base qty for posting).
- **Traceability.** `§03 §3`, UC-C2 (carton selling).

---

## 5.4 Cart & Selling

The basket lives **entirely in the client** until submit; `POST /api/v1/pos/sales` is a one-shot
finalise (price + tender + commit) — there is no add-line / draft / hold endpoint. Tender is a
single exact CASH leg by default, or an optional `tenders[]` list (multi-tender now shipped, §12 #3
CLOSED). Source: `§09`, UC-C1–C5, UC-C9.

### FR-SELL-1 — Build a multi-line cart (client-side)

- **Statement.** The client must let the cashier add, edit, and remove cart lines (product,
  quantity, unit, optional line discount) in local state, with a live client-side preview total.
- **Priority.** Must.
- **Acceptance criteria.**
  - Add / change-qty / change-unit / remove all update the local cart and the preview total.
  - The cart is preserved across token refresh (FR-AUTH-4) and app navigation; it is **not**
    persisted server-side until submit.
  - At least one line is required before submit (`lines` is `@NotEmpty`).
- **Traceability.** `§09 §2`, UC-C1, UC-C2, UC-C9 (basket is client-only).

### FR-SELL-2 — Quantity entry honouring unit precision

- **Statement.** The client must accept quantities `>= 0.0001`, respecting each unit's
  `decimalPlaces` / `fractional` flag for display and entry.
- **Priority.** Must.
- **Acceptance criteria.**
  - Non-fractional units (e.g. EA) reject fractional input; fractional units (e.g. KG) allow
    decimals to the unit's precision.
  - A quantity ≤ 0 is rejected client-side (and the server's 400 is handled).
- **Traceability.** `§09 §2.2`, `§03 §5`.

### FR-SELL-3 — Submit the sale (one-shot finalise)

- **Statement.** The client must submit the basket via `POST /api/v1/pos/sales` with
  `{ sessionUid, customerId, agentId, currency, lines[], tenderedAmount?, notes? }` and treat the
  `201` `SalesInvoiceDto` as the authoritative result.
- **Priority.** Must.
- **Acceptance criteria.**
  - Each line carries `productId`, `unitId`, `quantity`, and optional `lineDiscountAmount`;
    `unitPrice` and `agentId` are now **optional** (the `@NotNull` was relaxed, ADR-0042 §12 #4) and
    informational — accepted but ignored, the server re-derives price + VAT.
  - `Content-Type: application/json` is always sent (else `415`).
  - On `201`, the client reads `invoiceNumber`, `grossTotalAmount`, `netTotalAmount`,
    `vatTotalAmount`, `taxSummary`, `finalisedAt`, and `uid`, and prints the receipt (FR-RCPT-1).
  - The whole call is all-or-nothing: on any line failure the entire sale is rejected and the cart
    is kept for correction.
- **Traceability.** `§09 §1–§5`, UC-C1, UC-C2.

### FR-SELL-4 — Per-line discount (absolute amount only)

- **Statement.** The client must express any line reduction as `lineDiscountAmount` (absolute money,
  document currency), converting any percentage client-side first.
- **Priority.** Must.
- **Acceptance criteria.**
  - A discount entered as a percentage is converted to an absolute amount before sending (the POS
    path forwards only `lineDiscountAmount`; `lineDiscountPercent` is unreachable).
  - The client previews "discount before VAT" but treats the returned invoice as the source of
    truth.
  - A discount that nets the line to 0 (a 100% / over-line discount) lets the server floor net at 0;
    the client warns on a 0-gross / free line before submit.
- **Risk / open question (UNVERIFIED).** A **fully zero-gross sale** (every line netted to 0) is
  **unverified** against the server. `PosSaleServiceImpl` has **no explicit zero-gross guard**, and
  the finalise step then adds a payment for the (zero) gross (a single CASH leg by default, or the
  supplied `tenders[]`) and asserts paid-in-full; whether a zero-gross invoice finalises cleanly /
  is treated as paid-in-full is **not confirmed**.
  Confirm this behaviour with the ERP team (or a controlled test) **before** relying on free / 100%-off
  sales — do **not** assume it simply works.
- **Traceability.** `§09 §4`, `§09 §6`, UC-C4, `§12 #4`.

### FR-SELL-5 — Manual price override at the till

- **Statement.** Override the server list price for a line at the till (the `unitPrice` field).
- **Priority.** Won't-for-v1.
- **Reason / dependency.** `unitPrice` is now **optional** (the `@NotNull` was relaxed, §12 #4) but
  is still **accepted-and-ignored** — the server always re-derives price from its own price list,
  and there is no POS price-override permission/path. Server-authoritative pricing is **by design**
  (§12 #4 is a deliberate price-integrity guarantee, not a gap); a manual price-override is still
  **not a feature**. **Backend change required (if ever wanted):** honour `unitPrice` as a
  permission-gated override (`POS.SALE.PRICE_OVERRIDE`, audited). **v1 workaround:** express the
  reduction as `lineDiscountAmount` (FR-SELL-4).
- **Acceptance criteria.** The client never presents `unitPrice` as an editable price that changes
  the charge; any reduction is modelled as a discount.
- **Traceability.** `§12 #4`, UC-C4 (notes), UC-C-not-supported table.

### FR-SELL-6 — Auto-applied tiers / customer prices / promotions

- **Statement.** Automatically apply price-list tiers, customer-specific prices, or promotions to
  the rung price.
- **Priority.** Won't-for-v1.
- **Reason / dependency.** The POS path prices from the product's price-list row only; pricing-rule
  endpoints are **advisory display data**. **Backend change required:** server-side rule application
  on the POS sale path. **v1 workaround:** read the rule and reflect the negotiated price as a
  `lineDiscountAmount`.
- **Acceptance criteria.** Any tier/customer/promo price shown is labelled "preview"; only
  `lineDiscountAmount` affects the charge.
- **Traceability.** `§04 §3`, UC-C-not-supported table.

### FR-SELL-7 — Attribute the sale to an agent (limited)

- **Statement.** The client may send an `agentId` (now optional — `@NotNull` relaxed, §12 #4), and
  must **not** promise that an arbitrary agent is recorded on the invoice.
- **Priority.** Should.
- **Acceptance criteria.**
  - An `agentId` may be sent (resolve via `GET /api/v1/agents` if a picker is offered; needs
    `AGENT.VIEW`); it is **informational** and not forwarded.
  - The UI states that the invoice agent defaults to the **authenticated user** (the POS path
    forwards `agentUid = null`); the client treats the returned `agentId`/`agentName` as the source
    of truth and does not display the submitted `agentId` as "the recorded agent".
- **Note (doc accuracy).** API ref `§09 §2.1` originally described `agentId` as "carried for
  receipt/audit attribution"; that was **inaccurate** — the authoritative behaviour is "informational
  / **not forwarded**" (corrected in `§09 §2.1` and catalogued in `§12 #4`). `agentId` is now
  optional, not required-but-ignored. A v1 build must **not** rely on per-sale agent attribution.
- **Traceability.** UC-C5, `§09 §2.1`, `§12 #4`.

### FR-SELL-8 — Attribute the invoice to an arbitrary agent

- **Statement.** Record a specific (non-cashier) agent on the finalised POS invoice.
- **Priority.** Won't-for-v1.
- **Reason / dependency.** The POS path does not forward `agentId`; the invoice agent defaults to the
  logged-in user. **Backend change required:** forward `agentId` to the invoice on the POS path.
  **v1 workaround:** have the target agent be the authenticated user, or use the back-office
  invoice flow (out of POS scope).
- **Traceability.** UC-C5 (notes).

### FR-SELL-9 — Suspend / hold (park) & recall a sale

- **Statement.** The client should let a cashier park an in-progress basket locally and recall it
  later, **without** posting to the server.
- **Priority.** Should.
- **Acceptance criteria.**
  - "Park" saves the cart (lines, `customerId`, `agentId`, `currency`) in **local** state only and
    clears the active basket; "Recall" reloads it.
  - The UI states a parked basket lives only on this device and is lost if the app/device is lost
    (no server persistence).
  - The client must **not** emulate hold by posting a sale and reversing it (even though a whole-sale
    `/reverse` now exists, §12 #2 — posting-then-reversing a real invoice is not a park mechanism).
- **Note.** This is **absent from the API** (no draft/park endpoint), not a §12 gap; a future
  server-side draft-sale endpoint would supersede the local-only approach.
- **Traceability.** UC-C9.

### FR-SELL-10 — Handle the five sale-rejection branches distinctly

- **Statement.** The client must branch on HTTP status (never message text) and apply the correct
  cure for each `POST /pos/sales` rejection.
- **Priority.** Must.
- **Acceptance criteria.**
  - `409` "session not OPEN" → terminal; prompt to open a fresh session and re-ring (no retry).
  - `400` "Product has no price (BR-SALES-03)" → terminal; remove line or escalate price-list fix
    (a higher `unitPrice` does **not** help).
  - `403` → terminal; compare `auth/me` codes and escalate (the message never names the code).
  - `400` validation → fix the listed field(s) and resend.
  - `409` credit-limit exceeded → terminal; switch to the cash customer or get a
    `SALES.CREDIT.OVERRIDE` supervisor.
  - `404`/`415`/`422` handled per their cause; only the optimistic-lock `409` string is auto-retried.
- **Traceability.** UC-E1, `§09 §11`, `§11 §2.1`.

---

## 5.5 Pricing & Tax (server-authoritative)

The server prices and VATs every line and computes all totals; the client **previews**, the server
**prices**. The `unitPrice` the client sends is ignored. Source: `§04`, `§09 §4`, `§12 #4`.

### FR-PRICE-1 — Client-side preview mirrors the server algorithm

- **Statement.** The client should preview line nets, VAT, and the basket total using the documented
  tax-exclusive algorithm (rounding HALF_UP at each boundary, 0-dp working scale) so the previewed
  total matches the server's `grossTotalAmount`.
- **Priority.** Should.
- **Acceptance criteria.**
  - For a STANDARD-rated line the preview matches the server (e.g. 1,000 × 3, 18% → net 3,000, VAT
    540, gross 3,540).
  - ZERO_RATED / EXEMPT products preview VAT = 0.
- **Traceability.** `§04 §6`, `§09 §4`.

### FR-PRICE-2 — Server totals are the source of truth on the receipt

- **Statement.** The client must print the receipt from the returned `SalesInvoiceDto` totals (and,
  where needed, the lines read endpoint), never from its own preview.
- **Priority.** Must.
- **Acceptance criteria.**
  - The amount charged on the receipt is `grossTotalAmount`; the VAT block is parsed from
    `taxSummary`; per-line prices come from `unitPriceAmount` on the lines endpoint when used.
  - Any divergence between preview and server is resolved in favour of the server values.
- **Traceability.** `§09 §5`, `§09 §8`, `§12 #4`.

### FR-PRICE-3 — Currency picker constrained to enabled currencies

- **Statement.** The client must source the sale `currency` from
  `GET /api/v1/fx/currencies/enabled?companyUid=…[&branchUid=…]`, default to `resolvedDefault`, and
  constrain the picker to the `enabled` codes.
- **Priority.** Must.
- **Acceptance criteria.**
  - The picker shows only enabled codes; the field is pre-filled with `resolvedDefault`.
  - A `422` `CurrencyNotEnabledException` is treated as terminal (pick an enabled code; no blind
    retry).
  - Without `CURRENCY.VIEW`, the client falls back to the company `baseCurrency` and warns that a
    sale may still 422 if that code is not enabled.
- **Traceability.** `§04 §5`, UC-E4.

### FR-PRICE-4 — Single-currency document; no per-line FX

- **Statement.** The client must treat the chosen `currency` as both the invoice header currency and
  the tender currency (every tender is forced to the document currency, §12 #3), with no per-line FX.
- **Priority.** Must.
- **Acceptance criteria.** All line prices/VAT/totals display in the one document currency; the
  client offers no per-line currency control and no rate maintenance (back-office only).
- **Traceability.** UC-E4 (notes), `§04 §5.3`.

---

## 5.6 Customers

The POS uses the **shared** customer master; a sale takes a **numeric** `customerId`. The client
cannot read credit status. Source: `§05`, UC-C3.

### FR-CUST-1 — Search / select a customer

- **Statement.** The client must search customers via `GET /api/v1/customers?companyId=&q=…` and use
  the selected customer's numeric **`id`** as `customerId` on the sale.
- **Priority.** Must.
- **Acceptance criteria.**
  - `q` matches name (contains, case-insensitive) or exact `tin`/`phone`/`code`; the client filters
    to `status == "ACTIVE"`.
  - The selected customer's `id` (a JSON string on the wire) is used as `customerId`.
- **Traceability.** `§05` (search/list), UC-C3.

### FR-CUST-2 — Default walk-in / cash customer

- **Statement.** The client must support a configured default **cash / walk-in** customer
  (`customerKind = CASH_WALK_IN`) for anonymous sales and pre-select it.
- **Priority.** Must.
- **Acceptance criteria.**
  - A walk-in sale rings against the configured cash customer's id without forcing a per-sale search.
  - The walk-in id is held in client config (created once per company; not a POS endpoint).
- **Traceability.** UC-C1 (preconditions), UC-C3.

### FR-CUST-3 — Create a customer on the fly (permission-gated)

- **Statement.** When the operator holds `CUSTOMER.MANAGE`, the client may create a customer via
  `POST /api/v1/customers` and use the returned `id` on the next sale.
- **Priority.** Should.
- **Acceptance criteria.**
  - The quick form sends `{ companyId, partyType, displayName, customerKind, phone }`, preferring
    `INDIVIDUAL` + `CASH_WALK_IN` to avoid the BUSINESS `tin` requirement (`BR-PARTY-04`).
  - The "New customer" button is hidden when `CUSTOMER.MANAGE` is absent (denied → generic `403`).
  - Because create is **not** idempotent, on a timeout the client re-searches by phone and reuses a
    match before creating again.
- **Traceability.** `§05` (create), UC-C3.

### FR-CUST-4 — Credit vs cash interaction (treat finalise 409 as the gate)

- **Statement.** The client must rely on the synchronous sale-finalise `409` as the authoritative
  credit gate, since it cannot read `creditStatus`/`manualHold`.
- **Priority.** Must.
- **Acceptance criteria.**
  - Selling to a `CREDIT_ACCOUNT` customer is allowed but the client communicates it is still rung
    as a **fully-paid CASH** sale (POS is not an on-account channel).
  - A credit-limit `409` shows the server's user-safe message; the cure offered is "switch to cash
    customer" or "supervisor with `SALES.CREDIT.OVERRIDE`".
- **Traceability.** `§05` (credit ↔ POS), UC-C3, UC-E1 (case e).

### FR-CUST-5 — Sell "on account" to a credit customer

- **Statement.** Ring a charge/on-account sale at the POS.
- **Priority.** Won't-for-v1.
- **Reason / dependency.** POS always rings a fully-paid CASH sale regardless of `customerKind`.
  **Backend change required:** an on-account POS path. **v1 workaround:** use the back-office credit
  invoice flow (out of POS scope).
- **Traceability.** UC-C3 (notes), UC-C-not-supported table.

---

## 5.7 Payments & Tender

> **STATUS UPDATE (2026-06-20, §12 #3 CLOSED, `f08fb08`).** Multi-tender / non-cash payments now
> **ship**: `POST /pos/sales` accepts an optional `tenders[]` list (`CASH`/`CARD`/`MOBILE_MONEY`/
> `CHEQUE`, split, sum ≥ gross), each posting a real ledger payment. The single-exact-CASH path
> below remains the **default** (omit `tenders`), so this area's framing is now a *default*, not the
> only option. FR-PAY-2..5 below were authored as Won't-for-v1 against the old gap; their **factual
> "Backend change required" basis is now CLOSED** — flagged inline for the owner to re-prioritise
> (the MoSCoW/scope decision is left for the owner to re-make deliberately). PCI / card-data handling
> (use an external certified terminal) remains a valid client-side concern.

The default path is **single exact CASH**: the server adds one CASH tender for exactly the gross;
tendered amount and change are client-side display only. Source: `§09 §6`, `§12 #3`, UC-E4.

### FR-PAY-1 — Single exact CASH tender (default path)

- **Statement.** When no `tenders[]` list is supplied, the client must treat the POS sale as paid by
  a single CASH tender equal to the gross, computing change locally. (Multi-tender via `tenders[]` is
  now also available — §12 #3 CLOSED, FR-PAY-2..4; this requirement is the default no-`tenders` path.)
- **Priority.** Must.
- **Acceptance criteria.**
  - The client sends an optional `tenderedAmount` (cash given) for **receipt printing only**; it
    does not affect the payment amount or get stored on the invoice.
  - Change is computed as `tenderedAmount − grossTotalAmount` (client-side); the ledger
    `changeAmount` is null on the POS path and is not relied upon.
  - The client surfaces no ledger over-tender / change row (none is written server-side).
- **Traceability.** `§09 §6`, `§09 §9`, UC-C1, `§12 #3`.

### FR-PAY-2 — Card tender

- **Statement.** Accept card payment at the POS.
- **Priority.** Won't-for-v1.
  > **NOTE (2026-06-20): §12 #3 is now CLOSED (`f08fb08`) — the backend change this row was gated on
  > has shipped; this Won't-for-v1 priority should be revisited by the owner.** `PosSaleRequest` now
  > carries an optional `tenders[]` list and posts a real `CARD` ledger payment. (PCI / card-data
  > handling via an external certified terminal remains a client-side concern.)
- **Reason / dependency (HISTORICAL — now resolved).** The POS orchestrator *formerly* hard-coded
  `TenderType.CASH`. The backend change this required — `PosSaleRequest` carrying a tender list and
  persisting it (`§12 #3` recommended fix) — has **shipped**.
- **Traceability.** `§12 #3`, UC-B10, UC-C-not-supported table.

### FR-PAY-3 — Mobile-money / cheque tender

- **Statement.** Accept mobile-money or cheque payment at the POS.
- **Priority.** Won't-for-v1.
  > **NOTE (2026-06-20): §12 #3 is now CLOSED (`f08fb08`) — the POS tender list has shipped; this
  > Won't-for-v1 priority should be revisited by the owner.** The POS path now emits
  > `TenderType.{MOBILE_MONEY,CHEQUE}` from the `tenders[]` list.
- **Reason / dependency (HISTORICAL — now resolved).** Same as FR-PAY-2 —
  `TenderType.{MOBILE_MONEY,CHEQUE}` exist in the payment layer; the POS path *formerly* never
  emitted them. The required POS tender list has **shipped**.
- **Traceability.** `§12 #3`, UC-B10.

### FR-PAY-4 — Split tender (part cash + part card/other)

- **Statement.** Accept more than one tender on a single POS sale.
- **Priority.** Won't-for-v1.
  > **NOTE (2026-06-20): §12 #3 is now CLOSED (`f08fb08`) — split tender has shipped; this
  > Won't-for-v1 priority should be revisited by the owner.** The `tenders[]` list accepts multiple
  > tenders with sum-≥-gross validation.
- **Reason / dependency (HISTORICAL — now resolved).** The POS path *formerly* added exactly one
  CASH payment for the gross. The backend change required — a multi-tender list with sum-≥-gross
  validation and change on CASH (`BR-SALES-07` already exists in the payment layer) — has **shipped**.
- **Traceability.** `§12 #3`, UC-B10.

### FR-PAY-5 — Over-tender with change recorded at the ledger

- **Statement.** Record over-tender and change on the invoice/ledger.
- **Priority.** Won't-for-v1.
  > **NOTE (2026-06-20): §12 #3 is now CLOSED (`f08fb08`) — the `tenders[]` list now records real
  > per-tender ledger payments (and validates tender sum ≥ gross), so this row's basis is largely
  > superseded; the owner should revisit it.** A residual nuance remains: per `§12 #3`, change for a
  > CASH over-tender is still computed on the **client** (`tenderedAmount` is a receipt hint, the
  > ledger `changeAmount` is not persisted on the POS path), so the literal "change recorded at the
  > ledger" ask may still be partly open. Left for the owner to confirm/re-scope.
- **Reason / dependency (HISTORICAL — superseded).** The POS payment *formerly* equalled gross
  exactly with no `changeAmount` written. Multi-tender now persists real per-tender payments; the
  client still prints CASH change locally (FR-PAY-1).
- **Traceability.** `§09 §6`, `§12 #3`, UC-C-not-supported table.

---

## 5.8 Receipts

A counter receipt is composed from the `201` `SalesInvoiceDto` (+ optional lines/payments reads).
POS invoices use the shared `INV-####` series. Source: `§09 §5`, `§09 §8–§9`, UC-C7, UC-C8.

### FR-RCPT-1 — Print a receipt from the 201 response

- **Statement.** The client must print a receipt immediately from the returned `SalesInvoiceDto`,
  optionally enriching lines/tender via the sales-invoice read endpoints.
- **Priority.** Must.
- **Acceptance criteria.**
  - Header (`invoiceNumber`, `finalisedAt`, `agentName`, `customerName`), totals
    (`netTotalAmount`, `vatTotalAmount`, `grossTotalAmount`, per-band VAT from parsed `taxSummary`),
    and a tender block (paid = `tenderedAmount`, change = `tenderedAmount − grossTotalAmount`) all
    render.
  - The receipt shows the `INV-####` number (there is no separate POS receipt-number series).
  - Optional line detail comes from `GET /api/v1/sales-invoices/uid/{uid}/lines` (needs
    `SALES.INVOICE.VIEW`); if unavailable, the client prints from its submitted lines + header
    totals.
- **Traceability.** `§09 §8–§9`, UC-C7.

### FR-RCPT-2 — Reprint / look up a past receipt

- **Statement.** The client should find an earlier sale and reprint it via
  `GET /api/v1/sales-invoices/uid/{uid}` (and `/lines`, `/payments`) or by searching
  `GET /api/v1/sales-invoices?companyId=&q=…`.
- **Priority.** Should.
- **Acceptance criteria.**
  - A reprint renders identically to the original (FR-RCPT-1) and changes nothing server-side.
  - The reprint always reflects the **original** sale (there is no refunded/voided state at the
    POS).
  - Printed change on a reprint uses a client-stored `tenderedAmount` (the ledger `changeAmount` is
    null for POS).
- **Traceability.** UC-C8, `§09 §8`.

### FR-RCPT-3 — Gift receipt (price-suppressed rendering)

- **Statement.** The client should offer a gift-receipt rendering that omits prices/VAT/totals.
- **Priority.** Could.
- **Acceptance criteria.**
  - The gift receipt is the same document with price columns and totals suppressed (a client-side
    rendering choice; there is no "gift receipt" API mode).
- **Traceability.** UC-C7 (notes).

### FR-RCPT-4 — Persist the sale result locally to make reprint idempotent

- **Statement.** The client must persist the `201` `SalesInvoiceDto` keyed by its local transaction
  id so a reprint never triggers a second `POST`.
- **Priority.** Must.
- **Acceptance criteria.**
  - Reprinting a stored result issues no new `POST /pos/sales`.
- **Traceability.** `§11 §4.2`, UC-E2.

---

## 5.9 Returns / Refunds

> **STATUS UPDATE (2026-06-20, §12 #2 CLOSED, `f08fb08`).** A **whole-sale** POS reversal/refund/void
> now **ships**: `POST /pos/sales/uid/{uid}/reverse` (perm `POS.SALE.VOID`, OPEN session, FINALISED,
> POS-origin) atomically reverses revenue + VAT + cash + stock + COGS and drops the sale out of the
> drawer. The "there is no POS reversal" framing below is now **wrong for open-session whole-sale
> refunds**. **Still deferred:** partial / line-level refunds (ADR-0042), and closed-session sales
> (use the back-office void). FR-RET-2/FR-RET-3 below were authored Won't-for-v1 against the old gap;
> their factual basis is now CLOSED for whole-sale — flagged inline for the owner to re-prioritise.

The legacy cash-drawer `REFUND` payout (drawer bookkeeping only) remains, but the first-class
mechanism for an open-session whole-sale refund is now the `/reverse` endpoint; closed-session sales
and partial returns still need a back-office correction. Source: `§10`, `§12 #2`, UC-B9, UC-D1, UC-D2.

### FR-RET-1 — Record a cash refund as a drawer payout (with explicit caveat)

- **Statement.** When cash must be returned, the client must record it as a `REFUND` **payout** on
  an OPEN session (FR-SHIFT-6) and clearly state this is **drawer bookkeeping only**.
- **Priority.** Must.
  > **NOTE (2026-06-20): §12 #2 is now CLOSED (`f08fb08`) — for an open-session whole-sale refund the
  > first-class lever is now `/reverse` (FR-RET-2), which posts the full stock/VAT/revenue reversal.
  > The owner should revisit whether the `REFUND`-payout-only flow is still the primary refund path
  > or a fallback (e.g. closed-session or partial refunds).** The drawer-bookkeeping facts below
  > remain accurate for the payout itself.
- **Acceptance criteria.**
  - The refund flow records `{ payoutType: "REFUND", amount, reason }` (the client puts the original
    `INV-####` and SKU/qty in `reason` for later back-office matching).
  - The UI explicitly states the payout posts **no** stock-in, VAT/revenue reversal, or AR credit,
    and that a back-office correcting entry is required to make the ledger correct.
- **Traceability.** UC-D1, `§10 §3`, `§12 #2`.

### FR-RET-2 — Refund / void / reverse a POS sale at the till

- **Statement.** Reverse a POS sale (stock back in + VAT/revenue reversal + customer credit) from
  the till.
- **Priority.** Won't-for-v1.
  > **NOTE (2026-06-20): §12 #2 is now CLOSED (`f08fb08`) — the exact backend change this row called
  > for has shipped; this Won't-for-v1 priority should be revisited by the owner.** The first-class
  > `POST /pos/sales/uid/{uid}/reverse` endpoint now exists (perm `POS.SALE.VOID`; requires an OPEN
  > session, a FINALISED POS-origin invoice), atomically reversing revenue + VAT + cash + stock +
  > COGS and dropping the sale out of the drawer — i.e. a whole-sale at-till refund is now buildable.
  > (**Partial / line-level** refunds remain deferred — see FR-RET-3 / §12 #5; closed-session sales
  > still use the back-office void.)
- **Reason / dependency (HISTORICAL — now resolved for whole-sale).** There was *formerly* no POS
  reverse endpoint, and the general returns path requires a `deliveryUid`+`deliveryLineUid` that a
  DIRECT-origin POS invoice never has. The backend change required — a first-class
  `POST /pos/sales/uid/{uid}/reverse` gated by `POS.SALE.VOID`, atomically reversing stock + GL — has
  **shipped**. **Legacy v1 workaround:** FR-RET-1 (drawer payout) + back-office correction.
- **Acceptance criteria.** The client offers no void/refund/reverse control on a POS invoice; it
  routes the merchandise/accounting side to the back office.
- **Traceability.** UC-B9, UC-D1, `§12 #2`, `§10 §2/§5`.

### FR-RET-3 — Correct a mis-rung sale at the till

- **Statement.** Cancel/edit a finalised mis-rung POS invoice from the till.
- **Priority.** Won't-for-v1.
  > **NOTE (2026-06-20): §12 #2 AND §12 #1 are now CLOSED (`f08fb08`) — both factors this row was
  > gated on have shipped; this Won't-for-v1 priority should be revisited by the owner.** A
  > whole-sale `/reverse` now lets the till void a mis-rung **open-session** invoice (then re-ring
  > corrected), and `Idempotency-Key` makes the re-ring safe to retry. (Editing a finalised invoice
  > in place is still not a feature; **partial** line corrections remain deferred, §12 #5; and a
  > closed-session sale still needs the back-office void.)
- **Reason / dependency (HISTORICAL — now resolved for whole-sale void).** There was *formerly* no
  POS undo (`§12 #2`), compounded by no idempotency (`§12 #1`). The backend change required — the
  same reverse/void endpoint as FR-RET-2 — has **shipped**. **Legacy v1 workaround:** record any cash
  returned as a `REFUND` payout, re-ring the corrected basket as a **new** sale, and have finance
  post a correcting entry against the wrong invoice (both invoices then exist).
- **Acceptance criteria.**
  - When an error is spotted **before** submit, the client simply edits the cart (nothing to undo).
  - When spotted **after** submit, the client does not attempt to edit/cancel the invoice and guides
    the workaround above.
- **Traceability.** UC-D2, `§12 #1`, `§12 #2`.

### FR-RET-4 — No negative lines / negative sale

- **Statement.** The client must not attempt to model a return as a negative-line "negative sale".
- **Priority.** Must.
- **Acceptance criteria.** Line quantities are constrained `>= 0.0001`; the client offers no
  negative-quantity entry (the POS path cannot post negative lines).
- **Traceability.** UC-D1 (notes), `§09 §2.2`.

---

## 5.10 Stock Visibility (advisory)

On-hand reads are **point-in-time, not reserved**; stock decrement is asynchronous (~1s). The POS
endpoint does not hard-block overselling. Source: `§06`, UC-C6.

### FR-STK-1 — Advisory on-hand display

- **Statement.** The client should show advisory in-stock / out-of-stock and quantity for a product
  using `GET /api/v1/stock/on-hand` and `GET /api/v1/stock/on-hand/by-product/uid/{uid}?companyId=…`.
- **Priority.** Should.
- **Acceptance criteria.**
  - On-hand is displayed as **advisory** with a clear "not reserved" caveat; the client still allows
    ringing regardless (the check does not block the sale).
  - A token with no active branch returns `409` and the client surfaces the scope issue rather than
    breaking the sell flow.
  - The client re-queries after a short delay when displaying live counts (decrement is async).
- **Note (409 family).** The no-active-branch `409` is **terminal** — the cure is re-scope (send a
  valid `X-Branch-Uid`) or re-authenticate; the client must **not** auto-retry it. It is a **different
  409 family** from the **retryable optimistic-lock 409** (the one whose `errors[0]` is exactly the
  optimistic-lock string, per FR-OFF-5 / FR-SELL-10). Branch on the message only to distinguish the
  retryable optimistic-lock case; treat this scope `409` as terminal.
- **Traceability.** `§06 §1/§3`, UC-C6, `§11 §2.1`.

### FR-STK-2 — Client-side soft over-sell guard

- **Statement.** The client could warn (not block) when ringing more than the advisory on-hand.
- **Priority.** Could.
- **Acceptance criteria.** A soft warning is shown when cart qty exceeds advisory on-hand; the
  cashier may proceed (no hard reservation exists in the API).
- **Traceability.** UC-C6 (notes — build your own guard).

### FR-STK-3 — Lot / serial selection at the POS

- **Statement.** Direct which specific lot/serial is issued for a line.
- **Priority.** Won't-for-v1.
- **Reason / dependency.** `POST /pos/sales` takes no batch/serial parameter — the server consumes
  lots FEFO and assigns serials internally. **Backend change required:** lot/serial parameters on
  the POS line. **v1:** lot/serial display is advisory only (FR-STK-1).
- **Traceability.** UC-C6 (notes).

---

## 5.11 Reporting (X / Z reads)

The POS surfaces the per-session cash reports already defined in §5.2. There is no dedicated POS
sales-report endpoint; the shared sales-invoice list is used for reconciliation/lookup. Source:
`§08 §7/§9`, UC-B4, UC-B6, UC-C8.

### FR-RPT-1 — X-read report view

- **Statement.** The client must present the mid-shift X-read (FR-SHIFT-8) as a printable/displayable
  drawer report.
- **Priority.** Must.
- **Acceptance criteria.** The X-read shows float, sales total, payouts total, expected cash, and
  invoice count, labelled as a cash-drawer (not ledger) report.
- **Traceability.** `§08 §7`, UC-B4.

### FR-RPT-2 — Z-read report view

- **Statement.** The client must present the Z-read (FR-SHIFT-10) on reconcile as the end-of-shift
  report, including the variance and `varianceJournalId`.
- **Priority.** Must.
- **Acceptance criteria.** The Z-read shows counted vs expected cash, variance, and the variance
  journal id (or null for a zero variance).
- **Traceability.** `§08 §9`, UC-B6.

### FR-RPT-3 — Session sales lookup (no POS-specific list)

- **Statement.** The client should let the operator look up the shift's sales via the shared
  `GET /api/v1/sales-invoices?companyId=…` (filtered client-side), since there is no
  "list my session's sales" endpoint.
- **Priority.** Should.
- **Acceptance criteria.**
  - The client lists/searches invoices for the company and filters client-side (POS sales carry
    `origin=POS`, but the list exposes no `origin` filter); lines/payments are fetched per uid.
- **Traceability.** UC-C8 (notes), `§09 §8`.

---

## 5.12 Offline Mode & Sync

> **STATUS UPDATE (2026-06-20, §12 #1 CLOSED, `f08fb08`).** **Sale idempotency now ships** (optional
> `Idempotency-Key` header). With a key, a client can **safely replay** queued sales on reconnect —
> the server returns the original invoice on replay, so retry safety no longer rests entirely on the
> client's reconcile-before-resend discipline (which is now the **no-key fallback**). The
> requirements below were authored against the old no-idempotency gap; the reconcile-before-resend
> machinery (FR-OFF-1..5) remains a valid belt-and-braces / no-key path, but **same-key resend is now
> the primary safe-retry mechanism** — the owner may wish to re-weight these. **Still open:**
> client-side **offline / batch ingest** (§12 #6) — there is no server batch endpoint, so the
> queue-and-replay logic still lives on the client.

The API is a stateless JWT server with **no offline/batch ingest** (§12 #6, still open); **sale
idempotency** is now available via the `Idempotency-Key` header (§12 #1, CLOSED). Source: `§11 §4`,
`§12 #1`, `§12 #6`, UC-E2.

### FR-OFF-1 — Per-basket durable transaction id

- **Statement.** The client must generate and persist a durable local transaction id per basket
  **before** the first `POST /pos/sales`, send it as the `Idempotency-Key` header (§12 #1, CLOSED) on
  every attempt of that same sale, and use a fresh id only for a genuinely new sale.
- **Priority.** Must.
  > **NOTE (2026-06-20): §12 #1 is now CLOSED (`f08fb08`).** Sending the durable id as
  > `Idempotency-Key` now gives **server-side** dedupe (replay returns the original invoice), not
  > just client-side. `X-Request-Id` remains **log-correlation only** and does **not** dedupe — the
  > id that earns the guarantee is `Idempotency-Key`. The owner may re-weight FR-OFF-1..5 now that
  > safe-retry is primarily server-enforced.
- **Acceptance criteria.**
  - The transaction id survives app restart and is reused (as `Idempotency-Key`) for all retries of
    the same logical sale.
  - A UI double-tap or in-flight resend never fires two committing `POST`s for one basket
    (client-side dedupe **plus** server-side idempotency).
- **Traceability.** `§11 §4.2` (#3), `§12 #1`, UC-E2.

### FR-OFF-2 — Never auto-retry a sale on an ambiguous outcome

- **Statement.** On a timeout, dropped connection, `500`, or any non-clean-`201`/non-clean-terminal
  outcome, the client must classify the result as **AMBIGUOUS** and must not auto-resend.
- **Priority.** Must.
- **Acceptance criteria.**
  - No automatic resend occurs after an ambiguous sale outcome; the operator is shown a "verifying
    last sale" state that triggers reconcile (FR-OFF-3).
- **Traceability.** `§11 §4.2` (#1), `§12 #1`, UC-E2.

### FR-OFF-3 — Reconcile before resend

- **Statement.** After an ambiguous outcome the client must reconcile — confirm the session is still
  OPEN (`GET /pos/sessions/uid/{uid}`) and search `GET /api/v1/sales-invoices?companyId=…` (newest
  first) for a matching finalised invoice — before deciding to resend.
- **Priority.** Must.
  > **NOTE (2026-06-20): §12 #1 is now CLOSED (`f08fb08`).** Reconcile-before-resend is now the
  > **no-key fallback**: a client that sends an `Idempotency-Key` (FR-OFF-1) can simply resend with
  > the **same key** on an ambiguous outcome (the server returns the original invoice, or a retryable
  > `409` if still in flight) instead of doing out-of-band reconciliation. The owner may re-weight
  > this requirement accordingly; the reconcile path remains valid for the no-key case.
- **Acceptance criteria.**
  - If a matching invoice is found, the client treats the original as **succeeded**, reprints from
    it, and does **not** resend.
  - If no match is found and the session is OPEN, the client resends **once** (same basket, same
    transaction id) and re-evaluates.
  - The client matches on amount + line snapshot + timestamp window (the X-read only confirms
    whether counts moved, not which invoice).
- **Traceability.** `§11 §4.2` (#2), UC-E2.

### FR-OFF-4 — Local offline sale queue with serial replay

- **Statement.** The client should let cashiers stage sales in a **local** queue while offline and,
  on reconnect, replay them **one at a time**, applying reconcile-before-resend to each and stopping
  on the first terminal error.
- **Priority.** Should.
- **Acceptance criteria.**
  - Queued sales are each stamped with a durable transaction id (FR-OFF-1).
  - Replay is serial (never parallel); the queue halts on the first terminal error for cashier
    review.
  - Before replaying, the client re-checks the session is still OPEN; if not, queued sales are
    escalated as an exception (the API has no offline/batch ingest, and a closed session rejects
    sales).
- **Traceability.** `§11 §4.3`, UC-E2.

### FR-OFF-5 — Branch retries strictly on HTTP status

- **Statement.** The client must drive all retry/terminal decisions off the HTTP status code, not
  the message text.
- **Priority.** Must.
- **Acceptance criteria.**
  - `400`/`403`/`404`/`415`/`422` → terminal (fix, do not resend same bytes); `401` → refresh/login
    then reconcile-before-resend; `409` → retry **only** when `errors[0]` is exactly the
    optimistic-lock string; `500`/network → ambiguous → reconcile.
- **Traceability.** `§11 §2.1`, `§11 §5`, UC-E1.

### FR-OFF-6 — Offline catalog & customer cache

- **Statement.** The client should serve product/unit/customer lookups from its local cache while
  offline so a cashier can keep building baskets.
- **Priority.** Should.
- **Acceptance criteria.**
  - Catalog/unit/walk-in-customer data loaded at shift start remains usable offline for cart
    building; only the sale POST requires connectivity.
- **Traceability.** `§03` (catalog-load flow), `§11 §4.3`.

### FR-OFF-7 — Server-side idempotent / exactly-once sale posting

- **Statement.** Guarantee exactly-once sale posting via a server idempotency key.
- **Priority.** Won't-for-v1.
  > **NOTE (2026-06-20): §12 #1 is now CLOSED (`f08fb08`) — the exact backend change this row called
  > for has shipped; this Won't-for-v1 priority should be revisited by the owner (this is arguably now
  > a "Must" delivered by the backend).** An optional `Idempotency-Key` header is accepted and
  > persisted unique per company (`pos_sale_idempotency`, V70, reserve-before-process); replay returns
  > the **original** invoice (still HTTP `201` — match on the returned `uid`), and an in-flight
  > duplicate gets a retryable `409`.
- **Reason / dependency (HISTORICAL — now resolved).** There was *formerly* no `Idempotency-Key`
  header or `clientSaleRef` on the sale path; `X-Request-Id` is correlation-only. The backend change
  required — accept and persist an idempotency key unique per company and return the original invoice
  on replay — has **shipped**. **Legacy v1 workaround:** the reconcile-before-resend discipline
  (FR-OFF-1..5), now the no-key fallback.
- **Traceability.** `§12 #1`, `§11 §4.4`.

---

## 5.13 Settings & Admin (provisioning is via the ERP, not the POS)

The POS client owns no provisioning. Operators, roles, grants, branch assignments, tills' master
data, cash accounts, price lists, currencies, and GL config are all created/maintained through ERP
back-office endpoints. Source: `§01 §10`, UC-A1, UC-A5 (not-supported table).

### FR-ADMIN-1 — No in-POS user/role/branch provisioning

- **Statement.** The client must **not** implement operator sign-up, role/permission management, or
  branch assignment; it must direct those to the ERP back office.
- **Priority.** Must.
- **Acceptance criteria.**
  - There is no self-registration or in-POS user/role/grant/branch-assignment UI.
  - When a `403`/`hasBranch=false` indicates a provisioning gap, the client shows guidance to
    contact an admin (who runs UC-A1 in the ERP).
- **Traceability.** `§01 §10`, UC-A1, UC-A5 (self-service sign-up not supported).

### FR-ADMIN-2 — Local device/terminal settings

- **Statement.** The client must keep purely-local device settings (API base URL, default till,
  default walk-in customer id, default currency fallback, printer config) in client config only.
- **Priority.** Must.
- **Acceptance criteria.**
  - Device settings persist locally and are never posted to the ERP (the API exposes no
    `code`/`defaultPriceListId`/`deviceTerminalId` on a till; those are managed outside this API).
  - Changing device settings requires no server call.
- **Traceability.** UC-A3 (notes — code/price-list/device id not settable), `§07` (integration
  notes).

### FR-ADMIN-3 — Surface provisioning/scope errors actionably

- **Statement.** The client must translate `403` (generic), `404` (bad id), and `hasBranch=false`
  into actionable operator guidance, using `GET /auth/me` to diagnose missing codes.
- **Priority.** Should.
- **Acceptance criteria.**
  - On a `403`, the client compares `auth/me` effective codes against the required `POS.*` set and
    tells the operator which capability is missing (without claiming the server named it).
  - On a scope mismatch, the client suggests checking the grant's company/branch against the acting
    scope.
- **Traceability.** `§01 §9`, UC-A1 (notes), UC-E1 (case c).

---

## 5.14 Requirements traceability summary

> **STATUS NOTE (2026-06-20, `f08fb08`/ADR-0042).** Several "Won't-for-v1 (backend dependency)"
> entries below were gated on gaps that are now **CLOSED**: **§12 #1** (idempotency → FR-OFF-7),
> **§12 #2** (POS reverse/void → FR-RET-2, FR-RET-3 for whole-sale), and **§12 #3** (tender list →
> FR-PAY-2..5). **§12 #4** (server-authoritative pricing → FR-SELL-5) is now **by design**, not a
> gap (so FR-SELL-5's *manual override* is a deliberate non-feature, not a blocked-by-gap deferral).
> The MoSCoW columns and the rollup below are **left as authored** — the owner should re-prioritise
> the now-unblocked rows deliberately. Items genuinely still deferred: **partial / line-level
> refunds** (§12 #5), **offline / batch ingest** (§12 #6), and the non-§12 deferrals (FR-SELL-6/8,
> FR-CUST-5, FR-SHIFT-7/12, FR-STK-3).

| Area | Must | Should | Could | Won't-for-v1 (backend dependency) |
|------|------|--------|-------|-----------------------------------|
| Auth & Session | FR-AUTH-1..6 | FR-AUTH-7, FR-AUTH-8 | — | — |
| Shift & Till | FR-SHIFT-1, 4, 5, 6, 8, 9, 10 | FR-SHIFT-2, 11 | FR-SHIFT-3 | FR-SHIFT-7 (CASH_IN type), FR-SHIFT-12 (re-open endpoint) |
| Catalog & Search | FR-CAT-1..4 | FR-CAT-5 | — | — |
| Cart & Selling | FR-SELL-1, 2, 3, 4, 10 | FR-SELL-7, 9 | — | FR-SELL-5 (`unitPrice` override), FR-SELL-6 (rule application), FR-SELL-8 (forward agentId) |
| Pricing & Tax | FR-PRICE-2, 3, 4 | FR-PRICE-1 | — | — |
| Customers | FR-CUST-1, 2, 4 | FR-CUST-3 | — | FR-CUST-5 (on-account POS path) |
| Payments & Tender | FR-PAY-1 | — | — | FR-PAY-2..5 (POS tender list `§12 #3`) |
| Receipts | FR-RCPT-1, 4 | FR-RCPT-2 | FR-RCPT-3 | — |
| Returns / Refunds | FR-RET-1, 4 | — | — | FR-RET-2, FR-RET-3 (reverse/void endpoint `§12 #2`) |
| Stock Visibility | — | FR-STK-1 | FR-STK-2 | FR-STK-3 (lot/serial param) |
| Reporting | FR-RPT-1, 2 | FR-RPT-3 | — | — |
| Offline & Sync | FR-OFF-1, 2, 3, 5 | FR-OFF-4, 6 | — | FR-OFF-7 (idempotency key `§12 #1`) |
| Settings & Admin | FR-ADMIN-1, 2 | FR-ADMIN-3 | — | — |

**Backend-gap rollup (the Won't-for-v1 set, with the single change that unblocks each):**

- `§12 #1` (sale idempotency) → unblocks **FR-OFF-7** (exactly-once posting). **— CLOSED `f08fb08`
  (Idempotency-Key header); FR-OFF-7's backend dependency is delivered.**
- `§12 #2` (POS reverse/void) → unblocks **FR-RET-2**, **FR-RET-3** (refund/void/correct at the
  till). **— CLOSED `f08fb08` for whole-sale (`/reverse`, perm `POS.SALE.VOID`, open session);
  FR-RET-2/3 whole-sale dependency is delivered. Partial / line-level refunds (§12 #5) still
  deferred.**
- `§12 #3` (tender list) → unblocks **FR-PAY-2..5** (card/mobile/cheque/split/over-tender). **—
  CLOSED `f08fb08` (`tenders[]` list); FR-PAY-2..4 delivered; FR-PAY-5's ledger-change nuance may
  remain (client computes CASH change).**
- `§12 #4` (`unitPrice` override) → unblocks **FR-SELL-5** (manual price override). **— RESOLVED BY
  DESIGN: server-authoritative pricing is a deliberate guarantee, `unitPrice`/`agentId` are now
  optional/informational. FR-SELL-5 (manual override) remains a non-feature by choice, not a
  gap-blocked deferral.**
- Other deferrals not catalogued in §12 but absent from the API: **FR-SELL-8** (forward `agentId`),
  **FR-SELL-6** (rule application on the POS path), **FR-CUST-5** (on-account POS), **FR-SHIFT-7**
  (CASH_IN payout), **FR-SHIFT-12** (session re-open), **FR-STK-3** (lot/serial selection).
- Note **FR-SELL-9** (park/recall) is a v1 **Should** delivered as a client-only local feature — it
  is absent from the API but does **not** depend on a backend change for the v1 (local-only) scope.

---

*End of Part 2 — Functional Requirements.*
