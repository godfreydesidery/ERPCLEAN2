# Issues Register — Simulation Run 2026-06-28 (Tembo Group)

> **Status:** Technical-team triage output for the **2026-06-28** simulation run.
> Source UPRs: [`run-2026-06-28/all-problems.json`](all-problems.json) (29 raw
> problem records across 16 personas) + [`onboard-summary.json`](onboard-summary.json)
> (the role/permission grant snapshot). Process: [TRIAGE-PROCESS.md](../TRIAGE-PROCESS.md).
>
> *Sikia kero, tafsiri kwa uhandisi* — "Hear the complaint, translate it into engineering."

---

> **UPR numbering note.** This register was triaged against the 29 raw captured records and
> refers to them by fine-grained working numbers (UPR-001..012). The **six business-voice
> reports staff actually filed** are in [UPR-REGISTER.md](UPR-REGISTER.md); they map onto these
> Issues as: filed **UPR-004**(Grace receipts/payments)→**ISSUE-001**; **UPR-005**(Amina
> supplier bill)→**ISSUE-002**; **UPR-002**(Editha work-orders/BOMs/products)→**ISSUE-003/005/006**;
> **UPR-001**(Sabina POS) & **UPR-003**(Frank stock)→**ISSUE-004**(+003); **UPR-006**(Sabina price
> list)→**ISSUE-007**; all → systemic **ISSUE-008**.

## Resolution — fixed & verified (2026-06-28)

The technical team fixed the blockers the same day — **no migration** (the DB is frozen and all four
codes were already seeded). The fix lives in the **gate layer**: the low-sensitivity, company-scoped
reference *pickers* (branch / product / WHT-type lists) now allow a provisioned member of their **own**
company to read them (`@perm.scopedOrMember` / `hasOrMember`); the PO list was broadened to
`PURCHASE.ORDER.VIEW or AP.BILL.ENTER` for the supplier-bill matcher only. **Tenant isolation is
unchanged** — every list still enforces its company-scope predicate, so a member reads only their own
company's data (read-widening, never a write, never cross-tenant). The affected screens also **degrade
gracefully** now instead of blanking. Recorded as security finding **F21**; guarded by
`ReferenceDataReadClosureIT` (11/11, incl. a cross-tenant 403 assertion). Gates green:
EndpointAuthorizationTest, PermissionCodesSeededTest, ModuleBoundaryTest, web build + 894 web assertions.

**Verified by re-running the blocked personas through the UI** (evidence:
[`run-2026-06-28/rerun-after-fix.json`](run-2026-06-28/rerun-after-fix.json)) — **0 blockers remain
(was 28)**:

| Issue | Was | Now | Status |
|---|---|---|---|
| ISSUE-001 (`WHT.VIEW` → receipts/payments) | Grace, John **BLOCKED** | screens open | **Fixed** |
| ISSUE-002 (`PURCHASE.ORDER.VIEW` → supplier bill) | Grace, Amina **BLOCKED** | opens | **Fixed** |
| ISSUE-003/005 (`PRODUCT`/`BRANCH.VIEW` → work-orders/BOMs/stock) | Editha, Editrude, Frank, Saidi **BLOCKED** | open | **Fixed** |
| ISSUE-004 (`BRANCH.VIEW` → POS/stock) | Sabina, Frank, Saidi **BLOCKED** | open | **Fixed** |
| ISSUE-007 (price-list bare 409) | opaque conflict | friendly message | **Fixed** |
| ISSUE-008 (no closure guard) | — | `ReferenceDataReadClosureIT` | **Done** |
| ISSUE-006 (PRODUCTION_OFFICER can't *create* product master data) | n/a (was masked) | view OK; create needs `PRODUCT.MANAGE` | **Deferred → system-analyst** (role-spec, not a defect) |

The only residual items in the after re-run are non-blocking and correct-by-design: production officers
can now *view* products and run their work-order/BOM screens, but creating product **master data** needs
`PRODUCT.MANAGE` — a legitimate role-design question (does a production officer own product master data,
or only procurement?), routed to **system-analyst**. The "RETAIL already exists" item is expected
duplicate data, now shown as a friendly message.

---

## How we read this run

Sixteen Tembo Group personas logged into the live web UI **as themselves** — each on a
custom ERP role provisioned during onboarding (`FINANCE_DIRECTOR`, `PRODUCTION_OFFICER`,
`STORES_SUPERVISOR`, `CASHIER`, `STOREKEEPER`, `SALES_OFFICER`, …), **not** as root and
**not** as `ORG_ADMIN`. That is the whole point: the run exercises the permission surface
that root and ORG_ADMIN mask. The onboard snapshot shows each role holds only a slice of
the 230-permission catalogue (e.g. `FINANCE_DIRECTOR 61/230`, `CASHIER 12/230`,
`PRODUCTION_OFFICER 24/230`, `STORES_SUPERVISOR 33/230`).

The personas filed **business-voice problems** — "I'm told I don't have permission to open
*Record receipt*, but it's part of my job." They cannot see status codes or endpoints. The
technical team's job was to attach the captured runtime evidence (each record carries the
exact failing API call + HTTP status) and translate each into a reproducible **Issue** with
a **Fix Plan**, grounded in the controller gates and the seed.

