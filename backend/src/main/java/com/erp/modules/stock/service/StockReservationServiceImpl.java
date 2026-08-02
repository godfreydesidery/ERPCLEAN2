package com.erp.modules.stock.service;

import com.erp.modules.stock.domain.dto.StockAvailabilityDto;
import com.erp.modules.stock.domain.entity.StockOnHand;
import com.erp.modules.stock.repository.StockOnHandRepository;
import java.math.BigDecimal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Soft-reservation primitive for stock_on_hand.reserved_qty (ADR-0021 D-5).
 *
 * <p>Mutates reserved_qty only — no stock_movements row, no GL entry (BR-SO-03).
 * Over-reservation (available < 0) is allowed and not blocked here (OQ-SO-02).
 *
 * <p>Concurrency: uses the @Version optimistic lock already on stock_on_hand with one retry
 * on ObjectOptimisticLockingFailureException (the ADR-0020 D-2 precedent, NFR-SO-05).
 */
@Service
@Transactional(propagation = Propagation.MANDATORY)
public class StockReservationServiceImpl implements StockReservationService {

    private static final Logger log = LoggerFactory.getLogger(StockReservationServiceImpl.class);

    private final StockOnHandRepository onHands;
    private final LocationResolver       locationResolver;

    public StockReservationServiceImpl(StockOnHandRepository onHands,
                                       LocationResolver locationResolver) {
        this.onHands          = onHands;
        this.locationResolver = locationResolver;
    }

    @Override
    public void applyReservationDelta(Long companyId, Long branchId, Long productId,
                                      BigDecimal delta, Long actorId) {
        try {
            doApply(companyId, branchId, productId, delta, actorId);
        } catch (ObjectOptimisticLockingFailureException ex) {
            log.warn("StockReservationService: optimistic lock conflict for company={} product={} — retrying once",
                    companyId, productId);
            doApply(companyId, branchId, productId, delta, actorId);
        }
    }

    // Not readOnly: locationResolver.defaultLocationId() lazily seeds a branch default location on
    // first touch (a write) — mirrors applyReservationDelta's own (non-readOnly) MANDATORY join.
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public StockAvailabilityDto getAvailability(Long companyId, Long branchId, Long productId) {
        // Sum EVERY location in the branch, not just the default one.
        //
        // Reading the default location alone was safe while nothing enforced the answer. Once the
        // negative-stock block went live it became a false refusal: a branch holding goods in a
        // receiving bay, a transit location or any second store reads as zero and the till turns
        // the customer away while the stock is on the shelf. Seen in production on 2026-08-02 —
        // TEST 3 had 2 units at TRANSIT-KILI003 and 0 at the branch default, and every sale was
        // rejected as "out of stock".
        //
        // Stock is fungible within a branch: what the cashier can sell is what the branch holds,
        // regardless of which bin the paperwork put it in. Reservations still live on the
        // default-location row (ADR-0028 D-3), so summing them changes nothing today and stays
        // correct if that ever spreads.
        var rows = onHands.findAllByCompanyIdAndBranchIdAndProductId(companyId, branchId, productId);
        if (rows.isEmpty()) {
            return StockAvailabilityDto.zero(companyId, branchId, productId);
        }
        BigDecimal qty = BigDecimal.ZERO;
        BigDecimal reserved = BigDecimal.ZERO;
        for (StockOnHand soh : rows) {
            qty = qty.add(soh.getQuantity());
            reserved = reserved.add(soh.getReservedQty());
        }
        return new StockAvailabilityDto(companyId, branchId, productId,
                qty, reserved, qty.subtract(reserved));
    }

    private void doApply(Long companyId, Long branchId, Long productId,
                         BigDecimal delta, Long actorId) {
        // ADR-0028 D-3: reservations are tracked on the branch default-location row.
        // Using the deprecated location-agnostic finder causes NonUniqueResultException when the
        // product is stocked across more than one location (issue #5). Always resolve the default
        // location first and use the location-aware 4-key finder so exactly one row is targeted.
        Long locId = locationResolver.defaultLocationId(companyId, branchId);
        StockOnHand soh = onHands
                .findByCompanyIdAndBranchIdAndLocationIdAndProductId(companyId, branchId, locId, productId)
                .orElseGet(() -> onHands.saveAndFlush(new StockOnHand(companyId, branchId, locId, productId)));

        BigDecimal newReserved = soh.getReservedQty().add(delta);
        if (newReserved.compareTo(BigDecimal.ZERO) < 0) {
            // Safety: cannot go below zero (over-release). Clamp to zero and warn.
            log.warn("StockReservationService: release would take reserved_qty below 0 for " +
                             "company={} branch={} product={} current={} delta={} — clamping to 0",
                    companyId, branchId, productId, soh.getReservedQty(), delta);
            newReserved = BigDecimal.ZERO;
        }
        soh.applyReservationDelta(newReserved.subtract(soh.getReservedQty()), actorId);
        onHands.save(soh);
    }
}
