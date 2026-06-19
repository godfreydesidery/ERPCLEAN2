# Customers

This section covers everything a POS client needs to do with **customers**: look one up
(search-as-you-type at the register), tell a **walk-in / cash** customer apart from a
**credit account** customer, **create a customer on the fly** when the person at the till
is new, and understand how a customer's kind and credit status interact with the
sale you are about to ring.

Every endpoint here is on `CustomerController`
(`src/main/java/com/erp/api/CustomerController.java`,
`@RequestMapping("/api/v1/customers")`). It is the **shared customer master** — there is
no POS-specific customer endpoint. The POS sale endpoint
(`POST /api/v1/pos/sales`) takes a customer by its numeric `customerId`, so the normal
flow is: search here, grab the customer's `id`, then ring the sale.

> Read the **Shared Contract** doc first. The envelope (`{data, errors, meta}`),
> auth (`Authorization: Bearer <accessToken>`), the `X-Branch-Uid` scope header,
> pagination (`page`/`size`/`sort` + `meta`), and the full error-status table all apply
> here verbatim and are **not** repeated below except where a behaviour is specific to
> customers.

---

## Permissions

Customer endpoints are gated by two permission codes (seeded in
`db/migration/R__seed_permissions.sql`):

| Code | Seed description | Used by |
|------|------------------|---------|
| `CUSTOMER.VIEW` | `View and select customers` | search/list, get-by-uid, list-branches |
| `CUSTOMER.MANAGE` | `Create, update and archive customers` | create, update, archive, restore |

A POS cashier who only **rings sales** needs `CUSTOMER.VIEW` (to search and pick a
customer) plus the POS codes (`POS.SALE.CREATE`, etc.). If you also want the cashier to
**create-on-the-fly** at the register, the user/role must additionally hold
`CUSTOMER.MANAGE`. Check the caller's effective codes via `GET /api/v1/auth/me`
(`permissions` array) and hide/disable the "New customer" button when `CUSTOMER.MANAGE`
is absent — a denied call returns the generic `403`
(`You do not have permission to perform this action.`), which never names the missing
code.

All customer endpoints are additionally **company-scoped**: the service calls
`ScopeGuard.assertCanActIn(...)` on the customer's `companyId`, so a caller cannot list,
read, or create customers outside the company in their active scope (root excepted). A
client-supplied `companyId` that you are not scoped into yields `403`.

---

## Customer kinds: walk-in (cash) vs credit account

`customerKind` is a required enum (`CustomerKind`,
`src/main/java/com/erp/modules/parties/domain/enums/CustomerKind.java`) with exactly two
values:

| `customerKind` | Meaning | At the POS till |
|----------------|---------|-----------------|
| `CASH_WALK_IN` | Cash / walk-in customer. `creditLimit` is null. | The normal POS path. The sale is paid in full in cash at ring-up. |
| `CREDIT_ACCOUNT` | Account customer with a credit limit and payment terms. | POS still rings it as a **fully-paid cash** sale (see below) — POS is not an on-account/charge channel. |

Two related enums you will see on the DTO:

- **`partyType`** (`PartyType`: `INDIVIDUAL` | `BUSINESS`) — required. Drives
  identifier rules: a `BUSINESS` customer **must** have a `tin`
  (`BR-PARTY-04`, enforced in `CustomerServiceImpl.validateIdentifiers`).
- **`segment`** (`CustomerSegment`: `RETAIL` | `WHOLESALE` | `GOVERNMENT` | `OTHER`) —
  optional, defaults to `OTHER`. Coarse reporting/price-list segment; not something a POS
  client normally needs to set.

