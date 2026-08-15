# V102 proposal — role codes unique *per tenant* (P4-1c, P4-1b, P4-1d)

**Status: awaiting owner approval.** Nothing here is authored yet. Per the standing rule, a Flyway
migration or an `R__` seed edit needs the DDL presented and approved first.

*Drafted 2026-08-15. Rehearsed against a restored copy of the live client's database.*

---

## 1 · What this changes, and why it is needed

`uq_role_code UNIQUE (code)` — created in `V1__baseline.sql:243` — is **global**. Two tenants can
therefore never both have a `SUPERVISOR`, which defeats per-tenant roles entirely.

The target model has three populations:

| Population | Uniqueness rule | Enforced by |
|---|---|---|
| **Global roles** (`organisation_id IS NULL`) — the 13 shipped: `ORG_ADMIN` + 12 bundles | code unique among themselves | `uq_role_code_global` (partial index) |
| **Tenant roles** | code unique **within a tenant**; two tenants may share a code | `uq_role_org_code` on `(organisation_id, code)` |
| **Across the two** | a tenant role must **not** reuse a global code (**R-1**) | *no index can express this* — see §4 |

V100 anticipated this and deliberately deferred it, in its own header:

> *"The two role indexes (`uq_role_code_global`, `uq_role_org_code`) are NOT here. `uq_role_code` is
> retained to protect `ApprovalEngineImpl:301` and `StepApproverResolver:78-84`, and while it stands
> those two are inert. They land with the `uq_role_code` drop, behind P4-1c."*

---

## 2 · The finding that makes this a three-part change

**Dropping `uq_role_code` breaks `R__seed_permissions.sql`.** Rehearsed on the client's database
copy, the seed fails outright:

```
ERROR:  there is no unique or exclusion constraint matching the ON CONFLICT specification
```

`R__seed_permissions.sql:322` upserts the role bundles with `ON CONFLICT (code) DO UPDATE`, and that
inference clause requires a unique index on `(code)` alone. Once the constraint is gone there is
nothing for it to bind to.

**This fails late, which is what makes it dangerous.** V102 does not change `R__`'s checksum, so the
seed does not re-run on the deploy and nothing appears to be wrong. It breaks on the **next seed
edit** — a boot failure on every deployment including the live client, potentially months later, and
the person making that edit gets an error about a constraint they never touched.

The seed edit is therefore **not optional and cannot be deferred** to a later release.

### A bug this fixes on the way past

Today's clause is `ON CONFLICT (code) DO UPDATE ... SET is_system = true`. When a customer authors a
role whose code a later release happens to ship as a bundle, the seed **silently adopts their role
and marks it a system role** — the defect `TenancyReconciler` already logs and refuses to guess
about. After the change the seed only sees the global partition, inserts its own row, and leaves the
customer's role untouched. Strictly better than the current behaviour.

---

## 3 · The three parts

### Part A — `V102__role_code_per_tenant.sql` (new migration)

```sql
-- ###########################################################################
-- ## V102 — role codes become unique PER TENANT (P4-1c).
-- ##
-- ## Replaces V1's global uq_role_code with two partial indexes. Both are
-- ## created BEFORE the drop so there is never a window in which role codes
-- ## are unconstrained.
-- ##
-- ## Plain transactional CREATE UNIQUE INDEX, NOT CONCURRENTLY: this repo has
-- ## no non-transactional migration wiring (see V78, V81-V85, V100 headers).
-- ## CONCURRENTLY inside Flyway's transaction fails with SQLSTATE 25001.
-- ## `roles` is tiny (15 rows on the live client), so the ACCESS EXCLUSIVE
-- ## lock on the drop is momentary; lock_timeout makes it fail fast rather
-- ## than queue behind a long reader.
-- ###########################################################################

SET LOCAL lock_timeout = '1s';

-- Global roles: the shipped thirteen. NULL organisation_id IS the marker.
CREATE UNIQUE INDEX IF NOT EXISTS uq_role_code_global
    ON roles (code) WHERE organisation_id IS NULL;

-- Tenant roles: unique within a tenant, free to repeat across tenants.
CREATE UNIQUE INDEX IF NOT EXISTS uq_role_org_code
    ON roles (organisation_id, code) WHERE organisation_id IS NOT NULL;

-- Only now is it safe to drop the global rule.
ALTER TABLE roles DROP CONSTRAINT IF EXISTS uq_role_code;
```

`uq_role_code` is a table **constraint** (V1 line 243), not a bare index, so it drops with
`ALTER TABLE ... DROP CONSTRAINT`.

### Part B — `R__seed_permissions.sql:322` (seed edit, mandatory)

```diff
-ON CONFLICT (code) DO UPDATE SET name = EXCLUDED.name, description = EXCLUDED.description, is_system = true;
+ON CONFLICT (code) WHERE organisation_id IS NULL
+DO UPDATE SET name = EXCLUDED.name, description = EXCLUDED.description, is_system = true;
```

The predicate lets Postgres infer `uq_role_code_global`. **Verified working** on the client copy.

