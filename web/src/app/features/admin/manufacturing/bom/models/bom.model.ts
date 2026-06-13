/**
 * BOM module DTOs — mirror backend BomDto / BomComponentDto / enums.
 * ALL Long / BigDecimal fields are typed string (stringified on the wire).
 */

export type BomStatus = 'DRAFT' | 'ACTIVE' | 'ARCHIVED';

export type ComponentSourcing = 'MAKE' | 'BUY';

// ── Child: BomComponentDto ───────────────────────────────────────────────────

export interface BomComponentDto {
  id: string;
  uid: string;
  bomId: string;
  companyId: string;
  lineNo: string;
  componentProductId: string;
  componentProductCode: string;
  componentProductName: string;
  qtyPer: string;
  sourcing: ComponentSourcing;
  scrapPercent: string;
  reference: string;
  createdAt: string;
  createdBy: string;
  updatedAt: string;
  updatedBy: string;
}

// ── Primary: BomDto ──────────────────────────────────────────────────────────

export interface BomDto {
  id: string;
  uid: string;
  companyId: string;
  parentProductId: string;
  parentProductUid: string;
  versionNo: string;
  status: BomStatus;
  outputQty: string;
  yieldPercent: string;
  effectiveFrom: string;
  effectiveTo: string;
  sourceBomUid: string;
  notes: string;
  activatedAt: string;
  archivedAt: string;
  version: string;
  createdAt: string;
  createdBy: string;
  updatedAt: string;
  updatedBy: string;
  components: BomComponentDto[];
}

// ── Request: CreateBomRequest ────────────────────────────────────────────────

export interface CreateBomRequest {
  parentProductUid: string;
  outputQty: string;
  yieldPercent?: string;
  notes?: string;
  cloneFromBomUid?: string;
}

// ── Request: UpdateBomRequest ────────────────────────────────────────────────

export interface UpdateBomRequest {
  outputQty?: string;
  yieldPercent?: string;
  notes?: string;
}

// ── Request: AddBomComponentRequest ─────────────────────────────────────────

export interface AddBomComponentRequest {
  componentProductUid: string;
  qtyPer: string;
  sourcing?: ComponentSourcing;
  scrapPercent?: string;
  reference?: string;
}

// ── Request: UpdateBomComponentRequest ──────────────────────────────────────

export interface UpdateBomComponentRequest {
  qtyPer?: string;
  sourcing?: ComponentSourcing;
  scrapPercent?: string;
  reference?: string;
}

// ── Request: ActivateBomRequest ──────────────────────────────────────────────

export interface ActivateBomRequest {
  effectiveFrom: string;
}
