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
     * Location-aware posting primitive (ADR-0028 D-3). The primary overload post-V37.
     *
     * @param companyId          tenant company
     * @param branchId           tenant branch
     * @param locationId         physical location (ADR-0028 D-3); use LocationResolver.defaultLocationId
     *                           for paths that do not specify one
     * @param productId          the product whose on-hand changes
     * @param quantity           signed delta in base units (non-zero)
     * @param movementType       movement category (TRANSFER_* now admitted — D-3)
     * @param sourceEventUid     originating domain_events.uid (null for manual movements)
     * @param sourceDocumentType SALES_INVOICE / GOODS_RECEIPT / STOCK_COUNT / STOCK_TRANSFER (null for manual)
     * @param sourceDocumentUid  the document uid (null for manual)
     * @param reasonCode         AdjustmentReason name (mandatory for ADJUSTMENT, null otherwise)
     * @param note               optional free-text
     * @param occurredAt         business time (event time for event-driven; now() for manual)
     * @param actorId            operator user id for manual movements; null for system/event-driven
     * @param unitCostAmount     unit cost for this movement (ADR-0020 D-2); null = no cost recorded
     * @param valueAmount        signed value = qty × unit_cost, HALF_UP 4dp; null = no cost recorded
     * @return the uid of the newly created StockMovement row
     */
    String post(Long companyId, Long branchId, Long locationId, Long productId,
                BigDecimal quantity, MovementType movementType,
                String sourceEventUid, String sourceDocumentType, String sourceDocumentUid,
                String reasonCode, String note, Instant occurredAt, Long actorId,
                BigDecimal unitCostAmount, BigDecimal valueAmount);

    /**
     * Location-aware extended overload with dimension default ids (ADR-0025 D-6 + ADR-0028 D-3).
     */
    String post(Long companyId, Long branchId, Long locationId, Long productId,
                BigDecimal quantity, MovementType movementType,
                String sourceEventUid, String sourceDocumentType, String sourceDocumentUid,
                String reasonCode, String note, Instant occurredAt, Long actorId,
                BigDecimal unitCostAmount, BigDecimal valueAmount,
                Long costCentreValueId, Long departmentValueId);

    /**
     * Legacy location-unaware overload — routes to the default location via the impl.
     * @deprecated Prefer the location-aware overload; this is retained for backward compatibility.
     */
    @Deprecated
    String post(Long companyId, Long branchId, Long productId,
                BigDecimal quantity, MovementType movementType,
                String sourceEventUid, String sourceDocumentType, String sourceDocumentUid,
                String reasonCode, String note, Instant occurredAt, Long actorId,
                BigDecimal unitCostAmount, BigDecimal valueAmount);

    /**
     * Legacy extended overload with dimension ids but no locationId.
     * @deprecated Prefer the location-aware overload.
     */
    @Deprecated
    String post(Long companyId, Long branchId, Long productId,
                BigDecimal quantity, MovementType movementType,
                String sourceEventUid, String sourceDocumentType, String sourceDocumentUid,
                String reasonCode, String note, Instant occurredAt, Long actorId,
                BigDecimal unitCostAmount, BigDecimal valueAmount,
                Long costCentreValueId, Long departmentValueId);
}
