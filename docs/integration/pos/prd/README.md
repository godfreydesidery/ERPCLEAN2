# POS Client Application — Product Requirements Document

> **What this is.** The Product Requirements Document (PRD) for an **external Point-of-Sale (POS)
> client application** — a standalone desktop / web / mobile till app that a developer will build
> to run a retail register. The app is a **separate product from the ERP**: it owns no database and
> no business logic, and operates a till entirely by calling the ERP's stateless, JWT-secured REST
> API (`/api/v1/...`). A cashier logs in, opens a cash session on a till, rings sales against the
> catalogue, prints a receipt from the finalised invoice the API returns, and at end-of-shift
> closes and reconciles the drawer — every step a sequence of plain `application/json` HTTPS calls,
> with the ERP authoritative for price, VAT, totals, stock, and all postings. This PRD is the build
> specification for that client, written against the API **exactly as it exists today**.

---

## Executive Summary

**The opportunity.** The ERP exposes a capable but **raw** transactional surface — POS sale,
session, and till controllers plus auth and several cross-module reads — with **no cashier-facing
UI, no peripheral integration, no basket model, and no client-side safety net** for the realities
of a retail floor (dropped networks, expiring 15-minute access tokens, ambiguous timeouts, and the
absence of server-side sale idempotency). Without a purpose-built client, a retailer cannot operate
a till. A thin, fast, error-tolerant POS client turns that API into a working register and captures
the retry, reconcile, and receipt-integrity discipline the server explicitly delegates to the
client.

**What v1 is.** A **controlled, attended, cash-only** till that runs the full shift loop end-to-end
on **today's API** — login → resolve scope → pick/create till → open session → ring single- and
multi-line **cash** sales (line discounts, walk-in or registered customer) → print and reprint
receipts → mid-shift X-read and payouts → close → reconcile (server-computed Z-read variance) —
**online-first**, with disciplined retry-safety (a durable client transaction id and
reconcile-before-resend in place of the server idempotency the API does not provide). v1 is viable
and shippable on the API as it stands.

**The headline.** **Full retail capability is backend-gated, not a matter of client effort.**
Refunds and voids at the till, card / mobile-money / cheque / split / over-tender payments, and a
robust true-offline till **cannot** be delivered by any amount of client cleverness — each depends
on a specific backend change tracked in **§12 (Dependencies, Roadmap & Backend Asks)** of this PRD.
Until those ship, the client **must not** offer the action and **must** present the documented
workaround (e.g. a cash-drawer `REFUND` payout plus a mandatory back-office correction). Nothing in
this PRD silently assumes a capability the API lacks: every requirement is either supported today,
flagged as a stated v1 workaround, or deferred to a later phase **with the backend dependency it
needs named**.

---

## Document map / Table of Contents

The PRD body is four part files covering **sections 1–12**. Read Part 1 first for the scope rules
and glossary, then the parts relevant to your work.

### [Part 1 — Overview, Goals, Personas & Scope](./01-overview-personas-scope.md) *(sections 1–4)*
- 1. Overview & Vision (what the client is, the problem it solves, product principles PRIN-1…7)
- 2. Goals & Success Metrics (G-1…G-11)
- 3. Personas & Users (cashier, shift supervisor, store manager, integrator/admin)
- 4. Platforms, Form Factors & Scope (in-scope IN-1…9, out-of-scope OUT-1…10, future FUT-1…7, assumptions, glossary)

### [Part 2 — Functional Requirements](./02-functional-requirements.md) *(section 5)*
- 5.1 Authentication & Session · 5.2 Shift & Till Management · 5.3 Catalog & Product Search
- 5.4 Cart & Selling · 5.5 Pricing & Tax (server-authoritative) · 5.6 Customers
- 5.7 Payments & Tender · 5.8 Receipts · 5.9 Returns / Refunds · 5.10 Stock Visibility (advisory)
- 5.11 Reporting (X / Z reads) · 5.12 Offline Mode & Sync · 5.13 Settings & Admin
- 5.14 Requirements traceability summary

