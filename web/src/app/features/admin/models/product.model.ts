/**
 * Product feature models — mirrors backend DTOs exactly.
 * All Long id fields are typed `string` (wire contract: Jackson stringifies Longs).
 * Money follows ADR-0005 D-7: { amount: string; currency: string }.
 */

// ── Enums ──────────────────────────────────────────────────────────────────────

export type ProductType = 'GOODS' | 'SERVICE';
export type ProductStatus = 'ACTIVE' | 'INACTIVE' | 'ARCHIVED';
export type UomStatus = 'ACTIVE' | 'INACTIVE' | 'ARCHIVED';
/** VAT classification for a product. Mirrors VatStatus on the backend. */
export type VatStatus = 'STANDARD' | 'ZERO_RATED' | 'EXEMPT';

// ── UnitOfMeasureDto ──────────────────────────────────────────────────────────

export interface UnitOfMeasureDto {
  id: string;
  uid: string;
  companyId: string;
  code: string;
  name: string;
  status: UomStatus;
  version: string | null;
  createdAt: string | null;
  createdBy: string | null;
  updatedAt: string | null;
  updatedBy: string | null;
}

export interface CreateUnitOfMeasureRequest {
  companyUid: string;
  code: string;
  name: string;
}

export interface UpdateUnitOfMeasureRequest {
  name: string;
}

// ── Money (ADR-0005 D-7) ──────────────────────────────────────────────────────

export interface Money {
  amount: string;
  currency: string;
}

// ── ProductDto ────────────────────────────────────────────────────────────────

export interface ProductModel {
  id: string;
  uid: string;
  companyId: string;
  code: string;
  name: string;
  description: string | null;
  type: ProductType;
  sellable: boolean;
  stockable: boolean;
  /** Replaced by FK references; backend now returns baseUnitUid / baseUnitCode / baseUnitName. */
  baseUnitUid: string;
  baseUnitCode: string;
  baseUnitName: string;
  cost: Money | null;
  /** VAT classification — added in sales module iteration. Defaults to STANDARD. */
  vatStatus: VatStatus;
  status: ProductStatus;
  /** Free-text department / category label. */
  category?: string | null;
  brand?: string | null;
  manufacturer?: string | null;
  purchasable?: boolean;
  preferredSupplierId?: string | null;
  hsCode?: string | null;
  imageUrl?: string | null;
  notes?: string | null;
  lotTracked?: boolean;
  serialTracked?: boolean;
  expiryTracked?: boolean;
  reorderLevel?: string | null;
  reorderQty?: string | null;
  safetyStock?: string | null;
  minStock?: string | null;
  maxStock?: string | null;
  version: string | null;
  createdAt: string | null;
  createdBy: string | null;
  updatedAt: string | null;
  updatedBy: string | null;
}

// ── CreateProductRequest ──────────────────────────────────────────────────────
// NOTE: uses companyUid (not companyId) — backend contract for create only.

export interface CreateProductRequest {
  companyUid: string;
  /** Optional; blank → backend auto-assigns PROD-####. */
  code?: string;
  name: string;
  description?: string;
  type: ProductType;
  sellable: boolean;
  stockable: boolean;
  /** uid of a units_of_measure row scoped to the same company. */
  baseUnitUid: string;
  cost?: Money;
  /** VAT classification. Defaults to STANDARD on the backend if omitted. */
  vatStatus?: VatStatus;
  /** Free-text product department / category label. No FK — not linked to HR departments. */
  category?: string;
  /** Optional brand / trade name. */
  brand?: string;
  /** Optional manufacturer name. */
  manufacturer?: string;
  /** Whether the product can be purchased. Default true. */
  purchasable?: boolean;
  /** Numeric party id (wire: string) of the preferred supplier. */
  preferredSupplierId?: string;
  /** HS tariff code for customs / EFD. */
  hsCode?: string;
  /** Public image URL for POS / catalogue display. */
  imageUrl?: string;
  /** Internal free-text notes. */
  notes?: string;
  /** Whether stock is tracked by lot number. Default false. */
  lotTracked?: boolean;
  /** Whether stock is tracked by serial number. Default false. */
  serialTracked?: boolean;
  /** Whether expiry dates are tracked. Default false. */
  expiryTracked?: boolean;
  /** Product-level default reorder point (units). */
  reorderLevel?: string;
  /** Product-level default replenishment quantity (units). */
  reorderQty?: string;
  /** Safety stock buffer quantity. */
  safetyStock?: string;
  /** Minimum allowed stock level. */
  minStock?: string;
  /** Maximum allowed stock level. */
  maxStock?: string;
}

