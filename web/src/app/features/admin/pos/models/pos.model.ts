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
  /** ADR-0068: till may keep selling while it cannot reach the server. */
  offlineAllowed?: boolean;
}

// ── Offline POS (ADR-0068) ────────────────────────────────────────────────────

export type OfflineReviewKind = 'PRICE_DIFFERENCE' | 'NEGATIVE_STOCK' | 'CLOSED_PERIOD' | 'BELOW_COST' | 'HELD';
export type OfflineReviewStatus = 'OPEN' | 'ACKNOWLEDGED';

export interface OfflineReviewDto {
  uid: string;
  kind: OfflineReviewKind;
  status: OfflineReviewStatus;
  offlineNumber: string | null;
  detail: string | null;
  tillName: string | null;
  invoiceUid: string | null;
  invoiceNumber: string | null;
  /** JSON string, HELD items only. Display only — never evaluated. */
  heldRequest: string | null;
  createdAt: string;
  acknowledgedByName: string | null;
  acknowledgedAt: string | null;
  acknowledgeNote: string | null;
}

export interface OfflinePeriodDto {
  uid: string;
  tillName: string | null;
  reason: 'UNREACHABLE' | 'MANUAL';
  switchedByName: string | null;
  startedAt: string;
  endedAt: string | null;
  salesCount: number;
}

// ── PosSessionDto ─────────────────────────────────────────────────────────────

export interface PosSessionDto {
  id: string;
  uid: string;
  /** Human-readable session number, e.g. POS-0001 (added in backend v2). */
  sessionNumber: string | null;
  companyId: string;
  branchId: string;
  /**
   * Human labels for the three ids above (UAT 2026-08). The session used to expose
   * `cashierId: "7"` / `posTillId: "1"` / `branchId: "1"` and nothing else, so a shift could not
   * be read without three cross-reference lookups. Null when the row behind the id could not be
   * resolved — render as "—", never fall back to printing the id.
   */
  branchName: string | null;
  posTillId: string;
  tillName: string | null;
  cashierId: string;
  cashierName: string | null;
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
  tillName: string | null;
  cashierId: string;
  cashierName: string | null;
  branchId: string;
  branchName: string | null;
  openedAt: string | null;
  openingFloatAmount: string;
  totalSalesAmount: string;
  totalPayoutsNetAmount: string;
  /** Null when withheld from a reader who may not settle the till (POS-04). */
  expectedCashAmount: string | null;
  invoiceCount: number;
}

// ── ZReadDto ──────────────────────────────────────────────────────────────────

export interface ZReadDto {
  sessionUid: string;
  posTillId: string;
  tillName: string | null;
  cashierId: string;
  cashierName: string | null;
  branchId: string;
  branchName: string | null;
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
  /**
   * K7 — optional. The `authoriserUid` from a successful manager step-up
   * (`POST /api/v1/auth/verify-authority` with permission `SALES.DISCOUNT.OVERRIDE`).
   *
   * Per LINE, not per sale: a basket may hold one heavily discounted item and nine ordinary ones,
   * and a single sale-level flag would let the approval for the first wave the rest through. Only
   * consulted when the company's discount policy is APPROVE and the line's discount exceeds the
   * ceiling; the server re-verifies the named user's authority in the invoice's company, so the
   * uid alone is never enough.
   */
  discountAuthorisedByUid?: string;
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
