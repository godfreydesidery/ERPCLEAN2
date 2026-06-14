# Fixed Assets

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

Placing an asset in service capitalises it: the system posts a GL entry and generates the depreciation schedule.

1. Open a **Draft** asset.
2. Click **Place in Service**.
3. Enter the **Posting Date** (must fall in an open fiscal period).
4. Confirm.

Status changes to **In Service**. A capitalisation GL entry is posted (DR Asset Account / CR Cash or AP Clearing). The depreciation schedule is generated for the full useful life.

**Validation.** Posting date is required. The fiscal period containing the posting date must be open. The action is available only on Draft assets.

---

## 5. Transferring an asset

A transfer changes the branch or cost centre of an asset without affecting its financial values. No GL entry is posted.

1. Open a **Draft** or **In Service** asset.
2. Click **Transfer**.
3. Enter the target **Branch ID** and optionally a new **Location** and **Cost Centre ID**.
4. Confirm.

The asset's branch and location are updated immediately. Disposed and Written-Off assets cannot be transferred.

> **Note.** The transfer form accepts branch and cost centre as typed numeric IDs rather than pickers. Check with your system administrator for the correct numeric IDs if you do not know them.

---

## 6. Depreciation

### 6.1 Supported methods

| Method | Behaviour |
|---|---|
| **Straight Line** | Equal charge each period: (Acquisition Cost − Salvage Value) / Life Periods |
| **Reducing Balance** | Percentage of the closing book value each period: NBV × Reducing Rate |

### 6.2 Previewing a depreciation run

Before posting, preview the run to see what charges will be created.

1. Navigate to **Fixed Assets > Run Depreciation** (`/admin/depreciation-runs/post`).
2. Select the **Company**.
3. Enter the **Fiscal Period UID** for the period you want to depreciate.
4. Click **Preview**.

The preview table lists each eligible asset with its planned charge for the period, plus a total. Nothing is posted.

### 6.3 Posting a depreciation run

After reviewing the preview:

1. Enter the **Posting Date** (must fall within the selected open fiscal period).
2. Click **Post**.

The system creates a depreciation run with status **Posted** and a run number (e.g. `DEPR-0001`). A single consolidated GL entry is posted covering all eligible assets. Each asset's accumulated depreciation balance increases. The schedule lines for the period are marked as posted.

**Validation.** Only one depreciation run is allowed per company per fiscal period. Attempting a second run for the same period is rejected.

### 6.4 Viewing depreciation runs

Navigate to **Fixed Assets > Depreciation Runs** (`/admin/depreciation-runs`). The list shows all posted runs in reverse date order. Click a run to see the detail, which includes per-asset lines showing the charge amount, accumulated depreciation after the run, and NBV after the run.

---

## 7. Revaluing an asset

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

Use this option when the asset is scrapped, lost, or fully impaired and no proceeds are received.

1. Open an **In Service** asset.
2. Click **Write Off**.
3. Enter the **Write-Off Date** and an optional reason.
4. Confirm.

The loss equals the full NBV at the write-off date (proceeds are forced to zero). The same final-period depreciation logic applies. Status changes to **Written Off**.

---

## 9. FA to GL reconciliation

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
