package com.erp.modules.stock.domain.dto;

import java.math.BigDecimal;

/**
 * Available-to-promise read for a single (company, branch, product) at the branch default
 * location — {@code quantity − reserved_qty} (ADR-0021 D-5 formula, reused here for the
 * "block negative stock on sale" guard, owner decision 2026-07-05).
 *
 * <p>Cross-module read contract: the sales module calls
 * {@link com.erp.modules.stock.service.StockReservationService#getAvailability} to get this DTO
 * rather than importing the {@code StockOnHand} entity (module-boundary rule — only
 * {@code domain.dto}/{@code domain.enums} cross module lines).
 *
 * <p>Missing on-hand row (first-touch product) resolves to zero quantity / zero reserved / zero
 * available — never blocks a first sale on a technicality, and never null-pointers the caller.
 */
public record StockAvailabilityDto(
        Long companyId,
        Long branchId,
        Long productId,
        BigDecimal quantity,
        BigDecimal reservedQty,
        BigDecimal availableQty
) {

    public static StockAvailabilityDto zero(Long companyId, Long branchId, Long productId) {
        return new StockAvailabilityDto(
                companyId, branchId, productId, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
    }
}
