# Taking Payment

This chapter covers the moment of truth at the till: turning a basket of items into money in the drawer (or on the card terminal) and a printed receipt in the customer's hand. It explains the **Payment** screen, the four ways a customer can pay, how to take a simple cash sale, how to split one sale across several payments, and — importantly — what to do when you are not sure whether a payment went through.

Everything here works the same way in all three OrbixPOS modes — Supermarket, Pharmacy and Restaurant. The register on the left differs, but the **Payment** screen is identical, because all three share one payment, receipt and session flow.

> Before you can take payment you must be signed in and have an **open** shift (an open session on a till). If you have not opened a shift yet, see the *Starting and Ending a Shift* chapter (Chapter 2). The **PAY** button is greyed out while the basket is empty.

---

## Opening the Payment screen

**What it is.** The **Payment** screen is a single panel that appears on top of your register when you are ready to collect money. On the left it shows the amount the customer owes and a running list of what they have paid so far; on the right it shows a number pad and the buttons that finish the sale.

**Why it exists.** Ringing items and taking money are two separate steps on purpose. You build the basket first, check it with the customer, and only then move money. Keeping payment on its own screen means you can take your time over the amount and the change without accidentally adding more items.

**When it happens.** Once every item is in the basket and the customer is ready to pay.

**How it works.** When you press the green **PAY** button on the register, OrbixPOS opens the **Payment** screen and locks in the current basket total as the amount due. Nothing is sent to the server yet — you are still preparing the payment. The sale is only created when you press **Complete sale**.

To open it:

1. Finish adding items to the basket on the register.
2. Read the total back to the customer.
3. Press the green **PAY** button (bottom-right of the register). The **Payment** screen opens.

> You cannot open **Payment** with an empty basket — the **PAY** button is dimmed and does nothing until at least one line is on the ticket.

---

## What you see on the Payment screen

The **Payment** screen has two halves.

### The left half — what is owed and what is paid

| Area | What it shows |
| --- | --- |
| **AMOUNT DUE** | A large highlighted figure at the top, labelled **AMOUNT DUE** with the currency in brackets (for example **AMOUNT DUE (TZS)**). This is the basket total the customer must cover. |
| Tender-type buttons | A row of four buttons — **Cash**, **Mobile**, **Cheque**, **Card** — used to choose how the *next* payment is being made. The selected one is highlighted. |
| Tender list | The space below the buttons lists each payment you have added so far. Until you add one it reads *"Quick-cash or add a tender →"*. |
| **Total / Paid / Change** | A short summary at the bottom: **Total** (the amount due), **Paid** (what you have entered so far), and **Change** (shown only when the customer has handed over more than the total). |

### The right half — the number pad

| Control | What it does |
| --- | --- |
| Amount display | A dark box at the top showing the figure you are currently typing. It reads **0** until you key something in. |
| Quick-cash buttons | A grid of suggested amounts — for example **Exact**, and rounded notes such as **5,000** or **10,000** — so you can fill in a common cash amount with one tap instead of typing it. |
| Number pad | Keys **0**–**9** and a decimal point for typing an amount. **C** clears the typed amount; the backspace key (⌫) deletes the last digit. |
| **Add tender** | Records the amount you have typed as one payment of the selected type, and adds it to the tender list. Use this for split payments (see below). |
| **Complete sale** | The big green button that finishes the sale, sends it to the server, and shows the receipt. |

> The amount you type with the number pad, the quick-cash buttons, and the **Change** figure are all a **preview** to help you and the customer. The real price, tax and totals are calculated by the ERP server when you press **Complete sale**, and the printed receipt is built from what the server sends back. If a server figure ever differs from the on-screen preview, the server figure is the correct one.

---

## The four tender types

**What a tender is.** A *tender* is one way of paying — the form the money takes. OrbixPOS accepts four:

| Tender | Button label | What it means |
| --- | --- | --- |
| Cash | **Cash** | Notes and coins handed across the counter, paid into the drawer. |
| Card | **Card** | A debit or credit card, processed on your separate card terminal. |
| Mobile money | **Mobile** | A mobile-money transfer (for example a phone-wallet payment). |
| Cheque | **Cheque** | A bank cheque. |

**Why there are four.** Customers do not all pay the same way, and one customer may use more than one method for a single basket. Letting you pick the method per payment means the receipt and the books record exactly how the money arrived.

**How it works.** The tender-type buttons sit in a row on the left of the **Payment** screen. The one you tap is highlighted; whatever you do next (type an amount, press a quick-cash button, or press **Complete sale**) applies to that type. **Cash** is selected by default when the screen opens.

> **Card data is never typed into OrbixPOS.** When the customer pays by **Card**, you run the card on your external card terminal (the physical card machine beside the till). OrbixPOS only records *that* a card payment of a given amount was taken — it never asks for, stores, or shows a card number, PIN, or CVV. Treat the card machine as the place where card details live; the till just notes the amount.

---

## Task: a simple cash sale

