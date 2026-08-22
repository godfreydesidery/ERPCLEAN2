# Completeness Critique — What the Catalogue Is Missing

**OrbixERP Executive Mobile · gap pack v1 · 2026-08-18**
27 additional reports, an audit of unfilled matrix cells, and 16 corrections to reports already generated. Same doctrine, same format. Feasibility still ignored; ⚠ marks a build item.

---

## 0. What the audit found

**The generators worked; the joins did not.** Nine domain packs produced ~200 reports and between them left three structural holes:

1. **No product-level profitability exists anywhere.** Sales ranks customers, channels, branches, sellers and routes. Stock ranks branches and ages items. Nobody ever asks *which products make money*. For a trading group carrying thousands of SKUs this is the largest single omission in the catalogue.
2. **Cross-domain reports were not generated at all** — by construction. A domain pack cannot produce "margin per square metre against rent", "what credit days cost in margin", or "everything about one branch on one screen", because each needs two owners' data and neither author owns both. Seven of the reports below are of this kind, and they are the highest-value seven.
3. **Time is under-served.** The catalogue has today, this month, 24 months and cohorts. It has no *rolling twelve*, and — more damaging — no answer to the owner's actual behaviour, which is to open the app after nine days away and ask **"what have I missed?"** That report does not exist and it should be the second most-opened screen in the suite.

Plus five unserved anxieties with no home at all: **litigation and claims, guarantees given, competitor pricing, customer complaints, and safety incidents.** And the projects module — one of the 25 — produced zero reports.

### 0.1 Matrix cells audit

| Archetype × column | Status | Action |
|---|---|---|
| **Docket × People ●** (flagship) | **Empty** | New #4 *People Waiting* |
| **Trend × Cust ●** (flagship) | **Empty** | New #5 *Customer Count* |
| **Scorecard × Growth ●** (flagship) | Empty (Seven Numbers is Growth-adjacent but plan-scoped) | New #2 *The Twenty Numbers* |
| Variance × Tax ○ | Empty | New #6 *Tax Rate Gap* |
| Ageing × Sales ○ | Empty | New #7 *Orders by Age* |
| Ageing × Assets ○ | Empty | New #8 *Plant by Age* |
| Cycle-Time × Assets ○ | Empty | New #9 *Back in Service* |
| Cycle-Time × People ○ | Empty | New #10 *Empty Seats* |
| League × Growth ○ | Empty | New #11 *Growth League* |
| League × Sales ● (product axis) | Empty | New #12 *Product League* |
| Cycle-Time × Cust ○ | Empty | New #22 *Open Complaints* (complaint→resolution is the cycle; served as a register with an age field — see correction C14 on ageing-as-field) |
| Flash × AR ○ | "Empty" | **Correctly covered** by *Today's Cash*. Mark served, do not build a second flash. |
| Flash × Tax ○ | "Empty" | **Correctly covered** by *EFD Gaps* + *Tills Gone Dark*. Mark served. |
| Scorecard × Cust ○ | Empty | New #16 *Before the Meeting* |
| Concentration × Growth ○ | Empty | New #20 *What We Guaranteed* (exposure to things we do not own) |

### 0.2 Two sanctioned family extensions, declared

- **Position & Movement**, personal period: opening balance = *the numbers when you last looked*. Used once, in #1. The archetype survives intact — it is still a stock and its named movements; only the period boundary is per-user.
- **Scorecard**, single-subject: `Before the <occasion>`. A scorecard scoped to one entity and assembled for a specific conversation. Used twice, #3 and #16. It is not a dashboard: it has a fixed measure set, printed standards, and misses-first ordering.

---

## Index of additional reports

| # | Screen name | Archetype | Tier | Why it is here | Novelty |
|---|---|---|:--:|---|:--:|
| 1 | Since You Looked | Position & Movement | 1 | time gap | **NOVEL** |
| 2 | The Twenty Numbers | Scorecard | 1 | matrix ● | CLASSIC |
| 3 | Before the Visit | Scorecard | 1 | cross-domain | **NOVEL** |
| 4 | People Waiting | Decision Docket | 1 | matrix ● | CLASSIC |
| 5 | Customer Count | Trend & Trajectory | 1 | matrix ● | **NOVEL** |
| 6 | Product League | League Table | 1 | cross-domain | **NOVEL** |
| 7 | Lines Not Paying | Exception Register | 1 | cross-domain | **NOVEL** |
| 8 | What Credit Costs | Variance Bridge | 1 | cross-domain | **NOVEL** |
| 9 | Orders by Age | Ageing Pyramid | 1 | matrix | **NOVEL** |
| 10 | Open Complaints | Exception Register | 1 | anxiety | **NOVEL** |
| 11 | Above Market | Exception Register | 2 | anxiety | **NOVEL** |
| 12 | Before the Meeting | Scorecard | 2 | cross-domain | **NOVEL** |
| 13 | Space League | League Table | 2 | cross-domain | **NOVEL** |
| 14 | Growth League | League Table | 2 | matrix | CLASSIC |
| 15 | Related Parties | Concentration & Exposure | 2 | anxiety | **NOVEL** |
| 16 | Same Names Always | Concentration & Exposure | 2 | anxiety | **NOVEL** |
| 17 | Open Claims | Ageing Pyramid | 2 | anxiety | **NOVEL** |
| 18 | What We Guaranteed | Concentration & Exposure | 2 | anxiety | **NOVEL** |
| 19 | Tax Rate Gap | Variance Bridge | 2 | matrix | **NOVEL** |
| 20 | Project Overruns | Exception Register | 2 | module gap | CLASSIC |
| 21 | Retentions Held | Ageing Pyramid | 2 | module gap | **NOVEL** |
| 22 | Safety Incidents | Exception Register | 2 | anxiety | CLASSIC |
| 23 | Plant by Age | Ageing Pyramid | 3 | matrix | **NOVEL** |
| 24 | Back in Service | Cycle-Time & Flow | 3 | matrix | **NOVEL** |
| 25 | Empty Seats | Cycle-Time & Flow | 3 | matrix | CLASSIC |
| 26 | Did It Work | Reconciliation & Assurance | 3 | suite integrity | **NOVEL** |
| 27 | Never Opened | Exception Register | 3 | suite integrity | **NOVEL** |

**19 NOVEL of 27.** The five that most earn the owner's "I have never been able to see that": **Since You Looked (1)**, **Product League (6)**, **What Credit Costs (8)**, **Same Names Always (16)**, **Did It Work (26)**.

---

# TIER 1

---

## 1. Since You Looked

| field | content |
|---|---|
| **Screen name** | `Since You Looked` (16) |
| **Full name** | What Changed Since You Last Looked — the numbers that moved and why |
| **Archetype** | 3 · Position & Movement *(personal-period extension, declared in §0.2)* |
| **The question it answers** | "I have been away nine days. What do I actually need to know?" |
| **Key figures** | (1) The period, stated: "You last opened this on 9 August — 9 days, 7 trading days"; (2) the three measures that moved most against their own normal movement, each with its old value, new value and delta — not the three biggest numbers, the three biggest *surprises*; (3) new exceptions opened while away (count and TZS); (4) exceptions that cleared themselves (count) — proof the business ran without you; (5) decisions that expired unmade (count and TZS at stake) |
| **The comparison** | Each measure's movement against **how much it normally moves in a period of that length** — a debtor book that grows TZS 90M in nine days is not news unless nine days normally produces 30M. Nothing here compares to a plan; the comparison is to the reader's own last state of knowledge. |
| **Exception lead** | The single largest unexpected move, in words: "Debtors grew TZS 210M — three times a normal nine days; TZS 140M of it is one account." Then, ranked second and always shown: **what expired while you were away** — the quotation, the discount deadline, the covenant test. |
| **Consolidation level** | Group, decomposing to company. Personal by definition — two directors opening the app on the same morning see different periods and different rows. |
| **Cadence** | On every open. It is the second screen after the home tray, and after any absence of more than three days it should be the first. |
| **The decision it triggers** | **Owner:** re-entry. Which of the nine days' events still needs an act today, and which are already dead. It converts a backlog into a shortlist and is the difference between an owner who reads the app and one who abandons it after a trip. |
| **Tap-through** | Any moved measure opens its own report at the same two dates. **Refuses** to show a chronological feed of events — a feed is what every notification system already fails at, and re-reading nine days of noise is precisely the thing the owner is avoiding. |
| **Alert condition** | No push. This report *is* what makes push unnecessary; a suite that pushes daily does not need it and does not deserve it. |
| **Data needed** | ⚠ **A per-user view log — which figures this user saw, at what values, at what time** (the single build item; nothing in an ERP records what a human has read). ⚠ **Normal movement per measure per elapsed trading day**, so "surprising" is computable rather than assumed. ⚠ **Expiry dates on time-limited items** (quotes, discounts, covenant tests) so "expired unmade" exists. Trading-day calendar. |
| **Novelty** | **NOVEL.** The catalogue is built for someone who reads it daily. This is the only report built for the man who does not. |

---

## 2. The Twenty Numbers

