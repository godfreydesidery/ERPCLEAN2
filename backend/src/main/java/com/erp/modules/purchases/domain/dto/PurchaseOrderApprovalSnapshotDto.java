package com.erp.modules.purchases.domain.dto;

import com.erp.modules.purchases.domain.enums.PoApprovalStatus;
import com.erp.modules.purchases.domain.enums.PurchaseOrderOrigin;

/**
 * The two facts another module needs about a purchase order — where it came from, and where its
 * approval stands — read straight off the stored row.
 *
 * <p><b>Why this exists.</b> {@code PurchaseOrderService#getByUid} is not a pure read: it polls the
 * approval engine and writes the answer back onto the order (plus an audit row) so a decision taken
 * in the generic Approvals inbox lands on the PO. That is right for a detail read or a placement
 * check, and wrong for a listing — a list page must not mutate anything, and inside a
 * {@code readOnly} transaction Hibernate's MANUAL flush mode silently discards the write anyway, so
 * the reconcile costs an engine round-trip per row and persists nothing.
 *
 * <p>This projection is the cheap alternative: no engine poll, no mutation, no audit row, one query
 * for a whole page. Callers that need a freshly reconciled status (payment release, placement) must
 * keep using {@code getByUid}.
 *
 * <p>{@code approvalStatus} is the stored enum verbatim, including {@link PoApprovalStatus#NOT_REQUIRED}
 * — unlike {@link PurchaseOrderDto}, which collapses that to null on the wire. This DTO is a
 * module-to-module read, not a wire payload, so it does not hide the distinction.
 */
public record PurchaseOrderApprovalSnapshotDto(
        String uid,
        Long companyId,
        PurchaseOrderOrigin origin,
        PoApprovalStatus approvalStatus
) {
}
