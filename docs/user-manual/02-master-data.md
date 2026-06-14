# Master Data

Master data is the reference information shared across the system: the parties you trade with, the products you sell or buy, the prices you charge, the currencies you transact in, the taxes you apply, and the routes your sales team covers. Set this up first; every transaction in Sales, Procurement, Inventory, and Finance depends on it.

All master data screens are under the **Admin** section of the navigation. Your access depends on the permissions assigned to your role — the sections below note which permission is required for each area.

---

## Customers

**Navigation:** Admin > Customers | **Permission to view:** `CUSTOMER.VIEW` | **Permission to create / edit:** `CUSTOMER.MANAGE`

Customers are the parties you sell to. Each customer belongs to one company and carries a system-generated code (`CUST-0001`, `CUST-0002`, …). You never enter or see the internal uid — the system uses that behind the scenes.

### Customer types

Every customer record has two classification fields set at creation time:

| Field | Options | Notes |
|---|---|---|
| **Party Type** | Individual, Business | Business customers must have a TIN. |
| **Customer Kind** | Cash / Walk-in, Credit Account | Credit account customers carry a credit limit and payment terms. |

Once saved, Party Type and Customer Kind can be changed on the detail edit form.

### How to create a customer

1. Navigate to **Admin > Customers**.
2. Click **New Customer**. An inline form appears below the toolbar.
3. Enter the **Display Name** (required).
4. Select **Party Type** (Individual or Business).
   - If you choose Business, a **TIN** field becomes required.
5. Select **Customer Kind** (Cash / Walk-in or Credit Account).
   - If you choose Credit Account, a **Credit Limit** (amount and currency) and a **Payment Terms (days)** field appear. These are optional — you can leave them blank and set them later.
6. Optionally fill in Phone, Email, Address, Region, District.
7. If the customer is VAT-registered, tick **VAT Registered** and then enter the **VRN**. You cannot enter a VRN unless VAT Registered is ticked.
8. Click **Submit**.

The system assigns a unique code and sets the status to **Active**. The new row appears in the list immediately.

### How to search for a customer

On the Customers list:

- Type in the **Search** box. Name search is case-insensitive and matches any part of the name.
- Searching by **TIN**, **Phone**, or **Code** requires an exact match.
- The list resets to the first page when you start a new search.
- Click **Clear** to return to the full unfiltered list.

### How to view and edit a customer

1. Click on any row in the customer list to open the detail page.
2. The URL contains the customer's uid (`/admin/customers/uid/…`) — you do not need to read or type this.
3. Edit any field in the form. The **Code** and **Company** fields are read-only (they are set at creation and cannot change).
4. If Customer Kind is **Cash / Walk-in**, the Credit Limit and Payment Terms fields are hidden. Switch to Credit Account to reveal them.
5. Click **Save** to apply changes.

### How to archive and restore a customer

An archived customer remains in the database for historical reporting but is not available for new transactions.

1. Open the customer detail page.
2. Click **Archive**. The status badge changes to **Archived**.
3. To reverse, click **Restore**. The status returns to **Active**.

Archiving and restoring are both immediate and do not require a reason.

### Branch associations

A customer can be associated with specific branches of your company. This determines which branches can see the customer in their scoped views.

1. Open the customer detail page.
2. Scroll to the **Branch Associations** panel.
3. Select the **Company** from the first dropdown, then select the **Branch** (shown as `code — name`) from the second.
4. Click **Assign**. The branch appears in the association list with the date it was assigned.
5. To remove a branch, click **Remove** on the relevant row.

You need the `PARTY.BRANCH.ASSIGN` permission to assign or remove branches. You can only assign branches that belong to the same company as the customer.

---

## Suppliers

**Navigation:** Admin > Suppliers | **Permission to view:** `SUPPLIER.VIEW` | **Permission to create / edit:** `SUPPLIER.MANAGE`

