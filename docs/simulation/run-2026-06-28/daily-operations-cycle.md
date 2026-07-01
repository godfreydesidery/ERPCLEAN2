# Daily-operations cycle — run 2026-06-28

> The business-operations personas driving a **normal business day** end-to-end through the web UI
> (no seeding — every record entered as the real user would). This is the steady-state of the
> find → fix → verify → deploy loop after the device/responsive track was parked.

## The cycle (one connected business day)

| # | Daily operation | Persona (role) | Screen | Result |
|---|---|---|---|---|
| 1 | Register suppliers & product master data | Yusuf Mbwana (Procurement) | products / suppliers | ✅ 5 suppliers, 11 products |
| 2 | Raise a purchase order + line, **place** it | Yusuf Mbwana | purchase-orders | ✅ raised + placed |
| 3 | Price the product (procurement cost) | Yusuf Mbwana | product detail | ✅ |
| 4 | **Receive goods against the PO (GRN)** | Saidi Karume (Storekeeper) | goods-receipts/create | ✅ (F23 fix) |
| 5 | Enter the supplier bill (AP intake) | Amina Mwanga (Accountant) | ap/supplier-bills/enter | ✅ |
| 6 | Post a GL journal | Amina Mwanga | gl/journals/post | ✅ |
| 7 | Release a work order (factory) | Editha Mhagama (Production) | work-orders | ✅ |
| 8 | Register customers + raise a sales order + line | Sabina Aloyce (Sales) | customers / sales-orders | ✅ 5 customers, SO+line |
| 9 | Record a customer receipt (cash & bank) | John Komba (Cashier) | ar/receipts/record | ✅ |

**Real problems (excluding idempotency 409s from re-runs against the durable DB): 0.**

## What this run proved
- **Procure-to-pay** closes end-to-end: PO → goods receipt → supplier bill. The receive step was the
  one that broke — **F23** (storekeeper 403 on the GRN PO picker) — found here, fixed, verified, deployed.
- **Make** works: a production officer releases a work order.
- **Order-to-cash** works: customer → sales order (priced line) → cash receipt.
- Every step run by the **role that actually owns it**, as a NON-root user, through the UI — so a
  permission/read-closure gap shows up as a real block (the way F21/F22/F23 did), not a silent root pass.

## Harness
`DEEP=1 node e2e/sim/operate.js <slug>` per persona (Yusuf first so the GRN has a fresh placed PO).
Idempotency 409s (e.g. re-recording a stock opening balance already posted) are expected against the
durable DB and are not defects.