**What we found, grounded in code.** Of 29 raw records, 28 are `BLOCKED` and 1 is `SLOW`.
The 28 blockers are **not** 28 bugs — they collapse into **one dominant systemic defect**
plus a small tail. When you read the evidence (not the screen label) the pattern is stark:
the persona was denied not by the screen's *headline* action but by a **supporting
reference-data read** the screen fires on load — `GET /branches`, `GET /products`,
`GET /wht/types`, `GET /purchase-orders` — each returning **403** because the persona's
custom role was granted the operational permission for that screen but **not** the
supporting `*.VIEW` code the same screen needs. Every one of those four codes
(`BRANCH.VIEW`, `PRODUCT.VIEW`, `WHT.VIEW`, `PURCHASE.ORDER.VIEW`) **is correctly seeded**
in `R__seed_permissions.sql` — so this is **not** phantom permissions. It is a
**role-grant composition gap**: the role catalogue grants a workflow's primary permission
without its read-dependency closure. This is the role-design-level sibling of the
route-guard ↔ endpoint parity lesson.

We deduplicated the 29 raw records (several personas hit the same 403 repeatedly while
retrying) into **8 Issues**, of which **5 share the single reference-data root cause**
(ISSUE-001..005, grouped below), **2 are distinct** (ISSUE-006 a role-coverage gap on a
core read; ISSUE-007 a duplicate-uid 409), and **ISSUE-008** is the systemic guard the run
argues for.

---

## Summary table

| Issue-ID | UPR(s) | Reporter(s) / role | Screen | Verdict | Severity | Owner | Root cause (one-liner) |
|---|---|---|---|---|---|---|---|
| **ISSUE-001** | UPR-001, UPR-008 | Grace Mhina (FINANCE_DIRECTOR), John Komba (CASHIER) | Record receipt / Record payment | **Real defect** | Blocker | security-engineer | Cash/AR/AP screens load `GET /wht/types` (`WHT.VIEW`) which neither role is granted → 403 blocks the page. |
| **ISSUE-002** | UPR-002, UPR-007 | Grace Mhina (FINANCE_DIRECTOR), Amina Mwanga (ACCOUNTANT) | Enter supplier bill | **Real defect** | Blocker | security-engineer | Supplier-bill screen loads `GET /purchase-orders` (`PURCHASE.ORDER.VIEW`) which neither role is granted → 403. |
| **ISSUE-003** | UPR-003, UPR-005, UPR-009, UPR-011 | Editha Mhagama & Editrude Mwakalukwa (PRODUCTION_OFFICER), Frank Materu (STORES_SUPERVISOR) | Work orders / BOMs / Stock transfer / Products | **Real defect** | Blocker | security-engineer | Manufacturing & stock screens load `GET /products` (`PRODUCT.VIEW`), not granted to these roles → 403. |
| **ISSUE-004** | UPR-004, UPR-006, UPR-010 | Frank Materu (STORES_SUPERVISOR), Saidi Karume (STOREKEEPER), Sabina Aloyce (SALES_OFFICER) | Stock on-hand / Stock count / Stock transfer / POS sell | **Real defect** | Blocker | security-engineer | Branch-picker on stock/POS screens loads `GET /branches` (`BRANCH.VIEW`), not granted to these roles → 403. |
| **ISSUE-005** | UPR-003, UPR-004, UPR-011 | Editha/Editrude (PRODUCTION_OFFICER), Frank (STORES_SUPERVISOR) | Work orders / Stock transfer | **Real defect** | Blocker | security-engineer | Same screens also fire `GET /branches` (`BRANCH.VIEW`) → 403; double-dependency with ISSUE-003/004 (merge candidate). |
| **ISSUE-006** | UPR-003, UPR-005 | Editha & Editrude (PRODUCTION_OFFICER) | Products (create manufactured product) | **Real defect** | High | security-engineer | `PRODUCTION_OFFICER` lacks `PRODUCT.VIEW` outright — they cannot list/select products at all, a core part of their job. |
| **ISSUE-007** | UPR-012 | Sabina Aloyce (SALES_OFFICER) | Price lists | **Real defect (latent)** | Medium | backend-engineer | Creating price list "RETAIL" → 409 "same unique identifier already exists"; uid/natural-key collision surfaced as an opaque conflict. |
| **ISSUE-008** | (systemic) | — | — | **Process finding** | High | qa-engineer / security-engineer | No test asserts a role's grant set is *closed* over the reference-data reads its screens fire → role-grant gaps ship invisibly. |

