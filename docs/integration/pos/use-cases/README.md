# POS Use Cases — Catalogue

These pages are the **scenario** layer of the POS integration docs: end-to-end journeys an integrator stitches together from the endpoints — "provision a cashier", "open a shift and ring sales", "give a refund", "recover from a dropped sale" — each with the realistic order of calls, the load-bearing fields, the failure branches, and an honest note where the desired action is **not supported today**. They are deliberately distinct from the **API reference** (the sibling sections `00`–`12` one directory up at [`../`](../README.md)): the reference documents each endpoint, DTO, permission and error in isolation (request/response shapes, validation rules, the `{data, errors, meta}` envelope), whereas these use cases assume you already have that reference and tell you how to *compose* those endpoints into a working till. When in doubt, read the reference section a use case links to (e.g. [§08 Sessions](../08-sessions.md), [§12 Known Limitations](../12-known-limitations.md)) for the authoritative contract; the use case tells you the *why* and the *sequence*.

---

## Master catalogue of use cases

Five group files, grouped A–E. "Supported today?" reflects what the **POS API** can do right now: **yes** = a first-class POS path exists; **partial** = the call succeeds but a field/behaviour does not work as its name implies (see the row note); **no** = there is no POS endpoint for it (workaround lives in the back office). The rows that previously hinged on the §12 limitations are flagged in the last column — but **three of those four gaps are now CLOSED** (commit `f08fb08`, [ADR-0042](../12-known-limitations.md)), so the scenarios that depended on them are now POS-resolvable. See the note under the table for the new framing; the linked group-file pages are being refreshed and may still read "not supported today" until that lands.

