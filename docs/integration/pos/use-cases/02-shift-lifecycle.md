# POS Use Cases — Shift Lifecycle (Cash Management)

End-to-end scenarios for the cashier/supervisor shift loop — open a session with a float, work it, then close and reconcile (Z-read) it — grounded in the real session API ([§08 Sessions](../08-sessions.md)).

> **Read first.** These scenarios assume the shared contract: JWT bearer auth, the `{data, errors, meta}` envelope, the `X-Branch-Uid` scope header, and the HTTP error table ([§00 Overview & Conventions](../00-overview-and-conventions.md), [§01 Authentication & Permissions](../01-authentication-and-permissions.md), [§11 Errors, Offline & Idempotency](../11-errors-offline-idempotency.md)). They are not re-derived here.

## Session state model (the spine of every UC below)

A session has exactly three statuses with a one-way flow (`PosSessionStatus`):

```
OPEN  →  CLOSED  →  RECONCILED
```

The status gates which actions are legal. Every state-violating call returns **409 Conflict**:

| Action | Endpoint | Required state | Resulting state |
|--------|----------|----------------|-----------------|
| Open | `POST /api/v1/pos/sessions` | (creates new) | `OPEN` |
| Ring a sale | `POST /api/v1/pos/sales` | **`OPEN`** | unchanged |
| Record payout | `POST .../uid/{uid}/payouts` | **`OPEN`** | unchanged |
| X-read (mid-shift) | `GET .../uid/{uid}/x-read` | **`OPEN`** | unchanged |
| Close | `POST .../uid/{uid}/close` | **`OPEN`** | `CLOSED` |
| Reconcile (Z-read) | `POST .../uid/{uid}/reconcile` | **`CLOSED`** | `RECONCILED` |
| Get / list | `GET .../uid/{uid}`, `GET /api/v1/pos/sessions` | any | unchanged |

Two hard rules to design around (both from `PosSessionServiceImpl`):

- **A till can have at most one `OPEN` session.** Opening a second on the same till → **409** (`"Till <tillUid> already has an OPEN session."`).
- **Once `CLOSED` or `RECONCILED`, the session is read-only.** No more sales, payouts, or X-reads against it — open a fresh session for the next shift.

---

### UC-B1: Open a session with an opening float
- **Actor:** cashier (any user holding `POS.SESSION.OPEN`).
- **Goal:** start a shift by opening a cash session on a chosen till with a declared opening float.
- **Preconditions:**
  - Authenticated; bearer access token valid (TTL 15 min — refresh before it elapses).
  - Holds `POS.SESSION.OPEN`, and the active scope can act in the till's company.
  - A target till exists and is `ACTIVE` (discover via `GET /api/v1/pos/tills?companyId=&branchId=` — [§07](../07-tills.md)).
  - The till has **no** existing `OPEN` session (one-open-per-till rule).
