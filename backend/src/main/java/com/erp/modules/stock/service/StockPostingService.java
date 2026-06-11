package com.erp.modules.stock.service;

import com.erp.modules.stock.domain.enums.MovementType;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * The single internal posting primitive (ADR-0010 D-4).
 *
 * <p>Every path that changes stock — the four event handlers and the two manual ops — funnels
 * through this service. One call: (1) appends a {@code stock_movements} row, (2) upserts the
 * matching {@code stock_on_hand} quantity by the signed delta. Both happen in one transaction.
 *
 * <p>On-hand may go negative (no block — BR-STOCK-03). The optimistic {@code version} on
 * {@link com.erp.modules.stock.domain.entity.StockOnHand} guards concurrent updates (NFR-STOCK-04).
 *
 * <p>ADR-0020 D-2: two trailing nullable cost params added. Existing callers that post no cost
 * pass {@code null, null}. The cost is stored immutably on the movement row for exact reversal.
 */
public interface StockPostingService {

    /**
     * Append a movement row and update on-hand in the caller's current transaction.
     *
     * @param companyId          tenant company
     * @param branchId           tenant branch
     * @param productId          the product whose on-hand changes (must be stockable — caller's
     *                           responsibility to enforce D-3; D-9 enforcement split)
     * @param quantity           signed delta in base units (non-zero)
     * @param movementType       one of the six v1 types (TRANSFER_* excluded — D-4)
     * @param sourceEventUid     originating domain_events.uid (null for manual movements)
     * @param sourceDocumentType SALES_INVOICE / GOODS_RECEIPT (null for manual)
     * @param sourceDocumentUid  invoice or receipt uid (null for manual)
     * @param reasonCode         AdjustmentReason name (mandatory for ADJUSTMENT, null otherwise)
     * @param note               optional free-text
     * @param occurredAt         business time (event time for event-driven; now() for manual)
     * @param actorId            operator user id for manual movements; null for system/event-driven
     * @param unitCostAmount     unit cost for this movement (ADR-0020 D-2); null = no cost recorded
     * @param valueAmount        signed value = qty × unit_cost, HALF_UP 4dp; null = no cost recorded
     * @return the uid of the newly created StockMovement row
     */
    String post(Long companyId, Long branchId, Long productId,
                BigDecimal quantity, MovementType movementType,
                String sourceEventUid, String sourceDocumentType, String sourceDocumentUid,
                String reasonCode, String note, Instant occurredAt, Long actorId,
                BigDecimal unitCostAmount, BigDecimal valueAmount);

    /**
     * Extended overload with dimension default ids (ADR-0025 D-6, V28).
     * Both dimension ids are nullable — untagged when null (NFR-CC-01 zero-regression guarantee).
     */
    String post(Long companyId, Long branchId, Long productId,
                BigDecimal quantity, MovementType movementType,
                String sourceEventUid, String sourceDocumentType, String sourceDocumentUid,
                String reasonCode, String note, Instant occurredAt, Long actorId,
                BigDecimal unitCostAmount, BigDecimal valueAmount,
                Long costCentreValueId, Long departmentValueId);
}
