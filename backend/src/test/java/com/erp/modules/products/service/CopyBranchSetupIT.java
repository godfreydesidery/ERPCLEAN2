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
import com.erp.modules.products.domain.dto.CopyBranchMenuRequest;
import com.erp.modules.products.domain.dto.CopyBranchMenuResultDto;
import com.erp.modules.products.domain.dto.CreateProductRequest;
import com.erp.modules.products.domain.dto.CreateUnitOfMeasureRequest;
import com.erp.modules.products.domain.dto.ProductBranchDto;
import com.erp.modules.products.domain.dto.ProductDto;
import com.erp.modules.products.domain.dto.SetBranchMenuRequest;
import com.erp.modules.products.domain.dto.UnitOfMeasureDto;
import com.erp.modules.products.domain.entity.ProductBranch;
import com.erp.modules.products.domain.enums.ProductType;
import com.erp.modules.products.repository.ProductBranchRepository;
import com.erp.modules.products.repository.ProductRepository;
import com.erp.modules.sales.domain.dto.CopyCategoryRoutesRequest;
import com.erp.modules.sales.domain.dto.CopyCategoryRoutesResultDto;
import com.erp.modules.sales.domain.dto.CreatePrepStationRequest;
import com.erp.modules.sales.domain.dto.PrepStationCategoryDto;
import com.erp.modules.sales.domain.dto.PrepStationDto;
import com.erp.modules.sales.domain.dto.SetCategoryRouteRequest;
import com.erp.modules.sales.service.RestaurantSetupService;
import com.erp.platform.common.api.ForbiddenException;
import com.erp.platform.security.RequestContext;
import com.erp.support.IamTestData;
import com.erp.support.PostgresIntegrationTest;
import java.math.BigDecimal;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Starting a branch from another (ADR-0073): the menu-and-prices copy fills gaps only and never
 * overwrites, and the routing copy only ever routes onto stations the target already has — it never
 * creates a station, moves a route or touches the default (a one-printer branch is left alone).
 */
class CopyBranchSetupIT extends PostgresIntegrationTest {

    @Autowired private ProductService productService;
    @Autowired private UnitOfMeasureService unitService;
    @Autowired private BranchMenuService branchMenu;
    @Autowired private RestaurantSetupService restaurant;
    @Autowired private ProductBranchRepository productBranches;
    @Autowired private ProductRepository productRepo;
    @Autowired private OrganisationRepository organisations;
    @Autowired private CompanyRepository companies;
    @Autowired private BranchRepository branches;
    @Autowired private AppUserRepository users;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private IamTestData testData;

    private Organisation org;
    private Company company;
    private Long companyId;
    private Branch arusha;   // the source
    private Branch dar;      // another branch
    private Branch kia;      // the new branch
    private Long rootId;
    private UnitOfMeasureDto cup;

    @BeforeEach
    void setUp() {
        testData.clearAll();
        org = organisations.save(new Organisation("Copy Branch Org"));
        company = companies.save(new Company(org, "CPYB", "Copy Branch Co"));
        companyId = company.getId();
        arusha = branches.save(new Branch(company, "CB-ARU", "Arusha"));
        dar = branches.save(new Branch(company, "CB-DAR", "Dar"));
        kia = branches.save(new Branch(company, "CB-KIA", "KIA"));

        AppUser root = new AppUser("copybranch_root", passwordEncoder.encode("RootPass1!"), "Root");
        root.setRoot(true);
        root.setOrganisationId(org.getId());
        rootId = users.save(root).getId();
        at(arusha);
        cup = unitService.create(new CreateUnitOfMeasureRequest(company.getUid(), "CUP", "Cup"));
    }

    @AfterEach
    void tearDown() {
        RequestContext.clear();
    }

    // ---- menu & prices --------------------------------------------------------------

