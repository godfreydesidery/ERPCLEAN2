package com.erp.modules.stock.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.erp.modules.stock.domain.entity.StockLocation;
import com.erp.modules.stock.domain.enums.LocationType;
import com.erp.modules.stock.repository.StockLocationRepository;
import com.erp.platform.common.domain.MasterStatus;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link StockLocationSeeder}.
 *
 * <p>Regression for fix/e2e-pos2b defect (B):
 * V37 only seeds stock locations for branches that exist at migration time. Branches created
 * after V37 (via BranchServiceImpl or BootstrapRunner) had no in-transit location, causing
 * {@code StockTransferServiceImpl.dispatch} to throw
 * {@code IllegalStateException("No in-transit location … found")}.
 *
 * <p>Fix: {@link StockLocationSeeder#seedDefaults} is called from BranchServiceImpl.create
 * and BootstrapRunner for every branch, ensuring both a WAREHOUSE default and an OTHER
 * in-transit location exist regardless of when the branch was created.
 */
class StockLocationSeederTest {

    private StockLocationRepository locations;
    private StockLocationSeeder     seeder;

    private static final Long COMPANY_ID  = 10L;
    private static final Long BRANCH_ID   = 20L;
    private static final String BRANCH_CODE = "HQ";

    @BeforeEach
    void setUp() {
        locations = mock(StockLocationRepository.class);
        seeder    = new StockLocationSeeder(locations);
    }

    /**
     * When no locations exist for the branch, seedDefaults must persist both a WAREHOUSE
     * default location and an OTHER in-transit location.
     */
    @Test
    void seedDefaults_noExistingLocations_savesBothWarehouseAndInTransit() {
        when(locations.findByCompanyIdAndBranchIdAndIsDefaultTrue(COMPANY_ID, BRANCH_ID))
                .thenReturn(Optional.empty());
        when(locations.findByCompanyIdAndBranchIdAndStatusOrderByCodeAsc(
                eq(COMPANY_ID), eq(BRANCH_ID), eq(MasterStatus.ACTIVE)))
                .thenReturn(List.of());
        when(locations.save(any(StockLocation.class))).thenAnswer(inv -> inv.getArgument(0));

        seeder.seedDefaults(COMPANY_ID, BRANCH_ID, BRANCH_CODE);

        // Two saves: one WAREHOUSE, one OTHER in-transit
        verify(locations, org.mockito.Mockito.times(2)).save(any(StockLocation.class));
    }

    /**
     * When a WAREHOUSE default already exists, the seeder must skip that save but still
     * create the in-transit location if absent.
     */
    @Test
    void seedDefaults_warehouseExists_onlySavesInTransit() {
        StockLocation existing = mock(StockLocation.class);
        when(locations.findByCompanyIdAndBranchIdAndIsDefaultTrue(COMPANY_ID, BRANCH_ID))
                .thenReturn(Optional.of(existing));
        when(locations.findByCompanyIdAndBranchIdAndStatusOrderByCodeAsc(
                eq(COMPANY_ID), eq(BRANCH_ID), eq(MasterStatus.ACTIVE)))
                .thenReturn(List.of());
        when(locations.save(any(StockLocation.class))).thenAnswer(inv -> inv.getArgument(0));

        seeder.seedDefaults(COMPANY_ID, BRANCH_ID, BRANCH_CODE);

        // Only one save — the in-transit location
        verify(locations, org.mockito.Mockito.times(1)).save(any(StockLocation.class));
    }

    /**
     * When both a WAREHOUSE default and an OTHER in-transit location already exist,
     * seedDefaults must be fully idempotent — no saves at all.
     */
    @Test
    void seedDefaults_bothExist_savesNothing() {
        StockLocation warehouse = mock(StockLocation.class);
        when(locations.findByCompanyIdAndBranchIdAndIsDefaultTrue(COMPANY_ID, BRANCH_ID))
                .thenReturn(Optional.of(warehouse));

        StockLocation inTransit = mock(StockLocation.class);
        when(inTransit.isDefault()).thenReturn(false);
        when(inTransit.getLocationType()).thenReturn(LocationType.OTHER);
        when(locations.findByCompanyIdAndBranchIdAndStatusOrderByCodeAsc(
                eq(COMPANY_ID), eq(BRANCH_ID), eq(MasterStatus.ACTIVE)))
                .thenReturn(List.of(inTransit));

        seeder.seedDefaults(COMPANY_ID, BRANCH_ID, BRANCH_CODE);

        verify(locations, never()).save(any(StockLocation.class));
    }
}
