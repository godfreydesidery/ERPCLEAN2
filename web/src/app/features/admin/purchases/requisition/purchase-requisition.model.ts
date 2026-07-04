/**
 * Purchase Requisition models — mirrors backend DTOs exactly.
 * All Long id fields typed `string` (Jackson stringifies on wire).
 * BigDecimal amounts typed `string`.
 * Instant → string (ISO-8601). LocalDate → string (YYYY-MM-DD).
 */

// ── Enum ──────────────────────────────────────────────────────────────────────

export type RequisitionStatus =
  | 'DRAFT'
  | 'SUBMITTED'
  | 'APPROVED'
  | 'REJECTED'
  | 'CONVERTED'
  | 'CANCELLED';

// ── PurchaseRequisitionLineDto ─────────────────────────────────────────────────

export interface PurchaseRequisitionLineDto {
  id: string;
  uid: string;
  lineNo: number;
  productId: string;
  productCode: string;
  productName: string;
  unitId: string;
  unitName: string;
  requestedQty: string;
  estimatedUnitCost: string | null;
  note: string | null;
}

// ── PurchaseRequisitionDto ─────────────────────────────────────────────────────

export interface PurchaseRequisitionDto {
  id: string;
  uid: string;
  companyId: string;
  branchId: string;
  requisitionNumber: string | null;
  status: RequisitionStatus;
  requiredByDate: string | null;
  costCentreCode: string | null;
  approvalRequestUid: string | null;
  approvalStatus: string | null;
  convertedToType: string | null;
  convertedToUid: string | null;
  notes: string | null;
  submittedAt: string | null;
  approvedAt: string | null;
  rejectedAt: string | null;
  convertedAt: string | null;
  cancelledAt: string | null;
  createdAt: string | null;
  lines: PurchaseRequisitionLineDto[];
}

// ── Request types ──────────────────────────────────────────────────────────────

export interface RequisitionLineRequest {
  /** Long productId as string; resolved from product uid in the create component. */
  productId: string;
  unitId: string;
  requestedQty: string;
  estimatedUnitCost?: string;
  note?: string;
}

export interface CreatePurchaseRequisitionRequest {
  companyUid: string;
  requiredByDate?: string;
  costCentreCode?: string;
  notes?: string;
  lines: RequisitionLineRequest[];
}

export interface RejectRequisitionRequest {
  reason: string;
}

export interface ConvertRequisitionRequest {
  targetType: 'PURCHASE_ORDER' | 'RFQ';
  /** Suppliers to invite — required (non-empty) when targetType === 'RFQ'. */
  supplierUids?: string[];
  /** Supplier to order from — required when targetType === 'PURCHASE_ORDER'. */
  supplierUid?: string;
  /** Optional PO currency override; blank defaults server-side to the company's base currency. */
  currency?: string;
}
