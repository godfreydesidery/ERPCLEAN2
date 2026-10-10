/**
 * Sales-order line units (SAL-01 / LSF-01).
 *
 * A sales-order line is ordered and priced in its OWN unit (`unitName`, e.g. a Crate of 24);
 * every running counter on it (`qtyFulfilledBase`, `qtyInvoicedBase`, `qtyReservedBase`,
 * `openQtyBase`) is in the product's BASE unit, because stock is. Screens that label a quantity
 * with `unitName` must therefore show it in that unit — base ÷ factor — or "24 Crate" is printed
 * for one crate. A delivery quantity is ENTERED in the line's unit; the server converts it.
 *
 * Mirrors the backend `SalesLineUnits`: the factor is read off the order line itself
 * (`qtyOrderedBase / qtyOrdered`), so a base-unit line has factor 1 and nothing changes for it.
 */

/** The two fields of an order line the factor is derived from (wire values are strings). */
export interface OrderedQuantities {
  qtyOrdered: string | number | null | undefined;
  qtyOrderedBase: string | number | null | undefined;
}

/** Base units in ONE line unit; 1 for a base-unit line (or a line with no usable quantity). */
export function lineFactor(line: OrderedQuantities): number {
  const ordered = Number(line.qtyOrdered ?? 0);
  const orderedBase = Number(line.qtyOrderedBase ?? 0);
  if (!Number.isFinite(ordered) || !Number.isFinite(orderedBase) || ordered <= 0 || orderedBase <= 0) {
    return 1;
  }
  return orderedBase / ordered;
}

/**
 * A base quantity expressed in the line's unit, rounded to the 6 decimals the server stores.
 * `factor` is base units per line unit (from {@link lineFactor} or a delivery line's
 * `factorToBase`); a missing or non-positive factor is treated as 1.
 */
export function baseToUnit(baseQty: string | number | null | undefined, factor: number | string | null | undefined): number {
  const base = Number(baseQty ?? 0);
  const f = Number(factor ?? 1);
  if (!Number.isFinite(base)) return 0;
  if (!Number.isFinite(f) || f <= 0) return round6(base);
  return round6(base / f);
}

function round6(n: number): number {
  return Math.round(n * 1e6) / 1e6;
}