| UC | Title | Group | Supported today? | §12-bound |
|----|-------|-------|------------------|-----------|
| [UC-A1](./01-setup-and-provisioning.md#uc-a1-provision-a-pos-operator) | Provision a POS operator (user + role + grant + branch) | Setup & Provisioning | **yes** | |
| [UC-A2](./01-setup-and-provisioning.md#uc-a2-resolve-the-operating-context) | Resolve the operating context (org → company → branch) | Setup & Provisioning | **yes** | |
| [UC-A3](./01-setup-and-provisioning.md#uc-a3-create-a-till-register) | Create a till (register) | Setup & Provisioning | **yes** | |
| [UC-A4](./01-setup-and-provisioning.md#uc-a4-listselect-an-existing-till) | List / select an existing till | Setup & Provisioning | **yes** | |
| [UC-A5](./01-setup-and-provisioning.md#uc-a5-retire--delete-a-till) | Retire / delete a till (soft-delete) | Setup & Provisioning | **yes** | |
| [UC-B1](./02-shift-lifecycle.md#uc-b1-open-a-session-with-an-opening-float) | Open a session with an opening float | Shift Lifecycle | **yes** | |
| [UC-B2](./02-shift-lifecycle.md#uc-b2-view-the-current--open-session) | View the current / open session | Shift Lifecycle | **yes** | |
| [UC-B3](./02-shift-lifecycle.md#uc-b3-record-a-cash-payout--drawer-drop-mid-shift) | Record a cash payout / drawer drop mid-shift | Shift Lifecycle | **yes** | |
| [UC-B4](./02-shift-lifecycle.md#uc-b4-run-a-mid-shift-x-read-x-report) | Run a mid-shift X-read (X report) | Shift Lifecycle | **yes** | |
| [UC-B5](./02-shift-lifecycle.md#uc-b5-close-the-session-declare-counted-cash) | Close the session (declare counted cash) | Shift Lifecycle | **yes** | |
| [UC-B6](./02-shift-lifecycle.md#uc-b6-reconcile-z-read-and-resolve-the-variance) | Reconcile (Z-read) and resolve the variance | Shift Lifecycle | **yes** | |
| [UC-B7](./02-shift-lifecycle.md#uc-b7-float-top-up--cash-in-mid-shift--not-supported-today) | Float top-up / cash-in mid-shift | Shift Lifecycle | **no** | |
| [UC-B8](./02-shift-lifecycle.md#uc-b8-re-open-or-edit-a-closedreconciled-session--not-supported-today) | Re-open or edit a closed/reconciled session | Shift Lifecycle | **no** | |
| [UC-B9](./02-shift-lifecycle.md#uc-b9-refund--void-a-pos-sale-from-the-till--not-supported-today) | Refund / void a POS sale from the till | Shift Lifecycle | **yes** (whole-sale, OPEN session) | §12 #2 — **CLOSED** |
| [UC-B10](./02-shift-lifecycle.md#uc-b10-non-cash-or-split-tender-at-the-till--not-supported-today) | Non-cash or split tender at the till | Shift Lifecycle | **yes** | §12 #3 — **CLOSED** |
| [UC-C1](./03-selling.md#uc-c1-ring-a-simple-cash-walk-in-sale-single-line) | Ring a simple cash walk-in sale (single line) | Selling | **yes** | |
| [UC-C2](./03-selling.md#uc-c2-multi-line-sale) | Multi-line sale | Selling | **yes** | |
| [UC-C3](./03-selling.md#uc-c3-sale-to-a-registered-customer-look-up-or-create-on-the-fly) | Sale to a registered customer (look up / create-on-the-fly) | Selling | **yes** | |
| [UC-C4](./03-selling.md#uc-c4-apply-a-line-discount) | Apply a line discount | Selling | **yes** | |
| [UC-C5](./03-selling.md#uc-c5-attribute-a-sale-to-a-sales-agent) | Attribute a sale to a sales agent | Selling | **partial** | |
| [UC-C6](./03-selling.md#uc-c6-check-stock-availability-before--while-ringing-advisory) | Check stock availability before / while ringing (advisory) | Selling | **yes** | |
| [UC-C7](./03-selling.md#uc-c7-print-a-receipt-from-the-201-response) | Print a receipt from the 201 response | Selling | **yes** | |
| [UC-C8](./03-selling.md#uc-c8-reprint--look-up-a-past-receipt) | Reprint / look up a past receipt | Selling | **yes** | |
| [UC-C9](./03-selling.md#uc-c9-suspend--park--recall-a-sale--not-supported-at-pos-today) | Suspend / park & recall a sale | Selling | **no** | |
| [UC-C10](./03-selling.md#uc-c10-cashier-access-token-expires-mid-shift) | Cashier access token expires mid-shift | Selling | **yes** | |
| [UC-D1](./04-returns-and-refunds.md#uc-d1-customer-wants-to-return-goods--get-a-refund) | Customer wants to return goods / get a refund | Returns & Refunds | **yes** (whole-sale, OPEN session) — *partial returns deferred* | §12 #2 — **CLOSED** |
| [UC-D2](./04-returns-and-refunds.md#uc-d2-correcting-a-mis-rung-sale-wrong-item-wrong-quantity-accidental-sale) | Correcting a mis-rung sale (wrong item / qty / accidental) | Returns & Refunds | **yes** (reverse + re-ring; key prevents double-post) | §12 #1/#2 — **CLOSED** |
| [UC-E1](./05-exceptions-and-operations.md#uc-e1-sale-rejected--the-five-ways-post-possales-says-no) | Sale rejected — the five ways `POST /pos/sales` says no | Exceptions & Operations | **yes** | |
| [UC-E2](./05-exceptions-and-operations.md#uc-e2-network-drop--ambiguous-outcome-during-a-sale--reconcile-before-resend) | Network drop / ambiguous outcome — reconcile before resend | Exceptions & Operations | **yes** | |
| [UC-E3](./05-exceptions-and-operations.md#uc-e3-operating-multiple-tills--branches-concurrently) | Operating multiple tills / branches concurrently | Exceptions & Operations | **yes** | |
| [UC-E4](./05-exceptions-and-operations.md#uc-e4-currency-selection--enabled-currencies-and-the-default) | Currency selection — enabled currencies and the default | Exceptions & Operations | **yes** | |
| [UC-E5](./05-exceptions-and-operations.md#uc-e5-end-of-day-close-out-across-tills) | End-of-day close-out across tills | Exceptions & Operations | **yes** | |

> **Note on the formerly §12-bound scenarios (now largely shipped).** Four use cases — **UC-B9**, **UC-D1**, **UC-D2**, **UC-B10** — were previously "no" because they were blocked by the backend gaps catalogued in [§12 Known Limitations](../12-known-limitations.md). **Three of those four gaps are now CLOSED** in commit `f08fb08` ([ADR-0042](../12-known-limitations.md)), so these scenarios are now **resolvable at the POS** (with the two narrow exceptions called out below):
>
> - **UC-B9 / UC-D1 (refund/void/return at the POS)** — now served by [§12 #2 — whole-sale POS reversal, CLOSED](../12-known-limitations.md#2-whole-sale-pos-reversal--refund--void--closed-reverse-endpoint): `POST /api/v1/pos/sales/uid/{uid}/reverse` with `{ "reason": … }` → `204` (perm `POS.SALE.VOID`) reverses the **whole** sale (revenue + VAT + cash + stock + COGS) and drops it out of the drawer — no separate payout needed. The cash-drawer `REFUND` payout is **no longer the only recourse** for an OPEN-session POS sale.
> - **UC-D2 (correcting a mis-rung sale)** — reverse the bad sale (as above) and re-ring the correct one; the **double-post worry is also addressed** by [§12 #1 — idempotency, CLOSED](../12-known-limitations.md#1-sale-idempotency-on-sale-creation--closed-idempotency-key-header) (send an `Idempotency-Key` so a retried POST cannot duplicate).
> - **UC-B10 (non-cash / split tender)** — now served by [§12 #3 — multi-tender, CLOSED](../12-known-limitations.md#3-multi-tender--non-cash-payments--closed-tenders-list): pass an optional `tenders[]` list on `PosSaleRequest` (CASH/CARD/MOBILE_MONEY/CHEQUE, split supported, sum ≥ gross). Omitting it keeps the legacy single-exact-CASH behaviour.
>
> **Two residual exceptions remain genuinely "no" at the POS:** (a) **partial / line-level refunds** are explicitly **deferred** by ADR-0042 — [§12 #5](../12-known-limitations.md#5-partial--line-level-pos-refunds--deferred); a per-line return is still a back-office credit note. (b) **A sale whose session has already CLOSED/RECONCILED** cannot be reversed at the till — it must be handled via a **back-office invoice void** (the cash difference becomes a reconciled-variance matter). The `/reverse` endpoint requires the originating session to be **OPEN** and the invoice **POS-origin + FINALISED**, else `409`.
>
> *(**UC-C9** — suspend/park & recall a sale — remains a **no**, but it was never a §12-bound scenario: it is simply **absent from the POS API** (there is no draft/hold/park endpoint), not a catalogued backend gap; its workaround is purely client-side basket state — see the UC-C9 notes.)* *(**UC-C5** is **partial** for an unrelated, by-design reason: `agentId` is accepted but informational, so the invoice's agent defaults to the logged-in user — see the UC-C5 notes and [§12 #4](../12-known-limitations.md#4-server-authoritative-pricing--by-design-not-a-limitation), not a posting bug.)* Group files also list a handful of *unnumbered* "not supported today" scenarios (e.g. update/move/reactivate a till, manual price override, sell on account) in their closing tables — read those for the full reality check. **Note:** the UC-B9/UC-B10/UC-D1/UC-D2 group-file pages still carry their old "not supported today" prose; the links above are kept valid against those headers until the group files are refreshed to match `f08fb08`.

---

## Actors & permissions legend

POS actions are gated by the seven `POS.*` permission codes plus a few cross-module reads; a `403` **never names the missing code** ([§01 §9](../01-authentication-and-permissions.md)), so map roles to codes up front and diagnose with `GET /api/v1/auth/me` (effective `permissions[]`). The bootstrap **root** user bypasses every permission check.

| Actor | Typically holds | Use cases |
|-------|-----------------|-----------|
| **Integrator / admin** (or root) | `USER.MANAGE`, `ROLE.MANAGE`, `BRANCH.ASSIGN`, `COMPANY.VIEW`, `BRANCH.VIEW`, `POS.TILL.MANAGE` | UC-A1–A5, provisioning/scope in UC-E3 |
| **Store manager / shift supervisor** | `POS.TILL.MANAGE`, `POS.SESSION.RECONCILE`, often `POS.SALE.VOID` (+ cashier codes); often `SALES.CREDIT.OVERRIDE` | UC-A3/A5, UC-B6, UC-E5 (reconcile), whole-sale reversal in UC-B9/UC-D1/UC-D2, credit-override path in UC-C3/UC-E1 |
| **Cashier** | `POS.SALE.CREATE`, `POS.SESSION.OPEN`, `POS.SESSION.CLOSE`, `POS.SESSION.VIEW`, `POS.TILL.VIEW` (and `POS.SALE.VOID` if cashiers may reverse their own sales; withhold `POS.TILL.MANAGE` + `POS.SESSION.RECONCILE`) | UC-B1–B5, UC-C1–C8, UC-B9/UC-D1/UC-D2 (whole-sale reverse, OPEN session), UC-E1–E5 |
| **Finance / back office** | `SALES.INVOICE.VIEW` + finance posting rights (outside the POS surface) | partial / line-level returns (deferred at POS) and back-office voids for sessions already closed, behind UC-D1/UC-D2 |

**The `POS.*` codes** (full detail in [§01 §9](../01-authentication-and-permissions.md)): `POS.TILL.MANAGE` (create/deactivate tills), `POS.TILL.VIEW` (list/get tills), `POS.SESSION.OPEN` (open session **and** record payouts — there is no separate payout code), `POS.SESSION.CLOSE` (close/declare cash), `POS.SESSION.RECONCILE` (Z-read variance → GL; segregate from the cashier), `POS.SESSION.VIEW` (get/list/X-read), `POS.SALE.CREATE` (ring a sale), and — added by `f08fb08` ([ADR-0042](../12-known-limitations.md)) — `POS.SALE.VOID` (whole-sale reverse via `POST /pos/sales/uid/{uid}/reverse`; seeded, auto-granted to `ORG_ADMIN`). That makes **eight** POS codes. *(The §01 reference page still enumerates the original seven and is pending a refresh to list `POS.SALE.VOID`.)*

**Common cross-module reads** a selling client also needs: `CUSTOMER.VIEW`/`CUSTOMER.MANAGE`, `PRODUCT.VIEW`/`UOM.VIEW`, `CURRENCY.VIEW`, `STOCK.VIEW`, `AGENT.VIEW`, `SALES.INVOICE.VIEW` (reprints/reconcile). See each use case's preconditions for the exact set, and [§01](../01-authentication-and-permissions.md) for how grants are scoped to a company/branch.
