package com.erp.modules.stock.service;

import com.erp.modules.stock.domain.entity.StockLocation;
import com.erp.modules.stock.domain.enums.LocationType;
import com.erp.modules.stock.repository.StockLocationRepository;
import com.erp.platform.common.api.NotFoundException;
import com.erp.platform.common.domain.MasterStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The single place that resolves a branch's default location (ADR-0028 D-1/D-3).
 *
 * <p>Every path that receives or issues stock WITHOUT an explicit location calls
 * {@link #defaultLocationId} to get the branch default — preserving backward compatibility
 * with all shipped receipt/delivery/adjustment flows (BR-INVD-04).
 *
 * <p>Every path that resolves a location uid calls {@link #resolveLocation} which validates
 * scope (the location must belong to the principal's company, ACTIVE).
 */
@Component
public class LocationResolver {

    private static final Logger log = LoggerFactory.getLogger(LocationResolver.class);

    private final StockLocationRepository locations;

    public LocationResolver(StockLocationRepository locations) {
        this.locations = locations;
    }

    /**
     * Returns the ID of the branch's default location (DR-INVD-03 / BR-INVD-04).
     * If no default location exists (e.g. a branch created after the V37 backfill, as in tests),
     * one is created lazily — mirroring the V37 seed logic so that new branches work without
     * manual setup.
     */
    @Transactional
    public Long defaultLocationId(Long companyId, Long branchId) {
        return locations
                .findByCompanyIdAndBranchIdAndIsDefaultTrue(companyId, branchId)
                .map(StockLocation::getId)
                .orElseGet(() -> seedDefaultLocation(companyId, branchId));
    }

    /** Creates and persists the WAREHOUSE default location for a branch that has none (V37 parity). */
    private Long seedDefaultLocation(Long companyId, Long branchId) {
        log.info("LocationResolver: no default stock location for company={} branch={} — seeding one (V37 parity)",
                companyId, branchId);
        StockLocation loc = new StockLocation(
                companyId, branchId,
                "MAIN-" + branchId,
                "Main Store",
                LocationType.WAREHOUSE,
                true,
                null);
        return locations.save(loc).getId();
    }

    /**
     * Returns the in-transit location id for a branch. The V37 migration seeds exactly one per
     * branch (LocationType OTHER, code='TRANSIT-&lt;branchCode&gt;'). If none exists, this method
     * throws {@link IllegalStateException} — silently falling back to the default (WAREHOUSE)
     * location would post TRANSFER_OUT to the wrong account and corrupt the 1300 recon invariant
     * (ADR-0020 Σ on_hand_value == accountBalance(1300)).
     *
     * <p>FIX — Finding 3 (adversarial review): removed the {@code orElseGet(() -> defaultLocationId...)}
     * fallback that silently routed in-transit movements to the branch default location.
     */
    @Transactional(readOnly = true)
    public Long inTransitLocationId(Long companyId, Long branchId) {
        return locations
                .findByCompanyIdAndBranchIdAndStatusOrderByCodeAsc(
                        companyId, branchId, MasterStatus.ACTIVE)
                .stream()
                .filter(l -> !l.isDefault()
                        && l.getLocationType() == LocationType.OTHER)
                .findFirst()
                .map(StockLocation::getId)
                .orElseThrow(() -> new IllegalStateException(
                        "No in-transit location (LocationType.OTHER) found for company=" + companyId
                        + " branch=" + branchId
                        + " — V37 migration may have failed to seed the in-transit location. "
                        + "Transfer dispatch cannot proceed without an in-transit location."));
    }

    /**
     * Resolve a location by uid, asserting it belongs to the given company and is ACTIVE.
     */
    @Transactional(readOnly = true)
    public StockLocation resolveLocation(String locationUid, Long companyId) {
        StockLocation loc = locations.findByUid(locationUid)
                .orElseThrow(() -> NotFoundException.of("StockLocation", locationUid));
        if (!loc.getCompanyId().equals(companyId)) {
            throw new com.erp.platform.common.api.ForbiddenException(
                    "Location " + locationUid + " does not belong to company " + companyId);
        }
        if (loc.getStatus() != MasterStatus.ACTIVE) {
            throw new IllegalStateException(
                    "Location " + locationUid + " is not ACTIVE (status=" + loc.getStatus() + ")");
        }
        return loc;
    }
}
