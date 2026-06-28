# Company Scenario — Tembo Group Ltd

> **Status:** Canonical simulation world bible. Single source of truth for every
> simulated persona, branch, party, product, and role that exercises this ERP.
> Read this before role-playing any persona or seeding any scenario.
>
> *Kila tembo na mzigo wake* — "Every elephant carries its own load."

---

## 1. Company overview

**Tembo Group Ltd** is a countrywide Tanzanian enterprise headquartered in Dar es
Salaam. It is a diversified group that both **imports and distributes** fast-moving
consumer goods, hardware and electronics (its **Trading division**) and
**manufactures** edible oil, soap & detergent, bottled water, maize flour and
furniture from raw materials it procures locally and abroad (its **Manufacturing
division**). The group sells nationally through nine branches to wholesalers,
supermarkets, institutional buyers, hotels, government tenders and credit-account
retailers.

| Attribute | Value |
|---|---|
| Legal name | Tembo Group Ltd |
| Tagline | *Kila tembo na mzigo wake* ("Every elephant carries its own load") |
| Country | Tanzania |
| Reporting currency | TZS (Tanzanian Shilling) |
| VAT | 18% standard rate, administered by the **TRA**; sales issue **EFD** fiscal receipts |
| TIN / VRN | Registered taxpayer; VAT-registered (VRN issued) |
| Head office | Nyerere Road, Dar es Salaam |
| Divisions | Trading division; Manufacturing division |
| Financial year | 1 July – 30 June (aligns with Tanzanian government fiscal year) |

### Divisions & activities

**Trading division** — imports containers of FMCG, hardware and electronics through
Dar es Salaam port, clears them, warehouses them at the main Dar warehouse, and
distributes nationwide. It runs wholesale counter sales, credit-account sales to
retailers, route/van sales to up-country dukas, and supplies institutional and
tender customers.

**Manufacturing division** — operates the Dar es Salaam factory. It procures raw
materials (crude palm oil, caustic soda, maize grain, PET resin/preforms, timber)
and converts them through work orders into finished goods: cooking oil, bar &
powder soap, bottled drinking water, maize flour (sembe), and wooden furniture.
Finished goods feed the same distribution network as the traded goods.

Activities in scope across the group: **importing**, **wholesale & retail
distribution**, **manufacturing/processing**, **route/van sales**, **institutional
& tender supply**, **warehousing & inter-branch transfer**, **after-sales service
on electronics**, and **fleet/asset management** for delivery vehicles and factory
plant.

---

## 2. Branch network

HQ, main warehouse and the factory are co-located in Dar es Salaam; the other eight
branches are regional sales-and-distribution depots, each with a small store and a
counter, some doubling as up-country redistribution hubs.

| Branch | Region | Role |
|---|---|---|
| Dar es Salaam HQ | Dar es Salaam | Group head office: finance, procurement, IT, main import warehouse **and** the manufacturing factory |
| Arusha | Arusha | Northern-zone distribution hub (Arusha, Kilimanjaro, Manyara) |
| Mwanza | Mwanza | Lake-zone distribution hub (Mwanza, Shinyanga, Geita) |
| Dodoma | Dodoma | Central-zone branch serving the capital, government & institutional buyers |
| Mbeya | Mbeya | Southern-highlands hub (Mbeya, Songwe, Njombe), border trade to Zambia/Malawi |
| Mtwara | Mtwara | South-coast branch (Mtwara, Lindi), port & cashew-belt trade |
| Zanzibar | Zanzibar (Unguja) | Island branch — note separate VAT/customs regime; tourism & hotel supply |
| Morogoro | Morogoro | Eastern-corridor branch on the central line; agri & transit trade |
| Tanga | Tanga | North-coast branch (Tanga, Pwani-north); sisal belt & cross-border to Kenya |

---

## 3. Organisation chart (text)

