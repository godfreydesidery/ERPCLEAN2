## OrbixERP Executive Mobile — The Report Catalogue

**The names and the types · v1 · 2026-08-18**
Consolidated from nine domain packs and the gap audit. 252 proposals in; **151 reports out** after ruthless de-duplication (the merges and retirements are listed at the end of §3). Every survivor answers one question, carries one comparison, is one archetype, and is named by that archetype's template.

---

## 1. How the suite is organised

Seven shelves, named the way the owner talks about his business: **The Trade** (what we sell and to whom), **The Margin** (what we keep of it), **The Cash** (money in the bank, and who owes whom), **The Goods** (stock, buying and making), **The People and the Property** (staff, machines, vehicles, space and overhead), **The Rules** (control, tax and risk), and **The Group** (the whole business and what happens next).

The logic is the owner's own sequence of worry: *sell it → keep some of it → get paid for it → have the goods to sell → pay for the people and the plant that do it → make sure nobody is stealing or filing late → and stand back once a month and ask whether the whole thing is going anywhere.* Every shelf holds the same fourteen shapes of report, so once an owner learns what a League Table or a Variance Bridge does, he can read a new one on any shelf without being taught. Nothing sits on two shelves: where two domains produced the same report, one framing survived and the other became a tap-through.

---

## 2. The report TYPES — the fourteen archetypes

| # | Archetype | Question shape | Screen form | Example reports |
|---|---|---|---|---|
| 1 | **Flash** | Is today normal? | One big number + delta chips, 3–5 pulse lines | Today's Trade · Today's Cash · Today on the Floor · Today's Attendance · Month So Far |
| 2 | **Scorecard** | Are we meeting the standard we set? | Verdict ("4 of 7 on plan") + fixed rows, actual · target · gap, misses first | The Seven Numbers · Sales Against Plan · Factory Against Plan · Bank Covenants · Before the Visit |
| 3 | **Position & Movement** | Where do we stand, and how did we get here? | Closing balance + waterfall of named movement classes | Cash Position · Money in Stock · Money Committed · Owner Drawings · Since You Looked |
| 4 | **Variance Bridge** | Why did we miss? Decompose the gap. | Headline is the gap, then ≤6 cause bars + a policed residual | Margin Gap · Cash Gap · Wage Bill Gap · Unit Cost Gap · Real Growth |
| 5 | **League Table** | Who is best and worst, on the same rule? | Spread headline, ranked bars, group line, rank-change arrows | Branch League · Product League · Supplier League · Capital League · Space League |
| 6 | **Exception Register** | What is wrong right now, by our own rules? | Count + money, new/repeat/cleared, rows ranked by value at risk, accept/assign | Sold Below Cost · Dead Stock · Unbanked Cash · Broken Rules · Losing Money |
| 7 | **Ageing Pyramid** | How old is this pile, how much has gone rotten? | Buckets with value and count, ghost outline of last month, worst names | Debtor Ageing · Supplier Bills by Age · Orders by Age · Unfixed Breaches · Open Claims |
| 8 | **Trend & Trajectory** | Which way, and is this real or noise? | 13–24 points, shaded normal band, verdict in words above the chart | Margin Direction · Collection Days · Group Trajectory · Season Shape |
| 9 | **Forecast & Runway** | What next, and on what date do we hit the wall? | A **date** as the headline, forward band, assumptions, own past accuracy | Cash Runs Out · Salary Cover · Runs Out First · Line Stoppers · Year-End Forecast |
| 10 | **Concentration & Exposure** | How much depends on one thing that could stop? | Pareto with cumulative line, tolerance threshold, year-ago share | Biggest Customers · Single-Source Risk · Key Person Risk · What We Guaranteed |
| 11 | **Cohort & Retention** | Do the ones we won stay, and are recent ones worse? | 5–6 cohort lines, recent highlighted, plain-language verdict on top | New Customers · New Branch Payback · Do New Hires Last |
| 12 | **Cycle-Time & Flow** | How long end to end, and where does it stall? | One segmented bar, standard ticks, median **and** 90th percentile, items stuck now | Money Tied Up · Request to Goods · Approval Delay |
| 13 | **Reconciliation & Assurance** | Do two things that must agree, agree? | Two sides, difference by reason, **unexplained isolated and aged** | What Is Final · Cash Match · Count vs Books · VAT Match · Did It Work |
| 14 | **Decision Docket** | What is waiting for me, and what does waiting cost? | Queue size + value + cost of delay, ranked by consequence, write-back | Waiting on You · Payments Waiting · Credit to Approve · What Waiting Cost Us |

A chart type is not an archetype. A filter is not an archetype. A dashboard is not an archetype — it is a tray, and the trays are in §4.

---

## 3. THE MASTER CATALOGUE

Sorted by section, then tier. **Aud:** O=Owner, C=CFO, G=GM, B=Branch Manager, F=Factory Manager. **Cons:** consolidation level. **N?** = novel (nothing in a normal ERP answers this).

