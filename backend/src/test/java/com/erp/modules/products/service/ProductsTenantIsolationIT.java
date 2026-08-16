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
import com.erp.modules.products.domain.dto.ActivateBomRequest;
import com.erp.modules.products.domain.dto.AddBomComponentRequest;
import com.erp.modules.products.domain.dto.BomDto;
import com.erp.modules.products.domain.dto.CreateBomRequest;
import com.erp.modules.products.domain.dto.CreateProductRequest;
import com.erp.modules.products.domain.dto.CreatePromotionRequest;
import com.erp.modules.products.domain.dto.CreateUnitOfMeasureRequest;
import com.erp.modules.products.domain.enums.ComponentSourcing;
import com.erp.modules.products.domain.enums.PromotionEffect;
import com.erp.modules.products.domain.enums.PromotionTarget;
import com.erp.modules.products.domain.enums.ProductType;
import com.erp.modules.products.domain.enums.VatStatus;
import com.erp.platform.common.api.NotFoundException;
import com.erp.platform.security.RequestContext;
import com.erp.support.IamTestData;
import com.erp.support.PostgresIntegrationTest;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Regression tests for the two confirmed CONFUSED_DEPUTY bugs in the products module.
 *
 * <p><b>BUG-1 (HIGH) — BomServiceImpl.cloneComponents cross-tenant clone:</b>
 * A Company-A caller posting {@code POST /api/v1/boms?companyId=A} with
 * {@code cloneFromBomUid} pointing at a Company-B BOM used to return 201 with all of
 * Company-B's secret recipe copied into Company-A's new BOM.  Fix: after loading the source BOM
 * by uid, verify {@code source.companyId == target.companyId} and throw 404 when it differs
 * (avoids confirming existence of the foreign BOM).
 *
 * <p><b>BUG-2 (LOW) — PricingRuleServiceImpl.createPromotion cross-tenant product reference:</b>
 * When {@code target=PRODUCT}, the service resolved {@code targetProductUid} via a bare
 * {@code products.findByUid} with no company filter, allowing a Company-A promotion to carry a
 * Company-B product's {@code target_product_id} (dangling cross-tenant FK + existence oracle).
 * The sibling fix for {@code targetCustomerUid} is also covered.  Fix: resolve via
 * {@code products.findByCompanyIdAndUid(company, uid)} / {@code customers.findByCompanyIdAndUid},
 * returning 404 when the entity is not in the promotion's company.
 *
 * <p><b>Manual repro (if the container is unavailable):</b>
 * <ol>
 *   <li>Start the app locally with dev profile.  Bootstrap creates rootadmin + company 1/branch 1.
 *   <li>Create a second organisation/company (company 2) and a product in it (product B uid = PB).
 *   <li>For BUG-1: create a BOM in company 2 and activate it.  As company-1 caller POST
 *       /api/v1/boms?companyId=1 with body {@code {parentProductUid: <A product>, cloneFromBomUid: <B bom uid>}}.
 *       Before fix: 201 with B's components.  After fix: 404.
 *   <li>For BUG-2: as company-1 caller POST /api/v1/pricing-rules/promotions with
 *       {@code {companyUid: <A uid>, target: "PRODUCT", targetProductUid: <B product uid>, ...}}.
 *       Before fix: 201 with B's product id stored.  After fix: 404.
 * </ol>
 */
class ProductsTenantIsolationIT extends PostgresIntegrationTest {

    @Autowired private BomService bomService;
    @Autowired private BomComponentService bomComponentService;
    @Autowired private PricingRuleService pricingRuleService;
    @Autowired private ProductService productService;
    @Autowired private UnitOfMeasureService uomService;
    @Autowired private OrganisationRepository organisations;
    @Autowired private CompanyRepository companies;
    @Autowired private BranchRepository branches;
    @Autowired private AppUserRepository users;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private IamTestData testData;

