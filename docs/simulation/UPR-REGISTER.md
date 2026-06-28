# UPR Register — 2026-06-28 Simulation Run

These are the User Problem Reports (UPRs) that Tembo Group staff filed after using the
system on **2026-06-28**. The data was entered through the UI and the problems were captured
live as people hit them during their normal work. Each report below is reproduced verbatim as
it was filed; the consolidated **UPR Register** table follows at the end.

---

## UPR-001 — Counter sale (POS) till won't open — says I don't have permission

```
UPR-ID:                  UPR-001
Date:                    2026-06-28

Reporter
  Name:                  Sabina Aloyce
  Designation:           Salesperson
  Branch:                Dar es Salaam HQ

Role / permissions:      Sales Officer

What I was trying to do:
  A walk-in customer came to the Dar counter wanting to buy. I opened the
  counter sale (POS) till screen to ring up the items, take her cash and
  print her EFD receipt — the normal everyday thing I do at the counter.

What I expected to happen:
  The till screen should open and load ready for me to start the sale —
  pick the items, enter quantities, take the payment and print the fiscal
  receipt. It usually opens straight away.

What actually happened:
  The till would not open at all. Instead of the sale screen, I was told I
  do not have permission, and the page would not load. I could not even
  start ringing up the sale — there was nothing to type into. The customer
  was standing right there with her money and I had to apologise and send
  her away because I could not serve her on the system.

Screen / menu path:
  Sales > Counter Sale (POS) — the till screen I use for walk-in customers
  (the address is /admin/pos/sell).

On-screen reference or number:
  No sale number, because the screen never opened. The only thing shown was
  a "permission" / "403" reference. No customer or document number — I never
  got that far.

Severity (tick ONE, in plain terms):
  [x] Can't do my job   — I am completely blocked; I cannot finish this task at all.
  [ ] Slows me down     — I found a way around it, but it wastes my time / extra steps.
  [ ] Annoying but I coped — It bothered or confused me, but I got the work done.

Screenshot reference:
  none — the customer was waiting and I could not stop to take a picture.
  The screen just showed the permission message; I can show it again if the
  team comes to the counter.

How often:
  Every time I try today. The till has not opened once — I cannot get onto
  the counter sale screen at all this morning.
```

---

## UPR-002 — Cannot open Work Orders, Bills of Materials or Products — every screen says I am not allowed

```
UPR-ID:                  UPR-002
Date:                    2026-06-28

Reporter
  Name:                  Editha Mhagama
  Designation:           Production Manager
  Branch:                Dar es Salaam HQ

Role / permissions:      Production Officer (I run the factory — work orders,
                          recipes/BOMs, and receiving finished goods into the
                          Dar store).

What I was trying to do:
  I was starting my morning on the factory floor. First I wanted to open my
  Work Orders to release a batch of Tembo Cooking Oil, then check the recipe
  (Bill of Materials) for the palm oil, bottles, caps and labels, and look up
  the products themselves. So I went to open three screens in turn:
    1. Work Orders
    2. Bills of Materials
    3. the Products list
  These are the everyday screens I must use to run production.

What I expected to happen:
  Each screen should open and show me my work — the list of work orders for the
  Dar factory, the material recipes, and the products we make — so I can release
  an order and get the line started.

What actually happened:
  None of the three screens would open for me. Every single one told me I am not
  allowed to see it — it would not let me in at all. I could not even reach the
  list of work orders, I could not open a single recipe, and I could not open the
  product list. I am completely shut out of the screens I need to run the factory.
  This is not one screen — it is all three of my main production screens at once.

  This is not only me. Editrude Mwakalukwa, my Production Supervisor (same
  Production Officer role as me), tried Work Orders and Bills of Materials on her
  own login and was blocked in exactly the same way — told she is not allowed.
  So the whole production team is locked out, not just one person.

Screen / menu path:
  - Work Orders          (Manufacturing > Work Orders)
  - Bills of Materials   (Manufacturing > Bills of Materials)
  - Products list        (the page that lists the products we make)

On-screen reference or number:
  No friendly message — the screens simply refused to load and showed a
  "not allowed" / access-refused result. For the technical team, when they
  open Work Orders or the Products list, the page is being refused permission
  (an access-refused / "403" type block). No document or batch number, because
  I never got far enough to start any work order.

Severity:
  [x] Can't do my job   — I am completely blocked; I cannot finish this task at all.
  [ ] Slows me down
  [ ] Annoying but I coped

  The factory cannot start. I cannot release a work order, I cannot check a
  recipe, I cannot see the products — production is at a standstill until this
  is opened up for the Production Officer role.

Screenshot reference:
  none yet — the screen blocked me before I could do anything. I can take a
  photo of the "not allowed" page on Work Orders if it helps, and Editrude
  can do the same.

How often:
  Every time, today — on all three screens, every attempt. Editrude gets the
  same on Work Orders and Bills of Materials every time too.
```