There is also a separate **credit-control status** (`CreditStatus`:
`OK` | `WARNING` | `ON_HOLD` | `STOPPED`) stored on the customer entity, but **it is not
exposed on `CustomerDto`** and is not returned by any of the endpoints below — so a POS
client cannot read it. See [How credit interacts with POS](#how-customer-credit-interacts-with-pos)
for what this means in practice.

---

## Search / list customers

The single endpoint a POS client uses to find a customer at the till.

**`GET /api/v1/customers`** — `@PreAuthorize("@perm.has('CUSTOMER.VIEW')")`

### Query params

| Param | Required | Type | Notes |
|-------|----------|------|-------|
| `companyId` | **yes** | `Long` | Tenant scope. Must be a company you are scoped into, else `403`. Missing → `400`. |
| `q` | no | `String` | Search term. When blank/omitted, returns **all** customers in the company (paged). |
| `page` | no | `int` | Zero-based, default `0`. |
| `size` | no | `int` | Default `20`. |
| `sort` | no | `String` | e.g. `sort=displayName,asc`. |

The `q` filter is a single OR across four columns
(`CustomerRepository.search`):

- `displayName` — **case-insensitive substring** (`LIKE %q%`), good for type-ahead;
- `tin` — **exact** match;
- `phone` — **exact** match;
- `code` — **exact** match (the system code, e.g. `CUST-0001`).

So partial-name typing matches, but TIN / phone / code must be typed in full to match.

### Success response

This endpoint returns the envelope **with pagination meta** (the controller returns
`ApiResponse.ok(page.getContent(), PageMeta.from(page))`):

```json
{
  "data": [
    {
      "id": "1024",
      "uid": "c1f0a4d2-9b3e-4a77-8b2c-2c0f0d5e7a11",
      "companyId": "1",
      "code": "CUST-0007",
      "partyType": "INDIVIDUAL",
      "displayName": "Jane Walk-in",
      "legalName": null,
      "tin": null,
      "vatRegistered": false,
      "vrn": null,
      "businessRegNo": null,
      "mobileMoneyNo": "0712345678",
      "phone": "0712345678",
      "email": null,
      "physicalAddress": null,
      "postalAddress": null,
      "region": null,
      "district": null,
      "country": null,
      "customerKind": "CASH_WALK_IN",
      "creditLimit": null,
      "paymentTermsDays": null,
      "paymentTermsId": null,
      "taxExempt": false,
      "taxExemptionRef": null,
      "defaultCurrency": null,
      "defaultPriceListId": null,
      "defaultAgentId": null,
      "segment": "OTHER",
      "status": "ACTIVE",
      "version": "0",
      "createdAt": "2026-06-19T08:14:02.113Z",
      "createdBy": "5",
      "updatedAt": null,
      "updatedBy": null
    }
  ],
  "errors": [],
  "meta": { "page": 0, "size": 20, "totalElements": 1, "totalPages": 1, "hasNext": false }
}
```

> **`id` and other `Long`s are JSON strings.** Per the project's global Long-as-string
> Jackson config, `id`, `companyId`, `version`, `createdBy`, etc. serialize as quoted
> strings (`"1024"`), even though the POS sale request expects `customerId` as a number
> (Jackson accepts both `1024` and `"1024"` on the way in). The `customerId` you send to
> `POST /api/v1/pos/sales` is this `id`.

### `CustomerDto` fields

Source: `src/main/java/com/erp/modules/parties/domain/dto/CustomerDto.java`.

| Field | Type | Notes |
|-------|------|-------|
| `id` | `Long` (string on wire) | DB id — **this is the `customerId` for POS sales**. |
| `uid` | `String` | URL-addressing uid (used for `/uid/{uid}` paths and `X-` scoping). |
| `companyId` | `Long` | Owning company. |
| `code` | `String` | System code, e.g. `CUST-0007` (assigned on create; not user-editable). |
| `partyType` | `PartyType` | `INDIVIDUAL` \| `BUSINESS`. |
| `displayName` | `String` | Name shown on the receipt / picker. |
| `legalName` | `String` | Nullable. |
| `tin` | `String` | Nullable (mandatory for `BUSINESS`). |
| `vatRegistered` | `boolean` | |
| `vrn` | `String` | VAT reg number; only allowed when `vatRegistered`. |
| `businessRegNo` | `String` | Nullable. |
| `mobileMoneyNo` | `String` | Nullable. |
| `phone` | `String` | Nullable. |
| `email` | `String` | Nullable. |
| `physicalAddress` / `postalAddress` | `String` | Nullable. |
| `region` / `district` / `country` | `String` | Nullable. |
| `customerKind` | `CustomerKind` | `CASH_WALK_IN` \| `CREDIT_ACCOUNT`. |
| `creditLimit` | `MoneyDto` (`{amount, currency}`) or `null` | Null for walk-in; null when unset. |
| `paymentTermsDays` | `Integer` | Nullable. |
| `paymentTermsId` | `Long` | Soft-FK to payment terms; nullable. |
| `taxExempt` | `boolean` | |
| `taxExemptionRef` | `String` | Nullable. |
| `defaultCurrency` | `String` (3-letter) | Nullable. |
| `defaultPriceListId` | `Long` | Nullable. |
| `defaultAgentId` | `Long` | Nullable. |
| `segment` | `CustomerSegment` | `RETAIL` \| `WHOLESALE` \| `GOVERNMENT` \| `OTHER`. |
| `status` | `MasterStatus` | `ACTIVE` \| `INACTIVE` \| `ARCHIVED`. Archived customers still appear in search results. |
| `version` | `Long` | Optimistic-lock version. |
| `createdAt` / `updatedAt` | `String` (ISO-8601) | Nullable. |
| `createdBy` / `updatedBy` | `Long` | Nullable. |

> **Note:** `CustomerDto` does **not** carry `creditStatus`, `manualHold`,
> `creditHoldReason`, or the current AR balance. None of the customer endpoints surface
> credit-control state to a POS client.

### Notable errors

- `400` — `companyId` missing or not coercible to `Long`.
- `401` — missing/expired bearer token, or the user is no longer `ACTIVE`.
- `403` — caller lacks `CUSTOMER.VIEW`, or `companyId` is outside the caller's scope.

### curl

```bash
# Type-ahead search for "jane" in company 1, first page
curl -s 'https://erp.example.com/api/v1/customers?companyId=1&q=jane&page=0&size=10&sort=displayName,asc' \
  -H 'Authorization: Bearer <accessToken>'

# Look up by exact phone
curl -s 'https://erp.example.com/api/v1/customers?companyId=1&q=0712345678' \
  -H 'Authorization: Bearer <accessToken>'
```

---

## Get one customer by uid

**`GET /api/v1/customers/uid/{uid}`** — `@PreAuthorize("@perm.has('CUSTOMER.VIEW')")`

Path var: `uid` (the customer `uid`, **not** the numeric `id`). Returns a single
`CustomerDto` (auto-wrapped → `{ "data": { ...CustomerDto... }, "errors": [], "meta": null }`).

Useful for refreshing a previously cached customer (e.g. to re-read the latest
`creditLimit` or `version`).

### Notable errors

- `404` — no customer with that `uid` (`NotFoundException` → `Customer` not found).
- `403` — customer exists but belongs to a company outside your scope (uid is **not**
  authorization; the service scope-checks the loaded entity).

```bash
curl -s 'https://erp.example.com/api/v1/customers/uid/c1f0a4d2-9b3e-4a77-8b2c-2c0f0d5e7a11' \
  -H 'Authorization: Bearer <accessToken>'
```

---

## Create a customer on the fly

When the person at the till is not in the system, create them, read back the `id`, then
ring the sale. Requires `CUSTOMER.MANAGE`.

**`POST /api/v1/customers`** — `@PreAuthorize("@perm.has('CUSTOMER.MANAGE')")`,
returns **HTTP 201**.

### Request body — `CreateCustomerRequest`

Source: `src/main/java/com/erp/modules/parties/domain/dto/CreateCustomerRequest.java`.
Send `Content-Type: application/json` (POS clients **must** — a wrong content type is
`415 Content-Type not supported. Use application/json.`).

**Required fields** (bean-validated; a violation is `400` with `field: message`):

| Field | Type | Constraint |
|-------|------|------------|
| `companyId` | `Long` | `@NotNull`. Must be a company you are scoped into. |
| `partyType` | `PartyType` | `@NotNull`. `INDIVIDUAL` or `BUSINESS`. |
| `displayName` | `String` | `@NotBlank`. |
| `customerKind` | `CustomerKind` | `@NotNull`. `CASH_WALK_IN` or `CREDIT_ACCOUNT`. |

**Optional fields:**

`legalName`, `tin`, `vatRegistered` (`Boolean`), `vrn`, `businessRegNo`,
`mobileMoneyNo`, `phone`, `email`, `physicalAddress`, `postalAddress`, `region`,
`district`, `creditLimit` (`MoneyDto` = `{ "amount": "...", "currency": "TZS" }`),
`paymentTermsDays` (`Integer`), `paymentTermsId` (`Long`), `country`,
`defaultPriceListId` (`Long`), `defaultAgentId` (`Long`), `segment` (`CustomerSegment`),
`taxExempt` (`Boolean`), `taxExemptionRef`, `defaultCurrency` (3-letter code).

> **The `code` is system-assigned** (e.g. `CUST-0008`) — do not send it; it is not a
> request field. `companyId` and `code` are immutable after create.

### Minimal create form for POS

For a walk-in created at the register, the minimal body is just the four required fields:

```json
{
  "companyId": 1,
  "partyType": "INDIVIDUAL",
  "displayName": "Walk-in 19-Jun 14:32",
  "customerKind": "CASH_WALK_IN"
}
```

A realistic walk-in form would also capture `phone`/`mobileMoneyNo` (handy because both
are exact-match search keys later):

```json
{
  "companyId": 1,
  "partyType": "INDIVIDUAL",
  "displayName": "Jane Walk-in",
  "customerKind": "CASH_WALK_IN",
  "phone": "0712345678",
  "mobileMoneyNo": "0712345678"
}
```

### Conditional business rules (beyond bean validation)

Enforced in `CustomerServiceImpl.validateIdentifiers` — these surface as `400`
(`IllegalArgumentException`):

- **`BR-PARTY-04`** — a `BUSINESS` customer **must** have a non-blank `tin`, else:
  `A business customer must have a TIN (BR-PARTY-04).`
- **`BR-PARTY-06`** — `vrn` may only be set when `vatRegistered` is true, else:
  `VRN may only be set when the customer is VAT-registered (BR-PARTY-06).`
- **`BR-PARTY-07`** — a `CREDIT_ACCOUNT` customer requires a `displayName`
  (also covered by `@NotBlank`).

For a fast walk-in flow, prefer `partyType=INDIVIDUAL` + `customerKind=CASH_WALK_IN` so
you avoid the TIN requirement entirely.

### Success response (HTTP 201)

A single `CustomerDto`, auto-wrapped:

```json
{
  "data": {
    "id": "1031",
    "uid": "9d2b...",
    "companyId": "1",
    "code": "CUST-0008",
    "partyType": "INDIVIDUAL",
    "displayName": "Jane Walk-in",
    "customerKind": "CASH_WALK_IN",
    "creditLimit": null,
    "segment": "OTHER",
    "status": "ACTIVE",
    "version": "0",
    "createdAt": "2026-06-19T14:32:10.501Z",
    "createdBy": "5"
  },
  "errors": [],
  "meta": null
}
```

Take `data.id` (`"1031"`) and use it as `customerId` on the very next POS sale.

### Notable errors

| Status | When |
|--------|------|
| `400` | Missing required field (`field: must not be null/blank`); `BR-PARTY-04/06`; malformed JSON or a bad enum value (e.g. `Invalid value 'WALKIN' for field 'customerKind' (CustomerKind).`). |
| `401` | Auth failure / inactive user. |
| `403` | Lacks `CUSTOMER.MANAGE`, or `companyId` outside scope. |
| `409` | DB unique violation (`A record with the same unique identifier already exists.`); optimistic-lock conflict (retryable). |
| `415` | Wrong `Content-Type`. |

### curl

```bash
curl -s -X POST 'https://erp.example.com/api/v1/customers' \
  -H 'Authorization: Bearer <accessToken>' \
  -H 'Content-Type: application/json' \
  -d '{
        "companyId": 1,
        "partyType": "INDIVIDUAL",
        "displayName": "Jane Walk-in",
        "customerKind": "CASH_WALK_IN",
        "phone": "0712345678"
      }'
```

> **No idempotency.** Like the POS sale endpoint, customer-create has no
> `Idempotency-Key`. A retried `POST /customers` after a network timeout will create a
> **second** customer record (there is no unique constraint on name/phone). De-dupe
> client-side: on retry, first re-run the search (`q=<phone>`) and reuse any match before
> re-posting.

---

## Update / archive / restore (reference)

These exist on the same controller but are **not** part of the normal POS ring-up flow;
included for completeness. All are `uid`-scoped under `CUSTOMER.MANAGE`.

| Method + path | Permission | Body | Result |
|---------------|-----------|------|--------|
| `PUT /api/v1/customers/uid/{uid}` | `@perm.scoped(#uid,'customer','CUSTOMER.MANAGE')` | `UpdateCustomerRequest` (same fields as create **minus** `companyId`; `code` is immutable) | `200` updated `CustomerDto` |
| `PUT /api/v1/customers/uid/{uid}/archive` | `@perm.scoped(...,'CUSTOMER.MANAGE')` | — | `204`, sets `status=ARCHIVED` |
| `PUT /api/v1/customers/uid/{uid}/restore` | `@perm.scoped(...,'CUSTOMER.MANAGE')` | — | `204`, sets `status=ACTIVE` |

Archiving is a soft-delete; archived customers **still appear** in search results
(`list`/`search` do not filter by `status`), so a POS client should check
`status === "ACTIVE"` before letting a cashier pick a customer.

---

## How customer credit interacts with POS

This is the part that surprises integrators: **the POS sale endpoint always rings a
fully-paid CASH sale, regardless of the customer's `customerKind`.**

Grounding (`PosSaleServiceImpl.processSale`,
`src/main/java/com/erp/modules/sales/service/PosSaleServiceImpl.java`):

1. POS resolves the customer by `customerId` (`customers.findById(...)` → `404 Customer`
   if unknown). It does **not** branch on `customerKind`.
2. It builds the invoice and then adds a **single CASH payment for the full gross**
   (`AddPaymentRequest(TenderType.CASH, grossTotal, currency, null)`).
3. It calls `finalise(...)`.

What `finalise` does with the customer's kind
(`SalesInvoiceServiceImpl.finalise`):

- **`CASH_WALK_IN`** → the cash branch: the invoice must be **paid in full**
  (`assertPaidInFull`). POS satisfies this by construction (it paid the full gross), so it
  finalises cleanly.
- **`CREDIT_ACCOUNT`** → the credit branch runs a **credit-limit check**: if the customer
  has a positive `creditLimit` and `existingArBalance + thisGross > creditLimit`, finalise
  throws unless the caller holds `SALES.CREDIT.OVERRIDE`:
  `Credit limit exceeded for customer <uid>. Limit: ... , projected balance: ...
  Requires SALES.CREDIT.OVERRIDE permission.` → surfaces as **`409 Conflict`**.
  - Because the POS payment covers the whole invoice, the AR open item ends up fully
    settled, but the credit-limit gate is evaluated **before** payment is netted against
    AR, so a credit customer already near their limit can still trip this `409`. The cure
    is `SALES.CREDIT.OVERRIDE` on the cashier, or selling to that customer as cash.

### Practical guidance for a POS client

- **Default to `CASH_WALK_IN`.** POS is a cash channel: paying in full at the till is the
  model the endpoint assumes. New-at-the-till customers should be created as
  `CASH_WALK_IN`.
- **Selling to an existing `CREDIT_ACCOUNT` customer is allowed**, but it is still rung as
  a paid-in-full cash sale — POS does **not** post the sale "on account". If the customer
  is over limit you will get a `409` unless the cashier has `SALES.CREDIT.OVERRIDE`.
- **You cannot read credit status from the API.** `creditStatus` / `manualHold` are not on
  `CustomerDto`. The only credit signal a POS client can pre-check is `creditLimit` (and
  even that does not include the live AR balance, which is not exposed here). Treat the
  finalise `409` as the authoritative credit gate and surface its (user-safe) message to
  the cashier.
- **The credit-limit check is on the synchronous finalise path**, so the `409` comes back
  on the `POST /api/v1/pos/sales` response itself — you do not have to wait for the async
  posting to learn the sale was blocked.