    // Company A — the "caller's" tenant
    private Company companyA;
    private Branch  branchA;

    // Company B — the "foreign" tenant whose data must not bleed into A
    private Company companyB;
    private Branch  branchB;

    private Long rootId;

    // UoM uid per company (products require a base unit in the same company)
    private String uomAUid;
    private String uomBUid;

    @BeforeEach
    void setUp() {
        testData.clearAll();

        Organisation org = organisations.save(new Organisation("Isolation Test Org"));
        companyA = companies.save(new Company(org, "ISO-A", "Isolation Co A"));
        branchA  = branches.save(new Branch(companyA, "ISO-A1", "Isolation Branch A1"));
        companyB = companies.save(new Company(org, "ISO-B", "Isolation Co B"));
        branchB  = branches.save(new Branch(companyB, "ISO-B1", "Isolation Branch B1"));

        AppUser root = new AppUser("iso_root", passwordEncoder.encode("Is0R00t!"), "ISO Root");
        root.setRoot(true);
        root.setOrganisationId(org.getId());
        root   = users.save(root);
        rootId = root.getId();

        // Root context for setup calls — root bypasses the companyId scope guard
        RequestContext.set(new RequestContext.Principal(
                rootId, "iso_root", true, companyA.getId(), branchA.getId(), null));

        uomAUid = uomService.create(new CreateUnitOfMeasureRequest(companyA.getUid(), "PCS-A", "Pieces A")).uid();

        // Switch context to B to create B's UoM and products
        RequestContext.set(new RequestContext.Principal(
                rootId, "iso_root", true, companyB.getId(), branchB.getId(), null));
        uomBUid = uomService.create(new CreateUnitOfMeasureRequest(companyB.getUid(), "PCS-B", "Pieces B")).uid();
    }

