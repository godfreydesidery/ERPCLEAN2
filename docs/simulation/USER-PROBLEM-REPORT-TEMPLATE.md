# User Problem Report (UPR)

> **What this is.** The form a Tembo Group staff member fills in when a screen on the
> system **stops them**, **confuses them**, or **gives a result that looks wrong**.
> It is written in everyday work language — *not* a technical bug report. You do not
> need to know anything about computers, error codes or "what went wrong inside the
> machine." Just tell us, in your own words, **what you were trying to do** and
> **what the screen did instead.**
>
> *Kila tembo na mzigo wake* — when one elephant's load slips, the whole line slows.
> If a screen is blocking you, raise it. A small report from you saves the next person
> the same trouble.

---

## How to fill this in (read once)

- **Write like you would tell a colleague.** "I clicked Save and nothing happened" is
  perfect. You don't need to explain *why* — that's our job.
- **Do not copy long red messages word-for-word if they look like computer code.** If
  there is a short, plain message or a reference number on the screen, write that down
  (the "On-screen reference or number" box).
- **One problem per report.** If two different screens trouble you, file two reports.
- **A picture helps a lot.** If you can, take a screenshot (or a photo of the screen
  with your phone) and note its file name in the "Screenshot reference" box.
- **Be honest about how badly it stopped you.** That tells us what to fix first.

---

## The form

Copy the block below into a new report and fill every line. Leave a line blank only if
you truly cannot answer it.

```
UPR-ID:                  (we fill this — leave blank, or use the next number in the register)
Date:                    (the day you hit the problem, e.g. 2026-06-28)

Reporter
  Name:                  (your full name)
  Designation:           (your job title, e.g. Accountant)
  Branch:                (the branch you were working in, e.g. Dar es Salaam HQ)

Role / permissions:      (your system role, e.g. Accountant / Sales Officer.
                          If you don't know, write "not sure")

What I was trying to do:
  (Plain words. e.g. "Enter the supplier invoice from Mbasha Holdings for the
   palm oil delivery.")

What I expected to happen:
  (What you thought the screen would do. e.g. "It would save and show the
   invoice in my list.")

What actually happened:
  (What the screen did instead. e.g. "I pressed Save and a message in red
   appeared and the invoice did not save.")

Screen / menu path:
  (How you got to the screen. e.g. "Purchases > Supplier Invoices > New".
   If you're not sure of the names, describe it: "the page where I type
   in supplier bills.")

On-screen reference or number:
  (Any short message, code, document number, or reference the screen showed.
   e.g. invoice no. SI-2026-0148, or the words "Amount does not match".
   Write "none shown" if there was nothing.)

Severity (tick ONE, in plain terms):
  [ ] Can't do my job   — I am completely blocked; I cannot finish this task at all.
  [ ] Slows me down     — I found a way around it, but it wastes my time / extra steps.
  [ ] Annoying but I coped — It bothered or confused me, but I got the work done.

Screenshot reference:
  (File name or note, e.g. "amina-supplier-invoice-error.png", or
   "photo sent to IT on WhatsApp 28-Jun". Write "none" if you have no picture.)

How often:
  (e.g. "every time I try", "happened twice this week", "only this once so far",
   "happens with big invoices only".)
```

---

## Field guide (what each line means)

| Field | What we need from you |
|---|---|
| **UPR-ID** | A short label so we can track your report (e.g. `UPR-014`). Leave blank — the person who logs it in the register fills it in. |
| **Date** | The day the problem happened. |
| **Reporter** | Your name, your job title, and the branch you were sitting in. |
| **Role / permissions** | What the system lets you do (your role). Helps us tell whether a screen is *broken* or simply *not allowed for your role*. |
| **What I was trying to do** | The real work task, in business words. |
| **What I expected to happen** | What a working screen should have done. |
| **What actually happened** | What the screen actually did — the stuck button, the red message, the wrong total. |
| **Screen / menu path** | Where you were, so we can go to the same place. |
| **On-screen reference or number** | A document number, customer/supplier name, amount, or short message visible on screen. **Never a password.** |
| **Severity** | How much it stopped you — pick one of the three plain choices. |
| **Screenshot reference** | A picture of the screen is the single most useful thing you can attach. |
| **How often** | Whether it happens always, sometimes, or only once. |