This is the everyday case — the customer pays the whole basket in cash.

1. With the basket ready, press **PAY** on the register. The **Payment** screen opens with **Cash** already selected and **AMOUNT DUE** showing the total.
2. Take the cash from the customer.
3. Enter how much cash they gave you, in one of these ways:
   - Press a **quick-cash** button that matches (for example **Exact** if they paid the exact total, or **10,000** if they handed over a 10,000 note); **or**
   - Type the amount on the number pad.
4. Check the **Change** figure at the bottom-left. If the customer gave you more than the total, OrbixPOS shows the change you owe them. If they paid the exact amount, no change is shown.
5. Press **Complete sale**.
6. Hand over the change shown, then give the customer their receipt (see the *Receipts and Refunds* chapter, Chapter 6).

> For an exact-cash sale you do not have to type anything at all — you can simply press **Complete sale** and the till settles the basket for its exact total. Typing the cash given is only needed so the till can show you the **Change**.

**What happens behind the scenes.** When you press **Complete sale**, OrbixPOS sends the basket and the cash amount to the ERP server. The server prices every line, adds tax, finalises the sale, and returns a finished invoice. OrbixPOS clears the basket, saves the receipt on this device, and shows it to you.

---

## Task: split or multi-tender payment

**What it is.** A *split* (or *multi-tender*) payment is one sale paid with two or more payments — for example part on **Card** and the rest in **Cash**, or two cards, or a mobile-money transfer plus some cash.

**Why it exists.** Customers do not always have enough of one tender. Splitting lets a single basket be settled however the customer can actually pay, while still producing one sale and one receipt.

**When it happens.** Whenever the customer wants to use more than one method, or you simply choose to record cash and non-cash portions separately.

**How it works.** You add each payment one at a time with **Add tender**. The **Paid** figure grows as you add tenders; you keep adding until **Paid** reaches (or passes) the **Total**. Then you press **Complete sale**.

To take a split payment:

1. Press **PAY** to open the **Payment** screen.
2. Choose the tender type for the first payment (for example tap **Card**).
3. Type that payment's amount on the number pad (for example the card portion).
4. Press **Add tender**. The payment appears in the tender list on the left, and **Paid** increases by that amount.
5. Repeat for each further payment: tap its tender type (for example **Cash**), type its amount, and press **Add tender**.
6. Watch the **Paid** total. When it reaches the **Total**, the sale is fully covered. (If the last tender is cash and the customer over-pays, the **Change** figure tells you what to give back.)
7. Press **Complete sale**.

> **Removing a payment you added by mistake.** Each row in the tender list has a small **×** on the right. Tap it to remove that payment from the list before you complete the sale. The **Paid** total updates immediately.

> **Keep non-cash payments exact.** Make any **Card**, **Mobile** or **Cheque** payment the precise amount for that portion, and let **Cash** be the one that absorbs any over-payment (the change). The server gives change only on cash; over-paying on a card or mobile-money tender is rejected.

### Split-payment troubleshooting

| What you see | What to do |
| --- | --- |
| The message *"Tendered … is less than the total …"* when you press **Complete sale** | The payments you added do not yet cover the **Total**. Add another tender for the shortfall, then press **Complete sale** again. |
| You added the wrong amount or wrong type | Tap the **×** on that row in the tender list to remove it, then add it again correctly. |
| The customer changes their mind about how to pay | Remove the tenders with **×** and start the payment again, or close the **Payment** screen (the **×** at the top) to return to the basket. |

---

## The change preview

**What it is.** The **Change** figure on the **Payment** screen is how much money you owe the customer back when they have handed over more than the basket total.

**Why it is a preview.** The figure is calculated on the till as a convenience so you can count out the change quickly. The amount of cash the customer gave is not part of the sale record — it is used only to work out the change to show. The receipt prints the change as well, taken from the finalised sale.

**How it appears.** **Change** is shown at the bottom-left, under **Total** and **Paid**, only when there is change to give:
- In a plain cash sale, it is the cash you typed minus the total.
- In a split sale, it is the total paid minus the total owed.

> If **Change** does not appear, there is no change to give — the customer paid the exact amount (or has not yet covered the total).

---

## Completing the sale

**What it is.** **Complete sale** is the button that turns your prepared payment into a real, recorded sale on the ERP server and produces the receipt.

**Why it is the only step that commits.** Up to this point everything on the **Payment** screen is local preparation. **Complete sale** is the single action that sends the sale to the server, takes the money in the books, and finalises the invoice. Nothing is charged before you press it.

**How it works.** When you press **Complete sale**:

1. OrbixPOS checks that the payments cover the total. If they do not, it warns you (*"Tendered … is less than the total …"*) and stops — fix the payments and try again.
2. If the basket contains an **age-restricted** item, you are asked to confirm the customer's age first (see below).
3. The sale is sent to the server. The button is disabled briefly while it waits.
4. On success, the basket clears, the receipt is saved on this device, and the **Sale complete** receipt appears.

