# Multi-device responsive review — run 2026-06-28 (SIM-BL-002)

> Real Tembo Group staff don't all sit at a 1440px desktop. Field sales work a phone between
> dukas, a storekeeper carries a handheld around the warehouse, a GM checks figures on a tablet
> between meetings, the counter cashier rings up on a phone. A screen that passes every functional
> click can still be **unusable** at 390px — the action button is off-screen, the amount column is
> clipped, the form table overflows. Automation drives the clicks regardless of layout, so the gate
> here is a **visual review by the end-user persona**, not a pass/fail assertion.
>
> This is the first systematic pass of **SIM-BL-002** (multi-device coverage, queued in
> [BACKLOG.md](../BACKLOG.md)). It covers the **mobile-critical personas** — field sales, counter
> sales, warehouse, finance, and the GM — at two viewports captured by the `DEVICE` harness:
> **phone = 390px** and **tablet = 834px**. Companion docs: [SIM-RUN-REPORT.md](../SIM-RUN-REPORT.md)
> (the run), [ISSUES-REGISTER.md](../ISSUES-REGISTER.md) (the RBAC defects this run also closed).

---

## Headline

The **tablet (834px) is broadly good** across every persona and screen reviewed — full columns,
visible action buttons, clear branch identity, usable forms. The **phone (390px) has one pervasive
pattern defect**:

- **List and form tables overflow horizontally on the phone, clipping the rightmost columns and —
  critically — the per-row action buttons** (Open / Edit / Ledger / Adjust). This is the *same
  defect wearing six faces* across Sales Orders, Customers, Products, Purchase Orders, the supplier
  bill, and the manual journal. Where the only way into a record is that off-screen button, the
  phone user is **stranded on the list**. (Narrow tables like Stock On-Hand fit fine — it's the
  *wide* transaction tables that need a small-screen strategy.)

> **Correction (verified after the review).** The reviewer also reported a "developer error overlay
> covering the login form on mobile" (`TS2339: screenLabel does not exist on RoleEditComponent`) as a
> blocker. **This is NOT a shipped defect — it is a transient dev-server artifact.** `screenLabel` *is*
> defined (`role-edit.component.ts:201`) and used correctly (`.html:166`); the QA **production `ng build`
> passed** and the **web unit suite is 903/903 green**, so the committed code compiles. The overlay was
> Angular's dev-mode error overlay flashing during the grant-time-validator's mid-edit recompile, which
> happened to land in Hamisi's *concurrent-login* screenshot (he logged in cleanly on a serial re-run).
> Discounted as a harness/dev-server timing artifact, not a responsive or product defect.

Everything else reported is cosmetic ("API: UP" wraps, branch pill loses its label) or is a
non-responsive bug that merely showed up in the device run (a wrong-branch default, a missing
dashboard) — separated out below and **not** counted as responsive breakage.

---

## Findings

