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
import com.erp.modules.products.domain.dto.AddBarcodeRequest;
import com.erp.modules.products.domain.dto.AssignProductBranchRequest;
import com.erp.modules.products.domain.dto.CreatePriceListRequest;
import com.erp.modules.products.domain.dto.CreateProductRequest;
import com.erp.modules.products.domain.dto.CreateUnitOfMeasureRequest;
import com.erp.modules.products.domain.dto.PriceListDto;
import com.erp.modules.products.domain.dto.ProductBranchDto;
import com.erp.modules.products.domain.dto.ProductDto;
import com.erp.modules.products.domain.dto.ResolveUnitPricesRequest;
import com.erp.modules.products.domain.dto.ResolvedUnitPriceDto;
import com.erp.modules.products.domain.dto.SellingPriceQuery;
import com.erp.modules.products.domain.dto.SetBranchMenuRequest;
import com.erp.modules.products.domain.dto.SetProductPriceRequest;
import com.erp.modules.products.domain.dto.UnitOfMeasureDto;
import com.erp.modules.products.domain.enums.PriceSource;
import com.erp.modules.products.domain.enums.ProductType;
import com.erp.platform.common.api.NotFoundException;
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
import org.springframework.data.domain.PageRequest;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Branch menu and branch prices against real Postgres (ADR-0073): the JPQL filters behind
 * {@code soldHere}, the "no rows = sold everywhere" rule, the first-row materialisation of the
 * branch-menu endpoint, and the branch price reaching the till's batch price read.
 */
class BranchMenuIT extends PostgresIntegrationTest {

    @Autowired private ProductService productService;
    @Autowired private PriceListService priceListService;
    @Autowired private UnitOfMeasureService unitService;
    @Autowired private PriceResolutionService priceResolution;
    @Autowired private UnitPriceLookupService priceLookup;
    @Autowired private BranchMenuService branchMenu;
    @Autowired private OrganisationRepository organisations;
    @Autowired private CompanyRepository companies;
    @Autowired private BranchRepository branches;
    @Autowired private AppUserRepository users;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private IamTestData testData;

    private Organisation org;
    private Company company;
    private Long companyId;
    private Branch arusha;
    private Branch moshi;
    private Long rootId;
    private UnitOfMeasureDto cup;
    private ProductDto latte;      // sold everywhere (no rows)
    private ProductDto mandazi;    // made Arusha-only
    private PriceListDto retail;

    @BeforeEach
    void setUp() {
        testData.clearAll();
        org = organisations.save(new Organisation("Branch Menu Org"));
        company = companies.save(new Company(org, "BMNU", "Branch Menu Co"));
        companyId = company.getId();
        arusha = branches.save(new Branch(company, "BM-ARU", "Arusha"));
        moshi = branches.save(new Branch(company, "BM-MSH", "Moshi"));

        AppUser root = new AppUser("branchmenu_root", passwordEncoder.encode("RootPass1!"), "Root");
        root.setRoot(true);
        root.setOrganisationId(org.getId());
        rootId = users.save(root).getId();
        at(arusha);

        cup = unitService.create(new CreateUnitOfMeasureRequest(company.getUid(), "CUP", "Cup"));
        latte = product("Cafe Latte", "Coffee");
        mandazi = product("Mandazi", "Bakery");
        productService.addBarcode(mandazi.uid(), new AddBarcodeRequest("6200000000017", true));

        retail = priceListService.create(
                new CreatePriceListRequest(company.getUid(), "RETAIL", "Retail"));
        priceListService.setDefaultByUid(retail.uid());
        productService.setPrice(latte.uid(),
                new SetProductPriceRequest(retail.uid(), new MoneyDto("8000.00", "TZS"), null));
        productService.setPrice(mandazi.uid(),
                new SetProductPriceRequest(retail.uid(), new MoneyDto("500.00", "TZS"), null));
    }

    @AfterEach
    void tearDown() {
        RequestContext.clear();
    }

    @Test
    void itemWithNoRows_isSoldEverywhere() {
        assertThat(branchMenu.isSoldAt(latte.id(), arusha.getId())).isTrue();
        assertThat(branchMenu.isSoldAt(latte.id(), moshi.getId())).isTrue();
        at(moshi);
        assertThat(names(productService.listSoldHere(companyId, null, null, PageRequest.of(0, 50))))
                .contains("Cafe Latte", "Mandazi");
    }

    @Test
    void firstEntry_switchingOneBranchOff_keepsTheItemOnEveryOtherBranch() {
        List<ProductBranchDto> rows = productService.setBranchMenu(mandazi.uid(),
                new SetBranchMenuRequest(moshi.getUid(), false, null, null));

        assertThat(rows).hasSize(2);
        assertThat(branchMenu.isSoldAt(mandazi.id(), arusha.getId())).isTrue();
        assertThat(branchMenu.isSoldAt(mandazi.id(), moshi.getId())).isFalse();
        assertThatThrownBy(() -> branchMenu.assertSoldAt(mandazi.id(), moshi.getId(), "Mandazi"))
                .hasMessageContaining("Mandazi is not sold at this branch");
    }

