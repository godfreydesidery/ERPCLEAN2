package com.erp.modules.products.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.erp.modules.products.domain.dto.ResolvePriceRequest;
import com.erp.modules.products.domain.dto.SellingPriceQuery;
import com.erp.modules.products.domain.dto.UnitListPriceDto;
import com.erp.modules.products.domain.dto.UnitPriceQuoteDto;
import com.erp.modules.products.domain.dto.UnitPriceQuoteResult;
import com.erp.modules.products.domain.entity.CustomerPrice;
import com.erp.modules.products.domain.entity.PriceList;
import com.erp.modules.products.domain.entity.Product;
import com.erp.modules.products.domain.entity.ProductBulkPack;
import com.erp.modules.products.domain.entity.ProductPrice;
import com.erp.modules.products.domain.entity.UnitOfMeasure;
import com.erp.modules.products.domain.enums.PriceSource;
import com.erp.modules.products.domain.enums.ProductType;
import com.erp.modules.products.domain.enums.UnitPriceStatus;
import com.erp.modules.products.repository.CustomerPriceRepository;
import com.erp.modules.products.repository.PriceListRepository;
import com.erp.modules.products.repository.PriceTierRepository;
import com.erp.modules.products.repository.ProductBulkPackRepository;
import com.erp.modules.products.repository.ProductPriceRepository;
import com.erp.modules.products.repository.ProductRepository;
import com.erp.modules.products.repository.PromotionRepository;
import com.erp.platform.common.domain.MasterStatus;
import com.erp.platform.common.money.Money;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Unit tests for the selling-price resolver: the unit-aware rules of ADR-0048 D-1/D-2 (explicit
 * pack row, else base × factor) and the list choice of PRD-01 (customer list &gt; company default
 * list &gt; legacy lowest-id row; archived and out-of-date lists skipped; document currency
 * preferred), plus customer contract prices (PRD-02, customer prices only).
 *
 * <p>The repository hands the resolver every price row of the product (oldest first); these tests
 * stub that list and assert which row wins. The real query is exercised against Postgres by
 * {@code SellingPriceResolutionIT}.
 */
class PriceResolutionServiceImplTest {