---

## UPR-003 — Locked out of my own stock screens — can't see on-hand, can't start a count, can't raise a transfer

```
UPR-ID:                  UPR-003
Date:                    2026-06-28

Reporter
  Name:                  Frank Materu
  Designation:           Stores / Warehouse Supervisor
  Branch:                Dar es Salaam HQ

Role / permissions:      Stores Supervisor (stock for the Dar warehouse)

What I was trying to do:
  My normal morning work in the warehouse, three jobs one after another:
  1. Open the stock on-hand screen to check what we actually hold for a few
     lines (Cement, the LED televisions, the imported wheat flour) before
     Saidi starts receiving today's container.
  2. Start a stock count so Saidi and I can count a section of the floor and
     compare it against the system.
  3. Raise an inter-branch transfer to send some stock from the Dar warehouse
     up to one of the other branches.

What I expected to happen:
  These are the everyday screens I run this warehouse from. I expected each one
  to open normally so I can see my quantities, kick off a count, and fill in a
  transfer. I am the stores supervisor for Dar — this is exactly my job.

What actually happened:
  All three screens refused me. Each one tells me I am not permitted / not
  allowed to open it. I cannot see the stock on-hand at all, I cannot start a
  stock count, and on the transfer screen I cannot even pick the products or the
  branch to send to — it blocks me before I can do anything. So I am standing in
  my own warehouse with no way to check stock, no way to count it, and no way to
  move it. Saidi Karume, my storekeeper, hit the very same wall on the stock
  on-hand screen and on the stock count screen, so it is not just my login.

Screen / menu path:
  Three screens, all under Stock:
  - Stock > Stock on-hand
  - Stock > Stock counts > Create (start a count)
  - Stock > Stock transfers > Create (raise an inter-branch transfer)

On-screen reference or number:
  No document number — I never got far enough to create one. The screens just
  say I am not permitted / not allowed. (On-screen reference only: the pages were
  blocked while trying to load the branches list and, on the transfer screen, the
  products list — both came back refused.)

Severity:
  [x] Can't do my job   — I am completely blocked; I cannot finish this task at all.
  [ ] Slows me down
  [ ] Annoying but I coped

Screenshot reference:
  none yet — I can take pictures of all three "not permitted" screens this
  afternoon and send them to IT if that helps.

How often:
  Every time, on all three screens, for both me and Saidi. It is consistent —
  not a one-off. It has blocked my whole stock morning.
```

---

## UPR-004 — Cannot open Record Receipt or Record Payment — both say I'm not allowed

