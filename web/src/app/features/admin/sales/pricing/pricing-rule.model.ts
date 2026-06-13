/**
 * Pricing-rule feature models — mirrors backend DTOs exactly (ADR-0029 D-6).
 * All Long/BigDecimal fields are typed `string` (wire contract: Jackson stringifies them).
 */

export type MasterStatus = 'ACTIVE' | 'INACTIVE' | 'ARCHIVED';

// ── PriceTierDto ──────────────────────────────────────────────────────────────

export interface PriceTierDto {
  id: string;
  uid: string;
  companyId: string;
  productId: string;
  priceListId: string;
  /** BigDecimal on wire */
  minQty: string;
  /** BigDecimal on wire */
  unitPriceAmount: string;
  currency: string;
  status: MasterStatus;
}

// ── CreatePriceTierRequest ────────────────────────────────────────────────────

export interface CreatePriceTierRequest {
  companyUid: string;
  productUid: string;
  priceListUid: string;
  minQty: string;
  unitPriceAmount: string;
  currency: string;
}

// ── CustomerPriceDto ──────────────────────────────────────────────────────────

export interface CustomerPriceDto {
  id: string;
  uid: string;
  companyId: string;
  customerId: string;
  productId: string;
  /** BigDecimal on wire */
  unitPriceAmount: string;
  currency: string;
  /** ISO date string */
  effectiveFrom: string | null;
  /** ISO date string */
  effectiveTo: string | null;
  status: MasterStatus;
}

// ── CreateCustomerPriceRequest ────────────────────────────────────────────────

export interface CreateCustomerPriceRequest {
  companyUid: string;
  customerUid: string;
  productUid: string;
  unitPriceAmount: string;
  currency: string;
  effectiveFrom: string | null;
  effectiveTo: string | null;
}