```
                         Board of Directors
                                 |
                  Bakari Mbaga — Group General Manager
                                 |
   ----------------------------------------------------------------------
   |              |              |            |             |            |
 Grace          (Sales)      (Procure-    (Production)    (HR/        (Branch
 Mhina —       Daudi          ment)        Editha         Payroll)    Managers)
 Finance       Kessy —      Rehema         Mhagama —      Neema        |
 Director      Group        Salum —        Production     Kileo —    --+------------------
 (CFO)         Sales        Procurement    Manager        HR &         |        |        |
   |           Manager      Manager           |           Payroll   Halima   Emanuel   (other
   |              |            |              |            Manager   Juma —   Mushi —    regional
 --+------       |            |          ---+---             |       Branch   Branch     managers)
 |       |    ---+---      ---+---       |       |        ---+---    Mgr      Mgr
Amina   John   |     |     |     |    Frank   (prod.   |      |     Dar      Arusha
Mwanga  Komba  Sabina Hamisi Yusuf  Materu   clerks)  (payroll  store      counter
Account- Cashier Aloyce Ngassa M031...  Stores         officer)  & sales)   & sales
ant     /Cash-  Sales- Field   Procure- Super-
        Bank    person  Sales   ment      visor
        Officer        Agent   Officer      |
                                         Saidi
                                         Karume —
                                         Storekeeper
```

Reporting lines in prose:

- **Bakari Mbaga (Group GM)** reports to the Board; everyone else rolls up to him.
- **Grace Mhina (Finance Director / CFO)** owns finance: the accountant, cashier
  and (dotted-line) credit control report into finance.
- **Daudi Kessy (Group Sales Manager)** owns the sales force across branches.
- **Rehema Salum (Procurement Manager)** owns sourcing for both divisions.
- **Editha Mhagama (Production Manager)** owns the factory and its supervisors/clerks.
- **Neema Kileo (HR & Payroll Manager)** owns people, payroll and leave.
- **Branch Managers** (Halima Juma – Dar, Emanuel Mushi – Arusha, plus regional
  peers) run their depots and report to the GM with a dotted line to Sales and Finance.

---

## 4. ERP roles (permission bundles)

Roles are created in the ERP's web UI as bundles of seeded permission codes. The
table below describes each role by the **modules / capabilities** it grants. A
persona is granted exactly one `erpRole`; root/group-admin is reserved for IT and
is out of scope for this business cast.

| Key | Name | Scope | Capabilities (modules / actions) |
|---|---|---|---|
| `GROUP_GM` | Group General Manager | All companies & branches | Read-all across every module; dashboards & BI; approve high-value sales/purchase docs above manager thresholds; final approval authority; no routine data entry |
| `FINANCE_DIRECTOR` | Finance Director | All branches | Full GL, AR, AP, cash & bank, tax, fixed assets, budgeting, FX, costing, reporting/BI; approve payments & journals; period close; approve credit limits |
| `BRANCH_MANAGER` | Branch Manager | Home branch (+ assigned) | Read/approve sales & purchase docs for the branch; stock view & transfers; branch dashboards & reports; approve within branch thresholds; manage branch parties |
| `ACCOUNTANT` | Accountant | Assigned branches | GL postings & journals, AR & AP invoicing/credit notes, bank reconciliation support, tax (VAT return prep, EFD), period-end tasks, financial reports |
| `CASHIER` | Cashier / Cash & Bank Officer | Home branch | Cash & bank module: receipts, payments, petty cash, cash counts, deposits; record customer payments against AR; daily cash reconciliation |
| `SALES_OFFICER` | Sales Officer | Home branch | Sales module: quotations, sales orders, sales invoices, delivery notes, customer (party) creation, AR view; POS/counter sales; EFD receipt issue |
| `FIELD_SALES_AGENT` | Field / Route Sales Agent | Home branch | Van/route sales orders & invoices, capture orders from dukas, record cash collections, view own customers & stock on the van; assigned as agent on orders |
| `PROCUREMENT_OFFICER` | Procurement Officer | Assigned branches | Purchases module: requisitions, RFQs, purchase orders, supplier (party) management, goods-receipt initiation, purchase returns |
| `STOREKEEPER` | Storekeeper / Stock Controller | Home branch store | Stock module: goods receipt, stock issues, stock counts, locations & batches, inter-branch transfer receipts, stock adjustments (within limits) |
| `STORES_SUPERVISOR` | Stores / Warehouse Supervisor | Home warehouse | All storekeeper capabilities + approve stock adjustments & transfers, manage stock locations, oversee counts, valuation view |
| `PRODUCTION_OFFICER` | Production Officer | Factory branch | Manufacturing module: work orders, BOM consumption, finished-goods receipt to stock, production reporting; costing view |
| `HR_PAYROLL_OFFICER` | HR / Payroll Officer | All branches | HR module: employees, contracts, leave, attendance, payroll runs, payslips; statutory deductions (PAYE, NSSF, WCF, SDL) |

