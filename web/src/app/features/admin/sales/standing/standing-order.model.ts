/**
 * Standing order feature models — mirrors backend DTOs exactly (ADR-0029 D-8).
 * All Long/BigDecimal fields are typed `string` (wire contract: Jackson stringifies Longs).
 */

// ── Enums ──────────────────────────────────────────────────────────────────────

export type StandingFrequency = 'DAILY' | 'WEEKLY' | 'BIWEEKLY' | 'MONTHLY';
export type StandingStatus = 'ACTIVE' | 'PAUSED' | 'CANCELLED';

// ── StandingOrderLineDto ──────────────────────────────────────────────────────

export interface StandingOrderLineDto {
  id: string;
  uid: string;
  standingOrderId: string;
  lineNo: number;
  productId: string;
  productCode: string;
  productName: string;
  unitId: string;
  unitName: string;
  qty: string;
  qtyBase: string;
  unitPriceAmount: string;
  currency: string;
}

// ── StandingOrderDto ──────────────────────────────────────────────────────────

export interface StandingOrderDto {
  id: string;
  uid: string;
  companyId: string;
  branchId: string;
  orderNumber: string | null;
  customerId: string;
  currency: string;
  frequency: StandingFrequency;
  startDate: string;
  endDate: string | null;
  nextRunDate: string | null;
  status: StandingStatus;
  notes: string | null;
  lines: StandingOrderLineDto[];
}

// ── CreateStandingOrderRequest ────────────────────────────────────────────────

export interface CreateStandingOrderLineRequest {
  productId: string;
  unitId: string;
  qty: string;
  qtyBase: string;
  unitPriceAmount: string;
}

export interface CreateStandingOrderRequest {
  companyUid: string;
  branchId: string;
  customerId: string;
  currency: string;
  frequency: StandingFrequency;
  startDate: string;
  endDate?: string;
  lines: CreateStandingOrderLineRequest[];
  notes?: string;
}