```
UPR-ID:                  UPR-004
Date:                    2026-06-28

Reporter
  Name:                  Grace Mhina
  Designation:           Finance Director (CFO)
  Branch:                Dar es Salaam HQ

Role / permissions:      Finance Director — full finance across all branches (GL, AR, AP, cash & bank, tax). I approve and release payments and receipts, so these are squarely my screens.

What I was trying to do:
  Two ordinary finance tasks. First, record a customer's payment against their account — open Record Receipt, pick the customer, and enter the money they paid us. Second, pay a supplier — open Record Payment and enter the payment going out. Both are everyday cashier-and-finance work and both are inside my remit.

What I expected to happen:
  The Record Receipt screen should open so I can enter the customer's payment and post it to their account. The Record Payment screen should open so I can enter the supplier payment. Plain and simple — the screens should let me in and let me do the work.

What actually happened:
  Neither screen would open. When I clicked into Record Receipt, the page told me I am not allowed to use it. The same on Record Payment — it also said I am not permitted. I am the Finance Director; recording receipts and paying suppliers is exactly my job, so being told I'm not allowed makes no sense. I am completely blocked — I cannot take in a customer payment and I cannot pay a supplier through these screens.

  This is not just me. John Komba, our Cashier, hit the very same wall on both Record Receipt and Record Payment — he is locked out the same way, and taking receipts is his daily duty. So between us, money cannot move in or out through these two screens at all.

Screen / menu path:
  Accounts Receivable > Record customer receipt (the page where we enter money a customer has paid us), and Accounts Payable > Record supplier payment (the page where we enter a payment going out to a supplier).

On-screen reference or number:
  No document or batch number — I never got far enough to start one, because the screens refused to open. The only thing shown was a message that I am not allowed / not permitted to use the screen. (For the technical team's reference only, a small note about a "wht/types" request returning a "403" flashed up — I don't understand it, but I'm passing it along in case it helps them.)

Severity:
  [x] Can't do my job   — I am completely blocked; I cannot finish this task at all. Neither I nor the Cashier can record a customer receipt or pay a supplier from these screens.
  [ ] Slows me down
  [ ] Annoying but I coped

Screenshot reference:
  none — I can reproduce it on demand for IT and show the "not allowed" message on both screens whenever they're ready.

How often:
  Every time, both screens, for both of us. It is not intermittent — Record Receipt and Record Payment refuse to open every single time we try, for me (Finance Director) and for John Komba (Cashier).
```

---

## UPR-005 — Enter Supplier Bill screen won't open — says I'm not allowed

```
UPR-ID:                  UPR-005
Date:                    2026-06-28

Reporter
  Name:                  Amina Mwanga
  Designation:           Accountant
  Branch:                Dar es Salaam HQ

Role / permissions:      Accountant

What I was trying to do:
  Mbasha Holdings Ltd delivered goods into our HQ warehouse and the store
  already received them. Their invoice is on my desk, so I went to enter the
  supplier bill against that goods receipt — book Mbasha's bill so we can
  pay them. To start, I went to the Enter Supplier Bill screen, where I would
  normally pick the supplier and match their bill to the posted goods receipt.

What I expected to happen:
  The Enter Supplier Bill screen should open and let me pick Mbasha Holdings,
  find their goods receipt, and start typing in the invoice number, date and
  amounts the way I do every time.

What actually happened:
  The screen would not open at all. Instead of the form, it told me I am not
  allowed to use it. I could not even get in to start the bill — so I cannot
  book Mbasha's invoice or send it on to Grace for payment. This is a normal
  part of my job as the accountant, so being told I'm not allowed surprised me.
  My Finance Director, Grace Mhina, tried the very same screen and was blocked
  in exactly the same way, so it is not just my own login.

Screen / menu path:
  AP > Supplier Bills > Enter Supplier Bill (the page where I enter supplier
  bills against a goods receipt).

On-screen reference or number:
  No bill or document number — I never got into the screen. It showed a
  "not allowed" / "you can't open this" message (on-screen reference: 403).
  Supplier I was trying to bill: Mbasha Holdings Ltd.

Severity:
  [x] Can't do my job   — I am completely blocked; I cannot finish this task at all.
  [ ] Slows me down
  [ ] Annoying but I coped

Screenshot reference:
  none — I can show the "not allowed" message again if IT need to see it.

How often:
  Every time I try, today. It happens for me and for Grace Mhina, on this same
  Enter Supplier Bill screen.
```

---

## UPR-006 — Can't save a new RETAIL price list — it gets rejected with no clear reason