---

## 5. Product catalog

### 5.1 Sourced (traded / imported — bought to resell)

| Product | Category | Unit |
|---|---|---|
| Cement (Portland 50 kg) | Hardware / Building | bag |
| Corrugated iron sheet (gauge 28) | Hardware / Roofing | sheet |
| Steel reinforcement bar (12 mm) | Hardware / Building | bar |
| LED television 32" | Electronics | unit |
| Solar home lighting kit | Electronics | kit |
| Mobile phone (entry smartphone) | Electronics | unit |
| Wheat flour (imported, 25 kg) | FMCG / Food | bag |
| Sugar (50 kg) | FMCG / Food | bag |
| Rice (imported, 25 kg) | FMCG / Food | bag |
| Cooking gas cylinder (6 kg LPG) | FMCG / Energy | cylinder |
| Soft drink crate (24 × 300 ml) | FMCG / Beverage | crate |
| Laundry bucket (assorted plastics) | FMCG / Household | unit |

### 5.2 Manufactured (made in the Dar factory from raw materials)

| Product | Category | Unit | Raw materials |
|---|---|---|---|
| Tembo Cooking Oil (1 L bottle) | Edible Oil | bottle | Crude palm oil; PET bottle (1 L); bottle cap; printed label |
| Tembo Bar Soap (800 g) | Soap & Detergent | bar | Caustic soda; crude palm oil; soap fragrance; soap wrapper |
| Tembo Washing Powder (1 kg) | Soap & Detergent | packet | Sodium sulphate; surfactant (LABSA); detergent perfume; printed packet |
| Tembo Drinking Water (500 ml) | Bottled Water | bottle | Treated water; PET preform; bottle cap; shrink-wrap film |
| Tembo Maize Flour / Sembe (25 kg) | Milled Grain | bag | Maize grain; woven polypropylene sack; printed label |
| Tembo Office Desk (1.2 m) | Furniture | unit | Sawn timber; plywood board; wood glue; varnish; metal fittings |

---

## 6. Customers & suppliers

### 6.1 Customers (external parties Tembo sells to)

| Name | Kind | Region |
|---|---|---|
| Joseph Ulimboka | Credit-account retailer (duka owner) | Mwanza |
| Kariakoo Wholesale Mart | Wholesaler | Dar es Salaam |
| Mlimani Supermarket Ltd | Supermarket chain | Dar es Salaam |
| Serengeti Lodges Ltd | Hospitality / hotel group | Arusha |
| Mbeya District Council | Government / institutional (tender) | Mbeya |
| Zanzibar Beach Resorts Ltd | Hospitality (island) | Zanzibar |
| Dodoma Cash & Carry | Wholesaler | Dodoma |
| Tanga Fresh Distributors | Distributor / re-seller | Tanga |
| Morogoro Mini-Markets Assoc. | Buying group (multiple dukas) | Morogoro |

### 6.2 Suppliers (external parties Tembo buys from)

| Name | Kind | Supplies |
|---|---|---|
| Mbasha Holdings Ltd | Importer / wholesaler | Raw materials (crude palm oil, caustic soda) **and** traded goods (electronics, FMCG) |
| Bidco Africa (TZ) Ltd | Manufacturer | Crude palm oil & edible-oil inputs |
| Twiga Cement PLC | Manufacturer | Portland cement & building materials |
| Mwananchi Maize Traders | Agricultural trader | Maize grain (raw material for the mill) |
| PET-Pak Tanzania Ltd | Packaging manufacturer | PET preforms, bottles, caps, labels, shrink-wrap |
| Coastal Chemicals Ltd | Chemical importer | Caustic soda, LABSA, sodium sulphate, fragrances |
| Sao Hill Timber Suppliers | Timber merchant | Sawn timber, plywood, varnish (furniture inputs) |
| Shenzhen Electro Import Co. | Overseas exporter | Electronics (TVs, phones, solar kits) imported via Dar port |

---

## 7. Persona roster

The full cast: **16 STAFF** who log in and operate the ERP, plus the **2 EXTERNAL**
parties who do not log in but whose business is entered (and whose complaints are
translated into problem reports) by named staff.

