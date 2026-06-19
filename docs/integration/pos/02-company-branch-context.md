# Company & Branch Context

How an external POS client resolves *where it is operating* — which organisation, which
company, and which branch — before it can open a session, list tills, or ring a sale.

This is the second leg of the integration. It assumes you have already authenticated per
[`01-auth-and-conventions`](./01-auth-and-conventions.md) and hold a valid bearer token.
The shared contract (base path, `ApiResponse<T>` envelope, error table, auth/JWT flow,
pagination, `X-Branch-Uid` scoping) is defined there and is **not** repeated here — this
section only adds the organisation/company/branch resolution endpoints and the
**numeric `id` vs string `uid`** rules that POS endpoints depend on.

---

## 1. Why a POS needs this

The ERP is a multi-tenant hierarchy:

```
Organisation (one per deployment)
  └── Company        (legal entity, has a base currency)
        └── Branch   (physical location; POS tills live on a branch)
```

The JWT you get at login is already scoped to your **default branch** (and therefore a
company) — see `TokenResponse.AuthUser{ activeCompanyUid, activeBranchUid, hasBranch }`
in the shared auth contract. So in the common case a POS does **not** need to walk this
hierarchy at all: it reads `activeCompanyUid` / `activeBranchUid` from the login response.

You still need the endpoints below when:

- the POS lets the operator **pick** a company/branch (e.g. a multi-store deployment), or
- you need the **numeric `companyId` / `branchId`** that several POS endpoints require as
  query params (the JWT and DTOs expose both a numeric `id` and a string `uid`; the POS
  till/session *list* endpoints take the numeric form — see §5), or
- `hasBranch == false` (the logged-in user has no usable default branch and must choose one
  from `GET /api/v1/auth/my-branches`).

> Self-scoped shortcut: `GET /api/v1/auth/my-branches` (from the shared auth contract,
> `@PreAuthorize("isAuthenticated()")`, no `USER.VIEW` needed) returns the caller's own
> ACTIVE switchable branches as `List<UserBranchDto>` and is the recommended way for a POS
> to populate a branch picker without the heavier `COMPANY.VIEW` / `BRANCH.VIEW` reads
> below. The endpoints in §2–§4 are the *administrative* read path and require the
> permissions noted on each.

---

## 2. Resolve the organisation — `GET /api/v1/organisations` and `/current`

Grounded in `com.erp.api.OrganisationController` and `OrganisationServiceImpl`. The
organisation is created by deployment bootstrap; these endpoints are **read-only**.

### 2.1 `GET /api/v1/organisations/current`

The bootstrap call: *which organisation is this deployment?* Any authenticated caller may
read it (`@PreAuthorize("isAuthenticated()")`) — no `COMPANY.VIEW` required — because it
carries no sensitive data and is needed before any permission-scoped screen loads.

| | |
|---|---|
| Method + path | `GET /api/v1/organisations/current` |
| Permission | `isAuthenticated()` (any logged-in user) |
| Path params | none |
| Query params | none |
| Request body | none |

Implementation note: despite the controller Javadoc mentioning "by name", the service
(`OrganisationServiceImpl.current()`) returns `findFirstByOrderByIdAsc()` — i.e. the
**lowest-id (first-bootstrapped) organisation**. Single-org deployments are the norm, so
this is the deployment's one organisation. If none exists yet it throws `NotFoundException`
("No organisation exists yet. Bootstrap the deployment first.") → **404**.

Response payload is an `OrganisationDto`:

```java
record OrganisationDto(
    Long id, String uid, String name, String legalName,
    String defaultTimeZone, String status) {}
```

`status` is the `Organisation.status` enum name as a string (e.g. `"ACTIVE"`). `id` is a
`Long` but is serialised as a **JSON string** (see §6).

curl:

```bash
curl -s https://erp.example.com/api/v1/organisations/current \
  -H "Authorization: Bearer $ACCESS_TOKEN"
```

