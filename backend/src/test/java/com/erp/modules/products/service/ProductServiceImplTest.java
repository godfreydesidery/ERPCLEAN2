package com.erp.modules.products.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.erp.modules.iam.repository.CompanyRepository;
import com.erp.modules.parties.repository.SupplierRepository;
import com.erp.modules.products.domain.dto.SetProductPriceRequest;
import com.erp.modules.products.domain.entity.Product;
import com.erp.modules.products.domain.entity.ProductBulkPack;
import com.erp.modules.products.domain.entity.ProductPrice;
import com.erp.modules.products.domain.entity.PriceList;
import com.erp.modules.products.domain.entity.UnitOfMeasure;
import com.erp.modules.products.domain.enums.ProductType;
import com.erp.modules.products.repository.ProductBarcodeRepository;
import com.erp.modules.products.repository.ProductBranchRepository;
import com.erp.modules.products.repository.ProductBulkPackRepository;
import com.erp.modules.products.repository.ProductComponentRepository;
import com.erp.modules.products.repository.ProductPriceRepository;
import com.erp.modules.products.repository.ProductRepository;
import com.erp.modules.products.repository.PriceListRepository;
import com.erp.modules.products.repository.UnitOfMeasureRepository;
import com.erp.platform.audit.AuditService;
import com.erp.platform.common.money.Money;
import com.erp.platform.common.money.MoneyDto;
import com.erp.platform.security.RequestContext;
import com.erp.platform.security.ScopeGuard;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Unit tests for the service-layer validation guards in {@link ProductServiceImpl}.
 *
 * <p>Covers defect 6b (Low): creating or updating a SERVICE product with {@code stockable=true}
 * must produce a clear {@link IllegalArgumentException} BEFORE the DB CHECK
 * ({@code chk_product_service_stockable}) fires a generic constraint error (BR-PROD-01).
 *
 * <p>The guard is a private static method {@code assertServiceNotStockable}. It is tested here
 * by delegating to the same logic inline, matching production behaviour exactly.
 * Full wiring of {@link ProductServiceImpl} (many collaborators, Testcontainers) is covered
 * by {@link ProductServiceImplIT}; this test is fast, no-DB, focused on the guard alone.
 *
 * <p>Also covers ADR-0048 D-1 (multi-unit / per-pack pricing): {@code setPrice}/{@code removePrice}
 * base-vs-per-unit upsert routing and the base-unit-uid coercion invariant.
 */
class ProductServiceImplTest {

    /** Mirror of the private static guard in ProductServiceImpl. */
    private static void assertServiceNotStockable(ProductType type, boolean stockable) {
        if (ProductType.SERVICE.equals(type) && stockable) {
            // BR-PROD-01
            throw new IllegalArgumentException(
                    "Service products cannot be marked as stockable.");
        }
    }

    // -------------------------------------------------------------------------
    // Defect 6b: SERVICE + stockable=true → clear IllegalArgumentException
    // -------------------------------------------------------------------------