// ── UpdateProductRequest ──────────────────────────────────────────────────────

export interface UpdateProductRequest {
  name: string;
  description?: string;
  type: ProductType;
  sellable: boolean;
  stockable: boolean;
  /** uid of a units_of_measure row scoped to the same company. */
  baseUnitUid: string;
  cost?: Money;
  /** VAT classification. Defaults to STANDARD on the backend if omitted. */
  vatStatus?: VatStatus;
  /** Free-text product department / category label. No FK. */
  category?: string;
  brand?: string;
  manufacturer?: string;
  purchasable?: boolean;
  preferredSupplierId?: string;
  hsCode?: string;
  imageUrl?: string;
  notes?: string;
  lotTracked?: boolean;
  serialTracked?: boolean;
  expiryTracked?: boolean;
  reorderLevel?: string;
  reorderQty?: string;
  safetyStock?: string;
  minStock?: string;
  maxStock?: string;
}

// ── ProductBarcodeDto ─────────────────────────────────────────────────────────

export interface ProductBarcodeDto {
  id: string;
  uid: string;
  productId: string;
  companyId: string;
  barcode: string;
  primary: boolean;
}

export interface AddBarcodeRequest {
  barcode: string;
  /** Kept for product-detail.component compat; product-master uses isPrimary per contract. */
  primary?: boolean;
  /** Canonical field name per confirmed backend contract. */
  isPrimary?: boolean;
  /** Barcode type (EAN_13, QR, UPC_A, …). Optional. */
  barcodeType?: string;
  /** Unit of measure uid this barcode is keyed to. Optional. */
  uomUid?: string;
}

// ── ProductBulkPackDto ────────────────────────────────────────────────────────

export interface ProductBulkPackDto {
  id: string;
  uid: string;
  productId: string;
  /** uid / code / name of the units_of_measure row (replaces free-text name). */
  unitUid: string;
  unitCode: string;
  unitName: string;
  factorToBase: string;
}

export interface CreateBulkPackRequest {
  /** uid of a units_of_measure row scoped to the same company. */
  unitUid: string;
  factorToBase: string;
}

// ── ProductPriceDto ───────────────────────────────────────────────────────────

export interface ProductPriceDto {
  id: string;
  productId: string;
  priceListId: string;
  priceListUid: string;
  priceListCode: string;
  priceListName: string;
  companyId: string;
  price: Money;
}

export interface SetProductPriceRequest {
  priceListUid: string;
  price: Money;
  /** ISO-8601 date string. Default = today. Backend contract confirmed. */
  effectiveFrom?: string;
}

// ── ProductComponentDto ───────────────────────────────────────────────────────

export interface ProductComponentDto {
  id: string;
  composedProductId: string;
  componentProductId: string;
  componentProductUid: string;
  componentProductCode: string;
  componentProductName: string;
  quantity: string;
}

export interface AddComponentRequest {
  componentProductUid: string;
  quantity: string;
}

// ── ProductBranchDto ──────────────────────────────────────────────────────────

export interface ProductBranchDto {
  branchId: string;
  assignedAt: string | null;
  assignedBy: string | null;
  /** Whether the product is active in this branch (confirmed contract). */
  active?: boolean;
  /** Per-branch reorder override (confirmed contract). */
  reorderLevel?: string;
  /** Per-branch price override (confirmed contract). */
  branchPrice?: string;
}

export interface AssignProductBranchRequest {
  branchUid: string;
  /** Whether the product is active/available in this branch. Default true. */
  active?: boolean;
  /** Per-branch reorder level override. */
  reorderLevel?: string;
  /** Per-branch selling price override. */
  branchPrice?: string;
}

// ── PriceListDto ──────────────────────────────────────────────────────────────

export interface PriceListDto {
  id: string;
  uid: string;
  companyId: string;
  code: string;
  name: string;
  isDefault: boolean;
  status: string;
  version: string | null;
  createdAt: string | null;
  createdBy: string | null;
  updatedAt: string | null;
  updatedBy: string | null;
}

export interface CreatePriceListRequest {
  companyUid: string;
  code: string;
  name: string;
}

export interface UpdatePriceListRequest {
  name: string;
}