> **Verdict legend:** *Real defect* = reproducible as the reporting role, fix warranted.
> *Process finding* = no single endpoint to patch; a guard/test the run argues for.
> All five reference-data Issues (001–005) share one root cause — see the grouped block
> below — and could legitimately be tracked as **one Issue with five symptom screens**;
> we keep them split so each blocked workflow can be re-tested and closed independently
> by its reporter (per the triage rule that a UPR closes only when *its* outcome works).

---

## GROUP A — Shared root cause: supporting reference-data reads (branches / products / WHT / POs) **403 for operational roles**

> ISSUE-001 through ISSUE-005 are **one defect with five faces**. Each persona was stopped
> not by the screen's headline permission but by a **secondary read the screen fires on
> open**, for which their custom role holds no grant. The denied endpoints and their gates:
>
> | Reference read (on screen load) | Endpoint | Gate (controller, verified) | Code seeded? |
> |---|---|---|---|
> | Branch picker | `GET /api/v1/branches?companyUid=…` | `@perm.scoped(#companyUid,'company','BRANCH.VIEW')` | **Yes** (`R__seed_permissions.sql:47`) |
> | Product list/picker | `GET /api/v1/products?…` | `@perm.has('PRODUCT.VIEW')` | **Yes** (`:142`) |
> | WHT types (tax dropdown) | `GET /api/v1/wht/types?companyId=…` | `@perm.has('WHT.VIEW')` | **Yes** (`:238`) |
> | PO lookup (bill matching) | `GET /api/v1/purchase-orders?…` | `@perm.has('PURCHASE.ORDER.VIEW')` | **Yes** (`:158`) |
>
> **None of these is a phantom code.** All four resolve in the seed. The seed auto-grants
> the *full* catalogue only to `ORG_ADMIN` (CROSS JOIN, `:250`); every operational role is a
> hand-curated subset. The subset was built around each role's *primary* verbs and **omitted
> the read-dependency closure** of the screens those verbs live on. Root and ORG_ADMIN never
> hit it (they hold every code) — exactly why the run had to be driven as the real personas.

---

### ISSUE-001 — Cash/AR/AP screens 403 on the WHT-types read for FINANCE_DIRECTOR and CASHIER

```
ISSUE-001
Links UPR:        UPR-001 (Grace Mhina), UPR-008 (John Komba)
Title:            Record-receipt / record-payment screens denied by WHT.VIEW dependency
                  for finance & cashier roles

Reproduction (UI):
  Login as:       gmhina (FINANCE_DIRECTOR)  /  jkomba (CASHIER)   — NOT root
  Branch:         home branch (company 3)
  Data state:     onboarded roles per onboard-summary.json (FINANCE_DIRECTOR 61/230,
                  CASHIER 12/230)
  Steps:
    1. Finance → AR → Record receipt   (and AP → Record payment).
    2. Screen opens, fires its supporting loads.

Expected:         The receipt/payment capture form opens; finance & cashier can record
                  cash movements — squarely their job.
Actual:           "You do not have permission to perform this action." Page does not load.

Evidence (from all-problems.json):
  Console:        Failed to load resource: 403 (Forbidden)
  API:            GET /api/v1/wht/types?companyId=3 → 403
                  body: {"errors":["You do not have permission to perform this action."]}

Suspected layer: permission   (gate: @perm.has('WHT.VIEW'), WhtTypeController:56)
Module:          tax (dependency) blocking ar / ap / cash-bank
Severity:        Blocker  (core finance job; no workaround as the role)
Assigned agent:  security-engineer
```

