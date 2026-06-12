package com.erp.modules.stock.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.erp.modules.stock.domain.entity.StockLocation;
import com.erp.modules.stock.domain.enums.LocationType;
import com.erp.modules.stock.repository.StockLocationRepository;
import com.erp.platform.common.domain.MasterStatus;
import java.lang.reflect.Field;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link LocationResolver}.
 *
 * <p>Covers:
 * <ul>
 *   <li>Finding 3 fix: {@link LocationResolver#inTransitLocationId} must throw
 *       {@link IllegalStateException} when no LocationType.OTHER location exists — NOT fall back
 *       to the branch default (WAREHOUSE) location, which would corrupt the 1300 recon invariant.</li>
 *   <li>Happy path: in-transit location found and its id returned.</li>
 *   <li>defaultLocationId happy path: returns the id of the default location when present.</li>
 * </ul>
 */
class LocationResolverImplTest {

    private StockLocationRepository mockLocations;
    private LocationResolver resolver;

    private static final Long COMPANY_ID = 1L;
    private static final Long BRANCH_ID  = 10L;

    @BeforeEach
    void setUp() {
        mockLocations = mock(StockLocationRepository.class);
        resolver      = new LocationResolver(mockLocations);
    }

    // =========================================================================
    // Finding 3 FIX: inTransitLocationId must throw when no OTHER-type location exists
    // =========================================================================

    @Test
    void inTransitLocationId_noOtherTypeLocation_throwsIllegalState_finding3() {
        // Only a WAREHOUSE (default) location is returned — no OTHER/in-transit
        StockLocation warehouseLoc = makeLocation(1L, LocationType.WAREHOUSE, true, MasterStatus.ACTIVE);
        when(mockLocations.findByCompanyIdAndBranchIdAndStatusOrderByCodeAsc(
                COMPANY_ID, BRANCH_ID, MasterStatus.ACTIVE))
                .thenReturn(List.of(warehouseLoc));

        // Finding 3 fix: must throw — NOT fall back to warehouse location
        assertThatThrownBy(() -> resolver.inTransitLocationId(COMPANY_ID, BRANCH_ID))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("in-transit location")
                .hasMessageContaining("company=" + COMPANY_ID)
                .hasMessageContaining("branch=" + BRANCH_ID)
                .hasMessageContaining("V37 migration");
    }

    @Test
    void inTransitLocationId_emptyLocationList_throwsIllegalState() {
        when(mockLocations.findByCompanyIdAndBranchIdAndStatusOrderByCodeAsc(
                COMPANY_ID, BRANCH_ID, MasterStatus.ACTIVE))
                .thenReturn(Collections.emptyList());

        assertThatThrownBy(() -> resolver.inTransitLocationId(COMPANY_ID, BRANCH_ID))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("in-transit location");
    }

    @Test
    void inTransitLocationId_otherTypeLocationExists_returnsItsId() {
        StockLocation warehouse = makeLocation(1L, LocationType.WAREHOUSE, true,  MasterStatus.ACTIVE);
        StockLocation transit   = makeLocation(2L, LocationType.OTHER,     false, MasterStatus.ACTIVE);
        when(mockLocations.findByCompanyIdAndBranchIdAndStatusOrderByCodeAsc(
                COMPANY_ID, BRANCH_ID, MasterStatus.ACTIVE))
                .thenReturn(List.of(warehouse, transit));

        Long result = resolver.inTransitLocationId(COMPANY_ID, BRANCH_ID);
        assertThat(result)
                .as("Must return the OTHER-type location's id")
                .isEqualTo(2L);
    }

    @Test
    void inTransitLocationId_otherTypeDefaultLocation_notReturned_nonDefaultOtherReturned() {
        // Edge case: if for some reason an OTHER location is also default, it should be skipped
        // (the filter is !isDefault && type == OTHER)
        StockLocation otherDefault    = makeLocation(1L, LocationType.OTHER, true,  MasterStatus.ACTIVE);
        StockLocation otherNonDefault = makeLocation(2L, LocationType.OTHER, false, MasterStatus.ACTIVE);
        when(mockLocations.findByCompanyIdAndBranchIdAndStatusOrderByCodeAsc(
                COMPANY_ID, BRANCH_ID, MasterStatus.ACTIVE))
                .thenReturn(List.of(otherDefault, otherNonDefault));

        Long result = resolver.inTransitLocationId(COMPANY_ID, BRANCH_ID);
        assertThat(result)
                .as("Non-default OTHER location takes precedence over default OTHER")
                .isEqualTo(2L);
    }

    // =========================================================================
    // defaultLocationId: returns existing default's id (no seeding attempted)
    // =========================================================================

    @Test
    void defaultLocationId_defaultExists_returnsId() {
        StockLocation defaultLoc = makeLocation(5L, LocationType.WAREHOUSE, true, MasterStatus.ACTIVE);
        when(mockLocations.findByCompanyIdAndBranchIdAndIsDefaultTrue(COMPANY_ID, BRANCH_ID))
                .thenReturn(Optional.of(defaultLoc));

        Long result = resolver.defaultLocationId(COMPANY_ID, BRANCH_ID);
        assertThat(result).isEqualTo(5L);
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    /** Build a mock StockLocation with the given id, type, isDefault, and status via reflection. */
    private StockLocation makeLocation(Long id, LocationType type, boolean isDefault,
                                        MasterStatus status) {
        StockLocation loc = mock(StockLocation.class);
        when(loc.getId()).thenReturn(id);
        when(loc.getLocationType()).thenReturn(type);
        when(loc.isDefault()).thenReturn(isDefault);
        when(loc.getStatus()).thenReturn(status);
        when(loc.getCompanyId()).thenReturn(COMPANY_ID);
        when(loc.getBranchId()).thenReturn(BRANCH_ID);
        return loc;
    }
}
