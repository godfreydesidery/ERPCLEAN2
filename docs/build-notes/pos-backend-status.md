# POS backend — status & remaining backlog

**Updated:** 2026-06-20. **Sources:** [ADR-0042](../decisions/0042-pos-api-gap-closure.md) (transaction-integrity closure),
[ADR-0044](../decisions/0044-pos-supermarket-readiness.md) (supermarket gaps D-1…D-7),
[pos-p1-supermarket-build-plan.md](pos-p1-supermarket-build-plan.md) (P1 estimates),
PRD backend-asks BR-1…BR-15 ([prd/04 §9/§9a](../integration/pos/prd/04-architecture-dependencies-roadmap.md)).

Single tracker for "what's left in the POS **backend**." The external POS **client** app is a separate
track (not started — see the PRD). Cashier-ergonomics (scan loop, void-line, suspend/recall, peripherals)
are client-side by design (ADR-0044 D-7), not backend items.

---

## ✅ Done — live on `develop`
- **Core POS / transaction integrity** (ADR-0042): sessions, tills, ring sale, server-authoritative pricing + VAT, multi-tender (CASH/CARD/MOBILE_MONEY/CHEQUE + split), idempotency (`Idempotency-Key`), whole-sale reverse/void.
- **`V72` origin-refs fix** — a real POS sale now persists (`origin='POS'` no longer violates `chk_sales_invoice_origin_refs`) — plus a **basic end-to-end POS-sale IT** so the core can't silently break again.
- **P1 — Wave 1: age-restriction gate** (ADR-0044 D-3a / BR-11).

## 🔶 In review — built & full-suite-green, awaiting manual review/commit
- **P1 — Wave 2a: embedded weight/price barcodes** (ADR-0044 D-1a / BR-9). On branch `feat/pos-age-restriction`, uncommitted: `barcode_symbology_rules` (`V73`) + decode-enabled `barcode-lookup`.

---

## ⛔ Remaining backend — NOT STARTED

### P1 — legal + core grocery (finish first)
| Item | ADR / BR | Scope | Est. | Notes |
|---|---|---|---|---|
| **Weighed goods** (sell-by-weight) | D-1b / BR-13 | `products` cols (`is_weighed`, `tare_weight`, `scale_rounding_mode`) via `V74` + sale-line validation (must use a WEIGHT unit; tare; rounding) | ~3–5d | **Next up** (Wave 2b). Price math already works. |
| **Fiscal / EFD receipt** | D-3b / BR-10 | Pluggable fiscalisation SPI at finalise + invoice fiscal columns + NoOp dev adapter | harness ~5–8d | **The long pole.** Must be **PROVIDER-AGNOSTIC** (owner, 2026-06-20): one adapter SPI spanning EAC authorities **TRA / URA / KRA / RRA / …** and their **aggregators**. The real per-authority adapters are a **separate spike → own ADR**, **uncommitted date**. Legal prerequisite for a VAT retailer. |

### P2 — margin + service
| Item | ADR / BR | Scope | Notes |
|---|---|---|---|
| **Promotions-on-POS** | D-2 / BR-12 | Extend the (orphaned) `Promotion` model for **multi-buy / mix-match / BOGO / threshold**, AND evaluate + apply it server-side on the sale pricing path (it is configurable today but never applied at sale time) | **Highest leverage** for grocery economics. |
| **Partial / line-level refunds** | D-4 / BR-2a | Credit-note-by-line against a POS sale (the part of the reverse work ADR-0042 deferred); refund-to-original-tender; no-receipt return | Common grocery return. |

### P3 — retention + convenience
| Item | ADR / BR | Notes |
|---|---|---|
| **Loyalty subsystem** | D-5 / BR-15 | Member, points accrual/redemption, member pricing. Sizeable — **own ADR**. |
| **Gift card / store credit** | D-6 / BR-15 | Issue (sellable) + redeem (balance-backed tender). |
| **PLU master / lookup** | D-1c / BR-14 | Ring-by-number for un-barcoded loose produce. |

### Smaller pre-existing asks
| Item | BR | Notes |
|---|---|---|
| Manual price override at POS (supervisor) | BR-5 | New `POS.SALE.PRICE_OVERRIDE` perm; back-office invoice flow already has `overrideLinePrice`. |
| Server-side park/recall (held-sale draft) | BR-6 | Optional cross-device basket recall. |
| Mid-shift float top-up / cash-in | BR-7 | Cash-in counterpart to the existing payout. |
| Re-open / amend a closed/reconciled session | BR-8 | Currently terminal. |

### Also open (non-grocery)
- **Offline / batch sale ingest** (§12 #6) — server-side queue/replay endpoint; superset of idempotency.

---

## Suggested order
1. Finish **P1**: Wave 2b weighed goods → fiscal/EFD (spike the multi-authority adapter early — it gates a real EAC supermarket).
2. **P2**: promotions-on-POS (margin) + partial refunds.
3. **P3** + the smaller BR-5…8 as prioritised.

> **Migration discipline:** every schema change above is a new additive `V<n>` (or repeatable `R__` seed), proposed for **owner approval before it is written** (standing rule, 2026-06-20). Next free version after the in-review `V73` is `V74`.
