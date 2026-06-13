/**
 * Blanket Order models — mirrors backend DTOs exactly.
 * All Long/BigDecimal fields typed `string` on the wire (Jackson stringifies).
 * Timestamps (LocalDate) arrive as ISO YYYY-MM-DD strings.
 * ADR-0029 D-7.
 */

// ── Enums ─────────────────────────────────────────────────────────────────────

export type BlanketStatus = 'ACTIVE' | 'EXHAUSTED' | 'CANCELLED';

// ── BlanketOrderLineDto ────────────────────────────────────────────────────────

export interface BlanketOrderLineDto {
  id: string;
  uid: string;
  blanketOrderId: string;
  lineNo: number;
  productId: string;
  productCode: string;
  productName: string;
  unitId: string;
  unitName: string;
  committedQtyBase: string;
  drawnQtyBase: string;
  remainingQtyBase: string;
  unitPriceAmount: string;
  currency: string;
}

// ── BlanketOrderDto ────────────────────────────────────────────────────────────

export interface BlanketOrderDto {
  id: string;
  uid: string;
  companyId: string;
  branchId: string;
  orderNumber: string | null;
  customerId: string;
  currency: string;
  validFrom: string;
  validTo: string;
  totalCommittedAmount: string;
  totalDrawnAmount: string;
  status: BlanketStatus;
  notes: string | null;
  lines: BlanketOrderLineDto[];
}

// ── Request DTOs ───────────────────────────────────────────────────────────────

export interface BlanketLineRequest {
  productId: string;
  unitId: string;
  committedQtyBase: string;
  unitPriceAmount: string;
}

export interface CreateBlanketOrderRequest {
  companyUid: string;
  branchId: string;
  customerId: string;
  currency: string;
  validFrom: string;
  validTo: string;
  lines: BlanketLineRequest[];
  notes?: string;
}

export interface DrawLineRequest {
  blanketLineUid: string;
  qtyBase: string;
}

export interface DrawBlanketRequest {
  blanketUid: string;
  branchId: string;
  agentId: string;
  lines: DrawLineRequest[];
}