    @Test
    void anItemRestrictedBeforeTheBranchExisted_isSoldThere_withTheSourcesPrice() {
        ProductDto latte = product("Latte");
        restrictTo(latte, arusha, new BigDecimal("6000"));
        restrictTo(latte, dar, new BigDecimal("8000"));
        assertThat(branchMenu.isSoldAt(latte.id(), kia.getId())).isFalse();   // the KIA problem

        CopyBranchMenuResultDto r = productService.copyBranchMenu(
                new CopyBranchMenuRequest(dar.getUid(), kia.getUid()));

        assertThat(r.itemsNowSold()).isEqualTo(1);
        assertThat(r.pricesCopied()).isEqualTo(1);
        assertThat(branchMenu.isSoldAt(latte.id(), kia.getId())).isTrue();
        assertThat(rowAt(latte, kia).map(ProductBranchDto::branchPrice).orElseThrow())
                .isEqualByComparingTo("8000");
    }

    @Test
    void whatTheTargetAlreadyHas_isNeverOverwritten() {
        ProductDto latte = product("Latte");
        restrictTo(latte, dar, new BigDecimal("8000"));
        restrictTo(latte, kia, new BigDecimal("7000"));   // KIA set its own price
        ProductDto beer = product("Beer");
        restrictTo(beer, dar, null);
        productBranches.save(row(beer, kia, false));     // KIA chose NOT to sell beer

        CopyBranchMenuResultDto r = productService.copyBranchMenu(
                new CopyBranchMenuRequest(dar.getUid(), kia.getUid()));

        assertThat(r.itemsNowSold()).isZero();
        assertThat(r.alreadySetAtTarget()).isEqualTo(2);
        assertThat(rowAt(latte, kia).map(ProductBranchDto::branchPrice).orElseThrow())
                .isEqualByComparingTo("7000");
        assertThat(branchMenu.isSoldAt(beer.id(), kia.getId())).isFalse();
    }

    @Test
    void itemsSoldEverywhere_andItemsTheSourceDoesNotSell_areLeftAsTheyAre() {
        ProductDto water = product("Water");                       // no rows: sold everywhere
        ProductDto pizza = product("Pizza");
        restrictTo(pizza, arusha, null);                            // not sold at Dar

        CopyBranchMenuResultDto r = productService.copyBranchMenu(
                new CopyBranchMenuRequest(dar.getUid(), kia.getUid()));

        assertThat(r.itemsNowSold()).isZero();
        assertThat(productBranches.findByProductId(water.id())).isEmpty();
        assertThat(branchMenu.isSoldAt(water.id(), kia.getId())).isTrue();
        assertThat(branchMenu.isSoldAt(pizza.id(), kia.getId())).isFalse();
    }

    @Test
    void copyingTwice_changesNothingTheSecondTime() {
        ProductDto latte = product("Latte");
        restrictTo(latte, dar, new BigDecimal("8000"));

        productService.copyBranchMenu(new CopyBranchMenuRequest(dar.getUid(), kia.getUid()));
        CopyBranchMenuResultDto again = productService.copyBranchMenu(
                new CopyBranchMenuRequest(dar.getUid(), kia.getUid()));

        assertThat(again.itemsNowSold()).isZero();
        assertThat(again.alreadySetAtTarget()).isEqualTo(1);
    }

    @Test
    void theSameBranch_orABranchOfAnotherCompany_isRefused() {
        assertThatThrownBy(() -> productService.copyBranchMenu(
                new CopyBranchMenuRequest(kia.getUid(), kia.getUid())))
                .hasMessageContaining("different branch");

        Company other = companies.save(new Company(org, "CPY2", "Other Co"));
        Branch theirs = branches.save(new Branch(other, "CB2-A", "Theirs"));
        assertThatThrownBy(() -> productService.copyBranchMenu(
                new CopyBranchMenuRequest(theirs.getUid(), kia.getUid())))
                .isInstanceOf(ForbiddenException.class);
    }

    // ---- kitchen routing -----------------------------------------------------------

