# Client pending issues — triage, 2026-08-11

Investigation of the six issues reported by the client (J T, WhatsApp, 2026-08-11 20:39, in Swahili).
Read-only code investigation against `develop @ 5c84b396`, by 11 parallel root-cause agents each
checked by two independent adversarial verifiers (19 verdicts returned; four substantive refutations,
all folded into the positions below).

**Nothing here was verified against a live database or a running build.** No test was executed and no
deploy was inspected as part of the investigation. Where a conclusion depends on the client's actual
data or deployed SHA, that is stated explicitly.

---

## Verdict at a glance

| # | Client's report | What is actually wrong | Confirmed? | Sev | Fix size | Migration / seed? |
|---|---|---|---|---|---|---|
| 4a | "Add item" does nothing | `.trim()` called on a **number**-typed signal → `TypeError` thrown before any validation message can be set. Screen 100% broken since it shipped. | **Yes** — read first-hand | Critical | S | No |
| 4b | "Receive into stock" does nothing | Cascade of 4a: button is `[disabled]` while no line is staged, and a disabled primary button still paints blue. No defect in the submit path itself. | Partly — mechanism confirmed, *cause* contested | High | S | No |
| 3 | 1 OUTER deducts a whole CARTON (12×) | **Not** a conversion bug. Three candidate causes, all data/write-path; two are real code defects that silently lose a pack definition. | **Contested** — needs one DB query to settle | Critical | M | No |
| 6 | Stock Valuation cannot export or print | Feature was never built for this one screen; its three sibling reports all have it. | **Yes** | Medium | M | No |
| 1 | Cashier cannot resume a shift | Contested: either a missing `POS.SESSION.VIEW` grant on a hand-made role, or a stale OrbixPOS binary. Client dead-ends either way — no fallback, generic toast. | **Contested** | High | S–M | No |
| 2 | X/Z hidden from cashier; wants manager password popup | Working as designed (permission-denied rows are *hidden*, not dimmed). The manager-override path does not exist server-side. Two of the client's three sub-claims are false. | **Yes** | Medium | M–L | No, if an existing code is reused |
| 4c | No GRN print | Already exists at HEAD with supplier + item + qty + line value + total. Almost certainly deployment lag. Real residual gap: the *on-screen* detail lacks supplier and total. | **Yes** | Low | S | No |
| — | *(not reported)* GL out of balance by 99.00 | Real finance defect: moving-average engine retro-values pre-existing uncosted stock, creating inventory value with no GL counter-leg. Plus a second, independent double-post on stock counts. | **Yes** — 3 verifiers upheld | High | M | No |

---

## Issue 4a — "Add item" does nothing  ✅ FIXED

**Client:** *"This 'add item' option does not work. You should be able to add items, and after adding
items you click the button to receive stock into the system — which also does not work."*

### Root cause (confirmed first-hand)

