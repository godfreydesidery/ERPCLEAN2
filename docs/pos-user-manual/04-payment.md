# Taking Payment

This chapter covers the moment of truth at the till: turning a basket of items into money in the drawer (or on the card terminal) and a receipt in the customer's hand. It explains the **Payment** screen, the four ways a customer can pay, how to take a simple cash sale, how to split one sale across several payments, and — importantly — what to do when you are not sure whether a payment went through.

Everything here works the same way in all three OrbixPOS modes — Supermarket, Pharmacy and Restaurant. The register differs, but the **Payment** screen is identical, because all three share one payment, receipt and session flow.

> Before you can take payment you must be signed in and have an **open** shift (an open session on a till). If you have not opened a shift yet, see the *Starting and Ending a Shift* chapter (Chapter 2). The **PAY** button is greyed out while the basket is empty.

---

## Opening the Payment screen

**What it is.** The **Payment** screen is a single panel that appears on top of your register when you are ready to collect money. On the left it shows the amount the customer owes and a running list of what they have paid so far; on the right it shows the amount box, a number pad and the buttons that finish the sale.

**Why it exists.** Ringing items and taking money are two separate steps on purpose. You build the basket first, check it with the customer, and only then move money.

**When it happens.** Once every item is in the basket and the customer is ready to pay.

**How it works.** When you press the **PAY** button on the register (it also shows the basket total), OrbixPOS makes two quick checks and then opens the **Payment** screen with the basket total as the amount due. Nothing is sent to the server yet. The sale is only created when you press **Complete sale**.

To open it:

1. Finish adding items to the basket on the register.
2. Read the total back to the customer.
3. Press **PAY** (bottom-right of the register).

Before the **Payment** screen opens, the till may stop you with one of these:

