# Fixed Assets

**What is the Fixed Assets module?**
A fixed asset is a tangible item a business buys and uses over multiple years — machinery, vehicles, computers, office furniture. Unlike stock, which is sold and replaced constantly, a fixed asset sits on the company's balance sheet as long as it is in use. Because the asset is consumed gradually over its useful life, its cost is spread across accounting periods as **depreciation**: a periodic charge that reduces the asset's book value and recognises the consumption on the profit and loss account. Without a formal asset register, capital purchases get mis-coded as expenses (overstating costs and understating the balance sheet), depreciation goes unrecorded, and the financial statements do not reflect the real value of the business. The Fixed Assets module (ADR-0030) provides the register, the depreciation engine, and the GL integration that keeps the balance sheet and the profit and loss account accurate.

This chapter covers registering and managing fixed assets, running depreciation, transferring assets between branches, and disposing of or writing off assets. All screens are available from the **Fixed Assets** navigation group.

---

## 1. Permissions quick reference

| Task | Permission code required |
|---|---|
| View asset categories | `FA.CATEGORY.VIEW` |
| Create / edit / archive asset categories | `FA.CATEGORY.MANAGE` |
| View the asset register, schedule, revaluations, depreciation runs, reconciliation | `FA.VIEW` |
| Register, edit, place in service, transfer assets | `FA.REGISTER.MANAGE` |
| Dispose, write off, or revalue an asset | `FA.DISPOSE` |
| Preview and post depreciation runs | `FA.DEPRECIATE` |

Navigation items are hidden when the corresponding permission is absent.

---

## 2. Asset categories

Navigate to **Fixed Assets > Asset Categories** (`/admin/asset-categories`).

**What is an asset category, and why does it exist?**
An asset category is a classification template that groups assets of the same type together — for example "Motor Vehicles", "Machinery", or "Office Furniture". It is used because assets of the same type typically depreciate at the same rate, have the same useful life, and should post to the same General Ledger (GL) accounts. Rather than setting the depreciation method, useful life, and three GL account codes on every individual asset, you set them once on the category and every asset in that category inherits them. This ensures consistency, reduces data-entry errors, and means a change in accounting policy (such as adjusting the useful life for a class of machinery) can be applied at the category level without re-editing each asset. Before any asset can be registered the relevant category must exist.

An asset category defines the depreciation method, useful life, and GL accounts used for assets of a particular type (e.g. Machinery, Vehicles, Furniture). Categories must be set up before any asset can be registered.

### 2.1 Creating a category

1. Click **New Category**.
2. Enter a unique **Code** (e.g. `MACH`) and a **Name** (e.g. Machinery).
3. Choose the **Depreciation Method**:
   - **Straight Line** — equal charge each period.
   - **Reducing Balance** — percentage of the remaining book value each period. Requires a **Reducing Rate** (e.g. `0.25` for 25%).
4. Enter the **Default Life Periods** — the standard useful life in accounting periods.
5. Enter the three **GL Account IDs** by their numeric identifier:
   - Asset Account (the balance-sheet asset account, e.g. 1510)
   - Accumulated Depreciation Account (the contra account, e.g. 1515)
   - Depreciation Expense Account (the P&L charge account, e.g. 6510)
6. Click **Submit**.

New categories are created with **Active** status.

**Validation.** Code must be unique within the company. Reducing Balance requires a Reducing Rate. All three GL account IDs are required.

### 2.2 Editing a category

Open the category detail (navigate from the list). Click **Edit**, change the name, method, life, or account IDs, and save. The code is not editable after creation.

### 2.3 Archiving a category

Open the category detail and click **Archive**. The status changes to **Archived**. Archived categories are hidden from the category dropdown on the asset-registration form. An archived category is not deleted; its history and associated assets remain.

---

## 3. Asset register

Navigate to **Fixed Assets > Fixed Assets** (`/admin/fixed-assets`).

**What is the asset register?**
The asset register is the master list of every fixed asset the company owns. It is the single source of truth for capital investment: it records the original cost of each asset, the depreciation accumulated against it so far, and the resulting **net book value (NBV)** — the carrying value shown on the balance sheet. Every purchase of a capital item must be entered here (not coded to expense) so that the balance sheet correctly shows the asset, the profit and loss account receives only the proportionate depreciation charge each period, and the year-end accounts accurately reflect the company's capital base. The register is used by the finance team and reviewed by auditors to verify that assets exist, are in service, and are depreciated appropriately. The system keeps the register in step with the GL: every capitalisation, depreciation run, revaluation, and disposal posts a matching GL entry, and the FA-to-GL reconciliation screen (section 9) confirms the two agree.

The register lists all fixed assets for the selected company. Use the status filter to show assets by state: Draft, In Service, Disposed, or Written Off.

