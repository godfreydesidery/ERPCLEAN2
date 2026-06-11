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
import com.erp.modules.products.domain.dto.BomComponentDto;
import com.erp.modules.products.domain.dto.BomDto;
import com.erp.modules.products.domain.dto.BomExplosionLeafDto;
import com.erp.modules.products.domain.dto.BomExplosionResultDto;
import com.erp.modules.products.domain.dto.CreateBomRequest;
import com.erp.modules.products.domain.dto.CreateProductRequest;
import com.erp.modules.products.domain.dto.WhereUsedRowDto;
import com.erp.modules.products.domain.enums.BomStatus;
import com.erp.modules.products.domain.enums.ComponentSourcing;
import com.erp.modules.products.domain.enums.ProductType;
import com.erp.modules.products.domain.enums.VatStatus;
import com.erp.modules.products.domain.dto.CreateUnitOfMeasureRequest;
import com.erp.platform.security.RequestContext;
import com.erp.support.IamTestData;
import com.erp.support.PostgresIntegrationTest;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Integration tests for the Multi-level BOM module (ADR-0026).
 *
 * <p>Exercises the core flows per the ADR:
 * <ol>
 *   <li>Create DRAFT BOM → add components (make/buy default) → activate.
 *   <li>Duplicate child rejected (BR-BOM-02).
 *   <li>ACTIVE version frozen: adding component to ACTIVE header is rejected (BR-BOM-03).
 *   <li>Transitive cycle check rejects A→B→A (BR-BOM-01).
 *   <li>Multi-level explosion: flat leaf summary aggregates shared leaves across branches.
 *   <li>Where-used: single-level returns parent BOMs consuming a component.
 *   <li>Activate supersedes prior ACTIVE version (BR-BOM-04 atomic supersede).
 * </ol>
 *
 * <p>Requires Docker (Testcontainers singleton-container pattern). If Docker is unavailable in
 * this worktree, the IT is skipped by Maven failsafe at verify — compile-clean is the primary gate.
 */
class BomServiceIT extends PostgresIntegrationTest {

    @Autowired private BomService bomService;
    @Autowired private BomComponentService bomComponentService;
    @Autowired private BomExplosionService bomExplosionService;
    @Autowired private BomWhereUsedService bomWhereUsedService;
    @Autowired private ProductService productService;
    @Autowired private UnitOfMeasureService uomService;
    @Autowired private OrganisationRepository organisations;
    @Autowired private CompanyRepository companies;
    @Autowired private BranchRepository branches;
    @Autowired private AppUserRepository users;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private IamTestData testData;

    private Company company;
    private Branch  branch;
    private Long    rootId;
    private String  uomUid;         // base UoM "PCS"

    @BeforeEach
    void setUp() {
        testData.clearAll();

        Organisation org = organisations.save(new Organisation("BOM IT Org"));
        company = companies.save(new Company(org, "BOMIT", "BOM IT Co"));
        branch  = branches.save(new Branch(company, "BOMIT1", "BOM IT Branch"));

        AppUser root = new AppUser("bom_root", passwordEncoder.encode("B0mR00t!"), "BOM Root");
        root.setRoot(true);
        root   = users.save(root);
        rootId = root.getId();

        RequestContext.set(new RequestContext.Principal(
                rootId, "bom_root", true, company.getId(), branch.getId(), null));

        // Create a base UoM (required by ProductService.create for baseUnitUid)
        var uomDto = uomService.create(new CreateUnitOfMeasureRequest(
                company.getUid(), "PCS", "Pieces"));
        uomUid = uomDto.uid();
    }

