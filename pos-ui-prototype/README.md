# OrbixPOS — UI prototype

A **static, click-through mock** of the POS client UI — pure HTML/CSS/JS, **no API, no build step,
no dependencies**. The point is to *see and refine the UI* before we build the real app in **Flutter**.

> Grounded in `docs/integration/pos/prd/` (Parts 1 & 2). All data on screen is **mock**. Nothing is
> posted anywhere. Money/VAT shown are illustrative previews — in the real client the **ERP is
> authoritative** for price, VAT and totals (PRIN-1/2).

## How to open

Just open **`index.html`** in any modern browser (double-click it, or drag it into a browser tab).
No server needed.

## Business modes

The shell adapts to three retail verticals — pick one on the open-shift screen, or flip live with the
top-bar switcher (🛒 / 🍽 / 💊). All three share the same payment, receipt, session, X/Z-read and
reconcile flows; only the **register** changes.

| Mode | Register | Tuned for |
|---|---|---|
| 🛒 **Supermarket** | **75%** Excel-style line grid (gridlines, one datum per column, scan/search to add, inline Qty/Disc, **X** remove + **V** void columns) + **25% keypad** with three jobs — **× Qty** multiplier, **PLU/code** add, **Tender** (cash → change) — plus docked Total + Pay | Fast, scanner-first grocery checkout |
| 🍽 **Restaurant** | **Tables / floor** picker + menu by **course** + **order ticket** + **Send to kitchen** + split bill | Table service, covers, course ordering |
| 💊 **Pharmacy** | **Patient / Rx** header + drug search + dispensing table with **batch · expiry** + ℞ flags | Dispensing with batch/expiry & prescription capture |

## The flow (click through it)

1. **Login** → Sign in (any values; it's a mock).
2. **Open shift** → choose a **business mode**, pick a till, set the opening float → **Open session**.
   You can also switch modes live from the top-bar switcher at any time.
3. **Register** (the main screen — varies by mode):
   - *Supermarket*: scan/type to add rows to an **Excel-style grid** (75%); select a row, then use the
     **number pad** (25%) with ×Qty / −Disc, or type directly in a cell. The dark panel shows live
     totals + Pay. Line total is VAT-inclusive, so the rows sum to the grand total.
   - *Pharmacy*: scan or type to add **line rows** with **batch · expiry** and ℞ flags; the right panel
     shows the running bill.
   - *Restaurant*: tap a **table**, build the **order ticket** from the menu, **Send to kitchen**.
   - Live **preview totals** (net / VAT / discount / total) — labelled as a preview.
   - **Pay** → payment modal.
4. **Payment** → keypad + quick-cash; pick a **tender** (Cash / Card / Mobile / Cheque), **add
   tender** (supports **split**), see change → **Complete sale**.
5. **Receipt** → printed-style receipt; **Reprint**, **Gift receipt**, **Refund / reverse**.
6. **Session menu** (☰ top-right) → **X-read**, **Cash payout**, **Today’s sales** (reprint),
   **Close session** (count drawer → variance), **Reconcile / Z-read** (supervisor).

## Screens ↔ PRD requirements

| Screen / element | PRD |
|---|---|
| Login | FR-AUTH-1 |
| Open shift: till picker (ACTIVE only) + opening float | FR-SHIFT-1, FR-SHIFT-4 |
| Register: product grid, search, barcode | FR-CAT-1/3/4, FR-SELL-1 |
| Cart: qty/discount/remove, preview total | FR-SELL-1/2/4, FR-PRICE-1 |
| Customer chip + picker (walk-in default) | FR-CUST-1/2 |
| Currency shown on totals | FR-PRICE-3 |
| Payment: cash keypad + change | FR-PAY-1 |
| Payment: card / mobile / cheque / **split** tender | (now supported — POS multi-tender) |
| Receipt from finalised invoice; reprint; gift | FR-RCPT-1/2/3 |
| Refund / reverse a sale | (now supported — POS reverse endpoint) |
| Cash payout (drawer / refund) | FR-SHIFT-6, FR-RET-1 |
| X-read (drawer report) | FR-SHIFT-8, FR-RPT-1 |
| Close session (count → variance) | FR-SHIFT-9 |
| Reconcile / Z-read (supervisor) | FR-SHIFT-10, FR-RPT-2 |
| Today’s sales lookup + reprint | FR-RPT-3, FR-RCPT-2 |

## Notes for refinement

- This prototype **reflects the post-gap-closure backend**: it shows **multi-tender** and
  **refund/reverse**, which the PRD originally lists as *Won't-for-v1* (§12 #2/#3) but which we have
  since implemented (`ADR-0042`). If you want a strict cash-only v1 mock, we hide the extra tenders
  and the refund button — say the word.
- Server-authoritative discipline (PRIN-1/2) is represented by the “preview” labels; the Flutter
  build must treat the `SalesInvoiceDto` as the receipt of record.
- Files: `index.html` (structure), `styles.css` (design system), `app.js` (mock data + interactions).
  All three are small and meant to be edited freely as we iterate.

## What this is **not**

No authentication, no network calls, no persistence, no real catalogue, no peripherals. It is a
visual/interaction sketch only — the contract for the Flutter implementation is the **PRD + API
reference**, not this mock.
