### Stock transfers now move the right value

When stock was transferred, the quantity always arrived correctly, but the **value** did not:

- The cost price of goods at the receiving location was recorded at roughly **half** what it should
  have been. This happened on every transfer, instant or in-transit.
- On an in-transit transfer between two branches, the goods reached the receiving branch but their
  value stayed behind in **In-Transit**, and the In-Transit location never went back to zero.

Both are fixed for every transfer made after this update. **Transfers made before it are not changed
automatically** — we will go through them with you and correct the affected stock values.

### Importing and exporting: up to 50,000 rows

Bulk import and export used to stop at 2,000 rows. They now handle up to **50,000**, so a full
product list can be exported, edited and imported back in one go. Files up to 32 MB are accepted.

### Barcode search on the product list

Searching the product list by scanning or typing a barcode now shows the product it found. It
previously showed a row with a blank code, name and type.

### The till: a new OrbixPOS version

**OrbixPOS 1.5.2** is available alongside this update and is installed separately on each till. It
fixes barcode scanning:

- after a scan, the item is added and the search list closes — it used to open again a moment later;
- scanning items quickly one after another now adds each item once. Previously, while that list was
  open, the next scan could add the **previous** item again instead of the one scanned. Please check
  recent sales for items that appear twice.
