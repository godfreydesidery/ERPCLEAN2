/**
 * Item Inquiry — the counter lookup (K-2026-08-30 #3). Mirrors the backend ItemInquiryDto.
 *
 * Money and quantity are BigDecimal on the backend, which serialise as JSON NUMBERS. `null` is a
 * REAL answer, not a zero: a null `buyingPrice` means the item has never been costed (or that the
 * caller may not see cost — `costVisible` separates the two), a null `sellingPrice` means it has
 * never been priced. Format them through `formatInquiryAmount`, never a money pipe that would turn
 * an unknown into "0.00" and tell a shopkeeper the goods are free.
 */

export interface ItemInquiryRowDto {
  productUid: string;
  productCode: string | null;
  productName: string | null;
  /** The base unit the quantity is expressed in — "12" means nothing on its own. */
  unitName: string | null;
  quantityOnHand: number | string | null;
  /** False for a service or other non-stocked line: the quantity is meaningless, not zero. */
  stockable: boolean;
  buyingPrice: number | string | null;
  sellingPrice: number | string | null;
}

export interface ItemInquiryDto {
  /** null = every branch in the company. */
  branchName: string | null;
  currency: string;
  /** null when the company has no default price list — every selling price will be blank. */
  priceListName: string | null;
  priceIncludesVat: boolean;
  /** False when the caller may not see cost; the cost column is then withheld, not unknown. */
  costVisible: boolean;
  /** True when more items matched than were returned — ask for a narrower search. */
  truncated: boolean;
  rows: ItemInquiryRowDto[];
}

/**
 * Money that may genuinely be UNKNOWN — same rule as the product-stock reports: an absent figure is
 * an em dash, a real zero still prints 0.00.
 */
export function formatInquiryAmount(v: number | string | null | undefined): string {
  if (v === null || v === undefined || v === '') return '—';
  const n = +v;
  return Number.isFinite(n)
    ? n.toLocaleString('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2 })
    : '—';
}

/**
 * Quantity, in the product's base unit. Up to 3 dp: rounding to whole numbers made 20 loose pieces
 * under a carton base unit print as "0", and the shop read it as out of stock (K4).
 */
export function formatInquiryQty(v: number | string | null | undefined): string {
  const n = +(v ?? 0);
  return Number.isFinite(n)
    ? n.toLocaleString('en-US', { minimumFractionDigits: 0, maximumFractionDigits: 3 })
    : '0';
}