> **Please never write in a report:** your password, anyone else's password, a customer's
> full bank or card number, or a long block of computer code. A document number, a name,
> an amount and a screenshot are all we need.

---

## Worked example 1 — Supplier invoice won't save

```
UPR-ID:                  UPR-007
Date:                    2026-06-28

Reporter
  Name:                  Amina Mwanga
  Designation:           Accountant
  Branch:                Dar es Salaam HQ

Role / permissions:      Accountant

What I was trying to do:
  Mbasha Holdings delivered the crude palm oil last week and Saidi at the
  store already received it. I was entering Mbasha's supplier invoice so we
  can pay them. I matched it to their delivery and typed the amount from
  their bill, 4,720,000 TZS including VAT.

What I expected to happen:
  When I pressed Save, the invoice should be saved and appear in my list of
  supplier invoices waiting for Grace to approve for payment.

What actually happened:
  When I pressed Save, nothing was saved. A short message in red showed at the
  top of the page. I tried three times. Each time it cleared the amount box I
  had filled and I had to type the figure again. The invoice is still not in
  my list, so I cannot send it to Grace and Mbasha keep calling about their
  payment.

Screen / menu path:
  Purchases > Supplier Invoices > New (the page where I enter supplier bills
  against a goods receipt).

On-screen reference or number:
  Goods receipt I was matching: GRN-2026-0312 (Mbasha Holdings).
  The red message said something like "Invoice amount does not match the
  received goods." There was no other code shown.

Severity:
  [x] Can't do my job   — I am completely blocked; I cannot finish this task at all.
  [ ] Slows me down
  [ ] Annoying but I coped

Screenshot reference:
  amina-supplier-invoice-mbasha-error.png (saved on my desktop, also sent to
  IT on email)

How often:
  Every time I try, today. It happens on this Mbasha invoice for sure. I have
  not tried a different supplier yet.
```

---

## Worked example 2 — POS won't let me finish a counter sale

```
UPR-ID:                  UPR-008
Date:                    2026-06-28

Reporter
  Name:                  Sabina Aloyce
  Designation:           Salesperson
  Branch:                Dar es Salaam HQ

Role / permissions:      Sales Officer

What I was trying to do:
  A walk-in customer at the Dar counter was buying 3 bags of sugar and 2 cartons
  of Tembo Cooking Oil. I rang the items up on the counter sale screen and went to
  take the cash and print the EFD receipt.

What I expected to happen:
  After I press "Take payment" and enter the cash, the sale should complete, the
  stock should come down, and the EFD fiscal receipt should print for the customer.

What actually happened:
  I pressed "Take payment" and the screen just spun / stayed on the same page. The
  receipt did not print. The customer was waiting with the money. I had to cancel
  the whole sale and ring it up again from the start — the second time it went
  through and printed. It cost me about five minutes with a queue building behind
  her.

Screen / menu path:
  Sales > Counter Sale (POS) — the till screen I use for walk-in customers.

On-screen reference or number:
  The sale I had to cancel showed number POS-2026-1190. No clear message appeared,
  the screen just did not move on.

Severity:
  [ ] Can't do my job
  [x] Slows me down     — I found a way around it (cancel and redo), but it wastes
                          my time and looks bad with customers waiting.
  [ ] Annoying but I coped

Screenshot reference:
  none — I could not take a picture with the customer waiting. I can show the
  cancelled sale POS-2026-1190 if that helps.

How often:
  Happened twice this week, both times at busy moments in the morning. Most sales
  are fine; it seems to be when the counter is busy.
```

---

## UPR Register (team appends here)

> Log every report received in this table. Give each a running `UPR-ID`, note who
> raised it and on which screen, the severity in plain terms, the current status, and
> the technical issue/ticket it was linked to once IT picked it up.

| UPR-ID | Reporter | Screen | Severity | Status | Linked Issue |
|---|---|---|---|---|---|
| UPR-007 | Amina Mwanga | Purchases > Supplier Invoices > New | Can't do my job | Open | — |
| UPR-008 | Sabina Aloyce | Sales > Counter Sale (POS) | Slows me down | Open | — |
|  |  |  |  |  |  |

**Status values:** `Open` · `Being looked at` · `Fixed — please re-test` · `Closed` ·
`Not a fault (how it's meant to work)`.

**Severity values:** `Can't do my job` · `Slows me down` · `Annoying but I coped`.
