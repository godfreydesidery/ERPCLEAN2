

===== DOMAIN: money — MONEY — profit, margin and earnings quality =====
# MONEY — Profit, Margin & Earnings Quality
**Executive Mobile · report catalogue v1 · 2026-08-18**
25 reports. Every one is an instance of exactly one archetype, named by its archetype's template, and passes the eight-point naming checklist. Currency is TZS throughout; "margin" always means gross margin in shillings unless a rate is explicitly named.

---

## Ranking at a glance

| Tier | # | Screen name | Archetype | Novelty |
|---|---|---|---|---|
| **1** | 1 | Today's Profit | Flash | NOVEL |
| **1** | 2 | Margin Gap | Variance Bridge | CLASSIC |
| **1** | 3 | Profit Against Plan | Scorecard | CLASSIC |
| **1** | 4 | Branch Profit League | League Table | CLASSIC |
| **1** | 5 | Sold Below Cost | Exception Register | CLASSIC |
| **1** | 6 | Discount Overrides | Exception Register | CLASSIC |
| **1** | 7 | Profit to Cash | Reconciliation & Assurance | **NOVEL** |
| **1** | 8 | Discounts Waiting | Decision Docket | CLASSIC |
| **1** | 9 | Where Month Lands | Forecast & Runway | CLASSIC |
| **2** | 10 | The Profit Ladder | Position & Movement | CLASSIC |
| **2** | 11 | Margin Direction | Trend & Trajectory | CLASSIC |
| **2** | 12 | Margin After Costing | Reconciliation & Assurance | **NOVEL** |
| **2** | 13 | Profit Quality | Scorecard | **NOVEL** |
| **2** | 14 | The Month-End Swing | Variance Bridge | **NOVEL** |
| **2** | 15 | Did Prices Hold? | Variance Bridge | **NOVEL** |
| **2** | 16 | Channel League | League Table | CLASSIC |
| **2** | 17 | Cost to Serve | League Table | **NOVEL** |
| **2** | 18 | Break-even Day | Forecast & Runway | **NOVEL** |
| **2** | 19 | Margin Given Back | Exception Register | **NOVEL** |
| **3** | 20 | Route Profit | League Table | **NOVEL** |
| **3** | 21 | Standard vs Actual | Reconciliation & Assurance | CLASSIC |
| **3** | 22 | If Sales Slip | Concentration & Exposure | **NOVEL** |
| **3** | 23 | Do Discounts Pay? | Cohort & Retention | **NOVEL** |
| **3** | 24 | Cost-to-Price Lag | Cycle-Time & Flow | **NOVEL** |
| **3** | 25 | If Five Left | Concentration & Exposure | CLASSIC |

11 NOVEL. The novel spine of this domain is the same argument stated five ways: **the profit you were told about and the profit that survived costing, returns, discounting, closing and collection are five different numbers, and no ERP shows an owner the distance between them.**

---

## TIER 1 — opened weekly or more

---

### 1. Today's Profit

| field | content |
|---|---|
| **Screen name** | `Today's Profit` |
| **Full name** | Today's Profit — margin earned so far against the same weekday |
| **Archetype** | 1 Flash |
| **The question it answers** | "Am I making money today at the rate I normally do?" |
| **Key figures** | (1) Margin earned today, TZS, rounded to 0.1M; (2) margin rate today, %; (3) sales today, TZS; (4) average margin per ticket, TZS; (5) share of today's sales that carried a discount, % |
| **The comparison** | Same weekday, 4-week median — as a delta chip on each line. Never "vs yesterday". Second chip: same weekday last month (for month-position effects — salary week trades differently). |
| **Exception lead** | If any branch is below 70% of its normal weekday margin rate, that branch's name and gap replace the third body line and the header turns amber. Silence is deliberate and earned: when nothing is out of band the screen says "Normal for a Tuesday". |
| **Consolidation level** | Group headline; rolls down to company then branch. Branch strip is the first tap. Must roll up — group figure is the sum of posted branch margin, not a re-derivation. |
| **Cadence** | Glance daily (the 11:00 and 17:00 look) |
| **Decision it triggers** | Owner / GM: make one phone call before lunch. "Kariakoo is at 41% of a normal Tuesday's margin at 11:00 — what is happening at the counter?" |
| **Tap-through** | The branch strip — same five numbers per branch. **Refuses:** invoice lines, ticket lists, salesperson names. Today is provisional; naming people on provisional data starts fights. |
| **Alert condition** | Push at 12:00 and 17:00 **only if** group margin rate is more than 3 percentage points below the weekday median, **or** any branch is below 60% of its weekday-median margin shillings. Otherwise no push. |
| **Data needed** | Posted and unposted sales by branch and hour; cost of each line at the cost basis in force (moving average); discount given per line; ticket count. ⚠ **Weekday-median baselines must be computed and stored** — a rolling 8-week profile per branch per weekday; no ERP keeps this. ⚠ **POS batches not yet synced must be identifiable and counted**, otherwise a van with no signal reads as a collapsing branch. ⚠ **Trading-calendar exceptions** (public holidays, Ramadan hours, branch closure) must be flagged or the baseline lies. |
| **Novelty** | **NOVEL** — every ERP flashes daily *sales*. Almost none flashes daily *margin*, because it requires costing the line at sale time. This is the single highest-frequency screen in the suite and the one that teaches an owner that revenue and profit move differently. |

---

### 2. Margin Gap

| field | content |
|---|---|
| **Screen name** | `Margin Gap` |
| **Full name** | Where the Margin Went — this month against plan, by cause |
| **Archetype** | 4 Variance Bridge |
| **The question it answers** | "I planned to make 1.5 billion of margin and I made 1.38. Where did the 120 million go?" |
| **Key figures** | (1) The gap itself, TZS, as the headline — not the margin; (2–7) six named cause bars, largest first: **price** (realised price vs plan price), **volume**, **mix** (what we sold, not how much), **purchase cost**, **discount**, **shrinkage & returns**; (8) residual, which must stay under 8% of the gap or the screen prints "not fully explained" |
| **The comparison** | The bridge *is* the comparison: plan → actual. A second, switchable basis: prior month, and same month last year. Basis is stated in the subtitle, never in the name. |
| **Exception lead** | The largest adverse bar is pre-expanded with its top-3 contributors named ("purchase cost −TZS 47M: cooking oil +18%, sugar +11%, cement +6%"). The screen opens on the loss, not on the total. |
| **Consolidation level** | Group by default; the same bridge must exist unchanged at company and branch level (an owner's first instinct after seeing a group bar is "which branch"). Bars must sum identically at every level — no allocation-only bars at group that vanish at branch. |
| **Cadence** | Weekly during the month; the definitive read at month-end |
| **Decision it triggers** | **Owner + CFO**, monthly, one decision per bar. Price bar → authorise a list-price rise or renegotiate supply. Mix bar → change what the floor pushes and what the incentive pays on. Discount bar → tighten authority limits (feeds report 6 and 8). Cost bar → move supplier or reprice. Each bar carries a named owner; a bar with no owner is a bar nobody fixes. |
| **Tap-through** | Any bar opens its League Table — which branches and which product families produced that effect, ranked. **Refuses:** the invoice register. A bridge that ends in a transaction list has failed; the cause is the answer. |
| **Alert condition** | Push once a week (Monday 07:00) **only if** the month-to-date gap is worse than −5% of plan margin, naming the largest bar. No push when on or ahead of plan. |
| **Data needed** | Plan margin by month, company, branch and product family, at the level of price and volume assumptions — not a single plan total; actual invoiced quantity, realised price, standard/plan price, actual cost per line; discount per line; returns and credit notes; stock shrinkage valued. ⚠ **A margin plan decomposed into price and volume assumptions** is the big build item — most groups budget one number per branch, which makes a price/volume split arithmetically impossible. ⚠ **A frozen plan-price list per period** (else "price variance" silently becomes "we changed the list price"). ⚠ **Mix requires a stable product-family hierarchy** with no mid-year re-parenting. |
| **Novelty** | CLASSIC — but the version most ERPs ship is a two-bar sales-vs-budget chart. The six mutually-exclusive causes with a policed residual are what make it executive. |

---

### 3. Profit Against Plan

| field | content |
|---|---|
| **Screen name** | `Profit Against Plan` |
| **Full name** | Profit Against Plan — the seven money measures and their gaps |
| **Archetype** | 2 Scorecard |
| **The question it answers** | "Are we hitting the money standard the board set — and which one measure is missing worst?" |
| **Key figures** | A verdict ("4 of 7 on plan") then seven fixed rows, each showing actual · target · gap: (1) gross margin TZS; (2) gross margin rate %; (3) contribution after direct branch cost; (4) operating profit; (5) net profit; (6) overhead as a share of gross margin; (7) margin per shilling of stock held |
| **The comparison** | The printed target on every row, plus a bullet gauge showing the acceptable band. The measure set is **frozen for the financial year** — an owner must be able to carry it in memory. |
| **Exception lead** | Rows sorted by size of miss in shillings, not by importance and never alphabetically. The worst miss is row one with its gap in bold. |
| **Consolidation level** | Group / company / branch, switchable; identical seven measures at every level. Rolls up by summation for shillings and by re-derivation for rates (never average a rate). |
| **Cadence** | Weekly glance; the formal read at month-end |
| **Decision it triggers** | **Owner + CFO** at the monthly management meeting: which single miss gets management attention for the next 30 days, and whether the plan or the performance is the thing that is wrong. Repeated misses on the same row for three months force a plan re-baseline decision. |
| **Tap-through** | Any red row opens its Variance Bridge (report 2 for margin rows, report 24/10 for cost rows). This pairing is the backbone. **Refuses:** a per-row 12-month chart — that is report 11's job and putting it here turns a scorecard into a dashboard. |
| **Alert condition** | No push. A scorecard that pushes becomes noise; it is a station you visit. |
| **Data needed** | Board-approved plan for all seven measures by company, branch and month; actual posted P&L mapped to the same seven; direct vs indirect branch cost classification; stock value at cost. ⚠ **A plan at branch level for every measure** — groups typically plan revenue only. ⚠ **Fixed vs variable cost classification on the chart of accounts** (needed for row 3 and reports 18 and 22). ⚠ **A recorded plan version and approval date** so the screen can state which plan it is judging against. |
| **Novelty** | CLASSIC |

---

### 4. Branch Profit League

| field | content |
|---|---|
| **Screen name** | `Branch Profit League` |
| **Full name** | Branch Profit League — margin per branch against plan and the group rate |
| **The question it answers** | "Which branches actually make money, on the same rule, and who is sliding?" |
| **Archetype** | 5 League Table |
| **Key figures** | (1) The spread as the headline — "best 31%, worst 12%, group 22%"; (2) per row: margin rate %, (3) margin shillings, (4) margin per square metre **or** per staff head (the size-neutraliser), (5) rank change since last month, (6) achievement vs that branch's own plan % |
| **The comparison** | The group rate printed as a reference line every row is measured against, **and** each branch against its own plan — because a mall counter and a wholesale depot are not comparable on rate alone. Comparability class is stated ("Retail counters · 6 branches"). |
| **Exception lead** | Not the leader — the biggest **rank fall** is called out at the top ("Mbagala −4 places") because a sliding good branch needs a different call from a chronic weak one. |
| **Consolidation level** | Group and company. Branches ranked only within their comparability class; cross-class ranking is refused by design and the screen says so. |
| **Cadence** | Weekly |
| **Decision it triggers** | **Owner / GM**: where this week's site visit goes, and which practice at the top branch gets written down and forced into the bottom three. **Branch Manager**: sees own row and the group line, not colleagues' names, on the branch-scoped build. |
| **Tap-through** | A row opens that branch's Scorecard (report 3 scoped to branch). **Refuses:** staff-level ranking. Naming individuals here makes the data political and the entries get gamed. |
| **Alert condition** | Push when a branch falls 3+ ranks in a month, or when any branch's margin rate drops below the group rate minus 8 points for two consecutive weeks. |
| **Data needed** | Margin and sales by branch; branch direct costs; branch plan; branch size attributes. ⚠ **A branch comparability class** (retail counter / depot / factory shop / route hub) — an attribute nobody captures, without which the table is dismissed on first viewing. ⚠ **Branch floor area and rostered headcount** for the size-neutraliser. ⚠ **Branch opening date** to exclude branches under 90 days. ⚠ **A stated policy on central-cost allocation** printed in the trust line. |
| **Novelty** | CLASSIC |

---

### 5. Sold Below Cost

| field | content |
|---|---|
| **Screen name** | `Sold Below Cost` |
| **Full name** | Sold Below Cost — lines that went out under landed cost, by value at risk |
| **Archetype** | 6 Exception Register |
| **The question it answers** | "Where are we selling things for less than they cost us — and is it still happening?" |
| **Key figures** | (1) Count and money together — "23 lines · TZS 8.4M given away"; (2) new vs repeat vs cleared trio; (3) per row: product, branch, shillings below cost, how many days this product has been breaching; (4) worst-offending product's cumulative loss this month |
| **The comparison** | Same count and money seven days ago, and the repeat share. A breach that survives a week is a control failure, not an incident, and the screen says which is which. |
| **Exception lead** | Ranked by shillings lost, not by count and not by date. The top line is the biggest money leak of the week. |
| **Consolidation level** | Group, drillable to company and branch. Branch managers see their own. Must roll up: the group count is the sum of branch counts, no de-duplication. |
| **Cadence** | Weekly; on-alert for large single breaches |
| **Decision it triggers** | **GM / Branch Manager**: fix the price file, or accept the breach with a reason (clearance, promotion, contractual). **Owner**: if the same product repeats for three weeks, the decision is that the price file is not being maintained and that is a person problem, not a pricing problem. |
| **Tap-through** | The breaching product-branch pair with its price history and cost history on one small chart, plus **accept / assign / fix price** write-back. **Refuses:** the individual receipts. Accepting a breach must be possible or the register clogs and dies within a fortnight. |
| **Alert condition** | Push immediately when a single line loses more than TZS 500k, or when a product breaches at 3+ branches on the same day (that is a cost-update event, not a counter error). |
| **Data needed** | Landed cost per item per branch at the moment of sale; selling price per line; discount applied; approved clearance and promotion flags. ⚠ **Landed cost including freight, duty and clearing** allocated to the item — most ERPs compare against purchase price only, which understates the breach. ⚠ **A clearance / promotional-price authorisation flag** so approved losses are separated from mistakes. ⚠ **An "accepted breach" state with an expiry date.** |
| **Novelty** | CLASSIC — but the ageing, the repeat detection and the accept path are what keep it alive past month two. |

---

### 6. Discount Overrides

| field | content |
|---|---|
| **Screen name** | `Discount Overrides` |
| **Full name** | Discounts Beyond the Agreed Limit — who gave them and what they cost |
| **Archetype** | 6 Exception Register |
| **The question it answers** | "Who is giving away margin beyond the authority they have, and how much has it cost me this month?" |
| **Key figures** | (1) "TZS 62M discounted · TZS 18M beyond limit"; (2) breaches count, new vs repeat; (3) per row: branch, seller role, discount % given vs limit %, shillings beyond limit; (4) the group's average discount rate, %; (5) share of sales value that carried any discount, % |
| **The comparison** | Each discount against **the giver's authorised limit** (the rule), and this month's discount rate against last month's and against plan. |
| **Exception lead** | Beyond-limit shillings first. Within-limit discounting is shown only as a single context number — the exception is the breach, not the practice. |
| **Consolidation level** | Group / company / branch. Rolls up. Branch view shows roles; only the Owner/GM view resolves roles to named users. |
| **Cadence** | Weekly |
| **Decision it triggers** | **GM**: withdraw or reduce a discount limit; retrain; or, if 80% of "breaches" come from one customer segment, **CFO** decides the limit is unrealistic and changes the rule rather than the people. |
| **Tap-through** | The breach with the customer's total margin history (so a legitimate strategic discount is visible as such), plus accept/assign. **Refuses:** a full discount register — that is a finance extract, not an executive screen. |
| **Alert condition** | Push when a single order is discounted more than TZS 1M beyond limit, or when beyond-limit shillings exceed 2% of the week's margin. |
| **Data needed** | Discount per line and per order; the discounting user's role and authority limit; customer contract prices (a contract price is not a discount and must not be counted as one). ⚠ **Discount authority limits per role/user, held as data** — usually a policy in a memo, not a field. ⚠ **Contract / agreed-price flag** to suppress false breaches. ⚠ **Separation of line discount, header discount, and price-override**, which most systems collapse into one number and thereby hide half the leak. |
| **Novelty** | CLASSIC in intent, rarely built with the authority limit as data — which is the only thing that makes it a *rule* breach rather than a statistic. |

---

### 7. Profit to Cash

| field | content |
|---|---|
| **Screen name** | `Profit to Cash` |
| **Full name** | Does the Profit Turn Into Cash? — profit against cash actually collected |
| **Archetype** | 13 Reconciliation & Assurance |
| **The question it answers** | "The books say I made 400 million last month. Where is it? My bank did not grow by 400 million." |
| **Key figures** | (1) Verdict + difference — "Profit TZS 412M · cash from trading TZS 96M · gap TZS 316M"; (2–6) the gap by reason, each with value and **age**: money still owed by customers, money tied up in new stock, money paid to suppliers ahead of sale, tax paid, non-cash entries (depreciation, provisions, revaluation); (7) the persistent portion — how much of the gap has been unconverted for more than 90 days |
| **The comparison** | The two sides side by side, plus the 12-month cash conversion rate (cash from trading ÷ profit) so a bad month is separable from a bad habit. |
| **Exception lead** | The largest reason bar, and above all the **persistent** portion — a gap that has not converted in 90 days is not timing, it is value that may never arrive. |
| **Consolidation level** | Group and company (cash is managed at company level). Branch-level is refused: branches do not hold the bank. Rolls up. |
| **Cadence** | Month-end, every month, without exception |
| **Decision it triggers** | **Owner + CFO**: stop celebrating a profit that has not converted; act on the specific reason — collect, stop the stock build, or, when the gap is non-cash, understand that this month's profit was an accounting result. It is also the trigger for questioning whether a bonus should be paid on that profit. |
| **Tap-through** | The largest reason's composition — top contributors by customer or product family. **Refuses:** the full cash flow statement. An owner asked one question; the statement answers forty. |
| **Data needed** | Posted profit; cash receipts and payments classified as trading vs financing vs capital; change in receivables, payables and stock; non-cash P&L entries identified. ⚠ **A non-cash flag on P&L entries** (depreciation, provision movement, stock revaluation, FX revaluation) — the single biggest build item here; without it the "non-cash" bar is a residual and the report explains nothing. ⚠ **Ageing of the unconverted portion**, which requires linking the profit period to the collection event. ⚠ **Classification of payments as trading vs capital.** |
| **Novelty** | **NOVEL** — this is the "earnings quality" question in the owner's own words. Almost no ERP puts profit and cash on one screen with the difference explained by named business reasons and aged. It is the report most likely to change how an owner reads every other number in the suite. |

---

### 8. Discounts Waiting

| field | content |
|---|---|
| **Screen name** | `Discounts Waiting` |
| **Full name** | Waiting on You — price and discount requests, ranked by the margin they give away |
| **Archetype** | 14 Decision Docket |
| **The question it answers** | "What price decisions are sitting on me, what do they cost, and what breaks if I keep waiting?" |
| **Key figures** | (1) "7 requests · TZS 41M order value · TZS 6.2M margin at stake · oldest 3 days · 2 blocking despatch"; (2) per row: customer, order value, requested discount % vs limit, **resulting margin rate**, what breaks if it waits; (3) your median approval time this month |
| **The comparison** | Each request against policy (within limit / over limit / below floor price / worse than this customer's last 3 orders) **and** against your own median response time. |
| **Exception lead** | Ranked by consequence of delay — a despatch-blocking request with a truck loaded outranks a larger request with no deadline. Never arrival order. |
| **Consolidation level** | Personal — every item addressed to the logged-in approver, across all companies and branches. Rolls up in the sense that a group owner sees everything they are the approver for. |
| **Cadence** | Glance daily; it is the app's action tile |
| **Decision it triggers** | **Owner / GM / CFO** (whoever holds the limit): approve, reject, or send back with a reason. Over time it triggers a **delegation decision** — requests you approve unchanged 20 times running should have their limit raised, and the screen should say so. |
| **Tap-through** | The one fact needed to decide: this customer's last 3 prices, their current outstanding balance, and the resulting margin — then approve / reject / send back. **Refuses:** the full order and its lines. If you must read the order, the docket has failed to summarise it. |
| **Alert condition** | Push on arrival for anything blocking despatch or above TZS 5M order value; a daily 08:00 digest otherwise; escalate at 24 hours old. |
| **Data needed** | Pending price/discount approval requests with requester, value, requested and floor price, and the resulting margin; approval limits; despatch dependency. ⚠ **"What breaks if this waits"** — a link from the request to the blocked despatch/production event; almost never modelled. ⚠ **Floor price per item** (the price below which approval is mandatory). ⚠ **Approver response-time history**, so the docket can measure itself and reveal that the owner is the bottleneck. |
| **Novelty** | CLASSIC as an approval queue; **the ranking by margin at stake and cost of delay is what makes it a report rather than an inbox.** |

---

### 9. Where Month Lands

| field | content |
|---|---|
| **Screen name** | `Where Month Lands` |
| **Full name** | What This Month's Profit Will Land At — projection, range, and last month's error |
| **Archetype** | 9 Forecast & Runway |
| **The question it answers** | "With 11 days left, what will this month actually finish at — and can I still make plan?" |
| **Key figures** | (1) Likely landing, TZS, with a high/low band; (2) **the date the month can no longer reach plan** if the current run-rate holds — a date, not a number; (3) shillings of margin per remaining trading day required to hit plan vs the current daily rate; (4) plan; (5) last month's forecast error, % |
| **The comparison** | Forecast vs plan for the same endpoint, and forecast vs **the previous forecast** — forecast drift within a month is itself an executive signal (a landing that has slid three times is a management problem). |
| **Exception lead** | If the required daily rate exceeds the best day achieved this year, the screen says "plan is no longer reachable" in words, at the top. Executives need permission to stop chasing an impossible number. |
| **Consolidation level** | Group and company; branch on tap. Rolls up — group landing is the sum of company landings, each with its own band. |
| **Cadence** | Weekly, tightening to daily in the last week of the month |
| **Decision it triggers** | **Owner + GM** in the last 10 days: push a specific push (release a promotion, pull a big order forward, chase a factory batch), or accept the miss and stop discounting to chase a number that costs more margin than it gains. |
| **Tap-through** | The driver list — the confirmed orders, scheduled despatches and factory batches that most move the answer, ranked by shillings of impact. **Refuses:** a scenario editor. Executives do not model on phones. |
| **Data needed** | Month-to-date actual margin; trading-day calendar; confirmed forward orders and their margin; historical intra-month shape by branch. ⚠ **A trading-day calendar with the group's real seasonality** (month-end salary weeks, Ramadan, festive peaks) — a straight-line pro-rata forecast is wrong in every trading business and will discredit the screen in the first month. ⚠ **Forecast accuracy history**, stored per run, so the screen can confess its own past error. ⚠ **Committed forward order book with margin**, not just value. |
| **Novelty** | CLASSIC as a concept; the stored forecast-error line and the "plan no longer reachable" verdict are the parts usually missing. |

---

## TIER 2 — monthly, or on alert

---

### 10. The Profit Ladder

| field | content |
|---|---|
| **Screen name** | `The Profit Ladder` |
| **Full name** | How the Profit Was Made — from sales down to net, step by step |
| **Archetype** | 3 Position & Movement |
| **The question it answers** | "Of every hundred shillings that came in, how many were still mine at the bottom — and which step took the most?" |
| **Key figures** | (1) Net profit as the closing position, TZS; then the ladder: (2) sales; (3) less cost of goods → gross margin; (4) less direct branch/route cost → contribution; (5) less overheads → operating profit; (6) less finance, FX and tax → net. Each step also shown as shillings per 100 of sales. |
| **The comparison** | The same ladder for the prior month drawn as a ghost outline, and the plan ladder as a target tick on each step. Steps are **business categories** ("keeping the branches open", "head office", "cost of borrowing") — never GL account names, never "Suspense 9200". |
| **Exception lead** | The step whose shillings-per-100 worsened most against prior month is highlighted with its own delta, above the ladder. |
| **Consolidation level** | Group / company / branch (branch ladder stops at contribution unless allocation policy is agreed and stated). Rolls up. |
| **Cadence** | Month-end |
| **Decision it triggers** | **Owner + CFO**: which layer of the business is the problem — buying, selling, running the branches, or head office. It is the routing screen that decides which of reports 2, 17, 24 gets opened next. |
| **Tap-through** | Any step opens its composition — top contributors within the step, not transactions. **Refuses:** the trial balance. |
| **Data needed** | Posted P&L mapped to the six business steps; branch direct-cost identification; FX and finance cost separation. ⚠ **A business-language mapping of the chart of accounts** — one attribute per account naming its ladder step, owned by the CFO and versioned. ⚠ **A stated overhead allocation policy** (or the honest refusal to allocate, printed). ⚠ **Residual discipline: an "other" step over 5% invalidates the screen.** |
| **Novelty** | CLASSIC — but "P&L on a phone in the owner's words, with per-100 units" is a genuinely different object from an exported income statement. |

---

### 11. Margin Direction

| field | content |
|---|---|
| **Screen name** | `Margin Direction` |
| **Full name** | Which Way the Margin Is Going — 24 months with the normal range |
| **Archetype** | 8 Trend & Trajectory |
| **The question it answers** | "Is my margin genuinely slipping, or is this just a bad month?" |
| **Key figures** | (1) Current month's margin rate, %; (2) verdict in words — "Falling, 4th consecutive month below the normal range"; (3) the normal band (12-month mean ± 1 SD); (4) same month last year; (5) shillings of margin that the drift is worth per month at current volume |
| **The comparison** | The band is the comparison — it converts a wiggle into a signal. Plus a dotted same-period-last-year line for a business with festive and Ramadan peaks. |
| **Exception lead** | The verdict sentence, above the chart. If the last three points are outside the band on the same side, the screen escalates with a direct link to report 2. |
| **Consolidation level** | Group; decomposable to company, branch and product family on tap. Rolls up. |
| **Cadence** | Month-end |
| **Decision it triggers** | **Owner + CFO**: whether to intervene at all. This report's main job is preventing expensive reactions to noise — and making a genuine slow bleed impossible to rationalise away. |
| **Tap-through** | The same series split by branch or product family, to locate the drift. **Refuses:** the current incomplete month inside the trend line — it is shown separately and excluded from the band. |
| **Data needed** | 24+ months of margin rate at group and segment level, on a **consistent costing basis**. ⚠ **Costing-method change markers** — if the group moved from FIFO to moving average, the series has a break and the band is nonsense unless annotated. ⚠ **Event annotations** (price rise, branch opening, factory shutdown, VAT change) as data. ⚠ Restated history when the product hierarchy changes. |
| **Novelty** | CLASSIC |

---

### 12. Margin After Costing

| field | content |
|---|---|
| **Screen name** | `Margin After Costing` |
| **Full name** | Does the Margin Survive Costing? — margin booked at sale against margin after landed cost |
| **Archetype** | 13 Reconciliation & Assurance |
| **The question it answers** | "The margin I saw on the day of sale — did it still exist once the freight, duty and supplier invoices landed?" |
| **Key figures** | (1) Verdict and difference — "Booked TZS 480M · after costing TZS 431M · lost TZS 49M (10%)"; (2) the difference by reason: freight and clearing added later, supplier price higher than GRN estimate, FX movement on import, cost corrections, uninvoiced receipts still estimated; (3) the **age** of the still-unfinalised portion; (4) the branches/product families where booked margin most overstates |
| **The comparison** | The two versions of the same month's margin, side by side, and the 6-month average shrinkage between them — a systematic 10% overstatement is a costing-policy problem, not a monthly surprise. |
| **Exception lead** | The product families whose booked margin most overstates reality, because those are the ones being priced on a fiction. |
| **Consolidation level** | Group / company; product family and branch on tap. Rolls up. |
| **Cadence** | Month-end, and on-alert after a large import lands |
| **Decision it triggers** | **CFO**: change the estimated landed-cost uplift used at sale time so the floor price protects real margin. **Owner**: stop trusting day-of-sale margin on imported lines by the amount this report quantifies — which recalibrates how they read report 1 and report 4. |
| **Tap-through** | The worst product family's cost story: estimated vs final landed cost over the last 6 receipts. **Refuses:** the GRN and invoice matching detail — that is a buyer's screen. |
| **Data needed** | Margin as computed at invoice time (**must be snapshotted at the moment of sale**), final costed margin after all landed-cost allocation, and the reason for each movement between them. ⚠ **A stored margin-at-sale snapshot per line** — the decisive build item; without it the "before" number is unrecoverable and this report cannot exist. ⚠ **Landed-cost components (freight, duty, clearing, insurance) allocable to receipt lines.** ⚠ **Received-not-invoiced value with the estimate used.** ⚠ FX rate at receipt vs at supplier-invoice settlement. |
| **Novelty** | **NOVEL** — this is literally the owner's "profit I thought I made vs profit I actually made", at line level, for an importing trading group. Nearly every ERP overwrites the estimate and destroys the evidence. |

---

### 13. Profit Quality

| field | content |
|---|---|
| **Screen name** | `Profit Quality` |
| **Full name** | How Much of This Profit Is Real — the cash-backed, one-off and estimated parts |
| **Archetype** | 2 Scorecard |
| **The question it answers** | "Of the profit I am being shown, how much would survive a hard look?" |
| **Key figures** | Verdict — "TZS 412M reported · TZS 268M durable" — then six standing rows, each actual vs its tolerance: (1) share cash-backed; (2) share from one-off items (asset sale, insurance claim, supplier rebate, FX gain); (3) share resting on estimates (uncosted receipts, unbilled work, accruals); (4) share from stock revaluation rather than trade; (5) provisions movement as a share of profit; (6) profit made after the last working day of the month (late entries) |
| **The comparison** | Each row against a board-set tolerance line, and against the same row's 6-month average. A rising estimate share is the classic early warning. |
| **Exception lead** | The largest quality deduction, in shillings, with its cause named — "TZS 68M of this month's profit is a stock revaluation, not trade". |
| **Consolidation level** | Group and company. Rolls up. Branch is refused — quality adjustments live at company level. |
| **Cadence** | Month-end; mandatory before any profit-linked payout or bank submission |
| **Decision it triggers** | **Owner + CFO**: whether to distribute, bonus, or borrow against this profit; and whether the reported number is fit to send to the bank or the auditor. A durable-profit figure below 70% of reported for two months triggers a review of the estimating policy. |
| **Tap-through** | The one-off items list with values and dates. **Refuses:** journal detail; the point is the proportion, not the entry. |
| **Data needed** | P&L with each line classified as trade / one-off / estimate / revaluation / provision; cash conversion from report 7; posting timestamps vs period end. ⚠ **A "non-recurring" flag on transactions and journals** — nobody captures this, and it is the whole report. ⚠ **An "estimate vs final" state on cost postings.** ⚠ **Posting-date vs entry-timestamp retention** to detect late entries. ⚠ Board-set tolerances per row. |
| **Novelty** | **NOVEL** — earnings quality is a listed-company discipline that private trading groups never get, and it is exactly what an owner needs before paying a dividend or signing a loan covenant. |

---

### 14. The Month-End Swing

| field | content |
|---|---|
| **Screen name** | `The Month-End Swing` |
| **Full name** | Why the Month Changed After Close — flash profit against final, by adjustment |
| **Archetype** | 4 Variance Bridge |
| **The question it answers** | "On the 31st you told me 400 million. The final accounts say 340. What happened in between, and does this happen every month?" |
| **Key factors / key figures** | (1) The swing as the headline — "−TZS 61M between flash and final"; (2) bridge bars by adjustment class: late supplier invoices, stock count adjustments, cost corrections, provisions and write-offs, cut-off corrections (sales posted to the wrong month), depreciation and accruals; (3) the 6-month average swing and its direction; (4) how many days after close the number stopped moving |
| **The comparison** | Flash → final for this month, and the 6-month swing history. A consistently negative swing means the flash number is biased, and the bias size is printed so the owner can mentally discount it. |
| **Exception lead** | The largest adjustment class, and any class that appears in 4+ of the last 6 months — a recurring "surprise" is not a surprise, it is a process defect. |
| **Consolidation level** | Group / company. Rolls up. Branch on tap where the adjustment was branch-specific. |
| **Cadence** | Month-end, after close |
| **Decision it triggers** | **CFO** owns it: fix the process that generates the recurring adjustment (supplier invoice lag, count timing, cut-off discipline). **Owner** decides how much to trust the mid-month numbers — this report is the calibration of the entire Tier 1 set. |
| **Tap-through** | The largest adjustment class with its top items and which department caused them. **Refuses:** the closing journal listing. |
| **Data needed** | A **frozen snapshot of the profit as reported at period end** and the final posted profit, with every post-close entry classified. ⚠ **A stored period-end flash snapshot** — the ERP overwrites, so this must be captured deliberately on the last day; without it the report is impossible. ⚠ **Adjustment reason codes on post-close journals.** ⚠ **A recorded "final" date per period** so "stopped moving" can be measured. |
| **Novelty** | **NOVEL** — a report about the *reliability of the other reports*. It is the fastest way to earn or destroy executive trust in a mobile suite, and virtually no ERP ships it. |

---

### 15. Did Prices Hold?

| field | content |
|---|---|
| **Screen name** | `Did Prices Hold?` |
| **Full name** | Did the Price Increase Reach the Invoice? — list rise against realised rise |
| **Archetype** | 4 Variance Bridge |
| **The question it answers** | "I raised prices 8% in June. How much of that actually reached my invoices — and who gave it back?" |
| **Key figures** | (1) The headline pair — "List +8.0% · realised +3.1%"; (2) the bridge from list rise to realised rise: discount given back, contract prices not repriced, mix shifted to cheaper lines, stale price files still on the old list, promotions; (3) shillings of margin the shortfall is worth per month; (4) the branch and channel where least of the rise survived |
| **The comparison** | Intended (list) vs realised, by branch and channel, measured on the same basket of items before and after the effective date. |
| **Exception lead** | The branch or channel with the lowest realisation, named, with its cause bar. Usually one branch is still invoicing the old file, and it is invisible any other way. |
| **Consolidation level** | Group / company / branch / channel. Rolls up on shillings; realisation % re-derived, never averaged. |
| **Cadence** | On-alert — 14, 30 and 60 days after any price change; otherwise not opened |
| **Decision it triggers** | **GM**: enforce the new price file where it did not land, and withdraw discount authority that is silently reversing a board decision. **Owner + CFO**: decide whether the next rise needs to be larger to net the same, and whether contract customers must be repriced. |
| **Tap-through** | The lowest-realisation branch's item-level realisation by product family. **Refuses:** customer-by-customer negotiation history. |
| **Data needed** | List price history with effective dates, realised price per line before and after, discount per line, contract prices and their review dates, product mix. ⚠ **A price-change event as a first-class object** — announcement date, effective date, intended %, scope — which no ERP models; without it there is nothing to measure realisation against. ⚠ **Contract-price expiry/review dates.** ⚠ **A like-for-like basket definition** to strip mix out honestly. |
| **Novelty** | **NOVEL** — owners raise prices on faith and never learn what landed. This report is the difference between a pricing decision and a pricing *system*. |

---

### 16. Channel League

| field | content |
|---|---|
| **Screen name** | `Channel League` |
| **Full name** | Channel League — margin per shilling sold across counter, route and trade |
| **Archetype** | 5 League Table |
| **The question it answers** | "Which way of selling actually makes me money — the shop counter, the vans, or the credit trade?" |
| **Key figures** | (1) The spread — "counter 29%, route 21%, credit trade 14%, group 22%"; (2) per channel: margin rate %, (3) margin shillings, (4) margin **after channel cost** (van running, counter staff, credit cost), (5) rank change, (6) share of group margin |
| **The comparison** | Group rate as the reference line, plus each channel against its own plan, plus rank movement. Crucially, margin **before and after channel cost** side by side — the ranking often inverts, and that inversion is the entire finding. |
| **Exception lead** | Any channel whose after-cost margin is negative or below the cost of the capital it consumes appears first, regardless of rank. |
| **Consolidation level** | Group and company; channel × branch on tap. Rolls up. |
| **Cadence** | Month-end |
| **Decision it triggers** | **Owner**: where to put the next shilling of working capital and the next hire — expand routes, or expand the counter. **GM**: whether a channel's cost base is justified by the margin it generates. |
| **Tap-through** | The worst channel's cost breakdown (report 17 / 20 scoped). **Refuses:** customer lists inside a channel. |
| **Data needed** | Channel tag on every sale; margin by channel; channel-specific direct costs (vehicle, fuel, crew, counter staff, POS charges, credit cost). ⚠ **A channel dimension on transactions** — many groups tag by branch only, and route sales get lost inside a branch. ⚠ **Channel cost pools and an agreed allocation rule.** ⚠ **Cost of credit per channel** (days outstanding × cost of money). |
| **Novelty** | CLASSIC in idea, rarely built because the channel dimension is missing at source. |

---

### 17. Cost to Serve

| field | content |
|---|---|
| **Screen name** | `Cost to Serve` |
| **Full name** | What Serving Each Customer Costs — margin after delivery, credit and returns |
| **Archetype** | 5 League Table |
| **The question it answers** | "Which of my big customers are actually worth having once I count what it costs to serve them?" |
| **Key figures** | (1) The inversion headline — "3 of the top 10 customers by sales are in the bottom 10 by profit"; (2) per customer: gross margin, (3) cost to serve (deliveries, small-order handling, returns, credit days × cost of money), (4) **net margin after serving**, (5) rank change vs the sales ranking, (6) net margin % |
| **The comparison** | Each customer's net-after-serving rate against the group's, and — the killer comparison — their **rank by sales vs their rank by net margin**. |
| **Exception lead** | Customers with negative net margin, largest first, with the dominant cost driver named ("41 deliveries averaging TZS 180k · 96 days to pay"). |
| **Consolidation level** | Group (customers trade across branches and must be consolidated under one owner). Company view on tap. Related entities grouped. |
| **Cadence** | Month-end / quarterly; on-alert when a customer crosses into negative |
| **Decision it triggers** | **Owner + GM**: minimum order value, delivery charge, changed credit terms, or a deliberate exit — negotiated with facts instead of feelings. **CFO**: reprice the customers who are subsidised by everyone else. |
| **Tap-through** | The customer's cost-to-serve breakdown and 12-month net margin trend. **Refuses:** their invoice history — that conversation belongs in AR, not here. |
| **Data needed** | Margin by customer; delivery events and cost per drop; order count and average order size; returns by customer; days-to-pay by customer and a cost-of-money rate; related-party grouping. ⚠ **Cost per delivery / per drop** — almost never captured; needs vehicle cost pools and drop counts. ⚠ **Cost-of-money rate as a governed parameter.** ⚠ **Related-entity grouping** (one owner trading as four names hides the exposure). ⚠ Order-handling cost standard for small orders. |
| **Novelty** | **NOVEL** — the biggest-customer illusion is the most expensive blind spot in a trading group, and cost-to-serve is the only thing that dispels it. |

---

### 18. Break-even Day

| field | content |
|---|---|
| **Screen name** | `Break-even Day` |
| **Full name** | The Day the Month Turns Profitable — fixed costs covered, and what each day after earns |
| **Archetype** | 9 Forecast & Runway |
| **The question it answers** | "On what day of the month do I stop working for the landlord and start working for myself?" |
| **Key figures** | (1) The date — "Break-even falls on 19 August (was 16 in July)"; (2) fixed cost still to cover, TZS; (3) margin earned per trading day at current rate; (4) margin each remaining day adds to profit; (5) the same date for the previous 6 months as a small strip |
| **The comparison** | This month's break-even date against last month's and against the same month last year. A date sliding later month after month is the most legible possible statement of deteriorating operating leverage — an owner understands it instantly where a "contribution margin ratio" means nothing. |
| **Exception lead** | Any company or branch whose break-even date has moved past the 25th, or has no break-even date at all this month, is named at the top. |
| **Consolidation level** | Company (fixed costs are a company concept); group as a weighted view; branch where branch fixed costs are identifiable. Does not naively roll up — the group date is derived from group fixed cost, and the screen says so. |
| **Cadence** | Month-end; glanced mid-month during a bad month |
| **Decision it triggers** | **Owner**: whether the fixed-cost base is affordable at the current trading level — the rent, the head office, the third shift. **CFO**: what volume the group must hold to stay above water, expressed as a date everyone in the business can repeat. |
| **Tap-through** | The fixed-cost stack — the five largest fixed costs and how each moved the date. **Refuses:** expense line detail. |
| **Data needed** | Fixed vs variable classification of all costs; contribution margin rate; trading-day calendar; monthly fixed cost run rate. ⚠ **Fixed/variable/step-fixed classification on the chart of accounts** — the foundational build item for this and reports 3 and 22. ⚠ **Step-fixed costs modelled** (a second shift, an extra van) or the date is wrong at high volume. ⚠ Trading-day calendar with real seasonality. |
| **Novelty** | **NOVEL** — break-even is textbook, but as a *date on the calendar*, tracked month over month, it becomes an executive instrument that a factory manager and a shopkeeper both understand. |

---

### 19. Margin Given Back

| field | content |
|---|---|
| **Screen name** | `Margin Given Back` |
| **Full name** | What the Credit Notes Took Back — margin reversed after the sale |
| **Archetype** | 6 Exception Register |
| **The question it answers** | "How much of the profit I booked was handed back afterwards, and who keeps handing it back?" |
| **Key figures** | (1) "TZS 34M reversed · 4.1% of margin booked"; (2) new vs repeat vs cleared; (3) per row: branch or customer, value reversed, reason (goods returned, price correction, quality claim, wrong billing, expiry), days after the original sale; (4) reversals raised more than 30 days after the sale — the suspicious class; (5) the branch with the highest reversal rate |
| **The comparison** | Reversal rate this month vs the 6-month average, and each branch against the group rate. Repeat customers and repeat reasons are marked. |
| **Exception lead** | Late reversals (>30 days after sale) and reversals concentrated on one customer or one issuer, because those are a control question rather than a quality question. |
| **Consolidation level** | Group / company / branch. Rolls up. |
| **Cadence** | Month-end; on-alert for large single credits |
| **Decision it triggers** | **GM**: fix the underlying cause — a supplier quality problem, a billing process, or a branch practice. **CFO**: if reversals are concentrated late and by one issuer, escalate as a control investigation. |
| **Tap-through** | The reason breakdown for the worst branch, with the top customers involved. **Refuses:** the credit-note documents. |
| **Data needed** | Credit notes and returns with value, margin effect, original sale link, and reason; issuer; elapsed days. ⚠ **Mandatory reason codes on credit notes** — usually free text, which makes the report unbuildable. ⚠ **A link from credit note back to the original invoice line** so the margin reversal is measurable, not just the revenue. ⚠ Approval trail on credit notes above a threshold. |
| **Novelty** | **NOVEL** in this form — most ERPs report returns in units or revenue. Reporting them as *margin reversal with ageing and issuer* turns a quality statistic into a fraud-and-control screen. |

---

## TIER 3 — specialist, on demand

---

### 20. Route Profit

| field | content |
|---|---|
| **Screen name** | `Route Profit` |
| **Full name** | What Each Route Earns After Running It — margin less fuel, crew, returns and credit |
| **Archetype** | 5 League Table |
| **The question it answers** | "Does each van actually pay for itself, once I count the fuel, the crew and the stock that comes back?" |
| **Key figures** | (1) The spread — "best route TZS 310k/day, worst −TZS 40k/day"; (2) per route: margin sold, (3) running cost (fuel, crew, vehicle), (4) **net per trading day**, (5) value of stock returned as a share of stock loaded, (6) rank change |
| **The comparison** | Net per day against the route's own plan and against the fleet median; loaded-vs-returned ratio against the fleet. |
| **Exception lead** | Routes that are net negative, and routes whose return ratio exceeds 20% (a van carrying stock around town all day is working capital on wheels). |
| **Consolidation level** | Company and branch (routes belong to a hub). Rolls into the Channel League. |
| **Cadence** | Weekly during a route push; monthly otherwise |
| **Decision it triggers** | **GM / Branch Manager**: re-cut the route, change the load plan, change the crew, or withdraw the van. **Owner**: whether route selling is a growth channel or a habit. |
| **Tap-through** | The worst route's daily net over the last 30 days, with load-vs-return. **Refuses:** per-customer detail on the route — that is a sales-domain screen. |
| **Data needed** | Sales and margin by route and day; stock loaded and returned per trip, valued; fuel and vehicle cost per route; crew cost; credit sales made on route and their collection. ⚠ **Fuel and vehicle cost attributed to a route/trip**, not to a fleet cost centre. ⚠ **Load-out and return-in valued at cost per trip.** ⚠ Route as a dimension on the sale. ⚠ Trading days per route (a van off the road must not drag its average). |
| **Novelty** | **NOVEL** — van sales are usually measured on revenue. Net-per-day-on-the-road with the return ratio is the number that decides whether a van stays. |

---

### 21. Standard vs Actual

| field | content |
|---|---|
| **Screen name** | `Standard vs Actual` |
| **Full name** | Does the Standard Cost Still Hold? — standard against actual factory cost, by product |
| **Archetype** | 13 Reconciliation & Assurance |
| **The question it answers** | "Am I pricing my manufactured goods off a cost that is two years out of date?" |
| **Key figures** | (1) Verdict — "Out by TZS 22M this month · 9 of 34 products stale"; (2) the difference by reason: material price, material usage/yield, labour rate, labour efficiency, overhead absorption; (3) per product family: standard cost, actual cost, gap %; (4) age of the oldest standard still in use; (5) products whose selling price is now below actual cost |
| **The comparison** | Standard vs actual per unit, and the age of each standard against the group's revision policy (e.g. quarterly). |
| **Exception lead** | Products where actual cost has passed the selling price, and standards older than the revision policy — both are pricing hazards, not accounting curiosities. |
| **Consolidation level** | Company and factory; product family within. Feeds the group margin bridge as the "cost" bar for manufactured lines. |
| **Cadence** | Month-end; mandatory before any factory price-list revision |
| **Decision it triggers** | **CFO + Factory Manager**: revise standards, or fix the process that is missing the standard. **Owner**: reprice manufactured lines whose real cost has moved. |
| **Tap-through** | The worst product's cost build-up, standard against actual, by component. **Refuses:** work-order detail. |
| **Data needed** | Standard cost per product with its effective date; actual material, labour and overhead consumed per output unit; production volumes; overhead absorption rate. ⚠ **Standard costs with revision dates and a revision policy** as data. ⚠ **Actual labour hours per work order** (frequently not captured — the usual gap). ⚠ **Yield and scrap by product**, valued. ⚠ Overhead absorption basis stated. |
| **Novelty** | CLASSIC in manufacturing accounting; **rare in a trading-group ERP**, and the reason a factory can look profitable for two years while quietly selling below cost. |

---

### 22. If Sales Slip

| field | content |
|---|---|
| **Screen name** | `If Sales Slip` |
| **Full name** | How Far Sales Can Fall Before We Lose Money — by company and branch |
| **Archetype** | 10 Concentration & Exposure |
| **The question it answers** | "If trade drops the way it did during the last slow season, at what point do I start losing money?" |
| **Key figures** | (1) The exposure headline — "Sales can fall 14% before the group loses money (was 22% a year ago)"; (2) the same figure per company; (3) profit remaining at −10%, −20%, −30% sales; (4) fixed cost per month, TZS; (5) the branches that go negative first, named, with their threshold |
| **The comparison** | The safety margin now vs a year ago (is fragility deepening?) and against a board tolerance line — e.g. "the group should withstand a 25% fall". |
| **Exception lead** | The branches that turn negative first, in order — an owner needs the *sequence of failure*, not the average. |
| **Consolidation level** | Group, company and branch. Does not average — each level has its own fixed base. |
| **Cadence** | Quarterly; on demand before taking on fixed commitments (a lease, a shift, a fleet) |
| **Decision it triggers** | **Owner + CFO**: whether to sign a new fixed commitment; whether to convert fixed costs to variable; which branches to plan to hibernate rather than close in a downturn. |
| **Tap-through** | The fixed-cost stack driving the exposure, largest first. **Refuses:** a general scenario builder — one stress test, well chosen, beats a toy. |
| **Data needed** | Contribution margin rate; fixed vs variable classification; step-fixed thresholds; historical worst observed sales fall. ⚠ **Fixed/variable classification** (shared with 18). ⚠ **Contractual notice periods on fixed commitments** (leases, contracts) — the difference between a cost you can shed and one you cannot; almost never captured. ⚠ **Historical downturn depth** from the group's own history to make the stress realistic rather than arbitrary. |
| **Novelty** | **NOVEL** — operating leverage stated as "how far can we fall", with a named sequence of which branch dies first. Owners plan around this instinctively and have never had it measured. |

---

### 23. Do Discounts Pay?

| field | content |
|---|---|
| **Screen name** | `Do Discounts Pay?` |
| **Full name** | Do Discounts Buy Volume? — growth of discounted accounts against undiscounted |
| **Archetype** | 11 Cohort & Retention |
| **The question it answers** | "I keep being told a discount is needed to win the volume. Did the volume ever come?" |
| **Key figures** | (1) The verdict — "Accounts given 10%+ grew volume 4% and margin −11%; comparable undiscounted accounts grew volume 6%"; (2) 4–5 discount-band cohorts (0%, 1–5%, 5–10%, 10%+) each as a small line of volume index at months 3, 6, 12 from the discount start; (3) margin per cohort at month 12; (4) retention rate per cohort; (5) total shillings given away by the cohorts that did not grow |
| **The comparison** | Cohort against cohort — deeper discounts against shallower and none. No external target needed; the comparison is internal and therefore unarguable. |
| **Exception lead** | The band that gave away the most and grew the least, in shillings, stated in plain words above the chart. |
| **Consolidation level** | Group (customer behaviour crosses branches). Company on tap. |
| **Cadence** | Quarterly; before any annual pricing or trade-terms review |
| **Decision it triggers** | **Owner + CFO**: withdraw or restructure the discount ladder; move from unconditional discounts to earned rebates; stop rewarding sales staff for volume bought with margin. |
| **Tap-through** | The accounts inside the worst-performing band. **Refuses:** individual salesperson attribution — that makes it political before it is understood. |
| **Data needed** | Discount level per customer over time; volume and margin per customer by month; first-discount date to anchor the cohort; comparable-account matching (size, segment, branch). ⚠ **A customer segment/size classification** so cohorts are compared like for like. ⚠ **Discount start dates as events** (when a customer moved to a deeper band). ⚠ **Twelve months of clean per-customer history** — cohorts younger than 3 months must be shown but marked uninterpretable. |
| **Novelty** | **NOVEL** — the single most expensive untested belief in a trading business ("we had to discount to hold the volume"), finally tested. |

---

### 24. Cost-to-Price Lag

| field | content |
|---|---|
| **Screen name** | `Cost-to-Price Lag` |
| **Full name** | How Long From Cost Rise to Price Rise — the days, and the margin lost in them |
| **Archetype** | 12 Cycle-Time & Flow |
| **The question it answers** | "When my supplier raises the price, how many days do I keep selling at the old price — and what does that delay cost me?" |
| **Key figures** | (1) Total elapsed days, median and 90th percentile — "Median 11 days, worst 44"; (2) the stages as one segmented bar: supplier notifies → new cost lands in the system → price reviewed → new price file published → branches actually invoice at it; (3) the stage exceeding standard, with the count of items stuck in it now; (4) margin lost in the lag this month, TZS; (5) items currently bought at a higher cost but still selling at the old price |
| **The comparison** | Against the agreed service standard (e.g. 5 working days) and against the same measure a quarter ago. The 90th percentile shown alongside the median — the catastrophic tail is where the money is. |
| **Exception lead** | The items **currently** in the lag with the largest daily bleed, not the historical average. This is the only cycle-time report where the in-flight items matter more than completed ones. |
| **Consolidation level** | Company; branch for the final stage (a branch still invoicing old prices is a named stage failure with a named owner). |
| **Cadence** | Monthly; on-alert during a period of supplier cost increases or a currency move |
| **Decision it triggers** | **GM / CFO**: shorten a specific stage — usually the review stage, where an approval costs more days than it saves shillings. Also a delegation decision: below a threshold, cost rises should reprice automatically. |
| **Tap-through** | The items stuck in the worst stage now, with the daily margin cost and an owner per item. **Refuses:** historical cost-change detail per supplier. |
| **Data needed** | Supplier cost-change events with dates; cost effective date in the system; price review and approval timestamps; price file publication; first invoice at the new price by branch; volume sold during the lag. ⚠ **Supplier cost-change notification as a dated event** — nobody captures the notification, only the eventual invoice, so the first stage is invisible. ⚠ **Price review/approval timestamps.** ⚠ **Price file publication vs branch adoption** as distinct events (they are not the same day, and the gap is where the loss lives). |
| **Novelty** | **NOVEL** — margin in a trading group is lost in the gaps between events, not inside transactions. This report is the only one in the suite that measures a *delay* as a money loss with a named owner. |

---

### 25. If Five Left

| field | content |
|---|---|
| **Screen name** | `If Five Left` |
| **Full name** | If Our Five Biggest Earners Left — share of group margin and the cover behind them |
| **Archetype** | 10 Concentration & Exposure |
| **The question it answers** | "How much of my profit is standing on five customers who could walk?" |
| **Key figures** | (1) The exposure — "Top 5 customers = 38% of group margin (31% a year ago)"; (2) per name: margin share, cumulative share, (3) months of relationship and contractual notice, (4) their current outstanding balance (concentration of *risk*, not just of income), (5) the fixed costs that would remain if they left |
| **The comparison** | The same shares a year ago (dependence deepening or easing) and a board tolerance line ("no customer above 15% of margin"). |
| **Exception lead** | Any single name above tolerance, and any name whose share has grown more than 5 points in a year — the drift is the warning, not the level. |
| **Consolidation level** | Group, with related entities grouped under one beneficial owner. Company on tap. |
| **Cadence** | Quarterly; on demand before a large credit extension |
| **Decision it triggers** | **Owner**: deliberately diversify, take credit insurance, cap exposure, or lock a contract with notice terms before dependence hardens. **CFO**: set the credit ceiling on the concentrated names. |
| **Tap-through** | The named account's 24-month margin history and terms. **Refuses:** their transaction ledger. |
| **Data needed** | Margin (not revenue) by customer, rolling 12 months; related-party grouping; contract notice periods; outstanding balance. ⚠ **Share of margin, not revenue** — the usual build measures revenue and understates true dependence on high-margin accounts. ⚠ **Related-entity grouping** — without it one owner trading as four names hides the concentration entirely. ⚠ **Contractual notice periods** captured as data. ⚠ Board tolerance threshold. |
| **Novelty** | CLASSIC as an idea; the margin basis, the related-party grouping and the year-on-year drift are the three things that are usually missing and that make it a warning rather than a fact. |

---

## Cross-cutting build items this domain creates

These fall out of the specs above and are the price of the suite. Ranked by how many reports they unlock.

| # | Build item | Unlocks |
|---|---|---|
| 1 | **Margin snapshot at the moment of sale** (price, cost basis, margin, frozen per line) | 1, 12, 14 — and it is unrecoverable retrospectively, so it must be built before anything else |
| 2 | **Margin plan decomposed into price × volume × mix, by branch and product family** | 2, 3, 9, 15 |
| 3 | **Fixed / variable / step-fixed classification on the chart of accounts** | 3, 10, 18, 22 |
| 4 | **Period-end flash snapshot, frozen and retained** | 14 (and the credibility of 1–9) |
| 5 | **Landed cost components allocated to receipt lines** | 5, 12, 21 |
| 6 | **Discount authority limits and floor prices held as data** | 6, 8, 15 |
| 7 | **Price-change events as first-class objects** (announced, effective, intended %, scope) | 15, 24 |
| 8 | **Non-recurring / non-cash flags on P&L entries** | 7, 13 |
| 9 | **Channel and route dimensions on every sale, plus route running costs** | 16, 17, 20 |
| 10 | **Related-party grouping and customer segment class** | 17, 23, 25 |
| 11 | **Weekday/seasonal baselines and a real trading calendar** | 1, 9, 11, 18 |
| 12 | **Mandatory reason codes on credit notes, linked to the original invoice line** | 19 |

===== DOMAIN: cash — CASH — position, movement and forecast =====
# CASH — The Executive Report Suite
**OrbixERP Executive Mobile · Domain: Cash · v1 · 2026-08-18**
25 reports. Designed from first principles against the archetype doctrine, not against what the ERP currently serves.

---

## 0. Domain frame

**What the owner is actually asking, in his own words:** *"How much money do I have, how much of it can I actually touch, what is already promised, when do I run out, why is there profit but no cash, and is anyone stealing it on the way to the bank?"* Six questions. Every report below serves exactly one of them.

**Matrix discipline.** The Cash column ticks Flash ●, Scorecard ○, Position & Movement ●, Variance Bridge ●, League ○, Exception ●, Trend ●, Forecast ●, Concentration ○, Cycle-Time ●, Reconciliation ●, Decision Docket ●. **Ageing and Cohort are blank in this column and I have deliberately built neither** — there is no cohort of a bank balance, and cash ageing questions (unbanked takings, in-transit, unretired floats) are correctly Exception Registers with an age column, not pyramids. Where I felt the pull toward an Ageing Pyramid (reports 12 and 17) I resisted it and kept the ageing as a *field*, not a *form*.

**Naming note on the shared "Cash" prefix.** Six screen names lead with "Cash" (Cash Position, Cash Gap, Cash Match, Cash Cycle, Cash Trend, Cash League). This passes R9 because the second word carries the whole difference and each is a different archetype. Operationally: **the tile keeps the prefix on the home screen** (where it sits beside Margin and Stock tiles) **and drops it inside the Cash section** (Position, Gap, Match, Cycle, Trend, League) where the section header already supplies it.

**One sanctioned template extension.** Report 6 uses `Can We Pay <obligation>?` — a Forecast & Runway name that is not `How Long <thing> Lasts` or `What Runs Out First`. Justification: the two canonical templates answer *"when?"*. A payroll date is fixed by law and calendar; the only executive question is *"yes or no, and how short?"* The extension is registered here so the family stays authored rather than accidental. It applies only to date-certain obligations (payroll, statutory tax, loan instalments).

---

## Index

| # | Screen name | Full name | Archetype | Tier | Novelty |
|---|---|---|---|:--:|:--:|
| 1 | Today's Cash | Today's Cash — money in, money out, against the same weekday | Flash | 1 | CLASSIC |
| 2 | Cash Position | Where the Cash Is — bank, till, wallet, in transit, and how it moved | Position & Movement | 1 | CLASSIC |
| 3 | Usable Cash | Cash We Can Actually Use — free, committed and restricted | Position & Movement | 1 | **NOVEL** |
| 4 | Committed Cash | What We Have Already Promised to Pay — by due week | Position & Movement | 1 | **NOVEL** |
| 5 | Cash to 30 Days | How Long the Cash Lasts — the day it crosses the floor | Forecast & Runway | 1 | CLASSIC |
| 6 | Salary Cover | Can We Pay the Salaries? — cover at payroll date and the gap | Forecast & Runway | 1 | **NOVEL** |
| 7 | Cash Gap | Why Cash Is Short — planned closing against actual, by cause | Variance Bridge | 1 | CLASSIC |
| 8 | Profit vs Cash | Why the Profit Is Not in the Bank — profit to cash by cause | Variance Bridge | 1 | **NOVEL** |
| 9 | Cash Against Plan | Cash Against Plan — the treasury measures against board plan | Scorecard | 1 | CLASSIC |
| 10 | Payments Waiting | Payments Waiting on You — value, due date and cost of delay | Decision Docket | 1 | CLASSIC/**NOVEL** |
| 11 | Cash Match | Does the Cash Match? — bank, till and ledger against book | Reconciliation | 1 | CLASSIC |
| 12 | Unbanked Cash | Cash That Never Reached the Bank — by branch, age and value | Exception Register | 1 | **NOVEL** |
| 13 | Cash Cycle | How Long Our Cash Is Tied Up — stock, debtors and suppliers | Cycle-Time & Flow | 2 | CLASSIC |
| 14 | Till to Bank | How Long From Till to Bank — takings to cleared funds | Cycle-Time & Flow | 2 | **NOVEL** |
| 15 | Borrowing Room | How Much Borrowing Room Is Left — facility, drawn, covenant | Position & Movement | 2 | CLASSIC |
| 16 | Odd Payments | Payments That Broke the Rule — off-policy and duplicate | Exception Register | 2 | **NOVEL** |
| 17 | Petty Cash | Floats and Advances Not Retired — by holder, age and value | Exception Register | 2 | CLASSIC |
| 18 | Till Match | Does Every Till Match? — over, short and repeat offenders | Reconciliation | 2 | **NOVEL** |
| 19 | Wallet Match | Does the Wallet Match? — mobile money against the ledger | Reconciliation | 2 | **NOVEL** |
| 20 | Cash Trend | Which Way the Cash Is Going — 24 months with the normal band | Trend & Trajectory | 2 | CLASSIC |
| 21 | Cash League | Branch Cash League — cash generated per branch, against plan | League Table | 2 | **NOVEL** |
| 22 | Bank Exposure | How Much Rides on One Bank — deposits and counterparty risk | Concentration | 2 | **NOVEL** |
| 23 | Big Receipts | How Much Rides on the Big Receipts — the plan's weak points | Concentration | 3 | **NOVEL** |
| 24 | Foreign Cash | Where the Foreign Cash Is — balances and import cover | Position & Movement | 3 | **NOVEL** |
| 25 | What Waiting Cost Us | What Waiting Cost Us — the price of last month's slow approvals | Decision Docket | 3 | **NOVEL** |

**12 NOVEL** of 25. The five that most earn "I have never been able to see that": **Profit vs Cash (8)**, **Usable Cash (3)**, **Unbanked Cash (12)**, **Till to Bank (14)**, **Odd Payments (16)** — with **Big Receipts (23)** and **Wallet Match (19)** close behind.

---

# TIER 1 — opened weekly or more

---

## 1. Today's Cash

| field | content |
|---|---|
| **Screen name** | `Today's Cash` (12 chars) |
| **Full name** | Today's Cash — money in, money out, against the same weekday |
| **Archetype** | 1 · Flash |
| **The question it answers** | "Is today's money coming in the way a normal day's money comes in?" |
| **Key figures** | **Cash in today** (all channels, TZS 41.2M) · **Cash out today** (TZS 12.8M) · **Net today** (+28.4M) · **Group cash right now** (TZS 612M) · **Sitting in tills, not yet banked** (TZS 18.6M) |
| **The comparison** | Same weekday last week **and** the 4-week same-weekday median, as delta chips. Never "vs yesterday" — a Tuesday against a Sunday is noise dressed as news. Public holidays and market days flagged out of the baseline. |
| **Exception lead** | Any branch below 65% of its normal same-weekday cash-in by 14:00 rises to the top of the strip, before the group total. Second: any till over its cash-holding limit. |
| **Consolidation level** | Group headline; one-tap branch strip. Rolls up branch → company → group. Company tags shown because payments are entity-bound. |
| **Cadence** | Glance daily — morning (yesterday closed) and 15:00 (today in flight). |
| **The decision it triggers** | **Owner / GM:** phone a branch before close; order an extra banking run; send the supervisor. "Kariakoo is at 40% of a normal Tuesday at 11am — what is wrong?" |
| **Tap-through** | The branch strip: the same five numbers per branch. **Refuses** individual receipts, payment lists, or any invoice number. |
| **Alert condition** | Push at 14:00 when group cash-in < 65% of the 4-week same-weekday median; or immediately when any till holds > its limit + TZS 2M. |
| **Data needed** | Receipts by channel and timestamp; payments released today; bank balances intraday; till declared cash; banking slips. ⚠ **Intraday/same-day bank balance feed** (most ERPs hold only a book balance). ⚠ **Per-till cash-holding limit.** ⚠ **Branch trading calendar** (opening days, holidays, market days) so the baseline is honest. |
| **Novelty** | CLASSIC |

---

## 2. Cash Position

| field | content |
|---|---|
| **Screen name** | `Cash Position` (13) |
| **Full name** | Where the Cash Is — bank, till, wallet, in transit, and how it moved |
| **Archetype** | 3 · Position & Movement |
| **The question it answers** | "How much money do we have, where exactly is it sitting, and how did we get here from the start of the month?" |
| **Key figures** | **Total cash TZS 612M**, split: bank cleared 430M · cash on hand at tills 62M · mobile money wallets 74M · in transit 46M. Then **opening balance 1 Aug 548M** and **net movement +64M** across 5 movement classes (collections in, supplier payments out, payroll & statutory out, transfers, other). |
| **The comparison** | Opening balance for the month; the same position one month ago; last month-end close. The waterfall *is* the comparison. |
| **Exception lead** | The largest adverse movement class, and anything in transit older than 2 days — money that has left a branch but arrived nowhere is the first thing an owner should see. |
| **Consolidation level** | Group headline. Drills group → company → branch → account. Must roll up; must also show the company split, because a group total that cannot legally be pooled is a misleading number. |
| **Cadence** | Glance daily; the station you return to. |
| **The decision it triggers** | **Owner / CFO:** sweep idle branch and wallet balances to the main account before a payment run; chase an in-transit item; close an account nobody uses. |
| **Tap-through** | Any movement bar → its top contributors as business categories (customer groups, payment classes). **Refuses** bank statement lines and journal detail. |
| **Alert condition** | No push — this is a station, not an alarm. Single exception: cash in transit > 3 days or > TZS 50M. |
| **Data needed** | Per-account daily balances; till balances; wallet balances per merchant account; inter-account and inter-branch transfers with dispatch **and** receipt timestamps. ⚠ **Cleared vs book balance distinction.** ⚠ **A mapping from GL accounts to owner-language movement classes** — the report dies the moment a bar is labelled "Suspense 9200". |
| **Novelty** | CLASSIC |

---

## 3. Usable Cash

| field | content |
|---|---|
| **Screen name** | `Usable Cash` (11) |
| **Full name** | Cash We Can Actually Use — free, committed and restricted |
| **Archetype** | 3 · Position & Movement |
| **The question it answers** | "Of all the money the system says we have, how much can I actually spend today?" |
| **Key figures** | **Free and usable today TZS 284M** (headline) · Total cash 612M · Committed within 7 days 196M · Restricted 86M (bank minimum balances, LC and guarantee margins, security and rent deposits) · Not yet cleared / in transit 46M · **Usable share 46%** |
| **The comparison** | The usable share this month vs last month vs the 6-month average; and against a **board-set free-cash floor** (e.g. TZS 250M) drawn as a line. A falling usable share while total cash is flat is the whole story. |
| **Exception lead** | Free cash below the floor. Then: restricted cash *rising* — money being quietly trapped is invisible in every other report. |
| **Consolidation level** | Group, with an explicit "usable but stranded" call-out: cash sitting in a branch or a company that cannot be moved to where the payment must be made. Company-level is the operative level; group total is context only. |
| **Cadence** | Weekly, and always before any large payment decision. |
| **The decision it triggers** | **Owner / CFO:** approve, stage or withhold a large payment; ask the bank to release an LC margin; renegotiate a minimum balance; sweep a stranded balance. |
| **Tap-through** | The restricted bucket → what each restriction is, who imposed it, when it releases. **Refuses** the forecast — tomorrow belongs to report 5. |
| **Alert condition** | Push when free usable cash falls below the board floor, or when the restricted share exceeds 20% of total cash. |
| **Data needed** | Bank balances; committed outflows (report 4). ⚠ **Bank minimum-balance requirements per account.** ⚠ **LC / bank-guarantee margins with release dates.** ⚠ **Security, rent and utility deposits register.** ⚠ **Clearing lag per instrument.** ⚠ **Inter-company / inter-branch transferability rules and cost.** |
| **Novelty** | **NOVEL** — every ERP shows the balance; almost none distinguishes the balance from the money you may actually spend. This is the report that stops an owner committing against cash he does not have. |

---

## 4. Committed Cash

| field | content |
|---|---|
| **Screen name** | `Committed Cash` (14) |
| **Full name** | What We Have Already Promised to Pay — by due week |
| **Archetype** | 3 · Position & Movement |
| **The question it answers** | "How much of the money in the bank is already spoken for, and what can I still stop?" |
| **Key figures** | **Committed next 30 days TZS 418M** · by class: supplier invoices due 172M · approved POs not yet invoiced 96M · payroll + statutory 88M · loan instalments 34M · rent, utilities, standing orders 28M · **Discretionary (can move without breaking a promise) 121M** · **Cover: commitments ÷ projected receipts = 0.86×** |
| **The comparison** | The same commitment level a month ago (are we over-committing?); commitments against projected receipts week by week; the discretionary share as your room to manoeuvre. |
| **Exception lead** | The **week** where commitments exceed projected receipts — surfaced before the 30-day total. Then the largest non-discretionary item in that week. |
| **Consolidation level** | Company is the operative level (payments are legal-entity bound); group roll-up for the owner; branch informational only. |
| **Cadence** | Weekly, and before every payment run. |
| **The decision it triggers** | **CFO / Owner:** sequence the payment run; defer discretionary items out of the tight week; call the bank a week early instead of on the day. |
| **Tap-through** | The worst week → its items by value, each tagged *fixed / movable / negotiable*. **Refuses** the full AP ledger — this is a commitment view, not a payables screen. |
| **Alert condition** | Push when any week inside the next 30 days has cover < 1.0×, or when total commitments rise more than 25% week-on-week. |
| **Data needed** | Unpaid supplier bills with due dates; approved POs. ⚠ **Expected invoice date on an approved PO** (commitment ≠ invoice). ⚠ **Payroll calendar and amount per company.** ⚠ **Statutory due-date calendar** (VAT 20th, PAYE, NSSF, WCF, SDL) with computed amounts. ⚠ **Loan amortisation schedules.** ⚠ **Standing orders and direct debits.** ⚠ **A fixed/movable/negotiable flag on payment classes** — without it the report says what you owe but not what you can do. |
| **Novelty** | **NOVEL** — ERPs hold payables; very few hold *total commitment* including statutory, loans, standing orders and approved-but-uninvoiced POs, which is the number that actually constrains an owner. |

---

## 5. Cash to 30 Days

| field | content |
|---|---|
| **Screen name** | `Cash to 30 Days` (15) |
| **Full name** | How Long the Cash Lasts — the day it crosses the floor |
| **Archetype** | 9 · Forecast & Runway |
| **The question it answers** | "On what day do we run out of money?" |
| **Key figures** | **"Below the TZS 100M floor on 4 September — 12 working days"** (the date is the headline, never the projection) · Day-30 balance: likely 42M, high 168M, low −60M · **82% of forecast receipts are from confirmed due dates** · The single receipt the plan most depends on · **Last 3 forecasts ran 6% optimistic** |
| **The comparison** | Against last week's forecast for the same date (forecast drift is itself the signal); against the treasury plan; against the floor line. |
| **Exception lead** | The crossing date. If there is no crossing, the tightest day and its cover ratio — never open with a comfortable closing balance. |
| **Consolidation level** | Per company first (money is not freely movable between legal entities), group as an aggregate clearly labelled "assumes free transfer". Branch is not a cash-forecast unit. |
| **Cadence** | Glance daily in a tight month; weekly otherwise. |
| **The decision it triggers** | **Owner / CFO:** draw the overdraft *before* the day, delay a payment run, launch a collection push, defer a purchase or a capex item. |
| **Tap-through** | The driver list — receipts and payments ranked by **how many days each moves the crossing date**, not by size. **Refuses** the invoice-level ledger. |
| **Alert condition** | Push when the crossing date moves inside 15 working days, or moves 5+ days nearer since the previous run. |
| **Data needed** | Receivables with due dates; committed outflows (report 4); payroll and statutory calendar; seasonality. ⚠ **Promise-to-pay dates and who gave them.** ⚠ **Per-customer historical payment behaviour** (days late distribution, not an average). ⚠ **Capex and project payment plan.** ⚠ **Retained prior forecast versions** — without them the report cannot report its own accuracy, and a forecast that never scores itself is disbelieved after its first miss. |
| **Novelty** | CLASSIC — but the forecast-accuracy self-report is **NOVEL** and is what makes it survivable. |

---

## 6. Salary Cover

| field | content |
|---|---|
| **Screen name** | `Salary Cover` (12) |
| **Full name** | Can We Pay the Salaries? — cover at payroll date and the gap |
| **Archetype** | 9 · Forecast & Runway *(template extension `Can We Pay <obligation>?`, registered in §0)* |
| **The question it answers** | "On the 28th, will there be enough money for salaries, PAYE, NSSF and the big suppliers — and if not, how short am I?" |
| **Key figures** | **Payroll + statutory due 28 Aug: TZS 214M** · Projected free cash on that date: 187M · **Shortfall −27M** · Cover ratio 0.87× (policy 1.15×) · First day cover falls below 1.0: 24 Aug · **The single action that closes the gap** ("collect Tembo Distributors 41M") |
| **The comparison** | Cover ratio for the same obligation in each of the last 3 months; against the policy cover of 1.15×; against the VAT obligation on the 20th, which competes for the same money. |
| **Exception lead** | The shortfall and its size — the screen opens red or green on a single word, then shows the number. Second exception: the next obligation at risk. |
| **Consolidation level** | **Per company** — payroll is an employer-entity obligation and cannot be paid from a sister company's account. Group roll-up shown only as a memo line. |
| **Cadence** | Weekly from the 10th; daily from the 22nd. |
| **The decision it triggers** | **Owner / CFO:** start the collection push now, arrange the overdraft, stage the supplier run behind payroll, defer discretionary spend. Standing rule the report enforces: **statutory is never the item that slips** — penalties and interest make it the most expensive borrowing available. |
| **Tap-through** | The gap-closers: collections and deferrals ranked by size × likelihood, each with an owner. **Refuses** individual salaries — totals by company only, masked by design. |
| **Alert condition** | Push when cover falls below 1.0× at any point inside 14 days; escalate to daily inside 5 days; push again if it recovers (so the owner learns the alert is honest). |
| **Data needed** | Projected free cash (report 5); overdraft headroom (report 15). ⚠ **Payroll amount and pay date per company, forward-looking** (not just the last run). ⚠ **Statutory obligations, rates and due dates computed forward.** ⚠ **Collection likelihood per receipt.** ⚠ **A payroll-confidentiality masking rule** — an executive app that leaks individual salaries will be switched off. |
| **Novelty** | **NOVEL** — the owner's literal question, and almost never a screen anywhere. |

---

## 7. Cash Gap

| field | content |
|---|---|
| **Screen name** | `Cash Gap` (8) |
| **Full name** | Why Cash Is Short — planned closing against actual, by cause |
| **Archetype** | 4 · Variance Bridge |
| **The question it answers** | "We planned to close the month with 400M and we have 260M — where did the 140M go?" |
| **Key figures** | **Gap −TZS 140M** (headline is the gap, never the balance) · cause bars, largest first: slow collections −78M · stock build −46M · early supplier payments −31M · capex −22M · tax timing −14M · trading better than plan +51M · **residual −0M, must stay under 8M** |
| **The comparison** | Built into the form: plan → actual. Each bar also carries "same cause last month" so a repeat offender is visible. |
| **Exception lead** | The largest adverse bar, with a **named owner attached** (collections → credit control; stock build → purchasing). A bridge with no name on any bar changes nothing. |
| **Consolidation level** | Group headline; decomposes to company and branch on tap. |
| **Cadence** | Month-end, every month; mid-month when the forecast drifts hard. |
| **The decision it triggers** | **Owner:** which single lever gets pulled next month, and whether the plan itself was wrong. **CFO** executes. |
| **Tap-through** | A bar → its own League Table (which branches, customers or categories produced that effect). **Refuses** transaction lists — a cause that needs transactions to explain has not been decomposed properly. |
| **Alert condition** | No push at month-end (it is a scheduled read). Mid-month push only when the running gap exceeds 25% of planned closing cash. |
| **Data needed** | ⚠ **An approved, versioned cash plan by month** — the single biggest build item in this domain; most ERPs have a budget for P&L and nothing for cash. Actual movements classified into causes ⚠ (mutually exclusive, or the bars double-count). Stock value movement; capex actuals; tax payments; collections vs terms. |
| **Novelty** | CLASSIC in theory; genuinely rare in practice, because the plan side does not exist. |

---

## 8. Profit vs Cash

| field | content |
|---|---|
| **Screen name** | `Profit vs Cash` (14) |
| **Full name** | Why the Profit Is Not in the Bank — profit to cash by cause |
| **Archetype** | 4 · Variance Bridge |
| **The question it answers** | "The books say we made 300 million this month. Why is there no money in the bank?" |
| **Key figures** | **Profit TZS 302M → cash generated TZS 41M · gap −261M** · bars: debtors up −142M · stock up −96M · creditors paid down −74M · capex −38M · loan repayments −30M · depreciation added back +58M · provisions +21M · **conversion rate 14% (6-month average 61%)** |
| **The comparison** | The same bridge last month; the 6-month average conversion rate; whether each bar is a first offence or a third consecutive month. |
| **Exception lead** | The largest cash-absorbing bar **and its repeat count**. "Debtors have absorbed cash for three months running" is a different decision from "we bought stock for Christmas". |
| **Consolidation level** | Group and per company. Not meaningful at branch level (financing and capex are not branch decisions) — and the screen says so rather than showing a misleading branch split. |
| **Cadence** | Month-end, every month. The single most important monthly read in this domain. |
| **The decision it triggers** | **Owner:** stop trusting the P&L on its own, and issue the specific correction — tighten credit terms, freeze buying, halt capex, stop paying suppliers early. **CFO** executes and reports back on the same bar next month. |
| **Tap-through** | The worst bar → the owning domain's report (debtors → *Who Owes Us, and How Long*; stock → *Stock That Isn't Moving*). **Refuses** the trial balance. |
| **Alert condition** | Push when cash conversion falls below 25% of profit for two consecutive months — the classic profitable-and-insolvent signature. |
| **Data needed** | Profit by period; working-capital balances at both period ends; non-cash charges (depreciation, provisions, FX revaluation); capex; financing flows. ⚠ **A clean operating / investing / financing classification expressed in owner language**, not IAS-7 headings. ⚠ **Month-close status** — this report is a lie if run on a half-posted month, so the trust line must carry the close state. |
| **Novelty** | **NOVEL** — the flagship. Most owners of trading and manufacturing groups have never seen their profit reconciled to their bank balance on one screen, and it is the single most common cause of a "profitable" business dying. |

---

## 9. Cash Against Plan

| field | content |
|---|---|
| **Screen name** | `Cash Against Plan` (17) |
| **Full name** | Cash Against Plan — the treasury measures against board plan |
| **Archetype** | 2 · Scorecard |
| **The question it answers** | "Are we meeting the money standards we set for ourselves?" |
| **Key figures** | Verdict **"3 of 7 on plan"**, then the fixed seven, ordered by size of miss, each actual / target / gap: closing cash 612M vs 700M (−88M) · collections MTD 1.21Bn vs 1.40Bn (−190M) · days of cash cover 21 vs 30 · overdraft utilisation 62% vs ≤50% · cost of money (charges + interest) 0.42% of turnover vs 0.30% · unbanked takings over 24h: 3 vs 0 · unexplained reconciling items TZS 2.4M vs 0 |
| **The comparison** | The printed target on every row — the standard is external to the number, which is what separates this from a position screen. |
| **Exception lead** | Rows sorted by size of miss, not by importance and never alphabetically. The worst miss is row one. |
| **Consolidation level** | Group and per company. Two of the seven rows (unbanked takings, collections) drill to branch. |
| **Cadence** | Weekly, Monday morning; formally at month-end. |
| **The decision it triggers** | **GM / CFO:** the single miss that gets management attention this week, with a named owner and a review date. **Owner:** whether the target or the performance is wrong. |
| **Tap-through** | Any red row → its Variance Bridge or its League Table. **Refuses** ad-hoc row additions — the measure set is fixed for the financial year, or nobody can hold a memory of it. |
| **Alert condition** | Push when 3+ of 7 are red, or when any single row misses for 3 consecutive weeks (chronic beats severe). |
| **Data needed** | Actuals for each measure. ⚠ **A board-approved, versioned, dated treasury plan with targets** — and evidence the targets were agreed by the people measured, or the screen gets argued with instead of acted on. ⚠ **Itemised bank charges and interest** (usually never enters the ERP). ⚠ **Facility limits.** |
| **Novelty** | CLASSIC |

---

## 10. Payments Waiting

| field | content |
|---|---|
| **Screen name** | `Payments Waiting` (16) |
| **Full name** | Payments Waiting on You — value, due date and cost of delay |
| **Archetype** | 14 · Decision Docket |
| **The question it answers** | "What money decisions are waiting for me, and what does each day of waiting cost?" |
| **Key figures** | **9 items · TZS 340M · oldest 6 days · 2 blocking despatch · 1 loses a TZS 3.4M early-settlement discount tomorrow** · your median approval time 1.8 days vs the 1-day standard |
| **The comparison** | Each item against policy — within limit / over limit / above the last price paid / off-contract / **supplier bank account changed in the last 30 days** — and the queue against your own approval SLA. |
| **Exception lead** | Ranked by **cost of delay**, not arrival order and not age. The item that costs TZS 3.4M tomorrow outranks the one that has waited six days and costs nothing. |
| **Consolidation level** | Personal — everything addressed to the logged-in approver, across every company and branch, with the entity tagged on each line. |
| **Cadence** | Glance daily. |
| **The decision it triggers** | The approval itself — **Owner** for over-limit, **CFO** in-limit. Over time it triggers a second, better decision: **raise the delegated limit** for classes the owner approves unchanged every time. The report should surface that pattern itself ("you have approved all 34 of these unchanged — raise the limit?"). |
| **Tap-through** | The item → approve / reject / send back with a reason, showing **the one fact needed to decide**: supplier's current balance, last price paid, remaining budget, bank-account change history. **Refuses** a document browser — if you need to read three attachments on a phone, the request was incomplete. |
| **Alert condition** | Push on arrival for any item > TZS 50M, any item blocking despatch, and any item with a discount or penalty deadline inside 48 hours. Batch everything else into one 08:00 digest. |
| **Data needed** | Approval queue with amounts and requesters; approval limits. ⚠ **Cost of delay per item** — discount terms, penalty clauses, despatch dependency, stop-supply risk. ⚠ **Supplier bank-account change log with timestamps.** ⚠ **Approver SLA history.** ⚠ **Write-back with audit to the approver's name.** |
| **Novelty** | CLASSIC as an inbox; **NOVEL** in ranking by cost of delay and in proposing its own delegation change. |

---

## 11. Cash Match

| field | content |
|---|---|
| **Screen name** | `Cash Match` (10) |
| **Full name** | Does the Cash Match? — bank, till and ledger against book |
| **Archetype** | 13 · Reconciliation & Assurance |
| **The question it answers** | "Does the money the bank says we have agree with the money our books say we have — and what part of the difference can nobody explain?" |
| **Key figures** | Verdict **"Out by TZS 2.4M across 3 accounts"** · bank cleared 430.6M vs ledger 433.0M · reconciling items by reason: timing 1.9M · in transit 0.9M · **unmatched (unexplained) 2.4M, oldest 34 days** · disputed / charges 0.2M · accounts not reconciled in 10+ days: 2 |
| **The comparison** | The two sides side by side; **the age of each difference**; and the unexplained figure a month ago. A chronic TZS 2M hole and today's timing gap must never look the same. |
| **Exception lead** | The **unexplained** portion, oldest first — separated absolutely from timing. That separation is the difference between an accounting artefact and a theft. |
| **Consolidation level** | Per bank account (the only level at which a reconciliation is real), rolled to company and group as a count of clean/unclean accounts. |
| **Cadence** | Weekly; mandatory at month-end. |
| **The decision it triggers** | **CFO:** assign the clearing with a name and a deadline; escalate a bank dispute. **Owner:** stop trusting every downstream cash number until this clears — this report is what makes the other 24 credible. |
| **Tap-through** | The unmatched items with an owner and an age. **Refuses** free browsing of the bank statement. |
| **Alert condition** | Push when unexplained exceeds TZS 1M or ages past 14 days, or when any account has gone 10 days without a reconciliation. |
| **Data needed** | Bank statements per account; ledger cash movements; matching status. ⚠ **Automated bank feed or a disciplined statement import** (a manual monthly upload makes this a month-end-only report). ⚠ **Reason codes on reconciling items** (timing / in transit / unmatched / disputed / error). ⚠ **Reconciliation completion timestamps and who signed.** ⚠ **A tolerance policy that is deliberately tight** — a tolerance wide enough to guarantee green trains everyone to ignore red. |
| **Novelty** | CLASSIC |

---

## 12. Unbanked Cash

| field | content |
|---|---|
| **Screen name** | `Unbanked Cash` (13) |
| **Full name** | Cash That Never Reached the Bank — by branch, age and value |
| **Archetype** | 6 · Exception Register |
| **The question it answers** | "Whose takings have not reached the bank, how much, and how long has it been sitting there?" |
| **Key figures** | **7 breaches · TZS 84M exposed · oldest 6 days · 4 are repeats from last week** · largest: Kariakoo TZS 31M, 4 days · **short-banked (declared minus deposited): TZS 1.9M across 3 branches** · cleared since last week: 5 |
| **The comparison** | The same count and value 7 days ago; the new / repeat / cleared trio at the head; every line against the banking policy (bank daily, hold no more than TZS 5M overnight). **Repeats are the signal** — a breach that survives a week is a control failure, not an incident. |
| **Exception lead** | Ranked by value at risk, then by repeat status. Short-banking (deposited less than declared) always outranks late banking regardless of value — it is a different crime. |
| **Consolidation level** | Branch-level items with a group headline; rolls branch → company → group. |
| **Cadence** | Glance daily. |
| **The decision it triggers** | **Owner / GM:** send someone to the branch today; suspend a cashier; change the banking route or the CIT contract. **Branch Manager:** bank now. Accepting a breach (a genuine bank-holiday backlog) must be possible with a reason, or the register clogs and dies. |
| **Tap-through** | The branch → its banking history: declared, deposited, lag, who signed. **Refuses** individual sales receipts. |
| **Alert condition** | Push immediately when any branch holds cash over its policy limit or over 48 hours, and immediately when deposited is less than declared by more than TZS 200k. |
| **Data needed** | Daily till declarations. ⚠ **Banking slips / deposit records with timestamps.** ⚠ **Bank credits matched back to specific deposits** (without this the report can only guess). ⚠ **Per-branch banking policy and overnight holding limit.** ⚠ **CIT pickup schedule.** ⚠ **Cashier and manager on duty per session.** ⚠ **An accept-with-reason action.** |
| **Novelty** | **NOVEL** — the gap between "sold" and "banked" is where cash businesses bleed, and almost no ERP watches it as a standing register with ageing and repeat detection. |

---

# TIER 2 — opened monthly or on alert

---

## 13. Cash Cycle

| field | content |
|---|---|
| **Screen name** | `Cash Cycle` (10) |
| **Full name** | How Long Our Cash Is Tied Up — stock, debtors and suppliers |
| **Archetype** | 12 · Cycle-Time & Flow |
| **The question it answers** | "How many days does a shilling stay locked in the business before it comes back to me as cash?" |
| **Key figures** | **71 days** (stock 58 + debtors 46 − suppliers 33) vs a 60-day standard · **the stage over standard: stock, +18 days** · **TZS 14.2M tied up per extra day** · 90th-percentile debtor days 118 (median 41) · value currently stuck in the worst stage |
| **The comparison** | Against the agreed standard cycle, against a quarter ago, and stage by stage against each stage's own standard. Median **and** 90th percentile — the average hides the pathological tail that actually loses the money. |
| **Exception lead** | The stage exceeding its standard, with the shilling value it ties up. Not the total. |
| **Consolidation level** | Group and company; product family and customer group on tap; branch only where stock is physically held. |
| **Cadence** | Month-end; quarterly for the trend. |
| **The decision it triggers** | **Owner / GM:** attack **one** stage — cut stock cover on the slow families, tighten credit terms, or negotiate longer supplier terms. The per-day value converts the argument into money. |
| **Tap-through** | The worst stage → what sits in it (top product families or customer groups) with an owner. **Refuses** item-level stock lists and invoice lists. |
| **Alert condition** | No push. Monthly digest line when the cycle worsens by 5+ days. |
| **Data needed** | Average stock value and COGS; receivables and credit sales; payables and purchases. ⚠ **An agreed target cycle and per-stage standards.** ⚠ **Contracted terms vs observed terms per customer and supplier.** ⚠ **Distribution, not just averages** — percentiles must be computable. |
| **Novelty** | CLASSIC — and almost never built, because ERPs report the three components separately and never as one bar. |

---

## 14. Till to Bank

| field | content |
|---|---|
| **Screen name** | `Till to Bank` (12) |
| **Full name** | How Long From Till to Bank — takings to cleared funds |
| **Archetype** | 12 · Cycle-Time & Flow |
| **The question it answers** | "From the moment a customer's cash hits the till, how many days until it is money I can actually spend?" |
| **Key figures** | **Median 2.4 days end to end** vs a 1-day standard · stages: in the till 1.1d · in transit 0.6d · deposited but not cleared 0.7d · **90th percentile 6.8 days** · value stuck in the worst stage right now TZS 46M · **what the lag costs at 18% overdraft: TZS 1.4M a month** |
| **The comparison** | Against the 1-day standard; against a quarter ago; against peer branches on the same measure. |
| **Exception lead** | The stage over standard, with the count and value of takings currently stuck in it, and the branch responsible. |
| **Consolidation level** | Branch → company → group. Branch is the operative level; the group median exists only to set the standard. |
| **Cadence** | Weekly. |
| **The decision it triggers** | **GM / Branch Manager:** change banking frequency, switch to a nearer bank branch, engage a cash-in-transit service, push customers toward mobile money. **CFO:** the lag has a price — this report puts it in shillings, which is what wins the argument for paying a CIT contractor. |
| **Tap-through** | The branch league on this one measure. **Refuses** individual deposit slips. |
| **Alert condition** | Push when any branch's median lag exceeds 4 days for 3 consecutive days. |
| **Data needed** | Till close timestamps; deposit timestamps. ⚠ **Bank value date / clearing date per deposit** (not the posting date). ⚠ **CIT handover records.** ⚠ **The overdraft rate**, to price the lag. |
| **Novelty** | **NOVEL** — a cash business's most expensive invisible delay. Owners feel it ("the money is slow") and have never had it staged, measured and priced. |

---

## 15. Borrowing Room

| field | content |
|---|---|
| **Screen name** | `Borrowing Room` (14) |
| **Full name** | How Much Borrowing Room Is Left — facility, drawn, covenant |
| **Archetype** | 3 · Position & Movement |
| **The question it answers** | "How much can we still borrow, and how close are we to breaking the bank's conditions?" |
| **Key figures** | **Headroom TZS 456M** · facilities 1.20Bn · drawn 744M · movement this month: drawn +180M, repaid −95M · **nearest covenant: current ratio 1.18 vs a 1.20 minimum — breached** · nearest facility expiry: 14 Nov (88 days) |
| **The comparison** | Headroom a month and six months ago; each covenant against its limit; drawn against the seasonal norm (borrowing peaks before Christmas stocking are normal, not alarming). |
| **Exception lead** | The covenant closest to breach, then the nearest expiry. Headroom is the headline number but a covenant breach cancels the headroom entirely, so it leads. |
| **Consolidation level** | Per company — facilities are entity-level and cross-guarantees must be shown explicitly. Group total with a "cross-guaranteed" flag. |
| **Cadence** | Weekly; formally at month-end when covenants are tested. |
| **The decision it triggers** | **Owner / CFO:** open the renewal conversation 90 days early rather than 10; repay to restore a covenant before the test date; stop drawing; open a second facility while you still look strong. |
| **Tap-through** | The facility list — limit, drawn, rate, security pledged, expiry, covenant tests and next test date. **Refuses** the loan transaction ledger. |
| **Alert condition** | Push when headroom falls below 15% of limit; when any covenant comes within 5% of its limit; when any facility is within 90 days of expiry. |
| **Data needed** | Facility limits, drawn balances, rates. ⚠ **Covenant definitions, thresholds and test dates** (almost never in an ERP). ⚠ **Security and pledges register.** ⚠ **Expiry and renewal dates.** ⚠ **Guarantee and LC utilisation against the same limits.** |
| **Novelty** | CLASSIC — and a real gap: most groups track this in the CFO's head and one spreadsheet. |

---

## 16. Odd Payments

| field | content |
|---|---|
| **Screen name** | `Odd Payments` (12) |
| **Full name** | Payments That Broke the Rule — off-policy and duplicate |
| **Archetype** | 6 · Exception Register |
| **The question it answers** | "Which payments went out that should have made somebody raise an eyebrow?" |
| **Key figures** | **14 flagged · TZS 128M · 5 repeats** · by rule: no approved PO 4 (41M) · **paid to a bank account changed within 30 days 1 (26M)** · paid before due date 3 (28M) · possible duplicate 2 (19M) · round-sum manual payment 3 (14M) · supplier with no TIN/VRN 1 (0.6M) |
| **The comparison** | The same counts last month; the repeat share; every line against the written payment policy. A rising count on one rule is a control decaying. |
| **Exception lead** | **Any bank-account-change-then-payment flag comes first regardless of value** — it is the signature of the most common supplier-payment fraud. Then by value. |
| **Consolidation level** | Company and branch, group roll-up. Every line names the approver and the entity. |
| **Cadence** | Weekly; on-alert for the fraud-pattern rules. |
| **The decision it triggers** | **Owner / CFO:** recall or hold a payment, demand a written explanation, change an authority limit, call in audit. **Owner alone** for anything touching a bank-account change. |
| **Tap-through** | The payment with its full approval trail and the supplier's bank-account change history. **Refuses** bulk export — this is an investigation, not a data feed, and exporting it makes it a document that leaks. |
| **Alert condition** | Push immediately when a supplier bank account changes and a payment follows within 30 days; and for any suspected duplicate above TZS 5M. |
| **Data needed** | Payment records with approver, PO linkage and due dates. ⚠ **Supplier bank-account change log with timestamps and who changed it.** ⚠ **A duplicate-detection rule** (same supplier + amount + invoice reference within a window). ⚠ **Supplier TIN/VRN registry and validation.** ⚠ **A written early-payment policy to breach.** ⚠ **An accept-with-reason action** for legitimate exceptions. |
| **Novelty** | **NOVEL** — ERPs enforce approval at the point of payment and then never look back at the population. This looks back. |

---

## 17. Petty Cash

| field | content |
|---|---|
| **Screen name** | `Petty Cash` (10) |
| **Full name** | Floats and Advances Not Retired — by holder, age and value |
| **Archetype** | 6 · Exception Register |
| **The question it answers** | "Who is holding our money outside the tills, how much, and for how long?" |
| **Key figures** | **23 holders · TZS 38M outstanding · oldest 94 days** · floats over their limit: 6 (11M) · advances unretired past 30 days: 9 (19M) · spend with no supporting document: 4.2M · top holder 6.1M / 94 days · **holders who have left the company: 2 (1.4M)** |
| **The comparison** | The same total a month ago; against the float policy (limit per holder, retire within 14 days); repeat holders across months. |
| **Exception lead** | Oldest × largest — the person holding the most for the longest. Leavers with open advances jump to the top regardless of value. |
| **Consolidation level** | Branch and department; company; group total. |
| **Cadence** | Monthly; on-alert past 60 days. |
| **The decision it triggers** | **GM / CFO:** recover from salary, freeze new advances to a holder, cut float limits, close dormant floats. **Owner:** whether the float system should exist at all at this size. |
| **Tap-through** | The holder → their outstanding items with ages. **Refuses** the expense line detail — that is an audit task, not an executive one. |
| **Alert condition** | Push when any advance passes 60 days, when a holder exceeds their limit by 50%, or when a leaver has an open balance. |
| **Data needed** | ⚠ **A float register with limits and named holders.** ⚠ **Advance issue and retirement records with dates.** ⚠ **Supporting-document attachment status.** ⚠ **Holder employment status feed** (to catch leavers). |
| **Novelty** | CLASSIC |

---

## 18. Till Match

| field | content |
|---|---|
| **Screen name** | `Till Match` (10) |
| **Full name** | Does Every Till Match? — over, short and repeat offenders |
| **Archetype** | 13 · Reconciliation & Assurance |
| **The question it answers** | "Does the cash counted at each till agree with what was sold, and is the same person always short?" |
| **Key figures** | Verdict **"6 of 41 tills out"** · **shorts TZS 1.76M / overs TZS 0.42M shown separately** (never netted — offsetting is how theft hides) · worst till: Mbezi #2, short 480k over 3 sessions · **repeat: 3 cashiers short in 3 of their last 5 sessions** · sessions with no blind count: 12 · voids/no-sales above normal: 2 tills |
| **The comparison** | Against zero (the standard), against the same cashier's own history, against branch peers, against the tolerance (TZS 5k per session). |
| **Exception lead** | **Repeat shorts by the same person** — a pattern outranks a single large discrepancy, because one is a control failure and the other is a bad night. |
| **Consolidation level** | Till → branch → company → group. Group sees counts and value; branch sees tills; only the branch manager and above see the cashier line. |
| **Cadence** | Daily glance on exceptions; weekly review. |
| **The decision it triggers** | **Branch Manager / GM:** retrain, re-roster, investigate, suspend. **Owner:** change the cash-handling control (blind counts, dual custody, till limits) when the pattern is estate-wide rather than personal. |
| **Tap-through** | The till's session history — over/short by session with the cashier's identity available to authorised roles only. **Refuses** customer and receipt detail, and refuses a name in the report title (R7: the condition is named, not the person). |
| **Alert condition** | Push when a single session is out by more than TZS 100k; or when a cashier is short in 3 of their last 5 sessions. |
| **Data needed** | Session open/close with declared cash; sales by tender type per session. ⚠ **A blind-count flag** (did the cashier see the expected figure before counting? — without it the whole control is theatre). ⚠ **Cashier identity per session.** ⚠ **Void, refund and no-sale counts per session.** ⚠ **A tolerance policy.** |
| **Novelty** | **NOVEL** at the executive level — POS systems produce z-reads; almost nobody turns them into a repeat-offender pattern an owner can act on from a phone. |

---

## 19. Wallet Match

| field | content |
|---|---|
| **Screen name** | `Wallet Match` (12) |
| **Full name** | Does the Wallet Match? — mobile money against the ledger |
| **Archetype** | 13 · Reconciliation & Assurance |
| **The question it answers** | "Does the money in our M-Pesa, Mixx and Airtel accounts agree with the sales we recorded, after their charges?" |
| **Key figures** | Verdict **"Out by TZS 640k across 3 wallets"** · wallet statements 74.2M vs recorded collections 74.8M · **charges and commission 0.9M = 1.2% of value (negotiated rate 0.9%)** · reversals and failed pushes 0.3M · **unallocated customer payments 1.1M — money we hold that nobody has been credited for** · oldest unallocated 19 days |
| **The comparison** | The two sides side by side; the realised charge rate against the negotiated rate; the unexplained figure a month ago. |
| **Exception lead** | **Unallocated receipts** — a customer has paid, we have the money, and their account still shows a debt. That is a dispute and a lost sale waiting to happen, and it outranks the reconciliation difference itself. |
| **Consolidation level** | Merchant account → branch → company. Group sees the count of unclean wallets and the total unallocated. |
| **Cadence** | Weekly; mandatory at month-end. |
| **The decision it triggers** | **CFO:** chase the aggregator, allocate the unmatched receipts, renegotiate the commission rate when the realised rate drifts above the contract. **Owner:** whether to consolidate onto fewer wallets, and whether the charge rate justifies pushing customers to bank transfer. |
| **Tap-through** | The unallocated pushes with reference, date and masked payer number. **Refuses** full customer phone numbers (mask all but the last 3 digits). |
| **Alert condition** | Push when unallocated wallet receipts exceed TZS 500k or age past 7 days; or when the realised charge rate exceeds the negotiated rate by more than 20%. |
| **Data needed** | Recorded mobile-money collections. ⚠ **Wallet / aggregator statements as a feed** (today usually a portal download per network). ⚠ **Negotiated commission rates per network, per tier.** ⚠ **Reversal and failed-transaction records.** ⚠ **Customer-to-phone-number mapping** for allocation. ⚠ **Float top-up records** where wallets are also used for payments. |
| **Novelty** | **NOVEL** — and specifically Tanzanian. Mobile money is a material share of takings and is very often reconciled once a quarter, badly, if at all. |

---

## 20. Cash Trend

| field | content |
|---|---|
| **Screen name** | `Cash Trend` (10) |
| **Full name** | Which Way the Cash Is Going — 24 months with the normal band |
| **Archetype** | 8 · Trend & Trajectory |
| **The question it answers** | "Are we generating more cash than we used to, or is this month just noise?" |
| **Key figures** | **Verdict in words: "Falling — third consecutive month below the normal band"** · net cash generated this month TZS 41M · normal band = 12-month mean ± 1 SD (90M ± 55M) · same month last year 156M · closing-balance line as a second, ghosted series |
| **The comparison** | The normal band behind the line (this is what makes a movement interpretable); the same month last year for a business with Ramadan and Christmas peaks; event markers for branch openings, price rises and factory shutdowns. |
| **Exception lead** | The verdict sentence and the count of consecutive out-of-band months — a run, not a point. |
| **Consolidation level** | Group; per company; split by branch on tap. |
| **Cadence** | Month-end; a monthly glance. |
| **The decision it triggers** | **Owner:** whether to intervene at all. This report's main job is *preventing* a reaction to a random month. When it does fire (2+ consecutive out-of-band months), the decision is to open *Why Cash Is Short* and *Profit vs Cash*. |
| **Tap-through** | The same series decomposed by company or branch. **Refuses to include the current incomplete month** — and says so on screen, because including it manufactures a collapse every single month. |
| **Alert condition** | Push only when three consecutive months fall outside the band. Nothing else. |
| **Data needed** | 24+ months of monthly cash movement history. ⚠ **Consistently classified history** (a classification change mid-series invalidates the band). ⚠ **An event log for annotations.** ⚠ **Month-close status per period.** |
| **Novelty** | CLASSIC |

---

## 21. Cash League

| field | content |
|---|---|
| **Screen name** | `Cash League` (11) |
| **Full name** | Branch Cash League — cash generated per branch, against plan |
| **Archetype** | 5 · League Table |
| **The question it answers** | "Which branches actually produce cash, and which ones only produce sales?" |
| **Key figures** | **Spread: best 82% cash conversion, worst 31%, group 61%** · per branch: cash banked · **cash conversion (banked ÷ sales)** · days of takings unbanked · credit sales share · net cash after branch running costs · rank-change arrow |
| **The comparison** | The group conversion rate as a printed line every row is measured against; each branch against its own plan; **rank movement since last month**, because a chronic laggard and a newly collapsing branch need opposite responses. |
| **Exception lead** | The biggest **faller**, then the bottom three. Not the leader. |
| **Consolidation level** | Branch within company; group table with company tags. Branches open less than 90 days excluded and the exclusion is printed. Comparability classes kept apart (counter / depot / van route are ranked within class, never against each other). |
| **Cadence** | Weekly headline, monthly review. |
| **The decision it triggers** | **Owner / GM:** where this week's visit goes; which branch's banking and credit practice gets copied into the bottom three. **Branch Manager:** the specific behaviour (banking frequency, credit granting) that moved them down. |
| **Tap-through** | A branch → its Scorecard. **Refuses to rank on absolute cash** — that just re-ranks by branch size and teaches nothing; the ranking rule is printed on screen so nobody argues about it. |
| **Alert condition** | No push. Weekly digest with the biggest faller named. |
| **Data needed** | Sales and cash banked per branch; credit sales per branch. ⚠ **Branch running costs and an agreed allocation rule** for the net-cash row (an unagreed allocation makes the table political). ⚠ **Branch opening dates.** ⚠ **A comparability class per branch.** |
| **Novelty** | **NOVEL** — branches are universally ranked on sales and sometimes on profit; ranking them on *cash generated* exposes the branch that sells beautifully on credit and banks nothing. |

---

## 22. Bank Exposure

| field | content |
|---|---|
| **Screen name** | `Bank Exposure` (13) |
| **Full name** | How Much Rides on One Bank — deposits and counterparty risk |
| **Archetype** | 10 · Concentration & Exposure |
| **The question it answers** | "If one bank froze our accounts tomorrow, how much of our money and our borrowing would be stuck?" |
| **Key figures** | **Our largest bank holds 68% of deposits (TZS 293M) and 74% of our facilities** · top-5 banks with share and cumulative share · **days of operating cash we could run without that bank: 4** · board tolerance line: no bank above 50% of deposits · **share a year ago: 54% — dependence deepening** |
| **The comparison** | Against the board tolerance line drawn across the Pareto; against the same share a year ago (the drift is the whole point). |
| **Exception lead** | Any bank over tolerance, and the *direction* of drift. Nothing here may be wrong today — that is the nature of a fragility report. |
| **Consolidation level** | Group — this is a group treasury question. Companies listed underneath because a single bank relationship usually spans them all, which compounds the exposure. |
| **Cadence** | Quarterly; on-alert. |
| **The decision it triggers** | **Owner / CFO:** open a second operating account, split the payroll account away from the main one, move deposits, or use the concentration as leverage in a rate negotiation before it hardens. |
| **Tap-through** | The bank → its accounts, balances, facilities, rates, security pledged, and relationship history. **Refuses** transaction listings. |
| **Alert condition** | Push when any bank crosses the tolerance share, or when a facility with the dominant bank comes within 90 days of expiry. |
| **Data needed** | Balances and facilities per bank over time. ⚠ **A board-set concentration tolerance.** ⚠ **Security and pledges by bank.** ⚠ **Daily operating cash burn** to compute the "days without them" cover. ⚠ **External bank standing / news** (outside the ERP entirely). |
| **Novelty** | **NOVEL** — treasury counterparty risk is a large-corporate discipline that mid-market groups carry entirely in the owner's head. |

---

# TIER 3 — specialist / on demand

---

## 23. Big Receipts

| field | content |
|---|---|
| **Screen name** | `Big Receipts` (12) |
| **Full name** | How Much Rides on the Big Receipts — the plan's weak points |
| **Archetype** | 10 · Concentration & Exposure |
| **The question it answers** | "Which few payments, if they came a week late, would break my cash plan?" |
| **Key figures** | **The top 5 expected receipts are 54% of next month's inflow (TZS 612M)** · the one that matters most: Tembo Distributors TZS 168M — **a 7-day slip moves the floor date 9 days earlier** · their average lateness history: 11 days · **if all five slip 7 days: shortfall −84M** · tolerance: no single receipt above 20% of the month's inflow |
| **The comparison** | Against the tolerance line; against the same concentration last quarter; each payer's promised date against their historical behaviour. |
| **Exception lead** | The receipt with the largest **date impact** combined with the worst payment history — not simply the largest receipt. |
| **Consolidation level** | Group and company. Related parties grouped, so one owner's four trading names count as one exposure. |
| **Cadence** | Monthly; and always before committing to a large payment or a capex decision. |
| **The decision it triggers** | **Owner / CFO:** get a written promise-to-pay, ask for part-payment up front, take a post-dated instrument, arrange standby borrowing, or hold the payment run until the money lands. |
| **Tap-through** | The payer's payment history and current total exposure. **Refuses** the invoice list — this is about the plan's fragility, not about collections. |
| **Alert condition** | Push when any top-5 expected receipt passes its due date without a promise-to-pay on file. |
| **Data needed** | Expected receipts with due dates. ⚠ **Promise-to-pay dates, with who gave the promise and when.** ⚠ **Per-customer historical lateness distribution.** ⚠ **A forecast sensitivity engine** (shift one receipt, recompute the crossing date). ⚠ **Related-party grouping.** |
| **Novelty** | **NOVEL** — turns a forecast from a projection into a named list of the specific things that can break it. |

---

## 24. Foreign Cash

| field | content |
|---|---|
| **Screen name** | `Foreign Cash` (12) |
| **Full name** | Where the Foreign Cash Is — balances and import cover |
| **Archetype** | 3 · Position & Movement |
| **The question it answers** | "Do we hold enough dollars to cover what we must pay foreign suppliers, or will we be buying at whatever rate we are given on the day?" |
| **Key figures** | **USD 184,000 held (TZS 470M)** · foreign payables due within 60 days USD 310,000 · **cover 59% (policy 80%)** · unhedged shortfall USD 126,000 · **cost of a 5% rate move on the shortfall: TZS 16M** · realised purchase rate last month vs the reference rate: 2.1% spread |
| **The comparison** | Cover ratio against policy and against last month; the rate we actually paid against the official reference rate; movement of the FX balance across the month. |
| **Exception lead** | Cover below policy, with the shillings at risk — not the balance. |
| **Consolidation level** | Group and company, per currency. USD dominant; EUR, CNY, KES where relevant. |
| **Cadence** | Monthly; on-alert whenever an import LC is opened or a large foreign order is placed. |
| **The decision it triggers** | **Owner / CFO:** buy currency now, book a forward, ask the supplier for TZS terms, or delay an import order until cover exists. |
| **Tap-through** | Foreign payables by due date and supplier, with the currency needed per week. **Refuses** FX gain/loss accounting detail — that belongs to the FX domain, and mixing them makes this screen an accounting report instead of a treasury decision. |
| **Alert condition** | Push when cover falls below 50%, or when the realised purchase spread exceeds 3% over the reference rate. |
| **Data needed** | FX balances by currency and account; foreign-currency payables with due dates. ⚠ **Realised purchase rates per FX deal** (to measure the spread we actually pay). ⚠ **An official reference rate feed.** ⚠ **Forward contracts and LC margins.** ⚠ **A board FX cover policy.** |
| **Novelty** | **NOVEL** as a cash-cover framing — most systems hold FX for revaluation, not for "can we pay the import". |

---

## 25. What Waiting Cost Us

| field | content |
|---|---|
| **Screen name** | `What Waiting Cost Us` (20) — the doctrine's own canonical name |
| **Full name** | What Waiting Cost Us — the price of last month's slow approvals |
| **Archetype** | 14 · Decision Docket |
| **The question it answers** | "What did our own delays cost the business last month?" |
| **Key figures** | **34 items approved after their needed-by date · cost TZS 18.6M** · lost early-settlement discounts 6.2M · late-payment penalties and interest 3.1M · **7 supplier stop-supply days → estimated lost sales 8.4M** · rush and expedite fees 0.9M · median approval time 1.8 days vs a 1-day standard |
| **The comparison** | Against the agreed approval standard; against last month; per approver (including the owner). |
| **Exception lead** | The single most expensive delay, and who was holding it — including when the answer is the owner. A report that cannot embarrass the person reading it is not an assurance report. |
| **Consolidation level** | Group; by approver; by company. |
| **Cadence** | Month-end. |
| **The decision it triggers** | **Owner:** raise delegated limits for the classes always approved unchanged; appoint a deputy approver; change the escalation rule. **GM:** restructure an approval chain that costs more in days than it saves in shillings. |
| **Tap-through** | The delayed items with their individual cost and holder. **Refuses** a "worst approvers" title or ranking headline (R7 — the report names the condition, not the person; the detail still shows who). |
| **Alert condition** | No push. This is a monthly conscience, not an alarm. |
| **Data needed** | ⚠ **Approval timestamps at every stage** (not just final approval). ⚠ **A needed-by date on each request** — without it "late" is undefinable, and this is the single build item the report cannot exist without. ⚠ **Early-settlement discount terms per supplier.** ⚠ **Penalty and interest clauses.** ⚠ **Stop-supply events.** ⚠ **Estimated lost-sales value from resulting stock-outs.** |
| **Novelty** | **NOVEL** — measures the executive rather than the staff, which is why almost nobody builds it and why the owner who does build it gets faster within a quarter. |

---

## Build items this suite creates

Consolidated ⚠ list — the data the ERP must start capturing for the Cash suite to exist. Ordered by how many reports each unblocks.

| # | Build item | Unblocks |
|---|---|---|
| 1 | **An approved, versioned, dated treasury/cash plan with targets** (monthly closing cash, collections, cover, utilisation) | 7, 9, 5, 21 — without it there is no comparison and three flagships degenerate into position screens |
| 2 | **A needed-by / due-by date and cost-of-delay attributes on every approval request** (discount terms, penalty clauses, despatch dependency) | 10, 25 |
| 3 | **Promise-to-pay dates with the promiser's name, plus per-customer lateness history** | 5, 6, 23 |
| 4 | **Deposit records with timestamps, matched to bank credits, and per-branch banking policy limits** | 12, 14, 2, 9 |
| 5 | **Bank feed with cleared vs book balance, value dates and itemised charges** | 1, 2, 3, 11, 14, 9 |
| 6 | **Total commitment register** — statutory calendar, payroll forward, loan schedules, standing orders, approved-PO expected invoice dates, fixed/movable flags | 4, 5, 6, 3 |
| 7 | **Facility, covenant and security register** with limits, test dates and expiries | 15, 22, 6 |
| 8 | **Supplier bank-account change log** with timestamps and actor | 16 |
| 9 | **Restricted-cash register** — minimum balances, LC and guarantee margins, deposits, with release dates | 3, 24 |
| 10 | **Mobile-money aggregator statement feed, negotiated rates, and customer-to-phone mapping** | 19, 2, 1 |
| 11 | **Till session integrity data** — blind-count flag, cashier identity, voids/no-sales, tolerance policy | 18, 12 |
| 12 | **Retained forecast versions** (so a forecast can score its own past accuracy) | 5, 6, 23 |
| 13 | **Owner-language movement-class mapping over the chart of accounts** | 2, 7, 8 |
| 14 | **Realised FX deal rates + a reference rate feed + FX cover policy** | 24 |
| 15 | **Accept-with-reason write-back on exception registers** | 12, 16, 17 — without it every register clogs and is abandoned within a fortnight |

## Home screen recommendation for this domain

The owner's Cash tile opens onto six: **Cash to 30 Days · Usable Cash · Today's Cash · Payments Waiting · Unbanked Cash · Profit vs Cash** (the last swapping to the top slot for the three days after each month-end close). Everything else lives one level down, grouped as *Position · Forecast · Control · Comparison*.

===== DOMAIN: credit — RECEIVABLES & CREDIT RISK, and PAYABLES & COMMITMENTS =====
# Executive Report Catalogue — Receivables & Credit Risk · Payables & Commitments

**OrbixERP Executive Mobile · domain spec v1 · 2026-08-18**
25 reports. Two mirrored halves — *who owes us* and *what we owe* — plus two that only make sense across both. Every report is one archetype, sits in a ticked matrix cell, and is named by its family template.

Convention used throughout: **the book** = the debtor ledger; **the bill** = a supplier invoice; **cover** = how long we survive without a thing. TZS everywhere, millions/billions rounded — never shillings-to-the-unit on a provisional figure.

---

## Index

| # | Screen name | Archetype | Tier | Novelty |
|---|---|---|---|---|
| **A — Receivables & credit risk** ||||
| 1 | Debtor Ageing | Ageing Pyramid | 1 | CLASSIC |
| 2 | Over Their Limit | Exception Register | 1 | CLASSIC |
| 3 | Slipping Payers | Exception Register | 1 | **NOVEL** |
| 4 | Promises Broken | Exception Register | 1 | **NOVEL** |
| 5 | Cash Due In | Forecast & Runway | 1 | **NOVEL** |
| 6 | Collection Gap | Variance Bridge | 1 | **NOVEL** |
| 7 | Credit to Approve | Decision Docket | 1 | CLASSIC |
| 8 | Credit Against Plan | Scorecard | 2 | CLASSIC |
| 9 | Biggest Debtors | Concentration & Exposure | 2 | CLASSIC |
| 10 | Collector League | League Table | 2 | CLASSIC |
| 11 | Collection Days | Trend & Trajectory | 2 | CLASSIC |
| 12 | Book Movement | Position & Movement | 2 | CLASSIC |
| 13 | Disputed Invoices | Exception Register | 2 | **NOVEL** |
| 14 | Debt at Risk | Forecast & Runway | 2 | **NOVEL** |
| 15 | New Credit Quality | Cohort & Retention | 3 | **NOVEL** |
| **B — Payables & commitments** ||||
| 16 | Supplier Bills by Age | Ageing Pyramid | 1 | CLASSIC |
| 17 | Bills Falling Due | Forecast & Runway | 1 | CLASSIC |
| 18 | Discounts We Lost | Exception Register | 1 | **NOVEL** |
| 19 | Money Committed | Position & Movement | 1 | **NOVEL** |
| 20 | Payments to Approve | Decision Docket | 1 | CLASSIC |
| 21 | Overdue Orders | Exception Register | 2 | **NOVEL** |
| 22 | Goods Not Billed | Reconciliation & Assurance | 2 | CLASSIC |
| 23 | Supplier Dependence | Concentration & Exposure | 3 | CLASSIC |
| **C — Both sides** ||||
| 24 | Money Tied Up | Cycle-Time & Flow | 2 | **NOVEL** |
| 25 | What Waiting Cost Us | Decision Docket | 3 | **NOVEL** |

---
---

# SECTION A — RECEIVABLES & CREDIT RISK

---

## 1. Debtor Ageing

| field | content |
|---|---|
| **Screen name** | `Debtor Ageing` (13) |
| **Full name** | Who Owes Us, and How Long — by age, largest and oldest first |
| **Archetype** | 7 · Ageing Pyramid |
| **The question it answers** | "How much is out there, and how much of it has gone rotten?" |
| **Key figures** | 1) Total owed TZS 1.94Bn · 2) Overdue share 47% (TZS 912M) · 3) Over-90 share 31% (TZS 601M) · 4) Buckets: current / 1–30 / 31–60 / 61–90 / 90+ with value **and** account count · 5) Top 5 names inside the 90+ bucket with their amount and their days · 6) Unallocated receipts TZS 40M not yet applied |
| **The comparison** | The same pyramid one month ago as a ghost outline behind the current stack — is the base fattening or the tip? Plus the 90+ share against the board's tolerance (≤ 10%). |
| **Exception lead** | The 90+ bucket opens the body, with its five worst names visible without scrolling. The total is deliberately *not* the first thing read — the toxic share is. |
| **Consolidation level** | Group headline; rolls down company → branch → customer, and sideways to **customer group** (related parties consolidated — one owner with four trading names is one exposure). |
| **Cadence** | Weekly glance; hard read every Monday and at month-end. |
| **The decision it triggers** | Stop supply to a named account (Owner/GM); hand a bucket to a named collector with a date (CFO); provide for or write off the tail (CFO). |
| **Tap-through** | A bucket → the names inside it, ranked by amount, each with days-overdue and last receipt date. **Refuses** to show invoice lines — an owner never needs invoice numbers to decide to stop a supply. |
| **Alert condition** | Push when 90+ value rises more than 15% week-on-week, or when any single account enters 90+ with more than TZS 20M. |
| **Data needed** | Open invoices with **due date** (age by due date, never document date), receipts and their allocation, credit notes, customer→customer-group parent, agreed terms per customer. ⚠ **Related-party grouping is rarely captured** — without it concentration hides. ⚠ **Dispute flag** needed to keep genuine disputes out of the 90+ stack (see #13). |
| **Novelty** | CLASSIC — but most ERPs age by document date and show no names; both are corrected here. |

---

## 2. Over Their Limit

| field | content |
|---|---|
| **Screen name** | `Over Their Limit` (16) |
| **Full name** | Customers Trading Past Their Credit Limit — by how much, and for how long |
| **Archetype** | 6 · Exception Register |
| **The question it answers** | "Who is buying on credit they were never granted, and how much is exposed right now?" |
| **Key figures** | 1) 23 accounts over limit · 2) TZS 84M above granted limits in total · 3) Worst single breach: TZS 19M over on one account · 4) 9 of the 23 are repeats from last week · 5) 4 breaches were created by an over-ride, not by drift — with the over-riding user's role · 6) Exposure counted as invoices + undelivered orders + unbilled deliveries, not invoices alone |
| **The comparison** | Count and value against the same day last week; repeat-vs-new split; each breach against its own granted limit (printed on the row). |
| **Exception lead** | The largest *money over limit* first — never alphabetical, never by count. A repeat breach ranks above a new one of the same value. |
| **Consolidation level** | Group roll-up of value; the row is per customer-group, showing which branch let it through. |
| **Cadence** | Daily glance for the credit controller, weekly for the Owner; on-alert for the GM. |
| **The decision it triggers** | Block further despatch, raise the limit formally, or take security (Owner/CFO). Branch Manager is told which order to hold today. |
| **Tap-through** | The account: its limit, exposure build-up, who last over-rode a block and why. **Refuses** to offer a limit increase inline — a limit change must go through the docket (#7) so it is audited and priced. |
| **Alert condition** | Push immediately when total over-limit exposure passes TZS 100M, or when a single account exceeds its limit by more than 50%. |
| **Data needed** | Granted credit limit per customer-group, live exposure including **undelivered orders and delivered-not-yet-invoiced**, block/over-ride events with user and reason. ⚠ **Over-ride reason capture** is usually missing. ⚠ Limits are often set per *account* not per *group* — that must change or the report lies. |
| **Novelty** | CLASSIC — with two upgrades most ERPs miss: exposure includes commitments, and over-rides are named. |

---

## 3. Slipping Payers

| field | content |
|---|---|
| **Screen name** | `Slipping Payers` (15) |
| **Full name** | Customers Paying Slower Than They Used To — before they become a bad debt |
| **Archetype** | 6 · Exception Register |
| **The question it answers** | "Which of my good customers are quietly turning bad — while they are still paying?" |
| **Key figures** | 1) 11 accounts slipping · 2) TZS 310M of exposure sits behind them · 3) Worst slip: from 22 to 58 average days over three months · 4) Their combined margin last 12 months TZS 96M (what we lose if we cut them) · 5) How many are still *within* terms today (the whole point — 6 of 11) · 6) Slip score per row: change in days-to-pay, weighted by balance |
| **The comparison** | Each customer against **their own** 12-month payment behaviour, not against a group average. A customer who always took 45 days and still takes 45 is not on this list; one who moved 22 → 38 is, even though 38 is better than the group. |
| **Exception lead** | The biggest deterioration carrying the biggest balance — sorted by money-at-risk × slip, so a 30-day slip on a TZS 2M account never outranks a 12-day slip on TZS 200M. |
| **Consolidation level** | Group; drillable by branch and by salesperson (deterioration clusters around one seller surprisingly often). |
| **Cadence** | Weekly. This is the report that earns its keep between month-ends. |
| **The decision it triggers** | Call the customer *this week* while the relationship is intact; shorten terms; ask for a deposit; move to cash-on-delivery before the balance grows (Owner or GM, not the clerk — this is a relationship act). |
| **Tap-through** | One account's payment history as a simple days-to-pay line with the slip marked. **Refuses** to show the open invoice list — the decision is about the pattern, not the paperwork. |
| **Alert condition** | Push when an account with over TZS 50M exposure slips more than 15 days versus its own 12-month norm for two consecutive months. |
| **Data needed** | Full payment history per customer (invoice due date → cash-received date), rolling average days-to-pay, exposure, margin earned per customer. ⚠ **Margin per customer** requires cost allocation at line level — usually available but rarely surfaced. ⚠ Needs at least 12 months of history to be meaningful; new accounts go to #15 instead. |
| **Novelty** | **NOVEL** — almost no ERP flags a customer who is *still within terms*. This is the single highest-value report in the domain: it converts a write-off into a phone call. |

---

## 4. Promises Broken

| field | content |
|---|---|
| **Screen name** | `Promises Broken` (15) |
| **Full name** | Payment Promises That Were Not Kept — who promised, how much, how often |
| **Archetype** | 6 · Exception Register |
| **The question it answers** | "Who told us they would pay and then did not — and which of them tell us that every month?" |
| **Key figures** | 1) 17 promises broken this month · 2) TZS 268M promised and not received · 3) Serial breakers: 5 accounts with 3+ broken promises in 6 months · 4) Promise-keeping rate group-wide 62% · 5) Value of promises still open (falling due in the next 7 days) TZS 190M · 6) Days between promise date and actual payment, median 11 |
| **The comparison** | Promise-keeping rate against last quarter and against the 85% standard; each account against its own promise history. Also: which collector's promises are kept most often — some staff extract real commitments, others extract polite noises. |
| **Exception lead** | Serial breakers first, with the count of broken promises printed on the row. A first-time miss ranks below a third-time miss of half the value. |
| **Consolidation level** | Group; by branch and by collector. |
| **Cadence** | Weekly, and daily during a collection push. |
| **The decision it triggers** | Stop accepting promises from a named account and demand a bank slip before despatch (Owner/CFO); reassign the account to a senior collector; use the promise record as evidence for legal action (CFO). |
| **Tap-through** | The account's promise ledger — every promise, the amount, who took it, what actually arrived. **Refuses** to let a new promise be recorded from the executive app; that is the collector's job in the back office. |
| **Alert condition** | Push when a promise over TZS 25M passes its date unpaid, on the morning after. |
| **Data needed** | ⚠ **Promise-to-pay records — date, amount, who promised, who took the promise — are almost never captured in an ERP.** This is the flagship build item of the AR half: a lightweight promise log against the customer, filled by collectors and by the branch, with automatic matching to receipts. ⚠ Also needs collector assignment per account. |
| **Novelty** | **NOVEL** — and the data does not exist yet. Build the promise log; it also feeds #5's confidence split and #10's league. |

---

## 5. Cash Due In

| field | content |
|---|---|
| **Screen name** | `Cash Due In` (11) |
| **Full name** | How Much Will Actually Reach the Bank — next 30 days, and how sure we are |
| **Archetype** | 9 · Forecast & Runway |
| **The question it answers** | "Of everything owed to me, how much will really land in the next thirty days — and by when?" |
| **Key figures** | 1) Expected collections TZS 1.12Bn over 30 days · 2) Split: TZS 640M **promised** (a named commitment behind it) / TZS 480M **modelled** (behaviour-based) · 3) Week-by-week: W1 340M, W2 260M, W3 300M, W4 220M · 4) The gap against the funding need on the same dates (from #17) · 5) Last month's forecast was 9% optimistic · 6) The five accounts that move the answer most |
| **The comparison** | Forecast against the collection plan for the month, against the previous run of the same forecast (drift is the signal), and against its own historical accuracy — printed, not hidden. |
| **Exception lead** | The week where expected receipts fall below committed payments — that week is called out in words above the chart ("Week 3 is short by TZS 120M"), not left to be spotted in the bars. |
| **Consolidation level** | Group is the only level that matters for cash, but must decompose by company (legal entities cannot lend each other cash freely) and by branch for accountability. |
| **Cadence** | Twice weekly — Monday and Thursday. Daily during a squeeze. |
| **The decision it triggers** | Pull a collection forward, delay a payment run, draw the overdraft, or discount for early settlement (CFO, sanctioned by Owner). |
| **Tap-through** | The driver list — the accounts and amounts the forecast leans on most, ranked by how much they move the answer. **Refuses** to show a full invoice-due schedule; that is a finance-office worklist, not an executive screen. |
| **Alert condition** | Push when the 30-day forecast falls more than 15% below the plan, or when any forward week turns negative against committed outflows. |
| **Data needed** | Open invoices with due dates, per-customer historical payment behaviour (to model the un-promised half), ⚠ **promise-to-pay dates** (#4), ⚠ **a collection plan/target by week** — usually absent, and without it there is nothing to miss against, ⚠ post-dated cheques and standing arrangements, ⚠ **forecast-run history** so the accuracy line can exist at all. |
| **Novelty** | **NOVEL** — the promised/modelled split and the self-reported accuracy are what make a forecast believable; nearly every ERP ships a single unqualified line and is disbelieved after its first miss. |

---

## 6. Collection Gap

| field | content |
|---|---|
| **Screen name** | `Collection Gap` (14) |
| **Full name** | Why the Collections Missed — plan to actual, by cause |
| **Archetype** | 4 · Variance Bridge |
| **The question it answers** | "We planned to collect TZS 1.4Bn and we collected 1.16Bn. Where did the 240 million go?" |
| **Key figures** | Waterfall, largest cause first, capped at six bars plus a residual under 10%: 1) Broken promises −TZS 96M · 2) Disputes withheld −TZS 54M · 3) New credit extended (sales grew, so the book grew) −TZS 42M · 4) Deliveries invoiced late −TZS 31M · 5) Early receipts pulled in +TZS 18M · 6) Write-offs and credit notes −TZS 12M · residual −TZS 5M. Headline is **the gap**, not the total collected. |
| **The comparison** | Built into the form: plan → actual. Each bar also carries last month's same bar, so a chronic cause is visibly chronic. |
| **Exception lead** | The largest negative bar is the headline sentence in words: "Broken promises cost us TZS 96M — 40% of the miss." |
| **Consolidation level** | Group; each bar decomposes by branch and by collector. |
| **Cadence** | Month-end, and mid-month when the plan is clearly slipping. |
| **The decision it triggers** | A named corrective act per bar: broken promises → escalate authority for supply blocks (Owner); late invoicing → fix the despatch-to-invoice handoff (GM); disputes → put a deadline on the dispute desk (CFO). |
| **Tap-through** | Each bar → its league table: which branches and which accounts caused *that* cause. **Refuses** a general drill into all receipts — the bridge exists to prevent that browse. |
| **Alert condition** | No push. This is a read, not a trigger — it is opened *because* #5 or #8 already raised the alarm. |
| **Data needed** | ⚠ **A collection plan by period** (the same build item as #5), promise records, dispute records with amount withheld, despatch date vs invoice date, credit notes and write-offs, new credit sales in period. ⚠ Causes must be **mutually exclusive by construction** — the decomposition rule needs signing off once by the CFO or the bars will double-count. |
| **Novelty** | **NOVEL** — ERPs report collections; almost none decompose the shortfall into causes an executive can act on. |

---

## 7. Credit to Approve

| field | content |
|---|---|
| **Screen name** | `Credit to Approve` (17) |
| **Full name** | Credit Waiting on You — held orders, limit requests and write-offs |
| **Archetype** | 14 · Decision Docket |
| **The question it answers** | "What credit decision is sitting on me right now, and what is it costing to leave it there?" |
| **Key figures** | 1) 9 items · 2) TZS 340M of value held · 3) Oldest 6 days · 4) 3 items are blocking despatch today (goods loaded, van waiting) · 5) Split: 5 held orders / 3 limit increases / 1 write-off · 6) Your own median decision time 1.8 days |
| **The comparison** | Each item against policy (within limit / over limit / customer already in 90+ / no security held) and against your own median approval time. The queue's total against last week's queue. |
| **Exception lead** | Ranked by consequence of delay — a TZS 4M order with a van at the gate outranks a TZS 60M limit review that can wait until Friday. Never arrival order. |
| **Consolidation level** | Personal — items addressed to this approver, across all companies and branches they cover. |
| **Cadence** | Daily, twice. This is a home-screen tile. |
| **The decision it triggers** | The approval itself (Owner / CFO / GM by threshold). Over time: a delegation decision — items you approve unchanged 20 times running should have their limit raised. |
| **Tap-through** | Approve / reject / send back with a reason, plus **the one fact needed to decide**: that customer's current exposure, their ageing shape, and their last three payment behaviours. **Refuses** to show the order's line items — you are approving credit, not pricing. |
| **Alert condition** | Push when an item enters the queue that blocks a despatch, and again at 24 hours for anything over TZS 50M still unactioned. |
| **Data needed** | Approval queue with requester, value, reason for hold, blocking status; customer exposure and ageing at decision time; approval thresholds by role; ⚠ **"what breaks if this waits" (blocking-despatch flag)** is not normally modelled; ⚠ decision-time history per approver, to compute the median and to prove the owner is or is not the bottleneck. |
| **Novelty** | CLASSIC as an inbox — **NOVEL** in that it ranks by cost of delay and carries the deciding fact inline. |

---

## 8. Credit Against Plan

| field | content |
|---|---|
| **Screen name** | `Credit Against Plan` (19) |
| **Full name** | The Credit Book Against Plan — the six numbers we hold ourselves to |
| **Archetype** | 2 · Scorecard |
| **The question it answers** | "Is the credit book behaving the way we agreed it would?" |
| **Key figures** | Six fixed measures, each with actual / target / gap: 1) Collections TZS 1.16Bn vs plan 1.40Bn · 2) DSO 54 days vs target 45 · 3) Overdue share 47% vs 30% · 4) Over-90 share 31% vs 10% · 5) Bad-debt charge TZS 38M vs 25M · 6) Accounts over limit 23 vs 5 |
| **The comparison** | The target, printed on every row — board-approved and dated. Rows ordered by **size of miss**, not importance. Colour band per row. |
| **Exception lead** | The verdict replaces the total: "2 of 6 on plan" in a red band at the top. The worst miss is row one. |
| **Consolidation level** | Group headline; every measure rolls down to company and branch with the same six rows — this is the section's spine, and the branch version is what a Branch Manager is held to. |
| **Cadence** | Weekly glance, formal read at month-end. |
| **The decision it triggers** | Which single miss gets management attention this week; and, when a target is missed by every branch for three months, whether the plan is wrong rather than the performance (CFO proposes, Owner rules). |
| **Tap-through** | Any red row → its Variance Bridge (#6) or its register (#1, #2). That pairing is the backbone. **Refuses** to add a seventh measure — the set is fixed for the year so the owner can hold it in memory. |
| **Alert condition** | Push once a month on close, plus immediately if any measure crosses into the red band from green (a two-step move). |
| **Data needed** | ⚠ **Agreed targets for all six measures, dated and versioned** — the most commonly missing thing in the whole suite; without targets this degenerates into a position statement. Everything else is standard AR data. |
| **Novelty** | CLASSIC. |

---

## 9. Biggest Debtors

| field | content |
|---|---|
| **Screen name** | `Biggest Debtors` (15) |
| **Full name** | If Our Biggest Debtor Stopped Paying — share of the book and our cover |
| **Archetype** | 10 · Concentration & Exposure |
| **The question it answers** | "How much of my money is standing with a handful of people, and could I survive one of them failing?" |
| **Key figures** | 1) Largest single exposure 27% of the book (TZS 524M) · 2) Top 5 = 61% cumulative · 3) That top account is 19% of group **margin**, not just revenue · 4) Cover: losing it would consume 4.1 months of group profit · 5) Security held against it: none · 6) Same share 12 months ago: 18% — dependence is deepening |
| **The comparison** | This year's shares against last year's (the drift is the story), and against the board's tolerance line — no single customer above 15% of the book — drawn across the Pareto. |
| **Exception lead** | Any account above the tolerance line, shown first with its breach printed, before the ranked list. |
| **Consolidation level** | Group, consolidated by **customer group / related party**, not by trading account. Also shown per company where legal exposure is separate. |
| **Cadence** | Monthly; formally at board and at insurance renewal. |
| **The decision it triggers** | Cap the account's exposure, take security or a guarantee, buy credit insurance, or deliberately grow the second tier of customers (Owner, with CFO). |
| **Tap-through** | The account's own history: exposure over 24 months, terms, security held, payment behaviour. **Refuses** to show its invoices — this is a fragility report, nothing here is necessarily late. |
| **Alert condition** | Push when any single customer group passes 20% of the book, or when the top-5 cumulative share rises 5 points year-on-year. |
| **Data needed** | Exposure by customer group, ⚠ **related-party links between accounts**, margin by customer (12-month rolling), ⚠ **security/guarantees held per customer — rarely recorded anywhere in an ERP**, ⚠ credit insurance cover if any, board tolerance thresholds. |
| **Novelty** | CLASSIC — sharpened by measuring **margin share and cover**, not revenue share. Most ERPs rank by balance and stop. |

---

## 10. Collector League

| field | content |
|---|---|
| **Screen name** | `Collector League` (16) |
| **Full name** | Collector League — cash brought in against the book each one carries |
| **Archetype** | 5 · League Table |
| **The question it answers** | "Who is actually getting the money in, on a fair comparison?" |
| **Key figures** | 1) The spread: best collects 71% of what falls due in the month, worst 34%, group 52% · 2) Ranked rows: collector, book carried, collected %, promise-keeping rate on their promises, 90+ movement on their book · 3) Rank-change arrow versus last month · 4) Middle collapsed ("+4 collectors between") |
| **The comparison** | The group rate as a printed reference line every row is measured against — never an absolute amount, or the collector with the biggest book always wins and the table teaches nothing. |
| **Exception lead** | Not the leader — the **spread** is the headline, and the bottom three are shown in full with their rank arrows. A collector newly collapsing outranks a chronic laggard for attention. |
| **Consolidation level** | Per company; branch collectors compared only against comparable books (a wholesale credit book and a retail counter book are not peers — the report states its peer rule). |
| **Cadence** | Monthly; weekly during a collection push. |
| **The decision it triggers** | Reassign the worst accounts to the best collector; copy the top performer's practice (usually: they take dated, specific promises); coach or replace (GM/CFO). |
| **Tap-through** | A collector → their own scorecard: book, ageing shape, promises made and kept. **Refuses** to name-and-shame on the tile label (R7 — the name is "Collector League", never "Worst Collectors"). |
| **Alert condition** | No push. Monthly read. |
| **Data needed** | ⚠ **Collector assignment per account** (with history, so a reassignment does not corrupt the month), amount falling due per collector per month, receipts attributed to the collector's book, promise records (#4). ⚠ Peer-comparability rule must be defined once, or the table is dismissed. |
| **Novelty** | CLASSIC — with the essential fix that it ranks on *percentage of what fell due*, not shillings collected. |

---

## 11. Collection Days

| field | content |
|---|---|
| **Screen name** | `Collection Days` (15) |
| **Full name** | Which Way Collection Days Are Going — DSO against the normal band |
| **Archetype** | 8 · Trend & Trajectory |
| **The question it answers** | "Are we getting paid slower than we used to, or is this just a bad month?" |
| **Key figures** | 1) DSO 54 days · 2) Verdict in words: "Rising — 4th consecutive month outside the normal band" · 3) The band: 12-month mean ±1 SD (44–50 days) · 4) Same month last year: 41 days · 5) 24 monthly points on one line · 6) Event markers: terms change in March, new branch in June |
| **The comparison** | The normal band behind the line — the band is what makes a movement interpretable — plus the prior-year line for seasonality (Ramadan and Christmas both distort a Tanzanian trading book). |
| **Exception lead** | The verdict sentence sits **above** the chart, so the exception is read before the picture. A flat month says "Flat within normal range" and is honest about being uninteresting. |
| **Consolidation level** | Group; decomposes to branch and to customer segment (wholesale / retail credit / institutional — institutions are structurally slow and should not be blamed for it). |
| **Cadence** | Monthly. Never read on a partial month — the current month is excluded from the trend and shown greyed. |
| **The decision it triggers** | Whether to intervene at all. A run of consecutive points outside the band opens #6 (Collection Gap) and, if terms are the cause, a terms review (CFO → Owner). |
| **Tap-through** | The same series split by branch, or by customer segment. **Refuses** to plot fewer than 13 points, and refuses to include the incomplete current month. |
| **Alert condition** | Push when DSO closes three consecutive months above the band, not on a single month. |
| **Data needed** | Monthly closing receivables and credit sales for 24+ months, ⚠ **segment tagging of customers** (wholesale/retail/institutional), ⚠ **an event log of policy changes** (terms changes, branch openings, credit-policy edits) to place the markers — this is a small build item with outsized interpretive value. |
| **Novelty** | CLASSIC — the band, the seasonal line and the excluded partial month are the three things that usually go wrong. |

---

## 12. Book Movement

| field | content |
|---|---|
| **Screen name** | `Book Movement` (13) |
| **Full name** | How the Debtor Book Moved — opening to closing, by cause |
| **Archetype** | 3 · Position & Movement |
| **The question it answers** | "The book grew by 300 million this month — is that good news or bad news?" |
| **Key figures** | Waterfall in business categories, not GL account names: 1) Opening TZS 1.64Bn · 2) New credit sales +TZS 1.48Bn · 3) Receipts −TZS 1.16Bn · 4) Credit notes and returns −TZS 34M · 5) Write-offs −TZS 12M · 6) Unallocated cash sitting unapplied −TZS 40M · closing TZS 1.94Bn. Residual must stay under 3%. |
| **The comparison** | Opening vs closing; the same movement shape last month; and closing book against the same month last year at comparable sales volume (growth-adjusted — a book that grows slower than sales is a *win*). |
| **Exception lead** | The one-line verdict: "The book grew 18% while credit sales grew 6% — the growth is slow payment, not more trade." That sentence is the report. |
| **Consolidation level** | Group and company; branch view for branches that carry their own credit book. |
| **Cadence** | Month-end. |
| **The decision it triggers** | Whether the increase is planned growth (accept), slow payment (collect), or unapplied cash (an admin failure to fix today) — CFO decides, Owner is informed. |
| **Tap-through** | Any movement bar → its top contributors (accounts, not transactions). **Refuses** the ledger detail; if the closing balance disagrees with the GL, the screen says so in the trust line rather than inviting a hunt. |
| **Alert condition** | Push when unallocated receipts exceed TZS 50M at month-end — that is cash we have that our reports say we do not. |
| **Data needed** | Opening/closing AR, credit sales, receipts, credit notes, write-offs, unallocated cash on account, FX revaluation if any foreign-currency customers. ⚠ Movement categories must be **business categories signed off once**; "Adjustments 9200" on this screen kills the report. |
| **Novelty** | CLASSIC — the growth-adjusted verdict line is the upgrade. |

---

## 13. Disputed Invoices

| field | content |
|---|---|
| **Screen name** | `Disputed Invoices` (18) |
| **Full name** | Invoices the Customer Refuses to Pay — value, reason and days open |
| **Archetype** | 6 · Exception Register |
| **The question it answers** | "How much of what I think I am owed is actually being argued about — and whose fault is it?" |
| **Key figures** | 1) 31 disputes · 2) TZS 187M withheld (9.6% of the book) · 3) Oldest open 74 days · 4) By reason: short delivery 41%, price disagreement 28%, damaged goods 19%, missing fiscal receipt 12% · 5) TZS 112M of it sits in the 90+ ageing bucket and is distorting it · 6) Our own fault rate: 68% of value resolved in the customer's favour last quarter |
| **The comparison** | Dispute value against the same month last quarter; reason mix against last quarter (a rising short-delivery share is a warehouse problem, not a credit problem); each dispute's age against the 14-day resolution standard. |
| **Exception lead** | The oldest high-value dispute, plus the count of disputes with **no owner assigned** — an unowned dispute is the real failure. |
| **Consolidation level** | Group; by branch, and by **cause-owner department** (warehouse / sales / finance) — which is what makes it act. |
| **Cadence** | Weekly for the CFO; monthly for the Owner. |
| **The decision it triggers** | Settle, escalate or write off a named dispute (CFO); fix the upstream process the reason mix points at (GM); and remove disputed value from collector targets so nobody is chasing money we are not owed. |
| **Tap-through** | The dispute: reason, value, who owns it, what has been agreed so far. **Refuses** to allow settlement from the executive app — settlement is a credit note and belongs in the docket (#7). |
| **Alert condition** | Push when disputed value passes 10% of the book, or when any single dispute over TZS 25M passes 30 days. |
| **Data needed** | ⚠ **A dispute record: flag on the invoice, reason code, value withheld, owner, opened date, resolution and outcome — very few ERPs model this at all.** Second-biggest build item on the AR side. Needs to feed #1 so disputed items are visually separated from genuine slow payment. |
| **Novelty** | **NOVEL** — and the "our own fault rate" figure is the one that changes behaviour: it proves the debtor book is partly a warehouse and invoicing problem. |

---

## 14. Debt at Risk

| field | content |
|---|---|
| **Screen name** | `Debt at Risk` (12) |
| **Full name** | How Much of This Debt We Will Never Collect — expected loss and when |
| **Archetype** | 9 · Forecast & Runway |
| **The question it answers** | "Of the 1.94 billion out there, how much is honestly gone — and what should I be putting aside?" |
| **Key figures** | 1) Expected loss TZS 143M (7.4% of the book) · 2) Provision currently carried TZS 88M — **short by TZS 55M** · 3) High-confidence loss (no payment 180+ days, no promise, no contact) TZS 61M · 4) Loss expected to harden by quarter: Q3 TZS 34M, Q4 TZS 71M · 5) Top 5 accounts making up 58% of the expected loss · 6) Last year's prediction was 12% under actual |
| **The comparison** | Expected loss against the provision carried (the gap is the headline), against last year's actual write-offs, and against the prediction's own past accuracy. |
| **Exception lead** | The provision shortfall, stated as money and as a P&L consequence: "TZS 55M of profit is not yet recognised as lost." |
| **Consolidation level** | Group and per company (provisioning is a statutory, per-entity act). |
| **Cadence** | Month-end; hard read at quarter and year end. |
| **The decision it triggers** | Raise or release the provision (CFO, Owner signs); hand a named account to a lawyer or a debt collector; decide to write off and stop spending collector time on a dead balance (Owner). |
| **Tap-through** | The accounts driving the expected loss, each with the evidence: age, last payment, last contact, broken promises, disputes. **Refuses** to average — a loss rate applied blindly to a bucket is how provisions get argued with; each large account is assessed by name. |
| **Alert condition** | Push at month-end when the shortfall between expected loss and carried provision exceeds TZS 50M. |
| **Data needed** | Ageing, payment history, broken promises (#4), disputes (#13), ⚠ **last-contact date and contact outcome per account**, ⚠ **loss-rate history by bucket and by segment** (needs several years of write-off history to calibrate), ⚠ current provision balance by entity, ⚠ **a stated provisioning policy** to measure against. |
| **Novelty** | **NOVEL** — an expected-loss view with a stated shortfall and self-reported accuracy. Most ERPs offer an ageing and leave the judgement entirely to the auditor in March. |

---

## 15. New Credit Quality

| field | content |
|---|---|
| **Screen name** | `New Credit Quality` (18) |
| **Full name** | Do New Credit Customers Pay Like the Old Ones? |
| **Archetype** | 11 · Cohort & Retention |
| **The question it answers** | "The customers we took on this year — are they as good as the ones we took on before, or are we buying growth with bad credit?" |
| **Key figures** | 1) Verdict: "Accounts opened in 2026 are 2.3× more likely to reach 90 days than the 2024 intake" · 2) Six cohorts by opening half-year, each a small line: share of cohort value overdue at months 3, 6, 12 · 3) 2026-H1 cohort: 31% of value overdue by month 6 vs group norm 14% · 4) Value still active per cohort (do they keep trading?) · 5) Write-off rate per cohort · 6) Which branch opened the worst cohort |
| **The comparison** | Cohort against cohort — that is the whole comparison, no external target needed. Recent cohorts highlighted, older ones ghosted. |
| **Exception lead** | The plain-language verdict sentence on top; the deteriorating cohort's line drawn heaviest. |
| **Consolidation level** | Group; splits by opening branch and by opening salesperson — this is where a "new-accounts opened" incentive shows up as a credit problem. |
| **Cadence** | Quarterly. Specialist, but the highest-leverage report in the domain for policy. |
| **The decision it triggers** | Tighten credit screening at onboarding; stop rewarding account-count targets; require a deposit for the first 90 days of any new account; retrain the branch that is opening bad accounts (Owner + CFO, policy level). |
| **Tap-through** | The accounts inside a failing cohort. **Refuses** to be presented as a 60-cell heat grid — on a phone it is 5–6 lines and a verdict, or it is nothing. |
| **Alert condition** | No push. A quarterly read. |
| **Data needed** | Account opening date and opening branch/salesperson, monthly overdue value per account since opening, write-offs by account, ⚠ **credit-screening record at onboarding** (what checks were done, what limit was granted, on whose authority) — normally not captured and it is the causal variable, ⚠ cohorts under 3 months must be shown but marked uninterpretable. |
| **Novelty** | **NOVEL** — cohort thinking is virtually absent from ERP credit reporting, and it is the only way to separate "we are growing" from "we are lending badly and calling it growth". |

---
---

# SECTION B — PAYABLES & COMMITMENTS

---

## 16. Supplier Bills by Age

| field | content |
|---|---|
| **Screen name** | `Supplier Bills by Age` (21) |
| **Full name** | What We Owe and How Long We Have Owed It — by age and by supplier |
| **Archetype** | 7 · Ageing Pyramid |
| **The question it answers** | "How much do I owe, how late am I, and which supplier is about to stop serving me?" |
| **Key figures** | 1) Total owed TZS 1.31Bn · 2) Overdue TZS 402M (31%) · 3) Over 60 days TZS 156M · 4) Buckets: not yet due / 1–30 / 31–60 / 60+ with value and bill count · 5) The 5 suppliers we are most overdue with, and whether each is single-sourced (from #23) · 6) On credit hold with us: 2 suppliers |
| **The comparison** | The same pyramid one month ago as a ghost outline; overdue share against our own stated policy (pay within terms, ≤ 5% overdue). |
| **Exception lead** | Not the biggest debt — the **most overdue supplier we cannot replace**. Being 70 days late with a commodity trader is a nuisance; with the sole importer of a raw material it is a factory stoppage. |
| **Consolidation level** | Per company (legal obligation) with a group roll-up; branch only where branches raise their own purchases. |
| **Cadence** | Weekly. |
| **The decision it triggers** | Which supplier gets paid first out of limited cash this week (CFO proposes, Owner rules); which relationship needs a phone call before it becomes a supply problem (GM). |
| **Tap-through** | A supplier → their bills by age with due dates and any dispute flag. **Refuses** to initiate payment — payment goes through #20. |
| **Alert condition** | Push when a single-sourced supplier's overdue balance passes 30 days, or when total overdue passes TZS 500M. |
| **Data needed** | Open bills with due dates and agreed terms per supplier, disputes, ⚠ **supplier criticality / single-source flag** (see #23), ⚠ **notice of credit hold from a supplier** — a real-world event nobody records in the system. |
| **Novelty** | CLASSIC — with the criticality overlay, which is what makes it executive rather than clerical. |

---

## 17. Bills Falling Due

| field | content |
|---|---|
| **Screen name** | `Bills Falling Due` (17) |
| **Full name** | What We Must Pay Next — due bills against the cash we will have |
| **Archetype** | 9 · Forecast & Runway |
| **The question it answers** | "What has to go out in the next two weeks, and do I have it?" |
| **Key figures** | 1) Due in 14 days TZS 640M · 2) Cash and expected receipts over the same days TZS 512M — **short TZS 128M on day 9** · 3) The date the shortfall bites: 27 August · 4) Split: must-pay (statutory, payroll, VAT, single-source supplier) TZS 410M / can-slip TZS 230M · 5) Discounts at stake if we slip TZS 6.2M (from #18) · 6) Unpaid last run: 3 bills rolled over |
| **The comparison** | Outflow against the inflow forecast (#5) on the same dates; against the same fortnight last month; and the must-pay/can-slip split against what we actually slipped last time. |
| **Exception lead** | **The date**, in words, at the top: "Cash falls short on 27 August, by TZS 128M." Not the total due. |
| **Consolidation level** | Group cash view, but honest about company boundaries — cash in one company cannot silently fund another. |
| **Cadence** | Twice weekly; daily during a squeeze. |
| **The decision it triggers** | The pre-emptive act while there is still time: pull a collection, delay a can-slip bill, draw the overdraft, take a supplier's early-settlement offer or decline it (CFO, Owner sanctions the overdraft). |
| **Tap-through** | The must-pay list with the reason each is must-pay. **Refuses** to show the whole payables ledger; only the next 14 days exist on this screen. |
| **Alert condition** | Push when a projected shortfall appears inside 10 working days, or when a must-pay item cannot be covered on its date. |
| **Data needed** | Bills with due dates, current bank/cash balances across all accounts, expected receipts (#5), payroll and statutory calendar (VAT, PAYE, NSSF), ⚠ **a must-pay / can-slip classification per supplier or bill** — a business judgement nobody records, and the single thing that makes this screen decisive, ⚠ facility headroom (overdraft limits). |
| **Novelty** | CLASSIC in principle — **the must-pay/can-slip split and the named shortfall date are the executive upgrade.** |

---

## 18. Discounts We Lost

| field | content |
|---|---|
| **Screen name** | `Discounts We Lost` (17) |
| **Full name** | Early-Payment Discounts We Failed to Take — and what is still catchable |
| **Archetype** | 6 · Exception Register |
| **The question it answers** | "How much free money am I throwing away by paying late — and what can I still grab this week?" |
| **Key figures** | 1) Lost last month TZS 14.6M · 2) **Still catchable this week TZS 6.2M across 9 bills** · 3) Lost year-to-date TZS 96M · 4) The cost of *not* taking them expressed as an interest rate: 2% in 10 days = 36% a year — far above our borrowing cost of 18% · 5) Why they were missed: 61% approval delay, 24% cash timing, 15% bill received late · 6) Worst repeat offender: one supplier, missed 5 months running |
| **The comparison** | The implied annual rate of each discount against our actual cost of borrowing — that comparison is what turns a rounding error into a decision. Plus lost value this month vs last. |
| **Exception lead** | **Still catchable**, first and largest — a report about lost money that does not lead with recoverable money has failed. Each catchable row shows the deadline date. |
| **Consolidation level** | Group; by company for the payment run, and by approver (because #25's answer usually lives here). |
| **Cadence** | Weekly — timed to the payment run, because the value expires. |
| **The decision it triggers** | Pay these nine bills today (CFO); raise the auto-approve threshold for bills under a limit, since approval delay is costing more than the bills are worth (Owner); renegotiate terms with the supplier we always miss (GM). |
| **Tap-through** | The catchable list with deadline and discount value, one tap to send to the payment run. **Refuses** to show discounts already lost older than 90 days — that is history, not a decision. |
| **Alert condition** | Push every Monday when catchable discount value exceeds TZS 2M, and immediately when a single discount over TZS 1M expires in 48 hours. |
| **Data needed** | ⚠ **Discount terms per supplier or per bill (e.g. 2/10 net 30) — very rarely captured in an ERP; usually it lives in an email.** The flagship build item on the AP side. Also: bill receipt date vs bill date (to detect late-arriving bills), payment date, approval timestamps, ⚠ our cost of borrowing as a maintained number. |
| **Novelty** | **NOVEL** — and it usually pays for the whole mobile app in its first quarter. |

---

## 19. Money Committed

| field | content |
|---|---|
| **Screen name** | `Money Committed` (15) |
| **Full name** | Where the Committed Money Is — ordered, received, billed, unpaid |
| **Archetype** | 3 · Position & Movement |
| **The question it answers** | "Forget what I owe on paper — how much have I already committed to spend that has not hit my accounts yet?" |
| **Key figures** | The commitment ladder: 1) Approved orders not yet delivered TZS 890M · 2) Delivered, not yet billed TZS 214M · 3) Billed, not yet paid TZS 1.31Bn · 4) **True obligation TZS 2.41Bn — 84% more than the payables ledger shows** · 5) Of the undelivered orders, TZS 340M is already past its promised date (#21) · 6) Movement this month: new commitments +1.1Bn, converted to bills −960M, cancelled −40M |
| **The comparison** | True obligation against the ledger figure (the gap *is* the insight), against the same figure last month, and against the cash forecast horizon — commitments landing inside 60 days versus cash expected in 60 days. |
| **Exception lead** | The gap sentence at the top: "The books say we owe 1.31Bn. We have actually committed 2.41Bn." |
| **Consolidation level** | Group and company; by branch and by factory for who committed it. |
| **Cadence** | Weekly; essential before any large capital or stock decision. |
| **The decision it triggers** | Freeze or release new ordering (Owner); cancel undelivered orders that are no longer needed before they arrive and become stock (GM); size the real funding requirement, not the ledger one (CFO). |
| **Tap-through** | Any rung of the ladder → its top suppliers and largest orders. **Refuses** to show individual PO lines — this is a size-of-obligation screen. |
| **Alert condition** | Push when total committed obligation grows more than 20% in a month, or when commitments falling inside 60 days exceed forecast cash for the same period. |
| **Data needed** | Approved purchase orders with values and expected delivery dates, goods received not invoiced, open bills, cancellations. ⚠ **Commitment accounting is often absent entirely** — approved-but-undelivered orders are usually invisible to finance. ⚠ Needs order-to-receipt-to-bill linkage to avoid double counting the same money on two rungs. |
| **Novelty** | **NOVEL** — most owners have never seen their true forward obligation on one screen, and the ledger number they *do* see routinely understates it by half. |

---

## 20. Payments to Approve

| field | content |
|---|---|
| **Screen name** | `Payments to Approve` (19) |
| **Full name** | Payments Waiting on You — value, discount at stake, supply at risk |
| **Archetype** | 14 · Decision Docket |
| **The question it answers** | "What payment is sitting on me, and what does each day of delay cost?" |
| **Key figures** | 1) 14 items · 2) TZS 512M · 3) Oldest 9 days · 4) TZS 3.1M of early-payment discount expires within 3 days · 5) 2 items are with suppliers who have threatened to stop supply · 6) 4 items are above the last price we paid (flagged, with the variance) |
| **The comparison** | Each item against policy: within budget / on contract / at or above the last price paid / supplier already overdue. Queue age against your own median. |
| **Exception lead** | Ranked by **consequence of delay** — expiring discount and supply-at-risk first, largest value third. Arrival order is never used. |
| **Consolidation level** | Personal, across companies and branches the approver covers. |
| **Cadence** | Daily. Home-screen tile. |
| **The decision it triggers** | The approval itself (Owner / CFO by threshold). Over time, a delegation decision: bills under TZS 5M approved unchanged 30 times running should not be reaching the Owner. |
| **Tap-through** | Approve / reject / send back with reason, plus **the deciding fact**: last price paid for the same item, the supplier's current balance, and remaining budget for the line. **Refuses** to show the full bill image by default — one fact, one decision. |
| **Alert condition** | Push when an item enters with a discount expiring inside 72 hours, or when anything over TZS 50M has waited 48 hours. |
| **Data needed** | Approval queue with value, requester, supplier, budget line; last price paid per item; supplier balance and overdue status; discount deadline (#18); ⚠ **supply-at-risk flag** (a supplier who has warned us) — not modelled anywhere; ⚠ approver decision-time history. |
| **Novelty** | CLASSIC as a queue — **NOVEL** in that it prices delay in shillings on every row. |

---

## 21. Overdue Orders

| field | content |
|---|---|
| **Screen name** | `Overdue Orders` (14) |
| **Full name** | Orders the Supplier Has Not Delivered — value, days late, what it blocks |
| **Archetype** | 6 · Exception Register |
| **The question it answers** | "What have I ordered and paid attention to, that simply has not arrived — and what is it holding up?" |
| **Key figures** | 1) 34 orders past their promised date · 2) TZS 340M of committed value · 3) Oldest 61 days late · 4) TZS 78M of it was **prepaid or deposited** — our cash is with them · 5) 6 orders are blocking a factory run or a customer order, with the downstream sales value TZS 210M · 6) Worst supplier: 9 late orders, average 24 days late |
| **The comparison** | Each order against the supplier's **own promised date** (not our request date — the distinction decides whose fault it is), and each supplier's lateness against their own 12-month record. |
| **Exception lead** | The orders blocking downstream revenue, with the sales value at risk printed — that reframes a procurement nuisance as a revenue problem. |
| **Consolidation level** | Group; by company, branch and factory. |
| **Cadence** | Weekly. |
| **The decision it triggers** | Chase, cancel and re-source, or claim back a deposit (GM); stop prepaying a chronically late supplier (CFO); escalate to the supplier's owner (Owner). |
| **Tap-through** | The supplier's late orders and their delivery record. **Refuses** to show order lines and specifications — that is the buyer's screen, not the owner's. |
| **Alert condition** | Push when prepaid-and-undelivered value exceeds TZS 50M, or when a blocking order passes 14 days late. |
| **Data needed** | Purchase orders with **supplier-confirmed promised delivery date**, receipts against orders, prepayments and deposits per order. ⚠ **The supplier's promised date is usually not stored separately from our requested date** — without it the report cannot assign fault. ⚠ **The link from an order to what it blocks** (a production order or a customer order) is a genuine build item, and it is what makes this executive. |
| **Novelty** | **NOVEL** — prepaid-and-undelivered plus downstream revenue at risk are two things owners ask about constantly and almost never see reported. |

---

## 22. Goods Not Billed

| field | content |
|---|---|
| **Screen name** | `Goods Not Billed` (16) |
| **Full name** | Do the Goods We Received Match the Bills We Have? |
| **Archetype** | 13 · Reconciliation & Assurance |
| **The question it answers** | "Have I received things I have not been charged for — and are the charges I have received actually right?" |
| **Key figures** | 1) Verdict: "Out by TZS 214M across 87 receipts" · 2) Received not billed TZS 214M, of which **TZS 61M is older than 60 days** — likely a hidden liability or a lost bill · 3) Billed not received TZS 34M — we are being charged for goods that never came · 4) Price mismatches: 19 bills, TZS 12M above the ordered price · 5) Quantity mismatches: 11 bills, TZS 4M · 6) Unexplained (neither timing nor known dispute) TZS 9M |
| **The comparison** | The two sides side by side, and — more important — **how long each difference has persisted**. A 5-day gap is timing; a 90-day gap is a missing liability or a control failure. Grouped by reason: timing / in transit / price / quantity / unexplained. |
| **Exception lead** | The **unexplained** portion, isolated and named. Not the total difference — total difference is mostly legitimate timing and hides the theft-shaped part. |
| **Consolidation level** | Company (this drives the accounts); with branch and warehouse detail for who owns the clearing. |
| **Cadence** | Weekly for the CFO, month-end mandatory before close. |
| **The decision it triggers** | Stop trusting the reported cost of sales and the payables figure until this clears (CFO); assign clearing with a name and a date; investigate a warehouse where receipts persistently go unbilled (Owner — that pattern is how goods leave a building). |
| **Tap-through** | The unmatched items and who owns clearing each. **Refuses** to auto-clear anything, and refuses a tolerance so wide the screen is always green. |
| **Alert condition** | Push at month-end when unexplained difference exceeds TZS 10M, or when any receipt passes 60 days unbilled. |
| **Data needed** | Goods received records with values, supplier bills matched to receipts and orders, ordered price vs billed price, dispute flags. ⚠ **A second reconciliation source — the supplier's own statement of account — should feed this screen**; supplier statements are almost never captured in an ERP, and they are the only way to find a liability we have no paperwork for at all. Flag as a build item: a monthly statement capture and match. ⚠ Reason codes for differences must be defined, or "unexplained" swallows everything. |
| **Novelty** | CLASSIC in accounting — executive framing (unexplained isolated, ageing of the difference, and the warehouse-pattern read) is the upgrade. |

---

## 23. Supplier Dependence

| field | content |
|---|---|
| **Screen name** | `Supplier Dependence` (19) |
| **Full name** | How Much Rides on One Supplier — spend share, cover and switching time |
| **Archetype** | 10 · Concentration & Exposure |
| **The question it answers** | "If one supplier stopped tomorrow, what stops with them — and how long would it take me to replace them?" |
| **Key figures** | 1) Largest supplier 61% of purchases (TZS 4.2Bn a year) · 2) Top 5 = 88% cumulative · 3) Items single-sourced from them: 41 SKUs, carrying TZS 2.1Bn of annual sales · 4) Switching lead time 90 days; stock cover on those items 34 days — **a 56-day hole** · 5) Prepayments and deposits held by them TZS 78M · 6) Same share a year ago: 52% — dependence is deepening |
| **The comparison** | Share this year vs last year, against a board tolerance line (no supplier above 40% of spend); and cover (days of stock) against switching time — that pair is the whole risk. |
| **Exception lead** | The gap between switching time and stock cover, in days, stated as a sentence: "If they stop, we run dry 56 days before a replacement can deliver." |
| **Consolidation level** | Group — supply risk does not respect legal entities. Also by category (a 90% share of packaging matters differently from 90% of stationery). |
| **Cadence** | Quarterly; and before any large single-supplier contract. |
| **The decision it triggers** | Qualify a second supplier, hold strategic stock on the exposed SKUs, negotiate a notice period into the contract, or accept the risk explicitly and write it down (Owner, with GM). |
| **Tap-through** | The supplier: spend history, the single-sourced items, contract notice period, prepayments at risk. **Refuses** to rank suppliers on price — that is a procurement report and it would turn this into a negotiation tool rather than a risk one. |
| **Alert condition** | Push when any supplier passes 50% of category spend, or when stock cover on a single-sourced item falls below its switching lead time. |
| **Data needed** | Spend by supplier (rolling 12 months), ⚠ **single-source flag per item and alternative-supplier register — almost never maintained**, ⚠ **switching lead time per item**, ⚠ **contract notice periods**, stock cover per item, prepayments held. |
| **Novelty** | CLASSIC as a concentration report — **NOVEL** in pairing switching time against stock cover to produce a number of exposed days. |

---
---

# SECTION C — BOTH SIDES

---

## 24. Money Tied Up

| field | content |
|---|---|
| **Screen name** | `Money Tied Up` (13) |
| **Full name** | How Long Our Money Is Tied Up — stage by stage |
| **Archetype** | 12 · Cycle-Time & Flow |
| **The question it answers** | "From the day I pay a supplier to the day the customer pays me — how many days is my money out of my hands, and where does it get stuck?" |
| **Key figures** | One segmented bar: 1) Days stock sits before sale 62 · 2) Days from sale to cash 54 · 3) *Less* days we take to pay suppliers 38 · 4) **Money tied up: 78 days — up from 61 a year ago** · 5) What each day costs: TZS 9.4M of working capital per day, so the 17-day slide has locked up TZS 160M · 6) The stage exceeding its standard, named: "Stock is the stall — 62 days against a 45-day standard" |
| **The comparison** | Against the same measure a year ago and a quarter ago; each stage against its agreed standard; and the group figure against each company. |
| **Exception lead** | The worst stage, named in words with its overshoot — plus the shilling cost of the overall slide, which is what makes it land. |
| **Consolidation level** | Group and per company; per branch where a branch holds its own stock and credit book. Manufacturing gets its own version with a work-in-progress stage inserted. |
| **Cadence** | Monthly; a standing board number. |
| **The decision it triggers** | Attack the one stage: cut slow stock, shorten credit terms, or extend supplier terms — and know which one before spending management effort (Owner + CFO). |
| **Tap-through** | The worst stage's own report — #1 for debtor days, the stock ageing report for stock days, #16 for supplier days. **Refuses** to average away the tail: the 90th percentile is shown alongside the median, because the pathological cases are where the money actually sits. |
| **Alert condition** | Push when the cycle lengthens by more than 10 days versus the same quarter last year. |
| **Data needed** | Stock value and cost of sales, receivables and credit sales, payables and purchases — monthly for 24 months. ⚠ **Agreed standards per stage** (what "good" is for this business) must be set once. ⚠ **Cost of working capital per day** requires a maintained borrowing rate. ⚠ For the manufacturing arm, work-in-progress duration is a separate stage and is often not timestamped. |
| **Novelty** | **NOVEL** in this form — the cash conversion cycle exists in textbooks and almost never on a screen an owner reads, and the "TZS per day tied up" translation is what converts it from an accounting ratio into a decision. |

---

## 25. What Waiting Cost Us

| field | content |
|---|---|
| **Screen name** | `What Waiting Cost Us` (20) |
| **Full name** | What Our Own Delays Cost Last Month — approvals, in shillings |
| **Archetype** | 14 · Decision Docket (retrospective instance) |
| **The question it answers** | "How much money did we lose last month simply because a decision sat waiting on one of us?" |
| **Key figures** | 1) Cost of delay TZS 21.4M · 2) Made up of: early-payment discounts expired TZS 9.1M, held customer orders that went elsewhere TZS 7.8M, express freight to recover a late order TZS 3.2M, penalty interest TZS 1.3M · 3) Median approval time 2.9 days against a 1-day standard · 4) Where the time went, by approver role — Owner 41%, CFO 33%, GM 26% · 5) Items approved unchanged: 87% (candidates for delegation) · 6) The single most expensive delay: TZS 4.1M on one bill |
| **The comparison** | This month's cost of delay against last month's; each approver's median against the standard; the unchanged-approval rate against the delegation threshold policy. |
| **Exception lead** | The largest single avoidable loss, named with its item and its days — and the approver role, never the individual's name on the tile (R7). |
| **Consolidation level** | Group; by approver role and by decision type. |
| **Cadence** | Monthly — a governance read, opened deliberately rather than glanced at. |
| **The decision it triggers** | Raise a delegation limit so routine items never reach the Owner; add a second approver for cover during travel; set an approval service standard and hold to it (Owner — this is the one report whose subject is the Owner). |
| **Tap-through** | The delayed items with their cost and days. **Refuses** to rank individuals publicly; the roll-up is by role, and the individual view is available only to the Owner. |
| **Alert condition** | No push. Monthly, deliberate. |
| **Data needed** | Approval timestamps (requested, decided) per item and approver; ⚠ **the consequence of each delay, priced** — expired discount value (#18), lost order value, express freight cost, penalty interest — none of which an ERP links to an approval today. This is the hardest build item in the set and the most quietly transformative: it makes the approval queue measure itself. |
| **Novelty** | **NOVEL** — the archetype exists in the doctrine, but virtually no business measures the cost of its own executives' latency. |

---
---

## Build items this catalogue creates

Everything flagged ⚠ above, consolidated and ranked by how many reports it unlocks.

| Build item | Unlocks | Note |
|---|---|---|
| **Promise-to-pay log** (date, amount, who promised, who took it, auto-matched to receipts) | #4, #5, #6, #10, #14 | Highest leverage single item in the AR half. |
| **Early-payment discount terms per supplier/bill** (e.g. 2/10 net 30) | #17, #18, #20, #25 | Usually lives in email. Pays for itself fastest. |
| **Dispute record on AR and AP** (reason code, value withheld, owner, opened/resolved, outcome) | #1, #6, #13, #16, #22 | Also cleans the ageing pyramids, which are lying today. |
| **Targets and plans, dated and versioned** — collection plan by week, the six credit targets, stage standards | #5, #6, #8, #24 | Without targets, half the suite degrades into position statements. |
| **Related-party / customer-group links** | #1, #2, #9 | Concentration is invisible without it. |
| **Commitment accounting** — approved orders not delivered, linked order→receipt→bill | #17, #19, #21 | Makes true obligation visible for the first time. |
| **Supplier promised delivery date** (distinct from our requested date) | #21, #23 | Decides whose fault lateness is. |
| **Supplier criticality: single-source flag, alternative supplier, switching lead time, contract notice** | #16, #21, #23 | Turns AP ageing from clerical into strategic. |
| **Must-pay / can-slip classification** | #17, #20 | A business judgement, recorded once per supplier. |
| **Approval timestamps + priced consequence of delay + blocking-despatch flag** | #7, #20, #25 | Makes the docket measure itself. |
| **Credit-screening record at onboarding** (checks done, limit granted, authority) | #2, #15 | The causal variable behind bad cohorts. |
| **Security/guarantees held, credit insurance cover** | #9, #14 | Nowhere in a typical ERP. |
| **Supplier statement capture and match** | #22 | The only route to finding an unrecorded liability. |
| **Policy/event log** (terms changes, branch openings, credit-policy edits) | #11, #24 | Small item, large interpretive payoff on every trend. |
| **Maintained cost-of-borrowing rate** | #18, #24 | One number, entered quarterly. |
| **Forecast-run history** (so forecasts can report their own accuracy) | #5, #14 | Cheap, and it is what makes forecasts believed. |

## Naming notes for review

- **Twin test (R9):** `Debtor Ageing` / `Book Movement` were separated deliberately — an earlier draft had both leading with "Debtor" and hiding an Ageing-vs-Position difference. `Credit to Approve` / `Payments to Approve` share a trailing phrase but differ on the leading word, which is the correct way round.
- **R8 corrections applied:** `Due in 14 Days` → `Bills Falling Due`; the horizon lives in the subtitle, so the name survives a policy change from 14 days to 21.
- **R12 (Swahili) corrections applied:** `Cash Runway` → `Cash Due In` (*fedha zinazotarajiwa*); `Bad Debt Leakage` → `Debt at Risk`; `Working Capital Cycle` (noun-stack) → `Money Tied Up` (*fedha zetu zimekaa muda gani*); `Payment Behaviour Deterioration` → `Slipping Payers`; `Cash Application Exceptions` folded into #12 rather than named.
- **R7 applied:** `Collector League`, not `Worst Collectors`; `What Waiting Cost Us` reports by role, not by name, on the face of the screen.

===== DOMAIN: stock — STOCK & WORKING CAPITAL IN GOODS =====
# Stock & Working Capital in Goods — Executive Report Suite

**OrbixERP Executive Mobile · Domain 5 of the catalogue · v1 · 2026-08-18**
25 reports. All names run through the R1–R13 checklist. Archetype in every case is exactly one.

**The domain's governing sentence:** *the owner does not want to know how much stock there is — he wants to know how much of his money is asleep in it, which part will never wake up, and what the sleeping money cost him this month.*

**Tier index**

| Tier | Reports | Opened |
|---|---|---|
| **TIER 1** | 1–10 | weekly or more |
| **TIER 2** | 11–19 | monthly or on alert |
| **TIER 3** | 20–25 | specialist / on demand |

**Novelty count:** 10 NOVEL of 25 — reports 4, 10, 15, 17, 18, 19, 20, 21, 24 and (partly) 12.

---

# TIER 1 — the weekly spine

---

## 1. Money in Stock

| field | content |
|---|---|
| **Screen name** | `Money in Stock` |
| **Full name** | Where the Stock Money Is — value by branch and category, and how it moved |
| **Archetype** | 3 · Position & Movement |
| **The question it answers** | "How much of my money is sitting in goods right now, and what put it there this month?" |
| **Key figures** | 1) **Stock at cost, group** (TZS, one big number); 2) **Change this month** (TZS ± and %); 3) the movement waterfall in six business bars — **Bought in**, **Made in factory**, **Sold out (at cost)**, **Transferred (net, in transit)**, **Shrinkage & write-off**, **Revaluation**; 4) **Closing** ; 5) **Unexplained residual** (must be < 2% of movement, printed even when zero) |
| **The comparison** | Opening balance of the period, the same position one month ago and 12 months ago, and the board's stock-value ceiling drawn as a line across the headline |
| **Exception lead** | If any single movement bar is more than 1.5× its 6-month normal, that bar is pulled to the top of the body with a one-line plain reason ("Bought in TZS 812M — 2.1× normal, 61% of it is one factory raw-material build"). If the residual exceeds 2%, that replaces the headline: *"TZS 44M of the movement is unexplained."* |
| **Consolidation level** | Group headline; rolls down company → branch → location. Must roll up: branch figures sum to group with in-transit shown separately so it never double-counts. |
| **Cadence** | Glance weekly; read properly at month-end |
| **The decision it triggers** | Owner / CFO: stop or release the next purchase wave; approve a deliberate build ("this is the Christmas run, leave it"); demand the residual be cleared before the month is signed |
| **Tap-through** | One drill: tap a movement bar → its **top 10 contributing categories** (not items, not documents). It deliberately refuses to show invoice or GRN lines — that is what the desktop ERP is for. |
| **Alert condition** | Push when group stock value crosses the board ceiling, or when it moves more than 12% in a rolling 30 days without an approved build reason attached |
| **Data needed** | Stock quantity × carrying cost by branch/location/category; purchase receipts, production receipts, cost of goods sold, inter-branch transfers, adjustments and write-offs, revaluation entries, all classified into the six **business** movement classes. ⚠ A **stock-value ceiling / stock budget by company** (boards set one verbally, ERPs rarely store it). ⚠ A **"planned build" flag** on a purchase or production campaign so a deliberate build reads as planned rather than as a breach. |
| **Novelty** | CLASSIC — but the residual bar and the planned-build flag are what most ERPs miss |

---

## 2. Dead Stock

| field | content |
|---|---|
| **Screen name** | `Dead Stock` |
| **Full name** | Stock That Isn't Moving — value, age and where it sits |
| **Archetype** | 6 · Exception Register |
| **The question it answers** | "How much of my money is in goods that nobody has bought for months, and where is it?" |
| **Key figures** | 1) **Money not moving** (TZS) and **share of total stock** (%); 2) **Lines breaching** (count); 3) split **New this month / Still dead / Cleared** (three numbers, TZS); 4) top 7 lines: item, branch, value, **days since last sale**, **months of cover at current rate**; 5) **Recoverable if discounted 30%** (TZS estimate) |
| **The comparison** | The same three numbers 30 days ago, and the repeat share — how much of today's dead money was also dead last month. Chronic dead money is a buying failure; new dead money is a demand event. |
| **Exception lead** | Ranked by value at risk, not by age. The single largest chronic line leads: *"TZS 61M · Ceramic tiles 60×60 · Mikocheni · no sale in 214 days · dead in all 4 of the last 4 months."* |
| **Consolidation level** | Group total, drillable to branch; same item dead in one branch and live in another is flagged (links to report 10) |
| **Cadence** | Glance weekly; act monthly |
| **The decision it triggers** | GM / Branch Manager: clear it — discount, bundle, transfer to the branch that sells it, return to supplier, or write it down. Owner: approve the write-down and stop re-ordering the line. |
| **Tap-through** | One drill: the line's **12-month sale-and-receipt history as two sparklines** plus the last purchase date and buyer. Refuses to show the individual stock movements. |
| **Alert condition** | Push when dead money crosses 12% of total stock value, or when a single line above TZS 25M becomes newly dead |
| **Data needed** | Last sale date per item per branch, on-hand quantity and value, receipt dates, sales velocity. ⚠ **Per-category "dead" thresholds** (90 days for fast-moving groceries, 365 for spares — a single global threshold makes the register useless). ⚠ **An "accept / known exception" state with an expiry date** so approved strategic stock stops clogging the list. ⚠ Estimated **recovery value at discount** (needs a markdown policy or a historical clearance-realisation rate). |
| **Novelty** | CLASSIC — but per-category thresholds and an accept-with-expiry state are what stop it dying in a fortnight |

---

## 3. Stock Cover

| field | content |
|---|---|
| **Screen name** | `Stock Cover` |
| **Full name** | Stock Against Cover Policy — days of cover by category, worst misses first |
| **Archetype** | 2 · Scorecard |
| **The question it answers** | "Are we holding the amount of stock we agreed to hold — not more, not less?" |
| **Key figures** | Verdict: **"5 of 9 categories within policy"**; then per category: **days of cover (actual)**, **policy band (min–max)**, **gap in days**, **money above or below band (TZS)**. Foot of screen: **Total money above band** and **Total money below band** — the two numbers that price the whole screen. |
| **The comparison** | The printed policy band itself, per category — the standard is external to the number, which is what makes this a Scorecard and not a position |
| **Exception lead** | Ordered by money at stake, misses first. Overstock and understock are shown in the same list with opposite-direction bars, so a category can never hide in the middle. |
| **Consolidation level** | Company level by default (policy is set per company); branch view for a branch manager. Rolls up by weighting on value, never on average days. |
| **Cadence** | Weekly |
| **The decision it triggers** | GM / CFO: cut the next order for over-cover categories, expedite the under-cover ones. Owner: change the policy itself when the same category misses four months running — the plan may be wrong, not the buyer. |
| **Tap-through** | One drill: a red row opens **report 7, the stock variance bridge**, for that category. This pairing is the backbone. |
| **Alert condition** | Push when total money above band exceeds TZS 300M, or when any A-class category falls below its minimum band |
| **Data needed** | On-hand value and quantity by category/branch, recent sales rate (a defensible one — 8-week weighted, not last month). ⚠ **A cover policy: minimum and maximum days per category per branch.** Almost no ERP holds this; it lives in the GM's head and it is the single highest-value build item in this domain. ⚠ **Seasonality profile per category**, so December cover targets are not judged on an August rate. |
| **Novelty** | CLASSIC in concept, absent in practice — most ERPs report cover with nothing to compare it to |

---

## 4. Empty Shelves

| field | content |
|---|---|
| **Screen name** | `Empty Shelves` |
| **Full name** | What the Empty Shelf Cost — lines out of stock while customers were asking |
| **Archetype** | 6 · Exception Register |
| **The question it answers** | "What did I fail to sell this week because the goods were not there?" |
| **Key figures** | 1) **Sales lost (TZS estimate)** — the headline, in money not in incidents; 2) **Lines out of stock** (count) and **shelf-hours lost**; 3) **Share of the assortment unavailable** (%); 4) top 7: item, branch, hours out, estimated lost sales, **estimated lost margin**; 5) **Repeat offenders** — lines out three weeks running (count) |
| **The comparison** | Same week last month, and the branch's own normal out-of-stock rate; each line is compared to its own recent daily sale rate, which is how the lost value is estimated |
| **Exception lead** | The most expensive absence, not the longest. *"TZS 18.4M · Cooking oil 20L · Kariakoo · out 31 hours · third week running."* Repeats are called out on the face of the screen because a repeat is a replenishment-process failure, not bad luck. |
| **Consolidation level** | Branch-native, rolls to company and group; group headline is the total lost money |
| **Cadence** | Glance daily in retail, read weekly |
| **The decision it triggers** | Branch Manager: fix replenishment for named lines today. GM: change the reorder point or supplier for chronic repeats. Owner: sees the true cost of the "we are keeping stock tight" instruction he gave. |
| **Tap-through** | One drill: the item's **availability timeline for the last 14 days** (in stock / out, against its daily sale rate). Refuses to show the customers who asked — that is a CRM matter, not an executive one. |
| **Alert condition** | Push when estimated lost sales in a day exceed TZS 5M at any branch, or when any A-class line is out for more than 6 trading hours |
| **Data needed** | On-hand quantity **over time** (not just now) per item per branch, sale rate per item per branch, assortment list per branch. ⚠ **A stock-position history / availability log** — ERPs hold current on-hand and movements but rarely a queryable "was this zero at 14:00 on Tuesday" series; this must be built (a nightly or hourly availability snapshot). ⚠ **The intended assortment per branch (planogram / listed lines)** — without it you cannot tell "out of stock" from "we never sell that here". ⚠ Ideally **recorded demand while out** (POS "customer asked, we had none" capture, or substitute-sale detection) to convert an estimate into a fact. |
| **Novelty** | **NOVEL** — almost no ERP prices the sale that never happened. It is the number that changes how an owner thinks about "lean stock". |

---

## 5. Runs Out First

| field | content |
|---|---|
| **Screen name** | `Runs Out First` |
| **Full name** | What Runs Out First — lines that go empty before the next delivery lands |
| **Archetype** | 9 · Forecast & Runway |
| **The question it answers** | "Which goods will I be out of before the replacement arrives, and what will that cost me?" |
| **Key figures** | 1) **The date and the count** — *"7 lines go empty inside their lead time; the first on 26 August (6 trading days)"*; 2) **Sales at risk in the next 30 days (TZS)**; 3) **Margin at risk (TZS)**; 4) per line: item, branch, **days of stock left**, **supplier lead time**, **days short**, **value at risk**; 5) **Forecast reliability** — how accurate last month's stock-out predictions were (%) |
| **The comparison** | Each line's remaining cover against **its own supplier lead time** — the comparison *is* the report; plus this week's at-risk value against last week's (is the exposure growing?) |
| **Exception lead** | Ranked by money at risk, not by date. A cheap line running out in two days matters less than a flagship running out in nine with a 21-day lead time. |
| **Consolidation level** | Branch-native; group view ranks across all branches and merges lines where a transfer could solve it (hands off to report 10) |
| **Cadence** | Glance daily in a trading business; weekly minimum |
| **The decision it triggers** | GM / Branch Manager: raise the purchase order today, air-freight, split the order, or pull stock from a sister branch. Owner: authorise the premium cost of expediting when the margin at risk justifies it. |
| **Tap-through** | One drill: the line's **projected quantity curve** to zero with the open purchase orders marked on it. Refuses to show the full order book. |
| **Alert condition** | Push when margin at risk exceeds TZS 30M, or when any line in the top-50 by margin will go empty inside 5 trading days with no open order |
| **Data needed** | On-hand by item/branch, sales rate with recent trend, open purchase and production orders with expected dates, transfer pipeline. ⚠ **Actual supplier lead times measured from history** — ERPs store a static lead-time field that nobody updates; the real one must be computed from order-to-receipt (see report 22). ⚠ **Promotion and seasonality calendar** — a forecast that ignores next week's promotion is wrong in exactly the week it matters. ⚠ **Forecast-accuracy tracking of this report's own past runs.** |
| **Novelty** | CLASSIC — but self-reported accuracy and money-ranking (not date-ranking) are the executive upgrades |

---

## 6. Branch Stock League

| field | content |
|---|---|
| **Screen name** | `Branch Stock League` |
| **Full name** | Branch Stock League — cash returned per shilling of stock, ranked |
| **Archetype** | 5 · League Table |
| **The question it answers** | "Which branch makes my stock money work, and which one is sitting on it?" |
| **Key figures** | The spread first: **"Best returns TZS 3.40 of margin per shilling of stock a year; worst returns TZS 0.90; group 2.10"**; per branch: **margin per shilling of stock (GMROI)**, **stock turns**, **stock value (TZS)**, **dead share (%)**, **rank change since last month** |
| **The comparison** | The group rate printed as a line every branch is measured against, plus each branch's own rank last month — a chronic laggard and a newly collapsing branch need different visits |
| **Exception lead** | The spread, then the **biggest rank fall**, then the bottom three. Top three are shown so a practice can be copied, but they never lead. |
| **Consolidation level** | Branch, within a company; group view groups branches by comparable format (mall counter / depot / factory store) — ranking a wholesale depot against a retail counter destroys the table's credibility |
| **Cadence** | Weekly glance, monthly decision |
| **The decision it triggers** | Owner / GM: where the manager visit goes this week; which branch's excess stock gets redistributed; which branch manager's stock authority is tightened |
| **Tap-through** | One drill: a row opens **that branch's Stock Cover scorecard (report 3)**. Refuses to show individual items on the league screen. |
| **Alert condition** | No push. This is a rhythm report, not an event report — pushing a league table weekly makes it political. |
| **Data needed** | Margin and cost of sales by branch, average stock value by branch (average, not closing — a closing-balance GMROI is gameable by shipping stock out on the last day), dead-stock value by branch. ⚠ **Branch format / comparability grouping** and ⚠ **branch age** (branches under 90 days must be excluded or the league is nonsense). ⚠ Whether **central and warehouse stock is allocated to branches or held separately** — state it on the trust line or the table gets argued with. |
| **Novelty** | CLASSIC in retail, rare in ERPs — most rank branches on sales, which just tells you which branch is biggest |

---

## 7. Stock Gap

| field | content |
|---|---|
| **Screen name** | `Stock Gap` |
| **Full name** | Why the Stock Is Heavier Than Planned — the gap against plan, by cause |
| **Archetype** | 4 · Variance Bridge |
| **The question it answers** | "We planned to hold TZS 2.1Bn of stock and we are holding 2.6Bn — where did the extra half a billion come from?" |
| **Key figures** | Headline is the **gap**: *"TZS 486M above plan"*. Then the waterfall of causes, largest first, capped at six plus a small residual: **Sales below plan** (stock that did not leave), **Over-ordering against the plan**, **Early deliveries**, **Factory over-production**, **Purchase cost inflation** (same units, more money), **Slow-moving build-up**, **Residual**. Foot: **cost of that gap per month** (links to report 19). |
| **The comparison** | Built into the form: plan → actual. Plus last month's version of the same bridge, so a persistent cause is visible as a cause that will not go away. |
| **Exception lead** | The largest cause bar leads, with a named owner attached — a bridge bar without an owner is a fact, not a decision. *"Over-ordering: TZS 214M · Buyer: Central Purchasing."* |
| **Consolidation level** | Company; drillable to branch and to category. Group view sums the bridges only where the plan basis is the same. |
| **Cadence** | Month-end, and on alert when report 3 goes red |
| **The decision it triggers** | CFO / Owner: freeze ordering in the category driving the biggest bar; change the buyer's authority; correct the plan if the sales-shortfall bar dominates (the stock is not the problem, the sales plan was). |
| **Tap-through** | One drill: a cause bar opens the **league of branches or categories that caused it**. Refuses to show documents. |
| **Alert condition** | Push when the gap exceeds 20% of plan, or when the residual bar exceeds 15% of the gap (the bridge has stopped explaining) |
| **Data needed** | A stock plan/budget by company and category by month; purchases, production, sales at cost, price movements on purchases. ⚠ **A stock plan at all** — most ERPs budget P&L and not the balance sheet; this is a real build item. ⚠ **Purchase price history per item** to separate "we bought more" from "it cost more" — the two look identical in a value figure and demand opposite actions. |
| **Novelty** | **NOVEL in practice** — decomposing a stock overhang into causes is standard in FMCG planning and near-absent in mid-market ERPs |

---

## 8. Count vs Books

| field | content |
|---|---|
| **Screen name** | `Count vs Books` |
| **Full name** | Does the Stock Match the Count? — book against counted, and what is unexplained |
| **Archetype** | 13 · Reconciliation & Assurance |
| **The question it answers** | "Is the stock the system says I own actually on the floor — and if not, can I still believe any of these other screens?" |
| **Key figures** | Verdict and difference: **"Out by TZS 12.6M across 4 locations"**; then **book value**, **counted value**, **difference (TZS and % of counted value)**; the difference split by reason: **Timing (in transit / not yet posted)**, **Unit or conversion error**, **Miscoded item**, **Unexplained**; and **coverage** — *"63% of stock value counted in the last 90 days"* |
| **The comparison** | The two sides against each other, the difference against the tolerance the board set (e.g. 0.5% of value), and the **age of the difference** — a hole that has persisted three months is a control failure, not a timing gap |
| **Exception lead** | **Unexplained** is isolated and always leads, even when it is smaller than the timing bucket. Timing is accounting; unexplained is theft, error or a broken process, and the two must never be added together. |
| **Consolidation level** | Location and branch; group verdict is the worst location, never the net — offsetting a plus at one branch against a minus at another hides two problems |
| **Cadence** | On count completion; monthly summary; the coverage number glanced weekly |
| **The decision it triggers** | Owner / CFO: sign or refuse the stock figure in the accounts; order a full recount at a named location; open an investigation at a named till/store; withhold a branch manager's stock authority |
| **Tap-through** | One drill: the worst location's **top variance lines by value with the counter's name and date**. Refuses to show every line counted. |
| **Alert condition** | Push when unexplained variance at any location exceeds 1% of that location's value or TZS 5M, whichever is smaller; and push when count coverage falls below 60% of value in 90 days |
| **Data needed** | Book quantity/value at the count moment, counted quantity, adjustment postings, count dates and locations. ⚠ **Reason codes on every adjustment** ("damaged", "expired", "miscount", "theft", "conversion") — without them the whole "by reason" body is a single "unexplained" bar and the report is worthless. ⚠ **Count schedule / cycle-count plan** to compute coverage. ⚠ **Counter identity and re-count evidence** for the tap-through. |
| **Novelty** | CLASSIC — the reason-code split and the isolation of "unexplained" are what make it executive |

---

## 9. Stock Decisions

| field | content |
|---|---|
| **Screen name** | `Stock Decisions` |
| **Full name** | Stock Decisions Waiting on You — value and what each delay costs |
| **Archetype** | 14 · Decision Docket |
| **The question it answers** | "What stock decision is stuck on my desk, and what is it costing me to keep sitting on it?" |
| **Key figures** | **"6 items · TZS 214M · oldest 5 days · 2 blocking despatch"**; per item: what (write-off / markdown / transfer / opening-stock adjustment / over-limit purchase), who asked, **value**, **why it needs you** (over limit / off-contract / above last price paid), **what breaks if it waits** (despatch held, stock ages another week, price offer expires); foot: **your median approval time vs the group's** |
| **The comparison** | Each item against its own policy limit and against the last price/cost on record; the queue against your own median turnaround — the docket must be able to tell the owner that *he* is the bottleneck |
| **Exception lead** | Ranked by consequence of delay, never by arrival order. A TZS 3M markdown that expires today outranks a TZS 90M transfer that can wait. |
| **Consolidation level** | Personal — addressed to the logged-in approver, across every company and branch he covers |
| **Cadence** | Daily glance; it is the app's most-opened screen after Flash |
| **The decision it triggers** | Owner / GM: the approval itself. Over time: a delegation decision — anything approved unchanged 20 times running should have its limit raised. |
| **Tap-through** | Approve / reject / send back with a reason, plus the **one fact needed to decide** (last cost paid, current on-hand and cover, the item's dead-stock status). Refuses to show the full requisition document. |
| **Alert condition** | Push when an item enters the queue that blocks despatch, or when any item ages past 48 hours, or when queue value exceeds TZS 100M |
| **Data needed** | Approval queue with type, value, requester, age, and the policy rule invoked. ⚠ **Cost-of-delay metadata per approval type** ("this blocks a despatch", "this offer expires on X") — ERPs store a queue, not a consequence. ⚠ **Approval-limit matrix by user and type**. ⚠ **Historic approval-turnaround measurement** so the docket can measure itself. |
| **Novelty** | CLASSIC as a queue, **NOVEL as a report** — pricing the delay is what turns an inbox into an executive screen |

---

## 10. Wrong Branch Stock

| field | content |
|---|---|
| **Screen name** | `Wrong Branch Stock` |
| **Full name** | Stock in the Wrong Branch — goods idle here while another branch runs out |
| **Archetype** | 6 · Exception Register |
| **The question it answers** | "Where am I about to buy something I already own — just in the wrong shop?" |
| **Key figures** | 1) **Money that could be moved instead of bought (TZS)** — the headline; 2) **Matched pairs** (count of item × surplus-branch × short-branch); 3) **Sales at risk if not moved (TZS)**; 4) top 7 pairs: item, **surplus branch (units, months of cover)**, **short branch (days left)**, **transferable value**, **transfer cost estimate**; 5) **Open purchase orders that this would make unnecessary (TZS)** |
| **The comparison** | Each branch's cover against its own policy band (report 3) — surplus and shortage are defined against policy, not against each other; plus how many of last month's pairs were actually resolved by a transfer |
| **Exception lead** | The pair with the largest avoidable purchase leads, with the open PO named: *"TZS 41M · Cement 42.5N · 4.1 months cover at Mbezi, 3 days left at Kariakoo — and PO-4471 for 2,000 bags is out to the supplier."* Catching a purchase before it is placed is the whole point. |
| **Consolidation level** | Group-native — this report only exists at group level; it is meaningless inside one branch |
| **Cadence** | Weekly; on alert when a purchase order is raised for an item held in surplus elsewhere |
| **The decision it triggers** | GM: order the transfer and cancel the PO. Owner: change the buying model when the same pairs recur — central buying is over-allocating. |
| **Tap-through** | One drill: the item's **cover by branch as a single bar row** (all branches at once). Refuses to show transfer paperwork. |
| **Alert condition** | Push when a purchase order or requisition is raised for an item with more than 2 months of surplus cover at another branch within transfer range, above TZS 5M |
| **Data needed** | On-hand and cover by item by branch, sale rate by item by branch, open purchase orders and requisitions. ⚠ **Transfer feasibility and cost** — distance/route/lead time between branches and a per-transfer cost, otherwise the report recommends moving a TZS 200k pallet 600 km. ⚠ **Cover policy per branch** (as report 3). ⚠ **A record of whether a recommended transfer actually happened**, so the report can report its own take-up. |
| **Novelty** | **NOVEL** — the classic ERP reports stock by branch and leaves the cross-branch arbitrage entirely to human memory. This is the report that pays for the app. |

---

# TIER 2 — monthly and on-alert

---

## 11. Stock by Age

| field | content |
|---|---|
| **Screen name** | `Stock by Age` |
| **Full name** | Stock by Age — value sitting in each age band, and how the shape is changing |
| **Archetype** | 7 · Ageing Pyramid |
| **The question it answers** | "How old is the money in my store, and how much of it has gone rotten?" |
| **Key figures** | **"TZS 2.41Bn in stock · 27% older than 180 days"**; bands **0–30 / 31–90 / 91–180 / 181–365 / 365+** each with **value**, **share**, **line count**; then the **top 5 items inside the worst band**; and **provision indicated** (TZS, per the write-down policy) |
| **The comparison** | The same pyramid one month and one year ago as a ghost outline behind the current one — whether the stack is fattening at the base or the tip is the entire message |
| **Exception lead** | The oldest band leads, with the money in it, and the change in that band since last month called out in words: *"the 365+ band grew TZS 38M this month."* |
| **Consolidation level** | Group, drillable to branch and category |
| **Cadence** | Month-end |
| **The decision it triggers** | CFO: raise or release the obsolescence provision. GM: a clearance campaign on the worst band. Owner: refuse the next order in categories whose base is fattening. |
| **Tap-through** | One drill: a band opens **its items ranked by value**. Refuses to show batches or receipts. |
| **Alert condition** | Push when the 365+ band grows by more than TZS 25M in a month |
| **Data needed** | Receipt date per unit/batch on hand, and an ageing method that survives partial issues (FIFO-layer ageing, not "last receipt date" — the latter makes an old pile look fresh the moment one carton arrives). ⚠ **Layer-level or batch-level ageing** — many ERPs hold only a moving-average pool with no age at all; this is a genuine build item. ⚠ **A write-down policy by age band** to compute the indicated provision. |
| **Novelty** | CLASSIC — the ghost outline and layer-true ageing are the upgrades |

---

## 12. Expiring Stock

| field | content |
|---|---|
| **Screen name** | `Expiring Stock` |
| **Full name** | Stock That Expires Before It Sells — value, date and what can still be saved |
| **Archetype** | 7 · Ageing Pyramid |
| **The question it answers** | "How much of my stock will be worthless soon, and how much of it can I still shift?" |
| **Key figures** | **"TZS 96M expires within 90 days · TZS 31M of it cannot sell in time at the current rate"** — the second number is the real one; bands **expired now / ≤30 days / 31–90 / 91–180**; per band **value**, **units**, **sellable-in-time value** vs **at-risk value**; top 5 at-risk batches with **branch and expiry date** |
| **The comparison** | Remaining shelf life against **the days of cover at the current sale rate** — an item with 60 days of life and 200 days of cover is already lost; that comparison is what distinguishes this from a plain expiry list |
| **Exception lead** | Largest at-risk value first, not soonest expiry. Already-expired stock still on the books appears as a separate red strip at the very top — it is a posting failure as much as a loss. |
| **Consolidation level** | Branch and location (expiry is physical), rolled to group by value |
| **Cadence** | Weekly in FMCG/pharma; monthly elsewhere |
| **The decision it triggers** | Branch Manager: markdown, bundle, move to a faster branch, return under supplier agreement. CFO: provide for the unsellable portion now rather than discover it at year end. |
| **Tap-through** | One drill: the batch's **branch-by-branch sale rate**, to see where it could still clear. Refuses to show batch movement history. |
| **Alert condition** | Push when at-risk value crosses TZS 20M, or when any single batch above TZS 5M enters the ≤30-day band |
| **Data needed** | Batch/lot expiry dates and quantities on hand by location, sale rate per item per branch. ⚠ **Expiry captured at receipt** — commonly a blank field even when the column exists; enforcement at GRN is the build item. ⚠ **Supplier return-for-expiry terms** to know what is recoverable. ⚠ **Markdown policy** to estimate the salvage value. |
| **Novelty** | CLASSIC as a list; **NOVEL as designed here** — pricing "cannot sell in time" rather than merely listing expiry dates |

---

## 13. Stock Trend

| field | content |
|---|---|
| **Screen name** | `Stock Trend` |
| **Full name** | Which Way the Stock Money Is Going — 24 months, against the normal range |
| **Archetype** | 8 · Trend & Trajectory |
| **The question it answers** | "Is my money in stock creeping up, or is this just a normal month?" |
| **Key figures** | Verdict in words: **"Rising — 5th consecutive month above the normal range"**; **current stock value**; **stock as % of monthly sales at cost** (the ratio that removes growth from the picture); **turns**; **dead share** — each as a 24-point line with a shaded normal band |
| **The comparison** | The rolling normal band (12-month mean ± 1 standard deviation) and the same month last year, because a Ramadan and a Christmas build are not a trend |
| **Exception lead** | The verdict sentence, and the marker for the event that best explains the break (branch opening, factory shutdown, supplier price rise) |
| **Consolidation level** | Group and company; branch on drill |
| **Cadence** | Month-end |
| **The decision it triggers** | Owner: intervene or leave it alone — this is the report that stops a reaction to a random month. A run of consecutive out-of-band months sends him to report 7. |
| **Tap-through** | One drill: the same series **split by category** (small multiples). Refuses to show months in a table. |
| **Alert condition** | Push only on the third consecutive month outside the band — not on any single breach |
| **Data needed** | Monthly closing stock value, cost of sales, turns, dead share, for at least 24 months. ⚠ **Event annotations** (branch openings, shutdowns, policy changes) — nothing in an ERP records them and without them the line is uninterpretable. ⚠ The **current incomplete month must be excluded**, which requires an explicit period-complete flag. |
| **Novelty** | CLASSIC |

---

## 14. Goods in Transit

| field | content |
|---|---|
| **Screen name** | `Goods in Transit` |
| **Full name** | Stock Still on the Road — value in transit, and what is overdue |
| **Archetype** | 7 · Ageing Pyramid |
| **The question it answers** | "How much of my stock is nowhere — sent but not received — and how much of that has been missing too long?" |
| **Key figures** | **"TZS 184M in transit · TZS 41M overdue"**; bands **within expected time / 1–3 days late / 4–7 days late / over 7 days**; per band value and consignment count; top 5 late consignments: from, to, value, days late, despatcher; and **in-transit value as a share of group stock** |
| **The comparison** | Each consignment against **its route's normal transit time** (Dar–Mwanza is not Dar–Mikocheni), and the total against the same figure last month |
| **Exception lead** | Over-7-days first, by value. Anything in transit more than 30 days is presented as a **suspected loss**, not as a transit item — that reclassification is the report's sharpest act. |
| **Consolidation level** | Group only — in-transit is by definition between two branches and belongs to neither |
| **Cadence** | Weekly; daily where van routes run |
| **The decision it triggers** | GM: chase or write off a named consignment; suspend a driver or route. CFO: stop counting suspected losses as stock. |
| **Tap-through** | One drill: the consignment's **despatch/receipt timeline with names**. Refuses to show item lines. |
| **Alert condition** | Push when any consignment above TZS 10M passes 7 days late, or when any consignment passes 30 days |
| **Data needed** | Transfers despatched but not received, with despatch timestamp, route, value, and both branches. ⚠ **Expected transit time per route** — the standard against which "late" is defined; usually nowhere in the system. ⚠ **Despatch and receipt timestamps as separate events** (many ERPs post a transfer instantaneously, which makes in-transit invisible by construction — that is itself the build item). ⚠ **Driver / vehicle / route identity** for accountability. |
| **Novelty** | CLASSIC in concept, frequently impossible in practice because transfers are posted as instantaneous |

---

## 15. Cost Drift

| field | content |
|---|---|
| **Screen name** | `Cost Drift` |
| **Full name** | Does the Cost We Carry Still Hold? — book cost against what these goods are really worth |
| **Archetype** | 13 · Reconciliation & Assurance |
| **The question it answers** | "Is the value on my stock sheet honest, or am I carrying goods at a cost I could never get back?" |
| **Key figures** | Verdict: **"TZS 78M of stock is carried above what it would fetch"**; **book value**, **value at latest purchase cost**, **value at net realisable price** (recent selling price less cost to sell); **drift** in TZS and %; split by reason: **price fell since purchase**, **selling price cut below cost**, **cost never updated (stale)**, **suspect cost (zero, negative, or 10× the peer average)** |
| **The comparison** | Three valuations of the same physical stock side by side, plus the drift a quarter ago — persistent drift is a costing-process failure |
| **Exception lead** | The **suspect-cost** bucket leads whenever it is non-zero, regardless of size — a zero-cost or absurd-cost item corrupts margin on every other report in the suite |
| **Consolidation level** | Company (valuation policy is per company), drillable to category |
| **Cadence** | Month-end, and always before signing accounts |
| **The decision it triggers** | CFO: write down to NRV, or correct a costing error before it reaches the P&L. Owner: refuse to accept a margin number sourced from a stale cost. Auditor-facing. |
| **Tap-through** | One drill: **the 20 lines with the largest drift, with book cost, last purchase cost and last selling price**. Refuses to show the costing transactions. |
| **Alert condition** | Push when suspect-cost lines exceed TZS 5M of value, or when total drift exceeds 3% of stock value |
| **Data needed** | Carrying cost per item, last purchase cost and date, recent selling prices, cost of sale (delivery/commission) for NRV. ⚠ **A defensible net realisable value** — needs recent actual selling price net of discount per item per branch, which exists but is rarely assembled. ⚠ **Cost-record staleness (age of the last cost update)** — not usually stored. ⚠ **Peer-average cost by category** to detect absurd costs. |
| **Novelty** | **NOVEL** — nearly every ERP treats carrying cost as truth. This report treats it as a claim to be checked, and it is the report that makes the whole suite auditable. |

---

## 16. Shrinkage League

| field | content |
|---|---|
| **Screen name** | `Shrinkage League` |
| **Full name** | Shrinkage League — loss per shilling of stock, by branch, ranked |
| **Archetype** | 5 · League Table |
| **The question it answers** | "Where am I losing goods, and is it the same place every time?" |
| **Key figures** | Spread: **"Worst branch loses 1.9% of stock value a quarter; best 0.2%; group 0.7%"**; per branch: **shrinkage value (TZS)**, **shrinkage rate (% of stock value)**, **rate last quarter**, **rank change**, **share unexplained (%)**; foot: **group shrinkage in TZS and as days of profit** |
| **The comparison** | The group rate as a printed line, each branch against its own previous quarter, and the rate — never the absolute value, or the biggest branch always "wins" and the table teaches nothing |
| **Exception lead** | The largest rise in rate, not the highest rate — a branch that has doubled is news; a branch that is chronically 1.2% is a standing problem already known |
| **Consolidation level** | Branch within comparable formats; group total |
| **Cadence** | Quarterly, or after each count cycle |
| **The decision it triggers** | Owner / GM: a controls intervention at a named branch — CCTV, till discipline, recount, a change of storekeeper. Never a public shaming: the report names the condition, not the person (R7). |
| **Tap-through** | One drill: the branch's **shrinkage split by reason code and by category**. Refuses to name individual staff on an executive screen. |
| **Alert condition** | Push when any branch's rate exceeds twice the group rate in a period, or when group shrinkage exceeds the board tolerance |
| **Data needed** | Adjustment and write-off value by branch with reason codes, average stock value by branch, count dates. ⚠ **Reason codes** again (as report 8) — without them everything is "unexplained" and the league cannot separate damage from theft. ⚠ **Count-coverage normalisation**: a branch that has not been counted shows zero shrinkage and would top the league falsely — coverage must be a printed column or the table lies. ⚠ **Board tolerance rate**. |
| **Novelty** | CLASSIC — the coverage normalisation is the trap most implementations fall into |

---

## 17. Buying Dead Lines

| field | content |
|---|---|
| **Screen name** | `Buying Dead Lines` |
| **Full name** | Buying What Isn't Selling — purchase orders for lines already slow |
| **Archetype** | 6 · Exception Register |
| **The question it answers** | "Am I still spending money on goods that have already proved they do not sell?" |
| **Key figures** | **"TZS 132M ordered in the last 30 days on lines with over 4 months of cover"**; **orders breaching** (count); **buyers involved** (count); top 7: item, **months of cover before the order**, **order value**, **buyer**, **days since last sale**; and **still cancellable (TZS)** — the money that can still be saved today |
| **The comparison** | Each order against the item's cover and sale rate **at the moment the order was raised** — judged on what was knowable then, not with hindsight; plus the same breach value last month, and repeat buyers |
| **Exception lead** | The largest still-cancellable order leads, because it is the only line on the screen where action still changes the outcome |
| **Consolidation level** | Company; drills to buyer and to branch |
| **Cadence** | Weekly |
| **The decision it triggers** | GM / Owner: cancel or cut the open orders now; lower the buyer's authority; add a hard system block on ordering above N months of cover without an override reason |
| **Tap-through** | One drill: the **buyer's own breach history** (count and value by month). Refuses to open the purchase document. |
| **Alert condition** | Push when a single order above TZS 10M is raised for a line with more than 6 months of cover, at the moment of raising — this alert is worth more than the weekly report |
| **Data needed** | Purchase orders with date, buyer, item, quantity, value; cover and sale rate as at the order date; order status (open/cancellable/received). ⚠ **A cover snapshot at order time** — requires historical stock position (same build item as report 4). ⚠ **Buyer identity on the order** (often only "system" or a shared account). ⚠ **An override-reason field** so a legitimate strategic buy is recorded rather than argued about later. |
| **Novelty** | **NOVEL** — dead stock is reported everywhere; the *act of creating it* almost nowhere. This is the only report in the suite that catches the mistake before the money leaves. |

---

## 18. Cash From Stock

| field | content |
|---|---|
| **Screen name** | `Cash From Stock` |
| **Full name** | When the Stock Money Comes Back — cash released, week by week |
| **Archetype** | 9 · Forecast & Runway |
| **The question it answers** | "If I stopped buying today, how fast would my stock turn back into cash, and how much of it never would?" |
| **Key figures** | Headline is a date and an amount: **"TZS 640M returns as cash by 30 September; TZS 310M never returns at the current rate"**; weekly release curve for 13 weeks (high / likely / low); **cash locked indefinitely** (the stock whose sale rate implies more than 12 months); **release if dead stock cleared at 30% discount** (TZS); **forecast accuracy of the last run** (%) |
| **The comparison** | The forecast against the same forecast one month ago (drift), and against the working-capital target the CFO carries |
| **Exception lead** | The **never-returns** number leads whenever it exceeds 15% of stock value — it is the real message, and a rising release curve must never be allowed to hide it |
| **Consolidation level** | Company (cash is managed per company); group roll-up for the owner |
| **Cadence** | Monthly, and whenever the cash forecast (Cash domain) turns red |
| **The decision it triggers** | CFO / Owner: fund a shortfall by unwinding stock instead of borrowing; set a clearance target with a number and a date; time a purchase freeze to a known cash pinch |
| **Tap-through** | One drill: the **top 10 categories by cash locked**, with each one's release time. Refuses to show items or documents. |
| **Alert condition** | Push when the cash the stock will release in the next 30 days falls more than 20% below the previous month's forecast for the same window |
| **Data needed** | On-hand value by item, sale rate by item, gross margin (to convert cost released into cash received), payment terms of customers (goods sold on credit release cash later — this is the join with AR). ⚠ **The cost-to-cash lag**: stock → sale → collection; needs debtor days by customer segment or the forecast is optimistic by 30–60 days. ⚠ **Clearance-realisation rate** (what discounted dead stock actually fetches historically). ⚠ **Forecast-accuracy history of this report.** |
| **Novelty** | **NOVEL** — ERPs forecast cash from receivables and payables and treat stock as a static balance. Owners think of stock as money they can get back; nothing tells them how fast. |

---

## 19. What Excess Costs

| field | content |
|---|---|
| **Screen name** | `What Excess Costs` |
| **Full name** | What the Extra Stock Costs — the surplus and what it burns each month |
| **Archetype** | 4 · Variance Bridge |
| **The question it answers** | "The extra stock I am carrying — what is it actually costing me every month to keep it?" |
| **Key figures** | Headline: **"The surplus costs TZS 26.4M a month"**; the cost bridge, largest first: **Interest on the money tied up**, **Warehouse space and handling**, **Insurance**, **Shrinkage attributable to the surplus**, **Obsolescence and markdown expected**, **Residual**; foot: **cost as % of the surplus value** and **annualised cost in days of group profit** |
| **The comparison** | Surplus is defined against the cover policy (report 3), so the whole screen inherits a plan comparison; the monthly cost is compared to the same figure a quarter ago and to the margin the surplus lines actually earn |
| **Exception lead** | Whichever cost bar exceeds its share leads, with the category driving it named. If **obsolescence** is the largest bar, that is stated in words: *"most of what the surplus costs is that it will never sell."* |
| **Consolidation level** | Company; drills to category and branch |
| **Cadence** | Monthly |
| **The decision it triggers** | Owner: authorise a clearance at a discount that is cheaper than the carrying cost — the report exists to make "discount it" arithmetically obvious. CFO: charge carrying cost back to the branches that create it. |
| **Tap-through** | One drill: the **categories ranked by monthly carrying cost**. Refuses to show cost-accounting workings on the phone. |
| **Alert condition** | Push when the monthly carrying cost of the surplus exceeds the gross margin the surplus lines earned that month — the point at which holding the stock is worse than giving it away |
| **Data needed** | Surplus value by category (from policy bands), cost of sales and margin by line. ⚠ **Cost of capital rate** (board-set, per company, in TZS terms). ⚠ **Warehouse cost per shilling or per cubic metre of stock** — needs storage cost allocation and ideally item volume; neither is normally captured. ⚠ **Insurance premium allocable to stock.** ⚠ **Historical obsolescence rate by category** to price expected write-off. |
| **Novelty** | **NOVEL** — every owner intuits that stock costs money to hold; almost nobody has ever been shown the monthly number. It reframes discounting from "losing money" to "losing less money". |

---

# TIER 3 — specialist and on demand

---

## 20. New Lines

| field | content |
|---|---|
| **Screen name** | `New Lines` |
| **Full name** | Do New Products Sell? — sell-through by launch month cohort |
| **Archetype** | 11 · Cohort & Retention |
| **The question it answers** | "The new products we keep adding — do they actually sell, and are the recent ones better or worse than the ones we added last year?" |
| **The question in one line on screen** | Verdict on recency: **"Lines launched this year have sold 41% of their first order by month 6; last year's had sold 63%."** |
| **Key figures** | 1) that verdict; 2) **sell-through % at months 3 / 6 / 12** per cohort (5–6 cohort lines); 3) **money still sitting in this year's launches (TZS)**; 4) **share of launches that became dead stock** per cohort; 5) **launches per cohort** (count) |
| **The comparison** | Cohort against cohort — no external target needed; recent cohorts highlighted, older ghosted |
| **Exception lead** | The worst recent cohort and the single largest launch that failed, by money still on the shelf |
| **Consolidation level** | Company; drills to category and buyer |
| **Cadence** | Quarterly |
| **The decision it triggers** | GM / Owner: fix range selection, not range size — stop rewarding buyers for the count of new lines introduced; impose a trial-quantity rule and a kill rule on new lines |
| **Tap-through** | One drill: the **failed launches inside a cohort**, with buyer and money stranded. Refuses to show the full product list. |
| **Alert condition** | No push — a quarterly reading report |
| **Data needed** | First-receipt date per item (the launch date), first-order quantity, cumulative sales by month since launch, current on-hand. ⚠ **A genuine product launch date** — "created on" in the item master is not it (items get created and never bought). ⚠ **A "new line" designation and its trial quantity**, so a launch can be judged against what was intended. ⚠ **Buyer / range-owner per item.** |
| **Novelty** | **NOVEL** — cohort thinking is applied to customers in perhaps 5% of ERPs and to products in almost none, yet range churn is where trading businesses quietly bury cash |

---

## 21. Receipt to Shelf

| field | content |
|---|---|
| **Screen name** | `Receipt to Shelf` |
| **Full name** | How Long From Goods In to Sellable — where the delay is, and what it holds up |
| **Archetype** | 12 · Cycle-Time & Flow |
| **The question it answers** | "When goods arrive at my gate, how long before they can actually be sold — and where do they get stuck?" |
| **Key figures** | **"Gate to shelf: 3.4 days median, 11 days at the 90th percentile"**; the stages as one segmented bar — **Unloaded → Counted/Inspected → Received into system → Costed → Put away → Available**; **stock currently stuck** (TZS and days) in the worst stage; **items sold-out at a branch while sitting in that branch's receiving area** (the killer number) |
| **The comparison** | Against the agreed service standard per stage, and against the same measure a quarter ago; median **and** 90th percentile, because the average hides the pathological consignments |
| **Exception lead** | The stage exceeding its standard, named, with the money stuck in it and the owner of that stage |
| **Consolidation level** | Branch and warehouse; group median for the owner |
| **Cadence** | Monthly; on alert when value stuck crosses a threshold |
| **The decision it triggers** | GM: add capacity or authority at the named stage; remove an inspection step that costs more days than it saves. Owner: sees that "we are out of stock" and "we have the stock in the yard" can be the same week. |
| **Tap-through** | One drill: **the consignments currently stuck in the worst stage, with an owner each**. Refuses to show line detail. |
| **Alert condition** | Push when stock worth more than TZS 50M has been in receiving for over 5 days, or when any item that is out of stock at a branch is sitting unreceived at that same branch |
| **Data needed** | Timestamps at each handoff for each receipt. ⚠ **Stage timestamps at all** — most ERPs record one GRN posting time and nothing before or after it; unload, inspect, cost and put-away times must be captured (even coarsely) for this report to exist. ⚠ **A service standard per stage.** ⚠ **A stage owner (role, not person).** |
| **Novelty** | **NOVEL** — the stock-out that was caused by your own receiving bay is invisible in every ERP I would expect to meet |

---

## 22. Order to Shelf

| field | content |
|---|---|
| **Screen name** | `Order to Shelf` |
| **Full name** | How Long From Order to Shelf — supplier lead time as it really is |
| **Archetype** | 12 · Cycle-Time & Flow |
| **The question it answers** | "How long do my goods really take to arrive, and which supplier is the one that makes me hold extra stock?" |
| **Key figures** | **"Order to sellable: 24 days median, 47 days at the 90th percentile — the system assumes 14"**; stages: **Requisition → Approval → PO issued → Supplier despatch → Arrival → Sellable**; **worst stage** named with days; **suppliers ranked by unreliability** (spread between median and 90th percentile, not by average lateness); **extra stock this unreliability forces us to hold (TZS)** |
| **The comparison** | Actual against the lead time the system uses for reordering — the gap between assumed and real lead time is the report's whole reason to exist; plus last quarter |
| **Exception lead** | The supplier whose **variability** costs the most safety stock, not the one who is slowest — a reliably slow supplier is cheap to plan around; an erratic one is expensive |
| **Consolidation level** | Company; drills to supplier and category |
| **Cadence** | Quarterly, or when report 5 keeps mispredicting |
| **The decision it triggers** | GM / CFO: renegotiate or replace a named supplier; correct the lead-time parameters that drive every reorder; charge the cost of unreliability into supplier price comparisons |
| **Tap-through** | One drill: **that supplier's last 12 deliveries — promised vs actual**. Refuses to open purchase orders. |
| **Alert condition** | No push — a parameter-correction report, read on a rhythm |
| **Data needed** | Requisition, approval, PO issue, supplier promise, receipt and sellable timestamps per order; the lead-time parameter currently used for planning. ⚠ **The supplier's promised date** (captured at order, and its revisions) — usually absent, and without it "late" cannot be distinguished from "always was 24 days". ⚠ **Safety-stock formula** to price the variability. ⚠ Approval timestamps (shared with report 9). |
| **Novelty** | CLASSIC in supply chain, **rare in mid-market ERPs**; pricing variability rather than lateness is the executive move |

---

## 23. Impossible Stock

| field | content |
|---|---|
| **Screen name** | `Impossible Stock` |
| **Full name** | Stock That Should Not Exist — negatives, no cost, no location |
| **Archetype** | 6 · Exception Register |
| **The question it answers** | "Is there anything in my stock records that is simply not possible — and how much of my reported value depends on it?" |
| **Key figures** | **"38 conditions · TZS 61M of reported value affected"**; by condition: **negative on-hand** (count, value), **on-hand with zero or missing cost** (count, value at peer cost), **stock with no location or a closed location**, **stock at a closed branch**, **quantity on hand but no movement ever recorded**, **units that cannot convert** (UoM breaks); each with age of oldest instance |
| **The comparison** | The same counts a week ago, and how many are repeats — a condition that survives a week is a control failure, and this register's repeats are usually the same three items forever |
| **Exception lead** | The condition with the greatest **value distortion**, since the purpose is protecting every other report in the suite, not tidiness |
| **Consolidation level** | Group; drills to branch |
| **Cadence** | Weekly glance by the CFO's office; the owner sees only the alert |
| **The decision it triggers** | CFO: block the month-end close until cleared; assign each condition to a named clearer with a deadline. Owner: distrust margin reporting while the zero-cost bucket is non-trivial. |
| **Tap-through** | One drill: **the items under one condition with branch and value**. Refuses to show the movement history. |
| **Alert condition** | Push when value affected exceeds TZS 20M, or when any negative on-hand persists past 72 hours |
| **Data needed** | On-hand by item/location with cost, location and branch status, movement existence, UoM conversion definitions. ⚠ **Peer/category average cost** to quantify what a zero-cost item is distorting. ⚠ **A clearer/owner assignment and an accept state**, as with every register. |
| **Novelty** | CLASSIC — hygiene, but it is what earns the suite the right to be believed |

---

## 24. Stock at Risk

| field | content |
|---|---|
| **Screen name** | `Stock at Risk` |
| **Full name** | How Much Rides on One Store — value by site against insured cover |
| **Archetype** | 10 · Concentration & Exposure |
| **The question it answers** | "If one warehouse burned or flooded tonight, how much of my business would be inside it — and would the insurance actually cover it?" |
| **Key figures** | **"41% of group stock sits in one building — TZS 990M, insured for 600M"**; top 5 sites with **value**, **share of group stock**, **cumulative share**, **insured limit**, **uninsured gap**; plus **cover** — *"how many weeks of trading that site's stock supports"*; and **single-sourced lines held only at that site** (count and margin they carry) |
| **The comparison** | Share against the same share a year ago (is dependence deepening?), and against a board-set tolerance line drawn across the Pareto |
| **Exception lead** | The largest **uninsured gap**, in money — not the largest site |
| **Consolidation level** | Group only — the whole point is a group-level fragility |
| **Cadence** | Half-yearly, and whenever insurance is renewed or a site is added |
| **The decision it triggers** | Owner: raise cover, split the stock across two sites, or accept the risk in writing. CFO: renegotiate the policy before renewal, not after a claim. |
| **Tap-through** | One drill: the site's **category composition and the lines held nowhere else**. Refuses transaction detail entirely. |
| **Alert condition** | Push when any site's stock value exceeds its insured limit by more than 10%, or when a site passes 40% of group stock |
| **Data needed** | Stock value by physical site (not by accounting branch — they differ), item presence by site. ⚠ **Insured value / policy limit per site** — an insurance register lives in a filing cabinet, never in the ERP. ⚠ **A physical-site dimension distinct from branch.** ⚠ **Board tolerance for single-site concentration.** ⚠ Single-source flag per item. |
| **Novelty** | **NOVEL** — the balance sheet says "stock TZS 2.4Bn" and never says "and 41% of it is in one shed" |

---

## 25. Received Not Billed

| field | content |
|---|---|
| **Screen name** | `Received Not Billed` |
| **Full name** | Goods Received but Not Yet Billed — value and age by supplier |
| **Archetype** | 7 · Ageing Pyramid |
| **The question it answers** | "How much have I taken into stock that no supplier has invoiced me for yet — and how much of that is a bill about to ambush me?" |
| **Key figures** | **"TZS 214M received not billed · 34% older than 60 days"**; bands **0–15 / 16–30 / 31–60 / 60+ days**, each with value and consignment count; top 5 suppliers by aged value; **priced at a cost that has since risen (TZS)** — the exposure to a bill larger than the accrual; **matched-and-cleared last month (TZS)** |
| **The comparison** | The same shape last month (is the pile fattening at the old end?), and the accrued cost against the supplier's current price list |
| **Exception lead** | The oldest band by value, and separately anything where the **expected invoice exceeds the accrual by more than 5%** — a coming P&L hit dressed up as a balance |
| **Consolidation level** | Company; drills to supplier |
| **Cadence** | Month-end |
| **The decision it triggers** | CFO: chase the invoices, or accept that the stock cost carried is wrong and correct it before close. Owner: refuse to read a margin number while a large aged GRNI sits unpriced. |
| **Tap-through** | One drill: **the supplier's aged consignments with dates and values**. Refuses to show the GRN lines. |
| **Alert condition** | Push when GRNI over 60 days exceeds TZS 50M, or when any single consignment above TZS 20M passes 45 days |
| **Data needed** | Goods receipts not matched to a supplier invoice, with receipt date, supplier, value, and the cost at which stock was taken in; supplier current prices. ⚠ **Current supplier price list per item** to detect the accrual-versus-actual gap. ⚠ **A dispute/hold flag** so contested consignments do not sit in the old band pretending to be neglect. |
| **Novelty** | CLASSIC — sits on the AP/Stock boundary; belongs here because it is money already in the stock value with no bill behind it |

---

# Build items this domain generates

Every ⚠ above, consolidated. These are the reason the suite is ambitious rather than a re-skin.

| # | Build item | Unlocks |
|---|---|---|
| B1 | **Cover policy: min/max days per category per branch** | 3, 7, 10, 17, 19 |
| B2 | **Historical stock-position snapshots (availability series)** | 4, 17, and any "as at the time" judgement |
| B3 | **Reason codes mandatory on every adjustment/write-off** | 8, 16, 11 |
| B4 | **Stage timestamps on receiving (unload → inspect → cost → put-away)** | 21, 5 |
| B5 | **Supplier promised date, captured and revised** | 22, 5 |
| B6 | **Stock plan / balance-sheet budget by category by month** | 7, 1, 3 |
| B7 | **Batch expiry enforced at GRN; layer-level ageing** | 11, 12 |
| B8 | **Despatch and receipt as separate transfer events, with route standards** | 14, 10 |
| B9 | **Assortment / listed lines per branch** | 4, 10 |
| B10 | **Cost of capital, storage cost, insurance allocation, insured limit per physical site** | 19, 24 |
| B11 | **Product launch date, trial quantity and range owner** | 20, 17 |
| B12 | **Accept-with-expiry state and named clearer on every Exception Register** | 2, 10, 17, 23 |
| B13 | **Cost-of-delay metadata on approvals; approval-turnaround history** | 9, 22 |
| B14 | **Forecast-accuracy self-tracking on every Forecast report** | 5, 18 |
| B15 | **Net realisable value inputs (recent net selling price per item per branch)** | 15, 2, 12 |

**The five to build first if only five are funded:** B1 (nothing in this domain has a comparison without it), B2 (it unlocks the two most novel reports), B3 (it is the difference between shrinkage reporting and shrinkage guessing), B6, B12.

===== DOMAIN: sales — SALES PERFORMANCE & CUSTOMERS =====
# Sales Performance & Customers — Executive Report Suite

**OrbixERP Executive Mobile · domain pack v1 · 2026-08-18**
25 reports, designed from first principles. Each is one archetype, named by that archetype's template, and passes the eight-point naming checklist. Currency TZS throughout; "margin" always means gross margin after landed cost unless stated.

**Tier summary**

| Tier | Count | Reports |
|---|---|---|
| **TIER 1** — opened weekly or more | 9 | Today's Trade · Sales Against Plan · Branch League · Sales Gap · Month-End Landing · Orders Waiting · Price Given Away · Seller League · Customers Gone Quiet |
| **TIER 2** — monthly or on alert | 12 | Route League · Customer League · Discounts Off Rule · Order to Delivery · Quote to Order · Why We Lose · Goods Coming Back · Big Customer Risk · New Customers · Shrinking Baskets · Sales We Missed · Sales Mix Shift |
| **TIER 3** — specialist / on demand | 4 | Costly Customers · One Seller Risk · Order Book Match · New Branch Ramp |

**Novelty split:** 15 CLASSIC, 10 NOVEL. The novel ten are: Price Given Away · Customers Gone Quiet · Why We Lose · Goods Coming Back · Shrinking Baskets · Sales We Missed · Costly Customers · One Seller Risk · Order Book Match · New Branch Ramp.

---

# TIER 1 — the weekly spine

---

## 1. Today's Trade

| field | content |
|---|---|
| **Screen name** | `Today's Trade` |
| **Full name** | Today's Trade — sales, tickets and basket vs the same weekday |
| **Archetype** | 1 · Flash |
| **The question it answers** | "Is today trading normally, and if not, which branch is dragging?" |
| **Key figures** | ① Sales value so far today (TZS) ② Tickets (invoice/receipt count) ③ Average basket = sales ÷ tickets ④ Cash collected today across tills ⑤ Number of branches trading below 80% of their normal weekday pace |
| **The comparison** | Same weekday, trailing 4-week median, at the same hour of day — as a delta chip on each line. Never "vs yesterday". A second faint chip carries same weekday last month for month-shape effects (salary week, month-end). |
| **Exception lead** | The branch furthest below its own weekday-hour pace is named at the top of the body — "Kariakoo 41% of a normal Tuesday at 11:04" — before any group total is read. If every branch is inside band, the line reads "All 11 branches inside normal range" and the screen is calm. |
| **Consolidation level** | Group headline; must roll up from branch. Company layer available but the owner reads group-then-branch. |
| **Cadence** | Glance daily — twice: mid-morning and at close. |
| **The decision it triggers** | Owner or GM makes a phone call before lunch: "why is the counter empty?" Branch Manager acts same-day (staffing, POS down, stock gap, competitor promo next door). |
| **Tap-through** | The branch strip — the same five numbers per branch, ranked by deviation from own pace. **Refuses** to show invoices, receipt numbers, or item lines. |
| **Alert condition** | Push at 11:00 and 16:00 **only when** group sales are below 70% of the weekday-hour pace, or any branch is below 50% of its own pace with >TZS 2M normally expected by that hour. Silence otherwise is the design. |
| **Data needed** | Posted + unposted POS/counter sales with timestamps; invoice counts; till cash; branch trading calendar (open/closed days, half-days). ⚠ **Branch trading-hours calendar and public-holiday calendar** — without them the weekday baseline is polluted by half-days. ⚠ **POS terminal heartbeat / uptime** so "no sales" can be distinguished from "POS is down". |
| **Novelty** | CLASSIC |

---

## 2. Sales Against Plan

| field | content |
|---|---|
| **Screen name** | `Sales Against Plan` |
| **Full name** | Sales Against Plan — month to date, by branch and channel |
| **Archetype** | 2 · Scorecard |
| **The question it answers** | "Are we going to make the month, and which part of the business is missing?" |
| **Key figures** | ① Verdict — "4 of 7 units on plan" ② Sales month-to-date vs plan-to-date, and the gap in TZS ③ Achievement % of the full-month plan with the calendar % elapsed printed beside it ④ Margin rate MTD vs planned margin rate ⑤ Gap in TZS of the single worst-missing unit ⑥ Days of selling left in the month |
| **The comparison** | The plan itself, printed on every row: actual · plan-to-date · gap. Plan-to-date is **weighted by trading days and weekday shape**, not straight-lined — a straight-lined plan makes every month look lost on day 3. |
| **Exception lead** | Rows ordered by size of miss in TZS, not alphabetically and not by size of unit. The largest shortfall is row one. Units on plan collapse into a single "3 units on plan" line at the bottom. |
| **Consolidation level** | Group verdict, rolls up company → branch → channel. Each level keeps the same seven measures so the owner can hold one memory of it. |
| **Cadence** | Glance daily from day 15; weekly before that; formally at month-end. |
| **The decision it triggers** | Owner/GM decides which single miss gets management attention this week, and whether to authorise a month-end push (promo, credit release, extra route day). CFO decides whether the plan or the performance is wrong. |
| **Tap-through** | Any red row opens **Sales Gap** (report 4) for that unit — the Scorecard→Bridge pairing. **Refuses** to show the customer list or invoice detail. |
| **Alert condition** | Push once, on the 20th of the month, if group achievement is below 75% of plan-to-date, or if any unit is below 60%. Also push immediately if a unit that was on plan crosses below 85% (a new failure is more actionable than a chronic one). |
| **Data needed** | Sales value by branch, channel and month; margin by the same. ⚠ **Monthly sales targets by branch, by channel and by seller, approved and versioned** — the single most important missing input in the whole suite; without it half these reports degrade into Position Statements. ⚠ **Planned margin rate** per unit. ⚠ **Trading-day calendar** for plan phasing. |
| **Novelty** | CLASSIC |

---

## 3. Branch League

| field | content |
|---|---|
| **Screen name** | `Branch League` |
| **Full name** | Branch League — sales and margin rate against plan and group |
| **Archetype** | 5 · League Table |
| **The question it answers** | "Which branch is genuinely selling well, and which one do I need to visit this week?" |
| **Key figures** | ① The spread — "best 31% margin, worst 12%, group 22%" ② Achievement % of own plan per branch ③ Margin rate per branch ④ Sales per selling day (size-neutralised) ⑤ Rank change since last month ⑥ Count of branches below the group rate |
| **The comparison** | Two reference lines drawn across the bars: the group rate and each branch's own plan. Ranking is on **margin rate and achievement**, never absolute sales — otherwise the biggest branch always wins and the table teaches nothing. |
| **Exception lead** | Bottom 3 shown first with rank-change arrows, then top 3, with "+5 branches between" collapsed in the middle. A branch that fell 4 places is flagged above a branch that is chronically last — a collapse and a laggard need different visits. |
| **Consolidation level** | Group, ranking branches. Company filter available. Branches under 90 days old are excluded and the exclusion is printed. |
| **Cadence** | Weekly, Monday morning. |
| **The decision it triggers** | Owner/GM: where the visit goes this week, and which practice from the top branch is forced into the bottom two. Repeated bottom placement over 3 months triggers a manager conversation. |
| **Tap-through** | A branch row opens that branch's **Sales Against Plan** scorecard. **Refuses** to show individual staff names on this screen — that is Seller League, deliberately separated so branch performance is not read as one person's fault. |
| **Alert condition** | No push. This is a routine station, and pushing a league table weekly makes it political. |
| **Data needed** | Sales and margin by branch; branch open date; selling days per branch. ⚠ **Branch plan** (see report 2). ⚠ **Branch comparability class** (mall counter / wholesale depot / factory shop) so incomparable units are not ranked together — a manual attribute someone must set. ⚠ Whether central costs are allocated (must be stated in the trust line; recommend **not** allocated here). |
| **Novelty** | CLASSIC |

---

## 4. Sales Gap

| field | content |
|---|---|
| **Screen name** | `Sales Gap` |
| **Full name** | Why Sales Are Short — plan to actual, by named cause |
| **Archetype** | 4 · Variance Bridge |
| **The question it answers** | "We are TZS 180M behind — what exactly caused it, in order of size?" |
| **Key figures** | ① The gap as the headline (TZS below plan, and % of plan) ② Volume effect ③ Price/discount effect ④ Mix effect (channel and product family) ⑤ Lost-customer effect (accounts that bought last year, nothing this year) ⑥ Residual, which must stay under 10% of the gap or the report says so |
| **The comparison** | Built into the form — plan → actual. A second toggle bridges prior-year → this-year for owners who trust last year more than the plan. |
| **Exception lead** | The largest negative bar is labelled with its owner: "Discount −TZS 62M · Kariakoo + Mwanza · authorised by 2 sellers". A bar with no name attached is a failure of this report. |
| **Consolidation level** | Group, drillable to company and branch — the same six causes at every level so the language stays constant. |
| **Cadence** | Weekly during the month; the definitive read is month-end. |
| **The decision it triggers** | Different bar, different act: **price bar** → Owner raises price or tightens discount authority; **volume bar** → GM pushes activity/coverage; **mix bar** → GM changes what the team is told to sell; **lost-customer bar** → GM assigns win-backs by name. |
| **Tap-through** | Any bar opens the League Table for that cause — "which branches and which sellers produced the discount effect". **Refuses** to open into invoice lines; the drill ends at the responsible unit, not the transaction. |
| **Alert condition** | No push. This is opened deliberately, usually from the Scorecard. |
| **Data needed** | Actual sales and margin by product, customer, channel, branch; list price and realised price per line; prior-year same-period actuals. ⚠ **Sales plan decomposed to at least branch × channel** (a single group number cannot be bridged). ⚠ **List/reference price effective-dated** so a price effect is not confused with a price-list change. |
| **Novelty** | CLASSIC |

---

## 5. Month-End Landing

| field | content |
|---|---|
| **Screen name** | `Month-End Landing` |
| **Full name** | Where Sales Will Land — projected close against plan |
| **Archetype** | 9 · Forecast & Runway |
| **The question it answers** | "If nothing changes, what number do I close the month on — and by when must I act?" |
| **Key figures** | ① Projected month-end sales, with a high/likely/low band ② The gap to plan at that landing ③ **The last date an intervention still moves the number** ("act by 26 Aug — after that only 3 selling days remain") ④ Confirmed order book already scheduled to ship this month ⑤ Forecast drift — how this projection has moved since the projection made on day 10 ⑥ Last month's forecast error (%) |
| **The comparison** | Against plan for the same endpoint, against the previous run of this same forecast (drift is itself the signal), and against last month's actual. |
| **Exception lead** | If the likely landing is below plan, the headline is the shortfall and the act-by date, not the projected sales value. If the forecast has drifted down two runs in a row, that drift is called out above everything else. |
| **Consolidation level** | Group headline, roll-up from branch × channel; each branch gets its own landing so the GM can chase the specific one. |
| **Cadence** | Glance daily from day 12; the act-by date makes it urgent from day 18. |
| **The decision it triggers** | Owner/GM authorises the month-end push while it can still work: release held credit orders, run a clearance on slow lines, add a route day, pull forward a project delivery. CFO uses the landing to set the collections and payables plan. |
| **Tap-through** | The driver list — the confirmed orders, scheduled deliveries and repeat-customer expectations ranked by how much each moves the answer. **Refuses** to show a single deterministic line; the band is always visible. |
| **Alert condition** | Push once when the likely landing first falls more than 10% below plan, and again if it drops a further 5%. Never push a forecast that is inside band — it teaches the owner to ignore the channel. |
| **Data needed** | Month-to-date sales; open confirmed order book with promised ship dates; repeat-customer buying rhythm; weekday/seasonal shape; historical month-end skew (the last-3-days spike). ⚠ **Promised delivery dates on open orders**, kept honest. ⚠ **Forecast-accuracy history** — the model must store its own past runs to report its error. ⚠ Plan. |
| **Novelty** | CLASSIC in ambition, rarely built. The act-by date and self-reported forecast error are what make it executive. |

---

## 6. Orders Waiting

| field | content |
|---|---|
| **Screen name** | `Orders Waiting` |
| **Full name** | Orders Waiting on You — value held and what stops despatch |
| **Archetype** | 14 · Decision Docket |
| **The question it answers** | "What sales decisions are sitting on me, what is each one worth, and what breaks if I keep waiting?" |
| **Key figures** | ① Queue size and value — "9 items · TZS 340M" ② Oldest item age in days ③ Count blocking despatch today ④ Estimated cost of delay this week (TZS of margin at risk from orders that will miss their promised date) ⑤ Your median approval time vs the team's |
| **The comparison** | Each item against its own policy — inside/outside credit limit, discount inside/outside authority, price above/below last price to that customer — and the queue against your own median decision time. |
| **Exception lead** | Ranked by **consequence of delay**, never arrival order. Top line is the order that loses a customer or a promised delivery today, not the biggest one. |
| **Consolidation level** | Personal — the queue addressed to the logged-in approver, across all companies and branches they cover. A GM sees a second tab-free strip: "3 items delegated to you are also late". |
| **Cadence** | Glance daily; it is a docket, not a report. |
| **The decision it triggers** | The approval itself — Owner, GM or CFO by limit. Over time it triggers a **delegation decision**: any item type approved unchanged 20 times in a row should have its limit raised, and the screen says so. |
| **Tap-through** | Approve / reject / send back with reason, plus the **one fact needed to decide**: for a credit hold — customer's current exposure, oldest overdue bucket, last promise kept or broken; for a discount — last price paid by that customer and the line's landed cost. **Refuses** to show the full order document; if you need the whole document, the approval limit is wrong. |
| **Alert condition** | Push when an item enters the queue that blocks a despatch scheduled within 24 hours, or when queue value exceeds TZS 250M, or when any item passes 48 hours. |
| **Data needed** | Approval queue with requester, value, type and age; credit exposure and overdue position per customer; last price paid per customer/item; landed cost; promised despatch dates. ⚠ **Cost of delay** requires promised-date data and a rule for what a missed promise costs. ⚠ **Approval-limit matrix** maintained as data, not as code. ⚠ Write-back — this archetype is worthless read-only. |
| **Novelty** | CLASSIC as a queue; NOVEL in treating it as a measured report (cost of delay + delegation signal). |

---

## 7. Price Given Away

| field | content |
|---|---|
| **Screen name** | `Price Given Away` |
| **Full name** | Where the List Price Went — discount, mix and free goods |
| **Archetype** | 4 · Variance Bridge |
| **The question it answers** | "If everything had sold at list, we would have earned X — where did the difference actually go?" |
| **Key figures** | ① Realisation rate — realised price as % of list (the headline, e.g. "86.4% of list") ② Total value given away this month (TZS) ③ Line discount effect ④ Off-price-list / manual override effect ⑤ Free goods, bonus quantity and rounding-down effect ⑥ Credit-note give-back effect (post-sale price corrections) |
| **The comparison** | Against the same realisation rate last month and same month last year, against the board's minimum realisation policy (e.g. "not below 90% of list"), and per branch against the group realisation rate. |
| **Exception lead** | The channel or seller with the worst realisation is named first, with the TZS given away, not the percentage — "Route sales realised 79% of list · TZS 44M given away". |
| **Consolidation level** | Group with mandatory roll-up: company → branch → channel → seller. Realisation is only meaningful when it can be walked down to a person. |
| **Cadence** | Weekly; formal review month-end. |
| **The decision it triggers** | Owner tightens discount authority or resets the price list; GM retrains or reassigns a seller; CFO reprices a customer tier whose realisation has drifted below the tier's intended level. |
| **Tap-through** | The seller/branch league on realisation rate. **Refuses** to show individual invoice lines — this report is about the pattern, and showing lines invites arguing about one exceptional deal. |
| **Alert condition** | Push when weekly realisation falls more than 3 percentage points below the trailing 12-week rate, or when any single seller's weekly realisation falls below 80%. |
| **Data needed** | Effective-dated list price per item per price list; realised price per line; bonus/free quantities; rounding at the till; credit notes classified as price correction vs return. ⚠ **A maintained list price for every sellable item** — most trading businesses have price lists that have rotted; this report will expose that first. ⚠ **Credit-note reason codes** to separate price give-back from goods returned. ⚠ **Bonus quantity captured as a value giveaway, not a zero-price line.** |
| **Novelty** | **NOVEL.** Most ERPs report discount %. Almost none decompose the full leakage — override, free goods, rounding and post-sale credit — into one realisation bridge that lands on a named person. |

---

## 8. Seller League

| field | content |
|---|---|
| **Screen name** | `Seller League` |
| **Full name** | Seller League — margin per selling day against the team rate |
| **Archetype** | 5 · League Table |
| **The question it answers** | "Who on the sales floor is actually earning money for us, and who only looks busy?" |
| **Key figures** | ① The spread — best vs worst margin per selling day, with the team rate printed ② Margin earned per seller (not revenue) ③ Realisation rate per seller (from report 7) ④ Active customers served this month per seller ⑤ Rank change since last month ⑥ Count of sellers below the team rate |
| **The comparison** | Team rate as the printed reference line; each seller against their own plan; rank change month over month. Normalised **per selling day** so leave, transfers and part-months do not distort. |
| **Exception lead** | Not the leader — the **biggest faller**. "Fell 6 places · realisation down 9 points" appears above the top performer, because a collapsing good seller is a more urgent fact than a stable star. |
| **Consolidation level** | Branch by default (peers who face comparable conditions), with a group view that groups sellers by comparability class — counter staff, route sellers, project/key-account sellers are ranked separately, never mixed. |
| **Cadence** | Weekly. |
| **The decision it triggers** | GM/Branch Manager: coaching, territory reassignment, incentive change. Owner: whether the incentive scheme is rewarding revenue when it should reward margin — usually the real finding. |
| **Tap-through** | The seller's own mini-scorecard: their plan, realisation, returns rate and customers gone quiet. **Refuses** to publish a group-wide named ranking on the home screen; the report is named for the condition, not the person, and access follows the manager's own scope. |
| **Alert condition** | No push. A pushed personal ranking becomes political within a fortnight. |
| **Data needed** | Sales and margin attributed to a seller per line; seller working-days calendar; seller class; per-seller targets. ⚠ **Reliable seller attribution on every sales line**, including counter/POS sales — commonly missing and the reason most seller leagues are wrong. ⚠ **Seller targets and leave calendar**. ⚠ **Seller comparability class**. |
| **Novelty** | CLASSIC in form; the per-selling-day and margin (not revenue) basis is what makes it honest. |

---

## 9. Customers Gone Quiet

| field | content |
|---|---|
| **Screen name** | `Customers Gone Quiet` |
| **Full name** | Customers That Stopped Buying — value at risk, days silent |
| **Archetype** | 6 · Exception Register |
| **The question it answers** | "Which customers have quietly stopped buying from us, before I find out from their competitor's truck?" |
| **Key figures** | ① Count and money — "17 accounts quiet · TZS 210M of annual margin at risk" ② Days silent vs that customer's own normal gap between orders ③ New this week vs still quiet from last week vs recovered ④ Value of the largest quiet account ⑤ Count of quiet accounts that are still inside their credit terms (i.e. not a collections problem — a relationship problem) |
| **The comparison** | Each customer against **their own buying rhythm**, not a fixed 90-day rule. A customer who buys weekly and has been silent 21 days is a screaming exception; a quarterly buyer silent 60 days is normal. The register also compares this week's count to last week's, and flags repeats. |
| **Exception lead** | Ranked by annual margin at risk × how far past their own normal interval they are. Top line: "Mbeya Hardware · buys every 9 days · silent 34 days · TZS 41M/yr". |
| **Consolidation level** | Group register, filterable to branch and to seller. Must roll up: the same customer buying from two branches counts once. |
| **Cadence** | Weekly, Monday. On-alert for the top decile. |
| **The decision it triggers** | GM assigns a named win-back call with a deadline; Owner personally calls the top 3; Branch Manager checks whether a service failure (short delivery, price dispute, rude counter) preceded the silence. Also a rule decision — if 60 accounts appear, the interval rule is too tight. |
| **Tap-through** | That customer's last 12 months of buying with the last order, last complaint and last credit note marked. **Refuses** to show the whole invoice history; the point is the pattern break, not the ledger. |
| **Alert condition** | Push immediately when an account in the top 50 by annual margin passes 2× its own normal buying interval. Weekly digest for the rest. |
| **Data needed** | Per-customer order history with dates and margin; derived per-customer expected buying interval and its variability; customer group/related-party linking; assigned seller. ⚠ **Related-party grouping** so one owner's four accounts do not hide a defection. ⚠ **Reason capture on win-back calls** — otherwise the register never learns why customers leave. ⚠ **Seasonal customers flagged** (school suppliers, Ramadan traders) or they generate false positives every year. |
| **Novelty** | **NOVEL.** Nearly every ERP has a "dormant customer" list on a fixed day count. Almost none measure each customer against their *own* rhythm and price the silence in margin — which is the difference between a list and a work queue. |

---

# TIER 2 — monthly, or when the alert fires

---

## 10. Route League

| field | content |
|---|---|
| **Screen name** | `Route League` |
| **Full name** | Route League — margin per day on the road and stock returned |
| **Archetype** | 5 · League Table |
| **The question it answers** | "Which van routes pay for themselves, and which ones are just driving stock around?" |
| **Key figures** | ① Spread — best vs worst margin per route-day, with group rate ② Margin per route-day ③ Drop rate — customers sold to ÷ customers visited ④ Return rate — value of stock loaded but brought back, as % of loaded ⑤ Cash collected on route vs credit extended on route ⑥ Rank change |
| **The comparison** | Group route rate, each route's own plan, and prior month rank. Normalised per route-day so a 6-day route is not compared raw against a 3-day route. |
| **Exception lead** | The route whose **return rate** is worst leads if any route exceeds 20% returned — loading stock that comes back is pure cost and is invisible in a sales report. Otherwise the worst margin per route-day leads. |
| **Consolidation level** | Company/branch that owns the routes; group roll-up for the owner. |
| **Cadence** | Weekly. |
| **The decision it triggers** | GM: redesign or merge a route, change the load list, change the visit plan. Owner: close a route that has been below cost for 3 months. Branch Manager: fix loading discipline. |
| **Tap-through** | The route's day-by-day trace: loaded value, sold value, returned value, cash in. **Refuses** to show customer-level lines — that is the route seller's own report, not the owner's. |
| **Alert condition** | Push when a route's returned stock exceeds 30% of loaded value on any day, or when a route runs below cost for 2 consecutive weeks. |
| **Data needed** | Van load-out and load-in by value; route plan (customers to visit); actual visits; sales, margin and cash per route-day; route running cost. ⚠ **Planned visit list per route** — without it drop rate cannot exist. ⚠ **Load-out/load-in reconciliation captured as an event**, not inferred. ⚠ **Route running cost** (fuel, driver, vehicle) to state "below cost" honestly. |
| **Novelty** | CLASSIC for a van-sales business; the returned-stock and drop-rate measures are the ones usually missing. |

---

## 11. Customer League

| field | content |
|---|---|
| **Screen name** | `Customer League` |
| **Full name** | Customer League — best and worst accounts by margin earned |
| **Archetype** | 5 · League Table |
| **The question it answers** | "Which customers actually make me money, and which big names are big only in revenue?" |
| **Key figures** | ① Spread — top account margin rate vs bottom, group rate printed ② Margin earned per account (12 months rolling) ③ Margin rate per account ④ Revenue rank vs margin rank, shown as a divergence arrow ⑤ Count of top-20-by-revenue accounts that are outside the top 20 by margin |
| **The comparison** | Group margin rate as the reference line; each account's revenue rank against its margin rank — the divergence is the whole story; and the same margin rate 12 months ago. |
| **Exception lead** | The **biggest revenue/margin divergence** leads: "Serengeti Traders — #2 by sales, #31 by margin". That single line changes how an owner treats their largest customer. |
| **Consolidation level** | Group, with related parties grouped into one account. Branch view available but the group view is the true one — a customer buying from three branches is one relationship. |
| **Cadence** | Monthly. |
| **The decision it triggers** | Owner/CFO: reprice or retier an account at renewal; withdraw a rebate; put a floor under discounting for that account. GM: protect the genuinely profitable accounts with service, not the loud ones. |
| **Tap-through** | The account's margin trend over 24 months with price-change markers. **Refuses** to show the account's open invoices — that is the AR domain's report, and mixing them turns a pricing decision into a collections argument. |
| **Alert condition** | No push. Monthly station. |
| **Data needed** | Sales and margin per customer at landed cost; related-party grouping; rebates and settlement discounts attributed back to the customer. ⚠ **Rebates, credit notes and settlement discounts attributed to the customer** or the margin is overstated for exactly the customers who negotiate hardest. ⚠ **Related-party grouping.** |
| **Novelty** | CLASSIC — but the revenue-rank vs margin-rank divergence is the fix that makes it worth an owner's tap. |

---

## 12. Discounts Off Rule

| field | content |
|---|---|
| **Screen name** | `Discounts Off Rule` |
| **Full name** | Sales Priced Below the Rule — value, approver, days open |
| **Archetype** | 6 · Exception Register |
| **The question it answers** | "Who is selling outside the price and discount rules we set, and how much has it cost?" |
| **Key figures** | ① Count and money — "31 breaches · TZS 58M below rule" ② New this week vs repeats ③ Value of the largest single breach ④ Count of breaches approved after the fact vs approved in advance ⑤ Number of distinct sellers involved ⑥ Repeat offenders (same seller, 3+ breaches in 30 days) |
| **The comparison** | Against the rule itself (max discount by customer tier, minimum margin %, price-list adherence), against the same count 7 days ago, and repeat vs new. |
| **Exception lead** | Repeats first, not the biggest single breach. A breach that recurs weekly is a control failure; a one-off large deal is usually a decision someone made on purpose. |
| **Consolidation level** | Group register, scoped by branch and seller; must roll up so a seller breaching in two branches is seen once. |
| **Cadence** | Weekly, and on-alert. |
| **The decision it triggers** | GM: a named conversation with a deadline. Owner: tighten or **loosen** the rule — if 200 breaches appear, the rule is unrealistic and the register is about to be ignored, which is the classic death of this archetype. CFO: revoke a discount authority. |
| **Tap-through** | The breach's context — customer tier, the applicable rule, the last price paid, and an **accept-with-reason** action so approved exceptions stop clogging the list. **Refuses** to show the full invoice. |
| **Alert condition** | Push when a single breach exceeds TZS 5M below rule, or when any seller reaches 3 breaches in 7 days. |
| **Data needed** | Discount rules by customer tier and item family; minimum margin policy; realised price and cost per line; approver identity; accept/waive state with reason. ⚠ **The discount rules must exist as data** — most businesses hold them in a manager's head. ⚠ **Approver captured at the moment of override**, not inferred later. ⚠ **Accept/waive write-back.** |
| **Novelty** | CLASSIC |

---

## 13. Order to Delivery

| field | content |
|---|---|
| **Screen name** | `Order to Delivery` |
| **Full name** | How Long From Order to Delivery — and where it stalls |
| **Archetype** | 12 · Cycle-Time & Flow |
| **The question it answers** | "When a customer orders, how long do they really wait — and which step is the hold-up?" |
| **Key figures** | ① Median elapsed days order → delivered ② 90th percentile days (the tail that loses customers) ③ The worst stage, named, with its median days and the count of orders stuck in it now ④ On-time-to-promise % ⑤ Value currently stuck past promise |
| **The comparison** | Against the promised lead time we quote customers, against the same measure a quarter ago, and per branch against the group median. The 90th percentile is always shown beside the median — the average hides the pathological cases. |
| **Exception lead** | The stage exceeding its standard, with the number of orders stuck there **now** and their value. Not the total cycle time. |
| **Consolidation level** | Group median with branch breakdown; must roll up. Stages are real-world handoffs (order taken · credit released · picked · despatched · delivered · signed), never system status codes. |
| **Cadence** | Monthly, weekly during a service problem. |
| **The decision it triggers** | GM: add capacity or authority at the named bottleneck — most often "credit release" or "waiting on approval", which is the owner discovering they are the bottleneck. Owner: remove an approval step costing more days than it saves shillings. |
| **Tap-through** | The orders currently stuck in the worst stage, each with an owner's name. **Refuses** to show completed orders — the value is in what is stuck, and a report built only on completed items never sees the orders stuck forever. |
| **Alert condition** | Push when the count of orders stuck past their promised date exceeds 15, or when stuck value exceeds TZS 100M. |
| **Data needed** | Timestamped stage transitions per order; promised delivery date per order; delivery confirmation. ⚠ **Promised delivery date captured at order entry** — usually absent, and without it on-time-to-promise cannot exist. ⚠ **Delivery/POD confirmation event with a real timestamp.** ⚠ **Cancelled orders flagged** so they are excluded and not silently dropped. |
| **Novelty** | CLASSIC |

---

## 14. Quote to Order

| field | content |
|---|---|
| **Screen name** | `Quote to Order` |
| **Full name** | How Long From Quote to Order — and where quotes die |
| **Archetype** | 12 · Cycle-Time & Flow |
| **The question it answers** | "How long do we take to turn a quotation into an order, and at which step do customers go cold?" |
| **Key figures** | ① Median days quote → order ② Conversion rate (quotes converted ÷ quotes issued, by value) ③ Median days from customer request → quote issued (our own responsiveness) ④ Value of quotes older than 30 days still open ⑤ Count of quotes never followed up |
| **The comparison** | Against our own service standard (quote within 24 hours), against the conversion rate one quarter ago, and per branch/seller against the group conversion rate. Conversion by **value** and by **count** shown together — a high count conversion with low value conversion means we win the small ones. |
| **Exception lead** | "Quotes never followed up" leads whenever it is above zero. A quote nobody chased is a self-inflicted loss and outranks a slow conversion. |
| **Consolidation level** | Branch and seller, rolling to group. |
| **Cadence** | Monthly; weekly in a project/B2B-heavy company. |
| **The decision it triggers** | GM: enforce a follow-up discipline and a quote-expiry rule; reassign key-account quoting. Owner: shorten the internal approval that delays quoting on large deals. |
| **Tap-through** | The open quotes over 30 days, ranked by value, with the seller named. **Refuses** to show quote line detail. |
| **Alert condition** | Push when open quote value over 30 days exceeds TZS 200M, or when conversion by value falls below 60% of the trailing quarter. |
| **Data needed** | Quotation issue dates and values; conversion linkage quote → order; follow-up activity; quote expiry. ⚠ **Quotations recorded as first-class documents with a status lifecycle**, including *lost* and *expired* — many ERPs let quotes rot in a limbo status. ⚠ **Customer request timestamp** (when they asked) to measure our own responsiveness. ⚠ **Follow-up activity log.** |
| **Novelty** | CLASSIC |

---

## 15. Why We Lose

| field | content |
|---|---|
| **Screen name** | `Why We Lose` |
| **Full name** | Where the Quoted Value Went — won, lost, and by reason |
| **Archetype** | 4 · Variance Bridge |
| **The question it answers** | "Of everything we were asked to supply this quarter, how much did we win — and what specifically made us lose the rest?" |
| **Key figures** | ① Total quoted value ② Won value and win rate ③ **Lost to price** ④ **Lost because we had no stock** ⑤ **Lost to lead time / delivery terms** ⑥ Lost to credit terms refused (we said no) — plus a residual "no reason given" bar that must stay under 15% or the report declares itself unreliable |
| **The comparison** | Bridge from quoted → won. Compared against the same decomposition last quarter, so a shift from "lost to price" toward "lost to stock" is visible — those demand completely different responses. |
| **Exception lead** | The largest loss cause in TZS, named with the branch and the product family behind it. If "no reason given" is the largest bar, that is the headline and the finding is a process failure. |
| **Consolidation level** | Group, drillable to branch and product family. |
| **Cadence** | Monthly for trading, per-quarter for project sales; on-alert if a single lost deal exceeds a threshold. |
| **The decision it triggers** | **Lost to price** → Owner/CFO revisit pricing or purchase cost. **Lost to stock** → GM changes the reorder policy for those specific lines, and the loss value justifies the working capital. **Lost to lead time** → GM fixes logistics. **Lost to credit** → CFO decides whether the credit policy is costing more margin than it saves in bad debt. |
| **Tap-through** | The named lost deals inside the largest cause bar, with competitor named where known. **Refuses** to show won deals — this screen exists to study losses. |
| **Alert condition** | Push when a single lost quotation exceeds TZS 50M, with its reason. |
| **Data needed** | Quotations with values; outcome (won/lost/expired); structured loss reason; competitor named; product family. ⚠ **A mandatory structured loss-reason code at quote closure** — this is the single biggest build item in this report, and without it the report is a residual bar. ⚠ **Competitor and competitor price where the seller knows it** (optional field, high value). ⚠ **Stock position at quote date**, so "lost to stock" can be verified rather than claimed. |
| **Novelty** | **NOVEL.** Very few ERPs price their losses. Being able to say "we lost TZS 310M this quarter because the shelf was empty" converts a stock argument into a board decision with a number attached. |

---

## 16. Goods Coming Back

| field | content |
|---|---|
| **Screen name** | `Goods Coming Back` |
| **Full name** | Sales That Came Back — credit notes by reason and seller |
| **Archetype** | 6 · Exception Register |
| **The question it answers** | "How much of what we sold is coming back, why, and who keeps causing it?" |
| **Key figures** | ① Return rate — returned value as % of sales, and TZS returned ② Margin destroyed (returned margin + restocking/damage loss) ③ Top return reason by value ④ Repeat offenders — sellers/branches with return rates above 2× group ⑤ Count of returns issued more than 30 days after the sale ⑥ Value of goods credited but never physically returned |
| **The comparison** | Group return rate as the line; each branch/seller against it; this month vs the trailing 6-month rate; and reason mix vs last quarter. |
| **Exception lead** | **"Credited but not returned"** leads whenever above zero — that is either a control failure or a price adjustment disguised as a return, and it is the line an auditor will ask about. Otherwise the branch with the worst return rate leads. |
| **Consolidation level** | Group with branch, seller and product-family breakdown; must roll up. |
| **Cadence** | Monthly; weekly if the rate is deteriorating. |
| **The decision it triggers** | Owner: stop selling a product family whose returns eat its margin. GM: retrain a seller who oversells, or fix the picking process behind "wrong item supplied". CFO: close the credit-note-without-goods loophole. Purchasing (via the buy domain): raise a supplier quality claim. |
| **Tap-through** | The reason breakdown for the worst branch, with product families named. **Refuses** to list individual credit notes on the executive screen. |
| **Alert condition** | Push when the group return rate exceeds 1.5× the trailing 6-month rate in any week, or when any single credit note exceeds TZS 10M. |
| **Data needed** | Credit notes with structured reasons; linkage credit note → original invoice → seller and branch; physical return receipt matched to the credit; product family; restocking outcome (resold / scrapped). ⚠ **Structured return-reason codes** (damaged · wrong item · not ordered · quality · price correction · customer changed mind). ⚠ **Physical return receipt event separate from the credit note** — the gap between them is the finding. ⚠ **Disposition of returned goods** (back to saleable vs scrap) to price the real loss. |
| **Novelty** | **NOVEL.** Returns are almost always reported as a finance number. Treating them as a **sales quality signal** — attributed to a seller, priced at full margin destroyed, and separating paper credits from physical returns — is rare and immediately actionable. |

---

## 17. Big Customer Risk

| field | content |
|---|---|
| **Screen name** | `Big Customer Risk` |
| **Full name** | If Our Biggest Customers Left — share of margin and cover |
| **Archetype** | 10 · Concentration & Exposure |
| **The question it answers** | "How much of this business depends on customers who could walk away, and is that dependence getting worse?" |
| **Key figures** | ① Largest single customer's share of **group margin** (not revenue) ② Top 5 cumulative share of margin ③ The same two shares 12 months ago ④ The board's tolerance line (e.g. "no single account above 15% of margin") and how many accounts breach it ⑤ Cover — months of overhead the top account's margin funds ⑥ Contractual notice period / terms of the top account |
| **The comparison** | Against the same shares a year ago (is dependence deepening?) and against the stated board tolerance drawn across the Pareto. |
| **Exception lead** | Any account above the tolerance line, named, with the year-on-year direction: "Serengeti Traders 22% of margin, up from 16% — above the 15% board limit". |
| **Consolidation level** | Group only, with related parties grouped into one economic customer. A branch-level concentration view is offered because a branch may be 70% dependent on one account even when the group is not — that is the hidden version of this risk. |
| **Cadence** | Quarterly station; monthly glance when a breach exists. |
| **The decision it triggers** | Owner: deliberately diversify, cap the account's exposure, negotiate a longer notice period, or take credit insurance. CFO: reprice risk into the account's terms. GM: build a second account in the same segment. |
| **Tap-through** | The account's 24-month margin history, terms, notice period and payment behaviour. **Refuses** to show their open invoices — this is a strategy screen, not a collections screen. |
| **Alert condition** | Push when any account first crosses the tolerance threshold, or when the top-5 cumulative share rises more than 5 points year on year. |
| **Data needed** | Margin per customer over rolling 12 months; related-party grouping; contract terms and notice periods; overhead run rate for the cover calculation. ⚠ **Related-party / group-of-companies linking** — without it one owner's four trading names hide the concentration entirely, which is the most common way this report lies. ⚠ **Contract notice periods and exclusivity terms** held as data. ⚠ **Board-agreed tolerance threshold.** |
| **Novelty** | CLASSIC in principle, rarely built on **margin** and almost never with related-party grouping. |

---

## 18. New Customers

| field | content |
|---|---|
| **Screen name** | `New Customers` |
| **Full name** | Do New Customers Stay? — value kept at months 3, 6 and 12 |
| **Archetype** | 11 · Cohort & Retention |
| **The question it answers** | "Are the customers we are winning now as good as the ones we won last year, or are we just replacing leavers?" |
| **Key figures** | ① The verdict in words — "Customers won this year are worth 18% less at month 6 than last year's" ② New accounts won this quarter and their first-quarter margin ③ % of cohort still buying at month 6 ④ Margin retained at month 6 as % of month-1 margin ⑤ Net customer movement — won minus lost, by value, this quarter |
| **The comparison** | Cohort against cohort — 5 or 6 quarterly cohorts as small lines, recent highlighted, older ghosted. No external target required; the cohorts compare themselves. |
| **Exception lead** | The verdict sentence at the top, stated in plain language before any chart. If the newest interpretable cohort is worse than its predecessor, that fact is the headline and the count of new customers won is demoted. |
| **Consolidation level** | Group; drillable by acquiring branch and by channel — "route-acquired customers retain worse than counter-acquired" is exactly the kind of finding this earns. |
| **Cadence** | Quarterly. Monthly glance for the net-movement number. |
| **The decision it triggers** | Owner: stop rewarding new-account counts and reward retained value instead. GM: fix onboarding, first-delivery service, and credit screening at acquisition. CFO: reconsider the introductory discount that buys customers who leave when it ends. |
| **Tap-through** | The accounts inside the worst-performing cohort, with the seller who won them. **Refuses** to show cohorts younger than 3 months as conclusions — they are drawn but marked "not yet interpretable". |
| **Alert condition** | No push. Quarterly reading. |
| **Data needed** | First-invoice date per customer; margin per customer per month; acquiring branch, channel and seller. ⚠ **Acquisition source/channel captured at customer creation** — otherwise the most useful cut (which channel wins customers who stay) is impossible. ⚠ **Duplicate customer detection** — the same trader created twice creates a phantom new cohort and a phantom churn. ⚠ **Related-party grouping.** |
| **Novelty** | **NOVEL for an ERP.** Cohort retention is standard in SaaS and absent from almost every trading ERP, yet it is the only report that separates "we are growing" from "we are churning and replacing". |

---

## 19. Shrinking Baskets

| field | content |
|---|---|
| **Screen name** | `Shrinking Baskets` |
| **Full name** | Customers Buying Less Than Before — lines and value lost |
| **Archetype** | 6 · Exception Register |
| **The question it answers** | "Which customers are still buying — so nobody has noticed — but buying less of us than they used to?" |
| **Key figures** | ① Count and money — "26 accounts shrinking · TZS 340M annualised margin drifting away" ② Average decline in **product lines bought** per account (breadth loss) ③ Average decline in value per order (depth loss) ④ The single largest shrinking account and its decline % ⑤ New this month vs shrinking for 3+ months ⑥ Count of shrinking accounts where a competitor is known to have entered |
| **The comparison** | Each account's trailing 3 months against its own trailing 12-month average, on both **breadth** (distinct product families) and **depth** (value per order). Breadth loss usually precedes defection by a quarter — that is the early warning. |
| **Exception lead** | Breadth loss first: "Kigoma Stores now buys 3 of the 11 families it used to — value down only 12% but the relationship is narrowing". A value-only decline is a softer signal than a category walking out of the door. |
| **Consolidation level** | Group, with branch and seller attribution; related parties grouped. |
| **Cadence** | Monthly. |
| **The decision it triggers** | GM assigns a visit with a specific brief: "find out who is supplying them cement now". Owner reprices or reinstates a service level for a named account. Branch Manager checks for a stock-out history in the categories that disappeared — often we lost the category by being out of stock once. |
| **Tap-through** | The account's category-by-category buying over 12 months, showing exactly which families stopped. **Refuses** to show individual invoices. |
| **Alert condition** | Push when a top-50 account loses more than 40% of its category breadth in a quarter. |
| **Data needed** | Per-customer, per-category purchase history by month; product family taxonomy; related-party grouping; stock-out history by branch and category. ⚠ **A clean product family/category taxonomy** — breadth is meaningless if categories are inconsistent. ⚠ **Stock-out events by branch and category** to explain the loss. ⚠ **Competitor-entry notes from sellers** (a simple free-text/flag field on the customer). |
| **Novelty** | **NOVEL.** Dormancy reports catch customers who have already left. This one catches them while they are still buying — the only window in which a visit still works. Owners describe this as the report they have never been able to see. |

---

## 20. Sales We Missed

| field | content |
|---|---|
| **Screen name** | `Sales We Missed` |
| **Full name** | Demand We Could Not Serve — value lost when stock was out |
| **Archetype** | 6 · Exception Register |
| **The question it answers** | "How much did customers try to buy from us and we could not supply — and what did that cost in margin?" |
| **Key figures** | ① Lost sales value and lost margin this month (TZS) ② Number of unserved requests ③ Top 5 items by lost margin ④ Branch with the most lost demand ⑤ Repeat misses — items that were out on 3+ separate days ⑥ Lost demand as % of total sales (the "leak rate") |
| **The comparison** | Against last month's lost margin, against the trailing 6-month rate, and per branch against the group leak rate. Also compared against the working capital that would have been required to hold the stock — the decision needs both sides. |
| **Exception lead** | Repeat misses first — an item out of stock once is bad luck; an item out on 8 days in a month is a reorder-policy failure with a name attached to it. |
| **Consolidation level** | Group with branch breakdown; must roll up, because the same item may be available at another branch — and "we had it 4km away" is its own finding, shown as a separate count. |
| **Cadence** | Monthly, weekly during a supply problem. |
| **The decision it triggers** | Owner authorises working capital for the specific lines with the highest lost margin — the report converts a stock argument into an investment case. GM changes reorder points; Branch Manager enables inter-branch transfer for the "we had it nearby" cases. |
| **Tap-through** | The item's stock-out history across branches with the reorder point and lead time shown. **Refuses** to show the individual customer requests as a list — the pattern per item is the decision unit. |
| **Alert condition** | Push when lost margin in a week exceeds TZS 20M, or when any A-class item is out of stock at 3+ branches simultaneously. |
| **Data needed** | Captured unserved demand — requests, enquiries and order lines that could not be fulfilled; stock position by branch and time; item margin; reorder points and lead times. ⚠ **Unserved demand capture at the counter and on the van** — the single largest build item in this domain: a one-tap "customer asked, we did not have it" on POS and the route app. ⚠ **Stock-out event history** (a time series of zero on-hand), not just current stock. ⚠ **Quotation lines lost to stock** (shared with report 15). |
| **Novelty** | **NOVEL.** Almost no ERP measures demand it failed to serve, because the transaction never happened. It is the largest invisible number in a trading business and it makes the case for working capital better than any stock report. |

---

## 21. Sales Mix Shift

| field | content |
|---|---|
| **Screen name** | `Sales Mix Shift` |
| **Full name** | Which Way the Sales Mix Is Going — by channel, 24 months |
| **Archetype** | 8 · Trend & Trajectory |
| **The question it answers** | "Is the shape of my business changing — are we drifting from cash counter sales into credit, and is that deliberate?" |
| **Key figures** | ① The verdict in words — "Credit sales rising, 5th consecutive month" or "Mix stable within normal range" ② Channel shares this month: counter · credit · route · project ③ The channel with the largest 12-month shift, in points ④ Margin rate by channel ⑤ Cash-converted share (sales collected within 7 days) |
| **The comparison** | A normal-range band behind each channel's share line (12-month mean ± 1 SD), plus the same month last year for seasonality. 24 monthly points, never 3. Event markers for price rises, branch openings and credit-policy changes. |
| **Exception lead** | Any channel outside its band for 3 consecutive months, stated in words above the chart. A mix that is inside band produces a calm "no meaningful shift" — and that silence is information. |
| **Consolidation level** | Group; decomposable by branch (a single branch drifting into credit is often the real story hidden inside a stable group mix). |
| **Cadence** | Monthly. |
| **The decision it triggers** | Owner: whether the drift toward credit was a decision or an accident, and whether to re-set credit authority. CFO: working-capital consequence of the shift. GM: rebalance incentives if sellers are choosing the easier channel. |
| **Tap-through** | The same series decomposed by branch. **Refuses** to include the incomplete current month in the trend — it is drawn separately and excluded from the verdict. |
| **Alert condition** | Push when any channel's share moves outside its band for 3 consecutive months. |
| **Data needed** | Sales and margin classified by channel; collection timing per sale; 24 months of history; event log of policy changes. ⚠ **Channel classified on every sale** (counter / credit / route / project / online) — commonly inferred badly from payment type. ⚠ **A dated event log of business changes** (price rise, branch opened, credit policy changed) to annotate the trend, otherwise the owner re-asks "what happened in March?" every time. |
| **Novelty** | CLASSIC |

---

# TIER 3 — specialist, on demand

---

## 22. Costly Customers

| field | content |
|---|---|
| **Screen name** | `Costly Customers` |
| **Full name** | Customers Who Cost More Than They Pay — margin after serving |
| **Archetype** | 6 · Exception Register |
| **The question it answers** | "Which customers look profitable on the invoice but lose me money by the time I have delivered, chased and taken their returns?" |
| **Key figures** | ① Count and money — "9 accounts · TZS 74M of true margin destroyed" ② Gross margin vs margin after cost-to-serve, per account ③ The largest cost-to-serve component (delivery trips · returns · collection effort · small-order handling) ④ Average order size for the affected accounts ⑤ Count of accounts that flip from profitable to loss-making once serving cost is applied |
| **The comparison** | Each account's invoice margin against its margin after serving cost, and against the group's average cost-to-serve ratio. The gap between the two margins is the report. |
| **Exception lead** | Accounts that flip sign — profitable on paper, loss-making in truth — named first with the flip amount. |
| **Consolidation level** | Group with related parties grouped; branch view for the branch manager who actually makes the deliveries. |
| **Cadence** | Quarterly, or on demand before a contract renewal. |
| **The decision it triggers** | Owner/CFO: impose a minimum order value, charge for delivery, move the account to collection-on-delivery, or resign the account. GM: consolidate delivery days for that customer's area. |
| **Tap-through** | The cost build-up for one account — trips, returns, credit-chasing contacts, small orders. **Refuses** to show a single blended "overhead allocation" number; every cost component must be traceable to a real event or it is not shown. |
| **Alert condition** | No push. On-demand and pre-renewal. |
| **Data needed** | Margin per customer; delivery trips and drops per customer; returns per customer; collection contacts/effort per customer; order count and average order value. ⚠ **Delivery cost per trip/drop** — needs vehicle running cost and a drop count, rarely present. ⚠ **Collection effort logged** (calls, visits, reminders per customer). ⚠ **An agreed cost-to-serve model signed off by the CFO** — without agreement the report is argued with rather than acted on. |
| **Novelty** | **NOVEL.** Cost-to-serve customer profitability is standard in distribution consulting and essentially absent from mid-market ERPs. It routinely reveals that the "best" customer by revenue is the worst by cash. |

---

## 23. One Seller Risk

| field | content |
|---|---|
| **Screen name** | `One Seller Risk` |
| **Full name** | If Our Best Seller Left — the customers only they hold |
| **Archetype** | 10 · Concentration & Exposure |
| **The question it answers** | "How much of my customer base is held by one person's relationship, and what walks out with them if they resign?" |
| **Key figures** | ① Largest single seller's share of group margin ② Value of margin from customers who have **only ever** dealt with that one seller ③ Top 5 sellers' cumulative margin share ④ Count of top-50 customers with a single point of contact ⑤ The board tolerance line and how many sellers breach it ⑥ The share a year ago |
| **The comparison** | Against the same shares 12 months ago (is the dependence hardening?) and against a stated tolerance ("no seller above 20% of margin, no top-50 customer with a single contact"). |
| **Exception lead** | The named seller above tolerance, with the exclusive-relationship margin behind them: "One seller holds TZS 610M of margin, TZS 380M of it from customers no one else has served". |
| **Consolidation level** | Group and per branch — the branch version is often more alarming, since a small branch may be one person. |
| **Cadence** | Twice a year, or immediately on a resignation. |
| **The decision it triggers** | Owner: deliberately introduce a second contact to the exclusive accounts, restructure incentives so relationships are shared, or move a key seller onto a notice-period contract. GM: rotate account coverage before, not after, a resignation. |
| **Tap-through** | The exclusive-relationship customer list for the top-risk seller, with each account's annual margin. **Refuses** to display the seller's own performance metrics here — that is Seller League; mixing risk with performance makes this look punitive. |
| **Alert condition** | Push immediately when a seller with more than 10% of group margin resigns or is flagged as leaving. |
| **Data needed** | Seller attribution on every sale over 24 months; count of distinct sellers per customer; margin per seller. ⚠ **Consistent seller attribution on counter/POS sales**. ⚠ **Contact/visit history per customer** to distinguish "invoiced by" from "the relationship is theirs". ⚠ **Board tolerance threshold.** |
| **Novelty** | **NOVEL.** Supplier and customer concentration are occasionally reported; concentration of the *relationship* in one employee is almost never quantified, yet it is how trading businesses actually lose customers overnight. |

---

## 24. Order Book Match

| field | content |
|---|---|
| **Screen name** | `Order Book Match` |
| **Full name** | Does What We Delivered Match What We Billed? — gaps by age |
| **Archetype** | 13 · Reconciliation & Assurance |
| **The question it answers** | "Can I believe the sales numbers — has everything we delivered been invoiced, and everything invoiced actually delivered?" |
| **Key figures** | ① The verdict and difference — "Out by TZS 31M across 46 documents" ② Delivered not invoiced (TZS and count, with age) ③ Invoiced not delivered (TZS and count, with age) ④ Invoiced not fiscalised — sales with no EFD receipt (count, value, days open) ⑤ Oldest unresolved gap in days ⑥ Unexplained portion, isolated from legitimate timing |
| **The comparison** | The two sides side by side, with **age of the difference** given more weight than its size. A one-day timing gap is expected and stated; a 40-day gap is a control failure. Compared against the same difference last month to show whether it is clearing. |
| **Exception lead** | The **unexplained** portion, separated from timing and in-transit. And any invoiced-not-fiscalised item, because that one carries regulatory consequence. |
| **Consolidation level** | Company (fiscal obligations are per company/TIN), rolling to group; branch breakdown to locate the source. |
| **Cadence** | Monthly at close; weekly for the fiscal-receipt line. |
| **The decision it triggers** | CFO: stop trusting the month's sales number until it clears, and assign the clearing with a name and a date. Owner: investigate a branch or till with repeat unexplained gaps — this is the archetype that catches revenue walking out. GM: fix the despatch-without-invoice habit at a named branch. |
| **Tap-through** | The unmatched items in the worst reason group, each with an owner responsible for clearing. **Refuses** to reconcile things that are not required to agree — no false red. |
| **Alert condition** | Push when unexplained (non-timing) difference exceeds TZS 5M, or when any invoiced-not-fiscalised item passes 3 days. |
| **Data needed** | Delivery notes / goods-issued events; sales invoices; the link between them; fiscal receipt confirmations; documented reason classification for each gap. ⚠ **Delivery note as a separate posted event linked to the invoice** — where invoicing and despatch are the same act, the report must say so and narrow its scope. ⚠ **Reason classification with a write-back** so timing is separable from unexplained. ⚠ **Fiscal/EFD confirmation status per invoice** (may be shared with the Tax domain — coordinate to avoid a twin). |
| **Novelty** | **NOVEL as an executive screen.** Finance teams reconcile this in spreadsheets; putting the *unexplained* portion and its age in front of the owner is what makes every other sales report in the suite believable. |

---

## 25. New Branch Ramp

| field | content |
|---|---|
| **Screen name** | `New Branch Ramp` |
| **Full name** | Do New Branches Reach the Group Rate? — months to par |
| **Archetype** | 11 · Cohort & Retention |
| **The question it answers** | "When I open a branch, how long does it take to perform like the rest — and are the recent openings ramping better or worse than the older ones?" |
| **Key figures** | ① The verdict — "Branches opened this year reach 60% of group sales-per-day at month 6; the 2024 openings reached 85%" ② Months to break even, by cohort ③ Sales per selling day as % of group rate at months 3, 6, 12 ④ Margin rate at month 6 vs group ⑤ Cumulative investment still unrecovered for the newest cohort |
| **The comparison** | Cohort against cohort — branches grouped by opening year/half — plus the group rate as the "par" line every cohort is climbing toward. |
| **Exception lead** | The newest interpretable cohort's shortfall against its predecessor, stated in words. If a specific branch is more than 6 months behind its cohort's curve, it is named. |
| **Consolidation level** | Group, ranking branch cohorts; a single branch can be traced against its cohort curve. |
| **Cadence** | Quarterly; on demand when an expansion decision is on the table. |
| **The decision it triggers** | Owner: whether to keep opening branches, and what the honest payback period is for the next site's business case. GM: intervene at a branch falling behind its cohort curve while it is still month 4, not month 14. CFO: set realistic first-year plans for new sites instead of copying a mature branch's plan. |
| **Tap-through** | One branch's own ramp against its cohort's median curve. **Refuses** to include branches under 3 months as conclusions — they are drawn and marked not yet interpretable. |
| **Alert condition** | No push. Opened when an expansion decision is live. |
| **Data needed** | Branch opening dates; sales, margin and selling days per branch per month since opening; setup investment and monthly running cost per branch. ⚠ **Branch opening date and setup investment held as data** — usually in someone's memory. ⚠ **Branch-level running cost** for break-even. ⚠ **Branch class** (mall counter vs depot) so cohorts compare like with like. |
| **Novelty** | **NOVEL.** Expansion decisions in trading groups are almost always made on the memory of the last opening. A cohort ramp curve turns them into evidence, and it is the report that tells an owner honestly whether growth is working. |

---

# Notes for the catalogue editor

**Matrix cells claimed by this domain.** Sales column: Flash ●, Scorecard ●, Variance Bridge ● (×2), League Table ● (×3 incl. Route/Seller), Exception Register ● (×4), Trend ●, Forecast ●, Concentration ● (×1, plus a seller-risk cell), Cycle-Time ● (×2), Decision Docket ●, Reconciliation ○. Customer column: League Table ●, Concentration ●, Cohort ● (×2), Exception Register ○ (×2). One new cell is argued for: **Cohort × Sales-unit ramp** (report 25), which the matrix does not currently tick — it is a genuine cohort, of branches rather than customers, and it earns its place.

**Twin-test collisions to resolve with sibling domains before names ship (R9).**
- `Branch League` (3) — the Profit domain may claim a branch ranking on net profit. Resolution: mine ranks on **sales achievement and margin rate**, theirs on **profit after allocated cost**. If both survive, rename theirs `Branch Profit League` and keep mine as `Branch League`; do not let both start with the same two words on different rules.
- `Orders Waiting` (6) vs the Approvals domain's generic `Waiting on You`. Resolution: the generic docket is the home tile; mine is its sales-specific instance and should appear only inside the Sales section, or be merged as a filter of the generic docket.
- `Order Book Match` (24) shares its fiscal-receipt line with the Tax domain's `EFD Gaps`. Resolution: Tax owns the fiscal-receipt register; my screen shows the count and value only, and taps through to theirs.

**The five build items that unlock the most reports, in order.**
1. **Sales targets by branch × channel × seller × month, versioned and approved.** Gates reports 2, 3, 4, 5, 8, 10 — over a third of the suite.
2. **Unserved-demand capture at POS and on the van** (one tap: "asked for, not available"). Unlocks report 20 and the strongest bar in report 15.
3. **Structured quotation loss reasons at quote closure.** Unlocks report 15; without it that report is a residual bar.
4. **Effective-dated list prices on every sellable item, plus bonus/free goods valued as a giveaway.** Unlocks report 7 and half of report 4.
5. **Related-party customer grouping and reliable seller attribution on counter sales.** Silently corrects reports 9, 11, 17, 18, 19, 23 — each of which currently lies in a specific, predictable way without them.

===== DOMAIN: buy-make — PROCUREMENT & PRODUCTION =====
# Procurement & Production — Executive Report Suite

**OrbixERP Executive Mobile · Domain pack: Buying & Making · v1 · 2026-08-18**
25 reports, designed from first principles against the archetype doctrine. Ignores the existing endpoint inventory by instruction; every ⚠ in "Data needed" is a build item, not a reason to cut.

**Matrix cells claimed:** Variance Bridge × Buy ●, × Make ● · Exception × Buy ●, × Make ○ · League × Buy ○ · Scorecard × Make ● · Flash × Make ○ · Forecast × Buy ●, × Make ○ · Concentration × Buy ●, × Make ○ · Reconciliation × Buy ●, × Make ○ · Cycle-Time × Buy ●, × Make ● · Ageing × Make ○ · Docket × Buy ● · Trend × Make ● · **Cohort × Buy — argued new cell, case stated at report 24.**

**Tiers:** TIER 1 = 10 (opened weekly or more) · TIER 2 = 11 (monthly or on alert) · TIER 3 = 4 (specialist / on demand).
**Novelty:** 10 NOVEL, 15 CLASSIC.

---

# TIER 1 — the owner opens these weekly or more

---

## 1 · Today on the Floor

| field | content |
|---|---|
| **Screen name** | `Today on the Floor` (18 chars — the doctrine's own canonical Flash instantiation; the 4-word count is sanctioned there) |
| **Full name** | Today on the Floor — output, stoppages and scrap against a normal shift |
| **Archetype** | 1 Flash |
| **The question it answers** | "Is the factory running normally today, or has something gone wrong that I should call about before lunch?" |
| **Key figures** | (1) Good units produced so far today, in the owner's unit (cases / tonnes / cartons — not "quantity"); (2) % of a normal shift's run-rate at this hour; (3) Minutes stopped today, with the largest single stoppage named; (4) Scrap % of input today; (5) Shifts running now vs shifts planned |
| **The comparison** | Same weekday of last week, and the median of the last 8 same-shifts at the same hour of the shift. Never "vs yesterday" — Sunday shifts and half-days poison that baseline. |
| **Exception lead** | If run-rate is under 80% of normal at this hour, the headline flips from output to the named cause: "Line 2 down 96 min — no steam". Colour appears only on deviation. |
| **Consolidation level** | Factory/plant level; rolls up to company for a multi-plant group. Not branch. |
| **Cadence** | Glance daily — twice on a bad day. |
| **The decision it triggers** | Phone the Factory Manager before the shift is lost; authorise overtime or a second shift tonight to protect a customer despatch. **Owner / GM.** |
| **Tap-through** | The line strip — same three numbers per line/machine. **Refuses** to show individual work orders, batch numbers or operator names. |
| **Alert condition** | Push when run-rate < 70% of the same-shift median at the 4-hour mark, or a single stoppage passes 60 minutes. One push per shift maximum. |
| **Data needed** | Live good-output count per line by hour; shift calendar and planned shifts; ⚠ **machine stoppage events with start/stop times and reason codes** (rarely captured — usually a paper log); ⚠ **normal shift run-rate standard per line/product**; scrap/reject quantity booked during the shift. |
| **Novelty** | CLASSIC |

---

## 2 · Factory Against Plan

| field | content |
|---|---|
| **Screen name** | `Factory Against Plan` (20) |
| **Full name** | Factory Against Plan — output, yield, downtime and unit cost against the standard we set |
| **Archetype** | 2 Scorecard |
| **The question it answers** | "Is the factory meeting the standard we agreed, and which single miss deserves my attention this week?" |
| **Key figures** | Verdict headline "4 of 6 on plan". Then six fixed rows, each actual / target / gap: (1) Good output vs production plan; (2) Yield % vs standard yield; (3) Downtime hours vs allowed; (4) Cost per unit vs standard cost; (5) Capacity used % vs planned utilisation; (6) On-time completion of works orders vs promise |
| **The comparison** | The printed target on every row — the production plan and standard cost approved for the period. The measure set never changes month to month. |
| **Exception lead** | Rows ordered by size of miss in money, not by importance. The biggest miss sits at the top with its TZS impact, not just its percentage. |
| **Consolidation level** | Plant; rolls up to company and group with each plant's own targets preserved. |
| **Cadence** | Weekly; formally at month-end. |
| **The decision it triggers** | Which one miss gets management attention this week — and, twice a year, whether the plan or the performance is wrong. **GM, with Owner arbitrating plan-vs-performance.** |
| **Tap-through** | Any red row opens its Variance Bridge (report 4, 10 or 11). This pairing is the backbone. **Refuses** to show works-order detail. |
| **Alert condition** | No push. This is a standing station, not an event. |
| **Data needed** | Production plan by product and period; actual good output; ⚠ **standard yield and standard cost per product, version-dated**; downtime hours; ⚠ **planned available capacity hours per line**; works-order promise dates vs completion. |
| **Novelty** | CLASSIC |

---

## 3 · Buy Price Gap

| field | content |
|---|---|
| **Screen name** | `Buy Price Gap` (13) |
| **Full name** | Where the Purchase Price Went — this month against agreed price, by cause |
| **Archetype** | 4 Variance Bridge |
| **The question it answers** | "We spent more than we should have on buying — how much of that is prices, how much is buying different things, and how much is somebody not following the price we negotiated?" |
| **Key figures** | Headline is the gap: "Bought TZS 84M above agreed price". Bars, largest first: (1) Supplier price increases on contracted items; (2) Off-agreement buying (paid above the agreed price for no stated reason); (3) Mix — bought the dearer grade/brand; (4) Volume at the old price vs new; (5) FX movement on imported lines; (6) Residual (must stay under 8% of the gap). |
| **The comparison** | Built into the form: last agreed price (or last quarter's weighted average where no agreement exists) → price actually paid. |
| **Exception lead** | The largest bar is the headline sentence, with the top supplier inside it named: "TZS 31M of it is one supplier's cement price, up 14% on the agreement." |
| **Consolidation level** | Group headline, decomposable by company and by buying branch. Must roll up — a group buying 12% above agreement across nine branches is invisible branch by branch. |
| **Cadence** | Weekly glance, month-end formally. |
| **The decision it triggers** | Renegotiate a named supplier, or withdraw a named branch's buying authority. **Owner (renegotiation) / CFO (authority).** |
| **Tap-through** | Each bar opens its League Table — which supplier and which branch caused it. **Refuses** to show individual PO lines. |
| **Alert condition** | Push when the month-to-date gap exceeds TZS 40M **or** 3% of month-to-date purchase spend, whichever comes first. |
| **Data needed** | Purchase receipts with price, quantity, item, supplier, branch; ⚠ **an agreed/contract price list per supplier-item with effective dates** (the single most commonly missing procurement asset); FX rate at order and at invoice; item grade/brand hierarchy for the mix term. |
| **Novelty** | CLASSIC — but only the price-agreement baseline makes it executive rather than accounting. |

---

## 4 · Unit Cost Gap

| field | content |
|---|---|
| **Screen name** | `Unit Cost Gap` (13) |
| **Full name** | Why a Unit Costs More — standard cost to actual, by material, labour and overhead |
| **Archetype** | 4 Variance Bridge |
| **The question it answers** | "Every case now costs me more to make than we planned — is that the materials, the people, or the factory standing idle?" |
| **Key figures** | Headline: "TZS 1,840 more per case than standard (+11%)". Bars: (1) Material price; (2) Material usage/yield; (3) Labour rate; (4) Labour efficiency (hours per unit); (5) Overhead absorption (volume shortfall); (6) Residual. Plus total money impact for the month's volume — the per-unit number is the diagnosis, the total is the consequence. |
| **The comparison** | Standard cost card for the product, version-dated, vs actual cost of the period's production. |
| **Exception lead** | The dominant bar leads, named in plain words: "Most of it is idle factory — we made 62% of plan, so the overhead sits on fewer cases." |
| **Consolidation level** | Product and product family; rolls to plant and company. |
| **Cadence** | Month-end; weekly for the top 3 products in a volatile period. |
| **The decision it triggers** | Reprice the product, change the recipe/supplier, or fill the factory. **Owner + CFO jointly — this is the report that decides whether a price rise is passed to customers.** |
| **Tap-through** | The worst product's own bridge; then the material inside it. **Refuses** to show batch-level costing. |
| **Alert condition** | Push at month-end close when actual unit cost exceeds standard by more than 10% on any product carrying over 15% of production value. |
| **Data needed** | ⚠ **Standard cost card per product (material, labour, overhead), version-dated**; bill of materials; actual material consumption per works order; ⚠ **actual labour hours booked to works orders** (usually absent — payroll knows hours, production does not); overhead pool and absorption basis; actual production volume. |
| **Novelty** | CLASSIC |

---

## 5 · Late Orders

| field | content |
|---|---|
| **Screen name** | `Late Orders` (11) |
| **Full name** | Orders Past Their Promise — value, days late, and what is waiting on them |
| **Archetype** | 6 Exception Register |
| **The question it answers** | "What did we order and pay for that has not arrived, and what is it holding up?" |
| **Key figures** | "17 orders · TZS 620M · oldest 41 days late · 4 blocking production". Then: new vs repeat vs cleared this week; and per line — supplier, item, value, days past promise, what it blocks (a customer order, a works order, or nothing). |
| **The comparison** | The count and value seven days ago, and the repeat share. An order that has been late for three weeks is a supplier-relationship failure, not a delay. |
| **Exception lead** | Ranked by consequence, not by age: orders blocking a confirmed customer despatch sit above older orders blocking nothing. |
| **Consolidation level** | Group, split by buying company/branch; must roll up. |
| **Cadence** | Weekly; daily during a peak build (Christmas, Ramadan stock-up). |
| **The decision it triggers** | Cancel and re-source, or escalate to the supplier's owner; release a deposit only against a firm new date. **GM / Procurement Lead; Owner on the top 3 by value.** |
| **Tap-through** | The supplier's own delivery history — how often they hit a promised date. Includes an **accept** action (a known, agreed slip stops cluttering the register). **Refuses** to show the PO document itself. |
| **Alert condition** | Push when a single overdue order exceeds TZS 50M, or when anything blocking a confirmed customer despatch passes 3 days late. |
| **Data needed** | Purchase orders with quantity outstanding; ⚠ **the supplier's confirmed promise date, separate from the date we requested** (most ERPs hold only the requested date, which makes every "late" figure arguable); goods receipts against orders; ⚠ **the link from a purchase to what it is for** (works order or customer order) — without it "what it blocks" is guesswork. |
| **Novelty** | CLASSIC — the promise-date/request-date separation is what most implementations get wrong. |

---

## 6 · Supplier League

| field | content |
|---|---|
| **Screen name** | `Supplier League` (15) |
| **Full name** | Supplier League — on time, in full, at the agreed price, and what they cost us when they fail |
| **Archetype** | 5 League Table |
| **The question it answers** | "Which suppliers actually deliver, and which ones am I paying a premium to for a worse service?" |
| **Key figures** | Spread headline: "Best 97% on time, worst 41%, group 78%". Rows: supplier, on-time-in-full %, price against agreement %, rejection %, and a single **cost-of-failure** figure in TZS (late-delivery downtime + rejected value + premium paid). Top 3 and bottom 3, middle collapsed ("+22 suppliers between"), each with a rank-change arrow. |
| **The comparison** | The group OTIF rate printed as a reference line on every row; and rank movement since last quarter. |
| **Exception lead** | A supplier that has fallen more than 5 rank places leads, before the chronic bottom — a collapsing supplier and a chronically poor one need different calls. |
| **Consolidation level** | Group. Suppliers are grouped by owning party — one owner trading under three names must appear once. Only suppliers with ≥ 5 receipts in the window are ranked; the rest sit in a "too few deliveries to rank" note. |
| **Cadence** | Monthly; quarterly for the formal review. |
| **The decision it triggers** | Move volume from the bottom to the top of the table; put a supplier on notice; award the annual contract. **Owner / GM.** |
| **Tap-through** | The supplier's scorecard — their own trend on the four measures. **Refuses** to name the buyer who placed the orders (R7 — name the condition, not the person; the buyer view is a separate, non-executive screen). |
| **Alert condition** | No push. Reviewed on cadence; single failures are caught by report 5. |
| **Data needed** | Receipts vs ordered quantity and promise date; ⚠ **inspection/rejection outcomes per receipt with reason**; agreed price list; ⚠ **related-party grouping of suppliers** (without it, concentration and league both lie); ⚠ **downtime attributable to a missing material** for the cost-of-failure figure. |
| **Novelty** | CLASSIC — the TZS cost-of-failure column is the part most ERPs never build. |

---

## 7 · Off-Contract Buys

| field | content |
|---|---|
| **Screen name** | `Off-Contract Buys` (17) |
| **Full name** | Bought Outside the Rules — spend that skipped the agreement, the approval, or the approved supplier |
| **Archetype** | 6 Exception Register |
| **The question it answers** | "How much of my money was spent without going through the process I set, and who keeps doing it?" |
| **Key figures** | "TZS 148M outside the rules this month — 9% of spend". Broken by breach type, ranked by money: (1) Bought from an unapproved supplier; (2) Bought above the agreed price with no waiver; (3) Goods received with no purchase order raised first (retro-PO); (4) Order split below the approval limit (two POs to the same supplier, same day, same item); (5) Bought while a contracted supplier had stock. |
| **The comparison** | The same share last month, and the repeat share by branch — a branch that breaches every month is a control failure, not an incident. |
| **Exception lead** | Order-splitting leads whenever present, regardless of value. It is the only breach type on this list that is deliberate. |
| **Consolidation level** | Group; ranked by company and branch. Must roll up — this is invisible at branch level by design of the person doing it. |
| **Cadence** | Weekly glance, monthly action. |
| **The decision it triggers** | Withdraw a branch's buying authority, or lower an approval limit; occasionally, raise a limit that is provably unrealistic and is being routed around. **CFO proposes, Owner decides.** |
| **Tap-through** | The breaching branch's own history and its top 5 off-contract lines by value, with an **accept-with-reason** action. **Refuses** to show the requisitioner's name on the executive screen. |
| **Alert condition** | Push immediately on a detected order-split above TZS 20M combined; otherwise weekly digest. |
| **Data needed** | ⚠ **An approved-supplier list with effective dates**; ⚠ **contract/agreement coverage flag per item**; approval limits by role and branch; PO creation timestamp vs goods-receipt timestamp (to detect retro-POs); ⚠ **a waiver record** so legitimate exceptions can be accepted rather than re-reported forever. |
| **Novelty** | NOVEL for an SME group — order-split and retro-PO detection are almost never implemented outside large-corporate procurement suites, and they are exactly where leakage lives in a family trading business. |

---

## 8 · Buys Waiting

| field | content |
|---|---|
| **Screen name** | `Buys Waiting` (12) |
| **Full name** | Buying Decisions Waiting on You — value, and what each delay is costing |
| **Archetype** | 14 Decision Docket |
| **The question it answers** | "What buying decisions are sitting on me, and what breaks if I leave them until Monday?" |
| **Key figures** | "11 items · TZS 512M · oldest 6 days · 3 blocking production". Each line: what, who asked, value, why it needs *you* (over limit / above last price / unapproved supplier / import deposit), and the consequence of waiting (line stops on Thursday; price quote expires tomorrow; vessel sails Friday). |
| **The comparison** | Each item against its own policy — last price paid, agreed price, remaining budget for that category — and the queue against your own median approval time. |
| **Exception lead** | Ranked by consequence of delay, never by arrival order. An expiring quote outranks an older routine order. |
| **Consolidation level** | Personal — addressed to the logged-in approver, across all companies they hold authority in. |
| **Cadence** | Glance daily. |
| **The decision it triggers** | The approval itself; and over a quarter, a delegation decision — anything approved unchanged 20 times running should have its limit raised. **Owner / CFO / GM per limit.** |
| **Tap-through** | Approve / reject / send back with reason, plus the one fact needed to decide: last price paid for this item and the supplier's OTIF. **Refuses** to become an inbox — no informational items, ever. |
| **Alert condition** | Push when an item blocking production or a vessel passes 24 hours, and a single morning digest if the queue value exceeds TZS 100M. |
| **Data needed** | Approval queue with requester, value, category, timestamps; approval limits; last price paid per item; supplier OTIF; ⚠ **quote validity/expiry dates**; ⚠ **the consequence link** — what production run or customer order the purchase serves; ⚠ **shipment cut-off / vessel dates for imports** (a Tanzanian import business lives or dies on these and no standard ERP holds them). |
| **Novelty** | CLASSIC as a queue; NOVEL in ranking by cost of delay rather than age. |

---

## 9 · Line Stoppers

| field | content |
|---|---|
| **Screen name** | `Line Stoppers` (13) |
| **Full name** | What Stops the Line First — the material that runs out before it can be replaced |
| **Archetype** | 9 Forecast & Runway |
| **The question it answers** | "Given what we plan to make, which material runs out first — and on what date does the factory stop?" |
| **Key figures** | Headline is a date: "Line 1 stops on 4 September (12 working days) — packaging film". Then the top 5 materials by **days of cover minus replenishment lead time**, each with: days of cover at planned consumption, supplier lead time, whether an order is already placed and its promise date, and the production value at risk in TZS. |
| **The comparison** | Cover against that material's own lead time (the only comparison that matters — 30 days of cover is comfortable for a local item and a crisis for an import), and against the previous run of this forecast (drift means the plan or the consumption changed). |
| **Exception lead** | Only materials whose cover is shorter than their lead time appear at all. If none do, the screen says "Nothing stops the line inside 60 days" and shows the tightest three anyway. |
| **Consolidation level** | Plant, with a group view where materials are shared and can be transferred between plants/branches. |
| **Cadence** | Glance daily during a build; weekly otherwise. |
| **The decision it triggers** | Place the order today, air-freight instead of sea, or re-sequence the production plan onto a product whose materials are in stock. **GM / Factory Manager; Owner when it means airfreight money.** |
| **Tap-through** | The material's own cover math — consumption rate, open orders, in-transit — and the works orders at risk. **Refuses** to show stock ledger transactions. |
| **Alert condition** | Push when any material's cover drops below its lead time + 5 days, or when a stop date moves earlier by more than 3 days between runs. |
| **Data needed** | Bill of materials; ⚠ **the forward production plan/schedule, not just history** (the difference between this report and a reorder listing); on-hand by location; open purchase orders with promise dates and in-transit quantity; ⚠ **actual supplier lead time per item, measured not assumed** (order date to receipt, 12-month median, plus its variability); ⚠ **customs/clearance days as a separate leg for imports** — Dar clearance is the volatile half of an import lead time and is never modelled. |
| **Novelty** | **NOVEL** — most ERPs offer reorder-point alerts on a single item. This one answers the executive's actual question: *the date the factory stops*, and which single item causes it. |

---

## 10 · Factory Hours

| field | content |
|---|---|
| **Screen name** | `Factory Hours` (13) |
| **Full name** | Where the Factory Hours Went — paid hours to producing hours, by cause |
| **Archetype** | 4 Variance Bridge |
| **The question it answers** | "I pay for the factory to be open — how many of those hours actually made something, and where did the rest go?" |
| **Key figures** | Headline: "Of 2,080 paid hours, 1,010 made product (49%)". Waterfall from paid/available hours down: (1) Not scheduled (no plan to run); (2) Changeover and setup; (3) Breakdown; (4) No material; (5) No power / utilities; (6) Quality re-runs; (7) = Producing hours. Each bar also priced in TZS at the hour's fully-loaded cost. |
| **The comparison** | Against the planned utilisation for the period, and against the same month last year. The percentage is meaningless alone; the money on each bar is what moves an owner. |
| **Exception lead** | The largest non-producing bar leads, with its money: "TZS 38M of paid time went to changeovers — we ran 41 short batches." |
| **Consolidation level** | Line, then plant, then company. Rolls up; the group number is the one that gets a capex conversation started. |
| **Cadence** | Monthly; weekly during a capacity squeeze. |
| **The decision it triggers** | Buy capacity, or stop wasting the capacity already paid for — longer runs, a second shift, a generator, a maintenance contract. **Owner (capex) / GM (scheduling).** This is the report that stops a premature machine purchase. |
| **Tap-through** | The worst cause's own trend across 12 months. **Refuses** to show shift-by-shift logs or operator attendance. |
| **Alert condition** | No push. Structural, not urgent. |
| **Data needed** | ⚠ **Shift calendar and paid/available hours per line**; ⚠ **stoppage events with duration and reason code, reconciled to cover the whole shift** (the hard part: unlogged time must land in a named bucket, not vanish); changeover events; ⚠ **fully-loaded hourly cost per line** (labour + overhead + energy); production hours from works orders. |
| **Novelty** | **NOVEL** — factories track OEE for engineers; almost none convert the same data into a money waterfall an owner can read in 20 seconds and act on with a capex decision. |

---

# TIER 2 — valuable; opened monthly or on alert

---

## 11 · Material Loss

| field | content |
|---|---|
| **Screen name** | `Material Loss` (13) |
| **Full name** | Where the Materials Went — input to finished goods, by yield, scrap, rework and unexplained loss |
| **Archetype** | 4 Variance Bridge |
| **The question it answers** | "For every hundred kilos I put in, how much came out as sellable product — and what happened to the rest?" |
| **Key figures** | Headline: "TZS 96M of material did not reach a sellable case this month". Waterfall: input at cost → (1) standard process loss (expected, shown but not blamed); (2) scrap above standard; (3) rework; (4) giveaway/overfill; (5) **unexplained loss**; = output at cost. Yield % printed against standard yield. |
| **The comparison** | Standard yield per product, and last quarter's actual. Standard process loss is separated from excess loss so the report does not blame physics. |
| **Exception lead** | Unexplained loss always leads when it exceeds 1% of input value, regardless of size — it is the only bar that can be theft. |
| **Consolidation level** | Product and line; rolls to plant. |
| **Cadence** | Monthly; weekly for high-value inputs. |
| **The decision it triggers** | Fix a machine setting, retrain a shift, tighten a fill weight, or open an investigation. **GM / Factory Manager; Owner when unexplained loss appears twice running.** |
| **Tap-through** | The worst product's input-by-input breakdown. **Refuses** to show individual batch records. |
| **Alert condition** | Push when unexplained loss exceeds TZS 10M in a month, or yield falls more than 3 points below standard on any product for two consecutive weeks. |
| **Data needed** | Issues to production by material; ⚠ **standard yield / expected process loss per product**; finished output; ⚠ **scrap and rework quantities with reason codes** (usually recorded as a single unexplained adjustment); ⚠ **fill weight / overfill capture** for packed goods. |
| **Novelty** | CLASSIC — with the unexplained-loss bar isolated, which is where it becomes an executive control report rather than a costing statement. |

---

## 12 · Landed Cost Gap

| field | content |
|---|---|
| **Screen name** | `Landed Cost Gap` (15) |
| **Full name** | What Landing the Goods Really Costs — invoice price to shelf cost, by freight, duty, clearing and delay |
| **Archetype** | 4 Variance Bridge |
| **The question it answers** | "I negotiated a good price — so why does this item cost so much by the time it reaches my shelf?" |
| **Key figures** | Headline: "Landed cost is 34% above invoice price — 6 points worse than last quarter". Bars from invoice price to landed cost: (1) Freight; (2) Duty and VAT deferment cost; (3) Clearing and agent fees; (4) **Demurrage and storage** (the avoidable one); (5) Inland transport; (6) FX movement between order and payment; (7) Insurance/other. Each as % of invoice value and in TZS. |
| **The comparison** | The same buildup for the prior quarter, and against the landed-cost assumption used when the selling price was set — the gap between them is margin quietly leaking. |
| **Exception lead** | Demurrage leads whenever it is non-zero. It is pure avoidable loss and it indicts a process, not a market. |
| **Consolidation level** | Item category and supplier/origin; rolls to company. Import-heavy companies only. |
| **Cadence** | Monthly; per-shipment on alert for large consignments. |
| **The decision it triggers** | Change clearing agent, change Incoterm, pre-fund duty, or re-price the product because the pricing assumption was wrong. **CFO + Owner.** |
| **Tap-through** | The worst consignment's own buildup. **Refuses** to show individual clearing invoices. |
| **Alert condition** | Push when demurrage on any consignment exceeds TZS 5M, or when landed-cost uplift on a category moves more than 5 points against the pricing assumption. |
| **Data needed** | Purchase invoice values by consignment; ⚠ **landed-cost components captured at line level and by type** (freight, duty, clearing, demurrage, insurance) — most systems dump these into one "other charges" bucket, which destroys the whole report; ⚠ **the landed-cost assumption used in the selling price** (usually nowhere in the system, only in the pricing spreadsheet); FX rate at order, at invoice, at payment. |
| **Novelty** | **NOVEL** — the demurrage bar and the pricing-assumption comparison are the two things no standard ERP shows, and in a Dar-port import business they are where the margin actually goes. |

---

## 13 · Market or Us

| field | content |
|---|---|
| **Screen name** | `Market or Us` (12) |
| **Full name** | Where the Price Rise Came From — the market moving, or us buying badly |
| **Archetype** | 4 Variance Bridge |
| **The question it answers** | "Prices are up — is that the world, or is it us? Because one of those I can do something about." |
| **Key figures** | Headline: "Input prices up 12% — 8 points is the market, 4 points is us". Two-bar split, then the "us" portion decomposed: (1) lost a discount tier by ordering small; (2) bought at spot instead of contract; (3) bought from the second-choice supplier; (4) paid a rush premium; (5) unfavourable payment terms priced in. |
| **The comparison** | Our weighted purchase price index against an external reference for the same basket — commodity index, published market rate, or a rolling median of what the group's other companies paid for the identical item. |
| **Exception lead** | The "us" portion leads whenever it exceeds 2 points, with the largest cause named and its TZS value. |
| **Consolidation level** | Category level; group-wide. The internal-benchmark version compares companies/branches against one another for the same item. |
| **Cadence** | Monthly. |
| **The decision it triggers** | Stop excusing cost increases as "the market". Consolidate volume for a discount tier, sign a contract, or accept the rise and pass it to price. **Owner + CFO.** |
| **Tap-through** | The category's top 5 items with our price vs the reference. **Refuses** to show the source quotes. |
| **Alert condition** | Push when the "us" component exceeds 3 points of a category's rise for two consecutive months. |
| **Data needed** | Purchase price history by item; ⚠ **an external market/commodity reference price per key input** (must be fed in — sugar, flour, fuel, cement, packaging resin, USD/TZS); ⚠ **supplier discount tier structures**; contract vs spot flag per receipt; ⚠ **rush/expedite flag on orders**. Where no external index exists, fall back to the cross-branch internal benchmark, which needs nothing new. |
| **Novelty** | **NOVEL** — the single most common blind spot in owner-managed trading businesses: no ERP anywhere separates market inflation from negotiation failure, so every price rise gets excused. |

---

## 14 · Unit Cost

| field | content |
|---|---|
| **Screen name** | `Unit Cost` (9) |
| **Full name** | Which Way Unit Cost Is Going — 24 months of cost per unit, with the normal range |
| **Archetype** | 8 Trend & Trajectory |
| **The question it answers** | "Is it getting more expensive to make what we sell, or is this just a bad month?" |
| **Key figures** | Headline verdict in words: "Rising — 4th consecutive month outside the normal range". Then: current cost per unit; 12-month mean; the band (mean ± 1 SD); same month last year. One line, 24 points. |
| **The comparison** | The shaded normal band (this is what makes a wiggle interpretable) plus a same-period-last-year dotted line, because a factory with a Ramadan and Christmas peak has genuine seasonal cost swings. Event markers: recipe change, energy tariff change, new machine, supplier switch. |
| **Exception lead** | The verdict sentence leads, not the chart. If the run is inside the band, the screen says "Flat within normal range" and nothing is coloured. |
| **Consolidation level** | Per product family; rolls to a value-weighted plant index. |
| **Cadence** | Monthly. Never mid-month — the incomplete period is excluded from the trend by design. |
| **The decision it triggers** | Whether to intervene at all. Three consecutive points outside the band opens the Unit Cost Gap bridge (report 4) and, usually, a price review. **CFO monitors, Owner decides on price.** |
| **Tap-through** | The same series decomposed into material / labour / overhead per unit. **Refuses** to show the current partial month. |
| **Alert condition** | Push on the third consecutive month outside the band. Not on a single month — that is the whole point of this report. |
| **Data needed** | Monthly actual cost per unit by product; production volume for weighting; ⚠ **an event log of things that legitimately shift cost** (tariff, recipe, supplier, machine) — without markers, every explanation is retrofitted from memory. |
| **Novelty** | CLASSIC |

---

## 15 · Single-Source Risk

| field | content |
|---|---|
| **Screen name** | `Single-Source Risk` (18) |
| **Full name** | How Much Rides on One Supplier — spend share, single-sourced items, and how long we could last |
| **Archetype** | 10 Concentration & Exposure |
| **The question it answers** | "If my biggest supplier stopped tomorrow, how much of my business stops with them, and how long do I have?" |
| **Key figures** | Headline: "One supplier is 61% of purchases and 4 of our top 10 sellers have no second source". Pareto of top 5 suppliers by spend with cumulative share; the board's tolerance line drawn across it; per supplier: share of spend, number of single-sourced items, the **revenue those items carry**, and days of cover if they stopped today. |
| **The comparison** | The same shares 12 months ago — dependence deepening is the story, not dependence existing — and the stated tolerance (e.g. "no supplier above 35%"). |
| **Exception lead** | Not the biggest supplier: the biggest *undefended* one — highest revenue-at-risk with no qualified alternative. A 60% supplier with two qualified alternatives is safer than a 15% one without. |
| **Consolidation level** | Group, with related parties merged into one entity. Must roll up — a supplier at 20% in each of four companies is a 20% group exposure that no company sees. |
| **Cadence** | Quarterly; on alert when a share crosses tolerance. |
| **The decision it triggers** | Qualify a second supplier, hold strategic stock, or negotiate a notice period into the contract while we still have leverage. **Owner.** |
| **Tap-through** | That supplier's items, revenue carried, and contract terms. **Refuses** to show spend transactions. |
| **Alert condition** | Push when any supplier crosses the tolerance share, or when a single-sourced item's carried revenue exceeds TZS 500M/year. |
| **Data needed** | Purchase spend by supplier, 12-month rolling; ⚠ **related-party grouping**; ⚠ **an alternate-supplier / qualification register per item** (the whole report hinges on this and virtually no SME ERP holds it); revenue attributable to items sourced from each supplier; ⚠ **contract notice periods and exclusivity terms**; item lead times for the cover figure. |
| **Novelty** | CLASSIC as spend concentration; **NOVEL** in measuring exposure by *revenue at risk with no alternative* rather than by spend share. |

---

## 16 · One-Machine Risk

| field | content |
|---|---|
| **Screen name** | `One-Machine Risk` (16) |
| **Full name** | How Much Rides on One Machine — output that has no second route, and what it would cost to lose it |
| **Archetype** | 10 Concentration & Exposure |
| **The question it answers** | "If one machine died on Monday, how much of what I sell stops — and could anyone else even run it?" |
| **The question restated for the floor** | The same question asked of people: which operations only one person can perform. |
| **Key figures** | Headline: "One filler carries 74% of despatched value and has no standby". Top 5 assets by output value carried: share of production value routed through it, whether an alternative route exists, its age and last overhaul, spare-part lead time in days, and **operators qualified to run it**. |
| **The comparison** | Against a stated tolerance (no single asset above X% without a standby route) and against last year's share — concentration usually deepens quietly as one line proves reliable. |
| **Exception lead** | The asset with the highest carried value, no alternative route, **and** a spare-part lead time over 30 days. That triple is the one that ends a business quarter. |
| **Consolidation level** | Plant; rolls to group where product can genuinely be moved between plants. |
| **Cadence** | Quarterly; on alert after any major breakdown. |
| **The decision it triggers** | Buy the standby, hold the critical spare, cross-train a second operator, or insure the business interruption. **Owner — this is a capex and insurance decision, not a maintenance one.** |
| **Tap-through** | That asset's downtime history and the products routed through it. **Refuses** to show maintenance work orders. |
| **Alert condition** | Push when an asset carrying over 50% of production value records unplanned downtime twice in one month. |
| **Data needed** | ⚠ **Routing — which product runs on which machine** (BOMs are common, routings rarely are); production value by asset; ⚠ **an alternative-route flag per product**; ⚠ **critical spare lead times**; ⚠ **operator qualification matrix per machine**; asset age and overhaul history. |
| **Novelty** | **NOVEL** — every group insures buildings and nobody quantifies the single machine that carries three-quarters of despatch. Owners recognise this exposure instantly and have never had a number for it. |

---

## 17 · Bill vs Goods

| field | content |
|---|---|
| **Screen name** | `Bill vs Goods` (13) |
| **Full name** | Does the Bill Match the Goods? — ordered, received and invoiced, and the difference nobody has cleared |
| **Archetype** | 13 Reconciliation & Assurance |
| **The question it answers** | "Am I being billed for exactly what I ordered and actually received — and if not, for how long has that been true?" |
| **Key figures** | Verdict: "Out by TZS 41M across 63 lines". Three-way position: ordered value / received value / invoiced value. Reconciling items grouped by reason: (1) received not invoiced (timing — legitimate); (2) invoiced not received (**the dangerous one**); (3) price differs from PO; (4) quantity differs from receipt; (5) disputed/debit note raised. Each with value **and age**. |
| **The comparison** | The two sides against each other, plus how long each difference has persisted. Age matters more than size: a TZS 2M hole 200 days old is a control failure; TZS 40M one day old is Tuesday. |
| **Exception lead** | "Invoiced not received" over 30 days old leads, always — that is either a supplier billing for goods never sent or a receipt never booked, and both are money. |
| **Consolidation level** | Company (this is a ledger integrity question); rolls to group with each company's difference visible. |
| **Cadence** | Monthly at close; weekly during a heavy import period. |
| **The decision it triggers** | Do not trust the stock valuation or the payables balance until this clears; assign the clearing with a name and a date; raise a supplier dispute before it ages past recovery. **CFO.** |
| **Tap-through** | The unmatched lines in the worst reason bucket, with the person accountable for clearing. **Refuses** to show the matched majority. |
| **Alert condition** | Push when "invoiced not received" exceeds TZS 25M or when any single difference passes 60 days unexplained. |
| **Data needed** | Purchase orders, goods receipts, supplier invoices, and the match links between them; ⚠ **a difference-reason code and an owner per unmatched item** (without a reason code, "timing" and "unexplained" collapse into one number and the report becomes useless); debit notes and dispute status; ⚠ **the age of a difference, tracked from when it first appeared**, not from document date. |
| **Novelty** | CLASSIC — but the timing-vs-unexplained separation and the ageing are what make it executive rather than a clerk's worklist. |

---

## 18 · Batch Balance

| field | content |
|---|---|
| **Screen name** | `Batch Balance` (13) |
| **Full name** | Does the Batch Add Up? — what went in, what came out, and what cannot be accounted for |
| **Archetype** | 13 Reconciliation & Assurance |
| **The question it answers** | "Does the material we issued to the floor equal the goods, the scrap and the stock that came back — and if not, where is the hole?" |
| **Key figures** | Verdict: "Out by TZS 18M across 11 runs this month". Per period: input issued (at cost) vs output + scrap + returned + WIP remaining. The **unaccounted** figure isolated in its own line, as value and as % of input. Then the top 3 runs/products by unaccounted value, and the repeat-offender line or shift. |
| **The comparison** | Against a tolerance set per product (a liquid line legitimately loses more than a packing line), and against the same measure last month. Repeat concentration on one line or shift is the signal. |
| **Exception lead** | Any run outside tolerance leads with the location and the value, not the percentage. |
| **Consolidation level** | Line and product; rolls to plant. |
| **Cadence** | Monthly; weekly for high-value inputs. |
| **The decision it triggers** | Investigate a specific line or shift; recalibrate a meter or scale; or accept and revise the tolerance because the standard was wrong. **GM; Owner when it repeats on the same line.** |
| **Tap-through** | The worst run's input/output detail. **Refuses** to name operators on the executive screen (R7) — the investigation names people, the report names the line. |
| **Alert condition** | Push when unaccounted value exceeds TZS 5M in a week, or when the same line breaches tolerance three times running. |
| **Data needed** | Material issues per works order; finished output per works order; scrap booked; ⚠ **returns of unused material to store** (routinely not booked — the single biggest source of false "loss"); ⚠ **WIP left on the line at period end**; ⚠ **a per-product tolerance for acceptable unaccounted variance**; line and shift stamped on each run. |
| **Novelty** | **NOVEL** — factories reconcile stock counts and accountants reconcile banks; almost nobody reconciles a production batch as a closed system with an unaccounted line an owner can read. It is the manufacturing equivalent of a bank reconciliation and it catches both theft and bad measurement. |

---

## 19 · Make or Buy

| field | content |
|---|---|
| **Screen name** | `Make or Buy` (11) |
| **Full name** | Items We Make Dearer Than We Can Buy — and what stopping would save |
| **Archetype** | 6 Exception Register |
| **The question it answers** | "Which things am I manufacturing that I could simply buy cheaper — and how much is that pride costing me a year?" |
| **Key figures** | "7 items cost more to make than to buy · TZS 214M a year". Per line: item, our fully-loaded make cost per unit, the best available buy price landed, the gap per unit, annual volume, annual saving if we stopped — and, critically, the **factory hours freed** and whether those hours have anything better to do. |
| **The comparison** | Our make cost against a live external buy price for the same specification, both on a landed, fully-loaded basis. The comparison is only honest if overhead treatment is stated — the screen states it. |
| **Exception lead** | Ranked by annual money at stake, with the items whose freed capacity has *no* alternative use flagged separately: stopping those saves less than the arithmetic suggests, because the overhead does not disappear. |
| **Consolidation level** | Product; company level. |
| **Cadence** | Quarterly, and whenever an input price moves sharply. |
| **The decision it triggers** | Stop making an item and buy it; or attack its cost with a target and a date. Occasionally the reverse — an item bought dearly that the line could make in its idle hours. **Owner + GM.** |
| **Tap-through** | That item's make-cost breakdown against the quoted buy price, side by side. **Refuses** to give a recommendation — the strategic reasons to keep making something (quality control, secrecy, employment) are not in the data. |
| **Alert condition** | Push when an item crosses from cheaper-to-make to cheaper-to-buy and the annual gap exceeds TZS 50M. |
| **Data needed** | Fully-loaded actual make cost per unit including overhead and the capacity it consumes; ⚠ **a current external buy price for the same specification** (nowhere in an ERP — needs a quote-capture habit or a supplier price feed); ⚠ **an explicit statement of which overheads are avoidable if we stop**; capacity hours consumed per unit; alternative use for freed capacity. |
| **Novelty** | **NOVEL** — the classic textbook decision that no ERP ever surfaces, because it requires a price for something you do not buy. Owners agonise over this in meetings with no numbers at all. |

---

## 20 · Request to Goods

| field | content |
|---|---|
| **Screen name** | `Request to Goods` (16) |
| **Full name** | How Long From Request to Goods — the days from someone needing it to it being on the shelf, and where it stalls |
| **Archetype** | 12 Cycle-Time & Flow |
| **The question it answers** | "When someone in my business asks for something, how long does it actually take to arrive — and which step is eating the time?" |
| **Key figures** | Headline: "Request to goods: 27 days median, 61 at the 90th percentile — up from 21". Segmented bar of stages with days each: request raised → approved → PO issued → supplier acknowledged → goods shipped → received → put away and available. The stage exceeding its standard is called out with **how many items are stuck in it right now**. |
| **The comparison** | Against the agreed service standard per stage, and against the same measure a quarter ago. Median *and* 90th percentile — the average hides the pathological tail, and the tail is what stops production. |
| **Exception lead** | The worst stage against standard leads, with the count currently stuck there and the value they represent. |
| **Consolidation level** | Company and buying branch; rolls to group for the standard-setting conversation. |
| **Cadence** | Monthly; on alert if a stage doubles. |
| **The decision it triggers** | Remove or delegate an approval step that costs more days than it saves shillings; add a buyer; change a supplier whose acknowledgement stage is the bottleneck. **CFO / GM; Owner when the bottleneck is the Owner's own approval step.** |
| **Tap-through** | The items currently stuck in the worst stage, with an owner each. **Refuses** to report on completed items only — items stuck forever are counted, which is the flaw in most cycle-time reporting. |
| **Alert condition** | Push when any single stage's median exceeds twice its standard for a full month. |
| **Data needed** | Timestamps at each handoff: requisition, approval, PO issue, supplier acknowledgement, despatch, receipt, put-away; ⚠ **supplier acknowledgement capture** (usually a WhatsApp message); ⚠ **put-away/available-to-use timestamp separate from receipt** — goods received and goods usable are not the same day; ⚠ **agreed stage service standards**; open/aged items, not just completed ones. |
| **Novelty** | CLASSIC — with the honest twist that the Owner's own approval queue frequently turns out to be the largest stage, which is why report 25 exists. |

---

## 21 · Costly Stoppages

| field | content |
|---|---|
| **Screen name** | `Costly Stoppages` (16) |
| **Full name** | Stoppages That Cost the Most — downtime priced in lost margin, not in minutes |
| **Archetype** | 6 Exception Register |
| **The question it answers** | "What is stopping my factory, and which of those stoppages is actually worth spending money to prevent?" |
| **Key figures** | "TZS 112M of margin lost to stoppages this month". Ranked by **money**, not by minutes: cause, total minutes, number of occurrences, margin lost (minutes × the contribution rate of what that line would have produced), and whether it is new or a repeat. Plus a "cheapest fix known" note where one exists. |
| **The comparison** | Against last month's cost of the same cause, and against the allowed downtime in the plan. Crucially, the ranking by money reorders the engineer's list: a 12-minute stoppage on the high-margin line can outrank a 4-hour one on a line making a low-margin filler product. |
| **Exception lead** | The most expensive cause leads, with a one-line statement of what it would cost to prevent — so the comparison the owner needs (loss vs fix) is on the screen. |
| **Consolidation level** | Line and plant; rolls to company. |
| **Cadence** | Monthly; weekly during a peak build. |
| **The decision it triggers** | Approve the specific spend — the spare, the service contract, the generator, the second operator — because the loss now exceeds the fix. **Owner approves, GM proposes.** |
| **Tap-through** | The cause's 12-month occurrence trend on the affected line. **Refuses** to show the maintenance log. |
| **Alert condition** | Push when a single cause costs more than TZS 20M in a month, or when a cause previously fixed reappears. |
| **Data needed** | ⚠ **Stoppage events with duration and reason code**; the line's planned product and its contribution margin per hour; ⚠ **contribution margin per production hour by line/product** (needs the costing and the selling price joined — normally they are not); ⚠ **an estimated cost-to-prevent per known cause** (maintenance knows it, the system does not). |
| **Novelty** | **NOVEL** — every factory reports downtime minutes and every owner ignores them. Pricing downtime in lost margin, and printing the cost of the fix beside it, converts an engineering log into a capital decision. |

---

# TIER 3 — specialist / on demand

---

## 22 · Rejected Goods

| field | content |
|---|---|
| **Screen name** | `Rejected Goods` (14) |
| **Full name** | Goods We Sent Back — rejections by supplier, what they cost, and whether we were credited |
| **Archetype** | 6 Exception Register |
| **The question it answers** | "What did we refuse or have to return, what did that failure cost us beyond the goods, and did the supplier ever actually credit us?" |
| **Key figures** | "TZS 62M rejected · TZS 21M still not credited · oldest 74 days". Per line: supplier, item, rejected value, reason, days since rejection, credit note received yes/no, and the **consequential cost** (production stopped, rush replacement, customer order missed). |
| **The comparison** | Rejection rate against the supplier's own history and against the group average; uncredited value against last month. |
| **Exception lead** | Uncredited rejections over 30 days lead — a rejection with no credit note is simply money given away, and it is the part everyone forgets. |
| **Consolidation level** | Group by supplier (related parties merged). |
| **Cadence** | Monthly; on alert for a high-value rejection. |
| **The decision it triggers** | Withhold payment against the uncredited amount; formally warn or de-list a supplier; tighten incoming inspection on a specific item. **CFO (payment) / GM (supplier).** |
| **Tap-through** | The supplier's rejection history by reason. **Refuses** to show inspection certificates. |
| **Alert condition** | Push when a rejection stops production, or when uncredited rejections exceed TZS 25M. |
| **Data needed** | ⚠ **Incoming inspection outcomes with reason codes**; return/debit-note records linked to receipts; ⚠ **credit notes matched back to the specific rejection** (almost never linked — this is why the money is lost); ⚠ **consequence link** to the production or customer order affected. |
| **Novelty** | CLASSIC — except the uncredited-value tracking, which quietly recovers real money. |

---

## 23 · WIP by Age

| field | content |
|---|---|
| **Screen name** | `WIP by Age` (10) |
| **Full name** | Work in Progress by Age — money sitting half-finished on the floor, and how long it has sat |
| **Archetype** | 7 Ageing Pyramid |
| **The question it answers** | "How much of my cash is stuck as half-made goods, and how much of it has been stuck so long it will never be finished?" |
| **Key figures** | "TZS 380M in progress · 22% older than 30 days". Buckets: 0–7 days (normal), 8–30, 31–90, 90+ — with value and count of works orders in each. Then the top 5 oldest runs by value, with the stage they are stuck at and why. |
| **The comparison** | The same shape one month ago as a ghost outline. A pyramid fattening at the base is a scheduling problem; fattening at the tip is abandoned work that will become a write-off. |
| **Exception lead** | The 90+ bucket leads with its value and the reason each item is stuck — awaiting a material, awaiting a decision, awaiting quality release, or genuinely abandoned. |
| **Consolidation level** | Line and plant; rolls to company (WIP is a balance-sheet number the CFO must defend). |
| **Cadence** | Monthly; at every stock count. |
| **The decision it triggers** | Finish it, rework it, or write it off — and stop starting work you cannot finish. **GM decides the disposition; CFO forces the write-off decision.** |
| **Tap-through** | The oldest works orders with their blocking reason and owner. **Refuses** to show material issue detail. |
| **Alert condition** | Push when the 90+ bucket exceeds TZS 100M or doubles in a month. |
| **Data needed** | Open works orders with material and labour value absorbed to date; ⚠ **the date work actually started, and the date it last moved** (age since last movement is the diagnostic, not age since opening); ⚠ **a blocking-reason code per stalled works order**; stage/routing position. |
| **Novelty** | **NOVEL** — debtor and stock ageing are universal; nobody ages WIP, and in a factory with long runs it silently holds a large, unexamined slice of working capital and eventual write-off. |

---

## 24 · Do New Suppliers Last?

| field | content |
|---|---|
| **Screen name** | `New Suppliers` (13) — full name carries the question |
| **Full name** | Do New Suppliers Last? — how the suppliers we onboard each year perform at months 3, 6 and 12 |
| **Archetype** | 11 Cohort & Retention |
| **The question it answers** | "The suppliers we sign are keen and cheap at the start — do they stay that way, or do we keep replacing them and calling it procurement?" |
| **Key figures** | Verdict: "Suppliers onboarded this year are 14 points worse on OTIF by month 6 than last year's". 5–6 cohort lines (by onboarding quarter), each tracking a single composite at months 3 / 6 / 12: OTIF %, price against the price they won on, and share still trading at all. Plus **survival**: what % of each cohort is still used after 12 months. |
| **The comparison** | Cohort against cohort — no external target needed. The story is whether our *selection* is getting better or worse, which no snapshot of supplier performance can ever show. |
| **Exception lead** | The plain-language verdict, followed by the single worst-decaying cohort and the two suppliers inside it that caused it. |
| **Consolidation level** | Group; by procurement category where volumes allow. |
| **Cadence** | Half-yearly; on demand before a supplier-strategy review. |
| **The decision it triggers** | Fix supplier *selection* rather than supplier management — change vetting, stop awarding on lowest quoted price, add a probation period with a review gate at month 3. **GM / Procurement Lead; Owner on the vetting policy.** |
| **Tap-through** | The suppliers inside a failing cohort. **Refuses** to show a 60-cell cohort grid — on a phone this is 5 lines or it is nothing. |
| **Alert condition** | No push. Strategic, reviewed on cadence. |
| **Data needed** | First-transaction date per supplier (the cohort key); OTIF and price-adherence history per supplier over time; ⚠ **the price the supplier won the business on** (the quote, retained — normally discarded once the PO is raised); ⚠ **an onboarding/vetting record with the reason they were selected**; last-transaction date for survival. |
| **Novelty** | **NOVEL** — and a **new matrix cell (Cohort × Buy), argued rather than assumed.** The doctrine leaves it blank on the reasonable view that cohorts belong to customers and people. The argument for opening it: a group that onboards 40+ suppliers a year is making a repeated *selection* decision with a measurable quality, exactly like hiring. Nothing else in the suite can distinguish "our suppliers perform badly" from "we choose suppliers badly", and those two findings have opposite remedies. It is proposed as a Tier 3 ○ cell, not a flagship. |

---

## 25 · Late Approvals

| field | content |
|---|---|
| **Screen name** | `Late Approvals` (14) |
| **Full name** | What Waiting Cost Us — buying decisions approved late, and the price of the delay |
| **Archetype** | 14 Decision Docket (self-measuring instance) |
| **The question it answers** | "When buying decisions sat waiting for a signature, what did that actually cost me — and was the signature mine?" |
| **Key figures** | "TZS 74M cost of delay last month, across 19 late approvals". Cost broken into named consequences: (1) price increase between quote and eventual order; (2) rush/airfreight premium paid to recover the time; (3) production hours lost waiting for material; (4) customer orders missed; (5) lost early-settlement discount. Plus median approval time by approval level. |
| **The comparison** | Against the agreed approval service standard (e.g. 24 hours), and against the same figure last quarter. |
| **Exception lead** | The single most expensive delay leads, stated as a fact without blame: "A 9-day wait on a resin order cost TZS 31M in airfreight." |
| **Consolidation level** | Company, by approval level — not by named approver on the executive screen (R7). |
| **Cadence** | Monthly. |
| **The decision it triggers** | Raise a delegation limit, add a deputy approver, or set an auto-approve rule for the item classes that are always approved unchanged. **Owner — usually about the Owner's own bottleneck, which is why it must exist as a report and not as an HR conversation.** |
| **Tap-through** | The delay-time distribution by approval level, and the items that were approved unchanged more than 20 times (delegation candidates). **Refuses** to rank individual approvers by name. |
| **Alert condition** | No push. Monthly, deliberately — a push here would be a rebuke. |
| **Data needed** | Approval request and decision timestamps by level; ⚠ **quote price at request vs price at eventual order** (needs the quote retained); ⚠ **rush/expedite premium flagged as such**; downtime attributable to a late material; ⚠ **approval service standard**; approved-unchanged counts per item class for the delegation analysis. |
| **Novelty** | **NOVEL** — the docket that measures the docket. It is the only report in the suite whose likely finding is that the person reading it is the bottleneck, and owners consistently report never having seen the cost of their own signature. |

---

## Suite notes

**The five hardest build dependencies**, each unlocking multiple reports:

1. **An agreed price list per supplier-item, with effective dates** — unlocks reports 3, 6, 7, 13.
2. **Stoppage events with duration and reason code, covering the whole shift** — unlocks 1, 10, 21, and half of 16.
3. **Standard cost cards and standard yields, version-dated** — unlocks 2, 4, 11, 19.
4. **Supplier promise dates and measured actual lead times, with clearance as a separate leg** — unlocks 5, 6, 9, 20.
5. **Landed-cost components captured by type at line level** — unlocks 12, and corrects the cost basis feeding 3, 4, 19.

**Naming self-checks applied.** No banned words survive anywhere in the suite. `Runway` was rejected for report 9 under R12 (untranslatable) in favour of `Line Stoppers` / *"kitakachosimamisha mtambo"*. `Maverick spend` was rejected as English procurement jargon in favour of `Off-Contract Buys` / *"manunuzi nje ya mkataba"*. `Bottleneck`, `leakage`, `OEE`, `spend under management` and `strategic sourcing` were all rejected on R10/R12. Three near-twins were separated under R9: `Buy Price Gap` (what we paid vs agreed), `Landed Cost Gap` (invoice vs shelf) and `Market or Us` (whose fault the rise is) — different questions, different screens, no shared leading phrase. `Rejected Goods` and `Costly Stoppages` name conditions, never people (R7).

===== DOMAIN: people-assets — PEOPLE, ASSETS AND OVERHEAD =====
# People, Assets & Overhead — Executive Report Suite
**OrbixERP Executive Mobile · domain pack v1 · 2026-08-18 · 25 reports**

Every name is filled from its archetype's template (R5), passes the banned-word strike (R3), the 22-char/3-word screen limit (R4) and the Swahili back-translation (R12). Cells are named as *archetype × matrix column*; one cell is argued as new and flagged.

## Tier index

| Tier | # | Reports |
|---|---|---|
| **TIER 1** — opened weekly or more | 10 | People vs Plan · Wage Bill Gap · Overtime Causes · Today's Attendance · Staff Cost League · Payroll Taxes Due · Capital Employed · Machine League · Idle Assets · Overhead vs Plan |
| **TIER 2** — monthly or on alert | 11 | Wage Share · Leave Owed · New Hires Kept · Key Person Risk · Payroll Check · Vehicle League · Assets Not Found · Repair or Replace · Capex Due · Asset Spend Waiting · Quiet Cost Rises |
| **TIER 3** — specialist / on demand | 4 | Insurance Gap · Fixed Cost Base · Contracts Due · Break-Even League |

**Novelty split:** 9 CLASSIC · **16 NOVEL**

---

# PART A — PEOPLE (11)

---

## 1. People vs Plan

| field | content |
|---|---|
| **Screen name** | People vs Plan |
| **Full name** | People Against Plan — headcount, wage bill, overtime and cost per head against budget |
| **Archetype** | 2 Scorecard (Scorecard × People) |
| **The question it answers** | "Am I carrying the number of people I agreed to carry, at the cost I agreed to pay?" |
| **Key figures** | (1) Verdict — "4 of 6 on plan"; (2) Headcount now vs approved establishment (heads); (3) Wage bill month-to-date vs budget (TZS m and %); (4) Overtime cost vs the 3% ceiling; (5) Cost per head vs budget (TZS/month); (6) Staff cost as % of sales vs the target rate |
| **The comparison** | The approved establishment and the board wage budget, printed on every row — actual, target, gap in TZS and heads |
| **Exception lead** | Rows sorted by size of miss. Opens on the worst: "Factory 11 heads over establishment · TZS 18.4m/month" |
| **Consolidation level** | Group headline; rolls down company → branch/function. Must roll up: group total = sum of company totals with no unallocated bucket |
| **Cadence** | Weekly glance; the real read is month-end |
| **The decision it triggers** | Freeze or release hiring for a named function (Owner/GM); re-cut the establishment if the plan is wrong (CFO) |
| **Tap-through** | Any red row opens **Wage Bill Gap** for that unit. Refuses to show individual salaries |
| **Alert condition** | Push when wage bill month-to-date exceeds budget pace by >8%, or headcount exceeds establishment by >5 heads in any one company |
| **Data needed** | Approved establishment by function and branch ⚠ (budgeted headcount is rarely held); board wage budget by month ⚠; live headcount by function; payroll gross by cost centre; sales by company for the ratio; overtime ceiling as policy ⚠ |
| **Novelty** | CLASSIC |

---

## 2. Wage Bill Gap

| field | content |
|---|---|
| **Screen name** | Wage Bill Gap |
| **Full name** | Where the Wage Bill Went — budget to actual by headcount, pay rate, overtime, statutory and mix |
| **Archetype** | 4 Variance Bridge (Variance Bridge × People) |
| **The question it answers** | "The wage bill is TZS 74m over budget — how much of that is more people, how much is higher pay, and how much is overtime?" |
| **Key figures** | Headline: the gap (TZS 74m over, +9.1%). Bars: (1) Headcount effect; (2) Pay-rate effect (increments, promotions, minimum-wage move); (3) Mix effect (senior replacing junior); (4) Overtime and allowances; (5) Statutory effect (NSSF/WCF/SDL rate or base change); (6) Terminal dues and gratuity; residual must stay under 5% |
| **The comparison** | Built in — budget → actual. Second reference: same bridge last month, so a persistent bar is visible as persistent |
| **Exception lead** | Largest adverse bar first with its owner named: "Pay rate +TZS 31m — 14 out-of-cycle increases approved by the GM" |
| **Consolidation level** | Group down to company and cost centre; each bar decomposes by branch |
| **Cadence** | Month-end, within 2 days of payroll close |
| **The decision it triggers** | Stop out-of-cycle increases (Owner); move overtime into headcount or vice versa (GM); re-base the budget (CFO) |
| **Tap-through** | The overtime bar opens **Overtime Causes**; every other bar opens its branch/function league. Refuses to name individuals on any bar except terminal dues |
| **Data needed** | Wage budget decomposed into heads × rate ⚠ (budgets are usually a single lump — needs headcount and average-rate assumptions stored); payroll gross by element (basic, overtime, allowance, statutory, terminal); joiners/leavers with dates; grade at hire and now ⚠ |
| **Novelty** | **NOVEL** — nearly every ERP reports payroll cost; almost none decomposes the miss into heads vs rate vs overtime, which is the only version an owner can act on |

---

## 3. Overtime Causes

| field | content |
|---|---|
| **Screen name** | Overtime Causes |
| **Full name** | Why We Paid Overtime — hours and cost by cause, against a normal month |
| **Archetype** | 4 Variance Bridge (Variance Bridge × People) |
| **The question it answers** | "We paid TZS 22m of overtime — what caused it, and was any of it avoidable?" |
| **Key figures** | (1) Overtime cost this month (TZS m) and as % of basic pay; (2) Hours paid vs hours rostered; (3) Cost by cause — absence cover, demand peak, machine breakdown re-run, understaffed shift, month-end close, delivery/loading delay; (4) Avoidable share (the causes management controls); (5) Effective overtime rate vs cost of an extra permanent head |
| **The comparison** | A normal month (12-month median), and the cost of the alternative — "these hours equal 6 permanent staff at TZS 3.1m each" |
| **Exception lead** | The single largest avoidable cause: "Machine 3 breakdown re-runs — 380 hours, TZS 6.2m, third month running" |
| **Consolidation level** | Branch and factory shift level, rolls to company and group |
| **Cadence** | Monthly; weekly during factory peak season |
| **The decision it triggers** | Hire permanently instead of paying premium hours (GM); fix the machine causing re-runs (Factory Manager); withdraw a supervisor's overtime authority (Owner) |
| **Tap-through** | A cause opens the shift/branch league for that cause. Refuses to show per-employee overtime ranking (that is an HR list, not an executive screen, and it makes the report political) |
| **Data needed** | Rostered vs actual hours ⚠ (shift rosters and planned hours are commonly absent); overtime hours with a **reason code** ⚠; absence records linked to the shift they left uncovered ⚠; machine downtime events ⚠; fully-loaded cost of a permanent head |
| **Novelty** | **NOVEL** — overtime is normally a payroll line item; treating it as a variance with named causes turns it into a management failure list |

---

## 4. Today's Attendance

| field | content |
|---|---|
| **Screen name** | Today's Attendance |
| **Full name** | Today's Attendance — who is in, who is short, and which branch cannot open properly |
| **Archetype** | 1 Flash (Flash × People) |
| **The question it answers** | "Do we have the people to trade today?" |
| **Key figures** | (1) Present now vs required for the day (heads); (2) Absent unplanned (count) with delta chip vs the same weekday; (3) Branches/shifts below minimum cover (count); (4) Factory shift cover % vs the level that keeps the line running; (5) Overtime hours already triggered today by absence (TZS estimate) |
| **The comparison** | Same weekday last week and same day last month — never yesterday (Sunday and market days distort) |
| **Exception lead** | "Kariakoo counter open with 3 of 6 — no cashier on till 2 since 08:40" appears above the group figure |
| **Consolidation level** | Group count; the value is the branch/shift strip one tap down |
| **Cadence** | Glance daily, before 10:00 |
| **The decision it triggers** | Move staff between branches this morning (GM/Branch Manager); authorise a shift swap (Factory Manager); call an absent key holder (Owner) |
| **Tap-through** | The branch strip — cover by branch and shift. Refuses to show individual lateness minutes (a supervisor's job, and it turns the owner into a timekeeper) |
| **Alert condition** | Push when any branch is below its minimum cover 30 minutes after opening, or when factory shift cover is under 80% |
| **Data needed** | Clock-in/attendance capture ⚠ (POS login is a weak proxy); minimum cover per branch and per shift ⚠; approved leave and its dates; today's roster ⚠ |
| **Novelty** | CLASSIC in form, **rare in practice** — most ERPs have attendance data and never surface it as a trading-day risk |

---

## 5. Staff Cost League

| field | content |
|---|---|
| **Screen name** | Staff Cost League |
| **Full name** | Staff Cost League — margin earned per shilling of wage, by branch |
| **Archetype** | 5 League Table (League × People) |
| **The question it answers** | "For every shilling I pay in wages, which branch brings back the most gross margin — and which brings back the least?" |
| **Key figures** | (1) The spread — "Best TZS 4.10 of margin per wage shilling, worst TZS 1.35, group TZS 2.60"; (2) Rank list of branches on the ratio; (3) Wage bill per branch (TZS m) as the size marker; (4) Rank change since last month; (5) Group breakeven ratio (the ratio at which a branch covers its own wages and rent) |
| **The comparison** | The group ratio drawn as a reference line on every row, plus each branch's own ratio three months ago |
| **Exception lead** | "Mbeya fell 4 places — margin per wage shilling down from 3.10 to 1.90 in two months" leads, ahead of the winner |
| **Consolidation level** | Branch, ranked within comparable formats (counter, wholesale depot, van route, factory excluded — they are not peers) |
| **Cadence** | Weekly glance, monthly decision |
| **The decision it triggers** | Where the manager visit goes this week (Owner/GM); move headcount from the worst to the best (GM); copy the top branch's rota into the bottom (Branch Manager) |
| **Tap-through** | A row opens that branch's **People vs Plan**. Refuses to rank on absolute wage bill (the biggest branch would always lose) and refuses to name individual staff |
| **Alert condition** | Push when any branch falls below the wage-cover ratio (margin < wages) for two consecutive weeks |
| **Data needed** | Gross margin by branch; wage cost by branch including allowances and statutory employer cost; branch format/peer group classification ⚠; branch opening date to exclude units under 90 days ⚠ |
| **Novelty** | **NOVEL** — revenue-per-employee exists in HR modules; margin per wage shilling ranked between peer branches is the version that changes a decision |

---

## 6. Wage Share

| field | content |
|---|---|
| **Screen name** | Wage Share |
| **Full name** | Which Way the Wage Share Is Going — wages as a share of sales, 24 months with the normal band |
| **Archetype** | 8 Trend & Trajectory (Trend × People) |
| **The question it answers** | "Are wages quietly eating a bigger slice of the business than they used to?" |
| **Key figures** | (1) Current wage share (% of sales) with the verdict in words — "Rising, 5th consecutive month"; (2) The target share; (3) 24-month line with normal band (12-month mean ± 1 s.d.); (4) Same month last year; (5) Wages in TZS m and sales in TZS m, so the reader can see which side moved |
| **The comparison** | The normal band plus the same-month-last-year line; annotated with known events (branch opening, minimum-wage change, factory shutdown) |
| **Exception lead** | The verdict sentence, not the chart: "Above the normal band for 3 months — driven by sales falling, not wages rising" |
| **Consolidation level** | Group and company; branch series behind the tap-through |
| **Cadence** | Month-end |
| **The decision it triggers** | Whether to intervene at all (Owner) — and if the run is outside the band, open the Wage Bill Gap (CFO) |
| **Tap-through** | The same series split by company and by function (selling, factory, admin). Refuses to show the incomplete current month inside the trend |
| **Alert condition** | Push only on the third consecutive month outside the band — never on a single month |
| **Data needed** | Monthly wage cost including employer statutory; monthly sales; 24 months of history; target share as a policy number ⚠; an event calendar for annotations ⚠ |
| **Novelty** | CLASSIC |

---

## 7. Leave Owed

| field | content |
|---|---|
| **Screen name** | Leave Owed |
| **Full name** | How the Leave Bill Moved — days earned, taken and paid out, and what we owe today |
| **Archetype** | 3 Position & Movement (**argued new cell: Position × People** — leave is a genuine stock with named movements, and it is money) |
| **The question it answers** | "If everyone took the leave they are owed tomorrow, what would it cost me, and is that pile growing?" |
| **Key figures** | (1) Leave liability today (TZS m) and total days owed; (2) Movement waterfall — opening, earned, taken, paid out in cash, forfeited, closing; (3) Same liability 12 months ago; (4) Days owed per head, worst function; (5) Staff carrying more than double their annual entitlement (count and TZS) |
| **The comparison** | Opening balance for the year and the same position last year — the direction of the pile is the message |
| **Exception lead** | "Factory supervisors hold 1,180 days — TZS 96m — and 9 of them have not taken leave in 2 years" (which is also a fraud-control signal, not only a cost one) |
| **Consolidation level** | Group; rolls down to company, branch and function |
| **Cadence** | Month-end; hard read at year-end for provisioning |
| **The decision it triggers** | Force leave to be taken in a named quarter (GM); provide for the liability in the accounts (CFO); cash-settle a capped share (Owner) |
| **Tap-through** | The worst function's holders, by days and value. Refuses to show one person's leave history |
| **Alert condition** | Push when liability grows more than 15% in a quarter, or when any employee crosses 2× entitlement |
| **Data needed** | Leave entitlement policy and accrual rule ⚠; leave taken with dates; daily pay rate per employee for valuation ⚠ (liability is rarely valued in money); leave-encashment history; forfeiture rule ⚠ |
| **Novelty** | **NOVEL** — leave sits in HR as a day count; nobody shows the owner the shilling liability or its growth, and the "never takes leave" line doubles as a control alert |

---

## 8. New Hires Kept

| field | content |
|---|---|
| **Screen name** | New Hires Kept |
| **Full name** | Do New Hires Last? — share still with us at 3, 6 and 12 months, by hire quarter |
| **Archetype** | 11 Cohort & Retention (Cohort × People) |
| **The question it answers** | "Are the people we are hiring now sticking, or am I paying to train replacements for my replacements?" |
| **The verdict on top** | "People hired this year are 22 points less likely to reach 6 months than last year's" |
| **Key figures** | (1) 12-month survival of the newest complete cohort vs the same cohort a year earlier; (2) Survival curve for the last 5–6 hire quarters; (3) Cost of churn (recruit + train + terminal dues per lost hire, TZS m for the year); (4) Worst function by early exit; (5) Share of exits inside probation |
| **The comparison** | Cohort against cohort — no external target needed |
| **Exception lead** | The failing cohort named with its function: "Van sales, hired Q1 2026 — half gone by month 4" |
| **Consolidation level** | Group and function; branch only where volumes allow (small cohorts are noise) |
| **Cadence** | Quarterly |
| **The decision it triggers** | Fix hiring quality or induction rather than hiring harder (GM/HR); stop paying recruiter fees per head placed (Owner); change the probation review (Branch Manager) |
| **Tap-through** | The exits inside a failing cohort with exit reason. Refuses to show performance ratings |
| **Data needed** | Hire date and exit date per employee; exit reason coded ⚠; cost to recruit and train per hire ⚠; function/grade at hire ⚠ |
| **Novelty** | CLASSIC in HR systems, **absent from ERPs** — and the cost-of-churn figure is the part that makes an owner act |

---

## 9. Key Person Risk

| field | content |
|---|---|
| **Screen name** | Key Person Risk |
| **Full name** | How Much Rides on One Person — duties, approvals and relationships held by a single name |
| **Archetype** | 10 Concentration & Exposure (Concentration × People) |
| **The question it answers** | "If one person did not come to work on Monday, what would stop?" |
| **Key figures** | (1) The single-point exposure — "One person releases 78% of all payments"; (2) Top 5 people ranked by what depends on them: share of approvals, share of customer margin owned, systems where they are the only holder of access, processes with no trained substitute; (3) Count of single-point duties with no backup; (4) Notice period and leave-not-taken for each (a person who never takes leave is both a risk and a control flag); (5) The board's stated tolerance line |
| **The comparison** | The same concentration a year ago — is the dependence deepening? — and the tolerance the board set |
| **Exception lead** | "Factory production planning: one person, no substitute, 11 years, 62 days leave untaken" |
| **Consolidation level** | Group, by company; roles not names on the group screen, names on tap-through |
| **Cadence** | Quarterly, and on any senior resignation |
| **The decision it triggers** | Force a documented substitute and cross-training (Owner/GM); split an approval authority (CFO); retention action on a named critical person (Owner) |
| **Tap-through** | The person's duty map — what only they can do. Refuses to show salary (this is a risk report, not a pay review) |
| **Alert condition** | Push when a person on the top-5 list resigns, or when a single-point duty count rises |
| **Data needed** | Approval authority per user (already in RBAC); customer/supplier relationship ownership ⚠; a duty/substitute map ⚠ (rarely captured anywhere); system access exclusivity; notice periods ⚠; leave balances |
| **Novelty** | **NOVEL** — no ERP tells an owner which single human being is a single point of failure, though the ERP already knows most of it from its own permission grants |

---

## 10. Payroll Taxes Due

| field | content |
|---|---|
| **Screen name** | Payroll Taxes Due |
| **Full name** | Payroll Deductions Not Yet Remitted — PAYE, NSSF, WCF and SDL by age and penalty at risk |
| **Archetype** | 6 Exception Register (Exception × Tax, People-sourced) |
| **The question it answers** | "Have we actually paid over everything we deducted from staff — and what does it cost if we are late?" |
| **Key figures** | (1) Count and money — "5 obligations · TZS 143m unremitted"; (2) Split by head: PAYE, NSSF, WCF, SDL, HESLB and any union/loan deductions; (3) Days past the statutory due date, worst first; (4) Estimated penalty and interest at today's date (TZS); (5) New vs repeat vs cleared since last week |
| **The comparison** | Each obligation against its own statutory due date, and the count of breaches seven days ago — a repeat breach is a control failure, not an incident |
| **Exception lead** | The oldest money-weighted breach: "NSSF, July, TZS 61m, 12 days past due — penalty accruing" |
| **Consolidation level** | Per company (each legal entity files separately — this must NOT be netted at group), with a group count on top |
| **Cadence** | Weekly; hard check in the first week of every month |
| **The decision it triggers** | Release the payment today (Owner/CFO); assign a named person and a deadline (CFO); escalate where cash was used elsewhere (Owner) |
| **Tap-through** | The obligation's computation basis and the payment reference once paid. Refuses to show per-employee deduction detail |
| **Alert condition** | Push on the due date minus 2 days if unpaid, and every day past due. Employee deductions withheld and not remitted are the highest-severity alert in this pack |
| **Data needed** | Deduction amounts per period per company; statutory due-date calendar per obligation ⚠ (rarely held as data); payment/remittance records matched to the period ⚠; penalty and interest rules ⚠; employer vs employee split |
| **Novelty** | CLASSIC — but the ageing, the penalty accrual and the remittance match make it executive rather than clerical |

---

## 11. Payroll Check

| field | content |
|---|---|
| **Screen name** | Payroll Check |
| **Full name** | Does the Payroll Match the People? — names paid against people employed, with the unexplained portion isolated |
| **Archetype** | 13 Reconciliation & Assurance (Recon × People) |
| **The question it answers** | "Am I paying anyone who does not work here?" |
| **Key figures** | (1) Verdict and difference — "Out by 7 names · TZS 9.4m this month"; (2) Two sides: names on the payroll run vs active employees on the establishment; (3) Reconciling items grouped by reason — new joiner not yet on file, leaver still paid, contractor on payroll, duplicate bank account, duplicate TIN/NIDA, no attendance record all month; (4) Age of each difference in payroll cycles (a difference surviving 3 runs is not a timing issue); (5) Cumulative value of the unexplained portion this year |
| **The comparison** | The two registers side by side, plus how many cycles each difference has survived |
| **Exception lead** | The unexplained group first: "2 names paid with no attendance and no supervisor — TZS 3.2m, 3rd month" |
| **Consolidation level** | Per company (payroll runs are per entity); group count on top |
| **Cadence** | Every payroll run, before payment release |
| **The decision it triggers** | Hold the payroll release until cleared (Owner/CFO); open an investigation at a named branch (Owner); separate payroll-master edit rights from payment release (CFO) |
| **Tap-through** | The unmatched names with who added them and when. Refuses to show salary rankings |
| **Alert condition** | Push before every payroll release if any unexplained item exists; escalate to the Owner directly if the item is a duplicate bank account |
| **Data needed** | Payroll run detail by employee; HR active-employee register with joiner/leaver dates ⚠ (the two are often the same file, which defeats the reconciliation — a genuinely independent source such as attendance or supervisor sign-off is required); bank account per employee for duplicate detection ⚠; NIDA/TIN uniqueness ⚠; attendance per pay period ⚠; payroll master-change log with the maker's name |
| **Novelty** | **NOVEL** — the ghost-worker check is the single most valuable people report in a multi-branch Tanzanian group and virtually no ERP ships it |

---

# PART B — ASSETS (9)

---

## 12. Capital Employed

| field | content |
|---|---|
| **Screen name** | Capital Employed |
| **Full name** | Where the Capital Is Tied Up — what the group owns, and what it earns on it |
| **Archetype** | 3 Position & Movement (Position × Assets) |
| **The question it answers** | "How much money is locked in this business, where is it locked, and is it earning?" |
| **Key figures** | (1) Capital employed today (TZS bn); (2) Return on it (%) — trailing 12-month operating profit over average capital employed; (3) Composition — plant and machinery, vehicles, buildings, stock, debtors, less supplier credit; (4) Movement this year — additions, disposals, depreciation, working-capital change; (5) Share of capital sitting in assets earning nothing (feeds Idle Assets) |
| **The comparison** | The same position 12 months ago, and the return against the group's own cost of money (bank borrowing rate) — capital earning less than the loan rate is the message |
| **Exception lead** | "Return 9% against borrowing at 16% — TZS 1.4bn of it in stock and idle plant" |
| **Consolidation level** | Group, rolls down to company and site. Must roll up cleanly with intercompany eliminated |
| **Cadence** | Month-end |
| **The decision it triggers** | Sell or redeploy an asset class (Owner); stop a capex programme until the return improves (Owner/CFO); attack working capital instead of buying growth (CFO) |
| **Tap-through** | Any composition bar opens its own movement. Refuses to show the fixed-asset register line by line |
| **Data needed** | Fixed assets at net book value by class and site; stock at cost; debtors; supplier credit; operating profit by company; cost of borrowing ⚠ (facility rates are rarely in the ERP); intercompany balances flagged ⚠ |
| **Novelty** | CLASSIC for a CFO, **new to the owner's phone** — return on capital is the number that reframes every other report |

---

## 13. Machine League

| field | content |
|---|---|
| **Screen name** | Machine League |
| **Full name** | Machine League — hours run against hours available, and output value per machine |
| **Archetype** | 5 League Table (League × Assets / League × Make) |
| **The question it answers** | "Which machines are actually working for me, and which are standing?" |
| **Key figures** | (1) The spread — "Best 84% utilised, worst 19%, factory 57%"; (2) Rank of machines by utilisation with rank-change arrows; (3) Output value produced per machine (TZS m) against its net book value; (4) Downtime hours and the top downtime reason per machine; (5) Utilisation needed to justify the machine (the break-even line drawn across the ranking) |
| **The comparison** | The factory average and the machine's own utilisation last quarter; the justification line as a printed threshold |
| **Exception lead** | "Machine 3 — 19% utilised, TZS 180m book value, 62% of downtime is 'waiting for material', not breakdown" |
| **Consolidation level** | Factory/site level; rolls to company as an average weighted by book value |
| **Cadence** | Weekly during production runs; monthly otherwise |
| **The decision it triggers** | Sell or hire out a standing machine (Owner); fix scheduling rather than buy a second machine (Factory Manager); refuse the capex request for more capacity (Owner) |
| **Tap-through** | The worst machine's downtime reasons by hours. Refuses to show shift-by-shift production logs |
| **Alert condition** | Push when a machine with book value over TZS 50m records zero output for 14 days |
| **Data needed** | Machine run-hours ⚠ (usually not captured — needs a meter reading or an operator log); planned available hours per machine ⚠; downtime with reason codes ⚠; production output valued and attributed to a machine ⚠; net book value per machine |
| **Novelty** | **NOVEL** — ERPs hold the asset register and the production order but never join them into utilisation the owner can rank |

---

## 14. Vehicle League

| field | content |
|---|---|
| **Screen name** | Vehicle League |
| **Full name** | Vehicle League — cost per kilometre and days off the road, by vehicle |
| **Archetype** | 5 League Table (League × Assets) |
| **The question it answers** | "Which vehicles are cheap to run and which are quietly eating me?" |
| **Key figures** | (1) The spread — "Best TZS 640/km, worst TZS 2,180/km, fleet TZS 980/km"; (2) Ranked vehicles with rank change; (3) Cost per km split into fuel, repairs, tyres, insurance, driver; (4) Days off the road this quarter and the route sales lost while off; (5) Litres per 100km against the vehicle's own history — a step change in fuel use per km is a theft signal, not an engineering one |
| **The comparison** | Fleet average as a reference line; each vehicle against its own 6-month baseline |
| **Exception lead** | "TZ 412 DPX — fuel per km up 41% since April with no load increase" |
| **Consolidation level** | Fleet, by branch and by route; rolls to company |
| **Cadence** | Monthly; on-alert for fuel step changes |
| **The decision it triggers** | Replace or dispose of a vehicle (Owner); investigate fuel drawing at a named branch (GM); re-cost the route delivery charge (CFO) |
| **Tap-through** | The worst vehicle's cost history by month. Refuses to show individual fuel dockets |
| **Alert condition** | Push when fuel per km rises >25% against a vehicle's own 6-month baseline for two consecutive weeks |
| **Data needed** | Odometer readings ⚠; fuel issued per vehicle ⚠ (usually booked as a cost centre expense, not per vehicle); repair/work-order cost linked to the vehicle ⚠; days off the road ⚠; route sales attributable to the vehicle; insurance and licence cost per vehicle |
| **Novelty** | **NOVEL** — van-route businesses lose real money here and the data is almost never assembled per vehicle |

---

## 15. Idle Assets

| field | content |
|---|---|
| **Screen name** | Idle Assets |
| **Full name** | Assets That Earn Nothing — value standing idle, how long, and what it costs to hold |
| **Archetype** | 6 Exception Register (Exception × Assets) |
| **The question it answers** | "What have I bought that is doing nothing?" |
| **Key figures** | (1) Count and money — "34 assets · TZS 612m idle"; (2) Ranked by value at risk with days idle; (3) Holding cost — depreciation still charging, insurance, space, finance cost on the capital (TZS m per month); (4) New vs repeat vs returned-to-use since last month; (5) Estimated realisable value if sold |
| **The comparison** | The same idle value 90 days ago, and the rule itself printed: "no recorded use in 90 days, book value over TZS 2m" |
| **Exception lead** | The largest, oldest idle asset: "Packing line 2 — TZS 210m, idle 14 months, still depreciating TZS 3.5m/month" |
| **Consolidation level** | Site and branch; rolls to company and group |
| **Cadence** | Monthly |
| **The decision it triggers** | Sell, hire out, redeploy to another branch, or write down (Owner); refuse a capex request for the same capability (Owner/CFO) |
| **Tap-through** | The asset's usage history and last recorded use, plus an *accept* action (an idle asset can be a deliberate standby — the register must allow that, with a review date, or it will be ignored within a month) |
| **Alert condition** | Push when idle value crosses TZS 500m at group, or when any single asset over TZS 100m becomes idle |
| **Data needed** | Asset usage signal ⚠ (run-hours, production attribution, or a movement/issue record — most ERPs have none); asset location and status; depreciation charge per asset; insurance per asset ⚠; realisable value estimate ⚠; a standby/accepted flag ⚠ |
| **Novelty** | **NOVEL** — the fixed-asset register exists everywhere; the idleness rule and the holding cost do not |

---

## 16. Assets Not Found

| field | content |
|---|---|
| **Screen name** | Assets Not Found |
| **Full name** | Assets Nobody Can Find — book value, last seen, and who signed for them |
| **Archetype** | 6 Exception Register (Exception × Assets) |
| **The question it answers** | "Do I still own everything the books say I own?" |
| **Key figures** | (1) Count and money — "19 assets · TZS 88m unverified"; (2) Ranked by value with days since last verified; (3) Split by state — not found at count, moved without a transfer note, custodian left the company, never verified since purchase; (4) Verification coverage — "62% of asset value physically verified in the last 12 months"; (5) New vs repeat vs found since last count |
| **The comparison** | The same register after the previous count, and the verification-coverage target (say 100% of value annually) |
| **Exception lead** | "Custodian left in March, 4 assets TZS 31m never handed over" — above the total |
| **Consolidation level** | Site and branch; group roll-up of unverified value |
| **Cadence** | On-alert after each verification round; a standing monthly glance at coverage |
| **The decision it triggers** | Recover from a named custodian or write off with authority (Owner); make handover-on-exit a condition of final dues (GM/CFO); order a count at a branch (Owner) |
| **Tap-through** | The asset's custody chain — who signed, when, where last seen. Refuses to show the full asset register |
| **Alert condition** | Push when an employee holding assets is marked as leaving, before final dues are released |
| **Data needed** | Asset custodian assignment ⚠; asset location with transfer notes ⚠; physical verification date and result per asset ⚠; link between employee exit and asset handover ⚠ (this link almost never exists and is the whole point) |
| **Novelty** | **NOVEL** — the exit-to-handover link turns a dead register into a recovery of real money |

---

## 17. Repair or Replace

| field | content |
|---|---|
| **Screen name** | Repair or Replace |
| **Full name** | Assets Costing More to Keep Than to Replace — 12-month repair spend against replacement price |
| **Archetype** | 6 Exception Register (Exception × Assets) |
| **The question it answers** | "Which machines and vehicles am I repairing to death?" |
| **Key figures** | (1) Count and money — "6 assets past the line · TZS 74m spent on repairs in 12 months"; (2) Ranked by repair spend as a % of replacement price (the rule: over 40% in 12 months); (3) Downtime hours caused by each and the output or sales lost; (4) Repair spend trend per asset — rising, flat; (5) Payback of replacement in months |
| **The comparison** | The replacement price of the same capability, and the asset's own repair spend in the prior 12 months |
| **Exception lead** | "Generator, Kariakoo — TZS 18m repairs in 12 months, replacement TZS 26m, 9 outages" |
| **Consolidation level** | Asset level, grouped by site; rolls to company |
| **Cadence** | Monthly; on-alert when a single repair crosses a threshold |
| **The decision it triggers** | Approve the replacement and stop repairing (Owner); change supplier or workshop (GM); bring maintenance in-house (Factory Manager) |
| **Tap-through** | The repair history for that asset with supplier and cost. Refuses to show every work order across the fleet |
| **Alert condition** | Push when a single repair quote exceeds 15% of replacement price, or when an asset crosses the 40% cumulative line |
| **Data needed** | Repair and maintenance cost **linked to a specific asset** ⚠ (usually booked to a cost centre); replacement price estimate per asset ⚠; downtime hours and lost output/sales ⚠; maintenance supplier per job |
| **Novelty** | **NOVEL** — this is the classic owner's argument ("we keep fixing that lorry") turned into a rule with a number |

---

## 18. Capex Due

| field | content |
|---|---|
| **Screen name** | Capex Due |
| **Full name** | What Capital Spend Falls Due — committed cash by month and the first month it bites |
| **Archetype** | 9 Forecast & Runway (Forecast × Assets) |
| **The question it answers** | "How much capital spend have I already promised, and when does it have to be paid?" |
| **Key figures** | (1) The date — "TZS 310m falls due in October, TZS 90m more than expected free cash"; (2) Committed but unspent capex (TZS m) split into contracted, ordered, approved-not-ordered; (3) Monthly profile for the next 6 months against expected free cash; (4) Maintenance commitments and statutory renewals falling in the same window; (5) Forecast drift — how this profile has moved since last month |
| **The comparison** | The capex budget for the year, the previous month's profile, and the free-cash line drawn across the chart |
| **Exception lead** | The first month where commitments exceed free cash, at the top, with the two largest commitments named |
| **Consolidation level** | Group, split by company; must roll up because the cash is shared |
| **Cadence** | Monthly; weekly in the month before a large commitment |
| **The decision it triggers** | Defer or stage a commitment (Owner); arrange finance now rather than in the month (CFO); cancel an approved-not-ordered item (Owner) |
| **Tap-through** | The commitments in the worst month, ranked by value, with supplier and contractual notice. Refuses to show purchase-order lines |
| **Alert condition** | Push when committed capex in any of the next 3 months exceeds forecast free cash |
| **Data needed** | Capex approvals with expected payment dates ⚠ (approval rarely carries a cash date); contracted vs ordered vs approved status; supplier payment terms; retention and milestone schedules ⚠; the cash forecast from the Cash domain; capex budget for the year ⚠ |
| **Novelty** | CLASSIC in concept, **rarely joined to cash** — the value is the collision date, not the list |

---

## 19. Insurance Gap

| field | content |
|---|---|
| **Screen name** | Insurance Gap |
| **Full name** | Does the Insurance Cover What We Own? — insured value against replacement value, by site |
| **Archetype** | 13 Reconciliation & Assurance (Recon × Assets) |
| **The question it answers** | "If the Mbagala warehouse burned tonight, would the insurance actually rebuild it?" |
| **Key figures** | (1) Verdict and difference — "Under-insured by TZS 1.1bn across 3 sites"; (2) Two sides per site: sum insured vs replacement value of assets plus stock held there; (3) Reconciling reasons — assets added since renewal, stock above the declared average, site not on the policy, policy lapsed; (4) Days since the policy schedule was last updated; (5) Average-clause exposure — the share of any claim that would be cut for under-insurance |
| **The comparison** | Policy sum insured vs current replacement value and current stock at that location, plus how long the gap has persisted |
| **Exception lead** | "Mbagala — stock TZS 900m against a TZS 400m declaration since March" |
| **Consolidation level** | Site and policy; group summary of total exposure |
| **Cadence** | Quarterly, and on-alert whenever stock at a site jumps |
| **The decision it triggers** | Increase cover before the next stock build (Owner/CFO); update the declaration (CFO); challenge a broker (Owner) |
| **Tap-through** | The uncovered assets/stock at the worst site. Refuses to show policy documents |
| **Alert condition** | Push when stock value at any site exceeds its declared sum insured, or when a policy is within 30 days of expiry |
| **Data needed** | Insurance policy schedule with sums insured per site and expiry dates ⚠ (almost never in an ERP); replacement values per asset ⚠; stock value by location (available); asset-to-site mapping ⚠ |
| **Novelty** | **NOVEL** — a rare report that can save the entire business once, and costs nothing but a policy table |

---

## 20. Asset Spend Waiting

| field | content |
|---|---|
| **Screen name** | Asset Spend Waiting |
| **Full name** | Capital Spend Waiting on You — asset and repair requests ranked by what each delay stops |
| **Archetype** | 14 Decision Docket (Docket × Assets) |
| **The question it answers** | "What capital and repair decisions are sitting on me, and what is standing still while I think?" |
| **Key figures** | (1) Queue and cost of delay — "7 items · TZS 420m · oldest 9 days · 2 stopping production"; (2) Each item: what, who asked, how much, why it needs the owner, what breaks if it waits (machine down, branch cannot open, vehicle off the road); (3) Against policy — within limit, over limit, off-budget, above last price paid for the same item; (4) The one deciding fact per item — last price paid, the asset's repair history, remaining capex budget; (5) The owner's own median approval time on this queue |
| **The comparison** | Each item against policy and budget; the queue against the owner's own historical approval speed |
| **Exception lead** | Items ranked by consequence, never by arrival: "Compressor repair TZS 12m — factory line 2 stopped 3 days, TZS 40m output lost so far" |
| **Consolidation level** | Personal — everything addressed to this approver across all companies |
| **Cadence** | Daily glance |
| **The decision it triggers** | The approval itself (Owner/CFO); and over time, raising a limit for the requests always approved unchanged (Owner) |
| **Tap-through** | Approve / reject / send back with a reason, plus the single deciding fact. Refuses to become an informational inbox — items not needing this person's decision never appear |
| **Alert condition** | Push when an item blocking production or a branch opening has waited more than 24 hours |
| **Data needed** | Approval queue with requester and value (exists); consequence-of-delay tagging ⚠ (needs the request to say what stops); last price paid for the same asset class ⚠; remaining capex budget ⚠; the approver's own historical decision times ⚠ |
| **Novelty** | CLASSIC as an inbox, **NOVEL as a report** — the cost-of-delay ranking and the self-measurement are what make it executive |

---

# PART C — OVERHEAD (5)

---

## 21. Overhead vs Plan

| field | content |
|---|---|
| **Screen name** | Overhead vs Plan |
| **Full name** | Overhead Against Plan — the running-cost categories, biggest miss first |
| **Archetype** | 2 Scorecard (Scorecard × Profit) |
| **The question it answers** | "Is the cost of running this business staying inside what we agreed?" |
| **Key figures** | (1) Verdict — "5 of 9 categories over plan · TZS 61m over month-to-date"; (2) Each category — rent, power and water, fuel and transport, security, communication, professional fees, repairs, marketing, bank charges — with actual, budget, gap; (3) Overhead as % of sales vs target; (4) Overhead per branch per month (TZS m) vs plan; (5) The largest single line inside the worst category |
| **The comparison** | The approved budget printed on every row, and the same month last year for categories with no budget |
| **Exception lead** | The biggest miss with its cause visible: "Power +TZS 24m — generator fuel at 3 branches during outages" |
| **Consolidation level** | Group; rolls down to company and branch. Categories must be identical across branches or the ranking is meaningless |
| **Cadence** | Month-end; weekly glance during a cost programme |
| **The decision it triggers** | Cut or renegotiate a named category (Owner); re-budget where the plan was wrong (CFO); withdraw spend authority at a branch (GM) |
| **Tap-through** | The worst category by branch and by supplier. Refuses to show individual invoices |
| **Alert condition** | Push when any single category exceeds budget by more than 25% at month-to-date pace |
| **Data needed** | Overhead budget by category and by branch ⚠ (often only a group-level budget exists); a stable cost-category mapping over the chart of accounts ⚠; sales by company for the ratio; branch allocation rules for shared costs ⚠ |
| **Novelty** | CLASSIC |

---

## 22. Quiet Cost Rises

| field | content |
|---|---|
| **Screen name** | Quiet Cost Rises |
| **Full name** | Costs That Rose Without a Decision — recurring lines up on last year, and by how much |
| **Archetype** | 6 Exception Register (Exception × Buy) |
| **The question it answers** | "Which of my regular bills have crept up without anybody approving an increase?" |
| **Key figures** | (1) Count and money — "17 recurring costs up · TZS 96m a year of extra spend nobody signed"; (2) Ranked by annualised increase: supplier, category, old monthly amount, new monthly amount, % up, month it changed; (3) How many have risen twice or more in 12 months; (4) The share with no contract or approval on record; (5) Total recurring cost base and its year-on-year growth vs inflation |
| **The comparison** | The same line 12 months ago, and the general price level — a 6% rise is normal, a 40% rise is a decision somebody made without telling the owner |
| **Exception lead** | "Security services, Kariakoo — TZS 4.2m to TZS 7.1m in April, no contract variation on file, TZS 35m a year" |
| **Consolidation level** | Group, by company and branch, grouped by supplier (related suppliers merged) |
| **Cadence** | Monthly |
| **The decision it triggers** | Renegotiate or re-tender a named supplier (Owner); require a signed variation before any recurring increase is paid (CFO); withdraw a branch's authority to vary a service (GM) |
| **Tap-through** | The 24-month payment history for that supplier line. Refuses to show one-off purchases (this report is about recurring spend only) |
| **Alert condition** | Push when a recurring monthly cost rises more than 15% against its own 6-month average |
| **Data needed** | Recurring-cost identification ⚠ (needs a rule that recognises a repeating supplier + category + roughly monthly cadence — most ERPs never mark a cost as recurring); supplier grouping for related parties ⚠; contract/approval reference per recurring line ⚠; an inflation reference ⚠ |
| **Novelty** | **NOVEL** — cost creep is the classic silent killer of a trading group's profit and no ERP names it; the increases are individually too small to notice and collectively enormous |

---

## 23. Fixed Cost Base

| field | content |
|---|---|
| **Screen name** | Fixed Cost Base |
| **Full name** | What It Costs to Open the Doors — the monthly fixed cost and how it moved this year |
| **Archetype** | 3 Position & Movement (Position × Profit) |
| **The question it answers** | "If we sold nothing next month, what would we still have to pay?" |
| **Key figures** | (1) Monthly fixed cost (TZS m) — the number the owner should know by heart; (2) Movement waterfall from January — new branches, headcount added, rent increases, contracts added, savings realised, closures; (3) Fixed cost as % of total cost (the operating-gearing read); (4) Days of cash the fixed base consumes; (5) Fixed cost per branch, highest first |
| **The comparison** | The same figure at the start of the year and 12 months ago — the direction of the base, not its level, is the message |
| **Exception lead** | "Fixed cost up TZS 47m a month since January — TZS 31m of it from two branches opened in March" |
| **Consolidation level** | Group and company; branch detail on tap |
| **Cadence** | Month-end; a hard read before any expansion decision |
| **The decision it triggers** | Whether the group can survive a bad quarter (Owner); whether to open another branch (Owner); which fixed cost to convert to variable — own fleet vs hired transport, permanent vs casual labour (GM/CFO) |
| **Tap-through** | The composition of the base by category. Refuses to show variable cost (that is the margin report's job) |
| **Data needed** | A fixed vs variable classification of every cost account ⚠ (this classification does not exist in a standard chart of accounts and is the single build item that unlocks this report and Break-Even League); lease and contract commitments ⚠; permanent payroll separated from casual/piece-rate ⚠ |
| **Novelty** | **NOVEL** — almost no ERP separates fixed from variable cost, so no owner is ever shown the one number that determines how much of a downturn the business can absorb |

---

## 24. Contracts Due

| field | content |
|---|---|
| **Screen name** | Contracts Due |
| **Full name** | What We Are Locked Into — rents, licences and service contracts by renewal date and notice period |
| **Archetype** | 9 Forecast & Runway (Forecast × Buy) |
| **The question it answers** | "What am I committed to, for how long, and when is my last chance to get out or renegotiate?" |
| **Key figures** | (1) The date — "Last day to give notice on the Kariakoo lease is 12 September (25 days)"; (2) Total committed spend for the next 12 months (TZS m) and how much of it is still cancellable; (3) The next 6 contracts by decision date — supplier, annual value, notice period, auto-renew yes/no; (4) Contracts that auto-renewed in the last year without review (count and value); (5) Licences and statutory permits expiring (business licence, fire, environmental, EFD/TRA registrations) |
| **The comparison** | Committed spend against the overhead budget for the same period, and against the previous view of this list (a contract that silently rolled is the failure) |
| **Exception lead** | The nearest irreversible date first, not the biggest contract |
| **Consolidation level** | Group, by company and site |
| **Cadence** | Monthly glance; the alerts do the real work |
| **The decision it triggers** | Serve notice, renegotiate or let it renew — deliberately (Owner); tender a service before auto-renewal (CFO/GM); renew a licence before it lapses and stops trading (GM) |
| **Tap-through** | The contract's terms summary and payment history. Refuses to show the document itself on the phone |
| **Alert condition** | Push at notice-date minus 30 days and again at minus 7; push at licence expiry minus 45 days |
| **Data needed** | A contract register with start/end, renewal and notice dates, annual value, auto-renew flag ⚠ (very rarely held anywhere but a drawer); licence and permit register with expiry ⚠; link from contract to the supplier account for spend history |
| **Novelty** | **NOVEL** — auto-renewing service contracts and lapsed licences are pure avoidable loss, and this is a table plus a date rule |

---

## 25. Break-Even League

| field | content |
|---|---|
| **Screen name** | Break-Even League |
| **Full name** | Break-Even League — what each branch must sell to cover its own cost, and the day it gets there |
| **Archetype** | 5 League Table (League × Profit) |
| **The question it answers** | "Which branches have paid for themselves this month, and which are still being carried by the others?" |
| **Key figures** | (1) The spread — "Best branch covers its cost by day 11, worst by day 26, two never do"; (2) Ranked branches by day-of-month reached break-even, with rank change; (3) Break-even sales per branch (TZS m) against actual sales; (4) Branches that did not break even last month and the amount the group carried (TZS m); (5) Margin rate assumed for each branch, printed, so nobody argues about the ranking rule |
| **The comparison** | Each branch against its own break-even point and against its position last month; the group's average break-even day as a reference line |
| **Exception lead** | "Tabora has not covered its own cost in 4 months — carried TZS 96m so far this year" |
| **Consolidation level** | Branch, ranked within comparable formats only (counter vs depot vs route are separate tables); rolls to company |
| **Cadence** | Weekly during the month — the "which day did you break even" read only works if the owner watches it running |
| **The decision it triggers** | Close, relocate or shrink a branch (Owner); cut a branch's fixed cost rather than push its sales (GM); reset a branch manager's monthly target to the break-even number (GM/Branch Manager) |
| **Tap-through** | The branch's own fixed cost composition. Refuses to show its transaction-level sales |
| **Alert condition** | Push when a branch fails to break even for two consecutive months |
| **Data needed** | Fixed cost allocated per branch ⚠ (including a defensible share of central cost, or explicitly excluding it — the choice must be stated on screen); gross margin rate per branch; sales by day per branch; branch format classification ⚠; branch opening date to exclude units under 90 days ⚠ |
| **Novelty** | **NOVEL** — every owner asks "is that branch making money?" and gets a P&L; the break-even day answers it in a form a branch manager can be held to |

---

# Build items this pack generates (⚠ consolidated)

These are the data the ERP must start capturing for the suite to exist. Ranked by how many reports each unlocks.

| # | Build item | Unlocks |
|---|---|---|
| 1 | **Approved establishment + wage budget decomposed into heads × rate** | People vs Plan, Wage Bill Gap |
| 2 | **Shift rosters, planned hours, attendance capture, absence reasons** | Today's Attendance, Overtime Causes, Payroll Check |
| 3 | **Overtime reason codes** | Overtime Causes, Wage Bill Gap |
| 4 | **Leave entitlement, accrual and per-employee daily rate for valuation** | Leave Owed, Key Person Risk |
| 5 | **Hire/exit dates with coded exit reasons and cost-to-hire** | New Hires Kept |
| 6 | **Duty/substitute map and relationship ownership per employee** | Key Person Risk |
| 7 | **Statutory due-date calendar + remittance matching + penalty rules** | Payroll Taxes Due |
| 8 | **Independent employee register, unique bank account / TIN / NIDA, payroll master-change log** | Payroll Check |
| 9 | **Asset usage signal — machine run-hours, planned availability, downtime reasons** | Machine League, Idle Assets, Capital Employed |
| 10 | **Per-vehicle odometer, fuel issue and repair cost** | Vehicle League, Repair or Replace |
| 11 | **Asset custodian, location, transfer notes, verification date; exit-to-handover link** | Assets Not Found |
| 12 | **Repair/maintenance cost linked to a specific asset + replacement price estimate** | Repair or Replace, Idle Assets, Machine League |
| 13 | **Capex approval carrying an expected cash date, and commitment status** | Capex Due, Asset Spend Waiting |
| 14 | **Insurance policy schedule: sums insured per site, expiry dates** | Insurance Gap |
| 15 | **Fixed vs variable classification on every cost account** | Fixed Cost Base, Break-Even League |
| 16 | **Recurring-cost flag + related-supplier grouping + contract/approval reference** | Quiet Cost Rises |
| 17 | **Contract and licence register: renewal date, notice period, auto-renew, annual value** | Contracts Due |
| 18 | **Overhead budget by category *and* branch; branch format and opening date; shared-cost allocation rule** | Overhead vs Plan, Break-Even League, Staff Cost League |
| 19 | **Consequence-of-delay tagging on approval requests; approver decision-time history** | Asset Spend Waiting |
| 20 | **Cost of borrowing / facility rates** | Capital Employed |

**Note on one argued cell:** *Leave Owed* occupies **Position & Movement × People**, which the governing matrix leaves blank. The argument: accrued leave is a true stock (opening → earned → taken → paid out → forfeited → closing), it is denominated in money, and it behaves exactly like the other Position & Movement instantiations. It is submitted for approval rather than assumed.

===== DOMAIN: governance — GOVERNANCE, RISK, TAX AND CONTROL =====
# Governance, Risk, Tax & Control — Executive Report Suite

**OrbixERP Executive Mobile · Domain pack 4 of N · v1 · 2026-08-18**
25 reports designed from first principles. Feasibility deliberately not considered; ⚠ marks data the ERP probably does not capture yet — each is a build item, not a reason to cut the report.

---

## Index

| # | Tier | Screen name | Archetype | Matrix cell | Novelty |
|---|---|---|---|---|---|
| 1 | 1 | Broken Rules | Exception Register | Profit / all ● | NOVEL |
| 2 | 1 | Sold Below Cost | Exception Register | Sales ● | CLASSIC |
| 3 | 1 | Over-Limit Discounts | Exception Register | Sales ● | CLASSIC |
| 4 | 1 | EFD Gaps | Exception Register | Tax ● | CLASSIC |
| 5 | 1 | Tills Gone Dark | Exception Register | Tax ● | NOVEL |
| 6 | 1 | Supplier Bank Changes | Exception Register | Buy ● | NOVEL |
| 7 | 1 | Instant Approvals | Exception Register | Buy ● / People ○ | NOVEL |
| 8 | 1 | What Is Final | Reconciliation & Assurance | Cash ● / Profit ○ | NOVEL |
| 9 | 1 | Approval Delay | Cycle-Time & Flow | Buy ● | CLASSIC |
| 10 | 1 | Unfixed Breaches | Ageing Pyramid | Buy ● / Stock ● | NOVEL |
| 11 | 2 | Backdated Entries | Exception Register | Tax ● | NOVEL |
| 12 | 2 | Voided After Print | Exception Register | Sales ● / Tax ● | NOVEL |
| 13 | 2 | Odd Journals | Exception Register | Profit ● / Cash ● | NOVEL |
| 14 | 2 | Unsplit Duties | Concentration & Exposure | People ● | NOVEL |
| 15 | 2 | Missing Stock | Variance Bridge | Stock ● | NOVEL |
| 16 | 2 | VAT Match | Reconciliation & Assurance | Tax ● | CLASSIC |
| 17 | 2 | Tax Exposure | Position & Movement | Tax ● | NOVEL |
| 18 | 2 | Tax Falling Due | Forecast & Runway | Tax ● / Cash ● | NOVEL |
| 19 | 2 | Close Progress | Scorecard | Tax ● / Profit ● | CLASSIC |
| 20 | 2 | Just Under Limit | Exception Register | Buy ● | NOVEL |
| 21 | 2 | Missing Numbers | Reconciliation & Assurance | Tax ● / Cash ● | NOVEL |
| 22 | 3 | Dormant Access | Exception Register | People ○ | NOVEL |
| 23 | 3 | New Powers | Exception Register | People ○ | NOVEL |
| 24 | 3 | Shared Bank Details | Exception Register | Buy ● / Cust ○ | NOVEL |
| 25 | 3 | Breach Rate | Trend & Trajectory | Profit ● | NOVEL |

19 NOVEL · 6 CLASSIC.

---

# TIER 1 — opened weekly or more

---

## 1. Broken Rules

| field | content |
|---|---|
| **Screen name** | Broken Rules |
| **Full name** | What Broke the Rules This Week — value at risk and repeats |
| **Archetype** | Exception Register (the parent register; every other register in this pack is one of its rows) |
| **The question it answers** | "Did anybody step outside our rules this week, and how much money is standing on it?" |
| **Key figures** | (1) Open breaches — count; (2) Value at risk — TZS; (3) New this week vs cleared this week — two counts as one chip; (4) Repeat breaches — same rule, same person or branch, twice or more in 30 days; (5) Age of the oldest open breach — days; (6) The rule family carrying the most value |
| **The comparison** | The same count and value seven days ago, plus the repeat share. A breach that survives a week is a control failure; a breach that recurs is a management failure. Both read differently from a one-off. |
| **Exception lead** | The rule family with the largest value at risk, and beside it the branch with the most repeats — not the total count |
| **Consolidation level** | Group headline, rolls down company → branch. A branch manager sees only their own rows; the Owner sees the group with a branch strip. Must roll up: one number for the group. |
| **Cadence** | Glance daily, worked weekly |
| **The decision it triggers** | Owner: assign each rule family to a named person with a date. And the inverse decision — any rule generating more than ~100 breaches a week is retired or re-set, because the rule is wrong, not the staff. |
| **Tap-through** | The rule family → its own dedicated register (reports 2, 3, 11, 12, 13, 20…). It deliberately refuses to show individual document lines here; this screen is a switchboard, not a work list. |
| **Alert condition** | Push when open value at risk crosses TZS 50M, when any single breach above TZS 10M appears, or when any breach ages past 14 days unassigned |
| **Data needed** | A written rule catalogue with thresholds, owners and severities ⚠ (in most businesses the policy lives in people's heads and is never machine-readable); breach detection evaluated at posting time; a value-at-risk figure per breach ⚠; and an accept / assign / clear workflow with a reason and a name ⚠ — without "accept", known-and-approved exceptions clog the register and it dies within a fortnight |
| **Novelty** | NOVEL — most ERPs scatter exceptions across a dozen module screens; none of them totals the money |

---

## 2. Sold Below Cost

| field | content |
|---|---|
| **Screen name** | Sold Below Cost |
| **Full name** | Lines Sold Under Landed Cost — value lost, by branch and seller |
| **Archetype** | Exception Register |
| **The question it answers** | "Are we selling anything at a loss without me knowing?" |
| **Key figures** | (1) Margin lost — TZS; (2) Lines below landed cost — count; (3) Below-cost share of sales value — %; (4) Worst branch and its lost margin; (5) Unauthorised vs cleared-for-clearance split ⚠ |
| **The comparison** | The prior four-week average, the group below-cost rate, and the policy tolerance (zero unauthorised). Clearance and promotional lines are shown separately, never counted as breaches. |
| **Exception lead** | The largest single loss-making line's value and where it happened; then the seller with the most such lines |
| **Consolidation level** | Group → company → branch → seller. Rolls up on lost margin, not on count. |
| **Cadence** | Glance daily during promotions, worked weekly otherwise |
| **The decision it triggers** | GM: withdraw price-override authority from a named seller, or fix the price list. Owner: if the same product recurs, the cost is wrong, not the price — send it to costing. |
| **Tap-through** | Product → its cost history and the price it should have carried. Refuses to show the invoice document; the owner must not be pulled into transaction review. |
| **Alert condition** | Push on any single line losing more than TZS 2M, or a day's total loss above TZS 5M |
| **Data needed** | Landed cost as at the moment of sale (not today's cost); price-override authority per role ⚠; a clearance/promotion flag on the line ⚠ so approved discounting is not reported as a breach |
| **Novelty** | CLASSIC |

---

## 3. Over-Limit Discounts

| field | content |
|---|---|
| **Screen name** | Over-Limit Discounts |
| **Full name** | Discounts Given Beyond Policy — who gave them and to whom |
| **Archetype** | Exception Register |
| **The question it answers** | "Who is giving away money beyond what I allowed, and who keeps receiving it?" |
| **Key figures** | (1) Discount value beyond policy — TZS; (2) Lines beyond policy — count; (3) Average excess over the allowed rate — percentage points; (4) Top giver and their excess value; (5) Top receiving customer and their excess value; (6) Share going to the same five customers — % |
| **The comparison** | Each row against the discount ceiling for that role and that customer tier — printed on the row. Plus the prior month and the peer branch rate. |
| **Exception lead** | The customer receiving the most excess discount across sellers — because a customer appearing under three different sellers is a different problem from one generous salesperson |
| **Consolidation level** | Group → branch → seller, and a second axis by customer. Rolls up on excess value. |
| **Cadence** | Weekly |
| **The decision it triggers** | GM: reset a seller's discount authority. Owner: renegotiate the customer's terms formally rather than let the discount leak through the counter. |
| **Tap-through** | The receiving customer → their full discount, margin and payment history. Refuses the individual sales lines. |
| **Alert condition** | Push when a single order's excess discount exceeds TZS 3M, or when one customer's monthly excess passes TZS 10M |
| **Data needed** | A discount policy matrix by role, customer tier and product family ⚠ (this almost never exists as data); list price at the time of sale; the approval record where an over-limit discount was authorised |
| **Novelty** | CLASSIC |

---

## 4. EFD Gaps

| field | content |
|---|---|
| **Screen name** | EFD Gaps |
| **Full name** | Sales With No Fiscal Receipt — count, value, days open |
| **Archetype** | Exception Register |
| **The question it answers** | "Have we sold anything the taxman has no receipt for?" |
| **Key figures** | (1) Unfiscalised taxable sales — value TZS; (2) Count of them; (3) VAT unremitted on those sales at 18% — TZS; (4) Age of the oldest gap — days; (5) Branches or tills affected — count |
| **The comparison** | The same figures seven days ago, and the standard, which is zero. Also the split between device failure and process failure ⚠ — the first is an IT ticket, the second is a discipline problem. |
| **Exception lead** | The oldest unfiscalised sale, then the largest — age first, because a gap that survives a filing date becomes a penalty |
| **Consolidation level** | Company (VRN-level, since TRA assesses per registered entity) with a branch and till breakdown. Must roll up per company, not per group. |
| **Cadence** | Glance daily; hard review before the 20th |
| **The decision it triggers** | Branch Manager: issue the missing receipt today. CFO: decide whether the month's gap is disclosed and settled voluntarily rather than found later. |
| **Tap-through** | Branch → its unfiscalised sales with age and value. Refuses to show customer-identifying detail on the group screen. |
| **Alert condition** | Push when unfiscalised value exceeds TZS 1M, or when any gap passes 48 hours, or on any gap surviving into a filed period |
| **Data needed** | The TRA acknowledgement/receipt reference stored against each taxable sale ⚠ (the acknowledgement code is frequently not persisted, only the print event); taxable-vs-exempt classification per line; reason codes on failures ⚠ |
| **Novelty** | CLASSIC |

---

## 5. Tills Gone Dark

| field | content |
|---|---|
| **Screen name** | Tills Gone Dark |
| **Full name** | Tills That Traded While the EFD Was Down — hours and value |
| **Archetype** | Exception Register |
| **The question it answers** | "Did any counter keep taking money while its fiscal device was not working?" |
| **Key figures** | (1) Trading minutes with no working fiscal device; (2) Sales value taken during those minutes — TZS; (3) Tills affected — count; (4) Longest single outage — minutes, and where; (5) Repeat tills — third or later outage in 30 days; (6) Manual receipts issued during outages — count ⚠ |
| **The comparison** | The same till's outage minutes last month, and the group's normal outage rate. A till that goes dark every Friday afternoon is not a hardware problem. |
| **Exception lead** | The till with the highest value taken while dark — value while dark, not outage length |
| **Consolidation level** | Branch and till, rolling to company. Group headline is total value taken while dark. |
| **Cadence** | Glance daily; on-alert in real time |
| **The decision it triggers** | Branch Manager: stop the till or switch to the standby device. Owner: if one till repeats, that is not a device fault — send someone unannounced. |
| **Tap-through** | The till → its outage timeline against its sales by hour. Refuses individual receipts. |
| **Alert condition** | Push immediately when a till records sales for more than 15 continuous minutes with no successful fiscalisation, and again if the same till repeats within 7 days |
| **Data needed** | A device heartbeat and per-receipt acknowledgement timestamp ⚠ (an ERP records the sale, not the device's health); till trading-hour calendar ⚠; the manual receipt book register ⚠, which today exists only on paper |
| **Novelty** | NOVEL — this is the exact window in which unrecorded cash sales happen, and no standard ERP report looks at it |

---

## 6. Supplier Bank Changes

| field | content |
|---|---|
| **Screen name** | Supplier Bank Changes |
| **Full name** | Supplier Bank Details Changed Before Payment — who and when |
| **Archetype** | Exception Register |
| **The question it answers** | "Did anyone change where our money goes, just before we sent it?" |
| **Key figures** | (1) Supplier bank accounts changed in the last 30 days — count; (2) Value paid to those accounts since the change — TZS; (3) Changes followed by a payment within 7 days — count and value; (4) Changes made by someone who can also release payments — count; (5) Changes with no supporting document on file — count ⚠ |
| **The comparison** | The prior 90-day change rate (a sudden cluster is the signal), and the verification standard — every change should carry a callback verification ⚠; the screen shows how many did not. |
| **Exception lead** | Change-then-pay inside seven days, largest value first. That single pattern is the classic invoice-redirection fraud. |
| **Consolidation level** | Group, because suppliers are shared across companies and the fraud crosses entities. Company and branch shown as attributes, not as the rollup axis. |
| **Cadence** | Glance daily in the week of a payment run; weekly otherwise |
| **The decision it triggers** | CFO: freeze the payment and order a callback to a previously known number, not the one on the changed record. Owner: if the changer can also release, that is a segregation failure — fix it the same day. |
| **Tap-through** | The supplier → old details, new details, who changed them, when, and every payment since. Refuses to display full account numbers on the group screen (last four digits only). |
| **Alert condition** | Push immediately on any bank change followed by a payment above TZS 5M within 7 days, and on any bank change made by a user who holds payment-release rights — regardless of value |
| **Data needed** | Field-level change history on supplier bank fields with before/after values and the user ⚠ (most ERPs overwrite silently or bury it in a generic audit table nobody reads); payment run timing; a callback-verification record ⚠; the map of which users hold payment-release rights |
| **Novelty** | NOVEL — the single highest-value fraud vector in a trading group, and almost never surfaced to an owner |

---

## 7. Instant Approvals

| field | content |
|---|---|
| **Screen name** | Instant Approvals |
| **Full name** | Approvals Decided in Under a Minute — who and how much |
| **Archetype** | Exception Register |
| **The question it answers** | "Which of my approvals are real decisions, and which are people clicking?" |
| **Key figures** | (1) Approvals decided in under 60 seconds — count and value TZS; (2) Instant share per approver — % of their decisions; (3) Their rejection rate — % (an approver who never rejects is not approving); (4) Median decision time per approver — minutes; (5) Instant approvals that later turned into a breach — count and value ⚠ |
| **The comparison** | Each approver against their peers holding the same limit, and against their own median last quarter. The group median is printed as the reference line. |
| **Exception lead** | The approver with the highest instant value — value passed through without reading, not the count |
| **Consolidation level** | Group, by approver. Rolls up to total value approved instantly. |
| **Cadence** | Weekly digest; reviewed properly monthly |
| **The decision it triggers** | Owner, two opposite decisions from one screen: withdraw or reduce a delegation where instant approvals keep turning into breaches; and *raise* the limit where an approver has approved 300 items instantly and none ever went wrong — that step is costing days and buying nothing. |
| **Tap-through** | The approver → their decision-time distribution and the outcomes of what they approved. Refuses to show the items themselves; this is about the approving, not the item. |
| **Alert condition** | Push only when a single instant approval exceeds TZS 20M; otherwise no push — this is a pattern report, not an incident report |
| **Data needed** | Both timestamps per approval — when it reached the approver and when they decided ⚠ (systems typically store only the decision time, which makes this report impossible until fixed); the link from an approval to any exception the approved item later caused ⚠; delegation and out-of-office records |
| **Novelty** | NOVEL — measures the quality of governance rather than its existence, and is the only report here that can *remove* a control |

---

## 8. What Is Final

| field | content |
|---|---|
| **Screen name** | What Is Final |
| **Full name** | How Much of This Month Is Final — and what is provisional |
| **Archetype** | Reconciliation & Assurance |
| **The question it answers** | "Can I believe the numbers I looked at this morning?" |
| **Key figures** | (1) The verdict in words — "Final to 15 Aug; the last three days are provisional"; (2) Share of month-to-date sales value posted and costed — %; (3) Unposted POS batches — count and value TZS; (4) Goods received but not yet costed (GRNI) — value TZS; (5) Sub-ledger to general-ledger control differences — TZS and how many accounts; (6) Unallocated receipts — value TZS |
| **The comparison** | The same six measures at the same point in the prior month, and the close calendar's expectation for today. Trust is judged against how it usually is, not against perfection. |
| **Exception lead** | The single largest provisional block, named in business terms ("TZS 310M of factory receipts not yet costed") |
| **Consolidation level** | Group headline with a per-company row, because one company's stuck batch poisons the consolidated figure and the owner must know which |
| **Cadence** | Glance daily — this is the screen that should be read *before* any other report in the suite |
| **The decision it triggers** | Owner: defer a decision that rests on a provisional number, or proceed knowing the tolerance. CFO: clear the specific named block before the board pack is issued. |
| **Tap-through** | The provisional block → what is stuck, since when, and with whom. Refuses transaction lists entirely. |
| **Alert condition** | Push when provisional value exceeds 10% of month-to-date sales; and — the valuable one — push when a figure the owner viewed in the last 24 hours has since moved more than 5% ⚠ |
| **Data needed** | Posting and costing status by document class; batch queue depth and last successful run of each integration ⚠; sub-ledger vs GL control balances for AR, AP, stock and cash; a record of which figures each user has viewed, to power the restatement alert ⚠ |
| **Novelty** | NOVEL — the trust layer. An executive suite without it is a suite of assertions, and the first time a number silently restates, every other screen loses credibility |

---

## 9. Approval Delay

| field | content |
|---|---|
| **Screen name** | Approval Delay |
| **Full name** | How Long From Request to Decision — and where it stalls |
| **Archetype** | Cycle-Time & Flow |
| **The question it answers** | "How long do my people wait for a yes, and who is holding them up?" |
| **Key figures** | (1) Median hours from request to final decision; (2) 90th percentile hours — the tail is where customers are lost; (3) Items waiting now beyond 48 hours — count and value TZS; (4) The slowest stage and its median days; (5) Items currently blocked from despatch or payment by an approval — count and value ⚠ |
| **The comparison** | Against the agreed service standard (say 24 hours) ⚠ printed as a tick on every stage bar, and against the same measure a quarter ago |
| **Exception lead** | The stage exceeding its standard by the most, with the number of items stuck in it right now — not the overall median |
| **Consolidation level** | Group by approval type (purchase, discount, credit limit, payment, write-off); drill by company. Rolls up on median and on stuck value. |
| **Cadence** | Weekly |
| **The decision it triggers** | GM: re-route authority or appoint a deputy for the bottleneck stage. Owner: raise a limit and delete a step where the wait costs more in delayed trade than the control saves — and accept when the bottleneck is himself. |
| **Tap-through** | The worst stage → the items stuck in it, each with an owner's name. Refuses to show completed items; the point is what is stuck, not what flowed. |
| **Alert condition** | Push when any item blocking despatch or a payment run has waited more than 48 hours, or when the 90th percentile doubles week on week |
| **Data needed** | Arrival and decision timestamps at every stage ⚠; delegation and absence records ⚠; the downstream link showing what an approval is blocking ⚠; a defined service standard per approval type ⚠ |
| **Novelty** | CLASSIC |

---

## 10. Unfixed Breaches

| field | content |
|---|---|
| **Screen name** | Unfixed Breaches |
| **Full name** | Control Breaches by Age — how long unfixed, and with whom |
| **Archetype** | Ageing Pyramid |
| **The question it answers** | "What did we already find, tell somebody about, and still not fix?" |
| **Key figures** | (1) Open breach value — TZS; (2) Share older than 30 days — %; (3) The four buckets — 0–7, 8–30, 31–90, 90+ days — each with count and value; (4) Top five owners by aged value; (5) Breaches formally accepted and closed this month — count |
| **The comparison** | The same pyramid shape 30 days ago, drawn as a ghost outline behind the current one. A pyramid fattening at the base means the organisation is finding and fixing; fattening at the tip means it is finding and forgetting. |
| **Exception lead** | The 90+ bucket's value and the person holding most of it |
| **Consolidation level** | Group, with an axis by owning manager rather than by branch — ageing is about accountability, not geography |
| **Cadence** | Weekly |
| **The decision it triggers** | Owner: escalate a named manager's aged pile, or formally accept the risk and clear it off the register. Nothing may simply sit. |
| **Tap-through** | The 90+ bucket → its breaches with owner and last action date. Refuses to reopen the underlying documents; this screen is about the *fixing*, not the finding. |
| **Alert condition** | Push when any breach crosses 60 days, or when the 90+ bucket grows two weeks running |
| **Data needed** | Assignment of each breach to a named owner with a due date ⚠; a status lifecycle including an explicit "accepted" outcome with a reason ⚠; the acceptance authority ladder (who may accept what value) ⚠ |
| **Novelty** | NOVEL — ageing is routinely applied to debtors and never to controls, which is why control findings quietly die |

---

# TIER 2 — monthly, or on alert

---

## 11. Backdated Entries

| field | content |
|---|---|
| **Screen name** | Backdated Entries |
| **Full name** | Entries Posted Into an Earlier Period — how far and by whom |
| **Archetype** | Exception Register |
| **The question it answers** | "Is somebody still changing months I have already reported on?" |
| **Key figures** | (1) Backdated documents — count and value TZS; (2) Furthest back — days; (3) Entries landing in a period already reported to the board or filed with TRA — count and value; (4) Users involved — count, with the top one named; (5) Share carrying a stated reason — % ⚠ |
| **The comparison** | The prior month's backdating volume, and the close calendar — postings before the close is signed are routine, postings after it are the exception. The screen separates the two. |
| **Exception lead** | Entries into an already-filed tax period, largest value first — those directly change a return already submitted |
| **Consolidation level** | Company (period locks and filings are per registered entity), rolling to a group count |
| **Cadence** | Month-end, and on alert |
| **The decision it triggers** | CFO: lock the period harder, or restate deliberately and formally. Owner: if backdating is routine, the close calendar is fiction and needs re-setting. |
| **Tap-through** | The user → what they backdated and why. Refuses to show the journal itself on the executive screen. |
| **Alert condition** | Push on any posting into a period already filed with TRA, and on any backdated entry above TZS 10M |
| **Data needed** | System entry timestamp held alongside document date (many systems keep only the document date); period lock state and who may override it ⚠; a per-period "reported to board" and "filed with TRA" flag ⚠; a mandatory reason on override ⚠ |
| **Novelty** | NOVEL |

---

## 12. Voided After Print

| field | content |
|---|---|
| **Screen name** | Voided After Print |
| **Full name** | Documents Voided After Printing or Fiscalising — value, who |
| **Archetype** | Exception Register |
| **The question it answers** | "Are we cancelling paperwork after the customer already had it — and after the goods left?" |
| **Key figures** | (1) Voids after print — count and value TZS; (2) Voids after fiscalisation — count and value (each needs a credit note to TRA); (3) Voids where despatch was already confirmed — count and value ⚠; (4) Median minutes between print and void; (5) Users with three or more voids in one day — count, top one named |
| **The comparison** | The branch's own prior-month void rate per 1,000 documents, and the group rate. Voiding is normal; a branch voiding at four times the group rate is not. |
| **Exception lead** | Voids where the goods had already gone — a document cancelled while stock physically left is a theft pattern, not a typing error |
| **Consolidation level** | Branch and till, rolling to company and group |
| **Cadence** | Weekly, on alert |
| **The decision it triggers** | Branch Manager: explain each same-day cluster. Owner: withdraw void rights at the counter and require a supervisor, if clusters repeat. |
| **Tap-through** | The user → their voids with times, values and reasons. Refuses to show the customer's identity on the group screen. |
| **Alert condition** | Push when a fiscalised sale above TZS 2M is voided, or when any user voids three or more documents within one hour |
| **Data needed** | A print and fiscalise event log with timestamps ⚠; the void event with user and mandatory reason ⚠; gate-pass or despatch confirmation linked to the document ⚠ |
| **Novelty** | NOVEL |

---

## 13. Odd Journals

| field | content |
|---|---|
| **Screen name** | Odd Journals |
| **Full name** | Manual Journals Into Unusual Accounts — who and how much |
| **Archetype** | Exception Register |
| **The question it answers** | "Has anybody moved money by hand into an account that never normally sees a hand?" |
| **Key figures** | (1) Manual journal value this period — TZS; (2) Journals into accounts that see manual entries fewer than three times a year — count and value; (3) The single largest manual journal — value and account; (4) Journals posted by someone who can also approve payments — count; (5) Journals with no supporting document attached — count and value ⚠; (6) Manual share of movement in the worst account — % |
| **The comparison** | Each account's own 12-month manual-entry history (that history *is* what makes an entry "odd"), and the prior month's total manual value |
| **Exception lead** | The rarest account with the biggest manual entry — rarity times value, not value alone |
| **Consolidation level** | Company, since the chart of accounts and the ledger are per company; group headline is total manual value |
| **Cadence** | Month-end, and on alert |
| **The decision it triggers** | CFO: require documentation and a second signature for that account class. Owner: ask what problem is being solved by hand every month — a recurring manual journal usually means a broken process upstream. |
| **Tap-through** | The account → its manual entries with poster, narration and attachment status. Refuses the double-entry detail; the owner needs the pattern, not the ledger. |
| **Alert condition** | Push on any manual journal above TZS 20M, or on any manual entry into a revenue, tax, stock or cash control account outside the close window |
| **Data needed** | Journal source flag (manual vs system-generated); per-account manual-entry frequency history; narration quality and attachment presence ⚠; poster identity mapped against payment-approval rights |
| **Novelty** | NOVEL — the rarity baseline per account is what standard "journal listing" reports never compute |

---

## 14. Unsplit Duties

| field | content |
|---|---|
| **Screen name** | Unsplit Duties |
| **Full name** | How Much One Person Can Do Alone — the value at risk |
| **Archetype** | Concentration & Exposure |
| **The question it answers** | "Is there anybody here who can create a supplier, order from them, receive the goods and pay them, without meeting another human being?" |
| **Key figures** | (1) Users who can complete a whole money cycle alone — count; (2) Value that actually flowed through such an unsupervised path in the last 90 days — TZS; (3) The worst combination of rights, named by right and not by person; (4) Branches with at least one such user — count and share of branches; (5) How many of these users are also dormant-privileged (cross-reference to report 22) |
| **The comparison** | The same count last quarter — is the exposure deepening or closing — and the board's stated tolerance (typically zero at head office, at most one at a small up-country branch) ⚠ drawn as a threshold line |
| **Exception lead** | The single combination through which the most real value flowed, not the theoretical worst |
| **Consolidation level** | Group, broken by company and branch. Small branches will always look worse — the screen states this rather than pretending otherwise. |
| **Cadence** | Month-end, and every access review |
| **The decision it triggers** | GM: split the duty, or install a compensating check (a second signature above a value, a monthly review by the branch manager's peer). Owner: accept the risk explicitly at named small branches, with a value cap. |
| **Tap-through** | The combination → the users holding it and the value each moved. Refuses to publish a list of names on the group screen — R7: name the condition, not the person. |
| **Alert condition** | Push when a *new* user acquires a complete-cycle capability, and when value through an unsupervised path exceeds TZS 100M in a quarter |
| **Data needed** | A map from system permissions to business steps — create party, raise order, approve, receive, pay ⚠ (the SoD matrix simply does not exist in most ERPs and is the main build item here); the actual execution paths, so theory can be separated from practice; branch headcount ⚠ to judge what is achievable |
| **Novelty** | NOVEL |

---

## 15. Missing Stock

| field | content |
|---|---|
| **Screen name** | Missing Stock |
| **Full name** | Where the Missing Stock Went — losses by cause and branch |
| **Archetype** | Variance Bridge |
| **The question it answers** | "The count is short — how much of that is thieving and how much is paperwork?" |
| **Key figures** | (1) The gap itself — counted value minus book value, TZS, as the headline; then the bridge bars: (2) recorded damage and expiry; (3) unit-of-measure and conversion error; (4) stock in transit between branches; (5) valuation and re-costing effect; (6) unexplained residual — TZS and as a % of the gap |
| **The comparison** | The bridge *is* the comparison (book → counted). Alongside it, shrinkage as a percentage of sales versus the group and versus the accepted tolerance ⚠. |
| **Exception lead** | The unexplained residual — its size and the branch holding most of it. If the residual is the biggest bar, the report says so plainly: "we have not explained this." |
| **Consolidation level** | Branch and location, rolling to company and group. Rolls up on value, and the residual rolls up separately — it must never be netted away against a positive variance elsewhere. |
| **Cadence** | Every count cycle (month-end for most, weekly for high-value lines) |
| **The decision it triggers** | Owner: unexplained residual above tolerance at one branch triggers an unannounced count and a look at that branch's void and discount registers. GM: fix the UoM master if conversion error is the biggest bar — that one is free to fix. |
| **Tap-through** | The residual bar → the product families and locations composing it. Refuses to show count sheets or individual adjustments. |
| **Alert condition** | Push when the unexplained residual exceeds TZS 5M in one count, or when a branch's shrinkage rate doubles against its own six-month average |
| **Data needed** | A regular cycle-count discipline with results by location ⚠ (the report is worthless without counting); mandatory reason codes on every adjustment ⚠; in-transit stock states; UoM conversion history; an agreed shrinkage tolerance ⚠ |
| **Novelty** | NOVEL — most systems report a shrinkage number; almost none decompose it into causes, which is the difference between a statistic and an investigation |

---

## 16. VAT Match

| field | content |
|---|---|
| **Screen name** | VAT Match |
| **Full name** | Does the VAT Match? — return, ledger and EFD side by side |
| **Archetype** | Reconciliation & Assurance |
| **The question it answers** | "Do the three VAT numbers that must agree — my books, my machines, and what I filed — actually agree?" |
| **Key figures** | (1) The verdict and the difference — "Out by TZS 2.4M, three months running"; (2) Output VAT per the ledger; (3) Output VAT per EFD Z-totals ⚠; (4) Output VAT per the filed return ⚠; (5) Input VAT claimed but resting on invoices without a valid supplier VRN — TZS ⚠; (6) The unexplained portion, isolated from timing |
| **The comparison** | The three sides against each other, plus the age of the difference. A one-month timing difference is expected; the same difference in a third month is a control failure. |
| **Exception lead** | The unexplained portion — separated hard from timing, in-transit and known adjustments, because that separation is the difference between an accounting artefact and a problem |
| **Consolidation level** | Company/VRN only. This report must *not* roll up to group: TRA assesses per registered entity and a netted group figure would be meaningless and misleading. |
| **Cadence** | Month-end, before filing on the 20th |
| **The decision it triggers** | CFO: hold the filing until the unexplained portion clears, or file and disclose. Owner: if input VAT rests on invalid VRNs, stop claiming it — that is a penalty waiting to happen. |
| **Tap-through** | The unexplained portion → its reconciling items grouped by reason, with an owner each. Refuses the transaction listing. |
| **Alert condition** | Push when the difference exceeds TZS 2M, when it persists two consecutive months, or five days before the filing date if unreconciled |
| **Data needed** | The values actually filed per month, held in the system ⚠ (returns are usually filed outside the ERP and never captured back); EFD Z-report totals per device per day ⚠; supplier VRN capture and validity checking ⚠; exempt/zero-rated classification per line |
| **Novelty** | CLASSIC in principle, rarely built in practice |

---

## 17. Tax Exposure

| field | content |
|---|---|
| **Screen name** | Tax Exposure |
| **Full name** | What We Would Owe If TRA Came Tomorrow — by cause |
| **Archetype** | Position & Movement |
| **The question it answers** | "If the taxman audited us this week, what is the worst realistic bill?" |
| **Key figures** | (1) Total exposure — tax plus estimated penalty and interest, TZS; then the movement: (2) added this month; (3) cleared this month; (4) re-estimated; (5) the largest single cause named (unfiscalised sales / input VAT without a valid VRN / withholding tax not deducted / PAYE and SDL under-remitted / late-filing penalties); (6) share of the exposure already provided for in the books — % ⚠ |
| **The comparison** | Last month's closing exposure — the movement is the message — and the provision held against it. An exposure that is fully provided is a known cost; an unprovided one is a surprise. |
| **Exception lead** | The cause that grew most this month, and any exposure older than one filing cycle |
| **Consolidation level** | Company/VRN, with a group total presented as the owner's aggregate risk. Movement classes are stated in tax terms, never GL account names. |
| **Cadence** | Month-end |
| **The decision it triggers** | CFO: raise or release the provision. Owner: decide on a voluntary disclosure while penalties are still discretionary, rather than after an assessment. |
| **Tap-through** | The cause → its composition by month and entity. Refuses to show individual transactions; this is a position, not a work list. |
| **Alert condition** | Push when total exposure grows more than 20% in a month, or crosses TZS 100M, or when any single cause doubles |
| **Data needed** | Penalty and interest rules per tax head, as computable rules ⚠; withholding-tax applicability per supplier type and service ⚠; supplier VRN validity ⚠; the provision balances carried in the ledger; and an explicit link from each exception register (reports 4, 5, 21) into this position so that exposure accumulates automatically ⚠ |
| **Novelty** | NOVEL — ERPs compute tax payable; almost none compute tax *exposure*, which is the number that actually frightens an owner |

---

## 18. Tax Falling Due

| field | content |
|---|---|
| **Screen name** | Tax Falling Due |
| **Full name** | What the Taxman Wants Next — amount, date and cash cover |
| **Archetype** | Forecast & Runway |
| **The question it answers** | "What must I pay the taxman next, on what date, and will the money be there?" |
| **Key figures** | (1) The next due date and its amount — the date leads, not the amount; (2) Total falling due in 30 days across all heads — TZS; (3) Projected cash on each due date and the cover ratio; (4) The first date on which cover falls below 1.0 — or "covered throughout"; (5) Last month's estimate versus what was actually paid — the forecast's own accuracy, % |
| **The comparison** | Against the cash forecast on the same dates, and against the previous run of this same forecast — drift in the estimate is itself a signal that something upstream is moving |
| **Exception lead** | The first uncovered date, stated in working days from today |
| **Consolidation level** | Company (each VRN and PAYE registration files separately), with a group cash view because the cash may sit in another company's account — and if it does, the screen says whether moving it is permitted |
| **Cadence** | Weekly, and hard before the 7th and the 20th |
| **The decision it triggers** | CFO: pull a collection or delay a supplier payment run to cover the date. Owner: authorise an inter-company transfer or an overdraft draw — while there is still time, not on the due date. |
| **Tap-through** | The due date → the heads composing it and the assumptions behind the amount. Refuses to show the underlying returns. |
| **Alert condition** | Push five working days before any due date where cover is below 1.2, and immediately on any filing date passing without a filing recorded |
| **Data needed** | A filing and payment calendar per tax head per registered entity ⚠ (VAT by the 20th, PAYE and SDL by the 7th, withholding by the 7th, provisional tax quarterly — as data, not as a wall chart); accrued liability per head; the cash forecast; and a record of what was filed and paid, to score accuracy ⚠ |
| **Novelty** | NOVEL — joins tax to cash, which is exactly how an owner experiences it and exactly how no ERP reports it |

---

## 19. Close Progress

| field | content |
|---|---|
| **Screen name** | Close Progress |
| **Full name** | How Far the Close Has Got — by company, step and days late |
| **Archetype** | Scorecard |
| **The question it answers** | "Are the books closing on time, and if not, who is holding them?" |
| **Key figures** | (1) The verdict — "6 of 9 steps done, 2 days behind"; (2) Steps complete out of total, per company; (3) Days ahead or behind the close calendar; (4) The blocking step and its owner; (5) Companies not yet closed — count; (6) Reconciliations outstanding — count and unreconciled value TZS |
| **The comparison** | The agreed close timetable (say, working day 5) printed against each step, and the same day in the prior month. Ordered by size of miss, not by step sequence. |
| **Exception lead** | The step furthest behind its target day, with its owner's name |
| **Consolidation level** | Group screen with a row per company; a group close cannot be declared complete while any company is open, and the screen enforces that visually |
| **Cadence** | Daily during the close window, silent otherwise |
| **The decision it triggers** | CFO: reallocate people to the blocking step. Owner: refuse to accept a board pack built on an unclosed month, or accept it explicitly with the caveat printed. |
| **Tap-through** | The blocking step → what is outstanding within it and with whom. Refuses to show the accounting detail. |
| **Alert condition** | Push when the close passes the agreed working day, or when any company slips two days behind the others |
| **Data needed** | A close checklist as data — steps, owners, target working days, dependencies ⚠ (almost always a spreadsheet); period lock state per company; reconciliation sign-off records with a name and time ⚠ |
| **Novelty** | CLASSIC |

---

## 20. Just Under Limit

| field | content |
|---|---|
| **Screen name** | Just Under Limit |
| **Full name** | Requests Priced Just Below an Approval Limit — by requester |
| **Archetype** | Exception Register |
| **The question it answers** | "Is anyone shaping their paperwork so it never reaches my desk?" |
| **Key figures** | (1) Documents landing within 5% below an approval threshold — count and value TZS; (2) Observed versus expected frequency at that band — the ratio is the whole report (a clean business shows roughly chance; 4× chance is deliberate); (3) Splits — two or more documents to the same supplier, by the same requester, on the same day, that together cross a limit — count and value; (4) The top requester and their share; (5) Value that thereby avoided the Owner's review — TZS |
| **The comparison** | The expected distribution of document values (from the business's own 12-month history) against the observed clustering, plus peer requesters facing the same limit |
| **Exception lead** | The splits — because a split is a decision, whereas a single just-under document may be a coincidence |
| **Consolidation level** | Group by requester and by approval limit; drill by company and branch |
| **Cadence** | Month-end |
| **The decision it triggers** | Owner: aggregate a requester's authority to a rolling weekly limit so splitting stops working; or investigate a named supplier relationship. GM: if everyone clusters below one limit, the limit is set too low and is buying evasion instead of control. |
| **Tap-through** | The requester → their value distribution as a histogram with the limit marked. Refuses to show the documents themselves; the pattern is the finding. |
| **Alert condition** | No push routinely — this is a monthly pattern report. Push only when a single split above TZS 20M is detected. |
| **Data needed** | The approval limit ladder as data, with its history ⚠ (limits change and the analysis must use the limit in force at the time); document clustering keys — supplier, requester, day, cost centre; 12 months of value history to build the expected distribution |
| **Novelty** | NOVEL — the "just under" signature is well known to auditors and effectively never surfaced by an ERP |

---

## 21. Missing Numbers

| field | content |
|---|---|
| **Screen name** | Missing Numbers |
| **Full name** | Gaps in the Invoice and Receipt Numbers — where and how old |
| **Archetype** | Reconciliation & Assurance |
| **The question it answers** | "Every receipt book and every till counts upwards — are any numbers simply not there?" |
| **Key figures** | (1) The verdict — "Sequence intact" or "9 numbers unaccounted for"; (2) Gaps by document class — fiscal receipts, cash receipts, invoices, delivery notes, GRNs; (3) Gaps explained by a recorded void or cancellation versus unexplained — two counts; (4) Age of the oldest unexplained gap — days; (5) Estimated value at risk, using the average value of the documents on either side of the gap ⚠ |
| **The comparison** | Against the standard, which is zero unexplained, and against last month's count. Explained gaps are shown but never counted as breaches. |
| **Exception lead** | Unexplained gaps in fiscal receipts and cash receipts — the two classes where a missing number means missing money |
| **Consolidation level** | Till, receipt book and branch — sequences are per issuing point, so this cannot be aggregated except as a count of affected points |
| **Cadence** | Weekly, and at every count or cash-up |
| **The decision it triggers** | Branch Manager: produce the missing document or the void record today. Owner: a branch with repeated unexplained gaps gets an unannounced visit — this is the strongest single indicator of unrecorded trade. |
| **Tap-through** | The issuing point → its gap list with dates and neighbouring document values. Refuses to show the surrounding documents' customers. |
| **Alert condition** | Push on any unexplained gap in a fiscal or cash receipt sequence older than 24 hours |
| **Data needed** | The issued-number sequence per branch, till and physical receipt book ⚠ (manual books are usually invisible to the ERP entirely — registering them is the build item); the void and cancellation register with numbers ⚠; document class definitions with their sequence rules |
| **Novelty** | NOVEL |

---

# TIER 3 — specialist, on demand or at review points

---

## 22. Dormant Access

| field | content |
|---|---|
| **Screen name** | Dormant Access |
| **Full name** | Powerful Accounts Nobody Uses — what they can still do |
| **Archetype** | Exception Register |
| **The question it answers** | "Which accounts can still move my money, but nobody has logged into for two months?" |
| **Key figures** | (1) Privileged accounts with no login in 60 days — count; (2) Of those, how many can move money — approve a payment, change bank details, post a journal; (3) Accounts belonging to people who have left the company — count ⚠; (4) Shared or generic accounts (counter1, admin) — count; (5) Root or administrator-equivalent accounts — count, against the agreed ceiling |
| **The comparison** | The same counts at the last access review, and the policy standard — zero dormant privileged accounts, and a named ceiling on administrator accounts |
| **Exception lead** | Leavers' accounts that are still enabled, then dormant accounts that can move money |
| **Consolidation level** | Group — identity is group-wide even when duties are branch-bound |
| **Cadence** | Quarterly at the access review, plus on alert |
| **The decision it triggers** | Owner: order immediate disabling; GM signs the access review as a record. Also: every shared account is either given an owner or killed. |
| **Tap-through** | The account → what it can do, expressed in business steps, and when it was last used. Refuses to show password or session detail. |
| **Alert condition** | Push when a dormant privileged account is used again after 60 days of silence — a reawakened dormant admin account is one of the highest-quality fraud signals available |
| **Data needed** | Last login and last action per user; the permission-to-money map ⚠ (shared with report 14); HR leaver dates joined to user accounts ⚠ — this join usually does not exist and is why leavers keep their access for years |
| **Novelty** | NOVEL |

---

## 23. New Powers

| field | content |
|---|---|
| **Screen name** | New Powers |
| **Full name** | Rights Granted Since the Last Review — who gained what |
| **Archetype** | Exception Register |
| **The question it answers** | "Who has quietly become more powerful since I last looked?" |
| **Key figures** | (1) Rights granted since the last signed access review — count; (2) Users who gained money-moving rights — count, with the largest new capability named; (3) Grants with no approval record behind them — count ⚠; (4) Self-grants, where the granter and the grantee are the same person — count (the standard is zero); (5) Days since the last signed access review |
| **The comparison** | The prior review period's grant volume, and the ceiling rule: no self-grants, and no grant above the granter's own authority |
| **Exception lead** | Self-grants first, then unapproved grants of payment-release or administrator rights |
| **Consolidation level** | Group, by grantee and by right |
| **Cadence** | Quarterly, on demand before an audit |
| **The decision it triggers** | Owner: revoke and re-approve. GM: sign or refuse to sign the access review — the screen is the review. |
| **Tap-through** | The grantee → every right they now hold that they did not hold at the last review. Refuses to show the full permission tree; only the delta matters. |
| **Alert condition** | Push immediately on any self-grant, and on any grant of payment-release or administrator rights |
| **Data needed** | A grant history with granter, grantee, right and timestamp ⚠; access review sign-off dates and signatories ⚠; the authority ceiling rule (nobody may grant what they do not hold) as an enforced rule, not a convention |
| **Novelty** | NOVEL |

---

## 24. Shared Bank Details

| field | content |
|---|---|
| **Screen name** | Shared Bank Details |
| **Full name** | Suppliers Sharing Bank or Phone Details With Staff |
| **Archetype** | Exception Register |
| **The question it answers** | "Are we paying a supplier that is actually one of my own people?" |
| **Key figures** | (1) Matches found — count, split supplier↔employee, supplier↔customer, supplier↔supplier; (2) Value paid to matched parties in the last 12 months — TZS; (3) Matched parties created in the last 90 days — count (new and matching is the sharpest signal); (4) Matches where the same user created both records — count; (5) Tax identification numbers reused across parties — count |
| **The comparison** | The last scan's results and the standard, which is zero unexplained matches. Legitimate matches (a staff member who genuinely is a landlord, a supplier who is also a customer) are recorded as accepted and stop appearing. |
| **Exception lead** | A supplier created in the last 90 days sharing bank details with an employee, ranked by value already paid |
| **Consolidation level** | Group — parties are shared across companies, and this fraud specifically exploits the seams between them |
| **Cadence** | On demand, and quarterly |
| **The decision it triggers** | Owner: hold all payments to the matched party and order a physical verification of the supplier's premises. GM: explain the relationship or terminate it. |
| **Tap-through** | The matched pair → what they share, who created each record, and the payment history. Refuses to show full bank account numbers or employee personal data beyond the matched field. |
| **Alert condition** | Push when a newly created supplier matches a staff member's bank account or phone number — at creation, before the first payment |
| **Data needed** | Bank account, phone, tax ID and address normalised into comparable form across employees, suppliers and customers ⚠; employee bank details available to the matcher under a controlled, logged access path ⚠ (a genuine privacy design question, not a technical one); record creator identity; an accepted-relationship register ⚠ |
| **Novelty** | NOVEL — ghost-supplier detection, and the report an owner is most likely to say he has never been able to see |

---

## 25. Breach Rate

| field | content |
|---|---|
| **Screen name** | Breach Rate |
| **Full name** | Which Way Breaches Are Going — per 1,000 documents |
| **Archetype** | Trend & Trajectory |
| **The question it answers** | "Are we getting more disciplined, or am I just shouting more?" |
| **Key figures** | (1) Breaches per 1,000 documents this month; (2) The verdict in words — "Falling, third consecutive month" or "Flat within the normal range"; (3) Value at risk per TZS 1bn traded — the money-weighted equivalent, because ten small breaches and one large one are different diseases; (4) The rule family driving the current move; (5) Median days to fix — the discipline of the response, not just of the behaviour |
| **The comparison** | A 24-month line with a normal band (rolling mean ± typical variation), the same month last year, and event markers where a policy or system control changed |
| **Exception lead** | A run of consecutive months outside the band — and only then; a single month's wiggle is explicitly reported as noise, which is the whole point of this screen |
| **Consolidation level** | Group, normalised by document volume so a growing business does not look like a deteriorating one. Branch trends behind the tap-through. |
| **Cadence** | Month-end; the current partial month is shown but excluded from the trend |
| **The decision it triggers** | Owner: decide whether the control programme is working, and — if breaches fall only where a *system* control was installed and not where a memo was issued — stop issuing memos and buy system controls instead. |
| **Tap-through** | The series decomposed by rule family, so the owner can see which controls improved. Refuses to show individual breaches. |
| **Alert condition** | No push. This report exists precisely to stop the owner reacting to a single bad week. |
| **Data needed** | Breach history retained long enough to build a band (24 months); document volumes for normalisation; a change log of policy and control events to place the markers ⚠ — without markers, an improvement cannot be attributed to anything and will not be repeated |
| **Novelty** | NOVEL |

---

## Cross-cutting build items (the ⚠ list, consolidated)

These are the data the suite needs and the ERP most likely does not hold. Ranked by how many reports they unblock.

| # | Build item | Unblocks |
|---|---|---|
| 1 | **A machine-readable rule catalogue** — rule, threshold, severity, owner, and an accept/assign/clear lifecycle with reasons | 1, 2, 3, 10, 25 — the entire exception spine |
| 2 | **Approval timestamps at both ends** (received and decided), per stage, with delegation and absence | 7, 9, and the Decision Docket in the parent suite |
| 3 | **Permission-to-business-step map** (which rights let a person create, approve, receive, pay) | 14, 22, 23, 6, 13 |
| 4 | **EFD device heartbeat, per-receipt TRA acknowledgement, and the manual receipt book register** | 4, 5, 21, 16, 17 |
| 5 | **Field-level change history on money-critical master fields** (supplier bank, credit limit, price, cost) with before/after and user | 6, 24 |
| 6 | **Filed-return values and the tax calendar held inside the system**, per registered entity | 16, 17, 18 |
| 7 | **HR leaver dates joined to user accounts** | 22, 23 |
| 8 | **Reason codes on every adjustment, void and override** — mandatory, from a controlled list | 2, 11, 12, 15, 21 |
| 9 | **A close checklist as data** — steps, owners, target working days, sign-offs | 19, 8, 11 |
| 10 | **Posting/costing status and integration run health, exposed as a first-class figure** | 8 — and therefore the credibility of every other screen in the whole app |
| 11 | **Cycle-count discipline with results by location** | 15 |
| 12 | **Party detail normalisation across employees, suppliers and customers**, with a privacy-controlled matching path | 24 |
| 13 | **Approval limit ladder with history**, and a value-aggregation rule to defeat splitting | 20, 9 |
| 14 | **A "reported to board" / "filed with TRA" flag per period** | 11, 16 |
| 15 | **Viewed-figure log**, to power the "the number you saw has moved" alert | 8 |

**Design notes for whoever builds this.** Twelve of the twenty-five are Exception Registers: build one register shell — count and money at the top, new/repeat/cleared trio, value-ranked rows, ageing, assign/accept/clear write-back — and the domain instantiates almost for free. Report 8 (*What Is Final*) should ship first regardless of tier: it is the trust substrate, and every other screen in this pack is an assertion until it exists. Report 1 (*Broken Rules*) is the only tile this domain deserves on the owner's six-tile home screen; everything else lives one tap behind it or fires as an alert.

===== DOMAIN: strategy — GROUP, GROWTH AND FORWARD-LOOKING =====
# Group, Growth & Forward-Looking — Executive Report Suite

**OrbixERP Executive Mobile · Domain 5 of the catalogue · v1 · 2026-08-18**
25 reports designed from first principles for the proprietor of a multi-company Tanzanian trading + manufacturing group. Feasibility deliberately ignored; ⚠ marks data the group must start capturing.

**Two sanctioned family extensions** (declared, not smuggled):
- **Flash** template extended from `Today's <thing>` to `This <period> So Far`. A group owner does not watch the day — the day is the branch manager's clock. His clock is the month against plan.
- **Forecast & Runway** template extended from `How Long <thing> Lasts` to `How the <period> Will End`. A landing forecast carries the same commitment (a value, a date, a confidence) as a runway; only the wall is a date on the calendar rather than a threshold.

---

## Tier index

| # | Screen name | Archetype | Novelty | Cadence |
|---|---|---|:--:|---|
| **TIER 1 — opened weekly or more** |
| 1 | The Seven Numbers | Scorecard | CLASSIC | Weekly |
| 2 | Month So Far | Flash | CLASSIC | Daily |
| 3 | Company League | League Table | CLASSIC | Weekly |
| 4 | Plan Gap | Variance Bridge | CLASSIC | Weekly |
| 5 | Year-End Forecast | Forecast & Runway | CLASSIC | Weekly |
| 6 | Group Money Left | Forecast & Runway | CLASSIC | Weekly / on-alert |
| 7 | Losing Money | Exception Register | CLASSIC | Weekly |
| 8 | Real Growth | Variance Bridge | **NOVEL** | Monthly |
| 9 | Capital League | League Table | **NOVEL** | Monthly |
| **TIER 2 — monthly or on alert** |
| 10 | Group Trajectory | Trend & Trajectory | CLASSIC | Month-end |
| 11 | Bigger or Busier | Trend & Trajectory | **NOVEL** | Quarterly |
| 12 | Season Shape | Trend & Trajectory | **NOVEL** | Monthly |
| 13 | Money Tied Up | Position & Movement | **NOVEL** | Monthly |
| 14 | Intercompany | Position & Movement | CLASSIC | Monthly |
| 15 | Unapproved Moves | Exception Register | **NOVEL** | On-alert |
| 16 | What Moves Profit | Concentration & Exposure | **NOVEL** | Quarterly |
| 17 | Weaker Shilling | Concentration & Exposure | **NOVEL** | On-alert |
| 18 | Bank Covenants | Scorecard | CLASSIC | Monthly |
| 19 | Owner Drawings | Position & Movement | **NOVEL** | Monthly |
| 20 | Capital Waiting | Decision Docket | CLASSIC | On-alert |
| 21 | New Branch Payback | Cohort & Retention | **NOVEL** | Quarterly |
| **TIER 3 — specialist / on demand** |
| 22 | Group vs Companies | Reconciliation & Assurance | CLASSIC | Month-end |
| 23 | Plan Credibility | Reconciliation & Assurance | **NOVEL** | Quarterly |
| 24 | Investment Payback | Scorecard | **NOVEL** | Quarterly |
| 25 | Head Office Cost | Position & Movement | **NOVEL** | Quarterly |

13 of 25 are NOVEL.

---

# TIER 1

---

## 1. The Seven Numbers

| field | content |
|---|---|
| **Screen name** | The Seven Numbers |
| **Full name** | The Seven Numbers — the group's measures against board plan |
| **Archetype** | 2 · Scorecard |
| **The question it answers** | "Of the seven things I said this group must deliver this year, how many are we delivering?" |
| **Key figures** | (1) Verdict: `4 of 7 on plan`; (2) Group revenue MTD vs plan (TZS M, gap); (3) Group gross margin % vs plan (pp gap); (4) Group profit before tax MTD vs plan (TZS M, gap); (5) Closing cash across all companies vs plan floor (TZS M); (6) Debtor days vs standard (days); (7) Stock cover vs standard (weeks); (8) Factory output vs standard (units, %). |
| **The comparison** | The **printed target** on every row — board plan v-n, with its approval date. Gap in the owner's unit (TZS millions, days, percentage points), never as an index. |
| **Exception lead** | Rows sorted by **size of miss**, biggest miss at the top. The verdict chip is the colour of the worst row, not the average. |
| **Consolidation level** | **Group**, must roll up. Every row is the eliminated consolidated figure; tap-through decomposes to company. |
| **Cadence** | Glance weekly; the home-screen anchor tile. |
| **The decision it triggers** | **Owner + GM:** which single miss gets management attention this week — and, at quarter end, whether the plan or the performance is wrong. |
| **Tap-through** | A red row opens **its own Variance Bridge** (rows 2–4 open *Plan Gap*; row 5 opens *Group Money Left*). It deliberately refuses to show any eighth measure, any ledger account, and any per-branch detail. |
| **Alert condition** | Push when a measure that was green for 2+ weeks turns red, or when the verdict falls below `4 of 7`. Never push the weekly result itself. |
| **Data needed** | Consolidated P&L with eliminations; ⚠ **board plan by company by month, versioned and frozen with an approval date** (a plan that can be silently edited destroys this screen); ⚠ **agreed standards for debtor days, stock cover and factory output** — these are policy numbers, not derived ones; cash balances across all companies and currencies. |
| **Novelty** | CLASSIC |

---

## 2. Month So Far

| field | content |
|---|---|
| **Screen name** | Month So Far |
| **Full name** | This Month So Far — pace against the same point last month |
| **Archetype** | 1 · Flash (period extension) |
| **The question it answers** | "Are we on pace this month, or am I going to be surprised on the 31st?" |
| **Key figures** | (1) Group sales month-to-date (TZS M, rounded to 0.1M — no false precision); (2) Working-day pace: `Day 12 of 24 working days — 47% of the month elapsed, 41% of plan billed`; (3) Group margin % MTD; (4) Cash banked MTD across all companies; (5) Projected close at current pace (TZS M) vs plan. |
| **The comparison** | Same **working-day position** in the prior month and in the same month last year — not calendar day, because a month with three Sundays and Eid is not comparable to one without. Delta chips only. |
| **Exception lead** | If any company is more than 10 pp behind pace, that company's name replaces the fourth pulse line: `Tembo Foods at 29% of plan on day 12`. |
| **Consolidation level** | **Group** headline; one-tap strip to **company**. Rolls up; branch detail is refused here (that is the branch manager's screen). |
| **Cadence** | Glance daily, before 09:00. |
| **The decision it triggers** | **Owner:** make one phone call today rather than a rescue at month-end. **GM:** re-point the sales push at the lagging company. |
| **Tap-through** | The **company strip** — same five numbers per company. Refuses transaction rows, invoice numbers, and any customer name. |
| **Alert condition** | Push once per month, on the working day the projected close first falls more than 10% below plan — not every morning. |
| **Data needed** | Posted and unposted sales across companies; ⚠ **working-day calendar per company including Tanzanian public holidays, Eid and factory shutdowns**; monthly plan phased to working days (⚠ most plans are phased evenly, which makes this screen lie in Ramadan); cash receipts by day. |
| **Novelty** | CLASSIC — but the working-day pace denominator is the part almost every ERP gets wrong. |

---

## 3. Company League

| field | content |
|---|---|
| **Screen name** | Company League |
| **Full name** | Company League — profit per company against plan and peers |
| **Archetype** | 5 · League Table |
| **The question it answers** | "Which of my businesses is actually carrying the group, and which is being carried?" |
| **Key figures** | (1) The spread: `Best 19% net margin · worst -3% · group 11%`; (2) per company: net margin % and profit TZS M; (3) achievement vs own plan (%); (4) rank-change arrow vs last month; (5) group average printed as a reference line. |
| **The comparison** | Two lines at once: each company against **its own plan** (fair — different businesses) and against the **group rate** (brutal — capital is fungible). A company can beat its plan and still be the worst use of the owner's money; this screen shows both facts on one row. |
| **Exception lead** | Bottom of the table shown in full; the middle collapses (`+3 companies between`). A company that fell two or more ranks is flagged before a chronically last one — a collapse and a laggard need different actions. |
| **Consolidation level** | **Company**, ranked; group total is a reference line only. Standalone figures **before** intercompany elimination, with the eliminated amount printed per row so nobody argues the table is inflated by internal trade. |
| **Cadence** | Weekly glance; the serious read is month-end. |
| **The decision it triggers** | **Owner:** where next month's attention, capital and the good manager go. **GM:** which practice to copy from the top company into the bottom one. |
| **Tap-through** | A row opens **that company's Scorecard**. Refuses to name individual managers on the tile (R7) and refuses to rank on absolute profit, which would let the biggest company win forever. |
| **Alert condition** | Push when any company's net margin crosses zero, in either direction. |
| **Data needed** | P&L by company; intercompany trade tagged per transaction; ⚠ **central/head-office cost allocation basis, agreed and stable** (change it mid-year and the league becomes politics); ⚠ **a comparability rule** — companies trading under 12 months shown but excluded from ranking. |
| **Novelty** | CLASSIC |

---

## 4. Plan Gap

| field | content |
|---|---|
| **Screen name** | Plan Gap |
| **Full name** | Where the Group Profit Went — plan to actual, by cause |
| **Archetype** | 4 · Variance Bridge |
| **The question it answers** | "We planned 640 million and made 480. Where exactly did the 160 go?" |
| **Key figures** | (1) Headline is the **gap**: `TZS 160M below plan`, not the profit; (2) six named cause bars, largest first, in TZS M: selling price, volume, product mix, purchase cost, discount given, overheads; (3) residual bar, which must stay under 10% of the gap or the screen prints `Not fully explained`; (4) the same gap last month, so the owner sees whether the same cause repeats. |
| **The comparison** | The bridge *is* the comparison: board plan → actual. A second toggle bridges **against last year** for months where the plan is disputed. |
| **Exception lead** | The single largest destroying bar is called out in words above the chart: `Purchase cost took TZS 71M — Tembo Foods, cooking oil`. |
| **Consolidation level** | **Group** by default, must roll up; toggle to a single company. Each bar is decomposable to company. |
| **Cadence** | Weekly during the month; formally at month-end close. |
| **The decision it triggers** | The specific corrective act, and it differs per bar. **Owner:** price bar → authorise a price rise. **GM:** mix bar → change what the sales force pushes. **CFO:** discount bar → tighten discount authority. **Branch Manager:** volume bar → the branch plan is re-cut. |
| **Tap-through** | One bar opens its **League Table** — which companies and branches created that specific effect. Refuses to show invoice lines; if the owner needs an invoice, the wrong person is looking. |
| **Alert condition** | Push when any single cause bar exceeds TZS 50M in a month, or when the same cause is the largest bar for three consecutive months (that is a structural problem, not a bad month). |
| **Data needed** | Plan at price × volume × mix level, not just a profit total (⚠ **most plans are a single revenue number — this report requires the plan to be built the way the bridge decomposes it, which is the single biggest planning-discipline build item in this domain**); actual cost of sales at moving average with GRNs costed; discount recorded as a line-level amount, not a net price; overhead actuals mapped to plan lines. |
| **Novelty** | CLASSIC |

---

## 5. Year-End Forecast

| field | content |
|---|---|
| **Screen name** | Year-End Forecast |
| **Full name** | How the Year Will End — forecast close against board plan |
| **Archetype** | 9 · Forecast & Runway (period extension) |
| **The question it answers** | "If nothing changes, what number do I take to the board in December — and how wrong have my forecasts been before?" |
| **Key figures** | (1) Forecast full-year profit before tax, as a **band**: `TZS 1.9 – 2.3Bn, likely 2.1Bn`; (2) plan for the same line: `2.6Bn`; (3) the shortfall and what it would take to close it: `TZS 500M short — needs +9% on remaining months`; (4) previous forecast run and the drift since (`was 2.3Bn on 18 Jul, down 200M`); (5) forecast accuracy record: `Last four forecasts averaged 6% optimistic`. |
| **The comparison** | Three at once: forecast vs **plan** (are we going to make it), forecast vs **last forecast** (which way is the answer moving), forecast vs **own past accuracy** (how much to trust it). Forecast drift is itself the executive signal. |
| **Exception lead** | If the forecast moved more than 5% since the previous run, the drift and its single largest cause lead the screen, above the headline band. |
| **Consolidation level** | **Group**, must roll up; per-company forecasts underneath, each with its own band. |
| **Cadence** | Weekly refresh, serious read monthly; mandatory read before any board or bank meeting. |
| **The decision it triggers** | **Owner + CFO:** the pre-emptive act while there is still time — hold a capex, re-cut a bonus scheme, warn the board early rather than confess late. **GM:** commit to a specific catch-up number by company. |
| **Tap-through** | The **assumption list** — the 3–5 assumptions that move the answer most, each with its value and its sensitivity (`Oil price +10% → -TZS 130M`). Refuses to show the month-by-month projection grid; that is a CFO spreadsheet, not an owner screen. |
| **Alert condition** | Push when the likely case first crosses below 90% of plan, and on any single run that moves the likely case by more than 5%. |
| **Data needed** | Actuals to date; ⚠ **committed order book and contracted revenue with dates**; ⚠ **seasonal profile by company from 3+ years of history**; ⚠ **a stored history of every forecast run, so the report can grade itself** (almost no ERP keeps its own past forecasts — this is a small table and an enormous credibility gain); known one-offs calendar (factory shutdown, new branch opening, contract expiry) ⚠. |
| **Novelty** | CLASSIC as a forecast; the self-grading accuracy line is NOVEL. |

---

## 6. Group Money Left

| field | content |
|---|---|
| **Screen name** | Group Money Left |
| **Full name** | How Long the Group's Money Lasts — cash plus undrawn credit |
| **Archetype** | 9 · Forecast & Runway |
| **The question it answers** | "Across all my companies together, on what date do I run out of room — counting what the bank will still lend me?" |
| **Key figures** | (1) **The date**: `Group falls below TZS 200M on 14 September — 19 working days`; (2) available today: `cash TZS 640M + undrawn facilities TZS 400M = TZS 1.04Bn`; (3) the trapped portion: `TZS 180M sits in companies that cannot lend it to the one that needs it`; (4) the largest single outflow before that date, named; (5) how the same date has moved since last week. |
| **The comparison** | The forecast against the **floor the owner set**, against **last week's date** (is the wall approaching faster than the calendar?), and against the same period last year. |
| **Exception lead** | The **trapped cash** line leads whenever group-available cash is comfortable but a single company is not — the classic group failure where the owner believes he is liquid and one subsidiary bounces a cheque. |
| **Consolidation level** | **Group**, but explicitly *not* a simple sum — it separates freely movable cash from cash trapped by ownership, currency, or a lender's restriction. Company detail one tap down. |
| **Cadence** | Weekly; daily inside 20 working days of the crossing date. |
| **The decision it triggers** | **Owner + CFO:** draw the facility now while the bank is calm; move money between companies with a proper intercompany loan; delay a payment run; pull a collection. |
| **Tap-through** | The **driver list** — the receipts and payments that move the date most, ranked by days gained or lost. Refuses to show the bank statement. |
| **Alert condition** | Push when the crossing date falls inside 20 working days, and again whenever the date moves in by more than 5 working days in a single week. |
| **Data needed** | Cash and bank across all companies and currencies; ⚠ **facility limits, drawn amounts and expiry dates per lender**; ⚠ **restrictions on moving money between companies** (lender covenants, minority shareholders, TRA considerations) — this is legal/contractual data no ERP holds; supplier payment due dates; ⚠ **promise-to-pay dates from customers**; payroll and tax payment calendar ⚠. |
| **Novelty** | CLASSIC in single-company form; the **trapped-cash split and facility headroom make the group version NOVEL** — most owners see a group cash total that is not actually spendable. |

---

## 7. Losing Money

| field | content |
|---|---|
| **Screen name** | Losing Money |
| **Full name** | Units That Lost Money — how much, how long, and where |
| **Archetype** | 6 · Exception Register |
| **The question it answers** | "Which parts of this group are consuming my money rather than making it, and for how long has that been true?" |
| **Key figures** | (1) `4 units losing · TZS 71M this month · worst has lost money 7 months running`; (2) per row: unit name, loss this month (TZS M), consecutive losing months, cumulative loss since it turned; (3) new vs repeat vs recovered trio at the head; (4) the loss as a share of group profit — `these four units cost the group 14% of what it earned`. |
| **The comparison** | Against **zero** (the rule: a unit earns its keep) and against **its own record** — a unit losing money in month 1 of trading is on plan; a unit losing money in month 19 is a decision the owner has been avoiding. |
| **Exception lead** | Ranked by **cumulative loss since turning negative**, not by this month's loss. A unit quietly bleeding TZS 8M a month for a year outranks a one-month TZS 30M hit. |
| **Consolidation level** | Mixed by design: rows can be **company, branch, route or product line** — whatever unit of the group is failing. Must state which level each row is. |
| **Cadence** | Weekly glance, monthly work. |
| **The decision it triggers** | **Owner:** close it, sell it, re-price it, or formally accept it as a strategic loss with an end date. The register must allow "accept with reason and review date" — otherwise known losses clog the list and the screen dies. |
| **Tap-through** | The failing unit's **Plan Gap** — why it loses. Refuses to show its transactions and refuses to name its manager on the list. |
| **Alert condition** | Push when a unit enters the register for the first time, and when any unit reaches 3 consecutive losing months. No push for units already accepted. |
| **Data needed** | P&L to the unit level including branch and route (⚠ **most groups cannot produce a branch-level P&L because overheads are never pushed down — the allocation basis is the build item**); ⚠ **an "accept with review date" state on exceptions**; ⚠ **unit opening dates and their agreed ramp-up period**, so a new branch is judged against its plan and not against zero. |
| **Novelty** | CLASSIC as a concept, rarely built to unit level. |

---

## 8. Real Growth

| field | content |
|---|---|
| **Screen name** | Real Growth |
| **Full name** | How Much of the Growth Is Real — price, volume, new sites |
| **Archetype** | 4 · Variance Bridge |
| **The question it answers** | "Sales are up 22% on last year. How much of that is because we sold more, and how much is just inflation and the two branches I opened?" |
| **Key figures** | (1) Headline growth: `+22% (TZS 1.84Bn)`; (2) the decomposition bars: price/inflation effect, volume effect, **new outlets opened in the period**, acquisitions, FX translation, closures; (3) the honest number: `Like-for-like volume growth: +3%`; (4) the same like-for-like number a year ago; (5) inflation reference: `National inflation 3.2% — our price effect was 9%`. |
| **The comparison** | Reported growth vs **like-for-like** growth vs **national inflation**. Three numbers that together answer whether the business is genuinely bigger or merely more expensive and more numerous. |
| **Exception lead** | When like-for-like volume is negative while reported growth is positive, that fact is the headline in words: `Existing branches sold 4% less; growth came entirely from the two new ones.` This is the sentence that changes an owner's plans. |
| **Consolidation level** | **Group**, rolls up; also runs per company. New/closed unit handling must be identical at every level or the numbers will not tie. |
| **Cadence** | Monthly; the deep read is at budget season. |
| **The decision it triggers** | **Owner:** whether to keep buying growth by opening sites, or to fix the sites already open. **GM:** whether the sales team's bonus is being paid for inflation. **CFO:** whether next year's plan can assume the same price effect. |
| **Tap-through** | The **like-for-like league** — which mature branches actually grew in volume. Refuses to show new sites in that list; that is the whole point of the screen. |
| **Alert condition** | Push once per quarter if like-for-like volume growth is negative for two consecutive quarters. Otherwise no push. |
| **Data needed** | Revenue and units by branch by month, multi-year; ⚠ **branch/company opening and closing dates with a defined maturity rule** (e.g. in the comparable base only after 13 months); price per unit history to separate price from volume; ⚠ **a national inflation series (NBS CPI)** — external data the ERP must ingest; acquisition dates and acquired revenue ⚠. |
| **Novelty** | **NOVEL.** Almost no ERP separates real growth from price and expansion. Owners of expanding Tanzanian groups routinely believe they are growing when their existing shops are shrinking. |

---

## 9. Capital League

| field | content |
|---|---|
| **Screen name** | Capital League |
| **Full name** | Where the Next Shilling Earns Most — return on capital used |
| **Archetype** | 5 · League Table |
| **The question it answers** | "I have 500 million to put somewhere. Which of my businesses turns a shilling into the most shillings?" |
| **Key figures** | (1) The spread: `Best returns 34% on capital used · worst 4% · group 17% · money costs us 16%`; (2) per unit: capital employed (TZS M), profit (TZS M), return %; (3) the **cost of money line** printed across the chart — anything below it is destroying value; (4) capital employed change over 12 months per unit — who has been quietly absorbing money; (5) `TZS 1.2Bn of group capital sits in units returning below the cost of money`. |
| **The comparison** | Return against the **cost of capital** (bank rate plus the owner's own required margin — a number the owner must state), and against the same unit a year ago. Not against other groups; that data does not exist honestly. |
| **Exception lead** | Units **below the cost-of-money line** appear first, with the amount of capital stranded in them — because the decision is not "who is best" but "what should I take money out of". |
| **Consolidation level** | **Company** primarily; **branch** where a branch has identifiable capital (stock + debtors + fixed assets − trade creditors). Does not roll up to a single group number — a group ROCE is a vanity figure; the spread is the message. |
| **Cadence** | Monthly; mandatory before any capital decision. |
| **The decision it triggers** | **Owner:** where the next shilling goes, and — harder and more valuable — which unit gets its capital *reduced*. **CFO:** set each unit's stock and debtor ceiling from this. |
| **Tap-through** | One unit's **capital composition** — how much is stock, debtors, fixed assets, less creditors. Refuses to show a valuation, a payback model, or anything resembling an investment appraisal; those live in *Capital Waiting*. |
| **Alert condition** | Push when a unit that was above the cost-of-money line falls below it for two consecutive months. |
| **Data needed** | ⚠ **Balance sheet by company and by branch** — the central build item; most ERPs post the balance sheet only at company level; ⚠ **the owner's stated cost of capital / hurdle rate**, reviewed annually; fixed asset register by location; stock and debtors by unit; ⚠ **treatment rule for shared assets** (a head-office building used by three companies). |
| **Novelty** | **NOVEL.** The owner's actual capital-allocation question, which no standard ERP report answers. This is the single highest-value screen in the domain. |

---

# TIER 2

---

## 10. Group Trajectory

| field | content |
|---|---|
| **Screen name** | Group Trajectory |
| **Full name** | Which Way the Group Is Going — 24 months against the band |
| **Archetype** | 8 · Trend & Trajectory |
| **The question it answers** | "Forget this month. Over two years, is this group getting better or worse?" |
| **Key figures** | (1) Verdict in words: `Margin rate falling — 5th consecutive month below the band`; (2) current gross margin rate and profit rate; (3) the normal band (`12-month mean ± 1 SD`) drawn behind the line; (4) same month last year as a dotted line; (5) count of consecutive periods outside the band. |
| **The comparison** | The **band** is the comparison — it is what turns a wiggle into a signal. Plus the seasonal line, because a Tanzanian trading group has a Ramadan, an Eid, a Christmas and a harvest. |
| **Exception lead** | The verdict sentence, which fires only on a **run** — three or more consecutive points outside the band — never on a single month. |
| **Consolidation level** | **Group**; tap decomposes the same series by company. Must roll up, and the current incomplete month is **excluded and shown greyed** — including it manufactures a collapse every month. |
| **Cadence** | Month-end. |
| **The decision it triggers** | **Owner:** whether to intervene at all. A move inside the band is noise and the correct decision is to do nothing — a decision this screen exists to make defensible. |
| **Tap-through** | The same series **by company**, so a group-level drift can be attributed. Refuses to show anything shorter than 13 points. |
| **Alert condition** | Push only on the third consecutive month outside the band, once. |
| **Data needed** | 24+ months of consolidated monthly P&L on a **consistent basis** (⚠ a restructure, an acquisition or a change of allocation policy breaks the series — the report needs **event markers with dates**: price rises, branch openings, factory shutdowns, policy changes); month-end close dates so partial months are excluded. |
| **Novelty** | CLASSIC |

---

## 11. Bigger or Busier

| field | content |
|---|---|
| **Screen name** | Bigger or Busier |
| **Full name** | Are We Bigger or Just Busier? — profit, capital and people |
| **Archetype** | 8 · Trend & Trajectory |
| **The question it answers** | "We do far more business than three years ago. Am I actually better off, or have I just built a bigger machine that earns the same?" |
| **Key figures** | Four indexed lines over 12 quarters, all rebased to 100: (1) turnover; (2) profit before tax; (3) capital employed; (4) headcount. Plus two derived numbers printed large: (5) **profit per shilling of capital** then vs now; (6) **profit per employee** then vs now. |
| **The comparison** | Each line against its own starting point, and the three against **each other**. The story is in the divergence: turnover +80%, capital +110%, profit +20%, headcount +95% is a group that got busier and poorer. |
| **Exception lead** | The verdict sentence: `Turnover grew 80%, capital grew 110% — each shilling now earns 4 cents less than in 2023.` |
| **Consolidation level** | **Group**, must roll up; a per-company version exists but the group view is the point. |
| **Cadence** | Quarterly. Read in full at year-end and before any expansion decision. |
| **The decision it triggers** | **Owner:** whether the growth strategy is working at all — stop expanding and consolidate, or push on. This is the report that stops a group from opening its eleventh unprofitable branch. |
| **Tap-through** | The **capital line decomposed** — how much of the growth in capital employed went into stock, into debtors, into fixed assets. Usually the answer is stock, and usually it is a surprise. |
| **Alert condition** | No push. This is a thinking report, not an alerting one. |
| **Data needed** | 3+ years of consolidated P&L and **balance sheet** (⚠ historic balance sheets by company at month-end are often not retained); ⚠ **headcount by month** (HR has it; it must be historised, not just current); consistent capital-employed definition across the period; acquisition/disposal dates for rebasing ⚠. |
| **Novelty** | **NOVEL.** The single most important long-horizon question an owner asks, and one that no ERP report format asks on his behalf. |

---

## 12. Season Shape

| field | content |
|---|---|
| **Screen name** | Season Shape |
| **Full name** | Is This Month Normal for the Season? — against three years |
| **Archetype** | 8 · Trend & Trajectory |
| **The question it answers** | "Sales dropped 18% this month. Is that a problem, or is that just what this month always does?" |
| **Key figures** | (1) Verdict: `Normal for August — this month is usually 14% below July`; (2) this month indexed against the group's own 3-year seasonal profile; (3) the deviation from seasonal expectation in TZS M — **the only number that matters**; (4) the seasonal peak and trough months named, with the next peak's date; (5) how much of the annual profit the remaining months usually carry: `61% of the year's profit still to come, in Nov–Dec`. |
| **The comparison** | Against the group's **own seasonal shape** built from 3+ years, not against last month and not against a flat twelfth of the plan. |
| **Exception lead** | Fires only when the deviation from the *seasonal* expectation exceeds the normal band — so a routine seasonal dip is explicitly reported as `no action`, which is what stops owners punishing branch managers for August. |
| **Consolidation level** | **Group** and **company** — seasonality differs sharply between a factory, a retail counter and a van route, so it must be computable at company level and never assumed uniform. |
| **Cadence** | Monthly; essential at budget time for phasing the plan. |
| **The decision it triggers** | **Owner + GM:** do nothing, or act — and separately, **CFO:** phase next year's plan by the real seasonal shape rather than dividing by twelve, which is what makes *Month So Far* and *Plan Gap* honest. |
| **Tap-through** | The **12-month shape** with this year overlaid. Refuses to show a forecast; that is *Year-End Forecast*. |
| **Alert condition** | Push when the month deviates from seasonal expectation by more than 15%, in either direction — a surprisingly good month deserves investigation too. |
| **Data needed** | 3+ years of monthly revenue and margin by company; ⚠ **a movable-feast calendar** — Ramadan and Eid shift ~11 days each year, so a fixed calendar-month seasonal index is wrong in Tanzania; harvest and school-term calendars ⚠; known disruptions flagged so a COVID month or a shutdown does not poison the seasonal index ⚠. |
| **Novelty** | **NOVEL.** The movable-feast handling in particular; every off-the-shelf seasonality report assumes Gregorian regularity. |

---

## 13. Money Tied Up

| field | content |
|---|---|
| **Screen name** | Money Tied Up |
| **Full name** | Where the Money Is Tied Up — by company, and for how long |
| **Archetype** | 3 · Position & Movement |
| **The question it answers** | "I am profitable but I never have cash. Where in this group is my money actually sitting?" |
| **Key figures** | (1) Headline: `TZS 3.4Bn of your money is not cash`; (2) the split — stock, debtors, prepayments, **less** what suppliers are funding for us (creditors); (3) the movement waterfall over 12 months: opening working capital → stock build → debtor growth → supplier funding → closing; (4) the duration: `Money leaves you and comes back in 74 days — 61 days a year ago`; (5) the worst company by absorption: `Tembo Foods absorbed TZS 420M more than last year`. |
| **The comparison** | Against **the same position 12 months ago** and against the amount of profit made in between — the sentence that lands is `you made TZS 900M and TZS 640M of it went into stock and debtors`. |
| **Exception lead** | The company that **absorbed the most money without growing** leads. Growth that consumes capital is investment; absorption without growth is a leak. |
| **Consolidation level** | **Group** headline, **company** rows, must roll up. Intercompany balances eliminated, with the eliminated amount shown so the arithmetic is checkable. |
| **Cadence** | Monthly. |
| **The decision it triggers** | **Owner + CFO:** set a stock ceiling and a debtor-days target per company, funded from this screen rather than from an argument. **GM:** the specific unit told to release capital this quarter. |
| **Tap-through** | The **cash-cycle days by company** — days in stock, days to collect, less days taken to pay. Refuses to show individual customer balances (that is the AR domain's *Debtor Ageing*). |
| **Alert condition** | Push when group working capital grows by more than 10% in a quarter while revenue is flat or falling. |
| **Data needed** | Stock at cost, debtors, creditors, prepayments by company by month-end, historised (⚠ **month-end balance snapshots are usually not retained — they must be, or the 12-month movement cannot be built**); purchases and cost of sales for day calculations; ⚠ **consignment and supplier-owned stock flagged**, or the stock figure overstates what the owner has funded. |
| **Novelty** | **NOVEL** at group level with the movement waterfall. Owners understand "profit" and "cash" but rarely see the bridge between them laid out by company. |

---

## 14. Intercompany

| field | content |
|---|---|
| **Screen name** | Intercompany |
| **Full name** | What the Companies Owe Each Other — balances and their age |
| **Archetype** | 3 · Position & Movement |
| **The question it answers** | "How much of my group's money is one of my companies lending to another, and has anyone been quietly funding a failing business?" |
| **Key figures** | (1) Total intercompany balances outstanding (TZS M) and net position per company (`Tembo Foods is owed 340M net`); (2) the pair matrix as ≤6 rows: `A → B, TZS 210M, 8 months old`; (3) movement over 12 months: how much flowed each way; (4) the oldest balance and its age; (5) the portion that is **trading** (goods actually supplied) vs **funding** (money lent). |
| **The comparison** | Against the same position a year ago (is one company's dependence deepening?) and against any **agreed intercompany limit**. |
| **Exception lead** | Balances **older than 90 days that are funding, not trade** — these are undeclared loans, and they have tax and audit consequences in Tanzania. |
| **Consolidation level** | **Company pairs**. Does not roll up — it nets to zero at group level, which is precisely why owners never see it. |
| **Cadence** | Monthly; mandatory before year-end audit and before any TRA transfer-pricing question. |
| **The decision it triggers** | **CFO:** formalise a long-standing balance as an intercompany loan with terms and interest, or settle it. **Owner:** stop one company funding another's losses invisibly. |
| **Tap-through** | The **ageing of one pair's balance**. Refuses to show the individual documents; and refuses to net trade against funding, because netting is how the problem stayed hidden. |
| **Alert condition** | Push when any single intercompany balance exceeds an agreed ceiling or exceeds 180 days. |
| **Data needed** | Intercompany transactions flagged at source with the counterparty company (⚠ **most groups identify intercompany only at consolidation time by account name — it must be a transaction attribute**); ⚠ **a trade-vs-funding classification on each intercompany transaction**; ⚠ **agreed intercompany limits and terms**; transfer pricing basis ⚠. |
| **Novelty** | CLASSIC in audit; NOVEL as an owner-facing monthly screen. |

---

## 15. Unapproved Moves

| field | content |
|---|---|
| **Screen name** | Unapproved Moves |
| **Full name** | Money Moved Between Companies Without Approval |
| **Archetype** | 6 · Exception Register |
| **The question it answers** | "Did anybody move my money between my companies this month without asking me?" |
| **Key figures** | (1) `6 movements · TZS 210M · 2 above your limit`; (2) per row: from company → to company, amount, date, who authorised it, which rule it broke; (3) new vs repeat vs cleared trio; (4) total moved this month vs the monthly average; (5) the largest single unapproved amount. |
| **The comparison** | Against the **authority rules the owner set** — who may move how much between which companies without him — and against the same count last month. Repeats are the signal: one incident is a mistake, a pattern is a control failure. |
| **Exception lead** | Ranked by amount above the authoriser's limit, not by amount. A TZS 40M transfer by someone with a TZS 5M limit outranks a TZS 90M transfer by the CFO. |
| **Consolidation level** | **Company pairs**, group-wide list. Does not roll up. |
| **Cadence** | On-alert; reviewed weekly. |
| **The decision it triggers** | **Owner:** an immediate conversation, and either an authority change or a disciplinary one. Must support **accept-with-reason**, because some moves are legitimate and undocumented. |
| **Tap-through** | The movement's **full trail** — who, when, from which account, with what narration, and whether it has been reversed. This is the one screen in the suite where transaction identity is the point. |
| **Alert condition** | Push **immediately** on any movement above the owner's stated threshold, and daily digest below it. This is one of only two report in the domain that pushes in real time. |
| **Data needed** | Bank and cash transfers tagged with counterparty company; ⚠ **an authority matrix: who may move how much, between which companies** — pure policy data no ERP ships with; approval records linked to the transaction; ⚠ **detection of same-day paired movements** that net out and would otherwise hide; audit trail with the acting user (the ERP has this). |
| **Novelty** | **NOVEL.** In owner-managed groups this is a live risk that no report has ever addressed, and the owner's reaction to it is usually visceral. |

---

## 16. What Moves Profit

| field | content |
|---|---|
| **Screen name** | What Moves Profit |
| **Full name** | What Moves Profit Most — the six drivers ranked by impact |
| **Archetype** | 10 · Concentration & Exposure |
| **The question it answers** | "Of everything I cannot control — the shilling, fuel, the bank rate, my biggest customer — which one actually hurts me most if it moves?" |
| **Key figures** | (1) The single largest exposure stated in words: `A 10% weaker shilling costs TZS 190M — more than losing your largest customer`; (2) ranked bars: for each of 6 drivers, the profit impact of a defined, realistic adverse move; (3) each driver's impact as a % of annual profit; (4) the tolerance line the owner set (`no single driver may cost more than 15% of profit`); (5) how each impact has changed since last year — is the group getting more or less fragile? |
| **The comparison** | Against the **tolerance line** and against **each other** — the ranking is the whole message. Also against last year's version, because fragility drifts silently. |
| **Exception lead** | Any driver above the tolerance line, first, with the specific hedge or contract that would cap it. |
| **Consolidation level** | **Group**, must roll up; the driver mix differs sharply by company (the factory is fuel-exposed, the importer is FX-exposed), so per-company breakdown must exist. |
| **Cadence** | Quarterly; re-run whenever the group takes on a major new contract, loan or supplier. |
| **The decision it triggers** | **Owner + CFO:** buy forward cover, fix a fuel contract, fix a bank rate, insure a receivable, or deliberately accept the exposure with the number written down. |
| **Tap-through** | One driver opens its own dedicated screen (FX opens *Weaker Shilling*). Refuses to show a probability — this is sensitivity, not prediction, and dressing it as prediction destroys it. |
| **Alert condition** | No push from the report itself; the underlying drivers push through their own screens. |
| **Data needed** | Cost structure by input category; ⚠ **purchase and sale currency exposure per transaction, including USD-priced local purchases** (very common in Tanzania and usually recorded in TZS only); ⚠ **fuel and power consumption volumes**, not just cost; ⚠ **loan balances split fixed vs floating rate**; customer margin concentration; ⚠ **the defined adverse move for each driver, agreed once** (e.g. shilling −10%, fuel +20%, rate +300bp), so the report is comparable across runs. |
| **Novelty** | **NOVEL.** A tornado sensitivity is standard in corporate finance and entirely absent from ERPs. |

---

## 17. Weaker Shilling

| field | content |
|---|---|
| **Screen name** | Weaker Shilling |
| **Full name** | How Much Rides on the Exchange Rate — profit at four rates |
| **Archetype** | 10 · Concentration & Exposure |
| **The question it answers** | "If the shilling goes to 2,900 to the dollar, what happens to my profit — and how long before I feel it?" |
| **Key figures** | (1) Current rate and the profit impact per 100 TZS of movement: `Every 100 TZS costs you TZS 62M a year`; (2) profit at four stated rates (2,500 / 2,650 / 2,800 / 2,950) as four bars; (3) **exposed value**: USD-denominated payables, USD-priced stock in transit, USD-linked selling prices; (4) the **natural hedge**: how much of the exposure is passed to customers automatically; (5) the **lag**: `A rate move reaches your selling prices after 6 weeks — that gap is where the loss happens`. |
| **The comparison** | Against the rate assumed in the **board plan** (this is the number that makes the plan wrong or right), and against the rate 12 months ago. |
| **Exception lead** | The **unhedged, un-passed-through portion** — the part of the exposure that lands on the owner rather than on the customer. |
| **Consolidation level** | **Group**, rolls up; the importing company will carry nearly all of it, which is itself the finding. |
| **Cadence** | On-alert; reviewed monthly, urgently when the rate moves. |
| **The decision it triggers** | **CFO:** buy forward cover, pre-pay a USD supplier, or hold stock. **Owner:** authorise a price rise now rather than after the margin is gone — the 6-week lag is the decision window. |
| **Tap-through** | The **exposed items** — which suppliers, which stock lines, which contracts. Refuses to forecast the rate; the ERP has no business predicting the shilling. |
| **Alert condition** | Push when the rate moves more than 3% from the plan assumption, and when unhedged exposure exceeds a stated ceiling. |
| **Data needed** | ⚠ **Transaction currency and rate recorded on every purchase, not just the TZS amount**; ⚠ **USD-linked pricing flags on customer contracts** (a price agreed "at the day's rate" is exposure the ERP cannot see); ⚠ **forward contracts and hedges held**; ⚠ **the plan's assumed rate**; ⚠ **the historical pass-through lag** — measurable from past rate moves and price changes, but nobody measures it; goods in transit valued in the purchase currency. |
| **Novelty** | **NOVEL**, particularly the pass-through lag. For a Tanzanian importer this is where the money is actually lost, and it is invisible in every existing report. |

---

## 18. Bank Covenants

| field | content |
|---|---|
| **Screen name** | Bank Covenants |
| **Full name** | Where We Stand With the Bank — ratios against the covenants |
| **Archetype** | 2 · Scorecard |
| **The question it answers** | "Am I about to breach something in my loan agreement and find out from the bank instead of from my own phone?" |
| **Key figures** | (1) Verdict: `3 of 4 within covenant · debt service cover breaches on 30 Sep at this trajectory`; (2) per covenant: the ratio's name in the bank's own words, current value, the limit, and headroom; (3) **days to the next test date**; (4) the forecast value at that test date; (5) what would have to happen to breach: `Profit falling below TZS 140M in September breaches it`. |
| **The comparison** | The **covenant limit itself**, printed, in the lender's definition — not the accountant's preferred definition. Plus the value at the last test date. |
| **Exception lead** | The covenant with the **least headroom at the next test date**, not the one closest to its limit today. A covenant tested quarterly is only in breach on the test date. |
| **Consolidation level** | **Whichever entity the lender contracts with** — usually one company, sometimes the group. This is the one report in the suite where the consolidation level is dictated externally and must be stated on the screen. |
| **Cadence** | Monthly; weekly within 30 days of a test date. |
| **The decision it triggers** | **Owner + CFO:** talk to the bank *before* the breach — a pre-emptive waiver costs a conversation, a discovered breach costs a repricing. Or defer a dividend/capex to hold the ratio. |
| **Tap-through** | One covenant's **calculation, in the bank's definition**, term by term. Refuses to show a "management-adjusted" version; there is exactly one definition that matters and it is in the facility letter. |
| **Alert condition** | Push at 60, 30 and 7 days before a test date if forecast headroom is under 10%, and immediately if the current value breaches. |
| **Data needed** | ⚠ **The covenant definitions, limits and test dates transcribed from each facility letter** — pure external contract data, and the reason most groups discover breaches late; EBITDA / debt service / gearing computed to those definitions; loan balances and schedules; the forecast from *Year-End Forecast* to project the test-date value; ⚠ **security and guarantee details** for the tap-through. |
| **Novelty** | CLASSIC in corporate finance; almost never in an ERP, and never on a phone. |

---

## 19. Owner Drawings

| field | content |
|---|---|
| **Screen name** | Owner Drawings |
| **Full name** | What the Owners Took Out — against profit and cash left |
| **Archetype** | 3 · Position & Movement |
| **The question it answers** | "Across all the companies, how much have we actually taken out this year — and could the business afford it?" |
| **Key figures** | (1) Total taken this year, all companies, all forms: `TZS 780M`; (2) the composition — dividends, director's loan movements, personal expenses paid by the business, assets used personally, salaries above market; (3) against **profit after tax**: `taken 87% of what the group earned`; (4) against **free cash**: `the group generated TZS 610M of free cash — you took 780M`; (5) the same ratio last year and the year before. |
| **The comparison** | Against **profit earned** and against **cash generated**, which are different constraints — a group can be profitable and still be drained. Plus the three-year trend of the ratio. |
| **Exception lead** | When drawings exceed free cash, that sentence leads, with the funding source named: `The difference came from supplier credit.` |
| **Consolidation level** | **Group**, must roll up across all companies and all owners — the entire value of this report is that no single company's books show the total. Per-owner breakdown where there is more than one. |
| **Cadence** | Monthly glance, quarterly read, mandatory before declaring a dividend. |
| **The decision it triggers** | **Owner:** set a drawings policy for the year — a stated percentage of profit — rather than taking money ad hoc from whichever company has cash. **CFO:** regularise personal expenses before the auditor or TRA finds them. |
| **Tap-through** | Drawings by **company and by month**, showing which company has been carrying the load. Refuses to itemise personal expenses on screen — it counts and totals them; the detail belongs in a private export. |
| **Alert condition** | Push when year-to-date drawings exceed year-to-date profit after tax. |
| **Data needed** | ⚠ **A consistent tagging of owner-related transactions across all companies** — dividends, director's current accounts, personally-used assets, family salaries. This is the single hardest data item in the domain and the one most likely to be politically resisted; ⚠ **a stated drawings policy** to compare against; free cash flow by company; tax paid and provided. |
| **Novelty** | **NOVEL.** Owner-managed groups almost never see this consolidated, and the number is routinely a shock. |

---

## 20. Capital Waiting

| field | content |
|---|---|
| **Screen name** | Capital Waiting |
| **Full name** | Capital Waiting on You — capex asks, value and cost of delay |
| **Archetype** | 14 · Decision Docket |
| **The question it answers** | "What spending decisions are sitting on me, what are they worth, and what is it costing me to keep thinking about them?" |
| **Key figures** | (1) `7 asks · TZS 1.4Bn · oldest 23 days · 2 have expired quotations`; (2) per item: what, which company, amount, promised return, days waiting, **what breaks if it waits** (`supplier quote expires 24 Aug`, `factory line stops in September`); (3) total asked vs **capital available** from *Group Money Left*: `You are being asked for 1.4Bn; you have 640M of room`; (4) each ask's promised return against the cost of money; (5) your own median decision time: `you take 11 days`. |
| **The comparison** | Each ask against the **hurdle rate**, against the **capital actually available**, and against **each other** — capital decisions are never independent, and a docket that shows them one at a time invites over-commitment. |
| **Exception lead** | Ranked by **cost of delay**, not by arrival or by size: expiring quotations, seasonal windows, and asks blocking other work first. |
| **Consolidation level** | **Group** queue spanning all companies — the owner approves across the group, so the docket must too. |
| **Cadence** | On-alert; opened whenever an ask arrives. |
| **The decision it triggers** | **Owner:** approve, reject, or send back with a reason — posted immediately and audited. Over time it triggers a **delegation** decision: asks the owner always approves unchanged should have their limit raised, and the report should say so. |
| **Tap-through** | The **one fact needed to decide**: what this company earns on capital today (*Capital League*), what the last similar purchase actually returned (*Investment Payback*), and the remaining capex budget. Refuses to show the full business-case document on the phone. |
| **Alert condition** | Push on arrival for any ask above a threshold; push on the day a quotation or a seasonal window expires. |
| **Data needed** | Capex requests with amount, requester, company and quotation validity date (⚠ **quotation expiry is rarely captured and is the most common cost of delay**); ⚠ **a promised return / payback on every ask**, which forces discipline on the asker; approval authority matrix; capex budget by company; the hurdle rate. |
| **Novelty** | CLASSIC as a docket; the *cost of delay* and *asked vs available* framing lift it. |

---

## 21. New Branch Payback

| field | content |
|---|---|
| **Screen name** | New Branch Payback |
| **Full name** | Do New Branches Pay Back? — profit curve by opening year |
| **Archetype** | 11 · Cohort & Retention |
| **The question it answers** | "The branches I opened this year — are they ramping up like the good ones did, or are the new ones worse?" |
| **Key figures** | (1) Verdict: `Branches opened in 2025 reach break-even in month 11 — the 2023 cohort did it in month 7`; (2) 4–6 cohort lines: cumulative profit by months-since-opening, one line per opening year; (3) months to break-even per cohort; (4) months to full capital payback per cohort; (5) the cohort still under water: `Three 2025 branches remain TZS 240M behind`. |
| **The comparison** | **Cohort against cohort.** No external target needed — the group's own history is the standard, and the deterioration or improvement between cohorts is the finding. |
| **Exception lead** | The most recent cohort's position against where the best cohort stood at the same age. Recency is what the owner is deciding about. |
| **Consolidation level** | **Branch**, grouped into cohorts by opening month; also runs for new companies, new routes and new product lines with the same shape. |
| **Cadence** | Quarterly; mandatory before approving the next opening. |
| **The decision it triggers** | **Owner:** whether to keep opening branches at the current rate, and whether the site-selection or launch method has degraded. **GM:** intervene on a specific under-ramping branch while it is still young enough to fix. |
| **Tap-through** | The **branches inside a failing cohort**, with each one's own curve. Refuses to show cohorts younger than 3 months as interpretable — it shows them ghosted with a "too early" label. |
| **Alert condition** | Push when the current cohort falls more than 3 months behind the group's historical break-even at the same age. |
| **Data needed** | ⚠ **Branch opening dates and the capital invested per opening** (fit-out, deposit, opening stock) — rarely held as a single figure; branch-level P&L monthly since opening (see *Losing Money*); ⚠ **a consistent rule for pre-opening costs**; closure dates, so failed branches stay in their cohort rather than vanishing and flattering the average — **survivorship bias is what makes most such analyses lie**. |
| **Novelty** | **NOVEL.** Cohort analysis applied to physical expansion rather than customers. For a group opening branches on instinct, this is the discipline that has been missing. |

---

# TIER 3

---

## 22. Group vs Companies

| field | content |
|---|---|
| **Screen name** | Group vs Companies |
| **Full name** | Does the Group Add Up? — standalone sum vs consolidated |
| **Archetype** | 13 · Reconciliation & Assurance |
| **The question it answers** | "When my accountant shows me a group number, can I trust that it is my four companies added up properly?" |
| **Key figures** | (1) Verdict: `Reconciles — TZS 0 unexplained` or `Out by TZS 2.4M`; (2) sum of standalone profits; (3) eliminations, by reason (intercompany sales, unrealised profit in stock, intercompany interest, dividends); (4) consolidated profit; (5) the **unexplained residual and how long it has persisted**. |
| **The comparison** | The two sides, printed side by side, plus the **age of any difference** — a chronic TZS 2M gap is a control failure; today's TZS 2M gap is timing. |
| **Exception lead** | The unexplained portion, always, separated from legitimate eliminations. "Unexplained" and "elimination" must never share a bar. |
| **Consolidation level** | Explicitly **both** — that is the report. |
| **Cadence** | Month-end, after close. |
| **The decision it triggers** | **CFO:** clear the difference before the numbers go to the board or the bank. **Owner:** stop trusting every other group-level screen until this one is green — this report is the licence for the rest of the suite. |
| **Tap-through** | The **unmatched items** and who owns clearing each. Refuses to show the elimination journals. |
| **Alert condition** | Push when the unexplained residual exceeds a stated tolerance or persists past two closes. |
| **Data needed** | Standalone P&L per company; intercompany transactions flagged at source; ⚠ **unrealised profit on intercompany stock** — requires knowing which stock on hand came from a sister company and at what margin, which almost no ERP tracks; ⚠ **the elimination rule set, versioned**; close dates per company so timing differences are legitimate and labelled. |
| **Novelty** | CLASSIC in audit; NOVEL as a self-service owner screen. |

---

## 23. Plan Credibility

| field | content |
|---|---|
| **Screen name** | Plan Credibility |
| **Full name** | Does the Plan Match What Happened? — bias by company |
| **Archetype** | 13 · Reconciliation & Assurance |
| **The question it answers** | "Before I make decisions off next year's budget — how wrong have these people's plans been before?" |
| **Key figures** | (1) Verdict: `The group plan has been 12% optimistic for three years running`; (2) per company: average miss %, direction (consistently over or under), hit rate (`met plan in 4 of 24 months`); (3) the most and least reliable planner, named by company not by person; (4) **plan revision count**: `The factory plan was revised 4 times this year — always downward`; (5) the implied honest version of the current plan: `On past bias, this year's 2.6Bn plan is really 2.3Bn`. |
| **The comparison** | Plan against **outturn**, over 24+ months, per company. Bias (systematic direction) is reported separately from error (size) — an unbiased but noisy planner and a consistently optimistic one require different responses. |
| **Exception lead** | Systematic bias in **one direction**, first. Random error is a hard business; consistent optimism is a behaviour. |
| **Consolidation level** | **Company**, plus a group total. Does not roll up numerically — bias does not sum. |
| **Cadence** | Quarterly; mandatory at the start of budget season. |
| **The decision it triggers** | **Owner + CFO:** apply a stated haircut to a chronically optimistic company's plan, or change who prepares it. And, structurally: stop treating every *Plan Gap* red bar as a performance failure when it is a planning failure. |
| **Tap-through** | One company's **plan-vs-actual over 24 months** as paired bars. Refuses to name the individual planner (R7). |
| **Alert condition** | No push. Read on demand and at budget time. |
| **Data needed** | ⚠ **Every plan version retained with its date and the reason for revision** — the critical build item; nearly all ERPs overwrite the budget; actuals by company by month on the same basis as the plan; a rule for handling mid-year re-forecasts so a company cannot "hit plan" by lowering it in November. |
| **Novelty** | **NOVEL.** Measuring the plan rather than the performance. It makes every other plan-based screen in this suite trustworthy, and it is the report a planning department least wants to exist. |

---

## 24. Investment Payback

| field | content |
|---|---|
| **Screen name** | Investment Payback |
| **Full name** | Investments Against Their Promise — by project and year |
| **Archetype** | 2 · Scorecard |
| **The question it answers** | "The machine we bought last year was going to pay for itself in 18 months. Did it?" |
| **Key figures** | (1) Verdict: `3 of 8 investments delivered what they promised`; (2) per project: amount spent vs approved, promised return vs actual return, promised payback months vs actual; (3) total capital committed in the period and the **weighted actual return**: `TZS 1.9Bn invested, returning 9% against 22% promised`; (4) the gap in TZS: `TZS 240M a year of promised profit never appeared`; (5) overspend against approval. |
| **The comparison** | Against **the business case that was approved** — the standard is the promise the asker made, which is why this is a Scorecard and not a Trend. |
| **Exception lead** | The largest **unmet promise in shillings**, and whether that same requester or company has unmet promises outstanding elsewhere. |
| **Consolidation level** | **Project**, rolled up by company and by year. |
| **Cadence** | Quarterly; every project reviewed at 12 and 24 months after commissioning. |
| **The decision it triggers** | **Owner:** whether to believe the next business case from the same source, and whether to raise the evidence bar on capex approvals. **GM:** fix or dispose of a specific under-performing asset. |
| **Tap-through** | One project's **promise vs outturn side by side**, with the assumptions that failed. Refuses to re-litigate the approval; it reports, it does not argue. |
| **Alert condition** | Push at each project's 12-month review date. |
| **Data needed** | ⚠ **The approved business case stored as structured data — promised return, promised payback, key assumptions — linked to the capex it authorised.** Without this the report cannot exist, and this is the reason no ERP has it; actual spend per project; ⚠ **a defined measurement rule for the benefit** (extra output, cost saved, sales enabled) agreed *at approval time*, not argued afterwards; commissioning dates. |
| **Novelty** | **NOVEL.** Post-audit of capital spending is standard doctrine and almost universally skipped. It is the discipline that makes *Capital Waiting* and *Capital League* honest. |

---

## 25. Head Office Cost

| field | content |
|---|---|
| **Screen name** | Head Office Cost |
| **Full name** | What the Centre Costs Each Company — share and trend |
| **Archetype** | 3 · Position & Movement |
| **The question it answers** | "What am I paying for the privilege of being a group rather than four separate businesses — and is it worth it?" |
| **Key figures** | (1) Total central cost this year (TZS M) and as a share of group gross margin: `TZS 420M — 14% of everything the group earns`; (2) the movement over 3 years vs group margin growth: `Centre grew 31%, margin grew 9%`; (3) composition — the 4–6 largest central functions; (4) the burden per company after allocation, in TZS and as % of that company's margin; (5) the company carrying the heaviest burden relative to what it uses. |
| **The comparison** | Against **group gross margin** (the only meaningful denominator) and against **its own three-year growth**. Central cost that grows faster than the margin it serves is the quiet killer of profitable groups. |
| **Exception lead** | The central function whose cost grew fastest against no growth in what it supports. |
| **Consolidation level** | **Group** total; allocated to **company**. The allocation basis is printed on the screen — half the arguments about this number are actually arguments about the basis. |
| **Cadence** | Quarterly; deep read at budget time. |
| **The decision it triggers** | **Owner:** cut, restructure or justify the centre — and decide whether shared services are cheaper than each company doing it itself. **CFO:** change the allocation basis (which changes *Company League*, so it must be a deliberate, dated decision). |
| **Tap-through** | The **cost by central function over 12 quarters**. Refuses to show individual salaries. |
| **Alert condition** | Push once a year if central cost as a share of group margin rises by more than 2 percentage points. |
| **Data needed** | Central/shared costs identified as such (⚠ **often buried in the largest company's overheads, which silently makes that company look worst in the league table**); ⚠ **an agreed and stable allocation basis with an effective date**; ⚠ **a usage measure per central function** (transactions processed, headcount supported) to judge whether the cost is proportionate; three years of history on a consistent basis. |
| **Novelty** | **NOVEL.** Groups accumulate a centre and never measure it. This report also protects *Company League* from being quietly rigged by an allocation change. |

---

# Build items this domain creates

Aggregated from the ⚠ flags, ranked by how many reports they unblock.

| # | Data the group must start capturing | Unblocks |
|---|---|---|
| 1 | **Versioned, frozen board plan with approval date, phased by working day, decomposed to price × volume × mix** | 1, 2, 4, 5, 18, 23 |
| 2 | **Balance sheet by company and by branch** (capital employed, month-end snapshots retained) | 9, 11, 13 |
| 3 | **Branch/company/route-level P&L with an agreed overhead allocation basis** | 3, 7, 21, 25 |
| 4 | **Unit lifecycle data**: opening/closing dates, capital invested per opening, agreed ramp-up period | 7, 8, 10, 21 |
| 5 | **Stored history of every forecast and every plan revision** (so the app can grade itself) | 5, 23 |
| 6 | **Intercompany flagged at transaction level**, split trade vs funding, with limits and terms | 14, 15, 22 |
| 7 | **Transaction currency + rate on every purchase; USD-linked contract flags; hedges held** | 16, 17 |
| 8 | **Structured business cases**: promised return, payback, assumptions, quotation expiry, linked to the capex | 20, 24 |
| 9 | **Owner-transaction tagging across all companies** (dividends, current accounts, personal expenses) | 19 |
| 10 | **Covenant definitions, limits and test dates transcribed from facility letters; facility limits and undrawn amounts** | 6, 18 |
| 11 | **Authority matrix** (who may move/approve how much, between which companies) | 15, 20 |
| 12 | **External series**: NBS inflation index, movable-feast calendar (Ramadan/Eid), public holidays | 2, 8, 12 |
| 13 | **Owner's stated cost of capital / hurdle rate, reviewed annually** | 9, 16, 20, 24 |
| 14 | **"Accept with reason and review date" state on every exception register** | 7, 15 |