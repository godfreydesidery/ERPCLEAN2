package com.erp.modules.gl.service;

import static com.erp.support.TenantFixtures.inOrganisation;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.erp.modules.costing.domain.dto.CreateDimensionValueRequest;
import com.erp.modules.costing.domain.dto.DimensionDto;
import com.erp.modules.costing.domain.dto.DimensionTagDto;
import com.erp.modules.costing.domain.dto.DimensionValueDto;
import com.erp.modules.costing.domain.dto.ResolvedDimensionTagDto;
import com.erp.modules.costing.domain.enums.DimensionSlot;
import com.erp.modules.costing.service.DimensionResolver;
import com.erp.modules.costing.service.DimensionSeeder;
import com.erp.modules.costing.service.DimensionService;
import com.erp.modules.gl.domain.dto.JournalEntryDraft;
import com.erp.modules.gl.domain.dto.JournalEntryDraft.LineDraft;
import com.erp.modules.gl.domain.dto.JournalEntryDto;
import com.erp.modules.gl.domain.entity.ChartOfAccount;
import com.erp.modules.gl.domain.enums.JournalSourceType;
import com.erp.modules.gl.repository.ChartOfAccountRepository;
import com.erp.modules.iam.domain.entity.AppUser;
import com.erp.modules.iam.domain.entity.Branch;
import com.erp.modules.iam.domain.entity.Company;
import com.erp.modules.iam.domain.entity.Organisation;
import com.erp.modules.iam.repository.AppUserRepository;
import com.erp.modules.iam.repository.BranchRepository;
import com.erp.modules.iam.repository.CompanyRepository;
import com.erp.modules.iam.repository.OrganisationRepository;
import com.erp.modules.projects.domain.dto.CreateProjectRequest;
import com.erp.modules.projects.service.ProjectService;
import com.erp.platform.common.api.ConflictException;
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
 * Integration tests for ADR-0041 D4 — per-account dimension requiredness at GL posting
 * (the {@code enforceAccountDimensionRequirements} gate inside {@code GLPostingServiceImpl},
 * reached only for {@code sourceType == MANUAL}).
 *
 * <p>For an account that sets {@code require_cost_centre} / {@code require_department} /
 * {@code require_project} = true:
 * <ol>
 *   <li>a MANUAL journal line missing the required dimension is rejected with
 *       {@link ConflictException};</li>
 *   <li>the same MANUAL line that supplies the dimension posts normally;</li>
 *   <li>a system/event-driven source (non-MANUAL) posting to the same account is NOT blocked,
 *       even when the dimension is absent (MANUAL-only by design, ADR-0025 exemption).</li>
 * </ol>
 */
class DimensionRequirednessIT extends PostgresIntegrationTest {

    @Autowired private GLPostingService         postingService;
    @Autowired private DimensionService         dimensionService;
    @Autowired private DimensionResolver        dimensionResolver;
    @Autowired private DimensionSeeder          dimensionSeeder;
    @Autowired private ProjectService           projectService;
    @Autowired private ChartOfAccountService    chartOfAccountService;
    @Autowired private FiscalCalendarService    fiscalCalendarService;
    @Autowired private GlConfigService          glConfigService;
    @Autowired private ChartOfAccountRepository accountRepo;
    @Autowired private OrganisationRepository   organisations;
    @Autowired private CompanyRepository        companies;
    @Autowired private BranchRepository         branches;
    @Autowired private AppUserRepository        users;
    @Autowired private PasswordEncoder          passwordEncoder;
    @Autowired private IamTestData              testData;

    private Company company;
    private Branch  branch;
    private Long    rootId;
    private Long    cashAccountId;   // 1000 — the balancing CR leg (no requirement)
    private Long    cogsAccountId;   // 5100 — the requirement-flagged DR leg

    private static final String TZS = "TZS";

