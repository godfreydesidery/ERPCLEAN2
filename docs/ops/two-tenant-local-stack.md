# Running two tenants locally, and probing the boundary

*Written 2026-08-15 alongside `TwoOrganisationIsolationIT` (MULTITENANCY-PLAN.md P7-1/P7-2).*

Every real environment — local, QA, production — holds exactly **one** organisation. That makes the
tenant boundary the one security property nobody can see working during ordinary development. This
recipe stands up a throwaway two-tenant stack in about two minutes so you can watch it refuse.

> **Use a throwaway database.** Never seed a second organisation into QA or a customer's database:
> tenancy changes what those environments *mean*, and the owner's standing decision (2026-08-15) is
> that test organisations do not go near them. Everything below runs against a container you delete
> afterwards.

## 1 · A disposable database

```bash
docker run -d --name erp-twoorg-db -p 5445:5432 \
  -e POSTGRES_DB=erp -e POSTGRES_USER=erp -e POSTGRES_PASSWORD=erp postgres:15
```

## 2 · Boot the API against it

Bootstrap creates tenant A (organisation + company + branch + root admin) on the empty schema, and
Flyway runs the whole migration chain — which is itself worth watching, since it is the only place
the full chain runs from scratch.

```bash
cd backend
SPRING_DATASOURCE_URL="jdbc:postgresql://localhost:5445/erp" \
SPRING_DATASOURCE_USERNAME=erp SPRING_DATASOURCE_PASSWORD=erp \
ERP_API_PORT=8099 ERP_MANAGEMENT_PORT=9099 \
ERP_BOOTSTRAP_ENABLED=true ERP_BOOTSTRAP_ORG_NAME="Tenant A Ltd" \
ERP_BOOTSTRAP_COMPANY_NAME="Tenant A Co" \
ERP_BOOTSTRAP_ADMIN_USERNAME=rootadmin ERP_BOOTSTRAP_ADMIN_PASSWORD=RootPass12345 \
mvn -o -B spring-boot:run -Dspring-boot.run.profiles=dev
```

Ports are deliberately non-default (8099/9099/5445) so this cannot collide with the ordinary dev
stack, or with whatever else is on 4200 — see the local-e2e-stack notes.

## 3 · Seed tenant B

> **⚠ Read this before using the SQL below — corrected 2026-08-15.**
>
> **The organisation-create endpoint shipped** (P5-2): `POST /api/v1/organisations`, gated on
> `ORG.CREATE`. This section predates it and the sentence that used to open it — "there is no
> organisation-create endpoint yet" — was wrong from the moment P5-2 merged.
>
> **For anything except probing the boundary, use the endpoint.** The SQL below inserts four rows
> and stops. `TenantProvisioningService` runs twenty-three company-scoped seeders: chart of
> accounts, fiscal calendar, GL config, AR/AP, tax rates, units, document branding, stock locations,
> leave types, document-number sequences. **A tenant seeded by this SQL has none of them** — it can
> be logged into and can post almost nothing, and the failures it produces are not the ones you are
> testing for.
>
> The SQL is kept because it is still the right tool for **one** job: making a second tenant exist
> as cheaply as possible in order to probe the isolation boundary (§4), where the seeded defaults
> are irrelevant and skipping them makes the stack quicker to build.

Tenant B goes in by SQL. The password hash is copied from `rootadmin`, so both accounts share one
password and you do not need to generate a bcrypt hash.

```bash
docker exec -i erp-twoorg-db psql -U erp -d erp -v ON_ERROR_STOP=1 <<'SQL'
INSERT INTO organisations (id, uid, name, alias, default_time_zone, status, version, created_at)
VALUES (nextval('organisations_id_seq'), '01TENANTB000000000000000AA', 'Tenant B Ltd', 'tenant-b',
        'Africa/Dar_es_Salaam', 'ACTIVE', 0, now());

INSERT INTO companies (id, uid, organisation_id, code, name, time_zone, status, version, created_at, base_currency)
SELECT nextval('companies_id_seq'), '01TENANTBCO0000000000000AA', o.id, 'B1', 'Tenant B Co',
       'Africa/Dar_es_Salaam', 'ACTIVE', 0, now(), 'TZS'
FROM organisations o WHERE o.alias = 'tenant-b';

INSERT INTO branches (id, uid, company_id, code, name, status, version, created_at)
SELECT nextval('branches_id_seq'), '01TENANTBBR0000000000000AA', c.id, 'B-BR01',
       'Tenant B Head Office', 'ACTIVE', 0, now()
FROM companies c WHERE c.code = 'B1';

INSERT INTO app_users (id, uid, username, password_hash, display_name, is_root, status, version, created_at, organisation_id)
SELECT nextval('app_users_id_seq'), '01TENANTBUSER000000000AA', 'adminb', u.password_hash, 'Admin B',
       false, 'ACTIVE', 0, now(), o.id
FROM app_users u, organisations o
WHERE u.username = 'rootadmin' AND o.alias = 'tenant-b';
SQL
```

**Use `docker exec -i`.** Without `-i` the heredoc never reaches psql, and the command reports
success having executed nothing — a trap that has cost time on this project before.

## 4 · Probe the boundary

Log in as `rootadmin` (root of tenant A) and reach for tenant B. **Root is the interesting caller**:
`is_root` is deployment-global, so it is precisely the account for which the boundary must still
hold. Measured 2026-08-15 on this stack:

| Request as root of A | Result | Control |
|---|---|---|
| `GET /organisations/current` | 200 · *Tenant A Ltd* | — |
| `GET /products?companyId=1` (own) | 200 | — |
| `GET /products?companyId=2` (B's) | **403** | P3-11 — the check inside `canActIn` |
| `GET /customers?companyId=2` | **403** | P3-11 |
| `GET /companies/uid/<B>` | **403** | P3-8 — root no longer short-circuits `canActOn` |
| `GET /companies?organisationUid=<B>` | **404** | P3-8/P3-6 — not-found, so no existence oracle |
| `GET /users/uid/<B admin>` | **404** | P3-8 |
| `X-Branch-Uid: <B branch>` | **403** | P3-1 — refused at the filter, before the principal exists |
| `GET /users` | only A's users | P3-8 |
| `GET /organisations` | only *Tenant A Ltd* | P3-7 |

Then the reverse, as `adminb` (**non-root** — P7-2's probe identity): `/organisations/current`
returns *Tenant B Ltd*, and every reach into A's company, organisation or branch is refused.

One reading note: `adminb` gets **403** rather than 404 on the company list, because the permission
gate fires before the tenancy check — the outer layer catches it first. Both are refusals; they just
come from different layers.

## 5 · Tear it down

```bash
docker rm -f erp-twoorg-db
```

## When to use this rather than the integration test

`TwoOrganisationIsolationIT` is the durable guard — it runs in CI, it is proven to fail on the bug,
and it probes services directly. **This stack proves the layers that test cannot reach**: the servlet
filter (so the `X-Branch-Uid` refusal above is real rather than modelled), the controllers, the
permission gates, and the HTTP status codes an actual client would receive. Use the IT to stop
regressions; use this when you want to *see* it, or when a new endpoint needs checking end to end.