| field | content |
|---|---|
| **Screen name** | `The Twenty Numbers` (18) |
| **Full name** | The Twenty Numbers — the group's standing measures, reviewed each quarter |
| **Archetype** | 2 · Scorecard |
| **The question it answers** | "Once a quarter, in one sitting: is this whole group in good order?" |
| **Key figures** | Verdict — "13 of 20 within standard". Then twenty fixed rows in five blocks of four, each actual · standard · gap: **Trade** (growth like-for-like, margin rate, customer count, largest-customer share); **Money** (cash cover days, cash conversion, borrowing headroom, covenant headroom); **Capital** (return on capital used, working capital days, stock cover, capex committed vs available); **Control** (unexplained reconciliation total, open breaches over 30 days, unfiscalised value, count of complete-cycle users); **People and risk** (wage share, key-person single points, insurance cover gap, open claims value) |
| **The comparison** | The **standard**, printed — and standards here are board policy, not the annual plan. That is what separates this from *The Seven Numbers*, which is plan-scoped, weekly, and narrow by design. Every row also carries its value four quarters ago, so drift is visible without a chart. |
| **Exception lead** | Rows sorted by size of miss against standard, misses first, blocks collapsed once all four are green. Any row that has been red for four consecutive quarters is flagged **chronic** and rises above larger one-off misses. |
| **Consolidation level** | Group. Rolls down to company for the twelve rows that are meaningful there; the eight group-only rows (covenants, concentration, claims, guarantees) say so rather than fabricating a company split. |
| **Cadence** | Quarterly, formally. Also the **day-one screen**: the first time an owner opens the app, this is what he is shown, because it is the only screen in the suite that describes the whole business at once. |
| **The decision it triggers** | **Owner + CFO, quarterly:** the three things the group will fix this quarter, written down, with names — and the input to *Did It Work* next quarter. Also the annual decision on whether a standard is wrong rather than the performance. |
| **Tap-through** | Any row opens its own domain report. **Refuses** a twenty-first row. The measure set is frozen for the financial year; a scorecard that grows is a dashboard, and this one is deliberately at the edge of what fits. |
| **Alert condition** | No push. Quarterly, deliberate, sat down. |
| **Data needed** | Everything the other nine domains already build, plus ⚠ **a board-approved standard for each of the twenty, dated and versioned** — and evidence of who agreed it. ⚠ **Four quarters of history on a consistent definition**, which means the definitions must be frozen before the first run, not after. |
| **Novelty** | CLASSIC as a form; the twenty-row cross-domain set is what no single-domain generator could produce. |

---

## 3. Before the Visit

| field | content |
|---|---|
| **Screen name** | `Before the Visit` (16) |
| **Full name** | How This Branch Stands Before You Visit — eight measures across the business |
| **Archetype** | 2 · Scorecard *(single-subject extension, §0.2)* |
| **The question it answers** | "I am driving to Mbeya this morning. What do I need to know before I walk in?" |
| **Key figures** | Eight rows for one branch, each actual · standard · gap: (1) sales against plan month-to-date; (2) margin rate against the group rate; (3) stock value and cover against policy; (4) dead stock share; (5) cash banked against sales, and days unbanked; (6) debtor days and over-90 value for the branch's own book; (7) shrinkage rate last count and count coverage; (8) staff cost against margin earned. Plus a single strip: **open exceptions at this branch** (count, TZS, oldest) |
| **The comparison** | Every row against the **group rate for comparable branches** and against **this branch's own position at your last visit** — the second is what makes the conversation possible: "last time I was here dead stock was 4%, now it is 11%." |
| **Exception lead** | The worst miss, and beneath it the one thing the branch manager will raise first if you do not — the report should never let the owner be ambushed by a fact his own app already held. |
| **Consolidation level** | One branch. Deliberately not a roll-up: the whole point is a single subject. Selecting the branch is the only control on the screen. |
| **Cadence** | On demand, before every branch visit. In practice weekly for a group of eleven branches. |
| **The decision it triggers** | **Owner / GM:** the agenda for the visit, in writing, before arriving — three questions instead of a tour. And afterwards, one commitment with a date, which becomes a row in *Did It Work*. |
| **Tap-through** | Any row opens its domain report scoped to this branch. **Refuses** to show the branch's league position on the face of the screen — bringing a ranking into the room turns a management conversation into a defence. |
| **Alert condition** | No push. It is opened, not delivered. |
| **Data needed** | All eight measures already exist in the domain packs; the build item is the **join and the scoping**, not the data. ⚠ **Branch-level debtor and cash attribution** where a branch sells on credit but banking is central. ⚠ **A record of the last visit date per branch per executive** — trivial to capture and it is what powers the "since your last visit" column. ⚠ Comparable-branch class (shared with Branch League). |
| **Novelty** | **NOVEL.** Every measure exists; no screen assembles them for the one occasion on which an owner needs all of them at once. |

---

## 4. People Waiting

| field | content |
|---|---|
| **Screen name** | `People Waiting` (14) |
| **Full name** | People Decisions Waiting on You — hires, pay, exits and what each delay costs |
| **Archetype** | 14 · Decision Docket |
| **The question it answers** | "What people decisions are sitting on me, and what is breaking while they sit?" |
| **Key figures** | (1) Queue and consequence — "6 items · 2 posts unfilled 41 days · 1 exit unsigned blocking final dues"; (2) per item: what (hire, replacement, pay rise, promotion, termination, disciplinary outcome, leave over policy), who asked, the annual cost of a yes, and **what breaks if it waits** (a shift running short, a resignation risk, a leaver still holding assets, a statutory deadline on a disciplinary); (3) items above the requester's own authority; (4) your median decision time on people items versus on money items — usually far worse, and the gap is the finding; (5) cost of the vacancies currently open (links to #25) |
| **The comparison** | Each item against the approved establishment and the wage budget (*People vs Plan*), against the pay band for the role ⚠, and against the group's own approval standard. |
| **Exception lead** | Ranked by consequence: an unsigned termination running against a statutory notice deadline outranks a pay review that costs nothing to defer. Never arrival order, and never by salary size. |
| **Consolidation level** | Personal — items addressed to this approver across all companies, with the employing entity tagged (people decisions are entity-bound in a way payments are not). |
| **Cadence** | Glance daily; it is the sibling tile to *Payments Waiting*. |
| **The decision it triggers** | The decision itself — **Owner / GM.** And, over a quarter, the delegation decision the group most needs: replacement hires inside the approved establishment and inside band should never reach the owner at all, and the report should say so with a count. |
| **Tap-through** | Approve / reject / send back, plus the **one deciding fact**: the establishment position, the band, and the cost per year. **Refuses** to display current salaries of unrelated staff, and refuses to show disciplinary evidence on a phone. |
| **Alert condition** | Push when an item carries a statutory deadline inside 5 days, or when a post has been unfilled beyond its cover standard. |
| **Data needed** | Approval queue for people items ⚠ (usually handled entirely by WhatsApp and a signature); ⚠ **pay bands per role**; ⚠ **establishment position linked to each request**; statutory notice and disciplinary deadlines ⚠; the leaver-to-asset-handover link (shared with *Assets Not Found*). |
| **Novelty** | CLASSIC as a docket, and the flagship matrix cell nobody built. |

---

## 5. Customer Count