### Asset status lifecycle

```
DRAFT
  |-- (place in service) --> IN_SERVICE
                              |-- (dispose/SALE)     --> DISPOSED
                              |-- (write-off)        --> WRITTEN_OFF
                              \-- (transfer)         --> IN_SERVICE (branch changes)
```

DISPOSED and WRITTEN_OFF are terminal states.

### 3.1 Registering an asset

1. Navigate to **Fixed Assets > Register Asset** (`/admin/fixed-assets/create`).
2. Select the **Company**.
3. Choose the **Category** from the dropdown (only Active categories are listed).
4. Pick the **Branch** from the picker (search by name).
5. Enter the **Asset Name** (e.g. Lathe #1).
6. Enter the **Acquisition Cost** (the purchase price, excluding VAT).
7. Enter the **Salvage Value** (the estimated residual value at the end of useful life; enter 0 if none).
8. Choose the **Depreciation Method** (defaults from the category, can be overridden).
9. For Reducing Balance, enter the **Reducing Rate**.
10. Enter the **Life Periods** (can be overridden from the category default).
11. Enter the **Acquisition Date** and **Depreciation Start Date** (ISO format yyyy-MM-dd).
12. Optionally enter an **Asset Tag**, **Location**, **Cost Centre ID**, and **Notes**.
13. Click **Submit**.

The asset is created with status **Draft** and a system-generated asset number (e.g. `AST-0001`). No GL posting occurs at this stage.

**Validation.** All required fields must be present. Reducing Balance requires a Reducing Rate. Life Periods must be at least 1.

### 3.2 Editing asset details

Non-financial fields (name, location, asset tag, cost centre) can only be edited while the asset is in **Draft** status. Open the asset detail, click **Edit**, make changes, and save.

Financial fields (acquisition cost, method, life, dates) cannot be changed after the asset is registered. To correct these, you must dispose of or write off the asset and register a new one.

### 3.3 Viewing the asset detail

Open any asset from the list. The detail screen shows:

- Header: asset number, name, category, branch, status badge, acquisition cost, accumulated depreciation, net book value (NBV), and (if revalued) the revaluation reserve balance.
- **Depreciation Schedule** tab (available when In Service) — a line for each period showing planned charge, accumulated depreciation after, NBV after, and a posted flag.
- **Revaluations** tab (available when In Service) — history of all revaluations in date order.

The asset number is the human identifier shown throughout the UI. The internal identifier appears only in the browser address bar.

---

## 4. Placing an asset in service

**What does "placing in service" mean?**
A Draft asset has been registered but not yet capitalised: it exists in the register but has no effect on the books. Placing an asset in service is the act of formally recognising it on the balance sheet — the moment the business acknowledges it owns an asset with an economic value. When you place an asset in service, two things happen simultaneously: (1) a GL journal entry is posted that moves the cost onto the Fixed Assets account (the balance-sheet impact), and (2) the full depreciation schedule is generated for the asset's entire useful life, so the system knows exactly how much to charge in each future period. The posting date must fall within an open fiscal period because it is a real accounting event. Until an asset is placed in service, it produces no depreciation and appears nowhere on the financial statements.

Placing an asset in service capitalises it: the system posts a GL entry and generates the depreciation schedule.

1. Open a **Draft** asset.
2. Click **Place in Service**.
3. Enter the **Posting Date** (must fall in an open fiscal period).
4. Confirm.

Status changes to **In Service**. A capitalisation GL entry is posted (DR Asset Account / CR Cash or AP Clearing). The depreciation schedule is generated for the full useful life.

**Validation.** Posting date is required. The fiscal period containing the posting date must be open. The action is available only on Draft assets.

---

## 5. Transferring an asset

**What is an asset transfer?**
A transfer is a purely administrative change that moves an asset from one branch or cost centre to another — for example, when a vehicle is reassigned from the Dar es Salaam branch to the Arusha branch. It has no accounting effect: the asset's cost, accumulated depreciation, and NBV remain unchanged, and no GL entry is posted. The purpose is to keep the register accurate so that each branch's asset list reflects what is physically present there, which matters for insurance, physical verification, and cost-centre reporting.

A transfer changes the branch or cost centre of an asset without affecting its financial values. No GL entry is posted.

1. Open a **Draft** or **In Service** asset.
2. Click **Transfer**.
3. Enter the target **Branch ID** and optionally a new **Location** and **Cost Centre ID**.
4. Confirm.

The asset's branch and location are updated immediately. Disposed and Written-Off assets cannot be transferred.

> **Note.** The transfer form accepts branch and cost centre as typed numeric IDs rather than pickers. Check with your system administrator for the correct numeric IDs if you do not know them.

---

## 6. Depreciation

**What is depreciation, and why is it run periodically?**
Depreciation is the systematic allocation of an asset's cost over its useful life. A delivery van costing TZS 24,000,000 that is expected to last 4 years does not cost the business TZS 24,000,000 in year one — it costs roughly TZS 6,000,000 per year (on the straight-line method). Recording that annual charge on the profit and loss account gives a realistic view of operating costs and ensures the balance sheet shows the asset at its current economic value, not its original price. Without running depreciation, the P&L understates costs, profits are overstated, and the balance sheet carries assets at inflated values. The system enforces one depreciation run per fiscal period per company: once a period's charges are posted, they cannot be doubled-up.

### 6.1 Supported methods

| Method | Behaviour |
|---|---|
| **Straight Line** | Equal charge each period: (Acquisition Cost − Salvage Value) / Life Periods |
| **Reducing Balance** | Percentage of the closing book value each period: NBV × Reducing Rate |

**Straight Line** is simpler and produces equal charges — appropriate for assets that provide roughly equal benefit in each period (office furniture, computers). **Reducing Balance** produces a higher charge early and a lower charge later — appropriate for assets that lose value quickly in the first years of use (vehicles, plant). In both cases the final period's charge is a residual plug that ensures the asset reaches exactly its salvage value: there is no rounding drift over the asset's life.

### 6.2 Previewing a depreciation run

**What is a depreciation run preview?**
A preview is a read-only simulation: it shows you exactly which assets would be charged and what amount each would attract if you were to post the run right now. No journal is posted and no data is changed. This is the recommended step before posting, because once a run is posted for a period it cannot be reversed or re-run. Reviewing the preview lets you catch anomalies — an unexpected zero charge, a newly capitalised asset you forgot to check — before they reach the books.

Before posting, preview the run to see what charges will be created.

1. Navigate to **Fixed Assets > Run Depreciation** (`/admin/depreciation-runs/post`).
2. Select the **Company**.
3. Enter the **Fiscal Period UID** for the period you want to depreciate.
4. Click **Preview**.

The preview table lists each eligible asset with its planned charge for the period, plus a total. Nothing is posted.

### 6.3 Posting a depreciation run

**What happens when you post a depreciation run?**
Posting a depreciation run does four things at once: (1) it creates a `DEPR-####` run record that acts as the audit trail for the period; (2) it posts a single consolidated GL journal — one Debit to Depreciation Expense and one Credit to Accumulated Depreciation per asset category — covering every eligible asset; (3) it marks each asset's schedule line for the period as posted and increases each asset's accumulated depreciation balance; and (4) it makes the run idempotent: re-running the same period is a safe no-op (the system returns the existing run without posting twice). This idempotency guarantee means you can safely retry a run if a network error occurs during posting, with no risk of double-charging.

After reviewing the preview:

1. Enter the **Posting Date** (must fall within the selected open fiscal period).
2. Click **Post**.

The system creates a depreciation run with status **Posted** and a run number (e.g. `DEPR-0001`). A single consolidated GL entry is posted covering all eligible assets. Each asset's accumulated depreciation balance increases. The schedule lines for the period are marked as posted.

**Validation.** Only one depreciation run is allowed per company per fiscal period. Attempting a second run for the same period is rejected.

### 6.4 Viewing depreciation runs

Navigate to **Fixed Assets > Depreciation Runs** (`/admin/depreciation-runs`). The list shows all posted runs in reverse date order. Click a run to see the detail, which includes per-asset lines showing the charge amount, accumulated depreciation after the run, and NBV after the run.

---

## 7. Revaluing an asset

**What is an asset revaluation, and when is it needed?**
An asset revaluation adjusts the carrying value of an asset to reflect its current fair market value, typically when an independent appraisal shows that the asset is worth significantly more or less than its book value. An upward revaluation increases the asset's carrying value on the balance sheet and creates a credit to a **Revaluation Reserve** (an equity account): the company is wealthier on paper, but the gain is deferred in equity rather than taken to income. A downward revaluation reduces the carrying value and is charged to the profit and loss account (a loss). In both cases the remaining depreciation schedule is regenerated from the new carrying value over the remaining useful life, so future depreciation charges reflect the revised base. Revaluation is done by the finance team when an appraisal indicates the book value is materially different from market value — typically at year-end or when preparing the accounts for a transaction such as a disposal or a valuation exercise.

Revaluation adjusts the carrying cost of an In Service asset to its current fair value. The depreciation schedule is regenerated after a revaluation.

1. Open an **In Service** asset.
2. Click **Revalue**.
3. Choose the **Direction**: Up or Down.
4. Enter the **Delta Amount** (the change in carrying cost, always a positive number).
5. Enter the **Revaluation Date**.
6. Enter a **Reason** (e.g. market appraisal).
7. Confirm.

For an **Up** revaluation: carrying cost increases by the delta; the revaluation reserve increases.

For a **Down** revaluation: carrying cost decreases by the delta. The delta must not reduce the carrying cost below the accumulated depreciation balance (the NBV cannot go below zero due to a revaluation).

The revaluation is recorded in the Revaluations tab. The depreciation schedule is regenerated from the new carrying cost over the remaining useful life.

---

## 8. Disposing of an asset

**What is an asset disposal?**
A disposal is the formal removal of an asset from the register when it is sold or scrapped. When an asset leaves the business, its gross cost must be removed from the Fixed Assets account, its accumulated depreciation must be cleared from the contra account, and any difference between the proceeds received and the asset's net book value at that date is recognised as a **gain or loss on disposal** on the profit and loss account. Failing to record a disposal leaves "ghost" assets on the balance sheet — assets the company no longer owns, overstating the balance sheet and inflating accumulated depreciation. The disposal also posts any outstanding scheduled depreciation up to the disposal date, ensuring the NBV used to calculate the gain or loss is accurate.

### 8.1 Disposal by sale

Use this option when the asset is sold.

1. Open an **In Service** asset.
2. Click **Dispose**.
3. Enter the **Disposal Date** (must fall in an open fiscal period).
4. Enter the **Proceeds Amount** (the sale price; enter `0` if the asset is given away for nothing).
5. Enter an optional reason.
6. Confirm.

The system first posts any depreciation charges that are scheduled up to the disposal date but have not yet been posted. It then calculates the net book value at the disposal date and computes the gain or loss:

`Gain / (Loss) = Proceeds − NBV at disposal`

Status changes to **Disposed**. A disposal GL entry is posted. An asset can only be disposed of once.

### 8.2 Write-off

**What is a write-off?**
A write-off is used when an asset is scrapped, lost, stolen, or so impaired that it has no recoverable value — so no sale proceeds are received. It is identical to a disposal by sale except the proceeds are forced to zero, meaning the entire remaining NBV becomes a loss on the profit and loss account. Common examples include equipment damaged beyond repair, assets destroyed in a fire, or obsolete technology with zero resale value.

Use this option when the asset is scrapped, lost, or fully impaired and no proceeds are received.

1. Open an **In Service** asset.
2. Click **Write Off**.
3. Enter the **Write-Off Date** and an optional reason.
4. Confirm.

The loss equals the full NBV at the write-off date (proceeds are forced to zero). The same final-period depreciation logic applies. Status changes to **Written Off**.

---

## 9. FA to GL reconciliation

**What is the FA-to-GL reconciliation, and why does it matter?**
The reconciliation screen confirms that the asset register and the General Ledger agree. Because every capitalisation, depreciation run, revaluation, and disposal in this module posts a matching GL journal, the sum of all asset costs in the register should always equal the balance on the Fixed Assets GL account, and the sum of all accumulated depreciation in the register should always equal the balance on the Accumulated Depreciation GL account. A discrepancy means someone has posted a manual journal directly to one of those GL accounts, bypassing the register — a data-integrity problem that must be investigated. A green "Ties" indicator confirms the books are clean; a red "Does Not Tie" indicator is a flag for the finance team to investigate before month-end or year-end close.

Navigate to **Fixed Assets > Reconciliation** (`/admin/fixed-assets/reconciliation`). Requires the `FA.VIEW` permission.

1. Select the **Company**.
2. The report compares two balances:
   - **Register Cost** — the sum of acquisition costs in the asset register.
   - **GL Cost Balance** — the total of all asset GL accounts.
   - **Register Accumulated Depreciation** — the sum of accumulated depreciation in the register.
   - **GL Accumulated Depreciation Balance** — the total of all accumulated-depreciation GL accounts.

Both bars show a green **Ties** indicator when the register and GL agree. A red **Does Not Tie** indicator means there is a discrepancy. A mismatch typically indicates a manual GL journal was posted directly to an asset account, which bypasses the register.

---

## 10. Frequently asked questions

**When does a GL entry get posted for a new asset?**
No GL entry is posted when the asset is registered (Draft). The capitalisation entry is posted when you click Place in Service.

**Can I change the depreciation method after placing an asset in service?**
No. Method and financial parameters are fixed at registration time. If a correction is needed, dispose of the asset and register a new one.

**What happens to scheduled depreciation at the time of disposal?**
The system automatically posts any depreciation that is scheduled and not yet posted, up to the disposal date. This ensures NBV is accurate before the gain/loss is calculated.

**Can I run depreciation more than once for the same period?**
No. The system enforces one run per company per fiscal period. Use the preview function first to confirm the charges before posting.

**Does a branch transfer post a GL entry?**
No. A transfer is a location update only and has no accounting effect.