```
FIXPLAN-001
Links Issue:        ISSUE-001

Root-cause hypothesis:
  The receipt/payment forms eagerly fetch the WHT type list to populate a
  withholding-tax dropdown. The gate WHT.VIEW is correct and seeded, but FINANCE_DIRECTOR
  and CASHIER were never granted it. Confirm: grant WHT.VIEW to both roles → the 403
  disappears and the form loads.

The change (files / area):
  - data/provisioning: add WHT.VIEW to the FINANCE_DIRECTOR and CASHIER role grant sets.
    These are simulation-provisioned roles (not seed roles), so the fix is in the role
    provisioning that builds them — extend each role's grant list to include its screens'
    read-dependency closure (here, WHT.VIEW for the cash/AR/AP capture screens).
  - web (optional hardening): make the WHT dropdown load lazy / tolerate a 403 by
    degrading to "no withholding" rather than failing the whole screen, so a missing
    optional read never blocks a core capture form.
  - migration: none (codes already seeded; no schema change).

Test to add:
  - security: RolePermissionClosureTest — for each operational role, assert its grant set
    contains every *.VIEW code its assigned screens fire on load (data-driven from a
    screen→reads map). Fails today for FINANCE_DIRECTOR/CASHIER on WHT.VIEW.

Risk:
  Contained — additive grant only; widens read, never write. No posting/GL effect.

Rollout:
  Branch fix off develop → PR → develop → re-provision roles on QA. No DB wipe.

Who verifies:
  qa-engineer replays as gmhina + jkomba; Grace & John confirm "I can open Record
  receipt / Record payment now." Closes UPR-001, UPR-008.
```

**Verdict:** Real defect (permission-grant gap). **Root cause:** the cash/AR/AP capture
screens depend on `WHT.VIEW`, which `FINANCE_DIRECTOR` and `CASHIER` were not granted —
a seeded code missing from those roles' grant sets, not a phantom code.

---

### ISSUE-002 — Enter-supplier-bill 403 on the purchase-orders read for FINANCE_DIRECTOR and ACCOUNTANT

```
ISSUE-002
Links UPR:        UPR-002 (Grace Mhina), UPR-007 (Amina Mwanga)
Title:            Supplier-bill entry denied by PURCHASE.ORDER.VIEW dependency for
                  finance & accountant roles

Reproduction (UI):
  Login as:       gmhina (FINANCE_DIRECTOR)  /  amwanga (ACCOUNTANT)   — NOT root
  Branch:         home branch (company 3)
  Steps:
    1. Purchases/AP → Enter supplier bill.
    2. Screen fires the PO lookup to match the bill to an ordered PO.

Expected:         The supplier-bill form opens; the user matches the bill against an
                  open purchase order.
Actual:           403; page does not load.

Evidence:
  API:            GET /api/v1/purchase-orders?companyId=3&page=0&size=200&status=ORDERED
                  → 403  ("You do not have permission to perform this action.")

Suspected layer: permission   (gate: @perm.has('PURCHASE.ORDER.VIEW'), PurchaseOrderController:62)
Module:          purchases (dependency) blocking ap
Severity:        Blocker
Assigned agent:  security-engineer
```

```
FIXPLAN-002
Links Issue:        ISSUE-002

Root-cause hypothesis:
  The supplier-bill screen lists ORDERED purchase orders for three-way matching via
  PURCHASE.ORDER.VIEW. FINANCE_DIRECTOR and ACCOUNTANT can enter bills but were not
  granted PURCHASE.ORDER.VIEW (a read they legitimately need to match against).

The change (files / area):
  - data/provisioning: add PURCHASE.ORDER.VIEW to FINANCE_DIRECTOR and ACCOUNTANT grants.
  - web (optional): if the PO-match list is optional, degrade gracefully on 403 rather
    than blocking bill entry entirely.
  - migration: none.

Test to add:
  - security: RolePermissionClosureTest (same harness as FIXPLAN-001) covers AP screens →
    PURCHASE.ORDER.VIEW for FINANCE_DIRECTOR/ACCOUNTANT.

Risk:    Contained — read-only grant widening.
Rollout: branch → PR → develop → re-provision roles on QA.
Who verifies: qa-engineer replays as gmhina + amwanga; closes UPR-002, UPR-007.
```

**Verdict:** Real defect (permission-grant gap). **Root cause:** supplier-bill entry needs
`PURCHASE.ORDER.VIEW` for PO matching; the finance/accountant roles lack that seeded read.

---