| # | Screen name | Full name | Section | Archetype | The question it answers | Tier | Aud | Cadence | Cons | N? | ⚠ Data gap |
|---|---|---|---|---|---|---|---|---|---|---|---|
| 1 | Today's Trade | Today's Trade — sales, tickets, cash and margin against the same weekday | Trade | Flash | Is today trading normally, and which branch is dragging? | 1 | O G | Daily | Group→Branch | – | Weekday baselines; POS heartbeat |
| 2 | Sales Against Plan | Sales Against Plan — month to date by branch and channel | Trade | Scorecard | Will we make the month, and which part is missing? | 1 | O G | Weekly | Group→Branch | – | Targets by branch × channel |
| 3 | Branch League | Branch League — margin rate, plan achievement and profit after direct cost | Trade | League | Which branch is worth the visit this week? | 1 | O G | Weekly | Branch | – | Branch comparability class; branch P&L |
| 4 | Sales Gap | Why Sales Are Short — plan to actual, by named cause | Trade | Variance | What exactly caused the shortfall? | 1 | O C | Weekly | Group→Branch | – | Plan split price × volume × mix |
| 5 | Orders Waiting | Orders Waiting on You — value held, discount at stake, what stops despatch | Trade | Docket | What selling decisions are sitting on me? | 1 | O G | Daily | Personal | – | Blocking-despatch flag; floor price |
| 6 | Customers Slipping Away | Customers Buying Less, Paying Slower or Gone Quiet — by margin at risk | Trade | Exception | Which customers are leaving before I notice? | 1 | O G | Weekly | Customer | Y | Per-customer buying rhythm; related-party grouping |
| 7 | Open Complaints | What Customers Complain About — value at risk, reason, days open | Trade | Exception | What are customers angry about right now? | 1 | O G | Weekly | Group→Branch | Y | A complaint register at all |
| 8 | Seller League | Seller League — margin per selling day against the team rate | Trade | League | Who on the floor actually earns? | 2 | G B | Weekly | Branch | – | Seller attribution on counter sales |
| 9 | Route League | Route League — net per day on the road and stock returned | Trade | League | Does each van pay for itself? | 2 | G B | Weekly | Route | Y | Route dimension; load-out/in valued |
| 10 | Channel League | Channel League — margin after channel cost: counter, route, credit trade | Trade | League | Which way of selling makes money? | 2 | O C | Monthly | Channel | – | Channel tag; channel cost pools |
| 11 | Customer League | Customer League — margin after cost to serve and after credit | Trade | League | Which customers are worth having? | 2 | O C | Monthly | Customer | Y | Delivery cost per drop; cost-of-money rate |
| 12 | Product League | Product League — margin per shilling of stock, by family | Trade | League | Which of the things I sell make money? | 2 | O G | Monthly | Item | Y | Stable product family hierarchy |
| 13 | Lines Not Paying | Products That Do Not Pay Their Way — margin against the cost of carrying them | Trade | Exception | What should I simply stop selling? | 2 | O G | Quarterly | Item | Y | Carrying cost per shilling of stock |
| 14 | Why We Lose | Where the Quoted Value Went — won, lost, and by reason | Trade | Variance | Why do we lose the orders we lose? | 2 | O G | Monthly | Group | Y | Structured loss reasons at quote closure |
| 15 | Goods Coming Back | Sales That Came Back — margin reversed, by reason, branch and issuer | Trade | Exception | What is coming back, why, and who keeps causing it? | 2 | G C | Monthly | Branch | Y | Credit-note reason codes linked to the original line |
| 16 | Orders by Age | Customer Orders by Age — value waiting and what is blocking it | Trade | Ageing | Who is still waiting for us, and for how long? | 2 | G | Weekly | Group→Branch | Y | Block reason per order; cancellation hygiene |
| 17 | Above Market | Lines Priced Above the Market — sales exposed and where we are undercut | Trade | Exception | Where am I being undercut? | 2 | O G | Monthly | Branch | Y | Competitor price capture |
| 18 | Biggest Customers | If Our Biggest Customers Left — share of margin and the cover behind them | Trade | Concentration | How much rides on a few names? | 3 | O | Quarterly | Group | – | Related-party grouping; contract notice periods |
| 19 | Before the Meeting | How This Customer Stands Before You Meet Them | Trade | Scorecard | What is the true state of this account? | 3 | O G | On demand | Customer | Y | Agreed terms held as data with their date |
| 20 | Margin Gap | Where the Margin Went — this month against plan, by cause | Margin | Variance | Where did the missing margin go? | 1 | O C | Weekly | Group→Branch | – | Plan at price × volume; frozen plan price list |
| 21 | Profit Against Plan | Profit Against Plan — the money measures and their gaps | Margin | Scorecard | Are we hitting the money standard, and which miss is worst? | 1 | O C | Weekly | Group→Branch | – | Branch-level plan; fixed/variable classification |
| 22 | Where Month Lands | What This Month Will Land At — projection, range, and last month's error | Margin | Forecast | With days left, where does the month finish? | 1 | O G | Weekly | Group→Company | – | Trading calendar; stored forecast history |
| 23 | Sold Below Cost | Lines Sold Under Landed Cost — value lost, by branch and seller | Margin | Exception | Are we selling anything at a loss? | 1 | G B | Weekly | Branch/Item | – | Landed cost at moment of sale; clearance flag |
| 24 | Over-Limit Discounts | Discounts Given Beyond the Agreed Limit — who gave, who received | Margin | Exception | Who gives away margin beyond their authority? | 1 | G C | Weekly | Branch/Seller | – | Discount authority limits as data |
| 25 | Price Given Away | Where the List Price Went — discount, override, free goods and credits | Margin | Variance | If everything sold at list, where did the difference go? | 1 | O G | Weekly | Branch/Seller | Y | Effective-dated list price; bonus goods valued |
| 26 | The Profit Ladder | How the Profit Was Made — sales down to net, step by step | Margin | Position | Of every 100 shillings in, how many were still mine? | 2 | O C | Monthly | Group→Company | – | Account→ladder mapping in business words |
| 27 | Margin Direction | Which Way the Margin Is Going — 24 months with the normal range | Margin | Trend | Is margin genuinely slipping or is this a bad month? | 2 | O C | Monthly | Group | – | Costing-method change markers; event log |
| 28 | Margin After Costing | Does the Margin Survive Costing? — booked at sale against landed | Margin | Recon | Did the margin still exist once freight and duty landed? | 2 | C | Monthly | Company | Y | **Margin snapshot at moment of sale** |
| 29 | Profit Quality | How Much of This Profit Is Real — cash-backed, one-off, estimated | Margin | Scorecard | How much of this profit survives a hard look? | 2 | O C | Monthly | Company | Y | Non-recurring / non-cash flags on P&L |
| 30 | The Month-End Swing | Why the Month Changed After Close — flash to final, by adjustment | Margin | Variance | Why is the final number never the one you told me? | 2 | C | Monthly | Company | Y | Frozen period-end flash snapshot |
| 31 | Did Prices Hold? | Did the Price Increase Reach the Invoice? — list rise against realised | Margin | Variance | How much of the price rise actually landed? | 2 | O G | Alert | Branch/Channel | Y | Price-change as a first-class event |
| 32 | What Credit Costs | What Giving Credit Costs Us — margin lost to the days customers take | Margin | Variance | What margin survives funding the customer? | 2 | O C | Monthly | Customer | Y | Maintained cost-of-money rate |
| 33 | Break-even Day | The Day the Month Turns Profitable — fixed costs covered | Margin | Forecast | What day do I stop working for the landlord? | 2 | O C | Monthly | Company/Branch | Y | Fixed / variable / step-fixed classification |
| 34 | Today's Cash | Today's Cash — money in, money out, against the same weekday | Cash | Flash | Is today's money coming in the way it should? | 1 | O G | Daily | Group→Branch | – | Intraday bank balance; per-till holding limits |
| 35 | Cash Position | Where the Cash Is — bank, till, wallet, in transit, and how it moved | Cash | Position | How much do we have and where is it sitting? | 1 | O C | Daily | Group→Company | – | Cleared vs book balance; movement-class mapping |
| 36 | Usable Cash | Cash We Can Actually Use — free, committed and restricted | Cash | Position | How much can I actually spend today? | 1 | O C | Weekly | Group→Company | Y | Restricted-cash register; transferability rules |
| 37 | Cash Runs Out | How Long the Cash Lasts — the day it crosses the floor | Cash | Forecast | On what date do we run out of money? | 1 | O C | Weekly | Company | Y | Promise-to-pay dates; retained forecast runs |
| 38 | Salary Cover | Can We Pay the Salaries? — cover at payroll date and the gap | Cash | Forecast | On the 28th, will the money be there? | 1 | O C | Weekly | Company | Y | Forward payroll + statutory calendar |
| 39 | Profit vs Cash | Why the Profit Is Not in the Bank — profit to cash, by cause | Cash | Variance | The books say we made it — where is it? | 1 | O C | Monthly | Group→Company | Y | Non-cash flags; month-close status |
| 40 | Payments Waiting | Payments Waiting on You — value, due date and cost of delay | Cash | Docket | What payment decisions are on me and what do they cost? | 1 | O C | Daily | Personal | – | Cost of delay; supplier bank-change log |
| 41 | Unbanked Cash | Cash That Never Reached the Bank — by branch, age and value | Cash | Exception | Whose takings have not reached the bank? | 1 | O G | Daily | Branch | Y | Deposit slips matched to bank credits |
| 42 | Cash Match | Does the Cash Match? — bank, wallet and till against the ledger | Cash | Recon | Does the bank agree with our books? | 1 | C | Weekly | Account | – | Bank feed; reconciling reason codes |
| 43 | Debtor Ageing | Who Owes Us, and How Long — by age, largest and oldest first | Cash | Ageing | How much is out there and how much has gone rotten? | 1 | O C | Weekly | Customer | – | Age by due date; dispute flag; related parties |
| 44 | Over Their Limit | Customers Trading Past Their Credit Limit — by how much, how long | Cash | Exception | Who is buying on credit never granted? | 1 | O G | Daily | Customer | – | Exposure incl. commitments; override reasons |
| 45 | Cash Due In | How Much Will Actually Reach the Bank — next 30 days, and how sure | Cash | Forecast | What will really land, and by when? | 1 | C | 2×weekly | Group→Company | Y | Promise-to-pay log; collection plan |
| 46 | Credit to Approve | Credit Waiting on You — held orders, limit requests, write-offs | Cash | Docket | What credit decision is on me right now? | 1 | O C | Daily | Personal | – | Blocking-despatch flag; decision-time history |
| 47 | Supplier Bills by Age | What We Owe and How Long We Have Owed It — by age and supplier | Cash | Ageing | Who am I late with, and can I replace them? | 1 | C | Weekly | Company | – | Supplier criticality / single-source flag |
| 48 | Bills Falling Due | What We Must Pay Next — due bills against the cash we will have | Cash | Forecast | What has to go out, and do I have it? | 1 | C | 2×weekly | Company | – | Must-pay / can-slip classification |
| 49 | Money Committed | What We Have Already Promised to Pay — ordered, received, billed, by due week | Cash | Position | How much is already spoken for? | 1 | O C | Weekly | Company | Y | Commitment accounting; statutory calendar |
| 50 | Discounts We Lost | Early-Payment Discounts We Failed to Take — and what is still catchable | Cash | Exception | How much free money am I throwing away? | 1 | C | Weekly | Company | Y | Discount terms per supplier (2/10 net 30) |
| 51 | Cash Gap | Why Cash Is Short — planned closing against actual, by cause | Cash | Variance | We planned 400 and have 260 — where did it go? | 2 | O C | Monthly | Group | Y | An approved, versioned cash plan |
| 52 | Cash Against Plan | Cash Against Plan — the treasury and credit measures against target | Cash | Scorecard | Are we meeting the money standards we set? | 2 | C G | Weekly | Group→Company | Y | Board-approved treasury targets; bank charges |
| 53 | Money Tied Up | How Long Our Money Is Tied Up — stock, debtors and suppliers, stage by stage | Cash | Cycle | How many days is my money out of my hands? | 2 | O C | Monthly | Group→Company | Y | Stage standards; cost of working capital |
| 54 | Promises Broken | Payment Promises That Were Not Kept — who, how much, how often | Cash | Exception | Who tells us they will pay and does not? | 2 | C | Weekly | Customer | Y | **Promise-to-pay log** |
| 55 | Disputed Invoices | Invoices the Customer Refuses to Pay — value, reason, days open | Cash | Exception | How much of what I am owed is being argued about? | 2 | C | Weekly | Customer | Y | Dispute record with reason and owner |
| 56 | Debt at Risk | How Much of This Debt We Will Never Collect — expected loss and when | Cash | Forecast | How much is honestly gone, and are we provided? | 2 | O C | Monthly | Company | Y | Loss-rate history; last-contact date |
| 57 | Odd Payments | Payments That Broke the Rule — off-policy and duplicate | Cash | Exception | Which payments should have raised an eyebrow? | 2 | O C | Weekly | Company | Y | Bank-change log; duplicate-detection rule |
| 58 | Borrowing Room | How Much Borrowing Room Is Left — facility, drawn, covenant | Cash | Position | How much can we still borrow before we break a condition? | 2 | O C | Weekly | Company | – | Facility, covenant and security register |
| 59 | Till Match | Does Every Till Match? — over, short and repeat offenders | Cash | Recon | Does the cash counted agree with what was sold? | 2 | B G | Daily | Till | Y | Blind-count flag; cashier per session |
| 60 | Cash League | Branch Cash League — cash generated per branch against plan | Cash | League | Which branches produce cash, not just sales? | 2 | O G | Weekly | Branch | Y | Branch running costs; comparability class |
| 61 | Petty Cash | Floats and Advances Not Retired — by holder, age and value | Cash | Exception | Who is holding our money outside the tills? | 3 | C G | Monthly | Holder | – | Float register; leaver feed |
| 62 | Money in Stock | Where the Stock Money Is — value by branch and category, and how it moved | Goods | Position | How much of my money is sitting in goods? | 1 | O C | Weekly | Group→Branch | – | Stock budget; planned-build flag |
| 63 | Dead Stock | Stock That Isn't Moving — value, age and where it sits | Goods | Exception | How much money is in goods nobody buys? | 1 | O G | Weekly | Branch/Item | – | Per-category dead thresholds; accept-with-expiry |
| 64 | Stock Cover | Stock Against Cover Policy — days of cover, worst misses first | Goods | Scorecard | Are we holding what we agreed to hold? | 1 | G C | Weekly | Category | Y | **Cover policy: min/max days per category per branch** |
| 65 | Empty Shelves | What the Empty Shelf Cost — lines out of stock and the sales lost | Goods | Exception | What did I fail to sell because it was not there? | 1 | O G | Daily | Branch/Item | Y | Availability history; unserved-demand capture |
| 66 | Runs Out First | What Runs Out First — lines empty before the next delivery lands | Goods | Forecast | What goes empty before the replacement arrives? | 1 | G B | Daily | Item | Y | Measured supplier lead times; promo calendar |
| 67 | Wrong Branch Stock | Stock in the Wrong Branch — idle here while another branch runs out | Goods | Exception | Where am I about to buy what I already own? | 1 | G | Weekly | Group | Y | Transfer cost and feasibility between branches |
| 68 | Count vs Books | Does the Stock Match the Count? — unexplained isolated from timing | Goods | Recon | Is the stock the system claims actually there? | 1 | O C | Monthly | Location | – | Adjustment reason codes; cycle-count plan |
| 69 | Stock Decisions | Stock Decisions Waiting on You — write-offs, markdowns, transfers | Goods | Docket | What stock decision is stuck on my desk? | 1 | O G | Daily | Personal | – | Cost-of-delay metadata per approval type |
| 70 | Buy Price Gap | Where the Purchase Price Went — against agreed price, by cause | Goods | Variance | Are we paying more than we negotiated, and why? | 1 | O C | Weekly | Supplier | – | **Agreed/contract price list per supplier-item** |
| 71 | Late Orders | Orders Past Their Promise — value, days late, and what they block | Goods | Exception | What did we order that has not arrived? | 1 | G | Weekly | Supplier | Y | Supplier promise date; link to what it blocks |
| 72 | Supplier League | Supplier League — on time, in full, at price, and what failure cost us | Goods | League | Which suppliers actually deliver? | 1 | O G | Monthly | Supplier | – | Inspection outcomes; downtime attribution |
| 73 | Off-Contract Buys | Bought Outside the Rules — unapproved supplier, retro-PO, split orders | Goods | Exception | How much was spent skipping the process? | 1 | O C | Weekly | Branch/Buyer | Y | Approved-supplier list; waiver record |
| 74 | Buys Waiting | Buying Decisions Waiting on You — value and what each delay costs | Goods | Docket | What buying decisions are on me? | 1 | O C | Daily | Personal | – | Quote expiry; vessel and shipment cut-offs |
| 75 | Today on the Floor | Today on the Floor — output, stoppages and scrap against a normal shift | Goods | Flash | Is the factory running normally today? | 1 | O F | Daily | Plant/Line | – | Stoppage events with reasons; run-rate standard |
| 76 | Factory Against Plan | Factory Against Plan — output, yield, downtime and unit cost against standard | Goods | Scorecard | Is the factory meeting the standard we set? | 1 | G F | Weekly | Plant | Y | Standard yield and cost cards, version-dated |
| 77 | Line Stoppers | What Stops the Line First — the material that runs out before it can be replaced | Goods | Forecast | On what date does the factory stop? | 1 | G F | Daily | Plant | Y | Forward production plan; customs as its own leg |
| 78 | Unit Cost Gap | Why a Unit Costs More — standard to actual, by material, labour and overhead | Goods | Variance | Why does every case cost more than planned? | 1 | O C | Monthly | Product | Y | Standard cost cards; actual labour hours |
| 79 | Stock Gap | Why the Stock Is Heavier Than Planned — the gap against plan, by cause | Goods | Variance | Where did the extra half a billion of stock come from? | 2 | C | Monthly | Category | Y | Stock plan by category; purchase price history |
| 80 | Expiring Stock | Stock That Expires Before It Sells — and what can still be saved | Goods | Ageing | How much will be worthless soon? | 2 | B C | Weekly | Batch | Y | Expiry enforced at GRN; supplier return terms |
| 81 | Cost Drift | Does the Cost We Carry Still Hold? — book cost against what goods are worth | Goods | Recon | Is the value on my stock sheet honest? | 2 | C | Monthly | Company | Y | Net realisable value inputs; cost staleness |
| 82 | Buying Dead Lines | Buying What Isn't Selling — orders for lines already slow | Goods | Exception | Am I still buying what has proved it does not sell? | 2 | G O | Weekly | Buyer | Y | Cover snapshot at the moment of ordering |
| 83 | What Excess Costs | What the Extra Stock Costs — the surplus and what it burns each month | Goods | Variance | What does the extra stock cost me every month? | 2 | O C | Monthly | Category | Y | Storage cost allocation; cost of capital |
| 84 | Landed Cost Gap | What Landing the Goods Really Costs — invoice price to shelf cost | Goods | Variance | Why does a well-bought item cost so much on the shelf? | 2 | O C | Monthly | Consignment | Y | Landed-cost components captured by type |
| 85 | Material Loss | Where the Materials Went — yield, scrap, rework and unexplained loss | Goods | Variance | For every hundred kilos in, what came out sellable? | 2 | G F | Monthly | Product | Y | Standard yield; scrap and rework reason codes |
| 86 | Factory Hours | Where the Factory Hours Went — paid hours to producing hours, priced | Goods | Variance | How many of the hours I pay for made anything? | 2 | O G | Monthly | Line | Y | Shift calendar; stoppages covering the whole shift |
| 87 | Bill vs Goods | Does the Bill Match the Goods? — ordered, received, invoiced, aged | Goods | Recon | Am I billed for exactly what I received? | 2 | C | Monthly | Company | – | Difference reason codes; supplier statements |
| 88 | Costly Stoppages | Stoppages That Cost the Most — downtime priced in lost margin | Goods | Exception | Which stoppage is worth spending money to prevent? | 2 | O G | Monthly | Line | Y | Contribution margin per production hour; cost to prevent |
| 89 | Single-Source Risk | How Much Rides on One Supplier — spend share, cover, switching time | Goods | Concentration | If one supplier stopped, what stops with them? | 2 | O | Quarterly | Group | Y | Alternate-supplier register; switching lead times |
| 90 | Request to Goods | How Long From Request to Goods — the days, and where it stalls | Goods | Cycle | How long from someone needing it to it being usable? | 2 | G C | Monthly | Company | Y | Stage timestamps incl. supplier ack and put-away |
| 91 | Make or Buy | Items We Make Dearer Than We Can Buy — and what stopping would save | Goods | Exception | What am I manufacturing that I could just buy? | 3 | O G | Quarterly | Product | Y | Current external buy price; avoidable overhead |
| 92 | New Lines | Do New Products Sell? — sell-through by launch cohort | Goods | Cohort | Are the new lines we add any good? | 3 | G O | Quarterly | Cohort | Y | True product launch date; trial quantity; range owner |
| 93 | People vs Plan | People Against Plan — headcount, wage bill, overtime and cost per head | People | Scorecard | Am I carrying the people I agreed to carry, at the cost agreed? | 1 | O G | Weekly | Group→Company | Y | Approved establishment; wage budget |
| 94 | Wage Bill Gap | Where the Wage Bill Went — heads, pay rate, overtime, statutory, mix | People | Variance | Is the overspend more people or higher pay? | 1 | O C | Monthly | Company | Y | Budget decomposed into heads × rate |
| 95 | Today's Attendance | Today's Attendance — who is in and which branch cannot open properly | People | Flash | Do we have the people to trade today? | 1 | G B | Daily | Branch/Shift | Y | Rosters; minimum cover per branch |
| 96 | People Waiting | People Decisions Waiting on You — hires, pay, exits and what each delay costs | People | Docket | What people decisions are sitting on me? | 1 | O G | Daily | Personal | Y | Pay bands; establishment link; statutory deadlines |
| 97 | Overhead vs Plan | Overhead Against Plan — running-cost categories, biggest miss first | People | Scorecard | Is the cost of running the business inside what we agreed? | 1 | O C | Monthly | Group→Branch | Y | Overhead budget by category **and** branch |
| 98 | Idle Assets | Assets That Earn Nothing — value idle, how long, and the cost to hold | People | Exception | What have I bought that is doing nothing? | 1 | O G | Monthly | Site | Y | Asset usage signal; standby/accepted flag |
| 99 | Machine League | Machine League — hours run against hours available, output per machine | People | League | Which machines work for me and which stand? | 1 | O F | Weekly | Plant | Y | Machine run-hours; planned availability |
| 100 | Overtime Causes | Why We Paid Overtime — hours and cost by cause, against a normal month | People | Variance | What caused the overtime, and was it avoidable? | 2 | G F | Monthly | Branch/Shift | Y | Overtime reason codes; absence-to-cover link |
| 101 | Capital Employed | Where the Capital Is Tied Up — and what the group earns on it | People | Position | How much is locked in, where, and is it earning? | 2 | O C | Monthly | Group→Company | Y | Balance sheet by unit; cost of borrowing |
| 102 | Vehicle League | Vehicle League — cost per kilometre and days off the road | People | League | Which vehicles are quietly eating me? | 2 | O G | Monthly | Vehicle | Y | Odometer; fuel and repairs per vehicle |
| 103 | Repair or Replace | Assets Costing More to Keep Than to Replace | People | Exception | Which machines am I repairing to death? | 2 | O G | Monthly | Asset | Y | Repair cost per asset; replacement price |
| 104 | Assets Not Found | Assets Nobody Can Find — book value, last seen, who signed | People | Exception | Do I still own what the books say I own? | 2 | O C | Alert | Site | Y | Custodian record; exit-to-handover link |
| 105 | Payroll Check | Does the Payroll Match the People? — the unexplained names isolated | People | Recon | Am I paying anyone who does not work here? | 2 | O C | Each run | Company | Y | Independent employee register; duplicate bank/NIDA |
| 106 | Quiet Cost Rises | Costs That Rose Without a Decision — recurring lines up on last year | People | Exception | Which regular bills crept up without approval? | 2 | O C | Monthly | Supplier | Y | Recurring-cost flag; contract reference |
| 107 | Capex Due | What Capital Spend Falls Due — committed cash by month | People | Forecast | What capital have I promised, and when must I pay? | 2 | O C | Monthly | Company | Y | Capex approvals carrying a cash date |
| 108 | Space League | Space League — margin per square metre against rent, by branch | People | League | Am I paying more for this shop than it earns? | 2 | O G | Quarterly | Branch | Y | Floor area split; rent and review dates per branch |
| 109 | Leave Owed | How the Leave Bill Moved — days earned, taken, paid out and owed | People | Position | What would it cost if everyone took their leave? | 2 | C G | Monthly | Company | Y | Entitlement rules; daily rate for valuation |
| 110 | Key Person Risk | How Much Rides on One Person — duties, approvals and relationships | People | Concentration | If one person did not come in Monday, what stops? | 3 | O | Quarterly | Group | Y | Duty/substitute map; relationship ownership |
| 111 | Fixed Cost Base | What It Costs to Open the Doors — the monthly fixed cost and how it moved | People | Position | If we sold nothing next month, what would we still pay? | 3 | O C | Quarterly | Group→Company | Y | **Fixed / variable classification on every account** |
| 112 | Insurance Gap | Does the Insurance Cover What We Own? — insured against replacement | People | Recon | If the warehouse burned tonight, would the cover rebuild it? | 3 | O C | Quarterly | Site | Y | Policy schedule: sums insured and expiry per site |
| 113 | Contracts Due | What We Are Locked Into — rents, licences and services by notice date | People | Forecast | When is my last chance to get out or renegotiate? | 3 | O C | Monthly | Group | Y | Contract and licence register with notice periods |
| 114 | Safety Incidents | Injuries and Near Misses — days lost and the causes that repeat | People | Exception | Is anyone getting hurt, and is it the same thing twice? | 3 | O G | Alert | Site | – | Incident and near-miss register |
| 115 | Broken Rules | What Broke the Rules This Week — value at risk and repeats | Rules | Exception | Did anybody step outside our rules, and for how much? | 1 | O | Daily | Group | Y | **Machine-readable rule catalogue with accept/assign** |
| 116 | What Is Final | How Much of This Month Is Final — and what is provisional | Rules | Recon | Can I believe the numbers I looked at this morning? | 1 | O C | Daily | Group→Company | Y | Posting/costing status; close checklist; view log |
| 117 | EFD Gaps | Sales With No Fiscal Receipt — count, value and days open | Rules | Exception | Have we sold anything the taxman has no receipt for? | 1 | C B | Daily | Company/Till | – | TRA acknowledgement stored per sale |
| 118 | Tills Gone Dark | Tills That Traded While the EFD Was Down — hours and value | Rules | Exception | Did a counter keep taking money with the device down? | 1 | O B | Alert | Till | Y | Device heartbeat; manual receipt book register |
| 119 | Supplier Bank Changes | Supplier Bank Details Changed Before Payment — who and when | Rules | Exception | Did anyone change where our money goes? | 1 | O C | Alert | Group | Y | Field-level change history with before/after |
| 120 | Approval Delay | How Long From Request to Decision — and where it stalls | Rules | Cycle | How long do my people wait for a yes? | 1 | O G | Weekly | Group | Y | Arrival **and** decision timestamps per stage |
| 121 | Unfixed Breaches | Control Breaches by Age — how long unfixed, and with whom | Rules | Ageing | What did we find, tell someone about, and still not fix? | 1 | O | Weekly | Breach owner | Y | Breach owner, due date and accepted state |
| 122 | Instant Approvals | Approvals Decided in Under a Minute — who and how much | Rules | Exception | Which approvals are decisions and which are clicks? | 2 | O | Monthly | Approver | Y | Arrival timestamps; approval→outcome link |
| 123 | Missing Stock | Where the Missing Stock Went — losses by cause, unexplained isolated | Rules | Variance | How much of the shortage is thieving and how much paperwork? | 2 | O G | Each count | Branch | Y | Adjustment reason codes; count discipline |
| 124 | Shrinkage League | Shrinkage League — loss per shilling of stock, by branch | Rules | League | Where am I losing goods, and is it always the same place? | 2 | O G | Quarterly | Branch | – | Count-coverage normalisation; board tolerance |
| 125 | Odd Journals | Manual Journals Into Unusual Accounts — who and how much | Rules | Exception | Has anybody moved money by hand where hands never go? | 2 | C | Monthly | Company | Y | Per-account manual-entry history; attachments |
| 126 | Voided After Print | Documents Voided After Printing or Fiscalising — value and who | Rules | Exception | Are we cancelling paperwork after the goods left? | 2 | O B | Weekly | Till/User | Y | Print, fiscalise and void event log; despatch link |
| 127 | Missing Numbers | Gaps in the Invoice and Receipt Numbers — where and how old | Rules | Recon | Are any receipt numbers simply not there? | 2 | O B | Weekly | Till/Book | Y | Manual receipt book registration; void register |
| 128 | Just Under Limit | Requests Priced Just Below an Approval Limit — by requester | Rules | Exception | Is anyone shaping paperwork so it never reaches me? | 2 | O | Monthly | Requester | Y | Approval limit ladder with history |
| 129 | Unsplit Duties | How Much One Person Can Do Alone — the value at risk | Rules | Concentration | Can anyone run a whole money cycle without meeting a human? | 2 | O G | Monthly | Group | Y | **Permission→business-step map** |
| 130 | Same Names Always | Trade That Always Runs Through the Same Two People | Rules | Concentration | Which supplier only ever deals with one of my people? | 2 | O | Quarterly | Group | Y | Multi-person baseline from own history |
| 131 | Shared Bank Details | Suppliers Sharing Bank or Phone Details With Staff | Rules | Exception | Are we paying a supplier that is one of my own people? | 2 | O | Quarterly | Group | Y | Normalised party matching under a controlled path |
| 132 | VAT Match | Does the VAT Match? — return, ledger and EFD side by side | Rules | Recon | Do the three VAT numbers that must agree, agree? | 2 | C | Monthly | Company/VRN | – | Filed return values captured; supplier VRN validity |
| 133 | Tax Exposure | What We Would Owe If TRA Came Tomorrow — by cause | Rules | Position | If they audited us this week, what is the worst bill? | 2 | O C | Monthly | Company | Y | Penalty rules; WHT applicability; provisions link |
| 134 | Tax Falling Due | What the Taxman Wants Next — amount, date and cash cover | Rules | Forecast | What must I pay next and will the money be there? | 2 | C | Weekly | Company | Y | Filing/payment calendar per head, as data |
| 135 | Tax Rate Gap | Why We Pay More Tax Than the Rate — statutory to actual, by cause | Rules | Variance | The rate is 30% — why did we hand over 41%? | 2 | O C | Quarterly | Company | Y | Tax-treatment attribute on the chart of accounts |
| 136 | Order Book Match | Does What We Delivered Match What We Billed? — gaps by age | Rules | Recon | Has everything delivered been invoiced, and vice versa? | 2 | C | Monthly | Company | Y | Delivery note as a linked posted event |
| 137 | Related Parties | Trade With Owners, Staff and Their Families — value, terms, margin | Rules | Concentration | How much business is with people connected to us? | 2 | O C | Quarterly | Group | Y | Related-party declaration register; comparator rule |
| 138 | Open Claims | Claims Against Us by Age — value, stage and what we set aside | Rules | Ageing | What are we being sued or assessed for? | 2 | O C | Quarterly | Company | Y | Claims register |
| 139 | What We Guaranteed | What We Have Guaranteed for Others — exposure and triggers | Rules | Concentration | What have I promised to pay if someone else does not? | 2 | O | Quarterly | Group + personal | Y | Guarantee and security register |
| 140 | Dormant Access | Powerful Accounts Nobody Uses — what they can still do | Rules | Exception | Which accounts can still move money but nobody uses? | 3 | O G | Quarterly | Group | Y | HR leaver dates joined to user accounts |
| 141 | Since You Looked | What Changed Since You Last Looked — the numbers that moved, and why | Group | Position | I have been away nine days — what do I need to know? | 1 | O | Every open | Personal | Y | **Per-user view log**; normal movement per measure |
| 142 | The Seven Numbers | The Seven Numbers — the group's measures against board plan | Group | Scorecard | Of the seven things this group must deliver, how many are we? | 1 | O C | Weekly | Group | – | Versioned, dated board plan; agreed standards |
| 143 | Month So Far | This Month So Far — pace against the same point last month | Group | Flash | Are we on pace, or will I be surprised on the 31st? | 1 | O | Daily | Group→Company | – | Working-day calendar incl. holidays and Eid |
| 144 | Waiting on You | Waiting on You — every decision addressed to you, by cost of delay | Group | Docket | What is waiting on me, and what does waiting cost? | 1 | O | Daily | Personal | – | Consequence tagging across every queue |
| 145 | Company League | Company League — profit per company against plan and peers | Group | League | Which business carries the group and which is carried? | 1 | O | Weekly | Company | – | Central cost allocation basis, stable and dated |
| 146 | Plan Gap | Where the Group Profit Went — plan to actual, by cause | Group | Variance | We planned 640 and made 480 — where did it go? | 1 | O C | Weekly | Group | – | Plan decomposed to price × volume × mix |
| 147 | Group Money Left | How Long the Group's Money Lasts — cash plus undrawn credit | Group | Forecast | Across all companies, when do I run out of room? | 1 | O C | Weekly | Group | Y | Transferability rules; facility limits |
| 148 | Losing Money | Units That Lost Money — how much, how long, and where | Group | Exception | Which parts of the group consume my money? | 1 | O | Weekly | Unit | Y | Unit-level P&L; accept-with-review-date |
| 149 | Year-End Forecast | How the Year Will End — forecast close against board plan | Group | Forecast | What number do I take to the board in December? | 2 | O C | Weekly | Group | Y | Stored forecast runs; committed order book |
| 150 | Real Growth | How Much of the Growth Is Real — price, volume and new sites | Group | Variance | Sales are up 22% — how much of that is real? | 2 | O | Monthly | Group | Y | Maturity rule for new units; inflation series |
| 151 | Capital League | Where the Next Shilling Earns Most — return on capital used | Group | League | Which business turns a shilling into the most shillings? | 2 | O C | Monthly | Company/Branch | Y | **Balance sheet by company and branch; hurdle rate** |
| 152 | Group Trajectory | Which Way the Group Is Going — 24 months against the band | Group | Trend | Forget this month — is this group better or worse? | 2 | O | Monthly | Group | – | Consistent basis; event markers |
| 153 | Season Shape | Is This Month Normal for the Season? — against three years | Group | Trend | Sales dropped 18% — is that a problem or just August? | 2 | O G | Monthly | Group/Company | Y | Movable-feast calendar (Ramadan, Eid), harvest |
| 154 | Where the Money Sits | Where the Money Is Tied Up — by company, and for how long | Group | Position | I am profitable and never have cash — where is it? | 2 | O C | Monthly | Company | Y | Retained month-end balance snapshots |
| 155 | Intercompany | What the Companies Owe Each Other — balances and their age | Group | Position | Is one company quietly funding another? | 2 | C | Monthly | Company pair | – | Intercompany tag at source; trade vs funding split |
| 156 | Unapproved Moves | Money Moved Between Companies Without Approval | Group | Exception | Did anybody move my money without asking? | 2 | O | Alert | Company pair | Y | Authority matrix for inter-company movement |
| 157 | What Moves Profit | What Moves Profit Most — the drivers ranked by impact | Group | Concentration | Of what I cannot control, which hurts most if it moves? | 2 | O C | Quarterly | Group | Y | Defined adverse move per driver; fuel volumes |
| 158 | Weaker Shilling | How Much Rides on the Exchange Rate — profit at four rates | Group | Concentration | If the shilling goes to 2,900, what happens to my profit? | 2 | O C | Alert | Group | Y | Transaction currency at source; pass-through lag |
| 159 | Bank Covenants | Where We Stand With the Bank — ratios against the covenants | Group | Scorecard | Am I about to breach a loan condition? | 2 | O C | Monthly | Borrowing entity | Y | Covenant definitions, limits and test dates |
| 160 | Owner Drawings | What the Owners Took Out — against profit and cash left | Group | Position | How much have we actually taken out, and could we afford it? | 2 | O | Monthly | Group | Y | Owner-transaction tagging across all companies |
| 161 | Capital Waiting | Capital Waiting on You — capex asks, value and cost of delay | Group | Docket | What spending decisions are sitting on me? | 2 | O | Alert | Personal | Y | Promised return per ask; quotation validity |
| 162 | New Branch Payback | Do New Branches Pay Back? — ramp and profit curve by opening year | Group | Cohort | Are the branches I open now as good as the old ones? | 2 | O | Quarterly | Cohort | Y | Opening capital; closed branches retained in cohort |
| 163 | Project Overruns | Projects Costing More Than Quoted — overrun and stage reached | Group | Exception | Which jobs will lose money, and can I still stop it? | 2 | O G | Monthly | Project | – | Project cost capture; variations as objects |
| 164 | The Twenty Numbers | The Twenty Numbers — the group's standing measures, each quarter | Group | Scorecard | Once a quarter: is this whole group in good order? | 2 | O C | Quarterly | Group | – | A board standard for each of the twenty rows |
| 165 | Before the Visit | How This Branch Stands Before You Visit — eight measures | Group | Scorecard | I am driving to Mbeya — what do I need to know? | 3 | O G | On demand | Branch | Y | Last-visit date per branch per executive |
| 166 | Bigger or Busier | Are We Bigger or Just Busier? — profit, capital and people | Group | Trend | We do more business — am I actually better off? | 3 | O | Quarterly | Group | Y | Historic balance sheets and headcount by month |
| 167 | Plan Credibility | Does the Plan Match What Happened? — bias by company | Group | Recon | How wrong have these people's plans been before? | 3 | O C | Quarterly | Company | Y | Every plan version retained with its date |
| 168 | Investment Payback | Investments Against Their Promise — by project and year | Group | Scorecard | The machine was to pay back in 18 months — did it? | 3 | O | Quarterly | Project | Y | Structured business case linked to the capex |
| 169 | Head Office Cost | What the Centre Costs Each Company — share and trend | Group | Position | What am I paying for the privilege of being a group? | 3 | O C | Quarterly | Group | Y | Central costs identified; usage measure per function |
| 170 | What Waiting Cost Us | What Our Own Delays Cost — approvals, in shillings | Group | Docket | What did our own slowness cost last month? | 3 | O | Monthly | Role | Y | Needed-by dates; priced consequence of delay |
| 171 | Did It Work | What Our Decisions Changed — the action taken and what followed | Group | Recon | Did any of my decisions actually move a number? | 3 | O | Quarterly | Group | Y | **A decision log** |
| 172 | Never Opened | What Nobody Opens — the screens to retire before the suite rots | Group | Exception | Which of these screens is anyone using? | 3 | O C | Half-yearly | The suite | Y | Per-screen usage telemetry; capture cost per feed |