To complete a sale, simply press **Complete sale** once the payments cover the **Total**.

> **Press it once and wait.** While the sale is being sent, the button is disabled so a second tap cannot fire. Let it finish. If the connection is slow, give it a moment rather than tapping repeatedly.

### Age-restricted items

**What it is.** Some products (for example alcohol or certain medicines) have a minimum legal age. OrbixPOS flags these and asks you to confirm the customer is old enough before the sale can complete.

**How it works.** If the basket contains a restricted item, pressing **Complete sale** opens an **Age-restricted items** prompt naming the restriction (for example *18+* or *21+*).

1. Verify the customer's age — by checking ID where required.
2. If they meet the minimum age, press **Age verified** and the sale continues.
3. If they do not, press **Cancel**. The sale is stopped (you will see *"Sale stopped: age not verified."*); remove the restricted item before completing.

> Only complete an age-restricted sale once you have genuinely checked. Pressing **Age verified** records that the check was done.

---

## "Did that go through?" — the safe-retry guarantee

**What it is.** Sometimes you press **Complete sale** and the till cannot tell you whether the sale succeeded — the network drops, or the server takes too long to answer. OrbixPOS handles this so that you can safely try again **without ever charging the customer twice**.

**Why it exists.** A slow or broken network at the worst possible moment — just as money changes hands — used to be a real risk: retry and you might double-charge; do not retry and you might lose the sale. OrbixPOS removes that dilemma.

**How it works.** Every sale carries a hidden, durable identifier that stays the same across retries. The server remembers it. If the original attempt actually reached the server, retrying with the same identifier simply returns that *same* original sale — it does not create a second one. If the original never reached the server, the retry creates the sale normally. Either way you end up with exactly one sale.

When the outcome is unknown, the **Payment** screen tells you plainly. A yellow banner appears on the left:

> *"The outcome was unknown (network/timeout). Press Complete again — the same idempotency key returns the original sale if it went through."*

At the same time, the green button changes its label from **Complete sale** to **RETRY (same key)**.

What to do:

1. Read the yellow banner. It means: the till is not sure whether the sale was recorded.
2. Press the green **RETRY (same key)** button.
3. One of two things happens:
   - If the sale had gone through, the original receipt appears — no second charge, no duplicate sale.
   - If it had not, the sale is created now and the receipt appears.
4. You can press **RETRY (same key)** as many times as it takes; it is always safe.

| What you see | What it means | What to do |
| --- | --- | --- |
| Yellow *"outcome was unknown"* banner and a **RETRY (same key)** button | The till could not confirm the sale (network or timeout) | Press **RETRY (same key)**. It returns the original sale if it went through, or completes it if it did not. Never double-charges. |
| A plain error message (red toast) and the button still reads **Complete sale** | The sale was rejected for a clear reason (for example the session is no longer open, or a price is missing) | Read the message and fix the cause, then try again. This is not a "retry the same thing" situation. |
| The receipt appears (**Sale complete**) | The sale succeeded | Hand over change and the receipt; you are done. |

> Do **not** open a fresh sale or re-ring the basket after an unknown outcome. Always use **RETRY (same key)** on the same **Payment** screen — that is what protects against a double charge. Only start over if you deliberately closed the **Payment** screen.

---

## After payment: the receipt

When the sale completes, OrbixPOS shows the **Sale complete** receipt straight away. From there you can **Print** it, produce a **Gift receipt** (which hides prices), or — with the right permission and while the session is open — **Refund / reverse** the whole sale. The receipt is also saved on this device so you can reprint it later without creating a new sale.

The full set of receipt actions — printing, gift receipts, reprints and refunds — is covered in the next chapter, *Receipts and Refunds* (Chapter 6).

> **A note on the printer.** In the current build, pressing **Print** shows a confirmation (*"Sent to printer (peripheral stub)."*) rather than driving a physical printer; real printer, cash-drawer and scale drivers are planned for a later release. The receipt is still saved and can be viewed and reprinted on screen.

---

## Quick reference

| You want to… | Do this |
| --- | --- |
| Open the payment screen | Press the green **PAY** on the register (basket must not be empty). |
| Take exact cash | Press **Complete sale** (no need to type anything). |
| Take cash and give change | Type the cash given (or tap a quick-cash button), check **Change**, press **Complete sale**. |
| Choose how the customer pays | Tap **Cash**, **Mobile**, **Cheque**, or **Card** before entering the amount. |
| Split across methods | Pick a type, type the amount, press **Add tender**; repeat until **Paid** covers **Total**; press **Complete sale**. |
| Remove a payment you added | Tap the **×** on its row in the tender list. |
| Sell an age-restricted item | Press **Complete sale**, then **Age verified** after checking the customer's age. |
| Recover from an unknown outcome | Press **RETRY (same key)** under the yellow banner — it never double-charges. |
| Take a card payment | Run the card on the external card terminal; OrbixPOS records only the amount, never card details. |
| Abandon the payment | Press the **×** at the top of the **Payment** screen to return to the basket. |