### ISSUE-003 — Manufacturing & stock-transfer screens 403 on the products read for PRODUCTION_OFFICER and STORES_SUPERVISOR

```
ISSUE-003
Links UPR:        UPR-003 (Editha Mhagama), UPR-005 (Editrude Mwakalukwa),
                  UPR-009 (Frank Materu, stock-transfer), UPR-011 (Frank Materu, products)
Title:            Work orders / BOMs / stock transfer denied by PRODUCT.VIEW dependency

Reproduction (UI):
  Login as:       emhagama / emwakalukwa (PRODUCTION_OFFICER), fmateru (STORES_SUPERVISOR)
  Steps:
    1. Manufacturing → Work orders / Bills of materials  (or Stock → Stock transfer).
    2. Screen loads its product picker.

Expected:         The work-order / BOM / transfer screen opens with a product list.
Actual:           403; page does not load.

Evidence:
  API:            GET /api/v1/products?companyId=3&page=0&size=500  → 403
                  (and size=200 on the transfer screen)

Suspected layer: permission   (gate: @perm.has('PRODUCT.VIEW'), ProductController:61/108/…)
Module:          products (dependency) blocking manufacturing / stock
Severity:        Blocker
Assigned agent:  security-engineer
```

```
FIXPLAN-003
Links Issue:        ISSUE-003

Root-cause hypothesis:
  Manufacturing screens (work orders, BOMs) and the stock-transfer screen need a product
  list (PRODUCT.VIEW). PRODUCTION_OFFICER (24/230) and STORES_SUPERVISOR (33/230) were not
  granted PRODUCT.VIEW.

The change (files / area):
  - data/provisioning: add PRODUCT.VIEW to PRODUCTION_OFFICER and STORES_SUPERVISOR grants.
  - migration: none.

Test to add:
  - security: RolePermissionClosureTest covers manufacturing + stock screens → PRODUCT.VIEW.

Risk:    Contained — read-only grant widening.
Rollout: branch → PR → develop → re-provision roles on QA.
Who verifies: qa-engineer replays as emhagama/emwakalukwa/fmateru; closes UPR-003,005,009,011.
```

**Verdict:** Real defect (permission-grant gap). **Root cause:** these screens depend on the
seeded `PRODUCT.VIEW`, absent from the production/stores role grants.

---

### ISSUE-004 — Stock & POS screens 403 on the branches read for STORES_SUPERVISOR, STOREKEEPER and SALES_OFFICER

```
ISSUE-004
Links UPR:        UPR-004 (Frank Materu), UPR-006 (Saidi Karume), UPR-010 (Sabina Aloyce, POS)
Title:            Stock on-hand / stock count / stock transfer / POS sell denied by
                  BRANCH.VIEW dependency (branch picker)

Reproduction (UI):
  Login as:       fmateru (STORES_SUPERVISOR), skarume (STOREKEEPER), saloyce (SALES_OFFICER)
  Steps:
    1. Stock → Stock on-hand / Stock count / Stock transfer   (or Sales → POS sell).
    2. Screen loads its branch picker.

Expected:         The screen opens with a branch selector so the user can scope to a
                  warehouse / till.
Actual:           403; page does not load.

Evidence:
  API:            GET /api/v1/branches?companyUid=01KW17PVFC09X1YZEG4E0VKTDN → 403

Suspected layer: permission   (gate: @perm.scoped(#companyUid,'company','BRANCH.VIEW'),
                  BranchController:38)
Module:          iam (dependency) blocking stock / pos / sales
Severity:        Blocker
Assigned agent:  security-engineer
```

```
FIXPLAN-004
Links Issue:        ISSUE-004

Root-cause hypothesis:
  Every stock/POS screen renders a branch picker fed by GET /branches (BRANCH.VIEW,
  company-scoped). STORES_SUPERVISOR, STOREKEEPER and SALES_OFFICER were not granted
  BRANCH.VIEW, so the picker read 403s and the screen is unusable. (Note: BRANCH.VIEW is
  in the `iam` module — easy to overlook when composing a non-admin operational role.)

The change (files / area):
  - data/provisioning: add BRANCH.VIEW to STORES_SUPERVISOR, STOREKEEPER, SALES_OFFICER
    (and any other operational role whose screens host a branch picker).
  - web (optional): degrade the branch picker to the user's default branch when the list
    read is denied, so a single missing read never blanks a core screen.
  - migration: none.

Test to add:
  - security: RolePermissionClosureTest covers stock/POS screens → BRANCH.VIEW.

Risk:    Contained — read-only grant widening; branch *list* stays company-scoped so no
         cross-tenant exposure (the gate already scopes to the caller's company).
Rollout: branch → PR → develop → re-provision roles on QA.
Who verifies: qa-engineer replays as fmateru/skarume/saloyce; closes UPR-004,006,010.
```

