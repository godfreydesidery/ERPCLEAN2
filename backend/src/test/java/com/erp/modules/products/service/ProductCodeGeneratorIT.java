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
import com.erp.modules.products.domain.dto.ProductDto;
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
 * <p>Verifies:
 * <ul>
 *   <li>Different companies each get {@code PROD-0001} independently.</li>
 *   <li>Same company increments: PROD-0001, PROD-0002, …</li>
 *   <li>code_sequence keyed by (company_id, 'PRODUCT').</li>
 * </ul>
 */
class ProductCodeGeneratorIT extends PostgresIntegrationTest {

    @Autowired private ProductService productService;
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
        root = users.save(root);
        rootId = root.getId();

        RequestContext.set(new RequestContext.Principal(
                rootId, "cg_root", true, companyA.getId(), branchA.getId(), null));
    }

    @AfterEach
    void tearDown() {
        RequestContext.clear();
    }

    @Test
    void sameCompany_threeProducts_codesAreSequential() {
        ProductDto p1 = productService.create(goods(companyA.getUid(), "P1"));
        ProductDto p2 = productService.create(goods(companyA.getUid(), "P2"));
        ProductDto p3 = productService.create(goods(companyA.getUid(), "P3"));

        assertThat(p1.code()).isEqualTo("PROD-0001");
        assertThat(p2.code()).isEqualTo("PROD-0002");
        assertThat(p3.code()).isEqualTo("PROD-0003");
    }

    @Test
    void differentCompanies_eachGetProd0001() {
        Company companyB = companies.save(new Company(org, "CGCB", "Code Gen Co B"));
        Branch branchB = branches.save(new Branch(companyB, "CG-B1", "CG Branch B1"));

        ProductDto prodA = productService.create(goods(companyA.getUid(), "A Item"));

        RequestContext.set(new RequestContext.Principal(
                rootId, "cg_root", true, companyB.getId(), branchB.getId(), null));
        ProductDto prodB = productService.create(goods(companyB.getUid(), "B Item"));

        assertThat(prodA.code()).isEqualTo("PROD-0001");
        assertThat(prodB.code()).isEqualTo("PROD-0001");
        assertThat(prodA.companyId()).isNotEqualTo(prodB.companyId());
    }

    private static CreateProductRequest goods(String companyUid, String name) {
        return new CreateProductRequest(
                companyUid, name, null, ProductType.GOODS, true, true, "piece", null);
    }
}
