# POS Use Cases — Exceptions & Operations

End-to-end **failure and operational** scenarios for an external POS: rejected sales, the network-drop/duplicate-posting hazard, multi-till/branch operation, currency selection, and end-of-day close-out — each grounded in the verified API guide ([§00](../00-overview-and-conventions.md)–[§12](../12-known-limitations.md)).

> **How to read these.** Every step that hits the API names the **method + path** and links the guide section that documents it. Branch your client logic on the **HTTP status code**, never on the message text ([§11 §2](../11-errors-offline-idempotency.md)). The error strings are user-safe display copy and may be reworded between releases.

---

### UC-E1: Sale rejected — the five ways `POST /pos/sales` says no

- **Actor:** cashier (rejection surfaced to them; some cures need a shift supervisor).
- **Goal:** ring a sale, and handle each distinct rejection correctly instead of blind-retrying.
- **Preconditions:** authenticated with a valid bearer token; cashier holds `POS.SALE.CREATE`; a session exists; a basket of lines is staged. `Content-Type: application/json` on every call.
- **Main flow (the happy path this UC deviates from):**
  1. Cashier submits the basket: `POST /api/v1/pos/sales` ([§09](../09-sales-payments-receipts.md)) with `{ sessionUid, customerId, agentId, currency, lines[], tenderedAmount?, notes? }`.
  2. On success → **201** with the finalised `SalesInvoiceDto`; print from it. The rest of this UC is the **non-201** branches.