Suppliers are the parties you purchase from. The data structure mirrors customers, with one difference: the kind field distinguishes **Goods** suppliers from **Service** suppliers (there are no credit limit or payment terms fields on a supplier record).

Supplier codes are prefixed `SUPP-` (for example, `SUPP-0001`).

### How to create a supplier

1. Navigate to **Admin > Suppliers**.
2. Click **New Supplier**.
3. Enter **Display Name** (required), **Party Type**, and **Supplier Kind** (Goods or Service).
4. If Party Type is Business, enter the **TIN**.
5. Fill in optional contact details and VAT fields as described in the Customers section above.
6. Click **Submit**.

The same rules apply: TIN required for Business parties, VRN only when VAT Registered is ticked.

### Search, edit, archive, restore, and branch associations

These work exactly as described for Customers above, substituting the Suppliers screen and the `SUPPLIER.MANAGE` / `PARTY.BRANCH.ASSIGN` permissions.

---

## Other Parties

**Navigation:** Admin > Other Parties | **Permission to view:** `OTHERPARTY.VIEW` | **Permission to create / edit:** `OTHERPARTY.MANAGE`

Other Parties covers any third party that is not a customer, supplier, or agent — for example, landlords, regulatory bodies, utility providers, or freight companies. Other Party codes are prefixed `OTHR-`.

The key difference from customers and suppliers is the **Other Kind** field, which is free text (not a fixed list). You can type any label, such as "Landlord", "Utility", or "Freight Forwarder". The field is optional.

All other behaviour — TIN rule for Business parties, VAT/VRN pairing, archive/restore lifecycle, and branch associations — is identical to Customers and Suppliers.

---

## Sales Agents

**Navigation:** Admin > Sales Agents | **Permission to view:** `AGENT.VIEW` | **Permission to create / edit:** `AGENT.MANAGE`

Sales agents represent the people or organisations that sell on your behalf. Agent codes are prefixed `AGNT-`.

### Agent kinds

| Kind | Meaning | User link |
|---|---|---|
| **Internal** | An employee who is also an app user | Must be linked to an active user in the same company |
| **External** | A third-party agent, not an app user | Must NOT be linked to an app user |

### How to create an agent

1. Navigate to **Admin > Sales Agents**.
2. Click **New Agent**.
3. Enter **Display Name**, **Party Type**, and **Agent Kind** (Internal or External).
4. If Kind is **Internal**, a **User** selector appears. Choose the user by name from the list. The system stores the link internally — you do not type a user id.
5. If Kind is **External**, the user selector is hidden.
6. Click **Submit**.

### Switching an agent between Internal and External

On the agent detail page, changing Kind from Internal to External clears the user link automatically on save. Changing from External to Internal requires you to select a user before saving.

### Search, edit, archive, restore, and branch associations

These work as described for Customers, using the `AGENT.MANAGE` and `PARTY.BRANCH.ASSIGN` permissions.

---

## Products

**Navigation:** Admin > Products | **Permission to view:** `PRODUCT.VIEW` | **Permission to create / edit:** `PRODUCT.MANAGE`

Products are the items you sell, buy, or manufacture. Each product belongs to one company and carries a system-generated code (for example, `PROD-0001`) unless you supply your own code at creation time.

### Product types

| Field | Options | Rules |
|---|---|---|
| **Type** | Goods, Service | Service products cannot be stockable (the Stockable checkbox is forced off). |
| **Stockable** | Yes / No | Only Goods products can be stockable. |
| **Sellable** | Yes / No | Controls whether the product appears in sales flows. |
| **VAT Status** | Standard, Zero-rated, Exempt | Defaults to Standard. |

### How to create a product

1. Navigate to **Admin > Products**.
2. Click **New Product**.
3. Optionally enter a **Code**. If you leave it blank the system assigns `PROD-####`. If you type a code it is trimmed of spaces and converted to upper case.
4. Enter the **Name** (required).
5. Select **Type** (Goods or Service). If you select Service, the Stockable checkbox becomes unavailable.
6. Select the **Base Unit** from the dropdown by its code and name (for example, `EA — Each`). Only active units of measure are offered.
7. Enter the **Cost** (amount and currency).
8. Select the **VAT Status**.
9. Click **Submit**.