| What appears | Why | What to do |
|---|---|---|
| An **Unfinished sale** dialog | An earlier sale on this till was interrupted before the till knew whether it went through. It must be settled first, so the same customer is never charged twice. | Press **Check sale** — see **An unfinished sale from before** below. |
| **Select a customer before completing the sale.** and the **Customer** list | Every sale needs a customer, and this basket has none (usually because your company's walk-in customer could not be found). | Pick the customer — usually the walk-in / cash customer — from the list. You can search by name, code or phone. If you close the list without choosing, the Payment screen does not open. |

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
| **Total / Paid / Change** | A short summary at the bottom: **Total** (the amount due), **Paid** (the payments you have added so far), and **Change** (shown only when the customer has handed over more than the total). |

### The right half — the amount box and number pad

| Control | What it does |
| --- | --- |
| Amount box | A dark box at the top where the amount you are entering appears. It shows a faint **0** until you enter something. The cursor is already in it, so you can **type the amount on the keyboard** — digits and one decimal point, no commas (type `10000`, not `10,000`). Pressing **Enter** here is the same as pressing **Complete sale**. |
| Quick-cash buttons | A grid of suggested amounts: **Exact** (the exact total) and the total rounded up to common notes — for a total of 12,500 the buttons are **Exact**, **13,000**, **14,000**, **15,000** and **20,000**. One tap fills the amount box. |
| Number pad | Keys **0**–**9** for typing an amount by touch or mouse. **C** clears the amount box; the backspace key (⌫) deletes the last digit. The pad and the keyboard edit the same box. |
| **Add tender** | Records the amount in the box as one payment of the selected type, and adds it to the tender list. If the box is empty, it adds the **remaining balance** as that type. Use this for split payments (see below). |
| **Complete sale** | The big green button (it also shows the total) that finishes the sale, sends it to the server, and shows the receipt. |

> The amount you enter, the quick-cash buttons, and the **Change** figure are a **preview** to help you and the customer. The real price, tax and totals are calculated by the ERP server when you press **Complete sale**, and the receipt is built from what the server sends back. If a server figure ever differs from the on-screen preview, the server figure is the correct one.

> **If a line has no price yet,** the on-screen total cannot be known, so the till shows no **Change** figure and lets the server price the sale. If the product truly has no price, the server refuses the sale and tells you why.

---

## The four tender types

**What a tender is.** A *tender* is one way of paying — the form the money takes. OrbixPOS accepts four:

| Tender | Button label | What it means |
| --- | --- | --- |
| Cash | **Cash** | Notes and coins handed across the counter, paid into the drawer. |
| Mobile money | **Mobile** | A mobile-money transfer (for example M-Pesa, Tigo Pesa or Airtel Money), confirmed on the customer's or the shop's phone. |
| Cheque | **Cheque** | A bank cheque. |
| Card | **Card** | A debit or credit card, processed on your separate card terminal. |

**Why there are four.** Customers do not all pay the same way, and one customer may use more than one method for a single basket. Recording the method per payment means the receipt and the books show exactly how the money arrived — and only **Cash** counts towards the cash expected in your drawer.

**How it works.** Click a tender-type button to select it; it highlights. Whatever you do next (enter an amount, press **Add tender**, or press **Complete sale**) applies to that type. **Cash** is selected when the screen opens.

> **Card data is never typed into OrbixPOS.** When the customer pays by **Card**, you run the card on your external card terminal (the physical card machine beside the till). OrbixPOS only records *that* a card payment of a given amount was taken — it never asks for, stores, or shows a card number, PIN, or CVV.

> **Confirm mobile money before you complete.** OrbixPOS does not send a payment request to the customer's phone. Wait until the transfer is confirmed on the phone, then record it as **Mobile**.

---

## Task: a simple cash sale

This is the everyday case — the customer pays the whole basket in cash.

1. With the basket ready, press **PAY** on the register. The **Payment** screen opens with **Cash** already selected and **AMOUNT DUE** showing the total.
2. Take the cash from the customer.
3. Enter how much cash they gave you, in one of these ways:
   - press a **quick-cash** button that matches (for example **Exact** if they paid the exact total, or **20,000** if they handed over a 20,000 note); **or**
   - type the amount on the keyboard or the number pad.
4. Check the **Change** figure at the bottom-left. If the customer gave you more than the total, OrbixPOS shows the change you owe them. If they paid the exact amount, no change is shown.
5. Press **Complete sale** (or press **Enter**).
6. Hand over the change shown, then give the customer their receipt (see **After payment** below).

> For an exact-cash sale you do not have to enter anything at all — just press **Complete sale** and the till settles the basket in cash for its exact total. Entering the cash given is only needed so the till can show you the **Change**.

> If you enter **less** than the total and press **Complete sale**, the till stops and says *"Tendered … is less than the total …. Use Add tender to split, or key the full amount."* Enter the full amount, or take the rest by another method (see split payments).

**What happens behind the scenes.** When you press **Complete sale**, OrbixPOS sends the basket and the payment to the ERP server. The server prices every line, adds tax, finalises the sale, and returns a finished invoice. OrbixPOS clears the basket, saves the receipt on this device, and shows it to you.

---

## Task: a single non-cash payment

If the customer pays the whole basket by **Card**, **Mobile** or **Cheque**:

1. Press **PAY**.
2. Click the tender type (for example **Mobile**).
3. Take the payment on the card terminal or confirm it on the phone, for the exact total.
4. Press **Complete sale**. With nothing entered, the whole total is recorded under the type you selected — it is never silently recorded as cash.

If you do enter an amount, it must be the full total; for part-payments use **Add tender** (next section).

---

## Task: split or multi-tender payment

**What it is.** A *split* (or *multi-tender*) payment is one sale paid with two or more payments — for example part on **Card** and the rest in **Cash**, or a mobile-money transfer plus some cash.

**Why it exists.** Customers do not always have enough of one tender. Splitting lets a single basket be settled however the customer can actually pay, while still producing one sale and one receipt.

**How it works.** You add each payment one at a time with **Add tender**. The **Paid** figure grows as you add tenders; you keep adding until **Paid** reaches (or passes) the **Total**. Then you press **Complete sale**.

To take a split payment:

1. Press **PAY** to open the **Payment** screen.
2. Choose the tender type for the first payment (for example click **Mobile**).
3. Enter that payment's amount (for example the mobile-money portion).
4. Press **Add tender**. The payment appears in the tender list on the left, and **Paid** increases by that amount.
5. For the last payment, click its tender type (for example **Cash**) and either enter the cash handed over, or leave the box empty — **Add tender** with an empty box adds exactly the remaining balance.
6. Press **Add tender** again, and watch **Paid**. When it reaches the **Total**, the sale is fully covered. (If the last tender is cash and the customer over-pays, the **Change** figure tells you what to give back.)
7. Press **Complete sale**.

> **Removing a payment you added by mistake.** Each row in the tender list has a small **×** on the right. Click it to remove that payment before you complete the sale. The **Paid** total updates immediately.

> **Keep non-cash payments exact.** Make any **Card**, **Mobile** or **Cheque** payment the precise amount for that portion, and let **Cash** be the one that absorbs any over-payment (the change). Change is given only on cash; over-paying on a non-cash tender is refused by the server.

### Split-payment troubleshooting

| What you see | What to do |
| --- | --- |
| *"Tendered … is less than the total …."* when you press **Complete sale** | The payments you added do not yet cover the **Total**. Add another tender for the shortfall, then press **Complete sale** again. |
| You added the wrong amount or wrong type | Click the **×** on that row in the tender list to remove it, then add it again correctly. |
| The customer changes their mind about how to pay | Remove the tenders with **×** and start the payment again, or close the **Payment** screen (the **×** at the top, or **Esc**) to return to the basket. |

---

## The change preview

**What it is.** The **Change** figure on the **Payment** screen is how much money you owe the customer back when they have handed over more than the basket total.

**Why it is a preview.** The figure is calculated on the till as a convenience so you can count out the change quickly. The receipt prints the change too, taken from the finalised sale.

**How it appears.** **Change** is shown at the bottom-left, under **Total** and **Paid**, only when there is change to give:

- in a plain cash sale, it is the cash you entered minus the total;
- in a split sale, it is the total of the payments added minus the total owed.

> If **Change** does not appear, there is no change to give — the customer paid the exact amount, has not yet covered the total, or a line has no price yet.

---

## Completing the sale

**What it is.** **Complete sale** is the button that turns your prepared payment into a real, recorded sale on the ERP server and produces the receipt.

**Why it is the only step that commits.** Up to this point everything on the **Payment** screen is local preparation. **Complete sale** is the single action that sends the sale to the server and finalises the invoice. Nothing is charged before you press it.

**How it works.** When you press **Complete sale**:

1. OrbixPOS checks that the payments cover the total. If they do not, it warns you (*"Tendered … is less than the total …"*) and stops — fix the payments and try again.
2. If the basket contains an **age-restricted** item, you are asked to confirm the customer's age first (see below).
3. The sale is sent to the server. The button is disabled, and the **Payment** screen cannot be closed, until the server answers.
4. On success, the basket clears, the receipt is saved on this device, and the **Sale complete** receipt appears.

> **Press it once and wait.** While the sale is being sent, the button is disabled so a second press cannot fire. If the connection is slow, give it a moment rather than pressing repeatedly.

### Age-restricted items

**What it is.** Some products (for example alcohol, tobacco or certain medicines) have a minimum legal age. OrbixPOS flags these and asks you to confirm the customer is old enough before the sale can complete.

**How it works.** If the basket contains a restricted item, pressing **Complete sale** opens an **Age-restricted items** prompt: "This basket contains 18+ items. Confirm you have verified the customer meets the minimum age." (or 21+).

1. Verify the customer's age — by checking ID where required.
2. If they meet the minimum age, press **Age verified** and the sale continues.
3. If they do not, press **Cancel**. The sale is stopped (you will see *"Sale stopped: age not verified."*); remove the restricted item before completing.

> Only press **Age verified** once you have genuinely checked. Pressing it records that the check was done.

> **Supervisors with the age-override right:** **Cancel** always stops the sale, for everyone. An account allowed to override the age check also sees a third button, **Override without check**, in the **Age-restricted items** prompt. Pressing it completes the sale without the age confirmation. Use it only when your shop's policy allows it.

### When the server refuses the sale

If the server definitely refuses the sale — for example the session has been closed, an item is out of stock, or a discount is larger than you may give — a red banner appears on the left with the server's reason and the line **"Nothing was charged. Fix it and try again."** The same reason appears briefly at the bottom of the screen. The basket is still there.

1. Read the reason.
2. Fix the cause (close the Payment screen to change the basket if needed).
3. Press **Complete sale** again.

If the refusal is about a **discount** above what a cashier may give alone, the banner also offers **Get manager approval for the discount**. A manager types their username and password in the **Manager approval — discount** dialog; the till then retries the same sale with the approval attached and shows **Approved by …** with the manager's name.

If the till says **"This basket is too old to complete. Nothing was charged — ring it again."**, the basket waited too long before reaching the server. Nothing was charged; ring it again.

---

## "Did that go through?" — the safe retry

**What it is.** Sometimes you press **Complete sale** and the till cannot tell whether the sale succeeded — the network drops, the server takes too long to answer, or the server reports an error after it may already have saved the sale. OrbixPOS handles this so you can safely try again **without charging the customer twice**.

**Why it exists.** A slow or broken network at the moment money changes hands is a real risk: retry the wrong way and you might charge twice; do not retry and you might lose the sale. OrbixPOS removes that dilemma.

**How it works.** Every sale carries a hidden reference that stays the same across every retry, and the till saves it on the device *before* sending the sale. The server remembers the reference. If the first attempt actually reached the server, retrying with the same reference simply returns that *same* sale — it does not create a second one. If it never reached the server, the retry creates the sale normally. Either way you end up with exactly one sale.

When the outcome is unknown, a yellow banner appears on the left:

> *"No answer from the ERP, so we cannot tell whether this sale went through. Press Retry — it is safe. If the sale was already recorded you will get that same receipt back, never a second charge."*

At the same time the green button changes from **Complete sale** to **Retry this sale**. If the server did send an explanation, it is shown in smaller text under the banner, starting **"The ERP said:"** — for example that a product has no price yet.

What to do:

1. Read the yellow banner. It means: the till is not sure whether the sale was recorded.
2. Press **Retry this sale**.
3. One of two things happens:
   - if the sale had gone through, the original receipt appears — no second charge;
   - if it had not, the sale is created now and the receipt appears.
4. You can press **Retry this sale** as many times as it takes; it is always safe.
5. If it keeps failing, read the **"The ERP said:"** line and show it to your supervisor. A retry cannot fix a problem such as an unpriced product — the product must be fixed in the ERP first.

| What you see | What it means | What to do |
| --- | --- | --- |
| Yellow *"No answer from the ERP…"* banner and a **Retry this sale** button | The till could not confirm the sale. | Press **Retry this sale**. It returns the original sale if it went through, or completes it if it did not. |
| The same, plus *"The ERP said: …"* | The server gave a reason, but the till still cannot be sure nothing was saved. | Retry once. If it fails again with the same reason, fix that reason (ask your supervisor). |
| Red banner with *"Nothing was charged. Fix it and try again."* | The server definitely refused the sale. Nothing was saved. | Fix the cause and press **Complete sale** again. |
| The receipt appears (**Sale complete**) | The sale succeeded. | Hand over change and the receipt; you are done. |

> Do **not** clear the basket and ring the items again after an unknown outcome. Always use **Retry this sale** on the same **Payment** screen — that is what protects against a double charge.

### An unfinished sale from before

If you close the **Payment** screen while the outcome is still unknown — or the app is closed, the PC restarts, or the power fails in the middle of a sale — the till remembers that sale. The next time you reach the register, or press **PAY**, it shows an **Unfinished sale** dialog: "A sale of TZS … started at … was interrupted before we knew whether it went through."

| Button | What it does |
| --- | --- |
| **Check sale** | Asks the ERP what happened. It only reads — it charges nothing. This is the one to press. |
| **Not now** | Leaves the question open. The till will not let you take another payment until it is settled. |
| **Leave unresolved…** | Gives up without an answer. It needs a manager's approval and is recorded on the ERP. Use it only when the ERP cannot answer and a supervisor decides. |

After **Check sale**, one of these follows:

| What the till says | What to do |
| --- | --- |
| **That sale went through as …** (the receipt number) **Nothing more to do.** and the receipt | The customer was charged. Give them the receipt if they are still there. Do not ring it again. |
| **Nothing was recorded** — "The ERP has no sale under this reference, so the customer was not charged." | If the customer is still there and wants the goods, press **Complete the sale** — it is sent under the same reference, so it can never charge twice. If they have left, press **Discard** (*"Discarded — nothing was charged."*). |
| **That sale is still going through. Give it a moment, then check again.** | The ERP is still working on it. Wait a few seconds and press **Check sale** again. |
| **Cannot reach the ERP to check. Try again when the connection is back.** | The till is offline. Fix the connection, then check again. |

If a manager approves **Leave unresolved…**, the till says *"Left unresolved … Check Today's sales before ringing it again."* Do exactly that: look for the sale in **Today's sales** before ringing the customer's goods again.

---

## After payment: the receipt

When the sale completes, OrbixPOS shows the **Sale complete** receipt straight away — on screen, laid out exactly as it will print. It is **not printed automatically**: press **Print** to print it on the receipt printer. If the till is set to open the cash drawer, the drawer opens as this first copy prints (it does not open again for a second copy or a reprint).

From the same screen you can produce a **Gift receipt** (which hides prices), or — with the right permission and while your session is open — **Refund / reverse** the whole sale (for a cashier, a manager approves it). The receipt is also saved on this device so you can reprint it later without creating a new sale.

If no printer is set up, **Print** shows **No receipt printer set — configure one in Setup.** The sale is still complete and the receipt can be reprinted later.

The full set of receipt actions — printing, gift receipts, reprints and refunds — is covered in the next chapter, *Receipts and Refunds* (Chapter 6).

---

## Quick reference

| You want to… | Do this |
| --- | --- |
| Open the payment screen | Press **PAY** on the register (basket must not be empty). Settle any **Unfinished sale** first. |
| Take exact cash | Press **Complete sale** (no need to enter anything). |
| Take cash and give change | Enter the cash given (keyboard, pad, or a quick-cash button), check **Change**, press **Complete sale** or **Enter**. |
| Take one card / mobile / cheque payment | Click the tender type, take the payment for the exact total, press **Complete sale**. |
| Split across methods | Pick a type, enter the amount, press **Add tender**; repeat (an empty box adds the remaining balance); press **Complete sale**. |
| Remove a payment you added | Click the **×** on its row in the tender list. |
| Sell an age-restricted item | Press **Complete sale**, check ID, then **Age verified**. |
| Recover from an unknown outcome | Press **Retry this sale** under the yellow banner — it never double-charges. |
| Settle an interrupted sale | **Unfinished sale → Check sale**, then follow what it says. |
| Get a discount approved | **Get manager approval for the discount** on the red banner; a manager signs in to the approval dialog. |
| Print the receipt | Press **Print** on the **Sale complete** screen. |
| Abandon the payment | Press the **×** at the top of the **Payment** screen (or **Esc**) to return to the basket. |
