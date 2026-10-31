# 0067 — Restaurant table ownership, transfer, and who is serving

- **Status:** Accepted (2026-10-31). The owner asked for the industry-standard rule after J COB's
  feedback ("a table occupied by waiter A can be served by waiter B — is that proper?"), chose the
  team-service switch as a **till setting** (no schema), and approved the DDL in Appendix A. It
  ships as `V109__restaurant_order_waiter.sql`.
- **Builds on:** ADR-0065 (restaurant orders), ADR-0066 D-8 (waiter on a shared till), ADR-0042
  (step-up authority).

## Context

Until now any waiter could add to, send, void or bill any table. Every report, bill and receipt
credited the waiter who **opened** the order, even for items another waiter added, and nothing
could ever move a table to another waiter: `opened_by` is set once. With the per-waiter reports
(J COB 2026-10-30) that makes per-waiter sales wrong whenever waiters help each other or change
shift.

The standard (Toast, Square, Lightspeed, Micros): the waiter who opens a table owns it; other
waiters see it but do not change it; cashiers and managers work any table; a manager transfers a
table to another waiter. Some restaurants run team service on purpose, so it is a switch.

## Decision

### D-1 — The current waiter

`restaurant_orders.served_by` is the waiter holding the order since the last transfer;
`served_since` when. NULL means the waiter who opened it. `opened_by` never changes and stays the
audit fact. Everything that names "the waiter" — the order, floor tiles, bill, receipt, slips'
order header, Live Orders, the waiter reports — uses the current waiter
(`COALESCE(served_by, opened_by)`). A bill is credited to the waiter holding the table **when it is
paid**; splitting one bill between waiters would need a waiter per item and is out of scope.

### D-2 — Transfer

`POST /restaurant/orders/uid/{uid}/transfer {toWaiterUid, actingWaiterUid?, authorisedByUid?}`,
`RESTAURANT.ORDER.MANAGE`, on an OPEN or BILLED order.

- The new waiter must be an active user holding `RESTAURANT.ORDER.MANAGE` in the order's company
  and assigned to its branch (re-verified on the server, never trusted from the request).
- Authority: a caller acting on their own (no `actingWaiterUid`) who holds `POS.SESSION.OPEN` (a
  cashier) or `RESTAURANT.ORDER.VOID` (a manager) transfers alone. A waiter — signed in on their own,
  or named in `actingWaiterUid` on a shared till — needs a manager's step-up approval for
  `RESTAURANT.ORDER.VOID`, re-verified like a void. No new permission code.
- Audited (`RESTAURANT_ORDER_TRANSFER`, from → to, approver); a refused attempt is recorded
  independently, like a refused void.

### D-3 — Ownership is enforced by the till; team service is a till setting

Owner ruling: the team-service switch is a **till setting**, not a branch record. A server cannot
trust a switch a till sends, so the ownership rule is applied **by OrbixPOS**: when the acting
waiter is not the order's current waiter, the till opens the order read-only with "Served by X —
ask a manager to transfer it to you" (no add, send, fire, void, bill, split, merge, move or
cancel). Cashiers and managers acting on their own work any table. "Team service — any waiter can
serve any table" (managers only, off by default) turns the rule off for that till. Every action is
still audited with who did it; the server enforces the transfer (D-2).

### D-4 — Who is serving: a three-way till setting

ADR-0066 D-8's "Shared till: ask who is serving" becomes **Who is serving?**:

- **Don't ask** — orders go to whoever is signed in (the default).
- **Ask before a new order** — the ADR-0066 behaviour: name + password before a new table or
  takeaway, remembered for 3 idle minutes.
- **Ask every time a table is opened** — name + password on every tap of a table, a takeaway or
  an order in the list; nothing remembered. The signed-in cashier appears as a tile too and also
  confirms with their own password, so the till never guesses who is standing at it.

No schema: it is a device setting (`AppConfig`), like the printers.

## Consequences

- Per-waiter sales follow the waiter who holds the table at payment.
- A waiter who goes home hands their tables over in one step, with a manager's approval.
- The ownership rule can be bypassed by calling the API directly; it is a workflow rule, and every
  action carries who did it. A server-enforced rule would need the switch on the server (a branch
  setting, i.e. schema) — reconsider if a client needs it.

## Appendix A — DDL (approved 2026-10-31)

```sql
SET LOCAL lock_timeout = '10s';
ALTER TABLE restaurant_orders ADD COLUMN served_by    BIGINT;
ALTER TABLE restaurant_orders ADD COLUMN served_since TIMESTAMPTZ;
ALTER TABLE restaurant_orders
    ADD CONSTRAINT fk_restaurant_order_served_by
    FOREIGN KEY (served_by) REFERENCES app_users(id);
```