**Verdict:** Real defect (permission-grant gap). **Root cause:** the branch picker on
stock/POS screens depends on the seeded `iam`-module `BRANCH.VIEW`, missing from these
operational roles' grant sets.

---

### ISSUE-005 — Work-order & stock-transfer screens fire BOTH product and branch reads (compound dependency)

```
ISSUE-005
Links UPR:        UPR-003 (Editha), UPR-004 (Frank, stock transfer), UPR-011 (Editrude)
Title:            Manufacturing/transfer screens carry a two-read dependency (PRODUCT.VIEW
                  AND BRANCH.VIEW) — fixing one still 403s on the other

Reproduction (UI):
  Login as:       emhagama / emwakalukwa (PRODUCTION_OFFICER), fmateru (STORES_SUPERVISOR)
  Steps:
    1. Work orders  /  Stock transfer.
    2. Observe TWO 403s in one screen load.

Expected:         Screen opens once the role holds the closure of its reads.
Actual:           Two 403s logged on the same open:
                  GET /branches → 403  AND  GET /products → 403.

Evidence:
  API:            GET /api/v1/branches?companyUid=… → 403
                  GET /api/v1/products?companyId=3&page=0&size=500 (or 200) → 403

Suspected layer: permission
Module:          manufacturing / stock (compound dependency on iam + products)
Severity:        Blocker
Assigned agent:  security-engineer
```

```
FIXPLAN-005
Links Issue:        ISSUE-005   (merge candidate with ISSUE-003 + ISSUE-004)

Root-cause hypothesis:
  Same root cause as 003/004 — captured separately because these screens fire *both*
  dependent reads, so granting only one code still leaves them blocked. The lesson: the
  fix must grant the *full* read closure per screen, not one code at a time.

The change (files / area):
  - data/provisioning: ensure PRODUCTION_OFFICER and STORES_SUPERVISOR get BOTH
    PRODUCT.VIEW and BRANCH.VIEW (covered jointly by FIXPLAN-003 + FIXPLAN-004).
  - migration: none.

Test to add:
  - security: RolePermissionClosureTest must assert the *complete* read set per screen
    (a screen with two reads needs both), not pass on a single match.

Risk:    Contained.
Rollout: folded into the 003/004 PR (one logical change: "complete operational-role read closure").
Who verifies: qa-engineer confirms NO 403 remains on a single open; closes the residual UPRs.
```

**Verdict:** Real defect (same root cause; compound). **Root cause:** these screens have a
two-code read dependency; a partial grant leaves a second 403 — the fix must close the
*whole* read set, which is why the regression test asserts the full closure, not one code.

---

## GROUP B — Distinct issues

### ISSUE-006 — PRODUCTION_OFFICER cannot view products at all (core-read coverage gap)

```
ISSUE-006
Links UPR:        UPR-003 (Editha Mhagama), UPR-005 (Editrude Mwakalukwa)
Title:            PRODUCTION_OFFICER has no PRODUCT.VIEW — "create manufactured product"
                  is impossible, not just a screen dependency

Reproduction (UI):
  Login as:       emhagama / emwakalukwa (PRODUCTION_OFFICER)
  Steps:
    1. Products → (attempt to) create a manufactured product.

Expected:         A production officer can list/select products and define a manufactured
                  product — this is the core of the role.
Actual:           403 on GET /products; the role cannot see the product master at all.
                  (Filed repeatedly — five retry records per officer in the raw data.)

Suspected layer: permission
Module:          products / manufacturing
Severity:        High  (core role capability absent; distinct from the screen-dependency
                  framing because it makes the role's *primary* job undoable)
Assigned agent:  security-engineer  (role design — pull system-analyst on the intended
                  PRODUCTION_OFFICER capability set)
```

