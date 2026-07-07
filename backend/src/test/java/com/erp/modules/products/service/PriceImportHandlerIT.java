package com.erp.modules.products.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.erp.modules.iam.domain.entity.AppUser;
import com.erp.modules.iam.domain.entity.Branch;
import com.erp.modules.iam.domain.entity.Company;
import com.erp.modules.iam.domain.entity.Organisation;
import com.erp.modules.iam.repository.AppUserRepository;
import com.erp.modules.iam.repository.BranchRepository;
import com.erp.modules.iam.repository.CompanyRepository;
import com.erp.modules.iam.repository.OrganisationRepository;
import com.erp.modules.products.domain.dto.CreatePriceListRequest;
import com.erp.modules.products.domain.dto.CreateProductRequest;
import com.erp.modules.products.domain.dto.CreateUnitOfMeasureRequest;
import com.erp.modules.products.domain.dto.PriceListDto;
import com.erp.modules.products.domain.dto.ProductDto;
import com.erp.modules.products.domain.dto.SetProductPriceRequest;
import com.erp.modules.products.domain.dto.UnitOfMeasureDto;
import com.erp.modules.products.domain.enums.ProductType;
import com.erp.platform.bulk.ImportContext;
import com.erp.platform.bulk.ImportMode;
import com.erp.platform.bulk.ImportRow;
import com.erp.platform.bulk.RowAction;
import com.erp.platform.bulk.RowOutcome;
import com.erp.platform.common.money.MoneyDto;
import com.erp.platform.security.RequestContext;
import com.erp.support.IamTestData;
import com.erp.support.PostgresIntegrationTest;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Integration tests for the combined cost + selling-price bulk import ({@link PriceImportHandler})
 * against real Postgres. Covers: commit updates both cost and selling price; an unchanged cost is a
 * no-op (only the price moves); both-blank is skipped; changing cost without product permission is
 * rejected (per-field gate); and the export pre-fills cost + selling price for the round-trip.
 */
class PriceImportHandlerIT extends PostgresIntegrationTest {

    @Autowired private PriceImportHandler handler;
    @Autowired private ProductService productService;
    @Autowired private PriceListService priceListService;
    @Autowired private UnitOfMeasureService unitService;
    @Autowired private OrganisationRepository organisations;
    @Autowired private CompanyRepository companies;
    @Autowired private BranchRepository branches;
    @Autowired private AppUserRepository users;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private IamTestData testData;

    private Company companyA;
    private Branch branchA;
    private Long rootId;
    private String productCode;
    private String productUid;

    @BeforeEach
    void setUp() {
        testData.clearAll();
        Organisation org = organisations.save(new Organisation("Price Import Org"));
        companyA = companies.save(new Company(org, "PICA", "Price Import Co"));
        branchA = branches.save(new Branch(companyA, "PI-A1", "PI Branch"));

        AppUser root = new AppUser("pi_root", passwordEncoder.encode("RootPass1!"), "PI Root");
        root.setRoot(true);
        root = users.save(root);
        rootId = root.getId();
        asRoot();

        UnitOfMeasureDto pcs = unitService.create(
                new CreateUnitOfMeasureRequest(companyA.getUid(), "PCS", "Pieces"));

        ProductDto product = productService.create(new CreateProductRequest(
                companyA.getUid(), null, "Widget", null, ProductType.GOODS, true, true, pcs.uid(),
                new MoneyDto("1000.00", "TZS"), null, null, null, null, null, null, null, null, null, null));
        productCode = product.code();  // PROD-0001
        productUid = product.uid();

        PriceListDto retail = priceListService.create(
                new CreatePriceListRequest(companyA.getUid(), "RETAIL", "Retail"));
        productService.setPrice(productUid, new SetProductPriceRequest(
                retail.uid(), new MoneyDto("2000.00", "TZS")));
    }

    @AfterEach
    void tearDown() {
        RequestContext.clear();
    }

    // -----------------------------------------------------------------------

    @Test
    void commit_updatesBothCostAndSellingPrice() {
        RowOutcome o = handler.process(companyA.getId(), row(
                "Product Code", productCode, "Cost Amount", "1500", "Cost Currency", "TZS",
                "Price List", "RETAIL", "Selling Price", "3000", "Selling Currency", "TZS"),
                ImportMode.COMMIT, new ImportContext());

        assertThat(o.action()).isEqualTo(RowAction.UPDATE);
        assertThat(new BigDecimal(productService.getByUid(productUid).cost().amount()))
                .isEqualByComparingTo("1500");
        assertThat(retailPrice()).isEqualByComparingTo("3000");
    }