| # | Full name | Designation | Level | Kind | Home branch | Username | ERP role |
|---|---|---|---|---|---|---|---|
| 1 | Bakari Mbaga | Group General Manager | senior-management | STAFF | Dar es Salaam HQ | bmbaga | GROUP_GM |
| 2 | Grace Mhina | Finance Director (CFO) | senior-management | STAFF | Dar es Salaam HQ | gmhina | FINANCE_DIRECTOR |
| 3 | Halima Juma | Branch Manager — Dar | manager | STAFF | Dar es Salaam HQ | hjuma | BRANCH_MANAGER |
| 4 | Emanuel Mushi | Branch Manager — Arusha | manager | STAFF | Arusha | emushi | BRANCH_MANAGER |
| 5 | Daudi Kessy | Group Sales Manager | manager | STAFF | Dar es Salaam HQ | dkessy | BRANCH_MANAGER |
| 6 | Rehema Salum | Procurement Manager | manager | STAFF | Dar es Salaam HQ | rsalum | PROCUREMENT_OFFICER |
| 7 | Editha Mhagama | Production Manager | manager | STAFF | Dar es Salaam HQ | emhagama | PRODUCTION_OFFICER |
| 8 | Neema Kileo | HR & Payroll Manager | hr | STAFF | Dar es Salaam HQ | nkileo | HR_PAYROLL_OFFICER |
| 9 | Frank Materu | Stores / Warehouse Supervisor | supervisor | STAFF | Dar es Salaam HQ | fmateru | STORES_SUPERVISOR |
| 10 | Editrude Mwakalukwa | Production Supervisor | supervisor | STAFF | Dar es Salaam HQ | emwakalukwa | PRODUCTION_OFFICER |
| 11 | Amina Mwanga | Accountant | accountant | STAFF | Dar es Salaam HQ | amwanga | ACCOUNTANT |
| 12 | John Komba | Cashier / Cash & Bank Officer | accountant | STAFF | Dar es Salaam HQ | jkomba | CASHIER |
| 13 | Sabina Aloyce | Salesperson | sales | STAFF | Dar es Salaam HQ | saloyce | SALES_OFFICER |
| 14 | Hamisi Ngassa | Field / Route Sales Agent | sales | STAFF | Mwanza | hngassa | FIELD_SALES_AGENT |
| 15 | Saidi Karume | Storekeeper / Stock Controller | stores | STAFF | Dar es Salaam HQ | skarume | STOREKEEPER |
| 16 | Yusuf Mbwana | Procurement Officer | procurement | STAFF | Dar es Salaam HQ | ymbwana | PROCUREMENT_OFFICER |
| 17 | Joseph Ulimboka | Credit-account retailer (customer) | external-party | EXTERNAL | Mwanza (buys from) | — | — |
| 18 | Mbasha Holdings Ltd | Raw-material & traded-goods supplier | external-party | EXTERNAL | Dar es Salaam (supplies) | — | — |

> Note on production roster: persona #10 (Production Supervisor) and #7 (Production
> Manager) both carry the `PRODUCTION_OFFICER` role; the supervisor operates work
> orders day-to-day while the manager approves/oversees. Persona #5 (Group Sales
> Manager) and #6 (Procurement Manager) carry `BRANCH_MANAGER` / `PROCUREMENT_OFFICER`
> respectively — the simulation defines no separate "sales-manager" permission bundle
> beyond branch-manager approval authority; refine if a finer split is needed.

---

## 8. Persona detail (STAFF — what they do, what they report)

### 1. Bakari Mbaga — Group General Manager
- **Modules:** BI/dashboards, reporting, sales, purchases, GL, approvals.
- **Primary workflows:** Review the group sales & gross-margin dashboard each
  morning; approve a high-value purchase order to an overseas supplier that exceeds
  the procurement manager's limit; approve a large credit sale to a tender customer.
- **Reports problems about:** Wrong or stale group figures on dashboards; approvals
  that don't reach him; inability to see a branch's numbers.
- **Reports to:** Board of Directors.

### 2. Grace Mhina — Finance Director (CFO)
- **Modules:** GL, AR, AP, cash & bank, tax, fixed assets, budgeting, FX, costing, reporting.
- **Primary workflows:** Run period close for the month and review the trial
  balance; approve a batch of supplier payments in the cash & bank module; set and
  approve a customer's credit limit; prepare the VAT return position for the TRA.