```
FIXPLAN-006
Links Issue:        ISSUE-006

Root-cause hypothesis:
  PRODUCTION_OFFICER (24/230) was scoped to work-order verbs but not given PRODUCT.VIEW,
  yet defining/manufacturing a product inherently requires reading the product master.
  This is a role-design omission of a *primary* capability, not just a screen's side-load
  (so it is tracked apart from ISSUE-003 even though the same grant fixes the 403).

The change (files / area):
  - system-analyst: confirm the intended PRODUCTION_OFFICER capability set (should it also
    hold PRODUCT.MANAGE to create manufactured products, or only PRODUCT.VIEW + a
    manufacturing-side create?). Don't guess the role's spec.
  - data/provisioning: grant the confirmed read/create codes.
  - migration: none unless a new code is required (then owner approval per the migration rule).

Test to add:
  - security: a capability assertion for PRODUCTION_OFFICER (can list products; can do the
    defined manufacturing create path) as a non-root role.

Risk:    Low-moderate — touches what a production role may do; needs the spec confirmed first.
Rollout: branch → PR → develop after system-analyst sign-off.
Who verifies: qa-engineer + Editha/Editrude confirm they can build a manufactured product.
```

**Verdict:** Real defect (role-design coverage gap). **Root cause:** `PRODUCTION_OFFICER`
was provisioned without `PRODUCT.VIEW`, making its core job (define a manufactured product)
impossible — needs the role's intended capability set confirmed before the grant.

---

### ISSUE-007 — Price-list create returns an opaque 409 "same unique identifier already exists"

```
ISSUE-007
Links UPR:        UPR-012 (Sabina Aloyce)
Title:            Creating price list "RETAIL" fails with a duplicate-uid 409 the user
                  can't act on

Reproduction (UI):
  Login as:       saloyce (SALES_OFFICER)
  Steps:
    1. Sales → Price lists → New → name "RETAIL" → Save.

Expected:         The price list saves, or a clear inline message ("a price list named
                  RETAIL already exists").
Actual:           409 Conflict; "A record with the same unique identifier already exists."
                  The user typed a fresh name and cannot tell what collided.

Evidence:
  API:            POST /api/v1/price-lists → 409
                  body: {"errors":["A record with the same unique identifier already exists."]}

Suspected layer: api / data   (secondary: web — error copy)
Module:          sales (pricing)
Severity:        Medium  (SLOW; the persona coped — one blocked save, not the whole job)
Assigned agent:  backend-engineer
```

```
FIXPLAN-007
Links Issue:        ISSUE-007

Root-cause hypothesis:
  Either (a) "RETAIL" already exists in this company (a real duplicate the message hides
  behind generic "unique identifier" wording), or (b) a uid/natural-key generation collides
  on create. Confirm by reading the price-list table for company 3: if "RETAIL" exists, this
  is an error-message-hygiene fix; if not, a key-generation bug.

The change (files / area):
  - backend: in the price-list create path, distinguish "name already used in this company"
    (return a friendly inline 409 naming the field) from any internal uid collision (which
    should never surface to the user — regenerate/retry server-side).
  - web: map this 409 to a calm inline field error, not the generic conflict banner
    (error-message hygiene: no "unique identifier" internals to the user).
  - migration: none.

Test to add:
  - backend: PriceListCreateDuplicateNameIT — creating a second "RETAIL" in the same company
    returns a friendly, field-scoped 409; creating in a different company succeeds.

Risk:    Contained to price-list create.
Rollout: branch → PR → develop → QA.
Who verifies: qa-engineer + Sabina confirm a clear message and a successful save of a new name.
```

**Verdict:** Real defect (latent — message hygiene at minimum, possibly a key collision).
**Root cause:** the create path surfaces an internal "unique identifier" conflict verbatim
instead of a clear, field-scoped duplicate-name message (and may be colliding on uid).

---

### ISSUE-008 — No guard asserts a role's grant set is closed over its screens' reference-data reads (process finding)

```
ISSUE-008
Links UPR:        (systemic — abstracted from UPR-001..011)
Title:            Role provisioning can ship a role that opens a menu it can't actually
                  use, because no test checks read-dependency closure

Verdict:          Process finding (no single endpoint to patch)
Severity:         High  (this run: 26 of 28 blockers trace to it)
Assigned agent:   qa-engineer (build the guard) + security-engineer (own the role closures)
```