    @Test
    void soldHere_onABranchWithNoRow_onAnItemThatHasRows_writesTheRow() {
        // Arusha-only item (a row for Arusha, none for Moshi), then switched on at Moshi.
        productService.assignBranch(mandazi.uid(), new AssignProductBranchRequest(arusha.getUid()));
        assertThat(branchMenu.isSoldAt(mandazi.id(), moshi.getId())).isFalse();

        productService.setBranchMenu(mandazi.uid(),
                new SetBranchMenuRequest(moshi.getUid(), true, null, null));

        assertThat(branchMenu.isSoldAt(mandazi.id(), moshi.getId())).isTrue();
    }

    @Test
    void soldHereFilter_coversList_search_category_categories_andBarcode() {
        productService.setBranchMenu(mandazi.uid(),
                new SetBranchMenuRequest(moshi.getUid(), false, null, null));

        at(moshi);
        assertThat(names(productService.listSoldHere(companyId, null, null, PageRequest.of(0, 50))))
                .containsExactly("Cafe Latte");
        assertThat(names(productService.listSoldHere(companyId, "manda", null, PageRequest.of(0, 50))))
                .isEmpty();
        assertThat(names(productService.listSoldHere(companyId, null, "bakery", PageRequest.of(0, 50))))
                .isEmpty();
        assertThat(productService.listCategoriesSoldHere(companyId)).containsExactly("Coffee");
        assertThatThrownBy(() -> productService.lookupBarcode(companyId, "6200000000017", true))
                .isInstanceOf(NotFoundException.class)
                .hasMessageContaining("not sold at this branch");
        // Without soldHere — the back office — nothing is hidden.
        assertThat(names(productService.list(companyId, null, null, PageRequest.of(0, 50))))
                .contains("Cafe Latte", "Mandazi");
        assertThat(productService.lookupBarcode(companyId, "6200000000017", false).productUid())
                .isEqualTo(mandazi.uid());

        at(arusha);
        assertThat(names(productService.listSoldHere(companyId, null, "Bakery", PageRequest.of(0, 50))))
                .containsExactly("Mandazi");
        assertThat(productService.listCategoriesSoldHere(companyId)).contains("Bakery", "Coffee");
        assertThat(productService.lookupBarcode(companyId, "6200000000017", true).productUid())
                .isEqualTo(mandazi.uid());
    }

    @Test
    void branchPrice_pricesTheTillAtThatBranchOnly_andCanBeCleared() {
        productService.setBranchMenu(latte.uid(),
                new SetBranchMenuRequest(arusha.getUid(), null, new BigDecimal("9000"), null));

        at(arusha);
        ResolvedUnitPriceDto there = tillPrice(latte);
        assertThat(there.amount()).isEqualByComparingTo("9000");
        assertThat(there.priceSource()).isEqualTo(PriceSource.BRANCH_PRICE);

        at(moshi);
        assertThat(tillPrice(latte).amount()).isEqualByComparingTo("8000");
        // Pricing an item did not take it off the other branch.
        assertThat(branchMenu.isSoldAt(latte.id(), moshi.getId())).isTrue();

        productService.setBranchMenu(latte.uid(),
                new SetBranchMenuRequest(arusha.getUid(), null, null, true));
        assertThat(priceResolution.findSellingPriceQuote(new SellingPriceQuery(companyId, latte.id(),
                cup.id(), null, null, null, null, null, arusha.getId())).price().amount())
                .isEqualByComparingTo("8000");
    }

    @Test
    void productMasterUpsert_canClearABranchPrice() {
        productService.assignBranch(latte.uid(),
                new AssignProductBranchRequest(arusha.getUid(), true, null, new BigDecimal("9100")));
        productService.assignBranch(latte.uid(),
                new AssignProductBranchRequest(arusha.getUid(), true, null, null, true));

        assertThat(productService.listBranches(latte.uid()))
                .singleElement()
                .satisfies(row -> assertThat(row.branchPrice()).isNull());
    }

    // ---- helpers -------------------------------------------------------------------

    private void at(Branch branch) {
        RequestContext.set(new RequestContext.Principal(
                rootId, "branchmenu_root", true, companyId, branch.getId(), null, org.getId()));
    }

    private ProductDto product(String name, String category) {
        return productService.create(new CreateProductRequest(
                company.getUid(), null, name, null, ProductType.GOODS, true, false, cup.uid(),
                null, null, null, null, null, null, null, null, null, null, null,
                category, null, null, null, null, null, null, null, null));
    }

    private ResolvedUnitPriceDto tillPrice(ProductDto product) {
        return priceLookup.resolve(new ResolveUnitPricesRequest(List.of(product.uid()), null, null,
                null)).get(0);
    }

    private static List<String> names(org.springframework.data.domain.Page<ProductDto> page) {
        return page.getContent().stream().map(ProductDto::name).toList();
    }
}
