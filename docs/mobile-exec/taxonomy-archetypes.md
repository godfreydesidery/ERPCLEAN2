# Executive Report Archetypes — the governing taxonomy

**OrbixERP Executive Mobile · v1 · 2026-08-18**
Scope: the *shapes* of executive reports, the archetype × domain generator, and the naming doctrine.
Not in scope: feasibility, data availability, endpoint inventory. Design for what an owner should have.

---

## 0. The frame

### 0.1 The grammar of the catalogue

Every report in this suite is one sentence:

> **ARCHETYPE** × **DOMAIN** × **SUBJECT**

*Variance Bridge × Profit × this month* → **Where the Margin Went**.
*Exception Register × Tax × open items* → **Sales With No Fiscal Receipt**.

Get the archetypes right and the catalogue generates itself. Skip them and you ship forty unrelated screens that each need their own explanation.

### 0.2 The six executive tests (every screen passes all six or it is cut)

| # | Test | Fails when |
|---|---|---|
| 1 | **One question.** The screen answers exactly one. | The screen has tabs. |
| 2 | **Carries a comparison.** vs plan, vs prior period, vs peer, vs rule. | A number stands alone. |
| 3 | **Exception-led.** The worst thing is at the top, not the total. | It opens with a grand total nobody disputes. |
| 4 | **One phone screen. No transaction rows.** | You scroll to understand, or you see invoice numbers. |
| 5 | **Triggers a named decision.** You can write the decision on the screen. | The honest response is "interesting". |
| 6 | **States as-of and trust.** When it was computed, what is provisional. | The number is silently 40 minutes stale, or half-posted. |

### 0.3 The one-screen budget (hard limit, all archetypes)

- 1 headline number or headline verdict
- 1 comparison carrier (delta, band, target line, rank)
- ≤ 7 body rows or ≤ 1 chart
- 1 as-of + trust line
- 1 primary action or tap-through

Anything beyond that lives behind the tap-through, not on the screen.

### 0.4 What is *not* an archetype

A chart type is not an archetype (a bar chart serves four of these). A filter is not an archetype. A drill-down is not an archetype — it is the tail of one. A "dashboard" is not an archetype; it is a tray that holds several.

---

## 1. The fourteen archetypes

---

### 1. FLASH

**Question shape:** *Is today normal?*
The pulse. Highest frequency, lowest precision, shortest read. Answers before coffee.

**Anatomy on a phone**
- **Top:** today's single number, very large (e.g. TZS 41.2M sales so far).
- **Comparison:** same weekday last week, and same day last month — as a delta chip, not a second number. Never "vs yesterday" alone; yesterday may have been a Sunday.
- **Body:** 3–5 pulse lines (cash banked, tickets, average ticket, factory output, POS uptime), each with its own delta chip. Colour on deviation only.
- **Tap-through:** the branch strip — same three numbers per branch, so "which branch is dragging" is one tap.

**Visual form:** one big number + delta chip; below it a short list of number + delta; optional 24-hour intraday sparkline behind the headline.

**Decision it triggers:** call someone before lunch. "Kariakoo is at 40% of a normal Tuesday at 11am — what is wrong?"

**Trust line:** "As of 11:04. Today is provisional — unposted POS batches included, GRNs not yet costed."

**How it fails badly:** it becomes a mini-dashboard with twelve tiles; it compares to the wrong baseline (yesterday, calendar month-to-date on a trading business with weekday seasonality); it shows precision it does not have (TZS 41,238,166 for a provisional figure); it is silent when nothing is wrong, so nobody learns to trust it.

**Instantiations**
- **Today's Trade** — sales, tickets, cash and average basket against the same weekday.
- **Today on the Floor** — units produced, downtime minutes and scrap against a normal shift.

---

### 2. SCORECARD

**Question shape:** *Are we meeting the standard we set?*
The standard is external to the number: a plan, a budget, a covenant, a policy target. Not a trend.

**Anatomy on a phone**
- **Top:** a verdict, not a number — "4 of 7 on plan", with a colour band.
- **Comparison:** the target itself, printed. Every row shows actual, target, and gap in the owner's unit.
- **Body:** the fixed set of 5–8 measures, ordered by size of miss (not alphabetically, not by importance — misses first).
- **Tap-through:** any red row opens its Variance Bridge. That pairing is the backbone of the suite.

**Visual form:** rows with a bullet gauge each (actual bar, target tick, band); no pie, no gauge dials.

**Decision it triggers:** which single miss gets management attention this week; whether the plan or the performance is wrong.

**Trust line:** "Month-to-date to 17 Aug. Plan = board budget v3, approved 2 Jul."

