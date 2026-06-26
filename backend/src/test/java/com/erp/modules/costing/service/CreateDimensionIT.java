package com.erp.modules.costing.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.erp.modules.costing.domain.dto.CreateDimensionRequest;
import com.erp.modules.costing.domain.dto.DimensionDto;
import com.erp.modules.costing.domain.enums.DimensionSlot;
import com.erp.modules.costing.repository.DimensionRepository;
import com.erp.modules.iam.domain.entity.AppUser;
import com.erp.modules.iam.domain.entity.Branch;
import com.erp.modules.iam.domain.entity.Company;
import com.erp.modules.iam.domain.entity.Organisation;
import com.erp.modules.iam.repository.AppUserRepository;
import com.erp.modules.iam.repository.BranchRepository;
import com.erp.modules.iam.repository.CompanyRepository;
import com.erp.modules.iam.repository.OrganisationRepository;
import com.erp.platform.common.api.ForbiddenException;
import com.erp.platform.security.RequestContext;
import com.erp.support.IamTestData;
import com.erp.support.PostgresIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Integration tests for {@link DimensionService#createDimension} (ADR-0025 D-1).
 *
 * <p>Covers:
 * <ul>
 *   <li>(a) First custom create → slot = DIMENSION_3, builtIn = false, persisted.</li>
 *   <li>(b) Second custom create → slot = DIMENSION_4.</li>
 *   <li>(c) Third create → 409-mapped IllegalStateException (both spare slots taken).</li>
 *   <li>(d) Acting outside caller's company scope → Forbidden (ScopeGuard).</li>
 * </ul>
 *
 * A normally provisioned company has COST_CENTRE + DEPARTMENT seeded as built-ins,
 * leaving DIMENSION_3 and DIMENSION_4 free for custom use.
 */
class CreateDimensionIT extends PostgresIntegrationTest {

    @Autowired private DimensionService     dimensionService;
    @Autowired private DimensionSeeder      dimensionSeeder;
    @Autowired private DimensionRepository  dimensionRepo;

    @Autowired private OrganisationRepository organisations;
    @Autowired private CompanyRepository      companies;
    @Autowired private BranchRepository       branches;
    @Autowired private AppUserRepository      users;
    @Autowired private PasswordEncoder        passwordEncoder;
    @Autowired private IamTestData            testData;

    private Company company;
    private Branch  branch;
    private Long    actorId;

    @BeforeEach
    void setUp() {
        testData.clearAll();

        Organisation org = organisations.save(new Organisation("CreateDim IT Org"));
        company = companies.save(new Company(org, "CDIT", "CreateDim IT Co"));
        branch  = branches.save(new Branch(company, "CDIT-B1", "CreateDim IT Branch"));

        AppUser actor = new AppUser("cdit_user", passwordEncoder.encode("Pass1!"), "CDit User");
        actor.setRoot(true);
        actor   = users.save(actor);
        actorId = actor.getId();

        RequestContext.set(new RequestContext.Principal(
                actorId, "cdit_user", true, company.getId(), branch.getId(), null));

        // Seed the two built-in dimensions (COST_CENTRE + DEPARTMENT) for this company
        dimensionSeeder.seedBuiltIns(company.getId());
    }

    @AfterEach
    void tearDown() {
        RequestContext.clear();
    }

    // =========================================================================
    // (a) First custom create → DIMENSION_3, builtIn = false, persisted
    // =========================================================================

    @Test
    void createDimension_firstCustom_claimsDimension3() {
        CreateDimensionRequest req = new CreateDimensionRequest(
                company.getId(), "PROJECT", "Project", "Custom project dimension");

        DimensionDto dto = dimensionService.createDimension(req);

        assertThat(dto.uid()).isNotBlank();
        assertThat(dto.slot()).isEqualTo(DimensionSlot.DIMENSION_3);
        assertThat(dto.builtIn()).isFalse();
        assertThat(dto.code()).isEqualTo("PROJECT");
        assertThat(dto.name()).isEqualTo("Project");
        assertThat(dto.companyId()).isEqualTo(company.getId());

        // Persisted and findable via repo
        assertThat(dimensionRepo.findByCompanyIdAndSlot(company.getId(), DimensionSlot.DIMENSION_3))
                .isPresent()
                .get()
                .satisfies(d -> {
                    assertThat(d.getUid()).isEqualTo(dto.uid());
                    assertThat(d.isBuiltIn()).isFalse();
                });
    }

    // =========================================================================
    // (b) Second custom create → DIMENSION_4
    // =========================================================================

    @Test
    void createDimension_secondCustom_claimsDimension4() {
        dimensionService.createDimension(new CreateDimensionRequest(
                company.getId(), "PROJ", "Project", null));

        DimensionDto dto = dimensionService.createDimension(new CreateDimensionRequest(
                company.getId(), "REGION", "Region", null));

        assertThat(dto.slot()).isEqualTo(DimensionSlot.DIMENSION_4);
        assertThat(dto.builtIn()).isFalse();
        assertThat(dimensionRepo.findByCompanyIdAndSlot(company.getId(), DimensionSlot.DIMENSION_4))
                .isPresent();
    }

    // =========================================================================
    // (c) Third create → IllegalStateException (both spare slots taken)
    // =========================================================================

    @Test
    void createDimension_thirdCustom_throwsWhenAllSlotsTaken() {
        dimensionService.createDimension(new CreateDimensionRequest(
                company.getId(), "DIM3", "Dim Three", null));
        dimensionService.createDimension(new CreateDimensionRequest(
                company.getId(), "DIM4", "Dim Four", null));

        assertThatThrownBy(() -> dimensionService.createDimension(new CreateDimensionRequest(
                company.getId(), "DIM5", "Dim Five", null)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("custom dimension slots are in use");
    }

    // =========================================================================
    // (d) Acting outside caller's company scope → ForbiddenException
    // =========================================================================

    @Test
    void createDimension_wrongCompany_throwsForbidden() {
        // Create a second company
        Organisation orgB    = organisations.save(new Organisation("Other Org"));
        Company otherCompany = companies.save(new Company(orgB, "OTHERC", "Other Co"));

        // Switch to a non-root actor whose active company is `company` (not `otherCompany`)
        AppUser nonRoot = new AppUser("cdit_nonroot", passwordEncoder.encode("Pass1!"), "NonRoot");
        nonRoot.setRoot(false);
        nonRoot = users.save(nonRoot);
        RequestContext.set(new RequestContext.Principal(
                nonRoot.getId(), "cdit_nonroot", false, company.getId(), branch.getId(), null));

        // Attempting to create a dimension in a company the caller is not scoped to → Forbidden
        CreateDimensionRequest req = new CreateDimensionRequest(
                otherCompany.getId(), "XDIM", "Cross-company dim", null);
        assertThatThrownBy(() -> dimensionService.createDimension(req))
                .isInstanceOf(ForbiddenException.class);
    }
}
