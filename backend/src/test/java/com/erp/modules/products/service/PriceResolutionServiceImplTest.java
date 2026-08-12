package com.erp.modules.products.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.erp.modules.products.domain.dto.ResolvePriceRequest;
import com.erp.modules.products.domain.dto.UnitListPriceDto;
import com.erp.modules.products.domain.dto.UnitPriceQuoteDto;
import com.erp.modules.products.domain.entity.PriceList;
import com.erp.modules.products.domain.entity.Product;
import com.erp.modules.products.domain.entity.ProductBulkPack;
import com.erp.modules.products.domain.entity.ProductPrice;
import com.erp.modules.products.domain.entity.UnitOfMeasure;
import com.erp.modules.products.domain.enums.ProductType;
import com.erp.modules.products.repository.CustomerPriceRepository;
import com.erp.modules.products.repository.PriceListRepository;
import com.erp.modules.products.repository.PriceTierRepository;
import com.erp.modules.products.repository.ProductBulkPackRepository;
import com.erp.modules.products.repository.ProductPriceRepository;
import com.erp.modules.products.repository.ProductRepository;
import com.erp.modules.products.repository.PromotionRepository;
import com.erp.platform.common.money.Money;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Unit tests for {@link PriceResolutionServiceImpl#resolveUnitListPrice} (ADR-0048 D-1/D-2) —
 * the narrow, unit-aware list-price resolver that replaces the sales modules' three identical
 * "first product_prices row for the company" copies (the latent pack-unit under-charge bug).
 *
 * <p>{@link PriceResolutionServiceImpl#resolve} (customer/promo/tier) stays dead / unused by any
 * caller in D-1 — not exercised here beyond a single NULL-unit back-compat smoke test confirming
 * the ambiguous {@code findByProductIdAndPriceListId} fix in {@code resolveListOrTier} compiles
 * and behaves.
 */
class PriceResolutionServiceImplTest {

    private static final Long COMPANY_ID = 10L;

    private CustomerPriceRepository customerPrices;
    private PromotionRepository promotions;
    private PriceTierRepository priceTiers;
    private ProductPriceRepository productPrices;
    private ProductBulkPackRepository bulkPacks;
    private ProductRepository products;
    private PriceListRepository priceLists;
    private PriceResolutionServiceImpl service;

    private Product product;
    private UnitOfMeasure baseUnit;
    private UnitOfMeasure boxUnit;
    private UnitOfMeasure unconfiguredUnit;

    @BeforeEach
    void setUp() {
        customerPrices = mock(CustomerPriceRepository.class);
        promotions = mock(PromotionRepository.class);
        priceTiers = mock(PriceTierRepository.class);
        productPrices = mock(ProductPriceRepository.class);
        bulkPacks = mock(ProductBulkPackRepository.class);
        products = mock(ProductRepository.class);
        priceLists = mock(PriceListRepository.class);

        service = new PriceResolutionServiceImpl(
                customerPrices, promotions, priceTiers, productPrices, bulkPacks, products, priceLists);

        baseUnit = unitWithId(1L, "BASEUID0000000000000040", "PCS");
        boxUnit = unitWithId(2L, "BOXUID00000000000000040", "BOX");
        unconfiguredUnit = unitWithId(3L, "BAGUID00000000000000040", "BAG");
        product = productWithId(100L, "PRODUID00000000000000040", baseUnit);

        when(products.findByCompanyIdAndId(COMPANY_ID, product.getId())).thenReturn(Optional.of(product));
        when(bulkPacks.findByProductId(product.getId()))
                .thenReturn(List.of(new ProductBulkPack(product, boxUnit, new BigDecimal("12"), 1L)));
    }

    @Test
    void resolveUnitListPrice_explicitPerUnitOverride_used() {
        ProductPrice packRow = priceRow(boxUnit, "1150.0000");
        when(productPrices.findFirstByProductIdAndUnitIdOrderByIdAsc(product.getId(), boxUnit.getId()))
                .thenReturn(Optional.of(packRow));

        UnitListPriceDto price = service.resolveUnitListPrice(COMPANY_ID, product.getId(), boxUnit.getId());

        assertThat(price.amount()).isEqualByComparingTo("1150.0000");
        assertThat(price.vatInclusive()).isFalse();
    }

    @Test
    void resolveUnitListPrice_noExplicitPerUnit_fallsBackToBaseTimesFactor() {
        when(productPrices.findFirstByProductIdAndUnitIdOrderByIdAsc(product.getId(), boxUnit.getId()))
                .thenReturn(Optional.empty());
        when(productPrices.findFirstByProductIdAndUnitIdIsNullOrderByIdAsc(product.getId()))
                .thenReturn(Optional.of(priceRow(null, "100.0000")));

        UnitListPriceDto price = service.resolveUnitListPrice(COMPANY_ID, product.getId(), boxUnit.getId());

        // 100 (base) x 12 (factor) — the pack-unit under-charge bug this ADR fixes.
        assertThat(price.amount()).isEqualByComparingTo("1200.0000");
        assertThat(price.vatInclusive()).isFalse();
    }

    @Test
    void resolveUnitListPrice_baseUnit_returnsBaseAmountTimesOne() {
        when(productPrices.findFirstByProductIdAndUnitIdIsNullOrderByIdAsc(product.getId()))
                .thenReturn(Optional.of(priceRow(null, "100.0000")));

        UnitListPriceDto price = service.resolveUnitListPrice(COMPANY_ID, product.getId(), baseUnit.getId());

        assertThat(price.amount()).isEqualByComparingTo("100.0000");
        assertThat(price.vatInclusive()).isFalse();
    }

    // -------------------------------------------------------------------------
    // ADR-0056 — VAT-inclusive stance threaded through resolveUnitListPrice
    // -------------------------------------------------------------------------

    @Test
    void resolveUnitListPrice_baseRowOnInclusiveList_carriesVatInclusiveTrue() {
        when(productPrices.findFirstByProductIdAndUnitIdIsNullOrderByIdAsc(product.getId()))
                .thenReturn(Optional.of(priceRow(null, "1180.0000", true)));

        UnitListPriceDto price = service.resolveUnitListPrice(COMPANY_ID, product.getId(), baseUnit.getId());

        assertThat(price.amount()).isEqualByComparingTo("1180.0000");
        assertThat(price.vatInclusive()).isTrue();
    }

    @Test
    void resolveUnitListPrice_packOverrideInclusive_inheritsItsOwnListFlag_notBaseRows() {
        // Base row lives on an EXCLUSIVE list; the pack override lives on its OWN INCLUSIVE list —
        // the pack must carry ITS OWN list's stance (ADR-0056 D-4), never the base row's.
        when(productPrices.findFirstByProductIdAndUnitIdIsNullOrderByIdAsc(product.getId()))
                .thenReturn(Optional.of(priceRow(null, "100.0000", false)));
        ProductPrice packRow = priceRow(boxUnit, "1180.0000", true);
        when(productPrices.findFirstByProductIdAndUnitIdOrderByIdAsc(product.getId(), boxUnit.getId()))
                .thenReturn(Optional.of(packRow));

        UnitListPriceDto price = service.resolveUnitListPrice(COMPANY_ID, product.getId(), boxUnit.getId());

        assertThat(price.amount()).isEqualByComparingTo("1180.0000");
        assertThat(price.vatInclusive()).isTrue();
    }

    @Test
    void resolveUnitListPrice_unconfiguredUnit_rejectedLikeComputeQtyInBase() {
        when(productPrices.findFirstByProductIdAndUnitIdIsNullOrderByIdAsc(product.getId()))
                .thenReturn(Optional.of(priceRow(null, "100.0000")));

        assertThatThrownBy(() -> service.resolveUnitListPrice(
                COMPANY_ID, product.getId(), unconfiguredUnit.getId()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not valid for this product");
    }

    @Test
    void resolveUnitListPrice_noPriceConfigured_rejectedAndNamesBothWaysOut() {
        // UAT wave 1: on a company with no price list — every company on day one — this was the only
        // thing a salesperson ever saw, and the old wording ("Product has no price configured for
        // this company.") named no way out of it. Both remedies must be in the message.
        when(productPrices.findFirstByProductIdAndUnitIdIsNullOrderByIdAsc(product.getId()))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.resolveUnitListPrice(
                COMPANY_ID, product.getId(), baseUnit.getId()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Set up a price list")
                .hasMessageContaining("enter a unit price on the line");
    }

    @Test
    void resolve_nullUnitBackCompat_stillReturnsBaseListPrice() {
        // Dead path (ADR-0048 D-1 keeps PriceResolutionService.resolve unwired) — smoke test that
        // the findByProductIdAndPriceListId -> findByProductIdAndPriceListIdAndUnitIdIsNull fix
        // still resolves the base row correctly when unitId is null (pre-D-1 shape).
        when(productPrices.findByProductIdAndPriceListIdAndUnitIdIsNull(product.getId(), 500L))
                .thenReturn(Optional.of(priceRow(null, "100.0000")));

        var result = service.resolve(new ResolvePriceRequest(
                COMPANY_ID, null, product.getId(), BigDecimal.ONE, LocalDate.now(), 500L, null));

        assertThat(result.unitPriceAmount()).isEqualByComparingTo("100.0000");
        assertThat(result.vatInclusive()).isFalse();
    }

    @Test
    void resolve_listPriceOnInclusiveList_carriesVatInclusiveTrue() {
        // ADR-0056: even though resolve() is dead (no caller), it stays honest about the flag.
        when(productPrices.findByProductIdAndPriceListIdAndUnitIdIsNull(product.getId(), 500L))
                .thenReturn(Optional.of(priceRow(null, "1180.0000", true)));

        var result = service.resolve(new ResolvePriceRequest(
                COMPANY_ID, null, product.getId(), BigDecimal.ONE, LocalDate.now(), 500L, null));

        assertThat(result.unitPriceAmount()).isEqualByComparingTo("1180.0000");
        assertThat(result.vatInclusive()).isTrue();
    }

    // -------------------------------------------------------------------------
    // resolveUnitListPriceQuote — same rules, plus the currency the batch price-read API needs
    // -------------------------------------------------------------------------

    @Test
    void resolveUnitListPriceQuote_explicitPerUnitOverride_carriesAmountAndCurrency() {
        when(productPrices.findFirstByProductIdAndUnitIdOrderByIdAsc(product.getId(), boxUnit.getId()))
                .thenReturn(Optional.of(priceRow(boxUnit, "1150.0000")));

        UnitPriceQuoteDto quote =
                service.resolveUnitListPriceQuote(COMPANY_ID, product.getId(), boxUnit.getId());

        assertThat(quote.amount()).isEqualByComparingTo("1150.0000");
        assertThat(quote.currency()).isEqualTo("TZS");
        assertThat(quote.vatInclusive()).isFalse();
    }

    @Test
    void resolveUnitListPriceQuote_derivedFromBaseRow_carriesTheBaseRowsCurrency() {
        when(productPrices.findFirstByProductIdAndUnitIdOrderByIdAsc(product.getId(), boxUnit.getId()))
                .thenReturn(Optional.empty());
        when(productPrices.findFirstByProductIdAndUnitIdIsNullOrderByIdAsc(product.getId()))
                .thenReturn(Optional.of(priceRow(null, "100.0000")));

        UnitPriceQuoteDto quote =
                service.resolveUnitListPriceQuote(COMPANY_ID, product.getId(), boxUnit.getId());

        assertThat(quote.amount()).isEqualByComparingTo("1200.0000"); // 100 x 12
        assertThat(quote.currency()).isEqualTo("TZS");
    }

    @Test
    void resolveUnitListPrice_delegatesToTheQuote_soBothStayInAgreement() {
        when(productPrices.findFirstByProductIdAndUnitIdIsNullOrderByIdAsc(product.getId()))
                .thenReturn(Optional.of(priceRow(null, "1180.0000", true)));

        UnitListPriceDto narrow =
                service.resolveUnitListPrice(COMPANY_ID, product.getId(), baseUnit.getId());
        UnitPriceQuoteDto quote =
                service.resolveUnitListPriceQuote(COMPANY_ID, product.getId(), baseUnit.getId());

        assertThat(narrow.amount()).isEqualByComparingTo(quote.amount());
        assertThat(narrow.vatInclusive()).isEqualTo(quote.vatInclusive());
    }

    // -------------------------------------------------------------------------
    // Fixture helpers
    // -------------------------------------------------------------------------

    private ProductPrice priceRow(UnitOfMeasure unit, String amount) {
        return priceRow(unit, amount, false);
    }

    private ProductPrice priceRow(UnitOfMeasure unit, String amount, boolean vatInclusive) {
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

    private static Product productWithId(Long id, String uid, UnitOfMeasure baseUnit) {
        Product p = new Product(COMPANY_ID, "PROD-0001", "Test Product", ProductType.GOODS,
                true, true, baseUnit, 1L);
        ReflectionTestUtils.setField(p, "id", id);
        ReflectionTestUtils.setField(p, "uid", uid);
        return p;
    }
}
