package com.erp.modules.stock.domain.dto;

import com.erp.modules.stock.domain.entity.StockMovement;
import com.erp.modules.stock.domain.enums.MovementType;
import java.math.BigDecimal;

/**
 * Response DTO for a single stock movement ledger row (ADR-0010 D-11).
 *
 * <p>Direction is derived from the sign of {@code quantity} (D-6: direction is not stored).
 * A {@code positive} quantity is stock-in; negative is stock-out.
 */
public record StockMovementDto(
        Long id,
        String uid,
        Long companyId,
        Long branchId,
        Long productId,
        MovementType movementType,
        /** Signed delta in base units. Positive = in, negative = out. */
        BigDecimal quantity,
        /** Derived from quantity sign for UI convenience. */
        String direction,
        String sourceEventUid,
        String sourceDocumentType,
        String sourceDocumentUid,
        String reasonCode,
        String note,
        String occurredAt,
        String createdAt,
        Long createdBy
) {

    public static StockMovementDto from(StockMovement m) {
        String dir = m.getQuantity().compareTo(BigDecimal.ZERO) > 0 ? "IN" : "OUT";
        return new StockMovementDto(
                m.getId(),
                m.getUid(),
                m.getCompanyId(),
                m.getBranchId(),
                m.getProductId(),
                m.getMovementType(),
                m.getQuantity(),
                dir,
                m.getSourceEventUid(),
                m.getSourceDocumentType(),
                m.getSourceDocumentUid(),
                m.getReasonCode(),
                m.getNote(),
                m.getOccurredAt() != null ? m.getOccurredAt().toString() : null,
                m.getCreatedAt() != null ? m.getCreatedAt().toString() : null,
                m.getCreatedBy()
        );
    }
}
