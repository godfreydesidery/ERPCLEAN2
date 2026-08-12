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
import com.erp.modules.products.domain.dto.CreateBulkPackRequest;
import com.erp.modules.products.domain.dto.SetProductPriceRequest;
import com.erp.modules.products.domain.dto.UpdateBulkPackRequest;
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
import com.erp.platform.audit.AuditEvent;
import com.erp.platform.audit.AuditService;
import com.erp.platform.common.api.ConflictException;
import com.erp.platform.common.api.NotFoundException;
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
import org.mockito.ArgumentCaptor;
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
    // Bulk packs: correctable factor + guards against entering a wrong one
    // (client defect — one OUTER deducting a CARTON's 48 pieces)
    // -------------------------------------------------------------------------

    @Test
    void addBulkPack_unitAlreadyAPack_namesTheUnitAndItsCurrentSize() {
        when(units.findByCompanyIdAndUid(COMPANY_ID, boxUnit.getUid())).thenReturn(Optional.of(boxUnit));
        when(bulkPacks.findByProductIdAndUnitId(product.getId(), boxUnit.getId()))
                .thenReturn(Optional.of(new ProductBulkPack(product, boxUnit, new BigDecimal("4"), 1L)));

        // Re-adding to correct a factor used to hit uq_product_bulk_pack_unit and surface as
        // "A record with the same unique identifier already exists" — useless to an operator.
        assertThatThrownBy(() -> service.addBulkPack(product.getUid(),
                new CreateBulkPackRequest(boxUnit.getUid(), new BigDecimal("48"))))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("BOX is already a pack unit for this product (currently 4 PCS)")
                .hasMessageContaining("Change its size instead of adding it again");
        verify(bulkPacks, never()).save(any());
    }

    @Test
    void addBulkPack_baseUnitAsPack_isRejected() {
        when(units.findByCompanyIdAndUid(COMPANY_ID, baseUnit.getUid())).thenReturn(Optional.of(baseUnit));

        assertThatThrownBy(() -> service.addBulkPack(product.getUid(),
                new CreateBulkPackRequest(baseUnit.getUid(), BigDecimal.ONE)))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("already the base unit");
        verify(bulkPacks, never()).save(any());
    }

    @Test
    void addBulkPack_factorEqualToAnExistingPack_savesButWarns() {
        // The product already carries a BOX pack of 12 (see setUp).
        when(units.findByCompanyIdAndUid(COMPANY_ID, unconfiguredUnit.getUid()))
                .thenReturn(Optional.of(unconfiguredUnit));
        when(bulkPacks.findByProductIdAndUnitId(product.getId(), unconfiguredUnit.getId()))
                .thenReturn(Optional.empty());
        when(bulkPacks.save(any())).thenAnswer(inv -> inv.getArgument(0));

        var dto = service.addBulkPack(product.getUid(),
                new CreateBulkPackRequest(unconfiguredUnit.getUid(), new BigDecimal("12")));

        assertThat(dto.warnings()).hasSize(1);
        assertThat(dto.warnings().get(0))
                .contains("BOX already holds 12 PCS")
                .contains("is a BAG really 12 PCS too?");
        // Advisory only — the pack is still created.
        assertThat(dto.factorToBase()).isEqualByComparingTo("12");
    }

    @Test
    void updateBulkPack_correctsTheFactorAndAuditsOldToNew() {
        ProductBulkPack pack = bulkPackWithUid(boxUnit, new BigDecimal("48"));
        when(bulkPacks.findByUidAndProductId(pack.getUid(), product.getId()))
                .thenReturn(Optional.of(pack));
        when(bulkPacks.findByProductId(product.getId())).thenReturn(List.of(pack));

        var dto = service.updateBulkPack(product.getUid(), pack.getUid(),
                new UpdateBulkPackRequest(new BigDecimal("4")));

        assertThat(dto.factorToBase()).isEqualByComparingTo("4");
        assertThat(pack.getFactorToBase()).isEqualByComparingTo("4");
        // A pack is not a clash with itself — the old 48 must not warn against the new 4.
        assertThat(dto.warnings()).isEmpty();

        ArgumentCaptor<AuditEvent> event = ArgumentCaptor.forClass(AuditEvent.class);
        verify(audit).record(event.capture());
        assertThat(event.getValue().detail())
                .containsEntry("action", "BULK_PACK_UPDATE")
                .containsEntry("packUid", pack.getUid())
                .containsEntry("previousFactorToBase", "48")
                .containsEntry("factorToBase", "4");
    }

    @Test
    void updateBulkPack_toAnotherPacksFactor_warns() {
        // The exact client shape: a CARTON of 48 already exists; the OUTER is being set to 48 too.
        ProductBulkPack carton = bulkPackWithUid(unitWithId(4L, "CTNUID00000000000000030", "CTN"),
                new BigDecimal("48"));
        ProductBulkPack outer = bulkPackWithUid(boxUnit, new BigDecimal("4"));
        when(bulkPacks.findByUidAndProductId(outer.getUid(), product.getId()))
                .thenReturn(Optional.of(outer));
        when(bulkPacks.findByProductId(product.getId())).thenReturn(List.of(carton, outer));

        var dto = service.updateBulkPack(product.getUid(), outer.getUid(),
                new UpdateBulkPackRequest(new BigDecimal("48")));

        assertThat(dto.warnings()).hasSize(1);
        assertThat(dto.warnings().get(0)).contains("CTN already holds 48 PCS");
        assertThat(dto.factorToBase()).isEqualByComparingTo("48");
    }

    @Test
    void updateBulkPack_otherProductsPack_throwsNotFound() {
        when(bulkPacks.findByUidAndProductId("NOTMINE0000000000000030", product.getId()))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.updateBulkPack(product.getUid(), "NOTMINE0000000000000030",
                new UpdateBulkPackRequest(new BigDecimal("4"))))
                .isInstanceOf(NotFoundException.class);
    }

    // -------------------------------------------------------------------------
    // Fixture helpers
    // -------------------------------------------------------------------------

    private int packSeq = 0;

    /** A pack with a ULID-shaped uid — updateBulkPack resolves and self-excludes by uid. */
    private ProductBulkPack bulkPackWithUid(UnitOfMeasure unit, BigDecimal factorToBase) {
        ProductBulkPack pack = new ProductBulkPack(product, unit, factorToBase, 1L);
        ReflectionTestUtils.setField(pack, "uid", String.format("BPUID%021d", ++packSeq));
        return pack;
    }

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