**How it fails badly:** the measure set changes month to month, so nobody can hold a memory of it; there is no target and it degenerates into a Position Statement; everything is amber; the targets were never agreed by the people measured, so the screen is argued with rather than acted on.

**Instantiations**
- **The Seven Numbers** — the group's standing measures against board plan.
- **Factory Against Plan** — output, yield, downtime, cost per unit against standard.

---

### 3. POSITION & MOVEMENT

**Question shape:** *Where do we stand right now, and how did we get from the opening balance to here?*
A stock, not a flow — and its explanation. The closing balance is the headline; the movement is the body.

**Anatomy on a phone**
- **Top:** the closing position (cash across all accounts; stock at cost; debtor book).
- **Comparison:** opening balance for the period, and the same position one month ago.
- **Body:** the waterfall — opening, the 4–6 movement classes (in, out, revaluation, transfer, write-off), closing. Movement classes are business categories, not GL account names.
- **Tap-through:** any movement bar opens its own composition (top contributors, not transactions).

**Visual form:** big number, then a horizontal waterfall with bars sized in the owner's unit.

**Decision it triggers:** whether an unwelcome balance is a timing problem or a real one — "stock is up TZS 300M, but 240M of that is one factory build for the Christmas run, so it is planned."

**Trust line:** "As of 17 Aug 23:00. Stock at moving average; GRNI TZS 62M sitting in the 'received not invoiced' bar."

**How it fails badly:** movement classes are chart-of-accounts labels ("Suspense", "Adjustments 9200") and mean nothing to the owner; a residual "Other" bar exceeds 10% of the movement, which means the report has not actually explained anything; the closing balance disagrees with the ledger and nobody says so.

**Instantiations**
- **Where the Cash Is** — bank, till, mobile money, cash in transit, with the month's movement.
- **How the Stock Value Moved** — opening to closing by purchases, production, sales cost, shrinkage, revaluation.

---

### 4. VARIANCE BRIDGE

**Question shape:** *Why did we miss? Decompose the gap into named causes.*
The intellectual centre of the suite. Turns a disappointing number into a list of decisions.

**Anatomy on a phone**
- **Top:** the gap itself as the headline — "Margin TZS 118M below plan", not "Margin TZS 1.42Bn".
- **Comparison:** built into the form; the bridge *is* plan → actual.
- **Body:** waterfall of causes, largest first, capped at 6 bars plus a residual that must stay small: price, volume, mix, cost, discount, shrinkage, FX.
- **Tap-through:** each cause opens its own League Table — "which branches and which products caused the mix effect".

**Visual form:** vertical or horizontal waterfall, red down-bars and green up-bars, values in TZS millions.

**Decision it triggers:** the specific corrective act. A price bar says raise prices or renegotiate; a mix bar says change what the sales team pushes; a discount bar says tighten authority.

**Trust line:** "Plan v3 vs actuals to 17 Aug. Cost effects use moving average; last week's GRNs are costed."

**How it fails badly:** the causes are not mutually exclusive and the bars double-count; the residual is the biggest bar; the decomposition is mathematically correct but has no owner attached to any bar; it is run on a number nobody had a target for, so there is no gap to bridge.

**Instantiations**
- **Where the Margin Went** — plan margin to actual margin by price, volume, mix, purchase cost, discount, shrinkage.
- **Why Cash Is Short** — expected closing cash to actual, by slow collections, early payments, stock build, capex.

---

### 5. LEAGUE TABLE

**Question shape:** *Who is best and who is worst, on the same rule?*
Comparison between peers who genuinely face comparable conditions. The most politically powerful archetype and the easiest to abuse.

**Anatomy on a phone**
- **Top:** the spread — "Best 34% margin, worst 11%, group 22%" — because the gap is the story, not the leader.
- **Comparison:** the group average or plan as a printed line every row is measured against.
- **Body:** ranked bars, top 3 and bottom 3 with the middle collapsed ("+7 branches between"). Every row carries a movement arrow (rank change since last period) — a consistently poor branch and a newly collapsing branch demand different actions.
- **Tap-through:** a row opens that unit's Scorecard.

**Visual form:** ranked horizontal bars with a group-average reference line; rank-change arrows.

**Decision it triggers:** where the manager visit goes this week; which practice to copy from the top and force into the bottom.

**Trust line:** "August month-to-date. Branches opened under 90 days excluded. Central costs not allocated."

**How it fails badly:** it ranks units that are not comparable (a wholesale depot against a mall counter) and everyone dismisses it; it ranks on an absolute number so the biggest branch always wins and the table teaches nothing; it names individuals in a way that makes the data political and the entries get gamed; there is no rank-change column so a slow collapse looks the same as a chronic laggard.

**Instantiations**
- **Branch League** — profit per branch against plan and against the group rate.
- **Route League** — margin per van route per day on the road, and stock returned.