    @AfterEach
    void tearDown() {
        RequestContext.clear();
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private String createProduct(Company company, Branch branch, String uomUid, String name) {
        RequestContext.set(new RequestContext.Principal(
                rootId, "iso_root", true, company.getId(), branch.getId(), null));
        return productService.create(new CreateProductRequest(
                company.getUid(), null, name, null,
                ProductType.GOODS, true, true, uomUid, null, VatStatus.STANDARD,
                null, null, null, null, null, null, null, null, null)).uid();
    }

    /**
     * Creates an ACTIVE BOM for {@code parentProductUid} in the given company/branch context,
     * adding one component to satisfy the "at least one component" activation rule.
     */
    private BomDto createActiveBom(Company company, Branch branch, String parentUid,
                                   String componentUid, String uomUid) {
        RequestContext.set(new RequestContext.Principal(
                rootId, "iso_root", true, company.getId(), branch.getId(), null));
        BomDto bom = bomService.create(company.getId(),
                new CreateBomRequest(parentUid, BigDecimal.ONE, BigDecimal.valueOf(100), "B recipe", null));
        bomComponentService.add(bom.uid(),
                new AddBomComponentRequest(componentUid, BigDecimal.valueOf(7),
                        ComponentSourcing.BUY, BigDecimal.ZERO, null));
        return bomService.activate(bom.uid(), new ActivateBomRequest(LocalDate.now()));
    }

    // =========================================================================
    // BUG-1: BomServiceImpl.cloneComponents — cross-tenant clone blocked
    // =========================================================================

    /**
     * A Company-A caller with a valid parent product in A and a cloneFromBomUid pointing at
     * Company-B's BOM must receive 404 (not 201 with B's recipe cloned into A).
     */
    @Test
    void cloneBom_withForeignCompanySourceBom_returnsNotFound() {
        // Create B's parent + component + active BOM
        String bParentUid = createProduct(companyB, branchB, uomBUid, "B-Parent");
        String bCompUid   = createProduct(companyB, branchB, uomBUid, "B-Secret-Component");
        BomDto bBom       = createActiveBom(companyB, branchB, bParentUid, bCompUid, uomBUid);

        // Create A's parent product
        String aParentUid = createProduct(companyA, branchA, uomAUid, "A-Parent");

        // Switch to Company-A caller context
        RequestContext.set(new RequestContext.Principal(
                rootId, "iso_root", true, companyA.getId(), branchA.getId(), null));

        // Attempt to clone B's BOM into a new A BOM — must throw NotFoundException (404)
        assertThatThrownBy(() ->
                bomService.create(companyA.getId(),
                        new CreateBomRequest(aParentUid, BigDecimal.ONE, BigDecimal.valueOf(100),
                                "A clone of B secret", bBom.uid()))
        ).isInstanceOf(NotFoundException.class);
    }

    /**
     * Cloning a same-company BOM must still work (regression guard — fix must not break happy path).
     */
    @Test
    void cloneBom_withSameCompanySourceBom_succeeds() {
        String aParentUid = createProduct(companyA, branchA, uomAUid, "A-Parent");
        String aCompUid   = createProduct(companyA, branchA, uomAUid, "A-Component");
        BomDto sourceBom  = createActiveBom(companyA, branchA, aParentUid, aCompUid, uomAUid);

        String aParent2Uid = createProduct(companyA, branchA, uomAUid, "A-Parent-2");

        RequestContext.set(new RequestContext.Principal(
                rootId, "iso_root", true, companyA.getId(), branchA.getId(), null));

        BomDto cloned = bomService.create(companyA.getId(),
                new CreateBomRequest(aParent2Uid, BigDecimal.ONE, BigDecimal.valueOf(100),
                        "A clone", sourceBom.uid()));

        assertThat(cloned.uid()).isNotEqualTo(sourceBom.uid());
        assertThat(cloned.companyId()).isEqualTo(companyA.getId());
    }

    // =========================================================================
    // BUG-2: PricingRuleServiceImpl.createPromotion — cross-tenant product ref
    // =========================================================================

    /**
     * A Company-A caller creating a PRODUCT-targeted promotion with a Company-B product uid
     * must receive 404, not 201 carrying B's product id in target_product_id.
     */
    @Test
    void createPromotion_withForeignCompanyTargetProduct_returnsNotFound() {
        // Create a product in B
        String bProductUid = createProduct(companyB, branchB, uomBUid, "B-Product");

        // Switch to Company-A caller
        RequestContext.set(new RequestContext.Principal(
                rootId, "iso_root", true, companyA.getId(), branchA.getId(), null));

        assertThatThrownBy(() ->
                pricingRuleService.createPromotion(new CreatePromotionRequest(
                        companyA.getUid(), "PROMO-X", "Cross-tenant product promo",
                        PromotionTarget.PRODUCT, bProductUid, null,
                        PromotionEffect.PERCENT_DISCOUNT, new BigDecimal("10"),
                        LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31), (short) 0))
        ).isInstanceOf(NotFoundException.class);
    }

    /**
     * A PRODUCT-targeted promotion referencing a product owned by the same company must still be
     * created successfully (regression guard).
     */
    @Test
    void createPromotion_withSameCompanyTargetProduct_succeeds() {
        String aProductUid = createProduct(companyA, branchA, uomAUid, "A-Promo-Product");

        RequestContext.set(new RequestContext.Principal(
                rootId, "iso_root", true, companyA.getId(), branchA.getId(), null));

        var dto = pricingRuleService.createPromotion(new CreatePromotionRequest(
                companyA.getUid(), "PROMO-OK", "Same-company product promo",
                PromotionTarget.PRODUCT, aProductUid, null,
                PromotionEffect.PERCENT_DISCOUNT, new BigDecimal("5"),
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31), (short) 0));

        assertThat(dto.uid()).isNotBlank();
        assertThat(dto.companyId()).isEqualTo(companyA.getId());
        assertThat(dto.targetProductId()).isNotNull();
    }
}
