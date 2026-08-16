package com.erp.modules.products.service;

import static org.assertj.core.api.Assertions.assertThat;

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
import com.erp.platform.security.RequestContext;
import com.erp.support.IamTestData;
import com.erp.support.PostgresIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Integration tests for {@link ProductCodeGenerator} — per-company PROD-#### sequence isolation
 * (ADR-0007 D-6, brief §5 numbering checklist).
 *
 * <p>Updated for UoM cutover: goodsRequest now takes a baseUnitUid resolved per company.
 */
class ProductCodeGeneratorIT extends PostgresIntegrationTest {

    @Autowired private ProductService productService;
    @Autowired private UnitOfMeasureService unitService;
    @Autowired private OrganisationRepository organisations;
    @Autowired private CompanyRepository companies;
    @Autowired private BranchRepository branches;
    @Autowired private AppUserRepository users;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private IamTestData testData;

    private Long rootId;
    private Organisation org;
    private Company companyA;
    private Branch branchA;
    private String pcsUidA;

    @BeforeEach
    void setUp() {
        testData.clearAll();
        org = organisations.save(new Organisation("Code Gen Org"));
        companyA = companies.save(new Company(org, "CGCA", "Code Gen Co A"));
        branchA = branches.save(new Branch(companyA, "CG-A1", "CG Branch A1"));

        com.erp.modules.iam.domain.entity.AppUser root =
                new com.erp.modules.iam.domain.entity.AppUser(
                        "cg_root", passwordEncoder.encode("RootPass1!"), "CG Root");
        root.setRoot(true);
        root.setOrganisationId(org.getId());
        root = users.save(root);
        rootId = root.getId();

        RequestContext.set(new RequestContext.Principal(
                rootId, "cg_root", true, companyA.getId(), branchA.getId(), null, org.getId()));

        // Seed a unit for companyA (UoM cutover)
        UnitOfMeasureDto pcs = unitService.create(
                new CreateUnitOfMeasureRequest(companyA.getUid(), "PCS", "Pieces"));
        pcsUidA = pcs.uid();
    }

    @AfterEach
    void tearDown() {
        RequestContext.clear();
    }

    @Test
    void sameCompany_threeProducts_codesAreSequential() {
        ProductDto p1 = productService.create(goods(companyA.getUid(), "P1", pcsUidA));
        ProductDto p2 = productService.create(goods(companyA.getUid(), "P2", pcsUidA));
        ProductDto p3 = productService.create(goods(companyA.getUid(), "P3", pcsUidA));

        assertThat(p1.code()).isEqualTo("PROD-0001");
        assertThat(p2.code()).isEqualTo("PROD-0002");
        assertThat(p3.code()).isEqualTo("PROD-0003");
    }

    @Test
    void differentCompanies_eachGetProd0001() {
        Company companyB = companies.save(new Company(org, "CGCB", "Code Gen Co B"));
        Branch branchB = branches.save(new Branch(companyB, "CG-B1", "CG Branch B1"));

        ProductDto prodA = productService.create(goods(companyA.getUid(), "A Item", pcsUidA));

        // Switch to companyB, seed its own unit. companyB is created in the SAME organisation as
        // companyA above, so the principal's tenant is unchanged — only the company scope moves.
        RequestContext.set(new RequestContext.Principal(
                rootId, "cg_root", true, companyB.getId(), branchB.getId(), null, org.getId()));
        UnitOfMeasureDto pcsB = unitService.create(
                new CreateUnitOfMeasureRequest(companyB.getUid(), "PCS", "Pieces"));
        ProductDto prodB = productService.create(goods(companyB.getUid(), "B Item", pcsB.uid()));

        assertThat(prodA.code()).isEqualTo("PROD-0001");
        assertThat(prodB.code()).isEqualTo("PROD-0001");
        assertThat(prodA.companyId()).isNotEqualTo(prodB.companyId());
    }

    private static CreateProductRequest goods(String companyUid, String name, String baseUnitUid) {
        return new CreateProductRequest(
                companyUid, null, name, null, ProductType.GOODS, true, true, baseUnitUid, null, null, null, null, null, null, null, null, null, null, null);
    }
}