---

### 6. EXCEPTION REGISTER

**Question shape:** *What is wrong right now, by our own rules?*
The purest expression of exception-led reporting. A rule was set; these are the breaches.

**Anatomy on a phone**
- **Top:** the count and the money — "23 breaches · TZS 84M exposed" — never the count alone.
- **Comparison:** the same count seven days ago, and how many of today's breaches are repeats. Repeats are the real signal: a breach that survives a week is a control failure, not an incident.
- **Body:** breaches ranked by value at risk, one line each: what, where, how much, how many days old.
- **Tap-through:** the item, plus a resolve/accept/assign action — this archetype is the one that most deserves write-back.

**Visual form:** a list of breaches with a severity rail; a small "new vs repeat vs cleared" trio at the head.

**Decision it triggers:** an assignment with a name and a deadline; or a decision to change the rule because the breach rate proves it is unrealistic.

**Trust line:** "Rules as at 1 Aug. Evaluated 17 Aug 23:00. Threshold: value over TZS 500k or age over 7 days."

**How it fails badly:** the rule is too loose and produces 400 breaches, so the register is ignored within a fortnight — the single most common death of this archetype; there is no ageing so nothing escalates; it lists breaches with no money attached and the owner cannot triage; there is no way to accept a breach, so known-and-approved exceptions clog the list forever.

**Instantiations**
- **Sold Below Cost** — lines that went out under landed cost, by branch and salesperson.
- **Stock That Should Not Exist** — negative on-hand, expired batches, and locations with no movement in 90 days.

---

### 7. AGEING PYRAMID

**Question shape:** *How old is this pile, and how much of it has gone rotten?*
Time-bucketing as the whole point. The shape of the stack is the diagnosis.

**Anatomy on a phone**
- **Top:** total and the toxic share — "TZS 1.9Bn owed · 31% over 90 days".
- **Comparison:** the same shape one month ago, as a ghost outline behind the current stack. Whether the pyramid is fattening at the base or the tip is the entire message.
- **Body:** buckets (current, 1–30, 31–60, 61–90, 90+) with value and count; then the top 5 names inside the worst bucket.
- **Tap-through:** a bucket opens its names, ranked by amount; a name opens its promise-to-pay history.

**Visual form:** stacked horizontal bar or a stepped pyramid, worst bucket weighted visually; ghost outline for prior period.

**Decision it triggers:** stop supply to a named account; hand a bucket to a collector; provide for or write off the tail; change credit terms for a segment.

**Trust line:** "As of 17 Aug. Unallocated receipts TZS 40M are not yet applied and may improve the 1–30 bucket."

**How it fails badly:** it shows buckets without names, so it is a statistic and not a work list; unallocated cash makes the old buckets look worse than reality and nobody warns of it; disputed items sit in 90+ alongside genuine slow-payers, which destroys trust in the whole stack; it ages by document date when the business runs on due date.

**Instantiations**
- **Who Owes Us, and How Long** — debtor book by age with the worst names surfaced.
- **Stock by Age** — value sitting in 0–30, 31–90, 91–180, 180+ days of no movement.

---

### 8. TREND & TRAJECTORY

**Question shape:** *Which way are we going, and is this move real or just noise?*
The archetype that prevents the owner from reacting to a random week.

**Anatomy on a phone**
- **Top:** the current value and the trajectory verdict in words — "Rising, 4th consecutive month" or "Flat within normal range".
- **Comparison:** a normal-range band behind the line (rolling mean ± typical variation) — the band is what makes the movement interpretable. Plus a same-period-last-year line for any seasonal business.
- **Body:** 13 to 24 points on one line. Never 3 points. Annotate known events (price rise, branch opening, factory shutdown) with small markers.
- **Tap-through:** the same series decomposed by branch or by product family.

**Visual form:** sparkline or small line chart with a shaded normal band and a dotted seasonal comparison; a plain-language verdict above it.

**Decision it triggers:** whether to intervene at all — and, when it is a run of consecutive moves outside the band, to open the Variance Bridge behind it.

**Trust line:** "Monthly to July; August partial and excluded from the trend. Band = 12-month mean ± 1 standard deviation."

**How it fails badly:** it includes the incomplete current period and manufactures a false collapse every month; no band, so every wiggle looks meaningful; too few points; the y-axis is truncated to dramatise a 2% move; seasonality is ignored in a business with a Ramadan and a Christmas peak.

**Instantiations**
- **Which Way the Margin Is Going** — 24 months of gross margin rate with the normal band.
- **How Long Debtors Take to Pay** — DSO trend with the band and the collection-policy change markers.

---

### 9. FORECAST & RUNWAY