[direct-goods-receipt.component.html:184](web/src/app/features/admin/purchases/direct-goods-receipt.component.html#L184)
binds Quantity and Unit cost as `type="number"` with `[ngModel]`:

```html
<input id="drQty" type="number" [ngModel]="newLineQty()" (ngModelChange)="newLineQty.set($event)" />
```

Angular's `NumberValueAccessor` claims the selector `input[type=number][ngModel]` and `parseFloat`s
the DOM value, so `(ngModelChange)` delivers a **number**. But the signals are declared
`signal('')` and typed `string`
([direct-goods-receipt.component.ts:92](web/src/app/features/admin/purchases/direct-goods-receipt.component.ts#L92)),
and `addLine()` reads them as strings:

```ts
if (!this.newLineQty().trim() || !Number.isFinite(qty) || qty <= 0) {   // line 226
```

`(50).trim` is `undefined` → `TypeError: this.newLineQty(...).trim is not a function`, thrown
**before** any `lineFormError` is set. `$event` is `any` on `ngModelChange`, so `ng build` and strict
mode never flag it. The throw escapes the `(ngSubmit)` handler into Angular's default `ErrorHandler`
(console only — `app.config.ts:15` registers no custom handler), so the button visibly does nothing
and the user is told nothing.

Four call sites are affected: lines 226, 230, 249, 250.

**This is a regression of a bug already fixed in this repo** — commit `8791f4b6` (2026-06-07),
*"fix(web): number-typed input signals crash on .trim() (stock + purchases)"*. The sibling LPO screen
carries the fix and a comment describing this exact failure mode
([purchase-order-detail.component.ts:424](web/src/app/features/admin/purchases/purchase-order-detail.component.ts#L424)).
This screen shipped later (`16c17f60`, 2026-08-08) without adopting it.

### Why no test caught it

Every spec pokes the signals with **string literals** (`comp.newLineQty.set('20')`) and calls
`addLine()` directly — the one path `NumberValueAccessor` never touches
([direct-goods-receipt.component.spec.ts:97](web/src/app/features/admin/purchases/direct-goods-receipt.component.spec.ts#L97)).
All six specs pass against a screen that is completely broken in the browser.

---

## Issue 4b — "Receive into stock" does nothing  ✅ FIXED

**Confirmed mechanism, contested cause.** The button is bound
`[disabled]="submitting() || stagedLines().length === 0"`, and a disabled `<button>` fires no click
event, so `submit()`'s own guard — which *would* say *"Add at least one item to receive."* — is
unreachable. The user gets total silence.

A verifier correctly pushed back on two points, and the corrected position is:

- The blocking mechanism is the **native `disabled` DOM property**, not Bootstrap's
  `pointer-events: none`. Fixing CSS would change nothing.
- That the empty list *caused this particular report* is an **inference**, and it contradicts the
  client's own words ("after adding items you click the button"). It is well explained as a cascade
  of 4a — no line can ever be staged — but that is not proven.

Separately real and independently reproducible: `--bs-btn-disabled-bg` is never overridden
(`grep -rn "btn-disabled" web/src/` → zero hits), so a disabled `.btn-primary` paints Bootstrap's
`#0d6efd` at `opacity:.65` rather than a muted tone — it reads as a live button.

### Deferred — needs your approval

`PURCHASE.RECEIVE.DIRECT` is granted to exactly one role, **STOREKEEPER**
([R__seed_permissions.sql:443](backend/src/main/resources/db/migration/R__seed_permissions.sql#L443)),
and STOREKEEPER is **not** granted `SUPPLIER.VIEW` (granted only at 512/659/684/716/810). So
`GET /api/v1/suppliers` 403s for the one role that owns this screen, and the component swallows it
to an empty dropdown
([direct-goods-receipt.component.ts:125](web/src/app/features/admin/purchases/direct-goods-receipt.component.ts#L125)).

Fixing this means adding `('STOREKEEPER','SUPPLIER.VIEW')` to `R__seed_permissions.sql` — a
repeatable-migration edit, so **deferred** per your standing rule. I have *not* worked around it in
code; suppressing a permission gate client-side would paper over an RBAC hole.

What I did do: the supplier search now distinguishes a 403 from an empty result and says so, instead
of showing a dropdown that silently never returns anything.

---

## Issue 3 — bulk pack deducts 12× too much  ⚠️ CONTESTED — one query settles it

**Client:** *"I set a CARTON of 48pcs and also an OUTER of 4pcs… selling 1 outer deducts a full
carton; selling 12 outers deducts 12 cartons."*

### What was ruled out (verified independently, twice)

The conversion arithmetic is correct everywhere:

- The POS posts `{productId, unitId, quantity}` with **no** client-side conversion
  ([cart_controller.dart:152](pos_app/lib/state/cart_controller.dart#L152)); packs are matched by
  unit uid, never by list index ([catalog_cache.dart:89](pos_app/lib/state/catalog_cache.dart#L89)).
- `computeQtyInBase` tests the base unit first, then matches the pack by
  `bp.getUnit().getId().equals(unit.getId())` and multiplies **once**
  ([SalesInvoiceServiceImpl.java:1165](backend/src/main/java/com/erp/modules/sales/service/SalesInvoiceServiceImpl.java#L1165)).
- `uq_product_bulk_pack_unit UNIQUE (product_id, unit_id)` makes that lookup deterministic — the
  CARTON row cannot be returned for an OUTER line.
- The stock move deducts `line.qtyInBase()` verbatim, no second conversion.

There is a repo test pinning exactly this contract
([pack_units_test.dart:185](pos_app/test/pack_units_test.dart#L185)).
**Do not "fix" the POS by converting to base units client-side — that would introduce a real
double-conversion.**

### The three candidate causes

The reported ratio is exactly `48 / 4` = cartonFactor / outerFactor. Every surviving explanation is
that **the till never had an OUTER unit to sell**, so it rang CARTON instead:

1. **The OUTER pack was never saved.** The Product Master *edit* screen re-POSTs already-persisted
   packs; the first one 409s on the unique constraint and the error branch calls `resolve()` without
   advancing, so **every row below it is silently never created**
   ([product-master.component.ts:1024–1053](web/src/app/features/admin/products/product-master.component.ts#L1024)).
   The screen keeps rendering the local signal, so it still *displays* "1 OUTER = 4 Pieces" that the
   server never stored. This reproduces the client's screenshot and the 12× exactly. **Real defect — fixed.**
2. **A barcode carries the wrong unit.** `product_barcodes` has a uom and the till honours it
   ([supermarket_register.dart:203](pos_app/lib/features/register/supermarket_register.dart#L203)) —
   a label registered against CARTON but physically stuck on an OUTER makes one scan deduct 48.
   Compounding it, the wizard **discards** `uomUid` and `barcodeType` on load
   ([product-master.component.ts:483](web/src/app/features/admin/products/product-master.component.ts#L483)),
   so it cannot round-trip the field that drives POS unit selection. **Real defect — fixed.**
3. **The stored factor really is 48.** Then it is a data-entry error — and note the operator
   currently *cannot correct it*: there is no update endpoint, only add/delete, and re-adding returns
   an opaque *"A record with the same unique identifier already exists."*

### The query that settles it

```sql
SELECT u.code, bp.factor_to_base
FROM   product_bulk_packs bp
JOIN   units_of_measure u ON u.id = bp.unit_id
JOIN   products p         ON p.id = bp.product_id
WHERE  p.code = 'PROD-0001';
```

- **No OUTER row** → cause 1 (fixed below; the operator must re-enter the pack).
- **OUTER = 48** → cause 3, data. Delete and re-add from *Product Detail* (the safe screen).
- **OUTER = 4 and it still deducts 48** → check `product_barcodes.uom` for that product (cause 2).

And decisively, for one disputed sale:
`SELECT quantity, qty_in_base FROM sales_invoice_lines WHERE ...` — the ratio **is** the factor the
server applied.

### Data already corrupted

Any sale posted against the wrong unit has a wrong `qty_in_base` and a wrong `SALE_ISSUE` movement.
Posting tables are append-only here, so correction is a **new stock adjustment** for the net
difference, never an `UPDATE`. Quantify before proposing anything. **Your call.**

---

## Issue 6 — Stock Valuation cannot export or print  ✅ FIXED

Confirmed and complete on both tiers: the component renders only a Refresh button, its service has
no blob call, and `StockValuationController` maps only `/report` and `/opening` — it does not even
inject `TabularExporter`, unlike all three sibling report controllers. The client's three red X marks
are accurate: nothing is broken, the feature was simply never built for this one screen.

Reuses `INVENTORY.VALUATION.VIEW` + `REPORT.EXPORT`, both already seeded — no migration, no seed edit.

**On "print":** there is no `window.print()` and no `@media print` stylesheet anywhere in `web/src`.
Across this app, *Export PDF* **is** the print path for reports, and it is what all three siblings
offer. If the client specifically wants one-click browser printing, that is a new app-wide pattern
and a separate decision.

---

## Issue 1 — cashier cannot resume a shift  ⚠️ CONTESTED

Two agents reached incompatible conclusions and the verifiers split. Both are plausible; they need
one field check to separate.

**Reading A — missing grant.** `resumeShift` does exactly one thing: `GET /pos/sessions/uid/{uid}`,
gated `POS.SESSION.VIEW`
([PosSessionController.java:60](backend/src/main/java/com/erp/api/PosSessionController.java#L60)).
The yellow *"We couldn't check whether you already have a shift open"* banner in the screenshot is
emitted by exactly one code path — the login-time lookup gated on the **same** code. So the banner is
direct evidence that this cashier is refused on `POS.SESSION.VIEW`. Close shift keeps working because
it is a different code — which is precisely the client's complaint. The seeded CASHIER bundle *does*
include `POS.SESSION.VIEW`, so the affected user is almost certainly on a **hand-made role**.

**Reading B — stale till binary.** The resume affordance did not exist before commit `61d062bb`
(2026-08-01): an occupied till was literally untappable (`onTap: occupied ? null : …`). That half of
the fix ships in the separately-distributed **Flutter binary**, while the backend half is already
live. A till installed before August has no working resume no matter what the server does.

**Settle it by:** calling `GET /api/v1/auth/me` as the affected cashier and checking whether
`POS.SESSION.VIEW` is in `permissions`; and reading the OrbixPOS build version off the failing till.

Regardless of which is true, the client-side dead-end is real and is fixed below: the Resume button
was rendered ungated with no fallback, and a refusal produced only *"You do not have permission for
this action."*

Also confirmed, and worth knowing: **logout does not close the session** — it revokes the refresh
token only, so the shift stays OPEN server-side. Nothing about logout or a power cut blocks resume,
and `pos.pending_sale` / `pos.receipt_journal` survive a hard kill.

**Related trap:** a session left open more than **36 hours** (`erp.pos.session.max-open-age`, default
`PT36H`, no override in any `application*.yml`) resumes fine but every sale returns HTTP 410. A shift
abandoned over a weekend produces "I cannot use my shift" with a completely different cause.

---

## Issue 2 — X/Z reports and the manager password popup  ⚠️ TWO OF THREE CLAIMS ARE FALSE

**Why the cashier sees no X-read.** The till drawer builds each row only if the operator holds its
permission ([session_menu.dart:145](pos_app/lib/features/session/session_menu.dart#L145)). This is
deliberate, documented policy — *"Permission-denied actions are HIDDEN, not dimmed"*. The client
revoked the permission, so the row vanished. Working as designed.

The screenshot is also diagnostic: *Reconcile (Z-read)* is still visible, which means this tenant's
cashier holds `POS.SESSION.RECONCILE` but **not** `POS.SESSION.VIEW` — the **inverse** of the shipped
bundle, where RECONCILE is deliberately withheld as a segregation-of-duties control. Worth confirming
that was intended: as configured, the cashier has the GL-posting step but not the read.

**"A manager must close the shift before X can be printed" — false.** The server accepts X-read on
OPEN and on CLOSED; only RECONCILED is refused
([PosSessionServiceImpl.java:887](backend/src/main/java/com/erp/modules/sales/service/PosSessionServiceImpl.java#L887)).

**"After closing, the till goes back to zero" — false as data loss.** Every Z figure is recomputed
from persisted rows on demand; nothing is zeroed. What is lost is the client's *handle* on it:
`endShift()` clears the shift and the drawer addresses every report as `app.shift?.uid`. The web back
office has a matching hole — `pos.service.ts` never calls `GET /z-read` at all, and the session
detail screen unconditionally loads x-read, which 409s on a reconciled session.

**The manager popup does not exist and cannot be faked client-side.** A step-up primitive *is*
shipped and sound (`POST /auth/verify-authority` — bcrypt check, refuses self-approval, throttled,
audited, returns a verdict and deliberately **no token**). But X/Z are `GET`s gated on the caller's
own permission with no channel for an approver, so the till's existing Z-read prompt is **decoration**
— the approval never reaches the server. Relaxing the client alone would turn a hidden row into a row
that 403s.

**Deferred — needs your decision.** The correct shape is the pattern already proven for refunds:
client calls `verify-authority`, then sends `authorisedByUid` on the report call, and the server
**re-verifies** it against the loaded session's company. That is a change to an authorisation gate
and wants an ADR line, plus a decision on which code the approver must prove. Reusing
`POS.SESSION.RECONCILE` needs no seed change; a dedicated code would need one.

Also absent today: **no audit trail that an X or Z was ever printed** — `xRead()`/`zRead()` call
`audit.record()` zero times.

---

## Issue 4c — GRN print  ✅ PARTLY FIXED (rest is a redeploy)

**Not reproducible at HEAD.** A *Print PDF* button exists on the goods-receipt detail screen and
`DocumentModelBuilder.buildGoodsReceipt()` emits exactly what the client asked for: supplier
(line 166), per-line qty / unit cost / line value (179–188), and *"Total Received Value"* (192).

Those priced fields landed in `16c17f60` (**2026-08-08**). The last recorded prod deploy is
`621a8cda` (**2026-08-01**). The version before that commit printed the party as the literal string
`"Supplier #" + supplierId` with null prices and an empty totals list — *precisely* the client's
complaint. **Confirm the deployed SHA before writing any code; a redeploy is very likely the whole
fix.**

The one genuine residual gap: the TypeScript `GoodsReceiptDto` was never given the new fields, so the
**on-screen** detail shows no supplier row and no grand total — only the PDF has them. Fixed below.

---

## Not reported, but found: GL out of balance by 99.00

The screenshot shows a *finance-grade alarm* — stock Σ 639.75 vs GL 1300 540.75. **The alarm is
correct to fire; the code behind it is what is wrong.** Three verifiers upheld this.

`doRecomputeOnReceipt` takes its "first receipt" branch whenever every on-hand row still has
`avg_cost IS NULL`, and sets `newTotalValue = newQty × cost` — where `newQty` **includes stock that
was already on hand**. It therefore values previously-uncosted quantity at the new receipt's cost.
But it returns only `receiptQty × cost`, and *that* is the single number posted to the GL. Σ
`on_hand_value` gains `existingQty × receiptCost` that no journal ever records — permanently, with no
error and no log
([InventoryValuationServiceImpl.java:157](backend/src/main/java/com/erp/modules/stock/service/InventoryValuationServiceImpl.java#L157)).

The uncosted-with-quantity state is manufactured by the product's own supported flows: opening
balance posts quantity with *"cost not set here"*, and the **bulk stock import** does the same.

The arithmetic fits the client's four on-screen numbers exactly (*labelled speculation — not verified
against their DB*): 3 units pre-existing, first receipt 10 @ 33.00, second 3 @ 70.25 gives GL 540.75,
stock 639.75, difference 99.00, avg 39.9844 → displays "39.98", and explains why 16 × 39.98 ≠ 639.75.

A **second, independent** defect: `StockCountServiceImpl.post` calls `revalueAdjustment` per variance
line (which posts its own GL journal) **and then** posts a net-variance journal — GL 1300 moves twice
while `on_hand_value` moves once. Its test mocks the valuation service, so nothing sees it.

**Deferred — needs your decision.** The fix forks: dilute the average by treating pre-existing
uncosted stock as genuinely free, **or** keep the reset and post the retro-valuation as its own
journal (DR 1300 / CR Opening Balance Equity). Either way it amends **ADR-0020 D-2**, which encodes
the current behaviour. Repairing the client's existing 99.00 is an operational GL journal, not a
Flyway backfill.

---

## What was fixed in this branch

No new Flyway migration. One repeatable-seed edit, explicitly approved by the owner (below).

| Area | Change |
|---|---|
| Direct GRN | `asStr()` coercion on all four number-signal reads; `addLine()` now catches and reports instead of dying silently; submit no longer disabled-on-empty so its guard message actually reaches the user; supplier/product 403 distinguished from an empty result, with `catchError` moved **inside** `switchMap` so one failure no longer kills the search stream permanently |
| App-wide | `--bs-btn-disabled-bg` / `-border-color`, so a disabled primary button stops painting as Bootstrap blue at 0.65 opacity |
| Product Master | Pre-existing packs and barcodes no longer re-POSTed on edit; a failed row no longer strands every row below it; rows are stamped with their server uid as they save; removing a pack now calls DELETE instead of only mutating local state; the barcode's unit is preserved on load instead of being blanked |
| Products (backend) | `PUT /products/uid/{uid}/bulk-packs/{packUid}` so a mistyped pack size is **correctable in place** (reuses `PRODUCT.MANAGE`); duplicate-unit guard with a message that names the unit and its current size; a pack whose unit is the base unit is now rejected; sibling-factor sanity warnings; `factorToBase` always recorded in the audit trail |
| Stock Valuation | PDF/XLSX/CSV export end to end at `/report/export`, with the GL reconciliation figures carried onto the printout |
| Goods receipt | Supplier row and grand total on the detail screen |
| POS back office | `GET /z-read` wired up; the session detail screen now loads the report matching the session's status instead of always calling x-read, which 409s once reconciled |
| OrbixPOS | Resume survives a refusal — falls back to the till row, explains what to do, and can no longer hang on `busy`; invented figures are suppressed behind a `figuresKnown` flag |

**Seed change (approved):** `('STOREKEEPER','SUPPLIER.VIEW')` in `R__seed_permissions.sql`. `PURCHASE.RECEIVE.DIRECT`
is granted to STOREKEEPER and to no other role, but the screen opens with a supplier picker backed by
`GET /suppliers`. Without this, a non-root storekeeper got a search box that silently returned nothing.
Repeatable convergent seed, additive, read-only lookup — it authorises no spend.

## Deferred, and why

| Item | Blocked on |
|---|---|
| ~~X/Z manager override~~ | **BUILT** — owner decided the design on 2026-08-12. See below. |
| Inventory valuation retro-costing fix | ADR-0020 D-2 amendment — dilute the average vs post a revaluation journal |
| Stock-count GL double-post | Changes GL posting behaviour; belongs with the decision above, not alone |
| Correcting existing wrong stock / the 99.00 | Append-only tables — a new adjustment + journal, and your sign-off |
| `price_lists` one-default index, branch scoping on report queries | Carried over from 2026-08-11; both need migrations |

## Verification — run against a live local stack

Not just unit tests: the API and web app were started and driven for real.

| Suite | Result |
|---|---|
| Web unit + axe (`npm test`) | **178 files, 1575 tests, 0 failures** |
| Backend surefire (`mvn -B test`) | **1129 tests, 0 failures, BUILD SUCCESS** |
| Flutter (`flutter test`) | 121 passed, 5 skipped (live/TLS cases self-skip without a host) |
| Production build (`npm run build`) | succeeded (only pre-existing budgeting warnings) |
| Migration hygiene (`check-migrations.sh`) | rule 1 OK; no versioned migration touched |
| **API smoke, live** | **22 checks, 0 failures** |
| **Browser e2e, live** | **4/4 pass** |

Environment note: `application-dev.yml` points at port 5434, which on this machine belongs to another
project's Postgres, and this repo's compose wants the container name `erp-db`, which is taken by the
**installed OrbixERP distribution** (`C:\OrbixERP`, `orbixerp-api:1.3.1`). That instance was left
running and untouched; an isolated dev database was used on port 5436 with its own volume, and the
port was overridden at runtime rather than by editing the config.

What the live run proved, beyond what any unit test could:

- All 78 migrations plus the repeatable seed applied to an empty database and the app booted.
- `POST /goods-receipts/direct` posted **GRN-0001**, and the detail response carried `supplierName`,
  `currency` and `receiptTotalAmount` = 250,000 (50 × 5,000) — arithmetically checked.
- A pack factor was **corrected in place 48 → 4 and confirmed by re-reading it from the database**.
  That closes the one gap the code review could only check by inspection: `updateBulkPack` relies on
  JPA dirty checking and never calls `save()`, so persistence was previously unproven.
- The new guards fire with human wording: *"Box is already a pack unit for this product (currently 48
  Pieces). Change its size instead of adding it again."*
- Export returned a real PDF (valid `%PDF` signature), XLSX and CSV, and the GL tie-out reaches paper:
  `Stock ledger total: 250,000.00 · GL 1300 Inventory balance: 250,000.00 · Difference: 0.00 ·
  Reconciled to GL: yes`. On clean data the alarm correctly stays silent.
- **In a real browser**: typing 50 and 5000 into the number inputs and pressing *+ Add item* stages the
  line; *Receive into stock* posts and lands on the GRN detail showing Supplier and Total Received
  Value; pressing it with nothing added now shows a reason instead of doing nothing; and the three
  export buttons are present and download a `.pdf`.

The browser regressions are kept as [web/e2e/client-issues-fixes.spec.ts](web/e2e/client-issues-fixes.spec.ts).
They matter because this class of defect is invisible to the unit suite — every existing spec set the
quantity signal to the string `'20'`, and the crash only happens when Angular's `NumberValueAccessor`
puts a real *number* there.

## Issue 2 — manager authorisation for X/Z reports (BUILT 2026-08-12)

Owner decisions taken: **tokenless, re-verified per request**; approver proves **`POS.SESSION.RECONCILE`**
(already seeded — no migration, no seed edit); **every X/Z print is audited**; and **viewing and printing
are one act**, so a single prompt covers both.

```
Cashier without POS.SESSION.VIEW taps "X-read"      ← row is visible now, not hidden
  → POS prompts for manager username + password
  → POST /auth/verify-authority  (bcrypt · self-approval refused · throttled · audited)
        ← verdict + authoriserUid   — NO token, by design
  → POST /pos/sessions/uid/{uid}/x-read/authorised   body { authorisedByUid }
        SERVER re-verifies from scratch: real, active, unlocked user genuinely holding
        POS.SESSION.RECONCILE, resolved in the company of the LOADED session
  → report renders; printing this copy does not ask again
  → the uid is dropped. A second report prompts afresh.
```

Nothing is issued, nothing is stored, nothing survives the request — "clears once viewed" is
structural rather than a rule someone has to remember. The existing `GET`s are untouched for holders.

**Verified live against real Postgres with real non-root users — 15/15.** This is the coverage the
unit suites cannot give, because root bypasses every permission check and mocked collaborators prove
nothing about the real step-up service:

| Attack / case | Result |
|---|---|
| Plain `GET x-read` as a cashier lacking the permission | 403 |
| No approver supplied | 403, friendly |
| Forged approver uid | 403 |
| **Self-approval** (cashier nominates themselves) | 403 |
| Approver who does not hold `POS.SESSION.RECONCILE` | 403 |
| Real manager who holds it | **served** |
| X-read on a CLOSED session | served (correct — "must reconcile first" was never a rule) |
| X-read on a RECONCILED session, *with* a valid approval | **409 — an approval does not override state** |
| Z-read on the reconciled session with approval | served |

Audit rows written for all of it, refusals included (`REFUSED_NO_APPROVAL`,
`REFUSED_NOT_AUTHORISED`, `SERVED` with the approver named). The refusals survive their own
rolled-back transactions, so a rejected attempt cannot erase its trace.

**Before this reaches their tills:** the client's screenshot shows their cashier holding
`POS.SESSION.RECONCILE` but not `POS.SESSION.VIEW` — the inverse of the shipped bundle, where
RECONCILE is withheld as a segregation-of-duties control. Since RECONCILE is now the approver code,
**on their current configuration one cashier could approve another's X-read.** The fix is theirs:
restore `POS.SESSION.VIEW` to the cashier role and revoke `RECONCILE`. Otherwise the control is theatre.

## Known test gaps in this branch

- The valuation export's **printed output** has no backend test (only its two rejection paths do). The
  sibling report ships a flatten test that asserts the actual cells; an equivalent is the obvious follow-up.
- `stock-valuation-report` has no axe spec, while its three siblings do — the new buttons are never scanned.
- `updateBulkPack` is covered only by unit tests against a mocked repository. It relies on JPA dirty
  checking (the established pattern in that class), so persistence is verified by inspection, not by a
  running test. An IT would close it.
- Integration tests (`*IT`, failsafe) were **not** run — they need Docker and `mvn verify`.

## Field checks that would settle the contested items

1. `SELECT u.code, bp.factor_to_base FROM product_bulk_packs …` for PROD-0001 → settles the 12×.
2. `GET /api/v1/auth/me` as the affected cashier → settles the resume issue.
3. OrbixPOS build version on the failing till → settles it the other way.
4. Deployed API SHA → settles the GRN print issue, and guards against the known
   `BRANCH`-override deploy trap.
