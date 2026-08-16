# Onboarding a tenant onto the shared instance

*Written 2026-08-15. Every command below was executed against a throwaway two-tenant stack before
this document was published; the response bodies are copied from that run, not composed. Where a
step could not be executed it says so.*

This is the document you follow to put a new paying customer onto OrbixERP. It assumes the
application is already installed and running with at least one organisation on it — the **first**
tenant arrives via `install.sh` and `BootstrapRunner`, not via this runbook.

The topology is a **shared instance** (owner decision D-11, 2026-08-15): both customers live in one
database, one application, separated by `organisation_id`. That is what makes the isolation probes
in §8 mandatory rather than interesting.

> ## ⛔ Read this before you onboard a real customer
>
> **There is an open cross-tenant authority hole, and it is measured, not theoretical.** Every
> tenant's administrator is created with `is_root = true`
> (`TenantProvisioningService.provision`), and `PermissionResolver.hasPermission` short-circuits
> `true` for root on every permission code. On a shared instance that means **the new customer's own
> administrator can suspend the existing customer's organisation** — one HTTP request, no exploit.
> Reproduced on the rehearsal stack; the measurement is in
> [§8.2](#82--the-known-hole--do-not-record-this-as-a-pass).
>
> The reads are closed. The writes are not. Until the platform tier lands, onboarding customer #2
> onto a shared box means **accepting** that either customer's admin can deny service to the other.
> That is a decision for the owner to make explicitly and date, not one to discover afterwards.
>
> The blocker list lives in [tenant-two-readiness.md](tenant-two-readiness.md) — it is not repeated
> here.

---

## 1 · Prerequisites

| # | Prerequisite | How to check |
|---|---|---|
| 1 | The application is running and healthy | `curl -s http://127.0.0.1:9090/actuator/health` → `{"status":"UP"}` |
| 2 | At least one organisation exists | `SELECT id, name, alias, status FROM organisations ORDER BY id;` |
| 3 | You hold credentials for an **`is_root`** account (see §2) | `POST /api/v1/auth/login` returns `"isRoot": true` |
| 4 | **A fresh backup exists, taken minutes ago** | `./orbixerp.sh backup`, and note the filename |
| 5 | You have the customer's real trading details | see the pre-flight worksheet in §3 |

**On prerequisite 4.** This runbook's one call writes **more than 120 rows across at least 20
tables** (measured — see §5) inside a single transaction, into the database **both customers
share**. The transaction is all-or-nothing
(`TenantProvisioningService.provision` is `@Transactional`), so a *failure* is clean — but a
*success with the wrong values* is not, and the most important of those values cannot be changed
afterwards (§3.1). Take the backup.

**There is no web UI for this.** `web/src/app/features/admin/organisation/organisation.service.ts`
exposes only `current()` and `list()`; nothing in the Angular client calls `POST
/api/v1/organisations`. This is an HTTP runbook by necessity, not by preference.

---

## 2 · Who may do this, and the honest answer about permissions

The endpoint is gated on **`ORG.CREATE`**:

```java
// backend/src/main/java/com/erp/api/OrganisationController.java
@PostMapping
@ResponseStatus(HttpStatus.CREATED)
@PreAuthorize("@perm.has('ORG.CREATE')")
public OrganisationDto createTenant(@Valid @RequestBody CreateTenantRequest request) { … }
```

Its siblings `ORG.SUSPEND` (suspend/resume) and `ORG.VIEW` (list) work the same way.

### 2.1 · `ORG.CREATE` is effectively root-only today

`ORG.CREATE` and `ORG.SUSPEND` are seeded into the **`platform`** permission module, and the grant
that gives every tenant administrator their permissions explicitly excludes that module:

```sql
-- R__seed_permissions.sql
('ORG.CREATE',  'platform', 'Provision a new tenant organisation (platform operator only)'),
('ORG.SUSPEND', 'platform', 'Suspend or resume a tenant organisation (platform operator only)')
…
INSERT INTO role_permission (role_id, permission_id)
SELECT r.id, p.id FROM roles r CROSS JOIN permissions p
WHERE  r.code = 'ORG_ADMIN'
  AND  p.module <> 'platform'          -- ← the exclusion
ON CONFLICT DO NOTHING;
```

That exclusion is real, tested (`PlatformPermissionBoundaryTest`), and correct. **No shipped role
grants `ORG.CREATE` to anybody.** So in practice the only accounts that can call this endpoint are
`is_root` accounts, which reach it by bypass rather than by grant:

```java
// backend/src/main/java/com/erp/platform/security/PermissionResolver.java
if (principal.root()) {
    …
    return true;      // root bypasses every permission code
}
```

**`PLATFORM_OPERATOR` does not exist as data.** It is a single string literal in
`AuthorityCeiling.java` — no seeded role row, no migration inserts it, zero hits across all 104
migration files. The platform tier the permission descriptions refer to ("platform operator only")
has not been built.

### 2.2 · What that actually means for you

Three consequences, all uncomfortable, all worth stating plainly:

1. **The vendor's platform capability is exercised through a customer's credential.** On a shared
   box the account that created the *first* organisation is that organisation's `rootadmin`. When
   you use it to create customer #2, you are acting as customer #1's root user.
2. **The new customer inherits the same capability.** `TenantProvisioningService` sets
   `admin.setRoot(true)` on every tenant administrator it creates — including the one this runbook
   creates. Customer #2's admin can then create further organisations, and suspend customer #1.
3. **Granting `ORG.CREATE` to a non-root user does not help.** It is technically possible — a root
   user can build a role carrying it — but `AuthorityCeiling.assertCanConfer` only lets a non-root
   caller confer a subset of what they already hold, so the grant can only ever originate from
   root. And `RoleServiceImpl.create` stamps the new role with the *caller's* organisation, so the
   role is tenant-scoped, not a platform role. It moves the problem; it does not fix it.

The fix is the platform tier (a real `PLATFORM_OPERATOR`, and gates on these two endpoints that do
not honour the root bypass). Until then, treat "who may onboard a tenant" as an operational
control — a credential kept by the vendor — and not as something the software enforces.

---

## 3 · Pre-flight — settle these before you make the call

### 3.1 · Compute the alias by hand, first

The **alias** is the `@alias` half of every username issued under this tenant. It is derived from
`organisationName`, it is set once, and **nothing in the product can change it afterwards** — there
is exactly one `setAlias` call on the create path and one in the reconciler's null-backfill, and no
`setUsername` exists anywhere in `backend/src/main`.

```java
// backend/src/main/java/com/erp/platform/bootstrap/OrganisationAlias.java
String slug = name.toLowerCase(Locale.ROOT)
        .replaceAll("[^a-z0-9]+", "-")     // every non-alphanumeric run → one hyphen
        .replaceAll("^-+|-+$", "");        // strip leading/trailing hyphens
if (slug.length() > 20) {
    slug = slug.substring(0, 20).replaceAll("-+$", "");   // ← TRUNCATED TO 20
}
if (slug.length() < 2) return "org-" + id;                // fallback
```

**The 20-character truncation is where this goes wrong**, and it goes wrong quietly. Two measured
examples from the rehearsal run:

| `organisationName` | alias you get | admin username you get |
|---|---|---|
| `Kilimanjaro Supermarket` | `kilimanjaro-supermar` | *(bootstrap — stays bare `rootadmin`)* |
| `Jambo Bora Traders Ltd` | `jambo-bora-traders-l` | `admin@jambo-bora-traders-l` |

Neither is a typo. `jambo-bora-traders-ltd` is 22 characters, so it is cut to 20 mid-word, and every
member of staff that tenant ever hires logs in with `…@jambo-bora-traders-l` forever.

**If the slug truncates badly, shorten `organisationName` before you call** — `Jambo Bora Traders`
gives `jambo-bora-traders`. The trading name shown on documents comes from `companyName` and the
Document Branding screen, not from `organisationName`, so a shorter organisation name costs nothing
the customer will ever see.

### 3.2 · Check the alias does not collide

Two constraints matter, and the alias is protected by both a format check and a **partial unique
index**:

```sql
-- V99__multitenancy_expand.sql — shape
ALTER TABLE organisations ADD CONSTRAINT ck_organisation_alias
    CHECK (alias ~ '^[a-z0-9][a-z0-9-]{0,18}[a-z0-9]$') NOT VALID;

-- V100__organisation_alias_unique.sql — uniqueness
CREATE UNIQUE INDEX IF NOT EXISTS uq_organisation_alias
    ON organisations (alias) WHERE alias IS NOT NULL;
```

So two organisations **cannot** end up sharing an alias — the database refuses. Good: the failure is
loud, and no duplicate is silently created.

**The bad part is the message.** Because the alias is derived rather than supplied, an operator who
picks a perfectly reasonable organisation name gets a rejection that mentions neither aliases nor
names. Measured — creating `Jambo Bora Traders Ltd Two` on an instance that already holds
`Jambo Bora Traders Ltd` (both slug to `jambo-bora-traders-l`):

```jsonc
{"data":null,"errors":["A record with the same unique identifier already exists."],"meta":null}
// HTTP 409
```

Nothing in that points at the 20-character truncation, and changing `adminUsername` does not help
(measured: still `409` — it is the alias, not the username). The transaction rolls back cleanly, so
the cost is confusion rather than damage. Check before you call:

```bash
docker exec -i <db-container> psql -U erp -d erp -c \
  "SELECT id, name, alias, status FROM organisations ORDER BY id;"
```

**This check needs database access; the API cannot do it.** `GET /api/v1/organisations` is scoped to
the caller's own organisation — measured: as the root admin of a tenant on a two-organisation
instance it returns a **single-element array**, its own. That is correct and deliberate (it is one of
the isolation properties in §8.1), but it means the person onboarding a tenant cannot enumerate
existing aliases over HTTP. Plan for a psql session, or record aliases as you issue them.

### 3.3 · The rest of the worksheet

| Field | Rule | Why it matters |
|---|---|---|
| `companyCode`, `branchCode` | ≤ 20 chars | appear on documents and in code sequences |
| `adminUsername` | ≤ 60, **no `@`** | rejected outright: *"The administrator username must not contain '@'."* The `@alias` is appended for you |
| `adminPassword` | **≥ 12 chars** | `@Size(min = 12)`; mirrors `ERP_BOOTSTRAP_ADMIN_PASSWORD`. Hand it over out of band — it is never returned or logged |
| `baseCurrency` | ISO-3, defaults `TZS` | lands on the company row; ~40 read paths resolve the posting currency from it. **Not a label you fix later** |
| `companyTaxId` | the real TIN | supply it **now**. It is what gets printed on every tax invoice; verify it after creation (§7 step 7) |
| `companyVrn` | the real VRN | it will reach the Company screen and report headers but **cannot** be printed on an invoice — §6.2 |
| `posTillName` | branch-qualify it | till names are unique across the **whole company** (`uq_pos_till_company_name`), so `Till 1` at head office blocks `Till 1` at shop two. `HQ Till 1` is worth the extra word |
| `priceListIncludesVat` | **ask the shop** | `true` if the prices they will type are the prices the customer pays — the normal case for TZ retail. Omit it and the list is VAT-**exclusive**, so 18% is added on top of every shelf price at invoicing. `sales_invoice_lines.price_inclusive` is snapshotted per line, so flipping the flag later fixes nothing already sold |

---

## 4 · The call

### 4.1 · Authenticate

```bash
curl -s -X POST http://127.0.0.1:8081/api/v1/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"username":"rootadmin","password":"<password>"}'
```

```jsonc
{"data":{"accessToken":"eyJhbGciOiJSUzI1NiJ9…","accessTokenExpiresAt":1786803687,
         "refreshToken":"…","user":{"username":"rootadmin","isRoot":true, …}},
 "errors":[],"meta":null}
```

Confirm `"isRoot": true` — without it the next call returns 403.

```bash
TOKEN=$(curl -s -X POST http://127.0.0.1:8081/api/v1/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"username":"rootadmin","password":"<password>"}' \
  | python -c 'import sys,json; print(json.load(sys.stdin)["data"]["accessToken"])')
```

### 4.2 · Create the tenant

```bash
curl -s -X POST http://127.0.0.1:8081/api/v1/organisations \
  -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{
    "organisationName":   "Jambo Bora Traders",
    "timeZone":           "Africa/Dar_es_Salaam",

    "companyCode":        "JBT",
    "companyName":        "Jambo Bora Traders Ltd",
    "companyLegalName":   "Jambo Bora Traders Limited",
    "companyTaxId":       "123-456-789",
    "companyVrn":         "40-123456-A",

    "branchCode":         "JBT-HQ",
    "branchName":         "Head Office",

    "adminUsername":      "admin",
    "adminPassword":      "<12+ characters, handed over out of band>",
    "adminDisplayName":   "Jambo Bora Administrator",

    "baseCurrency":       "TZS",
    "defaultCurrency":    "TZS",
    "enabledCurrencies":  ["TZS"],

    "priceListCode":         "RETAIL",
    "priceListName":         "Retail",
    "priceListIncludesVat":  true,
    "walkInCustomerName":    "Walk-in Customer",
    "posTillName":           "HQ Till 1"
  }'
```

<details>
<summary>PowerShell equivalent</summary>

```powershell
$login = Invoke-RestMethod -Method Post -Uri 'http://127.0.0.1:8081/api/v1/auth/login' `
  -ContentType 'application/json' `
  -Body '{"username":"rootadmin","password":"<password>"}'
$token = $login.data.accessToken

$body = Get-Content -Raw .\tenant.json     # the JSON above, in a file
Invoke-RestMethod -Method Post -Uri 'http://127.0.0.1:8081/api/v1/organisations' `
  -Headers @{ Authorization = "Bearer $token" } `
  -ContentType 'application/json' -Body $body
```

Put the body in a file rather than inline — PowerShell's quoting will otherwise mangle it, and a
password on a command line ends up in the shell history.
</details>

### 4.3 · The response, and the one thing it does not tell you

**`201 Created`.** Measured:

```jsonc
{"data":{"id":"2","uid":"01M02VP3NHWD9VJXD340F292Z6","name":"Jambo Bora Traders Ltd",
         "legalName":null,"defaultTimeZone":"Africa/Dar_es_Salaam","status":"ACTIVE",
         "alias":"jambo-bora-traders-l"},
 "errors":[],"meta":null}
```

Three notes on that body, all of which have caught someone:

- **The login name is not in it.** Compose it yourself: `adminUsername` + `@` + `alias` →
  `admin@jambo-bora-traders-l`. Copy the alias **from this response**, never from the name you typed
  — that is the entire point of §3.1.
- **`legalName` is `null` even though you sent `companyLegalName`.** They are different columns:
  `OrganisationDto.legalName` is the *organisation's* legal name, which nothing on this path sets.
  Your company legal name did land — verify it on `companies`, not here.
- **`id` is a JSON string.** Global Jackson config; all 64-bit ids serialise as strings.

An `ORG_CREATE` audit row is written carrying the organisation name, company code and admin
username — never the password.

### 4.4 · If it fails

Each row below was triggered deliberately on the rehearsal stack; the messages are verbatim.

| Response | Message | Meaning / fix |
|---|---|---|
| `403` | — | your account lacks `ORG.CREATE`; check `"isRoot": true` on the login response (§2) |
| `400` | `The administrator username must not contain '@'.` | you put the alias in `adminUsername`; send the local part only |
| `400` | `adminPassword: size must be between 12 and 200` | lengthen the password |
| `400` | `A price list needs both a code and a name. Supply both, or leave both blank to create no price list.` | send both, or neither |
| `409` | `A record with the same unique identifier already exists.` | **alias collision** (§3.2) — pick a shorter or more distinct `organisationName` |

**Every failure rolls the whole transaction back.** Verified: after four rejected attempts the
`organisations` table still held exactly the two rows it started with. There is no half-tenant to
clean up, so fix the body and call again.

---

## 5 · What provisioning does for you — do not redo any of it

In one transaction, `TenantProvisioningService.provision` creates the organisation, derives and
saves the alias, creates the company (with time zone, base currency and tax identity), runs **23
company-scoped seeders**, creates the default branch, seeds stock locations and the petty-cash fund,
creates the price list / walk-in customer / till you named, then creates the administrator with a
default branch assignment and an `ORG_ADMIN` grant.

Measured row counts for the tenant created in §4 — this is what "provisioned" looks like:

| Table | Rows | Table | Rows |
|---|---|---|---|
| `chart_of_accounts` | 48 | `document_templates` | 7 |
| `code_sequence` | 31 | `leave_types` | 6 |
| `units_of_measure` | 15 | `party_code_sequence` | 4 |
| `tax_rates` | 3 | `stock_locations` | 2 |
| `branches` | 1 | `cash_bank_accounts` | 1 |
| `document_branding` | 1 | `petty_cash_funds` | 1 |
| `price_lists` | 1 | `customers` (walk-in) | 1 |
| `pos_tills` | 1 | `user_role` (ORG_ADMIN) | 1 |
| | | **`user_company`** | **0 ← see §6.1** |

Three things people re-do by mistake:

- **Petty cash is seeded twice by design.** The call inside `provisionDefaults` is a no-op (no branch
  exists yet — `petty_cash_funds.branch_id` is `NOT NULL`); the one after the branch is the effective
  one. Seeing it twice in the log is correct.
- **Stock locations are branch-scoped** and deliberately outside `provisionDefaults`.
- **The administrator gets `ORG_ADMIN` as a real role grant *and* `is_root`.** Both. Do not "fix" the
  apparent redundancy — the role grant is what survives when the platform tier lands.

### 5.1 · The repair lever

If a seeder is ever *added* after a tenant exists, re-run it:

```bash
curl -s -X POST http://127.0.0.1:8081/api/v1/companies/uid/<companyUid>/provision-defaults \
  -H "Authorization: Bearer $TOKEN"
```

Gated on `COMPANY.MANAGE`, idempotent, safe on a company that has traded for years. **It does not
create the price list, walk-in customer or till** — those three are outside the heal path on
purpose, so re-provisioning a live company cannot mint duplicates.

Both properties measured on the tenant created above — `200`, and every count unchanged:

```
before:  1 pricelists, 1 customers, 1 tills, 48 accounts
after:   1 pricelists, 1 customers, 1 tills, 48 accounts
```

---

## 6 · What you must still do by hand

The create call now covers tax identity, price list, walk-in customer and till. What it does **not**
cover:

### 6.1 · Every staff user needs a company membership *and* a branch — before a role will stick

This is the first support call you will get, and it is fully reproducible.

`provision()` writes a `UserBranch` and a `UserRole` for the tenant administrator, but **no
`UserCompany` row** (measured immediately after creation: `user_company = 0`). The administrator is
`is_root`, so when they create their first cashier,
`UserServiceImpl.establishCreatorCompanyMembership` **no-ops** — it returns early for root callers.
The new user therefore belongs to no company, and the role grant is refused:

```bash
# 1. create the user  →  201
curl -s -X POST http://127.0.0.1:8081/api/v1/users \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"username":"cashier","displayName":"Jambo Cashier","password":"<12+ chars>"}'
# → {"data":{"username":"cashier@jambo-bora-traders-l", …}}   (the alias is appended here too)

# 2. grant a role  →  409  ← the dead end
curl -s -X POST http://127.0.0.1:8081/api/v1/user-roles \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"userUid":"<userUid>","roleUid":"<roleUid>","companyUid":"<companyUid>","branchUid":"<branchUid>"}'
# → {"data":null,"errors":["Assign this user to the company before granting roles."]}
```

The fix, in this order:

```bash
# 2a. company membership first (USER.COMPANY.MANAGE)
curl -s -X POST http://127.0.0.1:8081/api/v1/user-companies \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"userUid":"<userUid>","companyUid":"<companyUid>"}'                    # → 201

# 2b. now the role grant succeeds
curl -s -X POST http://127.0.0.1:8081/api/v1/user-roles \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"userUid":"<userUid>","roleUid":"<roleUid>","companyUid":"<companyUid>","branchUid":"<branchUid>"}'
                                                                              # → 201

# 2c. and a branch, or the user logs in with no scope at all (BRANCH.ASSIGN)
curl -s -X POST http://127.0.0.1:8081/api/v1/user-branches \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"userUid":"<userUid>","branchUid":"<branchUid>","makeDefault":true}'   # → 201
```

**Step 2c is not optional and its failure mode is silent.** A user with no branch assignment logs in
successfully — measured:

```jsonc
{"username":"cashier@jambo-bora-traders-l","isRoot":false,
 "activeCompanyUid":null,"activeBranchUid":null,"hasBranch":false}
```

`HTTP 200`, no error. But `PermissionResolver.resolve` returns an **empty** permission set when
`companyId` is null, so the user has no permissions at all and every screen is empty or forbidden.
It reads as "the system is broken", not as "assign me to a branch".

After all three steps, the same login returns a working session — measured:

```jsonc
{"username":"cashier@jambo-bora-traders-l","isRoot":false,
 "activeCompanyUid":"01M02VP3NR6E413TV7ZK7ACTZ9",
 "activeBranchUid":"01M02VP8GJ26XF8KZZ3Z5ZGP39","hasBranch":true}
```

and `GET /api/v1/auth/me` reports **31** effective permissions for a `CASHIER` grant. That call is
the quickest way to tell "this user is mis-provisioned" from "this user lacks a role": a permission
count of **0** means step 2a or 2c was skipped.

> **Why you may not be able to reproduce this later.** `UserCompanyBackfill` is an `ApplicationRunner`
> that reconciles `user_company` from active role grants and branch assignments **on every boot**.
> Measured: after one restart of the two-tenant stack it logged *"seeded 3 user_company rows from 3
> (user, company) pairs"* and the tenant administrator's missing membership was gone.
>
> So the gap is **real but time-bounded** — it exists from the moment the tenant is created until the
> next application restart, which on a live installation may be days. Onboarding day is squarely
> inside that window, which is exactly when the tenant's first staff are created. Do step 2a; do not
> wait for a restart to paper over it.
>
> Note also what the backfill derives from: role grants **and** branch assignments. A user with
> neither — created but never assigned — gets no membership from it either. Measured: the staff user
> created on tenant A with no role and no branch still had **no** `user_company` row after the
> restart.

### 6.2 · The rest

| Item | Why it is not automatic | Symptom if skipped |
|---|---|---|
| **VRN on printed documents** | `document_branding` has **no `vrn` column** (V19 has `tax_id` only), `BrandingBlock` has no `vrn` component, and `DocumentPdfRenderer` prints only a `TIN/VAT:` line. A per-document VRN, overridable on the Document Branding screen like the TIN, is a schema change and needs owner approval. | The VRN reaches the Company screen and report headers and **cannot** reach a printed invoice, so a VAT-registered supplier's "TAX INVOICE" is not compliant. Tell the customer up front rather than letting them hunt for the setting. |
| **Second and subsequent branches** | only the one branch in the request is created | — |
| **A till per additional branch** | only one till is created, on the default branch | `PosSessionServiceImpl` returns `NotFound`; no session can be opened |
| **Verify the till was actually created** | `PosTillProvisioner` **returns quietly** if the company has no cash/bank account, logging a WARN rather than failing the tenant | you asked for a till and silently did not get one — check `pos_tills` (§7 step 5) |
| **Fiscalisation (TRA/VFD)** | `erp.fiscal.provider` is a single JVM-wide property, and only `none`/`simulated` exist — **no TRA adapter is built for anyone** | not per-tenant configurable today; a legal gate on go-live independent of this runbook |
| **Mail `From` address** | `EmailSender` never calls `setFrom`; one `spring.mail.*` block per process | on a shared instance the new customer's notifications go out under the other customer's identity |

The last two are **not per-tenant at all on a shared instance.** Do not promise the new customer a
different VFD provider or their own sending address.

---

## 7 · Verification checklist

Run these in order. Stop at the first failure.

**1 · The create returned `201`** and you have recorded the `uid` and the `alias`.

**2 · The administrator can log in** with the composed name:

```bash
curl -s -X POST http://127.0.0.1:8081/api/v1/auth/login -H 'Content-Type: application/json' \
  -d '{"username":"admin@jambo-bora-traders-l","password":"<password>"}'
```

A `401` here is almost always the alias: re-read it from the create response, not from the name you
typed.

**3 · `GET /api/v1/organisations/current` names the NEW organisation.** Measured, as the new admin:

```jsonc
{"data":{"id":"2","name":"Jambo Bora Traders Ltd","status":"ACTIVE",
         "alias":"jambo-bora-traders-l"}, "errors":[],"meta":null}
```

If this returns the *other* customer's organisation, **stop** — the principal is not carrying
`organisationId` and nothing below is trustworthy.

**4 · `GET /api/v1/organisations` returns exactly one row**, the new one. Measured: a single-element
array.

**5 · The trading essentials exist.** These list endpoints all take a **required** company
parameter — calling them bare returns `400` *"This request was missing some required information"*,
which looks like a broken tenant and is not. Resolve the ids first, from the API:

```bash
# organisationUid is the `uid` from the create response in §4.3
curl -s "http://127.0.0.1:8081/api/v1/companies/accessible?organisationUid=<orgUid>" \
  -H "Authorization: Bearer $TOKEN"
# → [{"id":"2","uid":"01M02VP3NR6E413TV7ZK7ACTZ9","code":"JBT","name":"Jambo Bora Traders Ltd"}]

curl -s "http://127.0.0.1:8081/api/v1/branches?companyUid=<companyUid>" \
  -H "Authorization: Bearer $TOKEN"
# → [{"id":"2","uid":"01M02VP8GJ26XF8KZZ3Z5ZGP39","code":"JBT-HQ","isDefault":true}]
```

Then, with `CID` = that numeric company id and `BID` = the branch id:

```bash
for p in "price-lists?companyId=$CID" "customers?companyId=$CID" "products?companyId=$CID" \
         "branches?companyUid=$CUID" "pos/tills?companyId=$CID&branchId=$BID"; do
  printf '%-28s -> ' "${p%%\?*}"
  curl -s -o /dev/null -w '%{http_code}\n' "http://127.0.0.1:8081/api/v1/$p" -H "Authorization: Bearer $TOKEN"
done
```

All five `200`. Then check the **contents**, not just the status — measured on the rehearsal tenant:

| Endpoint | Expected | Measured |
|---|---|---|
| `price-lists` | one list, `isDefault: true` | `{"code":"RETAIL","name":"Retail","isDefault":true}` |
| `customers` | the counter customer | `{"code":"CUST-0001","displayName":"Walk-in Customer","partyType":"INDIVIDUAL"}` |
| `pos/tills` | one till on the default branch | `{"code":"TILL-0001","name":"HQ Till 1","status":"ACTIVE"}` |

**The till is the one that can be silently absent** (§6.2) — a `200` with an empty array is the
failure, and it is easy to read as a pass.

**6 · Ring a test sale end to end** and confirm it posts to the GL. This needs §6.1 done for at
least one non-root cashier, because a cashier with no branch has no permissions.

**7 · Print an INVOICE and confirm the TIN line is populated** — not merely that the PDF renders.
Seven templates are seeded (`INVOICE`, `AR_STATEMENT`, `PURCHASE_ORDER`, `GOODS_RECEIPT`,
`DELIVERY_NOTE`, `CREDIT_NOTE`, `QUOTATION`). A document headed "TAX INVOICE" with no TIN is not a
valid Tanzanian tax invoice.

> Historically this was the step that failed, because `DocumentBrandingSeeder` snapshots
> `companies.legal_name` and `companies.tax_id` **once**, only on the pass that creates the branding
> row. Either one filled in afterwards reached the Company screen and never the printed page.
> `DocumentModelBuilder` now falls back to the company row for both, which repairs the new tenant and
> any tenant created before the create path captured them. **Still verify it** — it is one query and
> it is the difference between a lawful invoice and an unlawful one.
>
> The fallback fires only when the branding column was **never written** (`NULL`). A branding value
> that is present but empty means an administrator saved the Document Branding screen with that field
> cleared, and that is their only way to keep a superseded `companies.tax_id` off a tax document — so
> it is honoured and nothing is substituted. If a tenant's invoices print no TIN while
> `companies.tax_id` holds one, look for `''` in `document_branding.tax_id`: the fix is to type the
> number on the Document Branding screen, not to widen the fallback.

**8 · Restart the API once and read the log.** Measured on the two-organisation stack — these four
lines are the pass:

```
o.f.core.internal.command.DbValidate : Successfully validated 104 migrations
o.f.core.internal.command.DbMigrate  : Schema "public" is up to date. No migration necessary.
c.e.p.bootstrap.UserCompanyBackfill  : seeded 3 user_company rows from 3 (user, company) pairs.
c.e.p.bootstrap.TenancyReconciler    : Tenancy reconcile: 2 organisations present — role
                                       attribution is left to provisioning, not inferred.
c.e.p.bootstrap.TenancyReconciler    : Tenancy reconcile: all users and customer roles are attributed.
```

`2 organisations present — role attribution is left to provisioning` is **expected** on a shared
instance and is not a warning: with more than one organisation the reconciler correctly stops
inferring. What you are looking for is *"all users and customer roles are attributed"* and the
**absence** of residual warnings, alias-collision warnings (R-1) and username suffix drift.

A non-zero `UserCompanyBackfill` count here is the fingerprint of §6.1 — someone was left without a
membership and the restart fixed it. Zero is what you want on a tenant you onboarded properly.

---

## 8 · Isolation probes — mandatory on a shared instance

These are the probes from [two-tenant-local-stack.md §4](two-tenant-local-stack.md), re-run with the
**correct identity**. That document's tenant B admin is seeded `is_root = false`, which is *not*
what the product produces — every API-provisioned tenant administrator is root. The table below was
re-measured 2026-08-15 with a root, API-provisioned admin.

### 8.1 · Reads — these hold

Log in as the **new** customer's administrator and reach for the **existing** customer.

| Request, as the new tenant's root admin | Result | Control |
|---|---|---|
| `GET /organisations/current` | **200** · own org only | `OrganisationServiceImpl.current` scopes to the principal |
| `GET /organisations` | **200** · one row, own | `findAllVisibleTo` |
| `GET /users` | **200** · own users only | P3-8 |
| `GET /products?companyId=<other>` | **403** | `ScopeGuard.canActIn` — tenant test runs *before* root's short-circuit |
| `GET /customers?companyId=<other>` | **403** | same |
| `GET /companies/uid/<other>` | **403** | `canActOn` applies the tenant test inside root's branch |
| `GET /companies?organisationUid=<other>` | **404** | not-found, so there is no existence oracle |
| `GET /users/uid/<other's admin>` | **404** | P3-8 |
| `GET /products` with `X-Branch-Uid: <other's branch>` | **403** | refused at the servlet filter, before the principal is built |

All nine measured on the rehearsal stack. **The read boundary genuinely holds, including for root.**

*Reading note (carried from the original table): a non-root caller may get 403 where root gets 404,
because the permission gate fires before the tenancy check. Both are refusals; they come from
different layers.*

### 8.2 · The known hole — do not record this as a pass

```bash
# as the NEW customer's administrator, against the EXISTING customer's organisation uid
curl -s -X POST http://127.0.0.1:8081/api/v1/organisations/uid/<other-org-uid>/suspend \
  -H "Authorization: Bearer $TOKEN_NEW_TENANT"
```

**Measured result: `HTTP 200`.** The other customer's organisation is set to `INACTIVE`:

```jsonc
{"data":{"id":"1","name":"Kilimanjaro Supermarket","status":"INACTIVE",
         "alias":"kilimanjaro-supermar"},"errors":[],"meta":null}
```

Why it is not caught: `OrganisationServiceImpl.setStatus` resolves the target through a bare
`organisations.findByUid(uid)` with no tenant predicate, and the only guard refuses suspending *your
own* organisation — exactly inverted for this case. `ScopeGuard` is never reached, because these
endpoints use `@perm.has` rather than `@perm.scoped`.

**Blast radius, measured.** Suspension bites at login and root is exempt, so:

| Account on the suspended tenant | Login result |
|---|---|
| their `rootadmin` (root) | **200** — still gets in, and can resume |
| a normal staff user | **401** — *"This account is not available at the moment. Please contact your administrator."* |

Every till and every clerk is locked out, with a message that gives their administrator no idea what
happened; the tenant's own root admin can undo it. It is recoverable, and it is a one-request denial
of service by one paying customer against another.

**The create endpoint has the same shape.** `POST /api/v1/organisations` is gated on `ORG.CREATE`
with the same `@perm.has`, so any tenant's administrator is authorised to mint further tenants —
each with another root account. Measured precisely: four create attempts from a tenant admin
returned body-validation and constraint errors (`400`, `409`) and **never `403`**, which is what
proves the authorisation gate was passed. A third tenant was not actually created; the boundary, not
the row, was the point.

**What stands between a customer and this today is not authorisation — it is not knowing the other
organisation's `uid`.** That is a ULID, so it is not guessable, and the scoped read endpoints do not
hand it over (§8.1). But a uid is a URL fragment: it appears in support tickets, screenshots and
shared links. **Secrecy of an identifier is not an access control**, and this should not be recorded
as mitigated because "they would have to know the uid".

**What to do about it in this runbook:** nothing technical — the fix is a code change to the
platform tier, tracked in [tenant-two-readiness.md](tenant-two-readiness.md). Operationally:

1. **Prefer not to onboard onto a shared instance until the platform tier lands.**
2. If the owner decides to proceed anyway, record §8.2 as a **known, accepted, dated exception** —
   not as a passed probe — and keep the vendor's root credential off customer machines.
3. Re-run §8.2 after the platform tier ships. The expected result changes to **403**, and that is
   the regression check.

---

## 9 · Rehearsing this runbook

Never rehearse against the local dev volume, QA, or production. Stand up a throwaway stack with its
own container, its own port and its own lifetime — the precedent is
[rehearsal-stack.md](rehearsal-stack.md):

```bash
docker run -d --name erp-onboard-rehearsal-db -p 127.0.0.1:5446:5432 \
  -e POSTGRES_DB=erp -e POSTGRES_USER=erp -e POSTGRES_PASSWORD=erp postgres:15

cd backend
SPRING_DATASOURCE_URL="jdbc:postgresql://127.0.0.1:5446/erp" \
SPRING_DATASOURCE_USERNAME=erp SPRING_DATASOURCE_PASSWORD=erp \
ERP_API_PORT=8099 ERP_MANAGEMENT_PORT=9099 \
ERP_BOOTSTRAP_ENABLED=true ERP_BOOTSTRAP_ORG_NAME="Tenant A Ltd" \
ERP_BOOTSTRAP_COMPANY_NAME="Tenant A Co" \
ERP_BOOTSTRAP_ADMIN_USERNAME=rootadmin ERP_BOOTSTRAP_ADMIN_PASSWORD=RootPass12345 \
SPRING_DEVTOOLS_RESTART_ENABLED=false \
mvn -o -B spring-boot:run -Dspring-boot.run.profiles=dev
```

Then follow §4–§8 literally, and tear it down:

```bash
docker rm -f erp-onboard-rehearsal-db
```

Two notes from doing exactly this:

- **Disable DevTools** (`SPRING_DEVTOOLS_RESTART_ENABLED=false`). Anything that writes to
  `target/classes` — another terminal running `mvn compile`, an IDE auto-build — restarts the
  application mid-probe, and the failure looks like a dropped connection rather than a restart.
- Ports 8099/9099/5446 are deliberately non-default so this cannot collide with the ordinary dev
  stack.

---

## 10 · Related documents

- [tenant-two-readiness.md](tenant-two-readiness.md) — the blocker list for customer #2. **Read it
  before onboarding**; it is not duplicated here.
- [two-tenant-local-stack.md](two-tenant-local-stack.md) — the disposable two-tenant stack and the
  original boundary-probe table.
- [rehearsal-stack.md](rehearsal-stack.md) — the restored-from-production stack, and the rules for
  handling a customer's data on a laptop.
- [release-staging-and-rollback.md](release-staging-and-rollback.md) — why a two-customer instance
  has no canary, and the five gates that become the only defence.
- [backup-restore.md](backup-restore.md) — and note that on a shared instance **restore is not
  per-tenant**: rolling one customer back drops the schema both customers live in.
- `MULTITENANCY-PLAN.md` D-11 — the shared-instance decision and what it reopened.