- **Reports problems about:** Out-of-balance journals, a VAT figure that disagrees
  with EFD totals, payment runs that won't post, FX revaluation errors.
- **Reports to:** Bakari Mbaga.

### 3. Halima Juma — Branch Manager (Dar)
- **Modules:** Sales, purchases, stock, reporting, approvals.
- **Primary workflows:** Approve the day's sales orders for the Dar branch; review
  Dar stock levels and raise an inter-branch transfer to Mwanza when they're short;
  check the branch sales-vs-target report.
- **Reports problems about:** Approvals queued to the wrong branch, transfers that
  don't arrive, branch report numbers that don't tie to the counter.
- **Reports to:** Bakari Mbaga (dotted line to Sales & Finance).

### 4. Emanuel Mushi — Branch Manager (Arusha)
- **Modules:** Sales, purchases, stock, reporting, approvals.
- **Primary workflows:** Switch his active branch to Arusha and approve Arusha
  sales orders; receive an inter-branch transfer from the Dar warehouse into the
  Arusha store; approve a local purchase for the Arusha depot.
- **Reports problems about:** Branch-switch confusion (acting on the wrong branch),
  a received transfer not showing in Arusha stock.
- **Reports to:** Bakari Mbaga.

### 5. Daudi Kessy — Group Sales Manager
- **Modules:** Sales, CRM, reporting, approvals.
- **Primary workflows:** Assign Hamisi Ngassa as the route agent on Mwanza sales
  orders; approve a discount on a wholesale order above the salesperson's limit;
  review the sales pipeline and agent-performance report across branches.
- **Reports problems about:** Agent assignment not sticking on an order, discount
  approvals not routing to him, sales reports missing a branch.
- **Reports to:** Bakari Mbaga.

### 6. Rehema Salum — Procurement Manager
- **Modules:** Purchases, parties, stock (view), approvals.
- **Primary workflows:** Approve a purchase requisition from the factory for crude
  palm oil; convert an RFQ to a purchase order on the cheapest quote; approve a PO
  to Mbasha Holdings within her limit and escalate larger ones to the GM.
- **Reports problems about:** RFQ-to-PO conversion losing prices, supplier records
  duplicated, PO approval thresholds wrong.
- **Reports to:** Bakari Mbaga.

### 7. Editha Mhagama — Production Manager
- **Modules:** Manufacturing, stock, costing, reporting.
- **Primary workflows:** Release a work order to produce 5,000 bottles of Tembo
  Cooking Oil; confirm the BOM raw-material consumption from the factory store;
  review the production cost report for a finished batch.
- **Reports problems about:** BOM consuming the wrong quantities, finished goods not
  landing in stock, production cost not matching material issues.
- **Reports to:** Bakari Mbaga.

### 8. Neema Kileo — HR & Payroll Manager
- **Modules:** HR, payroll, reporting.
- **Primary workflows:** Onboard a new branch employee with contract and branch
  assignment; run the monthly payroll and review PAYE/NSSF/WCF/SDL deductions;
  approve a leave request and generate payslips.
- **Reports problems about:** Wrong statutory deductions, a payroll run that won't
  post to GL, leave balances that don't update.
- **Reports to:** Bakari Mbaga.

### 9. Frank Materu — Stores / Warehouse Supervisor
- **Modules:** Stock, manufacturing (issues), reporting.
- **Primary workflows:** Approve a stock adjustment after a count discrepancy at the
  Dar warehouse; set up a new stock location for imported electronics; oversee the
  goods receipt of an imported container and confirm put-away.
- **Reports problems about:** Adjustments that won't approve, locations not findable
  across branches, valuation not updating after a receipt.
- **Reports to:** Halima Juma (Dar Branch Manager).

### 10. Editrude Mwakalukwa — Production Supervisor
- **Modules:** Manufacturing, stock (issues/receipts).
- **Primary workflows:** Execute the work order on the soap line and record actual
  raw-material consumption; receive finished bar soap into the factory store; report
  a production yield variance to the production manager.
- **Reports problems about:** Work order not letting her record actuals, finished-
  goods receipt rejected, yield variance miscalculated.
- **Reports to:** Editha Mhagama (Production Manager).

