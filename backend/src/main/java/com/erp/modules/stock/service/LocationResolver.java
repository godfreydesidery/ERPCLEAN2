package com.erp.modules.stock.service;

import com.erp.modules.stock.domain.entity.StockLocation;
import com.erp.modules.stock.domain.enums.LocationType;
import com.erp.modules.stock.repository.StockLocationRepository;
import com.erp.platform.common.api.NotFoundException;
import com.erp.platform.common.domain.MasterStatus;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
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
     * Code prefix of the system in-transit location: V31 seeds {@code 'TRANSIT-' || branch.code}
     * and {@link StockLocationSeeder} uses the same convention for every branch created since.
     */
    public static final String TRANSIT_CODE_PREFIX = "TRANSIT-";

    /** Whether a location code follows the in-transit naming convention (case-insensitive). */
    public static boolean hasTransitCode(String code) {
        return code != null && code.trim().toUpperCase(Locale.ROOT).startsWith(TRANSIT_CODE_PREFIX);
    }

    /**
     * Returns the in-transit location id for a branch. The V31 migration seeds exactly one per
     * branch (LocationType OTHER, code='TRANSIT-&lt;branchCode&gt;'). If none exists, this method
     * throws {@link IllegalStateException} — silently falling back to the default (WAREHOUSE)
     * location would post TRANSFER_OUT to the wrong account and corrupt the 1300 recon invariant
     * (ADR-0020 Σ on_hand_value == accountBalance(1300)).
     *
     * <p>FIX — Finding 3 (adversarial review): removed the {@code orElseGet(() -> defaultLocationId...)}
     * fallback that silently routed in-transit movements to the branch default location.
     *
     * <p>STK-07 / OPN-03 (2026-10-10 review): resolved by the {@code TRANSIT-} code convention, not
     * as "the first non-default OTHER location by code". The old rule let any user-made OTHER
     * location whose code sorted first ("BOND", "DAMAGED") silently become the in-transit holding
     * location for every transfer into the branch. See {@link #pickInTransit} for the order.
     */
    @Transactional(readOnly = true)
    public Long inTransitLocationId(Long companyId, Long branchId) {
        return findInTransitLocationId(companyId, branchId)
                .orElseThrow(() -> new IllegalStateException(
                        // ADR-0028 D-3: an in-transit location (LocationType.OTHER) must exist per branch
                        "No in-transit location is configured for this branch. "
                        + "Please contact your system administrator to set up an in-transit location "
                        + "before dispatching stock transfers."));
    }

    /** The branch's in-transit location id, or empty when the branch has none. Never throws. */
    @Transactional(readOnly = true)
    public Optional<Long> findInTransitLocationId(Long companyId, Long branchId) {
        if (companyId == null || branchId == null) {
            return Optional.empty();
        }
        return pickInTransit(locations.findByCompanyIdAndBranchIdAndStatusOrderByCodeAsc(
                        companyId, branchId, MasterStatus.ACTIVE))
                .map(StockLocation::getId);
    }

    /** Whether {@code location} is its branch's in-transit location (as {@link #inTransitLocationId} resolves it). */
    @Transactional(readOnly = true)
    public boolean isInTransitLocation(StockLocation location) {
        if (location == null || location.getId() == null) {
            return false;
        }
        return findInTransitLocationId(location.getCompanyId(), location.getBranchId())
                .map(location.getId()::equals)
                .orElse(false);
    }

    /**
     * Picks a branch's in-transit location from its ACTIVE locations (ordered by code):
     * <ol>
     *   <li>the first non-default location whose code follows the {@code TRANSIT-} convention —
     *       every seeded transit location, whatever type a user may since have set on it;</li>
     *   <li>only when the branch has none, the legacy rule (first non-default OTHER location), so a
     *       branch whose transit location was set up by hand before the convention keeps working and
     *       a transfer already dispatched there still receives from the same row.</li>
     * </ol>
     */
    static Optional<StockLocation> pickInTransit(List<StockLocation> activeLocations) {
        Optional<StockLocation> byCode = activeLocations.stream()
                .filter(l -> !l.isDefault() && hasTransitCode(l.getCode()))
                .findFirst();
        if (byCode.isPresent()) {
            return byCode;
        }
        return activeLocations.stream()
                .filter(l -> !l.isDefault() && l.getLocationType() == LocationType.OTHER)
                .findFirst();
    }

    /**
     * Returns whether a location (looked up by its PK id) permits negative on-hand stock.
     * Used by transfer guards where the location id is already known from the transfer entity
     * (scope was validated on create; no re-check needed here).
     * Defaults to {@code false} (safe: block transfer) when the location row is not found.
     */
    @Transactional(readOnly = true)
    public boolean isAllowNegative(Long locationId) {
        return locations.findById(locationId)
                .map(StockLocation::isAllowNegative)
                .orElse(false);
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
                    "The selected location does not belong to your company.");
        }
        if (loc.getStatus() != MasterStatus.ACTIVE) {
            throw new IllegalStateException(
                    "The selected location is not active and cannot be used.");
        }
        return loc;
    }
}