**Question shape:** *What happens next, and on what date do we hit the wall?*
Distinguished from Trend by carrying a commitment: a projected value, a date, and a confidence.

**Anatomy on a phone**
- **Top:** the date, not the number — "Cash goes below TZS 100M on 4 September (12 working days)". A date creates urgency that a projection line never does.
- **Comparison:** the forecast against the plan/target for the same endpoint, and against the previous forecast (forecast drift is itself an executive signal).
- **Body:** actual line to today, then a fan or a high/likely/low band forward; below it the 3 largest assumptions with their values, editable-in-principle.
- **Tap-through:** the driver list — the receipts, payments or orders the forecast depends on, ranked by how much they move the answer.

**Visual form:** line with a forward fan, a threshold line, and a marked crossing date.

**Decision it triggers:** the pre-emptive act while there is still time — draw the overdraft, delay a payment run, pull a collection, move a purchase.

**Trust line:** "Forecast run 17 Aug 23:00. 82% of next 30 days' receipts are from confirmed due dates; the rest is modelled. Last month's forecast was 6% optimistic."

**How it fails badly:** it presents a single line with no band and is treated as a promise, then is disbelieved forever after the first miss; it never reports its own past accuracy; it silently changes method between runs; the crossing date is buried below a chart nobody scrolls to.

**Instantiations**
- **How Long the Cash Lasts** — projected balance to the day it crosses the floor.
- **What Runs Out First** — SKUs projected to stock out inside their reorder lead time, ranked by lost sales value.

---

### 10. CONCENTRATION & EXPOSURE

**Question shape:** *How much of us depends on one thing that could stop?*
Not a performance report. A fragility report. Nothing here may be wrong today.

**Anatomy on a phone**
- **Top:** the single-point exposure — "Our largest customer is 27% of margin" or "One supplier covers 61% of purchases".
- **Comparison:** the same share a year ago (is dependence deepening?) and a stated tolerance line the board set.
- **Body:** the top 5 with share and cumulative share; a "cover" note — how long we could survive without each, or what the replacement cost is.
- **Tap-through:** the entity's own history, terms, and contractual notice period.

**Visual form:** a Pareto (bars plus cumulative line) with the tolerance threshold drawn across it.

**Decision it triggers:** deliberately diversify; sign a second supplier; take credit insurance; renegotiate before dependence hardens; cap a customer's exposure.

**Trust line:** "Rolling 12 months to 31 Jul. Share of gross margin, not revenue. Related entities grouped."

**How it fails badly:** it measures share of revenue when the exposure is margin or cash; it does not group related parties, so one owner appears as four customers and the concentration hides; it has no threshold, so it is a fact rather than a warning; it is run once and never again, when the whole value is the year-on-year drift.

**Instantiations**
- **If Our Biggest Customer Left** — margin share, cover, and terms of the top 5 accounts.
- **How Much Rides on One Supplier** — spend share, single-sourced items, and the switching lead time.

---

### 11. COHORT & RETENTION

**Question shape:** *Do the ones we won stay, and are the recent ones better or worse than the old ones?*
Groups by the period something started and follows it forward. The only archetype that separates "we are growing" from "we are churning and replacing".

**Anatomy on a phone**
- **Top:** the verdict on recency — "Customers won this year are worth 18% less at month 6 than last year's".
- **Comparison:** cohort against cohort. That is the comparison; no external target needed.
- **Body:** 5–6 cohort rows, each a small line of value or survival by months-since-start; recent cohorts highlighted, older ones ghosted.
- **Tap-through:** the accounts inside a failing cohort.

**Visual form:** small-multiple lines or a compact heat grid (cohort down, months-since across). On a phone, lines beat a grid.

**Decision it triggers:** fix acquisition quality rather than acquisition volume; change onboarding, credit screening, or the first-90-days service; stop rewarding new-account counts.

**Trust line:** "Cohorts by first-invoice month. Cohorts younger than 3 months shown but not yet interpretable."

**How it fails badly:** it is built as a grid of 60 cells and is unreadable on a phone; cohorts are too small to mean anything; it reports counts retained when the money is in value retained; it is shown to an owner who has not been told what a cohort is, with no plain-language verdict on top.

**Instantiations**
- **Do New Customers Stay?** — value retained by first-purchase cohort at months 3, 6, 12.
- **Do New Hires Last?** — retention and productivity by hire quarter.

---

### 12. CYCLE-TIME & FLOW

**Question shape:** *How long does this take end to end, and where exactly does it stall?*
Converts a vague "we are slow" into a named stage with a name attached to it.

**Anatomy on a phone**
- **Top:** total elapsed time and its trend — "Order to cash: 41 days, up from 34".
- **Comparison:** against the standard/agreed service time, and against the same measure a quarter ago.
- **Body:** the stages as segments of one bar, each with its days; the stage exceeding its standard is called out with the count of items currently stuck there.
- **Tap-through:** the stuck items in the worst stage, with an owner per item.

