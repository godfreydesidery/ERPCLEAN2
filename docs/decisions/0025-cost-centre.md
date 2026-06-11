# 0025 — Cost-centre / Accounting-dimension framework: a generic per-company dimension model (`dimensions` types + `dimension_values` members, seeded Cost Centre + Department) tagged onto `journal_lines` via a bounded set of additive nullable slot-columns (alongside the existing `branch_id` analysis tag) and inherited from source-document dimension defaults at post time, with dimension-sliced trial-balance/ledger reads — purely additive on the shipped `GLPostingService` (changes no account, no balance, no `gl_config`), in a new `com.erp.modules.costing` module, migrations `V27`–`V29`

- **Status:** Proposed
- **Date:** 2026-06-11
- **Deciders:** solutions-architect (requirements architect-authored, [docs/requirements/cost-centre.md](../requirements/cost-centre.md) — FR-CC-01..20, BR-CC-01..10, NFR-CC-01..08, §6 flows, §8 OQ log; the two LOAD-BEARING OQs (OQ-CC-01 fixed-slots, OQ-CC-02 poster-wiring set) carry architect recommendations adopted below and flagged for owner confirmation — neither blocks the build).
- **Context source:** [docs/requirements/cost-centre.md](../requirements/cost-centre.md) + [docs/PATH-TO-FULL-ERP.md](../PATH-TO-FULL-ERP.md) §3.11 (the build-first enabler) + §4 critical-dependency #4. Verified against the **shipped** code:
  - **GL** ([ADR-0013](0013-general-ledger-data-model.md) / [V10__general_ledger.sql](../../backend/src/main/resources/db/migration/V10__general_ledger.sql)): `journal_lines` (`id`, `uid` VARCHAR(26), `company_id` BIGINT NOT NULL, **`branch_id` BIGINT nullable — the pre-existing analysis tag, ADR-0013 D-7**, `entry_id`, `line_no`, `account_id`, `debit_amount`/`credit_amount` NUMERIC(19,4), `currency`, `line_memo`, `version`, `created_at`/`created_by` — **append-only, no `updated_*`**); `JournalEntryDraft(companyId, branchId, postingDate, description, JournalSourceType sourceType, sourceRef, reversalOfId, postedBy, List<LineDraft>)` + **`LineDraft(accountId, debitAmount, creditAmount, currency, lineMemo)`** (verified — the record this ADR extends with optional dimension value ids); `GLPostingService.post(JournalEntryDraft)→JournalEntryDto` (validates ≥2 lines, balance, OPEN period, **active accounts + same company + base currency** — the validation seam dimension validation joins) + `postReversal(...)`; `GLPostingSafeInvoker.postSaleInNewTx(...)`/`postInNewTx(draft)`/`postReversalInNewTx(...)` (REQUIRES_NEW, null-on-anomaly); `GLConfigResolver.resolve(companyId, GlConfigKey)→ChartOfAccount` (the active-account/same-company resolution precedent the dimension-value resolver mirrors); `ChartOfAccount` (per-company master, `is_active` posting gate, `MasterStatus status`, no-delete-if-posted BR-GL-07 — the dimension-value master mirrors it); `JournalLineRepository.trialBalanceSums(companyId)` / `trialBalanceSumsByPeriod(companyId, periodId)` / `accountBalance(companyId, accountId)` / `periodMovementByAccount(companyId, from, to)` (the read aggregates this ADR adds dimension-filtered variants of); the **shipped `SalesPostingHandler`** (`SALE.FINALISED` → builds the `JournalEntryDraft` via `GLPostingSafeInvoker.postSaleInNewTx`, re-reads the invoice totals via a Sales DTO — **the inheritance point: it also re-reads the invoice's dimension default and stamps the lines**).
  - **Stock / valuation** ([ADR-0020](0020-inventory-valuation-data-model.md) / V17): `InventoryGlPoster` builds the COGS / adjustment / opening drafts; `StockServiceImpl.adjust(...)` posts the shrinkage expense leg directly via `GLPostingService.post` (the stock-adjustment document-default inheritance point, FR-CC-12).
  - **AP** ([ADR-0015](0015-accounts-payable-data-model.md) / V12): `BillMatchServiceImpl.postMatchedBillToGl(SupplierBill)` builds the bill draft (DR PURCHASES/INVENTORY/GRNI · CR AP) — the supplier-bill document-default inheritance point (FR-CC-11).
  - **IAM / platform**: `ScopeGuard.companyIdOf(targetType, uid)` switch ([ScopeGuard.java](../../backend/src/main/java/com/erp/platform/security/ScopeGuard.java):204 — the new `dimension`/`dimensionvalue` cases land here, each backed by a `findCompanyIdByUid` projection, the 30+ existing cases' pattern); `@perm.has(code)` / `@perm.scoped(uid, targetType, code)` ([PermissionChecks.java](../../backend/src/main/java/com/erp/platform/security/PermissionChecks.java)) — **NEVER `hasAuthority`**; `MasterStatus` ([common/domain/MasterStatus.java](../../backend/src/main/java/com/erp/platform/common/domain/MasterStatus.java)); `code_sequence` numbering (ADR-0007 D-6); the V17 permission-seed + `ORG_ADMIN` CROSS-JOIN grant pattern (`INSERT … permissions … ON CONFLICT (code) DO NOTHING` + `INSERT … role_permission … CROSS JOIN … WHERE r.code='ORG_ADMIN' … ON CONFLICT DO NOTHING`); the **ISSUES-REGISTER #12** seed-uid rule: every per-company CROSS-JOIN seed-uid MUST be `'XX' || lpad(company_id::text,6,'0') || substr(md5(key),1,12)` (≤26 chars), **never** `|| key`.
  - [[db-naming-convention]] verified against V1–V19 (plural masters/children, singular junctions, singular constraint roots `uq_`/`fk_`/`chk_`, plural `ix_` indexes, `uid VARCHAR(26)` ULID, `company_id` BIGINT scalar, the additive `DROP/ADD CONSTRAINT` widen). **Latest shipped migration is `V19__sales_returns.sql`.** Per the build instruction this module takes the **reserved range `V27`–`V29`** (V20–V26 are reserved for other in-flight modules so the coordinator can sequence without collision — see D-9). Next free ADR number is **0025** (0022–0024 reserved for in-flight modules; this ADR claims 0025 per the build instruction).

This ADR is the **technical data model + integration design** for the Cost-centre / accounting-dimension framework (PATH-TO-FULL-ERP §3.11 build-first enabler, critical-dependency #4). It translates the spec into: the two new master tables in a new `com.erp.modules.costing` module (`dimensions` + `dimension_values`), the **bounded fixed-slot tag on `journal_lines`** (four additive nullable columns alongside `branch_id`) + the matching `JournalEntryDraft.LineDraft` extension, the source-document dimension-default columns on the four wired documents, the validation/resolution service the posting path calls, the dimension-sliced reporting reads, the `COSTING.*` permission catalogue, the `ScopeGuard` cases, the ArchUnit edges, and the additive `V27`–`V29` migration ordering with **#12-safe seeds**. It is **concrete enough that the backend engineer builds the model + the slot-columns + the posting-path validation + the four document-default inheritance points + the dimension-sliced TB without guessing a business rule.** It writes **no production code, no entities, no migration SQL** — that is the engineer's next step.

## Context

GL was built dimension-ready (ADR-0013 D-7): `journal_lines.branch_id` is a *nullable analysis tag*, never a separate ledger; a branch P&L is `journal_lines GROUP BY branch_id × account type`. The branch is the **first dimension**. What is missing — and what gates **Budgeting** (§3.14) and **Projects** (§3.13) — is a **generic, configurable dimension model** (Cost Centre, Department, …) and the **seam** that lands those dimension values on the journal lines and slices the books by them. The forces:

- **THE LOAD-BEARING SHAPE DECISION (OQ-CC-01): fixed slot-columns vs a per-line dimension child table.** Two models. **(a)** a bounded set of nullable `*_value_id` columns on `journal_lines` (Cost Centre, Department, + reserved slots), extending the `branch_id` precedent — a single-row read-modify-write on the existing line, simple indexed GROUP BYs, no extra rows on the hot posting path. **(b)** a `journal_line_dimensions(line_id, dimension_id, value_id)` child table — unbounded dimensions, but a join + N rows per posted line on the hot path and a more complex group. The brief's "extend `journal_lines` with nullable FKs + GROUP BY on reads" (§3.11) names (a); branch already proves (a) works. Resolved in **D-3** — **fixed slots (a)**, bounded at 4 user-defined dimensions (BR-CC-02).

- **The framework must change NO posting's correctness (NFR-CC-01, BR-CC-07).** It tags lines a posting already produces; it does not change *which* account a posting hits, *what* it debits/credits, or introduce a new `gl_config` / posting. This is the property that makes it safe to integrate with the shipped `GLPostingService` and every existing poster additively: with no dimensions configured and no document default set, every existing posting produces byte-for-byte the same journal lines. Resolved in **D-4** — the dimension ids are optional, nullable, additive params on `LineDraft`; the posting engine persists them after its existing validation; an untagged draft is unchanged.

- **Validation must extend the engine's existing checks, not bolt on a second path.** `GLPostingService.post` already validates each line's account is active + same-company; dimension validation (active value, correct slot, same company) is the *same kind of check* on the *same line* and belongs in the *same* validate step — balanced-or-rejected extends to dimension validity (BR-CC-04). Resolved in **D-4 / D-5**.

- **Inheritance from source documents (OQ-CC-02) multiplies touch-points — pick a v1 set.** The human picks the dimension on a *document* (a manual journal line, an invoice/bill header, an adjustment); the posting copies it onto the journal lines. Wiring *every* poster is more complete but multiplies risk. Resolved in **D-6** — four wired documents in v1 (manual journal per-line; sales invoice / supplier bill / stock adjustment header-level), the rest additive.

- **Budgeting and Projects build on this — design their contract up front (FR-CC-17, NFR-CC-06).** Both are greenfield and gate on this framework. Project is *one more dimension*, not a bespoke `project_id` scattered across postings. The framework must expose a documented service/DTO contract (resolve value by uid, list active values, read actuals grouped by account×value×period) so those modules consume the seam, not the tables. Resolved in **D-8**.

- **Module placement (OQ-CC-06).** A dedicated `com.erp.modules.costing` module owns the dimension masters + the validation/resolution service + the reporting query, with GL/Sales/Purchases/Stock posting paths *calling into* it. Folding dimensions into `gl` would pull Sales/Purchases document-tagging concerns into GL. Resolved in **D-1**.

- **Schema freeze / migration direction.** IAM=V1 … Sales Returns=V19, all frozen. This framework is additive: 2 new master tables, 4 additive nullable columns on `journal_lines`, dimension-default columns on 4 source-document tables, the permission seed, the per-company seed of the two built-in dimensions. It edits no shipped migration. Per the build instruction it lands in the **reserved `V27`–`V29`** range (D-9). It adds **no `gl_config` key, no CoA account, no `JournalSourceType`, no movement type** (BR-CC-07).

## Decision

### D-1 — Module placement: a new `com.erp.modules.costing` module owns the dimension masters + validation/resolution + reporting; GL/Sales/Purchases/Stock call into it (OQ-CC-06)

The dimension model lives in a **new `com.erp.modules.costing`** module — it is a cross-cutting analysis concern that **Budgeting, Projects, Sales, Purchases, Stock and GL** all touch; folding it into `gl` would pull Sales/Purchases/Stock document-tagging concerns into the books module and make GL depend on Sales (a cycle). `costing` owns the masters + the validation/resolution service + the dimension-sliced reporting query; the **posters call into `costing.service`** for validation/resolution and persist the resolved value id on the journal line (which physically lives in the `gl`-owned `journal_lines` table — the slot-columns are GL's, the *value master* is costing's; the FK is cross-module-by-scalar-id, no `@ManyToOne` across modules — D-7).

Internal layout:

```
com.erp.modules.costing
├── domain.entity   Dimension, DimensionValue
├── domain.dto      DimensionDto, DimensionValueDto,
│                   CreateDimensionValueRequest / UpdateDimensionValueRequest,
│                   SetDimensionMandatoryRequest,
│                   DimensionTagDto              (the {slot → valueId/valueUid} bundle a poster/document carries),
│                   DimensionSlicedTbRowDto / DimensionSlicedTbDto,
│                   DimensionActualsRowDto       (the Budgeting/Projects read contract — D-8)
├── domain.enums    DimensionSlot (COST_CENTRE | DEPARTMENT | DIMENSION_3 | DIMENSION_4),  // the 4 user-defined slots, D-3
│                   (branch is NOT a slot — BR-CC-08)
├── repository      DimensionRepository, DimensionValueRepository
└── service         DimensionService(+Impl)            — master CRUD + mandatory flag (COSTING.MANAGE)
                    DimensionResolver                   — resolve+validate a DimensionTag for the posting path (D-5)
                    DimensionSlicedReportQuery          — the dimension-sliced TB/ledger reads (D-8)
                    DimensionSeeder                     — seeds Cost Centre + Department dimension rows per company (D-9)
```

Controllers stay flat in `com.erp.api`: `DimensionController` (list dimensions + set-mandatory), `DimensionValueController` (cost-centre / department CRUD), `DimensionReportController` (the dimension-sliced TB). They touch only services (`ModuleBoundaryTest`).

**Boundary note (D-7):** `costing` depends only on `platform.*` + IAM (scope/audit). **GL** does **not** depend on `costing` for posting — the dimension value ids arrive on the `LineDraft` already-resolved (the poster resolved them via `costing.service` before building the draft), so GL persists scalar `Long` ids and never imports a costing entity. **Sales/Purchases/Stock** depend on `costing.service` + `costing.domain.dto` (to resolve a document's dimension default → value ids before building the draft) — a service/DTO read edge, the established `sales → products`/`stock → gl.service` shape. No module→module cycle (D-7).

### D-2 — The dimension masters: `dimensions` (types, per company) + `dimension_values` (members, per company)

Two new tables in `com.erp.modules.costing`. Both: plural names; `id` BIGINT IDENTITY PK; `uid` VARCHAR(26) ULID `uq_<root>_uid`; `company_id` BIGINT NOT NULL (tenant scope, the §3.2 predicate); `MasterStatus status`; standard audit cols; `@Version`.

#### `dimensions` (the dimension types, per company)

| column | type | null | notes |
|---|---|---|---|
| `id` | BIGINT IDENTITY PK | NO | |
| `uid` | VARCHAR(26) | NO | `uq_dimension_uid` |
| `company_id` | BIGINT | NO | FK → `companies(id)`; `fk_dimension_company`; tenant scope |
| `slot` | VARCHAR(20) | NO | `DimensionSlot` ∈ {`COST_CENTRE`,`DEPARTMENT`,`DIMENSION_3`,`DIMENSION_4`}; **maps 1:1 to a `journal_lines` slot-column** (D-3); `uq_dimension_company_slot UNIQUE (company_id, slot)` — one dimension per slot per company; `chk_dimension_slot` CHECK in-list |
| `code` | VARCHAR(40) | NO | stable key, e.g. `COST_CENTRE`/`DEPARTMENT`; `uq_dimension_company_code UNIQUE (company_id, code)` |
| `name` | VARCHAR(120) | NO | display label, editable (a company may rename "Cost Centre" → "Cost Center") |
| `is_built_in` | BOOLEAN | NO | DEFAULT false; `true` for the seeded Cost Centre + Department — cannot be deleted (FR-CC-01) |
| `is_mandatory` | BOOLEAN | NO | DEFAULT false; the opt-in governance flag (FR-CC-13, BR-CC-03) — **off by default so no existing posting is affected** |
| `status` | VARCHAR(32) | NO | `MasterStatus`; DEFAULT `'ACTIVE'` |
| `version` + audit cols | | | standard |

Constraints: `uq_dimension_uid`; `uq_dimension_company_slot`; `uq_dimension_company_code`; `fk_dimension_company`; `chk_dimension_slot CHECK (slot IN ('COST_CENTRE','DEPARTMENT','DIMENSION_3','DIMENSION_4'))`. Index: `ix_dimensions_company ON dimensions (company_id)`.

> v1 seeds exactly two rows per company: `(COST_CENTRE, 'COST_CENTRE', 'Cost Centre', is_built_in=true)` and `(DEPARTMENT, 'DEPARTMENT', 'Department', is_built_in=true)`. `DIMENSION_3`/`DIMENSION_4` slots exist in the enum/CHECK but **no dimension row occupies them in v1** — they are reserved for Project (§3.13) + one future, added as a `dimensions` INSERT + per-poster wiring, no schema change (BR-CC-02, NFR-CC-06, OQ-CC-08).

#### `dimension_values` (the members — cost centres / departments — per company, child of `dimensions`)

| column | type | null | notes |
|---|---|---|---|
| `id` | BIGINT IDENTITY PK | NO | journal lines reference this id (scalar, cross-module — D-7) |
| `uid` | VARCHAR(26) | NO | `uq_dimension_value_uid`; URLs address by uid; `ScopeGuard case "dimensionvalue"` (D-5) |
| `company_id` | BIGINT | NO | FK → `companies(id)`; denormalised from the dimension (tenant predicate without a join); `fk_dimension_value_company` |
| `dimension_id` | BIGINT | NO | FK → `dimensions(id)`; `fk_dimension_value_dimension`; the owning dimension/slot |
| `code` | VARCHAR(40) | NO | e.g. `CC-200`/`DEPT-OPS`; **unique per dimension per company**: `uq_dimension_value_dim_code UNIQUE (dimension_id, code)` (FR-CC-18); user-supplied (OQ-CC-03 default — no `code_sequence`) |
| `name` | VARCHAR(160) | NO | e.g. `Logistics`/`Operations` |
| `parent_id` | BIGINT | YES | FK → `dimension_values(id)` (self); the roll-up parent (FR-CC-02); same dimension + same company + acyclic (service-validated, BR-CC-09); NULL = root |
| `is_active` | BOOLEAN | NO | DEFAULT true; the **tagging gate** — an inactive value is excluded from new tagging but stays on historical lines (FR-CC-04, the `chart_of_accounts.is_active` precedent) |
| `status` | VARCHAR(32) | NO | `MasterStatus`; DEFAULT `'ACTIVE'` (lifecycle; `is_active` is the tagging gate) |
| `version` + audit cols | | | standard |

Constraints: `uq_dimension_value_uid`; `uq_dimension_value_dim_code`; `fk_dimension_value_company`; `fk_dimension_value_dimension`; `fk_dimension_value_parent FOREIGN KEY (parent_id) REFERENCES dimension_values (id)` (self; nullable). Indexes:
```
CREATE INDEX ix_dimension_values_company        ON dimension_values (company_id);
CREATE INDEX ix_dimension_values_dimension      ON dimension_values (dimension_id);
CREATE INDEX ix_dimension_values_active         ON dimension_values (dimension_id) WHERE is_active = true;  -- picker working set
CREATE INDEX ix_dimension_values_parent         ON dimension_values (parent_id) WHERE parent_id IS NOT NULL; -- roll-up
```

> **No hard-delete of a value with postings (FR-CC-03/BR-CC-05).** `DimensionValueService.delete` rejects if any `journal_lines.<slot>_value_id` references it (a cross-module count via `costing → gl` read, D-7) — else deactivate. Historical tags are immutable (append-only ledger). Mirrors `ChartOfAccountService.delete` (BR-GL-07).

### D-3 — THE LOAD-BEARING SHAPE: a bounded fixed set of nullable slot-columns on `journal_lines` (OQ-CC-01)

**Decision: extend `journal_lines` with four additive nullable `*_value_id` columns — one per user-defined dimension slot — alongside the pre-existing `branch_id` analysis tag.** This is the `branch_id` precedent (ADR-0013 D-7) applied four more times: a single-row tag, simple to GROUP BY and index, no extra rows on the hot posting path (NFR-CC-02/03). Rejected: a `journal_line_dimensions` child table (a join + N rows per posted line on the hot path, a heavier group — the flexibility of unbounded dimensions is not worth the hot-path cost at the bounded-dimension reality the business has; see Alternatives).

**ALTER `journal_lines` (additive, V27):**

| column | type | null | default | notes |
|---|---|---|---|---|
| `cost_centre_value_id` | BIGINT | YES | NULL | FK → `dimension_values(id)`; the Cost Centre slot tag; `fk_journal_line_cost_centre` |
| `department_value_id` | BIGINT | YES | NULL | FK → `dimension_values(id)`; the Department slot tag; `fk_journal_line_department` |
| `dimension3_value_id` | BIGINT | YES | NULL | FK → `dimension_values(id)`; reserved (Project, §3.13); `fk_journal_line_dimension3`; **no v1 poster writes it** |
| `dimension4_value_id` | BIGINT | YES | NULL | FK → `dimension_values(id)`; reserved (future); `fk_journal_line_dimension4` |

- All four **nullable** — an untagged line (no dimension) is the default and behaves exactly as today (NFR-CC-01, BR-CC-03). **No CHECK** forces any to be set (mandatory enforcement is in the *service* per-company, FR-CC-08, not a DB CHECK — a DB CHECK could not be per-company-toggled).
- The FK is a **real DB FK within the same database** to `dimension_values(id)` (both `journal_lines` and `dimension_values` live in the one Postgres) — this is the **same-company backstop** (a value from another company cannot be referenced because the service resolves only same-company values, D-5, and the value's own `company_id` matches). It is **cross-module by scalar id**, persisted by GL but the value-master owned by costing; **no cross-module `@ManyToOne`** — the GL `JournalLine` entity carries `Long cost_centre_value_id` (a scalar), not a `DimensionValue` association (D-7).
- Indexes for the dimension-sliced TB/P&L aggregate (NFR-CC-02 — the `ix_journal_lines_company_account` precedent):
```
CREATE INDEX ix_journal_lines_cost_centre ON journal_lines (company_id, cost_centre_value_id) WHERE cost_centre_value_id IS NOT NULL;
CREATE INDEX ix_journal_lines_department  ON journal_lines (company_id, department_value_id)  WHERE department_value_id  IS NOT NULL;
```
(Partial indexes — the vast majority of v1 lines are untagged; the index covers only tagged lines, keeping it small. `dimension3/4` get partial indexes when their dimension is activated by the consuming module — additive, not in V27.)

**The matching `JournalEntryDraft.LineDraft` extension (D-4).** This is the only GL-module touch beyond the columns.

### D-4 — Posting-path integration: extend `LineDraft` with optional dimension value ids; `GLPostingService.post` validates + persists them; an untagged draft is unchanged (BR-CC-07, NFR-CC-01)

**Extend the shipped `LineDraft` record additively:**

```java
public record LineDraft(
        Long accountId,
        BigDecimal debitAmount,
        BigDecimal creditAmount,
        String currency,
        String lineMemo,
        // NEW — optional dimension value ids (already resolved by the poster via costing.service, D-5).
        // null = untagged for that slot. Existing callers use the compact constructor / a builder that
        // defaults these to null, so no existing poster changes behaviour (NFR-CC-01).
        Long costCentreValueId,
        Long departmentValueId,
        Long dimension3ValueId,
        Long dimension4ValueId
) { }
```

> **Backward compatibility:** every shipped poster (the `GLPostingSafeInvoker.postSaleInNewTx` line-builds, `InventoryGlPoster`, `BillMatchServiceImpl`, AR/cash/year-end posters) constructs `LineDraft` today with 5 args. The engineer adds a **5-arg convenience constructor** (delegating to the 9-arg with the four ids null) OR adds the four params and updates each call site to pass `null` — *recommended: the convenience constructor*, so the existing call sites are literally unchanged (NFR-CC-01 is then trivially true for every un-wired poster). Only the **four wired posters** (D-6) pass non-null ids.

**`GLPostingService.post` (and `postReversal`) gain a dimension-validation + persist step — inside the existing validate, no second path (BR-CC-04):**
1. (unchanged) resolve OPEN period; validate ≥2 lines, balanced, active accounts, same company, base currency.
2. **NEW** — for each line, for each non-null dimension value id: call `DimensionResolver.validateForPosting(companyId, slot, valueId)` (D-5) — assert the value is **active**, belongs to the **dimension occupying that slot** in **this company**; reject the whole post on any failure (nothing partial written — the balanced-or-rejected invariant extends to dimension validity).
3. **NEW** — for each dimension flagged `is_mandatory` for the company, assert every line carries that slot's value; reject if any line is missing it (FR-CC-08; **skipped entirely when no dimension is mandatory — the v1 default**, so the check is a no-op for an un-configured company, NFR-CC-01).
4. (unchanged) allocate `JB-####`; insert batch + entry + **lines now carrying the four `*_value_id` columns**; emit `GL.JOURNAL.POST` audit.

> **GL depends on `costing.service` for step 2/3?** To keep GL a leaf (no `gl → costing` edge that, with `costing → gl` for the no-delete count, would cycle — D-7), the validation is done by the **poster before building the draft** for the wired posters, *and* `GLPostingService` does a **lightweight self-contained validity assertion** using a **GL-local read** of the value's `(company_id, dimension slot, is_active)` — concretely, **a `DimensionValue` read projection GL queries via a narrow repository GL owns OR the resolved-and-validated guarantee from the poster.** **Decision (D-7 resolves the direction cleanly):** the **`DimensionResolver` lives in `costing` and is called by the *poster* (Sales/Purchases/Stock/GL-manual-journal-service) to resolve+validate the document's dimension default into validated value ids *before* building the draft**; `GLPostingService.post` then trusts the ids are valid but **re-asserts same-company via the DB FK** (the FK to `dimension_values` rejects a bad id at insert) and **re-asserts active + correct-slot via a GL-local projection on `dimension_values`** (GL reading the costing-owned table by a narrow `@Query` projection is the **same cross-cutting read** `ScopeGuard` does across modules — allowed; it is a read of a value row, not a costing *service/entity* import). The mandatory check (step 3) is driven by a per-company `dimensions.is_mandatory` flag GL reads the same way. This keeps `gl → costing.table-read` (a projection, like ScopeGuard) without a `gl → costing.service` edge, and `costing → gl.table-read` (the no-delete count) — **two table-level read edges, no service cycle** (D-7 ArchUnit note).

**The untagged guarantee (NFR-CC-01, the release-blocker test):** with no dimension mandatory and no document default set, step 2 iterates zero non-null ids, step 3 finds no mandatory dimension, and the inserted lines carry NULL in all four slot columns — **byte-for-byte the journal of today**. An integration test must post an existing flow (a sale, a bill) with dimensions un-configured and assert the journal lines are identical to the pre-V27 baseline.

### D-5 — `DimensionResolver`: resolve a document's dimension default → validated value ids (the poster's entry point)

`DimensionResolver` (in `costing.service`) is the single validation/resolution authority the posters call:

- `resolveTag(companyId, DimensionTag tag) → ResolvedDimensionTag` — takes a `DimensionTag` (the `{slot → valueUid}` a document declared, by uid) and returns the resolved `{slot → valueId}`, asserting each value is **active**, of the **dimension occupying that slot**, in **this company**; throws (rejecting the document finalise) on any violation (BR-CC-04). A null/empty tag resolves to all-null (untagged).
- `validateForPosting(companyId, slot, valueId)` — the per-id assertion `GLPostingService` re-runs (active + correct slot + same company); the narrow form used by the engine's step 2.
- `mandatorySlots(companyId) → Set<DimensionSlot>` — the slots whose dimension `is_mandatory` for the company (drives the engine's step 3); empty for an un-configured company.

`DimensionResolver` reads `dimensions` + `dimension_values` (its own module's tables) — no cross-module read. The posters call `resolveTag` before building the `JournalEntryDraft`; they pass the resolved value ids onto each `LineDraft`.

**`ScopeGuard` additions (D-5):** `ScopeGuard.companyIdOf` gains two cases so `@perm.scoped` gates resolve a costing uid to its company:
```java
case "dimension"        -> dimensions.findCompanyIdByUid(uid);
case "dimensionvalue"   -> dimensionValues.findCompanyIdByUid(uid);
```
Each backed by a `@Query("SELECT x.companyId FROM … WHERE x.uid = :uid")` projection on the respective costing repository, mirroring the 30+ existing cases. `ScopeGuard` gains two costing-repository constructor deps — the accepted cross-cutting-spine pattern (it already holds sales/stock/gl/ar/ap/cashbank/tax repos). `assertCanActIn` is called on **every** costing read path (dimension list, value list, dimension-sliced TB, value drill) — NFR-CC-04.

### D-6 — Source-document dimension defaults: the four wired documents (OQ-CC-02)

The human picks the dimension on a **document**; the posting inherits it. v1 wires **four** documents (the rest additive). Each is an **additive, nullable** set of dimension-default columns on the existing document table + a resolve-and-stamp step in its post path.

| document | where the default lives | which journal lines inherit | post path that stamps |
|---|---|---|---|
| **Manual journal entry** (GL composer) | **per journal line** — the composer's `PostJournalLineRequest` gains optional `costCentreValueUid`/`departmentValueUid`/… ; resolved by `DimensionResolver` in `JournalServiceImpl` before posting | each line carries exactly its own picked values (the most granular, always-available tagging point) | `JournalServiceImpl.post` (the manual path) resolves per-line tags → `LineDraft` ids |
| **Sales invoice** | **header-level** default on `sales_invoices` (ALTER, V28): `cost_centre_value_id`, `department_value_id` nullable (OQ-CC-04 — header in v1; per-`sales_invoice_lines` deferred) | the auto-posted revenue lines (and COGS lines via the delivery handler where applicable) | `SalesPostingHandler` re-reads the invoice's dimension default (extend `InvoicePostingTotalsDto` with the two value ids) and stamps the `LineDraft`s it builds in `GLPostingSafeInvoker.postSaleInNewTx` |
| **Supplier bill** | **header-level** default on `supplier_bills` (ALTER, V28): two nullable value-id columns | the expense / inventory journal lines (`postMatchedBillToGl`) | `BillMatchServiceImpl.postMatchedBillToGl` reads the bill's default and stamps its `LineDraft`s |
| **Stock adjustment** | **header-level** default on the adjustment (ALTER, V28 — on the `stock_movements` ADJUSTMENT row's source doc, or carried in the adjust request → stamped) | the adjustment expense leg (`InventoryGlPoster` adjustment draft) | `StockServiceImpl.adjust` resolves the request's dimension tag and stamps the expense `LineDraft` |

> **Inheritance is one-way, at post time, by copy (BR-CC-06).** The poster resolves the document's declared default via `DimensionResolver.resolveTag(companyId, tag)`, then copies the resolved value ids onto the `LineDraft`s. The credit/cash/AR/AP control legs of these postings may be left untagged or inherit the same default — **decision: only the P&L-relevant legs inherit (revenue, expense, COGS, inventory, adjustment-expense); the balance-sheet control legs (AR/AP/Cash/VAT) post untagged in v1** (a cost centre is a P&L analysis axis; tagging the AR control account with a cost centre is not meaningful for v1's departmental-P&L use case and would complicate the control-account reconciliation reads). This is a v1 simplification (flag OQ — extend if Budgeting needs balance-sheet dimensions).

> **The document-default columns are on the document tables (V28), the slot-columns are on `journal_lines` (V27).** `sales_invoices` / `supplier_bills` get two nullable `*_value_id` columns each (Cost Centre + Department header default); the stock-adjustment default rides the adjust request (or an additive column on the adjustment's source row). Each is `fk_<table>_cost_centre` / `fk_<table>_department` → `dimension_values(id)` (the same-company backstop). These ALTERs are additive on frozen tables.

### D-7 — Module boundary: two table-level read edges, no service cycle, no cross-module `@ManyToOne` (NFR-CC-07)

`ModuleBoundaryTest` discipline:
- **`costing → platform.*` + IAM** — scope/audit/`MasterStatus`/`code_sequence`. The platform spine edge.
- **`costing → gl.repository` (a narrow read projection)** — `DimensionValueService.delete` counts `journal_lines` referencing the value (the no-delete-if-posted check, FR-CC-03). This is a **read** of the GL table via a narrow count projection — the cross-cutting read pattern `ScopeGuard` uses; it imports no GL *service/entity*, only a count. (Alternatively GL exposes a `countLinesUsingDimensionValue(companyId, valueId)` repository method costing calls — same effect, GL-owned query.)
- **`gl → dimension_values` (a narrow read projection)** — `GLPostingService.post` re-asserts a value's active/slot/company via a `dimension_values` projection (D-4 step 2), and reads `dimensions.is_mandatory` (step 3). A **read** of the costing tables, not a `costing.service` import — the same cross-cutting read `ScopeGuard` does across every module's repository.
- **`sales`/`purchases`/`stock` → `costing.service` (`DimensionResolver`) + `costing.domain.dto`** — the posters resolve a document's dimension default before building the draft (D-6). A service/DTO read edge, the established `sales → products`/`stock → gl.service` shape.
- **No `gl → costing.service` and no `costing → gl.service`** — the two edges are **table-level read projections** (like `ScopeGuard`), not service-to-service calls, so **no service cycle forms**. Direction: `costing → gl.table-read` (delete count), `gl → costing.table-read` (post validation), `posters → costing.service` (resolve). The DB FKs (`journal_lines.*_value_id` → `dimension_values`; `sales_invoices`/`supplier_bills` defaults → `dimension_values`) are intra-database scalar-id references, **no cross-module JPA association** — the `journal_lines` entity carries `Long` ids, not `DimensionValue` objects (the `stock_movements.source_document_uid` no-cross-module-FK-association discipline).
- The ArchUnit allow-list documents the two table-level read edges (`gl ↔ costing` table reads) as accepted, like the `ScopeGuard`-reads-every-repository allowance. **No cycle** — verified against the ADR-0013 D-11 / ADR-0020 D-12 / ADR-0021 D-13 documented allowances.

### D-8 — Dimension-sliced reporting + the Budgeting/Projects read contract (FR-CC-14..17)

**Dimension-sliced TB (the v1 read, `DimensionSlicedReportQuery` in `costing`).** The shipped TB is `journal_lines GROUP BY account_id SUM(debit)−SUM(credit)` (`JournalLineRepository.trialBalanceSums`). The dimension-sliced variant adds a `WHERE <slot>_value_id = :valueId` filter and/or a `GROUP BY <slot>_value_id, account_id`:
```sql
SELECT cost_centre_value_id, account_id,
       SUM(debit_amount) AS total_debit, SUM(credit_amount) AS total_credit
FROM journal_lines
WHERE company_id = :companyId
  [AND cost_centre_value_id = :valueId | AND cost_centre_value_id IN (:valueId + descendants)]   -- roll-up FR-CC-16
  [AND entry's posting_date within :period]
GROUP BY cost_centre_value_id, account_id
```
joined to `chart_of_accounts` (code/name/type) and `dimension_values` (value code/name). Hits `ix_journal_lines_cost_centre` (NFR-CC-02). Roll-up (FR-CC-16): the query expands a parent value to its descendant ids via the `parent_id` chain (a recursive CTE or a resolved id-set the service computes from `ix_dimension_values_parent`). Returns `DimensionSlicedTbDto { dimensionSlot, rows: [DimensionSlicedTbRowDto{ valueUid, valueCode, valueName, accountUid, accountCode, accountName, accountType, totalDebit, totalCredit, net }] }`. Scoped + `assertCanActIn` + RBAC (`COSTING.VIEW` + `GL.VIEW`). **No materialised view in v1** — the partial index makes the slice cheap at QA scale (the ADR-0013 D-8 stance).

> **Note — a dimension slice does NOT net to zero (BR-CC-01).** Unlike the company TB, a cost-centre slice can be non-zero (a line is tagged while its balancing line is untagged or differently tagged). The report presents the slice's net; it does not assert it balances. This is documented so a future reader does not "fix" a non-zero slice as a bug.

**The Budgeting/Projects read contract (FR-CC-17, NFR-CC-06) — designed up front so those modules build on the seam, not the tables:**
- `DimensionService.resolveValue(uid) → Optional<DimensionValueDto>` (uid → id/company/slot — what Budgeting needs to attach a budget line to a cost centre).
- `DimensionService.listActiveValues(companyId, slot) → List<DimensionValueDto>` (the picker source).
- `DimensionSlicedReportQuery.actualsByAccountValuePeriod(companyId, slot, fromDate, toDate) → List<DimensionActualsRowDto{ accountId, valueId, periodNo, totalDebit, totalCredit, net }>` — the **actuals roll-up** budget-vs-actual variance reads against (the `periodMovementByAccount` precedent, grouped by value). **Budgeting consumes this DTO; it does not query `journal_lines` directly.** This is the contract that makes Budgeting a consumer, not a re-implementer.

### D-9 — Migration: additive `V27`–`V29` (V1–V19 frozen; V20–V26 reserved for in-flight modules); #12-safe seeds

Per the build instruction, the framework takes the **reserved range `V27`–`V29`** — V20–V26 are reserved for other in-flight modules (Budgeting/Projects/etc. claim earlier slots in their own cycles) so the coordinator sequences without collision. Three additive files (a clean table-level cut; the engineer may collapse to fewer if the PM prefers, the names below are fixed):

**`V27__cost_centre_dimensions.sql`** — the framework core:
1. **CREATE** `dimensions` (D-2: `slot`/`code`/`name`/`is_built_in`/`is_mandatory`/`status` + constraints + `ix_dimensions_company`).
2. **CREATE** `dimension_values` (D-2: `dimension_id`/`code`/`name`/`parent_id` self-FK/`is_active`/`status` + constraints + the four indexes).
3. **ALTER `journal_lines`** — `ADD COLUMN cost_centre_value_id BIGINT` + `department_value_id` + `dimension3_value_id` + `dimension4_value_id` (all nullable) + the four `fk_journal_line_<slot>` FKs → `dimension_values(id)` + the two partial indexes (`ix_journal_lines_cost_centre`, `ix_journal_lines_department`). (Existing lines back-fill to NULL — correct: they were untagged.)
4. **permission seed + `ORG_ADMIN` grant** — INSERT the `COSTING.*` perms (D-10) `ON CONFLICT (code) DO NOTHING` + the `role_permission` CROSS-JOIN grant `WHERE r.code='ORG_ADMIN' … ON CONFLICT DO NOTHING` (the V17 pattern).
5. **dimension seed per existing company** — INSERT the two built-in `dimensions` (`COST_CENTRE`, `DEPARTMENT`) per company via `CROSS JOIN companies`, with **#12-safe seed-uids**: `'DIM' || lpad(company_id::text,6,'0') || substr(md5(slot),1,12)` (≤26 chars — **never** `|| slot`), `is_built_in=true`, `is_mandatory=false`, `status='ACTIVE'`, `ON CONFLICT (company_id, slot) DO NOTHING`. **No dimension *values* seeded** — cost centres/departments are company-specific data the controller creates (no canonical seed list). The `DimensionSeeder` (Java) seeds the two built-in dimension rows for **new** companies (`BootstrapRunner` + `CompanyService.create`, the `TaxRateSeeder`/`InventoryGlSeeder` precedent).

**`V28__cost_centre_document_defaults.sql`** — the source-document inheritance columns (D-6):
6. **ALTER `sales_invoices`** — `ADD COLUMN cost_centre_value_id BIGINT` + `department_value_id BIGINT` (nullable) + `fk_sales_invoice_cost_centre`/`fk_sales_invoice_department` → `dimension_values(id)`.
7. **ALTER `supplier_bills`** — same two nullable columns + `fk_supplier_bill_cost_centre`/`fk_supplier_bill_department`.
8. **ALTER the stock-adjustment carrier** — the adjustment dimension default (per D-6: an additive nullable pair on the relevant stock table, or carried in the adjust request and stamped — the engineer's call with the stock owner; if a column, `fk_stock_*` → `dimension_values`).

**`V29__cost_centre_dimension3_indexes.sql`** *(optional / forward — may be folded into V27)* — reserved for the partial indexes on `dimension3_value_id`/`dimension4_value_id` when the Projects module activates slot 3 (additive then). **v1 may ship V27+V28 only** and leave V29 for the Projects increment; the range is reserved either way. *Recommendation: ship V27 + V28; V29 is the Projects module's to claim when it wires slot 3.*

**#12 safety:** the **only** per-company CROSS-JOIN seed-uid in this module is the two built-in dimension rows (step 5) — guarded by the md5-bounded form above. No dimension *values* are seeded, so no value-uid #12 exposure. `MigrationKeepDataIT` extends to V28 (the additive nullable columns back-fill to NULL — keep-data-safe; the journal-line baseline is unchanged, NFR-CC-01). **No `gl_config` seed, no CoA account, no `JournalSourceType` widen, no movement-type widen** (BR-CC-07).

### D-10 — Permission catalogue (`COSTING.*`) + audit emit points

**Permissions (seeded in V27, module `costing`, granted to `ORG_ADMIN` by the CROSS-JOIN pattern; gated via `@perm.has`/`@perm.scoped`, NEVER `hasAuthority`):**

| code | module | description |
|---|---|---|
| `COSTING.VIEW` | costing | View dimensions, dimension values (cost centres / departments), and the dimension-sliced trial balance |
| `COSTING.MANAGE` | costing | Maintain dimension values (create/edit/deactivate) and set a dimension mandatory/optional |
| `COSTING.TAG` | costing | Pick dimension values on a journal / invoice / bill / adjustment when posting (the tagging permission distinct from posting itself) |

Naming mirrors the shipped catalogue (`GL.VIEW`/`GL.MANAGE`/`STOCK.ADJUST`): `MODULE.RESOURCE.ACTION`. The SYSTEM auto-poster (`SalesPostingHandler` etc.) runs under no user permission; it inherits the document's already-permissioned dimension default. A user lacking `COSTING.TAG` sees no dimension pickers (and, if a dimension is mandatory, cannot post — the deliberate governance consequence, §6.3).

**Audit emit points (every master mutation + mandatory-flag change, NFR-CC-05):**

| action | when | target_type / target |
|---|---|---|
| `COSTING.VALUE.CREATE` | create a cost-centre / department value | `dimension_values` / value id |
| `COSTING.VALUE.UPDATE` | edit name/parent/active | `dimension_values` / value id |
| `COSTING.VALUE.DEACTIVATE` | deactivate a value | `dimension_values` / value id |
| `COSTING.DIMENSION.MANDATORY.SET` | toggle a dimension mandatory/optional | `dimensions` / dimension id |

The dimension tag on a journal line is part of the immutable journal line (already audited by `GL.JOURNAL.POST`); the framework does not double-audit the post.

### D-11 — Angular nav routes

New routes under a **Costing** section (gated client-side by the perms, enforced server-side):
- `/costing/cost-centres` — Cost Centre value list + create/edit/deactivate (`COSTING.MANAGE`/`COSTING.VIEW`).
- `/costing/departments` — Department value list + CRUD.
- `/costing/dimensions` — dimension list + the mandatory toggle (`COSTING.MANAGE`).
- `/costing/trial-balance` — the dimension-sliced trial balance (filter by dimension value, roll-up toggle) (`COSTING.VIEW` + `GL.VIEW`).

Document-default pickers are **additions to existing screens** (the GL journal composer per-line; the sales-invoice and supplier-bill header forms; the stock-adjustment form) — not new routes, shown only when the user holds `COSTING.TAG`. All consume `ApiResponse<T>`/`PageMeta`, Long-as-string, address by uid.

## Consequences

**Positive**
- **Budgeting and Projects are unblocked** with a clean, documented contract (D-8 / FR-CC-17): Budgeting attaches budget lines to cost-centre dimension values and reads actuals via `actualsByAccountValuePeriod`; Projects adds a **Project dimension** in the reserved slot 3 (a `dimensions` INSERT + per-poster wiring) rather than scattering `project_id` columns — the framework was built for this (NFR-CC-06, OQ-CC-08).
- **Zero regression on the shipped books (NFR-CC-01, BR-CC-07).** The framework adds **no account, no `gl_config`, no posting, no source-type, no movement-type** — it only *tags* lines a posting already produces. With dimensions un-configured every existing posting is byte-for-byte unchanged (the convenience-constructor keeps every un-wired poster's call site literally unchanged). The integration test pins this.
- **The shape is boring and indexable (D-3).** Four nullable slot-columns extend the proven `branch_id` precedent; the dimension slice is a partial-indexed GROUP BY, not a join-heavy EAV scan; the hot posting path writes no extra rows (NFR-CC-02/03).
- **No service cycle (D-7).** The two cross-module edges are *table-level read projections* (the `ScopeGuard` pattern), not service calls; `ModuleBoundaryTest` stays green; the DB FKs are scalar-id, no cross-module JPA association.
- **Governance is opt-in (BR-CC-03).** Mandatory enforcement is a per-company per-dimension flag, off by default — a deployment turns it on deliberately; v1 never breaks an existing flow.

**Negative / costs**
- **The touch spans GL + Sales + Purchases + Stock in one release** (the four wired posters + the `LineDraft` extension + the four document-default ALTERs). Coordinated, but each touch is additive and small (a resolve-and-stamp step + two nullable columns). Flagged in the cross-module touch list.
- **The `LineDraft` extension touches a shipped record every poster constructs.** Mitigated by the 5-arg convenience constructor (un-wired posters unchanged); but the engineer must add the constructor *and* not accidentally regress an existing call site — a compile-time-safe change, test-pinned by NFR-CC-01.
- **Balance-sheet control legs post untagged in v1 (D-6).** Only P&L-relevant legs inherit the dimension; an AR/AP/Cash leg is untagged. Adequate for departmental-P&L; if Budgeting later needs balance-sheet dimensions, that is an additive wiring change (flagged).
- **Mandatory enforcement is partial (OQ-CC-05).** It applies only to the four wired posters; un-wired posters (AR receipt, cash, payroll) post untagged regardless. Turning Cost Centre "mandatory" does not retroactively force those — documented so it is not mistaken for full coverage.
- **Two reserved slots (`dimension3/4`) carry nullable columns no v1 poster writes.** The cost is two NULL columns + (deferred) partial indexes — negligible, and the alternative (ALTER `journal_lines` again when Projects lands) is the migration the reserved slots avoid.

**Neutral / deferred**
- Budgets, allocations, profit-centre roll-up dashboards, per-invoice-line dimension, full poster wiring, per-dimension security, dimension combination rules, FX dimension reporting — all deferred (spec §2), none precluded (NFR-CC-06). The Project dimension is reserved (slot 3), built by Projects.

## Alternatives considered

- **Dimension tag — fixed slot-columns (chosen) vs a `journal_line_dimensions` child table (EAV).** *Decided: fixed columns (D-3).* The child table allows unbounded dimensions but adds a join + N rows per posted line on the hot path and a heavier GROUP BY; the business reality is a small bounded set of dimensions (Cost Centre, Department, Project, +1) for which fixed nullable columns — the `branch_id` precedent — are faster to write, index (partial), and group. The EAV's flexibility is not worth the hot-path cost; if the business ever genuinely needs >4 simultaneous dimensions, the child table is an additive reshape under a new ADR. Fixed columns win for v1.
- **Module placement — a dedicated `costing` module (chosen) vs folding dimensions into `gl`.** *Decided: `costing` (D-1).* Dimensions are a cross-cutting analysis concern Budgeting/Projects/Sales/Purchases/Stock all touch; folding the master + the Sales/Purchases document-tagging concerns into `gl` would make GL depend on Sales (a cycle) and bloat the books module. A dedicated module keeps GL a leaf and gives Budgeting/Projects a clean home to consume.
- **Project — a dimension in this framework (chosen, reserved) vs a bespoke `project_id` column on every posting.** *Decided: Project is one more dimension (slot 3), built by the Projects module.* A bespoke `project_id` scattered across `journal_entries`/`ar_payments`/`stock_movements`/`sales_invoices` (the §3.10 sketch) duplicates the exact seam this framework builds and gives no shared reporting. Making Project a dimension reuses the slot-columns, the resolver, the inheritance, and the dimension-sliced reports — one mechanism, not two. Reserved, not built here (Projects owns its rollout).
- **Validation direction — poster resolves + GL re-asserts via table read (chosen) vs `GLPostingService` calls `costing.service`.** *Decided: table-level read projections both ways (D-7).* A `gl → costing.service` call plus the `costing → gl` no-delete count would form a service cycle. Resolving in the poster (which already touches `costing.service`) and having GL re-assert via a narrow `dimension_values` projection (the `ScopeGuard` cross-cutting-read pattern) keeps both edges at the table-read level — no service cycle, `ModuleBoundaryTest` green.
- **Mandatory enforcement — per-company flag in the service (chosen) vs a DB CHECK / a global setting.** *Decided: a per-company `dimensions.is_mandatory` flag enforced in the posting service.* A DB CHECK cannot be per-company-toggled (and would break every existing untagged line); a global setting cannot vary by tenant. The service flag, off by default, is the only shape that is opt-in per company without a schema reshape.
- **Inheritance set — four wired documents (chosen) vs wire every poster now.** *Decided: four in v1 (D-6).* Wiring every poster (AR receipt, AP payment, cash, payroll, depreciation, year-end) is more complete but multiplies the touch-points and the regression surface in one release. The four (manual journal, invoice, bill, adjustment) cover the dominant departmental-P&L tagging need; the rest are additive per-poster as each is needed (OQ-CC-02, owner-confirmable).

## Open items (OQ-CC — architect defaults adopted; the two LOAD-BEARING ones flagged for owner confirmation; none blocks the build)

- **OQ-CC-01 (LOAD-BEARING) — fixed slots vs child table:** adopted **fixed slot-columns, 4 user-defined slots** (D-3). Owner-confirmable; the design is built to it. Settled unless the business needs >4 simultaneous dimensions.
- **OQ-CC-02 (LOAD-BEARING) — v1 poster-wiring set:** adopted **manual journal (per-line) + sales invoice + supplier bill + stock adjustment (header)** (D-6). Owner may reprioritise the set (e.g. AP payment over stock adjustment) — a one-poster swap, additive. Settled on the recommended set.
- **OQ-CC-03 — value code:** adopted **user-supplied, unique per dimension per company** (no `code_sequence`, the CoA precedent, D-2). Add a `code_sequence` kind only if auto-numbering is wanted.
- **OQ-CC-04 — sales-invoice granularity:** adopted **header-level default** (D-6); per-`sales_invoice_lines` dimension deferred (additive).
- **OQ-CC-05 — mandatory coverage:** adopted **wired posters only** (D-6); un-wired posters post untagged regardless. Documented limitation.
- **OQ-CC-06 — module name:** adopted **`com.erp.modules.costing`** (D-1).
- **OQ-CC-07 — domain event:** adopted **no outbox event** (the framework changes no balance; Budgeting/Projects read the master via the DTO contract, D-8). Settled.
- **OQ-CC-08 — Project dimension:** adopted **reserve slot 3, built by Projects** (D-2/D-3). Settled.
- **Balance-sheet control-leg tagging (D-6 sub-decision):** adopted **only P&L-relevant legs inherit** in v1. Owner-confirmable if Budgeting needs BS dimensions.

---

## Summary

ADR-0025 designs the **Cost-centre / accounting-dimension framework** in a new **`com.erp.modules.costing`** module — the build-first enabler **Budgeting** (§3.14) and **Projects** (§3.13) consume. It adds **two master tables** (`dimensions` — the per-company dimension *types*, seeded with built-in **Cost Centre** + **Department** in two of four bounded slots; `dimension_values` — the per-company members / cost centres / departments, with a self-FK `parent_id` roll-up and `is_active` tagging gate, no-hard-delete-if-posted) and the **load-bearing tag seam**: **four additive nullable `*_value_id` slot-columns on `journal_lines`** alongside the pre-existing `branch_id` analysis tag (the ADR-0013 D-7 precedent extended), with the matching **additive `LineDraft` extension** (a 5-arg convenience constructor keeps every un-wired poster's call site unchanged). `GLPostingService.post` gains a **dimension-validation step inside its existing validate** (active value + correct slot + same company; mandatory-slot check, off by default) — balanced-or-rejected extends to dimension validity. **Four source documents inherit a dimension default at post time by copy** (manual journal per-line; sales invoice / supplier bill / stock adjustment header-level) onto the P&L-relevant journal lines. The **dimension-sliced trial balance** (filter + GROUP BY a dimension value, parent roll-up, partial-indexed) is the v1 read; the **Budgeting/Projects contract** (`resolveValue`, `listActiveValues`, `actualsByAccountValuePeriod`) is documented up front so those modules consume the seam, not the tables.

**The defining guarantee (BR-CC-07, NFR-CC-01):** the framework **introduces no GL account, no `gl_config` key, no posting, no `JournalSourceType`, no movement type** — it **only tags** lines a posting already produces. With dimensions un-configured every existing posting is **byte-for-byte unchanged** (test-pinned). **No module cycle (D-7):** the two cross-module edges (`costing → gl` no-delete count; `gl → costing` post-validation) are *table-level read projections* (the `ScopeGuard` pattern), not service calls; the DB FKs are scalar-id, no cross-module JPA association.

**Additive on frozen V1–V19**, in the reserved **`V27`–`V29`** range (V20–V26 reserved for in-flight modules): `V27` (the two masters + the four `journal_lines` slot-columns + perms + the #12-safe per-company built-in-dimension seed), `V28` (the four document-default ALTERs), `V29` reserved for the Projects slot-3 indexes. **#12-safe** (the only per-company CROSS-JOIN seed-uid — the two built-in dimensions — uses the md5-bounded `'DIM'||lpad(company_id,6)||substr(md5(slot),1,12)` form; no dimension *values* are seeded). **`ScopeGuard`** gains `dimension`/`dimensionvalue` cases. **Perms** `COSTING.VIEW`/`COSTING.MANAGE`/`COSTING.TAG`. **Cross-module touch list:** (1) **gl** — the `LineDraft` extension + the four `journal_lines` slot-columns + the post-time dimension validation; (2) **sales** — the sales-invoice header default + the `SalesPostingHandler` stamp (extend `InvoicePostingTotalsDto`); (3) **purchases** — the supplier-bill header default + the `postMatchedBillToGl` stamp; (4) **stock** — the adjustment default + the `InventoryGlPoster` adjustment-leg stamp; (5) **platform** — the two `ScopeGuard` cases. **Ready for build** on the recommended defaults; the two LOAD-BEARING OQs (fixed-slots, poster-wiring set) carry architect recommendations the design is built to and are owner-confirmable without reshaping the model.
