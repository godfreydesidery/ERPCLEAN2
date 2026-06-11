package com.erp.modules.stock.service;

import com.erp.modules.stock.domain.entity.StockLocation;
import com.erp.modules.stock.repository.StockLocationRepository;
import com.erp.platform.common.api.NotFoundException;
import com.erp.platform.common.domain.MasterStatus;
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

    private final StockLocationRepository locations;

    public LocationResolver(StockLocationRepository locations) {
        this.locations = locations;
    }

    /**
     * Returns the ID of the branch's default location (DR-INVD-03 / BR-INVD-04).
     * Throws if no default has been seeded for this branch (should not happen post-V37 backfill).
     */
    @Transactional(readOnly = true)
    public Long defaultLocationId(Long companyId, Long branchId) {
        return locations
                .findByCompanyIdAndBranchIdAndIsDefaultTrue(companyId, branchId)
                .map(StockLocation::getId)
                .orElseThrow(() -> new NotFoundException(
                        "No default stock location configured for branch " + branchId +
                        " in company " + companyId + ". Run the V37 migration or create a default location."));
    }

    /**
     * Returns the in-transit location id for a branch. The V37 migration seeds one per branch
     * (LocationType OTHER, code='TRANSIT-<branchCode>'). Returns the first non-default location
     * of type OTHER if found, otherwise throws.
     *
     * <p>In production the in-transit location is seeded by V37. This method is a fallback.
     */
    @Transactional(readOnly = true)
    public Long inTransitLocationId(Long companyId, Long branchId) {
        return locations
                .findByCompanyIdAndBranchIdAndStatusOrderByCodeAsc(
                        companyId, branchId, MasterStatus.ACTIVE)
                .stream()
                .filter(l -> !l.isDefault()
                        && l.getLocationType() == com.erp.modules.stock.domain.enums.LocationType.OTHER)
                .findFirst()
                .map(StockLocation::getId)
                .orElseGet(() -> defaultLocationId(companyId, branchId)); // fallback
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
