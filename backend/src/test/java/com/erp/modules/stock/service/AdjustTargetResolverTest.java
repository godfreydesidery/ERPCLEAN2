package com.erp.modules.stock.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.erp.modules.stock.domain.entity.StockLocation;
import com.erp.modules.stock.domain.entity.StockOnHand;
import com.erp.modules.stock.domain.enums.LocationType;
import com.erp.modules.stock.repository.StockLocationRepository;
import com.erp.modules.stock.repository.StockOnHandRepository;
import com.erp.platform.common.domain.MasterStatus;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * STK-01 / LBO-06: after any received transfer the branch holds a zero-quantity row at its
 * In-Transit location, which used to make adjust refuse ("held at more than one location") and the
 * bulk stock sheet skip the product.
 */
class AdjustTargetResolverTest {

    private static final Long COMPANY = 1L;
    private static final Long BRANCH  = 10L;
    private static final Long PRODUCT = 500L;
    private static final Long MAIN_ID = 100L;
    private static final Long BACK_ID = 101L;
    private static final Long TRANSIT_ID = 102L;

    private StockOnHandRepository onHands;
    private StockLocationRepository locations;
    private LocationResolver locationResolver;
    private AdjustTargetResolver resolver;

    private StockLocation main;
    private StockLocation back;
    private StockLocation transit;

    @BeforeEach
    void setUp() {
        onHands = mock(StockOnHandRepository.class);
        locations = mock(StockLocationRepository.class);
        locationResolver = new LocationResolver(locations);
        resolver = new AdjustTargetResolver(onHands, locations, locationResolver);

        main = location(MAIN_ID, "MAIN-BR", "Main Store", LocationType.WAREHOUSE, true, BRANCH);
        back = location(BACK_ID, "BACK", "Back Store", LocationType.STORE, false, BRANCH);
        transit = location(TRANSIT_ID, "TRANSIT-BR", "In-Transit", LocationType.OTHER, false, BRANCH);
        when(locations.findByCompanyIdAndBranchIdAndStatusOrderByCodeAsc(COMPANY, BRANCH, MasterStatus.ACTIVE))
                .thenReturn(List.of(back, main, transit));
        when(locations.findByCompanyIdAndBranchIdAndIsDefaultTrue(COMPANY, BRANCH)).thenReturn(Optional.of(main));
        when(locations.findByCompanyIdAndId(COMPANY, MAIN_ID)).thenReturn(Optional.of(main));
        when(locations.findByCompanyIdAndId(COMPANY, BACK_ID)).thenReturn(Optional.of(back));
    }

    @Test
    void zeroTransitRowLeftByReceipt_isIgnored_andTheStockedLocationIsTargeted() {
        StockOnHand atMain = row(MAIN_ID, "125");
        rows(atMain, row(TRANSIT_ID, "0"));

        AdjustTargetResolver.Target t = resolver.resolve(COMPANY, BRANCH, PRODUCT, null);

        assertThat(t.locationId()).isEqualTo(MAIN_ID);
        assertThat(t.locationUid()).isEqualTo("UID-" + MAIN_ID);
        assertThat(t.onHand()).isSameAs(atMain);
    }

    @Test
    void stockOnlyInBackStore_withEmptyDefaultRow_targetsBackStore() {
        StockOnHand atBack = row(BACK_ID, "40");
        rows(row(MAIN_ID, "0"), atBack);

        assertThat(resolver.resolve(COMPANY, BRANCH, PRODUCT, null).onHand()).isSameAs(atBack);
    }

    @Test
    void everythingEmpty_fallsBackToTheBranchDefault() {
        rows(row(TRANSIT_ID, "0"), row(BACK_ID, "0"));

        AdjustTargetResolver.Target t = resolver.resolve(COMPANY, BRANCH, PRODUCT, null);

        assertThat(t.locationId()).isEqualTo(MAIN_ID);
        assertThat(t.onHand()).isNull();
    }

    @Test
    void stockInTwoRealLocations_asksForALocation_namingThem() {
        rows(row(MAIN_ID, "5"), row(BACK_ID, "7"));
        when(locations.findByCompanyIdAndIdIn(any(), anyList())).thenReturn(List.of(main, back));

        assertThatThrownBy(() -> resolver.resolve(COMPANY, BRANCH, PRODUCT, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Back Store, Main Store")
                .hasMessageContaining("Choose the location");
    }

    @Test
    void explicitLocation_targetsIt() {
        StockOnHand atBack = row(BACK_ID, "7");
        rows(row(MAIN_ID, "5"), atBack);
        when(locations.findByUid("UID-" + BACK_ID)).thenReturn(Optional.of(back));

        AdjustTargetResolver.Target t = resolver.resolve(COMPANY, BRANCH, PRODUCT, "UID-" + BACK_ID);

        assertThat(t.locationId()).isEqualTo(BACK_ID);
        assertThat(t.onHand()).isSameAs(atBack);
    }

    @Test
    void explicitTransitLocation_refused() {
        rows();
        when(locations.findByUid("UID-" + TRANSIT_ID)).thenReturn(Optional.of(transit));

        assertThatThrownBy(() -> resolver.resolve(COMPANY, BRANCH, PRODUCT, "UID-" + TRANSIT_ID))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("in transit");
    }

    @Test
    void explicitLocationOfAnotherBranch_refused() {
        rows();
        StockLocation elsewhere = location(900L, "MAIN-X", "Main Store", LocationType.WAREHOUSE, true, 99L);
        when(locations.findByUid("UID-900")).thenReturn(Optional.of(elsewhere));

        assertThatThrownBy(() -> resolver.resolve(COMPANY, BRANCH, PRODUCT, "UID-900"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("another branch");
    }

    // -------------------------------------------------------------------------

    private void rows(StockOnHand... rs) {
        when(onHands.findAllByCompanyIdAndBranchIdAndProductId(COMPANY, BRANCH, PRODUCT))
                .thenReturn(List.of(rs));
    }

    private static StockOnHand row(Long locationId, String qty) {
        StockOnHand soh = mock(StockOnHand.class);
        when(soh.getLocationId()).thenReturn(locationId);
        when(soh.getQuantity()).thenReturn(new BigDecimal(qty));
        return soh;
    }

    private static StockLocation location(Long id, String code, String name, LocationType type,
                                          boolean isDefault, Long branchId) {
        StockLocation l = mock(StockLocation.class);
        when(l.getId()).thenReturn(id);
        when(l.getUid()).thenReturn("UID-" + id);
        when(l.getCode()).thenReturn(code);
        when(l.getName()).thenReturn(name);
        when(l.getLocationType()).thenReturn(type);
        when(l.isDefault()).thenReturn(isDefault);
        when(l.getCompanyId()).thenReturn(COMPANY);
        when(l.getBranchId()).thenReturn(branchId);
        when(l.getStatus()).thenReturn(MasterStatus.ACTIVE);
        return l;
    }
}
