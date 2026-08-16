package com.erp.modules.products.service;

import static com.erp.support.TenantFixtures.inOrganisation;
import static org.assertj.core.api.Assertions.assertThat;

import com.erp.modules.iam.domain.entity.AppUser;
import com.erp.modules.iam.domain.entity.Branch;
import com.erp.modules.iam.domain.entity.Company;
import com.erp.modules.iam.domain.entity.Organisation;
import com.erp.modules.iam.repository.AppUserRepository;
import com.erp.modules.iam.repository.BranchRepository;
import com.erp.modules.iam.repository.CompanyRepository;
import com.erp.modules.iam.repository.OrganisationRepository;
import com.erp.modules.products.domain.dto.CreateBulkPackRequest;
import com.erp.modules.products.domain.dto.CreateProductRequest;
import com.erp.modules.products.domain.dto.CreateUnitOfMeasureRequest;
import com.erp.modules.products.domain.dto.ProductBulkPackDto;
import com.erp.modules.products.domain.dto.ProductDto;
import com.erp.modules.products.domain.dto.UnitOfMeasureDto;
import com.erp.modules.products.domain.dto.UpdateProductRequest;
import com.erp.modules.products.domain.enums.ProductType;
import com.erp.platform.audit.AuditLog;
import com.erp.platform.audit.AuditRepository;
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
 * Server-side guardrails for the two product misconfigurations the Kilimanjaro UAT hit — both of
 * which the API used to accept in silence because the only check lived in the Angular
 * product-detail screen (K4). A screen-only guard protects one screen: bulk import, the POS admin
 * and any direct API call went straight past it.
 *
 * <p>Both guardrails are SOFT by design. A pack smaller than one base unit is legitimate for
 * weight/volume splits (a 0.5 kg pack of a kg-based product) and a hard {@code >= 1} rule would
 * contradict ratified ADR-0007; re-basing a product is a legitimate correction. So the write
 * succeeds and carries a warning — in the response DTO and on the append-only audit trail.
 */
class ProductConfigurationWarningsIT extends PostgresIntegrationTest {

    @Autowired private ProductService productService;
    @Autowired private UnitOfMeasureService unitService;
    @Autowired private AuditRepository auditRepository;
    @Autowired private OrganisationRepository organisations;
    @Autowired private CompanyRepository companies;
    @Autowired private BranchRepository branches;
    @Autowired private AppUserRepository users;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private IamTestData testData;

    private Company company;
    private String pcsUid;
    private String kgUid;

    @BeforeEach
    void setUp() {
        testData.clearAll();
        Organisation org = organisations.save(new Organisation("Guardrail Test Org"));
        company = companies.save(new Company(org, "GRDA", "Guardrail Co"));
        Branch branch = branches.save(new Branch(company, "GRD-A1", "Guardrail Branch"));

        AppUser root = new AppUser("guardrail_root", passwordEncoder.encode("RootPass1!"),
                "Guardrail Root");
        root.setRoot(true);
        root = users.save(inOrganisation(root, org.getId()));

        RequestContext.set(new RequestContext.Principal(
                root.getId(), "guardrail_root", true, company.getId(), branch.getId(), null));

        pcsUid = unitService.create(
                new CreateUnitOfMeasureRequest(company.getUid(), "PCS", "Pieces")).uid();
        kgUid = unitService.create(
                new CreateUnitOfMeasureRequest(company.getUid(), "KG", "Kilogram")).uid();
    }

    @AfterEach
    void tearDown() {
        RequestContext.clear();
    }

    // -----------------------------------------------------------------------
    // Sub-1 pack factor — the exact Kilimanjaro misconfiguration
    // -----------------------------------------------------------------------

    @Test
    void addBulkPack_withSubOneFactor_isAcceptedButWarns() {
        ProductDto product = productService.create(goodsRequest("Counted Product", pcsUid));
        UnitOfMeasureDto box = unitService.create(
                new CreateUnitOfMeasureRequest(company.getUid(), "BOX", "Box"));

        // 0.0208333 == 1/48: the operator entered "one piece is 1/48 of a box" into a field that
        // means "one box holds N pieces". Silently divides every figure booked in boxes by 48.
        ProductBulkPackDto pack = productService.addBulkPack(product.uid(),
                new CreateBulkPackRequest(box.uid(), new BigDecimal("0.0208333")));

        // Soft: the pack is still created with the factor as entered.
        assertThat(pack.uid()).isNotBlank();
        assertThat(pack.factorToBase()).isEqualByComparingTo("0.0208333");

        assertThat(pack.warnings()).hasSize(1);
        String warning = pack.warnings().get(0);
        assertThat(warning).contains("Box").contains("Pieces");
        // Points at the inversion the operator almost certainly meant: 1 / 0.0208333 ~= 48.
        assertThat(warning).contains("48");
    }

