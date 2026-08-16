package com.erp.modules.products.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.erp.modules.iam.domain.entity.Branch;
import com.erp.modules.iam.domain.entity.Company;
import com.erp.modules.iam.domain.entity.Organisation;
import com.erp.modules.iam.repository.AppUserRepository;
import com.erp.modules.iam.repository.BranchRepository;
import com.erp.modules.iam.repository.CompanyRepository;
import com.erp.modules.iam.repository.OrganisationRepository;
import com.erp.modules.products.domain.dto.CreateBulkPackRequest;
import com.erp.modules.products.domain.dto.CreatePriceListRequest;
import com.erp.modules.products.domain.dto.CreateProductRequest;
import com.erp.modules.products.domain.dto.CreateUnitOfMeasureRequest;
import com.erp.modules.products.domain.dto.PriceListDto;
import com.erp.modules.products.domain.dto.ProductDto;
import com.erp.modules.products.domain.dto.SetProductPriceRequest;
import com.erp.modules.products.domain.dto.UnitListPriceDto;
import com.erp.modules.products.domain.dto.UnitOfMeasureDto;
import com.erp.modules.products.domain.entity.Product;
import com.erp.modules.products.domain.entity.ProductPrice;
import com.erp.modules.products.domain.entity.UnitOfMeasure;
import com.erp.modules.products.domain.enums.ProductType;
import com.erp.modules.products.repository.PriceListRepository;
import com.erp.modules.products.repository.ProductPriceRepository;
import com.erp.modules.products.repository.ProductRepository;
import com.erp.platform.common.money.Money;
import com.erp.platform.common.money.MoneyDto;
import com.erp.platform.security.RequestContext;
import com.erp.support.IamTestData;
import com.erp.support.PostgresIntegrationTest;
import java.math.BigDecimal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Regression IT for {@link PriceResolutionServiceImpl#resolveUnitListPrice} against real Postgres
 * (Testcontainers) — an adversarial review caught that the D-1 (ADR-0048) multi-unit-pricing
 * change introduced a live 500 the mock-based {@link PriceResolutionServiceImplTest} cannot
 * exercise: a product priced on MORE THAN ONE price list holds more than one BASE row
 * ({@code unit_id IS NULL}) in {@code product_prices} — the new partial unique index
 * {@code uq_product_price_base} is unique per {@code (product, price_list)}, not per product. The
 * original code called {@code Optional}-returning finders
 * ({@code findByProductIdAndUnitIdIsNull} / {@code findByProductIdAndUnitId}) which throw
 * {@code IncorrectResultSizeDataAccessException} (HTTP 500) the moment a second price list is
 * primed — a regression against the pre-D-1 {@code findFirst()} tolerance. Production code is
 * already fixed to {@code findFirstByProductIdAndUnitIdIsNullOrderByIdAsc} /
 * {@code findFirstByProductIdAndUnitIdOrderByIdAsc} (first-wins, LIMIT 1); this test is the only
 * committed coverage that actually creates two real base rows via JPA and proves the resolver
 * returns a price instead of throwing.
 */
class PriceResolutionServiceImplIT extends PostgresIntegrationTest {

    @Autowired private ProductService productService;
    @Autowired private PriceListService priceListService;
    @Autowired private UnitOfMeasureService unitService;
    @Autowired private PriceResolutionService priceResolutionService;
    @Autowired private OrganisationRepository organisations;
    @Autowired private CompanyRepository companies;
    @Autowired private BranchRepository branches;
    @Autowired private AppUserRepository users;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private IamTestData testData;
    // Repositories, not services: the drifted-row tests below must write a shape no service can
    // produce — that is the whole point of them.
    @Autowired private ProductRepository products;
    @Autowired private ProductPriceRepository productPrices;
    @Autowired private PriceListRepository priceLists;

    private Company companyA;
    private Long companyId;
    private String pcsUid;
    private Long pcsId;

    @BeforeEach
    void setUp() {
        testData.clearAll();
        Organisation org = organisations.save(new Organisation("Price Resolution Test Org"));
        companyA = companies.save(new Company(org, "PRXA", "Price Resolution Co A"));
        companyId = companyA.getId();
        Branch branchA = branches.save(new Branch(companyA, "PRX-A1", "Price Res Branch A1"));

        com.erp.modules.iam.domain.entity.AppUser root =
                new com.erp.modules.iam.domain.entity.AppUser(
                        "pricing_root", passwordEncoder.encode("RootPass1!"), "Pricing Root");
        root.setRoot(true);
        root.setOrganisationId(org.getId());
        root = users.save(root);

        RequestContext.set(new RequestContext.Principal(
                root.getId(), "pricing_root", true, companyId, branchA.getId(), null, org.getId()));

        UnitOfMeasureDto pcs = unitService.create(
                new CreateUnitOfMeasureRequest(companyA.getUid(), "PCS", "Pieces"));
        pcsUid = pcs.uid();
        pcsId = pcs.id();
    }

    @AfterEach
    void tearDown() {
        RequestContext.clear();
    }

    // -----------------------------------------------------------------------
    // Regression — base unit priced on TWO price lists must not throw
    // (IncorrectResultSizeDataAccessException / HTTP 500).
    // -----------------------------------------------------------------------

    @Test
    void resolveUnitListPrice_baseUnitPricedOnTwoPriceLists_returnsAPriceInsteadOfThrowing() {
        ProductDto product = productService.create(goodsRequest("Two-List Base Product"));

        PriceListDto listOne = priceListService.create(
                new CreatePriceListRequest(companyA.getUid(), "RETAIL_ONE", "Retail One"));
        PriceListDto listTwo = priceListService.create(
                new CreatePriceListRequest(companyA.getUid(), "RETAIL_TWO", "Retail Two"));

        productService.setPrice(product.uid(),
                new SetProductPriceRequest(listOne.uid(), new MoneyDto("1000.00", "TZS")));
        productService.setPrice(product.uid(),
                new SetProductPriceRequest(listTwo.uid(), new MoneyDto("2000.00", "TZS")));

        assertThatCode(() ->
                priceResolutionService.resolveUnitListPrice(companyId, product.id(), pcsId))
                .doesNotThrowAnyException();

        BigDecimal resolved =
                priceResolutionService.resolveUnitListPrice(companyId, product.id(), pcsId).amount();

        // First-wins across price lists (lowest-id row) — must be one of the two configured
        // amounts, never a thrown exception. compareTo (not isIn/.equals) because the resolved
        // amount carries Money's stored scale (e.g. "1000.0000"), not the input's "1000.00".
        assertThat(resolved.compareTo(new BigDecimal("1000.00")) == 0
                || resolved.compareTo(new BigDecimal("2000.00")) == 0)
                .as("resolved amount %s should equal one of the two configured list prices", resolved)
                .isTrue();
    }

    // -----------------------------------------------------------------------
    // Regression — explicit pack-unit override priced on TWO price lists must not throw either
    // (findFirstByProductIdAndUnitIdOrderByIdAsc path).
    // -----------------------------------------------------------------------

    @Test
    void resolveUnitListPrice_packUnitPricedOnTwoPriceLists_returnsAPriceInsteadOfThrowing() {
        ProductDto product = productService.create(goodsRequest("Two-List Pack Product"));

        UnitOfMeasureDto carton = unitService.create(
                new CreateUnitOfMeasureRequest(companyA.getUid(), "CTN", "Carton"));
        productService.addBulkPack(product.uid(),
                new CreateBulkPackRequest(carton.uid(), new BigDecimal("12")));

        PriceListDto listOne = priceListService.create(
                new CreatePriceListRequest(companyA.getUid(), "PACK_ONE", "Pack One"));
        PriceListDto listTwo = priceListService.create(
                new CreatePriceListRequest(companyA.getUid(), "PACK_TWO", "Pack Two"));

        // A base row is required for setPrice's upsert to have somewhere sane to fall back to on
        // other lookups, but the point of this case is the explicit per-unit (pack) override row —
        // set it on both lists for the carton unit.
        productService.setPrice(product.uid(),
                new SetProductPriceRequest(listOne.uid(), new MoneyDto("11500.00", "TZS"), carton.uid()));
        productService.setPrice(product.uid(),
                new SetProductPriceRequest(listTwo.uid(), new MoneyDto("12500.00", "TZS"), carton.uid()));

        Long cartonId = carton.id();
        assertThatCode(() ->
                priceResolutionService.resolveUnitListPrice(companyId, product.id(), cartonId))
                .doesNotThrowAnyException();

        BigDecimal resolved =
                priceResolutionService.resolveUnitListPrice(companyId, product.id(), cartonId).amount();

        // First-wins across price lists — must be one of the two configured pack amounts, not a
        // base-times-factor computation and never a thrown exception. compareTo (not isIn/.equals)
        // for the same scale reason as the base-unit case above.
        assertThat(resolved.compareTo(new BigDecimal("11500.00")) == 0
                || resolved.compareTo(new BigDecimal("12500.00")) == 0)
                .as("resolved amount %s should equal one of the two configured pack prices", resolved)
                .isTrue();
    }

    // -----------------------------------------------------------------------
    // Positive sanity — single price list still resolves to the plain base amount (documents
    // intent: the regression above is about MULTIPLE lists, not price resolution itself).
    // -----------------------------------------------------------------------

    @Test
    void resolveUnitListPrice_baseUnitPricedOnSingleList_returnsBaseAmount() {
        ProductDto product = productService.create(goodsRequest("Single-List Base Product"));

        PriceListDto onlyList = priceListService.create(
                new CreatePriceListRequest(companyA.getUid(), "RETAIL_SOLO", "Retail Solo"));
        productService.setPrice(product.uid(),
                new SetProductPriceRequest(onlyList.uid(), new MoneyDto("500.00", "TZS")));

        UnitListPriceDto resolved =
                priceResolutionService.resolveUnitListPrice(companyId, product.id(), pcsId);

        assertThat(resolved.amount()).isEqualByComparingTo("500.00");
        // Backward-compat constructor omits priceIncludesVat -> service/DB default stays EXCLUSIVE
        // (ADR-0056 D-2; inclusive-by-default is a UI affordance, not a service default).
        assertThat(resolved.vatInclusive()).isFalse();
    }

    // -----------------------------------------------------------------------
    // Base price keyed on the base unit id, not on NULL — the shape that made a
    // priced product ring 0.00 at the till on 2026-08-16.
    // -----------------------------------------------------------------------

    @Test
    void resolveUnitListPrice_basePriceKeyedOnTheBaseUnitId_stillResolves() {
        ProductDto product = productService.create(goodsRequest("Drifted Base Unit Product"));
        PriceListDto list = priceListService.create(
                new CreatePriceListRequest(companyA.getUid(), "RETAIL_DRIFT", "Retail Drift"));

        // Written through the REPOSITORY because no service path can produce this row any more:
        // setPrice coerces a base-unit uid to null (ADR-0048 D-1), and updateByUid now refuses the
        // base-unit change that used to create it. The row exists in databases where that change
        // was made BEFORE the refusal shipped, and the read path has to survive data the current
        // write path would never write. That is precisely why nothing caught this.
        Product entity = products.findById(product.id()).orElseThrow();
        UnitOfMeasure baseUnit = entity.getBaseUnit();
        productPrices.saveAndFlush(new ProductPrice(entity,
                priceLists.findById(list.id()).orElseThrow(),
                baseUnit,                                   // <- the drift: not null
                new Money(new BigDecimal("20000.00"), "TZS"),
                null));

        UnitListPriceDto resolved =
                priceResolutionService.resolveUnitListPrice(companyId, product.id(), pcsId);

        assertThat(resolved.amount())
                .as("the price is on screen in the back office; the till must be able to see it too")
                .isEqualByComparingTo("20000.00");
    }

    @Test
    void resolveUnitListPrice_nullRowWinsOverABaseUnitKeyedRow() {
        ProductDto product = productService.create(goodsRequest("Both Rows Product"));
        PriceListDto viaService = priceListService.create(
                new CreatePriceListRequest(companyA.getUid(), "RETAIL_NULLWINS", "Retail NullWins"));
        PriceListDto drifted = priceListService.create(
                new CreatePriceListRequest(companyA.getUid(), "RETAIL_DRIFTED", "Retail Drifted"));

        // The correct row, written the correct way (unit_id IS NULL).
        productService.setPrice(product.uid(),
                new SetProductPriceRequest(viaService.uid(), new MoneyDto("500.00", "TZS")));

        Product entity = products.findById(product.id()).orElseThrow();
        productPrices.saveAndFlush(new ProductPrice(entity,
                priceLists.findById(drifted.id()).orElseThrow(),
                entity.getBaseUnit(),
                new Money(new BigDecimal("999.00"), "TZS"),
                null));

        UnitListPriceDto resolved =
                priceResolutionService.resolveUnitListPrice(companyId, product.id(), pcsId);

        assertThat(resolved.amount())
                .as("the fallback is repair for drifted data, and must never outrank a correctly "
                        + "keyed row — otherwise it would silently change live pricing")
                .isEqualByComparingTo("500.00");
    }

    // -----------------------------------------------------------------------
    // Private helpers
    // -----------------------------------------------------------------------

    private CreateProductRequest goodsRequest(String name) {
        return new CreateProductRequest(
                companyA.getUid(), null, name, null, ProductType.GOODS, true, true, pcsUid,
                null, null, null, null, null, null, null, null, null, null, null);
    }
}