    @Test
    void routing_goesOnlyToStationsTheTargetAlreadyHas_andNeverCreatesOne() {
        PrepStationDto darKitchen = station(dar, "KITCHEN");
        PrepStationDto darBarista = station(dar, "BARISTA");
        route(dar, "BREAKFAST", darKitchen);
        route(dar, "COFFEE", darBarista);
        PrepStationDto kiaBarista = station(kia, "Barista");   // KIA: one printer, one station

        CopyCategoryRoutesResultDto r = restaurant.copyCategoryRoutes(
                new CopyCategoryRoutesRequest(dar.getUid(), kia.getUid()));

        assertThat(r.routesCopied()).isEqualTo(1);              // COFFEE → Barista (name match)
        assertThat(r.skippedNoMatchingStation()).isEqualTo(1);  // BREAKFAST: KIA has no KITCHEN
        assertThat(r.stationsMissingAtTarget()).containsExactly("KITCHEN");
        assertThat(restaurant.listStations(kia.getUid())).extracting(PrepStationDto::name)
                .containsExactly("Barista");                     // nothing created
        assertThat(restaurant.listStations(kia.getUid())).singleElement()
                .satisfies(s -> assertThat(s.defaultStation()).isTrue());  // default untouched
        assertThat(restaurant.listCategoryRoutes(kia.getUid()))
                .extracting(x -> x.category() + " -> " + x.prepStationUid())
                .containsExactly("COFFEE -> " + kiaBarista.uid());
    }

    @Test
    void routing_neverMovesARouteTheTargetAlreadyHas() {
        PrepStationDto darKitchen = station(dar, "KITCHEN");
        route(dar, "PASTRIES", darKitchen);
        PrepStationDto kiaBarista = station(kia, "BARISTA");
        station(kia, "KITCHEN");
        route(kia, "PASTRIES", kiaBarista);   // KIA serves pastries from the barista

        CopyCategoryRoutesResultDto r = restaurant.copyCategoryRoutes(
                new CopyCategoryRoutesRequest(dar.getUid(), kia.getUid()));

        assertThat(r.routesCopied()).isZero();
        assertThat(r.alreadyRouted()).isEqualTo(1);
        assertThat(restaurant.listCategoryRoutes(kia.getUid())).singleElement()
                .satisfies(x -> assertThat(x.prepStationUid()).isEqualTo(kiaBarista.uid()));
    }

    // ---- helpers ------------------------------------------------------------------

    private void at(Branch branch) {
        RequestContext.set(new RequestContext.Principal(
                rootId, "copybranch_root", true, companyId, branch.getId(), null, org.getId()));
    }

    private ProductDto product(String name) {
        return productService.create(new CreateProductRequest(
                company.getUid(), null, name, null, ProductType.GOODS, true, false, cup.uid(),
                null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null));
    }

    /** Sold at {@code branch} (with an optional price) — the first row makes the item restricted. */
    private void restrictTo(ProductDto item, Branch branch, BigDecimal price) {
        productBranches.save(row(item, branch, true, price));
    }

    private ProductBranch row(ProductDto item, Branch branch, boolean active) {
        return row(item, branch, active, null);
    }

    private ProductBranch row(ProductDto item, Branch branch, boolean active, BigDecimal price) {
        var product = productRepo.findByCompanyIdAndUid(companyId, item.uid()).orElseThrow();
        ProductBranch pb = new ProductBranch(product, branch.getId(), rootId);
        pb.setActive(active);
        pb.setBranchPrice(price);
        return pb;
    }

    private Optional<ProductBranchDto> rowAt(ProductDto item, Branch branch) {
        return productService.listBranches(item.uid()).stream()
                .filter(r -> r.branchId().equals(branch.getId())).findFirst();
    }

    private PrepStationDto station(Branch branch, String name) {
        return restaurant.createStation(new CreatePrepStationRequest(
                branch.getUid(), name, null, null, false));
    }

    private void route(Branch branch, String category, PrepStationDto station) {
        restaurant.setCategoryRoute(new SetCategoryRouteRequest(
                branch.getUid(), category, station.uid()));
    }
}