### [Part 3 — Non-Functional Requirements & UX](./03-nonfunctional-and-ux.md) *(sections 6–7)*
- 6. Non-Functional Requirements — 6.1 Performance · 6.2 Reliability & Resilience · 6.3 Security
  · 6.4 Usability & Accessibility · 6.5 Localisation & Currency · 6.6 Observability
  · 6.7 Hardware & Peripherals · 6.8 Platform & Build · 6.9 Non-functional traceability
- 7. UX & Key Screens — 7.1 Screen inventory · 7.2 Primary wireflow · 7.3 Screen-by-screen behaviour notes · 7.4 Cross-cutting UX rules

### [Part 4 — Architecture, Dependencies, Risks & Roadmap](./04-architecture-dependencies-roadmap.md) *(sections 8–12)*
- 8. Integration & Technical Architecture (principles, layered client, auth/refresh, sync-invoice vs async-posting, reconcile-before-resend, local store, configuration)
- 9. Dependencies & Backend Asks (**BR-1…BR-8**)
- 10. Risks & Mitigations (R-1…R-10)
- 11. Release Plan & Phasing (Phase 1 / 2 / 3 gates)
- 12. Success Metrics, Open Questions & Traceability

> **Section numbering note.** Sections 1–12 run continuously across the four parts: Part 1 = §1–4,
> Part 2 = §5, Part 3 = §6–7, Part 4 = §8–12. The "§12 backend asks" referenced in the Executive
> Summary are the **dependencies/backend-asks of this PRD's §9–12 (Part 4)**, which in turn close
> the four API gaps catalogued in the API reference's own `../12-known-limitations.md`.

---

## Phasing-at-a-glance

Three phases, each gated by the backend capability it requires. Phase 1 ships entirely on today's
API; Phases 2 and 3 **must not start** until their named backend asks are delivered and verified.

| Phase | Theme | Backend dependency |
|-------|-------|--------------------|
| **Phase 1 — MVP** | Attended, **cash-only**, online-first: full shift loop (open → ring cash sales → print → X-read → close → reconcile) with reconcile-before-resend retry-safety; refunds handled only via the cash-drawer `REFUND` payout + mandatory back-office correction. | **None** — runs on the current API. |
| **Phase 2 — Idempotent sales + in-till refund/void** | Safely retryable sale posting; cashier can reverse a mistake / process a refund at the till; (accurate agent attribution if BR-4 lands). | **BR-1** (idempotency key / `clientSaleRef` returning the original `201` on replay) **and BR-2** (atomic POS reversal/refund/void, permission-gated). *(BR-4 should land here.)* |
| **Phase 3 — Multi-tender / card + offline-robust** | Card / mobile-money / cheque and **split / over-tender** at the till with ledger-recorded change; hardened true-offline queue with exactly-once replay; optional enhancers. | **BR-3** (multi/split tender + non-cash). Benefits from **BR-1** (offline exactly-once), **BR-5** (price override), **BR-6** (draft/hold), **BR-7** (float top-up), **BR-8** (session re-open). |

See **Part 4 §9** for the full backend-ask register (BR-1…BR-8) and **§11** for per-phase entry/exit
gate criteria.

---

## Grounding & complementary references

This PRD is **grounded in and complemented by** the verified POS Integration API reference and
use-case catalogue one directory up at
[`../`](../README.md) (`d:/My_Works/ERP/ERPCLEAN2/docs/integration/pos`):

- **[API reference `../00`–`../12`](../README.md)** — the endpoint-by-endpoint contract every
  requirement traces to (`§nn`). The single most important grounding document is
  **[`../12 — Known Limitations`](../12-known-limitations.md)**: no sale idempotency (#1), no POS
  reversal/refund/void (#2), single exact-cash tender (#3), `unitPrice` required-but-ignored (#4).
- **[Use-case catalogue `../use-cases/`](../use-cases/README.md)** — the `UC-xx` scenarios (and
  their actor/permission model) that the functional requirements are derived from and tested
  against.

Read the PRD alongside those: the PRD says *what to build and in what order*; the API reference and
use cases say *exactly how each call behaves and where the gaps are*.