- **Alternate / exception flows** (each is a real, separately-handled rejection from `PosSaleServiceImpl` / the shared handler — see [§09 §11](../09-sales-payments-receipts.md) and [§11 §2](../11-errors-offline-idempotency.md)):

  | # | Rejection | Status | `errors[0]` shape | Cure |
  |---|-----------|--------|-------------------|------|
  | a | **Session not OPEN** | **409** (`ConflictException`) | `"This POS session is not OPEN."` | The session is CLOSED/RECONCILED (or never opened). **Terminal for these bytes.** Open a fresh session (`POST /api/v1/pos/sessions`, [§08](../08-sessions.md)) and re-ring against the new `sessionUid` — but if the attempt carried an `Idempotency-Key`, keep that key until you know the original did not post ([§11 §4.2a](../11-errors-offline-idempotency.md)). See [UC-E5](#uc-e5-end-of-day-close-out-across-tills). |
  | a2 | **Not enough stock** | **409** (`ConflictException`) | `"Not enough stock of <product> to complete this sale — 3 available, 5 requested. Ask a supervisor to enable backorder if this should be allowed."` | The line would take branch on-hand below zero and the company blocks negative stock — the default, including for a company with **no** sales-settings row ([§06](../06-stock-availability.md), [§09 §3](../09-sales-payments-receipts.md)). **Terminal for these bytes:** reduce the quantity, drop the line, or have a supervisor enable backorder. Nothing was written — the whole sale rolled back. |
  | b | **Product has no price** | **400** (`IllegalArgumentException` from `addLine`) | `"Product has no price on any price list (BR-SALES-03)"` (also: product not sellable / archived) | The product has no `ProductPrice` row for the session's company ([§04 §1](../04-pricing-tax-currency.md)). **Terminal — `unitPrice` in your request does NOT supply the price; it is ignored** ([§12 #4](../12-known-limitations.md)). Remove the line, or have back-office add the product's price; then re-ring. |
  | c | **Missing permission** | **403** (`@PreAuthorize` denial) | `"You do not have permission to perform this action."` (never names the code) | Caller lacks `POS.SALE.CREATE` **or** cannot act in the session's company (`ScopeGuard`). **Terminal — do not retry as-is.** Compare the operator's effective codes (`GET /api/v1/auth/me` → `permissions`, [§01 §6](../01-authentication-and-permissions.md)) against the required `POS.*` set; escalate to provision the grant. |
  | d | **Validation** | **400** (`MethodArgumentNotValidException`) | one entry per bad field, e.g. `"lines: must not be empty"`, `"lines[0].quantity: must be greater than or equal to 0.0001"`, `"sessionUid: must not be blank"`, `"notes: size must be between 0 and 500"` | The request bytes are wrong. **Fix the field(s) listed in `errors[]` and resend the corrected request.** (Empty cart, qty ≤ 0, blank required field, over-long note.) |
  | e | **Credit-control block** (credit customer) | **409** (`IllegalStateException`) | `"Credit limit exceeded for customer <uid>. Limit: …, projected balance: …. Requires SALES.CREDIT.OVERRIDE permission."` | Only when the sale points at a `CREDIT_ACCOUNT` customer whose `existingArBalance + thisGross > creditLimit` ([§05 — credit ↔ POS](../05-customers.md), [§09 §3](../09-sales-payments-receipts.md)). **Terminal — do not blind-retry.** Cure: sell to the cash/walk-in customer instead, or have a supervisor with `SALES.CREDIT.OVERRIDE` ring it. The check is on the **synchronous finalise** path, so the `409` comes back in-line. |

  > Also possible on this endpoint: **404** (unknown `sessionUid`/`customerId`/`productId`/`unitId` — fix the id), **415** (body not `application/json`), **422** (currency not enabled — see [UC-E4](#uc-e4-currency-selection-enabled-currencies-and-the-default)). The auto-retryable 409s are the optimistic-lock string `"This record was modified by another transaction. Please reload and try again."` and the still-processing string `"This sale is still being processed. Please try again in a moment."` (resend the **same** key) ([§11 §2.1](../11-errors-offline-idempotency.md)). **A 409 never releases a durable `Idempotency-Key`** — even the terminal-for-the-bytes ones above ([§11 §4.2a](../11-errors-offline-idempotency.md)).
- **Outcome:** on any non-201, **no invoice is created** and **no stock/GL/AR posting is enqueued** — the whole `processSale` transaction rolls back. The cashier sees a specific, actionable message; the client took the correct branch (fix-and-resend for 400, escalate for 403, open-new-session for 409-not-OPEN, etc.) and did **not** hammer the server.
- **Notes & limitations:**
  - The session-not-OPEN (a), not-enough-stock (a2) and credit-limit (e) cases are **all 409 `IllegalStateException`/`ConflictException`** but are **terminal for the submitted bytes**, not transient. A client that blind-retries every 409 will spam genuinely-rejected sales; a client that treats every 409 as final will do worse and drop a live `Idempotency-Key`. Retry only the optimistic-lock and still-being-processed messages — and **hold the key regardless** ([§11 §2.1 / §4.2a](../11-errors-offline-idempotency.md)).
  - `unitPrice` is **required by validation but ignored** by pricing ([§12 #4](../12-known-limitations.md)) — case (b) is a server-price-list gap, not something a higher `unitPrice` can paper over. Express a negotiated reduction as `lineDiscountAmount` instead.
  - There is **no manual price override** at the POS ([§12 #4](../12-known-limitations.md)); a "wrong price" cannot be corrected on the call — fix the price list back-office.

---

### UC-E2: Network drop / ambiguous outcome during a sale — reconcile before resend

- **Actor:** cashier (the timeout), integrator (the reconcile logic baked into the client).
- **Goal:** recover safely from a `POST /pos/sales` whose outcome is unknown, **without double-posting**.
- **Preconditions:** a sale POST was sent; the client received **no clean 201 and no clean terminal 4xx** — i.e. a socket timeout, dropped connection, app crash, or a `500`. The session uid is known; the client wrote the `Idempotency-Key` **and the exact request body** to device storage *before* the first POST ([§11 §4.1a](../11-errors-offline-idempotency.md)) — without that persisted pair, an app kill loses the key and this whole procedure is unavailable.
- **Main flow (the safe recovery procedure — [§11 §4.2](../11-errors-offline-idempotency.md)):**
  1. **Classify the outcome as AMBIGUOUS, not failed.** The server is partially async: the POST transaction synchronously commits a FINALISED, fully-paid invoice; stock/GL/AR follow via the outbox (~1s). A lost response does **not** mean the sale did not commit ([§09 §7](../09-sales-payments-receipts.md)).
  2. **Resend the SAME `Idempotency-Key`, not a blind retry.** If you sent an `Idempotency-Key` header on the original POST (required in practice — a stable per-sale key, ≤80 chars, persisted before the POST; ADR-0042 / `f08fb08`), resending the **same** key replays the original: the server returns the **original invoice** (still **201**, match by `uid`), no double post. A concurrent in-flight request with the same key returns **409 "This sale is still being processed. Please try again in a moment."** — **not a terminal answer**: keep the key, keep the till blocked, retry the same key shortly. A blind resend that **omits** the header (legacy non-idempotent path) *does* mint a *second* finalised invoice and a *second* `SALE_FINALISED` event → duplicate stock issue + duplicate GL/AR ([§11 §4](../11-errors-offline-idempotency.md), [§12 #1](../12-known-limitations.md)). `X-Request-Id` does **not** dedupe — only `Idempotency-Key` does.
  2b. **After an app relaunch, resolve the stored attempt BEFORE taking a new sale.** Read the persisted slot on startup and, if it is occupied, replay the stored body under the stored key (prompting the cashier — "an earlier sale was interrupted, check it now"). Do **not** silently re-ring the basket and do **not** let the till accept a fresh sale while an attempt is unresolved: that is precisely how the same basket gets charged twice. Clear the slot only once the outcome is confirmed.
  3. **Confirm the session is still usable:** `GET /api/v1/pos/sessions/uid/{uid}` ([§08 §4](../08-sessions.md), perm `POS.SESSION.VIEW`). If it is no longer `OPEN`, the resend would 409 anyway — escalate.
  4. **Reconcile — look for the maybe-created invoice:** `GET /api/v1/sales-invoices?companyId={companyId}` (newest first) ([§11 §4.2](../11-errors-offline-idempotency.md)) and match on amount + line snapshot + the timestamp window of your attempt.
     - The session `x-read` (`GET .../uid/{uid}/x-read`, [§08 §7](../08-sessions.md)) tells you only *whether* `invoiceCount`/`totalSalesAmount` moved — not *which* invoice — so use the sales-invoices list to identify the specific one.
  5. **Decide:**
     - **Match found** → the original **succeeded**. Treat as done; reprint the receipt from the existing invoice; do **not** resend.
     - **No match** → the sale did not commit; resend (same basket, **same `Idempotency-Key`** so a stray original is replayed rather than duplicated; carry your durable local transaction id as `X-Request-Id` too, so any double-post is findable in server logs), then re-evaluate. If you sent no key on the original, resend **once** and rely on this reconcile pass.
- **Alternate / exception flows:**
  - **Token expired during the drop** (`401`): refresh first (`POST /api/v1/auth/refresh`, [§01 §4](../01-authentication-and-permissions.md)) — note refresh tokens are single-use/rotated — then run the reconcile.
  - **Offline for an extended period:** queue sales locally, each stamped with its durable local id; on reconnect **replay one at a time**, applying this reconcile-before-resend rule to each, and **stop on the first terminal error** ([§11 §4.3](../11-errors-offline-idempotency.md)). There is **no offline/batch ingest endpoint** — every sale needs a live authenticated request and an OPEN session.
  - **Outbox lag:** even after a confirmed 201, the ledger may not be posted yet; never gate the reconcile on stock/GL having moved — match on the **invoice**, which is synchronous.
- **Outcome:** exactly one finalised invoice exists for the basket (or zero, then one after a confirmed resend). No duplicate stock issue / GL / AR. The receipt is reprinted from the authoritative `SalesInvoiceDto`.
- **Notes & limitations:**
  - **Idempotency is now key-based** (ADR-0042 / `f08fb08`, [§12 #1](../12-known-limitations.md)): send a stable `Idempotency-Key` per sale and resend the **same** key on an ambiguous outcome — the server replays the original invoice, closing the window between "server committed" and "client learned about it". Keys are scoped per company; a failed sale frees its key for re-use. The reconcile discipline above is now the **fallback** — it is what you fall back to only when no key was sent (legacy path) or to confirm an outcome before resending.
  - **The key must survive the crash it protects against.** Persist it (with the request body) before the POST, clear it only on a confirmed terminal outcome, and never release it on a `409` ([§11 §4.1a/§4.2a](../11-errors-offline-idempotency.md)). A key held in a variable is lost with the process, and the re-rung basket mints a new one — which is exactly the duplicate this UC exists to prevent.
  - **POS whole-sale void now exists** (ADR-0042 / `f08fb08`, [§12 #2](../12-known-limitations.md)): if you *do* double-post, call `POST /api/v1/pos/sales/uid/{uid}/reverse` `{ reason }` → **204** (perm `POS.SALE.VOID`) to reverse the unwanted invoice whole — revenue + VAT + cash + stock + COGS all back out, and it drops out of the drawer. **Preconditions:** the invoice is POS-origin, **FINALISED**, and its originating session is still **OPEN** — else **409**, and you fall back to a back-office void. A cash-drawer `REFUND` payout (drawer bookkeeping only — does **not** touch stock/GL/AR, leaving a back-office correcting journal, [§10](../10-returns-refunds.md)) is **no longer the only** POS recourse for an OPEN-session sale. **Partial / line-level refunds remain deferred** ([§12 #2](../12-known-limitations.md)) — `reverse` is whole-invoice only. Preventing the duplicate is still cheaper than voiding it.
  - Persist the 201 `SalesInvoiceDto` locally keyed by your transaction id so a **reprint never triggers a second POST** ([§11 §4.2](../11-errors-offline-idempotency.md)).

---

### UC-E3: Operating multiple tills / branches concurrently

- **Actor:** store manager (provisioning/scope), cashiers (each on their own till).
- **Goal:** run several tills — possibly across branches — at the same time, with sales correctly scoped and the per-till drawer isolated.
- **Preconditions:** each cashier is authenticated; each holds the operating `POS.*` codes; tills exist on the relevant branch(es); cashiers are assigned to the branch(es) they sell in.
- **Main flow:**
  1. **Resolve scope.** The JWT is scoped to the cashier's **default branch** ([§01 §2](../01-authentication-and-permissions.md), `AuthUser.activeCompanyUid/activeBranchUid`). For a multi-store cashier, pick a branch: `GET /api/v1/auth/my-branches` ([§01 §7](../01-authentication-and-permissions.md)) → use the chosen `branchUid` in the `X-Branch-Uid` header to switch scope for a request ([§02 §… / §01 §8](../02-company-branch-context.md)). Non-root callers may only switch into an **ACTIVE assigned** branch (else 403 `"You are not assigned to that branch."`).
  2. **List tills on the branch:** `GET /api/v1/pos/tills?companyId={id}&branchId={id}` ([§02 §5](../02-company-branch-context.md), perm `POS.TILL.VIEW`) — note these list params are the **numeric ids**, not uids.
  3. **Each cashier opens their own session on a distinct till:** `POST /api/v1/pos/sessions` `{ tillUid, openingFloatAmount }` ([§08 §3](../08-sessions.md)). The session inherits company/branch **from the till**; `cashierId` and `openedAt` are server-stamped.
  4. **Ring sales independently:** each `POST /api/v1/pos/sales` carries its own `sessionUid` ([§09](../09-sales-payments-receipts.md)); scope is taken from the JWT/`X-Branch-Uid` plus the **session's** company (`ScopeGuard.assertCanActIn`) — there is **no branch in the sale body** ([§02 §5](../02-company-branch-context.md), [§09 §1](../09-sales-payments-receipts.md)).
- **Alternate / exception flows:**
  - **Two opens on the same till** → second one gets **409** `"This till already has an OPEN session."` — a till may have **at most one OPEN session** ([§08 §1/§3](../08-sessions.md)). Close/reconcile the first, resume it if it is your own abandoned shift ([§07 — Occupancy](../07-tills.md#occupancy--reading-hasopensession-and-the-opensession-fields)), or use a different till. Nothing frees a till on a timer — only an explicit cash-up does.
  - **Acting outside scope** → ringing against a session whose company the caller cannot act in → **403** (`ScopeGuard`); a rejected `X-Branch-Uid` → **403** ([§01 §8](../01-authentication-and-permissions.md)).
  - **Wrong list ids** → passing a `uid` where the till/session list wants the numeric `companyId`/`branchId` → **400** type-mismatch ([§02 §6](../02-company-branch-context.md)).
- **Outcome:** N concurrent sessions on N tills, each with an isolated drawer (its own float, sales total, payouts, expected cash). Each sale lands in the correct branch/company. Optimistic-lock 409s under concurrency (e.g. shared stock-on-hand) are the *only* retryable conflict ([§11 §2.1](../11-errors-offline-idempotency.md)).
- **Notes & limitations:**
  - **One session per till at a time** ([§08 §1](../08-sessions.md)) — model "lanes" as distinct tills, not parallel sessions on one till.
  - A `403` never names the missing permission/branch ([§01 §9](../01-authentication-and-permissions.md)); diagnose with `GET /auth/me` + `GET /auth/my-branches`.
  - Sessions/sales are **per company-branch scope**; there is no cross-branch "ring anywhere" — the operator must be assigned to (and scoped into) each branch they sell in.

---

### UC-E4: Currency selection — enabled currencies and the default

- **Actor:** cashier (picks/uses), integrator (wires the picker), store manager (enables currencies, back-office).
- **Goal:** ring a sale in a currency that is valid for the scope, defaulting sensibly, and never tripping the 422 trap.
- **Preconditions:** authenticated; cashier holds `CURRENCY.VIEW` (to read the list) and `POS.SALE.CREATE`; the company/branch has an enabled-currency allow-list maintained back-office.
- **Main flow:**
  1. **On login/scope, fetch the allow-list + default:** `GET /api/v1/fx/currencies/enabled?companyUid={uid}[&branchUid={uid}]` ([§04 §5.1](../04-pricing-tax-currency.md), perm `CURRENCY.VIEW`) → `{ resolvedDefault, enabled[] }`. `resolvedDefault` resolves branch default → company default → company base currency.
  2. **Pre-fill** the sale `currency` with `resolvedDefault`; **constrain** the cashier's picker to the `enabled` codes only ([§04 §5.3 / §7](../04-pricing-tax-currency.md)).
  3. **Ring the sale** with the chosen `currency`: `POST /api/v1/pos/sales` ([§09 §2.1](../09-sales-payments-receipts.md)). It becomes both the invoice header currency **and** the single CASH payment currency.
- **Alternate / exception flows:**
  - **Currency not enabled** → **422 Unprocessable Entity** `CurrencyNotEnabledException` ([§04 §5.3](../04-pricing-tax-currency.md), [§11 §2](../11-errors-offline-idempotency.md), ADR-0039). **Terminal** — pick a code from `enabled`; do not retry the same bytes.
  - **Blank `currency`** → **400** (`@NotBlank`).
  - **Lacking `CURRENCY.VIEW`** → **403** on the read; fall back to the company `baseCurrency` from `CompanyDto` ([§02 §3](../02-company-branch-context.md)) as the safe default, but the sale will still 422 if that code is not enabled.
- **Outcome:** the invoice and its CASH tender are denominated in an enabled currency; the 422 trap is avoided because the picker was sourced from `enabled`.
- **Notes & limitations:**
  - **Multi-tender supported** (ADR-0042 / `f08fb08`, [§12 #3](../12-known-limitations.md)): pass an optional `tenders[]` list (`tenderType` CASH/CARD/MOBILE_MONEY/CHEQUE + `amount` + instrument refs) and the server loops `addPayment`, requiring `sum ≥ gross`; split and non-cash are both fine. **Omit it** and the POS path falls back to legacy behaviour — **one** exact CASH payment in the header currency for the gross. Every tender is denominated in the one header currency (no per-line FX).
  - **No per-line FX** on the POS — all line prices/VAT/totals are in the one document currency ([§04 §5.3](../04-pricing-tax-currency.md)); rate maintenance (`POST /api/v1/fx/rates`) is back-office.
  - `GET /api/v1/fx/currencies` is the **global master** (every known currency) — do **not** drive the picker from it; use `…/enabled` for the scope ([§04 §5.2](../04-pricing-tax-currency.md)).

---

### UC-E5: End-of-day close-out across tills

- **Actor:** cashier (counts + closes own till), shift supervisor / store manager (reconcile — posts variance to GL).
- **Goal:** at end of day, close every open till, declare counted cash, and reconcile so the cash variance hits the ledger.
- **Preconditions:** sessions are `OPEN`; the cashier holds `POS.SESSION.CLOSE`; the person reconciling holds `POS.SESSION.RECONCILE` (a supervisor/back-office code, usually withheld from a plain cashier — [§01 §9](../01-authentication-and-permissions.md)).
- **Main flow (per till — [§08 §11](../08-sessions.md)):**
  1. **(Optional) Mid/late-shift cash check:** `GET /api/v1/pos/sessions/uid/{uid}/x-read` ([§08 §7](../08-sessions.md), perm `POS.SESSION.VIEW`) → running `totalSalesAmount`, `totalPayoutsNetAmount`, `expectedCashAmount`, `invoiceCount`. Requires the session still `OPEN`.
  2. **(Optional) Drawer drops / refunds before close:** `POST /api/v1/pos/sessions/uid/{uid}/payouts` `{ payoutType: PAID_OUT|REFUND, amount, reason? }` ([§08 §6](../08-sessions.md)) — both types are cash **outflows** that reduce expected cash. (A `REFUND` payout is drawer bookkeeping only — it does **not** reverse stock/GL/AR.)
  3. **Count and close:** `POST /api/v1/pos/sessions/uid/{uid}/close` `{ countedCashAmount, notes? }` ([§08 §8](../08-sessions.md), perm `POS.SESSION.CLOSE`). The server computes `expectedCashAmount = openingFloat + cashSalesTotal − totalPayouts` and `varianceAmount = counted − expected`, sets `status = CLOSED`. **No GL posting at close.**
  4. **Reconcile (Z-read):** `POST /api/v1/pos/sessions/uid/{uid}/reconcile` `{ notes? }` ([§08 §9](../08-sessions.md), perm `POS.SESSION.RECONCILE`). If `variance ≠ 0` it posts the variance journal **synchronously, fail-fast** (over → `DR Cash / CR POS_CASH_OVER`; short → `DR POS_CASH_SHORT / CR Cash`), stamps `varianceJournalId`, sets `status = RECONCILED`, and returns the `ZReadDto`.
  5. **Repeat** for every open till. (To find them: `GET /api/v1/pos/sessions?companyId={id}&status=OPEN` ([§08 §5](../08-sessions.md)) — filter **server-side**; an unfiltered page 0 will miss open shifts once the company has more lifetime sessions than fit on the page.)
- **Alternate / exception flows:**
  - **Close when not OPEN** (double-close) → **409** ([§08 §8](../08-sessions.md)).
  - **Reconcile when not CLOSED** → **409** `"Session must be CLOSED before reconciliation."` ([§08 §9](../08-sessions.md)) — close before reconcile; the flow is one-way `OPEN → CLOSED → RECONCILED`.
  - **Missing GL config for the variance** → surfaces here as a **409** domain conflict (the reconcile **fails fast** rather than completing without the journal — it is a human command, not the swallow-and-retry outbox) ([§08 §9](../08-sessions.md)).
  - **`countedCashAmount` null** → **400** ([§08 §8](../08-sessions.md)). **Lacking the permission** → **403** (cashier may close but not reconcile).
  - **Sale arrives after close** → ringing against a CLOSED/RECONCILED session → **409** `"This POS session is not OPEN."` ([UC-E1](#uc-e1-sale-rejected--the-five-ways-post-possales-says-no)); open a fresh session for the next shift.
  - **A till nobody closed** → it stays `OPEN` indefinitely; there is no timeout sweeper and no logout hook, because a close must record a **counted** cash amount and the system will not invent one. Someone holding `POS.SESSION.CLOSE` must cash it up (UC-B11 in the [shift-lifecycle use cases](02-shift-lifecycle.md)).
- **Outcome:** every till ends `RECONCILED`; `countedCashAmount`/`expectedCashAmount`/`varianceAmount` are recorded per session; any non-zero variance is posted to the GL with `varianceJournalId` set. The day's POS invoices are finalised (their stock/GL/AR having posted asynchronously, [§09 §7](../09-sales-payments-receipts.md)).
- **Notes & limitations:**
  - **X-read totals are a cash-drawer report, not a ledger report** ([§08 §7](../08-sessions.md)): the invoices are synchronous so totals appear immediately, but their downstream stock/GL/AR effects are eventual.
  - **No bulk close-out** endpoint — there is no "close all tills" call; iterate per session uid.
  - Variance posting at reconcile is in the **company base currency**, dated to the session's `closedAt` ([§08 §9](../08-sessions.md)).
  - A `REFUND` payout adjusts expected cash for reconciliation but is **not** a sale reversal ([§12 #2](../12-known-limitations.md), [§10](../10-returns-refunds.md)). To actually reverse a mistaken POS sale **while the session is still OPEN**, use `POST /api/v1/pos/sales/uid/{uid}/reverse` (perm `POS.SALE.VOID`, ADR-0042 / `f08fb08`) — whole-invoice only; **partial / line-level** merchandise returns remain deferred and must be handled office-side.

---

## Cross-reference

| Scenario | Primary endpoint(s) | Key statuses | Guide |
|----------|---------------------|--------------|-------|
| UC-E1 Sale rejected | `POST /api/v1/pos/sales` | 400 / 403 / 404 / 409 / 415 / 422 | [§09](../09-sales-payments-receipts.md), [§11](../11-errors-offline-idempotency.md) |
| UC-E2 Ambiguous outcome | `POST /pos/sales` (+ a **durable** `Idempotency-Key`) → replay, or `GET /sales-invoices` / `GET .../sessions/uid/{uid}` reconcile; `POST /pos/sales/uid/{uid}/reverse` to undo a double-post | 500 / timeout, then 201 (replay) / 200 / 409 (still processing — keep the key) / 204 (reverse) | [§11 §4](../11-errors-offline-idempotency.md), [§12 #1/#2](../12-known-limitations.md) |
| UC-E3 Multi-till / branch | `GET /pos/tills`, `POST /pos/sessions`, `X-Branch-Uid` | 409 (till busy), 403 (scope) | [§01](../01-authentication-and-permissions.md), [§02](../02-company-branch-context.md), [§08](../08-sessions.md) |
| UC-E4 Currency | `GET /fx/currencies/enabled` → `POST /pos/sales` | 422 (not enabled) | [§04 §5](../04-pricing-tax-currency.md), [§11 §2](../11-errors-offline-idempotency.md) |
| UC-E5 Close-out | `POST .../close`, `POST .../reconcile` | 409 (state), 403 (perm) | [§08](../08-sessions.md), [§12 #2](../12-known-limitations.md) |

> **Reality check.** As of ADR-0042 / `f08fb08`, the historical gaps are largely closed: **server-side idempotent retry** (`Idempotency-Key` header, [§12 #1](../12-known-limitations.md)), **POS whole-sale void** (`POST /pos/sales/uid/{uid}/reverse`, perm `POS.SALE.VOID`, [§12 #2](../12-known-limitations.md)), and **split / card / mobile-money tender** (the `tenders[]` list, [§12 #3](../12-known-limitations.md)) are all now supported. Pricing is **server-authoritative** — client `unitPrice` is accepted but ignored, so there is still **no manual price override** at the POS ([§12 #4](../12-known-limitations.md)). What genuinely remains **not supported today**: **partial / line-level refunds** (the reverse is whole-invoice only, [§12 #2](../12-known-limitations.md)) and **offline / batch sale ingest** ([§11 §4.3](../11-errors-offline-idempotency.md)). The disciplines above (same-key safe-retry, reconcile-before-resend as the fallback, OPEN-session `reverse` for a mis-post, `lineDiscountAmount` for reductions) are the closest the current API allows.