```
FIXPLAN-008
Links Issue:        ISSUE-008

Root-cause hypothesis:
  Existing guards (EndpointAuthorizationTest, PermissionCodesSeededTest) check that a gate
  EXISTS and that its CODE is SEEDED — neither checks that the roles which can *reach* a
  screen also hold every read that screen *fires*. So a role can pass the menu/route guard
  and 403 on a side-load. This run is the proof.

The change (files / area):
  - test: RolePermissionClosureTest — a data-driven map of screen → {reference reads it
    fires} (branches, products, wht/types, purchase-orders, customers, suppliers, …); for
    each role that can navigate to a screen, assert its grant set ⊇ that screen's read set.
    Drive the map from the Angular route table + the load() calls so it can't drift silently.
  - process: when composing/altering an operational role, compute the read-dependency closure
    of its assigned screens; grant the whole closure (these are read-only *.VIEW codes —
    widening a read, never a write).
  - migration: none.

Risk:    None to runtime — a test + a provisioning discipline.
Rollout: ships with the ISSUE-001..006 PRs as the regression net that pins them all.
Who verifies: qa-engineer — the test is red on the pre-fix role grants, green after the
              closures are granted.
```

**Verdict:** Process finding. **Root cause:** the test suite verifies gate-exists and
code-seeded but never grant-set-closure, so role-grant gaps reach users undetected.

---

## Patterns & systemic finding

**One root cause produced 26 of the 28 blockers.** Strip away the screen labels and the
business voice, and almost every "I'm told I don't have permission to open X" is the same
mechanism: the persona's custom operational role was granted the **headline** permission for
a workflow but **not the supporting reference-data reads** (`BRANCH.VIEW`, `PRODUCT.VIEW`,
`WHT.VIEW`, `PURCHASE.ORDER.VIEW`) that the *same screen* fires when it loads. The screen
renders, then a side-load 403s, and the user reads it as "the page won't open."

**This is the third member of a known family — and the run pins exactly which member.**

- **Phantom permission codes** ([memory](../../..)): a gate references a code that was
  **never seeded**. *Ruled out here* — all four reads resolve in `R__seed_permissions.sql`
  (lines 47 / 142 / 158 / 238). `PermissionCodesSeededTest` correctly stays green.
- **Route-guard ↔ endpoint parity:** the Angular `requirePermission('A')` guard diverges
  from the endpoint's `'B'`, so a user holding `A` passes the guard and 403s on the API.
  *Adjacent but not the literal failure here* — the personas weren't blocked by a divergent
  guard on the headline action; they passed the route and were stopped by a **secondary**
  read with its own (correct, seeded) gate.
- **New, named here: role-grant read-closure gap.** The permission *codes* are right, the
  *gates* are right, the *route guards* may even be right — but the **role's grant set is not
  closed over the reads its screens fire.** It is the same blind spot one level up: instead of
  guard-vs-endpoint parity, it is **role-grant vs screen-read-dependency** parity.

**Why every existing safety net missed it — and the run didn't.** The three things that
hide this class are identical to the older lessons: `app_user.is_root` short-circuits all
checks, and the seed CROSS JOINs the *entire* catalogue onto `ORG_ADMIN` — so root and
ORG_ADMIN hold every code and never 403. CI's `EndpointAuthorizationTest` only asserts a
gate **exists**; `PermissionCodesSeededTest` only asserts the code is **seeded**. Neither
asserts that the **roles able to reach a screen hold every read it fires.** The simulation
caught all of it for one reason consistent with the standing rule across these memories:
**it drove the product as the real, minimally-granted personas — never as root.** Run the
same screens as `rootadmin` and the register is empty.

**The parity chain this run completes.** For a permission-gated screen to actually work for
a non-root role, four links must all hold:

1. the menu/route is reachable (nav + route guard),
2. the route guard's code **equals** the headline endpoint's code (route-guard parity),
3. every code on the chain is **seeded** (no phantom codes), **and**
4. the role's grant set is **closed over every reference-data read the screen fires**
   (this run's finding).

Links 1–3 were the subjects of prior triages; **this run is the evidence that link 4 needs
its own guard.** `RolePermissionClosureTest` (ISSUE-008) is that guard: it makes a role
that can *open* a screen provably able to *use* it, and it would have turned all of
ISSUE-001..006 red before they ever reached a persona. The fixes themselves are deliberately
low-risk — additive grants of read-only `*.VIEW` codes (widening reads, never writes; branch
and product lists stay company-scoped, so no tenant exposure) plus, where cheap, a UI that
degrades a denied optional side-load instead of blanking the whole screen.

**Standing-rule reinforcement for the next run and the next role:** when composing or
editing any operational role, compute the **read-dependency closure** of its assigned
screens and grant the whole closure; and **always validate a role as that role, never as
root or ORG_ADMIN** — those two see green over precisely the bugs that block everyone else.