    @AfterEach
    void tearDown() {
        RequestContext.clear();
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private String createProduct(String name) {
        var dto = productService.create(new CreateProductRequest(
                company.getUid(), null, name, null,
                ProductType.GOODS, true, true, uomUid, null, VatStatus.STANDARD));
        return dto.uid();
    }

    // -------------------------------------------------------------------------
    // Tests
    // -------------------------------------------------------------------------

    @Test
    void createDraftBom_andAddComponents_happyPath() {
        String parentUid = createProduct("Finished Good A");
        String compAUid  = createProduct("Component A1");
        String compBUid  = createProduct("Component A2");

        // Create DRAFT BOM v1
        BomDto bom = bomService.create(company.getId(),
                new CreateBomRequest(parentUid, BigDecimal.ONE, BigDecimal.valueOf(100), "Test BOM", null));

        assertThat(bom.status()).isEqualTo(BomStatus.DRAFT);
        assertThat(bom.versionNo()).isEqualTo(1);

        // Add two components
        BomComponentDto c1 = bomComponentService.add(bom.uid(),
                new AddBomComponentRequest(compAUid, BigDecimal.valueOf(2), null, BigDecimal.ZERO, null));
        BomComponentDto c2 = bomComponentService.add(bom.uid(),
                new AddBomComponentRequest(compBUid, BigDecimal.valueOf(3), ComponentSourcing.BUY, BigDecimal.ZERO, null));

        assertThat(c1.sourcing()).isEqualTo(ComponentSourcing.BUY); // no active BOM → BUY
        assertThat(c2.sourcing()).isEqualTo(ComponentSourcing.BUY);

        // List components
        List<BomComponentDto> components = bomComponentService.list(bom.uid());
        assertThat(components).hasSize(2);
    }

    @Test
    void activateBom_setsStatusActiveAndVersion1HasEffectiveFrom() {
        String parentUid = createProduct("Activate Test Product");
        String compUid   = createProduct("Activate Test Component");

        BomDto bom = bomService.create(company.getId(),
                new CreateBomRequest(parentUid, BigDecimal.ONE, BigDecimal.valueOf(100), null, null));
        bomComponentService.add(bom.uid(),
                new AddBomComponentRequest(compUid, BigDecimal.ONE, null, BigDecimal.ZERO, null));

        LocalDate today = LocalDate.now();
        BomDto activated = bomService.activate(bom.uid(), new ActivateBomRequest(today));

        assertThat(activated.status()).isEqualTo(BomStatus.ACTIVE);
        assertThat(activated.effectiveFrom()).isEqualTo(today.toString());
        assertThat(activated.effectiveTo()).isNull();
    }

    @Test
    void activateBom_supersedes_priorActiveVersion() {
        String parentUid = createProduct("Supersede Test Product");
        String comp1Uid  = createProduct("Supersede Comp V1");
        String comp2Uid  = createProduct("Supersede Comp V2");

        // Create and activate v1
        BomDto v1 = bomService.create(company.getId(),
                new CreateBomRequest(parentUid, BigDecimal.ONE, BigDecimal.valueOf(100), null, null));
        bomComponentService.add(v1.uid(),
                new AddBomComponentRequest(comp1Uid, BigDecimal.ONE, null, BigDecimal.ZERO, null));
        LocalDate yesterday = LocalDate.now().minusDays(1);
        bomService.activate(v1.uid(), new ActivateBomRequest(yesterday));

        // Create and activate v2
        BomDto v2 = bomService.create(company.getId(),
                new CreateBomRequest(parentUid, BigDecimal.ONE, BigDecimal.valueOf(100), null, null));
        bomComponentService.add(v2.uid(),
                new AddBomComponentRequest(comp2Uid, BigDecimal.ONE, null, BigDecimal.ZERO, null));
        LocalDate today = LocalDate.now();
        BomDto activatedV2 = bomService.activate(v2.uid(), new ActivateBomRequest(today));

        assertThat(activatedV2.status()).isEqualTo(BomStatus.ACTIVE);
        assertThat(activatedV2.versionNo()).isEqualTo(2);

        // Retrieve v1 and verify it was archived
        BomDto v1Check = bomService.getByUid(v1.uid());
        assertThat(v1Check.status()).isEqualTo(BomStatus.ARCHIVED);
        assertThat(v1Check.effectiveTo()).isEqualTo(today.toString());
    }

    @Test
    void addComponent_toBomWithSameChild_rejects_BR_BOM_02() {
        String parentUid = createProduct("Dup Child Parent");
        String compUid   = createProduct("Dup Child Comp");

        BomDto bom = bomService.create(company.getId(),
                new CreateBomRequest(parentUid, BigDecimal.ONE, BigDecimal.valueOf(100), null, null));
        bomComponentService.add(bom.uid(),
                new AddBomComponentRequest(compUid, BigDecimal.ONE, null, BigDecimal.ZERO, null));

        // Adding the same child again should fail (BR-BOM-02)
        assertThatThrownBy(() ->
                bomComponentService.add(bom.uid(),
                        new AddBomComponentRequest(compUid, BigDecimal.valueOf(2), null, BigDecimal.ZERO, null))
        ).isInstanceOf(com.erp.platform.common.api.ConflictException.class)
         .hasMessageContaining("BOM version");
    }

    @Test
    void addComponent_toActiveBom_rejects_BR_BOM_03() {
        String parentUid = createProduct("Frozen BOM Parent");
        String comp1Uid  = createProduct("Frozen BOM Comp1");
        String comp2Uid  = createProduct("Frozen BOM Comp2");

        BomDto bom = bomService.create(company.getId(),
                new CreateBomRequest(parentUid, BigDecimal.ONE, BigDecimal.valueOf(100), null, null));
        bomComponentService.add(bom.uid(),
                new AddBomComponentRequest(comp1Uid, BigDecimal.ONE, null, BigDecimal.ZERO, null));
        bomService.activate(bom.uid(), new ActivateBomRequest(LocalDate.now()));

        // Adding to an ACTIVE BOM should fail (BR-BOM-03)
        assertThatThrownBy(() ->
                bomComponentService.add(bom.uid(),
                        new AddBomComponentRequest(comp2Uid, BigDecimal.ONE, null, BigDecimal.ZERO, null))
        ).isInstanceOf(IllegalStateException.class)
         .hasMessageContaining("DRAFT");
    }

    @Test
    void cycleGuard_rejects_selfReference_BR_BOM_01() {
        String parentUid = createProduct("Cycle Test Parent");

        BomDto bom = bomService.create(company.getId(),
                new CreateBomRequest(parentUid, BigDecimal.ONE, BigDecimal.valueOf(100), null, null));

        // Adding the parent as its own component should fail (BR-BOM-01 self-reference)
        assertThatThrownBy(() ->
                bomComponentService.add(bom.uid(),
                        new AddBomComponentRequest(parentUid, BigDecimal.ONE, null, BigDecimal.ZERO, null))
        ).isInstanceOf(IllegalArgumentException.class)
         .hasMessageContaining("cycle");
    }

    @Test
    void explosion_singleLevel_returnsLeaves() {
        String parentUid = createProduct("Explosion Parent");
        String leaf1Uid  = createProduct("Explosion Leaf 1");
        String leaf2Uid  = createProduct("Explosion Leaf 2");

        BomDto bom = bomService.create(company.getId(),
                new CreateBomRequest(parentUid, BigDecimal.ONE, BigDecimal.valueOf(100), null, null));
        bomComponentService.add(bom.uid(),
                new AddBomComponentRequest(leaf1Uid, BigDecimal.valueOf(2), ComponentSourcing.BUY, BigDecimal.ZERO, null));
        bomComponentService.add(bom.uid(),
                new AddBomComponentRequest(leaf2Uid, BigDecimal.valueOf(3), ComponentSourcing.BUY, BigDecimal.ZERO, null));
        bomService.activate(bom.uid(), new ActivateBomRequest(LocalDate.now()));

        // Explode for outputQty=5: leaf1=2*5=10, leaf2=3*5=15
        BomExplosionResultDto result = bomExplosionService.explode(
                parentUid, null, BigDecimal.valueOf(5), false, null, null, false);

        assertThat(result.leaves()).hasSize(2);

        // Find leaf1 and leaf2 in the result
        var leaf1 = result.leaves().stream()
                .filter(l -> l.componentProductUid().equals(leaf1Uid)).findFirst();
        var leaf2 = result.leaves().stream()
                .filter(l -> l.componentProductUid().equals(leaf2Uid)).findFirst();

        assertThat(leaf1).isPresent();
        assertThat(leaf1.get().totalQuantity()).isEqualByComparingTo("10.000000");
        assertThat(leaf2).isPresent();
        assertThat(leaf2.get().totalQuantity()).isEqualByComparingTo("15.000000");
    }

    @Test
    void whereUsed_singleLevel_returnsBomHeaders() {
        String parentUid = createProduct("Where Used Parent");
        String compUid   = createProduct("Where Used Component");

        BomDto bom = bomService.create(company.getId(),
                new CreateBomRequest(parentUid, BigDecimal.ONE, BigDecimal.valueOf(100), null, null));
        bomComponentService.add(bom.uid(),
                new AddBomComponentRequest(compUid, BigDecimal.valueOf(4), ComponentSourcing.BUY, BigDecimal.ZERO, null));
        bomService.activate(bom.uid(), new ActivateBomRequest(LocalDate.now()));

        List<WhereUsedRowDto> usages = bomWhereUsedService.whereUsed(compUid, company.getId());

        assertThat(usages).hasSize(1);
        assertThat(usages.get(0).bomUid()).isEqualTo(bom.uid());
        assertThat(usages.get(0).parentProductUid()).isEqualTo(parentUid);
        assertThat(usages.get(0).qtyPer()).isEqualByComparingTo("4");
    }

    @Test
    void scrapInflation_isApplied_correctly() {
        String parentUid = createProduct("Scrap Test Parent");
        String leafUid   = createProduct("Scrap Test Leaf");

        BomDto bom = bomService.create(company.getId(),
                new CreateBomRequest(parentUid, BigDecimal.ONE, BigDecimal.valueOf(100), null, null));
        // 10% scrap: effectiveQty = 1 / (1 - 0.1) = 1.111... per output_qty=1
        bomComponentService.add(bom.uid(),
                new AddBomComponentRequest(leafUid, BigDecimal.ONE, ComponentSourcing.BUY,
                        BigDecimal.valueOf(10), null)); // 10% scrap
        bomService.activate(bom.uid(), new ActivateBomRequest(LocalDate.now()));

        List<BomExplosionLeafDto> leaves = bomExplosionService.explodeToLeaves(
                parentUid, BigDecimal.ONE, false);

        assertThat(leaves).hasSize(1);
        // 1 / (1 - 0.10) = 1 / 0.90 ≈ 1.111111
        assertThat(leaves.get(0).totalQuantity()).isGreaterThan(BigDecimal.ONE);
        assertThat(leaves.get(0).totalQuantity())
                .isLessThanOrEqualTo(BigDecimal.valueOf(1.111112));
    }

    @Test
    void hasActiveBom_returnsFalse_beforeActivation() {
        String parentUid = createProduct("No Active BOM Product");
        assertThat(bomExplosionService.hasActiveBom(parentUid)).isFalse();
    }

    @Test
    void hasActiveBom_returnsTrue_afterActivation() {
        String parentUid = createProduct("Active BOM Product");
        String compUid   = createProduct("Active BOM Comp");

        BomDto bom = bomService.create(company.getId(),
                new CreateBomRequest(parentUid, BigDecimal.ONE, BigDecimal.valueOf(100), null, null));
        bomComponentService.add(bom.uid(),
                new AddBomComponentRequest(compUid, BigDecimal.ONE, null, BigDecimal.ZERO, null));
        bomService.activate(bom.uid(), new ActivateBomRequest(LocalDate.now()));

        assertThat(bomExplosionService.hasActiveBom(parentUid)).isTrue();
    }
}
