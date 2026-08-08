package com.erp.modules.stock.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.erp.modules.products.domain.dto.ProductDto;
import com.erp.modules.products.service.ProductService;
import com.erp.modules.stock.domain.dto.LocationOnHandRowDto;
import com.erp.modules.stock.domain.entity.StockLocation;
import com.erp.modules.stock.domain.entity.StockOnHand;
import com.erp.modules.stock.repository.StockLocationRepository;
import com.erp.modules.stock.repository.StockOnHandRepository;
import com.erp.platform.common.api.NotFoundException;
import com.erp.platform.security.RequestContext;
import com.erp.platform.security.ScopeGuard;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link LocationOnHandQuery} — persona UAT finding #12.
 *
 * <p>{@code GET /api/v1/stock/on-hand/by-product/uid/{uid}} returned every label as {@code null}
 * ({@code productUid}, product code/name, location uid/code/name), so the caller received bare
 * quantities with nothing to display them against. The rows are now enriched: locations from the
 * stock module's own repository, product labels across the module boundary via
 * {@link ProductService} (DTO only), plus the base-unit label that gives the quantities meaning.
 */
class LocationOnHandQueryTest {

    private static final Long COMPANY_ID  = 10L;
    private static final Long BRANCH_ID   = 20L;
    private static final Long PRODUCT_ID  = 852L;
    private static final Long LOCATION_ID = 13L;
    private static final String PRODUCT_UID = "01KVJT7H2BKGAXS41H5SHEMQQP";

    private StockOnHandRepository   onHands;
    private StockLocationRepository locations;
    private ProductService          productService;
    private ScopeGuard              scopeGuard;

    private LocationOnHandQuery query;

    @BeforeEach
    void setUp() {
        onHands        = mock(StockOnHandRepository.class);
        locations      = mock(StockLocationRepository.class);
        productService = mock(ProductService.class);
        scopeGuard     = mock(ScopeGuard.class);

        query = new LocationOnHandQuery(onHands, locations, productService, scopeGuard);

        RequestContext.set(new RequestContext.Principal(
                1L, "user@test.com", false, COMPANY_ID, BRANCH_ID, null));
    }

    @AfterEach
    void tearDown() {
        RequestContext.clear();
    }

    // -------------------------------------------------------------------------
    // Finding #12 — every label populated
    // -------------------------------------------------------------------------

    @Test
    void queryForProductByUid_populatesProductAndLocationLabels() {
        givenProduct();
        givenLocation();
        StockOnHand soh = onHandRow(new BigDecimal("-42"));
        when(onHands.findByCompanyIdAndProductId(COMPANY_ID, PRODUCT_ID)).thenReturn(List.of(soh));

        List<LocationOnHandRowDto> rows = query.queryForProductByUid(COMPANY_ID, PRODUCT_UID);

        assertThat(rows).hasSize(1);
        LocationOnHandRowDto row = rows.get(0);
        assertThat(row.productUid()).isEqualTo(PRODUCT_UID);
        assertThat(row.productCode()).isEqualTo("PROD-0852");
        assertThat(row.productName()).isEqualTo("Kilimanjaro Water 500ml");
        assertThat(row.unitLabel()).isEqualTo("PCS");
        assertThat(row.locationId()).isEqualTo(LOCATION_ID);
        assertThat(row.locationUid()).isEqualTo("01LOCUID");
        assertThat(row.locationCode()).isEqualTo("MAIN");
        assertThat(row.locationName()).isEqualTo("Main Store");
        // The figures themselves are unchanged — including a negative on-hand, which is a flagged
        // state, not an error (ADR-0010 D-2).
        assertThat(row.quantity()).isEqualByComparingTo("-42");
        assertThat(row.avgCost()).isEqualByComparingTo("300");
        assertThat(row.onHandValue()).isEqualByComparingTo("-12600");
    }

    @Test
    void queryForProductByUid_fallsBackToBaseUnitNameWhenCodeIsBlank() {
        ProductDto product = givenProduct();
        when(product.baseUnitCode()).thenReturn("  ");
        when(product.baseUnitName()).thenReturn("Piece");
        givenLocation();
        StockOnHand soh = onHandRow(BigDecimal.ONE);
        when(onHands.findByCompanyIdAndProductId(COMPANY_ID, PRODUCT_ID)).thenReturn(List.of(soh));

        assertThat(query.queryForProductByUid(COMPANY_ID, PRODUCT_UID).get(0).unitLabel())
                .isEqualTo("Piece");
    }