Success (HTTP 200, wrapped by `ApiResponseAdvice`):

```json
{
  "data": {
    "id": "1",
    "uid": "org_7Hk2pQ",
    "name": "Acme Retail Group",
    "legalName": "Acme Retail Group Ltd",
    "defaultTimeZone": "Africa/Dar_es_Salaam",
    "status": "ACTIVE"
  },
  "errors": [],
  "meta": null
}
```

### 2.2 `GET /api/v1/organisations`

Lists all organisations (sorted by `name` in `OrganisationServiceImpl.list()`). Rarely
needed by a POS (deployments are single-org); prefer `/current`.

| | |
|---|---|
| Method + path | `GET /api/v1/organisations` |
| Permission | `COMPANY.VIEW` (`@perm.has('COMPANY.VIEW')`) |
| Path/query params | none |
| Request body | none |

```bash
curl -s https://erp.example.com/api/v1/organisations \
  -H "Authorization: Bearer $ACCESS_TOKEN"
```

Success (HTTP 200) — `data` is a JSON array of `OrganisationDto`, `meta` is `null` (this
endpoint is **not** paged):

```json
{
  "data": [
    { "id": "1", "uid": "org_7Hk2pQ", "name": "Acme Retail Group",
      "legalName": "Acme Retail Group Ltd",
      "defaultTimeZone": "Africa/Dar_es_Salaam", "status": "ACTIVE" }
  ],
  "errors": [],
  "meta": null
}
```