```
UPR-ID:                  UPR-006
Date:                    2026-06-28

Reporter
  Name:                  Sabina Aloyce
  Designation:           Salesperson
  Branch:                Dar es Salaam HQ

Role / permissions:      Sales Officer

What I was trying to do:
  I was setting up our price lists so I have the right selling prices to
  use at the Dar counter. I went to add a new price list called RETAIL
  for our walk-in and shop-counter customers, filled in the name and the
  details, and pressed Save.

What I expected to happen:
  It should save the RETAIL price list and show it in my list of price
  lists, ready for me to start putting prices against it.

What actually happened:
  When I pressed Save it did not save. It looked like the screen pushed
  it back / rejected it — the price list was not added to my list. There
  was no plain message telling me what was wrong or what to do, so I was
  left guessing. I tried again and it still would not go through. I could
  not finish setting up the RETAIL list the way I wanted.

Screen / menu path:
  Price lists (the page where I create and manage our selling price lists)
  > New, while working in Dar es Salaam HQ.

On-screen reference or number:
  Price list name I was entering: RETAIL. No clear plain message came up
  on screen. (If it helps the team, what showed was a short "conflict"
  type rejection — nothing that told me, in words, what the clash was.)

Severity (tick ONE, in plain terms):
  [ ] Can't do my job   — I am completely blocked; I cannot finish this task at all.
  [x] Slows me down     — I found a way around it, but it wastes my time / extra steps.
  [ ] Annoying but I coped

  (I marked "Slows me down": I could not create the new RETAIL list, but
   it seems a RETAIL price list may already be there that I can fall back
   on. The problem is the screen never told me that — it just refused, so
   I lost time figuring out whether something was broken or whether I had
   done it wrong. A clear message like "A RETAIL price list already exists"
   would have saved me the trouble.)

Screenshot reference:
  none — I can show the Price lists screen and re-do the steps if the team
  wants to see the rejection for themselves.

How often:
  Every time I try to add a RETAIL price list today. It happens on RETAIL
  for sure; I have not tried other names yet.
```

---

## UPR Register

> **On the linked issues.** The technical team triaged by *root cause*, not one-Issue-per-UPR:
> the five "screen 403s on a supporting read" reports collapse onto a small set of shared
> Issues (a branch-picker `BRANCH.VIEW` gap, a `PRODUCT.VIEW` gap, a `WHT.VIEW` gap, a
> `PURCHASE.ORDER.VIEW` gap) plus the systemic guard ISSUE-008. See
> [ISSUES-REGISTER.md](ISSUES-REGISTER.md) for the full mapping and Fix Plans.

| UPR-ID | Reporter | Screen | Severity | Status | Linked Issue(s) |
| --- | --- | --- | --- | --- | --- |
| UPR-001 | Sabina Aloyce (Salesperson) | Sales > Counter Sale (POS) — `/admin/pos/sell` | Can't do my job | **Closed — verified** | ISSUE-004 (BRANCH.VIEW) |
| UPR-002 | Editha Mhagama (Production Manager) | Manufacturing > Work Orders / Bills of Materials / Products list | Can't do my job | **Closed — verified** (work-orders/BOMs open; product *create* → ISSUE-006 role-spec) | ISSUE-003, ISSUE-005, ISSUE-006 (PRODUCT/BRANCH.VIEW) |
| UPR-003 | Frank Materu (Stores / Warehouse Supervisor) | Stock > On-hand / Stock counts > Create / Stock transfers > Create | Can't do my job | **Closed — verified** | ISSUE-004, ISSUE-003 (BRANCH/PRODUCT.VIEW) |
| UPR-004 | Grace Mhina (Finance Director / CFO) | AR > Record customer receipt; AP > Record supplier payment | Can't do my job | **Closed — verified** | ISSUE-001 (WHT.VIEW) |
| UPR-005 | Amina Mwanga (Accountant) | AP > Supplier Bills > Enter Supplier Bill | Can't do my job | **Closed — verified** | ISSUE-002 (PURCHASE.ORDER.VIEW) |
| UPR-006 | Sabina Aloyce (Salesperson) | Price lists > New | Slows me down | **Fixed — friendly message** | ISSUE-007 (409 message hygiene) |

> **Closed — verified (2026-06-28):** after the technical team's gate-layer fix (security finding F21,
> no migration), the blocked personas were re-run through the UI — every "Can't do my job" screen now
> opens. Before/after evidence: [run-2026-06-28/rerun-after-fix.json](run-2026-06-28/rerun-after-fix.json);
> resolution detail: [ISSUES-REGISTER.md](ISSUES-REGISTER.md#resolution--fixed--verified-2026-06-28).

All six also feed the systemic **ISSUE-008** (no test asserts a role's grant set is closed over
the reference reads its screens fire).