    @BeforeEach
    void setUp() {
        testData.clearAll();

        Organisation org = organisations.save(new Organisation("Dim Req IT Org"));
        company = companies.save(new Company(org, "DIMR", "Dim Req IT Co"));
        branch  = branches.save(new Branch(company, "DIMR1", "Dim Req IT Branch"));

        AppUser root = new AppUser("dimr_root", passwordEncoder.encode("RootPass1!"), "DimR Root");
        root.setRoot(true);
        root   = users.save(inOrganisation(root, org.getId()));
        rootId = root.getId();

        RequestContext.set(new RequestContext.Principal(
                rootId, "dimr_root", true, company.getId(), branch.getId(), null));

        chartOfAccountService.seedDefaults(company.getId());
        fiscalCalendarService.seedCurrentYear(company.getId());
        glConfigService.seedDefaults(company.getId());
        dimensionSeeder.seedBuiltIns(company.getId());

        cashAccountId = accountId("1000");
        cogsAccountId = accountId("5100");
    }

    @AfterEach
    void tearDown() {
        RequestContext.clear();
    }

    // =========================================================================
    // Bar 1: MANUAL line to a require_cost_centre account WITHOUT a cost-centre → rejected
    // =========================================================================

    @Test
    void manualLine_requireCostCentre_missing_rejected() {
        setRequireCostCentre(cogsAccountId, true);

        assertThatThrownBy(() -> postingService.post(manualDraft(
                new LineDraft(cogsAccountId, new BigDecimal("100"), BigDecimal.ZERO, TZS, "DR COGS"),
                new LineDraft(cashAccountId, BigDecimal.ZERO, new BigDecimal("100"), TZS, "CR Cash"))))
                .as("require_cost_centre account must reject a MANUAL line with no cost-centre (ADR-0041 D4)")
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("cost-centre");
    }

    // =========================================================================
    // Bar 2: MANUAL line to the same account WITH a cost-centre value → posts
    // =========================================================================

    @Test
    void manualLine_requireCostCentre_present_posts() {
        setRequireCostCentre(cogsAccountId, true);
        Long ccValueId = newCostCentreValueId("CC-OPS", "Operations");

        JournalEntryDto posted = postingService.post(manualDraft(
                new LineDraft(cogsAccountId, new BigDecimal("100"), BigDecimal.ZERO, TZS, "DR COGS",
                        ccValueId, null, null, null),
                new LineDraft(cashAccountId, BigDecimal.ZERO, new BigDecimal("100"), TZS, "CR Cash")));

        assertThat(posted.uid())
                .as("a MANUAL line that supplies the required cost-centre must post").isNotBlank();
        assertThat(posted.lines()).hasSize(2);
    }

    // =========================================================================
    // Bar 3: system/event-driven source (non-MANUAL) to a require_cost_centre account → NOT blocked
    // =========================================================================

    @Test
    void systemSource_requireCostCentre_missing_notBlocked() {
        setRequireCostCentre(cogsAccountId, true);

        // sourceType != MANUAL (COGS, postedBy=SYSTEM/null) — the requiredness gate is skipped.
        JournalEntryDto posted = postingService.post(new JournalEntryDraft(
                company.getId(), branch.getId(), LocalDate.now(),
                "Automated posting to a require_cost_centre account",
                JournalSourceType.COGS, "REF-SYS-DIM-001", null, null,
                List.of(
                        new LineDraft(cogsAccountId, new BigDecimal("60"), BigDecimal.ZERO, TZS, "DR COGS"),
                        new LineDraft(cashAccountId, BigDecimal.ZERO, new BigDecimal("60"), TZS, "CR Cash"))));

        assertThat(posted)
                .as("system/event-driven posters bypass per-account requiredness (MANUAL-only, ADR-0025)")
                .isNotNull();
        assertThat(posted.uid()).isNotBlank();
    }

    // =========================================================================
    // Bar 4: require_department enforced for MANUAL; satisfied with a department value
    // =========================================================================

    @Test
    void manualLine_requireDepartment_missingThenPresent() {
        ChartOfAccount acct = accountRepo.findById(cogsAccountId).orElseThrow();
        acct.setRequireDepartment(true);
        accountRepo.save(acct);

        // Missing → rejected
        assertThatThrownBy(() -> postingService.post(manualDraft(
                new LineDraft(cogsAccountId, new BigDecimal("80"), BigDecimal.ZERO, TZS, "DR COGS"),
                new LineDraft(cashAccountId, BigDecimal.ZERO, new BigDecimal("80"), TZS, "CR Cash"))))
                .as("require_department account must reject a MANUAL line with no department")
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("department");

        // Present → posts
        Long deptValueId = newDepartmentValueId("DEPT-FIN", "Finance");
        JournalEntryDto posted = postingService.post(manualDraft(
                new LineDraft(cogsAccountId, new BigDecimal("80"), BigDecimal.ZERO, TZS, "DR COGS",
                        null, deptValueId, null, null),
                new LineDraft(cashAccountId, BigDecimal.ZERO, new BigDecimal("80"), TZS, "CR Cash")));
        assertThat(posted.uid()).isNotBlank();
    }

