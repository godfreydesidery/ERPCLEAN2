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
import com.erp.modules.products.domain.dto.CreateProductRequest;
import com.erp.modules.products.domain.dto.CreateUnitOfMeasureRequest;
import com.erp.modules.products.domain.dto.ProductDto;
import com.erp.modules.products.domain.dto.UnitOfMeasureDto;
import com.erp.modules.products.domain.enums.ProductType;
import com.erp.modules.products.repository.ProductBranchRepository;
import com.erp.modules.products.repository.ProductRepository;
import com.erp.platform.bulk.ImportContext;
import com.erp.platform.bulk.ImportMode;
import com.erp.platform.bulk.ImportRow;
import com.erp.platform.bulk.RowAction;
import com.erp.platform.bulk.RowOutcome;
import com.erp.platform.common.api.ForbiddenException;
import com.erp.platform.security.RequestContext;
import com.erp.support.IamTestData;
import com.erp.support.PostgresIntegrationTest;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * "Sold only at this branch" when an item is created (ADR-0073): through the API field the till and
 * OrbixHQ will send, and through the product import's "Sold At" column. Absent = sold everywhere,
 * exactly as before — including at branches opened later.
 */
class ProductSoldOnlyHereIT extends PostgresIntegrationTest {

    @Autowired private ProductService productService;
    @Autowired private ProductImportHandler importHandler;
    @Autowired private UnitOfMeasureService unitService;
    @Autowired private BranchMenuService branchMenu;
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
    private Branch arusha;
    private Branch dar;
    private Long rootId;
    private UnitOfMeasureDto cup;

    @BeforeEach
    void setUp() {
        testData.clearAll();
        org = organisations.save(new Organisation("Sold Here Org"));
        company = companies.save(new Company(org, "SLDH", "Sold Here Co"));
        companyId = company.getId();
        arusha = branches.save(new Branch(company, "SH-ARU", "Arusha"));
        dar = branches.save(new Branch(company, "SH-DAR", "Dar"));

        AppUser root = new AppUser("soldhere_root", passwordEncoder.encode("RootPass1!"), "Root");
        root.setRoot(true);
        root.setOrganisationId(org.getId());
        rootId = users.save(root).getId();
        RequestContext.set(new RequestContext.Principal(
                rootId, "soldhere_root", true, companyId, arusha.getId(), null, org.getId()));
        cup = unitService.create(new CreateUnitOfMeasureRequest(company.getUid(), "CUP", "Cup"));
    }

    @AfterEach
    void tearDown() {
        RequestContext.clear();
    }

    // ---- API field --------------------------------------------------------------------

    @Test
    void withoutTheField_theItemIsSoldEverywhere_includingABranchOpenedLater() {
        ProductDto p = productService.create(request("Latte", null));

        assertThat(productBranches.findByProductId(p.id())).isEmpty();
        Branch later = branches.save(new Branch(company, "SH-KIA", "KIA"));
        assertThat(branchMenu.isSoldAt(p.id(), later.getId())).isTrue();
    }

    @Test
    void soldOnlyHere_isSoldAtThatBranch_andNowhereElse() {
        ProductDto p = productService.create(request("Arusha Special", arusha.getUid()));

        assertThat(branchMenu.isSoldAt(p.id(), arusha.getId())).isTrue();
        assertThat(branchMenu.isSoldAt(p.id(), dar.getId())).isFalse();
        Branch later = branches.save(new Branch(company, "SH-KIA", "KIA"));
        assertThat(branchMenu.isSoldAt(p.id(), later.getId())).isFalse();
    }

    @Test
    void anotherCompanysBranch_isRefused_andNoItemIsCreated() {
        Company other = companies.save(new Company(org, "SLD2", "Other Co"));
        Branch theirs = branches.save(new Branch(other, "SH2-A", "Theirs"));
        long before = productRepo.count();

        assertThatThrownBy(() -> productService.create(request("Leaky", theirs.getUid())))
                .isInstanceOf(ForbiddenException.class);
        assertThat(productRepo.count()).isEqualTo(before);
    }

    // ---- product import "Sold At" --------------------------------------------------------

    @Test
    void importWithoutSoldAt_createsAnItemSoldEverywhere() {
        RowOutcome o = importRow(row("Name", "Water"));

        assertThat(o.action()).isEqualTo(RowAction.CREATE);
        var p = productRepo.findByCompanyIdAndCode(companyId, o.reference()).orElseThrow();
        assertThat(productBranches.findByProductId(p.getId())).isEmpty();
    }

    @Test
    void importWithSoldAt_sellsOnlyAtTheListedBranches() {
        Branch kia = branches.save(new Branch(company, "SH-KIA", "KIA"));

        RowOutcome o = importRow(row("Name", "Mandazi", "Sold At", "sh-dar ; SH-KIA"));

        var p = productRepo.findByCompanyIdAndCode(companyId, o.reference()).orElseThrow();
        assertThat(branchMenu.isSoldAt(p.getId(), dar.getId())).isTrue();
        assertThat(branchMenu.isSoldAt(p.getId(), kia.getId())).isTrue();
        assertThat(branchMenu.isSoldAt(p.getId(), arusha.getId())).isFalse();
    }

    @Test
    void anUnknownBranchCode_rejectsTheRow_andCreatesNothing() {
        long before = productRepo.count();

        assertThatThrownBy(() -> importRow(row("Name", "Typo Item", "Sold At", "SH-DAR, NOPE")))
                .hasMessageContaining("'NOPE' is not an active branch code");
        assertThat(productRepo.count()).isEqualTo(before);
    }

    @Test
    void soldAtOnAnExistingItem_isNotApplied_andTheRowSaysWhy() {
        ProductDto existing = productService.create(request("Cake", null));

        RowOutcome o = importRow(row("Code", existing.code(), "Name", "Cake", "Sold At", "SH-DAR"));

        assertThat(o.action()).isEqualTo(RowAction.UPDATE);
        assertThat(o.message()).contains("new items only");
        assertThat(productBranches.findByProductId(existing.id())).isEmpty();
    }

    // ---- helpers ------------------------------------------------------------------

    private CreateProductRequest request(String name, String soldOnlyAtBranchUid) {
        return new CreateProductRequest(
                company.getUid(), null, name, null, ProductType.GOODS, true, false, cup.uid(),
                null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, soldOnlyAtBranchUid);
    }

    private RowOutcome importRow(ImportRow row) {
        return importHandler.process(companyId, row, ImportMode.COMMIT, new ImportContext());
    }

    /** A product row with the required columns filled, plus {@code kv}. */
    private static ImportRow row(String... kv) {
        Map<String, String> cells = new LinkedHashMap<>();
        cells.put("Type", "GOODS");
        cells.put("Sellable", "Yes");
        cells.put("Stockable", "No");
        cells.put("Base Unit", "CUP");
        for (int i = 0; i < kv.length; i += 2) {
            cells.put(kv[i], kv[i + 1]);
        }
        return new ImportRow(2, cells);
    }
}