Line 274's `ON CONFLICT (code)` is on `permissions`, which keeps its own unique index — unchanged.
`V61`'s is on `currencies` — unrelated. `V1`'s is versioned and already applied, so it never re-runs.
`R__seed_permissions.sql` is the only runtime dependency on the dropped constraint.

### Part C — code that must ship in the same change

| Site | Today | Without the fix, after the drop |
|---|---|---|
| `ApprovalEngineImpl:301` `findByCode` | fine | returns `Optional`; two rows → `IncorrectResultSizeDataAccessException` → **HTTP 500** |
| `ApprovalPolicyServiceImpl:189` `existsByCode` | fine | true if **any** tenant holds the code → policy validation silently accepts a role the tenant does not have |
| `RoleServiceImpl:46` `existsByCode` (**P4-1b**) | fine | tenant B can never use a code tenant A took — the exact thing this migration exists to allow |
| `StepApproverResolver:78-84` | fine | **no change needed for cross-tenant** — it walks the user's *own* grants and compares codes, never resolving one globally. It *is* exposed to the R-1 case below |

---

## 4 · R-1, and why it is not symmetric

The two partial indexes cover **different partitions**, so `(NULL,'CASHIER')` and `(2,'CASHIER')` can
coexist. Demonstrated on the client copy: a tenant row with code `SALESPERSON` inserted happily
alongside the global `SALESPERSON`.

That ambiguity is what makes `findByCode` throw and lets `StepApproverResolver`'s **string** match
cross tiers. No pair of indexes can express the rule; it needs a check that looks at the other
partition.

**The two directions have very different failure modes, so they should be handled differently.**

**Direction 1 — a tenant creates a role colliding with a global code.**
→ Service check in `RoleServiceImpl.create()`, **plus** a trigger scoped to tenant rows only
(`organisation_id IS NOT NULL`). The service check gives a clean, friendly error on the only path a
tenant can create a role through; the trigger is the "a seeder cannot bypass it" guarantee R-1 asks
for, covering direct SQL and any future migration that inserts roles.

**Direction 2 — a future release adds a global bundle whose code a tenant already uses.**
→ **Detect, do not block.** A trigger firing here would abort the seed, which would abort the
migration, which would fail the boot — turning a name clash into an **outage on a live customer's
system**, caused by a release they did not ask for. Instead let `TenancyReconciler` warn at boot,
naming the collisions; it already reports exactly this shape of finding for adopted roles. The
residual ambiguity is milder than the silent `is_system` adoption that happens today.

> **Precedent worth stating plainly: there are zero triggers in this schema across 101 migrations.**
> This introduces a constraint that is invisible to the ORM and to anyone reading the entity class.
> That is justified here because the rule must hold against paths that do not go through the service
> — but it is a first, and it should be a deliberate choice rather than a detail.

---

## 5 · Rehearsal evidence (already run, on the client's database copy)

| Check | Result |
|---|---|
| Apply Part A DDL | applied clean |
| Run the seed **unchanged** afterwards | **ERROR — no unique or exclusion constraint matching the ON CONFLICT specification** |
| Run the upsert with the Part B predicate | OK |
| Insert a duplicate **global** code | rejected — `uq_role_code_global` |
| Insert `SUPERVISOR` in two different tenants | **both accepted** — the objective |
| Insert a tenant role reusing a global code | **accepted** — the R-1 gap, confirmed real |
| Existing data satisfies both new indexes | yes — 15 roles, all distinct codes |

---

## 6 · Risk and rollback

- **Lock:** `ALTER TABLE ... DROP CONSTRAINT` takes a momentary ACCESS EXCLUSIVE lock. `roles` holds
  15 rows; `lock_timeout = '1s'` makes it fail fast rather than queue.
- **Data:** no rows are written or moved. Existing rows already satisfy both new indexes, because the
  constraint being dropped was strictly stronger.
- **Rollback:** re-creating `uq_role_code` is only possible while no duplicate codes exist. Once a
  second tenant has been given a colliding code the drop is effectively one-way — so this should land
  while the estate is still single-tenant, which it is.
- **Blast radius:** every environment runs one organisation, so Parts A and B are behaviourally inert
  on all of them today. Part C's code changes are the only observable difference, and they are
  no-ops until duplicates exist.

## 7 · Verification plan

1. Rehearse the full three-part change on the client database copy (Parts A+B already rehearsed).
2. Extend `TwoOrganisationIsolationIT` to create `SUPERVISOR` in **both** tenants — impossible today,
   and the proof the change worked.
3. Add a probe for R-1: a tenant role reusing a global code must be refused, by the service **and**
   by direct SQL (the trigger).
4. Full suite, then QA, then verify the seed re-runs cleanly by making a trivial seed edit — the
   §6 test corrected on 2026-08-14: *to test the seed, edit it the way the release will, then boot.*

## 8 · What needs approval

1. **Part A** — the V102 DDL above.
2. **Part B** — the `R__seed_permissions.sql` `ON CONFLICT` predicate. **Not optional**; without it we
   ship a latent boot failure.
3. **R-1 enforcement** — the asymmetric design in §4, including the trigger precedent.