| Persona | Screen | Device | Severity | Issue |
|---|---|---|---|---|
| Hamisi Ngassa | Login page | mobile | Can't do my job | Red dev error overlay (`TS2339: Property screenLabel does not exist on type RoleEditComponent`) covers the login form — cannot get past it to sign in. |
| Hamisi Ngassa | Sales Orders list | mobile | Can't do my job | Table clipped at right edge — TOTAL header shows only "TO", amount invisible, and the per-row **Open** button is off-screen / un-tappable. No way into any order on the phone. |
| Sabina Aloyce | Sales Orders list | mobile | Can't do my job | Total clipped (shows "TZS" with no amount); **no Open buttons at all** on phone rows. Stranded on the list. |
| Bakari Mbaga | Sales Orders list | mobile | Slows me down | Order total cut off at the edge ("TO" header, "TZS" with nothing after); no **Open** button in the mobile table. |
| Bakari Mbaga | Purchase Orders list | mobile | Slows me down | **TOTAL column entirely absent** on mobile (only ORDER #, SUPPLIER, STATUS, CURRENCY); **Open** button missing too. List of orders with no values and no way in. |
| Amina Mwanga | Enter Supplier Bill — Bill Lines | mobile | Can't do my job | Lines table overflows; UNIT COST, LINE NET, PO LINE clipped — only DESCRIPTION + BILLED QTY visible, no scroll affordance. Cannot verify a bill against paper before posting. |
| Amina Mwanga | Post Manual Journal — Lines | mobile | Slows me down | Lines table overflows; CREDIT and MEMO columns hidden — only ACCOUNT + part of DEBIT visible. Can only half-see a double-entry posting. |
| Sabina Aloyce | Customers list | mobile | Slows me down | KIND clipped ("CREDIT_A…"); STATUS column and per-row **Edit** button off-screen — can't edit a customer without sideways scrolling, fiddly one-handed at the counter. |
| Hamisi Ngassa | Customers list | mobile | Slows me down | KIND clipped; STATUS column absent on mobile; Edit/Open action off-screen with no scroll indicator — can't tell account standing without opening each record. |
| Sabina Aloyce | Products list | mobile | Slows me down | Table stops at BASE UNIT; SELLABLE, STOCKABLE, STATUS and **Edit** all off-screen. Read-only browsing only on phone. |
| Saidi Karume | Stock On-Hand — row actions | mobile | Slows me down | FLAGS column and the row-level **Ledger** / **Adjust** buttons absent on phone (present on tablet) — no way to check movement history or file an adjustment from the phone. |
| Bakari Mbaga | Purchase Orders list | tablet | Slows me down | Totals render with no thousands separators — "1500000" instead of "1,500,000"; hard to read a TZS figure in the millions at a glance. |

### Reported in the device run but **not** responsive defects (tracked elsewhere)

| Persona | Screen | Device | Why it's out of scope here |
|---|---|---|---|
| Saidi Karume | New Stock Count — Branch field | mobile + tablet | Branch dropdown defaults to a *different company's* branch ("Alpha IIJBRT HQ") while the header shows "Dar es Salaam HQ" — affects **both** devices, so it's a default/data-scope bug, not responsive. Flag to backend/UX as its own Issue. |
| Bakari Mbaga | Group Dashboard | mobile + tablet | Shows the blank "workspace ready" placeholder on both — the dashboard isn't built/wired, not a layout failure. Out of scope for responsive; it's a missing feature. |
| Saidi Karume | Goods Receipt | mobile + tablet | Screenshot captured the home screen, not the GR form — a **harness coverage gap**, so GR is *unverified* on handhelds (not a confirmed bug). Re-capture next pass. |

---

## What works

The tablet earns genuine credit — this is responsive design that was actually done, not a desktop
page squeezed:

- **POS / counter checkout is genuinely responsive on phone** (Sabina). The form stacks to a single
  column at 390px with every field reachable (Session, Customer, Agent, Currency, Line Items,
  Payment), the branch dropdown reads "Dar es Salaam HQ" in full, and the full-width **Complete
  Sale** button is easy to tap one-handed. On tablet it goes two-column and uses the space well.
  This is the baseline every other screen should meet — and it confirms the framework/header
  collapse already works.
- **Record Receipt (finance) stacks cleanly on phone** (Amina) — fields and the Record/Cancel
  buttons all reachable one-handed.
- **The tablet across the board** — Sales Orders, Customers, Products, Purchase Orders all show
  their full column set *and* per-row action buttons at 834px; branch identity is clear
  (BRANCH label + name + "HQ-DAR" code). Sabina and Saidi both called the tablet "well served" /
  "mostly usable."
- The **manual-journal running Debits/Credits/Difference summary** stays visible below the (overflowing)
  table on phone — a partial mitigation that kept Amina oriented even when columns were clipped.

So the problem is **not** "the app isn't responsive." It's that **data tables and form-line tables
don't yet adopt a small-screen strategy**, and one **login-time compile error** slipped into the
mobile build.

---

## Recommended UX fixes (frontend-engineer)

Only real breakage is listed. Cosmetic items that personas explicitly coped with (the "API: UP"
two-line wrap, the branch pill losing its "BRANCH" micro-label and "HQ-DAR" code on phone, the
Stock On-Hand tab row wrapping, the "use the menu on the left" copy that points at a hamburger on
phone) are **noted but not actioned** — fold them into a single low-priority "mobile header & copy
polish" ticket if desired; none blocks work.

**Blocker (Can't do my job)**
- **Fix the login-page compile error on the mobile build** — `TS2339: Property screenLabel does not exist on type RoleEditComponent` renders a dev error overlay over the login form; no one can sign in. (Likely a build/lazy-chunk error surfacing via the dev overlay — verify it's gone from a production build too.)
- **Give list tables a small-screen strategy so the per-row action button is never off-screen** — Sales Orders, Purchase Orders, Customers, Products all hide their **Open/Edit** action on phone. Pin the primary action (sticky right column, a card layout, or a row "kebab"/tap-the-row-to-open) so a phone user can always enter a record.
- **Make the supplier-bill Bill Lines table readable on phone** — UNIT COST / LINE NET / PO LINE must be reachable (responsive columns or a per-line card) so a bill can be verified against paper before posting; never post a half-visible bill.

**Slows me down**
- **Surface the order TOTAL on phone for Sales Orders and Purchase Orders** — prioritise amount into the visible column set (it's clipped on Sales Orders and entirely dropped on Purchase Orders at 390px).
- **Show STATUS on the mobile Customers (and Products) list** — it's dropped on phone, forcing a tap into each record just to read account standing / active state.
- **Make the manual-journal lines show both DEBIT and CREDIT on phone** — a double-entry form must show both sides per line; CREDIT and MEMO are currently off-screen.
- **Restore the Stock On-Hand row actions (Ledger, Adjust) on phone** — they vanish at 390px, cutting off movement-history checks and adjustments from a warehouse handheld.
- **Add an explicit horizontal-scroll affordance** wherever a table must still scroll sideways — every persona who hit clipping noted there was *no hint* the table scrolled.
- **Add thousands separators to Purchase Order totals** (tablet, and check everywhere) — "1500000" should read "1,500,000".

**Out of scope for frontend responsive work (route to the right owner)**
- New Stock Count branch default points at the wrong company's branch on both devices — backend/UX default bug, file separately.
- Group Dashboard is a blank placeholder on both devices — missing feature, not a layout issue.

---

## Method + limits

- **Capture:** the `DEVICE` harness primitive (`e2e/sim/sim-lib.js` sets the viewport + touch/mobile
  UA; `operate.js` writes one **full-page screenshot per screen**). Two profiles this pass —
  **mobile 390px** and **tablet 834px**. Desktop/laptop not re-reviewed (assumed-good baseline).
- **Reviewer:** the **end-user persona** judged each screenshot for layout breakage (overflow,
  off-screen actions, clipped columns, cramped forms, tap targets). Automation clicks regardless of
  layout, so a human-eyed visual pass is the gate — a functional-only run would have reported all of
  these screens as "passing."
- **Personas covered:** Hamisi Ngassa (field/route sales), Sabina Aloyce (counter sales/POS), Saidi
  Karume (warehouse/stores), Bakari Mbaga (GM), Amina Mwanga (accountant) — the mobile-critical
  seats. Other personas (period-close/GL/HR) are desktop-bound and deferred.
- **Limits / not done yet:**
  - **No axe at mobile/tablet yet.** These are full-page screenshots only — the **axe a11y gate at
    mobile/tablet viewports** (touch-target size, reflow, focus order) is the **next SIM-BL-002
    step**, not run here.
  - **One screen unverified:** Goods Receipt — the harness captured the home screen instead of the
    GR form on both devices, so GR's handheld usability is *unverified* (re-capture next pass). It's
    Saidi's most-used screen, so prioritise it.
  - **Harness login flake at high concurrency** — under parallel persona logins the harness
    occasionally races the auth/session step; reviews were re-captured serially where that occurred.
    Noted for the coverage runner (lower concurrency or a retry on the login step).
  - Representative **real-device descriptors** (a low-end Android, an iPad) per the backlog item were
    not used — raw viewports only.