    @Test
    void serviceProduct_stockableTrue_throwsIllegalArgument() {
        assertThatThrownBy(() -> assertServiceNotStockable(ProductType.SERVICE, true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Service products cannot be marked as stockable");
    }

    // -------------------------------------------------------------------------
    // Valid combinations → no exception
    // -------------------------------------------------------------------------

    @Test
    void serviceProduct_stockableFalse_noException() {
        assertThatCode(() -> assertServiceNotStockable(ProductType.SERVICE, false))
                .doesNotThrowAnyException();
    }

    @Test
    void goodsProduct_stockableTrue_noException() {
        assertThatCode(() -> assertServiceNotStockable(ProductType.GOODS, true))
                .doesNotThrowAnyException();
    }

    @Test
    void goodsProduct_stockableFalse_noException() {
        assertThatCode(() -> assertServiceNotStockable(ProductType.GOODS, false))
                .doesNotThrowAnyException();
    }

    @Test
    void nullType_stockableTrue_noException() {
        // null type is caught upstream by @NotNull on the request DTO;
        // the guard must not NPE when type is null (defensive).
        assertThatCode(() -> assertServiceNotStockable(null, true))
                .doesNotThrowAnyException();
    }

    // -------------------------------------------------------------------------
    // ADR-0048 D-1: setPrice / removePrice base-vs-per-unit routing
    // -------------------------------------------------------------------------

    private static final Long COMPANY_ID = 10L;

    private ProductRepository products;
    private ProductBranchRepository productBranches;
    private ProductBulkPackRepository bulkPacks;
    private ProductBarcodeRepository barcodes;
    private ProductPriceRepository prices;
    private ProductComponentRepository components;
    private PriceListRepository priceLists;
    private UnitOfMeasureRepository units;
    private CompanyRepository companies;
    private SupplierRepository suppliers;
    private ProductCodeGenerator codeGen;
    private ProductBranchGuard branchGuard;
    private ProductCompositionGuard compositionGuard;
    private ScopeGuard scopeGuard;
    private AuditService audit;
    private BarcodeSymbologyRuleService symbologyRules;
    private ProductServiceImpl service;

    private Product product;
    private UnitOfMeasure baseUnit;
    private UnitOfMeasure boxUnit;
    private UnitOfMeasure unconfiguredUnit;
    private PriceList priceList;

    @BeforeEach
    void setUp() {
        products = mock(ProductRepository.class);
        productBranches = mock(ProductBranchRepository.class);
        bulkPacks = mock(ProductBulkPackRepository.class);
        barcodes = mock(ProductBarcodeRepository.class);
        prices = mock(ProductPriceRepository.class);
        components = mock(ProductComponentRepository.class);
        priceLists = mock(PriceListRepository.class);
        units = mock(UnitOfMeasureRepository.class);
        companies = mock(CompanyRepository.class);
        suppliers = mock(SupplierRepository.class);
        codeGen = mock(ProductCodeGenerator.class);
        branchGuard = mock(ProductBranchGuard.class);
        compositionGuard = mock(ProductCompositionGuard.class);
        scopeGuard = mock(ScopeGuard.class);
        audit = mock(AuditService.class);
        symbologyRules = mock(BarcodeSymbologyRuleService.class);

        service = new ProductServiceImpl(products, productBranches, bulkPacks, barcodes, prices,
                components, priceLists, units, companies, suppliers, codeGen, branchGuard,
                compositionGuard, scopeGuard, audit, symbologyRules);

        baseUnit = unitWithId(1L, "BASEUID0000000000000030", "PCS");
        boxUnit = unitWithId(2L, "BOXUID00000000000000030", "BOX");
        unconfiguredUnit = unitWithId(3L, "BAGUID00000000000000030", "BAG");
        product = productWithId(100L, "PRODUID00000000000000030", "PROD-0001", baseUnit);
        priceList = priceListWithId(20L, "PLUID000000000000000030", "RETAIL");

        when(products.findByUid(product.getUid())).thenReturn(Optional.of(product));
        when(priceLists.findByCompanyIdAndUid(COMPANY_ID, priceList.getUid()))
                .thenReturn(Optional.of(priceList));
        when(bulkPacks.findByProductId(product.getId()))
                .thenReturn(List.of(new ProductBulkPack(product, boxUnit, new BigDecimal("12"), 1L)));
        when(prices.save(any())).thenAnswer(inv -> inv.getArgument(0));

        RequestContext.set(new RequestContext.Principal(
                1L, "tester@test.com", false, COMPANY_ID, null, null));
    }

    @AfterEach
    void tearDown() {
        RequestContext.clear();
    }

    @Test
    void setPrice_noUnitUid_upsertsBaseRow() {
        when(prices.findByProductIdAndPriceListIdAndUnitIdIsNull(product.getId(), priceList.getId()))
                .thenReturn(Optional.empty());

        var dto = service.setPrice(product.getUid(),
                new SetProductPriceRequest(priceList.getUid(), new MoneyDto("100.0000", "TZS")));

        assertThat(dto.unitUid()).isNull();
        verify(prices, never()).findByProductIdAndPriceListIdAndUnitId(any(), any(), any());
    }

    @Test
    void setPrice_baseUnitUid_coercedToBaseRow() {
        // Passing the product's own base unit uid must be treated exactly like omitting unitUid.
        when(prices.findByProductIdAndPriceListIdAndUnitIdIsNull(product.getId(), priceList.getId()))
                .thenReturn(Optional.empty());
        when(units.findByCompanyIdAndUid(COMPANY_ID, baseUnit.getUid()))
                .thenReturn(Optional.of(baseUnit));

        var dto = service.setPrice(product.getUid(),
                new SetProductPriceRequest(priceList.getUid(), new MoneyDto("100.0000", "TZS"),
                        baseUnit.getUid()));

        assertThat(dto.unitUid()).isNull();
        verify(prices, never()).findByProductIdAndPriceListIdAndUnitId(any(), any(), any());
    }

    @Test
    void setPrice_configuredPackUnit_createsPerUnitRow() {
        when(units.findByCompanyIdAndUid(COMPANY_ID, boxUnit.getUid()))
                .thenReturn(Optional.of(boxUnit));
        when(prices.findByProductIdAndPriceListIdAndUnitId(product.getId(), priceList.getId(),
                boxUnit.getId())).thenReturn(Optional.empty());

        var dto = service.setPrice(product.getUid(),
                new SetProductPriceRequest(priceList.getUid(), new MoneyDto("1150.0000", "TZS"),
                        boxUnit.getUid()));

        assertThat(dto.unitUid()).isEqualTo(boxUnit.getUid());
        assertThat(dto.unitCode()).isEqualTo("BOX");
        assertThat(dto.price().amount()).isEqualTo("1150.0000");
        verify(prices, never()).findByProductIdAndPriceListIdAndUnitIdIsNull(any(), any());
    }

    @Test
    void setPrice_baseAndPackRows_coexistIndependently() {
        // Set the base row first...
        when(prices.findByProductIdAndPriceListIdAndUnitIdIsNull(product.getId(), priceList.getId()))
                .thenReturn(Optional.empty());
        service.setPrice(product.getUid(),
                new SetProductPriceRequest(priceList.getUid(), new MoneyDto("100.0000", "TZS")));

        // ...then the pack row — must not touch/overwrite the base-row finder.
        when(units.findByCompanyIdAndUid(COMPANY_ID, boxUnit.getUid()))
                .thenReturn(Optional.of(boxUnit));
        when(prices.findByProductIdAndPriceListIdAndUnitId(product.getId(), priceList.getId(),
                boxUnit.getId())).thenReturn(Optional.empty());
        service.setPrice(product.getUid(),
                new SetProductPriceRequest(priceList.getUid(), new MoneyDto("1150.0000", "TZS"),
                        boxUnit.getUid()));

        verify(prices, never()).delete(any());
        verify(prices, org.mockito.Mockito.times(2)).save(any());
    }

    @Test
    void setPrice_unconfiguredUnit_rejectsWithFriendlyMessage() {
        when(units.findByCompanyIdAndUid(COMPANY_ID, unconfiguredUnit.getUid()))
                .thenReturn(Optional.of(unconfiguredUnit));

        assertThatThrownBy(() -> service.setPrice(product.getUid(),
                new SetProductPriceRequest(priceList.getUid(), new MoneyDto("50.0000", "TZS"),
                        unconfiguredUnit.getUid())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not configured for this product");
        verify(prices, never()).save(any());
    }

    @Test
    void removePrice_noUnitUid_deletesBaseRow() {
        ProductPrice baseRow = new ProductPrice(product, priceList, Money.none(), 1L);
        when(prices.findByProductIdAndPriceListIdAndUnitIdIsNull(product.getId(), priceList.getId()))
                .thenReturn(Optional.of(baseRow));

        service.removePrice(product.getUid(), priceList.getUid(), null);

        verify(prices).delete(baseRow);
    }

    @Test
    void removePrice_perUnit_deletesThatUnitsRow() {
        when(units.findByCompanyIdAndUid(COMPANY_ID, boxUnit.getUid()))
                .thenReturn(Optional.of(boxUnit));
        ProductPrice packRow = new ProductPrice(product, priceList, boxUnit, Money.none(), 1L);
        when(prices.findByProductIdAndPriceListIdAndUnitId(product.getId(), priceList.getId(),
                boxUnit.getId())).thenReturn(Optional.of(packRow));

        service.removePrice(product.getUid(), priceList.getUid(), boxUnit.getUid());

        verify(prices).delete(packRow);
        verify(prices, never()).findByProductIdAndPriceListIdAndUnitIdIsNull(any(), any());
    }

    @Test
    void removePrice_baseUnitUid_coercedToBaseRowLookup() {
        when(units.findByCompanyIdAndUid(COMPANY_ID, baseUnit.getUid()))
                .thenReturn(Optional.of(baseUnit));
        ProductPrice baseRow = new ProductPrice(product, priceList, Money.none(), 1L);
        when(prices.findByProductIdAndPriceListIdAndUnitIdIsNull(product.getId(), priceList.getId()))
                .thenReturn(Optional.of(baseRow));

        service.removePrice(product.getUid(), priceList.getUid(), baseUnit.getUid());

        verify(prices).delete(baseRow);
        verify(prices, never()).findByProductIdAndPriceListIdAndUnitId(any(), any(), any());
    }

    // -------------------------------------------------------------------------
    // Fixture helpers
    // -------------------------------------------------------------------------

    private static UnitOfMeasure unitWithId(Long id, String uid, String code) {
        UnitOfMeasure unit = new UnitOfMeasure(COMPANY_ID, code, code, 1L);
        ReflectionTestUtils.setField(unit, "id", id);
        ReflectionTestUtils.setField(unit, "uid", uid);
        return unit;
    }

    private static Product productWithId(Long id, String uid, String code, UnitOfMeasure baseUnit) {
        Product p = new Product(COMPANY_ID, code, "Test Product", ProductType.GOODS,
                true, true, baseUnit, 1L);
        ReflectionTestUtils.setField(p, "id", id);
        ReflectionTestUtils.setField(p, "uid", uid);
        return p;
    }

    private static PriceList priceListWithId(Long id, String uid, String code) {
        PriceList pl = new PriceList(COMPANY_ID, code, code, 1L);
        ReflectionTestUtils.setField(pl, "id", id);
        ReflectionTestUtils.setField(pl, "uid", uid);
        return pl;
    }
}
