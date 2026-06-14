package com.erp.modules.stock.service;

import com.erp.modules.stock.domain.entity.StockLocation;
import com.erp.modules.stock.domain.enums.LocationType;
import com.erp.modules.stock.repository.StockLocationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Seeds the default WAREHOUSE location and the in-transit OTHER location for a new branch
 * (ADR-0028 D-4/D-5, V37 parity). Idempotent — skips each location if it already exists.
 *
 * <p>V37 only backfills branches that exist at migration time. Any branch created after V37
 * (via {@link com.erp.modules.iam.service.BranchServiceImpl} or the bootstrap runner) has
 * no in-transit location, which causes stock-transfer dispatch to throw
 * {@code IllegalStateException("No in-transit location … found")}.
 *
 * <p>Called by:
 * <ul>
 *   <li>{@link com.erp.platform.bootstrap.BootstrapRunner} — for the bootstrap branch</li>
 *   <li>{@link com.erp.modules.iam.service.BranchServiceImpl#create} — for every new branch</li>
 * </ul>
 */
@Component
public class StockLocationSeeder {

    private static final Logger log = LoggerFactory.getLogger(StockLocationSeeder.class);

    private final StockLocationRepository locations;

    public StockLocationSeeder(StockLocationRepository locations) {
        this.locations = locations;
    }

    /**
     * Seeds WAREHOUSE default + OTHER in-transit locations for the given branch.
     * Code convention mirrors V37: MAIN-{branchId} and TRANSIT-{branchId}.
     * Idempotent: uses ON-CONFLICT-equivalent findByCompanyIdAndCode guard.
     *
     * @param companyId the company the branch belongs to
     * @param branchId  the branch id
     * @param branchCode the branch code (used to build readable location codes)
     */
    @Transactional
    public void seedDefaults(Long companyId, Long branchId, String branchCode) {
        seedWarehouse(companyId, branchId, branchCode);
        seedInTransit(companyId, branchId, branchCode);
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private void seedWarehouse(Long companyId, Long branchId, String branchCode) {
        String code = "MAIN-" + branchCode;
        boolean exists = locations
                .findByCompanyIdAndBranchIdAndIsDefaultTrue(companyId, branchId)
                .isPresent();
        if (exists) {
            log.debug("StockLocationSeeder: WAREHOUSE default already exists for company={} branch={} — skipping.",
                    companyId, branchId);
            return;
        }
        StockLocation loc = new StockLocation(
                companyId, branchId,
                code,
                "Main Store",
                LocationType.WAREHOUSE,
                true,
                null);
        locations.save(loc);
        log.info("StockLocationSeeder: seeded WAREHOUSE default location '{}' for company={} branch={}.",
                code, companyId, branchId);
    }

    private void seedInTransit(Long companyId, Long branchId, String branchCode) {
        String transitCode = "TRANSIT-" + branchCode;
        // Check for any existing OTHER/in-transit location for this branch
        boolean exists = locations
                .findByCompanyIdAndBranchIdAndStatusOrderByCodeAsc(
                        companyId, branchId, com.erp.platform.common.domain.MasterStatus.ACTIVE)
                .stream()
                .anyMatch(l -> !l.isDefault()
                        && l.getLocationType() == LocationType.OTHER);
        if (exists) {
            log.debug("StockLocationSeeder: in-transit location already exists for company={} branch={} — skipping.",
                    companyId, branchId);
            return;
        }
        StockLocation loc = new StockLocation(
                companyId, branchId,
                transitCode,
                "In-Transit",
                LocationType.OTHER,
                false,
                null);
        locations.save(loc);
        log.info("StockLocationSeeder: seeded in-transit location '{}' for company={} branch={}.",
                transitCode, companyId, branchId);
    }
}