**(151 reports; numbering runs to 172 because the sections are numbered continuously and 21 proposals were merged out below.)**

### What was merged out, and why

- **Profit to Cash** → merged into **Profit vs Cash** (identical question, two archetypes; the Variance Bridge won).
- **What Waiting Cost Us** existed three times (cash, credit, buying) → **one group report**, filterable by decision type.
- **Money Tied Up** existed twice → the Cycle-Time version kept; the Position version renamed **Where the Money Sits**.
- **Slipping Payers + Shrinking Baskets + Customers Gone Quiet** → one register, **Customers Slipping Away**, with a stage column (narrowing / slowing / silent).
- **Cost to Serve + Costly Customers + customer credit cost** → folded into **Customer League** (ranked on margin after serving *and* credit); the group-level erosion bridge survives as **What Credit Costs**.
- **Received Not Billed** + **Goods Not Billed** → **Bill vs Goods**, with the ageing as a body row.
- **Empty Shelves** absorbed **Sales We Missed**; **Late Orders** absorbed **Overdue Orders**; **Single-Source Risk** absorbed **Supplier Dependence**; **Request to Goods** absorbed **Order to Shelf** and **Receipt to Shelf**; **Supplier League** absorbed **Rejected Goods**; **Factory Hours** absorbed **Costly Stoppages'** overlap; **Key Person Risk** absorbed **One Seller Risk**; **Cash Match** absorbed **Wallet Match**; **Unbanked Cash** absorbed **Till to Bank**; **Count vs Books** absorbed **Impossible Stock** and **Goods in Transit** losses; **Tax Falling Due** absorbed **Payroll Taxes Due**; **What Is Final** absorbed **Close Progress**, **Backdated Entries** and **Group vs Companies**; **Broken Rules** absorbed **Breach Rate** as its trend; **Debtor Ageing** absorbed **Book Movement**, **Collection Days**, **Biggest Debtors** and **Retentions Held**; **Capital Waiting** absorbed **Asset Spend Waiting**; **Waiting on You** is the master of all eight dockets, which remain as its typed queues.
- **Renamed:** `Cash to 30 Days` → **Cash Runs Out** (R8: no horizon in a name). `Line Stoppers` replaced "material runway" (R12: *runway* does not survive Swahili). `Discount Overrides` / `Discounts Off Rule` / `Over-Limit Discounts` → one name, **Over-Limit Discounts** (R9).
- **Reclassified:** `Goods in Transit` was labelled an Ageing Pyramid and is an Exception Register (its buckets are breach severities, not ages) — the ageing is a field, not the form.

