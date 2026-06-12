package com.erp.modules.stock.service;

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

    private void doApply(Long companyId, Long branchId, Long productId,
                         BigDecimal delta, Long actorId) {
        StockOnHand soh = onHands
                .findByCompanyIdAndBranchIdAndProductId(companyId, branchId, productId)
                .orElseGet(() -> {
                    // ADR-0028 D-3: location_id NOT NULL — resolve branch default before first save.
                    Long locId = locationResolver.defaultLocationId(companyId, branchId);
                    return onHands.saveAndFlush(new StockOnHand(companyId, branchId, locId, productId));
                });

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
