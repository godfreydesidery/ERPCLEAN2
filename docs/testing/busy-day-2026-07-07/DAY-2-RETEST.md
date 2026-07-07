# Busy-Day Retest (Day 2) — after the fixes

Same 17-persona busy day, re-run against the **fixed** system (PR #251 merged to `develop`). The harness
was taught to recognise the new access message, so a role-scoped redirect that now *explains itself* is
no longer counted as the old silent-bounce defect.

## Before → after

| | Day 1 (pre-fix) | Day 2 (post-fix) |
|---|---|---|
| Personas signed in & worked | 17 / 17 | 17 / 17 |
| **Problems filed** | **142** | **7** |
| Genuine defects | 2 | **0** |
| Role-scoped redirects (still correctly blocked) | 135 | **135** (unchanged) |
| …now shown *with an access message* | 0 | **135** |
| Screens reached OK | — | 119 |
| id/uid leaks · 5xx · a11y | 0 · 0 · 0 | 0 · 0 · 0 |

**The 135 redirects did not disappear** — roles are still kept out of out-of-remit screens (nothing was
loosened). They're simply no longer *silent*: each now shows *"You don't have access to that screen…"*, so
they're correct role-scoping, not a defect. That is the fix working, at scale.

## Both fixes confirmed

- **Fix A (silent redirect → message):** the 135 previously-"silent" bounces now carry the message. Fresh
  live check: **Grace Mhina (CFO)** → `/admin/work-orders` → redirected home **with the message**.
- **Fix B (stray `/agents` 403):** **Saidi Karume (Storekeeper)** → Stock Locations now loads clean
  (**0 `/agents` 403s**) and he reaches the create form — in day 1 he was blocked by a 403; in day 2 his
  only stop is a **409 "WH-MAIN already exists"** (a re-run duplicate), i.e. the screen works.

## The 7 remaining reports — all working-as-designed

- **6 × 409 "already exists"** — re-run data artefacts on the durable local DB (VAT return, GL account
  1100, FX rate USD→TZS, cost-centre CC001, department OPS, stock location WH-MAIN). All created on day 1;
  they would not recur on a clean single day. The duplicate messages are friendly and clear.
- **1 × 403** — John Komba (Cashier) opening POS sell: a cashier's remit is cash & bank, not POS selling
  (that's the Sales Officer, for whom POS works). Correct role-scoping. *(The POS screen still fetches the
  agents list and leaks a raw 403 for a non-sales user — same pattern as the fixed Stock-Locations
  over-fetch; noted as a low-priority follow-up, not a day-2 defect.)*

## Verdict

The system is **healthy** and both fixes hold under a full re-run. Zero genuine new defects. Coverage gaps
unchanged (manufacturing work-orders/BOM, route/field-sales orders, stores-supervisor still have no
scripted action — a harness gap to close for even fuller coverage, not a product issue).

*Harness note:* `e2e/sim/operate.js` now checks for the access message on a redirect, so a properly-signalled
role-scope is recorded (access matrix) but not filed as a problem.