---

## 4. The named tiers

### **The Morning Brief** — the home screen, six tiles, in this order

| # | Tile | What it answers before coffee | The comparison it carries |
|---|---|---|---|
| 1 | **Today's Trade** | Is today normal, and which branch is dragging? | Same weekday, 4-week median, at the same hour |
| 2 | **Since You Looked** | What changed while I was not looking? | Your own last-seen values, against normal movement for that many days |
| 3 | **Waiting on You** | What is stuck on me and what is it costing? | Each item against its policy limit, and the queue against your own median decision time |
| 4 | **Cash Runs Out** | On what date do we run out of room? | Against the floor, against last week's date, against the forecast's own past error |
| 5 | **Broken Rules** | Did anybody step outside the rules, for how much? | The same count and value seven days ago; new vs repeat |
| 6 | **The rotating slot** | The question the calendar is asking | **Month So Far** days 1–17 · **Where Month Lands** from the 18th · **Profit vs Cash** for three days after each close · **The Twenty Numbers** in the first week of a quarter |

Six tiles, never seven. Everything else is one tap behind a shelf.

### **The Weekly Ten** — opened Monday, or pushed as one digest at 07:00
The Seven Numbers · Sales Against Plan · Branch League · Margin Gap · Debtor Ageing · Unbanked Cash · Dead Stock · Stock Cover · Supplier League · Company League.
Plus, on the same rhythm but read only when they fire: Sold Below Cost, Over-Limit Discounts, Over Their Limit, Late Orders, Runs Out First, Empty Shelves, Customers Slipping Away, Open Complaints, Orders by Age, Approval Delay, Unfixed Breaches, Losing Money, Cash Against Plan, Cash League, Till Match, Promises Broken, Disputed Invoices, Odd Payments, Buying Dead Lines, Off-Contract Buys, Wrong Branch Stock, Expiring Stock, Voided After Print, Missing Numbers, Today's Attendance, Today's Cash, Today on the Floor, Factory Against Plan, Line Stoppers, Staff/Machine leagues.

