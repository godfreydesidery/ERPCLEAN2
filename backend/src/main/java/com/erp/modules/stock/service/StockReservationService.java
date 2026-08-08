package com.erp.modules.stock.service;

import com.erp.modules.stock.domain.dto.StockAvailabilityDto;
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

    /**
     * Serialised reserve: takes the branch reservation row under a {@code SELECT … FOR UPDATE}
     * lock, reads available-to-promise <em>under that lock</em>, adds {@code qty} to
     * {@code reserved_qty}, and returns the availability as it stood <strong>before</strong> the
     * reservation was applied.
     *
     * <p>This is the synchronous half of the negative-stock decision. A sale's quantity is not
     * deducted until the outbox poller runs the stock handler ~a second later, so a plain read of
     * {@code stock_on_hand} lets every sale inside that window see the same pre-sale quantity: with
     * 198 on hand and blocking switched on, eight consecutive sales of 30 were all accepted and left
     * −42. Reserving here, in the same transaction as the check and behind the row lock, means the
     * next caller blocks until this one commits and then sees this sale's claim.
     *
     * <p>The reservation is a <em>claim on stock already sold but not yet issued</em>; the stock
     * handler releases it (negative delta) in the same transaction that posts the movement, so the
     * two changes are never visible apart. A caller that rejects the sale simply throws — its
     * transaction rolls back and takes the reservation with it, which is why this method reserves
     * unconditionally rather than deciding for the caller.
     *
     * <p><strong>Deadlocks.</strong> One row is locked per product, and a document with several
     * products locks them in the order its caller iterates. Two documents sharing two products in
     * opposite order can therefore deadlock; PostgreSQL detects it and aborts one transaction, which
     * rolls the whole sale back — safe (nothing is oversold), visible, and rare. Single-product
     * sales, the case this fixes, never contend beyond the one row.
     *
     * @return availability observed under the lock, before this reservation was added
     */
    StockAvailabilityDto reserve(Long companyId, Long branchId, Long productId,
                                 BigDecimal qty, Long actorId);

    /**
     * Read-only available-to-promise for a (company, branch, product) at the branch default
     * location: {@code quantity − reserved_qty}. Does not mutate anything. Missing on-hand row
     * (product never touched at this branch) resolves to all-zero (never blocks on a technicality).
     *
     * <p>Used by the sales module's negative-stock guard (owner decision 2026-07-05) — the
     * cross-module DTO-returning read that keeps the module boundary intact (no {@code StockOnHand}
     * entity crosses into sales).
     */
    StockAvailabilityDto getAvailability(Long companyId, Long branchId, Long productId);
}