**Notable errors:** `401` if no/invalid/expired bearer or the user is no longer ACTIVE;
`403` if the caller lacks `COMPANY.VIEW` (generic "You do not have permission to perform
this action.").

---

## 3. Resolve the company — `GET /api/v1/companies?organisationUid=…`

Grounded in `com.erp.api.CompanyController`. The list endpoint **requires** the
`organisationUid` query param — there is no unfiltered "all companies" list.

| | |
|---|---|
| Method + path | `GET /api/v1/companies` |
| Permission | `COMPANY.VIEW` (`@perm.has('COMPANY.VIEW')`) |
| Path params | none |
| Query params | **`organisationUid`** (string, **required**) |
| Request body | none |

The controller signature is `list(@RequestParam String organisationUid)` →
`companies.listByOrganisationUid(organisationUid)`. Because it is `@RequestParam` with no
`required=false` and no default, **omitting it is a `400`** (MissingServletRequestParameter,
message `"organisationUid: ..."` per the shared error table).

Response payload is a `List<CompanyDto>`:

```java
record CompanyDto(
    Long id, String uid, Long organisationId, String code, String name,
    String legalName, String taxId, String vrn, String logoRef,
    Short fiscalYearStartMonth, String timeZone, String baseCurrency,
    String status) {}
```

Fields a POS cares about: `uid` (the URL identifier), `id` (the **numeric** company id,
serialised as a string — needed for the POS list endpoints in §5), `name`/`code` for
display, and **`baseCurrency`** (the company's ledger currency; relevant when a POS sale
omits/overrides currency — a currency not enabled for the scope yields `422`
`CurrencyNotEnabledException` per the shared error table).

curl:

```bash
curl -s "https://erp.example.com/api/v1/companies?organisationUid=org_7Hk2pQ" \
  -H "Authorization: Bearer $ACCESS_TOKEN"
```

Success (HTTP 200, `meta` is `null` — not paged):

```json
{
  "data": [
    {
      "id": "10",
      "uid": "co_4Bn1Zx",
      "organisationId": "1",
      "code": "ACME-TZ",
      "name": "Acme Tanzania",
      "legalName": "Acme Tanzania Ltd",
      "taxId": "123-456-789",
      "vrn": "40-000000-A",
      "logoRef": null,
      "fiscalYearStartMonth": 1,
      "timeZone": "Africa/Dar_es_Salaam",
      "baseCurrency": "TZS",
      "status": "ACTIVE"
    }
  ],
  "errors": [],
  "meta": null
}
```

**Notable errors:** `400` if `organisationUid` is omitted; `401` unauthenticated;
`403` if the caller lacks `COMPANY.VIEW`.

> There is also `GET /api/v1/companies/uid/{uid}`
> (`@perm.scoped(#uid,'company','COMPANY.VIEW')`) to fetch one company by uid, returning a
> single `CompanyDto`. The other `CompanyController` routes (create/update/archive/
> `base-currency`) are administrative and gated by `COMPANY.MANAGE` /
> `COMPANY.CURRENCY.CHANGE` — out of scope for a POS client.

---

## 4. Resolve the branch — `GET /api/v1/branches?companyUid=…`

Grounded in `com.erp.api.BranchController`. The list endpoint **requires** `companyUid`,
and (unlike the company list) it is **scope-checked against that company**:
`@PreAuthorize("@perm.scoped(#companyUid, 'company', 'BRANCH.VIEW')")`.

| | |
|---|---|
| Method + path | `GET /api/v1/branches` |
| Permission | `BRANCH.VIEW`, scoped to the company (`@perm.scoped(#companyUid,'company','BRANCH.VIEW')`) |
| Path params | none |
| Query params | **`companyUid`** (string, **required**) |
| Request body | none |

Signature: `list(@RequestParam String companyUid)` → `branches.listByCompanyUid(companyUid)`.
Omitting `companyUid` → `400`.

Response payload is a `List<BranchDto>`:

```java
record BranchDto(
    Long id, String uid, Long companyId, String companyUid, String code,
    String name, String timeZone, boolean isDefault, Long managerId,
    BranchType branchType, String status) {}
```

`branchType` is the `BranchType` enum, serialised as its name. Valid values:
`HEAD_OFFICE`, `RETAIL`, `WAREHOUSE`, `SALES_OFFICE`, `FACTORY`, `OTHER` (nullable on the
entity — purely descriptive). For a POS, `RETAIL` is the typical store branch. `isDefault`
marks the company's default branch. Note `BranchDto` conveniently carries **both**
`companyId` (numeric, as string) and `companyUid` (string), plus the branch's own `id` /
`uid`.

curl:

```bash
curl -s "https://erp.example.com/api/v1/branches?companyUid=co_4Bn1Zx" \
  -H "Authorization: Bearer $ACCESS_TOKEN"
```

Success (HTTP 200, `meta` is `null` — not paged):

```json
{
  "data": [
    {
      "id": "100",
      "uid": "br_9Qd3Mn",
      "companyId": "10",
      "companyUid": "co_4Bn1Zx",
      "code": "DSM-01",
      "name": "Dar es Salaam Flagship",
      "timeZone": "Africa/Dar_es_Salaam",
      "isDefault": true,
      "managerId": "42",
      "branchType": "RETAIL",
      "status": "ACTIVE"
    }
  ],
  "errors": [],
  "meta": null
}
```

**Notable errors:** `400` if `companyUid` is omitted; `401` unauthenticated; `403` if the
caller lacks `BRANCH.VIEW` **or** is not allowed to act in that company's scope
(`@perm.scoped` denial). A bad/unknown `companyUid` resolves to no branches per the
service contract.

> Single branch by uid: `GET /api/v1/branches/uid/{uid}`
> (`@perm.scoped(#uid,'branch','BRANCH.VIEW')`) → one `BranchDto`. Create/update/
> `set-default`/archive are `BRANCH.MANAGE` administrative routes, out of scope for POS.

---

## 5. Numeric `companyId` vs string `uid` — which the POS endpoints want

This is the part that trips up external clients. The platform exposes **two identifiers**
for every record:

- **`uid`** — an opaque string (e.g. `"co_4Bn1Zx"`), used in **path variables**
  (`/companies/uid/{uid}`, `/branches/uid/{uid}`, and every POS `…/uid/{uid}` route) and
  in most request bodies.
- **`id`** — the numeric primary key (a `Long`), serialised as a JSON **string** (§6).

The POS **list** endpoints bind the **numeric `id`** as query params, not the uid:

| POS endpoint | Param(s) | Source field |
|---|---|---|
| `GET /api/v1/pos/tills?companyId=&branchId=` | `companyId` (Long), `branchId` (Long) | `PosTillController.listByBranch(@RequestParam Long companyId, @RequestParam Long branchId)` → `CompanyDto.id`, `BranchDto.id` |
| `GET /api/v1/pos/sessions?companyId=&page=&size=&sort=` | `companyId` (Long) | `PosSessionController.list(@RequestParam Long companyId, Pageable)` → `CompanyDto.id` |

So the resolution chain for a POS that lists tills/sessions is:

1. `GET /organisations/current` → `OrganisationDto.uid`
2. `GET /companies?organisationUid=<uid>` → pick a `CompanyDto`; keep its **`id`** and `uid`
3. `GET /branches?companyUid=<company uid>` → pick a `BranchDto`; keep its **`id`** and `uid`
4. `GET /pos/tills?companyId=<company id>&branchId=<branch id>` (numeric ids)

By contrast, the **write/lifecycle** POS calls take **uids** in the body/path:

- `POST /api/v1/pos/tills` body `CreatePosTillRequest{ String companyUid, Long branchId,
  String name, String cashBankAccountUid? }` — note this one is **mixed**: `companyUid`
  (string) **and** `branchId` (numeric Long).
- `POST /api/v1/pos/sessions` body `OpenSessionRequest{ String tillUid, BigDecimal
  openingFloatAmount }` — the session inherits its company/branch from the till, so no
  company/branch id is passed here.
- All `…/uid/{uid}/…` session and till routes use the **uid** path variable.
- `POST /api/v1/pos/sales` takes **no** company/branch in the body at all — scope comes
  from the JWT (or `X-Branch-Uid`) plus the resolved session's company, per the shared
  contract.

Rule of thumb: **path variables and most bodies use `uid`; the two POS *list* query params
use the numeric `id`; `CreatePosTillRequest` is the odd one out (`companyUid` + numeric
`branchId`).** Always read both `id` and `uid` off the `CompanyDto`/`BranchDto` you
resolved and use whichever the target endpoint declares.

curl — list tills using the resolved numeric ids:

```bash
curl -s "https://erp.example.com/api/v1/pos/tills?companyId=10&branchId=100" \
  -H "Authorization: Bearer $ACCESS_TOKEN"
```

(`POS.TILL.VIEW` required; returns a plain `List<PosTillDto>`, `meta` null — not paged.)

curl — list POS sessions for a company (paged):

```bash
curl -s "https://erp.example.com/api/v1/pos/sessions?companyId=10&page=0&size=20&sort=createdAt,desc" \
  -H "Authorization: Bearer $ACCESS_TOKEN"
```

(`POS.SESSION.VIEW` required; `meta` is a `PageMeta` per the shared pagination contract.)

---

## 6. ID serialisation — every numeric id is a JSON string

Per `com.erp.platform.common.config.JacksonConfig`, the platform registers a global
`ToStringSerializer` for `Long` and `long`. Consequences for a POS client:

- **On the wire, every numeric id is a string**: `"id": "10"`, `"companyId": "10"`,
  `"branchId": "100"`, `"organisationId": "1"`, `"managerId": "42"`. This keeps 64-bit ids
  safe across JavaScript's 53-bit number precision.
- **On the way in, Jackson accepts both** `42` and `"42"` and coerces to `Long`. So when
  you pass `companyId` / `branchId` as query params, either form works
  (`?companyId=10` and `?companyId="10"` both bind). Plain `?companyId=10` is simplest.
- Type your client model's id fields as **strings** to round-trip them without precision
  loss (the web client does exactly this).
- `uid` is already a string and needs no special handling.

A non-numeric or uncoercible value for a numeric query param (e.g.
`?companyId=abc`) is a `MethodArgumentTypeMismatchException` → **400** per the shared error
table.

---

## 7. End-to-end context bootstrap (recommended POS flow)

```bash
# 0. Authenticate (see 01-auth-and-conventions); capture accessToken + AuthUser.
ACCESS_TOKEN="…"          # from POST /api/v1/auth/login -> TokenResponse.accessToken

# Fast path: the login response already gives you activeCompanyUid / activeBranchUid.
# Only walk the hierarchy below if you need a picker or the numeric ids.

# 1. Which organisation?
curl -s https://erp.example.com/api/v1/organisations/current \
  -H "Authorization: Bearer $ACCESS_TOKEN"
#   -> data.uid  = org_7Hk2pQ

# 2. Companies in that org (requires organisationUid):
curl -s "https://erp.example.com/api/v1/companies?organisationUid=org_7Hk2pQ" \
  -H "Authorization: Bearer $ACCESS_TOKEN"
#   -> pick a CompanyDto: keep .id (10) and .uid (co_4Bn1Zx) and .baseCurrency (TZS)

# 3. Branches in that company (requires companyUid; scope-checked):
curl -s "https://erp.example.com/api/v1/branches?companyUid=co_4Bn1Zx" \
  -H "Authorization: Bearer $ACCESS_TOKEN"
#   -> pick a BranchDto: keep .id (100) and .uid (br_9Qd3Mn)
#   (alternatively GET /api/v1/auth/my-branches for the caller's own switchable branches)

# 4. Tills on that branch (numeric ids):
curl -s "https://erp.example.com/api/v1/pos/tills?companyId=10&branchId=100" \
  -H "Authorization: Bearer $ACCESS_TOKEN"
#   -> pick a PosTillDto: keep .uid for OpenSessionRequest.tillUid

# 5. Open a session on that till (uid in body):
curl -s -X POST https://erp.example.com/api/v1/pos/sessions \
  -H "Authorization: Bearer $ACCESS_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"tillUid":"till_…","openingFloatAmount":"100000.00"}'
```

From here you proceed to the till/session/sale sections of this integration guide. Once you
hold a company `id`, a branch `id`/`uid`, and an OPEN session uid, the POS sale path needs
nothing more about context — `POST /api/v1/pos/sales` derives scope from the JWT (or
`X-Branch-Uid`) and the session's company.

---

## 8. Permission cheat-sheet for this section

| Endpoint | Permission (SpEL) |
|---|---|
| `GET /api/v1/organisations/current` | `isAuthenticated()` |
| `GET /api/v1/organisations` | `@perm.has('COMPANY.VIEW')` |
| `GET /api/v1/companies?organisationUid=` | `@perm.has('COMPANY.VIEW')` |
| `GET /api/v1/companies/uid/{uid}` | `@perm.scoped(#uid,'company','COMPANY.VIEW')` |
| `GET /api/v1/branches?companyUid=` | `@perm.scoped(#companyUid,'company','BRANCH.VIEW')` |
| `GET /api/v1/branches/uid/{uid}` | `@perm.scoped(#uid,'branch','BRANCH.VIEW')` |
| `GET /api/v1/auth/my-branches` | `isAuthenticated()` (self-scoped) |
| `GET /api/v1/pos/tills?companyId=&branchId=` | `@perm.has('POS.TILL.VIEW')` |
| `GET /api/v1/pos/sessions?companyId=` | `@perm.has('POS.SESSION.VIEW')` |

`root` users (`AuthUser.isRoot == true`) bypass these permission checks; for non-root
users, `GET /api/v1/auth/me` returns the effective permission codes for the active scope so
the POS can tell up-front whether it holds the `COMPANY.VIEW` / `BRANCH.VIEW` / `POS.*`
codes it needs.