**Visual form:** a segmented horizontal bar (stage durations) with standard-time ticks; optionally a distribution note (median and 90th percentile), because the average hides the pathological tail.

**Decision it triggers:** attack one stage; add capacity or authority at the specific bottleneck; remove an approval step that is costing more days than it saves shillings.

**Trust line:** "Median over items completed in the last 60 days. Cancelled items excluded. 90th percentile shown alongside."

**How it fails badly:** it reports averages only, so the 5% catastrophic cases (the ones that lose customers) are invisible; stages are system statuses rather than real-world handoffs; it measures completed items only and therefore never sees the items that are stuck forever; the bottleneck stage has no owner.

**Instantiations**
- **How Long From Order to Cash** — order, delivery, invoice, due, receipt, allocation, with the stall stage named.
- **How Long From Request to Delivery** — requisition, approval, PO, supplier, GRN, put-away.

---

### 13. RECONCILIATION & ASSURANCE

**Question shape:** *Do two things that must agree, agree — and can I believe the rest of this app?*
The archetype that makes every other archetype credible. An executive suite without one is a suite of assertions.

**Anatomy on a phone**
- **Top:** the verdict and the difference — "Matched, TZS 0" or "Out by TZS 2.4M across 3 accounts".
- **Comparison:** the two sides side by side, plus how long the difference has persisted. Age of a difference matters more than its size.
- **Body:** the reconciling items grouped by reason (timing, in transit, unmatched, disputed, error), each with value and age.
- **Tap-through:** the unmatched items and who owns clearing them.

**Visual form:** two-column comparison with a difference bar; reason chips underneath.

**Decision it triggers:** stop trusting a downstream number until this clears; assign the clearing; escalate a supplier or bank dispute; investigate a control failure at a named till or branch.

**Trust line:** "Bank feed to 16 Aug; ledger to 17 Aug — one day of legitimate timing difference is expected."

**How it fails badly:** it shows a difference with no ageing, so a chronic TZS 2M hole looks like today's timing gap; "unexplained" is not separated from "timing", which is the difference between an accounting artefact and a theft; it is only ever green because the tolerance was set wide enough to guarantee it; it reconciles things that are not actually required to agree, and trains people to ignore red.

**Instantiations**
- **Does the Cash Match?** — till, bank, mobile money and ledger, with the unexplained portion isolated.
- **Does the Stock Match the Count?** — book vs counted by location, with shrinkage value and repeat offenders.

---

### 14. DECISION DOCKET

**Question shape:** *What is waiting for me, what does it cost to keep waiting, and what happens if I do nothing?*
The action archetype. The owner's explicit ask ("do approvals") is this, and it should be treated as a report in its own right, not as a plumbing feature.