| field | content |
|---|---|
| **Screen name** | `Customer Count` (14) |
| **Full name** | Which Way the Customer Count Is Going — active buyers, won and lost |
| **Archetype** | 8 · Trend & Trajectory |
| **The question it answers** | "Am I selling more to fewer people, or genuinely reaching more of the market?" |
| **Key figures** | (1) Verdict in words — "Fewer customers, larger baskets — 4th consecutive quarter"; (2) active customers this month (bought at least once) with a 24-month line and normal band; (3) won and lost each month as two small bars beneath the line — **net movement is the story**; (4) average value per active customer, moving in the opposite direction; (5) share of margin from customers who also traded a year ago |
| **The comparison** | The normal band, the same month last year, and — the pairing that carries the meaning — the customer-count line drawn against the revenue line. Revenue rising while count falls is a concentration event dressed as growth. |
| **Exception lead** | The verdict sentence, and only on a run: three consecutive periods where count and revenue move in opposite directions. A single month's dip in count is noise and the screen says so. |
| **Consolidation level** | Group, with related parties counted once (a group's customer count is meaningless without this). Decomposes by branch and channel — a falling group count is usually one branch losing its base. |
| **Cadence** | Monthly; the serious read is quarterly. |
| **The decision it triggers** | **Owner:** whether the business is becoming more fragile while looking healthier — and if so, a deliberate acquisition push rather than another price rise. **GM:** which branch's customer base is eroding, which is a different problem from which branch is missing its sales number. |
| **Tap-through** | The same series split by branch and by customer size band. **Refuses** to include one-off buyers below a stated value threshold (a chaotic counter count is not a customer base) and states the threshold on screen. |
| **Alert condition** | Push once when net customer movement is negative for three consecutive months. |
| **Data needed** | Customer identity on every sale ⚠ — **the build item, and the hard one**: counter sales are frequently anonymous, so the count must either be restricted to identified trade (and say so) or supported by a loyalty/phone-number capture at POS ⚠. Related-party grouping. Duplicate-customer detection ⚠, or the count inflates with every misspelt name. |
| **Novelty** | **NOVEL.** ERPs count invoices and shillings. Almost none count *people*, which is the number that tells an owner whether he has a business or a handful of relationships. |

---

## 6. Product League

| field | content |
|---|---|
| **Screen name** | `Product League` (14) |
| **Full name** | Product League — margin earned per product family against the group rate |
| **Archetype** | 5 · League Table |
| **The question it answers** | "Which of the things I sell actually make me money?" |
| **Key figures** | (1) The spread — "Best family returns 34% margin, worst 6%, group 22%"; (2) ranked families with margin rate, margin shillings, and **share of group margin**; (3) **margin per shilling of stock held** for each — the size-neutraliser, because a 40% margin on stock that turns twice a year loses to 18% on stock that turns twelve times; (4) rank change since last quarter; (5) count of families below the cost of carrying their own stock |
| **The comparison** | The group rate printed as a reference line, each family against its own position a year ago, and the **cost-of-money line** drawn across the margin-per-shilling-of-stock column. Ranking is never on absolute margin, or the largest family wins permanently and the table teaches nothing. |
| **Exception lead** | The biggest rank fall, then any family below the cost-of-carry line — a family that returns less than the money costs is being subsidised by the rest of the range. |
| **Consolidation level** | Group, ranking families; drills to sub-family and to item, and sideways to branch (a family that is strong at the depot and weak at the counter is a pricing decision, not a product one). |
| **Cadence** | Monthly glance, quarterly decision. |
| **The decision it triggers** | **Owner + GM:** where the buying money and the shelf space go next quarter; which family gets a price rise; which gets pushed on incentive. **CFO:** which family's stock ceiling is cut. |
| **Tap-through** | A family opens its items ranked on the same rule, and its margin bridge. **Refuses** to rank on revenue anywhere on the screen, and refuses to mix manufactured and bought-in families in one table unless the cost basis is stated as comparable. |
| **Alert condition** | Push when a family carrying more than 10% of group margin falls more than 3 points in margin rate in a quarter. |
| **Data needed** | Margin at landed cost by item (exists); ⚠ **a stable product family hierarchy with no mid-year re-parenting** — the same requirement the margin bridge already carries, and the reason both fail together if it is not fixed; average stock value by family ⚠ (closing-balance versions are gameable); cost of money. |
| **Novelty** | **NOVEL by absence.** Two hundred reports and not one of them answers the most obvious question a trader asks. |

---

## 7. Lines Not Paying

| field | content |
|---|---|
| **Screen name** | `Lines Not Paying` (16) |
| **Full name** | Products That Do Not Pay Their Way — margin against the cost of carrying them |
| **Archetype** | 6 · Exception Register |
| **The question it answers** | "Which products should I simply stop selling?" |
| **Key figures** | (1) Count and money — "148 lines · TZS 410M of stock tied up · TZS 61M a year of margin, TZS 74M a year to carry"; (2) per line: margin earned in 12 months, cost to carry (money tied up × cost of capital, plus storage, shrinkage and expected markdown), **net contribution**, and stock value that would be released; (3) new this quarter vs still failing vs recovered; (4) how many are single-source or contractual (cannot simply be dropped); (5) shelf and warehouse space they occupy |
| **The comparison** | Each line against the rule: **twelve months of margin must exceed twelve months of carrying cost**. That rule, printed, is what makes this a register and not an opinion. Plus the same count last quarter — a range that is not being pruned grows a tail every year. |
| **Exception lead** | Ranked by net destruction — the line that costs the most more than it earns, not the slowest-moving one. A line that never sells but occupies nothing is cheap; a line that sells steadily at 4% margin on 200 days of stock is expensive. |
| **Consolidation level** | Company (range is a company decision), with branch detail because a line that fails group-wide may pay at one branch — and dropping it there is a different decision. |
| **Cadence** | Quarterly, and before every range review or supplier negotiation. |
| **The decision it triggers** | **Owner + GM:** the delist decision, with a number behind it for the first time. Also the supplier conversation — half of these lines exist because a supplier asked, and the register is the evidence for renegotiating terms rather than dropping them. Must support **accept-with-reason and an expiry date** (a loss-leader is a legitimate answer; a permanent one is not). |
| **Tap-through** | The line's 12-month sales, margin and stock history, and whether it is stocked because a customer contract requires it. **Refuses** to recommend — strategic range decisions (a full range attracts the trade customer who then buys the profitable lines) are not in the data, and a report that pretends otherwise gets ignored the first time it is wrong. |
| **Alert condition** | No push. Quarterly discipline. |
| **Data needed** | Margin and stock by item (exists); ⚠ **carrying cost per shilling of stock** — cost of capital plus storage plus insurance plus historical obsolescence by category (shared with *What Excess Costs*); ⚠ **contractual/range obligations flagged per item**; ⚠ **shelf and storage space per line** for the space column; accept-with-expiry state. |
| **Novelty** | **NOVEL.** *Dead Stock* catches what has stopped moving. This catches what moves perfectly well and still loses money, which is a far larger and entirely invisible category. |

---

## 8. What Credit Costs

| field | content |
|---|---|
| **Screen name** | `What Credit Costs` (17) |
| **Full name** | What Giving Credit Costs Us — margin lost to the days customers take |
| **Archetype** | 4 · Variance Bridge |
| **The question it answers** | "I sell at 22% margin on 60-day terms. What margin do I actually keep once I have funded that customer for 96 days?" |
| **Key figures** | (1) Headline is the erosion — "Invoiced margin TZS 1.42Bn · margin after the cost of credit TZS 1.19Bn · TZS 230M consumed"; (2) the bridge bars: cost of money over agreed terms (the price of doing business), **cost of money over the days beyond terms** (the avoidable part), settlement discounts given for early payment, bad-debt charge, collection effort cost, cost of the disputes we lost; (3) the effective margin rate after credit, against the headline rate; (4) the customer groups where the erosion is worst; (5) the same erosion a year ago |
| **The comparison** | Invoiced margin → margin after credit. And each customer group against a hurdle: **a customer whose after-credit margin is below the group's cost of money is being sold to at a loss and nobody in the business knows it.** |
| **Exception lead** | The customers whose after-credit margin turns negative, named, with the shillings — usually two or three large, slow, "important" accounts that everybody protects. |
| **Consolidation level** | Group, by customer group and by channel. Also by branch, because branches grant the credit and never carry its cost. |
| **Cadence** | Monthly; mandatory before any terms review or any decision to chase volume with credit. |
| **The decision it triggers** | **Owner + CFO:** reprice the slow accounts, shorten terms, or offer a settlement discount that is cheaper than the funding — the report makes that arithmetic explicit for the first time. **GM:** stop rewarding sales staff on invoiced margin when credit days destroy a fifth of it. |
| **Tap-through** | The worst customer group's own erosion bridge. **Refuses** to show the ageing — that is *Debtor Ageing*'s job, and mixing them turns a pricing decision into a collections argument. |
| **Alert condition** | Push when the group's after-credit margin rate falls more than 2 points in a quarter, or when any customer above TZS 100M annual sales turns negative after credit. |
| **Data needed** | Margin by customer (exists); days-to-pay by customer (exists); ⚠ **a maintained cost-of-money rate**, board-set, reviewed quarterly — one number, entered four times a year, and it unlocks five reports across the catalogue; ⚠ **collection effort cost attributable to a customer** (calls, visits, collector time); disputes lost by customer; settlement discounts recorded as such rather than netted into price ⚠. |
| **Novelty** | **NOVEL.** Every trading group knows credit costs something. None of them know how much, per customer, and it routinely exceeds the entire net margin on the slowest quartile of the book. |

---

## 9. Orders by Age

| field | content |
|---|---|
| **Screen name** | `Orders by Age` (13) |
| **Full name** | Customer Orders by Age — value waiting, how long, and what is blocking it |
| **Archetype** | 7 · Ageing Pyramid |
| **The question it answers** | "How much has been ordered and not delivered, and how much of it has waited so long the customer has probably gone elsewhere?" |
| **Key figures** | (1) Total on the order book TZS 1.24Bn · 22% older than 30 days; (2) bands — within promise / 1–14 days late / 15–30 / 30+ — each with value, order count and **margin at risk**; (3) the top 5 customers inside the worst band; (4) what is blocking, as a split across the whole book: no stock, credit hold, awaiting approval, awaiting delivery slot, customer's own hold; (5) orders old enough that the price they carry is now below current cost ⚠ |
| **The comparison** | The same pyramid one month ago as a ghost outline. A book fattening at the old end is a fulfilment failure; fattening at the young end is genuine demand. The two look identical in a single "order book value" figure, which is the only figure most groups have. |
| **Exception lead** | The 30+ band, with the block reason for its largest orders and — the sharp one — **the count of orders held on credit for a customer who has since paid**. Those are self-inflicted and free to release. |
| **Consolidation level** | Group with company and branch breakdown; must roll up, and must exclude orders the customer has cancelled but nobody has closed ⚠ (a stale open-order book is worse than none). |
| **Cadence** | Weekly. |
| **The decision it triggers** | **GM:** clear the block — release the credit hold, split the delivery, source from another branch. **Owner:** an order book aged past 30 days at a growing share is a lost-customer machine, and the decision is a fulfilment intervention, not a sales push. |
| **Tap-through** | A band opens its orders ranked by margin at risk, each with its block and an owner. **Refuses** to show order lines and specifications. |
| **Alert condition** | Push when 30+ value exceeds 10% of the book, or when any single order above TZS 20M passes 21 days blocked. |
| **Data needed** | Open orders with promised dates (shared with *Order to Delivery*); ⚠ **a structured block reason on every unfulfilled order**, maintained rather than entered once; order cancellation discipline ⚠; price validity dates on old orders ⚠. |
| **Novelty** | **NOVEL.** The catalogue ages debtors, stock, payables, WIP and GRNI. It never ages the one pile that represents customers actively waiting for us. |

---

## 10. Open Complaints

| field | content |
|---|---|
| **Screen name** | `Open Complaints` (15) |
| **Full name** | What Customers Complain About — value at risk, by reason and days open |
| **Archetype** | 6 · Exception Register |
| **The question it answers** | "What are customers angry about right now, and which of those complaints will cost me an account?" |
| **Key figures** | (1) Count and money — "34 open · TZS 610M of annual margin sits behind the complaining accounts"; (2) by reason: short delivery, late delivery, damaged goods, wrong item, price dispute, no fiscal receipt, poor service; (3) days open against the resolution standard, worst first; (4) **complaints from accounts in the top 50 by margin** (count, named); (5) new vs repeat vs closed; (6) share of complaints where the same customer has complained about the same thing before |
| **The comparison** | Against the resolution standard (say 5 working days), against the same count last month, and — the diagnostic pairing — **the reason mix against the reason mix in returns and credit notes**. If short delivery leads both, the problem is the warehouse and every other explanation is noise. |
| **Exception lead** | Complaints from high-margin accounts, then repeat complaints. A repeat complaint is the last warning before a customer appears in *Customers Gone Quiet*, by which time the report is an obituary. |
| **Consolidation level** | Group with branch attribution and **cause-owner department** (warehouse / sales / finance / transport) — which is what makes it act rather than merely inform. |
| **Cadence** | Weekly. |
| **The decision it triggers** | **GM:** assign and close, with a deadline. **Owner:** call the top-50 account personally while the complaint is still open — the only moment at which the call is worth anything. **Branch Manager:** the process fix behind the dominant reason. |
| **Tap-through** | The complaint with the customer's value, their history and the owner assigned. **Refuses** to allow resolution by credit note from the executive app — that is a docket item, so the money is authorised and audited. |
| **Alert condition** | Push when a top-50 account complains, immediately; and when any complaint passes twice the resolution standard. |
| **Data needed** | ⚠ **A complaint record at all** — reason code, customer, value at risk, owner, opened, resolved, outcome. This does not exist in most ERPs and complaints live in phone calls; capturing them at the counter and on the route is the build item, and it is small. ⚠ Link from complaint to the invoice or delivery that caused it. ⚠ Resolution standard as policy. |
| **Novelty** | **NOVEL by absence.** The catalogue measures what customers *did* (bought less, paid late, returned goods). Nothing measures what they *said*, which is the only signal that arrives early enough to act on. |

---

# TIER 2

---

## 11. Above Market

| field | content |
|---|---|
| **Screen name** | `Above Market` (12) |
| **Full name** | Lines Priced Above the Market — sales at risk and where we are undercut |
| **Archetype** | 6 · Exception Register |
| **The question it answers** | "Where am I being undercut, and how much trade is standing on those prices?" |
| **Key figures** | (1) Count and money — "41 lines above market · TZS 940M of annual sales exposed"; (2) per line: our price, the observed competing price, the gap in %, our annual sales on that line, the branch where the competitor is; (3) new this month vs still exposed vs corrected; (4) lines where we are **below** market and could raise price — shown as a second, smaller block, because the same data answers both questions and only one of them is being asked; (5) volume already lost on exposed lines (from *Why We Lose*) |
| **The comparison** | Our price against the captured competing price, and the gap against a stated tolerance ("we accept being 5% above on branded lines, 0% on commodities") ⚠. Never against a national average — a Kariakoo price and a Mbeya price are different markets and the screen keeps them apart. |
| **Exception lead** | Ranked by **annual sales exposed**, not by the size of the price gap. Being 18% above market on a line we sell twice a year is trivia; being 4% above on the line that carries the branch is the business. |
| **Consolidation level** | Branch and local market — this report does not roll up meaningfully to group except as a count and an exposed-value total, and it says so. |
| **Cadence** | Monthly; weekly during a price war or after any price rise (pairs with *Did Prices Hold?*). |
| **The decision it triggers** | **Owner:** match, hold and defend on service, or withdraw from the line. **GM:** a branch-level price authority for named lines in named markets. **CFO:** the lines where we are under market and are giving away margin for nothing — usually more valuable than the defensive half. |
| **Tap-through** | The line's price history against the observed competing price, and our volume across it. **Refuses** to name the competitor's staff or reproduce their documents; it holds an observed price, a date, a place and nothing else. |
| **Alert condition** | Push when a line carrying more than TZS 50M of annual sales is observed more than 10% below our price. |
| **Data needed** | ⚠ **Competitor price capture — the whole report.** A one-tap field on the route app and the counter: item, price seen, where, when, by whom. Nobody has this and it costs almost nothing to start; a sales force that visits customers already sees these prices daily. ⚠ **Observation freshness rules** (a price seen 90 days ago is not evidence). ⚠ A tolerance policy by product class. ⚠ Local market definition per branch. |
| **Novelty** | **NOVEL.** "A competitor is undercutting me" is in the top three things that keep a trading owner awake, and the entire catalogue was silent on it. |

---

## 12. Before the Meeting

| field | content |
|---|---|
| **Screen name** | `Before the Meeting` (18) |
| **Full name** | How This Customer Stands Before You Meet Them — value, credit, service and margin |
| **Archetype** | 2 · Scorecard *(single-subject extension)* |
| **The question it answers** | "I am seeing them at eleven. What is the true state of this relationship?" |
| **Key figures** | One customer group, eight rows, each with the agreed standard beside it: (1) sales 12 months and direction; (2) margin rate against the group rate; (3) **margin after cost to serve and after credit**; (4) exposure now against granted limit; (5) days to pay against agreed terms, and promises kept; (6) open disputes and complaints, value and age; (7) returns rate against the group; (8) categories they have stopped buying (breadth loss). Plus one line: **what they are worth if they leave** — margin, and the fixed costs that stay |
| **The comparison** | Against the terms actually agreed with them (printed, with the date agreed) and against the group's own rates. The gap between the terms in the contract and the terms in practice is the single most useful thing on the screen. |
| **Exception lead** | The thing they will raise: an open dispute, a short delivery, an unallocated payment sitting against their name while we chase them for it. An owner walking into a meeting unaware of an unallocated receipt has already lost it. |
| **Consolidation level** | One customer group, related parties consolidated — including any account trading under a different name at a different branch. |
| **Cadence** | On demand, before every customer meeting; automatically before any annual terms review. |
| **The decision it triggers** | **Owner:** the negotiating position — what to concede, what to demand, and whether this account is worth keeping on current terms. Feeds directly into a terms change, a limit change, or a deliberate exit. |
| **Tap-through** | Any row opens its domain report scoped to this customer. **Refuses** to show their invoice ledger; if the meeting is about invoices, the wrong person is going. |
| **Alert condition** | Push on the morning of any diarised meeting with an account carrying an open dispute or an over-limit position ⚠ (requires a calendar join — worth it). |
| **Data needed** | All rows exist across the packs; the build item is the join, the related-party consolidation, and ⚠ **the agreed terms held as data with their agreement date**, which is the row most often missing and most often argued about in the room. |
| **Novelty** | **NOVEL.** The most common executive use of an ERP — preparing for one conversation — has no screen anywhere in the catalogue. |

---

## 13. Space League

| field | content |
|---|---|
| **Screen name** | `Space League` (12) |
| **Full name** | Space League — margin per square metre against rent, by branch |
| **Archetype** | 5 · League Table |
| **The question it answers** | "Am I paying more for this shop than it earns me?" |
| **Key figures** | (1) The spread — "Best earns TZS 41,000 of margin per square metre a month, worst TZS 6,200, group 18,400"; (2) per branch: margin per m², rent per m², **margin-to-rent ratio**, rank change; (3) the break-even ratio drawn as a line (below it, the space costs more than it earns); (4) selling space vs storage space split ⚠ — a branch with 60% of its floor as stockroom is a different problem from one with a bad location; (5) months to the next rent review or lease expiry |
| **The comparison** | Group margin per m² as a reference line, each branch against its own rent, and against the same ratio a year ago — rents rise annually in Dar and margin often does not. |
| **Exception lead** | Branches below the break-even ratio **with a rent review inside 12 months**, because those are the ones where the decision is live. |
| **Consolidation level** | Branch, within comparable formats (a mall counter and a wholesale depot have different economics and are ranked separately). Group total shown as total rent against total margin. |
| **Cadence** | Quarterly; on demand before any lease decision. |
| **The decision it triggers** | **Owner:** renew, renegotiate, shrink, relocate or close — and the timing, because the leverage is 90 days before expiry, not on the day. **GM:** convert storage space to selling space, or move stock out of expensive floor. |
| **Tap-through** | The branch's rent history and its space split. **Refuses** to include branches whose premises are owned by the group without stating an imputed rent — otherwise owned sites always win and the comparison is worthless. |
| **Alert condition** | Push at 120 days before any lease expiry on a branch below the break-even ratio. |
| **Data needed** | ⚠ **Floor area per branch, split selling / storage / office** — nowhere in an ERP and easily measured once; ⚠ **rent and service charge per branch with review and expiry dates** (shared with *Contracts Due*); ⚠ **imputed rent for owned premises**, board-set; margin by branch (exists). |
| **Novelty** | **NOVEL.** Retail thinks in space; the ERP thinks in accounts. This is the single most natural way an owner already reasons about a shop and no report speaks it. |

---

## 14. Growth League

| field | content |
|---|---|
| **Screen name** | `Growth League` (13) |
| **Full name** | Growth League — like-for-like growth per unit against the group rate |
| **Archetype** | 5 · League Table |
| **The question it answers** | "Which parts of this group are actually growing, once I strip out inflation and new openings?" |
| **Key figures** | (1) The spread — "Best +14% like-for-like, worst −9%, group +3%"; (2) ranked units with like-for-like volume growth, reported growth, and the difference (the price and expansion effect); (3) rank change; (4) growth in customer count alongside growth in value — a unit growing on value with a falling count is consolidating, not growing; (5) count of units shrinking in real terms |
| **The comparison** | The group like-for-like rate as a printed line, and each unit against the same rate a year ago. Units open under 13 months are shown, greyed, outside the ranking, with the exclusion printed. |
| **Exception lead** | Units shrinking in real terms while reporting growth in shillings — the ones nobody is worried about and should be. |
| **Consolidation level** | Branch and company, in comparable classes. Rolls up to the group like-for-like figure that feeds *Real Growth*. |
| **Cadence** | Monthly. |
| **The decision it triggers** | **Owner / GM:** where the growth effort goes, and which unit's manager is being credited for inflation. Pairs with *Branch League* — a unit can be highly profitable and shrinking, and the two tables together are the conversation. |
| **Tap-through** | The unit's like-for-like series with its event markers. **Refuses** to rank on reported growth anywhere on the screen. |
| **Alert condition** | No push. Monthly station. |
| **Data needed** | Unit opening dates and a maturity rule ⚠ (shared with *Real Growth*); volume as well as value by unit; national inflation series ⚠; customer count by unit (shared with #5). |
| **Novelty** | CLASSIC in retail; absent from the catalogue, which ranks branches on profit and on cash but never on whether they are getting bigger. |

---

## 15. Related Parties

| field | content |
|---|---|
| **Screen name** | `Related Parties` (15) |
| **Full name** | Trade With Owners, Staff and Their Families — value, terms and margin |
| **Archetype** | 10 · Concentration & Exposure |
| **The question it answers** | "How much of my business is being done with people connected to me or to my staff, and on what terms?" |
| **Key figures** | (1) The exposure — "TZS 1.8Bn of trade with related parties · 14% of purchases, 6% of sales"; (2) the top parties by value, each with the relationship, the value both ways, and **the terms compared to a comparable unrelated party**; (3) margin earned on related-party sales against the group rate; (4) prices paid to related-party suppliers against the same item bought elsewhere; (5) related parties with no declaration on file ⚠; (6) the same share a year ago |
| **The comparison** | Each related party against **an unrelated comparator** — the same product, the same volume, the same period. That comparison is the entire report; a related-party list without it is a directory. Plus the board's stated policy (usually: arm's length or declared and approved). |
| **Exception lead** | The party trading on materially better terms than an unrelated comparator, with the difference in shillings — stated as a fact, without accusation. |
| **Consolidation level** | Group, across all companies — related-party trade routes itself through whichever entity is least observed, which is precisely why the group view is the only honest one. |
| **Cadence** | Quarterly; mandatory before the annual audit and before any TRA transfer-pricing enquiry. |
| **The decision it triggers** | **Owner:** regularise, price at arm's length, or stop. **CFO:** disclose properly before the auditor finds it, which changes it from a finding into a note. Both are cheaper than the alternative. |
| **Tap-through** | The party's full trade history both ways and the comparator used. **Refuses** to publish the relationship type below Owner/CFO access — a staff member's family connection is personal data and a leak here ends the report's life. |
| **Alert condition** | Push when a new supplier or customer is created that matches a declared related party, and when related-party purchase value exceeds a stated share of category spend. |
| **Data needed** | ⚠ **A related-party declaration register** — owners, directors, staff above a grade, and their declared interests, refreshed annually. This is a policy artefact, not a technical one, and it is the build item. ⚠ **A comparator rule** (same item, same volume band, same period) so the comparison is defensible. Matching engine shared with *Shared Bank Details*. |
| **Novelty** | **NOVEL.** Governance covers the covert version (shared bank details, ghost suppliers). Nothing covers the overt version, which is larger, legal, and quietly expensive. |

---

## 16. Same Names Always

| field | content |
|---|---|
| **Screen name** | `Same Names Always` (17) |
| **Full name** | Trade That Always Runs Through the Same Two People — value and share |
| **Archetype** | 10 · Concentration & Exposure |
| **The question it answers** | "Is there a supplier or a customer who only ever deals with one of my people — and who that person also created, prices, approves and credits?" |
| **Key figures** | (1) The exposure — "TZS 640M of trade in the last 12 months passed through a single employee–counterparty pair with no second person involved"; (2) the top pairs: employee role, counterparty, value, and **how many of the five control points that one person touched** (created the record, set the price, approved the order, received the goods, authorised the credit note); (3) pairs where the counterparty trades with nobody else in the group; (4) pairs where the counterparty was created by that same employee; (5) the same exposure a year ago |
| **The comparison** | Against the group's normal — most counterparties touch four or five staff over a year; a pair that touches one is a statistical outlier and the screen shows how far out. Plus the board tolerance (no counterparty above a value threshold served by a single person). |
| **Exception lead** | The pair with the most control points held by one person, weighted by value — not the largest pair. Value alone finds the biggest supplier; control-point concentration finds the arrangement. |
| **Consolidation level** | Group — this pattern hides in the seams between companies and branches, so a branch-level view cannot see it by construction. |
| **Cadence** | Quarterly; on demand before any procurement review. |
| **The decision it triggers** | **Owner:** rotate the relationship, insert a second person at one control point, or order an unannounced verification of the counterparty's premises. The remedy is almost always rotation, and it is cheap; the report exists so the decision is made on a pattern rather than on a rumour. |
| **Tap-through** | The pair's full transaction pattern — control points, values, timings, and any price comparison to other suppliers of the same item. **Refuses** to state a conclusion or use the word fraud anywhere; it reports concentration, and a human decides what it means. |
| **Alert condition** | Push when a newly created counterparty exceeds a value threshold with a single-person pattern inside 90 days. |
| **Data needed** | Actor identity on every step (the ERP has this in its audit trail); ⚠ **the permission-to-business-step map** (shared with *Unsplit Duties*); ⚠ **the expected multi-person baseline**, computed from the group's own history; record-creator identity per party. |
| **Novelty** | **NOVEL.** *Unsplit Duties* shows who **could** act alone. This shows who **has been** acting alone, with whom, and for how much — which is the version that leads to an investigation. |

---

## 17. Open Claims

| field | content |
|---|---|
| **Screen name** | `Open Claims` (11) |
| **Full name** | Claims Against Us by Age — value, stage and what we have set aside |
| **Archetype** | 7 · Ageing Pyramid |
| **The question it answers** | "What are we being sued or assessed for, how bad is it, and have we put anything aside?" |
| **The buckets** | By age since the claim arose, because a claim that has aged past two years is usually either dead or about to be very expensive |
| **Key figures** | (1) "TZS 840M claimed against us · TZS 210M provided · 4 claims older than 2 years"; (2) bands with value and count; (3) by type: TRA assessments, employment cases, supplier and customer disputes, landlord, regulatory; (4) the **worst realistic outcome** against the provision carried — the gap is the headline number; (5) claims we have brought against others and their value; (6) legal cost spent to date against the amount in dispute |
| **The comparison** | Claimed against provided, and the age shape against last quarter. Also, quietly damning: **legal cost spent against amount at stake** — several claims in every group have already cost more to fight than they are worth. |
| **Exception lead** | The claim with the largest unprovided exposure, then any claim with a hearing or a deadline inside 60 days. |
| **Consolidation level** | Per company (claims attach to a legal entity) with a group total, and a separate line for claims against the **owner personally** arising from the business ⚠, which no company's books will ever show. |
| **Cadence** | Quarterly; monthly where a large claim is live. |
| **The decision it triggers** | **Owner:** settle, fight or provide — and the discipline of deciding rather than letting a claim age. **CFO:** raise the provision before the auditor does. The legal-cost-versus-stake column triggers the most common correct decision, which is to settle something that has become a hobby. |
| **Tap-through** | The claim's stage, next date, adviser and the exposure range. **Refuses** to hold legal advice or privileged correspondence — it holds a value, a stage, a date and a provision, and nothing that would be damaging if the phone were lost. |
| **Alert condition** | Push at 30 days before any hearing or statutory response deadline, and when any new claim above a threshold is registered. |
| **Data needed** | ⚠ **A claims register — the entire build item**: claimant, type, amount claimed, best/worst estimate, stage, next date, adviser, costs to date, provision. It lives in a lawyer's file and the CFO's memory. It is one table. ⚠ Personal-exposure flag. ⚠ Provision balances linked to specific claims. |
| **Novelty** | **NOVEL by absence.** A 200-report catalogue for a Tanzanian trading group with a factory and no report on litigation or tax assessments is a serious omission. |

---

## 18. What We Guaranteed

| field | content |
|---|---|
| **Screen name** | `What We Guaranteed` (18) |
| **Full name** | What We Have Guaranteed for Others — exposure, and what would trigger it |
| **Archetype** | 10 · Concentration & Exposure |
| **The question it answers** | "What have I promised to pay if somebody else does not — and how likely is that somebody to fail?" |
| **Key figures** | (1) The exposure — "TZS 1.4Bn guaranteed · TZS 620M of it personally by the owner"; (2) each guarantee: who it is for, the beneficiary, the amount, the trigger, the expiry, and whether the guaranteed party is a group company, a related party or a third party; (3) **cross-guarantees between our own companies** — the amount by which a failure in one company reaches the others; (4) performance bonds and LCs outstanding; (5) assets pledged as security and to whom; (6) exposure a year ago |
| **The comparison** | Against the group's net worth and against free cash — a guarantee is only frightening in proportion to what would remain if it were called. And against a board tolerance on personal guarantees. |
| **Exception lead** | Guarantees for parties **outside the group**, first, and any guarantee where the guaranteed party is in distress (slow payment, covenant breach, claim). Then guarantees with no expiry, which are the ones everybody forgets. |
| **Consolidation level** | Group and owner-personal together. This report deliberately crosses the boundary between the business and the man, because the exposure does. |
| **Cadence** | Quarterly, and before signing anything. |
| **The decision it triggers** | **Owner:** refuse the next one, cap it, put an expiry on it, or get released from an old one that everyone has forgotten — release is usually available and never asked for. **CFO:** disclose the contingent liability properly. |
| **Tap-through** | The guarantee's terms, trigger conditions and the guaranteed party's current standing. **Refuses** to hold the executed document. |
| **Alert condition** | Push when a guaranteed party breaches a covenant, misses a payment, or receives a claim; and at 90 days before any guarantee expiry that could be allowed to lapse. |
| **Data needed** | ⚠ **A guarantee and security register** — beneficiary, principal, amount, trigger, expiry, assets pledged, personal vs corporate. Pure contract data, held nowhere, and the reason owners routinely discover a live 2019 guarantee during a bank negotiation. ⚠ A link from the guaranteed party to any distress signal the group already holds. |
| **Novelty** | **NOVEL.** The catalogue covers what the group owns and owes. It says nothing about what the group has *promised on behalf of someone else*, which is where owner-managed businesses die suddenly rather than slowly. |

---

## 19. Tax Rate Gap

| field | content |
|---|---|
| **Screen name** | `Tax Rate Gap` (12) |
| **Full name** | Why We Pay More Tax Than the Rate — from statutory to actual, by cause |
| **Archetype** | 4 · Variance Bridge |
| **The question it answers** | "The company rate is 30%. Why did we hand over 41% of our profit?" |
| **Key figures** | (1) Headline is the gap — "Effective rate 41%, statutory 30%, gap 11 points, TZS 214M"; (2) bridge bars: expenses disallowed for tax (entertainment, undocumented spend, excess interest), input VAT we could not reclaim, withholding tax suffered and not offset, penalties and interest, losses in one company that cannot relieve profits in another, capital allowances not claimed; (3) each bar's shilling value; (4) the recoverable share — how much of the gap is a process failure rather than a law; (5) the same effective rate last year |
| **The comparison** | Statutory → effective. Each bar is separately compared to last year, because a recurring bar is a system, not an accident. |
| **Exception lead** | The largest **recoverable** bar — the money lost because a document was missing or a claim was not made, not the money lost because the law says so. Typically undocumented expenditure and unreclaimed input VAT, and typically larger than anyone expects. |
| **Consolidation level** | Per company and group. The group view is essential for one bar specifically — losses trapped in one company while another pays tax is a structural cost that no single company's return reveals. |
| **Cadence** | Quarterly; mandatory before the annual return and before any group restructuring discussion. |
| **The decision it triggers** | **CFO:** fix the documentation discipline behind the disallowed bar; claim what was not claimed. **Owner:** the structural decision — whether the group's company structure is costing tax that a different structure would not, which is a decision made once a decade and always too late. |
| **Tap-through** | The largest bar's composition by expense category and company. **Refuses** to show the tax computation itself. |
| **Alert condition** | Push when the effective rate exceeds the statutory rate by more than 8 points for two consecutive quarters. |
| **Data needed** | ⚠ **A tax-treatment attribute on the chart of accounts** (allowable / disallowable / partly allowable) — the build item, and one the CFO can complete in a day; ⚠ **withholding tax suffered, tracked and matched to offsets**; ⚠ **unreclaimed input VAT identified by reason** (no VRN, no original, time-barred); capital allowance claims vs entitlement; losses by company with expiry dates ⚠. |
| **Novelty** | **NOVEL.** Governance covers whether we filed and whether we paid. Nothing covers *why the bill is the size it is*, which is the only tax question an owner actually asks. |

---

## 20. Project Overruns

| field | content |
|---|---|
| **Screen name** | `Project Overruns` (16) |
| **Full name** | Projects Costing More Than Quoted — overrun value and stage reached |
| **Archetype** | 6 · Exception Register |
| **The question it answers** | "Which jobs are going to lose money, and can I still do something about them?" |
| **Key figures** | (1) Count and money — "7 projects over · TZS 184M of overrun · 3 still under way"; (2) per project: contract value, cost to date, **cost to complete estimate**, forecast final margin against quoted margin, % complete; (3) split between projects still running (recoverable) and finished (a lesson); (4) the largest single cause across the portfolio — materials, labour hours, rework, scope added without a variation; (5) **variations done and not billed** ⚠, which is usually where the margin actually went |
| **The comparison** | Against the quoted cost and margin at the time the job was won, and against the project's own position last month — an overrun that grows every month has an estimating problem, not a cost problem. |
| **Exception lead** | Projects still under way and already forecast to finish below cost, ranked by the amount still to spend — that is the only money the owner can still stop. |
| **Consolidation level** | Company, by project and by project manager (role, not name, on the executive screen). |
| **Cadence** | Monthly; weekly on any project above a value threshold. |
| **The decision it triggers** | **Owner / GM:** stop, renegotiate, raise a variation, or accept and finish — and, structurally, whether the estimating rate needs to change before the next tender. **CFO:** provide for the loss now rather than discover it at completion. |
| **Tap-through** | The project's cost build-up against quote, by element. **Refuses** to show the timesheet or the material issue detail. |
| **Alert condition** | Push when a running project's forecast final margin turns negative, or when unbilled variations exceed a threshold. |
| **Data needed** | ⚠ **Cost captured against a project** — materials, labour hours, subcontract, plant — which is the module gap; ⚠ **the quoted cost build-up retained** so there is something to compare against; ⚠ **cost-to-complete estimated and updated monthly**, which is a discipline not a data feed; ⚠ **variations as first-class objects** with agreed/not-agreed and billed/not-billed status. |
| **Novelty** | CLASSIC in contracting; **absent from the catalogue entirely** — the projects module produced zero reports across nine domain packs. |

---

## 21. Retentions Held

| field | content |
|---|---|
| **Screen name** | `Retentions Held` (15) |
| **Full name** | Money Customers Are Holding Back — retention by age and release date |
| **Archetype** | 7 · Ageing Pyramid |
| **The question it answers** | "How much of my completed work is money somebody else is still sitting on?" |
| **Key figures** | (1) "TZS 310M held back · 41% past its release date"; (2) bands by time since the release date fell due — not yet due / 1–90 days / 91–180 / 180+; (3) per band value and customer count; (4) the top 5 holders with the amount and the certificate or condition still outstanding; (5) retentions where the defects period has expired and nobody has asked ⚠ — free money; (6) retentions we in turn hold from subcontractors, as an offset |
| **The comparison** | The same shape three months ago, and each retention against **its own contractual release date** — retention is not late until its condition is met, which is why ageing it by invoice date lies. |
| **Exception lead** | Retentions whose release condition was satisfied more than 90 days ago and which nobody has claimed. In most contracting businesses this is the largest single pot of forgotten cash. |
| **Consolidation level** | Company and project; group total for the cash forecast, into which retention releases should feed as dated receipts. |
| **Cadence** | Monthly. |
| **The decision it triggers** | **CFO:** claim them — with a name and a date per certificate. **Owner:** whether to price retention into future tenders, given how much of it is never recovered. |
| **Tap-through** | The customer's retentions with the outstanding condition per certificate. **Refuses** to show the contract. |
| **Alert condition** | Push when any retention passes 90 days after its release condition was met. |
| **Data needed** | ⚠ **Retention recorded as a distinct balance with its release conditions and dates** — usually buried inside the debtor balance, which is why it is neither chased nor forecast; ⚠ defects-liability period per contract; certificates and their approval status; subcontractor retentions held. |
| **Novelty** | **NOVEL.** Cash the business has already earned, is legally owed, and has silently written off through inattention. |

---

## 22. Safety Incidents

| field | content |
|---|---|
| **Screen name** | `Safety Incidents` (16) |
| **Full name** | Injuries and Near Misses — count, days lost and the causes that repeat |
| **Archetype** | 6 · Exception Register |
| **The question it answers** | "Is anyone getting hurt in my factory, and is it the same thing hurting them?" |
| **Key figures** | (1) Count and consequence — "6 incidents · 2 with lost time · 41 days lost · 1 reportable"; (2) by cause, ranked, with repeat count; (3) near misses reported (a **rising** near-miss count is good news and the screen says so explicitly, or nobody will ever report one again); (4) days since the last lost-time incident, by site; (5) open corrective actions past their due date; (6) statutory notifications made and outstanding ⚠ |
| **The comparison** | Against the same period last year and against the site's own best run. Never against another company's rate — external safety benchmarks are unreliable and invite argument instead of action. |
| **Exception lead** | Any repeat of a cause that has already been through a corrective action — a hazard that injures twice is a management failure, and it is the only line on this screen that should embarrass anyone. |
| **Consolidation level** | Site and shift; group total for the count and days lost. |
| **Cadence** | Monthly; immediate on any lost-time or reportable incident. |
| **The decision it triggers** | **Owner:** spend on the specific guard, the specific training, the specific process — safety spend approved on evidence rather than after an inspector's visit. **GM:** close the overdue corrective actions. And the cold commercial fact the screen should carry: a fatality closes a plant, and this is the cheapest possible insurance against that. |
| **Tap-through** | The cause's incident history and the corrective actions taken. **Refuses** to name the injured person anywhere on an executive screen. |
| **Alert condition** | Push immediately on any lost-time or reportable incident, at any hour. This is one of very few reports in the entire suite that earns an out-of-hours push. |
| **Data needed** | ⚠ **An incident register** — date, site, shift, cause, consequence, days lost, reportable yes/no, corrective action, owner, due date. Almost certainly a paper book today. ⚠ Near-miss capture with no blame attached, or the count will be zero and meaningless. ⚠ Statutory notification requirements and deadlines. |
| **Novelty** | CLASSIC in any manufacturing business; **entirely absent** from a catalogue that covers a group running a factory. |

---

# TIER 3

---

## 23. Plant by Age

| field | content |
|---|---|
| **Screen name** | `Plant by Age` (12) |
| **Full name** | Plant and Vehicles by Age — value by age band and the replacement wave coming |
| **Archetype** | 7 · Ageing Pyramid |
| **The question it answers** | "How old is the machinery this business runs on, and when does the bill for replacing it all arrive at once?" |
| **Key figures** | (1) "TZS 4.1Bn of plant and vehicles · 38% past its expected life"; (2) bands by age against expected life — under half life / half to full / past life / far past; (3) replacement cost of each band at today's prices, not book value ⚠; (4) the **replacement wave**: how much falls due in each of the next five years, as five bars — the shape is the message, and it is usually a spike; (5) assets past life still carrying the most output (from *Machine League*) |
| **The comparison** | Age against **expected useful life per asset class**, not against depreciation policy — the two differ, and depreciation is an accounting convention that says nothing about whether a machine will run next year. Plus the wave against the group's normal annual capex spend. |
| **Exception lead** | The year in which the replacement wave peaks, stated plainly: "TZS 1.6Bn of plant falls due for replacement in 2028 — four times a normal year's capex." |
| **Consolidation level** | Group, by asset class and site. |
| **Cadence** | Half-yearly; and whenever a capex plan is being built. |
| **The decision it triggers** | **Owner + CFO:** smooth the wave — bring one replacement forward, defer another, or start a sinking fund now. This is the report that turns a surprise into a plan, and it is worth several years of notice. |
| **Tap-through** | The band's assets with age, output carried and replacement cost. **Refuses** to show net book value as the headline — book value is the one number that is guaranteed not to reflect what replacement will cost. |
| **Alert condition** | No push. Read on a rhythm. |
| **Data needed** | Asset register with acquisition dates (exists); ⚠ **expected useful life per asset class**, set by the engineer not the accountant; ⚠ **current replacement cost per asset**, refreshed annually — a single estimate per class scaled by capacity is enough and is far better than nothing. |
| **Novelty** | **NOVEL.** Every group is quietly accumulating a replacement wave and discovers it in the year it lands. |

---

## 24. Back in Service

| field | content |
|---|---|
| **Screen name** | `Back in Service` (15) |
| **Full name** | How Long From Breakdown to Running — days down and what each day cost |
| **Archetype** | 12 · Cycle-Time & Flow |
| **The question it answers** | "When something breaks, how long is it actually off, and which part of the wait is my own fault?" |
| **Key figures** | (1) Median days from breakdown to running, and the 90th percentile; (2) the stages as one segmented bar: reported → diagnosed → **quote obtained → approved** → part ordered → part arrived → repaired → back in service; (3) the stage exceeding its standard, with the count of assets sitting in it now; (4) output or route sales lost during downtime this quarter, in TZS; (5) the share of total downtime spent **waiting for our own approval** — usually the largest stage and always the cheapest to fix |
| **The comparison** | Against the agreed service standard per stage, and against the same measure a quarter ago. Median and 90th percentile together — the average hides the vehicle that has been off the road for five weeks. |
| **Exception lead** | The stage over standard, named with its owner, and the assets stuck in it today with their daily cost. |
| **Consolidation level** | Fleet and plant, by site; group median for standard-setting. |
| **Cadence** | Monthly; weekly during a period of heavy breakdowns. |
| **The decision it triggers** | **Owner:** raise the delegated repair limit, or hold critical spares — the report prices both options against the downtime they would remove. **GM:** change the workshop or the parts supplier whose stage is the bottleneck. |
| **Tap-through** | The assets currently down, with stage, days and daily cost. **Refuses** to report only on completed repairs — the asset that has been down for four months is the whole point and it never appears in a completed-items average. |
| **Alert condition** | Push when any asset carrying more than a threshold of daily output has been down more than 5 days, or when the approval stage exceeds 48 hours. |
| **Data needed** | ⚠ **Stage timestamps on a breakdown** — reported, diagnosed, quoted, approved, part ordered, part received, returned to service. Most groups record only the repair invoice. ⚠ Daily output or route value per asset (shared with *Machine League*, *Vehicle League*). ⚠ Service standards per stage. |
| **Novelty** | **NOVEL.** *Repair or Replace* prices the repairs. Nothing prices the waiting, and the waiting is usually longer and more expensive than the repair. |

---

## 25. Empty Seats

| field | content |
|---|---|
| **Screen name** | `Empty Seats` (11) |
| **Full name** | How Long a Job Stays Empty — days open and what the gap costs |
| **Archetype** | 12 · Cycle-Time & Flow |
| **The question it answers** | "How long do we take to fill a post, and what is the empty chair costing while we take it?" |
| **Key figures** | (1) Median days from vacancy to start, and the 90th percentile; (2) stages: vacancy arises → approved to fill → advertised → shortlisted → offered → accepted → started; (3) posts open now, count and days, with the longest named by role; (4) **cost of the gap** — overtime paid to cover, lost sales at an unstaffed counter, contract labour premium; (5) the stage over standard, with the count stuck in it |
| **The comparison** | Against the agreed fill standard by grade ⚠ and against the same measure last year. The approval stage is compared separately, because in most groups it is longer than the recruitment itself. |
| **Exception lead** | Posts open beyond twice the standard, and — the finding that usually lands — the share of the total elapsed time spent waiting for the owner to approve the replacement of a role that was already in the establishment. |
| **Consolidation level** | Company and function; group median. |
| **Cadence** | Monthly. |
| **The decision it triggers** | **Owner:** delegate replacement hiring inside the approved establishment, which removes the largest stage at a stroke. **GM:** change the recruitment channel for roles that consistently take longest. |
| **Tap-through** | The posts open now with their stage and cover arrangement. **Refuses** to name candidates. |
| **Alert condition** | Push when a post covering a minimum-cover role passes its standard, or when overtime attributable to vacancies exceeds a threshold. |
| **Data needed** | ⚠ **Vacancy records with stage timestamps** — vacancy raised, approved, advertised, offered, started; ⚠ **fill standard by grade**; ⚠ overtime and contract-labour cost attributable to a specific vacancy (needs the absence-to-cover link shared with *Overtime Causes*). |
| **Novelty** | CLASSIC in HR, absent from the catalogue, and the version that matters is the cost of the gap rather than the days. |

---

## 26. Did It Work

| field | content |
|---|---|
| **Screen name** | `Did It Work` (11) |
| **Full name** | What Our Decisions Changed — the action taken and what happened after |
| **Archetype** | 13 · Reconciliation & Assurance |
| **The question it answers** | "Last quarter I made eleven decisions off these screens. Did any of them actually change the number?" |
| **Key figures** | (1) The verdict — "11 decisions · 4 moved the number · 3 did not · 4 too early to say"; (2) per decision: what was decided, from which screen, by whom, the measure it was meant to move, its value then and now; (3) the shillings the successful decisions are worth, annualised; (4) decisions taken and **never implemented** — count and the measure that consequently did not move; (5) median days from decision to first visible effect |
| **The comparison** | The measure at the moment of the decision against the measure now, with the group's normal drift subtracted — a measure that improved by less than it normally wanders is not evidence of anything, and the screen says so rather than claiming a win. |
| **Exception lead** | Decisions never implemented, first. A decision that was made and then quietly not done is worse than a decision that failed, because nobody learned anything from it. |
| **Consolidation level** | Group, by decision-maker role and by source screen — **which is the second finding: which reports actually produce decisions that work.** A screen whose decisions never move anything should be retired (see #27). |
| **Cadence** | Quarterly. |
| **The decision it triggers** | **Owner:** whether management action in this group has any effect at all — and, more usefully, which *kind* of action does. Over four quarters this becomes the most valuable screen in the suite: it teaches the group which levers are real. |
| **Tap-through** | The decision's measure plotted before and after with the decision date marked. **Refuses** to grade individuals and refuses to claim causation where the measure moved less than its normal variation. |
| **Alert condition** | Push once, at the review date set when the decision was recorded — that reminder is half the report's value. |
| **Data needed** | ⚠ **A decision log — the entire build item, and a small one.** When an owner acts on a screen, capture: what, why, which measure, target value, review date, owner. Three fields and a date. Nothing else in this catalogue makes the suite compound; without it the app produces insight and no memory. ⚠ Normal drift per measure (shared with *Since You Looked*). |
| **Novelty** | **NOVEL.** Two hundred reports tell an owner what is happening. This is the only one that tells him whether he is any good at responding to it. |

---

## 27. Never Opened

| field | content |
|---|---|
| **Screen name** | `Never Opened` (12) |
| **Full name** | What Nobody Opens — the screens to retire before the suite rots |
| **Archetype** | 6 · Exception Register |
| **The question it answers** | "Which of these hundred screens is anyone actually using, and what am I paying to keep the rest alive?" |
| **Key figures** | (1) Count and cost — "38 screens opened fewer than 3 times in 90 days · TZS 41M a year of data-capture effort behind them"; (2) per screen: opens in 90 days, distinct users, last opened, decisions recorded from it (from #26); (3) screens whose **alerts** fire and are never opened — a push that nobody reads is training people to ignore all pushes; (4) exception registers with more than 100 open items — the classic death, where a rule is too loose and the register has been abandoned; (5) screens whose underlying data has stopped being captured ⚠ |
| **The comparison** | Against a usage floor agreed when the suite shipped (e.g. a Tier 1 screen opened weekly by at least two executives), and against the same screen's usage a quarter ago. |
| **Exception lead** | Screens that fire alerts nobody opens — those are actively harmful, not merely unused. Then registers whose open-item count proves the rule is wrong. |
| **Consolidation level** | The suite itself. Not a business report; a report about the reports. |
| **Cadence** | Half-yearly. |
| **The decision it triggers** | **Owner + CFO:** retire the screen, or fix the rule behind it, or stop the data capture that feeds it — every ⚠ build item in this catalogue has an ongoing cost in somebody's day, and a screen nobody opens is that cost with no return. Also the honest inverse: a screen that is heavily used but produces no decisions in #26 is entertainment. |
| **Tap-through** | The screen's usage and decision history. **Refuses** to rank executives by how much they use the app — that measures compliance, not management. |
| **Alert condition** | No push. |
| **Data needed** | ⚠ **Per-screen usage telemetry** with user role and timestamp; alert delivery and open rates ⚠; open-item counts per register; the decision log from #26; ⚠ **an estimate of the ongoing capture cost per build item**, which nobody normally computes and which is what makes the retirement decision real. |
| **Novelty** | **NOVEL.** Every executive reporting suite decays into forty screens and six users. This is the only defence, and it costs one table. |

---

# Corrections to reports already generated

Ordered by severity. Several are R9 twin failures that will surface as duplicate tiles the first time the catalogue is assembled into one navigation.

**C1 — `Profit to Cash` (Money 7) and `Profit vs Cash` (Cash 8) are the same report.** Same question, same figures, near-identical names, different archetypes (Reconciliation vs Variance Bridge). This is the sharpest R9 failure in the catalogue and it will ship as two tiles that both promise to explain where the profit went. **Keep one: `Profit vs Cash`, archetype Variance Bridge**, in the Cash domain, with the persistence/ageing figure from Money 7 folded in as a body row. Delete Money 7 and link to it from *The Profit Ladder*.

**C2 — `What Waiting Cost Us` exists three times** (Cash 25, Credit 25, Buy-Make 25 as `Late Approvals`) with the same name and the same archetype. There is one report here, not three: **one group-wide `What Waiting Cost Us`, filterable by decision type.** The cost categories from all three merge; the domain packs keep a link, not a copy.

**C3 — `Money Tied Up` exists twice** (Credit 24, Cycle-Time; Strategy 13, Position & Movement). Same name, different archetypes, different questions. **Keep `Money Tied Up` for the Credit cycle-time report** (it is the better fit — the name promises duration) and rename Strategy 13 to **`Where the Money Sits`**, which is its actual Position & Movement promise and matches the family template.

**C4 — Three registers chase the same customer through three stages.** `Slipping Payers` (Credit 3, paying slower), `Shrinking Baskets` (Sales 19, buying less), `Customers Gone Quiet` (Sales 9, stopped). These are early, middle and late symptoms of one event. Three tiles, three alert streams, and the same account appearing on all three in successive months. **Consolidate into one register — `Customers Slipping Away`** — with a stage column (narrowing / slowing / silent) and a single ranking on margin at risk. Each domain keeps a scoped view; the group tile is one.

**C5 — `Cost to Serve` (Money 17) and `Costly Customers` (Sales 22) are the same report** with different archetypes (League vs Exception). **Keep the League Table** — the ranking and the revenue-rank-versus-profit-rank inversion is the finding — and demote the Exception version to its alert condition ("an account has turned negative"). Then merge #8 *What Credit Costs* into it as a cost component, so one screen finally shows true customer profit: margin − serving − credit − returns.

**C6 — `Cash to 30 Days` (Cash 5) violates R8** — it embeds a horizon in the name, which is exactly what R8 forbids, and the doctrine's own worked example introduced it. When treasury moves to a 45-day view the name rots. **Rename to `Cash Runs Out`** (13 chars, 3 words); the horizon lives in the subtitle. Note the doctrine document should be corrected too, since it prints this name as a model answer.

**C7 — Four daily Flash reports compete for the same moment**: `Today's Trade` (Sales), `Today's Profit` (Money), `Today's Cash` (Cash), `Today on the Floor` (Buy-Make), plus `Month So Far` (Strategy). An owner has one morning glance. **Only one Flash may hold a home tile.** Recommendation: `Today's Trade` becomes the group daily flash with a margin line and a cash-banked line folded in (absorbing `Today's Profit`), `Today's Cash` and `Today on the Floor` live one tap down as the treasury and factory strips, and `Month So Far` takes the second tile because it answers a different question (pace, not normality).

**C8 — `Received Not Billed` (Stock 25) and `Goods Not Billed` (Credit 22) are the same balance**, one aged, one reconciled. **Keep the Reconciliation** (Credit 22) — it isolates the unexplained portion, which the Ageing version cannot — and make the age bands a body row inside it. Delete Stock 25.

**C9 — `Goods in Transit` (Stock 14) is labelled Ageing Pyramid and is actually an Exception Register.** Its rule is a route's normal transit time; its buckets are breach severities, not ages; its lead item is a suspected loss. The Cash author explicitly resisted this same temptation and said so. **Reclassify to Exception Register**, keep ageing as a field.

**C10 — `Stock Cover` (Stock 3) and `Runs Out First` (Stock 5) and `Line Stoppers` (Buy-Make 9) share one lead-time engine** and two of them will disagree in front of the owner. Not a rename — a **shared-definition mandate**: one measured-lead-time service (order-to-receipt median plus variability, with customs as a separate leg), consumed by all three. Same for `Break-even Day` (Money 18) and `Break-Even League` (People-Assets 25), which must share one fixed/variable classification or produce two different break-even dates for the same branch.

**C11 — `The Seven Numbers` (Strategy 1) and `Profit Against Plan` (Money 3, "the seven money measures") echo each other badly.** Two seven-row scorecards, one of which is a subset of the other. **Rename Money 3 to `Profit Against Plan` with six rows explicitly declared as the expansion of the Seven Numbers' profit row**, and cross-link. Better still, make it the tap-through target of that row and stop calling it a seven.

**C12 — Weak or absent decisions.** Three reports fail test 5 as written. `Wage Share` (People 6) triggers "whether to intervene" — that is the Trend archetype's generic non-decision; attach the specific act (a hiring freeze threshold) or demote it to a tap-through of *Wage Bill Gap*. `Capital Employed` (People-Assets 12) triggers "sell or redeploy an asset class", which no owner can act on; it needs the *Capital League* ranking on the same screen or it is a fact. `Sales Mix Shift` (Sales 21) triggers "whether the drift was a decision" — sharpen to a credit-authority reset with a named threshold.

**C13 — Missing comparisons.** `Money in Stock` (Stock 1) compares only to its own opening balance; a stock value with no cover or sales comparison cannot be judged and the report fails test 2. Add stock-to-monthly-sales as the carried comparison. `Committed Cash` (Cash 4) compares commitments to receipts but not to the **same commitment level a year ago at the same point in the trading cycle** — without it, a normal pre-Christmas build reads as over-commitment every November.

**C14 — Ageing bases are inconsistent across the catalogue and will not tie.** `Debtor Ageing` ages by due date (correct). `Supplier Bills by Age` does not state its basis. `Received Not Billed` ages by receipt date. `Stock by Age` ages by FIFO layer (correct). **Mandate one rule per pile, printed on the trust line of every ageing report**, and state it in the doctrine: age by the date the obligation became actionable, never by document date.

**C15 — Alerts filed as reports.** `Tills Gone Dark` (Gov 5), `Supplier Bank Changes` (Gov 6) and `Unapproved Moves` (Strategy 15) are alert-first: their value is entirely in the real-time push and the register is the audit trail behind it. Reclassify as **alert-with-register**, and keep all three off the home tray — an owner should meet them only when they fire. Conversely `Instant Approvals` (Gov 7) and `Breach Rate` (Gov 25) correctly refuse to push and are correctly reports.

**C16 — One report is misplaced by domain.** `Payroll Taxes Due` (People 10) is a governance/tax exception, not a people report, and it duplicates part of `Tax Falling Due` (Gov 18). Move it to Governance and make it the payroll rows of that forecast, or the owner will meet the same PAYE liability on two screens with two different numbers.

---

# Packs, not reports

The brief asks whether the day-one screen, the weekly digest and the month-end pack are first-class reports. **Under the governing doctrine they are not, and pretending otherwise would ship three dashboards.** A dashboard is a tray. They are still deliverables and they still need designing, so here they are as **packs** — ordered sequences of existing reports with a stated purpose and a stated omission.

**The home tray — six tiles, no more.**
`Today's Trade` · `Since You Looked` · `Waiting on You` (the merged docket across money, orders, people, stock, capital) · `Cash Runs Out` · `Broken Rules` · one **rotating slot** that holds `Where Month Lands` from the 18th, `Profit vs Cash` for three days after each close, and `The Twenty Numbers` in the first week of a quarter. Everything else is one tap behind a domain. The rotating slot is the only concession to the fact that an owner's question changes with the calendar, and it must never hold more than one thing.

**The weekly digest — one push, Monday 07:00, five items.** Group pace against plan; the single largest new exception by value; the biggest rank fall in any league; the docket's queue value and oldest item; one line of trust ("the last three days are provisional"). It is a *delivery of existing reports*, and its only original content is the selection rule: **the five things that moved most against their own normal**, which is the same engine as `Since You Looked`. Build the engine once.

**The month-end pack — ten screens in a fixed order, exported as one PDF.** `What Is Final` first, always — the pack opens by saying how much of it can be trusted. Then `The Seven Numbers`, `Plan Gap`, `Profit vs Cash`, `The Month-End Swing`, `Company League`, `Where the Money Sits`, `Debtor Ageing`, `Broken Rules`, `Did It Work`. Fixed order, fixed contents, no additions — a board pack that changes shape every month cannot be compared month to month, which is the only reason to have one. The single new build item is **export with the as-of and trust line stamped on every page**, so a screenshot cannot escape into a bank meeting without its provenance.

**The board and bank pack** is the month-end pack plus `Bank Covenants`, `What We Guaranteed`, `Open Claims` and `Tax Exposure` — the four things a lender or a board will ask about that a management pack never contains.

---

# The new build items this pack creates

Beyond the ⚠ items listed per report, five are new to the catalogue and each unlocks several reports:

| Build item | Unlocks |
|---|---|
| **A decision log** — what was decided, from which screen, which measure, target, review date, owner | 26, and it is what makes the entire suite compound |
| **Per-user view state** — which figures this user saw, at what value, when | 1, and the "the number you saw has moved" alert in *What Is Final* |
| **Competitor price capture** — one tap on the route app and the counter | 11, and the strongest bar in *Why We Lose* |
| **Three registers that live in filing cabinets**: claims, guarantees and security, related-party declarations | 15, 17, 18 |
| **Carrying cost per shilling of stock, and a maintained cost-of-money rate** | 7, 8, 13, and *What Excess Costs*, *Money Tied Up*, *Capital League* — six reports resting on two numbers entered quarterly |