    @Test
    void commit_unchangedCost_updatesOnlyThePrice() {
        RowOutcome o = handler.process(companyA.getId(), row(
                "Product Code", productCode, "Cost Amount", "1000", "Cost Currency", "TZS",
                "Price List", "RETAIL", "Selling Price", "2500", "Selling Currency", "TZS"),
                ImportMode.COMMIT, new ImportContext());

        assertThat(o.action()).isEqualTo(RowAction.UPDATE);
        assertThat(o.reference()).contains("price").doesNotContain("cost");
        assertThat(new BigDecimal(productService.getByUid(productUid).cost().amount()))
                .isEqualByComparingTo("1000");
        assertThat(retailPrice()).isEqualByComparingTo("2500");
    }

    @Test
    void commit_bothBlank_isSkipped() {
        RowOutcome o = handler.process(companyA.getId(),
                row("Product Code", productCode, "Price List", "RETAIL"),
                ImportMode.COMMIT, new ImportContext());

        assertThat(o.action()).isEqualTo(RowAction.SKIP);
    }

    @Test
    void commit_changingCostWithoutProductPermission_isRejected() {
        AppUser priceUser = users.save(
                new AppUser("pi_priceonly", passwordEncoder.encode("Pass1!"), "Price Only"));
        RequestContext.set(new RequestContext.Principal(
                priceUser.getId(), "pi_priceonly", false, companyA.getId(), branchA.getId(), null));

        assertThatThrownBy(() -> handler.process(companyA.getId(), row(
                "Product Code", productCode, "Cost Amount", "1500", "Cost Currency", "TZS"),
                ImportMode.COMMIT, new ImportContext()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("permission");

        asRoot();
        assertThat(new BigDecimal(productService.getByUid(productUid).cost().amount()))
                .isEqualByComparingTo("1000");  // unchanged
    }

    @Test
    void export_prefillsCurrentCostAndSellingPrice() {
        List<LinkedHashMap<String, String>> rows =
                handler.exportRows(companyA.getId(), Map.of("priceList", "RETAIL"));

        LinkedHashMap<String, String> widget = rows.stream()
                .filter(r -> productCode.equals(r.get("Product Code")))
                .findFirst().orElseThrow();
        assertThat(widget.get("Product Name")).isEqualTo("Widget");
        assertThat(new BigDecimal(widget.get("Cost Amount"))).isEqualByComparingTo("1000");
        assertThat(widget.get("Cost Currency")).isEqualTo("TZS");
        assertThat(new BigDecimal(widget.get("Selling Price"))).isEqualByComparingTo("2000");
        assertThat(widget.get("Selling Currency")).isEqualTo("TZS");
    }

    @Test
    void export_thenReuploadWithEditedPrice_updates() {
        LinkedHashMap<String, String> exported =
                handler.exportRows(companyA.getId(), Map.of("priceList", "RETAIL")).stream()
                        .filter(r -> productCode.equals(r.get("Product Code")))
                        .findFirst().orElseThrow();
        exported.put("Selling Price", "2800");  // edit only the price, upload the row back

        RowOutcome o = handler.process(companyA.getId(),
                new ImportRow(2, exported), ImportMode.COMMIT, new ImportContext());

        assertThat(o.action()).isEqualTo(RowAction.UPDATE);
        assertThat(retailPrice()).isEqualByComparingTo("2800");
    }

    // -----------------------------------------------------------------------

    private void asRoot() {
        RequestContext.set(new RequestContext.Principal(
                rootId, "pi_root", true, companyA.getId(), branchA.getId(), null));
    }

    private BigDecimal retailPrice() {
        return productService.listPrices(productUid).stream()
                .findFirst()
                .map(p -> new BigDecimal(p.price().amount()))
                .orElseThrow();
    }

    /** Build an ImportRow from header/value pairs (row number is irrelevant to the assertions). */
    private static ImportRow row(String... kv) {
        Map<String, String> cells = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            cells.put(kv[i], kv[i + 1]);
        }
        return new ImportRow(2, cells);
    }
}