    private static final Long COMPANY_ID = 10L;
    private static final Long CUSTOMER_ID = 4242L;
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 10);

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

    /** The rows the repository returns for {@link #product}, in row-id order. */
    private final List<ProductPrice> rows = new ArrayList<>();
    private long nextRowId = 1;

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
                customerPrices, promotions, priceTiers, productPrices, bulkPacks, products, priceLists, com.erp.platform.common.time.CompanyCalendar.fixed(com.erp.platform.common.time.BusinessZone.DEFAULT, java.time.Clock.systemUTC()));

        baseUnit = unitWithId(1L, "BASEUID0000000000000040", "PCS");
        boxUnit = unitWithId(2L, "BOXUID00000000000000040", "BOX");
        unconfiguredUnit = unitWithId(3L, "BAGUID00000000000000040", "BAG");
        product = productWithId(100L, "PRODUID00000000000000040", baseUnit);

        when(products.findByCompanyIdAndId(COMPANY_ID, product.getId())).thenReturn(Optional.of(product));
        when(bulkPacks.findByProductId(product.getId()))
                .thenReturn(List.of(new ProductBulkPack(product, boxUnit, new BigDecimal("12"), 1L)));
        when(productPrices.findPricingRowsOfProduct(COMPANY_ID, product.getId())).thenReturn(rows);
    }

    // =========================================================================
    // ADR-0048 — unit-aware rules (walk-in, single list): unchanged behaviour
    // =========================================================================

    @Test
    void resolveUnitListPrice_explicitPerUnitOverride_used() {
        PriceList retail = list(500L, "RETAIL");
        row(retail, null, "100.0000");
        row(retail, boxUnit, "1150.0000");

        UnitListPriceDto price = service.resolveUnitListPrice(COMPANY_ID, product.getId(), boxUnit.getId());

        assertThat(price.amount()).isEqualByComparingTo("1150.0000");
        assertThat(price.vatInclusive()).isFalse();
    }

    @Test
    void resolveUnitListPrice_noExplicitPerUnit_fallsBackToBaseTimesFactor() {
        row(list(500L, "RETAIL"), null, "100.0000");

        UnitListPriceDto price = service.resolveUnitListPrice(COMPANY_ID, product.getId(), boxUnit.getId());

        // 100 (base) x 12 (factor) — the pack-unit under-charge bug ADR-0048 fixed.
        assertThat(price.amount()).isEqualByComparingTo("1200.0000");
        assertThat(price.vatInclusive()).isFalse();
    }

    @Test
    void resolveUnitListPrice_baseUnit_returnsBaseAmountTimesOne() {
        row(list(500L, "RETAIL"), null, "100.0000");

        UnitListPriceDto price = service.resolveUnitListPrice(COMPANY_ID, product.getId(), baseUnit.getId());

        assertThat(price.amount()).isEqualByComparingTo("100.0000");
        assertThat(price.source()).isEqualTo(PriceSource.LIST_PRICE);
    }

    @Test
    void resolveUnitListPrice_baseRowOnInclusiveList_carriesVatInclusiveTrue() {
        PriceList inclusive = list(500L, "RETAIL");
        inclusive.setPriceIncludesVat(true);
        row(inclusive, null, "1180.0000");

        UnitListPriceDto price = service.resolveUnitListPrice(COMPANY_ID, product.getId(), baseUnit.getId());

        assertThat(price.amount()).isEqualByComparingTo("1180.0000");
        assertThat(price.vatInclusive()).isTrue();
    }

    @Test
    void resolveUnitListPrice_packOverrideInclusive_inheritsItsOwnListFlag_notBaseRows() {
        // Legacy tier (no default): base row on an EXCLUSIVE list, the pack override on its OWN
        // INCLUSIVE list — the pack carries ITS OWN list's stance (ADR-0056 D-4).
        row(list(500L, "RETAIL"), null, "100.0000");
        PriceList packList = list(501L, "PACKS");
        packList.setPriceIncludesVat(true);
        row(packList, boxUnit, "1180.0000");

        UnitListPriceDto price = service.resolveUnitListPrice(COMPANY_ID, product.getId(), boxUnit.getId());

        assertThat(price.amount()).isEqualByComparingTo("1180.0000");
        assertThat(price.vatInclusive()).isTrue();
    }

    @Test
    void resolveUnitListPrice_unconfiguredUnit_rejectedLikeComputeQtyInBase() {
        row(list(500L, "RETAIL"), null, "100.0000");

        assertThatThrownBy(() -> service.resolveUnitListPrice(
                COMPANY_ID, product.getId(), unconfiguredUnit.getId()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not valid for this product");
    }

    @Test
    void resolveUnitListPrice_noPriceConfigured_rejectedAndNamesBothWaysOut() {
        // UAT wave 1: on a company with no price list — every company on day one — this was the only
        // thing a salesperson ever saw, and the old wording named no way out of it.
        assertThatThrownBy(() -> service.resolveUnitListPrice(
                COMPANY_ID, product.getId(), baseUnit.getId()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Set up a price list")
                .hasMessageContaining("enter a unit price on the line");
    }

    @Test
    void baseRowKeyedOnTheBaseUnitId_stillPrices_driftTolerance() {
        // A row written against a pack unit that later became the base unit (2026-08-16).
        row(list(500L, "RETAIL"), baseUnit, "20000.0000");

        UnitListPriceDto price = service.resolveUnitListPrice(COMPANY_ID, product.getId(), baseUnit.getId());

        assertThat(price.amount()).isEqualByComparingTo("20000.0000");
    }

    @Test
    void resolveUnitListPriceQuote_explicitPerUnitOverride_carriesAmountCurrencyAndList() {
        PriceList retail = list(500L, "RETAIL");
        row(retail, boxUnit, "1150.0000");
        row(retail, null, "100.0000");

        UnitPriceQuoteDto quote =
                service.resolveUnitListPriceQuote(COMPANY_ID, product.getId(), boxUnit.getId());

        assertThat(quote.amount()).isEqualByComparingTo("1150.0000");
        assertThat(quote.currency()).isEqualTo("TZS");
        assertThat(quote.vatInclusive()).isFalse();
        assertThat(quote.source()).isEqualTo(PriceSource.LIST_PRICE);
        assertThat(quote.priceListUid()).isEqualTo(retail.getUid());
        assertThat(quote.priceListName()).isEqualTo("RETAIL list");
    }

    @Test
    void resolveUnitListPrice_delegatesToTheQuote_soBothStayInAgreement() {
        PriceList inclusive = list(500L, "RETAIL");
        inclusive.setPriceIncludesVat(true);
        row(inclusive, null, "1180.0000");

        UnitListPriceDto narrow =
                service.resolveUnitListPrice(COMPANY_ID, product.getId(), baseUnit.getId());
        UnitPriceQuoteDto quote =
                service.resolveUnitListPriceQuote(COMPANY_ID, product.getId(), baseUnit.getId());

        assertThat(narrow.amount()).isEqualByComparingTo(quote.amount());
        assertThat(narrow.vatInclusive()).isEqualTo(quote.vatInclusive());
    }

    // =========================================================================
    // PRD-01 — which list prices the sale
    // =========================================================================

    @Test
    void noDefaultAndNoCustomerList_legacyLowestRowIdWins_unchangedForUnconfiguredCompanies() {
        // Row ids, not list ids, decide the legacy tier — exactly the pre-PRD-01 behaviour.
        row(list(900L, "WHOLESALE"), null, "2200.0000");   // created first
        row(list(100L, "RETAIL"), null, "2500.0000");

        assertThat(walkInBase().amount()).isEqualByComparingTo("2200.0000");
    }

    @Test
    void companyDefaultList_beatsAnOlderRowOnAnotherList() {
        row(list(900L, "WHOLESALE"), null, "2200.0000");   // first row ever created
        PriceList retail = list(100L, "RETAIL");
        retail.setDefault(true);
        row(retail, null, "2500.0000");

        UnitPriceQuoteDto quote = walkInBase();

        assertThat(quote.amount()).isEqualByComparingTo("2500.0000");
        assertThat(quote.priceListUid()).isEqualTo(retail.getUid());
    }

    @Test
    void severalListsFlaggedDefault_lowestListIdWins() {
        PriceList b = list(700L, "B");
        b.setDefault(true);
        row(b, null, "700.0000");
        PriceList a = list(600L, "A");
        a.setDefault(true);
        row(a, null, "600.0000");

        assertThat(walkInBase().amount()).isEqualByComparingTo("600.0000");
    }

    @Test
    void customerDefaultList_beatsTheCompanyDefault() {
        PriceList retail = list(100L, "RETAIL");
        retail.setDefault(true);
        row(retail, null, "55000.0000");
        PriceList wholesale = list(300L, "WHOLESALE");
        row(wholesale, null, "52500.0000");

        UnitPriceQuoteDto quote = forCustomer(wholesale.getId(), baseUnit.getId(), "TZS", null);

        assertThat(quote.amount()).isEqualByComparingTo("52500.0000");
        assertThat(quote.priceListName()).isEqualTo("WHOLESALE list");
    }

    @Test
    void customerListWithoutThisProduct_fallsThroughToTheCompanyDefault() {
        PriceList retail = list(100L, "RETAIL");
        retail.setDefault(true);
        row(retail, null, "55000.0000");
        list(300L, "WHOLESALE"); // no row for this product

        assertThat(forCustomer(300L, baseUnit.getId(), "TZS", null).amount())
                .isEqualByComparingTo("55000.0000");
    }

    @Test
    void archivedCustomerList_isSkipped() {
        PriceList retail = list(100L, "RETAIL");
        retail.setDefault(true);
        row(retail, null, "55000.0000");
        PriceList wholesale = list(300L, "WHOLESALE");
        wholesale.setStatus(MasterStatus.ARCHIVED);
        row(wholesale, null, "52500.0000");

        assertThat(forCustomer(300L, baseUnit.getId(), "TZS", null).amount())
                .isEqualByComparingTo("55000.0000");
    }

    @Test
    void customerListOutsideItsValidityWindow_isSkipped() {
        PriceList retail = list(100L, "RETAIL");
        retail.setDefault(true);
        row(retail, null, "55000.0000");
        PriceList promo = list(300L, "XMAS");
        promo.setEffectiveFrom(TODAY.plusDays(1));
        row(promo, null, "50000.0000");
        PriceList expired = list(301L, "OLD");
        expired.setEffectiveTo(TODAY.minusDays(1));
        row(expired, null, "40000.0000");

        assertThat(forCustomer(300L, baseUnit.getId(), "TZS", null).amount())
                .isEqualByComparingTo("55000.0000");
        assertThat(forCustomer(301L, baseUnit.getId(), "TZS", null).amount())
                .isEqualByComparingTo("55000.0000");
    }

    @Test
    void listValidOnTheBusinessDate_inclusiveBounds_isUsed() {
        PriceList window = list(300L, "WINDOW");
        window.setEffectiveFrom(TODAY);
        window.setEffectiveTo(TODAY);
        row(window, null, "50000.0000");

        assertThat(walkInBase().amount()).isEqualByComparingTo("50000.0000");
    }

    @Test
    void archivedRowsNeverPriceTheLegacyTier_evenWhenOlder() {
        PriceList lastYear = list(50L, "2025");
        lastYear.setStatus(MasterStatus.ARCHIVED);
        row(lastYear, null, "1000.0000");          // lowest row id
        row(list(60L, "2026"), null, "1200.0000");

        assertThat(walkInBase().amount()).isEqualByComparingTo("1200.0000");
    }

    @Test
    void rowOutsideItsOwnValidityWindow_isSkipped_PRD25() {
        PriceList retail = list(100L, "RETAIL");
        ProductPrice stale = row(retail, null, "1000.0000");
        stale.setEffectiveTo(TODAY.minusDays(1));
        row(list(101L, "OTHER"), null, "1300.0000");

        assertThat(walkInBase().amount()).isEqualByComparingTo("1300.0000");
    }

    @Test
    void everyRowOnAnArchivedList_isNoPrice() {
        PriceList gone = list(100L, "RETAIL");
        gone.setStatus(MasterStatus.ARCHIVED);
        row(gone, null, "1000.0000");

        UnitPriceQuoteResult result = service.findSellingPriceQuote(
                new SellingPriceQuery(COMPANY_ID, product.getId(), baseUnit.getId(),
                        null, null, null, null, TODAY));

        assertThat(result.status()).isEqualTo(UnitPriceStatus.NO_PRICE);
    }

    @Test
    void packLine_onTheChosenList_usesThatListsBaseTimesFactor_notAnotherListsPackRow() {
        // A wholesale customer buying a BOX: wholesale has a base price only; retail has an explicit
        // box price. The customer's own list decides — 2,200 x 12, never retail's box price.
        PriceList retail = list(100L, "RETAIL");
        retail.setDefault(true);
        row(retail, null, "2500.0000");
        row(retail, boxUnit, "29000.0000");
        PriceList wholesale = list(300L, "WHOLESALE");
        row(wholesale, null, "2200.0000");

        assertThat(forCustomer(300L, boxUnit.getId(), "TZS", null).amount())
                .isEqualByComparingTo("26400.0000");
        // ...while a walk-in buying a box gets retail's explicit box price.
        assertThat(service.resolveUnitListPrice(COMPANY_ID, product.getId(), boxUnit.getId()).amount())
                .isEqualByComparingTo("29000.0000");
    }

    @Test
    void documentCurrency_rowInThatCurrencyIsPreferred_otherCurrencyOnlyAsALastResort() {
        PriceList retail = list(100L, "RETAIL");
        retail.setDefault(true);
        row(retail, null, "2500.0000");                    // TZS
        PriceList dollars = list(200L, "USD");
        row(dollars, null, "1.0000", "USD");

        assertThat(quote(baseUnit.getId(), "USD").amount()).isEqualByComparingTo("1.0000");
        assertThat(quote(baseUnit.getId(), "USD").currency()).isEqualTo("USD");
        assertThat(quote(baseUnit.getId(), "TZS").amount()).isEqualByComparingTo("2500.0000");
        // No EUR row anywhere: the long-standing tolerance prices it from what exists.
        assertThat(quote(baseUnit.getId(), "EUR").amount()).isEqualByComparingTo("2500.0000");
    }

    // =========================================================================
    // PRD-02 — customer contract prices
    // =========================================================================

    @Test
    void customerContractPrice_beatsTheirListPrice_andInheritsThatListsVatStance() {
        PriceList retail = list(100L, "RETAIL");
        retail.setDefault(true);
        retail.setPriceIncludesVat(true);
        row(retail, null, "800.0000");
        contract("650.0000", "TZS");

        UnitPriceQuoteDto quote = forCustomer(null, baseUnit.getId(), "TZS", null);

        assertThat(quote.amount()).isEqualByComparingTo("650.0000");
        assertThat(quote.source()).isEqualTo(PriceSource.CUSTOMER_PRICE);
        // Entered on the same basis as the prices this customer sees: VAT-inclusive.
        assertThat(quote.vatInclusive()).isTrue();
    }

    @Test
    void customerContractPrice_isPerBaseUnit_soAPackLinePaysTimesFactor() {
        row(list(100L, "RETAIL"), boxUnit, "9000.0000");
        contract("650.0000", "TZS");

        assertThat(forCustomer(null, boxUnit.getId(), "TZS", null).amount())
                .isEqualByComparingTo("7800.0000");
    }

    @Test
    void customerContractPrice_withNoListPriceAtAll_stillPrices_exclusive() {
        contract("650.0000", "TZS");

        UnitPriceQuoteDto quote = forCustomer(null, baseUnit.getId(), "TZS", null);

        assertThat(quote.amount()).isEqualByComparingTo("650.0000");
        assertThat(quote.vatInclusive()).isFalse();
    }

    @Test
    void customerContractPrice_belowItsMinimumQuantity_listPriceStands() {
        row(list(100L, "RETAIL"), null, "800.0000");
        CustomerPrice cp = contract("650.0000", "TZS");
        cp.setMinQty(new BigDecimal("24"));

        // 2 boxes = 24 base units: meets it. 23 pieces: does not.
        assertThat(forCustomer(null, boxUnit.getId(), "TZS", new BigDecimal("2")).amount())
                .isEqualByComparingTo("7800.0000");
        assertThat(forCustomer(null, baseUnit.getId(), "TZS", new BigDecimal("23")).amount())
                .isEqualByComparingTo("800.0000");
    }

    @Test
    void customerContractPrice_inAnotherCurrency_orAnotherCompany_isIgnored() {
        row(list(100L, "RETAIL"), null, "800.0000");
        contract("1.0000", "USD");

        assertThat(forCustomer(null, baseUnit.getId(), "TZS", null).amount())
                .isEqualByComparingTo("800.0000");

        CustomerPrice foreign = new CustomerPrice(99L, CUSTOMER_ID, product.getId(),
                new BigDecimal("1.0000"), "TZS", null, null, 1L);
        when(customerPrices.findActiveForCustomerProduct(CUSTOMER_ID, product.getId(), TODAY))
                .thenReturn(Optional.of(foreign));
        assertThat(forCustomer(null, baseUnit.getId(), "TZS", null).amount())
                .isEqualByComparingTo("800.0000");
    }

    @Test
    void walkIn_neverConsultsCustomerPrices() {
        row(list(100L, "RETAIL"), null, "800.0000");

        service.resolveUnitListPrice(COMPANY_ID, product.getId(), baseUnit.getId());

        verify(customerPrices, never()).findActiveForCustomerProduct(anyLong(), anyLong(), any());
    }

    // =========================================================================
    // ADR-0029 resolve() — still dormant (PRD-02 tiers/promotions); smoke only
    // =========================================================================

    @Test
    void resolve_nullUnitBackCompat_stillReturnsBaseListPrice() {
        PriceList retail = list(500L, "RETAIL");
        when(productPrices.findByProductIdAndPriceListIdAndUnitIdIsNull(product.getId(), 500L))
                .thenReturn(Optional.of(new ProductPrice(product, retail, null,
                        new Money(new BigDecimal("100.0000"), "TZS"), 1L)));

        var result = service.resolve(new ResolvePriceRequest(
                COMPANY_ID, null, product.getId(), BigDecimal.ONE, LocalDate.now(), 500L, null));

        assertThat(result.unitPriceAmount()).isEqualByComparingTo("100.0000");
        assertThat(result.vatInclusive()).isFalse();
    }

    @Test
    void resolve_listPriceOnInclusiveList_carriesVatInclusiveTrue() {
        PriceList inclusive = list(500L, "RETAIL");
        inclusive.setPriceIncludesVat(true);
        when(productPrices.findByProductIdAndPriceListIdAndUnitIdIsNull(product.getId(), 500L))
                .thenReturn(Optional.of(new ProductPrice(product, inclusive, null,
                        new Money(new BigDecimal("1180.0000"), "TZS"), 1L)));

        var result = service.resolve(new ResolvePriceRequest(
                COMPANY_ID, null, product.getId(), BigDecimal.ONE, LocalDate.now(), 500L, null));

        assertThat(result.unitPriceAmount()).isEqualByComparingTo("1180.0000");
        assertThat(result.vatInclusive()).isTrue();
    }

    // -------------------------------------------------------------------------
    // Fixture helpers
    // -------------------------------------------------------------------------

    private UnitPriceQuoteDto walkInBase() {
        return quote(baseUnit.getId(), null);
    }

    private UnitPriceQuoteDto quote(Long unitId, String currency) {
        UnitPriceQuoteResult result = service.findSellingPriceQuote(new SellingPriceQuery(
                COMPANY_ID, product.getId(), unitId, null, null, currency, null, TODAY));
        assertThat(result.status()).isEqualTo(UnitPriceStatus.RESOLVED);
        return result.price();
    }

    private UnitPriceQuoteDto forCustomer(Long customerListId, Long unitId, String currency,
                                          BigDecimal quantity) {
        UnitPriceQuoteResult result = service.findSellingPriceQuote(new SellingPriceQuery(
                COMPANY_ID, product.getId(), unitId, CUSTOMER_ID, customerListId, currency,
                quantity, TODAY));
        assertThat(result.status()).isEqualTo(UnitPriceStatus.RESOLVED);
        return result.price();
    }

    private CustomerPrice contract(String amount, String currency) {
        CustomerPrice cp = new CustomerPrice(COMPANY_ID, CUSTOMER_ID, product.getId(),
                new BigDecimal(amount), currency, null, null, 1L);
        when(customerPrices.findActiveForCustomerProduct(CUSTOMER_ID, product.getId(), TODAY))
                .thenReturn(Optional.of(cp));
        return cp;
    }

    private static PriceList list(Long id, String code) {
        PriceList priceList = new PriceList(COMPANY_ID, code, code + " list", 1L);
        ReflectionTestUtils.setField(priceList, "id", id);
        ReflectionTestUtils.setField(priceList, "uid", String.format("PLUID%021d", id));
        return priceList;
    }

    private ProductPrice row(PriceList priceList, UnitOfMeasure unit, String amount) {
        return row(priceList, unit, amount, "TZS");
    }

    private ProductPrice row(PriceList priceList, UnitOfMeasure unit, String amount, String currency) {
        ProductPrice pp = new ProductPrice(product, priceList, unit,
                new Money(new BigDecimal(amount), currency), 1L);
        ReflectionTestUtils.setField(pp, "id", nextRowId++);
        rows.add(pp);
        return pp;
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
