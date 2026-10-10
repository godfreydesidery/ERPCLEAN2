package com.erp.modules.stock.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.erp.modules.stock.domain.dto.StockAvailabilityDto;
import com.erp.modules.stock.domain.entity.StockLocation;
import com.erp.modules.stock.domain.entity.StockOnHand;
import com.erp.modules.stock.domain.enums.LocationType;
import com.erp.modules.stock.repository.StockLocationRepository;
import com.erp.modules.stock.repository.StockOnHandRepository;
import com.erp.platform.common.domain.MasterStatus;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * STK-06 / OPN-02: a transfer books its in-transit leg under the destination branch at dispatch, so
 * summing every location let that branch sell goods still on the lorry. Quarantined stock is not
 * sellable either; van stock still is (route sales issue from the branch warehouse).
 */
class StockReservationAvailabilityTest {

    private static final Long CO = 1L;
    private static final Long BR = 10L;
    private static final Long PRODUCT = 5L;

    @Test
    void availability_excludesInTransitAndQuarantine_keepsVanAndStores() {
        StockOnHandRepository onHands = mock(StockOnHandRepository.class);
        StockLocationRepository locations = mock(StockLocationRepository.class);
        StockReservationServiceImpl service = new StockReservationServiceImpl(
                onHands, new LocationResolver(locations), locations);

        StockLocation main = location(1L, "MAIN-BR", LocationType.WAREHOUSE, true);
        StockLocation transit = location(2L, "TRANSIT-BR", LocationType.OTHER, false);
        StockLocation quarantine = location(3L, "DAMAGED", LocationType.QUARANTINE, false);
        StockLocation van = location(4L, "VAN-01", LocationType.VAN, false);
        when(locations.findByCompanyIdAndBranchIdAndStatusOrderByCodeAsc(CO, BR, MasterStatus.ACTIVE))
                .thenReturn(List.of(quarantine, main, transit, van));
        when(locations.findByCompanyIdAndIdIn(any(), anyList()))
                .thenReturn(List.of(main, transit, quarantine, van));

        List<StockOnHand> rows = List.of(
                row(1L, "10", "2"), row(2L, "24", "0"), row(3L, "5", "0"), row(4L, "6", "0"));
        when(onHands.findAllByCompanyIdAndBranchIdAndProductId(CO, BR, PRODUCT)).thenReturn(rows);

        StockAvailabilityDto a = service.getAvailability(CO, BR, PRODUCT);

        assertThat(a.quantity()).isEqualByComparingTo("16");       // 10 main + 6 van
        assertThat(a.reservedQty()).isEqualByComparingTo("2");
        assertThat(a.availableQty()).isEqualByComparingTo("14");
    }

    private static StockOnHand row(Long locationId, String qty, String reserved) {
        StockOnHand soh = mock(StockOnHand.class);
        when(soh.getLocationId()).thenReturn(locationId);
        when(soh.getQuantity()).thenReturn(new BigDecimal(qty));
        when(soh.getReservedQty()).thenReturn(new BigDecimal(reserved));
        return soh;
    }

    private static StockLocation location(Long id, String code, LocationType type, boolean isDefault) {
        StockLocation l = mock(StockLocation.class);
        when(l.getId()).thenReturn(id);
        when(l.getCode()).thenReturn(code);
        when(l.getLocationType()).thenReturn(type);
        when(l.isDefault()).thenReturn(isDefault);
        when(l.isSellable()).thenReturn(true);
        when(l.getCompanyId()).thenReturn(CO);
        when(l.getBranchId()).thenReturn(BR);
        return l;
    }
}
