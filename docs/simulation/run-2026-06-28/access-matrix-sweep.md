# Access-matrix sweep — run 2026-06-28

> A systematic sweep (harness `SWEEP=1`): **every persona visits every operational screen** (the 15
> launchpad destinations) and records OK / FORBIDDEN / REDIRECTED_HOME. The goal: find any role
> *silently* blocked from a screen its job needs — the class the Goods-Receipt finding exposed, where
> `permission.guard` bounces a gap to home (not /forbidden) so it looks like nothing happened. This
> complements the read-closure validator (which catches a supporting *read* 403'ing an open screen);
> the sweep catches a role that can't reach a *job-critical* screen at all.

## Method
16 staff personas × 15 screens = 240 navigations. Each cell: `OK` (reached), `REDIRECTED_HOME` (the
guard silently bounced the role to home — it lacks the route's permission), `FORBIDDEN` (a 403 / a
/forbidden route). Most `REDIRECTED_HOME` cells are **correct** (an accountant *should not* reach POS,
a storekeeper *should not* post journals) — the signal is a cell that's redirected but **core to that
role's job**.

## Finding (real)
- **Grace Mhina (FINANCE_DIRECTOR / CFO) was redirected from the Dashboard** — her role lacked `BI.VIEW`,
  even though reviewing financial KPIs (Revenue / OpEx / Net Profit / working capital) is *her core
  daily job*. Ironically the dashboard was reachable by procurement (a fuzzy keyword over-grant) but not
  the CFO. **Fixed:** granted `BI.VIEW` + `BI.FINANCE.VIEW` to FINANCE_DIRECTOR; Grace now reaches the
  dashboard (finance KPIs load, no 403). The GM already held `BI.VIEW`.

## Two harness improvements this sweep produced
- **`REDIRECTED_HOME` detector** — navigating to a screen but landing on `/admin/home` is now flagged
  BLOCKED, not a false OK (the class that hid the Goods-Receipt gap).
- **`looksForbidden` tightened** — it no longer treats a *graceful* inline "you don't have permission to
  view X" notice as a block (that's good degradation UX); a real block is a 403 or a /forbidden route.
  (This was false-positiving the CFO's dashboard, which actually loaded fine.)

## Note (not a product bug)
The simulation provisions roles by keyword-matching permission codes, which **over-grants** in places
(e.g. procurement's `order`/`receipt` keywords pull in some sales/AR codes). That's a sim role-provisioning
imprecision, not a product defect — a real deployment composes roles deliberately. The sweep is the tool
that surfaces both *under*-grants (the CFO dashboard) and over-grants.

## Not covered
HR/payroll screens aren't in the 15-screen operational set, so HR personas (Neema) couldn't be assessed
here — add HR screens to a future sweep.