### How to set a custom code

Type the code in the **Code** field. The system converts it to upper case (so `sku-001` becomes `SKU-001`). Codes must be unique within the company — if you enter a duplicate you will see an error after you submit.

### How to edit a product

1. Click a product row to open the detail page.
2. Modify fields as needed. The **Code** field is read-only on the detail page.
3. Click **Save**.

If you change Type from Goods to Service, the Stockable checkbox is forced off automatically.

### How to archive and restore a product

Open the product detail page and click **Archive** (to make it unavailable) or **Restore** (to make it active again). Archived products are excluded from order lines and component pickers.

### Branch associations

Works exactly as described for Customers. The permission required is `PRODUCT.BRANCH.ASSIGN`.

### Barcodes

In the **Barcodes** panel on the product detail page:

1. Type the barcode value.
2. Tick **Primary** if this is the product's primary barcode.
3. Click **Add Barcode**.
4. To remove a barcode, click **Remove** on the relevant row.

### Bulk packs

Bulk packs define how many base units fit into a larger packaging unit (for example, 24 `EA` in a `CTN — Carton`).

1. In the **Bulk Packs** panel, select the **Unit** (the larger packaging unit) from the dropdown by code and name.
2. Enter the **Factor** — the number of base units in one pack (must be greater than zero).
3. Click **Add**.
4. To remove a bulk pack, click **Remove**.

### Product prices

You can set a selling price for this product on each of your price lists.

1. In the **Prices** panel, select the **Price List** by its code and name.
2. Enter the **Amount** and **Currency**.
3. Click **Set Price**.

Setting a price on a price list that already has a price for this product overwrites the existing price. To remove a price, click **Remove** on the row.

### Product components (recipe)

Components define the ingredients or sub-products that make up this product — used in manufacturing or bundled sales.

1. In the **Components / Recipe** panel, start typing a product name in the search box.
2. Select the component product from the results (shown as `code — name`). The product itself and archived products are excluded from the list.
3. Enter the **Quantity** (must be greater than zero).
4. Click **Add Component**.
5. To remove a component, click **Remove** on the row.

---

## Units of Measure

**Navigation:** Admin > Units of Measure | **Permission to view:** `UOM.VIEW` | **Permission to create / edit:** `UOM.MANAGE`

Units of measure (UoM) are the quantity labels used on products, bulk packs, and order lines — for example, `EA` (Each), `KG` (Kilogram), `CTN` (Carton).

### How to create a unit

1. Navigate to **Admin > Units of Measure**.
2. Click **New Unit**.
3. Enter the **Code** (for example, `CTN`) and the **Name** (for example, `Carton`). Both are required and the code must be unique within the company.
4. Click **Submit**.

### How to edit a unit

Click **Edit** on a row, change the **Name** (the Code is read-only after creation), and click **Save**.

### Archive and restore

Click **Archive** to deactivate a unit. Archived units are removed from product and bulk-pack dropdowns — only active units are selectable. Click **Restore** to make the unit active again.

---

## Price Lists

**Navigation:** Admin > Price Lists | **Permission to view:** `PRICELIST.VIEW` | **Permission to create / edit:** `PRICELIST.MANAGE`

Price lists group selling prices. You might have a Retail list (`RETAIL`), a Wholesale list (`WHOLESALE`), and a Distributor list. Customers and orders are assigned a price list, and the system looks up the price from there.

### How to create a price list

1. Navigate to **Admin > Price Lists**.
2. Click **New Price List**.
3. Enter a **Code** (for example, `RETAIL`) and a **Name** (for example, `Retail Price List`). Both are required and the code must be unique within the company.
4. Click **Submit**.

### Edit, archive, restore

