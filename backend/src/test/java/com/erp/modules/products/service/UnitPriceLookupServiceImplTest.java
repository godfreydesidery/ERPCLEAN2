package com.erp.modules.products.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.erp.modules.products.domain.dto.ResolveUnitPricesRequest;
import com.erp.modules.products.domain.dto.ResolvedUnitPriceDto;
import com.erp.modules.products.domain.entity.PriceList;
import com.erp.modules.products.domain.entity.Product;
import com.erp.modules.products.domain.entity.ProductBulkPack;
import com.erp.modules.products.domain.entity.ProductPrice;
import com.erp.modules.products.domain.entity.UnitOfMeasure;
import com.erp.modules.products.domain.enums.ProductType;
import com.erp.modules.products.domain.enums.UnitPriceStatus;
import com.erp.modules.products.repository.CustomerPriceRepository;
import com.erp.modules.products.repository.PriceListRepository;
import com.erp.modules.products.repository.PriceTierRepository;
import com.erp.modules.products.repository.ProductBulkPackRepository;
import com.erp.modules.products.repository.ProductPriceRepository;
import com.erp.modules.products.repository.ProductRepository;
import com.erp.modules.products.repository.PromotionRepository;
import com.erp.modules.products.repository.UnitOfMeasureRepository;
import com.erp.platform.common.api.ForbiddenException;
import com.erp.platform.common.api.NotFoundException;
import com.erp.platform.common.money.Money;
import com.erp.platform.security.RequestContext;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Unit tests for the batch, unit-aware price read.
 *
 * <p>Wires the REAL {@link PriceResolutionServiceImpl} underneath (only the repositories are
 * mocked) — the whole point of the endpoint is that clients get the SAME number the sales services
 * charge, so stubbing the resolver would test nothing worth testing.
 *
 * <p><b>What this file provably CANNOT catch.</b> It builds {@code PriceResolutionServiceImpl} with
 * {@code new}: a plain object, no Spring proxy, no transaction. For months that let the
 * {@code NO_PRICE} / {@code UNIT_NOT_APPLICABLE} assertions below pass green while the live
 * endpoint returned HTTP 500 on any batch containing an unpriceable product — in production the
 * resolver is {@code @Transactional} and joins the wrapper's transaction, so an exception leaving
 * its proxy marked the transaction rollback-only and the COMMIT failed
 * ({@code UnexpectedRollbackException}) even though the wrapper had caught it and built a good
 * list. Transaction semantics are invisible here, by construction.
 *
 * <p>Two things follow, and both are load-bearing:
 * <ul>
 *   <li>{@link UnitPriceLookupServiceIT} exercises the same statuses through the real proxies and
 *       is the test that would have failed. Status assertions belong in BOTH files, not only here.</li>
 *   <li>{@link #batchWrapper_neverCallsTheThrowingResolver} is the guard that survives at unit
 *       level: it fails if anyone routes the batch back through the throwing API, which is the one
 *       aspect of the defect a mock-only test can still see.</li>
 * </ul>
 */
class UnitPriceLookupServiceImplTest {

    private static final Long COMPANY_ID = 10L;
    private static final Long OTHER_COMPANY_ID = 99L;

    private static final String PROD_A_UID = "PRODAUID000000000000040";
    private static final String PROD_B_UID = "PRODBUID000000000000040";
    private static final String UNKNOWN_UID = "NOSUCHPRODUCT0000000040";
    private static final String BOX_UID = "BOXUID00000000000000040";

    private ProductRepository products;
    private UnitOfMeasureRepository units;
    private ProductPriceRepository productPrices;
    private ProductBulkPackRepository bulkPacks;
    private PriceResolutionService priceResolution;

    private UnitPriceLookupServiceImpl service;

    private UnitOfMeasure pcs;
    private UnitOfMeasure box;
    private Product productA;
    private Product productB;

    private final Map<String, Product> catalogue = new HashMap<>();

    @BeforeEach
    void setUp() {
        products = mock(ProductRepository.class);
        units = mock(UnitOfMeasureRepository.class);
        productPrices = mock(ProductPriceRepository.class);
        bulkPacks = mock(ProductBulkPackRepository.class);

        // Spied, not stubbed: the real resolution rules still run (see the class javadoc), but the
        // spy also records WHICH resolver API the wrapper reached for — the throwing one is what
        // poisoned the transaction in production.
        priceResolution = spy(new PriceResolutionServiceImpl(
                mock(CustomerPriceRepository.class),
                mock(PromotionRepository.class),
                mock(PriceTierRepository.class),
                productPrices,
                bulkPacks,
                products,
                mock(PriceListRepository.class)));

        service = new UnitPriceLookupServiceImpl(products, units, priceResolution);

        pcs = unitWithId(1L, "PCSUID00000000000000040", "PCS");
        box = unitWithId(2L, BOX_UID, "BOX");

        productA = productWithId(100L, PROD_A_UID, "PROD-0001", "Soap", pcs);
        productB = productWithId(101L, PROD_B_UID, "PROD-0002", "Sugar", pcs);
        catalogue.put(PROD_A_UID, productA);
        catalogue.put(PROD_B_UID, productB);

        // Product A sells in a BOX of 12; product B has no pack units at all.
        when(bulkPacks.findByProductId(productA.getId()))
                .thenReturn(List.of(new ProductBulkPack(productA, box, new BigDecimal("12"), 1L)));

        when(products.findByCompanyIdAndUidIn(eq(COMPANY_ID), any())).thenAnswer(invocation -> {
            Collection<String> requested = invocation.getArgument(1);
            return requested.stream().map(catalogue::get).filter(Objects::nonNull).toList();
        });
        when(products.findByCompanyIdAndId(eq(COMPANY_ID), anyLong())).thenAnswer(invocation -> {
            Long id = invocation.getArgument(1);
            return catalogue.values().stream().filter(p -> p.getId().equals(id)).findFirst();
        });
        when(units.findByCompanyIdAndUid(COMPANY_ID, BOX_UID)).thenReturn(Optional.of(box));

        RequestContext.set(new RequestContext.Principal(
                7L, "cashier", false, COMPANY_ID, 3L, "127.0.0.1"));
    }

    @AfterEach
    void tearDown() {
        RequestContext.clear();
        catalogue.clear();
    }

    // -------------------------------------------------------------------------
    // Resolution parity with what the sales services charge
    // -------------------------------------------------------------------------

    @Test
    void explicitPerUnitPrice_returnedVerbatim_notBaseTimesFactor() {
        basePrice(productA, "100.0000");
        // An explicit BOX row of 1150 — deliberately NOT 12 x 100; a non-linear pack discount.
        when(productPrices.findFirstByProductIdAndUnitIdOrderByIdAsc(productA.getId(), box.getId()))
                .thenReturn(Optional.of(priceRow(productA, box, "1150.0000", false)));

        List<ResolvedUnitPriceDto> rows = service.resolve(
                new ResolveUnitPricesRequest(List.of(PROD_A_UID), BOX_UID));

        assertThat(rows).hasSize(1);
        ResolvedUnitPriceDto row = rows.get(0);
        assertThat(row.productUid()).isEqualTo(PROD_A_UID);
        assertThat(row.unitUid()).isEqualTo(BOX_UID);
        assertThat(row.amount()).isEqualByComparingTo("1150.0000");
        assertThat(row.currency()).isEqualTo("TZS");
        assertThat(row.vatInclusive()).isFalse();
        assertThat(row.status()).isEqualTo(UnitPriceStatus.RESOLVED);
    }

    @Test
    void noExplicitPerUnitRow_priceIsDerivedFromBaseTimesFactor() {
        basePrice(productA, "100.0000");
        when(productPrices.findFirstByProductIdAndUnitIdOrderByIdAsc(productA.getId(), box.getId()))
                .thenReturn(Optional.empty());

        List<ResolvedUnitPriceDto> rows = service.resolve(
                new ResolveUnitPricesRequest(List.of(PROD_A_UID), BOX_UID));

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).amount()).isEqualByComparingTo("1200.0000"); // 100 x 12
        assertThat(rows.get(0).unitUid()).isEqualTo(BOX_UID);
        assertThat(rows.get(0).status()).isEqualTo(UnitPriceStatus.RESOLVED);
    }

    @Test
    void noUnitRequested_pricesEachProductInItsOwnBaseUnit() {
        basePrice(productA, "100.0000");
        basePrice(productB, "2500.0000");

        List<ResolvedUnitPriceDto> rows = service.resolve(
                new ResolveUnitPricesRequest(List.of(PROD_A_UID, PROD_B_UID), null));

        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).amount()).isEqualByComparingTo("100.0000");
        assertThat(rows.get(0).unitUid()).isEqualTo(pcs.getUid());
        assertThat(rows.get(1).amount()).isEqualByComparingTo("2500.0000");
        assertThat(rows.get(1).unitUid()).isEqualTo(pcs.getUid());
    }

    @Test
    void vatInclusivePriceList_carriesTheGrossStanceToTheClient() {
        when(productPrices.findFirstByProductIdAndUnitIdIsNullOrderByIdAsc(productA.getId()))
                .thenReturn(Optional.of(priceRow(productA, null, "1180.0000", true)));

        List<ResolvedUnitPriceDto> rows = service.resolve(
                new ResolveUnitPricesRequest(List.of(PROD_A_UID), null));

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).amount()).isEqualByComparingTo("1180.0000");
        assertThat(rows.get(0).vatInclusive()).isTrue();
    }

    // -------------------------------------------------------------------------
    // Batch tolerance — one bad row must never blank the whole page
    // -------------------------------------------------------------------------

    @Test
    void unknownProductUid_isIgnored_andTheRestOfTheBatchStillPrices() {
        basePrice(productA, "100.0000");
        basePrice(productB, "2500.0000");

        List<ResolvedUnitPriceDto> rows = service.resolve(new ResolveUnitPricesRequest(
                List.of(PROD_A_UID, UNKNOWN_UID, PROD_B_UID), null));

        assertThat(rows).extracting(ResolvedUnitPriceDto::productUid)
                .containsExactly(PROD_A_UID, PROD_B_UID);
    }

    @Test
    void productFromAnotherCompany_isIgnoredLikeAnUnknownUid() {
        // The scoped query never returns it — indistinguishable from "no such product".
        Product foreign = productWithId(500L, "FOREIGNUID0000000000040", "PROD-9001", "Rice", pcs);
        ReflectionTestUtils.setField(foreign, "companyId", OTHER_COMPANY_ID);
        basePrice(productA, "100.0000");

        List<ResolvedUnitPriceDto> rows = service.resolve(new ResolveUnitPricesRequest(
                List.of(PROD_A_UID, foreign.getUid()), null));

        assertThat(rows).extracting(ResolvedUnitPriceDto::productUid).containsExactly(PROD_A_UID);
    }

    @Test
    void productWithNoPriceConfigured_comesBackAsNoPrice_notAnError() {
        basePrice(productA, "100.0000");
        // productB has no product_prices row stubbed at all.

        List<ResolvedUnitPriceDto> rows = service.resolve(new ResolveUnitPricesRequest(
                List.of(PROD_A_UID, PROD_B_UID), null));

        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).status()).isEqualTo(UnitPriceStatus.RESOLVED);
        ResolvedUnitPriceDto unpriced = rows.get(1);
        assertThat(unpriced.productUid()).isEqualTo(PROD_B_UID);
        assertThat(unpriced.status()).isEqualTo(UnitPriceStatus.NO_PRICE);
        assertThat(unpriced.amount()).isNull();
        assertThat(unpriced.currency()).isNull();
    }

    @Test
    void unitNotValidForOneProduct_marksThatRowOnly() {
        // BOX is configured for A but not for B — a mixed batch under one requested unit.
        basePrice(productA, "100.0000");
        basePrice(productB, "2500.0000");
        when(productPrices.findFirstByProductIdAndUnitIdOrderByIdAsc(productA.getId(), box.getId()))
                .thenReturn(Optional.empty());

        List<ResolvedUnitPriceDto> rows = service.resolve(new ResolveUnitPricesRequest(
                List.of(PROD_A_UID, PROD_B_UID), BOX_UID));

        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).status()).isEqualTo(UnitPriceStatus.RESOLVED);
        assertThat(rows.get(0).amount()).isEqualByComparingTo("1200.0000");
        assertThat(rows.get(1).status()).isEqualTo(UnitPriceStatus.UNIT_NOT_APPLICABLE);
        assertThat(rows.get(1).amount()).isNull();
    }

    /**
     * The one part of the 500 this mock-only test can still see: the batch must ask the resolver
     * for an OUTCOME, never call the throwing {@code resolveUnitListPriceQuote} and catch.
     *
     * <p>Both services are {@code @Transactional} in production and the resolver joins the
     * wrapper's transaction, so an exception leaving the resolver's proxy marks that transaction
     * rollback-only whether or not the wrapper catches it — the batch then returned a good list and
     * blew up at COMMIT. Catching cannot un-poison a transaction, so "don't throw at all" is the
     * invariant. Nothing about that is observable without a proxy; the call site is.
     */
    @Test
    void batchWrapper_neverCallsTheThrowingResolver() {
        basePrice(productA, "100.0000");
        // productB is deliberately unpriced — the exact shape that used to 500 the whole page.

        service.resolve(new ResolveUnitPricesRequest(List.of(PROD_A_UID, PROD_B_UID), null));

        verify(priceResolution, never()).resolveUnitListPriceQuote(anyLong(), anyLong(), anyLong());
        verify(priceResolution, never()).resolveUnitListPrice(anyLong(), anyLong(), anyLong());
    }

    // -------------------------------------------------------------------------
    // Request handling / scope
    // -------------------------------------------------------------------------

    @Test
    void duplicateAndBlankUids_areCollapsedAndSkipped() {
        basePrice(productA, "100.0000");

        List<ResolvedUnitPriceDto> rows = service.resolve(new ResolveUnitPricesRequest(
                Arrays.asList(PROD_A_UID, "  ", PROD_A_UID, null), null));

        assertThat(rows).extracting(ResolvedUnitPriceDto::productUid).containsExactly(PROD_A_UID);
    }

    @Test
    void emptyRequest_returnsEmptyList() {
        assertThat(service.resolve(new ResolveUnitPricesRequest(List.of(), null))).isEmpty();
    }

    @Test
    void unknownUnitUid_isRejectedForTheWholeBatch() {
        when(units.findByCompanyIdAndUid(COMPANY_ID, "NOSUCHUNIT00000000000040"))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.resolve(new ResolveUnitPricesRequest(
                List.of(PROD_A_UID), "NOSUCHUNIT00000000000040")))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void noActiveCompany_isRefused() {
        RequestContext.clear();

        assertThatThrownBy(() -> service.resolve(
                new ResolveUnitPricesRequest(List.of(PROD_A_UID), null)))
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    void batchLargerThanTheCap_isRejected() {
        List<String> tooMany = new java.util.ArrayList<>();
        for (int i = 0; i < 201; i++) {
            tooMany.add(String.format("PRODUID%016d", i));
        }

        assertThatThrownBy(() -> service.resolve(new ResolveUnitPricesRequest(tooMany, null)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // -------------------------------------------------------------------------
    // Fixture helpers
    // -------------------------------------------------------------------------

    private void basePrice(Product product, String amount) {
        when(productPrices.findFirstByProductIdAndUnitIdIsNullOrderByIdAsc(product.getId()))
                .thenReturn(Optional.of(priceRow(product, null, amount, false)));
    }

    private ProductPrice priceRow(Product product, UnitOfMeasure unit, String amount,
                                  boolean vatInclusive) {
        PriceList priceList = new PriceList(COMPANY_ID, "RETAIL", "Retail", 1L);
        ReflectionTestUtils.setField(priceList, "id", 500L);
        ReflectionTestUtils.setField(priceList, "uid", "PLUID000000000000000040");
        priceList.setPriceIncludesVat(vatInclusive);
        return new ProductPrice(product, priceList, unit,
                new Money(new BigDecimal(amount), "TZS"), 1L);
    }

    private static UnitOfMeasure unitWithId(Long id, String uid, String code) {
        UnitOfMeasure unit = new UnitOfMeasure(COMPANY_ID, code, code, 1L);
        ReflectionTestUtils.setField(unit, "id", id);
        ReflectionTestUtils.setField(unit, "uid", uid);
        return unit;
    }

    private static Product productWithId(Long id, String uid, String code, String name,
                                         UnitOfMeasure baseUnit) {
        Product p = new Product(COMPANY_ID, code, name, ProductType.GOODS, true, true, baseUnit, 1L);
        ReflectionTestUtils.setField(p, "id", id);
        ReflectionTestUtils.setField(p, "uid", uid);
        return p;
    }
}