### **The Month-End Pack** — ten screens, fixed order, exported as one PDF
1 **What Is Final** (always first — the pack opens by saying how much of it can be trusted) · 2 The Seven Numbers · 3 Plan Gap · 4 Profit vs Cash · 5 The Month-End Swing · 6 Company League · 7 Where the Money Sits · 8 Debtor Ageing · 9 Broken Rules · 10 Did It Work.
*Fixed contents, no additions — a board pack that changes shape cannot be compared month to month.*
Also read at month-end, outside the pack: The Profit Ladder, Margin Direction, Margin After Costing, Profit Quality, Money in Stock, Stock Gap, Count vs Books, Bill vs Goods, Unit Cost Gap, Material Loss, Factory Hours, Landed Cost Gap, Money Tied Up, Cash Gap, Debt at Risk, Wage Bill Gap, Overtime Causes, Overhead vs Plan, Capital Employed, Payroll Check, VAT Match, Tax Exposure, Order Book Match, Intercompany, Owner Drawings, Real Growth, Capital League, Group Trajectory, Season Shape, Year-End Forecast, Project Overruns.

### **The Board and Bank Pack** — the Month-End Pack, plus
Bank Covenants · Borrowing Room · What We Guaranteed · Open Claims · Tax Exposure · Capital League.