**Anatomy on a phone**
- **Top:** the queue and its cost of delay — "9 items · TZS 340M · oldest 6 days · 2 blocking despatch".
- **Comparison:** the item against its own policy (within limit, over limit, off-contract, above last price) and against your own median approval time.
- **Body:** items ranked by consequence of delay, not by arrival order. Each line: what, who asked, how much, why it needs you, what breaks if it waits.
- **Tap-through:** approve / reject / send back with a reason — and the one piece of evidence you need to decide (last price paid, customer's current exposure, remaining budget).

**Visual form:** an action list with a value column and an urgency rail; a summary strip of queue size, value and age.

**Decision it triggers:** the approval itself. And, over time, a delegation decision: items that you always approve unchanged should have their limit raised.

**Trust line:** "Live queue as at 11:04. Approving here posts immediately and is audited to your name."

**How it fails badly:** it is a raw inbox in arrival order with no value and no consequence, so it is worked front-to-back rather than by importance; it lacks the one fact needed to decide, forcing a phone call anyway; it never measures itself, so nobody notices that the owner is the bottleneck in Cycle-Time; the queue grows an "informational" section and stops being a docket.

**Instantiations**
- **Waiting on You** — every approval addressed to this user, ranked by cost of delay.
- **What Waiting Cost Us** — items approved late last month and the discount, stock-out or delivery each delay caused.

---

## 2. The archetype × domain matrix

The generative engine. Read down a column to build a domain's section; read across a row to keep an archetype's family consistent.

**Legend:** ● flagship — an owner opens this unprompted · ○ genuine, second wave · blank — do not build

| Archetype | Profit | Cash | AR | AP | Stock | Sales | Cust | Buy | Make | People | Assets | Tax | Growth |
|---|:--:|:--:|:--:|:--:|:--:|:--:|:--:|:--:|:--:|:--:|:--:|:--:|:--:|
| 1 Flash | ○ | ● | ○ | | | ● | | | ○ | ○ | | ○ | |
| 2 Scorecard | ● | ○ | ● | ○ | ● | ● | ○ | ○ | ● | ○ | | ● | ● |
| 3 Position & Movement | ○ | ● | ● | ● | ● | | | ○ | ○ | | ● | ● | |
| 4 Variance Bridge | ● | ● | ○ | ○ | ● | ● | ○ | ● | ● | ○ | | ○ | ○ |
| 5 League Table | ● | ○ | ● | | ● | ● | ● | ○ | ● | ● | ○ | ○ | ○ |
| 6 Exception Register | ● | ● | ● | ● | ● | ● | ○ | ● | ○ | ○ | ○ | ● | |
| 7 Ageing Pyramid | | | ● | ● | ● | ○ | ○ | ● | ○ | | ○ | ○ | |
| 8 Trend & Trajectory | ● | ● | ● | ○ | ● | ● | ● | ○ | ● | ○ | ○ | ○ | ● |
| 9 Forecast & Runway | ○ | ● | ● | ● | ● | ● | ○ | ● | ○ | ○ | ○ | ● | ○ |
| 10 Concentration & Exposure | ○ | ○ | ● | ○ | ○ | ● | ● | ● | ○ | ● | | | ○ |
| 11 Cohort & Retention | | | ○ | | ○ | ○ | ● | | | ● | | | ● |
| 12 Cycle-Time & Flow | | ● | ● | ○ | ○ | ● | ○ | ● | ● | ○ | ○ | | |
| 13 Reconciliation & Assurance | ○ | ● | ● | ● | ● | ○ | | ● | ○ | ○ | ○ | ● | |
| 14 Decision Docket | ○ | ● | ● | ● | ○ | ● | ○ | ● | ○ | ● | ○ | ○ | ○ |

### How to read it

- **Dense rows carry the suite.** Exception Register, Trend, Forecast, League Table and Decision Docket touch nearly every domain — build their *shell components* first and instantiate them cheaply thereafter. One well-built Exception Register component yields eleven reports.
- **Dense columns deserve a section, not a screen.** Cash, AR, Stock and Sales each earn a small family of 5–6 reports; the phone navigation should be organised by these domains, with archetypes as the forms inside them.
- **Empty cells are a decision, not an omission.** There is no Cohort of Payables and no Ageing of Profit. Resist the symmetry instinct; a filled matrix is a sign the taxonomy has gone slack.
- **The matrix is a generator, not a backlog.** It yields ~90 defensible reports. Version 1 should ship 14–18. The owner's home screen holds six tiles.

### The v1 shortlist the matrix argues for

Pick the cells that are both flagship and *decision-dense*: Cash Forecast, Margin Variance Bridge, Branch League, Debtor Ageing, Exception Registers for below-cost and fiscal-receipt gaps, Today's Trade, Waiting on You, Stock That Isn't Moving, Cash Reconciliation, The Seven Numbers.

---

## 3. Naming doctrine

A report's name is a contract with the person who taps it. If the name does not state what you will know afterwards, the tap is a gamble — and executives stop gambling after about three losses.

### 3.1 The core rules

**R1 — The name states the promise, not the plumbing.**
Name the question answered or the object examined. Never name the data source, the module, the table, or the technology. "Sales Invoice Extract" describes where the number came from; the owner does not care where the number came from.

**R2 — Question-shaped or noun-phrase, chosen on purpose.**

| Use a **question-shaped** name | Use a **noun-phrase** name |
|---|---|
| The report exists because the owner already carries a doubt | The report is a standing object visited on routine |
| The answer is a *finding* that varies each time | The answer is a *position* that is always there |
| Archetypes: Variance Bridge, Forecast, Reconciliation, Concentration, Cohort, Cycle-Time | Archetypes: Position & Movement, League Table, Exception Register, Ageing, Decision Docket, Flash |
| *Where the Margin Went · How Long the Cash Lasts · Does the Cash Match?* | *Branch League · Today's Trade · Waiting on You · Debtor Ageing* |

Mixing forms *within* an archetype family is the error. Mixing them *across* archetypes is correct and helps the owner feel the difference between a diagnosis and a station.

**R3 — Banned as a whole name, and banned as a trailing noun.**
`Report`, `Summary`, `Analysis`, `Data`, `Overview`, `Dashboard`, `Metrics`, `KPIs`, `Statistics`, `Details`, `Info`, `Management`, `Module`, `Master`, `List`, `Screen`, `View`, `Insights`, `Intelligence`.
Test: strike the banned word. If the name still means the same thing, the word was noise ("Sales Report" → "Sales" — no loss, and no promise either). Every one of these words is a confession that the author did not decide what the screen is for.

**R4 — Two names, always: the SCREEN name and the FULL name.**

| | Screen name (tile, tab, header) | Full name (catalogue, menu, share sheet, PDF header) |
|---|---|---|
| Length | **≤ 22 characters, ≤ 3 words** | **≤ 60 characters** |
| Job | Recognition at a glance | Disambiguation when scanning 40 entries |
| Form | Shortest unambiguous handle | Screen name + em dash + one clause naming the comparison |
| Example | `Margin Gap` | `Where the Margin Went — this month against plan, by cause` |
| Example | `Cash Runway` | `How Long the Cash Lasts — projected balance and the date it turns` |

The 22-character limit is not aesthetic: at 360 CSS pixels a two-line tile label wraps into ugliness and a truncated label ("Consolidated Grou…") is a broken promise. The full name may ask a question; the screen name usually should not, because question marks read badly at tile size.

**R5 — Family consistency. One grammar per archetype.**
The suite must feel authored, not accumulated. Templates:

| Archetype | Naming template | Example |
|---|---|---|
| Flash | `Today's <thing>` | Today's Trade · Today on the Floor |
| Scorecard | `<Domain> Against Plan` / `The <N> Numbers` | Factory Against Plan · The Seven Numbers |
| Position & Movement | `Where the <thing> Is` / `How the <thing> Moved` | Where the Cash Is · How the Stock Value Moved |
| Variance Bridge | `Where the <thing> Went` / `Why <thing> Is Short` | Where the Margin Went · Why Cash Is Short |
| League Table | `<unit> League` | Branch League · Route League · Seller League |
| Exception Register | `<things> That <broke the rule>` | Sold Below Cost · Stock That Should Not Exist |
| Ageing Pyramid | `<thing> by Age` / `Who Owes Us, and How Long` | Stock by Age · Supplier Bills by Age |
| Trend & Trajectory | `Which Way <thing> Is Going` | Which Way the Margin Is Going |
| Forecast & Runway | `How Long <thing> Lasts` / `What Runs Out First` | How Long the Cash Lasts |
| Concentration | `If <thing> Stopped` / `How Much Rides on <thing>` | If Our Biggest Customer Left |
| Cohort | `Do <group> Stay?` | Do New Customers Stay? · Do New Hires Last? |
| Cycle-Time | `How Long From <A> to <B>` | How Long From Order to Cash |
| Reconciliation | `Does <A> Match <B>?` | Does the Cash Match? · Does the Stock Match the Count? |
| Decision Docket | `Waiting on You` / `What Waiting Cost Us` | Waiting on You |

A new report is named by filling in its archetype's template. If the template will not fit, the archetype is probably wrong.

**R6 — The name carries the question; the subtitle carries the comparison and the as-of.**
Do not stuff "vs Budget YTD" into the name. `Where the Margin Went` on the tile; `against plan · month to date · as of 23:00` in the subtitle line. This keeps names stable when the comparison basis is switched.

**R7 — Name the condition, never the person.**
`Branches Below Standard`, not `Worst Branches`. The ranking still exposes the bottom; the name does not editorialise. In a hierarchical business a blaming name gets the report suppressed, not the problem fixed.

**R8 — No period, no filter, no value in the name.**
`Q3 Sales`, `Top 10 Customers 2026`, `TZS 5M+ Debtors` all rot. Time and filters live in the as-of line and the controls. The one exception: a standing rule that defines the report's identity (`The Seven Numbers`) may carry its number.

**R9 — The twin test.**
Two reports may not share a leading phrase that hides their difference. `Sales Performance` and `Sales Performance by Branch` are a naming failure — they are a League Table and a Scorecard, and they should read as different things: `Sales Against Plan` and `Branch League`.

**R10 — The gravity test.**
Say the name aloud to a serious business owner, in a room with the auditor and the bank. `Money Mondays`, `Cash Health Check`, `Profit Pulse Pro`, `The Margin Story` all fail. Plain, direct, slightly blunt is the register: this is a suite for someone deciding whether to pay a supplier or lay off a shift. No emoji, no exclamation, no alliteration for its own sake, no product-marketing nouns ("Insights", "360", "Cockpit", "Command Centre").

**R11 — First person only where it is genuinely the owner's own voice, and used consistently.**
`Waiting on You` (the docket is personal, addressed to the logged-in approver) is right. `Where My Margin Went` is right in a single-owner business and wrong the moment three managers share the app — standardise on `the` / `our`. Choose once, apply everywhere.

**R12 — Survive Swahili.**
The app will be read by Tanzanian managers and much of it will be spoken in Swahili even when the screen is English. A name that cannot be said in Swahili without an explanation is a bad name in English too.

- **Avoid metaphor and English idiom.** `Runway`, `burn`, `bleeding`, `leakage`, `deep dive`, `north star`, `health check`, `pipeline`, `funnel`, `headwinds` — these do not travel and they make a plain fact sound like consultancy. `How Long the Cash Lasts` → *"Fedha itadumu muda gani"*: exact. `Cash Runway` → nothing.
- **Avoid noun-stacking.** English tolerates `Customer Credit Exposure Concentration`; Swahili renders it as a chain of *ya/wa* that no one will read. Prefer a verb: `How Much One Customer Owes Us`.
- **Avoid phrasal verbs and puns.** `Write-offs on the Rise`, `Stock Take Take Two`. Puns are untranslatable and they undercut the seriousness of the number.
- **Prefer subject–verb–object questions.** Swahili forms these cleanly: `Who Owes Us` → *"Nani anatudai"*. `Does the Cash Match?` → *"Fedha zinalingana?"*. `Do New Customers Stay?` → *"Wateja wapya wanabaki?"*.
- **Keep loanwords that Tanzanian traders actually use.** *Stock*, *cash*, *branch*, *VAT*, *EFD*, *TZS* are spoken as-is in trade. Do not "improve" `Stock` into `Inventory` — nobody at the counter says inventory.
- **Test by back-translation.** Translate the name to Swahili and back to English with no context. If it returns something different, rename it. Run this before the name reaches a tile.

**R13 — One idea per name.**
If the name needs an "and", the screen is probably two screens. `Sales and Collections Performance` is two reports with one tile and no promise.

### 3.2 Eight before → after

| # | Bad | Screen name | Full name | Why the fix works |
|---|---|---|---|---|
| 1 | Sales Summary Report | **Today's Trade** | Today's Trade — sales, tickets and cash against the same weekday | Banned words gone; names the moment, and the full name states the comparison that makes it information rather than a number. |
| 2 | Gross Margin Analysis | **Margin Gap** | Where the Margin Went — this month against plan, by cause | Question-shaped for a diagnostic archetype; promises causes, which is what a Variance Bridge owes you. Translates cleanly (*"faida imepotelea wapi"*). |
| 3 | Debtors Ageing Report | **Debtor Ageing** | Who Owes Us, and How Long — by age, largest and oldest first | Keeps the term the finance team uses on the tile, but the catalogue name says who and how long, and promises exception-led ordering. |
| 4 | Inventory Analysis Dashboard | **Dead Stock** | Stock That Isn't Moving — value, age and where it sits | Names the condition, not the module. `Stock` not `Inventory` (spoken usage). The Exception Register family grammar is visible. |
| 5 | Branch Performance Overview | **Branch League** | Branch League — profit per branch against plan and the group rate | League Table family template; states the ranking rule so nobody argues about which number ranks them. |
| 6 | Cash Flow Projection Report | **Cash Runway** ✗ → **Cash to 30 Days** | How Long the Cash Lasts — projected balance and the date it crosses the floor | Shows the doctrine correcting itself: `Runway` fails R12 (untranslatable metaphor). The full name delivers a *date*, which is what a Forecast is for. |
| 7 | Purchase Order Approval Queue | **Waiting on You** | Waiting on You — approvals, their value, and what each delay costs | Addressed to the person, so it earns a home-screen tile; the full name promises cost of delay, which turns an inbox into a report. |
| 8 | VAT Compliance Data Extract | **EFD Gaps** | Sales With No Fiscal Receipt — count, value and days open | Names the breach in the regulator's own terms; every banned word removed; ageing promised, so the register can escalate. |

### 3.3 Naming checklist (run before any name ships)

1. Strike `Report`/`Summary`/`Analysis`/`Overview`/`Data` — does anything survive? (R3)
2. Does it fill its archetype's template? (R5)
3. Screen name ≤ 22 characters and ≤ 3 words? Full name ≤ 60? (R4)
4. Does the name promise something you would be embarrassed not to deliver on that screen? (R1)
5. Back-translate through Swahili — same meaning? (R12)
6. Said aloud in front of the bank and the auditor — still serious? (R10)
7. Does any other report in the catalogue share its leading phrase? (R9)
8. Does it hold up unchanged when the period or the branch filter changes? (R8)

---

## 4. What this document governs

Downstream work — the report catalogue, the screen inventory, the navigation, the build backlog — is constrained by three things in this document, in this order:

1. **A report must be an instance of exactly one archetype.** If it is not, it is a dashboard or a data export, and it does not belong in the executive app.
2. **A report must occupy a ticked cell in the matrix.** New cells may be argued for; they may not be assumed.
3. **A report must be named by its archetype's template and pass the eight-point checklist.** Names are reviewed before screens are designed, not after.