    @Test
    void queryForProductByUid_leavesLocationLabelsNullWhenTheLocationIsGone() {
        givenProduct();
        when(locations.findByCompanyIdAndIdIn(eq(COMPANY_ID), anyList())).thenReturn(List.of());
        StockOnHand soh = onHandRow(BigDecimal.TEN);
        when(onHands.findByCompanyIdAndProductId(COMPANY_ID, PRODUCT_ID)).thenReturn(List.of(soh));

        LocationOnHandRowDto row = query.queryForProductByUid(COMPANY_ID, PRODUCT_UID).get(0);

        // Degrade, never fail: the quantity still has to reach the caller.
        assertThat(row.locationUid()).isNull();
        assertThat(row.locationName()).isNull();
        assertThat(row.quantity()).isEqualByComparingTo("10");
        assertThat(row.productCode()).isEqualTo("PROD-0852");
    }

    @Test
    void queryForProduct_resolvesLocationsScopedToTheCompany() {
        givenProduct();
        givenLocation();
        StockOnHand soh = onHandRow(BigDecimal.ONE);
        when(onHands.findByCompanyIdAndProductId(COMPANY_ID, PRODUCT_ID)).thenReturn(List.of(soh));

        query.queryForProduct(COMPANY_ID, PRODUCT_ID);

        // Never a bare findById: the location lookup carries the company predicate.
        verify(locations).findByCompanyIdAndIdIn(COMPANY_ID, List.of(LOCATION_ID));
        verify(scopeGuard).assertCanActIn(RequestContext.get(), COMPANY_ID);
    }

    @Test
    void queryForProduct_survivesAProductThatNoLongerExists() {
        when(productService.getById(PRODUCT_ID)).thenThrow(new NotFoundException("Product not found."));
        givenLocation();
        StockOnHand soh = onHandRow(BigDecimal.ONE);
        when(onHands.findByCompanyIdAndProductId(COMPANY_ID, PRODUCT_ID)).thenReturn(List.of(soh));

        List<LocationOnHandRowDto> rows = query.queryForProduct(COMPANY_ID, PRODUCT_ID);

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).productCode()).isNull();
        assertThat(rows.get(0).locationName()).isEqualTo("Main Store");
    }

    // -------------------------------------------------------------------------
    // Tenant scoping — the uid is resolved through the product's OWN company
    // -------------------------------------------------------------------------

    @Test
    void queryForProductByUid_refusesAProductBelongingToAnotherCompany() {
        ProductDto foreign = mock(ProductDto.class);
        when(foreign.companyId()).thenReturn(99L);
        when(productService.getByUid(PRODUCT_UID)).thenReturn(foreign);

        assertThatThrownBy(() -> query.queryForProductByUid(COMPANY_ID, PRODUCT_UID))
                .isInstanceOf(NotFoundException.class)
                .hasMessageContaining("Product not found");
    }

    // -------------------------------------------------------------------------
    // Fixtures
    // -------------------------------------------------------------------------

    private ProductDto givenProduct() {
        ProductDto product = mock(ProductDto.class);
        when(product.id()).thenReturn(PRODUCT_ID);
        when(product.uid()).thenReturn(PRODUCT_UID);
        when(product.companyId()).thenReturn(COMPANY_ID);
        when(product.code()).thenReturn("PROD-0852");
        when(product.name()).thenReturn("Kilimanjaro Water 500ml");
        when(product.baseUnitCode()).thenReturn("PCS");
        when(productService.getByUid(PRODUCT_UID)).thenReturn(product);
        when(productService.getById(PRODUCT_ID)).thenReturn(product);
        return product;
    }

    private void givenLocation() {
        StockLocation location = mock(StockLocation.class);
        when(location.getId()).thenReturn(LOCATION_ID);
        when(location.getUid()).thenReturn("01LOCUID");
        when(location.getCode()).thenReturn("MAIN");
        when(location.getName()).thenReturn("Main Store");
        when(locations.findByCompanyIdAndIdIn(eq(COMPANY_ID), anyList()))
                .thenReturn(List.of(location));
    }

    private StockOnHand onHandRow(BigDecimal quantity) {
        StockOnHand soh = mock(StockOnHand.class);
        when(soh.getLocationId()).thenReturn(LOCATION_ID);
        when(soh.getProductId()).thenReturn(PRODUCT_ID);
        when(soh.getQuantity()).thenReturn(quantity);
        when(soh.getAvgCost()).thenReturn(new BigDecimal("300"));
        when(soh.getOnHandValue()).thenReturn(quantity.multiply(new BigDecimal("300")));
        return soh;
    }
}
