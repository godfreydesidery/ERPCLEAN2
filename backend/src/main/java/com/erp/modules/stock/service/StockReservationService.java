package com.erp.modules.stock.service;

import java.math.BigDecimal;

/**
 * Soft-reservation primitive for the Order-to-Cash spine (ADR-0021 D-5).
 *
 * <p>Mutates {@code stock_on_hand.reserved_qty} only — no {@code stock_movements} row,
 * no GL entry (BR-SO-03). Available-to-promise = {@code quantity − reserved_qty}.
 *
 * <p>Over-reservation (reserved_qty > quantity → negative available) is allowed and flagged
 * (OQ-SO-02): backorders are supported.
 *
 * <p>Concurrency: uses the {@code @Version} optimistic lock already on {@code stock_on_hand}
 * with one retry on {@link org.springframework.orm.ObjectOptimisticLockingFailureException}
 * (the ADR-0020 D-2 precedent, NFR-SO-05).
 */
public interface StockReservationService {

    /**
     * Apply a delta to {@code reserved_qty} for the given (company, branch, product).
     * Positive delta = reserve; negative delta = release.
     * Upserts the on-hand row if absent (a reservation can precede any receipt — backorder).
     *
     * @param companyId tenant
     * @param branchId  branch
     * @param productId product
     * @param delta     signed change in reserved quantity (base units); positive = reserve, negative = release
     * @param actorId   the user performing the operation (null for system paths)
     */
    void applyReservationDelta(Long companyId, Long branchId, Long productId,
                               BigDecimal delta, Long actorId);
}
