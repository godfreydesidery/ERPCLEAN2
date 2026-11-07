# Selling Offline

When the till cannot reach the ERP server — the internet is down, the router has restarted, the line is too slow to use — a till that is **allowed to sell offline** keeps taking cash, mobile money and card sales. It keeps every sale safely on the till and sends them to the ERP by itself when the server is back. This chapter explains what changes while you are offline, what you can and cannot do, and what happens afterwards.

> **The rule to remember.** An offline sale is a real sale: the customer has paid and takes the goods. The till writes it to its own storage **before** the receipt prints, marks the receipt **OFFLINE**, and the ERP records it later — at the price the customer paid and at the time it was sold.

---

## Is this till allowed to sell offline?

A manager decides this for each till in the ERP (**POS › Tills**, the **Offline selling** switch). Every till starts with it **off**. On a till where it is off, nothing in this chapter applies: when the server cannot be reached the till behaves as before and you wait for the connection.

---

## Going offline

**When the connection drops.** If the till cannot reach the server, a strip appears at the top: **"No connection — switch to offline?"**. Any cashier may accept it. The till checks for itself that the server really cannot be reached before it lets you switch.

**When the connection is slow but working.** A supervisor or manager can switch the till to offline by hand, from the session menu. They confirm with their **password** (not their PIN), because switching relaxes the rules for the whole till.

While the till is offline you see a red banner at the top of every screen:

**OFFLINE — sales are kept on this till until the server is back**

with how many sales are waiting, how old the prices are ("prices as of 07:40"), and what you may take.

---

## What you can do offline

- Ring **quick sales** to the **walk-in customer**, at the prices the till last received from the server.
- Take **cash**, **mobile money** and **card** — and split a sale between them. For mobile money and card you **must** type the reference (the transaction number from the customer's message, or the card terminal's approval number); the sale will not complete without it.
- Give change for cash as usual.
- Print and reprint receipts. Every offline receipt shows `*** OFFLINE SALE ***` and an offline receipt number such as `OFF-T2K7-000153`.
- Open a shift if none is open (see below) and keep selling in the same shift after a restart.

## What you cannot do offline

These need the server and wait until it is back:

- discounts, complimentary, refunds and reversals (anything that needs a manager's approval);
- credit sales and sales to a named customer;
- restaurant orders (the restaurant screen says so and offers the quick-sale screen);
- **closing your shift** while it still has offline sales waiting to be sent.

**After 24 hours** of offline selling without reaching the server, the till stops taking new sales until it has sent the ones it holds.

---

## Opening a shift while offline

If you arrive and no shift is open, the till opens an **offline shift** with your opening float. It is created in the ERP when the till next reaches the server, with the same opening time and float, and the offline sales go into it.

## If the till restarts during an outage

After a power cut or a restart, the sign-in screen says **"No connection to the ERP"** and offers **Sign in offline**. Anyone who has signed in **on this till** before (while it was online) can sign in with their usual password or PIN. You continue in the same shift with the sales already waiting.

- Five wrong tries lock that person out of offline sign-in for 15 minutes.
- Offline sign-in works for 14 days after a person's last online sign-in on this till.

---

## When the server is back

The till notices by itself. It finishes the sale you are ringing, then — at the latest after five minutes — returns to online and sends the waiting sales. If you signed in offline, it asks you to **sign in online** first; nothing is lost while it waits.

Open **Offline sales** from the session menu to see:

- how many sales are waiting and when the till last sent them;
- any sale the ERP **held** instead of recording, with the reason in plain words.

Each sale is recorded once, even if the till sends it twice. The receipt the customer holds keeps its offline number; the ERP links it to the real invoice number, and reprints show both.

---

## For managers: reviewing offline sales

In the ERP, **POS › Offline Sales Review** lists, for your branch:

- sales recorded at a **different price** from today's list price;
- sales that took **stock below zero**;
- sales **sold below cost** (when your below-cost policy is on);
- sales dated in a **closed accounting period**;
- sales the ERP **held** and did not record, with the sale exactly as it was rung;
- every offline period: which till, who switched, why, how long, and how many sales.

Look at each item and **Acknowledge** it, with a note if you like. Nothing on the list stops the books — flagged sales are already recorded.

---

## Quick reference

| You see | It means | What to do |
|---|---|---|
| "No connection — switch to offline?" | The till cannot reach the server | Accept it if the till is allowed offline; otherwise wait |
| Red OFFLINE banner | You are selling offline | Sell as usual: cash, mobile money or card, walk-in only |
| "A mobile money or card payment needs its reference" | The reference box is empty | Type the transaction or approval number |
| Close shift refused | Offline sales are still waiting | Wait until they are sent (Offline sales shows the count) |
| "Sign in online" on the banner | The server is back after an offline sign-in | Sign in normally; the sales then go |
| Offline selling stopped after 24 hours | Too long without the server | Get the connection back; the till sends its sales and then sells again |
