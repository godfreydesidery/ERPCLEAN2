/**
 * Van-stock reconciliation feature models — mirrors backend DTOs exactly (ADR-0051 D-8).
 * All Long-id fields are typed `string` (wire: Jackson stringifies Longs).
 * Quantity/amount (BigDecimal) fields are typed `number` (wire: BigDecimal serialises as a JSON
 * number, unlike Long) — see the "Wire serialization: number vs string" lesson; never call
 * string ops (.trim/.startsWith) on these.
 */

// ── Enum ──────────────────────────────────────────────────────────────────────

export type VanReconciliationStatus = 'OPEN' | 'RECONCILED' | 'CANCELLED';

// ── Response DTOs ─────────────────────────────────────────────────────────────

export interface VanReconciliationLineDto {
  /** Backend Long id — not used for navigation (lines have no uid; identify by productUid). */
  id: string;
  lineNo: number;
  productUid: string;
  productName: string;
  /** Snapshot avg unit cost — report-only column, no GL impact (ADR-0051 D-8.2). */
  unitCostAmount: number | null;
  /** Derived from Σ TRANSFER_IN at the van in the business-date window; prefilled, editable. */
  loadedQty: number;
  /** Entered — the round's sold total per product (not derivable from the ledger). */
  soldQty: number;
  /** Derived from −Σ TRANSFER_OUT at the van in the window; prefilled, editable. */
  returnedQty: number;
  /** Computed + stored (immutable snapshot): loaded − sold − returned. */
  expectedQty: number;
  /** Entered day-end physical count on the van. Null until entered. */
  physicalQty: number | null;
  /** Computed: physical − expected. Positive = surplus/under-reported sales; negative = shortage. */
  varianceQty: number;
  /** Report-only: varianceQty × unitCostAmount. No GL posting. */
  varianceValue: number | null;
}

export interface VanReconciliationDto {
  id: string;
  uid: string;
  companyId: string;
  branchId: string;
  vanLocationUid: string;
  vanLocationName: string;
  agentUid: string | null;
  agentName: string | null;
  reconNumber: string;
  businessDate: string;
  status: VanReconciliationStatus;
  notes: string | null;
  lines: VanReconciliationLineDto[];
  reconciledAt: string | null;
}

// ── Request DTOs ───────────────────────────────────────────────────────────────

export interface CreateVanReconciliationRequest {
  vanLocationUid: string;
  /** ISO date yyyy-MM-dd */
  businessDate: string;
  notes?: string;
  /** Optional product uid subset; empty/omitted = all products with van movement in the window. */
  productUids?: string[];
}

export interface VanReconciliationLineEntry {
  productUid: string;
  soldQty: number;
  /** Null when the physical count hasn't been done yet — a sold-only entry still persists. */
  physicalQty: number | null;
  /** Override for the derived loaded qty; omit to keep the server-derived/prefilled value. */
  loadedQty?: number;
  /** Override for the derived returned qty; omit to keep the server-derived/prefilled value. */
  returnedQty?: number;
}

export interface EnterVanReconciliationLinesRequest {
  lines: VanReconciliationLineEntry[];
}