    // =========================================================================
    // Bar 5: require_project enforced for MANUAL; satisfied with a project id
    // =========================================================================

    @Test
    void manualLine_requireProject_missingThenPresent() {
        ChartOfAccount acct = accountRepo.findById(cogsAccountId).orElseThrow();
        acct.setRequireProject(true);
        accountRepo.save(acct);

        // Missing → rejected
        assertThatThrownBy(() -> postingService.post(manualDraft(
                new LineDraft(cogsAccountId, new BigDecimal("90"), BigDecimal.ZERO, TZS, "DR COGS"),
                new LineDraft(cashAccountId, BigDecimal.ZERO, new BigDecimal("90"), TZS, "CR Cash"))))
                .as("require_project account must reject a MANUAL line with no project")
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("project");

        // Present → posts. projectId must reference a real project (journal_lines.project_id FK).
        Long projectId = newProjectId("Dim Req Project");
        JournalEntryDto posted = postingService.post(new JournalEntryDraft(
                company.getId(), branch.getId(), LocalDate.now(),
                "Manual posting with project tag",
                JournalSourceType.MANUAL, "REF-PROJ-001", null, rootId,
                List.of(
                        new LineDraft(cogsAccountId, new BigDecimal("90"), BigDecimal.ZERO, TZS, "DR COGS",
                                null, null, null, null, projectId, null, null),
                        new LineDraft(cashAccountId, BigDecimal.ZERO, new BigDecimal("90"), TZS, "CR Cash"))));
        assertThat(posted.uid()).isNotBlank();
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private void setRequireCostCentre(Long accountId, boolean required) {
        ChartOfAccount acct = accountRepo.findById(accountId).orElseThrow();
        acct.setRequireCostCentre(required);
        accountRepo.save(acct);
    }

    private Long newCostCentreValueId(String code, String name) {
        DimensionDto cc = dimBySlot(DimensionSlot.COST_CENTRE);
        DimensionValueDto v = dimensionService.createValue(
                new CreateDimensionValueRequest(cc.uid(), code, name, null));
        ResolvedDimensionTagDto resolved = dimensionResolver.resolveTag(
                company.getId(), new DimensionTagDto(v.uid(), null, null, null));
        return resolved.costCentreValueId();
    }

    private Long newDepartmentValueId(String code, String name) {
        DimensionDto dept = dimBySlot(DimensionSlot.DEPARTMENT);
        DimensionValueDto v = dimensionService.createValue(
                new CreateDimensionValueRequest(dept.uid(), code, name, null));
        ResolvedDimensionTagDto resolved = dimensionResolver.resolveTag(
                company.getId(), new DimensionTagDto(null, v.uid(), null, null));
        return resolved.departmentValueId();
    }

    private Long newProjectId(String name) {
        return projectService.create(
                new CreateProjectRequest(company.getUid(), branch.getUid(), name,
                        null, null, null, null, null, null),
                RequestContext.get()).id();
    }

    private DimensionDto dimBySlot(DimensionSlot slot) {
        return dimensionService.listDimensions(company.getId())
                .stream().filter(d -> d.slot() == slot)
                .findFirst().orElseThrow();
    }

    private JournalEntryDraft manualDraft(LineDraft drLine, LineDraft crLine) {
        return new JournalEntryDraft(
                company.getId(), branch.getId(), LocalDate.now(),
                "Manual dimension-requiredness test",
                JournalSourceType.MANUAL, null, null, rootId,
                List.of(drLine, crLine));
    }

    private Long accountId(String code) {
        return accountRepo.findByCompanyIdAndAccountCode(company.getId(), code)
                .map(ChartOfAccount::getId)
                .orElseThrow(() -> new AssertionError("Account " + code + " not seeded"));
    }
}