    @Test
    void addBulkPack_withSubOneFactor_writesTheWarningToTheAuditTrail() {
        ProductDto product = productService.create(goodsRequest("Audited Pack Product", pcsUid));
        UnitOfMeasureDto box = unitService.create(
                new CreateUnitOfMeasureRequest(company.getUid(), "BOX", "Box"));

        productService.addBulkPack(product.uid(),
                new CreateBulkPackRequest(box.uid(), new BigDecimal("0.5")));

        // The API caller may drop the warning on the floor; the append-only trail still records it.
        List<AuditLog> logs = auditRepository.findAll();
        assertThat(logs).anySatisfy(log ->
                assertThat(String.valueOf(log.getDetail()))
                        .contains("BULK_PACK_ADD")
                        .contains("warnings"));
    }

    @Test
    void addBulkPack_withFactorOfOneOrMore_warnsAboutNothing() {
        ProductDto product = productService.create(goodsRequest("Normal Pack Product", pcsUid));
        UnitOfMeasureDto carton = unitService.create(
                new CreateUnitOfMeasureRequest(company.getUid(), "CTN", "Carton"));

        ProductBulkPackDto pack = productService.addBulkPack(product.uid(),
                new CreateBulkPackRequest(carton.uid(), new BigDecimal("24")));

        assertThat(pack.warnings()).isEmpty();
    }

    @Test
    void addBulkPack_fractionalPackOfAWeightProduct_stillSaves_becauseTheRuleIsAdvisoryNotAGate() {
        // The legitimate case ADR-0007 protects: a 0.5 kg pack of a kg-based product. It warns
        // (the server cannot know the operator's intent) but must never be refused.
        ProductDto product = productService.create(goodsRequest("Sugar By Weight", kgUid));
        UnitOfMeasureDto gram = unitService.create(
                new CreateUnitOfMeasureRequest(company.getUid(), "GRAMPACK", "Gram Pack"));

        ProductBulkPackDto pack = productService.addBulkPack(product.uid(),
                new CreateBulkPackRequest(gram.uid(), new BigDecimal("0.5")));

        assertThat(pack.uid()).isNotBlank();
        assertThat(pack.factorToBase()).isEqualByComparingTo("0.5");
        assertThat(pack.warnings()).hasSize(1);
    }

    // -----------------------------------------------------------------------
    // Base-unit change on an existing product
    // -----------------------------------------------------------------------

    @Test
    void updateByUid_changingTheBaseUnit_isAcceptedButWarnsThatNothingIsConverted() {
        ProductDto product = productService.create(goodsRequest("Re-based Product", pcsUid));

        ProductDto updated = productService.updateByUid(product.uid(),
                updateRequest("Re-based Product", kgUid));

        assertThat(updated.baseUnitUid()).isEqualTo(kgUid);
        assertThat(updated.warnings()).hasSize(1);
        assertThat(updated.warnings().get(0))
                .contains("Pieces")
                .contains("Kilogram")
                .contains("NOT converted");
    }

    @Test
    void updateByUid_leavingTheBaseUnitAlone_warnsAboutNothing() {
        ProductDto product = productService.create(goodsRequest("Untouched Product", pcsUid));

        ProductDto updated = productService.updateByUid(product.uid(),
                updateRequest("Untouched Product Renamed", pcsUid));

        assertThat(updated.warnings()).isEmpty();
    }

    @Test
    void updateByUid_changingTheBaseUnit_writesTheWarningToTheAuditTrail() {
        ProductDto product = productService.create(goodsRequest("Audited Rebase", pcsUid));

        productService.updateByUid(product.uid(), updateRequest("Audited Rebase", kgUid));

        List<AuditLog> logs = auditRepository.findAll();
        assertThat(logs).anySatisfy(log ->
                assertThat(String.valueOf(log.getDetail()))
                        .contains("BASE_UNIT_CHANGE")
                        .contains("warnings"));
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private CreateProductRequest goodsRequest(String name, String baseUnitUid) {
        return new CreateProductRequest(
                company.getUid(), null, name, null, ProductType.GOODS, true, true, baseUnitUid,
                null, null, null, null, null, null, null, null, null, null, null);
    }

    private UpdateProductRequest updateRequest(String name, String baseUnitUid) {
        return new UpdateProductRequest(name, null, ProductType.GOODS, true, true, baseUnitUid,
                null, null, null, null, null, null, null, null, null, null, null);
    }
}