- **Main flow:**
  1. (If the till uid isn't known) list tills and let the cashier pick an `ACTIVE` one: `GET /api/v1/pos/tills?companyId={id}&branchId={id}` ([§07](../07-tills.md)). The list includes `INACTIVE`/`ARCHIVED` tills too — filter client-side on `status == "ACTIVE"`.
  2. Open the session: `POST /api/v1/pos/sessions` ([§08](../08-sessions.md)) with body `{ "tillUid", "openingFloatAmount" }`.
     - `tillUid` — `@NotBlank`; `openingFloatAmount` — `@NotNull`, `@DecimalMin("0.00")` (a `0.00` float is allowed).
  3. On **201 Created**, persist the returned `PosSessionDto.uid` — every sale, payout, X-read, close, and reconcile for this shift references it. Note also `sessionNumber` (e.g. `POS-0001`) for the receipt header, and `status: "OPEN"`.
- **Alternate / exception flows:**
  - **400** — `tillUid` blank, or `openingFloatAmount` null/negative (bean validation).
  - **403** — lacks `POS.SESSION.OPEN`, or cannot act in the till's company.
  - **404** — `tillUid` does not resolve (`NotFoundException.of("PosTill", uid)`).
  - **409** — the till already has an `OPEN` session. Resolve the prior session (close + reconcile it, or have the supervisor do so) before retrying. Find it via `GET /api/v1/pos/sessions?companyId={id}&sort=openedAt,desc` and filter `status == "OPEN"` for that till.
  - **415** — body not `application/json`.
- **Outcome:** a new session in `OPEN` status; `openedAt` and `cashierId` are **server-stamped** (the caller's user id) — the client never sends them. The till is now locked to this one open session. The session is ready to ring sales.
- **Notes & limitations:**
  - `cashierId` is always the calling user — there is no "open on behalf of another cashier".
  - `sessionNumber` and `openedAt` are generated server-side; do not attempt to set them.
  - No per-shift "float top-up" / cash-in endpoint exists: the float is fixed at open. Mid-shift cash movements are **outflows only** (see UC-B3) — there is no `CASH_IN` payout type.

---

### UC-B2: View the current / open session
- **Actor:** cashier or shift supervisor (holding `POS.SESSION.VIEW`).
- **Goal:** retrieve the current session's state (status, float, computed amounts) to resume a shift after an app restart or to confirm what's open on a till.
- **Preconditions:** authenticated; holds `POS.SESSION.VIEW`; active scope can act in the session's company.
- **Main flow (you already hold the uid):**
  1. `GET /api/v1/pos/sessions/uid/{uid}` ([§08 §4](../08-sessions.md)) → **200** with the `PosSessionDto` reflecting current state (e.g. `closedAt`/`varianceAmount` are populated only once the session has progressed; `null` while `OPEN`).
- **Main flow (you need to discover the open session, e.g. after a crash):**
  1. `GET /api/v1/pos/sessions?companyId={id}&sort=openedAt,desc` ([§08 §5](../08-sessions.md)) — **paged**: `companyId` is a required query param; `data` is the array, `meta` carries page info.
  2. Filter the returned page for `posTillId == <your till>` and `status == "OPEN"`; recover its `uid` and resume.
- **Alternate / exception flows:**
  - **400** (list) — `companyId` missing or non-numeric.
  - **403** — lacks `POS.SESSION.VIEW`, or cannot act in the session's/company's scope.
  - **404** (get-by-uid) — unknown session uid (`NotFoundException.of("PosSession", uid)`).
  - **Empty state** (list) — no sessions match → `data: []`, `meta` present, HTTP **200** (not a 404).
- **Outcome:** read-only; nothing changes. You have the authoritative session state and uid to continue the loop.
- **Notes & limitations:**
  - There is no "get the open session for till X" convenience endpoint — discovery is list-then-filter (or hold the uid client-side from UC-B1).
  - Amounts like `expectedCashAmount`/`varianceAmount` are `null` until the session is closed (UC-B5) — for live running totals during the shift, use the X-read (UC-B4), not get-by-uid.

---

### UC-B3: Record a cash payout / drawer drop mid-shift
- **Actor:** cashier (or supervisor) holding `POS.SESSION.OPEN`.
- **Goal:** record cash leaving the drawer during the shift (a drawer-to-safe drop, a petty payout, or ad-hoc cash handed back that is **not** tied to reversing a sale) so the expected-cash figure stays accurate. To actually refund a POS sale, use UC-B9 (`/reverse`), not a payout.
- **Preconditions:**
  - Authenticated; holds `POS.SESSION.OPEN` (there is **no** separate payout permission — the same code that opens a session authorises payouts), scoped to the session.
  - The session is **`OPEN`** (payouts are illegal once `CLOSED`/`RECONCILED`).
- **Main flow:**
  1. `POST /api/v1/pos/sessions/uid/{uid}/payouts` ([§08 §6](../08-sessions.md)) with body `{ "payoutType", "amount", "reason" }`.
     - `payoutType` — `@NotNull`, one of `PAID_OUT` (drawer-to-safe / petty payout) or `REFUND` (ad-hoc cash returned to a customer — drawer bookkeeping only; this does **not** reverse a sale, see UC-B9).
     - `amount` — `@NotNull`, `@DecimalMin("0.01")` (strictly positive).
     - `reason` — optional, `@Size(max = 255)`.
  2. On **200 OK** the body is `{ "data": null, ... }` — the handler returns `void`. The amount is now subtracted from the session's expected cash.
- **Alternate / exception flows:**
  - **400** — `payoutType` null/invalid, `amount` null or `< 0.01`, or `reason` over 255 chars.
  - **403** — lacks `POS.SESSION.OPEN`, or cannot act in the session's company.
  - **404** — unknown session uid.
  - **409** — session is not `OPEN` (`"Session <sessionNumber> is not OPEN."`).
  - **415** — body not `application/json`.
- **Outcome:** every payout **reduces** expected cash (`expectedCashAmount = openingFloat + cashSales − totalPayouts`). The reduction is visible immediately on the next X-read (UC-B4) and is baked into the close/reconcile math (UC-B5/B6).
- **Notes & limitations:**
  - **Payouts are cash-drawer bookkeeping only.** A `REFUND` payout records cash leaving the till but posts **no** stock-in, no VAT/revenue reversal, and no AR credit. It does **not** undo a sale. To actually reverse an OPEN-session POS sale (stock + VAT + revenue + cash), use the dedicated whole-sale void/refund `POST /api/v1/pos/sales/uid/{uid}/reverse` (`POS.SALE.VOID`) instead — see UC-B9 and [§12 #2 (whole-sale reversal — CLOSED)](../12-known-limitations.md#2-whole-sale-pos-reversal--refund--void--closed-reverse-endpoint) (commit `f08fb08`, ADR-0042). The `REFUND` payout is now for **ad-hoc cash-out not tied to a sale** (or a sale whose session has already closed); using it to refund an open-session sale leaves the ledger overstated and needs a back-office correcting entry.
  - Both enum values are **outflows**; there is no cash-in / float-top-up type. To add cash to a drawer you must open a new session with a larger float — there is no mid-shift increase.
  - This endpoint is **not idempotent** — a blind retry records a second payout. Confirm success before resending ([§11](../11-errors-offline-idempotency.md)).

---

### UC-B4: Run a mid-shift X-read (X report)
- **Actor:** cashier or shift supervisor holding `POS.SESSION.VIEW`.
- **Goal:** pull a non-resetting snapshot of the drawer's running totals (sales, payouts, expected cash) without closing the shift.
- **Preconditions:**
  - Authenticated; holds `POS.SESSION.VIEW`, scoped to the session.
  - The session is **`OPEN`** (X-read is for live sessions; for a finished shift use the Z-read instead — UC-B6).
- **Main flow:**
  1. `GET /api/v1/pos/sessions/uid/{uid}/x-read` ([§08 §7](../08-sessions.md)) → **200** with an `XReadDto`.
  2. Key response fields: `openingFloatAmount`, `totalSalesAmount` (gross cash sales), `totalPayoutsNetAmount` (sum of all payouts), `expectedCashAmount` (`openingFloat + totalSales − totalPayouts`), and `invoiceCount`.
- **Alternate / exception flows:**
  - **403** — lacks `POS.SESSION.VIEW`, or cannot act in the session's company.
  - **404** — unknown session uid.
  - **409** — session is not `OPEN` (already closed/reconciled). Use the reconcile Z-read for a closed session.
- **Outcome:** read-only; the session is unchanged (no reset, no status change). The cashier/supervisor sees what the drawer *should* hold so far.
- **Notes & limitations:**
  - **It's a cash-drawer report, not a ledger report.** `totalSalesAmount`/`invoiceCount` reflect finalised POS invoices tagged to the session (these appear immediately, since invoice finalisation is synchronous), but the downstream stock/GL/AR effects of those sales post **asynchronously** (~1s outbox poller — [§09](../09-sales-payments-receipts.md)). Do not treat the X-read as proof the ledger is posted.
  - The X-read does **not** include a counted-vs-expected variance — that only exists after close (UC-B5), because the cashier hasn't declared counted cash yet.

---

### UC-B5: Close the session (declare counted cash)
- **Actor:** cashier (or shift supervisor) holding `POS.SESSION.CLOSE`.
- **Goal:** end the shift by declaring the physically counted drawer cash; the server computes expected cash and the variance and flips the session to `CLOSED`.
- **Preconditions:**
  - Authenticated; holds `POS.SESSION.CLOSE`, scoped to the session.
  - The session is **`OPEN`**.
  - All sales for the shift have been rung and all mid-shift payouts recorded (closing freezes the cash math; you cannot ring sales or record payouts against a `CLOSED` session afterwards).
- **Main flow:**
  1. Cashier counts the drawer.
  2. `POST /api/v1/pos/sessions/uid/{uid}/close` ([§08 §8](../08-sessions.md)) with body `{ "countedCashAmount", "notes" }`.
     - `countedCashAmount` — `@NotNull` (the physical cash counted); `notes` — optional free text stored on the session.
  3. On **200** the response is the updated `PosSessionDto` with `status: "CLOSED"`, `closedAt`, and the server-computed:
     - `expectedCashAmount = openingFloatAmount + cashSalesTotal − totalPayouts`
     - `varianceAmount = countedCashAmount − expectedCashAmount` (positive = over, negative = short)
- **Alternate / exception flows:**
  - **400** — `countedCashAmount` null.
  - **403** — lacks `POS.SESSION.CLOSE`, or cannot act in the session's company.
  - **404** — unknown session uid.
  - **409** — session is not `OPEN` (already closed/reconciled) — `"Session <sessionNumber> is not OPEN."`.
  - **415** — body not `application/json`.
- **Outcome:** session is `CLOSED`; `countedCashAmount`, `expectedCashAmount`, and `varianceAmount` are populated. **No GL posting happens at close** — the variance journal is deferred to reconcile (UC-B6). `varianceJournalId` is still `null`. The session is now read-only except for reconcile.
- **Notes & limitations:**
  - There is **no "re-open"** for an over-eager close — the flow is one-way (`OPEN → CLOSED → RECONCILED`). If you closed with a wrong count, the variance gets recorded as-is; correct it via the back office, not by re-opening.
  - The close itself is not idempotent in the sense of being repeatable: a second close on the same session returns **409** (it is no longer `OPEN`), so a retry after an ambiguous timeout is naturally rejected — re-fetch the session (UC-B2) to confirm whether the first call landed.

---

### UC-B6: Reconcile (Z-read) and resolve the variance
- **Actor:** shift supervisor / store manager holding `POS.SESSION.RECONCILE` (typically not the cashier — segregation of duties).
- **Goal:** finalise a closed session — post the cash variance to the GL (when non-zero), flip the session to `RECONCILED`, and obtain the Z-read report.
- **Preconditions:**
  - Authenticated; holds `POS.SESSION.RECONCILE`, scoped to the session.
  - The session is **`CLOSED`** (reconcile on an `OPEN` or already-`RECONCILED` session → 409).
  - The company's GL config for the variance accounts (`POS_CASH_OVER`, `POS_CASH_SHORT`) is set up if a non-zero variance is expected (see below).
- **Main flow:**
  1. `POST /api/v1/pos/sessions/uid/{uid}/reconcile` ([§08 §9](../08-sessions.md)) with an optional `{ "notes" }` body (the body has no `@Valid`; `{}` is accepted — but still send `Content-Type: application/json`). **The variance is computed server-side; no amount is accepted from the client.**
  2. The server posts the variance journal **synchronously, fail-fast** (inline in the request transaction, not via the async outbox):
     - **Over** (`varianceAmount > 0`): `DR Cash / CR POS_CASH_OVER`.
     - **Short** (`varianceAmount < 0`): `DR POS_CASH_SHORT / CR Cash`.
     - Currency = company base currency; posting date = the session's `closedAt` date.
     - If `varianceAmount == 0`, no journal is posted and `varianceJournalId` stays `null`.
  3. On **200** the response is a `ZReadDto` carrying the full session totals plus `countedCashAmount`, `expectedCashAmount`, `varianceAmount`, and the resulting `varianceJournalId` (the journal id, or `null` for a zero variance). The session is now `RECONCILED`.
- **Alternate / exception flows:**
  - **403** — lacks `POS.SESSION.RECONCILE`, or cannot act in the session's company.
  - **404** — unknown session uid.
  - **409** — session not `CLOSED` (`"Session must be CLOSED before reconciliation."`). Close it first (UC-B5), or it has already been reconciled.
  - **409** — a **missing GL config** for the variance posting surfaces here as a domain conflict. Because this is a human-initiated command (not the swallow-and-retry outbox), the reconcile **fails fast** rather than completing without the journal — the session stays `CLOSED`. Fix the `POS_CASH_OVER` / `POS_CASH_SHORT` GL config and retry.
  - **415** — body not `application/json`.
- **Outcome:** session is `RECONCILED` (terminal). For a non-zero variance, a balanced GL journal exists and its id is stamped on `varianceJournalId` and echoed in the Z-read; for a zero variance, no journal. The shift is fully accounted: float + cash sales − payouts vs. counted cash, with the difference booked to over/short.
- **Notes & limitations:**
  - **Terminal state.** There is no un-reconcile and no edit after reconcile — any correction is a manual back-office journal.
  - The variance posting is **fail-fast and synchronous**, unlike the sale's stock/GL/AR effects which are eventual ([§09](../09-sales-payments-receipts.md)). A 200 from reconcile means the variance journal is actually posted (or the variance was zero).
  - Reconcile resolves only the **cash variance**. It does not reconcile or reverse any sale-level discrepancy — e.g. cash refunded via an ad-hoc `REFUND` payout (UC-B3) leaves stock/revenue/VAT overstated; that is not something Z-read fixes. The proper way to reverse a sale is the whole-sale void/refund endpoint while its session is still OPEN (UC-B9); once the session has closed, a sale-level correction is a back-office void on `/sales-invoices`.

---

## Edge cases beyond the core shift loop

These are common cash-management needs that sit outside the core shift loop. Most are still **not supported** by the session API (UC-B7, UC-B8, UC-B10) and are listed so a POS builder plans around them rather than discovering the gap at runtime. One — **whole-sale void/refund at the till (UC-B9)** — is now **supported** (commit `f08fb08`, ADR-0042); only its partial / line-level variant remains deferred.

### UC-B7: Float top-up / cash-in mid-shift — **Not supported today**
- **What you'd want:** add cash to the drawer (or correct the opening float) during an open shift.
- **Why not:** the float is fixed at open (UC-B1), and `PosPayoutType` has only **outflow** values (`PAID_OUT`, `REFUND`) — there is no `CASH_IN` type ([§08 §6](../08-sessions.md)).
- **Closest workaround:** close the current session and open a new one with the corrected float, or handle the extra cash as a back-office movement. No reference for an in-session top-up exists.

### UC-B8: Re-open or edit a closed/reconciled session — **Not supported today**
- **What you'd want:** undo a premature close, fix a mis-counted drawer, or reverse a reconcile.
- **Why not:** the lifecycle is strictly one-way (`OPEN → CLOSED → RECONCILED`); every backward transition is rejected with **409**.
- **Closest workaround:** record the correction as a back-office GL journal; do not attempt to mutate the session.

### UC-B9: Void / refund a whole POS sale from the till — **Supported** (commit `f08fb08`, ADR-0042)
- **Actor:** cashier (or supervisor) holding **`POS.SALE.VOID`** (scoped on the invoice `uid`; auto-granted to `ORG_ADMIN`).
- **Goal:** a cashier reverses a wrong or returned sale at the register while the shift is still open.
- **Preconditions (else **409** → back-office void):**
  - The invoice is **POS-origin** (has a `posSessionId`).
  - Its originating **session is still `OPEN`**.
  - The invoice is **FINALISED**.
- **Main flow:**
  1. `POST /api/v1/pos/sales/uid/{uid}/reverse` with body `{ "reason": "<text>" }` (`reason` is `@NotBlank`) → **204 No Content**.
  2. The whole-invoice reversal acts as a **full refund**: it reverses revenue + VAT + cash (a POS cash sale has no AR leg), reverses the stock issue (restoring inventory valuation), and posts **DR Inventory / CR COGS** — see [§12 #2](../12-known-limitations.md#2-whole-sale-pos-reversal--refund--void--closed-reverse-endpoint).
  3. The reversed sale automatically **drops out of the session's expected cash** at X/Z-read — **no separate `REFUND` payout is needed** (and a payout would not reverse the sale anyway; see UC-B3).
- **Limitation — partial / line-level refunds are deferred (ADR-0042):** this endpoint reverses the **whole** sale only. To return some lines, or a partial quantity, use a back-office per-line credit note against the originating invoice — there is no at-till partial refund yet ([§12 #5](../12-known-limitations.md#5-partial--line-level-pos-refunds--deferred)).
- **Closest workaround for a closed session:** if the originating session has already CLOSED/RECONCILED, `/reverse` returns **409** — handle the reversal via the back-office invoice void on `/sales-invoices`, where the cash difference is a reconciled-variance matter rather than a till refund.

### UC-B10: Non-cash or split tender at the till — **Not supported today**
- **What you'd want:** card, mobile-money, cheque, or split (part-cash/part-card) tender on a sale during the shift.
- **Why not:** POS sales are hard-coded to a single exact **CASH** tender for the full gross ([§12 #3](../12-known-limitations.md)); the session cash math (UC-B4/B5/B6) assumes all sales are cash. Non-cash tenders are not modelled in the drawer at all.
- **Closest workaround:** none within POS — cash-only shifts are the supported model today. Non-cash sales must go through the back-office `/api/v1/sales-invoices` flow ([§09](../09-sales-payments-receipts.md)).

---

## End-to-end happy path (recap)

1. **UC-B1** — `POST /api/v1/pos/sessions` `{tillUid, openingFloatAmount}` → 201, session `OPEN`, keep `uid`.
2. (through the shift) ring sales — `POST /api/v1/pos/sales` `{sessionUid, ...}` (session must be `OPEN`; [§09](../09-sales-payments-receipts.md)).
3. **UC-B3** (optional) — `POST .../uid/{uid}/payouts` for drawer drops / ad-hoc cash-out not tied to a sale.
4. **UC-B9** (optional) — `POST /api/v1/pos/sales/uid/{uid}/reverse` `{reason}` → 204 to void/refund a whole sale while its session is OPEN (`POS.SALE.VOID`).
5. **UC-B4** (optional) — `GET .../uid/{uid}/x-read` for a mid-shift cash snapshot.
6. **UC-B5** — `POST .../uid/{uid}/close` `{countedCashAmount, notes}` → 200, session `CLOSED`, variance computed.
7. **UC-B6** — `POST .../uid/{uid}/reconcile` `{notes}` → 200, Z-read returned, variance journal posted (if non-zero), session `RECONCILED`.
