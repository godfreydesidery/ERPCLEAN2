/**
 * POS feature models — mirrors backend DTOs exactly (ADR-0029).
 * All Long/BigDecimal fields are typed `string` on the wire.
 */

// ── Enums ──────────────────────────────────────────────────────────────────────

export type PosSessionStatus = 'OPEN' | 'CLOSED' | 'RECONCILED';
export type PosPayoutType = 'REFUND' | 'PAID_OUT';
export type MasterStatus = 'ACTIVE' | 'INACTIVE' | 'ARCHIVED';

// ── PosTillDto ────────────────────────────────────────────────────────────────

export interface PosTillDto {
  id: string;
  uid: string;
  companyId: string;
  branchId: string;
  name: string;
  status: MasterStatus;
  /** True while a session in status OPEN holds this till. */
  hasOpenSession?: boolean;
  /** The occupying session — who holds the till and since when. Null when free. */
  openSessionUid?: string | null;
  openSessionCashierId?: string | null;
  openSessionOpenedAt?: string | null;
}

// ── PosSessionDto ─────────────────────────────────────────────────────────────

export interface PosSessionDto {
  id: string;
  uid: string;
  /** Human-readable session number, e.g. POS-0001 (added in backend v2). */
  sessionNumber: string | null;
  companyId: string;
  branchId: string;
  posTillId: string;
  cashierId: string;
  status: PosSessionStatus;
  openedAt: string | null;
  closedAt: string | null;
  reconciledAt: string | null;
  openingFloatAmount: string;
  countedCashAmount: string | null;
  expectedCashAmount: string | null;
  varianceAmount: string | null;
  varianceJournalId: string | null;
  notes: string | null;
}

// ── XReadDto ──────────────────────────────────────────────────────────────────

export interface XReadDto {
  sessionUid: string;
  posTillId: string;
  cashierId: string;
  openedAt: string | null;
  openingFloatAmount: string;
  totalSalesAmount: string;
  totalPayoutsNetAmount: string;
  expectedCashAmount: string;
  invoiceCount: number;
}

// ── ZReadDto ──────────────────────────────────────────────────────────────────

export interface ZReadDto {
  sessionUid: string;
  posTillId: string;
  cashierId: string;
  openedAt: string | null;
  closedAt: string | null;
  reconciledAt: string | null;
  openingFloatAmount: string;
  totalSalesAmount: string;
  totalPayoutsNetAmount: string;
  expectedCashAmount: string;
  countedCashAmount: string;
  varianceAmount: string;
  varianceJournalId: string | null;
  invoiceCount: number;
  notes: string | null;
}

// ── Request types ─────────────────────────────────────────────────────────────

export interface CreatePosTillRequest {
  companyUid: string;
  branchId: string;
  name: string;
}

export interface OpenSessionRequest {
  tillUid: string;
  openingFloatAmount: string;
}

export interface PosPayoutRequest {
  payoutType: PosPayoutType;
  amount: string;
  reason: string;
}

export interface CloseSessionRequest {
  countedCashAmount: string;
  notes?: string;
}

export interface ReconcileSessionRequest {
  notes?: string;
}

export interface PosSaleLineRequest {
  productId: string;
  unitId: string;
  quantity: string;
  unitPrice: string;
  lineDiscountAmount?: string;
}

export interface PosSaleRequest {
  sessionUid: string;
  customerId: string;
  agentId?: string;
  currency: string;
  lines: PosSaleLineRequest[];
  tenderedAmount: string;
  notes?: string;
}
