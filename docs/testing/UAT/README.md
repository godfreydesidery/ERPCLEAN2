# ERP — UAT Test Plan (workbook)

**`ERP-UAT-Test-Plan.xlsx`** is the comprehensive, ready-to-execute User Acceptance
Test plan for the QA team — **696 manual test cases across 27 module tabs**, covering
every module of the ERP (IAM/RBAC, platform services, masters, sales/POS, procurement,
inventory, manufacturing, GL/AR/AP/cash-bank, tax, FX, reporting/BI, fixed assets,
budgeting, HR & payroll, CRM, projects) plus the cross-cutting UI conventions.

## How it's organised
- **Cover & Instructions** — purpose, QA environment, how to run a case, status/severity legends.
- **Summary** — a live dashboard: per-module Pass/Fail/Blocked/Not-Run counts and Pass %
  via `COUNTIF` formulas that update as testers fill in the Status column. A TOTAL row gives
  the overall pass rate.
- **One tab per module** — each row is an executable case with columns:
  `ID · Area · Test Scenario · Type · Priority · Preconditions · Steps · Test Data ·
  Expected Result · Route · Permission · Actual Result · Status · Severity · Tester ·
  Run Date · Comments`. `Type`, `Priority`, `Status`, `Severity` are dropdowns;
  headers are frozen and auto-filtered.

## How QA uses it
1. Pick a module tab. For each row, set up the **Preconditions**, follow the **Steps** in the app,
   and compare against **Expected Result**.
2. Record **Pass / Fail / Blocked** in **Status**. On a fail, describe what happened in
   **Actual Result**, set **Severity**, and log it on the team's defect tracker.
3. Use a **non-root** user for Permission/RBAC cases — `rootadmin` bypasses all permission
   checks and will hide RBAC issues.
4. QA environment: the QA stack (durable data — not a clean DB each run).

Some cases intentionally document a **known issue** (the scenario/expected text says so) —
verify the stated current behaviour.

## Provenance & regeneration
Cases were **distilled from** the authoritative, code-verified docs in
[`../test-cases/`](../test-cases/) (endpoints, permission codes, enum values and routes were
checked against the controllers, DTOs, migrations and Angular components).

The workbook is reproducible from `source/`:

```bash
# needs python + openpyxl (pip install openpyxl)
python docs/testing/UAT/source/build_uat.py
```

`source/uat-rows-*.json` hold the raw cases (one file per author group); edit those and
re-run `build_uat.py` to extend or refresh the workbook.
