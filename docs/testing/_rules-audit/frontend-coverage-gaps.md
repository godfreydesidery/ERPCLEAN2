# Frontend Coverage Audit — backend capabilities with no/partial UI

> Generated 2026-06-13 by a verified 113-controller sweep (each suspected gap adversarially
> re-checked against routes, services, components, and nav). Companion to the rules-compliance work.

## Summary
| Coverage | Count | Meaning |
|---|---|---|
| FULL | 69 | own route + component + service |
| EMBEDDED | 10 | no own screen but driven inside another feature's screen (covered) |
| PARTIAL | 10 | core capability usable; some endpoints unreachable from UI |
| NONE | 24 | API-only — no route, component, or service |

## 🔴 HIGH — core user-facing capability, NO UI at all
| Capability | Base path | Missing UI |
|---|---|---|
| POS — Sale | `/api/v1/pos/sales` | checkout: ring items, tender, change, receipt, refund |
| POS — Session | `/api/v1/pos/sessions` | open/close a till per shift; float; cash count; over/short; Z-report |
| POS — Till | `/api/v1/pos/tills` | register/station setup per branch (manager) |
| Stock Transfer | `/api/v1/stock-transfers` | inter-location transfer DRAFT→DISPATCHED→RECEIVED + instant |
| Stock Count | `/api/v1/stock-counts` | physical/cycle count → variance → GL post |
| Purchase Requisition | `/api/v1/purchase-requisitions` | raise → approve → convert to RFQ/PO |
| RFQ | `/api/v1/rfqs` | request-for-quote → supplier quotes → award→auto-PO |
| Standing Order | `/api/v1/standing-orders` | recurring sales orders (pause/resume/trigger) |
| BOM | `/api/v1/boms` | bill-of-materials authoring (manufacturing depends on it) |
| CRM Activity | `/api/v1/crm/activities` | log calls/meetings/tasks on leads & opportunities |
| HR Contract | `/api/v1/hr/contracts` | employment contracts |
| HR Statutory | `/api/v1/hr/statutory` | statutory deductions/filings |

**POS note:** Till + Session + Sale are three layers of ONE capability. "POS management" = all three.
A till screen alone can't sell; a sale screen alone can't record/reconcile cash. Build them together.

## 🟡 MED — no UI (build from scratch)
BlanketOrder `/api/v1/blanket-orders`, PricingRule `/api/v1/pricing-rules`,
OtherParty `/api/v1/other-parties`, PurchaseReturn `/api/v1/purchase-returns`,
PurchaseSettings `/api/v1/purchase-settings`, SupplierQuote `/api/v1/supplier-quotes`,
LandedCost `/api/v1/landed-costs`, StockBatch `/api/v1/stock-batches`,
StockLocation `/api/v1/stock-locations`, StockSerial `/api/v1/stock-serials`,
YearEndClose `/api/v1/gl/periods/fiscal-years`, HrDepartment `/api/v1/hr/departments`.

## 🟡 PARTIAL — capability works, only secondary endpoints unreachable (wire-up, not build)
- ArReceipt — record works; receipt list + get-by-uid unreachable
- ApPayment — payment-run works; paySingle + list + get unreachable
- ApDebitNote — partial
- ApStatement — balance + ageing work; reconciliation unreachable
- Stock — on-hand/movements/adjust/opening work; on-hand by-location + by-product reads unreachable
- CashTransfer — record works; transfer list + get-by-uid unreachable
- ArStatement — statement works; standalone /ar/ageing + /ar/balance unreachable
- CashDirectEntry — partial
- BiDashboard — partial

## 🟢 LOW
Read-only / admin endpoints, minor.

## Suggested build order (HIGH first)
1. **POS management** (Till + Session + Sale) — owner-requested
2. **Stock Transfer** + **Stock Count** (inventory operations)
3. **Purchase Requisition** + **RFQ** + **SupplierQuote** (procure-to-pay front door)
4. **BOM** (unblocks manufacturing)
5. **Standing Order**, **CRM Activity**, **HR Contract/Statutory**
6. MED no-UI batch, then PARTIAL wire-ups (cheap, high polish value)
