package com.erp.modules.stock.service;

import com.erp.modules.stock.domain.dto.StockAvailabilityDto;
import com.erp.modules.stock.domain.entity.StockLocation;
import com.erp.modules.stock.domain.entity.StockOnHand;
import com.erp.modules.stock.domain.enums.LocationType;
import com.erp.modules.stock.repository.StockLocationRepository;
import com.erp.modules.stock.repository.StockOnHandRepository;
import java.math.BigDecimal;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
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
 * <p>Concurrency: {@link #applyReservationDelta} uses the @Version optimistic lock already on
 * stock_on_hand with one retry on ObjectOptimisticLockingFailureException (the ADR-0020 D-2
 * precedent, NFR-SO-05). {@link #reserve} needs more than that — it must give a <em>reader</em> a
 * consistent answer, not merely detect a lost update — so it takes a pessimistic row lock and holds
 * it to commit.
 */
@Service
@Transactional(propagation = Propagation.MANDATORY)
public class StockReservationServiceImpl implements StockReservationService {

    private static final Logger log = LoggerFactory.getLogger(StockReservationServiceImpl.class);

    private final StockOnHandRepository   onHands;
    private final LocationResolver        locationResolver;
    private final StockLocationRepository locations;

    public StockReservationServiceImpl(StockOnHandRepository onHands,
                                       LocationResolver locationResolver,
                                       StockLocationRepository locations) {
        this.onHands          = onHands;
        this.locationResolver = locationResolver;
        this.locations        = locations;
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

    @Override
    public StockAvailabilityDto reserve(Long companyId, Long branchId, Long productId,
                                        BigDecimal qty, Long actorId) {
        Long locId = locationResolver.defaultLocationId(companyId, branchId);

        // Lock FIRST — before any other read of this row in the transaction. A PESSIMISTIC_WRITE
        // query on an already-managed entity returns the cached copy, so reading availability before
        // locking would re-introduce the very staleness the lock exists to remove.
        StockOnHand reservationRow = onHands
                .lockByCompanyBranchLocationProduct(companyId, branchId, locId, productId)
                .orElseGet(() -> onHands.saveAndFlush(new StockOnHand(companyId, branchId, locId, productId)));

        // Availability spans every location in the branch (stock is fungible within a branch — see
        // getAvailability); reservations all sit on the locked default-location row, so that one
        // lock still serialises every reserve for this (company, branch, product).
        BigDecimal qtyOnHand = BigDecimal.ZERO;
        BigDecimal reserved  = BigDecimal.ZERO;
        List<StockOnHand> rows = onHands.findAllByCompanyIdAndBranchIdAndProductId(companyId, branchId, productId);
        Set<Long> notSellable = notSellableLocationIds(companyId, branchId, rows);
        for (StockOnHand soh : rows) {
            if (!notSellable.contains(soh.getLocationId())) {
                qtyOnHand = qtyOnHand.add(soh.getQuantity());
            }
            reserved  = reserved.add(soh.getReservedQty());
        }
        StockAvailabilityDto before = new StockAvailabilityDto(
                companyId, branchId, productId, qtyOnHand, reserved, qtyOnHand.subtract(reserved));

        if (qty != null && qty.compareTo(BigDecimal.ZERO) > 0) {
            reservationRow.applyReservationDelta(qty, actorId);
            onHands.save(reservationRow);
        }
        return before;
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
        // regardless of which bin the paperwork put it in — except stock that is not on a shelf
        // at all (in transit, quarantined): see notSellableLocationIds (STK-06). Reservations still live on the
        // default-location row (ADR-0028 D-3), so summing them changes nothing today and stays
        // correct if that ever spreads.
        var rows = onHands.findAllByCompanyIdAndBranchIdAndProductId(companyId, branchId, productId);
        if (rows.isEmpty()) {
            return StockAvailabilityDto.zero(companyId, branchId, productId);
        }
        BigDecimal qty = BigDecimal.ZERO;
        BigDecimal reserved = BigDecimal.ZERO;
        Set<Long> notSellable = notSellableLocationIds(companyId, branchId, rows);
        for (StockOnHand soh : rows) {
            if (!notSellable.contains(soh.getLocationId())) {
                qty = qty.add(soh.getQuantity());
            }
            reserved = reserved.add(soh.getReservedQty());
        }
        return new StockAvailabilityDto(companyId, branchId, productId,
                qty, reserved, qty.subtract(reserved));
    }

    /**
     * STK-06 / OPN-02: locations whose stock the branch cannot sell. Since transfers book the
     * in-transit leg under the DESTINATION branch at dispatch, counting the in-transit location let
     * that branch sell goods still on the lorry (the Main Store went negative while In-Transit showed
     * positive). Damaged goods in a QUARANTINE location, and any location flagged not sellable, are
     * excluded for the same reason. VAN locations stay in: route sales issue from the branch
     * warehouse (ADR-0051 D-8.2), so the van's load must keep counting toward what the agent sells.
     * Reservations still sum across every row (they live on the default row).
     */
    private Set<Long> notSellableLocationIds(Long companyId, Long branchId, List<StockOnHand> rows) {
        Set<Long> excluded = new HashSet<>();
        if (rows.isEmpty()) {
            return excluded;
        }
        locationResolver.findInTransitLocationId(companyId, branchId).ifPresent(excluded::add);
        List<Long> ids = rows.stream().map(StockOnHand::getLocationId)
                .filter(java.util.Objects::nonNull).distinct().toList();
        if (!ids.isEmpty()) {
            for (StockLocation l : locations.findByCompanyIdAndIdIn(companyId, ids)) {
                if (!l.isDefault()
                        && (l.getLocationType() == LocationType.QUARANTINE || !l.isSellable())) {
                    excluded.add(l.getId());
                }
            }
        }
        return excluded;
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

    @Override
    public boolean hasStockOnHand(Long companyId, Long productId) {
        return onHands.findByCompanyIdAndProductId(companyId, productId).stream()
                .anyMatch(soh -> soh.getQuantity() != null
                        && soh.getQuantity().compareTo(BigDecimal.ZERO) != 0);
    }
}