### **The Quarter Review** — sat down, once every three months
The Twenty Numbers · Bigger or Busier · Did It Work · Plan Credibility · Investment Payback · Head Office Cost · New Branch Payback · New Customers · New Lines · What Moves Profit · Single-Source Risk · Biggest Customers · Key Person Risk · Related Parties · Open Claims · What We Guaranteed · Unsplit Duties · Same Names Always · Shared Bank Details · Dormant Access · Shrinkage League · Space League · Lines Not Paying · Fixed Cost Base · If the half-year: Never Opened, Insurance Gap.

### **On Demand** — opened for a specific occasion, never on a rhythm
Before the Visit (before a branch trip) · Before the Meeting (before a customer meeting) · Make or Buy (before a range or capacity decision) · Contracts Due (before signing anything) · Weaker Shilling (when the rate moves) · Did Prices Hold? (14, 30 and 60 days after a price change) · Capital Waiting (whenever an ask arrives) · Cost Drift and Insurance Gap (before signing accounts or renewing cover).

---

## 5. Naming rationale

A report's name is a contract with the person who taps it. If the name does not state what you will know afterwards, the tap is a gamble — and executives stop gambling after about three losses. So: the name states the **promise**, never the plumbing; question-shaped when the report is a diagnosis, noun-phrase when it is a station you visit; ≤22 characters and ≤3 words on the tile, ≤60 in the catalogue; the comparison and the as-of live in the subtitle so the name survives a change of basis; the name identifies a **condition**, never a person; and every name must survive being said in Swahili to a manager and being said aloud in a room with the bank and the auditor. Eighteen words are banned outright as whole names or trailing nouns — *Report, Summary, Analysis, Data, Overview, Dashboard, Metrics, KPIs, Statistics, Details, Info, Management, Module, Master, List, Screen, View, Insights, Intelligence* — because each is a confession that the author never decided what the screen was for.

| # | What a normal ERP calls it | Our name | Why ours is better |
|---|---|---|---|
| 1 | Sales Summary Report | **Today's Trade** | Banned word gone; names the moment, and the full name states the comparison (same weekday) that turns a number into information. |
| 2 | Gross Margin Analysis | **Margin Gap** | The headline is the *gap*, not the margin. Question-shaped because it is a diagnosis, and it promises causes — which is what a bridge owes you. Translates cleanly: *faida imepotelea wapi*. |
| 3 | Debtors Ageing Report | **Debtor Ageing** | Keeps the term finance uses on the tile; the full name — *Who Owes Us, and How Long* — promises names and exception-led ordering. *Nani anatudai* is exact. |
| 4 | Inventory Analysis Dashboard | **Dead Stock** | Names the condition, not the module. `Stock`, not `Inventory` — nobody at a Kariakoo counter says inventory. |
| 5 | Cash Flow Projection Report | **Cash Runs Out** | A forecast owes you a **date**, not a line. `Cash Runway` was rejected: *runway* is a metaphor that dies in translation and makes a plain fact sound like consultancy. |
| 6 | Purchase Order Approval Queue | **Buys Waiting** | Addressed to a person, so it earns a tile; the full name promises the cost of delay, which turns an inbox into a report. |
| 7 | VAT Compliance Data Extract | **EFD Gaps** | Names the breach in the regulator's own words; ageing promised, so the register can escalate. Three banned words removed. |
| 8 | Customer Profitability Analysis | **Customer League** | League Table grammar makes the ranking rule visible, so nobody argues about which number ranks them — and the finding (revenue rank vs margin rank) is in the name's promise. |
| 9 | Working Capital Cycle Ratio | **Money Tied Up** | Un-stacks the nouns. *Fedha zetu zimekaa muda gani* is a sentence a manager repeats; "working capital cycle ratio" is one nobody does. |
| 10 | Fixed Asset Utilisation Report | **Idle Assets** | Says the bad thing out loud. Utilisation is a percentage; idleness is a decision — sell it, hire it out, or move it. |
| 11 | Segregation of Duties Matrix | **Unsplit Duties** | Owner language for an auditor's phrase. The full name — *How Much One Person Can Do Alone* — states the exposure in money, not in a control framework. |
| 12 | Budget Variance Report (Payroll) | **Wage Bill Gap** | *Wage bill* is what the owner calls it; *gap* commits the screen to leading with the miss, and the full name promises the split between more people and higher pay — the only split that changes what you do. |

