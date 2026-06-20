# POS Client PRD — Part 1: Overview, Goals, Personas & Scope

> **What this document is.** This is **Part 1 of the Product Requirements Document (PRD)** for an
> **external Point-of-Sale (POS) client application** — a desktop and/or mobile/kiosk till app that a
> developer will build to operate a retail register. The app is a **separate product from the ERP**:
> it owns no database and no business logic; it runs a till entirely by calling the ERP's REST API
> (`/api/v1/...`). This PRD is the build specification for that client.
>
> **Ground truth.** Every requirement here is traced to the verified API reference
> ([`../00`](../00-overview-and-conventions.md)–[`../12`](../12-known-limitations.md)) and the
> use-case catalogue ([`../use-cases/`](../use-cases/README.md)). Where the API has a known gap, this
> PRD says so explicitly and either states a **v1 workaround** or defers the capability to a later
> phase **with the backend change it depends on**. The single most important grounding document is
> [`../12 — Known Limitations`](../12-known-limitations.md): sale idempotency **CLOSED** via the
> optional `Idempotency-Key` header (§12 #1), whole-sale POS reversal/refund/void **CLOSED** via
> `POST /pos/sales/uid/{uid}/reverse` (§12 #2), multi-tender / non-cash payments **CLOSED** via the
> optional `tenders[]` list (§12 #3), and server-authoritative pricing with `unitPrice` accepted-but-
> ignored as a **by-design** behaviour (§12 #4). Still open: **partial/line-level POS refunds** (§12 #5)
> and **client-side offline ingest** (§12 #6). Nothing in this PRD silently assumes a capability the
> API lacks.
>
> > **NOTE (2026-06-20):** GAP-1/2/3 (idempotency, whole-sale reversal/refund/void, multi-tender) are
> > now **CLOSED** (commit `f08fb08`, ADR-0042) and GAP-4 is **resolved by design**. The scope tables
> > below (OUT-1, OUT-2, OUT-3, OUT-9) and the gating decisions that depended on these gaps
> > (PF-5 kiosk, the "v1 = cash-only attended till" boundary) should be revisited by the owner. The
> > original scope/planning text is left in place per the factual-correction-only remit; see the inline
> > flags below.
>
> **MoSCoW.** Requirements use **Must / Should / Could / Won't-for-v1** prioritisation. Each carries
> an id (e.g. `PR-1.1`) and traces to use cases (UC-xx) and/or API sections (§nn) where relevant.
>
> **Part map.** Part 1 (this document) = sections 1–4 (Overview, Goals & Metrics, Personas,
> Platforms & Scope). Later parts cover functional requirements (provisioning, shift lifecycle,
> selling, receipts, exceptions/offline), non-functional requirements, and the phased roadmap with
> backend dependencies.

---

## 1. Overview & Vision

### 1.1 What the POS client is

The POS client is a **thin retail till application** that turns the ERP's stateless JSON API into a
working cashier experience. A cashier logs in, opens a cash session on a till, scans/searches the
catalogue, rings sales, prints a receipt from the finalised invoice the API returns, takes cash, and
at end-of-shift counts the drawer, closes, and reconciles the session. The client renders the UI,
manages local cart/basket state, drives peripherals (scanner, receipt printer, cash drawer), and
orchestrates the API call sequence; the ERP owns all data, money, stock, and posting.

The complete server loop the client automates is, end to end:
**login → resolve context → pick/create till → open session → load catalogue & prices → ring sale →
print receipt → (mid-shift X-read / payouts) → close → reconcile (Z-read) → logout**
(see [`../README` Quickstart](../README.md) and the "typical day" sequence there).

### 1.2 The problem it solves

The ERP exposes a capable but **raw** transactional surface (three POS controllers plus auth and
several cross-module read endpoints). It has **no cashier-facing UI**, no peripheral integration, no
basket model, and — critically — **no client-side safety net** for the realities of a retail floor:
dropped networks mid-sale, expiring 15-minute access tokens, ambiguous timeouts, and (for clients
that omit the now-shipped `Idempotency-Key` header) the legacy duplicate-posting window (§12 #1).
Without a purpose-built client, a retailer cannot operate a till. The
POS client closes that gap: it presents a fast, error-tolerant register UI and **owns the retry,
reconcile, and receipt-integrity discipline** that the server explicitly delegates to the client
([`../11 §4.2`](../11-errors-offline-idempotency.md)).

### 1.3 Product principles

These principles bind every requirement in this PRD.

- **PRIN-1 — Thin client over the ERP API.** The client holds **no business logic** and **no system
  of record**. It computes nothing the server is authoritative for (price, VAT, totals, variance) and
  treats the API as the only source of truth. The returned `SalesInvoiceDto` — not any client
  calculation — is the receipt of record (§12 #4; [`../09`](../09-sales-payments-receipts.md)).
- **PRIN-2 — Server-authoritative money.** Price, discount resolution, VAT, gross/net totals, and
  cash variance are computed by the ERP. Client-side prices and change are **previews/aids only**
  (§12 #3, §12 #4; UC-C1).
- **PRIN-3 — Be realistic, not aspirational.** The client never offers a retail action the API cannot
  perform. As of `f08fb08` (ADR-0042) the API **now supports** whole-sale till-side refund/void
  (§12 #2) and card/mobile-money/cheque/split tender (§12 #3); the actions the API still cannot
  perform are **partial/line-level refunds** (§12 #5), manual price override (§12 #4 — by design),
  and draft/hold (no endpoint). Where retailers expect an unsupported action, the UI states the
  supported workaround instead of failing silently (§12 #4/#5; UC-B9, UC-B10, UC-C9, UC-D1, UC-D2).
- **PRIN-4 — Safe by default on writes.** The one mutating money call — `POST /pos/sales` — is
  **never blind-retried**. The server now provides idempotency via the optional `Idempotency-Key`
  header (§12 #1, `f08fb08`): the client sends one key per logical sale and **resends the same key**
  on an ambiguous outcome to get the original invoice back. Only when the header is omitted does the
  legacy reconcile-before-resend fallback apply ([`../11 §4`](../11-errors-offline-idempotency.md)).
- **PRIN-5 — Fast at the counter.** The ring-to-receipt path is optimised for keyboard/scanner-first
  operation; catalogue and price data are cached locally to keep ringing responsive.
- **PRIN-6 — Honest degradation.** When connectivity, tokens, or session state are not healthy, the
  client tells the cashier plainly what it can and cannot do rather than appearing to work.
- **PRIN-7 — Auditability.** Every logical sale carries a durable local client transaction id used as
  `X-Request-Id` across attempts so a double-post is findable in ERP logs
  ([`../11 §4.2 #3`](../11-errors-offline-idempotency.md)).

---

## 2. Goals & Success Metrics

Goals are measurable and testable. Each metric names how it is measured so it can be verified in
acceptance testing or in production telemetry.

| Id | Goal | Metric (target for v1) | How measured |
|----|------|------------------------|--------------|
| **G-1** | Fast ring-to-receipt | Median time from first item scanned to receipt printed **≤ 8 s** on a single-line cash sale; 95th percentile **≤ 15 s** (excludes peripheral hardware faults) | Client instrumentation timestamping scan → `201` → print, on the UC-C1 happy path |
| **G-2** | Catalogue responsiveness | Item lookup (barcode scan or search keystroke) renders a result in **≤ 300 ms** when served from local cache | Client timer around catalogue read; cache hit path (§03, §04) |
| **G-3** | No silent duplicate sales | **Zero** duplicate finalised invoices attributable to client auto-retry across an acceptance run that injects timeouts on `POST /pos/sales` | Fault-injection test asserting reconcile-before-resend (UC-E2; [`../11 §4.2`](../11-errors-offline-idempotency.md)) |
| **G-4** | Token-expiry resilience | **100%** of in-progress carts survive a mid-shift access-token expiry with no data loss and at most one transparent refresh | Test forcing 15-min access-token expiry mid-cart (UC-C10; [`../01`](../01-authentication-and-permissions.md)) |
| **G-5** | Offline ring-up resilience (queue-and-replay) | A sale composed while disconnected is **never lost**: it is queued locally and either replayed exactly once on reconnect or surfaced for cashier review | Offline-then-reconnect test; serialized replay with reconcile (UC-E2; [`../11 §4.3`](../11-errors-offline-idempotency.md)) — see scope note **SC-OFF** below |
| **G-6** | Shift-close accuracy | Counted-vs-expected cash variance shown to the cashier **equals** the server-computed `varianceAmount` to the minor unit (no client rounding drift) | Close → compare client display to `PosSessionDto.varianceAmount` / `ZReadDto` (UC-B5, UC-B6; [`../08`](../08-sessions.md)) |
| **G-7** | Receipt fidelity | Printed totals (net, VAT, gross, invoice number) **match** the returned `SalesInvoiceDto` byte-for-figure on 100% of sampled receipts | Compare printed fields to `201` payload (UC-C7; [`../09`](../09-sales-payments-receipts.md)) |
| **G-8** | Reprint without re-posting | Reprinting any receipt triggers **zero** new `POST /pos/sales` calls | Assert reprint path reads persisted/looked-up invoice only (UC-C8; [`../11 §4.2 #5`](../11-errors-offline-idempotency.md)) |
| **G-9** | Error legibility | Every API failure is shown to the cashier with an actionable message branched on **HTTP status, not parsed text** | Review against the §11 status table (UC-E1; [`../11 §2`](../11-errors-offline-idempotency.md)) |
| **G-10** | Adoption / task completion | A trained cashier completes open-session → 10 sales → close → reconcile **without external help** in a usability session; ≥ 90% task-completion across pilot cashiers | Pilot observation; supervisor sign-off (UC-B1–B6, UC-C1–C8) |
| **G-11** | Segregation-of-duties honoured | The client never lets a cashier without `POS.SESSION.RECONCILE` reach the reconcile action | RBAC test against `/auth/me` permission gating (UC-B6; [`../use-cases/README` actors](../use-cases/README.md)) |

> **Non-goal metrics.** Throughput of asynchronous ledger posting (stock issue, GL, AR) is **out of
> the client's control** — those land ~1s after the `201` via the server outbox poller
> ([`../09`](../09-sales-payments-receipts.md); [`../11 §4.1`](../11-errors-offline-idempotency.md)).
> The client measures only what it can affect (the synchronous `201` and its own UX), and **must not**
> assert ledger state at receipt time.

---

## 3. Personas & Users

The personas map directly to the actor model in the use-case catalogue
([`../use-cases/README` — Actors & permissions](../use-cases/README.md)). Permission codes are the
seven `POS.*` codes plus cross-module reads; a `403` **never names the missing code**, so the client
must drive its UI from the effective `permissions[]` returned by `GET /api/v1/auth/me`
([`../01`](../01-authentication-and-permissions.md)).

### 3.1 Cashier (primary user)

- **Maps to:** the **Cashier** actor (UC-B1–B5, UC-C1–C8, UC-E1–E5; payout-only role in UC-D1/UC-D2).
- **Typically holds:** `POS.SALE.CREATE`, `POS.SESSION.OPEN`, `POS.SESSION.CLOSE`, `POS.SESSION.VIEW`,
  `POS.TILL.VIEW`, plus reads (`CUSTOMER.VIEW`, `PRODUCT.VIEW`, `UOM.VIEW`, `CURRENCY.VIEW`,
  `STOCK.VIEW`, `AGENT.VIEW`, `SALES.INVOICE.VIEW`). **Withholds** `POS.TILL.MANAGE` and
  `POS.SESSION.RECONCILE`.
- **Goals:** ring sales quickly with scanner/keyboard; take cash and give correct change; reprint a
  receipt; open and close own shift; keep moving during brief network blips.
- **Pain points the client must address:** slow lookups; confusing API errors; ambiguous "did that
  sale go through?" timeouts (now resolvable by reusing the `Idempotency-Key`, §12 #1); access token
  silently expiring mid-cart; **wanting to void/refund at the till** — now supported for a whole open-
  session sale via `POST /pos/sales/uid/{uid}/reverse` (§12 #2, `f08fb08`); the cashier still cannot
  do a **partial/line-level refund** (§12 #5 — deferred), so the UI must say so for those cases and
  offer the documented workaround rather than pretend to refund a line.

### 3.2 Shift supervisor

- **Maps to:** the **Store manager / shift supervisor** actor (UC-B6, UC-E5; credit-override path in
  UC-C3/UC-E1).
- **Typically holds:** cashier codes **plus** `POS.SESSION.RECONCILE`, often `POS.TILL.MANAGE`, and
  may hold `SALES.CREDIT.OVERRIDE`.
- **Goals:** authorise reconciliation (Z-read) at end of shift; review variance; unblock a
  credit-limit-stopped sale via override; oversee multiple cashiers/tills.
- **Pain points the client must address:** needing a clear variance view at close; segregation of
  duties (the cashier who counts cash should not be the one who reconciles the variance to GL —
  [`../08`](../08-sessions.md)); diagnosing a cashier's `403` without the code being named (resolve via
  `/auth/me`).

### 3.3 Store manager

- **Maps to:** the **Store manager** facet of the manager/supervisor actor (UC-A3/A5, UC-E5).
- **Typically holds:** `POS.TILL.MANAGE`, `POS.SESSION.RECONCILE`, plus oversight reads.
- **Goals:** create/retire tills for the branch; end-of-day close-out across all tills; confirm the
  day's sessions are reconciled; operate across branches via `X-Branch-Uid` without re-login
  ([`../02`](../02-company-branch-context.md), UC-E3).
- **Pain points the client must address:** managing several tills/sessions per branch; an at-a-glance
  end-of-day status across tills (UC-E5); knowing that **re-opening or editing a
  closed/reconciled session is not supported** (UC-B8) and that **mid-shift float top-up is not
  supported** (UC-B7) — the client must not offer these.

### 3.4 Integrator / administrator (and root)

- **Maps to:** the **Integrator / admin (or root)** actor (UC-A1–A5; provisioning/scope in UC-E3).
- **Typically holds:** `USER.MANAGE`, `ROLE.MANAGE`, `BRANCH.ASSIGN`, `COMPANY.VIEW`, `BRANCH.VIEW`,
  `POS.TILL.MANAGE`. The bootstrap **root** user bypasses every permission check (`isRoot=true`,
  empty `permissions[]`).
- **Goals:** provision cashier users with the right roles and branch assignments; configure the
  client's host/environment; verify the walk-in customer and sales-agent records exist (their numeric
  ids are required on every sale); validate connectivity and `POS.*` grants before go-live.
- **Pain points the client must address:** a clear setup/diagnostics screen (host reachability,
  login, `/auth/me` permission echo, till discovery); guidance that **provisioning of users/roles is
  primarily an ERP-admin task** (the POS client supports it only as far as the API exposes, UC-A1).

---

## 4. Platforms, Form Factors & Scope

### 4.1 Platforms & form factors

| Id | Platform / form factor | Priority | Notes |
|----|------------------------|----------|-------|
| **PF-1** | **Desktop — Windows** (counter till with scanner, receipt printer, cash drawer) | **Must** | Primary attended-till target; richest peripheral support |
| **PF-2** | **Web / browser** (responsive register UI) | **Should** | Same API; peripheral access constrained by browser capabilities |
| **PF-3** | **Mobile — Android** (handheld cashier / line-buster) | **Should** | Touch-first ringing; intermittent connectivity expected |
| **PF-4** | **Mobile — iOS** | **Could** | Parity with Android where platform allows |
| **PF-5** | **Self-checkout kiosk** (unattended) | **Won't (v1)** | Unattended use is **blocked** until §12 #1 (idempotency) and §12 #2 (reversal/refund) are closed — see [`../12` scope note](../12-known-limitations.md); revisit when those backend changes ship |

> **PF-5 rationale.** Unattended kiosk operation cannot tolerate the duplicate-posting window (§12 #1)
> or the inability to void a mis-scan (§12 #2) without staff present. v1 targets **attended** tills
> only; kiosk is a future phase gated on those backend fixes.
>
> > **NOTE (2026-06-20):** the two backend fixes this PF-5 gating depended on — §12 #1 (idempotency)
> > and §12 #2 (whole-sale void) — are now **CLOSED** (`f08fb08`, ADR-0042). The "Won't (v1)" decision
> > and its rationale should be revisited by the owner; left in place pending a deliberate re-plan.

### 4.2 Online / offline expectation

The API is a **stateless JWT resource server with no offline/batch ingest endpoint**; a sale
**cannot** be posted to the server while disconnected ([`../11 §4.3`](../11-errors-offline-idempotency.md)).

| Id | Requirement | Priority | Trace |
|----|-------------|----------|-------|
| **SC-ON** | The client **Must** treat itself as primarily **online**: ringing a sale requires a live authenticated request, an OPEN session resolved server-side, and synchronous invoice-number allocation. | **Must** | [`../11 §4.3`](../11-errors-offline-idempotency.md), UC-C1 |
| **SC-CACHE** | The client **Must** cache read-only catalogue, unit, price, customer, and agent data locally so ringing stays responsive and tolerates brief read-side blips. | **Must** | §03, §04, §05, G-2 |
| **SC-OFF** | The client **Should** support **offline compose + queue-and-replay**: a cart built while disconnected is persisted locally with a durable client transaction id, then on reconnect **replayed one at a time** with reconcile-before-resend per item. It **Must not** fire the queue in parallel and **Must** stop on the first terminal error for review. | **Should** | [`../11 §4.3`](../11-errors-offline-idempotency.md), UC-E2, G-5 |
| **SC-OFF-LIMIT** | The client **Must** warn that a long offline period can outlast the cashier's server-side session (it may be closed/reconciled), and on reconnect **Must** re-check `GET /pos/sessions/uid/{uid}` before replaying; if not OPEN, queued sales are an exception to escalate (open a new session). | **Must** | [`../11 §4.3`](../11-errors-offline-idempotency.md), UC-B8 |
| **SC-NO-OFFLINE-POST** | The client **Won't** claim to "post sales offline." There is no server-side queue to accept deferred posts; offline is **local queue only**. | **Won't (v1 — by design)** | [`../11 §4.4`](../11-errors-offline-idempotency.md) |

### 4.3 In scope (v1)

These v1 capabilities all rest on **API support that already exists** (verified in §00–§11 and the
"yes" use cases of [`../use-cases/README`](../use-cases/README.md)):

- **IN-1** Authentication: login, transparent access-token refresh, logout; bearer on every call;
  re-login on refresh-chain failure (§01; UC-C10).
- **IN-2** Context resolution: read `/auth/me`, `/auth/my-branches`; resolve numeric `companyId` /
  `branchId`; per-call branch override via `X-Branch-Uid` (§02; UC-A2, UC-E3).
- **IN-3** Till management: list/select tills; create/retire a till (manager) (§07; UC-A3–A5).
- **IN-4** Session lifecycle: open with float, view current, record cash **payout**, mid-shift X-read,
  close with counted cash, reconcile (Z-read) with server-computed variance (§08; UC-B1–B6).
- **IN-5** Catalogue & pricing UI: product search, barcode lookup, units, price preview, currency
  selection from enabled currencies (§03, §04; UC-C6, UC-E4).
- **IN-6** Selling: single- and multi-line **cash** sales; line discount via `lineDiscountAmount`;
  registered-customer or walk-in customer; sales-agent selection (with the caveat in OUT-5);
  advisory stock check (§06, §09; UC-C1–C6).
  > **NOTE (2026-06-20):** the API now accepts an optional multi-tender `tenders[]` list
  > (CASH/CARD/MOBILE_MONEY/CHEQUE, split, sum ≥ gross) — §12 #3 / OUT-3 is **CLOSED** (`f08fb08`).
  > Whether v1 selling is widened from "cash only" to multi-tender is a scope decision for the owner;
  > the "**cash**" wording is left as-is pending that re-plan.
- **IN-7** Receipts: print from the `201` `SalesInvoiceDto`; reprint/look up a past receipt without
  re-posting (§09; UC-C7, UC-C8).
- **IN-8** Errors & resilience: status-code-driven error handling; reconcile-before-resend on
  ambiguity; durable client transaction id as `X-Request-Id`; local receipt persistence (§11;
  UC-E1, UC-E2).
- **IN-9** Multi-till / multi-branch operation and end-of-day close-out across tills (§02, §08;
  UC-E3, UC-E5).

### 4.4 Out of scope (v1) — gated on backend changes

These are **excluded from v1 because the API does not support them today**. Each lists the backend
change it depends on; until that ships, the client **Must not** offer the action and **Must** present
the stated workaround.

> **NOTE (2026-06-20):** the backend changes that OUT-1, OUT-2, OUT-3 and OUT-9 named as their
> "backend dependency to unblock" have **shipped** (`f08fb08`, ADR-0042): whole-sale reverse endpoint
> (§12 #2), `Idempotency-Key` (§12 #1) and the multi-tender `tenders[]` list (§12 #3). Their
> "excluded — API does not support it" premise no longer holds for the whole-sale / multi-tender
> cases (only **partial/line-level** refunds, §12 #5, remain genuinely deferred). These out-of-scope
> rows should be re-evaluated by the owner; the rows are left in place per the factual-correction-only
> remit, with the now-stale gap basis annotated inline below.

| Id | Excluded capability | API gap | v1 behaviour / workaround | Backend dependency to unblock |
|----|--------------------|---------|---------------------------|-------------------------------|
| **OUT-1** | Till-side **refund / void / reverse** a POS sale | ~~§12 #2 (no reversal endpoint)~~ **CLOSED `f08fb08`** — whole-sale reverse now ships; see §12 #2. *(Partial/line-level refund still deferred, §12 #5.)* | ~~UI states it is unavailable; offer a cash-drawer `REFUND` **payout**~~ → for an OPEN-session sale, call `POST /pos/sales/uid/{uid}/reverse` (perm `POS.SALE.VOID`); workaround now applies only to closed-session sales / partial refunds | `POST /pos/sales/uid/{uid}/reverse` — **DELIVERED** (UC-B9, UC-D1, UC-D2) *(owner: re-scope this row)* |
| **OUT-2** | Correcting a **mis-rung** sale at the till | ~~§12 #1 + §12 #2~~ **both CLOSED `f08fb08`** (idempotency + whole-sale reverse) | ~~Same as OUT-1; never client-resolvable~~ → an OPEN-session mis-rung sale is now client-resolvable via reverse; partial-line correction still deferred (§12 #5) | Idempotency + reversal endpoints — **DELIVERED** (UC-D2) *(owner: re-scope this row)* |
| **OUT-3** | **Card / mobile-money / cheque / split / over-tender** at the ledger | ~~§12 #3 (POS hard-codes single exact-gross `CASH`)~~ **CLOSED `f08fb08`** — optional `tenders[]` list (CASH/CARD/MOBILE_MONEY/CHEQUE, split, sum ≥ gross) now posts real ledger payments; see §12 #3 | ~~Cash only; UI does not present non-cash tenders~~ → multi-tender now postable; client-side change preview from `tenderedAmount` still applies for cash. **PCI/card-data note:** capture card data via an external certified terminal, never in the POS client | `PosSaleRequest` carrying a tender list — **DELIVERED** (UC-B10) *(owner: re-scope this row)* |
| **OUT-4** | **Manual price override** at the till | §12 #4 — pricing is server-authoritative **by design** (not a gap). *(Update `f08fb08`: `unitPrice`/`agentId` `@NotNull` were relaxed to optional; `unitPrice` is accepted-but-ignored, the server re-derives price + VAT.)* | Reductions only via `lineDiscountAmount`; UI shows server price as authoritative; `unitPrice` need **not** be sent (validation no longer requires it) and is ignored if sent — treat the returned invoice as truth | Honour `unitPrice` as `POS.SALE.PRICE_OVERRIDE` (audited) — still not a feature (UC-C4) |
| **OUT-5** | **Cashier-attributed sales agent** as posted | §12-adjacent (UC-C5 **partial**: `agentId` accepted but **not forwarded**; invoice agent defaults to the logged-in user) | UI may collect `agentId` but **Must** tell stakeholders the posted agent is the logged-in user, not the selected agent | Forward `agentId` from `PosSaleRequest` to the invoice (UC-C5) |
| **OUT-6** | **Suspend / park & recall** a sale (draft/hold) | No draft/hold/park endpoint exists (UC-C9 — absent, not a catalogued §12 gap) | Park is **client-side basket state only**; nothing is posted until checkout | A POS draft/hold endpoint (UC-C9) |
| **OUT-7** | **Mid-shift float top-up / cash-in** | No cash-in endpoint (UC-B7) | Not offered; only opening float + payouts exist | A session cash-in endpoint (UC-B7) |
| **OUT-8** | **Re-open / edit a closed or reconciled session** | No re-open/edit endpoint (UC-B8) | Not offered; close/reconcile are terminal | A re-open endpoint (UC-B8) |
| **OUT-9** | **Sell on account** (deferred payment / credit terms at the till) | ~~POS posts a fully-paid cash invoice only (§12 #3)~~ — §12 #3 multi-tender is now CLOSED (`f08fb08`), but the shipped `tenders[]` covers paid tenders (CASH/CARD/MOBILE_MONEY/CHEQUE), **not** an AR/credit-terms leg (the reverse path notes a POS sale "has no AR leg") | Not offered at v1 POS | ~~Depends on OUT-3~~ — OUT-3 shipped but does **not** by itself add credit/AR terms; a deferred-payment/AR tender is still a separate backend item *(owner: confirm whether this stays out-of-scope)* |
| **OUT-10** | **Asserting ledger state at receipt time** (stock decremented, GL/AR posted) | Postings are **eventual** (~1s outbox poll), not synchronous with `201` | Receipt is built from the `201` only; the client never claims stock/GL/AR are posted | N/A — by design ([`../09`](../09-sales-payments-receipts.md); [`../11 §4.1`](../11-errors-offline-idempotency.md)) |

### 4.5 Future (later phases)

When the corresponding §12 backend changes ship, these become candidate requirements (each detailed,
with acceptance criteria, in the roadmap part of this PRD):

> **NOTE (2026-06-20):** the backend changes that FUT-1, FUT-2 and FUT-3 wait on have **shipped**
> (`f08fb08`, ADR-0042) — `Idempotency-Key` (§12 #1), whole-sale `/reverse` (§12 #2) and the
> multi-tender `tenders[]` list (§12 #3). These three are no longer "future, gated on backend"; the
> owner should promote them into v1 (or an early follow-on) scope and write their acceptance criteria.
> FUT-2's **merchandise return / partial refund** portion is still genuinely future (§12 #5). Left in
> the Future list pending the owner's deliberate re-plan.

- **FUT-1** Exactly-once sale posting using a server `Idempotency-Key` / `clientSaleRef` (unblocks
  removal of the reconcile-before-resend workaround) — depends on **§12 #1** *(DELIVERED `f08fb08`)*.
- **FUT-2** Till-side refund / void / merchandise return — depends on **§12 #2** *(whole-sale reverse
  DELIVERED `f08fb08`; partial/line-level return still future, §12 #5)*.
- **FUT-3** Card, mobile-money, cheque, split and over-tender (change at the ledger) — depends on
  **§12 #3** *(DELIVERED `f08fb08`)*.
- **FUT-4** Permission-gated manual price override (`POS.SALE.PRICE_OVERRIDE`, audited) — depends on
  **§12 #4**.
- **FUT-5** Correctly attributed sales agent on the posted invoice — depends on `agentId` forwarding
  (UC-C5).
- **FUT-6** Suspend/park & recall server-side; mid-shift float top-up; session re-open — depend on the
  respective new endpoints (UC-C9, UC-B7, UC-B8).
- **FUT-7** Unattended **self-checkout kiosk** (PF-5) — depends on **§12 #1 and #2** *(both DELIVERED
  `f08fb08`; the backend gate is now lifted — owner to revisit PF-5 / kiosk phasing)*.

### 4.6 Assumptions

- **AS-1** The ERP host is reachable over **HTTPS**; the client is configured per environment with a
  base host (the `/api/v1` path is identical everywhere) ([`../00 §2`](../00-overview-and-conventions.md)).
- **AS-2** Cashier user accounts are **ACTIVE**, assigned to a usable default branch, and granted the
  required `POS.*` codes and cross-module reads **before** go-live (provisioning is an ERP-admin task,
  UC-A1).
- **AS-3** Per company, a **walk-in/cash customer** and at least one **sales agent** record exist;
  their numeric ids are required on every sale ([`../README` prerequisites](../README.md), §05).
- **AS-4** Each product to be sold has a **price-list row** for the company in the selling currency;
  otherwise the line is rejected `400 BR-SALES-03` (§04; UC-C1).
- **AS-5** The selling **currency is enabled** for the company/branch scope, else the sale returns
  `422 CurrencyNotEnabled` (§04; UC-E4).
- **AS-6** Token TTLs are **15 min** (access) and **7 days** (refresh, single-use/rotated); the client
  refreshes proactively and re-logs in if the refresh chain is broken ([`../01`](../01-authentication-and-permissions.md);
  [`../11 §4.3`](../11-errors-offline-idempotency.md)).
- **AS-7** All request bodies are `application/json` (else `415`), and the client branches on **HTTP
  status, never on `errors[]` text** ([`../00 §4`](../00-overview-and-conventions.md);
  [`../11 §1`](../11-errors-offline-idempotency.md)).
- **AS-8** The `SalesInvoiceDto` returned by `POST /pos/sales` is the **receipt of record**; the
  client persists it locally keyed by its client transaction id (§09; [`../11 §4.2 #5`](../11-errors-offline-idempotency.md)).
- **AS-9** This PRD targets the API **as it is today**; if a backend fix ships, the affected OUT-/FUT-
  item is re-scoped — no requirement here pre-assumes a future capability.

### 4.7 Glossary

| Term | Meaning |
|------|---------|
| **POS client** | The external till application this PRD specifies; talks only to the ERP REST API; no DB/business logic of its own. |
| **ERP / the API** | The backend system of record exposing `/api/v1/...`; authoritative for price, VAT, totals, stock, postings. |
| **Till (register)** | A point-of-sale device record (`PosTillDto`) with a default cash/bank account; managed under `/api/v1/pos/tills` (§07). |
| **Session (shift)** | A cashier's cash session on a till: OPEN → (payouts / X-read) → CLOSED → RECONCILED; managed under `/api/v1/pos/sessions` (§08). |
| **Float** | The opening cash placed in the drawer when a session is opened (`openingFloatAmount`). |
| **Payout** | Cash leaving the drawer mid-shift (bookkeeping only; posts no stock/VAT/revenue reversal) (§12 #2). |
| **X-read** | A non-resetting mid-shift totals snapshot of the session (UC-B4; §08). |
| **Z-read / reconcile** | End-of-shift finalisation that computes and posts the cash variance to the GL (UC-B6; §08). |
| **Variance** | Counted cash minus expected cash, computed **server-side** at close/reconcile. |
| **Ring (a sale)** | Composing a cart and calling `POST /pos/sales` to create a finalised, fully-paid CASH invoice (UC-C1; §09). |
| **`SalesInvoiceDto`** | The finalised-invoice payload returned by `POST /pos/sales`; the receipt of record (§09). |
| **Tender** | The payment method/amount. POS now accepts an optional multi-tender `tenders[]` list — CASH/CARD/MOBILE_MONEY/CHEQUE, split, sum ≥ gross (§12 #3, CLOSED `f08fb08`); omitting it falls back to the legacy single-cash behaviour. |
| **`tenderedAmount`** | Cash presented by the customer; a **receipt-printing aid**, not stored on the invoice; change is computed client-side (§12 #3). |
| **`unitPrice`** | A line field that is **optional and ignored** by the server (its `@NotNull` was relaxed in `f08fb08`); pricing is server-authoritative by design (§12 #4). |
| **`lineDiscountAmount`** | The **only** honoured per-line price reduction at the POS (§12 #4; UC-C4). |
| **Idempotency** | Guarantee that a retried request has no extra effect. The API now provides it for sale creation via the optional `Idempotency-Key` header (§12 #1, CLOSED `f08fb08`); omitting the header leaves the client to own retry safety ([`../11 §4`](../11-errors-offline-idempotency.md)). |
| **`X-Request-Id`** | A correlation/log id echoed by the server; **not** used for de-duplication ([`../11 §4.1 #4`](../11-errors-offline-idempotency.md)). |
| **Client transaction id** | A durable, locally-generated id for one logical sale, reused as `X-Request-Id` across attempts so duplicates are findable (PRIN-7). |
| **`X-Branch-Uid`** | Optional per-request header to act in a non-default branch without re-login (§02). |
| **`ApiResponse<T>` envelope** | `{ data, errors, meta }` wrapping every JSON response; success has `errors: []` (§00 §3). |
| **DIRECT invoice** | A POS sale produces a DIRECT-origin invoice with **no delivery**, which is why `/sales-returns` cannot process it (§12 #2). |
| **Outbox poller** | The server's ~1s async worker that applies stock issue + GL + AR **after** the `201` returns ([`../09`](../09-sales-payments-receipts.md)). |
| **MoSCoW** | Prioritisation: Must / Should / Could / Won't-for-v1. |
| **UC-xx** | A use case in [`../use-cases/`](../use-cases/README.md) (e.g. UC-C1 = ring a simple cash walk-in sale). |
| **§nn** | A section of the API reference [`../00`](../00-overview-and-conventions.md)–[`../12`](../12-known-limitations.md). |

---

*End of Part 1. Subsequent parts cover detailed functional requirements (provisioning, shift
lifecycle, selling, receipts, exceptions & offline), non-functional requirements, and the phased
roadmap with explicit backend dependencies.*