Click **Edit** on a row to change the name (code is read-only after creation). Archive and restore work as on all master records.

---

## Currencies and FX Rates

**Navigation:** Admin > FX Rates | **Permission to view:** `CURRENCY.VIEW` | **Permission to add rates:** `CURRENCY.MANAGE`

The system's base currency is **TZS**. You can record foreign exchange rates to support transactions in other currencies (USD, EUR, KES, and others).

### Currency list

Currencies are global reference data — you cannot create or delete them. The available currencies (TZS, USD, EUR, KES, and others) are seeded by the system and visible in the From / To pickers on the FX Rates screen.

### How to add an FX rate

1. Navigate to **Admin > FX Rates**.
2. Click **New Rate**.
3. Select the **From** currency and the **To** currency. They must be different.
4. Enter the **Rate** (must be greater than zero).
5. Set the **Effective Date** (required; format `YYYY-MM-DD`).
6. Set **Rate Type** (for example, `SPOT`) and **Source** (for example, `MANUAL`).
7. Click **Submit**.

FX rates are **append-only**: you cannot edit a rate in place. To correct a rate, add a new row with the corrected value and the correct effective date. The system uses the latest effective-dated rate for each currency pair when converting amounts.

The rates list is sorted newest-first and is paginated.

---

## Tax Rates

**Navigation:** Admin > Tax Rates | **Permission to view:** `TAXRATE.VIEW` | **Permission to edit:** `TAXRATE.MANAGE`

Three VAT bands are seeded per company:

| Band | Default rate |
|---|---|
| Standard | 18% (0.18) |
| Zero-rated | 0% (0.00) |
| Exempt | 0% (0.00) |

You can edit the rate for each band. There is no create or archive on tax rates — the three bands are fixed.

### How to edit a tax rate

1. Navigate to **Admin > Tax Rates**.
2. Click **Edit** on the relevant band row.
3. Enter the new rate as a decimal between 0 and 0.9999 (for example, `0.18` for 18%).
4. Click **Save**.

The rate applies to all future transactions that reference this VAT band on a product.

---

## Distribution Routes

**Navigation:** Admin > Routes | **Permission to view:** `ROUTE.VIEW` | **Permission to create / edit / assign branches:** `ROUTE.MANAGE` | **Permission to assign customers and agents:** `ROUTE.ASSIGN`

Routes represent geographic or logical delivery areas used to group customers and assign agents. Each route has a system-generated code, a name, and an optional location identifier.

### How to create a route

1. Navigate to **Admin > Routes**.
2. Click **New Route**.
3. Enter the **Name** (required) and optionally a **Location Identifier**.
4. Click **Submit**.

The system assigns a code. Status defaults to Active.

### How to edit a route

1. Click a route row to open the detail page.
2. Change the name or location identifier (code and company are read-only).
3. Click **Save**.

### Archive and restore

Click **Archive** on the route detail page to deactivate it. Click **Restore** to reactivate.

### Assigning customers to a route

1. Open the route detail page.
2. In the **Customers** panel, start typing a customer name in the search box.
3. Select the customer from the results (shown as `code — displayName`).
4. Click **Assign**.
5. To remove a customer from the route, click **Remove** on the row.

Only active customers from the same company appear in the picker. You need the `ROUTE.ASSIGN` permission.

### Assigning agents to a route

1. In the **Agents** panel, start typing an agent name.
2. Select the agent from the results. Only **External** agents are available — internal agents cannot be assigned to a route.
3. Tick **Primary** if this agent is the primary agent for this route.
4. Click **Assign**.
5. To remove an agent, click **Remove**.

You need the `ROUTE.ASSIGN` permission.

### Assigning branches to a route

1. In the **Branches** panel, select the **Company** from the first dropdown, then select the **Branch** (shown as `code — name`).
2. Click **Assign**.
3. To remove a branch, click **Remove**.

You need the `ROUTE.MANAGE` permission (not `ROUTE.ASSIGN`) to manage branch assignments on a route.
