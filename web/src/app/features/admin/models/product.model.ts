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
  primary: boolean;
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
}

export interface AssignProductBranchRequest {
  branchUid: string;
}

// ── PriceListDto ──────────────────────────────────────────────────────────────

export interface PriceListDto {
  id: string;
  uid: string;
  companyId: string;
  code: string;
  name: string;
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
