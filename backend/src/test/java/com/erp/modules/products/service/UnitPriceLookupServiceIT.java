package com.erp.modules.products.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.erp.modules.iam.domain.entity.AppUser;
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
import com.erp.modules.products.domain.dto.ResolveUnitPricesRequest;
import com.erp.modules.products.domain.dto.ResolvedUnitPriceDto;
import com.erp.modules.products.domain.dto.SetProductPriceRequest;
import com.erp.modules.products.domain.dto.UnitOfMeasureDto;
import com.erp.modules.products.domain.enums.ProductType;
import com.erp.modules.products.domain.enums.UnitPriceStatus;
import com.erp.platform.common.money.MoneyDto;
import com.erp.platform.security.RequestContext;
import com.erp.support.IamTestData;
import com.erp.support.PostgresIntegrationTest;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Integration test for the batch price read ({@code POST /api/v1/product-prices/resolve}) against
 * real Postgres and, crucially, the REAL Spring transaction proxies.
 *
 * <p><b>Why this file exists.</b> {@link UnitPriceLookupServiceImplTest} asserts the same
 * {@code NO_PRICE} / {@code UNIT_NOT_APPLICABLE} statuses and passed green while the live endpoint
 * returned HTTP 500 for every batch containing one unpriceable product — because it builds
 * {@code PriceResolutionServiceImpl} with {@code new}. A plain object has no transaction proxy, so
 * the resolver's {@code IllegalArgumentException} / {@code IllegalStateException} was just an
 * exception the wrapper caught. In production both services are {@code @Transactional} and the
 * inner one JOINS the outer read-only transaction: throwing out of the inner proxy set
 * rollback-only, the outer {@code catch} produced a perfectly good list, and the COMMIT then failed
 * with {@code UnexpectedRollbackException}. The documented per-row statuses were unreachable.
 *
 * <p>So these tests deliberately call the service from a NON-transactional test method: the
 * transaction is opened and committed by the service's own proxy, exactly as an HTTP request does.
 * Run them against the pre-fix code (throwing resolver + try/catch wrapper) and every one of them
 * fails on commit — that is the property that makes them worth having.
 */
class UnitPriceLookupServiceIT extends PostgresIntegrationTest {

    @Autowired private UnitPriceLookupService unitPriceLookupService;
    @Autowired private ProductService productService;
    @Autowired private PriceListService priceListService;
    @Autowired private UnitOfMeasureService unitService;
    @Autowired private OrganisationRepository organisations;
    @Autowired private CompanyRepository companies;
    @Autowired private BranchRepository branches;
    @Autowired private AppUserRepository users;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private IamTestData testData;

    private Company company;
    private String pcsUid;
    private PriceListDto retail;

    @BeforeEach
    void setUp() {
        testData.clearAll();
        Organisation org = organisations.save(new Organisation("Batch Price Test Org"));
        company = companies.save(new Company(org, "BPXA", "Batch Price Co"));
        Branch branch = branches.save(new Branch(company, "BPX-A1", "Batch Price Branch"));

        AppUser root = new AppUser("batch_price_root", passwordEncoder.encode("RootPass1!"),
                "Batch Price Root");
        root.setRoot(true);
        root = users.save(root);

        RequestContext.set(new RequestContext.Principal(
                root.getId(), "batch_price_root", true, company.getId(), branch.getId(), null));

        UnitOfMeasureDto pcs = unitService.create(
                new CreateUnitOfMeasureRequest(company.getUid(), "PCS", "Pieces"));
        pcsUid = pcs.uid();
        retail = priceListService.create(
                new CreatePriceListRequest(company.getUid(), "RETAIL", "Retail"));
    }

    @AfterEach
    void tearDown() {
        RequestContext.clear();
    }

    // -----------------------------------------------------------------------
    // The reproduction: one unpriceable row must not kill the batch OR the transaction
    // -----------------------------------------------------------------------

