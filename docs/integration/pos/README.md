# POS Integration API

This guide is the entry point for building an **external Point-of-Sale (POS) client** against the ERP over REST. The ERP exposes a stateless, JWT-secured JSON API: a POS application (a till app, a self-checkout kiosk, a mobile cashier, or a third-party register) authenticates a cashier, opens a cash session on a till, rings sales against that session, prints a receipt from the finalised invoice the API returns, and finally closes and reconciles the session at end-of-shift. Everything is plain `application/json` over HTTPS — there is no SDK, no SOAP, and no proprietary transport — so any HTTP-capable client can integrate. The financial back-office (stock issue, GL journal, AR posting) happens **eventually** behind each sale, so this guide is as much about *what the API guarantees synchronously* as it is about the call shapes.

---

## Conventions at a glance

These hold for every endpoint in this guide; see [00 — Overview & Conventions](./00-overview-and-conventions.md) and [01 — Authentication & Permissions](./01-authentication-and-permissions.md) for the full treatment.

| Topic | Rule |
|---|---|
| Base path | All endpoints are versioned under the fixed prefix **`/api/v1`** (path-based versioning; no header/media-type negotiation). POS paths: `POST /api/v1/pos/sales`, `/api/v1/pos/sessions/*`, `/api/v1/pos/tills/*`. |
| Auth | Stateless JWT bearer. `Authorization: Bearer <accessToken>` on every protected call. Access token TTL **15 min**; refresh token TTL **7 days** (single-use / rotated). |
| Response envelope | Every response is wrapped as `{ "data": <payload>, "errors": [], "meta": null }` on success and `{ "data": null, "errors": ["..."], "meta": null }` on error. (Binary/file bodies are not wrapped.) |
| Content type | POST/PUT bodies **must** be `application/json`, or you get **415 Unsupported Media Type**. |
| Scope | Company/branch scope is taken from the JWT (the cashier's default branch). Override per-request with the optional `X-Branch-Uid` header — no re-login. |
| Correlation | Send `X-Request-Id` to correlate logs; it is echoed back on the response (logging only — **not** used for deduplication). |
| Paging | Paged endpoints accept `page` (0-based), `size`, `sort`; `meta` carries `{page,size,totalElements,totalPages,hasNext}`. |
| Idempotency | **None.** `POST /api/v1/pos/sales` has no idempotency key — a blind retry creates a *second* sale. See [11 — Errors, Offline & Idempotency](./11-errors-offline-idempotency.md). |
| OpenAPI | Swagger UI at `/swagger-ui/index.html`, spec at `/v3/api-docs` (can be disabled in production via `ERP_SWAGGER_ENABLED=false`). |

---

## Quickstart — minimal end-to-end happy path

This walks a new integrator from zero to a printed receipt and a reconciled session, using only the real endpoints. Replace `https://erp.example.com` with your host. The cashier user needs the relevant `POS.*` permissions (see [01](./01-authentication-and-permissions.md)); the till/session/sale steps each require `POS.TILL.*`, `POS.SESSION.*`, and `POS.SALE.CREATE` respectively.

### 0. Prerequisites
- A cashier user account that is **ACTIVE** and assigned to a usable default branch.
- That user holds (directly or via role): `POS.TILL.VIEW`/`POS.TILL.MANAGE`, `POS.SESSION.OPEN`/`POS.SESSION.VIEW`/`POS.SESSION.CLOSE`/`POS.SESSION.RECONCILE`, and `POS.SALE.CREATE`.
- A walk-in/cash **customer** and a **sales agent** record exist (their numeric ids are required on every sale).

### 1. Log in
```http
POST /api/v1/auth/login
Content-Type: application/json

{ "username": "cashier01", "password": "••••••••" }
```
Response `data` is a `TokenResponse`:
```json
{
  "accessToken": "eyJ...",
  "accessTokenExpiresAt": 1718800000,
  "refreshToken": "eyJ...",
  "user": {
    "uid": "usr_...", "username": "cashier01", "displayName": "Cashier One",
    "isRoot": false, "activeCompanyUid": "co_...", "activeBranchUid": "br_...",
    "hasBranch": true
  }
}
```
Store both tokens. `accessTokenExpiresAt` is **epoch seconds** — refresh before it elapses via `POST /api/v1/auth/refresh`. Send `Authorization: Bearer <accessToken>` on all calls below.

### 2. Resolve company & branch context
The login already pins the cashier's default scope (`activeCompanyUid`, `activeBranchUid`). To confirm the effective scope and permissions, and to discover other branches the cashier may operate in:
```http
GET /api/v1/auth/me            # -> { uid, ..., activeCompanyUid, activeBranchUid, permissions[] }
GET /api/v1/auth/my-branches   # -> [ UserBranchDto ] of switchable ACTIVE branches
```
Check that `permissions` contains the `POS.*` codes you need (for a root user, `permissions` is empty and `isRoot=true` grants everything). To act in a non-default branch on any call, add header `X-Branch-Uid: <branchUid>`. You will need the **numeric** `companyId` and `branchId` for the till lookup in step 3 — resolve these via [02 — Company & Branch Context](./02-company-branch-context.md) (`GET /api/v1/companies`, `GET /api/v1/branches`).

### 3. Create or pick a till (register)
List the active tills for the current branch:
```http
GET /api/v1/pos/tills?companyId={companyId}&branchId={branchId}
```
Pick a till `uid` from the list. If none exists, create one (requires `POS.TILL.MANAGE`):
```http
POST /api/v1/pos/tills
Content-Type: application/json

{
  "companyUid": "co_...",
  "branchId": 12,
  "name": "Front Counter 1",
  "cashBankAccountUid": null   // null -> defaults to the company's default cash/bank account
}
```
Response `data` is a `PosTillDto` (`{ id, uid, companyId, branchId, code, name, cashBankAccountId, status }`). Keep its **`uid`**.

### 4. Open a session
Open a cashier session on the till with the opening cash float (requires `POS.SESSION.OPEN`):
```http
POST /api/v1/pos/sessions
Content-Type: application/json

{ "tillUid": "till_...", "openingFloatAmount": 200.00 }
```
Response `data` is a `PosSessionDto` with `status: "OPEN"` and a `sessionNumber`. Keep its **`uid`** — every sale, the X-read, the close, and the reconcile reference this session uid. A till may have only one OPEN session at a time.

### 5. Load catalog & prices
Fetch the products and their selling prices for the cashier UI (see [03 — Catalog](./03-catalog-products-units.md) and [04 — Pricing, Tax & Currency](./04-pricing-tax-currency.md)):
```http
GET /api/v1/products?companyId={companyId}&q={searchTerm}&page=0&size=50
GET /api/v1/units                                  # units of measure (need unitId per line)
GET /api/v1/products/uid/{productUid}/prices       # price-list entries for a product
GET /api/v1/products/barcode-lookup?companyId={companyId}&barcode={scanned}
```
Build your cart from the numeric `productId` + `unitId` + a `unitPrice`. Optionally check sellable quantity via [06 — Stock Availability](./06-stock-availability.md) (`GET /api/v1/stock/on-hand`). Resolve the cash **customer** id and the **agent** id from [05 — Customers](./05-customers.md) (`GET /api/v1/customers`) and `GET /api/v1/agents`.

### 6. Ring a sale
Post the cart against the open session (requires `POS.SALE.CREATE`):
```http
POST /api/v1/pos/sales
Content-Type: application/json

{
  "sessionUid": "sess_...",
  "customerId": 501,
  "agentId": 9,
  "currency": "TZS",
  "lines": [
    { "productId": 1001, "unitId": 3, "quantity": 2, "unitPrice": 1500.00, "lineDiscountAmount": 0 },
    { "productId": 1042, "unitId": 3, "quantity": 1, "unitPrice": 4000.00, "lineDiscountAmount": 200.00 }
  ],
  "tenderedAmount": 7000.00,
  "notes": "walk-in"
}
```
On success you get **HTTP 201** and `data` is the **finalised** `SalesInvoiceDto` (number allocated, VAT and totals frozen, tagged `origin=POS` and to your `posSessionId`). The sale is recorded as a fully-paid CASH **DIRECT** invoice.

> **What is *not* done by the time 201 returns:** stock issue, the GL revenue/VAT/cash journal, and the AR posting run **asynchronously** (a ~1s outbox poller), each in its own transaction. Do not assume the ledger is posted at response time. See [09 — Sales, Payments & Receipts](./09-sales-payments-receipts.md).
>
> **No idempotency:** if the POST times out but actually committed, a blind retry creates a **second** invoice and a second stock/GL/AR effect. Implement client-side dedupe (e.g. re-query before resending). See [11](./11-errors-offline-idempotency.md).

### 7. Print the receipt
The 201 body is everything you need to print — invoice number, line snapshots, VAT, gross total, and the `tenderedAmount` you submitted (compute change locally). No extra call is required for a basic receipt; reprints/lookups are covered in [09](./09-sales-payments-receipts.md). Note: the POS API has **no sale-reversal/refund endpoint** — see [10 — Returns & Refunds](./10-returns-refunds.md) for what is (and isn't) possible.

### 8. (During the shift) X-read — non-resetting snapshot
At any point you can pull a mid-shift totals snapshot without affecting the session (requires `POS.SESSION.VIEW`):
```http
GET /api/v1/pos/sessions/uid/{sessionUid}/x-read
```

### 9. Close the session
At end-of-shift the cashier counts the drawer and closes (requires `POS.SESSION.CLOSE`):
```http
POST /api/v1/pos/sessions/uid/{sessionUid}/close
Content-Type: application/json

{ "countedCashAmount": 7150.00, "notes": "end of shift" }
```
Response `data` is the `PosSessionDto` now in `CLOSED` status with `expectedCashAmount` and `varianceAmount` computed server-side. (Cash paid out mid-shift — e.g. supplier payments from the drawer — is recorded earlier via `POST /api/v1/pos/sessions/uid/{uid}/payouts`.)

### 10. Reconcile (Z-read) — posts the variance
Finalise the session and post the cash variance to the GL (requires `POS.SESSION.RECONCILE`):
```http
POST /api/v1/pos/sessions/uid/{sessionUid}/reconcile
Content-Type: application/json

{ "notes": "reconciled" }
```
The variance amount is computed server-side; response `data` is a `ZReadDto`. The session is now `RECONCILED` and a variance journal id is stamped on it. Full session lifecycle and reconciliation rules are in [08 — Sessions](./08-sessions.md).

That is the full loop: **login → context → till → session → catalog → sale → receipt → close → reconcile.**

---

## Table of contents

| # | Section | What it covers |
|---|---|---|
| 00 | [Overview & Conventions](./00-overview-and-conventions.md) | Base path & versioning, the `ApiResponse` envelope, content type, `X-Request-Id`, OpenAPI/Swagger, glossary. |
| 01 | [Authentication & Permissions](./01-authentication-and-permissions.md) | Login / refresh / logout, bearer usage, token TTLs, `/auth/me`, the seven `POS.*` permissions and what each gates. |
| 02 | [Company & Branch Context](./02-company-branch-context.md) | Resolving company & branch ids, default scope from the JWT, the `X-Branch-Uid` override and its rules. |
| 03 | [Catalog — Products & Units](./03-catalog-products-units.md) | Product search/list, barcode lookup, units of measure, the ids needed for sale lines. |
| 04 | [Pricing, Tax & Currency](./04-pricing-tax-currency.md) | Price lists, product prices, VAT/tax handling, currency enablement and the 422 case. |
| 05 | [Customers](./05-customers.md) | Walk-in/cash customers, customer lookup, the `customerId` required on every sale. |
| 06 | [Stock Availability](./06-stock-availability.md) | On-hand reads (by product / by location), sellable quantity checks. |
| 07 | [Tills](./07-tills.md) | Till (register) CRUD, listing by branch, default cash/bank account, deactivation. |
| 08 | [Sessions](./08-sessions.md) | Session lifecycle: open → payout → X-read → close → reconcile (Z-read); variance posting. |
| 09 | [Sales, Payments & Receipts](./09-sales-payments-receipts.md) | `POST /pos/sales` in depth, the synchronous vs. eventual side effects, receipt data. |
| 10 | [Returns & Refunds](./10-returns-refunds.md) | Why the POS API has no sale-reversal/refund endpoint, and the office-side return path. |
| 11 | [Errors, Offline & Idempotency](./11-errors-offline-idempotency.md) | Full HTTP error table, the no-idempotency caveat, safe-retry and offline guidance. |

---

## Typical day — sequence (text)

```
Cashier App                         ERP API (/api/v1)                 Async Outbox Poller
    |                                     |                                    |
    |  POST /auth/login                   |                                    |
    |------------------------------------>|                                    |
    |  <-- TokenResponse (access+refresh) |                                    |
    |                                     |                                    |
    |  GET /auth/me, /auth/my-branches    |   (confirm scope + POS.* perms)    |
    |------------------------------------>|                                    |
    |                                     |                                    |
    |  GET /pos/tills?companyId&branchId  |                                    |
    |------------------------------------>|   (pick a till uid; or POST /pos/tills to create)
    |                                     |                                    |
    |  POST /pos/sessions {tillUid,float} |                                    |
    |------------------------------------>|   session OPEN, sessionUid issued  |
    |                                     |                                    |
    |  GET /products, /units, /products/uid/{u}/prices                         |
    |------------------------------------>|   (load catalog + prices into UI)  |
    |                                     |                                    |
    |  == repeated through the day ==                                          |
    |  POST /pos/sales {sessionUid,...}   |                                    |
    |------------------------------------>|  201 finalised SalesInvoiceDto     |
    |  <-- 201 (print receipt locally)    |  + queue SALE_FINALISED outbox row |
    |                                     |------------------------------------>|  ~1s later:
    |                                     |                                    |  - issue stock
    |                                     |                                    |  - post GL journal
    |                                     |                                    |  - post AR open item
    |                                     |                                    |
    |  (mid-shift) GET .../x-read         |   non-resetting totals snapshot    |
    |  (mid-shift) POST .../payouts       |   drawer cash paid out             |
    |------------------------------------>|                                    |
    |                                     |                                    |
    |  == end of shift ==                                                      |
    |  POST .../close {countedCash}       |                                    |
    |------------------------------------>|  session CLOSED, variance computed |
    |                                     |                                    |
    |  POST .../reconcile {notes}         |                                    |
    |------------------------------------>|  Z-read; session RECONCILED;       |
    |  <-- ZReadDto                       |  variance journal posted to GL     |
    |                                     |                                    |
    |  POST /auth/logout {refreshToken}   |                                    |
    |------------------------------------>|  204; refresh token revoked        |
    v                                     v                                    v
```

> Note the timing gap: the 201 from `POST /pos/sales` confirms a **finalised, paid invoice** synchronously, but the inventory and ledger effects land a moment later via the outbox poller. Build your receipt and drawer logic off the 201 payload; reconcile your back-office expectations against the eventual postings, not the HTTP response.