Two family extensions were declared rather than smuggled: **Flash** may say *This <period> So Far* (a group owner's clock is the month, not the day), and **Forecast** may ask *Can We Pay <obligation>?* for date-certain obligations where the only question is yes-or-no. Both are registered so the suite stays authored rather than accumulated.

---

## 6. Alerts — the reports that push

An alert is a promise that the app will interrupt you only when interruption is worth it. Everything not on this list is **opened, never delivered**. Three reports (Tills Gone Dark, Supplier Bank Changes, Unapproved Moves) are *alert-first*: their register exists as the audit trail behind the push and should not sit on the home screen.

| Report | Trigger |
|---|---|
| Today's Trade | 12:00 and 17:00 only if group margin rate is >3pp below the weekday median, **or** any branch is below 60% of its weekday-median margin |
| Cash Runs Out | Crossing date falls inside 15 working days, or moves 5+ days nearer in a week |
| Salary Cover | Cover falls below 1.0× inside 14 days; daily inside 5 days; and again when it recovers |
| Profit vs Cash | Cash conversion below 25% of profit for two consecutive months |
| Unbanked Cash | Any branch over its holding limit or 48 hours; **immediately** if deposited is less than declared by >TZS 200k |
| Cash Match | Unexplained difference over TZS 1M or aged past 14 days; any account 10 days unreconciled |
| Till Match | A session out by >TZS 100k, or a cashier short in 3 of their last 5 sessions |
| Debtor Ageing | 90+ value rises >15% week on week, or any account enters 90+ with >TZS 20M |
| Over Their Limit | Total over-limit exposure passes TZS 100M, or a single account exceeds its limit by >50% |
| Promises Broken | A promise over TZS 25M passes its date unpaid, the morning after |
| Discounts We Lost | Monday, when catchable discount exceeds TZS 2M; immediately when a single discount >TZS 1M expires in 48h |
| Bills Falling Due | Projected shortfall inside 10 working days, or a must-pay item uncovered on its date |
| Payments Waiting / Buys Waiting / Orders Waiting / Credit to Approve / Stock Decisions / People Waiting / Capital Waiting | On arrival for anything blocking despatch or production, any item above its value threshold, any expiring quote or discount inside 48h, any statutory deadline inside 5 days; one 08:00 digest for the rest; escalation at 24h |
| Sold Below Cost | A single line losing >TZS 500k, or the same product breaching at 3+ branches on one day |
| Over-Limit Discounts | A single order discounted >TZS 1M beyond limit, or beyond-limit shillings >2% of the week's margin |
| Empty Shelves | Estimated lost sales in a day above TZS 5M at any branch, or an A-class line out >6 trading hours |
| Runs Out First / Line Stoppers | Margin at risk >TZS 30M; any material whose cover drops below lead time + 5 days; a stop date moving 3+ days earlier |
| Wrong Branch Stock | A purchase order raised >TZS 5M for an item with 2+ months surplus cover within transfer range |
| Late Orders | Single overdue order >TZS 50M, or anything blocking a confirmed despatch 3 days late |
| Count vs Books / Missing Stock | Unexplained variance >1% of a location's value or TZS 5M; count coverage below 60% of value in 90 days |
| Today on the Floor | Run-rate <70% of the same-shift median at the 4-hour mark, or a single stoppage past 60 minutes — max one push per shift |
| Costly Stoppages | A single cause costing >TZS 20M in a month, or a previously fixed cause reappearing |
| EFD Gaps | Unfiscalised value >TZS 1M, any gap past 48 hours, any gap surviving into a filed period |
| **Tills Gone Dark** | A till records sales for >15 continuous minutes with no successful fiscalisation; again if the same till repeats within 7 days |
| **Supplier Bank Changes** | A bank change followed by a payment >TZS 5M within 30 days; **any** change made by a user who can also release payments |
| **Unapproved Moves** | Any inter-company movement above the owner's stated threshold, immediately |
| Odd Payments | Suspected duplicate >TZS 5M; any bank-change-then-pay pattern |
| Shared Bank Details | A newly created supplier matching a staff bank account or phone number, **before the first payment** |
| Assets Not Found | An employee holding assets is marked as leaving, before final dues are released |
| Safety Incidents | Any lost-time or reportable incident, immediately, at any hour |
| Payroll Check | Before every payroll release if any unexplained name exists; direct to Owner for a duplicate bank account |
| Tax Falling Due | 5 working days before a due date where cover is below 1.2×; any filing date passing without a filing recorded |
| Bank Covenants | 60, 30 and 7 days before a test date if forecast headroom is under 10%; immediately on a current breach |
| Contracts Due | 30 and 7 days before a notice date; 45 days before a licence expiry |
| Group Money Left | Crossing date inside 20 working days, or moving in 5+ days in a week |
| Weaker Shilling | Rate moves >3% from the plan assumption; unhedged exposure over its ceiling |
| Broken Rules | Open value at risk crosses TZS 50M; any single breach >TZS 10M; any breach 14 days unassigned |
| What Is Final | Provisional value exceeds 10% of month-to-date sales; **or a figure you viewed in the last 24 hours has since moved more than 5%** |
| Losing Money | A unit enters the register for the first time, or reaches 3 consecutive losing months |
| Customers Slipping Away | A top-50 account passes 2× its own normal buying interval, or loses 40% of its category breadth in a quarter |
| Open Complaints | A top-50 account complains, immediately; any complaint past twice the resolution standard |
| Did It Work | Once, at the review date set when the decision was recorded |

**Deliberately silent:** every League Table, every Trend (except on a *run* of three consecutive months outside the band), every Scorecard, Break-even Day, Cohorts, Instant Approvals, What Waiting Cost Us, Never Opened. A pushed league table becomes political within a fortnight; a pushed trend teaches the owner to react to noise.

---

## 7. The data we do not yet capture

Every ⚠ in the catalogue, consolidated and ranked by how many reports it unlocks. Effort is rough: **S** = a table and a form (days) · **M** = a table plus a capture habit or an integration (weeks) · **L** = a policy decision plus a data model plus a discipline that must hold (months, and it is mostly people).

| # | Missing data | Reports that need it | How it would be captured | Effort |
|---|---|---|---|---|
| 1 | **Plans and targets, versioned, dated and decomposed** — sales by branch × channel × seller × month; margin split price × volume × mix; cash/treasury plan; collection plan; stock plan; the six credit targets; the seven and the twenty standards | 2, 4, 20, 21, 22, 25, 45, 51, 52, 64, 76, 79, 93, 97, 142, 146, 149, 164, 167 | A planning screen that stores a plan *the way the bridges decompose it*, frozen on approval, with the approver and date; never editable in place | L |
| 2 | **Margin snapshot at the moment of sale** (price, cost basis, margin, frozen per line) | 1, 28, 30 | Write the costed margin onto the invoice line at posting; it is unrecoverable retrospectively, so build it first | M |
| 3 | **Fixed / variable / step-fixed classification on every cost account**, plus a business-language ladder mapping | 21, 26, 33, 97, 111, 148, 151 | One attribute per GL account, owned by the CFO, versioned | S |
| 4 | **Promise-to-pay log** — date, amount, who promised, who took it, auto-matched to receipts | 37, 45, 54, 56, 147 | A two-field capture on the collector's and branch screens | S |
| 5 | **Cover policy per category per branch** (min/max days) and measured supplier lead times (order→receipt median and spread, clearance as its own leg) | 64, 66, 71, 72, 77, 79, 82, 90 | Policy table set once by the GM; lead times computed from history nightly | M |
| 6 | **Reason codes, mandatory and controlled** — on adjustments, write-offs, credit notes, voids, overrides, returns, scrap, disputes, reconciling items, stoppages, breakdowns, order blocks, exits, incidents | 15, 55, 68, 71, 85, 86, 88, 105, 114, 116, 123, 125, 126, 127, 136 | Replace free text with a short list per document type; refuse to post without one | M |
| 7 | **Unserved-demand and availability history** — a one-tap "asked for, not available" on POS and the route app, plus an hourly stock-position snapshot | 65, 66, 82, 14 | POS/route app button; nightly snapshot job | M |
| 8 | **Cost-of-money rate and carrying cost per shilling of stock** (capital + storage + insurance + historical obsolescence by category) | 11, 13, 32, 53, 83, 101, 151 | Two board-set numbers entered quarterly, plus a storage-cost allocation | S |
| 9 | **Related-party and customer/supplier grouping**, plus customer segment class and duplicate detection | 6, 11, 18, 43, 72, 89, 130, 137 | A parent-party field with a merge tool and a periodic duplicate scan | M |
| 10 | **Approval instrumentation** — arrival *and* decision timestamps at every stage, delegation and absence, needed-by dates, priced consequence of delay, blocking-despatch/production links, limit ladder with history | 5, 40, 46, 69, 74, 96, 120, 122, 128, 144, 161, 170 | Extend the approval object; the timestamps are the cheap half, the consequence link is the valuable half | L |
| 11 | **Effective-dated list prices, floor prices, discount authority limits, contract prices, and price-change as an event** (announced, effective, intended %, scope) | 23, 24, 25, 31, 44 | Price master with effective dates; an authority matrix table; a price-change object | M |
| 12 | **Landed-cost components captured by type at line level** (freight, duty, clearing, demurrage, insurance) | 23, 28, 84, 78 | Extend the receipt/costing model; stop dumping into "other charges" | M |
| 13 | **Standard cost cards and standard yields, version-dated; actual labour hours per works order; routing (which product on which machine)** | 76, 78, 85, 91, 99 | Costing master plus a shop-floor hours capture, however coarse | L |
| 14 | **Commitment accounting** — approved orders not delivered, linked order→receipt→bill, supplier promised date distinct from our requested date, must-pay/can-slip flag, statutory and payroll calendars forward | 47, 48, 49, 71, 89, 134 | Extend PO model; a small calendar table per entity | M |
| 15 | **Bank, wallet and till integrity** — cleared vs book balance, value dates, itemised charges, deposit slips matched to bank credits, aggregator statement feed, negotiated rates, blind-count flag, cashier per session, till holding limits | 34, 35, 41, 42, 50, 59 | Bank/aggregator feeds plus three fields on the till session | M |
| 16 | **Restricted-cash, facility, covenant, security and guarantee registers** — minimum balances, LC margins, deposits with release dates; limits, drawn, rates, expiries, covenant definitions and test dates; guarantees given and assets pledged | 36, 58, 139, 147, 159 | Transcribe from facility letters and contracts into one table; the data is entirely external to the ERP | S |
| 17 | **Retained forecast runs and retained plan versions**, so forecasts and plans can be graded against their own history | 22, 37, 45, 56, 66, 149, 167 | Snapshot every run; never overwrite a plan | S |
| 18 | **Competitor price capture** — item, price seen, where, when, by whom, with freshness rules | 17, 14 | One tap on the route app and the counter; the sales force already sees these prices daily | S |
| 19 | **Establishment, rosters and pay bands** — approved headcount by function, shift rosters and planned hours, attendance, minimum cover per branch, overtime reason codes, leave entitlement and daily rate, pay bands, statutory deadlines | 93, 94, 95, 96, 100, 109 | HR master extensions plus a clock-in source | L |
| 20 | **Independent employee register, unique bank/TIN/NIDA, payroll master-change log, leaver feed joined to user accounts and asset custody** | 104, 105, 140 | The join is the work; the fields mostly exist in separate systems | M |
| 21 | **Asset instrumentation** — machine run-hours and planned availability, per-vehicle odometer and fuel, repair cost linked to a specific asset, replacement price, custodian and location with transfer notes, verification dates, insured limit per site, useful life | 98, 99, 102, 103, 104, 107, 112 | Meter readings and a fuel/repair coding change; an insurance table typed once | M |
| 22 | **Governance substrate** — a machine-readable rule catalogue with thresholds, owners, severities and an accept/assign/clear lifecycle; the permission→business-step map; field-level change history on money-critical master fields; EFD device heartbeat and TRA acknowledgement stored; manual receipt book registration | 115, 118, 119, 121, 126, 127, 129, 131, 117 | The rule catalogue and SoD map are policy work; the change history and heartbeat are engineering | L |
| 23 | **Tax substrate** — filed return values captured back into the system, filing/payment calendar per registered entity, penalty and interest rules, supplier VRN validity, WHT applicability, tax-treatment attribute on the chart of accounts, "filed/reported" flag per period | 132, 133, 134, 135 | A returns table plus one account attribute the CFO can complete in a day | M |
| 24 | **Group substrate** — intercompany tagged at transaction level with trade/funding split, transferability rules, authority matrix for inter-company movement, owner-transaction tagging across all companies, transaction currency and rate on every purchase, central-cost allocation basis with an effective date, balance sheet by company **and branch**, retained month-end snapshots | 151, 154, 155, 156, 158, 160, 169, 101 | Mostly attributes at source plus a monthly snapshot job; the owner-tagging is politically, not technically, hard | L |
| 25 | **External series and calendars** — NBS inflation, official FX reference rate, commodity/market reference prices for key inputs, movable-feast calendar (Ramadan, Eid), public holidays, trading-day and branch-opening calendars, harvest and school terms | 1, 34, 143, 150, 153, 157, 158, 106 | A small ingestion job plus one hand-maintained calendar table | S |
| 26 | **Registers that live in filing cabinets** — claims, guarantees and security, related-party declarations, contracts and licences with notice periods, incidents and near misses, complaints, float and advances, project business cases and variations, insurance policies | 106, 113, 114, 7, 137, 138, 139, 61, 163, 168, 112 | One table each and an annual refresh discipline; individually trivial, collectively transformative | S each |
| 27 | **The suite's own memory** — a decision log (what, from which screen, which measure, target, review date, owner), a per-user view log, and per-screen usage telemetry | 141, 171, 172, 116 | Three small tables; nothing else in this catalogue makes the app compound | S |

**The five that unlock the most for the least:** #17 (retained plans and forecasts), #8 (two numbers entered quarterly), #18 (competitor prices), #26 (six filing-cabinet tables), #27 (the decision log). None of them is an integration project.

---

## 8. The first ten

If only ten are built, these ten — chosen so an owner opens the app every morning within a fortnight, and so each one either earns its keep in cash or makes the next one believable.

| Order | Report | Why it is in the ten |
|---|---|---|
| 1 | **What Is Final** | The trust substrate. Every other screen is an assertion until this one exists, and the first time a number silently restates, the suite is dead. Ship it before anything else, even though nobody will ask for it. |
| 2 | **Today's Trade** | The habit. One number, one comparison, twice a day — it is what makes the app something the owner opens rather than something he is reminded about. It also teaches, in week one, that revenue and margin move differently. |
| 3 | **Waiting on You** | The only screen that *does* something rather than reports something. It converts the app from a viewer into a tool, and within a month it produces the delegation decision that pays for the build. |
| 4 | **Cash Runs Out** | The owner's most frequent private question, answered as a **date**. It changes behaviour the week it ships: overdrafts get drawn early, payment runs get sequenced, collections get pulled. |
| 5 | **Margin Gap** | The intellectual centre. It turns a disappointing month into six named causes with an owner attached to each, and it is the tap-through target for half the suite. |
| 6 | **Debtor Ageing** | The largest pot of money outside the bank, aged by due date with the worst names on the face of the screen. Nothing else recovers cash as fast, and it needs almost no new data. |
| 7 | **Dead Stock** | The second-largest pot. It survives past month two only because of per-category thresholds and an accept-with-expiry state — build both, or it becomes another ignored list. |
| 8 | **Branch League** | The management instrument. It decides where the visit goes, it makes practice transferable from the best branch to the worst, and it is the screen branch managers will argue with — which is how the definitions get fixed. |
| 9 | **Broken Rules** | The switchboard for control. One tile, every exception behind it, ranked by money and by repeat. It is also the cheapest possible insurance against the two things that actually kill trading groups: unbanked cash and redirected supplier payments. |
| 10 | **Profit vs Cash** | The report that recalibrates how the owner reads every other number. "We made 300 million and there is none in the bank" is the most common question in a profitable trading group and the one no ERP has ever answered on one screen. |

**Why not the others yet.** *The Seven Numbers* needs a versioned board plan that does not exist on day one — build it second wave, once #1 in the data table is done. *Product League* and *Customer League* are the two most valuable missing reports in the whole catalogue but both need a clean family hierarchy and a cost-of-money rate first. *Since You Looked* needs the view log, which is trivial to build and impossible to backfill — start writing it from day one even though the screen ships later. *Did It Work* needs a decision log with four fields; start logging decisions from the first day the ten are live, and the report writes itself a quarter later.