    @Test
    void mixedBatch_onePriceableOneNot_returnsBothRowsWithPerRowStatuses() {
        ProductDto priced = productService.create(goodsRequest("Priced Soap"));
        ProductDto unpriced = productService.create(goodsRequest("Unpriced Sugar"));
        productService.setPrice(priced.uid(),
                new SetProductPriceRequest(retail.uid(), new MoneyDto("1500.00", "TZS")));

        ResolveUnitPricesRequest request =
                new ResolveUnitPricesRequest(List.of(priced.uid(), unpriced.uid()), null);

        // Pre-fix this threw UnexpectedRollbackException at commit — HTTP 500 for the whole page.
        assertThatCode(() -> unitPriceLookupService.resolve(request)).doesNotThrowAnyException();

        List<ResolvedUnitPriceDto> rows = unitPriceLookupService.resolve(request);

        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).productUid()).isEqualTo(priced.uid());
        assertThat(rows.get(0).status()).isEqualTo(UnitPriceStatus.RESOLVED);
        assertThat(rows.get(0).amount()).isEqualByComparingTo("1500.00");
        assertThat(rows.get(0).currency()).isEqualTo("TZS");

        assertThat(rows.get(1).productUid()).isEqualTo(unpriced.uid());
        assertThat(rows.get(1).status()).isEqualTo(UnitPriceStatus.NO_PRICE);
        assertThat(rows.get(1).amount()).isNull();
        assertThat(rows.get(1).currency()).isNull();
    }

    @Test
    void productWithNoPriceRow_isNoPrice_notAnError() {
        ProductDto unpriced = productService.create(goodsRequest("No Price At All"));

        List<ResolvedUnitPriceDto> rows = unitPriceLookupService.resolve(
                new ResolveUnitPricesRequest(List.of(unpriced.uid()), null));

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).status()).isEqualTo(UnitPriceStatus.NO_PRICE);
        assertThat(rows.get(0).amount()).isNull();
    }

    @Test
    void unitThatIsNeitherBaseNorPack_isUnitNotApplicable_notAnError() {
        ProductDto product = productService.create(goodsRequest("Piece Only Product"));
        productService.setPrice(product.uid(),
                new SetProductPriceRequest(retail.uid(), new MoneyDto("900.00", "TZS")));
        // A real unit of this company, but NOT configured as a pack of this product.
        UnitOfMeasureDto crate = unitService.create(
                new CreateUnitOfMeasureRequest(company.getUid(), "CRATE", "Crate"));

        List<ResolvedUnitPriceDto> rows = unitPriceLookupService.resolve(
                new ResolveUnitPricesRequest(List.of(product.uid()), crate.uid()));

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).status()).isEqualTo(UnitPriceStatus.UNIT_NOT_APPLICABLE);
        assertThat(rows.get(0).unitUid()).isEqualTo(crate.uid());
        assertThat(rows.get(0).amount()).isNull();
    }

    /**
     * The mixed case a POS till actually hits: one requested unit applied across products that do
     * not all carry it. Both "no" reasons and a RESOLVED row in ONE transaction — the strongest
     * form of the regression, since pre-fix the first rejection alone poisoned the commit.
     */
    @Test
    void oneRequestedUnitAcrossAMixedBatch_yieldsResolvedNoPriceAndUnitNotApplicableTogether() {
        UnitOfMeasureDto box = unitService.create(
                new CreateUnitOfMeasureRequest(company.getUid(), "BOX", "Box"));

        ProductDto boxed = productService.create(goodsRequest("Boxed Priced"));
        productService.addBulkPack(boxed.uid(),
                new CreateBulkPackRequest(box.uid(), new BigDecimal("12")));
        productService.setPrice(boxed.uid(),
                new SetProductPriceRequest(retail.uid(), new MoneyDto("100.00", "TZS")));

        ProductDto pieceOnly = productService.create(goodsRequest("Piece Only Priced"));
        productService.setPrice(pieceOnly.uid(),
                new SetProductPriceRequest(retail.uid(), new MoneyDto("250.00", "TZS")));

        ProductDto boxedButUnpriced = productService.create(goodsRequest("Boxed Unpriced"));
        productService.addBulkPack(boxedButUnpriced.uid(),
                new CreateBulkPackRequest(box.uid(), new BigDecimal("6")));

        List<ResolvedUnitPriceDto> rows = unitPriceLookupService.resolve(
                new ResolveUnitPricesRequest(
                        List.of(boxed.uid(), pieceOnly.uid(), boxedButUnpriced.uid()), box.uid()));

        assertThat(rows).hasSize(3);
        assertThat(rows.get(0).status()).isEqualTo(UnitPriceStatus.RESOLVED);
        assertThat(rows.get(0).amount()).isEqualByComparingTo("1200.00"); // 100 x 12
        assertThat(rows.get(1).status()).isEqualTo(UnitPriceStatus.UNIT_NOT_APPLICABLE);
        assertThat(rows.get(2).status()).isEqualTo(UnitPriceStatus.NO_PRICE);
    }

    /**
     * A batch of ONLY unpriceable products still commits. Pre-fix this was the purest form of the
     * bug: nothing was written, the wrapper caught everything, and the read-only transaction still
     * refused to commit.
     */
    @Test
    void batchWhereNothingCanBePriced_stillReturnsRowsInsteadOfFailingTheCommit() {
        ProductDto one = productService.create(goodsRequest("Unpriced One"));
        ProductDto two = productService.create(goodsRequest("Unpriced Two"));

        List<ResolvedUnitPriceDto> rows = unitPriceLookupService.resolve(
                new ResolveUnitPricesRequest(List.of(one.uid(), two.uid()), null));

        assertThat(rows).hasSize(2);
        assertThat(rows).allSatisfy(row ->
                assertThat(row.status()).isEqualTo(UnitPriceStatus.NO_PRICE));
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private CreateProductRequest goodsRequest(String name) {
        return new CreateProductRequest(
                company.getUid(), null, name, null, ProductType.GOODS, true, true, pcsUid,
                null, null, null, null, null, null, null, null, null, null, null);
    }
}