### 11. Amina Mwanga — Accountant
- **Modules:** GL, AR, AP, tax, reporting, cash & bank (view).
- **Primary workflows:** Post the sales invoices raised at the counter to the GL and
  reconcile to EFD totals; raise a supplier (AP) invoice against Mbasha Holdings'
  goods-receipt; prepare the monthly VAT return figures; post a correcting journal.
- **Reports problems about:** Invoices not posting to GL, VAT mis-stated, a journal
  that won't balance, AP not matching the goods receipt.
- **Reports to:** Grace Mhina (Finance Director).

### 12. John Komba — Cashier / Cash & Bank Officer
- **Modules:** Cash & bank, AR (receipts), reporting.
- **Primary workflows:** Record a customer's cash payment against Joseph Ulimboka's
  outstanding AR balance; do the end-of-day cash count and bank deposit; record
  petty-cash disbursements for the Dar branch.
- **Reports problems about:** Receipts not clearing the customer balance, cash count
  not balancing, deposit not reflecting in the bank account.
- **Reports to:** Grace Mhina (Finance Director).

### 13. Sabina Aloyce — Salesperson
- **Modules:** Sales, parties, AR (view), POS/counter.
- **Primary workflows:** Create a quotation, then a sales order and sales invoice
  for Kariakoo Wholesale Mart and issue the EFD receipt; register a new walk-in
  customer; raise a delivery note for goods leaving the Dar store.
- **Reports problems about:** Invoice totals or VAT wrong, EFD receipt not issuing,
  a new customer she can't save, order stuck without an agent.
- **Reports to:** Daudi Kessy (Group Sales Manager).

### 14. Hamisi Ngassa — Field / Route Sales Agent
- **Modules:** Sales (route orders & invoices), cash & bank (collections).
- **Primary workflows:** Capture a route sales order from Joseph Ulimboka's duka on
  his Mwanza round and invoice it; record the cash collected against the invoice;
  reconcile his van stock at the end of the route.
- **Reports problems about:** Not being assigned as agent on his own orders, cash
  collection not matching invoices, van stock not reconciling.
- **Reports to:** Daudi Kessy (Group Sales Manager).

### 15. Saidi Karume — Storekeeper / Stock Controller
- **Modules:** Stock, purchases (goods receipt).
- **Primary workflows:** Receive a purchase order from Mbasha Holdings into the Dar
  warehouse and record batch/expiry; issue raw materials to the factory against a
  work order; perform a cycle count and record the result for supervisor approval.
- **Reports problems about:** Goods receipt not matching the PO, stock issue not
  reducing on-hand, count flow losing entered lines.
- **Reports to:** Frank Materu (Stores Supervisor).

### 16. Yusuf Mbwana — Procurement Officer
- **Modules:** Purchases, parties.
- **Primary workflows:** Raise a purchase requisition for crude palm oil and timber,
  then an RFQ to three suppliers; create a purchase order to Mbasha Holdings for
  crude palm oil and route it to Rehema Salum for approval; register a new supplier.
- **Reports problems about:** Requisition scope/branch wrong, RFQ not reaching
  suppliers, PO that won't submit for approval, duplicate supplier records.
- **Reports to:** Rehema Salum (Procurement Manager).

---

## 9. External parties (do not log in)

### 17. Joseph Ulimboka — Customer (credit-account retailer)
- **Who enters his business:** Hamisi Ngassa captures Joseph's route orders and
  invoices on the Mwanza round; John Komba records his payments; Amina Mwanga
  manages his AR.
- **Typical complaints he raises (translated into a problem report by staff):**
  "My delivery was short two bags of sembe," "you're charging me VAT on an item I
  return," "my account still shows last month's balance I already paid." A staff
  persona (Hamisi or Amina) translates the complaint into a problem report.

### 18. Mbasha Holdings Ltd — Supplier (raw materials + traded goods)
- **Who enters their business:** Yusuf Mbwana raises POs and RFQs to them; Saidi
  Karume receives their goods; Amina Mwanga raises the AP invoice and Grace Mhina
  approves payment.
- **Typical complaints they raise (translated into a problem report by staff):**
  "Your goods receipt under-counted our delivery," "the PO price doesn't match our
  quote," "our last invoice hasn't been paid past terms." Yusuf or Amina translates
  the complaint into a problem report.

---

*End of world bible. Every persona, party, product, role and branch referenced by
the simulation must resolve to an entry above.*
